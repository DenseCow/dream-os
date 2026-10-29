import Foundation
import DreamCore

/// The Dream Core Resolver (NativeResolver.kt, ported 1:1 minus the Fire Stick's browse / details-page pre-resolves):
/// Play to a direct debrid HTTPS link in a second or two. It is the only resolver: what it can't find, nothing plays.
///
/// The viewer's scrapers (`Services.shared.sources`) are asked for the title's torrents, which stream in as each
/// provider answers. The viewer's own source filters (`DreamFilters`) drop what they wouldn't play, the rest is ranked
/// (best allowed quality, a size that streams well, English, an MKV before an MP4, seeders) and the top `top` go to the
/// linked debrid services in turn (the viewer's order, by default Premiumize, TorBox, Real-Debrid, then AllDebrid; one
/// that starts streams clearly faster for this viewer goes first), which unlock the first one they have cached. A found
/// link is remembered for `linkTTL`.
///
/// Checking a torrent at AllDebrid or Real-Debrid adds it to the viewer's account (AllDebrid caps an account at 1000
/// magnets), so what a lookup may ask depends on why it runs (`DebridPurpose`): a real Play, the next episode and the
/// source list's top rows. AllDebrid at its magnet limit is skipped for the others.
public final class NativeResolver: @unchecked Sendable {
    public static let shared = NativeResolver()

    /// How many ranked candidates the services see: enough to almost always hit a cached one, one upload call.
    static let top = 10
    static let linkTTL: Int64 = 40 * 60_000
    /// A scrape (and so the ranking) for a title.
    static let indexTTL: Int64 = 30 * 60_000
    static let failedTTL: Int64 = 60 * 60_000
    static let gb: Int64 = 1 << 30
    /// How long Play waits for more sources once the first arrived, with none / one of the best allowed grade in.
    static let graceMs: Int64 = 1_200
    static let graceGoodMs: Int64 = 250
    /// The same for a look-ahead (nobody waiting): longer, for a better ranking, but never the whole 20 s scrape.
    static let aheadGraceMs: Int64 = 5_000
    static let aheadGraceGoodMs: Int64 = 2_000
    /// Time kept for the debrid step before a Play's budget ends.
    static let debridReserveMs: Int64 = 2_500
    static let maxListed = 80

    /// Why a Play's lookup came back empty: nothing playable at all (not out yet), none of it ready on the services,
    /// or the scrapers gave no answer at all (busy, not running, every provider failed): unknown, never "not out yet".
    public enum Miss: Sendable { case nothing, notReady, noAnswer }

    /// A link played with the viewer's filters set aside (nothing passed them): [cam] it's a camera / screener copy.
    struct Relaxed: Sendable { let hash: String; let cam: Bool }

    /// One lookup under way. [purpose] may be raised by a caller that joins it; [ran] is the purpose the debrid step
    /// actually used (nil: it never got there).
    final class Lookup: @unchecked Sendable {
        let lock = NSLock()
        private var _purpose: DebridPurpose
        private var _ran: DebridPurpose?
        let promise = Promise<StreamLink?>()
        let request: PlayRequest
        private var _deadline: Int64
        init(_ p: DebridPurpose, _ r: PlayRequest, deadline: Int64) { _purpose = p; request = r; _deadline = deadline }
        var purpose: DebridPurpose { get { lock.withLock { _purpose } } set { lock.withLock { _purpose = newValue } } }
        var ran: DebridPurpose? { get { lock.withLock { _ran } } set { lock.withLock { _ran = newValue } } }
        /// When this lookup gives up (`Mono.now()`), read live: a Play that joins a look-ahead's 30 s lookup brings
        /// its own budget with it, so the lookup ends when the Play's does, never after.
        var deadline: Int64 { lock.withLock { _deadline } }
        func tighten(to d: Int64) { lock.withLock { if d < _deadline { _deadline = d } } }
    }

    /// A scraped torrent with what ranking needs beyond `Candidate`. [text] is upper case.
    struct Parsed: Sendable {
        let c: Candidate
        let text: String
        let cam: Bool
        let dubbed: Bool
        let pack: Bool
        let provider: String?
        let lang: String?
    }

    /// Links unlocked side by side with a Play's (Instant Play's race): the player's next try when its link doesn't open.
    static let raceLinks = 3
    /// A later service in the viewer's order that answers within this of an earlier one still answering doesn't win yet.
    static let tieMs: Int64 = 250

    private let lock = NSLock()
    private var links: [String: (link: StreamLink, at: Int64)] = [:]
    private var alternates: [String: (links: [StreamLink], at: Int64)] = [:]
    private var failed: [String: [String: Int64]] = [:]
    /// Links that failed to play, by title key (the alternates skip these; `failed` skips torrents in the ranking).
    private var failedURLs: [String: Set<String>] = [:]
    /// A service whose check adds to the account (AllDebrid, Real-Debrid) joins the race this much later when a free
    /// cache check (Premiumize, TorBox) is in it: when the free one has a link by then, nothing is uploaded at all.
    static let uploadDelayMs: Int64 = 500
    private var imdbs: [String: String] = [:]
    private var inFlight: [String: Lookup] = [:]
    private var misses: [String: Miss] = [:]
    private var relaxedLinks: [String: Relaxed] = [:]
    private var gathers: [String: (job: Gather, at: Int64)] = [:]
    private var handedAt: [String: Int64] = [:]
    private var aheadRunning = Set<String>()
    /// Cellular Data Saver: titles whose remembered link was found under it (dropped when Wi-Fi is back), and when a
    /// lookup within its limit last found nothing ready (the remembered bigger link then plays at once for a while).
    private var saverKeys = Set<String>()
    private var saverMiss: [String: Int64] = [:]
    /// How long a Play waits for a copy within the saver's limit while a bigger remembered link is ready.
    static let saverWaitMs: Int64 = 4_500
    static let saverMissTTL: Int64 = 10 * 60_000

    private init() {
        _ = PlaybackPath.shared // the network monitor runs from the start (Cellular Data Saver, `networkChanged`)
        DebridLedger.shared.setInUse { [weak self] in
            guard let self else { return [] }
            let now = Mono.now()
            return self.lock.withLock { Set(self.links.values.filter { now - $0.at < 4 * 3_600_000 }.map(\.link.hash)) }
        }
        NotificationCenter.default.addObserver(forName: .dreamScrapersChanged, object: nil, queue: nil) { [weak self] _ in
            self?.scrapersChanged()
        }
    }

    // MARK: Public API

    /// A linked debrid account exists (without one nothing can play).
    public func available() -> Bool { DebridAccounts.anyLinked }

    /// The last Play lookup's `Miss` for [req], or nil (found, or the reason isn't certain).
    public func miss(_ req: PlayRequest) -> Miss? { lock.withLock { misses[key(req)] } }

    /// [link]'s note for the loading screen when it was played with the viewer's filters set aside.
    func relaxed(_ req: PlayRequest, _ link: StreamLink) -> Relaxed? {
        lock.withLock { relaxedLinks[key(req)].flatMap { $0.hash == link.hash ? $0 : nil } }
    }

    /// AllDebrid is at its magnet limit (remembered ~30 min): a play that found nothing elsewhere fails with a calm message.
    public func limitBlocked() -> Bool { DebridAccounts.get().allDebrid.map { AllDebridService.limited($0) } ?? false }

    /// The remembered link for [req], no network; nil if none / expired.
    public func peek(_ req: PlayRequest) -> StreamLink? {
        let now = Mono.now()
        return lock.withLock { links[key(req)].flatMap { now - $0.at < Self.linkTTL ? $0.link : nil } }
    }

    /// Forget a remembered link (it failed to play). Its torrent is also skipped for this title / episode for an hour,
    /// so the next `find` ("Try Another Source") lands on the next candidate.
    public func forget(_ req: PlayRequest) {
        let k = key(req)
        let f: StreamLink? = lock.withLock {
            guard let f = links.removeValue(forKey: k)?.link else { return nil }
            failed[k, default: [:]][f.hash] = Mono.now()
            failedURLs[k, default: []].insert(f.url.absoluteString)
            return f
        }
        guard let f else { return }
        plog.warning("Dream Core: \(self.label(req), privacy: .public): \(f.service.uppercased(), privacy: .public) \(f.quality, privacy: .public) \(Self.size(f.sizeBytes), privacy: .public) didn't play; skipped from now on")
        SourceMemory.shared.record(traits(k, f), ok: false, ms: nil)
        StreamCache.shared.forget(f.url)
        // What the failed link's service added for it goes now (AllDebrid's magnet, TorBox's torrent).
        service(f.service)?.discard([f.hash])
    }

    /// The next ready link for [req] after its link failed (`forget` first): one unlocked alongside it by the race,
    /// not failed, remembered as the title's link from now on. nil when none is left.
    public func nextLink(_ req: PlayRequest) -> StreamLink? {
        let k = key(req)
        let now = Mono.now()
        let next: (StreamLink, Int)? = lock.withLock {
            guard var a = alternates[k], now - a.at < Self.linkTTL else { alternates[k] = nil; return nil }
            // Skipped by link, not by torrent: the same torrent on another service is a different copy (another host),
            // and a host that failed is the usual reason for a switch.
            let bad = failedURLs[k] ?? []
            let cur = links[k]?.link.url.absoluteString
            while !a.links.isEmpty {
                let f = a.links.removeFirst()
                let u = f.url.absoluteString
                if bad.contains(u) || u == cur { continue }
                alternates[k] = a.links.isEmpty ? nil : a
                links[k] = (f, now)
                return (f, a.links.count)
            }
            alternates[k] = nil
            return nil
        }
        guard let (f, left) = next else { return nil }
        plog.warning("Dream Core: \(self.label(req), privacy: .public) → next source \(f.service.uppercased(), privacy: .public) \(f.quality, privacy: .public) \(Self.size(f.sizeBytes), privacy: .public) (\(left) more ready)")
        return f
    }

