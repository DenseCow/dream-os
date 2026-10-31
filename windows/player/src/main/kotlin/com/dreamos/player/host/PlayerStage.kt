package com.dreamos.player.host

import androidx.compose.runtime.Composable
import androidx.compose.ui.awt.ComposeDialog
import com.dreamos.player.engine.PlayerLog
import com.sun.jna.Native
import java.awt.Canvas
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JDialog
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * Where Dream Player draws, as two windows stacked exactly on top of each other:
 *
 *  - [video]: an undecorated window holding one heavyweight AWT [Canvas]. libmpv gets the canvas's HWND (`wid`) and
 *    renders straight into it with its own Direct3D 11 swap chain (gpu-next). Nothing is copied through Java, so 4K60
 *    and HDR / Dolby Vision output (an HDR swap chain on an HDR screen) are mpv's own path.
 *  - [overlay]: a transparent, undecorated Compose window owned by [video], so Windows always keeps it above the video.
 *    Dream Player's controls are ordinary Compose UI drawn there; where it draws nothing the video shows through.
 *
 * Both windows are owned by the app's window, so they never get their own taskbar button and they stay above Dream
 * OS while it is active. In [Mode.Window] the stage covers the app window's content area and follows it when it
 * moves or resizes; [Mode.FullScreen] covers the whole monitor; [Mode.Mini] is a small always-on-top window in a
 * screen corner. mpv keeps playing through every change (no re-open, no black frame): only the windows' bounds move.
 *
 * In the app ([PlayerDisplay.host] set) full screen belongs to the app: the app window covers its monitor in either
 * full-screen mode, so [Mode.Window] (its content area) is the whole monitor with no border or gap, and the stage
 * never uses [Mode.FullScreen]. Its windows are "always in front" exactly while the app's are (Full Screen while
 * Dream OS is in front), and always in the mini player.
 */
class PlayerStage(private val owner: Window?) {
    enum class Mode { Window, FullScreen, Mini }

    private val host: DisplayHost? = PlayerDisplay.host.takeIf { owner != null }
    private var stopListening: () -> Unit = {}

    val canvas = object : Canvas() {
        // AWT would clear a heavyweight canvas to its background on every resize; mpv owns these pixels.
        override fun update(g: java.awt.Graphics?) {}
        override fun paint(g: java.awt.Graphics?) {}
    }.apply { background = Color.BLACK }

    val video = JDialog(owner).apply {
        isUndecorated = true
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        background = Color.BLACK
        contentPane.background = Color.BLACK
        contentPane.add(canvas)
        focusableWindowState = false
        title = "Dream Player"
    }

    val overlay = ComposeDialog(video).apply {
        isUndecorated = true
        isTransparent = true
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        title = "Dream Player"
    }

    var mode: Mode = Mode.Window
        private set
    var onModeChanged: (Mode) -> Unit = {}
    /** The viewer closed the app window or the stage asked to close. */
    var onCloseRequest: () -> Unit = {}

    private var miniBounds: Rectangle? = null
    /** [hide] ran: the stage is on its way out and never moves or shows again. */
    private var hidden = false
    /** The two windows' own HWNDs (for the check after [dispose], when AWT no longer knows them). */
    private var nativeWindows: List<Long> = emptyList()
    private val follow = object : ComponentAdapter() {
        override fun componentMoved(e: ComponentEvent?) { if (mode == Mode.Window) place() }
        override fun componentResized(e: ComponentEvent?) { if (mode == Mode.Window) place() }
    }
    private val ownerState = object : WindowAdapter() {
        override fun windowClosing(e: WindowEvent?) = onCloseRequest()
    }

    fun setContent(content: @Composable () -> Unit) = overlay.setContent { content() }

    /** Shows both windows; returns the canvas HWND for mpv's `wid`. */
    fun show(): Long {
        place()
        video.isVisible = true
        overlay.isVisible = true
        overlay.toFront()
        overlay.requestFocus()
        owner?.addComponentListener(follow)
        owner?.addWindowListener(ownerState)
        // The app changed mode (F11, Settings, Full Screen gaining or losing "always in front"): follow it.
        stopListening = host?.listen { if (mode == Mode.Window) place(); applyTop() } ?: {}
        applyTop()
        val hwnd = Native.getComponentID(canvas)
        nativeWindows = listOf(video, overlay).mapNotNull { w -> runCatching { Native.getWindowID(w) }.getOrNull() }
        PlayerLog.i("Stage: shown ${video.width}x${video.height}, video window 0x${hwnd.toString(16)}")
        return hwnd
    }

    fun setMode(next: Mode) {
        if (next == mode || hidden) return
        if (mode == Mode.Mini) miniBounds = video.bounds
        mode = next
        applyTop()
        place()
        overlay.toFront()
        overlay.requestFocus()
        onModeChanged(next)
        PlayerLog.i("Stage: ${next.name} ${video.bounds.width}x${video.bounds.height}")
    }

