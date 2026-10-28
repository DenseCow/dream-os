import Foundation
import DreamCore

/// What Discover filters by (DiscoverFilters + Discover.kt). The same filters drive the Browse wall, the category rows
/// and the genre shortcuts.
struct DFilters: Sendable {
    var type: MediaKind = .movie
    var genre = 0
    /// "" any, "1990s" a decade, "older" before 1970, or "2024".
    var year = ""
    var rating = 0
    var runtime = 0
    var lang = ""
    var service = ""
    var sort: DiscoverSort = .mixed
    var movie: Bool { type == .movie }
}

enum Discover {
    static let animeKeyword = 210024
    private static let noAnime = "&without_keywords=210024"

    static let anime = -1, tvAdventure = -2, tvFantasy = -3, tvHistory = -4, tvHorror = -5, movieKids = -6, tvMusic = -7
    static let tvRomance = -8, tvThriller = -9, adultAnimationMovie = -10, adultAnimationTv = -11, trueCrime = -12, standUp = -13
    static let sitcom = -14, superheroMovie = -15, superheroTv = -16, musicalMovie = -17, musicalTv = -18, halloween = -19
    static let holiday = -20, mindBending = -21, trueStory = -22, feelGoodMovie = -23, feelGoodTv = -24, sportsMovie = -25
    static let sportsTv = -26, lgbtq = -27, teen = -28, any = -29, anyTv = -30

    static let movieGenres: [(Int, String)] = [(28, "Action"), (12, "Adventure"), (16, "Animation"), (35, "Comedy"), (80, "Crime"),
        (99, "Documentary"), (18, "Drama"), (10751, "Family"), (14, "Fantasy"), (36, "History"), (27, "Horror"), (10402, "Music"),
        (9648, "Mystery"), (10749, "Romance"), (878, "Science Fiction"), (53, "Thriller"), (10752, "War"), (37, "Western")]
    static let tvGenres: [(Int, String)] = [(10759, "Action & Adventure"), (16, "Animation"), (35, "Comedy"), (80, "Crime"),
        (99, "Documentary"), (18, "Drama"), (10751, "Family"), (10762, "Kids"), (9648, "Mystery"), (10764, "Reality"),
        (10765, "Sci-Fi & Fantasy"), (10766, "Soap"), (10767, "Talk"), (10768, "War & Politics"), (37, "Western")]

    static func genres(_ type: MediaKind) -> [(Int, String)] { type == .movie ? movieGenres : tvGenres }

    static func genreName(_ type: MediaKind, _ id: Int) -> String? {
        if let g = genres(type).first(where: { $0.0 == id }) { return g.1 }
        return id < 0 ? categories.first { $0.id(type) == id }?.name : nil
    }

    private static let pseudo: [Int: String] = [
        anime: "&with_genres=16&with_original_language=ja",
        tvAdventure: "&with_genres=10759&with_keywords=322942|6956|12988|1963|207372|10787|157186&without_genres=16",
        tvFantasy: "&with_genres=10765&with_keywords=293198|2343|177912|12554|3205|211227|234213|170362|177895&without_genres=16",
        tvHistory: "&with_keywords=192772|15060",
        tvHorror: "&with_keywords=315058|256183|12339|325319|313454",
        movieKids: "&with_genres=16,10751\(noAnime)",
        tvMusic: "&with_keywords=4344",
        tvRomance: "&with_keywords=9840|386551&without_genres=16",
        tvThriller: "&with_keywords=316362|12565",
        adultAnimationMovie: "&with_genres=16&without_genres=10751&certification_country=US&certification=R|NC-17\(noAnime)",
        adultAnimationTv: "&with_keywords=161919&without_genres=10751,10762\(noAnime)",
        trueCrime: "&with_genres=99,80",
        standUp: "&with_keywords=9716",
        sitcom: "&with_genres=35&with_keywords=193171&without_genres=10762",
        superheroMovie: "&with_keywords=9715\(noAnime)",
        superheroTv: "&with_keywords=9715&without_genres=10762\(noAnime)",
        musicalMovie: "&with_keywords=4344\(noAnime)",
        musicalTv: "&with_keywords=4344&without_genres=10762\(noAnime)",
        halloween: "&with_keywords=3335|318653",
        holiday: "&with_keywords=207317",
        mindBending: "&with_keywords=362567|326438|275311|10854|157171|321059|243230|3972|231173|174089\(noAnime)",
        trueStory: "&with_keywords=9672",
        feelGoodMovie: "&with_genres=35|10751|10749&without_genres=27,53,80,10752,99,9648,28&vote_average.gte=7\(noAnime)",
        feelGoodTv: "&with_genres=35&without_genres=80,9648,10768,99,10762,16,10764,10767&vote_average.gte=7.5",
        sportsMovie: "&with_keywords=6075\(noAnime)",
        sportsTv: "&with_keywords=6075&without_genres=16,99",
        lgbtq: "&with_keywords=158718\(noAnime)",
        teen: "&with_keywords=155722|193400|6270\(noAnime)",
        any: "",
        anyTv: "&without_genres=10763,10764,10766,10767",
    ]

