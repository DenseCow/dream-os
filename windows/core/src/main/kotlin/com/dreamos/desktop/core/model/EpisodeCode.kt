package com.dreamos.desktop.core.model

import kotlinx.serialization.Serializable

/**
 * What an episode is: one numbered episode of a season, or a special, which belongs to a show but sits outside its
 * order (STANDARDS §5). A special is a kind, never a season number: season 0 is how the outside world numbers one and
 * it reaches three boundaries only (TMDB's `/tv/{id}/season/0`, the index request `{imdb}:0:{n}` that Cinemeta
 * answers, and release-name matching). Code that means "a real episode in the linear order" asks for [SERIES].
 */
@Serializable
enum class EpisodeKind { SERIES, SPECIAL }

/**
 * What a watch-history entry is about. Three-valued on purpose: a special behaves like a movie that belongs to a show
 * (its own entry, its own card, finished means off the row), so neither "movie" nor "episode" fits it, and a branch
 * that has to name one of the three cannot quietly treat a special as either.
 */
@Serializable
enum class EntryKind { MOVIE, SERIES, SPECIAL }

/**
 * The one place an episode is named for a viewer, on every screen: "S2 E5" for a numbered episode, a special's own
 * title for a special. Nothing a viewer sees says "Season 0" or "S0", and nothing shows a special's number, which is
 * a position in air order that moves when the sources backfill an earlier one (fire-home PlayFlow.episodeCode).
 */
object EpisodeCode {
    /** A special's stand-in name, where its own title isn't known. */
    const val SPECIALS = "Specials"

    /** "S2 E5"; [sep] goes between the two parts. Numbered episodes only ([label] covers both kinds). */
    fun of(season: Int, episode: Int, sep: String = " ") = "S$season" + sep + "E$episode"

    /**
     * What an episode is called on a card, a line or a button: "S2 E5" for a numbered episode, a special's own title
     * ([name]) for a special, else "Specials".
     */
    fun label(kind: EpisodeKind, season: Int, episode: Int, name: String? = null, sep: String = " "): String =
        if (kind == EpisodeKind.SPECIAL) specialWords(name) else of(season, episode, sep)

    /** [label] for a request that may be a movie's: null when there is no episode to name. */
    fun maybe(kind: EpisodeKind, season: Int?, episode: Int?, name: String? = null, sep: String = " "): String? {
        if (kind == EpisodeKind.SPECIAL) return specialWords(name)
        val s = season ?: return null
        val e = episode ?: return null
        return of(s, e, sep)
    }

    /** A blurb's words: "Season 2, Episode 5"; a special has no place in the order, so "Specials" alone. */
    fun place(kind: EpisodeKind, season: Int, episode: Int) =
        if (kind == EpisodeKind.SPECIAL) SPECIALS else "Season $season, Episode $episode"

    /** A special's own title, else "Specials". */
    fun specialWords(name: String?): String = name?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: SPECIALS

    /** A season tab's name: "Season 3", or "Specials" for the specials tab. */
    fun seasonName(kind: EpisodeKind, season: Int) = if (kind == EpisodeKind.SPECIAL) SPECIALS else "Season $season"
}
