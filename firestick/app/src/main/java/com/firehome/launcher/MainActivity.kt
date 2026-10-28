package com.firehome.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.style.StyleSpan
import android.text.style.ForegroundColorSpan
import android.text.Spanned
import android.text.SpannableString
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import kotlin.math.abs
import kotlin.math.sign
import com.firehome.launcher.HomeShell.Tab

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var pageHost: FrameLayout
    private var page = Tab.HOME
    private var splash: View? = null
    /** When the boot intro went up; it stays about [SPLASH_TOTAL_MS] in all (the user likes to enjoy it). */
    private var splashShownAt = 0L
    private var details: View? = null
    private var picker: ProfilePicker? = null
    private var pickerProfile: String? = null // profile in use when the picker opened
    private var setup: SetupWizard? = null

    private var apps: List<AppEntry> = emptyList()
    private var appsDirty = false
    private var resumed = false
    private var pendingAmazonSettings = false
    private var pendingOpenTitle: Title? = null
    /** The open title page came from Dream Player's X-Ray: Back from it returns to the paused player. */
    private var backToPlayer = false
    /** Dream Search should open with this query once resumed (Alexa heard a search: AlexaSearch). */
    private var pendingQuery: String? = null
    private var initialFocusDone = false
    private var btnPrimary = 0
    private var btnSecondary = 0
    /** dream_logo.png is 915x560 but the top bar shows it 50 dp tall at most: decode it subsampled (~0.16 MB, not 2.5 MB). */
    private val sidebarLogo: android.graphics.Bitmap? by lazy {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeResource(resources, R.drawable.dream_logo, opts)
        var sample = 1
        while (opts.outHeight / (sample * 2) >= 50.dp) sample *= 2
        android.graphics.BitmapFactory.decodeResource(resources, R.drawable.dream_logo,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // Current theme, read once per layout build.
    private var textColor = 0
    private var dimColor = 0
    private var accent = 0
    private var selectColor = 0
    private var bgTop = 0
    private lateinit var font: Typeface
    private lateinit var fontBold: Typeface

    // Boot loading screen: each step's live label, shown until it's done ("home": Home's first row is on screen).
    // Paced to fill the ~8 s intro ([BOOT_STEP_MS] each); "apps" and "home" wait for the real work, the rest are ready.
    private val bootTasks = linkedMapOf(
        "apps" to "Loading your apps\u2026",
        "accounts" to "Connecting your accounts\u2026",
        "core" to "Starting Dream Core\u2026",
        "home" to "Building your Home\u2026",
        "picks" to "Getting your picks ready\u2026",
    )
    private val bootDone = HashSet<String>()
    private var bootBar: View? = null
    private var bootStatus: TextView? = null
    private lateinit var sounds: Sounds
    private lateinit var remote: PhoneRemote
    private lateinit var airPods: AirPods
    private var airPodsConnected = false
    private var headphonesOverlay: View? = null
    private var airPodsPanel: AirPodsPanel? = null
    private var banner: View? = null
    /** Notifications (DreamNotices): the banners, the panel and the renewal sheet. */
    private val notices by lazy {
        DreamNoticesUi(this, object : DreamNoticesUi.Host {
            override fun homeFree() = resumed && saver == null && splash == null && setup == null && picker == null &&
                !DreamPlayerActivity.visible && !DreamSaver.dreaming
            override fun setHomeFocusable(on: Boolean) = this@MainActivity.setHomeFocusable(on)
            override fun runTarget(target: String) = false
            override fun chime() { if (Sounds.noticeOn(this@MainActivity) && !DreamPlayerActivity.visible && saver == null) sounds.notice() }
            override fun resumed() = this@MainActivity.resumed
        })
    }
    /** The debrid premium check (DebridExpiry): a few seconds after Home shows, so it never competes with the first frames. */
    private val expiryCheck = Runnable {
        DebridExpiry.maybeCheck(this)
        // Dream OS's own updates (DreamUpdate): the daily check, and the new-version notice card on a free Home.
        // Every launch also checks once, with a live "Checking for Updates…" banner (Dream Cinema; DreamNotices.live).
        DreamUpdate.onHome(this) { notices.canShowBanner() && !notices.bannerShowing }
    }
    private var saver: ScreensaverView? = null
    private val idleRunnable = Runnable { showSaver() }
    private var searchInput: EditText? = null
    private var remoteOverlay: View? = null
    private var weatherOverlay: View? = null
    private var adBlockOverlay: View? = null
    private var adBlockPanel: AdBlockPanel? = null
    private var keyHeld = false
    private var splashSparkles: SparkleView? = null
    private var splashGlow: View? = null
    private var splashSweep: View? = null

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            shell?.onTick()
            DebridExpiry.maybeCheck(c) // at most every ~6 h (a timestamp check otherwise)
        }
    }

    /** Time zone or clock format changed (Settings > Time & Region): every visible clock follows at once. */
    private val onTimeChanged: () -> Unit = { shell?.onTick() }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            appsDirty = true
            setup?.onPackagesChanged()
            val s = shell ?: return
            if (resumed && details == null) { apps = Apps.load(this@MainActivity); appsDirty = false; s.appsChanged() }
        }
    }

    // ---- lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DataMigration.consumeContinueSetup(this) // restored from another Fire TV: setup continues (before anything reads "set up")
        DreamTime.init(this) // the chosen time zone + clock format, before anything formats a time
        DreamTime.addListener(onTimeChanged)
        HomeRedirectService.ensureEnabled(this)
        Net.installHttpCache(this) // conditional TMDB requests after a restart
        // Background trailers (details page) never play under a menu, a sheet, the screensaver or setup.
        HeroTrailer.blocked = { saver != null || menuOverlay != null || splash != null || settingsPage != null || picker != null || setup != null }
        // The ratings prefetch (Ratings.kt) rests while the player, a trailer or the saver is up.
        Ratings.paused = { !resumed || saver != null || DreamPlayerActivity.visible || DreamSaver.dreaming || HeroTrailer.anyPlaying }
        sounds = Sounds(this)
        // Phone remote: its server runs only while a pairing code is on screen, or while it's on with a paired phone.
        remote = PhoneRemote(this, remoteCallbacks).also { it.onPaired = { name -> onPhonePaired(name) }; it.update() }
        airPods = AirPods(this) { updateAirPodsStatus() }
        AirPodsLink.init(this) // send to iPhone / bring to TV, listening stats, AirPods options
        airPods.connectedName { name -> airPodsConnected = name != null; updateAirPodsStatus() }
        registerReceiver(airPodsReceiver, IntentFilter(AirPods.ACTION_CONNECTION))
        apps = Apps.load(this)
        buildScreen()
        AlexaSearch.ui = alexaScreen
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        maybeShowSplash(intent.getBooleanExtra(EXTRA_SPLASH, false))
        // Cold start from Dream Player's "More like this": its details page once resumed.
        pendingOpenTitle = PlayFlow.titleFrom(intent.getStringExtra(EXTRA_OPEN_TITLE))
        backToPlayer = pendingOpenTitle != null && intent.getBooleanExtra(EXTRA_BACK_TO_PLAYER, false)
        pendingQuery = intent.getStringExtra(EXTRA_SEARCH_QUERY)
        // Recreated without the intro (it only plays once per process): still finish first-run setup.
        if (splash == null && !Profiles.isSetUp(this)) showSetup(firstRun = true)
        bootStep("apps")
        // Slow network: wrap up so the intro still ends on "Loading Complete" (Home's first row usually lands well before).
        handler.postDelayed({ bootTasks.keys.forEach { bootStep(it) } }, 7000)
        DreamNotices.setPresenter(notices)
    }

    /** "Finish Setting Up" (SetupGate) only over a settled Home: never over the boot logo, setup, the profile picker or the screensaver. */
    private fun maybeSetupPrompt() {
        handler.removeCallbacks(setupPromptCheck)
        handler.postDelayed(setupPromptCheck, 2500)
    }
    private val setupPromptCheck = Runnable {
        if (resumed && splash == null && setup == null && picker == null && saver == null && !DreamPlayerActivity.visible) SetupGate.maybePrompt(this)
    }

    /** [NativeResolver.warm] once the remote has rested [WARM_IDLE_MS] (not mid-scroll), or [WARM_MAX_WAIT_MS] after [since]. */
    private fun warmResolverWhenIdle(since: Long) {
        if (isFinishing) return
        if (PreResolve.sinceKeyMs() >= WARM_IDLE_MS || SystemClock.uptimeMillis() - since > WARM_MAX_WAIT_MS) NativeResolver.warm(this)
        else handler.postDelayed({ warmResolverWhenIdle(since) }, 400L)
    }

    override fun onResume() {
        super.onResume()
        // The scraper engine and DNS/TLS to the debrid services ahead of the first lookup (returns at once). Starting
        // Python takes seconds of CPU, which made the boot intro stutter and then the first scroll down Home: it waits
        // for the intro to end and then for the remote to rest (Play starts it on demand if it comes first).
        if (splash == null) warmResolverWhenIdle(SystemClock.uptimeMillis())
        else handler.postDelayed({ warmResolverWhenIdle(SystemClock.uptimeMillis()) }, SPLASH_TOTAL_MS + 1_500L)
        airPods.start()
        setup?.onResume() // back from Bluetooth / Accessibility settings: re-check setup status once
        maybeSetupPrompt()
        updateAirPodsStatus() // ...or unpaired: the top-bar AirPods chip follows
        resetIdle()
        handler.removeCallbacks(trailerAwake); handler.post(trailerAwake)
        resumed = true
        HomeRedirectService.onFireHomeVisible(this)
        Trakt.onHomeOpened(this) // throttled background sync; returns at once
        if (pendingAmazonSettings) {
            pendingAmazonSettings = false
            openAmazonSettings()
        }
        if (pendingHomeTop) { pendingHomeTop = false; goHomeTop() }
        pendingOpenTitle?.let { t -> pendingOpenTitle = null; handler.post { openDetails(t) } }
        pendingQuery?.let { q -> pendingQuery = null; handler.post { showSearchFor(q) } }
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        if (appsDirty) {
            // Home rebuilds only when the app list or the hidden apps really changed (the stick's
            // background system updates send package broadcasts that change neither).
            shell?.let { apps = Apps.load(this); appsDirty = false; it.appsResumed() }
        }
        shell?.onResume()
        // Back on a details page (from the player, say): its stream is looked for again once the remote rests.
        if (titlePage != null) { handler.removeCallbacks(preResolveRest); handler.postDelayed(preResolveRest, PRE_RESOLVE_REST_MS) }
        if (setup != null && findViewById<View>(android.R.id.content).visibility != View.VISIBLE) pauseHomeForSetup() // still under setup
        // Notices that waited (Dream Player, the screensaver) slide in once Home has settled; the premium check follows.
        DreamNotices.setPresenter(notices)
        notices.onHomeBack()
        handler.removeCallbacks(expiryCheck)
        handler.postDelayed(expiryCheck, 8_000)
    }

    override fun onPause() {
        // Fire OS's Home is bringing up Amazon's launcher: come straight back (before Amazon draws).
        HomeRedirectService.bounceBackIfHome(this)
        pauseWork()
        super.onPause()
    }

    /** Everything a pause stops or releases (bounceBackIfHome aside, which always comes first). */
    private fun pauseWork() {
        shell?.onPause()
        airPods.stop()
        handler.removeCallbacks(idleRunnable)
        handler.removeCallbacks(trailerAwake)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        closeSaver(animate = false)
        resumed = false
        unregisterReceiver(timeReceiver)
        notices.onPause()
        handler.removeCallbacks(expiryCheck)
    }

    // The system binds FireVoiceService, so this process never gets TRIM_MEMORY_UI_HIDDEN: release the
    // GPU cache and spare bitmaps ourselves once Home has been hidden a moment (a quick Home-and-back is free).
    private val releaseWhenHidden = Runnable { if (!resumed) releaseGraphics() }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(releaseWhenHidden)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(releaseWhenHidden)
        handler.postDelayed(releaseWhenHidden, 1500)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && !resumed) releaseGraphics() // in case the system ever sends one
    }

    private fun releaseGraphics() {
        try {
            Net.evictMemory() // shown posters stay in their views; the rest reload from the disk cache
            // The Fire OS screensaver is our window too: don't pull its renderer out from under it.
            shell?.releaseGraphics()
            // Dream Player is our window too: COMPLETE would destroy its renderer mid-playback, so only purge
            // the shared GPU caches then (UI_HIDDEN keeps the GL context and every window intact).
            if (!DreamSaver.dreaming) Hwui.trim(
                if (DreamPlayerActivity.visible) android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                else android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
            )
        } catch (e: Exception) {
            android.util.Log.w("FireHome", "releaseGraphics: $e")
        }
    }

    override fun onDestroy() {
        DreamTime.removeListener(onTimeChanged)
        handler.removeCallbacks(releaseWhenHidden)
        notices.destroy()
        shell?.destroy()
        // Static: mustn't keep this Activity alive (only clear our own, a newer instance may have set its own).
        if (WatchHistory.onChanged === onHistoryChanged) WatchHistory.onChanged = null
        if (AlexaSearch.ui === alexaScreen) AlexaSearch.ui = null
        unregisterReceiver(packageReceiver)
        sounds.release()
        remote.stop()
        airPods.stop()
        unregisterReceiver(airPodsReceiver)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Brought forward by the Alexa hand-off, which drives the screen itself (AlexaSearch.ui).
        if (intent.getBooleanExtra(EXTRA_ALEXA, false)) return
        // Screensaver preview for the developer (screenshots):
        //   adb shell am start -n com.firehome.launcher/.MainActivity --ez saver true
        // It shows once Home has resumed and settled; any key wakes it, as always.
        if (intent.getBooleanExtra(EXTRA_SAVER, false)) { handler.postDelayed({ showSaver() }, 1_200); return }
        if (intent.getBooleanExtra(EXTRA_SPLASH, false)) maybeShowSplash(force = true)
        if (intent.getBooleanExtra(EXTRA_AMAZON_SETTINGS, false)) {
            // onResume follows onNewIntent and would cancel it, so open from there.
            pendingAmazonSettings = true
            return
        }
        intent.getStringExtra(EXTRA_SEARCH_QUERY)?.let { pendingQuery = it; return }
        // Dream Player's "More like this" picked a show: its details page (opened from onResume, like above).
        PlayFlow.titleFrom(intent.getStringExtra(EXTRA_OPEN_TITLE))?.let {
            pendingOpenTitle = it
            backToPlayer = intent.getBooleanExtra(EXTRA_BACK_TO_PLAYER, false)
            return
        }
        // Home pressed: Fire OS bounces it off Amazon's launcher, so Dream OS is usually paused right now.
        // Either way it lands on the Home page, at its top (applied in onResume when paused).
        if (resumed) goHomeTop() else if (intent.getBooleanExtra(EXTRA_HOME_TOP, false)) pendingHomeTop = true
    }

    /** Set by a Home press that arrived while paused; onResume takes it. */
    private var pendingHomeTop = false

    private fun goHomeTop() {
        backToPlayer = false
        closeDetails()
        closeSettings()
        shell?.homePressed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android picks its own initial focus (the first focusable, i.e. the Wi-Fi icon) when the
        // window gains focus; put it on the page content instead.
        if (hasFocus && !initialFocusDone && details == null) {
            initialFocusDone = true
            shell?.focusContent()
        }
    }

    @Deprecated("Home screen has nowhere to go back to")
    override fun onBackPressed() {
        when {
            notices.handleBack() -> {} // the renewal sheet, then the Notifications panel
            screenFitOverlay != null -> closeScreenFit(save = false)
            menuOverlay != null -> closeMenu()
            pins.handleBack() -> {}
            setup != null -> if (setup?.handleBack() != true) closeSetup()
            picker != null -> if (picker?.handleBack() != true && picker?.canClose() == true) closeProfiles()
            remoteOverlay != null -> closePhoneRemote()
            weatherOverlay != null -> closeWeatherPage()
            themesOverlay != null -> closeThemesInfo()
            adBlockOverlay != null -> if (adBlockPanel?.handleBack() != true) closeAdBlock()
            headphonesOverlay != null -> if (airPodsPanel?.handleBack() != true) closeHeadphones()
            details != null -> if (titlePage?.handleBack() != true) {
                // Back to the title page this one was opened from, else out to where the first was opened.
                val prev = detailsStack.removeLastOrNull()
                when {
                    prev == null -> {
                        val toPlayer = backToPlayer && DreamPlayerActivity.canReturn()
                        backToPlayer = false
                        closeDetails()
                        // Opened from the player's X-Ray: back to the paused title, at the same spot.
                        if (toPlayer) PlayFlow.bringPlayerToFront(applicationContext)
                    }
                    prev.page != null -> restoreDetails(prev)
                    else -> openTitlePage(prev.title, null)
                }
            }
            settingsPage != null -> settingsPage?.handleBack()
            else -> shell?.handleBack()
        }
    }

    // ---- shared modal card (dimmed backdrop, centered card, focus kept inside) ----

    private fun openModal(card: View, focus: View, layered: Boolean = true): FrameLayout {
        val overlay = FrameLayout(this).apply {
            isClickable = true
            addView(card, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        }
        (window.decorView as FrameLayout).addView(overlay, MATCH_PARENT, MATCH_PARENT)
        // Lifted controls touch the card's content edges: only the card's own edge clips (it has padding to spare).
        (card as? ViewGroup)?.let { Ui.unclip(it) }
        // Back closes just this card and puts focus back on whatever opened it.
        currentFocus?.let { modalReturns[overlay] = it }
        setHomeFocusable(false)
        // The scrim (a themed dim, not flat black) fades on its own while the card rises in:
        // no full-screen offscreen layer, only the card is cached while it moves.
        val scrim = ColorDrawable(Ui.alpha(Ui.mix(bgTop, Color.BLACK, 0.45f), 0.74f)).apply { alpha = 0 }
        overlay.background = scrim
        scrimAnims[overlay] = android.animation.ObjectAnimator.ofInt(scrim, "alpha", 0, 255).apply {
            duration = Ui.OVERLAY_IN
            interpolator = Ui.EASE
            start()
        }
        frostBehind(overlay)
        card.alpha = 0f
        card.translationY = 26.dp.toFloat()
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        val a = card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.SPRING)
        if (layered) a.withLayer()
        a.start()
        focus.post { focus.requestFocus() }
        return overlay
    }

    private fun dismissModal(v: View) {
        val card = (v as? ViewGroup)?.getChildAt(0)
        if (card != null) {
            // Card drops back and fades while the dim lifts.
            card.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).translationY(10.dp.toFloat())
                .setDuration(Ui.SHEET_OUT).setInterpolator(Ui.EXIT).withLayer().start()
            fadeAway(v, Ui.OVERLAY_OUT, 40)
        } else {
            fadeAway(v, 180, 0)
        }
        setHomeFocusable(true)
        val back = modalReturns.remove(v)
        if (back == null || !back.isAttachedToWindow || !back.requestFocus()) {
            if (settingsPage == null) focusFirstIn(pageHost) else settingsPage?.focusInitial()
        }
    }

    private val modalReturns = java.util.WeakHashMap<View, View>()
    private val scrimAnims = java.util.WeakHashMap<View, android.animation.Animator>()

    /**
     * Frost: one tiny, blurred snapshot of the screen fades in behind the card (a twilight veil on
     * top keeps the card readable). Taken once per open and dropped when the overlay goes away.
     */
    private fun frostBehind(overlay: FrameLayout) {
        PremiumKit.frost(window) { bmp ->
            if (bmp == null) return@frost
            if (!overlay.isAttachedToWindow || overlay.alpha < 1f) { bmp.recycle(); return@frost }
            // Drawn as the overlay background under the dim (not a child, so the card stays child 0).
            val frost = android.graphics.drawable.BitmapDrawable(resources, bmp).apply { alpha = 0; isFilterBitmap = true }
            val dim = overlay.background
            overlay.background = android.graphics.drawable.LayerDrawable(arrayOf(frost, dim))
            android.animation.ObjectAnimator.ofInt(frost, "alpha", 0, 255).apply { duration = 280; start() }
            // With frost behind it, the dim can ease off so the blurred home screen shows through.
            scrimAnims.remove(overlay)?.cancel()
            (dim as? ColorDrawable)?.let { d -> android.animation.ObjectAnimator.ofInt(d, "alpha", d.alpha, 170).apply { duration = 280; start() } }
            overlay.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) { overlay.background = null; bmp.recycle() }
            })
        }
    }

    /**
     * Fades a closing overlay out and removes it. It stops taking focus at once, and is removed
     * even if its fade gets cut short, so an invisible card can never keep the remote's keys.
     */
    private fun fadeAway(v: View, duration: Long, delay: Long) {
        (v as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        v.isFocusable = false
        val remove = Runnable { (v.parent as? ViewGroup)?.removeView(v) }
        v.animate().alpha(0f).setStartDelay(delay).setDuration(duration).setInterpolator(Ui.SETTLE).withEndAction(remove).start()
        handler.postDelayed(remove, delay + duration + 150)
    }

    /** Background for centered cards: themed glass with a hairline edge. */
    private fun modalCardBackground(): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Ui.surface(this, 0.86f), Ui.alpha(Ui.mix(Ui.surface(this), bgTop, 0.45f), 0.9f))).apply {
            cornerRadius = Ui.R_XL.dp.toFloat()
            setStroke(maxOf(1, 1.dp), Ui.alpha(textColor, 0.08f))
        }

    private fun modalCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = modalCardBackground()
    }

    // ---- full-screen layers (profile picker, PIN pad), stacked like the modals above ----

    private val layers = ArrayList<View>()
    private val layerReturn = HashMap<View, View>() // focus to restore when the layer above it closes

    private val layerHost = object : ModalHost {
        override fun show(view: View, focus: View) {
            layers.lastOrNull()?.let { top ->
                currentFocus?.let { layerReturn[top] = it }
                (top as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            layers += view
            view.alpha = 0f
            (window.decorView as FrameLayout).addView(view, MATCH_PARENT, MATCH_PARENT)
            setHomeFocusable(false)
            view.animate().alpha(1f).setDuration(220).setInterpolator(SmoothScrollView.EASE).start()
            focus.post { focus.requestFocus() }
        }

        override fun hide(view: View) {
            if (!layers.remove(view)) return
            (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            view.animate().alpha(0f).setDuration(180).withEndAction { (view.parent as? ViewGroup)?.removeView(view) }.start()
            val top = layers.lastOrNull()
            if (top == null) {
                setHomeFocusable(true)
                focusFirstIn(pageHost)
                return
            }
            (top as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            val back = layerReturn.remove(top)
            top.post { if (top.findFocus() == null && back?.isAttachedToWindow == true) back.requestFocus() }
        }
    }

    private val pins by lazy { PinPrompt(this, layerHost) }

    // ---- profiles ----

    /** After the intro: "Who's watching?" when there is a choice to make (or the profile is PIN-locked). */
    private fun onIntroDone() {
        if (!Profiles.isSetUp(this)) return showSetup(firstRun = true)
        if (Profiles.all(this).size > 1 || Profiles.hasPin(this, Profiles.current(this).id)) showProfiles(atBoot = true)
    }

    /** At boot nobody is unlocked yet; from Settings the current profile is trusted (no PIN to re-pick or edit it). */
    private fun showProfiles(atBoot: Boolean) {
        if (picker != null) return
        val id = Profiles.current(this).id
        val p = ProfilePicker(this, pins, if (atBoot) null else id) { closeProfiles() }
        picker = p
        pickerProfile = id
        layerHost.show(p.view, p.initialFocus())
    }

    /** Edit or switch profile, from the sidebar's profile picture. */
    private fun showProfileMenu() = showMenu(Profiles.current(this).name, listOf(
        "Edit Profile" to {
            showProfiles(atBoot = false)
            picker?.editCurrent { closeProfiles(changed = true) }
        },
        "Switch Profile" to { showProfiles(atBoot = false) },
    ))

    /** Closes the picker, rebuilding the home screen (theme, favorites, My List) if the profile changed. */
    private fun closeProfiles(changed: Boolean = false) {
        val p = picker ?: return
        picker = null
        if (changed || Profiles.current(this).id != pickerProfile) buildScreen()
        layerHost.hide(p.view)
    }

    // ---- first-run setup ----

    /** Full-screen setup wizard. First run can't be backed out of; from Settings, BACK on the first step closes it. */
    private fun showSetup(firstRun: Boolean) {
        if (setup != null || picker != null) return
        val w = SetupWizard(this, pins, canCancel = !firstRun, host = setupHost)
        setup = w
        layerHost.show(w.view, w.initialFocus)
        // The wizard is opaque: stop drawing the home screen under it (hero art, trailers).
        handler.postDelayed({
            if (setup !== w) return@postDelayed
            findViewById<View>(android.R.id.content).visibility = View.INVISIBLE
            hideWindowBg(true) // the window's full-screen background too: one less full-screen pass per frame
            pauseHomeForSetup()
        }, 300)
    }

    /**
     * Setup covers the screen: nothing under it may keep animating (the featured-title rotation),
     * since every frame it asks for redraws the whole wizard too.
     */
    private fun pauseHomeForSetup() {
        shell?.onPause()
    }

    private fun resumeHomeAfterSetup() {
        if (!resumed) return // onResume restarts them
        shell?.onResume()
    }

    /** Closes the wizard and rebuilds home so the new name, picture and look show. */
    private fun closeSetup() {
        val w = setup ?: return
        setup = null
        hideWindowBg(false)
        findViewById<View>(android.R.id.content).visibility = View.VISIBLE
        layerHost.hide(w.view)
        buildScreen()
        resumeHomeAfterSetup()
    }

    private val setupHost = object : SetupWizard.Host {
        override fun airPodsConnected() = airPodsConnected
        override fun pairedAirPods(): String? = try { airPods.pairedDevice()?.let { it.name ?: "AirPods" } } catch (e: Exception) { null }
        override fun connectAirPods() = airPods.connect()
        override fun openBluetoothSettings() {
            try {
                openFireSettings("tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity")
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Not available on this device", Toast.LENGTH_SHORT).show()
            }
        }
        override fun remoteUrl() = remote.url
        override fun qrBitmap(url: String) = this@MainActivity.qrBitmap(url)
        override fun apps(): List<AppEntry> = apps
        override fun openAccessibilitySettings() {
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Not available on this device", Toast.LENGTH_SHORT).show()
            }
        }
        override fun finish() = closeSetup()
    }

    // ---- weather page ----

    /** AirPlay needs a receiver app; open AirScreen, or its Appstore page if it isn't installed yet. */
    private fun openAirPlay() {
        val pkg = "com.ionitech.airscreen"
        if (apps.any { it.pkg == pkg }) {
            launch(pkg)
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("amzn://apps/android?p=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Search \"AirScreen\" in the Appstore to add AirPlay", Toast.LENGTH_LONG).show()
        }
    }

    private fun askSleepTimer() {
        val choices = listOf(0, 15, 30, 45, 60, 90, 120)
        showMenu("Sleep Timer", choices.map { m ->
            (if (m == 0) "Off" else if (m < 60) "$m Minutes" else if (m % 60 == 0) "${m / 60} Hour${if (m > 60) "s" else ""}" else "${m / 60} h ${m % 60} min") to {
                HomeRedirectService.setSleepTimer(m)
                showBanner(if (m == 0) "Sleep Timer Off" else "Sleep Timer Set for $m Minutes", R.drawable.ic_moon)
            }
        })
    }

    private fun showWeatherPage() {
        if (weatherOverlay != null) return
        val card = modalCard().apply { setPadding(44.dp, 34.dp, 44.dp, 30.dp) }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(TextView(this).apply {
            text = "Loading forecast\u2026"
            textSize = 18f
            typeface = font
            setTextColor(Color.argb(200, 255, 255, 255))
        })
        val close = pillButton("Close", R.drawable.ic_arrow_up, primary = true) { closeWeatherPage() }
        card.addView(body, LinearLayout.LayoutParams(860.dp, WRAP_CONTENT))
        card.addView(close.apply { (layoutParams as LinearLayout.LayoutParams).apply { topMargin = 22.dp; marginEnd = 0; gravity = Gravity.CENTER_HORIZONTAL } })
        weatherOverlay = openModal(card, close)
        Weather.forecast(this) { f ->
            if (weatherOverlay == null) return@forecast
            body.removeAllViews()
            if (f == null) {
                body.addView(TextView(this).apply { text = "Couldn't load the forecast."; textSize = 18f; setTextColor(Color.WHITE) })
            } else {
                fillWeather(body, f)
            }
        }
    }

    private fun fillWeather(body: LinearLayout, f: Weather.Forecast) {
        val white = Color.WHITE
        val dim = Color.argb(170, 255, 255, 255)
        fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size; setTextColor(color); typeface = if (bold) fontBold else font
        }
        // Now: big icon + temperature + summary/place.
        val icon = label(f.nowIcon, 64f, white)
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon)
            addView(label("${f.nowF}\u00b0", 72f, white).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); setPadding(18.dp, 0, 22.dp, 0) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(f.nowSummary, 24f, white, bold = true))
                addView(label(f.place, 16f, dim))
            })
        })
        icon.scaleX = 0.6f; icon.scaleY = 0.6f
        icon.animate().scaleX(1f).scaleY(1f).setDuration(500).setInterpolator(OvershootInterpolator(1.6f)).start()
        // Next 12 hours.
        body.addView(label("NEXT 12 HOURS", 12f, dim, bold = true).apply { letterSpacing = 0.15f; setPadding(0, 24.dp, 0, 10.dp) })
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (h in f.hours) addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(label(h.label, 13f, dim))
                addView(label(h.icon, 26f, white).apply { setPadding(0, 4.dp, 0, 4.dp) })
                addView(label("${h.tempF}\u00b0", 18f, white, bold = true))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        // Next days with a temperature range bar scaled to the whole week.
        body.addView(label("5-DAY FORECAST", 12f, dim, bold = true).apply { letterSpacing = 0.15f; setPadding(0, 24.dp, 0, 6.dp) })
        val weekLow = f.days.minOf { it.lowF }
        val weekHigh = f.days.maxOf { it.highF }.coerceAtLeast(weekLow + 1)
        for (d in f.days) {
            val track = Ui.keepClip(FrameLayout(this).apply { background = roundedRect(Color.argb(40, 255, 255, 255), 4, null) })
            val span = View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.parseColor("#38BDF8"), Color.parseColor("#FBBF24"))).apply { cornerRadius = 4.dp.toFloat() }
            }
            val barW = 220.dp
            val start = ((d.lowF - weekLow) / (weekHigh - weekLow).toFloat() * barW).toInt()
            val width = (((d.highF - d.lowF) / (weekHigh - weekLow).toFloat()) * barW).toInt().coerceAtLeast(8.dp)
            track.addView(span, FrameLayout.LayoutParams(width, MATCH_PARENT).apply { leftMargin = start })
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8.dp, 0, 8.dp)
                addView(label(d.label, 18f, white, bold = true), LinearLayout.LayoutParams(150.dp, WRAP_CONTENT))
                addView(label(d.icon, 24f, white), LinearLayout.LayoutParams(50.dp, WRAP_CONTENT))
                addView(label(d.summary, 15f, dim), LinearLayout.LayoutParams(170.dp, WRAP_CONTENT))
                addView(label(if (d.rainPct > 0) "\uD83D\uDCA7 ${d.rainPct}%" else "", 14f, Color.parseColor("#7DD3FC")), LinearLayout.LayoutParams(90.dp, WRAP_CONTENT))
                addView(label("${d.lowF}\u00b0", 16f, dim).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(50.dp, WRAP_CONTENT).apply { marginEnd = 12.dp })
                addView(track, LinearLayout.LayoutParams(barW, 8.dp))
                addView(label("${d.highF}\u00b0", 16f, white, bold = true), LinearLayout.LayoutParams(60.dp, WRAP_CONTENT).apply { marginStart = 12.dp })
            })
        }
    }

    private fun closeWeatherPage() {
        val v = weatherOverlay ?: return
        weatherOverlay = null
        dismissModal(v)
    }

    // ---- screensaver ----

    private fun resetIdle() {
        handler.removeCallbacks(idleRunnable)
        val minutes = Theme.SAVER_MINUTES[Theme.get(this, Theme.SAVER)]
        if (minutes > 0 && resumed) handler.postDelayed(idleRunnable, minutes * 60_000L)
        // Once nothing has moved for a few seconds, drop the GPU's leftover offscreen buffers (Hwui).
        Hwui.trimWhenStill { resumed && splash == null && saver == null && !DreamPlayerActivity.visible && !DreamSaver.dreaming }
    }

    /**
     * A trailer playing on Home or a title page counts as watching: the screen stays awake (Fire OS's own
     * sleep timer holds off) while one plays, checked every few seconds; cleared the moment none does.
     */
    private val trailerAwake = object : Runnable {
        override fun run() {
            if (HeroTrailer.anyPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (resumed) handler.postDelayed(this, 5_000)
        }
    }

    private fun showSaver() {
        // Watching a trailer is watching: the idle countdown starts over (from when it ends) instead.
        if (HeroTrailer.anyPlaying) { resetIdle(); return }
        if (saver != null || splash != null || !resumed) return
        val v = ScreensaverView(this).apply { alpha = 0f }
        (window.decorView as FrameLayout).addView(v, MATCH_PARENT, MATCH_PARENT)
        saver = v
        v.start()
        // Once it's fully in, the home screen under the opaque saver stops being drawn: the slow zoom
        // redraws every frame, and drawing all of Home beneath it too made each frame miss vsync.
        v.animate().alpha(1f).setDuration(1200).withEndAction {
            val content = findViewById<View>(android.R.id.content)
            if (saver === v && content.visibility == View.VISIBLE) {
                content.visibility = View.INVISIBLE
                window.setBackgroundDrawable(null) // the window's full-screen gradient too
                saverHidContent = true
            }
        }.start()
    }

    /** The saver hid the home screen under it (restored when it closes). */
    private var saverHidContent = false

    private fun closeSaver(animate: Boolean = true) {
        val v = saver ?: return
        saver = null
        if (saverHidContent) {
            saverHidContent = false
            window.setBackgroundDrawable(Theme.background(this))
            findViewById<View>(android.R.id.content).visibility = View.VISIBLE
        }
        v.stop()
        if (animate) v.animate().alpha(0f).setDuration(400).withEndAction { (v.parent as? ViewGroup)?.removeView(v) }.start()
        else (v.parent as? ViewGroup)?.removeView(v)
        resetIdle()
        if (animate) notices.onHomeBack() // notices that arrived under the screensaver
    }

    // ---- AirPods ----

    private val airPodsReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (AirPods.isAirPodsDisconnected(i)) {
                airPodsConnected = false
                updateAirPodsStatus()
                // Not when they were just sent to the iPhone on purpose: they weren't put away.
                if (Theme.get(c, Theme.AIRPODS_SLEEP) == 1 && !AirPodsLink.justSentToPhone()) {
                    HomeRedirectService.setSleepTimer(10)
                    Toast.makeText(c, "AirPods Put Away · Sleep Timer Set for 10 Minutes", Toast.LENGTH_LONG).show()
                }
                return
            }
            if (!AirPods.isAirPodsConnected(i)) return
            airPodsConnected = true
            // Give the audio route a moment to switch so the intro sound plays in the AirPods.
            pendingIntro?.let { handler.postDelayed({ startPendingIntro() }, AUDIO_SETTLE_MS) }
            updateAirPodsStatus()
            airPods.refresh()
            if (resumed) showBanner("AirPods Connected") else Toast.makeText(c, "\uD83C\uDFA7 AirPods connected", Toast.LENGTH_SHORT).show()
        }
    }

    /** The AirPods state changed: the bar's AirPods control, setup and the open AirPods card follow. */
    private fun updateAirPodsStatus() {
        shell?.updateAirPods()
        setup?.refreshAirPods()
        airPodsPanel?.refresh()
    }

    /**
     * Text for the AirPods chip (always opens the AirPods card): "AirPods Connected · <model>" while
     * they're the TV's audio output, "AirPods Disconnected" while paired but not connected to the TV,
     * null (hide the chip) when no AirPods are paired. [airPodsConnected] tells the two states apart.
     */
    private fun airPodsChipLabel(): String? {
        if (!airPodsConnected) return if (airPods.pairedDevice() != null) "AirPods Disconnected" else null
        val model = airPods.last?.model?.takeIf { it != "AirPods" } ?: airPods.rememberedModel ?: "AirPods"
        return if (model == "AirPods") "AirPods Connected" else "AirPods Connected · $model"
    }

    /** The AirPods card (AirPodsPanel): send to iPhone / bring to TV, options and listening stats (battery only with a real reading). */
    private fun showHeadphones() {
        if (headphonesOverlay != null) return
        val panel = AirPodsPanel(this, airPods, accent, selectColor, font, fontBold) { msg -> showBanner(msg) }
        airPodsPanel = panel
        headphonesOverlay = openModal(panel.view, panel.focusView)
    }

    private fun closeHeadphones() {
        val v = headphonesOverlay ?: return
        headphonesOverlay = null
        airPodsPanel?.handleBack()
        airPodsPanel = null
        dismissModal(v)
        // One Back closes the card and lands on the AirPods chip it was opened from, ready to move on
        // (posted after dismissModal's own focus step, so this one wins).
        handler.post {
            if (shell?.focusAirPods() != true && currentFocus == null) shell?.focusContent()
        }
    }

    /** The Notifications panel (DreamNoticesUi): the bell in Dream Cinema's bar and Settings › Notifications open it. */
    private fun openNotices() = notices.openPanel()

    /**
     * A toast for a few seconds: a glass capsule with the icon in a small accent disc. Drops in with a soft
     * spring, rests, then lifts away; nothing keeps animating while it rests.
     */
    private fun showBanner(text: String, icon: Int = R.drawable.ic_headphones) {
        banner?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val iconView = ImageView(this).apply {
            setImageDrawable(getDrawable(icon)!!.mutate().apply { setTint(Theme.contrastOn(selectColor)) })
            setPadding(6.dp, 6.dp, 6.dp, 6.dp)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(selectColor) }
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp, 8.dp, 24.dp, 8.dp)
            background = Ui.rounded(context, Ui.surface(context, 0.97f), Ui.R_PILL, 1f, Ui.alpha(textColor, 0.10f))
            elevation = Ui.Z_SHEET.dp.toFloat()
            addView(iconView, LinearLayout.LayoutParams(32.dp, 32.dp).apply { marginEnd = 12.dp })
            addView(TextView(context).apply {
                this.text = text
                textSize = Ui.T_BODY
                typeface = Ui.semibold(font)
                includeFontPadding = false
                setTextColor(textColor)
            })
            alpha = 0f
            translationY = -36.dp.toFloat()
            scaleX = 0.94f
            scaleY = 0.94f
        }
        (window.decorView as FrameLayout).addView(pill, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = 30.dp
        })
        banner = pill
        pill.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(Ui.SPRING).withLayer().start()
        handler.postDelayed({
            pill.animate().alpha(0f).translationY(-24.dp.toFloat()).scaleX(0.96f).scaleY(0.96f).setDuration(260).setInterpolator(Ui.EXIT)
                .withLayer().withEndAction { (pill.parent as? ViewGroup)?.removeView(pill); if (banner === pill) banner = null }.start()
        }, 3200)
    }

    // ---- phone remote ----

    // One thread for phone-remote key presses (not one per press); keeps them in order, exits when idle.
    private val keyInjector = java.util.concurrent.ThreadPoolExecutor(1, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>()).apply { allowCoreThreadTimeOut(true) }

    private val remoteCallbacks = object : PhoneRemote.Callbacks {
        override fun onKey(key: String) {
            if (key == "home") { goHomeFromRemote(); return } // leaves other apps too, like the real remote
            if (!resumed) {
                // Fire OS Settings, the system UI and installers are never driven from a phone (Back and Home still work).
                if (key != "back" && key != "playpause" && PhoneRemote.isProtected(HomeRedirectService.foregroundPackage())) return
                // Fire OS won't let us inject keys into other apps: accessibility moves their focus.
                otherAppKey(key)
                return
            }
            val code = when (key) {
                "up" -> KeyEvent.KEYCODE_DPAD_UP
                "down" -> KeyEvent.KEYCODE_DPAD_DOWN
                "left" -> KeyEvent.KEYCODE_DPAD_LEFT
                "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
                "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
                "back" -> KeyEvent.KEYCODE_BACK
                "menu" -> KeyEvent.KEYCODE_MENU
                "playpause" -> { HomeRedirectService.mediaPlayPause(this@MainActivity); return }
                else -> return
            }
            // Injecting into our own window is allowed and drives normal focus navigation.
            keyInjector.execute { try { android.app.Instrumentation().sendKeyDownUpSync(code) } catch (e: Exception) {} }
        }

        private fun otherAppKey(key: String) {
            when (key) {
                "up" -> HomeRedirectService.moveFocus(View.FOCUS_UP)
                "down" -> HomeRedirectService.moveFocus(View.FOCUS_DOWN)
                "left" -> HomeRedirectService.moveFocus(View.FOCUS_LEFT)
                "right" -> HomeRedirectService.moveFocus(View.FOCUS_RIGHT)
                "ok" -> HomeRedirectService.clickFocused()
                "back" -> HomeRedirectService.globalBack()
                "playpause" -> HomeRedirectService.mediaPlayPause(this@MainActivity)
            }
        }

        override fun onText(text: String) {
            if (resumed && SetupText.phoneText(text)) return // a repository address or key on an Accounts / Scrapers screen
            if (resumed && setup?.phoneText(text) == true) return // typing into a setup field
            if (resumed && TraktPage.phoneText(text)) return // typing a Trakt app key
            if (!resumed) {
                if (PhoneRemote.isProtected(HomeRedirectService.foregroundPackage())) return // never types into Fire OS Settings or the system UI
                // Type into another app's focused field; otherwise search in Dream OS.
                if (HomeRedirectService.setFocusedText(text)) return
            }
            searchFromRemote(text)
        }

        private fun searchFromRemote(text: String) {
            if (!resumed) startActivity(Intent(this@MainActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            closePhoneRemote()
            closeDetails()
            showPage(Tab.SEARCH)
            searchInput?.let { it.setText(text); it.setSelection(text.length); it.requestFocus() }
        }

        override fun onLaunch(pkg: String) {
            // Only the apps the phone was offered (visible, not hidden), and never a settings or system app.
            if (PhoneRemote.isProtected(pkg) || apps().none { it.second == pkg }) return
            closeSaver(animate = false)
            launch(pkg)
        }

        override fun onPhoto(jpeg: ByteArray) {
            Net.background({ saveWallpaper(jpeg) }) { ok ->
                if (ok != true) return@background
                Theme.set(this@MainActivity, Theme.BG_TYPE, Theme.BG_PHOTO)
                buildScreen()
                Toast.makeText(this@MainActivity, "Wallpaper updated from your phone", Toast.LENGTH_SHORT).show()
            }
        }

        override fun apps(): List<Pair<String, String>> {
            val hidden = Apps.hidden(this@MainActivity)
            return apps.filter { it.pkg !in hidden }.map { it.label to it.pkg }
        }
    }

    private fun goHomeFromRemote() {
        if (resumed) {
            closePhoneRemote()
            closeDetails()
            showPage(Tab.HOME, focusContent = true)
        } else {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Center-crops to 16:9, scales to 1920x1080 and saves; runs off the UI thread. */
    private fun saveWallpaper(jpeg: ByteArray): Boolean {
        val targetRatio = 16f / 9f
        fun crop(w: Int, h: Int) = if (w / h.toFloat() > targetRatio) (h * targetRatio).toInt() to h else w to (w / targetRatio).toInt()
        // Bounds first, then decode subsampled (the 16:9 crop stays >= 1920x1080) in RGB_565, which is how
        // Theme shows it anyway: a 12 MP photo peaks at ~12 MB here instead of ~100 MB.
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val (fullW, fullH) = crop(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (fullW / (sample * 2) >= 1920 && fullH / (sample * 2) >= 1080) sample *= 2
        val src = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }) ?: return false
        val (cw, ch) = crop(src.width, src.height)
        val left = (src.width - cw) / 2
        val top = (src.height - ch) / 2
        // Crop and scale in one pass.
        val out = android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.RGB_565)
        android.graphics.Canvas(out).drawBitmap(src, android.graphics.Rect(left, top, left + cw, top + ch),
            android.graphics.Rect(0, 0, 1920, 1080), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        Theme.wallpaperFile(this).outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        out.recycle()
        return true
    }

    /**
     * Phone Remote: a QR code that pairs one phone (a fresh one-time code each time it's shown or a
     * phone pairs), what's paired, and the remote's switch. The bitmap exists only while this is open.
     */
    private fun showPhoneRemote() {
        if (remoteOverlay != null) return
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40.dp, 34.dp, 40.dp, 30.dp)
            background = modalCardBackground()
        }
        card.addView(TextView(this).apply {
            text = "Phone Remote"
            textSize = 24f
            typeface = fontBold
            setTextColor(Color.WHITE)
        })
        card.addView(TextView(this).apply {
            text = "Open the camera on your iPhone or Android phone and point it at the code."
            textSize = 15f
            typeface = font
            setTextColor(Color.argb(200, 255, 255, 255))
            setPadding(0, 8.dp, 0, 22.dp)
        })
        val qr = ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(14.dp, 14.dp, 14.dp, 14.dp)
        }
        card.addView(qr, LinearLayout.LayoutParams(260.dp, 260.dp))
        val address = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.argb(170, 255, 255, 255))
            setPadding(0, 18.dp, 0, 4.dp)
        }
        card.addView(address)
        card.addView(TextView(this).apply {
            text = "Remote  \u00b7  Type Searches  \u00b7  Open Apps  \u00b7  Send a Photo as Wallpaper"
            textSize = 13f
            typeface = font
            setTextColor(Color.argb(150, 255, 255, 255))
        })
        val status = TextView(this).apply {
            textSize = 14f
            typeface = font
            gravity = Gravity.CENTER
            setTextColor(Color.argb(200, 255, 255, 255))
            setPadding(0, 16.dp, 0, 0)
        }
        card.addView(status)
        val closeBtn = pillButton("Close", R.drawable.ic_arrow_up, primary = true) { closePhoneRemote() }
        lateinit var refresh: () -> Unit
        val toggle = pillButton("Turn Off", R.drawable.ic_phone, primary = false) { remote.setEnabled(!remote.enabled()); refresh() }
        val forget = pillButton("Forget Paired Phones", R.drawable.ic_phone, primary = false) {
            remote.forgetAll()
            showBanner("Paired Phones Forgotten", R.drawable.ic_phone)
            refresh()
            closeBtn.requestFocus()
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
        buttons.addView(closeBtn)
        buttons.addView(toggle)
        buttons.addView(forget.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = 0 })
        card.addView(buttons, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 22.dp })
        refresh = {
            val url = remote.pairingUrl()
            (qr.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let { old -> qr.setImageDrawable(null); old.recycle() }
            if (url != null) { qr.setImageBitmap(qrBitmap(url)); qr.visibility = View.VISIBLE } else qr.visibility = View.GONE
            address.text = url ?: "Not Connected to Wi-Fi"
            val n = remote.pairedPhones().size
            val phones = "$n Phone${if (n == 1) "" else "s"} Paired"
            status.text = when {
                n == 0 -> "No Phones Paired Yet"
                remote.enabled() -> "On  \u00b7  $phones"
                else -> "Off  \u00b7  $phones"
            }
            toggle.text = if (remote.enabled()) "Turn Off" else "Turn On"
            toggle.visibility = if (n > 0) View.VISIBLE else View.GONE
            forget.visibility = if (n > 0) View.VISIBLE else View.GONE
            (closeBtn.layoutParams as LinearLayout.LayoutParams).marginEnd = if (n > 0) 14.dp else 0
            closeBtn.requestLayout()
        }
        refresh()
        phoneRemoteRefresh = refresh
        remoteOverlay = openModal(card, closeBtn)
    }

    /** Refreshes the open Phone Remote card (a phone just paired: a new code, a new count). */
    private var phoneRemoteRefresh: (() -> Unit)? = null

    private fun onPhonePaired(name: String) {
        showBanner("$name Paired", R.drawable.ic_phone)
        phoneRemoteRefresh?.invoke()
    }

    private fun closePhoneRemote() {
        val v = remoteOverlay ?: return
        remoteOverlay = null
        phoneRemoteRefresh = null
        remote.closePairing()
        dismissModal(v)
    }

    /** Renders [text] as a crisp QR bitmap (1 px per module, scaled up with no smoothing by the ImageView). */
    private fun qrBitmap(text: String): android.graphics.Bitmap {
        val qr = io.nayuki.qrcodegen.QrCode.encodeText(text, io.nayuki.qrcodegen.QrCode.Ecc.MEDIUM)
        val scale = 8
        val bmp = android.graphics.Bitmap.createBitmap(qr.size * scale, qr.size * scale, android.graphics.Bitmap.Config.RGB_565)
        val px = IntArray(bmp.width * bmp.height)
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            px[y * bmp.width + x] = if (qr.getModule(x / scale, y / scale)) Color.BLACK else Color.WHITE
        }
        bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return bmp
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Ratings.onKey() // the hero's rating lookups wait for the remote to rest
        if (splash != null) return true
        if (DreamVoice.isVoiceKey(event.keyCode)) { onVoiceKey(event); return true }
        if (saver != null) {
            if (event.action == KeyEvent.ACTION_UP) closeSaver()
            return true
        }
        resetIdle()
        AutoScroll.poke() // a long synopsis eases back to its top and waits for the next rest
        ScreenCover.refresh(this) // the Home-press cover gets a picture of the screen as it is once it settles
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            PreResolve.keyPressed() // the next pause may be the user's doing
            // Instant Play: the page's stream is looked for once the remote has rested on it a moment.
            if (titlePage != null) { handler.removeCallbacks(preResolveRest); handler.postDelayed(preResolveRest, PRE_RESOLVE_REST_MS) }
        }
        // The details page's trailer has the screen: the key brings the page back and does nothing else.
        if (titlePage?.interceptKey(event) == true) return true
        if (setup?.ensureFocus(event) == true) return true // nothing focused under setup: back onto its step
        if (event.action == KeyEvent.ACTION_DOWN) keyHeld = event.repeatCount > 0
        if (event.action == KeyEvent.ACTION_UP && currentFocus != null && Theme.get(this, Theme.NAV_SOUND) == 1 &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) sounds.select()
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) pressFeedback(event)
        if (event.keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
            currentFocus?.takeIf { it.isLongClickable }?.performLongClick()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * The OK "click": the focused control dips on press and springs back on release. The release
     * runs before the click is handled, so whatever the click animates (focus moving on, a page
     * opening) takes over cleanly. Text fields keep their own OK behavior.
     */
    private fun pressFeedback(event: KeyEvent) {
        val v = currentFocus ?: return
        if (v is EditText || !v.isClickable) return
        when {
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> Ui.press(v)
            event.action == KeyEvent.ACTION_UP -> Ui.release(v)
        }
    }

    // ---- theme ----

    private fun loadTheme() {
        textColor = Theme.get(this, Theme.TEXT)
        dimColor = Theme.get(this, Theme.TEXT_DIM)
        accent = Theme.get(this, Theme.ACCENT)
        selectColor = Theme.get(this, Theme.SELECT)
        bgTop = Theme.get(this, Theme.BG_TOP)
        font = Theme.typeface(this)
        fontBold = Theme.typeface(this, Typeface.BOLD)
        btnPrimary = Theme.get(this, Theme.BTN_PRIMARY)
        btnSecondary = Theme.get(this, Theme.BTN_SECONDARY)
    }

    /** Builds the home screen (Dream Cinema's shell). Called at start and whenever the profile or its look changes. */
    private fun buildScreen() {
        loadTheme()
        window.setBackgroundDrawable(Theme.background(this))
        shell?.destroy()
        shell = null
        buildShell()
    }

    // ---- pages ----

    private fun showPage(p: Tab, force: Boolean = false, focusContent: Boolean = false) {
        val s = shell ?: return
        if (appsDirty) { apps = Apps.load(this); appsDirty = false; s.appsChanged() }
        // A forced refresh of "the current page" means the one the shell shows (its own tabs move without us).
        s.show(if (force && p == page) s.currentTab else p, force, focusContent)
        page = p
        searchInput = s.searchInput
    }

    // ---- boot loading bar + live status ----

    private fun bootStep(key: String) {
        bootDone.add(key) // the ticker below reveals steps at a readable pace
        // Cold start: the intro art fades in once Home's first build has landed (a beat after its first row).
        if (key == "home") artReveal?.let { handler.removeCallbacks(it); handler.postDelayed(it, ART_AFTER_HOME_MS) }
    }

    /** The intro art's fade-in, waiting for Home's first build on a cold start (so the two don't overlap). */
    private var artReveal: Runnable? = null

    private var bootShown = 0
    /** Fades the intro out; called once the loading bar has finished (or by a safety timeout). */
    private var finishSplash: (() -> Unit)? = null
    private var bootStart = 0L

    /** Advances the on-screen status one step at a time (~0.65 s each), waiting on steps that aren't done yet. */
    private val bootTicker = object : Runnable {
        override fun run() {
            val keys = bootTasks.keys.toList()
            if (bootShown < keys.size && keys[bootShown] in bootDone) bootShown++
            updateBootUi()
            if (bootStatus != null && bootShown < keys.size) {
                val late = SystemClock.uptimeMillis() - bootStart > 7600
                handler.postDelayed(this, if (late) 150 else BOOT_STEP_MS)
            }
        }
    }

    /** Colors the leading action word (or "Loading Complete") so each step reads at a glance. */
    /** The status line with its leading word (or "Loading Complete") in the flowing Dream rainbow, bold. */
    private fun colorizeBoot(view: TextView, text: String, done: Boolean): CharSequence {
        val end = if (done) "Loading Complete".length else text.indexOf(' ').takeIf { it > 0 } ?: text.length
        val span = android.text.SpannableString(Rainbow.flow(view, text, 0, end, 220.dp.toFloat()))
        span.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, end, 0)
        return span
    }

    private fun celebrateBoot() {
        if (Theme.get(this, Theme.INTRO_SOUND) == 1) sounds.chime()
        // Plain gradients, drawn directly: cheap for the GPU. (withLayer() rendered both into offscreen layers
        // on the UI thread as the swoosh started, a 32 ms freeze on its first frame.)
        splashGlow?.let { g ->
            g.animate().alpha(1f).scaleX(1.35f).scaleY(1.35f).setDuration(700).setInterpolator(SmoothScrollView.EASE).withEndAction {
                g.animate().alpha(0f).setDuration(1100).start()
            }.start()
        }
        splashSweep?.let { sw ->
            sw.alpha = 1f
            sw.animate().translationX(resources.displayMetrics.widthPixels + 260.dp.toFloat())
                .setDuration(1300).setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction { sw.alpha = 0f }.start()
        }
    }

    private fun updateBootUi() {
        val keys = bootTasks.keys.toList()
        // The bar glides for a whole step, so it fills steadily across the intro instead of jumping.
        bootBar?.animate()?.scaleX(bootShown / keys.size.toFloat())?.setDuration(BOOT_STEP_MS)
            ?.setInterpolator(android.view.animation.LinearInterpolator())?.start()
        val status = bootStatus ?: return
        val done = bootShown >= keys.size
        val text = if (done) "Loading Complete, Welcome!" else bootTasks.getValue(keys[bootShown])
        if (status.tag == text) return
        status.tag = text
        status.animate().alpha(0f).setDuration(110).withEndAction {
            status.text = colorizeBoot(status, text, done)
            if (done) {
                status.typeface = fontBold
                status.textSize = 18f
                celebrateBoot()
                // Bar is full and the welcome is showing: let it sit so the intro is on screen about 8 s in all
                // (at least 2.5 s of welcome however long loading took), then fade it out.
                val shown = android.os.SystemClock.uptimeMillis() - (if (bootStart > 0) bootStart else splashShownAt)
                handler.postDelayed({ finishSplash?.invoke() }, maxOf(1_500L, SPLASH_TOTAL_MS - shown - HOME_DRAW_LEAD_MS))
            }
            status.animate().alpha(1f).setDuration(220).start()
        }.start()
    }

    private fun focusFirstIn(v: View) {
        v.post {
            val list = ArrayList<View>()
            v.addFocusables(list, View.FOCUS_FORWARD)
            val card = list.firstOrNull { (it.tag as? String)?.let { t -> t.startsWith("app|") || t.startsWith("title|") } == true }
            (card ?: list.firstOrNull())?.requestFocus()
        }
    }

    // ---- Continue Watching (tracking lives in WatchHistory, Dream Player and AppWatch) ----

    private val onHistoryChanged: () -> Unit = { shell?.historyChanged(); titlePage?.historyChanged() }

    private fun resumeWatching(e: WatchHistory.Entry) {
        if (!WatchHistory.ours(e)) {
            if (!AppWatch.resume(this, e)) Toast.makeText(this, "Couldn't open that app", Toast.LENGTH_SHORT).show()
            return
        }
        val tv = e.isShow && e.season > 0 && e.episode > 0
        // Dream Player resumes at the saved spot.
        val at = if (!e.upNext && e.progress > 0f && e.progress < WatchHistory.FINISHED) e.positionMs else 0L
        PlayFlow.play(this, PlayFlow.request(tv, e.tmdbId, e.title, e.year, e.imdb, if (tv) e.season else 0, if (tv) e.episode else 0,
            startMs = at, backdropPath = e.backdropPath, posterPath = e.posterPath, episodeTitle = if (tv) e.episodeTitle else null))
    }

    // ---- New Episodes (data and "New" tracking live in NewEpisodes.kt) ----

    /** OK on a released episode: play it in Dream OS, or an installed app that streams the show. */
    private fun showEpisodeChoice(item: NewEpisodes.Item) {
        val show = item.show
        val installed = apps.map { it.pkg }.toSet()
        val options = ArrayList<Pair<String, () -> Unit>>()
        options += "Play S${item.ep.season} E${item.ep.number}" to { playNewEpisode(item) }
        for (svc in NewEpisodes.services(show, installed)) options += "Open in ${svc.name}" to { openOnService(svc, show.title()) }
        options += "" to {}
        options += "Show Details" to { openDetails(show.title()) }
        showMenu("${show.name}  ·  ${item.code()}", options)
    }

    private fun playNewEpisode(item: NewEpisodes.Item) {
        val show = item.show
        Net.background({ NewEpisodes.imdb(this, show) }) { imdb ->
            PlayFlow.play(this, PlayFlow.request(true, show.id, show.name, show.year, imdb, item.ep.season, item.ep.number,
                backdropPath = show.backdrop, posterPath = show.poster, episodeTitle = item.ep.name.ifBlank { null }))
        }
    }

    private fun pillButton(label: String, icon: Int, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = Ui.T_BODY
        typeface = Ui.semibold(font)
        gravity = Gravity.CENTER
        includeFontPadding = false
        tag = if (primary) "pill:primary" else "pill:secondary"
        setCompoundDrawables(getDrawable(icon)!!.mutate().apply { setBounds(0, 0, 18.dp, 18.dp) }, null, null, null)
        compoundDrawablePadding = 10.dp
        setPadding(22.dp, 0, 26.dp, 0)
        isFocusable = true
        isClickable = true
        stylePill(this, primary)
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, 46.dp).apply { marginEnd = 14.dp }
        id = View.generateViewId()
        setOnClickListener { onClick() }
        // Lift with a soft colored shadow, and one light sweep across the face as it lands.
        val sheen = SheenDrawable(Ui.R_MD.dp.toFloat())
        foreground = sheen
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            outlineSpotShadowColor = selectColor
            outlineAmbientShadowColor = selectColor
        }
        setOnFocusChangeListener { v, has ->
            Ui.lift(v, has, Ui.LIFT_PILL, 0)
            if (has) sheen.sweep() else sheen.cancel()
        }
        // Pearl face under the fading rainbow border (the face owns the background from here on).
        cinemaRowFocus(this, Ui.R_MD, if (primary) btnPrimary else Ui.alpha(btnSecondary, 0.82f), if (primary) 0 else Ui.alpha(textColor, 0.14f))
    }

    /** Focused: solid menu color, no outline. Unfocused: white (primary) or dark glass. */
    private fun stylePill(v: TextView, primary: Boolean) {
        val colors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), IntArray(0)),
            intArrayOf(Theme.contrastOn(selectColor), Theme.contrastOn(if (primary) btnPrimary else mix(btnSecondary, Color.BLACK, 0.4f))),
        )
        v.setTextColor(colors)
        v.compoundDrawableTintList = colors
        // Softer corners, a hairline edge on the glass ones, eased state fades.
        if (v.background is android.graphics.drawable.LayerDrawable) return // the Cinema focus face is already in place
        v.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(this@MainActivity, selectColor, Ui.R_MD))
            addState(IntArray(0), if (primary) Ui.rounded(this@MainActivity, btnPrimary, Ui.R_MD)
                else Ui.rounded(this@MainActivity, Ui.alpha(btnSecondary, 0.82f), Ui.R_MD, 1f, Ui.alpha(textColor, 0.14f)))
            setEnterFadeDuration(Ui.FOCUS_IN.toInt())
            setExitFadeDuration(Ui.FOCUS_OUT.toInt())
        }
    }

    // ---- Play (details page and hero; through the play flow like Continue Watching) ----

    /** The show's in-progress or "Up next" episode from the watch history, if there is one. */
    private fun nextEpisode(t: Title): WatchHistory.Entry? =
        if (t.type != "tv") null
        else WatchHistory.entries(this).firstOrNull { it.key == t.key && it.isShow && it.season > 0 && it.episode > 0 }

    /** Movie: plays it. Show: the episode from the watch history, or (always with [pick]) a season/episode chooser. */
    private fun watch(t: Title, pick: Boolean = false) {
        if (pick && t.type == "tv") return chooseEpisode(t)
        playTarget(t) { r -> if (r != null) PlayFlow.play(this, r) else chooseEpisode(t) }
    }

    /**
     * What the Play button plays for [t]: the movie; a show's episode from the watch history, else
     * (Dream Player's button says "Play S1 E1") the first aired season's first episode; null when it
     * would ask instead. [done] on the main thread (the season lookup runs in the background). Shared
     * by Play and Instant Play's pre-resolve, so both mean the very same request.
     */
    private fun playTarget(t: Title, done: (PlaybackRequest?) -> Unit) {
        val year = t.year.take(4).toIntOrNull()
        fun req(tv: Boolean, y: Int?, imdb: String?, season: Int = 0, episode: Int = 0) =
            PlayFlow.request(tv, t.id, t.name, y, imdb, season, episode, backdropPath = t.backdropPath, posterPath = t.posterPath)
        if (t.type != "tv") return done(req(false, year, null))
        val next = nextEpisode(t)
        if (next != null) return done(req(true, next.year, next.imdb, next.season, next.episode))
        Net.background({ airedSeasons(t.id).firstOrNull()?.first }) { first ->
            done(first?.let { req(true, year, null, it, 1) })
        }
    }

    // ---- Instant Play while browsing: focus resting on a card or the hero (HomeShell.Host.speculate) ----

    private var speculateTitle: Title? = null
    private val speculateRun: Runnable = object : Runnable {
        override fun run() {
            val t = speculateTitle
            // Lookups are a few small HTTPS calls off the main thread: they needn't wait for the remote to
            // rest (the stream prefetch itself still holds while it's in use). Without an account there's nothing to look for.
            when {
                t == null || titlePage != null || !resumed -> {}
                !NativeResolver.available(this@MainActivity) || PreResolve.covers(t.id, t.type == "tv") -> {}
                else -> playTarget(t) { r ->
                    if (r != null && speculateTitle?.key == t.key && titlePage == null) {
                        if (!PreResolve.speculate(this@MainActivity, r)) handler.postDelayed(this, 800) // the cooldown passes
                    }
                }
            }
        }
    }

    /**
     * Focus rests on [t] (null: on nothing playable): its stream is looked for after a short rest,
     * replacing the last (cheap lookups, ~1 s apart at most).
     */
    private fun speculate(t: Title?) {
        handler.removeCallbacks(speculateRun)
        speculateTitle = t
        if (t != null && InstantPlay.enabled(this)) handler.postDelayed(speculateRun, SPECULATE_NATIVE_REST_MS)
    }

    /** Instant Play: the stream the Play button would play for [t] is found while the page is being read. */
    private fun preResolve(t: Title, tries: Int = 0) {
        if (!InstantPlay.enabled(this) || !NativeResolver.available(this) || PreResolve.covers(t.id, t.type == "tv")) return
        playTarget(t) { r ->
            if (r != null && titlePage != null && detailsTitle?.key == t.key) {
                val now = PreResolve.start(this, r)
                // Back from the player onto the same page (it counts as active a while longer): once more shortly.
                if (!now && tries < 4) handler.postDelayed({ if (detailsTitle?.key == t.key) preResolve(t, tries + 1) }, 6_000)
            }
        }
    }

    /** One episode (or a movie when [episode] is 0) through the play flow; PlayFlow fills in the IMDb id / artwork when missing. */
    private fun playEpisode(t: Title, season: Int = 0, episode: Int = 0, year: Int? = t.year.take(4).toIntOrNull(), imdb: String? = null) {
        PlayFlow.play(this, PlayFlow.request(t.type == "tv", t.id, t.name, year, imdb, season, episode, backdropPath = t.backdropPath, posterPath = t.posterPath))
    }

    /** Menu-sheet chooser: a ready-to-play first row (next episode, or S1 E1), then the aired seasons. */
    private fun chooseEpisode(t: Title) {
        val owner = details
        val next = nextEpisode(t)
        Net.background({ airedSeasons(t.id) }) { seasons ->
            if (details !== owner || menuOverlay != null) return@background
            if (seasons.isNullOrEmpty()) {
                Toast.makeText(this, "Couldn't find episodes of ${t.name}", Toast.LENGTH_SHORT).show()
                return@background
            }
            if (seasons.size == 1 && next == null) return@background chooseEpisodeIn(t, seasons[0].first)
            val options = ArrayList<Pair<String, () -> Unit>>()
            options += if (next != null) {
                val verb = if (next.upNext || next.progress <= 0f) "Play next episode" else "Resume episode"
                "$verb · S${next.season} E${next.episode}" to { playEpisode(t, next.season, next.episode, next.year, next.imdb) }
            } else {
                "Play First Episode · S${seasons[0].first} E1" to { playEpisode(t, seasons[0].first, 1) }
            }
            options += "" to {}
            for ((n, count) in seasons) options += "Season $n · $count Episode${if (count == 1) "" else "s"}" to { chooseEpisodeIn(t, n) }
            detailsMenu(t.name, options)
        }
    }

    /** The aired episodes of one season; the first row (E1) has focus. */
    private fun chooseEpisodeIn(t: Title, season: Int) {
        val owner = details
        Net.background({
            val today = todayYmd()
            WatchHistory.seasonEpisodes(this, t.id, season).orEmpty()
                .filter { aired(it.optString("air_date"), today) }
                .map { it.optInt("episode_number") to it.optString("name") }
        }) { eps ->
            if (details !== owner || menuOverlay != null) return@background
            if (eps.isNullOrEmpty()) {
                Toast.makeText(this, "No episodes of season $season to play yet", Toast.LENGTH_SHORT).show()
                return@background
            }
            detailsMenu("${t.name} · Season $season", eps.map { (n, name) ->
                "Episode $n${if (name.isNotBlank() && name != "Episode $n") " · $name" else ""}" to { playEpisode(t, season, n) }
            })
        }
    }

    /** Aired seasons as (number, episode count), specials left out. Background thread. */
    private fun airedSeasons(showId: Int): List<Pair<Int, Int>> {
        val a = WatchHistory.details(this, "tv", showId)?.optJSONArray("seasons") ?: return emptyList()
        val today = todayYmd()
        return (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.optInt("season_number") > 0 && it.optInt("episode_count") > 0 && aired(it.optString("air_date"), today) }
            .map { it.optInt("season_number") to it.optInt("episode_count") }
            .sortedBy { it.first }
    }

    private fun todayYmd(): String = DreamTime.todayYmd(this)

    private fun aired(date: String, today: String) = date.isNotEmpty() && date != "null" && date <= today

    /**
     * showMenu that also works over the details page, which sits outside the Home views showMenu blocks:
     * Left/Right stay in the sheet, and Home is blocked again behind the page once the sheet closes.
     */
    private fun detailsMenu(title: String, options: List<Pair<String, () -> Unit>>, icons: Map<String, Drawable> = emptyMap()) {
        val reblock = { if (details != null && menuOverlay == null) setHomeFocusable(false) }
        showMenu(title, options.map { (label, action) -> label to { action(); reblock() } }, icons)
        val sheet = menuOverlay ?: return
        if (details == null) return
        val rows = ArrayList<View>()
        fun collect(v: View) {
            if (v.isFocusable && v !is ScrollView) rows += v
            if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
        }
        collect(sheet)
        for (r in rows) {
            if (r.id == View.NO_ID) r.id = View.generateViewId()
            r.nextFocusLeftId = r.id
            r.nextFocusRightId = r.id
        }
        rows.firstOrNull()?.let { it.nextFocusUpId = it.id }
        rows.lastOrNull()?.let { it.nextFocusDownId = it.id }
        sheet.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) = reblock()
        })
    }

    // ---- details overlay ----

    /** Where focus goes back to when the details page closes (the card that opened it). */
    private var detailsReturn: View? = null

    private fun openDetails(t: Title) = openTitlePage(t, null)

    /** Back onto a stacked title page kept whole: the current page leaves, the earlier one returns as it was. */
    private fun restoreDetails(e: StackedPage) {
        val page = e.page ?: return openTitlePage(e.title, null)
        titlePage?.let { cur -> val v = details; cur.animateOut { (v?.parent as? ViewGroup)?.removeView(v); cur.release() } }
        titlePage = page
        details = page.view
        detailsTitle = e.title
        PreResolve.lookingAt(this, e.title)
        (window.decorView as FrameLayout).addView(page.view, MATCH_PARENT, MATCH_PARENT)
        val f = e.focus
        page.view.post { if (titlePage === page && (f == null || !f.isAttachedToWindow || !f.requestFocus())) page.focusInitial() }
        e.page = null; e.focus = null
    }

    private fun closeDetails() {
        detailsStack.forEach { dropStacked(it) }
        detailsStack.clear()
        val v = details ?: return
        val d = titlePage ?: return
        detailsTitle = null
        handler.removeCallbacks(preResolveRest)
        // The page's own exit, then it lets go of its images.
        titlePage = null
        details = null
        shell?.root?.visibility = View.VISIBLE
        d.animateOut { (v.parent as? ViewGroup)?.removeView(v); d.release() }
        setHomeFocusable(true)
        val back = detailsReturn
        detailsReturn = null
        if (back == null || !back.isAttachedToWindow || !back.requestFocus()) focusFirstIn(pageHost)
    }

    /** Opens [t] in the service's app via its search deep link, or just launches the app if it has none. */
    private fun openOnService(svc: Tmdb.Service, t: Title) {
        val link = svc.search?.format(Uri.encode(t.name))
        if (link != null) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(svc.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
            }
        }
        Toast.makeText(this, "Opening ${svc.name} — search for \"${t.name}\"", Toast.LENGTH_LONG).show()
        launch(svc.pkg)
    }

    /**
     * [t]'s trailer, full screen in Dream Player (its own lookup, its calm curtain, "Trailer Unavailable"
     * when there is none). Never another app. [startMs] continues the details page's background trailer
     * when the player finds the same video ([videoKey]).
     */
    private fun playTrailerFor(t: Title, startMs: Long = 0, videoKey: String? = null) {
        try {
            startActivity(DreamPlayerActivity.trailerIntent(this, t, startMs, videoKey))
        } catch (e: Exception) {
            android.util.Log.w("FireHome", "trailer: couldn't open Dream Player: $e")
            showBanner("Trailer Unavailable", R.drawable.ic_movie)
        }
    }

    // ---- menus & actions ----

    private fun launch(pkg: String) {
        val intent = Apps.launchIntent(this, pkg)
        try {
            if (intent == null) throw IllegalStateException()
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open that app", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppMenu(app: AppEntry) {
        val installed = apps.map { it.pkg }.toSet()
        val allFavs = Apps.favorites(this)
        val favs = allFavs.filter { it in installed }.toMutableList()
        val missing = allFavs.filter { it !in installed }
        val refresh = { showPage(page, force = true); restoreFocus("app|${app.pkg}") }
        val saveFavs = { Apps.saveFavorites(this, favs + missing); refresh() }
        val idx = favs.indexOf(app.pkg)

        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (idx < 0) {
            options += "Add to Your Apps" to { favs += app.pkg; saveFavs() }
        } else {
            if (idx > 0) options += "Move Left" to { favs.add(idx - 1, favs.removeAt(idx)); saveFavs() }
            if (idx < favs.size - 1) options += "Move Right" to { favs.add(idx + 1, favs.removeAt(idx)); saveFavs() }
            options += "Remove from Your Apps" to { favs.removeAt(idx); saveFavs() }
        }
        options += "Hide from Apps" to { Apps.saveHidden(this, Apps.hidden(this) + app.pkg); refresh() }
        options += "Force Stop" to {
            Toast.makeText(this, "Stopping ${app.label}…", Toast.LENGTH_SHORT).show()
            HomeRedirectService.forceStop(this, app.pkg, app.label)
        }
        if (!app.system) {
            options += "Uninstall" to { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}"))) }
        }
        options += "App Info" to {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
        showMenu(app.label, options)
    }

    private fun showTitleMenu(t: Title) {
        val saved = Tmdb.inMyList(this, t)
        showMenu(t.name, listOf(
            "Details" to { openDetails(t) },
            (if (saved) "Remove from My List" else "Add to My List") to {
                Tmdb.toggleMyList(this, t)
                if (page == Tab.MYLIST) showPage(page, force = true, focusContent = true)
            },
            "Play Trailer" to { playTrailerFor(t) },
        ))
    }

    private fun restoreFocus(tag: String) {
        pageHost.post { pageHost.findViewWithTag<View>(tag)?.requestFocus() }
    }

    /** A mic key that reaches Dream OS (other remotes / keyboards; Fire OS keeps its own for Alexa). */
    private fun onVoiceKey(e: KeyEvent) {
        if (e.action == KeyEvent.ACTION_UP) return
        if (e.repeatCount > 0 || !DreamVoice.claimTrigger()) return
        DreamVoice.onMicKey(true)
        showSearchFor("") // Dream Search, ready to type
    }

    // ---- Alexa hand-off (AlexaSearch.kt): Dream Search stays on screen the whole time ----

    /** Where the user was when Alexa started listening (to go back if it wasn't a search). */
    private var alexaPrevAway = false
    private var alexaPrevPage: Tab? = null

    private val alexaScreen = object : AlexaSearch.Screen {
        override fun isFront() = resumed
        override fun alexaListening(prevPkg: String?) {
            // Setup's voice step shows the test itself; Dream Search stays put underneath.
            if (setup?.alexaListening() == true) return // the cover snapshots the screen itself (ScreenCover)
            alexaPrevAway = prevPkg != null && prevPkg != packageName
            alexaPrevPage = if (alexaPrevAway) null else page
            if (saver != null) closeSaver(animate = false)
            closeMenu()
            closePhoneRemote()
            closeDetails()
            closeSettings()
            showPage(Tab.SEARCH, focusContent = true)
            (searchInput as? QueryField)?.onAlexa?.invoke(true)
        }

        override fun alexaQuery(q: String) {
            if (setup?.alexaHeard(q) == true) return // setup's voice step shows what was heard
            showSearchFor(q) // while hidden: it's on screen with the query the moment it's shown again
        }

        override fun alexaCancelled() {
            if (setup?.alexaCancelled() == true) return
            (searchInput as? QueryField)?.onAlexa?.invoke(false)
            if (alexaPrevAway) moveTaskToBack(true)
            else alexaPrevPage?.let { if (it != Tab.SEARCH) showPage(it, focusContent = true) }
            alexaPrevPage = null
            alexaPrevAway = false
        }
    }

    /** Dream Search with [q] searched, focus on the results (Alexa's spoken query). */
    private fun showSearchFor(q: String) {
        if (saver != null) closeSaver(animate = false)
        closeMenu()
        closePhoneRemote()
        closeDetails()
        closeSettings()
        showPage(Tab.SEARCH, focusContent = true)
        handler.post {
            val f = searchInput
            val run = (f as? QueryField)?.onSearch
            if (run != null) run(q) else f?.let { it.setText(q); it.setSelection(q.length) }
        }
    }

    /** Private DNS ad blocking: provider choice + status card (logic lives in AdBlock). */
    private fun showAdBlock() {
        if (adBlockOverlay != null) return
        val panel = AdBlockPanel(this, accent, selectColor, font, fontBold) { msg -> showBanner(msg, R.drawable.ic_shield) }
        adBlockPanel = panel
        adBlockOverlay = openModal(panel.view, panel.focusView)
        panel.view.translationY = 18.dp.toFloat()
        panel.view.animate().translationY(0f).setDuration(280).setInterpolator(SmoothScrollView.EASE).start()
    }

    private fun closeAdBlock() {
        val v = adBlockOverlay ?: return
        adBlockOverlay = null
        adBlockPanel = null
        dismissModal(v)
    }

    /** Locks or unlocks the home screen under overlays so focus can't slip underneath them. */
    private fun setHomeFocusable(enabled: Boolean) {
        (findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? ViewGroup)?.descendantFocusability =
            if (enabled) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    /**
     * Settings › Close Dream OS: Fire TV's own home comes back and Dream OS closes completely (its
     * task and process). The Home button stays Amazon's until Dream OS is opened again (or the TV restarts).
     */
    private fun closeToAmazonHome() {
        HomeRedirectService.setAmazonHome(this, true)
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(HomeRedirectService.AMAZON_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            android.util.Log.w("FireHome", "Close Dream OS: Amazon home didn't open: $e")
        }
        finishAndRemoveTask()
        // Once Amazon's home is up: end the process (Fire OS restarts the Home service on its own; it stays idle).
        handler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 800)
    }

    /** Amazon's full settings menu, which lives inside its launcher. */
    private fun openAmazonSettings() {
        HomeRedirectService.enterAmazonSettings()
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }

    private fun openFireSettings(activity: String) {
        startActivity(Intent().setClassName(FIRE_SETTINGS, "$FIRE_SETTINGS.$activity"))
    }

    private var menuOverlay: View? = null
    private var menuReturn: View? = null

    /**
     * Dream OS option sheet: a night-sky card with soft glass groups; icons and the focused row take the
     * theme's highlight colors (a glowing gradient pill). An empty label starts a new group.
     */
    private fun showMenu(title: String, options: List<Pair<String, () -> Unit>>, icons: Map<String, Drawable> = emptyMap()) {
        if (menuOverlay != null || options.none { it.first.isNotEmpty() }) return
        // Side room for the focused row's lift: the scroll view clips at its edges, which used to
        // square off the highlight's corners.
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 4.dp, 14.dp, 8.dp)
            clipChildren = false
            clipToPadding = false
        }
        var group: LinearLayout? = null
        var first: View? = null
        val menuRows = ArrayList<View>()
        for ((label, action) in options) {
            if (label.isEmpty()) { group = null; continue }
            val g = group ?: menuGroup().also {
                list.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = if (list.childCount == 0) 0 else 16.dp })
                group = it
            }
            if (g.childCount > 0) g.addView(View(this).apply { setBackgroundColor(Color.argb(16, 255, 255, 255)) },
                LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { marginStart = if (icons[label] != null || menuIcon(label) != null) 62.dp else 18.dp })
            val row = menuRow(label, icons[label]) {
                closeMenu()
                try {
                    action()
                } catch (e: Exception) {
                    android.util.Log.w("FireHome", "menu '$label' failed", e) // the toast is generic; the cause goes to the log
                    Toast.makeText(this, "Not available on this device", Toast.LENGTH_SHORT).show()
                }
            }
            g.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 54.dp))
            menuRows += row
            if (first == null) first = row
        }
        val rows = options.count { it.first.isNotEmpty() }
        val groups = 1 + options.count { it.first.isEmpty() }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Themed glass sheet: quiet title, a short capsule in the highlight color under it.
            background = modalCardBackground()
            setPadding(24.dp, 24.dp, 24.dp, 18.dp)
            addView(TextView(context).apply {
                text = title
                textSize = Ui.T_HEADLINE
                typeface = Ui.semibold(font)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(textColor)
                setPadding(16.dp, 0, 16.dp, 10.dp)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(View(context).apply { background = Ui.rounded(context, selectColor, Ui.R_PILL) },
                LinearLayout.LayoutParams(28.dp, 3.dp).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = 18.dp })
            // Long lists (Settings) scroll; the ScrollView follows the focused row. Its edges melt into the sheet
            // through SmoothScrollView.softEdges (no Android fading edges: an offscreen layer every frame).
            addView(SmoothScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                clipChildren = false
                addView(list)
                // The sheet's color where the list's edge sits: its glass gradient (surface to a touch of the
                // page tint, 0.86 to 0.9 opaque) over the 0.74 scrim over the dark page.
                val surface = Ui.surface(context, 1f)
                val behind = Ui.mix(bgTop, Ui.mix(bgTop, Color.BLACK, 0.45f), 0.74f)
                fun sheetAt(f: Float) = Ui.mix(behind, Ui.mix(surface, Ui.mix(surface, bgTop, 0.45f), f), 0.86f + 0.04f * f)
                var placed = -1
                addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
                    val h = (v.parent as? View)?.height ?: 0
                    if (h > 0 && top * 31 + bottom != placed) {
                        placed = top * 31 + bottom
                        softEdges(sheetAt(top / h.toFloat()), sheetAt(bottom / h.toFloat()), 28.dp)
                    }
                }
            }, LinearLayout.LayoutParams(560.dp, minOf(rows * 55 + groups * 16 - 4, 430).dp))
        }
        menuReturn = currentFocus
        // The sheet rises in (openModal); its first rows follow a beat behind, top to bottom.
        menuOverlay = openModal(card, first!!, layered = false)
        Ui.rise(menuRows.take(7), fromDp = 10, startDelay = 70L, step = 24L, duration = 260L)
    }

    private fun menuGroup() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = 18.dp.toFloat()
            setColor(Ui.alpha(textColor, 0.045f))
            setStroke(1, Ui.alpha(textColor, 0.06f))
        }
        clipChildren = false
        clipToPadding = false
    }

    /** Icon for well-known menu entries; null for plain choices like "15 minutes". */
    private fun menuIcon(label: String): Int? {
        val l = label.lowercase()
        return MENU_ICONS.firstOrNull { (key, _) -> key in l }?.second
    }

    /** [image]: a picture shown as is instead of the label's tinted icon. */
    private fun menuRow(label: String, image: Drawable? = null, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(14.dp, 0, 16.dp, 0)
        isFocusable = true
        isClickable = true
        val onPill = Theme.contrastOn(selectColor)
        val fg = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), IntArray(0)),
            intArrayOf(onPill, withAlpha(textColor, 0.92f)))
        // Icons rest in the text color (the highlight color is saved for focus).
        val iconRest = withAlpha(textColor, 0.78f)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(context, selectColor, Ui.R_MD))
            setEnterFadeDuration(120)
            setExitFadeDuration(Ui.FOCUS_OUT.toInt())
            addState(IntArray(0), ColorDrawable(Color.TRANSPARENT))
        }
        // One rounded face (clear at rest, solid pearl focused) under the fading rainbow border (CinemaRowFocus).
        val cinemaFocus = CinemaRowFocus(this, Ui.R_MD, Color.TRANSPARENT)
        val icon = menuIcon(label)
        if (image != null) {
            addView(ImageView(context).apply {
                setImageDrawable(image)
                scaleType = ImageView.ScaleType.FIT_CENTER
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, 8.dp.toFloat())
                }
            }, LinearLayout.LayoutParams(34.dp, 34.dp).apply { marginEnd = 16.dp })
        } else if (icon != null) {
            addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), IntArray(0)), intArrayOf(onPill, iconRest))
                isDuplicateParentStateEnabled = true
                setPadding(7.dp, 7.dp, 7.dp, 7.dp)
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(withAlpha(onPill, 0.16f)) })
                    addState(IntArray(0), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(withAlpha(textColor, 0.07f)) })
                }
            }, LinearLayout.LayoutParams(34.dp, 34.dp).apply { marginEnd = 16.dp })
        } else {
            setPadding(18.dp, 0, 16.dp, 0)
        }
        addView(TextView(context).apply {
            text = label
            textSize = 17f
            typeface = font
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(fg)
            isDuplicateParentStateEnabled = true
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            text = "\u203A"
            textSize = 24f
            includeFontPadding = false
            setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), IntArray(0)),
                intArrayOf(withAlpha(onPill, 0.8f), withAlpha(textColor, 0.3f))))
            isDuplicateParentStateEnabled = true
        })
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, has ->
            cinemaFocus.focus(has)
            Ui.lift(v, has, Ui.LIFT_ROW + 0.01f, 0)
        }
    }

    // ---- the home screen: Dream Cinema's shell ----

    private var shell: CinemaShell? = null

    private fun buildShell() {
        val s = CinemaShell(this, shellHost)
        android.util.Log.w("FireHome", "home shell: ${s.javaClass.simpleName} (${Theme.NAME})")
        shell = s
        pageHost = s.content
        setContentView(s.root)
        if (details != null) setHomeFocusable(false)
        s.root.viewTreeObserver.addOnGlobalFocusChangeListener { old, now ->
            if (old != null && now != null && splash == null && Theme.get(this, Theme.NAV_SOUND) == 1) sounds.tick(keyHeld)
        }
        WatchHistory.onChanged = onHistoryChanged
        s.show(page, force = true, focusContent = true)
        searchInput = s.searchInput
        applyScreenFit()
    }

    private val shellHost = object : HomeShell.Host {
        override fun apps() = this@MainActivity.apps
        override fun logo() = sidebarLogo
        override fun launch(pkg: String) = this@MainActivity.launch(pkg)
        override fun appMenu(app: AppEntry) = showAppMenu(app)
        override fun titleMenu(t: Title) = showTitleMenu(t)
        override fun openDetails(t: Title, from: View?) = openTitlePage(t, from)
        override fun speculate(t: Title?) = this@MainActivity.speculate(t)
        override fun resume(e: WatchHistory.Entry) = resumeWatching(e)
        override fun continueMenu(e: WatchHistory.Entry, t: Title, card: View) {
            showMenu(t.name, listOf(
                (if (e.upNext || e.progress <= 0f) "Play" else "Resume") to { resumeWatching(e) },
                "Details" to { this@MainActivity.openDetails(t) },
                "Remove from Continue Watching" to {
                    WatchHistory.remove(this@MainActivity, e.key)
                    shell?.continueRemoved()
                },
            ))
        }
        override fun episodeChoice(item: NewEpisodes.Item) = showEpisodeChoice(item)
        override fun watch(t: Title, pick: Boolean) = this@MainActivity.watch(t, pick)
        override fun canPlay() = true
        override fun homeReady() = bootStep("home")
        override fun playTrailerFor(t: Title) = this@MainActivity.playTrailerFor(t)
        override fun settings() = showSettings()
        override fun profileMenu() = showProfileMenu()
        override fun phoneRemote() = showPhoneRemote()
        override fun weather() = showWeatherPage()
        override fun airPods() = showHeadphones()
        override fun airPodsLabel() = airPodsChipLabel()
        override fun notices() = openNotices()
        override fun systemView(): View = SystemHealth(this@MainActivity) { label, icon, onClick -> pillButton(label, icon, primary = false, onClick = onClick) }.view
        override fun resumed() = this@MainActivity.resumed
        // Not under the boot intro or Settings either: building a page ahead holds the main thread 50-300 ms,
        // which showed as hitches in the intro's zoom and in Settings' scrolling.
        override fun idle() = menuOverlay == null && details == null && picker == null && setup == null && splash == null && settingsPage == null
    }

    // ---- title pages & Settings ----

    private var titlePage: TitlePage? = null
    /** The title whose details page is open (Instant Play's pre-resolve is for it). */
    private var detailsTitle: Title? = null
    private val preResolveRest = Runnable { detailsTitle?.takeIf { titlePage != null }?.let { preResolve(it) } }

    /**
     * Title pages opened from another title page (More Like This, cast…), oldest first: Back steps back
     * through them one page at a time before leaving to where the first one was opened from. The newest
     * [LIVE_PAGES] are kept whole (detached, not released), so Back lands on the very row and card the
     * viewer left from; older ones keep only their title and reopen at the top.
     */
    private class StackedPage(val title: Title, var page: TitlePage?, var focus: View?)
    private val detailsStack = ArrayList<StackedPage>()

    /** Lets go of a stacked page kept whole (its view is already detached). */
    private fun dropStacked(e: StackedPage) { e.page?.release(); e.page = null; e.focus = null }

    /**
     * Opens [t]'s title page. Over a page that's already open it swaps pages in place: [stack] (a title
     * opened from that page) remembers the current one for Back; the home screen's return focus is kept.
     */
    private fun openTitlePage(t: Title, from: View?, stack: Boolean = false) {
        if (KidsFilter.gate(this, t) { openTitlePage(t, from, stack) }) return // over this profile's rating limit (or still checking)
        val old = titlePage
        if (old != null) {
            val v = details
            val oldTitle = detailsTitle
            titlePage = null
            details = null
            if (stack && oldTitle != null) {
                // Kept whole for Back: detached as it is (scroll, tab, season, focused card), not released.
                detailsStack.add(StackedPage(oldTitle, old, v?.findFocus()))
                (v?.parent as? ViewGroup)?.removeView(v)
                detailsStack.filter { it.page != null }.dropLast(LIVE_PAGES).forEach { dropStacked(it) }
            } else {
                old.animateOut { (v?.parent as? ViewGroup)?.removeView(v); old.release() }
            }
        } else {
            detailsStack.clear()
            detailsReturn = currentFocus
        }
        val d: TitlePage = CinemaDetails(this, t, detailsHost)
        titlePage = d
        detailsTitle = t
        handler.removeCallbacks(speculateRun)
        PreResolve.lookingAt(this, t) // a search for another title stops; this one's stream, if ready, comes back to disk
        handler.postDelayed(preResolveRest, PRE_RESOLVE_OPEN_MS) // most Plays come within a second or two of opening
        (window.decorView as FrameLayout).addView(d.view, MATCH_PARENT, MATCH_PARENT)
        details = d.view
        setHomeFocusable(false)
        var rect: android.graphics.Rect? = null
        var bmp: android.graphics.Bitmap? = null
        if (from != null) {
            val r = android.graphics.Rect()
            if (from.getGlobalVisibleRect(r)) rect = r
            bmp = cardBitmap(from)
        }
        d.animateIn(rect, if (rect != null) bmp else null)
        d.view.post { if (titlePage === d) d.focusInitial() }
        // The page covers the whole screen once it's in: stop drawing the home screen under it.
        handler.postDelayed({ if (titlePage === d) shell?.root?.visibility = View.INVISIBLE }, 480)
    }

    /** The art bitmap shown in a card, if it has loaded. */
    private fun cardBitmap(v: View): android.graphics.Bitmap? {
        if (v is ImageView) return (v.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        if (v is ViewGroup) for (i in 0 until v.childCount) cardBitmap(v.getChildAt(i))?.let { return it }
        return null
    }

    private val detailsHost = object : TitlePage.Host {
        override fun installed() = apps.map { it.pkg }.toSet()
        override fun canPlay() = true
        override fun nextEpisode(t: Title) = this@MainActivity.nextEpisode(t)
        override fun watch(t: Title, pick: Boolean) = this@MainActivity.watch(t, pick)
        override fun playEpisode(t: Title, season: Int, episode: Int) = this@MainActivity.playEpisode(t, season, episode)
        override fun resume(e: WatchHistory.Entry) = resumeWatching(e)
        override fun openOnService(svc: Tmdb.Service, t: Title) = this@MainActivity.openOnService(svc, t)
        override fun playTrailer(t: Title, startMs: Long, videoKey: String?) = this@MainActivity.playTrailerFor(t, startMs, videoKey)
        override fun openDetails(t: Title) = openTitlePage(t, null, stack = true)
        override fun titleMenu(t: Title) = showTitleMenu(t)
    }

    private var settingsPage: PremiumSettings? = null
    private var settingsHidden: Runnable? = null

    /** Settings: a full-screen, frosted page with categories on the left. */
    private fun showSettings() {
        if (settingsPage != null) return
        val p = Profiles.current(this)
        val s = PremiumSettings(this, window, PremiumSettings.Header(p.name, p.emoji, p.color), settingsSections(), object : PremiumSettings.Host {
            override fun close() = closeSettings()
        })
        settingsPage = s
        settingsReturn = currentFocus
        (window.decorView as FrameLayout).addView(s.view, MATCH_PARENT, MATCH_PARENT)
        setHomeFocusable(false)
        s.animateIn()
        s.view.post { if (settingsPage === s) s.focusInitial() }
        // The frost covers the home screen completely: stop drawing it underneath.
        // Once its frost is fully in, the window's own full-screen background under it goes too: each would
        // otherwise cost a full-screen pass on every frame (the focus glow alone redraws ~20 times a second).
        var tries = 0
        settingsHidden = object : Runnable {
            override fun run() {
                if (settingsPage !== s) return
                shell?.root?.visibility = View.INVISIBLE
                if (s.opaque) hideWindowBg(true) else if (++tries < 10) handler.postDelayed(this, 200)
            }
        }.also { handler.postDelayed(it, 420) }
    }

    /** The window background set aside while an opaque full-screen page covers it ([hideWindowBg]). */
    private var windowBgAside: android.graphics.drawable.Drawable? = null

    private fun hideWindowBg(hide: Boolean) {
        if (hide) {
            if (windowBgAside != null) return
            windowBgAside = window.decorView.background ?: return
            window.setBackgroundDrawable(null)
        } else {
            val bg = windowBgAside ?: return
            windowBgAside = null
            // A rebuild meanwhile (a theme change) may have set a fresh one: keep that.
            if (window.decorView.background == null) window.setBackgroundDrawable(bg)
        }
    }

    private fun closeSettings(then: (() -> Unit)? = null) {
        val s = settingsPage ?: return
        settingsPage = null
        settingsHidden?.let { handler.removeCallbacks(it) }
        hideWindowBg(false)
        shell?.root?.visibility = View.VISIBLE
        s.animateOut { (s.view.parent as? ViewGroup)?.removeView(s.view); s.release() }
        setHomeFocusable(true)
        // Back to the control that opened Settings (the gear), not the top of the page.
        val back = settingsReturn
        settingsReturn = null
        if (back == null || !back.isAttachedToWindow || !back.requestFocus()) shell?.focusBar()
        then?.invoke()
    }

    private var settingsReturn: View? = null

    /**
     * Keeps interactive content clear of TV overscan by [percent] of the screen per side (backdrops
     * and backgrounds stay full-bleed). Live: the calibration calls it on every step.
     */
    private fun applyScreenFit(percent: Int = ScreenFit.get(this)) {
        val (h, v) = ScreenFit.insets(this, percent).let { it[0] to it[1] }
        shell?.applyInsets(h, v)
    }

    private fun refreshSettings() = settingsPage?.refresh(settingsSections())

    private var themesOverlay: View? = null

    /** Settings › Themes: Dream Cinema is the look; downloadable and user-made themes are a future update (no picker). */
    private fun showThemesInfo() {
        if (themesOverlay != null) return
        val card = modalCard().apply { setPadding(44.dp, 34.dp, 44.dp, 30.dp); gravity = Gravity.CENTER_HORIZONTAL }
        card.addView(TextView(this).apply {
            text = "Themes"
            textSize = Ui.T_HEADLINE
            typeface = Ui.semibold(font)
            setTextColor(dimColor)
            gravity = Gravity.CENTER
        })
        card.addView(TextView(this).apply {
            text = Theme.NAME
            textSize = 30f
            typeface = fontBold
            letterSpacing = Ui.TRACK_TITLE
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(0, 6.dp, 0, 0)
        })
        card.addView(View(this).apply { background = Ui.rounded(context, selectColor, Ui.R_PILL) },
            LinearLayout.LayoutParams(28.dp, 3.dp).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = 14.dp; bottomMargin = 16.dp })
        card.addView(TextView(this).apply {
            text = Theme.FUTURE_NOTE
            textSize = Ui.T_BODY
            typeface = font
            setTextColor(dimColor)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }, LinearLayout.LayoutParams(560.dp, WRAP_CONTENT))
        val close = pillButton("Close", R.drawable.ic_arrow_up, primary = true) { closeThemesInfo() }
        card.addView(close.apply { (layoutParams as LinearLayout.LayoutParams).apply { topMargin = 26.dp; marginEnd = 0; gravity = Gravity.CENTER_HORIZONTAL } })
        themesOverlay = openModal(card, close)
    }

    private fun closeThemesInfo() {
        val v = themesOverlay ?: return
        themesOverlay = null
        dismissModal(v)
    }

    /**
     * Settings, grouped like a TV's own: who's watching, the services Dream Core plays from, alerts,
     * how things play, what sources are allowed, how Dream OS looks, Home, devices, network, the
     * system, Advanced (API keys) and
     * About. Every row does something; values sit on the right, a short caption under the name.
     */
    private fun settingsSections(): List<PremiumSettings.Section> {
        fun I(label: String, value: String?, icon: Int?, toggle: Boolean?, caption: String? = null, tone: Int? = null, action: () -> Unit) =
            PremiumSettings.Item(label, value, icon, toggle, caption, tone, action = action)
        val sleep = HomeRedirectService.sleepMinutesLeft()
        val hidden = Apps.hidden(this)
        val navSound = Theme.get(this, Theme.NAV_SOUND) == 1
        val introSound = Theme.get(this, Theme.INTRO_SOUND) == 1
        val chime = Theme.get(this, Theme.NEW_EP_CHIME) == 1
        val saverIdx = Theme.get(this, Theme.SAVER).coerceIn(0, Theme.SAVER_MINUTES.size - 1)
        val f = DreamFilters.current(this)
        val stored = DreamFilters.stored(this)
        // Source filters are Dream OS's own (DreamFilters), read by Dream Core on the next lookup.
        fun filter(key: String, value: String) { DreamFilters.put(this, key, value); refreshSettings() }
        fun flag(key: String, on: Boolean) = filter(key, if (on) "false" else "true")
        val qualities = listOf("4k" to "4K", "1080p" to "1080p", "720p" to "720p", "sd" to "SD")
        val qIdx = qualities.indexOfFirst { it.first == stored["quality.max"] }
        val subLangs = listOfNotNull(stored["subs.lang1"], stored["subs.lang2"]).filter { it.isNotBlank() }.distinct()
        val profile = Profiles.current(this)
        // About's update line: asked again in the background when it's over six hours old (AboutPage, DreamUpdate.status).
        AboutPage.refreshIfDue(this) { refreshSettings() }
        return listOfNotNull(
            PremiumSettings.Section("Profiles", SettingsGlyph.PERSON, listOf(
                I(if (Profiles.all(this).size > 1) "Switch Profile" else "Profiles", profile.name.takeIf { it.isNotBlank() }, R.drawable.ic_home, null,
                    "Add, Edit or Switch Who's Watching") { closeSettings { showProfiles(atBoot = false) } },
                I("Run Setup Again", null, R.drawable.ic_flame, null, "Walk Through Dream OS Setup Step by Step") {
                    closeSettings { showSetup(firstRun = false) }
                },
            )),
            // DreamSetupPages.kt: the same Accounts and Scrapers pages setup uses.
            PremiumSettings.Section("Accounts & Scrapers", SettingsGlyph.KEY, listOf(
                I("Accounts", DreamSettingsPage.accountsValue(this), R.drawable.ic_shield, null, "Your Debrid Services and Trakt") {
                    closeSettings { DreamSettingsPage.open(this, DreamSettingsPage.ACCOUNTS) }
                },
            ) + TraktPage.settingsItems(this) { refreshSettings() } + listOf( // TraktPage.kt: always shown (own Trakt app when the build has no keys)
                I("Scrapers", DreamSettingsPage.scrapersValue(this), R.drawable.ic_info, null, "Your Scraper Repositories and Providers") {
                    closeSettings { DreamSettingsPage.open(this, DreamSettingsPage.SCRAPERS) }
                },
                // DreamSetupMore.kt: the same page as setup's Playback Preferences.
                I("Sources & Quality", PlaybackPrefs.summary(this), R.drawable.ic_settings, null, "Quality, Languages, Subtitles and Size Limits") {
                    closeSettings { DreamPanelPage.openPlayback(this) }
                },
                // BackupTransfer.kt: sends everything to a new stick over your home network.
                I("Back Up Dream OS", null, R.drawable.ic_shield, null, "Move Everything to a New Fire TV Stick") {
                    closeSettings { BackupPage.open(this, restore = false) }
                },
                I("Unlock Features", UnlockSettings.value(this), R.drawable.ic_shield, null, "Home Button, Startup, Voice Search and More") {
                    closeSettings { UnlockSettings.open(this) }
                },
            )),
            PremiumSettings.Section("Notifications", LineIcon.BELL, listOf(
                I("Notifications", DreamNotices.unreadCount(this).let { if (it == 0) "All Caught Up" else if (it == 1) "1 Unread" else "$it Unread" },
                    R.drawable.ic_info, null, "Account Alerts and Expiry Reminders") { closeSettings { openNotices() } },
                (Theme.get(this, Theme.ACCOUNT_SUMMARY) == 1).let { on ->
                    I("Account Summary at Startup", null, R.drawable.ic_shield, on, "Time Left on Each Account When Dream OS Starts") {
                        Theme.set(this, Theme.ACCOUNT_SUMMARY, if (on) 0 else 1); refreshSettings()
                    }
                },
                I("Check Premium Accounts", DebridExpiry.summary(this), R.drawable.ic_shield, null, "Asks Each Linked Service When Its Plan Ends") {
                    showBanner("Checking Premium Accounts…", R.drawable.ic_shield)
                    DebridExpiry.checkNow(this) { n ->
                        refreshSettings()
                        showBanner(if (n > 0) "Premium Accounts Checked" else "Couldn't Reach the Services Right Now", R.drawable.ic_shield)
                    }
                },
            )),
            PremiumSettings.Section("Playback", SettingsGlyph.PLAY, listOf(
                I("Instant Play", null, R.drawable.ic_bolt, InstantPlay.enabled(this), "Finds the Stream While You Browse, So Play Starts at Once") {
                    InstantPlay.setEnabled(this, !InstantPlay.enabled(this)); refreshSettings()
                },
                // Dream Player's play flow (PlayFlow / PostPlay).
                I("Autoplay Next Episode", null, R.drawable.ic_play, PlayFlow.autoplayNext(this), "Up Next Counts Down at the Credits") {
                    PlayFlow.setAutoplayNext(this, !PlayFlow.autoplayNext(this)); refreshSettings()
                },
                I("Are You Still Watching?", null, R.drawable.ic_moon, PlayFlow.stillWatchingCheck(this), "Asks After 3 Episodes in a Row") {
                    PlayFlow.setStillWatchingCheck(this, !PlayFlow.stillWatchingCheck(this)); refreshSettings()
                },
                I("Skip Intros Automatically", null, R.drawable.ic_play, SkipMarkers.autoSkipIntros(this), "When Dream Player Knows Where the Intro Ends") {
                    SkipMarkers.setAutoSkipIntros(this, !SkipMarkers.autoSkipIntros(this)); refreshSettings()
                },
                I("Subtitles", null, R.drawable.ic_movie, f.subsOn, "Turns On Subtitles in Your Language") { flag("subs.enabled", f.subsOn) },
                I("Subtitle Languages", subLangs.joinToString(", ").ifEmpty { "English" }, R.drawable.ic_movie, null, "First and Second Choice") { closeSettings { DreamPanelPage.openPlayback(this) } },
                (stored["subs.embedded"] != "false").let { on ->
                    I("Use Subtitles in the Video First", null, R.drawable.ic_movie, on, "Before Downloading Any") { flag("subs.embedded", on) }
                },
                I("Subtitle Size", listOf("Small", "Medium", "Large", "Extra Large")[PlayerPrefs.size(this)], R.drawable.ic_movie, null) {
                    PlayerPrefs.setSize(this, (PlayerPrefs.size(this) + 1) % PlayerPrefs.SIZES.size); refreshSettings()
                },
                I("Subtitle Style", PlayerPrefs.STYLES[PlayerPrefs.style(this)], R.drawable.ic_movie, null) {
                    PlayerPrefs.setStyle(this, (PlayerPrefs.style(this) + 1) % PlayerPrefs.STYLES.size); refreshSettings()
                },
                I("Subtitle Position", PlayerPrefs.POSITIONS[PlayerPrefs.position(this)], R.drawable.ic_movie, null) {
                    PlayerPrefs.setPosition(this, (PlayerPrefs.position(this) + 1) % PlayerPrefs.POSITIONS.size); refreshSettings()
                },
            )),
            PremiumSettings.Section("Sources & Quality", SettingsGlyph.SLIDERS, listOf(
                I("Highest Quality", if (qIdx < 0) "Automatic" else qualities[qIdx].second, R.drawable.ic_movie, null, "Hides Anything Better Than This") {
                    filter("quality.max", qualities[if (qIdx < 0) 1 else (qIdx + 1) % qualities.size].first)
                },
                I("Hide AV1", null, R.drawable.ic_shield, f.hideAv1, "Some AV1 Videos Restart This Fire TV") { flag("hide.av1", f.hideAv1) },
                I("Hide HEVC (H.265)", null, R.drawable.ic_shield, f.hideHevc) { flag("hide.hevc", f.hideHevc) },
                I("Hide HDR", null, R.drawable.ic_shield, f.hideHdr, "For TVs Without HDR") { flag("hide.hdr", f.hideHdr) },
                I("Hide Dolby Vision", null, R.drawable.ic_shield, f.hideDv, "Keeps Files That Also Have HDR") { flag("hide.dv", f.hideDv) },
                I("Hide CAM Copies", null, R.drawable.ic_shield, f.hideCam, "Recorded in a Cinema") { flag("hide.cam", f.hideCam) },
                I("HDR and Dolby Vision First", null, R.drawable.ic_movie, f.preferHdr) { flag("prefer.hdr", f.preferHdr) },
                I("All Source Settings", null, R.drawable.ic_settings, null, "Size Limits, Languages and Your Soundbar") { closeSettings { DreamPanelPage.openPlayback(this) } },
            )),
            PremiumSettings.Section("Appearance", SettingsGlyph.SPARKLE, listOfNotNull(
                I("Themes", Theme.NAME, R.drawable.ic_flame, null, "Your Look · More Themes Are Coming") { showThemesInfo() },
                // A photo sent from the phone remote is the background: one row puts Dream Cinema's own back.
                if (Theme.get(this, Theme.BG_TYPE) == Theme.BG_PHOTO) I("Remove Phone Wallpaper", null, R.drawable.ic_phone, null, "Back to Dream Cinema's Own Background") {
                    Theme.clearPhoto(this); buildScreen(); refreshSettings()
                    showBanner("Wallpaper Removed", R.drawable.ic_phone)
                } else null,
                I("Screen Fit", ScreenFit.get(this).let { if (it == 0) "Normal" else "$it% Inset" }, R.drawable.ic_tv, null, "Keeps Everything Inside Your TV's Edges") { showScreenFit() },
                I("Background Trailers", HeroTrailer.label(this), R.drawable.ic_movie, null, "Behind the Featured Title on Home") {
                    HeroTrailer.cycle(this); refreshSettings()
                },
                I("Navigation Sounds", null, R.drawable.ic_play, navSound, "Soft Ticks as You Move") {
                    Theme.set(this, Theme.NAV_SOUND, if (navSound) 0 else 1); refreshSettings()
                },
                I("Intro Sound", null, R.drawable.ic_play, introSound, "The Chime When Dream OS Opens") {
                    Theme.set(this, Theme.INTRO_SOUND, if (introSound) 0 else 1); refreshSettings()
                },
                Sounds.noticeOn(this).let { on ->
                    I("Notification Sound", null, R.drawable.ic_play, on, "A Soft Chime When a Notification Appears") {
                        Theme.set(this, Theme.NOTICE_SOUND, if (on) 0 else 1); refreshSettings()
                    }
                },
            )),
            PremiumSettings.Section("Home & Apps", LineIcon.APPS, listOfNotNull(
                I("Weather", null, R.drawable.ic_bolt, null, "Your Local Forecast") { showWeatherPage() },
                I("New Episode Chime", null, R.drawable.ic_tv, chime, "When a Show You Watch Has a New Episode") {
                    Theme.set(this, Theme.NEW_EP_CHIME, if (chime) 0 else 1); refreshSettings()
                },
                if (hidden.isNotEmpty()) I("Show Hidden Apps", "${hidden.size} Hidden", R.drawable.ic_apps, null) {
                    Apps.saveHidden(this, emptySet()); showPage(page, force = true); refreshSettings()
                } else null,
                I("Manage Applications", null, R.drawable.ic_apps, null, "Fire TV's App Settings") { openFireSettings("tv.applications.ApplicationsActivity") },
            )),
            // TimeSettings.kt: every time Dream OS shows follows this zone and clock format (DreamTime).
            PremiumSettings.Section("Time & Region", SettingsGlyph.CLOCK, TimeSettings.settingsItems(this) { refreshSettings() }),
            PremiumSettings.Section("Devices", LineIcon.HEADPHONES, listOf(
                I("AirPods", airPodsChipLabel() ?: "Not Paired", R.drawable.ic_headphones, null, "Pair and Connect") { showHeadphones() },
                I("Phone Remote", remote.pairedPhones().size.let { n -> if (n == 0) null else if (remote.enabled()) "On" else "Off" }, R.drawable.ic_phone, null,
                    "Use Your iPhone or Android Phone") { showPhoneRemote() },
                I("AirPlay from iPhone", null, R.drawable.ic_tv, null, "Mirror Your iPhone With AirScreen") { openAirPlay() },
                I("Controllers & Bluetooth", null, R.drawable.ic_headphones, null, "Remotes, Game Controllers and Speakers") {
                    openFireSettings("tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity")
                },
            )),
            PremiumSettings.Section("Network & Ad Blocking", SettingsGlyph.WIFI, listOf(
                I("Ad Blocking", AdBlock.status(this).label, R.drawable.ic_shield, null, "Private DNS for Apps and Websites") { showAdBlock() },
                I("Network", null, R.drawable.ic_wifi, null, "Wi-Fi and Ethernet") { openFireSettings("tv.network.NetworkActivity") },
            )),
            PremiumSettings.Section("System", SettingsGlyph.CHIP, listOf(
                I("System Health", null, R.drawable.ic_info, null, "Storage, Memory and Streaming Status") {
                    closeSettings { showPage(Tab.SYSTEM, focusContent = true) }
                },
                I("Sleep Timer", if (sleep > 0) "$sleep Min Left" else "Off", R.drawable.ic_moon, null, "Puts the Fire TV to Sleep Later") { askSleepTimer() },
                I("Screensaver", if (saverIdx == 0) "Off" else "After ${Theme.SAVER_MINUTES[saverIdx]} Min", R.drawable.ic_moon, null, "When Nothing Moves for a While") {
                    Theme.set(this, Theme.SAVER, (saverIdx + 1) % Theme.SAVER_MINUTES.size); refreshSettings()
                },
                I("Preview Screensaver", null, R.drawable.ic_moon, null) { closeSettings { showSaver() } },
                I("My Fire TV", null, R.drawable.ic_tv, null, "Device Info, Updates and Restart") { openFireSettings("tv.device.DeviceActivity") },
                I("Accessibility", null, R.drawable.ic_info, null) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                I("All Fire TV Settings", null, R.drawable.ic_settings, null, "The Amazon Menu") { openAmazonSettings() },
                I("Close Dream OS", null, R.drawable.ic_home, null, "Back to the Fire TV Home · Open Dream OS From Your Apps") { closeToAmazonHome() },
            )),
            // Advanced: API keys for title data and ratings.
            PremiumSettings.Section("Advanced", LineIcon.GEAR, listOf(
                // The key pages (KeyPages.kt): Added / Not Added, Show Me How, If Something Goes Wrong, What to Try.
                I("TMDB Key", if (Tmdb.hasKey(this)) "Added" else "Not Added", R.drawable.ic_movie, null, "Titles, Artwork and Search") {
                    DreamPanelPage.openKey(this, KeyPages.Kind.TMDB) { refreshSettings(); showPage(page, force = true) }
                },
                // Rotten Tomatoes and IMDb scores (Ratings.kt); without a key those badges just stay hidden.
                I("OMDb Key", if (Ratings.omdbEnabled(this)) "Added" else "Not Added", R.drawable.ic_movie, null, "Rotten Tomatoes and IMDb Scores") {
                    DreamPanelPage.openKey(this, KeyPages.Kind.OMDB) { refreshSettings(); showPage(page, force = true) }
                },
            )),
            // UpdatePage.kt: Dream OS's own updates; hidden when the build has no releases repository. The dot: a new version waits.
            PremiumSettings.Section("Updates", SettingsGlyph.SPARKLE, UpdatePage.settingsItems(this) { refreshSettings() },
                badge = DreamUpdate.available(this) != null).takeIf { DreamUpdate.enabled(this) },
            PremiumSettings.Section("About", SettingsGlyph.INFO, listOf(
                // AboutPage.kt: the version (BuildConfig), whether it's up to date, source code and disclaimer as QR codes, credits.
                I("About Dream OS", "Version ${BuildConfig.VERSION_NAME}", R.drawable.ic_info, null, AboutPage.statusText(this)) {
                    AboutPage.open(this) { refreshSettings() }
                },
                I("What's Inside", "Version ${BuildConfig.VERSION_NAME}", R.drawable.ic_info, null, "Every Feature at a Glance, With System Details") {
                    closeSettings { showPage(Tab.SYSTEM, focusContent = true) }
                },
                I("Replay Intro", null, R.drawable.ic_play, null, "The Dream OS Opening, Once More") { closeSettings { maybeShowSplash(force = true) } },
                I("Source Code", "GitHub", R.drawable.ic_info, null, "Open Source  ·  GPL-3.0  ·  QR Code in About") {
                    AboutPage.open(this) { refreshSettings() }
                },
                // TMDB's terms: attribution wherever its data is used.
                // A legal credit: TMDB's own sentence, in sentence case and shown whole (two lines, not cut off).
                PremiumSettings.Item("Movie and Show Data", "TMDB", R.drawable.ic_movie, null,
                    "This product uses the TMDB API but is not endorsed or certified by TMDB.", wrapCaption = true) {},
                // JustWatch's terms: credit for the where-to-watch data TMDB passes on (STANDARDS §4).
                PremiumSettings.Item("Where to Watch", "JustWatch", R.drawable.ic_movie, null,
                    "Streaming Availability From JustWatch", wrapCaption = true) {},
                // Rotten Tomatoes and IMDb scores (Ratings.kt) come from OMDb.
                PremiumSettings.Item("Scores", "OMDb", R.drawable.ic_movie, null,
                    "Rotten Tomatoes and IMDb Scores From OMDb", wrapCaption = true) {},
            )),
        )
    }

    // ---- screen fit (overscan) ----

    private var screenFitOverlay: View? = null
    private var screenFitStart = 0

    /**
     * Calibration: pearl corner marks sit at the edges of the (scaled) window; Left/Right move them
     * in or out live until they just fit on the TV. OK saves, Back puts it back as it was.
     */
    private fun showScreenFit() {
        if (screenFitOverlay != null) return
        screenFitStart = ScreenFit.get(this)
        var value = screenFitStart
        val mark = Theme.get(this, Theme.ACCENT)
        val overlay = FrameLayout(this).apply {
            isClickable = true
            setBackgroundColor(Color.argb(150, 0, 0, 0))
        }
        // Corner marks at the edge of the safe area (the extra inset on top of the normal margins).
        val corners = listOf(Gravity.TOP or Gravity.START, Gravity.TOP or Gravity.END, Gravity.BOTTOM or Gravity.START, Gravity.BOTTOM or Gravity.END).map { g ->
            FrameLayout(this).apply {
                addView(View(context).apply { setBackgroundColor(mark) }, FrameLayout.LayoutParams(64.dp, 5.dp, g))
                addView(View(context).apply { setBackgroundColor(mark) }, FrameLayout.LayoutParams(5.dp, 64.dp, g))
            }.also { overlay.addView(it, FrameLayout.LayoutParams(64.dp, 64.dp, g)) }
        }
        val valueText = TextView(this).apply {
            textSize = 54f
            typeface = fontBold
            setTextColor(textColor)
            gravity = Gravity.CENTER
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(44.dp, 30.dp, 44.dp, 28.dp)
            background = modalCardBackground()
            addView(TextView(context).apply { text = "Screen Fit"; textSize = 24f; typeface = fontBold; setTextColor(textColor) })
            addView(TextView(context).apply {
                text = "Press ◀ ▶ until the four corner marks just fit on your screen."
                textSize = 15f
                typeface = font
                setTextColor(dimColor)
                gravity = Gravity.CENTER
                setPadding(0, 8.dp, 0, 10.dp)
            })
            addView(valueText)
            addView(TextView(context).apply {
                text = "Press Select to save, or Back to cancel"
                textSize = 13f
                typeface = font
                setTextColor(dimColor)
                setPadding(0, 10.dp, 0, 0)
            })
        }
        overlay.addView(card, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        fun show() {
            valueText.text = if (value == 0) "Normal" else "$value%"
            val (h, v) = ScreenFit.insets(this, value).let { it[0] to it[1] }
            for (c in corners) (c.layoutParams as FrameLayout.LayoutParams).let { it.setMargins(h, v, h, v); c.layoutParams = it }
            applyScreenFit(value)
        }
        // One focusable catcher takes the keys while the calibration is up.
        val keys = View(this).apply {
            isFocusable = true
            setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN -> { value = (value + 1).coerceAtMost(ScreenFit.MAX); show(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP -> { value = (value - 1).coerceAtLeast(0); show(); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { ScreenFit.set(this@MainActivity, value); closeScreenFit(save = true); true }
                    else -> false
                }
            }
        }
        overlay.addView(keys, 1, 1)
        show()
        (window.decorView as FrameLayout).addView(overlay, MATCH_PARENT, MATCH_PARENT)
        screenFitOverlay = overlay
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(220).start()
        keys.post { keys.requestFocus() }
    }

    private fun closeScreenFit(save: Boolean) {
        val v = screenFitOverlay ?: return
        screenFitOverlay = null
        if (!save) applyScreenFit(screenFitStart)
        fadeAway(v, 180, 0)
        refreshSettings()
        settingsPage?.focusInitial() ?: focusFirstIn(pageHost)
    }

    private fun closeMenu() {
        val v = menuOverlay ?: return
        menuOverlay = null
        val card = (v as? ViewGroup)?.getChildAt(0)
        if (card != null) {
            card.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).translationY(8.dp.toFloat())
                .setDuration(Ui.SHEET_OUT).setInterpolator(Ui.EXIT).withLayer().start()
            fadeAway(v, Ui.SHEET_OUT + 40, 30)
        } else {
            fadeAway(v, 160, 0)
        }
        setHomeFocusable(true)
        val back = menuReturn
        menuReturn = null
        if (back == null || !back.isAttachedToWindow || !back.requestFocus()) focusFirstIn(pageHost)
    }

    // ---- intro ----

    /** Plays the logo intro on every cold start (power-on or after a force close), or on demand. */
    private fun maybeShowSplash(force: Boolean = false) {
        if (!force && splashPlayed) return
        splashPlayed = true
        if (splash != null) return

        // Full-screen artwork: fade in from black, slow zoom, then fade out to the home screen.
        val art = ImageView(this).apply {
            // RGB_565: the JPEG has no alpha, and this halves the intro's biggest bitmap.
            val opts = android.graphics.BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 }
            android.graphics.BitmapFactory.decodeResource(resources, R.drawable.splash_bg, opts)
                ?.let { setImageBitmap(it) } ?: setImageResource(R.drawable.splash_bg)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        val sparkles = SparkleView(this)
        val glowPulse = View(this).apply {
            background = rainbowGlow()
            alpha = 0f
            scaleX = 0.6f
            scaleY = 0.6f
            translationY = -72.dp.toFloat() // on the logo's cloud (splash_bg: the mark sits ~144 px above centre)
        }
        val sweep = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.TRANSPARENT, Color.argb(80, 255, 255, 255), Color.TRANSPARENT))
            rotation = 18f
            alpha = 0f
            translationX = -460.dp.toFloat()
        }
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(art, MATCH_PARENT, MATCH_PARENT)
            addView(glowPulse, FrameLayout.LayoutParams(720.dp, 720.dp, Gravity.CENTER))
            addView(sparkles, MATCH_PARENT, MATCH_PARENT)
            addView(sweep, FrameLayout.LayoutParams(240.dp, (resources.displayMetrics.heightPixels * 1.7f).toInt(), Gravity.CENTER_VERTICAL))
        }
        splashSparkles = sparkles
        splashGlow = glowPulse
        splashSweep = sweep
        // Live loading status + progress bar along the bottom of the intro.
        val status = TextView(this).apply {
            textSize = 15f
            typeface = font
            gravity = Gravity.CENTER
            setTextColor(Color.argb(225, 255, 255, 255))
            setShadowLayer(10f, 0f, 2f, Color.argb(220, 0, 0, 0))
        }
        val fill = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                Rainbow.COLORS).apply {
                cornerRadius = 3.dp.toFloat()
            }
            pivotX = 0f
            scaleX = 0f
        }
        val track = FrameLayout(this).apply {
            background = roundedRect(Color.argb(70, 255, 255, 255), 3, null)
            addView(fill, MATCH_PARENT, MATCH_PARENT)
        }
        val loader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            alpha = 0f
            addView(status, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = 12.dp })
            addView(track, LinearLayout.LayoutParams(360.dp, 5.dp))
        }
        overlay.addView(loader, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = 64.dp
        })
        val version = introVersion()
        overlay.addView(version, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = 40.dp
            bottomMargin = 40.dp
        })
        // What's Inside on the dark left of the artwork (the logo holds the middle).
        val inside = WhatsInside.intro(this)
        overlay.addView(inside, FrameLayout.LayoutParams(214.dp, WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START).apply {
            marginStart = 30.dp // clear of the logo's cloud even at the end of the intro's slow zoom
        })
        // The intro's text panels are static once in: each draws from its own layer (their soft text shadows are
        // blurred once, not on every frame of the full-screen zoom).
        for (v in listOf<View>(inside, version, loader)) v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        bootBar = fill
        bootStatus = status
        (window.decorView as FrameLayout).addView(overlay, MATCH_PARENT, MATCH_PARENT)
        splash = overlay
        splashShownAt = android.os.SystemClock.uptimeMillis()
        shell?.introDone = false // Home's rows don't warm up under the intro (CinemaRowsPage)
        // The intro covers the whole screen: Home under it still builds and lays out, but isn't drawn. Drawn
        // under the opaque intro it doubled the GPU's work every frame (~35 fps instead of 60 on this stick).
        findViewById<View>(android.R.id.content).visibility = View.INVISIBLE

        val start: () -> Unit = {
            sparkles.start()
            // ~1.3 s in: TVs/soundbars often take about a second to wake HDMI audio after silence.
            if (Theme.get(this, Theme.INTRO_SOUND) == 1) handler.postDelayed({ sounds.swell() }, 1300)
            bootShown = 0
            bootStart = SystemClock.uptimeMillis()
            for (k in listOf("accounts", "core", "picks")) bootStep(k) // paced by the ticker, nothing to wait for
            updateBootUi()
            handler.removeCallbacks(bootTicker)
            handler.postDelayed(bootTicker, 900)
            loader.animate().alpha(1f).setStartDelay(700).setDuration(500).start()
            version.animate().alpha(1f).setStartDelay(900).setDuration(600).start()
            WhatsInside.revealIntro(inside, 1200)
            // The loading steps end once Home's first row is in (7 s at most), the welcome holds 4 s, then a 1.3 s fade.
            // On a cold start Home's first build (two ~150 ms frames) would stutter the art's fade-in: the art
            // waits for it (ART_WAIT_MS at most); a replay from Settings has Home built already.
            val reveal = Runnable {
                artReveal = null
                art.animate().alpha(1f).setDuration(1600).start()
                art.animate().scaleX(1.10f).scaleY(1.10f).setDuration(13_000).setInterpolator(DecelerateInterpolator()).start()
            }
            if (force || "home" in bootDone) reveal.run()
            else { artReveal = reveal; handler.postDelayed(reveal, ART_WAIT_MS) }
            handler.postDelayed({ finishSplash?.invoke() }, 16_000) // safety net
        }
        // Cold start: bring paired AirPods in first so the intro is heard in them (the screen stays black meanwhile).
        if (force) start() else connectAirPodsThen(start)
        finishSplash = {
            finishSplash = null
            // Home shows again under the intro (setup, if it follows, hides it once more for itself). It is drawn
            // for the first time under the still-opaque intro, a beat before the fade, so that heavy first frame
            // never lands in the fade; pages built ahead wait until well after it.
            if (setup == null) findViewById<View>(android.R.id.content).visibility = View.VISIBLE
            shell?.holdWorkAhead(HOME_DRAW_LEAD_MS + 1300 + 2500)
            shell?.introDone = true
            overlay.animate().alpha(0f).setStartDelay(HOME_DRAW_LEAD_MS).setDuration(1300).withEndAction {
                (overlay.parent as? ViewGroup)?.removeView(overlay)
                art.setImageDrawable(null) // the 4 MB intro art goes now, even if a callback still holds the view
                Hwui.trimWhenStill { resumed && splash == null && saver == null && !DreamPlayerActivity.visible && !DreamSaver.dreaming }
                splash = null
                maybeSetupPrompt()
                bootBar = null
                bootStatus = null
                splashSparkles?.stop()
                splashSparkles = null
                splashGlow = null
                splashSweep = null
                onIntroDone()
            }.start()
        }
    }

    /** "Dream OS \u00b7 Version 4.1 (DEV)" (BuildConfig, never written in code): the number in the Dream rainbow, the channel in its status color. */
    private fun introVersion() = TextView(this).apply {
        val name = BuildConfig.VERSION_NAME
        val line = "Dream OS  \u00b7  Version $name"
        text = android.text.SpannableStringBuilder(Rainbow.still(line, line.length - name.length, line.length, 36.dp.toFloat())).apply {
            setSpan(StyleSpan(Typeface.BOLD), length - name.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            // The build channel: (PUBLIC) in green for builds published by tools/release.sh, (DEV) in red for every other.
            append(" ")
            val at = length
            append(AboutPage.channelTag())
            setSpan(ForegroundColorSpan(AboutPage.channelColor()), at, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), at, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        textSize = 13f
        typeface = font
        setTextColor(Color.argb(225, 255, 255, 255))
        setShadowLayer(10f, 0f, 2f, Color.argb(220, 0, 0, 0))
        // Smoked-glass chip with a hairline, neutral like the What's Inside panel (the number carries the color).
        background = roundedRect(Color.argb(150, 18, 18, 20), 14, null).apply { setStroke(1.dp, Color.argb(34, 255, 255, 255)) }
        setPadding(14.dp, 6.dp, 14.dp, 6.dp)
        alpha = 0f
    }

    /**
     * The intro's welcome pulse: a halo in the logo's rainbow (hues round the circle, a soft white heart,
     * fading to nothing at the rim). Painted once into a small bitmap on the CPU and drawn scaled with
     * filtering: a blurry glow loses nothing at 256 px, and the GPU samples one texture per pixel instead
     * of evaluating two gradients across a 1440 px square while the sweep runs.
     */
    private fun rainbowGlow(): android.graphics.drawable.Drawable {
        val n = 256
        val c = n / 2f
        val bmp = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val hues = android.graphics.SweepGradient(c, c, Rainbow.COLORS + Rainbow.COLORS[0], null)
        val falloff = android.graphics.RadialGradient(c, c, c, intArrayOf(Color.argb(60, 0, 0, 0), Color.argb(150, 0, 0, 0),
            Color.argb(70, 0, 0, 0), Color.TRANSPARENT), floatArrayOf(0f, 0.32f, 0.62f, 1f), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawCircle(c, c, c, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.ComposeShader(hues, falloff, android.graphics.PorterDuff.Mode.DST_IN)
        })
        canvas.drawCircle(c, c, c * 0.5f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.RadialGradient(c, c, c * 0.5f, Color.argb(120, 255, 255, 255), Color.TRANSPARENT,
                android.graphics.Shader.TileMode.CLAMP)
        })
        return android.graphics.drawable.BitmapDrawable(resources, bmp).apply { isFilterBitmap = true }
    }

    private var pendingIntro: (() -> Unit)? = null

    /** Connects paired AirPods (if any) and runs [then] once they're the audio output, or after a timeout. */
    private fun connectAirPodsThen(then: () -> Unit) {
        if (airPods.pairedDevice() == null || !AirPodsLink.autoConnect(this)) return then()
        airPods.connectedName { name ->
            if (name != null || !airPods.connect()) return@connectedName then()
            pendingIntro = then
            // In a closed case or out of range: don't hold the intro up for long.
            handler.postDelayed({ startPendingIntro() }, AIRPODS_CONNECT_TIMEOUT_MS)
        }
    }

    private fun startPendingIntro() {
        val go = pendingIntro ?: return
        pendingIntro = null
        go()
    }

    // ---- drawing helpers ----

    private fun roundedRect(fill: Int, radiusDp: Int, stroke: Int?) = GradientDrawable().apply {
        cornerRadius = radiusDp.dp.toFloat()
        setColor(fill)
        if (stroke != null) setStroke(3.dp, stroke)
    }

    private fun withAlpha(color: Int, a: Float) =
        Color.argb((a * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        /** How long the boot intro stays up in all, loading included, before its fade. */
        private const val SPLASH_TOTAL_MS = 8_000L
        /** Home's first draw happens under the opaque intro this long before the intro starts fading. */
        private const val HOME_DRAW_LEAD_MS = 260L
        private const val WARM_IDLE_MS = 2_000L
        private const val WARM_MAX_WAIT_MS = 20_000L
        /** Cold start: the intro art fades in this long after Home's first row, or after [ART_WAIT_MS] at most. */
        private const val ART_AFTER_HOME_MS = 300L
        private const val ART_WAIT_MS = 1_200L
        /** One boot status step (5 steps + the welcome fill the intro). */
        private const val BOOT_STEP_MS = 1_150L
        const val EXTRA_SPLASH = "splash"
        const val EXTRA_AMAZON_SETTINGS = "amazon_settings"
        /** A Title as JSON (PlayFlow.openDetails): opens its details page. */
        const val EXTRA_OPEN_TITLE = "open_title"
        /** With [EXTRA_OPEN_TITLE] from the player's X-Ray: Back from that page returns to the paused player. */
        const val EXTRA_BACK_TO_PLAYER = "back_to_player"
        /** Opens Dream Search with this query searched (Alexa heard a search). */
        const val EXTRA_SEARCH_QUERY = "search_query"
        /** Home was pressed inside Dream OS (HomeRedirectService): land at the top of the Home page. */
        const val EXTRA_HOME_TOP = "home_top"
        /** Brought forward by the Alexa hand-off (AlexaSearch drives what shows). */
        const val EXTRA_ALEXA = "alexa"
        /** Developer preview: shows the screensaver now (see onNewIntent). */
        const val EXTRA_SAVER = "saver"
        private const val FIRE_SETTINGS = "com.amazon.tv.settings.v2"
        /** Instant Play: how long the remote rests on a details page (after a key) before its stream is looked for. */
        private const val PRE_RESOLVE_REST_MS = 3_000L
        /** ...and how soon after the page opens. */
        private const val PRE_RESOLVE_OPEN_MS = 300L
        /** Browsing: focus resting this long on a card or the hero looks for that title's stream. */
        private const val SPECULATE_NATIVE_REST_MS = 600L

        /** Title pages kept whole on the Back stack (each holds its art; deeper ones reopen at the top). */
        private const val LIVE_PAGES = 3
        private const val AIRPODS_CONNECT_TIMEOUT_MS = 6000L
        // Menu icons: the first keyword found in the lowercased label wins.
        private val MENU_ICONS = listOf(
            "season" to R.drawable.ic_tv,     // the episode chooser; first so episode titles can't pick odd icons
            "episode" to R.drawable.ic_play,
            "ad blocking" to R.drawable.ic_shield,
            "source settings" to R.drawable.ic_movie,
            "airplay" to R.drawable.ic_tv,
            "phone" to R.drawable.ic_phone,
            "weather" to R.drawable.ic_bolt,
            "sleep" to R.drawable.ic_moon,
            "screensaver" to R.drawable.ic_play,
            "network" to R.drawable.ic_wifi,
            "applications" to R.drawable.ic_apps,
            "bluetooth" to R.drawable.ic_headphones,
            "my fire tv" to R.drawable.ic_tv,
            "accessibility" to R.drawable.ic_info,
            "all fire tv" to R.drawable.ic_settings,
            "tmdb" to R.drawable.ic_movie,
            "hidden" to R.drawable.ic_apps,
            "edit profile" to R.drawable.ic_settings,
            "profile" to R.drawable.ic_home,
            "setup" to R.drawable.ic_flame,
            "intro" to R.drawable.ic_play,
            "add to" to R.drawable.ic_bookmark,
            "remove from" to R.drawable.ic_bookmark,
            "move" to R.drawable.ic_apps,
            "hide" to R.drawable.ic_info,
            "force stop" to R.drawable.ic_bolt,
            "uninstall" to R.drawable.ic_info,
            "app info" to R.drawable.ic_info,
        )
        private const val AUDIO_SETTLE_MS = 1200L

        // Lives as long as the process, so the intro plays once per cold start.
        private var splashPlayed = false
    }
}
