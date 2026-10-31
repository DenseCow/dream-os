package com.dreamos.desktop.core.net

import com.dreamos.desktop.core.util.Log
import com.dreamos.desktop.core.util.parseJson
import kotlinx.coroutines.asContextElement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A non-2xx answer. [code] stays out of anything a viewer sees (STANDARDS §4: no HTTP codes on screen). */
class HttpError(val code: Int, val body: String = "", val retryAfter: Double? = null) : Exception("HTTP $code")

/** One answer: status, body, and the headers callers read (ETag, Retry-After). */
class NetResponse(val code: Int, val body: String, val headers: Map<String, List<String>>) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    val ok: Boolean get() = code in 200..299
}

/**
 * HTTP for core (fire-home Net.kt's getText / getJson). One shared client: HTTP/2 when the server speaks it, keep-alive,
 * redirects followed. Every TMDB request goes through here, paced by [TmdbGate] at 35 a second; a 429 waits out
 * TMDB's pause and asks again (twice), so a row never disappears for it. Blocking: call from IO threads only.
 */
object Net {
    const val USER_AGENT = "DreamOS"
    private const val CONNECT_MS = 8_000L
    private const val READ_MS = 10_000L

    val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(CONNECT_MS))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Set by tests: answers instead of the network (url -> response), null = the real network. */
    @Volatile var fake: ((method: String, url: String, body: String?) -> NetResponse?)? = null

    fun isTmdb(url: String) = url.startsWith("https://api.themoviedb.org/")

    /** TMDB requests sent to the network since launch (the speed harness reports it). */
    val tmdbSent = java.util.concurrent.atomic.AtomicInteger()

    /** Told of each TMDB request as it is sent (the speed harness logs them); null in the app. */
    @Volatile var onTmdb: ((url: String) -> Unit)? = null

    /**
     * GET [url] as text; throws [HttpError] on a non-2xx answer, IOException on no network. TMDB answers go through
     * [TmdbDisk] (kept on disk, lists served stale-while-revalidate).
     */
    fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        if (isTmdb(url) && headers.isEmpty()) tmdb(url).body else fetch(url, headers)

    /**
     * A TMDB GET through the disk cache, saying whether the answer is past its lifetime (a fresh one is on its way).
     * [revalidate] true: an object (a show's details) is also served stale and refreshed behind, for a row that
     * shouldn't wait on it; null: lists only.
     */
    fun tmdb(url: String, revalidate: Boolean? = null): TmdbDisk.Answer = TmdbDisk.get(url, revalidate) { fetch(url, emptyMap()) }

    private fun fetch(url: String, headers: Map<String, String>): String {
        val r = request("GET", url, null, headers)
        if (!r.ok) throw HttpError(r.code, r.body, r.header("Retry-After")?.toDoubleOrNull())
        return r.body
    }

    fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonObject = parseJson(getText(url, headers)).jsonObject

    fun getJsonAny(url: String, headers: Map<String, String> = emptyMap()): JsonElement = parseJson(getText(url, headers))

    /**
     * Any method. [body] is sent as JSON unless headers say otherwise. Returns every answer (callers read the code);
     * only TMDB's 429 is retried here.
     */
    fun request(method: String, url: String, body: String? = null, headers: Map<String, String> = emptyMap(), timeoutMs: Long = READ_MS): NetResponse {
        val tmdb = isTmdb(url)
        var attempt = 0
        while (true) {
            if (tmdb) { TmdbGate.enter(); tmdbSent.incrementAndGet(); onTmdb?.invoke(url) }
            val r = fake?.invoke(method, url, body) ?: send(method, url, body, headers, timeoutMs)
            if (tmdb && r.code == 429 && attempt < 2) {
                attempt++
                TmdbGate.backOff(maxOf(r.header("Retry-After")?.toDoubleOrNull() ?: 0.0, 0.6 * attempt))
                continue
            }
            return r
        }
    }

    private fun send(method: String, url: String, body: String?, headers: Map<String, String>, timeoutMs: Long): NetResponse {
        val b = HttpRequest.newBuilder(uri(url)).timeout(Duration.ofMillis(timeoutMs)).header("User-Agent", USER_AGENT)
        var typed = false
        for ((k, v) in headers) {
            b.header(k, v)
            if (k.equals("Content-Type", true)) typed = true
        }
        if (body != null && !typed) b.header("Content-Type", "application/json")
        b.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        val resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return NetResponse(resp.statusCode(), resp.body().orEmpty(), resp.headers().map())
    }

    fun encode(s: String): String = java.net.URLEncoder.encode(s, Charsets.UTF_8)

    /**
     * [url] as a URI. TMDB queries carry characters java.net.URI refuses ("|" in "with_release_type=4|5", a space or a
     * quote from a title): those are percent-encoded here so every caller can write queries as the Fire Stick did.
     */
    fun uri(url: String): URI {
        val sb = StringBuilder(url.length + 16)
        for (c in url) when (c) {
            '|' -> sb.append("%7C"); ' ' -> sb.append("%20"); '"' -> sb.append("%22"); '<' -> sb.append("%3C")
            '>' -> sb.append("%3E"); '{' -> sb.append("%7B"); '}' -> sb.append("%7D"); '^' -> sb.append("%5E")
            '`' -> sb.append("%60"); '\\' -> sb.append("%5C")
            else -> sb.append(c)
        }
        return URI(sb.toString())
    }
}

