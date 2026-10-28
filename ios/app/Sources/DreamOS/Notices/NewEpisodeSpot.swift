import Foundation
import Observation
import DreamCore

/// "New Episode" (NewEpisodeBanner.kt NewEpisodeSpot): the moment a followed show's new episode is out. Decides which
/// one Home's banner offers, tells the viewer once through a notice (banner only: it waits for Dream OS to be free,
/// never over playback), and keeps the per-profile played / dismissed marks.
///
/// An episode is offered when it came out in the last `bannerDays` days, the viewer hasn't played or dismissed it, it
/// was first shown less than 48 hours ago (no nagging), and the viewer is caught up: a show only on My List always
/// counts; a watched one only when this is the episode right after the last one finished (or the Up Next one).
@MainActor @Observable
final class NewEpisodeSpot {
    static let shared = NewEpisodeSpot()

    /// Offered while at most this many days old (0 = today).
    static let bannerDays = 3
    /// Retired this long after the banner first showed it, played or not.
    static let retireMs: Int64 = 48 * 3_600_000
    /// The refresh while Dream OS stays in front.
    static let checkSeconds: Double = 30 * 60

    /// The episode Home's banner offers, nil = none (the banner is gone).
    private(set) var current: NewEpisodeInfo?
    /// Its status pill once the checker answered.
    private(set) var availability: Availability?

    @ObservationIgnored private var loadedAt: Date?
    @ObservationIgnored private var loading = false
    @ObservationIgnored private var loop: Task<Void, Never>?
    @ObservationIgnored private var soonTask: Task<Void, Never>?
    @ObservationIgnored private var profile = DreamStore.shared.profileId

    private init() {}

    // ---- loading ----

    /// Home's look: New Episodes loads (TMDB, cached), the pick is made, the notice posted for episodes found since.
    func refresh() async {
        if profile != DreamStore.shared.profileId { profile = DreamStore.shared.profileId; current = nil; availability = nil }
        guard !loading else { return }
        loading = true
        defer { loading = false }
        let snap = await Services.shared.catalog.newEpisodes()
        let history = await Services.shared.history.recent()
        loadedAt = Date()
        let pick = onSnapshot(snap, history: history)
        offer(pick)
        if let at = snap.nextAt { soonest(at) }
        startLoop()
    }

    /// Dream OS came to the front: looks again when the last load is older than the half-hour check.
    func refreshIfStale() {
        guard let at = loadedAt, Date().timeIntervalSince(at) >= Self.checkSeconds else { return }
        Task { await refresh() }
    }

