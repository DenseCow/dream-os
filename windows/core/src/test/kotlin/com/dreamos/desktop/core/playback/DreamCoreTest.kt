package com.dreamos.desktop.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A scraper engine tests drive: each start() hands out a job whose sources the test sets. */
class FakeEngine(var isActive: Boolean = true, val autoAnswer: (ScrapeQuery, ScrapeMode) -> List<ScrapedSource>? = { _, _ -> null }) : SourceEngine {
    inner class Job(val query: ScrapeQuery, val mode: ScrapeMode) : ScrapeJob {
        override val state = MutableStateFlow(ScrapeState())
        var promoted = false
        fun add(vararg s: ScrapedSource) { state.value = state.value.let { it.copy(sources = it.sources + s, version = it.version + 1, answered = it.answered + 1) } }
        fun finish(answered: Boolean = true) { state.value = state.value.let { it.copy(done = true, version = it.version + 1, answered = if (answered) maxOf(1, it.answered) else 0) } }
        override fun cancel() { state.value = state.value.copy(done = true, cancelled = true, version = state.value.version + 1) }
        override fun promote() { promoted = true }
    }
    val jobs = CopyOnWriteArrayList<Job>()
    override fun active() = isActive
    override fun start(query: ScrapeQuery, mode: ScrapeMode): ScrapeJob = Job(query, mode).also { j ->
        jobs += j
        autoAnswer(query, mode)?.let { j.add(*it.toTypedArray()); j.finish() }
    }
}

class DreamCoreTest {
    private val GB = 1L shl 30
    private val movie = PlayTarget(MediaKind.MOVIE, 603, "The Matrix", 1999, imdb = "tt0133093")

    private fun hash(c: Char) = c.toString().repeat(40)
    private fun item(c: Char, name: String, q: String = "1080p") = ScrapedSource(hash(c), name, q, 2 * GB, 50, "m:torrentio")

    private class Rig(val core: DreamCore, val http: FakeHttp, val engine: FakeEngine, val playback: Playback)

