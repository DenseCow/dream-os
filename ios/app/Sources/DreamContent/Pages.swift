import Foundation
import DreamCore

/// One row's recipe: placeholder, how it fills, and (deep rows) how it pages on.
struct Slot: Sendable {
    var row: CatalogRow
    /// Raw titles (claims applied inside); nil/short = row left out. Used again for later pages of a deep row.
    var titles: (@Sendable () async throws -> [Title]?)?
    /// A row built as a whole (Continue Watching, New Episodes for You, hub rows).
    var custom: (@Sendable () async throws -> CatalogRow?)?
    /// Per title: kicker, subtitle, badge.
    var decorate: @Sendable (Title) -> (kicker: String?, subtitle: String?, badge: String?) = { _ in (nil, nil, nil) }
    /// Rows past their first page.
    var deep = false
    /// Cut to this many items when it fills.
    var limit: Int?
}

/// One open page: its claims and its rows (a fresh one each time `rowSlots(for:)` is asked).
final class PageSession: @unchecked Sendable {
    let page: CatalogPage
    let claims: RowClaims
    let ctx: ProfileContext
    private let lock = NSLock()
    private var slots: [String: Slot] = [:]
    private var shown: [String: Set<String>] = [:]
    private var nextPage: [String: Int] = [:]
    var order: [String] = []

    init(page: CatalogPage, ctx: ProfileContext, anime: Bool) {
        self.page = page; self.ctx = ctx; claims = RowClaims(ctx: ctx, anime: anime)
    }

    func add(_ s: Slot) { lock.withLock { var s = s; if slots[s.row.id] != nil { s.row.id += "#\(order.count)" }; slots[s.row.id] = s; order.append(s.row.id) } }
    func slot(_ id: String) -> Slot? { lock.withLock { slots[id] } }
    func remember(_ id: String, _ keys: [String]) { lock.withLock { shown[id, default: []].formUnion(keys); if nextPage[id] == nil { nextPage[id] = 2 } } }
    func paging(_ id: String) -> (Int, Set<String>) { lock.withLock { (nextPage[id] ?? 2, shown[id] ?? []) } }
    func paged(_ id: String, next: Int) { lock.withLock { nextPage[id] = next } }
    var rows: [CatalogRow] { lock.withLock { order.compactMap { slots[$0]?.row } } }
}

/// A chart as first claimed on a page, shared by every row that asks after it.
final class ChartMemo: @unchecked Sendable {
    private let lock = NSLock()
    private var tasks: [String: Task<[Title], Never>] = [:]

    /// The kept answer; else [make]'s, kept only when [store] (a page-1 fill: look-ahead and deep pages claim nothing).
    func value(_ key: String, store: Bool, _ make: @escaping @Sendable () async -> [Title]) async -> [Title] {
        if let t = lock.withLock({ tasks[key] }) { return await t.value }
        if !store { return await make() }
        let t = lock.withLock { () -> Task<[Title], Never> in
            if let t = tasks[key] { return t }
            let t = Task { await make() }
            tasks[key] = t
            return t
        }
        return await t.value
    }
}

/// The rows every page shows, in order (CinemaPages.kt / CinemaBrowse.kt / SeasonHub.kt row lists).
enum Pages {
    static let minRow = 6
    static let maxServices = 6

    static func titleRow(_ name: String, _ style: RowStyle = .landscape, deep: Bool = true, limit: Int? = nil,
                         decorate: @escaping @Sendable (Title) -> (kicker: String?, subtitle: String?, badge: String?) = { _ in (nil, nil, nil) },
                         _ load: @escaping @Sendable () async throws -> [Title]?) -> Slot {
        Slot(row: CatalogRow(id: name, name: name, style: style, items: []), titles: load, decorate: decorate, deep: deep && style != .top10, limit: limit)
    }

    static func spotlight(_ kick: String, _ load: @escaping @Sendable () async throws -> [Title]?, pick: @escaping @Sendable ([Title]) -> Title? = { $0.first }) -> Slot {
        Slot(row: CatalogRow(id: "spotlight:\(kick)", name: "", style: .big, items: [], isSpotlight: true),
             titles: { guard let l = try await load()?.filter({ $0.backdropPath != nil }), let t = pick(l) else { return nil }; return [t] },
             decorate: { _ in (kick, nil, nil) }, deep: false)
    }

    static let comingSub: @Sendable (Title) -> (kicker: String?, subtitle: String?, badge: String?) = { t in
        (nil, [DreamTime.shortDate(t.date).isEmpty ? nil : DreamTime.shortDate(t.date), t.kind == .tv ? "Series" : "Movie"].compactMap { $0 }.joined(separator: " · "), nil)
    }

    static func withArt(_ l: [Title]) -> [Title] { l.filter { $0.backdropPath != nil || $0.posterPath != nil } }

