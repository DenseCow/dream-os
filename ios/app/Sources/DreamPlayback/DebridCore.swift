import Foundation
import CryptoKit
import os
import DreamCore

// The seams of Dream Core (DebridApi.kt + DreamStore.kt's DreamAccounts): what a scraper found (`Candidate`), the
// debrid services (`DebridService`), the accounts the viewer linked (`DebridAccounts`, Keychain), the record of what
// Dream OS added to those accounts (`DebridLedger`) and a small JSON-over-HTTPS helper (`DebridHttp`).
// Tokens and stream URLs are never logged.

let plog = Logger(subsystem: "com.dreamos", category: "DreamCore")

/// Monotonic milliseconds (SystemClock.elapsedRealtime).
enum Mono { static func now() -> Int64 { Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000) } }
/// Wall-clock milliseconds since 1970.
public func wallMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

func sleepMs(_ ms: Int64) async { if ms > 0 { try? await Task.sleep(nanoseconds: UInt64(ms) * 1_000_000) } }

// MARK: JSON (org.json-style reads)

/// A loose JSON value: missing keys read as empty, numbers sent as strings still read as numbers (optString/optLong).
struct JV {
    let v: Any?
    init(_ v: Any?) { self.v = (v is NSNull) ? nil : v }
    init(data: Data) { self.init(try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])) }
    subscript(_ k: String) -> JV { JV((v as? [String: Any])?[k]) }
    subscript(_ i: Int) -> JV { guard let a = v as? [Any], i >= 0, i < a.count else { return JV(nil) }; return JV(a[i]) }
    var exists: Bool { v != nil }
    var isNull: Bool { v == nil }
    var array: [JV]? { (v as? [Any])?.map { JV($0) } }
    var object: [String: JV]? { (v as? [String: Any])?.mapValues { JV($0) } }
    var isObject: Bool { v is [String: Any] }
    var string: String {
        switch v {
        case let s as String: return s
        case let n as NSNumber: return CFGetTypeID(n) == CFBooleanGetTypeID() ? (n.boolValue ? "true" : "false") : n.stringValue
        default: return ""
        }
    }
    var int64: Int64 {
        switch v {
        case let n as NSNumber: return n.int64Value
        case let s as String: return Int64(s) ?? Double(s).map { Int64($0) } ?? 0
        default: return 0
        }
    }
    var int: Int { Int(truncatingIfNeeded: int64) }
    var double: Double? {
        switch v {
        case let n as NSNumber: return n.doubleValue
        case let s as String: return Double(s)
        default: return nil
        }
    }
    var bool: Bool {
        switch v {
        case let n as NSNumber: return n.boolValue
        case let s as String: return s.lowercased() == "true"
        default: return false
        }
    }
    func bool(_ def: Bool) -> Bool { v == nil ? def : bool }
}

// MARK: Candidates

/// One torrent, best first in the list it comes in (DebridApi.kt `Candidate`).
struct Candidate: Sendable, Hashable {
    var hash: String           // info hash, lowercase hex
    var torrentName: String
    var fileName: String?
    var fileIdx: Int?
    var sizeBytes: Int64       // 0 = unknown
    var quality: String        // "4K" | "1080p" | "720p" | "SD"
    var seeders: Int
    var season: Int?           // what was asked for (episodes)
    var episode: Int?
}

/// A playable link (DebridApi.kt `Found`): all four services serve HTTPS, so a plain-HTTP link is upgraded.
func found(_ url: String, service: String, fileName: String, sizeBytes: Int64, quality: String, hash: String) -> StreamLink? {
    let u = url.lowercased().hasPrefix("http://") ? "https://" + url.dropFirst(7) : url
    guard let link = URL(string: u) else { return nil }
    return StreamLink(url: link, service: service, fileName: fileName, sizeBytes: sizeBytes, quality: quality, hash: hash)
}

