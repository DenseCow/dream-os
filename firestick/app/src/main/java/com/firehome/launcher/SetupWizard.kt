package com.firehome.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * First-run setup ("Run setup again" in Settings): one idea per screen, in Dream Cinema's look
 * (PremiumKit pills and glass, Ui tokens, the theme colors).
 *
 * Steps: Welcome, Before You Start, Your Time Zone, Connect Your Accounts, Scrapers, Playback
 * Preferences, the final check, Unlock Features, Profile, Who's Watching, Your Look & Screen Fit, Home
 * Button, Content (TMDB, weather, Your Apps), Dream OS + Apple, AirPods, iPhone, Voice Search, Extras, Done.
 * Every choice is saved the moment it's made, so any step can simply be continued past; everything
 * here also lives in Settings.
 *
 * Layout: the backdrop is full-bleed; the content is inset by Screen fit. Most steps are split:
 * the title and explanation on the left, the controls on the right with the buttons under them, so
 * Down always ends on Continue. Only the current step's views exist. Steps cross-fade and slide with
 * a short stagger (transform + alpha only; every animation ends, nothing loops).
 *
 * The host forwards BACK to [handleBack] (false = close the wizard), resumes and AirPods changes to
 * [refreshAirPods], the Alexa hand-off to [alexaListening] / [alexaHeard] / [alexaCancelled], and
 * phone-remote typing to [phoneText].
 */
