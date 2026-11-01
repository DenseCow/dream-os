import Foundation
import DreamCore

/// A profile's taste (Taste.kt on the Fire TV): what it finishes, keeps on My List and opens, turned into genre,
/// language and era affinities that rank every discovery row and choose Home's "for you" rows. Positive signals
/// only: finishing, saving, opening (and Trakt's history, which lands in the watch history). Nothing is learned from
/// a title that was started and left (owner's rule): taste is what you liked, never what you didn't finish.
///
/// Built in the background from local data (plus a cached details call for seeds whose genres aren't known) and
/// kept in DreamStore per profile, so a page reads the last snapshot at once and never waits on it (the instant rule).
public struct TasteProfile: Codable, Sendable {
    /// TMDB genre id -> 0...1 (1 = the genre this profile watches most).
    public var genre: [Int: Double] = [:]
    /// Original language -> 0...1.
    public var lang: [String: Double] = [:]
    /// Decade (1990, 2000, ...) -> 0...1.
    public var era: [Int: Double] = [:]
    /// Share of the signal that is shows (0.5 = even).
    public var shows: Double = 0.5
    /// 0 = nothing known (cold start: popularity and quality alone), 1 = a well-known taste.
    public var strength: Double = 0
    public var signature: String = ""
    public var builtAt: Date = .distantPast

    public static let cold = TasteProfile()

    /// How much this profile likes [t]: the mean of its genres' affinities, its language and its era (0...1).
    public func affinity(_ t: Title) -> Double {
        var g = 0.0
        if !t.genreIds.isEmpty {
            g = t.genreIds.reduce(0.0) { $0 + (genre[$1] ?? 0) } / Double(t.genreIds.count)
        }
        let l = t.lang.isEmpty ? 0 : (lang[t.lang] ?? 0)
        let e = Int(t.year).map { era[$0 / 10 * 10] ?? 0 } ?? 0
        return min(1, 0.7 * g + 0.2 * l + 0.1 * e)
    }

    /// Affinity for a Browse category (its movie and TV genre ids), the higher of the two.
    public func affinity(movie: Int, tv: Int) -> Double {
        max(movie > 0 ? (genre[movie] ?? 0) : 0, tv > 0 ? (genre[tv] ?? 0) : 0)
    }
}

public enum Taste {
    /// Rows that keep their own order: charts, dated rows and the viewer's own lists.
    private static let keepOrder = ["Trending", "Top 10", "Coming Soon", "Continue Watching", "My List", "New Episodes for You", "In Theaters"]

    static func ranks(_ row: String) -> Bool { !keepOrder.contains { row.hasPrefix($0) } }

    // MARK: scoring

    /// TMDB's score steadied by its vote count (a Bayesian average toward 6.4): 7.9 from 90 votes reads about 7.1,
    /// 7.6 from 10,000 stays 7.6. Below 100 votes a title is mostly its prior.
    static func quality(_ t: Title) -> Double {
        let m = t.kind == .movie ? AlgoRules.qualityVotesMovie : AlgoRules.qualityVotesTv
        let v = Double(max(0, t.votes))
        let q = t.rating > 0 && v > 0 ? (v * t.rating + m * AlgoRules.qualityPrior) / (v + m) : AlgoRules.qualityUnknown
        return min(1, max(0, (q - AlgoRules.qualityLow) / AlgoRules.qualitySpan))
    }

    private static func recency(_ t: Title) -> Double {
        guard t.date.count == 10 else { return 0 }
        if t.date >= DreamTime.yearsAgo(1) { return AlgoRules.fresh1y }
        if t.date >= DreamTime.yearsAgo(3) { return AlgoRules.fresh3y }
        if t.date >= DreamTime.yearsAgo(10) { return AlgoRules.fresh10y }
        return 0
    }

    /// Java's String.hashCode, which is specified and so is the same number on every build. Swift's own hashing is
    /// salted per process, so it would give each phone a different rotation and a different day's page.
    static func javaHash(_ s: String) -> Int32 {
        var h: Int32 = 0
        for u in s.utf16 { h = h &* 31 &+ Int32(u) }
        return h
    }

