import Foundation
import DreamCore

/// The real catalog (CatalogService): TMDB rows for every page, details, search, scores, availability.
final class ContentCatalog: CatalogService, @unchecked Sendable {
    private let lock = NSLock()
    private var sessions: [CatalogPage: PageSession] = [:]

    private static func key(_ page: CatalogPage) -> String {
        switch page {
        case .home: "home"; case .movies: "movies"; case .shows: "shows"; case .anime: "anime"; case .kids: "kids"
        case .service(let s): "service:\(s.name)"; case .genre(let g): "genre:\(g.id)"; case .season(let id): "season:\(id)"
        }
    }

    /// A Browse page's spec: `.genre(Genre(id: name))`, `.anime`, `.kids`.
    private static func spec(_ page: CatalogPage) -> GenreSpec? {
        switch page {
        case .anime: GenreSpec.find("Anime")
        case .kids: GenreSpec.find("Kids")
        case .genre(let g): GenreSpec.find(g.id) ?? GenreSpec.find(g.name)
        default: nil
        }
    }

    /// "chip:movie:28" (Movies / TV Shows chips).
    private static func chip(_ page: CatalogPage) -> (MediaKind, String, Int)? {
        guard case .genre(let g) = page, g.id.hasPrefix("chip:") else { return nil }
        let p = g.id.split(separator: ":")
        guard p.count == 3, let k = MediaKind(rawValue: String(p[1])), let id = Int(p[2]) else { return nil }
        return (k, g.name, id)
    }

    private func newSession(_ page: CatalogPage) async -> PageSession {
        let ctx = await ProfileContext.current()
        var anime = false
        if let s = Self.spec(page) { anime = s.anime }
        if case .service(let svc) = page { anime = svc.name == "Crunchyroll" }
        if let c = Self.chip(page) { anime = c.2 == Discover.anime }
        let s = PageSession(page: page, ctx: ctx, anime: anime)
        guard Tmdb.hasKey else { return s }
        switch page {
        case .home: await Pages.home(s)
        case .movies: await Pages.category(s, .movie)
        case .shows: await Pages.category(s, .tv)
        case .service(let svc): Pages.service(s, svc)
        case .season(let id): await Pages.season(s, id)
        case .anime, .kids, .genre:
            if let c = Self.chip(page) { Pages.chip(s, c.0, c.1, c.2) } else if let spec = Self.spec(page) { Pages.genre(s, spec) }
        }
        lock.withLock { sessions[page] = s }
        return s
    }

    private func session(_ page: CatalogPage) async -> PageSession {
        if let s = lock.withLock({ sessions[page] }) { return s }
        return await newSession(page)
    }

    // MARK: rows

    func rowSlots(for page: CatalogPage) async -> [CatalogRow] {
        let rows = await newSession(page).rows
        // Browse pages carry no in-row Spotlight card (owner, 2026-10-28); only Home and seasonal hubs keep theirs.
        return page.keepsSpotlight ? rows : rows.filter { !$0.isSpotlight }
    }

    func fill(row: CatalogRow, page: CatalogPage) async throws -> CatalogRow? {
        let s = await session(page)
        guard let slot = s.slot(row.id) else { return nil }
        if let custom = slot.custom {
            var r = try await custom()
            r?.id = row.id
            if let got = r { r = await KeyArt.shared.decorate(got) }
            return r
        }
        guard let load = slot.titles, var list = try await load(), !list.isEmpty else { return nil }
        if let n = slot.limit { list = Array(list.prefix(n)) }
        s.remember(row.id, list.map(\.key))
        var out = slot.row
        out.items = await Pages.items(list, slot.decorate)
        out.hasMore = slot.deep && list.count < RowPaging.maxTitles
        return await KeyArt.shared.decorate(out)
    }

    func rows(for page: CatalogPage) async throws -> [CatalogRow] {
        let slots = await rowSlots(for: page)
        // Every row's fetches at once (`warm` claims nothing), then the fills in page order, read from the cache, so
        // the claims still match the TV's top-down filling.
        await withTaskGroup(of: Void.self) { g in
            for r in slots { g.addTask { await self.warm(row: r, page: page) } }
        }
        var out: [CatalogRow] = []
        for r in slots { if let f = try? await fill(row: r, page: page) { out.append(f) } }
        return out
    }

