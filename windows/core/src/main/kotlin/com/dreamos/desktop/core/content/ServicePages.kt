package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.RowStyle
import com.dreamos.desktop.core.model.ServiceSpec
import com.dreamos.desktop.core.model.Title

/**
 * Streaming service pages (fire-home ServiceSpec.kt and CinemaPages.kt CinemaService): Browse by Service's cards, and
 * one service's rows from TMDB's US subscription catalog for it (Dream OS plays it either way).
 *
 * The Fire Stick picks the services for Home, Movies, TV Shows and genre pages from its installed apps first; a PC has
 * no installed apps, so they come in the iPhone's fixed order ([HOME_SERVICES], first [MAX_SERVICES]).
 */
object ServicePages {
    /** "Popular on" rows on Movies, TV Shows and genre pages: this many of [HOME_SERVICES]. */
    const val MAX_SERVICES = 6

    /** The services Home, Movies, TV Shows and genre pages have rows for (fire-home / iPhone HomePicks.HOME_SERVICES). */
    val HOME_SERVICES = listOf("Netflix", "Hulu", "Disney+", "HBO Max", "Prime Video", "Apple TV", "Paramount+", "Peacock", "AMC+")

    /** The first [MAX_SERVICES] of [HOME_SERVICES] that TMDB has provider ids for. */
    fun homeServices(): List<String> = HOME_SERVICES.filter { Discover.serviceIds(it).isNotEmpty() }.take(MAX_SERVICES)

    /** The Browse by Service cards in order: name and the TMDB networks its originals air on (none for Tubi). */
    private val SHORTCUTS = listOf(
        "Netflix" to listOf(213), "Hulu" to listOf(453), "Prime Video" to listOf(1024), "Apple TV" to listOf(2552),
        "Disney+" to listOf(2739), "HBO Max" to listOf(3186, 49), "Peacock" to listOf(3353), "Paramount+" to listOf(4330),
        "AMC+" to listOf(174, 4661), "Crunchyroll" to listOf(1112), "Tubi" to emptyList(),
    )

    /** Browse by Service's cards ([ServiceSpec]: TMDB provider ids from [Tmdb.SERVICES], network ids, its web search). */
    fun all(): List<ServiceSpec> = SHORTCUTS.map { (name, nets) ->
        val s = Tmdb.SERVICES.filter { it.name == name }
        ServiceSpec(id = name, name = name, providerIds = s.map { it.providerId }, networkIds = nets, webSearch = s.firstNotNullOfOrNull { it.web })
    }

    fun find(id: String): ServiceSpec? = all().firstOrNull { it.id.equals(id, ignoreCase = true) || it.name.equals(id, ignoreCase = true) }

    /** The service page's genre and category rows, in this order (those in season). */
    private val ORDER = listOf("Halloween", "Holiday", "Comedy", "Drama", "Action", "Sci-Fi", "Crime", "Thriller", "Horror", "Anime",
        "Adult Animation", "True Crime", "K-Drama", "Mystery", "Romance", "Sitcoms", "Superheroes", "Animation", "Documentary",
        "Reality", "Stand-Up Comedy", "Based on a True Story", "Feel-Good", "Mind-Bending", "Family", "Kids", "Sports", "Teen",
        "Fantasy", "Adventure", "Musicals", "LGBTQ+", "’80s Classics", "’90s Classics")

    /** A genre or category row on a service needs a few more titles than usual to show. */
    private const val CATEGORY_MIN = 8

    private fun categories(): List<GenreSpec> {
        val all = GenreSpec.all()
        return ORDER.mapNotNull { n -> all.firstOrNull { it.name == n } }
    }

    private fun filters(spec: ServiceSpec, type: MediaKind, f: DiscoverFilters.() -> Unit = {}) =
        DiscoverFilters(type = type, service = spec.name).apply(f)

    /** Shows and movies on [spec] for the filter [f], interleaved (shows first), [extra] on top. */
    private fun mixed(spec: ServiceSpec, extra: String = "", f: DiscoverFilters.() -> Unit = {}): List<Title> =
        RowMix.interleave(RowPaging.all(*listOf(MediaKind.TV, MediaKind.MOVIE).map { type ->
            { Discover.list(filters(spec, type, f), extra) }
        }.toTypedArray()))

