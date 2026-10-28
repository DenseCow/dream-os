package com.firehome.launcher

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Reads AirPods battery from Apple's "proximity pairing" Bluetooth LE broadcast and can route TV
 * audio to already-paired AirPods.
 *
 * Two broadcast shapes matter:
 *  - the open-case message (25-byte payload, prefix 0x01): battery in plain 10% steps plus a
 *    16-byte block, AES-128-ECB encrypted with the AirPods' broadcast key, holding exact percentages;
 *  - the short message AirPods send while worn (17-byte payload: a subtype byte plus one 16-byte
 *    encrypted block: model id, then case / left / right battery as exact percentages).
 * Without the key only the open-case message is readable, so battery goes stale while the AirPods
 * are in use. With it (one-time setup, see [AirPodsKeyReceiver]) both decode and the values are exact.
 *
 * Scans in short bursts, filtered to Apple's broadcast, and only while started (the home screen
 * calls start/stop with its own visibility), so it costs nothing in the background. Scanning shares
 * the radio with A2DP and makes the AirPods stutter, so while any Bluetooth audio link is up we don't
 * scan at all, and a burst still running when one comes up stops at once.
 */
@SuppressLint("MissingPermission")
class AirPods(private val ctx: Context, private val onUpdate: (Status?) -> Unit) {

