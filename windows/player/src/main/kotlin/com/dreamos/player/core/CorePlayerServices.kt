package com.dreamos.player.core

import com.dreamos.desktop.core.content.WatchHistory
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.PlayRequest as Asked
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.playback.LanguagePillsStore
import com.dreamos.desktop.core.playback.PlayTarget
import com.dreamos.desktop.core.service.Services
import com.dreamos.desktop.core.time.DreamTime
import com.dreamos.desktop.core.trakt.ScrobbleAction
import com.dreamos.desktop.core.trakt.Trakt
import com.dreamos.player.api.CastItem
import com.dreamos.player.api.EpisodeItem
import com.dreamos.player.api.PersonItem
import com.dreamos.player.api.PlayState
import com.dreamos.player.api.PlayerDetails
import com.dreamos.player.api.PlayerServices
import com.dreamos.player.api.RecItem
import com.dreamos.player.api.SeasonItem
import com.dreamos.player.engine.PlayerLog
import com.dreamos.player.session.PlayerSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Dream Player over core: the catalog and title pages (`Services.titles`), the watch history (`Services.history`, which
 * owns the 90 s Continue Watching rule), `WatchHistory.episodeAfter` (the one next-episode rule), Trakt scrobbles,
 * DreamTime for every clock and date. The app passes [langs] from its Playback
 * (`playback.core.langs`) and [openTitle] to its title page.
 */
