import Foundation

/// Every seasonal and event hub, the whole year round (plain data: `SeasonCalendar` picks the one that's on).
/// The four base seasons cover every day; holidays, heritage months and events take over by `SeasonalHub.priority`:
///
///  - base seasons 10 · heritage and awareness months 40-55 · Awards Season 50 (85 in its last days)
///  - Valentine's Day 50 (90 in its last week) · Easter 44 (74 in its last days)
///  - Mother's and Father's Day, Thanksgiving, St. Patrick's 60
///  - Halloween 50 · Fourth of July, Christmas 70 · New Year's 80
///
/// Rows are TMDB Discover filters (keyword ids from /search/keyword, each checked to fill a row with
/// well-known titles) or hand-picked titles. Heritage months and Pride are built from TMDB's own topic
/// keywords and acclaimed titles, never from clichés. Anime never shows here (every row is tidied without it).
/// Ported 1:1 from the Fire TV app's SeasonCatalog.kt.
enum SeasonCatalog {
    /// Movies a kids profile may see: US rating G or PG.
    private static let kidsCert = "&certification_country=US&certification.lte=PG"
    private static let noAnime = "&without_keywords=210024"
    /// A TV row that stands on keywords alone: no cartoons, kids' or family shows (they'd crowd in on a keyword).
    private static let grownTV = "&without_genres=16,10751,10762"
    /// TMDB has no Horror genre for shows: horror, supernatural horror, slasher, J- and K-horror.
    private static let horrorTV = "&with_keywords=315058|256183|12339|325319|313454"
    private static let feelGood = "&with_genres=35|10751|10749&without_genres=27,53,80,10752,99,9648,28&vote_average.gte=7\(noAnime)"
    private static let feelGoodTV = "&with_genres=35&without_genres=80,9648,10768,99,10762,16,10764,10767&vote_average.gte=7.5"
    private static let christmas = "&with_keywords=207317"
    private static let lgbtq = "&with_keywords=158718"

    private static func row(
        _ title: String, _ kind: SeasonRow.Kind, _ movie: String?, _ tv: String? = nil,
        kids: Bool = false, genre: Int = 0
    ) -> SeasonRow {
        SeasonRow(title, kind, movie, tv, kids: kids, genre: genre)
    }

    private static func picks(_ title: String, _ kids: Bool, _ ids: [String]) -> SeasonRow {
        SeasonRow(title, .picks, nil, nil, kids: kids, ids: ids)
    }

    private static func movies(_ ids: Int...) -> [String] { ids.map { "movie:\($0)" } }

    private static func shows(_ ids: Int...) -> [String] { ids.map { "tv:\($0)" } }

    /// A family movie night (kids-safe): well-loved family films.
    private static func familyNight(_ title: String = "Family Movie Night") -> SeasonRow {
        row(title, .variety, "&with_genres=10751&vote_average.gte=6.8\(kidsCert)\(noAnime)", "&with_genres=10751|10762&vote_average.gte=7.5&without_genres=16", kids: true)
    }

    // MARK: - The four base seasons (one is always on)

    private static let winter = SeasonalHub(
        id: "winter", title: "Cozy Winter Nights", kicker: "Winter",
        tagline: "Snowbound Thrillers, Fireside Mysteries and Feel-Good Favorites",
        glyph: "Season:Snowflake", hue: 7, priority: 10, base: true,
        window: .fixed(1201, 229),
        pick: row("Tonight's Pick", .popular, "&with_keywords=10794|1442|156783|3623&vote_average.gte=6.8&vote_count.gte=800&sort_by=vote_count.desc"),
        rows: [
            row("Snowbound Thrillers", .variety, "&with_keywords=10794|1442|156783&with_genres=53|27|9648&vote_count.gte=100"),
            row("Fireside Mysteries", .variety, "&with_keywords=12570|207046|703&with_genres=9648&without_genres=27&vote_count.gte=150", "&with_keywords=12570|207046|703\(grownTV)&vote_count.gte=40"),
            row("Cozy Feel-Good Favorites", .variety, feelGood, feelGoodTV),
            row("Epic Fantasy Marathons", .topVoted, "&with_genres=14&vote_count.gte=3000&vote_average.gte=7\(noAnime)"),
            row("Bingeable Dramas", .acclaimed, nil, "&with_genres=18"),
            row("Winter Wonderlands", .variety, "&with_keywords=10794|1442|10115|263324&with_genres=10751|16\(kidsCert)\(noAnime)&vote_count.gte=50", kids: true),
            familyNight(),
        ]
    )

    private static let spring = SeasonalHub(
        id: "spring", title: "Spring Into Something New", kicker: "Spring",
        tagline: "Fresh Releases, New Series and Feel-Good Favorites",
        glyph: "Season:Blossom", hue: 2, priority: 10, base: true,
        window: .fixed(301, 531),
        pick: row("Tonight's Pick", .popular, "&vote_average.gte=7&vote_count.gte=400&primary_release_date.gte={YEARSAGO:2}&sort_by=vote_count.desc\(noAnime)"),
        rows: [
            row("New Releases", .new, "", ""),
            row("New Series to Start", .new, nil, "&without_genres=10763,10764,10766,10767,10762"),
            row("Fresh Rom-Coms", .variety, "&with_genres=10749,35&primary_release_date.gte={YEARSAGO:12}&vote_count.gte=150", "&with_genres=35&with_keywords=9840&without_genres=16&vote_count.gte=60"),
            row("Coming-of-Age Stories", .variety, "&with_keywords=10683&vote_count.gte=300", "&with_keywords=10683\(grownTV)&vote_count.gte=40"),
            row("Nature & Wildlife", .variety, "&with_genres=99&with_keywords=18330|9902|221355&vote_count.gte=20", "&with_genres=99&with_keywords=18330|9902|221355&vote_count.gte=5", kids: true),
            row("Feel-Good Favorites", .variety, feelGood, feelGoodTV),
            familyNight(),
        ]
    )

