package com.dreamos.desktop.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The shared data every module speaks. Mirrors fire-home's Tmdb.kt / TitleInfo.kt / WatchHistory.kt and the iPhone's
// DreamCore/Models.swift so behaviour ports 1:1. Never rename or remove a field; a new field gets a default value so
// every stored copy still reads (the JSON reader ignores unknown keys and fills defaults).

@Serializable
enum class MediaKind {
    @SerialName("movie") MOVIE,
    @SerialName("tv") TV;

    /** "movie" / "tv", as TMDB paths and [Title.key] spell it. */
    val path: String get() = if (this == MOVIE) "movie" else "tv"

    companion object {
        fun of(s: String): MediaKind? = when (s) {
            "movie" -> MOVIE
            "tv", "episode", "show" -> TV
            else -> null
        }
    }
}

/** TMDB image URLs. Sizes: posters w185 / w342 / w500 / w780, backdrops w300 / w780 / w1280 / original, logos w300 / w500. */
object TmdbImage {
    const val BASE = "https://image.tmdb.org/t/p/"
    fun url(path: String?, size: String): String? = if (path.isNullOrEmpty()) null else BASE + size + path
}

/** A movie or show as list results describe it (fire-home Tmdb.kt `Title`). */
@Serializable
data class Title(
    val id: Int,
    val kind: MediaKind,
    val name: String,
    val overview: String = "",
    val posterPath: String? = null,
    val backdropPath: String? = null,
    /** "2024"; empty when unknown. */
    val year: String = "",
    /** TMDB vote average (0-10). */
    val rating: Double = 0.0,
    /** Release (movies) or first air date (shows), "yyyy-MM-dd"; empty when unknown. */
    val date: String = "",
    /** TMDB vote count: [rating] means little below a few dozen votes (0 = unknown). */
    val votes: Int = 0,
    /** TMDB genre ids from list results (empty when unknown, e.g. a saved title). */
    val genreIds: List<Int> = emptyList(),
    /** Original language (ISO 639-1), "" when unknown. */
    val lang: String = "",
    /** TMDB's adult flag; such titles never make a row. */
    val adult: Boolean = false,
    /** Origin countries (ISO 3166-1); the anime check reads them for non-Japanese animation. */
    val originCountry: List<String> = emptyList(),
) {
    /** "movie:603" / "tv:1396": the key history, My List, Trakt and every cache use (same on every Dream OS app). */
    val key: String get() = "${kind.path}:$id"
    val isShow: Boolean get() = kind == MediaKind.TV
    fun poster(size: String = "w342"): String? = TmdbImage.url(posterPath, size)
    fun backdrop(size: String = "w780"): String? = TmdbImage.url(backdropPath, size)
}

@Serializable
data class Provider(val id: Int, val name: String, val logoPath: String? = null) {
    fun logo(size: String = "w92"): String? = TmdbImage.url(logoPath, size)
}

@Serializable
data class CastMember(
    val id: Int,
    val name: String,
    val character: String = "",
    val profilePath: String? = null,
) {
    fun profile(size: String = "w185"): String? = TmdbImage.url(profilePath, size)
}

/** One season as a show's details list it (specials are season 0). */
@Serializable
data class SeasonSummary(
    val number: Int,
    val name: String,
    val episodeCount: Int,
    /** "yyyy-MM-dd" or "". */
    val airDate: String = "",
    val posterPath: String? = null,
)

@Serializable
data class Episode(
    val showId: Int,
    val season: Int,
    val number: Int,
    val name: String,
    val overview: String = "",
    val stillPath: String? = null,
    /** "yyyy-MM-dd" or "". Date-only: never shifted by a time zone (DreamTime). */
    val airDate: String = "",
    /** Minutes, 0 = unknown. */
    val runtime: Int = 0,
    /** The exact instant it airs or drops (epoch ms, TVmaze), null when only [airDate] is known. */
    val airsAt: Long? = null,
    /** Still to come: not playable yet; word its date with DreamTime. */
    val upcoming: Boolean = false,
    /** Up before its air day: the index already has a copy, so it plays like any aired episode ("a source decides"). */
    val outEarly: Boolean = false,
) {
    val id: String get() = "$showId:$season:$number"
    fun still(size: String = "w300"): String? = TmdbImage.url(stillPath, size)
}

