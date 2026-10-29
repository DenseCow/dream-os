import Foundation
import DreamCore

// Trakt's storage and HTTP (fire-home Trakt.kt: Keys, Tokens, HTTP, State and queue). Per profile, like the TV:
// every profile signs in on its own. Tokens and the viewer's own Trakt app keys live in the Keychain (SecureStore),
// never in a log. The sync state and the queue of changes still to send are small JSON documents (DreamStore).

/// A Trakt app's client id and secret (`secret` is empty for an app that has none: Trakt apps made since 2026).
struct TraktKeys: Sendable, Equatable {
    let id: String
    let secret: String
}

struct TraktTokens: Codable, Sendable, Equatable {
    var a: String
    var r: String
    /// Expires at, ms since 1970.
    var e: Int64
}

/// The signed-in Trakt account (shown on the Trakt page).
public struct TraktUser: Codable, Sendable, Hashable {
    public var username: String
    public var name: String?
    /// https only.
    public var avatar: String?
}

/// One title for the scrobble and history calls: a movie, or one episode of a show (TMDB ids).
struct TraktItem: Codable, Sendable, Hashable {
    var movie: Bool
    var tmdb: Int
    var season: Int = 0
    var number: Int = 0

    /// {"movie": {"ids": {"tmdb": 1}}} / {"show": {"ids": {"tmdb": 1}}, "episode": {"season": 1, "number": 2}}
    var json: [String: Any] {
        if movie { return ["movie": ["ids": ["tmdb": tmdb]]] }
        return ["show": ["ids": ["tmdb": tmdb]], "episode": ["season": season, "number": number]]
    }

}

/// A change still to send (fire-home's queue entries): "history", "progress", "wl_add", "wl_remove", "pb_remove".
struct TraktOp: Codable, Sendable {
    var op: String
    var id: Int64 = 0
    /// wl_*: the title key ("movie:603").
    var key: String?
    /// wl_*: when it changed here (ms); history: when it was watched (ms).
    var at: Int64?
    var item: TraktItem?
    var progress: Double?
    /// pb_remove: Trakt's playback id.
    var pbId: Int64?
    /// progress: when the pause happened here (ms). Trakt stamps a paused point with the time it's sent, so one sent
    /// late is dropped once the history moved past it (`Trakt.staleProgress`). nil: queued by an older build.
    var when: Int64? = nil

    /// Newest wins for the same title and kind.
    var sameKey: String {
        switch op {
        case "wl_add", "wl_remove": "wl:" + (key ?? "")
        case "pb_remove": "pb:\(pbId ?? 0)"
        default: op + ":" + (item.map { "\($0.movie):\($0.tmdb):\($0.season):\($0.number)" } ?? "")
        }
    }
}

/// What the last sync knew, per profile (fire-home's "state" JSON).
struct TraktState: Codable, Sendable {
    struct Mark: Codable, Sendable { var inList: Bool; var at: Int64 }
    /// The last /sync/last_activities answer, as Trakt sent it.
    var la: String?
    /// When the last sync finished (ms).
    var at: Int64 = 0
    /// Something played or changed since: Home's next sync doesn't wait.
    var dirty: Bool = false
    /// TMDB show id -> when an episode was last watched (ms), for the followed shows.
    var shows: [String: Int64] = [:]
    /// "tv:1396" / "movie:603" -> Trakt's playback id (a removal here clears it there).
    var pb: [String: Int64] = [:]
    /// What was on the Trakt watchlist at the last sync (nil before the first one).
    var wl: [String]?
    /// My List changes made here: key -> (in the list, when).
    var ledger: [String: Mark] = [:]

    init() {}
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        la = try c.decodeIfPresent(String.self, forKey: .la)
        at = try c.decodeIfPresent(Int64.self, forKey: .at) ?? 0
        dirty = try c.decodeIfPresent(Bool.self, forKey: .dirty) ?? false
        shows = try c.decodeIfPresent([String: Int64].self, forKey: .shows) ?? [:]
        pb = try c.decodeIfPresent([String: Int64].self, forKey: .pb) ?? [:]
        wl = try c.decodeIfPresent([String].self, forKey: .wl)
        ledger = try c.decodeIfPresent([String: Mark].self, forKey: .ledger) ?? [:]
    }
}

/// Keychain + DreamStore access, with the tokens and keys cached in memory (the pages ask often).
final class TraktVault: @unchecked Sendable {
    static let shared = TraktVault()
    private let lock = NSLock()
    /// Guards read-modify-write of the state and queue documents.
    let store = NSLock()
    private var tokenCache: [String: TraktTokens] = [:]
    private var tokenLoaded: Set<String> = []
    private var own: TraktKeys?
    private var ownLoaded = false

    private static let ownKeysKey = "trakt.app"
    private static func authKey(_ pid: String) -> String { "trakt.auth.\(pid)" }

