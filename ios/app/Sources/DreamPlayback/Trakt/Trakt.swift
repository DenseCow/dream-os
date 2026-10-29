import Foundation
import DreamCore
#if canImport(UIKit)
import UIKit
#endif

public extension Notification.Name {
    /// Trakt signed in or out, a sync started or finished (the Trakt page and the Settings row repaint). Main thread.
    static let dreamTraktChanged = Notification.Name("dream.traktChanged")
}

/// Trakt sync, per Dream OS profile (fire-home Trakt.kt, 1:1).
///
///  - Sign-in: Trakt's device code (`startDevice` / `poll`); the page is DreamOS/Settings/TraktView. Tokens are kept in
///    the Keychain, never logged, and renewed an hour before they expire (or on a 401).
///  - Scrobble: Dream Player's reports (`PlayerReports`, DreamCore) feed `TraktScrobbler`: start, pause and stop go out
///    in the background, never in playback's way. A stop or pause that can't be sent is queued (a finished one as a
///    history entry with its real time) and sent with the next sync.
///  - Sync (`sync`): /sync/last_activities first, then only what changed: recent history and playback progress into
///    Continue Watching (`WatchHistory.mergeRemote`, last write wins; a finished episode becomes Up Next), and the
///    watchlist both ways with My List. On sign-in, when the app comes to the front (throttled) and after playback.
///  - `followedShowTmdbIds`: watchlist shows, paused shows and shows watched lately, for New Episodes.
///
/// Keys: the viewer's own Trakt app (entered on the Trakt page, kept in the Keychain) wins, else the build's own
/// (`Keys.traktClientId` / `Keys.traktClientSecret`). The client id alone is enough for the device sign-in. Without
/// any keys, nothing Trakt runs.
public final class Trakt: @unchecked Sendable {
    public static let shared = Trakt()

    /// Home opening (the app coming to the front) syncs at most this often; a finished playback skips the wait.
    static let homeEveryMs: Int64 = 15 * 60_000
    /// Watchlist pages of `watchlistPage` read per type (10,000 titles; past that the sync leaves My List alone).
    static let watchlistPage = 500
    static let watchlistPages = 20
    /// Renew the access token when it has less than this left.
    static let refreshAheadMs: Int64 = 60 * 60_000
    /// Trakt counts a stop at this percentage or more as watched.
    static let watchedPct = 80.0
    /// History older than this doesn't come into Continue Watching.
    static let historyDays: Int64 = 180

    /// Where a Trakt account creates its own apps.
    public static let appsURL = URL(string: "https://trakt.tv/oauth/applications")!
    /// The redirect URI Trakt's app form asks for (device sign-in doesn't use it, but the form requires one).
    public static let redirectURI = TraktHTTP.redirect

    let vault = TraktVault.shared
    /// Scrobbles, the queue and small pushes: one after another, in order.
    let io = TraktSerial()
    /// Syncs (TMDB lookups can take a while): their own line, so a scrobble never waits behind one.
    let syncer = TraktSerial()
    let flushGate = TraktGate()
    let refreshGate = TraktGate()
    let lock = NSLock()
    var _syncing = false
    var _lastSyncFailed = false
    /// Title keys My List is changing because of a sync (their change isn't pushed back to Trakt).
    var applying: Set<String> = []
    private var installed = false

    /// The current profile (every Trakt call is for one profile; the sign-in belongs to it alone).
    var pid: String { DreamStore.shared.profileId }

    // MARK: Launch

