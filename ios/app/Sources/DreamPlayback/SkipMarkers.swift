import Foundation
import DreamCore

/// Intro / recap / credits, ms (SkipSources.kt `Markers`).
struct Markers: Codable, Sendable, Hashable {
    var introStartMs: Int64?
    var introEndMs: Int64?
    var recapEndMs: Int64?
    var creditsStartMs: Int64?
    var hasAny: Bool { introEndMs != nil || recapEndMs != nil || creditsStartMs != nil }

    /// Field-wise: [a] wins, [b] fills gaps. The intro start / end pair always comes from one source.
    static func merge(_ a: Markers?, _ b: Markers?) -> Markers? {
        guard let a else { return b }
        guard let b else { return a }
        let introA = a.introEndMs != nil
        return Markers(introStartMs: introA ? a.introStartMs : b.introStartMs, introEndMs: introA ? a.introEndMs : b.introEndMs,
                       recapEndMs: a.recapEndMs ?? b.recapEndMs, creditsStartMs: a.creditsStartMs ?? b.creditsStartMs)
    }

    /// As the shared `SkipMarker`s (seconds). A recap after the opening (common in anime) starts where the intro ends.
    func markers(durationMs: Int64) -> [SkipMarker] {
        var out: [SkipMarker] = []
        if let e = introEndMs { out.append(SkipMarker(kind: .intro, start: Double(introStartMs ?? 0) / 1000, end: Double(e) / 1000)) }
        if let r = recapEndMs {
            let s: Int64 = (introEndMs.map { $0 < r && (introStartMs ?? 0) < r } ?? false) ? introEndMs! : 0
            out.append(SkipMarker(kind: .recap, start: Double(s) / 1000, end: Double(r) / 1000))
        }
        if let c = creditsStartMs { out.append(SkipMarker(kind: .credits, start: Double(c) / 1000, end: Double(max(durationMs, c)) / 1000)) }
        return out
    }
}

/// Where skip markers come from (SkipSources.kt). Verified sources (Oct 2026):
///  - TheIntroDB  GET api.theintrodb.org/v4/media?tmdb_id=&season=&episode=[&duration_ms=]; no key, 30 req / 10 s.
///  - AniSkip     GET api.aniskip.com/v2/skip-times/{malId}/{ep}?types[]=op&types[]=ed&types[]=recap&episodeLength=
///  - ARM         GET arm.haglund.dev/api/v2/themoviedb?id={tmdbShowId} -> MAL/Kitsu ids per anime entry.
///  - Kitsu       GET kitsu.io/api/edge/anime?filter[id]=a,b -> episodeCount + startDate (cour splits).
enum SkipSources {
    struct Http { let code: Int; let body: Data?; let retryAfter: Int }
    struct RateLimited: Error { let seconds: Int }
    struct Failed: Error {}

    static func get(_ url: String, accept: String = "application/json") async throws -> Http {
        guard let u = URL(string: url) else { throw Failed() }
        var r = URLRequest(url: u, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 9)
        r.setValue("DreamOS/1.0 (iPhone)", forHTTPHeaderField: "User-Agent")
        r.setValue(accept, forHTTPHeaderField: "Accept")
        let (data, resp) = try await DebridHttp.session.data(for: r)
        let h = resp as? HTTPURLResponse
        let retry = ["Retry-After", "X-UsageLimit-Reset", "X-RateLimit-Reset"].lazy.compactMap { h?.value(forHTTPHeaderField: $0).flatMap { Int($0) } }.first ?? 0
        return Http(code: h?.statusCode ?? 0, body: data, retryAfter: retry)
    }

    static func theIntroDbUrl(tmdbId: Int, imdb: String?, season: Int?, episode: Int?, durationMs: Int64) -> String? {
        let id: String
        if tmdbId > 0 { id = "tmdb_id=\(tmdbId)" }
        else if let imdb, imdb.range(of: "^tt\\d{6,9}$", options: .regularExpression) != nil { id = "imdb_id=\(imdb)" }
        else { return nil }
        let ep = season != nil && episode != nil ? "&season=\(season!)&episode=\(episode!)" : ""
        let dur = (5 * 60_000...6 * 3_600_000).contains(durationMs) ? "&duration_ms=\(durationMs)" : ""
        return "https://api.theintrodb.org/v4/media?\(id)\(ep)\(dur)"
    }

