package com.firehome.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * A title's trailer playing silently-or-softly behind a hero, where its backdrop was (Netflix / Hulu style).
 *
 * Drop-in: [attach] it to a full-bleed [FrameLayout] that sits right above the backdrop image and under
 * the scrims and the info block, and report [setState] whenever the title, the hero focus or the page's
 * visibility changes. While it plays it takes the whole screen: [onImmersive] tells the host to fade its
 * UI out (and back). A key while it's full screen goes to [wake] (the UI fades back in over the still
 * playing trailer, and the host consumes that key); after [UI_IDLE_MS] without a key it goes full screen
 * again. Focus leaving the hero (e.g. onto the synopsis) stops it.
 *
 * - Starts [REST_MS] (1 s) after focus rests on the hero (any focus move off it restarts the wait), only with
 *   Dream OS in front, its window focused, nothing over it ([blocked]) and enough free memory. The lookup
 *   starts after half a second; a title not looked up yet starts as soon as its streams are found.
 * - One ExoPlayer on a SurfaceView (composited by the display hardware, so the weak GPU never redraws
 *   the UI per video frame). A copy of the backdrop covers it until real picture is on screen, then
 *   dissolves away; at the end (or the moment anything changes) the still fades back over it and the
 *   player and its decoder are released at once. No loops.
 * - 1080p AVC (VP9 when there's no AVC) + AAC, 720p/480p kept as fallbacks for slow networks; 720p max
 *   when memory is tight. Letterboxed trailers are zoomed to fill like the backdrop.
 * - Sound per profile, from the "Background Trailers" setting (On with Sound / On, Muted / Off).
 * - After a trailer ends the next one waits until the user moves and rests again.
 */
class HeroTrailer private constructor(
    private val slot: FrameLayout,
    private val kit: PremiumKit,
    /** The bitmap the backdrop under [slot] shows (center-cropped), or null while it hasn't loaded. */
    private val backdrop: () -> Bitmap?,
) {
    private val ctx = slot.context
    private val act: Activity? = activityOf(ctx)
    /** Stopped by a real pause of its screen (Play, another app): once back, resting counts again. */
    private var restartOnResume = false
    private val main = Handler(Looper.getMainLooper())
    private val Int.dp get() = (this * ctx.resources.displayMetrics.density).toInt()

    private var title: Title? = null
    private var resting = false
    private var visible = false
    private var released = false
    /** A trailer ended (or failed) by itself: the next one waits for the user to move. */
    private var waitForUser = false

    /** ARMED: the [REST_MS] wait runs; RESOLVING: waiting for the stream; STARTING: the still covers the first frames. */
    private enum class Phase { IDLE, ARMED, RESOLVING, STARTING, PLAYING, STOPPING }
    private var phase = Phase.IDLE
    /** Bumped on every arm/disarm, so a late stream lookup can't start a trailer for an older wait. */
    private var gen = 0
    /** Runs once the player is gone (a page waiting to fade out). */
    private var onStopped: (() -> Unit)? = null

    private var player: ExoPlayer? = null
    private var surface: SurfaceView? = null
    private var cover: ImageView? = null
    private var volumeAnim: ValueAnimator? = null
    private var startedAt = 0L
    /** When the last rest began (timing log: rest start to the picture on screen). */
    private var armedAt = 0L
    private var firstFrameAt = 0L
    private var videoW = 0
    private var videoH = 0
    private var pixelRatio = 1f
    private var zoom = 1f
    private var lastZoom = 0f
    private var probes = 0
    private val probeBmp: Bitmap by lazy { Bitmap.createBitmap(PROBE_W, PROBE_H, Bitmap.Config.ARGB_8888) }

    /** The video now playing (for [handoff]). */
    private var playingKey: String? = null
    /** Where the last trailer was when it stopped: video, spot, when. */
    private var lastKey: String? = null
    private var lastTitle: String? = null
    private var lastPos = 0L
    private var lastAt = 0L

    // ---- public API ----

    /**
     * The trailer takes the screen ([immersive] true: fade the page's UI and scrims out) or gives it back
     * (false: fade them in; [animate] false means at once, e.g. the screen is going away).
     */
    var onImmersive: ((immersive: Boolean, animate: Boolean) -> Unit)? = null

    /** True while the trailer has the screen: the host consumes the next key and calls [wake]. */
    var immersive = false
        private set

    /**
     * A key while the trailer has the screen: it keeps playing and the page's UI fades back in over it (the
     * host consumes that key). After [UI_IDLE_MS] with no key the UI fades away again.
     */
    fun wake() {
        if (!immersive) return
        setImmersive(false, animate = true)
        rehide()
    }

    /** A key while the UI shows over a playing trailer: the UI stays up for another [UI_IDLE_MS]. */
    fun userActive() {
        if (phase == Phase.PLAYING && !immersive) rehide()
    }

    private val hideAgain = Runnable {
        if (phase == Phase.PLAYING && !immersive && wanted() && canPlay()) setImmersive(true, animate = true)
    }

    private fun rehide() {
        main.removeCallbacks(hideAgain)
        main.postDelayed(hideAgain, UI_IDLE_MS)
    }

    /**
     * The trailer that just played on this page and where it stopped (video key, ms), for opening the same
     * trailer full screen from that spot; null when none played in the last few minutes.
     */
    fun handoff(): Pair<String, Long>? {
        val k = lastKey ?: return null
        if (lastTitle != title?.key || SystemClock.elapsedRealtime() - lastAt > HANDOFF_MS || lastPos <= 0) return null
        return k to lastPos
    }

    /**
     * Optional: decides from the newly focused view whether focus rests on the hero (e.g. inside its button
     * row). When set, every focus move updates the resting state by itself, so the host only reports titles.
     */
    var heroFocus: ((View?) -> Boolean)? = null

    /**
     * What the hero shows: the featured [title], whether focus [resting] on its buttons, and whether the
     * page is [visible] (on screen, nothing over it). Any change stops a playing trailer; the wait restarts.
     */
    fun setState(title: Title?, resting: Boolean, visible: Boolean = true) {
        if (released) return
        val changed = title?.key != this.title?.key
        this.title = title
        this.resting = resting
        this.visible = visible
        if (changed) {
            waitForUser = false
            evaluateRetries = 0
            if (phase != Phase.IDLE) { stop(Stop.SOFT); disarm() }
        }
        evaluate()
    }

    /** Something is about to play or open (the click is on its way): release the decoder right now. */
    fun stopNow() {
        if (phase != Phase.IDLE) { stop(Stop.NOW); disarm() }
        waitForUser = true
    }

    /** For a page fading out: the still settles back over the video first ([done] runs when it has, at once if idle). */
    fun stopForExit(done: () -> Unit) {
        if (phase != Phase.PLAYING) { stop(Stop.NOW); disarm(); done(); return }
        stop(Stop.EXIT) { done() }
        disarm()
    }

    /** For good: player, views and listeners. */
    fun release() {
        if (released) return
        stop(Stop.NOW)
        disarm()
        released = true
        live -= this
        main.removeCallbacksAndMessages(null)
        onImmersive = null
        slot.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        slot.viewTreeObserver.removeOnWindowFocusChangeListener(windowListener)
    }

    // ---- when ----

    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, now ->
        heroFocus?.let { resting = it(now) }
        userMoved()
    }
    private val windowListener = ViewTreeObserver.OnWindowFocusChangeListener { has ->
        if (!has) { stop(Stop.NOW); disarm() } else { act?.let { front = WeakReference(it) }; evaluate() }
    }

    /** Focus moved (any key that did something): the idle wait starts over. */
    private fun userMoved() {
        if (released) return
        waitForUser = false
        if (phase == Phase.ARMED || phase == Phase.RESOLVING) disarm()
        evaluate()
    }

    private fun wanted() = title != null && resting && visible && !waitForUser && mode(ctx) != OFF

    private fun evaluate() {
        if (released) return
        main.removeCallbacks(evaluateAgain)
        if (!wanted()) {
            if (phase == Phase.ARMED || phase == Phase.RESOLVING) disarm()
            else if (phase == Phase.STARTING || phase == Phase.PLAYING) stop(Stop.SOFT)
            return
        }
        if (phase != Phase.IDLE) return
        if (canPlay()) { evaluateRetries = 0; arm(); return }
        // Wanted but not possible just now (back from Dream Player, which counts as up a moment longer; the
        // window not focused yet): nothing else will ask again, so look again shortly, a few times.
        if (evaluateRetries < 5) { evaluateRetries++; main.postDelayed(evaluateAgain, 1_000) }
    }

    private var evaluateRetries = 0
    private val evaluateAgain = Runnable { evaluate() }

    private val prefetch = Runnable { title?.let { t -> TrailerEngine.resolve(ctx, t) {} } }
    private val go = Runnable { begin() }

    init {
        live += this
        track(ctx)
        // Window listeners follow the slot's window (a detached view's observer would be the wrong one).
        slot.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = listen(v)
            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
                v.viewTreeObserver.removeOnWindowFocusChangeListener(windowListener)
                stop(Stop.NOW)
                disarm()
            }
        })
        if (slot.isAttachedToWindow) listen(slot)
    }

    private fun listen(v: View) {
        v.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        v.viewTreeObserver.addOnWindowFocusChangeListener(windowListener)
        evaluate()
    }

    private fun arm() {
        phase = Phase.ARMED
        gen++
        armedAt = SystemClock.elapsedRealtime()
        main.removeCallbacks(prefetch)
        main.removeCallbacks(go)
        // Resolve early (a second or three of network) so the trailer can start right on time.
        main.postDelayed(prefetch, PREFETCH_MS)
        main.postDelayed(go, REST_MS)
    }

    private fun disarm() {
        main.removeCallbacks(prefetch)
        main.removeCallbacks(go)
        if (phase == Phase.ARMED || phase == Phase.RESOLVING) { phase = Phase.IDLE; gen++ }
    }

    /** Dream OS in front and focused, this page on screen, nothing over it, Dream Player not up. */
    private fun canPlay(): Boolean = !released && act != null && front?.get() === act && slot.isAttachedToWindow &&
        slot.isShown && slot.hasWindowFocus() && !DreamPlayerActivity.visible && !DreamSaver.dreaming && !blocked()

    private fun begin() {
        if (phase != Phase.ARMED) return
        phase = Phase.IDLE
        val t = title ?: return
        if (!wanted()) return
        if (!canPlay()) { arm(); return }
        val freeMb = freeMemoryMb()
        if (freeMb in 0 until MIN_FREE_MB) {
            Log.w(TAG, "skipped ${t.name}: only $freeMb MB free")
            waitForUser = true
            return
        }
        phase = Phase.RESOLVING
        val g = gen
        TrailerEngine.resolve(ctx, t) { r ->
            if (phase != Phase.RESOLVING || g != gen || title?.key != t.key) return@resolve
            phase = Phase.IDLE
            when {
                r == null -> waitForUser = true
                !wanted() -> {}
                !canPlay() -> arm()
                else -> start(t, r, freeMb)
            }
        }
    }

    // ---- playing ----

    private fun start(t: Title, r: TrailerEngine.Resolved, freeMb: Int) {
        val still = backdrop()
        if (still == null || still.isRecycled) { arm(); return }
        val maxHeight = if (freeMb in 0 until TIGHT_MB) 720 else 1080
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(10_000)
            .setAllowCrossProtocolRedirects(true)
        // Normally a DASH ladder; else YouTube's HLS master (H.264 only, from a local copy) or a muxed MP4.
        val source = try {
            when (val plan = r.plan) {
                is TrailerPick.Plan.Dash -> DashMediaSource.Factory(http).createMediaSource(
                    DashManifestParser().parse(Uri.parse("https://www.youtube.com/"), plan.manifest(maxHeight).byteInputStream()),
                    MediaItem.Builder().setUri(Uri.EMPTY).setMimeType(MimeTypes.APPLICATION_MPD).setMediaId(r.videoKey).build())
                is TrailerPick.Plan.Hls -> {
                    val f = java.io.File(ctx.cacheDir, "hero_trailer.m3u8").apply { writeText(plan.master) }
                    HlsMediaSource.Factory(DefaultDataSource.Factory(ctx, http)).createMediaSource(
                        MediaItem.Builder().setUri(Uri.fromFile(f)).setMimeType(MimeTypes.APPLICATION_M3U8).setMediaId(r.videoKey).build())
                }
                is TrailerPick.Plan.Progressive -> ProgressiveMediaSource.Factory(http).createMediaSource(
                    MediaItem.Builder().setUri(Uri.parse(plan.url)).setMediaId(r.videoKey).build())
            }
        } catch (e: Exception) {
            Log.w(TAG, "manifest for ${t.name}: $e")
            waitForUser = true
            return
        }
        // The still goes on top first: the SurfaceView punches a hole in the window that stays black until
        // the first frame, and the cover keeps the screen exactly as it was until then.
        val sv = SurfaceView(ctx)
        val cv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(still) }
        slot.addView(sv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        slot.addView(cv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        surface = sv
        cover = cv
        videoW = 0; videoH = 0; pixelRatio = 1f; zoom = 1f; lastZoom = 0f; probes = 0

        val selector = DefaultTrackSelector(ctx, AdaptiveTrackSelection.Factory(4_000, 25_000, 25_000, 0.75f))
        selector.setParameters(selector.buildUponParameters()
            .setMaxVideoSize(1920, 1080)
            .setTunnelingEnabled(false)
            .setExceedRendererCapabilitiesIfNecessary(true))
        val renderers = DefaultRenderersFactory(ctx)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        // A trailer needs little buffer: ~20 s ahead at most, capped at 12 MB of Java heap.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(8_000, 20_000, 1_500, 3_000)
            .setTargetBufferBytes(12 shl 20)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val p = ExoPlayer.Builder(ctx, renderers).setTrackSelector(selector).setLoadControl(load).build()
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
        p.volume = 0f
        p.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        p.repeatMode = Player.REPEAT_MODE_OFF
        p.addListener(listener)
        p.setVideoSurfaceView(sv)
        p.setMediaSource(source)
        p.playWhenReady = true
        p.prepare()
        player = p
        playingKey = r.videoKey
        phase = Phase.STARTING
        startedAt = SystemClock.elapsedRealtime()
        firstFrameAt = 0L
        Log.w(TAG, "starting ${t.name} (${r.videoKey}) up to ${maxHeight}p, $freeMb MB free")
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCH_MS)
    }

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            if (phase != Phase.STARTING || firstFrameAt != 0L) return
            firstFrameAt = SystemClock.elapsedRealtime()
            Log.w(TAG, "first frame after ${firstFrameAt - startedAt} ms (${videoW}x$videoH)")
            probe()
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            if (size.width <= 0 || size.height <= 0) return
            videoW = size.width
            videoH = size.height
            pixelRatio = if (size.pixelWidthHeightRatio > 0f) size.pixelWidthHeightRatio else 1f
            layoutSurface()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) end()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "playback failed: ${error.errorCodeName} ${TrailerPick.clean(error.message)}")
            // Before the first frame: that video is skipped next time (the title's next one is tried).
            // Mid-play: only fresh links next time.
            val key = playingKey
            title?.let { t -> if (phase == Phase.STARTING && key != null) TrailerEngine.failed(t, key, error.errorCodeName) else TrailerEngine.forget(t) }
            stop(if (phase == Phase.PLAYING) Stop.SOFT else Stop.NOW)
            waitForUser = true
        }
    }

    /** Center-crop like the backdrop: the surface is sized to cover the slot (then zoomed past any letterbox). */
    private fun layoutSurface() {
        val sv = surface ?: return
        if (videoW <= 0 || videoH <= 0) return
        val w = (if (slot.width > 0) slot.width else ctx.resources.displayMetrics.widthPixels).toFloat()
        val h = (if (slot.height > 0) slot.height else ctx.resources.displayMetrics.heightPixels).toFloat()
        val va = videoW * pixelRatio / videoH
        var sw = w
        var sh = h
        if (va > w / h) sw = h * va else sh = w / va
        sw *= zoom
        sh *= zoom
        val lp = sv.layoutParams as FrameLayout.LayoutParams
        if (lp.width == sw.toInt() && lp.height == sh.toInt()) return
        lp.width = sw.toInt()
        lp.height = sh.toInt()
        lp.gravity = Gravity.CENTER
        sv.layoutParams = lp
    }

    /**
     * Waits for real picture (trailers often open on black), sizes away baked-in letterbox bars, then
     * reveals. Small frame grabs (96x54) every quarter second, for a few seconds at most.
     */
    private fun probe() {
        val sv = surface ?: return
        if (phase != Phase.STARTING) return
        val waited = SystemClock.elapsedRealtime() - firstFrameAt
        if (waited > PROBE_MAX_MS || !sv.holder.surface.isValid) { reveal(); return }
        try {
            PixelCopy.request(sv, probeBmp, { result ->
                if (phase != Phase.STARTING || surface !== sv) return@request
                probes++
                val z = if (result == PixelCopy.SUCCESS) analyze(probeBmp) else null
                when {
                    result != PixelCopy.SUCCESS && probes >= 3 -> main.postDelayed({ reveal() }, 300)
                    z == null -> main.postDelayed({ probe() }, PROBE_STEP_MS)
                    // Two frames in a row must agree before the picture is zoomed (a dark scene isn't a letterbox).
                    z > 1.01f && abs(z - lastZoom) > 0.03f -> { lastZoom = z; main.postDelayed({ probe() }, PROBE_STEP_MS) }
                    else -> {
                        zoom = z
                        layoutSurface()
                        main.postDelayed({ reveal() }, if (z > 1.01f) 160L else 0L)
                    }
                }
            }, main)
        } catch (e: Exception) {
            reveal()
        }
    }

    /** null while the frame is (nearly) black; else the zoom that removes symmetric letterbox / pillarbox bars. */
    private fun analyze(b: Bitmap): Float? {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        fun luma(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        val rowMax = IntArray(h)
        val colMax = IntArray(w)
        var sum = 0L
        for (y in 0 until h) for (x in 0 until w) {
            val l = luma(px[y * w + x])
            sum += l
            if (x in 2 until w - 2 && l > rowMax[y]) rowMax[y] = l
            if (y in 2 until h - 2 && l > colMax[x]) colMax[x] = l
        }
        if ((rowMax.maxOrNull() ?: 0) < 60 || sum / (w * h) < 12) return null
        var z = 1f
        val top = rowMax.indexOfFirst { it >= BAR_LUMA }
        val bottom = h - 1 - rowMax.indexOfLast { it >= BAR_LUMA }
        if (top >= 3 && bottom >= 3 && abs(top - bottom) <= 2) {
            // Only real cinema shapes (1.85:1 to 2.4:1 inside the 16:9 frame).
            val aspect = (w.toFloat() / h) * h / (h - top - bottom).toFloat()
            if (aspect in 1.82f..2.45f) z = maxOf(z, h / (h - top - bottom).toFloat())
        }
        val left = colMax.indexOfFirst { it >= BAR_LUMA }
        val right = w - 1 - colMax.indexOfLast { it >= BAR_LUMA }
        if (left >= 6 && right >= 6 && abs(left - right) <= 2) z = maxOf(z, w / (w - left - right).toFloat())
        return z.coerceAtMost(MAX_ZOOM)
    }

    private fun reveal() {
        if (phase != Phase.STARTING) return
        val cv = cover ?: return
        phase = Phase.PLAYING
        Log.w(TAG, "on screen ${SystemClock.elapsedRealtime() - armedAt} ms after the rest began " +
            "(rest ${REST_MS} ms, player start to first frame ${firstFrameAt - startedAt} ms)")
        cv.animate().cancel()
        cv.animate().alpha(0f).setStartDelay(0).setDuration(REVEAL_MS).setInterpolator(Ui.EASE).withEndAction {
            // Hidden under the video: let it go of the bitmap's texture (the backdrop below keeps showing it).
            if (phase == Phase.PLAYING && cover === cv) cv.visibility = View.INVISIBLE
        }.start()
        fadeVolume(targetVolume(), VOLUME_IN_MS)
        setImmersive(true, animate = true)
    }

    private fun setImmersive(on: Boolean, animate: Boolean) {
        if (on) main.removeCallbacks(hideAgain)
        if (immersive == on) return
        immersive = on
        onImmersive?.invoke(on, animate)
    }

    private fun end() {
        if (phase == Phase.PLAYING || phase == Phase.STARTING) {
            stop(Stop.END)
            waitForUser = true
        }
    }

    /** Once a second while a trailer is up: the screen must still be ours, and the end fades out early. */
    private val watchdog = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (phase == Phase.IDLE || phase == Phase.STOPPING) return
            when {
                !canPlay() -> { stop(if (front?.get() === act && slot.hasWindowFocus()) Stop.SOFT else Stop.NOW); disarm(); return }
                phase == Phase.STARTING && SystemClock.elapsedRealtime() - startedAt > START_TIMEOUT_MS -> {
                    Log.w(TAG, "no picture after ${START_TIMEOUT_MS / 1000} s, giving up")
                    val key = playingKey
                    title?.let { t -> if (key != null) TrailerEngine.failed(t, key, "no picture") else TrailerEngine.forget(t) }
                    stop(Stop.NOW)
                    waitForUser = true
                    return
                }
                phase == Phase.PLAYING && p.duration != C.TIME_UNSET && p.duration - p.currentPosition < END_EARLY_MS -> { end(); return }
            }
            main.postDelayed(this, WATCH_MS)
        }
    }

    private enum class Stop { SOFT, EXIT, END, NOW }

    /**
     * SOFT / END: the still fades back over the video (END slower), the volume dips, then everything is
     * released. NOW: released this instant (Dream Player or another app is taking the screen, or Play was
     * pressed); the backdrop under the slot is the same picture, so nothing flashes.
     */
    private fun stop(kind: Stop, done: (() -> Unit)? = null) {
        main.removeCallbacks(watchdog)
        main.removeCallbacks(hideAgain)
        if (phase != Phase.STARTING && phase != Phase.PLAYING && phase != Phase.STOPPING) { done?.invoke(); return }
        if (done != null) onStopped = onStopped?.let { before -> { before(); done() } } ?: done
        // Remember the spot (a full-screen Trailer press continues from it).
        if (phase == Phase.PLAYING) player?.let { p ->
            lastKey = playingKey
            lastTitle = title?.key
            lastPos = p.currentPosition
            lastAt = SystemClock.elapsedRealtime()
        }
        // The page's UI comes back as the still does.
        setImmersive(false, animate = kind != Stop.NOW)
        val cv = cover
        if (kind == Stop.NOW || phase == Phase.STARTING || cv == null) {
            teardown()
            return
        }
        if (phase == Phase.STOPPING) return // already fading back; [onStopped] runs when it lands
        phase = Phase.STOPPING
        val ms = when (kind) { Stop.END -> END_FADE_MS; Stop.EXIT -> EXIT_FADE_MS; else -> STOP_FADE_MS }
        fadeVolume(0f, (ms * 0.8f).toLong())
        cv.visibility = View.VISIBLE
        cv.animate().cancel()
        cv.animate().alpha(1f).setStartDelay(0).setDuration(ms).setInterpolator(Ui.SETTLE).withEndAction {
            teardown()
            evaluate()
        }.start()
    }

    private fun teardown() {
        volumeAnim?.cancel()
        volumeAnim = null
        player?.let {
            it.removeListener(listener)
            it.release() // frees the hardware decoder before anything else can ask for it
        }
        player = null
        playingKey = null
        setImmersive(false, animate = false)
        cover?.let { it.animate().cancel(); it.setImageDrawable(null); slot.removeView(it) }
        cover = null
        surface?.let { slot.removeView(it) }
        surface = null
        phase = Phase.IDLE
        Log.w(TAG, "released (${freeMemoryMb()} MB free)")
        onStopped?.let { onStopped = null; it() }
    }

    // ---- sound ----

    private fun targetVolume() = if (mode(ctx) == SOUND) VOLUME else 0f

    private fun fadeVolume(to: Float, ms: Long) {
        val p = player ?: return
        volumeAnim?.cancel()
        val from = p.volume
        if (from == to) return
        volumeAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            interpolator = Ui.SETTLE
            addUpdateListener { a -> player?.volume = a.animatedValue as Float }
            start()
        }
    }

    companion object {
        private const val TAG = "Trailer"

        const val SOUND = 0
        const val MUTED = 1
        const val OFF = 2
        val LABELS = listOf("On with Sound", "On, Muted", "Off")

        /** Set by the host: true while something covers the screen (a menu, the screensaver, a sheet). */
        var blocked: () -> Boolean = { false }

        /** True while any hero trailer is loaded or playing (the ratings prefetch rests then). Main thread. */
        val anyPlaying: Boolean get() = live.any { it.playingKey != null }

        fun attach(slot: FrameLayout, kit: PremiumKit, backdrop: () -> Bitmap?) = HeroTrailer(slot, kit, backdrop)

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("trailers", Context.MODE_PRIVATE)

        /** Background Trailers for the current profile: [SOUND] (default), [MUTED] or [OFF]. */
        fun mode(ctx: Context) = prefs(ctx).getInt("mode" + Profiles.suffix(ctx), SOUND).coerceIn(SOUND, OFF)

        fun setMode(ctx: Context, m: Int) = prefs(ctx).edit().putInt("mode" + Profiles.suffix(ctx), m.coerceIn(SOUND, OFF)).apply()

        fun label(ctx: Context) = LABELS[mode(ctx)]

        /** Settings: On with Sound -> On, Muted -> Off -> On with Sound. */
        fun cycle(ctx: Context) = setMode(ctx, (mode(ctx) + 1) % 3)

        // ---- which activity is in front (one tracker for every hero) ----

        private val live = ArrayList<HeroTrailer>()
        private var front: WeakReference<Activity>? = null
        private var tracking = false

        /** [activity] is paused for real: another screen is taking over (Dream Player, a streaming app), free the decoder now. */
        fun pausedAfterAll(activity: Activity) {
            if (front?.get() === activity) front = null
            for (h in live.toList()) if (h.act === activity) { h.stop(Stop.NOW); h.disarm(); h.restartOnResume = true }
        }

        private fun track(ctx: Context) {
            if (tracking) return
            val a = activityOf(ctx) ?: return
            tracking = true
            if (a.hasWindowFocus()) front = WeakReference(a)
            a.application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    front = WeakReference(activity)
                    for (h in live.toList()) if (h.act === activity) {
                        if (h.restartOnResume) { h.restartOnResume = false; h.waitForUser = false }
                        h.evaluate()
                    }
                }
                override fun onActivityPaused(activity: Activity) {
                    pausedAfterAll(activity)
                }
                override fun onActivityCreated(activity: Activity, state: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        }

        private fun activityOf(ctx: Context): Activity? {
            var c: Context? = ctx
            while (c is ContextWrapper) {
                if (c is Activity) return c
                c = c.baseContext
            }
            return null
        }

        /** Free system memory in MB (-1 when unknown); low-RAM devices report 0 (never play). */
        private fun freeMemoryMb(ctx: Context): Int {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return -1
            if (am.isLowRamDevice) return 0
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            return (mi.availMem / (1024 * 1024)).toInt()
        }

        /** Focus at rest on the details page this long before the trailer starts (owner, 2026-10-30: was 4 s). */
        private const val REST_MS = 1_000L
        private const val PREFETCH_MS = 500L
        /** The UI shown over a playing trailer fades away again after this long without a key. */
        private const val UI_IDLE_MS = 4_000L
        /** How long a stopped trailer's spot is kept for [handoff]. */
        private const val HANDOFF_MS = 5 * 60_000L
        private const val WATCH_MS = 1_000L
        private const val START_TIMEOUT_MS = 15_000L
        private const val END_EARLY_MS = 1_200L
        private const val REVEAL_MS = 900L
        private const val STOP_FADE_MS = 320L
        private const val END_FADE_MS = 900L
        /** The page is closing: a quick settle back to the still before the page itself fades. */
        private const val EXIT_FADE_MS = 180L
        private const val VOLUME_IN_MS = 1_800L
        /** A touch below full: a trailer starts on its own, it shouldn't jolt the room. */
        private const val VOLUME = 0.8f
        private const val MIN_FREE_MB = 250
        private const val TIGHT_MB = 300
        private const val PROBE_W = 96
        private const val PROBE_H = 54
        private const val PROBE_STEP_MS = 250L
        private const val PROBE_MAX_MS = 3_500L
        private const val BAR_LUMA = 28
        private const val MAX_ZOOM = 1.45f
    }

    private fun freeMemoryMb() = freeMemoryMb(ctx)
}
