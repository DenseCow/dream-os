package com.dreamos.desktop.trailer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dreamos.desktop.core.trailer.TrailerEngine
import com.dreamos.desktop.core.trailer.TrailerRef
import com.dreamos.desktop.core.util.Log
import com.dreamos.desktop.settings.kit.Dream
import com.dreamos.desktop.settings.kit.DreamType
import com.dreamos.desktop.settings.kit.Glyph
import com.dreamos.desktop.settings.kit.GlyphIcon
import com.dreamos.desktop.settings.kit.ProgressLine
import com.dreamos.desktop.settings.kit.dreamClick
import com.dreamos.desktop.settings.kit.lit
import com.dreamos.desktop.settings.kit.rainbowEdge
import com.dreamos.desktop.settings.kit.rainbowNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The title page's Trailer button when the hero's own trailer is already playing: that very decoder becomes the
 * full-screen picture (owner, 2026-10-31). Nothing is loaded again, so there is no buffering, no black frame and
 * never two mpv instances on one video; the hero simply stops holding the handle ([TrailerBackdropState.promote]).
 *
 * The page keeps one of these and asks it first:
 * ```
 * if (!full.take(trailer, ref)) { trailer.stopNow(); nav.playTrailer(ref, videoKey) }  // nothing playing: as before
 * ...
 * FullTrailer(full, d.name)
 * ```
 */
@Stable
class FullTrailerHost internal constructor(private val scope: CoroutineScope) {
    internal var live by mutableStateOf<FullTrailerState?>(null)
        private set

    /** True while a promoted trailer has the window. */
    val showing: Boolean get() = live != null

    /**
     * Takes the hero's trailer of [title] if it is playing on screen, and returns true: it is the full-screen
     * picture from here on. False means there was nothing to take (Background Trailers off, still resolving, waiting
     * for the page's rest, stopped, or another title), and the caller starts the trailer the way it always did.
     */
    fun take(hero: TrailerBackdropState, title: TrailerRef): Boolean {
        if (live != null) return true
        val handed = hero.promote(title) ?: return false
        live = FullTrailerState(handed, scope) { live = null }
        return true
    }
}

@Composable
fun rememberFullTrailer(): FullTrailerHost {
    val scope = rememberCoroutineScope()
    return remember { FullTrailerHost(scope) }
}

/**
 * A promoted trailer playing full size in the window: the hero's live libmpv with its sound up, the whole picture
 * instead of the hero's cover crop, and the lean controls of a trailer (no history, no sources, no Up Next).
 *
 * Sound comes up even when the hero was muted: full screen is a deliberate watch (owner, 2026-10-31). The viewer's
 * "Background Trailers" choice is left alone, so the next hero trailer behaves as it did.
 *
 * Only one holder of the decoder exists at a time: the page let go of it in `promote`, and [close] gives it back,
 * still playing, so the hero carries on behind the picture the way it carried on into full screen (owner,
 * 2026-10-31). Only a trailer that ended or failed here is let go of, and then by this state alone. [close] runs
 * once however it is reached: Close, Esc, the end, the page leaving.
 */
