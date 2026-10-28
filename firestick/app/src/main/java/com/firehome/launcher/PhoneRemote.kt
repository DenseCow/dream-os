package com.firehome.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Tiny LAN web server for the phone remote. The page is served from res/raw/remote.html.
 *
 * Off unless needed: it runs only while a pairing code is on screen (a QR code, [pairingUrl], for
 * [PAIR_WINDOW_MS]) or while the remote is turned on with at least one paired phone ([enabled]).
 *
 * Pairing: the QR code carries a one-time code in the URL fragment (never in a request line). The
 * page swaps it (POST /pair) for its own random 256-bit key, kept in the phone's browser; the TV keeps
 * a list of paired phones ("Forget Paired Phones" drops them all and every key with them). Each later
 * request is signed: HMAC-SHA256 over phone id, a rising sequence number, method, path and the body's
 * SHA-256, sent in X-Dream-* headers. The key itself never crosses the network again, so watching the
 * Wi-Fi doesn't reveal it, and a captured request can't be replayed or changed.
 *
 * Callbacks always run on the main thread. One request per connection (Connection: close).
 */
class PhoneRemote(private val ctx: Context, private val callbacks: Callbacks) {

    interface Callbacks {
        fun onKey(key: String)
        fun onText(text: String)
        fun onLaunch(pkg: String)
        fun onPhoto(jpeg: ByteArray)
        fun apps(): List<Pair<String, String>> // label, package
    }

    private val main = Handler(Looper.getMainLooper())
    // Bounded on both ends: a connection flood is dropped instead of queueing sockets, and idle workers exit.
    private val pool = ThreadPoolExecutor(WORKERS, WORKERS, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(16), ThreadFactory { r ->
        Thread(r, "phone-remote-worker").apply { isDaemon = true }
    }).apply { allowCoreThreadTimeOut(true) }
    private val photoBusy = AtomicBoolean(false) // one 12 MB upload in memory at a time
    private val html by lazy { ctx.resources.openRawResource(R.raw.remote).use { it.readBytes() } }
    private var running = false // guarded by this
    private var generation = 0 // guarded by this; bumped by start/stop so a dying server thread can't clobber a newer one
    private var server: ServerSocket? = null

