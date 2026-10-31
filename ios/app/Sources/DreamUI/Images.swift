import SwiftUI
import UIKit
import DreamCore

/// Remote art: a memory cache of decoded images over Net's URLCache (disk), one download per URL however many cards
/// ask for it, decoded off the main thread and prepared for display, so scrolling never decodes.
///
/// Lanes (docs/port/next.md "Art loading"): `fast` for art on screen, `spare` (its own connection) for a card's
/// hedged next choice and for retrying a download that stalled, `slow` (Net.session) for look-ahead. Each URLSession
/// talks to image.tmdb.org over one HTTP/2 connection, so a stalled connection holds every request on it: the spare
/// lane routes around it, and a fast lane that keeps stalling is replaced with a fresh session.
public final class ImageStore: @unchecked Sendable {
    public static let shared = ImageStore()
    private let memory = NSCache<NSURL, UIImage>()
    private let lock = NSLock()

    enum Lane: String { case fast, spare, slow }
    private struct Flight { let task: Task<UIImage?, Never>; let lane: Lane; let started: Date }
    private var inflight: [URL: Flight] = [:]
    /// URLs the server says don't exist (404 / 410) this session: a card skips straight to its next choice instead of
    /// asking again on every retry.
    private var missing: Set<URL> = []
    /// When fast-lane downloads stalled (timed out, or no answer for `stallAfter`): two close together replace the
    /// fast session (its connection is the likely culprit).
    private var stalls: [Date] = []

    /// A download on screen this old is not joined by a new ask: the new one goes to the spare lane instead.
    static let joinWithin: TimeInterval = 1.5
    static let stallAfter: TimeInterval = 4

    private static func session() -> URLSession {
        let c = URLSessionConfiguration.default
        c.urlCache = Net.session.configuration.urlCache
        c.httpMaximumConnectionsPerHost = 8
        // Idle timeout: a stalled connection gives up in 6 s (was 15) so the card's next ask can go elsewhere.
        c.timeoutIntervalForRequest = 6
        c.timeoutIntervalForResource = 20
        c.httpAdditionalHeaders = ["User-Agent": Net.userAgent]
        if ArtTest.on { c.requestCachePolicy = .reloadIgnoringLocalCacheData }
        return URLSession(configuration: c)
    }

    /// Art on screen gets its own connections: look-ahead fills Net.session's queue with hundreds of images, and a
    /// visible cover used to wait ~10 s behind them. Same disk cache, so look-ahead still pays off.
    private var fast: URLSession = ImageStore.session()
    private let spare: URLSession = ImageStore.session()

    init() {
        // Generous (speed pass): an iPhone has room for a few screens of decoded art.
        // Sized to the phone (a 4 GB iPhone gets ~256 MB, 8 GB ~384 MB): older iPhones never get pushed to the edge.
        memory.totalCostLimit = min(384 << 20, Int(ProcessInfo.processInfo.physicalMemory / 16))
        memory.countLimit = 1500
        // Views hold the art they show, so emptying the cache costs nothing on screen.
        NotificationCenter.default.addObserver(forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: nil) { [weak self] _ in
            self?.trimMemory()
        }
    }

    /// Downloads ahead into the disk cache only (no decode, no memory): art a later screen may show.
    public func warm(_ url: URL?) {
        guard let url, cached(url) == nil else { return }
        let asked = Date()
        Task.detached(priority: .background) {
            let began = Date()
            ArtLog.shared.begin("warm")
            var outcome = "ok"
            do { _ = try await Net.data(url) } catch { outcome = ArtLog.describe(error) }
            ArtLog.shared.end("warm", kind: "warm", url: url, outcome: outcome, asked: asked, began: began, metrics: nil, decodeMs: 0)
        }
    }

    /// The image if it is already decoded (a card showing again draws at once, no flash).
    public func cached(_ url: URL) -> UIImage? { memory.object(forKey: url as NSURL) }

