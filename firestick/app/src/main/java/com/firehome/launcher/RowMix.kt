package com.firehome.launcher

/**
 * Keeps one Browse page from showing the same title in row after row. A title belongs to the first
 * row that loads it; later rows skip it. A row reloaded after recycling keeps its own titles.
 * Given a [ctx], every row is also tidied ([RowMix.tidy]): nothing adult, art-less, finished or obscure.
 */
class RowClaims(private val ctx: android.content.Context? = null, private val anime: Boolean = false) {
    private val owner = HashMap<String, String>()

    /**
     * Whether [row] may show anime: every row of an anime page ([anime]), a row named for it ("Anime
     * Series", "Anime on Netflix"), and the personal rows (what you watch is yours). Anywhere else anime
     * is left out ([RowMix.isAnime]): it lives in the anime sections alone.
     */
    private fun animeOk(row: String) = anime || RowMix.animeRow(row)

    /** [list] minus titles another row already shows; the first [n] left are claimed for [row]. */
    fun take(row: String, list: List<Title>, n: Int = 20): List<Title> {
        var pool = ctx?.let { RowMix.tidy(it, list, anime = animeOk(row)) } ?: list
        // Discovery rows are ranked for the profile (Taste: list order, taste, steadied quality, freshness; franchises
        // spread). Charts, dated rows and the viewer's own rows keep their order.
        if (ctx != null && Taste.ranks(row)) pool = Taste.rank(Taste.current(ctx), pool)
        // Another row loading its next page reads this row's lists (for their claims) without claiming more.
        val claim = !RowPaging.foreign(row)
        return synchronized(owner) {
            // Rows load on background threads, several at once: claimed under the lock.
            val out = pool.filter { owner[it.key].let { o -> o == null || o == row } }.take(n)
            if (claim) out.forEach { owner[it.key] = row }
            out
        }
    }

    /**
     * [take], but a row that must always be there (In Theaters) never disappears because earlier rows (Trending,
     * a seasonal hub) claimed most of its titles: when fewer than [min] are left, it shows its own list anyway
     * (sharing titles), claiming only those nobody has. Null when even that is under [min].
     */
    fun takeAtLeast(row: String, list: List<Title>, min: Int, n: Int = 20): List<Title>? {
        take(row, list, n).takeIf { it.size >= min }?.let { return it }
        val pool = ctx?.let { RowMix.tidy(it, list, anime = animeOk(row)) } ?: list
        return synchronized(owner) {
            val out = pool.take(n)
            if (!RowPaging.foreign(row)) out.forEach { owner.putIfAbsent(it.key, row) }
            out
        }.takeIf { it.size >= min }
    }

    /**
     * A chart (Top 10) keeps its order and ranks: shown as is, its titles kept out of the rows below. With [n], the
     * first [n] left after the filters, so a chart fetched long (two pages) always fills.
     */
    fun claim(row: String, list: List<Title>, n: Int = Int.MAX_VALUE): List<Title> = (if (animeOk(row) || ctx == null || !RowMix.animeApart(ctx)) list else list.filterNot { RowMix.isAnime(it) }).take(n).let { l ->
        synchronized(owner) {
            if (!RowPaging.foreign(row)) l.forEach { owner.putIfAbsent(it.key, row) }
            l
        }
    }
}

/**
 * Deep rows: a row whose focus nears its end loads its next page by running its own loader again
 * inside [at]. Every [Tmdb.list] call made on that thread then asks TMDB for the page [page] - 1
 * further on ("&page=3" becomes "&page=5" for the third page), so a loader that blends several lists
 * pages through all of them at once, with no change to the loader. The row drops what it already shows.
 */
object RowPaging {
    private val state = ThreadLocal<Pair<String, Int>?>()

    /** How far a row goes: about five pages of titles. */
    const val MAX_TITLES = 100
    /** Focus this many cards from a row's end loads its next page. */
    const val NEAR_END = 6
    /** Pages in a row that bring nothing new before the row counts as done. */
    const val EMPTY_TRIES = 2

    /** Runs [block] as page [page] (2 or more) of [row]. */
    fun <T> at(row: String, page: Int, block: () -> T): T {
        val old = state.get()
        state.set(row to page)
        try { return block() } finally { state.set(old) }
    }

    val page: Int get() = state.get()?.second ?: 1

    /** True while this thread loads a later page of a row other than [row]. */
    fun foreign(row: String): Boolean = state.get()?.let { it.first != row } ?: false

