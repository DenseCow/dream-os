import Foundation
import DreamCore

// Premiumize, TorBox and Real-Debrid behind `DebridService` (DebridOthers.kt, ported 1:1; untested against real
// accounts). Tokens and stream URLs never reach the log.

private func num(_ j: JV) -> Int64 { j.int64 }

/// Sleeps [ms] only when the deadline still leaves room for the request that follows; false when it doesn't.
private func pause(_ ms: Int64, _ deadline: Int64) async -> Bool {
    if deadline - Mono.now() < ms + 1_000 { return false }
    await sleepMs(ms)
    return true
}

private func foundFor(_ url: String, _ service: String, _ c: Candidate, _ path: String, _ size: Int64) -> StreamLink? {
    found(url, service: service, fileName: DebridFiles.base(path), sizeBytes: size > 0 ? size : c.sizeBytes, quality: c.quality, hash: c.hash)
}

/// Premiumize ("pm"): one batched cache check for every candidate, then /transfer/directdl on the first cached one.
/// directdl hands back the file links without creating a transfer, so nothing is added to the account.
struct PremiumizeService: DebridService {
    let token: String
    let key = "pm"
    private static let api = "https://www.premiumize.me/api"
    private static let maxHashes = 60

    func resolve(_ candidates: [Candidate], deadline: Int64) async -> StreamLink? {
        for c in await cached(candidates, deadline) {
            if Mono.now() >= deadline { break }
            if let f = await unlock(c, deadline) { return f }
        }
        return nil
    }

    /// The first [limit] cached candidates unlocked side by side (directdl adds nothing to the account).
    func resolveMany(_ candidates: [Candidate], deadline: Int64, limit: Int) async -> [StreamLink] {
        if limit <= 1 { return [await resolve(candidates, deadline: deadline)].compactMap { $0 } }
        let cached = await cached(candidates, deadline)
        var out = await Self.unlockMany(Array(cached.prefix(limit)), deadline) { await unlock($0, deadline) }
        // None of the raced ones opened: the rest in turn, as before.
        if out.isEmpty {
            for c in cached.dropFirst(limit) {
                if Mono.now() >= deadline { break }
                if let f = await unlock(c, deadline) { out = [f]; break }
            }
        }
        return out
    }

    /// [candidates] Premiumize has cached, in their order, from one batched check; empty when it didn't answer.
    private func cached(_ candidates: [Candidate], _ deadline: Int64) async -> [Candidate] {
        let list = Array(candidates.prefix(Self.maxHashes))
        if list.isEmpty || token.isEmpty { return [] }
        let t0 = Mono.now()
        guard let r = try? await DebridHttp.postForm("\(Self.api)/cache/check", list.map { ("items[]", $0.hash) }, bearer: token,
                                                      timeoutMs: DebridHttp.left(deadline)) else {
            plog.warning("Premiumize: cache check failed (no answer)"); return []
        }
        let o = r.ok ? r.json() : JV(nil)
        guard o["status"].string == "success", let flags = o["response"].array else {
            // Premiumize answers errors with HTTP 200 and status "error" (code "authentication_failed" for a bad token).
            plog.warning("Premiumize: cache check failed (HTTP \(r.code) \(o["code"].string, privacy: .public))"); return []
        }
        let cached = list.indices.filter { $0 < flags.count && flags[$0].bool }.map { list[$0] }
        plog.info("Premiumize: \(cached.count) of \(list.count) cached (\(Mono.now() - t0) ms)")
        return cached
    }

    /// [cands] unlocked side by side, the links back in the candidates' order.
    static func unlockMany(_ cands: [Candidate], _ deadline: Int64, _ unlock: @escaping @Sendable (Candidate) async -> StreamLink?) async -> [StreamLink] {
        if cands.isEmpty { return [] }
        return await withTaskGroup(of: (Int, StreamLink?).self) { g in
            for (i, c) in cands.enumerated() { g.addTask { (i, await unlock(c)) } }
            var got: [(Int, StreamLink)] = []
            for await (i, f) in g { if let f { got.append((i, f)) } }
            return got.sorted { $0.0 < $1.0 }.map(\.1)
        }
    }

