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
        /// A special only: its source id (`Episode.specialId`). Optional, so rows written before specials decode.
        public var specialId: String?
        /// Movie, numbered episode, or special, as stored. Optional for the same reason; a row without it is read
        /// from the title's kind, which is what every row written so far was.
        public var type: String?

        /// Three-valued on purpose (`WatchKind`): never ask "is this a show" without saying which half you meant.
        public var kind: WatchKind {
            if let t = type, let k = WatchKind(rawValue: t) { return k }
            return specialId != nil ? .special : title.kind == .tv ? .episode : .movie
        }
        /// A numbered episode: the only kind with a place in the show's order.
        public var isSeries: Bool { kind.isSeries }
        public var isSpecial: Bool { kind.isSpecial }
        /// Part of a show, numbered or not (presentation, taste, row building).
        public var ofShow: Bool { kind.ofShow }
        /// A special keeps its own row, so it never takes the show's place on Continue Watching.
        public var key: String { WatchKind.historyKey(title: title, kind: kind, specialId: specialId) }

        public init(title: Title, episodeTitle: String? = nil, season: Int = 0, episode: Int = 0, imdb: String? = nil, progress: Double = 0,
                    positionMs: Int64 = 0, durationMs: Int64 = 0, updatedAt: Int64 = wallMs(), source: String = WatchHistory.dream,
                    upNext: Bool = false, hidden: Bool = false, stillPath: String? = nil, player: String? = nil,
                    kind: WatchKind? = nil, specialId: String? = nil) {
            self.title = title; self.episodeTitle = episodeTitle; self.season = season; self.episode = episode; self.imdb = imdb
            self.progress = progress; self.positionMs = positionMs; self.durationMs = durationMs; self.updatedAt = updatedAt
            self.source = source; self.upNext = upNext; self.hidden = hidden; self.stillPath = stillPath; self.player = player
            self.specialId = specialId
            self.type = (kind ?? (specialId != nil ? .special : title.kind == .tv ? .episode : .movie)).rawValue
        }

        public var asHistoryEntry: HistoryEntry {
            HistoryEntry(title: title, season: isSeries ? season : nil, episode: isSeries ? episode : nil, episodeName: episodeTitle,
                         stillPath: stillPath, positionMs: positionMs, durationMs: durationMs, upNext: upNext,
                         updatedAt: Date(timeIntervalSince1970: Double(updatedAt) / 1000), hidden: hidden,
                         kind: kind, specialId: specialId)
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

    /// [e] (a series row) is for an episode after S[season]E[episode]: the viewer has moved on past that one
    /// (WatchHistory.kt `later`). A special has no place in that order, so it is never "later" than anything.
    public static func later(_ e: Entry, _ season: Int, _ episode: Int) -> Bool {
        e.isSeries && (e.season > season || (e.season == season && e.episode > episode))
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

    /// Whether [e] was really watched, so it may shape recommendations.
    public static func watchedEnough(_ e: Entry) -> Bool { e.upNext || e.progress >= finished || e.positionMs >= meaningfulMs }

    // MARK: Named lookups
    //
    // A show can have two rows at once: its own, and a special's. Say which one you want, because a series-position
    // rule given a special's row hides a real new episode.

    /// The show's own row: where the viewer is in the numbered episodes, finished and removed ones included.
    public func seriesEntry(_ showId: Int) -> Entry? { Self.seriesEntry(recent(), showId) }

    public static func seriesEntry(_ history: [Entry], _ showId: Int) -> Entry? {
        history.first { $0.isSeries && $0.title.id == showId }
    }

    /// The show's own row while it is still on Continue Watching (Resume and Play's words).
    public func seriesOnRow(_ showId: Int) -> Entry? { entries().first { $0.isSeries && $0.title.id == showId } }

    /// One special's own row, by its source id.
    public func specialEntry(_ showId: Int, _ specialId: String) -> Entry? { Self.specialEntry(recent(), showId, specialId) }

    public static func specialEntry(_ history: [Entry], _ showId: Int, _ specialId: String) -> Entry? {
        guard !specialId.isEmpty else { return nil }
        return history.first { $0.isSpecial && $0.title.id == showId && $0.specialId == specialId }
    }

    /// The row [r] resumes from: the special's own for a special, else the title's.
    public func entryFor(_ r: PlayRequest) -> Entry? {
        if let id = r.specialId { return specialEntry(r.title.id, id) }
        return recent().first { $0.key == r.title.key }
    }

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
        // A special resumes from its own row, like a movie: the show's position has nothing to do with it.
        guard let e = entryFor(r), Self.ours(e) else { return nil }
        if e.isSeries {
            let s = r.season ?? 0, ep = r.episode ?? 0
            if e.season > s || (e.season == s && e.episode > ep) { return 0 } // the show moved on: that one was watched
            if e.season != s || e.episode != ep { return nil }
        }
        if e.hidden || e.upNext || e.progress >= Self.finished { return 0 }
        return e.progress > 0 ? max(e.positionMs, 0) : nil
    }

    /// Label for a title's main play button ("Play", "Resume", "Play S1 E1", "Resume S2 E5"). [jump]: the
    /// `caughtUpNext` answer for a show that left the row, when the caller has one.
    public func playLabel(_ t: Title, jump: Entry? = nil) -> String {
        if t.kind != .tv {
            let e = entries().first { $0.key == t.key }
            let resume = e.map { $0.progress > 0 && $0.progress < Self.finished && Self.ours($0) } ?? false
            return resume ? "Resume" : "Play"
        }
        // The series' own row; a special never names this button, and never displaced it.
        guard let next = seriesOnRow(t.id) ?? jump,
              let code = EpisodeWords.code(next.season, next.episode) else { return "Play S1 E1" }
        let verb = next.upNext || next.progress <= 0 ? "Play" : "Resume"
        return "\(verb) \(code)"
    }

    /// A show the viewer caught up on (finished its latest episode with nothing after it yet, so it left Continue
    /// Watching): the episode out after it now (aired, or out early with a source), as an Up Next entry. The title
    /// page's Play button jumps there ("Play S2 E1"). Nil when the show is still in the row, the viewer removed it from
    /// the row (that one starts fresh at S1 E1), or nothing newer is out (WatchHistory.kt `caughtUpNext`).
    public func caughtUpNext(_ key: String) async -> Entry? {
        guard let e = lock.withLock({ load() }).first(where: { $0.key == key }), e.isSeries, e.hidden, !e.upNext,
              e.progress >= Self.finished, e.season > 0, e.episode > 0 else { return nil }
        return await nextEpisode(e)
    }

    // MARK: Recording

    /// Dream Player's report for what plays. [exact]: it stopped, paused or left: written as is.
    public func record(_ h: HistoryEntry, exact: Bool = false, source: String = WatchHistory.dream) async {
        let kind = h.kind
        let season = kind.isSeries ? (h.season ?? 0) : 0, episode = kind.isSeries ? (h.episode ?? 0) : 0
        var title = h.title
        var epTitle = h.episodeName
        var still = h.stillPath
        var duration = h.durationMs
        var imdb: String?
        // What the player didn't know: artwork, the episode's name and still, the running time (TMDB, cached). A
        // movie's running time comes from the title; a special's from its own catalog row, never from the show's.
        if title.posterPath == nil || title.backdropPath == nil || (kind == .movie && duration <= 0) {
            if let d = try? await Services.shared.catalog.details(kind: title.kind, id: title.id) {
                title.posterPath = title.posterPath ?? d.title.posterPath
                title.backdropPath = title.backdropPath ?? d.title.backdropPath
                if title.overview.isEmpty { title.overview = d.title.overview }
                if title.year.isEmpty { title.year = d.title.year }
                imdb = d.imdbId
                if kind == .movie && duration <= 0 { duration = Int64(d.runtime) * 60_000 }
            }
        }
        if epTitle == nil || still == nil || duration <= 0 {
            var row: Episode?
            switch kind {
            case .movie: break
            case .episode:
                if season > 0, episode > 0 {
                    row = (try? await Services.shared.catalog.episodes(showId: title.id, season: season))?.first { $0.number == episode }
                }
            case .special:
                if let id = h.specialId {
                    row = await Services.shared.catalog.specials(showId: title.id).first { $0.specialId == id }
                }
            }
            if let ep = row {
                if (epTitle ?? "").isEmpty { epTitle = ep.name.isEmpty ? nil : ep.name }
                if still == nil { still = ep.stillPath }
                if duration <= 0 { duration = Int64(ep.runtime) * 60_000 }
            }
        }
        let progress = duration > 0 ? min(max(Double(h.positionMs) / Double(duration), 0), 1) : 0
        let now = wallMs()
        var base = Entry(title: title, episodeTitle: epTitle, season: season, episode: episode, imdb: imdb, progress: progress,
                         positionMs: h.positionMs, durationMs: duration, updatedAt: now, source: source, stillPath: still,
                         kind: kind, specialId: h.specialId)
        // Only a numbered episode rolls on to a next one; a special ends where it ends.
        let next: Entry? = progress >= Self.finished && kind.isSeries ? await nextEpisode(base) : nil
        if progress >= Self.finished { Self.markWatched(base) } // the Watched pill (outside the lock: the ledger reads history)
        let token = UUID()
        let stampKey = DreamStore.shared.profileId + "|" + base.key
        let what = EpisodeWords.name(kind, season: season, episode: episode, title: epTitle) ?? "movie"
        let skipped: Bool = lock.withLock {
            // Out of order (an older save that waited longer on TMDB than a newer one): the newer one stands.
            if let seen = newestStamp[stampKey], h.updatedAt < seen {
                plog.info("watch history: older save for \(base.title.name, privacy: .public) \(what, privacy: .public) dropped (a newer one landed first)")
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
            if let o = old, kind.isSeries, !o.hidden, progress >= Self.finished, Self.later(o, season, episode) { return false }
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
            // Only this row: a special's key is its own, so the show's own row is left where it was.
            list.removeAll { $0.key == base.key }
            if progress < Self.finished { list.append(base) }
            else if let next, kind.isSeries { list.append(next) } // the next episode takes its place
            // Finished: a movie and a special both go off the row; so does a series at the end of what's out.
            else { var b = base; b.hidden = true; list.append(b) }
            save(list)
            if firstOrDone {
                plog.info("watch history: \(base.title.name, privacy: .public) \(what, privacy: .public) \(Int(progress * 100))% via \(source, privacy: .public)")
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

    /// The episode after [e] as an "Up Next" entry, if it exists and has aired: the player's own pick (`PlayFlow`),
    /// so a show that skips a season number or starts one past episode 1 stays in Continue Watching.
    private func nextEpisode(_ e: Entry) async -> Entry? {
        guard let n = await PlayFlow.nextEpisode(PlayRequest(title: e.title, season: e.season, episode: e.episode)),
              let season = n.request.season, let episode = n.request.episode else { return nil }
        var out = e
        out.season = season; out.episode = episode; out.episodeTitle = n.request.episodeName
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
            guard e.updatedAt > old.updatedAt,
                  !(old.season == e.season && old.episode == e.episode && e.updatedAt - old.updatedAt < Self.echoMs) else { return false }
            // "The show never goes back" is about the numbered order. A special has no place in it, and its row is
            // its own, so comparing it with episode numbers would reject every special that came from Trakt.
            return !e.kind.isSeries || !Self.later(old, e.season, e.episode)
        }
        let current = Dictionary(lock.withLock { load() }.map { ($0.key, $0) }, uniquingKeysWith: { a, _ in a })
        var resolved: [Entry] = []
        for e in items where newer(e, current[e.key]) {
            if e.progress < Self.finished { resolved.append(e) }
            else if e.kind.isSeries, let n = await nextEpisode(e) { resolved.append(n) } // the next episode takes over
            else { var x = e; x.hidden = true; resolved.append(x) } // a finished movie or special goes off the row
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

    /// A finished movie, episode or special into the Watched ledger; a show's air state is asked behind (TMDB,
    /// cached). A special goes into the show's own specials set, never the movie set and never the "S:E" set.
    static func markWatched(_ e: Entry) {
        switch e.kind {
        case .movie:
            WatchedLedger.shared.markMovie(e.title.id)
            return
        case .special:
            guard let id = e.specialId, !id.isEmpty else { return }
            WatchedLedger.shared.markSpecial(show: e.title.id, id: id)
        case .episode:
            guard e.season > 0, e.episode > 0 else { return }
            WatchedLedger.shared.markEpisode(show: e.title.id, season: e.season, episode: e.episode)
        }
        // A finished episode or special can take the show's Watched pill from it, so its air state is asked again.
        let id = e.title.id
        Task.detached(priority: .utility) {
            if let s = await Services.shared.catalog.showAirState(showId: id) { WatchedLedger.shared.noteAir(show: id, s) }
        }
    }

    /// What the history really knows was finished, for a profile's first Watched ledger: finished movies, episodes and
    /// specials, and the episode right before an Up Next one in the same season (that one was finished). Nothing else
    /// is inferred; in-progress ones don't count.
    public func watchedSeeds() -> [WatchedSeed] {
        recent().compactMap { e -> WatchedSeed? in
            switch e.kind {
            case .movie:
                return e.progress >= Self.finished ? .movie(e.title.id) : nil
            case .special:
                guard let id = e.specialId, !id.isEmpty, e.progress >= Self.finished else { return nil }
                return .special(show: e.title.id, id: id)
            case .episode:
                guard e.season > 0, e.episode > 0 else { return nil }
                if e.upNext { return e.episode > 1 ? .episode(show: e.title.id, season: e.season, episode: e.episode - 1) : nil }
                return e.progress >= Self.finished ? .episode(show: e.title.id, season: e.season, episode: e.episode) : nil
            }
        }
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
    func playLabel(for title: Title) async -> String {
        title.kind == .tv ? h.playLabel(title, jump: await h.caughtUpNext(title.key)) : h.playLabel(title)
    }
    func caughtUpNext(for key: String) async -> HistoryEntry? { await h.caughtUpNext(key)?.asHistoryEntry }
}
