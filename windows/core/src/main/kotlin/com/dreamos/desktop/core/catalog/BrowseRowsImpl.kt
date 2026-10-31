package com.dreamos.desktop.core.catalog

import com.dreamos.desktop.core.catalog.PageRow.Companion.MIN_ROW
import com.dreamos.desktop.core.content.*

import com.dreamos.desktop.core.model.*

/** [BrowseRows] over the Browse port (BrowsePages, ServicePages, Seasons, NewReleases, Discover, GenreSearch). */
object BrowseRowsImpl : BrowseRows {
    override fun genreName(kind: MediaKind, id: Int): String? = Discover.genreName(kind, id)

    override fun tasteCategories(): List<Taste.Category> = Discover.tasteCategories()

    override fun homeSeasonRow(claims: RowClaims): PageRow? = row(Seasons.homeSeasonRow(claims))

    override fun homeNewReleases(claims: RowClaims, first: () -> Unit): List<PageRow> = listOf(
        PageRow("home:new-releases", "New Releases", RowStyle.LANDSCAPE, load = {
            first()
            claims.take("New Releases", NewReleases.mixed()).takeIf { it.size >= MIN_ROW }
        }),
        // Running shows with a new season out: their own row, the newest episode under each card.
        PageRow("home:new-episodes", "New Episodes", RowStyle.LANDSCAPE, load = {
            claims.take("New Episodes", NewReleases.returning()).takeIf { it.size >= MIN_ROW }
        }, card = { t, _ -> CatalogItem(t, subtitle = NewReleases.episodeSub(t)) }),
        // Only in theaters so far: always there, even when earlier rows claimed most of it.
        PageRow("home:in-theaters", "In Theaters", RowStyle.LANDSCAPE, load = {
            first()
            claims.takeAtLeast("In Theaters", BrowsePages.withArt(NewReleases.inTheaters()), MIN_ROW)
        }),
    )

    override fun forYouRow(id: String, category: String, title: String, claims: RowClaims, first: () -> Unit): PageRow? {
        val spec = GenreSpec.find(category) ?: return null
        return PageRow(id, title, RowStyle.LANDSCAPE, load = {
            val shelf = Shelf(title, Shelf.Mode.VARIETY, GenreShelves.clause(spec, MediaKind.MOVIE), GenreShelves.clause(spec, MediaKind.TV))
            val l = RowPaging.all({ first(); emptyList() }, { GenreShelves.load(spec, shelf) })[1]
            claims.take(title, BrowsePages.withArt(l)).takeIf { it.size >= MIN_ROW }
        })
    }

    override fun comingSoon(kind: MediaKind): List<Title> =
        Discover.list(DiscoverFilters(type = kind, sort = DiscoverSort.COMING_SOON))

    override fun claims(page: CatalogPage): RowClaims = BrowsePages.claimsFor(page)

    override fun pageRows(page: CatalogPage, claims: RowClaims): List<PageRow> {
        val specs: List<RowSpec> = when (page) {
            CatalogPage.Movies, CatalogPage.Shows -> {
                val kind = if (page == CatalogPage.Movies) MediaKind.MOVIE else MediaKind.TV
                val seeds = BecauseYouWatched.seeds(kind).iterator()
                BrowsePages.moviesPage(kind, claims) {
                    if (!seeds.hasNext()) null else seeds.next().let { e -> becauseSpec(kind, e, claims) }
                }
            }
            CatalogPage.Anime -> BrowsePages.animePage(claims)
            CatalogPage.Kids -> BrowsePages.kidsPage(claims)
            is CatalogPage.GenrePage -> BrowsePages.parseChip(page.genre.id)?.let { (k, id) -> BrowsePages.chipPage(k, id, claims) }
                ?: BrowsePages.genrePage(page.genre.id, claims).ifEmpty { BrowsePages.genrePage(page.genre.name, claims) }
            is CatalogPage.Service -> ServicePages.servicePage(page.spec, claims)
            is CatalogPage.Season -> Seasons.seasonPage(page.hubId, claims)
            CatalogPage.Home -> emptyList()
        }
        return specs.mapNotNull { row(it) }
    }