    private fun rig(engine: FakeEngine, http: FakeHttp, vararg linked: Pair<String, String>, viewer: Map<String, String> = emptyMap()): Rig {
        val store = MemoryStore()
        val secrets = MemorySecrets().apply { linked.forEach { put(it.first, it.second) } }
        val hooks = object : PlaybackHooks {
            override fun audioLang() = "en"
            override fun viewerSourceSettings() = viewer // never this PC's own settings
        }
        val p = Playback.create(engine, hooks, store, secrets, object : ScreenFacts {},
            object : ReleaseInfo {
                override fun today(): LocalDate = LocalDate.of(2026, 10, 30)
                override fun dateWords(date: String) = date
                override fun airWords(at: Long) = "$at"
                override fun cardDate(at: Long) = "Nov 1" to "Nov 1"
            }, http = http, clock = Clock.SYSTEM, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
        return Rig(p.core, http, engine, p)
    }

    private fun premiumize(http: FakeHttp, cached: (String) -> Boolean) = http
        .on("cache/check") { s ->
            val hs = Regex("items%5B%5D=([a-z0-9]+)").findAll(s.body).map { it.groupValues[1] }.toList()
            HttpReply(200, """{"status":"success","response":[${hs.joinToString(",") { cached(it).toString() }}]}""")
        }
        .on("transfer/directdl") { s ->
            val h = Regex("btih%3A([a-z0-9]+)").find(s.body)!!.groupValues[1]
            HttpReply(200, """{"status":"success","content":[{"path":"Movie/$h.mkv","size":2000,"link":"https://pm.example/$h.mkv"}]}""")
        }

    @Test fun playRanksRacesAndKeepsAlternates() = runBlocking<Unit> {
        val engine = FakeEngine { _, _ -> listOf(item('a', "The.Matrix.1999.1080p.BluRay.x264-GRP"), item('b', "The.Matrix.1999.1080p.WEB-DL"),
            item('c', "The.Matrix.1999.HDCAM")) }
        val r = rig(engine, premiumize(FakeHttp()) { true }, "premiumizetoken" to "tok")
        val req = r.playback.play(movie)
        assertEquals(hash('a'), req.stream.hash)
        assertEquals(DebridServiceId.PREMIUMIZE, req.stream.service)
        assertNull(req.stream.note)
        assertEquals(listOf(hash('b')), req.alternates.map { it.hash }) // the CAM never races
        // The link failed: the alternate plays next, no network.
        r.playback.failed(movie, req.stream)
        val sent = r.http.sent.size
        assertEquals(hash('b'), r.playback.nextStream(movie)?.hash)
        assertEquals(sent, r.http.sent.size)
        assertNull(r.playback.nextStream(movie))
    }

    @Test fun aPlayJoinsTheLookAheadsScrapeAndRelaxesToCamWithANote() = runBlocking<Unit> {
        val engine = FakeEngine()
        val r = rig(engine, premiumize(FakeHttp()) { true }, "premiumizetoken" to "tok")
        r.playback.titleOpened(movie)
        delay(150)
        assertEquals(1, engine.jobs.size)
        val play = async { r.playback.play(movie) }
        delay(150)
        engine.jobs[0].add(item('c', "The.Matrix.1999.HDTS.1080p"))
        engine.jobs[0].finish()
        val req = play.await()
        assertEquals(1, engine.jobs.size) // one scrape for the page and the Play
        assertTrue(engine.jobs[0].promoted)
        assertEquals(Playback.CAM_NOTE, req.stream.note)
        assertTrue(req.stream.cam)
    }

    @Test fun uploadingServicesWaitWhileAFreeCheckAnswers() = runBlocking<Unit> {
        val engine = FakeEngine { _, _ -> listOf(item('a', "The.Matrix.1999.1080p.BluRay")) }
        val http = premiumize(FakeHttp()) { true }
        val r = rig(engine, http, "premiumizetoken" to "tok", "alldebridtoken" to "adtok")
        val req = r.playback.play(movie)
        assertEquals(DebridServiceId.PREMIUMIZE, req.stream.service)
        delay(700)
        assertEquals(0, http.count("magnet/upload"))
    }

    @Test fun missesBecomeTheRightWords() = runBlocking<Unit> {
        val silent = rig(FakeEngine { _, _ -> null }.also { e -> }, FakeHttp(), "premiumizetoken" to "tok")
        // No answer at all: the job ends unanswered.
        val job = async { runCatching { silent.playback.play(movie) }.exceptionOrNull() }
        delay(100)
        silent.engine.jobs.first().finish(answered = false)
        assertEquals(Playback.NO_ANSWER.title, (job.await() as PlaybackError).title)

        val nothing = rig(FakeEngine { _, _ -> emptyList() }, FakeHttp(), "premiumizetoken" to "tok")
        assertEquals(Playback.NOT_YET.title, (runCatching { nothing.playback.play(movie) }.exceptionOrNull() as PlaybackError).title)

        val noAccount = rig(FakeEngine { _, _ -> emptyList() }, FakeHttp())
        assertEquals(Playback.NO_ACCOUNT.title, (runCatching { noAccount.playback.play(movie) }.exceptionOrNull() as PlaybackError).title)
    }

    @Test fun sourceListFlagsOtherEpisodesAndPutsCamFirst() = runBlocking<Unit> {
        val ep = PlayTarget(MediaKind.EPISODE, 1396, "Breaking Bad", imdb = "tt0903747", season = 1, episode = 2)
        val engine = FakeEngine { _, _ -> listOf(item('a', "Breaking.Bad.S01E02.1080p.WEB"), item('b', "Breaking.Bad.S01E03.1080p.WEB"),
            item('c', "Breaking.Bad.S01E02.1080p.HDTS")) }
        val r = rig(engine, FakeHttp(), "premiumizetoken" to "tok")
        val rows = r.playback.sources(ep, loose = true)
        assertEquals(false, rows.first { it.source.hash == hash('a') }.otherEpisode)
        assertEquals(true, rows.first { it.source.hash == hash('b') }.otherEpisode)
        val cam = rows.first { it.source.hash == hash('c') }
        assertTrue(cam.cam && cam.badges.first() == "HDTS")
        // "S01E02.1080p" is episode 2, not episodes 2 to 108.
        assertEquals(SourceNames.Match.OK, SourceNames.match("Breaking.Bad.S01E02.1080p.WEB", ep))
    }

    @Test fun anEpisodeUpEarlyIsAvailableAndOneStillAheadSaysComing() = runBlocking<Unit> {
        val early = PlayTarget(MediaKind.EPISODE, 259909, "Dexter: Resurrection", imdb = "tt33043892", season = 2, episode = 1, airDate = "2026-11-01")
        val later = early.atEpisode(2, 2, airDate = "2026-11-08")
        val engine = FakeEngine { q, _ ->
            if (q.target.episode == 1) listOf(item('a', "Dexter Resurrection S02E01 1080p WEB H264-CAKES")) else emptyList()
        }
        val r = rig(engine, FakeHttp())
        val a = r.playback.availability
        assertEquals(AvailabilityStatus.AVAILABLE, a.check(early)?.status)
        assertEquals("Available Now", a.cardPill(early)?.label)
        assertTrue(a.outEarly(early.key))
        val b = a.check(later)
        assertEquals(AvailabilityStatus.NONE, b?.status)
        assertEquals("Coming Nov 1", a.cardPill(later)?.label)
    }

    @Test fun languagePillsFromReleaseNames() {
        val langs = LanguagePillsStore(MemoryStore(), FakeClock())
        langs.record("tv:1:1:1", listOf("Show S01E01 1080p WEB Japanese ENG SUB" to hash('a'), "Show S01E01 1080p JPN" to hash('b')), "en")
        val p = langs.pills("tv:1:1:1", "ja", "en")
        assertEquals("Japanese Audio Only", p.audio?.text)
        assertEquals(PillTone.WARN, p.audio?.tone)
        langs.record("tv:1:1:2", listOf("Show S01E02 1080p Dual Audio" to hash('c')), "en")
        assertEquals("English Dub Available", langs.pills("tv:1:1:2", "ja", "en").audio?.text)
        assertTrue(langs.pills("movie:9", "en", "en").isEmpty)
    }

    @Test fun searchAllSourcesSetsTheFiltersAsideForOneSearch() = runBlocking<Unit> {
        // The viewer hides HEVC; the only cached copy is HEVC.
        val engine = FakeEngine { _, _ -> listOf(item('a', "The.Matrix.1999.1080p.BluRay.x265.HEVC-GRP"), item('b', "The.Matrix.1999.1080p.WEB-DL")) }
        val r = rig(engine, premiumize(FakeHttp()) { it == hash('a') }, "premiumizetoken" to "tok")
        r.core.filters.put("hide.hevc", "true")
        assertTrue(r.playback.sources(movie).none { it.source.hash == hash('a') })
        val all = r.playback.sources(movie, loose = true)
        assertEquals(true, all.first { it.source.hash == hash('a') }.outsideFilters)
        assertEquals(hash('a'), r.playback.playLoose(movie).stream.hash)
    }

    @Test fun settingsSavedByThePagesReachTheRanking() = runBlocking<Unit> {
        val viewer = PagesSourceSettings.map { mapOf("quality.max" to "720p", "hide.hevc" to "true", "prefer.hdr" to "false", "subs.lang2" to "None")[it] }
        assertEquals(mapOf("quality.max" to "720p", "hide.hevc" to "true", "quality.hdr" to "false"), viewer)
        assertEquals("", PagesSourceSettings.map { if (it == "quality.max") "Automatic" else null }["quality.max"])
        val engine = FakeEngine { _, _ -> listOf(item('a', "The.Matrix.1999.1080p.BluRay.x264-GRP"), item('b', "The.Matrix.1999.720p.WEB-DL", "720p")) }
        val r = rig(engine, premiumize(FakeHttp()) { true }, "premiumizetoken" to "tok", viewer = viewer)
        assertEquals(hash('b'), r.playback.play(movie).stream.hash) // Maximum Quality 720p from Settings
        assertEquals("720p", r.core.filters.stored()["quality.max"])
    }

    @Test fun heardLanguagesAreNormalizedForThePills() {
        val r = rig(FakeEngine { _, _ -> emptyList() }, FakeHttp(), "premiumizetoken" to "tok")
        val show = PlayTarget(MediaKind.EPISODE, 1, "Show", season = 1, episode = 1, originalLanguage = "ja")
        r.playback.heardLanguages(show, hash('a'), setOf("jpn", "eng", "und"))
        assertTrue(r.core.langs.heardIn(hash('a'), "en"))
        assertEquals("English Audio Available", r.core.langs.pills(show.key, "ja", "en").audio?.text)
    }

    @Test fun firstFrameLearnsAndTimesTheService() = runBlocking<Unit> {
        val engine = FakeEngine { _, _ -> listOf(item('a', "The.Matrix.1999.1080p.BluRay.x264-GRP")) }
        val r = rig(engine, premiumize(FakeHttp()) { true }, "premiumizetoken" to "tok")
        val req = r.playback.play(movie)
        delay(30)
        r.playback.firstFrame(movie, req.stream)
        assertNotNull(r.core.memory.snapshot())
    }
}