    private static let summer = SeasonalHub(
        id: "summer", title: "Summer Blockbusters", kicker: "Summer",
        tagline: "Popcorn Thrills, Road Trips and Endless Summer Fun",
        glyph: "Season:Sun", hue: 5, priority: 10, base: true,
        window: .fixed(601, 831),
        pick: row("Tonight's Pick", .popular, "&with_genres=28|12|878&vote_average.gte=7.2&vote_count.gte=8000&sort_by=vote_count.desc\(noAnime)"),
        rows: [
            row("Biggest Blockbusters", .topVoted, "&with_genres=28|12|878&primary_release_date.gte={YEARSAGO:12}&vote_average.gte=6.5\(noAnime)"),
            row("Summer Vacation", .variety, "&with_keywords=14714|13088&without_genres=27,53,10749&vote_count.gte=150\(noAnime)"),
            row("Summer Camp", .variety, "&with_keywords=5767&vote_count.gte=40"),
            row("Road Trips", .variety, "&with_keywords=7312&vote_count.gte=150"),
            row("Deep Water Thrills", .variety, "&with_keywords=15097|158108&with_genres=53|27|28|12&without_genres=16&vote_count.gte=100&primary_release_date.gte=1970-01-01"),
            row("Superhero Showdowns", .variety, "&with_keywords=9715\(noAnime)", "&with_keywords=9715&without_genres=10762\(noAnime)"),
            row("Summer Series to Binge", .popular, nil, "&vote_average.gte=7.5&vote_count.gte=300&first_air_date.gte={YEARSAGO:4}&without_genres=16,10762,10764,10767&sort_by=vote_count.desc"),
            row("Animated Summer Hits", .variety, "&with_genres=16,10751\(kidsCert)\(noAnime)", kids: true),
        ]
    )

    private static let fall = SeasonalHub(
        id: "fall", title: "Fall Favorites", kicker: "Fall",
        tagline: "Cozy Mysteries, Prestige Dramas and New Season Premieres",
        glyph: "Season:Leaf", hue: 4, priority: 10, base: true,
        window: .fixed(901, 1130),
        pick: row("Tonight's Pick", .popular, "&with_genres=18|9648&vote_average.gte=7.6&vote_count.gte=4000&sort_by=vote_count.desc\(noAnime)"),
        rows: [
            row("Cozy Mysteries", .variety, "&with_keywords=12570|207046|703&with_genres=9648&without_genres=27&vote_count.gte=150", "&with_keywords=12570|207046|703\(grownTV)&vote_count.gte=40"),
            row("New Season Premieres", .new, nil, "&without_genres=10763,10764,10766,10767,10762"),
            row("Prestige Dramas", .acclaimed, "&with_genres=18", "&with_genres=18"),
            row("Small-Town Secrets", .variety, "&with_keywords=1415&with_genres=9648&without_genres=27&vote_count.gte=100", "&with_keywords=1415&with_genres=9648|80&vote_count.gte=30"),
            row("Football Season", .variety, "&with_keywords=579|11671|5621&vote_count.gte=50", "&with_keywords=579|11671&vote_count.gte=10"),
            row("Autumn Feel-Good Favorites", .variety, feelGood, feelGoodTV),
            familyNight(),
        ]
    )

    // MARK: - Holidays, heritage months and events

    private static let newYear = SeasonalHub(
        id: "newyear", title: "Ring In the New Year", kicker: "New Year's",
        tagline: "The Year's Best, Fresh Starts and Countdown Classics",
        glyph: "Season:NewYear", hue: 5, priority: 80,
        window: .fixed(1227, 102),
        countdown: Countdown({ y in SDay.of(y, 1, 1) }, "Day", "the New Year", "Happy New Year", eve: "Tonight Is New Year's Eve"),
        pick: row("Tonight's Pick", .popular, "&primary_release_year={LASTYEAR}&vote_average.gte=6.8&vote_count.gte=300&sort_by=vote_count.desc\(noAnime)"),
        kidsPick: row("Tonight's Pick", .popular, "&primary_release_year={LASTYEAR}&with_genres=10751\(kidsCert)\(noAnime)&sort_by=vote_count.desc"),
        rows: [
            row("The Year's Biggest Movies", .popular, "&primary_release_year={LASTYEAR}&vote_count.gte=100&sort_by=vote_count.desc\(noAnime)"),
            row("The Year's Best New Series", .popular, nil, "&first_air_date.gte={LASTYEAR}-01-01&first_air_date.lte={LASTYEAR}-12-31&vote_count.gte=40&without_genres=10762,10763,10764,10766,10767&sort_by=vote_count.desc"),
            row("Critically Acclaimed in {LASTYEAR}", .popular, "&primary_release_year={LASTYEAR}&vote_average.gte=7.2&vote_count.gte=300&sort_by=vote_average.desc\(noAnime)"),
            row("New Year's Eve Movies", .variety, "&with_keywords=613|252123|5632&vote_count.gte=20&primary_release_date.gte=1960-01-01"),
            row("Second Chances & Time Loops", .variety, "&with_keywords=10854|366519&vote_count.gte=100", "&with_keywords=10854\(grownTV)&vote_count.gte=20"),
            row("Feel-Good Fresh Starts", .variety, feelGood, feelGoodTV),
            row("Party Movies", .variety, "&with_keywords=286407|9694|11798|191260|221475&with_genres=35&vote_count.gte=50"),
            familyNight("Family Celebration Favorites"),
        ]
    )

