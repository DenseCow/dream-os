package com.dreamos.player.dev

import com.dreamos.desktop.core.playback.DebridServiceId
import com.dreamos.desktop.core.playback.PlayRequest
import com.dreamos.desktop.core.playback.PlayTarget
import com.dreamos.desktop.core.playback.PlaybackApi
import com.dreamos.desktop.core.playback.Quality
import com.dreamos.desktop.core.playback.Source
import com.dreamos.desktop.core.playback.SourceRow
import com.dreamos.desktop.core.playback.SourceSurvey
import com.dreamos.desktop.core.playback.Stream
import com.dreamos.player.api.CastItem
import com.dreamos.player.api.EpisodeItem
import com.dreamos.player.api.PlayState
import com.dreamos.player.api.PlayerDetails
import com.dreamos.player.api.PlayerEvents
import com.dreamos.player.api.PlayerServices
import com.dreamos.player.api.SeasonItem
import com.dreamos.player.api.SkipMarker
import com.dreamos.player.engine.PlayerLog

/*
 * Test stand-ins for the dev runners only (never shipped): Dream Core always answers with the open movie Sintel
 * (Blender Foundation, 2010, CC BY 3.0); the details are TMDB's real ones for movie 45745; the episode layout, the
 * skip markers, the second source and the cast list are made up to exercise the UI.
 */

/** Dev runs keep their settings in a scratch folder, never the viewer's own (`build/dev-settings`). */
internal fun devStore() {
    com.dreamos.desktop.core.store.DreamStore.use(com.dreamos.desktop.core.store.DreamStore(java.nio.file.Path.of("build", "dev-settings").toAbsolutePath()))
}

internal const val SINTEL = "https://download.blender.org/durian/movies/Sintel.2010.1080p.mkv"

internal class DevPlayback(url: String) : PlaybackApi {
    private val stream = Stream(url, DebridServiceId.REAL_DEBRID, "Sintel.2010.1080p.mkv", 691_000_000, Quality.FHD_1080, hash = "0f1e2d", releaseName = "Sintel.2010.1080p.BluRay.x264")
    override fun canPlay() = true
    override suspend fun play(target: PlayTarget, startMs: Long) = PlayRequest(target, stream, startMs)
    override suspend fun playLoose(target: PlayTarget, startMs: Long) = play(target, startMs)
    override suspend fun sources(target: PlayTarget, loose: Boolean) = listOf(
        SourceRow(Source("0f1e2d", "Sintel.2010.1080p.BluRay.x264", "Sintel.2010.1080p.mkv", null, 691_000_000, Quality.FHD_1080, 120), listOf("BluRay", "H.264"), false, false, ready = true),
        SourceRow(Source("a1b2c3", "Sintel.2010.2160p.WEB-DL.HEVC.HDR", null, null, 2_400_000_000, Quality.UHD_4K, 35), listOf("HDR", "HEVC"), true, false, ready = null),
    )
    override suspend fun readyHashes(rows: List<SourceRow>): Set<String>? = null
    override suspend fun playSource(target: PlayTarget, source: Source, startMs: Long) = play(target, startMs)
    override fun failed(target: PlayTarget, stream: Stream) = PlayerLog.i("Dev: failed ${target.label}")
    override fun nextStream(target: PlayTarget): Stream? = null
    override fun handing(stream: Stream) {}
    override fun firstFrame(target: PlayTarget, stream: Stream) {}
    override suspend fun survey(target: PlayTarget): SourceSurvey? = null
    override fun titleOpened(target: PlayTarget) {}
    override fun cardRested(target: PlayTarget) {}
    override fun continueWatching(targets: List<PlayTarget>) {}
    override fun nextEpisodeAhead(next: PlayTarget) = PlayerLog.i("Dev: look ahead ${next.label}")
    override fun playerActive(active: Boolean) {}
    override fun warm() {}
    override suspend fun skipMarkers(target: PlayTarget, durationMs: Long, chapters: List<com.dreamos.desktop.core.playback.SkipChapter>) =
        com.dreamos.desktop.core.playback.SkipInfo(listOf(
            com.dreamos.desktop.core.playback.SkipMark(com.dreamos.desktop.core.playback.SkipKind.INTRO, 5_000, 52_000),
            com.dreamos.desktop.core.playback.SkipMark(com.dreamos.desktop.core.playback.SkipKind.CREDITS, 12 * 60_000L + 24_000, durationMs),
        ))
}

internal object DemoEvents : PlayerEvents {
    override fun nowPlaying(target: PlayTarget) = PlayerLog.i("Demo: now playing ${target.label}")
    override fun closed(target: PlayTarget, positionMs: Long) = PlayerLog.i("Demo: closed at ${positionMs / 1000}s")
}

internal class DemoServices : PlayerServices {
    /** -Ddream.autoplay=false holds Up Next on screen (no countdown) for a screenshot. */
    override val autoplayNext: Boolean get() = System.getProperty("dream.autoplay") != "false"
    override suspend fun details(target: PlayTarget) = PlayerDetails(
        // TMDB's own art for Sintel (movie 45745).
        logoUrl = "https://image.tmdb.org/t/p/w500/p3NwyW8Jf5AGmKk5B0K69MhiMWv.png",
        backdropUrl = "https://image.tmdb.org/t/p/w1280/msqeiEyIRpPAtrCeRGFNZQ9tkJL.jpg",
        overview = "A lonely young woman, Sintel, helps and befriends a dragon, whom she calls Scales. But when he is kidnapped by an adult dragon, Sintel decides to embark on a dangerous quest to find her lost friend Scales.",
        runtimeMin = 14, genres = listOf("Animation", "Fantasy"), year = 2010, vote = 7.0, imdbId = "tt1727587",
        episodeName = target.episodeTitle, episodeOverview = if (target.isEpisode) "Test data for the episode layout." else null,
        episodeRuntimeMin = if (target.isEpisode) 15 else 0, episodeAirDate = if (target.isEpisode) "2010-09-27" else null,
    )
    private val names = listOf("One", "Two", "Three")
    override suspend fun seasons(showId: Int) = listOf(SeasonItem(1, "Season 1", 3), SeasonItem(2, "Season 2", 2))
    override suspend fun episodes(showId: Int, season: Int) = (1..(if (season == 1) 3 else 2)).map {
        EpisodeItem(season, it, "Test Episode ${names[it - 1]}", "Test data for the episode layout.", null, "2010-09-27", 15)
    } + listOfNotNull(if (season == 1) EpisodeItem(1, 4, "Test Episode Four", upcoming = true,
        airsAt = System.currentTimeMillis() + 3 * 86_400_000L, airDate = java.time.LocalDate.now().plusDays(3).toString()) else null)
    override suspend fun nextEpisode(target: PlayTarget) =
        target.episode?.takeIf { target.isEpisode && it < 3 }?.let { target.atEpisode(1, it + 1, "Test Episode ${names[it]}") }
    override suspend fun cast(target: PlayTarget) = listOf(
        CastItem(1, "Halina Reijn", "Sintel (voice)", null),
        CastItem(2, "Thom Hoffman", "Shaman (voice)", null),
    )
    override fun report(target: PlayTarget, positionMs: Long, durationMs: Long, realWatchedMs: Long, exact: Boolean) =
        PlayerLog.i("Dev: report ${target.label} ${positionMs / 1000}/${durationMs / 1000}s real=${realWatchedMs / 1000}s exact=$exact")
    override fun scrobble(target: PlayTarget, progress: Double, state: PlayState, durationMs: Long) =
        PlayerLog.i("Dev: scrobble $state at ${(progress * 100).toInt()}%")
}
