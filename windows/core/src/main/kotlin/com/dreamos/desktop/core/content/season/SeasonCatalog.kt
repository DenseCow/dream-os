package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.content.SeasonRow.Kind.ACCLAIMED
import com.dreamos.desktop.core.content.SeasonRow.Kind.NEW
import com.dreamos.desktop.core.content.SeasonRow.Kind.PICKS
import com.dreamos.desktop.core.content.SeasonRow.Kind.POPULAR
import com.dreamos.desktop.core.content.SeasonRow.Kind.TOP_VOTED
import com.dreamos.desktop.core.content.SeasonRow.Kind.TRENDING
import com.dreamos.desktop.core.content.SeasonRow.Kind.VARIETY

/**
 * Every seasonal and event hub (fire-home SeasonCatalog.kt), the whole year round (plain data: [SeasonCalendar] picks the one that's on).
 * The four base seasons cover every day; holidays, heritage months and events take over by [SeasonalHub.priority]:
 *
 *  - base seasons 10 · heritage and awareness months 40-55 · Awards Season 50 (85 in its last days)
 *  - Valentine's Day 50 (90 in its last week) · Easter 44 (74 in its last days)
 *  - Mother's and Father's Day, Thanksgiving, St. Patrick's 60
 *  - Halloween 50 · Fourth of July, Christmas 70 · New Year's 80
 *
 * Rows are TMDB Discover filters (keyword ids from /search/keyword, each checked to fill a row with
 * well-known titles) or hand-picked titles. Heritage months and Pride are built from TMDB's own topic
 * keywords and acclaimed titles, never from clichés. Anime never shows here (every row is tidied without it).
 */
object SeasonCatalog {
    /** Movies a kids profile may see: US rating G or PG. */
    private const val KIDS = "&certification_country=US&certification.lte=PG"
    private const val NO_ANIME = "&without_keywords=210024"
    /** A TV row that stands on keywords alone: no cartoons, kids' or family shows (they'd crowd in on a keyword). */
    private const val GROWN_TV = "&without_genres=16,10751,10762"
    /** TMDB has no Horror genre for shows: horror, supernatural horror, slasher, J- and K-horror. */
    private const val HORROR_TV = "&with_keywords=315058|256183|12339|325319|313454"
    private const val FEEL_GOOD = "&with_genres=35|10751|10749&without_genres=27,53,80,10752,99,9648,28&vote_average.gte=7$NO_ANIME"
    private const val FEEL_GOOD_TV = "&with_genres=35&without_genres=80,9648,10768,99,10762,16,10764,10767&vote_average.gte=7.5"
    private const val CHRISTMAS = "&with_keywords=207317"
    private const val LGBTQ = "&with_keywords=158718"

    private fun row(title: String, kind: SeasonRow.Kind, movie: String?, tv: String? = null, kids: Boolean = false, genre: Int = 0) =
        SeasonRow(title, kind, movie, tv, kids, genre = genre)

    private fun picks(title: String, kids: Boolean = false, vararg ids: String) = SeasonRow(title, PICKS, null, null, kids, ids.toList())

    private fun movies(vararg ids: Int) = ids.map { "movie:$it" }.toTypedArray()

    private fun shows(vararg ids: Int) = ids.map { "tv:$it" }.toTypedArray()

    /** A family movie night (kids-safe): well-loved family films. */
    private fun familyNight(title: String = "Family Movie Night") =
        row(title, VARIETY, "&with_genres=10751&vote_average.gte=6.8$KIDS$NO_ANIME", "&with_genres=10751|10762&vote_average.gte=7.5&without_genres=16", kids = true)

    // ---- the four base seasons (one is always on) ----

    private val WINTER = SeasonalHub(
        id = "winter", title = "Cozy Winter Nights", kicker = "Winter",
        tagline = "Snowbound Thrillers, Fireside Mysteries and Feel-Good Favorites",
        glyph = "Season:Snowflake", hue = 7, priority = 10, base = true,
        window = SeasonWindow.fixed(1201, 229),
        pick = row("Tonight's Pick", POPULAR, "&with_keywords=10794|1442|156783|3623&vote_average.gte=6.8&vote_count.gte=800&sort_by=vote_count.desc"),
        rows = listOf(
            row("Snowbound Thrillers", VARIETY, "&with_keywords=10794|1442|156783&with_genres=53|27|9648&vote_count.gte=100"),
            row("Fireside Mysteries", VARIETY, "&with_keywords=12570|207046|703&with_genres=9648&without_genres=27&vote_count.gte=150", "&with_keywords=12570|207046|703$GROWN_TV&vote_count.gte=40"),
            row("Cozy Feel-Good Favorites", VARIETY, FEEL_GOOD, FEEL_GOOD_TV),
            row("Epic Fantasy Marathons", TOP_VOTED, "&with_genres=14&vote_count.gte=3000&vote_average.gte=7$NO_ANIME"),
            row("Bingeable Dramas", ACCLAIMED, null, "&with_genres=18"),
            row("Winter Wonderlands", VARIETY, "&with_keywords=10794|1442|10115|263324&with_genres=10751|16$KIDS$NO_ANIME&vote_count.gte=50", kids = true),
            familyNight(),
        ),
    )

