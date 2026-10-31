package com.dreamos.player.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.dreamos.desktop.core.playback.MediaKind
import com.dreamos.desktop.core.playback.PlayTarget
import com.dreamos.player.engine.MpvEngine
import com.dreamos.player.engine.PlayerLog
import com.dreamos.player.session.PlayerSession
import com.dreamos.player.thumbs.ScrubThumbnails
import com.dreamos.player.ui.NetImage
import com.dreamos.player.ui.PlayerScreen
import com.dreamos.player.ui.StageControl
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities

/**
 * Screenshots of the real player UI without a window, a sound or the pointer: a headless mpv plays the stream (so the
 * session, tracks, timeline and preview frames are real), and the overlay is rendered with ImageComposeScene over the
 * title's backdrop standing in for the picture. `./gradlew :player:shots -Pshots=<dir>`.
 */
/** One key press and release, as the keyboard sends it (a dev harness may lean on Compose's own key event builder). */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
private fun ImageComposeScene.key(key: Key) {
    for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
        sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(key, type))
        render()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val out = File(System.getProperty("dream.shots") ?: "build/shots").apply { mkdirs() }
    devStore()
    val url = System.getProperty("dream.url") ?: SINTEL
    val only = System.getProperty("dream.only")?.split(",")?.toSet()

    class FakeStage : StageControl {
        var full by mutableStateOf(false)
        var miniOn by mutableStateOf(false)
        override val fullScreen get() = full
        override val mini get() = miniOn
        override fun toggleFullScreen() { full = !full }
        override fun enterMini() { miniOn = true }
        override fun expand() { miniOn = false }
        override fun dragMini(dx: Int, dy: Int) {}
    }

    fun ui(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    fun run(name: String, episode: Boolean, w: Int, h: Int, setup: (PlayerSession, FakeStage) -> Unit, pointer: Offset? = null, waitMs: Long = 2500, controls: Boolean = false, wantMs: Long? = null, prepare: () -> Unit = {}, trailerAudio: String? = null, drag: LongRange? = null, act: ((ImageComposeScene, PlayerSession, () -> Unit) -> Unit)? = null) {
        if (only != null && name !in only) return
        prepare()
        val target = if (episode) PlayTarget(MediaKind.EPISODE, 45745, "Sintel", 2010, season = 1, episode = 2, episodeTitle = "Test Episode Two")
        else PlayTarget(MediaKind.MOVIE, 45745, "Sintel", 2010)
        val dev = DevPlayback(url)
        lateinit var session: PlayerSession
        lateinit var engine: MpvEngine
        lateinit var thumbs: ScrubThumbnails
        val stage = FakeStage()
        var closed = false
        ui {
            engine = MpvEngine(null)
            val req = kotlinx.coroutines.runBlocking { dev.play(target, 0) }
            session = if (trailerAudio != null) PlayerSession(req, dev, DemoServices(), object : com.dreamos.player.api.PlayerEvents {}, engine, trailer = true, trailerAudio = trailerAudio) { closed = true }
            else PlayerSession(req, dev, DemoServices(), object : com.dreamos.player.api.PlayerEvents {}, engine) { closed = true }
            thumbs = ScrubThumbnails(session)
        }
        // Wait for the first frame (or an error), then let the scenario set itself up.
        val t0 = System.currentTimeMillis()
        while (!session.engineState.firstFrame && session.curtain !is PlayerSession.Curtain.Error && System.currentTimeMillis() - t0 < 30_000) Thread.sleep(100)
        ui { setup(session, stage) }
        // The pointer resting on the timeline keeps asking for the spot under it, as the real overlay does every frame.
        val until = System.currentTimeMillis() + waitMs
        // A drag: the pointer moves 6 s along the timeline every 150 ms (a slow scrub), then rests on its last spot.
        var at = drag?.first
        while (System.currentTimeMillis() < until) {
            if (drag != null && at != null) { thumbs.frameNear(at); if (at < drag.last) at += 6_000; Thread.sleep(150) }
            else { wantMs?.let { thumbs.frameNear(it) }; Thread.sleep(200) }
        }
        lateinit var bytes: ByteArray
        ui {
            if (controls) session.showControls()
            val scene = ImageComposeScene(w, h, Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    NetImage(session.details?.backdropUrl, Modifier.fillMaxSize(), fadeMs = 1)
                    PlayerScreen(session, stage, frameAt = thumbs::frameNear)
                }
            }
            var t = 0L
            repeat(30) { scene.render(t); t += 16_000_000L }
            if (pointer != null) { scene.sendPointerEvent(PointerEventType.Enter, pointer); scene.sendPointerEvent(PointerEventType.Move, pointer) }
            repeat(40) { scene.render(t); t += 16_000_000L }
            act?.let { it(scene, session) { scene.render(t); t += 16_000_000L }; repeat(30) { scene.render(t); t += 16_000_000L } }
            bytes = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
            scene.close()
        }
        // Images arrive asynchronously; render once more after they land.
        Thread.sleep(1200)
        ui {
            if (controls) session.showControls()
            val scene = ImageComposeScene(w, h, Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    NetImage(session.details?.backdropUrl, Modifier.fillMaxSize(), fadeMs = 1)
                    PlayerScreen(session, stage, frameAt = thumbs::frameNear)
                }
            }
            var t = 0L
            if (pointer != null) { scene.sendPointerEvent(PointerEventType.Enter, pointer); scene.sendPointerEvent(PointerEventType.Move, pointer) }
            repeat(30) { scene.render(t); t += 16_000_000L }
            act?.let { it(scene, session) { scene.render(t); t += 16_000_000L } }
            repeat(30) { scene.render(t); t += 16_000_000L }
            bytes = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
            scene.close()
        }
        File(out, "$name.png").writeBytes(bytes)
        PlayerLog.i("Shots: $name ${w}x$h -> ${File(out, "$name.png").absolutePath} (${engine.smoothness()})")
        ui { if (!closed) session.close() }
        thumbs.close()
        engine.destroy()
    }

    run("controls-1280", false, 1280, 720, { s, _ -> s.seekTo(200_000) }, controls = true)
    run("controls-1920", false, 1920, 1080, { s, _ -> s.seekTo(200_000) }, controls = true)
    run("controls-1024", true, 1024, 640, { s, _ -> s.seekTo(200_000) }, controls = true)
    run("scrub-preview", false, 1600, 900, { s, _ -> s.seekTo(120_000) }, pointer = Offset(900f, 744f), waitMs = 14_000, controls = true, wantMs = 504_000)
    // The pointer dragged from 2:00 to 9:00 once the previews run: the log has every drag frame's time.
    run("scrub-drag", false, 1600, 900, { s, _ -> s.seekTo(60_000) }, pointer = Offset(900f, 744f), waitMs = 16_000, controls = true, drag = 120_000L..540_000L)
    run("skip-intro", true, 1600, 900, { s, _ -> s.seekTo(12_000) }, controls = true)
    run("skip-credits", true, 1600, 900, { s, _ -> s.seekTo(760_000) }, waitMs = 3000)
    run("sheet-audio", false, 1600, 900, { s, _ -> s.openSheet(PlayerSession.Sheet.AUDIO_SUBS) })
    // Settings › Subtitles on: the video's own English track is chosen ahead of the downloads (In the Video First).
    run("sheet-audio-subs-on", false, 1600, 900, { s, _ -> s.openSheet(PlayerSession.Sheet.AUDIO_SUBS) }, waitMs = 4000,
        prepare = { com.dreamos.player.session.PlayerSettings.pages().putBoolean("subs.enabled", true) })
    com.dreamos.player.session.PlayerSettings.pages().remove("subs.enabled")
    // Trailer mode: the picture's link and a separate sound link (here the same file twice: mpv lists the second's audio).
    run("trailer", false, 1600, 900, { s, _ -> s.seekTo(30_000); PlayerLog.i("Shots: trailer audio tracks ${s.audioTracks.map { it.title + if (it.external) " (separate)" else "" }}") },
        controls = true, trailerAudio = url)
    run("sheet-info", false, 1600, 900, { s, _ -> s.openSheet(PlayerSession.Sheet.INFO) })
    run("sheet-episodes", true, 1600, 900, { s, _ -> s.openSheet(PlayerSession.Sheet.EPISODES) })
    run("sheet-source", false, 1600, 900, { s, _ -> s.openSheet(PlayerSession.Sheet.SOURCE) })
    run("sheet-playback", false, 1600, 900, { s, _ -> s.openSheet(PlayerSession.Sheet.PLAYBACK) })
    run("xray", false, 1600, 900, { s, _ -> s.openSheet(PlayerSession.Sheet.XRAY) })
    if (only != null && "up-next" in only) System.setProperty("dream.autoplay", "false")
    run("up-next", true, 1600, 900, { s, _ -> s.seekTo(s.engineState.durationMs - 55_000) }, waitMs = 1500)
    run("pause-screen", false, 1600, 900, { s, _ -> s.engine.pause() }, waitMs = 10_500)
    // An episode paused: its pause screen ("S1 · E2  Name", the time left), past the 0.28 paused dim.
    run("paused", true, 1600, 900, { s, _ -> s.seekTo(200_000); s.engine.pause(); s.input() }, waitMs = 1500, controls = true)
    // Skip Intros Automatically: the log says "Skip: intro skipped automatically" (the 1.6 s pill is gone before the render).
    run("intro-skipped", true, 1600, 900, { s, _ -> s.updatePrefs(s.prefs.copy(autoSkipIntros = true)); s.seekTo(8_000) }, waitMs = 1500)
    com.dreamos.player.session.PlayerSettings.pages().putBoolean("play.skipIntros", false)
    // The end before any picture: the damaged-file error with its ways forward.
    run("curtain-damaged", false, 1600, 900, { s, _ -> s.showError(com.dreamos.desktop.core.playback.PlaybackError("This Title Isn't Available Right Now", PlayerSession.DAMAGED_TEXT)) })
    run("mini", false, 480, 270, { s, st -> st.enterMini(); s.showControls() }, pointer = Offset(240f, 135f))
    run("curtain-error", false, 1600, 900, { s, _ -> s.showError(com.dreamos.desktop.core.playback.PlaybackError("Couldn't Find a Ready Stream", "Try again in a moment, or choose another source.")) }, pointer = Offset(118f, 794f))
    // The volume (Windows' speaker and slider, bottom row after Play / Pause) at each size: hovered, dragging,
    // muted, keyboard-focused, and a click on the slider leaving Left / Right to the player.
    for ((w, h) in listOf(1280 to 720, 1920 to 1080, 1024 to 640)) {
        val k = minOf(w / 1600f, h / 900f).coerceIn(0.8f, 1.3f)
        val y = h - 68 * k
        val speaker = Offset(154 * k, y)
        // The slider's track runs 195..289 dp from the left (the narrow layout slides it out to the same spot).
        fun track(f: Float) = Offset((195 + 94 * f) * k, y)
        val tag = "$w"
        fun state(s: PlayerSession, what: String) = PlayerLog.i("Shots: vol-$what-$tag volume ${s.engineState.volume} muted ${s.engineState.muted} saved ${s.prefs.volume} seek bubble ${s.seekBubble}")
        // Frames move the scene's clock on, so the slide-out finishes before the press.
        fun ImageComposeScene.hoverTo(p: Offset, frame: () -> Unit) { sendPointerEvent(PointerEventType.Move, p); repeat(20) { frame() } }
        // Narrow: the slider is out only once the speaker is hovered (or focused).
        fun ImageComposeScene.reach(frame: () -> Unit) { if (w < 1100) hoverTo(speaker, frame) }
        run("vol-hover-$tag", true, w, h, { s, _ -> s.seekTo(200_000); s.setVolume(80) }, pointer = if (w < 1100) speaker else track(0.5f), controls = true,
            act = { sc, s, frame -> if (w < 1100) sc.hoverTo(track(0.5f), frame); state(s, "hover") })
        run("vol-drag-$tag", true, w, h, { s, _ -> s.seekTo(200_000); s.setVolume(30) }, pointer = speaker, controls = true, act = { sc, s, frame ->
            sc.reach(frame)
            sc.sendPointerEvent(PointerEventType.Press, track(0.3f), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            for (f in listOf(0.4f, 0.5f, 0.6f, 0.65f)) { sc.sendPointerEvent(PointerEventType.Move, track(f), buttons = PointerButtons(isPrimaryPressed = true)); frame() }
            Thread.sleep(300)
            state(s, "drag")
        })
        run("vol-muted-$tag", true, w, h, { s, _ -> s.seekTo(200_000); s.setVolume(65); s.toggleMute(hud = false) }, controls = true, act = { _, s, _ -> state(s, "muted") })
        // Tab from the player to the slider (window buttons, timeline, Play / Pause, speaker, slider), then Right: 5% up, no seek.
        run("vol-focus-$tag", true, w, h, { s, _ -> s.seekTo(200_000); s.setVolume(60) }, controls = true, act = { sc, s, frame ->
            repeat(7) { sc.key(Key.Tab); repeat(15) { frame() } }
            sc.key(Key.DirectionRight)
            Thread.sleep(300)
            state(s, "focus")
        })
        // A click on the slider doesn't take the keyboard: Right still seeks 10 s afterwards, and the volume stays.
        run("vol-click-$tag", true, w, h, { s, _ -> s.seekTo(200_000); s.setVolume(50) }, pointer = speaker, controls = true, act = { sc, s, frame ->
            sc.reach(frame)
            sc.sendPointerEvent(PointerEventType.Press, track(0.4f), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            sc.sendPointerEvent(PointerEventType.Release, track(0.4f), buttons = PointerButtons(), button = PointerButton.Primary)
            frame()
            sc.key(Key.DirectionRight)
            Thread.sleep(300)
            state(s, "click")
        })
    }
    kotlin.system.exitProcess(0)
}
