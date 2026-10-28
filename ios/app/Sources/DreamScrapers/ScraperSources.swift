import DreamCore
import Foundation

/// `SourceService`: the user's scraper repositories as torrents for Dream Core (the gather half of the Fire TV app's
/// NativeResolver). One scrape per title / episode is shared by everyone who asks for 30 minutes (an empty one is
/// redone after two minutes); a switch or install drops them.
final class ScraperSources: SourceService, @unchecked Sendable {
    static let shared = ScraperSources()

    private let lock = NSLock()
    private var gathers: [String: (at: Date, job: ScrapeJob)] = [:]
    private static let indexTTL: TimeInterval = 30 * 60

    func find(_ request: PlayRequest) async -> [StreamCandidate] { await find(request, budget: 21) }

    func isActive() async -> Bool { Scrapers.active }

    /// The candidates as providers answer: each element holds the ones just added (never repeats a hash). Ends when
    /// the scrape ends (at most ~22 s). `urgent`: a real Play (its scrape never waits for a lane slot, and a scrape
    /// that got no answer is redone at once, not after 20 s). A scrape the scrapers answered with nothing yields one
    /// empty batch; one that got no answer at all (busy, Python down, every provider failed) yields nothing, so Dream
    /// Core never reads it as "nothing out yet".
    func stream(_ request: PlayRequest) -> AsyncStream<[StreamCandidate]> { stream(request, urgent: false) }

    func stream(_ request: PlayRequest, urgent: Bool) -> AsyncStream<[StreamCandidate]> {
        AsyncStream { c in
            let t = Task {
                guard Scrapers.active, let job = await self.gather(request, play: true, light: false, urgent: urgent) else { c.finish(); return }
                var sent = Set<String>()
                var any = false
                func flush() {
                    let fresh = job.items().filter { sent.insert($0.hash).inserted }
                    if !fresh.isEmpty { any = true; c.yield(Self.candidates(fresh, request)) }
                }
                for await _ in job.changes() {
                    if Task.isCancelled { break }
                    flush()
                }
                flush()
                if !any && job.isDone && !job.failedAll { c.yield([]) }
                c.finish()
            }
            let timer = Task {
                try? await Task.sleep(nanoseconds: 22_000_000_000)
                t.cancel()
                c.finish()
            }
            c.onTermination = { _ in t.cancel(); timer.cancel() }
        }
    }

    /// Scrapes (or reuses a scrape) and waits for it to end, at most `budget` seconds.
    func find(_ request: PlayRequest, budget: TimeInterval, light: Bool = false) async -> [StreamCandidate] {
        guard Scrapers.active else { ScraperEngine.trace("find: scrapers not active"); return [] }
        guard let job = await gather(request, play: !light, light: light) else { ScraperEngine.trace("find: no job (no title info)"); return [] }
        ScraperEngine.trace("find: job \(job.id) \(Self.label(request)), waiting up to \(Int(budget)) s")
        let ended = await job.awaitDone(until: job.startedAt.addingTimeInterval(budget))
        ScraperEngine.trace("find: job \(job.id) \(ended ? "ended" : "still running at the deadline"), \(job.count) sources")
        return Self.candidates(job.items(), request)
    }

    /// The quick check for the availability pills (SourceService.quick): the two fastest providers, short limits, or a
    /// full scrape of the title that already ran. nil when the scrapers couldn't answer (busy, not started, every
    /// provider failed or timed out): no pill, asked again later. Empty: they answered and have nothing.
    func quick(_ request: PlayRequest) async -> [StreamCandidate]? {
        guard Scrapers.active else { return nil }
        // A full scrape of this title (a Play, a title page's look-ahead) with sources counts at once.
        if let full = lock.withLock({ usable(gathers[Self.key(request)]) }), full.count > 0 {
            return Self.candidates(full.items(), request)
        }
        guard let job = await gather(request, play: false, light: true) else { return nil }
        let ended = await job.awaitDone(until: max(job.startedAt.addingTimeInterval(16), Date().addingTimeInterval(7)))
        let items = job.items()
        if items.isEmpty && (!ended || job.failedAll) {
            ScraperEngine.trace("quick check: no answer for \(Self.label(request)) (\(ended ? "every provider failed" : "still running"))")
            return nil
        }
        return Self.candidates(items, request)
    }

    /// A look-ahead scrape that is in a real Play's way: stopped in Python and dropped (a later ask scrapes again).
    func cancel(_ request: PlayRequest) {
        let k = Self.key(request)
        guard let job = lock.withLock({ gathers.removeValue(forKey: k)?.job }), !job.isDone else { return }
        ScraperEngine.trace("job \(job.id) \(job.label): cancelled (a Play needs the room)")
        job.cancel()
    }

    /// A Play joined a look-ahead's scrape for `request`: it is urgent from now on (a slot at once if still waiting).
    func promote(_ request: PlayRequest) {
        guard let job = lock.withLock({ gathers[Self.key(request)]?.job }), !job.isDone, !job.urgent else { return }
        ScraperEngine.trace("job \(job.id) \(job.label): now a Play's (urgent)")
        job.promote()
    }

