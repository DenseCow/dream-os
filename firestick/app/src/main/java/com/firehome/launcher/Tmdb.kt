package com.firehome.launcher

import android.content.Context
import android.util.LruCache
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.FutureTask

class Title(
    val id: Int,
    val type: String, // "movie" or "tv"
    val name: String,
    val overview: String,
    val posterPath: String?,
    val backdropPath: String?,
    val year: String,
    val rating: Double,
    /** Release (movies) or first air date (shows), "yyyy-MM-dd"; empty when unknown. */
    val date: String = "",
    /** TMDB vote count: [rating] means little below a few dozen votes (0 = unknown). */
    val votes: Int = 0,
    /** TMDB genre ids from list results (empty when unknown, e.g. a saved title). */
    val genreIds: List<Int> = emptyList(),
    /** Original language (ISO 639-1) from list results, "" when unknown. */
    val lang: String = "",
    /** TMDB's adult flag (list results); such titles never make a row ([RowMix.keep]). */
    val adult: Boolean = false,
    /**
     * Where it was made ("JP", "US"), from list results; TMDB's movie lists leave it out, so a movie's is empty
     * until its details were asked for ([Tmdb.origin]).
     */
    val originCountry: List<String> = emptyList(),
) {
    fun poster(size: String = "w185") = posterPath?.let { "${Tmdb.IMG}$size$it" }
    fun backdrop(size: String = "w300") = backdropPath?.let { "${Tmdb.IMG}$size$it" }
    val key get() = "$type:$id"
}

class Details(
    val title: Title,
    val genres: List<String>,
    val runtime: String,
    val trailerKey: String?,
    val providers: List<Provider>,
    /** Top-billed cast (at most 15), from the same request. */
    val cast: List<CastMember> = emptyList(),
    /** "More like this" (at most 20), from the same request. */
    val recommendations: List<Title> = emptyList(),
    /** Shows: every season TMDB lists, specials included. */
    val seasons: List<Season> = emptyList(),
    /** Fallbacks for titles built from partial data (watch history, My List). */
    val overview: String = "",
    val backdropPath: String? = null,
    /** The title page's Information section (dates, people, studios, money), from the same request; null for partial data. */
    val info: TitleInfo? = null,
)

class Provider(val id: Int, val name: String)

class CastMember(val name: String, val character: String, val profilePath: String?) {
    fun profile(size: String = "w185") = profilePath?.let { "${Tmdb.IMG}$size$it" }
}

class Season(val number: Int, val name: String, val episodes: Int, val airDate: String)

/** themoviedb.org client. Key comes from local.properties (tmdb.key) or the Settings menu. */
object Tmdb {
    const val IMG = "https://image.tmdb.org/t/p/"
    private const val API = "https://api.themoviedb.org/3"

    /** Streaming services we can link to, by TMDB provider id. */
    val SERVICES = listOf(
        Service(8, "Netflix", "com.netflix.ninja", "https://www.netflix.com/search?q=%s"),
        Service(9, "Prime Video", "com.amazon.firebat", "https://app.primevideo.com/search?phrase=%s"),
        Service(119, "Prime Video", "com.amazon.firebat", "https://app.primevideo.com/search?phrase=%s"),
        Service(386, "Peacock", "com.peacock.peacockfiretv"),
        Service(387, "Peacock", "com.peacock.peacockfiretv"),
        Service(337, "Disney+", "com.disney.disneyplus"),
        Service(15, "Hulu", "com.hulu.plus"),
        Service(1899, "HBO Max", "com.wbd.stream"),
        // Paramount+ is two plans on TMDB now (Premium, Essential); its old id 531 lists almost nothing.
        Service(2303, "Paramount+", "com.cbs.ott"),
        Service(2616, "Paramount+", "com.cbs.ott"),
        Service(350, "Apple TV", "com.apple.atve.amazon.appletv"),
        Service(283, "Crunchyroll", "com.crunchyroll.crunchyroid"),
        Service(526, "AMC+", "com.amcplus.amcfiretv"),
        Service(73, "Tubi", "com.tubitv.ott"),
        Service(300, "Pluto TV", "tv.pluto.android"),
    )

