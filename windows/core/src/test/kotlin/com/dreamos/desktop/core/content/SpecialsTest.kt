package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.TestSupport
import com.dreamos.desktop.core.TestSupport.show
import com.dreamos.desktop.core.model.EntryKind
import com.dreamos.desktop.core.model.EpisodeCode
import com.dreamos.desktop.core.model.EpisodeKind
import com.dreamos.desktop.core.model.HistoryEntry
import com.dreamos.desktop.core.model.PlayRequest
import com.dreamos.desktop.core.model.SeasonSummary
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.net.NetResponse
import com.dreamos.desktop.core.catalog.ContentPlaybackHooks
import com.dreamos.desktop.core.catalog.Personal
import com.dreamos.desktop.core.playback.MediaKind as PlayKind
import com.dreamos.desktop.core.trakt.TraktItem
import com.dreamos.desktop.core.util.objects
import com.dreamos.desktop.core.util.parseJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

/**
 * Specials: the catalog that merges TMDB's season 0 with TVmaze's list, and the model around it. A special is its own
 * kind and behaves like a movie that belongs to a show: its own history entry, its own watch record, never Up Next,
 * never what Play names, and never counted as progress through the series (STANDARDS §5).
 *
 * The data is a show with eight listed episodes, no season 0 at TMDB (a 404, as on the day this feature was written)
 * and one special TVmaze lists as season 1 with no number.
 */
class SpecialsTest {
    private val series = show(500, "Show 500")

    private val SPECIAL_JSON = """{"id":7001,"season":1,"number":null,"name":"A Special","airdate":"2026-10-31","airtime":"","airstamp":"2026-10-31T12:00:00+00:00","runtime":30,"summary":""}"""

    private fun maze(vararg eps: String) = parseJson("[${eps.joinToString(",")}]") as JsonArray
    private fun tmdbEp(id: Int, number: Int, name: String, day: String) =
        parseJson("""{"id":$id,"episode_number":$number,"season_number":0,"name":"$name","air_date":"$day","runtime":30}""") as JsonObject

    @BeforeTest fun setUp() {
        TestSupport.freshStore()
        Tmdb.setKey("test")
        WatchHistory.clearCache()
        Watched.profileChanged()
        Specials.clearCache()
        Net.fake = { _, _, _ -> NetResponse(404, "", emptyMap()) }
    }

    @AfterTest fun tearDown() { Net.fake = null }

    // ---- the catalog ----

    @Test fun tvmazesSpecialsAreListedWhenTmdbHasNoSeasonZero() {
        val list = maze("""{"id":7001,"season":1,"number":null,"name":"A Special","airdate":"2026-10-31","airtime":"21:00",
            "airstamp":"2026-11-01T01:00:00+00:00","runtime":30,"summary":"<p>A <b>holiday</b> hour.</p>"}""")
        val specials = Specials.merge(500, emptyList(), AirTimes.parseSpecials(list))
        val s = specials.single()
        assertEquals("tvmaze:7001", s.id, "identified by its source id")
        assertEquals(1, s.number, "first in air order, which is what the index numbers it by")
        assertEquals(1, s.parentSeason, "the season it aired beside, for the S01E00 form of its release name")
        assertEquals("A holiday hour.", s.overview, "TVmaze summaries are HTML")
        assertEquals(EpisodeKind.SPECIAL, s.episode().kind)
        assertEquals("500/sp:tvmaze:7001", s.episode().id)
    }

    @Test fun tmdbsEntryWinsAndTakesTvmazesTimeAndParentSeason() {
        val list = maze("""{"id":7001,"season":2,"number":null,"name":"A Special","airdate":"2026-10-31","airtime":"21:00",
            "airstamp":"2026-11-01T01:00:00+00:00","runtime":30,"summary":""}""")
        val specials = Specials.merge(500, listOf(tmdbEp(9001, 4, "A Special", "2026-10-31")), AirTimes.parseSpecials(list))
        val s = specials.single()
        assertEquals("tmdb:9001", s.id, "TMDB's record is the same special, not a second one")
        assertEquals(listOf("tvmaze:7001"), s.aliasIds, "and the id it was first known by stays")
        assertEquals(2, s.parentSeason, "from TVmaze")
        assertEquals(4, s.tmdbNumber, "TMDB's own season-0 number, which release names may use")
        assertEquals(1, s.number, "its place in air order is its own")
    }

    @Test fun twoSpecialsOnOneDayStayTwo() {
        // A two-part special dropped together: matching on the day alone would collapse them into one.
        val list = maze(
            """{"id":7001,"season":1,"number":null,"name":"Part One","airdate":"2026-12-24","airtime":"","airstamp":"2026-12-24T12:00:00+00:00","runtime":30,"summary":""}""",
            """{"id":7002,"season":1,"number":null,"name":"Part Two","airdate":"2026-12-24","airtime":"","airstamp":"2026-12-24T12:00:00+00:00","runtime":30,"summary":""}""",
        )
        val specials = Specials.merge(500, listOf(tmdbEp(9001, 1, "Part One", "2026-12-24")), AirTimes.parseSpecials(list))
        assertEquals(listOf("tmdb:9001", "tvmaze:7002"), specials.map { it.id })
    }

