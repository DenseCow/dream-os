package com.firehome.launcher

import android.view.View
import android.widget.LinearLayout

/**
 * Cinema's Browse (the bar's Browse tab): two big entry tiles (TV Shows and Movies,
 * wearing this week's most popular art), Browse by Genre (cards tinted with the rainbow's hues), Browse by
 * Service (the brand tiles), and at the bottom the Discover wall, inline: three lines of filter
 * chips (type, order, genre; saved per profile) over a poster wall of every title, seven
 * across with the name under each, that pages in as you near its end. Every page Browse opens comes back
 * here, onto the tile that opened it ([focusTile]).
 *
 * The wall's lines are ordinary grid rows of the page ([CinemaRow.grid]): they keep their column moving
 * up and down, bind their art only near the screen and stop drawing far from it, like every other row.
 */
class CinemaBrowse(shell: CinemaShell) : CinemaRowsPage(shell) {
    private val tv: CinemaCard
    private val movies: CinemaCard
    private val genreRow: CinemaRow
    /** Each category row's specs, in its cards' order ([focusTile]). */
    private val tileSpecs = HashMap<CinemaRow, List<GenreSpec>>()
    private val serviceRow: CinemaRow
    private val serviceSpecs: List<ServiceSpec>

    // ---- the wall ----
    /** Only type, order and genre are Cinema's chips: any other saved filters are kept in storage, not applied here. */
    private val f = Discover.load(ctx).let { DiscoverFilters(type = it.type, genre = it.genre, sort = it.sort) }
    private lateinit var genreChips: CinemaChips
    private var wallFrom = 0
    private val wallLines = ArrayList<CinemaRow>()
    private val wallKeys = HashSet<String>()
    /** Titles waiting for a full line: the wall flows on across pages, only its very last line may be short. */
    private val wallBuffer = ArrayList<Title>()
    private var page = 0
    private var totalPages = 1
    private var loading = false
    private var gen = 0
    private var cap: View? = null
    /** The wall's placeholder line while its first line loads. */
    private var ghostLine: CinemaRow? = null

    init {
        add(header("Browse", "Movies, shows, genres and services, all in one place"), gap = 22)
        val dm = ctx.resources.displayMetrics
        val w = ((dm.widthPixels / dm.density - 2 * CinemaStyle.GUTTER - CinemaStyle.CARD_GAP) / 2).toInt()
        val h = w * 9 / 32 + 60
        val hue = CinemaStyle.GLOW_HUES
        tv = kit.entryTile("TV Shows", "New seasons and every series", w, h, intArrayOf(hue[0], hue[1])).apply {
            setOnClickListener { shell.openBrowseChild("TV") }
        }
        movies = kit.entryTile("Movies", "New releases and all-time favorites", w, h, intArrayOf(hue[3], hue[4])).apply {
            setOnClickListener { shell.openBrowseChild("MOVIES") }
        }
        val entry = newRow(null, RowKind.STANDARD)
        add(entry, gap = 30)
        entry.fill(listOf(tv, movies).map { (it as View) to it }, animate = false)
        // The tiles wear the most popular title of the week.
        if (Tmdb.hasKey(ctx)) for ((card, type) in listOf(tv to "tv", movies to "movie")) {
            // Anime only inside anime (STANDARDS §5): the tiles wear the most popular non-anime title that is out.
            Net.background({ Tmdb.trendingShown(ctx, "/trending/$type/week", type = type, art = "backdrop").firstOrNull() }) { t ->
                if (t == null || released) return@background
                card.art().source(t.backdrop("w780"))
                card.art().bind(0)
            }
        }

        // The genres as cards, the rainbow's hues running on across them. The categories beyond them (moods,
        // formats, themes, decades) have no cards here: they're rows inside the Movies, TV Shows and service pages.
        var hueAt = 0
        fun tiles(title: String, specs: List<GenreSpec>): CinemaRow {
            val r = newRow(title, RowKind.STANDARD)
            add(r)
            tileSpecs[r] = specs
            r.fill(specs.map { s ->
                val c = kit.genreTile(s.name, hue[hueAt++ % hue.size])
                c.setOnClickListener { shell.openGenre(s) }
                c.onFocus { has -> prefetchOnRest(has, "genre ${s.name}") { CinemaGenre.prefetch(ctx, s) } }
                (c as View) to c
            }, animate = false)
            return r
        }
        genreRow = tiles("Browse by Genre", GenreSpec.SHORTCUTS)

        val installed = host.apps().map { it.pkg }.toSet()
        fun has(s: ServiceSpec) = Tmdb.SERVICES.firstOrNull { it.name == s.name }?.pkg in installed
        serviceSpecs = ServiceSpec.SHORTCUTS.sortedBy { if (has(it)) 0 else 1 }
        serviceRow = newRow("Browse by Service", RowKind.BRAND)
        add(serviceRow, gap = 36)
        serviceRow.fill(serviceSpecs.map { s ->
            val c = kit.brand(s)
            c.setOnClickListener { shell.openService(s, fromHome = false) }
            c.onFocus { has -> prefetchOnRest(has, "service ${s.name}") { CinemaService.prefetch(ctx, s) } }
            (c as View) to c
        }, animate = false)

        if (Tmdb.hasKey(ctx)) wall()
    }

