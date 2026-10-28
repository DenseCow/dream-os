import Foundation
import DreamCore
import DreamPlayback
import DreamScrapers
import DreamPlayer

/// Points the Pages screens' hooks (Settings/PagesServices.swift) at the real modules.
@MainActor
enum Wiring {
    static func install() {
        PagesServices.debrid = RealDebrid()
        PagesServices.scrapers = RealScrapers()
        // Cellular Data Saver: on mobile data with the answer due, the question comes first (Shell/CellularPrompt.swift).
        PagesServices.play = { r in CellularPrompt.beforePlay { DreamPlayerPresenter.shared.play(r) } }
        CellularPrompt.install()
        // An empty key (the title has no trailer): the player says "Trailer Unavailable" (card menu › Play Trailer).
        PagesServices.playTrailer = { t, key in DreamPlayerPresenter.shared.playTrailer(of: t, keys: key.isEmpty ? [] : [key]) }
        // Instant Play: Python and the providers are warm before the first title page asks for a scrape.
        Scrapers.prepare()
    }
}

// MARK: Debrid accounts -> DreamPlayback (DebridAccounts, DebridLink, AccountHealth, DebridExpiry)

struct RealDebrid: DebridAccountsAPI {
    func linkedOrder() async -> [String] { DebridAccounts.linkedInOrder() }

    func state(_ service: String) async -> DebridAccountState {
        let linked = DebridAccounts.linked(service)
        var s = DebridAccountState(service: service, linked: linked, user: DebridAccounts.user(service))
        if let h = AccountHealth.last(service) {
            s.health = Self.health(h.health)
            s.premium = h.premium
            if h.until > 0 { s.premiumUntil = Date(timeIntervalSince1970: Double(h.until) / 1000) }
            if s.user == nil { s.user = h.user }
        }
        if let e = DebridExpiry.status(service) {
            s.premium = e.premium || e.subscribed
            if e.until > 0 { s.premiumUntil = Date(timeIntervalSince1970: Double(e.until) / 1000) }
        }
        return s
    }

    func check(_ service: String) async -> DebridAccountState {
        guard DebridAccounts.linked(service) else { return DebridAccountState(service: service, linked: false) }
        _ = await AccountHealth.check(service)
        return await state(service)
    }

    func usesDeviceSignIn(_ service: String) -> Bool { DebridLink.deviceSignIn(service) }

    func startDeviceLink(_ service: String) async throws -> DebridDeviceLink {
        guard let p = await DebridLink.start(service), let url = URL(string: p.url) else {
            throw DreamError("\(DebridServices.name(service)) didn't answer. Check your internet connection and try again.")
        }
        PendingLinks.shared.put(p)
        return DebridDeviceLink(service: service, code: p.code, url: url, intervalSeconds: max(1, p.interval), token: p.code)
    }

    func poll(_ link: DebridDeviceLink) async -> DebridLinkPoll {
        guard let p = PendingLinks.shared.get(link.token) else { return .failed("This code expired. Start again.") }
        let r = await DebridLink.poll(p)
        switch r {
        case .waiting: return .waiting
        case .done:
            DebridLink.finish(link.service, r)
            PendingLinks.shared.drop(link.token)
            await DebridExpiry.accountsChanged()
            return .done
        case .failed(let m):
            PendingLinks.shared.drop(link.token)
            return .failed(m)
        }
    }

    func link(_ service: String, apiKey: String) async throws {
        let r = await DebridLink.premiumizeKey(apiKey)
        switch r {
        case .done: DebridLink.finish(service, r); await DebridExpiry.accountsChanged()
        case .failed(let m): throw DreamError(m)
        case .waiting: throw DreamError("That key didn't work. Check it and try again.")
        }
    }

    func unlink(_ service: String) async { DebridAccounts.unlink(service); AccountHealth.forget(service) }
    func makePrimary(_ service: String) async { DebridAccounts.makePrimary(service) }

    private static func health(_ h: AccountHealth.Health) -> DebridAccountState.Health {
        switch h {
        case .ok, .limit: .ok
        case .invalid: .invalid
        case .offline: .offline
        }
    }
}

/// Device sign-ins in progress, by code (DebridLink.Pending keeps state the hook's struct can't carry).
final class PendingLinks: @unchecked Sendable {
    static let shared = PendingLinks()
    private let lock = NSLock()
    private var byCode: [String: DebridLink.Pending] = [:]
    func put(_ p: DebridLink.Pending) { lock.withLock { byCode[p.code] = p } }
    func get(_ code: String) -> DebridLink.Pending? { lock.withLock { byCode[code] } }
    func drop(_ code: String) { lock.withLock { byCode[code] = nil } }
}

/// Runs its block once, from whichever caller gets there first.
final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    func run(_ block: () -> Void) {
        let first = lock.withLock { let f = !done; done = true; return f }
        if first { block() }
    }
}

// MARK: Scrapers -> DreamScrapers

struct RealScrapers: ScrapersAPI {
    func status() async -> ScraperStatus {
        let s = Scrapers.status()
        return ScraperStatus(
            repos: s.repos.map(Self.repo),
            providers: s.providers.map { ScraperProviderInfo(id: $0.id, name: $0.name, caption: Scrapers.caption($0.id), enabled: $0.enabled) },
            ready: s.ready,
            recommended: Scrapers.isRecommended())
    }

    func addRepository(_ url: String, progress: @escaping @Sendable (String) -> Void) async throws -> ScraperRepoInfo {
        try Self.unwrap(await Scrapers.addRepository(url, progress: progress))
    }

    func updateAll(progress: @escaping @Sendable (String) -> Void) async throws -> ScraperRepoInfo {
        try Self.unwrap(await Scrapers.updateAll(progress: progress))
    }

    func removeRepository(_ id: String) async { await Scrapers.removeRepository(id) }
    func setEnabled(_ providerId: String, _ on: Bool) async { Scrapers.setEnabled(providerId, on) }
    func applyRecommended() async { Scrapers.applyRecommended() }

    func test(progress: @escaping @Sendable (String) -> Void) async -> ScraperTestResult {
        // The check never hangs setup: after a minute it reports back and Check Later appears.
        let timedOut = ScraperTestResult(ok: false, found: 0, ready: 0,
                                         message: "The check is taking too long. Try again in a moment.")
        // A race of unstructured tasks: a task group would wait for the stuck test before returning.
        return await withCheckedContinuation { (c: CheckedContinuation<ScraperTestResult, Never>) in
            let once = Once()
            Task {
                let t = await Scrapers.test(progress: progress)
                once.run { c.resume(returning: ScraperTestResult(ok: t.ok, found: t.found, ready: t.ready, message: t.message)) }
            }
            Task {
                try? await Task.sleep(for: .seconds(60))
                once.run { Log.app.notice("Setup check: timed out after 60 s"); c.resume(returning: timedOut) }
            }
        }
    }

    private static func repo(_ r: Scrapers.Repo) -> ScraperRepoInfo {
        ScraperRepoInfo(id: r.id, name: r.name, version: r.version, url: r.url,
                        modules: r.modules.map { ScraperModuleInfo(name: $0.name, version: $0.version) })
    }

    private static func unwrap(_ r: Scrapers.AddResult) throws -> ScraperRepoInfo {
        switch r {
        case .ok(let r, _): return repo(r)
        case .failed(let m): throw DreamError(m)
        }
    }
}
