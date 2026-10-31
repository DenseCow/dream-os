package com.dreamos.desktop.shell.data

import com.dreamos.desktop.core.model.CatalogPage
import com.dreamos.desktop.core.model.DiscoverCategory
import com.dreamos.desktop.core.model.DiscoverFilter
import com.dreamos.desktop.core.model.DiscoverPage
import com.dreamos.desktop.core.model.Genre
import com.dreamos.desktop.core.model.HubTile
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.SearchResults
import com.dreamos.desktop.core.model.ServiceSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * What the shell's pages (Home, Browse, hubs, Search, My List) need, as one small interface over core's services
 * ([CoreCatalog]). Titles, genres, services, hub tiles, Discover and Search results are core's own types; the card
 * and row types below add what only the UI needs (the pill to draw, the art order).
 */

typealias Title = com.dreamos.desktop.core.model.Title

/**
 * A card's status pill (availability, Watched), drawn bottom-left on the art: [labels] longest first (a narrow card
 * takes the shortest, never an icon-only pill), its status [tone].
 */
data class Pill(val labels: List<String>, val tone: Tone) {
    /** GOOD green, WARN amber, BAD red (STANDARDS section 2), WATCHED gold, PLAIN neutral. */
    enum class Tone { GOOD, WARN, BAD, WATCHED, PLAIN }
}

/**
 * One card: the title plus what its row says about it. [sub] replaces the year and type line ("S2 · E4"), [progress]
 * 0..1 for Continue Watching, [still] an episode still, [keyArt] the backdrop with the title's name on it.
 */
data class CardItem(
    val title: Title,
    val sub: String? = null,
    val badge: String? = null,
    val progress: Float? = null,
    val pill: Pill? = null,
    val still: String? = null,
    val keyArt: String? = null,
    /** A textless backdrop for frames wider than the art (a Spotlight), when the title's own backdrop has text on it. */
    val plainArt: String? = null,
    val upNext: Boolean = false,
    val kicker: String? = null,
    /** The episode this card plays (Continue Watching, New Episodes for You). */
    val season: Int? = null,
    val episode: Int? = null,
    /** The episode's air day ("yyyy-MM-dd") and whether it is still to come (New Episodes for You). */
    val airDate: String? = null,
    val upcoming: Boolean = false,
) {
    /** A New Episodes for You card: an episode, not one being watched (Continue Watching's carry progress). */
    val newEpisode: Boolean get() = season != null && episode != null && progress == null && !upNext
}

/**
 * Covers (key art) known now, memory only: a card drawn after its row was built (scrolled into view) takes a cover that
 * arrived since then in its first frame, instead of the backdrop its row was built with. Set by the catalog.
 */
object Covers {
    @Volatile var known: (Title) -> String? = { null }

    /** [c]'s cover: its own key art, else one known now; never for a card that plays an episode. */
    fun of(c: CardItem): String? = c.keyArt ?: if (c.still == null && c.episode == null) known(c.title) else null
}

/** How a row draws its cards. */
enum class RowStyle { CONTINUE, BIG, LANDSCAPE, POSTER, TOP10, SPOTLIGHT }

/** A loaded row; [hubs] are seasonal hub tiles shown before its cards; [more] fetches its next cards (deep rows). */
data class Row(
    val id: String, val name: String, val style: RowStyle, val items: List<CardItem>, val hubs: List<HubTile> = emptyList(),
    val more: (suspend () -> List<CardItem>)? = null,
)

/** A row before it has loaded: its place on the page and how to fill it. null from [load] = the row isn't shown. */
class RowSlot(val id: String, val name: String, val style: RowStyle, val load: suspend () -> Row?)

/** The viewer's own rows a card can be taken off, by their row ids' ending (home:continue, movie:continue, ...). */
enum class PersonalRow(private val idEnd: String) {
    CONTINUE(":continue"), MY_LIST(":my-list");
    fun holds(rowId: String) = rowId.endsWith(idEnd)
}

/** The viewer took [key]'s card off [from] rows: told at once, before core has saved it, so the card leaves now. */
data class Removal(val key: String, val from: PersonalRow)

/** A hero title: the kicker over it ("Tonight  ·  Movie"), its title logo when TMDB has one. */
data class HeroItem(val title: Title, val kicker: String, val logoPath: String? = null)

/** A title's scores as kept (no network): the Tomatometer and the audience badge ("IMDb 7.4"). */
data class HeroScores(val tomato: Int?, val audience: String?)

