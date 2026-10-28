import Foundation
import DreamCore

/// The age-rating filter for a profile with a maximum rating (KidsFilter.kt + KidsProfiles levels). Every list keeps only
/// what the level allows by US certification; unknown or unrated titles are left out. Certifications are cached in
/// memory and in DreamStore (30 days).
enum KidsFilter {
    struct Level: Sendable { let name: String; let movie: String; let tv: [String] }
    static let levels: [Level] = [
        Level(name: "Little Kids", movie: "G", tv: ["TV-Y", "TV-G"]),
        Level(name: "Kids", movie: "PG", tv: ["TV-Y", "TV-G", "TV-Y7", "TV-PG"]),
        Level(name: "Teens", movie: "PG-13", tv: ["TV-Y", "TV-G", "TV-Y7", "TV-PG", "TV-14"]),
        Level(name: "Everyone", movie: "R", tv: ["TV-Y", "TV-G", "TV-Y7", "TV-PG", "TV-14", "TV-MA"]),
    ]
    static let movieOrder = ["G", "PG", "PG-13", "R", "NC-17"]

    /// The current profile's level index, nil when it has no limit. `Profile.ratingLimit` names a level, its movie
    /// rating ("PG") or a TV rating ("TV-Y7"); a Kids profile with no limit set is "Kids", like the TV.
    static func level() async -> Int? {
        guard let p = await Services.shared.profiles.current() else { return nil }
        if let lim = p.ratingLimit?.trimmingCharacters(in: .whitespaces).uppercased(), !lim.isEmpty {
            let i = levels.firstIndex { $0.name.uppercased() == lim || $0.movie == lim || $0.tv.last == lim }
                ?? (movieOrder.firstIndex(of: lim).map { min($0, levels.count - 1) })
            guard let i, i < levels.count - 1 else { return nil }
            return i
        }
        return p.isKids ? 1 : nil
    }

    static func allows(_ level: Int, _ cert: String?, tv: Bool) -> Bool {
        if level >= levels.count - 1 { return true }
        let c = (cert ?? "").trimmingCharacters(in: .whitespaces).uppercased()
        if c.isEmpty || c == "NR" || c == "UNRATED" { return false }
        let l = levels[level]
        if tv { return l.tv.contains(c) }
        guard let i = movieOrder.firstIndex(of: c), let m = movieOrder.firstIndex(of: l.movie) else { return false }
        return i <= m
    }

    /// Extra /discover parameters that make TMDB apply the limit itself ("" without one).
    static func discoverClause(_ type: MediaKind) async -> String {
        guard let lvl = await level() else { return "" }
        let l = levels[lvl]
        let allowed = type == .tv ? l.tv : Array(movieOrder.prefix((movieOrder.firstIndex(of: l.movie) ?? 0) + 1))
        return "&include_adult=false&certification_country=US&certification=" + allowed.joined(separator: "|")
    }

    /// [list] without what the limit leaves out; missing ratings are fetched first (up to ~8 s).
    static func titles(_ list: [Title], trusted: Bool = false) async -> [Title] {
        guard let lvl = await level(), !list.isEmpty else { return list }
        if trusted { return list.filter { !$0.adult } }
        let missing = await Certs.shared.missing(list)
        if !missing.isEmpty {
            await withTaskGroup(of: Void.self) { g in
                for t in missing { g.addTask { _ = await Certs.shared.fetch(t) } }
            }
        }
        var out: [Title] = []
        for t in list where !t.adult {
            if let c = await Certs.shared.cached(t), allows(lvl, c, tv: t.kind == .tv) { out.append(t) }
        }
        return out
    }

    /// The current profile may open [t] (fetches its rating when unknown).
    static func allowed(_ t: Title) async -> Bool {
        guard let lvl = await level() else { return true }
        let c: String?
        if let hit = await Certs.shared.cached(t) { c = hit } else { c = await Certs.shared.fetch(t) }
        return allows(lvl, c, tv: t.kind == .tv)
    }

    static func remember(_ t: Title, _ cert: String) async { await Certs.shared.put(t, cert) }
}

/// US certifications ("" = TMDB has none), 30 days.
actor Certs {
    static let shared = Certs()
    private struct Entry: Codable { let c: String; let at: Date }
    private var map: [String: Entry]
    private var running: [String: Task<String?, Never>] = [:]
    private let ttl: TimeInterval = 30 * 86_400

    init() { map = DreamStore.shared.get([String: Entry].self, "kids_certs") ?? [:] }

    private func key(_ t: Title) -> String { (t.kind == .tv ? "t" : "m") + "\(t.id)" }

    func cached(_ t: Title) -> String? {
        guard let e = map[key(t)], Date().timeIntervalSince(e.at) < ttl else { return nil }
        return e.c
    }

    func missing(_ list: [Title]) -> [Title] {
        var seen = Set<String>()
        return list.filter { cached($0) == nil && seen.insert($0.key).inserted }
    }

    func put(_ t: Title, _ cert: String) {
        map[key(t)] = Entry(c: cert.trimmingCharacters(in: .whitespaces), at: Date())
        if map.count % 10 == 0 { DreamStore.shared.set(map, "kids_certs") }
    }

    func fetch(_ t: Title) async -> String? {
        let k = key(t)
        if let r = running[k] { return await r.value }
        let task = Task<String?, Never> {
            let path = t.kind == .tv ? "/tv/\(t.id)/content_ratings" : "/movie/\(t.id)/release_dates"
            guard let u = URL(string: "\(Tmdb.api)\(path)?api_key=\(Keys.tmdb)") else { return nil }
            do {
                let o = try await Net.object(u)
                return Certs.usCert(o, tv: t.kind == .tv)
            } catch NetError.status(404, _) { return "" } catch { return nil }
        }
        running[k] = task
        let c = await task.value
        running[k] = nil
        if let c { put(t, c); DreamStore.shared.set(map, "kids_certs") }
        return c
    }

    /// US certification from release_dates (theatrical, digital, limited, physical first) or content_ratings.
    static func usCert(_ o: [String: Any], tv: Bool) -> String {
        for r in o["results"] as? [[String: Any]] ?? [] where r["iso_3166_1"] as? String == "US" {
            if tv { return (r["rating"] as? String ?? "").trimmingCharacters(in: .whitespaces) }
            return sortedDates(r["release_dates"] as? [[String: Any]] ?? [])
                .map { ($0["certification"] as? String ?? "").trimmingCharacters(in: .whitespaces) }.first { !$0.isEmpty } ?? ""
        }
        return ""
    }

    static func sortedDates(_ a: [[String: Any]]) -> [[String: Any]] {
        func rank(_ o: [String: Any]) -> Int { switch o["type"] as? Int { case 3: 0; case 4: 1; case 2: 2; case 5: 3; default: 4 } }
        return a.sorted { rank($0) < rank($1) }
    }
}