/** Everything a title page's Information section shows (fire-home TitleInfo.kt). Dates are raw "yyyy-MM-dd"; word them with DreamTime. */
@Serializable
data class TitleInfo(
    val premiere: String = "",
    val theatrical: String = "",
    val digital: String = "",
    val cert: String = "",
    val certNote: String = "",
    /** Minutes (a show's typical episode length). */
    val runtime: Int = 0,
    val runtimeApprox: Boolean = false,
    val genres: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val basedOn: String = "",
    val producers: List<String> = emptyList(),
    val creators: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val networks: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val language: String = "",
    /** TMDB's spoken languages ("ko", "en"): a dub tells itself from an original track by them (language pills). */
    val spoken: List<String> = emptyList(),
    val originalTitle: String = "",
    val budget: Long = 0,
    val revenue: Long = 0,
    val collection: String = "",
    /** TMDB collection id (franchise spreading in Taste), 0 = none. */
    val collectionId: Int = 0,
    val status: String = "",
    val lastAired: String = "",
    val nextSeason: Int = 0,
    val nextEpisode: Int = 0,
    val nextDate: String = "",
    val seasons: Int = 0,
    val episodes: Int = 0,
    /** Where to Watch (US, from JustWatch through TMDB): subscription, free (with ads) and rent / buy service names. */
    val streaming: List<String> = emptyList(),
    val free: List<String> = emptyList(),
    val rentBuy: List<String> = emptyList(),
    val isShow: Boolean = false,
    /** TMDB's one-line tagline, "" when none. */
    val tagline: String = "",
    /** TMDB's own score and vote count. */
    val score: Double = 0.0,
    val votes: Int = 0,
)

/** A title page's data (fire-home Tmdb.kt `Details`): one TMDB request. */
@Serializable
data class TitleDetails(
    val title: Title,
    val genres: List<String> = emptyList(),
    /** Minutes; a show's episode length. 0 = unknown. */
    val runtime: Int = 0,
    /** "2 hr 10 min" (movies) or "3 Seasons" (shows), as the title page words it; "" when unknown. */
    val runtimeWords: String = "",
    /** Trailer video key. Played inside Dream Player; never shown or named as YouTube. */
    val trailerKey: String? = null,
    /** Where to Watch, subscription providers (US). YouTube's movie store is left out; YouTube TV stays. */
    val providers: List<Provider> = emptyList(),
    /** Top-billed cast (at most 15). */
    val cast: List<CastMember> = emptyList(),
    /** More Like This (at most 20), already filtered for the profile. */
    val recommendations: List<Title> = emptyList(),
    /** Shows: every season TMDB lists, specials included. */
    val seasons: List<SeasonSummary> = emptyList(),
    val imdbId: String? = null,
    /** The title's English logo art (transparent PNG path), when TMDB has one. */
    val logoPath: String? = null,
    val info: TitleInfo? = null,
) {
    fun logo(size: String = "w500"): String? = TmdbImage.url(logoPath, size)
}

/**
 * Scores as Dream OS shows them (fire-home Ratings.kt): the Rotten Tomatoes tomato, then the audience score exactly as
 * published ("IMDb 6.5", else "TMDB 7.1" after 150 votes). No stars anywhere.
 */
@Serializable
data class Scores(
    /** Tomatometer 0-100, null when unknown. */
    val tomatometer: Int? = null,
    /** Certified Fresh / fresh / rotten, for the tomato art. */
    val tomato: Tomato? = null,
    /** "IMDb 6.5" / "TMDB 7.1", null when there is nothing worth showing. */
    val audienceBadge: String? = null,
    /** The age rating ("PG-13", "TV-MA"), "" when none. */
    val certification: String = "",
) {
    @Serializable
    enum class Tomato { CERTIFIED, FRESH, ROTTEN }
}

/** One person's facts, biography and best-known titles (person page, X-Ray). */
@Serializable
data class PersonDetails(
    val id: Int,
    val name: String,
    val profilePath: String? = null,
    /** "Actor  ·  Born March 3, 1971 in Seoul" style line, worded with DreamTime. */
    val meta: String = "",
    /** Ends on a full sentence. */
    val bio: String = "",
    val knownFor: List<Title> = emptyList(),
    val department: String = "",
    /**
     * Every credit for the Movies & Shows grid, newest first (announced titles without a date at the end): acting and
     * crew, one entry per title, within the profile's rating limit. Talk and news shows, appearances as themselves and
     * titles without a poster are left out.
     */
    val credits: List<PersonCredit> = emptyList(),
) {
    fun profile(size: String = "h632"): String? = TmdbImage.url(profilePath, size)
}

/** One title on a person page's Movies & Shows grid and what they did on it. */
@Serializable
data class PersonCredit(
    val title: Title,
    /** "Walter White", "Director", "Director, Writer"; "" when TMDB names no role. */
    val role: String = "",
    /** TMDB's department for the first role: "Acting", "Directing", "Writing", "Production"... */
    val department: String = "",
)

/** A show's latest aired episode and its next air day (the Watched pill's "caught up" rule). */
@Serializable
data class ShowAirState(
    val lastSeason: Int,
    val lastEpisode: Int,
    /** Episodes per season, index = season number (specials at 0). */
    val seasonCounts: List<Int> = emptyList(),
    /** "yyyy-MM-dd" of the next episode, "" when none is announced. */
    val nextAir: String = "",
)