    /// Whether `nextLink` has one for [req] (nothing taken).
    public func hasAlternate(_ req: PlayRequest) -> Bool {
        let k = key(req)
        let now = Mono.now()
        return lock.withLock {
            guard let a = alternates[k], now - a.at < Self.linkTTL else { return false }
            let bad = failedURLs[k] ?? []
            let cur = links[k]?.link.url.absoluteString
            return a.links.contains { !bad.contains($0.url.absoluteString) && $0.url.absoluteString != cur }
        }
    }

    /// Links the race unlocked besides the one played: kept as the player's next tries, at most a few, no repeats.
    private func addAlternates(_ k: String, _ links: [StreamLink]) {
        if links.isEmpty { return }
        let now = Mono.now()
        lock.withLock {
            var a: (links: [StreamLink], at: Int64) = alternates[k].flatMap { now - $0.at < Self.linkTTL ? $0 : nil } ?? (links: [], at: now)
            // One entry per link (the same torrent from two services is two copies, each a try of its own).
            let cur = self.links[k]?.link.url.absoluteString
            for l in links where l.url.absoluteString != cur && !a.links.contains(where: { $0.url == l.url }) { a.links.append(l) }
            a.links = Array(a.links.prefix(6))
            a.at = now
            alternates[k] = a.links.isEmpty ? nil : a
            if alternates.count > 30 { alternates = alternates.filter { now - $0.value.at < Self.linkTTL } }
        }
    }

    /// The winner's first frame is up: alternates whose service added something for them (AllDebrid, TorBox) are
    /// dropped and cleaned up; the free ones (Premiumize, Real-Debrid) stay as later tries.
    private func settleAlternates(_ k: String, winner: StreamLink) {
        let drop: [StreamLink] = lock.withLock {
            guard var a = alternates[k] else { return [] }
            let d = a.links.filter { $0.service == "ad" || $0.service == "tb" || $0.hash == winner.hash }
            a.links.removeAll { l in d.contains { $0.hash == l.hash } }
            alternates[k] = a.links.isEmpty ? nil : a
            return d.filter { $0.hash != winner.hash }
        }
        for (svc, group) in Dictionary(grouping: drop, by: \.service) {
            service(svc)?.discard(group.map(\.hash))
        }
    }

    /// The linked service with key [key] for a Play, or nil.
    private func service(_ key: String) -> DebridService? {
        services(DebridAccounts.get(), .play).first { $0.key == key }
    }

    /// The learning traits of [link] for title [k]: from its scraped release when the scrape is still kept, else
    /// from the link itself.
    private func traits(_ k: String, _ link: StreamLink) -> [String] {
        let item = lock.withLock { gathers[k]?.job.items.first { $0.hash == link.hash } }
        if let item {
            return SourceMemory.traits(name: item.torrentName, fileName: item.fileName ?? link.fileName, provider: item.origin.isEmpty ? nil : item.origin,
                                       sizeBytes: item.pack == true ? 0 : (link.sizeBytes > 0 ? link.sizeBytes : item.sizeBytes))
        }
        return SourceMemory.traits(name: link.fileName, fileName: link.fileName, provider: nil, sizeBytes: link.sizeBytes)
    }

    /// The stream's start to disk (StreamCache) for a link found ahead of Play, with the resume window where the
    /// history says [req] starts.
    func warmStream(_ req: PlayRequest, _ link: StreamLink) {
        let start = req.fromStart ? 0 : (WatchHistory.shared.historyStartMs(req) ?? 0)
        let dur = WatchHistory.shared.recent().first { $0.key == req.title.key }?.durationMs ?? 0
        StreamCache.shared.warm(link, startMs: start, durationMs: dur, label: label(req))
    }

    /// Best cached stream for [req], or nil (no account, no scrapers, nothing cached, budget over). Concurrent calls for
    /// the same title / season / episode share ONE lookup. A found link is remembered ~40 min. Every caller waits at most
    /// its own [budgetMs]; a lookup that outlives its caller still finishes and remembers what it finds.
    public func find(_ req: PlayRequest, budgetMs: Int64 = 8_000, purpose: DebridPurposeKind = .play) async -> StreamLink? {
        await find(req, budgetMs: budgetMs, purpose: purpose.internal)
    }

    func find(_ req: PlayRequest, budgetMs: Int64 = 8_000, purpose: DebridPurpose) async -> StreamLink? {
        let k = key(req)
        var deadline = Mono.now() + budgetMs
        if purpose == .play { _ = lock.withLock { misses.removeValue(forKey: k) } }
        // Cellular Data Saver: a remembered link above the limit (an Instant Play / Wi-Fi find) waits while this lookup
        // (Play or look-ahead) looks for one within it, on the scrape already kept for the title (only the ranking and
        // the debrid step run again), at most `saverWaitMs` for a Play; it still plays when nothing within the limit
        // is ready (never "no streams"). Found nothing a moment ago: it plays at once.
        var spare: StreamLink?
        var saverCap: Int?
        if let p = peek(req) {
            guard let cap = DreamFilters.current().cellCap, qi(p.quality) < cap else { return p }
            let what = "the remembered \(p.quality) \(Self.size(p.sizeBytes)) link"
            if let at = lock.withLock({ saverMiss[k] }), Mono.now() - at < Self.saverMissTTL {
                plog.info("Sources: cellular saver: nothing within \(Self.capLabel(cap), privacy: .public) was ready for \(self.label(req), privacy: .public) \(Self.secs(Mono.now() - at), privacy: .public) ago; \(purpose == .play ? "playing" : "keeping", privacy: .public) \(what, privacy: .public)")
                return p
            }
            spare = p
            saverCap = cap
            if purpose == .play { deadline = min(deadline, Mono.now() + Self.saverWaitMs) }
            plog.info("Sources: cellular saver looks for a copy within \(Self.capLabel(cap), privacy: .public) for \(self.label(req), privacy: .public)\(purpose == .play ? "" : " (look-ahead)", privacy: .public); \(what, privacy: .public) waits")
        }
        if purpose == .play { preempt(k) }
        var res: StreamLink?
        for _ in 0..<2 {
            let mine = Lookup(purpose, req, deadline: deadline)
            let (l, started) = lock.withLock { () -> (Lookup, Bool) in
                if let l = inFlight[k] { return (l, false) }
                inFlight[k] = mine
                return (mine, true)
            }
            if started {
                Task.detached(priority: .userInitiated) { [self] in
                    let r = await self.lookup(req, k, l)
                    l.promise.fulfill(r)
                    self.lock.withLock { if self.inFlight[k] === l { self.inFlight[k] = nil } }
                }
            }
            if l.purpose < purpose { l.purpose = purpose }
            // A Play joining a look-ahead's lookup: its scrape is a Play's now (a lane slot at once if still waiting),
            // and the lookup ends with the Play's budget, not the look-ahead's 30 s.
            if !started && purpose == .play { l.tighten(to: deadline); Services.shared.sources.promote(req) }
            let w = await l.promise.wait(timeoutMs: max(deadline - Mono.now(), 1))
            if w.timedOut {
                if let spare { return keepSpare(req, k, spare, purpose) }
                plog.warning("Dream Core: \(self.label(req), privacy: .public) → nothing yet (budget of \(Self.secs(budgetMs), privacy: .public) over; the lookup goes on and remembers what it finds)")
                return nil
            }
            res = w.value ?? nil
            let ran = l.ran
            if res != nil || l === mine || ran == nil || ran! >= purpose { break }
            // The joined lookup asked fewer services (the next episode's): this one does its own debrid step.
            lock.withLock {
                if inFlight[k] === l { inFlight[k] = nil }
                misses[k] = nil // the joined lookup's answer isn't this Play's
            }
        }
        if let spare, let cap = saverCap {
            if let res, qi(res.quality) >= cap { _ = lock.withLock { saverMiss.removeValue(forKey: k) }; return res }
            // The lookup's own fallback above the limit. Only a Play (every linked service asked) counts as "nothing ready".
            if let res { if purpose == .play { lock.withLock { saverMiss[k] = Mono.now() } }; return res }
            return keepSpare(req, k, spare, purpose)
        }
        return res
    }

    /// Cellular Data Saver found nothing within the limit: the remembered link above it plays after all.
    private func keepSpare(_ req: PlayRequest, _ k: String, _ link: StreamLink, _ purpose: DebridPurpose) -> StreamLink {
        plog.info("Sources: cellular saver found nothing smaller ready for \(self.label(req), privacy: .public); \(purpose == .play ? "playing" : "keeping", privacy: .public) the remembered \(link.quality, privacy: .public) \(Self.size(link.sizeBytes), privacy: .public) link")
        lock.withLock {
            misses[k] = nil
            if purpose == .play { saverMiss[k] = Mono.now() }
            // Remembered again (a fresh lookup may have dropped it): the next Play takes it at once.
            if links[k] == nil { links[k] = (link, Mono.now()) }
        }
        return link
    }

    /// The remembered link a look-ahead may stop at: nil when Cellular Data Saver applies now and it's above the limit
    /// (then the look-ahead runs, within the limit; Instant Play on Wi-Fi is unchanged).
    public func peekUsable(_ req: PlayRequest) -> StreamLink? {
        guard let p = peek(req) else { return nil }
        guard let cap = DreamFilters.current().cellCap, qi(p.quality) < cap else { return p }
        if let at = lock.withLock({ saverMiss[key(req)] }), Mono.now() - at < Self.saverMissTTL { return p }
        return nil
    }

