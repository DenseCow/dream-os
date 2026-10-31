package com.dreamos.player

import com.dreamos.desktop.core.playback.DebridServiceId
import com.dreamos.desktop.core.playback.MediaKind
import com.dreamos.desktop.core.playback.PlayRequest
import com.dreamos.desktop.core.playback.PlayTarget
import com.dreamos.desktop.core.playback.Playback
import com.dreamos.desktop.core.playback.PlaybackApi
import com.dreamos.desktop.core.playback.Stream
import com.dreamos.player.api.PlayerEvents
import com.dreamos.player.dev.DemoServices
import com.dreamos.player.dev.DevPlayback
import com.dreamos.player.dev.SINTEL
import com.dreamos.player.dev.devStore
import com.dreamos.player.engine.LibMpv
import com.dreamos.player.engine.MpvEngine
import com.dreamos.player.session.PlayerSession
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The owner's "Now Playing stuck forever": a Play whose lookup never answers (a blocking call that ignores
 * cancellation) still ends in the loading screen's error at the curtain's deadline, and Try Again starts a fresh
 * lookup. Headless libmpv (no window, no sound, nothing loaded); skipped without libmpv-2.dll.
 */
class CurtainDeadlineTest {
    private class StuckPlayback(dev: PlaybackApi) : PlaybackApi by dev {
        val gate = CountDownLatch(1)
        val plays = AtomicInteger()
        val abandons = AtomicInteger()
        override suspend fun play(target: PlayTarget, startMs: Long): PlayRequest {
            plays.incrementAndGet()
            gate.await() // blocking: coroutine cancellation can't end it
            throw Playback.NOT_READY
        }
        override fun abandon(target: PlayTarget) { abandons.incrementAndGet() }
    }

    @Test fun aStuckLookupEndsInTheErrorAndTryAgainLooksAfresh() {
        if (LibMpv.dirs().none { File(it, "libmpv-2.dll").isFile }) { println("CurtainDeadlineTest: no libmpv-2.dll (tools/fetch-mpv.sh), skipped"); return }
        devStore()
        val target = PlayTarget(MediaKind.MOVIE, 45745, "Sintel", 2010, imdb = "tt1727587")
        val playback = StuckPlayback(DevPlayback(SINTEL))
        val before = PlayerSession.curtainMs
        PlayerSession.curtainMs = 2_000
        lateinit var engine: MpvEngine
        lateinit var session: PlayerSession
        try {
            ui {
                engine = MpvEngine(null)
                // DreamPlayer.play's request: no stream yet, the curtain shows while Dream Core looks.
                val pending = PlayRequest(target, Stream(url = "", service = DebridServiceId.REAL_DEBRID, fileName = ""), 0)
                session = PlayerSession(pending, playback, DemoServices(), object : PlayerEvents {}, engine) {
                    Thread({ engine.destroy() }, "curtain-test-close").apply { isDaemon = true }.start()
                }
            }
            assertTrue(waitFor(6_000) { onUi { session.curtain is PlayerSession.Curtain.Error } }, "the curtain never gave up on a stuck lookup")
            val error = onUi { session.curtain } as PlayerSession.Curtain.Error
            assertEquals(Playback.NOT_READY.title, error.title)
            assertEquals(1, playback.abandons.get(), "the stuck lookup wasn't dropped")
            // Try Again: a fresh lookup (the old one dropped again), and the curtain's deadline armed again.
            ui { session.tryAgain() }
            assertTrue(waitFor(2_000) { playback.plays.get() == 2 }, "Try Again didn't look again")
            assertTrue(onUi { session.curtain } == PlayerSession.Curtain.Loading)
            assertTrue(waitFor(6_000) { onUi { session.curtain is PlayerSession.Curtain.Error } }, "Try Again's curtain never gave up")
            assertTrue(playback.abandons.get() >= 2)
        } finally {
            PlayerSession.curtainMs = before
            playback.gate.countDown()
            ui { session.close() }
        }
    }

    private fun waitFor(ms: Long, what: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (what()) return true; Thread.sleep(50) }
        return false
    }

    private fun ui(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    private fun <T> onUi(block: () -> T): T {
        var r: Result<T>? = null
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r!!.getOrThrow()
    }
}
