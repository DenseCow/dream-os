import Foundation
import CryptoKit
import DreamCore

/// themoviedb.org client (fire-home Tmdb.kt). `url` adds api_key, language, region and the page itself: never add
/// them again in a query (TMDB answers 400 to a repeated parameter and the row silently disappears).
/// Lists are cached in memory 30 minutes and identical requests in flight share one fetch.
enum Tmdb {
    static let api = "https://api.themoviedb.org/3"

    /// Streaming services by TMDB provider id (Tmdb.SERVICES; app packages dropped: Dream OS plays them all).
    struct Service: Sendable { let providerId: Int; let name: String }
    static let services: [Service] = [
        Service(providerId: 8, name: "Netflix"), Service(providerId: 9, name: "Prime Video"), Service(providerId: 119, name: "Prime Video"),
        Service(providerId: 386, name: "Peacock"), Service(providerId: 387, name: "Peacock"), Service(providerId: 337, name: "Disney+"),
        Service(providerId: 15, name: "Hulu"), Service(providerId: 1899, name: "HBO Max"),
        // Paramount+ is two plans on TMDB now (Premium, Essential); its old id 531 lists almost nothing.
        Service(providerId: 2303, name: "Paramount+"), Service(providerId: 2616, name: "Paramount+"),
        Service(providerId: 350, name: "Apple TV"), Service(providerId: 283, name: "Crunchyroll"), Service(providerId: 526, name: "AMC+"),
        Service(providerId: 73, name: "Tubi"), Service(providerId: 300, name: "Pluto TV"),
    ]

    static var hasKey: Bool { !Keys.tmdb.trimmingCharacters(in: .whitespaces).isEmpty }

    /// The full URL: [query] parts each start with "&"; repeats are folded by `single`.
    static func url(_ path: String, _ query: String = "", kids: String = "") -> URL? {
        let q = single(query + (path.hasPrefix("/discover/") ? kids : "") + NewReleases.animeClause(path, query))
        return URL(string: "\(api)\(path)?api_key=\(Keys.tmdb)&language=en-US&region=US\(q)")
    }

    /// [query] with each parameter once (Tmdb.single): genres and keywords left out add up, genres asked for AND together,
    /// a ".gte" floor takes the higher value and a ".lte" ceiling the lower; anything else, the last one wins.
    static func single(_ query: String) -> String {
        if query.isEmpty { return query }
        let parts = query.split(separator: "&").map(String.init).filter { !$0.isEmpty }
        let keys = parts.map { $0.components(separatedBy: "=")[0] }
        if keys.count == Set(keys).count { return query }
        func higher(_ a: String, _ b: String) -> Bool {
            if let x = Double(a), let y = Double(b) { return y > x }
            return b > a
        }
        var order: [String] = []
        var m: [String: String] = [:]
        for p in parts {
            let k = p.components(separatedBy: "=")[0]
            let v = p.contains("=") ? String(p[p.index(after: p.firstIndex(of: "=")!)...]) : ""
            guard let old = m[k] else { m[k] = v; order.append(k); continue }
            if k == "without_genres" || k == "without_keywords" {
                var seen = Set<String>()
                m[k] = (old.split(separator: ",") + v.split(separator: ",")).map(String.init).filter { !$0.isEmpty && seen.insert($0).inserted }.joined(separator: ",")
            } else if k == "with_genres" {
                if old.contains("|") || v.contains("|") { m[k] = v } else {
                    var seen = Set<String>()
                    m[k] = (old.split(separator: ",") + v.split(separator: ",")).map(String.init).filter { seen.insert($0).inserted }.joined(separator: ",")
                }
            } else if k.hasSuffix(".gte") { m[k] = higher(old, v) ? v : old }
            else if k.hasSuffix(".lte") { m[k] = higher(old, v) ? old : v }
            else { m[k] = v }
        }
        return order.map { "&\($0)=\(m[$0]!)" }.joined()
    }

    /// [path]'s titles without what the profile's rating limit leaves out (the cache keeps them all).
    static func list(_ path: String, _ query: String = "", type: MediaKind? = nil) async throws -> [Title] {
        let kids = await KidsFilter.discoverClause(path.hasPrefix("/discover/tv") ? .tv : .movie)
        guard let u = url(path, RowPaging.paged(query), kids: kids) else { return [] }
        let all = try await ListCache.shared.get(u) {
            let o = try await TmdbDisk.object(u)
            let results = o["results"] as? [[String: Any]] ?? []
            return results.compactMap { parse($0, type) }
        }
        return await KidsFilter.titles(all, trusted: path.hasPrefix("/discover/movie"))
    }

