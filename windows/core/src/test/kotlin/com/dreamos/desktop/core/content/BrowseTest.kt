package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.CatalogPage
import com.dreamos.desktop.core.model.DiscoverFilter
import com.dreamos.desktop.core.model.DiscoverSort
import com.dreamos.desktop.core.model.Genre
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.time.DreamTime
import com.dreamos.desktop.core.util.parseJson
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.*

/** Browse's content logic without the network: Discover queries, the anime rule, genre shelves, genre search. */
class BrowseTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = ZonedDateTime.of(2026, 10, 30, 14, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val anime = "without_keywords=${Tmdb.ANIME_KEYWORD}"

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

    private fun count(s: String, part: String) = s.windowed(part.length).count { it == part }

    // ---- Discover ----

    @Test
    fun clauseAndAnimeKeyword() {
        assertEquals("&with_genres=27", Discover.clause(MediaKind.MOVIE, 27))
        // Animation that isn't Japanese leaves anime out by its keyword; Japanese animation is anime's own.
        assertEquals("&with_genres=16&$anime", Discover.clause(MediaKind.MOVIE, 16))
        assertEquals("&with_genres=16", Discover.clause(MediaKind.MOVIE, 16, "ja"))
        assertEquals("&with_keywords=315058|256183|12339|325319|313454", Discover.clause(MediaKind.TV, Discover.TV_HORROR))
        assertNull(Discover.clause(MediaKind.MOVIE, 0))
        assertEquals("&with_genres=16&with_original_language=ja", Discover.clause(MediaKind.TV, Discover.ANIME))
    }

    @Test
    fun animeRuleOnDiscoverQueries() {
        val horror = Discover.query(DiscoverFilters(type = MediaKind.TV, genre = Discover.TV_HORROR))
        assertEquals("&$anime", Tmdb.animeClause("/discover/tv", horror))
        val animeWall = Discover.query(DiscoverFilters(type = MediaKind.TV, genre = Discover.ANIME))
        assertEquals("", Tmdb.animeClause("/discover/tv", animeWall))
        val crunchy = Discover.query(DiscoverFilters(type = MediaKind.TV, service = "Crunchyroll"))
        assertEquals("", Tmdb.animeClause("/discover/tv", crunchy))
        assertTrue(Discover.wantsAnime(DiscoverFilters(genre = 16, lang = "ja")))
        assertTrue(Discover.wantsAnime(DiscoverFilters(service = "Crunchyroll")))
        assertFalse(Discover.wantsAnime(DiscoverFilters(genre = 16)))
        // Every non-anime /discover URL carries the keyword exactly once, even when the category already wrote it in.
        val url = Tmdb.url("/discover/movie", Discover.query(DiscoverFilters(genre = 16)))
        assertEquals(1, count(url, "without_keywords="))
        assertTrue(url.contains(anime))
        // Keep Anime in Anime Sections off: the keyword is gone everywhere.
        ContentPrefs.setAnimeApart(false)
        assertFalse(Tmdb.url("/discover/movie", Discover.query(DiscoverFilters(genre = 16))).contains("$anime"))
        ContentPrefs.setAnimeApart(true)
    }

    @Test
    fun singleFoldsCategoryAndRowClauses() {
        // An Adult Animation row on top of the gems' left-out genres: one without_genres, both lists kept.
        val q = Discover.clause(MediaKind.MOVIE, Discover.ADULT_ANIMATION_MOVIE)!! + "&without_genres=10770&vote_count.gte=40&vote_count.gte=150"
        val folded = Tmdb.single(q + "&without_keywords=${Tmdb.ANIME_KEYWORD}")
        assertEquals(1, count(folded, "without_genres="))
        assertTrue(folded.contains("without_genres=10751,10770"))
        assertEquals(1, count(folded, "without_keywords="))
        assertTrue(folded.contains("vote_count.gte=150"))
        assertFalse(folded.contains("vote_count.gte=40"))
        // A decade row on a decade category: the dates fold to the tighter window.
        val decade = GenreSpec.find("’80s Classics")!!
        val row = GenreShelves.curated(decade).first { it.title == "’80s Action" }
        val movie = Tmdb.single(row.query(MediaKind.MOVIE)!! + "&primary_release_date.gte=1985-01-01")
        assertTrue(movie.contains("primary_release_date.gte=1985-01-01"))
        assertTrue(movie.contains("primary_release_date.lte=1989-12-31"))
        assertTrue(movie.contains("with_genres=28"))
    }

    @Test
    fun queryBySort() {
        val soon = Discover.query(DiscoverFilters(sort = DiscoverSort.COMING_SOON))
        assertTrue(soon.contains("primary_release_date.gte=2026-10-31"), soon)
        assertTrue(soon.contains("primary_release_date.lte=${DreamTime.ymdPlus(150)}"), soon)
        val top = Discover.query(DiscoverFilters(type = MediaKind.TV, genre = 18, sort = DiscoverSort.TOP_RATED))
        assertTrue(top.contains("vote_count.gte=100") && top.contains("first_air_date.gte=2001-10-30"), top)
        val decade = Discover.query(DiscoverFilters(year = "1990s", rating = 7))
        assertTrue(decade.contains("primary_release_date.gte=1990-01-01&primary_release_date.lte=1999-12-31"), decade)
        assertTrue(decade.contains("vote_average.gte=7") && decade.contains("vote_count.gte=100"), decade)
        val older = Discover.query(DiscoverFilters(year = "older", runtime = 1, lang = "fr"))
        assertTrue(older.contains("lte=1969-12-31") && older.contains("with_runtime.lte=89") && older.contains("with_original_language=fr"))
        val netflix = Discover.query(DiscoverFilters(service = "Prime Video"))
        assertTrue(netflix.contains("with_watch_providers=9|119&watch_region=US"), netflix)
    }

    @Test
    fun modelFilterMapsBothWays() {
        val f = DiscoverFilters.of(DiscoverFilter(kind = MediaKind.TV, genre = 35, sort = DiscoverSort.NEWEST, decade = 1990, service = 8))
        assertEquals("1990s", f.year)
        assertEquals("Netflix", f.service)
        assertEquals(MediaKind.TV, f.type)
        assertEquals(DiscoverFilter(kind = MediaKind.TV, genre = 35, sort = DiscoverSort.NEWEST, decade = 1990, service = 8), f.toModel())
        assertEquals("older", DiscoverFilters.of(DiscoverFilter(decade = 1950)).year)
    }

    @Test
    fun genresCarryAcrossTypes() {
        assertEquals(Discover.TV_HORROR, Discover.mapGenre(27, MediaKind.TV))
        assertEquals(27, Discover.mapGenre(Discover.TV_HORROR, MediaKind.MOVIE))
        assertEquals(28, Discover.mapGenre(10759, MediaKind.MOVIE))
        assertEquals(0, Discover.mapGenre(10764, MediaKind.MOVIE))
        val f = DiscoverFilters(genre = 878, runtime = 2)
        f.switchType(MediaKind.TV)
        assertEquals(10765, f.genre)
        assertEquals(0, f.runtime)
        assertEquals(21, Discover.tasteCategories().size)
        assertFalse(Discover.discoverCategories(MediaKind.MOVIE).any { it.name == "Reality" })
    }

    @Test
    fun savedFiltersPerProfile() {
        Discover.save(DiscoverFilters(type = MediaKind.TV, genre = 18, sort = DiscoverSort.TOP_RATED))
        val back = Discover.load()
        assertEquals(MediaKind.TV, back.type)
        assertEquals(18, back.genre)
        assertEquals(DiscoverSort.TOP_RATED, back.sort)
    }

    // ---- genre shelves ----

    @Test
    fun mergeReplacesKeywordsAndKeepsCartoonsOut() {
        val horror = GenreSpec.find("Horror")!!
        val tv = GenreShelves.clause(horror, MediaKind.TV)!!
        val zombies = GenreShelves.merge(tv, "&with_keywords=12377")
        assertEquals("&with_keywords=12377&without_genres=16,10751,10762", zombies)
        assertEquals("&with_genres=27,35", GenreShelves.merge("&with_genres=27", "&with_genres=35"))
    }

    @Test
    fun genrePageRows() {
        val horror = GenreSpec.find("Horror")!!
        val names = GenreShelves.page(horror, listOf("Netflix", "Hulu")).map { it.title }
        assertEquals(listOf("Trending Horror", "New Releases", "Horror Series", "Critically Acclaimed Horror"), names.take(4))
        assertEquals("Horror on Netflix", names[7])
        assertTrue("Zombies" in names && "Hidden Horror Gems" in names)
        assertEquals(listOf("’80s Horror Classics", "’90s Horror Classics"), names.takeLast(2))
        // A decade's page: its classics lead, no trending or new.
        val eighties = GenreShelves.page(GenreSpec.find("’80s Classics")!!, emptyList()).map { it.title }
        assertEquals(listOf("Popular ’80s Classics", "’80s Blockbusters", "’80s TV Classics", "Top Rated ’80s Classics"), eighties.take(4))
        // The PC's services: Home's first six.
        assertEquals(listOf("Netflix", "Hulu", "Disney+", "HBO Max", "Prime Video", "Apple TV"), ServicePages.homeServices())
        assertEquals(listOf(MediaKind.TV, MediaKind.MOVIE), GenreShelves.types(GenreSpec.find("K-Drama")!!))
        assertEquals(listOf(MediaKind.TV), GenreShelves.types(GenreSpec.find("Reality")!!))
    }

    @Test
    fun claimsKeepAnimeOnlyInAnimeSections() {
        val anime = Title(1, MediaKind.TV, "Frieren", posterPath = "/p", backdropPath = "/b", genreIds = listOf(16), lang = "ja", votes = 2000, rating = 8.8)
        val drama = Title(2, MediaKind.TV, "Severance", posterPath = "/p", backdropPath = "/b", genreIds = listOf(18), lang = "en", votes = 2000, rating = 8.4)
        val list = listOf(anime, drama)
        assertEquals(listOf(drama), BrowsePages.claimsFor(CatalogPage.Movies).take("Popular on Netflix", list))
        assertEquals(2, BrowsePages.claimsFor(CatalogPage.Anime).take("Trending Anime", list).size)
        assertEquals(2, BrowsePages.claimsFor(CatalogPage.GenrePage(Genre("chip:tv:-1", "Anime"))).take("Popular in Anime", list).size)
        assertEquals(2, BrowsePages.claimsFor(CatalogPage.GenrePage(GenreSpec.find("Anime")!!.toGenre())).take("Trending Anime", list).size)
        assertEquals(2, BrowsePages.claimsFor(CatalogPage.Service(ServicePages.find("Crunchyroll")!!)).take("Popular on Crunchyroll", list).size)
    }

    @Test
    fun chipsAndPages() {
        val chips = BrowsePages.chips(MediaKind.MOVIE)
        assertEquals("chip:movie:28", chips.first().id)
        assertEquals(MediaKind.TV to Discover.SITCOM, BrowsePages.parseChip("chip:tv:${Discover.SITCOM}"))
        assertNull(BrowsePages.parseChip("Horror"))
        val claims = RowClaims()
        val rows = BrowsePages.chipPage(MediaKind.TV, 80, claims).map { it.name }
        assertEquals(listOf("Popular in Crime", "New Releases", "New Episodes", "Top Rated", "Fan Favorites", "Hidden Gems", "2010s Hits", "2000s Hits", "’90s Classics"), rows)
        val movies = BrowsePages.moviesPage(MediaKind.MOVIE, RowClaims()).map { it.name }
        assertEquals(listOf("Trending This Week", "Top 10 Today", "New Releases", "In Theaters", "Coming Soon", "Critically Acclaimed", "Hidden Gems"), movies.take(7))
        // October 30: Halloween is in season and comes before the services.
        assertEquals("Halloween Movies", movies[7])
        assertEquals("Popular on Netflix", movies[8])
        assertTrue("Anime Movies" in movies && movies.last() == "2000s Hits")
        assertEquals(movies.size, movies.toSet().size)
        val shows = BrowsePages.moviesPage(MediaKind.TV, RowClaims()).map { it.name }
        assertTrue("New Episodes" in shows && "Bingeworthy Shows" in shows && "In Theaters" !in shows)
        val spec = ServicePages.find("Netflix")!!
        assertEquals(listOf(8), spec.providerIds)
        val svc = ServicePages.servicePage(spec, RowClaims()).map { it.name }
        assertEquals(listOf("Popular on Netflix", "Netflix Originals", "New Releases", "TV Shows", "Movies", "Critically Acclaimed", "Hidden Gems"), svc.take(7))
        assertEquals("Halloween Favorites on Netflix", svc[7])
        assertFalse(ServicePages.servicePage(ServicePages.find("Tubi")!!, RowClaims()).any { it.name == "Tubi Originals" })
    }

    // ---- genre search ----

    @Test
    fun genreSearchMatches() {
        val horrorMovies = GenreSearch.match("horror movies")!!
        assertEquals("Horror", horrorMovies.target.spec.name)
        assertTrue(horrorMovies.movies && !horrorMovies.shows)
        assertEquals("Horror Movies", GenreSearch.heading(horrorMovies, MediaKind.MOVIE))
        val scary = GenreSearch.match("scary shows")!!
        assertTrue(scary.shows && !scary.movies)
        assertEquals("Horror Shows", GenreSearch.heading(scary, MediaKind.TV))
        val romcom = GenreSearch.match("rom com")!!
        assertEquals("Comedy", romcom.target.spec.name)
        assertEquals("Romantic Comedies", romcom.target.shelf)
        assertTrue(romcom.movies && romcom.shows)
        assertEquals("Romantic Comedies on TV", GenreSearch.heading(romcom, MediaKind.TV))
        val combo = GenreSearch.match("anime horror")!!
        assertEquals("Anime Horror", combo.target.label)
        assertTrue(combo.target.anime)
        assertEquals("’80s Classics", GenreSearch.match("80s")!!.target.spec.name)
        assertEquals("Documentary", GenreSearch.match("docu")!!.target.spec.name)
        // Reality has no movies: "reality movies" falls back to its shows.
        val reality = GenreSearch.match("reality movies")!!
        assertTrue(reality.shows && !reality.movies)
        // Title searches stay title searches.
        assertNull(GenreSearch.match("war of the worlds"))
        assertNull(GenreSearch.match("her"))
        assertEquals(listOf("Horror"), GenreSearch.suggest("hor", max = 1))
        assertEquals(listOf(GenreSpec.find("Comedy")!!), GenreSearch.specs(romcom))
    }

    // ---- words ----

    @Test
    fun rowNamesHaveNoEmDashes() {
        val specs = GenreSpec.SHORTCUTS + GenreSpec.CATEGORIES + GenreSpec.DECADES
        val names = specs.flatMap { s ->
            listOf(s.name, s.plural, s.series, s.fresh, s.rowTitle(MediaKind.MOVIE), s.rowTitle(MediaKind.TV)) +
                GenreShelves.page(s, ServicePages.HOME_SERVICES).map { it.title }
        } + BrowsePages.MOVIE_ROWS.values + BrowsePages.TV_ROWS.values + BrowsePages.MOVIE_CHIPS.map { it.first } +
            SeasonCatalog.ALL.flatMap { h -> listOf(h.title, h.kicker, h.tagline) + h.rows.map { it.title } }
        assertTrue(names.none { '—' in it }, names.filter { '—' in it }.toString())
    }

    @Test
    fun newEpisodeLine() {
        fun ep(s: String) = parseJson(s).jsonObject
        assertEquals("S22 E5 · The Return", NewReleases.episodeLine(ep("""{"season_number":22,"episode_number":5,"name":"The Return"}""")))
        assertEquals("New Episode · S3 E2", NewReleases.episodeLine(ep("""{"season_number":3,"episode_number":2,"name":"Episode 2"}""")))
        assertNull(NewReleases.episodeLine(ep("""{"season_number":0,"episode_number":1,"name":"Special"}""")))
    }
}
