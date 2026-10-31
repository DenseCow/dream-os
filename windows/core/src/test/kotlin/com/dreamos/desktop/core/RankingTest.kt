package com.dreamos.desktop.core

import com.dreamos.desktop.core.TestSupport.movie
import com.dreamos.desktop.core.TestSupport.show
import com.dreamos.desktop.core.content.*
import com.dreamos.desktop.core.model.*
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.net.NetResponse
import com.dreamos.desktop.core.time.DreamTime
import kotlin.test.*

/** Rows: the anime rule, tidy, claims, Top 10, Taste ranking, Because You Watched and Top Picks seeds. */
class RankingTest {
    @BeforeTest fun setUp() {
        TestSupport.freshStore()
        DreamTime.fixedNow = java.time.LocalDate.parse("2026-10-30").atTime(20, 0).atZone(DreamTime.zone).toInstant().toEpochMilli()
    }

    @AfterTest fun tearDown() {
        DreamTime.fixedNow = null
        Net.fake = null
    }

    @Test fun animeIsJapaneseAnimationAndAsianAnimatedSeries() {
        assertTrue(RowMix.isAnime(show(1, genres = listOf(16, 10759), lang = "ja")))
        assertTrue(RowMix.isAnime(movie(2, genres = listOf(16), lang = "ja")))
        assertTrue(RowMix.isAnime(show(3, genres = listOf(16), lang = "zh")))
        assertFalse(RowMix.isAnime(show(4, genres = listOf(16, 10762), lang = "ko")), "a kids' cartoon isn't anime")
        assertFalse(RowMix.isAnime(movie(5, genres = listOf(16), lang = "zh")), "a Chinese CGI film isn't anime")
        assertFalse(RowMix.isAnime(movie(6, genres = listOf(16), lang = "en")))
        assertFalse(RowMix.isAnime(show(7, genres = listOf(18), lang = "ja")), "Japanese live action isn't anime")
    }

    @Test fun animeStaysInAnimeRowsWhileTheSettingIsOn() {
        val anime = show(1, genres = listOf(16), lang = "ja", votes = 5000)
        val drama = show(2)
        assertTrue(ContentPrefs.animeApart(), "on by default")
        assertEquals(listOf(drama), RowMix.tidy(listOf(anime, drama)))
        assertEquals(listOf(anime, drama), RowMix.tidy(listOf(anime, drama), anime = true))
        assertTrue(RowMix.animeRow("Anime Series") && RowMix.animeRow("Popular on Crunchyroll") && RowMix.animeRow("Because You Watched Frieren"))
        assertFalse(RowMix.animeRow("Trending Now"))
        ContentPrefs.setAnimeApart(false)
        assertEquals(listOf(anime, drama), RowMix.tidy(listOf(anime, drama)))
        assertEquals("&with_genres=16", Tmdb.withAnime("&with_genres=16&without_keywords=210024"))
    }

    @Test fun tidyDropsWhatNoRowShows() {
        val ok = movie(1)
        val adult = movie(2).copy(adult = true)
        val noPoster = movie(3, poster = false)
        val bad = movie(4, votes = 400, rating = 4.2)
        val obscureForeign = movie(5, lang = "tl", votes = 40)
        val famousForeign = movie(6, lang = "ko", votes = 9000)
        val noBackdrop = movie(7, backdrop = false)
        val out = RowMix.tidy(listOf(noBackdrop, ok, adult, noPoster, bad, obscureForeign, famousForeign))
        assertEquals(listOf(ok, famousForeign, noBackdrop), out, "art first, then the rest in order")
        assertEquals(listOf(ok), RowMix.tidy(listOf(ok, movie(9)), done = setOf("movie:9")), "finished titles leave rows")
    }

    @Test fun aMostlyForeignRowKeepsItsLanguage() {
        val list = (1..5).map { movie(it, lang = "ko", votes = 30) }
        assertEquals(5, RowMix.tidy(list).size)
    }

    @Test fun unreleasedTitlesStayOutOfCharts() {
        assertTrue(RowMix.released(movie(1, date = "2026-10-30")))
        assertFalse(RowMix.released(movie(2, date = "2026-12-18")))
        assertTrue(RowMix.released(movie(3, date = "")), "no date known counts as out")
    }

    @Test fun claimsKeepEachTitleInOneRow() {
        val claims = RowClaims(tidy = false)
        val a = (1..10).map { movie(it) }
        val first = claims.take("Trending Now", a, 5)
        assertEquals((1..5).toList(), first.map { it.id })
        val second = claims.take("New Releases", a)
        assertEquals((6..10).toList(), second.map { it.id })
        // A row reloaded keeps its own titles.
        assertEquals((1..5).toList(), claims.take("Trending Now", a, 5).map { it.id })
        // In Theaters always shows when most of its titles were taken.
        assertNotNull(claims.takeAtLeast("In Theaters", a.take(6), 6))
    }

    @Test fun topTenIsAlwaysTenAfterFiltering() {
        Tmdb.setKey("test")
        val day1 = (1..20).map { i ->
            when (i) {
                2 -> """{"id":$i,"media_type":"tv","name":"Anime $i","poster_path":"/p.jpg","genre_ids":[16],"original_language":"ja","first_air_date":"2025-01-01"}"""
                4 -> """{"id":$i,"media_type":"movie","title":"Future $i","poster_path":"/p.jpg","release_date":"2027-03-01"}"""
                6 -> """{"id":$i,"media_type":"movie","title":"No Art $i","release_date":"2024-01-01"}"""
                else -> """{"id":$i,"media_type":"movie","title":"Hit $i","poster_path":"/p.jpg","release_date":"2025-05-01"}"""
            }
        }
        val day2 = (21..40).map { """{"id":$it,"media_type":"movie","title":"Hit $it","poster_path":"/p.jpg","release_date":"2025-05-01"}""" }
        Net.fake = { _, url, _ ->
            when {
                "/trending/all/day" in url && "page=2" in url -> NetResponse(200, """{"results":[${day2.joinToString(",")}]}""", emptyMap())
                "/trending/all/day" in url -> NetResponse(200, """{"results":[${day1.joinToString(",")}]}""", emptyMap())
                else -> NetResponse(404, "", emptyMap())
            }
        }
        val top = RowMix.top10(RowClaims())
        assertEquals(10, top.size)
        assertEquals(listOf(1, 3, 5, 7, 8, 9, 10, 11, 12, 13), top.map { it.id }, "TMDB's order kept, anime / unreleased / art-less left out")
    }

