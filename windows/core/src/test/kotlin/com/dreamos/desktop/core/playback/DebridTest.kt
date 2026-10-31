package com.dreamos.desktop.core.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DebridTest {
    private val GB = 1L shl 30

    @Test fun pickTheEpisodeInAPack() {
        val files = listOf("Show.S01E01.mkv" to 2 * GB, "Show.S01E02.mkv" to 2 * GB, "Show.S01E02.sample.mkv" to 50L, "Show.S01E03.mkv" to 3 * GB)
        assertEquals(1, DebridFiles.pick(files, src("h", "Show S01", season = 1, episode = 2)))
        assertEquals(3, DebridFiles.pick(files, src("h", "Show")))
        assertEquals(-1, DebridFiles.pick(listOf("Show.S01E01.rar" to GB), src("h", "Show", season = 1, episode = 1)))
    }

    @Test fun ledgerNeverClaimsTheViewersOwn() {
        val l = DebridLedger(MemoryStore(), FakeClock())
        l.setUsers("ad:x", setOf("mine"))
        assertEquals(setOf("new"), l.claim("ad:x", listOf("mine", "new")))
        l.seen("ad", mapOf("new" to true))
        assertEquals(true, l.cached("ad", "new"))
    }

    @Test fun uploadBudgetStopsLookAheadsButNeverPlay() {
        val l = DebridLedger(MemoryStore(), FakeClock())
        l.counted("ad", DebridLedger.SOFT_DAY, play = false)
        assertEquals(0, l.allowance("ad", DebridPurpose.AHEAD))
        assertEquals(10, l.allowance("ad", DebridPurpose.PLAY))
    }

    private fun allDebrid(http: FakeHttp, clock: FakeClock = FakeClock()): Pair<AllDebridService, DebridKit> {
        val kit = fakeKit(http, clock)
        return AllDebridService(kit, "tok", DebridPurpose.PLAY, AllDebridState()) to kit
    }

    @Test fun allDebridUploadsTopTwoKeepsReadyOnesAndCleansTheRest() = runBlocking {
        val http = FakeHttp()
            // The janitor's first look: the viewer owns one magnet already.
            .json("v4.1/magnet/status", """{"status":"success","data":{"magnets":[{"id":1,"hash":"user1"}]}}""")
            .on("magnet/upload") { s ->
                val hs = Regex("magnets%5B%5D=([a-z0-9]+)").findAll(s.body).map { it.groupValues[1] }.toList()
                HttpReply(200, """{"status":"success","data":{"magnets":[${hs.mapIndexed { i, h -> """{"hash":"$h","id":${100 + i + hs.size * 10},"ready":${h != "c"}}""" }.joinToString(",")}]}}""")
            }
            .json("magnet/files", """{"status":"success","data":{"magnets":[{"files":[{"n":"Movie.mkv","s":2000,"l":"https://alldebrid.com/f/x"}]}]}}""")
            .json("link/unlock", """{"status":"success","data":{"link":"https://cdn.alldebrid.com/dl/Movie.mkv","filename":"Movie.mkv","filesize":2000}}""")
            .json("magnet/delete", """{"status":"success","data":{}}""")
        val (ad, kit) = allDebrid(http)
        val out = ad.resolveMany(listOf(src("a", "Movie A"), src("b", "Movie B"), src("c", "Movie C")), kit.now() + 8_000, limit = 3)
        assertEquals(listOf("a", "b"), out.map { it.hash })
        assertEquals(1, http.count("magnet/upload"))
        assertTrue(out.all { it.service == DebridServiceId.ALLDEBRID && it.url.startsWith("https://") })
        // Both kept (played + alternate): nothing deleted yet; the viewer's magnet is never in any delete.
        delay(200)
        assertTrue(http.sent.filter { it.url.contains("magnet/delete") }.none { it.body.contains("ids%5B%5D=1&") || it.body.endsWith("ids%5B%5D=1") })
    }

    @Test fun allDebridAuthBlockedIsRememberedAndSkipped() = runBlocking {
        val http = FakeHttp().json("magnet/status", """{"status":"error","error":{"code":"AUTH_BLOCKED","message":"x"}}""")
        val (ad, kit) = allDebrid(http)
        assertTrue(ad.resolveMany(listOf(src("a", "Movie")), kit.now() + 5_000, 1).isEmpty())
        assertTrue(ad.blocked())
        val before = http.sent.size
        assertTrue(ad.resolveMany(listOf(src("a", "Movie")), kit.now() + 5_000, 1).isEmpty())
        assertEquals(before, http.sent.size) // refused: no more calls for a while
    }

    @Test fun premiumizeChecksOnceAndUnlocksCached() = runBlocking {
        val http = FakeHttp()
            .json("cache/check", """{"status":"success","response":[false,true]}""")
            .json("transfer/directdl", """{"status":"success","content":[{"path":"Movie/Movie.mkv","size":"2000","link":"https://pm.example/Movie.mkv"}]}""")
        val kit = fakeKit(http)
        val out = PremiumizeService(kit, "tok").resolveMany(listOf(src("a", "A"), src("b", "B")), kit.now() + 5_000, 1)
        assertEquals(listOf("b"), out.map { it.hash })
        assertEquals(2000, out.first().sizeBytes)
        assertTrue(http.sent.first().headers["Authorization"] == "Bearer tok")
    }

    @Test fun premiumizeApiKeyGoesAsParameter() = runBlocking {
        val http = FakeHttp().json("cache/check", """{"status":"success","response":[false]}""")
        val kit = fakeKit(http)
        PremiumizeService(kit, "apikey:KEY123").resolveMany(listOf(src("a", "A")), kit.now() + 5_000, 1)
        assertTrue(http.sent.first().url.contains("apikey=KEY123"))
        assertTrue("Authorization" !in http.sent.first().headers)
    }

    @Test fun realDebridRefreshesOn401AndSavesTheToken() = runBlocking {
        val secrets = MemorySecrets().apply {
            put("realdebridtoken", "old"); put("realdebridrefresh", "ref"); put("realdebrid.clientid", "cid"); put("realdebridsecret", "sec")
        }
        val infos = java.util.concurrent.atomic.AtomicInteger()
        val http = FakeHttp()
            .on("torrents/addMagnet") { s -> if (s.headers["Authorization"] == "Bearer old") HttpReply(401, """{"error":"bad_token"}""") else HttpReply(201, """{"id":"T1"}""") }
            .json("oauth/v2/token", """{"access_token":"new","refresh_token":"ref2"}""")
            .on("torrents/info/T1") { _ -> HttpReply(200, if (infos.getAndIncrement() == 0) """{"status":"waiting_files_selection","files":[{"id":1,"path":"/Movie.mkv","bytes":2000}]}"""
                else """{"status":"downloaded","links":["https://real-debrid.com/d/x"]}""") }
            .json("selectFiles/T1", "", 204)
            .json("unrestrict/link", """{"download":"https://dl.real-debrid.com/Movie.mkv","filename":"Movie.mkv","filesize":2000}""")
            .json("torrents/delete", "", 204)
        val kit = fakeKit(http, secrets = secrets)
        val rd = RealDebridService(kit, RealDebridState())
        val out = rd.resolveMany(listOf(src("a", "Movie")), kit.now() + 8_000, 1)
        assertEquals("https://dl.real-debrid.com/Movie.mkv", out.single().url)
        assertEquals("new", secrets.get("realdebridtoken"))
        assertEquals("ref2", secrets.get("realdebridrefresh"))
        delay(300)
        assertTrue(http.count("torrents/delete/T1") >= 1) // the probe torrent never stays
    }

    @Test fun torBoxSkipsPacksWithoutTheEpisode() = runBlocking {
        val http = FakeHttp()
            .json("torrents/mylist?bypass", """{"data":[]}""")
            .json("checkcached", """{"data":[{"hash":"a","files":[{"name":"Show.S01E01.mkv","size":1}]},{"hash":"b","files":[{"name":"Show.S01E02.mkv","size":1}]}]}""")
            .json("createtorrent", """{"data":{"torrent_id":7}}""")
            .json("mylist?id=7", """{"data":{"files":[{"id":3,"name":"Show.S01E02.mkv","size":1000}]}}""")
            .json("requestdl", """{"data":"https://tb.example/Show.S01E02.mkv"}""")
        val kit = fakeKit(http)
        val out = TorBoxService(kit, "tok", TorBoxState()).resolveMany(
            listOf(src("a", "Show S01", season = 1, episode = 2), src("b", "Show S01", season = 1, episode = 2)), kit.now() + 8_000, 1)
        assertEquals(listOf("b"), out.map { it.hash })
        assertEquals(1, http.count("createtorrent"))
    }
}
