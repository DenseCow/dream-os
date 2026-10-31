package com.dreamos.desktop.core

import com.dreamos.desktop.core.TestSupport.movie
import com.dreamos.desktop.core.TestSupport.show
import com.dreamos.desktop.core.content.*
import com.dreamos.desktop.core.model.*
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.net.NetResponse
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.time.DreamTime
import java.nio.file.Files
import kotlin.test.*

/** Watch history (real watching, a show never moves back, Up Next), the Watched ledger, profiles, PINs and the store. */
class HistoryTest {
    private lateinit var store: DreamStore

    @BeforeTest fun setUp() {
        store = TestSupport.freshStore()
        Tmdb.setKey("test")
        WatchHistory.clearCache()
        // Season 1 of show 100: three aired episodes; nothing else.
        Net.fake = { _, url, _ ->
            when {
                "/tv/100/season/1" in url -> NetResponse(200, """{"episodes":[
                    {"episode_number":1,"season_number":1,"name":"One","air_date":"2024-01-01","runtime":50},
                    {"episode_number":2,"season_number":1,"name":"Two","air_date":"2024-01-08","runtime":50},
                    {"episode_number":3,"season_number":1,"name":"Three","air_date":"2099-01-15","runtime":50}]}""", emptyMap())
                "/tv/100" in url -> NetResponse(200, """{"id":100,"seasons":[{"season_number":1}]}""", emptyMap())
                else -> NetResponse(404, "", emptyMap())
            }
        }
    }

    @AfterTest fun tearDown() { Net.fake = null; WatchHistory.outEarly = null }

    private val drama = show(100, "Drama")
    private fun ep(s: Int, e: Int) = PlayRequest(drama, season = s, episode = e)

    @Test fun aTitleJoinsContinueWatchingOnlyAfterNinetySecondsOfRealWatching() {
        val m = PlayRequest(movie(1))
        WatchHistory.report(m, positionMs = 50 * 60_000L, durationMs = 100 * 60_000L, realWatchedMs = 30_000, exact = true)
        assertTrue(WatchHistory.entries().isEmpty(), "a seek far in with 30 s of playback isn't watching")
        WatchHistory.report(m, positionMs = 50 * 60_000L, durationMs = 100 * 60_000L, realWatchedMs = 91_000, exact = true)
        assertEquals(listOf("movie:1"), WatchHistory.entries().map { it.key })
        // Once on the row it keeps saving as before.
        WatchHistory.report(m, positionMs = 60 * 60_000L, durationMs = 100 * 60_000L, realWatchedMs = 5_000, exact = true)
        assertEquals(60 * 60_000L, WatchHistory.entry("movie:1")!!.positionMs)
        assertEquals("Resume", WatchHistory.playLabel(movie(1)))
        assertEquals(60 * 60_000L, WatchHistory.startPositionMs(m))
        assertEquals(0L, WatchHistory.startPositionMs(m.copy(fromStart = true)))
    }

    @Test fun aFinishedMovieLeavesTheRowAndWearsWatched() {
        val m = PlayRequest(movie(2))
        WatchHistory.report(m, 95, 100, 200_000, exact = true)
        assertTrue(WatchHistory.entries().isEmpty())
        assertTrue(Watched.movie(2))
        assertEquals(0L, WatchHistory.startPositionMs(m), "a finished title starts from the beginning")
        assertEquals("Play", WatchHistory.playLabel(movie(2)))
    }