    /// nil = no data (404). Throws on network trouble or 429, so those aren't cached as misses.
    static func theIntroDb(_ url: String, durationMs: Int64) async throws -> Markers? {
        let r = try await get(url)
        if r.code == 429 { throw RateLimited(seconds: min(max(r.retryAfter, 10), 24 * 3600)) }
        if r.code == 404 { return nil }
        guard (200..<300).contains(r.code), let b = r.body else { throw Failed() }
        return parseTheIntroDb(JV(data: b), durationMs: durationMs)
    }

    static func parseTheIntroDb(_ o: JV, durationMs: Int64) -> Markers {
        func segs(_ name: String) -> [(Int64?, Int64?)] {
            (o[name].array ?? []).filter(\.isObject).map { ($0["start_ms"].isNull ? nil : $0["start_ms"].int64, $0["end_ms"].isNull ? nil : $0["end_ms"].int64) }
        }
        let intro = segs("intro").lazy.compactMap { plausibleIntro($0.0 ?? 0, $0.1, durationMs) }.first
        let recap = segs("recap").lazy.compactMap { plausibleRecap($0.0 ?? 0, $0.1) }.first
        let credits = pickCredits(segs("credits").compactMap(\.0), durationMs) ?? pickCredits(segs("preview").compactMap(\.0), durationMs)
        return Markers(introStartMs: intro?.0, introEndMs: intro?.1, recapEndMs: recap, creditsStartMs: credits)
    }

    static func aniSkip(mal: Int, episode: Int, durationMs: Int64) async throws -> Markers? {
        let url = "https://api.aniskip.com/v2/skip-times/\(mal)/\(episode)?types[]=op&types[]=ed&types[]=recap&episodeLength=\(durationMs > 0 ? durationMs / 1000 : 0)"
        let r = try await get(url.replacingOccurrences(of: "[]", with: "%5B%5D"))
        if r.code == 429 { throw RateLimited(seconds: min(max(r.retryAfter, 10), 3600)) }
        if r.code == 404 { return nil }
        guard (200..<300).contains(r.code), let b = r.body else { throw Failed() }
        return parseAniSkip(JV(data: b), durationMs: durationMs)
    }

    static func parseAniSkip(_ o: JV, durationMs: Int64) -> Markers? {
        guard o["found"].bool, let a = o["results"].array else { return nil }
        var intro: (Int64, Int64)?
        var recap: Int64?
        var credits: [Int64] = []
        for r in a {
            let iv = r["interval"]
            let s = Int64((iv["startTime"].double ?? -1) * 1000), e = Int64((iv["endTime"].double ?? -1) * 1000)
            if s < 0 || e <= s { continue }
            switch r["skipType"].string {
            case "op": if intro == nil { intro = plausibleIntro(s, e, durationMs) }
            case "recap": if recap == nil { recap = plausibleRecap(s, e) }
            case "ed": credits.append(s)
            default: break
            }
        }
        let m = Markers(introStartMs: intro?.0, introEndMs: intro?.1, recapEndMs: recap, creditsStartMs: pickCredits(credits, durationMs))
        return m.hasAny ? m : nil
    }

    struct AnimeEntry { let mal: Int; let kitsu: Int?; let tmdbSeason: Int? }

    static func armEntries(_ tmdbShowId: Int) async throws -> [AnimeEntry] {
        let r = try await get("https://arm.haglund.dev/api/v2/themoviedb?id=\(tmdbShowId)")
        if r.code == 404 { return [] }
        guard (200..<300).contains(r.code), let b = r.body else { throw Failed() }
        // TV series only (movies / OVAs / specials don't share TMDB's numbering).
        return (JV(data: b).array ?? []).filter { $0["media"].string == "TV" && $0["myanimelist"].int > 0 }.map {
            AnimeEntry(mal: $0["myanimelist"].int, kitsu: $0["kitsu"].int > 0 ? $0["kitsu"].int : nil,
                       tmdbSeason: $0["themoviedb-season"].exists ? $0["themoviedb-season"].int : nil)
        }
    }