    /// The network flipped between Wi-Fi and a metered one (`PlaybackPath`). To Wi-Fi: links found under Cellular Data
    /// Saver are dropped (the next Play ranks at full quality again). To mobile data: links above the limit stay as
    /// fallbacks while the next Play (or look-ahead) looks within it (`find`).
    func networkChanged(unmetered: Bool) {
        if unmetered {
            let n: Int = lock.withLock {
                let ks = saverKeys.filter { links[$0] != nil }
                for k in ks { links[k] = nil; alternates[k] = nil }
                saverKeys.removeAll()
                saverMiss.removeAll()
                return ks.count
            }
            plog.info("Sources: cellular saver: off the metered network; \(n) links found under the saver dropped (the next Play ranks at full quality)")
        } else {
            guard let cap = DreamFilters.current().cellCap else {
                plog.info("Sources: cellular saver: on a metered network, saver not in use (\(CellularSaver.modeLabel, privacy: .public), answer \(CellularSaver.answer.rawValue, privacy: .public)); remembered links unchanged")
                return
            }
            let n: Int = lock.withLock {
                let ks = links.filter { qi($0.value.link.quality) < cap }.map(\.key)
                for k in ks { alternates[k] = nil } // a failover never jumps to a big copy behind the curtain
                saverMiss.removeAll()
                return ks.count
            }
            plog.info("Sources: cellular saver: on a metered network; \(n) remembered links above \(Self.capLabel(cap), privacy: .public) wait while the next Play looks within it")
        }
    }

    /// A real Play for [k] starts: look-aheads for other titles (Instant Play's `.page` / `.browse` lookups) give up
    /// their scrape slots at once; the Play's scrape never waits behind speculation. Their lookups end with nothing.
    private func preempt(_ k: String) {
        // Collected under the lock, cancelled outside it (a Gather's cancel wakes waiters and cancels tasks).
        let others: [(Lookup, Gather?)] = lock.withLock {
            inFlight.filter { $0.key != k && $0.value.purpose <= .page }.map { (_, l) in
                (l, gathers.removeValue(forKey: key(l.request))?.job)
            }
        }
        for (l, g) in others {
            g?.cancel()
            Services.shared.sources.cancel(l.request)
            plog.info("Instant Play: dropped the lookup for \(self.label(l.request), privacy: .public) (a Play starts)")
        }
    }

    /// The next episode's link while one plays (PreResolve.ahead): a torrent or two at the debrid service; the play that
    /// follows finds it remembered (`peek`) or joins the lookup. Returns at once.
    public func ahead(_ req: PlayRequest) {
        if !available() || peekUsable(req) != nil { return }
        let k = key(req)
        let go = lock.withLock { aheadRunning.insert(k).inserted }
        guard go else { return }
        Task.detached(priority: .utility) { [self] in
            let f = await self.find(req, budgetMs: 30_000, purpose: .ahead)
            _ = self.lock.withLock { self.aheadRunning.remove(k) }
            if let f {
                plog.info("Next episode: link ready for \(self.label(req), privacy: .public) (\(f.service.uppercased(), privacy: .public) \(f.quality, privacy: .public))")
                self.warmStream(req, f) // its opening seconds on disk before the credits end
            }
        }
    }

    // MARK: Keys

    func isEpisode(_ r: PlayRequest) -> Bool { r.title.kind == .tv }

    func key(_ r: PlayRequest) -> String {
        isEpisode(r) ? "tv:\(r.title.id):\(r.season ?? -1):\(r.episode ?? -1)" : "movie:\(r.title.id)"
    }

    func label(_ r: PlayRequest) -> String {
        isEpisode(r) ? "\(r.title.name) S\(r.season ?? 0)E\(r.episode ?? 0)" : r.title.name + (r.title.year.isEmpty ? "" : " (\(r.title.year))")
    }

    // MARK: The lookup

    private func lookup(_ req: PlayRequest, _ k: String, _ l: Lookup) async -> StreamLink? {
        let t0 = Mono.now()
        let acc = DebridAccounts.get()
        if !acc.any { return none(req, k, l, "no debrid account linked") }
        let ep = isEpisode(req)
        if ep && (req.season == nil || req.episode == nil) { return none(req, k, l, "no season/episode") }
        guard let imdb = await imdbFor(req) else { return none(req, k, l, "no IMDb id") }
        if !(await Services.shared.sources.isActive()) { return none(req, k, l, "no scraper sources added", .nothing) }
        if l.purpose == .browse {
            // Browsing: the quick index only (shared with the availability pills); nothing goes to any service.
            var r = req
            r.imdbId = imdb
            _ = await Services.shared.sources.quick(r)
            if l.purpose == .browse { return nil }
        }
        return await scraperLookup(req, k, imdb: imdb, l, acc, t0)
    }

    private func none(_ req: PlayRequest, _ k: String, _ l: Lookup, _ why: String, _ miss: Miss? = nil) -> StreamLink? {
        if let miss, l.purpose == .play { lock.withLock { misses[k] = miss } }
        if l.purpose <= .page { plog.info("Instant Play: \(self.label(req), privacy: .public): \(why, privacy: .public)") }
        else { plog.warning("Dream Core: \(self.label(req), privacy: .public) → nothing (\(why, privacy: .public))") }
        return nil
    }

    /// The show's (episodes) or movie's IMDb id: the request's own, else TMDB's.
    func imdbFor(_ req: PlayRequest) async -> String? {
        if let i = req.imdbId, i.hasPrefix("tt") { return i }
        if req.title.id <= 0 { return nil }
        let k = req.title.key
        if let i = lock.withLock({ imdbs[k] }) { return i }
        guard let d = try? await Services.shared.catalog.details(kind: req.title.kind, id: req.title.id),
              let i = d.imdbId, i.hasPrefix("tt") else { return nil }
        lock.withLock { imdbs[k] = i }
        return i
    }

    /// Providers switched or sources installed / removed: earlier scrapes no longer say what the viewer would get.
    func scrapersChanged() {
        let old = lock.withLock { () -> [Gather] in let g = gathers.values.map(\.job); gathers.removeAll(); return g }
        for g in old where !g.done { g.cancel() }
    }

    /// The scrape for [req]: one running or done in the last `indexTTL` (an empty one is redone after two minutes; one
    /// the scrapers never answered, busy or failed, is redone at once), else a new one. [urgent]: a real Play is
    /// waiting (the scrape never queues behind look-aheads; a running one it reuses is promoted).
    private func gather(_ req: PlayRequest, imdb: String, urgent: Bool = false) -> Gather {
        let k = key(req)
        let now = Mono.now()
        var r = req
        r.imdbId = imdb
        let (job, reused) = lock.withLock { () -> (Gather, Bool) in
            if let g = gathers[k], now - g.at < Self.indexTTL, !g.job.cancelled,
               !(g.job.done && g.job.count == 0 && (!g.job.answered || now - g.at >= 2 * 60_000)) {
                return (g.job, true)
            }
            let job = Gather(r, urgent: urgent)
            gathers[k] = (job, now)
            if gathers.count > 40 { gathers = gathers.filter { now - $0.value.at < Self.indexTTL } }
            return (job, false)
        }
        if reused && urgent && !job.done { Services.shared.sources.promote(r) }
        return job
    }

    private func parsed(_ job: Gather, _ season: Int?, _ episode: Int?) -> [Parsed] {
        job.items.map { scraped($0, season, episode) }
    }

    /// [job]'s torrents plus what the availability pills' quick check already found for [req] (the Fire Stick's
    /// `merged`): the card said Available Now from those, so Play counts them at once and never contradicts the pill.
    private func merged(_ job: Gather, _ req: PlayRequest, _ season: Int?, _ episode: Int?) -> [Parsed] {
        let a = parsed(job, season, episode)
        guard let q = Services.shared.sources.quickKnown(req), !q.isEmpty else { return a }
        var seen = Set(a.map(\.c.hash))
        return a + q.compactMap { c in seen.insert(c.hash.lowercased()).inserted ? scraped(c, season, episode) : nil }
    }

    private func scraped(_ i: StreamCandidate, _ season: Int?, _ episode: Int?) -> Parsed {
        let text = i.torrentName.uppercased()
        var cam = false
        let quality: String
        switch (i.scraperQuality ?? i.quality).uppercased() {
        case "4K", "2160P": quality = "4K"
        case "1080P": quality = "1080p"
        case "720P": quality = "720p"
        case "CAM", "SCR", "TELESYNC", "TS": cam = true; quality = "SD"
        default:
            quality = has(text, "2160P", "4K", "UHD") ? "4K" : has(text, "1080P") ? "1080p" : has(text, "720P") ? "720p" : "SD"
        }
        cam = cam || has(text, "CAM", "HDCAM", "TS", "HDTS", "TELESYNC", "HDTC", "TELECINE", "SCR", "DVDSCR", "SCREENER")
        // A season pack's size is the whole season's: unknown per episode.
        let pack = i.pack == true
        let c = Candidate(hash: i.hash.lowercased(), torrentName: i.torrentName, fileName: i.fileName, fileIdx: i.fileIdx,
                          sizeBytes: pack ? 0 : i.sizeBytes, quality: quality, seeders: i.seeders, season: season, episode: episode)
        let dubbed = i.torrentName.range(of: "[\\u0400-\\u04FF]", options: .regularExpression) != nil ||
            has(text, "DUB", "DUBBED", "DUBLADO", "MULTI", "DUAL", "TRUEFRENCH", "FRENCH", "ITA", "LATINO")
        return Parsed(c: c, text: text, cam: cam, dubbed: dubbed, pack: pack, provider: i.origin.isEmpty ? nil : i.origin,
                      lang: ReleaseLanguage.from(i.torrentName))
    }

    /// The title's running time in seconds when the "fit my internet speed" size limit needs it; else 0.
    private func runtimeFor(_ req: PlayRequest, _ f: DreamFilters.Values) async -> Int {
        let ep = isEpisode(req)
        guard f.speedMbps(ep) != nil, req.title.id > 0,
              let d = try? await Services.shared.catalog.details(kind: req.title.kind, id: req.title.id) else { return 0 }
        return d.runtime * 60
    }

    /// Dream Player's preferred audio language (2-letter), else the device's.
    private func wantLang() -> String? {
        ReleaseLanguage.norm(PlaybackSettings.audioLang ?? Locale.current.language.languageCode?.identifier)
    }

    /// Best tier the viewer's filters can give at all (what "a good result" means for the early exit).
    private func bestTier(_ f: DreamFilters.Values) -> Int {
        let b = f.known ? min(f.cap, 3) : 0
        // Cellular Data Saver: the best grade within its limit.
        guard let c = f.cellCap else { return b }
        return max(b, tier(Self.capLabel(c), f))
    }

