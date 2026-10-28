import SwiftUI
import Observation
import DreamCore
import DreamUI

/// Everything a title page shows, loaded when it opens: details, scores, availability, the watch history's word on
/// Play / Resume, My List, and the episodes (the likely season alongside the details, every other season and the
/// stills fetched ahead, so nothing is seen loading in).
@MainActor
@Observable
final class TitleDetailsModel {
    let title: Title
    var details: TitleDetails?
    var failed: String?
    var scores: Scores?
    var availability: AvailabilityDetail?
    var inMyList = false
    /// Movies: the saved spot. Shows: the in-progress or up-next episode.
    var history: HistoryEntry?
    var season = 0
    var episodes: [Int: [Episode]] = [:]
    /// Seasons being fetched right now (the one on show and the look-ahead ones).
    private var loadingSeasons: Set<Int> = []
    private var lookedAhead = false
    var seasonFailed: Set<Int> = []
    /// Median episode length of the first season loaded (Information's Episode Length).
    var episodeMinutes = 0
    /// Episode pills (aired in the last 21 days only), by episode id.
    var episodePills: [String: Availability] = [:]

    static let finished = 0.92

    init(title: Title) { self.title = title }

    var isShow: Bool { title.kind == .tv }
    var shown: Title { details?.title ?? title }

    func load() async {
        // Instant Play: only the title page actually opened looks ahead for its likely stream (returns at once).
        Services.shared.lookAhead.titleOpened(title)
        let c = Services.shared.catalog
        async let mine = Services.shared.myList.contains(title.key)
        // A watched show: TMDB's air state again when due, so a new episode takes the Watched pill away.
        if isShow, WatchedLedger.shared.needsAirCheck(title.id) {
            let id = title.id
            Task { if let s = await c.showAirState(showId: id) { WatchedLedger.shared.noteAir(show: id, s) } }
        }
        await refreshHistory()
        // Instant episodes (owner rule): the likely season (the history's, else Season 1) is fetched alongside the
        // details instead of after them, and a season seen earlier this session shows from memory at once.
        var guessed = false
        if isShow, season == 0 {
            let guess = max(1, history?.season ?? 1)
            if let hit = SeasonCache.get(title.id, guess) { episodes[guess] = hit; season = guess; guessed = true }
            Task { await loadSeason(guess) }
        }
        do {
            let d = try await c.details(kind: title.kind, id: title.id)
            details = d
            failed = nil
            if isShow, season == 0 || guessed {
                let best = defaultSeason(d)
                if best > 0, best != season { season = best }
            }
            if season > 0 { Task { await loadSeason(season); await lookAhead() } }
            scores = await c.scores(for: d)
        } catch let e as DreamError {
            failed = e.message
        } catch {
            failed = "Couldn't load this title. Check your internet connection and try again."
        }
        if let a = await c.availabilityDetail(for: title) { availability = a }
        else if let s = await c.availability(for: title) { availability = AvailabilityDetail(state: s) }
        inMyList = await mine
    }

    /// Redone whenever the page shows again (a viewing may have ended behind it): Resume never uses a stale spot.
    func refreshHistory() async {
        let e = await Services.shared.history.entry(for: title.key)
        if history != e { history = e }
    }

    func toggleMyList() async {
        inMyList = await Services.shared.myList.toggle(shown)
    }

    // MARK: Seasons

    /// Seasons already out (number > 0, with episodes, aired).
    var seasonsOut: [SeasonSummary] {
        (details?.seasons ?? []).filter { $0.number > 0 && $0.episodeCount > 0 && PageTime.aired($0.airDate) }.sorted { $0.number < $1.number }
    }

    /// Announced seasons after the last one out (at most two), shown as "Coming Mar 4".
    var seasonsAhead: [SeasonSummary] {
        let last = seasonsOut.map(\.number).max() ?? 0
        return (details?.seasons ?? []).filter { $0.number > last && !PageTime.aired($0.airDate) && !$0.airDate.isEmpty }
            .sorted { $0.number < $1.number }.prefix(2).map { $0 }
    }

    var allSeasons: [SeasonSummary] { seasonsOut + seasonsAhead }