    private val SPRING = SeasonalHub(
        id = "spring", title = "Spring Into Something New", kicker = "Spring",
        tagline = "Fresh Releases, New Series and Feel-Good Favorites",
        glyph = "Season:Blossom", hue = 2, priority = 10, base = true,
        window = SeasonWindow.fixed(301, 531),
        pick = row("Tonight's Pick", POPULAR, "&vote_average.gte=7&vote_count.gte=400&primary_release_date.gte={YEARSAGO:2}&sort_by=vote_count.desc$NO_ANIME"),
        rows = listOf(
            row("New Releases", NEW, "", ""),
            row("New Series to Start", NEW, null, "&without_genres=10763,10764,10766,10767,10762"),
            row("Fresh Rom-Coms", VARIETY, "&with_genres=10749,35&primary_release_date.gte={YEARSAGO:12}&vote_count.gte=150", "&with_genres=35&with_keywords=9840&without_genres=16&vote_count.gte=60"),
            row("Coming-of-Age Stories", VARIETY, "&with_keywords=10683&vote_count.gte=300", "&with_keywords=10683$GROWN_TV&vote_count.gte=40"),
            row("Nature & Wildlife", VARIETY, "&with_genres=99&with_keywords=18330|9902|221355&vote_count.gte=20", "&with_genres=99&with_keywords=18330|9902|221355&vote_count.gte=5", kids = true),
            row("Feel-Good Favorites", VARIETY, FEEL_GOOD, FEEL_GOOD_TV),
            familyNight(),
        ),
    )

    private val SUMMER = SeasonalHub(
        id = "summer", title = "Summer Blockbusters", kicker = "Summer",
        tagline = "Popcorn Thrills, Road Trips and Endless Summer Fun",
        glyph = "Season:Sun", hue = 5, priority = 10, base = true,
        window = SeasonWindow.fixed(601, 831),
        pick = row("Tonight's Pick", POPULAR, "&with_genres=28|12|878&vote_average.gte=7.2&vote_count.gte=8000&sort_by=vote_count.desc$NO_ANIME"),
        rows = listOf(
            row("Biggest Blockbusters", TOP_VOTED, "&with_genres=28|12|878&primary_release_date.gte={YEARSAGO:12}&vote_average.gte=6.5$NO_ANIME"),
            row("Summer Vacation", VARIETY, "&with_keywords=14714|13088&without_genres=27,53,10749&vote_count.gte=150$NO_ANIME"),
            row("Summer Camp", VARIETY, "&with_keywords=5767&vote_count.gte=40"),
            row("Road Trips", VARIETY, "&with_keywords=7312&vote_count.gte=150"),
            row("Deep Water Thrills", VARIETY, "&with_keywords=15097|158108&with_genres=53|27|28|12&without_genres=16&vote_count.gte=100&primary_release_date.gte=1970-01-01"),
            row("Superhero Showdowns", VARIETY, "&with_keywords=9715$NO_ANIME", "&with_keywords=9715&without_genres=10762$NO_ANIME"),
            row("Summer Series to Binge", POPULAR, null, "&vote_average.gte=7.5&vote_count.gte=300&first_air_date.gte={YEARSAGO:4}&without_genres=16,10762,10764,10767&sort_by=vote_count.desc"),
            row("Animated Summer Hits", VARIETY, "&with_genres=16,10751$KIDS$NO_ANIME", kids = true),
        ),
    )

    private val FALL = SeasonalHub(
        id = "fall", title = "Fall Favorites", kicker = "Fall",
        tagline = "Cozy Mysteries, Prestige Dramas and New Season Premieres",
        glyph = "Season:Leaf", hue = 4, priority = 10, base = true,
        window = SeasonWindow.fixed(901, 1130),
        pick = row("Tonight's Pick", POPULAR, "&with_genres=18|9648&vote_average.gte=7.6&vote_count.gte=4000&sort_by=vote_count.desc$NO_ANIME"),
        rows = listOf(
            row("Cozy Mysteries", VARIETY, "&with_keywords=12570|207046|703&with_genres=9648&without_genres=27&vote_count.gte=150", "&with_keywords=12570|207046|703$GROWN_TV&vote_count.gte=40"),
            row("New Season Premieres", NEW, null, "&without_genres=10763,10764,10766,10767,10762"),
            row("Prestige Dramas", ACCLAIMED, "&with_genres=18", "&with_genres=18"),
            row("Small-Town Secrets", VARIETY, "&with_keywords=1415&with_genres=9648&without_genres=27&vote_count.gte=100", "&with_keywords=1415&with_genres=9648|80&vote_count.gte=30"),
            row("Football Season", VARIETY, "&with_keywords=579|11671|5621&vote_count.gte=50", "&with_keywords=579|11671&vote_count.gte=10"),
            row("Autumn Feel-Good Favorites", VARIETY, FEEL_GOOD, FEEL_GOOD_TV),
            familyNight(),
        ),
    )

    // ---- holidays, heritage months and events ----

    private val NEW_YEAR = SeasonalHub(
        id = "newyear", title = "Ring In the New Year", kicker = "New Year's",
        tagline = "The Year's Best, Fresh Starts and Countdown Classics",
        glyph = "Season:NewYear", hue = 5, priority = 80,
        window = SeasonWindow.fixed(1227, 102),
        countdown = Countdown({ y -> SDay.of(y, 1, 1) }, "Day", "the New Year", "Happy New Year", eve = "Tonight Is New Year's Eve"),
        pick = row("Tonight's Pick", POPULAR, "&primary_release_year={LASTYEAR}&vote_average.gte=6.8&vote_count.gte=300&sort_by=vote_count.desc$NO_ANIME"),
        kidsPick = row("Tonight's Pick", POPULAR, "&primary_release_year={LASTYEAR}&with_genres=10751$KIDS$NO_ANIME&sort_by=vote_count.desc"),
        rows = listOf(
            row("The Year's Biggest Movies", POPULAR, "&primary_release_year={LASTYEAR}&vote_count.gte=100&sort_by=vote_count.desc$NO_ANIME"),
            row("The Year's Best New Series", POPULAR, null, "&first_air_date.gte={LASTYEAR}-01-01&first_air_date.lte={LASTYEAR}-12-31&vote_count.gte=40&without_genres=10762,10763,10764,10766,10767&sort_by=vote_count.desc"),
            row("Critically Acclaimed in {LASTYEAR}", POPULAR, "&primary_release_year={LASTYEAR}&vote_average.gte=7.2&vote_count.gte=300&sort_by=vote_average.desc$NO_ANIME"),
            row("New Year's Eve Movies", VARIETY, "&with_keywords=613|252123|5632&vote_count.gte=20&primary_release_date.gte=1960-01-01"),
            row("Second Chances & Time Loops", VARIETY, "&with_keywords=10854|366519&vote_count.gte=100", "&with_keywords=10854$GROWN_TV&vote_count.gte=20"),
            row("Feel-Good Fresh Starts", VARIETY, FEEL_GOOD, FEEL_GOOD_TV),
            row("Party Movies", VARIETY, "&with_keywords=286407|9694|11798|191260|221475&with_genres=35&vote_count.gte=50"),
            familyNight("Family Celebration Favorites"),
        ),
    )