    /// Top 10 Today: TMDB's daily trending (two pages, 40 titles) filtered (poster, adult, anime, kids), then the
    /// first 10 in TMDB's order, so a filtered title is always refilled (it showed 9 before, owner 2026-10-28).
    static func top10(_ c: RowClaims, _ kind: MediaKind?) async -> [Title] {
        let path = "/trending/\(kind?.rawValue ?? "all")/day"
        async let p1 = (try? await Tmdb.list(path, type: kind)) ?? []
        async let p2 = (try? await Tmdb.list(path, "&page=2", type: kind)) ?? []
        var seen = Set<String>()
        // Only what can be watched today: TMDB's chart lists films months ahead of release (Dune: Part Three in
        // October for December); those belong in Coming Soon. The chart's order is otherwise kept.
        let pool = (await p1 + p2).filter { $0.posterPath != nil && !$0.adult && RowMix.released($0) && seen.insert($0.key).inserted }
        return c.claim("Top 10 Today", pool, 10)
    }

    // MARK: Home (CinemaHome.build)

    static func home(_ s: PageSession) async {
        let c = s.claims
        let ctx = s.ctx
        let charts = ChartMemo()
        // Top 10 and Trending Now are claimed once by the first row's fill and that answer is reused: each ask was two
        // charts tidied and ranked again, and a deep row's later page fetched other chart pages to claim nothing.
        @Sendable func top10() async -> [Title] {
            await charts.value("Top 10 Today", store: RowPaging.state == nil) { await Pages.top10(c, nil) }
        }
        // Trending claims right after Top 10 (the TV's order); the three charts are fetched side by side, and every
        // row that waits on them starts its own fetch at the same time (speed pass: no round trips in series).
        @Sendable func trending() async -> [Title] {
            // Trending Now's own later pages are fetched as usual.
            if RowPaging.state != nil && !RowPaging.foreign("Trending Now") { return await trendingList() }
            return await charts.value("Trending Now", store: RowPaging.state == nil) { await trendingList() }
        }
        @Sendable func trendingList() async -> [Title] {
            async let a = (try? await Tmdb.trending()) ?? []
            async let b = (try? await Tmdb.list("/trending/all/week", "&page=2")) ?? []
            _ = await top10()
            return c.take("Trending Now", (await a + b).filter(RowMix.released))
        }
        s.add(Slot(row: CatalogRow(id: "Continue Watching", name: "Continue Watching", style: .continueWatching, items: []),
                   custom: { await continueRow(nil) }))
        s.add(Slot(row: CatalogRow(id: "Top Picks for You", name: "Top Picks for You", style: .big, items: []), custom: {
            async let picks = HomePicks.topPicks(ctx)
            async let day1 = (try? await Tmdb.list("/trending/all/day")) ?? []
            async let day2 = (try? await Tmdb.list("/trending/all/day", "&page=2")) ?? []
            _ = await trending()
            var name = "Top Picks for You"
            var list = c.take("Top Picks for You", await picks)
            if list.count < HomePicks.minPicks {
                // Popular Today: only what's out and no anime outside its sections (the row's personal name lets
                // anime through `take`). Two pages: Top 10 and Trending Now claim most of the first.
                name = "Popular Today"
                list = c.take("Top Picks for You", (await day1 + day2).filter { $0.backdropPath != nil && RowMix.released($0) && !RowMix.animeHidden($0) }, 12)
            }
            if list.isEmpty { return nil }
            let items = await items(Array(list.prefix(12))) { t in (bigKicker(t), nil, nil) }
            return CatalogRow(id: "Top Picks for You", name: name, style: .big, items: items)
        }))
        let today = Seasons.today()
        let hub = Seasons.active(kids: ctx.kids, today)
        s.add(seasonRow(hub, today, c))
        s.add(Slot(row: CatalogRow(id: "New Episodes for You", name: "New Episodes for You", style: .continueWatching, items: []), custom: { await newEpisodesRow() }))
        s.add(titleRow("New Releases") {
            async let m = NewReleases.mixed()
            _ = await trending(); let l = c.take("New Releases", await m); return l.count >= minRow ? l : nil
        })
        s.add(titleRow("New Episodes", decorate: NewReleases.episodeSub) { let l = c.take("New Episodes", try await NewReleases.returning()); return l.count >= minRow ? l : nil })
        s.add(titleRow("In Theaters") {
            async let th = NewReleases.inTheaters()
            _ = await trending(); return c.takeAtLeast("In Theaters", withArt(try await th), min: minRow)
        })
        s.add(titleRow("Trending Now", limit: 16) { await trending() })
        s.add(titleRow("Top 10 Today", .top10) { let l = await top10(); return l.count >= minRow ? l : nil })
        s.add(spotlight("Spotlight") {
            async let p3 = (try? await Tmdb.list("/trending/all/week", "&page=3")) ?? []
            async let p4 = (try? await Tmdb.list("/trending/all/week", "&page=4")) ?? []
            let taken = Set((await trending()).map(\.key) + (await top10()).map(\.key))
            let p2 = (try? await Tmdb.list("/trending/all/week", "&page=2")) ?? []
            for l in [p2, await p3, await p4] {
                let ok = l.filter { !taken.contains($0.key) && $0.votes >= 300 && $0.backdropPath != nil && !RowMix.animeHidden($0) && RowMix.released($0) }.sorted { $0.rating > $1.rating }
                if let f = ok.first { return [f] }
            }
            return []
        })
        s.add(Slot(row: CatalogRow(id: "My List", name: "My List", style: .landscape, items: []), custom: {
            let l = Array(await Services.shared.myList.items().prefix(20))
            return l.isEmpty ? nil : CatalogRow(id: "My List", name: "My List", style: .landscape, items: await items(l))
        }))
        let seeds = (await BecauseYouWatched.pick(shows: false) + BecauseYouWatched.pick(shows: true)).sorted { $0.updatedAt > $1.updatedAt }.prefix(2).map { $0 }
        func because(_ e: HistoryEntry) -> Slot {
            let name = "Because You Watched \(e.title.name)"
            return titleRow(name) {
                async let recs = RowMix.recs(ctx, e.title.kind, e.title.id, seedYear: Int(e.title.year))
                _ = await trending()
                let skip = Set(await Services.shared.history.recent().map(\.title.key) + Services.shared.myList.items().map(\.key))
                let l = c.take(name, await recs.filter { !skip.contains($0.key) })
                return l.count >= minRow ? l : nil
            }
        }
        if let e = seeds.first { s.add(because(e)) }
        // Rows for this profile's taste (Taste.forYou): "Dark Crime Stories", "Edge-of-Your-Seat Thrillers"...; the
        // categories it finishes most, with a nod to the time of day. None for a new profile.
        for (name, row) in Taste.forYou(ctx.taste, kids: ctx.kids, moment: HomePicks.moment().genres) {
            guard let spec = GenreSpec.find(name) else { continue }
            s.add(titleRow(row) {
                let shelf = Shelf(title: row, mode: .variety, movie: GenreShelves.clause(spec, .movie), tv: GenreShelves.clause(spec, .tv))
                async let l = GenreShelves.load(spec, shelf)
                _ = await trending()
                let got = c.take(row, withArt(await l))
                return got.count >= minRow ? got : nil
            })
        }
        let services = HomePicks.homeServices.prefix(maxServices)
        for (i, name) in services.enumerated() {
            let title = "Popular on \(name)"
            let pid = Tmdb.services.first { $0.name == name }?.providerId ?? 0
            s.add(titleRow(title) {
                async let tv = (try? await Tmdb.onService(pid, .tv)) ?? []
                async let mv = (try? await Tmdb.onService(pid, .movie)) ?? []
                _ = await trending()
                // Shows and movies interleaved in TMDB's popularity order; `take` ranks them (steadied quality and
                // taste), so a 131-vote 8.3 no longer leads over Breaking Bad as a plain rating sort let it.
                let l = c.take(title, RowMix.interleave([await tv, await mv]), 16)
                return l.count >= minRow ? l : nil
            })
            switch i {
            case 1:
                s.add(titleRow("Coming Soon", decorate: comingSub) {
                    async let mv = (try? await Discover.list(DFilters(type: .movie, sort: .comingSoon))) ?? []
                    async let tv = (try? await Discover.list(DFilters(type: .tv, sort: .comingSoon))) ?? []
                    let soon = await mv + tv
                    let l = c.take("Coming Soon", soon.filter { !$0.date.isEmpty && $0.backdropPath != nil }, 16).sorted { $0.date < $1.date }
                    return l.count >= minRow ? l : nil
                })
            case 2:
                s.add(spotlight("Coming Soon", {
                    Array(((try? await Discover.list(DFilters(type: .movie, sort: .comingSoon))) ?? []).filter { !$0.date.isEmpty && !RowMix.animeHidden($0) }.dropFirst(16).prefix(8))
                }, pick: { $0.max { $0.votes < $1.votes } }))
            case 3: if seeds.count > 1 { s.add(because(seeds[1])) }
            default: break
            }
        }
    }

