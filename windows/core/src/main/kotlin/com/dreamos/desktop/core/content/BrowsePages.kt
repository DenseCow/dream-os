package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.CatalogPage
import com.dreamos.desktop.core.model.DiscoverSort
import com.dreamos.desktop.core.model.Genre
import com.dreamos.desktop.core.model.HubTile
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.RowStyle
import com.dreamos.desktop.core.model.Title

/**
 * One row of a page, as fire-home's page classes declare them (`titles(name, kind) { load }`): the Catalog service turns
 * each into a `CatalogRow`.
 *
 * [load] runs on an IO thread and may throw (network): treat a throw like null. It returns the row's titles already
 * claimed through the page's [RowClaims] (a title shows once per page), or null when the row is left out (fewer than
 * [BrowsePages.MIN_ROW]). A deep row ([deep]) loads its next page by running [load] again inside
 * `RowPaging.at(name, page)` (use [RowPaging.next]). A row with [hubs] is never left out: its tiles show even when
 * [load] brings nothing.
 */
class RowSpec(
    val id: String,
    val name: String,
    val style: RowStyle,
    /** One title across the page (style BIG); its [kicker] is the module line. No heading. */
    val spotlight: Boolean = false,
    val load: () -> List<Title>?,
    /** Pages on as the row's end nears (Top 10, Spotlights and tile rows don't). */
    val deep: Boolean = true,
    /** Seasonal hub tiles shown before the titles (Home's hub row, More to Celebrate). */
    val hubs: List<HubTile> = emptyList(),
    /** A card's kicker line (big cards: "Comedy", "Netflix"; the Spotlight's module line). */
    val kicker: ((Title) -> String?)? = null,
    /** A card's caption when the row sets its own ("Nov 14 · Movie", "S22 E5 · Name"); null keeps year and type. */
    val subtitle: ((Title) -> String?)? = null,
    /** A card's corner badge ("TONIGHT'S PICK"). */
    val badge: ((Title) -> String?)? = null,
)

/**
 * Browse's pages (fire-home CinemaPages.kt CinemaCategory, CinemaBrowse.kt CinemaGenre): Movies and TV Shows (All and
 * their chips), a Browse genre / category / decade page, the Anime and Kids pages, and each page's hero titles. Each
 * page is a list of [RowSpec]s in order, built with one [RowClaims] per page ([claimsFor] makes the right one).
 * Service pages are [ServicePages]', seasonal hub pages [Seasons]'.
 */
object BrowsePages {
    /** A row needs this many titles to show at all. */
    const val MIN_ROW = 6

    fun withArt(l: List<Title>) = l.filter { it.backdropPath != null || it.posterPath != null }

    /**
     * A standard row of titles, minus those another row shows; left out with fewer than [min]. [page] prefixes its id
     * ("movie:New Releases").
     */
    fun titleRow(
        page: String, claims: RowClaims, name: String, style: RowStyle = RowStyle.LANDSCAPE, min: Int = MIN_ROW,
        kicker: ((Title) -> String?)? = null, subtitle: ((Title) -> String?)? = null, load: () -> List<Title>,
    ) = RowSpec("$page:$name", name, style, load = { claims.take(name, withArt(load())).takeIf { it.size >= min } },
        kicker = kicker, subtitle = subtitle)

    /**
     * The page's [RowClaims]: anime's own sections keep anime (the Anime page and chips, a genre page about anime,
     * Crunchyroll); every other page leaves it out while the profile keeps anime apart.
     */
    fun claimsFor(page: CatalogPage): RowClaims = RowClaims(anime = when (page) {
        is CatalogPage.Anime -> true
        is CatalogPage.Service -> page.spec.name == "Crunchyroll"
        is CatalogPage.GenrePage -> parseChip(page.genre.id)?.second == Discover.ANIME || GenreSpec.find(page.genre.id)?.anime == true
        else -> false
    })

    /** The coming-soon caption: "Nov 14 · Movie" / "Nov 14 · Series". */
    val comingSub: (Title) -> String? = { t ->
        listOfNotNull(Discover.shortDate(t.date).ifEmpty { null }, if (t.kind == MediaKind.TV) "Series" else "Movie").joinToString(" · ")
    }

    private fun label(kind: MediaKind) = if (kind == MediaKind.MOVIE) "Movies" else "TV Shows"

