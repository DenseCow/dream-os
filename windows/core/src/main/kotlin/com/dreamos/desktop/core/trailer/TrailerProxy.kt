package com.dreamos.desktop.core.trailer

import com.dreamos.desktop.core.util.Log
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A loopback HTTP server that hands trailer streams to mpv at full speed.
 *
 * Why: the video host throttles an open-ended download to about the stream's own bitrate (measured: 1080p at
 * 0.4 MB/s, audio at 30 KB/s, 4K at 2.3 MB/s for a 2.2 MB/s stream), while bounded ranges come at full speed
 * (about 14 MB/s here). mpv (ffmpeg) asks for one open-ended range, so this server answers it by fetching the bytes
 * in bounded chunks, two in flight. Seeks are new requests, as usual.
 *
 * It also serves HLS masters cut to the classes asked for. Only 127.0.0.1, only random 128-bit paths it handed out itself.
 *
 * Heads ([warmHead]): a title's trailer resolved ahead (a card rested on, the page opening) has the first 512 KiB of
 * its picture and sound fetched right away, so mpv's first read is answered from memory and the picture starts
 * without a network round trip.
 *
 * Memory: two chunks per open connection (512 KiB first, then 4 MiB); at most [MAX_CONNECTIONS] connections, so
 * 128 MiB in the worst case (a trailer in Dream Player uses two or three). [MAX_HEADS] heads (4 MiB). [MAX_ENTRIES]
 * registered links at most.
 */
object TrailerProxy {
    private const val FIRST_CHUNK = 512L * 1024
    private const val CHUNK = 4L * 1024 * 1024
    private const val MAX_CONNECTIONS = 16
    private const val MAX_ENTRIES = 96
    private const val MAX_HEADS = 8

    /** [hlsMax] > 0: [url] is an HLS master, served cut to that class (fetched when mpv first asks). */
    private class Entry(val url: String, val mime: String, @Volatile var length: Long, val hlsMax: Int = 0)

