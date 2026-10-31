package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.DiscoverCategory
import com.dreamos.desktop.core.model.DiscoverFilter
import com.dreamos.desktop.core.model.DiscoverPage
import com.dreamos.desktop.core.model.DiscoverSort
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.time.DreamTime
import kotlinx.serialization.Serializable

/**
 * What Discover (in Browse) is filtering by (fire-home DiscoverFilters). Every field has a neutral default; [type] is
 * never empty. The same filters drive the Browse wall, the category rows' "See All", and the genre shortcuts, so a row
 * and its See All always agree. The model's [DiscoverFilter] (what the UI holds) maps onto it with [of].
 */
@Serializable
data class DiscoverFilters(
    var type: MediaKind = MediaKind.MOVIE,
    /** A [Discover] category id for [type] (a TMDB genre, or a made-up id below 0), 0 = any. */
    var genre: Int = 0,
    /** "" any, "1990s" a decade, "older" before 1970, or a single year ("2024"). */
    var year: String = "",
    /** Minimum TMDB score (6, 7, 8), 0 = any. */
    var rating: Int = 0,
    /** Movies only: 0 any, 1 under 90 min, 2 90-120 min, 3 over 2 hours. */
    var runtime: Int = 0,
    /** Original language (ISO 639-1), "" = any. */
    var lang: String = "",
    /** Streaming service name (one of [Tmdb.SERVICES]), "" = any. */
    var service: String = "",
    var sort: DiscoverSort = Discover.SORT_DEFAULT,
) {
    val movie get() = type == MediaKind.MOVIE

    /** True when only the type (and the default sort) is set. */
    fun isClear() = genre == 0 && year.isEmpty() && rating == 0 && runtime == 0 && lang.isEmpty() && service.isEmpty() && sort == Discover.SORT_DEFAULT

    /** Back to the defaults, keeping Movies or TV Shows. */
    fun clear() { genre = 0; year = ""; rating = 0; runtime = 0; lang = ""; service = ""; sort = Discover.SORT_DEFAULT }

    /** Switches between movies and shows, carrying the genre across where TMDB has a match. */
    fun switchType(to: MediaKind) {
        if (to == type) return
        genre = Discover.mapGenre(genre, to)
        if (to != MediaKind.MOVIE) runtime = 0
        type = to
    }

    /** The UI's filter: decade 1990 = "1990s", a decade before 1970 = "older", a provider id = its service's name. */
    fun toModel() = DiscoverFilter(
        kind = type, genre = genre, sort = sort,
        decade = when {
            year == "older" -> 1960
            year.endsWith("s") -> year.dropLast(1).toIntOrNull() ?: 0
            else -> 0
        },
        service = Tmdb.SERVICES.firstOrNull { it.name == service }?.providerId ?: 0,
    )

    companion object {
        fun of(f: DiscoverFilter) = DiscoverFilters(
            type = f.kind,
            genre = f.genre,
            year = when {
                f.decade <= 0 -> ""
                f.decade < 1970 -> "older"
                else -> "${f.decade / 10 * 10}s"
            },
            service = if (f.service == 0) "" else Tmdb.SERVICES.firstOrNull { it.providerId == f.service }?.name.orEmpty(),
            sort = f.sort,
        )
    }
}

/**
 * Discover (fire-home Discover.kt): the Browse wall's query for a [DiscoverFilters], its categories (TMDB genres and the
 * made-up ids that stand for keywords or genre blends), and the lists every Browse row reads through [query].
 * Anime stays in the anime sections: Animation leaves TMDB's anime keyword out unless the filter asks for Japanese, and
 * [Tmdb.url] adds the same rule to every /discover query that doesn't ask for anime.
 */
object Discover {
    /** Popular now, alternating with all-time favorites (over a year old): the wall isn't only this year's titles. */
    val SORT_DEFAULT = DiscoverSort.MIXED

