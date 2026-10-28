import Foundation
import DreamCore

/// AllDebrid (api.alldebrid.com v4) for Dream Core (DebridAllDebrid.kt, ported 1:1). AllDebrid has no free cache
/// check: the only test is magnet/upload, which adds the magnet to the viewer's account, and the account takes at most
/// 1000 magnets, counting recently deleted ones (MAGNET_TOO_MANY: hit on 2026-10-25 by speculative lookups uploading
/// ten candidates per title; an incident leaked 342 magnets). So uploads are spent sparingly:
///
///  - Only for a real Play, the next episode and the source list's top rows (`DebridPurpose`), within a rolling 24 h
///    budget (`DebridLedger.allowance`).
///  - Candidates go one at a time (then in twos), stopping at the first ready one; hashes seen ready lately go first
///    within their quality, hashes seen not cached in the last ~6 h are skipped (`DebridLedger.cached`).
///  - Every hash is recorded in `DebridLedger` BEFORE it is uploaded; after each lookup everything it added except the
///    magnet handed to the player is deleted in one batched call (`cleanup`, on every path: found, nothing, errors,
///    deadline). The played one is kept until the `janitor` removes it: on the first lookup of the session, hourly,
///    and at once on MAGNET_TOO_MANY, it deletes Dream OS's magnets older than `maxAge` or beyond `cap` that aren't
///    playing. The viewer's own magnets (everything in the account Dream OS never claimed) are never touched.
///  - On MAGNET_TOO_MANY: janitor, one retry, then AllDebrid is skipped for `limitMs` and the other services asked.
struct AllDebridService: DebridService {
    let token: String
    let purpose: DebridPurpose
    let key = "ad"
    var scope: String { DebridLedger.scope("ad", token) }
    private var ledger: DebridLedger { .shared }

    init(token: String, purpose: DebridPurpose = .play) { self.token = token; self.purpose = purpose }

    struct Mag: Sendable { let id: String; let ready: Bool }
    private struct Entry { let path: String; let bytes: Int64; let link: String }

    static let api = "https://api.alldebrid.com/v4/"
    private static let api41 = "https://api.alldebrid.com/v4.1/"
    static let agent = "DreamOS"
    /// A refused key is tried again after this long (a blocked account may have been unblocked by email).
    private static let retryMs: Int64 = 10 * 60_000
    /// After MAGNET_TOO_MANY survives the janitor, AllDebrid is skipped this long (then tried again).
    static let limitMs: Int64 = 30 * 60_000
    /// Dream OS's magnets older than this (and not playing) are removed by the janitor.
    static let maxAge: Int64 = 2 * 3_600_000
    /// At most this many of Dream OS's magnets stay in the account (oldest removed first).
    static let cap = 25

    private final class State: @unchecked Sendable {
        let lock = NSLock()
        var refusedScope = "", refusedAt: Int64 = 0
        var sweptFor = "", sweptAt: Int64 = 0
        var sweeping = false
    }
    private static let state = State()

    func resolve(_ candidates: [Candidate], deadline: Int64) async -> StreamLink? {
        await resolveMany(candidates, deadline: deadline, limit: 1).first
    }

