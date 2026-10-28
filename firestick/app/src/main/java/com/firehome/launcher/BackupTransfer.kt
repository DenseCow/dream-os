package com.firehome.launcher

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Move to a New Stick: the old Fire TV sends everything (DataMigration's sealed export of profiles,
 * accounts, scrapers, lists and settings) straight to the new one over the home Wi-Fi.
 *
 * Old TV ("Back Up Dream OS" in Settings, [Sender]): makes the sealed export under a fresh random key,
 * then for at most [WINDOW_MS] runs a small server on [PORT] (LAN addresses only), announced on the
 * network as `_dreamos._tcp`, and shows a one-time 6-character code and its address.
 *
 * New TV ("Restore From Backup", [Finder] + [receive]): finds the old TV (or takes its typed address),
 * asks for the code, then:
 *   1. sends a fresh P-256 public key with a proof that it knows the code (HMAC under a PBKDF2 key of
 *      the code). The old TV checks it in constant time; 5 wrong codes and the code on screen changes;
 *   2. the old TV answers with its own fresh public key, its own proof (so the new TV knows it's
 *      really the TV showing the code), and the export's key wrapped (AES-GCM) under a key from the
 *      ECDH secret and the code: someone watching the Wi-Fi learns nothing, and without the code
 *      nobody can pose as either TV; then streams the sealed export;
 *   3. the new TV imports it (DataMigration checks the whole file first), and restarts.
 * One successful send and the old TV's server stops; it also stops when its page closes.
 *
 * Plain sockets on both ends (Android's cleartext rules apply to its HTTP clients, not to this), one
 * request per connection.
 */
internal object BackupTransfer {
    private const val TAG = "FireHome"
    const val PORT = 8687
    private const val SERVICE = "_dreamos._tcp"
    private const val CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val CODE_LEN = 6
    const val WINDOW_MS = 10 * 60_000L
    private const val MAX_FAILS = 5
    private const val ITER = 20_000
    private val SALT = "dreamos-backup-v1".toByteArray()

    fun normalize(code: String) = code.uppercase().filter { it in CODE_CHARS }

    private fun newCode(): String {
        val r = SecureRandom()
        return (1..CODE_LEN).map { CODE_CHARS[r.nextInt(CODE_CHARS.length)] }.joinToString("")
    }

    private fun codeKey(code: String): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(normalize(code).toCharArray(), SALT, ITER, 256)).encoded

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) { update(p.size.toString().toByteArray()); update(':'.code.toByte()); update(p) }
        doFinal()
    }

    private fun ecPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun ecdh(priv: PrivateKey, pub: ByteArray): ByteArray {
        val p = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pub))
        return KeyAgreement.getInstance("ECDH").run { init(priv); doPhase(p, true); generateSecret() }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** This TV's name as Fire TV shows it ("Living Room Fire TV"), else its model. */
    fun deviceName(ctx: Context): String = try {
        android.provider.Settings.Global.getString(ctx.contentResolver, "device_name")?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) { null } ?: "Fire TV (${Build.MODEL})"

    /** What a backup of this TV carries, in plain words ("2 Profiles · Real-Debrid · 1 Scraper Repository · Trakt"). */
    fun summary(ctx: Context): String {
        val parts = ArrayList<String>()
        try { Profiles.all(ctx).size.let { parts += if (it == 1) "1 Profile" else "$it Profiles" } } catch (e: Exception) {}
        try { DreamAccounts.linkedInOrder(ctx).map { AccountHealth.name(it) }.takeIf { it.isNotEmpty() }?.let { parts += it.joinToString(", ") } } catch (e: Exception) {}
        try { Scrapers.status(ctx).repos.size.takeIf { it > 0 }?.let { parts += if (it == 1) "1 Scraper Repository" else "$it Scraper Repositories" } } catch (e: Exception) {}
        try { if (Trakt.signedIn(ctx)) parts += "Trakt" } catch (e: Exception) {}
        parts += "Your Lists and Settings"
        return parts.joinToString("  ·  ")
    }

    private fun isLan(a: InetAddress) = a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress

    // =================================================================================
    // Old TV: the sender
    // =================================================================================

    enum class Phase { PREPARING, READY, SENDING, SENT, FAILED, EXPIRED }

    class State(val phase: Phase, val code: String? = null, val address: String? = null, val done: Long = 0, val total: Long = 0, val message: String? = null)

    /** One send session. [onState] runs on the main thread. Start once; [stop] ends it (and deletes the export). */
    class Sender(ctx: Context, private val onState: (State) -> Unit) {
        private val app = ctx.applicationContext
        private val main = Handler(Looper.getMainLooper())
        private val file = File(app.cacheDir, "dreamos-backup.bin")
        @Volatile private var stopped = false
        @Volatile private var server: ServerSocket? = null
        private var code = ""
        private var key = ByteArray(0) // PBKDF2 of [code]
        private var fails = 0
        private var exportKey = ""
        private var summary = ""
        private var address = ""
        private var nsd: NsdManager? = null
        private var reg: NsdManager.RegistrationListener? = null
        private val expire = Runnable { if (!stopped) { Log.w(TAG, "Backup: send window over"); finish(State(Phase.EXPIRED)) } }

        /** Posted while the session is live (its final state included); nothing after [stop] from the page. */
        private fun post(s: State) { val live = !stopped; main.post { if (live) onState(s) } }

        fun start() {
            post(State(Phase.PREPARING))
            Thread({ run() }, "backup-sender").apply { isDaemon = true }.start()
        }

        private fun run() {
            val ip = PhoneRemote.localIp()
            if (ip == null) { fail("Connect this TV to Wi-Fi first, then try again."); return }
            val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
            exportKey = hex(k)
            summary = summary(app)
            val t0 = SystemClock.elapsedRealtime()
            if (!DataMigration.export(app, exportKey, file) || stopped) {
                file.delete()
                if (!stopped) fail("Dream OS couldn't make the backup. Make sure this TV has free space, then try again.")
                return
            }
            Log.w(TAG, "Backup: export ready, ${file.length() / 1024} KB in ${SystemClock.elapsedRealtime() - t0} ms")
            val ss = (0..3).firstNotNullOfOrNull { i ->
                try { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT + i)) } } catch (e: IOException) { null }
            }
            if (ss == null) { fail("Dream OS couldn't start the transfer. Try again."); return }
            server = ss
            if (stopped) { close(ss); return }
            address = if (ss.localPort == PORT) ip else "$ip:${ss.localPort}"
            synchronized(this) { rotate() }
            advertise(ss.localPort)
            main.postDelayed(expire, WINDOW_MS)
            Log.w(TAG, "Backup: waiting for the new TV on $address")
            while (!stopped && !ss.isClosed) {
                val c = try { ss.accept() } catch (e: IOException) { break }
                try { handle(c) } catch (e: Throwable) { Log.w(TAG, "Backup: request failed: $e") } finally { close(c) }
            }
        }

        /** A new code on screen (at the start, and after too many wrong ones). */
        private fun rotate() {
            code = newCode()
            key = codeKey(code)
            fails = 0
            post(State(Phase.READY, code, address))
        }

        private fun handle(sock: Socket) {
            if (!isLan(sock.inetAddress)) { Log.w(TAG, "Backup: refused a request from outside the home network"); return }
            sock.soTimeout = 10_000
            val input = BufferedInputStream(sock.getInputStream())
            val head = readHead(input) ?: return
            val out = sock.getOutputStream()
            if (head.line != "POST /backup HTTP/1.1") { reply(out, 404, "Not found"); return }
            val len = head.headers["content-length"]?.toIntOrNull() ?: -1
            if (len !in 1..4096) { reply(out, 400, "Bad request"); return }
            val req = try { JSONObject(String(readN(input, len), Charsets.UTF_8)) } catch (e: Exception) { reply(out, 400, "Bad request"); return }
            val cPub = try { unb64(req.getString("pub")) } catch (e: Exception) { reply(out, 400, "Bad request"); return }
            val proof = try { unhex(req.getString("proof")) } catch (e: Exception) { reply(out, 400, "Bad request"); return }
            val k = synchronized(this) { key }
            if (!MessageDigest.isEqual(hmac(k, "client".toByteArray(), cPub), proof)) {
                val n = synchronized(this) { ++fails }
                Log.w(TAG, "Backup: wrong code ($n)")
                Thread.sleep(1000)
                if (n >= MAX_FAILS) synchronized(this) { Log.w(TAG, "Backup: too many wrong codes, new code"); rotate() }
                reply(out, 403, "Wrong code")
                return
            }
            // The right code: wrap the export's key for this TV only.
            val mine = ecPair()
            val sPub = mine.public.encoded
            val wrapKey = hmac(k, "wrap".toByteArray(), ecdh(mine.private, cPub), cPub, sPub)
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(128, iv))
                doFinal(JSONObject().put("key", exportKey).put("summary", summary).put("from", deviceName(app)).toString().toByteArray())
            }
            val total = file.length()
            Log.w(TAG, "Backup: code accepted, sending ${total / 1024} KB to ${sock.inetAddress.hostAddress}")
            sock.soTimeout = 60_000
            val h = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: $total\r\n" +
                "X-Dream-Pub: ${b64(sPub)}\r\nX-Dream-Proof: ${hex(hmac(k, "server".toByteArray(), sPub, cPub))}\r\n" +
                "X-Dream-Key: ${b64(iv + sealed)}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            out.write(h.toByteArray())
            var sent = 0L
            var shown = 0L
            try {
                file.inputStream().use { f ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = f.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - shown > 250) { shown = now; post(State(Phase.SENDING, code, address, sent, total)) }
                    }
                }
                out.flush()
            } catch (e: IOException) {
                Log.w(TAG, "Backup: send cut off after ${sent / 1024} KB: $e")
                post(State(Phase.READY, code, address, message = "The transfer stopped. Try again on your new TV."))
                return
            }
            Log.w(TAG, "Backup: sent")
            finish(State(Phase.SENT, total = total, message = summary))
        }

        private fun fail(message: String) { Log.w(TAG, "Backup: $message"); finish(State(Phase.FAILED, message = message)) }

        private fun finish(s: State) {
            post(s)
            stop()
        }

        /** Ends the session: the server closes, the announcement goes, the export is deleted. */
        fun stop() {
            if (stopped) return
            stopped = true
            main.removeCallbacks(expire)
            server?.let { close(it) }
            server = null
            reg?.let { r -> try { nsd?.unregisterService(r) } catch (e: Exception) {} }
            reg = null
            Thread { Thread.sleep(500); file.delete() }.start()
        }

        private fun advertise(port: Int) {
            try {
                val m = app.getSystemService(Context.NSD_SERVICE) as NsdManager
                val l = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(i: NsdServiceInfo) { Log.w(TAG, "Backup: announced as ${i.serviceName}") }
                    override fun onRegistrationFailed(i: NsdServiceInfo, err: Int) { Log.w(TAG, "Backup: announce failed ($err); typing the address still works") }
                    override fun onServiceUnregistered(i: NsdServiceInfo) {}
                    override fun onUnregistrationFailed(i: NsdServiceInfo, err: Int) {}
                }
                val info = NsdServiceInfo().apply { serviceName = deviceName(app).take(60); serviceType = SERVICE; this.port = port }
                m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
                nsd = m
                reg = l
                if (stopped) try { m.unregisterService(l) } catch (e: Exception) {}
            } catch (e: Exception) { Log.w(TAG, "Backup: announce failed: $e") }
        }
    }

    // =================================================================================
    // New TV: finding the old one, and receiving
    // =================================================================================

    class Found(val name: String, val host: String, val port: Int) {
        val address get() = if (port == PORT) host else "$host:$port"
    }

    /** Looks for Dream OS TVs sending a backup on this network. [onChange] runs on the main thread. */
    class Finder(ctx: Context, private val onChange: (List<Found>) -> Unit) {
        private val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        private val main = Handler(Looper.getMainLooper())
        private val found = LinkedHashMap<String, Found>()
        private val queue = ArrayDeque<NsdServiceInfo>()
        private var resolving = false
        private var on = false
        private val me = PhoneRemote.localIp()

        private val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) { Log.w(TAG, "Backup: looking for other TVs") }
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, err: Int) { Log.w(TAG, "Backup: search failed ($err)"); main.post { on = false } }
            override fun onStopDiscoveryFailed(t: String, err: Int) {}
            override fun onServiceFound(i: NsdServiceInfo) { main.post { if (on) { queue.addLast(i); next() } } }
            override fun onServiceLost(i: NsdServiceInfo) { main.post { if (found.remove(i.serviceName) != null) publish() } }
        }

        private fun next() {
            if (resolving || !on) return
            val i = queue.removeFirstOrNull() ?: return
            resolving = true
            try {
                nsd?.resolveService(i, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, err: Int) { main.post { resolving = false; next() } }
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        main.post {
                            resolving = false
                            val host = s.host?.hostAddress
                            if (on && host != null && host != me && s.host?.let { isLan(it) } == true) {
                                found[s.serviceName] = Found(s.serviceName, host, s.port)
                                publish()
                            }
                            next()
                        }
                    }
                })
            } catch (e: Exception) { resolving = false }
        }

        private fun publish() = onChange(found.values.toList())

        fun start() {
            if (on || nsd == null) return
            on = true
            try { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery) } catch (e: Exception) { on = false; Log.w(TAG, "Backup: search failed: $e") }
        }

        fun stop() {
            if (!on) return
            on = false
            queue.clear()
            try { nsd?.stopServiceDiscovery(discovery) } catch (e: Exception) {}
        }
    }

    sealed class Result {
        class Ok(val file: File, val keyHex: String, val summary: String, val from: String) : Result()
        class Failed(val message: String, val wrongCode: Boolean = false) : Result()
    }

    /** One download; [cancel] stops it from any thread. */
    class Download {
        @Volatile var cancelled = false
            private set
        @Volatile internal var socket: Socket? = null
        fun cancel() { cancelled = true; socket?.let { close(it) } }
    }

    /** Parses "192.168.1.23" or "192.168.1.23:8688" (what the old TV shows); null when it isn't an address on this network. */
    fun parseAddress(typed: String): Found? {
        val t = typed.trim()
        val m = Regex("^(\\d{1,3}(?:\\.\\d{1,3}){3})(?::(\\d{2,5}))?$").find(t) ?: return null
        val host = m.groupValues[1]
        if (host.split('.').any { it.toInt() > 255 }) return null
        val port = m.groupValues[2].toIntOrNull() ?: PORT
        if (port !in 1024..65535) return null
        return try { if (isLan(InetAddress.getByName(host))) Found(host, host, port) else null } catch (e: Exception) { null }
    }

    /** Fetches the backup from [from] with [code] (blocking; [progress] from this thread). */
    fun receive(ctx: Context, from: Found, code: String, dl: Download, progress: (Long, Long) -> Unit): Result {
        val app = ctx.applicationContext
        val out = File(app.cacheDir, "dreamos-restore.bin")
        try {
            val k = codeKey(code)
            val mine = ecPair()
            val cPub = mine.public.encoded
            val body = JSONObject().put("v", 1).put("pub", b64(cPub)).put("proof", hex(hmac(k, "client".toByteArray(), cPub))).toString().toByteArray()
            val sock = Socket()
            dl.socket = sock
            if (dl.cancelled) return Result.Failed("Cancelled.")
            sock.connect(InetSocketAddress(from.host, from.port), 6000)
            sock.soTimeout = 60_000
            sock.getOutputStream().apply {
                write(("POST /backup HTTP/1.1\r\nHost: ${from.host}:${from.port}\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                write(body)
                flush()
            }
            val input = BufferedInputStream(sock.getInputStream(), 64 * 1024)
            val head = readHead(input) ?: return Result.Failed("Your other TV didn't answer. Try again.")
            val status = head.line.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            if (status == 403) return Result.Failed("That code didn't match. Check the code on your other TV and try again.", wrongCode = true)
            if (status != 200) return Result.Failed("Your other TV didn't send the backup. Try again.")
            val sPub = unb64(head.headers["x-dream-pub"] ?: "")
            val proof = unhex(head.headers["x-dream-proof"] ?: "")
            if (!MessageDigest.isEqual(hmac(k, "server".toByteArray(), sPub, cPub), proof))
                return Result.Failed("That TV couldn't be verified. Check the code and try again.", wrongCode = true)
            val wrapKey = hmac(k, "wrap".toByteArray(), ecdh(mine.private, sPub), cPub, sPub)
            val wrapped = unb64(head.headers["x-dream-key"] ?: "")
            val payload = JSONObject(String(Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(128, wrapped, 0, 12))
                doFinal(wrapped, 12, wrapped.size - 12)
            }, Charsets.UTF_8))
            val total = head.headers["content-length"]?.toLongOrNull() ?: return Result.Failed("Your other TV didn't send the backup. Try again.")
            // Room for the download and for unpacking it.
            if (app.cacheDir.usableSpace < total * 3 + 50L * 1024 * 1024) return Result.Failed("There isn't enough free space on this TV. Free up space by removing an app, then try again.")
            var got = 0L
            var shown = 0L
            out.outputStream().buffered(64 * 1024).use { f ->
                val buf = ByteArray(64 * 1024)
                while (got < total) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), total - got).toInt())
                    if (n < 0) throw EOFException()
                    f.write(buf, 0, n)
                    got += n
                    val now = SystemClock.elapsedRealtime()
                    if (now - shown > 250) { shown = now; progress(got, total) }
                }
            }
            progress(total, total)
            close(sock)
            Log.w(TAG, "Backup: received ${total / 1024} KB from ${from.address}")
            return Result.Ok(out, payload.getString("key"), payload.optString("summary"), payload.optString("from", from.name))
        } catch (e: Exception) {
            out.delete()
            if (dl.cancelled) return Result.Failed("Cancelled.")
            Log.w(TAG, "Backup: receive failed: $e")
            return Result.Failed(if (e is java.net.ConnectException || e is java.net.SocketTimeoutException)
                "Couldn't reach your other TV. Make sure both TVs are on the same Wi-Fi and Back Up Dream OS is still open on your old TV."
                else "The transfer didn't finish. Try again.")
        } finally {
            dl.socket?.let { close(it) }
        }
    }

    /**
     * Unpacks [ok] over this TV's data and restarts Dream OS (DataMigration ends the process; an alarm
     * opens Dream OS again a moment later). Returns only when it failed.
     */
    fun restore(ctx: Context, ok: Result.Ok): Boolean {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getActivity(app, 7331, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_CANCEL_CURRENT)
        am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1500, pi)
        Log.w(TAG, "Backup: restoring from ${ok.from}")
        val done = DataMigration.import(app, ok.keyHex, ok.file, continueSetup = true) // ends the process when it worked; setup carries on after
        am.cancel(pi)
        ok.file.delete()
        Log.w(TAG, "Backup: restore failed")
        return done
    }

    // ---- tiny HTTP parts ----

    private class Head(val line: String, val headers: Map<String, String>)

    private fun readHead(input: InputStream): Head? {
        fun line(): String? {
            val b = ByteArrayOutputStream()
            while (true) {
                val c = input.read()
                if (c < 0) return null
                if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
                if (b.size() > 8192) return null
                b.write(c)
            }
        }
        val first = line() ?: return null
        val h = HashMap<String, String>()
        for (i in 0..40) {
            val l = line() ?: return null
            if (l.isEmpty()) return Head(first, h)
            val c = l.indexOf(':')
            if (c > 0) h[l.substring(0, c).trim().lowercase()] = l.substring(c + 1).trim()
        }
        return null
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var r = 0
        while (r < n) { val k = input.read(b, r, n - r); if (k < 0) throw EOFException(); r += k }
        return b
    }

    private fun reply(out: java.io.OutputStream, code: Int, msg: String) {
        val body = msg.toByteArray()
        out.write("HTTP/1.1 $code ${if (code == 403) "Forbidden" else if (code == 404) "Not Found" else "Bad Request"}\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun close(c: java.io.Closeable) = try { c.close() } catch (e: Exception) {}
}

// =====================================================================================
// The pages: Back Up Dream OS (old TV) and Restore From Backup (new TV)
// =====================================================================================

/**
 * The Move to a New Stick screens for a [SetupPanel]: [sendOverview] on the old TV, [restoreOverview]
 * on the new one. [onRestoring] runs just before the restore restarts Dream OS.
 */
internal class BackupPages(
    private val ctx: Context,
    private val ui: SetupUi,
    private val panel: SetupPanel,
    private val onRestoring: () -> Unit = {},
) {
    private val k = ui.k
    private val parts = SetupParts(ctx, k)
    private val Int.dp get() = Ui.dp(ctx, this)
    private fun app() = ctx.applicationContext

    private fun mb(b: Long) = if (b < 10L * 1024 * 1024) "%.1f MB".format(b / 1048576.0) else "${b / 1048576} MB"

    private fun setResult(head: LinearLayout, ok: Boolean?, text: String) {
        head.getChildAt(0).background = StatusGlyph(parts.resultMark(ok))
        (head.getChildAt(1) as TextView).text = text
    }

    private fun stepRow(n: Int, t: String) = parts.row(SetupArt.monogram(ctx, "$n", 32)).apply { set(t) }

    // ---- old TV ----

    fun sendOverview(): SetupScreen {
        val s = SetupScreen(ui, null, null)
        s.add(parts.note("Moving to a new Fire TV Stick? Dream OS sends everything to it over your Wi-Fi: profiles, accounts, scrapers, lists and settings."), 0)
        s.view("k:steps", 20) { parts.section("On Your New Fire TV Stick", null) }
        s.view("s1", 10) { stepRow(1, "Install Dream OS and open it") }
        s.view("s2", 8) { stepRow(2, "Choose Restore From Backup") }
        s.view("s3", 8) { stepRow(3, "Pick this TV, then enter the code below") }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 20.dp, 24.dp, 22.dp)
            background = Ui.rounded(ctx, Ui.alpha(Color.WHITE, 0.05f), Ui.R_LG, 1f, Ui.alpha(Color.WHITE, 0.10f))
        }
        val kicker = k.label("YOUR CODE", 12f, Ui.alpha(Color.WHITE, 0.62f), k.semibold, Ui.TRACK_CAPS)
        val code = k.label("", 46f, SetupBrand.TEXT, k.semibold).apply { letterSpacing = 0.16f }
        val where = k.label("", 15f, SetupBrand.DIM, k.font, lines = 2)
        val status = k.label("", 15f, SetupBrand.TEXT, k.medium, lines = 3)
        val bar = ui.progress().apply { visibility = View.GONE }
        card.addView(kicker)
        card.addView(code, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        card.addView(where, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
        card.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        card.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, 6.dp).apply { topMargin = 12.dp })
        s.view("card", 22) { card.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT) } }
        var sender: BackupTransfer.Sender? = null
        lateinit var again: TextView
        fun begin() {
            sender?.stop()
            again.visibility = View.GONE
            code.setCompoundDrawablesRelative(null, null, null, null)
            val me = BackupTransfer.Sender(ctx) { st ->
                if (!s.alive) return@Sender
                when (st.phase) {
                    BackupTransfer.Phase.PREPARING -> {
                        kicker.text = "YOUR CODE"; code.text = "· · · · · ·"; where.text = ""
                        status.text = "Getting your backup ready…"; bar.visibility = View.GONE
                    }
                    BackupTransfer.Phase.READY -> {
                        code.text = st.code ?: ""
                        where.text = "${BackupTransfer.deviceName(app())}  ·  ${st.address}"
                        status.text = st.message ?: "Waiting for your new TV. This code works for 10 minutes."
                        bar.visibility = View.GONE
                    }
                    BackupTransfer.Phase.SENDING -> {
                        status.text = "Sending  ·  ${mb(st.done)} of ${mb(st.total)}"
                        bar.visibility = View.VISIBLE
                        bar.set(if (st.total > 0) st.done.toFloat() / st.total else 0f)
                    }
                    BackupTransfer.Phase.SENT -> {
                        // The drawn green check in place of the code (never a text "✓").
                        kicker.text = "SENT"; code.text = ""
                        code.setCompoundDrawablesRelative(StatusGlyph(BrandRow.Mark.OK).apply { setBounds(0, 0, 52.dp, 52.dp) }, null, null, null)
                        status.text = "Everything is on your new TV. It restarts on its own to finish."
                        where.text = st.message ?: ""
                        bar.set(1f)
                        again.text = "Send Again"; again.visibility = View.VISIBLE
                    }
                    BackupTransfer.Phase.EXPIRED -> {
                        code.text = "—"; where.text = ""
                        status.text = "The code expired. Choose New Code when your new TV is ready."
                        bar.visibility = View.GONE
                        again.text = "New Code"; again.visibility = View.VISIBLE
                    }
                    BackupTransfer.Phase.FAILED -> {
                        code.text = "—"; where.text = ""
                        status.text = st.message ?: "Something went wrong. Try again."
                        bar.visibility = View.GONE
                        again.text = "Try Again"; again.visibility = View.VISIBLE
                    }
                }
                if (st.phase != BackupTransfer.Phase.SENT) code.setTextColor(SetupBrand.TEXT)
            }
            sender = me
            me.start()
        }
        val done = parts.pill("Done", true) { if (!panel.pop()) BackupPage.close() }
        again = parts.pill("New Code", false) { begin() }
        s.view("buttons", 22) { parts.pills(done, again) }
        again.visibility = View.GONE
        s.focus = { done }
        s.onShow = { if (sender == null) begin() }
        s.onHide = { sender?.stop(); sender = null }
        return s
    }

    // ---- new TV ----

    fun restoreOverview(): SetupScreen {
        val s = SetupScreen(ui, null, null)
        val rows = ScreenRows(s)
        s.add(parts.note("On your old Fire TV Stick, open Settings, then Back Up Dream OS. Keep both TVs on the same Wi-Fi."), 0)
        s.view("k:tvs", 20) { parts.section("Your Other TVs", null) }
        var list: List<BackupTransfer.Found> = emptyList()
        // The TVs found, in their own box so they stay above Type Its Address; rows are made once and reused.
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        s.view("tvs", 10) { box.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT) } }
        val looking = parts.row().apply { set("Looking for Your Other TV…", "Open Back Up Dream OS on it", mark = BrandRow.Mark.BUSY) }
        box.addView(looking, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val tvRows = ArrayList<BrandRow>()
        fun paint() {
            looking.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            while (tvRows.size < list.size.coerceAtMost(8)) {
                val i = tvRows.size
                val r = parts.row(SetupArt.monogram(ctx, "TV"), chevron = true) { list.getOrNull(i)?.let { panel.push(codeEntry(it)) } }
                tvRows += r
                box.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (i > 0) topMargin = 8.dp })
            }
            for ((i, r) in tvRows.withIndex()) {
                val f = list.getOrNull(i)
                if (f == null) { if (r.hasFocus()) rows.get("type")?.requestFocus(); r.visibility = View.GONE }
                else { r.set(f.name, f.address); r.visibility = View.VISIBLE }
            }
        }
        rows.row("type", 18) { parts.row(null, chevron = true) { panel.push(addressEntry()) } }
            .set("Type Its Address", "Your old TV shows it under the code")
        val finder = BackupTransfer.Finder(ctx) { found -> list = found; if (s.alive) paint() }
        s.onShow = { paint(); finder.start() }
        s.onHide = { finder.stop() }
        s.focus = { tvRows.firstOrNull { it.visibility == View.VISIBLE } ?: rows.get("type") }
        return s
    }

    private fun addressEntry(): SetupScreen = setupEntry(ctx, ui, parts, "Your Old TV's Address", null, "Example: 192.168.1.23", "192.168.",
        listOf("192.168.", "10.0.", "."), "Type the address shown under the code on your old TV.", null) { typed ->
        val f = BackupTransfer.parseAddress(typed)
        if (f == null) panel.replace(problem("That Isn't an Address", "Type the numbers exactly as your old TV shows them, like 192.168.1.23.") { panel.replace(addressEntry()) })
        else panel.replace(codeEntry(f))
    }

    private fun codeEntry(from: BackupTransfer.Found): SetupScreen = setupEntry(ctx, ui, parts, "Enter the Code", null, "6-Character Code", "",
        emptyList(), "Type the code shown on ${from.name}.", null) { typed ->
        val c = BackupTransfer.normalize(typed)
        if (c.length != BackupTransfer.CODE_LEN) panel.replace(problem("That Code Is Too Short", "The code has 6 letters and numbers. Check your old TV and try again.") { panel.replace(codeEntry(from)) })
        else panel.replace(transfer(from, c))
    }

    private fun problem(title: String, line: String, retry: () -> Unit): SetupScreen {
        val s = SetupScreen(ui, null, null)
        s.view("head", 0) { parts.result(false, title) }
        s.view("line", 12) { parts.note(line) }
        val again = parts.pill("Try Again", true) { retry() }
        val cancel = parts.pill("Cancel", false) { panel.pop() }
        s.view("buttons", 24) { parts.pills(again, cancel) }
        s.focus = { again }
        return s
    }

    private fun transfer(from: BackupTransfer.Found, code: String): SetupScreen {
        val s = SetupScreen(ui, null, null)
        val head = s.view("head", 0) { parts.result(null, "Connecting…") } as LinearLayout
        val line = s.view("line", 12) { parts.note("Connecting to ${from.name}.") } as TextView
        val bar = ui.progress()
        s.view("bar", 16) { bar.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 6.dp) } }
        val dl = BackupTransfer.Download()
        var finished = false
        val cancel = parts.pill("Cancel", false) { dl.cancel(); panel.pop() }
        s.view("buttons", 24) { parts.pills(cancel) }
        s.focus = { cancel }
        s.onBack = { if (!finished) dl.cancel(); false }
        fun failed(r: BackupTransfer.Result.Failed) {
            finished = true
            setResult(head, false, if (r.wrongCode) "That Didn't Work" else "Couldn't Restore")
            line.text = r.message
            s.hide("bar")
            val again = parts.pill("Try Again", true) { panel.replace(if (r.wrongCode) codeEntry(from) else transfer(from, code)) }
            val back = parts.pill("Cancel", false) { panel.pop() }
            s.hide("buttons")
            s.view("buttons2", 24) { parts.pills(again, back) }
            again.post { if (s.alive) again.requestFocus() }
        }
        var started = false
        s.onShow = {
            if (!started) {
                started = true
                Log.w(SetupBrand.TAG, "Setup: Restore From Backup, connecting to ${from.address}")
                Thread {
                    val r = BackupTransfer.receive(ctx, from, code, dl) { got, total ->
                        ui.main.post { if (s.alive) {
                            setResult(head, null, "Copying Your Data…")
                            line.text = "${mb(got)} of ${mb(total)} from ${from.name}"
                            bar.set(if (total > 0) got.toFloat() / total else 0f)
                        } }
                    }
                    ui.main.post {
                        if (dl.cancelled) { (r as? BackupTransfer.Result.Ok)?.file?.delete(); return@post }
                        when (r) {
                            is BackupTransfer.Result.Failed -> if (s.alive) failed(r)
                            is BackupTransfer.Result.Ok -> {
                                finished = true
                                setResult(head, true, "Restore Complete")
                                line.text = "${r.summary.ifBlank { "Your profiles, accounts and settings" }}\n\nDream OS restarts in a moment to finish."
                                bar.set(1f)
                                s.hide("buttons")
                                onRestoring()
                                // A moment to read what came over, then the restore (it restarts Dream OS).
                                ui.main.postDelayed({
                                    Thread {
                                        BackupTransfer.restore(ctx, r)
                                        ui.main.post { if (s.alive) failed(BackupTransfer.Result.Failed("Dream OS couldn't open the backup. Try again from your old TV.")) }
                                    }.start()
                                }, 3500)
                            }
                        }
                    }
                }.apply { isDaemon = true; name = "backup-receive" }.start()
            }
        }
        return s
    }
}