    /** The order the Sort By picker lists them in. */
    val SORT_ORDER = listOf(DiscoverSort.MIXED, DiscoverSort.POPULAR, DiscoverSort.TOP_RATED, DiscoverSort.NEWEST, DiscoverSort.COMING_SOON)

    /**
     * One page of the wall for [f]. Popular & Favorites interleaves the popular page with the same page of the
     * most-voted titles over a year old (a filter's own years are kept); totals are the popular list's.
     */
    fun page(f: DiscoverFilters, page: Int): Tmdb.Results {
        val q = query(f)
        if (f.sort != DiscoverSort.MIXED) return Tmdb.discover(f.type, q, page)
        val dateKey = RowMix.dateKey(f.type)
        // Favorites from the last 30 years unless a year is picked (the decades hold the classics).
        val favQuery = q.replace(Regex("&sort_by=[^&]*"), "&sort_by=vote_count.desc") +
            (if (q.contains("$dateKey.lte=")) "" else "&$dateKey.lte=${RowMix.yearsAgo(1)}") +
            (if (f.year.isNotEmpty()) "" else "&$dateKey.gte=${RowMix.yearsAgo(RowMix.RECENT_YEARS)}")
        // Both lists at once (the popular one's failure still fails the page).
        val both = RowPaging.all<Any>(
            { Tmdb.discover(f.type, q, page) },
            { try { Tmdb.discover(f.type, favQuery, page).titles } catch (e: Exception) { emptyList<Title>() } },
        )
        val popular = both[0] as Tmdb.Results
        @Suppress("UNCHECKED_CAST") val favorites = both[1] as List<Title>
        return Tmdb.Results(RowMix.interleave(listOf(popular.titles, favorites)), popular.page, popular.totalPages, popular.totalResults)
    }

    /**
     * The Browse wall's page [page] for the UI's [filter]: [page] tidied like every row (nothing adult, finished or
     * obscure; anime only when the filter asks for it, [wantsAnime]), in the order's own order.
     */
    fun wall(filter: DiscoverFilter, page: Int): DiscoverPage {
        val f = DiscoverFilters.of(filter)
        val r = page(f, page)
        return DiscoverPage(RowMix.tidy(r.titles, artFirst = false, anime = wantsAnime(f)), r.page, r.totalPages, r.totalResults)
    }

    /** [f]'s first list as a row reads it ([Tmdb.list]: cached, paged by [RowPaging]), [extra] on top. */
    fun list(f: DiscoverFilters, extra: String = ""): List<Title> = Tmdb.list("/discover/${f.type.path}", query(f) + extra, f.type)

    class Genre(val id: Int, val name: String)

    val MOVIE_GENRES = listOf(
        Genre(28, "Action"), Genre(12, "Adventure"), Genre(16, "Animation"), Genre(35, "Comedy"), Genre(80, "Crime"),
        Genre(99, "Documentary"), Genre(18, "Drama"), Genre(10751, "Family"), Genre(14, "Fantasy"), Genre(36, "History"),
        Genre(27, "Horror"), Genre(10402, "Music"), Genre(9648, "Mystery"), Genre(10749, "Romance"),
        Genre(878, "Science Fiction"), Genre(53, "Thriller"), Genre(10752, "War"), Genre(37, "Western"),
    )

    val TV_GENRES = listOf(
        Genre(10759, "Action & Adventure"), Genre(16, "Animation"), Genre(35, "Comedy"), Genre(80, "Crime"),
        Genre(99, "Documentary"), Genre(18, "Drama"), Genre(10751, "Family"), Genre(10762, "Kids"), Genre(9648, "Mystery"),
        Genre(10764, "Reality"), Genre(10765, "Sci-Fi & Fantasy"), Genre(10766, "Soap"), Genre(10767, "Talk"),
        Genre(10768, "War & Politics"), Genre(37, "Western"),
    )