    /// Dream going to the background (Safari for a sign-in, another app): the decoded art goes, so iOS is far less
    /// likely to close Dream to free memory. It comes back from the disk cache instantly on return.
    public func trimMemory() { memory.removeAllObjects() }

    private func keep(_ img: UIImage, _ url: URL) {
        let cost = Int(img.size.width * img.size.height * img.scale * img.scale * 4)
        memory.setObject(img, forKey: url as NSURL, cost: cost)
    }

    /// The image from the disk cache only (never the network), decoded: a later choice that look-ahead already
    /// fetched shows at once while the first choice downloads.
    public func fromDisk(_ url: URL) async -> UIImage? {
        if let hit = cached(url) { return hit }
        if ArtTest.on { return nil }
        let img: UIImage? = await Task.detached(priority: .userInitiated) {
            guard let cache = Net.session.configuration.urlCache,
                  let r = cache.cachedResponse(for: URLRequest(url: url)),
                  (r.response as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) ?? true,
                  let raw = UIImage(data: r.data) else { return nil }
            return await raw.byPreparingForDisplay() ?? raw
        }.value
        if let img { keep(img, url); ArtLog.shared.count("disk", "", "ok") }
        return img
    }

    /// `urgent`: a card on screen asks (the fast lane); false: look-ahead (the shared session). `hedge`: a card's
    /// backup ask while its first choice is slow (the spare lane, its own connection).
    /// `kind`: what the art is (keyArt / backdrop / poster / still), for the art log only.
    public func image(_ url: URL, urgent: Bool = true, hedge: Bool = false, kind: String = "") async -> UIImage? {
        if let hit = cached(url) { ArtLog.shared.count(urgent ? "fast" : "slow", kind, "memory"); return hit }
        if lock.withLock({ missing.contains(url) }) { ArtLog.shared.count(urgent ? "fast" : "slow", kind, "known missing"); return nil }
        let asked = Date()
        var joined = false
        let task: Task<UIImage?, Never> = lock.withLock {
            if let f = inflight[url] {
                // Look-ahead joins anything. A card on screen joins a recent on-screen download; it never waits on
                // look-ahead in the slow lane, nor on a fast-lane download that may be stuck on a stalled connection.
                let fresh = asked.timeIntervalSince(f.started) < Self.joinWithin
                if !urgent || f.lane == .spare || (f.lane == .fast && fresh) { joined = true; return f.task }
            }
            let lane: Lane = !urgent ? .slow : (hedge || inflight[url]?.lane == .fast ? .spare : .fast)
            let session = lane == .spare ? spare : fast
            let t = Task.detached(priority: urgent ? .userInitiated : .utility) { () -> UIImage? in
                await self.download(url, lane: lane, session: session, kind: kind, asked: asked)
            }
            inflight[url] = Flight(task: t, lane: lane, started: asked)
            return t
        }
        if joined { ArtLog.shared.count(urgent ? "fast" : "slow", kind, "joined") }
        let img = await task.value
        lock.withLock { if inflight[url]?.task == task { inflight[url] = nil } }
        if let img { keep(img, url) }
        return img
    }

