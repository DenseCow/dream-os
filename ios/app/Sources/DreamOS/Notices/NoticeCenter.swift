import Foundation
import Observation
import DreamCore

// Dream Notices (DreamNotices.kt): Dream OS's notification center. One app-private, persistent list of notices
// (newest first, the last `keep`), each with a dedupe key, a severity, read state and an optional action.
//
// Adding a new kind of notice is one call, from any thread:
//
//     NoticeCenter.shared.post(key: "update:1.4", type: .update, title: "Dream OS 1.4 Is Ready", body: "Restart Dream OS to finish.")
//
// A key is posted once: posting it again (even after it was dismissed) does nothing, so put whatever makes the event
// new into the key (a date, a version). A new notice shows as a banner over Dream OS (NoticeBoard + NoticeBannerHost);
// while Dream Player, the Settings sheet or the Notifications list is in front it waits. The bell on Home and
// Settings > Notifications open the list.
//
// Actions are plain strings (`Notice.Action.target`) the UI knows how to run: "renew:<service>" ("ad" | "pm" | "tb" |
// "rd") opens that service's renewal page; "accounts" opens Accounts; "title:<kind>:<id>:<name>" opens a title page;
// "home" goes to Home. Nothing here ever holds a token.

struct Notice: Codable, Sendable, Hashable, Identifiable {
    enum Severity: String, Codable, Sendable { case info, warning, critical }
    enum Kind: String, Codable, Sendable { case expiry, limit, episodes, update, info, summary }

    /// What tapping the notice does: [label] names it ("Renew"), [target] says what to open (see above).
    struct Action: Codable, Sendable, Hashable {
        var label: String
        var target: String
    }

    var key: String
    var type: Kind
    var title: String
    var body: String
    var severity: Severity
    /// When it was posted, ms since 1970.
    var at: Int64
    var read: Bool
    var action: Action?
    var id: String { key }

    /// The startup summary's "Name: value" lines (shown as two aligned columns).
    var isSummary: Bool { type == .summary && body.contains(": ") }
}

/// The store. Thread-safe; every change is told to `NoticeBoard` on the main actor.
final class NoticeCenter: @unchecked Sendable {
    static let shared = NoticeCenter()

    /// Notices kept (oldest drop off).
    static let keep = 50
    /// Dedupe keys remembered after their notice is gone (so a dismissed notice never comes back).
    static let seenKeep = 300
    /// Kinds that are never kept in the list, only shown as a banner (the list is for account expiry, the owner's rule).
    static let bannerOnly: Set<Notice.Kind> = [.summary, .limit]

    private let lock = NSLock()
    private var cache: [Notice]?
    /// Banner-only notices still waiting for, or on, their banner.
    private var transient = Set<String>()

    private static let listKey = "notices.list"
    private static let seenKey = "notices.seen"

    private func load() -> [Notice] {
        if let cache { return cache }
        var list = DreamStore.shared.get([Notice].self, Self.listKey) ?? []
        // Banner-only kinds never stay in the list.
        let before = list.count
        list.removeAll { Self.bannerOnly.contains($0.type) }
        if list.count != before { DreamStore.shared.set(list, Self.listKey) }
        cache = list
        return list
    }

    private func save(_ list: [Notice]) {
        cache = list
        DreamStore.shared.set(list, Self.listKey)
    }

    private func seen() -> [String] { DreamStore.shared.get([String].self, Self.seenKey) ?? [] }

    // ---- reading ----

    /// Every notice, newest first.
    func all() -> [Notice] { lock.withLock { load() } }
    func unreadCount() -> Int { lock.withLock { load().filter { !$0.read }.count } }
    func find(_ key: String) -> Notice? { lock.withLock { load().first { $0.key == key } } }

    // ---- writing ----

