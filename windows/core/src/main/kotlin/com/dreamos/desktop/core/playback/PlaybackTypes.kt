package com.dreamos.desktop.core.playback

import kotlinx.serialization.Serializable

/*
 * The shared playback types: what is played (PlayTarget), what the scrapers find (ScrapedSource), what Dream Core
 * ranks (Source), what a debrid service hands back (Stream) and what Dream Player gets (PlayRequest).
 * Ported from fire-home (PlayerContract.kt PlaybackRequest, DebridApi.kt Candidate / Found) and the iPhone
 * (Models.swift PlayRequest / StreamCandidate / StreamLink). win-content owns core/model (Title, Episode...);
 * PlayTarget is the small local stand-in until that lands, then gets a `from(Title, Episode)` builder.
 */

/** Movie or one episode of a show. */
@Serializable
enum class MediaKind { MOVIE, EPISODE }

/**
 * What to play: a movie, or one episode of a show. [tmdbId] is the movie's id, or the SHOW's id for an episode.
 * [originalLanguage] (TMDB's ISO 639-1) and [spokenLanguages] drive the dub preference and the language pills.
 */
@Serializable
data class PlayTarget(
    val kind: MediaKind,
    val tmdbId: Int,
    val title: String,
    val year: Int? = null,
    val imdb: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val originalLanguage: String? = null,
    val spokenLanguages: List<String> = emptyList(),
    val isAnime: Boolean = false,
    /** Minutes; 0 when unknown (the "fit my internet speed" size limit needs it). */
    val runtimeMin: Int = 0,
    /** The episode's (or movie's) listed date, yyyy-MM-dd; informational only: a source decides availability. */
    val airDate: String? = null,
    val backdrop: String? = null,
    val poster: String? = null,
    val logo: String? = null,
) {
    val isEpisode get() = kind == MediaKind.EPISODE

    /**
     * The key every playback store uses for this title / episode: "movie:603", "tv:1396:1:2". The same as fire-home's
     * AnimeDubs.key and the iPhone's pills key, so data moved between apps lines up.
     */
    val key: String get() {
        val id = if (tmdbId > 0) "$tmdbId" else imdb.orEmpty()
        return if (isEpisode) "tv:$id:$season:$episode" else "movie:$id"
    }

    /** For the log: "Severance S2E3", "Dune (2021)". */
    val label: String get() = if (isEpisode) "$title S${season}E$episode" else title + (year?.let { " ($it)" } ?: "")

    /** The same show at another episode (next episode look-ahead, Up Next). */
    fun atEpisode(season: Int, episode: Int, episodeTitle: String? = null, airDate: String? = null) =
        copy(season = season, episode = episode, episodeTitle = episodeTitle, airDate = airDate)
}

/**
 * A video quality grade. [rank] 0 is the best; the filters' cap and the device cap use this scale (fire-home's
 * `qi`). 8K exists only so it can be recognized and kept out: Dream Core never picks an 8K copy.
 */
@Serializable
enum class Quality(val label: String, val rank: Int) {
    UHD_8K("8K", -1), UHD_4K("4K", 0), FHD_1080("1080p", 1), HD_720("720p", 2), SD("SD", 3);

    companion object {
        /** The scale index (0 4K, 1 1080p, 2 720p, 3 SD) back to a grade. */
        fun ofRank(rank: Int) = entries.firstOrNull { it.rank == rank } ?: SD
        fun ofLabel(label: String) = entries.firstOrNull { it.label.equals(label, true) }
    }
}

/**
 * One result the user's scrapers return (what `scrapers` hands to Dream Core). [hash] is the torrent's info hash,
 * lowercase hex (results without one are hosters, which Dream Core doesn't unlock yet, so the engine drops them).
 * [quality] is the provider's own label ("2160p", "1080p", "CAM", "SD"...). [provider] is "module:provider".
 * [pack]: a season pack (its size is the whole season's).
 */
@Serializable
data class ScrapedSource(
    val hash: String,
    val name: String,
    val quality: String = "SD",
    val sizeBytes: Long = 0,
    val seeders: Int = 0,
    val provider: String = "",
    val pack: Boolean = false,
    val fileName: String? = null,
    val fileIdx: Int? = null,
)

/**
 * One torrent Dream Core ranks and asks the debrid services about (fire-home Candidate, iPhone StreamCandidate).
 * [season] / [episode]: what was asked for (episodes), so a service can pick the right file in a pack.
 */
