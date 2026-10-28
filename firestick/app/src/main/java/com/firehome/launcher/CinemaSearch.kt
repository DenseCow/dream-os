package com.firehome.launcher

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Cinema's Search (Apple TV): the field and the Dream keyboard on the left, results on the right as a
 * calm poster grid (Trending Now while the field is empty, Top Results and a person's titles while
 * typing), recent searches and suggestions under the keyboard. The search itself is
 * TMDB's spoken-query search plus a person lookup, debounced, with recent searches per profile;
 * voice comes from the remote's mic button through Alexa ([QueryField.onAlexa] / [searchFor]).
 * A genre or category typed ("horror", "crime shows", "romcom": [GenreSearch]) brings its movies and
 * shows, and a tile into its Browse page, above the title results.
 */
class CinemaSearch(shell: CinemaShell) : CPage(shell) {
    private val pk = shell.premiumKit
    private val queryField = QueryField(ctx).apply {
        hint = HINT
        setHintTextColor(Ui.alpha(kit.text, 0.45f))
        setTextColor(kit.text)
        typeface = kit.medium
        textSize = 18f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(16.dp, 0, 48.dp, 0)
        caretColor = CinemaStyle.glowNow
    }
    /** The search text (the phone remote types here, once the page shows: see [live]). */
    val input: EditText get() = queryField
    /**
     * On screen. Search is built ahead, hidden ([CinemaShell]'s idle prebuild): until it shows it takes no
     * text (the field refuses it), runs no search and moves no focus, whatever reaches it.
     */
    private var live = false
    private val listen = ListenIndicator(pk)
    private val keyboard = DreamKeyboard(pk, queryField, KB_W, KEY_H, KEY_GAP, null).apply {
        // The key highlight takes the rainbow's hue, blending into the next one.
        // The focused key: a pearl box with dark ink and the same fading rainbow edge as every highlight.
        glideStroke = true
        glideColors = { CinemaStyle.glowNow.let { intArrayOf(it, it) } }
        CinemaStyle.followGlow(this) { v, _ -> (v as DreamKeyboard).refreshGlide() }
    }
    private val suggestTitle = kit.kicker("")
    private val suggestList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val resultsTitle = kit.label("", CinemaStyle.T_ROW, kit.text, kit.semibold)
    private val wall = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(0, 18.dp, 0, 40.dp)
    }
    private val scroll = SmoothScrollView(ctx).apply {
        // The page places the focused line itself ([placeLine]): always on the wall's top line.
        manual = true
        keyScroll = false
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        margin = 40.dp
        clipChildren = false
        addView(wall, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
    private val posterW: Int
    private var pending: Runnable? = null
    private var gen = 0
    private var shownQuery: String? = null
    private var remembered: String? = null
    private var focusResultsNext = false
    /** How many suggestions fit under the keyboard at this screen size ([fitSuggestions]). */
    private var maxSuggest = MAX_SUGGEST
    /** The genre search's Browse tile last used: Back from that genre's page lands on it. */
    private var browseTile: View? = null

    override val view: View = object : FlatFrame(ctx) {
        // Left out of the results lands back on the key you came from.
        override fun focusSearch(focused: View?, direction: Int): View? {
            val r = super.focusSearch(focused, direction)
            if (direction == View.FOCUS_LEFT && focused != null && HomeShell.isInside(focused, wall)) {
                return if (r == null || !HomeShell.isInside(r, wall)) keyboard.entryKey() else r
            }
            return r
        }
    }.apply { clipChildren = false; clipToPadding = false }

    init {
        val dm = ctx.resources.displayMetrics
        val wallX = CinemaStyle.GUTTER + KB_W + COL_GAP
        val wallW = dm.widthPixels / dm.density - wallX - CinemaStyle.GUTTER
        posterW = ((wallW - (COLS - 1) * CinemaStyle.CARD_GAP) / COLS).toInt()
        queryField.focusRedirect = { live && keyboard.focusEntry() }
        queryField.onSearch = { q -> if (live) searchFor(q) }
        queryField.onAlexa = { on -> if (live || !on) alexaListening(on) }
        // Hidden, the field keeps what it has: any edit (typed, pasted, replaced, deleted) is refused.
        queryField.filters = queryField.filters + android.text.InputFilter { _, _, _, dest, dstart, dend -> if (live) null else dest.subSequence(dstart, dend) }
        val box = FrameLayout(ctx).apply {
            background = Ui.rounded(ctx, Ui.alpha(Ui.mix(kit.tile, kit.text, 0.06f), 0.9f), CinemaStyle.R_BUTTON, 1f, Ui.alpha(kit.text, 0.12f))
            addView(queryField, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(listen, FrameLayout.LayoutParams(34.dp, 34.dp, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = 7.dp })
        }
        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(box, LinearLayout.LayoutParams(KB_W.dp, FIELD_H.dp))
            addView(keyboard, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = KB_TOP.dp })
            addView(suggestTitle, LinearLayout.LayoutParams(KB_W.dp, WRAP_CONTENT).apply { topMargin = SUGGEST_TOP.dp })
            addView(suggestList, LinearLayout.LayoutParams(KB_W.dp, WRAP_CONTENT).apply { topMargin = LIST_TOP.dp })
        }
        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(resultsTitle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        val root = view as FrameLayout
        root.addView(right, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            leftMargin = wallX.dp; topMargin = TOP.dp; rightMargin = 0
        })
        // Room for the focused poster's lift and glow; the scroller clips at its own (outer) edge.
        Ui.liftRoom(scroll, 24, 24)
        root.addView(left, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = kit.gutter; topMargin = TOP.dp })
        maxSuggest = fitSuggestions(dm.heightPixels / dm.density)
        queryField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = queryChanged()
        })
        showEmpty()
    }

    // ---- typing ----

    private fun query() = queryField.text.toString().trim()

    private fun queryChanged() {
        queryField.invalidate()
        val q = query()
        pending?.let { shell.cancel(it) }
        if (q == shownQuery) return
        if (q.isEmpty()) { showEmpty(); return }
        shownQuery = null
        val g = ++gen
        // Recent searches and genre names suggest at once; titles as soon as TMDB's plain search answers.
        showSuggestions(q, emptyList())
        if (!Tmdb.hasKey(ctx) || q.length < 2) { shownQuery = q; showResults(q, emptyList(), loading = false); return }
        if (wall.tag != "results") showResults(q, emptyList(), loading = true)
        pending = Runnable { searchTitles(q, g) }.also { shell.post(it, DEBOUNCE_MS) }
    }

    /** A genre search's rows: the category (and its Browse tiles), its movies and its shows. */
    private class GenreHits(val match: GenreSearch.Match, val movies: List<Title>, val shows: List<Title>)

    private fun searchTitles(q: String, g: Int) {
        // The suggestions don't wait for the whole search (its spelling variants, a person, a genre's rows):
        // TMDB's plain search is shared with it (one request in flight) and fills them the moment it answers.
        Net.background({ Tmdb.search(ctx, q).filter { it.posterPath != null } }) { list ->
            if (g != gen || query() != q || list.isNullOrEmpty() || shownQuery == q) return@background
            showSuggestions(q, list)
        }
        val genre = GenreSearch.match(q)
        Net.background({
            // A person ("brad pitt movies") is looked up beside the title search, on its own thread; a genre's
            // movies and shows load beside it too (Browse's own category loaders, their lists side by side).
            val person = java.util.concurrent.FutureTask { try { Tmdb.searchPerson(ctx, q) } catch (e: Exception) { null } }
            Thread(person, "search-person").start()
            val got = RowPaging.all<Any?>(
                { Tmdb.searchSpoken(ctx, q).filter { it.posterPath != null } },
                { if (genre?.movies == true) GenreSearch.load(ctx, genre, "movie") else emptyList<Title>() },
                { if (genre?.shows == true) GenreSearch.load(ctx, genre, "tv") else emptyList<Title>() },
            )
            @Suppress("UNCHECKED_CAST")
            val hits = genre?.let { GenreHits(it, got[1] as List<Title>, got[2] as List<Title>) }
            @Suppress("UNCHECKED_CAST")
            Triple(got[0] as List<Title>, person.get(), hits)
        }) { r ->
            if (g != gen || query() != q) return@background
            shownQuery = q
            val person = r?.second
            val titles = r?.first.orEmpty().let { list -> if (person == null) list else list.filter { t -> person.titles.none { it.key == t.key } } }
            showSuggestions(q, titles)
            showResults(q, titles, loading = false, person = person, genre = r?.third)
        }
    }

    // ---- suggestions under the keyboard ----

    /**
     * Netflix-style autocomplete: a genre whose name starts with what's typed ("hor" → Horror), recent
     * searches and titles that match, prefix matches first, no repeats; as many as fit under the keyboard.
     */
    private fun showSuggestions(q: String, titles: List<Title>) {
        fun prefix(s: String) = s.startsWith(q, ignoreCase = true) || s.split(' ', ':', '-').any { it.startsWith(q, ignoreCase = true) }
        val all = RecentSearches.list(ctx).filter { it.contains(q, ignoreCase = true) && !it.equals(q, ignoreCase = true) } + titles.map { it.name }
        val seen = HashSet<String>()
        val genres = GenreSearch.suggest(q, max = 1)
        val picked = (genres + all.filter { prefix(it) } + all.filterNot { prefix(it) })
            .filter { it.isNotBlank() && !it.equals(q, ignoreCase = true) && seen.add(it.lowercase()) }.take(maxSuggest)
        fillSuggestions("", picked, q)
    }

    private fun fillSuggestions(title: String, items: List<String>, q: String = "") {
        val hadFocus = suggestList.hasFocus()
        suggestTitle.text = title.uppercase()
        // No heading while typing: the room goes to one more suggestion.
        suggestTitle.visibility = if (title.isEmpty()) View.GONE else View.VISIBLE
        suggestList.removeAllViews()
        val n = if (title.isEmpty()) maxSuggest else maxOf(1, maxSuggest - 1)
        for ((i, s) in items.take(n).withIndex()) suggestList.addView(suggestion(s, q), LinearLayout.LayoutParams(WRAP_CONTENT, SUGGEST_H.dp).apply { if (i > 0) topMargin = SUGGEST_GAP.dp })
        if (hadFocus) (suggestList.getChildAt(0) ?: keyboard.entryKey()).requestFocus()
    }

    private fun suggestion(s: String, q: String): TextView = kit.chip(s) {
        focusResultsNext = true
        RecentSearches.add(ctx, s)
        queryField.setQuery(s)
    }.apply {
        text = highlight(s, q)
        maxWidth = KB_W.dp
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /** The typed part stays as it is; the rest of the suggestion is set bold (what pressing it adds). */
    private fun highlight(s: String, q: String): CharSequence {
        val at = if (q.isEmpty()) -1 else s.indexOf(q, ignoreCase = true)
        if (q.isEmpty()) return s
        val sp = android.text.SpannableString(s)
        fun bold(from: Int, to: Int) {
            if (from >= to) return
            val span: Any = if (android.os.Build.VERSION.SDK_INT >= 28) android.text.style.TypefaceSpan(kit.bold) else android.text.style.StyleSpan(android.graphics.Typeface.BOLD)
            sp.setSpan(span, from, to, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (at < 0) bold(0, s.length) else { bold(0, at); bold(at + q.length, s.length) }
        return sp
    }

    /**
     * Suggestions that fit between the keyboard and the screen's bottom safe edge at [screenH] dp: the
     * field, the keyboard and the gaps above them are fixed, each suggestion takes [SUGGEST_H] and a gap.
     */
    private fun fitSuggestions(screenH: Float): Int {
        val kb = 6 * KEY_H + 5 * KEY_GAP
        val top = TOP + FIELD_H + KB_TOP + kb + LIST_TOP
        val room = screenH - BOTTOM_SAFE - top + SUGGEST_GAP
        return (room / (SUGGEST_H + SUGGEST_GAP)).toInt().coerceIn(1, MAX_SUGGEST)
    }

    // ---- results ----

    private fun showEmpty() {
        gen++
        shownQuery = ""
        val recent = RecentSearches.list(ctx)
        fillSuggestions(if (recent.isEmpty()) "" else "Recent", recent.take(maxSuggest))
        resetWall("trending")
        if (!Tmdb.hasKey(ctx)) {
            resultsTitle.text = ""
            wall.addView(kit.empty(LineIcon.SEARCH, "Find Something to Watch", "Type with the keyboard, or hold the remote's mic button and say what you're looking for."))
            return
        }
        resultsTitle.text = "Trending Now"
        trending?.let { addPosters(it); return }
        val g = gen
        Net.background({ Tmdb.trending(ctx).filter { it.posterPath != null }.take(MAX_TILES) }) { list ->
            if (list.isNullOrEmpty() || g != gen) return@background
            trending = list
            resetWall("trending")
            addPosters(list)
        }
    }

    private fun showResults(q: String, titles: List<Title>, loading: Boolean, person: Tmdb.PersonResult? = null, genre: GenreHits? = null) {
        val hadFocus = wall.hasFocus()
        resetWall("results")
        browseTile = null
        val genreShown = genre != null && (genre.movies.size + genre.shows.size >= GENRE_MIN)
        when {
            genreShown && genre != null -> {
                // The genre leads (unless a title is named exactly what was typed: then that title does).
                val exact = titles.firstOrNull()?.takeIf { squash(it.name) == squash(q) }
                resultsTitle.text = if (exact != null) "Top Results" else genre.match.target.label
                if (exact != null) { addPosters(titles.take(COLS)); heading(genre.match.target.label) }
                genreSection(genre)
                val rest = if (exact != null) titles.drop(COLS) else titles
                if (person != null && person.titles.isNotEmpty()) { heading(person.heading); addPosters(person.titles.take(MAX_TILES / 2)) }
                if (rest.isNotEmpty()) { heading(if (exact != null) "More Results" else "Top Results"); addPosters(rest.take(MAX_TILES / 2)) }
            }
            person != null -> {
                resultsTitle.text = person.heading
                addPosters(person.titles.take(MAX_TILES))
                if (titles.isNotEmpty()) {
                    wall.addView(kit.label("Top Results", CinemaStyle.T_ROW, kit.text, kit.semibold), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp; bottomMargin = 16.dp })
                    addPosters(titles.take(MAX_TILES / 2))
                }
            }
            titles.isNotEmpty() -> { resultsTitle.text = "Top Results"; addPosters(titles.take(MAX_TILES)) }
            loading -> resultsTitle.text = "Searching…"
            else -> { resultsTitle.text = ""; wall.addView(kit.empty(LineIcon.SEARCH, "No Matches for “$q”", "Try fewer letters, or another spelling.")) }
        }
        if (!loading && (hadFocus || focusResultsNext)) {
            focusResultsNext = false
            firstTile()?.requestFocus()
            wallY = 0
            scroll.glideTo(0)
        }
    }

    /** A heading inside the wall, over the lines it names (placed with them: [placeLine]). */
    private fun heading(text: String) {
        wall.addView(kit.label(text, CinemaStyle.T_ROW, kit.text, kit.semibold), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp; bottomMargin = 16.dp })
    }

    /**
     * A genre search's rows: a tile into the category's Browse page (both, for a pair: "Browse Anime",
     * "Browse Horror"), then its movies and its shows, each under its own heading. Two lines of each when
     * both show; four when the search asked for one type ("horror movies").
     */
    private fun genreSection(h: GenreHits) {
        val tiles = FlatLinear(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            for (spec in GenreSearch.specs(h.match)) addView(kit.chip("Browse ${spec.name}") {
                browseTile = focusedChild
                RecentSearches.add(ctx, query())
                shell.openGenre(spec, fromSearch = true)
            })
        }
        wall.addView(tiles, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 22.dp })
        val both = h.movies.isNotEmpty() && h.shows.isNotEmpty()
        val lines = if (both) 2 else 4
        for ((type, list) in listOf("movie" to h.movies, "tv" to h.shows)) {
            if (list.isEmpty()) continue
            heading(GenreSearch.heading(h.match, type))
            addPosters(list.take(lines * COLS))
        }
    }

    private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Back from a genre page this search opened: onto the tile that opened it. */
    fun focusBack(): Boolean = browseTile?.takeIf { it.isAttachedToWindow }?.requestFocus() == true

    private fun resetWall(kind: String) {
        wall.removeAllViews()
        // New results start at the top.
        wallY = 0
        scroll.scrollTo(0, 0)
        wall.tag = kind
    }

    private fun addPosters(list: List<Title>) {
        for ((i, chunk) in list.chunked(COLS).withIndex()) {
            val r = FlatLinear(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
                for ((j, t) in chunk.withIndex()) addView(poster(t, i), LinearLayout.LayoutParams(posterW.dp, WRAP_CONTENT).apply {
                    if (j < chunk.size - 1) marginEnd = CinemaStyle.CARD_GAP.dp
                })
            }
            wall.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = ROW_GAP.dp })
            if (i < 3) Ui.rise(listOf(r), fromDp = 14, startDelay = i * 45L, duration = 300L)
        }
    }

    /** A result: its poster, the title (one line, gliding while focused) and year · type under it. */
    private fun poster(t: Title, row: Int): View = kit.unit(kit.poster(t, posterW, (posterW * 1.5f).toInt()).apply {
        art().bind(row)
        Availability.attachTitle(this, t) // a new title's status pill, like on every row card
        setOnClickListener { host.openDetails(t, this) }
        isLongClickable = true
        setOnLongClickListener { host.titleMenu(t); true }
        onFocus { has ->
            if (!has) return@onFocus
            host.speculate(t)
            // Engaging with a result counts the search as a recent one.
            val q = query()
            if (q.length >= 2 && q != remembered) { remembered = q; RecentSearches.add(ctx, q) }
        }
    }, null, t.name, kit.subline(t))

    private fun firstTile(): View? = ArrayList<View>().also { wall.addFocusables(it, View.FOCUS_FORWARD) }.firstOrNull()

    // ---- voice ----

    fun alexaListening(on: Boolean) {
        listen.active = on
        if (on) {
            queryField.setQuery("")
            queryField.hint = "Listening…"
            keyboard.focusEntry()
        } else queryField.hint = HINT
    }

    fun searchFor(q: String) {
        listen.active = false
        queryField.hint = HINT
        val t = q.trim()
        if (t.isEmpty()) { keyboard.focusEntry(); return }
        focusResultsNext = true
        RecentSearches.add(ctx, t)
        if (query() == t) { shownQuery = null; queryChanged() } else queryField.setQuery(t)
        keyboard.focusEntry()
    }

    // ---- page ----

    override fun focusFirst() { keyboard.focusEntry() }

    override fun onFocus(v: View) {
        queryField.caretShown = keyboard.isKey(v)
        queryField.caretColor = CinemaStyle.glowNow
        placeLine(v)
    }

    /**
     * The results scroll at their edges: moving among the lines that show whole moves only the focus. A line
     * running off the bottom brings the wall up just enough to show it whole, captions included; a line cut by
     * the top brings it down until that line (with a heading just above it, as a person's "Top Results") sits
     * where the first line does. Lines the top edge cuts fade out, and come back as they come into view.
     */
    private fun placeLine(v: View) {
        val r = HomeShell.directChild(v, wall) ?: return
        var k = wall.indexOfChild(r)
        if (k < 0) return
        if (k > 0 && wall.getChildAt(k - 1) is TextView) k--
        val anchor = wall.getChildAt(k)
        val view = scroll.height - scroll.paddingTop - scroll.paddingBottom
        var y = wallY
        if (view > 0 && r.bottom > y + view - EDGE.dp) y = r.bottom - view + EDGE.dp
        else if (anchor.top < y + EDGE.dp) y = if (k == 0) 0 else anchor.top - wall.paddingTop
        y = maxOf(0, y)
        if (y != wallY) { wallY = y; scroll.glideTo(y, 360) }
        for (i in 0 until wall.childCount) {
            val c = wall.getChildAt(i)
            val a = if (i < k && c.top < y + EDGE.dp) 0f else 1f
            if ((lineAlpha[c] ?: 1f) == a) continue
            lineAlpha[c] = a
            c.animate().alpha(a).setStartDelay(0).setDuration(if (a == 0f) 160L else 220L).setInterpolator(Ui.EASE).start()
        }
    }

    /** Where the wall is gliding to (or resting). */
    private var wallY = 0

    private val lineAlpha = java.util.WeakHashMap<View, Float>()

    override fun handleBack(focused: View): Boolean {
        if (listen.active) { alexaListening(false); return true }
        if (!keyboard.isKey(focused)) return keyboard.focusEntry()
        if (queryField.text.isNotEmpty()) { queryField.backspace(); return true }
        return false
    }

    override fun fadesTop() = false

    override fun onShow() { live = true }

    override fun onHide() {
        if (listen.active) alexaListening(false)
        live = false
    }

    companion object {
        private const val HINT = "Movies, shows and people"
        /**
         * The field, then the keyboard, then the suggestions, top to bottom. At 1080p (540 dp tall) that's
         * 84 + 44 + 12 + 241 (six rows of 36 dp keys) + 14 = 395 dp to the first suggestion, leaving room for
         * three (30 dp each, 6 apart) above the bottom safe edge. With the old 40 dp keys, a 48 dp field and a
         * heading the list began at ~464 dp: one suggestion showed, cut by the screen's edge, the rest off it.
         */
        private const val KEY_H = 36
        private const val KEY_GAP = 5
        private const val FIELD_H = 44
        private const val KB_TOP = 12
        private const val SUGGEST_TOP = 14
        private const val LIST_TOP = 14
        private const val SUGGEST_H = 30
        private const val SUGGEST_GAP = 6
        /** The bottom of the screen a TV may overscan (dp). */
        private const val BOTTOM_SAFE = 24
        /** A genre search shows its rows with at least this many titles between movies and shows. */
        private const val GENRE_MIN = 5
        private const val KB_W = 6 * KEY_H + 5 * KEY_GAP
        private const val COL_GAP = 44
        /**
         * Five across: two whole lines (poster, title, year) fit under the heading at 1080p
         * (125 + 192 + 16 + 192 = 525 dp of the 540).
         */
        private const val COLS = 5
        private const val ROW_GAP = 16
        /** A line closer than this to the wall's top or the screen's bottom counts as cut (dp). */
        private const val EDGE = 6
        private const val TOP = 84
        private const val MAX_TILES = 24
        private const val MAX_SUGGEST = 3
        private const val DEBOUNCE_MS = 250L
        /** Trending titles for the empty state, fetched once per process. */
        private var trending: List<Title>? = null
    }
}

/** Recent searches, per profile (newest first). */
object RecentSearches {
    private const val PREFS = "dream_search"
    private const val MAX = 8

    private fun key(ctx: Context) = "recent_" + Profiles.current(ctx).id

    fun list(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(ctx), "").orEmpty()
            .split('\n').filter { it.isNotBlank() }

    fun add(ctx: Context, q: String) {
        val t = q.trim()
        if (t.length < 2) return
        val l = (listOf(t) + list(ctx).filterNot { it.equals(t, ignoreCase = true) }).take(MAX)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key(ctx), l.joinToString("\n")).apply()
    }
}