    /// [req] resumes (Continue Watching has a spot for it): an MP4 costs more.
    private func resuming(_ req: PlayRequest) -> Bool { !req.fromStart && WatchHistory.shared.historyStartMs(req).map { $0 > 0 } == true }

    private func skipSet(_ k: String) -> Set<String> {
        let now = Mono.now()
        return lock.withLock {
            guard var m = failed[k] else { return [] }
            m = m.filter { now - $0.value <= Self.failedTTL }
            failed[k] = m
            return Set(m.keys)
        }
    }

    /// Dream Core through the viewer's scrapers. A Play doesn't wait for every provider: once the first ones are in it
    /// waits a moment for more (`graceGoodMs` when one of the best allowed grade is there, else `graceMs`), ranks what it
    /// has and asks the debrid services. Nothing ready yet: the next sources to arrive get their turn (up to three
    /// rounds), until the budget ends. The scrape itself runs on and is kept for Change Source.
    private func scraperLookup(_ req: PlayRequest, _ k: String, imdb: String, _ l: Lookup,
                               _ acc: DebridAccounts.Accounts, _ t0: Int64) async -> StreamLink? {
        // Read live: a Play that joins a title page's look-ahead raises it to `.play`, and this lookup becomes the Play's
        // (the debrid step runs for it; no second scrape), on the Play's budget (`Lookup.tighten`).
        var purpose: DebridPurpose { l.purpose }
        var deadline: Int64 { l.deadline }
        // Only a real Play sets the viewer's filters aside when nothing passes them (no speculative CAM unlocks).
        var relaxOk: Bool { purpose == .play }
        let ep = isEpisode(req)
        let s = ep ? req.season : nil
        let e = ep ? req.episode : nil
        let job = gather(req, imdb: imdb, urgent: purpose == .play)
        let f = DreamFilters.current()
        let rt = await runtimeFor(req, f)
        let want = wantLang()
        let resume = resuming(req)
        let best = bestTier(f)
        let skip = skipSet(k)
        var tried = Set<String>()
        var services: [DebridService]?
        var servicesFor: DebridPurpose?
        var round = 0
        while true {
            var kept: [Parsed]
            while true {
                let v = job.version
                kept = choose(merged(job, req, s, e), f, ep, skip.union(tried), rt, want, resume: resume)
                let t = Mono.now()
                if job.done || t >= deadline - Self.debridReserveMs { break }
                var wake = deadline - Self.debridReserveMs
                if !kept.isEmpty {
                    if round > 0 { break } // later rounds: whatever new arrives goes at once
                    let since = t - job.startedAt - job.firstMs
                    let good = kept.contains { tier($0.c.quality, f) <= best && !overCell($0.c.quality, f) }
                    if purpose == .page {
                        // A look-ahead ends early too (a slot and the scraper lanes free again; the scrape itself goes
                        // on and Play ranks whatever has arrived by then), with a longer grace than a Play for a better
                        // ranking, waking every moment in case Play was pressed meanwhile (then the Play's grace applies).
                        let grace = good ? Self.aheadGraceGoodMs : Self.aheadGraceMs
                        if since >= grace { break }
                        wake = min(wake, t + min(400, grace - since))
                    } else {
                        let grace = good ? Self.graceGoodMs : Self.graceMs
                        if since >= grace { break }
                        wake = min(wake, t + (grace - since))
                    }
                }
                await job.await(v, until: wake)
            }
            // Nothing passes the viewer's filters once the scrape is in (or the wait is over): a Play relaxes them.
            var level = 0
            if kept.isEmpty && relaxOk {
                let r = chooseRelaxed(merged(job, req, s, e), f, ep, skip.union(tried), rt, want, relaxOk: true, resume: resume)
                kept = r.0; level = r.1
            }
            if kept.isEmpty {
                if job.done || Mono.now() >= deadline {
                    let n = merged(job, req, s, e).count
                    if n == 0 {
                        // No answer at all (busy, not running, every provider failed) is unknown, never "not out yet".
                        if job.done && !job.answered { return none(req, k, l, "the scrapers didn't answer", .noAnswer) }
                        return none(req, k, l, "the scrapers found nothing\(job.done ? "" : " yet")", job.done ? .nothing : nil)
                    }
                    if round == 0 { return none(req, k, l, "nothing of \(n) passes your source filters\(relaxOk ? ", even relaxed" : "")", job.done ? .nothing : nil) }
                    return none(req, k, l, "none of \(tried.count) cached", .notReady)
                }
                await job.await(job.version, until: deadline)
                continue
            }
            let topList = Array(kept.prefix(Self.top))
            let p = purpose
            l.ran = p
            if servicesFor != p { services = nil }
            let svcs = services ?? self.services(acc, p)
            services = svcs
            servicesFor = p
            if svcs.isEmpty {
                if p <= .page { return none(req, k, l, "ranked \(kept.count) of \(job.count); the debrid step waits for Play") }
                return none(req, k, l, "AllDebrid is at its magnet limit and no other service is linked")
            }
            if let (found, others) = await race(k, svcs, topList.map(\.c), purpose: p, deadline: deadline) {
                remember(k, found)
                lock.withLock { () -> Void in if f.cellCap != nil { _ = saverKeys.insert(k) } else { _ = saverKeys.remove(k) } }
                addAlternates(k, others)
                noteRelaxed(req, k, found, kept, level)
                if p == .play { noteHDR(req, found, kept, f) }
                noteCellular(req, found, kept, f, lookAhead: p != .play)
                plog.warning("Dream Core: \(self.label(req), privacy: .public) → \(found.service.uppercased(), privacy: .public) \(found.quality, privacy: .public) \(Self.size(found.sizeBytes), privacy: .public) in \(Self.secs(Mono.now() - t0), privacy: .public) (scrapers: \(job.count) sources so far, \(kept.count) kept, round \(round + 1)\(others.isEmpty ? "" : ", \(others.count) more ready", privacy: .public)\(p <= .page ? ", look-ahead" : "", privacy: .public))")
                return found
            }
            if adLimited(acc), svcs.contains(where: { $0 is AllDebridService }) { limitNote(Array(svcs.drop { $0.key != "ad" }.dropFirst()), others: svcs.count > 1) }
            if p <= .page {
                // Premiumize had none of them: the ranking is done; Play does the real debrid step.
                return none(req, k, l, "ranked \(kept.count) of \(job.count), none cached free; the debrid step waits for Play")
            }
            tried.formUnion(topList.map(\.c.hash))
            round += 1
            let names = svcs.map { $0.key.uppercased() }.joined(separator: "/")
            if round >= 3 { return none(req, k, l, "none of \(tried.count) cached at \(names)", .notReady) }
            if Mono.now() >= deadline { return none(req, k, l, "budget over; none of \(tried.count) cached at \(names)") }
            if job.done && chooseRelaxed(merged(job, req, s, e), f, ep, skip.union(tried), rt, want, relaxOk: relaxOk, resume: resume).0.isEmpty {
                return none(req, k, l, "none of \(tried.count) cached at \(names)", .notReady)
            }
        }
    }

    private func remember(_ k: String, _ link: StreamLink) {
        let now = Mono.now()
        lock.withLock {
            links[k] = (link, now)
            if links.count > 100 { links = links.filter { now - $0.value.at < Self.linkTTL } }
        }
    }

    // MARK: The source list (Choose a Source, Search All Sources)

    /// Search All Sources: every filter off but CAM, discs and archives (always out, see `allowed`); 4K ranked first.
    private let loose = DreamFilters.Values(["quality.max": "4k", "quality.min": "sd", "hide.av1": "false", "hide.3d": "false"])

    /// The torrents the viewer's scrapers find for [req], filtered and ranked exactly as `find` ranks them; [loose]: the
    /// viewer's filters off, what they'd hide marked. Torrents that failed to play for this title come last; releases
    /// for another episode are flagged; one row per release. Empty when no account, no IMDb id or no scraper answered.
    public func sources(_ req: PlayRequest, loose isLoose: Bool, budgetMs: Int64 = 8_000) async -> [StreamCandidate] {
        let deadline = Mono.now() + budgetMs
        let ep = isEpisode(req)
        let s = req.season, e = req.episode
        guard DebridAccounts.anyLinked, !(ep && (s == nil || e == nil)), await Services.shared.sources.isActive(),
              let imdb = await imdbFor(req) else { return [] }
        let job = gather(req, imdb: imdb)
        await job.awaitDone(until: deadline)
        let all = merged(job, req, ep ? s : nil, ep ? e : nil)
        let f = DreamFilters.current()
        let rt = await runtimeFor(req, f)
        let bad = lock.withLock { Set(failed[key(req)]?.keys.map { $0 } ?? []) }
        let want = wantLang()
        let resume = resuming(req)
        let ranked: [Parsed], level: Int
        if isLoose { ranked = choose(all, loose, ep, [], rt, want, relax: Self.relaxQuality, resume: resume); level = 0 }
        else { (ranked, level) = chooseRelaxed(all, f, ep, [], rt, want, relaxOk: true, resume: resume) }
        let ordered = ranked.filter { !bad.contains($0.c.hash) } + ranked.filter { bad.contains($0.c.hash) }
        let rows = ordered.prefix(Self.maxListed).map { p -> StreamCandidate in
            var c = StreamCandidate(hash: p.c.hash, torrentName: p.c.torrentName, fileName: p.c.fileName, fileIdx: p.c.fileIdx,
                                    sizeBytes: p.c.sizeBytes, quality: p.c.quality, seeders: p.c.seeders, origin: p.provider ?? "",
                                    pack: p.pack)
            c.tags = tags(p.text)
            c.outsideFilters = (isLoose || level > 0) && (!allowed(p, f, ep, rt) || (f.hideSd && p.c.quality == "SD"))
            c.cam = camGrade(p)
            let name = p.c.fileName.map(DebridFiles.base) ?? p.c.torrentName
            c.otherEpisode = SourceNames.match(name, req) == .otherEpisode
            return c
        }
        return SourceNames.dedupe(Array(rows))
    }

