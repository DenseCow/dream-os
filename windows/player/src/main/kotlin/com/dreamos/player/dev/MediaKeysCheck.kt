package com.dreamos.player.dev

import com.dreamos.player.engine.PlayerLog
import com.dreamos.player.host.MediaControls
import com.sun.jna.Native
import javax.swing.JDialog
import javax.swing.SwingUtilities

/**
 * Checks the system media controls without showing anything: a window that only gets its native handle (never made
 * visible), the controls attached, a title and the paused state sent, read back, then closed after two seconds.
 * `./gradlew :player:mediakeys`.
 */
fun main() {
    lateinit var w: JDialog
    SwingUtilities.invokeAndWait { w = JDialog().apply { isUndecorated = true; addNotify() } }
    val hwnd = Native.getWindowID(w)
    val mc = MediaControls.attach(hwnd) { b -> PlayerLog.i("MediaKeysCheck: button $b") } ?: error("not Windows")
    mc.update(MediaControls.Info("Sintel", "Movie  ·  2010", "https://image.tmdb.org/t/p/w780/msqeiEyIRpPAtrCeRGFNZQ9tkJL.jpg", playing = false, canNext = true))
    Thread.sleep(1500)
    PlayerLog.i("MediaKeysCheck: ${mc.readBack()}")
    mc.close()
    SwingUtilities.invokeAndWait { w.dispose() }
    kotlin.system.exitProcess(0)
}
