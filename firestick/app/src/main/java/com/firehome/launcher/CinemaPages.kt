package com.firehome.launcher

import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout

/** The kinds of rows Cinema pages show: their card size (dp) and how many placeholders hold their place. */
enum class RowKind(val w: Int, val h: Int, val skeletons: Int) {
    STANDARD(CinemaStyle.LAND_W, CinemaStyle.LAND_W * 9 / 16, 5),
    BIG(CinemaStyle.BIG_W, CinemaStyle.BIG_H, 3),
    /** Width: the numeral and the poster together ([CinemaKit.ranked]), five across. */
    RANKED(CinemaStyle.RANK_W + CinemaKit.RANK_NUM + 4, CinemaStyle.RANK_H, 5),
    CONTINUE(CinemaStyle.LAND_W, CinemaStyle.LAND_W * 9 / 16, 5),
    BRAND(CinemaStyle.BRAND_W, CinemaStyle.BRAND_W * 9 / 16, 6),
    /** Browse's wall: 2:3 posters, seven across, the title (one line) and year · type under them. */
    POSTER(110, 165, 7),
    /** One title across the page (the 960 dp screen between the gutters). */
    SPOTLIGHT(960 - 2 * CinemaStyle.GUTTER, CinemaKit.SPOTLIGHT_H, 1);

    /** The caption under each card (px), as [CinemaKit.unit] lays it out. */
    fun caption(kit: CinemaKit) = when (this) {
        STANDARD, CONTINUE -> kit.captionHeight(kicker = false, sub = true)
        BIG -> kit.captionHeight(kicker = false, sub = true)
        RANKED -> kit.captionHeight(kicker = false, sub = false)
        POSTER -> kit.captionHeight(kicker = false, sub = true)
        BRAND, SPOTLIGHT -> 0
    }
}

/**
 * A Cinema browse page (Home, Movies, TV Shows, a service): sections stacked down the page (a hero, chips,
 * rows), each a [CinemaSection]. Up and Down go from section to section (into a row's remembered card),
 * never by geometry; the page glides so the focused row's title sits on [CinemaStyle.ROW_LINE], unless it
 * fits on the first screen, where the page stays at the top with the hero and the bar.
 *
 * Kept light while it grows: rows load lazily (a few ahead of focus), rows far from the screen are not
 * drawn at all (INVISIBLE, so the renderer neither draws nor syncs them) and let go of their art, and
 * the art that is bound loads nearest-first.
 */