    /// The candidate's file through directdl; `link` is the original file (`stream_link` is a transcode).
    private func unlock(_ c: Candidate, _ deadline: Int64) async -> StreamLink? {
        guard let r = try? await DebridHttp.postForm("\(Self.api)/transfer/directdl", [("src", DebridFiles.magnet(c))], bearer: token,
                                                      timeoutMs: DebridHttp.left(deadline)), r.ok else { return nil }
        let o = r.json()
        guard o["status"].string == "success" else {
            plog.warning("Premiumize: directdl refused (\(o["code"].string, privacy: .public))"); return nil
        }
        let items = (o["content"].array ?? []).filter { $0["link"].string.hasPrefix("https") || $0["stream_link"].string.hasPrefix("https") }
        let idx = DebridFiles.pick(items.map { ($0["path"].string, num($0["size"])) }, c)
        guard idx >= 0 else { return nil }
        let f = items[idx]
        let url = f["link"].string.hasPrefix("https") ? f["link"].string : f["stream_link"].string
        return foundFor(url, key, c, f["path"].string, num(f["size"]))
    }
}

/// TorBox ("tb"): one batched cache check (checkcached, list format with file lists, so a pack without the episode is
/// skipped before anything is added), then for the first fit: createtorrent with add_only_if_cached (it can never start
/// a real download), the file ids from mylist, and requestdl for the link. The played torrent is kept (whether a link
/// outlives its torrent is undocumented); ones added and not used are deleted. Every torrent added is recorded in the
/// ledger before it is created; the janitor (first lookup, then hourly) removes Dream OS's kept ones after two hours
/// (never one in use) and keeps at most `cap`. A torrent the viewer already had (same hash) is never deleted.
struct TorBoxService: DebridService {
    let token: String
    let key = "tb"
    var scope: String { DebridLedger.scope("tb", token) }
    private static let api = "https://api.torbox.app/v1/api"
    private static let maxHashes = 100
    private static let cap = 25
    private static let maxAge: Int64 = 2 * 3_600_000

    private final class State: @unchecked Sendable { let lock = NSLock(); var sweptFor = ""; var sweptAt: Int64 = 0 }
    private static let state = State()

    /// At most hourly (the first lookup of the session too), in the background.
    static func maybeJanitor(_ token: String, force: Bool = false) {
        let scope = DebridLedger.scope("tb", token)
        let now = Mono.now()
        let s = state
        let why: String? = s.lock.withLock {
            if !force && s.sweptFor == scope && now - s.sweptAt < 3_600_000 { return nil }
            let first = s.sweptFor != scope
            s.sweptFor = scope; s.sweptAt = now
            return first ? "app start" : "hourly"
        }
        guard let why else { return }
        Task.detached(priority: .utility) { await TorBoxService(token: token).sweep(why) }
    }

    private func sweep(_ why: String) async {
        let ledger = DebridLedger.shared
        guard let r = try? await DebridHttp.get("\(Self.api)/torrents/mylist?bypass_cache=true", bearer: token, timeoutMs: 10_000),
              r.ok, let arr = r.json()["data"].array else {
            plog.warning("TorBox janitor: account not listed"); return
        }
        let items: [(String, Int64)] = arr.compactMap { o in
            let h = o["hash"].string.lowercased()
            return h.isEmpty ? nil : (h, o["id"].exists ? o["id"].int64 : -1)
        }
        let owned = ledger.owned(scope)
        let held = Set(items.map(\.0))
        let user = items.filter { owned[$0.0] == nil }
        ledger.setUsers(scope, Set(user.map(\.0)))
        let wall = wallMs()
        ledger.release(scope, owned.filter { !held.contains($0.key) && wall - $0.value.t > 120_000 }.map(\.key))
        let dream = items.filter { owned[$0.0] != nil }
        let protect = ledger.inUse
        let free = dream.filter { !protect.contains($0.0) && $0.1 >= 0 }.sorted { (owned[$0.0]?.t ?? 0) < (owned[$1.0]?.t ?? 0) }
        var drop = free.filter { wall - (owned[$0.0]?.t ?? 0) > Self.maxAge }
        let over = dream.count - drop.count - Self.cap
        if over > 0 { drop += free.filter { f in !drop.contains(where: { $0.0 == f.0 }) }.prefix(over) }
        var n = 0
        for (h, id) in drop { if await delete(id) { ledger.release(scope, [h]); n += 1 } }
        if n > 0 || why != "hourly" { plog.warning("TorBox janitor (\(why, privacy: .public)): removed \(n) Dream OS torrent\(n == 1 ? "" : "s", privacy: .public) (viewer's \(user.count) untouched)") }
    }

