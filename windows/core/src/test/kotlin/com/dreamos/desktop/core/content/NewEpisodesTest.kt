package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.TestSupport
import com.dreamos.desktop.core.TestSupport.show
import com.dreamos.desktop.core.model.HistoryEntry
import com.dreamos.desktop.core.model.PlayRequest
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.net.NetResponse
import com.dreamos.desktop.core.time.DreamTime
import kotlin.test.*

/**
 * New Episodes leaves out what the viewer already watched (bug 2026-10-30: "when you finish a show it still shows up in
 * new episodes"). The data is Grey's Anatomy (TMDB 1416) as TMDB had it on 2026-10-30: Season 23 Episode 3 aired the
 * day before, Episode 4 a week later. Dates are relative to today so the test never goes stale.
 */
class NewEpisodesTest {
    private val greys = show(1416, "Grey's Anatomy")
    private fun day(offset: Int) = DreamTime.ymdPlus(offset)

    @BeforeTest fun setUp() {
        TestSupport.freshStore()
        Tmdb.setKey("test")
        WatchHistory.clearCache()
        Watched.profileChanged()
        NewEpisodes.clearCache()
        Net.fake = { _, url, _ ->
            when {
                "/tv/1416/season/23" in url -> NetResponse(200, """{"episodes":[
                    {"episode_number":1,"season_number":23,"name":"One","air_date":"${day(-15)}","runtime":44},
                    {"episode_number":2,"season_number":23,"name":"Two","air_date":"${day(-8)}","runtime":44},
                    {"episode_number":3,"season_number":23,"name":"It's All Wrong, but It's All Right","air_date":"${day(-1)}","runtime":44},
                    {"episode_number":4,"season_number":23,"name":"Four","air_date":"${day(6)}","runtime":44}]}""", emptyMap())
                "/tv/1416" in url -> NetResponse(200, """{"id":1416,"name":"Grey's Anatomy","first_air_date":"2005-03-27",
                    "last_episode_to_air":{"season_number":23,"episode_number":3,"name":"It's All Wrong, but It's All Right","air_date":"${day(-1)}"},
                    "next_episode_to_air":{"season_number":23,"episode_number":4,"name":"Four","air_date":"${day(6)}"},
                    "seasons":[{"season_number":22,"episode_count":18,"air_date":"2025-10-02"},{"season_number":23,"episode_count":4,"air_date":"${day(-15)}"}]}""",
                    emptyMap())
                else -> NetResponse(404, "", emptyMap())
            }
        }
    }

    @AfterTest fun tearDown() { Net.fake = null }

    private fun watch(e: Int, minutes: Long) = WatchHistory.report(PlayRequest(greys, season = 23, episode = e), minutes * 60_000L, 44 * 60_000L, 600_000, exact = true)
    private fun keys() = NewEpisodes.load().recent.map { it.key }

    @Test fun theLatestEpisodeLeavesNewEpisodesOnceItIsFinished() {
        watch(3, 20)
        assertEquals(listOf("1416:s23e3"), keys(), "half watched: still new to them")
        watch(3, 43)
        assertTrue(Watched.episode(1416, 23, 3))
        assertEquals(emptyList(), keys(), "caught up: no new episode until Episode 4 airs")
        assertTrue(Watched.badged.isEmpty(), "and no NEW EPISODE badge")
        assertEquals("1416:s23e4", NewEpisodes.load().upcoming.single().key, "Coming Up still shows the next one")
    }

    @Test fun anUpNextEpisodeIsStillNew() {
        watch(2, 43)
        assertTrue(WatchHistory.entry("tv:1416")!!.upNext)
        assertEquals(listOf("1416:s23e3"), keys())
    }

    @Test fun finishedElsewhereCountsToo() {
        // Trakt's history (another device) knows it; nothing played here.
        Watched.fromTrakt(emptyList(), mapOf(1416 to listOf(23 to 3)))
        assertTrue(NewEpisodes.caughtUp(1416, 23, 3))
        assertFalse(NewEpisodes.caughtUp(1416, 23, 4), "a newer episode is new again")
    }

    @Test fun theRule() {
        fun h(s: Int, e: Int, pos: Long = 0, upNext: Boolean = false, hidden: Boolean = false) =
            HistoryEntry(greys, s, e, positionMs = pos, durationMs = 100, upNext = upNext, hidden = hidden)
        // Finished: the episode itself or a later one (ledger), a binge drop only once its last episode is done.
        assertTrue(NewEpisodes.past(23 to 3, null, 23, 3))
        assertTrue(NewEpisodes.past(24 to 1, null, 23, 8))
        assertFalse(NewEpisodes.past(23 to 3, null, 23, 8), "Episodes 4 to 8 of the drop are still new")
        assertFalse(NewEpisodes.past(null, null, 23, 3), "never watched (followed from My List)")
        // The history: finished, removed from Continue Watching, or on a later episode.
        assertTrue(NewEpisodes.past(null, h(23, 3, pos = 95), 23, 3))
        assertTrue(NewEpisodes.past(null, h(23, 3, pos = 40, hidden = true), 23, 3))
        assertTrue(NewEpisodes.past(null, h(23, 4, upNext = true), 23, 3))
        assertTrue(NewEpisodes.past(null, h(23, 4, pos = 10), 23, 3))
        assertFalse(NewEpisodes.past(null, h(23, 3, upNext = true), 23, 3), "up next: not watched yet")
        assertFalse(NewEpisodes.past(null, h(23, 3, pos = 50), 23, 3), "in progress")
        assertFalse(NewEpisodes.past(null, h(23, 3, pos = 95), 23, 4), "finished the one before")
    }
}
