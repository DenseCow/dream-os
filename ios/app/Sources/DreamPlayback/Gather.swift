import Foundation
import DreamCore

/// One scrape of the viewer's scrapers for a title / episode (ScraperEngine.Job): the candidates stream in as each
/// provider answers (`SourceService.stream`). Kept by Dream Core for 30 minutes, so Change Source opens at once.
final class Gather: @unchecked Sendable {
    private let lock = NSLock()
    private let signal = Signal()
    private var _items: [StreamCandidate] = []
    private var seen = Set<String>()
    private var _version = 0
    private var _done = false
    private var _cancelled = false
    private var _answered = false
    private var _firstMs: Int64 = -1
    private var task: Task<Void, Never>?
    let startedAt = Mono.now()
    /// The whole scrape is given at most this long (the Fire Stick's ScraperEngine: 20 s).
    static let timeoutMs: Int64 = 20_000

    var items: [StreamCandidate] { lock.withLock { _items } }
    var version: Int { lock.withLock { _version } }
    var done: Bool { lock.withLock { _done } }
    var cancelled: Bool { lock.withLock { _cancelled } }
    var count: Int { lock.withLock { _items.count } }
    /// The scrapers answered (with sources, or with an empty batch: "we looked"). False when the scrape ended
    /// without any answer (busy, not running, every provider failed): not "nothing out yet", just unknown.
    var answered: Bool { lock.withLock { _answered } }
    /// Ms from the start to the first source (0 until one arrived).
    var firstMs: Int64 { lock.withLock { max(_firstMs, 0) } }

    /// [urgent]: a real Play is waiting (the scrape never queues behind look-aheads; see SourceService.stream).
    init(_ request: PlayRequest, urgent: Bool = false) {
        let stream = Services.shared.sources.stream(request, urgent: urgent)
        let t = Task.detached(priority: .userInitiated) { [weak self] in
            let timer = Task.detached { [weak self] in
                await sleepMs(Gather.timeoutMs)
                self?.finish()
            }
            for await batch in stream {
                guard let self, !self.cancelled, !self.done else { break }
                self.add(batch)
            }
            timer.cancel()
            self?.finish()
        }
        // Under the lock: the task above can reach `finish` (which reads `task`) before init returns.
        let cancelNow = lock.withLock { task = t; return _done }
        if cancelNow { t.cancel() }
    }

    private func add(_ batch: [StreamCandidate]) {
        lock.withLock {
            _answered = true
            for c in batch where !c.hash.isEmpty {
                let h = c.hash.lowercased()
                if !seen.insert(h).inserted { continue }
                var x = c
                x.hash = h
                _items.append(x)
            }
            if _firstMs < 0 && !_items.isEmpty { _firstMs = Mono.now() - startedAt }
            _version += 1
        }
        signal.fire()
    }

    private func finish() {
        let changed = lock.withLock { () -> Bool in if _done { return false }; _done = true; _version += 1; return true }
        if changed { signal.fire(); lock.withLock { task }?.cancel() }
    }

    func cancel() {
        lock.withLock { _cancelled = true }
        finish()
    }

    /// Waits until something new arrives after [version], the scrape is done, or [until].
    func await(_ version: Int, until: Int64) async {
        await signal.wait(until: until) { [weak self] in
            guard let self else { return false }
            return self.lock.withLock { !self._done && self._version == version }
        }
    }

    /// Waits until the scrape is done or [until].
    func awaitDone(until: Int64) async {
        while Mono.now() < until {
            let (d, v) = lock.withLock { (_done, _version) }
            if d { return }
            await self.await(v, until: until)
        }
    }
}