    static func kitsuCounts(_ ids: [Int]) async throws -> [Int: (String, Int?)] {
        let r = try await get("https://kitsu.io/api/edge/anime?filter%5Bid%5D=\(ids.map(String.init).joined(separator: ","))&fields%5Banime%5D=episodeCount,startDate&page%5Blimit%5D=20",
                              accept: "application/vnd.api+json")
        guard (200..<300).contains(r.code), let b = r.body else { throw Failed() }
        var out: [Int: (String, Int?)] = [:]
        for d in JV(data: b)["data"].array ?? [] {
            guard let id = Int(d["id"].string) else { continue }
            let at = d["attributes"]
            let start = at["startDate"].string.isEmpty ? "9999" : at["startDate"].string
            out[id] = (start, at["episodeCount"].isNull ? nil : (at["episodeCount"].int > 0 ? at["episodeCount"].int : nil))
        }
        return out
    }

    /// Picks (malId, episode) for TMDB [season]/[episode]; [absolute] is the episode counted across TMDB's regular seasons.
    static func mapToMal(_ entries: [AnimeEntry], season: Int, episode: Int, absolute: Int,
                         counts: ([Int]) async throws -> [Int: (String, Int?)]) async throws -> (Int, Int)? {
        if entries.isEmpty || season < 1 || episode < 1 { return nil }
        if entries.count == 1 {
            let only = entries[0]
            if only.tmdbSeason == nil { return (only.mal, absolute) } // one long-running entry (One Piece, Naruto)
            return only.tmdbSeason == season ? (only.mal, episode) : nil
        }
        let same = entries.filter { $0.tmdbSeason == season }
        if same.count == 1 { return (same[0].mal, episode) }
        // Cour splits or TMDB folding several MAL entries into one season: walk them in air order.
        if entries.count > 20 || entries.contains(where: { $0.kitsu == nil }) { return nil }
        let info = try await counts(entries.map { $0.kitsu! })
        if info.count < entries.count { return nil }
        let ordered = entries.filter { info[$0.kitsu!]?.0 != "9999" }.sorted { info[$0.kitsu!]!.0 < info[$1.kitsu!]!.0 }
        var left = absolute
        for (i, e) in ordered.enumerated() {
            guard let n = info[e.kitsu!]?.1 else { return i == ordered.count - 1 ? (e.mal, left) : nil } // airing: must be the last
            if left <= n { return (e.mal, left) }
            left -= n
        }
        return nil
    }

    /// Intro: 5 s – 5 min long, ending in the first half (when the duration is known).
    static func plausibleIntro(_ s: Int64, _ e: Int64?, _ durationMs: Int64) -> (Int64, Int64)? {
        guard let e, s >= 0, (5_000...300_000).contains(e - s) else { return nil }
        if durationMs > 0 && e > durationMs / 2 { return nil }
        return (s, e)
    }

    /// Recap: ends in the first 12 minutes, 5 s – 10 min long. Returns its end.
    static func plausibleRecap(_ s: Int64, _ e: Int64?) -> Int64? {
        guard let e, (5_000...600_000).contains(e - s), e <= 12 * 60_000 else { return nil }
        return e
    }

    /// Credits start: the latest candidate in the second half of the runtime (credits over an anime OP don't trigger
    /// Up Next at 0:30). Without a duration, the latest one past 1 min.
    static func pickCredits(_ starts: [Int64], _ durationMs: Int64) -> Int64? {
        durationMs > 0 ? starts.filter { $0 >= durationMs / 2 && $0 < durationMs - 3_000 }.max() : starts.filter { $0 >= 60_000 }.max()
    }

    /// Credits estimate for autoplay timing only. Movies: the last 8 % (1–10 min). Episodes: the last 4 % (30–90 s).
    static func estimateCredits(_ kind: MediaKind, _ durationMs: Int64) -> Int64? {
        if durationMs < 5 * 60_000 { return nil }
        let tail = kind == .movie ? min(max(Int64(Double(durationMs) * 0.08), 60_000), 600_000) : min(max(Int64(Double(durationMs) * 0.04), 30_000), 90_000)
        return durationMs - tail
    }

    // MARK: Chapters (mpv reads the file's own; names to markers here)

