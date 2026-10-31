package com.dreamos.desktop.details

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The title page's words (CinemaDetails / TitleInfo), one place so every page agrees. Dates are date-only and never
 * shift a day; when core's DreamTime lands, the "today" here becomes DreamTime's.
 */
object TitleWords {
    const val DOT = "  ·  "
    /** Upcoming.TBA */
    const val TBA = "Date to Be Announced"
    private val LONG = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)
    private val SHORT = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val SHORT_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    fun long(d: LocalDate): String = d.format(LONG)

    /** "Oct 12" this year, "Oct 12, 2025" another year. */
    fun short(d: LocalDate, today: LocalDate = LocalDate.now()): String = d.format(if (d.year == today.year) SHORT else SHORT_YEAR)

    /** The meta line's date: "Coming March 1, 2027" while ahead, else "July 31, 2026", else the year. */
    fun metaDate(release: LocalDate?, year: Int?, today: LocalDate = LocalDate.now()): String? = when {
        release != null && release.isAfter(today) -> "Coming ${long(release)}"
        release != null -> long(release)
        year != null -> "$year"
        else -> null
    }

    /** "2 hr 46 min", "2 hr", "56 min". */
    fun runtime(min: Int): String = when {
        min < 60 -> "$min min"
        min % 60 == 0 -> "${min / 60} hr"
        else -> "${min / 60} hr ${min % 60} min"
    }

    fun seasons(n: Int): String = if (n == 1) "1 Season" else "$n Seasons"

    /** "$190 million", "$1.2 billion", "$850,000". */
    fun money(v: Long): String = when {
        v >= 1_000_000_000 -> "$" + trim(v / 1e9) + " billion"
        v >= 1_000_000 -> "$" + trim(v / 1e6) + " million"
        else -> "$" + String.format(Locale.US, "%,d", v)
    }

    private fun trim(x: Double): String = String.format(Locale.US, "%.1f", x).removeSuffix(".0")

    /** The audience score exactly as published: "IMDb 6.5", else "TMDB 7.1" after 150 votes (Ratings.audienceFor). */
    fun audience(s: Scores): Pair<String, String>? = when {
        s.badge != null -> s.badge.substringBefore(' ') to s.badge.substringAfter(' ')
        s.imdb != null -> "IMDb" to String.format(Locale.US, "%.1f", s.imdb)
        s.tmdb != null && s.tmdbVotes >= 150 -> "TMDB" to String.format(Locale.US, "%.1f", s.tmdb)
        else -> null
    }

    /**
     * [d] with Information's Episode Length from a loaded season's real median runtime (CinemaDetails / TitleInfo
     * .episodeMinutes); unchanged when the season says nothing or the grid has no such cell.
     */
    fun withEpisodeLength(d: TitleDetails, eps: List<EpisodeInfo>): TitleDetails {
        val mins = eps.mapNotNull { it.runtimeMin?.takeIf { m -> m > 0 } }.sorted()
        if (mins.isEmpty() || d.info.none { it.label == EPISODE_LENGTH }) return d
        val m = mins.size / 2
        val median = if (mins.size % 2 == 1) mins[m] else Math.round((mins[m - 1] + mins[m]) / 2.0).toInt()
        return d.copy(info = d.info.map { if (it.label == EPISODE_LENGTH) it.copy(value = runtime(median)) else it })
    }

    const val EPISODE_LENGTH = "Episode Length"

    /** "S2 E4" */
    fun se(season: Int, episode: Int): String = "S$season E$episode"

    /** Play's label (PlayFlow.playLabel): "Resume" / "Resume S2 E4" while in progress, "Play S2 E5" for a show, else "Play". */
    fun playLabel(d: TitleDetails): String {
        d.playLabel?.let { return it }
        val p = d.progress
        if (!d.ref.isShow) return if (p != null && p.inProgress) "Resume" else "Play"
        if (p != null && p.inProgress && p.season != null && p.episode != null) return "Resume ${se(p.season, p.episode)}"
        val next = d.upNext ?: (d.defaultSeason ?: 1).let { it to 1 }
        return "Play ${se(next.first, next.second)}"
    }

    /** "S2 E4  ·  Name  ·  12 Min Left" (parts left out when unknown). */
    fun progressLine(d: TitleDetails): String? {
        val p = d.progress?.takeIf { it.inProgress } ?: return null
        return listOfNotNull(
            if (p.season != null && p.episode != null) se(p.season, p.episode) else null,
            p.episodeName?.takeIf { it.isNotBlank() },
            p.minutesLeft.takeIf { it > 0 }?.let { "$it Min Left" },
        ).joinToString(DOT).ifBlank { null }
    }

    /**
     * An episode card's sub line (CinemaDetails.episodeCard): "47 min  ·  English Dub", or the upcoming words. The air
     * date lives in the blurb under the lane ("Aired October 12, 2026").
     */
    fun episodeSub(e: EpisodeInfo): String = if (e.upcoming) (e.comingWords ?: TBA)
    else listOfNotNull(e.runtimeMin?.takeIf { it > 0 }?.let { runtime(it) }, e.languageWords).joinToString(DOT)

    /** An episode card's title: "4. Name"; one still to come reads "S2 · E7  Name" ("S2 · Episode 7" with no name yet). */
    fun episodeCardTitle(e: EpisodeInfo): String = if (!e.upcoming) "${e.number}. ${episodeName(e)}"
    else e.name.takeIf { it.isNotBlank() && !it.matches(PLACEHOLDER) }?.let { "S${e.season} · E${e.number}  $it" } ?: "S${e.season} · Episode ${e.number}"

    /**
     * An upcoming card's date stamp (CinemaDetails.dateStamp): weekday, "Oct 30" ("Oct 30, 2027" another year) and the
     * time when TVmaze's exact instant is known (in the viewer's zone); null when the date isn't known.
     */
    fun dateStamp(e: EpisodeInfo): Triple<String, String, String?>? {
        val dt = com.dreamos.desktop.core.time.DreamTime
        val at = e.airsAt
        if (at != null && at > 0) {
            val thisYear = dt.format(at, "yyyy") == dt.thisYear().toString()
            return Triple(dt.format(at, "EEEE"), dt.format(at, if (thisYear) "MMM d" else "MMM d, yyyy"), dt.formatTime(at))
        }
        val d = e.airDate ?: return null
        val thisYear = d.year == dt.thisYear()
        return Triple(d.format(DateTimeFormatter.ofPattern("EEEE", Locale.US)), d.format(if (thisYear) SHORT else SHORT_YEAR), null)
    }

    /**
     * The red pill on an upcoming card (Availability.whenForms): "Coming Oct 30" while its day is ahead, "Coming Soon"
     * with no date, "Not Available Yet" once the day came and no copy is up.
     */
    fun comingPill(e: EpisodeInfo, today: LocalDate = LocalDate.now()): String {
        val dt = com.dreamos.desktop.core.time.DreamTime
        val at = e.airsAt
        if (at != null && at > dt.now()) return "Coming " + dt.format(at, if (dt.format(at, "yyyy") == dt.thisYear().toString()) "MMM d" else "MMM d, yyyy")
        val d = e.airDate ?: return "Coming Soon"
        return if (d.isAfter(today)) "Coming " + short(d, today) else "Not Available Yet"
    }

    /** TMDB's stand-in names for an episode not named yet ("Episode 7", "TBA"). */
    private val PLACEHOLDER = Regex("^(episode|ep\\.?)\\s*#?\\d+$|^(tba|tbd|untitled)$", RegexOption.IGNORE_CASE)

    /** The blurb under the episodes: "Season 2, Episode 3  ·  47 min  ·  Aired October 12, 2026". */
    fun episodeBlurb(e: EpisodeInfo, today: LocalDate = LocalDate.now()): String = listOfNotNull(
        "Season ${e.season}, Episode ${e.number}",
        if (e.upcoming) e.comingWords ?: TBA else null,
        e.runtimeMin?.takeIf { it > 0 && !e.upcoming }?.let { runtime(it) },
        e.airDate?.takeIf { !e.upcoming && !it.isAfter(today) }?.let { "Aired ${long(it)}" },
        e.languageWords,
    ).joinToString(DOT)

    fun episodeName(e: EpisodeInfo): String = e.name.ifBlank { "Episode ${e.number}" }

    /** The person's life line without a dash: "Born July 31, 1970  ·  Chicago" or "July 31, 1970 to January 1, 2020  ·  Chicago". */
    fun lifeLine(p: PersonDetails): String? {
        val dates = when {
            p.birthday != null && p.deathday != null -> "${long(p.birthday)} to ${long(p.deathday)}"
            p.birthday != null -> "Born ${long(p.birthday)}"
            else -> null
        }
        return listOfNotNull(dates, p.birthplace?.takeIf { it.isNotBlank() }).joinToString(DOT).ifBlank { null }
    }

    /**
     * A bio's lead that ends on a whole sentence (CinemaPages bio rule): within [max] characters, cut after the last
     * ". ! ?" that isn't a lone initial, at least 40 characters in; with no such end the whole bio shows (never
     * a cut with "…").
     */
    fun bioLead(bio: String, max: Int = 260): String {
        val text = bio.replace(Regex("\\s*\\n+\\s*"), " ").trim()
        if (text.length <= max) return text
        val window = text.take(max + 1)
        val ends = Regex("[.!?][\"')\\]]?(?=\\s)").findAll(window).map { it.range.last + 1 }
            .filter { it >= 40 && !Regex("\\s[A-Z]\\.$").containsMatchIn(window.take(it)) }.toList()
        return ends.lastOrNull()?.let { window.take(it) } ?: text
    }
}