    /// A big card's kicker: new, acclaimed, else its genre.
    static func bigKicker(_ t: Title) -> String {
        let recent = !t.date.isEmpty && t.date >= DreamTime.ymdPlus(-60) && t.date <= DreamTime.ymdPlus(0)
        if recent { return t.kind == .tv ? "New Series" : "New Movie" }
        if t.rating >= 7.8 && t.votes >= 200 { return "Critically Acclaimed" }
        return t.genreIds.lazy.compactMap { Discover.genreName(t.kind, $0) }.first ?? (t.kind == .tv ? "Series" : "Movie")
    }

    static func continueRow(_ kind: MediaKind?) async -> CatalogRow? {
        let list = await Services.shared.history.continueWatching().filter { kind == nil || $0.title.kind == kind }.prefix(15)
        if list.isEmpty { return nil }
        let items = list.map { e in
            CatalogItem(title: e.title, episode: e.season.map { Episode(showId: e.title.id, season: $0, number: e.episode ?? 1, name: e.episodeName ?? "", stillPath: e.stillPath) },
                        progress: e.upNext ? 0 : e.progress, subtitle: e.season.map { "S\($0)  ·  E\(e.episode ?? 1)" + (e.upNext ? "  ·  Up Next" : "") })
        }
        return CatalogRow(id: "Continue Watching", name: "Continue Watching", style: .continueWatching, items: items)
    }

