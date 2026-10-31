package com.dreamos.desktop.details

import com.dreamos.desktop.settings.kit.Glyph
import com.dreamos.desktop.settings.kit.PillKind
import com.dreamos.desktop.settings.kit.TitleRef
import java.time.LocalDate

/**
 * What the title page shows, already decided by core (availability, language pills, watched, history, scores).
 * The page only lays it out. Stand-ins until win-content / win-playback's types land; see [TitleSource].
 */
data class PillSpec(val text: String, val kind: PillKind, val glyph: Glyph? = null)

data class Scores(val tomato: Int? = null, val imdb: Double? = null, val tmdb: Double? = null, val tmdbVotes: Int = 0, /** Core's "IMDb 6.5" / "TMDB 7.1", shown exactly as given. */ val badge: String? = null)

/** Where the viewer is in this title (WatchHistory): the episode for shows, how far, minutes left. */
data class TitleProgress(val season: Int? = null, val episode: Int? = null, val episodeName: String? = null, val fraction: Float = 0f, val minutesLeft: Int = 0) {
    val inProgress: Boolean get() = fraction > 0f && fraction < FINISHED
    companion object { const val FINISHED = 0.92f }
}

/**
 * [comingWords]: an announced season's chip words ("Coming Mar 4", "Coming 2027", "Upcoming"); [comingLong]: its card and
 * blurb words ("Coming March 4, 2027", else "Date to Be Announced").
 */
data class SeasonInfo(val number: Int, val name: String, val episodeCount: Int, val comingWords: String? = null, val comingLong: String? = null)

data class EpisodeInfo(
    val season: Int,
    val number: Int,
    val name: String,
    val runtimeMin: Int? = null,
    val airDate: LocalDate? = null,
    val overview: String = "",
    val stillUrl: String? = null,
    val fallbackArt: List<String> = emptyList(),
    val progress: Float = 0f,
    val watched: Boolean = false,
    /** Availability pill for episodes aired in the last 21 days or up early (core decides). */
    val pill: PillSpec? = null,
    /** "English Dub", "Japanese Audio" once that episode's copies were read. */
    val languageWords: String? = null,
    /** Not out and no copy found: not playable. */
    val upcoming: Boolean = false,
    /** "Coming Friday at 9:00 PM" for upcoming ones. */
    val comingWords: String? = null,
    /** The exact instant an upcoming one airs (epoch ms, TVmaze), null when only [airDate] is known. */
    val airsAt: Long? = null,
)

data class CastMember(val id: Int, val name: String, val role: String, val photoUrl: String?)

/** [overview] and [audience] ("IMDb 7.5", "TMDB 7.1") feed More Like This's blurb. */
data class TitleCard(val ref: TitleRef, val name: String, val posterUrl: String?, val year: Int?, val pill: PillSpec? = null, val overview: String = "", val audience: String? = null)

data class InfoCell(val label: String, val value: String, val wide: Boolean = false)

data class WhereToWatch(val streaming: List<String> = emptyList(), val free: List<String> = emptyList(), val rentOrBuy: List<String> = emptyList()) {
    val isEmpty: Boolean get() = streaming.isEmpty() && free.isEmpty() && rentOrBuy.isEmpty()
}

data class TitleDetails(
    val ref: TitleRef,
    val name: String,
    val logoUrl: String? = null,
    val backdropUrl: String? = null,
    val posterUrl: String? = null,
    /** The movie's release day (earliest US theatrical or digital) or the show's first air date. */
    val releaseDate: LocalDate? = null,
    val year: Int? = null,
    val certification: String? = null,
    val runtimeMin: Int? = null,
    val seasonCount: Int? = null,
    val genres: List<String> = emptyList(),
    val scores: Scores = Scores(),
    /** The status band: availability or Watched pill, its note, language pills. */
    val statusPill: PillSpec? = null,
    val statusNote: String? = null,
    val languagePills: List<PillSpec> = emptyList(),
    val tagline: String? = null,
    val overview: String = "",
    val trailerKey: String? = null,
    val seasons: List<SeasonInfo> = emptyList(),
    /** The season to open on: the history's if it is out, else the first one out. */
    val defaultSeason: Int? = null,
    val cast: List<CastMember> = emptyList(),
    val similar: List<TitleCard> = emptyList(),
    val info: List<InfoCell> = emptyList(),
    val whereToWatch: WhereToWatch = WhereToWatch(),
    val progress: TitleProgress? = null,
    /** The episode Play starts for shows with no progress: history's up next, else S1 E1. */
    val upNext: Pair<Int, Int>? = null,
    val onMyList: Boolean = false,
    /** Core's own label for Play ("Resume S2 E4"); the page works it out from [progress] when null. */
    val playLabel: String? = null,
)

