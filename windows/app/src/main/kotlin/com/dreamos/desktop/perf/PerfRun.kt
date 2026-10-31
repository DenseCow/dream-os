package com.dreamos.desktop.perf

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.platform.Platform
import com.dreamos.desktop.settings.kit.TitleRef
import com.dreamos.desktop.shell.AppServices
import com.dreamos.desktop.shell.DreamApp
import com.dreamos.desktop.shell.RowsModel
import com.dreamos.desktop.shell.ShellState
import com.dreamos.desktop.shell.SlotState
import com.dreamos.desktop.shell.data.Row
import com.dreamos.desktop.shell.data.RowStyle
import com.dreamos.desktop.ui.kit.DreamTheme
import com.dreamos.desktop.ui.kit.Images
import com.dreamos.desktop.ui.kit.Metrics
import com.dreamos.desktop.ui.kit.Tmdb
import com.dreamos.desktop.ui.kit.cardArt
import com.dreamos.desktop.ui.kit.cardChoice
import com.dreamos.desktop.ui.kit.cardWidth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * The speed harness (DREAM_PERF=<folder for shots> ./gradlew :app:run), offscreen, on a throwaway data folder
 * (DREAM_PERF_ROOT, kept between runs so the second run is a relaunch; DREAM_PERF_W / _H for the size). From the moment
 * the app starts it times the first frame, Home's hero and first rows with their art in memory, then title pages
 * opened the way a viewer would (right as Home appears, while a first launch is still warming the other pages; and
 * after resting on a card): details, backdrop and logo (the page's top) and a show's episodes. Pop-ins after the
 * intro: art that arrived after its card or hero was drawn, and card pills that changed after their row showed.
 * Shots of Home, Top Picks and a landscape row, and of title pages. Nothing ships to viewers; no window, no sound.
 */
@OptIn(ExperimentalComposeUiApi::class)
object PerfRun {
    private val t0 = System.nanoTime()
    private fun now() = (System.nanoTime() - t0) / 1_000_000
    private const val INTRO_MS = 2_420L

    fun run(dir: String) {
        val out = File(dir).apply { mkdirs() }
        val root = System.getenv("DREAM_PERF_ROOT")?.let { Paths.get(it) } ?: Files.createTempDirectory("dream-perf")
        val first = !Files.exists(root.resolve("cache").resolve("tmdb"))
        val tag = if (first) "first" else "relaunch"
        Platform.current = Platform.forTests(root)
        val w = (System.getenv("DREAM_PERF_W") ?: "1920").toInt()
        val h = (System.getenv("DREAM_PERF_H") ?: "1080").toInt()
        println("perf: ${if (first) "FIRST LAUNCH" else "RELAUNCH"} at ${w}x$h, data in $root")
        val log = java.util.concurrent.ConcurrentLinkedQueue<String>()
        Net.onTmdb = { u -> log += "${now()} ${Thread.currentThread().name} ${com.dreamos.desktop.core.net.TmdbDisk.pathOf(u)}" }
        AppServices.start()
        CoverProbe.on = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val shell = ShellState(AppServices.catalog, scope)
        val m = Metrics(w.toFloat(), h.toFloat())
        val scene = ImageComposeScene(w, h, Density(1f)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                DreamTheme(Metrics(maxWidth.value, maxHeight.value)) { DreamApp(shell, null, intro = true) }
            }
        }
        val marks = LinkedHashMap<String, Long>()
        fun mark(name: String) { if (name !in marks) { marks[name] = now(); println("perf: $name at ${marks[name]} ms (TMDB sent ${Net.tmdbSent.get()})") } }
        fun shot(name: String) = File(out, "$name-${w}x$h-$tag.png").writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)

        // Pills each card had when its row first showed, to count the ones that changed after.
        val pillsAtShow = HashMap<String, Map<String, String?>>()
        var lateAtIntroEnd = -1
        var lastTrace = -1L
        var homeShot = false
        var introShot = false
        val clicks = ArrayList<kotlinx.coroutines.Deferred<String>>()
        val end = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < end) {
            Snapshot.sendApplyNotifications()
            scene.render(System.nanoTime())
            mark("first frame")
            val introEnd = marks.getValue("first frame") + INTRO_MS
            if (now() >= introEnd && lateAtIntroEnd < 0) { lateAtIntroEnd = Images.lateShown.get(); mark("intro over") }
            val home = runCatching { shell.model<RowsModel>("home") { error("none") } }.getOrNull()
            if (com.dreamos.desktop.core.catalog.ContentServices.warmed.value.isNotEmpty()) mark("Home filled ahead (core)")
            if (home != null) {
                val hero = home.hero
                if (hero != null && hero.isNotEmpty() && Images.cached(Tmdb.backdrop(hero[0].title.backdropPath, m.widthDp)) != null) mark("home hero with art")
                val shown = ready(home, m, 8)
                if (shown >= 1 && "home hero with art" in marks) mark("home first row with art")
                if (shown >= 3 && "home hero with art" in marks) mark("home 3 rows with art")
                if (shown >= 8 && "home hero with art" in marks) mark("home 8 rows with art")
                for (s in home.slots) {
                    val row = (home.states[s.id] as? SlotState.Shown)?.row ?: continue
                    if (s.id !in pillsAtShow) pillsAtShow[s.id] = row.items.take(8).associate { it.title.key to pillOf(shell, it.title) }
                }
            }
            if ("home first row with art" in marks && !homeShot) {
                homeShot = true
                // The viewer clicks right away: a movie and a show from Home's rows, no rest on the card first.
                val items = home!!.slots.mapNotNull { (home.states[it.id] as? SlotState.Shown)?.row }.flatMap { it.items }
                val picks = listOfNotNull(items.firstOrNull { !it.title.isShow }, items.firstOrNull { it.title.isShow }, items.drop(20).firstOrNull { it.title.isShow })
                for (p in picks.distinctBy { it.title.key }) clicks += scope.async { title(TitleRef(p.title.id, p.title.isShow, p.title.name), "click") }
                // A card rested on first (TitleAhead after the pointer's 300 ms rest), then clicked.
                items.drop(30).firstOrNull { it.title.isShow && it.title.key !in picks.map { p -> p.title.key } }?.let { p ->
                    clicks += scope.async {
                        com.dreamos.desktop.shell.data.TitleAhead.rest(p.title)
                        kotlinx.coroutines.delay(com.dreamos.desktop.shell.data.TitleAhead.REST_MS + 400)
                        title(TitleRef(p.title.id, p.title.isShow, p.title.name), "rest 300 ms + 400 ms, then click")
                    }
                }
            }
            if ("intro over" in marks && now() > marks.getValue("intro over") + 600 && !introShot) { introShot = true; shot("home") }
            if (System.getenv("DREAM_PERF_TRACE") != null && now() / 2000 != lastTrace) {
                lastTrace = now() / 2000
                val st = home?.let { hm -> hm.slots.take(10).joinToString(" ") { s -> when (val x = hm.states[s.id]) { is SlotState.Shown -> if (artIn(x.row, m)) "A" else "s"; SlotState.Hidden -> "-"; SlotState.Failed -> "F"; else -> "." } } }
                println("trace ${now()} ms: hero=${home?.hero?.size} rows=$st late=${Images.lateShown.get()} failed=${Images.failed.get()}")
                home?.let { hm -> hm.slots.take(10).forEach { sl -> (hm.states[sl.id] as? SlotState.Shown)?.row?.takeIf { !artIn(it, m) }?.let { r ->
                    println("  missing in ${r.name} (${r.style}): " + cardArt(r, m, 1f, 6).filter { u -> u != null && Images.cached(u) == null }.take(2))
                } } }
            }
            if (homeShot && clicks.all { it.isCompleted } && "home 8 rows with art" in marks && "intro over" in marks && introShot && now() > marks.getValue("intro over") + 3_000) break
            Thread.sleep(16)
        }
        // Pop-ins: art drawn late after the intro, and pills that changed on cards already showing.
        val lateArt = Images.lateShown.get() - maxOf(0, lateAtIntroEnd)
        val home = shell.model<RowsModel>("home") { error("none") }
        var pillChanges = 0
        for (s in home.slots) {
            val row = (home.states[s.id] as? SlotState.Shown)?.row ?: continue
            val at = pillsAtShow[s.id] ?: continue
            for (it in row.items.take(8)) if (at.containsKey(it.title.key) && at[it.title.key] != pillOf(shell, it.title)) {
                pillChanges++
                println("perf: pill changed on ${it.title.name} in ${row.name}: ${at[it.title.key]} -> ${pillOf(shell, it.title)}")
            }
        }

        // Shots: Top Picks (big cards) and the first landscape row brought up under the hero.
        fun scrollTo(style: RowStyle, name: String) {
            val i = home.slots.indexOfFirst { s -> (home.states[s.id] as? SlotState.Shown)?.row?.style == style }
            if (i < 0) return
            runBlocking { home.list.scrollToItem(i) }
            repeat(40) { Snapshot.sendApplyNotifications(); scene.render(System.nanoTime()); Thread.sleep(25) }
            shot(name)
        }
        // A title nobody rested on, opened: the page once in.
        home.slots.mapNotNull { (home.states[it.id] as? SlotState.Shown)?.row }.flatMap { it.items }.drop(45)
            .firstOrNull { AppServices.titleSource.instant(TitleRef(it.title.id, it.title.isShow, it.title.name)) == null }?.let { c ->
                shell.openTitle(c.title)
                repeat(24) { Snapshot.sendApplyNotifications(); scene.render(System.nanoTime()); Thread.sleep(25) }
                shot("title-open")
                shell.back()
                repeat(30) { Snapshot.sendApplyNotifications(); scene.render(System.nanoTime()); Thread.sleep(25) }
            }
        // The title page's first frame while its details are still on their way (a source that never answers): the
        // card's backdrop under the page's scrims, the name, the quiet buttons.
        home.slots.mapNotNull { (home.states[it.id] as? SlotState.Shown)?.row }.flatMap { it.items }.firstOrNull()?.title?.let { t ->
            val ref = TitleRef(t.id, t.isShow, t.name)
            val real = AppServices.titleSource
            val waiting = object : com.dreamos.desktop.details.TitleSource by real {
                override suspend fun details(ref: TitleRef): com.dreamos.desktop.details.TitleDetails = kotlinx.coroutines.awaitCancellation()
                override fun instant(ref: TitleRef): com.dreamos.desktop.details.TitleDetails? = null
            }
            val initial = com.dreamos.desktop.details.TitleDetails(ref, t.name, backdropUrl = t.backdrop("w1280"), year = t.year.toIntOrNull())
            runBlocking { initial.backdropUrl?.let { Images.load(it) } }
            val page = ImageComposeScene(w, h, Density(1f)) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    DreamTheme(Metrics(maxWidth.value, maxHeight.value)) {
                        com.dreamos.desktop.details.TitlePage(ref, waiting, onBack = {}, initial = initial)
                    }
                }
            }
            repeat(3) { Snapshot.sendApplyNotifications(); page.render(System.nanoTime()) }
            File(out, "title-loading-${w}x$h-$tag.png").writeBytes(page.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            page.close()
        }
        scrollTo(RowStyle.BIG, "top-picks")
        scrollTo(RowStyle.LANDSCAPE, "landscape-row")
        scene.close()

        println("==== perf ${if (first) "FIRST LAUNCH" else "RELAUNCH"} ${w}x$h ====")
        for ((k, v) in marks) println("  $k: $v ms")
        runBlocking { for (c in clicks) println("  " + c.await()) }
        println("  pop-ins after the intro: $lateArt images drawn late, $pillChanges card pills changed after their row showed")
        println("  TMDB requests sent: ${Net.tmdbSent.get()} by ${now()} ms")
        // Covers: every 16:9 title card drawn (CoverProbe); "missed" ones drew the backdrop though the title has key art
        // (known by now): a cover that came after the card was first drawn.
        val drawn = CoverProbe.drawn.values.toList()
        val missed = drawn.filter { (t, cover) -> !cover && com.dreamos.desktop.core.content.Details.knownKeyArt(t) != null }
        println("  covers: ${drawn.size} title cards drawn in 16:9 rows, ${drawn.count { it.second }} with their cover, ${missed.size} drew the backdrop though key art came later")
        missed.take(8).forEach { println("    missed cover: ${it.first.name}") }
        File(out, "tmdb-log-$tag.txt").writeText(log.joinToString(System.lineSeparator()))
        System.exit(0)
    }

    private fun pillOf(shell: ShellState, t: com.dreamos.desktop.shell.data.Title): String? =
        shell.catalog.pill(t, false)?.let { it.labels.firstOrNull() + "/" + it.tone }

    /** How many of the page's first [n] rows (skipping rows left out) are on screen with every visible card's art in memory. */
    private fun ready(model: RowsModel, m: Metrics, n: Int): Int {
        var done = 0
        for (s in model.slots) {
            when (val st = model.states[s.id]) {
                is SlotState.Shown -> if (artIn(st.row, m)) done++ else return done
                SlotState.Hidden, SlotState.Failed -> continue
                else -> return done
            }
            if (done >= n) return done
        }
        return done
    }

    // A card draws its row's art, or a cover known by the time it was first drawn (Covers): either counts.
    private fun artIn(row: Row, m: Metrics): Boolean {
        val px = cardWidth(row.style, m)
        return row.items.take(6).all { c ->
            val shown = listOf(cardChoice(c, row.style, px, live = false), cardChoice(c, row.style, px)).mapNotNull { ch -> ch.urls.firstOrNull { it != null } }
            shown.isEmpty() || shown.any { Images.cached(it) != null }
        }
    }

    /** A title opened: its page in the first frame ([instant]) or after details; then backdrop and logo; a show's season. */
    private suspend fun title(ref: TitleRef, how: String): String {
        val src = AppServices.titleSource
        val a = now()
        val sent = Net.tmdbSent.get()
        val instant = src.instant(ref)
        val d = instant ?: runCatching { src.details(ref) }.getOrNull() ?: return "title ${ref.name}: details FAILED"
        val det = now() - a
        val artInMemory = listOfNotNull(d.backdropUrl, d.logoUrl).all { Images.cached(it) != null }
        kotlinx.coroutines.coroutineScope {
            listOf(async { d.backdropUrl?.let { Images.load(it) } }, async { d.logoUrl?.let { Images.load(it) } }).forEach { it.await() }
        }
        val top = now() - a
        val eps = if (ref.isShow) d.defaultSeason?.let { s -> val b = now(); src.season(ref, s); now() - b } else null
        return "title ${ref.name} (${if (ref.isShow) "show" else "movie"}, $how) at $a ms: " +
            (if (instant != null) "complete page in the first frame" else "details $det ms") +
            ", top with art ${if (artInMemory) "in the first frame" else "$top ms"}" +
            (eps?.let { ", episodes +$it ms" } ?: "") + " (TMDB sent ${Net.tmdbSent.get() - sent} meanwhile)"
    }
}