    private val VALENTINES = SeasonalHub(
        id = "valentines", title = "Date Night", kicker = "Valentine's Day",
        tagline = "Rom-Coms, Love Stories and Crowd-Pleasers for Two",
        glyph = "Season:Valentine", hue = 2, priority = 50, boostDays = 6, boost = 40,
        window = SeasonWindow.fixed(201, 214), genres = setOf("Romance"),
        countdown = Countdown({ y -> SDay.of(y, 2, 14) }, "Day", "Valentine's Day", "Happy Valentine's Day"),
        pick = row("Tonight's Pick", POPULAR, "&with_genres=10749&vote_average.gte=7.2&vote_count.gte=2000&sort_by=vote_count.desc$NO_ANIME"),
        rows = listOf(
            row("Romantic Comedies", VARIETY, "&with_genres=10749,35", "&with_genres=35&with_keywords=9840&without_genres=16&vote_count.gte=60"),
            row("Swoon-Worthy Love Stories", ACCLAIMED, "&with_genres=10749,18"),
            row("Date Night Crowd-Pleasers", POPULAR, "&with_genres=35|28|12&vote_average.gte=7&vote_count.gte=3000&primary_release_date.gte=2000-01-01&sort_by=vote_count.desc$NO_ANIME"),
            row("Romance Series", VARIETY, null, "&with_keywords=9840|386551&without_genres=16,10762"),
            row("Valentine's Day Movies", VARIETY, "&with_keywords=160404|376604&vote_count.gte=10"),
            row("Tearjerkers", VARIETY, "&with_keywords=156924|346048&vote_count.gte=100"),
            row("Galentine's Night", VARIETY, "&with_keywords=5248&with_genres=35&vote_count.gte=100"),
            row("Love Stories Through the Decades", TOP_VOTED, "&with_genres=10749&primary_release_date.gte=1985-01-01&primary_release_date.lte=2009-12-31"),
            row("Animated Love Stories", VARIETY, "&with_genres=16,10749$KIDS$NO_ANIME&vote_count.gte=100", kids = true),
        ),
    )

    private val BLACK_HISTORY = SeasonalHub(
        id = "blackhistory", title = "Black History Month", kicker = "Celebrating Black Stories",
        tagline = "Landmark Films, Bold Voices and Stories That Shaped History",
        glyph = "Season:Stories", hue = 3, priority = 55,
        window = SeasonWindow.fixed(201, 229), genres = setOf("Documentary", "History", "Drama"),
        pick = picks("Tonight's Pick", false, *movies(376867, 76203, 273895, 1883, 925, 381284, 419430, 284054, 465914, 393457, 873, 583406, 615667, 9665, 650, 157354, 522212, 1677, 1233413)),
        kidsPick = picks("Tonight's Pick", true, *movies(508442, 10198, 381284, 13751, 324857, 10637)),
        rows = listOf(
            picks("Essential Black Cinema", false, *movies(376867, 76203, 273895, 1883, 925, 381284, 419430, 284054, 465914, 393457, 873,
                583406, 615667, 9665, 650, 157354, 522212, 1677, 506528, 424781, 854239, 1056360, 1233413, 470044, 724495)),
            row("Civil Rights Stories", VARIETY, "&with_keywords=270891|4098|233016|6984|381945&with_genres=18|36|99&without_genres=878,35,27&vote_count.gte=50",
                "&with_keywords=270891|4098|233016$GROWN_TV&vote_count.gte=5"),
            row("Black Biopics & True Stories", VARIETY, "&with_keywords=256015|251436|190675&with_genres=36|18&vote_count.gte=150"),
            picks("Landmark Series", false, *shows(81355, 82816, 79788, 71694, 54650, 61733, 67679, 79086, 66606, 13555, 72071, 64150, 107365, 70767, 115)),
            picks("Comedy Favorites", false, *movies(9602, 10634, 10611, 417870, 9737, 16162), *shows(65495, 67883, 61381, 125935, 1892, 252, 171, 4270)),
            row("Powerful Documentaries", VARIETY, "&with_keywords=256015|251436|190675|270891|12425&with_genres=99&vote_count.gte=20",
                "&with_keywords=256015|251436|190675|270891&with_genres=99&vote_count.gte=3"),
            picks("Music Legends", false, *movies(1677, 615667, 592863, 1125, 15765, 277216, 239566, 696157)),
            picks("Family Favorites", true, *movies(508442, 10198, 381284, 13751, 324857, 10637, 196867, 589739)),
        ),
    )

