import Foundation
import DreamCore

/// Trakt (a later wave) plugs in here: told when the viewer takes something off Continue Watching or changes My List,
/// and it feeds other devices' progress back through `WatchHistory.mergeRemote`.
public protocol HistorySync: Sendable {
    func continueWatchingRemoved(_ key: String) async
    func myListChanged(_ title: Title, added: Bool) async
}

/// What the viewer has been watching, for Continue Watching (WatchHistory.kt). Fed by Dream Player. One entry per
/// movie or per show (a show keeps only its latest episode). Stored per profile.
public final class WatchHistory: @unchecked Sendable {
    public static let shared = WatchHistory()

    /// One entry (WatchHistory.kt `Entry`).
    public struct Entry: Codable, Sendable, Hashable {
        public var title: Title
        public var episodeTitle: String?
        public var season: Int
        public var episode: Int
        public var imdb: String?
        /// 0...1
        public var progress: Double
        public var positionMs: Int64
        public var durationMs: Int64
        /// ms since 1970.
        public var updatedAt: Int64
        /// "dream" (no dot: Dream OS's own) or another app / device ("trakt").
        public var source: String
        /// The queued next episode, not started yet.
        public var upNext: Bool
        /// Finished or removed by the viewer.
        public var hidden: Bool
        public var stillPath: String?
        public var player: String?
        public var isShow: Bool { title.kind == .tv }
        public var key: String { title.key }

        public init(title: Title, episodeTitle: String? = nil, season: Int = 0, episode: Int = 0, imdb: String? = nil, progress: Double = 0,
                    positionMs: Int64 = 0, durationMs: Int64 = 0, updatedAt: Int64 = wallMs(), source: String = WatchHistory.dream,
                    upNext: Bool = false, hidden: Bool = false, stillPath: String? = nil, player: String? = nil) {
            self.title = title; self.episodeTitle = episodeTitle; self.season = season; self.episode = episode; self.imdb = imdb
            self.progress = progress; self.positionMs = positionMs; self.durationMs = durationMs; self.updatedAt = updatedAt
            self.source = source; self.upNext = upNext; self.hidden = hidden; self.stillPath = stillPath; self.player = player
        }

        public var asHistoryEntry: HistoryEntry {
            HistoryEntry(title: title, season: isShow ? season : nil, episode: isShow ? episode : nil, episodeName: episodeTitle,
                         stillPath: stillPath, positionMs: positionMs, durationMs: durationMs, upNext: upNext,
                         updatedAt: Date(timeIntervalSince1970: Double(updatedAt) / 1000))
        }
    }

    public static let finished = 0.92
    /// `Entry.source` of what Dream Player plays.
    public static let dream = "dream"
    private static let maxEntries = 40
    /// Watched this far into something, it counts toward recommendations (`watchedEnough`).
    private static let meaningfulMs: Int64 = 10 * 60_000
    /// Progress reports that moved < 1 % and < ~60 s since the last save aren't written; the newest skipped one is
    /// replayed a minute later, so the position saved is never more than one report old.
    private static let saveEveryMs: Int64 = 55_000
    /// A remote report this close after ours, for the same episode, is our own Trakt scrobble coming back.
    private static let echoMs: Int64 = 3 * 60_000

    private let lock = NSLock()
    private var pending: (HistoryEntry, UUID)?
    nonisolated(unsafe) public static var sync: HistorySync?

    /// Played in Dream OS itself (any source that isn't an app's package name): resumed by the play flow.
    public static func ours(_ e: Entry) -> Bool { !e.source.contains(".") }

    /// [e] (a show's entry) is for an episode after S[season]E[episode]: the viewer has moved on past that one
    /// (WatchHistory.kt `later`).
    public static func later(_ e: Entry, _ season: Int, _ episode: Int) -> Bool {
        e.isShow && (e.season > season || (e.season == season && e.episode > episode))
    }

    /// Per profile and title, the stamp (`HistoryEntry.updatedAt`, set when the player made the report) of the newest
    /// report seen. `record` waits on TMDB before it writes, so two saves can land out of order; one older than a
    /// report already seen is dropped, so a periodic save never overwrites a newer pause / exit save.
    private var newestStamp: [String: Date] = [:]

    private func load() -> [Entry] { DreamStore.shared.get([Entry].self, "history", perProfile: true) ?? [] }

    private func save(_ list: [Entry]) {
        let keep = Array(list.sorted { $0.updatedAt > $1.updatedAt }.prefix(Self.maxEntries))
        DreamStore.shared.set(keep, "history", perProfile: true)
        Task { @MainActor in NotificationCenter.default.post(name: .dreamHistoryChanged, object: nil) }
    }