open class CinemaRowsPage(shell: CinemaShell) : CPage(shell) {
    protected val scroll = SmoothScrollView(ctx).apply {
        manual = true
        keyScroll = false
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isFillViewport = true
        clipChildren = false
    }
    protected val col = FlatLinear(ctx).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        // Room under the last row so it can still rise to the line.
        setPadding(0, 0, 0, kit.screenH / 2)
    }
    override val view: View = object : FlatFrame(ctx) {
        override fun focusSearch(focused: View?, direction: Int): View? {
            if (focused != null && (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN)) {
                val sec = HomeShell.directChild(focused, col)
                if (sec != null) return vertical(sec, if (direction == View.FOCUS_DOWN) 1 else -1) ?: focused
            }
            return super.focusSearch(focused, direction)
        }
    }.apply {
        clipChildren = false
        addView(scroll, MATCH_PARENT, MATCH_PARENT)
    }
    /**
     * A soft fade along the bottom edge while sections lie below: the next row only peeks in under it and
     * is never cut by the screen's edge (its captions sit in the solid part), and the chevron has a clear
     * strip of its own there, over nothing but the fade.
     */
    private val bottomFade = View(ctx).apply {
        val bg = kit.bg
        background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Ui.alpha(bg, 0f), Ui.alpha(bg, 0.55f), Ui.alpha(bg, 0.92f), bg, bg))
        alpha = 0f
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    /** "More below": a small chevron at the bottom edge while rows lie past the screen. */
    private val moreHint = android.widget.ImageView(ctx).apply {
        setImageResource(R.drawable.ic_chevron_down)
        setColorFilter(Ui.alpha(kit.text, 0.75f))
        alpha = 0f
    }
    private var hintShown = false
    private var bob: android.animation.ObjectAnimator? = null
    /** The page's hero, if it has one. */
    protected var hero: CinemaHero? = null
    private val pending = HashMap<View, Runnable>()
    /** Lazy rows whose list is queued or loading ([Net.load]), with the runnable that starts it again if dropped. */
    private val queued = HashMap<View, Pair<Net.Task, Runnable>>()
    /** When focus landed on a row still showing placeholders (uptime ms), for its [RowTiming] line. */
    private val blankSince = java.util.WeakHashMap<View, Long>()
    private var focusSec = 0
    private var focusView: View? = null
    private var scrollTarget = 0
    protected var released = false

    init {
        scroll.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        (view as FrameLayout).addView(bottomFade, FrameLayout.LayoutParams(MATCH_PARENT, BOTTOM_FADE.dp, android.view.Gravity.BOTTOM))
        // In the fade's solid last quarter, clear of every card and caption.
        (view as FrameLayout).addView(moreHint, FrameLayout.LayoutParams(18.dp, 18.dp, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 3.dp })
    }

    /** Shows the chevron while any section lies below the screen where the page is headed; three soft bobs, then still. */
    private fun updateHint() {
        val h = scroll.height.takeIf { it > 0 } ?: kit.screenH
        var bottom = 0
        for (k in 0 until col.childCount) col.getChildAt(k).let { if (it.visibility != View.GONE) bottom = maxOf(bottom, it.bottom) }
        val show = bottom > scrollTarget + h + 8.dp
        if (show == hintShown) return
        hintShown = show
        moreHint.animate().alpha(if (show) 1f else 0f).setDuration(260).start()
        bottomFade.animate().alpha(if (show) 1f else 0f).setDuration(260).start()
        if (!show) return
        bob?.cancel()
        bob = android.animation.ObjectAnimator.ofFloat(moreHint, "translationY", -3.dp.toFloat(), 0f).apply {
            duration = 700
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = 4 // an odd number of runs: it ends where it rests
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    // ---- building ----

    protected fun add(v: View, top: Int = 0, gap: Int = CinemaStyle.ROW_GAP) {
        col.addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = top.dp; bottomMargin = gap.dp })
    }

    /** A row of [kind] holding placeholders until it is filled. */
    protected fun newRow(title: String?, kind: RowKind) = CinemaRow(kit, title).apply {
        skeleton(kind.skeletons, kind.w, kind.h, kind.caption(kit))
        onCardFocus = { _, card -> host.speculate(card.title) }
    }

    /**
     * A row that fills from a background [load]; left out if nothing comes back. A [lazy] row fetches only
     * once focus is a few rows away. [make] builds the cards (and may retitle the row); [onEmpty] runs when it's left out.
     */
    protected fun <T> row(title: String?, kind: RowKind, lazy: Boolean = true, load: () -> List<T>?, onEmpty: (() -> Unit)? = null,
                          make: (CinemaRow, List<T>) -> List<Pair<View, CinemaCard>>): CinemaRow {
        val r = newRow(title, kind)
        add(r)
        // Landscape and big rows ask for their titles' key art (KeyArt) on the row thread, briefly, before their cards are made.
        val loadArt: () -> List<T>? = if (kind == RowKind.STANDARD || kind == RowKind.BIG) ({
            load()?.also { l -> KeyArt.prime(ctx, l.filterIsInstance<Title>().take(20)) }
        }) else load
        val start = object : Runnable {
            override fun run() {
                val again = this
                // Nearest to focus first, on the row-list threads (not behind the shared queue), re-ranked as focus moves.
                val task = Net.load(loadRank(r), loadArt) { list ->
                    val job = queued.remove(r)?.first
                    if (r.parent == null || released) return@load
                    // A row off screen that loads mid-glide makes its cards once the page has landed: building them
                    // during the glide was the first-scroll hitch on rows the warm-up hadn't reached yet.
                    if (belowScreen(r) && moving()) {
                        whenStill(r, SystemClock.uptimeMillis()) { if (r.parent != null) landRow(r, title, kind, list, job, loadArt, onEmpty, make) }
                        return@load
                    }
                    landRow(r, title, kind, list, job, loadArt, onEmpty, make)
                }
                queued[r] = task to again
            }
        }
        if (lazy) pending[r] = start else start.run()
        return r
    }

    /** The page is gliding, or a key was just pressed (the next glide is on its way): a scroll is in progress. */
    private fun moving() = scroll.gliding || PreResolve.sinceKeyMs() < STILL_KEY_MS

    /**
     * [r] starts more than half a screen below the screen (an unfilled row has no height yet, so its top decides,
     * not [onScreen]); closer than that it must be built now, so a scroll never reaches a blank row.
     */
    private fun belowScreen(r: View): Boolean {
        val h = scroll.height.takeIf { it > 0 } ?: kit.screenH
        return r.top >= maxOf(scroll.scrollY, scrollTarget) + h + h / 2
    }

    /** Runs [f] once the page has stopped moving, [r] has come near, or [STILL_MAX_MS] passed since [since]. */
    private fun whenStill(r: View, since: Long, f: () -> Unit) {
        if (released) return
        if (!moving() || !belowScreen(r) || SystemClock.uptimeMillis() - since > STILL_MAX_MS) f()
        else shell.post(Runnable { whenStill(r, since, f) }, STILL_POLL_MS)
    }

    /** A loaded row's cards: made, filled in, deepened, and the window updated. */
    private fun <T> landRow(r: CinemaRow, title: String?, kind: RowKind, list: List<T>?, job: Net.Task?, load: () -> List<T>?,
                            onEmpty: (() -> Unit)?, make: (CinemaRow, List<T>) -> List<Pair<View, CinemaCard>>) {
        if (released) return
        val t0 = SystemClock.uptimeMillis()
        // Pills of the row's new titles are queued as the cards are made: one batch, logged by row name.
        val pairs = if (list.isNullOrEmpty()) emptyList() else Availability.batch(title ?: kind.name.lowercase()) { make(r, list) }
        if (pairs.isEmpty()) { setShown(r, false); onEmpty?.invoke(); return }
        val timing = job?.let { RowTiming(title ?: kind.name.lowercase(), it.queuedAt, it.startedAt, SystemClock.uptimeMillis()) }
        if (timing != null) r.onArtReady = { arts -> timing.art(arts, col.indexOfChild(r), focusSec, blankSince.remove(r)) }
        r.fill(pairs, animate = onScreen(r))
        @Suppress("UNCHECKED_CAST")
        if ((kind == RowKind.STANDARD || kind == RowKind.BIG) && title != null && list!!.first() is Title)
            deepen(r, title, list as List<Title>, load as () -> List<Title>?, make as (CinemaRow, List<Title>) -> List<Pair<View, CinemaCard>>)
        window()
        // A row's cards are made on the main thread: anything slow enough to delay a key press is named here.
        (SystemClock.uptimeMillis() - t0).takeIf { it >= 24 }?.let { android.util.Log.i("DreamRows", "'${title ?: kind.name.lowercase()}' made its cards in $it ms on the main thread") }
    }

    /** A section's place in the row-list and art queues: the focused row 0, rows ahead 1, 2, ..., rows behind count double. */
    private fun loadRank(sec: View): Int {
        val d = col.indexOfChild(sec) - focusSec
        return if (d >= 0) d else -2 * d
    }

    /** [sec] leaves the page (or is hidden): its list no longer starts, and if it was still waiting it never runs. */
    private fun forget(sec: View) {
        pending.remove(sec)
        queued.remove(sec)?.first?.cancel()
    }

    /**
     * A deep row: once focus nears its end it loads its next page ([RowPaging]: the same [load] a page
     * further on), up to [RowPaging.MAX_TITLES] titles or until TMDB runs out, and adds the new cards at
     * the end ([CinemaRow.append]). Titles the row already shows are dropped.
     */
    private fun deepen(r: CinemaRow, name: String, first: List<Title>, load: () -> List<Title>?, make: (CinemaRow, List<Title>) -> List<Pair<View, CinemaCard>>) {
        val shown = first.mapTo(HashSet()) { it.key }
        var page = 2
        var busy = false
        var done = false
        r.onNearEnd = near@{
            if (busy || done || released) return@near
            if (r.cards.size >= RowPaging.MAX_TITLES) { done = true; return@near }
            busy = true
            val seen = HashSet(shown)
            // The focused row's next page: first in the row-list queue.
            Net.load(0, { RowPaging.next(name, page, seen, load) }) { res ->
                busy = false
                if (r.parent == null || released) return@load
                val fresh = res?.second.orEmpty().filter { shown.add(it.key) }.take(RowPaging.MAX_TITLES - r.cards.size)
                if (fresh.isEmpty()) { done = true; return@load }
                page = res!!.first + 1
                r.append(make(r, fresh))
            }
        }
    }

    /** Cards for [list]: [card] builds each one, and every card opens its title (☰ for its menu). */
    protected fun titleCards(list: List<Title>, card: (Title) -> Pair<View, CinemaCard>) = list.map { t -> card(t).also { wire(it.second, t) } }

    protected fun wire(c: CinemaCard, t: Title) {
        if (t.type == "tv" && t.id in NewEpisodeSpot.badged) c.badge("NEW EPISODE") // a followed show's new episode (NewEpisodeBanner.kt)
        Availability.attachTitle(c, t) // only new titles: a recent or upcoming movie, a show with a new or announced episode
        c.setOnClickListener { host.openDetails(t, c) }
        c.isLongClickable = true
        c.setOnLongClickListener { host.titleMenu(t); true }
    }

    /**
     * [sec] holds focus and is about to go (hidden, removed, swapped): focus moves on first, to the next
     * section [step] (±1) that can take it, else the other way, so it never falls out of the page.
     */
    private fun rescueFocus(sec: View, step: Int) {
        if (!sec.hasFocus()) return
        (vertical(sec, step) ?: vertical(sec, -step))?.requestFocus()
    }

    /** Removes the rows from [from] on (a chip switched them out). Focus in them moves up first. */
    protected fun removeFrom(from: Int) {
        for (k in from until col.childCount) if (col.getChildAt(k).hasFocus()) {
            col.getChildAt(from).let { first -> (vertical(first, -1) ?: shell.barEntry())?.requestFocus() }
            break
        }
        while (col.childCount > from) {
            val c = col.getChildAt(col.childCount - 1)
            forget(c)
            col.removeViewAt(col.childCount - 1)
        }
        if (focusSec >= col.childCount) focusSec = maxOf(0, col.childCount - 1)
    }

    protected fun sectionCount() = col.childCount

    protected fun header(title: String, sub: String): View = kit.pageHeader(title, sub)

    /** Swaps section [old] for [new] in place (same spacing). */
    protected fun replace(old: View, new: View) {
        val i = col.indexOfChild(old)
        if (i < 0) return
        val lp = old.layoutParams
        val had = old.hasFocus()
        if (had) rescueFocus(old, -1)
        forget(old)
        col.removeViewAt(i)
        col.addView(new, i, lp)
        if (had) (new as? CinemaSection)?.entry()?.requestFocus()
    }

    /**
     * Shows or hides a section without the page jumping under the user: a section above the focused one
     * that comes or goes moves the page by its height in the same frame it lays out.
     */
    protected fun setShown(sec: View, on: Boolean) {
        if (on == (sec.visibility != View.GONE)) return
        forget(sec)
        // A row focus rests on (its placeholders) came back empty: focus goes on down before it leaves.
        if (!on) rescueFocus(sec, 1)
        val i = col.indexOfChild(sec)
        val above = i in 0 until focusSec && scrollTarget > 0
        val lp = sec.layoutParams as ViewGroup.MarginLayoutParams
        val before = if (on) 0 else sec.height + lp.topMargin + lp.bottomMargin
        sec.visibility = if (on) View.VISIBLE else View.GONE
        if (!above) return
        col.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View?, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                col.removeOnLayoutChangeListener(this)
                val dy = if (on) sec.height + lp.topMargin + lp.bottomMargin else -before
                scrollTarget = maxOf(0, scrollTarget + dy)
                scroll.glideTo(scrollTarget, 1)
            }
        })
    }

    // ---- focus and scrolling ----

    /** The next section [step] (±1) from [sec] that can take focus, made visible; Up past the top: the bar. */
    private fun vertical(sec: View, step: Int): View? {
        var k = col.indexOfChild(sec) + step
        while (k in 0 until col.childCount) {
            val c = col.getChildAt(k)
            if (c.visibility != View.GONE && c is CinemaSection) {
                if (sec is CinemaRow && c is CinemaRow && sec.grid && c.grid) c.prefer(sec.index)
                val e = c.entry()
                if (e != null && e.isFocusable) {
                    if (c.visibility == View.INVISIBLE) c.visibility = View.VISIBLE
                    return e
                }
            }
            k += step
        }
        return if (step < 0) shell.barEntry() else null
    }

    private fun firstSection(): View? = (0 until col.childCount).map { col.getChildAt(it) }
        .firstOrNull { it.visibility != View.GONE && it is CinemaSection && (it as CinemaSection).entry() != null }

    override fun focusFirst() {
        val s = firstSection() ?: return
        if (s.visibility == View.INVISIBLE) s.visibility = View.VISIBLE
        (s as CinemaSection).entry()?.requestFocus()
    }

    override fun focusMoved(now: View?) { hero?.focusMoved() }

    override fun onFocus(v: View) {
        val sec = HomeShell.directChild(v, col) ?: return
        val i = col.indexOfChild(sec)
        when (sec) {
            is CinemaHero -> host.speculate(sec.current())
            is CinemaRow -> {} // its cards report their own titles
            else -> host.speculate(null)
        }
        sectionFocused(sec, v)
        // Landed on a row whose cards aren't in yet: how long it stays blank goes in its timing line.
        if (sec is CinemaRow && !sec.filled && sec !in blankSince) blankSince[sec] = SystemClock.uptimeMillis()
        if (focusView === sec && i == focusSec) return
        focusView = sec
        focusSec = i
        val t = targetFor(sec)
        fadeAbove(if (sec is CinemaRow && sec.grid) i else -1, t)
        glide(t)
        // The hero's slow drift only while focus rests on it: browsing rows never shares frames with it.
        hero?.drifting = sec === hero
        kick()
        window()
    }

    private var chipPending: Runnable? = null
    private var quietChip = false

    /** A page's own reaction to focus landing in [sec]. Chips: resting on one switches to it. */
    protected open fun sectionFocused(sec: View, v: View) {
        chipPending?.let { shell.cancel(it) }
        val c = sec as? CinemaChips ?: return
        // Back put focus on the first chip: that's a way home, not a choice (the rows stay as they are).
        if (quietChip) { quietChip = false; return }
        val i = c.indexOf(v)
        if (i < 0 || i == c.current) return
        chipPending = Runnable { if (v.isFocused) c.select(i, now = false) }.also { shell.post(it, CHIP_REST_MS) }
    }

    /** The end of the page: "You're All Caught Up" and Back to Top. */
    protected fun endCap(): View = CinemaEndCap(kit) {
        toTop()
        shell.post(Runnable { focusFirst() }, 120)
    }.also { add(it, top = 8, gap = 0) }

    /** Takes section [v] off the page (an end cap that more rows go before). */
    protected fun removeSection(v: View) {
        rescueFocus(v, -1)
        forget(v)
        col.removeView(v)
    }

    private fun targetFor(sec: View): Int {
        val h = scroll.height.takeIf { it > 0 } ?: kit.screenH
        // A section that would end in the bottom fade rises to the line instead (nothing focused sits under it).
        if (sec.bottom + BOTTOM_FADE.dp <= h) return 0
        if (sec is CinemaRow && sec.grid) return edgeTarget(sec, h)
        return maxOf(0, sec.top - lineFor(sec).dp)
    }

    /**
     * A poster grid scrolls at its edges: moving among the lines that show whole moves only the focus. A line
     * past the bottom (into the fade) brings the page up just enough that it ends where the fade starts, its
     * captions whole; a line cut by the top brings the page down until it sits on [lineFor] (its cards on
     * [CinemaStyle.GRID_LINE]). Either way one line's worth, in the same glide.
     */
    private fun edgeTarget(sec: View, h: Int): Int {
        val cur = scrollTarget
        val usable = h - BOTTOM_FADE.dp
        return when {
            sec.bottom > cur + usable -> sec.bottom - usable
            sec.top < cur + GRID_TOP_CLEAR.dp -> maxOf(0, sec.top - lineFor(sec).dp)
            else -> cur
        }
    }

    /**
     * Where [sec]'s top settles once the page scrolls (dp from the top of the screen). Rows: their title on
     * [CinemaStyle.ROW_LINE]; a poster grid's line: its cards on [CinemaStyle.GRID_LINE]. Every row style is
     * sized so the focused row and the next one, titles and captions included, both end above the bottom fade.
     */
    protected open fun lineFor(sec: View): Int = if (sec is CinemaRow && sec.grid) CinemaStyle.GRID_LINE else CinemaStyle.ROW_LINE

    /** A poster grid's line has focus: no top veil, the sections above it fade out. */
    private var gridFocus = false
    /** Each section's alpha as last asked for (where a fade under way is headed). */
    private val shownAlpha = java.util.WeakHashMap<View, Float>()

    /**
     * On a poster grid, instead of the shell's top veil (which would dim a lifted poster on the top line), the
     * sections above the focused line that the top edge cuts fade out, and come back as the page brings them
     * in again. Elsewhere every section shows and the veil is back.
     */
    private fun fadeAbove(gridAt: Int, target: Int = 0) {
        val on = gridAt >= 0
        val clear = target + GRID_TOP_CLEAR.dp
        for (k in 0 until col.childCount) {
            val c = col.getChildAt(k)
            // Only what the top edge would cut fades: lines (and chips) still whole on screen stay.
            val a = if (on && k < gridAt && c.top < clear && c !is CinemaHero) 0f else 1f
            if ((shownAlpha[c] ?: 1f) == a) continue
            shownAlpha[c] = a
            c.animate().alpha(a).setStartDelay(0).setDuration(if (a == 0f) 160L else 220L).setInterpolator(Ui.EASE).start()
        }
        if (on != gridFocus) {
            gridFocus = on
            shell.topVeilChanged()
        }
    }

    override fun fadesTop(): Boolean = !gridFocus

    private fun glide(y: Int) {
        val h = scroll.height.takeIf { it > 0 } ?: kit.screenH
        val t = y.coerceIn(0, maxOf(0, col.height - h))
        // Always a glide, never a jump (a jump reads as the page reloading): a longer way takes a little longer.
        val screens = kotlin.math.abs(t - scroll.scrollY) / h.toFloat()
        scrollTarget = t
        scroll.glideTo(t, (GLIDE_MS + screens * 90).toLong().coerceAtMost(LONG_GLIDE_MS))
    }

    /**
     * Netflix's look-ahead: the lazy rows from the one above focus to [LOOKAHEAD] past it start loading,
     * nearest first ([loadRank]). Lists still waiting are re-ranked for where focus is now, and those for
     * rows [DROP_BEHIND] or more behind it are dropped (they start again if focus comes back), so a fast
     * scroll down never leaves the rows it passed queued ahead of the ones it is heading for.
     */
    private fun kick() {
        if (pending.isEmpty() && queued.isEmpty()) return
        // Counted in sections still on the page: rows that came back empty (gone) don't use up the look-ahead.
        var k = maxOf(0, focusSec - 1)
        var ahead = 0
        while (k < col.childCount && ahead < LOOKAHEAD) {
            val c = col.getChildAt(k)
            pending.remove(c)?.run()
            if (k > focusSec && c.visibility != View.GONE) ahead++
            k++
        }
        if (queued.isEmpty()) return
        val it = queued.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val (task, again) = e.value
            if (col.indexOfChild(e.key) <= focusSec - DROP_BEHIND && task.cancel()) {
                it.remove()
                pending[e.key] = again
            } else task.rank = loadRank(e.key)
        }
        Net.resortLoads()
    }

    private fun onScreen(r: View): Boolean {
        if (r.visibility != View.VISIBLE || !showing()) return false
        val h = scroll.height.takeIf { it > 0 } ?: kit.screenH
        return r.height == 0 || (r.bottom > scrollTarget && r.top < scrollTarget + h)
    }

    /**
     * Rows within half a screen of where the page is (and is heading) draw and hold their art; the rest
     * let go of it, and a moment after the glide (when they're surely off screen) stop being drawn.
     */
    protected fun window() {
        val h = scroll.height.takeIf { it > 0 } ?: kit.screenH
        val from = minOf(scroll.scrollY, scrollTarget) - h / 2
        val to = maxOf(scroll.scrollY, scrollTarget) + h + h / 2
        for (k in 0 until col.childCount) {
            val c = col.getChildAt(k)
            if (c.visibility == View.GONE) continue
            // Everything a glide passes over draws (no blank gaps going by); only the rows where the page is
            // and where it's headed hold art (a long glide back to the top doesn't decode every row on the way).
            val near = c.height == 0 || (c.bottom > from && c.top < to)
            if (near && c.visibility == View.INVISIBLE) c.visibility = View.VISIBLE
            val art = c.height == 0 || inWindow(c, scrollTarget, h, ahead = true) || inWindow(c, scroll.scrollY, h)
            (c as? CinemaRow)?.art(art, loadRank(c))
        }
        // Art already queued takes the new ranks too: the focused row and the next first, the rows passed last
        // (without this, a row's art kept the rank it was queued at, as a row three ahead, behind the rows passed).
        Net.reorder { v -> (v as? CinemaArt)?.let { a -> a.owner?.takeIf { it.parent === col }?.rankOf(a) } }
        shell.cancel(hideFar)
        shell.post(hideFar, GLIDE_MS + 80)
        updateHint()
        if (!warmStarted && col.childCount > 3) { warmStarted = true; shell.post(warmRows, WARM_START_MS) }
    }

    // ---- one-time warm-up of the rows below the screen ----
    //
    // The first time a row comes on screen it decodes all its art at once: a one-time hitch, smooth on every later
    // pass. While the remote rests, the rows below are warmed one at a time ahead of that first visit: lazy rows
    // built and their art decoded into the image cache (kept there when the row lets go of it). Nothing here blocks
    // the main thread for long (Top 10's numerals draw themselves in the background: CinemaKit.RankNumeral).
    private var warmStarted = false
    private var warmNext = 0
    private val warmRows = object : Runnable {
        override fun run() {
            if (released) return
            shell.workAheadWait().takeIf { it > 0 }?.let { shell.post(this, it); return } // never under the boot intro
            if (PreResolve.sinceKeyMs() < WARM_REST_MS) { shell.post(this, WARM_REST_MS); return }
            val h = scroll.height.takeIf { it > 0 } ?: return
            while (warmNext < col.childCount) {
                val c = col.getChildAt(warmNext)
                // A lazy row that hasn't loaded yet is built now, while nothing moves (building its cards is the
                // bigger part of the first-visit hitch); the same row is warmed on the next step, once it's filled.
                pending.remove(c)?.let { start -> start.run(); shell.post(this, WARM_STEP_MS * 2); return }
                warmNext++
                if (c.visibility == View.GONE || c !is CinemaRow || c.height == 0) continue
                if (inWindow(c, scroll.scrollY, h, ahead = true)) continue // already warm: on screen or just ahead
                c.art(true, 30 + warmNext)
                shell.post(this, WARM_STEP_MS)
                return
            }
        }
    }

    /**
     * Half a screen above and below the screen at [y]; [ahead] (where the page is headed): two screens below,
     * so the first cards of the next rows decode before focus reaches them.
     */
    private fun inWindow(c: View, y: Int, h: Int, ahead: Boolean = false) = c.bottom > y - h / 2 && c.top < y + if (ahead) 2 * h else h + h / 2

    private val hideFar = Runnable {
        val h = scroll.height
        if (h == 0 || released) return@Runnable
        val from = scroll.scrollY - h / 2
        val to = scroll.scrollY + h + h / 2
        for (k in 0 until col.childCount) {
            val c = col.getChildAt(k)
            if (c.visibility == View.VISIBLE && c.height > 0 && (c.bottom <= from || c.top >= to) && !c.hasFocus()) c.visibility = View.INVISIBLE
        }
    }

    override fun toTop() {
        focusSec = 0
        focusView = null
        fadeAbove(-1)
        glide(0)
        hero?.drifting = true
        window()
    }

    /**
     * Back, one calm step at a time (Netflix): in a row or a line of chips, first back to its first item (the
     * row glides there like any move); from there, up to the top of the page (the hero's Play); at the top,
     * false (the shell goes to the bar). Nothing reloads, rebuilds or jumps on the way.
     */
    override fun handleBack(focused: View): Boolean {
        val sec = HomeShell.directChild(focused, col) ?: return false
        if (sec is CinemaRow) {
            val first = sec.cards.firstOrNull()
            if (first != null && !HomeShell.isInside(focused, first)) { first.requestFocus(); return true }
        }
        if (sec is CinemaChips) {
            val first = sec.chip(0)
            if (first != null && focused !== first) { quietChip = true; first.requestFocus(); return true }
        }
        val top = firstSection() ?: return false
        if (sec === top) return false
        toTop()
        focusFirst()
        return true
    }

    override fun barVisible() = scrollTarget == 0

    override fun onShow() {
        hero?.active = true
        hero?.restoreArt()
        kick()
        view.post { if (!released) window() }
    }

    override fun onHide() { hero?.active = false }
    override fun pause() { hero?.active = false }
    override fun opaqueArea(): View? = if (showing()) hero?.opaqueArea() else null
    override fun resume() { if (showing()) hero?.active = true }

    /** Built ahead (hidden): the first rows start loading and the art near the top binds, so it shows ready. */
    override fun prepare() {
        kick()
        view.post { if (!released) window() }
    }

    override fun offscreen() {
        super.offscreen()
        hero?.releaseArt()
    }

    override fun release() {
        released = true
        hero?.release()
        pending.clear()
        for ((task, _) in queued.values) task.cancel()
        queued.clear()
        shell.cancel(hideFar)
        chipPending?.let { shell.cancel(it) }
    }

    /**
     * Apple TV's Spotlight: one title across the page, between rows (a break in the rhythm of rows). [pick]
     * chooses it from [load]'s titles; left out when there's nothing with art.
     */
    protected fun spotlight(kick: String, load: () -> List<Title>?, pick: (List<Title>) -> Title? = { it.firstOrNull() }) {
        row(null, RowKind.SPOTLIGHT, load = { load()?.filter { it.backdropPath != null } }) { _, list ->
            val t = pick(list) ?: return@row emptyList()
            val c = kit.spotlight(t, kick)
            wire(c, t)
            listOf((c as View) to c)
        }
    }

    /**
     * A seasonal hub's row (Home; a genre page while a matching hub is on): the hub's tile first (it opens the
     * hub, SeasonHub.kt), then Tonight's Pick (marked) and the hub's lead titles. Lazy and cached like every
     * row, static once filled: nothing runs per frame.
     */
    protected fun seasonRow(hub: SeasonalHub, claims: RowClaims? = null) {
        val today = Seasons.today(ctx)
        row(hub.title, RowKind.STANDARD, load = { Seasons.homeTitles(ctx, hub) { n, l -> claims?.take(n, l) ?: l } }) { r, list ->
            val first = !r.filled
            val pick = Seasons.pickedKey(ctx, hub)
            (if (first) listOf(SeasonUi.tile(kit, hub, today) { c -> shell.openSeason(hub, c) }) else emptyList()) +
                titleCards(list) { t -> kit.landscape(t).also { (_, c) -> if (first && t.key == pick) c.badge("TONIGHT'S PICK") } }
        }
    }

    protected companion object {
        const val GLIDE_MS = 380L
        /** The longest a glide takes, however far (back to the top from deep in a page). */
        const val LONG_GLIDE_MS = 820L
        /**
         * The bottom fade's height (dp); its last quarter is solid (the chevron's strip, over nothing a reader
         * needs). At 1080p it starts at 486 dp: a row on [CinemaStyle.ROW_LINE] and the next one end by then
         * (52 + 199 + 28 + 199 = 478 dp for standard rows; no row style is taller than 204 dp), and so do two
         * poster-grid lines on [CinemaStyle.GRID_LINE] (44 + 213 + 17 + 213 = 487 dp).
         */
        const val BOTTOM_FADE = 54
        /** On a poster grid, a section whose top is closer than this to the screen's top edge counts as cut (dp). */
        const val GRID_TOP_CLEAR = 30
        /** Lazy rows start loading this many sections ahead of focus. */
        const val LOOKAHEAD = 4
        /** A lazy row this many sections behind focus whose list hasn't started yet is dropped from the queue. */
        const val DROP_BEHIND = 2
        /** Home's hero (dp): [CinemaStyle.HERO_SHARE] of the 540 dp screen. */
        val HERO_H = (540 * CinemaStyle.HERO_SHARE).toInt()
        /** The browse pages' smaller hero (dp). */
        const val COMPACT_H = 356
        /** A row needs this many titles to show at all. */
        const val MIN_ROW = 6
        /** The rows below the screen warm up from this long after the page first lays out (past the boot intro's fade). */
        const val WARM_START_MS = 2_000L
        /** One row per step, only while the remote has rested this long. */
        const val WARM_STEP_MS = 450L
        /** A real pause, not the gap between presses (a step can hold the main thread ~50-120 ms making a row). */
        const val WARM_REST_MS = 1_200L
        /** How often a row waiting for a glide to land checks again. */
        const val STILL_POLL_MS = 60L
        /** A key this recent means more presses are coming (held or repeated Down). */
        const val STILL_KEY_MS = 300L
        /** The longest a loaded row waits for the page to be still. */
        const val STILL_MAX_MS = 2_500L
        const val CHIP_REST_MS = 420L
    }
}

