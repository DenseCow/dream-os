import DreamCore
import Foundation

/// Runs the providers of the installed scraper modules in the embedded Python (port of ScraperEngine.kt).
///
/// A `ScrapeJob` is one title's scrape: the enabled providers run on a small shared thread pool inside Python
/// ("play" lane for a real Play, "background" for everything else) and every provider's sources stream back as it
/// returns. Nothing secret crosses into Python: debrid checks happen in DreamPlayback.
///
/// Threading rules (Bug 1, 2026-10-28): nothing here ever blocks a Swift concurrency thread. Python runs on plain
/// Threads; every wait has a deadline; the lock held across Python calls (`runnerLock`) is never taken by a reader
/// (`ready` / `needsSync` read `stateLock`, which no Python call sits under); a job that Python never hands back is
/// finished for its callers by a watchdog and its lane slot given back.
enum ScraperEngine {
    static let playThreads = 8
    /// The iPhone has room for four quick checks' providers at once (the stick ran two).
    static let backgroundThreads = 4

    /// Serializes `ensure()` (runner init + module sync). Held across Python calls: only `ensure()` takes it.
    private static let runnerLock = NSLock()
    /// Guards the flags readers ask for. Never held across a Python call.
    private static let stateLock = NSLock()
    /// Guards `jobs` / `nextId` only. Python worker threads take it while holding the GIL (result callbacks), so it must
    /// never be held while calling into Python (sharing a lock with `ensure` deadlocked the first scrape).
    private static let jobsLock = NSLock()
    nonisolated(unsafe) private static var initialized = false
    nonisolated(unsafe) private static var syncedStamp = -1
    nonisolated(unsafe) private static var jobs: [Int: ScrapeJob] = [:]
    nonisolated(unsafe) private static var nextId = 0

    /// Stage lines in the log ("Scrapers: job 3 …"), for finding where a scrape stops. Cheap; turn off once quiet.
    nonisolated(unsafe) static var stages = true

    /// Lanes: a Play (or a title page's look-ahead) never waits behind quick checks. Each lane runs a few scrapes at
    /// once; the rest wait for a slot, never longer than their own time limit.
    enum Lane: String { case play, background }
    private static let gates: [Lane: LaneGate] = [.play: LaneGate(3), .background: LaneGate(2)]

    /// Python is up with the installed modules loaded.
    static var ready: Bool { stateLock.withLock { initialized } && PythonRuntime.isStarted }
    /// Python couldn't start on this phone.
    static var broken: Bool { PythonRuntime.startFailure != nil }
    /// Something was installed or removed since the runner last loaded the modules.
    static var needsSync: Bool { stateLock.withLock { syncedStamp } != Scrapers.stamp }

    static func trace(_ s: @autoclosure () -> String) {
        guard stages else { return }
        let text = s()
        PythonRuntime.log.notice("Scrapers: \(text, privacy: .public)")
    }