    fun genres(type: MediaKind) = if (type == MediaKind.MOVIE) MOVIE_GENRES else TV_GENRES

    fun genreName(type: MediaKind, id: Int) = genres(type).firstOrNull { it.id == id }?.name
        ?: if (id < 0) CATEGORIES.firstOrNull { it.id(type) == id }?.name else null

    /**
     * One of the wall's categories, the same set for movies and shows. [movie] and [tv] are the TMDB genre for each type
     * where there is one, else a made-up id below 0 that [query] reads as keywords or a genre blend ([PSEUDO]); 0: none
     * for that type (the chip is left out).
     */
    class Category(val name: String, val movie: Int, val tv: Int) {
        fun id(type: MediaKind) = if (type == MediaKind.MOVIE) movie else tv
    }

    const val ANIME = -1
    const val TV_ADVENTURE = -2
    const val TV_FANTASY = -3
    const val TV_HISTORY = -4
    const val TV_HORROR = -5
    const val MOVIE_KIDS = -6
    const val TV_MUSIC = -7
    const val TV_ROMANCE = -8
    const val TV_THRILLER = -9

    // Browse's categories beyond the genres (GenreSpec.CATEGORIES): TMDB keywords and blends, checked against TMDB to
    // bring well over a row's worth of well-voted titles of each type they're used for.
    const val ADULT_ANIMATION_MOVIE = -10
    const val ADULT_ANIMATION_TV = -11
    const val TRUE_CRIME = -12
    const val STAND_UP = -13
    const val SITCOM = -14
    const val SUPERHERO_MOVIE = -15
    const val SUPERHERO_TV = -16
    const val MUSICAL_MOVIE = -17
    const val MUSICAL_TV = -18
    const val HALLOWEEN = -19
    const val HOLIDAY = -20
    const val MIND_BENDING = -21
    const val TRUE_STORY = -22
    const val FEEL_GOOD_MOVIE = -23
    const val FEEL_GOOD_TV = -24
    const val SPORTS_MOVIE = -25
    const val SPORTS_TV = -26
    const val LGBTQ = -27
    const val TEEN = -28
    /** Any genre: a decade's classics, whose years come from its [GenreSpec.year]. */
    const val ANY = -29
    /** Any genre on TV, less news, reality, soaps and talk (a decade's TV classics are its series). */
    const val ANY_TV = -30

    /** TMDB's "anime" keyword: left out of every animation filter but Anime's own (anime stays in Anime). */
    const val ANIME_KEYWORD = Tmdb.ANIME_KEYWORD
    private const val NO_ANIME = "&without_keywords=${Tmdb.ANIME_KEYWORD}"