    func resolve(_ candidates: [Candidate], deadline: Int64) async -> StreamLink? {
        for c in await fits(candidates, deadline) {
            if Mono.now() >= deadline { break }
            if let f = await unlock(c, deadline) { return f }
        }
        return nil
    }

    /// The first [limit] cached candidates that hold the file, added (cached only) and unlocked side by side. Each
    /// alternate is a torrent kept in the account until `discard` (the winner started) or the janitor.
    func resolveMany(_ candidates: [Candidate], deadline: Int64, limit: Int) async -> [StreamLink] {
        if limit <= 1 { return [await resolve(candidates, deadline: deadline)].compactMap { $0 } }
        let fits = await fits(candidates, deadline)
        var out = await PremiumizeService.unlockMany(Array(fits.prefix(limit)), deadline) { await unlock($0, deadline) }
        if out.isEmpty {
            for c in fits.dropFirst(limit) {
                if Mono.now() >= deadline { break }
                if let f = await unlock(c, deadline) { out = [f]; break }
            }
        }
        return out
    }

    /// Alternates no longer needed: their torrents (added by Dream OS, not in use) are deleted.
    func discard(_ hashes: [String]) {
        let ledger = DebridLedger.shared
        let owned = ledger.owned(scope)
        let protect = ledger.inUse
        let drop = hashes.compactMap { h -> (String, Int64)? in
            guard !protect.contains(h), let o = owned[h], let i = o.i, let id = Int64(i) else { return nil }
            return (h, id)
        }
        if drop.isEmpty { return }
        let token = token, scope = scope
        Task.detached(priority: .utility) {
            var n = 0
            for (h, id) in drop { if await TorBoxService(token: token).delete(id) { DebridLedger.shared.release(scope, [h]); n += 1 } }
            plog.info("TorBox: dropped \(n) alternate\(n == 1 ? "" : "s", privacy: .public)")
        }
    }

    /// [candidates] TorBox has cached and that hold the wanted file (a pack without the episode is skipped before
    /// anything is added), in their order, from one batched check; empty when it didn't answer.
    private func fits(_ candidates: [Candidate], _ deadline: Int64) async -> [Candidate] {
        let list = Array(candidates.prefix(Self.maxHashes))
        if list.isEmpty || token.isEmpty { return [] }
        Self.maybeJanitor(token)
        let t0 = Mono.now()
        let body = try? JSONSerialization.data(withJSONObject: ["hashes": list.map(\.hash)])
        guard let r = try? await DebridHttp.request("\(Self.api)/torrents/checkcached?format=list&list_files=true", method: "POST", bearer: token,
                                                     body: body, contentType: "application/json", timeoutMs: DebridHttp.left(deadline)) else { return [] }
        guard r.ok else {
            // 401/403 with error BAD_TOKEN for a stale token.
            plog.warning("TorBox: cache check failed (HTTP \(r.code) \(r.json()["error"].string, privacy: .public))"); return []
        }
        let cached = cachedFiles(r.json()["data"])
        let fits = list.filter { c in
            guard let files = cached[c.hash.lowercased()] else { return false }
            return files.isEmpty || DebridFiles.pick(files, c) >= 0
        }
        plog.info("TorBox: \(fits.count) of \(list.count) cached (\(Mono.now() - t0) ms)")
        return fits
    }

    /// hash -> its files (name, size) from a checkcached reply, in list or object format; uncached hashes are absent.
    private func cachedFiles(_ data: JV) -> [String: [(String, Int64)]] {
        var out: [String: [(String, Int64)]] = [:]
        func add(_ fallback: String?, _ t: JV) {
            var h = t["hash"].string
            if h.isEmpty { h = fallback ?? "" }
            h = h.lowercased()
            if h.isEmpty { return }
            out[h] = (t["files"].array ?? []).map { f in (f["name"].string.isEmpty ? f["short_name"].string : f["name"].string, num(f["size"])) }
        }
        if let a = data.array { for t in a { add(nil, t) } }
        else if let o = data.object { for (k, t) in o { add(k, t) } }
        return out
    }