    static func newEpisodesRow() async -> CatalogRow? {
        let snap = await NewEpisodes.shared.load()
        if snap.recent.isEmpty && snap.upcoming.isEmpty { return nil }
        var out: [CatalogItem] = []
        for i in snap.recent + snap.upcoming.prefix(10) {
            let ep = Episode(showId: i.show.id, season: i.season, number: i.number, name: i.name, overview: i.overview, stillPath: i.still,
                             airDate: i.airDate, airsAt: i.at, upcoming: i.upcoming)
            let avail = i.upcoming || AvailabilityChecker.recentEpisode(at: i.at, date: i.airDate)
                ? await AvailabilityChecker.shared.episode(ep, show: i.show)?.state : nil
            let isNew = !i.upcoming ? await NewEpisodes.shared.isNew(i.key) : false
            out.append(CatalogItem(title: i.show, episode: ep, availability: avail, subtitle: i.badge(), badge: isNew ? "NEW" : nil))
        }
        return CatalogRow(id: "New Episodes for You", name: snap.recent.isEmpty ? "Coming Up" : "New Episodes for You", style: .continueWatching, items: out)
    }

    // MARK: Movies / TV Shows (CinemaCategory)

    static let movieRows: [Int: String] = [28: "Action Movies", 12: "Adventure Movies", 16: "Animated Movies", 35: "Comedies", 80: "Crime Movies",
        99: "Documentaries", 18: "Dramas", 10751: "Family Movies", 14: "Fantasy Movies", 36: "Historical Movies", 27: "Horror Movies",
        10402: "Music & Musicals", 9648: "Mysteries", 10749: "Romantic Movies", 878: "Sci-Fi Movies", 53: "Thrillers", 10752: "War Movies", 37: "Westerns"]
    static let tvRows: [Int: String] = [10759: "Action & Adventure Series", 16: "Animated Series", 35: "Comedy Series", 80: "Crime Series",
        99: "Docuseries", 18: "Drama Series", 10751: "Family Series", 10762: "Shows for Kids", 9648: "Mystery Series", 10764: "Reality TV",
        10765: "Sci-Fi & Fantasy Series", 10766: "Soap Operas", 10767: "Talk Shows", 10768: "War & Politics Series", 37: "Western Series"]
    static let movieChips: [(String, Int)] = [("Action", 28), ("Comedy", 35), ("Drama", 18), ("Sci-Fi", 878), ("Thriller", 53), ("Horror", 27),
        ("Animation", 16), ("Family", 10751), ("Romance", 10749), ("Crime", 80), ("Documentary", 99), ("Anime", Discover.anime),
        ("Adult Animation", Discover.adultAnimationMovie), ("Superheroes", Discover.superheroMovie), ("Stand-Up", Discover.standUp), ("True Stories", Discover.trueStory)]
    static let tvChips: [(String, Int)] = [("Drama", 18), ("Comedy", 35), ("Crime", 80), ("Sci-Fi & Fantasy", 10765), ("Action & Adventure", 10759),
        ("Mystery", 9648), ("Animation", 16), ("Reality", 10764), ("Documentary", 99), ("Kids", 10762), ("Anime", Discover.anime),
        ("Adult Animation", Discover.adultAnimationTv), ("Sitcoms", Discover.sitcom), ("True Crime", Discover.trueCrime), ("Superheroes", Discover.superheroTv)]
    static let earlyThemes = ["Adult Animation", "K-Drama", "True Crime", "Sitcoms", "Superheroes", "Based on a True Story", "Stand-Up Comedy", "Feel-Good"]

