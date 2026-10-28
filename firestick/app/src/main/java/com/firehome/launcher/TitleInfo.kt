package com.firehome.launcher

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.Locale

/**
 * Everything a title page's Information section shows (Apple TV style): release dates, run time, age rating,
 * the people behind it, studios, money, where to watch. Parsed once from the details call ([Tmdb.fetchDetails]
 * appends release_dates / content_ratings to it, so no extra request) and cached with the [Details].
 * Every date is worded through [DreamTime] (the user's zone and clock format).
 */
class TitleInfo(
    val show: Boolean,
    val name: String,
    /** Movies: TMDB's release date; shows: the first air date ("yyyy-MM-dd", "" when unknown). */
    val premiere: String,
    /** Movies, US: first theatrical (or limited) release, first digital release ("" when unknown). */
    val theatrical: String,
    val digital: String,
    /** US age rating ("PG-13", "TV-MA"; "" when unknown) and TMDB's content descriptors for it. */
    val cert: String,
    val certNote: String,
    /** Minutes: a movie's length, a show's episode length (0 = unknown); [runtimeApprox] when only one episode told it. */
    val runtime: Int,
    val runtimeApprox: Boolean,
    val genres: List<String>,
    val directors: List<String>,
    val writers: List<String>,
    /** "Novel by Frank Herbert" ("" when not based on anything TMDB names). */
    val basedOn: String,
    val producers: List<String>,
    val creators: List<String>,
    val studios: List<String>,
    val networks: List<String>,
    val countries: List<String>,
    val language: String,
    /** The original title when it differs from the shown one. */
    val originalTitle: String,
    val budget: Long,
    val revenue: Long,
    val collection: String,
    val status: String,
    val lastAired: String,
    val nextSeason: Int,
    val nextEpisode: Int,
    val nextDate: String,
    val seasons: Int,
    val episodes: Int,
    val streaming: List<String>,
    val free: List<String>,
    val rentBuy: List<String>,
    val score: Double,
    val votes: Int,
    /** TMDB's one-line tagline ("Long live the fighters."), "" when none. */
    val tagline: String = "",
) {
    /** One label / value pair of the grid; [span] columns wide. */
    class Cell(val label: String, val value: String, val span: Int = 1)

    /** The day it comes out in the US (movies: the earliest US theatrical or digital day), else TMDB's date. */
    val releaseDay: String get() =
        if (show) premiere else listOf(theatrical, digital).filter { it.length == 10 }.minOrNull() ?: premiere

    /** The grid's cells in reading order; [rating] adds Rotten Tomatoes / IMDb when Ratings has them, [nextAt] the next episode's exact time. */
    /** [episodeMin]: the median episode length of a season Dream OS loaded (0 = none yet: TMDB's figure stands in). */
    fun cells(ctx: Context, rating: Ratings.Rating?, nextAt: Long = 0L, episodeMin: Int = 0): List<Cell> {
        val out = ArrayList<Cell>()
        fun add(label: String, value: String?, span: Int = 1) { if (!value.isNullOrBlank()) out += Cell(label, value, span) }
        fun names(list: List<String>, max: Int = 3) = list.distinct().take(max).joinToString(", ")
        val rated = cert.ifEmpty { rating?.cert.orEmpty() }.let { c -> if (c.isNotEmpty() && certNote.isNotEmpty()) "$c  ·  $certNote" else c }
        if (!show) {
            add(if (isAhead(ctx, releaseDay)) "Release Date" else "Released", releasedWords(ctx), 2)
            add("Run Time", runtimeWords(runtime))
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
            add("Budget", money(budget))
            add("Box Office", money(revenue))
            add("Collection", collection.takeIf { it.isNotEmpty() }?.let { if (it.endsWith("Collection")) "Part of the $it" else "Part of $it" })
            add("Status", status.takeIf { it != "Released" })
        } else {
            add("First Aired", longDate(ctx, premiere)?.let { if (isAhead(ctx, premiere)) "Coming $it" else it })
            add("Last Aired", longDate(ctx, lastAired)?.takeIf { lastAired != premiere })
            add("Status", status)
            add("Seasons", listOfNotNull(
                seasons.takeIf { it > 0 }?.let { "$it Season" + if (it > 1) "s" else "" },
                episodes.takeIf { it > 0 }?.let { "$it Episode" + if (it > 1) "s" else "" }).joinToString(", "))
            add("Next Episode", nextWords(ctx, nextAt), 2)
            add(EPISODE_LENGTH, episodeLengthWords(episodeMin))
            add("Rated", rated)
            add("Genres", names(genres, 4))
            add("Created By", names(creators))
            add(if (networks.size > 1) "Networks" else "Network", names(networks))
            add(if (studios.size > 1) "Studios" else "Studio", names(studios, 2))
            add(if (countries.size > 1) "Countries" else "Country", names(countries))
            add("Original Language", language)
            add("Original Title", originalTitle)
        }
        add("Where to Watch", watchWords(), 2)
        add(RATINGS, ratingsWords(rating), 2)
        return out
    }

    /** "47 min" from a season's real median ([episodeMin]), else TMDB's median, else "About 56 min" (one episode's length). */
    fun episodeLengthWords(episodeMin: Int = 0): String? =
        if (episodeMin > 0) runtimeWords(episodeMin) else runtimeWords(runtime)?.let { if (runtimeApprox) "About $it" else it }

    /** "In Theaters March 1, 2024  ·  Digital April 16, 2024", or TMDB's one date. */
    fun releasedWords(ctx: Context): String? {
        val parts = ArrayList<String>()
        longDate(ctx, theatrical)?.let { parts += "In Theaters $it" }
        longDate(ctx, digital)?.let { parts += "Digital $it" }
        return if (parts.isNotEmpty()) parts.joinToString("  ·  ") else longDate(ctx, premiere)
    }

    /** "Season 2, Episode 7  ·  Friday, October 30 at 9:00 PM" (the exact time when known), else its day. */
    fun nextWords(ctx: Context, at: Long): String? {
        if (nextSeason <= 0 || nextEpisode <= 0) return null
        val whenWords = if (at > 0) DreamTime.fmt(ctx, "EEEE, MMMM d").format(Date(at)) + " at " + DreamTime.formatTime(ctx, at)
        else longDate(ctx, nextDate) ?: Upcoming.TBA
        return "Season $nextSeason, Episode $nextEpisode  ·  $whenWords"
    }

    /** "Streaming on Netflix, Max" and "Rent or Buy on Amazon, Apple TV" (two lines at most). */
    fun watchWords(): String {
        val first = listOfNotNull(
            streaming.take(4).takeIf { it.isNotEmpty() }?.let { "Streaming on " + it.joinToString(", ") },
            free.filter { it !in streaming }.take(2).takeIf { it.isNotEmpty() }?.let { "Free on " + it.joinToString(", ") },
        ).joinToString("  ·  ")
        val second = rentBuy.take(3).takeIf { it.isNotEmpty() }?.let { "Rent or Buy on " + it.joinToString(", ") }
        return listOf(first, second.orEmpty()).filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** "Rotten Tomatoes 94%  ·  IMDb 8.5  ·  TMDB 8.1" (what's known). */
    fun ratingsWords(r: Ratings.Rating?): String {
        val parts = ArrayList<String>()
        r?.tomatometer?.let { parts += "Rotten Tomatoes $it%" }
        r?.imdb?.takeIf { it.isNotBlank() && it != "N/A" }?.let { parts += "IMDb $it" }
        val s = if (score > 0 && votes >= MIN_VOTES) score else r?.takeIf { it.votes >= MIN_VOTES }?.score ?: 0.0
        if (s > 0) parts += "TMDB " + String.format(Locale.US, "%.1f", s)
        return parts.joinToString("  ·  ")
    }

    companion object {
        const val RATINGS = "Ratings"
        const val EPISODE_LENGTH = "Episode Length"

        /** The median of the positive values, rounded to the minute (0 when there are none). */
        fun median(v: List<Int>): Int {
            val s = v.filter { it > 0 }.sorted()
            if (s.isEmpty()) return 0
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else Math.round((s[m - 1] + s[m]) / 2.0).toInt()
        }

        /** A season's median episode length (TMDB season episodes, their "runtime"), 0 when none is known. */
        fun episodeMinutes(eps: List<JSONObject>?): Int = median(eps.orEmpty().map { it.optInt("runtime", 0) })
        private const val MIN_VOTES = 20

        /** "March 1, 2024" in the user's zone (a date-only value: parsed and worded in the same zone, so the day never shifts). */
        fun longDate(ctx: Context, ymd: String?): String? {
            if (ymd == null || ymd.length < 10) return null
            return try {
                DreamTime.fmt(ctx, "yyyy-MM-dd").parse(ymd.take(10))?.let { DreamTime.fmt(ctx, "MMMM d, yyyy").format(it) }
            } catch (e: Exception) { null }
        }

        fun isAhead(ctx: Context, ymd: String?) = ymd != null && ymd.length >= 10 && ymd.take(10) > DreamTime.todayYmd(ctx)

        /** "Coming March 1, 2027" for a day still ahead, else null (the meta line then shows the year). */
        fun comingWords(ctx: Context, ymd: String?): String? =
            if (isAhead(ctx, ymd)) longDate(ctx, ymd)?.let { "Coming $it" } else null

        /** "2 hr 46 min", "2 hr", "56 min". */
        fun runtimeWords(min: Int): String? = when {
            min <= 0 -> null
            min < 60 -> "$min min"
            min % 60 == 0 -> "${min / 60} hr"
            else -> "${min / 60} hr ${min % 60} min"
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

        /** Built from the details JSON (the same request as [Details]); null when it isn't there. */
        fun parse(json: JSONObject, t: Title, genres: List<String>): TitleInfo {
            val show = t.type == "tv"
            fun str(o: JSONObject?, k: String) = o?.optString(k)?.takeIf { it.isNotBlank() && it != "null" }.orEmpty()
            fun objs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

            // US release days and rating (movies: release_dates by type; shows: content_ratings).
            var theatrical = ""
            var digital = ""
            var cert = ""
            var note = ""
            if (!show) {
                val us = objs(json.optJSONObject("release_dates")?.optJSONArray("results")).firstOrNull { it.optString("iso_3166_1") == "US" }
                val dates = objs(us?.optJSONArray("release_dates"))
                fun first(vararg types: Int) = dates.filter { it.optInt("type") in types }.map { str(it, "release_date").take(10) }
                    .filter { it.length == 10 }.minOrNull().orEmpty()
                theatrical = first(3).ifEmpty { first(2) }
                digital = first(4)
                val pick = dates.sortedBy { when (it.optInt("type")) { 3 -> 0; 4 -> 1; 2 -> 2; 5 -> 3; else -> 4 } }
                    .firstOrNull { str(it, "certification").isNotEmpty() }
                cert = str(pick, "certification")
                note = descriptors(pick?.optJSONArray("descriptors"))
            } else {
                val us = objs(json.optJSONObject("content_ratings")?.optJSONArray("results")).firstOrNull { it.optString("iso_3166_1") == "US" }
                cert = str(us, "rating")
                note = descriptors(us?.optJSONArray("descriptors"))
            }

            val crew = objs(json.optJSONObject("credits")?.optJSONArray("crew"))
            fun jobs(vararg js: String) = js.flatMap { j -> crew.filter { it.optString("job") == j }.map { str(it, "name") } }.filter { it.isNotEmpty() }.distinct()
            val based = BASED_ON.firstNotNullOfOrNull { (job, word) -> jobs(job).takeIf { it.isNotEmpty() }?.let { "$word by " + it.take(2).joinToString(" and ") } }.orEmpty()

            val code = str(json, "original_language")
            val language = objs(json.optJSONArray("spoken_languages")).firstOrNull { it.optString("iso_639_1") == code }
                ?.let { str(it, "english_name") }?.ifEmpty { null }
                ?: code.takeIf { it.isNotEmpty() }?.let { Locale(it).getDisplayLanguage(Locale.US).takeIf { n -> n.isNotEmpty() && n != it } }.orEmpty()

            val countries = if (show) {
                val a = json.optJSONArray("origin_country")
                (0 until (a?.length() ?: 0)).mapNotNull { a?.optString(it)?.takeIf { c -> c.length == 2 } }.map { countryName(it) }
            } else objs(json.optJSONArray("production_countries")).map { countryName(str(it, "iso_3166_1"), str(it, "name")) }

            val original = str(json, if (show) "original_name" else "original_title")
            val shown = str(json, if (show) "name" else "title").ifEmpty { t.name }

            val runtimeShow = json.optJSONArray("episode_run_time")?.let { a -> median((0 until a.length()).map { a.optInt(it) }) } ?: 0
            val lastEpRuntime = json.optJSONObject("last_episode_to_air")?.optInt("runtime", 0) ?: 0
            val next = json.optJSONObject("next_episode_to_air")

            val providers = json.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject("US")
            val streaming = providerNames(providers?.optJSONArray("flatrate"))
            val free = providerNames(providers?.optJSONArray("free")) + providerNames(providers?.optJSONArray("ads"))
            val rentBuy = (providerNames(providers?.optJSONArray("rent")) + providerNames(providers?.optJSONArray("buy"))).distinct()

            return TitleInfo(
                show = show,
                name = shown,
                premiere = str(json, if (show) "first_air_date" else "release_date"),
                theatrical = theatrical,
                digital = digital,
                cert = cert,
                certNote = note,
                runtime = if (show) runtimeShow.takeIf { it > 0 } ?: lastEpRuntime else json.optInt("runtime"),
                runtimeApprox = show && runtimeShow <= 0 && lastEpRuntime > 0,
                genres = genres,
                directors = if (show) emptyList() else jobs("Director"),
                writers = if (show) emptyList() else jobs("Screenplay", "Writer", "Teleplay", "Story").take(3),
                basedOn = if (show) "" else based,
                producers = if (show) emptyList() else jobs("Producer").take(3),
                creators = objs(json.optJSONArray("created_by")).map { str(it, "name") }.filter { it.isNotEmpty() },
                studios = objs(json.optJSONArray("production_companies")).map { str(it, "name") }.filter { it.isNotEmpty() }.distinct(),
                networks = objs(json.optJSONArray("networks")).map { str(it, "name") }.filter { it.isNotEmpty() }.distinct(),
                countries = countries.filter { it.isNotEmpty() }.distinct(),
                language = language,
                originalTitle = original.takeIf { it.isNotEmpty() && !it.equals(shown, ignoreCase = true) }.orEmpty(),
                budget = json.optLong("budget", 0L),
                revenue = json.optLong("revenue", 0L),
                collection = str(json.optJSONObject("belongs_to_collection"), "name"),
                status = str(json, "status"),
                lastAired = str(json, "last_air_date"),
                nextSeason = next?.optInt("season_number", 0) ?: 0,
                nextEpisode = next?.optInt("episode_number", 0) ?: 0,
                nextDate = str(next, "air_date"),
                seasons = json.optInt("number_of_seasons", 0),
                episodes = json.optInt("number_of_episodes", 0),
                streaming = streaming,
                free = free.distinct(),
                rentBuy = rentBuy,
                score = json.optDouble("vote_average", 0.0),
                votes = json.optInt("vote_count", 0),
                tagline = str(json, "tagline"),
            )
        }

        /** Crew jobs that mean "based on", and how they read: "Novel by Frank Herbert". */
        private val BASED_ON = listOf("Novel" to "Novel", "Book" to "Book", "Graphic Novel" to "Graphic Novel",
            "Comic Book" to "Comic Book", "Short Story" to "Short Story", "Play" to "Play", "Article" to "Article")

        private fun descriptors(a: JSONArray?): String =
            (0 until (a?.length() ?: 0)).mapNotNull { a?.optString(it)?.trim()?.takeIf { s -> s.isNotEmpty() && s != "null" } }.take(3).joinToString(", ")

        private fun countryName(code: String, fallback: String = ""): String = when {
            code == "US" -> "United States"
            code == "GB" -> "United Kingdom"
            code.length == 2 -> Locale("", code).getDisplayCountry(Locale.US).takeIf { it.isNotEmpty() && it != code } ?: fallback
            else -> fallback
        }

        /**
         * Provider names as people say them, each service once: Dream OS's own names for the ones it knows,
         * add-on channels and ad tiers left out ("HBO Max Amazon Channel", "Netflix Standard with Ads").
         */
        private fun providerNames(a: JSONArray?): List<String> {
            if (a == null) return emptyList()
            // The services Dream OS knows lead (Netflix, HBO Max...), the rest in TMDB's order.
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.sortedWith(compareBy(
                { o -> if (Tmdb.SERVICES.any { it.providerId == o.optInt("provider_id") }) 0 else 1 },
                { o -> o.optInt("display_priority", 999) })).mapNotNull { o ->
                val id = o.optInt("provider_id")
                Tmdb.SERVICES.firstOrNull { it.providerId == id }?.let { return@mapNotNull it.name }
                var n = o.optString("provider_name").trim()
                // YouTube's movie store stays out; YouTube TV is a real streaming service and shows (owner, 2026-10-28).
                if (n.isEmpty() || id == 192 || (n.contains("YouTube", true) && !n.equals("YouTube TV", true)) ||
                    n.contains("Channel", true) || n.contains("with Ads", true)) return@mapNotNull null
                n = n.replace("Paramount Plus", "Paramount+").replace(Regex("\\s+(Premium|Essential|Standard|Basic)$"), "")
                when (n) {
                    "Amazon Video" -> "Amazon"
                    "Amazon Prime Video" -> "Prime Video"
                    "Apple TV Store" -> "Apple TV"
                    "Google Play Movies" -> "Google Play"
                    "tru TV" -> "truTV"
                    "fuboTV" -> "Fubo"
                    else -> n
                }
            }.distinct()
        }
    }
}

/**
 * The Information grid: label / value pairs in [columns] columns (a cell may span two), small tracked caps
 * in [dim] over values in [fg]. Built once when the data arrives; static (no focus, no per-frame work);
 * [set] rewrites one value in place (the ratings or the next episode's time landing later).
 */
class InfoGrid(
    ctx: Context, cells: List<TitleInfo.Cell>, widthPx: Int, fg: Int, dim: Int,
    labelFace: Typeface, valueFace: Typeface, columns: Int = 4, maxRows: Int = 5,
) : LinearLayout(ctx) {
    private val values = HashMap<String, TextView>()
    /** How many cells made it on (for the log line). */
    val count: Int

    override fun hasOverlappingRendering() = false

    init {
        orientation = VERTICAL
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val gap = dp(GAP)
        val unit = (widthPx - (columns - 1) * gap) / columns
        val rows = pack(cells, columns).take(maxRows)
        count = rows.sumOf { it.size }
        for ((ri, row) in rows.withIndex()) {
            val line = object : LinearLayout(ctx) { override fun hasOverlappingRendering() = false }.apply { orientation = HORIZONTAL }
            for ((ci, c) in row.withIndex()) {
                val span = c.span.coerceIn(1, columns)
                val cell = object : LinearLayout(ctx) { override fun hasOverlappingRendering() = false }.apply { orientation = VERTICAL }
                cell.addView(text(ctx, c.label, 11f, dim, labelFace, 1).apply { isAllCaps = true; letterSpacing = 0.12f },
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                val v = text(ctx, c.value, 14.5f, fg, valueFace, 2).apply { setLineSpacing(0f, 1.16f) }
                values[c.label] = v
                cell.addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
                line.addView(cell, LayoutParams(span * unit + (span - 1) * gap, LayoutParams.WRAP_CONTENT).apply {
                    if (ci < row.size - 1) marginEnd = gap
                })
            }
            addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { if (ri > 0) topMargin = dp(ROW_GAP) })
        }
    }

    /** Replaces the value under [label] (only a cell that's on the grid). */
    fun set(label: String, value: String?) {
        if (value.isNullOrBlank()) return
        values[label]?.let { if (it.text.toString() != value) it.text = value }
    }

    private fun text(ctx: Context, s: String, sp: Float, color: Int, face: Typeface, lines: Int) = TextView(ctx).apply {
        text = s
        textSize = sp
        setTextColor(color)
        typeface = face
        includeFontPadding = false
        maxLines = lines
        if (lines == 1) isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    companion object {
        private const val GAP = 36
        private const val ROW_GAP = 22

        /** Rows of [cols] units in reading order; a later one-column cell fills a hole a wide cell left. */
        fun pack(cells: List<TitleInfo.Cell>, cols: Int): List<List<TitleInfo.Cell>> {
            val left = cells.toMutableList()
            val rows = ArrayList<List<TitleInfo.Cell>>()
            while (left.isNotEmpty()) {
                val row = ArrayList<TitleInfo.Cell>()
                var used = 0
                var i = 0
                while (i < left.size && used < cols) {
                    val s = left[i].span.coerceIn(1, cols)
                    if (used + s <= cols) { row += left.removeAt(i); used += s } else i++
                }
                rows += row
            }
            return rows
        }
    }
}
