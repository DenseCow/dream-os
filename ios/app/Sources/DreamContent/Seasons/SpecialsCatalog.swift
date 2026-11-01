import Foundation
import DreamCore

/// A show's specials, the one catalog every surface reads (fire-home `Specials.kt`). TMDB is the app's episode list,
/// but it has no record of many specials: a returning show's `/tv/{id}/season/0` answered 404 while the service was
/// already streaming a 30-minute special of it that day (2026-10-31). So TMDB's season 0 is read where it exists (it
/// carries the stills and overviews) and TVmaze's `?specials=1` list fills in the rest.
///
/// Each special is named by its **source id**, `"tmdb:<episode id>"` or `"tvmaze:<episode id>"`, and that is what the
/// watch history, the watched ledger and the new-episode surfaces remember. Its 1-based place in air order is only a
/// view: a source backfilling a special with an earlier day moves every later number, so the position is computed
/// when a wire form needs it (`numberOf`) and never stored.
///
/// Season 0 lives only at the three wire boundaries: TMDB's `/season/0` request here, the index's `{imdb}:0:{n}`
/// form, and release-name matching. Everything inside the app uses the kind and the source id.
///
/// A special's air day is enough to call it out: an announced evening airstamp never hides one that has been up since
/// the morning (STANDARDS section 5, a source decides, not the date).
actor SpecialsCatalog {
    static let shared = SpecialsCatalog()
    private static let ttl: TimeInterval = 3 * 3600
    private static let maxShows = 24

    private var cache: [Int: (at: Date, eps: [Episode])] = [:]
    private var running: [Int: Task<[Episode], Never>] = [:]

    /// Show `showId`'s specials in air order, empty when it has none. Cached for three hours.
    static func of(_ showId: Int) async -> [Episode] { await shared.of(showId) }

    /// The special answering to any of these source ids, or nil. Either form is accepted, because a special
    /// remembered before a source backfilled it carries the old id (`Episode.specialAlias`).
    static func named(_ showId: Int, anyOf ids: [String]) async -> Episode? {
        let want = ids.filter { !$0.isEmpty }
        guard !want.isEmpty else { return nil }
        return await of(showId).first { ep in ep.specialIds.contains { want.contains($0) } }
    }

    static func named(_ showId: Int, _ specialId: String) async -> Episode? { await named(showId, anyOf: [specialId]) }

    /// Its 1-based place in air order, 0 when the catalog doesn't have it. The wire forms' number, worked out when
    /// one is needed and never kept.
    static func numberOf(_ showId: Int, _ specialId: String) async -> Int {
        await named(showId, specialId)?.number ?? 0
    }

    func of(_ showId: Int) async -> [Episode] {
        guard showId > 0 else { return [] }
        if let hit = cache[showId], Date().timeIntervalSince(hit.at) < Self.ttl { return hit.eps }
        if let r = running[showId] { return await r.value }
        let t = Task { await Self.load(showId) }
        running[showId] = t
        let eps = await t.value
        running[showId] = nil
        cache[showId] = (Date(), eps)
        if cache.count > Self.maxShows { cache = cache.filter { Date().timeIntervalSince($0.value.at) < Self.ttl } }
        return eps
    }

    /// What was already looked up, no network (nil: not asked yet).
    func peek(_ showId: Int) -> [Episode]? {
        cache[showId].flatMap { Date().timeIntervalSince($0.at) < Self.ttl ? $0.eps : nil }
    }

    private static func load(_ showId: Int) async -> [Episode] {
        // TMDB answers 404 when a show has no season 0 at all, which is the usual case for a new special.
        async let tmdbTask = try? await Tmdb.object("/tv/\(showId)/season/0")
        async let mazeTask = AirTimes.specials(showId)
        let raw = (await tmdbTask)?["episodes"] as? [[String: Any]] ?? []
        let maze = await mazeTask ?? []
        let out = merge(raw, maze, showId: showId)
        if !out.isEmpty {
            Log.core.notice("Specials: show \(showId, privacy: .public) has \(out.count, privacy: .public) (\(raw.count, privacy: .public) from TMDB, \(out.count - raw.count, privacy: .public) more from tvmaze)")
        }
        return out
    }

    /// TMDB's season 0 plus the specials only TVmaze lists, in air order. The same special from both sources is one
    /// entry: TMDB's wins (it has the still and the overview) and takes TVmaze's parent season and exact instant.
    private static func merge(_ tmdb: [[String: Any]], _ maze: [AirTimes.Special], showId: Int) -> [Episode] {
        let today = DreamTime.todayYmd()
        var out: [Episode] = []
        for o in tmdb {
            let date = Tmdb.str(o, "air_date")
            var e = Episode(showId: showId, season: 0, number: o["episode_number"] as? Int ?? 0, name: Tmdb.str(o, "name"),
                            overview: Tmdb.str(o, "overview"), stillPath: Tmdb.path(o, "still_path"),
                            airDate: date.count == 10 ? date : "", runtime: o["runtime"] as? Int ?? 0)
            e.isSpecial = true
            e.specialId = (o["id"] as? Int).flatMap { $0 > 0 ? "tmdb:\($0)" : nil } ?? ""
            if let m = maze.first(where: { same($0, day: e.airDate, name: e.name) }) {
                e.season = m.parentSeason
                e.airsAt = m.at
                // Both ids, learned here and nowhere else: this special may have been remembered under TVmaze's
                // before TMDB listed it, and that mark and its row must keep counting.
                let maze = m.id > 0 ? "tvmaze:\(m.id)" : ""
                if e.specialId.isEmpty { e.specialId = maze } else { e.specialAlias = maze }
            }
            out.append(e)
        }
        for s in maze where !tmdb.contains(where: { same(s, day: Tmdb.str($0, "air_date"), name: Tmdb.str($0, "name")) }) {
            var e = Episode(showId: showId, season: s.parentSeason, number: 0, name: s.name, overview: plain(s.summary),
                            airDate: s.date, runtime: s.runtime, airsAt: s.at)
            e.isSpecial = true
            e.specialId = s.id > 0 ? "tvmaze:\(s.id)" : ""
            out.append(e)
        }
        // Air order, undated ones last. The number is that place, which is only what a wire form asks for.
        out.sort { a, b in
            let (da, db) = (a.airDate.isEmpty ? "9999" : a.airDate, b.airDate.isEmpty ? "9999" : b.airDate)
            if da != db { return da < db }
            return (a.airsAt ?? .distantPast) < (b.airsAt ?? .distantPast)
        }
        for i in out.indices {
            let fromTmdb = out[i].number
            out[i].number = i + 1
            if fromTmdb > 0 && fromTmdb != i + 1 { out[i].tmdbNumber = fromTmdb }
            // The day alone decides: an announced 9 PM airstamp never hides one that has been up all afternoon.
            out[i].upcoming = out[i].airDate.isEmpty || out[i].airDate > today
        }
        // Without a source id there is nothing to remember a special by, so it is left out rather than kept by number.
        return out.filter { !$0.specialId.isEmpty }
    }

    /// The same special from both sources. Names decide when both have a real one, because two specials can share a
    /// day (a holiday special in two parts) and matching on the day alone would collapse them into one. The day is
    /// the fallback only when a name is missing or is a placeholder like "Special 1".
    private static func same(_ s: AirTimes.Special, day: String, name: String) -> Bool {
        let a = norm(s.name), b = norm(name)
        if !generic(s.name) && !generic(name) { return !a.isEmpty && a == b }
        return !s.date.isEmpty && s.date == day
    }

    /// A name that identifies nothing: empty, or one of a source's own placeholders ("Special", "Special 2", "TBA").
    private static func generic(_ s: String) -> Bool {
        let t = s.trimmingCharacters(in: .whitespaces)
        if t.isEmpty || t == "null" { return true }
        return t.range(of: "^(special|episode|ep\\.?)\\s*#?\\d*$|^(tba|tbd|untitled)$",
                       options: [.regularExpression, .caseInsensitive]) != nil
    }

    private static func norm(_ s: String) -> String {
        s.lowercased().replacingOccurrences(of: "[^a-z0-9]", with: "", options: .regularExpression)
    }

    /// TVmaze summaries are HTML; a blurb wants plain words.
    private static func plain(_ html: String) -> String {
        var s = html.replacingOccurrences(of: "<[^>]*>", with: "", options: .regularExpression)
        for (from, to) in [("&nbsp;", " "), ("&amp;", "&"), ("&quot;", "\""), ("&#39;", "'"), ("&lt;", "<"), ("&gt;", ">")] {
            s = s.replacingOccurrences(of: from, with: to)
        }
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

extension ContentCatalog {
    func specials(showId: Int) async -> [Episode] { await SpecialsCatalog.of(showId) }
}