    static func trending() async throws -> [Title] { try await list("/trending/all/week") }

    static func search(_ q: String) async throws -> [Title] {
        try await list("/search/multi", "&query=" + enc(q))
    }

    static func enc(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? s
    }

    /// Lowercase letters and digits only.
    static func squash(_ s: String) -> String { s.lowercased().filter { $0.isLetter || $0.isNumber } }

    /// Search as people say titles (Tmdb.searchSpoken): a multi-word query is also searched with its words joined;
    /// exact matches (spaces and punctuation ignored) first, most voted first; the rest in TMDB's order.
    static func searchSpoken(_ q: String) async -> [Title] {
        let words = q.trimmingCharacters(in: .whitespaces).split(whereSeparator: { $0.isWhitespace }).map(String.init)
        var variants: [String] = []
        if (2...5).contains(words.count) {
            variants.append(words.joined())
            if words.count > 2 {
                for i in 0..<(words.count - 1) {
                    let v = (Array(words[0..<i]) + [words[i] + words[i + 1]] + Array(words[(i + 2)...])).joined(separator: " ")
                    if !variants.contains(v) { variants.append(v) }
                }
            }
        }
        async let plain = (try? search(q)) ?? []
        var extra: [[Title]] = Array(repeating: [], count: variants.count)
        await withTaskGroup(of: (Int, [Title]).self) { g in
            for (i, v) in variants.enumerated() { g.addTask { (i, (try? await search(v)) ?? []) } }
            for await (i, l) in g { extra[i] = l }
        }
        var seen = Set<String>()
        let all = (await plain + extra.flatMap { $0 }).filter { seen.insert($0.key).inserted }
        let want = squash(q)
        let exact = all.filter { squash($0.name) == want }.sorted { $0.votes > $1.votes }
        return exact + all.filter { squash($0.name) != want }
    }

    /// "brad pitt movies": that person's titles (Tmdb.searchPerson). nil when the words don't name a person.
    static func searchPerson(_ q: String) async -> SearchResults.Person? {
        let cleaned = q.trimmingCharacters(in: .whitespaces).lowercased().replacingOccurrences(of: "[^a-z0-9' ]", with: " ", options: .regularExpression)
        var words = cleaned.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        let kindWords: Set<String> = ["movies", "movie", "films", "film", "shows", "show", "series", "tv"]
        let filler: Set<String> = ["with", "starring", "by", "and", "the", "in", "from", "all", "best"]
        var movies = true, shows = true
        let hadKind = words.contains { kindWords.contains($0) }
        if hadKind {
            let wm = words.contains { ["movies", "movie", "films", "film"].contains($0) }
            let ws = words.contains { ["shows", "show", "series", "tv"].contains($0) }
            movies = wm || !ws; shows = ws || !wm
        }
        words = words.filter { !kindWords.contains($0) }
        while let f = words.first, filler.contains(f) { words.removeFirst() }
        while let l = words.last, filler.contains(l) { words.removeLast() }
        if words.count < 2 && !hadKind { return nil }
        let name = words.joined(separator: " ")
        if squash(name).count < 4 { return nil }
        guard let u = url("/search/person", "&query=" + enc(name)), let o = try? await Net.object(u),
              let found = o["results"] as? [[String: Any]] else { return nil }
        let want = squash(name)
        guard let person = found.prefix(3).first(where: { squash($0["name"] as? String ?? "") == want })
                ?? (hadKind ? found.first.flatMap { (($0["popularity"] as? Double) ?? 0) >= 2 ? $0 : nil } : nil),
              let pid = person["id"] as? Int else { return nil }
        let directs = person["known_for_department"] as? String == "Directing"
        guard let cu = url("/person/\(pid)/combined_credits"), let credits = try? await Net.object(cu),
              let list = credits[directs ? "crew" : "cast"] as? [[String: Any]] else { return nil }
        let skipGenres: Set<Int> = [10767, 10763]
        var seen = Set<String>()
        let titles = list.compactMap { o -> Title? in
            if directs && o["job"] as? String != "Director" { return nil }
            if (o["character"] as? String ?? "").range(of: "\\b(self|himself|herself|narrator|host)\\b", options: [.regularExpression, .caseInsensitive]) != nil { return nil }
            return parse(o, nil)
        }.filter { t in
            t.posterPath != nil && (t.kind == .movie && movies || t.kind == .tv && shows) && !t.genreIds.contains(where: skipGenres.contains) && t.votes >= 20
        }.filter { seen.insert($0.key).inserted }.sorted { $0.votes > $1.votes }.prefix(40)
        let allowed = await KidsFilter.titles(Array(titles))
        if allowed.isEmpty { return nil }
        let who = person["name"] as? String ?? name
        let kind = movies && shows ? "Movies & Shows" : (movies ? "Movies" : "Shows")
        return SearchResults.Person(name: who, heading: directs ? "\(kind) Directed by \(who)" : "\(kind) with \(who)", titles: allowed)
    }

