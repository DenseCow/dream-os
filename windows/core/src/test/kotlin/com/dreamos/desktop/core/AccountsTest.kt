package com.dreamos.desktop.core

import com.dreamos.desktop.core.content.MyListStore
import com.dreamos.desktop.core.content.Tmdb
import com.dreamos.desktop.core.content.WatchHistory
import com.dreamos.desktop.core.content.Watched
import com.dreamos.desktop.core.model.*
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.net.NetResponse
import com.dreamos.desktop.core.notices.NoticeCenterImpl
import com.dreamos.desktop.core.notices.Notices
import com.dreamos.desktop.core.platform.Platform
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.trakt.*
import com.dreamos.desktop.core.update.UpdateCheck
import com.dreamos.desktop.core.update.UpdatesImpl
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.util.Collections
import kotlin.test.*

/** Trakt, the notification center and the update check, against a fake network (Net.fake). */
class AccountsTest {
    private val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val now = System.currentTimeMillis()
    private val clientId = "c".repeat(64)

    private fun json(body: String, code: Int = 200, headers: Map<String, String> = emptyMap()) =
        NetResponse(code, body, headers.mapValues { listOf(it.value) })

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    /** Answers with [handler]; anything it doesn't know is a 404 (never the real network). */
    private fun fake(handler: (method: String, url: String, body: String?) -> NetResponse?) {
        Net.fake = { m, u, b ->
            calls += "$m $u"
            handler(m, u, b) ?: json("", 404)
        }
    }

