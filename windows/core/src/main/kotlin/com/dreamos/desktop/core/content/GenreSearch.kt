package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.SearchResults
import com.dreamos.desktop.core.model.Title

/**
 * Search by genre (fire-home GenreSearch.kt): "horror" (or "scary") finds horror movies and horror shows, "horror movies" only the
 * movies, "crime shows" / "comedy series" / "horror tv" only the shows. Every Browse category answers to its
 * name and the ways people say it ("sci fi", "scifi", "romcom", "kdrama", "adult animation", "documentaries",
 * "80s"), a few sub-genres to theirs ("zombies", "slashers", "heists", "isekai"), and two at once combine
 * ("anime horror", "horror comedy").
 *
 * A real title search is never taken over: the whole query (less a "movies" or "shows" word) has to be a
 * genre's name or one of its words, or, from four letters on, the start of exactly one genre's words
 * ("horr", "docu"). "her" or "war of the worlds" stay title searches. The genre's rows show alongside the
 * title results, never instead of them, and load with Browse's own category loaders ([GenreShelves]).
 */
object GenreSearch {
    /** What a query names: a category, maybe one of its rows ([shelf]: a curated row's title), or two categories at once. */
    class Target(val spec: GenreSpec, val label: String, val shelf: String? = null, val with: GenreSpec? = null) {
        /** Anime results keep anime (everything else leaves it out: anime lives in the anime sections). */
        val anime get() = spec.anime || with?.anime == true
    }

    /** [target] for movies, shows or both ("horror movies": [shows] false). */
    class Match(val target: Target, val movies: Boolean, val shows: Boolean)

    private val MOVIE_WORDS = setOf("movies", "movie", "films", "film", "flicks")
    private val SHOW_WORDS = setOf("shows", "show", "series", "tv", "television", "programs")
    private val FILLER = setOf("best", "top", "good", "great", "popular", "some", "all")

    /** Category name to the words people search it by (lowercase; spaces, hyphens and apostrophes don't matter). */
    private val WORDS: Map<String, List<String>> = mapOf(
        "Action" to listOf("action", "action adventure", "action and adventure"),
        "Adventure" to listOf("adventure", "adventures"),
        "Comedy" to listOf("comedy", "comedies", "funny"),
        "Drama" to listOf("drama", "dramas"),
        "Horror" to listOf("horror", "scary", "spooky", "scary stuff"),
        "Thriller" to listOf("thriller", "thrillers", "suspense"),
        "Sci-Fi" to listOf("sci fi", "science fiction", "scifi"),
        "Fantasy" to listOf("fantasy", "fantasies"),
        "Romance" to listOf("romance", "romantic", "romances", "love stories"),
        "Crime" to listOf("crime", "crimes"),
        "Mystery" to listOf("mystery", "mysteries", "whodunit", "whodunits", "whodunnit"),
        "Animation" to listOf("animation", "animated", "cartoon", "cartoons"),
        "Anime" to listOf("anime", "animes"),
        "Family" to listOf("family", "family friendly"),
        "Kids" to listOf("kids", "kid", "children", "childrens", "for kids"),
        "Documentary" to listOf("documentary", "documentaries", "docs", "docuseries", "docu series"),
        "Reality" to listOf("reality", "reality tv"),
        "History" to listOf("history", "historical", "period", "period dramas", "period pieces"),
        "War" to listOf("war"),
        "Western" to listOf("western", "westerns", "cowboy", "cowboys"),
        "Music" to listOf("music"),
        "Halloween" to listOf("halloween"),
        "Holiday" to listOf("holiday", "holidays", "christmas", "xmas"),
        "Adult Animation" to listOf("adult animation", "adult animated", "adult cartoons", "adult cartoon", "animation for adults"),
        "K-Drama" to listOf("k drama", "k dramas", "korean drama", "korean dramas", "korean"),
        "True Crime" to listOf("true crime"),
        "Stand-Up Comedy" to listOf("stand up", "stand up comedy", "stand up specials", "comedy specials", "standup"),
        "Sitcoms" to listOf("sitcom", "sitcoms"),
        "Superheroes" to listOf("superhero", "superheroes", "superheros", "super heroes", "super hero"),
        "Musicals" to listOf("musical", "musicals"),
        "Based on a True Story" to listOf("based on a true story", "based on true story", "based on true events", "true story", "true stories"),
        "Feel-Good" to listOf("feel good", "feelgood", "uplifting", "heartwarming"),
        "Mind-Bending" to listOf("mind bending", "mind benders", "mind bender", "mind blowing", "mindbending"),
        "Sports" to listOf("sports", "sport"),
        "Teen" to listOf("teen", "teens", "teenage", "high school"),
        "LGBTQ+" to listOf("lgbt", "lgbtq", "lgbtq+", "gay", "queer"),
        "’70s Classics" to listOf("70s", "1970s", "seventies", "70s classics"),
        "’80s Classics" to listOf("80s", "1980s", "eighties", "80s classics"),
        "’90s Classics" to listOf("90s", "1990s", "nineties", "90s classics"),
        "2000s Classics" to listOf("2000s", "00s", "noughties"),
    )

