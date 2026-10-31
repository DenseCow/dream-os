package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.time.DreamTime
import com.dreamos.desktop.core.util.Log
import com.dreamos.desktop.core.util.int
import com.dreamos.desktop.core.util.o
import com.dreamos.desktop.core.util.a
import com.dreamos.desktop.core.util.objects
import com.dreamos.desktop.core.util.str
import com.dreamos.desktop.core.util.parseJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * New Releases (fire-home NewReleases.kt): what has just become watchable at home, not what's in theaters or still to
 * come.
 *
 * - Movies: US digital or physical releases ([MOVIE_DAYS] days back to today), first released within the last year and
 *   a half (a 4K re-issue of an old film isn't new), most popular first, rated by a few people.
 * - Shows: brand-new series that premiered in the last [TV_DAYS] days, most popular first. Running shows whose latest
 *   season premiered then are [returning]'s (the separate "New Episodes" row). News, soaps and talk are left out unless
 *   a row asks for them.
 *
 * Every list comes from [Tmdb.list] (cached, the Kids profile's rating limit applied, deep rows paged by [RowPaging],
 * anime left out by [Tmdb.animeClause]); the rows tidy it through [RowClaims]. A narrowed row (a genre, a service) that
 * finds little in the usual window looks further back ([WIDE_MOVIE_DAYS], [WIDE_TV_DAYS]). Blocking: IO threads only.
 */
object NewReleases {
    private const val TAG = "NewReleases"
    const val MOVIE_DAYS = 60
    const val TV_DAYS = 45
    private const val WIDE_MOVIE_DAYS = 150
    private const val WIDE_TV_DAYS = 120
    /** A narrowed row with fewer titles than this on its first page uses the wide window. */
    private const val THIN = 10
    private const val BASE = "&include_adult=false&include_video=false&sort_by=popularity.desc"
    /** TV genres no New Releases row shows unless it asks for them: news, soaps, talk. */
    private val TV_SKIP = listOf("10763", "10766", "10767")

    private fun withArt(l: List<Title>) = l.filter { it.posterPath != null || it.backdropPath != null }

    // ---- loaders ----

    /**
     * In Theaters: US theatrical releases of the last ~10 weeks, and only films first released recently. TMDB's
     * /movie/now_playing also lists anniversary re-releases of old films (and stale entries), which read as "old movies"
     * in a row called In Theaters. Most popular first.
     */
    fun inTheaters(): List<Title> {
        // No "&region=US" here: Tmdb.url always adds it, and TMDB answers 400 to a repeated parameter.
        val q = "$BASE&with_release_type=2|3&release_date.gte=${DreamTime.ymdPlus(-75)}&release_date.lte=${DreamTime.ymdPlus(0)}" +
            "&primary_release_date.gte=${DreamTime.ymdPlus(-150)}"
        // One page here: Tmdb.list adds the page itself (deep rows page on as they scroll).
        return try {
            withArt(Tmdb.list("/discover/movie", q, MediaKind.MOVIE))
                .also { if (RowPaging.page == 1) Log.i(TAG, "in theaters ${it.size}") }
        } catch (e: Exception) {
            Log.w(TAG, "in theaters failed: $e")
            throw e
        }
    }

    /** New digital and disc releases, [extra] narrowing them ("&with_genres=27", a service's providers). */
    fun movies(extra: String = ""): List<Title> {
        val narrow = extra.isNotEmpty()
        val votes = if (narrow) 8 else 25
        fun q(days: Int) = "$BASE$extra&with_release_type=4|5&release_date.gte=${DreamTime.ymdPlus(-days)}&release_date.lte=${DreamTime.ymdPlus(0)}" +
            "&primary_release_date.gte=${DreamTime.ymdPlus(-540)}&vote_count.gte=$votes"
        val query = window(MediaKind.MOVIE, narrow, q(MOVIE_DAYS), q(WIDE_MOVIE_DAYS))
        return withArt(Tmdb.list("/discover/movie", query, MediaKind.MOVIE))
    }

    /**
     * Brand-new series only (premiered in the last [TV_DAYS] days), [extra] narrowing them. Running shows with a new
     * season are [returning]'s: their own "New Episodes" row, never New Releases.
     */
    fun shows(extra: String = ""): List<Title> {
        val narrow = extra.isNotEmpty()
        val skip = skipGenres(extra)
        fun series(days: Int) = "$BASE$extra$skip&first_air_date.gte=${DreamTime.ymdPlus(-days)}&first_air_date.lte=${DreamTime.ymdPlus(0)}" +
            "&vote_count.gte=${if (narrow) 3 else 10}"
        val query = window(MediaKind.TV, narrow, series(TV_DAYS), series(WIDE_TV_DAYS))
        return withArt(Tmdb.list("/discover/tv", query, MediaKind.TV))
    }

    /**
     * The general "New Episodes" row: running shows whose latest season premiered in the last [TV_DAYS] days (each
     * checked against its seasons' dates, cached), most popular first, [extra] narrowing them.
     */
    fun returning(extra: String = ""): List<Title> {
        val narrow = extra.isNotEmpty()
        val skip = skipGenres(extra)
        val days = if (narrow) WIDE_TV_DAYS else TV_DAYS
        val from = DreamTime.ymdPlus(-days)
        val running = "$BASE$extra$skip&air_date.gte=$from&air_date.lte=${DreamTime.ymdPlus(0)}&first_air_date.lte=${DreamTime.ymdPlus(-days - 1)}" +
            "&vote_count.gte=${if (narrow) 15 else 50}"
        val airing = Tmdb.list("/discover/tv", running, MediaKind.TV)
        val history = try { WatchHistory.recent() } catch (e: Exception) { emptyList() }
        // A show the viewer is caught up on has no new episode for them (NewEpisodes.caughtUp): it leaves until the next one airs.
        fun caughtUp(t: Title) = synchronized(lastAired) { lastAired[t.id] }?.let { (s, e) -> NewEpisodes.caughtUp(t.id, s, e, history) } == true
        return withArt(seasonPremieres(airing, from).filterNot(::caughtUp))
    }

    /**
     * Movies and shows interleaved; null [movieExtra] / [tvExtra]: none of that type. [lead] is the type that goes
     * first. Each type's failure only drops that type.
     */
    fun mixed(movieExtra: String? = "", tvExtra: String? = "", lead: MediaKind = MediaKind.MOVIE): List<Title> {
        val (m, t) = RowPaging.all(
            { movieExtra?.let { try { movies(it) } catch (e: Exception) { emptyList() } }.orEmpty() },
            { tvExtra?.let { try { shows(it) } catch (e: Exception) { emptyList() } }.orEmpty() },
        )
        return RowMix.interleave(if (lead == MediaKind.TV) listOf(t, m) else listOf(m, t))
    }

    /** One type. */
    fun ofType(type: MediaKind, extra: String = ""): List<Title> = if (type == MediaKind.MOVIE) movies(extra) else shows(extra)

    /** [spec]'s new releases (a Browse genre or category), its main type first. */
    fun forGenre(spec: GenreSpec): List<Title> {
        val types = GenreShelves.types(spec)
        val m = if (MediaKind.MOVIE in types) GenreShelves.clause(spec, MediaKind.MOVIE) else null
        val t = if (MediaKind.TV in types) GenreShelves.clause(spec, MediaKind.TV) else null
        if (!spec.anime) return mixed(m, t, spec.primary)
        // Anime: Japanese animation, and animation tagged "anime" in another language (Uzumaki).
        fun tagged(c: String?) = c?.replace("&with_original_language=ja", "&with_keywords=${Tmdb.ANIME_KEYWORD}")
        return RowMix.interleave(listOf(mixed(m, t, spec.primary), mixed(tagged(m), tagged(t), spec.primary)))
    }

    /** A streaming service's new releases (its US subscription catalog). */
    fun onService(service: String): List<Title> {
        val ids = Discover.serviceIds(service).takeIf { it.isNotEmpty() } ?: return emptyList()
        val on = "&with_watch_providers=$ids&watch_region=US&with_watch_monetization_types=flatrate"
        return mixed(on, on, MediaKind.TV)
    }

    /** Logs a filled row: "home 20 (12 movies, 8 shows)". */
    fun log(where: String, list: List<Title>?) {
        val l = list.orEmpty()
        val m = l.count { it.kind == MediaKind.MOVIE }
        Log.i(TAG, "$where ${l.size} ($m movies, ${l.size - m} shows)")
    }

    // ---- helpers ----

    /** [usual], or [wide] for a narrowed row whose first page of [usual] is thin (decided on page 1, so a deep row's pages agree). */
    private fun window(type: MediaKind, narrow: Boolean, usual: String, wide: String): String {
        if (!narrow) return usual
        val first = try {
            RowPaging.at("NewReleases probe", 1) { Tmdb.list("/discover/${type.path}", usual, type) }
        } catch (e: Exception) { return usual }
        return if (first.size < THIN) wide else usual
    }

    /** "&without_genres=..." for news, soaps and talk (and kids' shows, outside a Kids profile), less what [extra] asks for. */
    private fun skipGenres(extra: String): String {
        val asked = Regex("with_genres=([0-9,|]+)").find(extra)?.groupValues?.get(1)?.split(',', '|')?.toSet().orEmpty()
        val skip = (TV_SKIP + if (KidsFilter.active()) emptyList() else listOf("10762")).filter { it !in asked }
        return if (skip.isEmpty()) "" else "&without_genres=" + skip.joinToString(",")
    }

    private class Lru<V>(private val max: Int) : LinkedHashMap<Int, V>(max, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, V>?) = size > max
    }

    /** show id -> (checked at, its latest season's premiere up to today, "" when none). */
    private val premiereCache = Lru<Pair<Long, String>>(600)
    private const val PREMIERE_TTL = 6 * 60 * 60_000L

    /** [airing] shows whose latest season (up to today) premiered on or after [from]. */
    private fun seasonPremieres(airing: List<Title>, from: String): List<Title> {
        if (airing.isEmpty()) return airing
        val dates = RowPaging.all(*airing.map { t -> { try { latestSeason(t.id) } catch (e: Exception) { "" } } }.toTypedArray())
        return airing.filterIndexed { i, _ -> dates[i] >= from }
    }

    private fun latestSeason(id: Int): String {
        val now = System.currentTimeMillis()
        synchronized(premiereCache) { premiereCache[id]?.takeIf { now - it.first < PREMIERE_TTL }?.let { return it.second } }
        // A row's lookup: a copy from the last days serves at once and refreshes behind (a premiere date rarely moves).
        val json = parseJson(Net.tmdb("${Tmdb.API}/tv/$id?api_key=${Tmdb.key()}&language=en-US", revalidate = true).body).jsonObject
        val today = DreamTime.ymdPlus(0)
        var latest = ""
        for (s in json.a("seasons").objects()) {
            val d = s.str("air_date")
            if (s.int("season_number") >= 1 && d.length >= 10 && d <= today && d > latest) latest = d
        }
        json.o("last_episode_to_air")?.let { ep ->
            episodeLine(ep)?.let { synchronized(lastEpisodes) { lastEpisodes[id] = it } }
            val s = ep.int("season_number")
            val n = ep.int("episode_number")
            if (s >= 1 && n >= 1) synchronized(lastAired) { lastAired[id] = s to n }
        }
        synchronized(premiereCache) { premiereCache[id] = now to latest }
        return latest
    }

    /** Forgets the premiere lookups (tests: a relaunch keeps only the disk). */
    fun clearCache() { synchronized(premiereCache) { premiereCache.clear() }; synchronized(lastEpisodes) { lastEpisodes.clear() } }

    /** Each returning show's newest aired episode line, filled by [latestSeason] from the details call it already makes. */
    private val lastEpisodes = Lru<String>(600)
    /** Each returning show's newest aired episode (season to number), from the same call: who is caught up. */
    private val lastAired = Lru<Pair<Int, Int>>(600)
    private val GENERIC_EPISODE = Regex("^(Episode|Ep\\.?)\\s*\\d+$", RegexOption.IGNORE_CASE)

    /**
     * "S22 E5 · Name" (or "New Episode · S22 E5" when TMDB names it only "Episode 5"): the newest aired episode under a
     * New Episodes card instead of "2005 · Series" (owner, 2026-10-28). Null when TMDB has none.
     */
    internal fun episodeLine(ep: JsonObject): String? {
        val s = ep.int("season_number")
        val n = ep.int("episode_number")
        if (s < 1 || n < 1) return null
        val name = ep.str("name").trim()
        return if (name.isEmpty() || GENERIC_EPISODE.matches(name)) "New Episode · S$s E$n" else "S$s E$n · $name"
    }

    /** A New Episodes card's subtitle: the show's newest episode, null when unknown (the card keeps its usual line). */
    fun episodeSub(t: Title): String? = synchronized(lastEpisodes) { lastEpisodes[t.id] }
}