    /// Blocking: Python started, the runner set up and in step with what's installed. False if Python can't run.
    /// Never on the main thread or a Swift concurrency thread. Waits at most 45 s for another `ensure` to finish.
    @discardableResult
    static func ensure() -> Bool {
        guard PythonRuntime.start() else { return false }
        // Up and in step: no lock (a Play never waits behind a sync or a stuck call it doesn't need).
        let current = Scrapers.stamp
        if stateLock.withLock({ initialized && syncedStamp == current }) { return true }
        let t0 = Date()
        guard runnerLock.lock(before: Date().addingTimeInterval(45)) else {
            PythonRuntime.log.error("Scrapers: the runner lock stayed busy for 45 s (another thread is stuck in Python)")
            return stateLock.withLock { initialized }
        }
        defer { runnerLock.unlock() }
        let waited = Int(Date().timeIntervalSince(t0) * 1000)
        if waited > 500 { trace("ensure: waited \(waited) ms for the runner lock") }
        do {
            if !stateLock.withLock({ initialized }) {
                let t1 = Date()
                trace("ensure: runner init")
                _ = try PythonRuntime.call("init", [ScraperFiles.home.path, ScraperFiles.temp.path, "0"])
                stateLock.withLock { initialized = true }
                PythonRuntime.log.info("Scrapers: runner ready in \(Int(Date().timeIntervalSince(t1) * 1000)) ms")
            }
            let stamp = Scrapers.stamp
            if stateLock.withLock({ syncedStamp }) != stamp {
                let t1 = Date()
                let libs = Scrapers.libDirs().map(\.path)
                trace("ensure: set_modules (\(libs.count) libs)")
                _ = try PythonRuntime.call("set_modules", [json(libs)])
                let enabled = Scrapers.enabledRefs()
                trace("ensure: warm (\(enabled.count) providers)")
                let n = try PythonRuntime.call("warm", [refsJSON(enabled)])
                stateLock.withLock { syncedStamp = stamp }
                PythonRuntime.log.info("Scrapers: \(n, privacy: .public) of \(enabled.count) enabled providers loaded in \(Int(Date().timeIntervalSince(t1) * 1000)) ms")
            }
            return true
        } catch {
            PythonRuntime.log.error("Scrapers: runner failed (\(String(describing: error).prefix(200), privacy: .public))")
            return stateLock.withLock { initialized }
        }
    }

    /// Provider settings changed (their settings files were rewritten): Python forgets what it read.
    static func settingsChanged() {
        guard ready else { return }
        Thread.detachNewThread { _ = try? PythonRuntime.call("reload_settings") }
    }

    /// Playback started: Python gives back idle memory; its modules stay loaded. Background thread only.
    static func trim() {
        guard ready else { return }
        _ = try? PythonRuntime.call("trim")
    }

    static func refsJSON(_ refs: [ProviderRef]) -> String {
        json(refs.map { ["id": $0.id, "addon": $0.addon, "file": $0.file.path, "name": $0.name, "kind": $0.kind] })
    }

    static func json(_ value: Any) -> String {
        guard let d = try? JSONSerialization.data(withJSONObject: value), let s = String(data: d, encoding: .utf8) else { return "[]" }
        return s
    }

    // MARK: Jobs