    /**
     * Side jobs for [all]: up to 12 at once, idle ones exit after 30 s. No queue: when they're
     * all busy a task runs on the thread that asked (so nested [all]s never wait on each other: no deadlock).
     * A service's category row is six TMDB lists (two types, three lists each): with only 6 side threads, two
     * rows loading at once already ran most of their lists one after another on the asking thread (a row
     * took several round trips instead of one). 12 covers the page's three row-list threads ([Net.load]).
     */
    private val fetch = java.util.concurrent.ThreadPoolExecutor(0, 12, 30, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue(), { r -> Thread(r, "net-fetch").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy())

    /**
     * Runs [tasks] side by side (the first on this thread) and returns their results in order: a row that
     * blends several TMDB lists waits for the slowest one, not for all of them one after another. Each task
     * runs as this thread's page of its row ([at]), so a deep row pages through them just the same. Like
     * running them in turn, a task that throws makes this throw (once all have finished).
     */
    fun <T> all(vararg tasks: () -> T): List<T> {
        if (tasks.size <= 1) return tasks.map { it() }
        val st = state.get()
        val rest = (1 until tasks.size).map { i ->
            java.util.concurrent.FutureTask {
                val old = state.get()
                state.set(st)
                try { tasks[i]() } finally { state.set(old) }
            }.also { fetch.execute(it) }
        }
        var error: Exception? = null
        val first = try { tasks[0]() } catch (e: Exception) { error = e; null }
        val out = ArrayList<T>(tasks.size)
        @Suppress("UNCHECKED_CAST")
        if (error == null) out += first as T
        for (f in rest) try { out += f.get() } catch (e: java.util.concurrent.ExecutionException) {
            if (error == null) error = (e.cause as? Exception) ?: e
        }
        error?.let { throw it }
        return out
    }

    private val PAGE = Regex("&page=(\\d+)")

    /** [query] moved [page] - 1 pages on (unchanged on the first page). */
    fun paged(query: String): String {
        val n = page
        if (n <= 1) return query
        val m = PAGE.find(query)
        val p = (m?.groupValues?.get(1)?.toIntOrNull() ?: 1) + (n - 1)
        return (if (m != null) query.removeRange(m.range) else query) + "&page=${minOf(500, p)}"
    }

    /**
     * Pages a row: [load] run again as page 2, 3, … (on a background thread) gives titles, of which those
     * not in [shown] are new. Returns the new ones (empty: the row is done). Tries up to [EMPTY_TRIES]
     * pages in a row before giving up, since a page can bring only titles other rows took.
     */
    fun next(row: String, from: Int, shown: Set<String>, load: () -> List<Title>?): Pair<Int, List<Title>> {
        var page = from
        repeat(EMPTY_TRIES) {
            val got = try { at(row, page) { load() } } catch (e: Exception) { null } ?: return page to emptyList()
            if (got.isEmpty()) return page to emptyList()
            val fresh = got.filter { it.key !in shown }.distinctBy { it.key }
            if (fresh.isNotEmpty()) return page to fresh
            page++
        }
        return page to emptyList()
    }
}

/**
 * Row recipes that keep Browse varied and feeling like a modern streamer, not a film archive: genre rows
 * mix what's popular now with favorites of the last [RECENT_YEARS] years, the editorial rows turn to a
 * different page of their list each day, and old titles appear only in the rows that ask for them
 * (the decades, "Classics"). [tidy] keeps every row clean.
 */
object RowMix {
    /** Genre, service and recommendation rows stay within this many years (a decade row sets its own). */
    const val RECENT_YEARS = 30
    /** Critically Acclaimed: released within this many years. */
    const val ACCLAIMED_YEARS = 25
    /** Hidden Gems: released within this many years (and at least one year ago). */
    const val GEM_YEARS = 18

    /**
     * Top 10 Today: TMDB's daily trending, two pages (40 titles), filtered (poster, adult, anime, Kids via Tmdb.list),
     * then the first 10 in TMDB's order. Taking 10 before the filters showed 9 when one was anime (2026-10-28).
     */
    fun top10(ctx: android.content.Context, claims: RowClaims, type: String? = null): List<Title> {
        val path = "/trending/${type ?: "all"}/day"
        val seen = HashSet<String>()
        val pool = (Tmdb.list(ctx, path, type = type) + Tmdb.list(ctx, path, "&page=2", type = type))
            .filter { it.posterPath != null && !it.adult && released(it) && seen.add(it.key) }
        return claims.claim("Top 10 Today", pool, 10)
    }

