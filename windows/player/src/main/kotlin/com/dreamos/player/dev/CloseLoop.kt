package com.dreamos.player.dev

import androidx.compose.ui.awt.ComposeWindow
import com.dreamos.desktop.core.playback.MediaKind
import com.dreamos.desktop.core.playback.PlayTarget
import com.dreamos.player.DreamPlayer
import com.dreamos.player.api.PlayerEvents
import com.dreamos.player.engine.PlayerLog
import com.dreamos.player.host.DisplayHost
import com.dreamos.player.host.PlayerDisplay
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import java.awt.Dimension
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Dev check (not shipped): Dream Player opened over a stand-in app window and closed, again and again, in the ways a
 * viewer closes it: right after the scrub previews open, while the stream loads, mid-seek, from the mini player, right
 * after leaving the mini player, from full screen, with Dream Subtitles' tracks added. After every close it checks:
 * the close returned within [CLOSE_LIMIT_MS] (else a thread dump), no window of the player is still on screen, and
 * mpv's and the previews' threads are gone a few seconds later. Exits 0 when every round passed, 1 otherwise.
 *
 * Real windows: run it where the owner can't see them (tools/closeloop.ps1 starts it on a desktop of its own), with
 * -Ddream.player.silent=true and a throwaway APPDATA / -Ddream.log.dir. `-Ddream.rounds=N` (default 50), `-Ddream.url`.
 */
fun main() {
    devStore()
    val url = System.getProperty("dream.url") ?: SINTEL
    val rounds = (System.getProperty("dream.rounds") ?: "50").toInt()
    val only = System.getProperty("dream.only")?.split(",")?.map { it.trim() }?.toSet()
    val host = FakeHost()
    lateinit var app: ComposeWindow
    ui {
        app = ComposeWindow().apply {
            title = "Dream OS (close loop)"
            contentPane.preferredSize = Dimension(1280, 720)
            pack()
            setLocation(40, 40)
            isVisible = true
        }
        host.window = app
        PlayerDisplay.host = host
    }
    val ways = Way.entries.filter { only == null || it.name in only }
    val failures = ArrayList<String>()
    for (n in 1..rounds) {
        val way = ways[(n - 1) % ways.size]
        val problem = runCatching { round(n, way, app, url, host) }.getOrElse { "threw ${PlayerLog.describe(it)}" }
        if (problem == null) PlayerLog.i("CloseLoop: round $n ${way.name} ok")
        else { PlayerLog.e("CloseLoop: round $n ${way.name} FAILED: $problem"); failures += "round $n ${way.name}: $problem" }
    }
    PlayerLog.i("CloseLoop: ${rounds - failures.size} of $rounds rounds passed" + failures.joinToString("") { "\n  $it" })
    exitProcess(if (failures.isEmpty()) 0 else 1)
}

private const val CLOSE_LIMIT_MS = 3_000L

/** How a round closes the player. */
private enum class Way {
    AFTER_THUMBS, DURING_LOAD, DURING_SEEK, FROM_MINI, RIGHT_AFTER_MINI, FROM_FULL_SCREEN, AFTER_THUMBS_SUBS,
    /** The owner's 18:46 close: the app out of full screen and back while playing, mini player, back, close. */
    OWNER_SEQUENCE,
}