/**
 * Cinema's Home: the hero carousel (what suits the time of day) with the first row peeking up under it,
 * Continue Watching, Hulu-style big cards of Top Picks for You, then Trending, Top 10 (Netflix's numbered
 * row), Spotlights (Apple TV), New Episodes, My List, Because You Watched and the services. No apps and no
 * service tiles here (those live on Apps and Browse). Each title shows in one row only ([RowClaims]); the
 * picks and the featured titles are Home's own ([HomePicks]).
 */
class CinemaHome(shell: CinemaShell) : CinemaRowsPage(shell) {
    private val claims = RowClaims(ctx)
    private var continueRow: CinemaRow? = null
    private var continueShown = -1L
    private var newEpRow: CinemaRow? = null
    /** A followed show's new episode, right under the hero (NewEpisodeBanner.kt); gone while there's none. */
    private var newEpBanner: NewEpisodeBanner? = null
    private var newEpShown: String? = null
    private var myListRow: CinemaRow? = null
    private var myListShown: String? = null
    private var seedKey: String? = null
    // Declared before init: build() fills them, and its first rows load on other threads at once.
    /** The keys of the Continue Watching cards as shown (which title focus rests on when the row is rebuilt). */
    private var continueKeys: List<String> = emptyList()
    /** Top 10 and Trending Now as first claimed ([top10], [trending]). */
    @Volatile private var top10Now: List<Title>? = null
    @Volatile private var trendingNow: List<Title>? = null
    private val charts = Any()

