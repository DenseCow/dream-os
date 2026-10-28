import Foundation
import DreamCore

// DebridExpiry.kt + AccountHealth.kt: when each linked account's premium ends and whether its sign-in still works,
// each from the service's own read-only "who am I" endpoint (never a magnet upload). Tokens only ever go to their own
// service; never logged or stored here.

/// A notice Dream Notices (a later wave) shows; posted through `DebridExpiry.onNotice`.
public struct DebridNotice: Sendable, Hashable {
    public enum Severity: String, Sendable { case info, warning, critical }
    /// "expiry:ad:3d:2027-01-10": once per threshold per expiry date.
    public let key: String
    public let title: String
    public let body: String
    public let severity: Severity
    /// "renew:ad" (the action opens `DebridExpiry.renewUrl`), nil = none.
    public let action: String?
    /// Older notices whose key starts with this are stale (one live notice per service).
    public let replacesPrefix: String?
}

public enum DebridExpiry {
    public static let services = ["ad", "pm", "tb", "rd"]
    static let everyMs: Int64 = 6 * 3_600_000
    /// A failed round (offline) is tried again after this long instead of six hours.
    static let retryMs: Int64 = 20 * 60_000

    /// Where notices go (Dream Notices sets it); nil = none shown yet.
    nonisolated(unsafe) public static var onNotice: (@Sendable (DebridNotice) -> Void)?
    /// A service's warnings are stale (renewed): the notices whose key starts with this prefix go.
    nonisolated(unsafe) public static var onDismissPrefix: (@Sendable (String) -> Void)?

    /// Renewal pages.
    public static func renewUrl(_ service: String) -> URL {
        URL(string: ["ad": "https://alldebrid.com/offer/", "pm": "https://www.premiumize.me/premium",
                     "tb": "https://torbox.app/pricing", "rd": "https://real-debrid.com/premium"][service] ?? "https://alldebrid.com/offer/")!
    }

    /// What a service said, last time. [until] ms since 1970 (0 = no date given).
    public struct Status: Codable, Sendable, Hashable {
        public let service: String
        public let until: Int64
        public let premium: Bool
        public let subscribed: Bool
        public let checkedAt: Int64
        public func daysLeft(now: Int64 = wallMs()) -> Int { ExpiryRules.daysLeft(now: now, until: until) }
        public var expired: Bool { ExpiryRules.level(now: wallMs(), until: until, premium: premium, subscribed: subscribed) == .expired }
    }

    private final class State: @unchecked Sendable { let lock = NSLock(); var running = false; var lastAttempt: Int64 = 0; var startChecked = false }
    private static let state = State()

    public static func status(_ service: String) -> Status? { DreamStore.shared.get(Status.self, "expiry." + service) }

    /// One short line for a linked service's row: "Premium Until Jan 10, 2027 · 77 Days Left", "Premium Expired ...".
    public static func caption(_ service: String) -> String? {
        guard let s = status(service) else { return nil }
        if s.expired { return s.until > 0 ? "Premium Expired \(dateText(s.until, year: true))" : "No Premium Plan" }
        if s.until <= 0 { return s.premium ? "Premium Active" : nil }
        let d = s.daysLeft()
        let left = d == 0 ? "Ends Today" : d == 1 ? "Ends Tomorrow" : "\(d) Days Left"
        return s.subscribed && d > 7 ? "Premium Renews \(dateText(s.until, year: true))" : "Premium Until \(dateText(s.until, year: true)) · \(left)"
    }

    /// Settings' line: each linked service with what is known.
    public static func summary() -> String {
        let acc = DebridAccounts.get()
        let parts = services.filter { acc.token($0) != nil }.map { "\(DebridAccounts.name($0)) · \(caption($0) ?? "Not Checked Yet")" }
        return parts.isEmpty ? "No Debrid Account Linked" : parts.joined(separator: "   ")
    }

    public static func shortDate(_ at: Int64) -> String { DreamTime.format(Date(timeIntervalSince1970: Double(at) / 1000), "EEE, MMM d") }
    public static func dateText(_ at: Int64, year: Bool = false) -> String {
        DreamTime.format(Date(timeIntervalSince1970: Double(at) / 1000), year ? "MMM d, yyyy" : "EEEE, MMMM d")
    }

    private static func fingerprint(_ acc: DebridAccounts.Accounts) -> String {
        services.map { acc.token($0).map { DebridLedger.scope($0, $0) } ?? "-" }.joined(separator: ",")
    }