    private func download(_ url: URL, lane: Lane, session: URLSession, kind: String, asked: Date) async -> UIImage? {
        let began = Date()
        ArtLog.shared.begin(lane.rawValue)
        var data: Data?
        var outcome = "ok"
        var metrics: URLSessionTaskMetrics?
        if lane == .slow {
            do { data = try await Net.data(url) } catch {
                if case NetError.status(let code, _) = error, code == 404 || code == 410 { markMissing(url) }
                outcome = ArtLog.describe(error)
            }
        } else if let stalled = await ArtTest.stall(lane) {
            outcome = stalled
        } else {
            // An error page (404, 429, 5xx) is no image: nil, so the caller moves to its next URL.
            let probe = MetricsProbe()
            do {
                let (d, r) = try await session.data(for: URLRequest(url: url), delegate: probe)
                let code = (r as? HTTPURLResponse)?.statusCode ?? 200
                if code == 404 || code == 410 { markMissing(url) }
                if (200..<300).contains(code) { data = d } else { outcome = "HTTP \(code)" }
            } catch {
                outcome = ArtLog.describe(error)
            }
            metrics = probe.metrics
        }
        if lane == .fast, outcome == "timeout" || (data != nil && Date().timeIntervalSince(began) > Self.stallAfter) {
            stalled(session)
        }
        let decodeStart = Date()
        var img: UIImage?
        if let data {
            if let raw = UIImage(data: data) { img = await raw.byPreparingForDisplay() ?? raw } else { outcome = "decode failed" }
        }
        ArtLog.shared.end(lane.rawValue, kind: kind, url: url, outcome: outcome, asked: asked, began: began, metrics: metrics,
                          decodeMs: Speed.ms(decodeStart))
        return img
    }

    /// A fast-lane download stalled: on the second within 15 s the fast lane gets a new session (a new connection);
    /// the old one finishes what it has and goes away.
    private func stalled(_ session: URLSession) {
        let now = Date()
        let old: URLSession? = lock.withLock {
            stalls = stalls.filter { now.timeIntervalSince($0) < 15 } + [now]
            guard stalls.count >= 2, session === fast else { return nil }
            stalls = []
            let o = fast
            fast = Self.session()
            return o
        }
        if let old {
            old.finishTasksAndInvalidate()
            Log.app.notice("Art: fast lane stalled twice, opened a new connection")
        }
    }

    private func markMissing(_ url: URL) { lock.withLock { _ = missing.insert(url) } }

    /// Fetches ahead (the hero's next slide). `afterTrailer`: art below the fold waits while a trailer is being readied
    /// (`TrailerWindow`, 2 s at most); art the page shows first never does.
    public func prefetch(_ url: URL?, afterTrailer: Bool = false) {
        guard let url, cached(url) == nil else { return }
        Task.detached(priority: .utility) {
            if afterTrailer { await TrailerWindow.yield() }
            _ = await self.image(url, urgent: false, kind: "prefetch")
        }
    }
}

/// Stress test for the art lanes (off in every build that ships): every download goes to the network, and fast-lane
/// downloads hang for 6 s and fail (as a stalled connection does) for a 10 s window after the first one and one in
/// three after that. Turn on, install, scroll Home, read the `Art:` lines, turn off.
enum ArtTest {
    static let on = false
    private static let lock = NSLock()
    nonisolated(unsafe) private static var first: Date?
    nonisolated(unsafe) private static var n = 0

    /// nil: go ahead; else the simulated outcome (after the simulated hang).
    static func stall(_ lane: ImageStore.Lane) async -> String? {
        guard on, lane == .fast else { return nil }
        let hang: Bool = lock.withLock {
            let now = Date()
            if first == nil { first = now }
            n += 1
            let t = now.timeIntervalSince(first!)
            return (t > 3 && t < 13) || n % 3 == 0
        }
        guard hang else { return nil }
        try? await Task.sleep(for: .seconds(6))
        return "timeout"
    }
}

/// Collects one request's timing (queueing, first byte, body) for the art log.
private final class MetricsProbe: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var collected: URLSessionTaskMetrics?
    var metrics: URLSessionTaskMetrics? { lock.withLock { collected } }
    func urlSession(_ session: URLSession, task: URLSessionTask, didFinishCollecting metrics: URLSessionTaskMetrics) {
        lock.withLock { collected = metrics }
    }
}

/// The art log (docs/port/next.md "Art loading"): one `Art: req` line per download with what it was, how it ended and
/// where the time went, and an `Art: …` summary every 5 s while cards are on screen (how many are blank and for how
/// long, time to first art, requests in flight per lane). Read it with
/// `idevicesyslog | grep --line-buffered 'DreamOS\[' | grep 'Art:'`.
public final class ArtLog: @unchecked Sendable {
    public static let shared = ArtLog()
    private let lock = NSLock()