    private func defaultSeason(_ d: TitleDetails) -> Int {
        let out = seasonsOut
        if let s = history?.season, out.contains(where: { $0.number == s }) { return s }
        return out.first?.number ?? allSeasons.first?.number ?? 0
    }

    /// Switching seasons is instant: every season was fetched ahead (`lookAhead`), so this only swaps what shows.
    func pick(season n: Int) {
        guard n != season else { return }
        season = n
        if episodes[n] == nil, let hit = SeasonCache.get(title.id, n) { episodes[n] = hit }
        prefetchStills(episodes[n] ?? [], first: 6)
        Task { await loadSeason(n) }
    }

    /// Fetches a season (once; a fresh copy from this session's memory needs no fetch), then fetches its stills ahead
    /// so the carousel never shows art loading, then asks for the recent episodes' pills (21-day rule) all at once.
    func loadSeason(_ n: Int) async {
        if episodes[n] == nil, let hit = SeasonCache.get(title.id, n) { episodes[n] = hit }
        guard !loadingSeasons.contains(n), episodes[n] == nil || !SeasonCache.fresh(title.id, n) else {
            if let eps = episodes[n] { await settle(eps, season: n) }
            return
        }
        loadingSeasons.insert(n)
        seasonFailed.remove(n)
        do {
            let eps = try await Services.shared.catalog.episodes(showId: title.id, season: n)
            SeasonCache.put(title.id, n, eps)
            if episodes[n] != eps { episodes[n] = eps }
            await settle(eps, season: n)
        } catch {
            if episodes[n] == nil { seasonFailed.insert(n) }
        }
        loadingSeasons.remove(n)
    }

    /// What follows a season's episodes arriving: Information's Episode Length, the stills, the pills.
    private func settle(_ eps: [Episode], season n: Int) async {
        if episodeMinutes == 0 { episodeMinutes = InfoWords.median(eps.filter { PageTime.aired($0.airDate) }.map(\.runtime)) }
        prefetchStills(eps, first: n == season ? eps.count : 6)
        let show = shown
        let recent = eps.filter { PageTime.recentEpisode($0.airDate) && episodePills[$0.id] == nil }
        guard !recent.isEmpty else { return }
        await withTaskGroup(of: (String, Availability?).self) { group in
            for e in recent {
                group.addTask { (e.id, await Services.shared.catalog.episodeAvailability(e, of: show)) }
            }
            for await (id, a) in group { if let a { episodePills[id] = a } }
        }
    }

    /// Every other season in the background, nearest to the one on show first, so a season switch is instant.
    func lookAhead() async {
        guard !lookedAhead else { return }
        lookedAhead = true
        let all = allSeasons.map(\.number).filter { $0 != season }.sorted { abs($0 - season) < abs($1 - season) }
        for n in all where episodes[n] == nil {
            await loadSeason(n)
        }
    }

    private func prefetchStills(_ eps: [Episode], first: Int) {
        var fallbackFetched = false
        for e in eps.sorted(by: { $0.number < $1.number }).prefix(first) {
            if let u = e.still(EpisodeCard.stillSize) {
                ImageStore.shared.prefetch(u)
            } else if !fallbackFetched, let u = episodeFallbackArt(season: e.season).compactMap({ $0 }).first {
                // No still on TMDB (new episodes often have none yet): the card shows the fallback, fetched once.
                ImageStore.shared.prefetch(u)
                fallbackFetched = true
            }
        }
    }

    /// An episode card's art when its still is missing or won't load: the show's backdrop, then the season's poster,
    /// then the show's poster, so every card shows a picture.
    func episodeFallbackArt(season n: Int) -> [URL?] {
        let seasonPoster = allSeasons.first { $0.number == n }?.posterPath
        return [shown.backdrop("w780"), TMDBImage.url(seasonPoster, "w500"), shown.poster("w500")]
    }

    // MARK: Play words (PlayFlow.playLabel)

    /// A movie's saved spot worth resuming, or a show's in-progress episode.
    var resumeEntry: HistoryEntry? {
        guard let e = history, !e.upNext, e.progress > 0, e.progress < Self.finished else { return nil }
        if isShow && ((e.season ?? 0) <= 0 || (e.episode ?? 0) <= 0) { return nil }
        return e
    }

