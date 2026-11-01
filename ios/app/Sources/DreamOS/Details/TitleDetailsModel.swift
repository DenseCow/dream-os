import SwiftUI
import Observation
import DreamCore
import DreamUI
import DreamPlayer
import DreamContent

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
    /// Movies: the saved spot. Shows: the show's own row (the in-progress or up-next episode), never a special's.
    var history: HistoryEntry?
    /// The group of episodes on show; nil until the first one is picked (no sentinel number).
    var tab: EpisodeTab?
    var episodes: [EpisodeTab: [Episode]] = [:]
    /// How many specials the catalog found, nil until it has answered. 0: the show has none, so no Specials row.
    var specialsCount: Int?
    /// Groups being fetched right now (the one on show and the look-ahead ones).
    private var loadingSeasons: Set<EpisodeTab> = []
    private var lookedAhead = false
    var seasonFailed: Set<EpisodeTab> = []
    /// Median episode length of the first season loaded (Information's Episode Length).
    var episodeMinutes = 0
    /// Episode pills (aired in the last 21 days only), by episode id.
    var episodePills: [String: Availability] = [:]

    static let finished = 0.92

    init(title: Title) {
        self.title = title
        showSavedLanguagePills() // the saved answer on the first frame
    }

    var isShow: Bool { title.kind == .tv }
    var shown: Title { details?.title ?? title }

    func load() async {
        let c = Services.shared.catalog
        // The status a card already showed, with its best copy (no network): the first frame has the pill.
        if availability == nil, let a = await c.availabilityPeek(for: title) { availability = a }
        // Instant Play: only the title page actually opened looks ahead for its likely stream (returns at once).
        Services.shared.lookAhead.titleOpened(title)
        async let mine = Services.shared.myList.contains(title.key)
        // The fresh status alongside the details, once (the cards' answer shows until it lands, `shownAvailability`);
        // a real answer goes to every card of this title at once.
        Task {
            guard let a = await c.availabilityDetail(for: title) else { return }
            availability = a
            CardPills.remember(title.key, a.state)
        }
        // A watched show: TMDB's air state again when due, so a new episode takes the Watched pill away.
        if isShow, WatchedLedger.shared.needsAirCheck(title.id) {
            let id = title.id
            Task { if let s = await c.showAirState(showId: id) { WatchedLedger.shared.noteAir(show: id, s) } }
        }
        await refreshHistory()
        showSavedLanguagePills() // a show's up-next episode is known now
        // Instant episodes (owner rule): the likely season (the history's, else Season 1) is fetched alongside the
        // details instead of after them, and a season seen earlier this session shows from memory at once.
        var guessed = false
        if isShow, tab == nil {
            let guess = EpisodeTab.season(max(1, history?.season ?? 1))
            if let hit = SeasonCache.get(title.id, guess) { episodes[guess] = hit; tab = guess; guessed = true }
            Task { await loadEpisodes(guess) }
            // The specials catalog answers behind the page (TVmaze, and TMDB's season 0 where it has one): the
            // Specials row appears when it lands, and never when the show has none.
            Task { await findSpecials() }
        }
        do {
            let d = try await c.details(kind: title.kind, id: title.id)
            details = d
            failed = nil
            Taste.noteOpened(d.title) // a title page opened: a small taste signal (Taste.swift)
            if isShow, tab == nil || guessed {
                if let best = defaultSeason(d), best != tab { tab = best }
            }
            if let t = tab { Task { await loadEpisodes(t); await lookAhead() } }
            Task { await loadLanguagePills() }
            scores = await c.scores(for: d)
        } catch let e as DreamError {
            failed = e.message
        } catch {
            failed = "Couldn't load this title. Check your internet connection and try again."
        }
        inMyList = await mine
    }

    /// The page's own answer, else what the cards already know for this title (observed: the look-ahead's answer
    /// shows the moment it lands, and on the first render when it's already in).
    var shownAvailability: AvailabilityDetail? {
        if let availability { return availability }
        if case .some(.some(let s)) = CardPills.observed(title.key) { return AvailabilityDetail(state: s) }
        return nil
    }

    /// Redone whenever the page shows again (a viewing may have ended behind it): Resume never uses a stale spot.
    /// A show the viewer caught up on takes the episode out after it now, so Play jumps there ("Play S2 E1").
    func refreshHistory() async {
        var e = await Services.shared.history.entry(for: title.key)
        if e == nil, isShow { e = await Services.shared.history.caughtUpNext(for: title.key) }
        if history != e { history = e }
        guard isShow else { return }
        // Each started special's own row, so its card shows its own progress (`WatchKind`).
        let rows = await Services.shared.history.recent()
        let mine = Dictionary(rows.filter { $0.title.id == title.id && $0.kind.isSpecial && !$0.hidden }
            .compactMap { r in r.specialId.map { ($0, r) } }, uniquingKeysWith: { a, _ in a })
        if startedSpecials != mine { startedSpecials = mine }
    }

    func toggleMyList() async {
        inMyList = await Services.shared.myList.toggle(shown)
    }

    // MARK: Seasons and specials

    /// Seasons already out (number > 0, with episodes, aired, or up early: an episode found before its day,
    /// `Episode.outEarly`; its chip then loses "Coming Nov 1").
    var seasonsOut: [SeasonSummary] {
        (details?.seasons ?? []).filter { s in
            s.number > 0 && s.episodeCount > 0
                && (PageTime.aired(s.airDate) || episodes[.season(s.number)]?.contains { $0.outEarly } == true)
        }.sorted { $0.number < $1.number }
    }

    /// Announced seasons after the last one out (at most two), shown as "Coming Mar 4".
    var seasonsAhead: [SeasonSummary] {
        let last = seasonsOut.map(\.number).max() ?? 0
        return (details?.seasons ?? []).filter { $0.number > last && !PageTime.aired($0.airDate) && !$0.airDate.isEmpty }
            .sorted { $0.number < $1.number }.prefix(2).map { $0 }
    }

    /// The episode picker's rows: the seasons out, the ones announced, then the specials **last**, so the row the
    /// page opens on stays Season 1 and Play keeps naming a real episode.
    var tabs: [EpisodeTabItem] {
        (seasonsOut + seasonsAhead).map(EpisodeTabItem.init)
            + ((specialsCount ?? 0) > 0 ? [EpisodeTabItem(specials: specialsCount ?? 0)] : [])
    }

    var aheadTabs: Set<EpisodeTab> { Set(seasonsAhead.map { EpisodeTab.season($0.number) }) }

    private func defaultSeason(_ d: TitleDetails) -> EpisodeTab? {
        let out = seasonsOut
        if let s = history?.season, out.contains(where: { $0.number == s }) { return .season(s) }
        if let first = out.first ?? seasonsAhead.first { return .season(first.number) }
        return nil
    }

    /// Switching rows is instant: each was fetched ahead (`lookAhead`), so this only swaps what shows.
    func pick(_ t: EpisodeTab) {
        guard t != tab else { return }
        tab = t
        if episodes[t] == nil, let hit = SeasonCache.get(title.id, t) { episodes[t] = hit }
        prefetchStills(episodes[t] ?? [], first: 6)
        Task { await loadEpisodes(t) }
    }

    /// How many specials the show has, so the Specials row shows only when there are some. Behind the page: the
    /// catalog asks TVmaze, which TMDB's own season list can't answer for (`SpecialsCatalog`).
    func findSpecials() async {
        guard isShow, specialsCount == nil else { return }
        let eps = await Services.shared.catalog.specials(showId: title.id)
        specialsCount = eps.count
        guard !eps.isEmpty else { return }
        SeasonCache.put(title.id, .specials, eps)
        if episodes[.specials] == nil { episodes[.specials] = eps }
    }

    /// Fetches one row's episodes (once; a fresh copy from this session's memory needs no fetch), then fetches its
    /// stills ahead so the carousel never shows art loading, then asks for the recent ones' pills (21-day rule).
    func loadEpisodes(_ t: EpisodeTab) async {
        if episodes[t] == nil, let hit = SeasonCache.get(title.id, t) { episodes[t] = hit }
        guard !loadingSeasons.contains(t), episodes[t] == nil || !SeasonCache.fresh(title.id, t) else {
            if let eps = episodes[t] { await settle(eps, tab: t) }
            return
        }
        loadingSeasons.insert(t)
        seasonFailed.remove(t)
        do {
            let eps: [Episode]
            switch t {
            case .season(let n): eps = try await Services.shared.catalog.episodes(showId: title.id, season: n)
            case .specials: eps = await Services.shared.catalog.specials(showId: title.id)
            }
            SeasonCache.put(title.id, t, eps)
            if episodes[t] != eps { episodes[t] = eps }
            await settle(eps, tab: t)
        } catch {
            if episodes[t] == nil { seasonFailed.insert(t) }
        }
        loadingSeasons.remove(t)
    }

    /// What follows a row's episodes arriving: Information's Episode Length, the stills, episodes up early, the pills
    /// (an episode up early gets the same pill as an aired one). A special's length never sets Episode Length, and
    /// nothing in the specials is "up early": its day is enough, which the catalog already settled.
    private func settle(_ eps: [Episode], tab t: EpisodeTab) async {
        if episodeMinutes == 0, !t.isSpecials {
            episodeMinutes = InfoWords.median(eps.filter { PageTime.aired($0.airDate) }.map(\.runtime))
        }
        prefetchStills(eps, first: t == tab ? eps.count : 6)
        let show = shown
        if case .season(let n) = t { await findEarly(eps, season: n, show: show) }
        let recent = (episodes[t] ?? eps).filter { (PageTime.recentEpisode($0.airDate) || $0.outEarly) && episodePills[$0.id] == nil }
        guard !recent.isEmpty else { return }
        await withTaskGroup(of: (String, Availability?).self) { group in
            for e in recent {
                group.addTask { (e.id, await Services.shared.catalog.episodeAvailability(e, of: show)) }
            }
            for await (id, a) in group { if let a { episodePills[id] = a } }
        }
    }

    /// A source decides, not the date (owner, 2026-10-30: "available as soon as there's a source"; a Sunday
    /// episode can appear on the Friday before): the season's episodes still ahead are asked about in order, first one
    /// first, until one isn't up yet. Each one found plays like an aired one (`Episode.outEarly`); the rest keep their
    /// "Coming ..." line.
    private func findEarly(_ eps: [Episode], season n: Int, show: Title) async {
        var found: Set<String> = []
        for e in eps.filter(\.upcoming).sorted(by: { $0.number < $1.number }) {
            guard await Services.shared.catalog.earlyRelease(e, of: show) else { break }
            found.insert(e.id)
        }
        let t = EpisodeTab.season(n)
        guard !found.isEmpty, var list = episodes[t] else { return }
        for i in list.indices where found.contains(list[i].id) { list[i].upcoming = false; list[i].outEarly = true }
        guard list != episodes[t] else { return }
        episodes[t] = list
        SeasonCache.put(title.id, t, list)
    }

    /// Every other season in the background, nearest to the one on show first, so a season switch is instant.
    func lookAhead() async {
        guard !lookedAhead else { return }
        lookedAhead = true
        // Nearest season first, so the likely next switch is already in; the specials come after them.
        let here = tab?.seasonNumber ?? 1
        let rest = tabs.map(\.tab).filter { $0 != tab }.sorted { a, b in
            switch (a.seasonNumber, b.seasonNumber) {
            case (nil, _): return false
            case (_, nil): return true
            case (let x?, let y?): return abs(x - here) < abs(y - here)
            }
        }
        for t in rest where episodes[t] == nil {
            await loadEpisodes(t)
        }
    }

    private func prefetchStills(_ eps: [Episode], first: Int) {
        var fallbackFetched = false
        for e in eps.sorted(by: { $0.number < $1.number }).prefix(first) {
            if let u = e.still(EpisodeCard.stillSize) {
                ImageStore.shared.prefetch(u, afterTrailer: true)
            } else if !fallbackFetched, let u = episodeFallbackArt(for: e).compactMap({ $0 }).first {
                // No still on TMDB (new episodes often have none yet): the card shows the fallback, fetched once.
                ImageStore.shared.prefetch(u, afterTrailer: true)
                fallbackFetched = true
            }
        }
    }

    /// An episode card's art when its still is missing or won't load: the show's backdrop, then its season's poster,
    /// then the show's poster, so every card shows a picture. A special has no season poster of its own.
    func episodeFallbackArt(for ep: Episode) -> [URL?] {
        let seasonPoster = ep.isSpecial ? nil : (seasonsOut + seasonsAhead).first { $0.number == ep.season }?.posterPath
        return [shown.backdrop("w780"), TMDBImage.url(seasonPoster, "w500"), shown.poster("w500")]
    }

    // MARK: Play words (PlayFlow.playLabel)

    /// A movie's saved spot worth resuming, or a show's in-progress episode.
    var resumeEntry: HistoryEntry? {
        guard let e = history, !e.upNext, e.progress > 0, e.progress < Self.finished else { return nil }
        if isShow && ((e.season ?? 0) <= 0 || (e.episode ?? 0) <= 0) { return nil }
        return e
    }

    /// Play's words. Only the show's own row ever names it: a special plays from its own card, and the show's
    /// position in the seasons is where the viewer really is.
    var playLabel: String {
        if let e = resumeEntry {
            guard isShow else { return "Resume" }
            return "Resume " + (EpisodeWords.code(e.season, e.episode) ?? "S1 E1")
        }
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
        if isShow, let code = EpisodeWords.code(e.season, e.episode) { parts.append(code) }
        if isShow, let name = e.episodeName, !name.isEmpty { parts.append(name) }
        if e.durationMs > 0 {
            let left = (e.durationMs - e.positionMs) / 60_000
            if left > 0 { parts.append("\(left) Min Left") }
        }
        return parts.joined(separator: "  ·  ")
    }

    func request(for ep: Episode, fromStart: Bool = false) -> PlayRequest {
        if ep.isSpecial { return .special(title: shown, imdbId: details?.imdbId, ep, fromStart: fromStart) }
        return PlayRequest(title: shown, imdbId: details?.imdbId, season: ep.season, episode: ep.number, episodeName: ep.name, fromStart: fromStart)
    }

    /// A card's progress (0...1), or nil. A special has its own row, so it is read from there and the show's own
    /// position never bleeds onto a special's card or the other way round.
    func progress(for ep: Episode) -> Double? {
        if ep.isSpecial {
            guard let e = ep.specialIds.compactMap({ startedSpecials[$0] }).first else { return nil }
            return e.progress
        }
        guard let e = resumeEntry, e.season == ep.season, e.episode == ep.number else { return nil }
        return e.progress
    }

    /// A special is never Up Next: nothing follows it.
    func isUpNext(_ ep: Episode) -> Bool {
        guard let e = history, !ep.isSpecial else { return false }
        return e.season == ep.season && e.episode == ep.number
    }

    /// Specials of this show the viewer has started, by source id: each special's own row, read once per refresh.
    var startedSpecials: [String: HistoryEntry] = [:]

    /// Whether the viewer finished this special (its own card's Watched mark).
    func specialWatched(_ ep: Episode) -> Bool {
        WatchedLedger.shared.specialWatched(show: title.id, anyOf: ep.specialIds)
    }

    // MARK: Language pills (LanguagePills)

    /// For a title in another language than the viewer's: "English Dub Available", or its audio and whether subtitles in
    /// their language exist, beside the status pill. Empty for their own language or while nothing is known.
    var languagePills: [LanguagePills.Pill] = []

    /// The viewer's audio language as Dream Core reads release names for it.
    private var want: String { Labels.normLang(AnimeDubs.want) ?? AnimeDubs.want }

    /// The saved answer for what Play plays (no network), so the page opens with it.
    func showSavedLanguagePills() {
        if let p = LanguagePills.pills(AnimeDubs.key(playRequest), original: shown.lang, want: want), p != languagePills { languagePills = p }
    }

    /// Refreshes behind the saved answer (fire-home LangPills.lookup): the quick check (the availability survey, which
    /// records the names) when nothing is known or it's older than 6 hours, then the subtitle index when the subtitles
    /// pill needs it. Nothing known still shows nothing.
    func loadLanguagePills() async {
        let r = playRequest, want = want, key = AnimeDubs.key(r)
        showSavedLanguagePills()
        guard let orig = LanguagePills.original(shown.lang) else { return }
        if orig == want { if !languagePills.isEmpty { languagePills = [] }; return }
        if LanguagePills.needsRefresh(key, want: want) { _ = await Services.shared.playback.survey(r) }
        LanguagePills.noteTitle(key, want: want, original: orig, spoken: details?.info?.spoken ?? [])
        LanguagePills.noteViewerSubs(SubtitleIndex.viewerLanguages())
        if LanguagePills.subtitleToCheck(key, original: orig, want: want), let index = await SubtitleIndex.languages(for: r) {
            LanguagePills.noteIndex(key, index)
        }
        let p = LanguagePills.pills(key, original: orig, want: want) ?? []
        if p != languagePills { languagePills = p }
    }

    /// An episode row's compact words ("English Dub", "Japanese Audio"), once Dream Core has read that episode's copies.
    func languageWords(for ep: Episode) -> String? {
        LanguagePills.episodeWords(AnimeDubs.key(request(for: ep)), original: shown.lang, want: want)
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

    /// Where to Watch names, never YouTube (YouTube TV allowed).
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

    static func get(_ show: Int, _ tab: EpisodeTab) -> [Episode]? { store["\(show):\(tab.id)"]?.episodes }

    static func fresh(_ show: Int, _ tab: EpisodeTab) -> Bool {
        guard let at = store["\(show):\(tab.id)"]?.at else { return false }
        return Date().timeIntervalSince(at) < freshFor
    }

    static func put(_ show: Int, _ tab: EpisodeTab, _ episodes: [Episode]) {
        if store.count > 400 { store.removeAll() }
        store["\(show):\(tab.id)"] = (Date(), episodes)
    }
}
