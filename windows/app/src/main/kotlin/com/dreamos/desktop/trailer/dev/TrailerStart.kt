package com.dreamos.desktop.trailer.dev

import com.dreamos.desktop.core.content.Tmdb
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.trailer.TrailerEngine
import com.dreamos.desktop.core.trailer.TrailerRef
import com.dreamos.desktop.trailer.BackdropVideo
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.Properties

/**
 * DEV TOOL: which of a resolved trailer's streams shows its first frame soonest in the hero's libmpv (silent,
 * windowless, 1920 x 929 like a 1080p window's hero), with and without the warm heads. Also the libmpv's own start-up.
 * `./gradlew :app:trailerStart [-Ptitle=movie:693134]`. Throwaway data folder, temporary settings store.
 */
fun main() {
    System.setProperty("dream.trailer.silent", "true")
    val spec = System.getProperty("dream.title") ?: "movie:693134"
    DreamStore.use(DreamStore(Files.createTempDirectory("dream-trailer-start")))
    val key = listOf(File("local.properties"), File("../local.properties")).firstOrNull { it.isFile }
        ?.let { f -> Properties().apply { f.reader().use { load(it) } }.getProperty("tmdb.key") }
    if (key.isNullOrBlank()) { System.err.println("tmdb.key missing from local.properties"); return }
    Tmdb.setKey(key)
    val ref = TrailerRef(spec.substringAfter(':').toInt(), spec.startsWith("tv:"), spec)
    val t = runBlocking { TrailerEngine.resolve(ref) } ?: run { println("no trailer"); System.exit(1); return }

    fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000
    fun firstFrame(label: String, url: String) {
        val c0 = System.nanoTime()
        var first = 0L
        val v = BackdropVideo(muted = true, onFrame = { if (first == 0L) first = System.nanoTime() }, onEvent = {})
        val created = ms(c0)
        v.size(1920, 929)
        val l0 = System.nanoTime()
        v.load(url)
        val until = System.currentTimeMillis() + 15_000
        while (first == 0L && System.currentTimeMillis() < until) Thread.sleep(5)
        println("START $spec $label: libmpv ready ${created} ms, first frame ${if (first == 0L) "none" else "${(first - l0) / 1_000_000} ms"}")
        v.destroy()
        Thread.sleep(800)
    }
    fun edl(cls: Int) = t.sources(cls).first { it.cls > 0 && it.sound }.url
    val hls = t.sources(1080).firstOrNull { it.cls == 0 }?.url
    val mp4 = t.sources(1080).lastOrNull { it.label.startsWith("MP4") }?.url
    firstFrame("1080p EDL, cold connection", edl(1080))
    firstFrame("720p EDL", edl(720))
    firstFrame("1080p EDL", edl(1080))
    firstFrame("1440p EDL", edl(1440))
    hls?.let { firstFrame("HLS up to 1080p", it) }
    mp4?.let { firstFrame("MP4 360p", it) }
    t.warmHeads(1080); t.warmHeads(720); t.warmHeads(1440)
    Thread.sleep(1500)
    firstFrame("1080p EDL, warm head", edl(1080))
    firstFrame("720p EDL, warm head", edl(720))
    firstFrame("1440p EDL, warm head", edl(1440))
    firstFrame("1080p EDL, warm head again", edl(1080))
    System.exit(0)
}