    /// Cheap; call when the app comes to the front. Starts a round when the last one is six hours old, or the linked
    /// accounts changed since.
    public static func maybeCheck() {
        let s = state
        let now = wallMs()
        let first: Bool? = s.lock.withLock {
            if s.running || now - s.lastAttempt < 60_000 { return nil }
            s.lastAttempt = now
            let f = !s.startChecked
            s.startChecked = true
            return f
        }
        guard let first else { return }
        Task.detached(priority: .utility) {
            let acc = DebridAccounts.get()
            let last = DreamStore.shared.get(Int64.self, "expiry._last") ?? 0
            let due = first || now - last >= everyMs || DreamStore.shared.get(String.self, "expiry._fp") != fingerprint(acc)
            pollLimits(acc)
            if due { _ = await checkNow() }
        }
    }

    /// An account was just linked or unlinked: check now.
    public static func accountsChanged() async { _ = await checkNow() }

    /// Settings' "Check Now": asks every linked service, saves what each said, posts the notices due. How many answered.
    @discardableResult
    public static func checkNow() async -> Int {
        let s = state
        if s.lock.withLock({ () -> Bool in if s.running { return true }; s.running = true; s.lastAttempt = wallMs(); return false }) { return 0 }
        defer { s.lock.withLock { s.running = false } }
        DebridAccounts.invalidate()
        let acc = DebridAccounts.get()
        var asked = 0, answered = 0
        for svc in services {
            guard let token = acc.token(svc) else { DreamStore.shared.set(nil as Status?, "expiry." + svc); continue }
            asked += 1
            guard let st = await fetch(svc, token, acc) else { continue }
            answered += 1
            DreamStore.shared.set(st, "expiry." + svc)
            plog.info("Premium check: \(DebridAccounts.name(svc), privacy: .public) \(st.until > 0 ? "premium until \(dateText(st.until, year: true)) (\(st.daysLeft()) days)" : st.premium ? "premium, no end date" : "no premium", privacy: .public)\(st.subscribed ? ", renews automatically" : "", privacy: .public)")
            notify(st, acc)
        }
        let now = wallMs()
        // A round where nothing answered (offline) counts as a short retry, not a six-hour wait.
        DreamStore.shared.set(asked > 0 && answered == 0 ? now - everyMs + retryMs : now, "expiry._last")
        DreamStore.shared.set(fingerprint(acc), "expiry._fp")
        return answered
    }

    /// The notice a status calls for, if any (one per threshold per expiry date).
    private static func notify(_ s: Status, _ acc: DebridAccounts.Accounts) {
        let name = DebridAccounts.name(s.service)
        let now = wallMs()
        let prefix = "expiry:\(s.service):"
        guard let level = ExpiryRules.level(now: now, until: s.until, premium: s.premium, subscribed: s.subscribed) else {
            onDismissPrefix?(prefix) // healthy (renewed, or far off): older warnings for this service are stale
            return
        }
        let key = ExpiryRules.key(s.service, level, s.until)
        let others = services.filter { $0 != s.service && acc.token($0) != nil && status($0)?.expired != true }
        let n: DebridNotice
        if level == .expired {
            n = DebridNotice(key: key, title: "\(name) Premium Expired",
                             body: others.isEmpty ? "Renew or Link Another Service to Keep Streaming"
                                : "Streaming Through \(others.map { DebridAccounts.name($0) }.joined(separator: " and ")) Instead",
                             severity: .critical, action: "renew:\(s.service)", replacesPrefix: prefix)
        } else {
            let d = s.daysLeft(now: now)
            n = DebridNotice(key: key, title: "\(name) Premium " + (d == 0 ? "Ends Today" : d == 1 ? "Ends Tomorrow" : "Ends in \(d) Days"),
                             body: "Renew by \(shortDate(s.until)) to Keep Streaming", severity: .warning, action: "renew:\(s.service)",
                             replacesPrefix: prefix)
        }
        onNotice?(n)
    }