    private static func rx(_ p: String) -> NSRegularExpression { try! NSRegularExpression(pattern: p) }
    nonisolated(unsafe) private static let intro = rx("^(intro|introduction|intro song|opening|opening credits|opening titles|opening theme|opening song|op|op ?\\d{1,2}|main titles?|main title sequence|title sequence|titles|theme song|theme|opening sequence)$")
    nonisolated(unsafe) private static let recap = rx("^(recap|previously|previously on\\b.*|last time\\b.*|story so far|the story so far|summary)$")
    nonisolated(unsafe) private static let credits = rx("^(credits|end credits|ending|ending credits|ending theme|ending song|ed|ed ?\\d{1,2}|closing credits|closing titles|end titles|outro|credits roll)$")
    nonisolated(unsafe) private static let preview = rx("^(preview|next episode|next episode preview|next time\\b.*|next on\\b.*|trailer next episode)$")

    static func classify(_ name: String) -> String? {
        let n = name.lowercased().replacingOccurrences(of: "[^a-z0-9]+", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
        if n.isEmpty { return nil }
        func m(_ r: NSRegularExpression) -> Bool { r.firstMatch(in: n, range: NSRange(n.startIndex..., in: n)) != nil }
        if m(intro) { return "intro" }
        if m(recap) { return "recap" }
        if m(credits) { return "credits" }
        if m(preview) { return "preview" }
        return nil
    }

    static func toMarkers(_ chs0: [SkipChapter], _ durationMs: Int64) -> Markers? {
        let chs = chs0.sorted { $0.start < $1.start }
        if chs.count < 2 { return nil }
        func ms(_ s: Double) -> Int64 { Int64(s * 1000) }
        func endOf(_ i: Int) -> Int64? {
            if let e = chs[i].end, e > chs[i].start { return ms(e) }
            if i + 1 < chs.count { return ms(chs[i + 1].start) }
            return durationMs > 0 ? durationMs : nil
        }
        var introP: (Int64, Int64)?
        var recapE: Int64?
        var cr: [Int64] = [], pv: [Int64] = []
        for (i, c) in chs.enumerated() {
            let s = ms(c.start)
            switch classify(c.title) {
            case "intro": if introP == nil, let p = plausibleIntro(s, endOf(i), durationMs), (8_000...200_000).contains(p.1 - p.0) { introP = p }
            case "recap": if recapE == nil { recapE = plausibleRecap(s, endOf(i)) }
            case "credits": cr.append(s)
            case "preview": pv.append(s)
            default: break
            }
        }
        let m = Markers(introStartMs: introP?.0, introEndMs: introP?.1, recapEndMs: recapE,
                        creditsStartMs: pickCredits(cr, durationMs) ?? pickCredits(pv, durationMs))
        return m.hasAny ? m : nil
    }
}

/// Finds intro / recap / credits for a title (SkipMarkers.kt). Per field, the first source that has it wins:
///  1. the stream's own chapters named Intro / Opening / Recap / Credits (exact for this very file; mpv reads them);
///  2. TheIntroDB (any show or movie, by TMDB id + season / episode);
///  3. AniSkip for anime (TMDB → MyAnimeList via ARM / Kitsu), only asked when 2 had no intro;
///  4. learned skips: where the viewer jumped past the intro on earlier episodes of the show;
///  5. a credits estimate (autoplay timing only).
final class SkipMarkers: @unchecked Sendable {
    static let shared = SkipMarkers()
    static let learnLeadMs: Int64 = 10_000
    private let lock = NSLock()
    private var blockedUntil: Int64 = 0 // TheIntroDB 429 back-off
    private var inFlight: [String: Task<Markers?, Never>] = [:]

    func keyOf(_ req: PlayRequest) -> String? {
        let id: String
        if req.title.id > 0 { id = "t\(req.title.id)" } else if let i = req.imdbId, !i.isEmpty { id = "i\(i)" } else { return nil }
        if req.title.kind == .tv {
            guard let s = req.season, let e = req.episode else { return nil }
            return "e:\(id):\(s):\(e)"
        }
        return "m:\(id)"
    }

    /// Durations within a couple of minutes are the same cut (TheIntroDB clusters within 5 min).
    private func cacheKey(_ key: String, _ durationMs: Int64) -> String { "\(key)@\(durationMs > 0 ? (durationMs + 60_000) / 120_000 : 0)" }