@Stable
class FullTrailerState internal constructor(
    private val handed: LiveTrailer,
    private val scope: CoroutineScope,
    private val onDone: () -> Unit,
) {
    private val video = handed.video

    var paused by mutableStateOf(false)
        private set
    var soundOn by mutableStateOf(false)
        private set
    /** Where it is and how long it runs, for the line under the picture (0 until mpv knows). */
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    /** The controls are up: they come back on a mouse move or a key and fade after [UI_IDLE_MS] of nothing. */
    var controlsUp by mutableStateOf(true)
        private set

    internal var frameTick by mutableLongStateOf(0L)
    internal val uiAlpha = Animatable(1f)

    private var done = false
    /** It played to its end or failed here: there is nothing live to give back. */
    private var finished = false
    /** The decoder has an audio device (the hero played with sound, or one was opened here). */
    private var audioOpen = !handed.muted
    private var volume = 0
    private var volumeJob: Job? = null
    private var hideJob: Job? = null
    private var watchJob: Job? = null

    init {
        // The frames and the ends are this surface's from now on; the hero ignores anything still on its way.
        video.listen(onFrame = { n -> frameTick = n }, onEvent = { e -> scope.launch { onEvent(e) } })
        video.probeFrames(null)
        video.fit()
        video.playing()
        if (!audioOpen) audioOpen = video.enableSound()
        soundOn = audioOpen
        if (audioOpen) fadeVolume(FULL_VOLUME, VOLUME_IN_MS)
        positionMs = video.positionMs
        durationMs = video.durationMs
        watchJob = scope.launch { watch() }
        rehide()
        Log.i(TAG, "full screen: ${handed.ref.name.ifEmpty { handed.ref.key }} (${handed.videoKey}) " +
            "carried on at $positionMs ms${if (audioOpen) ", sound up" else ", no sound here"}")
    }

    /** The window's size: the picture is drawn at it (capped in [BackdropVideo], then scaled). */
    internal fun sizeChanged(s: IntSize) {
        if (s.width > 0 && s.height > 0) video.size(s.width, s.height)
    }

    internal fun <T> paint(draw: (androidx.compose.ui.graphics.ImageBitmap?) -> T): T = video.withFrame(draw)

    /** Holds the picture, or lets it run on. */
    fun togglePause() {
        if (done) return
        paused = !paused
        video.pause(paused)
        if (paused) show() else rehide()
    }

    /** Full screen with the sound off, and back. */
    fun toggleSound() {
        if (done) return
        val on = !soundOn
        if (on && !audioOpen) audioOpen = video.enableSound()
        soundOn = on && audioOpen
        if (soundOn) fadeVolume(FULL_VOLUME, SOUND_TOGGLE_MS) else fadeVolume(0, SOUND_TOGGLE_MS / 2)
        rehide()
    }

    /** A mouse move, a wheel turn or a key: the controls come back for a while. */
    fun input() {
        if (done) return
        show()
        if (!paused) rehide()
    }

    /**
     * Close, Esc, the end of the trailer, or the page leaving. Where it stopped is kept for the Trailer button, and
     * the decoder goes back to the hero still playing, so the picture carries on behind the page instead of stopping
     * dead (owner, 2026-10-31). It is handed back silent: the hero puts its own level back, which is muted unless the
     * viewer chose sound. Only a trailer that ended or failed is let go of here, and only once; safe to call twice.
     */
    fun close() {
        if (done) return
        done = true
        volumeJob?.cancel(); watchJob?.cancel(); hideJob?.cancel()
        val at = video.positionMs
        TrailerEngine.rememberSpot(handed.ref, handed.videoKey, at)
        val live = !finished
        if (live) video.setVolume(0)
        // The page stops drawing this surface first, so only one of the two paints the picture.
        onDone()
        handed.back(if (live) video else null, audioOpen)
        if (!live) video.destroy()
        Log.i(TAG, "full screen: ${if (live) "handed back to the hero" else "let go of"} at $at ms")
    }

    private fun onEvent(e: BackdropVideo.Event) {
        when (e) {
            BackdropVideo.Event.STARTED -> {}
            // It played to its end: back to the page, as Dream Player's trailer mode closes on the end.
            BackdropVideo.Event.ENDED -> { finished = true; close() }
            BackdropVideo.Event.FAILED -> {
                // It was playing a moment ago: the links are stale rather than the video bad.
                finished = true
                TrailerEngine.forget(handed.ref)
                close()
            }
        }
    }

    /** The line under the picture, a few times a second while it plays. */
    private suspend fun watch() {
        while (scope.isActive && !done) {
            delay(WATCH_MS)
            if (done) return
            positionMs = video.positionMs
            durationMs = video.durationMs
        }
    }

    private fun show() {
        hideJob?.cancel()
        if (controlsUp) return
        controlsUp = true
        scope.launch { uiAlpha.animateTo(1f, tween(fade(UI_IN_MS), easing = EASE)) }
    }

    private fun rehide() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(UI_IDLE_MS)
            if (done || paused) return@launch
            controlsUp = false
            uiAlpha.animateTo(0f, tween(fade(UI_OUT_MS), easing = EASE))
        }
    }

    /** Windows' "Animation effects" off: the controls cut in and out instead of fading (the shell's rule). */
    private fun fade(ms: Int) = if (com.dreamos.desktop.shell.Motion.reduced) 0 else ms

    private fun fadeVolume(to: Int, ms: Int) {
        volumeJob?.cancel()
        val from = volume
        if (from == to) return
        volumeJob = scope.launch {
            val steps = maxOf(1, ms / 60)
            for (i in 1..steps) {
                volume = from + (to - from) * i / steps
                video.setVolume(volume)
                delay(60)
            }
        }
    }

    /** For the checks: the sound level the fade has reached. */
    internal val volumeForChecks: Int get() = volume
    internal val closedForChecks: Boolean get() = done

    companion object {
        private const val TAG = "Trailer"
        /** A deliberate watch plays at full level (the hero's background trailer stays at 80). */
        private const val FULL_VOLUME = 100
        private const val VOLUME_IN_MS = 600
        private const val SOUND_TOGGLE_MS = 900
        private const val WATCH_MS = 400L
        private const val UI_IDLE_MS = 4_000L
        private const val UI_IN_MS = 320
        private const val UI_OUT_MS = 700
        private val EASE = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
    }
}