    private val AWARDS = SeasonalHub(
        id = "awards", title = "And the Award Goes To", kicker = "Awards Season",
        tagline = "Best Picture Winners, This Year's Contenders and Modern Masterpieces",
        glyph = "Season:Award", hue = 5, priority = 50, boostDays = 3, boost = 35,
        window = SeasonWindow.until(215) { y -> SDay.awardsNight(y) },
        countdown = Countdown({ y -> SDay.awardsNight(y) }, "Day", "Awards Night", "Awards Night Is Here"),
        pick = picks("Tonight's Pick", false, *movies(98, 453, 122, 70, 1422, 6977, 12405, 12162, 45269, 74643, 68734, 76203, 194662, 314365,
            376867, 399055, 490132, 496243, 581734, 776503, 545611, 872585, 1064213)),
        kidsPick = picks("Tonight's Pick", true, *movies(12, 9806, 2062, 10681, 14160, 10193, 150540, 269149, 354912, 568124)),
        rows = listOf(
            row("This Season's Contenders", POPULAR, "&primary_release_year={LASTYEAR}&vote_average.gte=7.2&vote_count.gte=250&without_genres=16,99&sort_by=vote_count.desc"),
            picks("Best Picture Winners", false, *movies(1064213, 872585, 545611, 776503, 581734, 496243, 490132, 399055, 376867, 314365, 194662, 76203,
                68734, 74643, 45269, 12162, 12405, 6977, 1422, 1640, 70, 122, 1574, 453, 98)),
            picks("Recent Best Picture Nominees", false, *movies(974576, 549509, 974950, 402431, 661539, 693134, 1000837, 933260, 1028196,
                466420, 666277, 840430, 346698, 915935, 467244, 792307, 1056360, 523607, 674324, 817758, 804095, 361743, 614934, 777245, 497828, 49046)),
            picks("Best Picture Classics", false, *movies(238, 240, 424, 13, 597, 289, 197, 274, 770, 947, 1366, 279, 14, 1934, 581, 33, 409,
                380, 792, 510, 15121, 1725, 665, 703, 11778)),
            picks("International Favorites", false, *movies(426426, 758866, 49046, 467244, 580175, 1417, 194, 146, 637, 11216, 582, 60243,
                209274, 336050, 86837, 1000837, 915935)),
            picks("Award-Winning Series", false, *shows(1396, 1399, 65494, 76331, 136315, 126308, 97546, 1104, 67070, 61662, 2947, 70796, 1421, 4608,
                688, 1398, 69478, 124101, 250307, 95396, 60059, 115004, 87108, 111803, 241259, 154385, 249042)),
            picks("Animated Feature Winners", true, *movies(808, 12, 9806, 9836, 2062, 10681, 14160, 10193, 44896, 62177, 109445, 177572, 150540,
                269149, 354912, 324857, 301528, 508442, 568124, 555604, 823219, 533)),
        ),
    )

    private val WOMENS_HISTORY = SeasonalHub(
        id = "womenshistory", title = "Women's History Month", kicker = "Celebrating Women's Stories",
        tagline = "Trailblazers, Visionaries and the Women Behind the Camera",
        glyph = "Season:Women", hue = 2, priority = 45,
        window = SeasonWindow.fixed(301, 331), genres = setOf("Documentary", "History"),
        pick = row("Tonight's Pick", POPULAR, "&with_keywords=187056&vote_average.gte=7.2&vote_count.gte=1500&sort_by=vote_count.desc$NO_ANIME"),
        kidsPick = row("Tonight's Pick", POPULAR, "&with_keywords=11322&with_genres=10751$KIDS$NO_ANIME&vote_count.gte=2000&sort_by=vote_count.desc"),
        rows = listOf(
            row("Directed by Women", VARIETY, "&with_keywords=187056&vote_count.gte=200$NO_ANIME"),
            picks("Trailblazing True Stories", false, *movies(462, 381284, 506528, 396371, 1360, 1407, 339380, 245168, 71688, 376866, 389015, 369192,
                11287, 525661, 274479, 491283, 40842, 12502, 10130, 33602, 895549, 724495)),
            row("Women's Rights & History", VARIETY, "&with_keywords=212915|2383|275739|340876&vote_count.gte=30"),
            row("Leading Ladies of Action", VARIETY, "&with_keywords=11322&with_genres=28&vote_count.gte=300$NO_ANIME"),
            row("Leading Ladies of Comedy", VARIETY, "&with_keywords=11322&with_genres=35&vote_count.gte=300", "&with_keywords=11322&with_genres=35$GROWN_TV&vote_count.gte=40"),
            row("Series Led by Women", VARIETY, null, "&with_keywords=11322$GROWN_TV&vote_count.gte=100"),
            row("Sports Heroines", VARIETY, "&with_keywords=163097|362111&vote_count.gte=10&primary_release_date.gte=1970-01-01"),
            row("Heroines for Young Viewers", VARIETY, "&with_keywords=11322&with_genres=10751$KIDS$NO_ANIME&vote_count.gte=200", kids = true),
        ),
    )

