package com.dreamos.desktop.startup

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.dreamos.desktop.core.service.Services
import com.dreamos.desktop.shell.AppRoot
import com.dreamos.desktop.shell.AppServices
import com.dreamos.desktop.shell.RowsModel
import com.dreamos.desktop.shell.ShellState
import com.dreamos.desktop.shell.SlotState
import com.dreamos.desktop.shell.WindowPrefs
import com.dreamos.desktop.ui.kit.DreamTheme
import com.dreamos.desktop.ui.kit.Images
import com.dreamos.desktop.ui.kit.Metrics
import com.dreamos.desktop.ui.kit.Tmdb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.nio.file.Paths

/**
 * The launch, measured without a window (`DREAM_PROBE=<report file>`): the same start as `main` (services, the intro,
 * Home under it), drawn offscreen at the saved window size at 60 frames a second until the intro is gone and Home's
 * hero and first rows are painted, then the timings are written and the process exits. No window, no sound, and none
 * of the after-intro starts (scrapers, Dream Core's warm-up): they aren't part of reaching Home.
 *
 * Runs only on a throwaway data folder (APPDATA and LOCALAPPDATA pointed elsewhere), because it marks setup as done so
 * the launch goes straight to Home, as the viewer's relaunch does.
 */
object StartupProbe {
    fun run(report: String, motion: Thread) {
        val home = System.getProperty("user.home")
        val roaming = System.getenv("APPDATA").orEmpty()
        require(roaming.isNotBlank() && Paths.get(roaming).normalize() != Paths.get(home, "AppData", "Roaming").normalize()) {
            "DREAM_PROBE needs a throwaway APPDATA and LOCALAPPDATA (never the viewer's folders)"
        }
        StartupTrace.span("services") { AppServices.start() }
        motion.join()
        if (!Services.profiles.isSetUp()) Services.profiles.markSetUp()
        val prefs = WindowPrefs.load()
        val scale = runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX.toFloat()
        }.getOrDefault(1f)
        val w = (prefs.w * scale).toInt()
        val h = (prefs.h * scale).toInt()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val shell = ShellState(AppServices.catalog, scope)
        // Home's page model, the one HomePage then uses (ShellState keeps one per page).
        val model: RowsModel = shell.rows("home", { shell.catalog.home() }, { shell.catalog.hero() })
        var introDone = false
        // DREAM_PROBE_SAMPLE=1: where the launch thread spends the scene's creation and first frame (stack samples).
        val main = Thread.currentThread()
        val samples = HashMap<String, Int>()
        val sampling = java.util.concurrent.atomic.AtomicBoolean(System.getenv("DREAM_PROBE_SAMPLE") != null)
        if (sampling.get()) Thread({
            while (sampling.get()) {
                val st = main.stackTrace.filter { !it.className.startsWith("java.") && !it.className.startsWith("jdk.") && !it.className.startsWith("sun.") }.take(6)
                    .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                synchronized(samples) { samples[st] = (samples[st] ?: 0) + 1 }
                Thread.sleep(5)
            }
        }, "probe-sampler").apply { isDaemon = true; start() }
        StartupTrace.mark("scene: start")
        val scene = ImageComposeScene(w, h, Density(scale)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                DreamTheme(Metrics(maxWidth.value, maxHeight.value)) {
                    AppRoot(shell, null, intro = true, onIntroDone = { StartupTrace.mark("intro: gone"); introDone = true })
                }
            }
        }
        StartupTrace.mark("scene: made")
        val heroW = Metrics(prefs.w.toFloat(), prefs.h.toFloat()).widthDp * scale
        val end = System.currentTimeMillis() + 40_000
        // An offscreen scene can deadlock in Compose's flush dispatcher (seen once in ~40 runs): the report still gets
        // written and the process ends.
        Thread({
            Thread.sleep(50_000)
            StartupTrace.mark("probe: stalled")
            runCatching { File(report).writeText(StartupTrace.lines().joinToString("\n") + "\n") }
            Runtime.getRuntime().halt(2)
        }, "probe-watchdog").apply { isDaemon = true; start() }
        var frames = 0
        var slowest = 0L
        while (System.currentTimeMillis() < end) {
            val t = System.nanoTime()
            Snapshot.sendApplyNotifications()
            scene.render(t)
            val ms = (System.nanoTime() - t) / 1_000_000
            if (frames == 0) { sampling.set(false); StartupTrace.mark("frame: first"); StartupTrace.took("frame: first render", ms) } else slowest = maxOf(slowest, ms)
            frames++
            val hero = model.hero
            if (hero != null) StartupTrace.mark("home: hero listed (${hero.size})")
            val heroArt = hero?.firstOrNull()?.let { Images.cached(Tmdb.backdrop(it.title.backdropPath, heroW)) != null } ?: (hero != null)
            if (heroArt) StartupTrace.mark("home: hero art in")
            val slots = model.slots
            if (slots.isNotEmpty()) StartupTrace.mark("home: row list (${slots.size})")
            val settled = slots.take(3).count { model.states[it.id] != null && model.states[it.id] != SlotState.Loading }
            if (slots.isNotEmpty() && settled >= minOf(3, slots.size)) StartupTrace.mark("home: first 3 rows in")
            val shown = model.states.values.count { it is SlotState.Shown }
            if (shown >= 8) StartupTrace.mark("home: 8 rows in")
            if (introDone && heroArt && settled >= minOf(3, slots.size) && slots.isNotEmpty()) {
                StartupTrace.mark("home: usable")
                break
            }
            val spent = (System.nanoTime() - t) / 1_000_000
            if (spent < 16) Thread.sleep(16 - spent)
        }
        StartupTrace.took("frames drawn", frames.toLong())
        StartupTrace.took("slowest later frame", slowest)
        scene.close()
        val top = synchronized(samples) { samples.entries.sortedByDescending { it.value }.take(25).map { "${it.value}  ${it.key}" } }
        val out = StartupTrace.lines() + (if (top.isEmpty()) emptyList() else listOf("", "-- samples (5 ms) --") + top)
        File(report).writeText(out.joinToString("\n") + "\n")
        println(out.joinToString("\n"))
        Runtime.getRuntime().halt(0)
    }
}
