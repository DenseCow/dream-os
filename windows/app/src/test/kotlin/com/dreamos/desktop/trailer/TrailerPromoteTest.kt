package com.dreamos.desktop.trailer

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.dreamos.desktop.core.trailer.TrailerEngine
import com.dreamos.desktop.core.trailer.TrailerRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Trailer button promoting the hero's playing trailer to full screen (owner, 2026-10-31): the live decoder moves
 * to the full-screen surface instead of a second one being opened on the same video, and comes back let go of once.
 *
 * No libmpv and no network here: the decoder is a stand-in ([LiveVideo]) that counts what is asked of it, so a
 * reload, a second instance or a missing destroy shows as a number. The real thing, with libmpv, is
 * `TrailerCloseLoopTest.promotesToFullScreenAndComesBack` (live).
 */
class TrailerPromoteTest {
    private val movie = TrailerRef(1, false, "A Test Movie")
    private val show = TrailerRef(2, true, "A Test Show")
    private val key = "abc123"

    /** No frames are sent in the checks, so the animations simply never move on. */
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default + BroadcastFrameClock())

    private class FakeVideo(var position: Long = 42_000, val duration: Long = 150_000) : LiveVideo {
        val opened = AtomicInteger(1)
        var loads = 0; private set
        var destroys = 0; private set
        var soundOpened = 0; private set
        var fits = 0; private set
        var covers = 0; private set
        var coverZoom = 0f; private set
        var listens = 0; private set
        var volume = 0; private set
        var paused = false; private set
        var size: Pair<Int, Int>? = null; private set
        var paints = 0; private set
        var frames: ((Long) -> Unit)? = null; private set
        var events: ((BackdropVideo.Event) -> Unit)? = null; private set

        override fun size(w: Int, h: Int) { size = w to h }
        override fun load(url: String) { loads++ }
        override fun setVolume(v: Int) { volume = v }
        override fun pause(on: Boolean) { paused = on }
        override fun playing() {}
        override fun enableSound(): Boolean { soundOpened++; return true }
        override fun setZoom(z: Float) {}
        override fun fit() { fits++ }
        override fun cover(zoom: Float) { covers++; coverZoom = zoom }
        override val positionMs: Long get() = position
        override val durationMs: Long get() = duration
        override fun probeFrames(check: ((BackdropVideo.Sampler) -> Unit)?) {}
        /** A flat green frame stands in for the trailer's picture, so painting it is visible work. */
        private val picture = ImageBitmap(8, 8).also { b ->
            Canvas(b).drawRect(0f, 0f, 8f, 8f, Paint().apply { color = Color(0xFF2FBF4F) })
        }
        override fun <T> withFrame(paint: (ImageBitmap?) -> T): T { paints++; return paint(picture) }
        override fun listen(onFrame: (Long) -> Unit, onEvent: (BackdropVideo.Event) -> Unit) {
            listens++; frames = onFrame; events = onEvent
        }
        override fun destroy() { destroys++ }
    }

    /** Esc as the window would send it (no window is opened). */
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun escape() = androidx.compose.ui.input.key.KeyEvent(
        androidx.compose.ui.input.key.Key.Escape, androidx.compose.ui.input.key.KeyEventType.KeyDown)

    private fun waitFor(what: String, ms: Long = 5_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (done()) return
            Thread.sleep(10)
        }
        fail("$what did not happen within $ms ms")
    }

    @Test fun promotionMovesTheLiveDecoderAndLoadsNothing() {
        val scope = scope()
        try {
            val hero = TrailerBackdropState(scope)
            val v = FakeVideo()
            hero.playingForChecks(movie, key, v)
            val handed = hero.promote(movie) ?: fail("the playing trailer was not handed over")
            assertSame(v, handed.video, "the same decoder must go to full screen")
            assertEquals(key, handed.videoKey)
            assertEquals(0, v.loads, "nothing may be loaded again")
            assertEquals(0, v.destroys, "the decoder plays on")
            assertEquals(1, v.opened.get(), "no second instance")
            assertFalse(hero.holdsVideoForChecks, "the page must let go of the handle")
            assertFalse(hero.playing, "the hero is no longer the picture")
            assertFalse(hero.immersive)
            assertTrue(hero.stillForChecks, "the hero keeps still while the trailer is up full screen")
            assertNull(hero.promote(movie), "there is nothing left to hand over")
        } finally { scope.cancel() }
    }

    @Test fun nothingToPromoteWithoutAPlayingTrailerOfThatTitle() {
        val scope = scope()
        try {
            // Off, still resolving, waiting for the rest, stopped: the hero holds no picture.
            assertNull(TrailerBackdropState(scope).promote(movie), "an idle hero has nothing to promote")
            // Another title's trailer is playing (a page opened under it): the button starts its own, as before.
            val hero = TrailerBackdropState(scope)
            hero.playingForChecks(show, key, FakeVideo())
            assertNull(hero.promote(movie), "another title's trailer is not this title's")
            assertTrue(hero.holdsVideoForChecks, "a refused promotion leaves the hero as it was")
        } finally { scope.cancel() }
    }

    @Test fun fullScreenRaisesTheSoundAndKeepsThePictureRunning() {
        val scope = scope()
        try {
            val hero = TrailerBackdropState(scope)
            val v = FakeVideo()
            hero.playingForChecks(movie, key, v)
            val host = FullTrailerHost(scope)
            assertTrue(host.take(hero, movie), "the playing trailer goes full screen")
            val full = host.live ?: fail("no full-screen trailer")
            assertEquals(1, v.listens, "the frames and the ends move to the full-screen surface")
            assertEquals(1, v.fits, "full screen shows the whole picture, not the hero's crop")
            // The hero was muted ("On, Muted"): the audio device is opened here, and the sound comes up.
            assertEquals(1, v.soundOpened)
            waitFor("the sound fade") { v.volume == 100 }
            assertTrue(full.soundOn)
            assertEquals(0, v.loads, "no reload, no re-buffer")
            assertEquals(0, v.destroys)
            assertEquals(42_000L, v.positionMs, "the picture carries on where it was")
            full.sizeChanged(androidx.compose.ui.unit.IntSize(1920, 1080))
            assertEquals(1920 to 1080, v.size, "the picture is drawn at the window's size")
            full.togglePause()
            assertTrue(v.paused)
            full.togglePause()
            assertFalse(v.paused)
        } finally { scope.cancel() }
    }

    /**
     * Coming out must carry on the way going in did (owner, 2026-10-31): the same decoder goes back to the hero,
     * still playing, muted again unless the viewer chose sound, and it is let go of once, by the hero, when the page
     * is done with it.
     */
    @Test fun comingBackGivesTheDecoderToTheHeroStillPlaying() {
        val scope = scope()
        try {
            val hero = TrailerBackdropState(scope)
            val v = FakeVideo(position = 61_500)
            hero.playingForChecks(movie, key, v)
            val host = FullTrailerHost(scope)
            host.take(hero, movie)
            val full = host.live ?: fail("no full-screen trailer")
            v.position = 95_250
            full.close()
            assertEquals(0, v.destroys, "the picture carries on: nothing is let go of on the way back")
            assertEquals(0, v.loads, "and nothing is loaded again, in either direction")
            assertEquals(1, v.opened.get(), "still the one decoder")
            assertTrue(hero.holdsVideoForChecks, "the hero has it again")
            assertTrue(hero.playing, "and is playing it behind the picture")
            assertFalse(hero.stillForChecks, "it carries on rather than waiting for the viewer to move")
            assertEquals(1, v.covers, "the hero's framing is back (its crop and letterbox zoom)")
            assertEquals(0, v.volume, "a hero trailer is muted again unless the viewer chose sound")
            assertFalse(hero.soundOn, "and the speaker says so")
            assertEquals(key to 95_250L, TrailerEngine.spot(movie), "the Trailer button continues from where it stopped")
            assertFalse(host.showing, "the page is back")
            // Close, Esc and the page leaving can all arrive: only the first one counts.
            full.close()
            assertEquals(0, v.destroys, "a second close changes nothing")
            assertTrue(hero.holdsVideoForChecks)
            // The hero is the only owner now, and lets go exactly once.
            hero.stopNow()
            assertEquals(1, v.destroys, "the hero lets go of it once, as of any hero trailer")
            assertFalse(hero.holdsVideoForChecks)
        } finally { scope.cancel() }
    }

    /** The viewer chose sound on the hero: it comes back at the hero's own level, not full screen's. */
    @Test fun comingBackPutsTheHerosOwnSoundLevelBack() {
        val scope = scope()
        val chosen = TrailerSession.sound
        try {
            TrailerSession.sound = true
            val hero = TrailerBackdropState(scope)
            val v = FakeVideo()
            hero.playingForChecks(movie, key, v, soundOpen = true)
            val host = FullTrailerHost(scope)
            host.take(hero, movie)
            val full = host.live ?: fail("no full-screen trailer")
            waitFor("full screen's own level") { v.volume == 100 }
            full.close()
            assertTrue(hero.soundOn, "the viewer's choice stands")
            waitFor("the hero's level") { v.volume == 80 }
            assertEquals(0, v.destroys)
        } finally {
            TrailerSession.sound = chosen
            scope.cancel()
        }
    }

    /**
     * The surface as it is really composed, offscreen (no window, no sound): the handed picture is painted, the
     * controls are there and work with the mouse, Esc closes it without a mouse, and the controls fade after the
     * idle time and come back on a move. One decoder throughout, let go of once.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun theSurfaceDrawsItsPictureAndClosesByMouseOrKey() {
        // The smallest window the app allows and a big one: the controls must not collide at either.
        for ((byKey, size) in listOf(false to (1024 to 640), true to (1920 to 1080))) {
            val w = size.first
            val h = size.second
            val scope = scope()
            val hero = TrailerBackdropState(scope)
            val v = FakeVideo()
            hero.playingForChecks(movie, key, v)
            val host = FullTrailerHost(scope)
            assertTrue(host.take(hero, movie))
            val full = host.live ?: fail("no full-screen trailer")
            var now = 0L
            val scene = ImageComposeScene(w, h, Density(1f)) { FullTrailer(host, movie.name) }
            fun frames(ms: Long) {
                val end = now + ms * 1_000_000L
                while (now < end) {
                    Snapshot.sendApplyNotifications()
                    scene.render(now)
                    now += 16_000_000L
                }
            }
            try {
                frames(300)
                // Left for whoever reviews this: the surface as it really draws, at this size.
                java.io.File("build/full-trailer-${w}x$h.png").writeBytes(
                    scene.render(now).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
                assertTrue(v.paints > 0, "the handed picture is what the surface paints")
                assertEquals(0, v.loads, "the surface never loads anything")
                // The controls fade after the idle time, then a move brings them back (and keeps the pointer visible).
                frames(5_000)
                assertFalse(full.controlsUp, "the controls make way for the picture when nothing happens")
                scene.sendPointerEvent(PointerEventType.Move, Offset(w / 2f, h * 0.8f))
                frames(200)
                assertTrue(full.controlsUp, "a mouse move brings them back")
                if (byKey) {
                    scene.sendKeyEvent(escape())
                    frames(200)
                } else {
                    // Close, top left: 28 dp in, 22 dp down, 52 dp across.
                    val at = Offset(28f + 26f, 22f + 26f)
                    scene.sendPointerEvent(PointerEventType.Move, at)
                    frames(60)
                    scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    frames(120)
                    scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
                    frames(200)
                }
                assertTrue(full.closedForChecks, "${if (byKey) "Esc" else "Close"} ends the full-screen trailer")
                assertFalse(host.showing, "the page is back")
                assertEquals(0, v.destroys, "the picture goes back to the hero rather than stopping")
                assertTrue(hero.holdsVideoForChecks, "and the hero has it")
                hero.stopNow()
                assertEquals(1, v.destroys, "the hero then lets go of it once")
            } finally {
                scene.close()
                scope.cancel()
            }
        }
    }

    @Test fun theTrailerEndingClosesFullScreenItself() {
        val scope = scope()
        try {
            val hero = TrailerBackdropState(scope)
            val v = FakeVideo()
            hero.playingForChecks(movie, key, v)
            val host = FullTrailerHost(scope)
            host.take(hero, movie)
            val full = host.live ?: fail("no full-screen trailer")
            // mpv's event thread, as BackdropVideo reports it.
            v.events?.invoke(BackdropVideo.Event.ENDED) ?: fail("nobody is listening for the end")
            waitFor("the end to close full screen") { full.closedForChecks }
            waitFor("the page to come back") { !host.showing }
            // Nothing live to give back: let go of once, and the hero waits for the viewer, as after any end.
            assertEquals(1, v.destroys, "the end lets go of the decoder once")
            assertFalse(hero.holdsVideoForChecks, "the hero is not handed a trailer that has ended")
            assertTrue(hero.stillForChecks, "it starts again only once the viewer moves and the page rests")
        } finally { scope.cancel() }
    }
}
