package com.firehome.launcher

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Cinema's My List: the saved titles, organized. A line of chips on top (All · Movies · TV Shows, then
 * Recently Added · A–Z; the choice is kept per profile), then the sections: Continue Watching (saved titles
 * part way through, newest first), Movies and TV Shows, each headed with its count ("TV Shows  ·  12").
 *
 * Each section is a calm poster grid, eight across, its lines flowing on without short lines except the
 * section's last, the title always under each poster on one level line. The grid's lines are ordinary grid
 * rows of the page ([CinemaRow.grid]): Up and Down keep the column, art binds only near the screen, the
 * bottom fade and its chevron come with [CinemaRowsPage], and Back steps to the line's first title, then
 * to the chips, then to the bar.
 *
 * The grid scrolls at its edges ([CinemaRowsPage]): moving among the lines that show whole moves only the
 * focus; the page moves a line's worth only to bring in a line past the bottom fade or cut by the top.
 * A line is 168 dp at 1080p (36 more with its section heading), so at least two whole lines always show.
 *
 * Uses the existing My List storage ([Tmdb.myList], newest first) as is.
 */
class CinemaMyList(shell: CinemaShell) : CinemaRowsPage(shell) {
    private val saved = Tmdb.myList(ctx)
    private val listKey = key(saved)
    private val progressKey = progressSig()
    private var type = ALL
    private var order = RECENT
    private var from = 0

    init {
        load()
        val movies = saved.count { it.type == "movie" }
        val shows = saved.size - movies
        add(header("My List", summary(movies, shows)), gap = 22)
        if (saved.isEmpty()) {
            add(kit.empty(LineIcon.BOOKMARK, "Nothing Saved Yet", "Open any movie or show and choose My List. It will wait for you here."))
        } else {
            add(MyListChips(kit, TYPES.indexOf(type), ORDERS.indexOf(order)) { t, o ->
                type = TYPES[t]
                order = ORDERS[o]
                store()
                build(animate = true)
            }, gap = 30)
            from = sectionCount()
            build(animate = false)
        }
    }

    // ---- the sections ----

    private fun build(animate: Boolean) {
        removeFrom(from)
        val byKey = saved.associateBy { it.key }
        val keep = { t: Title -> type == ALL || t.type == type }
        val going = inProgress().filter { e -> byKey[e.key]?.let(keep) == true }
        val movies = sort(saved.filter { it.type == "movie" })
        val shows = sort(saved.filter { it.type == "tv" })
        val sections = ArrayList<Pair<String, List<Pair<View, CinemaCard>>>>()
        if (going.isNotEmpty()) sections += "Continue Watching" to going.map { e -> continueUnit(e, byKey.getValue(e.key)) }
        if (type != "tv" && movies.isNotEmpty()) sections += "Movies" to movies.map { posterUnit(it) }
        if (type != "movie" && shows.isNotEmpty()) sections += "TV Shows" to shows.map { posterUnit(it) }
        if (type == "movie" && movies.isEmpty()) add(kit.empty(LineIcon.FILM, "No Movies Saved Yet", "Open any movie and choose My List. It will wait for you here."))
        if (type == "tv" && shows.isEmpty()) add(kit.empty(LineIcon.TV, "No TV Shows Saved Yet", "Open any show and choose My List. It will wait for you here."))

        var lines = 0
        val added = ArrayList<View>()
        for ((s, sec) in sections.withIndex()) {
            val (name, units) = sec
            // Full lines only, except the section's very last (the grid flows on, no gaps mid-section).
            val chunks = units.chunked(COLS)
            for ((i, chunk) in chunks.withIndex()) {
                val line = CinemaRow(kit, if (i == 0) name else null).apply {
                    grid = true
                    onCardFocus = { _, card -> host.speculate(card.title) }
                }
                if (i == 0) line.header?.text = heading(name, units.size)
                val lastOfSection = i == chunks.size - 1
                add(line, gap = if (lastOfSection && s < sections.size - 1) SECTION_GAP else CinemaStyle.GRID_GAP)
                line.fill(chunk, animate = false)
                added += line
                lines++
            }
        }
        if (lines > 2) endCap()
        // A new choice rises in, the first lines a beat apart (nothing animates on the first build: it is made hidden).
        if (animate) added.take(2).forEachIndexed { i, v -> Ui.rise(listOf(v), fromDp = 14, startDelay = 40L + i * 60L, duration = 300L) }
        window()
    }