    @Test fun aSpecialWithNoNameFallsBackToTheDay() {
        val list = maze("""{"id":7001,"season":1,"number":null,"name":"Special","airdate":"2026-10-31","airtime":"","airstamp":"2026-10-31T12:00:00+00:00","runtime":30,"summary":""}""")
        val specials = Specials.merge(500, listOf(tmdbEp(9001, 1, "A Story", "2026-10-31")), AirTimes.parseSpecials(list))
        assertEquals(listOf("tmdb:9001"), specials.map { it.id }, "a name that says nothing isn't a second special")
    }

    @Test fun anEarlierSpecialArrivingLaterDoesNotChangeWhatAnIdMeans() {
        // The regression the source-id identity exists to prevent: TMDB backfills season 0 over time, and a special
        // that turns up with an earlier air day moves every later one's place in air order.
        val one = maze("""{"id":7001,"season":1,"number":null,"name":"A Special","airdate":"2026-10-31","airtime":"","airstamp":"2026-10-31T12:00:00+00:00","runtime":30,"summary":""}""")
        val before = Specials.merge(500, emptyList(), AirTimes.parseSpecials(one)).single()
        assertEquals(1, before.number)

        val both = maze(
            """{"id":7000,"season":1,"number":null,"name":"An Earlier Special","airdate":"2026-01-02","airtime":"","airstamp":"2026-01-02T12:00:00+00:00","runtime":30,"summary":""}""",
            """{"id":7001,"season":1,"number":null,"name":"A Special","airdate":"2026-10-31","airtime":"","airstamp":"2026-10-31T12:00:00+00:00","runtime":30,"summary":""}""",
        )
        val after = Specials.merge(500, emptyList(), AirTimes.parseSpecials(both))
        val same = after.single { it.id == before.id }
        assertEquals(2, same.number, "its place moved")
        assertEquals("A Special", same.name, "but it is still the same special")
        // A watch record keyed on the position would now point at the other special; keyed on the id it does not.
        Watched.fromSpecial(500, before.id)
        assertTrue(Watched.special(500, same.id), "the one they watched is still the one they watched")
        assertFalse(Watched.special(500, after.single { it.id != before.id }.id), "and the new one is not")
    }

    @Test fun theSpecialsTabComesAfterEverySeason() {
        val seasons = listOf(SeasonSummary(0, "Specials", 2), SeasonSummary(1, "Season 1", 8), SeasonSummary(2, "Season 2", 8))
        val ordered = Specials.ordered(seasons, count = 3)
        assertEquals(listOf(1, 2, 0), ordered.map { it.number }, "so the chosen season stays Season 1")
        assertEquals(EpisodeCode.SPECIALS, ordered.last().name)
        assertEquals(3, ordered.last().episodeCount, "the catalog's count, which can beat TMDB's")
        // Nothing asked yet: TMDB's own season 0 still gives a tab, with TMDB's count.
        assertEquals(listOf(1, 2, 0), Specials.ordered(seasons, count = 0).map { it.number })
        assertEquals(2, Specials.ordered(seasons, count = 0).last().episodeCount)
        val noZero = seasons.filter { it.number > 0 }
        assertEquals(listOf(1, 2), Specials.ordered(noZero, count = 0).map { it.number }, "no specials, no tab")
    }

    // ---- the model: a special is a movie that belongs to a show ----

    private fun special(id: String = "tmdb:9001") = PlayRequest(series, season = 1, episode = 1, episodeName = "A Special", specialId = id)
    private fun episode(s: Int, e: Int) = PlayRequest(series, season = s, episode = e)
    private fun report(r: PlayRequest, fraction: Double) =
        WatchHistory.report(r, (fraction * 60 * 60_000L).toLong(), 60 * 60_000L, 120_000, exact = true)

    @Test fun aSpecialAndASeriesEpisodeOfOneShowBothStayInTheHistory() {
        report(episode(3, 5), 0.6)
        report(special(), 0.4)
        val keys = WatchHistory.entries().map { it.key }
        assertEquals(setOf("tv:500", "tv:500/sp:tmdb:9001"), keys.toSet(), "two cards, not one")
        val s = WatchHistory.seriesEntry(500)!!
        assertEquals(3 to 5, s.season to s.episode, "the show's own position is untouched")
        assertEquals(EntryKind.SERIES, s.kind)
        assertEquals(EntryKind.SPECIAL, WatchHistory.specialEntry(500, "tmdb:9001")!!.kind)
    }

