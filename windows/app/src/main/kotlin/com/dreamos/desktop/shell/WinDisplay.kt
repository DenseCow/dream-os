package com.dreamos.desktop.shell

import com.dreamos.desktop.core.util.Log
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.ptr.PointerByReference
import java.awt.Window

/**
 * The Win32 calls behind the display modes (JNA, no extra library): a window's frame styles, its monitor, its exact
 * position in physical pixels, and the taskbar's "this window is full screen" mark. Everything is in physical pixels,
 * so monitors with different scaling never meet AWT's mixed coordinates. Off Windows every call is a no-op that
 * returns null / false (Mac and Linux get their own later).
 */
internal object WinDisplay {
    private val windows = System.getProperty("os.name").startsWith("Windows")

    private const val GWL_STYLE = -16
    private const val GWL_EXSTYLE = -20
    private const val WS_CAPTION = 0x00C00000L
    private const val WS_THICKFRAME = 0x00040000L
    private const val WS_MAXIMIZEBOX = 0x00010000L
    private const val WS_EX_DLGMODALFRAME = 0x00000001L
    private const val WS_EX_WINDOWEDGE = 0x00000100L
    private const val WS_EX_CLIENTEDGE = 0x00000200L
    private const val WS_EX_STATICEDGE = 0x00020000L
    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020
    private const val SWP_NOOWNERZORDER = 0x0200
    private const val MONITOR_DEFAULTTONEAREST = 2

    @Suppress("FunctionName")
    private interface User32 : StdCallLibrary {
        fun GetWindowLongPtrW(hWnd: Pointer, nIndex: Int): Long
        fun SetWindowLongPtrW(hWnd: Pointer, nIndex: Int, value: Long): Long
        fun SetWindowPos(hWnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun GetWindowRect(hWnd: Pointer, rect: Rect): Boolean
        fun MonitorFromWindow(hWnd: Pointer, flags: Int): Pointer?
        fun GetMonitorInfoW(monitor: Pointer, info: MonitorInfo): Boolean
        fun EnumDisplayMonitors(hdc: Pointer?, clip: Pointer?, proc: MonitorProc, data: Pointer?): Boolean
        fun GetForegroundWindow(): Pointer?
    }

    interface MonitorProc : StdCallLibrary.StdCallCallback {
        fun callback(monitor: Pointer, hdc: Pointer?, rect: Pointer?, data: Pointer?): Boolean
    }

    @Structure.FieldOrder("left", "top", "right", "bottom")
    class Rect : Structure() {
        @JvmField var left = 0
        @JvmField var top = 0
        @JvmField var right = 0
        @JvmField var bottom = 0
        fun box() = Box(left, top, right - left, bottom - top)
    }

    @Structure.FieldOrder("cbSize", "rcMonitor", "rcWork", "dwFlags")
    class MonitorInfo : Structure() {
        @JvmField var cbSize = 40
        @JvmField var rcMonitor = Rect()
        @JvmField var rcWork = Rect()
        @JvmField var dwFlags = 0
    }

    private val user32: User32? by lazy {
        if (!windows) null else runCatching { Native.load("user32", User32::class.java) }
            .onFailure { Log.w("Display", "user32 unavailable: ${it.message}") }.getOrNull()
    }

    fun hwnd(w: Window): Pointer? = if (!windows || !w.isDisplayable) null else runCatching { Pointer(Native.getWindowID(w)) }.getOrNull()

    /** Every monitor's full rectangle and work area (taskbar excluded), the main monitor first. */
    fun monitors(): List<Pair<Box, Box>> {
        val u = user32 ?: return emptyList()
        val out = ArrayList<Triple<Box, Box, Boolean>>()
        val proc = object : MonitorProc {
            override fun callback(monitor: Pointer, hdc: Pointer?, rect: Pointer?, data: Pointer?): Boolean {
                val mi = MonitorInfo()
                if (u.GetMonitorInfoW(monitor, mi)) out += Triple(mi.rcMonitor.box(), mi.rcWork.box(), mi.dwFlags and 1 == 1)
                return true
            }
        }
        runCatching { u.EnumDisplayMonitors(null, null, proc, null) }
        return out.sortedByDescending { it.third }.map { it.first to it.second }
    }

    /** The full rectangle of the monitor [hwnd] is on (the nearest one when it is on none). */
    fun monitorOf(hwnd: Pointer): Box? {
        val u = user32 ?: return null
        val mon = u.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST) ?: return null
        val mi = MonitorInfo()
        return if (u.GetMonitorInfoW(mon, mi)) mi.rcMonitor.box() else null
    }

    fun rect(hwnd: Pointer): Box? {
        val u = user32 ?: return null
        val r = Rect()
        return if (u.GetWindowRect(hwnd, r)) r.box() else null
    }

    /** The frame styles a window had before it went borderless, to put back. */
    data class Frame(val style: Long, val exStyle: Long)