    init { build() }

    private fun build() {
        val apps = host.apps()
        if (!Tmdb.hasKey(ctx)) { noKey(); host.homeReady(); return }
        val h = CinemaHero(kit, host, HERO_H, compact = false)
        hero = h
        // The first row tucks up into the hero's faded bottom (Apple TV's top-shelf peek).
        add(h, gap = -12)
        newEpBanner = NewEpisodeBanner(kit, host) { v, on -> setShown(v, on); window() }.also {
            it.visibility = View.GONE
            it.onFresh = { s -> newEpRow?.let { r -> fillNewEpisodes(r, s) } }
            add(it)
        }
        continueRow = newRow("Continue Watching", RowKind.CONTINUE).also { it.visibility = View.GONE; add(it) }
        var fallback = false
        // Popular Today: today's chart, only what's out and no anime outside its sections (the row's name is personal).
        // Two pages, like Top 10: Top 10 and Trending Now claim most of the first.
        fun popularToday() = claims.take("Top Picks for You", (Tmdb.list(ctx, "/trending/all/day") + Tmdb.list(ctx, "/trending/all/day", "&page=2"))
            .filter { it.backdropPath != null && RowMix.released(it) && !RowMix.animeHidden(ctx, it) }, 12)
        row("Top Picks for You", RowKind.BIG, lazy = false, load = {
            trending()
            // The first page decides which list the row is; its later pages stay on that list.
            if (RowPaging.page > 1) (if (fallback) popularToday() else claims.take("Top Picks for You", HomePicks.topPicks(ctx)))
            else claims.take("Top Picks for You", HomePicks.topPicks(ctx)).takeIf { it.size >= HomePicks.MIN_PICKS }
                ?: run { fallback = true; popularToday() }
        }, onEmpty = { host.homeReady() }) { r, list ->
            if (fallback) r.header?.text = "Popular Today"
            host.homeReady()
            titleCards(list.take(12)) { kit.big(it, bigKicker(it)) }
        }
        // The seasonal hub that's on (31 Nights of Horror, Home for the Holidays, a base season...): SeasonHub.kt.
        seasonRow(Seasons.active(ctx), claims)
        // The user's followed shows (personal), above the general New Episodes row below.
        newEpRow =newRow("New Episodes for You", RowKind.CONTINUE).also { it.visibility = View.GONE; add(it) }
        // Just out to watch at home: new digital movies and brand-new series (NewReleases.kt).
        row("New Releases", RowKind.STANDARD, load = {
            trending()
            claims.take("New Releases", NewReleases.mixed(ctx)).takeIf { it.size >= MIN_ROW }.also { if (RowPaging.page == 1) NewReleases.log("home", it) }
        }) { _, list -> titleCards(list) { kit.landscape(it) } }
        // Running shows with a new season out (NewReleases.returning): their own row; no pills on these cards.
        row("New Episodes", RowKind.STANDARD, load = {
            claims.take("New Episodes", NewReleases.returning(ctx)).takeIf { it.size >= MIN_ROW }
        }) { _, list -> titleCards(list) { kit.landscape(it, sub = NewReleases.episodeSub(it)) } }
        // Only in theaters so far (US now playing), with their pills (CAM Only / Coming … / Available Now once a real release is out).
        row("In Theaters", RowKind.STANDARD, load = {
            trending()
            val playing = NewReleases.inTheaters(ctx) // recent theatrical releases only, no re-released classics
            claims.takeAtLeast("In Theaters", playing.filter { it.backdropPath != null || it.posterPath != null }, MIN_ROW)
        }) { _, list -> titleCards(list) { kit.landscape(it) } }
        row("Trending Now", RowKind.STANDARD, lazy = false, load = { trending() }) { _, list -> titleCards(list.take(16)) { kit.landscape(it) } }
        row("Top 10 Today", RowKind.RANKED, lazy = false, load = { top10().takeIf { it.size >= MIN_ROW } }) { _, list ->
            list.mapIndexed { i, t -> kit.ranked(t, i + 1).also { wire(it.second, t) } }
        }
        // A break in the rows: the best-rated title of the week (not one the rows above already show).
        spotlight("Spotlight", load = {
            val taken = HashSet<String>().apply { trending().forEach { add(it.key) }; top10().forEach { add(it.key) } }
            // The week's best-rated trending title the rows above don't show. Trending Now also draws on page 2, so
            // one page could run dry (the card vanished): pages 2-4, the first page with a pick wins.
            (2..4).asSequence().map { pg ->
                Tmdb.trendingShown(ctx, query = "&page=$pg", art = "backdrop").filter { it.key !in taken && it.votes >= 300 }
                    .sortedByDescending { it.rating }.take(1)
            }.firstOrNull { it.isNotEmpty() }.orEmpty()
        })
        myListRow = newRow("My List", RowKind.STANDARD).also { it.visibility = View.GONE; add(it) }

        // Because You Watched: the two most recent daily seeds (movies and shows together).
        seedKey = seedsNow()
        val seeds = (BecauseYouWatched.pick(ctx, shows = false).entries + BecauseYouWatched.pick(ctx, shows = true).entries)
            .sortedByDescending { it.updatedAt }.take(2)
        fun becauseRow(e: WatchHistory.Entry) {
            val type = if (e.isShow) "tv" else "movie"
            val title = "Because You Watched ${e.title}"
            row(title, RowKind.STANDARD, load = {
                trending()
                val skip = HashSet<String>().apply { WatchHistory.recent(ctx).forEach { add(it.key) }; Tmdb.myList(ctx).forEach { add(it.key) } }
                claims.take(title, RowMix.recs(ctx, type, e.tmdbId, e.year).filter { it.key !in skip }).takeIf { it.size >= MIN_ROW }
            }) { _, list -> titleCards(list) { kit.landscape(it) } }
        }
        seeds.getOrNull(0)?.let { becauseRow(it) }
        // Rows for this profile's taste (Taste.forYou): "Dark Crime Stories", "Edge-of-Your-Seat Thrillers"...; the
        // categories it finishes most, with a nod to the time of day. None for a new profile.
        for ((name, title) in Taste.forYou(Taste.current(ctx), Seasons.kids(ctx), HomePicks.moment().genres)) {
            val spec = GenreSpec.find(name) ?: continue
            row(title, RowKind.STANDARD, load = {
                val shelf = Shelf(title, Shelf.Mode.VARIETY, GenreShelves.clause(spec, "movie"), GenreShelves.clause(spec, "tv"))
                val l = RowPaging.all({ trending() }, { GenreShelves.load(ctx, spec, shelf) })[1]
                claims.take(title, l.filter { it.backdropPath != null || it.posterPath != null }).takeIf { it.size >= MIN_ROW }
            }) { _, list -> titleCards(list) { kit.landscape(it) } }
        }

        // The big services (installed ones first). Dream OS plays them all, installed or not.
        val installed = apps.map { it.pkg }.toSet()
        val big = HomePicks.HOME_SERVICES.mapNotNull { n -> Tmdb.SERVICES.firstOrNull { it.name == n } }
        val services = (big.filter { it.pkg in installed } + big).distinctBy { it.name }.take(MAX_SERVICES)
        for ((i, svc) in services.withIndex()) {
            val title = "Popular on ${svc.name}"
            row(title, RowKind.STANDARD, load = {
                trending()
                // Shows and movies interleaved in TMDB's popularity order; take ranks them (steadied quality and taste):
                // a plain rating sort let an 8.3 from 131 votes lead over Breaking Bad.
                val (tv, mv) = RowPaging.all({ Tmdb.onService(ctx, svc.providerId, "tv") }, { Tmdb.onService(ctx, svc.providerId, "movie") })
                claims.take(title, RowMix.interleave(listOf(tv, mv)), 16)
                    .takeIf { it.size >= MIN_ROW }
            }) { _, list -> titleCards(list) { kit.landscape(it) } }
            when (i) {
                1 -> comingSoon()
                2 -> spotlight("Coming Soon", load = {
                    Tmdb.list(ctx, "/discover/movie", Discover.query(DiscoverFilters(type = "movie", sort = Discover.SORT_SOON)), "movie")
                        .filter { it.date.isNotEmpty() && !RowMix.animeHidden(ctx, it) }.drop(16).take(8) // anime stays in its sections
                }, pick = { l -> l.maxByOrNull { it.votes } })
                3 -> seeds.getOrNull(1)?.let { becauseRow(it) }
            }
        }
        endCap()
        refreshContinue()
        refreshMyList()
        loadNewEpisodes()
        Net.background({ HomePicks.featured(ctx) }) { list ->
            if (!list.isNullOrEmpty() && !released) h.setTitles(list) { t -> HomePicks.moment().name + if (t.type == "tv") "  ·  Series" else "  ·  Movie" }
        }
    }