    /// What Continue Watching shows: newest first, finished / removed ones left out.
    public func entries() -> [Entry] { lock.withLock { load() }.filter { !$0.hidden }.sorted { $0.updatedAt > $1.updatedAt } }

    /// Everything watched, newest first, finished and removed ones included ("Because You Watched").
    public func recent() -> [Entry] { lock.withLock { load() }.sorted { $0.updatedAt > $1.updatedAt } }

    /// TMDB ids of shows watched recently, newest first, finished ones included (New Episodes).
    public func recentShowIds() -> [Int] {
        var seen = Set<Int>()
        return recent().filter(\.isShow).map(\.title.id).filter { seen.insert($0).inserted }
    }

    /// Whether [e] was really watched, so it may shape recommendations.
    public static func watchedEnough(_ e: Entry) -> Bool { e.upNext || e.progress >= finished || e.positionMs >= meaningfulMs }

    /// Takes the title off Continue Watching (it comes back if the viewer watches it again).
    public func remove(_ key: String) {
        let changed: Bool = lock.withLock {
            var list = load()
            guard let i = list.firstIndex(where: { $0.key == key }) else { return false }
            list[i].hidden = true
            save(list)
            return true
        }
        if changed, let s = Self.sync { Task.detached { await s.continueWatchingRemoved(key) } }
    }

    // MARK: Where playback starts

    /// Where the watch history says [r] starts (ms): its saved spot while in progress; 0 once it's finished, taken off
    /// Continue Watching, or the show has moved on past that episode; nil when the history has no say (never played here,
    /// not started). A spot a page or card carried loses to this (STANDARDS §5).
    public func historyStartMs(_ r: PlayRequest) -> Int64? {
        guard let e = recent().first(where: { $0.key == r.title.key }), Self.ours(e) else { return nil }
        if r.title.kind == .tv {
            let s = r.season ?? 0, ep = r.episode ?? 0
            if e.season > s || (e.season == s && e.episode > ep) { return 0 } // the show moved on: that one was watched
            if e.season != s || e.episode != ep { return nil }
        }
        if e.hidden || e.upNext || e.progress >= Self.finished { return 0 }
        return e.progress > 0 ? max(e.positionMs, 0) : nil
    }

    /// Label for a title's main play button ("Play", "Resume", "Play S1 E1", "Resume S2 E5").
    public func playLabel(_ t: Title) -> String {
        if t.kind != .tv {
            let e = entries().first { $0.key == t.key }
            let resume = e.map { $0.progress > 0 && $0.progress < Self.finished && Self.ours($0) } ?? false
            return resume ? "Resume" : "Play"
        }
        guard let next = entries().first(where: { $0.key == t.key }) else { return "Play S1 E1" }
        let verb = next.upNext || next.progress <= 0 ? "Play" : "Resume"
        return "\(verb) S\(next.season) E\(next.episode)"
    }

    // MARK: Recording

