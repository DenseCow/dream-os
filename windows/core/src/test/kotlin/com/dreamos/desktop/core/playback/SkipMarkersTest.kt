package com.dreamos.desktop.core.playback

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkipMarkersTest {
    private val episode = PlayTarget(MediaKind.EPISODE, 1396, "Breaking Bad", season = 2, episode = 3)
    private val movie = PlayTarget(MediaKind.MOVIE, 603, "The Matrix", year = 1999)
    private val min = 60_000L

    @Test fun theIntroDbSegmentsBecomeMarks() {
        val o = Json.parseToJsonElement("""{"intro":[{"start_ms":null,"end_ms":52000}],"recap":[{"start_ms":52000,"end_ms":100000}],
            "credits":[{"start_ms":60000,"end_ms":90000},{"start_ms":2580000,"end_ms":null}],"preview":[{"start_ms":2650000,"end_ms":null}]}""")
        val m = SkipSources.parseTheIntroDb(o, 45 * min)
        assertEquals(0L, m.introStartMs)
        assertEquals(52_000L, m.introEndMs)
        assertEquals(100_000L, m.recapEndMs)
        // Credits over an opening (0:60) don't count: the latest start in the second half does.
        assertEquals(2_580_000L, m.creditsStartMs)
        assertEquals(2_650_000L, m.previewStartMs)
        val marks = m.marks(45 * min, creditsEstimated = false)
        assertEquals(listOf(SkipKind.INTRO, SkipKind.RECAP, SkipKind.CREDITS, SkipKind.PREVIEW), marks.map { it.kind })
        // A recap after the opening starts where the intro ends; credits run up to the preview.
        assertEquals(52_000L, marks[1].startMs)
        assertEquals(2_650_000L, marks[2].endMs)
    }

    @Test fun implausibleSegmentsAreDropped() {
        // An 8-minute "intro" and a recap ending at 20 min are someone's mistake.
        val o = Json.parseToJsonElement("""{"intro":[{"start_ms":0,"end_ms":480000}],"recap":[{"start_ms":1000000,"end_ms":1200000}],"credits":[]}""")
        val m = SkipSources.parseTheIntroDb(o, 45 * min)
        assertFalse(m.hasAny)
        // Only a preview: it stands in for the credits too.
        val p = SkipSources.parseTheIntroDb(Json.parseToJsonElement("""{"preview":[{"start_ms":2600000,"end_ms":null}]}"""), 45 * min)
        assertEquals(2_600_000L, p.creditsStartMs)
        assertEquals(listOf(SkipKind.CREDITS), p.marks(45 * min, false).map { it.kind })
    }

    @Test fun aniSkipAndChapterNames() {
        val a = SkipSources.parseAniSkip(Json.parseToJsonElement("""{"found":true,"results":[
            {"interval":{"startTime":90.5,"endTime":180.5},"skipType":"op"},{"interval":{"startTime":1300.0,"endTime":1390.0},"skipType":"ed"}]}"""), 24 * min)
        assertNotNull(a)
        assertEquals(90_500L, a.introStartMs)
        assertEquals(1_300_000L, a.creditsStartMs)
        assertNull(SkipSources.parseAniSkip(Json.parseToJsonElement("""{"found":false,"results":[]}"""), 24 * min))
        assertTrue(SkipSources.aniSkipUrl(1, 2, 0).contains("types%5B%5D=op")) // java.net.URI refuses raw brackets

        assertEquals(SkipKind.INTRO, SkipSources.classify("Opening Credits"))
        assertEquals(SkipKind.INTRO, SkipSources.classify("OP 2"))
        assertEquals(SkipKind.RECAP, SkipSources.classify("Previously on..."))
        assertEquals(SkipKind.CREDITS, SkipSources.classify("End Credits"))
        assertEquals(SkipKind.PREVIEW, SkipSources.classify("Next Episode Preview"))
        assertNull(SkipSources.classify("Chapter 3"))
        val ch = SkipSources.fromChapters(listOf(SkipChapter(0, null, "Prologue"), SkipChapter(70_000, null, "Opening"),
            SkipChapter(160_000, null, "Part A"), SkipChapter(1_310_000, null, "Ending"), SkipChapter(1_400_000, null, "Preview")), 24 * min)
        assertNotNull(ch)
        assertEquals(70_000L to 160_000L, ch.introStartMs!! to ch.introEndMs!!)
        assertEquals(1_310_000L, ch.creditsStartMs)
        assertEquals(1_400_000L, ch.previewStartMs)
    }

    @Test fun malMappingWalksCourSplits() = runBlocking {
        val one = listOf(SkipSources.AnimeEntry(21, 12, null))
        assertEquals(21 to 1100, SkipSources.mapToMal(one, 20, 5, 1100) { emptyMap() }) // One Piece: absolute numbering
        // TMDB season 3 is two MAL entries (cours of 12 and 10): episode 15 is the second's 3rd.
        val split = listOf(SkipSources.AnimeEntry(100, 1, 3), SkipSources.AnimeEntry(101, 2, 3))
        val r = SkipSources.mapToMal(split, 3, 15, 15 + 37) { mapOf(1 to ("2018-07-23" to 12), 2 to ("2019-04-29" to 10)) }
        assertNull(r) // the absolute count says episode 52 of the two; walked in air order there is no such episode
        val r2 = SkipSources.mapToMal(split, 3, 15, 15) { mapOf(1 to ("2018-07-23" to 12), 2 to ("2019-04-29" to 10)) }
        assertEquals(101 to 3, r2)
    }

    @Test fun answersAreCachedAndConcurrentAsksShareOneLookup() = runBlocking {
        val http = FakeHttp().on("theintrodb") { HttpReply(200, """{"intro":[{"start_ms":5000,"end_ms":60000}],"credits":[{"start_ms":2500000}]}""") }
        val store = MemoryStore()
        val clock = FakeClock()
        val s = SkipMarkers(http, store, clock)
        val all = (1..4).map { async { s.info(episode, 44 * min) } }.awaitAll()
        assertTrue(all.all { it.of(SkipKind.INTRO)?.endMs == 60_000L })
        assertEquals(1, http.count("theintrodb"))
        assertTrue(http.sent.first().url.contains("tmdb_id=1396&season=2&episode=3&duration_ms=2640000"))
        // A different cut of the same episode (2 min shorter) is the same answer; the cache answers.
        s.info(episode, 44 * min + 30_000)
        assertEquals(1, http.count("theintrodb"))
        // A restart reads the kept answers.
        delay(2_600)
        val again = SkipMarkers(http, store, clock)
        assertEquals(60_000L, again.info(episode, 44 * min).of(SkipKind.INTRO)?.endMs)
        assertEquals(1, http.count("theintrodb"))
    }

    @Test fun missesAreKeptButErrorsAndRateLimitsAreNot() = runBlocking {
        var code = 500
        val http = FakeHttp().on("theintrodb") { HttpReply(code, "", if (code == 429) mapOf("retry-after" to "30") else emptyMap()) }
        val clock = FakeClock()
        val s = SkipMarkers(http, MemoryStore(), clock)
        val i = s.info(movie, 120 * min)
        // Nothing known: only the credits estimate (the last 8 % of a movie), marked as a guess.
        assertTrue(i.creditsEstimated)
        assertEquals(listOf(SkipKind.CREDITS), i.marks.map { it.kind })
        assertTrue(i.marks[0].estimated)
        s.info(movie, 120 * min)
        assertEquals(2, http.count("theintrodb")) // an error isn't kept
        code = 429
        s.info(movie, 120 * min)
        s.info(movie, 120 * min)
        assertEquals(3, http.count("theintrodb")) // backing off for 30 s
        clock.advance(31_000)
        code = 404
        s.info(movie, 120 * min)
        s.info(movie, 120 * min)
        assertEquals(4, http.count("theintrodb")) // a 404 is a miss, kept for two days
    }

    @Test fun animeGoesThroughArmAndAniSkip() = runBlocking {
        val http = FakeHttp()
            .json("theintrodb", """{"intro":[],"credits":[]}""")
            .json("arm.haglund.dev", """[{"media":"TV","myanimelist":38524,"kitsu":41982,"themoviedb-season":3}]""")
            .json("aniskip", """{"found":true,"results":[{"interval":{"startTime":10,"endTime":99},"skipType":"op"}]}""")
        val aot = PlayTarget(MediaKind.EPISODE, 1429, "Attack on Titan", season = 3, episode = 15, originalLanguage = "ja", isAnime = true)
        val s = SkipMarkers(http, MemoryStore(), FakeClock()) { ShowFacts(true, "ja", mapOf(1 to 25, 2 to 12, 3 to 22)) }
        val i = s.info(aot, 24 * min)
        assertEquals(99_000L, i.of(SkipKind.INTRO)?.endMs)
        assertTrue(http.sent.any { it.url.contains("skip-times/38524/15") })
        // Not Japanese animation: no anime services asked.
        val http2 = FakeHttp().json("theintrodb", """{"intro":[]}""")
        SkipMarkers(http2, MemoryStore(), FakeClock()) { ShowFacts(false, "en", emptyMap()) }.info(episode, 45 * min)
        assertEquals(0, http2.count("arm.haglund.dev"))
    }

    @Test fun theFilesChaptersWinOverTheNetwork() = runBlocking {
        val http = FakeHttp().json("theintrodb", """{"intro":[{"start_ms":0,"end_ms":30000}],"credits":[{"start_ms":2500000}]}""")
        val s = SkipMarkers(http, MemoryStore(), FakeClock())
        val i = s.info(episode, 45 * min, listOf(SkipChapter(0, null, "Cold Open"), SkipChapter(120_000, null, "Intro"), SkipChapter(170_000, null, "Episode")))
        assertEquals(120_000L to 170_000L, i.of(SkipKind.INTRO)!!.startMs to i.of(SkipKind.INTRO)!!.endMs)
        assertEquals(2_500_000L, i.of(SkipKind.CREDITS)?.startMs) // the gap filled from TheIntroDB
    }

    @Test fun introsAreLearnedFromTheViewersJumps() = runBlocking {
        val http = FakeHttp().json("theintrodb", "", 404)
        val s = SkipMarkers(http, MemoryStore(), FakeClock())
        val i = s.info(episode, 45 * min)
        assertNull(i.of(SkipKind.INTRO))
        val learner = SkipLearner(episode, i) { from, to -> s.learn(episode, from, to) }
        var now = 0L
        var pos = 0L
        fun play(ms: Long) { var left = ms; while (left > 0) { now += 500; pos += 500; left -= 500; learner.tick(pos, now) } }
        play(95_000)
        pos += 30_000; now += 500; learner.tick(pos, now) // two quick jumps: one skip
        play(1_000)
        pos += 40_000; now += 500; learner.tick(pos, now)
        play(10_000)
        learner.end()
        val next = s.info(episode.atEpisode(2, 4), 45 * min)
        val intro = next.of(SkipKind.INTRO)
        assertNotNull(intro)
        assertTrue(next.introLearned)
        assertEquals(95_000L - SkipMarkers.LEARN_LEAD_MS, intro.startMs)
        assertEquals(166_000L, intro.endMs)
        // A movie, or a jump back, teaches nothing.
        val m = SkipLearner(movie, SkipInfo.NONE) { _, _ -> error("movies don't learn") }
        m.tick(60_000, 0); m.tick(120_000, 500); m.end()
    }

    @Test fun ownSkipsAreNotLearned() {
        var learned = false
        val l = SkipLearner(episode, SkipInfo.NONE) { _, _ -> learned = true }
        l.tick(10_000, 0)
        l.selfSeek(70_000, 100)
        l.tick(70_000, 500)
        l.tick(80_000, 10_000)
        l.end()
        assertFalse(learned)
    }
}