    private fun filters(kind: MediaKind, f: DiscoverFilters.() -> Unit = {}) = DiscoverFilters(type = kind).apply(f)

    // ---- Movies / TV Shows: All ----

    /** Each genre's row name in All, by its TMDB genre id. */
    val MOVIE_ROWS = mapOf(
        28 to "Action Movies", 12 to "Adventure Movies", 16 to "Animated Movies", 35 to "Comedies", 80 to "Crime Movies",
        99 to "Documentaries", 18 to "Dramas", 10751 to "Family Movies", 14 to "Fantasy Movies", 36 to "Historical Movies",
        27 to "Horror Movies", 10402 to "Music & Musicals", 9648 to "Mysteries", 10749 to "Romantic Movies",
        878 to "Sci-Fi Movies", 53 to "Thrillers", 10752 to "War Movies", 37 to "Westerns",
    )

    val TV_ROWS = mapOf(
        10759 to "Action & Adventure Series", 16 to "Animated Series", 35 to "Comedy Series", 80 to "Crime Series",
        99 to "Docuseries", 18 to "Drama Series", 10751 to "Family Series", 10762 to "Shows for Kids", 9648 to "Mystery Series",
        10764 to "Reality TV", 10765 to "Sci-Fi & Fantasy Series", 10766 to "Soap Operas", 10767 to "Talk Shows",
        10768 to "War & Politics Series", 37 to "Western Series",
    )

    /** Categories woven in among the genre rows on All, in this order; the rest follow the genres. */
    val EARLY_THEMES = listOf("Adult Animation", "K-Drama", "True Crime", "Sitcoms", "Superheroes", "Based on a True Story", "Stand-Up Comedy", "Feel-Good")

    /** All's hero: this week's trending of [kind], out today, with art, no anime. Kicker "Trending in Movies" ([moviesHeroKicker]). */
    fun moviesHero(kind: MediaKind): List<Title> = Tmdb.list("/trending/${kind.path}/week", type = kind)
        .filter { it.backdropPath != null && RowMix.released(it) && !RowMix.animeHidden(it) && !it.adult }.take(6)

    fun moviesHeroKicker(kind: MediaKind) = "Trending in ${label(kind)}"

