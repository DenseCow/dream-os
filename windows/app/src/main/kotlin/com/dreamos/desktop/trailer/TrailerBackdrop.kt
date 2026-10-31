package com.dreamos.desktop.trailer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.trailer.Trailer
import com.dreamos.desktop.core.trailer.TrailerEngine
import com.dreamos.desktop.core.trailer.TrailerRef
import com.dreamos.desktop.core.trailer.TrailerSource
import com.dreamos.desktop.core.util.Log
import com.dreamos.player.DreamPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The "Background Trailers" setting, per profile (Settings › Appearance; the pages' `look.trailers` key). */
enum class BackgroundTrailers(val label: String) {
    SOUND("On with Sound"), MUTED("On, Muted"), OFF("Off");

    companion object {
        /** Settings and setup default to On, Muted on the PC (the pages' choice; the Fire Stick defaults to sound). */
        const val DEFAULT = "On, Muted"
        fun current(): BackgroundTrailers {
            val v = runCatching { DreamStore.shared.profileFile("pages").string("look.trailers", DEFAULT) }.getOrDefault(DEFAULT)
            return entries.firstOrNull { it.label == v } ?: MUTED
        }
    }
}

/**
 * A title's trailer playing behind the title page's hero, where its backdrop picture is (fire-home HeroTrailer, the
 * Fire Stick's lean-back flow, made for a mouse). Title pages only, never Home or Browse heroes.
 *
 * - Starts after the page rests for [REST_MS] (1 s, the iPhone's): hero on screen, nothing over it, Dream OS's window
 *   in front, Dream Player closed, no pointer movement, scroll or key. The lookup starts before the page opens (a card
 *   rested on, Home's hero) or the moment it does; during the rest the trailer is opened hidden and muted and runs to
 *   its first real picture, then waits there paused, so it dissolves in the moment the rest ends. Leaving, Play or
 *   anything that rules the rest out lets go of it at once.
 * - Takes the screen: [immersive] turns true and the page fades its UI ([trailerUi]) and hides the pointer. Moving the
 *   mouse or pressing a key brings the UI back over the still playing trailer (that first click or key only wakes the
 *   page); 4 s without input and it fades away again.
 * - The picture dissolves in over the backdrop once real picture is up (trailers often open on black; letterbox bars
 *   are zoomed away), and settles back into the backdrop at the end, when the page scrolls or anything covers it. No
 *   loop: after an end the next one waits until the viewer moves and the page rests again.
 * - Sound per profile ([BackgroundTrailers]): On with Sound fades up to 80% over 1.8 s; On, Muted never opens an
 *   audio device.
 * - [stopNow] (Play, Trailer, an episode, the page leaving) stops the sound and lets go of the decoder at once.
 */
@Stable
class TrailerBackdropState internal constructor(private val scope: CoroutineScope) {
    // ---- what the page says ----
    private var ref: TrailerRef? = null
    private var resting = false
    private var visible = false
    private var focused = true
    private var released = false
    /** A trailer ended (or failed) by itself: the next one waits for the viewer to move. */
    private var waitForUser = false

    /**
     * IDLE: nothing. ARMED: the rest is running; the trailer is looked up, opened hidden and run to its first real
     * picture, where it waits paused ([prerolled]). STARTING: the rest is over and the picture isn't up yet (a cold
     * title). PLAYING: on screen. STOPPING: settling back into the backdrop.
     */
    private enum class Phase { IDLE, ARMED, STARTING, PLAYING, STOPPING }
    private var phase = Phase.IDLE
    /** Bumped on every arm, open and stop, so a late lookup or frame can't act for an older one. */
    private var gen = 0
    private var restJob: Job? = null
    private var prepJob: Job? = null
    /** The rest has run its full [REST_MS] since the last input. */
    private var restDone = false
    /** The hidden trailer waits paused at its first real picture (letterbox zoom applied): it can dissolve in at once. */
    private var prerolled = false
    private var watchJob: Job? = null
    private var hideJob: Job? = null
    private var volumeJob: Job? = null

    private var video: BackdropVideo? = null
    private var trailer: Trailer? = null
    private var sources: List<TrailerSource> = emptyList()
    private var sourceAt = 0
    private var mode = BackgroundTrailers.MUTED
    private var startedAt = 0L
    @Volatile private var firstFrameAt = 0L
    /** The picture is on screen (from the reveal to the teardown): new frames repaint the canvas. */
    @Volatile private var shown = false
    private var sizePx = IntSize.Zero

    // ---- what the page reads ----

    /** True while the trailer has the screen: fade the page's UI out ([trailerUi] does it). */
    var immersive by mutableStateOf(false)
        private set

    /** True from the moment real picture shows until it has settled back into the backdrop. */
    var playing by mutableStateOf(false)
        private set

    /** The trailer's sound is up (the hero's speaker button shows it). */
    var soundOn by mutableStateOf(false)
        private set

    /**
     * The hero's speaker button (and the M key): fades the sound up to the "On with Sound" level, or back to muted.
     * The choice lasts for the app session: later trailers start the same way. A trailer opened muted gets its audio
     * device only now.
     */
    fun toggleSound() {
        val on = !soundOn
        TrailerSession.sound = on
        soundOn = on
        if (phase != Phase.PLAYING) return
        if (on) {
            val v = video ?: return
            if (!audioOpen) audioOpen = v.enableSound()
            fadeVolume(VOLUME, SOUND_TOGGLE_MS)
        } else fadeVolume(0, SOUND_TOGGLE_MS / 2)
        rehide()
    }

    /** The decoder was opened with an audio device (sound wanted at start, or turned on since). */
    private var audioOpen = false
    private fun wantsSound() = TrailerSession.sound ?: (mode == BackgroundTrailers.SOUND)

    internal var frameTick by mutableLongStateOf(0L)
    internal val videoAlpha = Animatable(0f)
    internal val uiAlpha = Animatable(1f)

    // ---- public API ----

    /**
     * What the hero shows: [ref] the title (null: none), [resting] its hero is on screen (the page near its top),
     * [visible] nothing covers the page (a menu, Choose a Source, a dialog). Call on every change; cheap.
     */
    fun update(ref: TrailerRef?, resting: Boolean, visible: Boolean = true) {
        if (released) return
        val changed = ref?.key != this.ref?.key
        this.ref = ref
        this.resting = resting
        this.visible = visible
        if (changed) {
            waitForUser = false
            if (phase != Phase.IDLE) { stop(Stop.SOFT); disarm() }
            if (ref != null && BackgroundTrailers.current() != BackgroundTrailers.OFF) TrailerEngine.prefetch(ref)
        }
        evaluate()
    }

    /** Something is about to play or open (the click is on its way): sound off and the decoder released right now. */
    fun stopNow() {
        if (phase != Phase.IDLE) { stop(Stop.NOW); disarm() }
        waitForUser = true
    }

    /**
     * The viewer moved the mouse, scrolled or pressed a key ([press]: a click or key). Returns true when that event
     * should go no further: a click or key while the trailer has the screen only brings the page back.
     */
    fun input(press: Boolean): Boolean {
        if (released) return false
        if (phase == Phase.PLAYING) {
            val consumed = press && immersive
            if (immersive) immerse(false)
            rehide()
            return consumed
        }
        waitForUser = false
        when (phase) {
            // The rest starts over; what's looked up and opened stays (the hidden picture waits paused when it's ready).
            Phase.ARMED -> restAgain()
            Phase.STARTING -> { phase = Phase.ARMED; restAgain() }
            else -> evaluate()
        }
        return false
    }

    // ---- when ----

    internal fun windowFocus(has: Boolean) {
        focused = has
        if (!has) { if (phase != Phase.IDLE) stop(Stop.NOW); disarm() } else evaluate()
    }

    internal fun sizeChanged(s: IntSize) {
        sizePx = s
        if (s.height > 0) TrailerEngine.heroClass = maxClass()
        video?.size(s.width, s.height)
        evaluate()
    }

    internal fun release() {
        if (released) return
        stop(Stop.NOW)
        disarm()
        released = true
        hideJob?.cancel()
    }

    private fun wanted() = ref != null && resting && visible && !waitForUser &&
        BackgroundTrailers.current() != BackgroundTrailers.OFF && BackdropVideo.available

    /** Dream OS in front and focused, the hero laid out, Dream Player not up. */
    private fun canPlay() = !released && focused && sizePx.width > 0 && !DreamPlayer.isOpen

    private fun evaluate() {
        if (released) return
        if (!wanted()) {
            if (phase == Phase.ARMED) disarm()
            else if (phase == Phase.STARTING || phase == Phase.PLAYING) stop(Stop.SOFT)
            return
        }
        if (phase == Phase.IDLE && canPlay()) arm()
    }

    /** The rest begins, and with it the lookup (usually done already) and the hidden start. */
    private fun arm() {
        val r = ref ?: return
        phase = Phase.ARMED
        val g = ++gen
        restAgain()
        prepJob?.cancel()
        val ready = TrailerEngine.cached(r)
        Log.i(TAG, "hero: resting on ${r.name.ifEmpty { r.key }}${if (ready != null) " (trailer ready)" else ""}")
        // Readied ahead (the usual case): opened hidden right now, not after a trip through the UI thread's queue.
        if (ready != null) { start(r, ready); return }
        prepJob = scope.launch {
            val t = TrailerEngine.resolve(r)
            if (g != gen || phase != Phase.ARMED || ref?.key != r.key) return@launch
            when {
                t == null -> { disarm(); waitForUser = true }
                !wanted() || !canPlay() -> disarm()
                else -> start(r, t)
            }
        }
    }

    /** (Re)starts the [REST_MS] rest. */
    private fun restAgain() {
        restDone = false
        restJob?.cancel()
        // Undispatched: the rest is counted from now, not from when the UI thread's queue gets to it.
        restJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            delay(REST_MS)
            restDone = true
            restOver()
        }
    }

    /** The page rested long enough: the trailer shows now if it's ready, else as soon as its picture is up. */
    private fun restOver() {
        if (phase != Phase.ARMED || !restDone) return
        if (!wanted() || !canPlay()) { disarm(); return }
        val v = video ?: return // still looking up or opening: open() sees restDone
        phase = Phase.STARTING
        if (prerolled) {
            v.pause(false)
            reveal(gen)
        }
    }

    /** Back to IDLE from ARMED, letting go of a hidden trailer. STARTING / PLAYING go through [stop]. */
    private fun disarm() {
        restJob?.cancel(); restJob = null
        prepJob?.cancel(); prepJob = null
        restDone = false
        if (phase == Phase.ARMED) {
            if (video != null) teardown() else { phase = Phase.IDLE; gen++ }
        }
    }

    // ---- playing ----

    /** The hero's size picks the stream: 1080p up to a 1080-line hero, 1440p above (the picture is drawn at the hero's size). */
    private fun maxClass() = if (sizePx.height > 1100) 1440 else 1080

    private fun start(r: TrailerRef, t: Trailer) {
        trailer = t
        sources = t.sources(maxClass())
        sourceAt = 0
        mode = BackgroundTrailers.current()
        if (sources.isEmpty()) { disarm(); waitForUser = true; return }
        open(r, t)
    }

    private fun open(r: TrailerRef, t: Trailer) {
        val src = sources[sourceAt]
        val g = ++gen
        val v = try {
            BackdropVideo(
                muted = !wantsSound(),
                // Each frame only bumps the tick the canvas reads (any thread may write snapshot state), and only once
                // the picture shows: frames run hidden during the rest repaint nothing. The first one also starts the
                // picture checks on the UI thread.
                onFrame = { n -> if (shown) frameTick = n; if (firstFrameAt == 0L) scope.launch { firstFrame(g) } },
                onEvent = { e -> scope.launch { onVideoEvent(g, e) } },
            )
        } catch (e: Throwable) {
            Log.w(TAG, "the hero can't play trailers here: ${e.javaClass.simpleName}: ${e.message}")
            waitForUser = true
            return
        }
        video = v
        audioOpen = wantsSound()
        soundOn = wantsSound()
        v.size(sizePx.width, sizePx.height)
        v.load(src.url)
        if (phase == Phase.ARMED && restDone) phase = Phase.STARTING
        prerolled = false
        startedAt = System.currentTimeMillis()
        firstFrameAt = 0L
        Log.i(TAG, "hero: ${if (phase == Phase.ARMED) "opening hidden" else "starting"} ${r.name.ifEmpty { r.key }} (${t.videoKey}) " +
            "${src.label} at ${sizePx.width}x${sizePx.height}, ${mode.label}")
        watchJob?.cancel()
        watchJob = scope.launch { watch(g) }
    }

    private fun firstFrame(g: Int) {
        if (g != gen || !(phase == Phase.ARMED || phase == Phase.STARTING) || firstFrameAt != 0L) return
        firstFrameAt = System.currentTimeMillis()
        Log.i(TAG, "hero: first frame after ${firstFrameAt - startedAt} ms")
        probe(g)
    }

    private fun onVideoEvent(g: Int, e: BackdropVideo.Event) {
        if (g != gen) return
        when (e) {
            BackdropVideo.Event.STARTED -> {}
            BackdropVideo.Event.ENDED -> end()
            BackdropVideo.Event.FAILED -> failed("could not open")
        }
    }

    /** A source failed before picture: the next one (HLS, then the MP4); after the last, that video is marked failed. */
    private fun failed(why: String) {
        val r = ref ?: return
        val t = trailer ?: return
        val before = phase
        val early = before == Phase.ARMED || before == Phase.STARTING
        if (early && sourceAt + 1 < sources.size) {
            Log.w(TAG, "hero: ${sources[sourceAt].label} $why; trying ${sources[sourceAt + 1].label}")
            teardown()
            sourceAt++
            phase = before
            open(r, t)
            return
        }
        if (early) TrailerEngine.failed(r, t.videoKey, why) else TrailerEngine.forget(r)
        if (before == Phase.ARMED) disarm() else stop(if (before == Phase.PLAYING) Stop.SOFT else Stop.NOW)
        waitForUser = true
    }

    /** Waits for real picture (trailers often open on black), zooms away baked-in letterbox bars, then reveals. */
    private fun probe(g: Int) {
        val v = video ?: return
        var last = 0f
        v.probeFrames { s ->
            val z = analyze(s) ?: return@probeFrames
            // Two frames in a row must agree before the picture is zoomed (a dark scene isn't a letterbox).
            if (z > 1.01f && abs(z - last) > 0.03f) { last = z; return@probeFrames }
            v.probeFrames(null)
            scope.launch {
                if (g != gen || !(phase == Phase.ARMED || phase == Phase.STARTING)) return@launch
                if (z > 1.01f) { v.setZoom(z); delay(160) }
                pictureUp(g)
            }
        }
    }

    /** Real picture is up: on screen now when the rest is over, else it waits there paused for the rest to end. */
    private fun pictureUp(g: Int) {
        if (g != gen) return
        val v = video ?: return
        v.probeFrames(null)
        when (phase) {
            Phase.STARTING -> reveal(g)
            Phase.ARMED -> if (!prerolled) {
                v.pause(true)
                prerolled = true
                Log.i(TAG, "hero: ready ${System.currentTimeMillis() - startedAt} ms after opening, waiting for the rest")
            }
            else -> {}
        }
    }

    /**
     * Null while the frame is (nearly) black; else the zoom that removes symmetric letterbox or pillarbox bars. A grid
     * of [PROBE_W] x [PROBE_H] samples (fine rows: a 1.90:1 trailer leaves bars of only 3% in a 16:9 hero).
     */
    private fun analyze(s: BackdropVideo.Sampler): Float? {
        val w = PROBE_W; val h = PROBE_H
        val rowMax = IntArray(h)
        val colMax = IntArray(w)
        var sum = 0L
        for (y in 0 until h) for (x in 0 until w) {
            val l = s.luma(x * (s.w - 1) / (w - 1), y * (s.h - 1) / (h - 1))
            sum += l
            if (x in 2 until w - 2 && l > rowMax[y]) rowMax[y] = l
            if (y in 4 until h - 4 && l > colMax[x]) colMax[x] = l
        }
        if ((rowMax.maxOrNull() ?: 0) < 60 || sum / (w * h) < 12) return null
        var z = 1f
        val top = rowMax.indexOfFirst { it >= BAR_LUMA }
        val bottom = h - 1 - rowMax.indexOfLast { it >= BAR_LUMA }
        // Bars of at least 2% each side, within 1.5% of each other.
        if (top >= h / 50 && bottom >= h / 50 && abs(top - bottom) <= maxOf(2, h * 3 / 200)) {
            // Only real cinema shapes (1.85:1 to 2.4:1 inside what's drawn).
            val aspect = (s.w.toFloat() / s.h) * h / (h - top - bottom).toFloat()
            if (aspect in 1.82f..2.45f) z = maxOf(z, h / (h - top - bottom).toFloat())
        }
        val left = colMax.indexOfFirst { it >= BAR_LUMA }
        val right = w - 1 - colMax.indexOfLast { it >= BAR_LUMA }
        if (left >= w / 16 && right >= w / 16 && abs(left - right) <= maxOf(2, w / 50)) z = maxOf(z, w / (w - left - right).toFloat())
        return z.coerceAtMost(MAX_ZOOM)
    }

    private fun reveal(g: Int) {
        if (g != gen || phase != Phase.STARTING) return
        video?.probeFrames(null)
        video?.playing()
        shown = true
        frameTick++
        phase = Phase.PLAYING
        playing = true
        Log.i(TAG, "hero: picture up")
        scope.launch { videoAlpha.animateTo(1f, tween(REVEAL_MS, easing = EASE)) }
        if (wantsSound()) fadeVolume(VOLUME, VOLUME_IN_MS)
        immerse(true)
    }

    private fun immerse(on: Boolean) {
        hideJob?.cancel()
        if (immersive == on) return
        immersive = on
        scope.launch { uiAlpha.animateTo(if (on) 0f else 1f, tween(if (on) UI_OUT_MS else UI_IN_MS, easing = EASE)) }
    }

    private fun rehide() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(UI_IDLE_MS)
            if (phase == Phase.PLAYING && wanted() && canPlay()) immerse(true)
        }
    }

    private fun end() {
        if (phase == Phase.PLAYING || phase == Phase.STARTING) {
            stop(Stop.END)
            waitForUser = true
        }
    }

    /** Once a second while a trailer is up: the screen must still be ours, and the end fades out early. */
    private suspend fun watch(g: Int) {
        while (scope.isActive && g == gen) {
            delay(WATCH_MS)
            if (g != gen) return
            val v = video ?: return
            when {
                !canPlay() -> { stop(if (focused) Stop.SOFT else Stop.NOW); disarm(); return }
                (phase == Phase.STARTING || phase == Phase.ARMED) && !prerolled && firstFrameAt != 0L &&
                    System.currentTimeMillis() - firstFrameAt > PROBE_MAX_MS -> pictureUp(g)
                (phase == Phase.STARTING || phase == Phase.ARMED) && firstFrameAt == 0L &&
                    System.currentTimeMillis() - startedAt > START_TIMEOUT_MS -> {
                    Log.w(TAG, "hero: no picture after ${START_TIMEOUT_MS / 1000} s")
                    failed("no picture in time")
                    return
                }
                phase == Phase.PLAYING -> {
                    val dur = v.durationMs
                    if (dur > 0 && dur - v.positionMs < END_EARLY_MS) { end(); return }
                }
            }
        }
    }

    private enum class Stop { SOFT, END, NOW }

    /**
     * SOFT / END: the picture settles back into the backdrop (END slower) as the sound dips, then everything is let
     * go. NOW: let go this instant; the backdrop under it is the same picture, so nothing flashes.
     */
    private fun stop(kind: Stop) {
        watchJob?.cancel()
        hideJob?.cancel()
        if (phase != Phase.STARTING && phase != Phase.PLAYING && phase != Phase.STOPPING) return
        val v = video
        // The spot, for the Trailer button (it continues the same trailer from here).
        if (phase == Phase.PLAYING && v != null) ref?.let { r -> trailer?.let { TrailerEngine.rememberSpot(r, it.videoKey, v.positionMs) } }
        immerse(false)
        if (kind == Stop.NOW || phase == Phase.STARTING || v == null) { teardown(); return }
        if (phase == Phase.STOPPING) return
        phase = Phase.STOPPING
        val g = ++gen
        val ms = if (kind == Stop.END) END_FADE_MS else STOP_FADE_MS
        fadeVolume(0, (ms * 0.8f).toInt())
        scope.launch {
            videoAlpha.animateTo(0f, tween(ms, easing = SETTLE))
            if (g == gen && phase == Phase.STOPPING) { teardown(); evaluate() }
        }
    }

    private fun teardown() {
        volumeJob?.cancel()
        volume = 0
        audioOpen = false
        video?.destroy()
        video = null
        prerolled = false
        shown = false
        phase = Phase.IDLE
        gen++
        playing = false
        immersive = false
        scope.launch { videoAlpha.snapTo(0f); uiAlpha.animateTo(1f, tween(UI_IN_MS, easing = EASE)) }
    }

    // ---- sound ----

    private var volume = 0

    private fun fadeVolume(to: Int, ms: Int) {
        val v = video ?: return
        volumeJob?.cancel()
        val from = volume
        if (from == to) return
        volumeJob = scope.launch {
            val steps = maxOf(1, ms / 60)
            for (i in 1..steps) {
                volume = from + (to - from) * i / steps
                v.setVolume(volume)
                delay(60)
            }
        }
    }

    internal fun <T> paint(draw: (androidx.compose.ui.graphics.ImageBitmap?) -> T): T? = video?.withFrame(draw)

    companion object {
        private const val SOUND_TOGGLE_MS = 900
        private const val TAG = "Trailer"
        /** How long the page must rest before the trailer shows (the iPhone's 1 s; the Fire Stick waits 4 s). */
        const val REST_MS = 1_000L
        private const val UI_IDLE_MS = 4_000L
        private const val WATCH_MS = 1_000L
        private const val START_TIMEOUT_MS = 15_000L
        private const val END_EARLY_MS = 1_200L
        private const val REVEAL_MS = 900
        private const val STOP_FADE_MS = 320
        private const val END_FADE_MS = 900
        private const val UI_OUT_MS = 700
        private const val UI_IN_MS = 320
        private const val VOLUME_IN_MS = 1_800
        /** A touch below full: a trailer starts on its own, it shouldn't jolt the room. */
        private const val VOLUME = 80
        private const val PROBE_W = 128
        private const val PROBE_H = 144
        private const val PROBE_MAX_MS = 3_500L
        private const val BAR_LUMA = 28
        private const val MAX_ZOOM = 1.45f
        private val EASE = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
        private val SETTLE = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }
}