    /// Starts one scrape and returns at once. `info`: the title as scraper modules expect it (ScraperInfo);
    /// `light`: the quick check (few fast providers, short limits, no packs); `play`: a real Play's lane (also the
    /// look-aheads'); `urgent`: a viewer is waiting (a real Play): it never waits for a slot and gets its own Python
    /// pool, so look-ahead scrapes can't starve it (2026-10-28: Plays failed as "busy: no play slot" behind them).
    static func start(infoJSON: String, refs: [ProviderRef], label: String, light: Bool, play: Bool, urgent: Bool = false,
                      timeout: TimeInterval, perProvider: TimeInterval, packs: Bool) -> ScrapeJob {
        let id = jobsLock.withLock { nextId += 1; return nextId }
        let job = ScrapeJob(id: id, label: label, light: light, total: refs.count)
        if urgent { job.promote() }
        if refs.isEmpty { job.complete("no providers enabled"); return job }
        jobsLock.withLock { jobs[id] = job }
        let lane: Lane = play ? .play : .background
        let gate = gates[lane]!
        let refsText = refsJSON(refs)
        trace("job \(id) \(label): start (\(lane.rawValue)\(urgent ? ", urgent" : "")\(light ? ", quick check" : ""), \(refs.count) providers, \(Int(timeout)) s)")
        let t = Thread {
            // A slot: a Play waits up to 30 s (something is badly stuck by then), a check only as long as its own limit.
            // An urgent job (a real Play, or one a Play joined meanwhile) takes a slot at once, free or not.
            let slot = SlotHold(gate)
            guard slot.acquire(until: Date().addingTimeInterval(play ? 30 : timeout + 8), urgent: { job.urgent }) else {
                _ = jobsLock.withLock { jobs.removeValue(forKey: id) }
                job.complete("busy: no \(lane.rawValue) slot in time")
                return
            }
            defer {
                slot.release()
                _ = jobsLock.withLock { jobs.removeValue(forKey: id) }
            }
            let waited = Int(Date().timeIntervalSince(job.startedAt) * 1000)
            trace("job \(id): \(lane.rawValue) slot after \(waited) ms\(job.urgent ? " (urgent)" : "")")
            // Python bounds a scrape by `timeout`; long after that the job is finished for its callers, its slot given
            // back (this thread comes back whenever Python lets it) and Python asked to drop it. Armed before `ensure`
            // too: a thread stuck in the runner's init / sync must not keep a slot either.
            let watchdog = DispatchWorkItem {
                guard !job.isDone else { return }
                PythonRuntime.log.error("Scrapers: job \(id) \(job.label, privacy: .public) stuck in Python for \(Int(timeout) + 15) s; released")
                job.complete("stuck")
                slot.release()
                cancel(job)
            }
            DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + timeout + 15, execute: watchdog)
            guard ensure() else { watchdog.cancel(); job.complete("python unavailable"); return }
            if job.isCancelled { watchdog.cancel(); job.complete("cancelled"); return }
            trace("job \(id): scrape call")
            // An urgent scrape runs on its own Python pool ("urgent"), never behind look-ahead providers' tasks.
            let pyLane = job.urgent ? "urgent" : lane.rawValue
            let opts = json(["timeout": timeout, "per": perProvider, "lane": pyLane,
                             "threads": play ? playThreads : backgroundThreads, "packs": packs, "hosts": [String]()] as [String: Any])
            do {
                let t0 = Date()
                let n = try PythonRuntime.call("scrape", [String(id), infoJSON, refsText, opts])
                watchdog.cancel()
                trace("job \(id): scrape returned \(n) in \(Int(Date().timeIntervalSince(t0) * 1000)) ms")
                job.complete()
            } catch {
                watchdog.cancel()
                job.complete("failed: \(String(describing: error).prefix(160))")
            }
        }
        t.qualityOfService = play ? .userInitiated : .utility
        t.stackSize = 4 << 20
        t.name = "scrapers-job-\(id)"
        t.start()
        return job
    }

    static func cancel(_ job: ScrapeJob) {
        guard ready else { return }
        Thread.detachNewThread { _ = try? PythonRuntime.call("cancel", [String(job.id)]) }
    }

    /// A job became urgent (a Play joined a look-ahead's scrape): a thread of its waiting for a slot goes now.
    static func promoted(_ job: ScrapeJob) {
        for g in gates.values { g.poke() }
    }

    private static func job(_ id: Int) -> ScrapeJob? { jobsLock.withLock { jobs[id] } }

    /// From Python (sink): one provider's sources, a JSON array of {h, n, q, s, b, k, pk, ...}.
    static func deliver(job id: Int, provider: String, json text: String) {
        guard let job = job(id),
              let arr = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [[String: Any]] else { return }
        let base = provider.components(separatedBy: "#").first ?? provider
        var out: [ScrapeItem] = []
        out.reserveCapacity(arr.count)
        for o in arr {
            let kind = o["k"] as? String ?? "torrent"
            guard kind == "torrent", let h = o["h"] as? String, h.count == 40 else { continue }
            out.append(ScrapeItem(hash: h, name: o["n"] as? String ?? "", quality: o["q"] as? String ?? "SD",
                                  seeders: (o["s"] as? NSNumber)?.intValue ?? 0, bytes: (o["b"] as? NSNumber)?.int64Value ?? 0,
                                  provider: base, pack: !((o["pk"] as? String) ?? "").isEmpty))
        }
        job.add(provider: provider, items: out)
    }

    /// From Python (sink): one provider finished (count -1 = timed out).
    static func finished(job id: Int, provider: String, count: Int, ms: Int, error: String?) {
        job(id)?.finished(provider: provider, count: count, ms: ms, error: error)
        Scrapers.recordStat(provider.components(separatedBy: "#").first ?? provider, ms: ms, count: count, error: error)
    }
}

