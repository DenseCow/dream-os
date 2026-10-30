package com.firehome.launcher

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.media3.common.text.Cue
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import org.json.JSONObject

/**
 * Dream Player: the full-screen player Dream OS plays every title in (the play flow opens it first, with a
 * loading curtain, while Dream Core resolves). Hosts the engine ([ExoEngine]) and the on-screen components
 * (controls, skip buttons, post-play, curtain), keeps Continue Watching exact, and answers remote/AirPods
 * media keys through a MediaSession. Runs in its own task (affinity com.firehome.launcher.player), so
 * leaving it explicitly brings Dream OS back. Not exported: only Dream OS opens it.
 *
 * Intents:
 *  - The stream ([PlayFlow.streamIntent], PlayFlow.deliver with a stream found ahead, the source picker):
 *    ACTION_VIEW, data = stream URL (may end in "|Header=…&…"), type "video/(any)", the request as JSON in
 *    [PlayFlow.EXTRA_REQUEST]. Also read: extras "title", "headers" (String[] key,value,… or "A=b&C=d"),
 *    "subs" (Uri[]) + "subs.name" (String[]), "position" (ms).
 *  - Play flow: [intentFor] (action [ACTION_PLAY], [EXTRA_REQUEST] = the request as JSON):
 *    curtain up, waiting for the stream's VIEW intent, which arrives through onNewIntent (singleTask).
 *    [PlayFlow.EXTRA_FRONT_ONLY] just brings the screen forward.
 */
class DreamPlayerActivity : Activity(), PlayerHost {

    companion object {
        private const val TAG = "FireHome"

        @Volatile private var warmed = false

        /**
         * While the curtain waits for a stream (Dream Core still looking), ExoPlayer's main classes are loaded
         * and the device's decoder list read on a low-priority background thread, once per process, so the
         * player's setup on the main thread when the stream arrives (ExoEngine.load) doesn't also pay for
         * first-time class loading (bug pass 2026-10-26, item 17: the curtain's spinner froze at the hand-off).
         */
        @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
        private fun warmPlayerClasses() {
            if (warmed) return
            warmed = true
            Thread({
                val t0 = SystemClock.elapsedRealtime()
                val classes = listOf(
                    androidx.media3.exoplayer.ExoPlayer.Builder::class.java,
                    androidx.media3.exoplayer.DefaultRenderersFactory::class.java,
                    androidx.media3.exoplayer.DefaultLoadControl::class.java,
                    androidx.media3.exoplayer.trackselection.DefaultTrackSelector::class.java,
                    androidx.media3.exoplayer.source.DefaultMediaSourceFactory::class.java,
                    androidx.media3.exoplayer.source.ProgressiveMediaSource::class.java,
                    androidx.media3.exoplayer.video.MediaCodecVideoRenderer::class.java,
                    androidx.media3.exoplayer.audio.MediaCodecAudioRenderer::class.java,
                    androidx.media3.exoplayer.audio.DefaultAudioSink::class.java,
                    androidx.media3.extractor.DefaultExtractorsFactory::class.java,
                    androidx.media3.extractor.mkv.MatroskaExtractor::class.java,
                    androidx.media3.extractor.mp4.Mp4Extractor::class.java,
                )
                for (c in classes) try { Class.forName(c.name, true, c.classLoader) } catch (e: Throwable) {}
                // Cached by MediaCodecUtil: the playback thread's decoder pick for the first frame is quicker too.
                for (mime in listOf(androidx.media3.common.MimeTypes.VIDEO_H265, androidx.media3.common.MimeTypes.VIDEO_H264))
                    try { androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, false, false) } catch (e: Throwable) {}
                Log.w(TAG, "dream player: player classes warmed in ${SystemClock.elapsedRealtime() - t0} ms")
            }, "player-warm").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
        }
        /** Action of [intentFor] (any intent carrying [EXTRA_REQUEST] works the same). */
        const val ACTION_PLAY = "com.firehome.launcher.action.DREAM_PLAY"
        private const val SAVE_EVERY_MS = 30_000L
        private const val MIN_HISTORY_MS = 10 * 60_000L // shorter is a trailer/clip, not Continue Watching material
        /** Real playback (seeks and skips excluded) before a title not on Continue Watching joins it. */
        private const val REAL_WATCH_MS = 90_000L
        /** Quiet switches to the next source per title before the error screen shows (a ready alternate, else a fresh lookup). */
        private const val SILENT_TRIES = 3
        /** No first frame this long after a load while another link is ready: that one plays instead. */
        private const val START_WATCH_MS = 12_000L
        /** Sound this long with no picture counts as a failed source (a video track no decoder here can start). */
        private const val NO_PICTURE_MS = 12_000L
        private const val DAMAGED_TEXT = "This file is damaged or incomplete. Try Again plays another copy."

        /** The player window is on screen (MainActivity skips its process-wide GPU trim while it is). */
        @Volatile var visible = false
            private set
        @Volatile private var lastVisibleAt = 0L

        /** Dream Player is showing (or just was): background lookups keep out of playback's way. */
        fun isActive(): Boolean = visible || lastVisibleAt > 0 && SystemClock.elapsedRealtime() - lastVisibleAt < 20_000

        /** The player on screen, for the developer's held-key hook only ([PingReceiver]); nothing else reads it. */
        @Volatile internal var onScreen: java.lang.ref.WeakReference<DreamPlayerActivity>? = null
            private set

        /** The player screen while it exists (created, not destroyed), for [canReturn]. */
        @Volatile private var live: java.lang.ref.WeakReference<DreamPlayerActivity>? = null

        /**
         * A paused title waits behind Dream OS (X-Ray opened a title page from it): Back from that page can bring
         * it forward ([PlayFlow.bringPlayerToFront]) at the same spot.
         */
        fun canReturn(): Boolean {
            val a = live?.get() ?: return false
            return !a.isFinishing && !a.isDestroyed && !a.waiting && a.exo.hasSource
        }

