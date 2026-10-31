package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.HistoryEntry
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.time.DreamTime
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Keeps one page from showing the same title in row after row (fire-home RowClaims). A title belongs to the first row
 * that loads it; later rows skip it. A row reloaded keeps its own titles. With [tidy] on, every row is also tidied
 * ([RowMix.tidy]): nothing adult, art-less, finished, plainly bad or obscure, and anime only where it belongs.
 */
class RowClaims(private val tidy: Boolean = true, private val anime: Boolean = false) {
    private val owner = HashMap<String, String>()

    /**
     * Whether [row] may show anime: every row of an anime page ([anime]), a row named for it ("Anime Series",
     * "Anime on Netflix"), and the personal rows. Anywhere else anime is left out while the profile keeps it apart.
     */
    private fun animeOk(row: String) = anime || RowMix.animeRow(row)

    /** [list] minus titles another row already shows; the first [n] left are claimed for [row]. */
    fun take(row: String, list: List<Title>, n: Int = 20): List<Title> {
        var pool = if (tidy) RowMix.tidy(list, anime = animeOk(row)) else list
        // Discovery rows are ranked for the profile (Taste); charts, dated rows and the viewer's own rows keep their order.
        if (tidy && Taste.ranks(row)) pool = Taste.rank(Taste.current(), pool)
        // Another row loading its next page reads this row's lists (for their claims) without claiming more.
        val claim = !RowPaging.foreign(row)
        return synchronized(owner) {
            // Two pages of a chart can overlap (TMDB's order moves between them): each title once.
            val out = pool.distinctBy { it.key }.filter { owner[it.key].let { o -> o == null || o == row } }.take(n)
            if (claim) out.forEach { owner[it.key] = row }
            out
        }
    }

    /**
     * [take], but a row that must always be there (In Theaters) never disappears because earlier rows claimed most of
     * its titles: when fewer than [min] are left, it shows its own list anyway (sharing titles), claiming only those
     * nobody has. Null when even that is under [min].
     */
    fun takeAtLeast(row: String, list: List<Title>, min: Int, n: Int = 20): List<Title>? {
        take(row, list, n).takeIf { it.size >= min }?.let { return it }
        val pool = if (tidy) RowMix.tidy(list, anime = animeOk(row)) else list
        return synchronized(owner) {
            val out = pool.distinctBy { it.key }.take(n)
            if (!RowPaging.foreign(row)) out.forEach { owner.putIfAbsent(it.key, row) }
            out
        }.takeIf { it.size >= min }
    }

    /**
     * A chart (Top 10) keeps its order and ranks: shown as is, its titles kept out of the rows below. With [n], the
     * first [n] left after the anime filter, so a chart fetched long (two pages) always fills.
     */
    fun claim(row: String, list: List<Title>, n: Int = Int.MAX_VALUE): List<Title> =
        (if (animeOk(row) || !ContentPrefs.animeApart()) list else list.filterNot { RowMix.isAnime(it) }).take(n).let { l ->
            synchronized(owner) {
                if (!RowPaging.foreign(row)) l.forEach { owner.putIfAbsent(it.key, row) }
                l
            }
        }

    /** Who claimed [key] (tests, logs). */
    fun ownerOf(key: String): String? = synchronized(owner) { owner[key] }
}

/**
 * Deep rows (fire-home RowPaging): a row whose end nears loads its next page by running its own loader again inside
 * [at]. Every [Tmdb.list] call made on that thread then asks TMDB for the page [page] - 1 further on, so a loader that
 * blends several lists pages through all of them at once. The row drops what it already shows.
 */
object RowPaging {
    private val state = ThreadLocal<Pair<String, Int>?>()

    /** How far a row goes: about five pages of titles. */
    const val MAX_TITLES = 100
    /** Cards from a row's end that load its next page. */
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
     * Side jobs for [all]: up to 16 at once, idle ones exit after 30 s. No queue: when all are busy a task runs on the
     * thread that asked (nested [all]s never wait on each other: no deadlock).
     */
    private val fetch = ThreadPoolExecutor(0, 16, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { r -> Thread(r, "tmdb-fetch").apply { isDaemon = true } }, ThreadPoolExecutor.CallerRunsPolicy())