    @Test fun theSeriesLookupNeverReturnsTheSpecial() {
        report(special(), 0.4)
        assertNull(WatchHistory.seriesEntry(500), "only a special was watched: the show has no position yet")
        assertNull(WatchHistory.playEntry(series), "so Play starts the series at the beginning")
        assertEquals("Play S1 E1", WatchHistory.playLabel(series), "and never names the special")
        assertNull(WatchHistory.caughtUpNext(500))
        assertEquals(0L, WatchHistory.startPositionMs(episode(1, 1)), "the special's spot is not the series'")
    }

    @Test fun finishingASpecialLeavesTheShowWhereItWas() {
        report(episode(3, 5), 0.6)
        report(special(), 1.0)
        val s = WatchHistory.seriesEntry(500)!!
        assertEquals(3 to 5, s.season to s.episode)
        assertEquals(0.6, s.progress, 0.01, "still mid-episode")
        assertFalse(s.hidden, "still on Continue Watching")
        val sp = WatchHistory.recent().single { it.isSpecial }
        assertTrue(sp.hidden, "a finished special leaves the row, as a finished movie does")
        assertFalse(sp.upNext, "and never queues a next episode")
        assertTrue(Watched.special(500, "tmdb:9001"))
        assertNull(Watched.furthest(500), "a finished special is no progress through the series")
        assertFalse(Watched.episode(500, 1, 1), "and marks no episode")
    }

    @Test fun aSpecialResumesFromItsOwnEntry() {
        report(episode(3, 5), 0.6)
        report(special(), 0.25)
        assertEquals(15 * 60_000L, WatchHistory.startPositionMs(special()), "its own quarter of an hour")
        assertEquals(36 * 60_000L, WatchHistory.startPositionMs(episode(3, 5)), "and the series keeps its own spot")
    }

    @Test fun theCaughtUpRuleAsksAboutThatSpecialAlone() {
        // Caught up on the whole series: every new-episode surface still offers the special.
        report(episode(3, 5), 1.0)
        assertFalse(NewEpisodes.caughtUpSpecial(500, "tmdb:9001"), "finishing the series says nothing about a special")
        report(special(), 1.0)
        assertTrue(NewEpisodes.caughtUpSpecial(500, "tmdb:9001"), "only its own finish counts")
        assertFalse(NewEpisodes.caughtUpSpecial(500, "tmdb:9002"), "another special is still new")
    }

    @Test fun theRuleOnItsInputs() {
        fun entry(fraction: Double, upNext: Boolean = false, hidden: Boolean = false) = HistoryEntry(series, 1, 1,
            positionMs = (fraction * 100).toLong(), durationMs = 100, upNext = upNext, hidden = hidden, specialId = "tmdb:9001")
        assertTrue(NewEpisodes.pastSpecial(finished = true, entry = null))
        assertTrue(NewEpisodes.pastSpecial(false, entry(0.95)))
        assertTrue(NewEpisodes.pastSpecial(false, entry(0.4, hidden = true)), "taken off Continue Watching")
        assertFalse(NewEpisodes.pastSpecial(false, null), "never watched")
        assertFalse(NewEpisodes.pastSpecial(false, entry(0.5)), "in progress stays new")
    }

    @Test fun anIdThatChangesSourceStillFindsTheSameSpecialsRecords() {
        // TVmaze lists a special first; TMDB adds the same one later, which makes "tmdb:<id>" its preferred id. A
        // watch mark or a Continue Watching row written under the TVmaze id must still resolve to it, or the viewer's
        // finished special reads as unwatched and a part-watched row lingers as a stale card.
        val tv = AirTimes.parseSpecials(maze(SPECIAL_JSON))
        // First seen from TVmaze alone: the viewer starts it, then finishes it.
        val first = Specials.merge(500, emptyList(), tv).single()
        assertEquals("tvmaze:7001", first.id)
        Specials.put(500, listOf(first))
        report(special(first.id), 0.3)
        assertEquals("tv:500/sp:tvmaze:7001", WatchHistory.recent().single().key)
        report(special(first.id), 1.0)
        assertTrue(Watched.special(500, first.id))

        // TMDB now has a record of the same special, so the catalog prefers TMDB's id and keeps the old one.
        val merged = Specials.merge(500, listOf(tmdbEp(9001, 1, "A Special", "2026-10-31")), tv)
        Specials.put(500, merged)
        val now = merged.single()
        assertEquals("tmdb:9001", now.id)
        assertEquals(listOf("tvmaze:7001"), now.aliasIds)
        assertTrue(Watched.special(500, now.id), "still watched under the new id")
        assertEquals("tv:500/sp:tvmaze:7001", WatchHistory.specialEntry(500, now.id)?.key, "and still the same row")
        assertTrue(NewEpisodes.caughtUpSpecial(500, now.id), "so it is never announced as new all over again")
        assertEquals(listOf("tmdb:9001", "tvmaze:7001"), Specials.idsFor(500, now.id))
        assertEquals(listOf("tvmaze:7001", "tmdb:9001"), Specials.idsFor(500, "tvmaze:7001"))
    }

