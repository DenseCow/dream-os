package com.dreamos.desktop.core.model

import kotlinx.serialization.Serializable

/** How a row's cards look (fire-home CinemaPages.kt `RowKind`). */
@Serializable
enum class RowStyle {
    /** 2:3 posters. */
    POSTER,
    /** 16:9 backdrops with the title. */
    LANDSCAPE,
    /** Big wide cards with a kicker line ("For Fans of Comedies"). */
    BIG,
    /** Top 10: ranked, big numerals, order kept, always 10 cards. */
    TOP10,
    /** Continue Watching and New Episodes for You: episode art, progress, subtitle line. */
    CONTINUE,
    /** Service / network logos. */
    BRAND,
}

/** One card. */
@Serializable
data class CatalogItem(
    val title: Title,
    /** Continue Watching / New Episodes: the episode it points at. */
    val episode: Episode? = null,
    /** 0..1 watch progress (Continue Watching). */
    val progress: Double? = null,
    /** The line above big cards ("For Fans of Comedies"); a Spotlight's module line. */
    val kicker: String? = null,
    /** The caption under the card when the row sets its own ("Nov 14  ·  Movie", "S2  ·  E5  ·  Aired Yesterday"); null = year and type. */
    val subtitle: String? = null,
    /** A corner badge in caps: "NEW EPISODE", "TONIGHT'S PICK", "NEW". */
    val badge: String? = null,
    /** Key art: TMDB's English backdrop with the title's name on it; landscape and big cards show it first. */
    val keyArtPath: String? = null,
    /** A textless backdrop for frames wider than the art (a Spotlight), when [Title.backdropPath] has text on it; null: the backdrop. */
    val plainArtPath: String? = null,
    /** Top 10: the rank shown (1-10). */
    val rank: Int? = null,
    /** Continue Watching: "42 Min Left" (the Fire Stick's words), null when unknown, under a minute or Up Next. */
    val timeLeft: String? = null,
) {
    val id: String get() = title.key + (episode?.let { ":${it.season}:${it.number}" } ?: "")
    fun keyArt(size: String = "w780"): String? = TmdbImage.url(keyArtPath, size)

    /** Art to try in order (the next one when one fails), for a landscape card. */
    fun landscapeArt(size: String = "w780"): List<String> = listOfNotNull(
        episode?.still(if (size == "w780") "w300" else size), keyArt(size), title.backdrop(size), title.poster("w342"),
    )
}

/** A row of cards. Rows come from [com.dreamos.desktop.core.service.Catalog.rowSlots] empty and are filled near the screen. */
@Serializable
data class CatalogRow(
    /** Stable across launches ("home:trending", "home:byw:tv:1396"). */
    val id: String,
    /** The heading, Title Case ("Trending Now", "Because You Watched Severance"). Empty for a Spotlight. */
    val name: String,
    val style: RowStyle = RowStyle.POSTER,
    val items: List<CatalogItem> = emptyList(),
    /** More pages can be fetched with `Catalog.more`. */
    val hasMore: Boolean = false,
    /** One title across the page (style BIG, one item, its kicker is the module line). No heading. */
    val isSpotlight: Boolean = false,
    /** Seasonal hub tiles shown before the items (Home's hub row, a hub page's More to Celebrate). */
    val hubs: List<HubTile> = emptyList(),
    /** Placeholder from rowSlots that has not been filled yet. */
    val filled: Boolean = true,
)

/** A seasonal hub (fire-home SeasonHub.kt): its tile, and its page's masthead. */
@Serializable
data class HubTile(
    /** "halloween", "winter" (`CatalogPage.Season(id)`). */
    val id: String,
    val title: String,
    /** "Halloween", "Winter". */
    val kicker: String,
    val tagline: String,
    /** Line-art glyph id ("Season:Pumpkin"), drawn by the UI. */
    val glyph: String,
    /** Index into the rainbow's colors for the glyph. */
    val hue: Int,
    /** Under the tile and on the masthead: the countdown ("5 Nights Until Halloween") or the tagline. */
    val status: String,
    /** "Night 26 of 31" while a nightly hub runs, else null. */
    val night: String? = null,
    val hasCountdown: Boolean = false,
    /** The tile's small second line: the night, else the kicker, else "Seasonal Collection". */
    val sub: String = "",
)