    /**
     * Movies' or TV Shows' "All" (fire-home CinemaCategory.featured), less Continue Watching (the Catalog puts it first):
     * Trending This Week as big cards, Top 10 Today, New Releases, New Episodes (TV) or In Theaters (movies), Coming
     * Soon, Critically Acclaimed, Hidden Gems, Bingeworthy Shows (TV), a category in season, Popular on each of Home's
     * services, every genre (loved genres first once the profile has a taste) with Browse's leading categories woven
     * in, anime, the other categories and the decades. [because] gives the next "Because You Watched" row (null when
     * there's none left); it is asked at the Fire Stick's three places.
     */
    fun moviesPage(kind: MediaKind, claims: RowClaims, because: () -> RowSpec? = { null }): List<RowSpec> {
        val movie = kind == MediaKind.MOVIE
        val p = kind.path
        val out = ArrayList<RowSpec>()
        fun add(r: RowSpec?) { if (r != null) out += r }
        fun titles(name: String, style: RowStyle = RowStyle.LANDSCAPE, kicker: ((Title) -> String?)? = null,
                   subtitle: ((Title) -> String?)? = null, load: () -> List<Title>) =
            add(titleRow(p, claims, name, style, kicker = kicker, subtitle = subtitle, load = load))

        titles("Trending This Week", RowStyle.BIG, kicker = { t -> t.genreIds.firstNotNullOfOrNull { g -> Discover.genreName(kind, g) } ?: label(kind) }) {
            Tmdb.list("/trending/$p/week", type = kind).filter { RowMix.released(it) }
        }
        add(because())
        add(RowSpec("$p:Top 10 Today", "Top 10 Today", RowStyle.TOP10, deep = false, load = {
            RowMix.top10(claims, kind).takeIf { it.size >= MIN_ROW }
        }))
        // New to watch at home (digital releases; new series and seasons), then what's only in theaters so far.
        titles("New Releases") { NewReleases.ofType(kind).also { if (RowPaging.page == 1) NewReleases.log(p, it) } }
        // TV: running shows with a new season out, apart from the brand-new series above.
        if (!movie) titles("New Episodes", subtitle = NewReleases::episodeSub) { NewReleases.returning() }
        // Always there: Trending This Week above can claim most of what's in theaters (Home does the same).
        if (movie) add(RowSpec("$p:In Theaters", "In Theaters", RowStyle.LANDSCAPE, load = {
            claims.takeAtLeast("In Theaters", withArt(NewReleases.inTheaters()), MIN_ROW)
        }))
        add(because())
        add(RowSpec("$p:Coming Soon", "Coming Soon", RowStyle.LANDSCAPE, subtitle = comingSub, load = {
            claims.take("Coming Soon", withArt(Discover.list(filters(kind) { sort = DiscoverSort.COMING_SOON })).filter { it.date.isNotEmpty() })
                .sortedBy { it.date }.takeIf { it.size >= MIN_ROW }
        }))
        titles("Critically Acclaimed") { RowMix.acclaimed(kind, "", "Critically Acclaimed $p") }
        add(because())
        titles("Hidden Gems") { RowMix.hiddenGems(kind, "", "Hidden Gems $p") }
        if (!movie) {
            // Well-loved series with seasons to sink into: 7.8+ from 1,000+ people, from the last twelve years.
            titles("Bingeworthy Shows") {
                val q = "&include_adult=false&sort_by=popularity.desc&vote_count.gte=1000&vote_average.gte=7.8&first_air_date.gte=${RowMix.yearsAgo(12)}" +
                    "&without_genres=10763,10764,10766,10767,10762"
                RowPaging.all({ Tmdb.list("/discover/tv", q, MediaKind.TV) }, { Tmdb.list("/discover/tv", "$q&page=2", MediaKind.TV) }).flatten()
            }
        }
        // A category in season (Halloween, Holiday) comes before the services.
        val (seasonal, themes) = GenreSpec.CATEGORIES.filter { it.inSeason() }.partition { it.name == "Halloween" || it.name == "Holiday" }
        fun categoryRow(c: GenreSpec) {
            val shelf = GenreShelves.ofType(c, kind) ?: return
            titles(shelf.title) { GenreShelves.load(c, shelf) }
        }
        for (c in seasonal) categoryRow(c)
        // Home's services: Dream OS plays them all (the Fire Stick put installed apps first; a PC has none).
        for (name in ServicePages.homeServices()) titles("Popular on $name") { Discover.list(filters(kind) { service = name }, RowMix.recent(kind)) }
        // Every genre with Browse's categories of this type among them, then anime and the decades. The genres come in
        // the order this profile would want them (loved genres first, untouched ones last; ties keep TMDB's order).
        val names = if (movie) MOVIE_ROWS else TV_ROWS
        val early = themes.filter { it.name in EARLY_THEMES }.sortedBy { EARLY_THEMES.indexOf(it.name) }
        val ordered = genreOrder(kind)
        for ((i, g) in ordered.withIndex()) {
            val title = names[g.id] ?: g.name
            titles(title) { RowMix.variety(kind, Discover.query(filters(kind) { genre = g.id }), title) }
            // After every second genre, one of the leading categories (Adult Animation, K-Dramas, Sitcoms...).
            if (i % 2 == 1) early.getOrNull(i / 2)?.let { categoryRow(it) }
        }
        val anime = if (movie) "Anime Movies" else "Anime Series"
        titles(anime) { RowMix.variety(kind, Discover.query(filters(kind) { genre = 16; lang = "ja" }), anime) }
        for (c in themes.filter { it !in early.take(Discover.genres(kind).size / 2) }) categoryRow(c)
        titles("’70s Classics") { Discover.list(filters(kind) { year = "1970s" }) }
        titles("’80s Classics") { Discover.list(filters(kind) { year = "1980s" }) }
        titles("’90s Favorites") { Discover.list(filters(kind) { year = "1990s" }) }
        titles("2000s Hits") { Discover.list(filters(kind) { year = "2000s" }) }
        return out
    }

    /** [kind]'s genres in the order this profile would want them (a new profile: TMDB's order). */
    fun genreOrder(kind: MediaKind): List<Discover.Genre> {
        val plain = Discover.genres(kind)
        val taste = try { Taste.current() } catch (e: Exception) { return plain }
        if (taste.strength < 0.25) return plain
        return plain.withIndex().sortedWith(compareByDescending<IndexedValue<Discover.Genre>> { taste.genre[it.value.id] ?: 0.0 }.thenBy { it.index }).map { it.value }
    }