@Composable
fun rememberTrailerBackdropState(): TrailerBackdropState {
    val scope = rememberCoroutineScope()
    return remember { TrailerBackdropState(scope) }
}

/**
 * The trailer's picture. Put it right over the hero's backdrop image and under its scrims; same size as the image.
 * Draws nothing until real picture is up, then dissolves in; leaving the composition lets go of everything at once.
 */
@Composable
fun TrailerBackdrop(state: TrailerBackdropState, modifier: Modifier = Modifier) {
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(state, focused) { state.windowFocus(focused) }
    DisposableEffect(state) { onDispose { state.release() } }
    Canvas(modifier.onSizeChanged { state.sizeChanged(it) }) {
        state.frameTick // every new frame redraws (only this canvas)
        val a = state.videoAlpha.value
        if (a <= 0f) return@Canvas
        val dst = IntSize(size.width.toInt(), size.height.toInt())
        state.paint { img ->
            if (img != null) drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, dst, alpha = a,
                filterQuality = FilterQuality.Medium)
        }
    }
}

/**
 * For the title page's root: reports the mouse, the wheel and keys to [state] (they reveal the page over a playing
 * trailer and restart the rest), swallows the first click or key while the trailer has the screen, and hides the
 * pointer meanwhile.
 */
fun Modifier.trailerWatch(state: TrailerBackdropState): Modifier = this
    .pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                when (e.type) {
                    PointerEventType.Move, PointerEventType.Scroll, PointerEventType.Enter -> state.input(press = false)
                    PointerEventType.Press -> if (state.input(press = true)) e.changes.forEach { it.consume() }
                    else -> {}
                }
            }
        }
    }
    .onPreviewKeyEvent {
        when {
            it.type != KeyEventType.KeyDown -> false
            // M turns the trailer's sound on or off while it plays (and brings the page back, like any key).
            it.key == androidx.compose.ui.input.key.Key.M && state.playing -> { state.input(press = false); state.toggleSound(); true }
            else -> state.input(press = true)
        }
    }
    .pointerHoverIcon(if (state.immersive) NoPointer else PointerIcon.Default, overrideDescendants = state.immersive)

/** For the page's UI over the hero (scrims, title, buttons, the top bar): fades away while the trailer has the screen. */
fun Modifier.trailerUi(state: TrailerBackdropState): Modifier = graphicsLayer { alpha = state.uiAlpha.value }

private val NoPointer: PointerIcon by lazy {
    // No custom cursors without a display (offscreen checks run headless).
    if (java.awt.GraphicsEnvironment.isHeadless()) return@lazy PointerIcon.Default
    val img = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    PointerIcon(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(img, java.awt.Point(0, 0), "none"))
}

/** For the app session: the viewer's last sound choice on a hero trailer (null: Settings' Background Trailers decides). */
internal object TrailerSession {
    @Volatile var sound: Boolean? = null
}