    static func category(_ s: PageSession, _ kind: MediaKind) async {
        let c = s.claims
        let ctx = s.ctx
        let movie = kind == .movie
        let label = movie ? "Movies" : "TV Shows"
        func titles(_ name: String, _ style: RowStyle = .landscape, decorate: @escaping @Sendable (Title) -> (kicker: String?, subtitle: String?, badge: String?) = { _ in (nil, nil, nil) },
                    _ load: @escaping @Sendable () async throws -> [Title]) -> Slot {
            titleRow(name, style, decorate: decorate) { let l = c.take(name, withArt(try await load())); return l.count >= minRow ? l : nil }
        }
        s.add(Slot(row: CatalogRow(id: "Continue Watching", name: "Continue Watching", style: .continueWatching, items: []), custom: { await continueRow(kind) }))
        let seeds = await BecauseYouWatched.pick(shows: !movie)
        let skip = Set(await Services.shared.history.recent().map(\.title.key) + Services.shared.myList.items().map(\.key))
        var seedIt = seeds.makeIterator()
        func because() {
            guard let e = seedIt.next() else { return }
            let name = "Because You Watched \(e.title.name)"
            s.add(titleRow(name) {
                async let w = (try? await Tmdb.list("/trending/\(kind.rawValue)/week", type: kind)) ?? []
                async let d = (try? await Tmdb.list("/trending/\(kind.rawValue)/day", type: kind)) ?? []
                async let r = RowMix.recs(ctx, kind, e.title.id, seedYear: Int(e.title.year))
                let near = Set((await w).prefix(20).map(\.key) + (await d).prefix(10).map(\.key))
                let l = c.take(name, withArt(await r).filter { !skip.contains($0.key) && !near.contains($0.key) })
                return l.count >= minRow ? l : nil
            })
        }
        s.add(titles("Trending This Week", .big, decorate: { t in (t.genreIds.lazy.compactMap { Discover.genreName(kind, $0) }.first ?? label, nil, nil) }) {
            try await Tmdb.list("/trending/\(kind.rawValue)/week", type: kind).filter(RowMix.released)
        })
        because()
        s.add(titleRow("Top 10 Today", .top10) {
            let l = await top10(c, kind)
            return l.count >= minRow ? l : nil
        })
        s.add(titles("New Releases") { try await NewReleases.ofType(kind) })
        if !movie { s.add(titles("New Episodes", decorate: NewReleases.episodeSub) { try await NewReleases.returning() }) }
        if movie { s.add(titleRow("In Theaters") { c.takeAtLeast("In Theaters", withArt(try await NewReleases.inTheaters()), min: minRow) }) }
        because()
        s.add(titleRow("Coming Soon", decorate: comingSub) {
            let l = c.take("Coming Soon", try await Discover.list(DFilters(type: kind, sort: .comingSoon)).filter { !$0.date.isEmpty }).sorted { $0.date < $1.date }
            return l.count >= minRow ? l : nil
        })
        s.add(titles("Critically Acclaimed") { try await RowMix.acclaimed(kind, "", "Critically Acclaimed \(kind.rawValue)") })
        because()
        s.add(titles("Hidden Gems") { try await RowMix.hiddenGems(kind, "", "Hidden Gems \(kind.rawValue)") })
        if !movie {
            // Well-loved series with seasons to sink into: 7.8+ from 1,000+ people, from the last twelve years.
            s.add(titles("Bingeworthy Shows") {
                let q = "&include_adult=false&sort_by=popularity.desc&vote_count.gte=1000&vote_average.gte=7.8&first_air_date.gte=\(RowMix.yearsAgo(12))" +
                    "&without_genres=10763,10764,10766,10767,10762"
                async let a = Tmdb.list("/discover/tv", q, type: .tv); async let b = Tmdb.list("/discover/tv", "\(q)&page=2", type: .tv)
                return try await a + b
            })
        }
        let inSeason = GenreSpec.categories.filter { $0.inSeason() }
        let seasonal = inSeason.filter { $0.name == "Halloween" || $0.name == "Holiday" }
        let themes = inSeason.filter { !($0.name == "Halloween" || $0.name == "Holiday") }
        func categoryRow(_ g: GenreSpec) {
            guard let shelf = GenreShelves.ofType(g, kind) else { return }
            s.add(titles(shelf.title) { await GenreShelves.load(g, shelf) })
        }
        for g in seasonal { categoryRow(g) }
        var seenSvc = Set<String>()
        for name in HomePicks.homeServices where seenSvc.insert(name).inserted {
            s.add(titles("Popular on \(name)") { try await Discover.list(DFilters(type: kind, service: name), extra: RowMix.recent(kind)) })
        }
        let names = movie ? movieRows : tvRows
        let early = themes.filter { earlyThemes.contains($0.name) }.sorted { earlyThemes.firstIndex(of: $0.name)! < earlyThemes.firstIndex(of: $1.name)! }
        // The genre rows in the order this profile would want them (loved genres first, untouched ones last; ties keep
        // TMDB's order). A new profile sees the plain order.
        let plain = Discover.genres(kind)
        let taste = ctx.taste
        let genres = taste.strength >= 0.25
            ? plain.enumerated().sorted { a, b in
                let x = taste.genre[a.element.0] ?? 0, y = taste.genre[b.element.0] ?? 0
                return x != y ? x > y : a.offset < b.offset
            }.map(\.element)
            : plain
        for (i, g) in genres.enumerated() {
            let title = names[g.0] ?? g.1
            s.add(titles(title) { try await RowMix.variety(kind, Discover.query(DFilters(type: kind, genre: g.0)), title) })
            if i % 2 == 1, i / 2 < early.count { categoryRow(early[i / 2]) }
        }
        let anime = movie ? "Anime Movies" : "Anime Series"
        s.add(titles(anime) { try await RowMix.variety(kind, Discover.query(DFilters(type: kind, genre: 16, lang: "ja")), anime) })
        let usedEarly = Set(early.prefix(genres.count / 2).map(\.name))
        for g in themes where !usedEarly.contains(g.name) { categoryRow(g) }
        s.add(titles("’70s Classics") { try await Discover.list(DFilters(type: kind, year: "1970s")) })
        s.add(titles("’80s Classics") { try await Discover.list(DFilters(type: kind, year: "1980s")) })
        s.add(titles("’90s Favorites") { try await Discover.list(DFilters(type: kind, year: "1990s")) })
        s.add(titles("2000s Hits") { try await Discover.list(DFilters(type: kind, year: "2000s")) })
    }