/**
 * The promoted trailer on screen: the picture over the whole page, the title's name, how far along it is, and Pause,
 * Sound and Close. Nothing is drawn when no trailer was promoted.
 *
 * The mouse brings the controls back and hides itself with them; Esc and Close go back to the page; Space holds the
 * picture; M turns the sound on or off. Leaving the page lets go of the decoder.
 */
@Composable
fun FullTrailer(host: FullTrailerHost, name: String) {
    val state = host.live ?: return
    val focus = remember(state) { FocusRequester() }
    DisposableEffect(state) {
        // Banners and the update prompt wait while a trailer has the window, as they do for Dream Player.
        com.dreamos.desktop.shell.AppServices.playerShowing = true
        onDispose {
            state.close()
            com.dreamos.desktop.shell.AppServices.playerClosed()
        }
    }
    // The surface holds the keys (Esc, Space, M) while the trailer is up. It takes them back when the controls fade
    // away with the button that had them, so Esc never falls through to the page underneath.
    LaunchedEffect(state, state.controlsUp) { if (!state.controlsUp) runCatching { focus.requestFocus() } }
    LaunchedEffect(state) { runCatching { focus.requestFocus() } }
    val hidden = !state.controlsUp
    Box(
        Modifier.fillMaxSize().background(Dream.Black)
            .focusRequester(focus)
            .focusable()
            // Esc goes back to the page before the shell's own Esc can step back from the page itself.
            .onPreviewKeyEvent { k ->
                when {
                    k.type != KeyEventType.KeyDown -> false
                    k.key == Key.Escape -> { state.close(); true }
                    else -> { state.input(); false }
                }
            }
            // After the controls: a focused button keeps Space for itself.
            .onKeyEvent { k ->
                when {
                    k.type != KeyEventType.KeyDown -> false
                    k.key == Key.Spacebar -> { state.togglePause(); true }
                    k.key == Key.M -> { state.toggleSound(); true }
                    else -> false
                }
            }
            // The mouse wakes the controls; anything the controls did not take is swallowed here, so a click or the
            // wheel never reaches the page lying under the picture. A click while the controls are away only wakes them.
            .pointerInput(state) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        when (e.type) {
                            PointerEventType.Move, PointerEventType.Enter -> state.input()
                            PointerEventType.Scroll -> { state.input(); e.changes.forEach { it.consume() } }
                            PointerEventType.Press -> {
                                state.input()
                                if (e.changes.none { it.isConsumed }) e.changes.forEach { it.consume() }
                            }
                            PointerEventType.Release -> if (e.changes.none { it.isConsumed }) e.changes.forEach { it.consume() }
                            else -> {}
                        }
                    }
                }
            }
            .pointerHoverIcon(if (hidden) NoPointer else PointerIcon.Default, overrideDescendants = hidden),
    ) {
        Canvas(Modifier.fillMaxSize().onSizeChanged { state.sizeChanged(it) }) {
            state.frameTick // every new frame redraws this canvas
            val dst = IntSize(size.width.toInt(), size.height.toInt())
            state.paint { img ->
                if (img != null) drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, dst,
                    filterQuality = FilterQuality.Medium)
            }
        }
        // Faded out they leave the page: nothing invisible keeps the pointer's hand or the keyboard's Tab.
        val a = state.uiAlpha.value
        if (state.controlsUp || a > 0.01f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = a }) {
            // The words and the controls sit on their own shade: white over a trailer's own picture is not readable
            // on a bright frame (a scrim, not a guess, as the hero's text has).
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(140.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent))))
            // Deep enough by the words that they still read over a white frame (a trailer does go to white), not
            // only over a dark one: about 0.87 where the caption and the time sit.
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(260.dp)
                .background(Brush.verticalGradient(
                    0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.55f),
                    0.72f to Color.Black.copy(alpha = 0.88f), 1f to Color.Black.copy(alpha = 0.95f),
                )))
            TrailerGlassButton("Close", Glyph.Close, { state.close() }, Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 22.dp))
            Column(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, end = 40.dp, bottom = 34.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.widthIn(max = 720.dp)) {
                        Text(name, style = DreamType.Big, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text("Trailer", style = DreamType.Meta)
                    }
                    Spacer(Modifier.width(24.dp))
                    Box(Modifier.weight(1f))
                    TrailerGlassButton(
                        if (state.paused) "Play" else "Pause",
                        if (state.paused) Glyph.Play else Glyph.Pause,
                        { state.togglePause() },
                    )
                    Spacer(Modifier.width(14.dp))
                    TrailerGlassButton(
                        if (state.soundOn) "Turn Sound Off" else "Turn Sound On",
                        if (state.soundOn) Glyph.Speaker else Glyph.SpeakerOff,
                        { state.toggleSound() },
                    )
                }
                if (state.durationMs > 0) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressLine((state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f), Modifier.weight(1f))
                        Spacer(Modifier.width(14.dp))
                        Text(clock(state.positionMs) + " of " + clock(state.durationMs), style = DreamType.Meta)
                    }
                }
            }
        }
    }
}