    /** When each tile's page was last fetched ahead (TMDB's lists stay in memory for half an hour). */
    private val prefetchedAt = HashMap<String, Long>()
    private var restJob: Runnable? = null

    /**
     * Focus resting on a genre or service tile ([PREFETCH_REST_MS]) fetches what that page shows first, in
     * the background, so it usually opens filled. Moving on before then costs nothing.
     */
    private fun prefetchOnRest(has: Boolean, key: String, work: () -> Unit) {
        restJob?.let { shell.cancel(it) }
        restJob = null
        if (!has || !Tmdb.hasKey(ctx)) return
        val now = android.os.SystemClock.uptimeMillis()
        if (prefetchedAt[key]?.let { now - it < PREFETCH_FRESH_MS } == true) return
        restJob = Runnable {
            restJob = null
            if (released) return@Runnable
            prefetchedAt[key] = android.os.SystemClock.uptimeMillis()
            Net.background(work) {}
        }.also { shell.post(it, PREFETCH_REST_MS) }
    }

    /** The wall's title, its three chip lines, then the wall itself. */
    private fun wall() {
        add(kit.label("Every Movie and Show", CinemaStyle.T_ROW, kit.text, kit.semibold).apply {
            setPadding(kit.gutter, 0, kit.gutter, 0)
        }, gap = 16)
        add(CinemaChips(kit, listOf("Movies", "TV Shows")) { i ->
            f.switchType(if (i == 0) "movie" else "tv")
            val fresh = genreChipsFor()
            replace(genreChips, fresh)
            genreChips = fresh
            changed()
        }.apply { mark(if (f.type == "tv") 1 else 0) }, gap = 14)
        add(CinemaChips(kit, Discover.SORT_ORDER.map { Discover.SORTS[it] }) { i -> f.sort = Discover.SORT_ORDER[i]; changed() }.apply {
            mark(Discover.SORT_ORDER.indexOf(f.sort).coerceAtLeast(0))
        }, gap = 14)
        genreChips = genreChipsFor()
        add(genreChips, gap = 26)
        wallFrom = sectionCount()
        loadWall(reset = true)
    }

    /** The categories, the same set for movies and shows ([Discover.CATEGORIES]: Horror is on TV Shows too). */
    private fun genreChipsFor(): CinemaChips {
        val list = Discover.categories(f.type)
        return CinemaChips(kit, listOf("Any Genre") + list.map { it.name }) { i ->
            f.genre = if (i == 0) 0 else list[i - 1].id(f.type)
            changed()
        }.apply { mark((list.indexOfFirst { it.id(f.type) == f.genre } + 1).coerceAtLeast(0)) }
    }

    /** A chip changed the filters: saved for this profile (other saved filters untouched), the wall starts over. */
    private fun changed() {
        val saved = Discover.load(ctx)
        saved.type = f.type
        saved.genre = f.genre
        saved.sort = f.sort
        Discover.save(ctx, saved)
        loadWall(reset = true)
    }