    /// The quick check's torrents for `request` when one ran lately (running or done), else nil. Nothing is started.
    func quickKnown(_ request: PlayRequest) -> [StreamCandidate]? {
        guard let j = lock.withLock({ usable(gathers[Self.key(request) + "#quick"]) }), j.count > 0 else { return nil }
        return Self.candidates(j.items(), request)
    }

    /// Providers switched or sources installed / removed: earlier scrapes no longer say what the viewer would get.
    func scrapersChanged() {
        let old = lock.withLock { () -> [ScrapeJob] in
            let j = gathers.values.map(\.job)
            gathers.removeAll()
            return j
        }
        old.filter { !$0.isDone }.forEach { $0.cancel() }
    }

    private static func key(_ r: PlayRequest) -> String {
        if r.title.kind == .tv, let s = r.season, let e = r.episode { return "tv:\(r.title.id):\(s):\(e)" }
        return "movie:\(r.title.id)"
    }

    private func usable(_ g: (at: Date, job: ScrapeJob)?, urgent: Bool = false) -> ScrapeJob? {
        guard let g else { return nil }
        let age = Date().timeIntervalSince(g.at)
        if age > Self.indexTTL || g.job.isCancelled { return nil }
        if g.job.isDone, g.job.count == 0, age > 120 { return nil }
        // One that got no answer at all (busy, Python down, every provider failed) is tried again after a moment;
        // a real Play tries again at once.
        if g.job.isDone, g.job.count == 0, g.job.failedAll, urgent || age > 20 { return nil }
        return g.job
    }

    /// The scrape for `request`: one running or recent, else a new one. `play`: its own lane (a Play's or a
    /// look-ahead's); `urgent`: a real Play (never waits for a slot; a running scrape it reuses is promoted);
    /// `light`: the quick check (two fast providers, short limits, no season packs).
    private func gather(_ request: PlayRequest, play: Bool, light: Bool, urgent: Bool = false) async -> ScrapeJob? {
        let k = Self.key(request) + (light ? "#quick" : "")
        if let j = lock.withLock({ usable(gathers[k], urgent: urgent) }) { if urgent { j.promote() }; return j }
        let episode = request.title.kind == .tv
        if episode && (request.season == nil || request.episode == nil) { return nil }
        // Alternative titles / the episode's air date: a Play waits at most a moment for them.
        let t0 = Date()
        let info = await ScraperInfo.json(for: request, wait: play ? 1.2 : 2.5)
        ScraperEngine.trace("gather: title info for \(Self.label(request)) in \(Int(Date().timeIntervalSince(t0) * 1000)) ms\(info == nil ? " (none)" : "")")
        guard let info else { return nil }
        return lock.withLock {
            if let j = usable(gathers[k], urgent: urgent) { if urgent { j.promote() }; return j }
            let refs = light ? Scrapers.quickRefs() : Scrapers.enabledRefs()
            let job = ScraperEngine.start(infoJSON: info, refs: refs, label: Self.label(request), light: light, play: play, urgent: urgent,
                                          timeout: light ? 5 : 20, perProvider: light ? 4 : 12, packs: episode && !light)
            gathers[k] = (Date(), job)
            if gathers.count > 40 { gathers = gathers.filter { Date().timeIntervalSince($0.value.at) < Self.indexTTL } }
            return job
        }
    }

    private static func label(_ r: PlayRequest) -> String {
        if let s = r.season, let e = r.episode { return "\(r.title.name) S\(s)E\(e)" }
        return r.title.name
    }

    // MARK: Candidates

    static func candidates(_ items: [ScrapeItem], _ request: PlayRequest) -> [StreamCandidate] {
        items.map { i in
            StreamCandidate(hash: i.hash, torrentName: i.name, sizeBytes: i.pack ? 0 : i.bytes, quality: quality(i),
                            seeders: i.seeders, origin: Scrapers.providerName(i.provider), cached: nil)
        }
    }

    /// "4K" | "1080p" | "720p" | "SD" from the provider's label, else the release name. CAM-type labels are "SD"
    /// (the name still says CAM for the ranking).
    static func quality(_ i: ScrapeItem) -> String {
        switch i.quality.uppercased() {
        case "4K", "2160P": return "4K"
        case "1080P": return "1080p"
        case "720P": return "720p"
        case "CAM", "SCR", "TELESYNC", "TS": return "SD"
        default:
            let t = i.name.uppercased()
            if has(t, "2160P", "4K", "UHD") { return "4K" }
            if has(t, "1080P") { return "1080p" }
            if has(t, "720P") { return "720p" }
            return "SD"
        }
    }

    /// Whole-token match (letters/digits on either side don't count).
    private static func has(_ text: String, _ words: String...) -> Bool {
        words.contains { w in text.firstMatch("(?<![A-Z0-9])" + NSRegularExpression.escapedPattern(for: w) + "(?![A-Z0-9])") != nil }
    }
}