class SetupWizard(
    private val ctx: Context,
    private val pins: PinPrompt,
    private val canCancel: Boolean,
    private val host: Host,
) {
    interface Host {
        fun airPodsConnected(): Boolean
        /** Name of the paired AirPods, or null when none are paired. */
        fun pairedAirPods(): String?
        fun connectAirPods(): Boolean
        fun openBluetoothSettings()
        fun remoteUrl(): String?
        fun qrBitmap(url: String): Bitmap
        /** Launchable apps (the home screen's list). */
        fun apps(): List<AppEntry>
        fun openAccessibilitySettings()
        /** Setup is finished (flag saved): close it and rebuild home. */
        fun finish()
    }

    /**
     * A page built on a [SetupPanel] for a required step (the Accounts and Scrapers pages, shared with Settings).
     * The wizard draws the title, subtitle and buttons; [content] goes in the right-hand column.
     */
    interface SlotPage {
        val title: String
        val subtitle: String?
        /** Right-hand column content (rows, progress, codes). Up to ~460 x 330 dp. */
        val content: View
        /** First focus on the page; null = the wizard's Continue button. */
        val focus: View?
        val primaryLabel: String get() = "Continue"
        /** Optional quiet second button ("Not Now"); it moves on like Continue. */
        val secondaryLabel: String? get() = null
        /** true = handled inside the page (e.g. it closed its own sub-view). */
        fun handleBack(): Boolean = false
        /** The page is leaving the screen (stop timers, drop bitmaps). */
        fun release() {}
    }

    private class Built(val view: View, val focus: View, val anims: List<View>)

    private val k = PremiumKit(ctx)
    /** The rainbow border of each round chip ([tapChip]), set by whoever gives it the Dream focus look. */
    private val chipGlow = java.util.WeakHashMap<View, CinemaGlow>()
    private val Int.dp get() = Ui.dp(ctx, this)
    private val profileId = Profiles.current(ctx).id
    private var name: String
    private var emoji: String
    private var color: Int

    private var steps: List<Int> = emptyList()
    private var cur = WELCOME
    private var page: Built? = null
    private var gen = 0
    private var slot: SlotPage? = null
    private var nameField: EditText? = null
    private var keyField: EditText? = null
    private var cancelKeyEdit: (() -> Unit)? = null
    /** Live status for the step on screen: re-checked when Dream OS resumes, and when AirPods change. Updates in place. */
    private var onResumeCheck: (() -> Unit)? = null
    private var onAirPodsCheck: (() -> Unit)? = null
    private var onLeave: (() -> Unit)? = null
    private var voice: ((Int, String?) -> Unit)? = null
    private var voiceState = V_IDLE
    private var voiceTested = false

    private val stage = FrameLayout(ctx)
    private val dots = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    private var marks: FrameLayout? = null

    /**
     * Root view to pass to [ModalHost.show]. Flat: the host's open / close fade is applied per child
     * instead of drawing the whole full-screen wizard into an offscreen buffer every frame.
     */
    val view: FrameLayout = Root()

    /**
     * The wizard's root. The D-pad only ever moves within the step on screen: never into a page
     * that's fading out, never onto the home screen under the wizard, and at an edge focus simply
     * stays. Up from the buttons always reaches a slot page's rows.
     */
    private inner class Root : FrameLayout(ctx) {
        override fun hasOverlappingRendering() = false

        // Home's shell (and with it the shared glow clock) is paused under setup: the clock keeps running for
        // the wizard's own glows and rainbow buttons while it shows (only views on screen are re-tinted, and it
        // drops to a few steps a second when the remote rests).
        override fun onAttachedToWindow() { super.onAttachedToWindow(); CinemaStyle.keep(true) }
        override fun onDetachedFromWindow() { CinemaStyle.keep(false); super.onDetachedFromWindow() }

        override fun focusSearch(focused: View?, direction: Int): View? {
            val pv = page?.view as? ViewGroup
            if (focused == null || pv == null || !inside(focused, pv)) return super.focusSearch(focused, direction)
            FocusFinder.getInstance().findNextFocus(pv, focused, direction)?.let { return it }
            if (direction == View.FOCUS_UP && isButton(focused)) slot?.content?.let { c -> firstFocusable(c)?.let { return it } }
            // Left / Right from a row in a scrolling list (nothing beside it): straight to Continue, so a long
            // list never has to be scrolled to its end to reach the step's buttons.
            if ((direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) && inScroll(focused, pv))
                pv.findViewWithTag<View>(MAIN)?.takeIf { it.isShown && it.isFocusable }?.let { return it }
            return focused
        }
    }

    private fun inScroll(v: View, top: View): Boolean {
        var p = v.parent as? View
        while (p != null && p !== top) { if (p is android.widget.ScrollView) return true; p = p.parent as? View }
        return false
    }

    private fun inside(v: View, group: View): Boolean {
        var p: View? = v
        while (p != null) { if (p === group) return true; p = p.parent as? View }
        return false
    }

    private fun isButton(v: View) = v.tag == MAIN || (v.parent as? View)?.findViewWithTag<View>(MAIN) != null

    private fun firstFocusable(v: View): View? {
        if (v.visibility != View.VISIBLE) return null
        if (v.isFocusable) return v
        if (v is ViewGroup && v.descendantFocusability != ViewGroup.FOCUS_BLOCK_DESCENDANTS)
            for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Where focus belongs on the step on screen: the slot's own pick, else the step's start, else Continue. */
    private fun pageTarget(): View? {
        val b = page ?: return null
        // A slot page's focus; null means the wizard's Continue button ([SlotPage.focus]).
        slot?.let { s -> s.focus?.takeIf { it.isAttachedToWindow && it.isShown }?.let { return it } }
        b.focus.takeIf { it.isAttachedToWindow && it.isShown && it.isFocusable }?.let { return it }
        return b.view.findViewWithTag<View>(MAIN) ?: firstFocusable(b.view)
    }

    /**
     * From the host's dispatchKeyEvent: a D-pad press while nothing has focus (the focused view was
     * removed or hidden) puts focus back on the step instead of letting Android pick any view.
     * True = consumed.
     */
    fun ensureFocus(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN || e.keyCode !in NAV_KEYS) return false
        if (!view.isAttachedToWindow || view.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS) return false
        if (view.rootView.findFocus() != null) return false
        val t = pageTarget() ?: return false
        Log.w(TAG, "setup: no focus on a key press, back to the step")
        return t.requestFocus()
    }
    /** The first step's main control; give it to [ModalHost.show] as the focus. */
    val initialFocus: View

    init {
        val p = Profiles.current(ctx)
        // A fresh install's implicit profile is only a placeholder "Me": start blank so it gets a real name.
        name = if (Profiles.isSetUp(ctx) || p.name != "Me") p.name else ""
        emoji = p.emoji
        color = p.color
        view.isClickable = true // swallow touches meant for the app underneath
        view.background = backdrop()
        view.addView(stage, MATCH_PARENT, MATCH_PARENT)
        view.addView(dots, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        applyFit(ScreenFit.get(ctx))
        steps = stepList()
        buildDots()
        val b = build(cur)
        page = b
        stage.addView(b.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // The host fades the whole wizard in; the first page's parts follow just behind it.
        enter(b, 24.dp.toFloat(), delay = 160)
        initialFocus = b.focus
        updateProgress()
        // Focused pills, chips and rows lift past their rows and groups on every step: only the screen edge clips.
        Ui.unclip(view)
    }

    // ---- public hooks ----

    /** Goes back a step. Returns false when the wizard should close (first step, and cancelling is allowed). */
    fun handleBack(): Boolean {
        cancelKeyEdit?.let { it(); return true }
        if (slot?.handleBack() == true) return true
        val i = steps.indexOf(cur)
        if (i <= 0) return !canCancel
        go(steps[i - 1], forward = false)
        return true
    }

    /** AirPods pairing / connection may have changed (called often: only the AirPods step listens, in place). */
    fun refreshAirPods() {
        try { onAirPodsCheck?.invoke() } catch (e: Exception) { Log.w(TAG, "setup AirPods refresh: $e") }
    }

    /** Dream OS is back in front (e.g. from Bluetooth or Accessibility settings): re-check the step's status once. */
    fun onResume() {
        try { onResumeCheck?.invoke(); onAirPodsCheck?.invoke() } catch (e: Exception) { Log.w(TAG, "setup resume: $e") }
    }

    /** Apps were added or removed: steps that depend on an app (Apple TV) may come or go. */
    fun onPackagesChanged() {
        // The step on screen stays in the list so its dot stays lit.
        val s = stepList().let { if (cur in it) it else (it + cur).sorted() }
        if (s == steps) return
        steps = s
        buildDots()
        updateProgress()
    }

    /** Alexa started listening. Always handled while setup is open (Dream Search stays put underneath). */
    fun alexaListening(): Boolean {
        voiceState = V_LISTENING
        voice?.invoke(V_LISTENING, null)
        return true
    }

    /** Alexa heard a search ([q] may be empty when it couldn't be read). */
    fun alexaHeard(q: String): Boolean {
        if (cur == VOICE) voiceTested = true
        voiceState = V_HEARD
        voice?.invoke(V_HEARD, q)
        return true
    }

    /** Alexa finished without a search. */
    fun alexaCancelled(): Boolean {
        if (voiceState == V_LISTENING) {
            voiceState = V_MISSED
            voice?.invoke(V_MISSED, null)
        }
        return true
    }

    /** Text typed on the phone remote: fills the step's text field, if it has one. */
    fun phoneText(text: String): Boolean {
        val f = keyField ?: nameField ?: return false
        f.setText(text)
        f.setSelection(f.text.length)
        f.requestFocus()
        return true
    }

    // ---- steps & navigation ----

    /**
     * Welcome, then what Dream OS needs to play (time zone, accounts, scrapers, a real check), then the
     * personal and optional steps. The required ones hold Continue until they're met ([blocker]).
     */
    /**
     * Owner's order (2026-10-28, same as the iPhone): what you'll need, the time zone, then every account step back to
     * back (TMDB key, debrid, scrapers, the check that they work together), then the steps that have nothing to do
     * with accounts.
     */
    private fun stepList(): List<Int> = listOfNotNull(
        WELCOME, NEED, INTRO, ZONE, TMDB, ACCOUNTS, SCRAPERS, CHECK, PLAYBACK, UNLOCK,
        PROFILE, WHO, LOOK,
        // The unlock turns the Home button on; its own step stays only for when it's still off.
        if (homeActive() && cur != HOME) null else HOME,
        CONTENT, APPLE, AIRPODS, IPHONE, VOICE, MORE, DONE,
    )

    /** Why a required step can't be left yet (shown on the page), or null. */
    private fun blocker(step: Int): String? = when (step) {
        ACCOUNTS -> SetupGate.accountsBlocker(ctx, accountPages?.isChecking() == true)
        SCRAPERS -> if (SetupGate.scrapersReady(ctx)) null else "Add a scraper repository and turn on at least one provider to continue."
        CHECK -> if (SetupGate.checkPassed(ctx)) null else "Run the check to continue."
        TMDB -> if (Tmdb.hasKey(ctx)) null else "Add your TMDB key to continue."
        else -> null
    }

    private fun next(from: Int) {
        if (from != cur) return // a click from a page that is already fading away
        blocker(from)?.let { why -> showBlocker(why); return }
        if (from == ZONE) SetupGate.setZoneConfirmed(ctx)
        if (from == INTRO) SetupIntro.accept(ctx)
        if (from == PROFILE && !commitProfile()) return
        if (from == DONE) {
            Profiles.markSetUp(ctx)
            celebrate { host.finish() }
            return
        }
        SetupGate.saveStep(ctx, from.toString()) // an interrupted setup picks up after the last finished step
        val s = stepList()
        if (s != steps) { steps = s; buildDots() }
        val i = steps.indexOf(from)
        val to = if (i >= 0 && i + 1 < steps.size) steps[i + 1] else steps.firstOrNull { it > from } ?: DONE
        go(to, forward = true)
    }

    private fun go(to: Int, forward: Boolean) {
        if (to == cur && page != null) return // never re-enter the step on screen
        Log.w(TAG, "setup: step $cur -> $to")
        hideKeyboard()
        nameField?.let { name = it.text.toString().trim() } // keep a typed name when going back
        leave()
        val old = page
        cur = to
        gen++
        val b = build(to)
        page = b
        val dx = 28.dp.toFloat() * (if (forward) 1 else -1)
        if (old != null) {
            (old.view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            // Pages from earlier quick presses that are still fading: gone at once, so only one ever crossfades.
            for (i in stage.childCount - 1 downTo 0) {
                val v = stage.getChildAt(i)
                if (v !== old.view) { v.animate().cancel(); stage.removeView(v); releaseQr(v) }
            }
        }
        // The new step goes in hidden (every part at alpha 0), so the frame that lays it out and records it for
        // the first time is a still frame; the motion starts on the frame after, never with that work inside it.
        for (c in b.anims) { c.animate().cancel(); c.alpha = 0f; c.translationX = dx }
        stage.addView(b.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val g = gen
        stage.postOnAnimation {
            stage.postOnAnimation {
                if (page !== b || g != gen) return@postOnAnimation
                // No offscreen layers: a full-screen layer for the old page and one per part of the new page were
                // allocated and filled mid-fade (the long frames). Each draws its few views with the fade's alpha.
                if (old != null && old.view.parent === stage) {
                    old.view.forceHasOverlappingRendering(false)
                    old.view.animate().translationX(-dx * 0.6f).alpha(0f).setStartDelay(0).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT)
                        .withEndAction { stage.removeView(old.view); releaseQr(old.view) }.start()
                }
                enter(b, dx, delay = 110)
            }
        }
        // Resolved when it runs: a slot page may only have its rows by then.
        b.view.post { if (page === b) (pageTarget() ?: b.focus).requestFocus() }
        updateProgress()
    }

    /** Clears the per-step state of the step being left. */
    private fun leave() {
        onLeave?.invoke()
        onLeave = null
        onResumeCheck = null
        onAirPodsCheck = null
        voice = null
        blockNote = null
        nameField = null
        keyField = null
        cancelKeyEdit = null
        slot?.let { try { it.release() } catch (e: Exception) {} }
        slot = null
        marks?.let { view.removeView(it) }
        marks = null
    }

    /** Slides and fades a page's parts in one after another (Ui page motion; every part lands by ~0.6 s). */
    private fun enter(b: Built, dx: Float, delay: Long) {
        for ((i, c) in b.anims.withIndex()) {
            c.animate().cancel()
            c.alpha = 0f
            c.translationX = dx
            c.forceHasOverlappingRendering(false) // no layer per part (see [go])
            c.animate().alpha(1f).translationX(0f).setStartDelay(delay + minOf(i, 8) * Ui.STAGGER)
                .setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        }
    }

    /** New content arriving in a step that's already on screen: a short, soft rise (no swap, no flash). */
    private fun reveal(views: List<View>) = Ui.rise(views, fromDp = 6, step = Ui.STAGGER, duration = Ui.PAGE)

    // ---- in-place updates (no relayout or redraw when nothing changed) ----

    private fun setTextIfNew(t: TextView?, s: CharSequence) { if (t != null && t.text.toString() != s.toString()) t.text = s }
    private fun setColorIfNew(t: TextView?, c: Int) { if (t != null && t.currentTextColor != c) t.setTextColor(c) }
    private fun setShown(v: View?, shown: Boolean) {
        val want = if (shown) View.VISIBLE else View.GONE
        if (v != null && v.visibility != want) v.visibility = want
    }

    private fun build(s: Int): Built = try {
        when (s) {
            WELCOME -> welcome()
            INTRO -> introStep()
            NEED -> needStep()
            TMDB -> tmdbStep()
            PLAYBACK -> playbackStep()
            WHO -> whoStep()
            ZONE -> zoneStep()
            ACCOUNTS -> accountsStep()
            SCRAPERS -> scrapersStep()
            CHECK -> checkStep()
            UNLOCK -> unlockStep()
            PROFILE -> profile()
            LOOK -> look()
            HOME -> homeStep()
            CONTENT -> content()
            APPLE -> appleStep()
            AIRPODS -> airPodsStep()
            IPHONE -> iPhoneStep()
            VOICE -> voiceStep()
            MORE -> moreStep()
            else -> doneStep()
        }
    } catch (e: Exception) {
        // Never strand the viewer: a step that can't be built becomes a plain page that moves on.
        Log.w(TAG, "setup step $s failed", e)
        val p = Split(glyph(R.drawable.ic_info), "Let's Keep Going", "This part can be set up later in Settings.")
        p.built(p.buttons("Continue") { next(s) })
    }

    private fun buildDots() {
        dots.removeAllViews()
        repeat(steps.size) {
            dots.addView(View(ctx).apply { background = oval(k.text); alpha = 0.2f },
                LinearLayout.LayoutParams(6.dp, 6.dp).apply { marginStart = 5.dp; marginEnd = 5.dp })
        }
    }

    private fun updateProgress() {
        val at = steps.indexOf(cur)
        for (i in 0 until dots.childCount) {
            val d = dots.getChildAt(i)
            val now = i == at
            d.animate().alpha(if (now) 1f else if (i < at) 0.5f else 0.2f)
                .scaleX(if (now) 1.35f else 1f).scaleY(if (now) 1.35f else 1f).setDuration(300).setInterpolator(Ui.SETTLE).start()
        }
    }

    /** Content inset: Screen fit plus the design margins; the backdrop stays full-bleed. */
    private fun applyFit(percent: Int) {
        val ins = ScreenFit.insets(ctx, percent)
        stage.setPadding(ins[0] + SIDE.dp, ins[1] + TOP.dp, ins[0] + SIDE.dp, ins[1] + BOTTOM.dp)
        (dots.layoutParams as? FrameLayout.LayoutParams)?.let { it.bottomMargin = ins[1] + 18.dp; dots.layoutParams = it }
        marks?.let { placeMarks(it, percent) }
    }

    // ---- 1. welcome ----

    private fun welcome(): Built {
        val col = column()
        val logo = FrameLayout(ctx).apply {
            addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    gradientType = GradientDrawable.RADIAL_GRADIENT
                    gradientRadius = 70.dp.toFloat()
                    colors = intArrayOf(Color.argb(30, 255, 255, 255), Color.argb(8, 255, 255, 255), Color.TRANSPARENT) // neutral: the logo brings the color
                }
            }, FrameLayout.LayoutParams(150.dp, 104.dp, Gravity.CENTER))
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.dream_logo) // same cached drawable as the menu logo
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(WRAP_CONTENT, 84.dp, Gravity.CENTER))
        }
        // What Dream OS is (the logo and two lines), what it does (DreamWelcome.kt's feature grid, full width), then the buttons.
        val intro = vBox()
        val title = titleText("Welcome to Dream OS", center = false)
        val sub = subText("Dream OS turns your Fire TV into your own streaming service: every movie and show in one beautiful place, ready to play in seconds. Setup takes about 15 minutes, and every step shows you how.", center = false)
        intro.addView(title, lp(top = 0))
        intro.addView(sub, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(logo, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(intro, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = 20.dp })
        }
        col.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val features = WelcomeFeatures.grid(ctx, k)
        col.addView(features, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 18.dp })
        val main = pill("Get Started", primary = true) { next(WELCOME) }
        // Moving from another Dream OS stick: BackupTransfer.kt brings everything over, then Dream OS restarts.
        lateinit var restore: View
        restore = pill("Restore From Backup", primary = false) {
            val a = ctx as? android.app.Activity ?: return@pill
            Log.w(TAG, "Setup: Restore From Backup opened from Welcome")
            BackupPage.open(a, restore = true) { if (cur == WELCOME && restore.isAttachedToWindow) restore.requestFocus() }
        }
        val buttons = hRow().apply {
            addView(main, LinearLayout.LayoutParams(WRAP_CONTENT, 46.dp))
            addView(restore, LinearLayout.LayoutParams(WRAP_CONTENT, 46.dp).apply { marginStart = 14.dp })
        }
        col.addView(buttons, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 22.dp })
        Log.w(TAG, "Setup: step Welcome")
        return Built(center(col).apply { (getChildAt(0).layoutParams as FrameLayout.LayoutParams).width = MATCH_PARENT },
            main, listOf(logo, title, sub, features, buttons))
    }

    // ---- 2. profile ----

    private fun profile(): Built {
        val avatar = k.label(emoji, 44f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = oval(color)
        }
        val p = Split(avatar, "Create Your Profile", "A name and a picture. Each profile keeps its own apps, list and look.", iconDp = 88)
        // PhoneTyping.kt: scan to type the name on your phone (the text lands in the name box via phoneText).
        p.leftAdd(PhoneTyping.view(ctx, k, 96), 20)
        val emojis = hRow()
        val colors = hRow()
        // Each chip's resting disc, recolored in place when the choice changes (the focus look stays put).
        val emojiRest = ArrayList<GradientDrawable>()
        fun paint() {
            for ((i, d) in emojiRest.withIndex()) emojiBg(d, Profiles.EMOJIS[i] == emoji)
            // The chosen color: a drawn white check over the swatch (never a text "✓").
            for (i in 0 until colors.childCount) colors.getChildAt(i).foreground = if (Profiles.COLORS[i] == color) CheckInk(Color.WHITE, weight = 0.11f) else null
            avatar.text = emoji
            (avatar.background as GradientDrawable).setColor(color)
        }
        for (e in Profiles.EMOJIS) {
            val rest = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            emojiRest += rest
            emojis.addView(tapChip(e, 20f, Ui.LIFT_ICON) { emoji = e; paint() }.apply {
                // Dream focus: the pearl disc inside the rainbow border (the emoji brings its own color).
                chipGlow[this] = PearlFocus.apply(this, 17, rest = rest, haloDp = 8)
            }, LinearLayout.LayoutParams(34.dp, 34.dp).apply { marginStart = 2.dp; marginEnd = 2.dp })
        }
        for (c in Profiles.COLORS) {
            colors.addView(tapChip("", 13f, Ui.LIFT_ICON) { color = c; paint() }.apply {
                typeface = k.bold
                setTextColor(Color.WHITE)
                // A swatch shows its color: focus is the rainbow border and the lift, never a pearl face over it.
                chipGlow[this] = PearlFocus.apply(this, 13, fill = oval(c), haloDp = 8)
            }, LinearLayout.LayoutParams(26.dp, 26.dp).apply { marginStart = 7.dp; marginEnd = 7.dp })
        }
        paint()
        val field = textField("Your Name", name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, 20) {
            hideKeyboard()
            emojis.getChildAt(maxOf(0, Profiles.EMOJIS.indexOf(emoji))).requestFocus()
        }
        nameField = field
        p.add(field, 0)
        p.add(emojis, 16)
        p.add(colors, 12)
        val pin = row("Lock With a PIN", "Ask for a 4-digit PIN when this profile is chosen", icon = R.drawable.ic_shield,
            toggle = Profiles.hasPin(ctx, profileId)) { r ->
            if (Profiles.hasPin(ctx, profileId)) { Profiles.setPin(ctx, profileId, null); r.sw?.set(false) }
            else pins.setNew(profileId) { r.sw?.set(Profiles.hasPin(ctx, profileId)) }
        }
        p.add(pin.view, 16)
        val main = p.buttons("Continue", "Skip") { next(PROFILE) }
        // Start in the name box when it still needs filling in, otherwise on the chosen picture.
        return p.built(if (name.isBlank()) field else emojis.getChildAt(maxOf(0, Profiles.EMOJIS.indexOf(emoji))) ?: main)
    }

    /** Saves name / picture / color into the profile. False (and a nudge) while the name is blank. */
    private fun commitProfile(): Boolean {
        val f = nameField ?: return true
        val n = f.text.toString().trim().ifEmpty { Profiles.current(ctx).name }
        if (n.isEmpty()) {
            f.hint = "Type Your Name First"
            f.setHintTextColor(ERROR)
            shake(f)
            f.requestFocus()
            return false
        }
        name = n
        val list = Profiles.all(ctx)
        list.firstOrNull { it.id == profileId }?.let { it.name = n; it.emoji = emoji; it.color = color }
        Profiles.save(ctx, list)
        return true
    }

    // ---- 3. look & screen fit ----

    private fun look(): Built {
        val p = Split(glyph(R.drawable.ic_flame), "Your Look & Screen Fit", Theme.FUTURE_NOTE)
        p.note("Screen Fit keeps menus clear of the TV's edges. Adjust it until the four corner marks just fit.")
        // Dream Cinema is the one look (custom and downloadable themes come in a future update): shown, not picked.
        p.add(lookCard(Theme.NAME, Theme.CINEMA), 0, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        var fit = ScreenFit.get(ctx)
        val fitRow = stepper("Screen Fit", "Keeps menus clear of the TV's edges", R.drawable.ic_tv, { if (fit == 0) "Full" else "$fit%" }) { by ->
            fit = (fit + by).coerceIn(0, ScreenFit.MAX)
            ScreenFit.set(ctx, fit)
            applyFit(fit)
        }
        p.add(fitRow.view, 20)
        // Corner marks at the edge of the safe area while Screen Fit has the focus.
        val m = FrameLayout(ctx).apply { alpha = 0f; isClickable = false }
        for (g in listOf(Gravity.TOP or Gravity.START, Gravity.TOP or Gravity.END, Gravity.BOTTOM or Gravity.START, Gravity.BOTTOM or Gravity.END)) {
            m.addView(FrameLayout(ctx).apply {
                addView(View(ctx).apply { background = Ui.rounded(ctx, k.accent, Ui.R_PILL) }, FrameLayout.LayoutParams(56.dp, 4.dp, g))
                addView(View(ctx).apply { background = Ui.rounded(ctx, k.accent, Ui.R_PILL) }, FrameLayout.LayoutParams(4.dp, 56.dp, g))
            }, FrameLayout.LayoutParams(56.dp, 56.dp, g))
        }
        view.addView(m, MATCH_PARENT, MATCH_PARENT)
        marks = m
        placeMarks(m, fit)
        val baseFocus = fitRow.view.onFocusChangeListener
        fitRow.view.setOnFocusChangeListener { v, has ->
            baseFocus?.onFocusChange(v, has)
            m.animate().alpha(if (has) 1f else 0f).setDuration(if (has) 220 else 160).setInterpolator(Ui.SETTLE).start()
        }
        p.buttons("Continue") { next(LOOK) }
        return p.built(fitRow.view)
    }

    private fun placeMarks(m: FrameLayout, percent: Int) {
        val ins = ScreenFit.insets(ctx, percent)
        for (i in 0 until m.childCount) {
            val c = m.getChildAt(i)
            (c.layoutParams as FrameLayout.LayoutParams).let { it.setMargins(ins[0] + 10.dp, ins[1] + 10.dp, ins[0] + 10.dp, ins[1] + 10.dp); c.layoutParams = it }
        }
    }

    /** The current look: a tiny Dream Cinema home screen, its name with the pick check, and "Current Look". */
    private fun lookCard(label: String, look: Map<String, Int>) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
        val prev = LookPreview(look)
        addView(prev.view, LinearLayout.LayoutParams(240.dp, 134.dp))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // The drawn neutral pick check (never a text "✓").
            addView(View(ctx).apply { background = StatusGlyph(BrandRow.Mark.PICK) }, LinearLayout.LayoutParams(16.dp, 16.dp).apply { marginEnd = 8.dp })
            addView(k.label(label, Ui.T_LABEL, k.text, k.medium))
            addView(k.label("Current Look", Ui.T_CAPTION, Ui.alpha(k.dim, 0.9f), k.font).apply { setPadding(12.dp, 0, 0, 0) })
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 12.dp })
    }

    // ---- 4. home button ----

    private fun homeActive() = HomeRedirectService.current() != null
    private fun assistantActive() = try {
        Settings.Secure.getString(ctx.contentResolver, "voice_interaction_service") == "${ctx.packageName}/.FireVoiceService"
    } catch (e: Exception) { false }
    private fun canFix() = ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun homeStep(): Built {
        val p = Split(glyph(R.drawable.ic_home), "Make Dream OS Your Home",
            "So the Home button always brings you here, and voice search lands in Dream Search.")
        val chip = statusChip("", GOOD)
        p.leftAdd(chip, 18)
        val note = p.note("")
        val home = row("Home Button", "Home opens Dream OS from any app", icon = R.drawable.ic_home, value = "", focusable = false)
        val instant = row("Instant Home", "No waiting after you press Home", icon = R.drawable.ic_bolt, value = "", focusable = false)
        val action = row("Turn On", null, icon = R.drawable.ic_settings, chevron = true) { r -> fixHome(r) }
        p.add(home.view, 0)
        p.add(instant.view, 6)
        p.add(action.view, 6)
        val main = p.buttons("Continue") { next(HOME) }
        val b = p.built(main)
        // Everything updates in place; only what changed is touched.
        fun show() {
            val h = homeActive()
            val a = assistantActive()
            setStatus(home, h)
            setStatus(instant, a)
            val fix = canFix()
            val actionLabel: String? = when {
                h && a -> {
                    setChip(chip, "Dream OS Is Your Home", GOOD)
                    setTextIfNew(note, "Everything's on. Nothing else to do here.")
                    null
                }
                fix -> {
                    setChip(chip, "Almost There", WARN)
                    setTextIfNew(note, "Dream OS can turn these back on by itself.")
                    if (action.title.text.toString() == "Turning On…") "Turning On…" else "Turn On"
                }
                !h -> {
                    setChip(chip, "Home Button Is Off", WARN)
                    setTextIfNew(note, "In Accessibility, choose Dream OS and turn it on, then come back here.")
                    "Open Accessibility Settings"
                }
                else -> {
                    setChip(chip, "Home Button Is On", GOOD)
                    setTextIfNew(note, "Instant Home needs a one-time setup from a computer. Everything else works without it.")
                    null
                }
            }
            setShown(note, true)
            val hadFocus = action.view.isFocused
            if (actionLabel != null) setTextIfNew(action.title, actionLabel)
            setShown(action.view, actionLabel != null)
            // The focused row went away (it's all on now): Continue takes the focus, nothing else moves.
            if (hadFocus && actionLabel == null && page?.view === b.view) main.requestFocus()
        }
        show()
        onResumeCheck = { show() }
        homeShow = { show() }
        onLeave = { homeShow = null }
        return Built(b.view, if (action.view.visibility == View.VISIBLE) action.view else main, b.anims)
    }

    /** The Home step's in-place refresh, while it's on screen. */
    private var homeShow: (() -> Unit)? = null

    private fun fixHome(r: Row) {
        if (!canFix()) { host.openAccessibilitySettings(); return }
        HomeRedirectService.ensureEnabled(ctx, userAsked = true)
        Log.w(TAG, "setup: re-enabled the Home button and assistant slot")
        setTextIfNew(r.title, "Turning On…")
        val g = gen
        // The system binds the service a moment later: look once, and once more only if it isn't on yet.
        fun check(attempt: Int) {
            r.view.postDelayed({
                if (g != gen) return@postDelayed
                val done = homeActive() && assistantActive()
                if (!done && attempt == 0) { check(1); return@postDelayed }
                if (!done) setTextIfNew(r.title, "Turn On")
                homeShow?.invoke()
            }, if (attempt == 0) 1500L else 3000L)
        }
        check(0)
    }

    private fun setStatus(r: Row, on: Boolean) {
        setTextIfNew(r.value, if (on) "Active" else "Off")
        setColorIfNew(r.value, if (on) GREEN else WARN_C)
    }

    // ---- 5. content ----

    private fun content(): Built {
        val p = Split(glyph(R.drawable.ic_live), "Your Home Screen",
            "Pick the apps for your home screen. The weather comes from your approximate location.", tags = listOf("Optional"))
        val weather = row("Weather", "Based on your internet connection", icon = R.drawable.ic_bolt, value = "Finding You…", focusable = false)
        p.add(weather.view, 0)
        val g = gen
        Weather.load(ctx) { now ->
            if (g != gen) return@load
            weather.value?.text = if (now == null) "Unavailable Right Now" else "${now.tempF}° ${now.place}".trim()
        }
        p.add(p.kicker("YOUR APPS"), 18)
        val grid = appGrid()
        p.add(grid.first, 8)
        val main = p.buttons("Continue") { next(CONTENT) }
        return p.built(grid.second ?: main)
    }

    /**
     * Connect Movie & Show Info (owner, 2026-10-28, the iPhone's TMDB step): the viewer's own free TMDB key is
     * required (Dream OS ships no keys), with Show Me How and If Something Goes Wrong; OMDb for ratings is optional.
     */
    private fun tmdbStep(): Built {
        val ui = SetupUi(ctx, k)
        val panel = SetupPanel(ui)
        val me = cur
        val keys = KeyPages(ctx, ui, panel) { onResumeCheck?.invoke() }
        val s = SetupScreen(ui, null, null)
        val rows = ScreenRows(s)
        fun paint() {
            s.view("k:tmdb", 0) { SetupParts(ctx, k).section("Your TMDB Key", true) }
            keys.addRows(s, rows, KeyPages.Kind.TMDB, 10)
            s.view("k:omdb", 24) { SetupParts(ctx, k).section("Ratings", false) }
            s.view("n:omdb", 8) { SetupParts(ctx, k).note("A free OMDb key adds Rotten Tomatoes and IMDb scores to every title. You can add it now or later in Settings.", 14f) }
            keys.addRows(s, rows, KeyPages.Kind.OMDB, 10)
        }
        paint()
        s.onShow = { paint(); onResumeCheck?.invoke() }
        s.onResume = { paint(); onResumeCheck?.invoke() }
        val b = panelStep(SetupArt.Kind.ACCOUNTS, "Connect Movie & Show Info",
            if (Tmdb.hasKey(ctx)) "TMDB is a free movie and TV website. Every poster, detail and search in Dream OS comes from it. Your TMDB key is added, so you can continue."
            else "TMDB is a free movie and TV website. Every poster, detail and search in Dream OS comes from it, so Dream OS needs your own free TMDB key before it can show anything.",
            panel, s, continueFirst = { Tmdb.hasKey(ctx) }, tags = listOf("Required", "Free", "About 5 Minutes")) {}
        Log.w(TAG, "Setup: step Movie & Show Info (${if (blocker(me) == null) "key added" else "no key yet"})")
        return b
    }

    /**
     * An API key (TMDB by default, or OMDb for ratings): the row turns into a text field (the phone remote
     * can type into it too). The new key is checked with one request and the old one kept if it fails.
     */
    private fun editKey(
        p: Split, r: Row,
        hintText: String = "Paste or Type Your TMDB Key",
        tipText: String = "Tip: type it from your phone with the phone remote.",
        current: () -> String = { Tmdb.key(ctx) },
        has: () -> Boolean = { Tmdb.hasKey(ctx) },
        save: (String) -> Unit = { Tmdb.setKey(ctx, it) },
        check: () -> Boolean = { Tmdb.trending(ctx).isNotEmpty() },
    ) {
        val parent = r.view.parent as? ViewGroup ?: return
        val idx = parent.indexOfChild(r.view)
        val old = current()
        val field = textField(hintText, if (has()) old else "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 64) {}
        val tip = k.label(tipText, Ui.T_CAPTION, k.dim, k.font, lines = 2)
        r.view.visibility = View.GONE
        parent.addView(field, idx + 1, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        parent.addView(tip, idx + 2, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
        keyField = field
        fun close() {
            hideKeyboard()
            parent.removeView(field)
            parent.removeView(tip)
            keyField = null
            cancelKeyEdit = null
            r.view.visibility = View.VISIBLE
            r.view.requestFocus()
        }
        cancelKeyEdit = { close() }
        field.setOnEditorActionListener { _, _, _ ->
            val key = field.text.toString().trim()
            close()
            if (key.isEmpty() || key == old) return@setOnEditorActionListener true
            r.value?.text = "Checking…"
            r.value?.setTextColor(k.dim)
            save(key)
            val g = gen
            Net.background({ check() }) { ok ->
                if (ok != true) save(old) // keep the key that worked
                if (g != gen) return@background
                r.value?.text = if (ok == true) "Connected" else "Key Didn't Work"
                r.value?.setTextColor(if (ok == true) GREEN else ERROR)
            }
            true
        }
        field.post { field.requestFocus() }
    }

    /** Up to 12 apps as tiles; chosen ones are on the home screen's Your Apps row. Returns (grid, first tile). */
    private fun appGrid(): Pair<View, View?> {
        val all = try { host.apps() } catch (e: Exception) { emptyList() }
        val favs = Apps.favorites(ctx)
        val streaming = Tmdb.SERVICES.map { it.pkg }.toSet()
        val ordered = all.sortedWith(compareBy<AppEntry>({ if (it.pkg in favs) 0 else if (it.pkg in streaming || !it.system) 1 else 2 },
            { favs.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.label.lowercase() })).take(12)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (ordered.isEmpty()) {
            box.addView(k.label("Your apps show up here once they're installed.", Ui.T_LABEL, k.dim, k.font, lines = 2))
            return box to null
        }
        var first: View? = null
        for (chunk in ordered.chunked(6)) {
            val line = hRow().apply { gravity = Gravity.START }
            for (app in chunk) {
                val tile = appTile(app)
                if (first == null) first = tile
                line.addView(tile, LinearLayout.LayoutParams(70.dp, WRAP_CONTENT).apply { marginEnd = 6.dp })
            }
            box.addView(line, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (box.childCount > 0) topMargin = 8.dp })
        }
        return box to first
    }

    private fun appTile(app: AppEntry) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isFocusable = true
        isClickable = true
        // The tile takes the tile's focus (duplicate state, wired before it's added): the pearl face inside the
        // rainbow border when focused; at rest neutral glass, a brighter one with a hairline once it's picked.
        val tileBox = FrameLayout(ctx).apply { isDuplicateParentStateEnabled = true }
        val rest = Ui.rounded(ctx, Color.TRANSPARENT, Ui.R_MD)
        val glow = PearlFocus.apply(tileBox, Ui.R_MD, rest = rest)
        val icon = ImageView(ctx).apply { setImageDrawable(try { app.icon } catch (e: Exception) { null }); scaleType = ImageView.ScaleType.FIT_CENTER }
        tileBox.addView(icon, FrameLayout.LayoutParams(36.dp, 36.dp, Gravity.CENTER))
        // Picked: a pearl disc with an ink check, turning ink with a pearl check on the focused pearl face.
        val check = View(ctx).apply {
            isDuplicateParentStateEnabled = true
            foreground = CheckInk(PearlFocus.INK, PearlFocus.PEARL, weight = 0.13f) // drawn, never a text "✓"
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), oval(PearlFocus.INK))
                addState(IntArray(0), oval(PearlFocus.PEARL))
            }
        }
        tileBox.addView(check, FrameLayout.LayoutParams(16.dp, 16.dp, Gravity.TOP or Gravity.END).apply { topMargin = 3.dp; marginEnd = 3.dp })
        addView(tileBox, LinearLayout.LayoutParams(56.dp, 56.dp))
        addView(k.label(app.label, 11f, k.dim, k.font).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(68.dp, WRAP_CONTENT).apply { topMargin = 5.dp })
        fun paint() {
            val on = app.pkg in Apps.favorites(ctx)
            check.visibility = if (on) View.VISIBLE else View.INVISIBLE
            rest.setColor(Ui.alpha(Color.WHITE, if (on) 0.16f else 0.06f))
            rest.setStroke(maxOf(1, 1.dp), Ui.alpha(Color.WHITE, if (on) 0.5f else 0.08f))
        }
        paint()
        setOnFocusChangeListener { _, has -> PearlFocus.focus(glow, has); Ui.lift(this, has, Ui.LIFT_ICON) }
        setOnClickListener {
            val favs = Apps.favorites(ctx)
            if (!favs.remove(app.pkg)) favs.add(app.pkg)
            Apps.saveFavorites(ctx, favs)
            paint()
            refreshDrawableState()
        }
    }

    // ---- Dream OS's required steps: time zone, accounts, scrapers, the final check ----

    /** The Accounts pages on screen (their live checks decide [blocker] for ACCOUNTS). */
    private var accountPages: AccountsPages? = null
    /** The "what's still missing" line on a required step, or null. */
    private var blockNote: TextView? = null

    private fun showBlocker(why: String) {
        Log.w(TAG, "Setup: held at step $cur: $why")
        val n = blockNote ?: return
        setTextIfNew(n, why)
        setShown(n, true)
        shake(n)
    }

    /** The quiet "what's missing" line under a required step's controls; refreshed in place. */
    private fun addBlockNote(p: Split, step: Int): TextView {
        val n = k.label("", Ui.T_CAPTION, Ui.alpha(k.dim, 0.95f), k.font, lines = 0).apply { visibility = View.GONE }
        p.add(n, 12, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        blockNote = n
        val refresh = { if (cur == step) { val why = blocker(step); if (why == null) setShown(n, false) else if (n.visibility == View.VISIBLE) setTextIfNew(n, why) } }
        onResumeCheck = refresh
        return n
    }

    private fun zoneStep(): Built {
        val me = cur
        val p = Split(SetupArt(ctx, SetupArt.Kind.CLOCK), "Your Time Zone",
            "Dream OS uses this to show when new episodes and movies come out.", iconDp = 112)
        val now = k.label("", 22f, k.text, k.semibold, lines = 2)
        fun paintNow() = setTextIfNew(now, "${DreamTime.label(ctx, DreamTime.zone(ctx))}  ·  ${DreamTime.formatTime(ctx, System.currentTimeMillis())}")
        paintNow()
        p.add(now, 0)
        var main: View? = null
        // Picking a zone (or choosing the one already picked) moves straight on to Continue: no scrolling to the end.
        val picker = TimeSettings.picker(ctx, heightDp = 300) { paintNow(); if (cur == me) main?.requestFocus() }
        p.add(picker.view, 12, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // The header clock keeps the same minute as the list (which refreshes every minute).
        val tick = object : Runnable { override fun run() { if (cur != me) return; paintNow(); view.postDelayed(this, 15_000) } }
        view.postDelayed(tick, 15_000)
        onLeave = { picker.release(); view.removeCallbacks(tick) }
        main = p.buttons("Continue") { next(me) }
        return p.built(main!!)
    }

    /**
     * A required step built on a [SetupPanel] (the Accounts and Scrapers pages, shared with Settings):
     * the panel in the right-hand column, Back steps back inside it, Continue held by [blocker].
     */
    /** [continueFirst]: focus starts on Continue (nothing on the page needs doing, e.g. everything unlocked). */
    private fun panelStep(art: SetupArt.Kind, title: String, sub: String, panel: SetupPanel, first: SetupScreen,
                          continueFirst: () -> Boolean = { false }, tags: List<String> = emptyList(), onRelease: () -> Unit): Built {
        val me = cur
        panel.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 330.dp)
        panel.push(first, animate = false)
        slot = object : SlotPage {
            override val title = title
            override val subtitle: String? = sub
            override val content: View = panel
            override val focus: View? get() = if (continueFirst()) null else SetupScreen.firstFocusable(first.body)
            override fun handleBack() = panel.pop()
            override fun release() { onRelease(); panel.release() }
        }
        val p = Split(SetupArt(ctx, art), title, sub, iconDp = 112, tags = tags)
        p.add(panel, 0)
        addBlockNote(p, me)
        val main = p.buttons("Continue") { next(me) }
        return p.built(if (continueFirst()) main else SetupScreen.firstFocusable(first.body) ?: main)
    }

    private fun accountsStep(): Built {
        val ui = SetupUi(ctx, k)
        val panel = SetupPanel(ui)
        val me = cur
        val pages = AccountsPages(ctx, ui, panel, inSetup = true) { onResumeCheck?.invoke() }
        accountPages = pages
        return panelStep(SetupArt.Kind.ACCOUNTS, "Connect Your Accounts",
            "A debrid service is a paid online account that streams movies and shows to Dream OS in full quality, with no waiting. Dream OS needs one to play anything. Most cost about \$3 to \$12 a month.",
            panel, pages.overview(), tags = listOf("Required", "Paid", "About 5 Minutes")) { if (accountPages === pages) accountPages = null }
            .also { Log.w(TAG, "Setup: step Accounts (${if (blocker(me) == null) "ready" else "waiting"})") }
    }

    private fun scrapersStep(): Built {
        val ui = SetupUi(ctx, k)
        val panel = SetupPanel(ui)
        val pages = ScraperPages(ctx, ui, panel) { onResumeCheck?.invoke() }
        val listener: () -> Unit = { view.post { onResumeCheck?.invoke() } }
        Scrapers.addListener(listener)
        return panelStep(SetupArt.Kind.SCRAPERS, "Set Up Scrapers",
            "Scrapers are free add-ons that search the web for streams of what you pick, so your debrid service can play them. They come in a repository: a web address you add here. Dream OS doesn't include any.",
            panel, pages.overview(), tags = listOf("Required", "Free", "About 2 Minutes")) { Scrapers.removeListener(listener) }
    }

    // ---- Before You Start, Playback Preferences, Who's Watching (pages in DreamSetupMore.kt) ----

    private fun introStep(): Built {
        val me = cur
        val p = Split(SetupArt(ctx, SetupArt.Kind.INTRO), "Before You Start",
            "Dream OS doesn't include or host any movies, shows or sources. You connect your own accounts and add your own scraper repositories, and you're responsible for what you add.",
            iconDp = 112, tags = listOf("Required"))
        p.add(p.kicker("YOUR PRIVACY"), 0)
        p.add(k.label("Where your information goes:", 16f, k.text, k.medium), 10)
        p.add(SetupIntro.list(ctx, k), 12)
        p.add(k.label("A few free services provide extras such as subtitles and weather. Dream OS has no ads, tracking or analytics.",
            Ui.T_CAPTION, Ui.alpha(k.dim, 0.95f), k.font, lines = 0).apply { setLineSpacing(3.dp.toFloat(), 1f) }, 14)
        val main = p.buttons("I Understand") { next(me) }
        Log.w(TAG, "Setup: step Before You Start")
        return p.built(main)
    }

    /**
     * What You'll Need (owner, 2026-10-28; the iPhone's list): what's required, what gives the full viewing
     * experience, what each piece does and what it costs, before the steps ask for them.
     */
    private fun needStep(): Built {
        val me = cur
        val p = Split(SetupArt(ctx, SetupArt.Kind.ACCOUNTS), "What You'll Need",
            "Each step explains what it needs and shows you how. You make the keys and accounts on their own websites from your phone. Anything optional can be added later in Settings.",
            iconDp = 112)
        p.add(p.kicker("REQUIRED"), 0)
        p.add(SetupIntro.needs(ctx, k, listOf(
            Triple("A TMDB Key", "Every poster, detail and search. About 5 minutes", "Free"),
            Triple("A Debrid Service", "Streams what you watch. About \$3 to \$12 a month", "Paid"),
            Triple("A Scraper Repository", "Add-ons that find streams for your debrid service", "Free"),
        )), 10)
        p.add(p.kicker("FOR THE FULL VIEWING EXPERIENCE"), 18)
        p.add(SetupIntro.needs(ctx, k, listOf(
            Triple("Premiumize", "The fastest Play, about \$12 a month. TorBox works the same way", "Recommended"),
            Triple("An OMDb Key", "Rotten Tomatoes and IMDb scores", if (Ratings.omdbEnabled(ctx)) "Included" else "Free"),
            Triple("Trakt", "Your history and watchlist on every device", "Free"),
            Triple("A Profile for Everyone", "Their own rows, picks and Kids limits", ""),
        )), 10)
        val main = p.buttons("Continue") { next(me) }
        Log.w(TAG, "Setup: step What You'll Need")
        return p.built(main)
    }

    private fun playbackStep(): Built {
        val ui = SetupUi(ctx, k)
        val panel = SetupPanel(ui)
        return panelStep(SetupArt.Kind.PLAYBACK, "Playback Preferences",
            "Choose picture quality, languages, subtitles and sound. The defaults suit most TVs.",
            panel, PlaybackPages(ctx, ui, panel).overview(), tags = listOf("Optional")) {}
    }

    private fun whoStep(): Built {
        val ui = SetupUi(ctx, k)
        val panel = SetupPanel(ui)
        return panelStep(SetupArt.Kind.PROFILES, "Who's Watching?",
            "Add a profile for everyone at home. A Kids profile only shows movies and shows up to the rating you choose.",
            panel, ProfilesPages(ctx, ui, panel, pins).overview(), tags = listOf("Optional")) {}
    }

    private fun checkStep(): Built {
        val me = cur
        val art = SetupArt(ctx, SetupArt.Kind.CHECK)
        val p = Split(art, "Getting You Ready",
            "Dream OS runs one real search with your debrid service and scrapers, so you know everything works before you start watching.",
            iconDp = 112, tags = listOf("Required", "About 30 Seconds"))
        val line = k.label("", 22f, k.text, k.semibold, lines = 2)
        val detail = k.label("", Ui.T_CAPTION, Ui.alpha(k.dim, 0.95f), k.font, lines = 0)
        p.add(line, 0)
        p.add(detail, 8, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // While it runs: what it does (the iPhone's Show Me How for the check). After a miss: What to Try.
        val explain = k.label(SetupGuide.CHECK_STEPS[0].see.orEmpty(), Ui.T_CAPTION, Ui.alpha(k.dim, 0.8f), k.font, lines = 0).apply {
            setLineSpacing(3.dp.toFloat(), 1f)
            visibility = View.GONE
        }
        p.add(explain, 10, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val help = FrameLayout(ctx).apply { visibility = View.GONE }
        p.add(help, 12, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addBlockNote(p, me)
        var running = false
        lateinit var main: View
        // After the check passes: a short speed test, with "Use 1080p" when it's too slow for Maximum Quality.
        val speed = SpeedCard(ctx, k) { main.requestFocus() }
        p.add(speed, 16, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        onLeave = { speed.release() }
        fun run() {
            if (running) return
            running = true
            val g = gen
            art.checkState = SetupArt.CheckState.RUNNING
            setTextIfNew(line, "Checking…")
            setTextIfNew(detail, "")
            setShown(blockNote, false)
            setShown(help, false)
            setShown(explain, true)
            Thread {
                val r = try {
                    Scrapers.test(ctx.applicationContext) { msg -> view.post { if (gen == g) setTextIfNew(detail, msg) } }
                } catch (e: Exception) {
                    Scrapers.TestResult(false, 0, 0, 0, "Something went wrong. Try again.")
                }
                view.post {
                    if (gen != g) return@post
                    running = false
                    SetupGate.setCheckPassed(ctx, r.ok)
                    art.checkState = if (r.ok) SetupArt.CheckState.OK else SetupArt.CheckState.FAIL
                    setTextIfNew(line, if (r.ok) "Found ${r.found} Sources  ·  ${r.ready} Ready to Play" else "Not Quite Ready")
                    setTextIfNew(detail, r.message)
                    setShown(explain, false)
                    help.removeAllViews()
                    if (!r.ok) help.addView(SetupGuide.whatToTry(ctx, k, SetupGuide.checkHelp(r.message)))
                    setShown(help, !r.ok)
                    (main as? TextView)?.text = if (r.ok) "Continue" else "Try Again"
                    Log.w(TAG, "Setup: final check ${if (r.ok) "passed" else "failed"}: ${r.found} found, ${r.ready} ready in ${r.ms} ms")
                    if (r.ok) speed.run()
                }
            }.start()
        }
        main = p.buttons(if (SetupGate.checkPassed(ctx)) "Continue" else "Check Now") {
            if (SetupGate.checkPassed(ctx)) next(me) else run()
        }
        if (SetupGate.checkPassed(ctx)) {
            art.checkState = SetupArt.CheckState.OK
            setTextIfNew(line, "Ready to Watch")
            view.post { if (cur == me) speed.run() }
        } else view.post { if (cur == me) run() }
        return p.built(main)
    }

    /**
     * Unlock Dream OS Features (DreamUnlock.kt): recommended, never required. Skipping keeps Dream OS
     * working as an app; the same page lives in Settings to finish later.
     */
    private fun unlockStep(): Built {
        val ui = SetupUi(ctx, k)
        val panel = SetupPanel(ui)
        val me = cur
        val pages = UnlockPages(ctx, ui, panel, onChange = { onResumeCheck?.invoke() },
            afterTurnOff = { if (cur == me) page?.view?.findViewWithTag<View>(MAIN)?.requestFocus() })
        val b = panelStep(SetupArt.Kind.UNLOCK, "Unlock Dream OS Features",
            "A one-time step so Dream OS can fully take over your Fire TV: the Home button, startup, voice search and more.",
            panel, pages.overview(), continueFirst = { DreamUnlock.allOn(ctx) }, tags = listOf("Recommended")) {}
        (blockNote)?.let { n ->
            onResumeCheck = { if (cur == me) setTextIfNew(n, if (DreamUnlock.allOn(ctx)) "" else "You can skip this and unlock later in Settings.") }
            n.visibility = View.VISIBLE
            onResumeCheck?.invoke()
        }
        return b
    }

    // ---- 8. apple ----

    private fun appleTvInstalled() = try { Apps.launchIntent(ctx, APPLE_TV) != null } catch (e: Exception) { false }

    private fun appleStep(): Built {
        val p = Split(glyph(R.drawable.ic_phone), "Dream OS + Apple",
            "Optional. Dream OS works beautifully with your AirPods and iPhone. Here's what's included.")
        p.add(p.kicker("WORKS GREAT"), 0)
        p.add(info("AirPods", "TV sound, auto-connect and the AirPods menu", true), 8)
        p.add(info("AirPods to iPhone and Back", "One press in the AirPods menu", true), 4)
        p.add(info("iPhone", "A remote in your browser, with no app to install", true), 4)
        p.add(info("Typing From Your iPhone", "Search and sign in without the on-screen keyboard", true), 4)
        val main = p.buttons("Continue") { next(APPLE) }
        return p.built(main)
    }

    /** A plain, unfocusable fact row with a drawn status mark (a check, or a quiet ring when not available). */
    private fun info(title: String, caption: String, ok: Boolean) =
        row(title, caption, value = " ", focusable = false, compact = true).also {
            it.value?.text = ""
            it.value?.background = StatusGlyph(if (ok) BrandRow.Mark.OK else BrandRow.Mark.OFF)
            it.value?.minWidth = 22.dp
            it.value?.minHeight = 22.dp
            it.value?.textSize = 16f
        }.view

    // ---- 9. airpods ----

    private fun airPodsStep(): Built {
        // Illustration and connection ring (AirPodsArt.kt): one slow turn on arrival and on connect, otherwise still.
        val art = AirPodsArt(ctx, k.select)
        val ring = ConnectionRing(ctx, k)
        val ringLabel = k.label("", Ui.T_LABEL, k.dim, k.semibold).apply { gravity = Gravity.CENTER }
        val hero = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(art, LinearLayout.LayoutParams(300.dp, 150.dp))
            addView(ring, LinearLayout.LayoutParams(84.dp, 84.dp).apply { topMargin = 12.dp })
            addView(ringLabel, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 8.dp })
        }
        val p = Split(null, "Dream OS Works With AirPods Right Out of the Box", null, hero = hero)
        val sub = p.subtitle!!
        p.note("Pair them once. Dream OS connects them each time it starts, keeps the sound smooth while you watch, " +
            "and moves them to your iPhone and back from the AirPods menu on the home screen.")
        val body = vBox()
        p.add(body, 16)
        val optKicker = p.kicker("OPTIONS")
        p.add(optKicker, 16)
        val auto = row("Connect Automatically", "Each time Dream OS starts", icon = R.drawable.ic_headphones,
            toggle = AirPodsLink.autoConnect(ctx)) { r -> AirPodsLink.setAutoConnect(ctx, !AirPodsLink.autoConnect(ctx)); r.sw?.set(AirPodsLink.autoConnect(ctx)) }
        val sleep = row("Sleep When Put Away", "Sets a 10-minute sleep timer", icon = R.drawable.ic_moon,
            toggle = Theme.get(ctx, Theme.AIRPODS_SLEEP) == 1) { r ->
            val on = Theme.get(ctx, Theme.AIRPODS_SLEEP) == 0
            Theme.set(ctx, Theme.AIRPODS_SLEEP, if (on) 1 else 0)
            r.sw?.set(on)
        }
        p.add(auto.view, 8)
        p.add(sleep.view, 6)
        val main = p.buttons("Continue") { next(AIRPODS) }
        var state: String? = null
        var wasConnected: Boolean? = null
        var action: View? = null
        fun ringTo(s: ConnectionRing.State) {
            ring.show(s)
            setTextIfNew(ringLabel, when (s) {
                ConnectionRing.State.CONNECTED -> "Connected"
                ConnectionRing.State.CONNECTING -> "Connecting…"
                else -> "Not Connected"
            })
            setColorIfNew(ringLabel, if (s == ConnectionRing.State.CONNECTED) GREEN else k.dim)
        }
        // Every state's rows exist once; a change only swaps which are shown and updates text in place.
        val connectedRow = row("AirPods", "Connected to this TV", icon = R.drawable.ic_headphones, value = "", focusable = false).also {
            // The drawn green check (never a text "✓").
            it.value?.setCompoundDrawablesRelative(StatusGlyph(BrandRow.Mark.OK).apply { setBounds(0, 0, 22.dp, 22.dp) }, null, null, null)
        }
        var connecting: Runnable? = null
        val pairedRow = row("AirPods", "Paired with this TV", icon = R.drawable.ic_headphones, value = "Connect") { r ->
            if (connecting != null || !host.connectAirPods()) return@row
            setTextIfNew(r.value, "Connecting…")
            ringTo(ConnectionRing.State.CONNECTING)
            // No connection after a while: they're probably still in the closed case. One timer, cleared on any change.
            val t = Runnable {
                connecting = null
                if (!r.view.isAttachedToWindow) return@Runnable
                setTextIfNew(r.value, "Try Again")
                ringTo(ConnectionRing.State.IDLE)
            }
            connecting = t
            r.view.postDelayed(t, 9000)
        }
        val stepRows = listOf(
            "Open Bluetooth Settings, then Other Bluetooth Devices.",
            "Open the AirPods case and hold its button until the light flashes white.",
            "Choose your AirPods, then return here.",
        ).mapIndexed { i, t -> row(t, null, number = i + 1, focusable = false, compact = true) }
        val btRow = row("Open Bluetooth Settings", null, icon = R.drawable.ic_settings, chevron = true) { host.openBluetoothSettings() }
        body.addView(connectedRow.view)
        body.addView(pairedRow.view)
        for ((i, r) in stepRows.withIndex()) body.addView(r.view, rowLp(i > 0, 4))
        body.addView(btRow.view, rowLp(true))
        fun fill() {
            val paired = host.pairedAirPods()
            val connected = paired != null && host.airPodsConnected()
            val s = "$paired|$connected"
            if (s == state) return
            val first = state == null
            state = s
            connecting?.let { pairedRow.view.removeCallbacks(it) }
            connecting = null
            ringTo(if (connected) ConnectionRing.State.CONNECTED else ConnectionRing.State.IDLE)
            // They just connected while the step is up: one more turn.
            if (connected && wasConnected == false) art.turn()
            wasConnected = connected
            val focused = body.findFocus()
            setShown(sub, true)
            // Options matter once AirPods are paired; until then the pairing steps get the room.
            setShown(optKicker, paired != null)
            setShown(auto.view, paired != null)
            setShown(sleep.view, paired != null)
            setShown(connectedRow.view, connected)
            setShown(pairedRow.view, paired != null && !connected)
            for (r in stepRows) setShown(r.view, paired == null)
            setShown(btRow.view, paired == null)
            when {
                connected -> {
                    setTextIfNew(sub, "TV sound plays in your AirPods.")
                    setTextIfNew(connectedRow.title, paired!!)
                    action = null
                }
                paired != null -> {
                    setTextIfNew(sub, "Open the case near the TV, then connect.")
                    setTextIfNew(pairedRow.title, paired)
                    setTextIfNew(pairedRow.value, "Connect")
                    action = pairedRow.view
                }
                else -> {
                    setTextIfNew(sub, "Pair them once in Bluetooth settings. After that, Dream OS connects them for you.")
                    action = btRow.view
                }
            }
            // Focus only moves when the focused row was just hidden.
            if (!first && focused != null && focused.visibility != View.VISIBLE) (action ?: main).requestFocus()
        }
        fill()
        onAirPodsCheck = { fill() }
        // The first turn starts once the page has slid in.
        art.postDelayed({ if (art.isAttachedToWindow) art.turn() }, 420)
        onLeave = { art.stop(); ring.stop() }
        return p.built(action ?: main)
    }

    // ---- 10. iphone ----

    private fun iPhoneStep(): Built {
        val url = host.remoteUrl()
        val p = Split(glyph(R.drawable.ic_phone), "Your iPhone Is a Remote",
            if (url == null) "Connect the TV to Wi-Fi, then find this in Settings, under Devices."
            else "Point your iPhone's camera at the code. A remote opens in your browser, with no app to install.")
        p.leftAdd(k.label("Remote and Typing · Open Apps\n" + "AirPods to iPhone or TV · Photo Wallpaper",
            Ui.T_LABEL, k.text, k.medium, lines = 0).apply { setLineSpacing(6.dp.toFloat(), 1f) }, 18)
        if (url != null) {
            val qr = FrameLayout(ctx).apply {
                background = Ui.rounded(ctx, Color.WHITE, Ui.R_LG)
                setPadding(12.dp, 12.dp, 12.dp, 12.dp)
                addView(ImageView(ctx).apply {
                    tag = QR
                    setImageBitmap(host.qrBitmap(url))
                    // Crisp modules: no smoothing when the small bitmap is scaled up.
                    (drawable as? BitmapDrawable)?.let { it.isFilterBitmap = false; it.setAntiAlias(false) }
                }, MATCH_PARENT, MATCH_PARENT)
            }
            p.add(qr, 0, LinearLayout.LayoutParams(196.dp, 196.dp))
            p.add(k.label(url.substringBefore("/?"), Ui.T_CAPTION, k.dim, k.font), 10)
            p.add(k.label("Works on Android too. Your phone needs the same Wi-Fi as the TV.", Ui.T_CAPTION, k.dim, k.font, lines = 2), 4)
        }
        val main = p.buttons("Continue") { next(IPHONE) }
        return p.built(main)
    }

    /** Drops the QR bitmap as soon as its page has faded away. */
    private fun releaseQr(page: View) {
        val img = page.findViewWithTag<ImageView>(QR) ?: return
        val bmp = (img.drawable as? BitmapDrawable)?.bitmap
        img.setImageDrawable(null)
        bmp?.recycle()
    }

    // ---- 11. voice ----

    private fun voiceStep(): Built {
        val p = Split(glyph(R.drawable.ic_search), "Search With Your Voice",
            "Hold the microphone button on your remote, say a movie or show, then let go. The results open in Dream Search.")
        p.note("Some remotes' microphones don't work with this Fire TV. If nothing happens, try another remote.")
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = k.sheet(Ui.R_XL)
            setPadding(24.dp, 22.dp, 24.dp, 22.dp)
        }
        val badge = FrameLayout(ctx)
        val badgeIcon = ImageView(ctx).apply { setImageResource(R.drawable.ic_search); setColorFilter(PearlFocus.INK) }
        // Heard: a drawn white check on the green disc (never a text "✓").
        val badgeCheck = View(ctx).apply { background = CheckInk(Color.WHITE, weight = 0.075f); visibility = View.GONE }
        badge.addView(badgeIcon, FrameLayout.LayoutParams(28.dp, 28.dp, Gravity.CENTER))
        badge.addView(badgeCheck, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val headline = k.label("", Ui.T_HEADLINE, k.text, k.semibold).apply { gravity = Gravity.CENTER }
        val detail = k.label("", Ui.T_LABEL, k.dim, k.font, lines = 3).apply { gravity = Gravity.CENTER; setLineSpacing(3.dp.toFloat(), 1f) }
        card.addView(k.kicker("TRY IT"))
        card.addView(badge, LinearLayout.LayoutParams(64.dp, 64.dp).apply { topMargin = 14.dp })
        card.addView(headline, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        card.addView(detail, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
        p.add(card, 0, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val main = p.buttons("Continue", null) { next(VOICE) }
        val listenBg = oval(PearlFocus.PEARL) // listening: the pearl disc with an ink glyph, like the step emblems
        val heardBg = oval(GREEN_DEEP)
        var shownGood: Boolean? = null
        // In place: the badge only changes when the outcome does, the text only when it differs.
        fun show(state: Int, q: String?) {
            val good = state == V_HEARD
            if (good != shownGood) {
                badge.background = if (good) heardBg else listenBg
                badgeIcon.visibility = if (good) View.GONE else View.VISIBLE
                badgeCheck.visibility = if (good) View.VISIBLE else View.GONE
                // The check pops in once, when a search first comes through.
                if (good && shownGood == false) {
                    badge.scaleX = 0.6f; badge.scaleY = 0.6f
                    badge.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(Ui.SPRING).start()
                }
                shownGood = good
            }
            val (h, d) = when (state) {
                V_LISTENING -> "Listening…" to "Say a title, then let go of the button."
                V_HEARD -> "Voice Search Works" to (if (q.isNullOrBlank()) "Your search came through." else "You said “$q”.")
                V_MISSED -> "Let's Try Again" to "That didn't sound like a search. Say the name of a movie or show."
                else -> if (!homeActive()) "Turn On the Home Button First" to "Voice search needs it. Go back a few steps to turn it on."
                    else "Hold the Microphone Button" to "Then say something like “The Office.”"
            }
            setTextIfNew(headline, h)
            setTextIfNew(detail, d)
        }
        voiceState = if (voiceTested) V_HEARD else V_IDLE
        show(voiceState, null)
        voice = { s, q -> show(s, q) }
        onResumeCheck = { if (voiceState == V_IDLE) show(V_IDLE, null) }
        return p.built(main)
    }

    // ---- 12. extras ----

    private fun moreStep(): Built {
        val p = Split(glyph(R.drawable.ic_settings), "A Few Extras", "Small touches. All optional, all in Settings.")
        p.note("The sleep timer is in Settings too, whenever you want to fall asleep to something.")
        val saver = stepper("Screensaver", null, R.drawable.ic_moon, {
            val m = Theme.SAVER_MINUTES[Theme.get(ctx, Theme.SAVER).coerceIn(0, Theme.SAVER_MINUTES.size - 1)]
            if (m == 0) "Off" else "After $m Min"
        }) { by ->
            val n = Theme.SAVER_MINUTES.size
            Theme.set(ctx, Theme.SAVER, (Theme.get(ctx, Theme.SAVER) + by + n) % n)
        }
        p.add(saver.view, 0)
        if (AdBlock.supported()) {
            val st = AdBlock.status(ctx)
            val ad = row("Block Ads", if (st.on) "Using ${st.label}" else "Hides ads and trackers across the TV", icon = R.drawable.ic_shield, toggle = st.on) { r ->
                val ok = if (AdBlock.status(ctx).on) AdBlock.disable(ctx) else AdBlock.enable(ctx, AdBlock.PROVIDERS.first().host)
                val now = AdBlock.status(ctx)
                r.sw?.set(now.on)
                r.caption?.text = when {
                    !ok -> "Not available on this TV"
                    now.on -> "Using ${now.label}. Change it in Settings."
                    else -> "Hides ads and trackers across the TV"
                }
            }
            p.add(ad.view, 6)
        }
        for ((key, t, c) in listOf(
            Triple(Theme.NAV_SOUND, "Navigation Sounds", "Soft ticks as you move around"),
            Triple(Theme.INTRO_SOUND, "Intro Sound", "A gentle chime at startup"),
        )) {
            val r = row(t, c, icon = R.drawable.ic_play, toggle = Theme.get(ctx, key) == 1) { r ->
                val on = Theme.get(ctx, key) == 0
                Theme.set(ctx, key, if (on) 1 else 0)
                r.sw?.set(on)
            }
            p.add(r.view, 6)
        }
        // DreamUpdate.kt: on by default.
        val upd = row("Automatic Updates", "Keeps Dream OS up to date", icon = R.drawable.ic_bolt, toggle = DreamUpdate.autoEnabled(ctx)) { r ->
            val on = !DreamUpdate.autoEnabled(ctx)
            DreamUpdate.setAuto(ctx, on)
            r.sw?.set(DreamUpdate.autoEnabled(ctx))
            Log.w(TAG, "Setup: automatic updates ${if (on) "on" else "off"}")
        }
        p.add(upd.view, 6)
        p.buttons("Continue") { next(MORE) }
        return p.built(saver.view)
    }

    // ---- 13. done ----

    private fun doneStep(): Built {
        val col = column()
        val avatar = k.label(emoji, 46f, Color.WHITE).apply { gravity = Gravity.CENTER; background = oval(color) }
        val c0 = color
        val halo = FrameLayout(ctx).apply {
            addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    gradientType = GradientDrawable.RADIAL_GRADIENT
                    gradientRadius = 96.dp.toFloat()
                    colors = intArrayOf(Ui.alpha(c0, 0.42f), Color.TRANSPARENT)
                }
            }, FrameLayout.LayoutParams(220.dp, 170.dp, Gravity.CENTER))
            addView(avatar, FrameLayout.LayoutParams(100.dp, 100.dp, Gravity.CENTER))
        }
        col.addView(halo, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        val title = titleText(if (name.isBlank()) "You're All Set" else "You're All Set, $name", center = true)
        val sub = subText("Change anything anytime in Settings.", center = true)
        col.addView(title, lp(top = 0))
        col.addView(sub, lp(top = 8))
        val items = listOfNotNull(
            "Profile" to true,
            (if (homeActive()) "Home Button On" else "Home Button Off") to homeActive(),
            (if (Tmdb.hasKey(ctx)) "Movie & Show Info" else "Movie & Show Info Not Added") to Tmdb.hasKey(ctx),
            (if (Ratings.omdbEnabled(ctx)) "Ratings" else "Ratings Not Added") to Ratings.omdbEnabled(ctx),
            (if (SetupGate.anyVerified(ctx)) "Accounts Connected" else "Accounts") to SetupGate.anyVerified(ctx),
            (if (SetupGate.scrapersReady(ctx)) "Scrapers Ready" else "Scrapers") to SetupGate.scrapersReady(ctx),
            (if (host.airPodsConnected()) "AirPods Connected" else "AirPods Not Connected") to host.airPodsConnected(),
            (if (host.remoteUrl() != null) "Phone Remote Ready" else "Phone Remote Needs Wi-Fi") to (host.remoteUrl() != null),
            (if (voiceTested) "Voice Search Tested" else "Voice Search") to voiceTested,
        )
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        for (chunk in items.chunked(4)) {
            val line = hRow()
            for ((label, ok) in chunk) {
                line.addView(k.label(label, Ui.T_CAPTION, if (ok) k.text else k.dim, k.medium).apply {
                    // A drawn green check when done, a quiet ring when not (never a text "✓" / "–").
                    setCompoundDrawablesRelative(StatusGlyph(if (ok) BrandRow.Mark.OK else BrandRow.Mark.OFF).apply { setBounds(0, 0, 14.dp, 14.dp) }, null, null, null)
                    compoundDrawablePadding = 8.dp
                    setPadding(14.dp, 7.dp, 14.dp, 7.dp)
                    background = k.glass(Ui.R_PILL, 70)
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 5.dp; marginEnd = 5.dp })
            }
            grid.addView(line, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (grid.childCount > 0) topMargin = 10.dp })
        }
        col.addView(grid, lp(top = 22))
        val main = pill("Start Watching", primary = true) { next(DONE) }
        col.addView(main, LinearLayout.LayoutParams(WRAP_CONTENT, 46.dp).apply { topMargin = 28.dp })
        return Built(center(col), main, listOf(halo, title, sub, grid, main))
    }

    private var celebrating = false

    /**
     * The send-off after Start Watching: the page dims away, "You're All Set" appears alone in the center,
     * then the Dream OS mark rises out of a slowly turning rainbow halo with "Welcome to Dream OS" under it, then [then].
     * About 6 s; every animation ends, and keys are ignored meanwhile.
     */
    private fun celebrate(then: () -> Unit) {
        if (celebrating) return
        celebrating = true
        Log.w(TAG, "Setup: finished, welcome animation")
        (stage as ViewGroup).descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        stage.animate().alpha(0f).setDuration(420).setInterpolator(Ui.EXIT).start()
        dots.animate().alpha(0f).setDuration(300).start()
        val layer = FrameLayout(ctx).apply { isClickable = true; isFocusable = true; isFocusableInTouchMode = true }
        val halo = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.SWEEP_GRADIENT
                colors = Rainbow.COLORS + Rainbow.COLORS[0]
            }
            alpha = 0f; scaleX = 0.4f; scaleY = 0.4f
        }
        // A soft black core over the halo leaves only a glowing rainbow rim.
        val core = View(ctx).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = 118.dp.toFloat()
                colors = intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT)
            }
            alpha = 0f
        }
        val mark = ImageView(ctx).apply {
            setImageResource(R.drawable.dream_mark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0f; scaleX = 0.6f; scaleY = 0.6f
        }
        val art = FrameLayout(ctx).apply {
            addView(halo, FrameLayout.LayoutParams(250.dp, 250.dp, Gravity.CENTER))
            addView(core, FrameLayout.LayoutParams(250.dp, 250.dp, Gravity.CENTER))
            addView(mark, FrameLayout.LayoutParams(150.dp, 150.dp, Gravity.CENTER))
        }
        val title = k.label("You're All Set", Ui.T_DISPLAY, k.text, k.semibold, Ui.TRACK_TITLE).apply { gravity = Gravity.CENTER; alpha = 0f }
        val welcome = "Welcome to Dream OS"
        val brand = welcome.indexOf("Dream OS")
        val sub = k.label("", 24f, k.text, k.medium).apply {
            gravity = Gravity.CENTER; alpha = 0f
            text = Rainbow.still(welcome, brand, welcome.length, 180.dp.toFloat())
        }
        val enjoy = k.label("Enjoy!", 18f, k.dim, k.font).apply { gravity = Gravity.CENTER; alpha = 0f }
        // Three beats: "You're All Set" alone, then the mark rising out of the halo, then the welcome under it.
        val col = column().apply {
            addView(art, LinearLayout.LayoutParams(260.dp, 260.dp))
            addView(sub, lp(top = 8))
            addView(enjoy, lp(top = 8))
        }
        layer.addView(col, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        layer.addView(title, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        view.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        layer.requestFocus()
        // 1. "You're All Set" (0.3 s → 1.9 s).
        title.scaleX = 0.94f; title.scaleY = 0.94f
        title.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(300).setDuration(600).setInterpolator(Ui.EASE).start()
        view.postDelayed({
            title.animate().alpha(0f).scaleX(1.04f).scaleY(1.04f).setStartDelay(0).setDuration(380).setInterpolator(Ui.EXIT).start()
            // 2. The halo turns in and the mark pops up out of it (2.1 s → 3.2 s).
            halo.animate().alpha(0.85f).scaleX(1f).scaleY(1f).rotation(160f).setStartDelay(200).setDuration(2600).setInterpolator(Ui.EASE).start()
            core.animate().alpha(1f).setStartDelay(200).setDuration(500).start()
            mark.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(380).setDuration(720)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
            // 3. "Welcome to Dream OS", then "Enjoy!" (from 3.0 s).
            Ui.rise(listOf(sub, enjoy), fromDp = 10, startDelay = 1100L, step = 260L, duration = 560L)
        }, 1900)
        view.postDelayed({
            layer.animate().alpha(0f).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT).withEndAction {
                view.removeView(layer)
                then()
            }.start()
        }, 5600)
    }

    // ---- look preview ----

    /**
     * A tiny Dream Cinema home screen in [v]'s colors: the floating top bar (logo dot, tabs, the time), the
     * hero's title and lines, and a row of cards whose first one wears the rainbow focus line.
     */
    private inner class LookPreview(v: Map<String, Int>) {
        val view = FrameLayout(ctx)
        init {
            fun c(key: String) = v[key] ?: Theme.get(ctx, key)
            view.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c(Theme.BG_TOP), c(Theme.BG_BOTTOM))).apply {
                cornerRadius = Ui.R_LG.dp.toFloat()
                setStroke(1.dp, Ui.alpha(Color.WHITE, 0.14f))
            }
            view.clipToOutline = true
            view.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            // Top bar: the logo, three tabs (the first one current), the time.
            view.addView(View(ctx).apply { background = Ui.rounded(ctx, Ui.alpha(c(Theme.TEXT), 0.9f), Ui.R_PILL) },
                FrameLayout.LayoutParams(8.dp, 8.dp).apply { leftMargin = 12.dp; topMargin = 10.dp })
            for (i in 0..2) view.addView(View(ctx).apply {
                background = Ui.rounded(ctx, if (i == 0) c(Theme.SELECT) else Ui.alpha(c(Theme.TEXT), 0.28f), Ui.R_PILL)
            }, FrameLayout.LayoutParams(22.dp, 5.dp, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 12.dp; leftMargin = ((i - 1) * 56).dp })
            view.addView(View(ctx).apply { background = Ui.rounded(ctx, Ui.alpha(c(Theme.TEXT), 0.5f), Ui.R_PILL) },
                FrameLayout.LayoutParams(18.dp, 5.dp, Gravity.TOP or Gravity.END).apply { topMargin = 12.dp; rightMargin = 12.dp })
            // The hero: a title and two lines of text.
            view.addView(View(ctx).apply { background = Ui.rounded(ctx, c(Theme.TEXT), Ui.R_PILL) },
                FrameLayout.LayoutParams(84.dp, 9.dp).apply { leftMargin = 14.dp; topMargin = 40.dp })
            for (i in 0..1) view.addView(View(ctx).apply { background = Ui.rounded(ctx, Ui.alpha(c(Theme.TEXT_DIM), 0.6f), Ui.R_PILL) },
                FrameLayout.LayoutParams(if (i == 0) 110.dp else 76.dp, 4.dp).apply { leftMargin = 14.dp; topMargin = (58 + i * 9).dp })
            // A row of cards; the first one is focused (the rainbow, the only color).
            for (i in 0..3) view.addView(View(ctx).apply {
                background = Ui.rounded(ctx, c(Theme.TILE), Ui.R_XS)
            }, FrameLayout.LayoutParams(48.dp, 30.dp).apply { leftMargin = (14 + i * 54).dp; topMargin = 88.dp })
            view.addView(View(ctx).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, Rainbow.COLORS).apply { cornerRadius = 2.dp.toFloat() }
            }, FrameLayout.LayoutParams(48.dp, 3.dp).apply { leftMargin = 14.dp; topMargin = 121.dp })
        }
    }

    // ---- layout ----

    /**
     * A split step: [icon], title and explanation on the left; controls on the right with the
     * buttons under them.
     */
    private inner class Split(icon: View?, title: String, sub: String?, iconDp: Int = 52, hero: View? = null, tags: List<String> = emptyList()) {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val anims = ArrayList<View>()
        val subtitle: TextView?
        private val centered = hero != null

        init {
            if (hero != null) {
                // Hero mode: the illustration owns the left column; the title leads the controls.
                left.gravity = Gravity.CENTER_HORIZONTAL
                leftAdd(hero, 0)
                add(titleText(title, center = false), 0)
                subtitle = subText(sub ?: "", center = false)
                add(subtitle, 8)
            } else {
                if (icon != null) leftAdd(icon, 0, LinearLayout.LayoutParams(iconDp.dp, iconDp.dp))
                leftAdd(titleText(title, center = false), if (icon != null) 18 else 0)
                // The step's facts ("Required", "Free", "About 5 Minutes"), the iPhone's step tags.
                if (tags.isNotEmpty()) leftAdd(tagRow(tags), 10)
                subtitle = subText(sub ?: "", center = false)
                leftAdd(subtitle, 10)
            }
            root.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.42f))
            root.addView(right, LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.58f).apply { marginStart = 52.dp })
        }

        fun leftAdd(v: View, top: Int, lp: LinearLayout.LayoutParams? = null) {
            val p = lp ?: (v.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            p.topMargin = top.dp
            left.addView(v, p)
            anims += v
        }

        /** A short quiet paragraph under the explanation. */
        fun note(t: String): TextView = k.label(t, Ui.T_CAPTION, Ui.alpha(k.dim, 0.9f), k.font, lines = 0).apply {
            setLineSpacing(3.dp.toFloat(), 1f)
            if (centered) gravity = Gravity.CENTER
            if (t.isEmpty()) visibility = View.GONE
            leftAdd(this, 14, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        fun kicker(t: String) = k.kicker(t).apply { setPadding(6.dp, 0, 0, 0) }

        fun add(v: View, top: Int, lp: LinearLayout.LayoutParams? = null) {
            val p = lp ?: (v.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            p.topMargin = top.dp
            right.addView(v, p)
            anims += v
        }

        /** Primary pill (and an optional quiet one) under the controls; both move on. Returns the primary. */
        fun buttons(primary: String, secondary: String? = null, onClick: () -> Unit): View {
            val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val main = pill(primary, primary = true) { onClick() }.apply { tag = MAIN; minWidth = 180.dp }
            bar.addView(main, LinearLayout.LayoutParams(WRAP_CONTENT, 46.dp))
            if (secondary != null) bar.addView(pill(secondary, primary = false) { onClick() }, LinearLayout.LayoutParams(WRAP_CONTENT, 46.dp).apply { marginStart = 14.dp })
            add(bar, 20, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            return main
        }

        fun built(focus: View) = Built(root, focus, anims)
    }

    private fun column() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }

    /** Small capsules: "Required" is the strong one. */
    private fun tagRow(tags: List<String>) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for ((i, t) in tags.withIndex()) {
            val strong = t == "Required"
            addView(k.label(t, 12f, if (strong) Color.WHITE else k.dim, k.semibold, 0.02f).apply {
                setPadding(11.dp, 4.dp, 11.dp, 5.dp)
                background = Ui.rounded(ctx, Ui.alpha(Color.WHITE, if (strong) 0.14f else 0.06f), Ui.R_PILL, 1f, Ui.alpha(Color.WHITE, if (strong) 0.30f else 0.14f))
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (i > 0) marginStart = 8.dp })
        }
    }

    private fun center(col: View) = FrameLayout(ctx).apply { addView(col, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER)) }

    private fun vBox() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    private fun hRow() = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }

    private fun lp(top: Int) = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = top.dp }

    private fun rowLp(gap: Boolean, gapDp: Int = 6) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (gap) topMargin = gapDp.dp }

    private fun titleText(t: String, center: Boolean) = k.label(t, if (center) Ui.T_DISPLAY else 30f, k.text, k.semibold, Ui.TRACK_TITLE, lines = 2).apply {
        if (center) gravity = Gravity.CENTER
    }

    private fun subText(t: String, center: Boolean) = k.label(t, 16f, k.dim, k.font, lines = 0).apply {
        setLineSpacing(4.dp.toFloat(), 1f)
        if (center) { gravity = Gravity.CENTER; maxWidth = 620.dp }
        if (t.isEmpty()) visibility = View.GONE
    }

    // ---- controls ----

    /**
     * Setup's buttons in the Dream focus look: the primary is Play-type (pearl at rest, the fading rainbow
     * fill when focused), the others neutral glass that turn pearl with dark ink; both wear the rainbow
     * glow border while focused. Glow and fill share the capsule's radius (half its 46 dp height). The
     * look is [PremiumKit.pill]'s Dream one, always on here: setup is the brand's first impression.
     */
    private fun pill(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        k.pill(label, null, primary, 46, dream = true) { onClick() }.apply { setPadding(30.dp, 0, 30.dp, 0) }

    /** A step's emblem: the pearl disc with a dark ink glyph (like the Settings card's title disc). */
    private fun glyph(icon: Int) = FrameLayout(ctx).apply {
        background = oval(PearlFocus.PEARL)
        addView(ImageView(ctx).apply { setImageResource(icon); setColorFilter(PearlFocus.INK) }, FrameLayout.LayoutParams(26.dp, 26.dp, Gravity.CENTER))
    }

    private inner class Row(val view: LinearLayout, val title: TextView, val caption: TextView?, val value: TextView?, val sw: SetupSwitch?, val check: View?)

    /**
     * One option row, like Settings: optional icon disc or step [number], title (+ caption), then a
     * value, a switch ([toggle]), a check mark ([check]) or a chevron. Unfocusable when [focusable] is
     * false (plain facts and instructions).
     */
    private fun row(
        title: String, caption: String? = null, icon: Int? = null, value: String? = null, toggle: Boolean? = null,
        chevron: Boolean = false, check: Boolean = false, number: Int? = null, focusable: Boolean = true, compact: Boolean = false,
        onClick: ((Row) -> Unit)? = null,
    ): Row {
        val focused = intArrayOf(android.R.attr.state_focused)
        fun states(on: Int, rest: Int) = ColorStateList(arrayOf(focused, IntArray(0)), intArrayOf(on, rest))
        val interactive = focusable && onClick != null
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (if (compact) 44 else 54).dp
            setPadding(12.dp, (if (compact) 5 else 7).dp, 18.dp, (if (compact) 5 else 7).dp)
            if (!interactive) background = Ui.rounded(ctx, Ui.alpha(k.text, 0.035f), Ui.R_MD)
            isFocusable = interactive
            isClickable = interactive
        }
        // Focused: the pearl box with dark ink inside the rainbow glow border (PearlFocus), like every control.
        val glow = if (interactive) PearlFocus.apply(v, Ui.R_MD) else null
        val iconRest = Ui.alpha(k.text, 0.8f)
        val d: Drawable? = icon?.let { try { ctx.getDrawable(it)?.mutate()?.apply { setTint(iconRest) } } catch (e: Exception) { null } }
        when {
            // Step number: a quiet gray disc with a white figure; on the focused pearl face, a faint ink disc and ink figure.
            number != null -> v.addView(k.label("$number", 12f, Color.WHITE, k.bold).apply {
                gravity = Gravity.CENTER
                setTextColor(states(PearlFocus.INK, Color.WHITE))
                isDuplicateParentStateEnabled = true
                background = StateListDrawable().apply {
                    addState(focused, oval(Ui.alpha(PearlFocus.INK, 0.10f)))
                    addState(IntArray(0), oval(Ui.alpha(Color.WHITE, 0.14f)))
                }
            }, LinearLayout.LayoutParams(24.dp, 24.dp).apply { marginStart = 6.dp; marginEnd = 14.dp })
            d != null -> v.addView(ImageView(ctx).apply {
                setImageDrawable(d)
                setPadding(8.dp, 8.dp, 8.dp, 8.dp)
                isDuplicateParentStateEnabled = true
                background = StateListDrawable().apply {
                    addState(focused, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.alpha(PearlFocus.INK, 0.08f)) })
                    addState(IntArray(0), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.alpha(k.text, 0.07f)) })
                }
            }, LinearLayout.LayoutParams(34.dp, 34.dp).apply { marginEnd = 14.dp })
            else -> v.setPadding(20.dp, v.paddingTop, 18.dp, v.paddingBottom)
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // Names stay on one line (a step's sentence gets two) and never break mid-word.
        val t = k.label(title, if (number != null) Ui.T_LABEL else 16f, k.text, if (number != null) k.font else k.medium, lines = if (number != null) 2 else 1).apply {
            setTextColor(states(PearlFocus.INK, if (number != null) k.dim else Ui.alpha(k.text, 0.94f)))
            isDuplicateParentStateEnabled = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        }
        texts.addView(t)
        val cap = caption?.let {
            k.label(it, 12.5f, k.dim, k.font, lines = 2).apply {
                setTextColor(states(PearlFocus.INK_DIM, k.dim))
                isDuplicateParentStateEnabled = true
                setPadding(0, 3.dp, 0, 0)
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            }.also { c -> texts.addView(c) }
        }
        v.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val value2 = value?.let {
            k.label(it, Ui.T_LABEL, k.dim, k.medium).apply {
                setTextColor(states(PearlFocus.INK_DIM, k.dim))
                isDuplicateParentStateEnabled = true
                maxWidth = 200.dp
                gravity = Gravity.END
            }.also { vv -> v.addView(vv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 12.dp }) }
        }
        val sw = toggle?.let { SetupSwitch(ctx, it, k).also { s -> v.addView(s, LinearLayout.LayoutParams(46.dp, 28.dp).apply { marginStart = 14.dp }) } }
        // The chosen item: the drawn neutral pick check (never a text "✓").
        val ck = if (check) View(ctx).apply { background = StatusGlyph(BrandRow.Mark.PICK) }
            .also { v.addView(it, LinearLayout.LayoutParams(20.dp, 20.dp).apply { marginStart = 12.dp }) } else null
        if (chevron) v.addView(k.label("›", 24f, k.dim).apply {
            setTextColor(states(PearlFocus.INK_DIM, Ui.alpha(k.text, 0.32f)))
            isDuplicateParentStateEnabled = true
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 12.dp })
        val r = Row(v, t, cap, value2, sw, ck)
        if (interactive) {
            v.setOnFocusChangeListener { view, has ->
                Ui.lift(view, has, Ui.LIFT_ROW)
                d?.setTint(if (has) PearlFocus.INK else iconRest)
                glow?.let { PearlFocus.focus(it, has) }
                sw?.lit = has
            }
            v.setOnClickListener { try { onClick!!(r) } catch (e: Exception) { Log.w(TAG, "setup row $title", e) } }
        }
        v.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        return r
    }

    /** A row whose value Left / Right (or Select, forward) steps through. */
    private fun stepper(title: String, caption: String?, icon: Int?, label: () -> String, change: (Int) -> Unit): Row {
        var r: Row? = null
        fun show() { r?.value?.text = "‹ ${label()} ›" }
        r = row(title, caption, icon = icon, value = "") { change(1); show() }
        show()
        r.view.setOnKeyListener { _, code, e ->
            val by = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
            if (by != 0 && e.action == KeyEvent.ACTION_DOWN) { change(by); show() }
            by != 0
        }
        return r
    }

    private fun statusChip(t: String, tone: Int) = k.label("", Ui.T_LABEL, k.text, k.semibold).apply {
        setPadding(14.dp, 8.dp, 16.dp, 8.dp)
        setChip(this, t, tone)
    }

    /** Sets the chip's text and tone; a no-op when it already shows exactly that (no relayout, no redraw). */
    private fun setChip(chip: TextView, t: String, tone: Int) {
        val key = "$tone|$t"
        if (chip.tag == key) return
        chip.tag = key
        // Busy is neutral white (the brand's only color is the rainbow; green and amber are status, not decoration).
        val c = when (tone) { GOOD -> GREEN; WARN -> WARN_C; BUSY -> Color.WHITE; else -> k.dim }
        chip.text = t
        // The drawn status disc before the words (never a text "✓" / "●").
        val mark = when (tone) { GOOD -> BrandRow.Mark.OK; WARN -> BrandRow.Mark.WARN; BUSY -> BrandRow.Mark.BUSY; else -> BrandRow.Mark.OFF }
        chip.setCompoundDrawablesRelative(StatusGlyph(mark).apply { setBounds(0, 0, 16.dp, 16.dp) }, null, null, null)
        chip.compoundDrawablePadding = 9.dp
        chip.setTextColor(Ui.mix(c, k.text, 0.25f))
        chip.background = Ui.rounded(ctx, Ui.alpha(c, 0.14f), Ui.R_PILL, 1f, Ui.alpha(c, 0.42f))
        chip.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun textField(hintText: String, value: String, type: Int, max: Int, onDone: () -> Unit) = EditText(ctx).apply {
        inputType = type
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        filters = arrayOf<InputFilter>(InputFilter.LengthFilter(max))
        textSize = 18f
        typeface = k.font
        // Focused: the pearl face with dark ink inside the rainbow border; at rest neutral glass, white text.
        setTextColor(PearlFocus.states(PearlFocus.INK, k.text))
        setHintTextColor(PearlFocus.states(PearlFocus.INK_DIM, Ui.alpha(k.dim, 0.8f)))
        hint = hintText
        setText(value)
        setSelection(value.length)
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(20.dp, 12.dp, 20.dp, 12.dp)
        // A capsule of 48 dp: the glow needs a box at least twice its radius, so the height never drops below.
        minimumHeight = 48.dp
        minHeight = 48.dp
        val glow = PearlFocus.apply(this, 24, rest = Ui.rounded(ctx, Ui.alpha(k.text, 0.06f), 24, 1f, Ui.alpha(k.text, 0.10f)))
        setOnFocusChangeListener { v, has -> PearlFocus.focus(glow, has); Ui.lift(v, has, Ui.LIFT_ROW) }
        setOnEditorActionListener { _, _, _ -> onDone(); true }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun tapChip(t: String, size: Float, lift: Float, onClick: () -> Unit) = k.label(t, size, Color.WHITE).apply {
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, has -> chipGlow[v]?.let { PearlFocus.focus(it, has) }; Ui.lift(v, has, lift) }
    }

    /** ~300 ms horizontal shake, built from short chained steps. */
    private fun shake(v: View) {
        v.animate().cancel()
        val steps = floatArrayOf(12f, -12f, 8f, -8f, 3f, 0f)
        fun step(i: Int) {
            if (i < steps.size) v.animate().translationX(Ui.dpf(ctx, steps[i])).setDuration(50).withEndAction { step(i + 1) }.start()
        }
        step(0)
    }

    private fun hideKeyboard() {
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    // ---- drawables ----

    /**
     * Setup's sky: the brand's black falling into a dark gray (the logo's own black, like Home), with a faint
     * neutral light from the top left. Fixed, not the theme's: setup is the first thing anyone sees.
     */
    private fun backdrop(): Drawable {
        val dm = ctx.resources.displayMetrics
        val sky = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.BLACK, Color.rgb(12, 12, 14), Color.rgb(30, 30, 34)))
        val wash = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dm.widthPixels * 0.6f
            setGradientCenter(0.3f, 0f)
            colors = intArrayOf(Color.argb(18, 255, 255, 255), Color.TRANSPARENT)
        }
        val layers = LayerDrawable(arrayOf(sky, wash))
        // Baked once into one half-size bitmap (soft gradients scale up cleanly): one full-screen pass per frame
        // instead of two gradient passes, and the lists' soft edges reuse the same pixels (SmoothScrollView).
        return try {
            val w = maxOf(1, dm.widthPixels / 2)
            val h = maxOf(1, dm.heightPixels / 2)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            c.scale(0.5f, 0.5f)
            layers.setBounds(0, 0, dm.widthPixels, dm.heightPixels)
            sky.setDither(true)
            layers.draw(c)
            android.graphics.drawable.BitmapDrawable(ctx.resources, bmp).apply { isFilterBitmap = true; setDither(true) }
        } catch (e: Throwable) { layers }
    }

    private fun oval(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }

    /** An emoji chip's resting disc [d]: the chosen one a soft gray disc with a white hairline, the rest clear. */
    private fun emojiBg(d: GradientDrawable, selected: Boolean) {
        d.setColor(if (selected) Ui.alpha(Color.WHITE, 0.14f) else Color.TRANSPARENT)
        d.setStroke(if (selected) 1.dp else 0, Ui.alpha(Color.WHITE, 0.55f))
    }

    companion object {
        private const val TAG = "FireHome"

        private const val WELCOME = 0
        private const val PROFILE = 1
        private const val LOOK = 2
        private const val HOME = 3
        private const val CONTENT = 4
        // (5 to 9 are no longer used.)
        private const val APPLE = 10
        private const val AIRPODS = 11
        private const val IPHONE = 12
        private const val VOICE = 13
        private const val MORE = 14
        private const val DONE = 15
        // Dream OS's own required steps. Order comes from [stepList], not the numbers.
        private const val ZONE = 16
        private const val ACCOUNTS = 17
        private const val SCRAPERS = 18
        private const val CHECK = 19
        private const val UNLOCK = 20
        private const val INTRO = 21
        private const val PLAYBACK = 22
        private const val WHO = 23
        private const val NEED = 24
        private const val TMDB = 25

        private const val V_IDLE = 0
        private const val V_LISTENING = 1
        private const val V_HEARD = 2
        private const val V_MISSED = 3

        private const val GOOD = 0
        private const val WARN = 1
        private const val OFF = 2
        private const val BUSY = 3

        /** Design margins inside the Screen fit inset (dp). */
        private const val SIDE = 44
        private const val TOP = 18
        private const val BOTTOM = 36

        private const val MAIN = "main"
        private val NAV_KEYS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
        private const val QR = "qr"
        private const val APPLE_TV = "com.apple.atve.amazon.appletv"
        private val GREEN = Color.parseColor("#30D158")
        private val GREEN_DEEP = Color.parseColor("#22A35A")
        private val WARN_C = Color.parseColor("#FBBF24")
        private val ERROR = Color.parseColor("#F87171")
    }
}