    /**
     * What the made-up ids ask TMDB for. TMDB's TV genres have no Horror, Thriller, Romance, History, Music, Fantasy or
     * Adventure of their own, so those are its keywords (horror 315058, supernatural horror 256183, slasher 12339,
     * j-horror 325319, k-horror 313454; thriller 316362, psychological thriller 12565; romance 9840, romantic comedy
     * 386551; historical drama 192772, period drama 15060; musical 4344; fantasy 293198, magic 2343, wizard 177912,
     * dragon, fairy tale, high, dark and sword-and-sorcery fantasy and fantasy world within Sci-Fi & Fantasy; adventure
     * 322942, treasure hunt, pirate, expedition, quest, jungle and swashbuckler within Action & Adventure), live action
     * where animation would crowd them out. Anime is Japanese animation; Kids movies are animated family films.
     */
    private val PSEUDO = mapOf(
        ANIME to "&with_genres=16&with_original_language=ja",
        TV_ADVENTURE to "&with_genres=10759&with_keywords=322942|6956|12988|1963|207372|10787|157186&without_genres=16",
        TV_FANTASY to "&with_genres=10765&with_keywords=293198|2343|177912|12554|3205|211227|234213|170362|177895&without_genres=16",
        TV_HISTORY to "&with_keywords=192772|15060",
        TV_HORROR to "&with_keywords=315058|256183|12339|325319|313454",
        MOVIE_KIDS to "&with_genres=16,10751$NO_ANIME",
        TV_MUSIC to "&with_keywords=4344",
        TV_ROMANCE to "&with_keywords=9840|386551&without_genres=16",
        TV_THRILLER to "&with_keywords=316362|12565",
        // Adult Animation: films rated R or NC-17 (TMDB's US certificates), shows by the "adult animation" keyword
        // (161919), neither family, kids nor anime. True Crime: crime documentaries.
        ADULT_ANIMATION_MOVIE to "&with_genres=16&without_genres=10751&certification_country=US&certification=R|NC-17$NO_ANIME",
        ADULT_ANIMATION_TV to "&with_keywords=161919&without_genres=10751,10762$NO_ANIME",
        TRUE_CRIME to "&with_genres=99,80",
        STAND_UP to "&with_keywords=9716",
        SITCOM to "&with_genres=35&with_keywords=193171&without_genres=10762",
        SUPERHERO_MOVIE to "&with_keywords=9715$NO_ANIME",
        SUPERHERO_TV to "&with_keywords=9715&without_genres=10762$NO_ANIME",
        MUSICAL_MOVIE to "&with_keywords=4344$NO_ANIME",
        MUSICAL_TV to "&with_keywords=4344&without_genres=10762$NO_ANIME",
        // Halloween: halloween 3335, halloween special 318653. Holiday: christmas 207317.
        HALLOWEEN to "&with_keywords=3335|318653",
        HOLIDAY to "&with_keywords=207317",
        // Mind-Bending: mind-bending, twist ending, plot twist, time loop, nonlinear timeline and narrative, parallel
        // universe, simulated reality, dream within a dream, unreliable narrator.
        MIND_BENDING to "&with_keywords=362567|326438|275311|10854|157171|321059|243230|3972|231173|174089$NO_ANIME",
        TRUE_STORY to "&with_keywords=9672",
        // Feel-Good: well-loved comedies, family films and romances with nothing dark among their genres.
        FEEL_GOOD_MOVIE to "&with_genres=35|10751|10749&without_genres=27,53,80,10752,99,9648,28&vote_average.gte=7$NO_ANIME",
        FEEL_GOOD_TV to "&with_genres=35&without_genres=80,9648,10768,99,10762,16,10764,10767&vote_average.gte=7.5",
        SPORTS_MOVIE to "&with_keywords=6075$NO_ANIME",
        SPORTS_TV to "&with_keywords=6075&without_genres=16,99",
        LGBTQ to "&with_keywords=158718$NO_ANIME",
        // Teen: teen comedy, teen drama, high school.
        TEEN to "&with_keywords=155722|193400|6270$NO_ANIME",
        ANY to "",
        ANY_TV to "&without_genres=10763,10764,10766,10767",
    )

    /**
     * The Discover filter for category [id] on [type]: "&with_genres=27" for a TMDB genre, its [PSEUDO] keywords or
     * blend for a made-up id, null for 0 (none for that type).
     */
    fun clause(type: MediaKind, id: Int, lang: String = ""): String? = when {
        id > 0 -> "&with_genres=$id" + noAnime(id, lang)
        id < 0 -> PSEUDO[id]
        else -> null
    }

    /** Filters that ask for anime (the Anime category, Japanese animation, Crunchyroll): the only walls that show it. */
    fun wantsAnime(f: DiscoverFilters) = f.genre == ANIME || (f.genre == 16 && f.lang == "ja") || f.service == "Crunchyroll"

    /** Animation (16) that isn't Japanese leaves anime out by its keyword: anime lives in Anime alone. */
    fun noAnime(genre: Int, lang: String) = if (genre == 16 && lang != "ja") NO_ANIME else ""

