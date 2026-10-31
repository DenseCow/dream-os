package com.dreamos.player

import com.dreamos.player.engine.MpvEngine
import com.dreamos.player.engine.PlayerLog
import com.dreamos.player.thumbs.ScrubThumbnails
import javax.swing.SwingUtilities

/**
 * The libmpv side of closing the player, off the UI thread: the previews are told to stop (their own thread frees
 * their libmpv), the main libmpv is terminated (it lets go of the video window; on a big stream this can take a
 * while), then [released] runs on the UI thread. The UI thread never waits on mpv. When it is still going after
 * [SLOW_MS], every thread's stack goes to the log.
 */
internal object Teardown {
    const val SLOW_MS = 3_000L

    /** Starts the teardown and returns at once; [released] (UI thread) comes after mpv has let go, even on an error. */
    fun begin(engine: MpvEngine, thumbs: ScrubThumbnails, released: () -> Unit) {
        val t0 = System.nanoTime()
        thumbs.close()
        val closer = Thread({
            try {
                engine.destroy()
                if (!thumbs.awaitClosed(SLOW_MS)) PlayerLog.w("Thumbs: still letting go of their stream after ${SLOW_MS / 1000} s")
            } catch (t: Throwable) {
                PlayerLog.e("Engine: close failed", t)
            } finally {
                SwingUtilities.invokeLater {
                    runCatching(released).onFailure { PlayerLog.e("Stage: release after close failed", it) }
                    PlayerLog.i("Engine: closed in ${(System.nanoTime() - t0) / 1_000_000} ms")
                }
            }
        }, "dream-player-close").apply { isDaemon = true; start() }
        Thread({
            closer.join(SLOW_MS)
            if (closer.isAlive) PlayerLog.w("Engine: still closing after $SLOW_MS ms; every thread:\n${PlayerLog.threadDump()}")
        }, "dream-player-close-watch").apply { isDaemon = true; start() }
    }
}