    @Test fun aFinishedEpisodeRollsOnToUpNextAndTheShowNeverMovesBack() {
        WatchHistory.report(ep(1, 1), 49 * 60_000L, 50 * 60_000L, 600_000, exact = true)
        var e = WatchHistory.entry("tv:100")!!
        assertTrue(e.upNext)
        assertEquals(1 to 2, e.season to e.episode)
        assertEquals("Play S1 E2", WatchHistory.playLabel(drama))
        assertTrue(Watched.episode(100, 1, 1))
        assertFalse(Watched.episode(100, 1, 2), "nothing is inferred")

        // Episode 2 in progress, then episode 1 is opened again by hand: under 90 s it doesn't take the row back.
        WatchHistory.report(ep(1, 2), 10 * 60_000L, 50 * 60_000L, 120_000, exact = true)
        WatchHistory.report(ep(1, 1), 5 * 60_000L, 50 * 60_000L, 30_000, exact = true)
        e = WatchHistory.entry("tv:100")!!
        assertEquals(2, e.episode)
        // A late finish of episode 1 never moves the show back either.
        WatchHistory.report(ep(1, 1), 49 * 60_000L, 50 * 60_000L, 600_000, exact = true)
        assertEquals(2, WatchHistory.entry("tv:100")!!.episode)
        // Watched on purpose for real (90 s): it takes the row back.
        WatchHistory.report(ep(1, 1), 6 * 60_000L, 50 * 60_000L, 95_000, exact = true)
        assertEquals(1, WatchHistory.entry("tv:100")!!.episode)
    }

    @Test fun upNextWaitsForAnAiredEpisodeUnlessItIsOutEarly() {
        WatchHistory.report(ep(1, 2), 49 * 60_000L, 50 * 60_000L, 600_000, exact = true)
        assertNull(WatchHistory.entries().firstOrNull { it.key == "tv:100" }, "episode 3 hasn't aired: the show leaves the row")
        assertNull(WatchHistory.episodeAfter(100, 1, 2))
        WatchHistory.outEarly = { _, s, e -> s == 1 && e == 3 }
        assertEquals(3, WatchHistory.episodeAfter(100, 1, 2)!!.second.let { o -> (o["episode_number"] as kotlinx.serialization.json.JsonPrimitive).content.toInt() })
    }

    @Test fun traktMergeIsLastWriteWinsWithoutMovingAShowBack() {
        WatchHistory.report(ep(1, 2), 10 * 60_000L, 50 * 60_000L, 120_000, exact = true)
        val now = System.currentTimeMillis()
        val older = HistoryEntry(drama, 1, 1, positionMs = 20 * 60_000L, durationMs = 50 * 60_000L, updatedAt = now + 600_000, source = "trakt")
        assertEquals(0, WatchHistory.mergeRemote(listOf(older)), "an earlier episode never replaces a later one")
        val newer = HistoryEntry(movie(5), positionMs = 30 * 60_000L, durationMs = 100 * 60_000L, updatedAt = now, source = "trakt")
        assertEquals(1, WatchHistory.mergeRemote(listOf(newer)))
        assertEquals(30 * 60_000L, WatchHistory.startPositionMs(PlayRequest(movie(5))))
    }

    @Test fun removingFromContinueWatchingHidesIt() {
        WatchHistory.report(PlayRequest(movie(7)), 30 * 60_000L, 100 * 60_000L, 120_000, exact = true)
        var told: String? = null
        WatchHistory.onRemoved = { told = it }
        WatchHistory.remove("movie:7")
        WatchHistory.onRemoved = null
        assertTrue(WatchHistory.entries().isEmpty())
        assertEquals("movie:7", told)
        assertTrue(BecauseYouWatched.removed(WatchHistory.entry("movie:7")!!))
        assertEquals(0L, WatchHistory.startPositionMs(PlayRequest(movie(7))))
    }

    @Test fun pinsAreHashedAndWrongTriesWait() {
        val kid = ProfileStore.add("Kids")
        assertTrue(kid.isKids, "named like a kids profile")
        ProfileStore.setPin(Profile.FIRST, "1234")
        val raw = store.file("profiles").getString("pin_${Profile.FIRST}")!!
        assertFalse("1234" in raw, "never stored as typed")
        assertEquals(PinResult.Ok, ProfileStore.checkPin(Profile.FIRST, "1234"))
        val t = 1_000_000L
        repeat(4) { assertEquals(PinResult.Wrong, ProfileStore.checkPin(Profile.FIRST, "0000", t)) }
        assertEquals(PinResult.Wait(30), ProfileStore.checkPin(Profile.FIRST, "0000", t))
        assertTrue(ProfileStore.checkPin(Profile.FIRST, "1234", t + 1000) is PinResult.Wait, "even the right PIN waits")
        assertEquals(PinResult.Ok, ProfileStore.checkPin(Profile.FIRST, "1234", t + 31_000))
    }

