import Foundation
import DreamCore

/// What the current profile has finished and the languages it watches in (RowMix.finished / prefLangs), read once per
/// page build. Languages: English and the device's (the player's audio language joins once the player exposes it).
struct ProfileContext: Sendable {
    var finished: Set<String>
    var langs: Set<String>
    var kids: Bool
    /// The profile's taste snapshot (Taste.swift): ranks every discovery row, never waited on.
    var taste: TasteProfile = .cold

    static func current() async -> ProfileContext {
        let recent = await Services.shared.history.recent()
        let done = Set(recent.filter { $0.progress >= 0.92 && ($0.hidden || $0.title.kind == .movie) }.map(\.title.key))
        var langs: Set<String> = ["en"]
        if let l = Locale.current.language.languageCode?.identifier { langs.insert(l) }
        let p = await Services.shared.profiles.current()
        let saved = await Services.shared.myList.items()
        return ProfileContext(finished: done, langs: langs, kids: p?.isKids ?? false, taste: Taste.current(history: recent, myList: saved))
    }
}

/// Keeps one page from showing the same title in row after row (RowClaims): a title belongs to the first row that loads it.
final class RowClaims: @unchecked Sendable {
    private let lock = NSLock()
    private var owner: [String: String] = [:]
    let anime: Bool
    let ctx: ProfileContext

    init(ctx: ProfileContext, anime: Bool = false) { self.ctx = ctx; self.anime = anime }

    /// Anime is kept on an anime page, in a row named for it and in personal rows.
    private func animeOk(_ row: String) -> Bool { anime || RowMix.animeRow(row) }

    /// [list] minus titles another row shows; the first [n] left are claimed for [row]. A discovery row is ranked
    /// for the profile first (Taste.rank: its own order, taste, steadied quality, freshness; franchises spread).
    func take(_ row: String, _ list: [Title], _ n: Int = 20) -> [Title] {
        var pool = RowMix.tidy(ctx, list, anime: animeOk(row))
        if Taste.ranks(row) { pool = Taste.rank(ctx.taste, pool) }
        let claim = !RowPaging.foreign(row)
        return lock.withLock {
            let out = Array(pool.filter { owner[$0.key] == nil || owner[$0.key] == row }.prefix(n))
            if claim { for t in out { owner[t.key] = row } }
            return out
        }
    }

    /// [take], but a row that must always be there (In Theaters) shows its own list when others claimed most of it.
    func takeAtLeast(_ row: String, _ list: [Title], min: Int, _ n: Int = 20) -> [Title]? {
        let t = take(row, list, n)
        if t.count >= min { return t }
        let pool = RowMix.tidy(ctx, list, anime: animeOk(row))
        let out: [Title] = lock.withLock {
            let o = Array(pool.prefix(n))
            if !RowPaging.foreign(row) { for t in o where owner[t.key] == nil { owner[t.key] = row } }
            return o
        }
        return out.count >= min ? out : nil
    }

    /// A chart (Top 10) keeps its order: shown as is, its titles kept out of the rows below.
    /// With [n], the first [n] left after the filters (a chart fetched long so it always fills, e.g. Top 10).
    func claim(_ row: String, _ list: [Title], _ n: Int? = nil) -> [Title] {
        var l = animeOk(row) || !RowMix.animeApart ? list : list.filter { !RowMix.isAnime($0) }
        if let n { l = Array(l.prefix(n)) }
        if !RowPaging.foreign(row) { lock.withLock { for t in l where owner[t.key] == nil { owner[t.key] = row } } }
        return l
    }
}

/// Deep rows (RowPaging): a row's loader run again as page N moves every TMDB list it asks for N - 1 pages on.
enum RowPaging {
    struct State: Sendable { let row: String; let page: Int }
    @TaskLocal static var state: State?

    static let maxTitles = 100
    static let emptyTries = 2

    static var page: Int { state?.page ?? 1 }

    static func foreign(_ row: String) -> Bool { state.map { $0.row != row } ?? false }

    static func at<T: Sendable>(_ row: String, _ page: Int, _ block: () async throws -> T) async rethrows -> T {
        try await $state.withValue(State(row: row, page: page)) { try await block() }
    }

