package com.dreamos.desktop.core.trailer

import com.dreamos.desktop.core.TestSupport
import com.dreamos.desktop.core.content.Tmdb
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real thing, over the network: TMDB's list, NewPipe Extractor, and bytes through the proxy. Runs only with
 * `./gradlew :core:test --tests "*TrailerLiveTest*" -Pdream.live=true` (needs tmdb.key in local.properties).
 */
class TrailerLiveTest {
    private val live = System.getProperty("dream.live") == "true"

    @Test fun resolvesAndStreamsAtFullSpeed() {
        if (!live) return
        TestSupport.freshStore()
        Tmdb.setKey(assertNotNull(TestSupport.localProperty("tmdb.key"), "tmdb.key in local.properties"))
        val dune2 = TrailerRef(693134, isShow = false, name = "Dune: Part Two")
        val severance = TrailerRef(95396, isShow = true, name = "Severance")

        for (ref in listOf(dune2, severance)) {
            val t0 = System.nanoTime()
            val t = assertNotNull(runBlocking { TrailerEngine.resolve(ref) }, "a trailer for ${ref.name}")
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("LIVE ${ref.name}: ${t.videoKey} resolved in $ms ms: ${t.streams.label}")
            val t1 = System.nanoTime()
            runBlocking { TrailerEngine.resolve(ref) }
            println("LIVE ${ref.name}: cached lookup in ${(System.nanoTime() - t1) / 1000} us")
            assertTrue(t.streams.video.isNotEmpty() && t.streams.audio.isNotEmpty())
            for (s in t.sources(2160)) println("LIVE   source: ${s.label}")
        }

        // Bytes through the proxy: the first 16 MB of Dune's best picture, as mpv would ask (one open-ended range).
        val t = runBlocking { TrailerEngine.resolve(dune2) }!!
        val v = t.streams.videoUpTo(2160)!!
        val local = TrailerProxy.stream(v.url, v.mime, v.length)
        val client = HttpClient.newHttpClient()
        val want = 16L * 1024 * 1024
        val t2 = System.nanoTime()
        val r = client.send(HttpRequest.newBuilder(URI(local)).header("Range", "bytes=0-").build(), HttpResponse.BodyHandlers.ofInputStream())
        assertEquals(206, r.statusCode())
        var got = 0L
        var firstByteMs = -1L
        r.body().use { input ->
            val buf = ByteArray(256 * 1024)
            while (got < want) {
                val n = input.read(buf)
                if (n < 0) break
                if (firstByteMs < 0) firstByteMs = (System.nanoTime() - t2) / 1_000_000
                got += n
            }
        }
        val secs = (System.nanoTime() - t2) / 1e9
        println("LIVE proxy ${v.label}: first bytes after $firstByteMs ms, ${got / 1_048_576} MB in ${"%.2f".format(secs)} s = ${"%.1f".format(got / 1_048_576.0 / secs)} MB/s")
        assertTrue(got >= want)

        // The HLS fallback, cut.
        t.streams.hlsUrl?.let {
            val text = client.send(HttpRequest.newBuilder(URI(TrailerProxy.hls(it, 1080))).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, text.statusCode())
            assertTrue(text.body().contains("#EXT-X-STREAM-INF") && !text.body().contains("RESOLUTION=3840x2160"))
            println("LIVE HLS cut to 1080p: ${text.body().lines().count { l -> l.startsWith("#EXT-X-STREAM-INF") }} variants")
        }
    }
}