    /// Popular titles on one service (US subscription), widely rated, from the last RECENT_YEARS years.
    static func onService(_ providerId: Int, _ type: MediaKind) async throws -> [Title] {
        try await list("/discover/\(type.rawValue)",
                       "&with_watch_providers=\(providerId)&watch_region=US&with_watch_monetization_types=flatrate&sort_by=popularity.desc" +
                       "&include_adult=false&vote_count.gte=\(type == .movie ? 150 : 60)&\(RowMix.dateKey(type)).gte=\(RowMix.yearsAgo(RowMix.recentYears))",
                       type: type)
    }

    /// One page of /discover with TMDB's totals; not cached (each page is read once).
    static func discover(_ type: MediaKind, _ query: String, page: Int) async throws -> DiscoverPage {
        let kids = await KidsFilter.discoverClause(type)
        guard let u = url("/discover/\(type.rawValue)", "\(query)&page=\(page)", kids: kids) else { throw DreamError("Something went wrong. Try again.") }
        let o = try await Net.object(u)
        let a = (o["results"] as? [[String: Any]] ?? []).compactMap { parse($0, type) }
        let titles = await KidsFilter.titles(a, trusted: type == .movie)
        return DiscoverPage(titles: titles, page: o["page"] as? Int ?? page, totalPages: min(500, o["total_pages"] as? Int ?? 1),
                            totalResults: o["total_results"] as? Int ?? titles.count)
    }

    /// Any TMDB object (cached 30 min), for details-style calls.
    static func object(_ path: String, _ query: String = "") async throws -> [String: Any] {
        guard let u = url(path, query) else { throw DreamError("Something went wrong. Try again.") }
        return try await ObjectCache.shared.get(u) { JSONBox(v: try await TmdbDisk.object(u)) }.v
    }