    /// Dream Core marked a service as at its account limit: the day's warning.
    private static func pollLimits(_ acc: DebridAccounts.Accounts) {
        for svc in services {
            guard let t = acc.token(svc), DebridLedger.shared.limitedUntil(DebridLedger.scope(svc, t)) > wallMs() else { continue }
            onNotice?(DebridNotice(key: "limit:\(svc):\(DreamTime.todayYmd())", title: "\(DebridAccounts.name(svc)) Magnet Limit Reached",
                                   body: "It frees up automatically. Other linked services keep streaming.", severity: .warning,
                                   action: nil, replacesPrefix: nil))
        }
    }

    private static func fetch(_ svc: String, _ token: String, _ acc: DebridAccounts.Accounts) async -> Status? {
        let now = wallMs()
        do {
            switch svc {
            case "ad":
                let r = try await DebridHttp.get("\(AllDebridService.api)user?agent=\(AllDebridService.agent)", bearer: token, timeoutMs: 8_000)
                let j = r.json()
                guard j["status"].string == "success" else { plog.warning("Premium check: AllDebrid answered \(r.code) \(j["error"]["code"].string, privacy: .public)"); return nil }
                let u = j["data"]["user"]
                guard u.exists else { return nil }
                return Status(service: svc, until: u["premiumUntil"].int64 * 1000, premium: u["isPremium"].bool, subscribed: u["isSubscribed"].bool, checkedAt: now)
            case "pm":
                let r = try await DebridHttp.get("https://www.premiumize.me/api/account/info", bearer: token, timeoutMs: 8_000)
                let j = r.json()
                guard j["status"].string == "success" else { plog.warning("Premium check: Premiumize answered \(r.code)"); return nil }
                let until = j["premium_until"].isNull ? 0 : j["premium_until"].int64 * 1000
                return Status(service: svc, until: until, premium: until > now, subscribed: false, checkedAt: now)
            case "tb":
                let r = try await DebridHttp.get("https://api.torbox.app/v1/api/user/me", bearer: token, timeoutMs: 8_000)
                let j = r.json()
                guard r.ok, j["success"].bool else { plog.warning("Premium check: TorBox answered \(r.code)"); return nil }
                let d = j["data"]
                guard d.exists else { return nil }
                let until = d["premium_expires_at"].isNull ? 0 : ExpiryRules.iso(d["premium_expires_at"].string)
                return Status(service: svc, until: until, premium: d["plan"].int > 0 && (until == 0 || until > now), subscribed: d["is_subscribed"].bool, checkedAt: now)
            case "rd":
                var r = try await DebridHttp.get("https://api.real-debrid.com/rest/1.0/user", bearer: token, timeoutMs: 8_000)
                if r.code == 401 {
                    // The access token lasts about an hour: renewed with the viewer's own device credentials, and kept.
                    guard let fresh = await RealDebridService.refreshToken(acc, timeoutMs: 8_000) else { return nil }
                    r = try await DebridHttp.get("https://api.real-debrid.com/rest/1.0/user", bearer: fresh, timeoutMs: 8_000)
                }
                guard r.ok else { plog.warning("Premium check: Real-Debrid answered \(r.code)"); return nil }
                let j = r.json()
                let exp = j["expiration"].string
                var until = exp.isEmpty ? 0 : ExpiryRules.iso(exp)
                if until == 0 && j["premium"].int64 > 0 { until = now + j["premium"].int64 * 1000 }
                return Status(service: svc, until: until, premium: j["type"].string == "premium", subscribed: false, checkedAt: now)
            default: return nil
            }
        } catch {
            plog.warning("Premium check: \(DebridAccounts.name(svc), privacy: .public) failed: \(String(describing: type(of: error)), privacy: .public)")
            return nil
        }
    }
}

/// The thresholds, pure (checked with fake dates).
public enum ExpiryRules {
    public enum Level: String, Sendable { case d7 = "7d", d3 = "3d", d1 = "1d", expired }

    /// Calendar days from [now]'s date to [until]'s date in the viewer's zone (0 = today, 1 = tomorrow; negative once past).
    public static func daysLeft(now: Int64, until: Int64, zone: TimeZone = DreamTime.zone) -> Int {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        let a = cal.startOfDay(for: Date(timeIntervalSince1970: Double(now) / 1000))
        let b = cal.startOfDay(for: Date(timeIntervalSince1970: Double(until) / 1000))
        return cal.dateComponents([.day], from: a, to: b).day ?? 0
    }

