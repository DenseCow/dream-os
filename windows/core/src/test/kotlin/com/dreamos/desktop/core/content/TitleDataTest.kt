package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.Scores
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.time.DreamTime
import com.dreamos.desktop.core.util.objects
import com.dreamos.desktop.core.util.a
import com.dreamos.desktop.core.util.parseJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.time.ZonedDateTime
import java.time.ZoneId
import kotlin.test.*

class TitleDataTest {
    private val zone = ZoneId.of("America/New_York")
    /** 2026-10-30 2:00 PM in New York. */
    private val now = ZonedDateTime.of(2026, 10, 30, 14, 0, 0, 0, zone).toInstant().toEpochMilli()

    @BeforeTest
    fun setUp() {
        DreamStore.use(DreamStore(Files.createTempDirectory("dream-test")))
        DreamTime.setZone(zone.id)
        DreamTime.fixedNow = now
    }

    @AfterTest
    fun tearDown() {
        DreamTime.fixedNow = null
    }

    private fun json(s: String): JsonObject = parseJson(s).jsonObject

    private val movieJson = """
    {
      "id": 603, "title": "The Matrix", "original_title": "The Matrix", "overview": "A hacker learns the truth.",
      "poster_path": "/p.jpg", "backdrop_path": "/b.jpg", "release_date": "1999-03-31", "vote_average": 8.2,
      "vote_count": 26000, "original_language": "en", "adult": false, "runtime": 136, "budget": 63000000,
      "revenue": 467200000, "status": "Released", "tagline": "Welcome to the Real World.", "imdb_id": "tt0133093",
      "genres": [{"id": 28, "name": "Action"}, {"id": 878, "name": "Science Fiction"}],
      "spoken_languages": [{"iso_639_1": "en", "english_name": "English"}],
      "production_countries": [{"iso_3166_1": "US", "name": "United States of America"}],
      "production_companies": [{"name": "Warner Bros. Pictures"}, {"name": "Village Roadshow Pictures"}],
      "belongs_to_collection": {"id": 2344, "name": "The Matrix Collection"},
      "videos": {"results": [
        {"site": "YouTube", "type": "Teaser", "key": "teaserKey"},
        {"site": "Vimeo", "type": "Trailer", "key": "vimeoKey"},
        {"site": "YouTube", "type": "Trailer", "key": "trailerKey"}
      ]},
      "watch/providers": {"results": {"US": {
        "flatrate": [
          {"provider_id": 1899, "provider_name": "Max", "logo_path": "/max.png", "display_priority": 2},
          {"provider_id": 192, "provider_name": "YouTube", "logo_path": "/yt.png", "display_priority": 1},
          {"provider_id": 2528, "provider_name": "YouTube TV", "logo_path": "/yttv.png", "display_priority": 3},
          {"provider_id": 1825, "provider_name": "HBO Max Amazon Channel", "display_priority": 4}
        ],
        "rent": [{"provider_id": 10, "provider_name": "Amazon Video"}, {"provider_id": 192, "provider_name": "YouTube"}],
        "buy": [{"provider_id": 2, "provider_name": "Apple TV Store"}]
      }}},
      "credits": {
        "cast": [
          {"id": 6384, "name": "Keanu Reeves", "character": "Neo", "profile_path": "/k.jpg"},
          {"id": 2975, "name": "Laurence Fishburne", "character": "Morpheus", "profile_path": null},
          {"id": 9, "name": "", "character": "Nobody"}
        ],
        "crew": [
          {"job": "Director", "name": "Lana Wachowski"}, {"job": "Director", "name": "Lilly Wachowski"},
          {"job": "Screenplay", "name": "Lana Wachowski"}, {"job": "Producer", "name": "Joel Silver"}
        ]
      },
      "recommendations": {"results": [
        {"id": 604, "media_type": "movie", "title": "The Matrix Reloaded", "poster_path": "/r.jpg", "release_date": "2003-05-15", "vote_count": 10000},
        {"id": 603, "media_type": "movie", "title": "The Matrix"},
        {"id": 99, "media_type": "movie", "title": "Adult", "adult": true},
        {"id": 1399, "media_type": "tv", "name": "Game of Thrones", "first_air_date": "2011-04-17"}
      ]},
      "external_ids": {"imdb_id": "tt0133093"},
      "release_dates": {"results": [
        {"iso_3166_1": "GB", "release_dates": [{"type": 3, "certification": "15", "release_date": "1999-06-11T00:00:00.000Z"}]},
        {"iso_3166_1": "US", "release_dates": [
          {"type": 3, "certification": "R", "release_date": "1999-03-31T00:00:00.000Z", "descriptors": ["Sci-Fi Violence", "Brief Language"]},
          {"type": 4, "certification": "", "release_date": "1999-09-21T00:00:00.000Z"}
        ]}
      ]},
      "images": {
        "logos": [
          {"iso_639_1": "en", "file_path": "/logo.svg"},
          {"iso_639_1": "de", "file_path": "/logo-de.png"},
          {"iso_639_1": "en", "file_path": "/logo-en.png"}
        ],
        "backdrops": [
          {"iso_639_1": null, "file_path": "/plain.jpg", "vote_average": 9.0, "vote_count": 50},
          {"iso_639_1": "en", "file_path": "/named-a.jpg", "vote_average": 5.4, "vote_count": 3},
          {"iso_639_1": "en", "file_path": "/named-b.jpg", "vote_average": 5.6, "vote_count": 2}
        ]
      }
    }
    """

