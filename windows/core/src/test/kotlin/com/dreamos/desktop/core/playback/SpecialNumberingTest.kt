package com.dreamos.desktop.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Release-name matching for a special, which is one of the three places season 0 reaches (STANDARDS §5). Cinemeta and
 * the torrent index number a special S00E{n}; scene and P2P releases put the same file in the parent season as
 * "S01E00 <special title>". A lookup asks for both and accepts either, so the file inside a torrent is found whichever
 * way the release names it, and nothing else is.
 *
 * The forms come from the catalog ([com.dreamos.desktop.core.content.Specials.formsFor]); here they are given directly
 * so the matching is tested without any network.
 */
class SpecialNumberingTest {
    /** A special asked for as S00E01, which release names also write as S01E00, in a show whose season 1 has 8 parts. */
    private val special = src(season = 0, episode = 1, alt = listOf(EpisodeForm(1, 0)))
    private val ordinary = src(season = 1, episode = 1)

    private fun src(season: Int?, episode: Int?, alt: List<EpisodeForm> = emptyList()) =
        Source("h", "A Show Pack", season = season, episode = episode, altForms = alt)

    @Test fun aSpecialMatchesTheIndexForm() {
        assertTrue(DebridFiles.names("A.Show.S00E01.A.Special.1080p.WEB.h264.mkv", special))
        assertTrue(DebridFiles.names("A Show S00E1 1080p.mkv", special), "one digit")
        assertTrue(DebridFiles.names("A.Show.0x01.1080p.mkv", special), "the loose form")
    }

    @Test fun aSpecialMatchesTheParentSeasonForm() {
        assertTrue(DebridFiles.names("A.Show.S01E00.A.Special.1080p.WEB.h264.mkv", special))
        assertTrue(DebridFiles.names("A Show 1x00 1080p.mkv", special), "the loose form")
    }

    @Test fun aSpecialMatchesNothingElseOfTheShow() {
        assertFalse(DebridFiles.names("A.Show.S01E01.1080p.mkv", special), "the first episode of season 1")
        assertFalse(DebridFiles.names("A.Show.S01E08.1080p.mkv", special), "the season finale")
        assertFalse(DebridFiles.names("A.Show.S01E10.1080p.mkv", special), "a double-digit episode")
        assertFalse(DebridFiles.names("A.Show.S00E10.1080p.mkv", special), "a double-digit special")
        assertFalse(DebridFiles.names("A.Show.S02E00.1080p.mkv", special), "another season's special")
        assertFalse(DebridFiles.names("A.Show.S02E01.1080p.mkv", special), "another season's first episode")
        assertFalse(DebridFiles.names("A.Show.Season.01.COMPLETE.1080p.mkv", special), "a season pack with no episode named")
    }

    @Test fun anOrdinaryEpisodeIsUnaffected() {
        assertTrue(DebridFiles.names("A.Show.S01E01.1080p.mkv", ordinary))
        assertTrue(DebridFiles.names("A.Show.1x01.1080p.mkv", ordinary))
        assertFalse(DebridFiles.names("A.Show.S00E01.1080p.mkv", ordinary), "a special is not episode 1 of season 1")
        assertFalse(DebridFiles.names("A.Show.S01E00.1080p.mkv", ordinary))
        assertFalse(DebridFiles.names("A.Show.S01E11.1080p.mkv", ordinary))
    }

    @Test fun tmdbsOwnSeasonZeroNumberIsAcceptedToo() {
        // The catalog numbers by air order; TMDB sometimes numbers its season 0 differently, and releases follow TMDB.
        val s = src(season = 0, episode = 2, alt = listOf(EpisodeForm(3, 0), EpisodeForm(0, 7)))
        assertTrue(DebridFiles.names("A.Show.S00E02.1080p.mkv", s), "air order")
        assertTrue(DebridFiles.names("A.Show.S00E07.1080p.mkv", s), "TMDB's own number")
        assertTrue(DebridFiles.names("A.Show.S03E00.1080p.mkv", s), "the parent season")
        assertFalse(DebridFiles.names("A.Show.S00E03.1080p.mkv", s), "neither number")
    }

    @Test fun theFileInsideAPackIsPickedByEitherForm() {
        val gb = 1L shl 30
        val pack = listOf(
            "A.Show.S01E07.mkv" to 2 * gb,
            "A.Show.S01E00.A.Special.mkv" to 3 * gb,
            "A.Show.S01E00.sample.mkv" to 50L,
            "A.Show.S01E08.mkv" to 4 * gb,
        )
        assertEquals(1, DebridFiles.pick(pack, special), "the parent-season form, and not the sample")
        assertEquals(0, DebridFiles.pick(pack, src(season = 1, episode = 7)))
        val zero = listOf("A.Show.S00E01.mkv" to gb, "A.Show.S00E02.mkv" to 2 * gb)
        assertEquals(0, DebridFiles.pick(zero, special), "the index form")
        assertEquals(-1, DebridFiles.pick(zero, src(season = 1, episode = 3)), "no file for that episode")
    }

    @Test fun aSpecialsKeyAndLabelNeverNameASeason() {
        val t = PlayTarget(MediaKind.SPECIAL, 200875, "A Show", season = 1, episode = 1, specialId = "tmdb:9001",
            episodeTitle = "A Special")
        assertEquals("tv:200875/sp:tmdb:9001", t.key, "keyed on its identity, not its place")
        assertEquals("A Show A Special", t.label)
        assertTrue(t.isEpisode, "it plays like an episode")
        assertTrue(t.isSpecial)
        assertFalse("S0" in t.key || "S0" in t.label)
    }
}