        /** The PlaybackRequest as JSON ([toJson]) on an intent that opens the player. */
        const val EXTRA_REQUEST = "com.firehome.launcher.extra.REQUEST"

        /** Intent that opens the player with the curtain for [req] and waits for its stream. */
        fun intentFor(ctx: Context, req: PlaybackRequest): Intent =
            Intent(ctx, DreamPlayerActivity::class.java)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_REQUEST, toJson(req).toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        /** The request on an intent made by [intentFor], or null. */
        fun requestFrom(i: Intent?): PlaybackRequest? =
            i?.getStringExtra(EXTRA_REQUEST)?.let { try { fromJson(JSONObject(it)) } catch (e: Exception) { null } }

        fun toJson(r: PlaybackRequest): JSONObject = JSONObject().apply {
            put("title", r.title); put("type", r.type); put("tmdbId", r.tmdbId)
            r.imdb?.let { put("imdb", it) }; r.year?.let { put("year", it) }
            r.season?.let { put("season", it) }; r.episode?.let { put("episode", it) }
            r.episodeTitle?.let { put("episodeTitle", it) }
            r.backdrop?.let { put("backdrop", it) }; r.poster?.let { put("poster", it) }; r.logo?.let { put("logo", it) }
            put("startMs", r.startMs); r.player?.let { put("player", it) }; put("autoplayNext", r.autoplayNext)
            if (r.trailer) put("trailer", true)
        }

        fun fromJson(o: JSONObject): PlaybackRequest = PlaybackRequest(
            title = o.optString("title"), type = o.optString("type", "movie"), tmdbId = o.optInt("tmdbId"),
            imdb = o.optString("imdb").ifEmpty { null }, year = o.optInt("year").takeIf { it > 0 },
            season = if (o.has("season")) o.optInt("season") else null, episode = if (o.has("episode")) o.optInt("episode") else null,
            episodeTitle = o.optString("episodeTitle").ifEmpty { null },
            backdrop = o.optString("backdrop").ifEmpty { null }, poster = o.optString("poster").ifEmpty { null },
            logo = o.optString("logo").ifEmpty { null }, startMs = o.optLong("startMs"),
            player = o.optString("player").ifEmpty { null }, autoplayNext = o.optBoolean("autoplayNext", true),
            trailer = o.optBoolean("trailer", false),
        )

        /** Action of [trailerIntent]: a title's trailer, full screen in Dream Player (looked up here, never another app). */
        const val ACTION_TRAILER = "com.firehome.launcher.action.DREAM_TRAILER"
        private const val EXTRA_T_ID = "com.firehome.launcher.extra.TRAILER_ID"
        private const val EXTRA_T_TYPE = "com.firehome.launcher.extra.TRAILER_TYPE"
        private const val EXTRA_T_NAME = "com.firehome.launcher.extra.TRAILER_NAME"
        private const val EXTRA_T_BACKDROP = "com.firehome.launcher.extra.TRAILER_BACKDROP"
        /** The video the details page's background trailer was showing, for the start spot (a smooth hand-off). */
        private const val EXTRA_T_VIDEO = "com.firehome.launcher.extra.TRAILER_VIDEO"
        private const val EXTRA_T_START = "com.firehome.launcher.extra.TRAILER_START"
        private const val TRAILER_TIMEOUT_MS = 25_000L
        /** Videos moved on to after playback failures, per trailer press (each lookup also tries several). */
        private const val MAX_TRAILER_ADVANCES = 3

        /** Opens the player for [t]'s trailer: the curtain at once, then the trailer (from [startMs] when it's [videoKey]). */
        fun trailerIntent(ctx: Context, t: Title, startMs: Long = 0, videoKey: String? = null): Intent =
            Intent(ctx, DreamPlayerActivity::class.java)
                .setAction(ACTION_TRAILER)
                .putExtra(EXTRA_T_ID, t.id)
                .putExtra(EXTRA_T_TYPE, t.type)
                .putExtra(EXTRA_T_NAME, t.name)
                .putExtra(EXTRA_T_BACKDROP, t.backdropPath)
                .putExtra(EXTRA_T_VIDEO, videoKey)
                .putExtra(EXTRA_T_START, startMs)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var exo: ExoEngine
    /** Subtitles from the web for Dream Core streams (they bring only what's inside the file). */
    private lateinit var dreamSubs: DreamSubtitles.Session
    override val engine: PlayerEngine get() = exo
    override var request: PlaybackRequest? = null
        private set
    override lateinit var overlayRoot: FrameLayout
        private set

    /** Media3's subtitle renderer, above the video and below [overlayRoot]; restyle with [setSubtitleStyle]. */
    lateinit var subtitleView: SubtitleView
        private set
    /** Above everything else (dialogs). */
    lateinit var dialogRoot: FrameLayout
        private set
    private lateinit var videoFrame: AspectRatioFrameLayout
    private lateinit var surface: SurfaceView

    private lateinit var overlay: PlayerOverlay
    private lateinit var skip: SkipController
    private lateinit var postPlay: PostPlayController

    /** Developer preview (PingReceiver --ez still true): "Are You Still Watching?" over what's playing. */
    internal fun debugStillWatching() { if (::postPlay.isInitialized) postPlay.debugStillWatching() }
    private lateinit var curtain: LoadingCurtain
    private var session: MediaSession? = null