    /**
     * "Always in front": the mini player always; otherwise as the app's windows are (with a host), or in the stage's
     * own full screen (without). Never turned off under a host that is in front: Windows would take "always in
     * front" off the owner too (an owned window made non-topmost drops its owners).
     */
    private fun applyTop() {
        val top = when {
            mode == Mode.Mini -> true
            host != null -> host.onTop
            else -> mode == Mode.FullScreen
        }
        if (video.isAlwaysOnTop != top) video.isAlwaysOnTop = top
        if (overlay.isAlwaysOnTop != top) overlay.isAlwaysOnTop = top
    }

    /** Puts both windows on [bounds] in one go: the video first, then the controls over it. */
    private fun place() {
        if (hidden) return
        val b = target()
        if (video.bounds != b) video.bounds = b
        if (overlay.bounds != b) overlay.bounds = b
        // Heavyweight children only lay out on the next validate; do it now so mpv sees the new size this frame.
        canvas.setBounds(0, 0, b.width, b.height)
        video.validate()
    }

    private fun screenOf(): Rectangle {
        val ref = owner ?: video
        val gc = ref.graphicsConfiguration ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        return gc.bounds
    }

    private fun target(): Rectangle = when (mode) {
        Mode.FullScreen -> screenOf()
        Mode.Mini -> miniBounds ?: miniDefault()
        Mode.Window -> owner?.let { contentBounds(it) } ?: Rectangle(120, 90, 1280, 720)
    }

    private fun contentBounds(w: Window): Rectangle {
        val root = (w as? javax.swing.RootPaneContainer)?.contentPane ?: return w.bounds
        if (!root.isShowing) return w.bounds
        val p = root.locationOnScreen
        return Rectangle(p.x, p.y, root.width, root.height)
    }

    private fun miniDefault(): Rectangle {
        val gc = (owner ?: video).graphicsConfiguration
        val screen = gc.bounds
        val ins = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
        val w = (screen.width * 0.24).toInt().coerceIn(384, 640)
        val h = w * 9 / 16
        val margin = 24
        return Rectangle(screen.x + screen.width - ins.right - w - margin, screen.y + screen.height - ins.bottom - h - margin, w, h)
    }

    /** Moves the mini player by a drag (screen pixels). */
    fun dragMini(dx: Int, dy: Int) {
        if (mode != Mode.Mini) return
        val b = video.bounds
        b.translate(dx, dy)
        miniBounds = b
        place()
    }

    /**
     * The first step of closing, on the UI thread: both windows leave the screen at once (whatever mode they were in:
     * the mini player's corner, full screen) and the app's window comes back to the front. mpv may still be drawing
     * into the hidden video window; [dispose] follows once it has let go.
     */
    fun hide() {
        if (hidden) return
        hidden = true
        PlayerLog.i("Stage: closing from ${mode.name} ${video.width}x${video.height}")
        stopListening()
        owner?.removeComponentListener(follow)
        owner?.removeWindowListener(ownerState)
        // "Always in front" is left as it is: hidden windows cover nothing, and turning it off under a host in front
        // would take it off the app's window too (see applyTop).
        overlay.isVisible = false
        video.isVisible = false
        owner?.toFront()
    }

    /**
     * Frees both windows (UI thread), after mpv has let go of the video window: a window destroyed under mpv's live
     * swap chain can hang. Then makes sure neither is still on screen; one that is gets hidden and logged.
     */
    fun dispose() {
        hide()
        overlay.dispose()
        video.dispose()
        // AWT destroys the native windows on its own thread: look once that has had time.
        javax.swing.Timer(500) { hideLeftovers() }.apply { isRepeats = false; start() }
    }

    /** The two windows' HWNDs still visible after dispose (never expected): hidden by hand, and the log says so. */
    private fun hideLeftovers() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return
        val u = com.sun.jna.platform.win32.User32.INSTANCE
        val me = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentProcessId()
        for (id in nativeWindows) {
            val h = com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Pointer(id))
            // Only while the handle is still this process's (a freed one can be reused by any window).
            val pid = com.sun.jna.ptr.IntByReference()
            if (!u.IsWindow(h) || u.GetWindowThreadProcessId(h, pid) == 0 || pid.value != me) continue
            if (u.IsWindowVisible(h)) {
                u.ShowWindow(h, com.sun.jna.platform.win32.WinUser.SW_HIDE)
                PlayerLog.w("Stage: window 0x${id.toString(16)} was still on screen after closing; hidden")
            }
        }
    }

    companion object {
        fun onUi(block: () -> Unit) = if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }
}