    private val showJson = """
    {
      "id": 1396, "name": "Breaking Bad", "original_name": "Breaking Bad", "first_air_date": "2008-01-20",
      "last_air_date": "2013-09-29", "vote_average": 8.9, "vote_count": 15000, "original_language": "en",
      "origin_country": ["US"], "number_of_seasons": 3, "number_of_episodes": 33, "status": "Ended",
      "episode_run_time": [45, 47, 58],
      "genres": [{"id": 18, "name": "Drama"}],
      "created_by": [{"name": "Vince Gilligan"}], "networks": [{"name": "AMC"}],
      "content_ratings": {"results": [{"iso_3166_1": "US", "rating": "TV-MA", "descriptors": []}]},
      "last_episode_to_air": {"season_number": 3, "episode_number": 4, "runtime": 47},
      "next_episode_to_air": null,
      "seasons": [
        {"season_number": 0, "name": "Specials", "episode_count": 2, "air_date": null},
        {"season_number": 1, "name": "Season 1", "episode_count": 7, "air_date": "2008-01-20", "poster_path": "/s1.jpg"},
        {"season_number": 2, "name": "Season 2", "episode_count": 13, "air_date": "2009-03-08"},
        {"season_number": 3, "name": "Season 3", "episode_count": 13, "air_date": "2010-03-21"},
        {"season_number": 4, "name": "Season 4", "episode_count": 0, "air_date": "2027-02-01"}
      ]
    }
    """

    // ---- details ----

    @Test
    fun movieDetailsParse() {
        val p = Details.parse(json(movieJson), MediaKind.MOVIE)!!
        val d = p.details
        assertEquals("The Matrix", d.title.name)
        assertEquals("movie:603", d.title.key)
        assertEquals(listOf(28, 878), d.title.genreIds)
        assertEquals(listOf("Action", "Science Fiction"), d.genres)
        assertEquals(136, d.runtime)
        assertEquals("2 hr 16 min", d.runtimeWords)
        assertEquals("trailerKey", d.trailerKey)
        assertEquals("tt0133093", d.imdbId)
        // YouTube's movie store is left out; YouTube TV stays.
        assertEquals(listOf(1899, 2528, 1825), d.providers.map { it.id })
        assertTrue(d.providers.none { it.name == "YouTube" })
        assertEquals(listOf("Keanu Reeves", "Laurence Fishburne"), d.cast.map { it.name })
        assertEquals(6384, d.cast[0].id)
        assertEquals(listOf("movie:604", "tv:1399"), d.recommendations.map { it.key })
        assertEquals("/logo-en.png", d.logoPath)
        assertEquals("/named-b.jpg", p.keyArt)
        assertEquals("R", p.usCert)
        assertEquals("R", p.anyCert)
    }

