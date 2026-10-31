package com.dreamos.desktop.core

import com.dreamos.desktop.core.catalog.ContentPlaybackHooks
import com.dreamos.desktop.core.catalog.ContentReleases
import com.dreamos.desktop.core.catalog.Personal
import com.dreamos.desktop.core.content.Details
import com.dreamos.desktop.core.content.NewEpisodeSpot
import com.dreamos.desktop.core.content.ProfileStore
import com.dreamos.desktop.core.content.RowMix
import com.dreamos.desktop.core.content.ViewerSettings
import com.dreamos.desktop.core.model.HistoryEntry
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.PinResult
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.net.NetResponse
import com.dreamos.desktop.core.net.TmdbDisk
import com.dreamos.desktop.core.platform.DpapiCipher
import com.dreamos.desktop.core.platform.PlainCipher
import com.dreamos.desktop.core.platform.SealedSecretStore
import com.dreamos.desktop.core.platform.Secrets
import com.dreamos.desktop.core.playback.PlayTarget
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.time.DreamTime
import com.dreamos.desktop.core.trakt.Trakt
import com.dreamos.desktop.core.update.UpdateInstall
import com.dreamos.desktop.core.util.parseJson
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.*
import com.dreamos.desktop.core.playback.MediaKind as PlayKind

/** Round 2: the TMDB disk cache, secrets at rest, Hello select, credits, settings, playback hooks, banner rule, updater. */
class Round2Test {
    private lateinit var store: DreamStore

    @BeforeTest fun setUp() { store = TestSupport.freshStore() }

    @AfterTest fun tearDown() {
        Net.fake = null
        TmdbDisk.close()
    }

    // ---- TmdbDisk ----

    private fun tmdbUrl(path: String) = "https://api.themoviedb.org/3$path?api_key=k&language=en-US"

    private fun diskFile() = Files.list(TmdbDisk.folder!!).use { s -> s.filter { it.toString().endsWith(".json") }.toList() }.single()

    @Test fun listsComeFromDiskAndRevalidateBehind() {
        TmdbDisk.open(Files.createTempDirectory("tmdb-disk"))
        var calls = 0
        Net.fake = { _, _, _ -> calls++; NetResponse(200, """{"results":[{"id":$calls}]}""", emptyMap()) }
        val u = tmdbUrl("/trending/all/day")
        assertEquals("""{"results":[{"id":1}]}""", Net.getText(u))
        TmdbDisk.flush()
        assertEquals("""{"results":[{"id":1}]}""", Net.getText(u), "fresh: from disk")
        assertEquals(1, calls)
        // Two hours old (past the chart's hour): served at once, stale, refreshed behind.
        Files.setLastModifiedTime(diskFile(), FileTime.fromMillis(System.currentTimeMillis() - 2 * 3_600_000L))
        val a = Net.tmdb(u)
        assertTrue(a.stale)
        assertEquals("""{"results":[{"id":1}]}""", a.body)
        val end = System.currentTimeMillis() + 5_000
        while (calls < 2 && System.currentTimeMillis() < end) Thread.sleep(20)
        TmdbDisk.flush()
        assertEquals(2, calls, "refreshed behind")
        assertEquals("""{"results":[{"id":2}]}""", Net.getText(u))
    }

    @Test fun objectsAskAgainPastTheirLifetimeAndFallBackOffline() {
        TmdbDisk.open(Files.createTempDirectory("tmdb-disk"))
        var up = true
        Net.fake = { _, _, _ -> if (up) NetResponse(200, """{"id":7,"name":"A"}""", emptyMap()) else throw java.io.IOException("offline") }
        val u = tmdbUrl("/tv/7")
        Net.getText(u)
        TmdbDisk.flush()
        Files.setLastModifiedTime(diskFile(), FileTime.fromMillis(System.currentTimeMillis() - 7 * 3_600_000L))
        up = false
        val a = Net.tmdb(u)
        assertTrue(a.stale, "offline: the older copy stands in")
        Files.setLastModifiedTime(diskFile(), FileTime.fromMillis(System.currentTimeMillis() - 8 * 86_400_000L))
        assertFailsWith<java.io.IOException> { Net.getText(u) }
    }

