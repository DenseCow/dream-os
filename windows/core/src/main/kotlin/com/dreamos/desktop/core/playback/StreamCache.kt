package com.dreamos.desktop.core.playback

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Instant Play's head start on the stream itself (fire-home StreamCache, iPhone StreamCache + StreamProxy, sized for
 * a PC): once a link is ready ahead of Play (a title page's look-ahead with a free service, Continue Watching, the next
 * episode during the credits), the parts of the file the player reads first go to disk: the head (container header and
 * the first seconds), the tail (an MKV's seek index, read before the first frame) and, for a resume, a window around
 * where the position lands. Dream Player then opens the stream through a loopback HTTP server ([localUrl]): bytes on
 * disk come from disk, the rest from the same link in the same response, so the first frame no longer waits for a TLS
 * connect, the header and the index over the network.
 *
 * Sizes, for a PC (the iPhone keeps 96 MB of 2 titles): a 32 MB head (96 MB for a file over 12 GB, about 10 s of a 4K
 * remux, so mpv's first buffering comes from disk), a 4 MB tail, 4 MB before and 32 MB after a resume point: at most
 * 136 MB per title. [BUDGET_BYTES] on disk at most (1 GB, plus the two prefetches under way), [MAX_TITLES] titles (the
 * newest win), entries dropped after [ENTRY_TTL] (debrid links don't live longer). [PREFETCHES] titles fill at once,
 * low priority; [stop] when a player opens another title. Only 127.0.0.1 is served; URLs are never logged in full.
 */
class StreamCache(private val root: File, private val clock: Clock = Clock.SYSTEM) {
    companion object {
        const val HEAD_BYTES = 32L shl 20
        const val BIG_HEAD_BYTES = 96L shl 20
        const val TAIL_BYTES = 4L shl 20
        const val RESUME_BEFORE = 4L shl 20
        const val RESUME_AFTER = 32L shl 20
        const val MAX_TITLES = 8
        const val BUDGET_BYTES = 1L shl 30
        const val PREFETCHES = 2
        const val ENTRY_TTL = 2 * 3_600_000L
        const val PIECE_BYTES = 4L shl 20
        private const val BIG_FILE = 12L shl 30

        fun idOf(url: String): String = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
    }

    private class Segment(val offset: Long, @Volatile var length: Long, val file: File)
    private class Entry(val id: String, val url: String, val headers: Map<String, String>, @Volatile var length: Long, val name: String,
                        val at: Long, val segments: MutableList<Segment> = ArrayList())

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build()
    private val prefetch = ThreadPoolExecutor(PREFETCHES, PREFETCHES, 30, TimeUnit.SECONDS, LinkedBlockingDeque()) { r ->
        Thread(r, "stream-cache").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    @Volatile private var stopId: String? = null
    @Volatile private var server: HttpServer? = null

    @Volatile var enabled = true

    init { root.deleteRecursively(); root.mkdirs() } // nothing outlives a run: links expire anyway

    /**
     * Fetches [s]'s head, tail and (for a resume at [startMs] of [durationMs]) the window around it, in the background.
     * Returns at once; a second warm of the same link is skipped.
     */
    fun warm(s: Stream, startMs: Long, durationMs: Long, label: String) {
        if (!enabled) return
        val id = idOf(s.secureUrl)
        synchronized(lock) {
            if (entries.containsKey(id)) return
            entries[id] = Entry(id, s.secureUrl, s.headers, s.sizeBytes, s.fileName.ifBlank { "stream" }, clock.monotonicMs())
            trim()
        }
        stopId = null
        prefetch.execute { fill(id, startMs, durationMs, label) }
    }

    /** The player opens another title: a prefetch for [keepId] stays, any other stops. */
    fun stop(keepUrl: String?) { stopId = keepUrl?.let { idOf(it) } ?: "" }

    /** [url] failed: its bytes go. */
    fun forget(url: String) {
        val e = synchronized(lock) { entries.remove(idOf(url)) } ?: return
        e.segments.forEach { it.file.delete() }
    }

    /** The loopback address to open [s] through when its head is on disk; null: open the link itself. */
    fun localUrl(s: Stream): String? {
        if (!enabled) return null
        val e = synchronized(lock) { entries[idOf(s.secureUrl)] } ?: return null
        val head = synchronized(lock) { e.segments.firstOrNull { it.offset == 0L } } ?: return null
        if (head.length < (1L shl 20) || clock.monotonicMs() - e.at > ENTRY_TTL) return null
        val port = server()?.address?.port ?: return null
        return "http://127.0.0.1:$port/s/${e.id}/${DebridHttp.enc(e.name).replace("+", "%20")}"
    }

    private fun trim() {
        val now = clock.monotonicMs()
        entries.values.filter { now - it.at > ENTRY_TTL }.forEach { drop(it) }
        while (entries.size > MAX_TITLES) drop(entries.values.first())
        var total = entries.values.sumOf { e -> e.segments.sumOf { it.length } }
        while (total > BUDGET_BYTES && entries.size > 1) {
            val e = entries.values.first()
            total -= e.segments.sumOf { it.length }
            drop(e)
        }
    }

    private fun drop(e: Entry) { entries.remove(e.id); e.segments.forEach { it.file.delete() } }

    // ---- the prefetch ----

    private fun fill(id: String, startMs: Long, durationMs: Long, label: String) {
        val e = synchronized(lock) { entries[id] } ?: return
        val t0 = clock.monotonicMs()
        val head = if (e.length > BIG_FILE) BIG_HEAD_BYTES else HEAD_BYTES
        var got = fetch(e, 0, head)
        val len = e.length
        if (len > head + TAIL_BYTES) got += fetch(e, len - TAIL_BYTES, TAIL_BYTES)
        if (startMs > 0 && durationMs > 0 && len > 0) {
            val at = (len.toDouble() * startMs / durationMs).toLong()
            val from = (at - RESUME_BEFORE).coerceAtLeast(head)
            val to = (at + RESUME_AFTER).coerceAtMost(len - TAIL_BYTES)
            if (to > from) got += fetch(e, from, to - from)
        }
        PlaybackLog.i("Stream cache: $label: ${got shr 20} MB ahead in ${DreamCore.secs(clock.monotonicMs() - t0)} of ${len shr 20} MB")
        synchronized(lock) { trim() }
    }

    /** [count] bytes from [offset] into a segment file, in pieces; stops when told to. Returns the bytes kept. */
    private fun fetch(e: Entry, offset: Long, count: Long): Long {
        val f = File(root, "${e.id}-$offset.part")
        val seg = Segment(offset, 0, f)
        synchronized(lock) { e.segments += seg }
        try {
            RandomAccessFile(f, "rw").use { out ->
                var pos = offset
                val end = offset + count
                while (pos < end) {
                    if (stopId != null && stopId != e.id) return seg.length
                    val n = minOf(PIECE_BYTES, end - pos)
                    val r = open(e, pos, pos + n - 1) ?: break
                    if (r.statusCode() != 206 && !(r.statusCode() == 200 && pos == 0L)) { r.body().close(); break }
                    r.headers().firstValue("Content-Range").orElse(null)?.substringAfter('/')?.toLongOrNull()?.let { if (e.length <= 0) e.length = it }
                    var left = n
                    r.body().use { inp ->
                        val buf = ByteArray(64 * 1024)
                        while (left > 0) {
                            val k = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (k < 0) break
                            out.write(buf, 0, k)
                            left -= k
                            seg.length += k
                        }
                    }
                    if (left > 0) break
                    pos += n
                }
            }
        } catch (x: Exception) {
            PlaybackLog.w("Stream cache: prefetch stopped (${x.javaClass.simpleName})")
        }
        return seg.length
    }

    private fun open(e: Entry, from: Long, to: Long?): HttpResponse<InputStream>? = try {
        val b = HttpRequest.newBuilder(URI.create(e.url)).timeout(Duration.ofSeconds(20))
            .header("Range", "bytes=$from-${to ?: ""}").header("User-Agent", DebridHttp.UA)
        e.headers.forEach { (k, v) -> b.header(k, v) }
        http.send(b.GET().build(), HttpResponse.BodyHandlers.ofInputStream())
    } catch (x: Exception) { null }

    // ---- the loopback server ----

    private fun server(): HttpServer? {
        server?.let { return it }
        synchronized(lock) {
            server?.let { return it }
            return try {
                HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16).apply {
                    executor = Executors.newFixedThreadPool(8) { r -> Thread(r, "stream-proxy").apply { isDaemon = true } }
                    createContext("/s/") { ex -> try { serve(ex) } catch (x: Exception) { } finally { ex.close() } }
                    start()
                }.also { server = it }
            } catch (x: Exception) { PlaybackLog.w("Stream cache: loopback server couldn't start (${x.javaClass.simpleName})"); null }
        }
    }

    /**
     * GET /s/<id>/<name> with an optional Range: the bytes from disk where a segment covers them, else from the link,
     * in one response. Only loopback clients.
     */
    private fun serve(ex: HttpExchange) {
        if (!ex.remoteAddress.address.isLoopbackAddress) { ex.sendResponseHeaders(403, -1); return }
        val id = ex.requestURI.path.removePrefix("/s/").substringBefore('/')
        val e = synchronized(lock) { entries[id] } ?: run { ex.sendResponseHeaders(404, -1); return }
        if (e.length <= 0) {
            // Size unknown: ask the link once (a 1-byte range answers with the total).
            open(e, 0, 0)?.let { r -> r.body().close(); r.headers().firstValue("Content-Range").orElse(null)?.substringAfter('/')?.toLongOrNull()?.let { e.length = it } }
            if (e.length <= 0) { ex.sendResponseHeaders(502, -1); return }
        }
        val len = e.length
        val range = ex.requestHeaders.getFirst("Range")
        val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
        var from = m?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        var to = m?.groupValues?.get(2)?.toLongOrNull() ?: (len - 1)
        if (m != null && m.groupValues[1].isEmpty() && m.groupValues[2].isNotEmpty()) { from = len - to; to = len - 1 } // suffix range
        to = to.coerceAtMost(len - 1)
        if (from > to || from >= len) {
            ex.responseHeaders.add("Content-Range", "bytes */$len"); ex.sendResponseHeaders(416, -1); return
        }
        ex.responseHeaders.add("Accept-Ranges", "bytes")
        ex.responseHeaders.add("Content-Type", contentType(e.name))
        if (m != null) ex.responseHeaders.add("Content-Range", "bytes $from-$to/$len")
        val count = to - from + 1
        if (ex.requestMethod == "HEAD") { ex.responseHeaders.add("Content-Length", count.toString()); ex.sendResponseHeaders(if (m != null) 206 else 200, -1); return }
        ex.sendResponseHeaders(if (m != null) 206 else 200, count)
        ex.responseBody.use { out -> copy(e, from, to, out) }
    }

    /** Bytes [from]..[to] to [out]: disk where a segment holds them, the link for the gaps. */
    private fun copy(e: Entry, from: Long, to: Long, out: OutputStream) {
        var pos = from
        val buf = ByteArray(64 * 1024)
        while (pos <= to) {
            val seg = synchronized(lock) { e.segments.firstOrNull { pos >= it.offset && pos < it.offset + it.length } }
            if (seg != null) {
                RandomAccessFile(seg.file, "r").use { f ->
                    f.seek(pos - seg.offset)
                    val end = minOf(to + 1, seg.offset + seg.length)
                    while (pos < end) {
                        val k = f.read(buf, 0, minOf(buf.size.toLong(), end - pos).toInt())
                        if (k <= 0) break
                        out.write(buf, 0, k); pos += k
                    }
                }
                continue
            }
            // A gap: from the link up to the next segment on disk (or the end of the request).
            val next = synchronized(lock) { e.segments.filter { it.offset > pos && it.length > 0 }.minOfOrNull { it.offset } }
            val gapEnd = minOf(to, (next ?: Long.MAX_VALUE) - 1)
            val r = open(e, pos, if (gapEnd == len(e) - 1) null else gapEnd) ?: return
            if (r.statusCode() != 206) { r.body().close(); return }
            r.body().use { inp ->
                while (pos <= gapEnd) {
                    val k = inp.read(buf, 0, minOf(buf.size.toLong(), gapEnd - pos + 1).toInt())
                    if (k < 0) return
                    out.write(buf, 0, k); pos += k
                }
            }
        }
    }

    private fun len(e: Entry) = e.length

    private fun contentType(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"; "mp4", "m4v" -> "video/mp4"; "webm" -> "video/webm"; "avi" -> "video/x-msvideo"
        "ts" -> "video/mp2t"; "mov" -> "video/quicktime"; else -> "application/octet-stream"
    }
}