    /**
     * Top 10 and Trending Now as first claimed. Every row's loader asks for them first so their titles stay theirs;
     * once claimed, the answer is kept (each ask was two chart lists tidied and ranked again, and a deep row's later
     * page fetched other pages of the charts just to claim nothing).
     */
    private fun top10(): List<Title> {
        top10Now?.let { return it }
        if (RowPaging.page > 1) return RowMix.top10(ctx, claims)
        return synchronized(charts) { top10Now ?: RowMix.top10(ctx, claims).also { top10Now = it } }
    }

    /** Trending Now (claims right after Top 10). Its own later pages are fetched as usual. */
    private fun trending(): List<Title> {
        trendingNow?.let { if (RowPaging.page == 1 || RowPaging.foreign("Trending Now")) return it }
        if (RowPaging.page > 1) return trendingList()
        return synchronized(charts) { trendingNow ?: trendingList().also { trendingNow = it } }
    }

    /** Only what's out: the chart lists films months ahead. */
    private fun trendingList(): List<Title> {
        top10()
        return claims.take("Trending Now", (Tmdb.trending(ctx) + Tmdb.list(ctx, "/trending/all/week", "&page=2")).filter { RowMix.released(it) })
    }

    /** Coming Soon: the most anticipated movies and series of the next months, soonest first, their date under each. */
    private fun comingSoon() {
        row("Coming Soon", RowKind.STANDARD, load = {
            val soon = listOf("movie", "tv").flatMap { type ->
                Tmdb.list(ctx, "/discover/$type", Discover.query(DiscoverFilters(type = type, sort = Discover.SORT_SOON)), type)
            }.filter { it.date.isNotEmpty() && it.backdropPath != null }
            claims.take("Coming Soon", soon, 16).sortedBy { it.date }.takeIf { it.size >= MIN_ROW }
        }) { _, list -> titleCards(list) { t -> kit.landscape(t, sub = listOfNotNull(Discover.shortDate(t.date).ifEmpty { null }, if (t.type == "tv") "Series" else "Movie").joinToString(" · ")) } }
    }

    /** A big card's kicker: what makes it worth a look (new, acclaimed), else its genre. */
    private fun bigKicker(t: Title): String {
        // Released within the last two months: "New" (worked out per call: the launcher runs for weeks).
        val recent = t.date.isNotEmpty() && t.date >= DreamTime.ymdPlus(-60) && t.date <= DreamTime.ymdPlus(0)
        return when {
            recent -> if (t.type == "tv") "New Series" else "New Movie"
            t.rating >= 7.8 && t.votes >= 200 -> "Critically Acclaimed"
            else -> t.genreIds.firstNotNullOfOrNull { Discover.genreName(t.type, it) } ?: if (t.type == "tv") "Series" else "Movie"
        }
    }


    // ---- no TMDB key: how to get movies and shows here (apps live on the Apps tab) ----

    private fun noKey() {
        add(kit.empty(LineIcon.FILM, "Welcome to Dream OS", "Add a TMDB key in Settings to see movies and shows here. Your apps are on the Apps tab.").apply {
            setPadding(0, 120.dp, 0, 0)
        })
    }

    // ---- Continue Watching, New Episodes, My List ----

    /**
     * Rebuilds Continue Watching when the history changed. Focus resting on the row (back from the player, on the
     * card that was played) doesn't hold it back: the row shows the history as it is now, with focus kept on the
     * same title ([CinemaRow.fill]'s focusTo). Only while the remote is in use does the rebuild wait a moment.
     */
    fun refreshContinue(force: Boolean = false) {
        val r = continueRow ?: return
        if (WatchHistory.version == continueShown) return
        // The first fill comes from the saved history at once, even under the boot intro (before onResume), so the
        // row is laid out before Home shows; later changes rebuild it in place once Home is in front.
        if (!force && continueShown >= 0 && !host.resumed()) return
        val focused = r.hasFocus()
        if (!force && continueShown >= 0 && focused && PreResolve.userBusy()) {
            shell.post(continueLater, 1_600)
            return
        }
        continueShown = WatchHistory.version
        val list = WatchHistory.entries(ctx).take(15)
        val wasOn = if (focused) continueKeys.getOrNull(r.index) else null
        continueKeys = list.map { it.key }
        if (list.isEmpty()) { setShown(r, false); return }
        val apps = host.apps()
        val focusTo = if (!focused) -1 else list.indexOfFirst { it.key == wasOn }.takeIf { it >= 0 } ?: minOf(r.index, list.size - 1)
        r.fill(list.map { e ->
            kit.continueCard(e, apps).also { (_, c) ->
                c.setOnClickListener { host.resume(e) }
                c.isLongClickable = true
                c.setOnLongClickListener { host.continueMenu(e, e.toTitle(), c); true }
            }
        }, animate = false, focusTo = focusTo)
        setShown(r, true)
        window()
    }

    private val continueLater = Runnable { if (!released) refreshContinue() }

    /** A title was removed from Continue Watching: rebuild now, focus on the card that took its place. */
    fun continueRemoved() {
        val r = continueRow ?: return
        val had = r.hasFocus()
        val i = r.index
        refreshContinue(force = true)
        if (!had) return
        shell.post(Runnable {
            if (r.visibility == View.GONE || r.cards.getOrNull(minOf(i, r.cards.size - 1))?.requestFocus() != true) focusFirst()
        }, 60)
    }

    private fun refreshMyList() {
        val r = myListRow ?: return
        val list = Tmdb.myList(ctx)
        val sig = list.joinToString { it.key }
        if (sig == myListShown || r.hasFocus()) return
        myListShown = sig
        if (list.isEmpty()) { setShown(r, false); return }
        r.fill(titleCards(list.take(20)) { kit.landscape(it) }, animate = false)
        setShown(r, true)
        window()
    }

    private fun loadNewEpisodes() {
        val r = newEpRow ?: return
        Net.background({ NewEpisodes.load(ctx, refresh = false) }) { cached ->
            cached?.let { fillNewEpisodes(r, it) }
            Net.background({ NewEpisodes.load(ctx) }) { fresh -> if (fresh != null) fillNewEpisodes(r, fresh) }
        }
    }

    private fun fillNewEpisodes(r: CinemaRow, s: NewEpisodes.Snapshot) {
        if (released) return
        newEpBanner?.update(s)
        val shown = (s.recent + s.upcoming).joinToString { it.key + NewEpisodes.isNew(ctx, it) }
        if (shown == newEpShown || r.hasFocus()) return
        newEpShown = shown
        if (s.isEmpty) { setShown(r, false); return }
        r.header?.text = if (s.recent.isEmpty()) "Coming Up" else "New Episodes for You"
        r.fill((s.recent + s.upcoming.take(10)).map { item ->
            kit.episode(item).also { (_, c) ->
                // An episode card: upcoming ones say when; aired ones carry a status only while new (EPISODE_DAYS).
                if (item.upcoming || Availability.recentEpisode(item.ep.at, item.ep.airDate)) Availability.attach(c, Availability.episode(item))
                c.setOnClickListener {
                    NewEpisodes.markSeen(ctx, item)
                    if (item.upcoming) host.openDetails(item.show.title(), c) else host.episodeChoice(item)
                }
                c.isLongClickable = true
                c.setOnLongClickListener { host.titleMenu(item.show.title()); true }
                if (NewEpisodes.isNew(ctx, item)) c.onFocus { has -> if (has) NewEpisodes.markSeen(ctx, item) }
            }
        }, animate = false)
        setShown(r, true)
        window()
    }

