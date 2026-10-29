package com.firehome.launcher

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date
import java.util.Locale

/**
 * On-screen experience for Dream Player: title and clock up top, the timeline and a slim row of
 * actions at the bottom, ±10 s bubbles and hold-to-scrub when the controls are hidden, the Skip pill,
 * a calm buffering ring, a paused title card, short OSD toasts and the side sheets ([PlayerMenus]).
 *
 * Kept light for the stick's GPU (video plays underneath): hidden views are GONE, containers say
 * they don't overlap (fades never allocate offscreen layers), time text refreshes twice a second
 * only while the controls are up, and every animation ends (the ring spins only while buffering).
 *
 * Remote: OK play/pause (and shows the controls), Left/Right ±10 s (hold to scrub faster, commit on
 * release), Up/Down show the controls, Menu opens Audio & Subtitles, media keys work throughout.
 */
class PlayerOverlay(private val host: PlayerHost) : PlayerEngine.Listener, PlayerMenus.Callbacks, PlayerXRay.Callbacks {
    private val root = host.overlayRoot
    private val ctx = root.context
    private val kit = PremiumKit(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val engine get() = host.engine
    private val density = ctx.resources.displayMetrics.density
    private val Int.dp get() = (this * density).toInt()
    private val Float.dpf get() = this * density
    private val fit = ScreenFit.insets(ctx)
    private val padH = PremiumKit.EDGE.dp + fit[0]
    private val padTop = 30.dp + fit[1]
    private val padBottom = 30.dp + fit[1]
    /*
     * Dream Cinema's player ([PlayerLook]): scrims that melt into the Dream sky, the title logo, round
     * glass icon buttons that glow on focus (names as captions), a scrubber in the glow hue and a Skip pill
     * in the slowly fading rainbow.
     */
    private val pearlInk = PlayerLook.ink(ctx)
    private val iconBtn = CINE_BTN
    private val actIcon = CINE_ICON

    // ---- views (built once, on first start) ----
    private val dim = flatView().apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
    private val pausedCard = FlatLinear(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val stage = FlatFrame(ctx).apply { clipChildren = false; clipToPadding = false }
    private val controls = FlatFrame(ctx).apply { visibility = View.GONE; clipChildren = false; clipToPadding = false }
    private val topBar = FlatFrame(ctx)
    private val bottomBar = FlatLinear(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    // Wide caps tracking makes TextView measure the line short, so it never ellipsizes and keeps a
    // little end room (otherwise "SEASON 1 · EPISODE 4" clips to "EPISO…").
    private val kicker = kit.kicker("").apply { ellipsize = null; setPadding(0, 0, 12.dp, 0) }
    /** The same green "NOW PLAYING" as the loading curtain, above the title treatment. */
    private val nowPlaying = kit.label("NOW PLAYING", 16f, NOW_PLAYING_GREEN, kit.bold, Ui.TRACK_CAPS + 0.04f).apply {
        // One line, never wrapped: with the wide tracking a wrap-content box measured a hair short and broke
        // it after "NOW", and one line then showed only that word.
        setSingleLine(true)
        ellipsize = null
        setPadding(0, 0, 12.dp, 0)
    }
    private val titleView = kit.label("", Ui.T_TITLE, kit.text, kit.semibold, Ui.TRACK_TITLE)
    private val subView = kit.label("", Ui.T_SECTION, Ui.alpha(kit.text, 0.78f), kit.medium)
    private val clock = kit.label("", Ui.T_HEADLINE, kit.text, kit.semibold).apply { fontFeatureSettings = "tnum"; gravity = Gravity.END }
    private val endsAt = kit.label("", Ui.T_CAPTION, Ui.alpha(kit.text, 0.66f), kit.medium).apply { fontFeatureSettings = "tnum"; gravity = Gravity.END }
    private val scrubber = PlayerScrubber(ctx, kit)
    private val curTime = kit.label("0:00", Ui.T_CAPTION, Ui.alpha(kit.text, 0.86f), kit.medium).apply { fontFeatureSettings = "tnum" }
    private val remaining = kit.label("", Ui.T_CAPTION, Ui.alpha(kit.text, 0.62f), kit.medium).apply { fontFeatureSettings = "tnum" }
    private val actionsRow = FlatLinear(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
    private val playIcon = PlayerIcon(PlayerIcon.PLAY)
    private val pauseIcon = PlayerIcon(PlayerIcon.PAUSE)
    private lateinit var playBtn: ImageView
    private lateinit var btnEpisodes: TextView
    private lateinit var btnAudio: TextView
    private lateinit var btnInfo: TextView
    private lateinit var btnRestart: TextView
    private lateinit var btnSource: TextView
    /** Each action's full label (compact buttons show only their icon, the label as a caption on focus). */
    private val labels = LinkedHashMap<TextView, String>()
    private val compact = HashSet<TextView>()
    private var compactLevel = -1
    private val actionCaption = kit.label("", Ui.T_CAPTION, Ui.alpha(kit.text, 0.86f), kit.medium).apply {
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    private lateinit var btnNext: TextView
    private val preview = FlatLinear(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE }
    private val previewTime = kit.label("", 30f, kit.text, kit.semibold).apply { fontFeatureSettings = "tnum"; gravity = Gravity.CENTER }
    private val previewLabel = kit.label("", Ui.T_CAPTION, Ui.alpha(kit.text, 0.72f), kit.medium).apply { fontFeatureSettings = "tnum"; gravity = Gravity.CENTER }
    private val bubbleL = bubble()
    private val bubbleR = bubble()
    private val spinner = PlayerSpinner(ctx, kit).apply { visibility = View.GONE }
    private val skip = TextView(ctx)
    private val skipSheen = SheenDrawable(24.dp.toFloat())
    // Scrub preview frames ([PlayerThumbs]): the card above the time while scrubbing, once frames exist.
    private val thumbBox = FrameLayout(ctx).apply { visibility = View.GONE }
    private val thumbView = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val thumbGlow = CinemaStyle.glowDrawable(ctx, radiusDp = 10, haloDp = 8).apply { haloAlpha = 0.42f }
    private var thumbs: PlayerThumbs? = null
    private var thumbAt = -1L
    private var previewW = PREVIEW_W
    /** Cinema's Skip glyph (white, beside the label). */
    private val skipIcon = PlayerIcon(PlayerIcon.SKIP)
    // Cinema's Skip glass, at rest and focused, faintly tinted with Play's fading rainbow; its glow follows the hue.
    private val skipRest = GradientDrawable().apply { cornerRadius = 23.dp.toFloat() }
    private val skipFocus = GradientDrawable().apply { cornerRadius = 23.dp.toFloat() }
    private var skipGlow: CinemaGlow? = null
    private val skipShade by lazy { PlayerLook.shade(ctx) }
    /**
     * Steps the tint only while the pill is on screen (started by [showSkip], ended by [hideSkip]), about 12
     * times a second: a faint hue drifting over 2 s needs no more, and each step redraws the window over the
     * video (measured: per-frame steps kept the stick's UI rendering at 60 fps for the whole intro).
     */
    private var skipTintAt = 0L
    private val skipTint = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_000
        repeatCount = android.animation.ValueAnimator.INFINITE
        addUpdateListener {
            val now = SystemClock.uptimeMillis()
            if (now - skipTintAt >= 80) { skipTintAt = now; tintSkip() }
        }
    }

    private fun tintSkip() {
        val c = Rainbow.fadeColor()
        val edge = maxOf(1, 1.dp)
        skipRest.setColor(Ui.alpha(Ui.mix(skipShade, c, 0.22f), 0.74f))
        skipRest.setStroke(edge, Ui.alpha(Ui.mix(kit.text, c, 0.4f), 0.42f))
        skipFocus.setColor(Ui.mix(Ui.mix(skipShade, kit.text, 0.10f), c, 0.30f))
        skipFocus.setStroke(edge, Ui.alpha(Ui.mix(kit.text, c, 0.4f), 0.75f))
        skipGlow?.tint(c)
    }
    /** Logo of the title (Cinema's title block), else the title text. */
    private val titleLogo = ImageView(ctx).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START; visibility = View.GONE }
    private var titleLogoFor: String? = null
    /** The big play / pause glyph that flashes at the center on a toggle. */
    private val flash = ImageView(ctx).apply { visibility = View.GONE }
    private val flashPlay = PlayerIcon(PlayerIcon.PLAY)
    private val flashPause = PlayerIcon(PlayerIcon.PAUSE)
    private val toastView = kit.label("", Ui.T_LABEL, kit.text, kit.medium).apply {
        background = kit.glass(Ui.R_PILL, 170)
        setPadding(18.dp, 10.dp, 18.dp, 10.dp)
        visibility = View.GONE
    }
    // The Pause Screen (Netflix's "You're watching"): ~8 s into a pause with the remote at rest, the picture dims
    // and this card fades in at the lower left. Static views, GONE while playing; only short fades ever run.
    private val cardKicker = kit.kicker("You're Watching").apply {
        setTextColor(Ui.alpha(kit.text, 0.62f))
        ellipsize = null
        setPadding(0, 0, 12.dp, 0)
    }
    private val cardLogo = ImageView(ctx).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START }
    private val cardTitle = kit.label("", 40f, kit.text, kit.semibold, Ui.TRACK_TITLE, lines = 2)
    private val cardSub = kit.label("", Ui.T_SECTION, Ui.alpha(kit.text, 0.84f), kit.medium)
    private val cardSynopsis = kit.label("", Ui.T_BODY, Ui.alpha(kit.text, 0.74f), kit.font, lines = 4).apply { setLineSpacing(0f, 1.22f) }
    private val cardLeft = kit.label("", Ui.T_LABEL, Ui.alpha(kit.text, 0.62f), kit.medium).apply { fontFeatureSettings = "tnum" }
    /** What's playing ("4K", "Dolby Vision", "Dolby Atmos", "WEB-DL"): under the Pause Screen's S/E line and the controls' title. */
    private val cardBadges = StreamBadges.row(ctx)
    private val barBadges = StreamBadges.row(ctx)
    private var badgeKey: String? = null
    private var badgeLine = ""
    private val logBadges = Runnable { if (badgeLine.isNotEmpty()) android.util.Log.w("FireHome", "Player: playing $badgeLine") }
    private lateinit var menus: PlayerMenus
    /** X-Ray: the cast strip and person cards while paused ([PlayerXRay]). */
    private lateinit var xray: PlayerXRay

    // ---- state ----
    private var built = false
    private var started = false
    private var visible = false
    private var ready = false
    private var ended = false
    private var errored = false
    private var wasPaused = false
    private var cardUp = false
    /** An X-Ray person card paused playback (it resumes when the viewer goes back to the strip or closes X-Ray). */
    private var xrayPaused = false
    /** Subtitles hidden for a sheet ([syncSubtitles]). */
    private var subsHidden = false
    private var reqKey: String? = null
    private var showGen = 0
    private var lastAction: View? = null
    private var markers: Markers? = null
    private var info: PlayerInfo? = null
    private var infoWaiters: MutableList<(PlayerInfo?) -> Unit>? = null
    private var nextReq: PlaybackRequest? = null
    private var skipAction: (() -> Unit)? = null
    private var cardLogoFor: String? = null
    // scrubbing
    private var scrubTarget = -1L
    private var scrubFromHidden = false
    /** The latest scrub step (grows while a key is held) and its direction, for the readout. */
    private var scrubStep = STEP_TAP
    private var scrubDir = 1
    private var holdStart = 0L
    private var lastStep = 0L
    /** The largest step this hold reached (0 = no hold under way), so its release logs the one seek it makes. */
    private var holdMax = 0L
    private var tapBase = 0L
    private var tapOffset = 0L
    // track preferences
    private var audioApplied = false
    private var userAudio = false
    private var userText = false
    private val seenText = HashSet<String>()

    private val ticker = object : Runnable {
        override fun run() {
            if (!visible) return
            updateTimes()
            main.postDelayed(this, 500)
        }
    }
    private val idle = Runnable { onIdle() }
    private val commitTap = Runnable { commitTapSeek() }
    private val commitScrubRun = Runnable { commitScrub() }
    private val showSpinner = Runnable {
        if (started && ready && engine.isBuffering && !ended && !errored) {
            spinner.animate().cancel()
            spinner.visibility = View.VISIBLE
            spinner.alpha = 0f
            spinner.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
            spinner.begin()
        }
    }
    private val hideToast = Runnable {
        toastView.animate().alpha(0f).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT)
            .withEndAction { toastView.visibility = View.GONE }.start()
    }
    private val hideBubbles = Runnable { fadeOut(bubbleL); fadeOut(bubbleR) }

    // ======== public API ========

    /** A stream starts (the activity calls this once per session, the curtain still up). */
    fun start() {
        if (!built) build()
        if (started) return
        started = true
        reqKey = null
        checkRequest()
        engine.addListener(this)
        (engine as? ExoEngine)?.onFormat = { if (started) updateBadges() }
        badgeKey = null
        updateBadges()
        if (engine.isPlaying) markReady()
        PlayerPrefs.applyStyle(ctx, root, false)
        onState()
    }

    fun stop() {
        if (!started) return
        started = false
        engine.removeListener(this)
        (engine as? ExoEngine)?.onFormat = null
        main.removeCallbacksAndMessages(null)
        menus.closeNow()
        closeXRayNow()
        visible = false
        clockMin = Long.MIN_VALUE; endsMin = Long.MIN_VALUE
        cardUp = false
        ready = false
        scrubTarget = -1L
        tapOffset = 0L
        skipAction = null
        scrubber.setTarget(-1L)
        for (v in listOf(controls, pausedCard, dim, spinner, bubbleL, bubbleR, preview, toastView, skip, flash)) { v.animate().cancel(); v.visibility = View.GONE }
        spinner.end()
        skipTint.cancel()
        wasPaused = false
        PlayerPrefs.raiseForControls(ctx, root, false)
        stopThumbs()
    }

    /** Remote keys while playing; return true if consumed. */
    fun onKey(event: KeyEvent): Boolean {
        if (!started) return false
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        checkRequest()
        if (!ready && engine.isPlaying) markReady()
        // Curtain, error screen or post-play own the remote until the stream plays again.
        if (!ready || errored || ended) return false
        // X-Ray owns the remote while it's up (Play / Pause resumes and closes it).
        if (xray.isOpen) return xrayKey(event)
        // The Pause Screen: any key only dismisses it (Play / Pause and OK also resume; Up opens X-Ray).
        if (cardUp && !menus.isOpen && !isVolumeKey(code)) {
            if (first) dismissPauseScreen(code)
            return true
        }

        // Media keys work in every state.
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (first) { togglePlay(); if (!menus.isOpen) showControls(FOCUS_SCRUB) }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (first) { engine.play(); if (!menus.isOpen) showControls(FOCUS_SCRUB) }; return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (first) { engine.pause(); if (!menus.isOpen) showControls(FOCUS_SCRUB) }; return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (menus.isOpen) return true
                val dir = if (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) 1 else -1
                if (down) mediaScrub(dir, event.repeatCount)
                else if (scrubTarget >= 0 && scrubFromHidden) commitScrub()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (first) playNextEpisode(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (first) restart(); return true }
            KeyEvent.KEYCODE_CAPTIONS -> { if (first) toggleSubtitles(); return true }
        }
        if (menus.isOpen) return menuKey(event)
        if (pickerUp()) return false
        if (postPlayUp() && code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false
        return if (visible) visibleKey(event) else hiddenKey(event)
    }

    private fun isVolumeKey(code: Int) = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN ||
        code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_MUTE

    private fun isPlayPause(code: Int) = code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY ||
        code == KeyEvent.KEYCODE_HEADSETHOOK || code == KeyEvent.KEYCODE_SPACE

    /** A key on the Pause Screen: it goes; OK and Play / Pause resume, Up opens X-Ray, any other key brings the controls. */
    private fun dismissPauseScreen(code: Int) {
        android.util.Log.w("FireHome", "Player: pause screen dismissed by ${KeyEvent.keyCodeToString(code)}")
        when {
            code == KeyEvent.KEYCODE_DPAD_UP && openXRay() -> {}
            isOk(code) || isPlayPause(code) -> { hidePausedCard(); engine.play(); flashGlyph(true); main.postDelayed({ updatePlayIcon() }, 60) }
            else -> { hidePausedCard(); showControls(FOCUS_SCRUB) }
        }
    }

    /** Keys while X-Ray is up: Play / Pause toggles playback under it (X-Ray stays), other media keys wait, the rest is X-Ray's. */
    private fun xrayKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (isVolumeKey(code)) return false
        if (isPlayPause(code)) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                xrayPaused = false // the viewer's own choice now: Back from the card leaves it as it is
                togglePlay()
            }
            return true
        }
        xray.onKey(e)
        return true
    }

    /**
     * Opens X-Ray for what's playing, over the running (or paused) picture under a light dim: the Pause Screen
     * and the controls make way. It never pauses by itself (only a person card does). False when there's
     * nobody to show or something else is on screen.
     */
    private fun openXRay(): Boolean {
        val r = host.request ?: return false
        if (!built || xray.isOpen || !xray.canOpen(r) || !ready || ended || errored) return false
        if (menus.isOpen || pickerUp() || postPlayUp()) return false
        cancelScrub()
        main.removeCallbacks(idle)
        xrayPaused = false
        xray.open(r)
        hidePausedCard()
        hideControls()
        setDim(restDim())
        syncSubtitles()
        return true
    }

    /** X-Ray closed with Back: playback carries on as it was (the paused player shows its controls again). */
    override fun xrayClosed() {
        if (xrayPaused) { xrayPaused = false; engine.play(); main.postDelayed({ updatePlayIcon() }, 60) }
        setDim(restDim())
        syncSubtitles()
        if (isPaused()) showControls(FOCUS_SCRUB) else bumpIdle()
    }

    /** A person card opened from X-Ray: pause for it (and remember, to resume on the way back); back on the strip: resume. */
    override fun xrayCard(open: Boolean) {
        if (open) {
            if (engine.isPlaying || engine.isBuffering && !wasPaused) {
                engine.pause()
                xrayPaused = true
                main.postDelayed({ updatePlayIcon() }, 60)
                android.util.Log.w("FireHome", "Player: X-Ray person card, paused")
            }
        } else if (xrayPaused) {
            xrayPaused = false
            engine.play()
            main.postDelayed({ updatePlayIcon() }, 60)
            android.util.Log.w("FireHome", "Player: X-Ray back to the cast, playing again")
        }
        setDim(restDim())
    }

    /** Cast & Characters (inside Info): X-Ray over the picture (playback keeps going). */
    override fun openCast() {
        if (!openXRay()) toast("Cast details aren't available for this title")
    }

    override fun canOpenCast(): Boolean = xray.canOpen(host.request)

    /** The dim the picture rests at: deep under an X-Ray person card, light under the X-Ray strip or while paused, none while playing. */
    private fun restDim(): Float = when {
        built && xray.isOpen && xray.onCard -> DIM_CARD
        built && xray.isOpen -> DIM_XRAY
        isPaused() -> DIM_PAUSED
        else -> 0f
    }

    /**
     * Subtitles never draw through a player sheet: hidden while X-Ray (strip or card), a menu sheet (Episodes,
     * Audio & Subtitles, Info), Change Source or a post-play card is up, and back as soon as none is.
     * Called on each open / close (and when another component's panel comes or goes); no polling.
     */
    private fun syncSubtitles() {
        if (!built) return
        val hide = started && (xray.isOpen || menus.isOpen || pickerUp() || panelUp(postPlayPanel))
        if (hide == subsHidden) return
        subsHidden = hide
        PlayerPrefs.hideSubtitles(root, hide)
    }

    /** X-Ray gone at once (a new stream, the end, an error, Change Source): no resume owed, subtitles re-checked. */
    private fun closeXRayNow() {
        if (!built) return
        xray.closeNow()
        xrayPaused = false
        syncSubtitles()
    }

    /** [ref]'s panel is still in the overlay and showing (any alpha: one fading in counts). */
    private fun panelUp(ref: java.lang.ref.WeakReference<View>?): Boolean {
        val v = ref?.get() ?: return false
        return v.parent === root && v.visibility == View.VISIBLE
    }

    /** Back: close the topmost menu/controls first; return false to let the player exit. */
    fun onBack(): Boolean {
        if (!started) return false
        if (pickerUp()) return false
        if (xray.isOpen) { xray.back(); return true }
        if (menus.isOpen) { menus.close(); return true }
        if (scrubTarget >= 0) { cancelScrub(); bumpIdle(); return true }
        if (cardUp) { dismissPauseScreen(KeyEvent.KEYCODE_BACK); return true }
        if (visible) { hideControls(); return true }
        return false
    }

    fun showSkip(label: String, onSkip: () -> Unit) {
        if (!built) build()
        skipAction = onSkip
        skip.text = label
        if (skip.visibility != View.VISIBLE || skip.alpha < 1f) {
            skip.animate().cancel()
            if (skip.visibility != View.VISIBLE) {
                skip.alpha = 0f
                skip.translationX = 28.dp.toFloat()
            }
            skip.visibility = View.VISIBLE
            if (!skipTint.isStarted) skipTint.start()
            skip.translationY = skipLift()
            skip.animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        }
        // Netflix-style: ready to press without bringing up the controls (unless the user is busy).
        if (!menus.isOpen && scrubTarget < 0 && !cardUp && !xray.isOpen) skip.post { if (skip.visibility == View.VISIBLE && skipAction != null) skip.requestFocus() }
    }

    fun hideSkip() {
        if (!built || skip.visibility != View.VISIBLE) return
        val hadFocus = skip.isFocused
        skipAction = null
        if (hadFocus && visible) scrubber.requestFocus()
        skip.animate().alpha(0f).translationX(20.dp.toFloat()).setStartDelay(0).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT)
            .withEndAction { if (skipAction == null) { skip.visibility = View.GONE; skip.translationX = 0f; skipTint.cancel() } }.start()
        if (hadFocus && !visible) skip.clearFocus()
    }

    fun setControlsVisible(visible: Boolean) {
        if (!started) return
        if (visible) { if (!ready && engine.durationMs > 0) markReady(); showControls(FOCUS_SCRUB) }
        else {
            hideControls()
            hidePausedCard()
            menus.closeNow()
            if (built) { xray.closeNow(); xrayPaused = false }
            // Post-play hides us right before adding its card: remember that card so nothing of ours covers it.
            root.post { notePostPlayPanel() }
        }
    }

    private var postPlayPanel: java.lang.ref.WeakReference<View>? = null
    private var pickerPanel: java.lang.ref.WeakReference<View>? = null

    /** Change source: the play flow's picker takes over; we hide and stand aside while its view is up. */
    private fun changeSource() {
        if (!canChangeSource()) { android.util.Log.w("DreamPlayer", "change source: not available for this stream (trailer, or no provider and no TMDB id)"); return }
        cancelScrub()
        hidePausedCard()
        menus.closeNow()
        closeXRayNow()
        hideControls()
        main.removeCallbacks(idle)
        val before = (0 until root.childCount).map { root.getChildAt(it) }.toHashSet()
        SourcePicker.open(host)
        root.post {
            for (i in root.childCount - 1 downTo 0) {
                val c = root.getChildAt(i)
                if (c !in before && c.visibility == View.VISIBLE) { pickerPanel = java.lang.ref.WeakReference(c); syncSubtitles(); return@post }
            }
        }
    }

    /** Only when the play flow can resolve the title again. */
    private fun canChangeSource(): Boolean {
        val r = host.request ?: return false
        if (r.trailer) return false
        return r.player != null || r.tmdbId > 0
    }

    private fun pickerUp(): Boolean {
        val v = pickerPanel?.get() ?: return false
        if (v.parent === root && v.visibility == View.VISIBLE) return true
        pickerPanel = null
        return false
    }

    private fun notePostPlayPanel() {
        val mine = setOf(dim, pausedCard, stage, menus.layerView, xray.layerView, toastView)
        for (i in root.childCount - 1 downTo 0) {
            val c = root.getChildAt(i)
            if (c !in mine && c.visibility == View.VISIBLE) {
                postPlayPanel = java.lang.ref.WeakReference(c)
                cancelScrub()
                hidePausedCard()
                setDim(0f)
                syncSubtitles()
                return
            }
        }
    }

    /** A post-play card (Up next, Still watching, More like this) is on screen: it owns OK/Back, we stay hidden. */
    private fun postPlayUp(): Boolean {
        val v = postPlayPanel?.get() ?: return false
        if (v.parent === root && v.visibility == View.VISIBLE && v.alpha > 0.05f) return true
        postPlayPanel = null
        return false
    }

    // ======== build ========

    private fun build() {
        built = true
        // Another component's panel (post-play, Change Source) leaving the overlay: the subtitles may come back.
        root.setOnHierarchyChangeListener(object : android.view.ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {}
            override fun onChildViewRemoved(parent: View?, child: View?) { if (started) root.post { syncSubtitles() } }
        })
        root.addView(dim, MATCH_PARENT, MATCH_PARENT)
        buildPausedCard()
        root.addView(stage, MATCH_PARENT, MATCH_PARENT)
        buildControls()
        stage.addView(controls, MATCH_PARENT, MATCH_PARENT)
        stage.addView(bubbleL, FrameLayout.LayoutParams(88.dp, 88.dp, Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = padH + 90.dp })
        stage.addView(bubbleR, FrameLayout.LayoutParams(88.dp, 88.dp, Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = padH + 90.dp })
        stage.addView(spinner, FrameLayout.LayoutParams(64.dp, 64.dp, Gravity.CENTER))
        flash.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Ui.alpha(PlayerLook.shade(ctx), 0.52f))
                setStroke(maxOf(1, 1.dp), Ui.alpha(kit.text, 0.14f))
            }
            val p = 28.dp
            setPadding(p, p, p, p)
            imageTintList = ColorStateList.valueOf(kit.text)
        }
        stage.addView(flash, FrameLayout.LayoutParams(FLASH.dp, FLASH.dp, Gravity.CENTER))
        buildSkip()
        menus = PlayerMenus(host, kit, root, this)
        xray = PlayerXRay(host, kit, root, padH, padBottom, this)
        root.addView(toastView, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = padTop + 4.dp })
    }

    private fun buildControls() {
        // Soft scrims, top and bottom only: the middle of the picture stays untouched.
        // Cinema: the Dream sky itself (the theme background, cached at quarter size), melting into the picture.
        val ck = CinemaKit(ctx)
        val w = ctx.resources.displayMetrics.widthPixels
        val sh = ctx.resources.displayMetrics.heightPixels
        val topH = 230.dp
        val botH = 340.dp
        controls.addView(flatView().apply {
            background = ck.bgFade(w, topH, 0, "player-top") { _, v -> 0.80f * CinemaKit.ease(1f - v) }
        }, FrameLayout.LayoutParams(MATCH_PARENT, topH, Gravity.TOP))
        controls.addView(flatView().apply {
            background = ck.bgFade(w, botH, sh - botH, "player-bottom") { _, v -> 0.94f * CinemaKit.ease(v * 1.15f) }
        }, FrameLayout.LayoutParams(MATCH_PARENT, botH, Gravity.BOTTOM))

        // Top: title treatment (left), clock and "ends at" (right).
        topBar.setPadding(padH, padTop, padH, 0)
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        left.addView(nowPlaying)
        left.addView(kicker, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        titleLogo.maxWidth = 380.dp
        titleLogo.maxHeight = 92.dp
        left.addView(titleLogo, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        titleView.textSize = 32f
        subView.textSize = 17f
        left.addView(titleView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 12.dp })
        left.addView(subView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        left.addView(barBadges, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 12.dp })
        val dm = ctx.resources.displayMetrics
        titleView.maxWidth = (dm.widthPixels * 0.62f).toInt()
        subView.maxWidth = (dm.widthPixels * 0.62f).toInt()
        topBar.addView(left, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START))
        val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        // Its own width (a vertical LinearLayout would stretch it to the "Ends at" line and cut "12:01 AM").
        right.addView(clock, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        clock.ellipsize = null
        right.addView(endsAt, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 5.dp })
        topBar.addView(right, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END))
        controls.addView(topBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        // Bottom: timeline, times, actions.
        // Cinema keeps room under the round buttons for the focused one's caption.
        bottomBar.setPadding(padH, 0, padH, padBottom + 14.dp)
        // The track starts on the page edge line (the view keeps room for the thumb beyond it).
        bottomBar.addView(scrubber, LinearLayout.LayoutParams(MATCH_PARENT, 30.dp).apply { marginStart = (-9).dp; marginEnd = (-9).dp })
        curTime.textSize = Ui.T_LABEL
        remaining.textSize = Ui.T_LABEL
        val times = FrameLayout(ctx)
        times.addView(curTime, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START))
        times.addView(remaining, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END))
        bottomBar.addView(times, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 4.dp })

        playBtn = ImageView(ctx).apply {
            setImageDrawable(pauseIcon)
            val pad = 13.dp
            setPadding(pad, pad, pad, pad)
            imageTintList = ColorStateList(arrayOf(FOCUSED, IntArray(0)), intArrayOf(pearlInk, kit.text))
            isFocusable = true
            isClickable = true
            id = View.generateViewId()
            contentDescription = "Play or pause"
            setOnClickListener { togglePlay() }
            val (bg, glow) = PlayerLook.control(ctx, CINE_BTN / 2)
            background = bg
            setOnFocusChangeListener { v, has -> PlayerLook.focus(v, glow, has); if (has) lastAction = v }
        }
        val playSize = CINE_BTN.dp
        actionsRow.addView(playBtn, LinearLayout.LayoutParams(playSize, playSize).apply { marginEnd = CINE_GAP.dp })
        btnEpisodes = action(PlayerIcon.EPISODES, "Episodes") { openSheet("episodes") }
        btnAudio = action(PlayerIcon.SUBTITLES, "Audio & Subtitles") { openSheet("audio") }
        btnSource = action(PlayerIcon.SOURCE, "Change Source") { changeSource() }
        btnInfo = action(PlayerIcon.INFO, "Info") { openSheet("info") }
        btnRestart = action(PlayerIcon.RESTART, "Start Over") { restart() }
        // Cast & Characters lives inside Info (PlayerMenus.openInfo). Start Over sits alone in the far right
        // corner, after Next Episode (shown once past the first minute).
        for (b in listOf(btnEpisodes, btnAudio, btnSource, btnInfo)) actionsRow.addView(b)
        actionsRow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        btnNext = action(PlayerIcon.NEXT, "Next Episode") { playNextEpisode() }
        actionsRow.addView(btnNext)
        (btnRestart.layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        actionsRow.addView(btnRestart)
        bottomBar.addView(actionsRow, LinearLayout.LayoutParams(MATCH_PARENT, 56.dp).apply { topMargin = 14.dp })
        controls.addView(bottomBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        controls.addView(actionCaption, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START))
        // Keep the row on one line at any width or Screen fit: quieter actions drop to icons first.
        actionsRow.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol) { compactLevel = -1; actionsRow.post { fitActions() } }
        }

        // Scrub readout: big time on a glass capsule riding above the thumb.
        preview.background = PlayerLook.sheet(ctx, Ui.R_LG)
        toastView.background = PlayerLook.sheet(ctx, 22)
        preview.setPadding(14.dp, 10.dp, 14.dp, 10.dp)
        // The preview frame ([PlayerThumbs]) sits on top once the first one exists: a rounded 16:9 card with a glow edge.
        preview.clipChildren = false
        preview.clipToPadding = false
        thumbView.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, view.width, view.height, 10f.dpf)
        }
        thumbView.clipToOutline = true
        thumbView.setBackgroundColor(Ui.alpha(kit.text, 0.06f))
        thumbBox.background = thumbGlow
        thumbBox.addView(thumbView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        preview.addView(thumbBox, LinearLayout.LayoutParams(THUMB_W.dp, THUMB_H.dp).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = 10.dp })
        preview.addView(previewTime, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        preview.addView(previewLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 4.dp })
        controls.addView(preview, FrameLayout.LayoutParams(PREVIEW_W.dp, WRAP_CONTENT, Gravity.TOP or Gravity.START))
    }

    // ======== preview frames ========

    /**
     * Starts making preview frames for this stream ([PlayerThumbs]): plain HTTP(S) files only (not HLS / DASH
     * or trailers), a few seconds after the first frames so the start of playback has the network to itself.
     */
    private fun startThumbs() {
        stopThumbs()
        val exo = engine as? ExoEngine ?: return
        val r = host.request ?: return
        val u = exo.url
        val path = u.lowercase(Locale.US).substringBefore('?')
        if (!PlayerThumbs.ENABLED || r.trailer || !u.startsWith("http") || engine.durationMs <= 0 || path.endsWith(".m3u8") || path.endsWith(".mpd")) return
        val th = PlayerThumbs(u, exo.requestHeaders, engine.durationMs)
        th.onFrame = { at, b -> onThumb(at, b) }
        th.around = engine.positionMs
        thumbs = th
        main.postDelayed({ if (thumbs === th) th.start() }, THUMBS_DELAY)
    }

    private fun stopThumbs() {
        thumbs?.stop()
        thumbs = null
        thumbAt = -1L
        if (!built) return
        thumbView.setImageDrawable(null)
        thumbBox.visibility = View.GONE
        setPreviewWidth(PREVIEW_W)
    }

    /** The worker's view of the buffer: comfortable unless the player is buffering or running low while playing. */
    private fun feedThumbs() {
        val th = thumbs ?: return
        val p = engine.positionMs
        th.around = p
        val ok = !engine.isBuffering && (!engine.isPlaying || engine.bufferedMs - p > THUMBS_BUFFER)
        if (ok != th.healthy) { th.healthy = ok; if (ok) th.wake() }
    }

    /** While scrubbing: the nearest frame made so far at once, and the target's own one asked for. */
    private fun previewFrame(t: Long) {
        val th = thumbs ?: return
        th.nearest(t)?.let { (at, b) -> if (b.width > 1) showThumb(at, b) }
        th.want(t)
    }

    private fun onThumb(at: Long, b: Bitmap) {
        val t = scrubTarget
        if (t < 0 || b.width <= 1) return
        if (thumbAt < 0 || kotlin.math.abs(at - t) < kotlin.math.abs(thumbAt - t)) showThumb(at, b)
    }

    private fun showThumb(at: Long, b: Bitmap) {
        if (at == thumbAt) return
        thumbAt = at
        thumbView.setImageBitmap(b)
        if (thumbBox.visibility != View.VISIBLE) {
            thumbBox.visibility = View.VISIBLE
            setPreviewWidth(THUMB_W + 20)
            preview.post { updatePreview() }
        }
    }

    private fun setPreviewWidth(wDp: Int) {
        if (previewW == wDp) return
        previewW = wDp
        (preview.layoutParams as? FrameLayout.LayoutParams)?.let { it.width = wDp.dp; preview.layoutParams = it }
    }

    private fun buildPausedCard() {
        pausedCard.addView(cardKicker)
        pausedCard.addView(cardLogo, LinearLayout.LayoutParams(WRAP_CONTENT, 96.dp).apply { topMargin = 12.dp })
        cardLogo.maxWidth = 420.dp
        pausedCard.addView(cardTitle, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        pausedCard.addView(cardSub, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 12.dp })
        pausedCard.addView(cardBadges, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        pausedCard.addView(cardSynopsis, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        pausedCard.addView(cardLeft, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 16.dp })
        val w = (ctx.resources.displayMetrics.widthPixels * 0.5f).toInt()
        cardSynopsis.maxWidth = w
        cardTitle.maxWidth = w
        root.addView(pausedCard, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = padH
            bottomMargin = padBottom + 36.dp
        })
    }

    private fun buildSkip() {
        skip.apply {
            textSize = Ui.T_BODY
            typeface = kit.semibold
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            val rest = Ui.onColor(ctx, kit.btnPrimary)
            val colors = ColorStateList(arrayOf(FOCUSED, IntArray(0)), intArrayOf(kit.onSelect, rest))
            setTextColor(colors)
            val icon = PlayerIcon(PlayerIcon.SKIP).apply { setBounds(0, 0, 16.dp, 16.dp) }
            setCompoundDrawables(null, null, icon, null)
            compoundDrawableTintList = colors
            compoundDrawablePadding = 10.dp
            setPadding(24.dp, 0, 22.dp, 0)
            background = StateListDrawable().apply {
                addState(FOCUSED, Ui.focusFill(ctx, kit.select, Ui.R_PILL))
                addState(IntArray(0), Ui.rounded(ctx, kit.btnPrimary, Ui.R_PILL))
                setEnterFadeDuration(Ui.FOCUS_IN.toInt())
                setExitFadeDuration(Ui.FOCUS_OUT.toInt())
            }
            foreground = skipSheen
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                outlineSpotShadowColor = Ui.mix(kit.select, kit.blush, 0.5f)
                outlineAmbientShadowColor = Ui.mix(kit.select, kit.blush, 0.5f)
            }
            isFocusable = true
            isClickable = true
            id = View.generateViewId()
            visibility = View.GONE
            setOnClickListener { triggerSkip() }
            setOnFocusChangeListener { v, has ->
                Ui.lift(v, has, Ui.LIFT_PILL, 8)
                if (has) skipSheen.sweep() else skipSheen.cancel()
            }
            // Cinema, Netflix / Apple TV restraint: one compact pill of dark Dream glass with a hairline edge,
            // the label in white with a small skip glyph; focused, the rainbow glow and a gentle lift. Static
            // drawables only, so nothing redraws while it waits over the picture.
            textSize = 16f
            setTextColor(kit.text)
            skipIcon.setBounds(0, 0, 14.dp, 14.dp)
            skipIcon.setTint(kit.text)
            setCompoundDrawables(null, null, skipIcon, null)
            compoundDrawableTintList = null
            compoundDrawablePadding = 12.dp
            foreground = null
            setPadding(24.dp, 0, 22.dp, 0)
            // The glass takes a faint tint of Play's fading rainbow (same clock: [Rainbow.fadeColor]), stepped by
            // [skipTint] only while the pill shows. Focused it is solid (the halo band would show through glass).
            val (_, glow) = PlayerLook.control(ctx, 23)
            glow.haloAlpha = 0.38f
            skipGlow = glow
            background = android.graphics.drawable.LayerDrawable(arrayOf(glow, StateListDrawable().apply {
                addState(FOCUSED, skipFocus)
                addState(IntArray(0), skipRest)
                setEnterFadeDuration(Ui.FOCUS_IN.toInt())
                setExitFadeDuration(Ui.FOCUS_OUT.toInt())
            }))
            tintSkip()
            setOnFocusChangeListener { v, has -> PlayerLook.focus(v, glow, has, 1.06f) }
        }
        stage.addView(skip, FrameLayout.LayoutParams(WRAP_CONTENT, 46.dp, Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = padH
            bottomMargin = padBottom + 6.dp
        })
    }

    /** Quiet glass capsule: icon + label, the Dream glow and a light sweep on focus. */
    private fun action(shape: Int, label: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        labels[this] = label
        contentDescription = label
        text = label
        textSize = Ui.T_LABEL
        typeface = kit.semibold
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        val colors = ColorStateList(arrayOf(FOCUSED, IntArray(0)), intArrayOf(pearlInk, Ui.alpha(kit.text, 0.94f)))
        setTextColor(colors)
        val icon = PlayerIcon(shape).apply { setBounds(0, 0, actIcon.dp, actIcon.dp) }
        setCompoundDrawables(icon, null, null, null)
        compoundDrawableTintList = colors
        compoundDrawablePadding = 8.dp
        setPadding(15.dp, 0, 18.dp, 0)
        isFocusable = true
        isClickable = true
        id = View.generateViewId()
        setOnClickListener { onClick() }
        // Cinema: a round glass button (Next Episode a pill), pearl and glowing when focused, its name as a caption.
        textSize = 15f
        val (bg, glow) = PlayerLook.control(ctx, CINE_BTN / 2)
        background = bg
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, CINE_BTN.dp).apply { marginEnd = CINE_GAP.dp }
        setOnFocusChangeListener { v, has ->
            PlayerLook.focus(v, glow, has)
            if (has) lastAction = v
            if (has && v in compact) showCaption(v as TextView) else if (!has) hideCaption()
        }
    }

    /** Every action is a round icon, named by its caption on focus; Next Episode keeps its label. */
    private fun fitActions() {
        if (!built) return
        val avail = actionsRow.width - actionsRow.paddingLeft - actionsRow.paddingRight
        if (avail <= 0) return
        if (compactLevel == 0) return
        compactLevel = 0
        for (b in labels.keys) setCompact(b, b !== btnNext)
    }

    private fun setCompact(b: TextView, on: Boolean) {
        val was = b in compact
        if (on) compact += b else compact -= b
        val want = if (on) "" else labels[b].orEmpty()
        if (b.text.toString() != want) b.text = want
        if (was == on && b.layoutParams?.width == (if (on) iconBtn.dp else WRAP_CONTENT)) return
        b.compoundDrawablePadding = if (on) 0 else 10.dp
        val side = (iconBtn - actIcon) / 2f * density
        if (on) b.setPadding(kotlin.math.ceil(side).toInt(), 0, side.toInt(), 0)
        else b.setPadding(18.dp, 0, 24.dp, 0)
        (b.layoutParams as? LinearLayout.LayoutParams)?.let { it.width = if (on) iconBtn.dp else WRAP_CONTENT; b.layoutParams = it }
        if (b.isFocused) { if (on) showCaption(b) else hideCaption() }
    }

    /** A compact action's name, small and quiet, just under the focused icon. */
    private fun showCaption(b: TextView) {
        actionCaption.text = labels[b]
        actionCaption.animate().cancel()
        if (actionCaption.visibility != View.VISIBLE) { actionCaption.alpha = 0f; actionCaption.visibility = View.VISIBLE }
        actionCaption.post {
            if (!b.isFocused) return@post
            val cx = bottomBar.left + actionsRow.left + b.left + b.width / 2f
            val maxX = (controls.width - padH - actionCaption.width).toFloat().coerceAtLeast(padH.toFloat())
            actionCaption.x = (cx - actionCaption.width / 2f).coerceIn(padH.toFloat(), maxX)
            actionCaption.y = bottomBar.top + actionsRow.top + b.bottom + 12f.dpf
            actionCaption.animate().alpha(1f).setDuration(Ui.FOCUS_IN).setInterpolator(Ui.EASE).start()
        }
    }

    private fun hideCaption() {
        if (actionCaption.visibility != View.VISIBLE) return
        actionCaption.animate().cancel()
        actionCaption.animate().alpha(0f).setDuration(Ui.FOCUS_OUT).setInterpolator(Ui.EXIT)
            .withEndAction { if (actionCaption.alpha == 0f) actionCaption.visibility = View.GONE }.start()
    }

    private fun bubble() = kit.label("", Ui.T_SECTION, kit.text, kit.semibold).apply {
        gravity = Gravity.CENTER
        fontFeatureSettings = "tnum"
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Ui.alpha(PlayerLook.shade(ctx), 0.55f))
            setStroke(maxOf(1, 1.dp), Ui.alpha(kit.text, 0.16f))
        }
        visibility = View.GONE
    }

    private fun flatView() = object : View(ctx) { override fun hasOverlappingRendering() = false }

    // ======== controls visibility ========

    private fun showControls(focus: Int) {
        if (!started || !ready || ended || errored || postPlayUp()) return
        // X-Ray stays as it is (e.g. back from a title page it opened: the activity asks for the controls).
        if (xray.isOpen) return
        if (cardUp) hidePausedCard()
        refreshTitle()
        updatePlayIcon()
        updateActions()
        updateTimes()
        if (!visible) {
            visible = true
            val g = ++showGen
            controls.animate().cancel()
            controls.visibility = View.VISIBLE
            controls.alpha = 0f
            topBar.translationY = (-10).dp.toFloat()
            bottomBar.translationY = 14.dp.toFloat()
            controls.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
            topBar.animate().translationY(0f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
            bottomBar.animate().translationY(0f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
            PlayerPrefs.raiseForControls(ctx, root, true)
            main.removeCallbacks(ticker)
            main.postDelayed(ticker, 500)
            if (skip.visibility == View.VISIBLE) bottomBar.post { if (g == showGen && visible) moveSkip() }
        }
        val skipOn = skip.visibility == View.VISIBLE && skipAction != null
        val current = root.findFocus()?.takeIf { isInStage(it) && it !== skip }
        val target: View = when {
            focus == FOCUS_KEEP && current != null -> current
            focus == FOCUS_PLAY -> playBtn
            focus == FOCUS_SCRUB && skipOn && scrubTarget < 0 -> skip
            else -> scrubber
        }
        if (!target.isFocused) target.requestFocus()
        PlayerLook.resume() // a focused control kept its border while hidden: keep its hue moving again
        bumpIdle()
    }

    private fun showControlsFocusing(v: View) {
        showControls(FOCUS_KEEP)
        if (v.visibility == View.VISIBLE) v.requestFocus()
        bumpIdle()
    }

    private fun hideControls() {
        if (!visible) return
        visible = false
        main.removeCallbacks(ticker)
        clockMin = Long.MIN_VALUE; endsMin = Long.MIN_VALUE // the clock settings may change while hidden
        if (scrubTarget >= 0) cancelScrub()
        val g = ++showGen
        controls.animate().alpha(0f).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT)
            .withEndAction { if (g == showGen && !visible) controls.visibility = View.GONE }.start()
        topBar.animate().translationY((-8).dp.toFloat()).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT).start()
        bottomBar.animate().translationY(10.dp.toFloat()).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT).start()
        PlayerPrefs.raiseForControls(ctx, root, false)
        moveSkip()
        if (skip.visibility == View.VISIBLE && skipAction != null && !menus.isOpen && !xray.isOpen) skip.requestFocus()
    }

    /** The Skip pill rides above the bottom bar while the controls are up. */
    private fun skipLift(): Float = if (visible && bottomBar.height > 0) -(bottomBar.height - padBottom + 10.dp).toFloat() else 0f

    private fun moveSkip() {
        if (skip.visibility != View.VISIBLE) return
        skip.animate().translationY(skipLift()).setStartDelay(0).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
    }

    private fun bumpIdle() {
        main.removeCallbacks(idle)
        if (!started || !ready || ended || errored) return
        main.postDelayed(idle, if (isPaused()) PAUSED_CARD_MS else HIDE_MS)
    }

    private fun onIdle() {
        if (!started || menus.isOpen || ended || errored) return
        if (scrubTarget >= 0 || tapOffset != 0L) { main.postDelayed(idle, 1000); return }
        when {
            isPaused() -> showPausedCard()
            visible && engine.isPlaying -> hideControls()
            visible -> main.postDelayed(idle, 1500) // buffering: keep the controls a little longer
        }
    }

    private fun isPaused() = ready && !ended && !errored && !engine.isPlaying && !engine.isBuffering

    // ======== keys ========

    private fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A

    private fun isNav(code: Int) = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
        code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN

    private fun hiddenKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        if (postPlayUp()) {
            // Under a post-play card: quick ±10 s taps still work (seeking back dismisses Up next); nothing else of ours shows.
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (first || !down) hiddenSeek(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1, e)
                return true
            }
            return false
        }
        when {
            isOk(code) -> {
                if (first) {
                    if (skip.visibility == View.VISIBLE && skipAction != null) triggerSkip()
                    else { togglePlay(); showControls(FOCUS_SCRUB_ONLY) }
                }
                return true
            }
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                hiddenSeek(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1, e)
                return true
            }
            // Paused: Up is X-Ray (else, as while playing, it brings the controls).
            code == KeyEvent.KEYCODE_DPAD_UP -> { if (first && !(isPaused() && openXRay())) showControls(FOCUS_SCRUB); return true }
            code == KeyEvent.KEYCODE_DPAD_DOWN -> { if (first) showControls(FOCUS_PLAY); return true }
            code == KeyEvent.KEYCODE_MENU -> { if (first) openSheet("audio"); return true }
            code == KeyEvent.KEYCODE_INFO -> { if (first) openSheet("info"); return true }
        }
        return false
    }

    private fun visibleKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        val f = root.findFocus()?.takeIf { isInStage(it) }
        if (isNav(code) || isOk(code)) bumpIdle()
        if (f == null && (isNav(code) || isOk(code))) {
            if (down) scrubber.requestFocus()
            return true
        }
        when {
            f === scrubber && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) -> {
                val dir = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                if (down) {
                    if (engine.durationMs <= 0) return true
                    if (scrubTarget < 0) startScrub(engine.positionMs, fromHidden = false)
                    stepScrub(dir, e.repeatCount)
                } else if (scrubTarget >= 0 && scrubFromHidden) commitScrub()
                // A hold on the timeline seeks once, shortly after its release (another press within the
                // debounce keeps scrubbing); single taps keep their longer wait (or OK) as before.
                else if (scrubTarget >= 0 && scrubStep > STEP_TAP) {
                    scrubStep = STEP_TAP
                    updatePreview()
                    main.removeCallbacks(commitScrubRun)
                    main.postDelayed(commitScrubRun, HOLD_COMMIT_MS)
                }
                return true
            }
            isNav(code) -> { if (down) moveFocus(f, code); return true }
            isOk(code) -> {
                if (first && f != null) {
                    if (f === scrubber) { if (scrubTarget >= 0) commitScrub() else togglePlay() }
                    else click(f)
                }
                return true
            }
            code == KeyEvent.KEYCODE_MENU -> { if (first) openSheet("audio"); return true }
            code == KeyEvent.KEYCODE_INFO -> { if (first) openSheet("info"); return true }
        }
        return false
    }

    private fun menuKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        val panel = menus.focusRoot() ?: return false
        val f = panel.findFocus()
        if (menus.onKey(e, f)) return true
        if (f == null && (isNav(code) || isOk(code))) {
            if (down) {
                val all = ArrayList<View>()
                panel.addFocusables(all, View.FOCUS_DOWN)
                all.firstOrNull()?.requestFocus()
            }
            return true
        }
        when {
            isNav(code) -> {
                if (down && f != null) {
                    val dir = when (code) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                        KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                        KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                        else -> View.FOCUS_DOWN
                    }
                    FocusFinder.getInstance().findNextFocus(panel, f, dir)?.requestFocus(dir)
                }
                return true
            }
            isOk(code) -> { if (first) f?.let { click(it) }; return true }
            code == KeyEvent.KEYCODE_MENU -> { if (first) menus.close(); return true }
        }
        return false
    }

    /** OK on a control: a quick dip, then the click as it springs back. */
    private fun click(v: View) {
        Ui.press(v)
        main.postDelayed({ Ui.release(v); if (v.isAttachedToWindow) v.performClick() }, Ui.PRESS)
    }

    private fun visibleActions(): List<View> {
        val out = ArrayList<View>()
        for (i in 0 until actionsRow.childCount) {
            val c = actionsRow.getChildAt(i)
            if (c.isFocusable && c.visibility == View.VISIBLE) out += c
        }
        return out
    }

    private fun moveFocus(f: View?, code: Int) {
        val skipOn = skip.visibility == View.VISIBLE && skipAction != null
        when {
            f == null -> scrubber.requestFocus()
            f === skip -> if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT) scrubber.requestFocus()
            f === scrubber -> when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> (lastAction?.takeIf { it.visibility == View.VISIBLE && it.parent === actionsRow } ?: playBtn).requestFocus()
                // Up from the timeline: the Skip pill when it's up, else X-Ray (playing or paused).
                KeyEvent.KEYCODE_DPAD_UP -> if (skipOn) skip.requestFocus() else openXRay()
            }
            f.parent === actionsRow -> {
                val list = visibleActions()
                val i = list.indexOf(f)
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> list.getOrNull(i - 1)?.requestFocus()
                    KeyEvent.KEYCODE_DPAD_RIGHT -> list.getOrNull(i + 1)?.requestFocus()
                    KeyEvent.KEYCODE_DPAD_UP -> scrubber.requestFocus()
                }
            }
            else -> scrubber.requestFocus()
        }
    }

    private fun isInStage(v: View): Boolean {
        var p: Any? = v
        while (p is View) { if (p === stage) return true; p = p.parent }
        return false
    }

    // ======== seeking ========

    private fun hiddenSeek(dir: Int, e: KeyEvent) {
        val down = e.action == KeyEvent.ACTION_DOWN
        if (engine.durationMs <= 0) { if (down && e.repeatCount == 0) showControls(FOCUS_SCRUB); return }
        if (down) {
            if (e.repeatCount == 0) {
                if (tapOffset == 0L || (tapOffset > 0) != (dir > 0)) { tapBase = engine.positionMs; tapOffset = 0L }
                tapOffset += dir * STEP_TAP
                showBubble(dir)
                main.removeCallbacks(commitTap)
                main.postDelayed(commitTap, TAP_COMMIT_MS)
            } else {
                // Held: switch to the timeline with a live preview; commit on release.
                main.removeCallbacks(commitTap)
                main.removeCallbacks(hideBubbles)
                hideBubbles.run()
                val from = (tapBase + tapOffset).coerceIn(0, maxOf(0, engine.durationMs - END_GUARD))
                tapOffset = 0L
                showControls(FOCUS_SCRUB_ONLY)
                startScrub(from, fromHidden = true)
                holdStart = SystemClock.uptimeMillis()
                lastStep = holdStart
                scheduleScrubCommit()
            }
        } else if (tapOffset != 0L) {
            main.removeCallbacks(commitTap)
            main.postDelayed(commitTap, TAP_RELEASE_MS)
        }
    }

    private fun mediaScrub(dir: Int, repeat: Int) {
        if (engine.durationMs <= 0) return
        if (!visible) showControls(FOCUS_SCRUB_ONLY) else scrubber.requestFocus()
        if (scrubTarget < 0) startScrub(engine.positionMs, fromHidden = true)
        stepScrub(dir, repeat)
    }

    private fun showBubble(dir: Int) {
        val b = if (dir > 0) bubbleR else bubbleL
        val other = if (dir > 0) bubbleL else bubbleR
        other.animate().cancel(); other.visibility = View.GONE
        b.text = offsetLabel(tapOffset)
        main.removeCallbacks(hideBubbles)
        b.animate().cancel()
        if (b.visibility != View.VISIBLE) {
            b.visibility = View.VISIBLE
            b.alpha = 0f
            b.scaleX = 0.86f; b.scaleY = 0.86f
            b.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Ui.FOCUS_IN).setInterpolator(Ui.SPRING).start()
        } else {
            b.scaleX = 1.08f; b.scaleY = 1.08f
            b.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Ui.RELEASE).setInterpolator(Ui.SPRING).start()
        }
    }

    private fun commitTapSeek() {
        if (tapOffset == 0L) return
        val t = (tapBase + tapOffset).coerceIn(0, maxOf(0, engine.durationMs - END_GUARD))
        tapOffset = 0L
        engine.seekTo(t)
        if (visible) { scrubber.set(t, engine.durationMs, engine.bufferedMs); updateTimes() }
        main.postDelayed(hideBubbles, 420)
    }

    private fun startScrub(from: Long, fromHidden: Boolean) {
        scrubTarget = from
        scrubStep = STEP_TAP
        thumbGlow.snap(CinemaStyle.glowNow, true)
        scrubFromHidden = fromHidden
        holdStart = SystemClock.uptimeMillis()
        lastStep = 0L
        scrubber.setTarget(from)
        updatePreview()
        if (preview.visibility != View.VISIBLE || preview.alpha < 1f) {
            preview.animate().cancel()
            if (preview.visibility != View.VISIBLE) preview.alpha = 0f
            preview.visibility = View.VISIBLE
            preview.animate().alpha(1f).setDuration(Ui.FOCUS_IN).setInterpolator(Ui.EASE).start()
        }
    }

    private fun stepScrub(dir: Int, repeat: Int) {
        val now = SystemClock.uptimeMillis()
        if (repeat == 0) { holdStart = now; lastStep = now }
        else if (now - lastStep < HOLD_STEP_MS) { scheduleScrubCommit(); return }
        else lastStep = now
        val held = now - holdStart
        // Held, the step grows the way Netflix's does: 10 s, then 30 s, 1 min and 2 min per repeat. The
        // player isn't touched meanwhile (only the target moves); it seeks once, after the release.
        val step = when {
            repeat == 0 || held < 1000 -> STEP_TAP
            held < 2500 -> 30_000L
            held < 4000 -> 60_000L
            else -> 120_000L
        }
        // Each change of pace while held, for logcat (the remote's hold can't be driven over adb; see PingReceiver).
        if (step > STEP_TAP && step != scrubStep) android.util.Log.w("FireHome", "Player: hold step ${rateLabel(step)} at ${held / 100 / 10.0} s")
        if (step > STEP_TAP && step > holdMax) holdMax = step
        scrubStep = step
        scrubDir = dir
        val dur = engine.durationMs
        scrubTarget = (scrubTarget + dir * step).coerceIn(0, maxOf(0, dur - END_GUARD))
        scrubber.setTarget(scrubTarget)
        updatePreview()
        scheduleScrubCommit()
        bumpIdle()
    }

    private fun scheduleScrubCommit() {
        main.removeCallbacks(commitScrubRun)
        main.postDelayed(commitScrubRun, if (scrubFromHidden) HOLD_COMMIT_MS else SCRUB_COMMIT_MS)
    }

    private fun commitScrub() {
        main.removeCallbacks(commitScrubRun)
        val t = scrubTarget
        if (t < 0) return
        scrubTarget = -1L
        if (holdMax > 0) {
            android.util.Log.w("FireHome", "Player: hold released: one seek from ${fmt(engine.positionMs)} to ${fmt(t)} ($t ms), top step ${rateLabel(holdMax)}")
            holdMax = 0L
        }
        engine.seekTo(t)
        scrubber.setTarget(-1L)
        scrubber.set(t, engine.durationMs, engine.bufferedMs)
        hidePreview()
        if (visible) updateTimes()
        bumpIdle()
    }

    private fun cancelScrub() {
        main.removeCallbacks(commitScrubRun)
        holdMax = 0L
        if (scrubTarget < 0) return
        scrubTarget = -1L
        scrubber.setTarget(-1L)
        hidePreview()
    }

    private fun hidePreview() {
        preview.animate().alpha(0f).setDuration(Ui.FOCUS_OUT).setInterpolator(Ui.EXIT)
            .withEndAction { if (scrubTarget < 0) preview.visibility = View.GONE }.start()
    }

    private fun updatePreview() {
        val t = scrubTarget
        if (t < 0) return
        previewTime.text = fmt(t)
        previewFrame(t)
        // While a hold speeds the scrub up, the readout says how fast ("›› 1 min"), else the chapter or the offset.
        val s = scrubStep
        previewLabel.text = if (s > STEP_TAP) { val r = rateLabel(s); if (scrubDir > 0) "$r  ››" else "‹‹  $r" }
            else chapterAt(t) ?: offsetLabel(t - engine.positionMs)
        val place = Runnable {
            val tt = scrubTarget
            if (tt < 0) return@Runnable
            val x = bottomBar.left + scrubber.left + scrubber.xOf(tt) - previewW.dp / 2f
            preview.x = x.coerceIn(padH - 9f.dpf, maxOf(padH.toFloat(), (controls.width - padH - previewW.dp).toFloat() + 9f.dpf))
            val h = if (preview.height > 0) preview.height else 72.dp
            preview.y = bottomBar.top + scrubber.top - h - 6f.dpf
        }
        if (bottomBar.height == 0 || preview.height == 0) preview.post(place) else place.run()
    }

    private fun chapterAt(t: Long): String? {
        val m = markers ?: return null
        m.recapEndMs?.let { if (t < it) return "Recap" }
        val e = m.introEndMs
        if (e != null && t < e && t >= (m.introStartMs ?: 0L)) return "Intro"
        m.creditsStartMs?.let { if (t >= it) return "Credits" }
        return null
    }

    private fun restart() {
        if (!ready) return
        cancelScrub()
        engine.seekTo(0)
        if (!engine.isPlaying) engine.play()
        toast("Starting From The Beginning")
        if (visible) { scrubber.set(0, engine.durationMs, engine.bufferedMs); updateTimes(); scrubber.requestFocus() }
    }

    // ======== play state ========

    private fun togglePlay() {
        val play = !engine.isPlaying
        if (play) engine.play() else engine.pause()
        flashGlyph(play)
        main.postDelayed({ updatePlayIcon() }, 60)
    }

    /** Netflix's center flash: the new state's glyph pops in on a dark disc and fades out (~0.5 s, scale and alpha only). */
    private fun flashGlyph(play: Boolean) {
        if (!built) return
        flash.setImageDrawable(if (play) flashPlay else flashPause)
        flash.animate().cancel()
        flash.visibility = View.VISIBLE
        flash.alpha = 0f
        flash.scaleX = 0.82f; flash.scaleY = 0.82f
        flash.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(150).setInterpolator(Ui.EASE).withEndAction {
            flash.animate().alpha(0f).scaleX(1.14f).scaleY(1.14f).setStartDelay(260).setDuration(320).setInterpolator(Ui.EXIT)
                .withEndAction { flash.visibility = View.GONE }.start()
        }.start()
    }

    private fun updatePlayIcon() {
        if (!built) return
        val playing = engine.isPlaying || (engine.isBuffering && !wasPaused)
        val want = if (playing) pauseIcon else playIcon
        if (playBtn.drawable !== want) playBtn.setImageDrawable(want)
    }

    private fun markReady() {
        if (ready) return
        ready = true
        onReady()
    }

    /** First frames of a stream: fetch what the overlay shows later (markers, synopsis, next episode). */
    private fun onReady() {
        val req = host.request
        val key = reqKey
        PlayerPrefs.applyStyle(ctx, root, visible)
        applyTrackPrefs()
        if (req != null && req.tmdbId > 0) {
            val dur = engine.durationMs
            if (dur > 0) try {
                SkipMarkers.get(ctx, req, dur) { m ->
                    if (reqKey != key) return@get
                    // An estimated credits time is fine for post-play, but it isn't ticked or named on the timeline.
                    val shown = if (m.creditsStartMs != null && SkipMarkers.creditsEstimated(req)) m.copy(creditsStartMs = null) else m
                    markers = shown
                    scrubber.setMarkers(shown)
                }
            } catch (e: Exception) {}
            loadInfo { }
            startThumbs()
            // The cast for X-Ray, once the start has the network to itself for a while (one small TMDB request).
            main.postDelayed({ if (reqKey == key && started) host.request?.let { xray.prefetch(it) } }, XRAY_PREFETCH_MS)
            if (req.type == "episode" && req.season != null && req.episode != null) findNext(req, key)
        }
    }

    private fun findNext(req: PlaybackRequest, key: String?) {
        val s = req.season ?: return
        val e = req.episode ?: return
        val today = DreamTime.todayYmd(ctx)
        fun aired(o: org.json.JSONObject) = o.optString("air_date").let { it.isNotEmpty() && it != "null" && it <= today }
        Net.background({
            val eps = WatchHistory.seasonEpisodes(ctx, req.tmdbId, s).orEmpty()
            val inSeason = eps.filter { it.optInt("episode_number") > e && aired(it) }.minByOrNull { it.optInt("episode_number") }
            if (inSeason != null) Triple(s, inSeason.optInt("episode_number"), inSeason.optString("name"))
            else WatchHistory.seasonEpisodes(ctx, req.tmdbId, s + 1)?.filter { aired(it) }?.minByOrNull { it.optInt("episode_number") }
                ?.let { Triple(s + 1, it.optInt("episode_number"), it.optString("name")) }
        }) { r ->
            if (r == null || reqKey != key) return@background
            nextReq = req.copy(season = r.first, episode = r.second,
                episodeTitle = r.third.takeIf { it.isNotBlank() && it != "null" }, startMs = 0)
            if (visible) updateActions()
        }
    }

    private fun playNextEpisode() {
        val n = nextReq ?: return
        hideControls()
        menus.closeNow()
        host.playNext(n)
    }

    override fun playEpisode(season: Int, episode: Int, title: String?, startMs: Long) {
        val req = host.request ?: return
        hideControls()
        host.playNext(req.copy(season = season, episode = episode, episodeTitle = title, startMs = startMs))
    }

    // ======== engine listener ========

    override fun onState() {
        if (!started) return
        checkRequest()
        if (engine.isPlaying) { markReady(); ended = false; errored = false }
        // Playing again some other way (AirPods, the phone remote) while a card had paused: the card stays, no auto-resume later.
        if (engine.isPlaying && xrayPaused) xrayPaused = false
        updatePlayIcon()
        // Buffering ring: only after a short wait (no flicker on quick seeks), never over the curtain.
        if (engine.isBuffering && ready && !ended && !errored) {
            if (spinner.visibility != View.VISIBLE) { main.removeCallbacks(showSpinner); main.postDelayed(showSpinner, 380) }
        } else {
            main.removeCallbacks(showSpinner)
            if (spinner.visibility == View.VISIBLE) {
                spinner.animate().alpha(0f).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT)
                    .withEndAction { if (!engine.isBuffering) { spinner.visibility = View.GONE; spinner.end() } }.start()
            }
        }
        val paused = isPaused()
        if (paused != wasPaused) {
            wasPaused = paused
            if (paused) { if (!postPlayUp()) setDim(restDim()) } else { hidePausedCard(); setDim(restDim()) }
            updatePlayIcon()
            bumpIdle()
        }
    }

    override fun onProgress(positionMs: Long) {
        if (!started) return
        if (!ready && engine.isPlaying) { checkRequest(); markReady() }
        feedThumbs()
    }

    override fun onTracks() {
        if (!started) return
        checkRequest()
        applyTrackPrefs()
        if (built) menus.tracksChanged()
    }

    override fun onEnded() {
        if (!started) return
        ended = true
        cancelScrub()
        menus.closeNow()
        closeXRayNow()
        hideControls()
        hidePausedCard()
        setDim(0f)
        main.removeCallbacks(idle)
    }

    override fun onError(message: String) {
        if (!started) return
        errored = true
        main.removeCallbacks(showSpinner)
        main.removeCallbacks(idle)
        spinner.end()
        spinner.visibility = View.GONE
        cancelScrub()
        menus.closeNow()
        closeXRayNow()
        hideControls()
        hidePausedCard()
        hideSkip()
        setDim(0f)
    }

    // ======== new stream ========

    private fun keyOf(r: PlaybackRequest?) = r?.let { "${it.type}:${it.tmdbId}:${it.season}:${it.episode}:${it.title}" } ?: ""

    private fun checkRequest() {
        val k = keyOf(host.request)
        if (k == reqKey) return
        reqKey = k
        // A new item: the play flow's curtain covers the gap until it actually plays.
        ready = false
        ended = false
        errored = false
        markers = null
        info = null
        infoWaiters = null
        nextReq = null
        audioApplied = false
        userAudio = false
        userText = false
        autoSubs = null
        autoNote = null
        noticeAfter = 0L
        seenText.clear()
        stopThumbs()
        cardLogoFor = null
        tapOffset = 0L
        wasPaused = false
        if (built) {
            scrubber.setMarkers(null)
            cancelScrub()
            menus.closeNow()
            closeXRayNow()
            visible = false
            main.removeCallbacks(ticker)
            main.removeCallbacks(idle)
            controls.animate().cancel()
            controls.visibility = View.GONE
            PlayerPrefs.raiseForControls(ctx, root, false)
            cardUp = false
            pausedCard.visibility = View.GONE
            dim.animate().cancel()
            dim.visibility = View.GONE
            refreshTitle()
            updateBadges()
        }
    }

    /**
     * The stream badges, from the formats now playing ([StreamBadges]); called when one changes ([ExoEngine.onFormat])
     * and on a new item. Views are rebuilt only when the text does; the log line waits for the formats to settle.
     */
    private fun updateBadges() {
        if (!built) return
        val exo = engine as? ExoEngine ?: return
        val f = if (host.request?.trailer == true) null else StreamBadges.facts(exo)
        val key = f?.let { it.summary + "|" + it.size }.orEmpty()
        if (key == badgeKey) return
        badgeKey = key
        StreamBadges.fill(cardBadges, kit, f, compact = false, withSize = true)
        StreamBadges.fill(barBadges, kit, f, compact = true, withSize = false)
        badgeLine = f?.summary.orEmpty()
        main.removeCallbacks(logBadges)
        if (badgeLine.isNotEmpty()) main.postDelayed(logBadges, 1_500)
    }

    private fun refreshTitle() {
        val r = host.request
        when {
            r == null -> { kicker.text = ""; titleView.text = "Dream Player"; subView.text = "" }
            r.trailer -> { kicker.text = "Trailer"; titleView.text = r.title; subView.text = "" }
            r.type == "episode" -> {
                kicker.text = listOfNotNull(r.season?.let { "Season $it" }, r.episode?.let { "Episode $it" }).joinToString("  ·  ")
                titleView.text = r.title
                subView.text = r.episodeTitle.orEmpty()
            }
            else -> {
                kicker.text = r.year?.let { "Movie  ·  $it" } ?: if (r.tmdbId > 0) "Movie" else ""
                titleView.text = r.title
                subView.text = ""
            }
        }
        if (r != null) cinemaTitle(r)
        kicker.visibility = if (kicker.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        subView.visibility = if (subView.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Cinema's title block, Apple TV-style: the title's logo (else its name), then one quiet line,
     * "S2 E4 · Episode Name" or "Movie · 2024". The caps kicker isn't needed then.
     */
    private fun cinemaTitle(r: PlaybackRequest) {
        kicker.text = if (r.trailer) "TRAILER" else ""
        subView.text = when {
            r.trailer -> ""
            r.type == "episode" -> {
                val se = if (r.season != null && r.episode != null) "S${r.season} E${r.episode}" else null
                val name = r.episodeTitle?.takeIf { it.isNotBlank() && it != "null" }
                // A play that didn't carry the episode's name (some paths don't): fetch it, then fill the line in.
                if (name == null && se != null && r.tmdbId > 0) {
                    val season = r.season!!; val ep = r.episode!!
                    Net.background({
                        WatchHistory.seasonEpisodes(ctx, r.tmdbId, season)?.firstOrNull { it.optInt("episode_number") == ep }
                            ?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
                    }) { n -> if (n != null && host.request?.let { it.tmdbId == r.tmdbId && it.season == season && it.episode == ep } == true) subView.text = "$se  ·  $n" }
                }
                listOfNotNull(se, name).joinToString("  ·  ")
            }
            else -> listOfNotNull(if (r.tmdbId > 0) "Movie" else null, r.year?.toString()).joinToString("  ·  ")
        }
        val logo = r.logo
        if (logo == null) {
            titleLogoFor = null
            titleLogo.setImageDrawable(null)
            titleLogo.visibility = View.GONE
            titleView.visibility = View.VISIBLE
        } else if (titleLogoFor != logo) {
            titleLogoFor = logo
            titleLogo.setImageDrawable(null)
            titleLogo.visibility = View.GONE
            titleView.visibility = View.VISIBLE
            Net.image(ctx, titleLogo, logo) {
                if (titleLogoFor == logo) { titleLogo.visibility = View.VISIBLE; titleView.visibility = View.GONE }
            }
        }
    }

    private fun updateActions() {
        val r = host.request
        btnEpisodes.visibility = if (r?.type == "episode" && r.tmdbId > 0) View.VISIBLE else View.GONE
        btnNext.visibility = if (nextReq != null) View.VISIBLE else View.GONE
        btnRestart.visibility = if (engine.durationMs > 0 && engine.positionMs > 60_000) View.VISIBLE else View.GONE
        btnSource.visibility = if (canChangeSource()) View.VISIBLE else View.GONE
        compactLevel = -1
        if (actionsRow.width > 0) fitActions() else actionsRow.post { fitActions() }
    }

    // ======== time ========

    /** The minutes the clock and "Ends at" last showed: their text is formatted only when one changes. */
    private var clockMin = Long.MIN_VALUE
    private var endsMin = Long.MIN_VALUE

    private fun updateTimes() {
        if (!built) return
        val pos = engine.positionMs
        val dur = engine.durationMs
        scrubber.set(pos, dur, engine.bufferedMs)
        val cur = fmt(pos)
        if (curTime.text.toString() != cur) curTime.text = cur
        val rem = if (dur > 0) "−" + fmt(maxOf(0, dur - pos)) else "LIVE"
        if (remaining.text.toString() != rem) remaining.text = rem
        val now = System.currentTimeMillis()
        if (now / 60_000 != clockMin) { clockMin = now / 60_000; clock.text = DreamTime.formatTime(ctx, now) }
        val end = now + (dur - pos).coerceAtLeast(0)
        val em = if (dur > 0) end / 60_000 else -1L
        if (em != endsMin) {
            endsMin = em
            endsAt.text = if (dur > 0) "Ends at " + DreamTime.formatTime(ctx, end) else ""
            endsAt.visibility = if (dur > 0) View.VISIBLE else View.GONE
        }
        if (btnRestart.visibility == View.GONE && dur > 0 && pos > 60_000) {
            btnRestart.visibility = View.VISIBLE
            compactLevel = -1
            fitActions()
        }
    }

    /** A held scrub's step, said plainly: "30 s", "1 min", "2 min". */
    private fun rateLabel(step: Long): String = if (step < 60_000) "${step / 1000} s" else "${step / 60_000} min"

    private fun fmt(ms: Long): String {
        val s = maxOf(0L, ms / 1000)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        // "1:02:03" / "2:03", built by hand: this runs on every tick while the controls show.
        val b = StringBuilder(8)
        if (h > 0) { b.append(h).append(':'); if (m < 10) b.append('0') }
        b.append(m).append(':')
        if (sec < 10) b.append('0')
        return b.append(sec).toString()
    }

    private fun offsetLabel(delta: Long): String {
        val sign = if (delta < 0) "−" else "+"
        val s = kotlin.math.abs(delta) / 1000
        return sign + if (s < 60) "$s" else fmt(s * 1000)
    }

    // ======== paused card & dim ========

    private fun setDim(a: Float, duration: Long = Ui.PAGE) {
        dim.animate().cancel()
        if (a <= 0f) {
            if (dim.visibility != View.VISIBLE) return
            dim.animate().alpha(0f).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT)
                .withEndAction { dim.visibility = View.GONE }.start()
        } else {
            if (dim.visibility != View.VISIBLE) { dim.alpha = 0f; dim.visibility = View.VISIBLE }
            dim.animate().alpha(a).setDuration(duration).setInterpolator(Ui.EASE).start()
        }
    }

    /** "S2 · E5  Episode Name" for an episode (its name from TMDB when the play didn't carry it), else the movie's facts. */
    private fun pauseLine(r: PlaybackRequest, i: PlayerInfo?): String =
        if (r.type == "episode") {
            val name = r.episodeTitle?.takeIf { it.isNotBlank() && it != "null" } ?: i?.title?.takeIf { it.isNotBlank() && it != r.title }
            listOfNotNull(if (r.season != null && r.episode != null) "S${r.season} · E${r.episode}" else null, name).joinToString("  ")
        } else i?.meta?.takeIf { it.isNotBlank() } ?: r.year?.toString().orEmpty()

    /** "38 min left", "1 hr 12 min left"; null when the length isn't known. */
    private fun timeLeft(): String? {
        val d = engine.durationMs
        if (d <= 0) return null
        val min = ((d - engine.positionMs).coerceAtLeast(0) + 30_000) / 60_000
        return when {
            min < 1 -> "Less than a minute left"
            min < 60 -> "$min min left"
            min % 60 == 0L -> "${min / 60} hr left"
            else -> "${min / 60} hr ${min % 60} min left"
        }
    }

    private fun fillPauseText(r: PlaybackRequest, i: PlayerInfo?) {
        val line = pauseLine(r, i)
        cardSub.text = line
        cardSub.visibility = if (line.isBlank()) View.GONE else View.VISIBLE
        cardSynopsis.text = i?.overview.orEmpty()
        cardSynopsis.visibility = if (cardSynopsis.text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun showPausedCard() {
        if (cardUp || !isPaused() || menus.isOpen || scrubTarget >= 0 || postPlayUp() || xray.isOpen || pickerUp()) return
        val r = host.request ?: return
        hideControls()
        cardUp = true
        cardTitle.text = r.title
        fillPauseText(r, info)
        // The synopsis may still be on its way (slow TMDB): it slots in when it lands, if the card is still up.
        if (info == null) { val key = reqKey; loadInfo { i -> if (cardUp && reqKey == key) fillPauseText(r, i) } }
        val left = timeLeft()
        cardLeft.text = left.orEmpty()
        cardLeft.visibility = if (left == null) View.GONE else View.VISIBLE
        val logo = r.logo
        if (logo == null) {
            cardLogo.visibility = View.GONE
            cardTitle.visibility = View.VISIBLE
        } else if (cardLogoFor != logo) {
            cardLogoFor = logo
            cardLogo.setImageDrawable(null)
            cardLogo.visibility = View.GONE
            cardTitle.visibility = View.VISIBLE
            Net.image(ctx, cardLogo, logo) {
                if (cardLogoFor == logo) { cardLogo.visibility = View.VISIBLE; cardTitle.visibility = View.GONE }
            }
        }
        pausedCard.animate().cancel()
        pausedCard.visibility = View.VISIBLE
        pausedCard.alpha = 1f
        val parts = (0 until pausedCard.childCount).map { pausedCard.getChildAt(it) }.filter { it.visibility == View.VISIBLE }
        // The picture eases down to ~66 % black first; the card rises in as it settles.
        Ui.rise(parts, fromDp = 16, startDelay = 160L, step = 60L, duration = 460L)
        setDim(DIM_CARD, CARD_DIM_MS)
        android.util.Log.w("FireHome", "Player: pause screen shown ('${r.title}'" +
            (if (r.type == "episode") " S${r.season}E${r.episode}" else "") + ", ${left ?: "length unknown"}, synopsis ${if (info?.overview.isNullOrBlank()) "pending/none" else "ready"})")
    }

    private fun hidePausedCard() {
        if (!cardUp) return
        cardUp = false
        pausedCard.animate().cancel()
        pausedCard.animate().alpha(0f).setDuration(Ui.FOCUS_OUT).setInterpolator(Ui.EXIT)
            .withEndAction { if (!cardUp) pausedCard.visibility = View.GONE }.start()
        setDim(restDim())
    }

    // ======== skip ========

    private fun triggerSkip() {
        val a = skipAction ?: return
        Ui.press(skip)
        main.postDelayed({
            Ui.release(skip)
            a()
            hideSkip()
        }, Ui.PRESS)
    }

    // ======== toasts ========

    /** A longer toast in a status color: what plays when the user's filters were set aside (amber for a CAM copy). */
    fun notice(text: String, color: Int) {
        toast(text)
        if (!built) return
        toastView.setTextColor(color)
        main.removeCallbacks(hideToast)
        main.postDelayed(hideToast, 3_600)
    }

    override fun toast(text: String) {
        if (!built) return
        toastView.text = text
        toastView.setTextColor(kit.text)
        main.removeCallbacks(hideToast)
        toastView.animate().cancel()
        if (toastView.visibility != View.VISIBLE) {
            toastView.visibility = View.VISIBLE
            toastView.alpha = 0f
            toastView.translationY = (-8).dp.toFloat()
        }
        toastView.animate().alpha(1f).translationY(0f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        main.postDelayed(hideToast, 1900)
    }

    private fun fadeOut(v: View) {
        if (v.visibility != View.VISIBLE) return
        v.animate().alpha(0f).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT).withEndAction { v.visibility = View.GONE }.start()
    }

    // ======== sheets ========

    private fun openSheet(kind: String) {
        if (!ready) return
        if (kind == "episodes" && host.request?.type != "episode") return
        if (cardUp) hidePausedCard()
        xray.closeNow()
        cancelScrub()
        main.removeCallbacks(idle)
        if (visible) hideControls()
        when (kind) {
            "audio" -> menus.openAudio()
            "info" -> menus.openInfo()
            "episodes" -> menus.openEpisodes()
        }
        syncSubtitles()
    }

    override fun sheetClosed(opener: String) {
        syncSubtitles()
        val v: View = when (opener) {
            "audio" -> btnAudio
            "info" -> btnInfo
            "episodes" -> btnEpisodes
            else -> scrubber
        }
        showControlsFocusing(if (v.visibility == View.VISIBLE) v else scrubber)
    }

    override fun info() = info

    override fun loadInfo(done: (PlayerInfo?) -> Unit) {
        info?.let { done(it); return }
        val req = host.request ?: return done(null)
        infoWaiters?.let { it += done; return }
        val waiters = mutableListOf(done)
        infoWaiters = waiters
        val key = reqKey
        Net.background({ PlayerMeta.load(ctx, req) }) { i ->
            if (reqKey != key) return@background
            info = i
            infoWaiters = null
            for (w in waiters) w(i)
        }
    }

    override fun subtitleStyleChanged() = PlayerPrefs.applyStyle(ctx, root, false)

    override fun userPicked(kind: String) {
        if (kind == "audio") userAudio = true else pickedText()
    }

    /** The viewer chose subtitles in the player: kept for this title, so [audioCheck] never overrides it. */
    private fun pickedText() {
        userText = true
        autoSubs = null
        autoNote = null
        host.request?.let { PlayerPrefs.noteSubsChosen(ctx, it) }
    }

    // ======== audio in another language ========

    /** Languages the automatic subtitles look for, while they're on for this stream ([audioCheck]). */
    private var autoSubs: List<String>? = null
    /** "Japanese Audio": the first half of the notice, until the subtitles are settled. */
    private var autoNote: String? = null
    /** A notice already on screen at the picture's start (a CAM note): this one waits until then. */
    private var noticeAfter = 0L

    /**
     * The picture starts and the audio playing is in another language than the viewer's (a new anime episode in
     * Japanese, say), with no track in their language: subtitles in their subtitle language (else their audio
     * language) come on, from the video or Dream Subtitles ([subs]), with a short notice. Nothing when the audio's
     * language is unknown, or the viewer chose subtitles in the player for this title. [after]: a notice is showing.
     */
    fun audioCheck(subs: DreamSubtitles.Session, after: Boolean) {
        checkRequest() // a new stream's state is reset before this one's is set
        val req = host.request ?: return
        if (req.trailer || userText || PlayerPrefs.subsChosen(ctx, req)) return
        val audio = engine.tracks("audio")
        val playing = PlayerPrefs.normLang(audio.firstOrNull { it.selected }?.language) ?: return
        val want = AnimeDubs.want(ctx)
        if (playing == want || audio.any { it.supported && PlayerPrefs.normLang(it.language) == want }) return
        val player = PlayerPrefs.subLang(ctx)?.takeIf { it != PlayerPrefs.OFF }
        val langs = (listOfNotNull(player) + subs.languages() + want).map { if (it == "pob") "pt" else it }.distinct()
        noticeAfter = if (after) SystemClock.uptimeMillis() + 3_800 else 0L
        autoSubs = langs
        autoNote = "${AnimeDubs.langName(playing)} Audio"
        android.util.Log.w("FireHome", "dream player: audio is $playing, viewer's is $want: subtitles in ${langs.joinToString("/")}")
        if (autoPick()) return
        val key = reqKey
        subs.showWhenReady { added ->
            if (reqKey != key || added) return@showWhenReady // added: the new tracks go through applyTrackPrefs → autoPick
            autoNote?.let { say("$it  ·  No ${AnimeDubs.langName(langs.first())} Subtitles Found") }
            autoNote = null
        }
    }

    /** Selects the automatic subtitles' track when there is one (says so once); false when none is there yet. */
    private fun autoPick(): Boolean {
        val langs = autoSubs ?: return false
        val text = engine.tracks("text").filter { it.supported }
        val sel = text.firstOrNull { it.selected }?.takeIf { PlayerPrefs.normLang(it.language) in langs }
        val pick = sel ?: langs.firstNotNullOfOrNull { l ->
            text.filter { PlayerPrefs.normLang(it.language) == l }.minByOrNull { if (it.label.contains("forced", ignoreCase = true)) 1 else 0 }
        } ?: return false
        if (!pick.selected) engine.selectTrack("text", pick.id)
        PlayerPrefs.applyStyle(ctx, root, visible)
        autoNote?.let { say("$it  ·  ${AnimeDubs.langName(PlayerPrefs.normLang(pick.language))} Subtitles On") }
        autoNote = null
        return true
    }

    private fun say(text: String) {
        val wait = noticeAfter - SystemClock.uptimeMillis()
        val color = Ui.alpha(Color.WHITE, 0.92f)
        if (wait > 0) main.postDelayed({ notice(text, color) }, wait) else notice(text, color)
    }

    // ======== track preferences ========

    /** Auto-applies the profile's preferred audio / subtitle language when a stream's tracks arrive. */
    private fun applyTrackPrefs() {
        val e = engine
        if (!audioApplied && !userAudio) {
            val audio = e.tracks("audio")
            if (audio.isNotEmpty()) {
                audioApplied = true
                val want = PlayerPrefs.audioLang(ctx)
                val sel = audio.firstOrNull { it.selected }
                if (want != null && (sel == null || !sel.supported || PlayerPrefs.normLang(sel.language) != want)) {
                    audio.firstOrNull { it.supported && PlayerPrefs.normLang(it.language) == want }?.let { e.selectTrack("audio", it.id) }
                }
            }
        }
        if (!userText) {
            val text = e.tracks("text")
            var fresh = false
            for (t in text) if (seenText.add(t.id)) fresh = true
            if (fresh) {
                val want = PlayerPrefs.subLang(ctx)
                val sel = text.firstOrNull { it.selected }
                when {
                    autoSubs != null -> autoPick() // the audio is in another language: its subtitles stay on
                    want == null -> {}
                    want == PlayerPrefs.OFF -> if (sel != null) e.selectTrack("text", null)
                    sel == null || PlayerPrefs.normLang(sel.language) != want -> {
                        text.filter { it.supported && PlayerPrefs.normLang(it.language) == want }
                            .sortedBy { if (it.label.contains("forced", ignoreCase = true)) 1 else 0 }
                            .firstOrNull()?.let { e.selectTrack("text", it.id) }
                    }
                }
                PlayerPrefs.applyStyle(ctx, root, visible)
            }
        }
    }

    private fun toggleSubtitles() {
        val text = engine.tracks("text")
        val sel = text.firstOrNull { it.selected }
        if (sel != null) {
            pickedText()
            engine.selectTrack("text", null)
            PlayerPrefs.setSubLang(ctx, PlayerPrefs.OFF)
            toast("Subtitles  ·  Off")
        } else {
            val want = PlayerPrefs.subLang(ctx)?.takeIf { it != PlayerPrefs.OFF } ?: PlayerPrefs.audioLang(ctx) ?: Locale.getDefault().language
            val pick = text.filter { it.supported }.let { s -> s.firstOrNull { PlayerPrefs.normLang(it.language) == want } ?: s.firstOrNull() }
            if (pick == null) { toast("No subtitles for this stream"); return }
            pickedText()
            engine.selectTrack("text", pick.id)
            PlayerPrefs.setSubLang(ctx, PlayerPrefs.normLang(pick.language))
            toast("Subtitles  ·  " + pick.label)
        }
    }

    companion object {
        /** Matches the loading curtain's "NOW PLAYING" and Dream OS's other "connected/ready" greens. */
        private val NOW_PLAYING_GREEN = Color.parseColor("#4ADE80")
        private val FOCUSED = intArrayOf(android.R.attr.state_focused)
        private const val FOCUS_SCRUB = -1      // scrubber (or the Skip pill when it is up)
        private const val FOCUS_PLAY = -2       // play/pause in the action row
        private const val FOCUS_KEEP = -3       // leave focus where it is (inside the controls)
        private const val FOCUS_SCRUB_ONLY = -4 // scrubber even when Skip is up
        private const val HIDE_MS = 4000L
        /** The Pause Screen comes up after this long paused with the remote at rest. */
        private const val PAUSED_CARD_MS = 8000L
        /** Its dim fades in this slowly (one ViewPropertyAnimator, then nothing runs). */
        private const val CARD_DIM_MS = 700L
        /** X-Ray's cast is looked up this long after the first frames. */
        private const val XRAY_PREFETCH_MS = 10_000L
        private const val STEP_TAP = 10_000L
        private const val TAP_COMMIT_MS = 750L   // no key-up seen: commit anyway
        private const val TAP_RELEASE_MS = 380L  // after a tap's release, wait for another tap
        private const val HOLD_STEP_MS = 160L
        private const val HOLD_COMMIT_MS = 700L
        private const val SCRUB_COMMIT_MS = 1300L
        private const val END_GUARD = 2000L
        private const val PREVIEW_W = 150
        private const val DIM_PAUSED = 0.28f
        /** Under the X-Ray strip: a light dim, the picture still clearly playing (the strip has its own bottom scrim). */
        private const val DIM_XRAY = 0.16f
        private const val DIM_CARD = 0.66f
        // Cinema's round buttons (dp): size, glyph, gap; and the center play / pause flash.
        private const val CINE_BTN = 50
        private const val CINE_ICON = 22
        private const val CINE_GAP = 16
        private const val FLASH = 108
        // Scrub preview frame card (dp), the wait after the first frames before making any (ms), and how
        // much buffer ahead of the playhead (ms) counts as comfortable while it plays.
        private const val THUMB_W = 208
        private const val THUMB_H = 117
        private const val THUMBS_DELAY = 8_000L
        private const val THUMBS_BUFFER = 20_000L
    }
}
