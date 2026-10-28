package com.firehome.launcher

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Moves the paired AirPods between the TV and the iPhone, keeps listening statistics, and owns the
 * AirPods options that aren't theme settings. Process-wide (application context, main thread only):
 * the home screen calls [init] once; the AirPods panel and the phone remote use the rest.
 *
 * "Send to iPhone" only drops the TV's audio (A2DP) and call (HFP) links; the AirPods stay paired,
 * so the iPhone picks them up (automatically, or from Control Center) and "Bring to TV" reconnects.
 * Never BLE-scans: that stutters AirPods audio on this stick.
 */
@SuppressLint("MissingPermission")
object AirPodsLink {

    private const val TAG = "DreamAirPods"
    private const val PREFS = "airpods_link"
    /** Hidden BluetoothA2dp action (Android 8+): the stream's codec was (re)negotiated. */
    private const val ACTION_CODEC = "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"
    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val DISCONNECT_TIMEOUT_MS = 8_000L
    private const val PROXY_TIMEOUT_MS = 3_000L
    /** Listening time is written to disk this often while connected, so a crash loses at most this much. */
    private const val CHECKPOINT_MS = 60_000L
    private const val KEEP_DAYS = 14
    private const val DAY_MS = 24 * 60 * 60_000L

    const val FAIL_CONNECT = "Couldn't connect: put them in your ears or open the case near the TV"

    class Result(val ok: Boolean, val message: String)

    /** What the stick knows about the link right now. [codec] is null when Android won't say. */
    class Info(val device: BluetoothDevice?, val connected: Boolean, val streaming: Boolean, val callAudio: Boolean, val codec: String?)