    /**
     * Takes the title bar and the resize border off (keeping the system menu and the minimize box, so Win+Down and a
     * taskbar click still minimize), and returns what it had. AWT re-reads the frame's insets from the next
     * WM_NCCALCSIZE, so Compose's content fills the whole window.
     */
    fun removeFrame(hwnd: Pointer): Frame? {
        val u = user32 ?: return null
        val style = u.GetWindowLongPtrW(hwnd, GWL_STYLE)
        val ex = u.GetWindowLongPtrW(hwnd, GWL_EXSTYLE)
        u.SetWindowLongPtrW(hwnd, GWL_STYLE, style and (WS_CAPTION or WS_THICKFRAME or WS_MAXIMIZEBOX).inv())
        u.SetWindowLongPtrW(hwnd, GWL_EXSTYLE, ex and (WS_EX_DLGMODALFRAME or WS_EX_WINDOWEDGE or WS_EX_CLIENTEDGE or WS_EX_STATICEDGE).inv())
        return Frame(style, ex)
    }

    /** Puts [f] back and has Windows (and AWT) measure the frame again. */
    fun restoreFrame(hwnd: Pointer, f: Frame) {
        val u = user32 ?: return
        u.SetWindowLongPtrW(hwnd, GWL_STYLE, f.style)
        u.SetWindowLongPtrW(hwnd, GWL_EXSTYLE, f.exStyle)
        u.SetWindowPos(hwnd, null, 0, 0, 0, 0, SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOOWNERZORDER or SWP_FRAMECHANGED or SWP_NOACTIVATE)
    }

    /** Places the window exactly on [b] (physical pixels), the frame re-measured, z-order untouched. */
    fun place(hwnd: Pointer, b: Box) {
        val u = user32 ?: return
        u.SetWindowPos(hwnd, null, b.x, b.y, b.w, b.h, SWP_NOZORDER or SWP_NOOWNERZORDER or SWP_FRAMECHANGED or SWP_NOACTIVATE)
    }

    /** Right under the window that is in front now (after Full Screen let go of "always in front"). */
    fun behindForeground(hwnd: Pointer) {
        val u = user32 ?: return
        val fg = u.GetForegroundWindow() ?: return
        if (fg == hwnd) return
        u.SetWindowPos(hwnd, fg, 0, 0, 0, 0, SWP_NOMOVE or SWP_NOSIZE or SWP_NOACTIVATE or SWP_NOOWNERZORDER)
    }

    // ---- ITaskbarList2::MarkFullscreenWindow ----

    private interface Ole32 : StdCallLibrary {
        fun CoInitializeEx(reserved: Pointer?, coInit: Int): Int
        fun CoCreateInstance(clsid: Pointer, outer: Pointer?, ctx: Int, iid: Pointer, out: PointerByReference): Int
    }

    private val ole32: Ole32? by lazy { if (!windows) null else runCatching { Native.load("ole32", Ole32::class.java) }.getOrNull() }
    private var taskbar: Pointer? = null

    private fun guid(s: String): Memory {
        val h = s.replace("-", "")
        val m = Memory(16)
        m.setInt(0, java.lang.Long.parseLong(h.substring(0, 8), 16).toInt())
        m.setShort(4, Integer.parseInt(h.substring(8, 12), 16).toShort())
        m.setShort(6, Integer.parseInt(h.substring(12, 16), 16).toShort())
        for (i in 0 until 8) m.setByte(8L + i, Integer.parseInt(h.substring(16 + i * 2, 18 + i * 2), 16).toByte())
        return m
    }

    private fun vcall(obj: Pointer, slot: Int, vararg args: Any?): Int {
        val fn = Function.getFunction(obj.getPointer(0).getPointer(slot.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
        return fn.invokeInt(arrayOf<Any?>(obj, *args))
    }

    /**
     * Tells the taskbar [hwnd] is (or no longer is) a full-screen window, so it stays down on that monitor even with
     * auto-hide on. Best effort: a failure is logged and changes nothing else. Called on the event thread.
     */
    fun markFullScreen(hwnd: Pointer, on: Boolean) {
        val ole = ole32 ?: return
        runCatching {
            if (taskbar == null) {
                ole.CoInitializeEx(null, 2) // apartment threaded; S_FALSE / RPC_E_CHANGED_MODE are fine here
                val out = PointerByReference()
                val hr = ole.CoCreateInstance(guid("56FDF344-FD6D-11d0-958A-006097C9A090"), null, 1,
                    guid("602D4995-B13A-429b-A66E-1935E44F4317"), out)
                if (hr != 0) { Log.w("Display", "taskbar list: 0x${Integer.toHexString(hr)}"); return }
                val t = out.value
                vcall(t, 3) // HrInit
                taskbar = t
            }
            vcall(taskbar!!, 8, hwnd, if (on) 1 else 0) // ITaskbarList2::MarkFullscreenWindow
        }.onFailure { Log.w("Display", "taskbar mark: ${it.message}") }
    }
}