    /**
     * Out today in the viewer's zone (or no date known). TMDB's charts list films months ahead of release (Dune: Part
     * Three at #8 in October); a title nobody can watch yet isn't "watched today": it belongs in Coming Soon.
     */
    fun released(t: Title) = t.date.length != 10 || t.date <= DreamTime.todayYmd()

    /** Plainly bad: under 5.0 from 100 or more people. Never in a discovery row (charts keep what they list). */
    fun poorlyRated(t: Title) = t.votes >= 100 && t.rating > 0 && t.rating < 5.0

    private fun today(): Int = DreamTime.calendar().let { it.get(java.util.Calendar.YEAR) * 400 + it.get(java.util.Calendar.DAY_OF_YEAR) }

    /** A page from 1 to [pages] that stays the same all day for [row] and moves on tomorrow. */
    fun dailyPage(row: String, pages: Int): Int = 1 + Math.floorMod(today() * 31 + row.hashCode(), pages)

    fun dateKey(type: String) = if (type == "movie") "primary_release_date" else "first_air_date"

    /** The date [years] ago, "yyyy-MM-dd". */
    fun yearsAgo(years: Int): String = DreamTime.calendar().apply { add(java.util.Calendar.YEAR, -years) }
        .let { String.format(java.util.Locale.US, "%04d-%02d-%02d", it.get(java.util.Calendar.YEAR), it.get(java.util.Calendar.MONTH) + 1, it.get(java.util.Calendar.DAY_OF_MONTH)) }

    private fun thisYear() = DreamTime.calendar().get(java.util.Calendar.YEAR)

    // ---- tidy: what no row should show ----

    /** Languages this profile watches in: English, the player's audio language and the device's. */
    fun prefLangs(ctx: android.content.Context): Set<String> =
        setOf("en") + listOfNotNull(PlayerPrefs.normLang(PlayerPrefs.audioLang(ctx)), PlayerPrefs.normLang(java.util.Locale.getDefault().language))

    /** Titles this profile has finished: movies watched to the end, shows caught up on to their last episode. */
    fun finished(ctx: android.content.Context): Set<String> = WatchHistory.recent(ctx)
        .filter { it.progress >= WatchHistory.FINISHED && (it.hidden || !it.isShow) }.mapTo(HashSet()) { it.key }

    /**
     * Anime: Japanese animation, and Chinese or Korean animated series that aren't for kids or families
     * (donghua and aeni look like anime; their CGI films and preschool cartoons don't). It shows only in
     * the anime sections ([RowClaims], [tidy]), never in Animation, Adult Animation, Action and the rest.
     */
    fun isAnime(t: Title): Boolean {
        if (16 !in t.genreIds) return false
        if (t.lang == "ja") return true
        return t.type == "tv" && (t.lang == "zh" || t.lang == "cn" || t.lang == "ko") && 10762 !in t.genreIds && 10751 !in t.genreIds
    }

    /**
     * Settings › Keep Anime in Anime Sections (per profile, on by default). Off, anime shows in every row, chart and
     * genre search like any other title, and no TMDB query leaves it out ([NewReleases.withAnime]).
     */
    fun animeApart(ctx: android.content.Context) = Theme.get(ctx, Theme.ANIME_APART) == 1

    /** [t] is anime this profile keeps in the anime sections: left out of a general row, hero or chart. */
    fun animeHidden(ctx: android.content.Context, t: Title) = isAnime(t) && animeApart(ctx)

    /** A row that's about anime by its name, or a personal one ([RowClaims]): it keeps anime. */
    fun animeRow(row: String) = ANIME_WORD.containsMatchIn(row) || row.contains("Crunchyroll") ||
        PERSONAL.any { row.startsWith(it, ignoreCase = true) }

    private val ANIME_WORD = Regex("(?i)\\banime\\b")
    private val PERSONAL = listOf("Because You Watched", "Top Picks", "Continue Watching", "My List", "Recommended", "More Like")

    /** Below this many votes a title in a language the profile doesn't watch in counts as obscure. */
    private fun widelySeen(t: Title) = t.votes >= if (t.type == "movie") 500 else 150

