package com.dreamos.desktop.shell

/**
 * The viewer's Windows "Animation effects" setting (Settings > Accessibility > Visual effects), read once at start.
 * Off: the hero stops advancing by itself, crossfades and page slides become instant cuts.
 */
object Motion {
    @Volatile var reduced: Boolean = false
        internal set // MenuCheck holds the hero still

    fun read() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        runCatching {
            val p = ProcessBuilder("reg", "query", "HKCU\\Control Panel\\Desktop\\WindowMetrics", "/v", "MinAnimate").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            // "MinAnimate    REG_SZ    0" when animation effects are off.
            reduced = Regex("MinAnimate\\s+REG_SZ\\s+(\\d)").find(out)?.groupValues?.get(1) == "0"
        }
    }
}