data class PersonDetails(
    val id: Int,
    val name: String,
    val photoUrl: String?,
    val birthday: LocalDate?,
    val deathday: LocalDate?,
    val birthplace: String?,
    val biography: String,
    val knownFor: List<TitleCard>,
    val credits: List<TitleCard>,
    /** Core's worded line ("Actor  ·  Born March 3, 1971 in Seoul"); used in place of the dates when set. */
    val metaLine: String? = null,
)

/** One copy in Choose a Source. Line words are core's (StreamBadges); the page lays them out. */
data class SourceOption(
    val id: String,
    /** "4K  ·  12.3 GB" */
    val headline: String,
    /** "Dolby Vision  ·  HEVC  ·  Atmos  ·  English Dub" */
    val badges: String,
    /** The release name, prettified. */
    val releaseName: String,
    val pills: List<PillSpec> = emptyList(),
    /** The release name says another episode: listed behind "Show N More" (SourcePicker). */
    val otherEpisode: Boolean = false,
)

/**
 * The title page's data. Core's catalog (win-content) and sources (win-playback) implement it; [DevTitleSource]
 * stands in until then.
 */
interface TitleSource {
    suspend fun details(ref: TitleRef): TitleDetails
    /**
     * The page as last built for [ref] when it is recent (a hover or an earlier visit built it), for the first frame
     * with no waiting; null otherwise. The page shows it at once and asks [details] again behind it (an equal answer
     * changes nothing on screen).
     */
    fun instant(ref: TitleRef): TitleDetails? = null
    suspend fun season(ref: TitleRef, season: Int): List<EpisodeInfo>
    /**
     * A season listed as still to come whose episodes are already up (out early: a source decides, not the date). One
     * quick, bounded check; the page then drops its "Coming ..." words and lets it play.
     */
    suspend fun seasonUpEarly(ref: TitleRef, season: Int): Boolean = false
    /** One person; [excluding] is the title they were opened from (left out of Known For). */
    suspend fun person(id: Int, excluding: TitleRef? = null): PersonDetails
    /** Choose a Source's list; [loose] is Search All Sources (the viewer's source filters set aside, what they'd hide marked). */
    suspend fun sources(ref: TitleRef, season: Int?, episode: Int?, loose: Boolean = false): List<SourceOption>
    /**
     * The picked source ([SourceOption.id] from the last [sources]) made ready to play: core's request for Dream Player.
     * Null where this source can't prepare one (the dev sources): the page then hands the pick to [PagesNav.play].
     * Throws DreamError when the account can't play it right now.
     */
    suspend fun prepare(ref: TitleRef, season: Int?, episode: Int?, sourceId: String): com.dreamos.desktop.core.playback.PlayRequest? = null
    /** Whether [ref] is on My List now (no network): a poster's options say Add or Remove. */
    fun onMyList(ref: TitleRef): Boolean = false
    /** Adds or removes the title from My List; returns whether it is on the list now. */
    suspend fun setMyList(ref: TitleRef, on: Boolean): Boolean

    // ---- preloading (preload.TitleAhead): the page draws complete and follows new answers in place ----

    /** A season's episodes as last built, when recent (no network): the episode lane draws them in its first frame. */
    fun seasonNow(ref: TitleRef, season: Int): List<EpisodeInfo>? = null
    /** Keys of new pill answers (availability); the page then rebuilds its pills with [livePills] / [liveEpisodePills]. */
    val pillUpdates: kotlinx.coroutines.flow.Flow<String> get() = kotlinx.coroutines.flow.emptyFlow()
    /** [d] with its status, language and More Like This pills as the kept answers say now (no network). */
    suspend fun livePills(d: TitleDetails): TitleDetails = d
    /** [eps] (one show's) with their pills as the kept answers say now (no network). */
    fun liveEpisodePills(ref: TitleRef, eps: List<EpisodeInfo>): List<EpisodeInfo> = eps
    /** A pill is due on this page (its answer may still be on the way): the page keeps the pill's room. */
    fun pillExpected(d: TitleDetails): Boolean = false
    /** Asks ahead for what the page shows further down (More Like This pills), behind the viewer. */
    fun lookAhead(d: TitleDetails) {}
}