    private static let valentines = SeasonalHub(
        id: "valentines", title: "Date Night", kicker: "Valentine's Day",
        tagline: "Rom-Coms, Love Stories and Crowd-Pleasers for Two",
        glyph: "Season:Valentine", hue: 2, priority: 50, boostDays: 6, boost: 40,
        window: .fixed(201, 214), genres: ["Romance"],
        countdown: Countdown({ y in SDay.of(y, 2, 14) }, "Day", "Valentine's Day", "Happy Valentine's Day"),
        pick: row("Tonight's Pick", .popular, "&with_genres=10749&vote_average.gte=7.2&vote_count.gte=2000&sort_by=vote_count.desc\(noAnime)"),
        rows: [
            row("Romantic Comedies", .variety, "&with_genres=10749,35", "&with_genres=35&with_keywords=9840&without_genres=16&vote_count.gte=60"),
            row("Swoon-Worthy Love Stories", .acclaimed, "&with_genres=10749,18"),
            row("Date Night Crowd-Pleasers", .popular, "&with_genres=35|28|12&vote_average.gte=7&vote_count.gte=3000&primary_release_date.gte=2000-01-01&sort_by=vote_count.desc\(noAnime)"),
            row("Romance Series", .variety, nil, "&with_keywords=9840|386551&without_genres=16,10762"),
            row("Valentine's Day Movies", .variety, "&with_keywords=160404|376604&vote_count.gte=10"),
            row("Tearjerkers", .variety, "&with_keywords=156924|346048&vote_count.gte=100"),
            row("Galentine's Night", .variety, "&with_keywords=5248&with_genres=35&vote_count.gte=100"),
            row("Love Stories Through the Decades", .topVoted, "&with_genres=10749&primary_release_date.gte=1985-01-01&primary_release_date.lte=2009-12-31"),
            row("Animated Love Stories", .variety, "&with_genres=16,10749\(kidsCert)\(noAnime)&vote_count.gte=100", kids: true),
        ]
    )

    private static let blackHistory = SeasonalHub(
        id: "blackhistory", title: "Black History Month", kicker: "Celebrating Black Stories",
        tagline: "Landmark Films, Bold Voices and Stories That Shaped History",
        glyph: "Season:Stories", hue: 3, priority: 55,
        window: .fixed(201, 229), genres: ["Documentary", "History", "Drama"],
        pick: picks("Tonight's Pick", false, movies(376867, 76203, 273895, 1883, 925, 381284, 419430, 284054, 465914, 393457, 873, 583406, 615667, 9665, 650, 157354, 522212, 1677, 1233413)),
        kidsPick: picks("Tonight's Pick", true, movies(508442, 10198, 381284, 13751, 324857, 10637)),
        rows: [
            picks("Essential Black Cinema", false, movies(376867, 76203, 273895, 1883, 925, 381284, 419430, 284054, 465914, 393457, 873,
                583406, 615667, 9665, 650, 157354, 522212, 1677, 506528, 424781, 854239, 1056360, 1233413, 470044, 724495)),
            row("Civil Rights Stories", .variety, "&with_keywords=270891|4098|233016|6984|381945&with_genres=18|36|99&without_genres=878,35,27&vote_count.gte=50",
                "&with_keywords=270891|4098|233016\(grownTV)&vote_count.gte=5"),
            row("Black Biopics & True Stories", .variety, "&with_keywords=256015|251436|190675&with_genres=36|18&vote_count.gte=150"),
            picks("Landmark Series", false, shows(81355, 82816, 79788, 71694, 54650, 61733, 67679, 79086, 66606, 13555, 72071, 64150, 107365, 70767, 115)),
            picks("Comedy Favorites", false, movies(9602, 10634, 10611, 417870, 9737, 16162) + shows(65495, 67883, 61381, 125935, 1892, 252, 171, 4270)),
            row("Powerful Documentaries", .variety, "&with_keywords=256015|251436|190675|270891|12425&with_genres=99&vote_count.gte=20",
                "&with_keywords=256015|251436|190675|270891&with_genres=99&vote_count.gte=3"),
            picks("Music Legends", false, movies(1677, 615667, 592863, 1125, 15765, 277216, 239566, 696157)),
            picks("Family Favorites", true, movies(508442, 10198, 381284, 13751, 324857, 10637, 196867, 589739)),
        ]
    )

