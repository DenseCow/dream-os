package com.dreamos.desktop.core.trailer

import com.dreamos.desktop.core.trailer.TrailerPick.Candidate
import com.dreamos.desktop.core.trailer.TrailerPick.Codec
import com.dreamos.desktop.core.trailer.TrailerPick.Rep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrailerPickTest {
    private fun v(key: String, type: String = "Trailer", official: Boolean = true, lang: String? = "en", size: Int = 1080,
                  published: String = "2024-01-01", name: String = key) =
        TrailerEngine.Video("YouTube", key, type, official, lang ?: "null", size, published, name)

    @Test fun officialTrailerInViewerLanguageFirst() {
        val rows = listOf(
            v("teaser", type = "Teaser"),
            v("clip", type = "Clip"),
            v("fan", official = false),
            v("en"),
            v("de", lang = "de"),
            v("feat", type = "Featurette"),
        )
        assertEquals(listOf("de", "en", "fan", "teaser", "clip", "feat"), TrailerEngine.parseVideos(rows, "de").map { it.key })
        assertEquals(listOf("en", "fan", "de", "teaser", "clip", "feat"), TrailerEngine.parseVideos(rows, "en").map { it.key })
    }

    @Test fun redBandAfterTheOthersOfItsTierThenNewestAndSharpest() {
        val rows = listOf(
            v("old", published = "2023-05-01"),
            v("red", name = "Official Red Band Trailer", published = "2024-06-01"),
            v("new", published = "2024-03-01"),
            v("sd", published = "2024-04-01", size = 720),
        )
        assertEquals(listOf("new", "old", "sd", "red"), TrailerEngine.parseVideos(rows, "en").map { it.key })
    }

    @Test fun onlyTheResolvableHostAndRowsWithKeys() {
        val rows = listOf(
            TrailerEngine.Video("Vimeo", "vim", "Trailer", true, "en", 1080, "2024", "v"),
            TrailerEngine.Video("YouTube", "", "Trailer", true, "en", 1080, "2024", "empty"),
            v("ok"),
            v("ok"),
        )
        assertEquals(listOf("ok"), TrailerEngine.parseVideos(rows, "en").map { it.key })
    }

    @Test fun qualityClassOfLetterboxedAndOddSizes() {
        assertEquals(1080, TrailerPick.pclass(1920, 800))
        assertEquals(2160, TrailerPick.pclass(3840, 1608))
        assertEquals(1080, TrailerPick.pclass(1920, 1080))
        assertEquals(720, TrailerPick.pclass(1280, 534))
        assertEquals(1440, TrailerPick.pclass(2560, 1440))
    }

    private fun rep(cls: Int, codec: Codec, fps: Int = 24, br: Int = 1000, itag: Int = cls + codec.ordinal) =
        Rep(itag, "https://v/$itag", "video/webm", codec, br, cls * 16 / 9, cls, fps, cls)

    @Test fun oneVideoPerClassVp9FirstNo8k() {
        val picked = TrailerPick.videoReps(listOf(
            rep(4320, Codec.VP9), rep(2160, Codec.VP9), rep(1080, Codec.AVC, br = 3500), rep(1080, Codec.VP9, br = 1700),
            rep(720, Codec.AVC), rep(480, Codec.AVC), rep(360, Codec.AVC), rep(240, Codec.VP9),
        ))
        assertEquals(listOf(2160, 1080, 720, 480), picked.map { it.cls })
        assertEquals(Codec.VP9, picked[1].codec)
    }

    @Test fun thirtyFpsFirstWhenOffered() {
        val picked = TrailerPick.videoReps(listOf(rep(1080, Codec.VP9, fps = 60, br = 4000, itag = 1), rep(1080, Codec.VP9, fps = 24, br = 2000, itag = 2)))
        assertEquals(2, picked.single().itag)
    }

    @Test fun lowClassesOnlyWhenThatIsAll() {
        assertEquals(listOf(360, 240), TrailerPick.videoReps(listOf(rep(360, Codec.AVC), rep(240, Codec.AVC))).map { it.cls })
    }

    @Test fun videoUpToCapsAndFallsToLowest() {
        val s = TrailerPick.Streams(TrailerPick.videoReps(listOf(rep(2160, Codec.VP9), rep(1080, Codec.VP9), rep(720, Codec.VP9))),
            emptyList(), null, null, 120_000)
        assertEquals(2160, s.videoUpTo(2160)!!.cls)
        assertEquals(1080, s.videoUpTo(1440)!!.cls)
        assertEquals(720, s.videoUpTo(480)!!.cls)
    }

    @Test fun opusBeforeAacAndHeAacLast() {
        val a = TrailerPick.audioReps(listOf(
            Rep(140, "a", "audio/mp4", Codec.AAC, 128), Rep(139, "b", "audio/mp4", Codec.AAC, 1),
            Rep(251, "c", "audio/webm", Codec.OPUS, 160), Rep(249, "d", "audio/webm", Codec.OPUS, 50),
        ))
        assertEquals(listOf(251, 249, 140, 139), a.map { it.itag })
    }

    @Test fun codecsThePcPlays() {
        assertEquals(Codec.VP9, TrailerPick.videoCodec("vp9"))
        assertEquals(Codec.VP9, TrailerPick.videoCodec("vp09.00.40.08"))
        assertEquals(Codec.AVC, TrailerPick.videoCodec("avc1.640028"))
        assertNull(TrailerPick.videoCodec("vp09.02.51.10.01.09.16.09.00")) // HDR
        assertNull(TrailerPick.videoCodec("av01.0.08M.08"))
    }

    @Test fun linkFacts() {
        val u = "https://rr1.example/videoplayback?expire=1793408597&ei=x&itag=137&clen=33199068&dur=159.534"
        assertEquals(33199068L, TrailerPick.clen(u))
        assertEquals(1793408597L, TrailerPick.expiry(u))
        assertEquals(1793408598L, TrailerPick.expiry("https://m.example/api/manifest/hls_variant/expire/1793408598/ei/9"))
        assertEquals(0L, TrailerPick.clen("https://x/y"))
    }

    @Test fun hlsMasterCutToSdrClassesThePcPlays() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:URI="https://a/audio",TYPE=AUDIO,GROUP-ID="234",NAME="Default"
            #EXT-X-STREAM-INF:BANDWIDTH=3836331,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1920x1080,VIDEO-RANGE=SDR,AUDIO="234"
            https://v/1080avc
            #EXT-X-STREAM-INF:BANDWIDTH=18355820,CODECS="vp09.00.50.08,mp4a.40.2",RESOLUTION=3840x2160,VIDEO-RANGE=SDR,AUDIO="234"
            https://v/2160vp9
            #EXT-X-STREAM-INF:BANDWIDTH=9000000,CODECS="av01.0.12M.08,mp4a.40.2",RESOLUTION=2560x1440,VIDEO-RANGE=SDR,AUDIO="234"
            https://v/1440av1
            #EXT-X-STREAM-INF:BANDWIDTH=20000000,CODECS="vp09.02.51.10,mp4a.40.2",RESOLUTION=3840x2160,VIDEO-RANGE=PQ,AUDIO="234"
            https://v/2160hdr
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=1,URI="https://v/iframes"
        """.trimIndent()
        val cut4k = assertNotNull(TrailerPick.filterHlsMaster(master, 2160))
        assertTrue("https://v/2160vp9" in cut4k && "https://v/1080avc" in cut4k && "https://a/audio" in cut4k)
        assertFalse("1440av1" in cut4k || "2160hdr" in cut4k || "iframes" in cut4k)
        val cut1080 = assertNotNull(TrailerPick.filterHlsMaster(master, 1080))
        assertFalse("2160vp9" in cut1080)
        assertNull(TrailerPick.filterHlsMaster(master, 480))
    }

    @Test fun edlJoinsPictureAndSoundWithLengthPrefixes() {
        val e = Trailer.edl("http://127.0.0.1:5/t/a", "http://127.0.0.1:5/t/bb")
        assertEquals("edl://!new_stream;!no_clip;!no_chapters;%22%http://127.0.0.1:5/t/a;!new_stream;!no_clip;!no_chapters;%23%http://127.0.0.1:5/t/bb", e)
    }

    private fun cand(k: String) = Candidate(k, "Trailer", true, "en", 1080, "", "")

    @Test fun failedVideosAreSkippedUntilRetryAndHardFailsStay() {
        val fails = HashMap<String, TrailerPick.Fail>()
        val now = 1_000_000L
        fails["soft"] = TrailerPick.Fail(now + 1000, false, "x")
        fails["hard"] = TrailerPick.Fail(now + 1000, true, "age")
        val tried = ArrayList<String>()
        val r = TrailerPick.firstPlayable(listOf(cand("soft"), cand("hard"), cand("bad"), cand("good")), fails, now, false, 6, {}) { c ->
            tried += c.key
            (if (c.key == "good") "ok" else null) to "why"
        }
        assertEquals(listOf("bad", "good"), tried)
        assertEquals("good", r!!.first.key)
        assertTrue("bad" in fails && "good" !in fails)

        tried.clear()
        val again = TrailerPick.firstPlayable(listOf(cand("soft"), cand("hard")), fails, now, true, 6, {}) { c -> tried += c.key; "ok" to "" }
        assertEquals("soft", again!!.first.key)
        assertEquals(listOf("soft"), tried)
    }

    @Test fun aNetworkErrorStopsTheRound() {
        val tried = ArrayList<String>()
        val r = TrailerPick.firstPlayable<String>(listOf(cand("a"), cand("b")), HashMap(), 0, false, 6, {}) { c ->
            tried += c.key
            throw java.io.IOException("offline")
        }
        assertNull(r)
        assertEquals(listOf("a"), tried)
    }
}