/// A lane's slots: a counting gate with deadlines (never a semaphore without one).
final class LaneGate: @unchecked Sendable {
    private let cond = NSCondition()
    private var free: Int

    init(_ slots: Int) { free = slots }

    /// Waits for a free slot until [deadline]; an [urgent] caller takes one at once even when none is free (the count
    /// goes negative and later callers wait until it is given back). [urgent] is read again on every wake-up, so a
    /// waiting job promoted meanwhile goes through.
    func acquire(until deadline: Date, urgent: () -> Bool = { false }) -> Bool {
        cond.lock()
        defer { cond.unlock() }
        while free <= 0 && !urgent() {
            let now = Date()
            if now >= deadline { return false }
            _ = cond.wait(until: min(deadline, now.addingTimeInterval(0.3)))
        }
        free -= 1
        return true
    }

    func release() {
        cond.lock()
        free += 1
        cond.signal()
        cond.unlock()
    }

    /// Wakes every waiter (one of them may be urgent now).
    func poke() {
        cond.lock()
        cond.broadcast()
        cond.unlock()
    }
}

/// One job's hold on a lane slot: released once, by the job's thread or by the watchdog, whichever comes first.
private final class SlotHold: @unchecked Sendable {
    private let gate: LaneGate
    private let lock = NSLock()
    private var held = false

    init(_ gate: LaneGate) { self.gate = gate }

    func acquire(until deadline: Date, urgent: () -> Bool = { false }) -> Bool {
        guard gate.acquire(until: deadline, urgent: urgent) else { return false }
        lock.withLock { held = true }
        return true
    }

    func release() {
        let had = lock.withLock { let h = held; held = false; return h }
        if had { gate.release() }
    }
}

/// One torrent a provider found (lower-case hex info hash).
struct ScrapeItem: Sendable, Hashable {
    var hash: String
    var name: String
    var quality: String
    var seeders: Int
    var bytes: Int64
    /// The provider id ("<addon>:<name>").
    var provider: String
    /// A season pack (its size is the whole season's).
    var pack: Bool
}

/// One title's scrape. Sources arrive while it runs (`items`, `version` grows); `changes()` streams updates.
final class ScrapeJob: @unchecked Sendable {
    let id: Int
    let label: String
    let light: Bool
    let total: Int
    let startedAt = Date()
    private let lock = NSLock()
    private var byHash: [String: ScrapeItem] = [:]
    private var order: [String] = []
    private var _version = 0
    private var _done = false
    private var _cancelled = false
    private var _urgent = false
    private var _firstMs = 0
    private var _answered = 0
    private var errors = 0
    private var withResults = Set<String>()
    private var slow: [String] = []
    private var watchers: [UUID: AsyncStream<Int>.Continuation] = [:]
    private var doneWaiters: [UUID: CheckedContinuation<Void, Never>] = [:]

    init(id: Int, label: String, light: Bool, total: Int) {
        self.id = id; self.label = label; self.light = light; self.total = total
    }

    var version: Int { lock.withLock { _version } }
    var isDone: Bool { lock.withLock { _done } }
    var isCancelled: Bool { lock.withLock { _cancelled } }
    /// ms from start to the first source (0 = none yet).
    var firstMs: Int { lock.withLock { _firstMs } }
    var answered: Int { lock.withLock { _answered } }
    var count: Int { lock.withLock { byHash.count } }
    /// Ended with every provider failing or timing out (the network, not "nothing out yet"), or without Python at all.
    var failedAll: Bool { lock.withLock { _done && (_answered == 0 || errors >= _answered) } }

    func items() -> [ScrapeItem] { lock.withLock { order.compactMap { byHash[$0] } } }