    private func unlock(_ c: Candidate, _ deadline: Int64) async -> StreamLink? {
        let ledger = DebridLedger.shared
        let h = c.hash.lowercased()
        // Recorded before it is added; a hash the viewer already has is theirs and is never deleted from here.
        let mine = ledger.claim(scope, [h]).contains(h)
        let (body, type) = multipart([("magnet", DebridFiles.magnet(c)), ("seed", "3"), ("allow_zip", "false"), ("add_only_if_cached", "true")])
        let add = try? await DebridHttp.request("\(Self.api)/torrents/createtorrent", method: "POST", bearer: token, body: body, contentType: type,
                                                timeoutMs: DebridHttp.left(deadline))
        let idJ = add.map { $0.ok ? $0.json()["data"]["torrent_id"] : JV(nil) } ?? JV(nil)
        let id = idJ.exists ? idJ.int64 : -1
        if id < 0 {
            plog.warning("TorBox: add refused (HTTP \(add?.code ?? 0))")
            if mine && ledger.owned(scope)[h]?.i == nil { ledger.release(scope, [h]) }
            return nil
        }
        if mine { ledger.setId(scope, h, String(id)) }
        var keep = !mine
        defer {
            if !keep && !ledger.inUse.contains(h) {
                let token = token, scope = scope
                Task.detached(priority: .utility) { if await TorBoxService(token: token).delete(id) { DebridLedger.shared.release(scope, [h]) } }
            }
        }
        var files = await self.files(id, deadline)
        // A just-added torrent can list its files a moment later.
        if files.isEmpty, await pause(400, deadline) { files = await self.files(id, deadline) }
        let idx = DebridFiles.pick(files.map { ($0.1, $0.2) }, c)
        guard idx >= 0, Mono.now() < deadline else { return nil }
        let (fid, path, size) = files[idx]
        guard let r = try? await DebridHttp.get("\(Self.api)/torrents/requestdl?token=\(DebridHttp.enc(token))&torrent_id=\(id)&file_id=\(fid)",
                                                 bearer: token, timeoutMs: DebridHttp.left(deadline)) else { return nil }
        let url = r.ok ? r.json()["data"].string : ""
        guard url.hasPrefix("http") else { plog.warning("TorBox: no link (HTTP \(r.code))"); return nil }
        keep = true
        if mine { ledger.markPlayed(scope, h) }
        return foundFor(url, key, c, path, size)
    }

    /// (file id, name, size) of torrent [id]; bypass_cache, as mylist is otherwise cached for a while server side.
    private func files(_ id: Int64, _ deadline: Int64) async -> [(Int64, String, Int64)] {
        guard let r = try? await DebridHttp.get("\(Self.api)/torrents/mylist?id=\(id)&bypass_cache=true", bearer: token,
                                                 timeoutMs: DebridHttp.left(deadline)), r.ok else { return [] }
        let d = r.json()["data"]
        let t = d.isObject ? d : d[0]
        return (t["files"].array ?? []).map { ($0["id"].int64, $0["name"].string.isEmpty ? $0["short_name"].string : $0["name"].string, num($0["size"])) }
    }

    fileprivate func delete(_ id: Int64) async -> Bool {
        let body = try? JSONSerialization.data(withJSONObject: ["torrent_id": id, "operation": "delete"] as [String: Any])
        let r = try? await DebridHttp.request("\(Self.api)/torrents/controltorrent", method: "POST", bearer: token, body: body,
                                              contentType: "application/json", timeoutMs: 8_000)
        if r?.ok != true { plog.warning("TorBox: cleanup of an unused torrent failed (HTTP \(r?.code ?? 0))") }
        return r?.ok == true
    }

    /// A hand-built multipart/form-data body (TorBox's createtorrent takes nothing else): body and its Content-Type.
    private func multipart(_ fields: [(String, String)]) -> (Data, String) {
        let b = "DreamOS" + String(UInt64(Date().timeIntervalSince1970 * 1_000_000), radix: 16)
        var s = ""
        for (k, v) in fields { s += "--\(b)\r\nContent-Disposition: form-data; name=\"\(k)\"\r\n\r\n\(v)\r\n" }
        s += "--\(b)--\r\n"
        return (Data(s.utf8), "multipart/form-data; boundary=\(b)")
    }
}

