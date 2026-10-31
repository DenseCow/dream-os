package com.dreamos.desktop.preload

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.dreamos.desktop.core.model.PlayRequest
import com.dreamos.desktop.core.platform.Platform
import com.dreamos.desktop.core.playback.AvailabilityAnswer
import com.dreamos.desktop.core.playback.AvailabilityApi
import com.dreamos.desktop.core.playback.AvailabilityStatus
import com.dreamos.desktop.core.playback.LanguagePills
import com.dreamos.desktop.core.playback.Pill
import com.dreamos.desktop.core.playback.PillGlyph
import com.dreamos.desktop.core.playback.PlayTarget
import com.dreamos.desktop.core.service.Services
import com.dreamos.desktop.shell.AppRoot
import com.dreamos.desktop.shell.AppServices
import com.dreamos.desktop.shell.Phase
import com.dreamos.desktop.shell.RowsModel
import com.dreamos.desktop.shell.ShellState
import com.dreamos.desktop.shell.SlotState
import com.dreamos.desktop.ui.kit.DreamTheme
import com.dreamos.desktop.ui.kit.Metrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * The pop-in harness (`DREAM_SNAP=<out> DREAM_POPIN=1 DREAM_POPIN_DATA=<throwaway folder> ./gradlew :app:run`, with
 * LOCALAPPDATA and APPDATA pointed inside that folder so the image cache and the scrapers' folder are the harness's
 * own). It never touches the viewer's `%APPDATA%\Dream OS`. Run it twice on the same folder: the first run is a
 * first launch (nothing on disk), the second a relaunch. Offscreen at 1920x1080: the intro over Home, Home after the intro, Home scrolled to the rows below,
 * then a show's and a movie's title page opened from Home's rows (a click, no hover first). Prints what loaded in late.
 *
 * Dream Core's pills need the viewer's scrapers, which a throwaway folder doesn't have, so [FakeAvailability] answers
 * instead with the real checker's pacing (two at a time, 400 ms apart, about a second each) and keeps its answers on
 * disk like the real one.
 */
@OptIn(ExperimentalComposeUiApi::class)
object PopInRun {
    private const val W = 1920
    private const val H = 1080

    fun run(outDir: String) {
        val data = System.getenv("DREAM_POPIN_DATA")?.let { File(it).absoluteFile } ?: fail("set DREAM_POPIN_DATA to a throwaway folder")
        // The image cache (LOCALAPPDATA) and the scrapers' folder (APPDATA) must be the harness's own, never the viewer's.
        for (v in listOf("LOCALAPPDATA", "APPDATA")) {
            if (!File(System.getenv(v).orEmpty()).absolutePath.startsWith(data.path)) fail("point $v inside DREAM_POPIN_DATA")
        }
        val firstLaunch = !File(data, "core").isDirectory
        Platform.current = Platform.forTests(File(data, "core").toPath())
        AppServices.availabilityOverride = FakeAvailability(File(data, "fake-availability.json"))
        val t0 = System.currentTimeMillis()
        AppServices.start()
        // Nothing reaches the viewer's screen: no Windows notifications from the harness.
        Platform.current.notifier = null
        com.dreamos.desktop.core.notices.Notices.appInFront = { true }
        // No background trailers behind the title pages (nothing plays, and the hero art is what's measured).
        com.dreamos.desktop.core.store.DreamStore.shared.profileFile("pages").putString("look.trailers", "Off")
        val label = if (firstLaunch) "first launch" else "relaunch"
        if (firstLaunch) runBlocking {
            Services.profiles.markSetUp()
            val trending = Services.search.trending()
            trending.drop(8).take(6).forEach { Services.myList.toggle(it) }
            for ((i, t) in trending.filter { !it.isShow }.take(4).withIndex()) {
                val dur = 6_000_000L
                Services.history.report(PlayRequest(t), dur * (2 + i) / 10, dur, 180_000L, exact = true)
            }
            // One finished movie so a Watched pill has something to show.
            trending.filter { !it.isShow }.drop(4).firstOrNull()?.let { Services.history.report(PlayRequest(it), 5_900_000L, 6_000_000L, 5_000_000L, exact = true) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val shell = ShellState(AppServices.catalog, scope)
        PopIn.reset(Long.MAX_VALUE)
        val scene = ImageComposeScene(W, H, Density(1f)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                DreamTheme(Metrics(maxWidth.value, maxHeight.value)) {
                    AppRoot(shell, null, intro = true, start = Phase.APP, onIntroDone = {
                        // The intro's fade (420 ms) has ended; Home started showing through as it began.
                        PopIn.visibleAt = System.currentTimeMillis() - 420
                        AppServices.afterIntro()
                    })
                }
            }
        }
        val lines = mutableListOf("Pop-in, $label, ${W}x$H (startup to Home's first frame ${System.currentTimeMillis() - t0} ms)")
        fun section(name: String) {
            val r = PopIn.report()
            val late = r.values.sumOf { it.late }
            val missing = r.values.sumOf { it.missing }
            lines += "  $name: $late late, $missing missing  " + r.entries.joinToString("  ") { (k, c) -> "$k ${c.complete}/${c.late}/${c.missing}" + if (c.late > 0) " (+${c.lateMs} ms)" else "" }
        }
        frames(scene, 12_000)
        section("home (12 s after launch)")
        val home = shell.model<RowsModel>("home") { error("no Home model") }
        PopIn.reset()
        runBlocking { home.list.scrollToItem(5) }
        frames(scene, 2_500)
        section("home, rows below (scrolled to the 4th row)")
        PopIn.reset()
        runBlocking { home.list.scrollToItem(11) }
        frames(scene, 2_500)
        section("home, further down (scrolled to the 10th row)")
        runBlocking { home.list.scrollToItem(0) }
        frames(scene, 1_000)
        val titles = home.states.values.filterIsInstance<SlotState.Shown>().flatMap { it.row.items }.map { it.title }
        // A recent movie first (the JVM's first title page pays for loading the page's code), then two shows.
        val movie = titles.firstOrNull { !it.isShow && it.date >= LocalDate.now().minusDays(80).toString() }
        val shows = titles.filter { it.isShow }.take(2)
        for ((what, t) in listOfNotNull(movie?.let { "movie, the run's first title page" to it }) + shows.map { "show" to it }) {
            PopIn.reset()
            shell.openTitle(t)
            frames(scene, 5_000)
            section("title page, $what (${t.name})")
            shell.back(); frames(scene, 800)
        }
        scene.close()
        val text = lines.joinToString("\n")
        println(text)
        File(outDir).mkdirs()
        File(outDir, "popin.txt").appendText(text + "\n\n")
        System.exit(0)
    }

    private fun fail(msg: String): Nothing { System.err.println("Pop-in harness: $msg"); System.exit(2); error(msg) }

    private fun frames(scene: ImageComposeScene, ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            Snapshot.sendApplyNotifications()
            scene.render(System.nanoTime())
            Thread.sleep(16)
        }
    }
}

