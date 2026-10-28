package com.firehome.launcher

import android.content.Context
import android.util.Log
import android.util.LruCache

/**
 * New Releases: what has just become watchable at home, not what's in theaters or still to come.
 *
 * - Movies: US digital or physical releases ([MOVIE_DAYS] days back to today), first released within the
 *   last year and a half (a 4K re-issue of an old film isn't new), most popular first, rated by a few people.
 * - Shows: brand-new series that premiered in the last [TV_DAYS] days, most popular first. Running shows whose
 *   latest season premiered then are [returning]'s (the separate "New Episodes" row). News, soaps and talk are
 *   left out unless a row asks for them.
 *
 * Every list comes from [Tmdb.list] (cached, the Kids profile's rating limit applied, deep rows paged by
 * [RowPaging]); the rows tidy it through [RowClaims] (anime only in the anime sections). A narrowed row
 * (a genre, a service) that finds little in the usual window looks further back ([WIDE_MOVIE_DAYS], [WIDE_TV_DAYS]).
 */
object NewReleases {
    private const val TAG = "FireHome"
    const val MOVIE_DAYS = 60
    const val TV_DAYS = 45
    private const val WIDE_MOVIE_DAYS = 150
    private const val WIDE_TV_DAYS = 120
    /** A narrowed row with fewer titles than this on its first page uses the wide window. */
    private const val THIN = 10
    private const val BASE = "&include_adult=false&include_video=false&sort_by=popularity.desc"
    /** TV genres no New Releases row shows unless it asks for them: news, soaps, talk. */
    private val TV_SKIP = listOf("10763", "10766", "10767")

    // ---- anime stays in the anime sections ----

    private val ANIME_ASKED = listOf(
        Regex("(^|&)with_original_language=ja(&|$)"),
        Regex("(^|&)with_keywords=[^&]*\\b${Discover.ANIME_KEYWORD}\\b"),
        Regex("(^|&)with_watch_providers=[^&]*\\b283\\b"), // Crunchyroll
    )

    /**
     * For [Tmdb]'s URLs: every /discover query that doesn't ask for anime (Japanese titles, the anime keyword,
     * Crunchyroll) leaves out TMDB's "anime" keyword. [RowMix.isAnime] only sees genre and language, and some
     * anime is made in English (Uzumaki: an American co-production), so a Horror wall or row let it through.
     */
    fun animeClause(path: String, query: String): String =
        if (!path.startsWith("/discover/") || ANIME_ASKED.any { it.containsMatchIn(query) }) ""
        else "&without_keywords=${Discover.ANIME_KEYWORD}"

    // ---- loaders (background threads) ----

    /**
     * In Theaters: US theatrical releases of the last ~10 weeks, and only films first released recently.
     * TMDB's /movie/now_playing also lists anniversary re-releases of old films (and stale entries), which
     * read as "old movies" in a row called In Theaters. Two pages, most popular first.
     */
    fun inTheaters(ctx: Context): List<Title> {
        // No "&region=US" here: Tmdb.url always adds it, and TMDB answers 400 to a repeated parameter.
        val q = "$BASE&with_release_type=2|3&release_date.gte=${DreamTime.ymdPlus(-75)}&release_date.lte=${DreamTime.ymdPlus(0)}" +
            "&primary_release_date.gte=${DreamTime.ymdPlus(-150)}"
        // One page here: Tmdb.list adds the page itself (deep rows page on as they scroll); a second "&page=" makes TMDB answer 400.
        return try {
            Tmdb.list(ctx, "/discover/movie", q, "movie").filter { it.posterPath != null || it.backdropPath != null }
                .also { if (RowPaging.page == 1) Log.i(TAG, "NewReleases: in theaters ${it.size}") }
        } catch (e: Exception) {
            Log.w(TAG, "NewReleases: in theaters failed: $e")
            throw e
        }
    }

    /** New digital and disc releases, [extra] narrowing them ("&with_genres=27", a service's providers). */
    fun movies(ctx: Context, extra: String = ""): List<Title> {
        val narrow = extra.isNotEmpty()
        val votes = if (narrow) 8 else 25
        fun q(days: Int) = "$BASE$extra&with_release_type=4|5&release_date.gte=${DreamTime.ymdPlus(-days)}&release_date.lte=${DreamTime.ymdPlus(0)}" +
            "&primary_release_date.gte=${DreamTime.ymdPlus(-540)}&vote_count.gte=$votes"
        val query = window(ctx, "movie", narrow, q(MOVIE_DAYS), q(WIDE_MOVIE_DAYS))
        return Tmdb.list(ctx, "/discover/movie", query, "movie").filter { it.posterPath != null || it.backdropPath != null }
    }