    /**
     * Battery is 0..100 (10% steps unless [exact]), or null when that part isn't reporting.
     * [seenAt] is wall-clock time.
     */
    class Status(
        val model: String,
        val left: Int?, val right: Int?, val case: Int?,
        val leftCharging: Boolean, val rightCharging: Boolean, val caseCharging: Boolean,
        val seenAt: Long,
        /** True when the percentages came out of the encrypted block (1% resolution). */
        val exact: Boolean = false,
    ) {
        val single get() = model == "AirPods Max"
        // Clamped so a clock that jumped backwards (e.g. before network time syncs) reads as fresh.
        val ageMs get() = (System.currentTimeMillis() - seenAt).coerceAtLeast(0)
        val recent get() = ageMs < RECENT_MS

        /** Same values as [o]; an exact and a 10%-step reading count as the same when the steps agree. */
        fun sameReading(o: Status?): Boolean {
            if (o == null || model != o.model) return false
            if (leftCharging != o.leftCharging || rightCharging != o.rightCharging || caseCharging != o.caseCharging) return false
            val fine = exact && o.exact
            fun same(a: Int?, b: Int?) = if (fine || a == null || b == null) a == b else a / 10 == b / 10
            return same(left, o.left) && same(right, o.right) && same(case, o.case)
        }

        fun refreshed(at: Long) = Status(model, left, right, case, leftCharging, rightCharging, caseCharging, at, exact)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var scanning = false
    private var scanStartedAt = 0L
    private val scanStarts = ArrayDeque<Long>()
    private val stopRunnable = Runnable { stopScan() }
    private var running = false
    private val prefs get() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Last readable broadcast, restored from disk so battery shows again right after a restart. */
    var last: Status? = load()
        private set
    private var lastRecent = last?.recent ?: false


    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getManufacturerSpecificData(APPLE) ?: return
            // Loose distance filter: the stick usually sits behind the TV, so even nearby AirPods arrive weak.
            if (result.rssi < MIN_RSSI) return
            val status = parse(data, key(ctx)) ?: return
            handler.post { publish(status) }
        }
    }

    private fun publish(incoming: Status) {
        var status = incoming
        val prev = last
        // A 10%-step reading that agrees with a recent exact one only confirms it: keep the exact numbers.
        if (prev != null && prev.exact && !status.exact && status.sameReading(prev) && status.seenAt - prev.seenAt in 0 until EXACT_KEEP_MS) {
            status = prev.refreshed(status.seenAt)
        }
        // An open case repeats the same reading several times a second: only redraw / save when it
        // changes, or once a minute so the "updated" age stays right.
        if (prev != null && status.sameReading(prev) && status.exact == prev.exact && status.seenAt - prev.seenAt in 0 until 60_000L) return
        last = status
        lastRecent = true
        save(status)
        onUpdate(status)
    }

    private val burst = object : Runnable {
        override fun run() {
            if (!running) return
            scanBurst(periodic = true)
            handler.postDelayed(this, PERIOD_MS)
            // Let the UI know once the reading goes stale (it may hide or dim the battery).
            last?.let { if (lastRecent && !it.recent) { lastRecent = false; onUpdate(it) } }
        }
    }

    fun start() {
        if (running || adapter == null) return
        running = true
        handler.post(burst)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(burst)
        handler.removeCallbacks(stopRunnable)
        stopScan("home hidden")
    }

    /** One extra burst right now (e.g. when the headphones panel opens or AirPods just connected). */
    fun refresh() {
        if (!running) return
        scanBurst(periodic = false)
    }

    private fun scanBurst(periodic: Boolean) {
        if (!startScan()) return
        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, BURST_MS)
    }

    private fun startScan(): Boolean {
        val a = adapter?.takeIf { it.isEnabled } ?: return false
        // Scanning shares the Bluetooth radio with audio and makes AirPods stutter, and while they are
        // the TV's audio output they don't send the readable battery message anyway (buds in the open
        // case = audio disconnected). So never scan while streaming.
        if (a.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED) {
            // A burst started just before the audio link came up (the home screen refreshes on connect) ends now.
            stopScan("audio link up")
            return false
        }
        val scanner = a.bluetoothLeScanner ?: return false
        if (scanning) return true
        // Stay under Android's limit of 5 scan starts per 30 s, past which scans silently return nothing.
        val now = SystemClock.elapsedRealtime()
        while (scanStarts.isNotEmpty() && now - scanStarts.first() > 30_000L) scanStarts.removeFirst()
        if (scanStarts.size >= 4) return false
        scanStarts.addLast(now)
        val filter = ScanFilter.Builder()
            .setManufacturerData(APPLE, byteArrayOf(PROXIMITY_PAIRING), byteArrayOf(0xFF.toByte()))
            .build()
        // Balanced mode during the short bursts; low-power mode listened too sparsely to catch AirPods.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        return try {
            scanner.startScan(listOf(filter), settings, callback)
            scanning = true
            scanStartedAt = now
            true
        } catch (e: Exception) {
            // Missing location permission or Bluetooth busy: just skip this burst.
            false
        }
    }

    private fun stopScan(why: String = "burst over") {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(callback) } catch (e: Exception) {}
        // One line per burst (only ever while no Bluetooth audio is up), so a cut-out can be matched against scans.
        Log.w("FireHome", "AirPods: BLE scan %.1f s (balanced, %s)".format((SystemClock.elapsedRealtime() - scanStartedAt) / 1000.0, why))
    }

    /** Model seen in the most recent readable broadcast, remembered across restarts. */
    val rememberedModel: String? get() = prefs.getString("model", null)

    private fun save(s: Status) {
        val flags = (if (s.leftCharging) 1 else 0) or (if (s.rightCharging) 2 else 0) or (if (s.caseCharging) 4 else 0) or (if (s.exact) 8 else 0)
        prefs.edit().apply {
            if (s.model != "AirPods") putString("model", s.model)
            putString("lastModel", s.model)
            putInt("left", s.left ?: -1).putInt("right", s.right ?: -1).putInt("case", s.case ?: -1)
            putInt("charging", flags)
            putLong("seenAt", s.seenAt)
        }.apply()
    }

    private fun load(): Status? {
        val p = prefs
        val seenAt = p.getLong("seenAt", 0L).takeIf { it > 0 } ?: return null
        fun pct(key: String) = p.getInt(key, -1).takeIf { it >= 0 }
        val flags = p.getInt("charging", 0)
        return Status(
            model = p.getString("lastModel", null) ?: "AirPods",
            left = pct("left"), right = pct("right"), case = pct("case"),
            leftCharging = flags and 1 != 0, rightCharging = flags and 2 != 0, caseCharging = flags and 4 != 0,
            seenAt = seenAt,
            exact = flags and 8 != 0,
        )
    }

    /** Calls [done] with the name of currently connected AirPods (A2DP), or null. */
    fun connectedName(done: (String?) -> Unit) {
        val a = adapter ?: return done(null)
        val ok = a.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val name = try {
                    proxy.connectedDevices.firstOrNull { (it.name ?: "").contains("AirPods", ignoreCase = true) }?.name
                } catch (e: Exception) {
                    null
                }
                a.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                handler.post { done(name) }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
        if (!ok) done(null)
    }

    /** Paired AirPods (classic Bluetooth), if any. */
    fun pairedDevice(): BluetoothDevice? = try {
        adapter?.bondedDevices?.firstOrNull { (it.name ?: "").contains("AirPods", ignoreCase = true) }
    } catch (e: Exception) {
        null
    }

    /** Routes TV audio to the paired AirPods. Returns false if they aren't paired yet. */
    fun connect(): Boolean = AirPodsLink.connect(ctx) // A2DP connect (hidden, allowed on Android 9), as Settings does it

    companion object {
        private const val TAG = "DreamAirPods"
        private const val PREFS = "airpods"
        private const val KEY_PREF = "broadcastKey"
        private const val APPLE = 0x004C
        private const val PROXIMITY_PAIRING: Byte = 0x07
        private const val OPEN_CASE_LENGTH = 25
        private const val MIN_RSSI = -92
        private const val BURST_MS = 6_000L
        private const val PERIOD_MS = 45_000L
        /** A 10%-step reading within this time of an exact one keeps the exact numbers. */
        private const val EXACT_KEEP_MS = 10 * 60_000L
        /** Readings older than this are "last known": shown only while the AirPods are connected. */
        const val RECENT_MS = 30 * 60_000L
        /** Even while connected, don't show a reading older than this; it's more misleading than useful. */
        const val MAX_AGE_MS = 24 * 60 * 60_000L

        // Codes per LibrePods / CAPod; 0x2720 (Pro 3) comes from a capture of a real unit.
        private val MODELS = mapOf(
            0x0220 to "AirPods", 0x0F20 to "AirPods 2", 0x1320 to "AirPods 3",
            0x1920 to "AirPods 4", 0x1B20 to "AirPods 4",
            0x0E20 to "AirPods Pro", 0x1420 to "AirPods Pro 2", 0x2420 to "AirPods Pro 2", 0x2720 to "AirPods Pro 3",
            0x0A20 to "AirPods Max", 0x1F20 to "AirPods Max", 0x2D20 to "AirPods Max 2",
        )
        private val loggedCodes = HashSet<Int>()
        private val loggedShapes = HashSet<String>()

        @Volatile private var cachedKey: ByteArray? = null
        @Volatile private var keyLoaded = false

        /** The AirPods' 16-byte broadcast ("proximity encryption") key, if the user has stored one. */
        fun key(ctx: Context): ByteArray? {
            if (!keyLoaded) {
                cachedKey = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PREF, null)?.let { unhex(it) }
                keyLoaded = true
            }
            return cachedKey
        }

        /** Stores [hex] (32 hex digits, separators ignored; "clear" removes it). Returns false if it isn't a key. */
        fun setKey(ctx: Context, hex: String): Boolean {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (hex.trim().equals("clear", ignoreCase = true)) {
                p.edit().remove(KEY_PREF).apply()
                cachedKey = null; keyLoaded = true
                return true
            }
            val clean = hex.filter { it.isLetterOrDigit() }
            val bytes = unhex(clean) ?: return false
            p.edit().putString(KEY_PREF, clean.lowercase()).apply()
            cachedKey = bytes; keyLoaded = true
            loggedShapes.clear()
            return true
        }

        private fun unhex(s: String): ByteArray? {
            if (s.length != 32 || !s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
            return ByteArray(16) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

        /** Decodes the proximity-pairing payload (bytes after Apple's company id), decrypting with [key] when given. */
        fun parse(d: ByteArray, key: ByteArray? = null): Status? {
            if (d.size < 3 || d[0] != PROXIMITY_PAIRING) return null
            val len = d[1].toInt() and 0x1F // the top bits of the length byte are flags
            if (d.size < 2 + len) return null
            val p = d.copyOfRange(2, 2 + len)
            val subtype = p[0].toInt() and 0xFF
            // One line per message shape so a logcat shows what these AirPods actually send.
            // One line per message shape so a logcat shows what these AirPods actually send.
            if (loggedShapes.add("$len/$subtype")) Log.w(TAG, "broadcast len=$len subtype=0x%02X".format(subtype))
            return when {
                subtype == 0x01 && len >= OPEN_CASE_LENGTH -> parseOpenCase(p, key)
                // AirPods 5 send this one in the clear: model (little-endian), then case / left / right.
                (subtype == 0x04 || subtype == 0x07) && len >= 7 -> batteryBlock(p, 1, subtype)
                len == 17 && key != null -> decrypt(key, p, 1)?.let { block ->
                    if (loggedShapes.add("dec/$len/$subtype")) Log.w(TAG, "decrypted subtype 0x%02X: %s".format(subtype, hex(block)))
                    batteryBlock(block, 0, subtype)
                }
                else -> null
            }
        }

        /** The open-case message: plain 10% steps, upgraded to exact values when the encrypted block checks out. */
        private fun parseOpenCase(p: ByteArray, key: ByteArray?): Status {
            val model = ((p[1].toInt() and 0xFF) shl 8) or (p[2].toInt() and 0xFF)
            val name = modelName(model)
            val status = p[3].toInt() and 0xFF
            val primaryLeft = status and 0x20 != 0 // which bud is "primary" swaps the nibbles and the block order
            val battHigh = (p[4].toInt() shr 4) and 0x0F
            val battLow = p[4].toInt() and 0x0F
            val flags = (p[5].toInt() shr 4) and 0x0F
            val caseNibble = p[5].toInt() and 0x0F
            fun pct(v: Int) = when (v) { in 0..10 -> v * 10; in 11..14 -> 100; else -> null }
            var left = pct(if (primaryLeft) battLow else battHigh)
            var right = pct(if (primaryLeft) battHigh else battLow)
            var case = pct(caseNibble)
            var leftCharging = flags and (if (primaryLeft) 0b0001 else 0b0010) != 0
            var rightCharging = flags and (if (primaryLeft) 0b0010 else 0b0001) != 0
            var caseCharging = flags and 0b0100 != 0
            var exact = false
            if (key != null && p.size >= OPEN_CASE_LENGTH) {
                // Block: [1] primary bud, [2] other bud, [3] case; bit 7 = charging, low 7 bits = percent, 0xFF = unknown.
                val block = decrypt(key, p, p.size - 16)
                if (block != null) {
                    if (loggedShapes.add("dec/open")) Log.w(TAG, "decrypted open-case block: %s".format(hex(block)))
                    val l = cell(block[if (primaryLeft) 1 else 2])
                    val r = cell(block[if (primaryLeft) 2 else 1])
                    val c = cell(block[3])
                    // A wrong key decrypts to noise: only trust values that agree with the plain 10% steps.
                    if (agrees(l, left) && agrees(r, right) && agrees(c, case) && (l != null || r != null)) {
                        l?.let { left = it.pct; leftCharging = it.charging }
                        r?.let { right = it.pct; rightCharging = it.charging }
                        c?.let { case = it.pct; caseCharging = it.charging }
                        exact = true
                    } else if (loggedShapes.add("mismatch")) {
                        Log.w(TAG, "decrypted block disagrees with the plain values; is the key right?")
                    }
                }
            }
            return Status(name, left, right, case, leftCharging, rightCharging, caseCharging, System.currentTimeMillis(), exact)
        }

        /**
         * A battery block as found in the short messages: model id little-endian at [off], then
         * [off+3] case, [off+4] left, [off+5] right (bit 7 = charging, 0xFF = not reporting). The model
         * id doubles as the check that the block (or the key that produced it) is genuine.
         */
        private fun batteryBlock(b: ByteArray, off: Int, subtype: Int): Status? {
            if (b.size < off + 6) return null
            val model = ((b[off + 1].toInt() and 0xFF) shl 8) or (b[off].toInt() and 0xFF)
            val name = MODELS[model]
            if (name == null) {
                if (loggedCodes.add(model)) Log.w(TAG, "short broadcast (subtype 0x%02X) with unknown model 0x%04X; ignoring".format(subtype, model))
                return null
            }
            val raw = (3..5).map { b[off + it].toInt() and 0xFF }
            if (raw.any { it != 0xFF && it and 0x7F > 100 }) return null
            val c = cell(b[off + 3]); val l = cell(b[off + 4]); val r = cell(b[off + 5])
            if (l == null && r == null && c == null) return null
            return Status(
                name, l?.pct, r?.pct, c?.pct,
                l?.charging ?: false, r?.charging ?: false, c?.charging ?: false,
                System.currentTimeMillis(), exact = true,
            )
        }

        private class Cell(val pct: Int, val charging: Boolean)

        private fun cell(b: Byte): Cell? {
            val v = b.toInt() and 0xFF
            if (v == 0xFF) return null
            return Cell((v and 0x7F).coerceAtMost(100), v and 0x80 != 0)
        }

        /** An exact value agrees with a 10% step when it lies within one step of it. */
        private fun agrees(exact: Cell?, coarse: Int?): Boolean {
            if (exact == null) return true
            if (coarse == null) return false
            return kotlin.math.abs(exact.pct - coarse) <= 10
        }

        private fun decrypt(key: ByteArray, data: ByteArray, offset: Int): ByteArray? {
            if (key.size != 16 || offset < 0 || data.size < offset + 16) return null
            return try {
                val cipher = Cipher.getInstance("AES/ECB/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
                cipher.doFinal(data, offset, 16)
            } catch (e: Exception) {
                null
            }
        }

        private fun modelName(code: Int) = MODELS[code] ?: "AirPods".also {
            if (loggedCodes.add(code)) Log.w(TAG, "unknown AirPods model code 0x%04X".format(code))
        }

        /** True if [intent] reports an A2DP device named like AirPods just connected. */
        fun isAirPodsConnected(intent: Intent) = isAirPods(intent, BluetoothProfile.STATE_CONNECTED)

        /** True if AirPods just disconnected (put in the case, powered down, out of range). */
        fun isAirPodsDisconnected(intent: Intent) = isAirPods(intent, BluetoothProfile.STATE_DISCONNECTED)

        private fun isAirPods(intent: Intent, state: Int): Boolean {
            if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) != state) return false
            return isAirPods(intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE))
        }

        private fun isAirPods(d: BluetoothDevice?) =
            try { (d?.name ?: "").contains("AirPods", ignoreCase = true) } catch (e: Exception) { false }

        const val ACTION_CONNECTION = BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED
    }
}

/**
 * One-time setup of the AirPods broadcast key (the "ENC_KEY" / proximity encryption key, 16 bytes),
 * which lets the launcher read battery while the AirPods are worn. From the PC:
 *   adb shell am broadcast -n com.firehome.launcher/.AirPodsKeyReceiver --es key 0011223344556677889900AABBCCDDEEFF
 * (--es key clear forgets it; no extra just logs whether one is stored). Only adb (DUMP) may send it.
 */
class AirPodsKeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val hex = intent.getStringExtra("key")
        if (hex == null) {
            Log.w("DreamAirPods", "broadcast key stored: ${AirPods.key(context) != null}")
            return
        }
        Log.w("DreamAirPods", if (AirPods.setKey(context, hex)) "broadcast key saved" else "not a key: need 32 hex digits (or 'clear')")
    }
}