/// Real-Debrid's tokens: the access token lasts about an hour; on a 401 it is refreshed once with the viewer's own
/// device credentials and saved (DebridAccounts). Serialized, so parallel probes refresh once.
actor RDTokens {
    static let shared = RDTokens()
    /// The stored token [refreshedTo] replaces; a different stored token (re-linked) makes it stale.
    private(set) var refreshedFrom: String?
    private(set) var refreshedTo: String?
    private var refreshCode: String?
    private var failedAt: Int64 = 0
    /// The refresh under way: the actor is reentrant across its request, so parallel probes join it instead of each
    /// sending their own.
    private var running: Task<String?, Never>?

    func current(_ stored: String) -> String { refreshedFrom == stored ? (refreshedTo ?? stored) : stored }

    /// Swaps in a fresh access token unless another probe already did; nil when that isn't possible.
    func refresh(_ acc: DebridAccounts.Accounts, failed: String, timeoutMs: Int) async -> String? {
        let stored = acc.realDebrid ?? ""
        if current(stored) != failed { return current(stored) }
        if let running { return await running.value }
        let t = Task { await renew(acc, stored: stored, timeoutMs: timeoutMs) }
        running = t
        let fresh = await t.value
        running = nil
        return fresh
    }

    private func renew(_ acc: DebridAccounts.Accounts, stored: String, timeoutMs: Int) async -> String? {
        if failedAt != 0 && Mono.now() - failedAt < 60_000 { return nil }
        let code = (refreshedFrom == stored ? refreshCode : nil) ?? acc.realDebridRefresh
        if acc.realDebridClientId.isEmpty || acc.realDebridSecret.isEmpty || code.isEmpty { return nil }
        let r = try? await DebridHttp.postForm("https://api.real-debrid.com/oauth/v2/token", [
            ("client_id", acc.realDebridClientId), ("client_secret", acc.realDebridSecret),
            ("code", code), ("grant_type", "http://oauth.net/grant_type/device/1.0"),
        ], timeoutMs: timeoutMs)
        let o = (r?.ok == true) ? r!.json() : JV(nil)
        let access = o["access_token"].string
        if access.isEmpty {
            failedAt = Mono.now()
            plog.warning("Real-Debrid: token refresh failed (HTTP \(r?.code ?? 0))")
            return nil
        }
        let next = o["refresh_token"].string
        refreshCode = next.isEmpty ? code : next
        refreshedTo = access
        refreshedFrom = stored
        failedAt = 0
        DebridAccounts.refreshedRealDebrid(access: access, refresh: refreshCode)
        plog.info("Real-Debrid: token refreshed (saved in Dream OS)")
        return access
    }
}

/// Real-Debrid ("rd"). Its cache check (instantAvailability) was switched off in late 2024, so the only test is to add
/// the magnet, select the one file wanted and see whether it is "downloaded" at once: cached torrents are, anything
/// else is a real download and is deleted straight away. Candidates go in parallel rounds of `batch`; the best-ranked
/// cached one of a round is unrestricted, every torrent added is deleted afterwards (the unrestricted link lives on in
/// the account's downloads). Each torrent id is recorded in the ledger the moment it is added and dropped once
/// deleted; the janitor deletes any left over. Real-Debrid gives every add its own id, so the viewer's own torrents are
/// never touched.
struct RealDebridService: DebridService {
    let accounts: DebridAccounts.Accounts
    let key = "rd"
    private static let rest = "https://api.real-debrid.com/rest/1.0/"
    private static let batch = 3
    private static let maxTries = 9 // three rounds at most: each try is a torrent added to the account for a moment
    /// Statuses a cached torrent can show for a moment right after the file is selected.
    private static let waiting: Set<String> = ["queued", "waiting_files_selection", "magnet_conversion"]

    private var stored: String { accounts.realDebrid ?? "" }
    private var scope: String { DebridLedger.scope("rd", accounts.ledgerId("rd") ?? "") }
    /// The scope test torrents were kept under before it moved to the client id: swept with the current one.
    private var tokenScope: String { DebridLedger.scope("rd", stored) }

    private final class State: @unchecked Sendable { let lock = NSLock(); var sweptFor = ""; var sweptAt: Int64 = 0 }
    private static let state = State()

    static func refreshToken(_ acc: DebridAccounts.Accounts, timeoutMs: Int) async -> String? {
        let stored = acc.realDebrid ?? ""
        let failed = await RDTokens.shared.current(stored)
        return await RDTokens.shared.refresh(acc, failed: failed, timeoutMs: timeoutMs)
    }