    /// [c] (one of `sources`) unlocked through the linked debrid services in turn, cached torrents only. Remembered as
    /// [req]'s link, so "Try Another Source" skips it if it fails.
    public func resolveCandidate(_ req: PlayRequest, _ c: StreamCandidate, budgetMs: Int64 = 15_000) async -> StreamLink? {
        await unlock(req, [candidate(c, req)], deadline: Mono.now() + budgetMs, why: "picked")
    }

    func candidate(_ c: StreamCandidate, _ req: PlayRequest) -> Candidate {
        let ep = isEpisode(req)
        return Candidate(hash: c.hash.lowercased(), torrentName: c.torrentName, fileName: c.fileName, fileIdx: c.fileIdx, sizeBytes: c.sizeBytes,
                         quality: c.quality, seeders: c.seeders, season: ep ? req.season : nil, episode: ep ? req.episode : nil)
    }

    /// Search All Sources: `find` with the viewer's filters off, torrents that failed here skipped. AV1 stays out when
    /// the viewer hides it. nil when nothing loose is cached either.
    public func findLoose(_ req: PlayRequest, budgetMs: Int64 = 12_000) async -> StreamLink? {
        let deadline = Mono.now() + budgetMs
        let bad = lock.withLock { Set(failed[key(req)]?.keys.map { $0 } ?? []) }
        let av1 = DreamFilters.current().hideAv1
        let listed = await sources(req, loose: true, budgetMs: budgetMs / 2)
            .filter { !bad.contains($0.hash) && !(av1 && ($0.tags ?? []).contains("AV1")) }.prefix(Self.top * 2)
        let cands = listed.map { candidate($0, req) }
        if cands.isEmpty { plog.warning("Dream Core: \(self.label(req), privacy: .public) → nothing (loose: the scrapers found nothing playable)"); return nil }
        guard let f = await unlock(req, cands, deadline: deadline, why: "loose") else { return nil }
        // A CAM copy (listed last, so nothing better was ready): the loading screen says so.
        let k = key(req)
        let cam = listed.first { $0.hash == f.hash }?.cam == true
        lock.withLock { relaxedLinks[k] = cam ? Relaxed(hash: f.hash, cam: true) : nil }
        return f
    }

    /// The first of [cands] a linked service has cached, unlocked and remembered as [req]'s link; or nil.
    private func unlock(_ req: PlayRequest, _ cands: [Candidate], deadline: Int64, why: String) async -> StreamLink? {
        let acc = DebridAccounts.get()
        let t0 = Mono.now()
        let svcs = services(acc, .play)
        let k = key(req)
        if let (f, others) = await race(k, svcs, cands, purpose: why == "test" ? .list : .play, deadline: deadline) {
            remember(k, f)
            _ = lock.withLock { saverKeys.remove(k) } // picked by hand / Search All Sources: not a saver choice
            addAlternates(k, others)
            plog.warning("Dream Core: \(self.label(req), privacy: .public) → \(f.service.uppercased(), privacy: .public) \(f.quality, privacy: .public) \(Self.size(f.sizeBytes), privacy: .public) in \(Self.secs(Mono.now() - t0), privacy: .public) (\(why, privacy: .public)\(others.isEmpty ? "" : ", \(others.count) more ready", privacy: .public))")
            return f
        }
        if adLimited(acc), svcs.contains(where: { $0 is AllDebridService }) { limitNote(Array(svcs.drop { $0.key != "ad" }.dropFirst()), others: svcs.count > 1) }
        plog.warning("Dream Core: \(self.label(req), privacy: .public) → nothing cached of \(cands.count) (\(why, privacy: .public))")
        return nil
    }

    // MARK: The race (Instant Play, 2026-10-28)

    /// What one race has heard so far, shared by its service tasks and the waiter.
    private final class RaceBox: @unchecked Sendable {
        let lock = NSLock()
        let signal = Signal()
        let count: Int
        var results: [Int: [StreamLink]] = [:]
        var finished = Set<Int>()
        var firstReadyAt: Int64?
        var decided = false
        var version = 0
        init(count: Int) { self.count = count }

        /// Service [i] is done; true when the race was decided already (its links go to the alternates).
        func finish(_ i: Int, _ links: [StreamLink]) -> Bool {
            let late: Bool = lock.withLock {
                results[i] = links
                finished.insert(i)
                if firstReadyAt == nil && !links.isEmpty { firstReadyAt = Mono.now() }
                version += 1
                return decided
            }
            signal.fire()
            return late
        }

        enum Outcome { case waiting, nothing, won(Int, StreamLink, [StreamLink]) }

        /// The winner and the other ready links (in the viewer's order); `.nothing` when every service answered with none.
        func decide(now: Int64, tieMs: Int64) -> Outcome {
            lock.withLock {
                if decided { return .nothing }
                let ready = results.filter { !$0.value.isEmpty }.keys.sorted()
                guard let best = ready.first, let winner = results[best]?.first else { return finished.count >= count ? .nothing : .waiting }
                let earlierPending = (0..<best).contains { !finished.contains($0) }
                if earlierPending, let at = firstReadyAt, now - at < tieMs { return .waiting }
                decided = true
                let others = (results[best] ?? []).dropFirst() + ready.dropFirst().flatMap { results[$0] ?? [] }
                return .won(best, winner, Array(others))
            }
        }

        var nextWake: Int64? { lock.withLock { firstReadyAt } }
        var isDecided: Bool { lock.withLock { decided } }
    }

    /// The debrid step: every service in [svcs] asked at once (each its own task), the first with a link wins, the
    /// viewer's order breaking near-ties (`tieMs`): a later service that answers while an earlier one is still
    /// answering waits that long for it. The winner's extra links and every other service's links (even ones that
    /// land after the decision) become the title's alternates for the player's next try. A Play races up to
    /// `raceLinks` per service (AllDebrid caps itself at 2 uploads); other purposes one.
    private func race(_ k: String, _ svcs: [DebridService], _ cands: [Candidate], purpose p: DebridPurpose, deadline: Int64) async -> (StreamLink, [StreamLink])? {
        if svcs.isEmpty || cands.isEmpty { return nil }
        let box = RaceBox(count: svcs.count)
        let limit = p == .play ? Self.raceLinks : 1
        let anyFree = svcs.contains { $0.key == "pm" || $0.key == "tb" }
        for (i, svc) in svcs.enumerated() {
            let mine = p == .ahead && svc.key == "rd" ? Array(cands.prefix(2)) : cands
            let uploads = svc.key == "ad" || svc.key == "rd"
            Task.detached(priority: .userInitiated) { [self] in
                if uploads && anyFree {
                    // Magnet safety: the free checks get a head start; decided by then, this one never uploads.
                    await sleepMs(Self.uploadDelayMs)
                    if box.isDecided { _ = box.finish(i, []); return }
                }
                let links = await svc.resolveMany(mine, deadline: deadline, limit: limit)
                if box.finish(i, links), !links.isEmpty {
                    self.addAlternates(k, links)
                    plog.info("Dream Core: \(svc.key.uppercased(), privacy: .public) answered after the race: \(links.count) more ready")
                }
            }
        }
        let end = deadline + 1_500
        while true {
            let now = Mono.now()
            switch box.decide(now: now, tieMs: Self.tieMs) {
            case .won(let i, let f, let others):
                if i > 0 || !others.isEmpty || svcs.count > 1 {
                    plog.info("Dream Core: race won by \(svcs[i].key.uppercased(), privacy: .public) of \(svcs.map { $0.key.uppercased() }.joined(separator: "/"), privacy: .public) (\(others.count) more ready)")
                }
                return (f, others)
            case .nothing:
                return nil
            case .waiting:
                break
            }
            if now >= end { return nil }
            let v = box.version
            let wake = min(end, box.nextWake.map { $0 + Self.tieMs } ?? end)
            await box.signal.wait(until: max(wake, now + 20)) { box.lock.withLock { box.version == v } }
        }
    }

    /// Which of [cands] are ready to stream right now, for the source list's "Ready" marks: AllDebrid's one-call answer
    /// when it's linked (what it added is removed again), else nil (the other services would need a call per torrent).
    public func readyHashes(_ cands: [StreamCandidate], request: PlayRequest? = nil, budgetMs: Int64 = 6_000) async -> Set<String>? {
        guard let t = DebridAccounts.get().allDebrid else { return nil }
        let list = cands.prefix(DebridPurpose.list.maxUploads).map { c in
            request.map { candidate(c, $0) } ?? Candidate(hash: c.hash.lowercased(), torrentName: c.torrentName, fileName: c.fileName, fileIdx: c.fileIdx,
                                                         sizeBytes: c.sizeBytes, quality: c.quality, seeders: c.seeders, season: nil, episode: nil)
        }
        return await AllDebridService(token: t, purpose: .list).readyHashes(list, deadline: Mono.now() + budgetMs)
    }

    /// What the scrapers find for [req], for the availability checker: counts and the best release of each grade,
    /// nothing sent to any debrid service and no account needed.
    public func survey(_ req: PlayRequest, budgetMs: Int64 = 8_000) async -> SourceSurvey? {
        let ep = isEpisode(req)
        guard await Services.shared.sources.isActive(), !(ep && (req.season == nil || req.episode == nil)),
              let imdb = await imdbFor(req) else { return nil }
        let s = ep ? req.season : nil, e = ep ? req.episode : nil
        // A full scrape already run for it (a Play, a title page's look-ahead) counts; else the quick check (two fast
        // providers, short limits), as on the Fire Stick. A scrape that got no answer (busy, every provider failed or
        // timed out) is no answer: nil, no pill, asked again later. Never "Not Available Yet".
        let now = Mono.now()
        if let full = lock.withLock({ gathers[key(req)] }), now - full.at < Self.indexTTL, !full.job.cancelled {
            await full.job.awaitDone(until: now + budgetMs)
            if full.job.count > 0 { return summarize(parsed(full.job, s, e)) }
            if !full.job.done { return nil }
        }
        var r = req
        r.imdbId = imdb
        guard let items = await Services.shared.sources.quick(r) else {
            plog.info("Avail: no scraper answer for \(self.label(req), privacy: .public)")
            return nil
        }
        return summarize(items.map { scraped($0, s, e) })
    }

