package com.dreamos.desktop.setup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * The connection test after setup's check (iPhone SpeedTest): three parallel downloads from Cloudflare's speed
 * endpoint, 0.6 s warm-up, then 4 s measured. Bounded: about 5 s and at most 3 x 40 MB.
 */
object SpeedTest {
    private const val URL = "https://speed.cloudflare.com/__down?bytes=40000000"

    suspend fun run(live: (Int) -> Unit): Int? = withContext(Dispatchers.IO) {
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build()
        val bytes = AtomicLong(0)
        val start = System.nanoTime()
        val warm = start + 600_000_000L
        val end = warm + 4_000_000_000L
        var counted = 0L
        runCatching {
            coroutineScope {
                (1..3).map {
                    async {
                        val res = http.send(HttpRequest.newBuilder(URI(URL)).timeout(Duration.ofSeconds(6)).build(), HttpResponse.BodyHandlers.ofInputStream())
                        res.body().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (System.nanoTime() < end) {
                                val n = input.read(buf); if (n < 0) break
                                if (System.nanoTime() >= warm) bytes.addAndGet(n.toLong())
                            }
                        }
                    }
                } + async {
                    while (System.nanoTime() < end) {
                        kotlinx.coroutines.delay(400)
                        val secs = (System.nanoTime() - warm) / 1e9
                        if (secs > 0.3) live((bytes.get() * 8 / secs / 1e6).toInt())
                    }
                }
            }.let { }
        }
        counted = bytes.get()
        if (counted <= 0) null else (counted * 8 / 4.0 / 1e6).toInt()
    }

    /** "Great for 4K" and the rest of the verdicts. */
    fun verdict(mbps: Int): String = when {
        mbps >= 40 -> "Great for 4K"; mbps >= 25 -> "Good for 4K"; mbps >= 8 -> "Great for 1080p"; mbps >= 4 -> "Good for 720p"; else -> "Slower Connection"
    }
}
