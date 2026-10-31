package com.dreamos.desktop.shell

/**
 * How the app window sits on the PC's screen. Saved per PC (window.properties), not per profile. F11 and Alt+Enter
 * switch between [WINDOWED] and the full-screen kind chosen last ([FULL_SCREEN_WINDOWED] at first).
 */
enum class DisplayMode(val key: String, val label: String, val line: String) {
    FULL_SCREEN_WINDOWED(
        "fullScreenWindowed", "Full Screen Windowed",
        "Fills the screen without a border, and other apps are one Alt+Tab away.",
    ),
    FULL_SCREEN(
        "fullScreen", "Full Screen",
        "Fills the screen and stays in front of every other window while you use Dream OS.",
    ),
    WINDOWED("windowed", "Windowed", "A window you can move and resize, opening at the size and place you left it."),
    ;

    val full: Boolean get() = this != WINDOWED

    companion object {
        val DEFAULT = FULL_SCREEN_WINDOWED
        fun ofKey(k: String?) = entries.firstOrNull { it.key == k }
        fun ofLabel(l: String?) = entries.firstOrNull { it.label == l }
    }
}

/** A rectangle in one coordinate space (physical pixels for monitors, AWT units for saved window bounds). */
data class Box(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right get() = x + w
    val bottom get() = y + h
    fun overlap(o: Box): Long {
        val ow = minOf(right, o.right) - maxOf(x, o.x)
        val oh = minOf(bottom, o.bottom) - maxOf(y, o.y)
        return if (ow <= 0 || oh <= 0) 0L else ow.toLong() * oh
    }
    fun contains(px: Int, py: Int) = px >= x && px < right && py >= y && py < bottom
    /** Squared distance from (px, py) to the nearest point of this box (0 inside). */
    fun distance2(px: Int, py: Int): Long {
        val dx = (if (px < x) x - px else if (px >= right) px - right + 1 else 0).toLong()
        val dy = (if (py < y) y - py else if (py >= bottom) py - bottom + 1 else 0).toLong()
        return dx * dx + dy * dy
    }
    override fun toString() = "$x,$y,$w,$h"

    companion object {
        fun parse(s: String?): Box? = s?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 }
            ?.let { Box(it[0], it[1], it[2], it[3]) }
    }
}

/**
 * The window-bounds math, kept free of Windows so it can be checked offscreen (`DREAM_SNAP_PAGES=display-math`).
 * Monitors here are the monitors' full rectangles (taskbar ignored) in one space: Windows' physical pixels, where
 * monitors of different scaling sit side by side, a monitor left of or above the main one has negative coordinates,
 * and nothing is scaled.
 */
object ScreenFit {
    /** The monitor [window] is on: the one it overlaps most (Windows' MONITOR_DEFAULTTONEAREST), else the nearest. */
    fun monitorFor(window: Box, monitors: List<Box>): Box? {
        if (monitors.isEmpty()) return null
        val best = monitors.maxByOrNull { it.overlap(window) }!!
        if (best.overlap(window) > 0) return best
        val cx = window.x + window.w / 2
        val cy = window.y + window.h / 2
        return monitors.minByOrNull { it.distance2(cx, cy) }
    }

    /**
     * Where a full-screen window goes at launch: the monitor it was full screen on last time ([saved]) while that
     * monitor is still there (the same rectangle, or a resized one at the same top-left corner, a resolution change),
     * else the monitor the window's saved bounds are on, else the first monitor (the main one).
     */
    fun fullTarget(saved: Box?, window: Box?, monitors: List<Box>): Box? {
        if (monitors.isEmpty()) return null
        if (saved != null) {
            monitors.firstOrNull { it == saved }?.let { return it }
            monitors.firstOrNull { it.x == saved.x && it.y == saved.y }?.let { return it }
        }
        if (window != null) monitorFor(window, monitors)?.let { if (it.overlap(window) > 0) return it }
        return monitors.first()
    }

    /**
     * Saved windowed bounds that can still be reached: kept when a strip of the title bar (the top 32 units, at least
     * 120 wide) is on some monitor's work area, otherwise null (Windows picks the place). Sizes larger than the
     * biggest work area are not kept either.
     */
    fun reachable(saved: Box?, workAreas: List<Box>): Box? {
        if (saved == null || workAreas.isEmpty()) return saved.takeIf { workAreas.isEmpty() }
        val bar = Box(saved.x, saved.y, saved.w, minOf(32, saved.h))
        val onScreen = workAreas.any { a ->
            val ow = minOf(bar.right, a.right) - maxOf(bar.x, a.x)
            val oh = minOf(bar.bottom, a.bottom) - maxOf(bar.y, a.y)
            ow >= minOf(120, saved.w) && oh > 0
        }
        val fits = workAreas.any { saved.w <= it.w + 16 && saved.h <= it.h + 16 }
        return saved.takeIf { onScreen && fits }
    }

    /** F11 and Alt+Enter: Windowed goes to the last full-screen kind; either full-screen kind goes to Windowed. */
    fun toggled(mode: DisplayMode, lastFull: DisplayMode): DisplayMode =
        if (mode.full) DisplayMode.WINDOWED else (if (lastFull.full) lastFull else DisplayMode.DEFAULT)
}
