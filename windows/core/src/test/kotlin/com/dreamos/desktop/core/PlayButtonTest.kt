package com.dreamos.desktop.core

import com.dreamos.desktop.core.TestSupport.movie
import com.dreamos.desktop.core.TestSupport.show
import com.dreamos.desktop.core.content.*
import com.dreamos.desktop.core.model.*
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.net.NetResponse
import kotlin.test.*

/**
 * The title page's Play button, the one rule on all three apps (STANDARDS): partway resumes that episode, a finished
 * one plays the next one out (a new season too), caught up with nothing newer plays S1 E1, never watched plays S1 E1.
 */
class PlayButtonTest {
    @BeforeTest fun setUp() {
        TestSupport.freshStore()
        Tmdb.setKey("test")
        WatchHistory.clearCache()
        // Show 300: season 1 has two aired episodes; season 2's first is due in 2099.
        Net.fake = { _, url, _ ->
            when {
                "/tv/300/season/1" in url -> NetResponse(200, """{"episodes":[
                    {"episode_number":1,"season_number":1,"name":"One","air_date":"2024-01-01","runtime":50},
                    {"episode_number":2,"season_number":1,"name":"Two","air_date":"2024-01-08","runtime":50}]}""", emptyMap())
                "/tv/300/season/2" in url -> NetResponse(200, """{"episodes":[
                    {"episode_number":1,"season_number":2,"name":"Back","air_date":"2099-03-01","runtime":50}]}""", emptyMap())
                "/tv/300" in url -> NetResponse(200, """{"id":300,"seasons":[{"season_number":1},{"season_number":2}]}""", emptyMap())
                else -> NetResponse(404, "", emptyMap())
            }
        }
    }

    @AfterTest fun tearDown() { Net.fake = null; WatchHistory.outEarly = null }

    private val drama = show(300, "Drama")
    private fun ep(s: Int, e: Int) = PlayRequest(drama, season = s, episode = e)
    private fun finish(s: Int, e: Int) = WatchHistory.report(ep(s, e), 49 * 60_000L, 50 * 60_000L, 600_000, exact = true)
    private fun target() = WatchHistory.nextFor(drama).let { it.season to it.episode }

    @Test fun neverWatchedPlaysTheFirstEpisode() {
        assertEquals("Play S1 E1", WatchHistory.playLabel(drama))
        assertEquals(1 to 1, target())
        assertEquals("Play", WatchHistory.playLabel(movie(31)))
    }

    @Test fun partwayResumesThatEpisode() {
        WatchHistory.report(ep(1, 2), 20 * 60_000L, 50 * 60_000L, 120_000, exact = true)
        assertEquals("Resume S1 E2", WatchHistory.playLabel(drama))
        assertEquals(1 to 2, target())
        assertEquals(20 * 60_000L, WatchHistory.startPositionMs(ep(1, 2)))
        WatchHistory.report(PlayRequest(movie(32)), 30 * 60_000L, 100 * 60_000L, 120_000, exact = true)
        assertEquals("Resume", WatchHistory.playLabel(movie(32)))
    }

    @Test fun aFinishedEpisodePlaysTheNextOneOut() {
        finish(1, 1)
        assertEquals("Play S1 E2", WatchHistory.playLabel(drama))
        assertEquals(1 to 2, target())
        assertEquals(0L, WatchHistory.startPositionMs(ep(1, 2)))
    }

    @Test fun caughtUpWithNothingNewerPlaysTheFirstEpisodeThenJumpsToTheNewSeasonOnceItIsOutEarly() {
        finish(1, 2)
        assertTrue(WatchHistory.entries().none { it.key == drama.key }, "nothing after S1 E2 yet: the show left the row")
        assertEquals("Play S1 E1", WatchHistory.playLabel(drama))
        assertEquals(1 to 1, target())
        // S2 E1 is due in 2099, but a source already has it (out early): Play jumps ahead to it.
        WatchHistory.outEarly = { id, s, e -> id == 300 && s == 2 && e == 1 }
        assertEquals("Play S2 E1", WatchHistory.playLabel(drama))
        assertEquals(2 to 1, target())
        assertEquals(0L, WatchHistory.startPositionMs(ep(2, 1)))
        // A finished movie plays again from the start.
        WatchHistory.report(PlayRequest(movie(33)), 95, 100, 200_000, exact = true)
        assertEquals("Play", WatchHistory.playLabel(movie(33)))
        assertEquals(0L, WatchHistory.startPositionMs(PlayRequest(movie(33))))
    }

    @Test fun aShowTakenOffContinueWatchingStartsFresh() {
        WatchHistory.report(ep(1, 2), 20 * 60_000L, 50 * 60_000L, 120_000, exact = true)
        WatchHistory.remove(drama.key)
        assertEquals("Play S1 E1", WatchHistory.playLabel(drama))
        assertEquals(1 to 1, target(), "the button's words and what it plays agree")
        assertNull(WatchHistory.caughtUpNext(drama.key), "removed is not caught up")
    }
}
