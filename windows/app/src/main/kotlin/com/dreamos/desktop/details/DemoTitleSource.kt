package com.dreamos.desktop.details

import com.dreamos.desktop.settings.kit.Glyph
import com.dreamos.desktop.settings.kit.PillKind
import com.dreamos.desktop.settings.kit.TitleRef
import kotlinx.coroutines.awaitCancellation

/**
 * DEV ONLY: [DevTitleSource] plus the things core will decide (history, pills, sources) set by hand, so the offscreen
 * previews show every state of the title page. Never used by the app itself.
 */
class DemoTitleSource(private val base: TitleSource, private val mode: Mode = Mode.Normal) : TitleSource by base {
    /** [Upcoming]: Severance's season 2 ends in episodes still to come; [Coming]: it opens on an announced season 3. */
    enum class Mode { Normal, Loading, Failing, Upcoming, Coming }

    companion object {
        val SEVERANCE = TitleRef(95396, true, "Severance")
        val DUNE_TWO = TitleRef(693134, false, "Dune: Part Two")
        val SQUID_GAME = TitleRef(93405, true, "Squid Game")
    }

    override suspend fun details(ref: TitleRef): TitleDetails {
        when (mode) {
            Mode.Loading -> awaitCancellation()
            Mode.Failing -> error("demo failure")
            else -> {}
        }
        val d = base.details(ref)
        if (mode == Mode.Coming && ref.tmdbId == SEVERANCE.tmdbId) return d.copy(progress = null, upNext = 3 to 1, defaultSeason = 3,
            seasons = d.seasons.filter { it.comingWords == null && it.number < 3 } + SeasonInfo(3, "Season 3", 0, "Upcoming", TitleWords.TBA))
        return when (ref.tmdbId) {
            SEVERANCE.tmdbId -> d.copy(progress = TitleProgress(2, 4, "Woe's Hollow", 0.38f, 31), defaultSeason = 2)
            DUNE_TWO.tmdbId -> d.copy(statusPill = PillSpec("Watched", PillKind.Watched))
            SQUID_GAME.tmdbId -> d.copy(
                statusPill = PillSpec("Available Now · 4K", PillKind.Green),
                languagePills = listOf(PillSpec("Korean Audio Only", PillKind.Amber, Glyph.Speaker), PillSpec("English Subtitles", PillKind.Neutral, Glyph.Check)),
            )
            else -> d
        }
    }

    override suspend fun season(ref: TitleRef, season: Int): List<EpisodeInfo> {
        if (mode == Mode.Coming && ref.tmdbId == SEVERANCE.tmdbId && season == 3) return emptyList()
        val eps = base.season(ref, season)
        if (ref.tmdbId != SEVERANCE.tmdbId || season != 2) return eps
        val now = System.currentTimeMillis()
        return eps.map { e ->
            when {
                mode == Mode.Upcoming && e.number == 9 -> e.copy(upcoming = true, airsAt = now + 3 * 86_400_000L,
                    comingWords = "Coming " + com.dreamos.desktop.core.time.DreamTime.airLabel(now + 3 * 86_400_000L))
                mode == Mode.Upcoming && e.number == 10 -> e.copy(upcoming = true, airDate = null, stillUrl = null, name = "Episode 10", comingWords = TitleWords.TBA)
                e.number < 4 -> e.copy(watched = true)
                e.number == 4 -> e.copy(progress = 0.38f)
                e.number == 10 -> e.copy(pill = PillSpec("Available Now", PillKind.Green))
                else -> e
            }
        }
    }

    override suspend fun sources(ref: TitleRef, season: Int?, episode: Int?, loose: Boolean): List<SourceOption> = listOf(
        SourceOption("a", "4K  ·  18.4 GB", "Dolby Vision  ·  HEVC  ·  Atmos", "Severance S02E04 2160p ATVP WEB-DL DDP5 1 Atmos DV HDR H 265", listOf(PillSpec("Ready", PillKind.Green))),
        SourceOption("b", "4K  ·  9.1 GB", "HDR10+  ·  HEVC  ·  5.1", "Severance S02E04 Woes Hollow 2160p WEB H265", listOf(PillSpec("Ready", PillKind.Green))),
        SourceOption("c", "1080p  ·  3.2 GB", "HEVC  ·  5.1  ·  42 Seeders", "Severance S02E04 1080p WEB H264"),
        SourceOption("d", "1080p  ·  1.6 GB", "5.1  ·  English Dub", "Severance S02E05 1080p ATVP WEB-DL DDP5 1 H 264", listOf(PillSpec("Other Episode", PillKind.Amber)), otherEpisode = true),
        SourceOption("e", "720p  ·  850 MB", "8 Seeders", "Severance S02E04 720p WEB x264"),
    )
}