    /// Every 30 minutes while Dream OS is in front (network only when TMDB's cache is stale).
    private func startLoop() {
        guard loop == nil else { return }
        loop = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(Self.checkSeconds))
                guard let self, !Task.isCancelled else { return }
                if NoticeBoard.shared.isFree() { await self.refresh() }
            }
        }
    }

    /// The next exact air instant ahead: a look a minute and a half after it, not at the next half hour.
    private func soonest(_ at: Date) {
        let wait = at.timeIntervalSinceNow + 90
        guard wait > 0, wait < Self.checkSeconds else { return }
        soonTask?.cancel()
        soonTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(wait))
            guard let self, !Task.isCancelled else { return }
            Log.app.notice("NewEp: an episode just aired, looking again")
            await self.refresh()
        }
    }

    // ---- which episode ----

    private func onSnapshot(_ s: NewEpisodesSnapshot, history: [HistoryEntry]) -> NewEpisodeInfo? {
        let done = Set(get([String].self, "done") ?? [])
        let first = get([String: Int64].self, "first") ?? [:]
        let now = wallNow()
        func offered(_ i: NewEpisodeInfo) -> Bool {
            guard !i.upcoming, !done.contains(i.key), let d = DreamTime.daysFromToday(i.airDate), (-Self.bannerDays...0).contains(d) else { return false }
            return (first[i.key] ?? now) > now - Self.retireMs && Self.caughtUp(history, i)
        }
        let list = s.recent.filter(offered)
        // Found since the last load: one notice (it shows over Dream OS only, never over playback).
        let news = announce(s).filter(offered)
        if !news.isEmpty { notice(news) }
        NewEpisodeBadges.shared.set(list) // gaps worker: "NEW EPISODE" on these shows' cards in other rows
        EpisodeAlerts.shared.update(s, news: news) // gaps worker: the phone's own notifications for new episodes
        prune(s, done: done, first: first)
        let pick = list.first
        Log.app.notice("NewEp: \(s.recent.count) recent, \(list.count) for the banner\(pick.map { ": \($0.show.name) S\($0.season)E\($0.number)" } ?? "", privacy: .public)")
        return pick
    }

    /// Episodes the loads hadn't seen before, from shows that were already followed: the very first load and newly
    /// followed shows only learn, so adding a show doesn't announce its old episodes (NewEpisodes.markFound).
    private func announce(_ s: NewEpisodesSnapshot) -> [NewEpisodeInfo] {
        let firstRun = get([String].self, "known") == nil
        let known = Set(get([String].self, "known") ?? [])
        let oldShows = Set(get([Int].self, "shows") ?? [])
        let out = firstRun ? [] : s.recent.filter { !known.contains($0.key) && oldShows.contains($0.show.id) }
        put(s.recent.map(\.key), "known")
        put(s.followedIds, "shows")
        return out
    }

    /// Whether the viewer is caught up with [i]'s show: not in the history (followed from My List) yes; else [i] is the
    /// Up Next episode, or the one right after the last one finished (a new season's first after the last season).
    static func caughtUp(_ history: [HistoryEntry], _ i: NewEpisodeInfo) -> Bool {
        guard let e = history.first(where: { $0.title.kind == .tv && $0.title.id == i.show.id }) else { return true }
        let s = i.season, n = i.number
        let es = e.season ?? 0, en = e.episode ?? 0
        if e.upNext { return es == s && en == n }
        if es > s || (es == s && en >= n) { return false }
        let finished = e.progress >= 0.92 || e.hidden
        return finished && ((es == s && en == n - 1) || (es == s - 1 && n == 1))
    }

    private func notice(_ items: [NewEpisodeInfo]) {
        var seen = Set<String>()
        let names = items.map(\.show.name).filter { seen.insert($0).inserted }
        let title = items.count == 1 ? "New Episode" : "\(items.count) New Episodes"
        let body: String
        if items.count == 1 { body = "\(items[0].show.name)  ·  \(Self.code(items[0]))" }
        else if names.count == 1 { body = names[0] }
        else if names.count == 2 { body = "\(names[0]) and \(names[1])" }
        else { body = "\(names[0]), \(names[1]) and \(names.count - 2) More" }
        let action: Notice.Action = names.count == 1
            ? .init(label: "Go to Show", target: NoticeActions.titleTarget(items[0].show))
            : .init(label: "Open Home", target: "home")
        if NoticeCenter.shared.post(key: "newep:" + items.map(\.key).joined(separator: ","), type: .episodes, title: title, body: body,
                                    action: action, store: false) {
            Log.app.notice("NewEp: notice \"\(title, privacy: .public): \(body, privacy: .public)\"")
        }
    }

    /// Keeps the per-profile marks to episodes still in the window.
    private func prune(_ s: NewEpisodesSnapshot, done: Set<String>, first: [String: Int64]) {
        let keep = Set(s.recent.map(\.key))
        let d = done.intersection(keep)
        if d.count != done.count { put(Array(d), "done") }
        let f = first.filter { keep.contains($0.key) }
        if f.count != first.count { put(f, "first") }
    }

    // ---- the banner ----

    private func offer(_ item: NewEpisodeInfo?) {
        guard item?.key != current?.key else { return }
        current = item
        availability = nil
        guard let item else { return }
        // The retirement clock starts the first time the banner shows it.
        var first = get([String: Int64].self, "first") ?? [:]
        if first[item.key] == nil { first[item.key] = wallNow(); put(first, "first") }
        Task {
            let a = await Services.shared.catalog.episodeAvailability(item.episode, of: item.show)
            if self.current?.key == item.key { self.availability = a }
        }
    }

    /// Played or dismissed: never offered again (this profile).
    func done(_ i: NewEpisodeInfo, why: String) {
        var set = Set(get([String].self, "done") ?? [])
        if set.insert(i.key).inserted { put(Array(set), "done") }
        if current?.key == i.key { current = nil; availability = nil }
        NewEpisodeBadges.shared.remove(i.show.id)
        Task { await Services.shared.catalog.markEpisodeSeen(CatalogItem(title: i.show, episode: i.episode)) }
        Log.app.notice("NewEp: \(i.show.name, privacy: .public) S\(i.season)E\(i.number) \(why, privacy: .public)")
    }

    /// "S3 · E7" ("S1 · E1–8" for a batch).
    static func code(_ i: NewEpisodeInfo) -> String {
        "S\(i.season) · E\(i.number)" + (i.lastNumber > i.number ? "–\(i.lastNumber)" : "")
    }

    /// Next to the pill until the checker has answered: "Out Tonight at 9:00 PM", "Out Today", "Out Now".
    func statusWords(_ i: NewEpisodeInfo) -> String? {
        if availability != nil { return nil }
        let today = DreamTime.daysFromToday(i.airDate) == 0
        if let at = i.at, today { return "Out " + DreamTime.airLabel(at) }
        return today ? "Out Today" : "Out Now"
    }

    // ---- developer test ----

    /// Forgets every played / dismissed mark (this profile) and looks again.
    func reset() {
        put(nil as [String]?, "done")
        put(nil as [String: Int64]?, "first")
        current = nil
        Task { await refresh() }
    }

    // ---- storage (per profile) ----

    private func get<T: Decodable>(_ t: T.Type, _ name: String) -> T? { DreamStore.shared.get(t, "newep_banner." + name, perProfile: true) }
    private func put<T: Encodable>(_ v: T?, _ name: String) { DreamStore.shared.set(v, "newep_banner." + name, perProfile: true) }
}