    /// The filter for category [id] on [type]; nil for 0.
    static func clause(_ type: MediaKind, _ id: Int, lang: String = "") -> String? {
        if id > 0 { return "&with_genres=\(id)" + noAnimeFor(id, lang) }
        if id < 0 { return pseudo[id] }
        return nil
    }

    static func noAnimeFor(_ genre: Int, _ lang: String) -> String { genre == 16 && lang != "ja" ? noAnime : "" }

    static func wantsAnime(_ f: DFilters) -> Bool { f.genre == anime || (f.genre == 16 && f.lang == "ja") || f.service == "Crunchyroll" }

    struct Category: Sendable { let name: String; let movie: Int; let tv: Int; func id(_ t: MediaKind) -> Int { t == .movie ? movie : tv } }

    static let categories: [Category] = [
        Category(name: "Action", movie: 28, tv: 10759), Category(name: "Adventure", movie: 12, tv: tvAdventure), Category(name: "Animation", movie: 16, tv: 16),
        Category(name: "Anime", movie: anime, tv: anime), Category(name: "Comedy", movie: 35, tv: 35), Category(name: "Crime", movie: 80, tv: 80),
        Category(name: "Documentary", movie: 99, tv: 99), Category(name: "Drama", movie: 18, tv: 18), Category(name: "Family", movie: 10751, tv: 10751),
        Category(name: "Fantasy", movie: 14, tv: tvFantasy), Category(name: "History", movie: 36, tv: tvHistory), Category(name: "Horror", movie: 27, tv: tvHorror),
        Category(name: "Kids", movie: movieKids, tv: 10762), Category(name: "Music", movie: 10402, tv: tvMusic), Category(name: "Mystery", movie: 9648, tv: 9648),
        Category(name: "Reality", movie: 0, tv: 10764), Category(name: "Romance", movie: 10749, tv: tvRomance), Category(name: "Sci-Fi", movie: 878, tv: 10765),
        Category(name: "Thriller", movie: 53, tv: tvThriller), Category(name: "War", movie: 10752, tv: 10768), Category(name: "Western", movie: 37, tv: 37),
    ]

    static func categories(_ type: MediaKind) -> [Category] { categories.filter { $0.id(type) != 0 } }

    static func serviceIds(_ name: String) -> String { Tmdb.services.filter { $0.name == name }.map { "\($0.providerId)" }.joined(separator: "|") }