    /** Sub-genres: their words to the category and its curated row ([GenreShelves.curated]) that has them. */
    private val SUBS: List<Triple<List<String>, String, String>> = listOf(
        Triple(listOf("romcom", "romcoms", "rom com", "rom coms", "romantic comedy", "romantic comedies"), "Comedy", "Romantic Comedies"),
        Triple(listOf("zombie", "zombies"), "Horror", "Zombies"),
        Triple(listOf("slasher", "slashers"), "Horror", "Slashers"),
        Triple(listOf("found footage"), "Horror", "Found Footage"),
        Triple(listOf("vampire", "vampires", "werewolf", "werewolves"), "Horror", "Vampires & Werewolves"),
        Triple(listOf("haunted house", "haunted houses", "ghost", "ghosts"), "Horror", "Haunted Houses"),
        Triple(listOf("heist", "heists"), "Action", "Heists"),
        Triple(listOf("spy", "spies", "secret agent", "secret agents"), "Action", "Spies & Secret Agents"),
        Triple(listOf("martial arts", "kung fu"), "Action", "Martial Arts"),
        Triple(listOf("disaster", "disaster movies"), "Action", "Disaster Movies"),
        Triple(listOf("time travel"), "Sci-Fi", "Time Travel"),
        Triple(listOf("aliens", "alien invasion"), "Sci-Fi", "Aliens & First Contact"),
        Triple(listOf("dystopian", "dystopia"), "Sci-Fi", "Dystopian Futures"),
        Triple(listOf("post apocalyptic", "apocalypse"), "Sci-Fi", "Post-Apocalyptic"),
        Triple(listOf("cyberpunk"), "Sci-Fi", "Cyberpunk"),
        Triple(listOf("space opera", "space operas"), "Sci-Fi", "Space Operas"),
        Triple(listOf("dinosaur", "dinosaurs"), "Adventure", "Dinosaurs"),
        Triple(listOf("pirate", "pirates"), "Adventure", "Pirates & Swashbucklers"),
        Triple(listOf("dark comedy", "dark comedies", "black comedy"), "Comedy", "Dark Comedies"),
        Triple(listOf("mockumentary", "mockumentaries"), "Comedy", "Mockumentaries"),
        Triple(listOf("biopic", "biopics"), "Drama", "Biopics"),
        Triple(listOf("legal drama", "legal dramas", "courtroom"), "Drama", "Legal Dramas"),
        Triple(listOf("medical drama", "medical dramas"), "Drama", "Medical Dramas"),
        Triple(listOf("coming of age"), "Drama", "Coming of Age"),
        Triple(listOf("serial killer", "serial killers"), "Crime", "Serial Killers"),
        Triple(listOf("gangster", "gangsters", "mafia", "mob"), "Crime", "Gangsters & the Mob"),
        Triple(listOf("detective", "detectives"), "Crime", "Detectives"),
        Triple(listOf("cop shows", "police", "police procedural", "police procedurals"), "Crime", "Police Procedurals"),
        Triple(listOf("isekai"), "Anime", "Isekai"),
        Triple(listOf("shonen", "shounen"), "Anime", "Shounen"),
        Triple(listOf("mecha"), "Anime", "Mecha"),
        Triple(listOf("slice of life"), "Anime", "Slice of Life"),
        Triple(listOf("dating shows", "dating show"), "Reality", "Dating Shows"),
        Triple(listOf("cooking shows", "cooking show", "cooking competitions", "cooking"), "Reality", "Cooking Competitions"),
        Triple(listOf("competition shows", "game shows"), "Reality", "Competition Shows"),
        Triple(listOf("ww2", "wwii", "world war 2", "world war ii"), "War", "World War II"),
    )

