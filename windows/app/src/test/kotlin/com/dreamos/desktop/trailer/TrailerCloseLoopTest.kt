package com.dreamos.desktop.trailer

import androidx.compose.ui.graphics.asSkiaBitmap
import com.dreamos.player.engine.LibMpv
import com.dreamos.player.engine.PlayerLog
import java.io.File
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.fail

/**
 * Opens the hero trailer's libmpv on a public stream and destroys it 50 times at a different moment each round, with
 * two threads hammering it the way the title page does: one reads position, duration and buffer and sets volume,
 * pause, zoom; one paints the current frame (reads a pixel of it under the frame lock). Headless, silent (no audio
 * device), no window.
 *
 * A round fails when destroy holds the calling (UI) thread, when libmpv isn't let go within [RELEASE_LIMIT_MS] (the
 * log gets every thread's stack), when a hammer thread throws or hangs, or when a trailer thread is left behind. A
 * native crash ends the test JVM, which fails the run.
 *
 * Live (it streams Sintel): `./gradlew :app:test --tests "*TrailerCloseLoopTest*" -Pdream.live=true`, rounds with
 * `-Pdream.trailerloop.rounds=N`, another stream with `-Pdream.url=...`.
 */
class TrailerCloseLoopTest {
    private val live = System.getProperty("dream.live") == "true"
    private val rounds = (System.getProperty("dream.trailerloop.rounds") ?: "50").toInt()
    private val url = System.getProperty("dream.url")?.takeIf { it.isNotBlank() } ?: SINTEL

    /**
     * DURING_LOAD: 0 to 1.2 s after loadfile, usually before any picture. FIRST_FRAME: the moment the first frame is
     * drawn. MID_RENDER: 0.1 to 3 s into playback. IN_FRAME: from the frame callback itself (the render thread).
     * IN_EVENT: from the "playback started" callback (mpv's event thread).
     */
    private enum class Way { DURING_LOAD, FIRST_FRAME, MID_RENDER, IN_FRAME, IN_EVENT }