/// A debrid service the resolver can ask.
protocol DebridService: Sendable {
    /// "ad" | "pm" | "tb" | "rd".
    var key: String { get }
    /// The first of [candidates] (best first) the service has cached, unlocked to a direct link, or nil. Only cached
    /// torrents count (nothing is left downloading); anything added to the account and not used is removed again.
    /// Gives up at [deadline] (`Mono.now()`).
    func resolve(_ candidates: [Candidate], deadline: Int64) async -> StreamLink?
    /// Instant Play (2026-10-28): like `resolve`, but up to [limit] cached candidates unlocked side by side, best
    /// first: the first is played, the rest are kept as alternates in case it doesn't open. A service whose check adds
    /// to the account (AllDebrid) keeps the race small; what an alternate added stays until `discard` or the janitor.
    func resolveMany(_ candidates: [Candidate], deadline: Int64, limit: Int) async -> [StreamLink]
    /// The alternates with these info hashes aren't needed any more (the played one started, or they failed):
    /// whatever the race added to the account for them is removed. Returns at once.
    func discard(_ hashes: [String])
}

extension DebridService {
    func resolveMany(_ candidates: [Candidate], deadline: Int64, limit: Int) async -> [StreamLink] {
        [await resolve(candidates, deadline: deadline)].compactMap { $0 }
    }
    func discard(_ hashes: [String]) {}
}

/// Why a lookup runs, which sets how many torrents it may add to the viewer's account (`DebridLedger.allowance`).
/// Instant Play (2026-10-28) brought the Fire Stick's BROWSE / PAGE lookups back: they add nothing to any account.
enum DebridPurpose: Int, Comparable, Sendable {
    /// Browsing (Continue Watching on Home): the quick index only, nothing sent to any service.
    case browse = -1
    /// A title page's Instant Play look-ahead: the full scrape and ranking; only free cache checks (Premiumize).
    case page = 0
    /// The next episode, while one plays.
    case ahead = 1
    /// The source list's "Ready" marks (its top rows only).
    case list = 2
    /// A real Play (or a source the viewer picked): never rate-limited.
    case play = 3
    var maxUploads: Int { switch self { case .browse, .page: 0; case .ahead: 2; case .list: 5; case .play: 10 } }
    static func < (a: Self, b: Self) -> Bool { a.rawValue < b.rawValue }
}

// MARK: Accounts

/// The debrid accounts the viewer linked (DreamAccounts + DebridAccounts). Values live in the Keychain under stable
/// setting ids ("alldebridtoken", "realdebridrefresh"...); the service order in DreamStore. Cached for a minute.
public enum DebridAccounts {
    /// Every id kept per service; the first is the "<name>.enable" switch, the second the token.
    static let ids: [(String, [String])] = [
        ("ad", ["alldebrid.enable", "alldebridtoken", "alldebridusername"]),
        ("pm", ["premiumize.enable", "premiumizetoken", "premiumizeusername"]),
        ("tb", ["torbox.enable", "torboxtoken", "torbox.username"]),
        ("rd", ["realdebrid.enable", "realdebridtoken", "realdebridrefresh", "realdebrid.clientid", "realdebridsecret", "realdebridusername"]),
    ]
    static let userIds = ["ad": "alldebridusername", "pm": "premiumizeusername", "tb": "torbox.username", "rd": "realdebridusername"]
    /// Every service key, in the order Dream Core asks them by default: lookups that add nothing to the account first.
    public static let defaultOrder = ["pm", "tb", "rd", "ad"]
    public static let names = ["ad": "AllDebrid", "pm": "Premiumize", "tb": "TorBox", "rd": "Real-Debrid"]
    public static func name(_ service: String) -> String { names[service] ?? service.uppercased() }

