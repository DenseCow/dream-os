import Foundation
import DreamCore

/// A Browse category (GenreSpec.kt): its Discover ids for movies and TV, language, and the words its rows are named with.
struct GenreSpec: Sendable {
    let name: String
    let movie: Int
    let tv: Int
    var lang = ""
    var plural: String
    var adj: String
    var series: String
    var fresh: String
    var votes = 0
    var year = ""
    var main: MediaKind?
    /// Days of the year it shows ("MMdd" inclusive), nil = always.
    var season: ClosedRange<Int>?
    var movieRow: String?
    var tvRow: String?

    init(_ name: String, _ movie: Int, _ tv: Int, lang: String = "", plural: String? = nil, adj: String? = nil, series: String? = nil,
         fresh: String? = nil, votes: Int = 0, year: String = "", main: MediaKind? = nil, season: ClosedRange<Int>? = nil,
         movieRow: String? = nil, tvRow: String? = nil) {
        self.name = name; self.movie = movie; self.tv = tv; self.lang = lang
        let a = adj ?? name
        self.plural = plural ?? name; self.adj = a; self.series = series ?? "\(a) Series"; self.fresh = fresh ?? "New \(a) Releases"
        self.votes = votes; self.year = year; self.main = main; self.season = season; self.movieRow = movieRow; self.tvRow = tvRow
    }

    func rowTitle(_ type: MediaKind) -> String { type == .movie ? (movieRow ?? "\(adj) Movies") : (tvRow ?? "\(adj) Shows") }
    var primary: MediaKind { main ?? (movie != 0 ? .movie : .tv) }
    func inSeason() -> Bool {
        guard let s = season else { return true }
        let c = DreamTime.calendar
        let now = Date()
        return s.contains(c.component(.month, from: now) * 100 + c.component(.day, from: now))
    }
    var anime: Bool { lang == "ja" && (movie == 16 || tv == 16 || movie == Discover.anime) }

    static let shortcuts: [GenreSpec] = [
        GenreSpec("Action", 28, 10759, plural: "Action", adj: "Action"),
        GenreSpec("Adventure", 12, Discover.tvAdventure, plural: "Adventures", adj: "Adventure"),
        GenreSpec("Comedy", 35, 35, plural: "Comedies", adj: "Comedy"),
        GenreSpec("Drama", 18, 18, plural: "Dramas", adj: "Drama"),
        GenreSpec("Horror", 27, Discover.tvHorror, plural: "Horror", adj: "Horror"),
        GenreSpec("Thriller", 53, Discover.tvThriller, plural: "Thrillers", adj: "Thriller"),
        GenreSpec("Sci-Fi", 878, 10765, plural: "Sci-Fi", adj: "Sci-Fi"),
        GenreSpec("Fantasy", 14, Discover.tvFantasy, plural: "Fantasy", adj: "Fantasy", votes: 100),
        GenreSpec("Romance", 10749, Discover.tvRomance, plural: "Romance", adj: "Romantic", series: "Romance Series", fresh: "New Romance Releases"),
        GenreSpec("Crime", 80, 80, plural: "Crime Stories", adj: "Crime"),
        GenreSpec("Mystery", 9648, 9648, plural: "Mysteries", adj: "Mystery"),
        GenreSpec("Animation", 16, 16, plural: "Animation", adj: "Animated"),
        GenreSpec("Anime", 16, 16, lang: "ja", plural: "Anime", adj: "Anime", votes: 80, tvRow: "Anime Series"),
        GenreSpec("Family", 10751, 10751, plural: "Family Favorites", adj: "Family"),
        GenreSpec("Kids", Discover.movieKids, 10762, plural: "Kids' Favorites", adj: "Kids'", series: "Kids' Shows", fresh: "New for Kids", votes: 80),
        GenreSpec("Documentary", 99, 99, plural: "Documentaries", adj: "Documentary", series: "Docuseries", votes: 40, movieRow: "Documentaries", tvRow: "Docuseries"),
        GenreSpec("Reality", 0, 10764, plural: "Reality TV", adj: "Reality", fresh: "New Reality Shows", votes: 20, tvRow: "Reality TV"),
        GenreSpec("History", 36, Discover.tvHistory, plural: "Period Pieces", adj: "Historical", votes: 60),
        GenreSpec("War", 10752, 10768, plural: "War Stories", adj: "War", votes: 60),
        GenreSpec("Western", 37, 37, plural: "Westerns", adj: "Western", votes: 30),
        GenreSpec("Music", 10402, Discover.tvMusic, plural: "Music & Musicals", adj: "Music", series: "Musical Series", fresh: "New Music Movies", votes: 50),
    ]