    /** Lowercase words, punctuation and apostrophes gone ("Sci-Fi" → "sci fi", "’80s" → "80s"). */
    private fun words(s: String) = s.lowercase().replace("’", "").replace("'", "").replace("&", " and ").replace("+", "")
        .split(NON_WORD).filter { it.isNotEmpty() }

    /** Compiled once: [suggest] squashes every category's words on each key typed. */
    private val NON_WORD = Regex("[^a-z0-9]+")

    private fun squash(s: String) = words(s).joinToString("")

    /** Squashed words to their target (built once). */
    private val index: Map<String, Target> by lazy {
        val all = GenreSpec.SHORTCUTS + GenreSpec.CATEGORIES + GenreSpec.DECADES
        val m = LinkedHashMap<String, Target>()
        for (spec in all) {
            val t = Target(spec, spec.name)
            m.putIfAbsent(squash(spec.name), t)
            for (w in WORDS[spec.name].orEmpty()) m.putIfAbsent(squash(w), t)
        }
        for ((ws, name, shelf) in SUBS) {
            val spec = all.firstOrNull { it.name == name } ?: continue
            if (GenreShelves.curated(spec).none { it.title == shelf }) continue
            val t = Target(spec, shelf, shelf = shelf)
            for (w in ws) m.putIfAbsent(squash(w), t)
        }
        m
    }

    /** The one target whose words begin with [part] (4+ letters), else null (none, or more than one). */
    private fun prefix(part: String): Target? {
        if (part.length < 4) return null
        val hits = index.filterKeys { it.startsWith(part) }.values.distinctBy { it.label }
        return hits.singleOrNull()
    }

    /** What [query] names, or null for an ordinary title search. */
    fun match(query: String): Match? {
        var w = words(query)
        if (w.isEmpty()) return null
        // "movies"/"shows" at either end keep one type; "tv shows" counts once.
        var movies = false
        var shows = false
        fun strip(): Boolean {
            val first = w.firstOrNull() ?: return false
            val last = w.last()
            when {
                w.size > 1 && last in MOVIE_WORDS -> { movies = true; w = w.dropLast(1) }
                w.size > 1 && last in SHOW_WORDS -> { shows = true; w = w.dropLast(1) }
                w.size > 1 && first in MOVIE_WORDS -> { movies = true; w = w.drop(1) }
                w.size > 1 && first in SHOW_WORDS -> { shows = true; w = w.drop(1) }
                w.size > 1 && first in FILLER -> w = w.drop(1)
                else -> return false
            }
            return true
        }
        while (strip()) {}
        if (movies && shows) { movies = false; shows = false }
        val bothKinds = !movies && !shows
        val whole = w.joinToString("")
        val target = index[whole]
            ?: combo(w)
            // A prefix only for the whole query typed so far, never for a word inside a longer one.
            ?: (if (w.size == 1 || index.keys.any { it.startsWith(whole) }) prefix(whole) else null)
            ?: return null
        // "reality movies", "sitcom movies": a type the category doesn't have falls back to what it has.
        val hasMovie = has(target, MediaKind.MOVIE)
        val hasTv = has(target, MediaKind.TV)
        if (!hasMovie && !hasTv) return null
        val m = (bothKinds || movies) && hasMovie || (shows && !hasTv)
        val s = (bothKinds || shows) && hasTv || (movies && !hasMovie)
        return Match(target, m, s)
    }

    /** Two categories at once: "anime horror", "horror comedy" (each part a category's own words, not a sub-genre). */
    private fun combo(w: List<String>): Target? {
        if (w.size < 2) return null
        for (i in 1 until w.size) {
            val a = index[w.take(i).joinToString("")]?.takeIf { it.shelf == null } ?: continue
            val b = index[w.drop(i).joinToString("")]?.takeIf { it.shelf == null } ?: continue
            if (a.spec.name == b.spec.name || a.spec.year.isNotEmpty() && b.spec.year.isNotEmpty()) continue
            // The more specific one leads: a language or a decade sets the frame ("Anime Horror", "’80s Horror").
            val (lead, with) = if (b.spec.lang.isNotEmpty() || b.spec.year.isNotEmpty()) b to a else a to b
            return Target(lead.spec, "${lead.spec.adj} ${with.spec.name}", with = with.spec)
        }
        return null
    }