    struct Accounts: Sendable {
        let raw: [String: String]
        private func on(_ service: String, _ token: String) -> String? {
            let t = raw[token] ?? ""
            return !t.trimmingCharacters(in: .whitespaces).isEmpty && raw["\(service).enable"] != "false" ? t : nil
        }
        var allDebrid: String? { on("alldebrid", "alldebridtoken") }
        var premiumize: String? { on("premiumize", "premiumizetoken") }
        var torBox: String? { on("torbox", "torboxtoken") }
        var realDebrid: String? { on("realdebrid", "realdebridtoken") }
        var realDebridRefresh: String { raw["realdebridrefresh"] ?? "" }
        var realDebridClientId: String { raw["realdebrid.clientid"] ?? "" }
        var realDebridSecret: String { raw["realdebridsecret"] ?? "" }
        var any: Bool { allDebrid != nil || premiumize != nil || torBox != nil || realDebrid != nil }
        func token(_ service: String) -> String? {
            switch service { case "ad": allDebrid; case "pm": premiumize; case "tb": torBox; case "rd": realDebrid; default: nil }
        }
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var cached: Accounts?
    nonisolated(unsafe) private static var cachedAt: Int64 = 0

    private static func secureKey(_ id: String) -> String { "debrid." + id }

    static func get() -> Accounts {
        let now = Mono.now()
        if let c = lock.withLock({ cached.flatMap { now - cachedAt < 60_000 ? $0 : nil } }) { return c }
        var raw: [String: String] = [:]
        for (_, list) in ids { for id in list { raw[id] = SecureStore.get(secureKey(id)) ?? "" } }
        let a = Accounts(raw: raw)
        lock.withLock { cached = a; cachedAt = now }
        return a
    }

    /// Forget the cached read (an account was just linked or unlinked).
    static func invalidate() { lock.withLock { cached = nil; cachedAt = 0 } }

    /// A debrid account is linked and switched on (without one nothing can play).
    public static var anyLinked: Bool { Self.get().any }

    public static func linked(_ service: String) -> Bool {
        guard let id = ids.first(where: { $0.0 == service })?.1[1] else { return false }
        return !(SecureStore.get(secureKey(id)) ?? "").isEmpty
    }

    /// The account's user name as the service gave it at sign-in, if any.
    public static func user(_ service: String) -> String? {
        guard let id = userIds[service], let u = SecureStore.get(secureKey(id)), !u.isEmpty, u != "true" else { return nil }
        return u
    }

    /// Whether Dream Core may use a linked service (Settings › a service › Use This Service).
    public static func enabled(_ service: String) -> Bool {
        guard let id = ids.first(where: { $0.0 == service })?.1[0] else { return false }
        return SecureStore.get(secureKey(id)) != "false"
    }

    public static func setEnabled(_ service: String, _ on: Bool) {
        guard let id = ids.first(where: { $0.0 == service })?.1[0] else { return }
        SecureStore.set(secureKey(id), on ? "true" : "false")
        changed("\(service.uppercased()) \(on ? "on" : "off")")
    }

    /// The viewer's service order: all four keys, first = asked first (the primary). Linked or not.
    public static func order() -> [String] {
        let saved = (DreamStore.shared.get([String].self, "debrid.order") ?? []).filter { defaultOrder.contains($0) }
        var seen = Set<String>()
        let clean = saved.filter { seen.insert($0).inserted }
        return clean + defaultOrder.filter { !clean.contains($0) }
    }

    /// The linked services in the viewer's order (the primary first).
    public static func linkedInOrder() -> [String] { order().filter { linked($0) } }

    /// Saves a new order ([keys] may be partial: the rest keep their relative order after it).
    public static func setOrder(_ keys: [String]) {
        var seen = Set<String>()
        let clean = keys.filter { defaultOrder.contains($0) && seen.insert($0).inserted }
        let full = clean + order().filter { !clean.contains($0) }
        DreamStore.shared.set(full, "debrid.order")
        plog.info("Dream OS accounts: service order \(full.map { $0.uppercased() }.joined(separator: " > "), privacy: .public)")
    }

    public static func makePrimary(_ service: String) { setOrder([service] + order().filter { $0 != service }) }

    /// Moves [service] one place earlier ([by] = -1) or later (+1).
    public static func move(_ service: String, by: Int) {
        var o = order()
        guard let i = o.firstIndex(of: service) else { return }
        let j = min(max(i + by, 0), o.count - 1)
        if i == j { return }
        o.remove(at: i); o.insert(service, at: j)
        setOrder(o)
    }

