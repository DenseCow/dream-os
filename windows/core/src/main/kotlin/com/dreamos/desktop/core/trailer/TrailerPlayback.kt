package com.dreamos.desktop.core.trailer

import com.dreamos.desktop.core.playback.DebridServiceId
import com.dreamos.desktop.core.playback.MediaKind
import com.dreamos.desktop.core.playback.PlayRequest
import com.dreamos.desktop.core.playback.PlayTarget
import com.dreamos.desktop.core.playback.PlaybackApi
import com.dreamos.desktop.core.playback.PlaybackError
import com.dreamos.desktop.core.playback.Quality
import com.dreamos.desktop.core.playback.Source
import com.dreamos.desktop.core.playback.SourceRow
import com.dreamos.desktop.core.playback.SourceSurvey
import com.dreamos.desktop.core.playback.Stream
import com.dreamos.desktop.core.util.Log

/**
 * The Trailer button's way into Dream Player: a [PlaybackApi] that plays [ref]'s trailer instead of asking Dream Core.
 * Dream Player's trailer mode runs on it unchanged:
 *
 * ```
 * val tp = TrailerPlayback(ref, playback)
 * DreamPlayer.open(window, tp.pending(target), tp, services, events, trailer = true)
 * ```
 * The curtain shows at once; [play] resolves (usually instant: the title page looked it up already), from the hero
 * trailer's spot when the same video was just playing there. Fallbacks ride as the request's alternates (the joined
 * 4K/1080p streams, then HLS, then the muxed MP4). A failure marks that video, so Try Again takes the title's next one.
 *
 * Dream Core never hears of a trailer: no first-frame timing, no failed-link ledger, no next stream. Only
 * [playerActive] goes through to [base] (look-aheads rest while any player is up).
 */
class TrailerPlayback(
    private val ref: TrailerRef,
    private val base: PlaybackApi? = null,
    /** The picture's top class: 2160 (4K) on the PC; a smaller screen needs no less, mpv scales down. */
    private val maxClass: Int = 2160,
) : PlaybackApi {
    @Volatile private var current: Trailer? = null
    @Volatile private var sources: List<String> = emptyList()
    @Volatile private var started = false
    @Volatile private var handedAt = 0L
    @Volatile private var attempts = 0

    /**
     * The request to open Dream Player with: no stream yet (the curtain shows while [play] resolves), starting at the
     * hero's spot when there is one. Never 0: a 0 start makes the player ask the watch history, which is the movie's.
     */
    fun pending(target: PlayTarget): PlayRequest =
        PlayRequest(target, Stream(url = "", service = DebridServiceId.REAL_DEBRID, fileName = TRAILER), startMs = startSpot() ?: 1L)

    /** The hero's spot when the hero played the trailer that is (still) cached for this title. */
    private fun startSpot(): Long? {
        val (key, ms) = TrailerEngine.spot(ref) ?: return null
        return ms.takeIf { TrailerEngine.cached(ref)?.videoKey == key && it > 0 }
    }

    override fun canPlay() = true

    override suspend fun play(target: PlayTarget, startMs: Long): PlayRequest {
        val retry = attempts++ > 0
        val t = TrailerEngine.resolve(ref, retry = retry)
            ?: throw PlaybackError("Trailer Unavailable", "This trailer can't be played right now. Try again in a moment.")
        val list = t.sources(maxClass).map { s ->
            Stream(url = s.url, service = DebridServiceId.REAL_DEBRID, fileName = TRAILER, quality = quality(if (s.cls > 0) s.cls else maxClass))
        }
        if (list.isEmpty()) throw PlaybackError("Trailer Unavailable", "This trailer can't be played right now. Try again in a moment.")
        // A spot from the hero only holds for that same video; Try Again starts the next video from its beginning.
        val spot = TrailerEngine.spot(ref)?.takeIf { it.first == t.videoKey }?.second
        current = t
        sources = list.map { it.url }
        started = false
        Log.i("Trailer", "player: ${ref.key} (${t.videoKey}) ${t.sources(maxClass).joinToString(" / ") { it.label }}")
        return PlayRequest(target, list.first(), startMs = spot ?: startMs.coerceAtLeast(0), alternates = list.drop(1))
    }

    override suspend fun playLoose(target: PlayTarget, startMs: Long) = play(target, startMs)

    override suspend fun sources(target: PlayTarget, loose: Boolean): List<SourceRow> = emptyList()
    override suspend fun readyHashes(rows: List<SourceRow>): Set<String>? = null
    override suspend fun playSource(target: PlayTarget, source: Source, startMs: Long): PlayRequest = play(target, startMs)

    /** A source failed: the player moves to the next alternate; after the last one, the video is marked failed. */
    override fun failed(target: PlayTarget, stream: Stream) {
        val t = current ?: return
        when {
            started -> TrailerEngine.forget(ref) // it played, then stopped: fresh links next time
            stream.url == sources.lastOrNull() -> TrailerEngine.failed(ref, t.videoKey, "no picture from any of its streams")
        }
    }

    override fun nextStream(target: PlayTarget): Stream? = null

    override fun handing(stream: Stream) { handedAt = System.nanoTime() }

    override fun firstFrame(target: PlayTarget, stream: Stream) {
        started = true
        val ms = (System.nanoTime() - handedAt) / 1_000_000
        Log.i("Trailer", "player: first frame of ${ref.key} after $ms ms")
    }

    override suspend fun survey(target: PlayTarget): SourceSurvey? = null
    override fun titleOpened(target: PlayTarget) {}
    override fun cardRested(target: PlayTarget) {}
    override fun continueWatching(targets: List<PlayTarget>) {}
    override fun nextEpisodeAhead(next: PlayTarget) {}
    override fun playerActive(active: Boolean) { base?.playerActive(active) }
    override fun warm() {}

    companion object {
        /** The stream's name inside the player (it never shows a file name for trailers). */
        const val TRAILER = "Trailer"

        /** A target for the player's curtain and Info: the title itself (a show as its first episode slot, unnumbered). */
        fun target(ref: TrailerRef, year: Int? = null, backdrop: String? = null, logo: String? = null) = PlayTarget(
            kind = if (ref.isShow) MediaKind.EPISODE else MediaKind.MOVIE, tmdbId = ref.tmdbId, title = ref.name,
            year = year, backdrop = backdrop, logo = logo,
        )

        private fun quality(cls: Int): Quality = when {
            cls >= 2160 -> Quality.UHD_4K
            cls >= 1080 -> Quality.FHD_1080
            cls >= 720 -> Quality.HD_720
            else -> Quality.SD
        }
    }
}