    /** [search] is a deep link (with %s for the title) that opens the app on that title; null = just launch. */
    class Service(val providerId: Int, val name: String, val pkg: String, val search: String? = null)

    /**
     * The viewer's own TMDB key (owner, 2026-10-28, same as the iPhone: no built-in key option; the public build
     * ships with no keys). A build that carries the owner's key (local.properties) hands it over once, as if the
     * owner had typed it in, so it shows as their saved key; it never overwrites or comes back after a key the
     * viewer entered or removed. Every TMDB call reads this.
     */
    fun key(ctx: Context): String {
        val p = ctx.getSharedPreferences("tmdb", Context.MODE_PRIVATE)
        p.getString("key", null)?.takeIf { it.isNotBlank() }?.let { return it }
        if (!p.getBoolean("adopted", false)) {
            val built = BuildConfig.TMDB_KEY.trim()
            val e = p.edit().putBoolean("adopted", true)
            if (built.isNotEmpty() && !p.contains("key")) e.putString("key", built)
            e.apply() // the in-memory copy updates at once
        }
        return p.getString("key", null)?.takeIf { it.isNotBlank() }.orEmpty()
    }

    fun setKey(ctx: Context, key: String) =
        ctx.getSharedPreferences("tmdb", Context.MODE_PRIVATE).edit().putString("key", key.trim()).apply()

    fun hasKey(ctx: Context) = key(ctx).isNotBlank()

    private fun url(ctx: Context, path: String, query: String = ""): String {
        val apart = RowMix.animeApart(ctx)
        val q = single(query + kidsClause(ctx, path) + if (apart) NewReleases.animeClause(path, query) else "")
        return "$API$path?api_key=${key(ctx)}&language=en-US&region=US${if (apart) q else NewReleases.withAnime(q)}"
    }

    /** A profile with a rating limit: /discover asks TMDB to apply it too ([KidsFilter.discoverClause]). */
    private fun kidsClause(ctx: Context, path: String) =
        if (path.startsWith("/discover/")) KidsFilter.discoverClause(ctx, path.removePrefix("/discover/")) else ""

    /**
     * [query] with each parameter once: TMDB answers 400 to a repeated one, and a category's own filter
     * (a decade's dates, Feel-Good's score floor, Adult Animation's left-out keywords) can meet a row's.
     * Repeats fold: genres and keywords left out add up, genres asked for AND together, a ".gte" floor
     * takes the higher value and a ".lte" ceiling the lower (a decade inside "the last two years" is then
     * empty, and that row is simply left out); anything else, the last one wins.
     */
    fun single(query: String): String {
        if (query.isEmpty()) return query
        val parts = query.split('&').filter { it.isNotEmpty() }
        val keys = parts.map { it.substringBefore('=') }
        if (keys.size == keys.toSet().size) return query
        val m = LinkedHashMap<String, String>()
        fun higher(a: String, b: String): Boolean {
            val x = a.toDoubleOrNull()
            val y = b.toDoubleOrNull()
            return if (x != null && y != null) y > x else b > a
        }
        for (p in parts) {
            val k = p.substringBefore('=')
            val v = p.substringAfter('=', "")
            val old = m[k]
            m[k] = when {
                old == null -> v
                k == "without_genres" || k == "without_keywords" -> (old.split(',') + v.split(',')).filter { it.isNotEmpty() }.distinct().joinToString(",")
                k == "with_genres" -> if (old.contains('|') || v.contains('|')) v else (old.split(',') + v.split(',')).distinct().joinToString(",")
                k.endsWith(".gte") -> if (higher(old, v)) v else old
                k.endsWith(".lte") -> if (higher(old, v)) old else v
                else -> v
            }
        }
        return m.entries.joinToString("") { "&${it.key}=${it.value}" }
    }