    /// [query] moved page - 1 pages on (unchanged on the first page).
    static func paged(_ query: String) -> String {
        let n = page
        if n <= 1 { return query }
        if let r = query.range(of: "&page=(\\d+)", options: .regularExpression) {
            let num = Int(query[r].dropFirst(6)) ?? 1
            var q = query; q.removeSubrange(r)
            return q + "&page=\(min(500, num + n - 1))"
        }
        return query + "&page=\(min(500, n))"
    }

    /// Runs [tasks] side by side, results in order; a task that throws makes this throw (RowPaging.all).
    static func all<T: Sendable>(_ tasks: [@Sendable () async throws -> T]) async throws -> [T] {
        let st = state
        return try await withThrowingTaskGroup(of: (Int, T).self) { g in
            for (i, t) in tasks.enumerated() { g.addTask { (i, try await $state.withValue(st) { try await t() }) } }
            var out = [T?](repeating: nil, count: tasks.count)
            for try await (i, v) in g { out[i] = v }
            return out.map { $0! }
        }
    }

    /// The next page of a row: [load] as page from, from+1... (up to emptyTries pages bringing nothing new).
    static func next(_ row: String, from: Int, shown: Set<String>, _ load: @Sendable () async throws -> [Title]?) async -> (Int, [Title]) {
        var page = from
        for _ in 0..<emptyTries {
            guard let got = try? await at(row, page, load), !got.isEmpty else { return (page, []) }
            var seen = Set<String>()
            let fresh = got.filter { !shown.contains($0.key) && seen.insert($0.key).inserted }
            if !fresh.isEmpty { return (page, fresh) }
            page += 1
        }
        return (page, [])
    }
}

/// Row recipes (RowMix.kt): variety, hidden gems, acclaimed, recommendations, tidy.
enum RowMix {
    static let recentYears = 30
    static let acclaimedYears = 25
    static let gemYears = 18

    private static func javaHash(_ s: String) -> Int32 {
        var h: Int32 = 0
        for u in s.utf16 { h = h &* 31 &+ Int32(u) }
        return h
    }

    /// A page from 1 to [pages] that stays the same all day for [row] and moves on tomorrow.
    static func dailyPage(_ row: String, _ pages: Int) -> Int {
        let c = DreamTime.calendar // the viewer's zone decides when "tomorrow" starts (STANDARDS §5)
        let now = Date()
        let today = c.component(.year, from: now) * 400 + (c.ordinality(of: .day, in: .year, for: now) ?? 1)
        let v = Int64(today) * 31 + Int64(javaHash(row))
        let m = ((v % Int64(pages)) + Int64(pages)) % Int64(pages)
        return 1 + Int(m)
    }

    static func dateKey(_ type: MediaKind) -> String { type == .movie ? "primary_release_date" : "first_air_date" }

    static func yearsAgo(_ years: Int) -> String { DreamTime.yearsAgo(years) }

    private static var thisYear: Int { Calendar.current.component(.year, from: Date()) }

    /// Japanese animation, and Chinese or Korean animated series not for kids or families.
    static func isAnime(_ t: Title) -> Bool { AnimeDubs.isAnime(t) }

    /// Keep Anime in Anime Sections (AnimePrefs). Off, no row, hero or TMDB query leaves anime out (NewReleases.withAnime).
    static var animeApart: Bool { AnimePrefs.apart }

    /// [t] is anime this profile keeps in the anime sections: left out of a general row, hero or chart.
    static func animeHidden(_ t: Title) -> Bool { isAnime(t) && animeApart }

    private static let personal = ["Because You Watched", "Top Picks", "Continue Watching", "My List", "Recommended", "More Like"]

    static func animeRow(_ row: String) -> Bool {
        row.range(of: "\\banime\\b", options: [.regularExpression, .caseInsensitive]) != nil || row.contains("Crunchyroll") ||
            personal.contains { row.lowercased().hasPrefix($0.lowercased()) }
    }

