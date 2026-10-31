package com.dreamos.desktop.shell

import java.io.File
import java.util.Properties

/**
 * The window as the viewer left it, per PC (not per profile): the display mode, the full-screen kind F11 returns to,
 * the monitor it was full screen on, and the windowed size, place and maximized state. A small file at
 * %APPDATA%\Dream OS\window.properties (core's settings store holds the viewer's settings; this is the shell's own).
 *
 * Migration (2026-10-30): a file from before display modes has no `mode`. It opens in Full Screen Windowed, the
 * owner's new default, keeping its windowed size, place and maximized state for when the viewer goes Windowed; the
 * old `fullscreen` flag is dropped on the next save.
 */
data class WindowPrefs(
    val w: Int = 1440, val h: Int = 860,
    val x: Int? = null, val y: Int? = null,
    val maximized: Boolean = false,
    val mode: DisplayMode = DisplayMode.DEFAULT,
    val lastFull: DisplayMode = DisplayMode.DEFAULT,
    /** The monitor's rectangle (physical pixels) the window was last full screen on. */
    val screen: Box? = null,
) {
    fun save() = runCatching {
        file.parentFile.mkdirs()
        Properties().apply {
            setProperty("w", w.toString()); setProperty("h", h.toString())
            x?.let { setProperty("x", it.toString()) }; y?.let { setProperty("y", it.toString()) }
            setProperty("maximized", maximized.toString())
            setProperty("mode", mode.key)
            setProperty("lastFull", lastFull.key)
            screen?.let { setProperty("screen", it.toString()) }
        }.let { p -> file.outputStream().use { p.store(it, "Dream OS window") } }
    }

    companion object {
        const val MIN_W = 1024
        const val MIN_H = 640

        private val file: File by lazy {
            val base = System.getenv("APPDATA")?.let { File(it) } ?: File(System.getProperty("user.home"), ".config")
            File(base, "Dream OS/window.properties")
        }

        fun load(): WindowPrefs = runCatching {
            if (!file.isFile) return WindowPrefs()
            val p = Properties().apply { file.inputStream().use { load(it) } }
            read(p::getProperty)
        }.getOrDefault(WindowPrefs())

        /** The parsing on its own, for the offscreen check of the migration. */
        fun read(get: (String) -> String?): WindowPrefs {
            val lastFull = DisplayMode.ofKey(get("lastFull"))?.takeIf { it.full } ?: DisplayMode.DEFAULT
            return WindowPrefs(
                w = get("w")?.toIntOrNull()?.coerceAtLeast(MIN_W) ?: 1440,
                h = get("h")?.toIntOrNull()?.coerceAtLeast(MIN_H) ?: 860,
                x = get("x")?.toIntOrNull(), y = get("y")?.toIntOrNull(),
                maximized = get("maximized") == "true",
                mode = DisplayMode.ofKey(get("mode")) ?: DisplayMode.DEFAULT,
                lastFull = lastFull,
                screen = Box.parse(get("screen")),
            )
        }
    }
}