    /**
     * [list] without adult titles, titles with no poster, titles this profile has finished, and obscure
     * titles in a language it doesn't watch in. A row mostly in one language (anime, a K-drama page,
     * recommendations for a foreign film) takes that language as its own. With [artFirst], titles with a
     * backdrop come first (landscape cards); otherwise (and among each) in [list]'s order. Anime is left
     * out unless [anime] (an anime row, or a personal one): it lives in the anime sections alone.
     */
    fun tidy(ctx: android.content.Context, list: List<Title>, artFirst: Boolean = true, anime: Boolean = false): List<Title> {
        if (list.isEmpty()) return list
        val done = finished(ctx)
        val langs = prefLangs(ctx)
        val known = list.filter { it.lang.isNotEmpty() }
        val own = known.groupingBy { it.lang }.eachCount().maxByOrNull { it.value }
            ?.takeIf { known.size >= 4 && it.value * 2 >= known.size }?.key
        val keepAnime = anime || !animeApart(ctx)
        val ok = list.filter { t ->
            !t.adult && t.posterPath != null && t.key !in done && (keepAnime || !isAnime(t)) && !poorlyRated(t) &&
                (t.lang.isEmpty() || t.lang in langs || t.lang == own || widelySeen(t))
        }
        return if (!artFirst) ok else ok.filter { it.backdropPath != null } + ok.filter { it.backdropPath == null }
    }

    // ---- recipes ----

    /** "&date.gte=…": the last [RECENT_YEARS] years, for a row with no years of its own ("Popular on Netflix"). */
    fun recent(type: String) = "&${dateKey(type)}.gte=${yearsAgo(RECENT_YEARS)}"

    /** "&date.gte=…" for [type] unless [query] already limits that date. */
    private fun recentFloor(type: String, query: String, years: Int = RECENT_YEARS) =
        if (query.contains("${dateKey(type)}.gte=") || query.contains("${dateKey(type)}.lte=")) "" else "&${dateKey(type)}.gte=${yearsAgo(years)}"

    /** "&vote_count.gte=…" for [type] unless [query] sets its own. */
    private fun voteFloor(type: String, query: String, movie: Int, tv: Int) =
        if (query.contains("vote_count.gte=")) "" else "&vote_count.gte=${if (type == "movie") movie else tv}"

    /**
     * The Discover [query] on [type] as a varied row: popular now, alternating with favorites (most
     * voted, rated 6.3 or more, over a year old, from a page that changes daily). Both from the last
     * [RECENT_YEARS] years and rated by enough people, unless [query] sets its own years or votes
     * (a decade row). Rows above usually show the popular ones already, so the favorites fill in.
     */
    fun variety(ctx: android.content.Context, type: String, query: String, row: String): List<Title> {
        val path = "/discover/$type"
        val q = query + recentFloor(type, query) + voteFloor(type, query, 150, 60)
        val dated = q.contains("${dateKey(type)}.lte=")
        val favQuery = q.replace(Regex("&sort_by=[^&]*"), "&sort_by=vote_count.desc") +
            (if (dated) "" else "&${dateKey(type)}.lte=${yearsAgo(1)}") +
            (if (q.contains("vote_average.gte=")) "" else "&vote_average.gte=6.3")
        val page = dailyPage(row, 4)
        // The three lists at once (one after another, they were most of a row's wait).
        val (popular, fav1, fav2) = RowPaging.all(
            { Tmdb.list(ctx, path, q, type) },
            { Tmdb.list(ctx, path, "$favQuery&page=$page", type) },
            { Tmdb.list(ctx, path, "$favQuery&page=${page + 1}", type) },
        )
        return interleave(listOf(popular, fav1 + fav2))
    }

    /** Genres a gem shouldn't come from (family films, TV movies; kids, news, reality, soaps and talk on TV). */
    private fun notGems(type: String, extra: String): String {
        val asked = Regex("with_genres=([0-9,|]+)").find(extra)?.groupValues?.get(1)?.split(',', '|')?.toSet().orEmpty()
        val skip = (if (type == "movie") listOf("10751", "10770") else listOf("10762", "10751", "10763", "10764", "10766", "10767")).filter { it !in asked }
        return if (skip.isEmpty()) "" else "&without_genres=" + skip.joinToString(",")
    }

    /**
     * [extra] with [notGems] (when [gems]) folded into its own without_genres: TMDB answers 400 to the
     * parameter twice (TV Adventure, Fantasy and Romance leave out animation in their own filter).
     */
    private fun skipping(type: String, extra: String, gems: Boolean = true): String {
        val own = Regex("&without_genres=([^&]*)").find(extra)
        val skip = listOfNotNull(own?.groupValues?.get(1), if (gems) notGems(type, extra).removePrefix("&without_genres=").ifEmpty { null } else null)
        val rest = own?.let { extra.removeRange(it.range) } ?: extra
        return if (skip.isEmpty()) rest else "$rest&without_genres=" + skip.joinToString(",")
    }