    // ---- Movies / TV Shows: chips ----

    /** The genre chips, then Browse's leading categories for the type (their Discover ids: keywords and blends). */
    val MOVIE_CHIPS = listOf("Action" to 28, "Comedy" to 35, "Drama" to 18, "Sci-Fi" to 878, "Thriller" to 53, "Horror" to 27,
        "Animation" to 16, "Family" to 10751, "Romance" to 10749, "Crime" to 80, "Documentary" to 99,
        "Anime" to Discover.ANIME, "Adult Animation" to Discover.ADULT_ANIMATION_MOVIE, "Superheroes" to Discover.SUPERHERO_MOVIE,
        "Stand-Up" to Discover.STAND_UP, "True Stories" to Discover.TRUE_STORY)
    val TV_CHIPS = listOf("Drama" to 18, "Comedy" to 35, "Crime" to 80, "Sci-Fi & Fantasy" to 10765, "Action & Adventure" to 10759,
        "Mystery" to 9648, "Animation" to 16, "Reality" to 10764, "Documentary" to 99, "Kids" to 10762,
        "Anime" to Discover.ANIME, "Adult Animation" to Discover.ADULT_ANIMATION_TV, "Sitcoms" to Discover.SITCOM,
        "True Crime" to Discover.TRUE_CRIME, "Superheroes" to Discover.SUPERHERO_TV)

    /** [kind]'s chips after "All" (the UI's own first chip), as [Genre]s with id "chip:movie:28". */
    fun chips(kind: MediaKind): List<Genre> =
        (if (kind == MediaKind.MOVIE) MOVIE_CHIPS else TV_CHIPS).map { (name, id) -> Genre("chip:${kind.path}:$id", name) }

    /** "chip:movie:28" to (MOVIE, 28); null for any other id. */
    fun parseChip(id: String): Pair<MediaKind, Int>? {
        val p = id.split(':')
        if (p.size != 3 || p[0] != "chip") return null
        val kind = MediaKind.of(p[1]) ?: return null
        return kind to (p[2].toIntOrNull() ?: return null)
    }

    private fun chipName(kind: MediaKind, id: Int) =
        (if (kind == MediaKind.MOVIE) MOVIE_CHIPS else TV_CHIPS).firstOrNull { it.second == id }?.first ?: Discover.genreName(kind, id).orEmpty()

    /** A chip's hero: the category's most popular titles, out today, with art (anime only on the Anime chip). Kicker: the chip's name. */
    fun chipHero(kind: MediaKind, id: Int): List<Title> =
        Discover.list(filters(kind) { genre = id; sort = DiscoverSort.POPULAR })
            .filter { it.backdropPath != null && RowMix.released(it) && (id == Discover.ANIME || !RowMix.animeHidden(it)) }.take(6)

    /**
     * A Movies / TV Shows chip's rows (fire-home CinemaCategory.genre): Popular in it as big cards, New Releases, New
     * Episodes (TV), Top Rated, Fan Favorites, Hidden Gems and three decades. [claims]: [claimsFor] (the Anime chip keeps
     * anime).
     */
    fun chipPage(kind: MediaKind, id: Int, claims: RowClaims): List<RowSpec> {
        val name = chipName(kind, id)
        val p = "chip:${kind.path}:$id"
        val clause = Discover.clause(kind, id).orEmpty()
        val out = ArrayList<RowSpec>()
        fun titles(n: String, style: RowStyle = RowStyle.LANDSCAPE, kicker: ((Title) -> String?)? = null,
                   subtitle: ((Title) -> String?)? = null, load: () -> List<Title>) {
            out += titleRow(p, claims, n, style, kicker = kicker, subtitle = subtitle, load = load)
        }
        titles("Popular in $name", RowStyle.BIG, kicker = { t -> t.genreIds.firstNotNullOfOrNull { g -> Discover.genreName(kind, g) } ?: label(kind) }) {
            Discover.list(filters(kind) { genre = id; sort = DiscoverSort.POPULAR })
        }
        titles("New Releases") { NewReleases.ofType(kind, clause) }
        if (kind == MediaKind.TV) titles("New Episodes", subtitle = NewReleases::episodeSub) { NewReleases.returning(clause) }
        titles("Top Rated") { Discover.list(filters(kind) { genre = id; sort = DiscoverSort.TOP_RATED }) }
        titles("Fan Favorites") { RowMix.variety(kind, Discover.query(filters(kind) { genre = id }), "$name ${kind.path} favorites") }
        titles("Hidden Gems") { RowMix.hiddenGems(kind, clause, "Hidden Gems $name ${kind.path}") }
        titles("2010s Hits") { Discover.list(filters(kind) { genre = id; year = "2010s" }) }
        titles("2000s Hits") { Discover.list(filters(kind) { genre = id; year = "2000s" }) }
        titles("’90s Classics") { Discover.list(filters(kind) { genre = id; year = "1990s" }) }
        return out
    }

