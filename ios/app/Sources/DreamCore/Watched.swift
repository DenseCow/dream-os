import Foundation

// The "Watched" pill's memory (fire-home Watched.kt): what the viewer has finished, kept per profile, read instantly
// (no network) by every card. The watch history keeps one entry per title and forgets a finished one once it's
// started again, so this ledger is separate and never shrinks: a finished title the viewer starts again keeps its
// Watched pill (as on Netflix).
//  - A movie: finished once (the history's 0.92 rule, or Trakt's history).
//  - An episode: only one the viewer actually finished (each kept as "S:E"; nothing is inferred from a later one,
//    so starting a show at S3E5 marks nothing else).
//  - A show: every aired episode is in that set (TMDB's seasons up to its latest aired episode) and nothing newer has
//    aired. The air state is TMDB's (CatalogService.showAirState), asked again every 6 h and once the announced day
//    passes; a show with a new episode loses Watched.

/// A show's air state as TMDB has it (CatalogService.showAirState).
public struct ShowAirState: Codable, Sendable, Hashable {
    /// The latest aired episode (0 / 0 when none).
    public var lastSeason: Int
    public var lastEpisode: Int
    /// Episode counts of seasons 1 ..< lastSeason (index 0 = Season 1): every episode of those has aired.
    public var seasonCounts: [Int]
    /// "yyyy-MM-dd" the next episode (or the next announced season) airs, "" when none is announced.
    public var nextAir: String
    public init(lastSeason: Int, lastEpisode: Int, seasonCounts: [Int] = [], nextAir: String = "") {
        self.lastSeason = lastSeason; self.lastEpisode = lastEpisode; self.seasonCounts = seasonCounts; self.nextAir = nextAir
    }
}

public extension CatalogService {
    func showAirState(showId: Int) async -> ShowAirState? { nil }
}

public extension Notification.Name {
    /// What the viewer has watched changed (a title finished, a show's air state moved): Watched pills redo.
    static let dreamWatchedChanged = Notification.Name("dream.watchedChanged")
}

/// A title the viewer really finished (the history's backfill, Trakt's history).
public enum WatchedSeed: Sendable {
    case movie(Int)
    case episode(show: Int, season: Int, episode: Int)
}

public final class WatchedLedger: @unchecked Sendable {
    public static let shared = WatchedLedger()

    /// Set by DreamPlayback: the history's finished titles, read once per profile.
    nonisolated(unsafe) public static var seed: (@Sendable () -> [WatchedSeed])?

    struct Show: Codable, Sendable {
        /// Finished episodes, "S:E".
        var episodes: Set<String> = []
        /// TMDB's air state, nil until asked.
        var air: ShowAirState?
        /// When the air state was asked (ms since 1970), 0 = never.
        var checked: Int64 = 0
    }

    struct Book: Codable, Sendable {
        var movies: Set<Int> = []
        /// Show id (as a string) -> its state.
        var shows: [String: Show] = [:]
        var seeded = false
    }

    private static let key = "watched"
    private static let airTtlMs: Int64 = 6 * 3600_000
    private let lock = NSLock()
    private var book: Book?
    private var profile = ""

    /// The current profile's book (under the lock).
    private func current() -> Book {
        let pid = DreamStore.shared.profileId
        if let b = book, profile == pid, b.seeded || Self.seed == nil { return b }
        var b = DreamStore.shared.get(Book.self, Self.key, perProfile: true) ?? Book()
        profile = pid
        if !b.seeded, let seed = Self.seed {
            for s in seed() { _ = Self.apply(s, &b) }
            b.seeded = true
            DreamStore.shared.set(b, Self.key, perProfile: true)
        }
        book = b
        return b
    }

    private static func apply(_ s: WatchedSeed, _ b: inout Book) -> Bool {
        switch s {
        case .movie(let id):
            guard id > 0 else { return false }
            return b.movies.insert(id).inserted
        case .episode(let id, let season, let episode):
            guard id > 0, season > 0, episode > 0 else { return false }
            var show = b.shows["\(id)"] ?? Show()
            guard show.episodes.insert("\(season):\(episode)").inserted else { return false }
            b.shows["\(id)"] = show
            return true
        }
    }

    private func edit(_ f: (inout Book) -> Bool) {
        let changed: Bool = lock.withLock {
            var b = current()
            guard f(&b) else { return false }
            book = b
            DreamStore.shared.set(b, Self.key, perProfile: true)
            return true
        }
        if changed { Task { @MainActor in NotificationCenter.default.post(name: .dreamWatchedChanged, object: nil) } }
    }

    private func read<T>(_ f: (Book) -> T) -> T { lock.withLock { f(current()) } }

    // MARK: Writing

    /// The viewer finished a movie.
    public func markMovie(_ id: Int) { edit { Self.apply(.movie(id), &$0) } }

    /// The viewer finished this one episode.
    public func markEpisode(show: Int, season: Int, episode: Int) {
        edit { Self.apply(.episode(show: show, season: season, episode: episode), &$0) }
    }

    /// Several at once (Trakt's history).
    public func mark(_ items: [WatchedSeed]) {
        guard !items.isEmpty else { return }
        edit { b in items.reduce(false) { Self.apply($1, &b) || $0 } }
    }

    /// TMDB's word on a show the viewer has watched episodes of (ignored for other shows).
    public func noteAir(show: Int, _ s: ShowAirState) {
        edit { b in
            guard var old = b.shows["\(show)"] else { return false }
            old.checked = wallNowMs()
            old.air = s
            b.shows["\(show)"] = old
            return true
        }
    }

    // MARK: Reading (instant, local)

    public func movieWatched(_ id: Int) -> Bool { read { $0.movies.contains(id) } }

    /// The viewer finished this episode.
    public func episodeWatched(show: Int, season: Int, episode: Int) -> Bool {
        read { $0.shows["\(show)"]?.episodes.contains("\(season):\(episode)") ?? false }
    }

    /// Every aired episode finished, and nothing newer has aired since TMDB was asked.
    public func showWatched(_ id: Int) -> Bool {
        read { b in
            guard let s = b.shows["\(id)"], let air = s.air, air.lastSeason > 0, air.lastEpisode > 0,
                  air.seasonCounts.count >= air.lastSeason - 1 else { return false }
            if !air.nextAir.isEmpty && air.nextAir <= DreamTime.todayYmd() { return false }
            for season in 1..<air.lastSeason {
                let n = air.seasonCounts[season - 1]
                if n > 0, !(1...n).allSatisfy({ s.episodes.contains("\(season):\($0)") }) { return false }
            }
            return (1...air.lastEpisode).allSatisfy { s.episodes.contains("\(air.lastSeason):\($0)") }
        }
    }

    public func isWatched(_ t: Title) -> Bool { t.kind == .tv ? showWatched(t.id) : movieWatched(t.id) }

    /// A show with finished episodes whose air state is unknown, older than 6 h, or whose announced day has come.
    public func needsAirCheck(_ show: Int) -> Bool {
        read { b in
            guard let s = b.shows["\(show)"] else { return false }
            let age = wallNowMs() - s.checked
            if s.air == nil || s.checked == 0 || age > Self.airTtlMs { return true }
            let next = s.air?.nextAir ?? ""
            return !next.isEmpty && next <= DreamTime.todayYmd() && age > 3600_000
        }
    }

    private func wallNowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