    /// An approved sign-in (`DebridLink.Poll.done`'s settings): saved at once; linking turns the service on.
    public static func link(_ service: String, settings: [String: String]) {
        guard let list = ids.first(where: { $0.0 == service })?.1 else { return }
        for id in list { SecureStore.set(secureKey(id), settings[id] ?? "") }
        SecureStore.set(secureKey(list[0]), "true")
        changed("\(service.uppercased()) linked")
        Task.detached { await DebridExpiry.accountsChanged() }
    }

    /// Signs [service] out. The "<name>.enable" switch is left as it is.
    public static func unlink(_ service: String) {
        guard let list = ids.first(where: { $0.0 == service })?.1 else { return }
        for id in list.dropFirst() { SecureStore.set(secureKey(id), nil) }
        AccountHealth.forget(service)
        changed("\(service.uppercased()) unlinked")
    }

    /// A Real-Debrid access token refreshed by the resolver: kept, so the next start doesn't refresh again.
    static func refreshedRealDebrid(access: String, refresh: String?) {
        guard linked("rd") else { return }
        SecureStore.set(secureKey("realdebridtoken"), access)
        if let refresh, !refresh.isEmpty { SecureStore.set(secureKey("realdebridrefresh"), refresh) }
        invalidate()
    }

    private static func changed(_ what: String) {
        invalidate()
        plog.info("Dream OS accounts: \(what, privacy: .public)")
        NotificationCenter.default.post(name: .dreamAccountsChanged, object: nil)
    }
}

// MARK: HTTP

/// JSON over HTTPS for the debrid services: short timeouts, no cookies, no cache. Never throws for an HTTP status.
enum DebridHttp {
    static let ua = "Dream OS"
    /// Marks a stored Premiumize credential that is the account's API key, not an OAuth token (DebridLink).
    static let apiKeyPrefix = "apikey:"

    struct Reply: Sendable {
        let code: Int
        let body: Data
        var ok: Bool { (200..<300).contains(code) }
        var text: String { String(decoding: body, as: UTF8.self) }
        func json() -> JV { body.isEmpty ? JV([String: Any]()) : JV(data: body) }
    }

    static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.urlCache = nil
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpShouldSetCookies = false
        c.httpCookieAcceptPolicy = .never
        c.timeoutIntervalForRequest = 15
        c.httpMaximumConnectionsPerHost = 6
        return URLSession(configuration: c)
    }()