    @Test
    fun movieInfoParse() {
        val i = Details.parse(json(movieJson), MediaKind.MOVIE)!!.details.info!!
        assertEquals("1999-03-31", i.premiere)
        assertEquals("1999-03-31", i.theatrical)
        assertEquals("1999-09-21", i.digital)
        assertEquals("R", i.cert)
        assertEquals("Sci-Fi Violence, Brief Language", i.certNote)
        assertEquals(listOf("Lana Wachowski", "Lilly Wachowski"), i.directors)
        assertEquals(listOf("Lana Wachowski"), i.writers)
        assertEquals("English", i.language)
        assertEquals("", i.originalTitle)
        assertEquals(listOf("United States"), i.countries)
        assertEquals(2344, i.collectionId)
        // Known services by Dream OS's name; YouTube's store and add-on channels left out; YouTube TV stays.
        assertEquals(listOf("HBO Max", "YouTube TV"), i.streaming)
        assertEquals(listOf("Amazon", "Apple TV"), i.rentBuy)
        assertEquals("Streaming on HBO Max, YouTube TV\nRent or Buy on Amazon, Apple TV", i.watchWords())
        assertEquals("In Theaters March 31, 1999  ·  Digital September 21, 1999", i.releasedWords())
        val cells = i.cells(Scores(tomatometer = 83, audienceBadge = "IMDb 8.7"))
        assertEquals("Released", cells.first().label)
        assertEquals("\$63 million", cells.first { it.label == "Budget" }.value)
        assertEquals("\$467.2 million", cells.first { it.label == "Box Office" }.value)
        assertEquals("Part of the Matrix Collection", cells.first { it.label == "Collection" }.value)
        assertEquals("R  ·  Sci-Fi Violence, Brief Language", cells.first { it.label == "Rated" }.value)
        assertEquals("Rotten Tomatoes 83%  ·  IMDb 8.7  ·  TMDB 8.2", cells.first { it.label == InfoLabels.RATINGS }.value)
        assertTrue(cells.none { it.value.contains('—') }, "no em dashes in viewer text")
    }

    @Test
    fun ratingsWordsLeaveOutTmdbBelow150Votes() {
        val i = Details.parse(json(movieJson), MediaKind.MOVIE)!!.details.info!!.copy(votes = 149)
        assertEquals("Rotten Tomatoes 60%", i.ratingsWords(Scores(tomatometer = 60)))
    }

    @Test
    fun showDetailsParse() {
        val d = Details.parse(json(showJson), MediaKind.TV)!!.details
        assertEquals(47, d.runtime) // the median of TMDB's episode lengths
        assertEquals("3 Seasons", d.runtimeWords)
        assertEquals(listOf(0, 1, 2, 3, 4), d.seasons.map { it.number })
        assertEquals("", d.seasons[0].airDate) // "air_date": null never reads as the text "null"
        val i = d.info!!
        assertTrue(i.isShow)
        assertEquals("TV-MA", i.cert)
        assertEquals(listOf("Vince Gilligan"), i.creators)
        assertEquals(listOf("AMC"), i.networks)
        val cells = i.cells()
        assertEquals("January 20, 2008", cells.first { it.label == "First Aired" }.value)
        assertEquals("3 Seasons, 33 Episodes", cells.first { it.label == "Seasons" }.value)
        assertEquals("47 min", cells.first { it.label == InfoLabels.EPISODE_LENGTH }.value)
        assertEquals("52 min", i.episodeLengthWords(52))
    }

    @Test
    fun showAirStateFromDetails() {
        val s = Details.airState(json(showJson), DreamTime.todayYmd())
        assertEquals(3, s.lastSeason)
        assertEquals(4, s.lastEpisode)
        assertEquals(listOf(0, 7, 13), s.seasonCounts) // index = season number
        assertEquals("2027-02-01", s.nextAir) // no next episode: the next announced season
    }

    // ---- episodes: the Upcoming split ----

    @Test
    fun upcomingSplit() {
        val raw = json("""{"episodes": [
            {"episode_number": 1, "name": "Pilot", "air_date": "2026-10-23", "runtime": 50},
            {"episode_number": 2, "name": "Tonight", "air_date": "2026-10-30"},
            {"episode_number": 3, "name": "Earlier Today", "air_date": "2026-10-30"},
            {"episode_number": 4, "name": "Episode 4", "air_date": "2026-11-06"},
            {"episode_number": 5, "name": "TBA", "air_date": null},
            {"episode_number": 6, "name": "Early Drop", "air_date": "2026-11-13"}
        ]}""").a("episodes").objects()
        val ninePm = ZonedDateTime.of(2026, 10, 30, 21, 0, 0, 0, zone).toInstant().toEpochMilli()
        val maze = listOf(
            AirTimes.MazeEpisode(2, 2, "Tonight", "2026-10-30", ninePm),
            AirTimes.MazeEpisode(2, 4, "The Reckoning", "2026-11-06", null),
            AirTimes.MazeEpisode(2, 5, "Finale", "2026-11-20", null),
        )
        val eps = Details.split(77, 2, raw, maze, now) { show, s, e -> show == 77 && s == 2 && e == 6 }
        assertEquals(6, eps.size)
        with(eps[0]) { assertFalse(upcoming); assertFalse(outEarly); assertEquals(50, runtime) }
        // Dated today, but TVmaze says it airs at 9 PM: still to come, with its exact instant.
        with(eps[1]) { assertTrue(upcoming); assertEquals(ninePm, airsAt) }
        // Dated today with no exact time: out.
        assertFalse(eps[2].upcoming)
        // A stand-in name takes TVmaze's real title; the listed date never changes.
        with(eps[3]) { assertTrue(upcoming); assertEquals("The Reckoning", name); assertEquals("2026-11-06", airDate) }
        // No TMDB day: TVmaze's.
        with(eps[4]) { assertTrue(upcoming); assertEquals("2026-11-20", airDate); assertEquals("Finale", name) }
        // Ahead of its day, but the index already has it: out early (it plays), date untouched.
        with(eps[5]) { assertFalse(upcoming); assertTrue(outEarly); assertEquals("2026-11-13", airDate) }
    }