    @Test fun keyChecksAndErrorsAreNeverKept() {
        TmdbDisk.open(Files.createTempDirectory("tmdb-disk"))
        var calls = 0
        Net.fake = { _, _, _ -> calls++; NetResponse(200, "{}", emptyMap()) }
        Net.getText("https://api.themoviedb.org/3/configuration?api_key=k")
        Net.getText("https://api.themoviedb.org/3/configuration?api_key=k")
        assertEquals(2, calls)
        Net.fake = { _, _, _ -> NetResponse(404, "{}", emptyMap()) }
        assertFails { Net.getText(tmdbUrl("/movie/1")) }
        TmdbDisk.flush()
        assertEquals(0L, Files.list(TmdbDisk.folder!!).use { it.count() })
        assertEquals(3_600_000L, TmdbDisk.ttlMs("/search/multi"))
        assertEquals(7 * 86_400_000L, TmdbDisk.ttlMs("/movie/5/images"))
        assertTrue(TmdbDisk.isList("/movie/5/recommendations"))
        assertFalse(TmdbDisk.isList("/movie/5"))
    }

    @Test fun pruneKeepsTheFolderBounded() {
        val dir = Files.createTempDirectory("tmdb-prune")
        val old = dir.resolve("old.json").also { Files.writeString(it, "{}") }
        Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() - 8 * 86_400_000L))
        val fresh = dir.resolve("fresh.json").also { Files.writeString(it, "{}") }
        TmdbDisk.prune(dir)
        assertFalse(Files.exists(old))
        assertTrue(Files.exists(fresh))
    }

    // ---- secrets ----

    @Test fun dpapiSealsForThisUserOnly() {
        if (!System.getProperty("os.name").lowercase().startsWith("windows")) return
        val sealed = DpapiCipher.seal("token-123".toByteArray(), "realdebridtoken")
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("token-123"))
        assertEquals("token-123", DpapiCipher.open(sealed, "realdebridtoken")?.toString(Charsets.UTF_8))
        assertNull(DpapiCipher.open(sealed, "alldebridtoken"), "sealed under another key name: doesn't open")
        val s = SealedSecretStore(store.file("secrets_test"), DpapiCipher)
        s.put("realdebridtoken", "abc")
        assertEquals("abc", s.get("realdebridtoken"))
        assertFalse(store.file("secrets_test").getString("realdebridtoken")!!.contains("abc"))
        s.remove("realdebridtoken")
        assertNull(s.get("realdebridtoken"))
    }

    @Test fun plainDebridTokensMoveIntoTheSealedStoreOnce() {
        store.file("playback_accounts").putString("alldebridtoken", "ad-1")
        store.flush()
        val s = SealedSecretStore(store.file("secrets_playback"), PlainCipher)
        assertEquals(1, s.migrateFrom(store, "playback_accounts"))
        assertEquals("ad-1", s.get("alldebridtoken"))
        assertFalse(store.exists("playback_accounts"))
        assertEquals(0, s.migrateFrom(store, "playback_accounts"))
        assertNotNull(Secrets.playback)
    }

    @Test fun traktSignInMovesOffThePlainFile() {
        store.file("trakt_auth").putString("x", "1")
        store.file("trakt_auth").edit { putJson("tokens", parseJson("""{"a":"acc","r":"ref","e":99}""")) }
        store.flush()
        store.file("trakt_app").putString("client_id", "cid")
        assertTrue(Trakt.signedIn())
        assertFalse(store.exists("trakt_auth"), "the plain file is gone")
        val raw = store.file("secrets").getString("trakt.tokens")!!
        assertTrue(raw.startsWith("v1:"))
        if (System.getProperty("os.name").lowercase().startsWith("windows")) {
            assertFalse(java.util.Base64.getDecoder().decode(raw.removePrefix("v1:")).toString(Charsets.ISO_8859_1).contains("acc"))
        }
    }

    // ---- profiles ----

    @Test fun helloStandsInForTheProfilesOwnPinOnly() {
        val p = ProfileStore.add("Sam")
        ProfileStore.save(p, pin = "1234")
        assertEquals(PinResult.Wrong, ProfileStore.selectTrusted(p.id), "Hello not turned on for Sam")
        store.file("pages").putBoolean("hello.${p.id}", true)
        assertEquals(PinResult.Ok, ProfileStore.selectTrusted(p.id))
        assertEquals(p.id, ProfileStore.current.value.id)
        // Leaving a Kids profile that asks for a grown-up's PIN: Hello never stands in for that PIN.
        ProfileStore.save(ProfileStore.find("p1")!!, pin = "9999")
        store.file("pages").putBoolean("hello.p1", true)
        val open = ProfileStore.add("Guest")
        val kid = ProfileStore.add("Kids")
        ProfileStore.save(ProfileStore.find(kid.id)!!.copy(isKids = true, exitPin = true))
        ProfileStore.setCurrent(kid.id)
        assertEquals("p1", ProfileStore.pinNeeded(open.id))
        assertEquals(PinResult.Wrong, ProfileStore.selectTrusted(open.id))
        assertEquals(kid.id, ProfileStore.current.value.id)
    }

    // ---- content ----

    @Test fun everyCreditOncePerTitleNewestFirst() {
        val cc = parseJson("""{"cast":[
            {"id":1,"media_type":"movie","title":"Old","release_date":"2001-01-01","poster_path":"/a.jpg","character":"Hero","vote_count":900},
            {"id":2,"media_type":"tv","name":"Talk","first_air_date":"2020-01-01","poster_path":"/b.jpg","character":"Himself","genre_ids":[10767]},
            {"id":3,"media_type":"movie","title":"New","release_date":"2024-05-01","poster_path":"/c.jpg","character":"Villain"},
            {"id":4,"media_type":"movie","title":"Soon","poster_path":"/d.jpg","character":"Lead"},
            {"id":5,"media_type":"movie","title":"No Poster","release_date":"2023-01-01","character":"X"}],
          "crew":[{"id":3,"media_type":"movie","title":"New","release_date":"2024-05-01","poster_path":"/c.jpg","job":"Director","department":"Directing"},
                  {"id":6,"media_type":"movie","title":"Written","release_date":"2010-01-01","poster_path":"/e.jpg","job":"Writer","department":"Writing"}]}""").jsonObject
        val c = Details.credits(cc)
        assertEquals(listOf("New", "Written", "Old", "Soon"), c.map { it.title.name })
        assertEquals("Villain, Director", c[0].role)
        assertEquals("Writing", c[1].department)
    }

    @Test fun animationMadeInJapanIsAnime() {
        val edgerunners = Title(105248, MediaKind.TV, "Cyberpunk: Edgerunners", lang = "en", genreIds = listOf(16, 10759, 10765), originCountry = listOf("JP", "PL", "US"))
        assertTrue(RowMix.isAnime(edgerunners))
        val mario = Title(502356, MediaKind.MOVIE, "The Super Mario Bros. Movie", lang = "en", genreIds = listOf(16, 10751, 12), originCountry = listOf("JP", "US"))
        assertFalse(RowMix.isAnime(mario), "a family film stays")
        assertFalse(RowMix.isAnime(Title(1, MediaKind.TV, "Arcane", lang = "en", genreIds = listOf(16), originCountry = listOf("US", "FR"))))
    }

    @Test fun continueWatchingSaysTimeLeft() {
        assertEquals("42 Min Left", Personal.timeLeft(18 * 60_000L, 60 * 60_000L))
        assertNull(Personal.timeLeft(59 * 60_000L + 30_000, 60 * 60_000L))
        assertNull(Personal.timeLeft(0, 0))
    }

    @Test fun newEpisodeBannerOnlyWhenCaughtUp() {
        val show = Title(10, MediaKind.TV, "Show")
        fun e(s: Int, ep: Int, pos: Long, upNext: Boolean = false) = HistoryEntry(show, s, ep, positionMs = pos, durationMs = 100, upNext = upNext)
        assertTrue(NewEpisodeSpot.caughtUp(emptyList(), 10, 2, 5), "followed from My List")
        assertTrue(NewEpisodeSpot.caughtUp(listOf(e(2, 4, 100)), 10, 2, 5), "finished the one before")
        assertFalse(NewEpisodeSpot.caughtUp(listOf(e(2, 4, 40)), 10, 2, 5), "still watching the one before")
        assertFalse(NewEpisodeSpot.caughtUp(listOf(e(2, 2, 100)), 10, 2, 5), "behind")
        assertTrue(NewEpisodeSpot.caughtUp(listOf(e(1, 8, 100)), 10, 2, 1), "a new season after the last one")
        assertTrue(NewEpisodeSpot.caughtUp(listOf(e(2, 5, 0, upNext = true)), 10, 2, 5), "its Up Next")
    }

    @Test fun pagesSettingsReadAndDriveTheClock() {
        val f = store.profileFile("pages")
        assertNull(ViewerSettings.maxQuality())
        f.putString("quality.max", "1080p")
        assertEquals("1080p", ViewerSettings.maxQuality()?.label)
        f.putString("subs.lang1", "Spanish"); f.putString("subs.lang2", "French")
        assertEquals(listOf("es", "fr"), ViewerSettings.subtitleLangs())
        assertTrue(ViewerSettings.preferDubs())
        f.putString("time.zone", "Asia/Tokyo"); f.putString("time.clock", "24-Hour")
        ViewerSettings.applyTime()
        assertEquals("Asia/Tokyo", DreamTime.zone.id)
        assertTrue(DreamTime.use24h)
        f.putString("time.zone", ""); f.putString("time.clock", "Automatic")
        ViewerSettings.applyTime()
        assertTrue(DreamTime.isAutomatic)
        assertNull(DreamTime.override24h)
    }

    @Test fun scrapeQueryAndDigitalDayFromTmdb() {
        val d = parseJson("""{"name":"Dark","original_name":"Dark","number_of_seasons":3,"external_ids":{"imdb_id":"tt5753856","tvdb_id":334824},
            "alternative_titles":{"results":[{"iso_3166_1":"US","title":"Dark (2017)"},{"iso_3166_1":"DE","title":"Dunkel"}]}}""").jsonObject
        val t = PlayTarget(PlayKind.EPISODE, 70523, "Dark", season = 1, episode = 1)
        val q = ContentPlaybackHooks.query(t, "tt5753856", d)
        assertEquals(listOf("Dark (2017)"), q.aliases)
        assertEquals("334824", q.tvdb)
        assertEquals(3, q.totalSeasons)
        val r = parseJson("""{"release_dates":{"results":[
            {"iso_3166_1":"GB","release_dates":[{"type":4,"release_date":"2026-01-02T00:00:00.000Z"}]},
            {"iso_3166_1":"US","release_dates":[{"type":3,"release_date":"2025-11-01T00:00:00.000Z"},{"type":4,"release_date":"2026-01-20T00:00:00.000Z"}]}]}}""").jsonObject
        assertEquals("2026-01-20", ContentReleases.digitalFrom(r))
        assertEquals("2026-01-02", ContentReleases.digitalFrom(parseJson("""{"release_dates":{"results":[{"iso_3166_1":"GB","release_dates":[{"type":4,"release_date":"2026-01-02T00:00:00.000Z"}]}]}}""").jsonObject))
    }

    @Test fun installerChecksumIsComputedOverTheWholeFile() {
        val f = Files.createTempFile("dream", ".msi")
        Files.writeString(f, "abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdateInstall.sha256(f))
    }
}