    /// The notice due: nil (more than 7 days left, an auto-renewing subscription), d7 / d3 / d1, expired.
    public static func level(now: Int64, until: Int64, premium: Bool, subscribed: Bool, zone: TimeZone = DreamTime.zone) -> Level? {
        if until <= 0 { return premium ? nil : .expired }
        if until <= now { return .expired }
        if subscribed { return nil }
        let d = daysLeft(now: now, until: until, zone: zone)
        return d <= 1 ? .d1 : d <= 3 ? .d3 : d <= 7 ? .d7 : nil
    }

    /// "96 Days", "1 Day", "Ends Today", "1 Year", "2 Years", "Expired", "Active".
    public static func timeLeft(now: Int64, until: Int64, premium: Bool, subscribed: Bool) -> String {
        if level(now: now, until: until, premium: premium, subscribed: subscribed) == .expired { return "Expired" }
        if until <= 0 { return "Active" }
        let d = daysLeft(now: now, until: until)
        return d <= 0 ? "Ends Today" : d == 1 ? "1 Day" : d < 365 ? "\(d) Days" : d < 730 ? "1 Year" : "\(d / 365) Years"
    }

    public static func key(_ service: String, _ level: Level, _ until: Int64) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = TimeZone(identifier: "UTC"); f.dateFormat = "yyyy-MM-dd"
        let date = until > 0 ? f.string(from: Date(timeIntervalSince1970: Double(until) / 1000)) : "none"
        return "expiry:\(service):\(level.rawValue):\(date)"
    }

    /// ISO 8601 ("2027-01-10T20:04:02.000Z", "+00:00" offsets, or a bare date) to ms; 0 when unreadable.
    public static func iso(_ s: String) -> Int64 {
        let t = s.trimmingCharacters(in: .whitespaces)
        let pattern = "^(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2}))?(?:\\.\\d+)?)?\\s*(Z|[+-]\\d{2}:?\\d{2})?$"
        guard let re = try? NSRegularExpression(pattern: pattern),
              let m = re.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)) else { return 0 }
        func g(_ i: Int) -> String { Range(m.range(at: i), in: t).map { String(t[$0]) } ?? "" }
        var c = DateComponents()
        c.year = Int(g(1)); c.month = Int(g(2)); c.day = Int(g(3))
        c.hour = Int(g(4)) ?? 0; c.minute = Int(g(5)) ?? 0; c.second = Int(g(6)) ?? 0
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        guard let d = cal.date(from: c) else { return 0 }
        var ms = Int64(d.timeIntervalSince1970 * 1000)
        let z = g(7)
        if !z.isEmpty && z != "Z" {
            let sign: Int64 = z.hasPrefix("-") ? -1 : 1
            let digits = z.dropFirst().replacingOccurrences(of: ":", with: "")
            if digits.count >= 4 {
                let hh = Int64(digits.prefix(2)) ?? 0, mm = Int64(digits.dropFirst(2).prefix(2)) ?? 0
                ms -= sign * (hh * 3_600_000 + mm * 60_000)
            }
        }
        return ms
    }
}

/// The health of each linked account, for Settings › Streaming Accounts (AccountHealth.kt). The last result is kept,
/// so the page shows it at once and only re-asks when it's old (`stale`) or on Test Connection (`check`).
public enum AccountHealth {
    public enum Health: String, Codable, Sendable { case ok = "OK", limit = "LIMIT", invalid = "INVALID", offline = "OFFLINE" }
    /// The tone a value shows in: nil = the normal color, else the status color.
    public enum Tone: Sendable { case amber, red }

    /// [premium]: true/false when the service said, nil when unknown; [until]: plan end (ms), 0 when unknown or none.
    public struct Info: Codable, Sendable, Hashable {
        public let service: String
        public let health: Health
        public let at: Int64
        public let premium: Bool?
        public let until: Int64
        public let user: String?
    }

    public static let staleMs: Int64 = 30 * 60_000
    static let day: Int64 = 86_400_000

    public static func last(_ service: String) -> Info? { DreamStore.shared.get(Info.self, "health." + service) }
    public static func forget(_ service: String) { DreamStore.shared.set(nil as Info?, "health." + service) }
    public static func stale(_ service: String) -> Bool { last(service).map { wallMs() - $0.at > staleMs } ?? true }

    /// AllDebrid is resting at its magnet limit: until when (ms), or 0.
    public static func limitedUntil(_ service: String) -> Int64 {
        guard service == "ad", let t = DebridAccounts.get().allDebrid else { return 0 }
        let u = DebridLedger.shared.limitedUntil(DebridLedger.scope("ad", t))
        return u > wallMs() ? u : 0
    }