    private fun seedsNow() = BecauseYouWatched.key(ctx, shows = false) + "/" + BecauseYouWatched.key(ctx, shows = true)
    override fun stale(): Boolean = seedKey.let { it != null && it != seedsNow() }

    override fun onShow() {
        super.onShow()
        refreshContinue()
        refreshMyList()
    }

    override fun resume() {
        if (!showing()) return
        super.resume()
        refreshContinue()
        refreshMyList()
        loadNewEpisodes()
    }

    companion object {
        /** "Popular on" rows: Home's services ([HomePicks.HOME_SERVICES]), installed first, this many. */
        const val MAX_SERVICES = 6

        /** The services Home, Movies, TV Shows and genre pages have rows for: Dream OS plays them all, installed or not. */
        fun services(installed: Set<String>): List<String> {
            val big = HomePicks.HOME_SERVICES.mapNotNull { n -> Tmdb.SERVICES.firstOrNull { it.name == n } }
            return (big.filter { it.pkg in installed } + big).map { it.name }.distinct().take(MAX_SERVICES)
        }
    }
}

/**
 * Movies and TV Shows: a smaller hero, Hulu's category chips, and rows for the chip that is current.
 * Featured is the editorial mix (trending as big cards, Top 10, new, acclaimed, coming soon, hidden gems,
 * the installed services); a genre chip turns every row to that genre. Focus resting on a chip switches
 * the rows below it (and the hero takes that category's own featured titles).
 */
class CinemaCategory(shell: CinemaShell, private val kind: String) : CinemaRowsPage(shell) {
    private val movie = kind == "movie"
    private val genres = if (movie) MOVIE_CHIPS else TV_CHIPS
    private var chips: CinemaChips? = null
    private var rowsFrom = 0
    private var claims = RowClaims(ctx)
    private var seedKey: String? = null
    private val label = if (movie) "Movies" else "TV Shows"
    /** All's Continue Watching for this type (gone while there's none), and the history version it shows. Declared before init, which fills them. */
    private var continueRow: CinemaRow? = null
    private var continueShown = -1L

    init {
        if (!Tmdb.hasKey(ctx)) {
            add(kit.empty(if (movie) LineIcon.FILM else LineIcon.TV, "Add a TMDB Key", "Open Settings and add a TMDB key to browse ${label.lowercase()} here.").apply {
                setPadding(0, 120.dp, 0, 0)
            })
        } else {
            hero = CinemaHero(kit, host, COMPACT_H, compact = true).also { add(it, gap = 0) }
            chips = CinemaChips(kit, listOf("All") + genres.map { it.first }) { i -> rows(i) }.also { add(it, top = -6, gap = 24) }
            rowsFrom = sectionCount()
            rows(0)
        }
    }

    private fun filters(f: DiscoverFilters.() -> Unit = {}) = DiscoverFilters(type = kind).apply(f)

    private fun discover(f: DiscoverFilters, extra: String = "") = Tmdb.list(ctx, "/discover/$kind", Discover.query(f) + extra, kind)

    /** A standard row of titles, minus those another row shows; left out with fewer than [MIN_ROW]. */
    private fun titles(title: String, kind: RowKind = RowKind.STANDARD, lazy: Boolean = true, load: () -> List<Title>) =
        row(title, kind, lazy, load = { claims.take(title, load().filter { it.backdropPath != null || it.posterPath != null }).takeIf { it.size >= MIN_ROW } }) { _, list ->
            titleCards(list) { if (kind == RowKind.BIG) kit.big(it, it.genreIds.firstNotNullOfOrNull { g -> Discover.genreName(this.kind, g) } ?: label) else kit.landscape(it) }
        }

    private fun rows(i: Int) {
        removeFrom(rowsFrom)
        // The Anime chip's rows are anime's own section; anywhere else anime stays out.
        claims = RowClaims(ctx, anime = i > 0 && genres[i - 1].second == Discover.ANIME)
        if (i == 0) featured() else genre(genres[i - 1])
        endCap()
        onShowRows()
    }

    private fun onShowRows() {
        if (showing()) view.post { if (!released) window() }
    }

    /**
     * Continue Watching as the history is now: built with the page, rebuilt when the page shows again after the
     * history changed (it used to keep the progress and episodes it was built with until the page was rebuilt).
     */
    private fun refreshContinue() {
        val r = continueRow?.takeIf { it.parent != null } ?: return
        if (WatchHistory.version == continueShown || r.hasFocus()) return
        continueShown = WatchHistory.version
        val mine = WatchHistory.entries(ctx).filter { it.isShow == !movie }.take(15)
        if (mine.isEmpty()) { setShown(r, false); return }
        val apps = host.apps()
        r.fill(mine.map { e -> kit.continueCard(e, apps).also { (_, c) -> c.setOnClickListener { host.resume(e) }; c.setOnLongClickListener { host.continueMenu(e, e.toTitle(), c); true } } }, animate = false)
        setShown(r, true)
    }

    override fun onShow() {
        super.onShow()
        refreshContinue()
    }

    /**
     * "All": Continue Watching for this type, Trending This Week, up to three Because You Watched rows
     * spread between Top 10, New Releases, Coming Soon and Critically Acclaimed, Hidden Gems, Popular on each
     * installed service, every genre ([MOVIE_ROWS] / [TV_ROWS] names), anime and the decades. Each title shows once.
     */
    private fun featured() {
        val h = hero
        Net.background({ Tmdb.list(ctx, "/trending/$kind/week", type = kind).filter { it.backdropPath != null && RowMix.released(it) && !RowMix.animeHidden(ctx, it) }.take(6) }) { list ->
            if (!list.isNullOrEmpty() && !released && chips?.current == 0) h?.setTitles(list) { "Trending in $label" }
        }
        continueRow = newRow("Continue Watching", RowKind.CONTINUE).also { add(it) }
        continueShown = -1L
        refreshContinue()
        val seeds = BecauseYouWatched.pick(ctx, shows = !movie)
        seedKey = seeds.key
        // Never recommend what's watched, finished, saved or in progress, nor what the trending rows show.
        val skip = HashSet<String>().apply { WatchHistory.recent(ctx).forEach { add(it.key) }; Tmdb.myList(ctx).forEach { add(it.key) } }
        val because = seeds.entries.iterator()
        fun becauseRow() {
            val e = if (because.hasNext()) because.next() else return
            val title = "Because You Watched ${e.title}"
            row(title, RowKind.STANDARD, load = {
                val got = RowPaging.all(
                    { Tmdb.list(ctx, "/trending/$kind/week", type = kind).take(20) + Tmdb.list(ctx, "/trending/$kind/day", type = kind).take(10) },
                    { RowMix.recs(ctx, kind, e.tmdbId, e.year) },
                )
                val near = got[0]
                val recs = got[1]
                    .filter { (it.backdropPath != null || it.posterPath != null) && it.key !in skip && near.none { n -> n.key == it.key } }
                claims.take(title, recs).takeIf { it.size >= MIN_ROW }
            }) { _, list -> titleCards(list) { kit.landscape(it) } }
        }

        titles("Trending This Week", RowKind.BIG, lazy = false) { Tmdb.list(ctx, "/trending/$kind/week", type = kind).filter { RowMix.released(it) } }
        becauseRow()
        row("Top 10 Today", RowKind.RANKED, load = {
            RowMix.top10(ctx, claims, kind).takeIf { it.size >= MIN_ROW }
        }) { _, list -> list.mapIndexed { i, t -> kit.ranked(t, i + 1).also { wire(it.second, t) } } }
        // New to watch at home (digital releases; new series and seasons), then what's only in theaters so far.
        titles("New Releases") { NewReleases.ofType(ctx, kind).also { if (RowPaging.page == 1) NewReleases.log(kind, it) } }
        // TV: running shows with a new season out, apart from the brand-new series above.
        if (!movie) row("New Episodes", RowKind.STANDARD, load = {
            claims.take("New Episodes", NewReleases.returning(ctx).filter { it.backdropPath != null || it.posterPath != null }).takeIf { it.size >= MIN_ROW }
        }) { _, list -> titleCards(list) { kit.landscape(it, sub = NewReleases.episodeSub(it)) } }
        // Always there: Trending This Week above can claim most of what's in theaters (Home does the same).
        if (movie) row("In Theaters", RowKind.STANDARD, load = {
            claims.takeAtLeast("In Theaters", NewReleases.inTheaters(ctx).filter { it.backdropPath != null || it.posterPath != null }, MIN_ROW)
        }) { _, list -> titleCards(list) { kit.landscape(it) } }
        becauseRow()
        row("Coming Soon", RowKind.STANDARD, load = {
            claims.take("Coming Soon", discover(filters { sort = Discover.SORT_SOON }).filter { it.date.isNotEmpty() }).sortedBy { it.date }.takeIf { it.size >= MIN_ROW }
        }) { _, list -> titleCards(list) { t -> kit.landscape(t, sub = listOfNotNull(Discover.shortDate(t.date).ifEmpty { null }, if (t.type == "tv") "Series" else "Movie").joinToString(" · ")) } }
        titles("Critically Acclaimed") { RowMix.acclaimed(ctx, kind, "", "Critically Acclaimed $kind") }
        becauseRow()
        titles("Hidden Gems") { RowMix.hiddenGems(ctx, kind, "", "Hidden Gems $kind") }
        if (!movie) {
            // Well-loved series with seasons to sink into: 7.8+ from 1,000+ people, from the last twelve years.
            titles("Bingeworthy Shows") {
                val q = "&include_adult=false&sort_by=popularity.desc&vote_count.gte=1000&vote_average.gte=7.8&first_air_date.gte=${RowMix.yearsAgo(12)}" +
                    "&without_genres=10763,10764,10766,10767,10762"
                RowPaging.all({ Tmdb.list(ctx, "/discover/tv", q, "tv") }, { Tmdb.list(ctx, "/discover/tv", "$q&page=2", "tv") }).flatten()
            }
        }
        // A category in season (Halloween, Holiday) comes before the services.
        val (seasonal, themes) = GenreSpec.CATEGORIES.filter { it.inSeason() }.partition { it.name == "Halloween" || it.name == "Holiday" }
        for (c in seasonal) categoryRow(c)
        // Home's services (installed ones first): Dream OS plays them all, installed or not.
        val installed = host.apps().map { it.pkg }.toSet()
        for (name in CinemaHome.services(installed)) titles("Popular on $name") { discover(filters { service = name }, RowMix.recent(kind)) }
        // Every genre (friendly names) with Browse's categories of this type among them (the most
        // watched first), then anime and the decades. The genres come in the order this profile would want them
        // (loved genres first, untouched ones last; ties keep TMDB's order); a new profile sees the plain order.
        val names = if (movie) MOVIE_ROWS else TV_ROWS
        val early = themes.filter { it.name in EARLY_THEMES }.sortedBy { EARLY_THEMES.indexOf(it.name) }
        val taste = Taste.current(ctx)
        val plain = Discover.genres(kind)
        val ordered = if (taste.strength < 0.25) plain
            else plain.withIndex().sortedWith(compareByDescending<IndexedValue<Discover.Genre>> { taste.genre[it.value.id] ?: 0.0 }.thenBy { it.index }).map { it.value }
        for ((i, g) in ordered.withIndex()) {
            val title = names[g.id] ?: g.name
            titles(title) { RowMix.variety(ctx, kind, Discover.query(filters { genre = g.id }), title) }
            // After every second genre, one of the leading categories (Adult Animation, K-Dramas, Sitcoms...).
            if (i % 2 == 1) early.getOrNull(i / 2)?.let { categoryRow(it) }
        }
        val anime = if (movie) "Anime Movies" else "Anime Series"
        titles(anime) { RowMix.variety(ctx, kind, Discover.query(filters { genre = 16; lang = "ja" }), anime) }
        for (c in themes.filter { it !in early.take(Discover.genres(kind).size / 2) }) categoryRow(c)
        titles("’70s Classics") { discover(filters { year = "1970s" }) }
        titles("’80s Classics") { discover(filters { year = "1980s" }) }
        titles("’90s Favorites") { discover(filters { year = "1990s" }) }
        titles("2000s Hits") { discover(filters { year = "2000s" }) }
    }