    /// The /discover query for [f] (without the page).
    static func query(_ f: DFilters) -> String {
        var sb = "&include_adult=false&include_video=false"
        let dateKey = f.movie ? "primary_release_date" : "first_air_date"
        var gte: String?, lte: String?
        var votes = 0
        switch f.sort {
        case .topRated:
            sb += "&sort_by=vote_average.desc"
            let narrow = f.genre != 0 || !f.service.isEmpty || !f.lang.isEmpty
            votes = f.movie ? (narrow ? 300 : 1000) : (narrow ? 100 : 300)
            if f.year.isEmpty { gte = RowMix.yearsAgo(RowMix.acclaimedYears) }
        case .newest: sb += "&sort_by=\(dateKey).desc"; lte = DreamTime.ymdPlus(0); votes = 10
        case .comingSoon: sb += "&sort_by=popularity.desc"; gte = DreamTime.ymdPlus(1); lte = DreamTime.ymdPlus(150)
        default: sb += "&sort_by=popularity.desc"; votes = f.movie ? 40 : 20
        }
        func minD(_ a: String?, _ b: String) -> String { a == nil || b < a! ? b : a! }
        func maxD(_ a: String?, _ b: String) -> String { a == nil || b > a! ? b : a! }
        if f.year == "older" { lte = minD(lte, "1969-12-31") }
        else if f.year.hasSuffix("s"), let d = Int(f.year.dropLast()) { gte = maxD(gte, "\(d)-01-01"); lte = minD(lte, "\(d + 9)-12-31") }
        else if let y = Int(f.year) { gte = maxD(gte, "\(y)-01-01"); lte = minD(lte, "\(y)-12-31") }
        if let gte { sb += "&\(dateKey).gte=\(gte)" }
        if let lte { sb += "&\(dateKey).lte=\(lte)" }
        if f.genre > 0 { sb += "&with_genres=\(f.genre)" + noAnimeFor(f.genre, f.lang) }
        else if f.genre < 0, let p = pseudo[f.genre] { sb += p }
        if f.rating > 0 { sb += "&vote_average.gte=\(f.rating)"; votes = max(votes, 100) }
        if f.movie {
            switch f.runtime {
            case 1: sb += "&with_runtime.lte=89"
            case 2: sb += "&with_runtime.gte=90&with_runtime.lte=120"
            case 3: sb += "&with_runtime.gte=121"
            default: break
            }
        }
        if !f.lang.isEmpty { sb += "&with_original_language=\(f.lang)" }
        if !f.service.isEmpty { let ids = serviceIds(f.service); if !ids.isEmpty { sb += "&with_watch_providers=\(ids)&watch_region=US&with_watch_monetization_types=flatrate" } }
        if votes > 0 { sb += "&vote_count.gte=\(votes)" }
        return sb
    }

    /// One page of the wall; Popular & Favorites interleaves the popular page with the most-voted over a year old.
    static func page(_ f: DFilters, _ page: Int) async throws -> DiscoverPage {
        let q = query(f)
        if f.sort != .mixed { return try await Tmdb.discover(f.type, q, page: page) }
        let dk = RowMix.dateKey(f.type)
        let fav = q.replacingOccurrences(of: "&sort_by=[^&]*", with: "&sort_by=vote_count.desc", options: .regularExpression) +
            (q.contains("\(dk).lte=") ? "" : "&\(dk).lte=\(RowMix.yearsAgo(1))") + (f.year.isEmpty ? "&\(dk).gte=\(RowMix.yearsAgo(RowMix.recentYears))" : "")
        async let popular = Tmdb.discover(f.type, q, page: page)
        async let favorites = (try? await Tmdb.discover(f.type, fav, page: page).titles) ?? []
        let p = try await popular
        return DiscoverPage(titles: RowMix.interleave([p.titles, await favorites]), page: p.page, totalPages: p.totalPages, totalResults: p.totalResults)
    }

    /// Discover's list for [f] as a row list (Tmdb.list with Discover.query).
    static func list(_ f: DFilters, extra: String = "") async throws -> [Title] {
        try await Tmdb.list("/discover/\(f.type.rawValue)", query(f) + extra, type: f.type)
    }
}

/// New Releases (NewReleases.kt): what has just become watchable at home.
enum NewReleases {
    static let movieDays = 60, tvDays = 45
    private static let wideMovieDays = 150, wideTvDays = 120, thin = 10
    private static let base = "&include_adult=false&include_video=false&sort_by=popularity.desc"
    private static let tvSkip = ["10763", "10766", "10767"]

    /// Every /discover query that doesn't ask for anime leaves out TMDB's "anime" keyword.
    static func animeClause(_ path: String, _ query: String) -> String {
        guard path.hasPrefix("/discover/") else { return "" }
        let asked = query.range(of: "(^|&)with_original_language=ja(&|$)", options: .regularExpression) != nil ||
            query.range(of: "(^|&)with_keywords=[^&]*\\b210024\\b", options: .regularExpression) != nil ||
            query.range(of: "(^|&)with_watch_providers=[^&]*\\b283\\b", options: .regularExpression) != nil
        return asked ? "" : "&without_keywords=210024"
    }

