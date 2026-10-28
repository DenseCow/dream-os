import Foundation
import DreamCore

// Trakt sync (fire-home Trakt.kt: Sync, My List and Continue Watching hooks, State and queue).
extension Trakt {
    // MARK: When to sync

    /// The app came to the front (the TV's Home opening): a sync if the last one is old or something played since.
    /// Returns at once.
    public func onHomeOpened() {
        guard signedIn, !isSyncing else { return }
        let st = vault.state(pid)
        if !st.dirty && wallMs() - st.at < Self.homeEveryMs { return }
        sync(pid, "home")
    }

    /// Starts a sync for the current profile in the background (Sync Now: `force`).
    public func sync(force: Bool = false) { sync(pid, force ? "sync now" : "asked", force: force) }

    func sync(_ p: String, _ reason: String, force: Bool = false) {
        guard signedIn(p) else { return }
        if force { markDirty(p) }
        syncer.run { await Trakt.shared.syncNow(p, reason) }
    }

    func syncNow(_ p: String, _ reason: String) async {
        guard pid == p, vault.tokens(p) != nil else { return } // profile switched or signed out
        let start: Bool = lock.withLock { if _syncing { return false }; _syncing = true; return true }
        guard start else { return }
        changed()
        let started = wallMs()
        var ok = false
        defer {
            lock.withLock { _lastSyncFailed = !ok; _syncing = false }
            changed()
        }
        do {
            guard await flush(p) else { plog.notice("Trakt: sync (\(reason, privacy: .public)) waits: couldn't reach Trakt"); return }
            guard let r = try await authed(p, "GET", "/sync/last_activities") else { return }
            guard r.ok, let la = r.object else { plog.notice("Trakt: sync (\(reason, privacy: .public)) waits: Trakt answered \(r.code, privacy: .public)"); return }
            var st = vault.state(p)
            let prev: [String: Any]? = st.la.flatMap { $0.data(using: .utf8) }.flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] }
            func at(_ o: [String: Any]?, _ group: String, _ field: String) -> String { TJ.str(TJ.obj(o, group), field) }
            func moved(_ group: String, _ field: String) -> Bool { prev == nil || at(prev, group, field) != at(la, group, field) }