    @Test fun tasteRanksButKeepsTheListsIntent() {
        val cold = Taste.rank(TasteProfile.COLD, (1..6).map { movie(it, votes = 2000, rating = 7.0) })
        assertEquals((1..6).toList(), cold.map { it.id }, "equal quality: the list's own order")
        val liked = TasteProfile(genre = mapOf(27 to 1.0), strength = 1.0)
        val list = (1..6).map { movie(it, genres = listOf(if (it == 6) 27 else 18), votes = 2000, rating = 7.0) }
        val ranked = Taste.rank(liked, list)
        assertTrue(ranked.indexOfFirst { it.id == 6 } < 5, "a liked genre moves up")
        assertTrue(Taste.quality(movie(1, votes = 90, rating = 7.9)) < Taste.quality(movie(2, votes = 10000, rating = 7.6)))
    }

    @Test fun franchisesSpreadOut() {
        assertEquals("john wick", Taste.stem("John Wick: Chapter 4"))
        assertEquals("harry potter", Taste.stem("Harry Potter and the Goblet of Fire"))
        val l = listOf(movie(1, "John Wick"), movie(2, "John Wick: Chapter 2"), movie(3, "Heat"))
        assertEquals(listOf(1, 3, 2), Taste.spread(l).map { it.id })
        assertTrue(Taste.ranks("Because You Watched Heat"))
        assertFalse(Taste.ranks("Top 10 Today"))
        assertFalse(Taste.ranks("Coming Soon"))
    }

    @Test fun tasteLearnsFromFinishedNeverFromAbandoned() {
        val now = DreamTime.now()
        val horror = movie(1, genres = listOf(27))
        val comedy = movie(2, genres = listOf(35))
        val history = listOf(
            HistoryEntry(horror, positionMs = 95, durationMs = 100, updatedAt = now, hidden = true),
            HistoryEntry(comedy, positionMs = 20, durationMs = 100, updatedAt = now, hidden = true), // removed unfinished
        )
        val p = Taste.build(history, emptyList(), "sig", now, lookup = false)
        assertEquals(1.0, p.genre[27])
        assertNull(p.genre[35], "a title removed before it was finished seeds nothing")
    }

    @Test fun becauseYouWatchedSeedsAreRealWatching() {
        val now = DreamTime.now()
        val h = listOf(
            HistoryEntry(movie(1), positionMs = 30 * 60_000L, durationMs = 100 * 60_000L, updatedAt = now - 1000),
            HistoryEntry(movie(2), positionMs = 60_000L, durationMs = 100 * 60_000L, updatedAt = now - 2000), // a sample
            HistoryEntry(movie(3), positionMs = 20 * 60_000L, durationMs = 100 * 60_000L, updatedAt = now - 3000, hidden = true), // removed
            HistoryEntry(movie(4), positionMs = 99, durationMs = 100, updatedAt = now - 40L * 86_400_000L), // too old
        )
        val seeds = BecauseYouWatched.pick(shows = false, history = h, now = now).entries
        assertEquals(listOf(1), seeds.map { it.title.id })
        assertEquals(listOf(MediaKind.MOVIE to 1, MediaKind.MOVIE to 4), HomePicks.pickSeeds(h, emptyList()), "Top Picks: finished counts at any age")
    }

    @Test fun topPicksBlendFavorsTitlesSeveralSeedsRecommend() {
        val a = listOf(movie(10), movie(11), movie(12))
        val b = listOf(movie(12), movie(13))
        val out = HomePicks.blend(listOf(a, b), skip = setOf("movie:11"))
        assertEquals(12, out.first().id)
        assertFalse(out.any { it.id == 11 })
    }

    @Test fun featuredSuitsTheTimeOfDayAndSkipsUnreleased() {
        val night = HomePicks.moment(20)
        assertEquals("Tonight", night.name)
        val list = listOf(movie(1, genres = listOf(35)), movie(2, genres = listOf(53)), movie(3, date = "2027-01-01", genres = listOf(53)))
        val out = HomePicks.rankFeatured(list, night, TasteProfile.COLD, 6)
        assertEquals(listOf(2, 1), out.map { it.id })
    }

    @Test fun tmdbSingleFoldsRepeatedParameters() {
        assertEquals("&with_genres=27,53&vote_count.gte=200&without_keywords=1,2",
            Tmdb.single("&with_genres=27&vote_count.gte=100&without_keywords=1&with_genres=53&vote_count.gte=200&without_keywords=2"))
        assertEquals("&release_date.lte=2020-01-01", Tmdb.single("&release_date.lte=2024-01-01&release_date.lte=2020-01-01"))
        assertEquals("", Tmdb.animeClause("/trending/all/day", ""))
        assertEquals("&without_keywords=210024", Tmdb.animeClause("/discover/tv", "&with_genres=16"))
        assertEquals("", Tmdb.animeClause("/discover/tv", "&with_genres=16&with_original_language=ja"))
    }
}