    private struct Card {
        var name: String
        var kinds: [String]
        var appeared: Date
        var shownAt: Date?
        var shownKind: String?
        var trying: String?
        var tryingSince: Date?
        var loads = 0
    }
    private var cards: [UUID: Card] = [:]
    private var inflight: [String: Int] = [:]
    private var peak: [String: Int] = [:]
    private var outcomes: [String: Int] = [:]
    private var firstArt: [Int] = []
    private var leftBlank: [Int] = []
    private var requests: [Int] = []
    private var dirty = false
    private var ticking = false

    static func describe(_ error: Error) -> String {
        if let u = error as? URLError {
            switch u.code {
            case .timedOut: return "timeout"
            case .cancelled: return "cancelled"
            case .notConnectedToInternet, .networkConnectionLost: return "offline (\(u.code.rawValue))"
            default: return "URLError \(u.code.rawValue)"
            }
        }
        if case NetError.status(let code, _) = error { return "HTTP \(code)" }
        if error is CancellationError { return "cancelled" }
        return "error"
    }

    private static func size(_ url: URL) -> String {
        url.pathComponents.first { $0.hasPrefix("w") && Int($0.dropFirst()) != nil || $0 == "original" } ?? "?"
    }

    func begin(_ lane: String) {
        lock.withLock {
            inflight[lane, default: 0] += 1
            peak[lane] = max(peak[lane] ?? 0, inflight[lane] ?? 0)
            dirty = true
        }
        tick()
    }

    func count(_ lane: String, _ kind: String, _ outcome: String) {
        lock.withLock { outcomes["\(lane) \(outcome)", default: 0] += 1; dirty = true }
    }

    func end(_ lane: String, kind: String, url: URL, outcome: String, asked: Date, began: Date, metrics: URLSessionTaskMetrics?,
             decodeMs: Int) {
        let total = Speed.ms(asked)
        let sched = Int(began.timeIntervalSince(asked) * 1000)
        let (fast, spare, slow, warm): (Int, Int, Int, Int) = lock.withLock {
            inflight[lane, default: 0] -= 1
            outcomes["\(lane) \(outcome)", default: 0] += 1
            if lane == "fast" || lane == "spare" { requests.append(total) }
            dirty = true
            return (inflight["fast"] ?? 0, inflight["spare"] ?? 0, inflight["slow"] ?? 0, inflight["warm"] ?? 0)
        }
        var net = ""
        if let m = metrics, let t = m.transactionMetrics.last {
            let start = m.taskInterval.start
            func ms(_ d: Date?) -> String { d.map { String(Int($0.timeIntervalSince(start) * 1000)) } ?? "-" }
            let fetch = t.resourceFetchType == .localCache ? "disk" : (t.resourceFetchType == .networkLoad ? "net" : "other")
            net = " \(fetch) \(t.networkProtocolName ?? "?")\(t.isReusedConnection ? " reused" : " new") queue \(ms(t.fetchStartDate)) send \(ms(t.requestStartDate)) first byte \(ms(t.responseStartDate)) done \(ms(t.responseEndDate))"
        }
        let tail = url.lastPathComponent.prefix(10)
        Log.app.notice("Art: req \(lane, privacy: .public) \(kind, privacy: .public) \(Self.size(url), privacy: .public) \(outcome, privacy: .public) total \(total) ms (task start \(sched), decode \(decodeMs))\(net, privacy: .public) in flight fast \(fast) spare \(spare) slow \(slow) warm \(warm) \(tail, privacy: .public)")
    }

    // Cards

    func appear(_ id: UUID, name: String, kinds: [String]) {
        lock.withLock {
            cards[id] = Card(name: name, kinds: kinds, appeared: Date())
            dirty = true
        }
        tick()
    }

