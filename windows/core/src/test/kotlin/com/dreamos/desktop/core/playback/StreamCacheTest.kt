package com.dreamos.desktop.core.playback

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamCacheTest {
    private val size = 48L shl 20
    private fun byteAt(i: Long) = ((i * 31 + 7) % 251).toByte()
    private fun bytes(from: Long, to: Long) = ByteArray((to - from + 1).toInt()) { byteAt(from + it) }

    /** An origin like a debrid CDN: Range requests over a synthetic 48 MB file; counts bytes it sent. */
    private fun origin(sent: AtomicLong): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8).apply {
        createContext("/f.mkv") { ex ->
            val m = Regex("bytes=(\\d+)-(\\d*)").find(ex.requestHeaders.getFirst("Range") ?: "bytes=0-")!!
            val from = m.groupValues[1].toLong()
            val to = m.groupValues[2].toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
            ex.responseHeaders.add("Content-Range", "bytes $from-$to/$size")
            ex.sendResponseHeaders(206, to - from + 1)
            ex.responseBody.use { it.write(bytes(from, to)) }
            sent.addAndGet(to - from + 1)
        }
        start()
    }

    @Test fun headAndTailComeFromDiskAndTheRestFromTheLink() {
        val sent = AtomicLong()
        val o = origin(sent)
        val dir = Files.createTempDirectory("dream-stream").toFile()
        try {
            val cache = StreamCache(dir)
            val s = Stream("http://127.0.0.1:${o.address.port}/f.mkv", DebridServiceId.PREMIUMIZE, "Movie.mkv", size)
            cache.warm(s, 0, 0, "test")
            var local: String? = null
            for (i in 0 until 100) { Thread.sleep(50); local = cache.localUrl(s); if (local != null && sent.get() >= StreamCache.HEAD_BYTES + StreamCache.TAIL_BYTES) break }
            assertNotNull(local)
            Thread.sleep(200)
            val before = sent.get()
            val c = HttpClient.newHttpClient()
            fun get(from: Long, to: Long): ByteArray = c.send(HttpRequest.newBuilder(URI.create(local!!)).header("Range", "bytes=$from-$to").build(),
                HttpResponse.BodyHandlers.ofByteArray()).also { assertEquals(206, it.statusCode()) }.body()
            // The head and the tail: from disk, nothing new from the link.
            assertContentEquals(bytes(0, (1L shl 20) - 1), get(0, (1L shl 20) - 1))
            assertContentEquals(bytes(size - 1000, size - 1), get(size - 1000, size - 1))
            assertEquals(before, sent.get())
            // A range across the head's end and into the gap: disk, then the link, in one answer.
            val a = StreamCache.HEAD_BYTES - 500
            assertContentEquals(bytes(a, a + 2000), get(a, a + 2000))
            assertTrue(sent.get() - before in 1500L..(StreamCache.PIECE_BYTES * 8))
        } finally { o.stop(0); dir.deleteRecursively() }
    }
}
