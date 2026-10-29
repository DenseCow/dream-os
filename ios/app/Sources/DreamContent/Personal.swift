import Foundation
import DreamCore

/// Home's own picks (HomePicks.kt): hero titles for the time of day, Top Picks for You, the services Home always has.
enum HomePicks {
    static let homeServices = ["Netflix", "Hulu", "Disney+", "HBO Max", "Prime Video", "Apple TV", "Paramount+", "Peacock", "AMC+"]
    static let pickSeeds = 6, minPicks = 6

    struct Moment: Sendable { let name: String; let genres: Set<Int>; let movies: Double }

    static func moment() -> Moment {
        switch DreamTime.hourNow() {
        case 5...11: return Moment(name: "This Morning", genres: [35, 10751, 16, 12, 10762], movies: 0)
        case 12...16: return Moment(name: "This Afternoon", genres: [35, 12, 10759, 878, 10765, 14], movies: 0)
        case 17...21: return Moment(name: "Tonight", genres: [28, 18, 53, 80, 10759, 9648], movies: 1)
        default: return Moment(name: "Late Night", genres: [27, 53, 9648, 80, 878, 10765], movies: 0.5)
        }
    }

    /// [n] trending titles with art, the ones that suit the time of day (and this profile's taste) first. Only what's
    /// out: the chart lists films months ahead. No anime outside its sections (STANDARDS §5).
    static func featured(_ n: Int = 6, taste: TasteProfile = .cold) async -> [Title] {
        let m = moment()
        async let ta = (try? await Tmdb.trending()) ?? []
        async let tb = (try? await Tmdb.list("/trending/all/week", "&page=2")) ?? []
        let (a, b) = (await ta, await tb)
        var seen = Set<String>()
        let pool = (a + b).filter { $0.backdropPath != nil && RowMix.released($0) && !RowMix.animeHidden($0) && !$0.adult && seen.insert($0.key).inserted }
        return pool.enumerated().map { (i, t) in
            (t, (1.0 - Double(i) / 40.0) + (t.genreIds.contains(where: m.genres.contains) ? 1 : 0) + (t.kind == .movie ? 0.6 * m.movies : 0)
                + 0.8 * taste.strength * taste.affinity(t) + 0.4 * Taste.quality(t))
        }.sorted { $0.1 > $1.1 }.prefix(n).map(\.0)
    }

    static func watchedEnough(_ e: HistoryEntry) -> Bool { e.upNext || e.progress >= 0.92 || e.positionMs >= 10 * 60_000 }

    /// Recommendations for up to six recent titles (then My List), blended; nothing watched or saved.
    static func topPicks(_ ctx: ProfileContext) async -> [Title] {
        let watched = await Services.shared.history.recent()
        let saved = await Services.shared.myList.items()
        var seenSeed = Set<String>()
        let seeds = (watched.filter { $0.title.id > 0 && watchedEnough($0) && !BecauseYouWatched.removed($0) }.map(\.title) + saved).filter { seenSeed.insert($0.key).inserted }.prefix(pickSeeds)
        if seeds.isEmpty { return [] }
        let skip = Set(watched.map(\.title.key) + saved.map(\.key))
        var lists = [[Title]](repeating: [], count: seeds.count)
        await withTaskGroup(of: (Int, [Title]).self) { g in
            for (i, s) in seeds.enumerated() { g.addTask { (i, await RowMix.recs(ctx, s.kind, s.id, seedYear: nil)) } }
            for await (i, l) in g { lists[i] = l }
        }
        var score: [String: (Title, Double, Int)] = [:]
        var order = 0
        for (i, recs) in lists.enumerated() {
            for (rank, t) in recs.enumerated() where !skip.contains(t.key) && (t.backdropPath != nil || t.posterPath != nil) {
                let add = (1.0 - Double(rank) / 40.0) * (1.0 - Double(i) / (2.0 * Double(pickSeeds)))
                let old = score[t.key]
                score[t.key] = (t, (old?.1 ?? 0) + add, old?.2 ?? { order += 1; return order }())
            }
        }
        return score.values.sorted { $0.1 != $1.1 ? $0.1 > $1.1 : $0.2 < $1.2 }.map(\.0)
    }
}

/// Which watched titles seed "Because You Watched" (BecauseYouWatched.kt).
enum BecauseYouWatched {
    static let maxRows = 3

    /// Removed from Continue Watching before finishing (a test, a mistake): it seeds nothing (owner, 2026-10-28).
    static func removed(_ e: HistoryEntry) -> Bool { e.hidden && e.progress < 0.92 && !e.upNext }