    /// Instant Play's race on AllDebrid, kept small because every candidate checked is an upload: with [limit] 2 the
    /// first upload sends the top two at once and both ready ones are opened side by side (the second is the
    /// alternate); after that the search goes on one link at a time as before. An alternate's magnet stays in the
    /// account until `discard` (the winner started) or the janitor; nothing else this lookup added survives it.
    func resolveMany(_ candidates: [Candidate], deadline: Int64, limit: Int) async -> [StreamLink] {
        if candidates.isEmpty || refused() || Self.limited(token) { return [] }
        let allow = ledger.allowance(key, purpose)
        if allow <= 0 { return [] }
        await snapshot()
        // Without a listing Dream Core can't tell the viewer's own magnets from its own: nothing is uploaded.
        if !ledger.usersKnown(scope) { plog.warning("AllDebrid: account not listed yet; skipped (the viewer's magnets stay untouched)"); return [] }
        let order = order(candidates)
        if order.isEmpty {
            plog.info("AllDebrid: none of \(candidates.count) cached (all seen uncached in the last hours; nothing uploaded)")
            return []
        }
        let race = max(1, min(limit, Self.raceCap))
        var added: [(String, String)] = [] // hash -> magnet id: what this lookup added, for cleanup
        var kept = Set<String>()
        var sent = 0
        defer { cleanup(added, kept: kept) }
        var i = 0
        var batch = race
        while i < order.count && sent < allow && Mono.now() < deadline {
            let end = min(i + batch, order.count, i + (allow - sent))
            let chunk = Array(order[i..<end])
            i += chunk.count; sent += chunk.count; batch = 2
            guard let mags = await upload(chunk, deadline: deadline, added: &added) else { break }
            let ready: [(Candidate, Mag)] = chunk.compactMap { c in
                guard let m = mags[c.hash], m.ready else { return nil }
                return (c, m)
            }
            if ready.isEmpty { continue }
            if Mono.now() >= deadline { break }
            let out: [StreamLink]
            if race > 1 && ready.count > 1 {
                out = await withTaskGroup(of: (Int, StreamLink?).self) { g in
                    for (n, pair) in ready.prefix(race).enumerated() {
                        let c = pair.0, m = pair.1
                        g.addTask { (n, await open(c, m, deadline: deadline)) }
                    }
                    var got: [(Int, StreamLink)] = []
                    for await (n, f) in g { if let f { got.append((n, f)) } }
                    return got.sorted { $0.0 < $1.0 }.map(\.1)
                }
            } else {
                var one: [StreamLink] = []
                for pair in ready {
                    if Mono.now() >= deadline { break }
                    if let f = await open(pair.0, pair.1, deadline: deadline) { one = [f]; break }
                }
                out = one
            }
            guard let first = out.first else { continue }
            for f in out { kept.insert(f.hash) }
            if added.contains(where: { $0.0 == first.hash }) { ledger.markPlayed(scope, first.hash) }
            plog.info("AllDebrid: \(out.count == 1 ? "link" : "\(out.count) links", privacy: .public) after \(sent) upload\(sent == 1 ? "" : "s", privacy: .public) (\(candidates.count) candidates)")
            return out
        }
        if !Self.limited(token) { plog.info("AllDebrid: nothing cached after \(sent) upload\(sent == 1 ? "" : "s", privacy: .public) (\(candidates.count) candidates)") }
        return []
    }

    /// How many candidates a race may open on AllDebrid (each is an upload).
    static let raceCap = 2

    /// Alternates no longer needed: their magnets (added by Dream OS, not playing) are deleted now rather than by
    /// the janitor two hours on.
    func discard(_ hashes: [String]) {
        let owned = ledger.owned(scope)
        let protect = ledger.inUse
        let drop = hashes.compactMap { h -> (String, String)? in
            guard !protect.contains(h), let o = owned[h], let id = o.i else { return nil }
            return (id, h)
        }
        if drop.isEmpty { return }
        let token = token, scope = scope
        Task.detached(priority: .utility) {
            let gone = await Self.delete(token, drop, deadline: Mono.now() + 10_000)
            DebridLedger.shared.release(scope, gone)
            plog.info("AllDebrid: dropped \(gone.count) alternate\(gone.count == 1 ? "" : "s", privacy: .public)\(gone.count < drop.count ? "; \(drop.count - gone.count) left for the janitor" : "", privacy: .public)")
        }
    }

    /// Which of [candidates]' top rows AllDebrid has cached, for the source list's "Ready" marks: what the ledger knows,
    /// plus one upload of the unknown ones (deleted again right after). nil when it can't tell at all.
    func readyHashes(_ candidates: [Candidate], deadline: Int64) async -> Set<String>? {
        if candidates.isEmpty || refused() { return nil }
        let top = candidates.prefix(DebridPurpose.list.maxUploads)
        var ready = Set(top.filter { ledger.cached(key, $0.hash) == true }.map(\.hash))
        if Self.limited(token) { return ready }
        let ask = Array(top.filter { ledger.cached(key, $0.hash) == nil }.prefix(ledger.allowance(key, purpose)))
        if ask.isEmpty { return ready }
        await snapshot()
        if !ledger.usersKnown(scope) { return ready }
        var added: [(String, String)] = []
        defer { cleanup(added, kept: []) }
        if let mags = await upload(ask, deadline: deadline, added: &added) {
            for (h, m) in mags where m.ready { ready.insert(h) }
        }
        return ready
    }

