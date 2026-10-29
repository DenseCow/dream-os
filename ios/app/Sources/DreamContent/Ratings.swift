import Foundation
import DreamCore

/// Scores (Ratings.kt): the Rotten Tomatoes critic score and the IMDb rating from OMDb (free key: 1,000 a day), the
/// tomatometer from rottentomatoes.com when OMDb has none (RtWeb), and the audience badge exactly as published:
/// "IMDb 6.5", else "TMDB 7.1" after 150 votes. No stars anywhere. Cached on disk (7 days recent, 60 old, 14 misses).
actor Ratings {
    static let shared = Ratings()
    private struct Entry: Codable { var rt: Int?; var imdb: String?; var at: Date; var released: String; var rtWeb: Int?; var rtWebAt: Date? }
    private var cache: [String: Entry] = DreamStore.shared.get([String: Entry].self, "ratings") ?? [:]
    private var tried = Set<String>()
    private var backoffUntil = Date.distantPast
    private var budget: (day: String, used: Int, web: Int) = ("", 0, 0)

    private static let audienceVotes = 150

    func scores(_ d: TitleDetails) async -> Scores {
        let t = d.title
        var e = cache[t.key]
        var asked = false
        if let id = d.imdbId, !Keys.omdb.isEmpty, !fresh(e), !tried.contains(id), Date() >= backoffUntil, spend() {
            tried.insert(id)
            asked = true
            if let r = await askOmdb(id) { e = Entry(rt: r.0, imdb: r.1, at: Date(), released: t.date, rtWeb: e?.rtWeb, rtWebAt: e?.rtWebAt) }
        }
        // The website's tomatometer when OMDb answered without one (details pages only, 80 a day).
        if var x = e, x.rt == nil, !(x.rtWebAt.map { Date().timeIntervalSince($0) < (x.rtWeb != nil ? 30 : 14) * 86_400 } ?? false), webSpend() {
            x.rtWeb = await RtWeb.lookup(t)
            x.rtWebAt = Date()
            e = x
            asked = true
        }
        // Saved only when something was asked: every hero slide and title page reads this (up to 4000 entries).
        if asked, let e { cache[t.key] = e; save() }
        let tomato = e?.rt ?? e?.rtWeb
        return Scores(tomatometer: tomato, audienceBadge: Self.audience(imdb: e?.imdb, t))
    }

    /// The audience score: IMDb's, else TMDB's once 150 people voted. nil when neither.
    static func audience(imdb: String?, _ t: Title) -> String? {
        if let i = imdb { return "IMDb \(i)" }
        guard t.rating > 0, t.votes >= audienceVotes else { return nil }
        return "TMDB " + String(format: "%.1f", t.rating)
    }

    private func fresh(_ e: Entry?) -> Bool {
        guard let e else { return false }
        let has = e.rt != nil || e.imdb != nil
        let recent = DreamTime.daysFromToday(e.released).map { $0 > -365 } ?? true
        let ttl: TimeInterval = !has ? 14 : (recent ? 7 : 60)
        return Date().timeIntervalSince(e.at) < ttl * 86_400
    }

    private func today() -> String { DreamTime.todayYmd() }
    private func spend() -> Bool {
        if budget.day != today() { budget = (today(), 0, 0) }
        if budget.used >= 1000 { return false }
        budget.used += 1; return true
    }
    private func webSpend() -> Bool {
        if budget.day != today() { budget = (today(), 0, 0) }
        if budget.web >= 80 { return false }
        budget.web += 1; return true
    }

    private func askOmdb(_ id: String) async -> (Int?, String?)? {
        guard let u = URL(string: "https://www.omdbapi.com/?i=\(Tmdb.enc(id))&apikey=\(Tmdb.enc(Keys.omdb))") else { return nil }
        guard let o = try? await Net.object(u) else { backoffUntil = Date().addingTimeInterval(120); return nil }
        if o["Response"] as? String == "True" {
            let rt = (o["Ratings"] as? [[String: Any]] ?? []).first { $0["Source"] as? String == "Rotten Tomatoes" }
                .flatMap { ($0["Value"] as? String).flatMap { Int($0.trimmingCharacters(in: CharacterSet(charactersIn: "%"))) } }.map { min(100, max(0, $0)) }
            let imdb = Double(o["imdbRating"] as? String ?? "").flatMap { $0 > 0 ? String(format: "%.1f", $0) : nil }
            return (rt, imdb)
        }
        let err = o["Error"] as? String ?? ""
        if err.localizedCaseInsensitiveContains("not found") { return (nil, nil) }
        if err.localizedCaseInsensitiveContains("limit") { budget.used = 1000 } else { backoffUntil = Date().addingTimeInterval(600) }
        return nil
    }

    private func save() {
        if cache.count > 4000 { cache = Dictionary(uniqueKeysWithValues: cache.sorted { $0.value.at > $1.value.at }.prefix(3000).map { ($0.key, $0.value) }) }
        DreamStore.shared.set(cache, "ratings")
    }
}