    static let categories: [GenreSpec] = [
        GenreSpec("Halloween", Discover.halloween, Discover.halloween, plural: "Halloween Favorites", adj: "Halloween", series: "Spooky Series",
                  fresh: "New for Halloween", votes: 40, season: 915...1031),
        GenreSpec("Holiday", Discover.holiday, Discover.holiday, plural: "Holiday Favorites", adj: "Holiday", series: "Holiday Series",
                  fresh: "New Holiday Movies", votes: 40, season: 1101...1231),
        GenreSpec("Adult Animation", Discover.adultAnimationMovie, Discover.adultAnimationTv, plural: "Adult Animation", adj: "Adult Animated",
                  series: "Adult Animated Movies", fresh: "New Adult Animation", votes: 40, main: .tv, tvRow: "Adult Animated Series"),
        GenreSpec("K-Drama", 18, 18, lang: "ko", plural: "K-Dramas", adj: "K-Drama", series: "Korean Movies", fresh: "New K-Dramas", votes: 40, main: .tv,
                  movieRow: "Korean Movies", tvRow: "K-Dramas"),
        GenreSpec("True Crime", Discover.trueCrime, Discover.trueCrime, plural: "True Crime", adj: "True Crime", series: "True Crime Documentaries",
                  fresh: "New True Crime", votes: 30, main: .tv, movieRow: "True Crime Documentaries", tvRow: "True Crime Series"),
        GenreSpec("Stand-Up Comedy", Discover.standUp, Discover.standUp, plural: "Stand-Up Specials", adj: "Stand-Up", series: "Stand-Up Series",
                  fresh: "New Stand-Up Specials", votes: 30, movieRow: "Stand-Up Specials", tvRow: "Stand-Up Series"),
        GenreSpec("Sitcoms", 0, Discover.sitcom, plural: "Sitcoms", adj: "Sitcom", fresh: "New Sitcoms", votes: 40, tvRow: "Sitcoms"),
        GenreSpec("Superheroes", Discover.superheroMovie, Discover.superheroTv, plural: "Superhero Hits", adj: "Superhero", series: "Superhero Series",
                  fresh: "New Superhero Releases"),
        GenreSpec("Based on a True Story", Discover.trueStory, Discover.trueStory, plural: "True Stories", adj: "True Story",
                  series: "True Story Series", fresh: "New True Stories", movieRow: "True Story Movies", tvRow: "True Story Series"),
        GenreSpec("Feel-Good", Discover.feelGoodMovie, Discover.feelGoodTv, plural: "Feel-Good Favorites", adj: "Feel-Good",
                  series: "Feel-Good Series", fresh: "New Feel-Good Releases", votes: 60),
        GenreSpec("Mind-Bending", Discover.mindBending, Discover.mindBending, plural: "Mind-Benders", adj: "Mind-Bending",
                  series: "Mind-Bending Series", fresh: "New Mind-Benders", votes: 60),
        GenreSpec("Sports", Discover.sportsMovie, Discover.sportsTv, plural: "Sports Stories", adj: "Sports", series: "Sports Series",
                  fresh: "New Sports Releases", votes: 50),
        GenreSpec("Teen", Discover.teen, Discover.teen, plural: "Teen Favorites", adj: "Teen", series: "Teen Series", fresh: "New Teen Releases"),
        GenreSpec("Musicals", Discover.musicalMovie, Discover.musicalTv, plural: "Musicals", adj: "Musical", series: "Musical Series",
                  fresh: "New Musicals", votes: 50, movieRow: "Musicals", tvRow: "Musical Series"),
        GenreSpec("LGBTQ+", Discover.lgbtq, Discover.lgbtq, plural: "LGBTQ+ Stories", adj: "LGBTQ+", series: "LGBTQ+ Series",
                  fresh: "New LGBTQ+ Releases", votes: 40),
    ]

    static let decades: [GenreSpec] = [1970, 1980, 1990, 2000].map { y in
        let adj = y < 2000 ? "’\(y % 100)s" : "\(y)s"
        return GenreSpec("\(adj) Classics", Discover.any, Discover.anyTv, plural: "\(adj) Classics", adj: adj,
                         series: y < 2000 ? "\(adj) TV Classics" : "\(adj) TV Favorites", votes: 100, year: "\(y)s")
    }

    static func all() -> [GenreSpec] { shortcuts + categories.filter { $0.inSeason() } + decades }
    static func find(_ name: String?) -> GenreSpec? { guard let name else { return nil }; return (shortcuts + categories + decades).first { $0.name == name } }
}

