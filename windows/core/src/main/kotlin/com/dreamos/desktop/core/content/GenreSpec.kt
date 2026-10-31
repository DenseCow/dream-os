package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.Genre
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.time.DreamTime

/**
 * A Browse by Genre shortcut (fire-home GenreSpec.kt): its card name, its [Discover] category ids for movies and TV (a
 * TMDB genre, a made-up id below 0 for TV's missing genres such as Horror, 0 where it has none for that type), an
 * optional original language (Anime), and the words its rows are named with: the plural ("Trending Comedies"), the
 * adjective ("Hidden Comedy Gems"), its series row and its new releases. [votes]: a small genre's vote floor for its
 * rows (movies; shows half), 0 for the usual. Its rows are [GenreShelves]'.
 */
class GenreSpec(
    val name: String,
    val movie: Int,
    val tv: Int,
    val lang: String = "",
    val plural: String = name,
    val adj: String = name,
    val series: String = "$adj Series",
    val fresh: String = "New $adj Releases",
    val votes: Int = 0,
    /** A decade ("1980s"): the category is that decade's titles ([Discover.ANY] genre, Discover's year filter). */
    val year: String = "",
    /** Its main type when that isn't movies (K-Drama, Adult Animation and True Crime are mostly shows). */
    main: MediaKind? = null,
    /** Browse shows it only between these days of the year ("MMdd", inclusive): Halloween in the autumn. */
    private val season: IntRange? = null,
    /** Its row of movies alone, and of shows alone (Movies, TV Shows, Search): "Horror Movies", "Horror Shows". */
    private val movieRow: String? = null,
    private val tvRow: String? = null,
) {
    fun rowTitle(type: MediaKind) = if (type == MediaKind.MOVIE) movieRow ?: "$adj Movies" else tvRow ?: "$adj Shows"

    /** The type its rows lead with and See All opens Discover on (movies where TMDB has the genre for movies). */
    val primary: MediaKind = main ?: if (movie != 0) MediaKind.MOVIE else MediaKind.TV

    /** Today (the viewer's zone) is in its [season] (always, for a category without one). */
    fun inSeason(): Boolean {
        val s = season ?: return true
        val d = DreamTime.zoned()
        return d.monthValue * 100 + d.dayOfMonth in s
    }

    /** Anime's own category: the one place anime titles show ([RowMix.isAnime]). */
    val anime get() = lang == "ja" && (movie == 16 || tv == 16 || movie == Discover.ANIME)

    /** Its Browse card / page id for the UI ([com.dreamos.desktop.core.model.CatalogPage.GenrePage]): the name. */
    fun toGenre() = Genre(id = name, name = name)

    companion object {
        /** Browse by Genre's cards, in this order: the full category set ([Discover.CATEGORIES]). */
        val SHORTCUTS = listOf(
            GenreSpec("Action", 28, 10759, plural = "Action", adj = "Action"),
            GenreSpec("Adventure", 12, Discover.TV_ADVENTURE, plural = "Adventures", adj = "Adventure"),
            GenreSpec("Comedy", 35, 35, plural = "Comedies", adj = "Comedy"),
            GenreSpec("Drama", 18, 18, plural = "Dramas", adj = "Drama"),
            GenreSpec("Horror", 27, Discover.TV_HORROR, plural = "Horror", adj = "Horror"),
            GenreSpec("Thriller", 53, Discover.TV_THRILLER, plural = "Thrillers", adj = "Thriller"),
            GenreSpec("Sci-Fi", 878, 10765, plural = "Sci-Fi", adj = "Sci-Fi"),
            GenreSpec("Fantasy", 14, Discover.TV_FANTASY, plural = "Fantasy", adj = "Fantasy", votes = 100),
            GenreSpec("Romance", 10749, Discover.TV_ROMANCE, plural = "Romance", adj = "Romantic", series = "Romance Series", fresh = "New Romance Releases"),
            GenreSpec("Crime", 80, 80, plural = "Crime Stories", adj = "Crime"),
            GenreSpec("Mystery", 9648, 9648, plural = "Mysteries", adj = "Mystery"),
            GenreSpec("Animation", 16, 16, plural = "Animation", adj = "Animated"),
            GenreSpec("Anime", 16, 16, "ja", plural = "Anime", adj = "Anime", votes = 80, tvRow = "Anime Series"),
            GenreSpec("Family", 10751, 10751, plural = "Family Favorites", adj = "Family"),
            GenreSpec("Kids", Discover.MOVIE_KIDS, 10762, plural = "Kids' Favorites", adj = "Kids'", series = "Kids' Shows", fresh = "New for Kids", votes = 80),
            GenreSpec("Documentary", 99, 99, plural = "Documentaries", adj = "Documentary", series = "Docuseries", votes = 40, movieRow = "Documentaries", tvRow = "Docuseries"),
            GenreSpec("Reality", 0, 10764, plural = "Reality TV", adj = "Reality", fresh = "New Reality Shows", votes = 20, tvRow = "Reality TV"),
            GenreSpec("History", 36, Discover.TV_HISTORY, plural = "Period Pieces", adj = "Historical", votes = 60),
            GenreSpec("War", 10752, 10768, plural = "War Stories", adj = "War", votes = 60),
            GenreSpec("Western", 37, 37, plural = "Westerns", adj = "Western", votes = 30),
            GenreSpec("Music", 10402, Discover.TV_MUSIC, plural = "Music & Musicals", adj = "Music", series = "Musical Series", fresh = "New Music Movies", votes = 50),
        )

        /**
         * Browse's Popular Categories (Netflix, Hulu and Apple TV style): moods, formats and themes beyond the genres,
         * each with movies and shows where TMDB has both. A seasonal one (Halloween, Holiday) leads while it's in season
         * and is hidden the rest of the year. Each was checked against TMDB to fill its rows (Award Winners was left
         * out: TMDB has no awards data, and its award keywords bring a handful).
         */
        val CATEGORIES = listOf(
            GenreSpec("Halloween", Discover.HALLOWEEN, Discover.HALLOWEEN, plural = "Halloween Favorites", adj = "Halloween", series = "Spooky Series",
                fresh = "New for Halloween", votes = 40, season = 915..1031),
            GenreSpec("Holiday", Discover.HOLIDAY, Discover.HOLIDAY, plural = "Holiday Favorites", adj = "Holiday", series = "Holiday Series",
                fresh = "New Holiday Movies", votes = 40, season = 1101..1231),
            GenreSpec("Adult Animation", Discover.ADULT_ANIMATION_MOVIE, Discover.ADULT_ANIMATION_TV, plural = "Adult Animation", adj = "Adult Animated",
                series = "Adult Animated Movies", fresh = "New Adult Animation", votes = 40, main = MediaKind.TV, tvRow = "Adult Animated Series"),
            GenreSpec("K-Drama", 18, 18, "ko", plural = "K-Dramas", adj = "K-Drama", series = "Korean Movies", fresh = "New K-Dramas", votes = 40,
                main = MediaKind.TV, movieRow = "Korean Movies", tvRow = "K-Dramas"),
            GenreSpec("True Crime", Discover.TRUE_CRIME, Discover.TRUE_CRIME, plural = "True Crime", adj = "True Crime", series = "True Crime Documentaries",
                fresh = "New True Crime", votes = 30, main = MediaKind.TV, movieRow = "True Crime Documentaries", tvRow = "True Crime Series"),
            GenreSpec("Stand-Up Comedy", Discover.STAND_UP, Discover.STAND_UP, plural = "Stand-Up Specials", adj = "Stand-Up", series = "Stand-Up Series",
                fresh = "New Stand-Up Specials", votes = 30, movieRow = "Stand-Up Specials", tvRow = "Stand-Up Series"),
            GenreSpec("Sitcoms", 0, Discover.SITCOM, plural = "Sitcoms", adj = "Sitcom", fresh = "New Sitcoms", votes = 40, tvRow = "Sitcoms"),
            GenreSpec("Superheroes", Discover.SUPERHERO_MOVIE, Discover.SUPERHERO_TV, plural = "Superhero Hits", adj = "Superhero", series = "Superhero Series",
                fresh = "New Superhero Releases"),
            GenreSpec("Based on a True Story", Discover.TRUE_STORY, Discover.TRUE_STORY, plural = "True Stories", adj = "True Story",
                series = "True Story Series", fresh = "New True Stories", movieRow = "True Story Movies", tvRow = "True Story Series"),
            GenreSpec("Feel-Good", Discover.FEEL_GOOD_MOVIE, Discover.FEEL_GOOD_TV, plural = "Feel-Good Favorites", adj = "Feel-Good",
                series = "Feel-Good Series", fresh = "New Feel-Good Releases", votes = 60),
            GenreSpec("Mind-Bending", Discover.MIND_BENDING, Discover.MIND_BENDING, plural = "Mind-Benders", adj = "Mind-Bending",
                series = "Mind-Bending Series", fresh = "New Mind-Benders", votes = 60),
            GenreSpec("Sports", Discover.SPORTS_MOVIE, Discover.SPORTS_TV, plural = "Sports Stories", adj = "Sports", series = "Sports Series",
                fresh = "New Sports Releases", votes = 50),
            GenreSpec("Teen", Discover.TEEN, Discover.TEEN, plural = "Teen Favorites", adj = "Teen", series = "Teen Series", fresh = "New Teen Releases"),
            GenreSpec("Musicals", Discover.MUSICAL_MOVIE, Discover.MUSICAL_TV, plural = "Musicals", adj = "Musical", series = "Musical Series",
                fresh = "New Musicals", votes = 50, movieRow = "Musicals", tvRow = "Musical Series"),
            GenreSpec("LGBTQ+", Discover.LGBTQ, Discover.LGBTQ, plural = "LGBTQ+ Stories", adj = "LGBTQ+", series = "LGBTQ+ Series",
                fresh = "New LGBTQ+ Releases", votes = 40),
        )

        /** Browse's Through the Decades: each decade's classics, any genre, movies and shows. */
        val DECADES = listOf(1970, 1980, 1990, 2000).map { y ->
            val adj = if (y < 2000) "’${y % 100}s" else "${y}s"
            GenreSpec("$adj Classics", Discover.ANY, Discover.ANY_TV, plural = "$adj Classics", adj = adj,
                series = if (y < 2000) "$adj TV Classics" else "$adj TV Favorites", year = "${y}s", votes = 100)
        }

        /** Every Browse category: the genres, the categories in season, the decades. */
        fun all(): List<GenreSpec> = SHORTCUTS + CATEGORIES.filter { it.inSeason() } + DECADES

        fun find(name: String?): GenreSpec? = name?.let { n -> (SHORTCUTS + CATEGORIES + DECADES).firstOrNull { it.name == n } }
    }
}