/// Rotten Tomatoes critic score from rottentomatoes.com's search page (RtWeb.kt). Any markup change finds nothing.
enum RtWeb {
    private static let site = "https://www.rottentomatoes.com/"
    private static let ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    static func lookup(_ t: Title) async -> Int? {
        guard !t.name.isEmpty, let html = await get(site + "search?search=" + Tmdb.enc(t.name)) else { return nil }
        let section = t.kind == .tv ? "tvSeries" : "movie"
        let want = norm(t.name)
        let year = Int(t.year)
        var matches: [(Int?, String?)] = [], exactYear: [(Int?, String?)] = []
        let chunks = html.components(separatedBy: "<search-page-result").dropFirst()
        for chunk in chunks {
            guard let close = chunk.firstIndex(of: ">"), attrs(String(chunk[..<close]))["type"] == section else { continue }
            for m in matchesOf("<search-page-media-row\\b([^>]*)>(.*?)</search-page-media-row>", chunk) {
                let a = attrs(m[1])
                guard let anchor = matchesOf("<a\\b([^>]*\\bslot=\"title\"[^>]*)>(.*?)</a>", m[2]).first else { continue }
                let text = anchor[2].replacingOccurrences(of: "<[^>]*>", with: "", options: .regularExpression)
                guard norm(unescape(text)) == want else { continue }
                let y = Int((t.kind == .tv ? a["startyear"] : a["releaseyear"]) ?? "")
                if let year { guard let y, abs(y - year) <= 1 else { continue } }
                let href = attrs(anchor[1])["href"].flatMap { $0.hasPrefix(site) ? $0 : nil }
                let row = (Int(a["tomatometerscore"] ?? "").flatMap { (0...100).contains($0) ? $0 : nil }, href)
                matches.append(row)
                if y == year { exactYear.append(row) }
            }
        }
        let pick: (Int?, String?)
        if matches.count == 1 { pick = matches[0] } else if exactYear.count == 1 { pick = exactYear[0] } else { return nil }
        if let s = pick.0 { return s }
        guard let page = pick.1, let body = await get(page),
              let m = matchesOf("\"criticsScore\":\\{[^{}]*?\"score\":\"(\\d{1,3})\"", body).first, let s = Int(m[1]), (0...100).contains(s) else { return nil }
        return s
    }

    private static func get(_ url: String) async -> String? {
        guard let u = URL(string: url) else { return nil }
        var r = URLRequest(url: u, timeoutInterval: 6)
        r.setValue(ua, forHTTPHeaderField: "User-Agent")
        r.setValue("text/html,application/xhtml+xml", forHTTPHeaderField: "Accept")
        r.setValue("en-US,en;q=0.9", forHTTPHeaderField: "Accept-Language")
        r.cachePolicy = .reloadIgnoringLocalCacheData
        guard let d = try? await Net.data(r) else { return nil }
        return String(data: d, encoding: .utf8)
    }

    private static func matchesOf(_ pattern: String, _ s: String) -> [[String]] {
        guard let re = try? NSRegularExpression(pattern: pattern, options: [.dotMatchesLineSeparators]) else { return [] }
        let ns = s as NSString
        return re.matches(in: s, range: NSRange(location: 0, length: ns.length)).map { m in
            (0..<m.numberOfRanges).map { m.range(at: $0).location == NSNotFound ? "" : ns.substring(with: m.range(at: $0)) }
        }
    }

    private static func attrs(_ tag: String) -> [String: String] {
        var out: [String: String] = [:]
        for m in matchesOf("([A-Za-z][\\w-]*)=\"([^\"]*)\"", tag) {
            out[m[1].replacingOccurrences(of: "-", with: "").lowercased()] = m[2].trimmingCharacters(in: .whitespaces)
        }
        return out
    }

    private static func norm(_ s: String) -> String {
        var x = s.lowercased().replacingOccurrences(of: "&", with: " and ")
            .replacingOccurrences(of: "[^\\p{L}\\p{N}]+", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
        if x.hasPrefix("the ") { x = String(x.dropFirst(4)) }
        return x.replacingOccurrences(of: " ", with: "")
    }

    private static func unescape(_ s: String) -> String {
        s.replacingOccurrences(of: "&amp;", with: "&").replacingOccurrences(of: "&#39;", with: "'").replacingOccurrences(of: "&apos;", with: "'")
            .replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
            .trimmingCharacters(in: .whitespaces)
    }
}