    /** "TV Shows  ·  12": the name in the row title's tone, the count quieter. */
    private fun heading(name: String, n: Int): CharSequence {
        val b = SpannableStringBuilder(name)
        val at = b.length
        b.append("  ·  ").append(n.toString())
        b.setSpan(ForegroundColorSpan(kit.dim), at, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return b
    }

    private fun posterUnit(t: Title): Pair<View, CinemaCard> {
        val w = posterW()
        val card = kit.poster(t, w, w * 3 / 2)
        wire(card, t)
        // The section names the type, so the caption is the title alone (one level line under every poster).
        return kit.unit(card, null, t.name, null) to card
    }

    /** A saved title part way through: its poster with the progress line (or Up Next); OK carries on. */
    private fun continueUnit(e: WatchHistory.Entry, t: Title): Pair<View, CinemaCard> {
        val w = posterW()
        val card = kit.poster(t, w, w * 3 / 2)
        if (e.upNext) card.badge("UP NEXT") else if (e.progress > 0f) card.progress(e.progress)
        card.setOnClickListener { host.resume(e) }
        card.isLongClickable = true
        card.setOnLongClickListener { host.titleMenu(t); true }
        return kit.unit(card, null, t.name, null) to card
    }

    private fun posterW() = kit.fit(POSTER_W)

    private fun sort(list: List<Title>): List<Title> =
        if (order == AZ) list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { sortName(it.name) }) else list

    /** Saved titles that are part way through (or have an episode up next), most recently watched first. */
    private fun inProgress(): List<WatchHistory.Entry> {
        val keys = saved.mapTo(HashSet()) { it.key }
        if (keys.isEmpty()) return emptyList()
        return WatchHistory.entries(ctx).filter { it.key in keys && (it.upNext || it.progress > 0f) }.distinctBy { it.key }
    }

    /** What the Continue Watching posters show: which titles, their episode, Up Next and progress (a whole percent). */
    private fun progressSig() = inProgress().joinToString(",") { "${it.key}@${it.season}:${it.episode}:${it.upNext}:${(it.progress * 100).toInt()}" }

    override fun stale(): Boolean = key(Tmdb.myList(ctx)) != listKey || progressSig() != progressKey

    // ---- the saved choice ----

    private fun load() {
        val v = prefs().getString(PREF + Profiles.suffix(ctx), null)?.split('|')
        type = v?.getOrNull(0)?.takeIf { it in TYPES } ?: ALL
        order = v?.getOrNull(1)?.takeIf { it in ORDERS } ?: RECENT
    }

    private fun store() = prefs().edit().putString(PREF + Profiles.suffix(ctx), "$type|$order").apply()

    private fun prefs() = ctx.getSharedPreferences("cinema", Context.MODE_PRIVATE)

    private fun summary(movies: Int, shows: Int): String {
        if (movies + shows == 0) return ""
        fun n(c: Int, one: String, many: String) = "$c ${if (c == 1) one else many}"
        return listOfNotNull(n(movies + shows, "Title", "Titles"),
            if (movies > 0) n(movies, "Movie", "Movies") else null,
            if (shows > 0) n(shows, "TV Show", "TV Shows") else null).joinToString("  ·  ")
    }