    private static func widelySeen(_ t: Title) -> Bool { t.votes >= (t.kind == .movie ? 500 : 150) }

    /// Plainly bad: rated under 5.0 by at least 100 people (a popular row's "Resident Dead" at 5.4 stays, ranked down).
    static func poorlyRated(_ t: Title) -> Bool { t.votes >= 100 && t.rating > 0 && t.rating < 5.0 }

    /// Out now: a dated title whose day has come (a chart lists "Dune: Part Three" months ahead; it belongs in Coming Soon).
    static func released(_ t: Title) -> Bool { t.date.count != 10 || t.date <= DreamTime.todayYmd() }

    /// No adult, art-less, finished, poorly rated or obscure-foreign titles; anime only when [anime]. [artFirst]: backdrops first.
    static func tidy(_ ctx: ProfileContext, _ list: [Title], artFirst: Bool = true, anime: Bool = false) -> [Title] {
        if list.isEmpty { return list }
        let known = list.filter { !$0.lang.isEmpty }
        var counts: [String: Int] = [:]
        for t in known { counts[t.lang, default: 0] += 1 }
        let top = counts.max { $0.value < $1.value }
        let own = (top.map { known.count >= 4 && $0.value * 2 >= known.count } ?? false) ? top?.key : nil
        let keepAnime = anime || !animeApart
        let ok = list.filter { t in
            !t.adult && t.posterPath != nil && !ctx.finished.contains(t.key) && (keepAnime || !isAnime(t)) && !poorlyRated(t) &&
                (t.lang.isEmpty || ctx.langs.contains(t.lang) || t.lang == own || widelySeen(t))
        }
        return artFirst ? ok.filter { $0.backdropPath != nil } + ok.filter { $0.backdropPath == nil } : ok
    }

    static func recent(_ type: MediaKind) -> String { "&\(dateKey(type)).gte=\(yearsAgo(recentYears))" }

    private static func recentFloor(_ type: MediaKind, _ q: String) -> String {
        q.contains("\(dateKey(type)).gte=") || q.contains("\(dateKey(type)).lte=") ? "" : "&\(dateKey(type)).gte=\(yearsAgo(recentYears))"
    }

    private static func voteFloor(_ type: MediaKind, _ q: String, _ movie: Int, _ tv: Int) -> String {
        q.contains("vote_count.gte=") ? "" : "&vote_count.gte=\(type == .movie ? movie : tv)"
    }

    /// Popular now alternating with favorites (most voted, 6.3+, over a year old, a daily page).
    static func variety(_ type: MediaKind, _ query: String, _ row: String) async throws -> [Title] {
        let path = "/discover/\(type.rawValue)"
        let q = query + recentFloor(type, query) + voteFloor(type, query, 150, 60)
        let dated = q.contains("\(dateKey(type)).lte=")
        let fav = q.replacingOccurrences(of: "&sort_by=[^&]*", with: "&sort_by=vote_count.desc", options: .regularExpression) +
            (dated ? "" : "&\(dateKey(type)).lte=\(yearsAgo(1))") + (q.contains("vote_average.gte=") ? "" : "&vote_average.gte=6.3")
        let page = dailyPage(row, 4)
        let r = try await RowPaging.all([
            { @Sendable in try await Tmdb.list(path, q, type: type) },
            { @Sendable in try await Tmdb.list(path, "\(fav)&page=\(page)", type: type) },
            { @Sendable in try await Tmdb.list(path, "\(fav)&page=\(page + 1)", type: type) },
        ])
        return interleave([r[0], r[1] + r[2]])
    }

    private static func asked(_ extra: String) -> Set<String> {
        guard let r = extra.range(of: "with_genres=([0-9,|]+)", options: .regularExpression) else { return [] }
        return Set(extra[r].dropFirst("with_genres=".count).split(whereSeparator: { $0 == "," || $0 == "|" }).map(String.init))
    }

    private static func notGems(_ type: MediaKind, _ extra: String) -> [String] {
        let a = asked(extra)
        return (type == .movie ? ["10751", "10770"] : ["10762", "10751", "10763", "10764", "10766", "10767"]).filter { !a.contains($0) }
    }