    /** One of Browse's categories ([GenreSpec.CATEGORIES]) as a row of this type ("Adult Animated Series"), if it has this type. */
    private fun categoryRow(c: GenreSpec) {
        val shelf = GenreShelves.ofType(c, kind) ?: return
        titles(shelf.title) { GenreShelves.load(ctx, c, shelf) }
    }

    private fun genre(g: Pair<String, Int>) {
        val (name, id) = g
        val h = hero
        val popular = filters { genre = id; sort = Discover.SORT_POPULAR }
        Net.background({ discover(popular).filter { it.backdropPath != null && RowMix.released(it) && (id == Discover.ANIME || !RowMix.animeHidden(ctx, it)) }.take(6) }) { list ->
            if (!list.isNullOrEmpty() && !released && chips?.current != 0) h?.setTitles(list) { name }
        }
        titles("Popular in $name", RowKind.BIG, lazy = false) { discover(popular) }
        titles("New Releases", lazy = false) { NewReleases.ofType(ctx, kind, Discover.clause(kind, id).orEmpty()) }
        if (kind == "tv") titles("New Episodes") { NewReleases.returning(ctx, Discover.clause(kind, id).orEmpty()) }
        titles("Top Rated") { discover(filters { genre = id; sort = Discover.SORT_TOP }) }
        titles("Fan Favorites") { RowMix.variety(ctx, kind, Discover.query(filters { genre = id }), "$name $kind favorites") }
        titles("Hidden Gems") { RowMix.hiddenGems(ctx, kind, Discover.clause(kind, id).orEmpty(), "Hidden Gems $name $kind") }
        titles("2010s Hits") { discover(filters { genre = id; year = "2010s" }) }
        titles("2000s Hits") { discover(filters { genre = id; year = "2000s" }) }
        titles("’90s Classics") { discover(filters { genre = id; year = "1990s" }) }
    }

    /** A new day, or something new watched: the next visit builds the page fresh. */
    override fun stale(): Boolean = seedKey.let { it != null && it != BecauseYouWatched.key(ctx, shows = !movie) }

    private companion object {
        /** Each genre's row name in "All", by its TMDB genre id. */
        val MOVIE_ROWS = mapOf(
            28 to "Action Movies", 12 to "Adventure Movies", 16 to "Animated Movies", 35 to "Comedies", 80 to "Crime Movies",
            99 to "Documentaries", 18 to "Dramas", 10751 to "Family Movies", 14 to "Fantasy Movies", 36 to "Historical Movies",
            27 to "Horror Movies", 10402 to "Music & Musicals", 9648 to "Mysteries", 10749 to "Romantic Movies",
            878 to "Sci-Fi Movies", 53 to "Thrillers", 10752 to "War Movies", 37 to "Westerns",
        )

        val TV_ROWS = mapOf(
            10759 to "Action & Adventure Series", 16 to "Animated Series", 35 to "Comedy Series", 80 to "Crime Series",
            99 to "Docuseries", 18 to "Drama Series", 10751 to "Family Series", 10762 to "Shows for Kids", 9648 to "Mystery Series",
            10764 to "Reality TV", 10765 to "Sci-Fi & Fantasy Series", 10766 to "Soap Operas", 10767 to "Talk Shows",
            10768 to "War & Politics Series", 37 to "Western Series",
        )

        /** The genre chips, then Browse's leading categories for the type (their Discover ids: keywords and blends). */
        val MOVIE_CHIPS = listOf("Action" to 28, "Comedy" to 35, "Drama" to 18, "Sci-Fi" to 878, "Thriller" to 53, "Horror" to 27,
            "Animation" to 16, "Family" to 10751, "Romance" to 10749, "Crime" to 80, "Documentary" to 99,
            "Anime" to Discover.ANIME, "Adult Animation" to Discover.ADULT_ANIMATION_MOVIE, "Superheroes" to Discover.SUPERHERO_MOVIE,
            "Stand-Up" to Discover.STAND_UP, "True Stories" to Discover.TRUE_STORY)
        val TV_CHIPS = listOf("Drama" to 18, "Comedy" to 35, "Crime" to 80, "Sci-Fi & Fantasy" to 10765, "Action & Adventure" to 10759,
            "Mystery" to 9648, "Animation" to 16, "Reality" to 10764, "Documentary" to 99, "Kids" to 10762,
            "Anime" to Discover.ANIME, "Adult Animation" to Discover.ADULT_ANIMATION_TV, "Sitcoms" to Discover.SITCOM,
            "True Crime" to Discover.TRUE_CRIME, "Superheroes" to Discover.SUPERHERO_TV)
        /** Categories woven in among the genre rows on All, in this order; the rest follow the genres. */
        val EARLY_THEMES = listOf("Adult Animation", "K-Drama", "True Crime", "Sitcoms", "Superheroes", "Based on a True Story", "Stand-Up Comedy", "Feel-Good")
    }
}

/**
 * One service (a brand tile in Browse): its hero, then Popular, its Originals, New Releases, TV Shows,
 * Movies, acclaimed and hidden titles and its genres, from TMDB's US subscription catalog for it
 * (whether or not its app is installed: Dream OS plays it either way).
 */
class CinemaService(shell: CinemaShell, private val spec: ServiceSpec) : CinemaRowsPage(shell) {
    /** Crunchyroll is anime's own service: its rows keep it (anywhere else anime stays in the anime rows). */
    private val claims = RowClaims(ctx, anime = spec.name == "Crunchyroll")

    init {
        if (Tmdb.hasKey(ctx)) build()
        else add(kit.empty(LineIcon.TV, "Add a TMDB Key", "Open Settings and add a TMDB key to browse ${spec.name}.").apply { setPadding(0, 120.dp, 0, 0) })
    }

    private fun filters(type: String, f: DiscoverFilters.() -> Unit = {}) = filters(spec, type, f)

    private fun mixed(extra: String = "", f: DiscoverFilters.() -> Unit = {}): List<Title> = mixed(ctx, spec, extra, f)

    private fun titles(title: String, kind: RowKind = RowKind.STANDARD, lazy: Boolean = true, min: Int = MIN_ROW, load: () -> List<Title>) =
        row(title, kind, lazy, load = { claims.take(title, load().filter { it.backdropPath != null || it.posterPath != null }).takeIf { it.size >= min } }) { _, list ->
            titleCards(list) { if (kind == RowKind.BIG) kit.big(it, spec.name) else kit.landscape(it) }
        }

    private fun build() {
        val h = CinemaHero(kit, host, COMPACT_H, compact = true)
        hero = h
        add(h)
        Net.background({ heroTitles(ctx, spec) }) { list ->
            if (!list.isNullOrEmpty() && !released) h.setTitles(list) { "On ${spec.name}" }
        }
        titles("Popular on ${spec.name}", RowKind.BIG, lazy = false) { popular(ctx, spec) }
        if (spec.networks.isNotEmpty()) titles("${spec.name} Originals", lazy = false) { originals(ctx, spec) }
        titles("New Releases") { newReleases(ctx, spec) }
        titles("TV Shows") { RowMix.variety(ctx, "tv", Discover.query(filters("tv")), "${spec.name} TV Shows") }
        titles("Movies") { RowMix.variety(ctx, "movie", Discover.query(filters("movie")), "${spec.name} Movies") }
        titles("Critically Acclaimed") { mixed("&page=${RowMix.dailyPage("Critically Acclaimed ${spec.name}", 3)}") { sort = Discover.SORT_TOP } }
        titles("Hidden Gems") {
            val extra = "&with_watch_providers=${Discover.serviceIds(spec.name)}&watch_region=US&with_watch_monetization_types=flatrate"
            RowMix.interleave(RowPaging.all(*listOf("tv", "movie").map { type -> { RowMix.hiddenGems(ctx, type, extra, "Hidden Gems ${spec.name}") } }.toTypedArray()))
        }
        // The genres and Browse's categories on this service, movies and shows together ("Horror on HBO Max",
        // "Adult Animation on Hulu"): the most watched first, a category in season leading. Each loads as focus
        // nears it, its lists side by side; a row needs a few more titles than usual to show.
        for (g in categories()) GenreShelves.onService(g, spec.name)?.let { shelf ->
            titles(shelf.title, min = CATEGORY_MIN) { GenreShelves.load(ctx, g, shelf) }
        }
        endCap()
        // The rows come from where-to-watch data (JustWatch, through TMDB): its credit, small and quiet, at the very end.
        add(kit.label(CinemaDetails.JUSTWATCH, 12.5f, Ui.alpha(kit.text, 0.45f), kit.font).apply {
            setPadding(CinemaStyle.GUTTER.dp, 0, CinemaStyle.GUTTER.dp, 0)
        }, top = 4, gap = 24)
    }