/** A streaming service page (Netflix, Hulu, Crunchyroll...), fire-home ServiceSpec.kt. */
@Serializable
data class ServiceSpec(
    val id: String,
    val name: String,
    /** TMDB watch provider ids (US). */
    val providerIds: List<Int> = emptyList(),
    /** TMDB network ids (its own shows). */
    val networkIds: List<Int> = emptyList(),
    val logoPath: String? = null,
    /** The service's website (opened in the browser from Where to Watch); %s = the title's name, URL-encoded. */
    val webSearch: String? = null,
) {
    fun logo(size: String = "w300"): String? = TmdbImage.url(logoPath, size)
}

/** A genre card / chip. id: "chip:movie:28" for page chips, a GenreSpec name for Browse genres. */
@Serializable
data class Genre(val id: String, val name: String, val glyph: String = "")

/** The pages the catalog fills with rows. */
@Serializable
sealed interface CatalogPage {
    @Serializable data object Home : CatalogPage
    @Serializable data object Movies : CatalogPage
    @Serializable data object Shows : CatalogPage
    @Serializable data object Anime : CatalogPage
    @Serializable data object Kids : CatalogPage
    @Serializable data class Service(val spec: ServiceSpec) : CatalogPage
    @Serializable data class GenrePage(val genre: Genre) : CatalogPage
    /** Seasonal hubs (Halloween, Holidays...), fire-home SeasonHub.kt. */
    @Serializable data class Season(val hubId: String) : CatalogPage

    /** Pages that keep in-row Spotlight cards: Home and the seasonal hubs. Browse pages show their hero only. */
    val keepsSpotlight: Boolean get() = this == Home || this is Season
}

// ---- Browse's Discover wall ----

@Serializable
enum class DiscoverSort(val label: String) {
    POPULAR("Most Popular"), TOP_RATED("Top Rated"), NEWEST("Newest"), COMING_SOON("Coming Soon"), MIXED("Popular & Favorites"),
}

@Serializable
data class DiscoverFilter(
    val kind: MediaKind = MediaKind.MOVIE,
    /** A DiscoverCategory id; 0 = Any Genre. */
    val genre: Int = 0,
    val sort: DiscoverSort = DiscoverSort.POPULAR,
    /** Decade start year (1990), 0 = any. */
    val decade: Int = 0,
    /** TMDB provider id, 0 = any service. */
    val service: Int = 0,
)

@Serializable
data class DiscoverCategory(val id: Int, val name: String)

@Serializable
data class DiscoverPage(val titles: List<Title>, val page: Int, val totalPages: Int, val totalResults: Int)

// ---- Search ----

@Serializable
data class SearchResults(
    val titles: List<Title> = emptyList(),
    /** "brad pitt movies": that person's titles. */
    val person: Person? = null,
    /** "horror": the genre's rows and its Browse page. */
    val genre: GenreHits? = null,
) {
    @Serializable
    data class Person(val id: Int, val name: String, val heading: String, val titles: List<Title>)

    @Serializable
    data class GenreHits(
        val label: String,
        val browse: List<Genre> = emptyList(),
        val movieHeading: String = "",
        val movies: List<Title> = emptyList(),
        val showHeading: String = "",
        val shows: List<Title> = emptyList(),
    )
}

// ---- New episodes of followed shows ----

@Serializable
data class NewEpisodeInfo(
    val show: Title,
    val season: Int,
    val number: Int,
    /** The last episode of a batch drop (a whole season at once), else [number]. */
    val lastNumber: Int = number,
    val name: String = "",
    /** "yyyy-MM-dd". */
    val airDate: String = "",
    /** Exact drop instant (epoch ms) when known. */
    val at: Long? = null,
    val stillPath: String? = null,
    val overview: String = "",
    val upcoming: Boolean = false,
) {
    val key: String get() = "${show.id}:s${season}e$number"
    val episode: Episode get() = Episode(show.id, season, number, name, overview, stillPath, airDate, airsAt = at, upcoming = upcoming)
}

@Serializable
data class NewEpisodesSnapshot(
    /** Aired in the last days, newest first. */
    val recent: List<NewEpisodeInfo> = emptyList(),
    /** Announced, soonest first. */
    val upcoming: List<NewEpisodeInfo> = emptyList(),
    /** The followed shows' TMDB ids. */
    val followedIds: List<Int> = emptyList(),
) {
    fun nextAt(now: Long = System.currentTimeMillis()): Long? = upcoming.mapNotNull { it.at }.filter { it > now }.minOrNull()
}