    private val ST_PATRICKS = SeasonalHub(
        id = "stpatricks", title = "Luck of the Irish", kicker = "St. Patrick's Day",
        tagline = "Irish Films, Series and Stories From the Emerald Isle",
        glyph = "Season:Shamrock", hue = 6, priority = 60,
        window = SeasonWindow.fixed(313, 317),
        countdown = Countdown({ y -> SDay.of(y, 3, 17) }, "Day", "St. Patrick's Day", "Happy St. Patrick's Day"),
        pick = picks("Tonight's Pick", false, *movies(674324, 167073, 369557, 11663, 7984, 5723, 1116, 777270, 67913, 157832, 3109, 10161, 205220)),
        kidsPick = picks("Tonight's Pick", true, *movies(110416, 441130, 26963)),
        rows = listOf(
            row("Irish Films", VARIETY, "&with_origin_country=IE&vote_count.gte=80$NO_ANIME"),
            row("Irish Series", VARIETY, null, "&with_origin_country=IE&vote_count.gte=10&without_genres=10762,10764,10767"),
            row("Set in Ireland", VARIETY, "&with_keywords=14985|4729|306154|262466&vote_count.gte=50"),
            row("Irish Comedies", VARIETY, "&with_origin_country=IE&with_genres=35&vote_count.gte=20", "&with_origin_country=IE&with_genres=35&vote_count.gte=5"),
            row("Irish Crime & Drama", VARIETY, "&with_origin_country=IE&with_genres=80|53&vote_count.gte=20", "&with_origin_country=IE&with_genres=80|18&vote_count.gte=5"),
            picks("Irish-American Crime Sagas", false, *movies(1422, 398978, 261023, 4147, 3131, 23168, 322, 379, 8374)),
            row("Animated Irish Tales", VARIETY, "&with_origin_country=IE&with_genres=16$KIDS&vote_count.gte=20", kids = true),
        ),
    )

    private val EASTER = SeasonalHub(
        id = "easter", title = "Easter & Spring Break", kicker = "Family Fun",
        tagline = "Family Favorites, Springtime Adventures and Easter Classics",
        // Below Women's History Month until Easter's last few days (Easter can fall in late March).
        glyph = "Season:Easter", hue = 7, priority = 44, boostDays = 3, boost = 30,
        window = SeasonWindow.around({ y -> SDay.easter(y) }, 10, 1), genres = setOf("Family", "Kids", "Animation"),
        countdown = Countdown({ y -> SDay.easter(y) }, "Day", "Easter", "Happy Easter", after = "Family Favorites All Week"),
        pick = row("Tonight's Pick", POPULAR, "&with_genres=10751&vote_average.gte=7&vote_count.gte=3000$KIDS$NO_ANIME&sort_by=vote_count.desc"),
        rows = listOf(
            row("Easter & Bunny Favorites", VARIETY, "&with_keywords=9921|9923|350569|15199|181140&with_genres=10751|16$KIDS$NO_ANIME&vote_count.gte=10&primary_release_date.gte=1960-01-01", kids = true),
            familyNight(),
            row("Animated Adventures", VARIETY, "&with_genres=16,12$KIDS$NO_ANIME", kids = true),
            picks("Faith & Inspiration", false, *movies(615, 9837, 6844, 665, 335778, 719256, 470878, 2447, 235260, 339984, 236751, 355547)),
            row("Spring Break Road Trips", VARIETY, "&with_keywords=158054|7312&with_genres=35&without_genres=16,10751&vote_count.gte=100"),
            row("Family Series to Share", VARIETY, null, "&with_genres=10751|10762&vote_average.gte=7.2&vote_count.gte=50$NO_ANIME", kids = true),
        ),
    )

    private val MOTHERS_DAY = SeasonalHub(
        id = "mothersday", title = "For Mom", kicker = "Mother's Day",
        tagline = "Heartfelt Stories, Big Laughs and Movies to Share",
        glyph = "Season:Mother", hue = 2, priority = 60,
        window = SeasonWindow.around({ y -> SDay.mothersDay(y) }, 7, 0), genres = setOf("Family"),
        countdown = Countdown({ y -> SDay.mothersDay(y) }, "Day", "Mother's Day", "Happy Mother's Day"),
        pick = row("Tonight's Pick", POPULAR, "&with_keywords=240315|14768&with_genres=18|35&without_genres=27,53,878&vote_count.gte=800&sort_by=vote_count.desc$NO_ANIME"),
        kidsPick = row("Tonight's Pick", POPULAR, "&with_keywords=456|240315|240237&with_genres=10751$KIDS$NO_ANIME&vote_count.gte=300&sort_by=vote_count.desc"),
        rows = listOf(
            row("Mother-Daughter Stories", VARIETY, "&with_keywords=240315&without_genres=27,53,878&vote_count.gte=100", "&with_keywords=240315$GROWN_TV&vote_count.gte=10"),
            row("Mothers & Sons", VARIETY, "&with_keywords=240237|386961&with_genres=18|35|10751&without_genres=27,878&vote_count.gte=100"),
            row("Moms Who Do It All", VARIETY, "&with_keywords=14768&with_genres=18|35&without_genres=27,878,53&vote_count.gte=100", "&with_keywords=14768$GROWN_TV&vote_count.gte=10"),
            row("Rom-Coms to Share", VARIETY, "&with_genres=10749,35&vote_average.gte=6.5"),
            row("Feel-Good Favorites", VARIETY, FEEL_GOOD, FEEL_GOOD_TV),
            row("Family Comedies", VARIETY, "&with_genres=10751,35$KIDS$NO_ANIME", kids = true),
            row("Animated Moms & Families", VARIETY, "&with_keywords=456|240315|240237&with_genres=16$KIDS$NO_ANIME&vote_count.gte=100", kids = true),
        ),
    )

