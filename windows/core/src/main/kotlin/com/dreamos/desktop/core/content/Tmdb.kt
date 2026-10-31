package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.net.HttpError
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.util.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * themoviedb.org client (fire-home Tmdb.kt). Every TMDB call reads the viewer's own key ([key]; Dream OS ships none)
 * and goes through [Net] (paced by TmdbGate, 429s retried). Lists are cached 30 minutes; identical requests in flight
 * share one fetch. Blocking: IO threads only (the services switch to them).
 *
 * Title-page details, seasons and episodes are in Details.kt; this file is lists, search and discover.
 */
object Tmdb {
    const val API = "https://api.themoviedb.org/3"

    /**
     * Streaming services Where to Watch can open, by TMDB provider id (US). On a PC the service is its website:
     * [web] opens its search for the title (%s), else its home page. YouTube TV is a real service and stays; YouTube's
     * movie store never shows.
     */
    class Service(val providerId: Int, val name: String, val home: String, val web: String? = null)

    val SERVICES = listOf(
        Service(8, "Netflix", "https://www.netflix.com/", "https://www.netflix.com/search?q=%s"),
        Service(9, "Prime Video", "https://www.primevideo.com/", "https://www.primevideo.com/search/ref=atv_nb_sug?phrase=%s"),
        Service(119, "Prime Video", "https://www.primevideo.com/", "https://www.primevideo.com/search/ref=atv_nb_sug?phrase=%s"),
        Service(386, "Peacock", "https://www.peacocktv.com/"),
        Service(387, "Peacock", "https://www.peacocktv.com/"),
        Service(337, "Disney+", "https://www.disneyplus.com/"),
        Service(15, "Hulu", "https://www.hulu.com/"),
        Service(1899, "HBO Max", "https://www.max.com/"),
        // Paramount+ is two plans on TMDB now (Premium, Essential); its old id 531 lists almost nothing.
        Service(2303, "Paramount+", "https://www.paramountplus.com/"),
        Service(2616, "Paramount+", "https://www.paramountplus.com/"),
        Service(350, "Apple TV", "https://tv.apple.com/", "https://tv.apple.com/search?term=%s"),
        Service(283, "Crunchyroll", "https://www.crunchyroll.com/", "https://www.crunchyroll.com/search?q=%s"),
        Service(526, "AMC+", "https://www.amcplus.com/"),
        Service(73, "Tubi", "https://tubitv.com/", "https://tubitv.com/search/%s"),
        Service(300, "Pluto TV", "https://pluto.tv/"),
    )

    /**
     * Where to Watch leaves out YouTube's movie store (TMDB provider 192, or any "YouTube" that isn't YouTube TV);
     * YouTube TV is a real streaming service and shows (owner, 2026-10-28).
     */
    fun hiddenProvider(id: Int, name: String): Boolean =
        name.isBlank() || id == 192 || (name.contains("YouTube", true) && !name.equals("YouTube TV", true))

    // ---- the viewer's key ----

    private fun keys() = DreamStore.shared.file("tmdb")

    /** The viewer's own TMDB key ("" until setup's Connect chapter saves one). */
    fun key(): String = keys().getString("key")?.trim().orEmpty()

    fun setKey(key: String) = keys().putString("key", key.trim())
    fun hasKey() = key().isNotBlank()

    /** Checks [k] with one tiny request: true when TMDB accepts it. Throws when TMDB can't be reached. */
    fun checkKey(k: String): Boolean = try {
        Net.getText("$API/configuration?api_key=${Net.encode(k.trim())}")
        true
    } catch (e: HttpError) {
        if (e.code == 401 || e.code == 404) false else throw e
    }

    // ---- URLs ----

    /**
     * [path] with the key, language and region, the profile's Kids clause (/discover), and the anime rule: every
     * /discover query that doesn't ask for anime leaves out TMDB's anime keyword when the profile keeps anime apart.
     * Never add language, region or page in a query: a repeated parameter makes TMDB answer 400 ([single] folds them).
     */
    fun url(path: String, query: String = ""): String {
        val apart = ContentPrefs.animeApart()
        val q = single(query + kidsClause(path) + if (apart) animeClause(path, query) else "")
        return "$API$path?api_key=${key()}&language=en-US&region=US${if (apart) q else withAnime(q)}"
    }

    private fun kidsClause(path: String) =
        if (path.startsWith("/discover/")) KidsFilter.discoverClause(path.removePrefix("/discover/")) else ""

    /** TMDB's "anime" keyword: left out of every non-anime /discover (anime stays in the anime sections). */
    const val ANIME_KEYWORD = 210024

    private val ANIME_ASKED = listOf(
        Regex("(^|&)with_original_language=ja(&|$)"),
        Regex("(^|&)with_keywords=[^&]*\\b$ANIME_KEYWORD\\b"),
        Regex("(^|&)with_watch_providers=[^&]*\\b283\\b"), // Crunchyroll
    )