    /** "Because You Watched <title>" on Movies / TV Shows (recommendations, nothing watched or saved). */
    private fun becauseSpec(kind: MediaKind, e: HistoryEntry, claims: RowClaims): RowSpec {
        val title = "Because You Watched ${e.title.name}"
        return RowSpec("${kind.path}:byw:${e.title.id}", title, RowStyle.LANDSCAPE, load = {
            val skip = HashSet<String>().apply { WatchHistory.recent().forEach { add(it.key) }; MyListStore.items().forEach { add(it.key) } }
            // Nor what the trending rows show (fire-home CinemaCategory.becauseRow): this week's 20 and today's 10.
            val got = RowPaging.all(
                { Tmdb.list("/trending/${kind.path}/week", type = kind).take(20) + Tmdb.list("/trending/${kind.path}/day", type = kind).take(10) },
                { RowMix.recs(kind, e.title.id, e.title.year.toIntOrNull()) },
            )
            val near = got[0].mapTo(HashSet()) { it.key }
            val recs = got[1].filter { (it.backdropPath != null || it.posterPath != null) && it.key !in skip && it.key !in near }
            claims.take(title, recs).takeIf { it.size >= MIN_ROW }
        })
    }

    override fun hero(page: CatalogPage): List<CatalogItem> {
        fun items(list: List<Title>, kicker: String) = list.map { CatalogItem(it, kicker = kicker) }
        return when (page) {
            CatalogPage.Movies -> items(BrowsePages.moviesHero(MediaKind.MOVIE), BrowsePages.moviesHeroKicker(MediaKind.MOVIE))
            CatalogPage.Shows -> items(BrowsePages.moviesHero(MediaKind.TV), BrowsePages.moviesHeroKicker(MediaKind.TV))
            CatalogPage.Anime -> GenreSpec.find("Anime")?.let { items(BrowsePages.genreHero(it), it.name) }.orEmpty()
            CatalogPage.Kids -> GenreSpec.find("Kids")?.let { items(BrowsePages.genreHero(it), it.name) }.orEmpty()
            is CatalogPage.GenrePage -> BrowsePages.parseChip(page.genre.id)?.let { (k, id) -> items(BrowsePages.chipHero(k, id), page.genre.name) }
                ?: (GenreSpec.find(page.genre.id) ?: GenreSpec.find(page.genre.name))?.let { items(BrowsePages.genreHero(it), it.name) }.orEmpty()
            is CatalogPage.Service -> items(ServicePages.hero(page.spec), ServicePages.heroKicker(page.spec))
            is CatalogPage.Season, CatalogPage.Home -> emptyList()
        }
    }

    override fun chips(page: CatalogPage): List<Genre> = when (page) {
        CatalogPage.Movies -> BrowsePages.chips(MediaKind.MOVIE)
        CatalogPage.Shows -> BrowsePages.chips(MediaKind.TV)
        else -> emptyList()
    }

    override fun genres(): List<Genre> = BrowsePages.browseGenres()
    override fun categories(): List<Genre> = BrowsePages.browseCategories()
    override fun decades(): List<Genre> = BrowsePages.browseDecades()
    override fun services(): List<ServiceSpec> = ServicePages.all()
    override fun hubs(): List<HubTile> = Seasons.tiles()
    override fun hub(id: String): HubTile? = Seasons.hub(id)
    override fun discover(filter: DiscoverFilter, page: Int): DiscoverPage = Discover.wall(filter, page)
    override fun discoverCategories(kind: MediaKind): List<DiscoverCategory> = Discover.discoverCategories(kind)
    override fun genreSearch(query: String): SearchResults.GenreHits? = GenreSearch.hits(query)
    override fun suggestions(query: String): List<String> = GenreSearch.suggest(query)

    /** A Browse [RowSpec] as a catalog row (its load already claims and applies the row minimum). */
    fun row(s: RowSpec): PageRow = PageRow(
        id = s.id, name = s.name, style = s.style, spotlight = s.spotlight, min = 1, deep = s.deep,
        hubs = { s.hubs }, load = s.load,
        card = { t, i ->
            CatalogItem(t, kicker = s.kicker?.invoke(t), subtitle = s.subtitle?.invoke(t), badge = s.badge?.invoke(t),
                rank = if (s.style == RowStyle.TOP10) i + 1 else null)
        },
    )
}