    @Test
    fun upcomingSplitAfterTheExactInstant() {
        val raw = json("""{"episodes": [{"episode_number": 2, "name": "Tonight", "air_date": "2026-10-30"}]}""").a("episodes").objects()
        val noon = ZonedDateTime.of(2026, 10, 30, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val eps = Details.split(77, 2, raw, listOf(AirTimes.MazeEpisode(2, 2, "", "2026-10-30", noon)), now, null)
        assertFalse(eps[0].upcoming)
        assertEquals(noon, eps[0].airsAt)
    }

    @Test
    fun tvmazeStamps() {
        val o = json("""{"airtime": "21:00", "airstamp": "2026-10-31T01:00:00+00:00"}""")
        assertEquals(java.time.Instant.parse("2026-10-31T01:00:00Z").toEpochMilli(), AirTimes.exact(o))
        // No airtime: the airstamp is a noon stand-in, not an announced time.
        assertNull(AirTimes.exact(json("""{"airtime": "", "airstamp": "2026-10-31T16:00:00+00:00"}""")))
    }

    // ---- scores ----

    private fun title(rating: Double, votes: Int) = Title(1, MediaKind.MOVIE, "X", rating = rating, votes = votes)

    @Test
    fun audienceBadgeRules() {
        assertEquals("IMDb 6.5", Ratings.audience("6.5", 7.1, 5000)) // IMDb first
        assertEquals("TMDB 7.1", Ratings.audience(null, 7.08, 150)) // TMDB once 150 voted
        assertNull(Ratings.audience(null, 7.1, 149))
        assertNull(Ratings.audience("N/A", 0.0, 0))
        assertNull(Ratings.audience(null, 0.0, 9000))
        val s = Ratings.scoresOf(Ratings.Entry(rt = 94, imdb = "8.5"), title(8.1, 900), "PG-13")
        assertEquals(94, s.tomatometer)
        assertEquals(Scores.Tomato.FRESH, s.tomato)
        assertEquals("IMDb 8.5", s.audienceBadge)
        assertEquals("PG-13", s.certification)
        assertEquals(Scores.Tomato.ROTTEN, Ratings.scoresOf(Ratings.Entry(rtWeb = 59), title(0.0, 0)).tomato)
        assertEquals("TMDB 8.1", Ratings.scoresOf(null, title(8.1, 900)).audienceBadge)
        for (badge in listOfNotNull(s.audienceBadge, Ratings.audience(null, 7.1, 200))) assertFalse(badge.contains('★'), "never stars")
    }

    @Test
    fun omdbAnswers() {
        val found = json("""{"Response": "True", "imdbRating": "7", "Ratings": [
            {"Source": "Internet Movie Database", "Value": "7.0/10"}, {"Source": "Rotten Tomatoes", "Value": "88%"}]}""")
        assertEquals(88 to "7.0", Ratings.parseOmdb(found))
        assertEquals(null to null, Ratings.parseOmdb(json("""{"Response": "True", "imdbRating": "N/A"}""")))
        assertNull(Ratings.parseOmdb(json("""{"Response": "False", "Error": "Movie not found!"}""")))
    }

    @Test
    fun omdbKeyIsStored() {
        assertFalse(Ratings.hasKey())
        Ratings.setKey("  abc123 ")
        assertEquals("abc123", Ratings.key())
        assertEquals("abc123", DreamStore.shared.file("omdb").getString("key"))
    }

    @Test
    fun rottenTomatoesSearch() {
        val html = """
            <search-page-result type="movie"><ul>
              <search-page-media-row release-year="1999" tomatometer-score="83">
                <a href="https://www.rottentomatoes.com/m/matrix" slot="title"> The Matrix </a></search-page-media-row>
              <search-page-media-row release-year="2003" tomatometer-score="74">
                <a href="https://www.rottentomatoes.com/m/the_matrix_reloaded" slot="title">The Matrix Reloaded</a></search-page-media-row>
            </ul></search-page-result>
            <search-page-result type="tvSeries"><ul>
              <search-page-media-row startyear="1999" tomatometerscore="10">
                <a href="https://www.rottentomatoes.com/tv/matrix" slot="title">Matrix</a></search-page-media-row>
            </ul></search-page-result>"""
        val movie = Title(603, MediaKind.MOVIE, "The Matrix", year = "1999")
        assertEquals(RtWeb.Match(83, "https://www.rottentomatoes.com/m/matrix"), RtWeb.pick(html, movie))
        assertEquals(10, RtWeb.pick(html, Title(1, MediaKind.TV, "Matrix", year = "2000"))?.score)
        assertNull(RtWeb.pick(html, Title(2, MediaKind.MOVIE, "The Matrix", year = "2010")))
        assertEquals(97, RtWeb.criticsScore("""x "criticsScore":{"certified":true,"score":"97"} y"""))
    }

    // ---- people ----

    @Test
    fun bioEndsOnAFullSentence() {
        val bio = "Keanu Charles Reeves is a Canadian actor. He was born in Beirut, Lebanon. Reeves grew up in Toronto " +
            "with his mother, and J. Smith taught him hockey there for many years before he left to act in Hollywood."
        val lead = Details.bioLead(bio, 120)
        assertEquals("Keanu Charles Reeves is a Canadian actor. He was born in Beirut, Lebanon.", lead)
        // A lone initial ("J. ") is not a sentence's end.
        assertEquals("Keanu Charles Reeves is a Canadian actor. He was born in Beirut, Lebanon.", Details.bioLead(bio, 175))
        // Short enough: whole, line breaks folded.
        assertEquals("One line. Two lines.", Details.bioLead("One line.\n\nTwo lines.", 260))
        // No sentence end in reach: whole words and an ellipsis.
        assertEquals("Averyveryverylongwordthatgoesonandon and then some more…",
            Details.bioLead("Averyveryverylongwordthatgoesonandon and then some more words follow here.", 60))
    }

    @Test
    fun personFacts() {
        val j = json("""{"id": 6384, "name": "Keanu Reeves", "birthday": "1964-09-02", "deathday": null,
            "place_of_birth": "Beirut, Lebanon", "known_for_department": "Acting", "profile_path": "/k.jpg",
            "biography": "Keanu Reeves is a Canadian actor.",
            "combined_credits": {"cast": [
              {"id": 603, "media_type": "movie", "title": "The Matrix", "poster_path": "/m.jpg", "vote_count": 26000, "character": "Neo"},
              {"id": 5, "media_type": "tv", "name": "A Talk Show", "poster_path": "/t.jpg", "vote_count": 900, "genre_ids": [10767]},
              {"id": 6, "media_type": "movie", "title": "Himself", "poster_path": "/h.jpg", "vote_count": 900, "character": "Himself"},
              {"id": 7, "media_type": "movie", "title": "No Poster", "vote_count": 900},
              {"id": 245891, "media_type": "movie", "title": "John Wick", "poster_path": "/j.jpg", "vote_count": 19000, "character": "John Wick"}
            ]}}""")
        val p = Details.parsePerson(6384, j)
        assertEquals("Born September 2, 1964  ·  Beirut, Lebanon", p.meta)
        assertEquals("Acting", p.department)
        assertEquals(listOf("movie:603", "movie:245891"), p.knownFor.map { it.key })
        val died = Details.parsePerson(1, json("""{"name": "A", "birthday": "1930-01-05", "deathday": "2020-01-01"}"""))
        assertEquals("January 5, 1930 – January 1, 2020", died.meta)
    }

    @Test
    fun youTubeStoreLeftOut() {
        assertNull(TitleInfoParse.providerName(192, "YouTube"))
        assertNull(TitleInfoParse.providerName(188, "YouTube Premium"))
        assertEquals("YouTube TV", TitleInfoParse.providerName(2528, "YouTube TV"))
        assertEquals("Paramount+", TitleInfoParse.providerName(9999, "Paramount Plus Essential"))
    }

    @Test
    fun dateOnlyDaysNeverShift() {
        DreamTime.setZone("Pacific/Kiritimati") // UTC+14
        assertEquals("March 1, 2024", DreamTime.longDate("2024-03-01"))
        DreamTime.setZone("Pacific/Pago_Pago") // UTC-11
        assertEquals("March 1, 2024", DreamTime.longDate("2024-03-01"))
    }
}
