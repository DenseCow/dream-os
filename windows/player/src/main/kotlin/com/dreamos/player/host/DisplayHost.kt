package com.dreamos.player.host

/**
 * The app's display mode, which Dream Player follows (one source of truth: the app's Settings › Your PC › Display
 * Mode, F11 and Alt+Enter). The app sets [PlayerDisplay.host]; the player's own dev runners leave it null and keep
 * the player's old full-screen window of its own.
 */
interface DisplayHost {
    /** The app window covers its monitor (Full Screen Windowed or Full Screen): the player covers it too. */
    val fullScreen: Boolean
    /** Full Screen while Dream OS is in front: the player's windows stay in front of every other window too. */
    val onTop: Boolean
    /** The player's F / F11, double click and Full Screen button: the app switches mode, the player follows. */
    fun toggleFullScreen()
    /** [l] runs on the event thread after every change of [fullScreen] or [onTop]; returns the way to stop. */
    fun listen(l: () -> Unit): () -> Unit
}

object PlayerDisplay {
    @Volatile var host: DisplayHost? = null
}