    // MARK: The viewer's own Trakt app

    var ownKeys: TraktKeys? {
        lock.withLock {
            if !ownLoaded {
                ownLoaded = true
                if let s = SecureStore.get(Self.ownKeysKey), let d = s.data(using: .utf8),
                   let o = try? JSONSerialization.jsonObject(with: d) as? [String: String], let id = o["id"], !id.isEmpty {
                    own = TraktKeys(id: id, secret: o["secret"] ?? "")
                }
            }
            return own
        }
    }

    /// Saves (or with nil forgets) the viewer's own app keys. False when the Keychain refused them.
    @discardableResult
    func setOwnKeys(_ k: TraktKeys?) -> Bool {
        lock.withLock {
            if let k {
                let d = (try? JSONSerialization.data(withJSONObject: ["id": k.id, "secret": k.secret])) ?? Data()
                SecureStore.set(Self.ownKeysKey, String(data: d, encoding: .utf8))
                ownLoaded = true
                // A refused write keeps the old value, so compare, not just "something is there".
                own = SecureStore.get(Self.ownKeysKey) == String(data: d, encoding: .utf8) ? k : nil
                return own != nil
            }
            SecureStore.set(Self.ownKeysKey, nil)
            own = nil
            ownLoaded = true
            return true
        }
    }

    // MARK: Tokens (per profile)

    func tokens(_ pid: String) -> TraktTokens? {
        lock.withLock {
            if !tokenLoaded.contains(pid) {
                tokenLoaded.insert(pid)
                if let s = SecureStore.get(Self.authKey(pid)), let d = s.data(using: .utf8), let t = try? JSONDecoder().decode(TraktTokens.self, from: d) {
                    tokenCache[pid] = t
                }
            }
            return tokenCache[pid]
        }
    }

    /// Saves (or with nil forgets) a profile's sign-in. False when the Keychain refused it.
    @discardableResult
    func setTokens(_ pid: String, _ t: TraktTokens?) -> Bool {
        lock.withLock {
            tokenLoaded.insert(pid)
            guard let t, let d = try? JSONEncoder().encode(t) else {
                SecureStore.set(Self.authKey(pid), nil)
                tokenCache[pid] = nil
                return true
            }
            let text = String(data: d, encoding: .utf8)
            SecureStore.set(Self.authKey(pid), text)
            guard SecureStore.get(Self.authKey(pid)) == text else { tokenCache[pid] = nil; return false }
            tokenCache[pid] = t
            return true
        }
    }

    // MARK: Per-profile documents

    func user(_ pid: String) -> TraktUser? { DreamStore.shared.get(TraktUser.self, "trakt.user.\(pid)") }
    func setUser(_ pid: String, _ u: TraktUser?) { DreamStore.shared.set(u, "trakt.user.\(pid)") }

    func expired(_ pid: String) -> Bool { DreamStore.shared.get(Bool.self, "trakt.expired.\(pid)") ?? false }
    func setExpired(_ pid: String, _ on: Bool) { DreamStore.shared.set(on ? true : nil, "trakt.expired.\(pid)") }

    func state(_ pid: String) -> TraktState { DreamStore.shared.get(TraktState.self, "trakt.state.\(pid)") ?? TraktState() }
    func setState(_ pid: String, _ s: TraktState?) { DreamStore.shared.set(s, "trakt.state.\(pid)") }

    func queue(_ pid: String) -> [TraktOp] { DreamStore.shared.get([TraktOp].self, "trakt.queue.\(pid)") ?? [] }
    func setQueue(_ pid: String, _ q: [TraktOp]?) { DreamStore.shared.set(q?.isEmpty == true ? nil : q, "trakt.queue.\(pid)") }

    /// Profiles that have Trakt data here (so a deleted profile's sign-in can be cleaned up).
    var profileIds: [String] { DreamStore.shared.get([String].self, "trakt.profiles") ?? [] }
    func remember(_ pid: String) {
        store.withLock {
            var l = profileIds
            if !l.contains(pid) { l.append(pid); DreamStore.shared.set(l, "trakt.profiles") }
        }
    }

    /// Forgets everything Trakt kept for [pid]. Returns the sign-in that was there (to revoke it).
    @discardableResult
    func wipe(_ pid: String) -> TraktTokens? {
        let t = tokens(pid)
        setTokens(pid, nil)
        store.withLock {
            setUser(pid, nil)
            setState(pid, nil)
            setQueue(pid, nil)
            setExpired(pid, false)
            let l = profileIds.filter { $0 != pid }
            DreamStore.shared.set(l.isEmpty ? nil : l, "trakt.profiles")
        }
        return t
    }
}

// MARK: HTTP