    // Session state.
    private var waiting = false            // curtain up, waiting for the stream's URL
    private var componentsOn = false
    private var firstFrame = false
    private var rawUrl: String? = null     // as it arrived (with |headers), for the source picker
    private var lastSaveAt = 0L
    /**
     * Milliseconds actually played this item (normal playback only: seeks and skips add nothing). A title joins
     * Continue Watching only after [REAL_WATCH_MS] of it, unless it's already on the row ([onRow]; owner, 2026-10-28).
     */
    private var realWatchedMs = 0L
    /** The source and start of the session playing, for a from-the-top retry ([onEnded] before the first frame). */
    private var sessionSrc: ExoEngine.Source? = null
    private var sessionStartMs = 0L
    private var retriedFromTop = false
    private var noPictureReported = false
    /** Quiet switches to the next source for the title in [silentKey] ([silentFallback]). */
    private var silentTries = 0
    private var silentKey: String? = null
    private var lastTickPos = -1L
    private var onRow = false
    private var finished = false           // credits reached: saved as watched
    private var finishedAt = 0L            // where [finished] was set (a seek well before it un-finishes)
    private var creditsStartMs: Long? = null
    private var markersAsked = false
    private var resumeOnResume = false     // was playing when onPause paused it
    private var released = false           // player released in onStop
    private var loadStartedAt = 0L
    private var curtainHiddenAt = 0L       // the first frame: keys right after it are still meant for the page
    private var resumedAt = 0L
    /** A trailer is up: no sources, skip, post-play or Continue Watching; its end goes back to Dream OS. */
    private var trailerMode = false
    private var trailerTitle: Title? = null
    private var trailerVideo: String? = null
    /** The video the trailer's streams came from (a failure before its first frame moves on to the next one). */
    private var trailerPlaying: String? = null
    private var trailerAdvances = 0
    private var trailerGen = 0
    /** Skip markers and post-play were started (not for trailers). */
    private var fullComponents = false
    private val trailerTimeout = Runnable {
        if (trailerMode && !firstFrame && !isFinishing) {
            Log.w(TAG, "dream player: trailer gave no picture in ${TRAILER_TIMEOUT_MS / 1000} s")
            if (nextTrailer("no picture in ${TRAILER_TIMEOUT_MS / 1000} s")) return@Runnable
            stopComponents()
            if (exo.hasSource) exo.clear()
            curtain.showError("trailer timeout")
        }
    }