@Serializable
data class Source(
    val hash: String,
    val torrentName: String,
    val fileName: String? = null,
    val fileIdx: Int? = null,
    val sizeBytes: Long = 0,
    val quality: Quality = Quality.SD,
    val seeders: Int = 0,
    val provider: String = "",
    val pack: Boolean = false,
    val season: Int? = null,
    val episode: Int? = null,
)

/** The debrid services Dream Core can use, in the default ask order. [key] is the stored id on every platform. */
@Serializable
enum class DebridServiceId(val key: String, val displayName: String) {
    PREMIUMIZE("pm", "Premiumize"),
    TORBOX("tb", "TorBox"),
    REAL_DEBRID("rd", "Real-Debrid"),
    ALLDEBRID("ad", "AllDebrid");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
        /** The default order: services whose cache check adds nothing to the account first (fire-home ORDER). */
        val DEFAULT_ORDER = listOf(PREMIUMIZE, TORBOX, REAL_DEBRID, ALLDEBRID)
    }
}

/**
 * A playable direct link to the video file (fire-home Found, iPhone StreamLink). Plain-HTTP links are upgraded to
 * HTTPS (all four services serve both). [headers]: request headers the player must send (none for the debrid
 * services today; scrapers' direct links may need them). [note]: the loading screen's short line when the viewer's
 * filters were set aside ("Only a CAM Version Is Available"), else null. [cam]: the copy is a camera / screener one.
 */
@Serializable
data class Stream(
    val url: String,
    val service: DebridServiceId,
    val fileName: String,
    val sizeBytes: Long = 0,
    val quality: Quality = Quality.SD,
    val hash: String = "",
    val headers: Map<String, String> = emptyMap(),
    val note: String? = null,
    val cam: Boolean = false,
    /** The release (torrent) name, for the player's stream badges. */
    val releaseName: String = "",
) {
    /** The url with http:// upgraded to https:// (cleartext never leaves the PC; loopback addresses stay as they are). */
    val secureUrl: String get() = if (url.startsWith("http://", true) && !url.startsWith("http://127.0.0.1", true) &&
        !url.startsWith("http://localhost", true)) "https://" + url.substring(7) else url
}

/**
 * What Dream Player gets for a Play: the stream to open (with its headers), what it is, where to start, and the other
 * ready links Dream Core unlocked alongside it ([alternates], best first; the player falls back to them silently
 * when the stream fails to open or start, iPhone `nextLink`).
 */
@Serializable
data class PlayRequest(
    val target: PlayTarget,
    val stream: Stream,
    /** Where to start, ms; 0 from the beginning. The watch history decides it, never a page (STANDARDS §5). */
    val startMs: Long = 0,
    val alternates: List<Stream> = emptyList(),
    /** The bytes of [stream] already on disk are served from here (the stream head start), or null: open [Stream.url]. */
    val localUrl: String? = null,
) {
    val url: String get() = localUrl ?: stream.secureUrl
    val headers: Map<String, String> get() = if (localUrl != null) emptyMap() else stream.headers
}

/**
 * A failure the viewer sees: [title] a Title Case heading, [message] a plain sentence saying what happened and what
 * to do. Never technical (STANDARDS §4).
 */
class PlaybackError(val title: String, override val message: String) : Exception(message)

/**
 * Why a Play's lookup came back empty (fire-home NativeResolver.Miss): [NOTHING] the sources had nothing playable at
 * all (not out yet), [NOT_READY] none of what was found is ready at the debrid service(s), [NO_ANSWER] the scrapers
 * didn't answer (never "not out yet").
 */
enum class Miss { NOTHING, NOT_READY, NO_ANSWER }

/**
 * Why a lookup runs, which sets how many torrents it may add to the viewer's debrid accounts (fire-home
 * DebridPurpose). Ordered: a later purpose is "more" than an earlier one.
 */
enum class DebridPurpose(val maxUploads: Int) {
    /** A card the pointer or focus rests on: the scrapers' quick check only, nothing sent to any service. */
    BROWSE(0),
    /** A title page's Instant Play look-ahead: scrape and rank; only free cache checks (Premiumize, TorBox). */
    PAGE(0),
    /** The next episode while one plays. */
    AHEAD(2),
    /** The source list's "Ready" marks (its top rows only). */
    LIST(5),
    /** A real Play, or a source the viewer picked: never held back. */
    PLAY(10),
}
