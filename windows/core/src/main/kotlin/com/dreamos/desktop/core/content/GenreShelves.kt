package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.Title

/**
 * One row of a genre page (fire-home Shelf). [movie] and [tv] are the Discover filters for each type (the genre's own
 * filter with the row's on top), null where the row has no titles of that type. [mode] is how it's filled.
 */
class Shelf(val title: String, val mode: Mode, val movie: String?, val tv: String?) {
    enum class Mode {
        /** What's hot now: TMDB's trending titles in the genre, topped up from the last two years' most popular. */
        TRENDING,
        /** Released in the last year, most popular first. */
        NEW,
        /** Newly watchable at home ([NewReleases]: digital releases, new series and new seasons). */
        RELEASES,
        /** [RowMix.acclaimed] in the genre. */
        ACCLAIMED,
        /** [RowMix.hiddenGems] in the genre. */
        GEMS,
        /** [RowMix.variety]: popular now alternating with favorites (recent, unless the filter sets its years: a decade). */
        VARIETY,
        /** The most voted of all (a decade's blockbusters and best-known shows). */
        TOP_VOTED,
        /** The best rated by many (a decade's top rated). */
        TOP,
    }

    fun query(type: MediaKind) = if (type == MediaKind.MOVIE) movie else tv
}

/**
 * The rows of a Browse by Genre page (fire-home GenreShelves.kt), Netflix style: what's trending, new and acclaimed in
 * the genre, its series, then a curated set of sub-genres ([CURATED]: TMDB genres and keywords, so Horror has
 * Slashers, Zombies, Found Footage...), Hidden Gems, the genre on each streaming service and a couple of decades (the
 * only rows where old titles belong). Movies and shows mix wherever TMDB has the genre for both; TV's missing genres
 * (Horror, Thriller, Romance...) are [Discover]'s keyword stand-ins. Every row is deep ([RowPaging]: each loader pages
 * through the same lists) and tidied ([RowClaims], [RowMix.tidy]).
 *
 * Each curated row was checked against TMDB to bring at least ~20 recent, well-voted titles; rows that came back thin
 * were left out.
 */
object GenreShelves {
    /** Curated rows before the services. */
    private const val SUBS_FIRST = 3
    private const val BASE = "&include_adult=false&include_video=false&sort_by=popularity.desc"
    /** Rows with only keywords on TV (no genre to hold them) leave out cartoons and kids' and family shows. */
    private const val NOT_ON_TV = "16,10751,10762"

    /** A curated row: what it adds to the genre's filter for movies and for shows (null: none of that type). */
    class Pick(val title: String, val movie: String?, val tv: String?, val decade: Int = 0, val votes: Int = 0)

    /**
     * Keywords within the genre ("a|b": any of them). A niche row sets its own movie vote floor ([votes], shows half of
     * it) below the usual 150 / 60, so it fills.
     */
    private fun kw(title: String, ids: String, movie: Boolean = true, tv: Boolean = true, votes: Int = 0) =
        Pick(title, if (movie) "&with_keywords=$ids" else null, if (tv) "&with_keywords=$ids" else null, votes = votes)

    /** The genre and a second one at once (0: none for that type). */
    private fun both(title: String, movie: Int, tv: Int) =
        Pick(title, if (movie != 0) "&with_genres=$movie" else null, if (tv != 0) "&with_genres=$tv" else null)

    /** Anything else, per type. */
    private fun p(title: String, movie: String?, tv: String?) = Pick(title, movie, tv)

    /** A decade's classics (from [year] to [year] + 9): the one place older titles show. */
    private fun decade(name: String, year: Int) = Pick("’${(year % 100).toString().padStart(2, '0')}s $name Classics", "", "", year)

    /** The genre's own filter for [type] (null: TMDB has nothing of the genre for that type). */
    fun clause(spec: GenreSpec, type: MediaKind): String? =
        Discover.clause(type, if (type == MediaKind.MOVIE) spec.movie else spec.tv, spec.lang)?.let { c ->
            (if (spec.lang.isEmpty()) c else "$c&with_original_language=${spec.lang}") +
                (spec.year.removeSuffix("s").toIntOrNull()?.let { dates(type, it) } ?: "")
        }