    // ---- lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DreamTime.init(this) // player clock / "Ends at" in the chosen time zone
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        videoFrame = AspectRatioFrameLayout(this).apply {
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            addView(surface, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        root.addView(videoFrame, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        subtitleView = SubtitleView(this).apply {
            setApplyEmbeddedStyles(true)
            setApplyEmbeddedFontSizes(false)
        }
        setSubtitleStyle(CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.argb(220, 0, 0, 0), null), 0.0533f)
        root.addView(subtitleView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        overlayRoot = FrameLayout(this).apply { clipChildren = false }
        root.addView(overlayRoot, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        dialogRoot = FrameLayout(this)
        root.addView(dialogRoot, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        exo = ExoEngine(this)
        exo.setSurfaceView(surface)
        exo.onCues = { cues: List<Cue> -> subtitleView.setCues(cues) }
        exo.onVideoSize = { w, h, ratio -> videoFrame.setAspectRatio(w * ratio / h) }
        exo.onFirstFrame = {
            if (!firstFrame) {
                firstFrame = true
                Log.w(TAG, "dream player: first frame after ${SystemClock.elapsedRealtime() - loadStartedAt} ms, ${exo.videoInfo()}")
                handler.removeCallbacks(trailerTimeout)
                handler.removeCallbacks(startWatch)
                curtainHiddenAt = SystemClock.uptimeMillis()
                curtain.hide()
                dreamSubs.onFirstFrame()
                // How long this service's stream took to start (Dream Core asks a clearly faster one first next time).
                if (!trailerMode) request?.let { r -> rawUrl?.let { u -> try { NativeResolver.firstFrame(this, r, u) } catch (e: Exception) {} } }
                if (!trailerMode) PlaybackTrim.started(this) // background work waits, idle memory goes back (AirPods' audio needs the headroom)
                // Played with the user's filters set aside (only a CAM copy, say): said once as the picture starts.
                var noted = false
                PlayFlow.session?.takeIf { it.isFor(request) }?.let { s ->
                    s.note?.let { n ->
                        s.note = null
                        noted = true
                        overlay.notice(n, if (n == PlayFlow.CAM_NOTE) Availability.Status.CAM.tint else Ui.alpha(Color.WHITE, 0.92f))
                    }
                }
                // The languages this copy's audio carries, for the title page's language pills and Dream Core's ranking.
                if (!trailerMode) request?.let { r -> LangPills.heard(this, r, rawUrl, exo.tracks("audio")) }
                // Audio in another language than the viewer's (a new anime episode in Japanese): its subtitles come on.
                if (!trailerMode) overlay.audioCheck(dreamSubs, after = noted)
            }
        }
        exo.addListener(engineListener)
        dreamSubs = DreamSubtitles.Session(this, exo)

        overlay = PlayerOverlay(this)
        skip = SkipController(this)
        postPlay = PostPlayController(this)
        curtain = LoadingCurtain(this)

        setupSession()
        live = java.lang.ref.WeakReference(this)
        handleIntent(intent, created = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, created = false)
    }

    override fun onStart() {
        super.onStart()
        visible = true
        onScreen = java.lang.ref.WeakReference(this)
        exo.progressEnabled = true
    }

    override fun onResume() {
        super.onResume()
        resumedAt = SystemClock.uptimeMillis()
        if (released) {
            // Back after being hidden (screensaver, Home): same spot, paused, controls up.
            released = false
            // A source that failed stays failed: re-preparing it would lift the error screen at its first frame.
            if (exo.hasSource && exo.player == null && exo.error == null) {
                exo.restore(playWhenReady = false)
                if (componentsOn) overlay.setControlsVisible(true)
            }
        } else if (resumeOnResume) {
            exo.play()
        }
        resumeOnResume = false
        session?.isActive = true
    }

    override fun onPause() {
        HomeRedirectService.bounceBackIfHome(this) // the remote's Home: straight back to Dream OS
        resumeOnResume = exo.isPlaying
        if (exo.isPlaying) exo.pause()
        saveProgress(force = true)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        visible = false
        if (onScreen?.get() === this) onScreen = null
        lastVisibleAt = SystemClock.elapsedRealtime()
        exo.progressEnabled = false
        saveProgress(force = true)
        if (!isChangingConfigurations && exo.player != null) {
            exo.releasePlayer() // frees decoders and the stream buffer while hidden
            released = true
        }
        session?.isActive = false // media keys go back to whatever plays next
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        if (live?.get() === this) live = null
        handler.removeCallbacksAndMessages(null)
        saveProgress(force = true)
        Trakt.onPlayerClosed(this)
        stopComponents()
        dreamSubs.end()
        if (waiting) PlayFlow.cancel(this)
        exo.release()
        session?.run { isActive = false; release() }
        session = null
        if (visible) { visible = false; lastVisibleAt = SystemClock.elapsedRealtime() }
        super.onDestroy()
    }

    // ---- intents ----

    private fun handleIntent(i: Intent, created: Boolean) {
        val req = requestFrom(i)
        when {
            i.action == ACTION_TRAILER -> startTrailer(i)
            i.action == Intent.ACTION_VIEW && i.data != null -> onStream(i, created)
            req != null -> startWaiting(req)
            // Only brought back to the front (X-Ray's title page): nothing new.
            i.getBooleanExtra(PlayFlow.EXTRA_FRONT_ONLY, false) -> if (created) close()
            created -> close()
        }
    }

    /** Curtain up for [req] until its stream arrives. */
    private fun startWaiting(req: PlaybackRequest) {
        endTrailerMode()
        val same =waiting && request?.let { it.tmdbId == req.tmdbId && it.season == req.season && it.episode == req.episode } == true
        if (!same) {
            endSession()
            request = req
            waiting = true
            curtain.show(req)
            warmPlayerClasses()
        } else {
            request = req
        }
        updateMetadata()
        updatePlaybackState()
    }

    private fun onStream(i: Intent, created: Boolean) {
        val url = i.dataString ?: return
        // The request the stream was found for (the play flow puts it on the intent), else the one the curtain waits for.
        val carried = PlayFlow.requestFrom(i)
        // The user backed out of this title while it was still being found: don't pop back up.
        if (PlayFlow.isCancelled(carried ?: request)) {
            Log.w(TAG, "dream player: ignoring late stream for a cancelled title")
            if (created || !exo.hasSource) close()
            return
        }
        endTrailerMode() // the stream takes over (a trailer never comes through here)
        val titleExtra = i.getStringExtra("title") ?: i.getStringExtra(Intent.EXTRA_TITLE)
        val req = (carried ?: request?.takeIf { waiting })?.let { r -> if (r.title.isBlank() && titleExtra != null) r.copy(title = titleExtra) else r }
            ?: titleExtra?.takeIf { it.isNotBlank() }?.let { PlaybackRequest(title = it, type = "movie", tmdbId = 0) }
        val headers = LinkedHashMap<String, String>()
        headers.putAll(extraHeaders(i))
        val subs = ArrayList(extraSubs(i))
        val startMs = req?.startMs?.takeIf { it > 0 } ?: i.getIntExtra("position", 0).toLong()
        val mime = i.type?.takeIf { !it.startsWith("video/") } // e.g. application/x-mpegURL
        startSession(req, url, ExoEngine.Source(url, headers, mime, subs), startMs)
    }

    private fun extraHeaders(i: Intent): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            i.getStringArrayExtra("headers")?.let { a -> for (k in 0 until a.size - 1 step 2) out[a[k]] = a[k + 1] }
            i.getStringExtra("headers")?.let { out.putAll(StreamUrls.headers(it)) }
            i.getBundleExtra("android.media.intent.extra.HTTP_HEADERS")?.let { b -> b.keySet().forEach { k -> b.getString(k)?.let { out[k] = it } } }
        } catch (e: Exception) {}
        return out
    }

    private fun extraSubs(i: Intent): List<SubtitleFile> = try {
        @Suppress("DEPRECATION")
        val uris = i.getParcelableArrayExtra("subs")?.mapNotNull { (it as? Parcelable) as? Uri }.orEmpty()
        val names = i.getStringArrayExtra("subs.name")
        uris.mapIndexed { n, u -> SubtitleFile(u.toString(), StreamUrls.langFromName(u.toString()), names?.getOrNull(n)) }
    } catch (e: Exception) { emptyList() }

    // ---- trailers ----

    /** [ACTION_TRAILER]: the calm curtain at once, the trailer's streams looked up here, then played like a stream. */
    private fun startTrailer(i: Intent) {
        val id = i.getIntExtra(EXTRA_T_ID, 0)
        val type = i.getStringExtra(EXTRA_T_TYPE) ?: "movie"
        val name = i.getStringExtra(EXTRA_T_NAME).orEmpty()
        if (id <= 0) { close(); return }
        endSession()
        val t = Title(id, type, name, "", null, i.getStringExtra(EXTRA_T_BACKDROP), "", 0.0)
        trailerMode = true
        trailerGen++
        trailerTitle = t
        trailerVideo = i.getStringExtra(EXTRA_T_VIDEO)
        trailerPlaying = null
        trailerAdvances = 0
        // tmdbId 0 on purpose: nothing keyed by the title (markers, sources, history) attaches to a trailer.
        val req = PlaybackRequest(title = name, type = "movie", tmdbId = 0, backdrop = t.backdrop("w1280"),
            startMs = i.getLongExtra(EXTRA_T_START, 0L).coerceAtLeast(0L), autoplayNext = false, trailer = true)
        request = req
        curtain.show(req)
        updateMetadata()
        updatePlaybackState()
        resolveTrailer(req)
    }

    private fun resolveTrailer(req: PlaybackRequest, retry: Boolean = false) {
        val t = trailerTitle ?: return
        val gen = ++trailerGen
        firstFrame = false
        trailerPlaying = null
        handler.removeCallbacks(trailerTimeout)
        handler.postDelayed(trailerTimeout, TRAILER_TIMEOUT_MS)
        TrailerEngine.resolve(this, t, retry) { r ->
            if (gen != trailerGen || !trailerMode || isFinishing || isDestroyed) return@resolve
            if (r == null) {
                handler.removeCallbacks(trailerTimeout)
                curtain.showError("no trailer for ${t.key}")
                return@resolve
            }
            // Normally one small DASH manifest (1080p AVC + AAC, 720p/480p for slow networks) in the cache folder;
            // else the HLS master (H.264 variants only, also a local file) or a muxed MP4's link.
            val src = try {
                when (val plan = r.plan) {
                    is TrailerPick.Plan.Dash -> java.io.File(cacheDir, "trailer.mpd").let { f ->
                        f.writeText(plan.manifest(1080)); ExoEngine.Source(f.absolutePath, emptyMap(), "application/dash+xml")
                    }
                    is TrailerPick.Plan.Hls -> java.io.File(cacheDir, "trailer.m3u8").let { f ->
                        f.writeText(plan.master); ExoEngine.Source(f.absolutePath, emptyMap(), "application/x-mpegURL")
                    }
                    is TrailerPick.Plan.Progressive -> ExoEngine.Source(plan.url, emptyMap(), "video/mp4")
                }
            } catch (e: Exception) {
                handler.removeCallbacks(trailerTimeout)
                curtain.showError("trailer manifest: $e")
                return@resolve
            }
            trailerPlaying = r.videoKey
            // The background trailer's spot only applies to the same video.
            val start = if (trailerVideo != null && trailerVideo == r.videoKey) req.startMs else 0L
            trailerVideo = null
            startSession(req, src.url, src, start)
        }
    }

    /**
     * The trailer's video failed before its first frame: it's skipped from now on and the next of the title's
     * videos is looked up, behind the same curtain. False when there's nothing more to try here.
     */
    private fun nextTrailer(why: String): Boolean {
        val t = trailerTitle ?: return false
        val key = trailerPlaying ?: return false // still looking up: the lookup itself already tried the others
        TrailerEngine.failed(t, key, why)
        trailerPlaying = null
        if (trailerAdvances >= MAX_TRAILER_ADVANCES) return false
        trailerAdvances++
        val req = request?.takeIf { trailerMode } ?: return false
        stopComponents()
        if (exo.hasSource) exo.clear()
        val fresh = req.copy(startMs = 0)
        request = fresh
        if (!curtain.isShowing) curtain.show(fresh)
        resolveTrailer(fresh)
        return true
    }

    /** "Try Again" on the trailer's curtain: look it up afresh (the old stream links may have expired). */
    fun retryTrailer() {
        val req = request?.takeIf { trailerMode } ?: return close()
        trailerTitle?.let { TrailerEngine.forget(it) }
        trailerAdvances = 0
        stopComponents()
        if (exo.hasSource) exo.clear()
        val fresh = req.copy(startMs = 0)
        request = fresh
        curtain.show(fresh)
        resolveTrailer(fresh, retry = true)
    }

    private fun endTrailerMode() {
        if (!trailerMode) return
        trailerMode = false
        trailerTitle = null
        trailerVideo = null
        trailerPlaying = null
        trailerGen++
        handler.removeCallbacks(trailerTimeout)
    }

    // ---- session ----

    private fun startSession(req: PlaybackRequest?, raw: String, src: ExoEngine.Source, startMs: Long) {
        val wasWaiting = waiting
        stopComponents()
        waiting = false
        request = req
        rawUrl = raw
        firstFrame = false
        finished = false
        lastSaveAt = 0
        realWatchedMs = 0L
        lastTickPos = -1L
        sessionSrc = src
        sessionStartMs = startMs
        retriedFromTop = false
        noPictureReported = false
        // On the row for this title, and not for a later episode: an older episode started by mistake (a stale card, a
        // misplaced press) must play 90 s for real before it takes the show back from where the viewer is.
        onRow = req != null && req.tmdbId > 0 && (if (req.type == "episode") "tv:" else "movie:").let { p ->
            WatchHistory.recent(this).any { e ->
                e.key == p + req.tmdbId && !e.hidden &&
                    (req.type != "episode" || !WatchHistory.later(e, req.season ?: 0, req.episode ?: 0))
            }
        }
        creditsStartMs = null
        markersAsked = false
        released = false
        loadStartedAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(startWatch)
        if (!trailerMode && req != null && req.tmdbId > 0) handler.postDelayed(startWatch, START_WATCH_MS)
        // Not waiting (a retry after an error, say): a calm curtain until the first frame.
        // A trailer's curtain is already up from the lookup.
        if (trailerMode) { if (!curtain.isShowing) curtain.show(req) }
        else if (!wasWaiting || !curtain.isShowing) curtain.show(req)
        Log.w(TAG, "dream player: start '${req?.title ?: "unknown"}'${if (trailerMode) " (trailer)" else ""} at ${startMs / 1000}s, ${src.headers.size} headers, ${src.subtitles.size} subs")
        // Dream Subtitles fetched while the stream was resolved go into the media item now (no re-prepare later).
        val dl = dreamSubs.begin(req, raw, src.subtitles.isNotEmpty())
        exo.load(if (dl.isEmpty()) src else ExoEngine.Source(src.url, src.headers, src.mimeHint, src.subtitles + dl), startMs)
        overlay.start()
        // A trailer: just the controls (scrubber, pause/play). No skip markers, post-play or autoplay.
        if (!trailerMode) {
            // MKV chapters (intro/credits) are read from the stream itself by the skip markers.
            try { SkipMarkers.attachStreamUrl(raw, src.headers, req) } catch (e: Exception) { Log.w(TAG, "dream player: chapters: $e") }
            skip.start()
            postPlay.start()
            fullComponents = true
        }
        componentsOn = true
        updateMetadata()
    }

    private fun stopComponents() {
        if (!componentsOn) return
        componentsOn = false
        if (fullComponents) {
            fullComponents = false
            try { postPlay.stop() } catch (e: Exception) { Log.w(TAG, "dream player: postPlay.stop: $e") }
            try { skip.stop() } catch (e: Exception) { Log.w(TAG, "dream player: skip.stop: $e") }
        }
        try { overlay.stop() } catch (e: Exception) { Log.w(TAG, "dream player: overlay.stop: $e") }
    }

    /** Saves and stops whatever is playing (the screen stays). */
    private fun endSession() {
        handler.removeCallbacks(startWatch)
        saveProgress(force = true)
        stopComponents()
        if (exo.hasSource) exo.clear()
        subtitleView.setCues(emptyList())
        dreamSubs.end()
        waiting = false
    }

    private val engineListener = object : PlayerEngine.Listener {
        private var wasPlaying = false

        override fun onState() {
            val playing = exo.isPlaying
            val keepOn = playing || exo.isBuffering && exo.player?.playWhenReady == true
            if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (wasPlaying && !playing) saveProgress(force = true)
            wasPlaying = playing
            if (firstFrame && !trailerMode) Trakt.onPlayer(this@DreamPlayerActivity, request, playing, exo.positionMs, exo.durationMs) // returns at once
            if (!markersAsked && exo.durationMs > 0) askMarkers()
            updatePlaybackState()
        }

        override fun onProgress(positionMs: Long) {
            if (!firstFrame && !trailerMode && !noPictureReported && exo.isPlaying && positionMs - sessionStartMs > NO_PICTURE_MS) {
                noPictureReported = true
                Log.w(TAG, "dream player: sound for ${(positionMs - sessionStartMs) / 1000} s and no picture")
                // Through the engine, so the overlay hears it too (no Pause Screen over the error screen).
                exo.fail("The picture didn't start. Try another source.")
                return
            }
            // Real watching time: only small forward steps while playing count (a seek or a skip jumps and adds nothing).
            if (exo.isPlaying) {
                if (lastTickPos >= 0) (positionMs - lastTickPos).takeIf { it in 1 until 2_500 }?.let { realWatchedMs += it }
                lastTickPos = positionMs
            } else lastTickPos = positionMs
            val credits = creditsStartMs
            val d = exo.durationMs
            if (!finished && (credits != null && positionMs >= credits || d >= MIN_HISTORY_MS && positionMs >= d * WatchHistory.FINISHED)) onCreditsStarted()
            // Seeked well back after it counted as watched (Watch Again, a rewind): watching again, saved as it goes.
            else if (finished && positionMs < finishedAt - 60_000) { finished = false; Log.w(TAG, "dream player: back before the end, progress saved again") }
            saveProgress(force = false)
        }

        override fun onEnded() {
            // A trailer's end: straight back to the details page, where the user was.
            if (trailerMode) { close(); return }
            // Ended before its first picture (an empty, cut or fake file, or a saved spot past its end): a failure,
            // never "watched". From a saved spot the same link is tried once from the beginning; else the next source
            // quietly, and only then the error screen. The link is forgotten so the next Play takes another torrent.
            if (!firstFrame) {
                val req = request
                val src = sessionSrc
                val raw = rawUrl
                if (req != null && src != null && raw != null && sessionStartMs > 0 && !retriedFromTop) {
                    Log.w(TAG, "dream player: stream ended before its first frame from ${sessionStartMs / 1000} s; trying it from the beginning")
                    handler.post {
                        if (request !== req || isFinishing) return@post
                        startSession(req, raw, src, 0L)
                        retriedFromTop = true
                    }
                    return
                }
                Log.w(TAG, "dream player: stream ended before its first frame")
                req?.let { NativeResolver.forget(it) }
                if (silentFallback("stream ended before its first frame")) return
                curtain.showError("stream ended before its first frame", "This Title Isn't Available Right Now" to DAMAGED_TEXT)
                updatePlaybackState()
                return
            }
            finished = true
            finishedAt = exo.durationMs
            saveProgress(force = true)
            updatePlaybackState()
        }

        override fun onError(message: String) {
            if (trailerMode) {
                handler.removeCallbacks(trailerTimeout)
                // Before its first frame: that video is skipped and the next one tried, the curtain staying up.
                if (!firstFrame && nextTrailer(message)) return
                trailerTitle?.let { TrailerEngine.forget(it) } // mid-play: fresh links on Try Again
            }
            else request?.let { NativeResolver.forget(it) } // its link isn't handed out again; a retry takes the next torrent
            saveProgress(force = true)
            // A failed link quietly gives way to the next source from the same spot, behind the curtain (twice at most).
            if (!trailerMode && silentFallback("stream failed: $message")) return
            // The curtain shows one neutral title for every failure; the detail goes to the log.
            curtain.showError(message)
            updatePlaybackState()
        }
    }

    /** Real credits timestamp (not an estimate) for "finished": Continue Watching moves on to Up Next from there. */
    private fun askMarkers() {
        val req = request ?: return
        markersAsked = true
        if (req.tmdbId <= 0 || trailerMode) return
        val d = exo.durationMs
        try {
            SkipMarkers.get(this, req, d) { m ->
                if (request?.tmdbId == req.tmdbId && request?.episode == req.episode && !SkipMarkers.creditsEstimated(req)) {
                    creditsStartMs = m.creditsStartMs?.takeIf { it > d * 0.6 && it < d }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "dream player: markers: $e") }
    }

    /** The credits have started (PostPlay may call this too): counts as watched. */
    fun onCreditsStarted() {
        if (finished || !exo.hasSource || trailerMode) return
        finished = true
        finishedAt = exo.positionMs
        saveProgress(force = true)
    }

    private fun saveProgress(force: Boolean) {
        val req = request ?: return
        // Trailers are never Continue Watching material.
        if (trailerMode || req.trailer) return
        if (!::exo.isInitialized || !exo.hasSource || !firstFrame) return
        val d = exo.durationMs
        if (d < MIN_HISTORY_MS) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSaveAt < SAVE_EVERY_MS) return
        lastSaveAt = now
        val pos = if (finished) d else exo.positionMs
        val ep = req.type == "episode"
        // Not on Continue Watching yet: it joins only after real watching (skipping ahead to test doesn't count).
        if (onRow || realWatchedMs >= REAL_WATCH_MS) WatchHistory.record(this, WatchHistory.Sighting(
            // Dream OS's own: Continue Watching resumes these through the play flow.
            source = WatchHistory.DREAM,
            title = req.title,
            isEpisode = ep,
            season = if (ep) req.season ?: 0 else 0,
            episode = if (ep) req.episode ?: 0 else 0,
            episodeTitle = req.episodeTitle,
            year = req.year,
            tmdbId = req.tmdbId.takeIf { it > 0 },
            imdb = req.imdb,
            positionMs = pos,
            durationMs = d,
            player = req.player,
            exact = force, // pausing, leaving, the end: the exact spot, never skipped as "barely moved"
        ))
        Trakt.onPlayer(this, req, exo.isPlaying, pos, d) // progress for the Trakt scrobble; returns at once
    }

    // ---- PlayerHost ----

    override fun showSkip(label: String, onSkip: () -> Unit) = overlay.showSkip(label, onSkip)
    override fun hideSkip() = overlay.hideSkip()
    override fun setControlsVisible(visible: Boolean) = overlay.setControlsVisible(visible)

    override fun playNext(next: PlaybackRequest) {
        if (trailerMode) return // a trailer has no next
        if (!finished && exo.hasSource) finished = exo.durationMs > 0 && exo.positionMs > exo.durationMs * 0.8
        startWaiting(next) // saves this one, curtain up for the next
        PlayFlow.play(this, next)
    }

    override fun close() {
        if (isFinishing) return
        saveProgress(force = true)
        Trakt.onPlayerClosed(this)
        if (waiting) PlayFlow.cancel(this)
        stopComponents()
        // Back to Dream OS: its task goes in front first, then the player's task is removed entirely.
        returnToDreamOs(applicationContext)
        finishAndRemoveTask()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /** Dream OS's own task to the front: moved as-is (the details page stays), started only if it's gone. */
    private fun returnToDreamOs(ctx: Context) {
        val home = MainActivity::class.java.name
        val task = try {
            ctx.getSystemService(ActivityManager::class.java)?.appTasks?.firstOrNull { t ->
                val i = t.taskInfo
                i.baseActivity?.className == home || i.topActivity?.className == home
            }
        } catch (e: Exception) { null }
        try {
            if (task != null) task.moveToFront()
            else ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try { ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) {}
        }
    }

    /** The stream URL as it arrived (SourcePicker marks the source that's playing). */
    val streamUrl: String? get() = rawUrl

    /**
     * Change source (SourcePicker): saves the spot and puts the curtain up for this same title at
     * it; the picked stream arrives with this request and resumes there. Null if nothing is playing.
     */
    fun beginSourceSwitch(): PlaybackRequest? {
        if (trailerMode) return null
        val req = request ?: return null
        val at = if (exo.hasSource && firstFrame) exo.positionMs else req.startMs
        val again = req.copy(startMs = at)
        endSession()
        startWaiting(again)
        return again
    }

    /** Search All Sources (curtain / Change Source): one search with the source filters set aside, from here. */
    fun searchAllSources() {
        if (trailerMode) return retryTrailer()
        val req = request ?: return close()
        val at = if (exo.hasSource && firstFrame) exo.positionMs else req.startMs
        val again = req.copy(startMs = at)
        endSession()
        startWaiting(again)
        PlayFlow.resolve(this, again, loose = true)
    }

    /**
     * The iPhone's silent fallback: a link that fails (or ends before any picture) gives way to the next source from
     * the same spot, behind the loading curtain, with no error screen; [SILENT_TRIES] times per title, then the error
     * screen. A link the race unlocked alongside the failed one ([NativeResolver.nextLink]) plays with no lookup at
     * all; without one, Dream Core looks again. Posted: never inside the engine's own callback. True if it took over.
     */
    private fun silentFallback(why: String): Boolean {
        if (trailerMode) return false
        val req = request ?: return false
        if (req.tmdbId <= 0) return false
        val k = "${req.tmdbId}:${req.season}:${req.episode}"
        if (k != silentKey) { silentKey = k; silentTries = 0 }
        if (silentTries >= SILENT_TRIES) return false
        silentTries++
        val next = try { NativeResolver.nextLink(req) } catch (e: Exception) { null }
        if (next != null) {
            Log.w(TAG, "dream player: $why; switching to the next ready source quietly ($silentTries of $SILENT_TRIES, no lookup)")
            handler.post { if (request === req && !isFinishing) playAlternate(req, next) }
        } else {
            Log.w(TAG, "dream player: $why; switching to the next source quietly ($silentTries of $SILENT_TRIES)")
            handler.post { if (request === req && !isFinishing) tryAnotherSource() }
        }
        return true
    }

    /**
     * [f] (a link the race unlocked alongside the failed one) from the same spot, behind the curtain: its fast start
     * runs first ([StreamCache.fastStart], bounded), then it plays here, with no round trip through the play flow.
     */
    private fun playAlternate(req: PlaybackRequest, f: Found) {
        val at = if (exo.hasSource && firstFrame) exo.positionMs else req.startMs
        val again = req.copy(startMs = at)
        endSession()
        startWaiting(again)
        val app = applicationContext
        Net.runNow {
            NativeResolver.handing(f)
            try { StreamCache.fastStart(app, f.url, emptyMap(), again.startMs, PlayFlow.knownDuration(app, again), f.sizeBytes) } catch (e: Exception) {}
            handler.post {
                if (request !== again || !waiting || isFinishing) return@post
                startSession(again, f.url, ExoEngine.Source(f.url, emptyMap()), again.startMs)
            }
        }
    }

    /** [START_WATCH_MS] after a load with no first frame while another link is ready: that one plays instead of the error screen later. */
    private val startWatch = Runnable {
        val req = request
        if (firstFrame || trailerMode || waiting || isFinishing || req == null || req.tmdbId <= 0) return@Runnable
        if (!exo.hasSource || !NativeResolver.hasAlternate(req)) return@Runnable
        if (silentKey == "${req.tmdbId}:${req.season}:${req.episode}" && silentTries >= SILENT_TRIES) return@Runnable
        Log.w(TAG, "dream player: no first frame after ${START_WATCH_MS / 1000} s and another source is ready")
        NativeResolver.forget(req)
        silentFallback("no first frame after ${START_WATCH_MS / 1000} s")
    }

    fun tryAnotherSource() {
        if (trailerMode) return retryTrailer()
        val req = request ?: return close() // nothing to resolve again: back to Dream OS
        val at = if (exo.hasSource && firstFrame) exo.positionMs else req.startMs
        val again = req.copy(startMs = at)
        NativeResolver.forget(req) // "another" source: not the torrent that just played
        endSession()
        startWaiting(again)
        PlayFlow.resolve(this, again)
    }

    /** Subtitle look (the controls agent's subtitle settings). [textSizeFraction] is of the video height. */
    fun setSubtitleStyle(style: CaptionStyleCompat, textSizeFraction: Float = 0.0533f) {
        subtitleView.setStyle(style)
        subtitleView.setFractionalTextSize(textSizeFraction)
        subtitleView.setBottomPaddingFraction(0.075f)
    }

    /** Fit (default) or fill the screen, cropping the edges (zoom). */
    fun setVideoZoom(zoom: Boolean) {
        videoFrame.resizeMode = if (zoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    // ---- keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // The curtain (loading/errors) takes everything while it's up, BACK included.
        // "Change source" sheet (SourcePicker): its own keys, BACK included, while it's open (also over the curtain's error).
        if (SourcePicker.isOpen && safe { SourcePicker.onKey(event) }) return true
        if (curtain.isShowing && safe { curtain.onKey(event) }) return true
        // Instant Play lifts the curtain within a second of Play: an OK still meant for the page (a second press,
        // common when the first one only brought the page's UI back over its trailer) or one pressed before this
        // screen was up would pause the video.
        if (!waiting && isPlayKey(code) && (SystemClock.uptimeMillis() - curtainHiddenAt < 1500 || event.downTime < resumedAt)) return true
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
            return true
        }
        if (!waiting && componentsOn) {
            if (safe { postPlay.onKey(event) }) return true
            if (safe { overlay.onKey(event) }) return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && fallbackKey(code)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun isPlayKey(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE

    private inline fun safe(f: () -> Boolean): Boolean = try { f() } catch (e: Exception) { Log.w(TAG, "dream player: key handler: $e"); false }

    private fun onBack() {
        if (curtain.isShowing && safe { curtain.onBack() }) return
        if (waiting) { close(); return }
        if (componentsOn && (safe { postPlay.onBack() } || safe { overlay.onBack() })) return
        close()
    }

    /** Basic transport when no component took the key (media keys, OK = play/pause, left/right = 10 s). */
    private fun fallbackKey(code: Int): Boolean {
        if (waiting || !exo.hasSource) return false
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> togglePlay()
            KeyEvent.KEYCODE_MEDIA_PLAY -> exo.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> exo.pause()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_DPAD_RIGHT -> exo.seekTo(exo.positionMs + 10_000)
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_LEFT -> exo.seekTo(exo.positionMs - 10_000)
            KeyEvent.KEYCODE_MEDIA_STOP -> close()
            else -> return false
        }
        return true
    }

    private fun togglePlay() { if (exo.isPlaying) exo.pause() else exo.play() }

    // ---- media session (remote media keys, AirPods stem presses) ----

    private fun setupSession() {
        session = MediaSession(this, "DreamPlayer").apply {
            @Suppress("DEPRECATION")
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                // Not under the curtain: a failed source would start again behind its error screen.
                override fun onPlay() { if (!waiting && !curtain.isShowing) exo.play() }
                override fun onPause() { exo.pause() }
                override fun onStop() { exo.pause() }
                override fun onSeekTo(pos: Long) { exo.seekTo(pos) }
                override fun onFastForward() { exo.seekTo(exo.positionMs + 10_000) }
                override fun onRewind() { exo.seekTo(exo.positionMs - 10_000) }
                // AirPods double/triple press: skip 10 s like the on-screen buttons (not a different title).
                override fun onSkipToNext() { exo.seekTo(exo.positionMs + 10_000) }
                override fun onSkipToPrevious() { exo.seekTo(exo.positionMs - 10_000) }
            }, handler)
            isActive = true
        }
        updatePlaybackState()
    }

    private fun updatePlaybackState() {
        val s = session ?: return
        val state = when {
            waiting -> PlaybackState.STATE_CONNECTING
            exo.error != null -> PlaybackState.STATE_ERROR
            exo.isBuffering -> PlaybackState.STATE_BUFFERING
            exo.isPlaying -> PlaybackState.STATE_PLAYING
            exo.hasSource -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_NONE
        }
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
            .setState(state, exo.positionMs, if (exo.isPlaying) exo.player?.playbackParameters?.speed ?: 1f else 0f)
            .build())
        if (state == PlaybackState.STATE_PLAYING && exo.durationMs > 0 && metaDuration != exo.durationMs) updateMetadata()
    }

    private var metaDuration = -1L

    private fun updateMetadata() {
        val s = session ?: return
        val r = request
        metaDuration = exo.durationMs
        val sub = if (r?.trailer == true) "Trailer" else if (r?.type == "episode" && r.season != null && r.episode != null)
            "S${r.season} E${r.episode}" + (r.episodeTitle?.let { " · $it" } ?: "") else r?.year?.toString()
        s.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, r?.title ?: "Dream Player")
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, r?.title ?: "Dream Player")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, sub ?: "")
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, sub ?: "")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, metaDuration.coerceAtLeast(0))
            .build())
    }
}