class CorePlayerServices(
    /** No longer read: core's `PlaybackApi.heardLanguages` records the languages a copy carries. Kept for the shell's call. */
    @Suppress("unused") private val langs: LanguagePillsStore? = null,
    private val openTitlePage: (tmdbId: Int, isShow: Boolean) -> Unit = { _, _ -> },
) : PlayerServices {

    /** What the viewer asked to play, as core's history and Trakt know it. */
    fun asked(t: PlayTarget): Asked = Asked(
        title = Title(
            id = t.tmdbId,
            kind = if (t.isEpisode) MediaKind.TV else MediaKind.MOVIE,
            name = t.title,
            posterPath = t.poster?.takeIf { it.startsWith("/") },
            backdropPath = t.backdrop?.takeIf { it.startsWith("/") },
            year = t.year?.toString() ?: "",
        ),
        imdbId = t.imdb,
        season = t.season,
        episode = t.episode,
        episodeName = t.episodeTitle,
    )

    private fun kind(t: PlayTarget) = if (t.isEpisode) MediaKind.TV else MediaKind.MOVIE

    override suspend fun details(target: PlayTarget): PlayerDetails? {
        val d = runCatching { Services.titles.details(kind(target), target.tmdbId) }.getOrNull() ?: return null
        val ep = if (target.isEpisode) runCatching { Services.titles.episodes(target.tmdbId, target.season ?: 1) }.getOrNull()
            ?.firstOrNull { it.number == target.episode } else null
        return PlayerDetails(
            logoUrl = d.logo("w500"),
            backdropUrl = d.title.backdrop("w1280"),
            overview = d.title.overview,
            runtimeMin = d.runtime,
            genres = d.genres,
            year = d.title.year.toIntOrNull(),
            vote = d.title.rating,
            episodeName = ep?.name ?: target.episodeTitle,
            episodeOverview = ep?.overview?.ifBlank { null },
            episodeAirDate = ep?.airDate?.ifBlank { null },
            episodeStillUrl = ep?.still("w780"),
            episodeRuntimeMin = ep?.runtime ?: 0,
            imdbId = d.imdbId,
        )
    }

    override suspend fun seasons(showId: Int): List<SeasonItem> =
        runCatching { Services.titles.details(MediaKind.TV, showId).seasons }.getOrNull().orEmpty()
            .map { SeasonItem(it.number, it.name, it.episodeCount) }

    override suspend fun episodes(showId: Int, season: Int): List<EpisodeItem> =
        runCatching { Services.titles.episodes(showId, season) }.getOrNull().orEmpty().map {
            EpisodeItem(it.season, it.number, it.name, it.overview, it.still("w300"), it.airDate, it.runtime, upcoming = it.upcoming && !it.outEarly, airsAt = it.airsAt)
        }

    override suspend fun nextEpisode(target: PlayTarget): PlayTarget? {
        if (!target.isEpisode) return null
        val (season, ep) = runCatching { WatchHistory.episodeAfter(target.tmdbId, target.season ?: return null, target.episode ?: return null) }
            .getOrNull() ?: return null
        val n = ep["episode_number"]?.jsonPrimitive?.intOrNull ?: return null
        val name = ep["name"]?.jsonPrimitive?.contentOrNull
        val air = ep["air_date"]?.jsonPrimitive?.contentOrNull
        return target.atEpisode(season, n, name, air)
    }

    override suspend fun recommendations(target: PlayTarget): List<RecItem> =
        runCatching { Services.titles.details(kind(target), target.tmdbId).recommendations }.getOrNull().orEmpty()
            .filter { it.backdropPath != null }
            .filter { runCatching { Services.titles.allowed(it) }.getOrDefault(false) }
            .take(4)
            .map { RecItem(it.id, it.isShow, it.name, it.backdrop("w780"), it.year.toIntOrNull(), it.poster("w342")) }

    override suspend fun cast(target: PlayTarget): List<CastItem> =
        runCatching { Services.titles.cast(asked(target)) }.getOrNull().orEmpty()
            .map { CastItem(it.id, it.name, it.character, it.profile("w185")) }

    override suspend fun person(personId: Int, excluding: PlayTarget): PersonItem? {
        val key = "${kind(excluding).path}:${excluding.tmdbId}"
        val p = runCatching { Services.titles.person(personId, key) }.getOrNull() ?: return null
        return PersonItem(
            p.name, p.profile("w342"), p.meta, p.bio,
            p.knownFor.take(14).map { RecItem(it.id, it.isShow, it.name, it.backdrop("w780"), it.year.toIntOrNull(), it.poster("w342")) },
        )
    }

    override suspend fun historyStart(target: PlayTarget): Long =
        runCatching { Services.history.startPositionMs(asked(target)) }.getOrDefault(0L)

    /**
     * An episode row's progress (PlayerMenus.episodeRow): the show's history entry's own episode at its saved spot (not
     * while it is only queued as Up Next), every earlier episode full (specials aside), later ones none.
     */
    override fun watchedFraction(target: PlayTarget): Float? = runCatching {
        val e = runBlocking { Services.history.entry(asked(target).key) } ?: return@runCatching null
        val s = target.season ?: 0
        val n = target.episode ?: 0
        when {
            e.season == s && e.episode == n -> if (!e.upNext && e.durationMs > 0) (e.positionMs.toFloat() / e.durationMs).coerceIn(0f, 1f) else null
            s in 1 until e.season || (s == e.season && n < e.episode) -> 1f
            else -> null
        }
    }.getOrNull()

    override fun report(target: PlayTarget, positionMs: Long, durationMs: Long, realWatchedMs: Long, exact: Boolean) {
        runCatching { runBlocking { Services.history.report(asked(target), positionMs, durationMs, realWatchedMs, exact) } }
            .onFailure { PlayerLog.w("History: report failed (${it.message})") }
    }

    override fun scrobble(target: PlayTarget, progress: Double, state: PlayState, durationMs: Long) {
        val action = when (state) { PlayState.PLAYING -> ScrobbleAction.START; PlayState.PAUSED -> ScrobbleAction.PAUSE; PlayState.STOPPED -> ScrobbleAction.STOP }
        Trakt.scrobble(asked(target), progress, action, durationMs)
    }

    override fun playerClosed() = Trakt.playerClosed()


    override val autoplayNext: Boolean get() = PlayerSettings.autoplayNext
    override val stillWatching: Boolean get() = PlayerSettings.stillWatching

    override fun clock(epochMs: Long): String = DreamTime.formatTime(epochMs)
    override fun date(isoDate: String): String = DreamTime.formatDay(isoDate, "MMM d, yyyy") ?: isoDate
    override fun openTitle(tmdbId: Int, isShow: Boolean) = openTitlePage(tmdbId, isShow)
}
