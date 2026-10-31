package com.dreamos.desktop.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RankingTest {
    private val GB = 1L shl 30
    private fun item(hash: String, name: String, q: String = "", size: Long = 2 * GB, seeders: Int = 10, provider: String = "m:torrentio", pack: Boolean = false) =
        ScrapedSource(hash, name, q, size, seeders, provider, pack)

    private fun values(vararg kv: Pair<String, String>, hdrScreen: Boolean = false) = SourceFilters.Values(mapOf(*kv), 0, hdrScreen)
    private fun parse(vararg items: ScrapedSource, season: Int? = null, episode: Int? = null) = items.mapNotNull { Ranking.parse(it, season, episode) }
    private fun rank(f: SourceFilters.Values, vararg items: ScrapedSource, ep: Boolean = false) =
        Ranking.choose(parse(*items), Ranking.Context(f, ep)).map { it.s.hash }

    @Test fun camHiddenByDefaultAndCamWordsCaught() {
        val r = rank(values(), item("a", "Movie.2026.HDTS.1080p"), item("b", "Movie 2026 HQ.Pre DVD 1080p"), item("c", "Movie.2026.1080p.WEB-DL.x264-GRP"))
        assertEquals(listOf("c"), r)
    }

    @Test fun telesyncLabelFromProviderCountsAsCam() {
        val p = Ranking.parse(item("a", "Movie.2026.1080p", q = "TS"), null, null)!!
        assertTrue(p.cam)
        // "Movie.ts" is a container, not a telesync.
        assertFalse(Ranking.parse(item("b", "Movie.2026.1080p.WEB.ts"), null, null)!!.cam)
    }

    @Test fun junkNeverParses() {
        assertNull(Ranking.parse(item("a", "Movie 2026 Recap 1080p"), null, null))
        assertNull(Ranking.parse(item("b", "Movie.2026.1080p.1XBET"), null, null))
    }

    @Test fun eightKNeverPicked() {
        val r = rank(values("quality.max" to "4k"), item("a", "Movie.2026.4320p.8K.WEB-DL"), item("b", "Movie.2026.2160p.WEB-DL"))
        assertEquals(listOf("b"), r)
        // Not even relaxed.
        val (kept, _) = Ranking.chooseRelaxed(parse(item("a", "Movie.2026.8K.WEB-DL")), Ranking.Context(values(), false), true)
        assertTrue(kept.isEmpty())
    }

    @Test fun noCapRanks1080pBefore4k() {
        val r = rank(values(), item("4k", "Movie.2026.2160p.WEB-DL"), item("fhd", "Movie.2026.1080p.WEB-DL"))
        assertEquals(listOf("fhd", "4k"), r)
        val capped = rank(values("quality.max" to "4k"), item("4k", "Movie.2026.2160p.WEB-DL"), item("fhd", "Movie.2026.1080p.WEB-DL"))
        assertEquals(listOf("4k", "fhd"), capped)
    }

    @Test fun maximumQualityDropsAboveItButPreferHdrLetsHdrInOnAnHdrScreen() {
        val items = arrayOf(item("4khdr", "Movie.2026.2160p.WEB-DL.HDR10.HEVC"), item("4ksdr", "Movie.2026.2160p.WEB-DL"), item("fhd", "Movie.2026.1080p.WEB-DL"))
        assertEquals(listOf("fhd"), rank(values("quality.max" to "1080p"), *items))
        assertEquals(listOf("4khdr", "fhd"), rank(values("quality.max" to "1080p", hdrScreen = true), *items))
        // Off by choice.
        assertEquals(listOf("fhd"), rank(values("quality.max" to "1080p", "quality.hdr" to "false", hdrScreen = true), *items))
    }

    @Test fun relaxLetsCamInOnlyWhenNothingElseAndMarksLevel() {
        val all = parse(item("cam", "Movie.2026.HDCAM.1080p"))
        val cx = Ranking.Context(values(), false)
        assertTrue(Ranking.chooseRelaxed(all, cx, relaxOk = false).first.isEmpty())
        val (kept, level) = Ranking.chooseRelaxed(all, cx, relaxOk = true)
        assertEquals(listOf("cam"), kept.map { it.s.hash })
        assertEquals(Ranking.RELAX_QUALITY, level)
    }

    @Test fun camAfterEveryRealReleaseWhenRelaxedAndHideCamOff() {
        val f = values("hide.cam" to "false")
        val r = rank(f, item("cam", "Movie.2026.HDTS.1080p", seeders = 999), item("sd", "Movie.2026.WEBRip.480p", size = GB / 2))
        // Hide CAM off: the 1080p CAM ranks by its grade; a CAM never counts as "something better" that hides SD.
        assertEquals(listOf("cam", "sd"), r)
    }

    @Test fun sizeLimitsRelaxBeforeQuality() {
        val f = values("size.movie.limit" to "range", "size.movie.max_gb" to "5")
        val all = parse(item("big", "Movie.2026.1080p.BluRay", size = 20 * GB))
        val (kept, level) = Ranking.chooseRelaxed(all, Ranking.Context(f, false), true)
        assertEquals(listOf("big"), kept.map { it.s.hash })
        assertEquals(Ranking.RELAX_SIZE, level)
    }

    @Test fun mkvBeforeMp4AndMp4LastWhenResuming() {
        val items = arrayOf(item("mp4", "Movie.2026.1080p.WEB-DL.mp4", seeders = 500), item("mkv", "Movie.2026.1080p.WEB-DL.mkv", seeders = 5))
        assertEquals(listOf("mkv", "mp4"), rank(values(), *items))
    }

    @Test fun collectionsAndHugeFilesSink() {
        val r = rank(values(), item("trilogy", "Movie Trilogy 1080p BluRay", seeders = 900), item("one", "Movie.2026.1080p.BluRay"))
        assertEquals(listOf("one", "trilogy"), r)
    }

    @Test fun dubFirstWithinTierNeverAboveBetterTier() {
        val anime = PlayTarget(MediaKind.EPISODE, 1, "Show", season = 1, episode = 1, originalLanguage = "ja", isAnime = true)
        val dub = Dubs.rank(anime, "en", true)
        assertEquals(Dubs.Rank.DUB, dub)
        val all = parse(item("sub", "[SubsPlease] Show - 01 (1080p)", seeders = 900), item("dub", "Show S01E01 1080p WEB Dual Audio", seeders = 3),
            item("dub720", "Show S01E01 720p English Dub"), season = 1, episode = 1)
        val r = Ranking.choose(all, Ranking.Context(values("quality.max" to "4k"), true, want = "en", dub = dub)).map { it.s.hash }
        assertEquals(listOf("dub", "sub", "dub720"), r)
    }

    @Test fun learnedFailuresGoAStepLater() {
        val mem = SourceMemory(MemoryStore(), FakeClock())
        repeat(2) { mem.record(listOf("grp:badgrp"), ok = false, ms = null) }
        val all = parse(item("bad", "Movie.2026.1080p.WEB-DL.x264-BADGRP", seeders = 900), item("good", "Movie.2026.1080p.WEB-DL.x264-GOOD", seeders = 1))
        val r = Ranking.choose(all, Ranking.Context(values(), false, memory = mem.snapshot())).map { it.s.hash }
        assertEquals(listOf("good", "bad"), r)
    }

    @Test fun surveyNamesBestRealAndBestCam() {
        val s = Ranking.survey(parse(item("a", "Movie.2026.2160p.WEB-DL.DV.HDR10"), item("b", "Movie.2026.HDTS.1080p")), values())
        assertEquals(2, s.total); assertEquals(1, s.quality); assertEquals(1, s.cam)
        assertEquals("4K WEB-DL DV", s.best)
        assertEquals("HDTS 1080p", s.bestCam)
    }

    @Test fun releaseLanguageAndDubAudio() {
        assertEquals("it", ReleaseLanguage.from("Movie.2026.iTALiAN.1080p"))
        assertEquals(Dubs.Audio.ORIGINAL, Dubs.audio("Show S01E01 1080p ENG SUB", "en"))
        assertEquals(Dubs.Audio.DUB, Dubs.audio("Show S01E01 1080p Kor-Eng", "en"))
        assertEquals("Dual Audio", Dubs.badge("Movie 2026 1080p Dual Audio", "en", anime = false))
    }
}