/// One row of a genre page (GenreShelves.kt `Shelf`).
struct Shelf: Sendable {
    enum Mode: Sendable { case trending, new, releases, acclaimed, gems, variety, topVoted, top }
    let title: String
    let mode: Mode
    let movie: String?
    let tv: String?
    func query(_ t: MediaKind) -> String? { t == .movie ? movie : tv }
}

enum GenreShelves {
    private static let subsFirst = 3
    private static let base = "&include_adult=false&include_video=false&sort_by=popularity.desc"
    private static let notOnTv = "16,10751,10762"

    struct Pick: Sendable { let title: String; let movie: String?; let tv: String?; var decade = 0; var votes = 0 }
    private static func kw(_ t: String, _ ids: String, movie: Bool = true, tv: Bool = true, votes: Int = 0) -> Pick {
        Pick(title: t, movie: movie ? "&with_keywords=\(ids)" : nil, tv: tv ? "&with_keywords=\(ids)" : nil, votes: votes)
    }
    private static func both(_ t: String, _ m: Int, _ v: Int) -> Pick {
        Pick(title: t, movie: m != 0 ? "&with_genres=\(m)" : nil, tv: v != 0 ? "&with_genres=\(v)" : nil)
    }
    private static func p(_ t: String, _ m: String?, _ v: String?) -> Pick { Pick(title: t, movie: m, tv: v) }
    private static func decade(_ name: String, _ year: Int) -> Pick {
        Pick(title: "’\(String(format: "%02d", year % 100))s \(name) Classics", movie: "", tv: "", decade: year)
    }

    static func clause(_ spec: GenreSpec, _ type: MediaKind) -> String? {
        guard let c = Discover.clause(type, type == .movie ? spec.movie : spec.tv, lang: spec.lang) else { return nil }
        return (spec.lang.isEmpty ? c : "\(c)&with_original_language=\(spec.lang)") + (Int(spec.year.dropLast()).map { dates(type, $0) } ?? "")
    }

    static func types(_ spec: GenreSpec) -> [MediaKind] {
        [spec.primary, spec.primary == .movie ? .tv : .movie].filter { ($0 == .movie ? spec.movie : spec.tv) != 0 }
    }

    /// [clause] with [add] on top (GenreShelves.merge).
    static func merge(_ clause: String, _ add: String) -> String {
        func parse(_ s: String) -> [(String, String)] {
            s.split(separator: "&").filter { $0.contains("=") }.map { p in
                let i = p.firstIndex(of: "=")!; return (String(p[..<i]), String(p[p.index(after: i)...]))
            }
        }
        var keys: [String] = []
        var m: [String: String] = [:]
        func set(_ k: String, _ v: String) { if m[k] == nil { keys.append(k) }; m[k] = v }
        for (k, v) in parse(clause) { set(k, v) }
        var replaced = false
        for (k, v) in parse(add) {
            switch k {
            case "with_genres", "without_genres": set(k, m[k].map { "\($0),\(v)" } ?? v)
            case "with_keywords": if m[k] != nil { replaced = true }; set(k, v)
            default: set(k, v)
            }
        }
        if replaced && m["with_genres"] == nil { set("without_genres", m["without_genres"].map { "\($0),\(notOnTv)" } ?? notOnTv) }
        return keys.map { "&\($0)=\(m[$0]!)" }.joined()
    }

    private static func dates(_ type: MediaKind, _ year: Int) -> String {
        let k = RowMix.dateKey(type); return "&\(k).gte=\(year)-01-01&\(k).lte=\(year + 9)-12-31"
    }

    static func curated(_ spec: GenreSpec) -> [Shelf] {
        (curatedMap[spec.name] ?? decadeRows(spec)).map { pick in
            func q(_ type: MediaKind) -> String? {
                guard let c = clause(spec, type), let add = type == .movie ? pick.movie : pick.tv else { return nil }
                let votes = pick.votes > 0 ? pick.votes : spec.votes
                return merge(c, add) + (pick.decade != 0 ? dates(type, pick.decade) : "") +
                    (votes > 0 ? "&vote_count.gte=\(type == .movie ? votes : votes / 2)" : "")
            }
            return Shelf(title: pick.title, mode: .variety, movie: q(.movie), tv: q(.tv))
        }
    }