    /** Types the genre has, its main one first. */
    fun types(spec: GenreSpec) = listOf(spec.primary, if (spec.primary == MediaKind.MOVIE) MediaKind.TV else MediaKind.MOVIE)
        .filter { (if (it == MediaKind.MOVIE) spec.movie else spec.tv) != 0 }

    /**
     * [clause] with [add] on top: genres AND together; [add]'s keywords replace the clause's (TV's stand-in for a missing
     * genre: "zombie" alone is already horror), and a row left with keywords but no genre keeps cartoons and kids' shows
     * out ("vampire" alone finds Adventure Time). Anything else [add] sets wins.
     */
    fun merge(clause: String, add: String): String {
        fun parse(s: String) = s.split('&').filter { it.contains('=') }.map { it.substringBefore('=') to it.substringAfter('=') }
        val m = LinkedHashMap<String, String>()
        for ((k, v) in parse(clause)) m[k] = v
        var replaced = false
        for ((k, v) in parse(add)) when (k) {
            "with_genres", "without_genres" -> m[k] = m[k]?.let { "$it,$v" } ?: v
            "with_keywords" -> { if (m.containsKey(k)) replaced = true; m[k] = v }
            else -> m[k] = v
        }
        if (replaced && !m.containsKey("with_genres")) m["without_genres"] = m["without_genres"]?.let { "$it,$NOT_ON_TV" } ?: NOT_ON_TV
        return m.entries.joinToString("") { "&${it.key}=${it.value}" }
    }

    private fun dates(type: MediaKind, year: Int) = RowMix.dateKey(type).let { "&$it.gte=$year-01-01&$it.lte=${year + 9}-12-31" }

    /** The curated rows only (sub-genres and decades), for [spec]. */
    fun curated(spec: GenreSpec): List<Shelf> = CURATED[spec.name].orEmpty().map { pick ->
        fun q(type: MediaKind): String? {
            val c = clause(spec, type) ?: return null
            val add = (if (type == MediaKind.MOVIE) pick.movie else pick.tv) ?: return null
            val votes = if (pick.votes > 0) pick.votes else spec.votes
            return merge(c, add) + (if (pick.decade != 0) dates(type, pick.decade) else "") +
                (if (votes > 0) "&vote_count.gte=${if (type == MediaKind.MOVIE) votes else votes / 2}" else "")
        }
        Shelf(pick.title, Shelf.Mode.VARIETY, q(MediaKind.MOVIE), q(MediaKind.TV))
    }

    /**
     * Every row of [spec]'s page in order (the page adds its hero and any personal rows): Trending, New Releases, the
     * genre's series, Critically Acclaimed, the first few curated rows, the genre on each of [services], the other
     * curated rows, Hidden Gems, then the decades. [services]: [ServicePages.homeServices] (the Fire Stick's installed
     * apps; a PC has none, so the fixed order the iPhone uses).
     */
    fun page(spec: GenreSpec, services: List<String> = ServicePages.homeServices()): List<Shelf> {
        val m = clause(spec, MediaKind.MOVIE)
        val t = clause(spec, MediaKind.TV)
        val out = ArrayList<Shelf>()
        val era = spec.year.isNotEmpty()
        if (era) {
            // A decade: its classics have no "trending" or "new"; the most popular and the most voted lead.
            out += Shelf("Popular ${spec.plural}", Shelf.Mode.VARIETY, m, t)
            out += Shelf("${spec.adj} Blockbusters", Shelf.Mode.TOP_VOTED, m, null)
            out += Shelf(spec.series, Shelf.Mode.TOP_VOTED, null, t)
            out += Shelf("Top Rated ${spec.plural}", Shelf.Mode.TOP, m, t)
        } else {
            out += Shelf("Trending ${spec.plural}", Shelf.Mode.TRENDING, m, t)
            out += Shelf("New Releases", Shelf.Mode.RELEASES, m, t)
            // A genre with movies and shows: the type it doesn't lead with gets a row of its own (its series, or
            // K-Drama's movies), Trending already being mostly the main one.
            val votes = if (spec.votes > 0) "&vote_count.gte=${spec.votes / 2}" else ""
            if (m != null && t != null) out += if (spec.primary == MediaKind.MOVIE) Shelf(spec.series, Shelf.Mode.VARIETY, null, t + votes)
                else Shelf(spec.series, Shelf.Mode.VARIETY, m + (if (spec.votes > 0) "&vote_count.gte=${spec.votes}" else ""), null)
            out += Shelf("Critically Acclaimed ${spec.plural}", Shelf.Mode.ACCLAIMED, m, t)
        }
        val (decades, subs) = curated(spec).partition { it.title.first() == '’' && !era }
        out += subs.take(SUBS_FIRST)
        // The services come early: their slice of a genre is small, and a row lower down would find most of it taken.
        for (name in services) onService(spec, name)?.let { out += it }
        out += subs.drop(SUBS_FIRST)
        out += Shelf("Hidden ${spec.adj} Gems", Shelf.Mode.GEMS, m, t)
        out += decades
        return out
    }

