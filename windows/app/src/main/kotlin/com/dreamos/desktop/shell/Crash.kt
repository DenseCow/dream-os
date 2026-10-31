package com.dreamos.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import com.dreamos.desktop.ui.kit.ButtonRow
import com.dreamos.desktop.ui.kit.DreamTheme
import com.dreamos.desktop.ui.kit.LocalMetrics
import com.dreamos.desktop.ui.kit.Metrics
import com.dreamos.desktop.ui.kit.Palette
import com.dreamos.desktop.ui.kit.PearlButton
import com.dreamos.desktop.ui.kit.Type
import java.awt.Window
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.system.exitProcess

/**
 * No raw error dialogs, ever. Compose's own handler shows the exception text in a Swing box and closes the window;
 * this one writes the whole stack trace to the log ([AppLog]) and keeps Dream OS running when the error came from
 * handling one click or key (the next frame is fine). An error in composition stops Compose from drawing anything
 * new, so then, and when errors keep coming, it shows a calm Dream OS card instead: "Something Went Wrong", a plain
 * sentence, Restart Dream OS and Close Dream OS. Never the exception's words.
 */
@OptIn(ExperimentalComposeUiApi::class)
object Crash {
    private val recent = ArrayDeque<Long>()
    @Volatile private var shown = false

    /**
     * Every thread: a background error (an Error too: out of memory, a stack overflow, a native library that didn't
     * load) is logged with its stack and the app carries on. If writing the stack fails as well, one short line still
     * reaches the file. Exceptions thrown inside JNA callbacks (native code calling Java: mpv's render updates, the
     * media keys' COM handler) are logged the same way.
     */
    fun installThreadHandler() {
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                AppLog.error("uncaught on thread ${t.name}", e)
            } catch (_: Throwable) {
                runCatching { AppLog.lastWords("uncaught ${e.javaClass.name} on thread ${t.name}; its stack couldn't be written") }
            }
            // The event thread: the same rules as a window's error (AWT starts a new event thread and goes on).
            runCatching { if (SwingUtilities.isEventDispatchThread()) decide(null, e) }
        }
        com.sun.jna.Native.setCallbackExceptionHandler { c, e ->
            runCatching { AppLog.error("in a native callback (${c.javaClass.name})", e) }
        }
    }

    /** The factory for `LocalWindowExceptionHandlerFactory` around the app's window. */
    val factory = WindowExceptionHandlerFactory { window ->
        WindowExceptionHandler { e ->
            AppLog.error("in the window", e)
            decide(window, e)
        }
    }

    /** Every error reaching here is already in the log with its stack ([AppLog.error]); this decides what the viewer sees. */
    private fun decide(window: Window?, e: Throwable) {
        val now = System.currentTimeMillis()
        val burst = synchronized(recent) {
            recent.addLast(now)
            while (recent.isNotEmpty() && now - recent.first() > 10_000) recent.removeFirst()
            recent.size
        }
        if (fatal(e) || burst >= 4) show(window)
    }

    /** Composition errors leave the Recomposer stopped (nothing on screen would change again); input errors don't. */
    private fun fatal(e: Throwable): Boolean {
        if (e is OutOfMemoryError || e is StackOverflowError) return true
        return generateSequence(e) { it.cause }.take(6).any { c ->
            c.stackTrace.any { f -> f.className.startsWith("androidx.compose.runtime.Recomposer") || f.className.startsWith("androidx.compose.runtime.ComposerImpl") }
        }
    }

    private fun show(owner: Window?) {
        if (shown) return
        shown = true
        SwingUtilities.invokeLater {
            runCatching {
                val d = ComposeDialog(owner).apply {
                    isUndecorated = true
                    isResizable = false
                    defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                    title = "Dream OS"
                    setSize(560, 300)
                    setLocationRelativeTo(owner)
                    isAlwaysOnTop = true
                }
                d.setContent { DreamTheme(Metrics(560f, 300f)) { Card(onRestart = ::restart, onClose = { exitProcess(1) }) } }
                d.isVisible = true
            }.onFailure { e ->
                // The card itself failed: the reason goes to the log before Dream OS closes.
                AppLog.error("the Something Went Wrong card couldn't show", e)
                exitProcess(1)
            }
        }
    }

    /** The packaged app relaunches its own launcher; a development run just closes. */
    private fun restart() {
        val exe = System.getProperty("jpackage.app-path")
        if (exe != null) runCatching { ProcessBuilder(exe).start() }.onFailure { AppLog.error("Restart Dream OS couldn't start $exe", it) }
        exitProcess(1)
    }

    @Composable
    private fun Card(onRestart: () -> Unit, onClose: () -> Unit) {
        val m = LocalMetrics.current
        Box(
            Modifier.fillMaxSize().background(Palette.page)
                .border(m.u(1), Palette.hairline, RoundedCornerShape(m.u(4)))
                .padding(m.u(36)),
        ) {
            Column(verticalArrangement = Arrangement.Top) {
                Text("Something Went Wrong", style = Type.pageTitle())
                Spacer(Modifier.height(m.u(12)))
                Text("Dream OS ran into a problem it couldn't get past. Your profiles, lists and settings are safe.", style = Type.body())
                Spacer(Modifier.height(m.u(28)))
                val first = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
                androidx.compose.runtime.LaunchedEffect(Unit) { com.dreamos.desktop.ui.kit.Focus.request(first, "crash card") }
                ButtonRow {
                    PearlButton("Restart Dream OS", onRestart, requester = first)
                    PearlButton("Close Dream OS", onClose)
                }
            }
        }
    }

    /** For the offscreen check: the card as it shows. */
    @Composable fun Preview() = Card({}, {})
}