/// What a scraper module expects from its caller: imdb, tmdb, title, localtitle, aliases, year, and for an episode
/// tvshowtitle, season, episode, premiered (its air date), tvdb, total_seasons. Built from TMDB (Scrapers.kt `info`).
enum ScraperInfo {
    private final class Cache: @unchecked Sendable {
        let lock = NSLock()
        var map: [String: (at: Date, json: String)] = [:]
    }
    private static let cache = Cache()

    static func json(for r: PlayRequest, wait: TimeInterval) async -> String? {
        let ep = r.title.kind == .tv
        let key = ep ? "tv:\(r.title.id):\(r.season ?? 0):\(r.episode ?? 0)" : "movie:\(r.title.id)"
        if let hit = cache.lock.withLock({ cache.map[key] }), Date().timeIntervalSince(hit.at) < 6 * 3600 { return hit.json }
        let type = ep ? "tv" : "movie"
        let id = r.title.id
        async let detailsTask = tmdb("/\(type)/\(id)", ["append_to_response": "external_ids,alternative_titles"], timeout: wait)
        async let episodeTask: Data? = ep ? tmdb("/tv/\(id)/season/\(r.season ?? 1)/episode/\(r.episode ?? 1)", [:], timeout: wait) : nil
        let d = object(await detailsTask)
        let e = object(await episodeTask)
        let ext = d?["external_ids"] as? [String: Any]
        guard let imdb = r.imdbId ?? (d?["imdb_id"] as? String) ?? (ext?["imdb_id"] as? String), !imdb.isEmpty else {
            PythonRuntime.log.notice("Scrapers: no IMDb id for \(r.title.name, privacy: .public)")
            return nil
        }
        var o: [String: Any] = ["imdb": imdb, "tmdb": id > 0 ? String(id) : ""]
        let title = ((d?[ep ? "name" : "title"] as? String).flatMap { $0.isEmpty ? nil : $0 }) ?? r.title.name
        let original = d?[ep ? "original_name" : "original_title"] as? String ?? ""
        var year = r.title.year
        if year.isEmpty { year = String((d?[ep ? "first_air_date" : "release_date"] as? String ?? "").prefix(4)) }
        var aliases: [[String: String]] = []
        var seen: Set<String> = [title.lowercased()]
        func alias(_ t: String, _ country: String) {
            if !t.trimmingCharacters(in: .whitespaces).isEmpty, seen.insert(t.lowercased()).inserted, aliases.count < 12 {
                aliases.append(["title": t, "country": country])
            }
        }
        alias(original, ((d?["origin_country"] as? [String])?.first ?? r.title.originCountry.first ?? "").lowercased())
        if let alt = d?["alternative_titles"] as? [String: Any], let list = (alt["titles"] ?? alt["results"]) as? [[String: Any]] {
            for t in list {
                let c = t["iso_3166_1"] as? String ?? ""
                if ["US", "GB", "CA", "AU", "NZ", "IE"].contains(c) { alias(t["title"] as? String ?? "", c.lowercased()) }
            }
        }
        o["year"] = year
        o["aliases"] = aliases
        if ep {
            let epName = r.episodeName ?? (e?["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "Episode \(r.episode ?? 0)"
            o["tvshowtitle"] = title
            o["title"] = epName
            o["localtitle"] = title
            o["season"] = String(r.season ?? 0)
            o["episode"] = String(r.episode ?? 0)
            o["premiered"] = e?["air_date"] as? String ?? ""
            o["tvdb"] = (ext?["tvdb_id"] as? NSNumber).flatMap { $0.intValue > 0 ? String($0.intValue) : nil } ?? ""
            if let n = (d?["number_of_seasons"] as? NSNumber)?.intValue, n > 0 { o["total_seasons"] = n }
        } else {
            o["title"] = title
            o["localtitle"] = title
        }
        let text = ScraperEngine.json(o)
        if d != nil && (!ep || e != nil) {
            cache.lock.withLock {
                cache.map[key] = (Date(), text)
                if cache.map.count > 60 { cache.map = cache.map.filter { Date().timeIntervalSince($0.value.at) < 3600 } }
            }
        }
        return text
    }

    private static func object(_ d: Data?) -> [String: Any]? {
        d.flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] }
    }

    private static func tmdb(_ path: String, _ query: [String: String], timeout: TimeInterval) async -> Data? {
        let key = Keys.tmdb
        guard !key.isEmpty, var c = URLComponents(string: "https://api.themoviedb.org/3" + path) else { return nil }
        var items = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        let bearer = key.count > 40
        if !bearer { items.append(URLQueryItem(name: "api_key", value: key)) }
        c.queryItems = items
        guard let url = c.url else { return nil }
        var req = URLRequest(url: url)
        req.timeoutInterval = max(1, timeout)
        if bearer { req.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization") }
        return try? await Net.data(req)
    }
}