    /// A Movies / TV Shows chip's rows (CinemaCategory.genre).
    static func chip(_ s: PageSession, _ kind: MediaKind, _ name: String, _ id: Int) {
        let c = s.claims
        func titles(_ n: String, _ style: RowStyle = .landscape, decorate: @escaping @Sendable (Title) -> (kicker: String?, subtitle: String?, badge: String?) = { _ in (nil, nil, nil) },
                    _ load: @escaping @Sendable () async throws -> [Title]) -> Slot {
            titleRow(n, style, decorate: decorate) { let l = c.take(n, withArt(try await load())); return l.count >= minRow ? l : nil }
        }
        let clause = Discover.clause(kind, id) ?? ""
        s.add(titles("Popular in \(name)", .big, decorate: { t in (t.genreIds.lazy.compactMap { Discover.genreName(kind, $0) }.first ?? (kind == .movie ? "Movies" : "TV Shows"), nil, nil) }) {
            try await Discover.list(DFilters(type: kind, genre: id, sort: .popular))
        })
        s.add(titles("New Releases") { try await NewReleases.ofType(kind, clause) })
        if kind == .tv { s.add(titles("New Episodes", decorate: NewReleases.episodeSub) { try await NewReleases.returning(clause) }) }
        s.add(titles("Top Rated") { try await Discover.list(DFilters(type: kind, genre: id, sort: .topRated)) })
        s.add(titles("Fan Favorites") { try await RowMix.variety(kind, Discover.query(DFilters(type: kind, genre: id)), "\(name) \(kind.rawValue) favorites") })
        s.add(titles("Hidden Gems") { try await RowMix.hiddenGems(kind, clause, "Hidden Gems \(name) \(kind.rawValue)") })
        s.add(titles("2010s Hits") { try await Discover.list(DFilters(type: kind, genre: id, year: "2010s")) })
        s.add(titles("2000s Hits") { try await Discover.list(DFilters(type: kind, genre: id, year: "2000s")) })
        s.add(titles("’90s Classics") { try await Discover.list(DFilters(type: kind, genre: id, year: "1990s")) })
    }

    // MARK: Genre page (CinemaGenre)

    static func genreMixed(_ spec: GenreSpec, rating: Int = 0, sort: DiscoverSort = .mixed) async -> [Title] {
        let types = GenreShelves.types(spec)
        var lists = [[Title]](repeating: [], count: types.count)
        // Side by side (speed pass), kept in the types' order.
        await withTaskGroup(of: (Int, [Title]).self) { g in
            for (i, type) in types.enumerated() {
                let f = DFilters(type: type, genre: type == .movie ? spec.movie : spec.tv, year: spec.year, rating: rating, lang: spec.lang, sort: sort)
                g.addTask { (i, (try? await Discover.list(f)) ?? []) }
            }
            for await (i, l) in g { lists[i] = l }
        }
        return RowMix.interleave(lists).filter { spec.anime || !RowMix.animeHidden($0) }
    }

    static func genre(_ s: PageSession, _ spec: GenreSpec) {
        let c = s.claims
        let today = Seasons.today()
        for (i, shelf) in GenreShelves.page(spec, services: Array(HomePicks.homeServices.prefix(maxServices))).enumerated() {
            s.add(titleRow(shelf.title, i == 0 ? .big : .landscape, decorate: { _ in (i == 0 ? spec.name : nil, nil, nil) }) {
                let l = c.take(shelf.title, withArt(await GenreShelves.load(spec, shelf))); return l.count >= minRow ? l : nil
            })
            // No in-row Spotlight card on Browse pages (owner, 2026-10-28): the hero leads the page.
            if i == 0, let hub = Seasons.forGenre(spec.name, kids: s.ctx.kids, today) { s.add(seasonRow(hub, today, c)) }
        }
    }

    // MARK: Service page (CinemaService)

    static let serviceOrder = ["Halloween", "Holiday", "Comedy", "Drama", "Action", "Sci-Fi", "Crime", "Thriller", "Horror", "Anime",
        "Adult Animation", "True Crime", "K-Drama", "Mystery", "Romance", "Sitcoms", "Superheroes", "Animation", "Documentary",
        "Reality", "Stand-Up Comedy", "Based on a True Story", "Feel-Good", "Mind-Bending", "Family", "Kids", "Sports", "Teen",
        "Fantasy", "Adventure", "Musicals", "LGBTQ+", "’80s Classics", "’90s Classics"]

    static func serviceMixed(_ name: String, extra: String = "", rating: Int = 0, sort: DiscoverSort = .mixed) async -> [Title] {
        async let tv = (try? await Discover.list(DFilters(type: .tv, rating: rating, service: name, sort: sort), extra: extra)) ?? []
        async let mv = (try? await Discover.list(DFilters(type: .movie, rating: rating, service: name, sort: sort), extra: extra)) ?? []
        return RowMix.interleave([await tv, await mv])
    }