    /// A title's place in the day's rotation, 0...1: fixed for `key` on `day`, unrelated to the day before. The same
    /// all day (nothing moves while anyone looks), another order tomorrow.
    static func rotation(_ key: String, day: Int) -> Double {
        var h = javaHash(key) &* 31 &+ Int32(truncatingIfNeeded: day)
        h ^= Int32(bitPattern: UInt32(bitPattern: h) >> 16); h = h &* 0x45d9f3b
        h ^= Int32(bitPattern: UInt32(bitPattern: h) >> 16); h = h &* 0x45d9f3b
        h ^= Int32(bitPattern: UInt32(bitPattern: h) >> 16)
        return Double(h & 0xFFFF) / 65535.0
    }

    /// The day the rotation turns on, in the viewer's own zone. Year times 400 plus the day of the year, which is
    /// what the Fire Stick works out: the three builds have to land on the same number or they rotate on different
    /// days, and then "the same all day" is true on each build but of a different day's order.
    static func dayNumber(_ now: Date = Date()) -> Int {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        let year = cal.component(.year, from: now)
        let day = cal.ordinality(of: .day, in: .year, for: now) ?? 1
        return year * 400 + day
    }

    /// A page from 1 to `pages` that stays the same all day for `row` and moves on tomorrow.
    static func dailyPage(_ row: String, pages: Int, day: Int? = nil) -> Int {
        let d = day ?? dayNumber()
        let h = Int(javaHash(row))
        return 1 + ((d &* 31 &+ h) %% pages)
    }

    /// The page a row fetches after its first: 2 to 1 + deeperPages, the same all day for `row` and another tomorrow,
    /// so a row with no daily page of its own still changes day to day at no extra call.
    static func rotatingPage(_ row: String, day: Int? = nil) -> Int {
        1 + dailyPage(row, pages: AlgoRules.deeperPages, day: day)
    }

    /// [list] re-ranked for [p]: the list's own order (TMDB's intent: popularity, trending, votes) still counts for
    /// half, taste for a quarter (scaled by how well it is known), steadied quality for a fifth and freshness a
    /// little. Then franchises are spread out (one "John Wick" up front, the rest later). Stable: equal scores
    /// keep the list's order, so a cold profile sees popularity tempered by quality, never a shuffle.
    public static func rank(_ p: TasteProfile, _ list: [Title], artFirst: Bool = false, day: Int? = nil) -> [Title] {
        guard list.count > 1 else { return list }
        let d = day ?? dayNumber()
        let n = Double(max(1, list.count - 1))
        let scored = list.enumerated().map { (i, t) -> (Title, Double, Int) in
            (t, score(p, t, i: i, n: n, artFirst: artFirst, day: d), i)
        }.sorted { $0.1 != $1.1 ? $0.1 > $1.1 : $0.2 < $1.2 }.map(\.0)
        return spread(scored)
    }

    /// One title's score in `rank`: `i` of a list whose last index is `n`.
    static func score(_ p: TasteProfile, _ t: Title, i: Int, n: Double, artFirst: Bool, day: Int) -> Double {
        AlgoRules.wOrder * (1 - Double(i) / n)
            + AlgoRules.wTaste * p.strength * p.affinity(t)
            + AlgoRules.wQuality * quality(t)
            + AlgoRules.wFresh * recency(t)
            + AlgoRules.dayRotation * rotation(t.key, day: day)
            - (artFirst && (t.backdropPath ?? "").isEmpty ? AlgoRules.artPenalty : 0)
    }