    /// application/x-www-form-urlencoded (java.net.URLEncoder): letters, digits and `-._*` kept, space as `+`.
    static func enc(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics.intersection(CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"))
        allowed.insert(charactersIn: "-._*")
        return (s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s).replacingOccurrences(of: "%20", with: "+")
    }

    static func form(_ fields: [(String, String)]) -> String { fields.map { "\(enc($0.0))=\(enc($0.1))" }.joined(separator: "&") }

    /// [timeoutMs] bounds the request; [bearer] adds "Authorization: Bearer" (a Premiumize API key goes as `apikey`).
    static func request(_ url: String, method: String = "GET", bearer: String? = nil, body: Data? = nil,
                        contentType: String? = nil, timeoutMs: Int = 6_000, headers: [String: String] = [:]) async throws -> Reply {
        let apiKey = bearer.flatMap { $0.hasPrefix(apiKeyPrefix) ? String($0.dropFirst(apiKeyPrefix.count)) : nil }
        let target = apiKey.map { url + (url.contains("?") ? "&" : "?") + "apikey=" + enc($0) } ?? url
        guard let u = URL(string: target) else { throw DreamError("Something went wrong. Try again.") }
        var r = URLRequest(url: u, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: Double(max(timeoutMs, 1_000)) / 1000)
        r.httpMethod = method
        r.setValue(ua, forHTTPHeaderField: "User-Agent")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        if let bearer, apiKey == nil { r.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization") }
        for (k, v) in headers { r.setValue(v, forHTTPHeaderField: k) }
        if let body {
            r.httpBody = body
            if let contentType { r.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        }
        let (data, resp) = try await session.data(for: r)
        return Reply(code: (resp as? HTTPURLResponse)?.statusCode ?? 0, body: data)
    }

    static func get(_ url: String, bearer: String? = nil, timeoutMs: Int = 6_000) async throws -> Reply {
        try await request(url, bearer: bearer, timeoutMs: timeoutMs)
    }

    static func postForm(_ url: String, _ fields: [(String, String)], bearer: String? = nil, timeoutMs: Int = 6_000) async throws -> Reply {
        try await request(url, method: "POST", bearer: bearer, body: Data(form(fields).utf8),
                          contentType: "application/x-www-form-urlencoded", timeoutMs: timeoutMs)
    }

    /// Timeout left until [deadline], at least 1 s, at most [cap].
    static func left(_ deadline: Int64, cap: Int = 6_000) -> Int { Int(min(max(deadline - Mono.now(), 1_000), Int64(cap))) }
}

// MARK: Files inside a torrent

/// Picking the right file inside a torrent (packs hold a whole season or series).
enum DebridFiles {
    static func isVideo(_ name: String) -> Bool {
        name.range(of: "\\.(mkv|mp4|m4v|webm|avi|mov|ts)$", options: [.regularExpression, .caseInsensitive]) != nil
    }
    private static func junk(_ name: String) -> Bool {
        name.range(of: "(^|[^a-z])(sample|trailer|featurette|extras?)([^a-z]|$)", options: [.regularExpression, .caseInsensitive]) != nil
    }
    static func base(_ path: String) -> String {
        let a = path.split(separator: "/", omittingEmptySubsequences: false).last.map(String.init) ?? path
        return a.split(separator: "\\", omittingEmptySubsequences: false).last.map(String.init) ?? a
    }

    /// S02E01 / 2x01 / s2e1 style match for [season]/[episode] in [name].
    static func isEpisode(_ name: String, _ season: Int, _ episode: Int) -> Bool {
        let n = base(name)
        return n.range(of: "s0*\(season)[ ._-]*e0*\(episode)(?![0-9])", options: [.regularExpression, .caseInsensitive]) != nil ||
            n.range(of: "(?<![0-9])\(season)[x]0*\(episode)(?![0-9])", options: [.regularExpression, .caseInsensitive]) != nil
    }

    /// Among [files] (path or name, size), the one to play for [c]: the index's own file name when it's there, else for
    /// an episode the largest video named for it, else (a movie) the largest video that isn't a sample. -1 when none.
    static func pick(_ files: [(String, Int64)], _ c: Candidate) -> Int {
        if let want = c.fileName.map(base), let i = files.firstIndex(where: { base($0.0).caseInsensitiveCompare(want) == .orderedSame }) { return i }
        let videos = files.enumerated().filter { isVideo($0.element.0) && !junk(base($0.element.0)) }
        let pool: [EnumeratedSequence<[(String, Int64)]>.Element]
        if let s = c.season, let e = c.episode { pool = videos.filter { isEpisode($0.element.0, s, e) } } else { pool = videos }
        return pool.max(by: { $0.element.1 < $1.element.1 })?.offset ?? -1
    }

    /// A bare-hash magnet; the name helps the service label the item if it ever shows in the account.
    static func magnet(_ c: Candidate) -> String {
        "magnet:?xt=urn:btih:\(c.hash)" + (c.torrentName.trimmingCharacters(in: .whitespaces).isEmpty ? "" : "&dn=" + DebridHttp.enc(c.torrentName))
    }
}

// MARK: Ledger

/// What Dream OS added to the viewer's debrid accounts, kept across restarts (DreamStore, not per profile). Every info
/// hash is recorded here BEFORE it is uploaded, so whatever an account holds that isn't in the ledger is the viewer's own
/// and is never touched; a hash the viewer already had (`users`) is never claimed, even when a lookup sends it again
/// (AllDebrid answers with the viewer's own magnet id). Also kept: each service's cache answers per hash (ready ~7 days,
/// not ready ~6 h), a rolling 24 h upload count (`allowance`) and an "account limit reached" state (`limitedUntil`).
/// Scopes are "<service>:<token fingerprint>"; tokens themselves are never stored or logged here.
final class DebridLedger: @unchecked Sendable {
    static let shared = DebridLedger()

    struct Own: Codable, Sendable { var i: String?; var t: Int64; var p: Bool }
    private struct Users: Codable { var h: [String]; var at: Int64 }
    private struct Mark: Codable { var r: Bool; var t: Int64 }

    static let readyTTL: Int64 = 7 * 24 * 3_600_000
    static let notReadyTTL: Int64 = 6 * 3_600_000
    static let day: Int64 = 24 * 3_600_000
    /// Uploads in 24 h above which only real Plays upload, and the hard stop for everything but Play.
    static let softDay = 300
    static let hardDay = 600

    private let lock = NSLock()
    private var own: [String: [String: Own]] = [:]
    private var users: [String: Users] = [:]
    private var marks: [String: [String: Mark]] = [:]
    private var ups: [String: [[Int64]]] = [:]
    private var limits: [String: Int64] = [:]
    private var budgetLogged = 0
    private var _inUse: @Sendable () -> Set<String> = { [] }

    /// Info hashes of links handed out recently (maybe playing right now): cleanup never removes these.
    var inUse: Set<String> { lock.withLock { _inUse }() }
    func setInUse(_ f: @escaping @Sendable () -> Set<String>) { lock.withLock { _inUse = f } }

    static func scope(_ service: String, _ token: String) -> String {
        let d = SHA256.hash(data: Data(token.utf8))
        return service + ":" + d.prefix(4).map { String(format: "%02x", $0) }.joined()
    }

    private let store = DreamStore.shared
    private func k(_ s: String) -> String { "ledger." + s.replacingOccurrences(of: ":", with: "_") }

    private func ownL(_ scope: String) -> [String: Own] {
        if let o = own[scope] { return o }
        let o = store.get([String: Own].self, k("own." + scope)) ?? [:]
        own[scope] = o
        return o
    }
    private func saveOwn(_ scope: String, _ o: [String: Own]) { own[scope] = o; store.set(o, k("own." + scope)) }

    // ---- ownership ----

    func owned(_ scope: String) -> [String: Own] { lock.withLock { ownL(scope) } }

    /// Before an upload: [hashes] are Dream OS's from now on (the viewer's own are skipped). Returns the ones claimed.
    func claim(_ scope: String, _ hashes: [String]) -> Set<String> {
        lock.withLock {
            let u = Set(usersL(scope)?.h ?? [])
            var o = ownL(scope)
            let now = wallMs()
            var out = Set<String>()
            for h in hashes where !u.contains(h) {
                out.insert(h)
                if o[h] == nil { o[h] = Own(i: nil, t: now, p: false) } // an existing entry (kept, played) keeps its age
            }
            saveOwn(scope, o)
            return out
        }
    }

    func setId(_ scope: String, _ hash: String, _ id: String) {
        lock.withLock {
            var o = ownL(scope)
            var e = o[hash] ?? Own(i: nil, t: wallMs(), p: false)
            e.i = id
            o[hash] = e
            saveOwn(scope, o)
        }
    }

    func markPlayed(_ scope: String, _ hash: String) {
        lock.withLock {
            var o = ownL(scope)
            guard var e = o[hash] else { return }
            e.p = true; e.t = wallMs()
            o[hash] = e
            saveOwn(scope, o)
        }
    }

    /// [hashes] are gone from the account (deleted, or no longer there), or never got there.
    func release(_ scope: String, _ hashes: some Collection<String>) {
        if hashes.isEmpty { return }
        lock.withLock {
            var o = ownL(scope)
            for h in hashes { o[h] = nil }
            saveOwn(scope, o)
        }
    }

    // ---- the viewer's own items ----

    private func usersL(_ scope: String) -> Users? {
        if let u = users[scope] { return u }
        let u = store.get(Users.self, k("user." + scope))
        if let u { users[scope] = u }
        return u
    }
    func usersKnown(_ scope: String) -> Bool { lock.withLock { usersL(scope) != nil } }
    func users(_ scope: String) -> Set<String> { lock.withLock { Set(usersL(scope)?.h ?? []) } }
    /// From an account listing: everything in it that Dream OS never claimed.
    func setUsers(_ scope: String, _ hashes: Set<String>) {
        lock.withLock {
            let u = Users(h: Array(hashes), at: wallMs())
            users[scope] = u
            store.set(u, k("user." + scope))
        }
    }

    // ---- cache state per hash ----

    private func marksL(_ service: String) -> [String: Mark] {
        if let m = marks[service] { return m }
        let m = store.get([String: Mark].self, k("cache." + service)) ?? [:]
        marks[service] = m
        return m
    }

    /// True: seen ready within ~7 days; false: seen not cached within ~6 h; nil: not known.
    func cached(_ service: String, _ hash: String) -> Bool? {
        lock.withLock {
            guard let e = marksL(service)[hash] else { return nil }
            let age = wallMs() - e.t
            return age < (e.r ? Self.readyTTL : Self.notReadyTTL) ? e.r : nil
        }
    }

    func seen(_ service: String, _ results: [String: Bool]) {
        if results.isEmpty { return }
        lock.withLock {
            var o = marksL(service)
            let now = wallMs()
            for (h, r) in results { o[h] = Mark(r: r, t: now) }
            if o.count > 1500 {
                o = o.filter { now - $0.value.t <= ($0.value.r ? Self.readyTTL : Self.notReadyTTL) }
                if o.count > 1500 {
                    for (key, _) in o.sorted(by: { $0.value.t < $1.value.t }).prefix(o.count - 1200) { o[key] = nil }
                }
            }
            marks[service] = o
            store.set(o, k("cache." + service))
        }
    }

    // ---- the daily upload budget ----

    /// [n] hashes sent to [service]; [play]: for a real Play.
    func counted(_ service: String, _ n: Int, play: Bool) {
        if n <= 0 { return }
        lock.withLock {
            let now = wallMs()
            var a = (ups[service] ?? store.get([[Int64]].self, k("up." + service)) ?? []).filter { $0.count == 3 && now - $0[0] < Self.day }
            a.append([now, Int64(n), play ? 1 : 0])
            ups[service] = a
            store.set(a, k("up." + service))
        }
    }

    /// (all, not for a Play) hashes sent to [service] in the last 24 h.
    func uploads(_ service: String) -> (Int, Int) {
        lock.withLock {
            let now = wallMs()
            let a = ups[service] ?? store.get([[Int64]].self, k("up." + service)) ?? []
            ups[service] = a
            var all = 0, other = 0
            for e in a where e.count == 3 && now - e[0] < Self.day {
                all += Int(e[1]); if e[2] == 0 { other += Int(e[1]) }
            }
            return (all, other)
        }
    }

    /// How many hashes a lookup for [purpose] may send to [service] (the 24 h budget applied).
    func allowance(_ service: String, _ purpose: DebridPurpose) -> Int {
        if purpose == .play { return purpose.maxUploads }
        let (all, other) = uploads(service)
        let stop = purpose == .list ? other >= Self.hardDay : (all >= Self.softDay || other >= Self.hardDay)
        if !stop {
            if all < Self.softDay { lock.withLock { budgetLogged = 0 } }
            return purpose.maxUploads
        }
        let level = other >= Self.hardDay ? 2 : 1
        let log = lock.withLock { () -> Bool in if budgetLogged < level { budgetLogged = level; return true }; return false }
        if log {
            plog.warning("Dream Core: \(all) uploads to \(service.uppercased(), privacy: .public) in 24 h (\(other) ahead of a Play); \(level == 2 ? "only real Plays upload now" : "next-episode lookups paused; Play still works", privacy: .public)")
        }
        return 0
    }

    // ---- account limit ----

    func limitedUntil(_ scope: String) -> Int64 {
        lock.withLock {
            if let l = limits[scope] { return l }
            let l = store.get(Int64.self, k("limit." + scope)) ?? 0
            limits[scope] = l
            return l
        }
    }
    func setLimited(_ scope: String, until: Int64) {
        lock.withLock { limits[scope] = until; store.set(until, k("limit." + scope)) }
    }
}