    static func service(_ s: PageSession, _ spec: ServiceSpec) {
        let c = s.claims
        let name = spec.name
        func titles(_ n: String, _ style: RowStyle = .landscape, min: Int = minRow, _ load: @escaping @Sendable () async throws -> [Title]) -> Slot {
            titleRow(n, style, decorate: { _ in (style == .big ? name : nil, nil, nil) }) { let l = c.take(n, withArt(try await load())); return l.count >= min ? l : nil }
        }
        s.add(titles("Popular on \(name)", .big) { async let a = serviceMixed(name); async let b = serviceMixed(name, extra: "&page=2"); return await a + b })
        if !spec.networkIds.isEmpty {
            let nets = spec.networkIds.map(String.init).joined(separator: "|")
            s.add(titles("\(name) Originals") {
                let q = "&include_adult=false&sort_by=popularity.desc&with_networks=\(nets)"
                async let a = Tmdb.list("/discover/tv", q, type: .tv); async let b = Tmdb.list("/discover/tv", "\(q)&page=2", type: .tv)
                return try await a + b
            })
        }
        s.add(titles("New Releases") { await NewReleases.onService(name) })
        s.add(titles("TV Shows") { try await RowMix.variety(.tv, Discover.query(DFilters(type: .tv, service: name)), "\(name) TV Shows") })
        s.add(titles("Movies") { try await RowMix.variety(.movie, Discover.query(DFilters(type: .movie, service: name)), "\(name) Movies") })
        s.add(titles("Critically Acclaimed") { await serviceMixed(name, extra: "&page=\(RowMix.dailyPage("Critically Acclaimed \(name)", 3))", sort: .topRated) })
        s.add(titles("Hidden Gems") {
            let extra = "&with_watch_providers=\(Discover.serviceIds(name))&watch_region=US&with_watch_monetization_types=flatrate"
            async let tv = RowMix.hiddenGems(.tv, extra, "Hidden Gems \(name)"); async let mv = RowMix.hiddenGems(.movie, extra, "Hidden Gems \(name)")
            return RowMix.interleave([try await tv, try await mv])
        })
        let all = GenreSpec.all()
        for n in serviceOrder {
            guard let g = all.first(where: { $0.name == n }), let shelf = GenreShelves.onService(g, name) else { continue }
            s.add(titles(shelf.title, min: 8) { await GenreShelves.load(g, shelf) })
        }
    }

    // MARK: Seasonal hubs (SeasonHub.kt)

    static func seasonRow(_ hub: SeasonalHub, _ today: SDay, _ c: RowClaims) -> Slot {
        Slot(row: CatalogRow(id: "hub:\(hub.id)", name: hub.title, style: .landscape, items: [], hubs: [Seasons.tile(hub, today)]), custom: {
            let kids = c.ctx.kids
            let (pick, lead) = await Seasons.homeTitles(hub, kids: kids, today, c.ctx)
            let rest = c.take(hub.title, lead.filter { $0.key != pick?.key })
            let list = (pick.map { [$0] } ?? []) + rest
            var items = await items(list)
            if let p = pick, let i = items.firstIndex(where: { $0.title.key == p.key }) { items[i].badge = "TONIGHT'S PICK" }
            return CatalogRow(id: "hub:\(hub.id)", name: hub.title, style: .landscape, items: items, hubs: [Seasons.tile(hub, today)])
        }, deep: false)
    }

    static func season(_ s: PageSession, _ id: String) async {
        let c = s.claims
        let today = Seasons.today()
        let kids = s.ctx.kids
        // The slots below are kept by `s`: they capture its context, never `s` itself (that was a cycle).
        let ctx = s.ctx
        guard let hub = SeasonCalendar.find(id) else { return }
        let kick = ["Tonight's Pick", hub.night(today)].compactMap { $0 }.joined(separator: "  ·  ")
        s.add(spotlight(kick) { (await Seasons.pick(hub, kids: kids, today, ctx)).map { c.claim("Tonight's Pick", [$0]) } })
        for (i, r) in hub.rowsFor(kids: kids).enumerated() {
            let title = SeasonCalendar.fill(r.title, hub, today)
            s.add(titleRow(title, i == 0 ? .big : .landscape, decorate: { _ in (i == 0 ? hub.kicker : nil, nil, nil) }) {
                let l = c.take(title, withArt(await Seasons.load(hub, r, kids: kids, today, ctx))); return l.count >= minRow ? l : nil
            })
        }
        let others = Seasons.all(kids: kids, today).filter { $0.id != hub.id }
        if !others.isEmpty {
            let tiles = others.map { Seasons.tile($0, today) }
            s.add(Slot(row: CatalogRow(id: "More to Celebrate", name: "More to Celebrate", style: .landscape, items: [], hubs: tiles),
                       custom: { CatalogRow(id: "More to Celebrate", name: "More to Celebrate", style: .landscape, items: [], hubs: tiles) }))
        }
    }

    // MARK: items

    /// Cards for [list]: availability from what's known now (unreleased: its date; else the cache). Cards ask
    /// `availability(for:)` when they show for a fresh answer.
    static func items(_ list: [Title], _ decorate: @Sendable (Title) -> (kicker: String?, subtitle: String?, badge: String?) = { _ in (nil, nil, nil) }) async -> [CatalogItem] {
        var out: [CatalogItem] = []
        for t in list {
            let d = decorate(t)
            out.append(CatalogItem(title: t, availability: await AvailabilityChecker.shared.peek(t), kicker: d.kicker, subtitle: d.subtitle, badge: d.badge))
        }
        return out
    }
}

/// Seasonal hubs on the phone (Seasons object): today, the hub that's on, a hub's rows, Tonight's Pick.
enum Seasons {
    private static let notForKids: Set<Int> = [27, 53, 80, 10752]
    private static let base = "&include_adult=false&include_video=false&sort_by=popularity.desc"

    static func today() -> SDay {
        let c = DreamTime.calendar
        let now = Date()
        return SDay.of(c.component(.year, from: now), c.component(.month, from: now), c.component(.day, from: now))
    }