    private fun has(t: Target, type: MediaKind) = query(t, type) != null

    /** [t]'s Discover filter for [type] (null: nothing of that type). */
    private fun query(t: Target, type: MediaKind): String? {
        t.shelf?.let { name -> return GenreShelves.curated(t.spec).firstOrNull { it.title == name }?.query(type) }
        val a = GenreShelves.clause(t.spec, type) ?: return null
        val with = t.with ?: return GenreShelves.ofType(t.spec, type)?.query(type)
        val b = GenreShelves.clause(with, type) ?: return null
        return GenreShelves.merge(a, b) + "&vote_count.gte=${if (type == MediaKind.MOVIE) 40 else 20}"
    }

    /** The row's heading for [type]: "Horror Movies", "Crime Shows", "Romantic Comedies" (a row named for itself). */
    fun heading(m: Match, type: MediaKind): String {
        val t = m.target
        if (t.shelf != null) return if (m.movies && m.shows && type == MediaKind.TV) "${t.label} on TV" else t.label
        if (t.with != null) return "${t.label} ${if (type == MediaKind.MOVIE) "Movies" else "Shows"}"
        return t.spec.rowTitle(type)
    }

    /**
     * [m]'s titles of [type], best known first (popular now and all-time favorites, [RowMix.variety]), tidied like
     * every row (anime only for anime). Empty when none or the fetch fails.
     */
    fun load(m: Match, type: MediaKind): List<Title> {
        val q = query(m.target, type) ?: return emptyList()
        val shelf = Shelf(heading(m, type), Shelf.Mode.VARIETY, if (type == MediaKind.MOVIE) q else null, if (type == MediaKind.TV) q else null)
        return try {
            RowMix.tidy(GenreShelves.load(m.target.spec, shelf).filter { it.kind == type }, artFirst = false, anime = m.target.anime)
        } catch (e: Exception) { emptyList() }
    }

    /** The categories (and Browse tiles) [m] opens: the category, and the second of a pair. */
    fun specs(m: Match): List<GenreSpec> = listOfNotNull(m.target.spec, m.target.with)

    /** Below this many titles (movies and shows together) a search shows no genre rows (fire-home CinemaSearch). */
    const val GENRE_MIN = 5

    /**
     * Search's genre rows for [query] (the model's [SearchResults.GenreHits]): the matched category's movies and shows,
     * their headings and the Browse pages it opens. Null when the query names no category or the rows bring fewer than
     * [GENRE_MIN] titles. Blocking: an IO thread.
     */
    fun hits(query: String): SearchResults.GenreHits? {
        val m = match(query) ?: return null
        val (movies, shows) = RowPaging.all(
            { if (m.movies) load(m, MediaKind.MOVIE) else emptyList() },
            { if (m.shows) load(m, MediaKind.TV) else emptyList() },
        )
        if (movies.size + shows.size < GENRE_MIN) return null
        return SearchResults.GenreHits(
            label = m.target.label,
            browse = specs(m).map { it.toGenre() },
            movieHeading = heading(m, MediaKind.MOVIE), movies = movies,
            showHeading = heading(m, MediaKind.TV), shows = shows,
        )
    }

    /** Category names that begin with what's typed (2+ letters), for the suggestions under the keyboard. */
    fun suggest(query: String, max: Int = 2): List<String> {
        val q = squash(query)
        if (q.length < 2) return emptyList()
        val all = GenreSpec.SHORTCUTS + GenreSpec.CATEGORIES.filter { it.inSeason() } + GenreSpec.DECADES
        val named = all.filter { s -> squash(s.name).startsWith(q) && squash(s.name) != q }.map { it.name }
        val byWord = if (q.length < 3) emptyList() else all.filter { s -> s.name !in named && WORDS[s.name].orEmpty().any { squash(it).startsWith(q) && squash(it) != q } }.map { it.name }
        return (named + byWord).take(max)
    }
}