struct TraktReply: Sendable {
    let code: Int
    let body: Data
    let retryAfter: Int
    var ok: Bool { (200..<300).contains(code) }
    /// Worth trying again later (rate limit, Trakt down).
    var transient: Bool { code == 408 || code == 429 || code >= 500 }
    var object: [String: Any]? { (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] }
    var array: [[String: Any]]? { (try? JSONSerialization.jsonObject(with: body)) as? [[String: Any]] }
}

enum TraktFailure: Error { case signedOut, refused(Int), unreadable }

enum TraktHTTP {
    static let api = "https://api.trakt.tv"
    static let redirect = "urn:ietf:wg:oauth:2.0:oob"

    /// Account calls never touch the shared cache or cookies.
    static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.urlCache = nil
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpCookieStorage = nil
        c.timeoutIntervalForRequest = 20
        c.timeoutIntervalForResource = 40
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    static func body(_ o: [String: Any]) -> Data { (try? JSONSerialization.data(withJSONObject: o)) ?? Data("{}".utf8) }

    /// The client id, plus the secret only when the app has one.
    static func client(_ k: TraktKeys, _ o: [String: Any]) -> Data {
        var o = o
        o["client_id"] = k.id
        if !k.secret.isEmpty { o["client_secret"] = k.secret }
        return body(o)
    }

    /// Throws when Trakt can't be reached (offline, timeout).
    static func send(_ keys: TraktKeys, _ method: String, _ path: String, _ body: Data? = nil, token: String? = nil) async throws -> TraktReply {
        guard let url = URL(string: api + path) else { throw TraktFailure.unreadable }
        var r = URLRequest(url: url)
        r.httpMethod = method
        r.cachePolicy = .reloadIgnoringLocalCacheData
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        r.setValue(Net.userAgent, forHTTPHeaderField: "User-Agent")
        r.setValue("2", forHTTPHeaderField: "trakt-api-version")
        r.setValue(keys.id, forHTTPHeaderField: "trakt-api-key")
        if let token { r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        r.httpBody = body
        let (data, resp) = try await session.data(for: r)
        let http = resp as? HTTPURLResponse
        return TraktReply(code: http?.statusCode ?? 0, body: data,
                          retryAfter: Int(http?.value(forHTTPHeaderField: "Retry-After") ?? "") ?? 0)
    }
}

// MARK: Small helpers

/// Runs async jobs one after another, in the order they were added (fire-home's single-thread executors).
final class TraktSerial: @unchecked Sendable {
    private let lock = NSLock()
    private var tail: Task<Void, Never>?

    func run(_ op: @escaping @Sendable () async -> Void) {
        lock.withLock {
            let prev = tail
            tail = Task.detached(priority: .utility) {
                await prev?.value
                await op()
            }
        }
    }
}

/// One holder at a time across awaits (the flush and the token renewal).
actor TraktGate {
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func enter() async {
        if !busy { busy = true; return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func leave() {
        if waiters.isEmpty { busy = false } else { waiters.removeFirst().resume() }
    }
}

enum TraktTime {
    static func ms(_ s: String?) -> Int64 {
        guard let s, !s.isEmpty, s != "null" else { return 0 }
        if let d = try? Date.ISO8601FormatStyle(includingFractionalSeconds: true).parse(s) { return Int64(d.timeIntervalSince1970 * 1000) }
        if let d = try? Date.ISO8601FormatStyle().parse(s) { return Int64(d.timeIntervalSince1970 * 1000) }
        return 0
    }

    static func iso(_ ms: Int64) -> String {
        Date(timeIntervalSince1970: Double(ms) / 1000).formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: true))
    }
}

/// org.json-style reads for Trakt's answers.
enum TJ {
    static func obj(_ o: Any?, _ k: String) -> [String: Any]? { (o as? [String: Any])?[k] as? [String: Any] }
    static func str(_ o: Any?, _ k: String) -> String {
        guard let v = (o as? [String: Any])?[k] else { return "" }
        if let s = v as? String { return s }
        if v is NSNull { return "" }
        return "\(v)"
    }
    static func int(_ o: Any?, _ k: String) -> Int {
        let v = (o as? [String: Any])?[k]
        if let n = v as? NSNumber { return n.intValue }
        if let s = v as? String { return Int(s) ?? 0 }
        return 0
    }
    static func int64(_ o: Any?, _ k: String) -> Int64 {
        let v = (o as? [String: Any])?[k]
        if let n = v as? NSNumber { return n.int64Value }
        if let s = v as? String { return Int64(s) ?? 0 }
        return 0
    }
    static func double(_ o: Any?, _ k: String) -> Double { ((o as? [String: Any])?[k] as? NSNumber)?.doubleValue ?? 0 }
    /// ids.tmdb of o[k].
    static func tmdb(_ o: Any?, _ k: String) -> Int { int(obj(obj(o, k), "ids"), "tmdb") }
}