    private static let awards = SeasonalHub(
        id: "awards", title: "And the Award Goes To", kicker: "Awards Season",
        tagline: "Best Picture Winners, This Year's Contenders and Modern Masterpieces",
        glyph: "Season:Award", hue: 5, priority: 50, boostDays: 3, boost: 35,
        window: .until(215) { y in SDay.awardsNight(y) },
        countdown: Countdown({ y in SDay.awardsNight(y) }, "Day", "Awards Night", "Awards Night Is Here"),
        pick: picks("Tonight's Pick", false, movies(98, 453, 122, 70, 1422, 6977, 12405, 12162, 45269, 74643, 68734, 76203, 194662, 314365,
            376867, 399055, 490132, 496243, 581734, 776503, 545611, 872585, 1064213)),
        kidsPick: picks("Tonight's Pick", true, movies(12, 9806, 2062, 10681, 14160, 10193, 150540, 269149, 354912, 568124)),
        rows: [
            row("This Season's Contenders", .popular, "&primary_release_year={LASTYEAR}&vote_average.gte=7.2&vote_count.gte=250&without_genres=16,99&sort_by=vote_count.desc"),
            picks("Best Picture Winners", false, movies(1064213, 872585, 545611, 776503, 581734, 496243, 490132, 399055, 376867, 314365, 194662, 76203,
                68734, 74643, 45269, 12162, 12405, 6977, 1422, 1640, 70, 122, 1574, 453, 98)),
            picks("Recent Best Picture Nominees", false, movies(974576, 549509, 974950, 402431, 661539, 693134, 1000837, 933260, 1028196,
                466420, 666277, 840430, 346698, 915935, 467244, 792307, 1056360, 523607, 674324, 817758, 804095, 361743, 614934, 777245, 497828, 49046)),
            picks("Best Picture Classics", false, movies(238, 240, 424, 13, 597, 289, 197, 274, 770, 947, 1366, 279, 14, 1934, 581, 33, 409,
                380, 792, 510, 15121, 1725, 665, 703, 11778)),
            picks("International Favorites", false, movies(426426, 758866, 49046, 467244, 580175, 1417, 194, 146, 637, 11216, 582, 60243,
                209274, 336050, 86837, 1000837, 915935)),
            picks("Award-Winning Series", false, shows(1396, 1399, 65494, 76331, 136315, 126308, 97546, 1104, 67070, 61662, 2947, 70796, 1421, 4608,
                688, 1398, 69478, 124101, 250307, 95396, 60059, 115004, 87108, 111803, 241259, 154385, 249042)),
            picks("Animated Feature Winners", true, movies(808, 12, 9806, 9836, 2062, 10681, 14160, 10193, 44896, 62177, 109445, 177572, 150540,
                269149, 354912, 324857, 301528, 508442, 568124, 555604, 823219, 533)),
        ]
    )

    private static let womensHistory = SeasonalHub(
        id: "womenshistory", title: "Women's History Month", kicker: "Celebrating Women's Stories",
        tagline: "Trailblazers, Visionaries and the Women Behind the Camera",
        glyph: "Season:Women", hue: 2, priority: 45,
        window: .fixed(301, 331), genres: ["Documentary", "History"],
        pick: row("Tonight's Pick", .popular, "&with_keywords=187056&vote_average.gte=7.2&vote_count.gte=1500&sort_by=vote_count.desc\(noAnime)"),
        kidsPick: row("Tonight's Pick", .popular, "&with_keywords=11322&with_genres=10751\(kidsCert)\(noAnime)&vote_count.gte=2000&sort_by=vote_count.desc"),
        rows: [
            row("Directed by Women", .variety, "&with_keywords=187056&vote_count.gte=200\(noAnime)"),
            picks("Trailblazing True Stories", false, movies(462, 381284, 506528, 396371, 1360, 1407, 339380, 245168, 71688, 376866, 389015, 369192,
                11287, 525661, 274479, 491283, 40842, 12502, 10130, 33602, 895549, 724495)),
            row("Women's Rights & History", .variety, "&with_keywords=212915|2383|275739|340876&vote_count.gte=30"),
            row("Leading Ladies of Action", .variety, "&with_keywords=11322&with_genres=28&vote_count.gte=300\(noAnime)"),
            row("Leading Ladies of Comedy", .variety, "&with_keywords=11322&with_genres=35&vote_count.gte=300", "&with_keywords=11322&with_genres=35\(grownTV)&vote_count.gte=40"),
            row("Series Led by Women", .variety, nil, "&with_keywords=11322\(grownTV)&vote_count.gte=100"),
            row("Sports Heroines", .variety, "&with_keywords=163097|362111&vote_count.gte=10&primary_release_date.gte=1970-01-01"),
            row("Heroines for Young Viewers", .variety, "&with_keywords=11322&with_genres=10751\(kidsCert)\(noAnime)&vote_count.gte=200", kids: true),
        ]
    )