    /**
     * Brand-new series only (premiered in the last [TV_DAYS] days), [extra] narrowing them. Running shows with a
     * new season are [returning]'s: their own "New Episodes" row, never New Releases.
     */
    fun shows(ctx: Context, extra: String = ""): List<Title> {
        val narrow = extra.isNotEmpty()
        val skip = skipGenres(ctx, extra)
        fun series(days: Int) = "$BASE$extra$skip&first_air_date.gte=${DreamTime.ymdPlus(-days)}&first_air_date.lte=${DreamTime.ymdPlus(0)}" +
            "&vote_count.gte=${if (narrow) 3 else 10}"
        val query = window(ctx, "tv", narrow, series(TV_DAYS), series(WIDE_TV_DAYS))
        return Tmdb.list(ctx, "/discover/tv", query, "tv").filter { it.posterPath != null || it.backdropPath != null }
    }

    /**
     * The general "New Episodes" row: running shows whose latest season premiered in the last [TV_DAYS] days
     * (each checked against its seasons' dates, cached), most popular first, [extra] narrowing them.
     */
    fun returning(ctx: Context, extra: String = ""): List<Title> {
        val narrow = extra.isNotEmpty()
        val skip = skipGenres(ctx, extra)
        val days = if (narrow) WIDE_TV_DAYS else TV_DAYS
        val from = DreamTime.ymdPlus(-days)
        val running = "$BASE$extra$skip&air_date.gte=$from&air_date.lte=${DreamTime.ymdPlus(0)}&first_air_date.lte=${DreamTime.ymdPlus(-days - 1)}" +
            "&vote_count.gte=${if (narrow) 15 else 50}"
        val airing = Tmdb.list(ctx, "/discover/tv", running, "tv")
        return seasonPremieres(ctx, airing, from).filter { it.posterPath != null || it.backdropPath != null }
    }

    /**
     * Movies and shows interleaved; null [movieExtra] / [tvExtra]: none of that type. [lead] is the type that
     * goes first. Each type's failure only drops that type.
     */
    fun mixed(ctx: Context, movieExtra: String? = "", tvExtra: String? = "", lead: String = "movie"): List<Title> {
        val (m, t) = RowPaging.all(
            { movieExtra?.let { try { movies(ctx, it) } catch (e: Exception) { emptyList() } }.orEmpty() },
            { tvExtra?.let { try { shows(ctx, it) } catch (e: Exception) { emptyList() } }.orEmpty() },
        )
        return RowMix.interleave(if (lead == "tv") listOf(t, m) else listOf(m, t))
    }

    /** One type ([type] "movie" or "tv"). */
    fun ofType(ctx: Context, type: String, extra: String = ""): List<Title> = if (type == "movie") movies(ctx, extra) else shows(ctx, extra)

    /** [spec]'s new releases (a Browse genre or category), its main type first. */
    fun forGenre(ctx: Context, spec: GenreSpec): List<Title> {
        val types = GenreShelves.types(spec)
        val m = if ("movie" in types) GenreShelves.clause(spec, "movie") else null
        val t = if ("tv" in types) GenreShelves.clause(spec, "tv") else null
        if (!spec.anime) return mixed(ctx, m, t, spec.primary)
        // Anime: Japanese animation, and animation tagged "anime" in another language (Uzumaki).
        fun tagged(c: String?) = c?.replace("&with_original_language=ja", "&with_keywords=${Discover.ANIME_KEYWORD}")
        return RowMix.interleave(listOf(mixed(ctx, m, t, spec.primary), mixed(ctx, tagged(m), tagged(t), spec.primary)))
    }

    /** A streaming service's new releases (its US subscription catalog). */
    fun onService(ctx: Context, service: String): List<Title> {
        val ids = Discover.serviceIds(service).takeIf { it.isNotEmpty() } ?: return emptyList()
        val on = "&with_watch_providers=$ids&watch_region=US&with_watch_monetization_types=flatrate"
        return mixed(ctx, on, on, "tv")
    }

