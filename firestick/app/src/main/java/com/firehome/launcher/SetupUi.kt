package com.firehome.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Building blocks of Dream OS's setup and settings pages: rows in the setup wizard's style, status
 * marks, a calm progress bar, QR codes, and [SetupPanel], the stack of screens a page shows inside
 * the wizard's right-hand column (or the standalone setup). Colors from [PremiumKit] (the theme),
 * sizes from [Ui]. Nothing animates on its own except short entrances and focus moves.
 */
internal class SetupUi(val ctx: Context, val k: PremiumKit) {
    val Int.dp get() = Ui.dp(ctx, this)
    val main = Handler(Looper.getMainLooper())
    /** The setup wizard's stage: a sub-page has the whole column, so text entry puts its phone help beside the keyboard. */
    var roomy = false
    /** Dream Cinema: the brand (pearl face, dark ink, neutral marks); Premium keeps its highlight. */
    /** Ink on a focused row's face, and its quieter shade. */
    private val ink = PearlFocus.INK
    private val inkDim = PearlFocus.INK_DIM

    // ---- rows ----

    class Row(val view: LinearLayout, val title: TextView, val caption: TextView, val value: TextView, val mark: TextView) {
        var markState = Mark.NONE
    }

    /** NONE leaves no gap; OFF is a hollow ring (not on yet, keeps a list aligned); PICK is the chosen item's neutral check. */
    enum class Mark { NONE, OK, WARN, BUSY, OFF, PICK }