    private fun waitFor(ms: Long = 5_000, what: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!what()) {
            if (System.currentTimeMillis() > until) fail("timed out")
            Thread.sleep(10)
        }
    }

    @BeforeTest
    fun setUp() {
        DreamStore.use(DreamStore(Files.createTempDirectory("dream-test")))
        DreamStore.currentProfile = DreamStore.FIRST_PROFILE
        Notices.reset()
        Watched.profileChanged()
        MyListStore.reload()
        WatchHistory.clearCache()
        Tmdb.setKey("testkey")
        Trakt.background = false
        Trakt.pollUnitMs = 5
        calls.clear()
        fake { _, _, _ -> null }
    }

    @AfterTest
    fun tearDown() {
        // Background work still running (Watched's air check) must never reach the real network.
        fake { _, _, _ -> null }
    }

    private fun signIn(expiresInMs: Long = 90 * 86_400_000L) {
        Trakt.setClientId(clientId)
        TraktVault.setTokens(DreamStore.currentProfile, TraktTokens("access", "refresh", System.currentTimeMillis() + expiresInMs))
        TraktVault.setUser(DreamStore.currentProfile, TraktUser("joe"))
    }

    private fun show(id: Int) = Title(id, MediaKind.TV, "Show $id")
    private fun movie(id: Int) = Title(id, MediaKind.MOVIE, "Movie $id")

    // ---- Trakt sign-in ----

    @Test
    fun signInNeedsTheViewersClientId() {
        val e = assertFailsWith<DreamError> { runBlocking { TraktServiceImpl().startSignIn() } }
        assertEquals("Enter your Trakt app's Client ID first.", e.message)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun deviceSignInGoesPendingThenApproved() = runBlocking {
        var polls = 0
        fake { m, u, b ->
            when {
                m == "POST" && u == "https://auth.trakt.tv/oauth/device/code" -> {
                    assertTrue(b!!.contains(clientId))
                    assertFalse(b.contains("client_secret"))
                    json("""{"device_code":"dev123","user_code":"ABCD1234","verification_url":"https://trakt.tv/activate","expires_in":600,"interval":1}""")
                }
                m == "POST" && u == "https://auth.trakt.tv/oauth/device/token" -> {
                    assertTrue(b!!.contains("dev123"))
                    if (++polls < 3) json("", 400)
                    else json("""{"access_token":"a1","refresh_token":"r1","created_at":${now / 1000},"expires_in":7776000}""")
                }
                m == "GET" && u == "https://api.trakt.tv/users/settings" -> json("""{"user":{"username":"joe","name":"Joe"}}""")
                else -> null
            }
        }
        val svc = TraktServiceImpl()
        svc.setClientId("  $clientId \n")
        assertTrue(svc.status.value.hasClientId)
        val code = svc.startSignIn()
        assertEquals("ABCD1234", code.userCode)
        assertEquals("https://trakt.tv/activate", code.verifyUrl)
        assertTrue(svc.awaitApproval(code))
        assertEquals(3, polls)
        val s = svc.status.value
        assertTrue(s.signedIn)
        assertEquals("joe", s.user)
        assertNull(s.problem)
        // Sign-in never touches the API host's /oauth (Trakt answers an approved code there with 418).
        assertTrue(calls.none { it.contains("api.trakt.tv/oauth") })
    }

    @Test
    fun aWrongClientIdSaysSoPlainly() {
        fake { _, u, _ -> if (u.endsWith("/oauth/device/code")) json("", 401) else null }
        Trakt.setClientId(clientId)
        val e = assertFailsWith<DreamError> { Trakt.startSignIn() }
        assertTrue(e.message!!.startsWith("That Client ID didn't work."))
        fake { _, _, _ -> throw IOException("offline") }
        assertEquals("Couldn't reach Trakt. Try again.", assertFailsWith<DreamError> { Trakt.startSignIn() }.message)
    }

    @Test
    fun anExpiredCodeEndsTheWait() = runBlocking {
        Trakt.setClientId(clientId)
        val code = TraktDeviceCode("X", "https://trakt.tv/activate", System.currentTimeMillis() - 1, 1, "d")
        assertFalse(Trakt.awaitApproval(code))
        assertEquals("The code expired. Get a new one to try again.", Trakt.status.value.problem)
    }

    @Test
    fun renewalGoesToTheAuthHostWithoutASecret() {
        signIn(expiresInMs = 10 * 60_000L) // under the hour: renewed before the call
        var renewBody = ""
        fake { m, u, b ->
            when {
                m == "POST" && u == "https://auth.trakt.tv/oauth/token" -> {
                    renewBody = b!!
                    json("""{"access_token":"a2","refresh_token":"r2","created_at":${now / 1000},"expires_in":7776000}""")
                }
                u == "https://api.trakt.tv/users/settings" -> json("""{"user":{"username":"joe"}}""")
                else -> null
            }
        }
        val r = Trakt.authed(DreamStore.currentProfile, "GET", "/users/settings")
        assertEquals(200, r?.code)
        assertTrue(renewBody.contains("\"refresh_token\":\"refresh\""))
        assertTrue(renewBody.contains("http://localhost"))
        assertFalse(renewBody.contains("client_secret"))
        assertEquals("a2", TraktVault.tokens(DreamStore.currentProfile)?.a)
    }

    @Test
    fun aRefusedRenewalEndsTheSignIn() {
        signIn(expiresInMs = 30_000L)
        fake { _, u, _ -> if (u.endsWith("/oauth/token")) json("", 401) else null }
        assertNull(Trakt.authed(DreamStore.currentProfile, "GET", "/users/settings"))
        assertFalse(Trakt.signedIn())
        assertTrue(Trakt.expired())
        assertEquals("Sign In Again", Trakt.statusText())
    }

    @Test
    fun signOutForgetsTheProfilesSignIn() = runBlocking {
        signIn()
        TraktServiceImpl().signOut()
        assertFalse(Trakt.status.value.signedIn)
        assertNull(TraktVault.tokens(DreamStore.currentProfile))
        waitFor { calls.any { it == "POST https://auth.trakt.tv/oauth/revoke" } }
    }

    // ---- Trakt sync ----

    private val tmdb = "https://api.themoviedb.org/3"

    private fun tmdbAnswer(u: String): NetResponse? = when {
        u.startsWith("$tmdb/tv/100/season/1?") -> json("""{"episodes":[{"episode_number":5,"name":"Five","runtime":40,"air_date":"2020-01-05"},{"episode_number":6,"name":"Six","runtime":40,"air_date":"2020-01-12"}]}""")
        u.startsWith("$tmdb/tv/100?") -> json("""{"id":100,"name":"Show 100","first_air_date":"2020-01-01","genres":[{"id":18}],"seasons":[{"season_number":1},{"season_number":2}],"external_ids":{"imdb_id":"tt100"}}""")
        u.startsWith("$tmdb/movie/200?") -> json("""{"id":200,"title":"Movie 200","release_date":"2019-03-01","runtime":100,"external_ids":{"imdb_id":"tt200"}}""")
        u.startsWith("$tmdb/movie/300?") -> json("""{"id":300,"title":"Movie 300","release_date":"2021-05-01","runtime":90}""")
        else -> null
    }

    @Test
    fun historyPullMergesWithoutMovingAShowBack() {
        signIn()
        // Here the viewer is at S2 E3; Trakt's newest is S1 E5 (another device, an older episode).
        WatchHistory.mergeRemote(listOf(HistoryEntry(show(100), 2, 3, positionMs = 600_000, durationMs = 2_400_000, updatedAt = now - 3_600_000)))
        fake { m, u, _ ->
            tmdbAnswer(u) ?: when {
                u == "https://api.trakt.tv/sync/last_activities" -> json("""{"episodes":{"watched_at":"${iso(now - 60_000)}","paused_at":"a"},"movies":{"watched_at":"${iso(now - 120_000)}","paused_at":"a","watchlisted_at":"${iso(now - 86_400_000)}"},"shows":{"watchlisted_at":"${iso(now - 86_400_000)}"}}""")
                u.startsWith("https://api.trakt.tv/sync/history/episodes") -> json("""[{"watched_at":"${iso(now - 60_000)}","show":{"ids":{"tmdb":100}},"episode":{"season":1,"number":5}}]""")
                u.startsWith("https://api.trakt.tv/sync/history/movies") -> json("""[{"watched_at":"${iso(now - 120_000)}","movie":{"ids":{"tmdb":200}}}]""")
                u == "https://api.trakt.tv/sync/playback" -> json("[]")
                u.startsWith("https://api.trakt.tv/sync/watchlist/movies") -> json("""[{"listed_at":"${iso(now - 86_400_000)}","movie":{"ids":{"tmdb":300}}}]""")
                u.startsWith("https://api.trakt.tv/sync/watchlist/shows") -> json("[]")
                m == "POST" -> json("{}", 201)
                else -> null
            }
        }
        runBlocking { TraktServiceImpl().syncNow() }

        val s = WatchHistory.entry("tv:100")!!
        assertEquals(2, s.season)
        assertEquals(3, s.episode)
        assertFalse(s.hidden)
        val m = WatchHistory.entry("movie:200")!!
        assertTrue(m.hidden, "a finished movie leaves Continue Watching")
        assertEquals("trakt", m.source)
        assertTrue(Watched.movie(200))
        assertTrue(Watched.episode(100, 1, 5))
        // The watchlist came into My List without being pushed back.
        assertTrue(MyListStore.contains("movie:300"))
        assertTrue(calls.none { it.startsWith("POST https://api.trakt.tv/sync/watchlist") })
        val st = Trakt.status.value
        assertTrue(st.lastSync > 0)
        assertNull(st.problem)
        assertEquals(listOf(100), Trakt.followedShowTmdbIds())
    }

    @Test
    fun aFinishedEpisodeFromTraktBecomesUpNext() {
        signIn()
        fake { _, u, _ ->
            tmdbAnswer(u) ?: when {
                u == "https://api.trakt.tv/sync/last_activities" -> json("""{"episodes":{"watched_at":"${iso(now - 60_000)}"},"movies":{}}""")
                u.startsWith("https://api.trakt.tv/sync/history/episodes") -> json("""[{"watched_at":"${iso(now - 60_000)}","show":{"ids":{"tmdb":100}},"episode":{"season":1,"number":5}}]""")
                u.startsWith("https://api.trakt.tv/sync/") -> json("[]")
                else -> null
            }
        }
        Trakt.syncBlocking()
        val e = WatchHistory.entry("tv:100")!!
        assertEquals(1, e.season)
        assertEquals(6, e.episode)
        assertTrue(e.upNext)
    }

    @Test
    fun aQueuedPausedPointTheHistoryMovedPastIsDropped() {
        signIn()
        val p = DreamStore.currentProfile
        WatchHistory.mergeRemote(listOf(HistoryEntry(show(100), 1, 4, positionMs = 60_000, durationMs = 2_400_000, updatedAt = now)))
        TraktSync.queue(p, TraktOp("progress", item = TraktItem(false, 100, 1, 2), progress = 40.0, `when` = now - 600_000))
        TraktSync.queue(p, TraktOp("progress", item = TraktItem(true, 200), progress = 30.0, `when` = now))
        val sent = Collections.synchronizedList(ArrayList<String>())
        fake { m, u, b -> if (m == "POST" && u == "https://api.trakt.tv/scrobble/pause") { sent += b!!; json("{}", 201) } else null }
        assertTrue(TraktSync.flush(p))
        assertEquals(1, sent.size)
        assertTrue(sent[0].contains("\"movie\""))
        assertTrue(TraktVault.queue(p).isEmpty())
    }

    @Test
    fun myListChangesGoToTheWatchlist() {
        signIn()
        // Trakt.install()'s hook, without its timer (a timer sync would reach into the other tests).
        MyListStore.onChanged = { t, on -> TraktSync.onMyListChanged(t, on) }
        val added = Collections.synchronizedList(ArrayList<String>())
        fake { m, u, b -> if (m == "POST" && u == "https://api.trakt.tv/sync/watchlist") { added += b!!; json("{}", 201) } else null }
        MyListStore.toggle(movie(500))
        waitFor { added.isNotEmpty() }
        assertTrue(added[0].contains("500"))
        waitFor { TraktVault.queue(DreamStore.currentProfile).isEmpty() }
        assertEquals(true, TraktVault.state(DreamStore.currentProfile).ledger["movie:500"]?.inList)
        MyListStore.onChanged = null
    }

    @Test
    fun aStopThatCantBeSentIsQueuedAndPlaybackNeverWaits() {
        signIn()
        TraktScrobbler.settleMs = 20
        var offline = false
        fake { m, u, _ ->
            when {
                offline -> throw IOException("offline")
                m == "POST" && u.startsWith("https://api.trakt.tv/scrobble/") -> json("{}", 201)
                else -> null
            }
        }
        val req = PlayRequest(movie(400))
        val t0 = System.nanoTime()
        Trakt.scrobble(req, 0.1, ScrobbleAction.START, durationMs = 7_200_000)
        assertTrue(System.nanoTime() - t0 < 50_000_000, "scrobble returns at once")
        waitFor { calls.any { it == "POST https://api.trakt.tv/scrobble/start" } }
        offline = true
        Trakt.scrobble(req, 0.5, ScrobbleAction.STOP, durationMs = 7_200_000)
        waitFor { TraktVault.queue(DreamStore.currentProfile).isNotEmpty() }
        val op = TraktVault.queue(DreamStore.currentProfile).single()
        assertEquals("progress", op.op)
        assertEquals(50.0, op.progress)
        assertNotNull(op.`when`)
        // Short clips and unknown titles are never scrobbled.
        calls.clear()
        offline = false
        Trakt.scrobble(PlayRequest(movie(401)), 0.2, ScrobbleAction.START, durationMs = 60_000)
        Thread.sleep(100)
        assertTrue(calls.none { it.contains("scrobble") })
    }

    // ---- Notices ----

    @Test
    fun noticesPostOncePerKeyEver() {
        val center = NoticeCenterImpl()
        val n = Notice("expiry:rd:2026-11-02", Notice.TYPE_EXPIRY, "Real-Debrid Premium Ends in 3 Days", "Renew by Nov 2 to Keep Streaming",
            NoticeSeverity.WARNING, action = NoticeAction("Renew", "renew:rd"))
        center.post(n)
        center.post(n.copy(title = "Again"))
        assertEquals(1, center.notices.value.size)
        assertEquals("Real-Debrid Premium Ends in 3 Days", center.notices.value[0].title)
        assertTrue(center.notices.value[0].at > 0)
        assertEquals(1, Notices.unreadCount())
        center.markRead(n.key)
        assertEquals(0, Notices.unreadCount())
        center.dismiss(n.key)
        assertTrue(center.notices.value.isEmpty())
        // A dismissed notice never comes back.
        assertFalse(Notices.post(n))
        assertTrue(center.notices.value.isEmpty())
        // ...until its key is forgotten (a renewal starts a new period).
        Notices.forget("expiry:rd:")
        assertTrue(Notices.post(n))
    }

    @Test
    fun noticesKeepTheNewest50AndBannerOnlyKindsStayOut() {
        for (i in 1..55) Notices.post("info:$i", Notice.TYPE_INFO, "Notice $i", "")
        val all = Notices.all()
        assertEquals(Notices.KEEP, all.size)
        assertEquals("info:55", all.first().key)
        assertEquals("info:6", all.last().key)
        assertTrue(Notices.post("summary:today", Notice.TYPE_SUMMARY, "Your Accounts", "Real-Debrid: 5 Days"))
        assertNull(Notices.find("summary:today"))
        Notices.markAllRead()
        assertEquals(0, Notices.unreadCount())
    }

    @Test
    fun aNoticeInTheBackgroundAlsoGoesOutAsASystemNotification() {
        val saved = Platform.current
        try {
            Platform.current = Platform.forTests(Files.createTempDirectory("dream-platform"))
            val shown = ArrayList<String>()
            Platform.current.notifier = { t, b -> shown += "$t|$b" }
            Notices.appInFront = { true }
            Notices.post("info:front", Notice.TYPE_INFO, "In Front", "")
            assertTrue(shown.isEmpty())
            Notices.appInFront = { false }
            Notices.post("info:back", Notice.TYPE_INFO, "New Episode", "Show 100 S1 E6")
            assertEquals(listOf("New Episode|Show 100 S1 E6"), shown)
            // The banner waited for the window; it comes back to the front: banners go out.
            assertTrue(Notices.pendingBanners().any { it.key == "info:back" })
            val got = ArrayList<Notice>()
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launchCollect(got)
            Notices.appInFront = { true }
            Notices.flush()
            waitFor { got.isNotEmpty() }
            job.cancel()
            assertTrue(got.any { it.key == "info:back" })
            assertTrue(Notices.pendingBanners().isEmpty())
        } finally {
            Platform.current = saved
            Notices.appInFront = { true }
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.launchCollect(into: MutableList<Notice>): kotlinx.coroutines.Job {
        val started = java.util.concurrent.CountDownLatch(1)
        val job = launch(kotlinx.coroutines.Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            started.countDown()
            Notices.banners.collect { synchronized(into) { into += it } }
        }
        started.await()
        return job
    }

    // ---- Updates ----

    private val shaNotes = "a".repeat(64)
    private val shaAsset = "b".repeat(64)
    private val release = """{"tag_name":"v4.3.4","name":"Dream OS 4.3.4","published_at":"2026-10-30T12:00:00Z",
        "body":"## What's New\n- **Faster** start\n- See [the notes](https://example.com)\n\nSHA-256: $shaNotes",
        "assets":[
          {"name":"DreamOS.apk","size":1000,"browser_download_url":"https://github.com/DenseCow/dream-os/releases/download/v4.3.4/DreamOS.apk"},
          {"name":"DreamOS-Windows-Setup.exe","size":12345678,"browser_download_url":"https://github.com/DenseCow/dream-os/releases/download/v4.3.4/DreamOS-Windows-Setup.exe"},
          {"name":"DreamOS-Windows-Setup.exe.sha256","size":90,"browser_download_url":"https://github.com/DenseCow/dream-os/releases/download/v4.3.4/DreamOS-Windows-Setup.exe.sha256"}
        ]}"""

    @Test
    fun updateCheckFindsANewerReleaseThenKeepsItOn304() = runBlocking {
        System.setProperty("dream.version", "4.3.2")
        try {
            var notModified = false
            fake { _, u, _ ->
                when {
                    u == UpdateCheck.URL -> if (notModified) json("", 304) else json(release, headers = mapOf("ETag" to "\"e1\""))
                    u.endsWith(".exe.sha256") -> json("$shaAsset  DreamOS-Windows-Setup.exe\n")
                    else -> null
                }
            }
            val updates = UpdatesImpl()
            val s = updates.check(force = true)
            assertTrue(s.available)
            assertFalse(s.failed)
            assertEquals("4.3.2", s.current)
            val l = s.latest!!
            assertEquals("4.3.4", l.version)
            assertTrue(l.downloadUrl.endsWith("/DreamOS-Windows-Setup.exe"))
            assertEquals(12345678L, l.sizeBytes)
            assertEquals(shaAsset, l.sha256, "the checksum asset wins over the notes")
            assertEquals("What's New\n•  Faster start\n•  See the notes", l.notes)
            val n = Notices.find("update:4.3.4")!!
            assertEquals("There's a New Version Available", n.title)
            assertEquals("Dream OS 4.3.4", n.body)
            assertEquals("update", n.action?.target)

            // Within 6 hours: the stored answer, no network.
            calls.clear()
            assertEquals(s.latest, updates.check().latest)
            assertTrue(calls.isEmpty())

            // Forced: GitHub says nothing changed (304); the stored release stays.
            notModified = true
            val again = updates.check(force = true)
            assertEquals(1, calls.size)
            assertEquals("4.3.4", again.latest?.version)
            assertTrue(again.available)
            assertTrue(again.checkedAt >= s.checkedAt)

            // Updated to 4.3.4: up to date, the notice goes.
            System.setProperty("dream.version", "4.3.4")
            val current = updates.check(force = true)
            assertFalse(current.available)
            assertNull(Notices.find("update:4.3.4"))

            // Offline: the last answer stays, marked failed.
            fake { _, _, _ -> throw IOException("offline") }
            val offline = updates.check(force = true)
            assertTrue(offline.failed)
            assertEquals("4.3.4", offline.latest?.version)
            assertEquals(offline, updates.status.value)
        } finally {
            System.clearProperty("dream.version")
        }
    }

    @Test
    fun aReleaseWithoutAChecksumAssetReadsItFromTheNotes() {
        val o = com.dreamos.desktop.core.util.parseJson(release.replace("DreamOS-Windows-Setup.exe.sha256", "other.txt")) as kotlinx.serialization.json.JsonObject
        assertEquals(shaNotes, UpdateCheck.release(o)?.sha256)
    }

    @Test
    fun aReleaseWithOnlyTheOldMsiOffersNoInstaller() {
        val old = release.replace("DreamOS-Windows-Setup.exe", "DreamOS-Windows.msi")
        val info = UpdateCheck.release(com.dreamos.desktop.core.util.parseJson(old) as kotlinx.serialization.json.JsonObject)!!
        assertEquals("", info.downloadUrl)
        assertNull(info.sha256)
    }

    @Test
    fun versionCompareIsNumericPartByPart() {
        assertTrue(UpdateCheck.newer("4.10", "4.9"))
        assertTrue(UpdateCheck.newer("v4.3.4", "4.3.2"))
        assertTrue(UpdateCheck.newer("4.3.2.1", "4.3.2"))
        assertFalse(UpdateCheck.newer("4.3", "4.3.0"))
        assertFalse(UpdateCheck.newer("Dream OS 4.3.2", "v4.3.2"))
        assertFalse(UpdateCheck.newer("4.2.9", "4.3"))
        assertFalse(UpdateCheck.newer("beta", "4.3"))
        assertTrue(UpdateCheck.newer("4.3", "unknown"))
        assertTrue(UpdateCheck.newer("0.1", "0"))
    }
}
