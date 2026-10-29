package com.firehome.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * How Dream OS shows its notices (DreamNotices), in the Dream Cinema look:
 *  - a banner that slides in at the top right over the home screen: smoked glass, a small severity dot
 *    (amber for a warning, red for a critical one, soft white for news), the title and its line (either
 *    wraps rather than being cut); it rests ~6 s and leaves on its own, never taking focus;
 *  - the same banner with buttons ([showPrompt], the new-version notice: Install Now and Later): it takes
 *    focus and stays until the viewer picks one or presses Back;
 *  - the Notifications panel, sliding in from the right: rows (icon, title, body, "2 Days Ago"), the
 *    pearl-and-rainbow focus of every Dream row, Mark All Read / Clear All; OK runs a row's action,
 *    Menu removes the row, Back closes it;
 *  - the renewal sheet a premium-expiry notice opens: the service's renewal page as a QR code and as
 *    text, with the account's end date.
 * One per MainActivity; it is also the banner [DreamNotices.Presenter].
 */
class DreamNoticesUi(private val act: Activity, private val host: Host) : DreamNotices.Presenter {
    interface Host {
        /** Dream OS's own screen is in front and nothing full-screen covers it (Dream Player, the screensaver, the intro, setup). */
        fun homeFree(): Boolean
        fun setHomeFocusable(on: Boolean)
        /** The notification sound (Sounds.notice), when Settings has it on. */
        fun chime()
        /** MainActivity is resumed (banners only ever wait for it). */
        fun resumed(): Boolean
    }

    private val kit by lazy { CinemaKit(act) }
    private val handler = Handler(Looper.getMainLooper())
    private val density = act.resources.displayMetrics.density
    private val Int.dp get() = (this * density).toInt()
    private val decor get() = act.window.decorView as FrameLayout

    // ---- banner ----

    private var banner: View? = null
    private val queue = ArrayList<DreamNotices.Notice>()
    private var hideRun: Runnable? = null

    /** Not over the panel, the renewal sheet or Settings › Updates. */
    override fun canShowBanner() = host.homeFree() && panel == null && sheet == null && !UpdatePage.isOpen()

    override fun screenFreed() = onHomeBack()

    /** A banner is on screen right now (the new-version notice waits for it). */
    val bannerShowing get() = banner != null

    override fun showBanner(n: DreamNotices.Notice) {
        queue.add(n)
        if (banner == null) next()
    }

    private fun next() {
        val n = queue.removeFirstOrNull() ?: return
        if (!DreamNotices.stillNew(act, n.key) || !host.homeFree()) { DreamNotices.bannerDone(n.key); next(); return }
        DreamNotices.bannerDone(n.key) // a banner-only notice lives only as this banner
        val b = bannerView(n)
        val v = b.root
        slideIn(v)
        host.chime()
        // As it lands, the severity accent glows once; then the icon alternates between the emoji and a smiley.
        glow(b)
        startSwaps(b, n, emojiFor(n))
        hideRun = Runnable { hideBanner(animate = true) }.also { handler.postDelayed(it, BANNER_MS) }
    }

    /** Adds banner [v] at its place (top right, under the bar) and slides it in; it becomes [banner]. */
    private fun slideIn(v: View) {
        // A compact pill as wide as its words (up to BANNER_MAX_W): measured first, so the pivot is its right edge.
        v.measure(View.MeasureSpec.makeMeasureSpec(BANNER_MAX_W.dp, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val w = v.measuredWidth
        decor.addView(v, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = (CinemaStyle.BAR_H + 12).dp
            marginEnd = 44.dp
        })
        banner = v
        // In from the right on a soft spring, growing a touch (0.96 to 1) as it fades up, pivoted on its right
        // edge so it seems to come out of the screen's side. Transform and alpha only, on one cached layer.
        v.pivotX = w.toFloat()
        v.pivotY = 0f
        v.translationX = 96.dp.toFloat()
        v.scaleX = 0.96f
        v.scaleY = 0.96f
        v.alpha = 0f
        v.animate().translationX(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(560).setInterpolator(Ui.SPRING).withLayer().start()
    }

    /** The accent halo behind the icon glows once (as a banner lands, or as a live banner shows its result). */
    private fun glow(b: BannerViews, delay: Long = 260) {
        b.halo.animate().alpha(0.9f).scaleX(1.2f).scaleY(1.2f).setStartDelay(delay).setDuration(340).setInterpolator(Ui.EASE).withEndAction {
            b.halo.animate().alpha(0.35f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(700).setInterpolator(Ui.SETTLE).start()
        }.start()
    }

    /**
     * A live banner (DreamNotices.live): the same banner, place and motion, silent, the rainbow loader in the
     * icon slot while [title] is under way. [DreamNotices.Live.finish] turns it in place into the result: the
     * line in green (good, silent) or red (with the notification sound), the result's emoji popping in and,
     * as on every banner, alternating with a smiley; it then leaves like any banner. Banners that arrive
     * meanwhile wait behind it. Null when Home isn't free or another banner is showing.
     */
    override fun showLive(title: String): DreamNotices.Live? {
        if (banner != null || !canShowBanner()) return null
        val n = DreamNotices.Notice("live:${System.currentTimeMillis()}", DreamNotices.TYPE_INFO, title, "",
            DreamNotices.Severity.INFO, System.currentTimeMillis(), true, null)
        val b = bannerView(n)
        val v = b.root
        b.face.alpha = 0f
        val spin = LoadingCurtain.Spinner(act, Color.WHITE, Color.WHITE, 2.2f * density, rainbow = true)
        (b.face.parent as? FrameLayout)?.addView(spin, FrameLayout.LayoutParams(22.dp, 22.dp, Gravity.CENTER))
        slideIn(v)
        spin.start()
        return object : DreamNotices.Live {
            private var done = false
            override fun finish(title: String, good: Boolean, holdMs: Long, then: (() -> Unit)?) {
                if (done) return
                done = true
                if (banner !== v) { then?.invoke(); return } // went away with Home (pause): the next step decides again
                val color = if (good) LIVE_GOOD else LIVE_BAD
                spin.animate().alpha(0f).setDuration(180).withEndAction { spin.stop(); (spin.parent as? ViewGroup)?.removeView(spin) }.start()
                val result = if (good) "✅" else "✨"
                setFace(b.face, result)
                b.face.scaleX = 0.3f; b.face.scaleY = 0.3f; b.face.rotation = 40f
                b.face.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f).setStartDelay(120).setDuration(420)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2.6f)).start()
                b.title?.let { t ->
                    t.animate().alpha(0f).setDuration(140).withEndAction {
                        t.text = title
                        t.setTextColor(color)
                        t.animate().alpha(1f).setDuration(220).start()
                    }.start()
                }
                b.halo.background = oval(Ui.alpha(color, 0.34f))
                glow(b, delay = 120)
                if (!good) host.chime()
                startSwaps(b, n, result)
                hideRun?.let { handler.removeCallbacks(it) }
                hideRun = Runnable {
                    hideBanner(animate = true)
                    then?.let { handler.postDelayed(it, 450) }
                }.also { handler.postDelayed(it, holdMs) }
            }
            override fun dismiss(then: (() -> Unit)?) {
                if (done) return
                done = true
                spin.stop()
                if (banner === v) {
                    hideBanner(animate = true)
                    then?.let { handler.postDelayed(it, 450) }
                } else then?.invoke()
            }
        }
    }