    /// Every row of [spec]'s page in order (GenreShelves.page).
    static func page(_ spec: GenreSpec, services: [String]) -> [Shelf] {
        let m = clause(spec, .movie), t = clause(spec, .tv)
        var out: [Shelf] = []
        let era = !spec.year.isEmpty
        if era {
            out.append(Shelf(title: "Popular \(spec.plural)", mode: .variety, movie: m, tv: t))
            out.append(Shelf(title: "\(spec.adj) Blockbusters", mode: .topVoted, movie: m, tv: nil))
            out.append(Shelf(title: spec.series, mode: .topVoted, movie: nil, tv: t))
            out.append(Shelf(title: "Top Rated \(spec.plural)", mode: .top, movie: m, tv: t))
        } else {
            out.append(Shelf(title: "Trending \(spec.plural)", mode: .trending, movie: m, tv: t))
            out.append(Shelf(title: "New Releases", mode: .releases, movie: m, tv: t))
            let votes = spec.votes > 0 ? "&vote_count.gte=\(spec.votes / 2)" : ""
            if let m, let t {
                out.append(spec.primary == .movie ? Shelf(title: spec.series, mode: .variety, movie: nil, tv: t + votes)
                           : Shelf(title: spec.series, mode: .variety, movie: m + (spec.votes > 0 ? "&vote_count.gte=\(spec.votes)" : ""), tv: nil))
            }
            out.append(Shelf(title: "Critically Acclaimed \(spec.plural)", mode: .acclaimed, movie: m, tv: t))
        }
        let cur = curated(spec)
        let decades = cur.filter { $0.title.hasPrefix("’") && !era }
        let subs = cur.filter { !($0.title.hasPrefix("’") && !era) }
        out += subs.prefix(subsFirst)
        for name in services { if let s = onService(spec, name) { out.append(s) } }
        out += subs.dropFirst(subsFirst)
        out.append(Shelf(title: "Hidden \(spec.adj) Gems", mode: .gems, movie: m, tv: t))
        out += decades
        return out
    }

    static func onService(_ spec: GenreSpec, _ service: String, title: String? = nil) -> Shelf? {
        let ids = Discover.serviceIds(service)
        if ids.isEmpty { return nil }
        let on = "&with_watch_providers=\(ids)&watch_region=US&with_watch_monetization_types=flatrate"
        return Shelf(title: title ?? "\(spec.plural) on \(service)", mode: .variety,
                     movie: clause(spec, .movie).map { $0 + "\(on)&vote_count.gte=40" }, tv: clause(spec, .tv).map { $0 + "\(on)&vote_count.gte=20" })
    }

    static func ofType(_ spec: GenreSpec, _ type: MediaKind, title: String? = nil) -> Shelf? {
        guard let c = clause(spec, type) else { return nil }
        let votes = spec.votes > 0 ? "&vote_count.gte=\(type == .movie ? spec.votes : spec.votes / 2)" : ""
        return Shelf(title: title ?? spec.rowTitle(type), mode: .variety, movie: type == .movie ? c + votes : nil, tv: type == .tv ? c + votes : nil)
    }

    /// [s]'s titles, movies and shows interleaved (main type first).
    static func load(_ spec: GenreSpec, _ s: Shelf) async -> [Title] {
        let ts = types(spec)
        var lists = [[Title]](repeating: [], count: ts.count)
        let st = RowPaging.state
        await withTaskGroup(of: (Int, [Title]).self) { g in
            for (i, type) in ts.enumerated() {
                guard let q = s.query(type) else { continue }
                g.addTask { (i, await RowPaging.$state.withValue(st) { (try? await withTagged(spec, s, type, q)) ?? [] }) }
            }
            for await (i, l) in g { lists[i] = l }
        }
        return RowMix.interleave(lists)
    }

    private static func withTagged(_ spec: GenreSpec, _ s: Shelf, _ type: MediaKind, _ q: String) async throws -> [Title] {
        let tagged = q.replacingOccurrences(of: "&with_original_language=ja", with: "&with_keywords=210024")
        if !spec.anime || q.contains("with_keywords=") || tagged == q { return try await load(spec, s, type, q) }
        async let ja = load(spec, s, type, q)
        async let more = (try? await load(spec, s, type, tagged)) ?? []
        return RowMix.interleave([try await ja, await more])
    }

    private static func low(_ spec: GenreSpec, _ usual: Int) -> Int { spec.votes > 0 ? spec.votes : usual }