    @Test fun leavingAKidsProfileAsksForAGrownUpsPin() {
        val kid = ProfileStore.add("Junior")
        ProfileStore.save(kid.copy(exitPin = true))
        ProfileStore.setPin(Profile.FIRST, "4321")
        assertEquals(PinResult.Ok, ProfileStore.select(kid.id, null), "the kids profile has no PIN of its own")
        assertEquals(kid.id, ProfileStore.current.value.id)
        assertEquals(Profile.FIRST, ProfileStore.pinNeeded(Profile.FIRST))
        val other = ProfileStore.add("Sam")
        assertEquals(Profile.FIRST, ProfileStore.pinNeeded(other.id), "leaving Kids for a PIN-less profile asks the owner's PIN")
        assertEquals(PinResult.Wrong, ProfileStore.select(other.id, "1111"))
        assertEquals(PinResult.Ok, ProfileStore.select(other.id, "4321"))
        assertEquals(other.id, DreamStore.currentProfile)
    }

    @Test fun eachProfileHasItsOwnListAndHistory() {
        MyListStore.toggle(movie(1))
        val sam = ProfileStore.add("Sam")
        ProfileStore.setCurrent(sam.id)
        assertTrue(MyListStore.items().isEmpty())
        MyListStore.toggle(movie(2))
        ProfileStore.setCurrent(Profile.FIRST)
        assertEquals(listOf(1), MyListStore.items().map { it.id })
        ProfileStore.delete(sam.id)
        assertNull(ProfileStore.find(sam.id))
    }

    @Test fun kidsLimitFollowsTheLevel() {
        assertTrue(ContentPrefs.allows(1, "PG", tv = false))
        assertFalse(ContentPrefs.allows(1, "PG-13", tv = false))
        assertTrue(ContentPrefs.allows(1, "TV-PG", tv = true))
        assertFalse(ContentPrefs.allows(1, "TV-14", tv = true))
        assertFalse(ContentPrefs.allows(2, "", tv = false), "unrated is only for Everyone")
        assertTrue(ContentPrefs.allows(3, null, tv = false))
        val kid = ProfileStore.add("Kids")
        ProfileStore.setCurrent(kid.id)
        assertEquals("&include_adult=false&certification_country=US&certification=G|PG", KidsFilter.discoverClause("movie"))
        ProfileStore.setCurrent(Profile.FIRST)
        assertEquals("", KidsFilter.discoverClause("movie"))
    }

    @Test fun storeWritesAtomicallyAndSetsABadFileAside() {
        val dir = Files.createTempDirectory("dream-store")
        Files.writeString(dir.resolve("broken.json"), "{not json")
        val s = DreamStore(dir)
        val p = s.file("broken")
        assertFalse(p.contains("x"))
        assertTrue(Files.list(dir).use { l -> l.anyMatch { it.fileName.toString().startsWith("broken.json.bad-") } })
        p.putString("x", "1")
        p.putInt("n", 3)
        s.flush()
        val again = DreamStore(dir).file("broken")
        assertEquals("1", again.getString("x"))
        assertEquals(3, again.getInt("n"))
        assertEquals("tmdb_p9", s.profileFile("tmdb", "p9").name)
        assertEquals("tmdb", s.profileFile("tmdb", Profile.FIRST).name)
    }

    @Test fun datesNeverShift() {
        DreamTime.setZone("Pacific/Honolulu")
        try {
            assertEquals("Nov 14", DreamTime.formatDay("2026-11-14", "MMM d"))
            DreamTime.fixedNow = java.time.ZonedDateTime.of(2026, 11, 13, 23, 30, 0, 0, java.time.ZoneId.of("Pacific/Honolulu")).toInstant().toEpochMilli()
            assertEquals("Tomorrow", DreamTime.dayWords("2026-11-14"))
            assertEquals("Today", DreamTime.dayWords("2026-11-13"))
            assertEquals("Nov 14, 2027", DreamTime.dateWords("2027-11-14"))
        } finally {
            DreamTime.fixedNow = null
            DreamTime.setZone(null)
        }
    }
}