    class Stats(
        val todayMs: Long, val weekMs: Long, val allMs: Long,
        val connections: Int,
        /** Length of the current connection, or null when not connected. */
        val sessionMs: Long?,
        val lastConnected: Long, val lastDisconnected: Long,
        val since: Long,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private val listeners = LinkedHashSet<() -> Unit>()
    /** An in-flight connect / disconnect: [check] maps each AirPods A2DP state change to its outcome (null = keep waiting). */
    private class Waiter(val done: (Result) -> Unit, val check: (Int) -> Result?)
    private var waiter: Waiter? = null
    private var waiterTimeout: Runnable? = null

    /** True while AirPods are the TV's audio output (as far as the broadcasts and the startup check tell). */
    var connected = false
        private set
    /** Wall time of the last "Send to iPhone", for the panel's hint. */
    var sentToPhoneAt = 0L
        private set

    /** The AirPods left because of "Send to iPhone" moments ago (not put away). */
    fun justSentToPhone() = System.currentTimeMillis() - sentToPhoneAt in 0..20_000L

    private val adapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- options ----

    fun pauseOnDisconnect(ctx: Context) = prefs(ctx).getBoolean("pauseOnDisconnect", false)
    fun setPauseOnDisconnect(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("pauseOnDisconnect", on).apply()

    /** Dream OS connects paired AirPods while it starts (so the intro plays in them). On by default. */
    fun autoConnect(ctx: Context) = prefs(ctx).getBoolean("autoConnect", true)
    fun setAutoConnect(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("autoConnect", on).apply()

    // ---- lifecycle ----

    /** Starts tracking (idempotent). Registers on the application context, so it outlives any Activity. */
    fun init(ctx: Context) {
        if (app != null) return
        val a = ctx.applicationContext
        app = a
        a.registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
            addAction(ACTION_CODEC)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) // logged only: a call link setting up mid-stream can hiccup the audio
        })
        reconcile(a)
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { for (l in listeners.toList()) try { l() } catch (e: Exception) { Log.w(TAG, "listener: $e") } }

    /** Paired AirPods (classic Bluetooth), if any. */
    fun paired(): BluetoothDevice? = try {
        adapter?.bondedDevices?.firstOrNull { isAirPods(it) }
    } catch (e: Exception) {
        null
    }

    private fun isAirPods(d: BluetoothDevice?) = try { (d?.name ?: "").contains("AirPods", ignoreCase = true) } catch (e: Exception) { false }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (!isAirPods(d)) return
                    val state = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    val prev = i.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
                    linkLog(c, "audio link (A2DP)", state)
                    when (state) {
                        BluetoothProfile.STATE_CONNECTED -> onConnected(c)
                        BluetoothProfile.STATE_DISCONNECTED -> onDisconnected(c, wasConnected = prev == BluetoothProfile.STATE_CONNECTED || prev == BluetoothProfile.STATE_DISCONNECTING)
                    }
                    waiter?.let { w -> w.check(state)?.let { r -> clearWaiter(); w.done(r) } }
                    changed()
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    // Bluetooth switched off: no disconnect broadcast follows, so close the session here.
                    if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_TURNING_OFF && connected) {
                        onDisconnected(c, wasConnected = true)
                        changed()
                    }
                }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    if (isAirPods(i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE))) {
                        linkLog(c, "call link (HFP)", i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1))
                        changed() // the panel shows call audio too
                    }
                }
                else -> {
                    if (isAirPods(i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE))) {
                        if (i.action == ACTION_CODEC) {
                            // The codec status' text holds "codecName:AAC" first (the one in use).
                            val codec = Regex("codecName:(\\w+)").find(i.extras?.get("android.bluetooth.codec.extra.CODEC_STATUS")?.toString() ?: "")?.groupValues?.get(1)
                            Log.w(FH, "AirPods: codec ${codec ?: "changed"}")
                        } else {
                            val playing = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothA2dp.STATE_PLAYING
                            Log.w(FH, "Audio: Bluetooth stream ${if (playing) "started" else "stopped"}")
                        }
                    }
                    changed() // playing state or codec: the panel shows both
                }
            }
        }
    }

    private const val FH = "FireHome"

    /** "AirPods: call link (HFP) connected · audio link up · Wi-Fi 5.6 GHz": profile changes, to match against cut-outs. */
    private fun linkLog(c: Context, what: String, state: Int) {
        val s = when (state) {
            BluetoothProfile.STATE_CONNECTED -> "connected"
            BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
            else -> return // connecting / disconnecting: the outcome follows
        }
        Log.w(FH, "AirPods: $what $s · audio link ${if (BtAudio.active()) "up" else "down"} · ${BtAudio.wifi(c)}")
    }

    private fun onConnected(c: Context) {
        if (connected) return
        connected = true
        openSession(c, count = true)
    }

    private fun onDisconnected(c: Context, wasConnected: Boolean) {
        val was = connected
        connected = false
        if (was) closeSession(c)
        if ((was || wasConnected) && pauseOnDisconnect(c)) pausePlayback(c)
    }

    /** Media PAUSE (never play/pause, so it can't start anything) to whichever app owns the media session. */
    private fun pausePlayback(c: Context) {
        try {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            Log.w(TAG, "AirPods disconnected: paused playback")
        } catch (e: Exception) {
            Log.w(TAG, "pause failed: $e")
        }
    }

    /** Startup: learn whether the AirPods are already connected and repair a session left open by a restart. */
    private fun reconcile(c: Context) {
        withProxy(c, BluetoothProfile.A2DP) { proxy ->
            val on = try { proxy?.connectedDevices?.any { isAirPods(it) } ?: false } catch (e: Exception) { false }
            val p = prefs(c)
            val open = p.getLong("sessStartWall", 0L) > 0
            val sameBoot = open && p.getInt("ckBoot", -1) == bootCount(c) && p.getLong("ckRt", Long.MAX_VALUE) <= SystemClock.elapsedRealtime()
            when {
                on && open && sameBoot -> { connected = true; flush(c); startCheckpoints() } // process restarted mid-session
                on -> { if (open) closeStale(c); connected = true; openSession(c, count = true) }
                open -> closeStale(c)
            }
            changed()
        }
    }

    // ---- moving the AirPods ----

    /** Routes TV audio to the paired AirPods (A2DP connect, hidden but allowed on Android 9). False if not paired. */
    fun connect(ctx: Context): Boolean {
        val device = paired() ?: return false
        withProxy(ctx, BluetoothProfile.A2DP) { proxy -> if (proxy != null) invoke(proxy, "connect", device) }
        return true
    }

    /** Connects and reports the outcome once the AirPods connect, the attempt fails, or it times out. */
    fun bringToTv(ctx: Context, done: (Result) -> Unit) {
        val a = adapter
        if (a == null || !a.isEnabled) return done(Result(false, "Bluetooth is off"))
        val device = paired() ?: return done(Result(false, "AirPods aren't paired with this TV yet"))
        supersede()
        withProxy(ctx, BluetoothProfile.A2DP) { proxy ->
            if (proxy == null) return@withProxy done(Result(false, "Bluetooth isn't ready, try again"))
            val state = try { proxy.getConnectionState(device) } catch (e: Exception) { -1 }
            if (state == BluetoothProfile.STATE_CONNECTED) {
                if (!connected) { onConnected(ctx); changed() }
                return@withProxy done(Result(true, "Already connected to the TV"))
            }
            if (invoke(proxy, "connect", device) == false && state != BluetoothProfile.STATE_CONNECTING) {
                return@withProxy done(Result(false, FAIL_CONNECT))
            }
            await(CONNECT_TIMEOUT_MS, done, Result(false, FAIL_CONNECT)) { s ->
                when (s) {
                    BluetoothProfile.STATE_CONNECTED -> Result(true, "Connected")
                    BluetoothProfile.STATE_DISCONNECTED -> Result(false, FAIL_CONNECT)
                    else -> null
                }
            }
        }
    }

    /**
     * Lets go of the AirPods (A2DP and, if up, HFP) without unpairing, so the iPhone can take them.
     * Reports once A2DP is down or after a timeout.
     */
    fun sendToPhone(ctx: Context, done: (Result) -> Unit) {
        val device = paired() ?: return done(Result(false, "AirPods aren't paired with this TV"))
        val a = adapter
        if (a == null || !a.isEnabled) return done(Result(true, "Bluetooth is off, so they're free for your iPhone"))
        supersede()
        // Calls: drop the headset link too, or the AirPods may stay half-attached to the TV.
        withProxy(ctx, BluetoothProfile.HEADSET) { hfp ->
            if (hfp != null) try {
                val s = hfp.getConnectionState(device)
                if (s == BluetoothProfile.STATE_CONNECTED || s == BluetoothProfile.STATE_CONNECTING) invoke(hfp, "disconnect", device)
            } catch (e: Exception) {
                Log.w(TAG, "HFP disconnect: $e")
            }
        }
        withProxy(ctx, BluetoothProfile.A2DP) { proxy ->
            if (proxy == null) return@withProxy done(Result(false, "Bluetooth isn't ready, try again"))
            val state = try { proxy.getConnectionState(device) } catch (e: Exception) { -1 }
            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                sentToPhoneAt = System.currentTimeMillis()
                if (connected) { onDisconnected(ctx, wasConnected = false); changed() }
                return@withProxy done(Result(true, "Not connected to the TV, so they're free for your iPhone"))
            }
            if (invoke(proxy, "disconnect", device) == false) return@withProxy done(Result(false, "The TV couldn't let go of them; try again"))
            sentToPhoneAt = System.currentTimeMillis()
            await(DISCONNECT_TIMEOUT_MS, done, Result(false, "The TV is still holding on to them; try again")) { s ->
                if (s == BluetoothProfile.STATE_DISCONNECTED) Result(true, "Sent: your iPhone can take them now") else null
            }
        }
    }

    /** Waits for the A2DP state change that ends an operation: [check] maps a state to the outcome, or null to keep waiting. */
    private fun await(timeoutMs: Long, done: (Result) -> Unit, timeout: Result, check: (Int) -> Result?) {
        val w = Waiter(done, check)
        waiter = w
        val t = Runnable { if (waiter === w) { clearWaiter(); done(timeout) } }
        waiterTimeout = t
        handler.postDelayed(t, timeoutMs)
    }

    /** A newer request (say from the phone remote) replaces a pending one; the older caller still hears back. */
    private fun supersede() {
        val w = waiter ?: return
        clearWaiter()
        w.done(Result(false, "Replaced by a newer request"))
    }

    private fun clearWaiter() {
        waiter = null
        waiterTimeout?.let { handler.removeCallbacks(it) }
        waiterTimeout = null
    }

    /** Hidden profile method taking a device (connect / disconnect); null if it couldn't be called. */
    private fun invoke(proxy: BluetoothProfile, method: String, device: BluetoothDevice): Boolean? = try {
        proxy.javaClass.getMethod(method, BluetoothDevice::class.java).invoke(proxy, device) as? Boolean
    } catch (e: Exception) {
        Log.w(TAG, "$method via ${proxy.javaClass.simpleName} failed: $e")
        null
    }

    /** Runs [block] on the main thread with a profile proxy (null if unavailable), then closes the proxy. */
    private fun withProxy(ctx: Context, profile: Int, block: (BluetoothProfile?) -> Unit) {
        val a = adapter ?: return block(null)
        var finished = false
        val timeout = Runnable { if (!finished) { finished = true; block(null) } }
        val ok = try {
            a.getProfileProxy(ctx.applicationContext, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    handler.post {
                        handler.removeCallbacks(timeout)
                        if (!finished) {
                            finished = true
                            try { block(proxy) } catch (e: Exception) { Log.w(TAG, "proxy block: $e") }
                        }
                        try { a.closeProfileProxy(p, proxy) } catch (e: Exception) {}
                    }
                }

                override fun onServiceDisconnected(p: Int) {}
            }, profile)
        } catch (e: Exception) {
            false
        }
        if (!ok) { finished = true; block(null) } else handler.postDelayed(timeout, PROXY_TIMEOUT_MS)
    }

    // ---- live info (codec etc.) ----

    /** Connection, streaming and codec details for the panel, read through the A2DP / headset proxies. */
    fun info(ctx: Context, done: (Info) -> Unit) {
        val device = paired()
        if (device == null || adapter?.isEnabled != true) return done(Info(device, false, false, false, null))
        withProxy(ctx, BluetoothProfile.HEADSET) { hfp ->
            val call = try { hfp?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED } catch (e: Exception) { false }
            withProxy(ctx, BluetoothProfile.A2DP) { proxy ->
                val a2dp = proxy as? BluetoothA2dp
                val on = try { a2dp?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED } catch (e: Exception) { false }
                val playing = on && try { a2dp?.isA2dpPlaying(device) == true } catch (e: Exception) { false }
                done(Info(device, on, playing, call, if (on && a2dp != null) codec(a2dp, device) else null))
            }
        }
    }

    /** "AAC · 44.1 kHz · 16-bit · Stereo" from the hidden getCodecStatus(device); null if Android won't say. */
    private fun codec(a2dp: BluetoothA2dp, device: BluetoothDevice): String? = try {
        val status = BluetoothA2dp::class.java.getMethod("getCodecStatus", BluetoothDevice::class.java).invoke(a2dp, device)
        val config = status?.javaClass?.getMethod("getCodecConfig")?.invoke(status)
        if (config == null) null else {
            fun int(m: String) = try { config.javaClass.getMethod(m).invoke(config) as? Int } catch (e: Exception) { null }
            val name = when (int("getCodecType")) {
                0 -> "SBC"; 1 -> "AAC"; 2 -> "aptX"; 3 -> "aptX HD"; 4 -> "LDAC"
                else -> null
            }
            val rate = when (int("getSampleRate")) {
                0x1 -> "44.1 kHz"; 0x2 -> "48 kHz"; 0x4 -> "88.2 kHz"; 0x8 -> "96 kHz"; 0x10 -> "176.4 kHz"; 0x20 -> "192 kHz"
                else -> null
            }
            val bits = when (int("getBitsPerSample")) { 0x1 -> "16-bit"; 0x2 -> "24-bit"; 0x4 -> "32-bit"; else -> null }
            val channels = when (int("getChannelMode")) { 0x1 -> "Mono"; 0x2 -> "Stereo"; else -> null }
            name?.let { listOfNotNull(it, rate, bits, channels).joinToString(" · ") }
        }
    } catch (e: Exception) {
        Log.w(TAG, "codec status unavailable: $e")
        null
    }

    // ---- listening statistics ----
    //
    // A session runs from A2DP connect to disconnect. Time is measured on elapsedRealtime (immune to
    // clock changes) and booked onto local calendar days at each checkpoint, so today / the last 7
    // days / all time stay right across midnight, restarts and crashes.

    private val checkpoint = object : Runnable {
        override fun run() {
            val c = app ?: return
            if (!connected) return
            flush(c)
            handler.postDelayed(this, CHECKPOINT_MS)
        }
    }

    private fun startCheckpoints() {
        handler.removeCallbacks(checkpoint)
        handler.postDelayed(checkpoint, CHECKPOINT_MS)
    }

    private fun bootCount(c: Context) = try { Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, 0) } catch (e: Exception) { 0 }

    private fun openSession(c: Context, count: Boolean) {
        val now = System.currentTimeMillis()
        val rt = SystemClock.elapsedRealtime()
        val p = prefs(c)
        p.edit().apply {
            putLong("sessStartWall", now).putLong("sessStartRt", rt)
            putLong("ckRt", rt).putLong("ckWall", now).putInt("ckBoot", bootCount(c))
            putLong("lastOn", now)
            if (count) putInt("count", p.getInt("count", 0) + 1)
            if (p.getLong("since", 0L) == 0L) putLong("since", now)
        }.apply()
        startCheckpoints()
    }

    private fun closeSession(c: Context) {
        flush(c)
        handler.removeCallbacks(checkpoint)
        prefs(c).edit().putLong("lastOff", System.currentTimeMillis()).remove("sessStartWall").remove("sessStartRt").remove("ckRt").apply()
    }

    /** A session left open by a crash or reboot: its time was booked up to the last checkpoint; end it there. */
    private fun closeStale(c: Context) {
        val p = prefs(c)
        p.edit().putLong("lastOff", p.getLong("ckWall", System.currentTimeMillis()))
            .remove("sessStartWall").remove("sessStartRt").remove("ckRt").apply()
    }

    /** Books the time since the last checkpoint onto the calendar days it fell on. */
    private fun flush(c: Context) {
        val p = prefs(c)
        if (p.getLong("sessStartWall", 0L) == 0L) return
        val rt = SystemClock.elapsedRealtime()
        val ck = p.getLong("ckRt", rt)
        val delta = (rt - ck).coerceIn(0L, DAY_MS) // a gap longer than a day isn't believable listening
        val now = System.currentTimeMillis()
        val days = loadDays(p.getString("days", null))
        var end = now
        var left = delta
        while (left > 0) {
            // The day holding the millisecond just before [end] (so a span ending at midnight books the day before).
            val part = minOf(left, end - dayStart(end - 1))
            val k = dayKey(end - 1)
            days[k] = (days[k] ?: 0L) + part
            left -= part
            end -= part
        }
        // Drop days older than two weeks.
        val oldest = dayKey(now - KEEP_DAYS * DAY_MS)
        days.keys.filter { it < oldest }.forEach { days.remove(it) }
        p.edit().putLong("ckRt", rt).putLong("ckWall", now)
            .putLong("allMs", p.getLong("allMs", 0L) + delta)
            .putString("days", days.entries.joinToString(";") { "${it.key}=${it.value}" })
            .apply()
    }

    fun stats(ctx: Context): Stats {
        val c = ctx.applicationContext
        if (connected) flush(c)
        val p = prefs(c)
        val days = loadDays(p.getString("days", null))
        val now = System.currentTimeMillis()
        val today = dayKey(now)
        val weekStart = dayKey(now - 6 * DAY_MS)
        val startRt = p.getLong("sessStartRt", 0L)
        val session = if (connected && startRt > 0) (SystemClock.elapsedRealtime() - startRt).coerceAtLeast(0L) else null
        return Stats(
            todayMs = days[today] ?: 0L,
            weekMs = days.filterKeys { it in weekStart..today }.values.sum(),
            allMs = p.getLong("allMs", 0L),
            connections = p.getInt("count", 0),
            sessionMs = session,
            lastConnected = p.getLong("lastOn", 0L),
            lastDisconnected = p.getLong("lastOff", 0L),
            since = p.getLong("since", 0L),
        )
    }

    private fun loadDays(s: String?): MutableMap<String, Long> {
        val m = HashMap<String, Long>()
        s?.split(';')?.forEach { e ->
            val i = e.indexOf('=')
            if (i > 0) e.substring(i + 1).toLongOrNull()?.let { m[e.substring(0, i)] = it }
        }
        return m
    }

    private fun dayKey(t: Long) = DreamTime.fmt("yyyyMMdd").format(Date(t))

    private fun dayStart(t: Long) = DreamTime.calendar().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