            if vault.user(p) == nil { await fetchUser(p) }
            var watched = 0, progress = 0, listed = 0
            if !Keys.tmdb.isEmpty {
                if moved("episodes", "watched_at") || moved("movies", "watched_at") { watched = try await pullHistory(p, &st, prev) }
                if moved("episodes", "paused_at") || moved("movies", "paused_at") { progress = try await pullPlayback(p, &st) }
                if moved("movies", "watchlisted_at") || moved("shows", "watchlisted_at") || st.wl == nil {
                    listed = try await syncWatchlist(p, &st, la)
                }
            }
            // Saved as one: the activity marks move on only once everything they stand for is in.
            let saved: Bool = vault.store.withLock {
                guard vault.tokens(p) != nil else { return false }
                var cur = vault.state(p)
                cur.shows = st.shows
                cur.pb = st.pb
                if st.wl != nil { cur.wl = st.wl }
                cur.la = String(data: r.body, encoding: .utf8)
                cur.at = wallMs()
                cur.dirty = false
                vault.setState(p, cur)
                return true
            }
            guard saved else { return }
            ok = true
            plog.info("Trakt: sync (\(reason, privacy: .public)) pulled \(watched) watched, \(progress) in progress, \(listed) watchlist in \(wallMs() - started) ms")
        } catch {
            plog.notice("Trakt: sync (\(reason, privacy: .public)) stopped: \(String(describing: type(of: error)), privacy: .public)")
        }
    }

    // MARK: History and progress -> Continue Watching

    /// Recent history (since the last sync) into Continue Watching: each show's latest watched episode (it becomes
    /// Up Next) and movies watched; also the "shows watched lately" for `followedShowTmdbIds`.
    private func pullHistory(_ p: String, _ st: inout TraktState, _ prev: [String: Any]?) async throws -> Int {
        let cutoff = wallMs() - Self.historyDays * 86_400_000
        func since(_ group: String) -> String {
            let s = TJ.str(TJ.obj(prev, group), "watched_at")
            guard !s.isEmpty else { return "" }
            return "&start_at=" + (s.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? s)
        }
        var shows = st.shows
        var asks: [(MediaKind, Int, Int, Int, Int64)] = []

        var latestPerShow: [Int: (Int64, Int, Int)] = [:]
        // Everything Trakt lists as watched counts for the Watched pill, not only what reaches Continue Watching.
        var watched: [WatchedSeed] = []
        for o in try await getArray(p, "/sync/history/episodes?limit=100" + since("episodes")) {
            let sid = TJ.tmdb(o, "show")
            let at = TraktTime.ms(TJ.str(o, "watched_at"))
            guard sid > 0, at > 0 else { continue }
            if at > (shows["\(sid)"] ?? 0) { shows["\(sid)"] = at }
            let ep = TJ.obj(o, "episode")
            if TJ.int(ep, "number") > 0 { watched.append(.episode(show: sid, season: TJ.int(ep, "season"), episode: TJ.int(ep, "number"))) }
            if (latestPerShow[sid]?.0 ?? 0) < at { latestPerShow[sid] = (at, TJ.int(ep, "season"), TJ.int(ep, "number")) }
        }
        for (sid, v) in latestPerShow.sorted(by: { $0.value.0 > $1.value.0 }).prefix(15) where v.0 >= cutoff {
            asks.append((.tv, sid, v.1, v.2, v.0))
        }

        var seen = Set<Int>()
        for o in try await getArray(p, "/sync/history/movies?limit=30" + since("movies")) {
            let id = TJ.tmdb(o, "movie")
            let at = TraktTime.ms(TJ.str(o, "watched_at"))
            if id > 0 { watched.append(.movie(id)) }
            if id <= 0 || at < cutoff || !seen.insert(id).inserted || seen.count > 8 { continue }
            asks.append((.movie, id, 0, 0, at))
        }
        if pid == p { WatchedLedger.shared.mark(watched) }

        // Keep the 80 most recent shows.
        if shows.count > 80 {
            for (k, _) in shows.sorted(by: { $0.value < $1.value }).prefix(shows.count - 80) { shows[k] = nil }
        }
        st.shows = shows
        let entries = await entries(asks.map { ($0.0, $0.1, $0.2, $0.3, 1.0, $0.4) })
        return await merge(p, entries)
    }

    /// Trakt's paused-at points (other devices) into Continue Watching; the ids are kept so a removal here clears them there.
    private func pullPlayback(_ p: String, _ st: inout TraktState) async throws -> Int {
        var newest: [String: (Int64, [String: Any], Double)] = [:]
        for o in try await getArray(p, "/sync/playback") {
            let pct = TJ.double(o, "progress") / 100
            let at = TraktTime.ms(TJ.str(o, "paused_at"))
            if pct < 0.02 || at <= 0 { continue }
            let key: String
            switch TJ.str(o, "type") {
            case "movie": let id = TJ.tmdb(o, "movie"); guard id > 0 else { continue }; key = "movie:\(id)"
            case "episode": let id = TJ.tmdb(o, "show"); guard id > 0 else { continue }; key = "tv:\(id)"
            default: continue
            }
            if (newest[key]?.0 ?? 0) < at { newest[key] = (at, o, pct) }
        }
        var pb: [String: Int64] = [:]
        var asks: [(MediaKind, Int, Int, Int, Double, Int64)] = []
        for (key, t) in newest.sorted(by: { $0.value.0 > $1.value.0 }).prefix(20) {
            pb[key] = TJ.int64(t.1, "id")
            guard let id = Int(key.split(separator: ":").last ?? "") else { continue }
            if key.hasPrefix("movie:") { asks.append((.movie, id, 0, 0, t.2, t.0)) }
            else if let ep = TJ.obj(t.1, "episode") { asks.append((.tv, id, TJ.int(ep, "season"), TJ.int(ep, "number"), t.2, t.0)) }
        }
        st.pb = pb
        return await merge(p, await entries(asks))
    }

    private func merge(_ p: String, _ entries: [WatchHistory.Entry]) async -> Int {
        guard !entries.isEmpty, pid == p else { return 0 }
        return await WatchHistory.shared.mergeRemote(entries)
    }

    /// Continue Watching entries for Trakt items, filled from TMDB (cached), all at once.
    private func entries(_ asks: [(MediaKind, Int, Int, Int, Double, Int64)]) async -> [WatchHistory.Entry] {
        await withTaskGroup(of: WatchHistory.Entry?.self) { g in
            for a in asks { g.addTask { await Trakt.entryFor(a.0, a.1, a.2, a.3, a.4, a.5) } }
            var out: [WatchHistory.Entry] = []
            for await e in g { if let e { out.append(e) } }
            return out
        }
    }

    private static func entryFor(_ kind: MediaKind, _ tmdbId: Int, _ season: Int, _ episode: Int, _ progress: Double, _ at: Int64) async -> WatchHistory.Entry? {
        let isShow = kind == .tv
        if isShow && season <= 0 && episode <= 0 { return nil }
        guard let d = try? await Services.shared.catalog.details(kind: kind, id: tmdbId), !d.title.name.isEmpty else { return nil }
        var epTitle: String?
        var still: String?
        var duration = isShow ? 0 : Int64(d.runtime) * 60_000
        if isShow {
            if let ep = (try? await Services.shared.catalog.episodes(showId: tmdbId, season: season))?.first(where: { $0.number == episode }) {
                epTitle = ep.name.isEmpty ? nil : ep.name
                still = ep.stillPath
                duration = Int64(ep.runtime) * 60_000
            }
            if duration <= 0 { duration = Int64(d.runtime) * 60_000 }
        }
        let pr = min(max(progress, 0), 1)
        return WatchHistory.Entry(title: d.title, episodeTitle: epTitle, season: season, episode: episode,
                                  imdb: d.imdbId?.hasPrefix("tt") == true ? d.imdbId : nil, progress: pr,
                                  positionMs: duration > 0 ? Int64(Double(duration) * pr) : 0, durationMs: duration,
                                  updatedAt: at, source: WatchHistory.dream, stillPath: still)
    }

    // MARK: Watchlist <-> My List

    /// Trakt watchlist <-> My List. Last write wins:
    ///  - On Trakt, not here: added here, unless it was removed here after Trakt listed it (then removed on Trakt).
    ///  - Here, not on Trakt, and it was on Trakt at the last sync (removed there): removed here, unless it was added
    ///    here after Trakt's last watchlist change (then added back on Trakt).
    ///  - Here, never seen on Trakt (added before signing in, or the first sync): added on Trakt.
    /// Removals on Trakt carry no time of their own; Trakt's last watchlist activity stands in for it.
    private func syncWatchlist(_ p: String, _ st: inout TraktState, _ la: [String: Any]) async throws -> Int {
        var remote: [String: Int64] = [:]
        var order: [String] = []
        for (path, type, field) in [("movies", "movie", "movie"), ("shows", "tv", "show")] {
            for o in try await getArray(p, "/sync/watchlist/\(path)?page=1&limit=500") {
                let id = TJ.tmdb(o, field)
                guard id > 0 else { continue }
                let k = "\(type):\(id)"
                if remote[k] == nil { order.append(k) }
                remote[k] = TraktTime.ms(TJ.str(o, "listed_at"))
            }
        }
        let snap: Set<String>? = st.wl.map(Set.init)
        let ledger = st.ledger
        guard pid == p else { return 0 }
        let local = MyList.shared.items().map(\.key)
        let removedAt = max(TraktTime.ms(TJ.str(TJ.obj(la, "movies"), "watchlisted_at")), TraktTime.ms(TJ.str(TJ.obj(la, "shows"), "watchlisted_at")))
        var addHere: [String] = [], removeHere: [String] = [], pushAdd: [String] = [], pushRemove: [String] = []
        let localSet = Set(local)
        for k in order where !localSet.contains(k) {
            if let l = ledger[k], !l.inList, l.at > (remote[k] ?? 0) { pushRemove.append(k) } else { addHere.append(k) }
        }
        for k in local where remote[k] == nil {
            let l = ledger[k]
            if let snap, snap.contains(k) {
                if let l, l.inList, l.at > removedAt { pushAdd.append(k) } else { removeHere.append(k) }
            } else { pushAdd.append(k) }
        }
        // Newest-listed last, so it ends up first (My List puts each addition at the top).
        let wanted = Array(addHere.sorted { (remote[$0] ?? 0) < (remote[$1] ?? 0) }.suffix(60))
        let found: [String: Title] = await withTaskGroup(of: (String, Title?).self) { g in
            for k in wanted { g.addTask { (k, await Trakt.titleFor(k)) } }
            var out: [String: Title] = [:]
            for await (k, t) in g { if let t { out[k] = t } }
            return out
        }
        let titles = wanted.compactMap { found[$0] }
        if (!titles.isEmpty || !removeHere.isEmpty) && pid == p {
            for t in titles where !MyList.shared.contains(t.key) { applyRemote(t) }
            for k in removeHere { if let t = MyList.shared.items().first(where: { $0.key == k }) { applyRemote(t) } }
        }
        var onTrakt = Set(remote.keys)
        if !pushAdd.isEmpty, let r = try await authed(p, "POST", "/sync/watchlist", Self.watchlistBody(pushAdd)) {
            if r.ok { onTrakt.formUnion(pushAdd) }
            else if r.code == 420 { plog.notice("Trakt: watchlist is at its limit, \(pushAdd.count) title(s) stay only in My List") }
        }
        if !pushRemove.isEmpty, let r = try await authed(p, "POST", "/sync/watchlist/remove", Self.watchlistBody(pushRemove)), r.ok {
            onTrakt.subtract(pushRemove)
        }
        st.wl = Array(onTrakt).sorted()
        if !pushAdd.isEmpty || !pushRemove.isEmpty {
            plog.info("Trakt: watchlist pushed \(pushAdd.count) added, \(pushRemove.count) removed")
        }
        return titles.count + removeHere.count
    }

    /// Toggles [t] in My List for a sync (its change isn't sent back to Trakt).
    private func applyRemote(_ t: Title) {
        lock.withLock { _ = applying.insert(t.key) }
        MyList.shared.toggle(t)
    }

    private static func titleFor(_ key: String) async -> Title? {
        let parts = key.split(separator: ":")
        guard parts.count == 2, let id = Int(parts[1]) else { return nil }
        let kind: MediaKind = parts[0] == "movie" ? .movie : .tv
        guard let d = try? await Services.shared.catalog.details(kind: kind, id: id), !d.title.name.isEmpty else { return nil }
        return d.title
    }

    static func watchlistBody(_ keys: [String]) -> Data {
        var movies: [[String: Any]] = [], shows: [[String: Any]] = []
        for k in keys {
            let parts = k.split(separator: ":")
            guard parts.count == 2, let id = Int(parts[1]) else { continue }
            if parts[0] == "movie" { movies.append(["ids": ["tmdb": id]]) } else if parts[0] == "tv" { shows.append(["ids": ["tmdb": id]]) }
        }
        return TraktHTTP.body(["movies": movies, "shows": shows])
    }

    // MARK: My List and Continue Watching hooks

    /// A title was added to or taken off My List here: the Trakt watchlist follows.
    func onMyListChanged(_ t: Title, added: Bool) {
        let mine: Bool = lock.withLock { applying.remove(t.key) != nil }
        if mine || !signedIn || t.id <= 0 { return }
        let p = pid
        let now = wallMs()
        io.run {
            let me = Trakt.shared
            me.editState(p) { st in
                st.ledger[t.key] = TraktState.Mark(inList: added, at: now)
                if st.ledger.count > 300 {
                    for (k, _) in st.ledger.sorted(by: { $0.value.at < $1.value.at }).prefix(st.ledger.count - 300) { st.ledger[k] = nil }
                }
            }
            me.queue(p, TraktOp(op: added ? "wl_add" : "wl_remove", key: t.key, at: now))
            _ = await me.flush(p)
        }
    }

    /// The title left Continue Watching here: its Trakt playback progress goes too.
    func onContinueWatchingRemoved(_ key: String) {
        guard signedIn else { return }
        let p = pid
        io.run {
            let me = Trakt.shared
            var id: Int64 = 0
            me.editState(p) { st in id = st.pb[key] ?? 0; st.pb[key] = nil }
            if id > 0 {
                me.queue(p, TraktOp(op: "pb_remove", pbId: id))
                _ = await me.flush(p)
            }
        }
    }

    // MARK: State and queue

    func editState(_ p: String, _ edit: (inout TraktState) -> Void) {
        vault.store.withLock {
            guard vault.tokens(p) != nil else { return } // signed out meanwhile: nothing to keep
            var st = vault.state(p)
            edit(&st)
            vault.setState(p, st)
        }
    }

    func markDirty(_ p: String) { editState(p) { $0.dirty = true } }

    private static let opCounter = NSLock()
    nonisolated(unsafe) private static var lastOpId: Int64 = 0

    private static func nextOpId() -> Int64 {
        opCounter.withLock {
            lastOpId = max(lastOpId + 1, Int64(Date().timeIntervalSince1970 * 1_000_000))
            return lastOpId
        }
    }

    /// Adds [op] (newest wins for the same title and kind), at most 200 kept.
    func queue(_ p: String, _ op: TraktOp) {
        vault.store.withLock {
            guard vault.tokens(p) != nil else { return }
            var o = op
            o.id = Self.nextOpId()
            var q = vault.queue(p).filter { $0.sameKey != o.sameKey }
            q.append(o)
            if q.count > 200 { q.removeFirst(q.count - 200) }
            vault.setQueue(p, q)
        }
    }

    private func dropFromQueue(_ p: String, _ ids: Set<Int64>) {
        guard !ids.isEmpty else { return }
        vault.store.withLock { vault.setQueue(p, vault.queue(p).filter { !ids.contains($0.id) }) }
    }

    /// Sends what's queued, oldest first; stops at the first network failure (the rest waits for the next try). Ops
    /// Trakt refuses for good (unknown title, full watchlist) are dropped. One flush at a time: nothing is sent twice.
    func flush(_ p: String) async -> Bool {
        await flushGate.enter()
        let r = await flushLocked(p)
        await flushGate.leave()
        return r
    }

    private func flushLocked(_ p: String) async -> Bool {
        let q = vault.queue(p)
        if q.isEmpty { return true }
        var done = Set<Int64>()
        var allSent = true
        do {
            for o in q {
                guard let r = try await sendOp(p, o), !r.transient else { allSent = false; break }
                done.insert(o.id)
                if !r.ok && r.code != 409 { plog.notice("Trakt: queued \(o.op, privacy: .public) not accepted (\(r.code, privacy: .public)), dropped") }
                if r.ok { afterOp(p, o) }
            }
        } catch {
            allSent = false
        }
        dropFromQueue(p, done)
        if !done.isEmpty { plog.info("Trakt: sent \(done.count) queued change\(done.count == 1 ? "" : "s", privacy: .public)") }
        return allSent
    }

    private func sendOp(_ p: String, _ o: TraktOp) async throws -> TraktReply? {
        let done = TraktReply(code: 204, body: Data(), retryAfter: 0)
        switch o.op {
        case "history":
            guard let item = o.item else { return done }
            let at = TraktTime.iso(o.at ?? wallMs())
            let body: [String: Any] = item.movie
                ? ["movies": [["ids": ["tmdb": item.tmdb], "watched_at": at]]]
                : ["shows": [["ids": ["tmdb": item.tmdb],
                              "seasons": [["number": item.season, "episodes": [["number": item.number, "watched_at": at]]]]]]]
            return try await authed(p, "POST", "/sync/history", TraktHTTP.body(body))
        case "progress":
            guard let item = o.item else { return done }
            var body = item.json
            body["progress"] = o.progress ?? 0
            return try await authed(p, "POST", "/scrobble/pause", TraktHTTP.body(body))
        case "wl_add", "wl_remove":
            guard let k = o.key else { return done }
            return try await authed(p, "POST", o.op == "wl_add" ? "/sync/watchlist" : "/sync/watchlist/remove", Self.watchlistBody([k]))
        case "pb_remove":
            guard let id = o.pbId else { return done }
            let r = try await authed(p, "DELETE", "/sync/playback/\(id)")
            if let r, r.code == 404 { return done } // already gone
            return r
        default:
            return done
        }
    }

    /// A pushed watchlist change: the snapshot of what's on Trakt follows.
    private func afterOp(_ p: String, _ o: TraktOp) {
        guard o.op == "wl_add" || o.op == "wl_remove", let k = o.key else { return }
        editState(p) { st in
            guard var set = st.wl.map(Set.init) else { return }
            if o.op == "wl_add" { set.insert(k) } else { set.remove(k) }
            st.wl = Array(set).sorted()
        }
    }
}