    // ---- actionable banner (DreamNotices.prompt) ----

    /** The banner on screen has buttons and holds focus: what Back runs and where focus goes back to. */
    private class Prompt(val view: View, val onBack: () -> Unit, val back: View?)
    private var prompt: Prompt? = null

    /**
     * The standard banner (same disc, type, glass, place, motion and sound) with the notice's [choices] as
     * buttons under its line. Focus lands on the first choice and stays inside the banner (Home is locked
     * under it); a choice or Back ([handleBack]) takes the banner away, gives focus back, then runs. It
     * stays until the viewer picks (no timer), and its icon swaps a few times, then rests on the smiley.
     */
    override fun showPrompt(n: DreamNotices.Notice, choices: List<DreamNotices.Choice>, sound: Boolean, onBack: () -> Unit): Boolean {
        if (banner != null || prompt != null || !canShowBanner() || choices.isEmpty()) return false
        val back = act.currentFocus
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val b = bannerView(n, row)
        val v = b.root
        for (c in choices) row.addView(kit.button(c.label, null, primary = c.primary) {
            if (prompt?.view !== v) return@button
            hideBanner(animate = true) // gives focus back first, so a page the choice opens returns there
            c.run()
        })
        (row.getChildAt(row.childCount - 1).layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        prompt = Prompt(v, onBack, back)
        host.setHomeFocusable(false)
        slideIn(v)
        row.getChildAt(0).requestFocus()
        if (sound) host.chime()
        glow(b)
        startSwaps(b, n, emojiFor(n), max = PROMPT_SWAPS)
        return true
    }

    private var swapRun: Runnable? = null

    /**
     * Like an Xbox 360 notification: every [SWAP_MS] while banner [b] is on screen, its icon morphs between the
     * notice's own [emoji] and the smiley, back and forth. A timer and one short animation per swap, nothing
     * per frame in between; it stops when the banner leaves ([stopSwaps] from [hideBanner]), or after [max]
     * swaps (an odd count, so it rests on the smiley) on a banner that stays until the viewer acts.
     */
    private fun startSwaps(b: BannerViews, n: DreamNotices.Notice, emoji: String, max: Int = Int.MAX_VALUE) {
        stopSwaps()
        val smile = if (n.severity == DreamNotices.Severity.CRITICAL) GENTLE_SMILE else SMILE
        swapSmile = smile
        swapFace = b.face
        smiling = false
        var left = max
        swapRun = object : Runnable {
            override fun run() {
                if (banner !== b.root) { if (swapRun === this) swapRun = null; return }
                smiling = !smiling
                morph(b.face, if (smiling) smile else emoji)
                if (--left > 0) handler.postDelayed(this, SWAP_MS) else if (swapRun === this) swapRun = null
            }
        }.also { handler.postDelayed(it, SWAP_MS) }
    }

    // The swapping face: whether the smiley is up, so a leaving banner can end on it.
    private var smiling = false
    private var swapSmile: String? = null
    private var swapFace: TextView? = null

    private fun stopSwaps() {
        swapRun?.let { handler.removeCallbacks(it) }
        swapRun = null
    }

    /** The swap always ends on the smiley: if the other icon is up as the banner leaves, one last morph first. True if it morphed. */
    private fun endOnSmile(): Boolean {
        val face = swapFace ?: return false
        val s = swapSmile ?: return false
        swapFace = null
        if (smiling) return false
        smiling = true
        morph(face, s)
        return true
    }

    /** The icon spins down and out; [to] pops in with a little spring bounce. */
    private fun morph(face: TextView, to: String) {
        face.animate().scaleX(0.3f).scaleY(0.3f).rotation(-110f).alpha(0f).setStartDelay(0).setDuration(190).setInterpolator(Ui.EXIT).withEndAction {
            setFace(face, to)
            face.rotation = 40f
            face.animate().scaleX(1f).scaleY(1f).rotation(0f).alpha(1f).setDuration(420)
                .setInterpolator(android.view.animation.OvershootInterpolator(2.6f)).start()
        }.start()
    }

    /** Takes the banner away ([animate] off: at once, e.g. Home is going away); the next waiting one follows. */
    fun hideBanner(animate: Boolean) {
        hideRun?.let { handler.removeCallbacks(it) }
        hideRun = null
        stopSwaps()
        val v = banner ?: return
        banner = null
        val p = prompt
        if (p != null && p.view === v) {
            // An actionable banner: its buttons let go of focus, Home takes it back where it was.
            prompt = null
            (v as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            host.setHomeFocusable(true)
            if (p.back != null && p.back.isAttachedToWindow) p.back.requestFocus()
        }
        if (!animate) {
            swapFace = null
            v.animate().cancel()
            (v.parent as? ViewGroup)?.removeView(v)
            return
        }
        // It ends on the smiley: one last morph (~0.6 s), then the smiley rests a beat before the banner leaves.
        val lastMorph = if (endOnSmile()) 1_400L else 0L
        // Out the way it came: a short slide right and a fade, a hair smaller.
        v.animate().translationX(64.dp.toFloat()).scaleX(0.98f).scaleY(0.98f).alpha(0f).setStartDelay(lastMorph).setDuration(320).setInterpolator(Ui.EXIT).withLayer().withEndAction {
            (v.parent as? ViewGroup)?.removeView(v)
            if (queue.isNotEmpty()) handler.postDelayed({ if (banner == null && canShowBanner()) next() }, 420)
        }.start()
    }

    /** Home is going away (pause): the banner goes at once; anything still queued waits for Home. */
    fun onPause() {
        handler.removeCallbacks(flushRun)
        hideBanner(animate = false)
    }

    /** Home is back (resume, the screensaver closed): banners that waited slide in after a moment. */
    fun onHomeBack() {
        flushTries = 0
        handler.removeCallbacks(flushRun)
        handler.postDelayed(flushRun, 1_400)
    }

    private var flushTries = 0

    private val flushRun: Runnable = object : Runnable {
        override fun run() {
            // The intro, setup or the profile picker is still up: look again shortly (for a couple of minutes).
            if (!canShowBanner()) { if (++flushTries < 60 && host.resumed()) handler.postDelayed(this, 2_000); return }
            if (banner == null && queue.isNotEmpty()) next()
            DreamNotices.flush()
        }
    }

    private class BannerViews(val root: View, val face: TextView, val halo: View, val title: TextView? = null)

    /** The startup summary ("Name: value" lines). */
    private fun isSummary(n: DreamNotices.Notice) = n.type == DreamNotices.TYPE_SUMMARY && n.body.contains(": ")

    /**
     * "Name: value" lines as two aligned columns: the names with their colons in white on the left, each
     * value right after in its status color (like the AirPods chip). Up to [MAX_SUMMARY_LINES] lines.
     * [ink] switches both columns to dark ink for the pearl focus face and back.
     */
    private inner class SummaryGrid(val view: LinearLayout, private val rows: List<Triple<TextView, TextView, Int>>) {
        fun ink(on: Boolean) {
            for ((name, value, color) in rows) {
                name.setTextColor(if (on) PearlFocus.INK else Color.WHITE)
                value.setTextColor(if (on) PearlFocus.INK else color)
            }
        }
    }

    private fun summaryGrid(body: String, size: Float): SummaryGrid {
        val names = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val values = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val rows = ArrayList<Triple<TextView, TextView, Int>>()
        for ((i, line) in body.split('\n').filter { it.isNotBlank() }.take(MAX_SUMMARY_LINES).withIndex()) {
            val name = line.substringBefore(": ", line).trim()
            val value = line.substringAfter(": ", "").trim()
            val color = valueColor(value) ?: DETAIL
            val nameView = kit.label("$name:", size, Color.WHITE, kit.medium)
            val valueView = kit.label(value, size, color, kit.semibold)
            val lp = { LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (i > 0) topMargin = 4.dp } }
            names.addView(nameView, lp())
            values.addView(valueView, lp())
            rows += Triple(nameView, valueView, color)
        }
        val grid = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(names, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(values, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 10.dp })
        }
        return SummaryGrid(grid, rows)
    }