    /// Titles of one franchise at least `gap` cards apart where the list allows it: each slot takes the best
    /// remaining title whose stem was last placed `gap` or more slots ago, else the one placed longest ago (a row of
    /// eight entries of one saga still shows every one). Titles with no shared stem keep their order; nothing is
    /// dropped, where the old rule sent every repeat to the end of the row.
    static func spread(_ list: [Title], gap: Int = AlgoRules.spreadGap) -> [Title] {
        guard list.count > 2, gap > 1 else { return list }
        let stems = list.map { stem($0.name) }
        if Set(stems).count == stems.count { return list }
        var out: [Title] = []
        out.reserveCapacity(list.count)
        var left = Array(list.indices)
        var lastAt: [String: Int] = [:]
        while !left.isEmpty {
            let slot = out.count
            var pick = -1, oldest = Int.max, oldestIdx = 0
            for (k, i) in left.enumerated() {
                guard let at = lastAt[stems[i]] else { pick = k; break }
                if slot - at >= gap { pick = k; break }
                if at < oldest { oldest = at; oldestIdx = k }
            }
            let i = left.remove(at: pick >= 0 ? pick : oldestIdx)
            lastAt[stems[i]] = slot
            out.append(list[i])
        }
        return out
    }

    private static let stopWords: Set<String> = ["the", "a", "an", "of", "and", "in", "on", "to", "for", "with", "at", "from", "by", "&"]

    /// A franchise key: the name up to a colon or dash, without a trailing number, then its first two telling words.
    /// "John Wick: Chapter 4" -> "john wick"; "Harry Potter and the Goblet of Fire" -> "harry potter"; "The Dark Knight
    /// Rises" -> "dark knight"; "House of the Dragon" -> "house dragon". Used only to spread a row, never to drop a title.
    static func stem(_ name: String) -> String {
        var s = name.lowercased()
        for sep in [":", " - ", " – ", " — "] { if let r = s.range(of: sep) { s = String(s[..<r.lowerBound]) } }
        s = s.replacingOccurrences(of: "\\s+(part|chapter|vol\\.?|volume|episode)\\s+[0-9ivx]+$", with: "", options: .regularExpression)
        s = s.replacingOccurrences(of: "\\s+([0-9]+|ii|iii|iv|v|vi|vii|viii|ix)$", with: "", options: .regularExpression)
        let words = s.split(whereSeparator: { $0.isWhitespace }).map(String.init).filter { !stopWords.contains($0) }
        return words.isEmpty ? s.trimmingCharacters(in: .whitespaces) : words.prefix(2).joined(separator: " ")
    }

    // MARK: "for you" rows

    /// Evocative row names per Browse category: rows that say why they're there.
    static let rowNames: [String: String] = [
        "Action": "Action-Packed Picks for You", "Adventure": "Adventures Worth Taking", "Comedy": "Laugh-Out-Loud Comedies",
        "Drama": "Gripping Dramas for You", "Crime": "Dark Crime Stories", "Thriller": "Edge-of-Your-Seat Thrillers",
        "Horror": "Horror Picks for You", "Sci-Fi": "Sci-Fi Worlds to Explore", "Fantasy": "Fantasy Epics for You",
        "Romance": "Love Stories for You", "Mystery": "Mysteries to Unravel", "Animation": "Animated Favorites for You",
        "Family": "Family Favorites for You", "Documentary": "Documentaries for You", "History": "Period Pieces for You",
        "War": "War Stories for You", "Western": "Westerns for You", "Music": "Music & Musicals for You",
        "Reality": "Reality Picks for You", "Kids": "Kids' Favorites",
    ]

    /// Up to [n] categories this profile would want a row of now: by affinity, with a nod to the time of day;
    /// Anime stays in its own sections and Kids is for kids profiles. Nothing for a cold profile.
    public static func forYou(_ p: TasteProfile, kids: Bool, moment: Set<Int>, n: Int = 2) -> [(name: String, row: String)] {
        guard p.strength >= 0.25 else { return [] }
        let picks = Discover.categories.filter { $0.name != "Anime" && ($0.name != "Kids" || kids) && rowNames[$0.name] != nil }
            .map { c -> (Discover.Category, Double) in
                let a = p.affinity(movie: c.movie, tv: c.tv)
                let boost = (c.movie > 0 && moment.contains(c.movie)) || (c.tv > 0 && moment.contains(c.tv)) ? 0.15 : 0
                return (c, a + boost)
            }
            .filter { $0.1 >= 0.35 }
            .sorted { $0.1 > $1.1 }
            .prefix(n)
        return picks.map { ($0.0.name, rowNames[$0.0.name]!) }
    }