    /// [extra] with notGems folded into its own without_genres (TMDB answers 400 to the parameter twice).
    private static func skipping(_ type: MediaKind, _ extra: String, gems: Bool = true) -> String {
        var rest = extra
        var skip: [String] = []
        if let r = extra.range(of: "&without_genres=([^&]*)", options: .regularExpression) {
            skip.append(String(extra[r].dropFirst("&without_genres=".count)))
            rest.removeSubrange(r)
        }
        if gems { let g = notGems(type, extra); if !g.isEmpty { skip.append(g.joined(separator: ",")) } }
        return skip.isEmpty ? rest : rest + "&without_genres=" + skip.joined(separator: ",")
    }

    static func hiddenGems(_ type: MediaKind, _ extra: String, _ row: String) async throws -> [Title] {
        let votes = type == .movie ? "&vote_count.gte=150&vote_count.lte=1500" : "&vote_count.gte=80&vote_count.lte=800"
        let d = dateKey(type)
        return try await Tmdb.list("/discover/\(type.rawValue)", "&include_adult=false&sort_by=popularity.desc&vote_average.gte=7.0\(votes)" +
            "&\(d).gte=\(yearsAgo(gemYears))&\(d).lte=\(yearsAgo(1))\(skipping(type, extra))&page=\(dailyPage(row, extra.isEmpty ? 3 : 1))", type: type)
    }

    static func acclaimed(_ type: MediaKind, _ extra: String, _ row: String) async throws -> [Title] {
        let movie = type == .movie
        let d = dateKey(type)
        let narrow = !extra.isEmpty
        let base = "&include_adult=false&include_video=false&\(d).gte=\(yearsAgo(acclaimedYears))&\(d).lte=\(yearsAgo(0))\(skipping(type, extra, gems: !movie))"
        let best = movie ? (narrow ? 1000 : 3000) : (narrow ? 300 : 800)
        let loved = movie ? (narrow ? 400 : 1000) : (narrow ? 120 : 300)
        let p1 = narrow ? 1 : dailyPage("\(row) best", 3)
        let p2 = narrow ? 1 : dailyPage("\(row) loved", 2)
        let r = try await RowPaging.all([
            { @Sendable in try await Tmdb.list("/discover/\(type.rawValue)", "\(base)&sort_by=vote_average.desc&vote_average.gte=7.5&vote_count.gte=\(best)&page=\(p1)", type: type) },
            { @Sendable in try await Tmdb.list("/discover/\(type.rawValue)", "\(base)&sort_by=popularity.desc&vote_average.gte=7.8&vote_count.gte=\(loved)&page=\(p2)", type: type) },
        ])
        return interleave([r[1], r[0]])
    }

    /// Because You Watched: TMDB recommendations for the seed, topped up from similar titles.
    static func recs(_ ctx: ProfileContext, _ type: MediaKind, _ id: Int, seedYear: Int?) async -> [Title] {
        let path = "/\(type.rawValue)/\(id)"
        // Both at once (speed pass); similar titles are only used when the recommendations run short.
        async let rec = (try? await Tmdb.list("\(path)/recommendations", type: type)) ?? []
        async let sim = (try? await Tmdb.list("\(path)/similar", type: type)) ?? []
        let list = await rec
        let more = list.count >= 12 && RowPaging.page == 1 ? [] : await sim
        let floor = min(thisYear - recentYears, (seedYear ?? thisYear) - 15)
        let minVotes = type == .movie ? 200 : 60
        var seen = Set<String>()
        let all = (list + more).filter { seen.insert($0.key).inserted }.filter { t in
            t.id != id && t.votes >= minVotes && (Int(t.year) ?? thisYear) >= floor
        }
        return tidy(ctx, all, anime: true)
    }

    static func interleave(_ lists: [[Title]]) -> [Title] {
        var out: [Title] = []
        var seen = Set<String>()
        let n = lists.map(\.count).max() ?? 0
        for i in 0..<n { for l in lists where i < l.count { if seen.insert(l[i].key).inserted { out.append(l[i]) } } }
        return out
    }
}