    func info(_ req: PlayRequest, durationMs: Int64, chapters: [SkipChapter]) async -> SkipInfo {
        guard let key = keyOf(req) else { return compose(req, nil, chapters, durationMs) }
        let ck = cacheKey(key, durationMs)
        let remote: Markers?
        if let c = RemoteCache.get(ck) { remote = c.markers }
        else {
            let t: Task<Markers?, Never> = lock.withLock {
                if let t = inFlight[ck] { return t }
                let t = Task.detached { await self.fetchRemote(req, ck, durationMs) }
                inFlight[ck] = t
                return t
            }
            remote = await t.value
            _ = lock.withLock { inFlight.removeValue(forKey: ck) }
        }
        let i = compose(req, remote, chapters, durationMs)
        plog.info("SkipMarkers: \(ck, privacy: .public) -> \(i.markers.count) markers learned=\(i.introLearned) estCredits=\(i.creditsEstimated)")
        return i
    }

    private func compose(_ req: PlayRequest, _ remote: Markers?, _ chapters: [SkipChapter], _ durationMs: Int64) -> SkipInfo {
        let fromFile = SkipSources.toMarkers(chapters, durationMs)
        var m = Markers.merge(fromFile, remote) ?? Markers()
        var learned = false
        if m.introEndMs == nil && req.title.kind == .tv, let l = Learned.get(req), durationMs <= 0 || l.1 < durationMs / 2 {
            m.introStartMs = max(l.0 - Self.learnLeadMs, 0); m.introEndMs = l.1; learned = true
        }
        var est = false
        if m.creditsStartMs == nil, let c = SkipSources.estimateCredits(req.title.kind, durationMs) { m.creditsStartMs = c; est = true }
        return SkipInfo(markers: m.markers(durationMs: durationMs), introLearned: learned, creditsEstimated: est)
    }

    /// Network sources, through the cache. nil when nobody has data (cached as a miss).
    private func fetchRemote(_ req: PlayRequest, _ ck: String, _ durationMs: Int64) async -> Markers? {
        if Mono.now() < lock.withLock({ blockedUntil }) { return nil }
        let ep = req.title.kind == .tv
        var m: Markers?
        if let url = SkipSources.theIntroDbUrl(tmdbId: req.title.id, imdb: req.imdbId, season: ep ? req.season : nil, episode: ep ? req.episode : nil, durationMs: durationMs) {
            do { m = try await SkipSources.theIntroDb(url, durationMs: durationMs) }
            catch let e as SkipSources.RateLimited {
                lock.withLock { blockedUntil = Mono.now() + Int64(e.seconds) * 1000 }
                plog.warning("TheIntroDB: rate limited for \(e.seconds) s")
                return nil
            } catch { return nil } // network trouble: not cached, asked again next time
        }
        var transient = false
        if ep && m?.introEndMs == nil && req.title.id > 0 {
            do { m = Markers.merge(m, try await animeMarkers(req, durationMs)) }
            catch { transient = true }
        }
        let found = (m?.hasAny == true) ? m : nil
        if found != nil || !transient { RemoteCache.put(ck, found) }
        return found
    }

    /// AniSkip for Japanese animation only (TMDB says so), mapped to a MyAnimeList entry + episode.
    private func animeMarkers(_ req: PlayRequest, _ durationMs: Int64) async throws -> Markers? {
        guard let season = req.season, let episode = req.episode, season >= 1 else { return nil }
        let show = try await Services.shared.catalog.details(kind: .tv, id: req.title.id)
        let animation = show.title.genreIds.contains(16) || show.genres.contains("Animation")
        guard animation, show.title.lang == "ja" else { return nil }
        var absolute = episode
        for s in show.seasons where (1..<season).contains(s.number) { absolute += s.episodeCount }
        let entries = try await SkipSources.armEntries(req.title.id)
        guard let pick = try await SkipSources.mapToMal(entries, season: season, episode: episode, absolute: absolute, counts: { try await SkipSources.kitsuCounts($0) }) else { return nil }
        let (mal, malEp) = pick
        plog.info("SkipMarkers: anime S\(season)E\(episode) -> MAL \(mal) ep \(malEp)")
        return try await SkipSources.aniSkip(mal: mal, episode: malEp, durationMs: durationMs)
    }