    private static func withArt(_ l: [Title]) -> [Title] { l.filter { $0.posterPath != nil || $0.backdropPath != nil } }

    /// In Theaters: US theatrical releases of the last ~10 weeks, only films first released recently.
    static func inTheaters() async throws -> [Title] {
        let q = "\(base)&with_release_type=2|3&release_date.gte=\(DreamTime.ymdPlus(-75))&release_date.lte=\(DreamTime.ymdPlus(0))" +
            "&primary_release_date.gte=\(DreamTime.ymdPlus(-150))"
        return withArt(try await Tmdb.list("/discover/movie", q, type: .movie))
    }

    private static func window(_ type: MediaKind, _ narrow: Bool, _ usual: String, _ wide: String) async -> String {
        if !narrow { return usual }
        // The wide window is fetched alongside the probe (speed pass): a thin answer then needs no second round trip.
        Task { _ = try? await Tmdb.list("/discover/\(type.rawValue)", wide, type: type) }
        guard let first = try? await RowPaging.at("NewReleases probe", 1, { try await Tmdb.list("/discover/\(type.rawValue)", usual, type: type) }) else { return usual }
        return first.count < thin ? wide : usual
    }

    static func movies(_ extra: String = "") async throws -> [Title] {
        let narrow = !extra.isEmpty
        let votes = narrow ? 8 : 25
        func q(_ days: Int) -> String {
            "\(base)\(extra)&with_release_type=4|5&release_date.gte=\(DreamTime.ymdPlus(-days))&release_date.lte=\(DreamTime.ymdPlus(0))" +
                "&primary_release_date.gte=\(DreamTime.ymdPlus(-540))&vote_count.gte=\(votes)"
        }
        let query = await window(.movie, narrow, q(movieDays), q(wideMovieDays))
        return withArt(try await Tmdb.list("/discover/movie", query, type: .movie))
    }

    private static func skipGenres(_ extra: String) async -> String {
        var asked = Set<String>()
        if let r = extra.range(of: "with_genres=([0-9,|]+)", options: .regularExpression) {
            asked = Set(extra[r].dropFirst("with_genres=".count).split(whereSeparator: { $0 == "," || $0 == "|" }).map(String.init))
        }
        let kids = await KidsFilter.level() != nil
        let skip = (tvSkip + (kids ? [] : ["10762"])).filter { !asked.contains($0) }
        return skip.isEmpty ? "" : "&without_genres=" + skip.joined(separator: ",")
    }

    /// Brand-new series only (premiered in the last tvDays days).
    static func shows(_ extra: String = "") async throws -> [Title] {
        let narrow = !extra.isEmpty
        let skip = await skipGenres(extra)
        func series(_ days: Int) -> String {
            "\(base)\(extra)\(skip)&first_air_date.gte=\(DreamTime.ymdPlus(-days))&first_air_date.lte=\(DreamTime.ymdPlus(0))&vote_count.gte=\(narrow ? 3 : 10)"
        }
        let query = await window(.tv, narrow, series(tvDays), series(wideTvDays))
        return withArt(try await Tmdb.list("/discover/tv", query, type: .tv))
    }

    /// The general "New Episodes" row: running shows whose latest season premiered in the last tvDays days.
    static func returning(_ extra: String = "") async throws -> [Title] {
        let narrow = !extra.isEmpty
        let skip = await skipGenres(extra)
        let days = narrow ? wideTvDays : tvDays
        let from = DreamTime.ymdPlus(-days)
        let running = "\(base)\(extra)\(skip)&air_date.gte=\(from)&air_date.lte=\(DreamTime.ymdPlus(0))&first_air_date.lte=\(DreamTime.ymdPlus(-days - 1))" +
            "&vote_count.gte=\(narrow ? 15 : 50)"
        let airing = try await Tmdb.list("/discover/tv", running, type: .tv)
        var dates = [String](repeating: "", count: airing.count)
        await withTaskGroup(of: (Int, String).self) { g in
            for (i, t) in airing.enumerated() { g.addTask { (i, await latestSeason(t.id)) } }
            for await (i, d) in g { dates[i] = d }
        }
        return withArt(airing.enumerated().filter { dates[$0.offset] >= from }.map(\.element))
    }