    private static func load(_ spec: GenreSpec, _ s: Shelf, _ type: MediaKind, _ q: String) async throws -> [Title] {
        let movie = type == .movie
        let d = RowMix.dateKey(type)
        let path = "/discover/\(type.rawValue)"
        func two(_ n: String) async throws -> [Title] {
            try await RowPaging.all([{ @Sendable in try await Tmdb.list(path, n, type: type) }, { @Sendable in try await Tmdb.list(path, "\(n)&page=2", type: type) }]).flatMap { $0 }
        }
        switch s.mode {
        case .trending:
            let g = movie ? spec.movie : spec.tv
            let votes = movie ? min(40, low(spec, 40)) : min(20, low(spec, 40) / 2)
            let r = try await RowPaging.all([
                { @Sendable in g <= 0 ? [] : try await Tmdb.list("/trending/\(type.rawValue)/week", type: type) },
                { @Sendable in g <= 0 ? [] : try await Tmdb.list("/trending/\(type.rawValue)/week", "&page=2", type: type) },
                { @Sendable in try await Tmdb.list(path, "\(base)\(q)&\(d).gte=\(RowMix.yearsAgo(2))&vote_count.gte=\(votes)", type: type) },
            ])
            let hot = (r[0] + r[1]).filter { $0.genreIds.contains(g) && (spec.lang.isEmpty || $0.lang == spec.lang) && RowMix.released($0) }
            var seen = Set<String>()
            return (hot + r[2]).filter { seen.insert($0.key).inserted }
        case .new:
            return try await two("\(base)\(q)&\(d).gte=\(RowMix.yearsAgo(1))&\(d).lte=\(RowMix.yearsAgo(0))&vote_count.gte=\(movie ? min(20, low(spec, 40) / 2) : min(10, low(spec, 40) / 4))")
        case .releases: return try await NewReleases.ofType(type, q)
        case .acclaimed: return try await RowMix.acclaimed(type, q, "\(s.title) \(type.rawValue)")
        case .gems:
            if spec.year.isEmpty { return try await RowMix.hiddenGems(type, q, "\(s.title) \(type.rawValue)") }
            return try await Tmdb.list(path, "\(base)\(q)&vote_average.gte=7&vote_count.gte=\(movie ? 150 : 60)&vote_count.lte=\(movie ? 1500 : 600)", type: type)
        case .variety: return try await RowMix.variety(type, base + q, "\(s.title) \(type.rawValue)")
        case .topVoted: return try await two("\(base)\(q)&sort_by=vote_count.desc")
        case .top: return try await two("\(base)\(q)&sort_by=vote_average.desc&vote_count.gte=\(movie ? 1000 : 250)")
        }
    }

    private static func decadeRows(_ spec: GenreSpec) -> [Pick] {
        guard spec.year.count > 0 else { return [] }
        let a = spec.adj
        return [both("\(a) Action", 28, 10759), both("\(a) Comedies", 35, 35), p("\(a) Horror", "&with_genres=27", "&with_keywords=315058|256183|12339"),
                p("\(a) Sci-Fi & Fantasy", "&with_genres=878|14", "&with_genres=10765"), both("\(a) Dramas", 18, 18),
                p("\(a) Thrillers", "&with_genres=53", "&with_keywords=316362|12565"), p("\(a) Romance", "&with_genres=10749", "&with_keywords=9840|386551"),
                both("\(a) Family Favorites", 10751, 10751),
                p("\(a) Animation", "&with_genres=16&without_keywords=210024", "&with_genres=16&without_keywords=210024"), both("\(a) Crime", 80, 80)]
    }