    private static let stPatricks = SeasonalHub(
        id: "stpatricks", title: "Luck of the Irish", kicker: "St. Patrick's Day",
        tagline: "Irish Films, Series and Stories From the Emerald Isle",
        glyph: "Season:Shamrock", hue: 6, priority: 60,
        window: .fixed(313, 317),
        countdown: Countdown({ y in SDay.of(y, 3, 17) }, "Day", "St. Patrick's Day", "Happy St. Patrick's Day"),
        pick: picks("Tonight's Pick", false, movies(674324, 167073, 369557, 11663, 7984, 5723, 1116, 777270, 67913, 157832, 3109, 10161, 205220)),
        kidsPick: picks("Tonight's Pick", true, movies(110416, 441130, 26963)),
        rows: [
            row("Irish Films", .variety, "&with_origin_country=IE&vote_count.gte=80\(noAnime)"),
            row("Irish Series", .variety, nil, "&with_origin_country=IE&vote_count.gte=10&without_genres=10762,10764,10767"),
            row("Set in Ireland", .variety, "&with_keywords=14985|4729|306154|262466&vote_count.gte=50"),
            row("Irish Comedies", .variety, "&with_origin_country=IE&with_genres=35&vote_count.gte=20", "&with_origin_country=IE&with_genres=35&vote_count.gte=5"),
            row("Irish Crime & Drama", .variety, "&with_origin_country=IE&with_genres=80|53&vote_count.gte=20", "&with_origin_country=IE&with_genres=80|18&vote_count.gte=5"),
            picks("Irish-American Crime Sagas", false, movies(1422, 398978, 261023, 4147, 3131, 23168, 322, 379, 8374)),
            row("Animated Irish Tales", .variety, "&with_origin_country=IE&with_genres=16\(kidsCert)&vote_count.gte=20", kids: true),
        ]
    )

    private static let easter = SeasonalHub(
        id: "easter", title: "Easter & Spring Break", kicker: "Family Fun",
        tagline: "Family Favorites, Springtime Adventures and Easter Classics",
        // Below Women's History Month until Easter's last few days (Easter can fall in late March).
        glyph: "Season:Easter", hue: 7, priority: 44, boostDays: 3, boost: 30,
        window: .around({ y in SDay.easter(y) }, 10, 1), genres: ["Family", "Kids", "Animation"],
        countdown: Countdown({ y in SDay.easter(y) }, "Day", "Easter", "Happy Easter", after: "Family Favorites All Week"),
        pick: row("Tonight's Pick", .popular, "&with_genres=10751&vote_average.gte=7&vote_count.gte=3000\(kidsCert)\(noAnime)&sort_by=vote_count.desc"),
        rows: [
            row("Easter & Bunny Favorites", .variety, "&with_keywords=9921|9923|350569|15199|181140&with_genres=10751|16\(kidsCert)\(noAnime)&vote_count.gte=10&primary_release_date.gte=1960-01-01", kids: true),
            familyNight(),
            row("Animated Adventures", .variety, "&with_genres=16,12\(kidsCert)\(noAnime)", kids: true),
            picks("Faith & Inspiration", false, movies(615, 9837, 6844, 665, 335778, 719256, 470878, 2447, 235260, 339984, 236751, 355547)),
            row("Spring Break Road Trips", .variety, "&with_keywords=158054|7312&with_genres=35&without_genres=16,10751&vote_count.gte=100"),
            row("Family Series to Share", .variety, nil, "&with_genres=10751|10762&vote_average.gte=7.2&vote_count.gte=50\(noAnime)", kids: true),
        ]
    )

    private static let mothersDay = SeasonalHub(
        id: "mothersday", title: "For Mom", kicker: "Mother's Day",
        tagline: "Heartfelt Stories, Big Laughs and Movies to Share",
        glyph: "Season:Mother", hue: 2, priority: 60,
        window: .around({ y in SDay.mothersDay(y) }, 7, 0), genres: ["Family"],
        countdown: Countdown({ y in SDay.mothersDay(y) }, "Day", "Mother's Day", "Happy Mother's Day"),
        pick: row("Tonight's Pick", .popular, "&with_keywords=240315|14768&with_genres=18|35&without_genres=27,53,878&vote_count.gte=800&sort_by=vote_count.desc\(noAnime)"),
        kidsPick: row("Tonight's Pick", .popular, "&with_keywords=456|240315|240237&with_genres=10751\(kidsCert)\(noAnime)&vote_count.gte=300&sort_by=vote_count.desc"),
        rows: [
            row("Mother-Daughter Stories", .variety, "&with_keywords=240315&without_genres=27,53,878&vote_count.gte=100", "&with_keywords=240315\(grownTV)&vote_count.gte=10"),
            row("Mothers & Sons", .variety, "&with_keywords=240237|386961&with_genres=18|35|10751&without_genres=27,878&vote_count.gte=100"),
            row("Moms Who Do It All", .variety, "&with_keywords=14768&with_genres=18|35&without_genres=27,878,53&vote_count.gte=100", "&with_keywords=14768\(grownTV)&vote_count.gte=10"),
            row("Rom-Coms to Share", .variety, "&with_genres=10749,35&vote_average.gte=6.5"),
            row("Feel-Good Favorites", .variety, feelGood, feelGoodTV),
            row("Family Comedies", .variety, "&with_genres=10751,35\(kidsCert)\(noAnime)", kids: true),
            row("Animated Moms & Families", .variety, "&with_keywords=456|240315|240237&with_genres=16\(kidsCert)\(noAnime)&vote_count.gte=100", kids: true),
        ]
    )

