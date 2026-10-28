package com.firehome.launcher

import android.widget.FrameLayout
import java.net.URLDecoder

/**
 * Shared contract for Dream Player, the built-in video player that plays what Dream Core finds.
 * Several parts are built separately (engine/activity, controls, skip markers, play flow), so
 * these types are the agreed seams between them. Extend with care; don't rename.
 */

/**
 * An external subtitle file or URL. [online]: downloaded by Dream Subtitles (not tagged "External" in
 * the menu); [asDefault] false: listed, not chosen by itself.
 */
data class SubtitleFile(val uri: String, val language: String?, val label: String?, val online: Boolean = false, val asDefault: Boolean = true)

/** Stream URL helpers: request headers after '|' and subtitle languages from file names. */
object StreamUrls {
    /** The header suffix of a stream URL (the part after '|': "User-Agent=Foo%20Bar&Referer=…"); values are URL-encoded. */
    fun headers(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in s.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val k = decode(pair.substring(0, eq)).trim()
            val v = decode(pair.substring(eq + 1)).trim()
            // Player options, not HTTP headers.
            if (k.isEmpty() || k.equals("verifypeer", true) || k.equals("seekable", true) || k.startsWith("!")) continue
            out[k] = v
        }
        return out
    }

    /** "Movie.en.srt" / "Movie.English.forced.srt" -> "en" / "English"; null when the name has no language part. */
    fun langFromName(path: String): String? {
        val parts = path.substringAfterLast('/').substringBefore('?').split('.')
        if (parts.size < 3) return null
        return parts.subList(1, parts.size - 1).lastOrNull { p ->
            p.all(Char::isLetter) && (p.length in 2..3 && p.lowercase() == p || p.length > 3 && p[0].isUpperCase()) &&
                !p.equals("forced", true) && !p.equals("sdh", true)
        }
    }

    private fun decode(s: String): String = try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { s }
}

/** What is being played. Built by the play flow from Dream OS metadata. */
data class PlaybackRequest(
    val title: String,              // movie title, or the show name for episodes
    val type: String,               // "movie" | "episode"
    val tmdbId: Int,                // movie id, or the SHOW id for episodes
    val imdb: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val backdrop: String? = null,   // TMDB image URLs
    val poster: String? = null,
    val logo: String? = null,
    val startMs: Long = 0,          // resume position; 0 = from the start
    val player: String? = null,     // the source the stream came from, shown in the player's info (null: not known)
    val autoplayNext: Boolean = true,
    /** A title's trailer played in Dream Player: no sources, skip, post-play or Continue Watching. */
    val trailer: Boolean = false,
)

/** One audio or text track of the current stream. */
data class Track(
    val id: String,
    val kind: String,               // "audio" | "text"
    val label: String,              // human label, e.g. "English · Dolby Digital 5.1"
    val language: String?,
    val selected: Boolean,
    val supported: Boolean,
    /** Audio: the language (and the track's own name when it says more), e.g. "English · Director's Commentary". */
    val title: String? = null,
    /** Audio: codec and channels, e.g. "Dolby Digital 5.1", "AAC Stereo". */
    val detail: String? = null,
)

interface PlayerEngine {
    val isPlaying: Boolean
    val isBuffering: Boolean
    val positionMs: Long
    val durationMs: Long            // 0 when unknown
    val bufferedMs: Long
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun setSpeed(speed: Float)
    fun tracks(kind: String): List<Track>
    /** Selects a track; for "text", null turns subtitles off. */
    fun selectTrack(kind: String, id: String?)
    fun addExternalSubtitle(uri: String, language: String?, label: String?)
    /** Short stream description, e.g. "2160p · HEVC · HDR10 · E-AC3 5.1". */
    fun videoInfo(): String
    fun addListener(l: Listener)
    fun removeListener(l: Listener)

    interface Listener {
        fun onState() {}                 // play/pause/buffering changed
        fun onProgress(positionMs: Long) {} // ~2x per second while playing
        fun onTracks() {}
        fun onEnded() {}
        fun onError(message: String) {}
    }
}

/** What the player screen offers to the components that live on it. */
interface PlayerHost {
    val engine: PlayerEngine
    val request: PlaybackRequest?
    /** Add component views here: above the video and subtitles, below dialogs. */
    val overlayRoot: FrameLayout
    fun showSkip(label: String, onSkip: () -> Unit)
    fun hideSkip()
    fun setControlsVisible(visible: Boolean)
    /** Ends the current item and starts [next] through the play flow (stays in the player). */
    fun playNext(next: PlaybackRequest)
    /** Leaves the player and returns to Dream OS. */
    fun close()
}

/** Timestamps for skip buttons and post-play; any may be null when unknown. */
data class Markers(
    val introStartMs: Long? = null,
    val introEndMs: Long? = null,
    val recapEndMs: Long? = null,
    val creditsStartMs: Long? = null,
)
