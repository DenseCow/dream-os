import Foundation
import DreamCore

/// Instant Play (PreResolve.kt): the stream for what the viewer is looking at is found before Play is pressed.
///
/// A title page opening (`titleOpened`) and the first Continue Watching items (`continueWatching`) run Dream Core's
/// lookup with the `.page` purpose: the full scrape and ranking, and only Premiumize's free cache check. Nothing is
/// added to any debrid account (AllDebrid / Real-Debrid / TorBox checks add magnets, so they stay Play's); the scrape
/// and ranking are kept 30 minutes, so a Play goes straight to the debrid step (~1 s) and finds a Premiumize link
/// already remembered. Play pressed while a look-ahead runs joins that lookup and raises it (`NativeResolver.find`);
/// a Play for another title cancels the look-aheads in its way (`NativeResolver.preempt`).
public final class PlayAhead: LookAheadService, @unchecked Sendable {
    public static let shared = PlayAhead()

    private let lock = NSLock()
    /// Lookups under way, by Dream Core key, with their start.
    private var running: [String: Int64] = [:]
    private var lastContinueAt: Int64 = 0
    private var observers: [NSObjectProtocol] = []
    private var cwTask: Task<Void, Never>?
    /// Continue Watching: how many items are looked up (the newest first).
    static let continueItems = 2
    /// Look-ahead lookups at once (the rest wait; see `run`), and the waiting ask's ticket.
    static let maxAtOnce = 2
    private var active = 0
    private var waitTicket = 0
    /// Continue Watching is looked up again at most this often.
    static let continueEveryMs: Int64 = 90_000

    private init() {}

    /// Launch: Continue Watching's streams after Home has had its moment, and again whenever the history changes
    /// (a viewing ended: its next episode is the likely Play).
    public func start() {
        schedule(after: 6_000)
        startRefreshLoop()
        let nc = NotificationCenter.default
        observers.append(nc.addObserver(forName: .dreamHistoryChanged, object: nil, queue: nil) { [weak self] _ in self?.schedule(after: 3_000) })
        observers.append(nc.addObserver(forName: .dreamProfileChanged, object: nil, queue: nil) { [weak self] _ in self?.schedule(after: 2_000) })
    }

    /// The app came to the front: Continue Watching may have changed on another device.
    public func appActive() { schedule(after: 2_000) }

    private func schedule(after ms: Int64) {
        lock.withLock { cwTask?.cancel() }
        let t = Task.detached(priority: .utility) { [weak self] in
            await sleepMs(ms)
            guard let self, !Task.isCancelled else { return }
            let items = await Services.shared.history.continueWatching()
            let reqs = items.filter { !$0.hidden }.sorted { $0.updatedAt > $1.updatedAt }.prefix(Self.continueItems).map { e -> PlayRequest in
                e.title.kind == .tv
                    ? PlayRequest(title: e.title, season: max(1, e.season ?? 1), episode: max(1, e.episode ?? 1), episodeName: e.episodeName)
                    : PlayRequest(title: e.title)
            }
            self.continueWatching(Array(reqs))
        }
        lock.withLock { cwTask = t }
    }

    // MARK: LookAheadService

    public func titleOpened(_ title: Title) {
        guard NativeResolver.shared.available() else { return }
        Task.detached(priority: .utility) { [self] in
            // The likely Play: the movie, or the show's in-progress / Up Next episode (what the page's Play button plays).
            let req: PlayRequest
            if title.kind == .tv {
                let e = await Services.shared.history.entry(for: title.key)
                req = PlayRequest(title: title, season: max(1, e?.season ?? 1), episode: max(1, e?.episode ?? 1), episodeName: e?.episodeName)
            } else {
                req = PlayRequest(title: title)
            }
            self.begin(req, why: "title page")
        }
    }

    public func continueWatching(_ requests: [PlayRequest]) {
        guard NativeResolver.shared.available(), !requests.isEmpty else { return }
        let now = Mono.now()
        let go = lock.withLock { () -> Bool in
            if now - lastContinueAt < Self.continueEveryMs { return false }
            lastContinueAt = now
            return true
        }
        guard go else { return }
        Task.detached(priority: .utility) { [self] in
            for r in requests.prefix(Self.continueItems) {
                await self.run(r, why: "Continue Watching")
            }
        }
    }

    // MARK: Lookups

    private func begin(_ r: PlayRequest, why: String) {
        Task.detached(priority: .utility) { [self] in await self.run(r, why: why) }
    }