    val CATEGORIES = listOf(
        Category("Action", 28, 10759), Category("Adventure", 12, TV_ADVENTURE), Category("Animation", 16, 16),
        Category("Anime", ANIME, ANIME), Category("Comedy", 35, 35), Category("Crime", 80, 80),
        Category("Documentary", 99, 99), Category("Drama", 18, 18), Category("Family", 10751, 10751),
        Category("Fantasy", 14, TV_FANTASY), Category("History", 36, TV_HISTORY), Category("Horror", 27, TV_HORROR),
        Category("Kids", MOVIE_KIDS, 10762), Category("Music", 10402, TV_MUSIC), Category("Mystery", 9648, 9648),
        Category("Reality", 0, 10764), Category("Romance", 10749, TV_ROMANCE), Category("Sci-Fi", 878, 10765),
        Category("Thriller", 53, TV_THRILLER), Category("War", 10752, 10768), Category("Western", 37, 37),
    )

    /** The categories [type] has (Reality is shows only). */
    fun categories(type: MediaKind) = CATEGORIES.filter { it.id(type) != 0 }

    /** The wall's genre picker for [type] (the model's [DiscoverCategory]). */
    fun discoverCategories(type: MediaKind) = categories(type).map { DiscoverCategory(it.id(type), it.name) }

    /** The categories as Home's for-you rows read them ([Taste.forYou]). */
    fun tasteCategories(): List<Taste.Category> = CATEGORIES.map { Taste.Category(it.name, it.movie, it.tv) }

    /** Movie and TV genres that are the same idea under different ids. */
    private val MOVIE_TO_TV = mapOf(28 to 10759, 12 to 10759, 14 to 10765, 878 to 10765, 10752 to 10768)
    private val TV_TO_MOVIE = mapOf(10759 to 28, 10765 to 878, 10768 to 10752)

    fun mapGenre(id: Int, to: MediaKind): Int {
        if (id == 0) return 0
        val from = if (to == MediaKind.MOVIE) MediaKind.TV else MediaKind.MOVIE
        // A wall category carries over by name (Horror stays Horror), whatever ids it has on each side.
        CATEGORIES.firstOrNull { it.id(from) == id }?.id(to)?.takeIf { it != 0 }?.let { return it }
        if (id < 0) return 0
        if (genres(to).any { it.id == id }) return id
        val m = (if (to == MediaKind.MOVIE) TV_TO_MOVIE else MOVIE_TO_TV)[id] ?: return 0
        return if (genres(to).any { it.id == m }) m else 0
    }

    val LANGUAGES = listOf(
        "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian", "ja" to "Japanese",
        "ko" to "Korean", "hi" to "Hindi", "zh" to "Chinese", "pt" to "Portuguese", "tr" to "Turkish",
    )

    fun languageName(code: String) = LANGUAGES.firstOrNull { it.first == code }?.second

    val DECADES = listOf("2020s", "2010s", "2000s", "1990s", "1980s", "1970s")

    val RUNTIMES = listOf("Under 90 Min", "90–120 Min", "Over 2 Hours")

    val RATINGS = listOf(6, 7, 8)

    fun yearName(y: String) = when {
        y.isEmpty() -> null
        y == "older" -> "Before 1970"
        else -> y
    }

    /**
     * Streaming services the wall can filter by, one per name (Prime Video and Peacock have two TMDB ids), name to
     * "id|id". The Fire Stick lists the installed apps; a PC has none, so every service in [Tmdb.SERVICES]'s order.
     */
    fun services(): List<Pair<String, String>> =
        Tmdb.SERVICES.groupBy { it.name }.map { (name, s) -> name to s.joinToString("|") { it.providerId.toString() } }

    fun serviceIds(name: String) = Tmdb.SERVICES.filter { it.name == name }.joinToString("|") { it.providerId.toString() }

    private fun day(offsetDays: Int): String = DreamTime.ymdPlus(offsetDays)