    /// Called once at launch (DreamPlayback.register): plugs into the watch history and My List, New Episodes, Dream
    /// Player's reports, the app coming to the front and profile switches.
    public func install() {
        let first: Bool = lock.withLock { let f = !installed; installed = true; return f }
        guard first else { return }
        WatchHistory.sync = TraktHistorySync()
        FollowedShows.add { Trakt.shared.followedShowTmdbIds() }
        let nc = NotificationCenter.default
        nc.addObserver(forName: .dreamPlayerReport, object: nil, queue: .main) { n in
            guard let r = n.object as? PlayerReport else { return }
            MainActor.assumeIsolated { TraktScrobbler.shared.report(r) }
        }
        nc.addObserver(forName: .dreamPlayerClosed, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { TraktScrobbler.shared.closed() }
        }
        nc.addObserver(forName: .dreamProfileChanged, object: nil, queue: .main) { _ in
            Trakt.shared.forgetRemovedProfiles()
            Trakt.shared.changed()
            Trakt.shared.onHomeOpened()
        }
        #if canImport(UIKit)
        nc.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            Trakt.shared.onHomeOpened()
        }
        #endif
        forgetRemovedProfiles()
    }

    /// A deleted profile's Trakt sign-in and sync state go with it (revoked on Trakt, best effort).
    func forgetRemovedProfiles() {
        let live = Set(Profiles.shared.all().map(\.id))
        let keys = self.keys
        for p in vault.profileIds where !live.contains(p) {
            let t = vault.wipe(p)
            plog.info("Trakt: removed profile's sign-in forgotten")
            if let t, let keys { revoke(t, keys) }
        }
    }

    func changed() {
        Task { @MainActor in NotificationCenter.default.post(name: .dreamTraktChanged, object: nil) }
    }

    // MARK: Keys

    var builtIn: TraktKeys? {
        let id = Keys.traktClientId.trimmingCharacters(in: .whitespacesAndNewlines)
        return id.isEmpty ? nil : TraktKeys(id: id, secret: Keys.traktClientSecret.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    /// The keys every Trakt call uses: the viewer's own app first, else the build's. Nil when neither.
    var keys: TraktKeys? { vault.ownKeys ?? builtIn }

    /// This build carries its own Trakt app.
    public var hasBuiltInKeys: Bool { builtIn != nil }
    /// Trakt can be used: built-in keys, or the viewer's own app.
    public var configured: Bool { keys != nil }
    /// Running on the viewer's own Trakt app (the page offers Remove Trakt App Keys).
    public var usesOwnKeys: Bool { vault.ownKeys != nil }
    /// "abcd…wxyz" of the client id in use, for the Advanced page.
    public var clientIdHint: String? { keys.map { Self.masked($0.id) } }

    public static func masked(_ v: String) -> String { v.count <= 10 ? v : String(v.prefix(4)) + "…" + String(v.suffix(4)) }

    // MARK: Account state

    /// Signed in on the current profile (cheap: memory after the first look, no network).
    public var signedIn: Bool { configured && vault.tokens(pid) != nil }
    func signedIn(_ p: String) -> Bool { configured && vault.tokens(p) != nil }

    /// The sign-in ended (Trakt refused to renew it): the page offers Sign In Again.
    public var expired: Bool { configured && !signedIn && vault.expired(pid) }

    public var user: TraktUser? { vault.user(pid) }

    public var isSyncing: Bool { lock.withLock { _syncing } }
    /// The last sync couldn't finish (offline, Trakt down); the page says so in plain words.
    public var lastSyncFailed: Bool { lock.withLock { _lastSyncFailed } }

    public var lastSyncedAt: Int64 { vault.state(pid).at }

    /// "Last Synced 2 Min Ago" / "Not Synced Yet".
    public var lastSyncedText: String {
        let at = lastSyncedAt
        if at <= 0 { return "Not Synced Yet" }
        let m = max(0, (wallMs() - at) / 60_000)
        if m < 1 { return "Last Synced Just Now" }
        if m < 60 { return "Last Synced \(m) Min Ago" }
        if m < 48 * 60 { let h = m / 60; return "Last Synced \(h) Hour\(h == 1 ? "" : "s") Ago" }
        return "Last Synced \(m / 1440) Days Ago"
    }

    /// The Settings row's value: "Connected as @user", "Sign In Again" or "Not Connected" (TraktPage.status).
    public var statusText: String {
        if signedIn { return user.map { "Connected as @\($0.username)" } ?? "Connected" }
        if expired { return "Sign In Again" }
        return "Not Connected"
    }

    /// The Settings row's caption (TraktPage.settingsItems).
    public var captionText: String {
        if signedIn { return lastSyncedText }
        if expired { return "Your Trakt Sign-In Has Ended" }
        return "Sync Watched History, Progress and My List"
    }

    // MARK: Followed shows (New Episodes)

    /// TMDB ids of the shows this profile follows on Trakt: watchlist shows first, then shows paused part way, then
    /// shows with episodes watched in the last 180 days (newest first). From the last sync, no network.
    public func followedShowTmdbIds() -> [Int] {
        guard signedIn else { return [] }
        let st = vault.state(pid)
        var out: [Int] = []
        var seen = Set<Int>()
        func add(_ id: Int?) { if let id, id > 0, seen.insert(id).inserted { out.append(id) } }
        for k in st.wl ?? [] where k.hasPrefix("tv:") { add(Int(k.dropFirst(3))) }
        for k in st.pb.keys.sorted() where k.hasPrefix("tv:") { add(Int(k.dropFirst(3))) }
        let cutoff = wallMs() - Self.historyDays * 86_400_000
        for (k, at) in st.shows.sorted(by: { $0.value > $1.value }) where at >= cutoff { add(Int(k)) }
        return out
    }

    // MARK: Tokens

    static func tokens(from o: [String: Any]) -> TraktTokens? {
        let a = TJ.str(o, "access_token"), r = TJ.str(o, "refresh_token")
        guard !a.isEmpty, !r.isEmpty else { return nil }
        let created = TJ.int64(o, "created_at")
        let life = TJ.int64(o, "expires_in")
        let start = (created > 0 ? created : wallMs() / 1000) * 1000
        return TraktTokens(a: a, r: r, e: start + (life > 0 ? life : 86_400) * 1000)
    }

    /// A token good for the next hour, renewed if needed; nil when signed out (or the sign-in ended).
    func validTokens(_ p: String) async -> TraktTokens? {
        guard let t = vault.tokens(p) else { return nil }
        if t.e - wallMs() > Self.refreshAheadMs { return t }
        if let n = await refresh(p, t) { return n }
        return t.e > wallMs() + 60_000 ? t : nil
    }

    func refresh(_ p: String, _ old: TraktTokens) async -> TraktTokens? {
        await refreshGate.enter()
        let r = await refreshLocked(p, old)
        await refreshGate.leave()
        return r
    }

    private func refreshLocked(_ p: String, _ old: TraktTokens) async -> TraktTokens? {
        guard let cur = vault.tokens(p) else { return nil }
        // Renewed meanwhile by another call.
        if cur.a != old.a && cur.e - wallMs() > Self.refreshAheadMs { return cur }
        guard let keys else { return nil }
        let body = TraktHTTP.client(keys, ["refresh_token": cur.r, "redirect_uri": TraktHTTP.redirect, "grant_type": "refresh_token"])
        guard let r = try? await TraktHTTP.send(keys, "POST", "/oauth/token", body) else { return nil }
        switch r.code {
        case 200:
            guard let o = r.object, let t = Self.tokens(from: o) else { return nil }
            // Signed out (or the profile deleted) while the renewal was on its way: it must not sign back in.
            guard vault.tokens(p)?.r == cur.r else { return nil }
            vault.setTokens(p, t)
            plog.info("Trakt: sign-in renewed")
            return t
        case 400, 401:
            plog.notice("Trakt: sign-in ended (renewal refused, \(r.code, privacy: .public)); Sign In Again needed")
            vault.setTokens(p, nil)
            vault.setExpired(p, true)
            changed()
            return nil
        default:
            return nil
        }
    }

    /// A signed-in call (one renewal on 401, one short wait on 429). Nil when signed out. Throws when offline.
    func authed(_ p: String, _ method: String, _ path: String, _ body: Data? = nil) async throws -> TraktReply? {
        guard let keys, var t = await validTokens(p) else { return nil }
        var r = try await TraktHTTP.send(keys, method, path, body, token: t.a)
        if r.code == 401 {
            guard let n = await refresh(p, t) else { return nil }
            t = n
            r = try await TraktHTTP.send(keys, method, path, body, token: t.a)
        }
        if r.code == 429 && (1...10).contains(r.retryAfter) {
            await sleepMs(Int64(r.retryAfter) * 1000)
            r = try await TraktHTTP.send(keys, method, path, body, token: t.a)
        }
        return r
    }

    func getArray(_ p: String, _ path: String) async throws -> [[String: Any]] {
        guard let r = try await authed(p, "GET", path) else { throw TraktFailure.signedOut }
        guard r.ok else { throw TraktFailure.refused(r.code) }
        guard let a = r.array else { throw TraktFailure.unreadable }
        return a
    }

    private func revoke(_ t: TraktTokens, _ keys: TraktKeys) {
        io.run {
            _ = try? await TraktHTTP.send(keys, "POST", "/oauth/revoke", TraktHTTP.client(keys, ["token": t.a]))
        }
    }

    // MARK: Sign-in (device code)

    public struct DeviceCode: Sendable, Hashable {
        public let deviceCode: String
        public let userCode: String
        public let url: URL
        /// ms since 1970.
        public let expiresAt: Int64
        public let interval: Int
        /// The profile the sign-in is for (the one current when the code was asked for).
        public let profileId: String
        /// "trakt.tv/activate"
        public var site: String {
            url.absoluteString.replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "http://", with: "")
                .replacingOccurrences(of: "www.", with: "")
        }
    }

    public enum Poll: Sendable {
        case waiting
        case slowDown
        case done(TraktUser?)
        /// `badKeys`: Trakt refused the app's keys (the page offers to enter the viewer's own).
        case failed(String, expired: Bool, badKeys: Bool)
    }

    /// A code for the current profile, or nil when Trakt can't be reached.
    public func startDevice() async -> DeviceCode? {
        guard let keys else { return nil }
        let p = pid
        do {
            let r = try await TraktHTTP.send(keys, "POST", "/oauth/device/code", TraktHTTP.body(["client_id": keys.id]))
            guard r.ok, let o = r.object, let dc = Self.deviceCode(o, p) else {
                plog.notice("Trakt: device code refused (\(r.code, privacy: .public))")
                return nil
            }
            plog.info("Trakt: sign-in code shown")
            return dc
        } catch {
            plog.notice("Trakt: couldn't get a sign-in code (offline)")
            return nil
        }
    }

    private static func deviceCode(_ o: [String: Any], _ p: String) -> DeviceCode? {
        let dc = TJ.str(o, "device_code"), uc = TJ.str(o, "user_code")
        guard !dc.isEmpty, !uc.isEmpty else { return nil }
        let u = TJ.str(o, "verification_url")
        let life = TJ.int64(o, "expires_in")
        let iv = TJ.int(o, "interval")
        return DeviceCode(deviceCode: dc, userCode: uc, url: URL(string: u.isEmpty ? "https://trakt.tv/activate" : u) ?? URL(string: "https://trakt.tv/activate")!,
                          expiresAt: wallMs() + (life > 0 ? life : 600) * 1000, interval: min(max(iv > 0 ? iv : 5, 1), 30), profileId: p)
    }

    /// One poll; on approval the tokens are saved for the code's profile and the first sync starts.
    public func poll(_ dc: DeviceCode) async -> Poll {
        if wallMs() > dc.expiresAt { return .failed("The code expired. Get a new one to try again.", expired: true, badKeys: false) }
        guard let keys else { return .failed("Couldn't finish signing in. Try again.", expired: false, badKeys: false) }
        let body = TraktHTTP.client(keys, ["code": dc.deviceCode])
        guard let r = try? await TraktHTTP.send(keys, "POST", "/oauth/device/token", body) else { return .waiting }
        switch r.code {
        case 200:
            let p = dc.profileId
            guard let o = r.object, let t = Self.tokens(from: o), vault.setTokens(p, t) else {
                return .failed("Couldn't finish signing in. Try again.", expired: false, badKeys: false)
            }
            vault.store.withLock {
                vault.setState(p, nil)
                vault.setQueue(p, nil)
                vault.setExpired(p, false)
            }
            vault.remember(p)
            let u = await fetchUser(p)
            plog.info("Trakt: signed in\(u == nil ? "" : " as @" + u!.username, privacy: .public)")
            changed()
            sync(p, "sign-in", force: true)
            return .done(u)
        case 400: return .waiting
        case 429: return .slowDown
        case 404: return .failed("That code didn't work. Get a new one to try again.", expired: true, badKeys: false)
        case 409: return .failed("That code was already used. Get a new one to try again.", expired: true, badKeys: false)
        case 410: return .failed("The code expired. Get a new one to try again.", expired: true, badKeys: false)
        case 418: return .failed("Sign-in was declined on Trakt.", expired: false, badKeys: false)
        case 401, 403:
            plog.notice("Trakt: app keys refused at sign-in (\(r.code, privacy: .public))")
            return usesOwnKeys
                ? .failed("Those keys didn't work. Check them and try again.", expired: false, badKeys: true)
                : .failed("Trakt didn't accept this sign-in. Connect with your own free Trakt app instead.", expired: false, badKeys: true)
        default:
            return r.transient ? .waiting : .failed("Couldn't reach Trakt. Try again.", expired: false, badKeys: false)
        }
    }

    @discardableResult
    func fetchUser(_ p: String) async -> TraktUser? {
        guard let r = try? await authed(p, "GET", "/users/settings"), r.ok, let u = TJ.obj(r.object, "user") else { return nil }
        let name = TJ.str(u, "username")
        guard !name.isEmpty else { return nil }
        let avatar = TJ.str(TJ.obj(TJ.obj(u, "images"), "avatar"), "full")
        let full = TJ.str(u, "name")
        let user = TraktUser(username: name, name: full.isEmpty ? nil : full, avatar: avatar.hasPrefix("https://") ? avatar : nil)
        vault.setUser(p, user)
        return user
    }

    /// Signs the current profile out: the sign-in is revoked on Trakt (best effort) and forgotten here, with the sync
    /// state. Everything already in Continue Watching and My List stays.
    public func signOut() {
        let keys = self.keys
        let t = vault.wipe(pid)
        plog.info("Trakt: signed out")
        changed()
        if let t, let keys { revoke(t, keys) }
    }

    // MARK: The viewer's own Trakt app

    public enum KeyCheck: Sendable {
        /// The keys work and are saved; the code is a live sign-in code for the current profile.
        case ok(DeviceCode)
        case failed(String)
    }

    /// Checks the viewer's own Trakt app keys and saves them when they work: a sign-in code with the client id, then
    /// one token poll for the secret (a right one gets "still waiting", a wrong one 401). The code goes straight on
    /// to the sign-in screen. Keys are never logged. Switching apps signs every profile out (their sign-ins belonged
    /// to the old app).
    public func tryOwnKeys(id: String, secret: String) async -> KeyCheck {
        let k = TraktKeys(id: id.filter { !$0.isWhitespace }, secret: secret.filter { !$0.isWhitespace })
        let bad = KeyCheck.failed("Those keys didn't work. Check them and try again.")
        if k.id.isEmpty { return .failed("Enter your app's Client ID.") }
        do {
            let r = try await TraktHTTP.send(k, "POST", "/oauth/device/code", TraktHTTP.body(["client_id": k.id]))
            if r.transient { return .failed("Couldn't reach Trakt. Try again.") }
            guard r.ok, let o = r.object, let dc = Self.deviceCode(o, pid) else {
                plog.notice("Trakt: own app keys refused (\(r.code, privacy: .public))")
                return bad
            }
            let p = try await TraktHTTP.send(k, "POST", "/oauth/device/token", TraktHTTP.client(k, ["code": dc.deviceCode]))
            if p.code == 401 || p.code == 403 {
                plog.notice("Trakt: own app secret refused (\(p.code, privacy: .public))")
                return bad
            }
            let old = keys
            if old != k { signOutEveryone(old) }
            guard vault.setOwnKeys(k) else { return .failed("Couldn't save the keys on this iPhone. Try again.") }
            plog.info("Trakt: own app keys saved; sign-in code shown")
            changed()
            return .ok(dc)
        } catch {
            return .failed("Couldn't reach Trakt. Try again.")
        }
    }

    /// Forgets the viewer's own Trakt app. Every profile's sign-in belonged to it, so all of them are signed out
    /// (revoked on Trakt, best effort) with their sync state. The build's own keys, if any, take over.
    public func removeOwnKeys() {
        signOutEveryone(vault.ownKeys)
        vault.setOwnKeys(nil)
        plog.info("Trakt: own app keys removed; every profile signed out")
        changed()
    }

    private func signOutEveryone(_ keys: TraktKeys?) {
        var ids = Set(vault.profileIds)
        ids.formUnion(Profiles.shared.all().map(\.id))
        for p in ids {
            if let t = vault.wipe(p), let keys { revoke(t, keys) }
        }
    }
}

/// The watch history and My List tell Trakt what changed here (WatchHistory.sync).
struct TraktHistorySync: HistorySync {
    func continueWatchingRemoved(_ key: String) async { Trakt.shared.onContinueWatchingRemoved(key) }
    func myListChanged(_ title: Title, added: Bool) async { Trakt.shared.onMyListChanged(title, added: added) }
}