    private val entries = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > MAX_ENTRIES
    }
    private val byUrl = HashMap<String, String>()
    /** Upstream link -> its first [FIRST_CHUNK] bytes (or fewer: the whole file), fetched or on their way. */
    private val heads = object : LinkedHashMap<String, CompletableFuture<ByteArray>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CompletableFuture<ByteArray>>?) = size > MAX_HEADS
    }
    private val random = SecureRandom()
    @Volatile private var server: HttpServer? = null

    /** Upstream fetches; HTTP/1.1 keeps each chunk on its own pooled connection (no head-of-line waits). */
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** A local URL serving [url]'s bytes (the same one for the same link). [length]: its size when known, else 0. */
    fun stream(url: String, mime: String, length: Long): String = register(url) { Entry(url, mime, length) }

    /** A local URL serving the HLS master at [masterUrl] cut to SDR variants up to [maxClass] ([TrailerPick.filterHlsMaster]). */
    fun hls(masterUrl: String, maxClass: Int): String =
        register("hls:$maxClass:$masterUrl") { Entry(masterUrl, HLS_MIME, 0, maxClass) } + ".m3u8"

    private const val HLS_MIME = "application/vnd.apple.mpegurl"

    /**
     * Fetches the first bytes of [url] now (in the background) so the stream's first read is served from memory.
     * [length]: its size when known. Once per link; a failed fetch is forgotten (the read goes to the network).
     */
    fun warmHead(url: String, length: Long) {
        synchronized(heads) {
            if (heads[url] != null) return
            val end = if (length > 0) minOf(length, FIRST_CHUNK) - 1 else FIRST_CHUNK - 1
            val f = body(fetch(url, 0L..end))
            heads[url] = f
            f.whenComplete { _, err -> if (err != null) synchronized(heads) { heads.remove(url, f) } }
        }
    }

    /** The warm head of [url] when it's fetched or on its way (not failed). */
    private fun head(url: String): CompletableFuture<ByteArray>? =
        synchronized(heads) { heads[url] }?.takeIf { !it.isCompletedExceptionally }

    private fun register(id: String, make: () -> Entry): String {
        val port = start()
        val token = synchronized(entries) {
            byUrl[id]?.takeIf { entries[it] != null } ?: run {
                val t = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
                entries[t] = make()
                byUrl[id] = t
                if (byUrl.size > MAX_ENTRIES * 2) byUrl.entries.removeIf { entries[it.value] == null }
                t
            }
        }
        return "http://127.0.0.1:$port/t/$token"
    }

    @Synchronized
    private fun start(): Int {
        server?.let { return it.address.port }
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 32)
        val pool = ThreadPoolExecutor(0, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, SynchronousQueue()) { r ->
            Thread(r, "dream-trailer-proxy").apply { isDaemon = true }
        }
        // Past MAX_CONNECTIONS the extra request is refused (mpv retries), never queued without bound.
        pool.rejectedExecutionHandler = ThreadPoolExecutor.AbortPolicy()
        s.executor = pool
        s.createContext("/t/") { ex -> handle(ex) }
        s.start()
        server = s
        Log.i("Trailer", "proxy on 127.0.0.1:${s.address.port}")
        return s.address.port
    }

    private fun handle(ex: HttpExchange) {
        try {
            val token = ex.requestURI.path.removePrefix("/t/").removeSuffix(".m3u8")
            val e = synchronized(entries) { entries[token] }
            if (e == null || (ex.requestMethod != "GET" && ex.requestMethod != "HEAD")) { ex.sendResponseHeaders(404, -1); return }
            if (e.hlsMax > 0) { serveHls(ex, e); return }
            serveStream(ex, e)
        } catch (io: IOException) {
            // mpv closed the connection (a seek, the player closing): nothing to do.
        } catch (t: Throwable) {
            Log.w("Trailer", "proxy: ${t.javaClass.simpleName}: ${TrailerPick.clean(t.message)}")
        } finally {
            ex.close()
        }
    }

    private fun serveHls(ex: HttpExchange, e: Entry) {
        val r = client.send(HttpRequest.newBuilder(URI(e.url)).timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.ofString())
        val cut = if (r.statusCode() == 200) TrailerPick.filterHlsMaster(r.body(), e.hlsMax) else null
        if (cut == null) { ex.sendResponseHeaders(502, -1); return }
        val bytes = cut.toByteArray()
        ex.responseHeaders.add("Content-Type", e.mime)
        if (ex.requestMethod == "HEAD") { ex.sendResponseHeaders(200, -1); return }
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun serveStream(ex: HttpExchange, e: Entry) {
        if (e.length <= 0) e.length = probeLength(e.url)
        val total = e.length
        if (total <= 0) { ex.sendResponseHeaders(502, -1); return }
        val range = ex.requestHeaders.getFirst("Range")
        val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
        val from = m?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val to = (m?.groupValues?.get(2)?.toLongOrNull() ?: (total - 1)).coerceAtMost(total - 1)
        if (from > to || from >= total) {
            ex.responseHeaders.add("Content-Range", "bytes */$total")
            ex.sendResponseHeaders(416, -1)
            return
        }
        ex.responseHeaders.add("Content-Type", e.mime)
        ex.responseHeaders.add("Accept-Ranges", "bytes")
        if (m != null) ex.responseHeaders.add("Content-Range", "bytes $from-$to/$total")
        val code = if (m != null) 206 else 200
        if (ex.requestMethod == "HEAD") { ex.sendResponseHeaders(code, -1); return }

        // Chunk plan: a small first chunk (the picture starts sooner), then 4 MiB ones; the next one is always on its way.
        // A warm head answers the start from memory (the network fetch takes over where it ends).
        val chunks = ArrayList<LongRange>()
        val warm = if (from < FIRST_CHUNK) head(e.url) else null
        val headEnd = minOf(to, total - 1, FIRST_CHUNK - 1)
        var at = from
        var size = FIRST_CHUNK
        if (warm != null) { chunks += from..headEnd; at = headEnd + 1; size = CHUNK }
        while (at <= to) {
            val end = minOf(to, at + size - 1)
            chunks += at..end
            at = end + 1
            size = CHUNK
        }
        val pending = ArrayDeque<CompletableFuture<ByteArray>>()
        // The network fetches themselves, cancelled when mpv lets go (cancelling a derived future wouldn't reach them).
        val fetches = ArrayList<CompletableFuture<*>>()
        fun net(c: LongRange) = fetch(e.url, c).also { synchronized(fetches) { fetches += it } }.let(::body)
        var next = 0
        fun ask() {
            if (next >= chunks.size) return
            val c = chunks[next]
            pending.addLast(if (next == 0 && warm != null) {
                // The head, cut to this read; if its fetch failed after all, this piece comes from the network.
                warm.thenApply { b -> check(b.size > c.last) { "short head" }; b.copyOfRange(c.first.toInt(), c.last.toInt() + 1) }
                    .exceptionallyCompose { net(c) }
            } else net(c))
            next++
        }
        ask(); ask()
        var headersSent = false
        try {
            val out = ex.responseBody
            while (pending.isNotEmpty()) {
                val bytes = pending.removeFirst().get(30, TimeUnit.SECONDS)
                if (!headersSent) { ex.sendResponseHeaders(code, to - from + 1); headersSent = true }
                ask()
                out.write(bytes)
            }
            out.flush()
        } catch (t: Exception) {
            if (!headersSent && t !is IOException) runCatching { ex.sendResponseHeaders(502, -1) }
            if (t !is IOException) Log.w("Trailer", "proxy: ${t.javaClass.simpleName}: ${TrailerPick.clean(t.message)}")
        } finally {
            pending.forEach { it.cancel(true) }
            synchronized(fetches) { fetches.forEach { it.cancel(true) } }
        }
    }

    private fun fetch(url: String, r: LongRange): CompletableFuture<HttpResponse<ByteArray>> =
        client.sendAsync(
            HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20))
                .header("Range", "bytes=${r.first}-${r.last}").GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    /** [r]'s bytes; fails when the host answers anything but the range (or the whole file). */
    private fun body(r: CompletableFuture<HttpResponse<ByteArray>>): CompletableFuture<ByteArray> = r.thenApply {
        check(it.statusCode() == 206 || it.statusCode() == 200) { "upstream answered ${it.statusCode()}" }
        it.body()
    }

    /** The full size from a one-byte range's Content-Range, 0 when the host won't say. */
    private fun probeLength(url: String): Long = try {
        val r = fetch(url, 0L..0L).get(15, TimeUnit.SECONDS)
        r.headers().firstValue("Content-Range").orElse("").substringAfter('/').toLongOrNull() ?: 0L
    } catch (e: Exception) { 0L }
}