    func disappear(_ id: UUID) {
        lock.withLock {
            if let c = cards.removeValue(forKey: id), c.shownAt == nil { leftBlank.append(Speed.ms(c.appeared)) }
            dirty = true
        }
    }

    func loading(_ id: UUID) { lock.withLock { cards[id]?.loads += 1 } }

    private var hedged = 0
    func hedge() { lock.withLock { hedged += 1 } }

    func trying(_ id: UUID, _ kind: String?) { lock.withLock { cards[id]?.trying = kind; cards[id]?.tryingSince = Date() } }

    func shown(_ id: UUID, kind: String) {
        lock.withLock {
            guard var c = cards[id] else { return }
            if c.shownAt == nil { firstArt.append(Speed.ms(c.appeared)); c.shownAt = Date() }
            c.shownKind = kind
            cards[id] = c
            dirty = true
        }
    }

    private func tick() {
        let start: Bool = lock.withLock { if ticking { return false }; ticking = true; return true }
        guard start else { return }
        Task.detached(priority: .utility) {
            while true {
                try? await Task.sleep(for: .seconds(5))
                if !self.summary() { break }
            }
        }
    }

    private static func pct(_ xs: [Int], _ p: Double) -> Int {
        guard !xs.isEmpty else { return 0 }
        let s = xs.sorted()
        return s[min(s.count - 1, Int(Double(s.count - 1) * p))]
    }

    /// One summary; false stops the clock (nothing happened).
    private func summary() -> Bool {
        let now = Date()
        let (lines, keep): ([String], Bool) = lock.withLock {
            guard dirty || cards.values.contains(where: { $0.shownAt == nil }) else { ticking = false; return ([], false) }
            dirty = false
            let blank = cards.values.filter { $0.shownAt == nil }
            func older(_ s: Double) -> Int { blank.filter { now.timeIntervalSince($0.appeared) > s }.count }
            let byKind = Dictionary(grouping: cards.values.compactMap(\.shownKind), by: { $0 }).mapValues(\.count)
                .sorted { $0.key < $1.key }.map { "\($0.key) \($0.value)" }.joined(separator: ", ")
            let outs = outcomes.sorted { $0.key < $1.key }.map { "\($0.key) \($0.value)" }.joined(separator: ", ")
            var out = ["Art: cards \(cards.count), blank \(blank.count) (>1 s \(older(1)), >3 s \(older(3)), >10 s \(older(10))), showing [\(byKind)]; first art p50 \(Self.pct(firstArt, 0.5)) p90 \(Self.pct(firstArt, 0.9)) max \(firstArt.max() ?? 0) ms (n \(firstArt.count)); left blank \(leftBlank.count) (p50 \(Self.pct(leftBlank, 0.5)) ms); on-screen req p50 \(Self.pct(requests, 0.5)) p90 \(Self.pct(requests, 0.9)) max \(requests.max() ?? 0) ms (n \(requests.count)); hedged \(hedged); in flight fast \(inflight["fast"] ?? 0)/peak \(peak["fast"] ?? 0) spare \(inflight["spare"] ?? 0)/\(peak["spare"] ?? 0) slow \(inflight["slow"] ?? 0)/\(peak["slow"] ?? 0) warm \(inflight["warm"] ?? 0)/\(peak["warm"] ?? 0); outcomes [\(outs)]"]
            for c in blank.filter({ now.timeIntervalSince($0.appeared) > 3 }).sorted(by: { $0.appeared < $1.appeared }).prefix(10) {
                let waited = String(format: "%.1f", now.timeIntervalSince(c.appeared))
                let on = c.tryingSince.map { String(format: "%.1f", now.timeIntervalSince($0)) } ?? "-"
                out.append("Art: blank \(waited) s \"\(c.name)\" [\(c.kinds.joined(separator: ","))] trying \(c.trying ?? "nothing") for \(on) s, loads \(c.loads)")
            }
            firstArt = []; leftBlank = []; requests = []; outcomes = [:]; hedged = 0
            peak = inflight
            return (out, true)
        }
        for l in lines { Log.app.notice("\(l, privacy: .public)") }
        return keep
    }
}


