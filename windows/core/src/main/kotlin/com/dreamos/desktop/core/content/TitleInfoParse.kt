package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.Scores
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.model.TitleInfo
import com.dreamos.desktop.core.time.DreamTime
import com.dreamos.desktop.core.util.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * A title page's Information section (fire-home TitleInfo.kt): parsed from the details answer (Details appends
 * release_dates / content_ratings, credits and watch/providers to it, so no extra request). Dates stay raw
 * "yyyy-MM-dd"; the wording helpers below ([cells] and friends, the iPhone's `TitleInfo.cells()`) word them through
 * [DreamTime], so a date-only day never shifts. Pure: no network, no settings beyond DreamTime's zone.
 */
object TitleInfoParse {
    /** TMDB's score shows only with this many votes, as the meta line's audience badge ([Ratings.audience]). */
    const val MIN_VOTES = 150

    /** Crew jobs that mean "based on", and how they read: "Novel by Frank Herbert". */
    private val BASED_ON = listOf("Novel" to "Novel", "Book" to "Book", "Graphic Novel" to "Graphic Novel",
        "Comic Book" to "Comic Book", "Short Story" to "Short Story", "Play" to "Play", "Article" to "Article")

    /** Built from the details JSON of [t] ([genres]: its genre names, already read). */
    fun parse(json: JsonObject, t: Title, genres: List<String>): TitleInfo {
        val show = t.kind == MediaKind.TV
        fun s(o: JsonObject?, k: String) = o.str(k).trim().takeIf { it != "null" }.orEmpty()

        // US release days and rating (movies: release_dates by type; shows: content_ratings).
        var theatrical = ""
        var digital = ""
        var cert: String
        var note: String
        if (!show) {
            val us = json.o("release_dates").a("results").objects().firstOrNull { it.str("iso_3166_1") == "US" }
            val dates = us.a("release_dates").objects()
            fun first(vararg types: Int) = dates.filter { it.int("type") in types }.map { s(it, "release_date").take(10) }
                .filter { it.length == 10 }.minOrNull().orEmpty()
            theatrical = first(3).ifEmpty { first(2) }
            digital = first(4)
            val pick = dates.sortedBy { when (it.int("type")) { 3 -> 0; 4 -> 1; 2 -> 2; 5 -> 3; else -> 4 } }
                .firstOrNull { s(it, "certification").isNotEmpty() }
            cert = s(pick, "certification")
            note = descriptors(pick.a("descriptors"))
        } else {
            val us = json.o("content_ratings").a("results").objects().firstOrNull { it.str("iso_3166_1") == "US" }
            cert = s(us, "rating")
            note = descriptors(us.a("descriptors"))
        }

        val crew = json.o("credits").a("crew").objects()
        fun jobs(vararg js: String) = js.flatMap { j -> crew.filter { it.str("job") == j }.map { s(it, "name") } }
            .filter { it.isNotEmpty() }.distinct()
        val based = BASED_ON.firstNotNullOfOrNull { (job, word) ->
            jobs(job).takeIf { it.isNotEmpty() }?.let { "$word by " + it.take(2).joinToString(" and ") }
        }.orEmpty()

        val code = s(json, "original_language")
        val spokenList = json.a("spoken_languages").objects()
        val language = spokenList.firstOrNull { it.str("iso_639_1") == code }?.let { s(it, "english_name") }?.ifEmpty { null }
            ?: code.takeIf { it.isNotEmpty() }?.let {
                Locale.forLanguageTag(it).getDisplayLanguage(Locale.US).takeIf { n -> n.isNotEmpty() && n != it }
            }.orEmpty()

        val countries = if (show) json.a("origin_country").strings().filter { it.length == 2 }.map { countryName(it) }
        else json.a("production_countries").objects().map { countryName(s(it, "iso_3166_1"), s(it, "name")) }

        val original = s(json, if (show) "original_name" else "original_title")
        val shown = s(json, if (show) "name" else "title").ifEmpty { t.name }

        val runtimeShow = median(json.a("episode_run_time").ints())
        val lastEpRuntime = json.o("last_episode_to_air").int("runtime")
        val next = json.o("next_episode_to_air")

        val providers = json.o("watch/providers").o("results").o("US")
        val streaming = providerNames(providers.a("flatrate"))
        val free = (providerNames(providers.a("free")) + providerNames(providers.a("ads"))).distinct()
        val rentBuy = (providerNames(providers.a("rent")) + providerNames(providers.a("buy"))).distinct()

        return TitleInfo(
            premiere = s(json, if (show) "first_air_date" else "release_date"),
            theatrical = theatrical,
            digital = digital,
            cert = cert,
            certNote = note,
            runtime = if (show) runtimeShow.takeIf { it > 0 } ?: lastEpRuntime else json.int("runtime"),
            runtimeApprox = show && runtimeShow <= 0 && lastEpRuntime > 0,
            genres = genres,
            directors = if (show) emptyList() else jobs("Director"),
            writers = if (show) emptyList() else jobs("Screenplay", "Writer", "Teleplay", "Story").take(3),
            basedOn = if (show) "" else based,
            producers = if (show) emptyList() else jobs("Producer").take(3),
            creators = json.a("created_by").objects().map { s(it, "name") }.filter { it.isNotEmpty() },
            studios = json.a("production_companies").objects().map { s(it, "name") }.filter { it.isNotEmpty() }.distinct(),
            networks = json.a("networks").objects().map { s(it, "name") }.filter { it.isNotEmpty() }.distinct(),
            countries = countries.filter { it.isNotEmpty() }.distinct(),
            language = language,
            spoken = spokenList.map { s(it, "iso_639_1") }.filter { it.isNotEmpty() },
            originalTitle = original.takeIf { it.isNotEmpty() && !it.equals(shown, ignoreCase = true) }.orEmpty(),
            budget = json.long("budget"),
            revenue = json.long("revenue"),
            collection = s(json.o("belongs_to_collection"), "name"),
            collectionId = json.o("belongs_to_collection").int("id"),
            status = s(json, "status"),
            lastAired = s(json, "last_air_date"),
            nextSeason = next.int("season_number"),
            nextEpisode = next.int("episode_number"),
            nextDate = s(next, "air_date"),
            seasons = json.int("number_of_seasons"),
            episodes = json.int("number_of_episodes"),
            streaming = streaming,
            free = free,
            rentBuy = rentBuy,
            isShow = show,
            tagline = s(json, "tagline"),
            score = json.dbl("vote_average"),
            votes = json.int("vote_count"),
        )
    }

    /** The median of the positive values, rounded to the minute (0 when there are none). */
    fun median(v: List<Int>): Int {
        val s = v.filter { it > 0 }.sorted()
        if (s.isEmpty()) return 0
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else Math.round((s[m - 1] + s[m]) / 2.0).toInt()
    }

    /** A season's median episode length (TMDB season episodes' "runtime"), 0 when none is known. */
    fun episodeMinutes(eps: List<JsonObject>?): Int = median(eps.orEmpty().map { it.int("runtime") })

    private fun descriptors(a: JsonArray?): String =
        a.strings().map { it.trim() }.filter { it.isNotEmpty() && it != "null" }.take(3).joinToString(", ")

    private fun countryName(code: String, fallback: String = ""): String = when {
        code == "US" -> "United States"
        code == "GB" -> "United Kingdom"
        code.length == 2 -> Locale("", code).getDisplayCountry(Locale.US).takeIf { it.isNotEmpty() && it != code } ?: fallback
        else -> fallback
    }

    /** Provider names as people say them, each once; the services Dream OS knows lead, the rest in TMDB's order. */
    private fun providerNames(a: JsonArray?): List<String> = a.objects().sortedWith(compareBy(
        { o -> if (Tmdb.SERVICES.any { it.providerId == o.int("provider_id") }) 0 else 1 },
        { o -> o.int("display_priority", 999) },
    )).mapNotNull { o -> providerName(o.int("provider_id"), o.str("provider_name")) }.distinct()

    /**
     * A provider as people say it, or null for one that doesn't belong in a where-to-watch line: YouTube's movie
     * store ([Tmdb.hiddenProvider]; YouTube TV stays), add-on channels, ad tiers. Shared with Also Streaming On.
     */
    fun providerName(id: Int, raw: String): String? {
        Tmdb.SERVICES.firstOrNull { it.providerId == id }?.let { return it.name }
        var n = raw.trim()
        if (Tmdb.hiddenProvider(id, n) || n.contains("Channel", true) || n.contains("with Ads", true)) return null
        n = n.replace("Paramount Plus", "Paramount+").replace(Regex("\\s+(Premium|Essential|Standard|Basic)$"), "")
        return when (n) {
            "Amazon Video" -> "Amazon"
            "Amazon Prime Video" -> "Prime Video"
            "Apple TV Store" -> "Apple TV"
            "Google Play Movies" -> "Google Play"
            "tru TV" -> "truTV"
            "fuboTV" -> "Fubo"
            else -> n
        }
    }

    /** "$190 million", "$1.2 billion", "$850,000"; null when unknown. */
    fun money(v: Long): String? = when {
        v <= 0 -> null
        v >= 1_000_000_000 -> "$" + trim(v / 1e9) + " billion"
        v >= 1_000_000 -> "$" + trim(v / 1e6) + " million"
        else -> "$" + String.format(Locale.US, "%,d", v)
    }

    private fun trim(x: Double): String {
        val s = String.format(Locale.US, "%.1f", x)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }
}

// ---- the Information grid's wording (iPhone TitleInfo.cells, fire-home TitleInfo.cells) ----

/** One label / value pair of the Information grid, [span] columns wide (1 or 2). */
data class InfoCell(val label: String, val value: String, val span: Int = 1)

object InfoLabels {
    const val RATINGS = "Ratings"
    const val EPISODE_LENGTH = "Episode Length"
    const val WHERE_TO_WATCH = "Where to Watch"
    const val TBA = "Date to Be Announced"
}

/** The day it comes out in the US (movies: the earliest US theatrical or digital day), else TMDB's date. */
val TitleInfo.releaseDay: String
    get() = if (isShow) premiere else listOf(theatrical, digital).filter { it.length == 10 }.minOrNull() ?: premiere

/**
 * The grid's cells in reading order. [scores]: the tomato and audience score when known ([imdb] overrides the IMDb
 * figure); [nextAt]: the next episode's exact instant (epoch ms, AirTimes); [episodeMin]: the median episode length
 * of a loaded season (0: TMDB's figure). The "Streaming Availability From JustWatch" credit is the screen's to add.
 */
fun TitleInfo.cells(scores: Scores? = null, imdb: String? = null, nextAt: Long? = null, episodeMin: Int = 0): List<InfoCell> {
    val out = ArrayList<InfoCell>()
    fun add(label: String, value: String?, span: Int = 1) { if (!value.isNullOrBlank()) out += InfoCell(label, value, span) }
    fun names(list: List<String>, max: Int = 3) = list.distinct().take(max).joinToString(", ")
    val c = cert.ifEmpty { scores?.certification.orEmpty() }
    val rated = if (c.isNotEmpty() && certNote.isNotEmpty() && cert.isNotEmpty()) "$c  ·  $certNote" else c
    if (!isShow) {
        add(if (DreamTime.isAhead(releaseDay)) "Release Date" else "Released", releasedWords(), 2)
        add("Run Time", Tmdb.runtimeWords(runtime))
        add("Rated", rated)
        add("Genres", names(genres, 4))
        add(if (directors.size > 1) "Directors" else "Director", names(directors))
        add(if (writers.size > 1) "Writers" else "Writer", names(writers))
        add("Based On", basedOn)
        add("Producers", names(producers))
        add(if (studios.size > 1) "Studios" else "Studio", names(studios, 2))
        add(if (countries.size > 1) "Countries" else "Country", names(countries))
        add("Original Language", language)
        add("Original Title", originalTitle)
        add("Budget", TitleInfoParse.money(budget))
        add("Box Office", TitleInfoParse.money(revenue))
        // "Part of the Dune Collection"; TMDB's "The Matrix Collection" never reads "the The".
        add("Collection", collection.takeIf { it.isNotEmpty() }?.let { if (it.endsWith("Collection")) "Part of the ${it.removePrefix("The ")}" else "Part of $it" })
        add("Status", status.takeIf { it != "Released" })
    } else {
        add("First Aired", DreamTime.longDate(premiere)?.let { if (DreamTime.isAhead(premiere)) "Coming $it" else it })
        add("Last Aired", DreamTime.longDate(lastAired)?.takeIf { lastAired != premiere })
        add("Status", status)
        add("Seasons", listOfNotNull(
            seasons.takeIf { it > 0 }?.let { "$it Season" + if (it > 1) "s" else "" },
            episodes.takeIf { it > 0 }?.let { "$it Episode" + if (it > 1) "s" else "" }).joinToString(", "))
        add("Next Episode", nextWords(nextAt), 2)
        add(InfoLabels.EPISODE_LENGTH, episodeLengthWords(episodeMin))
        add("Rated", rated)
        add("Genres", names(genres, 4))
        add("Created By", names(creators))
        add(if (networks.size > 1) "Networks" else "Network", names(networks))
        add(if (studios.size > 1) "Studios" else "Studio", names(studios, 2))
        add(if (countries.size > 1) "Countries" else "Country", names(countries))
        add("Original Language", language)
        add("Original Title", originalTitle)
    }
    add(InfoLabels.WHERE_TO_WATCH, watchWords(), 2)
    add(InfoLabels.RATINGS, ratingsWords(scores, imdb), 2)
    return out
}

/** "47 min" from a loaded season's median ([episodeMin]), else TMDB's figure ("About 56 min" when one episode told it). */
fun TitleInfo.episodeLengthWords(episodeMin: Int = 0): String? =
    if (episodeMin > 0) Tmdb.runtimeWords(episodeMin) else Tmdb.runtimeWords(runtime)?.let { if (runtimeApprox) "About $it" else it }

/** "In Theaters March 1, 2024  ·  Digital April 16, 2024", or TMDB's one date. */
fun TitleInfo.releasedWords(): String? {
    val parts = ArrayList<String>()
    DreamTime.longDate(theatrical)?.let { parts += "In Theaters $it" }
    DreamTime.longDate(digital)?.let { parts += "Digital $it" }
    return if (parts.isNotEmpty()) parts.joinToString("  ·  ") else DreamTime.longDate(premiere)
}

/** "Season 2, Episode 7  ·  Friday, October 30 at 9:00 PM" (the exact time when known), else its day. */
fun TitleInfo.nextWords(at: Long? = null): String? {
    if (nextSeason <= 0 || nextEpisode <= 0) return null
    val whenWords = if (at != null && at > 0) DreamTime.format(at, "EEEE, MMMM d") + " at " + DreamTime.formatTime(at)
    else DreamTime.longDate(nextDate) ?: InfoLabels.TBA
    return "Season $nextSeason, Episode $nextEpisode  ·  $whenWords"
}

/** "Streaming on Netflix, HBO Max" and "Rent or Buy on Amazon, Apple TV" (two lines at most). */
fun TitleInfo.watchWords(): String {
    val first = listOfNotNull(
        streaming.take(4).takeIf { it.isNotEmpty() }?.let { "Streaming on " + it.joinToString(", ") },
        free.filter { it !in streaming }.take(2).takeIf { it.isNotEmpty() }?.let { "Free on " + it.joinToString(", ") },
    ).joinToString("  ·  ")
    val second = rentBuy.take(3).takeIf { it.isNotEmpty() }?.let { "Rent or Buy on " + it.joinToString(", ") }
    return listOf(first, second.orEmpty()).filter { it.isNotEmpty() }.joinToString("\n")
}

/** "Rotten Tomatoes 94%  ·  IMDb 8.5  ·  TMDB 8.1" (what's known; TMDB's score only after 150 votes). */
fun TitleInfo.ratingsWords(s: Scores?, imdb: String? = null): String {
    val parts = ArrayList<String>()
    s?.tomatometer?.let { parts += "Rotten Tomatoes $it%" }
    val im = imdb ?: s?.audienceBadge?.takeIf { it.startsWith("IMDb ") }?.removePrefix("IMDb ")
    if (!im.isNullOrBlank() && im != "N/A") parts += "IMDb $im"
    if (score > 0 && votes >= TitleInfoParse.MIN_VOTES) parts += "TMDB " + String.format(Locale.US, "%.1f", score)
    return parts.joinToString("  ·  ")
}
