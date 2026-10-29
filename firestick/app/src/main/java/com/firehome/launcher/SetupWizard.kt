package com.firehome.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
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

/**
 * First-run setup ("Run Setup Again" in Settings), redesigned 2026-10-29 for someone who has never heard of
 * TMDB, debrid or scrapers and is holding a remote.
 *
 * Four chapters after Welcome: Get Ready (what you'll need, time zone), Connect (TMDB key, debrid service,
 * scrapers, the real check), Your Fire TV (unlock, Home button, screen fit, voice search, AirPods, iPhone) and
 * Make It Yours (profile, who's watching, picture and sound, apps, extras), then Done. Every choice is saved the
 * moment it's made; everything here also lives in Settings.
 *
 * Every step has one shape, learned once ([Frame]): the chapter rail across the top ([ChapterRail]), the step's
 * emblem with "Step 2 of 4" and a plain title, one or two sentences, the one thing to do, then the buttons pinned
 * to the bottom of the column: one primary forward action, quiet secondary ones beside it. A sub-page (typing a
 * key, signing in) takes the whole column under the rail and returns or moves on by itself when it's done.
 * Only the current step's views exist. Steps slide and fade with a short stagger (transform and alpha only;
 * every animation ends, nothing loops).
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
        /** Launchable apps (the home screen's list). */
        fun apps(): List<AppEntry>
        fun openAccessibilitySettings()
        /** Setup is finished (flag saved): close it and rebuild home. */
        fun finish()
    }

    /** A step whose body is a [SetupPanel] (pages shared with Settings): Back steps back inside it first. */
    private class Slot(val panel: SetupPanel, val focus: () -> View?, val release: () -> Unit)

    private class Built(val view: View, val focus: View, val anims: List<View>)

    private val k = PremiumKit(ctx)
    private val Int.dp get() = Ui.dp(ctx, this)
    private val profileId = Profiles.current(ctx).id
    private var name: String
    private var emoji: String
    private var color: Int

    private var steps: List<Int> = emptyList()
    private var cur = WELCOME
    private var page: Built? = null
    private var gen = 0
    /** The step on screen was reached going forward (a passed check moves on by itself only then). */
    private var forward = true
    private var slot: Slot? = null
    private var nameField: EditText? = null
    /** Closes what the step opened in place (the time zone list); Back runs it first. */
    private var cancelInPlace: (() -> Unit)? = null
    /** Live status for the step on screen: re-checked when Dream OS resumes, and when AirPods change. Updates in place. */
    private var onResumeCheck: (() -> Unit)? = null
    private var onAirPodsCheck: (() -> Unit)? = null
    private var onLeave: (() -> Unit)? = null
    private var voice: ((Int, String?) -> Unit)? = null
    private var voiceState = V_IDLE
    private var voiceTested = false

    private val stage = FrameLayout(ctx)
    private val rail = ChapterRail(ctx, k).apply { alpha = 0f }
    private var marks: FrameLayout? = null

    /**
     * Root view to pass to [ModalHost.show]. Flat: the host's open / close fade is applied per child
     * instead of drawing the whole full-screen wizard into an offscreen buffer every frame.
     */
    val view: FrameLayout = Root()

    /**
     * The wizard's root. The D-pad only ever moves within the step on screen: never into a page
     * that's fading out, never onto the home screen under the wizard, and at an edge focus simply
     * stays. Up from the buttons always reaches a panel step's rows.
     */
    private inner class Root : FrameLayout(ctx) {
        override fun hasOverlappingRendering() = false

        // Home's shell (and with it the shared glow clock) is paused under setup: the clock keeps running for
        // the wizard's own glows and rainbow buttons while it shows.
        override fun onAttachedToWindow() { super.onAttachedToWindow(); CinemaStyle.keep(true) }
        override fun onDetachedFromWindow() { CinemaStyle.keep(false); super.onDetachedFromWindow() }

        override fun focusSearch(focused: View?, direction: Int): View? {
            val pv = page?.view as? ViewGroup
            if (focused == null || pv == null || !inside(focused, pv)) return super.focusSearch(focused, direction)
            FocusFinder.getInstance().findNextFocus(pv, focused, direction)?.let { return it }
            if (direction == View.FOCUS_UP && isButton(focused)) slot?.panel?.let { c -> firstFocusable(c)?.let { return it } }
            // Left / Right from a row in a scrolling list (nothing beside it): straight to the primary button, so a
            // long list never has to be scrolled to its end to reach it.
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

    /** Where focus belongs on the step on screen: the panel's own pick, else the step's start, else the primary button. */
    private fun pageTarget(): View? {
        val b = page ?: return null
        slot?.let { s -> s.focus()?.takeIf { it.isAttachedToWindow && it.isShown }?.let { return it } }
        b.focus.takeIf { it.isAttachedToWindow && it.isShown && it.isFocusable }?.let { return it }
        return b.view.findViewWithTag<View>(MAIN)?.takeIf { it.isShown } ?: firstFocusable(b.view)
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
    private val openedAt = android.os.SystemClock.uptimeMillis()
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
        view.addView(rail, FrameLayout.LayoutParams(colW(), WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        applyFit(ScreenFit.get(ctx))
        // A first-run setup cut short (a restart, a force stop) picks up after the last step it finished.
        if (!canCancel && !Profiles.isSetUp(ctx)) SetupGate.savedStep(ctx)?.toIntOrNull()?.let { done ->
            val list = stepList()
            val i = list.indexOf(done)
            if (i >= 0 && i + 1 < list.size) { cur = list[i + 1]; Log.w(TAG, "setup: resuming at step $cur") }
        }
        steps = stepList()
        val b = build(cur)
        page = b
        stage.addView(b.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // The host fades the whole wizard in; the first page's parts follow just behind it.
        enter(b, 24.dp.toFloat(), delay = 160)
        initialFocus = b.focus
        updateProgress()
        // Focused pills, tiles and rows lift past their rows and groups on every step: only the screen edge clips.
        Ui.unclip(view)
    }

    // ---- public hooks ----

    /** Goes back: inside a sub-page first, then a step. Returns false when the wizard should close (first step, cancel allowed). */
    fun handleBack(): Boolean {
        cancelInPlace?.let { it(); return true }
        if (slot?.panel?.pop() == true) return true
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

    /** Apps were added or removed: nothing in the step list depends on an app any more; the progress is redrawn. */
    fun onPackagesChanged() {
        val s = stepList().let { if (cur in it) it else (it + cur).sorted() }
        if (s == steps) return
        steps = s
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

    /** Text typed on the phone remote: fills the step's name box, if it has one (key and address entry use [SetupText]). */
    fun phoneText(text: String): Boolean {
        val f = nameField ?: return false
        f.setText(text)
        f.setSelection(f.text.length)
        f.requestFocus()
        return true
    }

    // ---- steps & navigation ----

    /** Welcome, the four chapters in order (the Home button step only while it's still off), then Done. */
    private fun stepList(): List<Int> = listOf(WELCOME) +
        CHAPTERS.flatMap { it.second }.filter { it != HOME || !homeActive() || cur == HOME } + DONE

    private fun chapterOf(step: Int) = CHAPTERS.indexOfFirst { step in it.second }

    /** The steps of [step]'s chapter that are in the flow now. */
    private fun chapterSteps(step: Int): List<Int> = CHAPTERS.getOrNull(chapterOf(step))?.second?.filter { it in steps } ?: emptyList()

    /** "Step 2 of 4" within the chapter. */
    private fun stepLabel(): String {
        val inCh = chapterSteps(cur)
        val i = inCh.indexOf(cur)
        return if (i < 0) "" else "Step ${i + 1} of ${inCh.size}"
    }

    /** Why a required step can't be left yet (shown on the page), or null. */
    private fun blocker(step: Int): String? = when (step) {
        ACCOUNTS -> SetupGate.accountsBlocker(ctx, accountPages?.isChecking() == true)
        SCRAPERS -> if (SetupGate.scrapersReady(ctx)) null else "Add a scraper repository and turn on at least one provider to continue."
        CHECK -> if (SetupGate.checkPassed(ctx)) null else "The check needs to pass to continue."
        TMDB -> if (Tmdb.hasKey(ctx)) null else "Add your TMDB key to continue."
        else -> null
    }

    private fun next(from: Int) {
        if (from != cur) return // a click from a page that is already fading away
        // The Select that opened setup (Settings › Run Setup Again) lands on Start Setup as it appears: ignored.
        if (from == WELCOME && android.os.SystemClock.uptimeMillis() - openedAt < 700) return
        blocker(from)?.let { why -> showBlocker(why); return }
        if (from == ZONE) SetupGate.setZoneConfirmed(ctx)
        if (from == NEED) SetupIntro.accept(ctx)
        if (from == PROFILE && !commitProfile()) return
        if (from == DONE) {
            SetupGate.clearStep(ctx)
            Profiles.markSetUp(ctx)
            celebrate { host.finish() }
            return
        }
        SetupGate.saveStep(ctx, from.toString()) // an interrupted setup picks up after the last finished step
        steps = stepList()
        val i = steps.indexOf(from)
        val to = if (i >= 0 && i + 1 < steps.size) steps[i + 1] else DONE
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
        this.forward = forward
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
                // No offscreen layers: each part draws its few views with the fade's alpha.
                if (old != null && old.view.parent === stage) {
                    old.view.forceHasOverlappingRendering(false)
                    old.view.animate().translationX(-dx * 0.6f).alpha(0f).setStartDelay(0).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT)
                        .withEndAction { stage.removeView(old.view); releaseQr(old.view) }.start()
                }
                enter(b, dx, delay = 110)
            }
        }
        // Resolved when it runs: a panel step may only have its rows by then.
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
        cancelInPlace = null
        slot?.let { try { it.release() } catch (e: Exception) {} }
        slot = null
        marks?.let { view.removeView(it) }
        marks = null
    }

    /** Slides and fades a page's parts in one after another (every part lands by ~0.5 s). */
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
            NEED -> needStep()
            ZONE -> zoneStep()
            TMDB -> tmdbStep()
            ACCOUNTS -> accountsStep()
            SCRAPERS -> scrapersStep()
            CHECK -> checkStep()
            UNLOCK -> unlockStep()
            HOME -> homeStep()
            LOOK -> fitStep()
            VOICE -> voiceStep()
            AIRPODS -> airPodsStep()
            IPHONE -> iPhoneStep()
            PROFILE -> profile()
            WHO -> whoStep()
            PLAYBACK -> playbackStep()
            CONTENT -> content()
            MORE -> moreStep()
            else -> doneStep()
        }
    } catch (e: Exception) {
        // Never strand the viewer: a step that can't be built becomes a plain page that moves on.
        Log.w(TAG, "setup step $s failed", e)
        val f = Frame(IconArt(ctx, R.drawable.ic_info), "Let's Keep Going", "This part can be set up later in Settings.")
        f.built(f.buttons("Continue") { next(s) })
    }

    /** The rail shows the chapter on screen and how far into it this step is; Welcome and Done have no rail. */
    private fun updateProgress() {
        val c = chapterOf(cur)
        val prog = FloatArray(CHAPTERS.size) { i ->
            when {
                c < 0 -> if (cur == DONE) 1f else 0f
                i < c -> 1f
                i > c -> 0f
                else -> chapterSteps(cur).let { s -> (s.indexOf(cur) + 1f) / s.size.coerceAtLeast(1) }
            }
        }
        rail.set(CHAPTERS.map { it.first }, c, prog)
        rail.animate().alpha(if (c >= 0) 1f else 0f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
    }

    /** The column's width: [COL_W] where it fits, else the room inside Screen Fit and the side margins. */
    private fun colW(): Int {
        val ins = ScreenFit.insets(ctx, ScreenFit.get(ctx))
        val room = ctx.resources.displayMetrics.widthPixels - 2 * (ins[0] + SIDE.dp)
        return minOf(COL_W.dp, room)
    }

    /** Content inset: Screen Fit plus the design margins; the backdrop stays full-bleed. */
    private fun applyFit(percent: Int) {
        val ins = ScreenFit.insets(ctx, percent)
        stage.setPadding(ins[0] + SIDE.dp, ins[1] + TOP.dp, ins[0] + SIDE.dp, ins[1] + BOTTOM.dp)
        (rail.layoutParams as? FrameLayout.LayoutParams)?.let { it.topMargin = ins[1] + TOP.dp; it.width = colW(); rail.layoutParams = it }
        (page?.view as? FrameLayout)?.getChildAt(0)?.let { c ->
            (c.layoutParams as? FrameLayout.LayoutParams)?.let { if (it.width > 0) { it.width = colW(); c.layoutParams = it } }
        }
        marks?.let { placeMarks(it, percent) }
    }

    // ---- Welcome ----

    private fun welcome(): Built {
        val col = column()
        val logo = ImageView(ctx).apply {
            setImageResource(R.drawable.dream_logo) // same cached drawable as the menu logo
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        // One centered column: the logo, the welcome and one sentence (the focal point), what's inside as a quiet
        // strip of named glyphs, then the buttons.
        val title = titleText("Welcome to Dream OS", center = true)
        val sub = subText("Your movies and shows in one place, ready to play in seconds. Setup takes about 15 minutes, and each step shows you how.", center = true)
        col.addView(logo, LinearLayout.LayoutParams(WRAP_CONTENT, 64.dp))
        col.addView(title, lp(top = 20))
        col.addView(sub, lp(top = 12))
        val features = WelcomeFeatures.strip(ctx, k)
        col.addView(features, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 32.dp })
        val main = pill("Start Setup", primary = true) { next(WELCOME) }.apply { tag = MAIN; minWidth = 200.dp }
        // Moving from another Dream OS stick: BackupTransfer.kt brings everything over, then Dream OS restarts.
        lateinit var restore: View
        restore = pill("Restore From Backup", primary = false) {
            val a = ctx as? android.app.Activity ?: return@pill
            Log.w(TAG, "Setup: Restore From Backup opened from Welcome")
            BackupPage.open(a, restore = true) { if (cur == WELCOME && restore.isAttachedToWindow) restore.requestFocus() }
        }
        val buttons = hRow().apply {
            addView(main, LinearLayout.LayoutParams(WRAP_CONTENT, 46.dp))
            addView(restore, LinearLayout.LayoutParams(WRAP_CONTENT, 46.dp).apply { marginStart = 16.dp })
        }
        col.addView(buttons, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 36.dp })
        Log.w(TAG, "Setup: step Welcome")
        return Built(center(col).apply { (getChildAt(0).layoutParams as FrameLayout.LayoutParams).width = MATCH_PARENT },
            main, listOf(logo, title, sub, features, buttons))
    }

    // ---- Get Ready ----

    /**
     * Here's What You'll Need (What You'll Need and Before You Start in one): the three things the Connect chapter
     * asks for, in that order, and "I Understand" for the responsibility line. Where Your Information Goes swaps
     * the cards for the list of services, in place.
     */
    private fun needStep(): Built {
        val me = cur
        val f = Frame(SetupArt(ctx, SetupArt.Kind.INTRO), "Here's What You'll Need",
            "Dream OS plays from accounts you connect yourself. It doesn't include any movies, shows or sources, and you're responsible for what you add.")
        val needs = vBox()
        val cards = hRow().apply { gravity = Gravity.TOP }
        for ((i, c) in listOf(
            Triple("A TMDB Key", "Posters, details and search", "Free  ·  About 5 Minutes"),
            Triple("A Debrid Service", "Streams what you watch in full quality", "About \$3 to \$12 a Month"),
            Triple("A Scraper Repository", "Free add-ons that find the streams", "Free  ·  About 2 Minutes"),
        ).withIndex()) cards.addView(needCard(i + 1, c.first, c.second, c.third), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { if (i > 0) marginStart = 16.dp })
        needs.addView(cards, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        needs.addView(k.label("Trakt, Rotten Tomatoes scores and the rest are optional. Add them later in Settings.",
            15f, k.dim, k.font, lines = 0), lp(top = 18))
        val privacy = vBox().apply { visibility = View.GONE }
        privacy.addView(SetupIntro.list(ctx, k).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        privacy.addView(k.label("Dream OS has no ads, tracking or analytics.", 15f, k.dim, k.font, lines = 0), lp(top = 18))
        f.add(needs, 0)
        f.add(privacy, 0)
        lateinit var where: TextView
        val main = f.buttons("I Understand", listOf("Where Your Information Goes" to {
            val showPrivacy = privacy.visibility != View.VISIBLE
            setShown(privacy, showPrivacy)
            setShown(needs, !showPrivacy)
            where.text = if (showPrivacy) "What You'll Need" else "Where Your Information Goes"
            reveal(listOf(if (showPrivacy) privacy else needs))
        })) { next(me) }
        where = f.secondary[0]
        Log.w(TAG, "Setup: step What You'll Need")
        return f.built(main)
    }

    /** One of the three things to have ready: its order, name, what it does and what it costs. Not focusable. */
    private fun needCard(n: Int, title: String, line: String, meta: String) = vBox().apply {
        background = Ui.rounded(ctx, Ui.alpha(Color.WHITE, 0.05f), Ui.R_LG, 1f, Ui.alpha(Color.WHITE, 0.08f))
        setPadding(18.dp, 16.dp, 16.dp, 16.dp)
        val top = hRow().apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(SetupArt.monogram(ctx, "$n", 26), LinearLayout.LayoutParams(26.dp, 26.dp))
        top.addView(k.label(title, 17.5f, k.text, k.semibold, lines = 0).apply { hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = 12.dp })
        addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(k.label(line, 14.5f, k.dim, k.font, lines = 0).apply { setLineSpacing(2.dp.toFloat(), 1f) }, lp(top = 10))
        addView(k.label(meta, 14f, Ui.alpha(Color.WHITE, 0.80f), k.medium, lines = 0), lp(top = 8))
    }

    /**
     * Check Your Time Zone: the zone Dream OS uses and the time there, big. Usually right, so Continue has the
     * focus; Change Time Zone opens the list in place, and picking one saves it and moves on.
     */
    private fun zoneStep(): Built {
        val me = cur
        val f = Frame(SetupArt(ctx, SetupArt.Kind.CLOCK), "Check Your Time Zone",
            "Dream OS uses it to show when new episodes and movies come out.")
        val now = k.label("", 26f, k.text, k.semibold, lines = 0)
        val how = k.label("", 15.5f, k.dim, k.font, lines = 0)
        fun paintNow() {
            setTextIfNew(now, "${DreamTime.label(ctx, DreamTime.zone(ctx))}  ·  ${DreamTime.formatTime(ctx, System.currentTimeMillis())}")
            setTextIfNew(how, if (DreamTime.isAutomatic(ctx)) "Set automatically from your Fire Stick." else "Chosen by you.")
        }
        paintNow()
        f.add(now, 0)
        f.add(how, 8)
        var picker: TimeSettings.Picker? = null
        fun closeList() {
            picker?.let { it.release(); f.body.removeView(it.view) }
            picker = null
            cancelInPlace = null
            f.listPage(false)
            setShown(now, true); setShown(how, true)
            f.main?.requestFocus()
        }
        val main = f.buttons("Continue", listOf("Change Time Zone" to {
            if (picker == null) {
                // The list is a page of its own: the sentence and the buttons step aside so it has the whole column
                // (a long list of zones is the one list here that scrolls). Back closes it.
                setShown(now, false); setShown(how, false)
                f.listPage(true)
                // The pick shows: the check moves, then the list closes on the new zone and time, and setup moves
                // on a moment later, like the other steps that advance by themselves.
                val p = TimeSettings.picker(ctx, heightDp = 300) {
                    paintNow()
                    val g = gen
                    view.postDelayed({ if (g == gen && cur == me) closeList() }, 320)
                    view.postDelayed({ if (g == gen && cur == me && picker == null) next(me) }, 1_800)
                }
                picker = p
                p.view.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                f.body.addView(p.view)
                cancelInPlace = { closeList() }
                // Fit the list to the room the column has now, then focus it.
                f.body.post {
                    if (cur != me || picker !== p) return@post
                    (p.view as? ViewGroup)?.getChildAt(0)?.let { c -> c.layoutParams = c.layoutParams.apply { height = f.body.height } }
                    p.focus.requestFocus()
                }
            }
        })) { next(me) }
        // The clock keeps the same minute as the list (which refreshes every minute).
        val tick = object : Runnable { override fun run() { if (cur != me) return; paintNow(); view.postDelayed(this, 15_000) } }
        view.postDelayed(tick, 15_000)
        onLeave = { picker?.release(); view.removeCallbacks(tick) }
        return f.built(main)
    }

    // ---- Connect ----

    /** The panel step on screen: refreshes its buttons when a sub-page closes or something changes. */
    private var panelRefresh: (() -> Unit)? = null

    /**
     * A step whose body is a [SetupPanel] (pages shared with Settings). While a sub-page is open (typing a key,
     * signing in) it has the whole column under the rail and the step's buttons step aside; when it closes, the
     * step repaints ([refresh]) and focus lands on the likely next press ([focusAfter]).
     */
    private fun panelFrame(art: View, title: String, sub: String?, panel: SetupPanel, first: SetupScreen,
                           focusAfter: () -> View?, onRelease: () -> Unit = {}): Frame {
        val me = cur
        val f = Frame(art, title, sub)
        panel.push(first, animate = false)
        f.add(panel, 0, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        panel.onDepth = { d ->
            if (cur == me) {
                f.subPage(d > 1)
                if (d <= 1) {
                    panelRefresh?.invoke()
                    // After the panel's own focus pass (it runs on the next frame): the step's likely next press.
                    view.post { view.post { if (cur == me && panel.depth <= 1) focusAfter()?.takeIf { it.isShown }?.requestFocus() } }
                }
            }
        }
        slot = Slot(panel, { if (panel.depth <= 1) focusAfter() else null }) {
            // The rows fade out with the step (they went blank at once while the rest of it faded).
            onRelease(); panel.onDepth = null; panel.release(keepViews = true)
        }
        onLeave = { panelRefresh = null }
        return f
    }

    private fun stageUi() = SetupUi(ctx, k).apply { roomy = true }

    /**
     * Connect Movie & Show Info: the viewer's own free TMDB key (required; Dream OS ships no keys). Until it's
     * added, the steps to get one with the QR code to TMDB's API page, and Enter My Key opens the entry (the key is
     * checked, and the page comes back by itself). Ratings (OMDb) are offered once the key is in, as a quiet
     * extra; setup never offers the built-in OMDb key.
     */
    private fun tmdbStep(): Built {
        val me = cur
        val ui = stageUi()
        val panel = SetupPanel(ui)
        val parts = SetupParts(ctx, k)
        val keys = KeyPages(ctx, ui, panel, { onResumeCheck?.invoke() }, builtIn = false)
        val first = SetupScreen(ui, null, null)
        fun paintFirst() {
            if (Tmdb.hasKey(ctx)) {
                first.hide("steps")
                first.view("added", 0) { parts.result(true, "Your TMDB Key Is Added") }
                first.view("addedNote", 12) { parts.note("Posters, details and search are ready.", 16f).apply { setPadding(58.dp, 0, 0, 0) } }
            } else {
                first.hide("added"); first.hide("addedNote")
                first.view("steps", 0) {
                    phoneSteps(listOf(
                        "Point your phone's camera at the code to open TMDB.",
                        "Sign up for free, then open the API page.",
                        "Copy the key labeled API Key. It's 32 letters and numbers.",
                        "Choose Enter My Key below, and type it here or on your phone.",
                    ), "https://www.themoviedb.org/settings/api", "themoviedb.org")
                }
            }
        }
        paintFirst()
        first.onShow = { paintFirst() }
        lateinit var f: Frame
        f = panelFrame(SetupArt(ctx, SetupArt.Kind.ACCOUNTS), "Connect Movie & Show Info",
            "Posters, details and search come from TMDB, a free movie website. Dream OS needs your own free key, made on your phone.",
            panel, first, focusAfter = { f.main })
        val ratings = "Add Rotten Tomatoes Scores"
        val main = f.buttons("Enter My Key", listOf(
            "Help" to { panel.push(SetupScreen(ui, "Help With TMDB", null).also { s ->
                SetupGuide.helpRows(ScreenRows(s), "tmdb", parts, panel, ui, "Get Your Free Key From TMDB", "Keys That Don't Work",
                    { SetupGuide.TMDB_STEPS }, SetupGuide.TMDB_TROUBLE, SetupGuide.TMDB_INTRO, top = 0)
            }) },
            ratings to { panel.push(keys.overview(KeyPages.Kind.OMDB)) },
        )) { if (Tmdb.hasKey(ctx)) next(me) else panel.push(keys.entry(KeyPages.Kind.TMDB)) }
        panelRefresh = {
            val has = Tmdb.hasKey(ctx)
            setTextIfNew(main, if (has) "Continue" else "Enter My Key")
            setShown(f.secondary[1], has && !Ratings.omdbEnabled(ctx))
            paintFirst()
        }
        panelRefresh?.invoke()
        onResumeCheck = { panelRefresh?.invoke() }
        Log.w(TAG, "Setup: step Movie & Show Info (${if (blocker(me) == null) "key added" else "no key yet"})")
        return f.built(main)
    }

    /**
     * Steps that need a phone: the QR code big on the left, the numbered steps in plain words beside it, and the
     * website's name under the code for anyone typing it instead.
     */
    private fun phoneSteps(lines: List<String>, url: String, site: String): View {
        val wrap = hRow().apply { gravity = Gravity.TOP }
        val code = vBox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        SetupUi(ctx, k).qr(url, QR_DP)?.let { code.addView(it, LinearLayout.LayoutParams(QR_DP.dp, QR_DP.dp)) }
        code.addView(k.label(site, 15f, Ui.alpha(Color.WHITE, 0.82f), k.medium, lines = 0).apply { gravity = Gravity.CENTER }, lp(top = 10))
        wrap.addView(code, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        wrap.addView(numbered(lines), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = 40.dp })
        return wrap
    }

    /** Numbered steps: a round number badge and the whole sentence beside it, never cut. */
    private fun numbered(lines: List<String>, gap: Int = 14) = numberedSteps(ctx, k, lines, gapDp = gap)

    /** The Accounts pages on screen (their live checks decide [blocker] for ACCOUNTS). */
    private var accountPages: AccountsPages? = null
    /** The "what's still missing" line on a required step, or null. */
    private var blockNote: TextView? = null

    private fun showBlocker(why: String) {
        Log.w(TAG, "Setup: held at step $cur: $why")
        val n = blockNote ?: return
        setTextIfNew(n, why)
        n.visibility = View.VISIBLE
        shake(n)
    }

    /**
     * Connect a Debrid Service: the four services as tiles (choose the one you have). A sign-in that works comes
     * back by itself and focus lands on Continue. Help Me Choose and Trakt are quiet buttons.
     */
    private fun accountsStep(): Built {
        val me = cur
        val ui = stageUi()
        val panel = SetupPanel(ui)
        val pages = AccountsPages(ctx, ui, panel, inSetup = true) { onResumeCheck?.invoke() }
        accountPages = pages
        val first = pages.setupOverview()
        lateinit var f: Frame
        f = panelFrame(SetupArt(ctx, SetupArt.Kind.ACCOUNTS), "Connect a Debrid Service",
            "A debrid service is a paid account that streams movies and shows to Dream OS in full quality. Choose the one you have.",
            panel, first, focusAfter = { if (accountsReady()) f.main else first.focus() }) { if (accountPages === pages) accountPages = null }
        val main = f.buttons("Continue", listOf(
            "Help Me Choose" to { panel.push(pages.help()) },
            "Connect Trakt" to { panel.push(pages.trakt()) },
        )) { next(me) }
        f.reserveNote()
        panelRefresh = { f.setNote(blocker(me)) }
        panelRefresh?.invoke()
        onResumeCheck = { panelRefresh?.invoke() }
        Log.w(TAG, "Setup: step Accounts (${if (blocker(me) == null) "ready" else "waiting"})")
        return f.built(if (accountsReady()) main else first.focus() ?: main)
    }

    /**
     * A service already passed its last check (the live re-check may still be running): Continue is the likely
     * press. Never a not-connected tile, where Select would start a sign-in nobody asked for.
     */
    private fun accountsReady() = SetupGate.anyVerified(ctx) && Tmdb.hasKey(ctx)

    /**
     * Add Your Scrapers: what an address looks like until there is one, then each repository and its providers.
     * Add Repository opens the entry; once it's added the recommended providers go on by themselves and the page
     * comes back on its own. Manage Scrapers is the whole Settings page; Help has the steps and tips.
     */
    private fun scrapersStep(): Built {
        val me = cur
        val ui = stageUi()
        val panel = SetupPanel(ui)
        val pages = ScraperPages(ctx, ui, panel, inSetup = true) { onResumeCheck?.invoke() }
        val listener: () -> Unit = { view.post { onResumeCheck?.invoke() } }
        Scrapers.addListener(listener)
        fun hasRepo() = try { Scrapers.status(ctx).repos.isNotEmpty() } catch (e: Exception) { false }
        lateinit var f: Frame
        f = panelFrame(SetupArt(ctx, SetupArt.Kind.SCRAPERS), "Add Your Scrapers",
            "Scrapers are free add-ons that find streams for your debrid service. You add them once, by typing the address of a repository. Dream OS doesn't include any.",
            panel, pages.summary(), focusAfter = { f.main }) { Scrapers.removeListener(listener) }
        val main = f.buttons("Add Repository", listOf(
            "Manage Scrapers" to { panel.push(pages.overview()) },
            "Help" to { panel.push(pages.help()) },
        )) { if (hasRepo()) next(me) else panel.push(pages.addRepo()) }
        f.reserveNote()
        panelRefresh = {
            val repo = hasRepo()
            setTextIfNew(main, if (repo) "Continue" else "Add Repository")
            setShown(f.secondary[0], repo)
            f.setNote(if (repo) blocker(me) else null)
        }
        panelRefresh?.invoke()
        onResumeCheck = { panelRefresh?.invoke() }
        return f.built(main)
    }

    /**
     * Making Sure It All Works: one real search with the viewer's debrid service and scrapers, run as the step
     * opens. When it passes and the speed test needs no choice, setup moves on by itself after the result shows.
     */
    /** The final check's test runs on its own thread; [checkDone] is the check step on screen (null when it isn't). */
    private var checkRunning = false
    private var checkDone: ((Scrapers.TestResult) -> Unit)? = null

    private fun checkStep(): Built {
        val me = cur
        val art = SetupArt(ctx, SetupArt.Kind.CHECK)
        val f = Frame(art, "Making Sure It All Works",
            "Dream OS runs one real search with your debrid service and scrapers, so you know everything plays before you start.")
        // The result line is the step's focal point; everything under it is quieter.
        val line = k.label("", 24f, k.text, k.semibold, lines = 0)
        val detail = k.label("", 15.5f, k.dim, k.font, lines = 0).apply { setLineSpacing(3.dp.toFloat(), 1f) }
        f.add(line, 0)
        f.add(detail, 8)
        val help = FrameLayout(ctx).apply { visibility = View.GONE }
        f.add(help, 16)
        var running = false
        lateinit var main: TextView
        fun moveOn(delay: Long) {
            if (!forward) return // coming back to a passed check never pushes the viewer forward again
            val g = gen
            view.postDelayed({ if (g == gen && cur == me) next(me) }, delay)
        }
        // After the check passes: a short speed test, with "Use 1080p" when it's too slow for Maximum Quality.
        val speed = SpeedCard(ctx, k) { main.requestFocus(); moveOn(1600) }
        // Nothing to choose: the result shows, then setup moves on. A slower connection: focus on its "Use 1080p".
        speed.onResult = { needsChoice -> if (!needsChoice) moveOn(2600) else speed.getChildAt(2)?.requestFocus() }
        f.add(speed, 20)
        onLeave = { speed.release(); checkDone = null }
        fun run() {
            if (running) return
            running = true
            art.checkState = SetupArt.CheckState.RUNNING
            setTextIfNew(line, "Checking…")
            setTextIfNew(detail, SetupGuide.CHECK_STEPS[0].see.orEmpty())
            setShown(help, false)
            setTextIfNew(main, "Checking…")
            // One test at a time across visits: coming back while one still runs waits for its result.
            checkDone = { r ->
                running = false
                art.checkState = if (r.ok) SetupArt.CheckState.OK else SetupArt.CheckState.FAIL
                setTextIfNew(line, if (r.ok) "Found ${r.found} Sources  ·  ${r.ready} Ready to Play" else "Not Quite Ready")
                setTextIfNew(detail, r.message)
                help.removeAllViews()
                if (!r.ok) help.addView(SetupGuide.whatToTry(ctx, k, SetupGuide.checkHelp(r.message)))
                setShown(help, !r.ok)
                setTextIfNew(main, if (r.ok) "Continue" else "Check Again")
                Log.w(TAG, "Setup: final check ${if (r.ok) "passed" else "failed"}: ${r.found} found, ${r.ready} ready in ${r.ms} ms")
                if (r.ok) speed.run() else main.requestFocus()
            }
            if (checkRunning) return
            checkRunning = true
            val g = gen
            Thread {
                val r = try {
                    Scrapers.test(ctx.applicationContext) { msg -> view.post { if (gen == g) setTextIfNew(detail, msg) } }
                } catch (e: Exception) {
                    Scrapers.TestResult(false, 0, 0, 0, "Something went wrong. Try again.")
                }
                view.post {
                    checkRunning = false
                    SetupGate.setCheckPassed(ctx, r.ok)
                    checkDone?.invoke(r)
                }
            }.start()
        }
        main = f.buttons("Continue") { if (SetupGate.checkPassed(ctx)) next(me) else run() }
        if (SetupGate.checkPassed(ctx)) {
            art.checkState = SetupArt.CheckState.OK
            setTextIfNew(line, "Ready to Watch")
            setTextIfNew(detail, "Your services found streams the last time Dream OS checked.")
            view.post { if (cur == me) speed.run() }
        } else view.post { if (cur == me) run() }
        return f.built(main)
    }

    // ---- Your Fire TV ----

    /**
     * Unlock Dream OS Features (DreamUnlock.kt): recommended, never required. The body shows what it turns on;
     * Unlock Now, Show Me How and Not Now are the step's buttons. Once everything is unlocked the step shows it
     * briefly and moves on by itself.
     */
    private fun unlockStep(): Built {
        val me = cur
        val ui = stageUi()
        val panel = SetupPanel(ui)
        lateinit var f: Frame
        val pages = UnlockPages(ctx, ui, panel, onChange = { panelRefresh?.invoke() },
            afterTurnOff = { if (cur == me) f.main?.requestFocus() }, setup = true)
        f = panelFrame(SetupArt(ctx, SetupArt.Kind.UNLOCK), "Unlock Dream OS Features",
            "Your Fire Stick keeps a few settings locked to other apps. A one-time unlock, about a minute, lets the Home button, startup and voice search open Dream OS.",
            // Just unlocked with ADB Debugging still on: turning it off is the next press (panelRefresh says the same).
            panel, pages.overview(), focusAfter = { if (DreamUnlock.allOn(ctx) && pages.canTurnOff()) f.secondary[2] else f.main })
        val main = f.buttons("Unlock Now", listOf(
            "Show Me How" to { pages.howTo() },
            "Not Now" to { next(me) },
            "Turn Off ADB Debugging" to { pages.turnOffAdb() },
        )) { if (DreamUnlock.allOn(ctx)) next(me) else pages.begin() }
        var wasAll = DreamUnlock.allOn(ctx)
        panelRefresh = {
            val all = DreamUnlock.allOn(ctx)
            val off = pages.canTurnOff()
            setTextIfNew(main, if (all) "Continue" else "Unlock Now")
            setShown(f.secondary[0], !all)
            setShown(f.secondary[1], !all)
            setShown(f.secondary[2], off)
            if (all && !wasAll && cur == me) {
                val g = gen
                // Just unlocked: turning ADB Debugging back off is the recommended next press; with nothing left to
                // do, the green marks show for a moment and setup moves on.
                if (off) view.post { if (g == gen && cur == me) f.secondary[2].requestFocus() }
                else view.postDelayed({ if (g == gen && cur == me && panel.depth <= 1) next(me) }, 2200)
            }
            wasAll = all
        }
        panelRefresh?.invoke()
        onResumeCheck = { panelRefresh?.invoke() }
        return f.built(main)
    }

    private fun homeActive() = HomeRedirectService.current() != null
    private fun assistantActive() = try {
        Settings.Secure.getString(ctx.contentResolver, "voice_interaction_service") == "${ctx.packageName}/.FireVoiceService"
    } catch (e: Exception) { false }
    private fun canFix() = ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun homeStep(): Built {
        val f = Frame(IconArt(ctx, R.drawable.ic_home), "Make Dream OS Your Home",
            "So the Home button always brings you here, and voice search lands in Dream Search.")
        val home = row("Home Button", "Home opens Dream OS from any app", icon = R.drawable.ic_home, value = "", focusable = false, compact = true)
        val instant = row("Instant Home", "No waiting after you press Home", icon = R.drawable.ic_bolt, value = "", focusable = false, compact = true)
        val action = row("Turn On", null, icon = R.drawable.ic_settings, chevron = true) { r -> fixHome(r) }
        f.add(home.view, 0)
        f.add(instant.view, 4)
        f.add(action.view, 14)
        f.reserveNote()
        val note = f.note.apply { setTextColor(k.dim); visibility = View.VISIBLE }
        val main = f.buttons("Continue") { next(HOME) }
        val b = f.built(main)
        // Everything updates in place; only what changed is touched.
        fun show() {
            val h = homeActive()
            val a = assistantActive()
            setStatus(home, h)
            setStatus(instant, a)
            val actionLabel: String? = when {
                h && a -> { setTextIfNew(note, "Everything's on. Nothing else to do here."); null }
                canFix() -> {
                    setTextIfNew(note, "Dream OS can turn these back on by itself.")
                    if (action.title.text.toString() == "Turning On…") "Turning On…" else "Turn On"
                }
                !h -> { setTextIfNew(note, "In Accessibility, choose Dream OS and turn it on, then come back here."); "Open Accessibility Settings" }
                else -> { setTextIfNew(note, "Instant Home needs a one-time setup from a computer. Everything else works without it."); null }
            }
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

    /**
     * Fit the Picture to Your TV: the four corner marks show the whole time this step is up. Most TVs need no
     * change, so Continue has the focus; Screen Fit is one row above it.
     */
    private fun fitStep(): Built {
        val f = Frame(IconArt(ctx, R.drawable.ic_tv), "Fit the Picture to Your TV",
            "Look for the four corner marks. If any are cut off, choose Screen Fit, then press Left or Right until all four show.")
        var fit = ScreenFit.get(ctx)
        val fitRow = stepper("Screen Fit", "Keeps menus clear of the TV's edges", R.drawable.ic_tv, { if (fit == 0) "Full" else "$fit%" }) { by ->
            fit = (fit + by).coerceIn(0, ScreenFit.MAX)
            ScreenFit.set(ctx, fit)
            applyFit(fit)
        }
        f.add(fitRow.view, 0, LinearLayout.LayoutParams(480.dp, WRAP_CONTENT))
        val m = FrameLayout(ctx).apply { isClickable = false }
        for (g in listOf(Gravity.TOP or Gravity.START, Gravity.TOP or Gravity.END, Gravity.BOTTOM or Gravity.START, Gravity.BOTTOM or Gravity.END)) {
            m.addView(FrameLayout(ctx).apply {
                addView(View(ctx).apply { background = Ui.rounded(ctx, PearlFocus.PEARL, Ui.R_PILL) }, FrameLayout.LayoutParams(56.dp, 4.dp, g))
                addView(View(ctx).apply { background = Ui.rounded(ctx, PearlFocus.PEARL, Ui.R_PILL) }, FrameLayout.LayoutParams(4.dp, 56.dp, g))
            }, FrameLayout.LayoutParams(56.dp, 56.dp, g))
        }
        view.addView(m, MATCH_PARENT, MATCH_PARENT)
        marks = m
        placeMarks(m, fit)
        val main = f.buttons("Continue") { next(LOOK) }
        return f.built(main)
    }

    private fun placeMarks(m: FrameLayout, percent: Int) {
        val ins = ScreenFit.insets(ctx, percent)
        for (i in 0 until m.childCount) {
            val c = m.getChildAt(i)
            (c.layoutParams as FrameLayout.LayoutParams).let { it.setMargins(ins[0] + 10.dp, ins[1] + 10.dp, ins[0] + 10.dp, ins[1] + 10.dp); c.layoutParams = it }
        }
    }

    private fun voiceStep(): Built {
        val f = Frame(IconArt(ctx, R.drawable.ic_search), "Search With Your Voice",
            "Hold the microphone button on your remote, say a movie or show, then let go. The results open in Dream Search.")
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(ctx, Ui.alpha(Color.WHITE, 0.05f), Ui.R_XL, 1f, Ui.alpha(Color.WHITE, 0.08f))
            setPadding(28.dp, 24.dp, 28.dp, 24.dp)
        }
        val badge = FrameLayout(ctx)
        val badgeIcon = ImageView(ctx).apply { setImageResource(R.drawable.ic_search); setColorFilter(PearlFocus.INK) }
        // Heard: a drawn white check on the green disc (never a text "✓").
        val badgeCheck = View(ctx).apply { background = CheckInk(Color.WHITE, weight = 0.075f); visibility = View.GONE }
        badge.addView(badgeIcon, FrameLayout.LayoutParams(28.dp, 28.dp, Gravity.CENTER))
        badge.addView(badgeCheck, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val texts = vBox()
        val headline = k.label("", Ui.T_HEADLINE, k.text, k.semibold, lines = 0)
        val detail = k.label("", 15.5f, k.dim, k.font, lines = 0).apply { setLineSpacing(3.dp.toFloat(), 1f) }
        texts.addView(k.label("Try It Now", 15f, Ui.alpha(Color.WHITE, 0.66f), k.semibold))
        texts.addView(headline, lp(top = 6))
        texts.addView(detail, lp(top = 6))
        card.addView(badge, LinearLayout.LayoutParams(64.dp, 64.dp))
        card.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = 24.dp })
        f.add(card, 0)
        f.add(k.label("Some remotes' microphones don't work with this Fire Stick. If nothing happens, try another remote.",
            14.5f, k.dim, k.font, lines = 0), 16)
        val main = f.buttons("Continue") { next(VOICE) }
        val listenBg = oval(PearlFocus.PEARL)
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
                else -> if (!homeActive()) "Turn On the Home Button First" to "Voice search needs it. Unlock Dream OS Features turns it on."
                    else "Hold the Microphone Button" to "Then say something like “The Office.”"
            }
            setTextIfNew(headline, h)
            setTextIfNew(detail, d)
        }
        voiceState = if (voiceTested) V_HEARD else V_IDLE
        show(voiceState, null)
        voice = { s, q -> show(s, q) }
        onResumeCheck = { if (voiceState == V_IDLE) show(V_IDLE, null) }
        return f.built(main)
    }

    private fun airPodsStep(): Built {
        // Illustration and connection ring (AirPodsArt.kt): one slow turn on arrival and on connect, otherwise still.
        // Its natural size (the parts are 140 dp tall), and no glow: squeezed into 200x100 the parts spilled out and the
        // glow filled the box as a gray rectangle.
        val art = AirPodsArt(ctx, k.select).apply { background = null }
        val ring = ConnectionRing(ctx, k)
        val ringLabel = k.label("", 15f, k.dim, k.semibold).apply { gravity = Gravity.CENTER }
        val hero = vBox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(art, LinearLayout.LayoutParams(296.dp, 148.dp))
            addView(ring, LinearLayout.LayoutParams(48.dp, 48.dp).apply { topMargin = 20.dp })
            addView(ringLabel, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 6.dp })
        }
        val f = Frame(IconArt(ctx, R.drawable.ic_headphones), "Dream OS Works With AirPods", "")
        val sub = f.subtitle
        val split = hRow().apply { gravity = Gravity.TOP }
        val body = vBox()
        split.addView(body, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        split.addView(hero, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 32.dp })
        f.add(split, 0)
        val auto = row("Connect Automatically", "Each time Dream OS starts", icon = R.drawable.ic_headphones,
            toggle = AirPodsLink.autoConnect(ctx)) { r -> AirPodsLink.setAutoConnect(ctx, !AirPodsLink.autoConnect(ctx)); r.sw?.set(AirPodsLink.autoConnect(ctx)) }
        val sleep = row("Sleep When Put Away", "Sets a 10-minute sleep timer", icon = R.drawable.ic_moon,
            toggle = Theme.get(ctx, Theme.AIRPODS_SLEEP) == 1) { r ->
            val on = Theme.get(ctx, Theme.AIRPODS_SLEEP) == 0
            Theme.set(ctx, Theme.AIRPODS_SLEEP, if (on) 1 else 0)
            r.sw?.set(on)
        }
        val main = f.buttons("Continue") { next(AIRPODS) }
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
        val stepRows = numbered(listOf(
            "Open Bluetooth Settings, then Other Bluetooth Devices.",
            "Open the AirPods case and hold its button until the light flashes white.",
            "Choose your AirPods, then come back here.",
        ), gap = 10)
        val btRow = row("Open Bluetooth Settings", null, icon = R.drawable.ic_settings, chevron = true) { host.openBluetoothSettings() }
        body.addView(connectedRow.view)
        body.addView(pairedRow.view)
        body.addView(stepRows, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        body.addView(btRow.view, rowLp(true, 12))
        body.addView(auto.view, rowLp(true, 10))
        body.addView(sleep.view, rowLp(true, 6))
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
            // Options matter once AirPods are paired; until then the pairing steps get the room.
            setShown(auto.view, paired != null)
            setShown(sleep.view, paired != null)
            setShown(connectedRow.view, connected)
            setShown(pairedRow.view, paired != null && !connected)
            setShown(stepRows, paired == null)
            setShown(btRow.view, paired == null)
            when {
                connected -> {
                    setTextIfNew(sub, "TV sound plays in your AirPods. Dream OS connects them each time it starts.")
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
                    setTextIfNew(sub, "Pair them once. After that, Dream OS connects them for you and moves them to your iPhone and back.")
                    action = btRow.view
                }
            }
            setShown(sub, true)
            // Focus only moves when the focused row was just hidden.
            if (!first && focused != null && focused.visibility != View.VISIBLE) (action ?: main).requestFocus()
        }
        fill()
        onAirPodsCheck = { fill() }
        // The first turn starts once the page has slid in.
        art.postDelayed({ if (art.isAttachedToWindow) art.turn() }, 420)
        onLeave = { art.stop(); ring.stop() }
        // Pairing AirPods is optional: Continue has the focus unless there's a pair to connect.
        return f.built(if (action === pairedRow.view) pairedRow.view else main)
    }

    private fun iPhoneStep(): Built {
        val url = host.remoteUrl()
        val f = Frame(IconArt(ctx, R.drawable.ic_phone), "Your iPhone Is a Remote",
            if (url == null) "Connect the TV to Wi-Fi, then find this in Settings, under Devices."
            else "A remote opens in your phone's browser, with no app to install: typing, opening apps, AirPods and photo wallpaper.")
        if (url != null) {
            val qr = FrameLayout(ctx).apply {
                background = Ui.rounded(ctx, Color.WHITE, Ui.R_LG)
                setPadding(10.dp, 10.dp, 10.dp, 10.dp)
                addView(ImageView(ctx).apply {
                    tag = QR
                    setImageDrawable(Qr.drawable(ctx, url))
                }, MATCH_PARENT, MATCH_PARENT)
            }
            val code = vBox().apply { gravity = Gravity.CENTER_HORIZONTAL }
            code.addView(qr, LinearLayout.LayoutParams(QR_DP.dp, QR_DP.dp))
            code.addView(k.label(url.substringBefore("/?").removePrefix("http://"), 15f, Ui.alpha(Color.WHITE, 0.82f), k.medium, lines = 0)
                .apply { gravity = Gravity.CENTER }, lp(top = 10))
            val wrap = hRow().apply { gravity = Gravity.TOP }
            wrap.addView(code, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            wrap.addView(numbered(listOf(
                "Point your iPhone's camera at the code.",
                "Tap the link that appears.",
                "Your phone needs the same Wi-Fi as the TV. Android phones work too.",
            )), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = 40.dp })
            f.add(wrap, 0)
        }
        val main = f.buttons("Continue") { next(IPHONE) }
        return f.built(main)
    }

    /** Drops the QR bitmap as soon as its page has faded away. */
    private fun releaseQr(page: View) {
        val img = page.findViewWithTag<ImageView>(QR) ?: return
        val bmp = (img.drawable as? BitmapDrawable)?.bitmap
        img.setImageDrawable(null)
        bmp?.recycle()
    }

    // ---- Make It Yours ----

    private fun profile(): Built {
        val avatar = k.label(emoji, 30f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = oval(color)
        }
        val f = Frame(avatar, "Create Your Profile", "Your name and a picture. Each profile keeps its own list, history and look. Add a PIN in Who's Watching or in Settings.")
        fun paint() {
            avatar.text = emoji
            (avatar.background as GradientDrawable).setColor(color)
        }
        // Picture and color as two rows that Left / Right step through (the emblem shows the result): big targets
        // on one column, never a cluster of small chips.
        fun cycle(i: Int, by: Int, n: Int) = ((i + by) % n + n) % n
        val picture = stepper("Picture", null, null, { emoji }) { by ->
            emoji = Profiles.EMOJIS[cycle(maxOf(0, Profiles.EMOJIS.indexOf(emoji)), by, Profiles.EMOJIS.size)]
            paint()
        }
        lateinit var colorRow: Row
        fun paintColor() {
            colorRow.value?.setCompoundDrawablesRelative(oval(color).apply { setBounds(0, 0, 18.dp, 18.dp) }, null, null, null)
            colorRow.value?.compoundDrawablePadding = 12.dp
        }
        colorRow = stepper("Color", null, null, { COLOR_NAMES.getOrElse(Profiles.COLORS.indexOf(color)) { "Custom" } }) { by ->
            color = Profiles.COLORS[cycle(maxOf(0, Profiles.COLORS.indexOf(color)), by, Profiles.COLORS.size)]
            paint()
            paintColor()
        }
        paintColor()
        paint()
        lateinit var main: TextView
        val field = textField("Your Name", name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, 20) {
            hideKeyboard()
            main.requestFocus()
        }
        nameField = field
        val controls = vBox()
        controls.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        controls.addView(picture.view, rowLp(true, 12))
        controls.addView(colorRow.view, rowLp(true, 6))
        val split = hRow().apply { gravity = Gravity.TOP }
        split.addView(controls, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        // PhoneTyping.kt: scan to type the name on your phone (the text lands in the name box via phoneText).
        split.addView(PhoneTyping.view(ctx, k, 104), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 40.dp })
        f.add(split, 0)
        main = f.buttons("Continue") { next(PROFILE) }
        // Start in the name box when it still needs filling in; otherwise everything is set, so Continue.
        return f.built(if (name.isBlank()) field else main)
    }

    /** Saves name / picture / color into the profile. False (and a nudge) while the name is blank. */
    private fun commitProfile(): Boolean {
        val f = nameField ?: return true
        // An empty box keeps the name the wizard started with: blank on a fresh install (never the placeholder "Me").
        val n = f.text.toString().trim().ifEmpty { name }
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

    private fun whoStep(): Built {
        val me = cur
        val ui = stageUi()
        val panel = SetupPanel(ui)
        lateinit var f: Frame
        f = panelFrame(SetupArt(ctx, SetupArt.Kind.PROFILES), "Who Else Watches?",
            "Add a profile for everyone at home, or continue if it's just you. A Kids profile only shows titles up to the rating you choose.",
            panel, ProfilesPages(ctx, ui, panel, pins, compact = true).overview(), focusAfter = { f.main })
        val main = f.buttons("Continue") { next(me) }
        return f.built(main)
    }

    /**
     * Picture and Sound: the defaults as four plain facts (they suit most TVs), so Continue has the focus.
     * Change Settings opens the whole Playback page as a sub-page.
     */
    private fun playbackStep(): Built {
        val me = cur
        val ui = stageUi()
        val panel = SetupPanel(ui)
        val first = SetupScreen(ui, null, null)
        val facts = hRow().apply { gravity = Gravity.TOP }
        fun fact(label: String) = vBox().also { c ->
            c.addView(k.label(label, 15f, k.dim, k.medium, lines = 0))
            c.addView(k.label("", 22f, k.text, k.semibold, lines = 0), lp(top = 6))
            facts.addView(c, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        val q = fact("Maximum Quality")
        val a = fact("Audio Language")
        val s = fact("Subtitles")
        // Shown in setup (owner, 2026-10-29): limits keep oversized files from pausing on a slow connection.
        val z = fact("Source Size Limits")
        fun paint() {
            PlaybackPrefs.ensureDefaults(ctx.applicationContext)
            val app = ctx.applicationContext
            setTextIfNew(q.getChildAt(1) as TextView, PlaybackPrefs.qualityLabel(PlaybackPrefs.quality(app)))
            setTextIfNew(a.getChildAt(1) as TextView, PlaybackPrefs.audioLabel(app))
            setTextIfNew(s.getChildAt(1) as TextView,
                if (PlaybackPrefs.subsOn(app)) PlaybackPrefs.subLabel(PlaybackPrefs.subLang(app) ?: "English") else "Off")
            setTextIfNew(z.getChildAt(1) as TextView,
                if (PlaybackPrefs.sizeMode(app, false) == "off" && PlaybackPrefs.sizeMode(app, true) == "off") "No Limit" else "On")
        }
        first.add(facts, 0)
        first.add(k.label(if (DeviceVideo.plays4k(ctx)) "Your Fire Stick and TV can play 4K." else "Your Fire Stick and TV play up to 1080p.",
            15f, k.dim, k.font, lines = 0), 24)
        paint()
        first.onShow = { paint() }
        lateinit var f: Frame
        f = panelFrame(SetupArt(ctx, SetupArt.Kind.PLAYBACK), "Picture and Sound",
            "These defaults suit most TVs. Change them now, or anytime in Settings.", panel, first, focusAfter = { f.main })
        val main = f.buttons("Continue", listOf("Change Settings" to { panel.push(PlaybackPages(ctx, ui, panel).hub()) })) { next(me) }
        return f.built(main)
    }

    private fun content(): Built {
        val f = Frame(IconArt(ctx, R.drawable.ic_apps), "Choose Your Home Screen Apps",
            "Pick the apps for the Your Apps row on your home screen. Change them anytime.")
        val grid = appGrid()
        f.add(grid.first, 0)
        val main = f.buttons("Continue") { next(CONTENT) }
        // The weather on Home comes from the approximate location: shown quietly at the end of the buttons.
        val weather = f.footerNote().apply { text = "Finding your weather…" }
        val g = gen
        Weather.load(ctx) { now ->
            if (g != gen) return@load
            weather.text = if (now == null) "Weather isn't available right now." else "Weather on Home: ${now.tempF}° ${now.place}".trim()
        }
        // Apps already chosen: Continue is the likely press; none yet, the first app.
        return f.built(if (Apps.favorites(ctx).isEmpty()) grid.second ?: main else main)
    }

    /** Up to 12 apps as tiles; chosen ones are on the home screen's Your Apps row. Returns (grid, first tile). */
    private fun appGrid(): Pair<View, View?> {
        val all = try { host.apps() } catch (e: Exception) { emptyList() }
        val favs = Apps.favorites(ctx)
        val streaming = Tmdb.SERVICES.map { it.pkg }.toSet()
        val ordered = all.sortedWith(compareBy<AppEntry>({ if (it.pkg in favs) 0 else if (it.pkg in streaming || !it.system) 1 else 2 },
            { favs.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.label.lowercase() })).take(12)
        val box = vBox()
        if (ordered.isEmpty()) {
            box.addView(k.label("Your apps show up here once they're installed.", 15.5f, k.dim, k.font, lines = 0))
            return box to null
        }
        var first: View? = null
        for (chunk in ordered.chunked(6)) {
            val line = hRow().apply { gravity = Gravity.START }
            for (app in chunk) {
                val tile = appTile(app)
                if (first == null) first = tile
                line.addView(tile, LinearLayout.LayoutParams(108.dp, WRAP_CONTENT).apply { marginEnd = 8.dp })
            }
            box.addView(line, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (box.childCount > 0) topMargin = 14.dp })
        }
        return box to first
    }

    private fun appTile(app: AppEntry) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isFocusable = true
        isClickable = true
        // The pearl face inside the rainbow border when focused; at rest neutral glass, a brighter one with a
        // hairline once it's picked.
        val tileBox = FrameLayout(ctx).apply { isDuplicateParentStateEnabled = true }
        val rest = Ui.rounded(ctx, Color.TRANSPARENT, Ui.R_MD)
        val glow = PearlFocus.apply(tileBox, Ui.R_MD, rest = rest)
        val icon = ImageView(ctx).apply { setImageDrawable(try { app.icon } catch (e: Exception) { null }); scaleType = ImageView.ScaleType.FIT_CENTER }
        tileBox.addView(icon, FrameLayout.LayoutParams(32.dp, 32.dp, Gravity.CENTER))
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
        addView(tileBox, LinearLayout.LayoutParams(50.dp, 50.dp))
        // The app's whole name, wrapped under the icon (never cut to "Prime Vi…").
        addView(k.label(app.label, 12.5f, k.dim, k.font, lines = 0).apply {
            gravity = Gravity.CENTER
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        }, LinearLayout.LayoutParams(108.dp, WRAP_CONTENT).apply { topMargin = 6.dp })
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

    /** A Few Extras: the options in two columns (all optional, the defaults already on), so Continue has the focus. */
    private fun moreStep(): Built {
        val f = Frame(IconArt(ctx, R.drawable.ic_settings), "A Few Extras",
            "Small touches, already set the way most people like them. They're all in Settings too, with the sleep timer.")
        val options = ArrayList<View>()
        // Anime first (owner): the same settings as Settings › Home & Apps and Settings › Playback.
        options += row("Keep Anime in Anime Sections", "Anime shows only on the Anime page and its rows", icon = R.drawable.ic_movie,
            toggle = RowMix.animeApart(ctx)) { r ->
            val on = !RowMix.animeApart(ctx)
            Theme.set(ctx, Theme.ANIME_APART, if (on) 1 else 0)
            r.sw?.set(on)
        }.view
        if (AnimeDubs.want(ctx) != "ja") {
            options += row(AnimeDubs.settingLabel(ctx), "Plays the ${AnimeDubs.langName(AnimeDubs.want(ctx))} dub when a copy has one",
                icon = R.drawable.ic_play, toggle = AnimeDubs.preferDubs(ctx)) { r ->
                val on = !AnimeDubs.preferDubs(ctx)
                AnimeDubs.setPreferDubs(ctx, on)
                r.sw?.set(on)
            }.view
        }
        val saver = stepper("Screensaver", null, R.drawable.ic_moon, {
            val m = Theme.SAVER_MINUTES[Theme.get(ctx, Theme.SAVER).coerceIn(0, Theme.SAVER_MINUTES.size - 1)]
            if (m == 0) "Off" else "After $m Min"
        }) { by ->
            val n = Theme.SAVER_MINUTES.size
            Theme.set(ctx, Theme.SAVER, (Theme.get(ctx, Theme.SAVER) + by + n) % n)
        }
        options += saver.view
        if (AdBlock.supported()) {
            val st = AdBlock.status(ctx)
            val ad = row("Block Ads", if (st.on) "Using ${st.label}" else "Hides ads and trackers across the TV", icon = R.drawable.ic_shield, toggle = st.on) { r ->
                if (AdBlock.running) return@row
                if (AdBlock.status(ctx).on) {
                    val ok = AdBlock.disable(ctx)
                    r.sw?.set(AdBlock.status(ctx).on)
                    r.caption?.text = if (ok) "Hides ads and trackers across the TV" else "Not available on this Fire Stick"
                    return@row
                }
                // The same checked switch as Settings › Ad Blocking: a network that blocks secure DNS would
                // otherwise take every app offline, the rest of setup included. It switches back if so.
                AdBlock.running = true
                r.sw?.set(true)
                r.caption?.text = "Checking that websites still load…"
                AdBlock.enableAndVerify(ctx, AdBlock.PROVIDERS.first().host, { p -> r.caption?.text = p }) { res ->
                    AdBlock.running = false
                    val now = AdBlock.status(ctx)
                    r.sw?.set(now.on)
                    r.caption?.text = if (now.on) "Using ${now.label}. Change it in Settings." else res.message
                }
            }
            options += ad.view
        }
        for ((key, t, c) in listOf(
            Triple(Theme.NAV_SOUND, "Navigation Sounds", "Soft ticks as you move around"),
            Triple(Theme.INTRO_SOUND, "Intro Sound", "A gentle chime at startup"),
        )) {
            options += row(t, c, icon = R.drawable.ic_play, toggle = Theme.get(ctx, key) == 1) { r ->
                val on = Theme.get(ctx, key) == 0
                Theme.set(ctx, key, if (on) 1 else 0)
                r.sw?.set(on)
            }.view
        }
        // DreamUpdate.kt: on by default.
        options += row("Automatic Updates", "Keeps Dream OS up to date", icon = R.drawable.ic_bolt, toggle = DreamUpdate.autoEnabled(ctx)) { r ->
            val on = !DreamUpdate.autoEnabled(ctx)
            DreamUpdate.setAuto(ctx, on)
            r.sw?.set(DreamUpdate.autoEnabled(ctx))
            Log.w(TAG, "Setup: automatic updates ${if (on) "on" else "off"}")
        }.view
        val grid = vBox()
        for ((i, pair) in options.chunked(2).withIndex()) {
            val line = hRow().apply { gravity = Gravity.TOP }
            for ((j, o) in pair.withIndex()) line.addView(o, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (j > 0) marginStart = 20.dp })
            if (pair.size == 1) line.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = 20.dp })
            grid.addView(line, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (i > 0) topMargin = 10.dp })
        }
        f.add(grid, 0)
        val main = f.buttons("Continue") { next(MORE) }
        return f.built(main)
    }

    // ---- Done ----

    private fun doneStep(): Built {
        val col = column()
        val avatar = k.label(emoji, 40f, Color.WHITE).apply { gravity = Gravity.CENTER; background = oval(color) }
        col.addView(avatar, LinearLayout.LayoutParams(88.dp, 88.dp))
        val title = titleText(if (name.isBlank()) "You're All Set" else "You're All Set, $name", center = true)
        val sub = subText("Change anything anytime in Settings.", center = true)
        col.addView(title, lp(top = 20))
        col.addView(sub, lp(top = 10))
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
        // What's set up as a plain three-column list: aligned columns read at a glance.
        val grid = vBox()
        for (chunk in items.chunked(3)) {
            val line = hRow().apply { gravity = Gravity.TOP }
            for ((label, ok) in chunk) {
                line.addView(k.label(label, 15.5f, if (ok) Ui.alpha(k.text, 0.92f) else k.dim, k.medium, lines = 0).apply {
                    // A drawn green check when done, a quiet ring when not (never a text "✓" / "–").
                    setCompoundDrawablesRelative(StatusGlyph(if (ok) BrandRow.Mark.OK else BrandRow.Mark.OFF).apply { setBounds(0, 0, 18.dp, 18.dp) }, null, null, null)
                    compoundDrawablePadding = 12.dp
                }, LinearLayout.LayoutParams(240.dp, WRAP_CONTENT))
            }
            grid.addView(line, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (grid.childCount > 0) topMargin = 16.dp })
        }
        col.addView(grid, lp(top = 32))
        val main = pill("Start Watching", primary = true) { next(DONE) }.apply { tag = MAIN; minWidth = 220.dp }
        col.addView(main, LinearLayout.LayoutParams(WRAP_CONTENT, 46.dp).apply { topMargin = 36.dp })
        return Built(center(col), main, listOf(avatar, title, sub, grid, main))
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
        rail.animate().alpha(0f).setDuration(300).start()
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

    // ---- the step frame ----

    /**
     * One step's frame: a column [colW] wide under the rail. The head (the step's emblem, "Step 2 of 4" and the
     * title), the sentence, then the body, which takes the room that's left, then the footer: the primary button
     * and any quiet secondary ones, always at the same place at the bottom. A step's content can grow or shrink
     * inside the body; the head and the buttons never move.
     */
    private inner class Frame(art: View?, title: String, sub: String?) {
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val root = FrameLayout(ctx).apply {
            clipChildren = false
            clipToPadding = false
            addView(column, FrameLayout.LayoutParams(colW(), MATCH_PARENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = RAIL_ROOM.dp })
        }
        private val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val subtitle: TextView = subText(sub ?: "", center = false)
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        private val footer = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        /** The "what's still missing" line just above the buttons; its line is kept even when empty, so nothing moves. */
        val note: TextView = k.label("", 15f, Ui.alpha(Color.WHITE, 0.78f), k.medium, lines = 0).apply { visibility = View.GONE }
        val secondary = ArrayList<TextView>()
        var main: TextView? = null
        private val anims = ArrayList<View>()

        init {
            if (art != null) head.addView(art, LinearLayout.LayoutParams(EMBLEM.dp, EMBLEM.dp).apply { marginEnd = 20.dp })
            val texts = vBox()
            texts.addView(k.label(stepLabel(), 15f, Ui.alpha(Color.WHITE, 0.66f), k.medium, lines = 0))
            texts.addView(titleText(title, center = false), lp(top = 2))
            head.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            column.addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            column.addView(subtitle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12.dp })
            column.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = BODY_TOP.dp })
            column.addView(note, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp })
            column.addView(footer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
            anims += head; anims += subtitle; anims += body; anims += footer
        }

        fun add(v: View, top: Int, lp: LinearLayout.LayoutParams? = null) {
            val p = lp ?: (v.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            p.topMargin = top.dp
            body.addView(v, p)
        }

        /** The primary button and quiet secondary ones beside it. Exactly one forward action. Returns the primary. */
        fun buttons(primary: String, secondaries: List<Pair<String, () -> Unit>> = emptyList(), onClick: () -> Unit): TextView {
            val m = pill(primary, primary = true) { onClick() }.apply { tag = MAIN; minWidth = 200.dp }
            footer.addView(m, LinearLayout.LayoutParams(WRAP_CONTENT, 46.dp))
            for ((label, act) in secondaries) {
                val s = pill(label, primary = false) { act() }
                secondary += s
                footer.addView(s, LinearLayout.LayoutParams(WRAP_CONTENT, 46.dp).apply { marginStart = 16.dp })
            }
            main = m
            return m
        }

        /** Quiet information at the end of the button row (it takes no height of its own). */
        fun footerNote(): TextView = k.label("", 15f, k.dim, k.font, lines = 0).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            footer.addView(this, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = 24.dp })
        }

        /** Keeps the note's line (one line of room) so a message arriving never pushes the body. */
        fun reserveNote() {
            note.visibility = View.INVISIBLE
            note.minHeight = (note.lineHeight)
            blockNote = note
        }

        /** The note's text, or the empty kept line for null. [quiet]: plain dim information instead of what's missing. */
        fun setNote(t: String?, quiet: Boolean = false) {
            setColorIfNew(note, if (quiet) k.dim else Ui.alpha(Color.WHITE, 0.78f))
            if (t == null) { note.visibility = View.INVISIBLE; return }
            setTextIfNew(note, t)
            note.visibility = View.VISIBLE
        }

        /** A full-column list in this step: the sentence, the note and the buttons step aside; the head stays. */
        fun listPage(on: Boolean) {
            subtitle.visibility = if (on || subtitle.text.isEmpty()) View.GONE else View.VISIBLE
            footer.visibility = if (on) View.GONE else View.VISIBLE
            if (note.visibility == View.VISIBLE && on) note.visibility = View.GONE
        }

        /** A sub-page has the column: the head, the sentence and the buttons step aside until it closes. */
        fun subPage(on: Boolean) {
            if (on == inSub) return
            inSub = on
            val v = if (on) View.GONE else View.VISIBLE
            head.visibility = v
            subtitle.visibility = if (on || subtitle.text.isEmpty()) View.GONE else View.VISIBLE
            footer.visibility = v
            if (on) { noteWas = note.visibility; note.visibility = View.GONE } else note.visibility = noteWas
            (body.layoutParams as LinearLayout.LayoutParams).topMargin = if (on) 0 else BODY_TOP.dp
            body.requestLayout()
        }
        private var inSub = false
        private var noteWas = View.GONE

        fun built(focus: View) = Built(root, focus, anims)
    }

    private fun column() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }

    private fun center(col: View) = FrameLayout(ctx).apply { addView(col, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER)) }

    private fun vBox() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    private fun hRow() = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }

    private fun lp(top: Int) = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = top.dp }

    private fun rowLp(gap: Boolean, gapDp: Int = 6) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (gap) topMargin = gapDp.dp }

    /** New content arriving in a step that's already on screen: a short, soft rise (no swap, no flash). */
    private fun reveal(views: List<View>) = Ui.rise(views, fromDp = 6, step = Ui.STAGGER, duration = Ui.PAGE)

    // Titles and explanations wrap in full (never "…") and break only between words.
    private fun titleText(t: String, center: Boolean) = k.label(t, if (center) Ui.T_DISPLAY else TITLE_SP, k.text, k.semibold, Ui.TRACK_TITLE, lines = 0).apply {
        hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        setLineSpacing(2.dp.toFloat(), 1f)
        if (center) gravity = Gravity.CENTER
    }

    private fun subText(t: String, center: Boolean) = k.label(t, 17f, k.dim, k.font, lines = 0).apply {
        setLineSpacing(5.dp.toFloat(), 1f)
        hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        if (center) { gravity = Gravity.CENTER; maxWidth = 660.dp }
        if (t.isEmpty()) visibility = View.GONE
    }

    // ---- controls ----

    /**
     * Setup's buttons in the Dream focus look: the primary is Play-type (pearl at rest, the fading rainbow
     * fill when focused), the others neutral glass that turn pearl with dark ink; both wear the rainbow
     * glow border while focused. Glow and fill share the capsule's radius (half its 46 dp height).
     */
    private fun pill(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        k.pill(label, null, primary, 46, dream = true) { onClick() }.apply { setPadding(28.dp, 0, 28.dp, 0) }

    private inner class Row(val view: LinearLayout, val title: TextView, val caption: TextView?, val value: TextView?, val sw: SetupSwitch?, val check: View?)

    /**
     * One option row, like Settings: optional icon disc, title (+ caption), then a value, a switch ([toggle]),
     * a check mark ([check]) or a chevron. Unfocusable when [focusable] is false (plain facts and instructions).
     */
    private fun row(
        title: String, caption: String? = null, icon: Int? = null, value: String? = null, toggle: Boolean? = null,
        chevron: Boolean = false, check: Boolean = false, focusable: Boolean = true, compact: Boolean = false,
        onClick: ((Row) -> Unit)? = null,
    ): Row {
        val focused = intArrayOf(android.R.attr.state_focused)
        fun states(on: Int, rest: Int) = ColorStateList(arrayOf(focused, IntArray(0)), intArrayOf(on, rest))
        val interactive = focusable && onClick != null
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (if (compact) 46 else 56).dp
            setPadding(12.dp, (if (compact) 4 else 8).dp, 18.dp, (if (compact) 4 else 8).dp)
            // A plain fact or instruction reads as text; only what can be chosen gets the glass of a control.
            isFocusable = interactive
            isClickable = interactive
        }
        // Focused: the pearl box with dark ink inside the rainbow glow border (PearlFocus), like every control.
        val glow = if (interactive) PearlFocus.apply(v, Ui.R_MD) else null
        val iconRest = Ui.alpha(k.text, 0.8f)
        val d: Drawable? = icon?.let { try { ctx.getDrawable(it)?.mutate()?.apply { setTint(iconRest) } } catch (e: Exception) { null } }
        if (d != null) v.addView(ImageView(ctx).apply {
            setImageDrawable(d)
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            isDuplicateParentStateEnabled = true
            background = StateListDrawable().apply {
                addState(focused, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.alpha(PearlFocus.INK, 0.08f)) })
                addState(IntArray(0), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.alpha(k.text, 0.07f)) })
            }
        }, LinearLayout.LayoutParams(34.dp, 34.dp).apply { marginEnd = 14.dp })
        else v.setPadding(20.dp, v.paddingTop, 18.dp, v.paddingBottom)
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // Setup text is never cut: titles and captions wrap in full, breaking only between words.
        val t = k.label(title, 16.5f, k.text, k.medium, lines = 0).apply {
            setTextColor(states(PearlFocus.INK, Ui.alpha(k.text, 0.94f)))
            isDuplicateParentStateEnabled = true
            setLineSpacing(2.dp.toFloat(), 1f)
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        }
        texts.addView(t)
        val cap = caption?.let {
            k.label(it, 13.5f, k.dim, k.font, lines = 0).apply {
                setTextColor(states(PearlFocus.INK_DIM, k.dim))
                isDuplicateParentStateEnabled = true
                setPadding(0, 3.dp, 0, 0)
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            }.also { c -> texts.addView(c) }
        }
        v.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val value2 = value?.let {
            k.label(it, 15f, k.dim, k.medium, lines = 0).apply {
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
        fun show() { r?.value?.text = "‹  ${label()}  ›" }
        r = row(title, caption, icon = icon, value = "") { change(1); show() }
        show()
        r.view.setOnKeyListener { _, code, e ->
            val by = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
            if (by != 0 && e.action == KeyEvent.ACTION_DOWN) { change(by); show() }
            by != 0
        }
        return r
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
     * Setup's sky: the brand's black falling into a dark gray, with a faint neutral light from the top left.
     * Fixed, not the theme's: setup is the first thing anyone sees.
     */
    private fun backdrop(): Drawable {
        val dm = ctx.resources.displayMetrics
        val sky = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.BLACK, Color.rgb(10, 10, 12), Color.rgb(24, 24, 28)))
        val wash = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dm.widthPixels * 0.6f
            setGradientCenter(0.3f, 0f)
            colors = intArrayOf(Color.argb(16, 255, 255, 255), Color.TRANSPARENT)
        }
        val layers = LayerDrawable(arrayOf(sky, wash))
        // Baked once into one half-size bitmap (soft gradients scale up cleanly): one full-screen pass per frame
        // instead of two gradient passes.
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


    companion object {
        private const val TAG = "FireHome"

        // Step ids (saved as the setup's place; the order comes from [CHAPTERS], not the numbers).
        private const val WELCOME = 0
        private const val PROFILE = 1
        private const val LOOK = 2
        private const val HOME = 3
        private const val CONTENT = 4
        private const val AIRPODS = 11
        private const val IPHONE = 12
        private const val VOICE = 13
        private const val MORE = 14
        private const val DONE = 15
        private const val ZONE = 16
        private const val ACCOUNTS = 17
        private const val SCRAPERS = 18
        private const val CHECK = 19
        private const val UNLOCK = 20
        private const val PLAYBACK = 22
        private const val WHO = 23
        private const val NEED = 24
        private const val TMDB = 25

        /**
         * The chapters, in order. Get Ready and Connect hold everything Dream OS needs to play (every account step
         * back to back, owner 2026-10-28); Your Fire TV and Make It Yours are the rest.
         */
        private val CHAPTERS = listOf(
            "Get Ready" to listOf(NEED, ZONE),
            "Connect" to listOf(TMDB, ACCOUNTS, SCRAPERS, CHECK),
            "Your Fire Stick" to listOf(UNLOCK, HOME, LOOK, VOICE, AIRPODS, IPHONE),
            "Make It Yours" to listOf(PROFILE, WHO, PLAYBACK, CONTENT, MORE),
        )

        private const val V_IDLE = 0
        private const val V_LISTENING = 1
        private const val V_HEARD = 2
        private const val V_MISSED = 3

        /** Design margins inside the Screen Fit inset (dp). */
        private const val SIDE = 48
        private const val TOP = 20
        private const val BOTTOM = 24
        /** The column's width (dp): wide enough for four tiles or a QR code beside its steps, narrow enough to read. */
        private const val COL_W = 800
        /** From the top of the content area to the step's head: the rail and the space under it (dp). */
        private const val RAIL_ROOM = 56
        /** From the sentence to the step's body (dp). */
        private const val BODY_TOP = 22
        /** A step's emblem (dp). */
        private const val EMBLEM = 52
        /** A step title (sp). */
        private const val TITLE_SP = 28f
        /** A QR code to scan with a phone (dp). */
        private const val QR_DP = 132

        /** Names for [Profiles.COLORS], in the same order. */
        private val COLOR_NAMES = listOf("Violet", "Sky Blue", "Pink", "Green", "Amber", "Coral", "Lavender", "Teal")

        private const val MAIN = "main"
        private val NAV_KEYS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
        private const val QR = "qr"
        private val GREEN = Color.parseColor("#30D158")
        private val GREEN_DEEP = Color.parseColor("#22A35A")
        private val WARN_C = Color.parseColor("#FFD60A")
        private val ERROR = Color.parseColor("#FF8178")
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