    /**
     * [spec] on streaming service [service] (US subscription catalog), movies and shows: "Horror on HBO Max". Null when
     * TMDB has no provider id for it. A service's slice of a category is small, so the vote floors are low.
     */
    fun onService(spec: GenreSpec, service: String, title: String = "${spec.plural} on $service"): Shelf? {
        val ids = Discover.serviceIds(service).takeIf { it.isNotEmpty() } ?: return null
        val on = "&with_watch_providers=$ids&watch_region=US&with_watch_monetization_types=flatrate"
        return Shelf(title, Shelf.Mode.VARIETY, clause(spec, MediaKind.MOVIE)?.plus("$on&vote_count.gte=40"), clause(spec, MediaKind.TV)?.plus("$on&vote_count.gte=20"))
    }

    /** [spec]'s movies alone or shows alone ([type]), popular now and favorites: Movies' and TV Shows' category rows, Search. Null: none of that type. */
    fun ofType(spec: GenreSpec, type: MediaKind, title: String = spec.rowTitle(type)): Shelf? {
        val c = clause(spec, type) ?: return null
        val votes = if (spec.votes > 0) "&vote_count.gte=${if (type == MediaKind.MOVIE) spec.votes else spec.votes / 2}" else ""
        return Shelf(title, Shelf.Mode.VARIETY, if (type == MediaKind.MOVIE) c + votes else null, if (type == MediaKind.TV) c + votes else null)
    }

    /** [s]'s titles, movies and shows interleaved (the genre's main type first). Pages with [RowPaging]. */
    fun load(spec: GenreSpec, s: Shelf): List<Title> = RowMix.interleave(RowPaging.all(*types(spec).map { type ->
        // Movies and shows at once (each its own lists, at once too): the row waits for one round trip, not six.
        { s.query(type)?.let { q -> try { withTagged(spec, s, type, q) } catch (e: Exception) { null } } }
    }.toTypedArray()).filterNotNull())

    /**
     * Anime's page: Japanese animation, and also animation TMDB tags "anime" in another language (Uzumaki, made in
     * English), which every other row leaves out ([Tmdb.animeClause]). TMDB can't OR the two, so a row without keywords
     * of its own runs both and interleaves them (duplicates dropped).
     */
    private fun withTagged(spec: GenreSpec, s: Shelf, type: MediaKind, q: String): List<Title> {
        val tagged = q.replace("&with_original_language=ja", "&with_keywords=${Tmdb.ANIME_KEYWORD}")
        if (!spec.anime || q.contains("with_keywords=") || tagged == q) return load(spec, s, type, q)
        val (ja, more) = RowPaging.all({ load(spec, s, type, q) }, { try { load(spec, s, type, tagged) } catch (e: Exception) { emptyList() } })
        return RowMix.interleave(listOf(ja, more))
    }

    /** [spec]'s own vote floor, else [usual]. */
    private fun low(spec: GenreSpec, usual: Int) = if (spec.votes > 0) spec.votes else usual