    static func pick(shows: Bool) async -> [HistoryEntry] {
        let now = Date()
        var seen = Set<Int>()
        let pool = await Services.shared.history.recent().sorted { $0.updatedAt > $1.updatedAt }
            .filter { ($0.title.kind == .tv) == shows && !$0.title.name.isEmpty && $0.title.id > 0 && now.timeIntervalSince($0.updatedAt) <= 30 * 86_400 }
            .filter { !Self.removed($0) }
            .filter(HomePicks.watchedEnough).filter { seen.insert($0.title.id).inserted }
        if pool.isEmpty { return [] }
        // No "abandoned" signal anywhere (owner): only recency weighs a seed.
        func weight(_ e: HistoryEntry) -> Double {
            let age = max(0, now.timeIntervalSince(e.updatedAt)) / 86_400
            return pow(0.5, age / 7.0)
        }
        var picked: [HistoryEntry] = []
        var rest = pool
        picked.append(rest.removeFirst())
        let c = DreamTime.calendar
        let key = "\(c.component(.year, from: now))-\(c.ordinality(of: .day, in: .year, for: now) ?? 0)|\(DreamStore.shared.profileId)|\(pool.first?.title.key ?? "")"
        // A stable hash: Swift's `hashValue` is seeded per launch, so the day's pick changed on every relaunch.
        var h: Int64 = 0
        for u in key.utf16 { h = h &* 31 &+ Int64(u) }
        var rng = SeededRandom(seed: UInt64(bitPattern: h))
        while picked.count < maxRows && !rest.isEmpty {
            var r = rng.nextDouble() * rest.reduce(0) { $0 + weight($1) }
            var i = 0
            while i < rest.count - 1 { r -= weight(rest[i]); if r <= 0 { break }; i += 1 }
            picked.append(rest.remove(at: i))
        }
        return picked
    }
}

/// A small deterministic generator (the day's pick stays the same between visits).
struct SeededRandom {
    var state: UInt64
    init(seed: UInt64) { state = seed == 0 ? 0x9E3779B97F4A7C15 : seed }
    mutating func next() -> UInt64 { state ^= state << 13; state ^= state >> 7; state ^= state << 17; return state }
    mutating func nextDouble() -> Double { Double(next() >> 11) / Double(1 << 53) }
}