    /** The loads behind the page's first rows, shared with [prefetch] (each blends its lists side by side, [RowPaging.all]). */
    companion object {
        private const val CATEGORY_MIN = 8
        private val ORDER = listOf("Halloween", "Holiday", "Comedy", "Drama", "Action", "Sci-Fi", "Crime", "Thriller", "Horror", "Anime",
            "Adult Animation", "True Crime", "K-Drama", "Mystery", "Romance", "Sitcoms", "Superheroes", "Animation", "Documentary",
            "Reality", "Stand-Up Comedy", "Based on a True Story", "Feel-Good", "Mind-Bending", "Family", "Kids", "Sports", "Teen",
            "Fantasy", "Adventure", "Musicals", "LGBTQ+", "’80s Classics", "’90s Classics")

        /** The service page's genre and category rows, in [ORDER] (those in season). */
        private fun categories(): List<GenreSpec> {
            val all = GenreSpec.all()
            return ORDER.mapNotNull { n -> all.firstOrNull { it.name == n } }
        }

        private fun filters(spec: ServiceSpec, type: String, f: DiscoverFilters.() -> Unit = {}) = DiscoverFilters(type = type, service = spec.name).apply(f)

        private fun mixed(ctx: android.content.Context, spec: ServiceSpec, extra: String = "", f: DiscoverFilters.() -> Unit = {}): List<Title> =
            RowMix.interleave(RowPaging.all(*listOf("tv", "movie").map { type ->
                { Tmdb.list(ctx, "/discover/$type", Discover.query(filters(spec, type, f)) + extra, type) }
            }.toTypedArray()))

        private fun heroTitles(ctx: android.content.Context, spec: ServiceSpec) = mixed(ctx, spec) { rating = 7 }
            .filter { it.backdropPath != null && RowMix.released(it) && (spec.name == "Crunchyroll" || !RowMix.animeHidden(ctx, it)) }.take(6)

        private fun popular(ctx: android.content.Context, spec: ServiceSpec) = RowPaging.all({ mixed(ctx, spec) }, { mixed(ctx, spec, "&page=2") }).flatten()

        private fun originals(ctx: android.content.Context, spec: ServiceSpec): List<Title> {
            val q = "&include_adult=false&sort_by=popularity.desc&with_networks=${spec.networks}"
            return RowPaging.all({ Tmdb.list(ctx, "/discover/tv", q, "tv") }, { Tmdb.list(ctx, "/discover/tv", "$q&page=2", "tv") }).flatten()
        }

        /** Recent digital releases, new series and new seasons on the service (NewReleases.kt), not the newest-dated (often unreleased) titles. */
        private fun newReleases(ctx: android.content.Context, spec: ServiceSpec) = NewReleases.onService(ctx, spec.name)
            .also { if (RowPaging.page == 1) NewReleases.log(spec.name, it) }

        /**
         * Fetches what [spec]'s page shows first (its hero and the rows that load with it) into TMDB's cache,
         * on a background thread: focus resting on its tile in Browse, so the page usually opens filled.
         */
        fun prefetch(ctx: android.content.Context, spec: ServiceSpec) {
            try {
                RowPaging.all({ heroTitles(ctx, spec) }, { popular(ctx, spec) }, { if (spec.networks.isNotEmpty()) originals(ctx, spec) else emptyList() }, { newReleases(ctx, spec) })
            } catch (e: Exception) {}
        }
    }
}

/**
 * Apps: a title on the page's line and a calm grid of the apps' own banners, five across, the name under
 * each. (My List is [CinemaMyList].)
 * The grid scrolls to keep the focused row in view (no transforms, so plain focus search works).
 */
class CinemaGrid(shell: CinemaShell, private val tab: HomeShell.Tab) : CPage(shell) {
    private val col = FlatLinear(ctx).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(0, 0, 0, 64.dp)
    }
    private val scroll = SmoothScrollView(ctx).apply {
        manual = true
        keyScroll = false
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isFillViewport = true
        clipChildren = false
        addView(col)
    }
    override val view: View = FlatFrame(ctx).apply { clipChildren = false; addView(scroll, MATCH_PARENT, MATCH_PARENT) }
    private val gridRows = ArrayList<View>()
    private var target = 0

    init {
        when (tab) {
            HomeShell.Tab.APPS -> {
                val hidden = Apps.hidden(ctx)
                val list = host.apps().filter { it.pkg !in hidden }
                header("Apps", "${list.size} apps" + if (hidden.isNotEmpty()) "  ·  ${hidden.size} hidden in Settings" else "")
                val cols = 5
                val w = cellWidth(cols)
                grid(list.map { a ->
                    kit.app(a, w, w * 9 / 16).also { (_, c) ->
                        c.setOnClickListener { host.launch(a.pkg) }
                        c.isLongClickable = true
                        c.setOnLongClickListener { host.appMenu(a); true }
                    }.first
                }, cols)
            }
            else -> {}
        }
    }

    /** Card width (dp) for [cols] across the page between the gutters. */
    private fun cellWidth(cols: Int): Int {
        val dm = ctx.resources.displayMetrics
        val avail = dm.widthPixels / dm.density - 2 * CinemaStyle.GUTTER
        return ((avail - (cols - 1) * CinemaStyle.CARD_GAP) / cols).toInt()
    }

    private fun header(title: String, sub: String) {
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.gutter, 92.dp, kit.gutter, 22.dp)
            addView(kit.label(title, 30f, kit.text, kit.bold).apply { letterSpacing = -0.01f })
            if (sub.isNotEmpty()) addView(kit.label(sub, 14.5f, kit.dim, kit.medium).apply { setPadding(0, 8.dp, 0, 0) })
        })
    }

    private fun grid(units: List<View>, cols: Int) {
        for ((i, chunk) in units.chunked(cols).withIndex()) {
            val r = FlatLinear(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
                setPadding(kit.gutter, 0, kit.gutter, 0)
                for ((j, u) in chunk.withIndex()) addView(u, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (j < chunk.size - 1) marginEnd = CinemaStyle.CARD_GAP.dp })
            }
            col.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 26.dp })
            gridRows += r
            // The first rows rise in once, a beat apart.
            if (i < 3) Ui.rise(listOf(r), fromDp = 16, startDelay = 60L + i * 50L, duration = 320L)
        }
    }

    override fun focusFirst() {
        val list = ArrayList<View>()
        col.addFocusables(list, View.FOCUS_FORWARD)
        list.firstOrNull()?.requestFocus()
    }

    override fun onFocus(v: View) {
        val r = HomeShell.directChild(v, col) ?: return
        val h = scroll.height.takeIf { it > 0 } ?: kit.screenH
        // Fits on the first screen: the page stays at the top (header and bar). Else the row settles on the line.
        target = if (r.bottom + 24.dp <= h) 0 else (r.top - (CinemaStyle.ROW_LINE + 20).dp).coerceAtLeast(0)
        scroll.glideTo(target, 360)
    }

    override fun barVisible() = target == 0

    override fun toTop() { target = 0; scroll.glideTo(0, 360) }

    override fun handleBack(focused: View): Boolean {
        val first = gridRows.firstOrNull() ?: return false
        if (HomeShell.isInside(focused, first)) return false
        toTop()
        focusFirst()
        return true
    }

}

/** System: storage, memory and network at a glance (the SystemHealth view), under a Cinema header. */
class CinemaSystem(shell: CinemaShell) : CPage(shell) {
    private val content = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(0, 0, 0, 48.dp)
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.gutter, 92.dp, kit.gutter, 12.dp)
            addView(kit.label("System", 30f, kit.text, kit.bold))
            addView(kit.label("Storage, memory and network on this Fire Stick", 14.5f, kit.dim, kit.medium).apply { setPadding(0, 8.dp, 0, 0) })
        })
        addView(host.systemView())
    }
    private val scroll = SmoothScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        clipChildren = false
        margin = 64.dp
        addView(content)
    }
    override val view: View = FlatFrame(ctx).apply { clipChildren = false; addView(scroll, MATCH_PARENT, MATCH_PARENT) }

    override fun focusFirst() {
        val list = ArrayList<View>()
        content.addFocusables(list, View.FOCUS_FORWARD)
        list.firstOrNull()?.requestFocus()
    }

    override fun barVisible() = scroll.scrollY < 40.dp
    override fun toTop() = scroll.glideTo(0)
}

/**
 * One row's way onto the screen, logged once its first visible cards show their art (tag "DreamRows"): how long
 * its list waited for a thread, the TMDB lists themselves, then those cards' art (the longest wait for an art
 * thread, download and decode among them; how many came from memory), and, if focus landed on the row while
 * it was still blank, how long it stayed blank under focus.
 */
class RowTiming(private val name: String, private val queuedAt: Long, private val startedAt: Long, private val listedAt: Long) {
    fun art(arts: List<Net.ArtTiming?>, at: Int, focus: Int, blankSince: Long?) {
        val now = SystemClock.uptimeMillis()
        val fetched = arts.filterNotNull().filter { !it.memory }
        val blank = blankSince?.let { ", blank under focus ${now - it} ms" } ?: ""
        Log.i(TAG, "'$name' (section $at, focus $focus): ${now - queuedAt} ms in all: queue ${maxOf(0L, startedAt - queuedAt)} ms, " +
            "lists ${listedAt - maxOf(queuedAt, startedAt)} ms, art ${now - listedAt} ms for ${arts.size} cards " +
            "(${arts.size - fetched.size} from memory; worst art-thread wait ${fetched.maxOfOrNull { it.waitMs } ?: 0} ms, " +
            "download ${fetched.maxOfOrNull { it.downloadMs } ?: 0} ms, decode ${fetched.maxOfOrNull { it.decodeMs } ?: 0} ms)$blank")
    }

    private companion object { const val TAG = "DreamRows" }
}