    /// A show's latest season premiere up to today ("" when none), cached with the details call.
    private static func latestSeason(_ id: Int) async -> String {
        guard let o = try? await Tmdb.object("/tv/\(id)") else { return "" }
        let today = DreamTime.ymdPlus(0)
        var latest = ""
        for s in o["seasons"] as? [[String: Any]] ?? [] {
            let d = Tmdb.str(s, "air_date")
            if (s["season_number"] as? Int ?? 0) >= 1 && d.count >= 10 && d <= today && d > latest { latest = d }
        }
        if let ep = o["last_episode_to_air"] as? [String: Any] { lastEpisodes.put(id, episodeLine(ep)) }
        return latest
    }

    /// "S22 E5 · Name" (or "New Episode · S22 E5" when TMDB names it only "Episode 5"): the newest aired episode
    /// under a New Episodes card instead of "2005 · Series" (owner, 2026-10-28). Nil when TMDB has none.
    private static func episodeLine(_ ep: [String: Any]) -> String? {
        guard let s = ep["season_number"] as? Int, let n = ep["episode_number"] as? Int, s >= 1 else { return nil }
        let name = Tmdb.str(ep, "name").trimmingCharacters(in: .whitespaces)
        let generic = name.isEmpty || name.range(of: "^(Episode|Ep\\.?)\\s*\\d+$", options: [.regularExpression, .caseInsensitive]) != nil
        return generic ? "New Episode · S\(s) E\(n)" : "S\(s) E\(n) · \(name)"
    }

    /// Each returning show's newest episode line, filled by `returning` (from the details call it already makes).
    static let lastEpisodes = EpisodeLines()

    /// The New Episodes rows' card decoration: the newest episode as the subtitle.
    static let episodeSub: @Sendable (Title) -> (kicker: String?, subtitle: String?, badge: String?) = { t in
        (nil, lastEpisodes.get(t.id), nil)
    }

    /// Movies and shows interleaved; nil extra: none of that type.
    static func mixed(_ movieExtra: String? = "", _ tvExtra: String? = "", lead: MediaKind = .movie) async -> [Title] {
        async let m: [Title] = movieExtra == nil ? [] : ((try? await movies(movieExtra!)) ?? [])
        async let t: [Title] = tvExtra == nil ? [] : ((try? await shows(tvExtra!)) ?? [])
        let (a, b) = (await m, await t)
        return RowMix.interleave(lead == .tv ? [b, a] : [a, b])
    }

    static func ofType(_ type: MediaKind, _ extra: String = "") async throws -> [Title] {
        type == .movie ? try await movies(extra) : try await shows(extra)
    }

    static func forGenre(_ spec: GenreSpec) async -> [Title] {
        let types = GenreShelves.types(spec)
        let m = types.contains(.movie) ? GenreShelves.clause(spec, .movie) : nil
        let t = types.contains(.tv) ? GenreShelves.clause(spec, .tv) : nil
        if !spec.anime { return await mixed(m, t, lead: spec.primary) }
        func tagged(_ c: String?) -> String? { c?.replacingOccurrences(of: "&with_original_language=ja", with: "&with_keywords=210024") }
        return RowMix.interleave([await mixed(m, t, lead: spec.primary), await mixed(tagged(m), tagged(t), lead: spec.primary)])
    }

    static func onService(_ service: String) async -> [Title] {
        let ids = Discover.serviceIds(service)
        if ids.isEmpty { return [] }
        let on = "&with_watch_providers=\(ids)&watch_region=US&with_watch_monetization_types=flatrate"
        return await mixed(on, on, lead: .tv)
    }
}

/// Show id -> its newest episode line (`NewReleases.episodeLine`), safe from any thread.
final class EpisodeLines: @unchecked Sendable {
    private let lock = NSLock()
    private var lines: [Int: String] = [:]
    func put(_ id: Int, _ line: String?) { lock.withLock { lines[id] = line } }
    func get(_ id: Int) -> String? { lock.withLock { lines[id] } }
}