    func warm(row: CatalogRow, page: CatalogPage) async {
        let s = await session(page)
        guard let slot = s.slot(row.id) else { return }
        // Inside another row's paging state every claim is skipped (RowPaging.foreign), and page 1 leaves queries as is.
        await RowPaging.at("\u{1}warm", 1) {
            if let custom = slot.custom { _ = try? await custom() } else if let load = slot.titles, let list = try? await load() {
                // Key art asked for ahead too, so the fill finds it remembered (KeyArt.decorate waits only briefly).
                if [.landscape, .big].contains(slot.row.style), !slot.row.isSpotlight {
                    for t in list.prefix(16) { Task { _ = await KeyArt.shared.fetch(t) } }
                }
            }
        }
    }

    func more(row: CatalogRow, page: CatalogPage) async throws -> [CatalogItem] {
        let s = await session(page)
        guard let slot = s.slot(row.id), slot.deep, let load = slot.titles else { return [] }
        let (from, shown) = s.paging(row.id)
        if shown.count >= RowPaging.maxTitles { return [] }
        let (pg, fresh) = await RowPaging.next(row.id, from: from, shown: shown, load)
        let list = Array(fresh.prefix(RowPaging.maxTitles - shown.count))
        s.paged(row.id, next: pg + 1)
        s.remember(row.id, list.map { $0.key })
        var r = slot.row
        r.items = await Pages.items(list, slot.decorate)
        return await KeyArt.shared.decorate(r, wait: 0.6).items
    }

    func spotlight(for page: CatalogPage) async throws -> [CatalogItem] {
        guard Tmdb.hasKey else { return [] }
        switch page {
        case .home:
            let m = HomePicks.moment().name
            let taste = await ProfileContext.current().taste
            return await Pages.items(await HomePicks.featured(taste: taste)) { t in (m + (t.kind == .tv ? "  ·  Series" : "  ·  Movie"), nil, nil) }
        case .movies, .shows:
            let kind: MediaKind = page == .movies ? .movie : .tv
            let label = kind == .movie ? "Movies" : "TV Shows"
            let l = try await Tmdb.list("/trending/\(kind.rawValue)/week", type: kind).filter { $0.backdropPath != nil && !RowMix.isAnime($0) }.prefix(6)
            return await Pages.items(Array(l)) { _ in ("Trending in \(label)", nil, nil) }
        case .service(let svc):
            let l = await Pages.serviceMixed(svc.name, rating: 7).filter { $0.backdropPath != nil && (svc.name == "Crunchyroll" || !RowMix.isAnime($0)) }.prefix(6)
            return await Pages.items(Array(l)) { _ in ("On \(svc.name)", nil, nil) }
        case .season: return []
        default:
            if let c = Self.chip(page) {
                let l = try await Discover.list(DFilters(type: c.0, genre: c.2, sort: .popular)).filter { $0.backdropPath != nil && (c.2 == Discover.anime || !RowMix.isAnime($0)) }.prefix(6)
                return await Pages.items(Array(l)) { _ in (c.1, nil, nil) }
            }
            guard let spec = Self.spec(page) else { return [] }
            let l = await Pages.genreMixed(spec, rating: 7).filter { $0.backdropPath != nil }.prefix(6)
            return await Pages.items(Array(l)) { _ in (spec.name, nil, nil) }
        }
    }

    func chips(for page: CatalogPage) async -> [Genre] {
        switch page {
        case .movies: Pages.movieChips.map { Genre(id: "chip:movie:\($0.1)", name: $0.0) }
        case .shows: Pages.tvChips.map { Genre(id: "chip:tv:\($0.1)", name: $0.0) }
        default: []
        }
    }

    // MARK: titles

    func details(kind: MediaKind, id: Int) async throws -> TitleDetails {
        let d = try await Details.fetch(kind, id)
        Taste.noteOpened(d.title) // a title page opened: a small taste signal (Taste.swift)
        return d
    }

    func episodes(showId: Int, season: Int) async throws -> [Episode] { try await Details.episodes(showId, season) }

    func titleLogo(kind: MediaKind, id: Int) async -> String? { await Details.logoPath(kind, id) }

    /// The Watched pill's show rule: TMDB's latest aired episode, the episode counts of the seasons before it (all
    /// aired), and the day the next one (else the next announced season) airs. Same request as the show pills' (6 h).
    func showAirState(showId: Int) async -> ShowAirState? {
        guard let d = try? await Tmdb.object("/tv/\(showId)", "&append_to_response=external_ids") else { return nil }
        let last = d["last_episode_to_air"] as? [String: Any]
        let ls = last?["season_number"] as? Int ?? 0, le = last?["episode_number"] as? Int ?? 0
        let today = DreamTime.todayYmd()
        let seasons = d["seasons"] as? [[String: Any]] ?? []
        var next = Tmdb.str(d["next_episode_to_air"] as? [String: Any], "air_date")
        if next.count != 10 {
            next = seasons
                .filter { ($0["season_number"] as? Int ?? 0) > ls && Tmdb.str($0, "air_date").count == 10 && Tmdb.str($0, "air_date") > today }
                .map { Tmdb.str($0, "air_date") }.min() ?? ""
        }
        let counts = ls > 1 ? (1..<ls).map { n in seasons.first { ($0["season_number"] as? Int) == n }?["episode_count"] as? Int ?? 0 } : []
        return ShowAirState(lastSeason: ls, lastEpisode: le, seasonCounts: counts, nextAir: next)
    }