    @Test fun aSpecialGoesToTraktByTmdbEpisodeIdOrNotAtAll() {
        // Trakt numbers season 0 its own way, so sending a place in air order as a season-0 number would scrobble a
        // different special. The TMDB episode id is the one numbering both sides agree on.
        val sent = TraktItem(movie = false, tmdb = 500, specialId = "tmdb:9001")
        assertEquals(9001, sent.episodeTmdb)
        assertEquals("""{"episode":{"ids":{"tmdb":9001}}}""", sent.json().toString())
        assertEquals("tv:500/sp:tmdb:9001", sent.key, "its own paused point, never the show's")

        val tvmazeOnly = TraktItem(movie = false, tmdb = 500, specialId = "tvmaze:7001")
        assertEquals(0, tvmazeOnly.episodeTmdb, "TMDB has no record of it, so Trakt is told nothing")

        val episode = TraktItem(movie = false, tmdb = 500, season = 3, number = 5)
        assertEquals("""{"show":{"ids":{"tmdb":500}},"episode":{"season":3,"number":5}}""", episode.json().toString())
        assertEquals("tv:500", episode.key)
    }

    @Test fun thePlayerSavesASpecialToItsOwnRowAndObeysTheNinetySecondRule() {
        // The player turns what it is playing back into a request before core sees it. If that conversion drops the
        // special's id, every report lands on the SHOW's row: the series position is overwritten by a 30-minute
        // special, and the 90-second rule is bypassed because the show was already on the row.
        val sp = Specials.Special(500, "tmdb:9001", number = 1, name = "A Special", parentSeason = 1)
        Specials.put(500, listOf(sp))
        report(episode(3, 5), 0.6)

        val target = ContentPlaybackHooks.target(series, sp.episode())
        assertEquals(PlayKind.SPECIAL, target.kind)
        assertEquals("tmdb:9001", target.specialId)
        val back = ContentPlaybackHooks.request(target)
        assertTrue(back.isSpecial, "the identity survives the round trip")
        assertEquals("tv:500/sp:tmdb:9001", back.historyKey, "its own row, never the show's")

        // 30 seconds of a special, far in: not watching, so it joins nothing, and the show keeps its own spot.
        WatchHistory.report(back, 20 * 60_000L, 60 * 60_000L, 30_000, exact = true)
        assertEquals(listOf("tv:500"), WatchHistory.entries().map { it.key }, "90 s of real playback or nothing")
        assertEquals(3 to 5, WatchHistory.seriesEntry(500)!!.let { it.season to it.episode })
        WatchHistory.report(back, 20 * 60_000L, 60 * 60_000L, 91_000, exact = true)
        assertEquals(setOf("tv:500", "tv:500/sp:tmdb:9001"), WatchHistory.entries().map { it.key }.toSet())
        assertEquals(0.6, WatchHistory.seriesEntry(500)!!.progress, 0.01, "the show was never touched")
    }

    @Test fun twoSpecialsOfOneShowAreTwoCards() {
        // Both sit at 0/0, so a card keyed by show plus season plus episode would collapse them into one.
        fun entry(id: String) = HistoryEntry(series, 0, 0, episodeName = "A Special", positionMs = 30, durationMs = 100,
            specialId = id)
        val a = Personal.item(entry("tmdb:9001"))
        val b = Personal.item(entry("tvmaze:7002"))
        assertEquals("tv:500/sp:tmdb:9001", a.id)
        assertEquals("tv:500/sp:tvmaze:7002", b.id)
        assertTrue(a.id != b.id, "two part-watched specials are two cards, not one")
        assertEquals("A Special", a.subtitle, "named, never numbered")
    }

    @Test fun nothingAViewerSeesNamesASeasonZero() {
        assertEquals("A Special", EpisodeCode.label(EpisodeKind.SPECIAL, 1, 1, "A Special"))
        assertEquals("Specials", EpisodeCode.label(EpisodeKind.SPECIAL, 1, 1, ""), "with no title of its own")
        assertEquals("S2 E5", EpisodeCode.label(EpisodeKind.SERIES, 2, 5, "Some Episode"))
        assertEquals("Specials", EpisodeCode.place(EpisodeKind.SPECIAL, 1, 1))
        assertEquals("Season 2, Episode 5", EpisodeCode.place(EpisodeKind.SERIES, 2, 5))
        assertNull(EpisodeCode.maybe(EpisodeKind.SERIES, null, null), "a movie names no episode")
    }
}