/**
 * Stand-in pills for the harness: a recent or coming movie, or an episode of the last 21 days, gets "Available Now"
 * once out and "Coming <date>" before; checks run like Dream Core's (2 workers, 400 ms apart, 0.8 to 1.6 s each) and
 * answers are kept on disk for a relaunch. Never used by the app.
 */
class FakeAvailability(private val file: File) : AvailabilityApi {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val map = MapSerializer(String.serializer(), AvailabilityAnswer.serializer())
    private val cache = ConcurrentHashMap<String, AvailabilityAnswer>().apply {
        runCatching { putAll(Json.decodeFromString(map, file.readText())) }
    }
    private val queue = ArrayDeque<PlayTarget>()
    private val queued = HashSet<String>()
    private var workers = 0
    private var lastStart = 0L
    private val _updates = MutableSharedFlow<Pair<String, AvailabilityAnswer>>(extraBufferCapacity = 256)
    override val updates: SharedFlow<Pair<String, AvailabilityAnswer>> get() = _updates

    private fun today() = LocalDate.now().toString()
    private fun may(t: PlayTarget): Boolean {
        val d = t.airDate?.takeIf { it.length == 10 } ?: return false
        return d >= LocalDate.now().minusDays(if (t.isEpisode) 21 else 90).toString()
    }

    override fun cardPill(target: PlayTarget, narrow: Boolean): Pill? {
        if (!may(target)) return null
        return cache[target.key]?.let(::pill)
    }
    override fun pagePill(target: PlayTarget): Pill? = cache[target.key]?.let(::pill)
    private fun pill(e: AvailabilityAnswer) = when (e.status) {
        AvailabilityStatus.AVAILABLE -> Pill(listOf("Available Now", "Available"), e.status.tone, PillGlyph.CHECK)
        else -> Pill(listOf(e.note ?: "Not Available Yet", "Soon"), e.status.tone, PillGlyph.CLOCK)
    }
    override fun peek(key: String) = cache[key]
    override fun languagePills(target: PlayTarget) = LanguagePills(null, null)

    override fun ask(targets: List<PlayTarget>, priority: Boolean) {
        for (t in if (priority) targets.reversed() else targets) {
            if (!may(t) && !(priority && targets.size == 1)) continue
            if (cache[t.key]?.let { System.currentTimeMillis() - it.checkedAt < 3_600_000L } == true) continue
            synchronized(queue) {
                if (t.key in queued) { if (priority) { queue.removeAll { it.key == t.key }; queue.addFirst(t) } }
                else { if (priority) queue.addFirst(t) else queue.addLast(t); queued += t.key }
                if (workers < 2) { workers++; scope.launch { work() } }
            }
        }
    }

    override suspend fun check(target: PlayTarget): AvailabilityAnswer? { answer(target); return cache[target.key] }

    private suspend fun work() {
        while (true) {
            val t = synchronized(queue) { queue.removeFirstOrNull()?.also { queued -= it.key } ?: run { workers--; null } } ?: return
            while (true) {
                val go = synchronized(queue) { val n = System.currentTimeMillis(); (n - lastStart >= 400).also { if (it) lastStart = n } }
                if (go) break
                delay(50)
            }
            answer(t)
        }
    }

    private suspend fun answer(t: PlayTarget) {
        delay(800L + (t.key.hashCode().toLong() and 0x7fffffff) % 800)
        val d = t.airDate.orEmpty()
        val e = if (d.isNotEmpty() && d > today()) AvailabilityAnswer(AvailabilityStatus.NONE, note = "Coming " + d.substring(5), checkedAt = System.currentTimeMillis())
        else AvailabilityAnswer(AvailabilityStatus.AVAILABLE, best = "1080p WEB-DL", checkedAt = System.currentTimeMillis())
        cache[t.key] = e
        _updates.tryEmit(t.key to e)
        synchronized(file) { runCatching { file.writeText(Json.encodeToString(map, cache.toMap())) } }
    }
}