    /// At most hourly (and on the first lookup): test torrents Dream OS added and never got to delete (older than
    /// 10 min) are deleted now. In the background.
    func maybeSweep(force: Bool = false) {
        if stored.isEmpty { return }
        let s = Self.state, scope = scope, old = tokenScope, now = Mono.now()
        let go = s.lock.withLock { () -> Bool in
            if !force && s.sweptFor == scope && now - s.sweptAt < 3_600_000 { return false }
            s.sweptFor = scope; s.sweptAt = now
            return true
        }
        guard go else { return }
        let me = self
        Task.detached(priority: .utility) {
            let ledger = DebridLedger.shared
            let scopes = old == scope ? [scope] : [scope, old]
            let left = scopes.flatMap { sc in ledger.owned(sc).filter { wallMs() - $0.value.t > 10 * 60_000 }.map { (sc, $0.key, $0.value) } }
            var n = 0
            for (sc, k, o) in left {
                let id = o.i ?? (k.hasPrefix("id:") ? String(k.dropFirst(3)) : k)
                guard let r = try? await DebridHttp.request("\(Self.rest)torrents/delete/\(id)", method: "DELETE", bearer: await me.token(), timeoutMs: 8_000) else { continue }
                // 404: already gone.
                if r.ok || r.code == 404 { ledger.release(sc, [k]); if r.ok { n += 1 } }
            }
            if !left.isEmpty { plog.warning("Real-Debrid janitor: removed \(n) leftover test torrent\(n == 1 ? "" : "s", privacy: .public)") }
        }
    }

    private func token() async -> String { await RDTokens.shared.current(stored) }

    /// A cached torrent found by a probe: its torrent id and the hoster link of the picked file.
    private struct Probe: Sendable { let rank: Int; let c: Candidate; let id: String; let path: String; let bytes: Int64; let link: String }

    func resolve(_ candidates: [Candidate], deadline: Int64) async -> StreamLink? {
        await resolveMany(candidates, deadline: deadline, limit: 1).first
    }

    /// Rounds of probes as before; within the round that has cached ones, up to [limit] are unrestricted side by
    /// side (the unrestricted links live on after the probe torrents are deleted, so alternates cost nothing).
    func resolveMany(_ candidates: [Candidate], deadline: Int64, limit: Int) async -> [StreamLink] {
        let list = Array(candidates.prefix(Self.maxTries))
        if list.isEmpty || stored.isEmpty { return [] }
        maybeSweep()
        let t0 = Mono.now()
        var start = 0
        while start < list.count {
            if Mono.now() >= deadline { break }
            let round = Array(list[start..<min(start + Self.batch, list.count)])
            let base = start
            start += Self.batch
            let cached: [Probe] = await withTaskGroup(of: Probe?.self) { g in
                for (i, c) in round.enumerated() { g.addTask { await probe(c, rank: base + i, deadline: deadline) } }
                var out: [Probe] = []
                for await p in g { if let p { out.append(p) } }
                return out.sorted { $0.rank < $1.rank }
            }
            var result: [StreamLink] = []
            if limit > 1 && cached.count > 1 {
                result = await withTaskGroup(of: (Int, StreamLink?).self) { g in
                    for p in cached.prefix(limit) { g.addTask { (p.rank, await unrestrict(p, deadline)) } }
                    var got: [(Int, StreamLink)] = []
                    for await (r, f) in g { if let f { got.append((r, f)) } }
                    return got.sorted { $0.0 < $1.0 }.map(\.1)
                }
                if !result.isEmpty { plog.info("Real-Debrid: \(result.count) link\(result.count == 1 ? "" : "s", privacy: .public) in \(Mono.now() - t0) ms") }
            } else {
                for p in cached {
                    if Mono.now() >= deadline { break }
                    if let f = await unrestrict(p, deadline) { result = [f]; plog.info("Real-Debrid: link for #\(p.rank) in \(Mono.now() - t0) ms"); break }
                }
            }
            // The round's torrents are in the account either way.
            if !cached.isEmpty {
                let me = self, ids = cached.map(\.id)
                Task.detached(priority: .utility) { for id in ids { await me.delete(id) } }
            }
            if !result.isEmpty { return result }
        }
        plog.info("Real-Debrid: none of \(list.count) cached (\(Mono.now() - t0) ms)")
        return []
    }

