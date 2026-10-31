package com.dreamos.desktop.trailer.dev

import com.dreamos.desktop.core.content.Tmdb
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.trailer.TrailerPlayback
import com.dreamos.desktop.core.trailer.TrailerRef
import com.dreamos.desktop.trailer.BackdropVideo
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.Properties

/**
 * The Trailer button's request, checked without a window or sound: [TrailerPlayback] resolves the title's trailer as
 * Dream Player would ask for it, and each of its streams (the first, then the alternates) is opened in a silent,
 * windowless libmpv; time to the first frame, the picture's height and how far it buffers are printed.
 * `./gradlew :app:trailerSources [-Pmovie=<tmdb id>]`. Uses a temporary settings store.
 */
fun main() {
    System.setProperty("dream.trailer.silent", "true")
    val movie = System.getProperty("dream.movie")?.toIntOrNull() ?: 693134
    val store = DreamStore(Files.createTempDirectory("dream-trailer-sources"))
    DreamStore.use(store)
    val key = listOf(File("local.properties"), File("../local.properties")).firstOrNull { it.isFile }
        ?.let { f -> Properties().apply { f.reader().use { load(it) } }.getProperty("tmdb.key") }
    if (key.isNullOrBlank()) { System.err.println("tmdb.key missing from local.properties"); return }
    Tmdb.setKey(key)

    val ref = TrailerRef(movie, isShow = false, name = "Movie $movie")
    val tp = TrailerPlayback(ref)
    val target = TrailerPlayback.target(ref)
    val t0 = System.nanoTime()
    val req = runBlocking { tp.play(target, tp.pending(target).startMs) }
    println("REQUEST in ${(System.nanoTime() - t0) / 1_000_000} ms: start ${req.startMs} ms, ${1 + req.alternates.size} streams")
    for ((i, s) in (listOf(req.stream) + req.alternates).withIndex()) {
        var first = 0L
        val opened = System.nanoTime()
        val v = BackdropVideo(muted = true, onFrame = { if (first == 0L) first = System.nanoTime() }, onEvent = {})
        v.size(640, 360)
        v.load(s.url)
        val until = System.currentTimeMillis() + 20_000
        while (first == 0L && System.currentTimeMillis() < until) Thread.sleep(20)
        val ff = if (first == 0L) "none in 20 s" else "${(first - opened) / 1_000_000} ms"
        Thread.sleep(if (i == 0) 8000 else 3000)
        println("STREAM ${i + 1} (${s.quality.label}): first frame $ff, ${v.videoHeight}p decoded, ${"%.0f".format(v.cacheAheadSec)} s buffered ahead at ${"%.1f".format(v.positionMs / 1000.0)} s")
        v.destroy()
        Thread.sleep(500)
    }
    System.exit(0)
}
