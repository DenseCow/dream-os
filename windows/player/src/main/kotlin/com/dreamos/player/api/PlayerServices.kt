package com.dreamos.player.api

import com.dreamos.desktop.core.playback.PlayTarget

/**
 * What Dream Player asks of the rest of Dream OS. The app wires one implementation at start (core's catalog,
 * PlaybackApi, history and settings); every member has a default so the player works with nothing but a URL.
 * Suspend calls run off the UI thread and must be bounded (the player never waits more than a few seconds on one).
 */
interface PlayerServices {
    // ---- Content (TMDB through core) ----
    suspend fun details(target: PlayTarget): PlayerDetails? = null
    suspend fun seasons(showId: Int): List<SeasonItem> = emptyList()
    suspend fun episodes(showId: Int, season: Int): List<EpisodeItem> = emptyList()
    /** The next aired episode (WatchHistory.episodeAfter: the one rule; an episode up early counts as aired). */
    suspend fun nextEpisode(target: PlayTarget): PlayTarget? = null
    /** More Like This at a movie's end: 4 with a backdrop, the profile's Kids limit applied. */
    suspend fun recommendations(target: PlayTarget): List<RecItem> = emptyList()
    /** X-Ray: the episode's cast and guest stars (or the movie's), top billing first, at most 24. */
    suspend fun cast(target: PlayTarget): List<CastItem> = emptyList()
    suspend fun person(personId: Int, excluding: PlayTarget): PersonItem? = null

    // ---- History, Continue Watching, Trakt, language pills ----
    /** Where playback starts for [target] (the history's call): the episode picker and Next Episode use it. */
    suspend fun historyStart(target: PlayTarget): Long = 0
    /** Saved fraction watched for an episode row (null: none; 1: finished). */
    fun watchedFraction(target: PlayTarget): Float? = null
    /**
     * The player's report for the watch history (core History.report): every 30 s while playing and [exact] on pause,
     * stop, close, end, error and a source switch. Never before the first frame, for trailers, or for clips under
     * 10 minutes. [realWatchedMs] counts only real playback (seeks and skips add nothing). Called off the UI thread.
     */
    fun report(target: PlayTarget, positionMs: Long, durationMs: Long, realWatchedMs: Long, exact: Boolean) {}
    /** Trakt: playing, paused, stopped ([progress] 0..1). Returns at once. */
    fun scrobble(target: PlayTarget, progress: Double, state: PlayState, durationMs: Long) {}
    fun playerClosed() {}

    // ---- Settings and words ----
    val autoplayNext: Boolean get() = true
    val stillWatching: Boolean get() = true
    /** "10:42 PM" / "22:42" in the viewer's time zone and clock choice (DreamTime). */
    fun clock(epochMs: Long): String = java.time.format.DateTimeFormatter.ofPattern("h:mm a")
        .format(java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()))
    /** "Mar 4, 2021" for a yyyy-MM-dd date (DreamTime, never shifting a day). */
    fun date(isoDate: String): String = runCatching {
        java.time.LocalDate.parse(isoDate).format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy"))
    }.getOrDefault(isoDate)

    /** X-Ray's Known For and More Like This shows open a title page; the player closes first. */
    fun openTitle(tmdbId: Int, isShow: Boolean) {}
}

enum class PlayState { PLAYING, PAUSED, STOPPED }

/** What the player tells the app. Called on the UI thread. */
interface PlayerEvents {
    /** The player moved on to another title or episode (Next Episode, Up Next, the Episodes sheet). */
    fun nowPlaying(target: PlayTarget) {}
    /** The player closed; the app shows its window again (and flushes notices). */
    fun closed(target: PlayTarget, positionMs: Long) {}
}

data class PlayerDetails(
    val logoUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String = "",
    val runtimeMin: Int = 0,
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    /** TMDB's vote average (0 when unknown). */
    val vote: Double = 0.0,
    /** Episodes: the episode's own name, synopsis, air date (yyyy-MM-dd) and still. */
    val episodeName: String? = null,
    val episodeOverview: String? = null,
    val episodeAirDate: String? = null,
    val episodeStillUrl: String? = null,
    val episodeRuntimeMin: Int = 0,
    /** The title's IMDb id (Dream Subtitles' index is keyed by it). */
    val imdbId: String? = null,
)

data class SeasonItem(val number: Int, val name: String, val episodeCount: Int)

data class EpisodeItem(
    val season: Int,
    val number: Int,
    val name: String,
    val overview: String = "",
    val stillUrl: String? = null,
    val airDate: String = "",
    val runtimeMin: Int = 0,
    val upcoming: Boolean = false,
    /** The exact instant an upcoming episode airs (epoch ms), null when only [airDate] is known. */
    val airsAt: Long? = null,
)

data class RecItem(val tmdbId: Int, val isShow: Boolean, val title: String, val backdropUrl: String?, val year: Int? = null, val posterUrl: String? = null)

data class CastItem(val personId: Int, val name: String, val character: String, val photoUrl: String?)

data class PersonItem(
    val name: String,
    val photoUrl: String?,
    /** "Jan 5, 1970 – Jan 1, 2020  ·  Place" / "Born Jan 5, 1970". */
    val meta: String,
    val bio: String,
    val knownFor: List<RecItem>,
    /** The person couldn't be looked up: Known For says so instead of "No other titles to show". */
    val failed: Boolean = false,
)

/**
 * One segment as the player shows it (core's SkipMark). [estimated]: a guess at the credits for Up Next timing only (no
 * tick, no Skip Credits). [learned]: an intro learned from the viewer's own skips (offered, never skipped by itself).
 */
data class SkipMarker(val kind: Kind, val startMs: Long, val endMs: Long, val estimated: Boolean = false, val learned: Boolean = false) {
    enum class Kind { INTRO, RECAP, CREDITS }
}
