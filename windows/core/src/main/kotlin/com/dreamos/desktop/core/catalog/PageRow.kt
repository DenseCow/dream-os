package com.dreamos.desktop.core.catalog

import com.dreamos.desktop.core.content.RowClaims
import com.dreamos.desktop.core.model.*

/**
 * One row recipe on a page (fire-home CinemaRowsPage.row / spotlight / the personal rows). [load] runs on an IO thread,
 * claims its titles through the page's [RowClaims] and returns them (null or under [min]: the row is left out); a deep
 * row runs it again inside `RowPaging.at(name, page)` for its next page. [personal] rows (Continue Watching, New
 * Episodes for You, My List) build their cards from local data instead and are never paged.
 */
class PageRow(
    /** Stable id ("home:top-picks"), unique on its page. */
    val id: String,
    val name: String,
    val style: RowStyle,
    val spotlight: Boolean = false,
    val min: Int = MIN_ROW,
    /** Cards shown at most on the first fill. */
    val first: Int = 20,
    /** Can load more as its end nears. */
    val deep: Boolean = true,
    /** Tiles shown before the cards (a seasonal hub's). */
    val hubs: () -> List<HubTile> = { emptyList() },
    /** The row's name once filled, when the list it ended up showing changes it ("Popular Today"). */
    val rename: (() -> String?)? = null,
    val load: (() -> List<Title>?)? = null,
    /** How each title becomes a card (kicker, subtitle, rank, badge). */
    val card: (Title, Int) -> CatalogItem = { t, _ -> CatalogItem(t) },
    /** Spotlight: which title of the list takes the card. */
    val pick: (List<Title>) -> Title? = { it.firstOrNull() },
    val personal: (() -> List<CatalogItem>?)? = null,
) {
    fun slot() = CatalogRow(id, name, style, isSpotlight = spotlight, filled = false)

    companion object {
        /** A row needs this many titles to show at all. */
        const val MIN_ROW = 6
    }
}

/** A page being shown: its rows and the claims they share (each title in one row only), plus each row's paging. */
class PageSession(val page: CatalogPage, val claims: RowClaims, rows: List<PageRow>) {
    val rows: List<PageRow> = rows.distinctBy { it.id }
    /** Next RowPaging page per row id, and the keys each row shows (its later pages drop them). */
    val nextPage = HashMap<String, Int>()
    val shown = HashMap<String, MutableSet<String>>()
    val done = HashSet<String>()
    val createdAt = System.currentTimeMillis()

    fun row(id: String) = rows.firstOrNull { it.id == id }
}