    /** The next page of the wall (or the first, [reset]), as lines of posters before the end of the page. */
    private fun loadWall(reset: Boolean) {
        if (reset) {
            gen++
            loading = false
            removeFrom(wallFrom)
            wallLines.clear()
            wallKeys.clear()
            wallBuffer.clear()
            page = 0
            totalPages = 1
            cap = null
            // A line of placeholders until the first line is in: focus can come down onto it (and stays on
            // the same poster when the real ones fill it, [CinemaRow.fill]).
            ghostLine = newRow(null, RowKind.POSTER).apply { grid = true }.also { add(it, gap = CinemaStyle.GRID_GAP) }
        }
        if (loading || page >= totalPages) return
        loading = true
        val g = gen
        val q = f.copy()
        val next = page + 1
        Net.background({ Discover.page(ctx, q, next) }) { r ->
            if (g != gen || released) return@background
            loading = false
            cap?.let { removeSection(it) }
            cap = null
            fun dropGhost() { ghostLine?.let { removeSection(it) }; ghostLine = null }
            if (r == null) {
                if (wallLines.isEmpty()) { dropGhost(); add(kit.empty(LineIcon.SEARCH, "Nothing to Show", "Check the connection, or try another genre.")) }
                return@background
            }
            page = r.page
            totalPages = r.totalPages
            val last = page >= totalPages
            // Tidied like every row (nothing adult, finished or obscure), in the order's own order.
            wallBuffer += RowMix.tidy(ctx, r.titles, artFirst = false, anime = Discover.wantsAnime(q)).filter { wallKeys.add(it.key) }
            if (wallBuffer.isEmpty() && wallLines.isEmpty() && last) {
                dropGhost()
                add(kit.empty(LineIcon.SEARCH, "Nothing Matches", "Try another genre or order."))
                return@background
            }
            // Only full lines go up; what's left waits for the next page (the end of everything may be short).
            // Lines are only ever added under the last: nothing already on the wall moves. The first one fills
            // the placeholder line in place.
            var added = 0
            while (wallBuffer.size >= COLS || (last && wallBuffer.isNotEmpty())) {
                val chunk = ArrayList(wallBuffer.subList(0, minOf(COLS, wallBuffer.size)))
                wallBuffer.subList(0, chunk.size).clear()
                val line = ghostLine ?: newRow(null, RowKind.POSTER).apply { grid = true }.also { add(it, gap = CinemaStyle.GRID_GAP) }
                ghostLine = null
                line.fill(titleCards(chunk) { posterUnit(it) }, animate = false)
                wallLines += line
                added++
            }
            cap = endCap()
            window()
            // A page that made no full line (other filters took most of it), or a wall still short: fetch on.
            if (!last && (added == 0 || wallLines.size < 3)) loadWall(reset = false)
        }
    }

    private fun posterUnit(t: Title): Pair<View, CinemaCard> {
        val w = kit.fit(RowKind.POSTER.w)
        val card = kit.poster(t, w, w * 3 / 2)
        return kit.unit(card, null, t.name, kit.subline(t)) to card
    }

    /** Nearing the wall's end (three lines left): the next page comes in under it. */
    override fun sectionFocused(sec: View, v: View) {
        super.sectionFocused(sec, v)
        val i = wallLines.indexOf(sec)
        if (i >= 0 && i >= wallLines.size - 3) loadWall(reset = false)
    }

    /** Back from a page Browse opened: onto its tile ([name]: the genre or service). */
    fun focusTile(from: String, name: String?): Boolean = when (from) {
        "TV" -> tv.requestFocus()
        "MOVIES" -> movies.requestFocus()
        "GENRE" -> genreRow.cards.getOrNull(tileSpecs[genreRow].orEmpty().indexOfFirst { it.name == name })?.requestFocus() == true
        "SERVICE" -> serviceRow.cards.getOrNull(serviceSpecs.indexOfFirst { it.name == name })?.requestFocus() == true
        else -> false
    }

    /** Seven across ([RowKind.POSTER]): two whole lines fit above the bottom fade (see [CinemaStyle.GRID_LINE]). */
    private companion object {
        const val COLS = 7
        /** Focus resting this long on a genre or service tile fetches its page ahead. */
        const val PREFETCH_REST_MS = 450L
        /** A page fetched ahead this recently isn't fetched again (inside TMDB's 30 minute memory). */
        const val PREFETCH_FRESH_MS = 20 * 60_000L
    }
}

/**
 * One genre, movies and shows together (Browse by Genre), Netflix style: its hero, then [GenreShelves]'
 * rows: Trending as big cards, New Releases, the genre's series, Critically Acclaimed, a Spotlight, the
 * curated sub-genres (Horror: Supernatural, Slashers, Zombies, Found Footage...), the genre on each
 * streaming app on this TV, Hidden Gems and the decades. Every row is lazy (it loads as focus nears it),
 * deep (it pages on toward [RowPaging.MAX_TITLES] as focus nears its end) and holds a title only once.
 */