/// The best choice index that has arrived for one load (shared by its hedged asks).
private final class Arrived: @unchecked Sendable {
    private let lock = NSLock()
    private var best = Int.max
    func note(_ i: Int) { lock.withLock { best = min(best, i) } }
    /// True when something at least as good as choice `i` is already in.
    func covers(_ i: Int) -> Bool { lock.withLock { best <= i } }
}

/// An image from `urls` (the first that loads), filling its frame. Shows `placeholder` until then and fades the art in;
/// art already in memory shows at once. Its size never depends on the image.
///
/// The art shown is held by the view itself, not only by the memory cache: the cache may drop images at any time
/// (memory pressure, a trailer or the player starting), and a card that only read the cache went back to its
/// placeholder on its next redraw and never loaded again (episode cards showing "E3" with art on the network).
public struct RemoteImage<Placeholder: View>: View {
    let urls: [URL]
    /// What each URL is (keyArt / backdrop / poster / still), for the art log.
    let kinds: [String]
    let label: String
    let contentMode: ContentMode
    let alignment: Alignment
    let placeholder: Placeholder
    @State private var image: UIImage?
    /// Which URL `image` came from (it shows while that URL is still one of the choices).
    @State private var loaded: URL?
    @State private var token = UUID()

    /// How long a card's choice may take before its next choice is asked too (on the spare lane).
    static var hedgeAfter: Double { 0.5 }

    public init(_ urls: [URL?], kinds: [String] = [], label: String = "", contentMode: ContentMode = .fill,
                alignment: Alignment = .center, @ViewBuilder placeholder: () -> Placeholder) {
        var u: [URL] = [], k: [String] = []
        for (i, url) in urls.enumerated() {
            guard let url else { continue }
            u.append(url)
            k.append(i < kinds.count ? kinds[i] : "art\(i)")
        }
        self.urls = u
        self.kinds = k
        self.label = label
        self.contentMode = contentMode
        self.alignment = alignment
        self.placeholder = placeholder()
    }

    /// The best choice on hand: the earliest URL that is held or in memory.
    private var shown: UIImage? {
        for u in urls {
            if u == loaded, let image { return image }
            if let hit = ImageStore.shared.cached(u) { return hit }
        }
        return nil
    }

    /// Index of the choice the view holds (nil: none). Arrivals compare against this, not `best`: `ImageStore` puts an
    /// image in memory before handing it over, so `best` already counts it, and comparing with `best` skipped the
    /// hold, left the view without a redraw and the card blank (ae9baec's blank rows).
    private var held: Int? {
        guard image != nil, let loaded else { return nil }
        return urls.firstIndex(of: loaded)
    }

    /// Index of the best choice on hand (nil: nothing yet).
    private var best: Int? {
        urls.firstIndex { ($0 == loaded && image != nil) || ImageStore.shared.cached($0) != nil }
    }

    public var body: some View {
        Color.clear
            .overlay(alignment: alignment) {
                if let img = shown {
                    Image(uiImage: img)
                        .resizable()
                        .aspectRatio(contentMode: contentMode)
                        .transition(.opacity)
                } else {
                    placeholder
                }
            }
            .clipped()
            .onAppear {
                ArtLog.shared.appear(token, name: label, kinds: kinds)
                if let i = best { ArtLog.shared.shown(token, kind: kinds[i]) }
            }
            .onDisappear { ArtLog.shared.disappear(token) }
            .task(id: urls) { await load() }
    }

    private func hold(_ u: URL, _ img: UIImage, animated: Bool) {
        if animated { withAnimation(.easeOut(duration: 0.22)) { image = img; loaded = u } }
        else { image = img; loaded = u }
        if let i = urls.firstIndex(of: u) { ArtLog.shared.shown(token, kind: kinds[i]) }
    }