    /**
     * One row: a status mark (check, attention dot, number), title and caption, a value on the
     * right ("Turn On", "Up To Date") and an optional chevron. Focusable when [onClick] is set.
     */
    fun row(title: String, caption: String? = null, value: String? = null, mark: Mark = Mark.NONE, chevron: Boolean = false,
            onClick: (() -> Unit)? = null): Row {
        val focused = intArrayOf(android.R.attr.state_focused)
        fun states(on: Int, rest: Int) = ColorStateList(arrayOf(focused, IntArray(0)), intArrayOf(on, rest))
        val interactive = onClick != null
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 52.dp
            setPadding(14.dp, 7.dp, 18.dp, 7.dp)
            background = if (interactive) StateListDrawable().apply {
                addState(focused, Ui.focusFill(ctx, k.select, Ui.R_MD))
                addState(IntArray(0), Ui.rounded(ctx, Ui.alpha(k.text, 0.05f), Ui.R_MD))
                setEnterFadeDuration(Ui.FOCUS_IN.toInt())
                setExitFadeDuration(Ui.FOCUS_OUT.toInt())
            } else null // a plain fact reads as text, so only what can be chosen looks like a control
            isFocusable = interactive
            isClickable = interactive
            id = View.generateViewId()
        }
        val markView = k.label("", 12f, k.text, k.bold).apply {
            gravity = Gravity.CENTER
            isDuplicateParentStateEnabled = true
        }
        v.addView(markView, LinearLayout.LayoutParams(24.dp, 24.dp).apply { marginEnd = 14.dp })
        setMark(markView, mark)
        // Passes the row's focus down to the title and caption (ink on the focused fill).
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true }
        // Setup text is never cut: titles and captions wrap at word breaks (no hyphenation) instead of ending in "…".
        val t = k.label(title, 16f, k.text, k.medium, lines = 0).apply {
            setTextColor(states(ink, Ui.alpha(k.text, 0.94f)))
            isDuplicateParentStateEnabled = true
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        }
        texts.addView(t)
        val cap = k.label(caption ?: "", 12.5f, k.dim, k.font, lines = 0).apply {
            setTextColor(states(inkDim, k.dim))
            isDuplicateParentStateEnabled = true
            setPadding(0, 3.dp, 0, 0)
            setLineSpacing(2.dp.toFloat(), 1f)
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            visibility = if (caption.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        texts.addView(cap)
        v.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val valueView = k.label(value ?: "", Ui.T_LABEL, k.dim, k.semibold, lines = 0).apply {
            setTextColor(states(inkDim, Ui.alpha(k.text, 0.85f)))
            isDuplicateParentStateEnabled = true
            maxWidth = 190.dp
            gravity = Gravity.END
            visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        v.addView(valueView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 14.dp })
        if (chevron) v.addView(k.label("›", 24f, k.dim).apply {
            setTextColor(states(inkDim, Ui.alpha(k.text, 0.32f)))
            isDuplicateParentStateEnabled = true
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 12.dp })
        if (interactive) {
            v.setOnFocusChangeListener { view, has -> Ui.lift(view, has, Ui.LIFT_ROW) }
            cinemaRowFocus(v, Ui.R_MD, Ui.alpha(k.text, 0.05f)) // Dream Cinema: pearl face under the rainbow border
            v.setOnClickListener { try { onClick!!() } catch (e: Exception) { Log.w("FireHome", "Setup row $title", e) } }
        }
        v.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        return Row(v, t, cap, valueView, markView).also { it.markState = mark }
    }

    fun setMark(m: TextView, mark: Mark) {
        when (mark) {
            Mark.NONE -> { m.text = ""; m.background = null; m.visibility = View.GONE }
            // Drawn status marks (the setup rows' StatusGlyph), never a text "✓" / "!" / "•".
            Mark.OK, Mark.WARN, Mark.BUSY, Mark.OFF, Mark.PICK -> {
                m.visibility = View.VISIBLE; m.text = ""
                m.background = StatusGlyph(when (mark) {
                    Mark.OK -> BrandRow.Mark.OK; Mark.WARN -> BrandRow.Mark.WARN; Mark.OFF -> BrandRow.Mark.OFF
                    Mark.PICK -> BrandRow.Mark.PICK; else -> BrandRow.Mark.BUSY
                })
            }
        }
    }

    private fun oval(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }

    fun update(r: Row, caption: String? = null, value: String? = null, mark: Mark? = null) {
        // Only what changed, so an update never relayouts or flashes a row that stays the same.
        if (caption != null && r.caption.text.toString() != caption) { r.caption.text = caption; r.caption.visibility = if (caption.isEmpty()) View.GONE else View.VISIBLE }
        if (value != null && r.value.text.toString() != value) { r.value.text = value; r.value.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE }
        if (mark != null && mark != r.markState) { setMark(r.mark, mark); r.markState = mark }
    }

    // ---- text ----

    fun kicker(t: String) = k.kicker(t).apply { setPadding(6.dp, 0, 0, 0) }

    // In setup's stage a sub-page's heading is its page title (the step's own title steps aside): title-sized there.
    fun heading(t: String) = k.label(t, if (roomy) 26f else Ui.T_SECTION, k.text, k.semibold, lines = 0).apply {
        hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
    }

    fun note(t: String) = k.label(t, Ui.T_CAPTION, Ui.alpha(k.dim, 0.95f), k.font, lines = 0).apply {
        setLineSpacing(3.dp.toFloat(), 1f)
        setPadding(6.dp, 0, 6.dp, 0)
    }

    /** Numbered plain steps (Fire TV settings paths). */
    fun steps(lines: List<String>) = numberedSteps(ctx, k, lines, badgeDp = 24, size = Ui.T_CAPTION, gapDp = 10).apply {
        setPadding(10.dp, 4.dp, 6.dp, 4.dp)
    }

    /** A setup button ([PremiumKit.pill]); [heightDp] must be the height it is laid out at (its focus ring is half of it). */
    fun pill(label: String, primary: Boolean, heightDp: Int = 42, onClick: () -> Unit): TextView = k.pill(label, null, primary, heightDp) { onClick() }.apply {
        setPadding(24.dp, 0, 24.dp, 0)
        (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0
    }

    fun pillRow(vararg pills: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for ((i, p) in pills.withIndex()) addView(p, LinearLayout.LayoutParams(WRAP_CONTENT, 42.dp).apply { if (i > 0) marginStart = 12.dp })
    }

    // ---- progress ----

    /** A calm determinate bar (no idle motion); [set] eases to the new value. */
    inner class Progress : FrameLayout(ctx) {
        private val fill = View(ctx).apply {
            background = Ui.rounded(ctx, PearlFocus.PEARL, Ui.R_PILL)
            pivotX = 0f
        }
        private var value = 0f
        init {
            background = Ui.rounded(ctx, Ui.alpha(k.text, 0.10f), Ui.R_PILL)
            addView(fill, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            fill.scaleX = 0.02f
        }
        fun set(p: Float) {
            val to = p.coerceIn(0.02f, 1f)
            if (Math.abs(to - value) < 0.002f) return
            value = to
            fill.animate().scaleX(to).setDuration(240).setInterpolator(Ui.SETTLE).start()
        }
    }

    fun progress() = Progress()

    // ---- QR ----

    /** A crisp QR code for [text] (white quiet zone, black modules), or null if it doesn't fit. */
    fun qr(text: String, sizeDp: Int): ImageView? {
        val d = try { Qr.drawable(ctx, text, border = 2) } catch (e: Exception) { return null }
        return ImageView(ctx).apply {
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = Ui.rounded(ctx, Color.WHITE, Ui.R_SM)
            setPadding(6.dp, 6.dp, 6.dp, 6.dp)
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(sizeDp.dp, sizeDp.dp)
        }
    }
}

/**
 * One screen of a setup page: a heading line and a scrolling body, built once. Rows are keyed
 * ([row], [view]): updating a key changes that row's text and mark in place, so focus stays on the
 * same item and nothing is rebuilt. [focus] is where focus lands when it first shows; coming back to
 * it (Back) returns to the item that opened the next screen. [onShow] runs when it shows (also on
 * Back), [onResume] when Dream OS comes back from another app (Fire TV settings, the installer),
 * [onHide] when it's covered or closed.
 */
internal class SetupScreen(val ui: SetupUi, title: String?, val kickerText: String? = null) {
    private val ctx = ui.ctx
    private val Int.dp get() = Ui.dp(ctx, this)
    val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    // A soft fade where the list runs past the panel's edge, never a hard cut through a row: the page's own
    // background painted back over the edge (an offscreen fading edge cost this GPU a layer on every frame).
    val scroll = SmoothScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false; isFillViewport = false
        softEdgesOverPage(Ui.dp(ctx, 64))
    }
    var focus: (() -> View?) = { firstFocusable(body) }
    var onShow: () -> Unit = {}
    var onResume: () -> Unit = {}
    var onHide: () -> Unit = {}
    /** Back inside this screen (e.g. a running step to cancel first); true = handled. */
    var onBack: () -> Boolean = { false }
    @Volatile var alive = false
    /** The view that had focus when another screen was opened over this one. */
    var returnFocus: View? = null
    private var shownOnce = false

    private val rows = HashMap<String, SetupUi.Row>()
    private val views = HashMap<String, View>()
    private val clicks = HashMap<String, () -> Unit>()

    init {
        if (kickerText != null) root.addView(ui.kicker(kickerText), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = 6.dp })
        // The gap under the heading is larger than the list's lift room, so the list's fade never covers the heading.
        if (title != null) root.addView(ui.heading(title).apply { setPadding(6.dp, 0, 6.dp, 0) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = (LIFT_V + 6).dp })
        scroll.addView(body, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        // Room for a focused row's lift and its 12 dp halo on all four sides (6 dp cut the last pill's glow at the bottom).
        scroll.post { Ui.liftRoom(scroll, LIFT_H, LIFT_V) }
    }

    /** Adds [v] to the body, [top] dp below the previous view. */
    fun add(v: View, top: Int = 8): View {
        body.addView(v, (v.layoutParams as? LinearLayout.LayoutParams)?.apply { topMargin = if (body.childCount == 0) 0 else top.dp }
            ?: LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = if (body.childCount == 0) 0 else top.dp })
        if (shownOnce) {
            // Arrives after the screen showed: a short fade, never a pop.
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        }
        return v
    }

    /**
     * The row for [key]: created (and added at the end) the first time, updated in place after.
     * Keyed rows are always focusable, so focus never has to move when one changes; [onClick] null
     * makes OK do nothing. [static] rows (plain facts) never take focus.
     */
    fun row(key: String, title: String, caption: String? = null, value: String? = null, mark: SetupUi.Mark = SetupUi.Mark.NONE,
            chevron: Boolean = false, static: Boolean = false, top: Int = 8, onClick: (() -> Unit)? = null): SetupUi.Row {
        if (onClick != null) clicks[key] = onClick else clicks.remove(key)
        val r = rows[key]
        if (r != null) {
            if (r.title.text.toString() != title) r.title.text = title
            ui.update(r, caption ?: "", value ?: "", mark)
            if (r.view.visibility != View.VISIBLE) r.view.visibility = View.VISIBLE
            return r
        }
        // Only the screen on top acts (a press can't reach a screen that's fading away).
        val n = ui.row(title, caption, value, mark, chevron, if (static) null else ({ if (alive) clicks[key]?.invoke(); Unit }))
        n.view.tag = key
        rows[key] = n
        add(n.view, top)
        return n
    }

    /** A keyed view other than a row (notes, steps, buttons): created once by [make]. */
    fun view(key: String, top: Int = 8, make: () -> View): View {
        views[key]?.let { if (it.visibility != View.VISIBLE) it.visibility = View.VISIBLE; return it }
        val v = make()
        views[key] = v
        add(v, top)
        return v
    }

    fun has(key: String) = rows.containsKey(key) || views.containsKey(key)

    /** Empties the body (keyed rows included). */
    fun clear() { body.removeAllViews(); rows.clear(); views.clear(); clicks.clear() }

    /** Hides [key] (a row or view); it comes back with its next [row] / [view] call. Focus moves on first if it's there. */
    fun hide(key: String) {
        val v = rows[key]?.view ?: views[key] ?: return
        if (v.visibility == View.GONE) return
        if (v.hasFocus()) (v.focusSearch(View.FOCUS_DOWN) ?: v.focusSearch(View.FOCUS_UP))?.requestFocus()
        v.visibility = View.GONE
    }

    fun rowFor(key: String) = rows[key]

    /** Called by the panel when the screen shows. */
    fun shown() { onShow(); shownOnce = true }

    /** Runs [work] off the main thread, then [done] on it while the screen is still showing. */
    fun <T> bg(work: () -> T, done: (T) -> Unit) {
        Net.run {
            val r = try { work() } catch (e: Exception) { Log.w("FireHome", "Setup: $e"); null }
            ui.main.post { if (alive && r != null) done(r) }
        }
    }

    companion object {
        /** Lift room around the list (dp): the halo (12) plus a wide row's 2% lift. */
        const val LIFT_H = 24
        const val LIFT_V = 16

        fun firstFocusable(v: View): View? {
            if (v.isFocusable && v.visibility == View.VISIBLE) return v
            if (v is ViewGroup && v.visibility == View.VISIBLE) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
            return null
        }
    }
}