    /// First upload ever for this account: what it holds now is the viewer's own (an hourly listing keeps this current).
    /// Later lookups start the hourly janitor in the background.
    private func snapshot() async {
        if !ledger.usersKnown(scope) {
            let s = Self.state
            s.lock.withLock { s.sweptFor = scope; s.sweptAt = Mono.now() }
            _ = await Self.janitor(token, why: "first look at the account", boundMs: 4_000)
        } else {
            Self.maybeHourly(token)
        }
    }

    /// The janitor at most hourly, in the background (the Fire Stick ran it on app start, then hourly).
    static func maybeHourly(_ token: String) {
        let scope = DebridLedger.scope("ad", token)
        let now = Mono.now()
        let s = state
        let why: String? = s.lock.withLock {
            if s.sweptFor == scope && now - s.sweptAt < 3_600_000 { return nil }
            let first = s.sweptFor != scope
            s.sweptFor = scope; s.sweptAt = now
            return first ? "app start" : "hourly"
        }
        guard let why else { return }
        Task.detached(priority: .utility) { _ = await janitor(token, why: why, boundMs: 8_000) }
    }

    /// [candidates] minus hashes seen uncached lately; within each quality (in rank order), the ones seen ready lately
    /// first, so a Play usually costs one upload without trading quality.
    private func order(_ candidates: [Candidate]) -> [Candidate] {
        var state: [String: Bool?] = [:]
        for c in candidates { state[c.hash] = ledger.cached(key, c.hash) }
        let live = candidates.filter { (state[$0.hash] ?? nil) != false }
        var qualities: [String] = []
        for c in live where !qualities.contains(c.quality) { qualities.append(c.quality) }
        return qualities.flatMap { q in
            let group = live.filter { $0.quality == q }
            return group.filter { (state[$0.hash] ?? nil) == true } + group.filter { (state[$0.hash] ?? nil) != true }
        }
    }

    /// One magnet/upload of [chunk]: claimed in the ledger first, counted, sent. What it added goes into [added]; each
    /// hash's cache state is remembered. On an account-limit error: the janitor, one retry, then the limit is remembered.
    private func upload(_ chunk: [Candidate], deadline: Int64, added: inout [(String, String)]) async -> [String: Mag]? {
        let hashes = chunk.map(\.hash)
        let mine = ledger.claim(scope, hashes)
        var r = await send(hashes, deadline: deadline)
        if r.limit { r = await heal(hashes, deadline: deadline, code: r.error) }
        guard let arr = r.data?["magnets"].array else {
            // An error answer added nothing; a lost answer may have (the janitor settles those claims from its listing).
            if r.answered { let o = ledger.owned(scope); ledger.release(scope, mine.filter { o[$0]?.i == nil }) }
            return nil
        }
        var mags: [String: Mag] = [:]
        var seen: [String: Bool] = [:]
        for o in arr {
            let h = o["hash"].string.lowercased()
            let id = o["id"].string
            if id.isEmpty { continue } // rejected: an "error" instead
            let ready = o["ready"].bool
            mags[h] = Mag(id: id, ready: ready)
            seen[h] = ready
            if mine.contains(h) { ledger.setId(scope, h, id); added.append((h, id)) }
        }
        let o = ledger.owned(scope)
        ledger.release(scope, mine.filter { mags[$0] == nil && o[$0]?.i == nil })
        ledger.seen(key, seen)
        return mags
    }