    private val FATHERS_DAY = SeasonalHub(
        id = "fathersday", title = "For Dad", kicker = "Father's Day",
        tagline = "Action Heroes, Sports Legends and Father-Kid Stories",
        glyph = "Season:Father", hue = 0, priority = 60,
        window = SeasonWindow.around({ y -> SDay.fathersDay(y) }, 7, 0), genres = setOf("Family"),
        countdown = Countdown({ y -> SDay.fathersDay(y) }, "Day", "Father's Day", "Happy Father's Day"),
        pick = row("Tonight's Pick", POPULAR, "&with_keywords=240119|240303|5905&vote_average.gte=7.2&vote_count.gte=1500&sort_by=vote_count.desc$NO_ANIME"),
        kidsPick = row("Tonight's Pick", POPULAR, "&with_keywords=5905|240119|240303|33479&with_genres=16$KIDS$NO_ANIME&vote_count.gte=300&sort_by=vote_count.desc"),
        rows = listOf(
            row("Father-Son Stories", VARIETY, "&with_keywords=240119&vote_count.gte=150", "&with_keywords=240119$GROWN_TV&vote_count.gte=20"),
            row("Father-Daughter Stories", VARIETY, "&with_keywords=240303&vote_count.gte=150"),
            row("Action Dads", VARIETY, "&with_keywords=181068|5905|240303|240119&with_genres=28&vote_count.gte=300$NO_ANIME"),
            row("Sports Legends", VARIETY, "&with_keywords=6075&with_genres=18&vote_count.gte=200"),
            row("Dad-Approved Classics", TOP_VOTED, "&with_genres=28|10752|37|80&primary_release_date.gte=1970-01-01&primary_release_date.lte=1999-12-31"),
            row("Dad Comedies", VARIETY, "&with_keywords=5905|33479|240119|240303&with_genres=35&vote_count.gte=150"),
            row("Animated Dads & Kids", VARIETY, "&with_keywords=5905|240119|240303|33479&with_genres=16$KIDS$NO_ANIME&vote_count.gte=100", kids = true),
        ),
    )

    private val PRIDE = SeasonalHub(
        id = "pride", title = "Pride", kicker = "Pride Month",
        tagline = "Celebrating LGBTQ+ Love, Joy and Trailblazing Stories",
        glyph = "LGBTQ+", hue = 0, priority = 40,
        window = SeasonWindow.fixed(601, 630),
        pick = row("Tonight's Pick", POPULAR, "$LGBTQ&vote_average.gte=7.3&vote_count.gte=1000&sort_by=vote_count.desc$NO_ANIME"),
        rows = listOf(
            row("Acclaimed LGBTQ+ Films", ACCLAIMED, "$LGBTQ$NO_ANIME"),
            row("LGBTQ+ Love Stories", VARIETY, "$LGBTQ&with_genres=10749&vote_count.gte=100", "&with_keywords=158718,9840&vote_count.gte=20"),
            row("LGBTQ+ Comedies", VARIETY, "$LGBTQ&with_genres=35&vote_count.gte=100", "$LGBTQ&with_genres=35&vote_count.gte=20"),
            row("LGBTQ+ Series", VARIETY, null, "$LGBTQ&with_genres=18|35&without_genres=10762,10751,10765&vote_count.gte=50"),
            row("Coming Out & Coming of Age", VARIETY, "&with_keywords=1862|382157&vote_count.gte=100", "&with_keywords=1862|382157$GROWN_TV&vote_count.gte=5"),
            row("True Stories & Trailblazers", VARIETY, "$LGBTQ&with_genres=99|36&vote_count.gte=20", "$LGBTQ&with_genres=99&vote_count.gte=3"),
            row("New LGBTQ+ Releases", NEW, LGBTQ, LGBTQ),
        ),
    )

    private val JULY_FOURTH = SeasonalHub(
        id = "july4", title = "Fourth of July", kicker = "Independence Day",
        tagline = "Fireworks-Worthy Blockbusters and Stories of America",
        glyph = "Season:Fireworks", hue = 0, priority = 70,
        window = SeasonWindow.fixed(628, 704), genres = setOf("History", "War", "Action"),
        countdown = Countdown({ y -> SDay.of(y, 7, 4) }, "Day", "the Fourth", "Happy Fourth of July"),
        pick = row("Tonight's Pick", POPULAR, "&with_genres=28|12&with_origin_country=US&vote_average.gte=7.2&vote_count.gte=8000&sort_by=vote_count.desc"),
        rows = listOf(
            row("Fireworks-Worthy Blockbusters", POPULAR, "&with_genres=28|12|878&vote_average.gte=6.8&vote_count.gte=3000&primary_release_date.gte={YEARSAGO:20}&sort_by=vote_count.desc$NO_ANIME"),
            row("American History on Screen", VARIETY, "&with_keywords=190024|197159&with_genres=36|18|10752&vote_count.gte=20&primary_release_date.gte=1950-01-01", "&with_keywords=190024|197159$GROWN_TV&vote_count.gte=5"),
            row("Presidents & Politics", VARIETY, "&with_keywords=366601|365774|833&vote_count.gte=100", "&with_keywords=366601|365774|833$GROWN_TV&vote_count.gte=10"),
            row("To the Moon & Beyond", VARIETY, "&with_keywords=1432|156836&vote_count.gte=50"),
            row("Heroes in Uniform", VARIETY, "&with_genres=10752&with_origin_country=US&vote_count.gte=300"),
            row("All-American Classics", TOP_VOTED, "&with_origin_country=US&primary_release_date.gte=1975-01-01&primary_release_date.lte=1999-12-31&vote_average.gte=7.3"),
            familyNight(),
        ),
    )