    /// Counts and the best release of each grade (`SourceSurvey`) from a scrape's torrents.
    private func summarize(_ all: [Parsed]) -> SourceSurvey {
        let cams = all.filter { camGrade($0) }, good = all.filter { !camGrade($0) }
        let fl = DreamFilters.current()
        func dv(_ p: Parsed) -> Bool { has(p.text, "DV", "DOVI", "DOLBY VISION") }
        func hdrOnly(_ p: Parsed) -> Bool { has(p.text, "HDR", "HDR10", "HDR10+") }
        func range(_ p: Parsed) -> String? { dv(p) && !fl.hideDv ? "DV" : hdrOnly(p) && !fl.hideHdr ? "HDR" : nil }
        let allowedGood0 = good.filter { !(fl.hideDv && dv($0)) && !(fl.hideHdr && hdrOnly($0)) }
        let allowedGood = allowedGood0.isEmpty ? good : allowedGood0
        func rank(_ p: Parsed) -> Int { qi(p.c.quality) * 10_000_000 + (range(p) == "DV" ? 0 : range(p) == "HDR" ? 1 : 2) * 1_000_000 - min(p.c.seeders, 999_999) }
        let topP = allowedGood.min { rank($0) < rank($1) }
        let topCam = cams.min { qi($0.c.quality) * 1_000_000 - min($0.c.seeders, 999_999) < qi($1.c.quality) * 1_000_000 - min($1.c.seeders, 999_999) }
        return SourceSurvey(total: all.count, quality: good.count, cam: cams.count,
                            best: topP.map { [$0.c.quality, source($0.text), range($0)].compactMap { $0 }.joined(separator: " ") },
                            bestCam: topCam.map { camTag($0.text) + " " + $0.c.quality })
    }

    /// What the setup page's test saw: [found] sources, [ready] of the top ones ready on the account.
    public struct TestOutcome: Sendable { public let found: Int; public let ready: Int; public let account: Bool }

    /// A full scrape of [req] and a readiness check of its best few (Settings › Scrapers › Test).
    public func testScrape(_ req: PlayRequest, budgetMs: Int64 = 30_000) async -> TestOutcome {
        let deadline = Mono.now() + budgetMs
        guard let imdb = await imdbFor(req) else { return TestOutcome(found: 0, ready: 0, account: false) }
        let job = gather(req, imdb: imdb)
        await job.awaitDone(until: deadline - 6_000)
        let n = job.count
        let acc = DebridAccounts.get()
        if !acc.any || n == 0 { return TestOutcome(found: n, ready: 0, account: acc.any) }
        let topC = choose(parsed(job, nil, nil), loose, false, []).prefix(5).map(\.c)
        let asRows = topC.map { StreamCandidate(hash: $0.hash, torrentName: $0.torrentName, sizeBytes: $0.sizeBytes, quality: $0.quality) }
        if let r = await readyHashes(asRows, budgetMs: max(deadline - Mono.now(), 3_000)) { return TestOutcome(found: n, ready: r.count, account: true) }
        let one = await unlock(req, Array(topC), deadline: max(deadline, Mono.now() + 4_000), why: "test")
        return TestOutcome(found: n, ready: one == nil ? 0 : 1, account: true)
    }

    // MARK: Which services a lookup asks

    /// The linked services in the viewer's order (by default those whose cache check adds nothing to the account first,
    /// then Real-Debrid, then AllDebrid), AllDebrid left out while at its magnet limit; the clearly fastest first.
    private func services(_ acc: DebridAccounts.Accounts, _ purpose: DebridPurpose) -> [DebridService] {
        let ad = acc.allDebrid
        // A look-ahead may only ask what adds nothing to any account: the free cache checks (Premiumize, TorBox's
        // checkcached), in the viewer's own order. AllDebrid / Real-Debrid checks need an upload, so they wait for Play.
        if purpose <= .page {
            return DebridAccounts.order().compactMap { k -> DebridService? in
                switch k {
                case "pm": acc.premiumize.map { PremiumizeService(token: $0) }
                case "tb": acc.torBox.map { TorBoxService(token: $0) }
                default: nil
                }
            }
        }
        var list: [DebridService] = []
        for k in DebridAccounts.order() {
            switch k {
            case "ad": if let ad, !AllDebridService.limited(ad) { list.append(AllDebridService(token: ad, purpose: purpose)) }
            case "pm": if let t = acc.premiumize { list.append(PremiumizeService(token: t)) }
            case "tb": if let t = acc.torBox { list.append(TorBoxService(token: t)) }
            case "rd": if acc.realDebrid != nil { list.append(RealDebridService(accounts: acc)) }
            default: break
            }
        }
        if let ad, AllDebridService.limited(ad) { limitNote(list, others: list.contains { !($0 is AllDebridService) }) }
        return fastestFirst(list)
    }

    /// [list] with the service that starts this viewer's streams clearly fastest (a typical first frame at least
    /// `clearlyFasterMs` sooner than the first service's, both measured) moved to the front.
    private func fastestFirst(_ list: [DebridService]) -> [DebridService] {
        guard list.count >= 2, let first = startTime(list[0].key) else { return list }
        guard let best = list.dropFirst().compactMap({ s in startTime(s.key).map { (s, $0) } }).min(by: { $0.1 < $1.1 }) else { return list }
        if first - best.1 < Self.clearlyFasterMs { return list }
        plog.info("Dream Core: asking \(best.0.key.uppercased(), privacy: .public) first (starts in ~\(Self.secs(best.1), privacy: .public), \(list[0].key.uppercased(), privacy: .public) ~\(Self.secs(first), privacy: .public))")
        return [best.0] + list.filter { $0.key != best.0.key }
    }

    private func adLimited(_ acc: DebridAccounts.Accounts) -> Bool { acc.allDebrid.map { AllDebridService.limited($0) } ?? false }

    /// The log line (and the day's notice) for AllDebrid at its limit; [services]: the ones asked instead.
    private func limitNote(_ services: [DebridService], others: Bool) {
        DebridExpiry.onNotice?(DebridNotice(key: "limit:ad:\(DreamTime.todayYmd())", title: "AllDebrid Magnet Limit Reached",
                                            body: "It frees up automatically. Other linked services keep streaming.", severity: .warning,
                                            action: nil, replacesPrefix: nil))
        let names = services.filter { !($0 is AllDebridService) }.map { DebridAccounts.name($0.key) }
        plog.warning("\(names.isEmpty ? "AllDebrid at its magnet limit: \(others ? "the other services had nothing either" : "no other service linked")" : "AllDebrid at its magnet limit: using \(names.joined(separator: "/"))", privacy: .public)")
    }

    // MARK: Measured start times per service

    /// [link] goes to the player now: its first frame (`firstFrame`) is timed from here.
    public func handing(_ link: StreamLink) {
        let now = Mono.now()
        lock.withLock {
            handedAt[link.url.absoluteString] = now
            if handedAt.count > 20 { handedAt = handedAt.filter { now - $0.value <= 120_000 } }
        }
    }

    private struct Timed: Codable { var avg: Int64; var n: Int }

    /// Dream Player's first frame of [link] for [req]: the time since `handing` counts for the service that unlocked it
    /// (a rolling average, recent starts weighing more), so `services` can ask the one that starts clearly faster first.
    public func firstFrame(_ req: PlayRequest, _ link: StreamLink) {
        let u = link.url.absoluteString
        guard let at = lock.withLock({ handedAt.removeValue(forKey: u) }), peek(req)?.url.absoluteString == u else { return }
        let ms = Mono.now() - at
        if ms <= 0 || ms > 60_000 { return }
        let k = "core.ttff." + link.service
        let old = DreamStore.shared.get(Timed.self, k)
        let avg = old.map { ($0.avg * 2 + ms) / 3 } ?? ms
        let n = min((old?.n ?? 0) + 1, 99)
        DreamStore.shared.set(Timed(avg: avg, n: n), k)
        plog.info("Dream Core: \(link.service.uppercased(), privacy: .public) first frame in \(Self.secs(ms), privacy: .public) (typical \(Self.secs(avg), privacy: .public) over \(n) plays)")
        let rk = key(req)
        SourceMemory.shared.record(traits(rk, link), ok: true, ms: ms)
        settleAlternates(rk, winner: link)
    }

    private func startTime(_ service: String) -> Int64? {
        guard let t = DreamStore.shared.get(Timed.self, "core.ttff." + service), t.n >= Self.minTimed, t.avg > 0 else { return nil }
        return t.avg
    }

    static let minTimed = 3
    static let clearlyFasterMs: Int64 = 1_500

    // MARK: Ranking

    private static let tokenLock = NSLock()
    nonisolated(unsafe) private static var tokens: [String: NSRegularExpression] = [:]

    /// [w] as a whole token in upper-case [t].
    func has(_ t: String, _ words: String...) -> Bool {
        let range = NSRange(t.startIndex..., in: t)
        for w in words {
            let re: NSRegularExpression? = Self.tokenLock.withLock {
                if let r = Self.tokens[w] { return r }
                let r = try? NSRegularExpression(pattern: "(?<![A-Z0-9])" + NSRegularExpression.escapedPattern(for: w) + "(?![A-Z0-9])")
                Self.tokens[w] = r
                return r
            }
            if let re, re.firstMatch(in: t, range: range) != nil { return true }
        }
        return false
    }

    private func qi(_ q: String) -> Int { switch q { case "4K": 0; case "1080p": 1; case "720p": 2; default: 3 } }
    static func capLabel(_ c: Int) -> String { ["4K", "1080p", "720p", "SD"][max(0, min(3, c))] }
    /// A copy above Cellular Data Saver's limit (only while it applies to this lookup).
    private func overCell(_ q: String, _ f: DreamFilters.Values) -> Bool { f.cellCap.map { qi(q) < $0 } ?? false }

