package com.dreamos.desktop.core.catalog

import com.dreamos.desktop.core.content.RowClaims
import com.dreamos.desktop.core.content.Taste
import com.dreamos.desktop.core.content.Tmdb
import com.dreamos.desktop.core.model.*

/**
 * The Browse side of the catalog (genres, categories, services, seasonal hubs, new releases, Discover), as the pages
 * need it. [BrowseRowsImpl] wires the ports of fire-home's Discover / NewReleases / GenreShelves / SeasonHub into this.
 */
interface BrowseRows {
    /** A TMDB genre's name for [kind] ("Comedy"), null when unknown. */
    fun genreName(kind: MediaKind, id: Int): String?

    /** Browse categories with their movie / TV genre ids (Taste's for-you rows). */
    fun tasteCategories(): List<Taste.Category>

    /** Home's seasonal hub row (its tile, Tonight's Pick, the hub's lead titles), null when no hub is on. */
    fun homeSeasonRow(claims: RowClaims): PageRow?

    /** Home's New Releases, New Episodes and In Theaters rows, in that order. [first] runs before each (the charts claim first). */
    fun homeNewReleases(claims: RowClaims, first: () -> Unit): List<PageRow>

    /** A taste row on Home ("Dark Crime Stories") for Browse category [category]; null when it has no category page. */
    fun forYouRow(id: String, category: String, title: String, claims: RowClaims, first: () -> Unit): PageRow?

    /** The most anticipated titles of the coming months, soonest first (Discover's Coming Soon sort). */
    fun comingSoon(kind: MediaKind): List<Title>

    /** Rows for a page other than Home (Movies, TV Shows, a chip, a genre, Anime, Kids, a service, a seasonal hub). */
    fun pageRows(page: CatalogPage, claims: RowClaims): List<PageRow> = emptyList()

    /** The hero titles for a page other than Home. */
    fun hero(page: CatalogPage): List<CatalogItem> = emptyList()

    /** The page's claims (anime's own sections keep anime). */
    fun claims(page: CatalogPage): RowClaims = RowClaims(anime = page == CatalogPage.Anime)
    fun chips(page: CatalogPage): List<Genre> = emptyList()
    fun genres(): List<Genre> = emptyList()
    fun categories(): List<Genre> = emptyList()
    fun decades(): List<Genre> = emptyList()
    fun hub(id: String): HubTile? = hubs().firstOrNull { it.id == id }
    fun services(): List<ServiceSpec> = emptyList()
    fun hubs(): List<HubTile> = emptyList()
    fun discover(filter: DiscoverFilter, page: Int): DiscoverPage = DiscoverPage(emptyList(), page, 0, 0)
    fun discoverCategories(kind: MediaKind): List<DiscoverCategory> = emptyList()
    /** Genre search hits for Search ("horror", "rom com"), null when the words name no genre. */
    fun genreSearch(query: String): SearchResults.GenreHits? = null
    fun suggestions(query: String): List<String> = emptyList()
}

/** Until the Browse port is wired: Home without the Browse-only rows, Coming Soon from TMDB's upcoming lists. */
object BasicBrowseRows : BrowseRows {
    override fun genreName(kind: MediaKind, id: Int): String? = null
    override fun tasteCategories(): List<Taste.Category> = emptyList()
    override fun homeSeasonRow(claims: RowClaims): PageRow? = null
    override fun homeNewReleases(claims: RowClaims, first: () -> Unit): List<PageRow> = emptyList()
    override fun forYouRow(id: String, category: String, title: String, claims: RowClaims, first: () -> Unit): PageRow? = null
    override fun comingSoon(kind: MediaKind): List<Title> =
        if (kind == MediaKind.MOVIE) Tmdb.list("/movie/upcoming", type = kind).filter { !com.dreamos.desktop.core.content.RowMix.released(it) }.sortedBy { it.date }
        else emptyList()
}
