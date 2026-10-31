package com.dreamos.desktop.details

import com.dreamos.desktop.settings.kit.TitleRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.util.Locale
import java.util.Properties

/**
 * DEV STAND-IN for core's catalog: reads TMDB (and OMDb for the tomato) with the keys in local.properties, so the
 * title and person pages show real titles before win-content's catalog merges. No availability, history or
 * language pills: those come from core. Delete once `TitleSource` is backed by core (requests.md).
 */
class DevTitleSource(private val tmdbKey: String?, private val omdbKey: String?) : TitleSource {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
    private val myList = mutableSetOf<TitleRef>()

    companion object {
        const val IMG = "https://image.tmdb.org/t/p/"

        /** Keys from the nearest local.properties (the worktree's; never committed). */
        fun fromLocalProperties(): DevTitleSource {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null && !File(dir, "local.properties").exists()) dir = dir.parentFile
            val p = Properties()
            dir?.let { d -> File(d, "local.properties").inputStream().use { p.load(it) } }
            return DevTitleSource(p.getProperty("tmdb.key")?.takeIf { it.isNotBlank() }, p.getProperty("omdb.key")?.takeIf { it.isNotBlank() })
        }
    }

    private fun get(url: String): JsonObject {
        val res = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())
        check(res.statusCode() in 200..299) { "status ${res.statusCode()}" }
        return Json.parseToJsonElement(res.body()) as JsonObject
    }

    private fun tmdb(path: String, extra: String = ""): JsonObject {
        val key = tmdbKey ?: error("no TMDB key")
        return get("https://api.themoviedb.org/3$path?api_key=$key&language=en-US$extra")
    }

    override suspend fun details(ref: TitleRef): TitleDetails = withContext(Dispatchers.IO) {
        val kind = if (ref.isShow) "tv" else "movie"
        val appends = if (ref.isShow) "credits,recommendations,content_ratings,images,videos,watch/providers,external_ids"
        else "credits,recommendations,release_dates,images,videos,watch/providers"
        val j = tmdb("/$kind/${ref.tmdbId}", "&append_to_response=$appends&include_image_language=en,null")
        val name = j.str(if (ref.isShow) "name" else "title") ?: ref.name
        val genres = j.arr("genres").mapNotNull { it.obj()?.str("name") }
        val release = if (ref.isShow) j.date("first_air_date") else usRelease(j) ?: j.date("release_date")
        val cert = if (ref.isShow) j.obj("content_ratings")?.arr("results")?.firstOrNull { it.obj()?.str("iso_3166_1") == "US" }?.obj()?.str("rating")
        else usCert(j)
        val logo = j.obj("images")?.arr("logos")?.mapNotNull { it.obj() }
            ?.filter { it.str("file_path")?.endsWith(".svg") == false }
            ?.let { l -> l.firstOrNull { it.str("iso_639_1") == "en" } ?: l.firstOrNull() }?.str("file_path")
        val trailer = j.obj("videos")?.arr("results")?.mapNotNull { it.obj() }
            ?.filter { it.str("site") == "YouTube" && !it.str("key").isNullOrBlank() }
            ?.sortedBy { if (it.str("type") == "Trailer") 0 else 1 }?.firstOrNull()?.str("key")
        val cast = j.obj("credits")?.arr("cast")?.mapNotNull { it.obj() }?.take(15)?.map {
            CastMember(it.int("id") ?: 0, it.str("name") ?: "", it.str("character") ?: "", it.str("profile_path")?.let { p -> "${IMG}w185$p" })
        } ?: emptyList()
        val similar = j.obj("recommendations")?.arr("results")?.mapNotNull { it.obj() }?.take(24)?.map { card(it, ref.isShow) } ?: emptyList()
        val imdbId = j.str("imdb_id") ?: j.obj("external_ids")?.str("imdb_id")
        val omdb = imdbId?.let { omdb(it) }
        val tomato = omdb?.arr("Ratings")?.firstOrNull { it.obj()?.str("Source") == "Rotten Tomatoes" }?.obj()?.str("Value")?.removeSuffix("%")?.toIntOrNull()
        val imdb = omdb?.str("imdbRating")?.toDoubleOrNull()
        val seasons = if (!ref.isShow) emptyList() else j.arr("seasons").mapNotNull { it.obj() }
            .filter { (it.int("season_number") ?: 0) > 0 && ((it.int("episode_count") ?: 0) > 0 || it.date("air_date")?.isAfter(LocalDate.now()) != false) }
            .map { s ->
                val n = s.int("season_number")!!
                val air = s.date("air_date")
                val named = s.str("name")?.takeIf { it.isNotBlank() && !it.startsWith("Season") && it.length <= 22 }
                SeasonInfo(n, named ?: "Season $n", s.int("episode_count") ?: 0,
                    comingWords = if (air == null) "Upcoming" else air.takeIf { it.isAfter(LocalDate.now()) }?.let { "Coming " + it.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.US)) },
                    comingLong = if (air == null) TitleWords.TBA else air.takeIf { it.isAfter(LocalDate.now()) }?.let { "Coming " + TitleWords.long(it) })
            }
        val providers = j.obj("watch/providers")?.obj("results")?.obj("US")
        fun names(key: String) = providers?.arr(key)?.mapNotNull { it.obj()?.str("provider_name") }
            ?.filter { !it.contains("YouTube") || it == "YouTube TV" }?.filter { !it.contains("Channel") && !it.contains("with Ads") }
            ?.map { it.replace(Regex(" (Premium|Essential|Standard|Basic)$"), "").replace("Amazon Prime Video", "Prime Video").replace("Amazon Video", "Amazon").replace("Apple TV Store", "Apple TV").replace("Paramount Plus", "Paramount+") }
            ?.distinct() ?: emptyList()
        val streaming = names("flatrate").take(8)
        val where = WhereToWatch(streaming, (names("free") + names("ads")).distinct().filter { it !in streaming }.take(8), (names("rent") + names("buy")).distinct().take(8))
        TitleDetails(
            ref = ref.copy(name = name), name = name,
            logoUrl = logo?.let { "${IMG}w500$it" },
            backdropUrl = j.str("backdrop_path")?.let { "${IMG}w1280$it" },
            posterUrl = j.str("poster_path")?.let { "${IMG}w342$it" },
            releaseDate = release, year = release?.year,
            certification = cert?.takeIf { it.isNotBlank() },
            runtimeMin = if (ref.isShow) null else j.int("runtime")?.takeIf { it > 0 },
            seasonCount = if (ref.isShow) j.int("number_of_seasons") else null,
            genres = genres.take(2),
            scores = Scores(tomato, imdb, j.dbl("vote_average"), j.int("vote_count") ?: 0),
            tagline = j.str("tagline")?.takeIf { it.isNotBlank() },
            overview = j.str("overview") ?: "",
            trailerKey = trailer,
            seasons = seasons,
            defaultSeason = seasons.firstOrNull { it.comingWords == null }?.number,
            cast = cast, similar = similar,
            info = info(j, ref.isShow, genres, cert, where, tomato, imdb),
            whereToWatch = where,
            onMyList = ref in myList,
        )
    }

    private fun card(o: JsonObject, show: Boolean): TitleCard {
        val isShow = o.str("media_type")?.let { it == "tv" } ?: show
        val date = o.date(if (isShow) "first_air_date" else "release_date")
        return TitleCard(TitleRef(o.int("id") ?: 0, isShow, o.str(if (isShow) "name" else "title") ?: ""), o.str(if (isShow) "name" else "title") ?: "",
            o.str("poster_path")?.let { "${IMG}w342$it" }, date?.year, overview = o.str("overview") ?: "",
            audience = o.dbl("vote_average")?.takeIf { it > 0 && (o.int("vote_count") ?: 0) >= 150 }?.let { "TMDB " + String.format(Locale.US, "%.1f", it) })
    }

    private fun omdb(imdbId: String): JsonObject? = omdbKey?.let { k ->
        runCatching { get("https://www.omdbapi.com/?i=$imdbId&apikey=${URLEncoder.encode(k, "UTF-8")}") }.getOrNull()
    }

    private fun usDates(j: JsonObject) = j.obj("release_dates")?.arr("results")?.firstOrNull { it.obj()?.str("iso_3166_1") == "US" }?.obj()?.arr("release_dates")?.mapNotNull { it.obj() } ?: emptyList()

    private fun usRelease(j: JsonObject): LocalDate? {
        val d = usDates(j)
        return (d.filter { it.int("type") == 3 }.ifEmpty { d.filter { it.int("type") == 2 } } + d.filter { it.int("type") == 4 })
            .mapNotNull { it.str("release_date")?.take(10)?.let { s -> runCatching { LocalDate.parse(s) }.getOrNull() } }.minOrNull()
    }

    private fun usCert(j: JsonObject): String? {
        val d = usDates(j)
        for (t in listOf(3, 4, 2, 5)) d.firstOrNull { it.int("type") == t && !it.str("certification").isNullOrBlank() }?.let { return it.str("certification") }
        return null
    }

    private fun info(j: JsonObject, show: Boolean, genres: List<String>, cert: String?, where: WhereToWatch, tomato: Int?, imdb: Double?): List<InfoCell> {
        val out = mutableListOf<InfoCell>()
        val long = java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)
        val crew = j.obj("credits")?.arr("crew")?.mapNotNull { it.obj() } ?: emptyList()
        fun people(jobs: Set<String>, n: Int) = crew.filter { it.str("job") in jobs }.mapNotNull { it.str("name") }.distinct().take(n)
        fun plural(one: String, many: String, l: List<String>) { if (l.isNotEmpty()) out += InfoCell(if (l.size == 1) one else many, l.joinToString(", ")) }
        val countries = j.arr("production_countries").mapNotNull { it.obj()?.str("name") }.map { if (it == "United States of America") "United States" else it }.take(3)
        val studios = j.arr("production_companies").mapNotNull { it.obj()?.str("name") }.take(2)
        val lang = j.str("original_language")?.let { Locale(it).getDisplayLanguage(Locale.US) }
        if (!show) {
            j.date("release_date")?.let { out += InfoCell("Released", it.format(long), wide = true) }
            j.int("runtime")?.takeIf { it > 0 }?.let { out += InfoCell("Run Time", TitleWords.runtime(it)) }
            cert?.let { out += InfoCell("Rated", it) }
            if (genres.isNotEmpty()) out += InfoCell("Genres", genres.take(4).joinToString(", "))
            plural("Director", "Directors", people(setOf("Director"), 3))
            plural("Writer", "Writers", people(setOf("Screenplay", "Writer", "Teleplay", "Story"), 3))
            people(setOf("Producer"), 3).takeIf { it.isNotEmpty() }?.let { out += InfoCell("Producers", it.joinToString(", ")) }
            plural("Studio", "Studios", studios)
            plural("Country", "Countries", countries)
            lang?.let { out += InfoCell("Original Language", it) }
            j.long("budget")?.takeIf { it > 0 }?.let { out += InfoCell("Budget", TitleWords.money(it)) }
            j.long("revenue")?.takeIf { it > 0 }?.let { out += InfoCell("Box Office", TitleWords.money(it)) }
            j.obj("belongs_to_collection")?.str("name")?.let { out += InfoCell("Collection", if (it.endsWith("Collection")) "Part of the $it" else "Part of $it") }
        } else {
            j.date("first_air_date")?.let { out += InfoCell("First Aired", it.format(long)) }
            j.date("last_air_date")?.takeIf { it != j.date("first_air_date") }?.let { out += InfoCell("Last Aired", it.format(long)) }
            j.str("status")?.let { out += InfoCell("Status", it) }
            val s = j.int("number_of_seasons"); val e = j.int("number_of_episodes")
            if (s != null) out += InfoCell("Seasons", "$s ${if (s == 1) "Season" else "Seasons"}" + (e?.let { ", $it Episodes" } ?: ""))
            cert?.let { out += InfoCell("Rated", it) }
            if (genres.isNotEmpty()) out += InfoCell("Genres", genres.take(4).joinToString(", "))
            j.arr("created_by").mapNotNull { it.obj()?.str("name") }.take(3).takeIf { it.isNotEmpty() }?.let { out += InfoCell("Created By", it.joinToString(", ")) }
            plural("Network", "Networks", j.arr("networks").mapNotNull { it.obj()?.str("name") }.take(3))
            plural("Studio", "Studios", studios)
            plural("Country", "Countries", countries)
            lang?.let { out += InfoCell("Original Language", it) }
        }
        val ratings = listOfNotNull(tomato?.let { "Rotten Tomatoes $it%" }, imdb?.let { "IMDb $it" },
            j.dbl("vote_average")?.takeIf { (j.int("vote_count") ?: 0) >= 150 }?.let { "TMDB " + String.format(Locale.US, "%.1f", it) })
        if (ratings.isNotEmpty()) out += InfoCell("Ratings", ratings.joinToString("  ·  "), wide = true)
        return out
    }

    override suspend fun season(ref: TitleRef, season: Int): List<EpisodeInfo> = withContext(Dispatchers.IO) {
        val j = tmdb("/tv/${ref.tmdbId}/season/$season")
        val today = LocalDate.now()
        j.arr("episodes").mapNotNull { it.obj() }.map { e ->
            val air = e.date("air_date")
            val upcoming = air == null || air.isAfter(today)
            EpisodeInfo(
                season = season, number = e.int("episode_number") ?: 0, name = e.str("name") ?: "",
                runtimeMin = e.int("runtime"), airDate = air, overview = e.str("overview") ?: "",
                stillUrl = e.str("still_path")?.let { "${IMG}w500$it" },
                upcoming = upcoming,
                comingWords = if (upcoming) air?.let { "Coming " + it.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.US)) } ?: "Date to Be Announced" else null,
            )
        }
    }

    override suspend fun person(id: Int, excluding: TitleRef?): PersonDetails = withContext(Dispatchers.IO) {
        val j = tmdb("/person/$id", "&append_to_response=combined_credits")
        val skip = Regex("(?i)\\b(self|himself|herself|narrator|host)\\b")
        val credits = j.obj("combined_credits")?.arr("cast")?.mapNotNull { it.obj() }
            ?.filter { it.str("poster_path") != null && !skip.containsMatchIn(it.str("character") ?: "") }
            ?.filter { o -> o.arr("genre_ids").none { (it as? JsonPrimitive)?.intOrNull in setOf(10767, 10763) } }
            ?.distinctBy { it.int("id") }?.sortedByDescending { it.int("vote_count") ?: 0 } ?: emptyList()
        val cards = credits.map { card(it, it.str("media_type") == "tv") }
        PersonDetails(
            id, j.str("name") ?: "", j.str("profile_path")?.let { "${IMG}w185$it" }, j.date("birthday"), j.date("deathday"),
            j.str("place_of_birth"), j.str("biography") ?: "", cards.filter { it.ref.tmdbId != excluding?.tmdbId || it.ref.isShow != excluding.isShow }.take(14), cards,
        )
    }

    override suspend fun sources(ref: TitleRef, season: Int?, episode: Int?, loose: Boolean): List<SourceOption> = emptyList()

    override suspend fun setMyList(ref: TitleRef, on: Boolean): Boolean {
        if (on) myList += ref else myList -= ref
        return on
    }
}

private fun JsonElement.obj(): JsonObject? = this as? JsonObject
private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
private fun JsonObject.arr(k: String): List<JsonElement> = (this[k] as? JsonArray) ?: emptyList()
private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.content?.toLongOrNull()
private fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.date(k: String): LocalDate? = str(k)?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
