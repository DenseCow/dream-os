package com.dreamos.desktop.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Art for every card, hero and tile: memory first, then disk, then the network, so art is usually on screen in the
 * same frame as its card and never visibly pops in.
 *
 * Sized for a PC (owner, 2026-10-30: the PC spends memory and cores on speed): decoded images in memory up to
 * [MEM_BYTES] (1 GB of native Skia memory, outside the Java heap; about 2,500 card images or 250 full-HD backdrops),
 * evicted least recently used; encoded files on disk up to [DISK_BYTES] (2 GB) under %LOCALAPPDATA%\Dream OS\Cache\images
 * (core's cache folder, the same place as before), the oldest deleted first; at most [PARALLEL] downloads and decodes at
 * once, each given [TIMEOUT_MS] before the next art choice is tried.
 */
object Images {
    private const val MEM_BYTES = 1024L shl 20
    private const val DISK_BYTES = 2048L shl 20
    private const val PARALLEL = 16
    private const val TIMEOUT_MS = 6_000L

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(PARALLEL)
    private val scope = CoroutineScope(SupervisorJob() + io)
    // HTTP/1.1: each download its own pooled connection, so 16 at once share the line instead of one HTTP/2
    // connection, and one stalled connection holds up one image, not all of them.
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val mem = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {}
    private var memBytes = 0L
    private val inflight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    private val writes = AtomicInteger()

    /** Under core's cache folder, so offscreen runs on a throwaway data folder never read or fill the viewer's. */
    val dir: File by lazy {
        com.dreamos.desktop.core.platform.Platform.current.cacheDir.resolve("images").toFile()
            .apply { mkdirs() }.also { d -> scope.launch { trimDisk(d) } }
    }

    /** The decoded image if it is in memory now (a card's first frame). */
    fun cached(url: String?): ImageBitmap? = url?.let { synchronized(mem) { mem[it] } }

    /** Memory, disk, then the network; null when the art can't be had. One fetch per URL however many ask. */
    suspend fun load(url: String): ImageBitmap? {
        cached(url)?.let { return it }
        val job = inflight.computeIfAbsent(url) { u -> scope.async { fetch(u).also { inflight.remove(u) } } }
        return job.await()
    }

    /**
     * Prefetches take at most [BACKGROUND] of the [PARALLEL] slots, in the order asked, so art for what is on screen
     * (cards drawing, a row about to show) never waits behind hundreds of prefetches on a first launch.
     */
    private const val BACKGROUND = 6
    private val background = kotlinx.coroutines.sync.Semaphore(BACKGROUND)

    /** Art that came in after its card or hero was first drawn (a pop-in; the speed harness counts them). */
    val lateShown = AtomicInteger()

    /** Starts loading [urls] in the background (rows ahead of the viewer, the next hero, other pages' first cards). */
    fun prefetch(urls: List<String?>) {
        for (u in urls) if (u != null && cached(u) == null && !inflight.containsKey(u)) scope.launch {
            background.acquire()
            try { if (cached(u) == null) load(u) } finally { background.release() }
        }
    }

    /** Loads [urls] and waits up to [maxMs] for all of them: a row shows once its first cards' art is in. */
    suspend fun warm(urls: List<String?>, maxMs: Long = 1_500L) {
        val jobs = urls.filterNotNull().filter { cached(it) == null }.map { u -> scope.async { load(u) } }
        withTimeoutOrNull(maxMs) { jobs.forEach { it.await() } }
    }

    private fun fetch(url: String): ImageBitmap? {
        val file = File(dir, key(url))
        val bytes = if (file.isFile) runCatching { file.readBytes().also { file.setLastModified(System.currentTimeMillis()) } }.getOrNull()
        else download(url)?.also { b ->
            runCatching { File(dir, file.name + ".part").apply { writeBytes(b); renameTo(file) } }
            if (writes.incrementAndGet() % 200 == 0) trimDisk(dir)
        }
        val bmp = bytes?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() } ?: return null
        put(url, bmp)
        return bmp
    }

    /** Downloads that failed or ran out of time (the speed harness reports it). */
    val failed = AtomicInteger()

    /**
     * The request's own timeout only covers the wait for the headers; a body that stalls would hold a download slot
     * for good (seen: every slot stuck, no art loading at all). So the whole download has [TIMEOUT_MS] twice over.
     */
    private fun download(url: String): ByteArray? {
        val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMillis(TIMEOUT_MS)).header("User-Agent", "DreamOS").GET().build()
        val f = http.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
        return try {
            f.get(TIMEOUT_MS * 2, java.util.concurrent.TimeUnit.MILLISECONDS).takeIf { it.statusCode() == 200 }?.body()
        } catch (e: Exception) {
            f.cancel(true)
            null
        } ?: run { failed.incrementAndGet(); null }
    }

    private fun put(url: String, bmp: ImageBitmap) = synchronized(mem) {
        val size = bmp.width.toLong() * bmp.height * 4
        mem.put(url, bmp)?.let { memBytes -= it.width.toLong() * it.height * 4 }
        memBytes += size
        val it = mem.entries.iterator()
        while (memBytes > MEM_BYTES && it.hasNext()) {
            val e = it.next()
            if (e.key == url) continue
            memBytes -= e.value.width.toLong() * e.value.height * 4
            it.remove()
        }
    }

    /** Drops decoded images (the player calls this when playback starts). Disk stays. */
    fun trimMemory() = synchronized(mem) { mem.clear(); memBytes = 0 }

    private fun trimDisk(d: File) {
        val files = d.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_BYTES * 8 / 10) break
            total -= f.length()
            f.delete()
        }
    }

    private fun key(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * Remote art: the first of [urls] that loads (later ones are fallbacks when one fails). When the art is already in
 * memory it draws in the first frame; otherwise [placeholder] shows and the art fades in over 180 ms. Choices from
 * index [fitFrom] on (a poster standing in for a missing backdrop) are drawn whole, centered, never cropped.
 */
@Composable
fun DreamImage(
    urls: List<String?>,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    fitFrom: Int = Int.MAX_VALUE,
    placeholder: @Composable () -> Unit = {},
) {
    val list = remember(urls) { urls.withIndex().filter { it.value != null } }
    // Only the first choice counts as "already here": a cached fallback (a backdrop for a poster) would show the
    // wrong art and then swap.
    val first = remember(list) { list.firstOrNull()?.let { f -> Images.cached(f.value)?.let { f.index to it } } }
    var bmp by remember(list) { mutableStateOf(first) }
    val fade = remember(list) { Animatable(if (first != null) 1f else 0f) }
    LaunchedEffect(list) {
        if (bmp != null) return@LaunchedEffect
        for ((i, u) in list) {
            val b = Images.load(u!!)
            if (b != null) { bmp = i to b; break }
        }
        if (bmp != null) {
            Images.lateShown.incrementAndGet()
            fade.animateTo(1f, tween(180))
        }
    }
    Box(modifier) {
        if (fade.value < 1f) placeholder()
        bmp?.let { (i, b) ->
            val fit = i >= fitFrom
            Image(b, null, Modifier.fillMaxSize().alpha(fade.value), alignment = if (fit) Alignment.Center else alignment,
                contentScale = if (fit) ContentScale.Fit else contentScale)
        }
    }
}

/** TMDB image URLs at the size a card needs ([w] is the drawn width in pixels). */
object Tmdb {
    private const val BASE = "https://image.tmdb.org/t/p/"
    // Full quality: the first size at least as wide as the drawn pixels (never a softer one scaled up), and TMDB's
    // original past its largest size.
    fun poster(path: String?, w: Float): String? = path?.let { BASE + pick(w, listOf(185, 342, 500, 780)) + it }
    fun backdrop(path: String?, w: Float): String? = path?.let { BASE + pick(w, listOf(300, 780, 1280)) + it }
    fun logo(path: String?, w: Float): String? = path?.let { BASE + pick(w, listOf(300, 500)) + it }
    fun profile(path: String?, w: Float): String? = path?.let { BASE + pick(w, listOf(185, 632)) + it }

    private fun pick(w: Float, sizes: List<Int>) = sizes.firstOrNull { it >= w }?.let { "w$it" } ?: "original"
}