    private func send(_ hashes: [String], deadline: Int64) async -> Res {
        ledger.counted(key, hashes.count, play: purpose == .play)
        let r = await Self.call(token, "magnet/upload", hashes.map { ("magnets[]", $0) }, deadline: deadline)
        // Every magnet refused for a limit counts as the account's limit too.
        if let arr = r.data?["magnets"].array, !arr.isEmpty {
            let codes = arr.map { $0["error"]["code"].string }
            if codes.allSatisfy({ Self.isLimit($0) }) { return Res(data: nil, error: codes[0], answered: true) }
        }
        return r
    }

    /// MAGNET_TOO_MANY (or a similar limit): the janitor (bounded), one retry; still full: AllDebrid rests.
    private func heal(_ hashes: [String], deadline: Int64, code: String) async -> Res {
        let sweep = await Self.janitor(token, why: "magnet limit", boundMs: 3_000, aggressive: true)
        let again = await send(hashes, deadline: deadline)
        if !again.limit {
            plog.warning("AllDebrid: magnet limit; janitor removed \(sweep?.removed ?? 0), retry \(again.data != nil ? "worked" : "failed (\(again.error))", privacy: .public)")
            return again
        }
        ledger.setLimited(scope, until: wallMs() + Self.limitMs)
        if let sweep, sweep.dream == 0, sweep.user >= 900 {
            plog.warning("AllDebrid account is full of the viewer's own magnets; Dream Core can't add new ones")
        } else {
            plog.warning("AllDebrid: magnet limit (\(again.error.isEmpty ? code : again.error, privacy: .public)) after the janitor removed \(sweep?.removed ?? 0) (Dream OS holds \(sweep.map { String($0.dream) } ?? "?", privacy: .public), viewer's \(sweep.map { String($0.user) } ?? "?", privacy: .public)); AllDebrid skipped for \(Self.limitMs / 60_000) min")
        }
        return again
    }

    /// The chosen magnet's file for [c], unlocked to a direct link; nil when it has none that fits or no link yet.
    private func open(_ c: Candidate, _ m: Mag, deadline: Int64) async -> StreamLink? {
        guard let tree = await Self.call(token, "magnet/files", [("id[]", m.id)], deadline: deadline).data?["magnets"][0]["files"].array else { return nil }
        var files: [Entry] = []
        flatten(tree, "", &files)
        let idx = DebridFiles.pick(files.map { ($0.path, $0.bytes) }, c)
        guard idx >= 0, Mono.now() < deadline else { return nil }
        let f = files[idx]
        // No link but "delayed": AllDebrid would have to prepare the file first; the next one may be instant.
        guard let u = await Self.call(token, "link/unlock", [("link", f.link)], deadline: deadline).data else { return nil }
        let url = u["link"].string
        guard url.hasPrefix("https://") || url.hasPrefix("http://") else { return nil }
        let name = u["filename"].string
        let size = u["filesize"].int64
        return found(url, service: key, fileName: name.isEmpty ? DebridFiles.base(f.path) : name,
                     sizeBytes: size > 0 ? size : f.bytes, quality: c.quality, hash: c.hash)
    }

    /// Everything this lookup added but [kept] (the one handed to the player and its alternates), deleted in one
    /// batched call, in the background.
    private func cleanup(_ added: [(String, String)], kept: Set<String>) {
        if added.isEmpty { return }
        let owned = ledger.owned(scope)
        let protect = ledger.inUse
        let drop = added.filter { h, _ in !kept.contains(h) && !protect.contains(h) && owned[h]?.p != true }
        let keptN = added.filter { kept.contains($0.0) }.count
        let keep = keptN > 0 ? " (kept \(keptN) \(purpose == .play ? "playing" : "ready to play"))" : ""
        if drop.isEmpty { if !keep.isEmpty { plog.info("AllDebrid: cleaned 0\(keep, privacy: .public)") }; return }
        let token = token, scope = scope
        Task.detached(priority: .utility) {
            let gone = await Self.delete(token, drop.map { ($0.1, $0.0) }, deadline: Mono.now() + 10_000)
            DebridLedger.shared.release(scope, gone)
            plog.warning("AllDebrid: cleaned \(gone.count)\(keep, privacy: .public)\(gone.count < drop.count ? "; \(drop.count - gone.count) left for the janitor" : "", privacy: .public)")
        }
    }