    private static let fathersDay = SeasonalHub(
        id: "fathersday", title: "For Dad", kicker: "Father's Day",
        tagline: "Action Heroes, Sports Legends and Father-Kid Stories",
        glyph: "Season:Father", hue: 0, priority: 60,
        window: .around({ y in SDay.fathersDay(y) }, 7, 0), genres: ["Family"],
        countdown: Countdown({ y in SDay.fathersDay(y) }, "Day", "Father's Day", "Happy Father's Day"),
        pick: row("Tonight's Pick", .popular, "&with_keywords=240119|240303|5905&vote_average.gte=7.2&vote_count.gte=1500&sort_by=vote_count.desc\(noAnime)"),
        kidsPick: row("Tonight's Pick", .popular, "&with_keywords=5905|240119|240303|33479&with_genres=16\(kidsCert)\(noAnime)&vote_count.gte=300&sort_by=vote_count.desc"),
        rows: [
            row("Father-Son Stories", .variety, "&with_keywords=240119&vote_count.gte=150", "&with_keywords=240119\(grownTV)&vote_count.gte=20"),
            row("Father-Daughter Stories", .variety, "&with_keywords=240303&vote_count.gte=150"),
            row("Action Dads", .variety, "&with_keywords=181068|5905|240303|240119&with_genres=28&vote_count.gte=300\(noAnime)"),
            row("Sports Legends", .variety, "&with_keywords=6075&with_genres=18&vote_count.gte=200"),
            row("Dad-Approved Classics", .topVoted, "&with_genres=28|10752|37|80&primary_release_date.gte=1970-01-01&primary_release_date.lte=1999-12-31"),
            row("Dad Comedies", .variety, "&with_keywords=5905|33479|240119|240303&with_genres=35&vote_count.gte=150"),
            row("Animated Dads & Kids", .variety, "&with_keywords=5905|240119|240303|33479&with_genres=16\(kidsCert)\(noAnime)&vote_count.gte=100", kids: true),
        ]
    )

    private static let pride = SeasonalHub(
        id: "pride", title: "Pride", kicker: "Pride Month",
        tagline: "Celebrating LGBTQ+ Love, Joy and Trailblazing Stories",
        glyph: "LGBTQ+", hue: 0, priority: 40,
        window: .fixed(601, 630),
        pick: row("Tonight's Pick", .popular, "\(lgbtq)&vote_average.gte=7.3&vote_count.gte=1000&sort_by=vote_count.desc\(noAnime)"),
        rows: [
            row("Acclaimed LGBTQ+ Films", .acclaimed, "\(lgbtq)\(noAnime)"),
            row("LGBTQ+ Love Stories", .variety, "\(lgbtq)&with_genres=10749&vote_count.gte=100", "&with_keywords=158718,9840&vote_count.gte=20"),
            row("LGBTQ+ Comedies", .variety, "\(lgbtq)&with_genres=35&vote_count.gte=100", "\(lgbtq)&with_genres=35&vote_count.gte=20"),
            row("LGBTQ+ Series", .variety, nil, "\(lgbtq)&with_genres=18|35&without_genres=10762,10751,10765&vote_count.gte=50"),
            row("Coming Out & Coming of Age", .variety, "&with_keywords=1862|382157&vote_count.gte=100", "&with_keywords=1862|382157\(grownTV)&vote_count.gte=5"),
            row("True Stories & Trailblazers", .variety, "\(lgbtq)&with_genres=99|36&vote_count.gte=20", "\(lgbtq)&with_genres=99&vote_count.gte=3"),
            row("New LGBTQ+ Releases", .new, lgbtq, lgbtq),
        ]
    )

    private static let julyFourth = SeasonalHub(
        id: "july4", title: "Fourth of July", kicker: "Independence Day",
        tagline: "Fireworks-Worthy Blockbusters and Stories of America",
        glyph: "Season:Fireworks", hue: 0, priority: 70,
        window: .fixed(628, 704), genres: ["History", "War", "Action"],
        countdown: Countdown({ y in SDay.of(y, 7, 4) }, "Day", "the Fourth", "Happy Fourth of July"),
        pick: row("Tonight's Pick", .popular, "&with_genres=28|12&with_origin_country=US&vote_average.gte=7.2&vote_count.gte=8000&sort_by=vote_count.desc"),
        rows: [
            row("Fireworks-Worthy Blockbusters", .popular, "&with_genres=28|12|878&vote_average.gte=6.8&vote_count.gte=3000&primary_release_date.gte={YEARSAGO:20}&sort_by=vote_count.desc\(noAnime)"),
            row("American History on Screen", .variety, "&with_keywords=190024|197159&with_genres=36|18|10752&vote_count.gte=20&primary_release_date.gte=1950-01-01", "&with_keywords=190024|197159\(grownTV)&vote_count.gte=5"),
            row("Presidents & Politics", .variety, "&with_keywords=366601|365774|833&vote_count.gte=100", "&with_keywords=366601|365774|833\(grownTV)&vote_count.gte=10"),
            row("To the Moon & Beyond", .variety, "&with_keywords=1432|156836&vote_count.gte=50"),
            row("Heroes in Uniform", .variety, "&with_genres=10752&with_origin_country=US&vote_count.gte=300"),
            row("All-American Classics", .topVoted, "&with_origin_country=US&primary_release_date.gte=1975-01-01&primary_release_date.lte=1999-12-31&vote_average.gte=7.3"),
            familyNight(),
        ]
    )

