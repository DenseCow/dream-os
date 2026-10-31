package com.dreamos.desktop.core.trailer

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Collections
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The proxy's warm heads: reads that start inside a warm head come from memory and the bytes are exactly the file's. */
class TrailerProxyHeadTest {
    private val file = Random(7).nextBytes(1_300_000)
    private val asked: MutableList<String> = Collections.synchronizedList(ArrayList())
    private lateinit var upstream: HttpServer
    private val client = HttpClient.newHttpClient()

    @BeforeTest fun start() {
        upstream = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8)
        upstream.createContext("/") { ex ->
            val range = ex.requestHeaders.getFirst("Range") ?: ""
            asked += range
            val m = Regex("bytes=(\\d+)-(\\d+)").find(range)!!
            val from = m.groupValues[1].toInt()
            val to = minOf(m.groupValues[2].toInt(), file.size - 1)
            ex.responseHeaders.add("Content-Range", "bytes $from-$to/${file.size}")
            ex.sendResponseHeaders(206, (to - from + 1).toLong())
            ex.responseBody.use { it.write(file, from, to - from + 1) }
        }
        upstream.start()
    }

    @AfterTest fun stop() = upstream.stop(0)

    private fun get(url: String, range: String?): ByteArray {
        val b = HttpRequest.newBuilder(URI(url))
        if (range != null) b.header("Range", range)
        return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray()).body()
    }

    private fun link(name: String) = "http://127.0.0.1:${upstream.address.port}/$name"

    @Test fun readsFromAWarmHeadAreExact() {
        val src = link("warm")
        val local = TrailerProxy.stream(src, "video/webm", file.size.toLong())
        TrailerProxy.warmHead(src, file.size.toLong())
        waitForAsks(1)
        Thread.sleep(100) // the head's bytes arrive
        assertEquals(listOf("bytes=0-524287"), asked.toList())
        assertContentEquals(file, get(local, "bytes=0-"))
        // The head served the start: the network was asked only from where it ends.
        assertEquals("bytes=524288-1299999", asked[1])
        assertTrue(asked.none { it.startsWith("bytes=0-") && it != "bytes=0-524287" })
        assertContentEquals(file.copyOfRange(100, 201), get(local, "bytes=100-200"))
        assertContentEquals(file.copyOfRange(500_000, 600_001), get(local, "bytes=500000-600000"))
        assertContentEquals(file.copyOfRange(900_000, file.size), get(local, "bytes=900000-"))
    }

    @Test fun withoutAHeadTheNetworkServesEverything() {
        val src = link("cold")
        val local = TrailerProxy.stream(src, "video/webm", file.size.toLong())
        assertContentEquals(file, get(local, null))
        // Two chunks are always on their way, so they may reach the host in either order.
        assertEquals(setOf("bytes=0-524287", "bytes=524288-1299999"), asked.toSet())
    }

    @Test fun aHeadIsFetchedOncePerLink() {
        val src = link("once")
        TrailerProxy.warmHead(src, file.size.toLong())
        TrailerProxy.warmHead(src, file.size.toLong())
        waitForAsks(1)
        Thread.sleep(100)
        assertEquals(1, asked.size)
    }

    private fun waitForAsks(n: Int) {
        val until = System.currentTimeMillis() + 5000
        while (asked.size < n && System.currentTimeMillis() < until) Thread.sleep(10)
    }
}