    private static let curatedMap: [String: [Pick]] = [
        "Action": [kw("Superhero Showdowns", "9715"), kw("Spies & Secret Agents", "470|5265"), kw("Martial Arts", "779|780"), kw("Heists", "10051"),
                   kw("Military & Special Forces", "15218|162365"), kw("Assassins & Hitmen", "782"), kw("Out for Revenge", "9748"),
                   both("Action Comedies", 35, 35), both("Sci-Fi Action", 878, 10765), kw("Disaster Movies", "10617", tv: false),
                   decade("Action", 1980), decade("Action", 1990)],
        "Adventure": [kw("Epic Quests", "207372"), kw("Treasure Hunts", "6956"), kw("Pirates & Swashbucklers", "12988|157186"), kw("Survival Adventures", "10349"),
                      kw("Jungles & Expeditions", "10787|1963|315709|5968|204975", votes: 80), kw("Dinosaurs", "12616"), kw("Road Trips", "7312", tv: false),
                      both("Space Adventures", 878, 10765), both("Fantasy Adventures", 14, 10765), both("Family Adventures", 10751, 10751),
                      both("Animated Adventures", 16, 0), decade("Adventure", 1980), decade("Adventure", 1990)],
        "Comedy": [p("Romantic Comedies", "&with_genres=10749", "&with_keywords=386551"), kw("Sitcoms", "193171", movie: false), kw("Stand-Up Comedy", "9716"),
                   kw("Dark Comedies", "10123"), kw("Satire & Parody", "8201|9755|11931"), kw("Teen Comedies", "155722|6270"),
                   kw("Buddy Comedies", "167541|167316", votes: 80), kw("Workplace Comedies", "210605|1438|6282", votes: 80), kw("Mockumentaries", "11800"),
                   both("Action Comedies", 28, 10759), both("Animated Comedies", 16, 16), decade("Comedy", 1980), decade("Comedy", 1990)],
        "Drama": [kw("Based on a True Story", "9672"), both("Crime Dramas", 80, 80), kw("Legal Dramas", "222517|33519"), kw("Medical Dramas", "208788|11612"),
                  kw("Period Dramas", "15060|192772"), kw("Coming of Age", "10683"), kw("Family Dramas", "12279"), kw("Political Dramas", "298528|6078"),
                  kw("Sports Dramas", "6075"), kw("Biopics", "5565|360939"), p("Romantic Dramas", "&with_genres=10749", "&with_keywords=9840"),
                  kw("Teen Dramas", "193400", movie: false), decade("Drama", 1980), decade("Drama", 1990)],
        "Horror": [kw("Supernatural Horror", "256183|9853"), kw("Slashers", "12339"), kw("Psychological Horror", "295907"), kw("Zombies", "12377"),
                   kw("Haunted Houses", "3358|162846"), kw("Possessions & Exorcisms", "9712|161261|2626|10375|241827", votes: 80),
                   both("Horror Comedies", 35, 35), kw("Found Footage", "163053"), kw("Creature Features", "158126|210614|11100|13031"),
                   kw("Vampires & Werewolves", "3133|12564"), kw("Folk Horror", "209568|155071", votes: 50), both("Sci-Fi Horror", 878, 10765),
                   decade("Horror", 1980), decade("Horror", 1990)],
        "Thriller": [kw("Psychological Thrillers", "12565"), p("Crime Thrillers", "&with_genres=80", "&with_genres=80"),
                     p("Mystery Thrillers", "&with_genres=9648", "&with_genres=9648"), kw("Spy Thrillers", "470|5265"),
                     kw("Conspiracy & Political Thrillers", "209817|298530|10410"), kw("Serial Killers", "10714"), kw("Survival Thrillers", "10349"),
                     kw("Kidnappings & Hostages", "1930"), kw("Legal Thrillers", "254459|222517"), kw("Heist Thrillers", "10051"),
                     both("Action Thrillers", 28, 10759), decade("Thriller", 1980), decade("Thriller", 1990)],
        "Sci-Fi": [kw("Space Exploration", "9882|252634|3801"), kw("Time Travel", "4379"), kw("Aliens & First Contact", "9951|14909|211248"),
                   kw("Dystopian Futures", "4565"), kw("AI & Robots", "310|14544"), kw("Post-Apocalyptic", "4458|10150|12332"), kw("Space Operas", "161176"),
                   kw("Cyberpunk", "12190"), kw("Superheroes", "9715"), p("Sci-Fi Thrillers", "&with_genres=53", "&with_keywords=316362|12565"),
                   both("Sci-Fi Action", 28, 10759), decade("Sci-Fi", 1980), decade("Sci-Fi", 1990)],
        "Fantasy": [kw("Epic Fantasy", "211227|375250|170362"), kw("Sword & Sorcery", "234213"), kw("Magic & Wizards", "2343|177912"),
                    kw("Dragons", "12554", votes: 80), kw("Fairy Tales", "3205"), kw("Dark Fantasy", "177895"), both("Fantasy Adventures", 12, 10759),
                    both("Fantasy Comedies", 35, 35), both("Animated Fantasy", 16, 0), decade("Fantasy", 1980), decade("Fantasy", 1990)],
        "Romance": [p("Romantic Comedies", "&with_genres=35", "&with_keywords=386551"), both("Romantic Dramas", 18, 18),
                    kw("Period Romance", "15060|192772", tv: false, votes: 80), kw("Teen Romance", "368947|157303"), kw("Love Triangles", "128"),
                    kw("Holiday Romance", "207317|65", tv: false), kw("Based on a Book", "818"), p("Romantic K-Dramas", nil, "&with_original_language=ko"),
                    kw("LGBTQ+ Love Stories", "158718"), kw("Weddings", "13027", tv: false), decade("Romance", 1980), decade("Romance", 1990)],
        "Crime": [both("Crime Dramas", 18, 18), p("Crime Thrillers", "&with_genres=53", "&with_keywords=316362|12565"), kw("Gangsters & the Mob", "10391|3149"),
                  kw("Detectives", "703"), kw("Police Procedurals", "268067"), kw("Heists", "10051"), kw("Serial Killers", "10714"), kw("Drug Cartels", "10175"),
                  kw("Behind Bars", "378"), both("True Crime", 99, 99), both("Crime Comedies", 35, 35), decade("Crime", 1980), decade("Crime", 1990)],
        "Mystery": [kw("Whodunits", "12570|207046"), kw("Detective Mysteries", "703"), p("Mystery Thrillers", "&with_genres=53", "&with_keywords=316362|12565"),
                    both("Crime Mysteries", 80, 80), kw("Supernatural Mysteries", "6152|9853"), kw("Small-Town Secrets", "1415"), kw("Missing Persons", "156091"),
                    p("British Mysteries", "&with_origin_country=GB", "&with_origin_country=GB"), both("Sci-Fi Mysteries", 878, 10765),
                    kw("Psychological Mysteries", "12565"), decade("Mystery", 1980), decade("Mystery", 1990)],
        "Animation": [p("Pixar & Disney Animation", "&with_companies=3|6125", nil), p("DreamWorks & Illumination", "&with_companies=521|6704", nil),
                      both("Animated Family Favorites", 10751, 10751), both("Animated Comedies", 35, 35), both("Animated Adventures", 12, 10759),
                      both("Animated Fantasy", 14, 10765), kw("Adult Animation", "161919"), kw("Animated Superheroes", "9715"), kw("Stop-Motion", "10121"),
                      kw("Animated Musicals", "4344"), decade("Animation", 1980), decade("Animation", 1990)],
        "Anime": [p("Anime Movies", "", nil), both("Action Anime", 28, 10759), both("Fantasy Anime", 14, 10765), both("Comedy Anime", 35, 35),
                  kw("Isekai", "237451"), kw("Shounen", "207826"), kw("Mecha", "10046"), kw("Supernatural Anime", "6152|9853|15001"),
                  kw("Slice of Life", "9914"), kw("Sports Anime", "6075"), p("Romance Anime", "&with_genres=10749", "&with_keywords=9840"),
                  decade("Anime", 1980), decade("Anime", 1990)],
        "Documentary": [both("True Crime", 80, 80), p("Music Documentaries", "&with_genres=10402", "&with_keywords=4048|10229|6029"),
                        kw("Nature & Wildlife", "18330|9902|18165"), p("History Documentaries", "&with_genres=36", "&with_keywords=192772|15060|1956"),
                        kw("Sports Documentaries", "6075"), kw("Biographical Documentaries", "5565"), kw("Science & Space", "287067|9882"),
                        kw("Food & Travel", "10637|1918"), kw("Politics & Power", "6078"), decade("Documentary", 1990)],
        "Family": [both("Animated Family Favorites", 16, 16), p("Live-Action Family Favorites", "&without_genres=16", "&without_genres=16"),
                   both("Family Comedies", 35, 35), both("Family Adventures", 12, 10759), both("Family Fantasy", 14, 10765),
                   kw("Holiday Favorites", "207317|65"), kw("Animal Friends", "267848|18165"), kw("Fairy Tales & Princesses", "3205"),
                   kw("Superheroes for Everyone", "9715"), kw("Family Sports Stories", "6075"), kw("Sing-Along Musicals", "4344"),
                   decade("Family", 1980), decade("Family", 1990)],
        "Kids": [p("Animated Movies for Kids", "", nil), both("Funny Cartoons", 35, 35), both("Kids' Adventures", 12, 10759),
                 kw("Animal Friends", "267848|18165"), kw("Princesses & Fairy Tales", "3205"), kw("Superheroes", "9715"), kw("Dinosaurs", "12616"),
                 kw("Sing-Along Musicals", "4344"), p("Live-Action Kids' Shows", nil, "&without_genres=16"), kw("Holiday Specials", "207317|65"),
                 decade("Kids", 1990)],
        "Reality": [kw("Competition Shows", "250845|271"), kw("Dating Shows", "13043"), kw("Cooking Competitions", "222083|1918"),
                    kw("Home Makeovers & Real Estate", "182804|179867|34130"), kw("Survival Shows", "227620|10349"), kw("Celebrity Reality", "6562")],
        "History": [kw("Biopics", "5565|360939"), kw("Period Dramas", "15060|192772"), kw("World War II Stories", "1956"), kw("Ancient Worlds", "5049|162861"),
                    kw("Kings & Queens", "9920"), kw("Medieval Times", "161257"), kw("Based on a True Story", "9672"), both("History Documentaries", 99, 99),
                    decade("History", 1980), decade("History", 1990)],
        "War": [kw("World War II", "1956"), kw("World War I", "2504"), kw("Vietnam War", "2957"), kw("Iraq & Afghanistan", "15087|7158"),
                kw("Based on a True Story", "9672"), both("Military Dramas", 18, 18), both("War Action", 28, 10759), both("War Documentaries", 99, 99),
                decade("War", 1980), decade("War", 1990)],
        "Western": [kw("Gunslingers & Outlaws", "75|9503"), both("Western Dramas", 18, 18), both("Western Action", 28, 10759), both("Western Comedies", 35, 35),
                    decade("Western", 1960), decade("Western", 1970), decade("Western", 1980), decade("Western", 1990)],
        "Music": [kw("Musicals", "4344"), kw("Music Biopics", "5565|360939"), kw("Concert Films", "156205|6029"),
                  p("Music Documentaries", "&with_genres=99", "&with_genres=99&with_keywords=4048|10229|6029"), kw("Dance Movies", "1691"),
                  kw("Hip-Hop", "898"), kw("Rock & Roll", "18001"), kw("Singers & Bands", "4048|10229"), both("Animated Musicals", 16, 16),
                  decade("Music", 1980), decade("Music", 1990)],
        "Halloween": [p("Halloween Horror", "&with_genres=27", "&with_genres=9648|10765&without_genres=10762,10751"), both("Family Halloween", 10751, 10751),
                      both("Halloween Comedies", 35, 35), both("Animated Halloween", 16, 16), decade("Halloween", 1980), decade("Halloween", 1990)],
        "Holiday": [both("Family Holiday Favorites", 10751, 10751), both("Holiday Comedies", 35, 35), p("Holiday Romance", "&with_genres=10749", nil),
                    both("Animated Holiday Specials", 16, 16), decade("Holiday", 1980), decade("Holiday", 1990)],
        "Adult Animation": [both("Animated Comedies", 35, 35), p("Animated Action & Adventure", "&with_genres=28|12", "&with_genres=10759"),
                            p("Animated Sci-Fi & Fantasy", "&with_genres=878|14", "&with_genres=10765"), p("Animated Superheroes", "&with_keywords=9715", nil)],
        "K-Drama": [p("Romantic K-Dramas", "&with_genres=10749", "&with_keywords=9840|386551"), p("K-Drama Thrillers", "&with_genres=53", "&with_keywords=316362|12565"),
                    both("Crime K-Dramas", 80, 80), p("Fantasy K-Dramas", "&with_genres=14", "&with_genres=10765"), both("K-Drama Comedies", 35, 35),
                    p("Historical K-Dramas", "&with_genres=36", "&with_keywords=192772|15060")],
        "True Crime": [kw("Serial Killers", "10714"), kw("Murder Cases", "9826|238812"), kw("Scams & Con Artists", "10453|11454|4373", votes: 20)],
        "Sitcoms": [p("Family Sitcoms", nil, "&with_genres=10751"), p("Animated Sitcoms", nil, "&with_genres=16"),
                    decade("Sitcom", 1980), decade("Sitcom", 1990), decade("Sitcom", 2000)],
        "Superheroes": [both("Animated Superheroes", 16, 16), both("Superhero Comedies", 35, 35), p("Superhero Sci-Fi", "&with_genres=878", "&with_genres=10765")],
        "Based on a True Story": [both("True Story Dramas", 18, 18), both("True Crime Stories", 80, 80),
                                  p("True War Stories", "&with_genres=10752", "&with_genres=10768"), p("History Makers", "&with_genres=36", nil)],
        "Feel-Good": [decade("Feel-Good", 1980), decade("Feel-Good", 1990)],
        "Mind-Bending": [p("Mind-Bending Sci-Fi", "&with_genres=878", "&with_genres=10765"), p("Mind-Bending Thrillers", "&with_genres=53", "&with_genres=9648"),
                         p("Mind-Bending Horror", "&with_genres=27", nil)],
        "Sports": [both("Sports Dramas", 18, 18), both("Sports Comedies", 35, 35), p("Sports Documentaries", "&with_genres=99", nil),
                   decade("Sports", 1980), decade("Sports", 1990)],
        "Teen": [both("Teen Comedies", 35, 35), both("Teen Dramas", 18, 18), p("Teen Romance", "&with_genres=10749", nil), p("Teen Horror", "&with_genres=27", nil),
                 decade("Teen", 1980), decade("Teen", 1990)],
        "Musicals": [both("Animated Musicals", 16, 16), both("Musical Comedies", 35, 35), p("Musical Romances", "&with_genres=10749", nil),
                     decade("Musicals", 1980), decade("Musicals", 1990)],
        "LGBTQ+": [both("LGBTQ+ Dramas", 18, 18), both("LGBTQ+ Comedies", 35, 35), p("LGBTQ+ Love Stories", "&with_genres=10749", nil)],
    ]
}