    static func all(kids: Bool, _ today: SDay) -> [SeasonalHub] { SeasonCalendar.active(today, kids: kids) }
    static func active(kids: Bool, _ today: SDay) -> SeasonalHub { all(kids: kids, today).first ?? SeasonCalendar.top(today) }
    static func forGenre(_ genre: String, kids: Bool, _ today: SDay) -> SeasonalHub? { all(kids: kids, today).first { !$0.base && $0.genres.contains(genre) } }

    static func tile(_ hub: SeasonalHub, _ today: SDay) -> HubTile {
        let night = hub.night(today)
        return HubTile(id: hub.id, title: hub.title, kicker: hub.kicker, tagline: hub.tagline, glyph: hub.glyph, hue: hub.hue, status: hub.status(today),
                       night: night, hasCountdown: hub.countdown != nil, sub: night ?? (hub.countdown != nil ? hub.kicker : "Seasonal Collection"))
    }

    private static func mode(_ k: SeasonRow.Kind) -> Shelf.Mode {
        switch k { case .trending: .trending; case .new: .new; case .topVoted: .topVoted; case .acclaimed: .acclaimed; default: .variety }
    }

    static func load(_ hub: SeasonalHub, _ r: SeasonRow, kids: Bool, _ today: SDay, _ ctx: ProfileContext) async -> [Title] {
        let movie = r.movie.map { SeasonCalendar.fill($0, hub, today) }
        let tv = r.tv.map { SeasonCalendar.fill($0, hub, today) }
        var raw: [Title] = []
        switch r.kind {
        case .picks:
            if RowPaging.page > 1 { break }
            // Every pick at once (speed pass), kept in the hub's order.
            var picked = [Title?](repeating: nil, count: r.ids.count)
            await withTaskGroup(of: (Int, Title?).self) { g in
                for (i, key) in r.ids.enumerated() {
                    g.addTask {
                        let p = key.split(separator: ":")
                        guard p.count == 2, let kind = MediaKind(rawValue: String(p[0])), let id = Int(p[1]),
                              let o = try? await Tmdb.object("/\(kind.rawValue)/\(id)", "&append_to_response=external_ids") else { return (i, nil) }
                        return (i, Tmdb.parse(o, kind))
                    }
                }
                for await (i, t) in g { picked[i] = t }
            }
            // Fetched one by one, not through a list: the profile's rating limit applies here.
            raw = await KidsFilter.titles(picked.compactMap { $0 })
        case .popular:
            let kinds = [(MediaKind.movie, movie), (.tv, tv)].compactMap { k, q in q.map { (k, $0) } }
            var lists = [[Title]](repeating: [], count: kinds.count)
            await withTaskGroup(of: (Int, [Title]).self) { g in
                for (i, (type, q)) in kinds.enumerated() {
                    let votes = q.contains("vote_count.gte=") ? "" : "&vote_count.gte=\(type == .movie ? 50 : 20)"
                    let query = base + q + votes
                    g.addTask {
                        async let a = (try? await Tmdb.list("/discover/\(type.rawValue)", query, type: type)) ?? []
                        async let b = (try? await Tmdb.list("/discover/\(type.rawValue)", "\(query)&page=2", type: type)) ?? []
                        return (i, await a + b)
                    }
                }
                for await (i, l) in g { lists[i] = l }
            }
            raw = RowMix.interleave(lists)
        default:
            let spec = GenreSpec(hub.title, movie != nil ? (r.genre > 0 ? r.genre : Discover.any) : 0, tv != nil ? Discover.anyTv : 0)
            raw = await GenreShelves.load(spec, Shelf(title: SeasonCalendar.fill(r.title, hub, today), mode: mode(r.kind), movie: movie, tv: tv))
        }
        return RowMix.tidy(ctx, raw).filter { !kids || !$0.genreIds.contains(where: notForKids.contains) }
    }

    /// Tonight's Pick: one title from the hub's pick pool, chosen by the date (the same all day).
    static func pick(_ hub: SeasonalHub, kids: Bool, _ today: SDay, _ ctx: ProfileContext) async -> Title? {
        guard let r = kids ? (hub.kidsPick ?? hub.rows.first { $0.kids }) : hub.pick else { return nil }
        let pool = await RowPaging.at("Tonight's Pick \(hub.id)", 1) { await load(hub, r, kids: kids, today, ctx) }
            .filter { $0.backdropPath != nil }.sorted { $0.key < $1.key }
        if pool.isEmpty { return nil }
        var h: Int32 = 0
        for u in "\(today)|\(hub.id)".utf16 { h = h &* 31 &+ Int32(u) }
        return pool[Int(((Int64(h) % Int64(pool.count)) + Int64(pool.count)) % Int64(pool.count))]
    }

    static func homeTitles(_ hub: SeasonalHub, kids: Bool, _ today: SDay, _ ctx: ProfileContext) async -> (Title?, [Title]) {
        guard let r = hub.rowsFor(kids: kids).first else { return (nil, []) }
        async let p = RowPaging.page > 1 ? nil : pick(hub, kids: kids, today, ctx)
        async let lead = load(hub, r, kids: kids, today, ctx)
        return (await p, await lead)
    }
}