    /// Test Connection: one read-only account call for [service]. Saved and returned; nil when it isn't linked.
    @discardableResult
    public static func check(_ service: String) async -> Info? {
        DebridAccounts.invalidate() // a link or unlink a moment ago counts
        let acc = DebridAccounts.get()
        guard let t = acc.token(service) else { return nil }
        let now = wallMs()
        var info: Info
        do {
            switch service {
            case "ad":
                let r = try await DebridHttp.get("\(AllDebridService.api)user?agent=\(AllDebridService.agent)", bearer: t, timeoutMs: 6_000)
                let j = r.json()
                let u = j["data"]["user"]
                let code = j["error"]["code"].string
                if j["status"].string == "success" && u.exists {
                    let h: Health = limitedUntil("ad") > 0 ? .limit : .ok
                    info = Info(service: service, health: h, at: now, premium: u["isPremium"].bool(true), until: u["premiumUntil"].int64 * 1000,
                                user: u["username"].string.isEmpty ? nil : u["username"].string)
                } else if code.hasPrefix("AUTH_") || code.hasPrefix("ACCOUNT_") {
                    info = Info(service: service, health: .invalid, at: now, premium: nil, until: 0, user: nil)
                } else { info = Info(service: service, health: .offline, at: now, premium: nil, until: 0, user: nil) }
            case "pm":
                let r = try await DebridHttp.get("https://www.premiumize.me/api/account/info", bearer: t, timeoutMs: 6_000)
                let j = r.json()
                if r.ok && j["status"].string == "success" {
                    let until = j["premium_until"].int64 * 1000
                    info = Info(service: service, health: .ok, at: now, premium: until > now, until: until,
                                user: j["customer_id"].string.isEmpty ? nil : j["customer_id"].string)
                } else if r.code == 401 || r.code == 403 || j["message"].string.lowercased().contains("auth") {
                    info = Info(service: service, health: .invalid, at: now, premium: nil, until: 0, user: nil)
                } else { info = Info(service: service, health: .offline, at: now, premium: nil, until: 0, user: nil) }
            case "tb":
                let r = try await DebridHttp.get("https://api.torbox.app/v1/api/user/me", bearer: t, timeoutMs: 6_000)
                if r.code == 401 || r.code == 403 { info = Info(service: service, health: .invalid, at: now, premium: nil, until: 0, user: nil) }
                else if r.ok && r.json()["success"].bool {
                    let d = r.json()["data"]
                    let until = d["premium_expires_at"].isNull ? 0 : ExpiryRules.iso(d["premium_expires_at"].string)
                    let plan = d["plan"].exists ? d["plan"].int : -1
                    let prem: Bool? = plan == 0 ? false : until > 0 ? until > now : plan > 0 ? true : nil
                    info = Info(service: service, health: .ok, at: now, premium: prem, until: until, user: d["email"].string.isEmpty ? nil : d["email"].string)
                } else { info = Info(service: service, health: .offline, at: now, premium: nil, until: 0, user: nil) }
            case "rd":
                info = await realDebrid(acc, t, now)
            default: return nil
            }
        } catch {
            plog.warning("Streaming accounts: \(service.uppercased(), privacy: .public) check failed")
            info = Info(service: service, health: .offline, at: now, premium: nil, until: 0, user: nil)
        }
        // Keep the last known plan when this answer didn't carry one.
        if info.health == .offline, let prev = last(service) {
            info = Info(service: service, health: .offline, at: now, premium: prev.premium, until: prev.until, user: prev.user)
        }
        DreamStore.shared.set(info, "health." + service)
        plog.info("Streaming accounts: \(service.uppercased(), privacy: .public) \(info.health.rawValue, privacy: .public)")
        return info
    }

