package com.dreamos.player

import com.dreamos.desktop.core.playback.MediaKind
import com.dreamos.desktop.core.playback.PlayTarget
import com.dreamos.player.api.PlayerEvents
import com.dreamos.player.dev.DemoServices
import com.dreamos.player.dev.DevPlayback
import com.dreamos.player.dev.SINTEL
import com.dreamos.player.dev.devStore
import com.dreamos.player.engine.LibMpv
import com.dreamos.player.engine.MpvEngine
import com.dreamos.player.engine.PlayerLog
import com.dreamos.player.session.PlayerSession
import com.dreamos.player.thumbs.ScrubThumbnails
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.fail

/**
 * Opens and closes the player's engine 50 times with the scrub previews running (their own libmpv), headless: no
 * window, no sound. Each round closes a different way: 0.2 to 3 s after the previews opened, while the stream loads,
 * mid-seek, with subtitles just added. A round fails when the close holds the UI thread, when mpv isn't let go within
 * [RELEASE_LIMIT_MS] (the log gets every thread's stack), or when an mpv or preview thread is left behind; a native
 * crash ends the test JVM, which fails the run.
 *
 * Live (it streams Sintel): `./gradlew :player:test --tests "*CloseLoopTest*" -Pdream.live=true`, rounds with
 * `-Pdream.closeloop.rounds=N`, another stream with `-Pdream.url=...` (an `edl://` link repeats a short clip).
 */
class CloseLoopTest {
    private val live = System.getProperty("dream.live") == "true"
    private val rounds = (System.getProperty("dream.closeloop.rounds") ?: "50").toInt()
    private val url = System.getProperty("dream.url")?.takeIf { it.isNotBlank() } ?: SINTEL

    private enum class Way { AFTER_THUMBS, DURING_LOAD, DURING_SEEK, AFTER_THUMBS_SUBS }

    @Test fun closesSafelyAgainAndAgain() {
        if (!live) { println("CloseLoopTest: live tests off (-Pdream.live=true), skipped"); return }
        if (LibMpv.dirs().none { File(it, "libmpv-2.dll").isFile }) { println("CloseLoopTest: no libmpv-2.dll (tools/fetch-mpv.sh), skipped"); return }
        devStore()
        val problems = ArrayList<String>()
        for (n in 1..rounds) {
            val way = Way.entries[(n - 1) % Way.entries.size]
            val problem = runCatching { round(n, way) }.getOrElse { "threw ${PlayerLog.describe(it)}" }
            if (problem == null) PlayerLog.i("CloseLoopTest: round $n ${way.name} ok")
            else { PlayerLog.e("CloseLoopTest: round $n ${way.name} FAILED: $problem"); problems += "round $n ${way.name}: $problem" }
        }
        PlayerLog.i("CloseLoopTest: ${rounds - problems.size} of $rounds rounds passed")
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    private fun round(n: Int, way: Way): String? {
        val target = PlayTarget(MediaKind.MOVIE, 45745, "Sintel", 2010, imdb = "tt1727587")
        val dev = DevPlayback(url)
        val released = CountDownLatch(1)
        lateinit var engine: MpvEngine
        lateinit var thumbs: ScrubThumbnails
        lateinit var session: PlayerSession
        ui {
            engine = MpvEngine(null)
            val req = kotlinx.coroutines.runBlocking { dev.play(target, 0) }
            // The same teardown DreamPlayer runs, with the windows' part replaced by the latch.
            session = PlayerSession(req, dev, DemoServices(), object : PlayerEvents {}, engine) {
                Teardown.begin(engine, thumbs) { released.countDown() }
            }
            thumbs = ScrubThumbnails(session)
        }
        fun waitFor(ms: Long, what: () -> Boolean): Boolean {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) { if (what()) return true; Thread.sleep(25) }
            return false
        }
        when (way) {
            Way.DURING_LOAD -> Thread.sleep(50L + (n * 131L) % 1_200)
            Way.DURING_SEEK -> {
                if (!waitFor(40_000) { session.engineState.firstFrame }) return "no first frame"
                Thread.sleep(300)
                ui { session.seekTo(((n * 61_000L) % 700_000L) + 30_000L) }
                Thread.sleep((n * 23L) % 150)
            }
            Way.AFTER_THUMBS, Way.AFTER_THUMBS_SUBS -> {
                if (!waitFor(40_000) { session.engineState.firstFrame }) return "no first frame"
                if (!waitFor(40_000) { thumbs.opened }) return "previews never opened"
                Thread.sleep(200L + (n * 389L) % 2_800)
                if (way == Way.AFTER_THUMBS_SUBS) ui { engine.addSubtitle(subFile.path, "Test", "en", select = true) }
            }
        }
        val t0 = System.nanoTime()
        ui { session.close() }
        val closeMs = (System.nanoTime() - t0) / 1_000_000
        if (closeMs > UI_LIMIT_MS) return "close held the UI thread for $closeMs ms"
        if (!released.await(RELEASE_LIMIT_MS, TimeUnit.MILLISECONDS)) {
            PlayerLog.e("CloseLoopTest: mpv not let go after $RELEASE_LIMIT_MS ms\n${PlayerLog.threadDump()}")
            return "mpv not let go within $RELEASE_LIMIT_MS ms"
        }
        if (!waitFor(5_000) { leftThreads().isEmpty() }) return "threads left behind: ${leftThreads()}"
        return null
    }

    private fun leftThreads() = Thread.getAllStackTraces().keys
        .filter { it.isAlive && (it.name.startsWith("mpv-") || it.name == "dream-thumbs" || it.name == "dream-player-close") }
        .map { it.name }

    private fun ui(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    private val subFile: File by lazy {
        File.createTempFile("dream-closeloop", ".srt").apply {
            deleteOnExit()
            writeText("1\n00:00:00,000 --> 00:59:00,000\nClose loop\n")
        }
    }

    private companion object {
        /** The UI thread's share of closing (saving, Trakt, the windows hidden): never a wait on mpv. */
        const val UI_LIMIT_MS = 500L
        const val RELEASE_LIMIT_MS = 15_000L
    }
}