    /** The /discover query for [f] (without the page). */
    fun query(f: DiscoverFilters): String {
        val sb = StringBuilder("&include_adult=false&include_video=false")
        val dateKey = RowMix.dateKey(f.type)
        var gte: String? = null
        var lte: String? = null
        var votes = 0
        when (f.sort) {
            // Top Rated is the modern acclaimed list (the last 25 years, rated by many), unless a year is picked: the
            // classics are a decade (or "Before 1970") away. Narrowed lists need fewer votes.
            DiscoverSort.TOP_RATED -> {
                sb.append("&sort_by=vote_average.desc")
                val narrow = f.genre != 0 || f.service.isNotEmpty() || f.lang.isNotEmpty()
                votes = if (f.movie) (if (narrow) 300 else 1000) else (if (narrow) 100 else 300)
                if (f.year.isEmpty()) gte = RowMix.yearsAgo(RowMix.ACCLAIMED_YEARS)
            }
            DiscoverSort.NEWEST -> { sb.append("&sort_by=$dateKey.desc"); lte = day(0); votes = 10 }
            DiscoverSort.COMING_SOON -> { sb.append("&sort_by=popularity.desc"); gte = day(1); lte = day(150) }
            // Popular: rated by at least a few dozen people, so a stray zero-vote listing never leads.
            else -> { sb.append("&sort_by=popularity.desc"); votes = if (f.movie) 40 else 20 }
        }
        when {
            f.year.isEmpty() -> {}
            f.year == "older" -> lte = minDate(lte, "1969-12-31")
            f.year.endsWith("s") -> f.year.dropLast(1).toIntOrNull()?.let { d ->
                gte = maxDate(gte, "$d-01-01"); lte = minDate(lte, "${d + 9}-12-31")
            }
            else -> f.year.toIntOrNull()?.let { y -> gte = maxDate(gte, "$y-01-01"); lte = minDate(lte, "$y-12-31") }
        }
        gte?.let { sb.append("&$dateKey.gte=$it") }
        lte?.let { sb.append("&$dateKey.lte=$it") }
        if (f.genre > 0) sb.append("&with_genres=${f.genre}").append(noAnime(f.genre, f.lang))
        else if (f.genre < 0) PSEUDO[f.genre]?.let { sb.append(it) }
        if (f.rating > 0) { sb.append("&vote_average.gte=${f.rating}"); votes = maxOf(votes, 100) }
        if (f.movie) when (f.runtime) {
            1 -> sb.append("&with_runtime.lte=89")
            2 -> sb.append("&with_runtime.gte=90&with_runtime.lte=120")
            3 -> sb.append("&with_runtime.gte=121")
        }
        if (f.lang.isNotEmpty()) sb.append("&with_original_language=${f.lang}")
        if (f.service.isNotEmpty()) serviceIds(f.service).takeIf { it.isNotEmpty() }?.let {
            sb.append("&with_watch_providers=$it&watch_region=US&with_watch_monetization_types=flatrate")
        }
        if (votes > 0) sb.append("&vote_count.gte=$votes")
        return sb.toString()
    }

    private fun minDate(a: String?, b: String) = if (a == null || b < a) b else a
    private fun maxDate(a: String?, b: String) = if (a == null || b > a) b else a

    /** "Nov 14" for a "yyyy-MM-dd" date (empty if it can't be read). */
    fun shortDate(date: String): String = DreamTime.shortDate(date)

    // ---- saved per profile (the same file and key as the Fire Stick: `browse[_<id>]`, "filters") ----

    private fun prefs() = DreamStore.shared.profileFile("browse")

    fun load(): DiscoverFilters = try {
        prefs().get("filters", DiscoverFilters.serializer()) ?: DiscoverFilters()
    } catch (e: Exception) {
        DiscoverFilters()
    }

    fun save(f: DiscoverFilters) = prefs().put("filters", DiscoverFilters.serializer(), f)
}