    /** A banner line that wraps in full (never cut with "…"), whole words only. */
    private fun bannerLine(t: CharSequence, size: Float, color: Int, face: android.graphics.Typeface) =
        kit.label(t, size, color, face, lines = 0).apply {
            maxWidth = (BANNER_MAX_W - 90).dp
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            breakStrategy = android.text.Layout.BREAK_STRATEGY_BALANCED // two even lines, never one word left alone
            setLineSpacing(2 * density, 1f)
        }

    /** [actions]: an actionable banner's buttons, under its line ([showPrompt]). */
    private fun bannerView(n: DreamNotices.Notice, actions: View? = null): BannerViews {
        val accent = severityColor(n.severity)
        // The leading icon: the type's emoji in a small dark disc, the severity's soft halo behind it.
        val halo = View(act).apply { background = oval(Ui.alpha(accent, 0.34f)); alpha = 0.35f }
        val face = TextView(act).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            textSize = 16f
            setTextColor(Color.WHITE)
        }
        setFace(face, emojiFor(n))
        val icon = FrameLayout(act).apply {
            clipChildren = false
            addView(halo, FrameLayout.LayoutParams(36.dp, 36.dp, Gravity.CENTER))
            addView(View(act).apply { background = oval(0xFF26262C.toInt()) }, FrameLayout.LayoutParams(30.dp, 30.dp, Gravity.CENTER))
            addView(face, FrameLayout.LayoutParams(30.dp, 30.dp, Gravity.CENTER))
        }
        // One bold line and one short detail line, both near-white; the values in them colored by band. A longer
        // line wraps to the next one, never cut.
        val titleView = bannerLine(colorize(n.title), 16.5f, Color.WHITE, kit.semibold)
        val texts = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(titleView)
            val grid = if (isSummary(n)) summaryGrid(n.body, 14f) else null
            if (grid != null) addView(grid.view, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 6.dp })
            else if (n.body.isNotBlank()) addView(bannerLine(colorize(n.body), 13.5f, DETAIL, kit.medium),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 4.dp })
            // Buttons sit under the words, their focus glow and lift inside the banner's padding.
            if (actions != null) addView(actions, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        }
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            // With buttons, the icon stays beside the title instead of floating to the middle of the stack.
            gravity = if (actions != null) Gravity.TOP else Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            isFocusable = false
            descendantFocusability = if (actions != null) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
            if (actions != null) setPadding(10.dp, 10.dp, 22.dp, 16.dp) else setPadding(10.dp, 9.dp, 20.dp, 9.dp)
            background = Ui.rounded(act, SMOKE, 16, 1f, Ui.alpha(Color.WHITE, 0.10f))
            elevation = 16.dp.toFloat()
            addView(icon, LinearLayout.LayoutParams(36.dp, 36.dp))
            addView(texts, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 12.dp })
        }
        return BannerViews(root, face, halo, titleView)
    }

    private fun emojiFor(n: DreamNotices.Notice) = when {
        n.type == DreamNotices.TYPE_SUMMARY -> "🗓️"                                         // calendar
        n.severity == DreamNotices.Severity.CRITICAL || n.type == DreamNotices.TYPE_LIMIT -> "⚠️" // warning sign
        n.type == DreamNotices.TYPE_EXPIRY -> "⏳"                                                     // hourglass
        n.type == DreamNotices.TYPE_EPISODES -> "📺"                                             // television
        n.type == DreamNotices.TYPE_UPDATE -> "✨"                                                // sparkles
        else -> "🔔"                                                                             // bell
    }

    /** Shows [emoji] in [v], or (no glyph for it on this device) a small drawn face instead. */
    private fun setFace(v: TextView, emoji: String) {
        if (hasGlyph(v, emoji)) { v.text = emoji; v.background = null; return }
        v.text = ""
        v.background = FaceDrawable(act, smile = emoji == SMILE || emoji == GENTLE_SMILE)
    }

    private fun hasGlyph(v: TextView, s: String): Boolean = try { v.paint.hasGlyph(s) } catch (e: Exception) { true }

    // ---- panel ----

    private var panel: FrameLayout? = null
    private var panelCard: LinearLayout? = null
    private var list: LinearLayout? = null
    private var countLine: TextView? = null
    private var panelReturn: View? = null
    private val listener: () -> Unit = { if (panel != null) fillList(keepFocus = true) }

    /** Opens the Notifications panel ([from]: what gets focus back when it closes; else whatever had it). */
    fun openPanel(from: View? = act.currentFocus) {
        if (panel != null) return
        hideBanner(animate = true)
        panelReturn = from
        val scrim = FrameLayout(act).apply {
            isClickable = true
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            alpha = 0f
        }
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(38.dp, 40.dp, 38.dp, 0)
            background = LayerDrawable(arrayOf(
                GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF7141418.toInt(), 0xF70C0C0E.toInt())),
            ))
            elevation = 24.dp.toFloat()
        }
        val title = kit.label("Notifications", 26f, Color.WHITE, kit.bold)
        val count = kit.label("", 14f, Ui.alpha(Color.WHITE, 0.62f))
        countLine = count
        card.addView(title)
        card.addView(count, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 8.dp })
        val actions = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(kit.chip("Mark All Read") { DreamNotices.markAllRead(act) })
            addView(kit.chip("Clear All") { DreamNotices.clearAll(act) })
        }
        card.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 20.dp })
        val rows = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(6.dp, 14.dp, 6.dp, 40.dp)
        }
        list = rows
        val scroll = ScrollView(act).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isSmoothScrollingEnabled = true
            addView(rows, MATCH_PARENT, WRAP_CONTENT)
        }
        card.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = 10.dp; marginStart = (-6).dp; marginEnd = (-6).dp })
        // A hairline down the panel's left edge separates it from the page behind.
        val edge = View(act).apply { setBackgroundColor(Ui.alpha(Color.WHITE, 0.08f)) }
        val holder = FrameLayout(act).apply {
            addView(card, MATCH_PARENT, MATCH_PARENT)
            addView(edge, FrameLayout.LayoutParams(1.dp.coerceAtLeast(1), MATCH_PARENT, Gravity.START))
        }
        scrim.addView(holder, FrameLayout.LayoutParams(PANEL_W.dp, MATCH_PARENT, Gravity.END))
        decor.addView(scrim, MATCH_PARENT, MATCH_PARENT)
        panel = scrim
        panelCard = card
        host.setHomeFocusable(false)
        DreamNotices.addListener(listener)
        fillList(keepFocus = false)
        scrim.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        holder.translationX = PANEL_W.dp.toFloat()
        holder.animate().translationX(0f).setDuration(380).setInterpolator(Ui.SETTLE).start()
        scrim.post { focusFirstRow() ?: actions.getChildAt(0)?.requestFocus() }
    }

    private fun focusFirstRow(): Boolean? = list?.let { l ->
        (0 until l.childCount).map { l.getChildAt(it) }.firstNotNullOfOrNull { SetupScreen.firstFocusable(it) }?.requestFocus()
    }?.takeIf { it }

    fun closePanel() {
        val p = panel ?: return
        panel = null
        panelCard = null
        list = null
        DreamNotices.removeListener(listener)
        host.setHomeFocusable(true)
        val holder = p.getChildAt(0)
        holder.animate().translationX(PANEL_W.dp.toFloat()).setDuration(260).setInterpolator(Ui.EXIT).start()
        p.animate().alpha(0f).setDuration(260).withEndAction { (p.parent as? ViewGroup)?.removeView(p) }.start()
        val back = panelReturn
        panelReturn = null
        if (back != null && back.isAttachedToWindow) back.requestFocus()
        onHomeBack() // notices that came in while the panel was open
    }

    /** Back: the renewal sheet first, then the panel. True when it was ours. */
    fun handleBack(): Boolean {
        prompt?.let { p -> hideBanner(animate = true); p.onBack(); return true }
        if (sheet != null) { closeSheet(); return true }
        if (panel != null) { closePanel(); return true }
        return false
    }

    private fun fillList(keepFocus: Boolean) {
        val rows = list ?: return
        val items = DreamNotices.all(act)
        val unread = items.count { !it.read }
        countLine?.text = when {
            items.isEmpty() -> "Nothing New"
            unread == 0 -> "All Caught Up"
            unread == 1 -> "1 Unread"
            else -> "$unread Unread"
        }
        val focusedKey = if (keepFocus) (act.currentFocus?.tag as? String) else null
        val focusedIndex = if (keepFocus) (0 until rows.childCount).indexOfFirst { rows.getChildAt(it).hasFocus() } else -1
        val hadFocusInList = focusedIndex >= 0
        rows.removeAllViews()
        if (items.isEmpty()) {
            rows.addView(kit.empty(LineIcon.BELL, "You're All Caught Up", "Account alerts and other news from Dream OS show up here."),
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 40.dp })
            if (hadFocusInList || act.currentFocus?.isAttachedToWindow != true) {
                (panelCard?.getChildAt(2) as? ViewGroup)?.getChildAt(0)?.requestFocus()
            }
            return
        }
        for ((i, n) in items.withIndex()) {
            rows.addView(if (n.type == DreamNotices.TYPE_UPDATE && n.action?.target == DreamUpdate.TARGET) updateRow(n) else row(n), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (i > 0) topMargin = 10.dp })
        }
        if (hadFocusInList) {
            val again = (0 until rows.childCount).map { rows.getChildAt(it) }.firstOrNull { it.tag == focusedKey }
                ?: rows.getChildAt(focusedIndex.coerceAtMost(rows.childCount - 1))
            again?.requestFocus()
        }
    }

    private fun row(n: DreamNotices.Notice): View {
        val accent = severityColor(n.severity)
        val icon = ImageView(act).apply {
            setImageDrawable(SettingsGlyph.of(iconFor(n)).apply { setTint(if (n.severity == DreamNotices.Severity.INFO) Ui.alpha(Color.WHITE, 0.9f) else accent) })
            val p = 10.dp
            setPadding(p, p, p, p)
            background = oval(Ui.alpha(if (n.severity == DreamNotices.Severity.INFO) Color.WHITE else accent, 0.14f))
        }
        val titleColor = if (n.read) Ui.alpha(Color.WHITE, 0.86f) else Color.WHITE
        val titleRest = colorize(n.title)
        val bodyRest = colorize(n.body)
        val title = kit.label(titleRest, 16f, titleColor, if (n.read) kit.medium else kit.semibold)
        val body = kit.label(bodyRest, 14f, DETAIL, lines = 3).apply { setLineSpacing(0f, 1.14f) }
        val grid = if (isSummary(n)) summaryGrid(n.body, 14.5f) else null
        val meta = kit.label(metaLine(n), 12.5f, Ui.alpha(Color.WHITE, 0.50f), kit.medium)
        val unreadDot = View(act).apply { background = oval(PearlFocus.PEARL); visibility = if (n.read) View.GONE else View.VISIBLE }
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(unreadDot, LinearLayout.LayoutParams(8.dp, 8.dp).apply { marginStart = 12.dp })
        }
        val texts = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(head, MATCH_PARENT, WRAP_CONTENT)
            if (grid != null) addView(grid.view, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 8.dp })
            else addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
            addView(meta, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
        }
        val r = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(16.dp, 16.dp, 18.dp, 16.dp)
            isFocusable = true
            isClickable = true
            tag = n.key
            id = View.generateViewId()
            addView(icon, LinearLayout.LayoutParams(40.dp, 40.dp))
            addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = 16.dp })
        }
        val focus = CinemaRowFocus(r, Ui.R_MD, Ui.alpha(Color.WHITE, 0.045f))
        r.setOnFocusChangeListener { v, has ->
            focus.focus(has)
            // On the pearl face the values go plain ink (the band colors are for the dark glass).
            title.text = if (has) n.title else titleRest
            body.text = if (has) n.body else bodyRest
            title.setTextColor(if (has) PearlFocus.INK else titleColor)
            body.setTextColor(if (has) PearlFocus.INK_DIM else DETAIL)
            grid?.ink(has)
            meta.setTextColor(if (has) PearlFocus.INK_DIM else Ui.alpha(Color.WHITE, 0.50f))
            unreadDot.background = oval(if (has) PearlFocus.INK else PearlFocus.PEARL)
            Ui.lift(v, has, Ui.LIFT_ROW)
        }
        r.setOnClickListener {
            DreamNotices.markRead(act, n.key)
            n.action?.let { run(it.target, n) }
        }
        r.setOnKeyListener { _, code, e ->
            if (code == KeyEvent.KEYCODE_MENU && e.action == KeyEvent.ACTION_UP) { DreamNotices.dismiss(act, n.key); true }
            else code == KeyEvent.KEYCODE_MENU
        }
        return r
    }

    /**
     * A new Dream OS version (DreamUpdate): the notice ("There's a New Version Available", "Dream OS 4.1")
     * with its three actions right on it: Install Now (the rainbow), View Changes and Later (not on Home again
     * until tomorrow; the notice stays here). Menu on any of them removes the notice.
     */
    private fun updateRow(n: DreamNotices.Notice): View {
        val version = n.key.removePrefix(DreamUpdate.NOTICE_PREFIX)
        val icon = ImageView(act).apply {
            setImageDrawable(SettingsGlyph.of(SettingsGlyph.SPARKLE).apply { setTint(Ui.alpha(Color.WHITE, 0.9f)) })
            val p = 10.dp
            setPadding(p, p, p, p)
            background = oval(Ui.alpha(Color.WHITE, 0.14f))
        }
        val unreadDot = View(act).apply { background = oval(PearlFocus.PEARL); visibility = if (n.read) View.GONE else View.VISIBLE }
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(kit.label(n.title, 16f, if (n.read) Ui.alpha(Color.WHITE, 0.86f) else Color.WHITE, if (n.read) kit.medium else kit.semibold, lines = 2),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(unreadDot, LinearLayout.LayoutParams(8.dp, 8.dp).apply { marginStart = 12.dp })
        }
        fun acted(go: () -> Unit) { DreamNotices.markRead(act, n.key); go() }
        val release = DreamUpdate.available(act)?.takeIf { it.version == version }
        val buttons = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(kit.button("Install Now", null, primary = true) { acted { closePanel(); UpdatePage.open(act, UpdatePage.INSTALL) } })
            addView(kit.button("View Changes", null, primary = false) { acted { closePanel(); UpdatePage.open(act, UpdatePage.CHANGES) } })
            addView(kit.button("Later", null, primary = false) { acted { release?.let { DreamUpdate.snooze(act, it) }; closePanel() } })
            for (i in 0 until childCount) getChildAt(i).setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_MENU && e.action == KeyEvent.ACTION_UP) { DreamNotices.dismiss(act, n.key); true }
                else code == KeyEvent.KEYCODE_MENU
            }
        }
        val texts = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(head, MATCH_PARENT, WRAP_CONTENT)
            addView(kit.label(n.body, 14f, DETAIL), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 6.dp })
            addView(kit.label(ago(n.at), 12.5f, Ui.alpha(Color.WHITE, 0.50f), kit.medium), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
            addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        }
        return LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
            setPadding(16.dp, 16.dp, 18.dp, 18.dp)
            background = Ui.rounded(act, Ui.alpha(Color.WHITE, 0.045f), Ui.R_MD)
            tag = n.key
            addView(icon, LinearLayout.LayoutParams(40.dp, 40.dp))
            addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = 16.dp })
        }
    }

    private fun metaLine(n: DreamNotices.Notice): String {
        val parts = arrayListOf(ago(n.at))
        n.action?.let { parts += it.label }
        return parts.joinToString("   ·   ")
    }

    private fun iconFor(n: DreamNotices.Notice) = when (n.type) {
        DreamNotices.TYPE_EXPIRY -> LineIcon.CLOCK
        DreamNotices.TYPE_LIMIT -> LineIcon.GAUGE
        DreamNotices.TYPE_EPISODES -> LineIcon.TV
        DreamNotices.TYPE_UPDATE -> SettingsGlyph.SPARKLE
        else -> LineIcon.BELL
    }

    // ---- actions ----

    /** Runs a notice's action target (see DreamNotices). */
    fun run(target: String, n: DreamNotices.Notice? = null) {
        when {
            target.startsWith("renew:") -> openRenewSheet(target.removePrefix("renew:"), n)
            target == "accounts" -> { closePanel(); DreamSettingsPage.open(act, DreamSettingsPage.ACCOUNTS) }
            target == DreamUpdate.TARGET -> { closePanel(); UpdatePage.open(act, UpdatePage.INSTALL) }
        }
    }

    private var sheet: FrameLayout? = null
    private var sheetReturn: View? = null
    private var sheetKey: String? = null

    /** The service's renewal page as a QR code and as text, with its end date. */
    fun openRenewSheet(service: String, n: DreamNotices.Notice? = null) {
        if (sheet != null) return
        val name = DreamNotices.serviceName(service)
        val url = DebridExpiry.renewUrl(service)
        sheetReturn = act.currentFocus
        sheetKey = n?.key
        val qr = ImageView(act).apply {
            setImageDrawable(Qr.drawable(act, url))
            scaleType = ImageView.ScaleType.FIT_CENTER
            val p = 14.dp
            setPadding(p, p, p, p)
            background = Ui.rounded(act, Color.WHITE, 16)
        }
        val status = DebridExpiry.caption(act, service)
        val statusColor = if (n != null && n.severity != DreamNotices.Severity.INFO) severityColor(n.severity) else Ui.alpha(Color.WHITE, 0.8f)
        val texts = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(kit.kicker("Premium"))
            addView(kit.label("Renew $name", 28f, Color.WHITE, kit.bold), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 10.dp })
            if (status != null) addView(kit.label(status, 15f, statusColor, kit.semibold),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 10.dp })
            addView(kit.label("Scan the code with your phone to open $name's renewal page.", 15f, Ui.alpha(Color.WHITE, 0.68f), lines = 3).apply { setLineSpacing(0f, 1.18f) },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 16.dp })
            addView(kit.label(url.removePrefix("https://").removeSuffix("/"), 17f, Color.WHITE, kit.semibold),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 14.dp })
            val done = kit.button("Done", null, primary = false) { closeSheet() }
            val buttons = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
                addView(done)
                if (n != null) addView(kit.button("Remove Notice", null, primary = false) { DreamNotices.dismiss(act, n.key); closeSheet() })
            }
            addView(buttons, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 26.dp })
        }
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(36.dp, 36.dp, 40.dp, 36.dp)
            background = Ui.rounded(act, 0xFA141418.toInt(), 24, 1f, Ui.alpha(Color.WHITE, 0.09f))
            elevation = 28.dp.toFloat()
            addView(qr, LinearLayout.LayoutParams(224.dp, 224.dp))
            addView(texts, LinearLayout.LayoutParams(400.dp, WRAP_CONTENT).apply { marginStart = 36.dp })
        }
        val overlay = FrameLayout(act).apply {
            isClickable = true
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            alpha = 0f
            addView(card, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        }
        decor.addView(overlay, MATCH_PARENT, MATCH_PARENT)
        sheet = overlay
        if (panel == null) host.setHomeFocusable(false)
        else panel?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        overlay.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        card.translationY = 22.dp.toFloat()
        card.animate().translationY(0f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.SETTLE).start()
        overlay.post { ((texts.getChildAt(texts.childCount - 1) as ViewGroup).getChildAt(0)).requestFocus() }
    }

    private fun closeSheet() {
        val s = sheet ?: return
        sheet = null
        s.animate().alpha(0f).setDuration(Ui.OVERLAY_OUT).withEndAction { (s.parent as? ViewGroup)?.removeView(s) }.start()
        val p = panel
        if (p != null) p.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS else host.setHomeFocusable(true)
        val back = sheetReturn
        sheetReturn = null
        // Marking the notice read rebuilt the list under the sheet: the same notice's new row, by its key.
        val again = sheetKey?.let { k -> list?.findViewWithTag<View>(k) }
        sheetKey = null
        if (back == null || !back.isAttachedToWindow || !back.requestFocus()) {
            if (again?.requestFocus() != true && p != null) focusFirstRow()
        }
        if (p == null) onHomeBack()
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        DreamNotices.removeListener(listener)
        hideBanner(animate = false)
    }

    // ---- helpers ----

    private fun oval(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }

    companion object {
        /** The banner grows with its words up to this width (dp). */
        private const val BANNER_MAX_W = 460
        /** Detail lines: near-white for reading from the couch. */
        private val DETAIL = Ui.alpha(Color.WHITE, 0.86f)
        /** A banner's icon swaps between its emoji and the smiley this often (each swap a ~0.6 s morph). */
        private const val SWAP_MS = 2_000L
        const val SMILE = "🙂"        // slightly smiling face
        const val GENTLE_SMILE = "😊" // smiling face with smiling eyes (after a critical notice)
        private const val BANNER_MS = 6_000L
        /** Icon swaps on a banner with buttons before it rests on the smiley (odd: it ends smiling). */
        private const val PROMPT_SWAPS = 3
        private const val PANEL_W = 520
        /** Dark smoked glass. */
        private const val SMOKE = 0xF0141417.toInt()
        /** A live banner's result line: legible tints of the system green (up to date) and red (an update waits). */
        private val LIVE_GOOD = Color.parseColor("#6BEB8F")
        private val LIVE_BAD = Color.parseColor("#FF8178")
        // The status hues of the AirPods chip and Settings' Streaming Accounts values (AccountHealth).
        const val GREEN = 0xFF4ADE80.toInt()
        const val AMBER = 0xFFFBBF24.toInt()
        const val ORANGE = 0xFFFB923C.toInt()
        const val RED = 0xFFF87171.toInt()
        private val VALUES = Regex("(\\d+) Days?\\b|(\\d+) Years?\\b|\\d+ Months?\\b|Ends Today|Ends Tomorrow|Expired|No Premium Plan|(?:Magnet |Download )?Limit Reached|Renews Automatically")

        /** Lines the startup summary shows at most (one per linked service). */
        private const val MAX_SUMMARY_LINES = 4
        /** "Renews Automatically": a softer green than a long count. */
        const val SOFT_GREEN = 0xFF86EFAC.toInt()

        /** A whole value's status color ("76 Days", "Expired", "Renews Automatically", "Active"); null when it has none. */
        fun valueColor(value: String): Int? = when {
            value == "Active" -> GREEN
            else -> VALUES.find(value)?.let { m -> colorOf(m) }
        }

        private fun colorOf(m: MatchResult): Int {
            val v = m.value
            return when {
                v == "Expired" || v == "No Premium Plan" -> RED
                v == "Renews Automatically" -> SOFT_GREEN
                v.endsWith("Limit Reached") -> AMBER
                v.startsWith("Ends") -> ORANGE
                m.groupValues[1].isNotEmpty() -> dayColor(m.groupValues[1].toInt())
                else -> GREEN // months and years
            }
        }

        /** Day counts colored by band: over 30 green, 8 to 30 amber, 7 or fewer orange; past red. */
        fun dayColor(days: Int) = when { days > 30 -> GREEN; days > 7 -> AMBER; else -> ORANGE }

        /**
         * Like the AirPods chip: names stay in the line's own color and the value words take their status
         * color ("96 Days" green, "21 Days" amber, "3 Days" orange, "Expired" red, "Magnet Limit Reached" amber).
         */
        fun colorize(text: String): CharSequence {
            val ms = VALUES.findAll(text).toList()
            if (ms.isEmpty()) return text
            val out = SpannableString(text)
            for (m in ms) out.setSpan(ForegroundColorSpan(colorOf(m)), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return out
        }

        fun severityColor(s: DreamNotices.Severity) = when (s) {
            DreamNotices.Severity.CRITICAL -> RED
            DreamNotices.Severity.WARNING -> AMBER
            DreamNotices.Severity.INFO -> 0xFFE9E6F0.toInt()
        }

        /** "Just Now", "5 Minutes Ago", "3 Hours Ago", "Yesterday", "2 Days Ago", then the date. */
        fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
            val m = ((now - at).coerceAtLeast(0) / 60_000L)
            return when {
                m < 1 -> "Just Now"
                m < 60 -> if (m == 1L) "1 Minute Ago" else "$m Minutes Ago"
                m < 24 * 60 -> (m / 60).let { if (it == 1L) "1 Hour Ago" else "$it Hours Ago" }
                m < 48 * 60 -> "Yesterday"
                m < 7 * 24 * 60 -> "${m / (24 * 60)} Days Ago"
                else -> DreamTime.fmt("MMM d").format(java.util.Date(at))
            }
        }

        /**
         * The bell's unread badge: a small disc in the brand's rainbow (blue to pink, white count), at the
         * bell's top right. [update] sets the count (0 hides it).
         */
        fun badge(ctx: Context): TextView = TextView(ctx).apply {
            val d = ctx.resources.displayMetrics.density
            textSize = 10f
            typeface = Theme.typeface(ctx, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
            minWidth = (17 * d).toInt()
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Rainbow.COLORS[0], Rainbow.COLORS[1], Rainbow.COLORS[2])).apply {
                cornerRadius = 9 * d
                setStroke((1.5f * d).toInt().coerceAtLeast(1), 0xFF0C0C0E.toInt())
            }
            isFocusable = false
            visibility = View.GONE
        }

        fun update(badge: TextView, count: Int) {
            badge.visibility = if (count > 0) View.VISIBLE else View.GONE
            badge.text = if (count > 9) "9+" else count.toString()
        }
    }
}

/** A simple drawn face for devices without the emoji glyph: a pearl disc, two eyes, a smile or a level mouth. */
private class FaceDrawable(ctx: Context, private val smile: Boolean) : android.graphics.drawable.Drawable() {
    private val d = ctx.resources.displayMetrics.density
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = PearlFocus.PEARL }
    private val ink = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = PearlFocus.INK; style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.8f * d; strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private val eye = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = PearlFocus.INK }
    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        val r = minOf(b.width(), b.height()) * 0.36f
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx - r * 0.36f, cy - r * 0.22f, r * 0.11f, eye)
        c.drawCircle(cx + r * 0.36f, cy - r * 0.22f, r * 0.11f, eye)
        if (smile) c.drawArc(android.graphics.RectF(cx - r * 0.48f, cy - r * 0.2f, cx + r * 0.48f, cy + r * 0.5f), 20f, 140f, false, ink)
        else c.drawLine(cx - r * 0.36f, cy + r * 0.34f, cx + r * 0.36f, cy + r * 0.34f, ink)
    }
    override fun setAlpha(alpha: Int) { fill.alpha = alpha; ink.alpha = alpha; eye.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