    /// Rank of a quality: best allowed first; with no settings, 1080p before 4K (streams on any network).
    private func tier(_ q: String, _ f: DreamFilters.Values) -> Int {
        if f.known { return qi(q) }
        switch q { case "1080p": return 0; case "4K": return 1; case "720p": return 2; default: return 3 }
    }

    private func hdr(_ t: String) -> Bool { has(t, "HDR", "HDR10", "HDR10+", "DV", "DOVI", "DOLBY VISION") }
    /// Dolby Vision with no HDR10 named: usually profile 5 (no standard HDR picture underneath). Plays on iPhone, but
    /// an HDR10 / hybrid copy is the safer pick above the cap (Dream Player notes, docs/port/player.md).
    private func dvOnly(_ t: String) -> Bool { has(t, "DV", "DOVI", "DOLBY VISION") && !has(t, "HDR", "HDR10", "HDR10+") }
    /// A copy above Maximum Quality that only Prefer HDR let in.
    private func aboveCap(_ p: Parsed, _ f: DreamFilters.Values) -> Bool { f.known && qi(p.c.quality) < f.cap }
    private func hevc(_ t: String) -> Bool { has(t, "HEVC", "X265", "H265", "H.265") }
    private func upscaled(_ t: String) -> Bool { has(t, "UPSCALE", "UPSCALED", "AI UPSCALED", "AI-UPSCALED", "AIUS") }
    private func disc(_ name: String) -> Bool { name.range(of: "\\.(iso|img|rar|zip|7z|exe)$", options: [.regularExpression, .caseInsensitive]) != nil }

    /// Camera / screener grade by release name (whole tokens: a title word like "CAMERA" is not CAM).
    func camGrade(_ p: Parsed) -> Bool {
        p.cam || has(p.text, "HQCAM", "CAMRIP", "CAM-RIP", "HDCAM", "HDTS", "TELESYNC", "HDTC", "TC", "TELECINE", "DVDSCR", "SCREENER", "PDVD", "PREDVD", "HDTSRIP")
    }

    private func source(_ t: String) -> String? {
        if has(t, "REMUX") { return "Remux" }
        if has(t, "BLURAY", "BLU-RAY", "BDRIP", "BRRIP") { return "BluRay" }
        if has(t, "WEB-DL", "WEBDL") { return "WEB-DL" }
        if has(t, "WEBRIP", "WEB-RIP") { return "WEBRip" }
        if has(t, "WEB") { return "WEB" }
        if has(t, "HDTV") { return "HDTV" }
        if has(t, "DVDRIP") { return "DVDRip" }
        return nil
    }

    private func camTag(_ t: String) -> String {
        ["HDCAM", "CAMRIP", "CAM", "HDTS", "TELESYNC", "TS", "HDTC", "TELECINE", "TC", "DVDSCR", "SCREENER", "SCR", "PDVD", "PREDVD"].first { has(t, $0) } ?? "CAM"
    }

    private func tags(_ t: String) -> [String] {
        var out: [String] = []
        if has(t, "DV", "DOVI", "DOLBY VISION") { out.append("Dolby Vision") } else if has(t, "HDR10+") { out.append("HDR10+") } else if has(t, "HDR", "HDR10") { out.append("HDR") }
        if hevc(t) { out.append("HEVC") } else if has(t, "AV1") { out.append("AV1") }
        if has(t, "REMUX") { out.append("Remux") }
        if has(t, "ATMOS") { out.append("Atmos") }
        else if let m = t.range(of: "(?<![0-9])([57])[ .]1(?![0-9])", options: .regularExpression) { out.append("\(t[m].first!).1") }
        return out
    }

    static let relaxSize = 1
    static let relaxQuality = 2
    static let relaxFormat = 3

    /// Whether the viewer would play [p] (their filters; always no CAM/TS/SCR, discs, archives, or episodes over 40 GB).
    /// Size limits: a GB range, or "fit my internet speed" (the average bitrate over [runtimeSec] must stay under it).
    private func allowed(_ p: Parsed, _ f: DreamFilters.Values, _ ep: Bool, _ runtimeSec: Int = 0, relax: Int = 0) -> Bool {
        let c = p.c, t = p.text
        if p.cam && relax < Self.relaxQuality { return false }
        if let fn = c.fileName { if !DebridFiles.isVideo(fn) { return false } }
        else if disc(c.torrentName) || has(t, "ISO", "BDMV", "BD25", "BD50", "BD66", "BD100") { return false }
        if ep && c.sizeBytes > 40 * Self.gb { return false }
        // Prefer HDR (HDRPreference): an HDR copy may go above Maximum Quality; every other copy obeys it.
        if relax < Self.relaxQuality && qi(c.quality) < f.cap && !(f.hdrAboveCap && hdr(t) && !(f.hideDv && dvOnly(t))) { return false }
        if relax < Self.relaxSize {
            if let r = f.range(ep), c.sizeBytes > 0, !r.contains(Double(c.sizeBytes) / Double(Self.gb)) { return false }
            if let mbps = f.speedMbps(ep), runtimeSec > 0, c.sizeBytes > 0, Double(c.sizeBytes) * 8 / Double(runtimeSec) / 1_000_000 > mbps { return false }
        }
        // Relaxed or not, what the viewer hides stays out: HDR / Dolby Vision / 3D, and AV1 when hidden.
        return !((relax < Self.relaxFormat && f.hideHevc && hevc(t)) ||
            (f.hideHdr && has(t, "HDR", "HDR10")) ||
            (f.hideDv && has(t, "DV", "DOVI", "DOLBY VISION")) ||
            (f.hideAv1 && has(t, "AV1")) ||
            (relax < Self.relaxFormat && f.hideAvc && has(t, "AVC", "X264", "H264", "H.264")) ||
            (f.hide3d && has(t, "3D")) ||
            (relax < Self.relaxQuality && f.hideUpscaled && upscaled(t)))
    }

    /// `choose` at the strictest level that leaves something: the filters as set, then without size limits, then
    /// without quality limits (CAM allowed, ranked after any real release), then without HEVC / AVC choices.
    private func chooseRelaxed(_ all: [Parsed], _ f: DreamFilters.Values, _ ep: Bool, _ skip: Set<String>, _ runtimeSec: Int,
                               _ want: String?, relaxOk: Bool, resume: Bool = false) -> ([Parsed], Int) {
        for level in 0...(relaxOk ? Self.relaxFormat : 0) {
            let kept = choose(all, f, ep, skip, runtimeSec, want, relax: level, resume: resume)
            if !kept.isEmpty { return (kept, level) }
        }
        return ([], 0)
    }

    /// After a Play's link: what Prefer HDR did (log only).
    private func noteHDR(_ req: PlayRequest, _ found: StreamLink, _ kept: [Parsed], _ f: DreamFilters.Values) {
        guard f.known, f.cap > 0 else { return }
        let capLabel = ["4K", "1080p", "720p", "SD"][min(3, f.cap)]
        if let p = kept.first(where: { $0.c.hash == found.hash }), aboveCap(p, f) {
            plog.info("Sources: prefer HDR picked \(p.c.quality, privacy: .public) \(self.tags(p.text).first ?? "HDR", privacy: .public) above your \(capLabel, privacy: .public) Maximum Quality for \(self.label(req), privacy: .public)")
        } else if HDRPreference.chosen, let why = HDRPreference.heldBack {
            plog.info("Sources: prefer HDR held back for \(self.label(req), privacy: .public) (\(why, privacy: .public)), kept within \(capLabel, privacy: .public)")
        } else if f.hdrAboveCap {
            plog.info("Sources: prefer HDR on, no HDR copy above \(capLabel, privacy: .public) was ready for \(self.label(req), privacy: .public)")
        }
    }

    /// After a Play's link: what Cellular Data Saver did (log only; the Info note is set at hand-over, `CellularSaver.mark`).
    private func noteCellular(_ req: PlayRequest, _ found: StreamLink, _ kept: [Parsed], _ f: DreamFilters.Values, lookAhead: Bool = false) {
        guard let c = f.cellCap else {
            if !lookAhead && CellularSaver.onMobileData { plog.info("Sources: cellular saver not in use for \(self.label(req), privacy: .public) on mobile data (\(CellularSaver.modeLabel, privacy: .public), answer \(CellularSaver.answer.rawValue, privacy: .public))") }
            return
        }
        let cap = Self.capLabel(c)
        let above = kept.filter { overCell($0.c.quality, f) }.count
        let tag = lookAhead ? " (look-ahead)" : ""
        if overCell(found.quality, f) {
            plog.info("Sources: cellular saver found nothing within \(cap, privacy: .public) ready for \(self.label(req), privacy: .public)\(tag, privacy: .public); \(lookAhead ? "keeping" : "playing", privacy: .public) the smallest above it (\(found.quality, privacy: .public) \(Self.size(found.sizeBytes), privacy: .public))")
        } else {
            plog.info("Sources: cellular saver kept \(self.label(req), privacy: .public) within \(cap, privacy: .public) on mobile data\(tag, privacy: .public) (\(found.quality, privacy: .public) \(Self.size(found.sizeBytes), privacy: .public); \(above) bigger copies ranked after)")
        }
    }

    /// After a link was found: when the filters were relaxed ([level] > 0), the log line and the note the loading screen shows.
    private func noteRelaxed(_ req: PlayRequest, _ k: String, _ found: StreamLink, _ kept: [Parsed], _ level: Int) {
        if level <= 0 { lock.withLock { relaxedLinks[k] = nil }; return }
        let p = kept.first { $0.c.hash == found.hash }
        let cam = p.map { camGrade($0) } ?? false
        let what = p.map { cam ? camTag($0.text) + " " + $0.c.quality : [$0.c.quality, source($0.text)].compactMap { $0 }.joined(separator: " ") } ?? found.quality
        let relaxed = ["size", "quality", "formats"].prefix(level).reversed().joined(separator: ", ")
        plog.warning("Dream Core: \(self.label(req), privacy: .public) nothing passes your filters, playing best available (\(what, privacy: .public)) [relaxed: \(relaxed, privacy: .public)]")
        lock.withLock { relaxedLinks[k] = Relaxed(hash: found.hash, cam: cam) }
    }