    private val prefs = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE)

    /** A paired phone. [key] is its HMAC key (hex); [seq] the highest sequence number seen. */
    class Phone(val id: String, val name: String, val key: String, val at: Long, var seq: Long)

    private val phones = LinkedHashMap<String, Phone>() // guarded by this
    private val seen = HashMap<String, java.util.TreeSet<Long>>() // guarded by this; recent sequence numbers per phone
    private var pairCode: String? = null // guarded by this
    private var pairUntil = 0L // guarded by this; elapsedRealtime
    private var pairFails = 0 // guarded by this
    private var seqSavedAt = 0L // guarded by this

    /** Main thread: a phone just paired (its name). */
    var onPaired: ((String) -> Unit)? = null

    init {
        // The old permanent pairing token (one for every phone, never rotated) is gone for good.
        if (prefs.contains("token")) prefs.edit().remove("token").apply()
        try {
            val a = JSONArray(prefs.getString("phones", "[]"))
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
                val id = o.optString("id"); val key = o.optString("key")
                if (id.isNotEmpty() && key.length == 64) phones[id] = Phone(id, o.optString("name", "Phone"), key, o.optLong("at"), o.optLong("seq"))
            }
        } catch (e: Exception) {}
        current = this
    }

    /** Turned on by the user (or by pairing a phone); off by default. The server also needs a paired phone. */
    fun enabled() = prefs.getBoolean("enabled", false)

    fun setEnabled(on: Boolean) { prefs.edit().putBoolean("enabled", on).apply(); update() }

    fun pairedPhones(): List<Phone> = synchronized(this) { phones.values.toList() }

    /** Drops every paired phone (and so every key) and the current pairing code; the remote turns off. */
    fun forgetAll() {
        synchronized(this) { phones.clear(); seen.clear(); pairCode = null; pairUntil = 0L }
        prefs.edit().putString("phones", "[]").putBoolean("enabled", false).apply()
        update()
    }

    /**
     * The address for a QR code that pairs a phone, valid for [PAIR_WINDOW_MS] from now (the server
     * runs meanwhile); null without Wi-Fi. [then] sends the phone to a section of its page ("type", "pm").
     */
    fun pairingUrl(then: String? = null): String? {
        val ip = localIp() ?: return null
        val code = synchronized(this) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (pairCode == null || now > pairUntil) { pairCode = randomCode(); pairFails = 0 }
            pairUntil = now + PAIR_WINDOW_MS
            pairCode!!
        }
        update()
        main.removeCallbacks(recheck)
        main.postDelayed(recheck, PAIR_WINDOW_MS + 1000)
        return "http://$ip:$PORT/#p=$code" + (then?.let { "&go=$it" } ?: "")
    }

    /** The QR code left the screen: the code stays good a short while longer (a phone may still be opening it). */
    fun closePairing() {
        synchronized(this) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (pairUntil > now + PAIR_GRACE_MS) pairUntil = now + PAIR_GRACE_MS
        }
        main.removeCallbacks(recheck)
        main.postDelayed(recheck, PAIR_GRACE_MS + 1000)
    }

    /** A temporary pairing (typing into one setup field) is over: the code stops working now. */
    fun endPairing() {
        synchronized(this) { pairCode = null; pairUntil = 0L }
        update()
    }

    private val recheck = Runnable { update() }

    /** For older callers: the pairing address (see [pairingUrl]). */
    val url: String? get() = pairingUrl()

    /** Starts or stops the server to match: a pairing code on screen, or turned on with a paired phone. */
    fun update() {
        val want = synchronized(this) {
            android.os.SystemClock.elapsedRealtime() < pairUntil || (phones.isNotEmpty() && prefs.getBoolean("enabled", false))
        }
        if (want) start() else stop()
    }

    private fun savePhones() {
        val a = JSONArray()
        synchronized(this) {
            for (p in phones.values) a.put(JSONObject().put("id", p.id).put("name", p.name).put("key", p.key).put("at", p.at).put("seq", p.seq))
        }
        prefs.edit().putString("phones", a.toString()).apply()
    }

    private fun start() {
        val gen = synchronized(this) { if (running) return; running = true; ++generation }
        Thread {
            var s: ServerSocket? = null
            try {
                val ss = ServerSocket().also { s = it }
                ss.reuseAddress = true // rebinding right after stop() mustn't fail with EADDRINUSE
                ss.bind(InetSocketAddress(PORT))
                synchronized(this) { if (generation != gen) return@Thread; server = ss }
                while (!ss.isClosed) {
                    val client = try { ss.accept() } catch (e: IOException) {
                        if (ss.isClosed) break
                        Thread.sleep(200) // transient (e.g. out of fds): back off, keep serving
                        continue
                    }
                    try { pool.execute { handle(client) } } catch (e: RejectedExecutionException) { closeQuietly(client) }
                }
            } catch (e: Exception) {
            } finally {
                s?.let { closeQuietly(it) }
                synchronized(this) { if (generation == gen) { server = null; running = false } }
            }
        }.apply { isDaemon = true; name = "phone-remote" }.start()
    }

    /** Stops the server (also when MainActivity goes away). Pairings are kept. */
    fun stop() {
        main.removeCallbacks(recheck)
        synchronized(this) {
            running = false
            generation++
            server?.let { closeQuietly(it) }
            server = null
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = READ_TIMEOUT_MS
                val req = Request(BufferedInputStream(s.getInputStream()), HEADER_DEADLINE_MS)
                val res = try { route(req) } catch (e: HttpError) { text(e.code, e.message.orEmpty()) }
                write(s, res)
            }
        } catch (e: Exception) {
            // Client vanished, timed out or sent garbage. Never let it reach the uncaught handler: that kills the app.
        } catch (e: OutOfMemoryError) {
        }
    }

    private fun route(req: Request): Response {
        val parts = (req.line() ?: throw EOFException()).split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) throw HttpError(400, "Bad request")
        val (method, target) = parts
        var length = 0L
        var chunked = false
        val dream = HashMap<String, String>() // X-Dream-* headers, lower-case names
        var agent = ""
        for (i in 0..MAX_HEADERS) {
            val h = req.line() ?: throw EOFException()
            if (h.isEmpty()) break
            if (i == MAX_HEADERS) throw HttpError(431, "Too many headers")
            val c = h.indexOf(':')
            if (c <= 0) continue
            val name = h.substring(0, c).trim()
            if (name.equals("Content-Length", ignoreCase = true)) {
                length = h.substring(c + 1).trim().toLongOrNull()?.takeIf { it >= 0 } ?: throw HttpError(400, "Bad Content-Length")
            } else if (name.equals("Transfer-Encoding", ignoreCase = true)) {
                chunked = true
            } else if (name.startsWith("X-Dream-", ignoreCase = true)) {
                dream[name.lowercase()] = h.substring(c + 1).trim().take(128)
            } else if (name.equals("User-Agent", ignoreCase = true)) {
                agent = h.substring(c + 1).trim().take(512)
            }
        }

        val path = target.substringBefore('?')
        val allowed = ROUTES[path] ?: return text(404, "Not found", headOnly = method == "HEAD")
        if (method != allowed) return text(405, "Method not allowed", allow = allowed, headOnly = method == "HEAD")
        if (path == "/") return Response(200, "text/html; charset=utf-8", html)
        if (chunked) return text(411, "Content-Length required")
        if (path == "/pair") {
            if (length > MAX_SMALL) return text(413, "Too large")
            return pair(String(req.body(length.toInt()), Charsets.UTF_8).trim(), agent)
        }
        val query = parseQuery(target.substringAfter('?', ""))
        val bodyHash = signed(dream, method, target) ?: return text(403, "Scan the QR code on your TV")
        val maxBody = when (path) { "/photo" -> MAX_PHOTO; else -> MAX_SMALL }
        if (length > maxBody) return text(413, "Too large")

        if (path == "/photo") {
            if (length == 0L) return text(400, "No photo")
            if (!photoBusy.compareAndSet(false, true)) return text(503, "Busy, try again")
            try {
                req.extend(PHOTO_DEADLINE_MS)
                val jpeg = req.body(length.toInt()) // throws if the upload is cut short, so no half photos
                if (!bodyOk(jpeg, bodyHash)) return text(400, "Changed on the way")
                main.post { callbacks.onPhoto(jpeg) }
            } finally {
                photoBusy.set(false)
            }
            return text(200, "ok")
        }

        val body = req.body(length.toInt())
        if (!bodyOk(body, bodyHash)) return text(400, "Changed on the way")
        when (path) {
            "/ping" -> {}
            "/key" -> query["k"]?.takeIf { it.length in 1..16 }?.let { k -> main.post { callbacks.onKey(k) } } ?: return text(400, "Missing k")
            "/text" -> String(body, Charsets.UTF_8).trim().take(200).takeIf { it.isNotEmpty() }?.let { t -> main.post { callbacks.onText(t) } }
            "/launch" -> query["pkg"]?.takeIf { it.length in 1..255 }?.let { p -> main.post { callbacks.onLaunch(p) } } ?: return text(400, "Missing pkg")
            "/apps" -> return Response(200, "application/json; charset=utf-8", appsJson())
            "/airpods/tv", "/airpods/phone" -> return airPods(toTv = path == "/airpods/tv")
            // Premiumize's API key from the phone page (Connect Premiumize on the TV); never logged.
            "/pmkey" -> return premiumizeKey(String(body, Charsets.UTF_8).trim())
        }
        return text(200, "ok")
    }

    /** Asks for the app list on the main thread (the callbacks own UI state) and serializes it here. */
    private fun appsJson(): ByteArray {
        val done = CountDownLatch(1)
        var apps: List<Pair<String, String>> = emptyList()
        main.post {
            try { apps = callbacks.apps() } catch (e: Exception) {} finally { done.countDown() }
        }
        if (!done.await(3, TimeUnit.SECONDS)) throw HttpError(503, "TV busy")
        val list = JSONArray()
        for ((label, pkg) in apps) list.put(JSONObject().put("label", label).put("pkg", pkg))
        return list.toString().toByteArray()
    }

    /**
     * Moves the AirPods (AirPodsLink does the work on the main thread) and answers with the outcome:
     * 200 + message when it worked, 409 + message when it didn't, 202 if it's still going after the wait.
     */
    private fun airPods(toTv: Boolean): Response {
        val done = CountDownLatch(1)
        var result: AirPodsLink.Result? = null
        main.post {
            try {
                AirPodsLink.init(ctx)
                val finish: (AirPodsLink.Result) -> Unit = { r -> result = r; done.countDown() }
                if (toTv) AirPodsLink.bringToTv(ctx, finish) else AirPodsLink.sendToPhone(ctx, finish)
            } catch (e: Exception) {
                done.countDown()
            }
        }
        if (!done.await(AIRPODS_WAIT_S, TimeUnit.SECONDS)) return text(202, if (toTv) "Still connecting… check the TV" else "Still disconnecting… check the TV")
        val r = result ?: return text(503, "TV busy")
        return text(if (r.ok) 200 else 409, r.message)
    }

    /**
     * POST /pmkey: hands the Premiumize API key to the Connect Premiumize screen ([onPremiumizeKey], main
     * thread), which checks it with Premiumize and answers: 200 + message when it's linked, 409 + message
     * when it isn't (or no such screen is open), 202 if the check is still going after the wait.
     */
    private fun premiumizeKey(key: String): Response {
        if (key.isEmpty() || key.length > 200) return text(400, "Paste your key first.")
        val done = CountDownLatch(1)
        var result: Pair<Boolean, String>? = null
        main.post {
            val hook = onPremiumizeKey
            if (hook == null) { result = false to "Choose Premiumize on your TV first."; done.countDown(); return@post }
            try { hook(key) { ok, msg -> if (result == null) { result = ok to msg; done.countDown() } } } catch (e: Exception) { done.countDown() }
        }
        if (!done.await(PM_WAIT_S, TimeUnit.SECONDS)) return text(202, "Sent. Check your TV.")
        val r = result ?: return text(503, "TV busy")
        return text(if (r.first) 200 else 409, r.second)
    }

    /** POST /pair: swaps the code from the QR on screen for this phone's own key. */
    private fun pair(code: String, agent: String): Response {
        val phone: Phone
        synchronized(this) {
            val want = pairCode
            val now = android.os.SystemClock.elapsedRealtime()
            if (want == null || now > pairUntil) return text(403, "Open Phone Remote on your TV and scan the code")
            if (!MessageDigest.isEqual(code.toByteArray(), want.toByteArray())) {
                // A few typos are fine; guessing is not: the code is replaced after too many misses.
                if (++pairFails >= MAX_PAIR_FAILS) { pairCode = randomCode(); pairFails = 0 }
                return text(403, "That code has expired. Scan the code on your TV again")
            }
            // One phone per code: the next phone needs the fresh code now on screen.
            pairCode = randomCode(); pairFails = 0
            val rnd = SecureRandom()
            phone = Phone(hex(ByteArray(8).also { rnd.nextBytes(it) }), deviceName(agent), hex(ByteArray(32).also { rnd.nextBytes(it) }),
                System.currentTimeMillis(), 0L)
            phones[phone.id] = phone
            while (phones.size > MAX_PHONES) phones.remove(phones.keys.first())
        }
        prefs.edit().putBoolean("enabled", true).apply()
        savePhones()
        main.post { onPaired?.invoke(phone.name) }
        return Response(200, "application/json; charset=utf-8", JSONObject().put("id", phone.id).put("key", phone.key).toString().toByteArray())
    }

    /**
     * Checks a signed request's X-Dream-* headers: a paired phone, a sequence number not used before
     * (nor far behind its newest), and the HMAC. Returns the body hash the request promised (checked
     * once the body is read), or null when it isn't a good request from a paired phone.
     */
    private fun signed(h: Map<String, String>, method: String, target: String): String? {
        val id = h["x-dream-phone"] ?: return null
        val seq = h["x-dream-seq"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val bodyHash = h["x-dream-body"]?.lowercase()?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } ?: return null
        val sig = h["x-dream-sig"]?.lowercase() ?: return null
        val phone = synchronized(this) { phones[id] } ?: return null
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(unhex(phone.key), "HmacSHA256")) }
        val want = hex(mac.doFinal("$id\n$seq\n$method\n$target\n$bodyHash".toByteArray()))
        if (!MessageDigest.isEqual(want.toByteArray(), sig.toByteArray())) return null
        var save = false
        synchronized(this) {
            if (phones[id] !== phone) return null
            val recent = seen.getOrPut(id) { java.util.TreeSet() }
            if (seq <= phone.seq - REPLAY_WINDOW || seq in recent) return null
            recent += seq
            if (seq > phone.seq) phone.seq = seq
            while (recent.isNotEmpty() && recent.first() <= phone.seq - REPLAY_WINDOW) recent.pollFirst()
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - seqSavedAt > 10_000) { seqSavedAt = now; save = true }
        }
        if (save) savePhones()
        return bodyHash
    }

    private fun bodyOk(body: ByteArray, hash: String) =
        MessageDigest.isEqual(hex(MessageDigest.getInstance("SHA-256").digest(body)).toByteArray(), hash.toByteArray())

    private fun write(s: Socket, r: Response) {
        val head = StringBuilder("HTTP/1.1 ${r.code} ${reason(r.code)}\r\n")
            .append("Content-Type: ${r.type}\r\nContent-Length: ${r.body.size}\r\n")
            .append("Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n")
        r.allow?.let { head.append("Allow: $it\r\n") }
        head.append("\r\n")
        val out = BufferedOutputStream(s.getOutputStream(), 8192)
        out.write(head.toString().toByteArray())
        if (!r.headOnly) out.write(r.body)
        out.flush()
    }

    private fun text(code: Int, msg: String, allow: String? = null, headOnly: Boolean = false) =
        Response(code, "text/plain; charset=utf-8", msg.toByteArray(), allow, headOnly)

    /** Malformed pairs (bad % escapes etc.) are skipped rather than failing the request. */
    private fun parseQuery(q: String): Map<String, String> = q.split('&').mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) return@mapNotNull null
        try {
            URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        } catch (e: IllegalArgumentException) {
            null
        }
    }.toMap()

    private class Response(
        val code: Int, val type: String, val body: ByteArray,
        val allow: String? = null, val headOnly: Boolean = false, // headOnly: answer a HEAD without the body
    )

    private class HttpError(val code: Int, message: String) : Exception(message)

    /** Reads one request, giving up once its deadline passes so a drip-feeding client can't pin a worker. */
    private class Request(private val input: InputStream, timeoutMs: Long) {
        private var deadline = System.currentTimeMillis() + timeoutMs
        private var headBytes = 0

        fun extend(ms: Long) { deadline = System.currentTimeMillis() + ms }

        private fun check() { if (System.currentTimeMillis() > deadline) throw SocketTimeoutException("Request too slow") }

        /** One header line without its CR/LF, or null at end of stream. */
        fun line(): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                check()
                val c = input.read()
                if (c < 0) return null
                if (c == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
                if (++headBytes > MAX_HEAD_BYTES) throw HttpError(431, "Headers too large")
                buf.write(c)
            }
        }

        fun body(length: Int): ByteArray {
            val b = ByteArray(length)
            var read = 0
            while (read < length) {
                check()
                val n = input.read(b, read, length - read)
                if (n < 0) throw EOFException()
                read += n
            }
            return b
        }
    }

    companion object {
        const val PORT = 8686
        private const val WORKERS = 4
        private const val READ_TIMEOUT_MS = 5000
        private const val HEADER_DEADLINE_MS = 10_000L
        private const val PHOTO_DEADLINE_MS = 60_000L
        private const val MAX_HEADERS = 50
        private const val MAX_HEAD_BYTES = 16 * 1024
        private const val MAX_SMALL = 4 * 1024
        private const val MAX_PHOTO = 12 * 1024 * 1024
        private const val AIRPODS_WAIT_S = 18L // longer than AirPodsLink's 15 s connect timeout
        private const val PM_WAIT_S = 20L // the TV's check with Premiumize, with room to spare
        /** How long a pairing code on screen stays good (the server runs meanwhile). */
        const val PAIR_WINDOW_MS = 10 * 60_000L
        private const val PAIR_GRACE_MS = 90_000L
        private const val MAX_PAIR_FAILS = 10
        private const val MAX_PHONES = 8
        /** Sequence numbers are the phone's clock in ms (or one more than its last): each accepted once, at most this far behind its newest. */
        private const val REPLAY_WINDOW = 120_000L

        /** The launcher's remote, for screens that show a pairing QR code. */
        @Volatile var current: PhoneRemote? = null
            private set

        /**
         * Set while Connect Premiumize waits for the phone (main thread): gets the pasted API key and
         * calls back once with (linked, a sentence for the phone).
         */
        @Volatile var onPremiumizeKey: ((String, (Boolean, String) -> Unit) -> Unit)? = null

        /**
         * Apps and screens the phone remote must never drive: Fire OS Settings (and Amazon's settings
         * inside its launcher), the system UI (where the "Allow debugging?" prompt lives) and installers.
         */
        fun isProtected(pkg: String?): Boolean {
            if (HomeRedirectService.amazonSettingsOpen) return true
            if (pkg == null) return false
            return PROTECTED.any { pkg == it || pkg.startsWith("$it.") }
        }
        private val PROTECTED = listOf(
            "com.amazon.tv.settings", "com.amazon.tv.settings.v2", "com.amazon.tv.launcher", "com.amazon.tv.oobe",
            "com.amazon.device.settings", "com.amazon.tv.parentalcontrols",
            "com.android.settings", "com.android.tv.settings", "com.android.systemui",
            "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.vending",
        )

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        private fun deviceName(agent: String) = when {
            "iPhone" in agent -> "iPhone"
            "iPad" in agent -> "iPad"
            "Android" in agent -> "Android Phone"
            "Macintosh" in agent || "Windows" in agent || "Linux" in agent -> "Computer"
            else -> "Phone"
        }

        private val ROUTES = mapOf(
            "/" to "GET", "/pair" to "POST", "/ping" to "GET", "/apps" to "GET",
            "/key" to "POST", "/text" to "POST", "/launch" to "POST", "/photo" to "POST",
            "/airpods/tv" to "POST", "/airpods/phone" to "POST",
            "/pmkey" to "POST",
        )

        private fun reason(code: Int) = when (code) {
            200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"
            411 -> "Length Required"; 413 -> "Payload Too Large"; 431 -> "Request Header Fields Too Large"
            503 -> "Service Unavailable"; else -> "Error"
        }

        private fun closeQuietly(c: java.io.Closeable) = try { c.close() } catch (e: Exception) {}

        /** A pairing code: 12 characters (about 59 bits), good for one phone within [PAIR_WINDOW_MS]. */
        private fun randomCode(): String {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val r = SecureRandom()
            return (1..12).map { chars[r.nextInt(chars.length)] }.joinToString("")
        }

        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