    var playLabel: String {
        if let e = resumeEntry { return isShow ? "Resume S\(e.season ?? 1) E\(e.episode ?? 1)" : "Resume" }
        guard isShow else { return "Play" }
        if let e = history, let s = e.season, let n = e.episode, s > 0, n > 0 { return "Play S\(s) E\(n)" }
        return "Play S1 E1"
    }

    var playRequest: PlayRequest {
        let d = details
        if isShow {
            let s = history?.season ?? 1, n = history?.episode ?? 1
            return PlayRequest(title: shown, imdbId: d?.imdbId, season: max(1, s), episode: max(1, n), episodeName: history?.episodeName)
        }
        return PlayRequest(title: shown, imdbId: d?.imdbId)
    }

    /// "S2 E4  ·  Name  ·  12 Min Left".
    var progressWords: String? {
        guard let e = resumeEntry else { return nil }
        var parts: [String] = []
        if isShow, let s = e.season, let n = e.episode { parts.append("S\(s) E\(n)") }
        if isShow, let name = e.episodeName, !name.isEmpty { parts.append(name) }
        if e.durationMs > 0 {
            let left = (e.durationMs - e.positionMs) / 60_000
            if left > 0 { parts.append("\(left) Min Left") }
        }
        return parts.joined(separator: "  ·  ")
    }

    func request(for ep: Episode, fromStart: Bool = false) -> PlayRequest {
        PlayRequest(title: shown, imdbId: details?.imdbId, season: ep.season, episode: ep.number, episodeName: ep.name, fromStart: fromStart)
    }

    /// The in-progress episode's progress (0...1) for a card, or nil.
    func progress(for ep: Episode) -> Double? {
        guard let e = resumeEntry, e.season == ep.season, e.episode == ep.number else { return nil }
        return e.progress
    }

    func isUpNext(_ ep: Episode) -> Bool {
        guard let e = history else { return false }
        return e.season == ep.season && e.episode == ep.number
    }

    // MARK: Meta line

    var metaYear: String? {
        let day = details?.info.map { $0.releaseDay }.flatMap { $0.isEmpty ? nil : $0 } ?? shown.date
        if let c = InfoWords.coming(day) { return c }
        // The full release date (owner, 2026-10-28), else the year when TMDB has no day.
        if day.count == 10, let full = DreamTime.formatDay(day, "MMM d, yyyy") { return full }
        return shown.year.isEmpty ? nil : String(shown.year.prefix(4))
    }

    var metaLength: String? {
        guard let d = details else { return nil }
        if isShow {
            let n = (d.info?.seasons ?? 0) > 0 ? (d.info?.seasons ?? 0) : seasonsOut.count
            return n > 0 ? "\(n) Season" + (n > 1 ? "s" : "") : nil
        }
        return InfoWords.runtime(d.runtime > 0 ? d.runtime : (d.info?.runtime ?? 0))
    }

    var metaGenres: String? {
        let g = (details?.genres ?? []).prefix(2).joined(separator: ", ")
        return g.isEmpty ? nil : g
    }

    var cert: String? { details?.info?.cert.isEmpty == false ? details?.info?.cert : nil }

    /// Where to Watch names, never YouTube.
    var watchNames: [String] {
        if let i = details?.info {
            let all = InfoWords.cleanProviders(i.streaming + i.free)
            if !all.isEmpty { return all }
        }
        return InfoWords.cleanProviders((details?.providers ?? []).map(\.name))
    }
}

/// A show's seasons kept for the session (memory), so a title page opened again shows its episodes at once.
/// Older than 20 minutes still shows at once and is fetched again behind it (air times and "Coming" words move).
@MainActor
enum SeasonCache {
    private static var store: [String: (at: Date, episodes: [Episode])] = [:]
    private static let freshFor: TimeInterval = 20 * 60

    static func get(_ show: Int, _ season: Int) -> [Episode]? { store["\(show):\(season)"]?.episodes }

    static func fresh(_ show: Int, _ season: Int) -> Bool {
        guard let at = store["\(show):\(season)"]?.at else { return false }
        return Date().timeIntervalSince(at) < freshFor
    }

    static func put(_ show: Int, _ season: Int, _ episodes: [Episode]) {
        if store.count > 400 { store.removeAll() }
        store["\(show):\(season)"] = (Date(), episodes)
    }
}