    /** Logs a filled row: "NewReleases: home 20 (12 movies, 8 shows)". */
    fun log(where: String, list: List<Title>?) {
        val l = list.orEmpty()
        val m = l.count { it.type == "movie" }
        Log.i(TAG, "NewReleases: $where ${l.size} ($m movies, ${l.size - m} shows)")
    }

    // ---- helpers ----

    /** [usual], or [wide] for a narrowed row whose first page of [usual] is thin (decided on page 1, so a deep row's pages agree). */
    private fun window(ctx: Context, type: String, narrow: Boolean, usual: String, wide: String): String {
        if (!narrow) return usual
        val first = try { RowPaging.at("NewReleases probe", 1) { Tmdb.list(ctx, "/discover/$type", usual, type) } } catch (e: Exception) { return usual }
        return if (first.size < THIN) wide else usual
    }

    /** "&without_genres=…" for news, soaps and talk (and kids' shows, outside a Kids profile), less what [extra] asks for. */
    private fun skipGenres(ctx: Context, extra: String): String {
        val asked = Regex("with_genres=([0-9,|]+)").find(extra)?.groupValues?.get(1)?.split(',', '|')?.toSet().orEmpty()
        val skip = (TV_SKIP + if (KidsFilter.active(ctx)) emptyList() else listOf("10762")).filter { it !in asked }
        return if (skip.isEmpty()) "" else "&without_genres=" + skip.joinToString(",")
    }

    /** show id -> (checked at, its latest season's premiere up to today, "" when none). */
    private val premiereCache = LruCache<Int, Pair<Long, String>>(600)
    private const val PREMIERE_TTL = 6 * 60 * 60_000L

    /** [airing] shows whose latest season (up to today) premiered on or after [from]. */
    private fun seasonPremieres(ctx: Context, airing: List<Title>, from: String): List<Title> {
        if (airing.isEmpty()) return airing
        val dates = RowPaging.all(*airing.map { t -> { try { latestSeason(ctx, t.id) } catch (e: Exception) { "" } } }.toTypedArray())
        return airing.filterIndexed { i, _ -> dates[i] >= from }
    }

    private fun latestSeason(ctx: Context, id: Int): String {
        val now = System.currentTimeMillis()
        synchronized(premiereCache) { premiereCache.get(id)?.takeIf { now - it.first < PREMIERE_TTL }?.let { return it.second } }
        val json = Net.getJson("https://api.themoviedb.org/3/tv/$id?api_key=${Tmdb.key(ctx)}&language=en-US")
        val today = DreamTime.ymdPlus(0)
        val seasons = json.optJSONArray("seasons")
        var latest = ""
        if (seasons != null) for (i in 0 until seasons.length()) {
            val s = seasons.optJSONObject(i) ?: continue
            val d = s.optString("air_date")
            if (s.optInt("season_number") >= 1 && d.length >= 10 && d != "null" && d <= today && d > latest) latest = d
        }
        json.optJSONObject("last_episode_to_air")?.let { ep -> episodeLine(ep)?.let { synchronized(lastEpisodes) { lastEpisodes.put(id, it) } } }
        synchronized(premiereCache) { premiereCache.put(id, now to latest) }
        return latest
    }

    /** Each returning show's newest aired episode line, filled by [latestSeason] from the details call it already makes. */
    private val lastEpisodes = LruCache<Int, String>(600)
    private val GENERIC_EPISODE = Regex("^(Episode|Ep\\.?)\\s*\\d+$", RegexOption.IGNORE_CASE)

    /**
     * "S22 E5 · Name" (or "New Episode · S22 E5" when TMDB names it only "Episode 5"): the newest aired episode under
     * a New Episodes card instead of "2005 · Series" (owner, 2026-10-28). Null when TMDB has none.
     */
    private fun episodeLine(ep: org.json.JSONObject): String? {
        val s = ep.optInt("season_number")
        val n = ep.optInt("episode_number")
        if (s < 1 || n < 1) return null
        val name = ep.optString("name").trim().takeIf { it != "null" }.orEmpty()
        return if (name.isEmpty() || GENERIC_EPISODE.matches(name)) "New Episode · S$s E$n" else "S$s E$n · $name"
    }

    /** A New Episodes card's subtitle: the show's newest episode, null when unknown (the card keeps its usual line). */
    fun episodeSub(t: Title): String? = synchronized(lastEpisodes) { lastEpisodes.get(t.id) }
}