    private static let backToSchool = SeasonalHub(
        id: "backtoschool", title: "Back to School", kicker: "School's In",
        tagline: "High School Comedies, Campus Classics and Coming-of-Age Favorites",
        glyph: "Season:School", hue: 5, priority: 50,
        window: .fixed(815, 905), genres: ["Comedy", "Teen"],
        pick: row("Tonight's Pick", .popular, "&with_keywords=6270|10683&without_genres=27&vote_average.gte=7&vote_count.gte=1500&sort_by=vote_count.desc\(noAnime)"),
        rows: [
            row("High School Comedies", .variety, "&with_keywords=6270&with_genres=35&vote_count.gte=150", "&with_keywords=6270&with_genres=35\(grownTV)&vote_count.gte=20"),
            row("Coming-of-Age Favorites", .variety, "&with_keywords=10683&vote_count.gte=300\(noAnime)"),
            row("Campus Life", .variety, "&with_keywords=3616|13160|221023&with_genres=35|18&without_genres=27,16&vote_count.gte=100", "&with_keywords=3616|13160\(grownTV)&vote_count.gte=10"),
            picks("Inspiring Teachers", false, movies(207, 29154, 1646, 7214, 14621, 11820, 9909, 1584, 2054, 840430, 244786, 25934)),
            row("Teen Dramas", .variety, nil, "&with_keywords=193400|6270&with_genres=18\(grownTV)&vote_count.gte=40"),
            row("Boarding School Stories", .variety, "&with_keywords=6271&without_genres=27&vote_count.gte=50\(noAnime)"),
            row("School Days for Kids", .variety, "&with_keywords=10873|6270&with_genres=10751|16\(kidsCert)\(noAnime)&vote_count.gte=50",
                "&with_keywords=10873&with_genres=10762|10751\(noAnime)&vote_count.gte=5", kids: true),
        ]
    )

    private static let hispanicHeritage = SeasonalHub(
        id: "hispanicheritage", title: "Hispanic Heritage Month", kicker: "Celebrating Latino Stories",
        tagline: "Acclaimed Cinema, Bold Voices and Stories From Across the Hispanic World",
        glyph: "Season:Voices", hue: 4, priority: 40,
        window: .fixed(915, 1015), genres: ["Drama", "Documentary"],
        pick: row("Tonight's Pick", .popular, "&with_original_language=es&vote_average.gte=7.4&vote_count.gte=1200&sort_by=vote_count.desc\(noAnime)"),
        kidsPick: picks("Tonight's Pick", true, movies(354912, 568124, 228326, 449406, 364689, 46195)),
        rows: [
            row("Acclaimed Spanish-Language Films", .acclaimed, "&with_original_language=es"),
            row("Spanish-Language Series", .variety, nil, "&with_original_language=es&vote_count.gte=50&without_genres=10762,10764,10767,10766,16"),
            picks("Latino Stories", false, movies(16052, 16620, 29154, 30309, 38722, 211954, 467909, 511809, 565770, 626332, 228203, 1002185, 264337, 15104)
                + shows(69017, 61418, 98308, 4626, 3308)),
            row("Mexican Cinema", .variety, "&with_origin_country=MX&vote_count.gte=100\(noAnime)"),
            row("Latin American Cinema", .variety, "&with_original_language=es|pt&without_origin_country=ES&vote_count.gte=150\(noAnime)"),
            row("Spanish-Language Thrillers", .variety, "&with_original_language=es&with_genres=53|80&vote_count.gte=200", "&with_original_language=es&with_genres=80|9648&vote_count.gte=40"),
            picks("Modern Classics", false, movies(1391, 55, 426426, 1417, 1360, 11779, 211954)),
            picks("Family Favorites", true, movies(354912, 568124, 228326, 449406, 364689, 46195, 10054)),
        ]
    )

    private static let halloween = SeasonalHub(
        id: "halloween", title: "31 Nights of Horror", kicker: "Halloween",
        tagline: "A Fright for Every Night of October",
        glyph: "Halloween", hue: 1, priority: 50, nights: true,
        window: .fixed(1001, 1031), genres: ["Horror", "Halloween", "Thriller"],
        countdown: Countdown({ y in SDay.of(y, 10, 31) }, "Night", "Halloween", "Happy Halloween", eve: "Halloween Is Tomorrow Night"),
        pick: row("Tonight's Pick", .popular, "&with_genres=27&vote_average.gte=7&vote_count.gte=2500&sort_by=vote_count.desc\(noAnime)"),
        kidsPick: row("Tonight's Pick", .popular, "&with_genres=10751&with_keywords=3335|318653|162846\(kidsCert)\(noAnime)&vote_count.gte=300&sort_by=vote_count.desc"),
        rows: [
            row("Trending Horror", .trending, "&with_genres=27", "\(horrorTV)\(grownTV)", genre: 27),
            row("Halloween Movies", .variety, "&with_keywords=3335|318653|9694|232795&vote_count.gte=100&primary_release_date.gte=1970-01-01"),
            row("Slasher Classics", .topVoted, "&with_keywords=12339&with_genres=27&primary_release_date.lte={YEARSAGO:15}", "&with_keywords=12339\(grownTV)&vote_count.gte=40"),
            row("Supernatural & Hauntings", .variety, "&with_genres=27&with_keywords=256183|3358|162846|9712", "&with_keywords=3358|162846|256183\(grownTV)&vote_count.gte=40"),
            row("Creature Features", .variety, "&with_genres=27&with_keywords=158126|210614|11100|13031|1299|12564|3133"),
            row("Horror Comedy", .variety, "&with_genres=27,35", "&with_genres=35&with_keywords=315058|256183|3133|162846|1299\(grownTV)&vote_count.gte=30"),
            row("Horror Series", .variety, nil, "\(horrorTV)\(grownTV)"),
            row("Critically Acclaimed Horror", .acclaimed, "&with_genres=27", "\(horrorTV)\(grownTV)"),
            row("Family-Friendly Frights", .variety, "&with_genres=10751&with_keywords=3335|318653|616|162846|3133|1299|12564\(kidsCert)\(noAnime)&vote_count.gte=40",
                "&with_genres=10751|10762&with_keywords=3335|318653|616|162846\(noAnime)&vote_count.gte=5", kids: true),
            row("Animated Halloween Favorites", .variety, "&with_genres=16&with_keywords=3335|318653|616|162846|1299|3133\(kidsCert)\(noAnime)&vote_count.gte=30",
                "&with_genres=16&with_keywords=3335|318653\(noAnime)&without_genres=10768&vote_count.gte=3", kids: true),
        ]
    )