/** Settings › Back Up Dream OS, and Restore From Backup (also from setup's first screen), as full pages. */
object BackupPage {
    private var openPage: View? = null
    private var closer: (() -> Unit)? = null

    fun isOpen() = openPage != null

    fun close() { closer?.invoke() }

    /** Opens the send page ([restore] false) or the restore page over the current screen; [onClose] runs once it's gone. */
    fun open(a: Activity, restore: Boolean, onClose: (() -> Unit)? = null) {
        if (openPage != null) return
        val k = PremiumKit(a)
        val ui = SetupUi(a, k)
        val panel = SetupPanel(ui)
        val parts = SetupParts(a, k)
        val back = a.currentFocus
        val dp = { v: Int -> Ui.dp(a, v) }
        lateinit var root: OverlayRoot
        fun close() {
            val r = root
            if (openPage !== r) return
            openPage = null
            closer = null
            r.animate().alpha(0f).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT).withEndAction {
                panel.release()
                (r.parent as? ViewGroup)?.removeView(r)
            }.start()
            if (back != null && back.isAttachedToWindow) back.requestFocus()
            onClose?.invoke()
        }
        root = OverlayRoot(a) { if (!panel.pop()) close() }
        root.isClickable = true
        SetupBrand.backdrop(root)
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(PremiumKit.EDGE), dp(44), dp(56), dp(36))
        }
        val left = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        left.addView(SetupArt(a, SetupArt.Kind.BACKUP), LinearLayout.LayoutParams(dp(132), dp(132)))
        left.addView(k.label(if (restore) "OPTIONAL" else "SETTINGS", 12f, Ui.alpha(Color.WHITE, 0.62f), k.semibold, Ui.TRACK_CAPS),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(18) })
        left.addView(k.label(if (restore) "Restore From Backup" else "Back Up Dream OS", 32f, SetupBrand.TEXT, k.semibold, Ui.TRACK_TITLE, lines = 2),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        left.addView(parts.note(if (restore)
            "Bring everything over from your old Fire TV Stick: profiles, accounts, scrapers, lists and settings. Dream OS restarts when it's done."
            else "Move Dream OS to a new Fire TV Stick. Everything travels over your own Wi-Fi, locked with a one-time code, and never leaves your home.", 16f)
            .apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        row.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.36f))
        row.addView(panel, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.64f).apply { marginStart = dp(56) })
        root.addView(row, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.TOP))
        Ui.unclip(row)
        (a.window.decorView as FrameLayout).addView(root, MATCH_PARENT, MATCH_PARENT)
        openPage = root
        closer = { close() }
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        Log.w(SetupBrand.TAG, "Setup: ${if (restore) "Restore From Backup" else "Back Up Dream OS"} opened")
        val pages = BackupPages(a, ui, panel)
        panel.push(if (restore) pages.restoreOverview() else pages.sendOverview(), animate = false)
    }
}