    private fun load(spec: GenreSpec, s: Shelf, type: MediaKind, q: String): List<Title> {
        val movie = type == MediaKind.MOVIE
        val d = RowMix.dateKey(type)
        val path = "/discover/${type.path}"
        return when (s.mode) {
            Shelf.Mode.TRENDING -> {
                val g = if (movie) spec.movie else spec.tv
                val votes = if (movie) minOf(40, low(spec, 40)) else minOf(20, low(spec, 40) / 2)
                val (week1, week2, popular) = RowPaging.all(
                    { if (g <= 0) emptyList() else Tmdb.list("/trending/${type.path}/week", type = type) },
                    { if (g <= 0) emptyList() else Tmdb.list("/trending/${type.path}/week", "&page=2", type) },
                    { Tmdb.list(path, "$BASE$q&$d.gte=${RowMix.yearsAgo(2)}&vote_count.gte=$votes", type) },
                )
                // Only titles out today: a chart lists films months ahead of release (STANDARDS 5).
                val hot = (week1 + week2).filter { g in it.genreIds && (spec.lang.isEmpty() || it.lang == spec.lang) && RowMix.released(it) }
                (hot + popular).distinctBy { it.key }
            }
            // Two pages: Trending, just above, takes many of the newest.
            Shelf.Mode.NEW -> "$BASE$q&$d.gte=${RowMix.yearsAgo(1)}&$d.lte=${RowMix.yearsAgo(0)}&vote_count.gte=${if (movie) minOf(20, low(spec, 40) / 2) else minOf(10, low(spec, 40) / 4)}".let { n ->
                RowPaging.all({ Tmdb.list(path, n, type) }, { Tmdb.list(path, "$n&page=2", type) }).flatten()
            }
            Shelf.Mode.RELEASES -> NewReleases.ofType(type, q)
            Shelf.Mode.ACCLAIMED -> RowMix.acclaimed(type, q, "${s.title} ${type.path}")
            // A decade's gems keep its own years (RowMix's are the last eighteen).
            Shelf.Mode.GEMS -> if (spec.year.isEmpty()) RowMix.hiddenGems(type, q, "${s.title} ${type.path}")
                else Tmdb.list(path, "$BASE$q&vote_average.gte=7&vote_count.gte=${if (movie) 150 else 60}&vote_count.lte=${if (movie) 1500 else 600}", type)
            Shelf.Mode.VARIETY -> RowMix.variety(type, BASE + q, "${s.title} ${type.path}")
            Shelf.Mode.TOP_VOTED -> "$BASE$q&sort_by=vote_count.desc".let { n ->
                RowPaging.all({ Tmdb.list(path, n, type) }, { Tmdb.list(path, "$n&page=2", type) }).flatten()
            }
            Shelf.Mode.TOP -> "$BASE$q&sort_by=vote_average.desc&vote_count.gte=${if (movie) 1000 else 250}".let { n ->
                RowPaging.all({ Tmdb.list(path, n, type) }, { Tmdb.list(path, "$n&page=2", type) }).flatten()
            }
        }
    }