    // Bounded (search adds one entry per query); expired entries are dropped when hit.
    // Sized for the Movies and TV Shows pages' category rows (a list is ~20 small objects).
    private val cache = LruCache<String, Pair<Long, List<Title>>>(128)
    // Identical requests in flight share one fetch (trending is asked for twice at boot).
    private val inFlight = HashMap<String, FutureTask<List<Title>>>()

    /** [path]'s titles, without what the profile's rating limit leaves out ([KidsFilter]; the cache keeps them all). */
    fun list(ctx: Context, path: String, query: String = "", type: String? = null): List<Title> =
        KidsFilter.titles(ctx, listAll(ctx, path, query, type), trusted = path.startsWith("/discover/movie"))

    private fun listAll(ctx: Context, path: String, query: String, type: String?): List<Title> {
        val u = url(ctx, path, RowPaging.paged(query))
        val task: FutureTask<List<Title>>
        val owner: Boolean
        synchronized(cache) {
            cache.get(u)?.let { hit ->
                if (System.currentTimeMillis() - hit.first < 30 * 60_000) return hit.second
                cache.remove(u)
            }
            val running = inFlight[u]
            owner = running == null
            task = running ?: FutureTask<List<Title>> {
                val json = Net.getJson(u)
                val results = json.getJSONArray("results")
                val titles = (0 until results.length()).mapNotNull { parse(results.getJSONObject(it), type) }
                synchronized(cache) { cache.put(u, System.currentTimeMillis() to titles) }
                titles
            }.also { inFlight[u] = it }
        }
        if (owner) {
            try { task.run() } finally { synchronized(cache) { inFlight.remove(u) } }
        }
        try {
            return task.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? Exception) ?: e
        }
    }

    fun trending(ctx: Context) = list(ctx, "/trending/all/week")

    /**
     * A trending list as Dream OS shows it outside the anime sections: only titles out today ([RowMix.released]),
     * no anime when the profile keeps it apart ([RowMix.animeHidden]), nothing adult, and with the art the place
     * needs ([art]: "backdrop" or "poster"). The Kids limit already applies in [list]. Blocking.
     */
    fun trendingShown(ctx: Context, path: String = "/trending/all/week", query: String = "", type: String? = null, art: String? = null): List<Title> =
        list(ctx, path, query, type).filter { t ->
            (art != "backdrop" || t.backdropPath != null) && (art != "poster" || t.posterPath != null) &&
                !RowMix.animeHidden(ctx, t) && !t.adult && RowMix.released(t)
        }

    fun search(ctx: Context, q: String) =
        list(ctx, "/search/multi", "&query=" + URLEncoder.encode(q, "UTF-8"))

    /**
     * Search as people (and Alexa) say titles: "yellow jackets" finds Yellowjackets. TMDB matches whole
     * words only, so a multi-word query is also searched with its words joined (all, and each adjacent
     * pair). Titles whose name equals the query once spaces and punctuation are ignored come first;
     * the rest keep TMDB's order, the plain query's results before the variants'.
     */
    fun searchSpoken(ctx: Context, q: String): List<Title> {
        val words = q.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val variants = LinkedHashSet<String>()
        if (words.size in 2..5) {
            variants.add(words.joinToString(""))
            if (words.size > 2) for (i in 0 until words.size - 1) {
                variants.add((words.take(i) + (words[i] + words[i + 1]) + words.drop(i + 2)).joinToString(" "))
            }
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(maxOf(1, variants.size))
        val extra = try {
            variants.map { v -> pool.submit<List<Title>> { try { search(ctx, v) } catch (e: Exception) { emptyList() } } }
        } finally { pool.shutdown() }
        val plain = search(ctx, q)
        val all = LinkedHashMap<String, Title>()
        (plain + extra.flatMap { it.get() }).forEach { all.putIfAbsent(it.key, it) }
        val want = squash(q)
        val (exact, rest) = all.values.partition { squash(it.name) == want }
        return exact.sortedByDescending { it.votes } + rest
    }

    /** A person found by Search, with the movies and shows they're in (or directed). */
    class PersonResult(val name: String, val heading: String, val titles: List<Title>)

    /**
     * "brad pitt", "brad pitt movies", "tom hanks shows", "movies with keanu reeves": that person's
     * movies and shows, best known first. A trailing (or leading) "movies"/"films" or "shows"/"series"
     * keeps one kind. Null when the words don't name a person TMDB knows (so title searches stay as
     * they were). Acting roles, or directing for a director; talk shows, news and "Himself" cameos are left out.
     */
    fun searchPerson(ctx: Context, q: String): PersonResult? {
        var words = q.trim().lowercase().replace(Regex("[^a-z0-9' ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        var movies = true
        var shows = true
        val kindWords = setOf("movies", "movie", "films", "film", "shows", "show", "series", "tv")
        val filler = setOf("with", "starring", "by", "and", "the", "in", "from", "all", "best")
        if (words.any { it in kindWords }) {
            val wantsMovies = words.any { it == "movies" || it == "movie" || it == "films" || it == "film" }
            val wantsShows = words.any { it == "shows" || it == "show" || it == "series" || it == "tv" }
            movies = wantsMovies || !wantsShows
            shows = wantsShows || !wantsMovies
        }
        val hadKind = words.any { it in kindWords }
        words = words.filter { it !in kindWords }
        // Leading/trailing filler only ("movies with brad pitt", "brad pitt and"), never inside a name.
        while (words.isNotEmpty() && words.first() in filler) words = words.drop(1)
        while (words.isNotEmpty() && words.last() in filler) words = words.dropLast(1)
        if (words.size < 2 && !hadKind) return null // one word is a title search ("Cars"), not a person
        val name = words.joinToString(" ")
        if (squash(name).length < 4) return null
        val found = Net.getJson(url(ctx, "/search/person", "&query=" + URLEncoder.encode(name, "UTF-8"))).optJSONArray("results") ?: return null
        val want = squash(name)
        val person = (0 until minOf(found.length(), 3)).map { found.getJSONObject(it) }.firstOrNull { squash(it.optString("name")) == want }
            ?: found.optJSONObject(0)?.takeIf { hadKind && it.optDouble("popularity", 0.0) >= 2.0 }
            ?: return null
        val directs = person.optString("known_for_department") == "Directing"
        val credits = Net.getJson(url(ctx, "/person/${person.getInt("id")}/combined_credits"))
        val list = credits.optJSONArray(if (directs) "crew" else "cast") ?: return null
        val skipGenres = setOf(10767, 10763) // talk, news
        val cameo = Regex("(?i)\\b(self|himself|herself|narrator|host)\\b")
        val titles = (0 until list.length()).mapNotNull { i ->
            val o = list.getJSONObject(i)
            if (directs && o.optString("job") != "Director") return@mapNotNull null
            if (cameo.containsMatchIn(o.optString("character"))) return@mapNotNull null
            parse(o, null)
        }.filter { t ->
            t.posterPath != null && (t.type == "movie" && movies || t.type == "tv" && shows) && t.genreIds.none { it in skipGenres } && t.votes >= 20
        }.distinctBy { it.key }.sortedByDescending { it.votes }.take(40).let { KidsFilter.titles(ctx, it) }
        if (titles.isEmpty()) return null
        val who = person.optString("name")
        val kind = when { movies && shows -> "Movies & Shows"; movies -> "Movies"; else -> "Shows" }
        return PersonResult(who, if (directs) "$kind Directed by $who" else "$kind with $who", titles)
    }

    /** Lowercase letters and digits only: "Yellow Jackets" and "Yellowjackets" both become "yellowjackets". */
    private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Popular titles available on one service (US, subscription): rated by enough people to mean
     * something, and from the last [RowMix.RECENT_YEARS] years (the service's archive lives in Browse).
     */
    fun onService(ctx: Context, providerId: Int, type: String) = list(
        ctx, "/discover/$type",
        "&with_watch_providers=$providerId&watch_region=US&with_watch_monetization_types=flatrate&sort_by=popularity.desc" +
            "&include_adult=false&vote_count.gte=${if (type == "movie") 150 else 60}&${RowMix.dateKey(type)}.gte=${RowMix.yearsAgo(RowMix.RECENT_YEARS)}",
        type,
    )

    /** One page of a /discover query, with TMDB's totals (for paging and the result count). */
    class Results(val titles: List<Title>, val page: Int, val totalPages: Int, val totalResults: Int)

    /** /discover/[type] ("movie" or "tv") with [query] (each part starting with "&"), page [page]. Not cached: each page is read once. */
    fun discover(ctx: Context, type: String, query: String, page: Int): Results {
        val json = Net.getJson(url(ctx, "/discover/$type", "$query&page=$page"))
        val a = json.optJSONArray("results")
        val titles = KidsFilter.titles(ctx, if (a == null) emptyList() else (0 until a.length()).mapNotNull { parse(a.getJSONObject(it), type) },
            trusted = type == "movie")
        // TMDB serves at most 500 pages of any query.
        return Results(titles, json.optInt("page", page), minOf(500, json.optInt("total_pages", 1)), json.optInt("total_results", titles.size))
    }

    // One bounded cache shared by the preview, the details page and the trailer button.
    private val detailsCache = LruCache<String, Details>(64)

    fun cachedDetails(t: Title): Details? = detailsCache.get(t.key)

    fun details(ctx: Context, t: Title): Details {
        val d = detailsCache.get(t.key) ?: fetchDetails(ctx, t).also { detailsCache.put(t.key, it) }
        if (!KidsFilter.active(ctx) || d.recommendations.isEmpty()) return d
        // A rating limit: More Like This keeps only what this profile may see (the cache keeps them all).
        return Details(d.title, d.genres, d.runtime, d.trailerKey, d.providers, d.cast, KidsFilter.titles(ctx, d.recommendations),
            d.seasons, d.overview, d.backdropPath, d.info)
    }

    private fun fetchDetails(ctx: Context, t: Title): Details {
        // US release days / age rating ride along for the Information section (TitleInfo): no second request.
        val ratingsPart = if (t.type == "movie") "release_dates" else "content_ratings"
        val json = Net.getJson(url(ctx, "/${t.type}/${t.id}", "&append_to_response=videos,watch/providers,credits,recommendations,$ratingsPart"))
        noteOrigin(t.type, t.id, json)
        val genres = json.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }.orEmpty()
        val runtime = when (t.type) {
            "movie" -> TitleInfo.runtimeWords(json.optInt("runtime")) ?: "" // "2 hr 10 min", as everywhere on the title page
            else -> json.optInt("number_of_seasons").takeIf { it > 0 }?.let { "$it Season" + if (it > 1) "s" else "" } ?: ""
        }
        val videos = json.optJSONObject("videos")?.optJSONArray("results")
        val trailer = videos?.let { v ->
            (0 until v.length()).map { v.getJSONObject(it) }
                .filter { it.optString("site") == "YouTube" }
                .sortedByDescending { if (it.optString("type") == "Trailer") 1 else 0 }
                .firstOrNull()?.optString("key")
        }
        val us = json.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject("US")
        val flat = us?.optJSONArray("flatrate")
        val providers = flat?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { Provider(it.getInt("provider_id"), it.getString("provider_name")) }
        }.orEmpty()
        val cast = json.optJSONObject("credits")?.optJSONArray("cast")?.let { a ->
            (0 until minOf(a.length(), 15)).map { a.getJSONObject(it) }.map {
                CastMember(it.optString("name"), it.optString("character"),
                    it.optString("profile_path").takeIf { p -> p.isNotEmpty() && p != "null" })
            }.filter { it.name.isNotEmpty() }
        }.orEmpty()
        val recs = json.optJSONObject("recommendations")?.optJSONArray("results")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                parse(o, o.optString("media_type").takeIf { it == "movie" || it == "tv" } ?: t.type)
            }.filter { it.key != t.key }.take(20)
        }.orEmpty()
        val seasons = json.optJSONArray("seasons")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                // A season with no date yet has "air_date": null, which optString turns into the text "null".
                Season(it.optInt("season_number"), it.optString("name"), it.optInt("episode_count"),
                    if (it.isNull("air_date")) "" else it.optString("air_date"))
            }
        }.orEmpty()
        val info = try { TitleInfo.parse(json, t, genres) } catch (e: Exception) { null }
        return Details(t, genres, runtime, trailer, providers, cast, recs, seasons, json.optString("overview"),
            json.optString("backdrop_path").takeIf { it.isNotEmpty() && it != "null" }, info)
    }

    /** Age rating ("PG-13", "TV-MA"; empty when none), IMDb id and TMDB's own score, for [Ratings]. */
    class Certification(
        val rating: String, val imdbId: String?, val score: Double, val votes: Int, val released: String,
        /** "2h 10m" (movies) or "9 Seasons" (shows), as [details] words it; empty when unknown. */
        val runtime: String = "",
        val genres: List<String> = emptyList(),
    )

    /** One request: US certification first (theatrical, then digital, then any), else the first non-empty one. */
    fun certification(ctx: Context, t: Title): Certification {
        val movie = t.type == "movie"
        val json = Net.getJson(url(ctx, "/${t.type}/${t.id}",
            "&append_to_response=" + if (movie) "release_dates,external_ids" else "content_ratings,external_ids"))
        noteOrigin(t.type, t.id, json)
        val results = json.optJSONObject(if (movie) "release_dates" else "content_ratings")?.optJSONArray("results")
        val byCountry = LinkedHashMap<String, String>()
        if (results != null) for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val country = o.optString("iso_3166_1")
            val cert = if (!movie) o.optString("rating").trim() else o.optJSONArray("release_dates")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                    .sortedBy { when (it.optInt("type")) { 3 -> 0; 4 -> 1; 2 -> 2; 5 -> 3; else -> 4 } }
                    .map { it.optString("certification").trim() }.firstOrNull { it.isNotEmpty() }
            }.orEmpty()
            if (cert.isNotEmpty() && country !in byCountry) byCountry[country] = cert
        }
        val rating = byCountry["US"] ?: byCountry.values.firstOrNull().orEmpty()
        try { KidsFilter.remember(ctx, t, byCountry["US"].orEmpty()) } catch (e: Exception) {}
        val imdb = (json.optJSONObject("external_ids")?.optString("imdb_id") ?: json.optString("imdb_id"))
            ?.takeIf { it.startsWith("tt") }
            ?: json.optString("imdb_id").takeIf { it.startsWith("tt") }
        // Shows count as recent while they're still airing.
        val released = if (movie) json.optString("release_date") else json.optString("last_air_date").ifEmpty { json.optString("first_air_date") }
        // The same call carries what the hero's meta line needs, so it never waits for the full details.
        val runtime = if (movie) TitleInfo.runtimeWords(json.optInt("runtime")).orEmpty()
        else json.optInt("number_of_seasons").takeIf { it > 0 }?.let { "$it Season" + if (it > 1) "s" else "" }.orEmpty()
        val genres = json.optJSONArray("genres")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.ifEmpty { null } } }.orEmpty()
        return Certification(rating, imdb, json.optDouble("vote_average", 0.0), json.optInt("vote_count", 0),
            released.takeIf { it != "null" }.orEmpty(), runtime, genres)
    }

    private fun parse(o: JSONObject, forcedType: String?): Title? {
        val type = forcedType ?: o.optString("media_type")
        if (type != "movie" && type != "tv") return null
        val date = o.optString(if (type == "movie") "release_date" else "first_air_date")
        return Title(
            id = o.getInt("id"),
            type = type,
            name = o.optString(if (type == "movie") "title" else "name"),
            overview = o.optString("overview"),
            posterPath = o.optString("poster_path").takeIf { it.isNotEmpty() && it != "null" },
            backdropPath = o.optString("backdrop_path").takeIf { it.isNotEmpty() && it != "null" },
            year = date.take(4),
            rating = o.optDouble("vote_average", 0.0),
            date = date.takeIf { it.length >= 10 }.orEmpty(),
            votes = o.optInt("vote_count", 0),
            genreIds = o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty(),
            lang = o.optString("original_language"),
            adult = o.optBoolean("adult", false),
            originCountry = countries(o),
        )
    }

    /** A TMDB object's "origin_country" ("JP"); empty when it has none (movie list results). */
    internal fun countries(o: JSONObject): List<String> =
        o.optJSONArray("origin_country")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.length == 2 } }.orEmpty()

    // Origin countries learned from details calls already made (title page, hero meta, history): a movie list result
    // has none, and the anime rule ([RowMix.isAnime]) reads it. Memory only, bounded; never a call of its own.
    private val origins = LruCache<String, List<String>>(512)

    /** Remembers [json]'s origin countries (a "/movie/{id}" or "/tv/{id}" answer) for [origin]. */
    internal fun noteOrigin(type: String, id: Int, json: JSONObject) {
        countries(json).takeIf { it.isNotEmpty() }?.let { origins.put("$type:$id", it) }
    }

    /** [t]'s origin countries: its own, else what an earlier details call said; empty when neither knows. */
    fun origin(t: Title): List<String> = t.originCountry.ifEmpty { origins.get(t.key).orEmpty() }

    // ---- My List (saved titles), stored as JSON per profile ----

    fun myList(ctx: Context): List<Title> {
        val raw = ctx.getSharedPreferences("tmdb", Context.MODE_PRIVATE).getString("mylist" + Profiles.suffix(ctx), "[]")!!
        val a = org.json.JSONArray(raw)
        return (0 until a.length()).mapNotNull { parse(a.getJSONObject(it), a.getJSONObject(it).optString("media_type")) }
    }

    fun inMyList(ctx: Context, t: Title) = myList(ctx).any { it.key == t.key }

    private val myListLock = Any()

    fun toggleMyList(ctx: Context, t: Title) {
        // Trakt's watchlist pull toggles from a background thread while the remote can toggle on the main one.
        val list = synchronized(myListLock) {
            val list = myList(ctx).toMutableList()
            if (list.removeAll { it.key == t.key }.not()) list.add(0, t)
            val a = org.json.JSONArray()
            for (x in list) a.put(JSONObject().apply {
                put("id", x.id); put("media_type", x.type)
                put(if (x.type == "movie") "title" else "name", x.name)
                put("overview", x.overview); put("poster_path", x.posterPath ?: ""); put("backdrop_path", x.backdropPath ?: "")
                // The full date (pills and Taste read it; a bare year read back as no date), genres and language
                // (Taste's affinities, the anime check) as the list result had them.
                put(if (x.type == "movie") "release_date" else "first_air_date", x.date.ifEmpty { x.year }); put("vote_average", x.rating)
                put("vote_count", x.votes)
                if (x.genreIds.isNotEmpty()) put("genre_ids", org.json.JSONArray(x.genreIds))
                if (x.lang.isNotEmpty()) put("original_language", x.lang)
                if (x.originCountry.isNotEmpty()) put("origin_country", org.json.JSONArray(x.originCountry))
            })
            ctx.getSharedPreferences("tmdb", Context.MODE_PRIVATE).edit().putString("mylist" + Profiles.suffix(ctx), a.toString()).apply()
            list
        }
        try { Trakt.onMyListChanged(ctx, t, list.any { it.key == t.key }) } catch (e: Exception) {} // the Trakt watchlist follows
    }
}