    // MARK: the opened ledger

    private static let openedKey = "taste.opened"
    private struct Opened: Codable { var k: String; var g: [Int]; var l: String; var y: String; var at: Date }

    /// A title page opened (TitleDetailsModel.load): a weak signal, kept 60 days, at most 80.
    public static func noteOpened(_ t: Title) {
        Task.detached(priority: .utility) {
            var list = DreamStore.shared.get([Opened].self, openedKey, perProfile: true) ?? []
            list.removeAll { $0.k == t.key || Date().timeIntervalSince($0.at) > 60 * 86_400 }
            list.insert(Opened(k: t.key, g: t.genreIds, l: t.lang, y: t.year, at: Date()), at: 0)
            DreamStore.shared.set(Array(list.prefix(80)), openedKey, perProfile: true)
        }
    }

    // MARK: building

    private static let snapshotKey = "taste.v1"

    /// The snapshots in memory (per profile) and the profiles being rebuilt, behind one lock.
    private final class Memory: @unchecked Sendable {
        let lock = NSLock()
        var profiles: [String: TasteProfile] = [:]
        var building: Set<String> = []
    }
    private static let memory = Memory()

    /// The profile's taste now: the last snapshot (memory, then DreamStore), rebuilt behind when what it was built
    /// from changed. Never waits on the network.
    public static func current(history: [HistoryEntry], myList: [Title]) -> TasteProfile {
        let pid = DreamStore.shared.profileId
        let sig = signature(history, myList)
        let m = memory
        let have: TasteProfile? = m.lock.withLock { m.profiles[pid] } ?? DreamStore.shared.get(TasteProfile.self, snapshotKey, perProfile: true)
        if let have { m.lock.withLock { m.profiles[pid] = have } }
        let stale = have == nil || have!.signature != sig || Date().timeIntervalSince(have!.builtAt) > 6 * 3600
        if stale, m.lock.withLock({ m.building.insert(pid).inserted }) {
            Task.detached(priority: .utility) {
                let built = await build(history: history, myList: myList, signature: sig)
                m.lock.withLock { m.profiles[pid] = built; m.building.remove(pid) }
                if DreamStore.shared.profileId == pid { DreamStore.shared.set(built, snapshotKey, perProfile: true) }
            }
        }
        return have ?? .cold
    }

    private static func signature(_ history: [HistoryEntry], _ myList: [Title]) -> String {
        let newest = history.map(\.updatedAt).max() ?? .distantPast
        let opened = DreamStore.shared.get([Opened].self, openedKey, perProfile: true)?.first?.at ?? .distantPast
        return "\(history.count)|\(Int(newest.timeIntervalSince1970))|\(myList.count)|\(Int(opened.timeIntervalSince1970))"
    }