    private companion object {
        const val ALL = "all"
        const val RECENT = "recent"
        const val AZ = "az"
        val TYPES = listOf(ALL, "movie", "tv")
        val ORDERS = listOf(RECENT, AZ)
        const val PREF = "mylist_view"
        /** Eight across (fitted to the page): with the section headings, two whole lines still clear the fade. */
        const val COLS = 8
        const val POSTER_W = 93
        /** Between one section's last line and the next section's heading (dp). */
        const val SECTION_GAP = 30

        fun key(list: List<Title>) = list.joinToString(",") { it.key }

        /** Sorting name: "The Office" files under O, "A Quiet Place" under Q. */
        fun sortName(n: String): String {
            val s = n.trim()
            for (a in listOf("the ", "a ", "an ")) if (s.length > a.length && s.startsWith(a, ignoreCase = true)) return s.substring(a.length)
            return s
        }
    }
}

/**
 * My List's chip line: the type (All · Movies · TV Shows) and, after a quiet divider, the order (Recently
 * Added · A–Z), one current chip in each group. Hulu's chips ([CinemaKit.chip]): OK picks one, and so does
 * resting on it a moment, as on the other pages. Left and Right walk the whole line.
 */
class MyListChips(private val kit: CinemaKit, typeAt: Int, orderAt: Int, private val onPick: (Int, Int) -> Unit) : FlatLinear(kit.ctx), CinemaSection {
    private val types = ArrayList<TextView>()
    private val orders = ArrayList<TextView>()
    private val all = ArrayList<TextView>()
    private var type = typeAt
    private var order = orderAt
    private var rest: Runnable? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(kit.gutter, 0, kit.gutter, 0)
        for ((i, l) in listOf("All", "Movies", "TV Shows").withIndex()) types += chip(l) { pick(i, order) }
        for ((i, l) in listOf("Recently Added", "A–Z").withIndex()) orders += chip(l) { pick(type, i) }
        types.forEach { addView(it) }
        (types.last().layoutParams as LayoutParams).marginEnd = 0
        val d = kit.ctx.resources.displayMetrics.density
        addView(View(kit.ctx).apply { background = Ui.rounded(kit.ctx, Ui.alpha(kit.text, 0.22f), 1) },
            LayoutParams(Math.max(1, d.toInt()), (18 * d).toInt()).apply { marginStart = (18 * d).toInt(); marginEnd = (18 * d).toInt() })
        orders.forEach { addView(it) }
        (orders.last().layoutParams as LayoutParams).marginEnd = 0
        all += types
        all += orders
        mark()
    }

    private fun chip(label: String, onClick: () -> Unit): TextView = kit.chip(label, onClick).also { c ->
        val base = c.onFocusChangeListener
        c.setOnFocusChangeListener { v, has ->
            base?.onFocusChange(v, has)
            rest?.let { removeCallbacks(it) }
            rest = null
            // Resting on a chip picks it, as Hulu's chips do on the other pages.
            if (has) rest = Runnable { if (v.isFocused) v.performClick() }.also { postDelayed(it, REST_MS) }
        }
    }

    private fun pick(t: Int, o: Int) {
        if (t == type && o == order) return
        type = t
        order = o
        mark()
        onPick(t, o)
    }

    private fun mark() {
        for ((i, c) in types.withIndex()) c.isSelected = i == type
        for ((i, c) in orders.withIndex()) c.isSelected = i == order
    }

    override fun focusSearch(focused: View?, direction: Int): View? {
        if (direction == FOCUS_LEFT || direction == FOCUS_RIGHT) {
            val i = all.indexOf(focused)
            if (i >= 0) return all.getOrNull(i + if (direction == FOCUS_RIGHT) 1 else -1) ?: focused
        }
        return super.focusSearch(focused, direction)
    }

    override fun entry(): View? = types.getOrNull(type)

    override fun onDetachedFromWindow() {
        rest?.let { removeCallbacks(it) }
        super.onDetachedFromWindow()
    }

    private companion object { const val REST_MS = 420L }
}