    /// Yields the version after every change; ends when the job ends.
    func changes() -> AsyncStream<Int> {
        AsyncStream { c in
            let key = UUID()
            let finishedAlready: Bool = lock.withLock {
                if _done { return true }
                watchers[key] = c
                c.yield(_version)
                return false
            }
            if finishedAlready { c.yield(version); c.finish(); return }
            c.onTermination = { [weak self] _ in
                guard let self else { return }
                _ = self.lock.withLock { self.watchers.removeValue(forKey: key) }
            }
        }
    }

    /// Waits until the job ends or `deadline`. True if it ended. Never blocks a thread; a timer wakes it at the deadline.
    func awaitDone(until deadline: Date) async -> Bool {
        if isDone { return true }
        let wait = deadline.timeIntervalSinceNow
        if wait <= 0 { return false }
        let key = UUID()
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            let go: Bool = lock.withLock {
                if _done { return false }
                doneWaiters[key] = c
                return true
            }
            if !go { c.resume(); return }
            Task.detached { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
                guard let self else { return }
                let w = self.lock.withLock { self.doneWaiters.removeValue(forKey: key) }
                w?.resume()
            }
        }
        return isDone
    }

    func cancel() {
        let first: Bool = lock.withLock { let f = !_cancelled; _cancelled = true; return f }
        if first { ScraperEngine.cancel(self) }
    }

    /// A viewer is waiting on this scrape (a real Play started it, or joined it): it never waits for a lane slot.
    var urgent: Bool { lock.withLock { _urgent } }
    func promote() {
        let first: Bool = lock.withLock { let f = !_urgent; _urgent = true; return f }
        if first && !isDone { ScraperEngine.promoted(self) }
    }

    private func bump() {
        _version += 1
        for c in watchers.values { c.yield(_version) }
    }

    func add(provider: String, items list: [ScrapeItem]) {
        lock.withLock {
            for it in list {
                if let old = byHash[it.hash] {
                    // The same torrent from two providers: keep the one that knows more; singles before packs.
                    if (old.pack && !it.pack) || (old.bytes <= 0 && it.bytes > 0) || (old.pack == it.pack && it.seeders > old.seeders && it.bytes > 0) {
                        byHash[it.hash] = it
                    }
                } else {
                    byHash[it.hash] = it
                    order.append(it.hash)
                }
            }
            if !list.isEmpty {
                withResults.insert(provider.components(separatedBy: "#").first ?? provider)
                if _firstMs == 0 { _firstMs = max(1, Int(Date().timeIntervalSince(startedAt) * 1000)) }
            }
            bump()
        }
    }

    func finished(provider: String, count: Int, ms: Int, error: String?) {
        lock.withLock {
            _answered += 1
            if let error, error != "late" {
                errors += 1
                let name = provider.components(separatedBy: ":").last ?? provider
                slow.append("\(name) (\(error)\(count < 0 ? ", \(ms / 1000) s" : ""))")
            }
            bump()
        }
    }

    func complete(_ error: String? = nil) {
        let (n, providers, slowList, waiters): (Int, Int, [String], [CheckedContinuation<Void, Never>]) = lock.withLock {
            if _done { return (-1, 0, [], []) }
            _done = true
            bump()
            for c in watchers.values { c.finish() }
            watchers.removeAll()
            let w = Array(doneWaiters.values)
            doneWaiters.removeAll()
            return (byHash.count, withResults.count, slow, w)
        }
        guard n >= 0 else { return }
        for w in waiters { w.resume() }
        let ms = Int(Date().timeIntervalSince(startedAt) * 1000)
        let tail = (slowList.isEmpty ? "" : "; no answer: " + slowList.prefix(8).joined(separator: ", ")) + (error.map { "; \($0)" } ?? "")
        PythonRuntime.log.info("Scrapers: \(n) results from \(providers) providers in \(ms) ms (\(self.label, privacy: .public)\(self.light ? ", quick check" : "")\(tail, privacy: .public))")
    }
}