    private val BACK_TO_SCHOOL = SeasonalHub(
        id = "backtoschool", title = "Back to School", kicker = "School's In",
        tagline = "High School Comedies, Campus Classics and Coming-of-Age Favorites",
        glyph = "Season:School", hue = 5, priority = 50,
        window = SeasonWindow.fixed(815, 905), genres = setOf("Comedy", "Teen"),
        pick = row("Tonight's Pick", POPULAR, "&with_keywords=6270|10683&without_genres=27&vote_average.gte=7&vote_count.gte=1500&sort_by=vote_count.desc$NO_ANIME"),
        rows = listOf(
            row("High School Comedies", VARIETY, "&with_keywords=6270&with_genres=35&vote_count.gte=150", "&with_keywords=6270&with_genres=35$GROWN_TV&vote_count.gte=20"),
            row("Coming-of-Age Favorites", VARIETY, "&with_keywords=10683&vote_count.gte=300$NO_ANIME"),
            row("Campus Life", VARIETY, "&with_keywords=3616|13160|221023&with_genres=35|18&without_genres=27,16&vote_count.gte=100", "&with_keywords=3616|13160$GROWN_TV&vote_count.gte=10"),
            picks("Inspiring Teachers", false, *movies(207, 29154, 1646, 7214, 14621, 11820, 9909, 1584, 2054, 840430, 244786, 25934)),
            row("Teen Dramas", VARIETY, null, "&with_keywords=193400|6270&with_genres=18$GROWN_TV&vote_count.gte=40"),
            row("Boarding School Stories", VARIETY, "&with_keywords=6271&without_genres=27&vote_count.gte=50$NO_ANIME"),
            row("School Days for Kids", VARIETY, "&with_keywords=10873|6270&with_genres=10751|16$KIDS$NO_ANIME&vote_count.gte=50",
                "&with_keywords=10873&with_genres=10762|10751$NO_ANIME&vote_count.gte=5", kids = true),
        ),
    )

    private val HISPANIC_HERITAGE = SeasonalHub(
        id = "hispanicheritage", title = "Hispanic Heritage Month", kicker = "Celebrating Latino Stories",
        tagline = "Acclaimed Cinema, Bold Voices and Stories From Across the Hispanic World",
        glyph = "Season:Voices", hue = 4, priority = 40,
        window = SeasonWindow.fixed(915, 1015), genres = setOf("Drama", "Documentary"),
        pick = row("Tonight's Pick", POPULAR, "&with_original_language=es&vote_average.gte=7.4&vote_count.gte=1200&sort_by=vote_count.desc$NO_ANIME"),
        kidsPick = picks("Tonight's Pick", true, *movies(354912, 568124, 228326, 449406, 364689, 46195)),
        rows = listOf(
            row("Acclaimed Spanish-Language Films", ACCLAIMED, "&with_original_language=es"),
            row("Spanish-Language Series", VARIETY, null, "&with_original_language=es&vote_count.gte=50&without_genres=10762,10764,10767,10766,16"),
            picks("Latino Stories", false, *movies(16052, 16620, 29154, 30309, 38722, 211954, 467909, 511809, 565770, 626332, 228203, 1002185, 264337, 15104),
                *shows(69017, 61418, 98308, 4626, 3308)),
            row("Mexican Cinema", VARIETY, "&with_origin_country=MX&vote_count.gte=100$NO_ANIME"),
            row("Latin American Cinema", VARIETY, "&with_original_language=es|pt&without_origin_country=ES&vote_count.gte=150$NO_ANIME"),
            row("Spanish-Language Thrillers", VARIETY, "&with_original_language=es&with_genres=53|80&vote_count.gte=200", "&with_original_language=es&with_genres=80|9648&vote_count.gte=40"),
            picks("Modern Classics", false, *movies(1391, 55, 426426, 1417, 1360, 11779, 211954)),
            picks("Family Favorites", true, *movies(354912, 568124, 228326, 449406, 364689, 46195, 10054)),
        ),
    )

    private val HALLOWEEN = SeasonalHub(
        id = "halloween", title = "31 Nights of Horror", kicker = "Halloween",
        tagline = "A Fright for Every Night of October",
        glyph = "Halloween", hue = 1, priority = 50, nights = true,
        window = SeasonWindow.fixed(1001, 1031), genres = setOf("Horror", "Halloween", "Thriller"),
        countdown = Countdown({ y -> SDay.of(y, 10, 31) }, "Night", "Halloween", "Happy Halloween", eve = "Halloween Is Tomorrow Night"),
        pick = row("Tonight's Pick", POPULAR, "&with_genres=27&vote_average.gte=7&vote_count.gte=2500&sort_by=vote_count.desc$NO_ANIME"),
        kidsPick = row("Tonight's Pick", POPULAR, "&with_genres=10751&with_keywords=3335|318653|162846$KIDS$NO_ANIME&vote_count.gte=300&sort_by=vote_count.desc"),
        rows = listOf(
            row("Trending Horror", TRENDING, "&with_genres=27", "$HORROR_TV$GROWN_TV", genre = 27),
            row("Halloween Movies", VARIETY, "&with_keywords=3335|318653|9694|232795&vote_count.gte=100&primary_release_date.gte=1970-01-01"),
            row("Slasher Classics", TOP_VOTED, "&with_keywords=12339&with_genres=27&primary_release_date.lte={YEARSAGO:15}", "&with_keywords=12339$GROWN_TV&vote_count.gte=40"),
            row("Supernatural & Hauntings", VARIETY, "&with_genres=27&with_keywords=256183|3358|162846|9712", "&with_keywords=3358|162846|256183$GROWN_TV&vote_count.gte=40"),
            row("Creature Features", VARIETY, "&with_genres=27&with_keywords=158126|210614|11100|13031|1299|12564|3133"),
            row("Horror Comedy", VARIETY, "&with_genres=27,35", "&with_genres=35&with_keywords=315058|256183|3133|162846|1299$GROWN_TV&vote_count.gte=30"),
            row("Horror Series", VARIETY, null, "$HORROR_TV$GROWN_TV"),
            row("Critically Acclaimed Horror", ACCLAIMED, "&with_genres=27", "$HORROR_TV$GROWN_TV"),
            row("Family-Friendly Frights", VARIETY, "&with_genres=10751&with_keywords=3335|318653|616|162846|3133|1299|12564$KIDS$NO_ANIME&vote_count.gte=40",
                "&with_genres=10751|10762&with_keywords=3335|318653|616|162846$NO_ANIME&vote_count.gte=5", kids = true),
            row("Animated Halloween Favorites", VARIETY, "&with_genres=16&with_keywords=3335|318653|616|162846|1299|3133$KIDS$NO_ANIME&vote_count.gte=30",
                "&with_genres=16&with_keywords=3335|318653$NO_ANIME&without_genres=10768&vote_count.gte=3", kids = true),
        ),
    )

