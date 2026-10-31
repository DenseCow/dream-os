package com.dreamos.desktop.core.playback

import kotlinx.serialization.Serializable

/*
 * Dream Core for the player and the pages (fire-home NativeResolver + PlayFlow + PreResolve, iPhone PlaybackService +
 * PlaybackExtras + LookAheadService). Every suspend call is bounded and never throws except where it says
 * PlaybackError (whose words go straight on screen).
 */

/** One row of Choose a Source (fire-home NativeResolver.Listed, iPhone StreamCandidate's source-list fields). */
@Serializable
data class SourceRow(
    val source: Source,
    /** Badges the release name carries, CAM first: "CAM", "Dolby Vision", "HDR", "HEVC", "Remux", "Atmos", "5.1", "English Dub". */
    val badges: List<String>,
    /** The viewer's filters would hide it (Search All Sources lists it, marked). */
    val outsideFilters: Boolean,
    val cam: Boolean,
    /** The release name says another episode: listed behind "Show More". */
    val otherEpisode: Boolean = false,
    /** True: ready to stream now; false: not cached; null: not checked (only the top rows are). */
    val ready: Boolean? = null,
    /** "Torrentio", "Comet": the provider shown on the row. */
    val providerName: String = "",
)

/** What the scrapers' quick check found (for the pills): counts and the best release of each grade. No debrid asked. */
@Serializable
data class SourceSurvey(val total: Int, val quality: Int, val cam: Int, val best: String?, val bestCam: String?)

interface PlaybackApi {
    /** A debrid account is linked (without one nothing can play). No network. */
    fun canPlay(): Boolean

    /**
     * Play: the best ready stream for [target], with its alternates, starting at [startMs]. Throws [PlaybackError]
     * with the loading screen's words when nothing plays ("Couldn't Find a Ready Stream"...).
     */
    suspend fun play(target: PlayTarget, startMs: Long = 0): PlayRequest

    /** Search All Sources: [play] with the viewer's filters set aside for this one search (Hide CAM stays). */
    suspend fun playLoose(target: PlayTarget, startMs: Long = 0): PlayRequest

    /** Choose a Source: every source ranked as Play ranks them; [loose] lists what the filters hide, marked. */
    suspend fun sources(target: PlayTarget, loose: Boolean = false): List<SourceRow>

    /** Which of [rows]' top rows are ready now (fills [SourceRow.ready]); null when that can't be told cheaply. */
    suspend fun readyHashes(rows: List<SourceRow>): Set<String>?

    /** The viewer picked [source]: unlock it. Throws PlaybackError "This Source Isn't Ready". */
    suspend fun playSource(target: PlayTarget, source: Source, startMs: Long = 0): PlayRequest

    /** [stream] failed to open or play: forgotten, its torrent skipped for [target] for an hour. */
    fun failed(target: PlayTarget, stream: Stream)

    /** The next ready alternate after a failure (no network), or null: then show the error. Call [failed] first. */
    fun nextStream(target: PlayTarget): Stream?

    /** The player opens [stream] now: its first frame is timed from here. */
    fun handing(stream: Stream)

    /** The player showed [stream]'s first frame: feeds the fastest-service order and source learning. */
    fun firstFrame(target: PlayTarget, stream: Stream)

    /** Survey for the pills (counts, best release); null when the scrapers didn't answer. */
    suspend fun survey(target: PlayTarget): SourceSurvey?

    // ---- Instant Play look-ahead: every call returns at once ----

    /** A title page opened: its likely Play (the movie, or the Up Next episode) is scraped and ranked now. */
    fun titleOpened(target: PlayTarget)

    /** The pointer or focus rested on a card (~400 ms): the quick check only. */
    fun cardRested(target: PlayTarget)

    /** Continue Watching is on screen: its first items are looked up, one at a time. */
    fun continueWatching(targets: List<PlayTarget>)

    /** About 90 s before an episode's Up Next card: the next episode's link is found now (and its head fetched). */
    fun nextEpisodeAhead(next: PlayTarget)

    /** Dream Player opened / closed: background look-aheads hold off while it plays. */
    fun playerActive(active: Boolean)

    /**
     * The loading screen gave up on [target], or the viewer chose Try Again: the lookup still in flight for it is
     * dropped, so the next Play starts a fresh one instead of waiting on the old one. No network.
     */
    fun abandon(target: PlayTarget) {}

    /** App start, a title page: Python and the debrid connections warmed. */
    fun warm()

    // ---- Skip markers (Dream Player); defaults so stand-in players compile ----

    /**
     * Intro / recap / credits / preview for [target] once its duration is known. [chapters]: the file's own, from mpv's
     * `chapter-list`. Cached; bounded by the HTTP timeouts; never throws ([SkipInfo.NONE] when nothing is known).
     */
    suspend fun skipMarkers(target: PlayTarget, durationMs: Long, chapters: List<SkipChapter> = emptyList()): SkipInfo = SkipInfo.NONE

    /** The viewer jumped past what looks like an intro: kept for the show (the player's [SkipLearner] decides when). */
    fun learnIntro(target: PlayTarget, fromMs: Long, toMs: Long) {}

    /** The viewer pressed Skip Intro on a learned intro ([SkipInfo.introLearned]): it is trusted more. */
    fun introConfirmed(target: PlayTarget) {}

    /** A learner for one item, feeding [learnIntro]. Call its `tick` about twice a second and `end` when the item ends. */
    fun skipLearner(target: PlayTarget, info: SkipInfo): SkipLearner = SkipLearner(target, info) { from, to -> learnIntro(target, from, to) }

    // ---- Language pills: what Dream Player and Dream Subtitles learn ----

    /** Dream Player's first frame: the audio languages [target]'s tracks carry (codes like "en" or "eng"), and the copy's hash. */
    fun heardLanguages(target: PlayTarget, hash: String?, langs: Set<String>) {}

    /** Dream Subtitles' index answered for [target]: the subtitle languages it has (empty: none). */
    fun subtitleIndex(target: PlayTarget, langs: Set<String>) {}

    /** The viewer's subtitle languages (Settings › Subtitles, first and second), for the "English Subtitles" pill. */
    fun viewerSubtitleLanguages(langs: List<String>) {}
}

/**
 * Choose a Source's words, the same on every Dream OS (fire-home SourcePicker). The Search All Sources row opens the
 * list again with `sources(target, loose = true)`; from the loading screen's error it is `playLoose`.
 */
object SourceListWords {
    const val CHANGE_SOURCE = "Change Source"
    const val ALL_SOURCES = "All Sources"
    const val SEARCH_ALL = "Search All Sources"
    const val SEARCH_ALL_LINE = "Includes Ones Your Source Settings Usually Hide"
    const val FIND_ANOTHER = "Find Another Source"
    const val FIND_ANOTHER_LINE = "Dream OS Picks the Best One, From Where You Are"
    const val OUTSIDE_FILTERS = "Outside Your Filters"
    /** "Show 4 More" with "For a Different Title or Episode" beneath. */
    fun showMore(n: Int) = "Show $n More"
    const val SHOW_MORE_LINE = "For a Different Title or Episode"
}