    @Test fun destroysSafelyAgainAndAgain() {
        if (!live) { println("TrailerCloseLoopTest: live tests off (-Pdream.live=true), skipped"); return }
        if (LibMpv.dirs().none { File(it, "libmpv-2.dll").isFile }) { println("TrailerCloseLoopTest: no libmpv-2.dll (tools/fetch-mpv.sh), skipped"); return }
        check(BackdropVideo.SILENT) { "run with -Ddream.trailer.silent=true (the app's test task sets it)" }
        val problems = ArrayList<String>()
        for (n in 1..rounds) {
            val way = Way.entries[(n - 1) % Way.entries.size]
            val problem = runCatching { round(n, way) }.getOrElse { "threw ${PlayerLog.describe(it)}" }
            if (problem == null) println("TrailerCloseLoopTest: round $n ${way.name} ok")
            else { System.err.println("TrailerCloseLoopTest: round $n ${way.name} FAILED: $problem"); problems += "round $n ${way.name}: $problem" }
        }
        println("TrailerCloseLoopTest: ${rounds - problems.size} of $rounds rounds passed")
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    private fun round(n: Int, way: Way): String? {
        val rnd = Random(n.toLong())
        val firstFrame = CountDownLatch(1)
        val destroyedInCallback = CountDownLatch(1)
        val frames = AtomicLong()
        val self = AtomicReference<BackdropVideo>()
        val inFrameAt = 1 + rnd.nextInt(5)
        val v = BackdropVideo(
            muted = true,
            onFrame = { f ->
                frames.set(f)
                firstFrame.countDown()
                if (way == Way.IN_FRAME && f >= inFrameAt) self.get()?.let { it.destroy(); destroyedInCallback.countDown() }
            },
            onEvent = { e ->
                if (way == Way.IN_EVENT && e == BackdropVideo.Event.STARTED) self.get()?.let { it.destroy(); destroyedInCallback.countDown() }
            },
        )
        self.set(v)
        v.size(1280, 720)
        v.load(url)

        val stop = AtomicBoolean(false)
        val hammerError = AtomicReference<Throwable>()
        val reads = AtomicLong()
        val readers = Thread({
            var i = 0
            try {
                while (!stop.get()) {
                    v.positionMs; v.durationMs; v.cacheAheadSec; v.videoHeight
                    when (i++ % 4) {
                        0 -> v.setVolume(0)
                        1 -> v.pause(false)
                        2 -> v.setZoom(1f + (i % 10) / 100f)
                        else -> v.playing()
                    }
                    reads.incrementAndGet()
                }
            } catch (t: Throwable) { hammerError.compareAndSet(null, t) }
        }, "trailer-loop-readers").apply { isDaemon = true; start() }
        val painter = Thread({
            try {
                while (!stop.get()) {
                    v.withFrame { img -> img?.asSkiaBitmap()?.let { b -> b.getColor(b.width / 2, b.height / 2) } }
                    Thread.sleep(4)
                }
            } catch (t: Throwable) { hammerError.compareAndSet(null, t) }
        }, "trailer-loop-painter").apply { isDaemon = true; start() }

        var uiMs = 0L
        fun destroyOnUi() {
            SwingUtilities.invokeAndWait {
                val t0 = System.nanoTime()
                v.destroy()
                uiMs = (System.nanoTime() - t0) / 1_000_000
            }
        }
        when (way) {
            Way.DURING_LOAD -> { Thread.sleep(rnd.nextInt(1_200).toLong()); destroyOnUi() }
            Way.FIRST_FRAME -> {
                if (!firstFrame.await(FIRST_FRAME_LIMIT_S, TimeUnit.SECONDS)) { v.destroy(); return "no first frame" }
                destroyOnUi()
            }
            Way.MID_RENDER -> {
                if (!firstFrame.await(FIRST_FRAME_LIMIT_S, TimeUnit.SECONDS)) { v.destroy(); return "no first frame" }
                Thread.sleep(100L + rnd.nextInt(2_900))
                destroyOnUi()
            }
            Way.IN_FRAME, Way.IN_EVENT -> {
                if (!destroyedInCallback.await(FIRST_FRAME_LIMIT_S, TimeUnit.SECONDS)) { v.destroy(); return "the callback never came" }
                // A second destroy from the UI thread must return at once.
                destroyOnUi()
            }
        }
        if (uiMs > UI_LIMIT_MS) return "destroy held the UI thread for $uiMs ms"
        // The hammers keep going through the release: every call must come back (and do nothing) once it began.
        if (!v.awaitReleased(RELEASE_LIMIT_MS)) {
            System.err.println("TrailerCloseLoopTest: not let go after $RELEASE_LIMIT_MS ms\n${PlayerLog.threadDump()}")
            return "libmpv not let go within $RELEASE_LIMIT_MS ms"
        }
        Thread.sleep(50)
        stop.set(true)
        readers.join(5_000); painter.join(5_000)
        if (readers.isAlive || painter.isAlive) return "a hammer thread hung:\n${PlayerLog.threadDump()}"
        hammerError.get()?.let { return "a hammer thread threw ${PlayerLog.describe(it)}" }
        val left = leftThreads()
        if (left.isNotEmpty()) {
            val end = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < end && leftThreads().isNotEmpty()) Thread.sleep(25)
            leftThreads().takeIf { it.isNotEmpty() }?.let { return "threads left behind: $it" }
        }
        println("TrailerCloseLoopTest: round $n ${frames.get()} frames, ${reads.get()} reader turns, destroy on the UI thread $uiMs ms")
        return null
    }

    private fun leftThreads() = Thread.getAllStackTraces().keys
        .filter { it.isAlive && it.name in setOf(BackdropVideo.RENDER_THREAD, BackdropVideo.EVENT_THREAD, BackdropVideo.RELEASE_THREAD) }
        .map { it.name }

    private companion object {
        const val SINTEL = "https://download.blender.org/durian/movies/Sintel.2010.1080p.mkv"
        /** destroy only queues a mute and starts a thread. */
        const val UI_LIMIT_MS = 100L
        const val RELEASE_LIMIT_MS = 15_000L
        const val FIRST_FRAME_LIMIT_S = 40L
    }
}