private fun round(n: Int, way: Way, app: ComposeWindow, url: String, host: FakeHost): String? {
    val target = PlayTarget(MediaKind.MOVIE, 45745, "Sintel", 2010, imdb = "tt1727587")
    val closed = java.util.concurrent.CountDownLatch(1)
    val events = object : PlayerEvents { override fun closed(target: PlayTarget, positionMs: Long) { closed.countDown() } }
    ui { DreamPlayer.play(app, target, DevPlayback(url), DemoServices(), events) }
    fun waitFor(ms: Long, what: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (what()) return true; Thread.sleep(50) }
        return false
    }
    val firstFrame = { DreamPlayer.session?.engineState?.firstFrame == true }
    when (way) {
        Way.DURING_LOAD -> Thread.sleep(150L + (n * 97L) % 900)
        Way.DURING_SEEK -> {
            if (!waitFor(40_000, firstFrame)) return "no first frame"
            Thread.sleep(500)
            ui { DreamPlayer.session?.seekTo(((n * 61_000L) % 700_000L) + 30_000L) }
            Thread.sleep((n * 37L) % 120)
        }
        Way.AFTER_THUMBS, Way.AFTER_THUMBS_SUBS, Way.FROM_MINI, Way.RIGHT_AFTER_MINI, Way.FROM_FULL_SCREEN, Way.OWNER_SEQUENCE -> {
            if (!waitFor(40_000, firstFrame)) return "no first frame"
            if (!waitFor(40_000) { DreamPlayer.thumbsOpened }) return "previews never opened"
            // Close 1 to 3 s after the previews opened (the owner's close came 1.4 s after).
            Thread.sleep(1_000L + (n * 389L) % 2_000)
            when (way) {
                Way.AFTER_THUMBS_SUBS -> ui { DreamPlayer.session?.let { s -> s.engine.addSubtitle(subFile.path, "Test", "en", select = true) } }
                Way.FROM_MINI -> { ui { DreamPlayer.control?.enterMini() }; Thread.sleep(800) }
                Way.RIGHT_AFTER_MINI -> { ui { DreamPlayer.control?.enterMini() }; Thread.sleep(700); ui { DreamPlayer.control?.expand() } }
                Way.FROM_FULL_SCREEN -> { ui { host.toggleFullScreen() }; Thread.sleep(700) }
                Way.OWNER_SEQUENCE -> {
                    ui { host.toggleFullScreen() }; Thread.sleep(1_000)
                    ui { host.toggleFullScreen() }; Thread.sleep(1_200)
                    ui { DreamPlayer.control?.enterMini() }; Thread.sleep(1_700)
                    ui { DreamPlayer.control?.expand() }; Thread.sleep(2_000)
                }
                else -> {}
            }
        }
    }
    // The viewer's Close: on the UI thread, timed. A hang past the limit gets a thread dump.
    val t0 = System.nanoTime()
    val done = java.util.concurrent.CompletableFuture<Unit>()
    SwingUtilities.invokeLater { runCatching { DreamPlayer.close() }; done.complete(Unit) }
    val returned = runCatching { done.get(CLOSE_LIMIT_MS, TimeUnit.MILLISECONDS) }.isSuccess
    val closeMs = (System.nanoTime() - t0) / 1_000_000
    if (!returned) {
        PlayerLog.e("CloseLoop: the UI thread is still in close after $CLOSE_LIMIT_MS ms\n${PlayerLog.threadDump()}")
        return "close blocked the UI thread for more than $CLOSE_LIMIT_MS ms"
    }
    PlayerLog.i("CloseLoop: close returned on the UI thread in $closeMs ms")
    ui { if (host.fullScreen) host.toggleFullScreen() }
    // Teardown finishes off the UI thread: give it the time a viewer would never notice, then look.
    val settled = waitFor(8_000) { playerWindowsOnScreen().isEmpty() && engineThreads().isEmpty() }
    if (!settled) {
        val left = playerWindowsOnScreen()
        val threads = engineThreads()
        if (left.isNotEmpty() || threads.isNotEmpty()) {
            PlayerLog.e("CloseLoop: after close: windows ${left.ifEmpty { listOf("none") }}, threads ${threads.ifEmpty { listOf("none") }}\n${PlayerLog.threadDump()}")
            return "left behind: " + (left + threads).joinToString("; ")
        }
    }
    val front = frontWindow()
    if (front?.startsWith("Dream OS (close loop)") != true) return "the app's window is not in front after closing (front: $front)"
    if (DreamPlayer.isOpen) return "the player still says it is open"
    if (closed.count != 0L) return "PlayerEvents.closed never came"
    return null
}

private val subFile: java.io.File by lazy {
    java.io.File.createTempFile("dream-closeloop", ".srt").apply {
        deleteOnExit()
        writeText("1\n00:00:00,000 --> 00:59:00,000\nClose loop\n")
    }
}

private fun ui(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

/** Visible top-level windows of the player in this process (not the stand-in app window): "class 'title' WxH at x,y". */
private fun playerWindowsOnScreen(): List<String> {
    val me = Kernel32.INSTANCE.GetCurrentProcessId()
    val out = CopyOnWriteArrayList<String>()
    val u = User32.INSTANCE
    u.EnumWindows({ h, _ ->
        val pid = IntByReference()
        u.GetWindowThreadProcessId(h, pid)
        if (pid.value == me && u.IsWindowVisible(h)) {
            val cls = CharArray(128).let { b -> String(b, 0, u.GetClassName(h, b, b.size)) }
            val title = CharArray(256).let { b -> String(b, 0, u.GetWindowText(h, b, b.size)) }
            // The player's windows (AWT's and mpv's own); Windows' own helpers on the desktop don't count.
            if ((cls.startsWith("SunAwt") || cls == "mpv") && !title.startsWith("Dream OS (close loop)")) {
                val r = WinDef.RECT().also { u.GetWindowRect(h, it) }
                out += "$cls '$title' ${r.right - r.left}x${r.bottom - r.top} at ${r.left},${r.top}"
            }
        }
        true
    }, null)
    return out
}

/** The title of this process's top visible window in z-order (EnumWindows goes from the top down). */
private fun frontWindow(): String? {
    val me = Kernel32.INSTANCE.GetCurrentProcessId()
    val u = User32.INSTANCE
    var found: String? = null
    u.EnumWindows({ h, _ ->
        val pid = IntByReference()
        u.GetWindowThreadProcessId(h, pid)
        val cls = CharArray(128).let { b -> String(b, 0, u.GetClassName(h, b, b.size)) }
        if (pid.value == me && u.IsWindowVisible(h) && cls.startsWith("SunAwt")) {
            found = CharArray(256).let { b -> String(b, 0, u.GetWindowText(h, b, b.size)) }
            false
        } else true
    }, null)
    return found
}

/** Threads of an mpv instance or the previews still alive. */
private fun engineThreads(): List<String> = Thread.getAllStackTraces().keys.filter { t ->
    t.isAlive && (t.name.startsWith("mpv-") || t.name == "dream-thumbs" || t.name.startsWith("dream-player-close"))
}.map { it.name }


/** The app's Display Mode, simplified: the stand-in window covers its monitor or goes back to 1280x720. */
private class FakeHost : DisplayHost {
    var window: java.awt.Frame? = null
    private var full = false
    private var before: java.awt.Rectangle? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    override val fullScreen get() = full
    override val onTop get() = false
    override fun toggleFullScreen() {
        val w = window ?: return
        full = !full
        if (full) { before = w.bounds; w.bounds = w.graphicsConfiguration.bounds } else before?.let { w.bounds = it }
        listeners.forEach { it() }
    }
    override fun listen(l: () -> Unit): () -> Unit { listeners += l; return { listeners -= l } }
}