    /**
     * Each genre's curated rows, keyed by [GenreSpec.name]. Keyword ids are TMDB's (/search/keyword);
     * on TV a keyword row stands on its keywords alone where TMDB has no such genre for shows.
     */
    private val CURATED: Map<String, List<Pick>> = mapOf(
        "Action" to listOf(
            kw("Superhero Showdowns", "9715"),
            kw("Spies & Secret Agents", "470|5265"),
            kw("Martial Arts", "779|780"),
            kw("Heists", "10051"),
            kw("Military & Special Forces", "15218|162365"),
            kw("Assassins & Hitmen", "782"),
            kw("Out for Revenge", "9748"),
            both("Action Comedies", 35, 35),
            both("Sci-Fi Action", 878, 10765),
            kw("Disaster Movies", "10617", tv = false),
            decade("Action", 1980), decade("Action", 1990),
        ),
        "Adventure" to listOf(
            kw("Epic Quests", "207372"),
            kw("Treasure Hunts", "6956"),
            kw("Pirates & Swashbucklers", "12988|157186"),
            kw("Survival Adventures", "10349"),
            kw("Jungles & Expeditions", "10787|1963|315709|5968|204975", votes = 80),
            kw("Dinosaurs", "12616"),
            kw("Road Trips", "7312", tv = false),
            both("Space Adventures", 878, 10765),
            both("Fantasy Adventures", 14, 10765),
            both("Family Adventures", 10751, 10751),
            both("Animated Adventures", 16, 0),
            decade("Adventure", 1980), decade("Adventure", 1990),
        ),
        "Comedy" to listOf(
            p("Romantic Comedies", "&with_genres=10749", "&with_keywords=386551"),
            kw("Sitcoms", "193171", movie = false),
            kw("Stand-Up Comedy", "9716"),
            kw("Dark Comedies", "10123"),
            kw("Satire & Parody", "8201|9755|11931"),
            kw("Teen Comedies", "155722|6270"),
            kw("Buddy Comedies", "167541|167316", votes = 80),
            kw("Workplace Comedies", "210605|1438|6282", votes = 80),
            kw("Mockumentaries", "11800"),
            both("Action Comedies", 28, 10759),
            both("Animated Comedies", 16, 16),
            decade("Comedy", 1980), decade("Comedy", 1990),
        ),
        "Drama" to listOf(
            kw("Based on a True Story", "9672"),
            both("Crime Dramas", 80, 80),
            kw("Legal Dramas", "222517|33519"),
            kw("Medical Dramas", "208788|11612"),
            kw("Period Dramas", "15060|192772"),
            kw("Coming of Age", "10683"),
            kw("Family Dramas", "12279"),
            kw("Political Dramas", "298528|6078"),
            kw("Sports Dramas", "6075"),
            kw("Biopics", "5565|360939"),
            p("Romantic Dramas", "&with_genres=10749", "&with_keywords=9840"),
            kw("Teen Dramas", "193400", movie = false),
            decade("Drama", 1980), decade("Drama", 1990),
        ),
        "Horror" to listOf(
            kw("Supernatural Horror", "256183|9853"),
            kw("Slashers", "12339"),
            kw("Psychological Horror", "295907"),
            kw("Zombies", "12377"),
            kw("Haunted Houses", "3358|162846"),
            kw("Possessions & Exorcisms", "9712|161261|2626|10375|241827", votes = 80),
            both("Horror Comedies", 35, 35),
            kw("Found Footage", "163053"),
            kw("Creature Features", "158126|210614|11100|13031"),
            kw("Vampires & Werewolves", "3133|12564"),
            kw("Folk Horror", "209568|155071", votes = 50),
            both("Sci-Fi Horror", 878, 10765),
            decade("Horror", 1980), decade("Horror", 1990),
        ),
        "Thriller" to listOf(
            kw("Psychological Thrillers", "12565"),
            p("Crime Thrillers", "&with_genres=80", "&with_genres=80"),
            p("Mystery Thrillers", "&with_genres=9648", "&with_genres=9648"),
            kw("Spy Thrillers", "470|5265"),
            kw("Conspiracy & Political Thrillers", "209817|298530|10410"),
            kw("Serial Killers", "10714"),
            kw("Survival Thrillers", "10349"),
            kw("Kidnappings & Hostages", "1930"),
            kw("Legal Thrillers", "254459|222517"),
            kw("Heist Thrillers", "10051"),
            both("Action Thrillers", 28, 10759),
            decade("Thriller", 1980), decade("Thriller", 1990),
        ),
        "Sci-Fi" to listOf(
            kw("Space Exploration", "9882|252634|3801"),
            kw("Time Travel", "4379"),
            kw("Aliens & First Contact", "9951|14909|211248"),
            kw("Dystopian Futures", "4565"),
            kw("AI & Robots", "310|14544"),
            kw("Post-Apocalyptic", "4458|10150|12332"),
            kw("Space Operas", "161176"),
            kw("Cyberpunk", "12190"),
            kw("Superheroes", "9715"),
            p("Sci-Fi Thrillers", "&with_genres=53", "&with_keywords=316362|12565"),
            both("Sci-Fi Action", 28, 10759),
            decade("Sci-Fi", 1980), decade("Sci-Fi", 1990),
        ),
        "Fantasy" to listOf(
            kw("Epic Fantasy", "211227|375250|170362"),
            kw("Sword & Sorcery", "234213"),
            kw("Magic & Wizards", "2343|177912"),
            kw("Dragons", "12554", votes = 80),
            kw("Fairy Tales", "3205"),
            kw("Dark Fantasy", "177895"),
            both("Fantasy Adventures", 12, 10759),
            both("Fantasy Comedies", 35, 35),
            both("Animated Fantasy", 16, 0),
            decade("Fantasy", 1980), decade("Fantasy", 1990),
        ),
        "Romance" to listOf(
            p("Romantic Comedies", "&with_genres=35", "&with_keywords=386551"),
            both("Romantic Dramas", 18, 18),
            kw("Period Romance", "15060|192772", tv = false, votes = 80),
            kw("Teen Romance", "368947|157303"),
            kw("Love Triangles", "128"),
            kw("Holiday Romance", "207317|65", tv = false),
            kw("Based on a Book", "818"),
            p("Romantic K-Dramas", null, "&with_original_language=ko"),
            kw("LGBTQ+ Love Stories", "158718"),
            kw("Weddings", "13027", tv = false),
            decade("Romance", 1980), decade("Romance", 1990),
        ),
        "Crime" to listOf(
            both("Crime Dramas", 18, 18),
            p("Crime Thrillers", "&with_genres=53", "&with_keywords=316362|12565"),
            kw("Gangsters & the Mob", "10391|3149"),
            kw("Detectives", "703"),
            kw("Police Procedurals", "268067"),
            kw("Heists", "10051"),
            kw("Serial Killers", "10714"),
            kw("Drug Cartels", "10175"),
            kw("Behind Bars", "378"),
            both("True Crime", 99, 99),
            both("Crime Comedies", 35, 35),
            decade("Crime", 1980), decade("Crime", 1990),
        ),
        "Mystery" to listOf(
            kw("Whodunits", "12570|207046"),
            kw("Detective Mysteries", "703"),
            p("Mystery Thrillers", "&with_genres=53", "&with_keywords=316362|12565"),
            both("Crime Mysteries", 80, 80),
            kw("Supernatural Mysteries", "6152|9853"),
            kw("Small-Town Secrets", "1415"),
            kw("Missing Persons", "156091"),
            p("British Mysteries", "&with_origin_country=GB", "&with_origin_country=GB"),
            both("Sci-Fi Mysteries", 878, 10765),
            kw("Psychological Mysteries", "12565"),
            decade("Mystery", 1980), decade("Mystery", 1990),
        ),
        "Animation" to listOf(
            p("Pixar & Disney Animation", "&with_companies=3|6125", null),
            p("DreamWorks & Illumination", "&with_companies=521|6704", null),
            both("Animated Family Favorites", 10751, 10751),
            both("Animated Comedies", 35, 35),
            both("Animated Adventures", 12, 10759),
            both("Animated Fantasy", 14, 10765),
            kw("Adult Animation", "161919"),
            kw("Animated Superheroes", "9715"),
            kw("Stop-Motion", "10121"),
            kw("Animated Musicals", "4344"),
            decade("Animation", 1980), decade("Animation", 1990),
        ),
        "Anime" to listOf(
            p("Anime Movies", "", null),
            both("Action Anime", 28, 10759),
            both("Fantasy Anime", 14, 10765),
            both("Comedy Anime", 35, 35),
            kw("Isekai", "237451"),
            kw("Shounen", "207826"),
            kw("Mecha", "10046"),
            kw("Supernatural Anime", "6152|9853|15001"),
            kw("Slice of Life", "9914"),
            kw("Sports Anime", "6075"),
            p("Romance Anime", "&with_genres=10749", "&with_keywords=9840"),
            decade("Anime", 1980), decade("Anime", 1990),
        ),
        "Documentary" to listOf(
            both("True Crime", 80, 80),
            p("Music Documentaries", "&with_genres=10402", "&with_keywords=4048|10229|6029"),
            kw("Nature & Wildlife", "18330|9902|18165"),
            p("History Documentaries", "&with_genres=36", "&with_keywords=192772|15060|1956"),
            kw("Sports Documentaries", "6075"),
            kw("Biographical Documentaries", "5565"),
            kw("Science & Space", "287067|9882"),
            kw("Food & Travel", "10637|1918"),
            kw("Politics & Power", "6078"),
            decade("Documentary", 1990),
        ),
        "Family" to listOf(
            both("Animated Family Favorites", 16, 16),
            p("Live-Action Family Favorites", "&without_genres=16", "&without_genres=16"),
            both("Family Comedies", 35, 35),
            both("Family Adventures", 12, 10759),
            both("Family Fantasy", 14, 10765),
            kw("Holiday Favorites", "207317|65"),
            kw("Animal Friends", "267848|18165"),
            kw("Fairy Tales & Princesses", "3205"),
            kw("Superheroes for Everyone", "9715"),
            kw("Family Sports Stories", "6075"),
            kw("Sing-Along Musicals", "4344"),
            decade("Family", 1980), decade("Family", 1990),
        ),
        "Kids" to listOf(
            p("Animated Movies for Kids", "", null),
            both("Funny Cartoons", 35, 35),
            both("Kids' Adventures", 12, 10759),
            kw("Animal Friends", "267848|18165"),
            kw("Princesses & Fairy Tales", "3205"),
            kw("Superheroes", "9715"),
            kw("Dinosaurs", "12616"),
            kw("Sing-Along Musicals", "4344"),
            p("Live-Action Kids' Shows", null, "&without_genres=16"),
            kw("Holiday Specials", "207317|65"),
            decade("Kids", 1990),
        ),
        "Reality" to listOf(
            kw("Competition Shows", "250845|271"),
            kw("Dating Shows", "13043"),
            kw("Cooking Competitions", "222083|1918"),
            kw("Home Makeovers & Real Estate", "182804|179867|34130"),
            kw("Survival Shows", "227620|10349"),
            kw("Celebrity Reality", "6562"),
        ),
        "History" to listOf(
            kw("Biopics", "5565|360939"),
            kw("Period Dramas", "15060|192772"),
            kw("World War II Stories", "1956"),
            kw("Ancient Worlds", "5049|162861"),
            kw("Kings & Queens", "9920"),
            kw("Medieval Times", "161257"),
            kw("Based on a True Story", "9672"),
            both("History Documentaries", 99, 99),
            decade("History", 1980), decade("History", 1990),
        ),
        "War" to listOf(
            kw("World War II", "1956"),
            kw("World War I", "2504"),
            kw("Vietnam War", "2957"),
            kw("Iraq & Afghanistan", "15087|7158"),
            kw("Based on a True Story", "9672"),
            both("Military Dramas", 18, 18),
            both("War Action", 28, 10759),
            both("War Documentaries", 99, 99),
            decade("War", 1980), decade("War", 1990),
        ),
        "Western" to listOf(
            kw("Gunslingers & Outlaws", "75|9503"),
            both("Western Dramas", 18, 18),
            both("Western Action", 28, 10759),
            both("Western Comedies", 35, 35),
            decade("Western", 1960), decade("Western", 1970), decade("Western", 1980), decade("Western", 1990),
        ),
        "Music" to listOf(
            kw("Musicals", "4344"),
            kw("Music Biopics", "5565|360939"),
            kw("Concert Films", "156205|6029"),
            p("Music Documentaries", "&with_genres=99", "&with_genres=99&with_keywords=4048|10229|6029"),
            kw("Dance Movies", "1691"),
            kw("Hip-Hop", "898"),
            kw("Rock & Roll", "18001"),
            kw("Singers & Bands", "4048|10229"),
            both("Animated Musicals", 16, 16),
            decade("Music", 1980), decade("Music", 1990),
        ),
        // ---- the categories beyond the genres (their own filter is mostly keywords: rows add genres, never keywords) ----
        "Halloween" to listOf(
            p("Halloween Horror", "&with_genres=27", "&with_genres=9648|10765&without_genres=10762,10751"),
            both("Family Halloween", 10751, 10751),
            both("Halloween Comedies", 35, 35),
            both("Animated Halloween", 16, 16),
            decade("Halloween", 1980), decade("Halloween", 1990),
        ),
        "Holiday" to listOf(
            both("Family Holiday Favorites", 10751, 10751),
            both("Holiday Comedies", 35, 35),
            p("Holiday Romance", "&with_genres=10749", null),
            both("Animated Holiday Specials", 16, 16),
            decade("Holiday", 1980), decade("Holiday", 1990),
        ),
        "Adult Animation" to listOf(
            both("Animated Comedies", 35, 35),
            p("Animated Action & Adventure", "&with_genres=28|12", "&with_genres=10759"),
            p("Animated Sci-Fi & Fantasy", "&with_genres=878|14", "&with_genres=10765"),
            p("Animated Superheroes", "&with_keywords=9715", null),
        ),
        "K-Drama" to listOf(
            p("Romantic K-Dramas", "&with_genres=10749", "&with_keywords=9840|386551"),
            p("K-Drama Thrillers", "&with_genres=53", "&with_keywords=316362|12565"),
            both("Crime K-Dramas", 80, 80),
            p("Fantasy K-Dramas", "&with_genres=14", "&with_genres=10765"),
            both("K-Drama Comedies", 35, 35),
            p("Historical K-Dramas", "&with_genres=36", "&with_keywords=192772|15060"),
        ),
        "True Crime" to listOf(
            kw("Serial Killers", "10714"),
            kw("Murder Cases", "9826|238812"),
            kw("Scams & Con Artists", "10453|11454|4373", votes = 20),
        ),
        "Sitcoms" to listOf(
            p("Family Sitcoms", null, "&with_genres=10751"),
            p("Animated Sitcoms", null, "&with_genres=16"),
            decade("Sitcom", 1980), decade("Sitcom", 1990), decade("Sitcom", 2000),
        ),
        "Superheroes" to listOf(
            both("Animated Superheroes", 16, 16),
            both("Superhero Comedies", 35, 35),
            p("Superhero Sci-Fi", "&with_genres=878", "&with_genres=10765"),
        ),
        "Based on a True Story" to listOf(
            both("True Story Dramas", 18, 18),
            both("True Crime Stories", 80, 80),
            p("True War Stories", "&with_genres=10752", "&with_genres=10768"),
            p("History Makers", "&with_genres=36", null),
        ),
        "Feel-Good" to listOf(
            decade("Feel-Good", 1980), decade("Feel-Good", 1990),
        ),
        "Mind-Bending" to listOf(
            p("Mind-Bending Sci-Fi", "&with_genres=878", "&with_genres=10765"),
            p("Mind-Bending Thrillers", "&with_genres=53", "&with_genres=9648"),
            p("Mind-Bending Horror", "&with_genres=27", null),
        ),
        "Sports" to listOf(
            both("Sports Dramas", 18, 18),
            both("Sports Comedies", 35, 35),
            p("Sports Documentaries", "&with_genres=99", null),
            decade("Sports", 1980), decade("Sports", 1990),
        ),
        "Teen" to listOf(
            both("Teen Comedies", 35, 35),
            both("Teen Dramas", 18, 18),
            p("Teen Romance", "&with_genres=10749", null),
            p("Teen Horror", "&with_genres=27", null),
            decade("Teen", 1980), decade("Teen", 1990),
        ),
        "Musicals" to listOf(
            both("Animated Musicals", 16, 16),
            both("Musical Comedies", 35, 35),
            p("Musical Romances", "&with_genres=10749", null),
            decade("Musicals", 1980), decade("Musicals", 1990),
        ),
        "LGBTQ+" to listOf(
            both("LGBTQ+ Dramas", 18, 18),
            both("LGBTQ+ Comedies", 35, 35),
            p("LGBTQ+ Love Stories", "&with_genres=10749", null),
        ),
    ) + DECADE_ROWS

    /** A decade's rows by genre ("’80s Action"), the same for each decade (its years come from its own filter). */
    private val DECADE_ROWS: Map<String, List<Pick>> get() = GenreSpec.DECADES.associate { d ->
        val a = d.adj
        d.name to listOf(
            both("$a Action", 28, 10759),
            both("$a Comedies", 35, 35),
            p("$a Horror", "&with_genres=27", "&with_keywords=315058|256183|12339"),
            p("$a Sci-Fi & Fantasy", "&with_genres=878|14", "&with_genres=10765"),
            both("$a Dramas", 18, 18),
            p("$a Thrillers", "&with_genres=53", "&with_keywords=316362|12565"),
            p("$a Romance", "&with_genres=10749", "&with_keywords=9840|386551"),
            both("$a Family Favorites", 10751, 10751),
            p("$a Animation", "&with_genres=16&without_keywords=${Discover.ANIME_KEYWORD}", "&with_genres=16&without_keywords=${Discover.ANIME_KEYWORD}"),
            both("$a Crime", 80, 80),
        )
    }
}