    /// Holds what memory has, then what the disk cache has (any choice better than that, no network), then asks the
    /// network for the choices better than the one showing (`race`). While nothing shows it keeps trying (a dropped
    /// download, a busy moment on the network) for as long as the view is on screen, backing off; while a later
    /// choice shows, the first choice is tried a few more times (a card's key art over its textless backdrop, an
    /// episode's still over the show's backdrop).
    private func load() async {
        guard !urls.isEmpty else { return }
        ArtLog.shared.loading(token)
        if let i = best {
            let u = urls[i]
            if u != loaded, let hit = ImageStore.shared.cached(u) { hold(u, hit, animated: false) }
            if i == 0 { return }
        }
        await fromDisk()
        if Task.isCancelled || best == 0 { return }
        let empty: [Double] = [0, 1.2, 3.5, 8, 15, 30, 60, 120]
        let better: [Double] = [0, 4, 15]
        var round = 0
        while !Task.isCancelled {
            let pauses = best == nil ? empty : better
            guard round < pauses.count else { return }
            let pause = pauses[round]
            round += 1
            if pause > 0 {
                ArtLog.shared.trying(token, nil)
                try? await Task.sleep(for: .seconds(pause))
                if Task.isCancelled { return }
            }
            await race(Array(urls.prefix(best ?? urls.count)))
            ArtLog.shared.trying(token, nil)
            if Task.isCancelled || best == 0 { return }
        }
    }

    /// Every choice better than the one showing, read from the disk cache at once (cheap: no network); the best found
    /// shows. Look-ahead often has a card's backdrop on disk while its key art is still to come.
    private func fromDisk() async {
        let choices = Array(urls.prefix(best ?? urls.count))
        guard !choices.isEmpty else { return }
        let found: (Int, UIImage)? = await withTaskGroup(of: (Int, UIImage?).self) { g in
            for (i, u) in choices.enumerated() { g.addTask { (i, await ImageStore.shared.fromDisk(u)) } }
            var top: (Int, UIImage)?
            for await (i, img) in g {
                guard let img else { continue }
                if top.map({ i < $0.0 }) ?? true { top = (i, img) }
                if i == 0 { g.cancelAll() }
            }
            return top
        }
        if let (i, img) = found, held.map({ i < $0 }) ?? true { hold(choices[i], img, animated: false) }
    }

    /// Asks the network for `choices` (best first): the first at once, and each next one `hedgeAfter` later while
    /// nothing at least as good has arrived. A stalled or slow download never holds a card blank: its next choice
    /// shows (from the spare lane's own connection), and the better one swaps in when it lands. On a quick network
    /// only the first is ever asked.
    private func race(_ choices: [URL]) async {
        let arrived = Arrived()
        let kinds = self.kinds, token = self.token, hedgeAfter = Self.hedgeAfter
        await withTaskGroup(of: (Int, UIImage?).self) { g in
            for (i, u) in choices.enumerated() {
                g.addTask {
                    if i > 0 {
                        try? await Task.sleep(for: .seconds(hedgeAfter * Double(i)))
                        if Task.isCancelled || arrived.covers(i) { return (i, nil) }
                        ArtLog.shared.hedge()
                    }
                    ArtLog.shared.trying(token, kinds[i])
                    return (i, await ImageStore.shared.image(u, hedge: i > 0, kind: kinds[i]))
                }
            }
            for await (i, img) in g {
                guard let img else { continue }
                arrived.note(i)
                if held.map({ i < $0 }) ?? true { hold(choices[i], img, animated: true) }
                if i == 0 { g.cancelAll() }
            }
        }
    }
}

public extension RemoteImage where Placeholder == Color {
    init(_ urls: [URL?], kinds: [String] = [], label: String = "", contentMode: ContentMode = .fill, alignment: Alignment = .center) {
        self.init(urls, kinds: kinds, label: label, contentMode: contentMode, alignment: alignment) { Color.clear }
    }
}