/**
 * Which lane this thread's TMDB requests take ([TmdbGate]). Foreground (the default) is what the viewer is looking at
 * or just asked for: the page on screen, a title page, search. Background is work ahead of the viewer: other pages
 * filled ahead, key art for cards, stale lists refreshed behind. Background pools set it on their threads; coroutines
 * take it along with [element] (`withContext(Lane.element()) { ... }`, `scope.launch(Lane.element()) { ... }`).
 */
object Lane {
    private val bg = ThreadLocal.withInitial { false }

    val background: Boolean get() = bg.get()

    /** Runs [block] on this thread in the background lane (blocking code). */
    fun <T> background(block: () -> T): T = on(true, block)

    /** Runs [block] on this thread in the foreground lane, also from a background pool (a click handled there). */
    fun <T> foreground(block: () -> T): T = on(false, block)

    private fun <T> on(background: Boolean, block: () -> T): T {
        val old = bg.get()
        bg.set(background)
        try { return block() } finally { bg.set(old) }
    }

    /** A coroutine context element putting the coroutine in a lane ([background] true: work ahead of the viewer). */
    fun element(background: Boolean = true): kotlin.coroutines.CoroutineContext = bg.asContextElement(background)

    /** A thread factory for a background pool: low priority, daemon, background lane. */
    fun threads(name: String): java.util.concurrent.ThreadFactory = java.util.concurrent.ThreadFactory { r ->
        Thread({ bg.set(true); r.run() }, name).apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
}

/**
 * TMDB's request pacing (fire-home Net.TmdbGate, from the iPhone). TMDB allows roughly 40-50 requests a second per
 * address; past that it answers 429. Requests wait for a free slot in the current second, and after a 429 everyone
 * waits out the pause. Blocking: network threads only.
 *
 * Two lanes ([Lane]): a foreground request always goes ahead of background ones (a background request waits while any
 * foreground one is waiting), and background requests take at most [BACKGROUND_PER_SECOND] of a second's slots, so a
 * click during a first launch's warm-up finds free slots at once instead of queueing behind hundreds of key art asks.
 */
object TmdbGate {
    const val PER_SECOND = 35
    const val BACKGROUND_PER_SECOND = 24
    private val stamps = ArrayDeque<Long>()
    private val bgStamps = ArrayDeque<Long>()
    private var pausedUntil = 0L
    private var foregroundWaiting = 0
    private val lock = Object()

    /** Milliseconds since an arbitrary start; tests may replace it. */
    @Volatile internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    fun enter(background: Boolean = Lane.background) {
        synchronized(lock) {
            if (!background) foregroundWaiting++
            try {
                while (true) {
                    val wait = tryEnter(background) ?: return
                    lock.wait(wait.coerceIn(5L, 1000L))
                }
            } finally {
                if (!background) { foregroundWaiting--; lock.notifyAll() }
            }
        }
    }

    /** Takes a slot now (null) or says how long to wait (ms). Caller holds [lock] (tests may call it alone). */
    internal fun tryEnter(background: Boolean = false): Long? = synchronized(lock) {
        val now = clock()
        while (stamps.isNotEmpty() && now - stamps.first() >= 1000) stamps.removeFirst()
        while (bgStamps.isNotEmpty() && now - bgStamps.first() >= 1000) bgStamps.removeFirst()
        if (now < pausedUntil) return pausedUntil - now
        val full = stamps.firstOrNull()?.let { 1000 - (now - it) } ?: 50L
        if (stamps.size >= PER_SECOND) return full
        if (background) {
            // Behind any waiting foreground request (it is woken first), and within the background share.
            if (foregroundWaiting > 0) return 20L
            if (bgStamps.size >= BACKGROUND_PER_SECOND) return bgStamps.first().let { 1000 - (now - it) }
            bgStamps.addLast(now)
        }
        stamps.addLast(now)
        null
    }

    fun backOff(seconds: Double) {
        synchronized(lock) {
            val until = clock() + (seconds * 1000).toLong()
            if (until > pausedUntil) pausedUntil = until
        }
        Log.w("Net", "TMDB: rate limited, pausing requests ${"%.1f".format(seconds)} s")
    }

    internal fun reset() = synchronized(lock) { stamps.clear(); bgStamps.clear(); pausedUntil = 0L; foregroundWaiting = 0 }
}