    /** "&without_keywords=210024" for a /discover that doesn't ask for anime (fire-home NewReleases.animeClause). */
    fun animeClause(path: String, query: String): String =
        if (!path.startsWith("/discover/") || ANIME_ASKED.any { it.containsMatchIn(query) }) ""
        else "&without_keywords=$ANIME_KEYWORD"

    /** Anime kept everywhere (setting off): [query] without TMDB's anime keyword among the left-out keywords. */
    fun withAnime(query: String): String = query.split('&').filter { it.isNotEmpty() }.mapNotNull { p ->
        if (!p.startsWith("without_keywords=")) return@mapNotNull p
        val v = p.substringAfter('=')
        val sep = if ('|' in v) "|" else ","
        v.split(',', '|').filter { it.isNotEmpty() && it != "$ANIME_KEYWORD" }
            .takeIf { it.isNotEmpty() }?.joinToString(sep, "without_keywords=")
    }.joinToString("") { "&$it" }

    /**
     * [query] with each parameter once: TMDB answers 400 to a repeated one. Repeats fold: genres and keywords left out
     * add up, genres asked for AND together, a ".gte" floor takes the higher value and a ".lte" ceiling the lower;
     * anything else, the last one wins.
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

    // ---- lists ----

    /**
     * Cached lists by URL, 30 minutes (on disk longer: TmdbDisk). Worst case: 1,024 lists of 20 titles (~1 KB each) =
     * about 20 MB. A PC has room for every page's rows, the Browse pages and a long Search session.
     */
    private const val MAX_LISTS = 1024
    private val cache = object : LinkedHashMap<String, Pair<Long, List<Title>>>(MAX_LISTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<Title>>>?) = size > MAX_LISTS
    }
    private val inFlight = HashMap<String, FutureTask<List<Title>>>()
    private const val LIST_TTL = 30 * 60_000L
    /** A list read stale from disk is asked again after this long (its fresh copy is being fetched behind). */
    private const val STALE_HOLD = 90_000L

    /** [path]'s titles, without what the profile's rating limit leaves out (the cache keeps them all). */
    fun list(path: String, query: String = "", type: MediaKind? = null): List<Title> =
        KidsFilter.titles(listAll(path, query, type), trusted = path.startsWith("/discover/movie"))

    private fun listAll(path: String, query: String, type: MediaKind?): List<Title> {
        val u = url(path, RowPaging.paged(query))
        val task: FutureTask<List<Title>>
        val owner: Boolean
        synchronized(cache) {
            cache[u]?.let { hit ->
                if (System.currentTimeMillis() - hit.first < LIST_TTL) return hit.second
                cache.remove(u)
            }
            val running = inFlight[u]
            owner = running == null
            task = running ?: FutureTask {
                val answer = Net.tmdb(u)
                val titles = parseJson(answer.body).jsonObject.a("results").objects().mapNotNull { parse(it, type) }
                val at = System.currentTimeMillis() - if (answer.stale) LIST_TTL - STALE_HOLD else 0L
                synchronized(cache) { cache[u] = at to titles }
                titles
            }.also { inFlight[u] = it }
        }
        if (owner) {
            try { task.run() } finally { synchronized(cache) { inFlight.remove(u) } }
        }
        try {
            return task.get()
        } catch (e: ExecutionException) {
            throw (e.cause as? Exception) ?: e
        }
    }

    /** Forgets every cached list (the key, the profile or a content setting changed). */
    fun clearLists() = synchronized(cache) { cache.clear() }

    fun trending(): List<Title> = list("/trending/all/week")

    /**
     * A trending list as Dream OS shows it outside the anime sections: only titles out today ([RowMix.released]), no
     * anime when the profile keeps it apart, nothing adult, and with the art the place needs ([art]: "backdrop" or
     * "poster"). The Kids limit already applies in [list].
     */
    fun trendingShown(path: String = "/trending/all/week", query: String = "", type: MediaKind? = null, art: String? = null): List<Title> =
        list(path, query, type).filter { t ->
            (art != "backdrop" || t.backdropPath != null) && (art != "poster" || t.posterPath != null) &&
                !RowMix.animeHidden(t) && !t.adult && RowMix.released(t)
        }

    fun search(q: String): List<Title> = list("/search/multi", "&query=" + Net.encode(q))

    /**
     * Search as people say titles: "yellow jackets" finds Yellowjackets. TMDB matches whole words only, so a
     * multi-word query is also searched with its words joined (all, and each adjacent pair). Titles whose name equals
     * the query once spaces and punctuation are ignored come first; the rest keep TMDB's order.
     */
    fun searchSpoken(q: String): List<Title> {
        val words = q.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val variants = LinkedHashSet<String>()
        if (words.size in 2..5) {
            variants.add(words.joinToString(""))
            if (words.size > 2) for (i in 0 until words.size - 1) {
                variants.add((words.take(i) + (words[i] + words[i + 1]) + words.drop(i + 2)).joinToString(" "))
            }
        }
        val tasks: List<() -> List<Title>> = listOf<() -> List<Title>>({ search(q) }) +
            variants.map { v -> { try { search(v) } catch (e: Exception) { emptyList() } } }
        val results = RowPaging.all(*tasks.toTypedArray())
        val all = LinkedHashMap<String, Title>()
        results.flatten().forEach { all.putIfAbsent(it.key, it) }
        val want = squash(q)
        val (exact, rest) = all.values.partition { squash(it.name) == want }
        return exact.sortedByDescending { it.votes } + rest
    }

    /** A person found by Search, with the movies and shows they're in (or directed). */
    class PersonResult(val id: Int, val name: String, val heading: String, val titles: List<Title>)

    /**
     * "brad pitt", "brad pitt movies", "tom hanks shows", "movies with keanu reeves": that person's movies and shows,
     * best known first. Null when the words don't name a person TMDB knows (title searches stay as they were). Acting
     * roles, or directing for a director; talk shows, news and "Himself" cameos are left out.
     */
    fun searchPerson(q: String): PersonResult? {
        var words = q.trim().lowercase().replace(Regex("[^a-z0-9' ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        var movies = true
        var shows = true
        val kindWords = setOf("movies", "movie", "films", "film", "shows", "show", "series", "tv")
        val filler = setOf("with", "starring", "by", "and", "the", "in", "from", "all", "best")
        val hadKind = words.any { it in kindWords }
        if (hadKind) {
            val wantsMovies = words.any { it == "movies" || it == "movie" || it == "films" || it == "film" }
            val wantsShows = words.any { it == "shows" || it == "show" || it == "series" || it == "tv" }
            movies = wantsMovies || !wantsShows
            shows = wantsShows || !wantsMovies
        }
        words = words.filter { it !in kindWords }
        while (words.isNotEmpty() && words.first() in filler) words = words.drop(1)
        while (words.isNotEmpty() && words.last() in filler) words = words.dropLast(1)
        if (words.size < 2 && !hadKind) return null // one word is a title search ("Cars"), not a person
        val name = words.joinToString(" ")
        if (squash(name).length < 4) return null
        val found = Net.getJson(url("/search/person", "&query=" + Net.encode(name))).a("results").objects()
        val want = squash(name)
        val person = found.take(3).firstOrNull { squash(it.str("name")) == want }
            ?: found.firstOrNull()?.takeIf { hadKind && it.dbl("popularity") >= 2.0 }
            ?: return null
        val directs = person.str("known_for_department") == "Directing"
        val credits = Net.getJson(url("/person/${person.int("id")}/combined_credits"))
        val list = credits.a(if (directs) "crew" else "cast") ?: return null
        val skipGenres = setOf(10767, 10763) // talk, news
        val cameo = Regex("(?i)\\b(self|himself|herself|narrator|host)\\b")
        val titles = list.objects().mapNotNull { o ->
            if (directs && o.str("job") != "Director") return@mapNotNull null
            if (cameo.containsMatchIn(o.str("character"))) return@mapNotNull null
            parse(o, null)
        }.filter { t ->
            t.posterPath != null && (t.kind == MediaKind.MOVIE && movies || t.kind == MediaKind.TV && shows) &&
                t.genreIds.none { it in skipGenres } && t.votes >= 20
        }.distinctBy { it.key }.sortedByDescending { it.votes }.take(40).let { KidsFilter.titles(it) }
        if (titles.isEmpty()) return null
        val who = person.str("name")
        val kind = when { movies && shows -> "Movies & Shows"; movies -> "Movies"; else -> "Shows" }
        return PersonResult(person.int("id"), who, if (directs) "$kind Directed by $who" else "$kind with $who", titles)
    }

    /** Lowercase letters and digits only: "Yellow Jackets" and "Yellowjackets" both become "yellowjackets". */
    fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Popular titles available on one service (US, subscription): rated by enough people to mean something, and from
     * the last [RowMix.RECENT_YEARS] years (the service's archive lives in Browse).
     */
    fun onService(providerId: Int, type: MediaKind): List<Title> = list(
        "/discover/${type.path}",
        "&with_watch_providers=$providerId&watch_region=US&with_watch_monetization_types=flatrate&sort_by=popularity.desc" +
            "&include_adult=false&vote_count.gte=${if (type == MediaKind.MOVIE) 150 else 60}&${RowMix.dateKey(type)}.gte=${RowMix.yearsAgo(RowMix.RECENT_YEARS)}",
        type,
    )

    /** One page of a /discover query, with TMDB's totals (for paging and the result count). */
    class Results(val titles: List<Title>, val page: Int, val totalPages: Int, val totalResults: Int)

    /** /discover/[type] with [query] (each part starting with "&"), page [page]. Not cached: each page is read once. */
    fun discover(type: MediaKind, query: String, page: Int): Results {
        val json = Net.getJson(url("/discover/${type.path}", "$query&page=$page"))
        val titles = KidsFilter.titles(json.a("results").objects().mapNotNull { parse(it, type) }, trusted = type == MediaKind.MOVIE)
        // TMDB serves at most 500 pages of any query.
        return Results(titles, json.int("page", page), minOf(500, json.int("total_pages", 1)), json.int("total_results", titles.size))
    }

    /** Age rating ("PG-13", "TV-MA"; empty when none), IMDb id and TMDB's own score, for Ratings and the hero line. */
    class Certification(
        val rating: String, val imdbId: String?, val score: Double, val votes: Int, val released: String,
        /** "2 hr 10 min" (movies) or "9 Seasons" (shows); empty when unknown. */
        val runtime: String = "",
        val genres: List<String> = emptyList(),
    )

    /** One request: US certification first (theatrical, then digital, then any), else the first non-empty one. */
    fun certification(t: Title): Certification {
        val movie = t.kind == MediaKind.MOVIE
        val json = Net.getJson(url("/${t.kind.path}/${t.id}",
            "&append_to_response=" + if (movie) "release_dates,external_ids" else "content_ratings,external_ids"))
        val byCountry = certificationsByCountry(json, movie)
        val rating = byCountry["US"] ?: byCountry.values.firstOrNull().orEmpty()
        try { KidsFilter.remember(t, byCountry["US"].orEmpty()) } catch (e: Exception) {}
        val imdb = json.o("external_ids").str("imdb_id").takeIf { it.startsWith("tt") }
            ?: json.str("imdb_id").takeIf { it.startsWith("tt") }
        // Shows count as recent while they're still airing.
        val released = if (movie) json.str("release_date") else json.str("last_air_date").ifEmpty { json.str("first_air_date") }
        val runtime = if (movie) runtimeWords(json.int("runtime")).orEmpty()
        else json.int("number_of_seasons").takeIf { it > 0 }?.let { "$it Season" + if (it > 1) "s" else "" }.orEmpty()
        val genres = json.a("genres").objects().mapNotNull { it.str("name").ifEmpty { null } }
        return Certification(rating, imdb, json.dbl("vote_average"), json.int("vote_count"), released, runtime, genres)
    }

    /** Country -> certification from a details answer that appended release_dates (movies) or content_ratings (shows). */
    fun certificationsByCountry(json: JsonObject, movie: Boolean): Map<String, String> {
        val results = json.o(if (movie) "release_dates" else "content_ratings").a("results").objects()
        val byCountry = LinkedHashMap<String, String>()
        for (o in results) {
            val country = o.str("iso_3166_1")
            val cert = if (!movie) o.str("rating").trim() else o.a("release_dates").objects()
                .sortedBy { when (it.int("type")) { 3 -> 0; 4 -> 1; 2 -> 2; 5 -> 3; else -> 4 } }
                .map { it.str("certification").trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            if (cert.isNotEmpty() && country !in byCountry) byCountry[country] = cert
        }
        return byCountry
    }

    /** "2 hr 10 min", "45 min", "2 hr"; null for 0 (fire-home TitleInfo.runtimeWords). */
    fun runtimeWords(minutes: Int): String? {
        if (minutes <= 0) return null
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h hr"
            else -> "$h hr $m min"
        }
    }

    /** A list result as a [Title]; [forcedType] when the list has one kind (else the result's media_type). */
    fun parse(o: JsonObject, forcedType: MediaKind?): Title? {
        val type = forcedType ?: MediaKind.of(o.str("media_type"))?.takeIf { o.str("media_type") != "episode" } ?: return null
        val movie = type == MediaKind.MOVIE
        val date = o.str(if (movie) "release_date" else "first_air_date")
        return Title(
            id = o.int("id").takeIf { it > 0 } ?: return null,
            kind = type,
            name = o.str(if (movie) "title" else "name").ifEmpty { o.str(if (movie) "name" else "title") },
            overview = o.str("overview"),
            posterPath = o.strOrNull("poster_path"),
            backdropPath = o.strOrNull("backdrop_path"),
            year = date.take(4),
            rating = o.dbl("vote_average"),
            date = date.takeIf { it.length >= 10 }.orEmpty(),
            votes = o.int("vote_count"),
            genreIds = o.a("genre_ids").ints(),
            lang = o.str("original_language"),
            adult = o.bool("adult"),
            originCountry = o.a("origin_country").strings(),
        )
    }
}