    /// Weighted seeds -> affinities. Finished titles count most, then the next episode queued, then anything watched
    /// a meaningful while (10 min), then a short play; a week's age halves nothing, ~45 days halves it. My List is
    /// a steady half signal; an opened title a small one.
    static func build(history: [HistoryEntry], myList: [Title], signature: String) async -> TasteProfile {
        let now = Date()
        var seeds: [(Title, Double)] = []
        var seen = Set<String>()
        /// Weight from opened pages alone, which shapes affinities in full but barely counts toward confidence.
        var opened = 0.0
        for e in history.sorted(by: { $0.updatedAt > $1.updatedAt }) where e.title.id > 0 && seen.insert(e.title.key).inserted {
            let isShow = e.title.kind == .tv
            let finishedEpisodes = isShow ? WatchedLedger.shared.episodeCount(show: e.title.id) : 0
            let cleared = e.hidden && e.progress < 0.92 && !e.upNext
            // Clearing a card is "not for me" only while it could still be a sample. Past keptAfterEpisodes finished
            // episodes it means "done with it for now", which is something the viewer liked, so the show keeps its
            // signal (owner, 2026-10-31).
            if cleared && !(isShow && finishedEpisodes >= AlgoRules.keptAfterEpisodes) { continue }
            let w: Double
            // STANDARDS section 5: taste comes from finished titles, My List, opened pages and Trakt, never from a
            // title started and left. A part-watched title used to seed 0.6 or 0.25 here, which is that rule broken.
            if e.progress >= 0.92 { w = isShow ? AlgoRules.seedEpisodes : AlgoRules.seedFinished }
            else if e.upNext || (isShow && finishedEpisodes > 0) { w = AlgoRules.seedEpisodes }
            else { continue }
            let age = max(0, now.timeIntervalSince(e.updatedAt)) / 86_400
            seeds.append((e.title, w * pow(0.5, age / AlgoRules.watchHalfLifeDays)))
            if seeds.count >= AlgoRules.historySeeds { break }
        }
        for t in myList where t.id > 0 && seen.insert(t.key).inserted { seeds.append((t, AlgoRules.seedSaved)) }
        for o in DreamStore.shared.get([Opened].self, openedKey, perProfile: true) ?? [] where seen.insert(o.k).inserted {
            let p = o.k.split(separator: ":")
            guard p.count == 2, let kind = MediaKind(rawValue: String(p[0])), let id = Int(p[1]) else { continue }
            let age = max(0, now.timeIntervalSince(o.at)) / 86_400
            seeds.append((Title(id: id, kind: kind, name: "", year: o.y, genreIds: o.g, lang: o.l),
                          AlgoRules.seedOpened * pow(0.5, age / AlgoRules.openedHalfLifeDays)))
            opened += AlgoRules.seedOpened * pow(0.5, age / AlgoRules.openedHalfLifeDays)
        }
        // Genres for seeds that came without them (history and My List keep little), from the cached details call.
        var filled = seeds
        await withTaskGroup(of: (Int, Title?).self) { g in
            for (i, s) in seeds.enumerated() where s.0.genreIds.isEmpty && s.1 >= 0.1 {
                g.addTask {
                    guard let o = try? await Tmdb.object("/\(s.0.kind.rawValue)/\(s.0.id)"), var t = Tmdb.parse(o, s.0.kind) else { return (i, nil) }
                    t.genreIds = (o["genres"] as? [[String: Any]])?.compactMap { $0["id"] as? Int } ?? []
                    return (i, t)
                }
            }
            for await (i, t) in g { if let t { filled[i].0 = t } }
        }
        var genre: [Int: Double] = [:], lang: [String: Double] = [:], era: [Int: Double] = [:]
        var total = 0.0, shows = 0.0
        for (t, w) in filled {
            total += w
            if t.kind == .tv { shows += w }
            for g in t.genreIds where g != 16 || !RowMix.isAnime(t) { genre[g, default: 0] += w }
            if !t.lang.isEmpty { lang[t.lang, default: 0] += w }
            if let y = Int(t.year), y > 1900 { era[y / 10 * 10, default: 0] += w }
        }
        func norm(_ m: [Int: Double]) -> [Int: Double] { let mx = m.values.max() ?? 0; return mx > 0 ? m.mapValues { $0 / mx } : [:] }
        let lmx = lang.values.max() ?? 0
        var p = TasteProfile()
        p.genre = norm(genre)
        p.lang = lmx > 0 ? lang.mapValues { $0 / lmx } : [:]
        p.era = norm(era)
        p.shows = total > 0 ? shows / total : 0.5
        // Diminishing returns, never a hard ceiling at six titles: about 0.22 from one fresh finished title, 0.78
        // from six, 0.95 from twelve. Browsing is not watching, so opened pages count toward confidence only up to
        // openedStrengthCap, however many pages were opened.
        let counted = max(0, total - opened) + min(opened, AlgoRules.openedStrengthCap)
        p.strength = counted <= 0 ? 0 : 1 - exp(-counted / AlgoRules.strengthScale)
        p.signature = signature
        p.builtAt = now
        return p
    }
}

/// Java's floorMod: the remainder takes the divisor's sign, so a negative hash still lands on a real page.
/// Swift's % keeps the dividend's sign and would ask for page 1 + a negative number.
infix operator %%: MultiplicationPrecedence
func %% (_ a: Int, _ n: Int) -> Int { let r = a % n; return r < 0 ? r + n : r }