    private val THANKSGIVING = SeasonalHub(
        id = "thanksgiving", title = "Thanksgiving Together", kicker = "Thanksgiving",
        tagline = "Family Gatherings, Feel-Good Favorites and Football Sunday",
        glyph = "Season:Turkey", hue = 4, priority = 60,
        window = SeasonWindow.around({ y -> SDay.thanksgiving(y) }, 7, 3), genres = setOf("Family"),
        countdown = Countdown({ y -> SDay.thanksgiving(y) }, "Day", "Thanksgiving", "Happy Thanksgiving", after = "Thanksgiving Weekend Favorites"),
        pick = picks("Tonight's Pick", false, *movies(2609, 9089, 2758, 9475, 5143, 22881, 773, 546554, 9043, 1550, 68924)),
        rows = listOf(
            row("Thanksgiving Movies", VARIETY, "&with_keywords=4543|307493|374738&without_genres=27,53&vote_count.gte=20&primary_release_date.gte=1940-01-01"),
            row("Family Gatherings", VARIETY, "&with_keywords=18187|33694&with_genres=35|18|10751&without_genres=27,53,28,80&vote_count.gte=50"),
            row("Food & Feasts", VARIETY, "&with_keywords=10637|1918&with_genres=35|18|10749&vote_count.gte=100", "&with_keywords=1918|10637&with_genres=10764|99&vote_count.gte=10"),
            row("Football Sunday", VARIETY, "&with_keywords=579|11671|5621&vote_count.gte=50", "&with_keywords=579|11671&vote_count.gte=10"),
            row("Feel-Good Favorites", VARIETY, FEEL_GOOD, FEEL_GOOD_TV),
            row("Holiday Season Kickoff", NEW, CHRISTMAS),
            familyNight(),
            row("Animated Holiday Favorites", VARIETY, "&with_keywords=4543|207317&with_genres=16$KIDS$NO_ANIME&vote_count.gte=30", kids = true),
        ),
    )

    private val CHRISTMAS_HUB = SeasonalHub(
        id = "christmas", title = "Home for the Holidays", kicker = "Christmas",
        tagline = "Holiday Classics, Cozy Comedies and Family Favorites",
        glyph = "Season:Christmas", hue = 6, priority = 70,
        window = SeasonWindow.fixed(1201, 1226), genres = setOf("Holiday", "Family", "Kids", "Comedy", "Romance"),
        countdown = Countdown({ y -> SDay.of(y, 12, 25) }, "Day", "Christmas", "Merry Christmas", eve = "Tonight Is Christmas Eve", after = "Happy Holidays"),
        pick = row("Tonight's Pick", POPULAR, "$CHRISTMAS&vote_count.gte=800&vote_average.gte=6.6&sort_by=vote_count.desc$NO_ANIME"),
        kidsPick = row("Tonight's Pick", POPULAR, "$CHRISTMAS&with_genres=10751$KIDS$NO_ANIME&vote_count.gte=300&sort_by=vote_count.desc"),
        rows = listOf(
            row("Holiday Classics", TOP_VOTED, "$CHRISTMAS&vote_average.gte=6.5$NO_ANIME"),
            row("Family Christmas Movies", VARIETY, "$CHRISTMAS&with_genres=10751$KIDS$NO_ANIME&primary_release_date.gte=1960-01-01&vote_count.gte=50", kids = true),
            row("Holiday Rom-Coms", VARIETY, "$CHRISTMAS&with_genres=10749&vote_count.gte=30"),
            row("Christmas Comedies", VARIETY, "$CHRISTMAS&with_genres=35&vote_count.gte=100&primary_release_date.gte=1980-01-01"),
            row("Animated Holiday Specials", VARIETY, "$CHRISTMAS&with_genres=16$KIDS$NO_ANIME&vote_count.gte=20&primary_release_date.gte=1960-01-01", kids = true),
            row("Holiday Action & Thrills", VARIETY, "$CHRISTMAS&with_genres=28|53|80&without_genres=27,16,10751&vote_count.gte=100&primary_release_date.gte=1980-01-01"),
            row("Holiday Series & Specials", VARIETY, null, "$CHRISTMAS&without_genres=16,10762&vote_count.gte=5"),
            row("New Holiday Movies", NEW, CHRISTMAS),
            row("Santa, Elves & Snowmen", VARIETY, "&with_keywords=1991|10115|5570&with_genres=10751|16$KIDS$NO_ANIME&vote_count.gte=30", kids = true),
        ),
    )

    /** Every hub (the base seasons first: [SeasonCalendar] falls back to them). */
    val ALL: List<SeasonalHub> = listOf(
        WINTER, SPRING, SUMMER, FALL,
        NEW_YEAR, VALENTINES, BLACK_HISTORY, AWARDS, WOMENS_HISTORY, ST_PATRICKS, EASTER, MOTHERS_DAY, FATHERS_DAY,
        PRIDE, JULY_FOURTH, BACK_TO_SCHOOL, HISPANIC_HERITAGE, HALLOWEEN, THANKSGIVING, CHRISTMAS_HUB,
    )
}
