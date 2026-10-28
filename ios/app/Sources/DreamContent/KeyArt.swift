import Foundation
import DreamCore

/// Key art for landscape and big cards (owner, 2026-10-28): TMDB's backdrops with the title's name on them (images
/// tagged `iso_639_1 = "en"`), the best voted one, instead of a textless scene. Movies and shows alike. The answer comes
/// from `/{kind}/{id}/images?include_image_language=en,null` (the same call the title logo uses, so it is shared and
/// kept on disk for a week by TmdbDisk), and is remembered per title (memory + DreamStore, 14 days) so a row asks once
/// and later launches decorate it at once. Cards fall back to the textless backdrop, then the poster.
final class KeyArt: @unchecked Sendable {
    static let shared = KeyArt()

    private struct Entry: Codable { let path: String; let at: Date }
    private static let storeKey = "keyart.v1"
    private static let maxAge: TimeInterval = 14 * 86_400
    private static let maxKept = 6000

    private let lock = NSLock()
    private var known: [String: Entry]
    private var inflight: [String: Task<String?, Never>] = [:]
    private var saveQueued = false

    private init() {
        let now = Date()
        known = (DreamStore.shared.get([String: Entry].self, Self.storeKey) ?? [:]).filter { now.timeIntervalSince($0.value.at) < Self.maxAge }
    }

    /// What is known now: `.some(path)` / `.some(nil)` (no key art for it), nil = not asked yet.
    func peek(_ t: Title) -> String?? {
        lock.withLock { known[t.key].map { $0.path.isEmpty ? nil : $0.path } }
    }

    /// The title's key art path (asks TMDB once; identical asks share the call).
    func fetch(_ t: Title) async -> String? {
        if let k = peek(t) { return k }
        let task: Task<String?, Never> = lock.withLock {
            if let running = inflight[t.key] { return running }
            let kind = t.kind, id = t.id
            let task = Task.detached(priority: .userInitiated) { await Self.ask(kind, id) }
            inflight[t.key] = task
            return task
        }
        let path = await task.value
        lock.withLock { inflight[t.key] = nil }
        return path
    }

    private static func ask(_ kind: MediaKind, _ id: Int) async -> String? {
        guard let o = try? await Tmdb.object("/\(kind.rawValue)/\(id)/images", "&include_image_language=en,null") else { return nil }
        let en = (o["backdrops"] as? [[String: Any]] ?? []).filter { $0["iso_639_1"] as? String == "en" }
        let best = en.max { a, b in
            let va = a["vote_average"] as? Double ?? 0, vb = b["vote_average"] as? Double ?? 0
            if va != vb { return va < vb }
            return (a["vote_count"] as? Int ?? 0) < (b["vote_count"] as? Int ?? 0)
        }
        let path = best.flatMap { Tmdb.path($0, "file_path") }
        shared.remember("\(kind.rawValue):\(id)", path)
        return path
    }

    private func remember(_ key: String, _ path: String?) {
        let queue: Bool = lock.withLock {
            known[key] = Entry(path: path ?? "", at: Date())
            if saveQueued { return false }
            saveQueued = true
            return true
        }
        guard queue else { return }
        // Written once a burst of answers is in (a page asks for many titles at once).
        Task.detached(priority: .utility) {
            try? await Task.sleep(for: .seconds(2))
            let snapshot: [String: Entry] = self.lock.withLock {
                self.saveQueued = false
                if self.known.count > Self.maxKept {
                    let keep = self.known.sorted { $0.value.at > $1.value.at }.prefix(Self.maxKept)
                    self.known = Dictionary(uniqueKeysWithValues: keep.map { ($0.key, $0.value) })
                }
                return self.known
            }
            DreamStore.shared.set(snapshot, Self.storeKey)
        }
    }

    /// Puts key art on a row's cards (landscape, big, continue-watching cards that aren't an episode). Known answers
    /// go on at once; unknown ones are asked for together and the row waits at most `wait` seconds (rows never wait
    /// long: answers that come later are remembered, so the next fill and the page's saved copy carry them).
    func decorate(_ row: CatalogRow, wait: Double = 0.25) async -> CatalogRow {
        guard row.style == .landscape || row.style == .big || row.style == .continueWatching, !row.isSpotlight else { return row }
        var r = row
        var ask: [Int] = []
        for i in r.items.indices where r.items[i].episode == nil && r.items[i].keyArtPath == nil {
            if let k = peek(r.items[i].title) { r.items[i].keyArtPath = k } else { ask.append(i) }
        }
        guard !ask.isEmpty, Tmdb.hasKey else { return r }
        let titles = ask.map { r.items[$0].title }
        // Every ask starts now and keeps going past the wait (so it is remembered); the row reads what's in by then.
        let tasks = titles.map { t in Task { await self.fetch(t) } }
        let deadline = Date().addingTimeInterval(wait)
        for task in tasks {
            let left = deadline.timeIntervalSinceNow
            if left <= 0 { break }
            _ = await Self.within(left, task)
        }
        for i in ask {
            if let k = peek(r.items[i].title) { r.items[i].keyArtPath = k }
        }
        return r
    }

    /// Waits for `task` at most `seconds` (never longer: the timer and the task race as unstructured tasks).
    private static func within(_ seconds: Double, _ task: Task<String?, Never>) async -> Bool {
        await withCheckedContinuation { (k: CheckedContinuation<Bool, Never>) in
            let once = OnceFlag()
            Task { _ = await task.value; if once.take() { k.resume(returning: true) } }
            Task { try? await Task.sleep(for: .seconds(seconds)); if once.take() { k.resume(returning: false) } }
        }
    }
}

/// True for the first caller only.
private final class OnceFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    func take() -> Bool { lock.withLock { if done { return false }; done = true; return true } }
}