    /// Posts a notice once per [key]; true when it is new. It goes to the top of the list, unread, and shows as a banner
    /// (now, or once Dream OS is free) unless [banner] is off. [store] off: a banner only, never in the list or the badge.
    /// [replace]: shown again even if the key was posted before (the summary on every start). Any thread.
    @discardableResult
    func post(key: String, type: Notice.Kind, title: String, body: String, severity: Notice.Severity = .info,
              action: Notice.Action? = nil, banner: Bool = true, replace: Bool = false, store: Bool? = nil) -> Bool {
        let keepIt = store ?? !Self.bannerOnly.contains(type)
        let n = Notice(key: key, type: type, title: title, body: body, severity: severity, at: wallNow(), read: false, action: action)
        let fresh: Bool = lock.withLock {
            var seen = seen()
            var list = load()
            if replace { list.removeAll { $0.key == key } }
            else if seen.contains(key) || list.contains(where: { $0.key == key }) { return false }
            if keepIt {
                list.insert(n, at: 0)
                if list.count > Self.keep { list.removeLast(list.count - Self.keep) }
                save(list)
            } else {
                transient.insert(key)
                if replace { save(list) }
            }
            if !seen.contains(key) { seen.append(key) }
            if seen.count > Self.seenKeep { seen.removeFirst(seen.count - Self.seenKeep) }
            DreamStore.shared.set(seen, Self.seenKey)
            return true
        }
        guard fresh else { return false }
        Log.app.notice("Notices: posted \(key, privacy: .public) (\(severity.rawValue, privacy: .public))")
        Task { @MainActor in
            NoticeBoard.shared.changed()
            if banner { NoticeBoard.shared.enqueue(n) }
        }
        return true
    }

    func markRead(_ key: String) { edit { list in for i in list.indices where list[i].key == key { list[i].read = true } } }
    func markAllRead() { edit { list in for i in list.indices { list[i].read = true } } }
    func dismiss(_ key: String) { edit { $0.removeAll { $0.key == key } } }
    /// Removes every notice whose key starts with [prefix] (a service's old expiry warnings after a renewal). Their keys stay seen.
    func dismissPrefix(_ prefix: String) { edit { $0.removeAll { $0.key.hasPrefix(prefix) } } }
    /// One live notice per service: the others with [prefix] go, [key] stays.
    func dismissPrefix(_ prefix: String, except key: String) { edit { $0.removeAll { $0.key.hasPrefix(prefix) && $0.key != key } } }
    func clearAll() { edit { $0.removeAll() } }

    /// Forgets that keys starting with [prefix] were ever posted, so they can fire again (a renewal starts a new period).
    func forget(_ prefix: String) {
        lock.withLock {
            var s = seen()
            let before = s.count
            s.removeAll { $0.hasPrefix(prefix) }
            if s.count != before { DreamStore.shared.set(s, Self.seenKey) }
        }
    }

    private func edit(_ change: (inout [Notice]) -> Void) {
        let changed: Bool = lock.withLock {
            var list = load()
            let before = list.map { "\($0.key)|\($0.read)" }
            change(&list)
            if list.map({ "\($0.key)|\($0.read)" }) == before { return false }
            save(list)
            return true
        }
        guard changed else { return }
        Task { @MainActor in NoticeBoard.shared.changed(dropStale: true) }
    }

    /// A banner not yet shown that is no longer worth showing (read or removed meanwhile).
    func stillNew(_ key: String) -> Bool {
        lock.withLock { transient.contains(key) || load().first { $0.key == key }?.read == false }
    }

    /// A banner-only notice has been shown (or dropped): nothing of it is kept.
    func bannerDone(_ key: String) { _ = lock.withLock { transient.remove(key) } }

    // ---- hooks for other parts of Dream OS ----

    /// "AllDebrid", "Premiumize"...
    static func serviceName(_ s: String) -> String {
        switch s { case "ad": "AllDebrid"; case "pm": "Premiumize"; case "tb": "TorBox"; case "rd": "Real-Debrid"; default: s }
    }

    /// Developer preview: a warning, a critical notice and the summary, new keys each time.
    func demo() {
        let t = wallNow()
        post(key: "demo:warn:\(t)", type: .expiry, title: "AllDebrid Premium Ends in 3 Days",
             body: "Renew by \(DreamTime.format(Date().addingTimeInterval(3 * 86_400), "EEE, MMM d")) to Keep Streaming",
             severity: .warning, action: .init(label: "Renew", target: "renew:ad"))
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) {
            self.post(key: "demo:crit:\(t)", type: .expiry, title: "Premiumize Premium Expired", body: "Streaming Through AllDebrid Instead",
                      severity: .critical, action: .init(label: "Renew", target: "renew:pm"))
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) {
            self.post(key: "demo:sum:\(t)", type: .summary, title: "Your Accounts",
                      body: "Real-Debrid: 5 Days\nPremiumize: 21 Days\nAllDebrid: 76 Days\nTorBox: Renews Automatically", replace: true)
        }
    }

    /// Developer: removes the demo notices.
    func clearDemo() { dismissPrefix("demo:") }
}

func wallNow() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