/**
 * The stack of [SetupScreen]s one setup page shows. Back pops one screen, focus returning to the
 * item that opened it; at the first screen Back is the host's (the wizard's previous step, or
 * closing the standalone setup). Screens cross-fade (Ui.SHEET_IN / SHEET_OUT, EASE / EXIT).
 */
internal class SetupPanel(val ui: SetupUi) : FrameLayout(ui.ctx) {
    private val stack = ArrayList<SetupScreen>()
    val top: SetupScreen? get() = stack.lastOrNull()
    val depth get() = stack.size
    /** Called after the stack changes (push, pop, replace) with the new depth. */
    var onDepth: ((Int) -> Unit)? = null
    private var lifecycle: android.app.Application.ActivityLifecycleCallbacks? = null

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val a = context as? android.app.Activity ?: return
        val cb = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) { if (activity === a) top?.let { if (it.alive) it.onResume() } }
            override fun onActivityCreated(activity: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        }
        a.application.registerActivityLifecycleCallbacks(cb)
        lifecycle = cb
    }

    override fun onDetachedFromWindow() {
        lifecycle?.let { (context as? android.app.Activity)?.application?.unregisterActivityLifecycleCallbacks(it) }
        lifecycle = null
        super.onDetachedFromWindow()
    }

    private fun fadeOut(v: View, then: () -> Unit) {
        v.animate().cancel()
        v.animate().alpha(0f).translationX(0f).setStartDelay(0).setDuration(Ui.SHEET_OUT).setInterpolator(Ui.EXIT).withEndAction(then).start()
    }

    private fun fadeIn(v: View, fromDp: Float) {
        v.animate().cancel()
        v.alpha = 0f
        v.translationX = Ui.dpf(context, fromDp)
        v.animate().alpha(1f).translationX(0f).setStartDelay(60).setDuration(Ui.SHEET_IN).setInterpolator(Ui.EASE).withEndAction(null).start()
    }

    /** Ignores a second open while one is still landing (a double press must not open the screen twice). */
    private var busyUntil = 0L

    private fun busy(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now < busyUntil) return true
        busyUntil = now + Ui.SHEET_IN
        return false
    }

    /**
     * A screen leaving or covered: nothing in it can take focus any more (its views stay in the
     * tree while they fade, right under the new screen, and the D-pad must never land there).
     */
    private fun block(s: SetupScreen) { s.root.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS }
    private fun unblock(s: SetupScreen) { s.root.descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS }

    /** Focus onto [s]: now, and once more after layout if it didn't land inside it. */
    private fun focusInto(s: SetupScreen, preferred: View? = null) {
        fun target() = preferred?.takeIf { it.isAttachedToWindow && it.visibility == View.VISIBLE && it.isFocusable } ?: s.focus()
        target()?.requestFocus()
        post {
            if (!s.alive) return@post
            val f = rootView?.findFocus()
            if (f == null || !isInside(f, s.root)) target()?.requestFocus()
        }
    }

    private fun isInside(v: View, parent: View): Boolean {
        var p: View? = v
        while (p != null) { if (p === parent) return true; p = p.parent as? View }
        return false
    }

    fun push(s: SetupScreen, animate: Boolean = stack.isNotEmpty()) {
        if (animate && busy()) return
        val prev = stack.lastOrNull()
        if (prev != null) {
            prev.returnFocus = prev.root.findFocus()
            prev.onHide(); prev.alive = false
            block(prev)
            val pv = prev.root
            if (animate) fadeOut(pv) { if (!prev.alive) pv.visibility = View.GONE } else pv.visibility = View.GONE
        }
        stack += s
        addView(s.root, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        unblock(s)
        s.alive = true
        if (animate) fadeIn(s.root, 22f)
        onDepth?.invoke(stack.size)
        s.shown()
        focusInto(s)
    }

    /** Back one screen; false at the first one. */
    fun pop(): Boolean {
        val s = stack.lastOrNull() ?: return false
        if (s.onBack()) return true
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        s.onHide(); s.alive = false
        block(s)
        val sv = s.root
        fadeOut(sv) { removeView(sv) }
        val prev = stack.last()
        prev.root.visibility = View.VISIBLE
        unblock(prev)
        prev.alive = true
        fadeIn(prev.root, -16f)
        onDepth?.invoke(stack.size)
        prev.shown()
        focusInto(prev, prev.returnFocus)
        return true
    }

    /** Replaces the top screen (a flow moving on: download → install → first start). */
    fun replace(s: SetupScreen) {
        val old = stack.removeLastOrNull()
        if (old != null) { old.onHide(); old.alive = false; block(old); val ov = old.root; fadeOut(ov) { removeView(ov) } }
        stack += s
        addView(s.root, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        unblock(s)
        s.alive = true
        fadeIn(s.root, 22f)
        onDepth?.invoke(stack.size)
        s.shown()
        focusInto(s)
    }

    /** Ends every screen. [keepViews]: they stay drawn (a page fading out takes them with it). */
    fun release(keepViews: Boolean = false) {
        for (s in stack) { s.onHide(); s.alive = false }
        stack.clear()
        if (!keepViews) removeAllViews()
    }
}

/**
 * Numbered steps, the one builder for setup and its Settings pages: each number on a Dream OS monogram disc, the
 * line beside it wrapping in full (setup text is never cut, STANDARDS §3) and centered on the disc when it fits.
 */
internal fun numberedStep(ctx: Context, k: PremiumKit, n: Int, text: CharSequence, badgeDp: Int = 30, size: Float = 17f) =
    LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        addView(SetupArt.monogram(ctx, "$n", badgeDp), LinearLayout.LayoutParams(Ui.dp(ctx, badgeDp), Ui.dp(ctx, badgeDp)))
        addView(k.label(text, size, k.text, k.font, lines = 0).apply {
            setLineSpacing(Ui.dp(ctx, 3).toFloat(), 1f)
            minHeight = Ui.dp(ctx, badgeDp)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(ctx, 14) })
    }

/** [lines] as [numberedStep]s, [gapDp] apart. */
internal fun numberedSteps(ctx: Context, k: PremiumKit, lines: List<CharSequence>, badgeDp: Int = 30, size: Float = 17f, gapDp: Int = 14) =
    LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        for ((i, l) in lines.withIndex()) addView(numberedStep(ctx, k, i + 1, l, badgeDp, size),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { if (i > 0) topMargin = Ui.dp(ctx, gapDp) })
    }
