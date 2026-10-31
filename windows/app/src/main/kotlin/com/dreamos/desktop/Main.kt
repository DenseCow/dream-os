package com.dreamos.desktop

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.dreamos.desktop.shell.AppLog
import com.dreamos.desktop.shell.AppRoot
import com.dreamos.desktop.shell.AppServices
import com.dreamos.desktop.shell.Crash
import com.dreamos.desktop.shell.Display
import com.dreamos.desktop.shell.Motion
import com.dreamos.desktop.shell.ShellState
import com.dreamos.desktop.shell.Snap
import com.dreamos.desktop.shell.WindowPlayer
import com.dreamos.desktop.shell.WindowPrefs
import com.dreamos.desktop.startup.StartArchive
import com.dreamos.desktop.startup.StartupProbe
import com.dreamos.desktop.startup.StartupTrace
import com.dreamos.desktop.startup.UiWarm
import com.dreamos.desktop.ui.kit.DreamTheme
import com.dreamos.desktop.ui.kit.Metrics
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.WindowConstants
import kotlin.system.exitProcess

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    StartupTrace.mark("main")
    // An installed copy checks itself ("Dream OS.exe" --selfcheck report.txt): libmpv, Python, Java modules.
    if (args.firstOrNull() == "--selfcheck") exitProcess(com.dreamos.desktop.selfcheck.SelfCheck.run(args.drop(1)))
    // The startup archive (startup/StartArchive.kt): "--prepare" writes it (the setup runs it once).
    StartArchive.command(args)?.let { exitProcess(it) }
    // Beside the services' start: Windows' animation setting (a registry query) and the window's native set-up.
    val motion = Thread({ StartupTrace.span("motion read") { Motion.read() } }, "dream-motion").apply { start() }
    // Screenshots for checking the layout at every window size (DREAM_SNAP=<folder>): no window, then exit.
    System.getenv("DREAM_SNAP")?.takeIf { it.isNotBlank() }?.let { motion.join(); Snap.run(it); return }
    // Speed harness (DREAM_PERF=<folder>, DREAM_PERF_ROOT=<throwaway data folder>): offscreen timings, then exit.
    System.getenv("DREAM_PERF")?.takeIf { it.isNotBlank() }?.let { motion.join(); com.dreamos.desktop.perf.PerfRun.run(it); return }
    // Skia's library on this thread first (skiko's loader races otherwise), then the window's AWT and fonts beside
    // the services' start.
    UiWarm.loadSkia()
    UiWarm.start()
    // Launch timings without a window (DREAM_PROBE=<report file>, on a throwaway data folder), then exit.
    System.getenv("DREAM_PROBE")?.takeIf { it.isNotBlank() }?.let { StartupProbe.run(it, motion); return }
    // The DreamOS.Running mutex for the life of the app: the setup, the in-app updater and the uninstaller see Dream OS
    // is open (screenshot runs above don't take it).
    com.dreamos.desktop.selfcheck.RunningMark.hold()

    // The log file (stdout and stderr also to %APPDATA%\Dream OS\logs\dream.log) and no raw error dialogs.
    AppLog.install()
    Crash.installThreadHandler()
    StartupTrace.span("services") { AppServices.start() }
    motion.join()
    val prefs = WindowPrefs.load()
    application {
      // The window's close button and the avatar menu's Exit Dream OS: Dream Player closes first (saving the spot; the
      // app's window waits for its windows to go, at most 5 s), then the window's place is saved, the scrapers stop and
      // the app ends.
      var quitting = false
      fun quit() {
          if (quitting) return
          quitting = true
          val finish = { Display.flush(); AppServices.shutdown(); exitApplication() }
          if (!com.dreamos.player.DreamPlayer.isOpen) return finish()
          var done = false
          val once = { if (!done) { done = true; finish() } }
          runCatching { com.dreamos.player.DreamPlayer.close(then = once) }.onFailure { once() }
          javax.swing.Timer(5_000) { once() }.apply { isRepeats = false; start() }
      }
      CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides Crash.factory) {
        // The window is made here rather than by Compose's WindowState: Display owns its mode (Full Screen Windowed,
        // Full Screen, Windowed), and a full-screen start loses its frame before the first frame shows.
        Window(
            create = {
                ComposeWindow().apply {
                    title = "Dream OS"
                    iconImage = runCatching { javax.imageio.ImageIO.read(Display::class.java.getResource("/dream_icon.png")) }.getOrNull()
                    defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                    addWindowListener(object : WindowAdapter() {
                        override fun windowClosing(e: WindowEvent?) = quit()
                        override fun windowOpened(e: WindowEvent?) = Display.shown()
                        // The Play buttons' fade holds still while nobody can see it.
                        override fun windowIconified(e: WindowEvent?) { com.dreamos.desktop.ui.kit.ShellRainbow.minimized = true }
                        override fun windowDeiconified(e: WindowEvent?) { com.dreamos.desktop.ui.kit.ShellRainbow.minimized = false }
                    })
                    // Notices: banners while the window is in front, Windows notifications while it isn't.
                    addWindowFocusListener(object : java.awt.event.WindowFocusListener {
                        override fun windowGainedFocus(e: java.awt.event.WindowEvent?) = AppServices.windowFocus(true)
                        override fun windowLostFocus(e: java.awt.event.WindowEvent?) = AppServices.windowFocus(false)
                    })
                    Display.attach(this, prefs)
                }
            },
            dispose = { it.dispose() },
        ) {
            androidx.compose.runtime.LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; StartupTrace.mark("frame: first") }
            val scope = rememberCoroutineScope()
            val shell = remember {
                ShellState(AppServices.catalog, scope).also { s ->
                    s.launcher = WindowPlayer({ window }) { t -> s.openTitle(t) }
                    s.exit = { quit() }
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                DreamTheme(Metrics(maxWidth.value, maxHeight.value)) {
                    AppRoot(shell, Display.control, intro = true, onIntroDone = AppServices::afterIntro)
                }
            }
        }
      }
    }
}