    /// Dream Player's report for what plays. [exact]: it stopped, paused or left: written as is.
    public func record(_ h: HistoryEntry, exact: Bool = false, source: String = WatchHistory.dream) async {
        let isShow = h.title.kind == .tv
        let season = h.season ?? 0, episode = h.episode ?? 0
        var title = h.title
        var epTitle = h.episodeName
        var still = h.stillPath
        var duration = h.durationMs
        var imdb: String?
        // What the player didn't know: artwork, the episode's name and still, the running time (TMDB, cached).
        if title.posterPath == nil || title.backdropPath == nil || (!isShow && duration <= 0) {
            if let d = try? await Services.shared.catalog.details(kind: title.kind, id: title.id) {
                title.posterPath = title.posterPath ?? d.title.posterPath
                title.backdropPath = title.backdropPath ?? d.title.backdropPath
                if title.overview.isEmpty { title.overview = d.title.overview }
                if title.year.isEmpty { title.year = d.title.year }
                imdb = d.imdbId
                if !isShow && duration <= 0 { duration = Int64(d.runtime) * 60_000 }
            }
        }
        if isShow && season > 0 && episode > 0 && (epTitle == nil || still == nil || duration <= 0) {
            if let ep = (try? await Services.shared.catalog.episodes(showId: title.id, season: season))?.first(where: { $0.number == episode }) {
                if (epTitle ?? "").isEmpty { epTitle = ep.name.isEmpty ? nil : ep.name }
                if still == nil { still = ep.stillPath }
                if duration <= 0 { duration = Int64(ep.runtime) * 60_000 }
            }
        }
        let progress = duration > 0 ? min(max(Double(h.positionMs) / Double(duration), 0), 1) : 0
        let now = wallMs()
        var base = Entry(title: title, episodeTitle: epTitle, season: season, episode: episode, imdb: imdb, progress: progress,
                         positionMs: h.positionMs, durationMs: duration, updatedAt: now, source: source, stillPath: still)
        let next: Entry? = progress >= Self.finished && isShow ? await nextEpisode(base) : nil
        if progress >= Self.finished { Self.markWatched(base) } // the Watched pill (outside the lock: the ledger reads history)
        let token = UUID()
        let stampKey = DreamStore.shared.profileId + "|" + base.key
        let skipped: Bool = lock.withLock {
            // Out of order (an older save that waited longer on TMDB than a newer one): the newer one stands.
            if let seen = newestStamp[stampKey], h.updatedAt < seen {
                plog.info("watch history: older save for \(base.title.name, privacy: .public) S\(season)E\(episode) dropped (a newer one landed first)")
                return false
            }
            if newestStamp.count > 200 { newestStamp.removeAll() } // only the titles playing now matter
            newestStamp[stampKey] = h.updatedAt
            var list = load()
            let old = list.first { $0.key == base.key }
            // Keep the player that played it when this report doesn't know.
            if base.player == nil, let o = old, o.player != nil, o.source == base.source { base.player = o.player }
            // An older episode finishing (credits still rolling after the next one was queued, a late save) never takes
            // the show back from a later one: the Up Next card, or the later episode's own spot, stays.
            if let o = old, isShow, !o.hidden, progress >= Self.finished, Self.later(o, season, episode) { return false }
            // Ignore a peek (under 90 s) at something that isn't on the row yet.
            if (old == nil || old!.hidden) && h.positionMs < 90_000 && progress < Self.finished { return false }
            // Same thing still playing and barely moved: skip the write (replayed a minute later).
            if !exact, let o = old, !o.hidden, !o.upNext, progress < Self.finished, o.progress < Self.finished,
               o.season == season, o.episode == episode, o.source == base.source, o.player == base.player,
               abs(progress - o.progress) < 0.01, (0..<Self.saveEveryMs).contains(now - o.updatedAt) {
                pending = (h, token)
                return true
            }
            pending = nil
            let firstOrDone = old == nil || old!.hidden || progress >= Self.finished
            list.removeAll { $0.key == base.key }
            if progress < Self.finished { list.append(base) }
            else if !isShow { var b = base; b.hidden = true; list.append(b) } // finished movie: off the row
            else if let next { list.append(next) }
            else { var b = base; b.hidden = true; list.append(b) }
            save(list)
            if firstOrDone {
                plog.info("watch history: \(base.title.name, privacy: .public) S\(season)E\(episode) \(Int(progress * 100))% via \(source, privacy: .public)")
            }
            return false
        }
        if skipped {
            Task.detached { [weak self] in
                await sleepMs(61_000)
                guard let self else { return }
                let p: HistoryEntry? = self.lock.withLock {
                    guard let p = self.pending, p.1 == token else { return nil }
                    self.pending = nil
                    return p.0
                }
                if let p { await self.record(p, exact: true, source: source) }
            }
        }
    }

    /// The episode after [e] as an "Up Next" entry, if it exists and has aired.
    private func nextEpisode(_ e: Entry) async -> Entry? {
        let today = DreamTime.todayYmd()
        func aired(_ ep: Episode) -> Bool { !ep.airDate.isEmpty && ep.airDate <= today }
        var season = e.season
        var next = (try? await Services.shared.catalog.episodes(showId: e.title.id, season: e.season))?.first { $0.number == e.episode + 1 }
        if next == nil {
            season = e.season + 1
            next = (try? await Services.shared.catalog.episodes(showId: e.title.id, season: season))?.first { $0.number == 1 }
        }
        guard let n = next, aired(n) else { return nil }
        var out = e
        out.season = season; out.episode = n.number; out.episodeTitle = n.name.isEmpty ? nil : n.name
        out.stillPath = n.stillPath; out.progress = 0; out.positionMs = 0; out.durationMs = Int64(n.runtime) * 60_000; out.upNext = true
        return out
    }