    /// Adds [c], selects its file and checks it is complete at once. A torrent not handed back is deleted.
    private func probe(_ c: Candidate, rank: Int, deadline: Int64) async -> Probe? {
        let ledger = DebridLedger.shared
        guard let add = try? await call("POST", "torrents/addMagnet", [("magnet", DebridFiles.magnet(c))], deadline) else { return nil }
        let id = add.ok ? add.json()["id"].string : ""
        if id.isEmpty {
            if !add.ok { plog.warning("Real-Debrid: add refused (HTTP \(add.code) \(add.json()["error"].string, privacy: .public))") }
            return nil
        }
        _ = ledger.claim(scope, ["id:" + id]); ledger.setId(scope, "id:" + id, id)
        var handed = false
        defer {
            if !handed { let me = self; Task.detached(priority: .utility) { await me.delete(id) } }
        }
        guard var info = await torrentInfo(id, deadline) else { return nil }
        // A torrent RD knows has its file list at once; magnet_conversion is a hash it has never seen.
        if info["status"].string == "magnet_conversion", await pause(350, deadline) { guard let i = await torrentInfo(id, deadline) else { return nil }; info = i }
        guard info["status"].string == "waiting_files_selection", let files = info["files"].array else { return nil }
        let idx = DebridFiles.pick(files.map { ($0["path"].string, $0["bytes"].int64) }, c)
        guard idx >= 0, Mono.now() < deadline else { return nil }
        let file = files[idx]
        guard let sel = try? await call("POST", "torrents/selectFiles/\(id)", [("files", String(file["id"].int64))], deadline), sel.ok else { return nil }
        guard var info2 = await torrentInfo(id, deadline) else { return nil }
        if Self.waiting.contains(info2["status"].string), await pause(400, deadline) { guard let i = await torrentInfo(id, deadline) else { return nil }; info2 = i }
        guard info2["status"].string == "downloaded" else { return nil }
        let link = info2["links"][0].string
        guard link.hasPrefix("http") else { return nil }
        handed = true
        return Probe(rank: rank, c: c, id: id, path: file["path"].string, bytes: file["bytes"].int64, link: link)
    }

    private func torrentInfo(_ id: String, _ deadline: Int64) async -> JV? {
        guard let r = try? await call("GET", "torrents/info/\(id)", nil, deadline), r.ok else { return nil }
        return r.json()
    }

    /// The direct HTTPS link for a cached probe; RAR archives and the like don't play, so they don't count.
    private func unrestrict(_ p: Probe, _ deadline: Int64) async -> StreamLink? {
        guard let r = try? await call("POST", "unrestrict/link", [("link", p.link)], deadline) else { return nil }
        guard r.ok else { plog.warning("Real-Debrid: unrestrict refused (HTTP \(r.code))"); return nil }
        let o = r.json()
        let url = o["download"].string
        let name = o["filename"].string.isEmpty ? DebridFiles.base(p.path) : o["filename"].string
        guard url.hasPrefix("https"), DebridFiles.isVideo(name) else { return nil }
        let size = o["filesize"].int64
        return found(url, service: key, fileName: name, sizeBytes: size > 0 ? size : p.bytes, quality: p.c.quality, hash: p.c.hash)
    }

    fileprivate func delete(_ id: String) async {
        guard let r = try? await DebridHttp.request("\(Self.rest)torrents/delete/\(id)", method: "DELETE", bearer: await token(), timeoutMs: 8_000) else { return }
        if r.ok || r.code == 404 { DebridLedger.shared.release(scope, ["id:" + id]) }
        if !r.ok { plog.warning("Real-Debrid: cleanup of a test torrent failed (HTTP \(r.code)); the janitor retries") }
    }

    /// A REST call with the current token; on 401 (bad_token: expired) refreshes once and retries.
    private func call(_ method: String, _ path: String, _ fields: [(String, String)]?, _ deadline: Int64) async throws -> DebridHttp.Reply {
        func send(_ t: String) async throws -> DebridHttp.Reply {
            if let fields {
                return try await DebridHttp.request(Self.rest + path, method: method, bearer: t, body: Data(DebridHttp.form(fields).utf8),
                                                    contentType: "application/x-www-form-urlencoded", timeoutMs: DebridHttp.left(deadline))
            }
            return try await DebridHttp.request(Self.rest + path, method: method, bearer: t, timeoutMs: DebridHttp.left(deadline))
        }
        let used = await token()
        let r = try await send(used)
        if r.code != 401 { return r }
        guard let fresh = await RDTokens.shared.refresh(accounts, failed: used, timeoutMs: DebridHttp.left(deadline)) else { return r }
        return try await send(fresh)
    }
}
