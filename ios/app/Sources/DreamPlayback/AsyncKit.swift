import Foundation

/// A one-shot result several callers wait on, each for at most its own time (NativeResolver's FutureTask.get(timeout)).
final class Promise<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var result: T?
    private var done = false
    private var waiters: [UUID: CheckedContinuation<T?, Never>] = [:]

    var isDone: Bool { lock.withLock { done } }

    func fulfill(_ v: T) {
        let ws: [CheckedContinuation<T?, Never>] = lock.withLock {
            if done { return [] }
            done = true; result = v
            let w = Array(waiters.values); waiters.removeAll()
            return w
        }
        for w in ws { w.resume(returning: v) }
    }

    /// The value, or nil when [timeoutMs] passes first (`timedOut` tells which).
    func wait(timeoutMs: Int64) async -> (value: T?, timedOut: Bool) {
        if let r = lock.withLock({ done ? Optional(result) : nil }) { return (r, false) }
        let id = UUID()
        let v: T? = await withCheckedContinuation { (c: CheckedContinuation<T?, Never>) in
            let now: T?? = lock.withLock {
                if done { return .some(result) }
                waiters[id] = c
                return .none
            }
            if let now { c.resume(returning: now); return }
            Task.detached { [weak self] in
                await sleepMs(max(timeoutMs, 1))
                guard let self else { return }
                let w = self.lock.withLock { self.waiters.removeValue(forKey: id) }
                w?.resume(returning: nil)
            }
        }
        let finished = lock.withLock { done }
        return (v, v == nil && !finished)
    }
}

/// Wakes waiters when something changes (ScraperEngine.Job.await): wait until [until] or the next `fire`.
final class Signal: @unchecked Sendable {
    private let lock = NSLock()
    private var waiters: [UUID: CheckedContinuation<Void, Never>] = [:]

    func fire() {
        let ws = lock.withLock { () -> [CheckedContinuation<Void, Never>] in let w = Array(waiters.values); waiters.removeAll(); return w }
        for w in ws { w.resume() }
    }

    /// Waits for the next `fire` or until [until] (`Mono.now()` ms); [stillWaiting] is checked under the lock first,
    /// so a change that landed just before isn't missed.
    func wait(until: Int64, stillWaiting: @escaping @Sendable () -> Bool) async {
        let ms = until - Mono.now()
        if ms <= 0 { return }
        let id = UUID()
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            let go: Bool = lock.withLock {
                if !stillWaiting() { return false }
                waiters[id] = c
                return true
            }
            if !go { c.resume(); return }
            Task.detached { [weak self] in
                await sleepMs(ms)
                guard let self else { return }
                let w = self.lock.withLock { self.waiters.removeValue(forKey: id) }
                w?.resume()
            }
        }
    }
}