    /// AllDebrid's file tree ({n, s, l} files, {n, e: [...]} folders) as flat paths.
    private func flatten(_ nodes: [JV], _ dir: String, _ out: inout [Entry]) {
        for n in nodes {
            let path = dir.isEmpty ? n["n"].string : dir + "/" + n["n"].string
            if let sub = n["e"].array { flatten(sub, path, &out) }
            else if !n["l"].string.isEmpty { out.append(Entry(path: path, bytes: n["s"].int64, link: n["l"].string)) }
        }
    }

    private func refused() -> Bool {
        let s = Self.state
        return s.lock.withLock { s.refusedScope == scope && Mono.now() - s.refusedAt < Self.retryMs }
    }

    /// One API call's outcome: [data] on success; [error] AllDebrid's code ("" when none); [answered]: AllDebrid replied.
    struct Res {
        let data: JV?
        let error: String
        let answered: Bool
        var limit: Bool { data == nil && AllDebridService.isLimit(error) }
    }

    /// What a janitor run saw: magnets it removed, Dream OS's left, the viewer's own.
    struct Sweep: Sendable { let removed: Int; let dream: Int; let user: Int }

    static func isLimit(_ code: String) -> Bool {
        !code.isEmpty && (code == "MAGNET_TOO_MANY" || code.contains("TOO_MANY") || code.contains("LIMIT") || code.contains("QUOTA"))
    }

    /// AllDebrid hit its magnet limit lately (remembered `limitMs`, across restarts): skip it, ask the other services.
    static func limited(_ token: String) -> Bool { DebridLedger.shared.limitedUntil(DebridLedger.scope("ad", token)) > wallMs() }

    /// Lists the account and deletes Dream OS's magnets (claimed in the ledger) that are older than `maxAge` or beyond
    /// `cap` (oldest first), never one in use; [aggressive] (the account is full): every one not in use. Everything else
    /// in the account is the viewer's own: recorded as such, never touched. At most [boundMs] for the listing and for
    /// the delete each. nil when the account can't be listed (or another run holds it past the bound).
    static func janitor(_ token: String, why: String, boundMs: Int, aggressive: Bool = false) async -> Sweep? {
        let s = state
        let waitEnd = Mono.now() + Int64(boundMs)
        while true {
            let got = s.lock.withLock { () -> Bool in if s.sweeping { return false }; s.sweeping = true; return true }
            if got { break }
            if Mono.now() >= waitEnd { return nil }
            await sleepMs(100)
        }
        defer { s.lock.withLock { s.sweeping = false } }
        let ledger = DebridLedger.shared
        let scope = DebridLedger.scope("ad", token)
        let end = Mono.now() + Int64(boundMs)
        let r = await call(token, "magnet/status", [], deadline: end, base: api41)
        guard let arr = r.data?["magnets"].array else {
            plog.warning("AllDebrid janitor: account not listed (\(r.error.isEmpty ? "no answer" : r.error, privacy: .public))")
            return nil
        }
        struct Item: Hashable { let id: String; let hash: String }
        let items = arr.compactMap { o -> Item? in
            let h = o["hash"].string.lowercased()
            return h.isEmpty ? nil : Item(id: o["id"].string, hash: h)
        }
        let owned = ledger.owned(scope)
        let held = Set(items.map(\.hash))
        let user = items.filter { owned[$0.hash] == nil }
        ledger.setUsers(scope, Set(user.map(\.hash)))
        let now = wallMs()
        // Claims no longer in the account are gone (a claim under 2 min old may be an upload still on its way).
        ledger.release(scope, owned.filter { !held.contains($0.key) && now - $0.value.t > 120_000 }.map(\.key))
        let dream = items.filter { owned[$0.hash] != nil }
        for d in dream where owned[d.hash]?.i == nil && !d.id.isEmpty { ledger.setId(scope, d.hash, d.id) }
        let protect = ledger.inUse
        let free = dream.filter { !protect.contains($0.hash) && !$0.id.isEmpty }.sorted { (owned[$0.hash]?.t ?? 0) < (owned[$1.hash]?.t ?? 0) }
        var drop = free.filter { aggressive || now - (owned[$0.hash]?.t ?? 0) > maxAge }
        let over = dream.count - drop.count - cap
        if over > 0 { drop += free.filter { !drop.contains($0) }.prefix(over) }
        let gone = drop.isEmpty ? Set<String>() : await delete(token, drop.map { ($0.id, $0.hash) }, deadline: Mono.now() + Int64(boundMs))
        ledger.release(scope, gone)
        if !gone.isEmpty || why != "hourly" {
            plog.warning("AllDebrid janitor (\(why, privacy: .public)): removed \(gone.count) Dream OS magnet\(gone.count == 1 ? "" : "s", privacy: .public) (viewer's \(user.count) untouched; Dream OS keeps \(dream.count - gone.count))")
        }
        return Sweep(removed: gone.count, dream: dream.count - gone.count, user: user.count)
    }