    /** The page's hero: well-rated titles (7+) on it, out today, with art, no anime outside Crunchyroll. Kicker: "On Netflix" ([heroKicker]). */
    fun hero(spec: ServiceSpec): List<Title> = mixed(spec) { rating = 7 }
        .filter { it.backdropPath != null && RowMix.released(it) && !it.adult && (spec.name == "Crunchyroll" || !RowMix.animeHidden(it)) }.take(6)

    fun heroKicker(spec: ServiceSpec) = "On ${spec.name}"

    private fun popular(spec: ServiceSpec) = RowPaging.all({ mixed(spec) }, { mixed(spec, "&page=2") }).flatten()

    private fun originals(spec: ServiceSpec): List<Title> {
        val q = "&include_adult=false&sort_by=popularity.desc&with_networks=${spec.networkIds.joinToString("|")}"
        return RowPaging.all({ Tmdb.list("/discover/tv", q, MediaKind.TV) }, { Tmdb.list("/discover/tv", "$q&page=2", MediaKind.TV) }).flatten()
    }

    /** Recent digital releases, new series and new seasons on the service (NewReleases), not the newest-dated (often unreleased) titles. */
    private fun newReleases(spec: ServiceSpec) = NewReleases.onService(spec.name)
        .also { if (RowPaging.page == 1) NewReleases.log(spec.name, it) }

    /**
     * One service's page (fire-home CinemaService): Popular on it as big cards, its Originals, New Releases, TV Shows,
     * Movies, Critically Acclaimed, Hidden Gems, then the genres and Browse's categories on it ("Horror on HBO Max",
     * "Adult Animation on Hulu"), a category in season leading. [claims]: `RowClaims(anime = spec.name == "Crunchyroll")`
     * ([BrowsePages.claimsFor]). The page ends with the JustWatch credit (the UI's).
     */
    fun servicePage(spec: ServiceSpec, claims: RowClaims): List<RowSpec> {
        val name = spec.name
        val p = "service:$name"
        val out = ArrayList<RowSpec>()
        fun titles(n: String, style: RowStyle = RowStyle.LANDSCAPE, min: Int = BrowsePages.MIN_ROW, load: () -> List<Title>) {
            out += BrowsePages.titleRow(p, claims, n, style, min, kicker = if (style == RowStyle.BIG) { _ -> name } else null, load = load)
        }
        titles("Popular on $name", RowStyle.BIG) { popular(spec) }
        if (spec.networkIds.isNotEmpty()) titles("$name Originals") { originals(spec) }
        titles("New Releases") { newReleases(spec) }
        titles("TV Shows") { RowMix.variety(MediaKind.TV, Discover.query(filters(spec, MediaKind.TV)), "$name TV Shows") }
        titles("Movies") { RowMix.variety(MediaKind.MOVIE, Discover.query(filters(spec, MediaKind.MOVIE)), "$name Movies") }
        titles("Critically Acclaimed") { mixed(spec, "&page=${RowMix.dailyPage("Critically Acclaimed $name", 3)}") { sort = com.dreamos.desktop.core.model.DiscoverSort.TOP_RATED } }
        titles("Hidden Gems") {
            val extra = "&with_watch_providers=${Discover.serviceIds(name)}&watch_region=US&with_watch_monetization_types=flatrate"
            RowMix.interleave(RowPaging.all(*listOf(MediaKind.TV, MediaKind.MOVIE).map { type -> { RowMix.hiddenGems(type, extra, "Hidden Gems $name") } }.toTypedArray()))
        }
        for (g in categories()) GenreShelves.onService(g, name)?.let { shelf ->
            titles(shelf.title, min = CATEGORY_MIN) { GenreShelves.load(g, shelf) }
        }
        return out
    }

    /** Fetches what [spec]'s page shows first (its hero and first rows) into TMDB's cache: call it while its card has focus. */
    fun prefetch(spec: ServiceSpec) {
        try {
            RowPaging.all({ hero(spec) }, { popular(spec) }, { if (spec.networkIds.isNotEmpty()) originals(spec) else emptyList() }, { newReleases(spec) })
        } catch (e: Exception) {}
    }
}