/// "New Episodes for You" (NewEpisodes.kt): recently aired and upcoming episodes of followed shows (TV shows on My List,
/// then shows from the watch history), the "NEW" badge per profile until seen.
actor NewEpisodes {
    static let shared = NewEpisodes()
    static let recentDays = 7, upcomingDays = 30, maxShows = 40

    struct Item: Sendable {
        let show: Title
        let season: Int
        let number: Int
        let lastNumber: Int
        let name: String
        let airDate: String
        let at: Date?
        let still: String?
        let overview: String
        let upcoming: Bool
        var key: String { "\(show.id):s\(season)e\(number)" }

        /// "S2  ·  E5  ·  Aired yesterday", "S1  ·  E1–8  ·  Aired today", "S3  ·  E1  ·  Tonight".
        func badge() -> String {
            let eps = lastNumber > number ? "E\(number)–\(lastNumber)" : "E\(number)"
            let days = DreamTime.daysFromToday(airDate) ?? 0
            let time: String
            if upcoming {
                if let at { time = DreamTime.airLabel(at) }
                else if days <= 0 { time = "Tonight" }
                else if days == 1 { time = "Tomorrow" }
                else if days < 7 { time = DreamTime.formatDay(airDate, "EEE") ?? airDate }
                else { time = DreamTime.formatDay(airDate, "MMM d") ?? airDate }
            } else {
                if days >= 0 { time = "Aired today" } else if days == -1 { time = "Aired yesterday" } else { time = "Aired " + (DreamTime.formatDay(airDate, "EEE") ?? airDate) }
            }
            return "S\(season)  ·  \(eps)  ·  \(time)"
        }
    }

    private var known: Set<String> { Set(DreamStore.shared.get([String].self, "newep_known", perProfile: true) ?? []) }
    private var fresh: Set<String> { Set(DreamStore.shared.get([String].self, "newep_fresh", perProfile: true) ?? []) }

    func followed() async -> [Title] {
        let list = await Services.shared.myList.items().filter { $0.kind == .tv }
        let watched = await Services.shared.history.recent().sorted { $0.updatedAt > $1.updatedAt }.map(\.title).filter { $0.kind == .tv }
        // Trakt's followed shows (watchlist, paused, watched lately) after My List, as on the TV (FollowedShows, DreamCore).
        let others = FollowedShows.ids().map { Title(id: $0, kind: .tv, name: "") }
        var seen = Set<Int>()
        return (list + others + watched).filter { $0.id > 0 && seen.insert($0.id).inserted }.prefix(Self.maxShows).map { $0 }
    }

    /// (recent, upcoming) for the current profile.
    func load() async -> (recent: [Item], upcoming: [Item]) {
        let shows = await followed()
        var recent: [Item] = [], upcoming: [Item] = []
        await withTaskGroup(of: (Item?, Item?).self) { g in
            for s in shows { g.addTask { await Self.items(s) } }
            for await (r, u) in g { if let r { recent.append(r) }; if let u { upcoming.append(u) } }
        }
        recent.sort { ($0.airDate, $0.at ?? .distantPast, $1.show.name) > ($1.airDate, $1.at ?? .distantPast, $0.show.name) }
        upcoming = upcoming.filter { u in !recent.contains { $0.key == u.key } }.sorted { ($0.airDate, $0.at ?? .distantPast) < ($1.airDate, $1.at ?? .distantPast) }
        // New ones (not seen by an earlier load) get the badge; the very first load only learns.
        let keys = Set(recent.map(\.key))
        let firstRun = DreamStore.shared.get([String].self, "newep_known", perProfile: true) == nil
        var f = fresh
        if !firstRun { let seen = known; for k in keys where !seen.contains(k) { f.insert(k) } }
        DreamStore.shared.set(Array(keys), "newep_known", perProfile: true)
        DreamStore.shared.set(Array(f.intersection(keys)), "newep_fresh", perProfile: true)
        return (recent, upcoming)
    }

    func isNew(_ key: String) -> Bool { fresh.contains(key) }

    func markSeen(_ key: String) {
        var f = fresh
        if f.remove(key) != nil { DreamStore.shared.set(Array(f), "newep_fresh", perProfile: true) }
    }

    private static func ep(_ o: Any?) -> (Int, Int, String, String, String?, String)? {
        guard let o = o as? [String: Any] else { return nil }
        let d = Tmdb.str(o, "air_date"); guard d.count == 10 else { return nil }
        return (o["season_number"] as? Int ?? 0, o["episode_number"] as? Int ?? 0, Tmdb.str(o, "name"), d, Tmdb.path(o, "still_path"), Tmdb.str(o, "overview"))
    }

    private static func items(_ show: Title) async -> (Item?, Item?) {
        guard let o = try? await Tmdb.object("/tv/\(show.id)") else { return (nil, nil) }
        let t = Tmdb.parse(o, .tv) ?? show
        let now = Date()
        let air = await AirTimes.info(show.id)
        var recent: Item?
        if let l = ep(o["last_episode_to_air"]), let days = DreamTime.daysFromToday(l.3), (-recentDays...0).contains(days) {
            var last = l.1
            var firstNum = l.1
            // A binge drop: every episode released that day.
            if days >= -recentDays, let s = try? await Tmdb.object("/tv/\(show.id)/season/\(l.0)") {
                let same = (s["episodes"] as? [[String: Any]] ?? []).filter { Tmdb.str($0, "air_date") == l.3 }.compactMap { $0["episode_number"] as? Int }
                last = max(last, same.max() ?? last)
                firstNum = min(firstNum, same.min() ?? firstNum)
            }
            let at = air?.prev.flatMap { $0.0 == l.0 && $0.1 == l.1 ? $0.2 : nil }
            if at.map({ $0 <= now }) ?? true {
                recent = Item(show: t, season: l.0, number: firstNum, lastNumber: last, name: l.2, airDate: l.3, at: at, still: l.4, overview: l.5, upcoming: false)
            }
        }
        var upcoming: Item?
        if let n = ep(o["next_episode_to_air"]), let days = DreamTime.daysFromToday(n.3) {
            let at = air?.next.flatMap { $0.0 == n.0 && $0.1 == n.1 ? $0.2 : nil }
            if let at, at <= now {
                if recent == nil { recent = Item(show: t, season: n.0, number: n.1, lastNumber: n.1, name: n.2, airDate: DreamTime.ymd(at), at: at, still: n.4, overview: n.5, upcoming: false) }
            } else if (0...upcomingDays).contains(days) {
                upcoming = Item(show: t, season: n.0, number: n.1, lastNumber: n.1, name: n.2, airDate: n.3, at: at, still: n.4, overview: n.5, upcoming: true)
            }
        }
        return (recent, upcoming)
    }

}