/**
 * Setup's switch: a capsule track with a knob that slides across (one short spring per flip).
 * [lit] = its row has the focus glow behind it, so it switches to the ink that reads on the glow.
 */
internal class SetupSwitch(ctx: Context, private var on: Boolean, k: PremiumKit) : View(ctx) {
    private val select = PearlFocus.PEARL // on: the pearl track (setup is always the brand)
    private val ink = PearlFocus.INK // lit = on the focused row's pearl box
    private val text = k.text
    private val offTrack = Ui.alpha(k.text, 0.18f)
    private val offTrackLit = Ui.fade(PearlFocus.INK, 0.26f)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var pos = if (on) 1f else 0f
    private var anim: ValueAnimator? = null

    var lit = false
        set(v) { if (field != v) { field = v; invalidate() } }

    init { isDuplicateParentStateEnabled = true }

    fun set(value: Boolean) {
        if (value == on) return
        on = value
        anim?.cancel()
        anim = ValueAnimator.ofFloat(pos, if (on) 1f else 0f).apply {
            duration = 200
            interpolator = Ui.SPRING
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        val p = pos.coerceIn(0f, 1f)
        track.color = if (lit) lerp(offTrackLit, ink, p) else lerp(offTrack, select, p)
        knob.color = if (lit) lerp(ink, select, p) else lerp(text, ink, p) // on, at rest: an ink knob on the pearl
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)
        canvas.drawCircle(r + (w - 2 * r) * pos, r, r - h * 0.13f, knob)
    }

    private fun lerp(a: Int, b: Int, t: Float) = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )
}