    /// Deletes magnets (id, hash) in one batched call; if AllDebrid refuses the batch (one id already gone fails it
    /// all), one by one. The hashes that are gone afterwards.
    static func delete(_ token: String, _ items: [(String, String)], deadline: Int64) async -> Set<String> {
        if items.isEmpty { return [] }
        let r = await call(token, "magnet/delete", items.map { ("ids[]", $0.0) }, deadline: deadline)
        if r.answered && r.error.isEmpty { return Set(items.map(\.1)) }
        if items.count == 1 && r.error != "MAGNET_INVALID_ID" { return [] }
        var gone = Set<String>()
        for (id, h) in items {
            if Mono.now() >= deadline { break }
            let one = items.count == 1 ? r : await call(token, "magnet/delete", [("id", id)], deadline: deadline)
            if one.answered && (one.error.isEmpty || one.error == "MAGNET_INVALID_ID") { gone.insert(h) }
        }
        return gone
    }

    /// One API call (form POST, Bearer token), logged without the token.
    static func call(_ token: String, _ path: String, _ fields: [(String, String)], deadline: Int64, base: String = api) async -> Res {
        let r: DebridHttp.Reply
        do {
            r = try await DebridHttp.postForm("\(base)\(path)?agent=\(agent)", fields, bearer: token, timeoutMs: DebridHttp.left(deadline))
        } catch {
            plog.warning("AllDebrid: \(path, privacy: .public) failed (\(String(describing: type(of: error)), privacy: .public))")
            return Res(data: nil, error: "", answered: false)
        }
        let j = r.json()
        guard j.isObject else {
            plog.warning("AllDebrid: \(path, privacy: .public) HTTP \(r.code), not JSON")
            return Res(data: nil, error: "", answered: false)
        }
        if j["status"].string != "error" {
            let d = j["data"]
            if d.exists || path == "magnet/delete" { return Res(data: d.exists ? d : JV([String: Any]()), error: "", answered: true) }
            plog.warning("AllDebrid: \(path, privacy: .public) HTTP \(r.code)")
            return Res(data: nil, error: "", answered: true)
        }
        var code = j["error"]["code"].string
        if code.isEmpty { code = "ERROR" }
        if code.hasPrefix("AUTH_") || code == "MUST_BE_PREMIUM" || code.hasPrefix("ACCOUNT_") { refuse(token, code) }
        else if code != "MAGNET_INVALID_ID" || path != "magnet/delete" { plog.warning("AllDebrid: \(path, privacy: .public) error \(code, privacy: .public)") }
        return Res(data: nil, error: code, answered: true)
    }

    /// The account can't be used (bad key, blocked, not premium): skipped for a while, logged once per key.
    private static func refuse(_ token: String, _ code: String) {
        let scope = DebridLedger.scope("ad", token)
        let s = state
        let first = s.lock.withLock { () -> Bool in
            let f = s.refusedScope != scope
            s.refusedScope = scope; s.refusedAt = Mono.now()
            return f
        }
        if first { plog.warning("AllDebrid: \(code.hasPrefix("AUTH_") ? "sign-in no longer valid" : "account can't stream", privacy: .public) (\(code, privacy: .public))") }
    }
}
