package com.dreamos.desktop.core.playback

import com.dreamos.desktop.core.TestSupport
import com.dreamos.desktop.core.content.Details
import com.dreamos.desktop.core.content.Tmdb
import com.dreamos.desktop.core.content.WatchHistory
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.net.NetResponse
import com.dreamos.desktop.core.time.DreamTime
import com.dreamos.desktop.core.util.int
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * "A source decides, not the date" (owner, 2026-10-30) end to end on Dexter: Resurrection's shape: TMDB lists Season 2
 * Episode 1 for Sunday Nov 1 and Episode 2 for Nov 8; it's Friday Oct 30 and the index already has
 * `Dexter Resurrection S02E01 1080p WEB H264-CAKES` (Paramount+ posts it on Friday).
 */
class OutEarlyTest {
    private val show = 259909
    private val zone = ZoneId.of("America/New_York")
    private val now = ZonedDateTime.of(2026, 10, 30, 14, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val asked = java.util.concurrent.CopyOnWriteArrayList<Int>()

    private fun season1() = (1..10).joinToString(",") { n ->
        """{"episode_number":$n,"season_number":1,"name":"S1 Episode $n","air_date":"2025-0${7 + (n - 1) / 4}-${(10 + (n - 1) % 4 * 7)}","runtime":55}"""
    }

    @BeforeTest fun setUp() {
        TestSupport.freshStore()
        DreamTime.setZone(zone.id)
        DreamTime.fixedNow = now
        Tmdb.setKey("test")
        WatchHistory.clearCache()
        Net.fake = { _, url, _ ->
            when {
                "/tv/$show/season/1" in url -> NetResponse(200, """{"episodes":[${season1()}]}""", emptyMap())
                "/tv/$show/season/2" in url -> NetResponse(200, """{"episodes":[
                    {"episode_number":1,"season_number":2,"name":"Risky Business","air_date":"2026-11-01","runtime":55},
                    {"episode_number":2,"season_number":2,"name":"Episode 2","air_date":"2026-11-08"}]}""", emptyMap())
                "/tv/$show" in url -> NetResponse(200, """{"id":$show,"name":"Dexter: Resurrection","seasons":[
                    {"season_number":1,"episode_count":10,"air_date":"2025-07-11"},
                    {"season_number":2,"episode_count":2,"air_date":"2026-11-01"}]}""", emptyMap())
                else -> NetResponse(404, "", emptyMap()) // TVmaze: no exact times, the listed days decide "ahead"
            }
        }
    }

    @AfterTest fun tearDown() {
        Net.fake = null
        DreamTime.fixedNow = null
        WatchHistory.outEarly = null
        WatchHistory.earlyRelease = null
    }

    private fun playback(): Playback {
        val engine = FakeEngine { q, _ ->
            asked += q.target.episode ?: 0
            if (q.target.season == 2 && q.target.episode == 1) listOf(ScrapedSource("a".repeat(40), "Dexter Resurrection S02E01 1080p WEB H264-CAKES", "1080p", 2L shl 30, 80, "m:torrentio"))
            else emptyList()
        }
        val hooks = object : PlaybackHooks {
            override suspend fun imdb(target: PlayTarget) = "tt33043892"
            override fun audioLang() = "en"
            override fun viewerSourceSettings() = emptyMap<String, String>()
        }
        val words = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        return Playback.create(engine, hooks, MemoryStore(), MemorySecrets(), object : ScreenFacts {},
            object : ReleaseInfo {
                override fun today(): LocalDate = LocalDate.of(2026, 10, 30)
                override fun dateWords(date: String) = LocalDate.parse(date).format(words)
                override fun airWords(at: Long) = "$at"
                override fun cardDate(at: Long) = java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().format(words).let { it to it }
            }, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }

    @Test fun seasonTwoEpisodeOneIsOutEarlyAndPlaysWhileEpisodeTwoStaysComing() = runBlocking<Unit> {
        val p = playback()
        val a = p.availability

        // Before any lookup, the listed date alone: both still to come, the season isn't out.
        val before = Details.episodes(show, 2)
        assertTrue(before.all { it.upcoming })
        assertFalse(a.outEarly("tv:$show:2:1"))
        assertTrue(asked.isEmpty(), "the plain episode list never asks the index")

        // The title page opens Season 2: one quick check of Episode 1 finds the WEB copy.
        val eps = Details.episodes(show, 2, askEarly = true)
        with(eps[0]) {
            assertFalse(upcoming, "Episode 1 plays")
            assertTrue(outEarly)
            assertEquals("2026-11-01", airDate, "the listed date never changes")
        }
        with(eps[1]) {
            assertTrue(upcoming, "Episode 2 has no source: still Coming")
            assertFalse(outEarly)
            assertEquals("2026-11-08", airDate)
        }
        assertEquals(listOf(1, 2), asked, "Episode 1 up early, so Episode 2 got its one look too")

        // The season counts as out (the title page's rule: its first episode is up early).
        assertTrue(a.outEarly("tv:$show:2:1"))
        // Episode 1's card wears the normal pill; the title page's says the quality.
        val e1 = Playback.earlyTarget(show, 2, 1, "2026-11-01")
        assertEquals("Available Now", a.cardPill(e1)?.label)
        assertEquals("Available Now · 1080p", a.pagePill(e1)?.label)
        assertEquals("Coming Nov 8", a.cardPill(Playback.earlyTarget(show, 2, 2, "2026-11-08"))?.label)

        // Every later list (the player's episode list, Play's menu) knows it without asking again.
        val again = Details.episodes(show, 2)
        assertTrue(again[0].outEarly && !again[0].upcoming)
        assertTrue(again[1].upcoming)
        Details.episodes(show, 2, askEarly = true)
        assertEquals(listOf(1, 2), asked, "not asked again within the hour")

        // Up Next / Next Episode after the Season 1 finale is Season 2 Episode 1.
        val next = assertNotNull(WatchHistory.episodeAfter(show, 1, 10))
        assertEquals(2 to 1, next.first to next.second.int("episode_number"))
        assertEquals(null, WatchHistory.episodeAfter(show, 2, 1), "Episode 2 isn't up: no Next Episode yet")
    }

    @Test fun thePageAndItsSeasonTabAskingAtOnceShareOneCheck() = runBlocking<Unit> {
        val a = playback().availability
        val e1 = Playback.earlyTarget(show, 2, 1, "2026-11-01")
        val both = listOf(async(Dispatchers.IO) { a.earlyRelease(e1) }, async(Dispatchers.IO) { a.earlyRelease(e1) }).awaitAll()
        assertEquals(listOf(true, true), both)
        assertEquals(listOf(1), asked)
    }

    @Test fun nothingIsAskedWhileDreamPlayerPlays() = runBlocking<Unit> {
        val p = playback()
        p.availability.paused = true
        val eps = Details.episodes(show, 2, askEarly = true)
        assertTrue(eps.all { it.upcoming })
        assertTrue(asked.isEmpty())
    }
}