/** A page of rows the shell opens from Browse, Home's hub row or Search: core's [page] under its [name]. */
data class Hub(val name: String, val page: CatalogPage) {
    companion object {
        val movies = Hub("Movies", CatalogPage.Movies)
        val shows = Hub("TV Shows", CatalogPage.Shows)
        fun genre(g: Genre) = Hub(g.name, CatalogPage.GenrePage(g))
        fun service(s: ServiceSpec) = Hub(s.name, CatalogPage.Service(s))
        fun season(t: HubTile) = Hub(t.title, CatalogPage.Season(t.id))
    }
}

/** A person to open (cast, Search). */
data class Person(val id: Int, val name: String, val profilePath: String? = null)

interface Catalog {
    /** False until the viewer has added a TMDB key: pages show how to add one instead of rows. */
    val ready: Boolean

    /** Home's rows in the Fire Stick's order, and the hero titles for the time of day. */
    suspend fun home(): List<RowSlot>
    suspend fun hero(): List<HeroItem>

    /** A hub page's hero titles and rows; its category chips (Movies / TV Shows), each opening as its own rows. */
    suspend fun hubHero(hub: Hub): List<HeroItem>
    suspend fun hub(hub: Hub): List<RowSlot>
    suspend fun chips(hub: Hub): List<Genre>

    /** Browse: genre cards, service cards, the seasonal hubs on today. */
    suspend fun genres(): List<Genre>
    suspend fun services(): List<ServiceSpec>
    suspend fun seasonHubs(): List<HubTile>
    /** One seasonal hub's tile as of today (its page's masthead: countdown, "Night 30 of 31"), on or not; null if unknown. */
    suspend fun seasonHub(id: String): HubTile? = null
    /** The week's most popular title of [kind], whose art the Browse entry tile wears. */
    suspend fun entryArt(kind: MediaKind): Title?

    suspend fun search(query: String): SearchResults
    /** Genre names that begin with what's typed (Search's suggestions). */
    suspend fun suggestions(query: String): List<String>
    /** Search before typing: Trending Now. */
    suspend fun trending(): List<Title>
    suspend fun discover(filter: DiscoverFilter, page: Int): DiscoverPage
    suspend fun discoverCategories(kind: MediaKind): List<DiscoverCategory>

    /**
     * [items] with the key art (the title's cover) filled in for each title card that has none yet. The first [wait]
     * cards' (the ones in view when the row shows) are asked for ahead of other card art and waited for at most
     * [maxMs] ([front]: the row is on screen, so they are asked as the page on screen is), so a row shows its covers
     * from its first frame instead of a backdrop swapped later; the rest take what is known. Episode cards are left as
     * they are; a card whose answer isn't in keeps the backdrop.
     */
    suspend fun withKeyArt(items: List<CardItem>, wait: Int, maxMs: Long, front: Boolean = false): List<CardItem> = items

    /** A title's logo image path (hero, Spotlight), cached. */
    suspend fun logo(t: Title): String?
    fun genreNames(t: Title, max: Int = 2): List<String>

    val myList: StateFlow<List<Title>>
    fun toggleMyList(t: Title)
    fun removeContinue(t: Title)

    /** Shown rows may be stale (profile, history, My List, settings, a new day): pages reload their rows. */
    val changes: Flow<Unit>

    /**
     * How many [changes] there have been, counted whether a page was listening or not: a page kept while the viewer
     * was on another one compares it when it shows again and reloads its rows if it moved.
     */
    val changeCount: StateFlow<Long>

    /** Cards the viewer removed (Remove from Continue Watching, Remove from My List), as the menu picks them. */
    val removals: Flow<Removal>

    /** A card's pill right now (no network); [ask] queues checks for cards about to show, [pillUpdates] says which changed. */
    fun pill(t: Title, narrow: Boolean): Pill?
    fun ask(titles: List<Title>)

    /**
     * Readies [titles]' pills before their row shows: Watched answers read now, availability checks queued ([front]:
     * on screen, ahead of the look-ahead) and waited for, at most [maxMs] and only while answers keep coming.
     */
    suspend fun readyPills(titles: List<Title>, maxMs: Long, front: Boolean = false) = ask(titles)
    val pillUpdates: Flow<String>

    /** An episode's status pill now (the New Episode banner's), no network; [askEpisode] queues its check up front. */
    fun episodePill(show: Title, season: Int, episode: Int, name: String, airDate: String): Pill? = null
    fun askEpisode(show: Title, season: Int, episode: Int, name: String, airDate: String) {}

    /** A title's kept scores (no network); [prefetchScores] asks for the ones not kept yet, behind the viewer. */
    fun scores(t: Title): HeroScores? = null
    fun prefetchScores(titles: List<Title>) {}
}