    static func str(_ o: [String: Any]?, _ k: String) -> String {
        guard let s = o?[k] as? String, s != "null" else { return "" }
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func path(_ o: [String: Any], _ k: String) -> String? {
        let s = str(o, k); return s.isEmpty ? nil : s
    }

    /// A list result as a Title (Tmdb.parse).
    static func parse(_ o: [String: Any], _ forced: MediaKind?) -> Title? {
        let kind = forced ?? MediaKind(rawValue: o["media_type"] as? String ?? "")
        guard let kind, let id = o["id"] as? Int else { return nil }
        let date = str(o, kind == .movie ? "release_date" : "first_air_date")
        return Title(id: id, kind: kind, name: str(o, kind == .movie ? "title" : "name"), overview: str(o, "overview"),
                     posterPath: path(o, "poster_path"), backdropPath: path(o, "backdrop_path"), year: String(date.prefix(4)),
                     rating: (o["vote_average"] as? Double) ?? Double(o["vote_average"] as? Int ?? 0), date: date.count >= 10 ? String(date.prefix(10)) : "",
                     votes: o["vote_count"] as? Int ?? 0, genreIds: (o["genre_ids"] as? [Int]) ?? ((o["genres"] as? [[String: Any]])?.compactMap { $0["id"] as? Int } ?? []),
                     lang: str(o, "original_language"), adult: o["adult"] as? Bool ?? false,
                     originCountry: o["origin_country"] as? [String] ?? [])
    }
}

/// 30-minute in-memory cache with shared in-flight fetches.
actor ListCache {
    static let shared = ListCache()
    private var store: [URL: (Date, [Title])] = [:]
    private var running: [URL: Task<[Title], Error>] = [:]

    func get(_ u: URL, _ make: @escaping @Sendable () async throws -> [Title]) async throws -> [Title] {
        if let hit = store[u], Date().timeIntervalSince(hit.0) < 1800 { return hit.1 }
        if let t = running[u] { return try await t.value }
        let t = Task { try await make() }
        running[u] = t
        defer { running[u] = nil }
        let v = try await t.value
        if store.count > 3000 { store.removeAll() }
        store[u] = (Date(), v)
        return v
    }
}

/// A JSON object handed across tasks (TMDB's parsed answers are never mutated after parsing).
struct JSONBox: @unchecked Sendable { let v: [String: Any] }

/// JSON objects cached 30 minutes (details, seasons, external ids).
actor ObjectCache {
    static let shared = ObjectCache()
    private var store: [URL: (Date, JSONBox)] = [:]
    private var running: [URL: Task<JSONBox, Error>] = [:]

    func get(_ u: URL, _ make: @escaping @Sendable () async throws -> JSONBox) async throws -> JSONBox {
        if let hit = store[u], Date().timeIntervalSince(hit.0) < 1800 { return hit.1 }
        if let t = running[u] { return try await t.value }
        let t = Task { try await make() }
        running[u] = t
        defer { running[u] = nil }
        let v = try await t.value
        if store.count > 2000 { store.removeAll() }
        store[u] = (Date(), v)
        return v
    }
}

/// TMDB's JSON answers on disk (Caches/DreamOS-tmdb), so a relaunch reads them back instead of asking again
/// (speed pass). Each kind of answer keeps its own lifetime; older files are pruned once a launch.
enum TmdbDisk {
    static let dir: URL = {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let d = base.appendingPathComponent("DreamOS-tmdb", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        Task.detached(priority: .background) { prune(d) }
        return d
    }()

    /// How long an answer stays good: charts and searches an hour, discover lists six hours, a show's details six
    /// hours (its next episode), a movie's details a day, ratings, ids, images and people a week.
    static func ttl(_ u: URL) -> TimeInterval {
        let p = u.path
        if p.contains("/trending/") || p.contains("/search/") { return 3600 }
        if p.contains("/discover/") { return 6 * 3600 }
        if p.hasSuffix("/release_dates") || p.hasSuffix("/content_ratings") || p.hasSuffix("/external_ids") || p.hasSuffix("/images")
            || p.contains("/person/") { return 7 * 86_400 }
        if p.contains("/season/") || p.contains("/tv/") { return 6 * 3600 }
        if p.contains("/movie/") { return 86_400 }
        return 6 * 3600
    }

    static func file(_ u: URL) -> URL {
        let h = SHA256.hash(data: Data(u.absoluteString.utf8)).map { String(format: "%02x", $0) }.joined()
        return dir.appendingPathComponent(h + ".json")
    }

    static func read(_ u: URL) -> Data? {
        let f = file(u)
        guard let at = (try? FileManager.default.attributesOfItem(atPath: f.path))?[.modificationDate] as? Date,
              Date().timeIntervalSince(at) < ttl(u) else { return nil }
        return try? Data(contentsOf: f)
    }

    static func write(_ u: URL, _ d: Data) {
        let f = file(u)
        Task.detached(priority: .utility) { try? d.write(to: f, options: .atomic) }
    }

    /// The answer from disk while it is good, else from TMDB (and kept).
    static func object(_ u: URL) async throws -> [String: Any] {
        if let d = read(u), let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] { return o }
        let d = try await Net.data(u)
        guard let o = (try JSONSerialization.jsonObject(with: d)) as? [String: Any] else { throw DreamError("Something went wrong. Try again.") }
        write(u, d)
        return o
    }

    /// Files older than a week go.
    private static func prune(_ d: URL) {
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(at: d, includingPropertiesForKeys: [.contentModificationDateKey]) else { return }
        for f in files {
            let at = (try? f.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
            if Date().timeIntervalSince(at) > 7 * 86_400 { try? fm.removeItem(at: f) }
        }
    }
}