    /// Trakt sync: titles watched or paused on other devices, already matched to TMDB, with `updatedAt` = when that
    /// happened there. Last write wins: one replaces ours only when it's newer. Finished ones leave the row (a movie) or
    /// move on to Up Next (a show). A show never goes back: an earlier episode's leftover progress or finish (a paused
    /// point Trakt kept, a scrobble sent late) never replaces a later episode we have, in progress, up next or finished.
    /// Returns how many entries changed.
    @discardableResult
    public func mergeRemote(_ items: [Entry]) async -> Int {
        func newer(_ e: Entry, _ old: Entry?) -> Bool {
            guard let old else { return true }
            return e.updatedAt > old.updatedAt && !(old.season == e.season && old.episode == e.episode && e.updatedAt - old.updatedAt < Self.echoMs)
                && !Self.later(old, e.season, e.episode)
        }
        let current = Dictionary(lock.withLock { load() }.map { ($0.key, $0) }, uniquingKeysWith: { a, _ in a })
        var resolved: [Entry] = []
        for e in items where newer(e, current[e.key]) {
            if e.progress < Self.finished { resolved.append(e) }
            else if !e.isShow { var x = e; x.hidden = true; resolved.append(x) }
            else if let n = await nextEpisode(e) { resolved.append(n) }
            else { var x = e; x.hidden = true; resolved.append(x) }
        }
        for e in items where e.progress >= Self.finished { Self.markWatched(e) } // Trakt's finished ones count as watched
        if resolved.isEmpty { return 0 }
        return lock.withLock {
            var list = load()
            var n = 0
            for e in resolved {
                let old = list.first { $0.key == e.key }
                if !newer(e, old) { continue } // a local report may have landed meanwhile
                list.removeAll { $0.key == e.key }
                // Same episode as ours: keep how we play it.
                if let old, old.season == e.season, old.episode == e.episode { var x = e; x.source = old.source; x.player = old.player; list.append(x) }
                else { list.append(e) }
                n += 1
            }
            if n > 0 { save(list) }
            return n
        }
    }

    // MARK: Watched (WatchedLedger, the Watched pill)

    /// A finished movie or episode into the Watched ledger; a show's air state is asked behind (TMDB, cached).
    static func markWatched(_ e: Entry) {
        if !e.isShow { WatchedLedger.shared.markMovie(e.title.id); return }
        guard e.season > 0, e.episode > 0 else { return }
        WatchedLedger.shared.markEpisode(show: e.title.id, season: e.season, episode: e.episode)
        let id = e.title.id
        Task.detached(priority: .utility) {
            if let s = await Services.shared.catalog.showAirState(showId: id) { WatchedLedger.shared.noteAir(show: id, s) }
        }
    }

    /// What the history really knows was finished, for a profile's first Watched ledger: finished movies and
    /// episodes, and the episode right before an Up Next one in the same season (that one was finished). Nothing else
    /// is inferred; in-progress ones don't count.
    public func watchedSeeds() -> [WatchedSeed] {
        recent().compactMap { e -> WatchedSeed? in
            if !e.isShow { return e.progress >= Self.finished ? .movie(e.title.id) : nil }
            guard e.season > 0, e.episode > 0 else { return nil }
            if e.upNext { return e.episode > 1 ? .episode(show: e.title.id, season: e.season, episode: e.episode - 1) : nil }
            return e.progress >= Self.finished ? .episode(show: e.title.id, season: e.season, episode: e.episode) : nil
        }
    }

    /// Strips "S1 E2"-style suffixes and years in brackets from a title (for matching another device's report).
    public static func cleanTitle(_ raw: String) -> String {
        raw.replacingOccurrences(of: "\\s*[\\(\\[]\\d{4}[\\)\\]]", with: "", options: .regularExpression)
            .replacingOccurrences(of: "(?i)\\s*[-:·|,]?\\s*(S(eason)?\\s*\\d+\\s*[:,.·-]?\\s*E(p(isode)?)?\\s*\\d+).*$", with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
    }
}

/// `HistoryService` for the app (Continue Watching, Resume, play labels).
struct DreamHistoryService: HistoryService {
    private var h: WatchHistory { .shared }
    func continueWatching() async -> [HistoryEntry] { h.entries().map(\.asHistoryEntry) }
    func entry(for key: String) async -> HistoryEntry? { h.entries().first { $0.key == key }?.asHistoryEntry }
    func record(_ entry: HistoryEntry) async { await h.record(entry) }
    func record(_ entry: HistoryEntry, exact: Bool) async { await h.record(entry, exact: exact) }
    func remove(key: String) async { h.remove(key) }
    func startPosition(for request: PlayRequest) async -> Double {
        if request.fromStart { return 0 }
        return Double(h.historyStartMs(request) ?? 0) / 1000
    }
    func recent() async -> [HistoryEntry] { h.recent().map(\.asHistoryEntry) }
    func playLabel(for title: Title) async -> String { h.playLabel(title) }
}