    /// Background link refresh (2026-10-28): while the app is in front, Continue Watching's newest items are looked
    /// up again whenever their remembered link has run out (links live 40 min), so the next episode stays ready;
    /// the same from a background refresh on Wi-Fi (`refreshNow`, booked by the app).
    static let refreshEveryMs: Int64 = 15 * 60_000
    private var refreshLoop: Task<Void, Never>?

    /// Starts the in-front refresh loop (idempotent).
    public func startRefreshLoop() {
        lock.withLock {
            if refreshLoop != nil { return }
            refreshLoop = Task.detached(priority: .utility) { [weak self] in
                while !Task.isCancelled {
                    await sleepMs(Self.refreshEveryMs)
                    guard let self, !Task.isCancelled else { return }
                    self.lock.withLock { self.lastContinueAt = 0 } // past the 90 s throttle: this is the timer
                    self.schedule(after: 0)
                }
            }
        }
    }

    /// A background refresh: Continue Watching's newest items looked up (and their streams' heads fetched) within
    /// [budgetMs]; returns when done or when the budget is over (the lookups go on and remember what they find).
    public func refreshNow(budgetMs: Int64) async {
        guard NativeResolver.shared.available() else { return }
        let items = await Services.shared.history.continueWatching()
        let reqs = items.filter { !$0.hidden }.sorted { $0.updatedAt > $1.updatedAt }.prefix(Self.continueItems).map { e -> PlayRequest in
            e.title.kind == .tv
                ? PlayRequest(title: e.title, season: max(1, e.season ?? 1), episode: max(1, e.episode ?? 1), episodeName: e.episodeName)
                : PlayRequest(title: e.title)
        }
        let end = Mono.now() + budgetMs
        for r in reqs {
            if Mono.now() >= end { break }
            await run(r, why: "background refresh", budgetMs: max(end - Mono.now(), 2_000))
        }
        plog.info("Instant Play: background refresh done (\(reqs.count) items)")
    }

    /// One look-ahead lookup for [r] (30 s at most); a second ask within a minute, or one with a link remembered, is skipped.
    private func run(_ r: PlayRequest, why: String, budgetMs: Int64 = 30_000) async {
        let core = NativeResolver.shared
        if core.peek(r) != nil { return }
        let k = core.key(r)
        let now = Mono.now()
        let go = lock.withLock { () -> Bool in
            if let at = running[k], now - at < 60_000 { return false }
            running[k] = now
            return true
        }
        guard go else { return }
        // At most `maxAtOnce` look-aheads scrape at once, so a burst (titles opened one after another) never fills the
        // scrapers and starves the availability checks; a newer ask waits for a place and replaces any older waiting
        // one (the page showing now is the likely Play). Nothing running is cancelled (a Play may have joined it).
        var ticket = -1
        while true {
            let state = lock.withLock { () -> Int in
                if ticket < 0 {
                    // First try: straight in when there's room, else this becomes the one waiting ask.
                    if active < Self.maxAtOnce { active += 1; return 0 }
                    waitTicket += 1
                    ticket = waitTicket
                    return 1
                }
                guard ticket == waitTicket else { return 2 }
                if active < Self.maxAtOnce { active += 1; return 0 }
                return 1
            }
            if state == 0 { break }
            if state == 2 || Task.isCancelled {
                _ = lock.withLock { running.removeValue(forKey: k) }
                plog.info("Instant Play: skipped \(core.label(r), privacy: .public) (a newer title page came first)")
                return
            }
            await sleepMs(250)
        }
        plog.info("Instant Play: looking up \(core.label(r), privacy: .public) (\(why, privacy: .public))")
        let f = await core.find(r, budgetMs: budgetMs, purpose: DebridPurpose.page)
        lock.withLock { active -= 1; _ = running.removeValue(forKey: k) }
        if let f {
            plog.info("Instant Play: link ready for \(core.label(r), privacy: .public) after \(NativeResolver.secs(Mono.now() - now), privacy: .public) (\(f.service.uppercased(), privacy: .public) \(f.quality, privacy: .public))")
            core.warmStream(r, f) // the stream's head start: its first seconds to disk before Play
        } else {
            plog.info("Instant Play: \(core.label(r), privacy: .public) ranked after \(NativeResolver.secs(Mono.now() - now), privacy: .public); Play goes straight to the debrid step")
        }
    }
}