class CinemaGenre(shell: CinemaShell, private val spec: GenreSpec) : CinemaRowsPage(shell) {
    /** Anime shows on Anime's page alone. */
    private val claims = RowClaims(ctx, anime = spec.anime)

    init {
        if (Tmdb.hasKey(ctx)) build()
        else add(kit.empty(LineIcon.FILM, "Add a TMDB Key", "Open Settings and add a TMDB key to browse ${spec.name}.").apply { setPadding(0, 120.dp, 0, 0) })
    }

    private fun titles(title: String, kind: RowKind = RowKind.STANDARD, lazy: Boolean = true, load: () -> List<Title>) =
        row(title, kind, lazy, load = { claims.take(title, load().filter { it.backdropPath != null || it.posterPath != null }).takeIf { it.size >= MIN_ROW } }) { _, list ->
            titleCards(list) { if (kind == RowKind.BIG) kit.big(it, spec.name) else kit.landscape(it) }
        }

    private fun build() {
        val h = CinemaHero(kit, host, COMPACT_H, compact = true)
        hero = h
        add(h)
        Net.background({ heroTitles(ctx, spec) }) { list ->
            if (!list.isNullOrEmpty() && !released) h.setTitles(list) { spec.name }
        }
        // Home's services (installed ones first): Dream OS plays them all, installed or not.
        val services = CinemaHome.services(host.apps().map { it.pkg }.toSet())
        GenreShelves.page(spec, services).forEachIndexed { i, s ->
            // The first two rows load with the page; the rest as focus nears them.
            titles(s.title, if (i == 0) RowKind.BIG else RowKind.STANDARD, lazy = i > 1) { GenreShelves.load(ctx, spec, s) }
            // No in-row Spotlight card on Browse pages (owner, 2026-10-28): the hero leads the page.
            // A seasonal hub that's on and matches this genre (Horror in October: 31 Nights of Horror), after the first row.
            if (i == 0) Seasons.forGenre(ctx, spec.name)?.let { seasonRow(it, claims) }
        }
        endCap()
    }

    companion object {
        /** Rows the page starts loading as it opens: the first two, and the next ones its lookahead reaches. */
        private const val FIRST_ROWS = 3

        /**
         * [spec]'s Discover list for movies and shows at once, interleaved (the genre's main type first; a
         * decade's years). No anime outside Anime's own page (the hero and Spotlight aren't tidied rows).
         */
        private fun mixed(ctx: android.content.Context, spec: GenreSpec, f: DiscoverFilters.() -> Unit = {}): List<Title> =
            RowMix.interleave(RowPaging.all(*GenreShelves.types(spec).map { type ->
                { Tmdb.list(ctx, "/discover/$type", Discover.query(DiscoverFilters(type = type, genre = if (type == "movie") spec.movie else spec.tv, lang = spec.lang, year = spec.year).apply(f)), type) }
            }.toTypedArray())).filter { spec.anime || !RowMix.animeHidden(ctx, it) }

        private fun heroTitles(ctx: android.content.Context, spec: GenreSpec) = mixed(ctx, spec) { rating = 7 }.filter { it.backdropPath != null && RowMix.released(it) }.take(6)

        /**
         * Fetches what [spec]'s page shows first (its hero and first rows) into TMDB's cache, on a background
         * thread: focus resting on its tile in Browse, so the page usually opens filled. (The first rows don't
         * depend on the services on this TV: those rows come later.)
         */
        fun prefetch(ctx: android.content.Context, spec: GenreSpec) {
            val first = GenreShelves.page(spec, emptyList()).take(FIRST_ROWS)
            try {
                RowPaging.all({ heroTitles(ctx, spec) }, *first.map { s -> { GenreShelves.load(ctx, spec, s) } }.toTypedArray())
            } catch (e: Exception) {}
        }
    }
}

/** A page title on the page's line under the bar (Browse, Discover): the name large, a line about it. */
fun CinemaKit.pageHeader(title: String, sub: String) = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    val d = ctx.resources.displayMetrics.density
    setPadding(gutter, (92 * d).toInt(), gutter, 0)
    addView(label(title, 30f, text, bold).apply { letterSpacing = -0.01f })
    if (sub.isNotEmpty()) addView(label(sub, 14.5f, dim, medium).apply { setPadding(0, (8 * d).toInt(), 0, 0) })
}