    /// [all] minus failed torrents and what the filters drop, best first: a film collection and files twice the
    /// network-friendly size go last; then quality tier; within one, network-friendly size (movies up to 25 GB, episodes
    /// 6 GB), then the viewer's HEVC / HDR preference and clean (not dubbed, not upscaled) releases, then an MKV before an
    /// MP4, then seeders. [resume]: the MP4 penalty comes right after the quality tier (a resume seeks into the file, and
    /// an MP4's index must be read first).
    private func choose(_ all: [Parsed], _ f: DreamFilters.Values, _ ep: Bool, _ skip: Set<String>, _ runtimeSec: Int = 0,
                        _ want: String? = nil, relax: Int = 0, resume: Bool = false) -> [Parsed] {
        let pass = all.filter { !skip.contains($0.c.hash) && allowed($0, f, ep, runtimeSec, relax: relax) }
        // SD goes only when something better exists (with CAM copies let in: a real release, not a CAM).
        let camsIn = relax >= Self.relaxQuality
        let list = f.hideSd && pass.contains(where: { $0.c.quality != "SD" && !(camsIn && camGrade($0)) }) ? pass.filter { $0.c.quality != "SD" } : pass
        let heavy = ep ? 6 * Self.gb : 25 * Self.gb
        let other = want != nil && want != "en"
        func b(_ x: Bool) -> Int { x ? 1 : 0 }
        func langMiss(_ p: Parsed) -> Int { !other ? 0 : (p.lang == want || has(p.text, "MULTI", "DUAL")) ? 0 : 1 }
        // A film from a scraper never names its file: a collection (which film would play?) goes last.
        func sink(_ p: Parsed) -> Bool {
            p.c.sizeBytes > 2 * heavy || (!ep && has(p.text, "TRILOGY", "DUOLOGY", "QUADRILOGY", "PENTALOGY", "HEXALOGY", "COLLECTION", "SAGA", "BOXSET", "ANTHOLOGY", "COMPLETE"))
        }
        func penalty(_ p: Parsed) -> Int {
            (p.dubbed && !other ? 2 : 0) + (upscaled(p.text) ? 2 : 0) + (f.preferHdr && !hdr(p.text) ? 1 : 0) + (f.preferHevc && !hevc(p.text) ? 1 : 0)
                + (aboveCap(p, f) && dvOnly(p.text) ? 1 : 0)
        }
        // Otherwise equal, an MKV before an MP4: an MP4's index (often at the end) costs its download before the first frame.
        func slow(_ p: Parsed) -> Bool { mp4(p) }
        // Learned on this phone (SourceMemory): releases whose kind failed here go a step later, ones that started
        // fast and cleanly a step earlier; after the quality tier and size, so never past the viewer's choices.
        let mem = SourceMemory.shared.snapshot()
        func learned(_ p: Parsed) -> Int {
            mem.isEmpty ? 0 : mem.nudge(SourceMemory.traits(name: p.c.torrentName, fileName: p.c.fileName, provider: p.provider, sizeBytes: p.c.sizeBytes))
        }
        // Cellular Data Saver (`CellularSaver`, only while it applies): copies above its limit after every copy within
        // it, the lowest grade and smallest file of them first; within it, a smaller file wins a tie (256 MB steps).
        let saver = f.cellCap != nil
        func over(_ p: Parsed) -> Bool { overCell(p.c.quality, f) }
        func sizeStep(_ p: Parsed) -> Int { p.c.sizeBytes > 0 ? Int(p.c.sizeBytes >> 28) : Int.max / 4 }
        let keyed = list.enumerated().map { (i, p) -> ([Int], Parsed) in
            ([b(sink(p)), b(camsIn && camGrade(p)), langMiss(p), b(over(p)), over(p) ? -qi(p.c.quality) : tier(p.c.quality, f),
              b(resume && slow(p)), b(p.pack), b(p.c.sizeBytes > heavy), over(p) ? sizeStep(p) : 0, penalty(p), learned(p),
              b(slow(p)), saver ? sizeStep(p) : 0, -p.c.seeders, i], p)
        }
        return keyed.sorted { $0.0.lexicographicallyPrecedes($1.0) }.map(\.1)
    }

    /// An MP4 (or M4V) by what's known up front: the file or torrent name, an "MP4" token, or YTS (always MP4).
    private func mp4(_ p: Parsed) -> Bool {
        let name = (p.c.fileName ?? p.c.torrentName).lowercased()
        let prov = (p.provider.map { $0.split(separator: ":").last.map(String.init) ?? $0 } ?? "").lowercased()
        return name.hasSuffix(".mp4") || name.hasSuffix(".m4v") || has(p.text, "MP4") || prov.hasPrefix("yts") || has(p.text, "YTS", "YTS.MX", "YIFY")
    }

    // MARK: Log formatting

    static func secs(_ ms: Int64) -> String { String(format: "%.1f s", Double(ms) / 1000) }
    static func size(_ b: Int64) -> String {
        if b <= 0 { return "size unknown" }
        if b < gb { return "\(b / (1024 * 1024)) MB" }
        return String(format: "%.1f GB", Double(b) / Double(gb))
    }
}

/// Why a lookup runs, for callers outside the module (Dream Core's own `DebridPurpose`).
public enum DebridPurposeKind: Sendable {
    case browse, page, nextEpisode, sourceList, play
    var `internal`: DebridPurpose {
        switch self { case .browse: .browse; case .page: .page; case .nextEpisode: .ahead; case .sourceList: .list; case .play: .play }
    }
}

/// Release-name checks for the source list (SourcePicker.kt): another episode's release, duplicates.
enum SourceNames {
    enum Match { case ok, doubt, otherEpisode, otherTitle }
    private static let stop: Set<String> = ["the", "a", "an", "and", "of", "in", "on", "to"]
    static func norm(_ s: String) -> String {
        s.lowercased().replacingOccurrences(of: "&", with: " and ").replacingOccurrences(of: "[^a-z0-9]+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
    }
    private static func words(_ s: String) -> [String] { norm(s).split(separator: " ").map(String.init).filter { !stop.contains($0) } }
    private static func yearsIn(_ s: String) -> [Int] {
        guard let re = try? NSRegularExpression(pattern: "(?<![0-9])(19\\d{2}|20\\d{2})(?![0-9])") else { return [] }
        return re.matches(in: s, range: NSRange(s.startIndex..., in: s)).compactMap { Range($0.range, in: s).flatMap { Int(s[$0]) } }
    }
    private static func groups(_ pattern: String, _ s: String) -> [String?]? {
        guard let re = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]),
              let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) else { return nil }
        return (0..<m.numberOfRanges).map { Range(m.range(at: $0), in: s).map { String(s[$0]) } }
    }

    /// Whether release [name] looks like [req]: its S/E (or year) and its title words.
    static func match(_ name: String, _ req: PlayRequest) -> Match {
        let title = words(req.title.name)
        let nameWords = Set(words(name))
        let have = title.isEmpty ? 1.0 : Double(title.filter { nameWords.contains($0) }.count) / Double(title.count)
        if req.title.kind == .tv, let rs = req.season, let re = req.episode {
            if let g = groups("(?<![a-z0-9])s(\\d{1,2})[ ._-]*e(\\d{1,3})(?:[ ._-]*-?[ ._-]*e?(\\d{1,3}))?", name) ?? groups("(?<![0-9])(\\d{1,2})x(\\d{2,3})(?![0-9])", name),
               let s = g[1].flatMap({ Int($0) }), let e1 = g[2].flatMap({ Int($0) }) {
                let e2 = (g.count > 3 ? g[3].flatMap { Int($0) } : nil).flatMap { $0 >= e1 ? $0 : nil } ?? e1
                if s != rs || !(e1...e2).contains(re) { return .otherEpisode }
            } else if let g = groups("(?<![a-z0-9])(?:s|season[ ._-]*)(\\d{1,2})(?![0-9e])", name), let s = g[1].flatMap({ Int($0) }), s != rs {
                return .otherEpisode
            }
        } else if let y = Int(req.title.year), !yearsIn(req.title.name).contains(y) {
            if let ny = yearsIn(name).first, abs(ny - y) > 1, have < 1 { return .otherTitle }
        }
        return have == 0 ? .otherTitle : have < 1 ? .doubt : .ok
    }

    private static func qrank(_ q: String) -> Int { switch q.uppercased() { case "4K", "2160P": 0; case "1080P": 1; case "720P": 2; case "SD", "480P": 3; default: 4 } }

    /// One entry per release (same hash or same release name), the best copy kept in the place it first appeared.
    static func dedupe(_ list: [StreamCandidate]) -> [StreamCandidate] {
        func name(_ c: StreamCandidate) -> String {
            let n = c.fileName.map(DebridFiles.base) ?? c.torrentName
            let base = n.contains(".") ? String(n[..<n.lastIndex(of: ".")!]) : n
            return norm(base).replacingOccurrences(of: " ", with: "")
        }
        func same(_ a: StreamCandidate, _ b: StreamCandidate) -> Bool {
            if a.hash == b.hash { return true }
            let na = name(a)
            return na.count >= 8 && na == name(b)
        }
        func better(_ a: StreamCandidate, _ b: StreamCandidate) -> Bool {
            qrank(a.quality) != qrank(b.quality) ? qrank(a.quality) < qrank(b.quality) : a.sizeBytes > b.sizeBytes
        }
        var out: [StreamCandidate] = []
        for s in list {
            if let i = out.firstIndex(where: { same($0, s) }) { if better(s, out[i]) { out[i] = s } } else { out.append(s) }
        }
        return out
    }
}