    // ---- Browse genre / category / decade pages, Anime, Kids ----

    /**
     * [spec]'s Discover list for movies and shows at once, interleaved (its main type first; a decade's years). No anime
     * outside Anime's own page (the hero isn't a tidied row).
     */
    private fun genreMixed(spec: GenreSpec, f: DiscoverFilters.() -> Unit = {}): List<Title> =
        RowMix.interleave(RowPaging.all(*GenreShelves.types(spec).map { type ->
            {
                Discover.list(DiscoverFilters(type = type, genre = if (type == MediaKind.MOVIE) spec.movie else spec.tv, lang = spec.lang, year = spec.year).apply(f))
            }
        }.toTypedArray())).filter { spec.anime || !RowMix.animeHidden(it) }

    /** A genre page's hero: its well-rated titles (7+), out today, with art. Kicker: the genre's name. */
    fun genreHero(spec: GenreSpec): List<Title> =
        genreMixed(spec) { rating = 7 }.filter {
            it.backdropPath != null && RowMix.released(it) && !it.adult && (RowMix.animeRow(spec.name) || !RowMix.animeHidden(it))
        }.take(6)

    /**
     * One genre, movies and shows together (fire-home CinemaGenre): [GenreShelves.page]'s rows (the first as big cards
     * with the genre's name as kicker), and after the first a seasonal hub row when a hub that matches the genre is on
     * (Horror in October: 31 Nights of Horror). No in-row Spotlight on Browse pages (owner, 2026-10-28): the hero leads.
     * [claims]: [claimsFor] (Anime's page keeps anime).
     */
    fun genrePage(spec: GenreSpec, claims: RowClaims): List<RowSpec> {
        val p = "genre:${spec.name}"
        val out = ArrayList<RowSpec>()
        GenreShelves.page(spec).forEachIndexed { i, s ->
            out += titleRow(p, claims, s.title, if (i == 0) RowStyle.BIG else RowStyle.LANDSCAPE,
                kicker = if (i == 0) { _ -> spec.name } else null) { GenreShelves.load(spec, s) }
            if (i == 0) Seasons.forGenre(spec.name)?.let { out += Seasons.hubRow(it, claims, p) }
        }
        return out
    }

    /** A genre page by its Browse id (the spec's name, [GenreSpec.toGenre]); empty for an unknown one. */
    fun genrePage(name: String, claims: RowClaims): List<RowSpec> = GenreSpec.find(name)?.let { genrePage(it, claims) }.orEmpty()

    /** The Anime page: Anime's genre page (claims with anime kept: `RowClaims(anime = true)`). */
    fun animePage(claims: RowClaims): List<RowSpec> = genrePage(GenreSpec.find("Anime")!!, claims)

    /** The Kids page: Kids' genre page (the profile's rating limit applies in every list). */
    fun kidsPage(claims: RowClaims): List<RowSpec> = genrePage(GenreSpec.find("Kids")!!, claims)

    /** Browse by Genre's cards, Popular Categories (those in season) and Through the Decades, as the UI's [Genre]s. */
    fun browseGenres(): List<Genre> = GenreSpec.SHORTCUTS.map { it.toGenre() }
    fun browseCategories(): List<Genre> = GenreSpec.CATEGORIES.filter { it.inSeason() }.map { it.toGenre() }
    fun browseDecades(): List<Genre> = GenreSpec.DECADES.map { it.toGenre() }
}
