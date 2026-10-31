package com.dreamos.desktop.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dreamos.desktop.core.util.Log
import com.dreamos.player.host.DisplayHost
import com.dreamos.player.host.PlayerDisplay
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowEvent
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * The one source of truth for how the app window sits on the screen (Settings › Your PC › Display Mode, F11 and
 * Alt+Enter in the app and in Dream Player). Everything runs on the event thread.
 *
 * - **Full Screen Windowed** (the default): the window loses its title bar and border and covers the monitor it is on
 *   exactly (the monitor's full rectangle, taskbar ignored), in physical pixels from Windows itself, so scaling and
 *   monitors side by side never matter. Ordinary z-order: Alt+Tab and other windows work as usual, no display mode
 *   change. Windows treats a window that exactly covers a monitor as full screen, so the taskbar (auto-hide too)
 *   stays down while Dream OS is in front.
 * - **Full Screen**: the same window, plus "always in front" while Dream OS is the active app (dropped the moment
 *   another app takes over, and Dream OS tucks under it), plus the taskbar's own full-screen mark. Not Windows'
 *   exclusive mode: see docs/port/shell.md, "Display modes", for why exclusive would be worse here.
 * - **Windowed**: the normal window with its frame, at its saved size and place (maximized if it was).
 *
 * The window stays on the monitor it is on: moving it (Win+Shift+Arrow), a scaling change or a resolution change
 * re-fits it to its monitor. The mode, the last full-screen kind and that monitor are saved per PC ([WindowPrefs]).
 */
object Display {
    /** The current mode; Compose reads it (Settings, the player's controls). */
    var mode by mutableStateOf(DisplayMode.DEFAULT)
        private set
    var lastFull = DisplayMode.DEFAULT
        private set

    private var window: Frame? = null
    private var prefs = WindowPrefs()
    /** The frame styles taken off for full screen (null while the window has its frame). */
    private var frame: WinDisplay.Frame? = null
    /** Windowed bounds in AWT units (kept while full screen, saved for the next Windowed). */
    private var floating: Box? = null
    private var maximized = false
    private var busy = false
    private var active = true
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val saver = Timer(600) { save() }.apply { isRepeats = false }

    /** For the app's shell (F11, Alt+Enter). */
    val control = object : WindowControl {
        override val fullscreen: Boolean get() = mode.full
        override fun toggleFullscreen() = toggle()
    }

    private val host = object : DisplayHost {
        override val fullScreen: Boolean get() = mode.full
        override val onTop: Boolean get() = topmost()
        override fun toggleFullScreen() = toggle()
        override fun listen(l: () -> Unit): () -> Unit { listeners += l; return { listeners -= l } }
    }

    /**
     * Sets [w] up before it is shown (call from the window's `create`): its first mode from [p], the size limits,
     * and the listeners that keep the mode true when the window moves, resizes or loses the front.
     */
    fun attach(w: Frame, p: WindowPrefs) {
        window = w
        prefs = p
        lastFull = if (p.lastFull.full) p.lastFull else DisplayMode.DEFAULT
        maximized = p.maximized
        floating = if (p.x != null && p.y != null) Box(p.x, p.y, p.w, p.h) else null
        val reach = ScreenFit.reachable(floating, awtWorkAreas())
        if (reach != null) w.setBounds(reach.x, reach.y, reach.w, reach.h)
        else {
            w.setSize(p.w, p.h)
            w.setLocationRelativeTo(null)
        }
        PlayerDisplay.host = host
        w.addComponentListener(object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent?) = changed()
            override fun componentResized(e: ComponentEvent?) = changed()
        })
        w.addWindowStateListener { e ->
            if (mode == DisplayMode.WINDOWED && !busy) { maximized = e.newState and Frame.MAXIMIZED_BOTH != 0; saver.restart() }
        }
        // The app is in front while any of its windows is (the player's overlay counts): Full Screen's "always in
        // front" follows it.
        // Focus moving between two of its own windows passes through "no active window" for a moment: losing the
        // front counts only when it lasts 150 ms.
        val lost = Timer(150) {
            if (KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow == null && active) { active = false; applyTopmost() }
        }.apply { isRepeats = false }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("activeWindow") { e ->
            if (e.newValue == null) lost.restart()
            else { lost.stop(); if (!active) { active = true; applyTopmost() } }
        }
        w.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowIconified(e: WindowEvent?) = applyTopmost()
            override fun windowDeiconified(e: WindowEvent?) { fit(); applyTopmost() }
        })
        // Full screen before the first frame shows: the window is made displayable (its native window exists, still
        // hidden), loses its frame and covers its monitor, so it never flashes a title bar.
        if (p.mode.full) {
            w.addNotify()
            enterFull(p.mode, launch = true)
        } else {
            mode = DisplayMode.WINDOWED
            w.minimumSize = Dimension(WindowPrefs.MIN_W, WindowPrefs.MIN_H)
        }
        Log.i("Display", "start ${mode.label}" + (if (mode.full) " on ${screenNow()}" else " at ${w.bounds.let { "${it.x},${it.y} ${it.width}x${it.height}" }}"))
    }

    /** Called once the window is visible: Windowed reopens maximized when it was left maximized. */
    fun shown() {
        val w = window ?: return
        if (mode == DisplayMode.WINDOWED && maximized) w.extendedState = w.extendedState or Frame.MAXIMIZED_BOTH
        applyTopmost()
    }

    /** Settings' choice. */
    fun set(next: DisplayMode) = onUi {
        if (next == mode) return@onUi
        val w = window
        if (w == null) { mode = next; if (next.full) lastFull = next; return@onUi }
        if (next.full) enterFull(next) else leaveFull()
        saver.restart()
        notifyListeners()
    }

    /** F11 and Alt+Enter (and the player's full-screen controls): Windowed and the last full-screen kind. */
    fun toggle() = set(ScreenFit.toggled(mode, lastFull))

    // ---- modes ----

    private fun enterFull(next: DisplayMode, launch: Boolean = false) {
        val w = window ?: return
        val h = WinDisplay.hwnd(w)
        busy = true
        try {
            if (frame == null) {
                if (!launch && w.extendedState and Frame.MAXIMIZED_BOTH != 0) {
                    maximized = true
                    w.extendedState = Frame.NORMAL
                } else if (!launch) {
                    maximized = false
                    floating = w.bounds.let { Box(it.x, it.y, it.width, it.height) }
                }
                w.minimumSize = Dimension(1, 1)
                frame = h?.let { WinDisplay.removeFrame(it) }
            }
            mode = next
            lastFull = next
            val target = h?.let { hw ->
                if (launch) ScreenFit.fullTarget(prefs.screen, WinDisplay.rect(hw), WinDisplay.monitors().map { it.first })
                else WinDisplay.monitorOf(hw)
            }
            if (h != null && target != null) WinDisplay.place(h, target)
            else fallbackFull(w)
            h?.let { WinDisplay.markFullScreen(it, next == DisplayMode.FULL_SCREEN) }
            applyTopmost()
            Log.i("Display", "${next.label} on $target")
        } finally {
            SwingUtilities.invokeLater { busy = false; fit() }
        }
    }

    private fun leaveFull() {
        val w = window ?: return
        val h = WinDisplay.hwnd(w)
        busy = true
        try {
            mode = DisplayMode.WINDOWED
            if (w.isAlwaysOnTop) w.isAlwaysOnTop = false
            h?.let { WinDisplay.markFullScreen(it, false) }
            frame?.let { f -> h?.let { WinDisplay.restoreFrame(it, f) } }
            frame = null
            w.minimumSize = Dimension(WindowPrefs.MIN_W, WindowPrefs.MIN_H)
            val back = ScreenFit.reachable(floating, awtWorkAreas())
            if (back != null) w.setBounds(back.x, back.y, back.w, back.h)
            else { w.setSize(prefs.w, prefs.h); w.setLocationRelativeTo(null) }
            if (maximized) w.extendedState = w.extendedState or Frame.MAXIMIZED_BOTH
            Log.i("Display", "Windowed at ${w.bounds.let { "${it.x},${it.y} ${it.width}x${it.height}" }}${if (maximized) ", maximized" else ""}")
        } finally {
            SwingUtilities.invokeLater { busy = false }
        }
    }

    /** Off Windows (no native window calls): the window simply takes its screen's bounds. */
    private fun fallbackFull(w: Window) {
        val b = w.graphicsConfiguration?.bounds ?: return
        w.setBounds(b.x, b.y, b.width, b.height)
    }

    /** The window moved or resized: Windowed remembers it; full screen re-fits to the monitor it is now on. */
    private fun changed() {
        val w = window ?: return
        if (busy) return
        if (mode == DisplayMode.WINDOWED) {
            if (w.extendedState and (Frame.MAXIMIZED_BOTH or Frame.ICONIFIED) == 0) {
                floating = Box(w.x, w.y, w.width, w.height)
                saver.restart()
            }
        } else SwingUtilities.invokeLater { fit() }
        notifyListeners()
    }

    /** Full screen: back onto exactly the monitor the window is on (after a move, a scaling or resolution change). */
    private fun fit() {
        val w = window ?: return
        if (!mode.full || busy || w.extendedState and Frame.ICONIFIED != 0) return
        val h = WinDisplay.hwnd(w) ?: return
        val mon = WinDisplay.monitorOf(h) ?: return
        if (WinDisplay.rect(h) != mon) {
            busy = true
            try { WinDisplay.place(h, mon) } finally { SwingUtilities.invokeLater { busy = false } }
            Log.i("Display", "re-fit to $mon")
        }
        if (prefs.screen != mon) { prefs = prefs.copy(screen = mon); saver.restart() }
    }

    private fun topmost() = mode == DisplayMode.FULL_SCREEN && active && window?.let { it.extendedState and Frame.ICONIFIED == 0 } == true

    /** Full Screen: "always in front" exactly while Dream OS is the active app; otherwise never. */
    private fun applyTopmost() {
        val w = window ?: return
        val top = topmost()
        if (w.isAlwaysOnTop != top) {
            w.isAlwaysOnTop = top
            // Letting go puts the window at the top of the ordinary windows, over the app just switched to: tuck it
            // under that app instead.
            if (!top && mode == DisplayMode.FULL_SCREEN) WinDisplay.hwnd(w)?.let { WinDisplay.behindForeground(it) }
        }
        notifyListeners()
    }

    private fun notifyListeners() = listeners.forEach { runCatching { it() } }

    private fun save() {
        prefs = prefs.copy(
            mode = mode, lastFull = lastFull, maximized = maximized,
            w = floating?.w ?: prefs.w, h = floating?.h ?: prefs.h, x = floating?.x ?: prefs.x, y = floating?.y ?: prefs.y,
            screen = if (mode.full) window?.let { WinDisplay.hwnd(it) }?.let { WinDisplay.monitorOf(it) } ?: prefs.screen else prefs.screen,
        )
        prefs.save()
    }

    /** Saves now (the app is closing). */
    fun flush() { if (saver.isRunning) saver.stop(); save() }

    private fun screenNow(): Box? = window?.let { WinDisplay.hwnd(it) }?.let { WinDisplay.rect(it) }

    /** Every screen's work area in AWT units (the space saved window bounds are in). */
    private fun awtWorkAreas(): List<Box> = runCatching {
        val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
        if (env.isHeadlessInstance) emptyList() else env.screenDevices.map { d ->
            val gc = d.defaultConfiguration
            val b = gc.bounds
            val i = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
            Box(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
        }
    }.getOrDefault(emptyList())

    /** The current screen size in AWT units, for sizing art before the window shows (full screen: the monitor). */
    fun expectedSize(p: WindowPrefs): Pair<Int, Int> {
        if (!p.mode.full) return p.w to p.h
        return runCatching {
            val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val b = env.defaultScreenDevice.defaultConfiguration.bounds
            b.width to b.height
        }.getOrDefault(p.w to p.h)
    }

    private fun onUi(block: () -> Unit) = if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)

}
