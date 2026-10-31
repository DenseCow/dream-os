package com.dreamos.desktop.trailer.dev

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.dreamos.desktop.core.content.Tmdb
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.trailer.TrailerRef
import com.dreamos.desktop.core.util.Log
import com.dreamos.desktop.details.DevTitleSource
import com.dreamos.desktop.details.TitleDetails
import com.dreamos.desktop.details.TitlePage
import com.dreamos.desktop.details.TitleSource
import com.dreamos.desktop.settings.FakeServices
import com.dreamos.desktop.settings.LocalPagesServices
import com.dreamos.desktop.settings.kit.LocalPagesNav
import com.dreamos.desktop.settings.kit.LoggingNav
import com.dreamos.desktop.settings.kit.TitleRef
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

/**
 * DEV TOOL: how long after a title page opens its hero trailer shows, offscreen and silent (no window, no sound, no
 * pointer). One title per run (a fresh JVM is a cold start: nothing resolved, the extractor's player script not yet
 * fetched). `-Pmode=warm` rests on the title's card first ([HOVER_MS] before the click, the card's hover hook firing
 * after its 300 ms rest), as a viewer pointing at a card and clicking it does.
 *
 * `-Phome=true`: Home's hero has readied another title's trailer first (the extractor's player script is fetched).
 * `./gradlew :app:trailerTiming -Ptitle=movie:693134 [-Pmode=cold|warm] [-Phome=true] [-Psize=960x540]`; prints one
 * RESULT line.
 * The task points APPDATA and LOCALAPPDATA into a throwaway folder; the settings store is a temporary one too.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    System.setProperty("dream.trailer.silent", "true")
    val spec = System.getProperty("dream.title") ?: "movie:693134"
    val warm = System.getProperty("dream.mode") == "warm"
    val (w, h) = (System.getProperty("dream.size") ?: "960x540").split("x").map { it.toInt() }
    val isShow = spec.startsWith("tv:")
    val id = spec.substringAfter(':').toInt()
    val ref = TitleRef(id, isShow, spec)

    DreamStore.use(DreamStore(Files.createTempDirectory("dream-trailer-timing")))
    val key = listOf(File("local.properties"), File("../local.properties")).firstOrNull { it.isFile }
        ?.let { f -> Properties().apply { f.reader().use { load(it) } }.getProperty("tmdb.key") }
    if (key.isNullOrBlank()) { System.err.println("tmdb.key missing from local.properties"); return }
    Tmdb.setKey(key)

    val marks = ConcurrentHashMap<String, Long>()
    var t0 = 0L
    // Absolute times: a lookup done during the hover finishes before the page opens (negative in the result).
    fun mark(k: String) { marks.putIfAbsent(k, System.nanoTime()) }
    Log.sink = { line ->
        when {
            "hero: first frame" in line -> mark("firstFrame")
            "hero: picture up" in line -> mark("reveal")
            "hero: ready" in line -> mark("prerolled")
            "hero: resting" in line -> mark("landed")
            " ready in " in line -> mark("resolved")
        }
    }
    val dev = DevTitleSource.fromLocalProperties()
    // Pages readied by a card's rest (TitleAhead's part: details and hero art) show in their first frame.
    val built = ConcurrentHashMap<TitleRef, TitleDetails>()
    val source = object : TitleSource by dev {
        override suspend fun details(ref: TitleRef): TitleDetails = dev.details(ref).also { mark("details") }
        override fun instant(ref: TitleRef): TitleDetails? = built[ref]
    }

    val fakes = FakeServices.create()
    lateinit var scene: ImageComposeScene
    fun ui(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
    fun open(r: TitleRef) = ui {
        t0 = System.nanoTime()
        scene = ImageComposeScene(w, h, Density(1f), coroutineContext = Dispatchers.Main) {
            CompositionLocalProvider(LocalPagesNav provides LoggingNav, LocalPagesServices provides fakes) {
                TitlePage(r, source, onBack = {})
            }
        }
    }
    fun renderUntil(ms: Long, done: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        // The page's coroutines (the hero's rest, its picture checks) run only inside render(), and an offscreen frame
        // of this page takes 140 to 200 ms at 960 x 540 (250 to 800 ms at 1920 x 1080): times here are late by up to
        // one frame, which a real window doesn't add. Hence the small default size.
        while (System.currentTimeMillis() < end && !done()) { ui { scene.render(System.nanoTime()) }; Thread.sleep(16) }
    }

    // A warm app first: another title's page (trailers off, so nothing of the trailer path warms), as when the viewer
    // has been using Dream OS for a while. The trailer path itself stays cold.
    DreamStore.shared.profileFile("pages").putString("look.trailers", "Off")
    open(WARMUP)
    renderUntil(10_000) { marks["details"] != null }
    renderUntil(1500) { false }
    if (System.getProperty("dream.home") == "true") {
        // Home's hero readies its slide's trailer a moment after Home shows: the extractor is warm before any page.
        kotlinx.coroutines.runBlocking { com.dreamos.desktop.core.trailer.TrailerEngine.ahead(TrailerRef(WARMUP.tmdbId, false, "Inception")) }
    }
    ui { scene.close() }
    marks.clear()
    DreamStore.shared.profileFile("pages").putString("look.trailers", "On, Muted")

    if (warm) {
        // The pointer comes to rest on the card; its hook fires after the card's 300 ms rest; the click comes later.
        val hoverEnds = System.currentTimeMillis() + HOVER_MS
        Thread.sleep(300)
        hover(TrailerRef(id, isShow, spec))
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(HOVER_MS - 300) {
                val d = dev.details(ref)
                com.dreamos.desktop.preload.TitlePageAhead.ready(source, d)
                built[ref] = d
            }
        }
        Thread.sleep(maxOf(0L, hoverEnds - System.currentTimeMillis()))
    }

    open(ref)
    renderUntil(25_000) { marks["reveal"] != null }
    fun m(k: String) = marks[k]?.let { "${(it - t0) / 1_000_000}" } ?: "-"
    println("RESULT $spec ${if (warm) "warm(${HOVER_MS} ms)" else "cold"}${if (System.getProperty("dream.home") == "true") "+home" else ""} landed=${m("landed")} details=${m("details")} resolved=${m("resolved")} firstFrame=${m("firstFrame")} prerolled=${m("prerolled")} reveal=${m("reveal")}")
    ui { scene.close() }
    Thread.sleep(1500)
    System.exit(0)
}

/** The page opened first to warm the app (Inception). */
private val WARMUP = TitleRef(27205, false, "Inception")

/** How long the pointer rests on the card before the click (warm runs; -Phover=<ms>). */
private val HOVER_MS = System.getProperty("dream.hover")?.toLongOrNull() ?: 1000L

/** The card's hover hook, trailer part (TitleAhead.rest calls it after the card's 300 ms rest). */
private fun hover(ref: TrailerRef) = com.dreamos.desktop.trailer.TrailerAhead.card(ref.tmdbId, ref.isShow, ref.name)