    /**
     * Hidden Gems: well rated (7.0 or more) by a modest number of people (movies 150-1,500 votes,
     * shows 80-800), released in the last [GEM_YEARS] years but at least a year ago, most watched first,
     * from a page that changes daily. (Sorting by score instead surfaces tiny titles with inflated
     * ratings.) [extra] adds filters ("&with_genres=27").
     */
    fun hiddenGems(ctx: android.content.Context, type: String, extra: String, row: String): List<Title> {
        val votes = if (type == "movie") "&vote_count.gte=150&vote_count.lte=1500" else "&vote_count.gte=80&vote_count.lte=800"
        val d = dateKey(type)
        return Tmdb.list(ctx, "/discover/$type", "&include_adult=false&sort_by=popularity.desc&vote_average.gte=7.0$votes" +
            "&$d.gte=${yearsAgo(GEM_YEARS)}&$d.lte=${yearsAgo(1)}${skipping(type, extra)}&page=${dailyPage(row, if (extra.isEmpty()) 3 else 1)}", type)
    }

    /**
     * Critically Acclaimed: recent, highly rated and widely seen. Released in the last [ACCLAIMED_YEARS]
     * years; the best rated by many (7.5+, movies 3,000+ votes, shows 800+) alternating with the most
     * watched of the very well rated (7.8+, movies 1,000+, shows 300+), so it is modern hits, not the
     * archive's all-time list. Kids, news, reality, soap and talk shows are left out. [extra] narrows it
     * (a genre or a service: smaller lists, so lower vote floors and always from the first page).
     */
    fun acclaimed(ctx: android.content.Context, type: String, extra: String, row: String): List<Title> {
        val movie = type == "movie"
        val d = dateKey(type)
        val narrow = extra.isNotEmpty()
        val base = "&include_adult=false&include_video=false&$d.gte=${yearsAgo(ACCLAIMED_YEARS)}&$d.lte=${yearsAgo(0)}${skipping(type, extra, gems = !movie)}"
        val best = if (movie) (if (narrow) 1000 else 3000) else (if (narrow) 300 else 800)
        val loved = if (movie) (if (narrow) 400 else 1000) else (if (narrow) 120 else 300)
        val (bestList, lovedList) = RowPaging.all(
            { Tmdb.list(ctx, "/discover/$type", "$base&sort_by=vote_average.desc&vote_average.gte=7.5&vote_count.gte=$best" +
                "&page=${if (narrow) 1 else dailyPage("$row best", 3)}", type) },
            { Tmdb.list(ctx, "/discover/$type", "$base&sort_by=popularity.desc&vote_average.gte=7.8&vote_count.gte=$loved" +
                "&page=${if (narrow) 1 else dailyPage("$row loved", 2)}", type) },
        )
        return interleave(listOf(lovedList, bestList))
    }

    /**
     * "Because You Watched": TMDB's recommendations for the seed ([type] [id], released [seedYear]),
     * topped up from its similar titles when they run short. Rated by enough people (movies 200+ votes,
     * shows 60+), and no older than [RECENT_YEARS] years, or 15 years before the seed for a classic
     * seed (a 1972 film may still lead to 1957's). Tidied like every row.
     */
    fun recs(ctx: android.content.Context, type: String, id: Int, seedYear: Int?): List<Title> {
        val path = "/$type/$id"
        val list = try { Tmdb.list(ctx, "$path/recommendations", type = type) } catch (e: Exception) { emptyList() }
        val more = if (list.size >= 12 && RowPaging.page == 1) emptyList()
        else try { Tmdb.list(ctx, "$path/similar", type = type) } catch (e: Exception) { emptyList() }
        val floor = minOf(thisYear() - RECENT_YEARS, (seedYear ?: thisYear()) - 15)
        val minVotes = if (type == "movie") 200 else 60
        // Personal: anime watched leads to anime.
        return tidy(ctx, (list + more).distinctBy { it.key }.filter { t ->
            t.id != id && t.votes >= minVotes && (t.year.toIntOrNull() ?: thisYear()) >= floor
        }, anime = true)
    }

    fun interleave(lists: List<List<Title>>): List<Title> {
        val out = ArrayList<Title>()
        val seen = HashSet<String>()
        val n = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until n) for (l in lists) l.getOrNull(i)?.let { if (seen.add(it.key)) out += it }
        return out
    }
}