    private static func realDebrid(_ acc: DebridAccounts.Accounts, _ token: String, _ now: Int64) async -> Info {
        func read(_ r: DebridHttp.Reply) -> Info? {
            guard r.ok else { return nil }
            let j = r.json()
            return Info(service: "rd", health: .ok, at: now, premium: j["type"].string == "premium", until: ExpiryRules.iso(j["expiration"].string),
                        user: j["username"].string.isEmpty ? nil : j["username"].string)
        }
        let url = "https://api.real-debrid.com/rest/1.0/user"
        guard let r = try? await DebridHttp.get(url, bearer: token, timeoutMs: 6_000) else {
            return Info(service: "rd", health: .offline, at: now, premium: nil, until: 0, user: nil)
        }
        if let i = read(r) { return i }
        if r.code != 401 { return Info(service: "rd", health: r.code == 403 ? .invalid : .offline, at: now, premium: nil, until: 0, user: nil) }
        guard let fresh = await RealDebridService.refreshToken(acc, timeoutMs: 6_000) else {
            return Info(service: "rd", health: .invalid, at: now, premium: nil, until: 0, user: nil)
        }
        plog.info("Streaming accounts: Real-Debrid sign-in renewed")
        if let r2 = try? await DebridHttp.get(url, bearer: fresh, timeoutMs: 6_000), let i = read(r2) { return i }
        return Info(service: "rd", health: .offline, at: now, premium: nil, until: 0, user: nil)
    }

    // ---- words ----

    /// The plan line and its tone: "Premium · 214 Days Left", "Expires in 3 Days" (amber), "Expired" (red).
    public static func premiumText(_ i: Info?) -> (String, Tone?)? {
        guard let i else { return nil }
        let now = wallMs()
        if i.premium == false || (i.until > 0 && i.until < now) { return ("Expired", .red) }
        if i.until <= 0 { return i.premium == true ? ("Premium", nil) : nil }
        let days = Int((i.until - now + day - 1) / day)
        if days <= 1 && i.until - now < day / 2 { return ("Expires Today", .amber) }
        if days <= 1 { return ("Expires Tomorrow", .amber) }
        if days <= 7 { return ("Expires in \(days) Days", .amber) }
        return ("Premium · \(days) Days Left", nil)
    }

    /// The plan line: the premium check or this page's own Test Connection, whichever answered last.
    public static func plan(_ service: String) -> (String, Tone?)? {
        let mine = last(service)
        guard let e = DebridExpiry.status(service), !(mine != nil && mine?.premium != nil && mine!.at > e.checkedAt) else { return premiumText(mine) }
        if e.expired { return ("Expired", .red) }
        if e.until <= 0 { return e.premium ? ("Premium", nil) : nil }
        let d = e.daysLeft()
        if e.subscribed && d <= 7 { return (d <= 0 ? "Renews Today" : d == 1 ? "Renews Tomorrow" : "Renews in \(d) Days", nil) }
        if d <= 0 { return ("Expires Today", .amber) }
        if d == 1 { return ("Expires Tomorrow", .amber) }
        if d <= 7 { return ("Expires in \(d) Days", .amber) }
        return ("Premium · \(d) Days Left", nil)
    }

    public static func healthText(_ h: Health) -> String {
        switch h { case .ok: "Connection OK"; case .limit: "Magnet Limit Reached"; case .invalid: "Sign-In Invalid"; case .offline: "No Answer Right Now" }
    }
    public static func healthTone(_ h: Health) -> Tone? {
        switch h { case .ok: nil; case .limit, .offline: .amber; case .invalid: .red }
    }

    public static func ago(_ at: Int64) -> String {
        let m = max((wallMs() - at) / 60_000, 0)
        if m < 1 { return "Checked Just Now" }
        if m < 60 { return "Checked \(m) Min Ago" }
        if m < 48 * 60 { return "Checked \(m / 60) Hour\(m / 60 == 1 ? "" : "s") Ago" }
        return "Checked \(m / 1440) Days Ago"
    }

    /// The short value a service row shows on the right, most important first, with its tone.
    public static func summary(_ service: String) -> (String, Tone?) {
        if !DebridAccounts.linked(service) { return ("Not Linked", nil) }
        let i = last(service)
        let prem = plan(service)
        if i?.health == .invalid { return ("Sign-In Invalid", .red) }
        if let prem, prem.1 == .red { return prem }
        if limitedUntil(service) > 0 { return ("Magnet Limit Reached", .amber) }
        return prem ?? ("Linked", nil)
    }

    /// Why someone might add [service] (Add a Service).
    public static func reason(_ service: String) -> String {
        switch service {
        case "pm": "Lookups Add Nothing to Your Account"
        case "tb": "Big Cache · Lookups Add Nothing to Your Account"
        case "rd": "Largest Cache"
        case "ad": "Fast Links · Lookups Use Magnet Slots"
        default: ""
        }
    }
}