    func learn(_ req: PlayRequest, fromMs: Int64, toMs: Int64) { Learned.record(req, fromMs, toMs) }
    func confirmLearned(_ req: PlayRequest) { Learned.confirm(req) }

    /// Per show: where the viewer jumps past the intro. One record per show [start, end, confidence n, last used], at
    /// most 100 shows; the least recently used is dropped. A new jump within 25 s of the stored start (and of similar
    /// length) averages in and raises n; a different one lowers n first and only replaces the record when n reaches 0.
    private enum Learned {
        static let maxShows = 100
        static let lock = NSLock()
        static func showKey(_ req: PlayRequest) -> String? {
            if req.title.id > 0 { return "t\(req.title.id)" }
            if let i = req.imdbId, !i.isEmpty { return "i\(i)" }
            let n = req.title.name.lowercased().trimmingCharacters(in: .whitespaces)
            return n.isEmpty ? nil : "n" + n
        }
        static func all() -> [String: [Int64]] { DreamStore.shared.get([String: [Int64]].self, "learned_skips") ?? [:] }
        static func save(_ o0: [String: [Int64]]) {
            var o = o0
            while o.count > maxShows, let oldest = o.min(by: { ($0.value.count > 3 ? $0.value[3] : 0) < ($1.value.count > 3 ? $1.value[3] : 0) })?.key { o[oldest] = nil }
            DreamStore.shared.set(o, "learned_skips")
        }
        static func get(_ req: PlayRequest) -> (Int64, Int64)? {
            guard let k = showKey(req), let a = lock.withLock({ all()[k] }), a.count >= 2 else { return nil }
            return (a[0], a[1])
        }
        static func record(_ req: PlayRequest, _ from: Int64, _ to: Int64) {
            guard let k = showKey(req) else { return }
            lock.withLock {
                var o = all()
                let now = wallMs()
                let rec: [Int64]
                if let old = o[k], old.count >= 3 {
                    let s = old[0], e = old[1], n = old[2]
                    let similar = abs(s - from) <= 25_000 && abs((e - s) - (to - from)) <= 20_000
                    if similar { let w = min(n, 5); rec = [(s * w + from) / (w + 1), (e * w + to) / (w + 1), n + 1, now] }
                    else if n > 1 { rec = [s, e, n - 1, now] }
                    else { rec = [from, to, 1, now] }
                } else { rec = [from, to, 1, now] }
                o[k] = rec
                save(o)
                plog.info("SkipMarkers: learned intro for \(k, privacy: .public): \(rec.map(String.init).joined(separator: ","), privacy: .public)")
            }
        }
        static func confirm(_ req: PlayRequest) {
            guard let k = showKey(req) else { return }
            lock.withLock {
                var o = all()
                guard var a = o[k], a.count >= 4 else { return }
                a[2] += 1; a[3] = wallMs()
                o[k] = a
                save(o)
            }
        }
    }

    /// TheIntroDB / AniSkip results: at most 400 entries, least recently written dropped. Hits keep 21 days (the
    /// community data gets refined), misses 2 days. Errors and 429s are never stored.
    private enum RemoteCache {
        struct Item: Codable { let t: Int64; let m: Markers? }
        static let maxItems = 400
        static let hitTTL: Int64 = 21 * 24 * 3_600_000
        static let missTTL: Int64 = 2 * 24 * 3_600_000
        static let lock = NSLock()
        struct Hit { let markers: Markers? }
        static func get(_ key: String) -> Hit? {
            lock.withLock {
                guard let e = (DreamStore.shared.get([String: Item].self, "skip_markers") ?? [:])[key] else { return nil }
                return wallMs() - e.t < (e.m != nil ? hitTTL : missTTL) ? Hit(markers: e.m) : nil
            }
        }
        static func put(_ key: String, _ m: Markers?) {
            lock.withLock {
                var all = DreamStore.shared.get([String: Item].self, "skip_markers") ?? [:]
                all[key] = Item(t: wallMs(), m: m)
                if all.count > maxItems { for (k, _) in all.sorted(by: { $0.value.t < $1.value.t }).prefix(all.count - maxItems) { all[k] = nil } }
                DreamStore.shared.set(all, "skip_markers")
            }
        }
    }
}