    /**
     * Runs [tasks] side by side (the first on this thread) and returns their results in order: a row that blends
     * several TMDB lists waits for the slowest one, not for all of them in turn. Each task runs as this thread's page
     * of its row ([at]). A task that throws makes this throw (once all have finished).
     */
    fun <T> all(vararg tasks: () -> T): List<T> {
        if (tasks.size <= 1) return tasks.map { it() }
        val st = state.get()
        val background = com.dreamos.desktop.core.net.Lane.background
        val rest = (1 until tasks.size).map { i ->
            FutureTask {
                val old = state.get()
                state.set(st)
                // The side job takes the asking thread's TmdbGate lane too.
                try { if (background) com.dreamos.desktop.core.net.Lane.background { tasks[i]() } else tasks[i]() } finally { state.set(old) }
            }.also { fetch.execute(it) }
        }
        var error: Exception? = null
        val first = try { tasks[0]() } catch (e: Exception) { error = e; null }
        val out = ArrayList<T>(tasks.size)
        @Suppress("UNCHECKED_CAST")
        if (error == null) out += first as T
        for (f in rest) try { out += f.get() } catch (e: ExecutionException) {
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
     * Pages a row: [load] run again as page 2, 3, ... gives titles, of which those not in [shown] are new. Returns the
     * page that brought them (the caller asks that + 1 next) and the new ones (empty: the row is done). Tries up to
     * [EMPTY_TRIES] pages.
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
 * Row recipes that keep Browse varied (fire-home RowMix): genre rows mix what's popular now with favorites of the last
 * [RECENT_YEARS] years, editorial rows turn to a different page each day, and old titles appear only in the rows that
 * ask for them. [tidy] keeps every row clean.
 */
object RowMix {
    /** Genre, service and recommendation rows stay within this many years (a decade row sets its own). */
    const val RECENT_YEARS = 30
    /** Critically Acclaimed: released within this many years. */
    const val ACCLAIMED_YEARS = 25
    /** Hidden Gems: released within this many years (and at least one year ago). */
    const val GEM_YEARS = 18

    /**
     * Top 10 Today: TMDB's daily trending, two pages (40 titles), filtered (poster, adult, released, anime, Kids), then
     * the first 10 in TMDB's order. Always 10 when TMDB has them: filtering first, never taking 10 and then filtering.
     */
    fun top10(claims: RowClaims, type: MediaKind? = null): List<Title> {
        val path = "/trending/${type?.path ?: "all"}/day"
        val seen = HashSet<String>()
        val (p1, p2) = RowPaging.all({ Tmdb.list(path, type = type) }, { Tmdb.list(path, "&page=2", type = type) })
        val pool = (p1 + p2).filter { it.posterPath != null && !it.adult && released(it) && seen.add(it.key) }
        return claims.claim("Top 10 Today", pool, 10)
    }

    /**
     * Out today in the viewer's zone (or no date known). TMDB's charts list films months ahead of release; a title
     * nobody can watch yet isn't "watched today": it belongs in Coming Soon.
     */
    fun released(t: Title) = t.date.length != 10 || t.date <= DreamTime.todayYmd()

    /** Plainly bad: under 5.0 from 100 or more people. Never in a discovery row (charts keep what they list). */
    fun poorlyRated(t: Title) = t.votes >= 100 && t.rating > 0 && t.rating < 5.0

    /** A page from 1 to [pages] that stays the same all day for [row] and moves on tomorrow. */
    fun dailyPage(row: String, pages: Int): Int {
        val d = DreamTime.zoned().toLocalDate()
        val today = d.year * 400 + d.dayOfYear
        return 1 + Math.floorMod(today * 31 + row.hashCode(), pages)
    }

    fun dateKey(type: MediaKind) = if (type == MediaKind.MOVIE) "primary_release_date" else "first_air_date"

    /** The date [years] ago, "yyyy-MM-dd" (computed per call: the app runs for weeks). */
    fun yearsAgo(years: Int): String = DreamTime.yearsAgo(years)

    private fun thisYear() = DreamTime.thisYear()

    // ---- tidy: what no row should show ----

    /** Languages this profile watches in: English, the player's audio language and the PC's. */
    fun prefLangs(): Set<String> = setOf("en") + listOfNotNull(
        ContentPrefs.audioLang(), ContentPrefs.normLang(java.util.Locale.getDefault().language),
    )

    /** Titles this profile has finished: movies watched to the end, shows caught up to their last episode. */
    fun finished(history: List<HistoryEntry> = WatchHistory.recent()): Set<String> = history
        .filter { it.progress >= HistoryEntry.FINISHED && (it.hidden || !it.isShow) }.mapTo(HashSet()) { it.key }

    /**
     * Anime: Japanese animation, and Chinese or Korean animated series that aren't for kids or families (donghua and
     * aeni look like anime; their CGI films and preschool cartoons don't). Also animation made in Japan in another
     * language that isn't for kids or families: Cyberpunk: Edgerunners is English on TMDB, made by a Japanese studio
     * (origin JP), and showed as Home's hero on 2026-10-30 (owner: anime only in anime sections). It shows only in the
     * anime sections.
     */
    fun isAnime(t: Title): Boolean {
        if (16 !in t.genreIds) return false
        if (t.lang == "ja") return true
        val forKids = 10762 in t.genreIds || 10751 in t.genreIds
        if ("JP" in t.originCountry && !forKids) return true
        return t.kind == MediaKind.TV && (t.lang == "zh" || t.lang == "cn" || t.lang == "ko") && !forKids
    }

    /** [t] is anime this profile keeps in the anime sections: left out of a general row, hero or chart. */
    fun animeHidden(t: Title) = isAnime(t) && ContentPrefs.animeApart()

    /** A row that's about anime by its name, or a personal one: it keeps anime. */
    fun animeRow(row: String) = ANIME_WORD.containsMatchIn(row) || row.contains("Crunchyroll") ||
        PERSONAL.any { row.startsWith(it, ignoreCase = true) }

    private val ANIME_WORD = Regex("(?i)\\banime\\b")
    private val PERSONAL = listOf("Because You Watched", "Top Picks", "Continue Watching", "My List", "Recommended", "More Like")

    /** Below this many votes a title in a language the profile doesn't watch in counts as obscure. */
    private fun widelySeen(t: Title) = t.votes >= if (t.kind == MediaKind.MOVIE) 500 else 150

    /**
     * [list] without adult titles, titles with no poster, titles this profile has finished, plainly bad ones, and
     * obscure titles in a language it doesn't watch in. A row mostly in one language (a K-drama page) takes that
     * language as its own. With [artFirst], titles with a backdrop come first (landscape cards). Anime is left out
     * unless [anime] (an anime row, or a personal one) or the profile shows anime everywhere.
     */
    fun tidy(
        list: List<Title>, artFirst: Boolean = true, anime: Boolean = false,
        done: Set<String> = finished(), langs: Set<String> = prefLangs(), animeApart: Boolean = ContentPrefs.animeApart(),
    ): List<Title> {
        if (list.isEmpty()) return list
        val known = list.filter { it.lang.isNotEmpty() }
        val own = known.groupingBy { it.lang }.eachCount().maxByOrNull { it.value }
            ?.takeIf { known.size >= 4 && it.value * 2 >= known.size }?.key
        val keepAnime = anime || !animeApart
        val ok = list.filter { t ->
            !t.adult && t.posterPath != null && t.key !in done && (keepAnime || !isAnime(t)) && !poorlyRated(t) &&
                (t.lang.isEmpty() || t.lang in langs || t.lang == own || widelySeen(t))
        }
        return if (!artFirst) ok else ok.filter { it.backdropPath != null } + ok.filter { it.backdropPath == null }
    }

    // ---- recipes ----

    /** "&date.gte=...": the last [RECENT_YEARS] years, for a row with no years of its own. */
    fun recent(type: MediaKind) = "&${dateKey(type)}.gte=${yearsAgo(RECENT_YEARS)}"

    private fun recentFloor(type: MediaKind, query: String, years: Int = RECENT_YEARS) =
        if (query.contains("${dateKey(type)}.gte=") || query.contains("${dateKey(type)}.lte=")) "" else "&${dateKey(type)}.gte=${yearsAgo(years)}"

    private fun voteFloor(type: MediaKind, query: String, movie: Int, tv: Int) =
        if (query.contains("vote_count.gte=")) "" else "&vote_count.gte=${if (type == MediaKind.MOVIE) movie else tv}"

    /**
     * The Discover [query] on [type] as a varied row: popular now, alternating with favorites (most voted, rated 6.3 or
     * more, over a year old, from a page that changes daily). Both from the last [RECENT_YEARS] years and rated by
     * enough people, unless [query] sets its own years or votes (a decade row).
     */
    fun variety(type: MediaKind, query: String, row: String): List<Title> {
        val path = "/discover/${type.path}"
        val q = query + recentFloor(type, query) + voteFloor(type, query, 150, 60)
        val dated = q.contains("${dateKey(type)}.lte=")
        val favQuery = q.replace(Regex("&sort_by=[^&]*"), "&sort_by=vote_count.desc") +
            (if (dated) "" else "&${dateKey(type)}.lte=${yearsAgo(1)}") +
            (if (q.contains("vote_average.gte=")) "" else "&vote_average.gte=6.3")
        val page = dailyPage(row, 4)
        val (popular, fav1, fav2) = RowPaging.all(
            { Tmdb.list(path, q, type) },
            { Tmdb.list(path, "$favQuery&page=$page", type) },
            { Tmdb.list(path, "$favQuery&page=${page + 1}", type) },
        )
        return interleave(listOf(popular, fav1 + fav2))
    }

    /** Genres a gem shouldn't come from (family films, TV movies; kids, news, reality, soaps and talk on TV). */
    private fun notGems(type: MediaKind, extra: String): String {
        val asked = Regex("with_genres=([0-9,|]+)").find(extra)?.groupValues?.get(1)?.split(',', '|')?.toSet().orEmpty()
        val skip = (if (type == MediaKind.MOVIE) listOf("10751", "10770") else listOf("10762", "10751", "10763", "10764", "10766", "10767")).filter { it !in asked }
        return if (skip.isEmpty()) "" else "&without_genres=" + skip.joinToString(",")
    }

    /** [extra] with [notGems] (when [gems]) folded into its own without_genres (TMDB answers 400 to it twice). */
    private fun skipping(type: MediaKind, extra: String, gems: Boolean = true): String {
        val own = Regex("&without_genres=([^&]*)").find(extra)
        val skip = listOfNotNull(own?.groupValues?.get(1), if (gems) notGems(type, extra).removePrefix("&without_genres=").ifEmpty { null } else null)
        val rest = own?.let { extra.removeRange(it.range) } ?: extra
        return if (skip.isEmpty()) rest else "$rest&without_genres=" + skip.joinToString(",")
    }

    /**
     * Hidden Gems: well rated (7.0 or more) by a modest number of people (movies 150-1,500 votes, shows 80-800),
     * released in the last [GEM_YEARS] years but at least a year ago, most watched first, from a page that changes daily.
     */
    fun hiddenGems(type: MediaKind, extra: String, row: String): List<Title> {
        val votes = if (type == MediaKind.MOVIE) "&vote_count.gte=150&vote_count.lte=1500" else "&vote_count.gte=80&vote_count.lte=800"
        val d = dateKey(type)
        return Tmdb.list("/discover/${type.path}", "&include_adult=false&sort_by=popularity.desc&vote_average.gte=7.0$votes" +
            "&$d.gte=${yearsAgo(GEM_YEARS)}&$d.lte=${yearsAgo(1)}${skipping(type, extra)}&page=${dailyPage(row, if (extra.isEmpty()) 3 else 1)}", type)
    }

    /**
     * Critically Acclaimed: recent, highly rated and widely seen (last [ACCLAIMED_YEARS] years); the best rated by many
     * alternating with the most watched of the very well rated. [extra] narrows it (lower vote floors, first page).
     */
    fun acclaimed(type: MediaKind, extra: String, row: String): List<Title> {
        val movie = type == MediaKind.MOVIE
        val d = dateKey(type)
        val narrow = extra.isNotEmpty()
        val base = "&include_adult=false&include_video=false&$d.gte=${yearsAgo(ACCLAIMED_YEARS)}&$d.lte=${yearsAgo(0)}${skipping(type, extra, gems = !movie)}"
        val best = if (movie) (if (narrow) 1000 else 3000) else (if (narrow) 300 else 800)
        val loved = if (movie) (if (narrow) 400 else 1000) else (if (narrow) 120 else 300)
        val (bestList, lovedList) = RowPaging.all(
            { Tmdb.list("/discover/${type.path}", "$base&sort_by=vote_average.desc&vote_average.gte=7.5&vote_count.gte=$best" +
                "&page=${if (narrow) 1 else dailyPage("$row best", 3)}", type) },
            { Tmdb.list("/discover/${type.path}", "$base&sort_by=popularity.desc&vote_average.gte=7.8&vote_count.gte=$loved" +
                "&page=${if (narrow) 1 else dailyPage("$row loved", 2)}", type) },
        )
        return interleave(listOf(lovedList, bestList))
    }

    /**
     * "Because You Watched": TMDB's recommendations for the seed, topped up from its similar titles when they run short.
     * Rated by enough people (movies 200+ votes, shows 60+), and no older than [RECENT_YEARS] years, or 15 years before
     * the seed for a classic seed. Personal: anime watched leads to anime.
     */
    fun recs(type: MediaKind, id: Int, seedYear: Int?): List<Title> {
        val path = "/${type.path}/$id"
        val list = try { Tmdb.list("$path/recommendations", type = type) } catch (e: Exception) { emptyList() }
        val more = if (list.size >= 12 && RowPaging.page == 1) emptyList()
        else try { Tmdb.list("$path/similar", type = type) } catch (e: Exception) { emptyList() }
        val floor = minOf(thisYear() - RECENT_YEARS, (seedYear ?: thisYear()) - 15)
        val minVotes = if (type == MediaKind.MOVIE) 200 else 60
        return tidy((list + more).distinctBy { it.key }.filter { t ->
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