/**
 * A round glass button over a picture (the hero's speaker, the full-screen trailer's controls): glass at rest, pearl
 * in the rainbow ring when the mouse is on it or the keyboard holds it.
 */
@Composable
internal fun TrailerGlassButton(label: String, glyph: Glyph, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val src = remember { MutableInteractionSource() }
    val lit = src.lit()
    val glow = rainbowNow(lit.any)
    // Pressed shows in the shape as well as the fill (the kit's buttons do the same): a state that is not colour alone.
    val press by animateFloatAsState(if (lit.pressed) 0.96f else 1f, tween(if (lit.pressed) 90 else 160))
    Box(
        modifier
            .semantics { contentDescription = label }
            .graphicsLayer { scaleX = press; scaleY = press }
            .rainbowEdge(lit.any, glow, 26.dp, edge = if (lit.focused) 3.dp else 2.dp, halo = if (lit.focused) 12.dp else 0.dp)
            .size(52.dp)
            .clip(CircleShape)
            .background(if (lit.any) Dream.Pearl else Color(0x8C141416))
            .dreamClick(src, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (lit.any) Dream.Ink else Dream.Text, 24.dp)
    }
}

/** 0:42, 1:08:30. */
private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val m = s / 60
    return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s % 60) else "%d:%02d".format(m, s % 60)
}