    func scores(for details: TitleDetails) async -> Scores { await Ratings.shared.scores(details) }

    func availability(for title: Title) async -> Availability? { await AvailabilityChecker.shared.detail(title)?.state }

    func availabilityDetail(for title: Title) async -> AvailabilityDetail? { await AvailabilityChecker.shared.detail(title) }

    func episodeAvailability(_ episode: Episode, of show: Title) async -> Availability? {
        await AvailabilityChecker.shared.episode(episode, show: show)?.state
    }

    func markEpisodeSeen(_ item: CatalogItem) async {
        guard let e = item.episode else { return }
        await NewEpisodes.shared.markSeen("\(item.title.id):s\(e.season)e\(e.number)")
    }

    func allowed(_ title: Title) async -> Bool { await KidsFilter.allowed(title) }

    func person(id: Int) async throws -> [Title] {
        let o = try await Tmdb.object("/person/\(id)/combined_credits")
        var seen = Set<String>()
        let list = (o["cast"] as? [[String: Any]] ?? []).compactMap { Tmdb.parse($0, nil) }
            .filter { $0.posterPath != nil && seen.insert($0.key).inserted }.sorted { $0.votes > $1.votes }
        return await KidsFilter.titles(list)
    }

    // MARK: search

    func search(_ query: String) async throws -> [Title] {
        await Tmdb.searchSpoken(query).filter { $0.posterPath != nil }
    }

    func searchAll(_ query: String) async -> SearchResults {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard q.count >= 2, Tmdb.hasKey else { return SearchResults(titles: []) }
        async let person = Tmdb.searchPerson(q)
        async let titles = Tmdb.searchSpoken(q)
        async let genre = GenreSearch.hits(q)
        let p = await person
        var t = await titles.filter { $0.posterPath != nil }
        if let p { t = t.filter { x in !p.titles.contains { $0.key == x.key } } }
        return SearchResults(titles: t, person: p, genre: await genre)
    }

    func suggestions(_ query: String) async -> [String] { GenreSearch.suggest(query) }

    func trending() async -> [Title] {
        Array(((try? await Tmdb.trending()) ?? []).filter { $0.posterPath != nil }.prefix(40))
    }

    // MARK: browse

    func genres() async -> [Genre] { GenreSpec.shortcuts.map { Genre(id: $0.name, name: $0.name) } }

    /// Browse by Service, in the TV's order (ServiceSpec.SHORTCUTS) with TMDB provider and network ids.
    func services() async -> [ServiceSpec] {
        let list: [(String, [Int])] = [("Netflix", [213]), ("Hulu", [453]), ("Prime Video", [1024]), ("Apple TV", [2552]), ("Disney+", [2739]),
            ("HBO Max", [3186, 49]), ("Peacock", [3353]), ("Paramount+", [4330]), ("AMC+", [174, 4661]), ("Crunchyroll", [1112]), ("Tubi", [])]
        return list.map { name, nets in
            ServiceSpec(id: name, name: name, providerIds: Tmdb.services.filter { $0.name == name }.map(\.providerId), networkIds: nets)
        }
    }

    func discover(_ filter: DiscoverFilter, page: Int) async throws -> DiscoverPage {
        let f = DFilters(type: filter.kind, genre: filter.genre, sort: filter.sort)
        var p = try await Discover.page(f, page)
        let ctx = await ProfileContext.current()
        p.titles = RowMix.tidy(ctx, p.titles, artFirst: false, anime: Discover.wantsAnime(f))
        return p
    }

    func discoverCategories(_ kind: MediaKind) async -> [DiscoverCategory] {
        Discover.categories(kind).map { DiscoverCategory(id: $0.id(kind), name: $0.name) }
    }

    func hub(id: String) async -> HubTile? {
        SeasonCalendar.find(id).map { Seasons.tile($0, Seasons.today()) }
    }

    func hubs() async -> [HubTile] {
        let today = Seasons.today()
        let kids = await Services.shared.profiles.current()?.isKids ?? false
        return Seasons.all(kids: kids, today).map { Seasons.tile($0, today) }
    }
}