    private static let thanksgiving = SeasonalHub(
        id: "thanksgiving", title: "Thanksgiving Together", kicker: "Thanksgiving",
        tagline: "Family Gatherings, Feel-Good Favorites and Football Sunday",
        glyph: "Season:Turkey", hue: 4, priority: 60,
        window: .around({ y in SDay.thanksgiving(y) }, 7, 3), genres: ["Family"],
        countdown: Countdown({ y in SDay.thanksgiving(y) }, "Day", "Thanksgiving", "Happy Thanksgiving", after: "Thanksgiving Weekend Favorites"),
        pick: picks("Tonight's Pick", false, movies(2609, 9089, 2758, 9475, 5143, 22881, 773, 546554, 9043, 1550, 68924)),
        rows: [
            row("Thanksgiving Movies", .variety, "&with_keywords=4543|307493|374738&without_genres=27,53&vote_count.gte=20&primary_release_date.gte=1940-01-01"),
            row("Family Gatherings", .variety, "&with_keywords=18187|33694&with_genres=35|18|10751&without_genres=27,53,28,80&vote_count.gte=50"),
            row("Food & Feasts", .variety, "&with_keywords=10637|1918&with_genres=35|18|10749&vote_count.gte=100", "&with_keywords=1918|10637&with_genres=10764|99&vote_count.gte=10"),
            row("Football Sunday", .variety, "&with_keywords=579|11671|5621&vote_count.gte=50", "&with_keywords=579|11671&vote_count.gte=10"),
            row("Feel-Good Favorites", .variety, feelGood, feelGoodTV),
            row("Holiday Season Kickoff", .new, christmas),
            familyNight(),
            row("Animated Holiday Favorites", .variety, "&with_keywords=4543|207317&with_genres=16\(kidsCert)\(noAnime)&vote_count.gte=30", kids: true),
        ]
    )

    private static let christmasHub = SeasonalHub(
        id: "christmas", title: "Home for the Holidays", kicker: "Christmas",
        tagline: "Holiday Classics, Cozy Comedies and Family Favorites",
        glyph: "Season:Christmas", hue: 6, priority: 70,
        window: .fixed(1201, 1226), genres: ["Holiday", "Family", "Kids", "Comedy", "Romance"],
        countdown: Countdown({ y in SDay.of(y, 12, 25) }, "Day", "Christmas", "Merry Christmas", eve: "Tonight Is Christmas Eve", after: "Happy Holidays"),
        pick: row("Tonight's Pick", .popular, "\(christmas)&vote_count.gte=800&vote_average.gte=6.6&sort_by=vote_count.desc\(noAnime)"),
        kidsPick: row("Tonight's Pick", .popular, "\(christmas)&with_genres=10751\(kidsCert)\(noAnime)&vote_count.gte=300&sort_by=vote_count.desc"),
        rows: [
            row("Holiday Classics", .topVoted, "\(christmas)&vote_average.gte=6.5\(noAnime)"),
            row("Family Christmas Movies", .variety, "\(christmas)&with_genres=10751\(kidsCert)\(noAnime)&primary_release_date.gte=1960-01-01&vote_count.gte=50", kids: true),
            row("Holiday Rom-Coms", .variety, "\(christmas)&with_genres=10749&vote_count.gte=30"),
            row("Christmas Comedies", .variety, "\(christmas)&with_genres=35&vote_count.gte=100&primary_release_date.gte=1980-01-01"),
            row("Animated Holiday Specials", .variety, "\(christmas)&with_genres=16\(kidsCert)\(noAnime)&vote_count.gte=20&primary_release_date.gte=1960-01-01", kids: true),
            row("Holiday Action & Thrills", .variety, "\(christmas)&with_genres=28|53|80&without_genres=27,16,10751&vote_count.gte=100&primary_release_date.gte=1980-01-01"),
            row("Holiday Series & Specials", .variety, nil, "\(christmas)&without_genres=16,10762&vote_count.gte=5"),
            row("New Holiday Movies", .new, christmas),
            row("Santa, Elves & Snowmen", .variety, "&with_keywords=1991|10115|5570&with_genres=10751|16\(kidsCert)\(noAnime)&vote_count.gte=30", kids: true),
        ]
    )

    /// Every hub (the base seasons first: `SeasonCalendar` falls back to them).
    static let all: [SeasonalHub] = [
        winter, spring, summer, fall,
        newYear, valentines, blackHistory, awards, womensHistory, stPatricks, easter, mothersDay, fathersDay,
        pride, julyFourth, backToSchool, hispanicHeritage, halloween, thanksgiving, christmasHub,
    ]
}
