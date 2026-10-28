import Foundation
import DreamCore

/// Is it out yet? (Availability.kt) For new and upcoming titles only (STANDARDS §5): movies released in the last ~90 days
/// or upcoming; shows premiered in the last ~12 months or not yet (their latest episode while it aired in the last
/// 21 days, else their premiere). Unreleased titles are answered locally; the rest ask the scrapers' index
/// (`PlaybackService.survey`, else DreamContent reads `SourceService.find` names itself). Asked by cards on screen
/// and ahead of the screen (`ahead`: a page's cards in row order, at background priority, holding off while a Play
/// runs), cached on disk (Available Now 24 h, anything else 1 h, sooner after a known release instant). A stale answer
/// keeps showing while it's checked again; a failed check, or a lower answer than a recent one, never takes a pill away.
actor AvailabilityChecker {
    static let shared = AvailabilityChecker()

    enum Status: String, Codable { case available, cam, none }
    struct Entry: Codable {
        var status: Status
        var best: String?
        var note: String?
        var checkedAt: Date
        /// A known release instant ahead (or the day's start).
        var releaseAt: Date?
        /// The movie's (or the episode's) date when checked: a launch shows only answers still in the pill window.
        var date: String? = nil
    }

    /// A brand-new show's pill target, kept between launches so its card shows the answer on the first frame.
    struct ShowRef: Codable {
        /// The checked episode's key; nil: the show has no pill now.
        var key: String?
        var releaseAt: Date?
        /// The episode's (or the premiere's) air day.
        var date: String
        /// The show's premiere.
        var first: String
        var at: Date
    }

    /// "availability2": the first store held "none" answers that were really failed checks (2026-10-28); they're gone.
    private var cache: [String: Entry] = DreamStore.shared.get([String: Entry].self, "availability2") ?? [:]
    private var showRefs: [String: ShowRef] = DreamStore.shared.get([String: ShowRef].self, "availabilityShows") ?? [:]
    private var running: [String: Task<Entry?, Never>] = [:]
    private var failedAt: [String: Date] = [:]
    /// A lower answer than a recent one, not taken: the quick check asks two providers, one missing a release isn't news.
    private var heldAt: [String: Date] = [:]
    private var shows: [Int: (Date, Target?)] = [:]

    static let recentDays = 90, newShowDays = 365, episodeDays = 21

    struct Target: Sendable {
        let key: String
        let request: PlayRequest
        let releaseAt: Date?
        let date: String
    }

    // MARK: public

    /// What a card shows now, without waiting for the network: the local answer for an unreleased title or the cached one.
    func peek(_ t: Title) -> Availability? {
        if t.kind == .tv { return showPeek(t.id, first: t.date) ?? nil }
        guard let target = Self.cardTarget(t) else { return nil }
        if let local = Self.localEntry(target) { return Self.state(local) }
        return cache[target.key].map(Self.state)
    }

    /// The card / title page answer (fetches when stale). nil: no pill for this title.
    func detail(_ t: Title) async -> AvailabilityDetail? {
        // (Instant Play is NOT started here: every card's pill asks this, and it flooded the Play lane with look-aheads
        // for whole rows and search results, so a real Play failed. The title page starts it: TitleDetailsModel.load.)
        var target = Self.cardTarget(t)
        if target == nil && t.kind == .tv { target = await showTarget(t) }
        // STANDARDS §5: a movie counts as new for ~90 days after its digital OR theatrical release. A film in theaters
        // months ago but digital only recently (Spider-Man: Brand New Day: Jul 29 / Oct 6) keeps its pill.
        if target == nil, t.kind == .movie, t.date.count == 10, t.date >= DreamTime.ymdPlus(-Self.digitalLookback),
           let digital = await Self.digitalDate(t.id), digital >= DreamTime.ymdPlus(-Self.recentDays) {
            target = Target(key: "movie:\(t.id)", request: PlayRequest(title: t), releaseAt: nil, date: t.date)
        }
        guard let target else { return nil }
        return await want(target).map(Self.detail)
    }

    /// How far back a movie's main date may be and still have a recent digital release (theaters -> digital usually
    /// takes 17-120 days).
    static let digitalLookback = 240

    func episode(_ e: Episode, show: Title) async -> AvailabilityDetail? {
        guard e.upcoming || Self.recentEpisode(at: e.airsAt, date: e.airDate) else { return nil }
        let t = Target(key: "tv:\(show.id):\(e.season):\(e.number)",
                       request: PlayRequest(title: show, season: e.season, episode: e.number, episodeName: e.name), releaseAt: e.airsAt, date: e.airDate)
        return await want(t).map(Self.detail)
    }

    /// Every kept card answer still in its pill window, by title key (the cards' memory at launch).
    func known() -> [String: Availability] {
        var out: [String: Availability] = [:]
        let oldest = DreamTime.ymdPlus(-Self.recentDays)
        for (k, e) in cache where k.hasPrefix("movie:") {
            if let d = e.date, d < oldest { continue }
            out[k] = Self.state(e)
        }
        for (id, ref) in showRefs {
            guard let n = Int(id), let a = showPeek(n, first: ref.first), let a else { continue }
            out["tv:\(id)"] = a
        }
        return out
    }

    /// The title can wear a pill at all (the card targets' windows; a show's pill also needs a recent episode).
    nonisolated static func mayHavePill(_ t: Title) -> Bool {
        guard t.date.count == 10 else { return false }
        return t.date >= DreamTime.ymdPlus(t.kind == .movie ? -digitalLookback : -newShowDays)
    }

    // MARK: ahead of the screen

    typealias Answer = @Sendable (String, Availability?) async -> Void
    /// Titles waiting, in the order asked (a page's rows top first), and who wants each answer.
    private var aheadOrder: [String] = []
    private var aheadTitles: [String: (title: Title, tries: Int, notBefore: Date)] = [:]
    private var aheadAnswers: [String: [Answer]] = [:]
    private var aheadWorkers = 0
    /// Look-ahead checks in the gate now.
    private var aheadChecking = 0
    /// No look-ahead check starts before this (a Play pressed, the player up).
    private var holdUntil = Date.distantPast
    private var watching = false
    static let aheadParallel = 2, aheadMax = 400

    /// Checks [titles] ahead of the screen, in order, two at a time, and only while the cards on screen leave room
    /// (`CheckGate.load`) and no Play runs. Answers known now (unreleased, fresh) come back at once; a check that
    /// gets no answer is tried again later (up to 3 times) and never reported.
    func ahead(_ titles: [Title], answer: @escaping Answer) async {
        watchPlays()
        var now: [(String, Availability?)] = []
        for t in titles {
            guard Self.mayHavePill(t) else { now.append((t.key, nil)); continue }
            if let a = freshAnswer(t) { now.append((t.key, a)); continue }
            if aheadTitles[t.key] == nil {
                guard aheadOrder.count < Self.aheadMax else { continue }
                aheadTitles[t.key] = (t, 0, .distantPast)
                aheadOrder.append(t.key)
            }
            aheadAnswers[t.key, default: []].append(answer)
        }
        while aheadWorkers < Self.aheadParallel && !aheadOrder.isEmpty {
            aheadWorkers += 1
            Task(priority: .background) { await self.aheadWork() }
        }
        for (k, a) in now { await answer(k, a) }
    }

    private func aheadWork() async {
        while !aheadOrder.isEmpty {
            let now = Date()
            guard let i = aheadOrder.firstIndex(where: { (aheadTitles[$0]?.notBefore ?? .distantPast) <= now }) else {
                // Only retries left, not due yet.
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                continue
            }
            let key = aheadOrder.remove(at: i)
            guard let item = aheadTitles[key] else { continue }
            // A card on screen may have answered it meanwhile.
            if let a = freshAnswer(item.title) { await deliver(key, a); continue }
            await room()
            aheadChecking += 1
            _ = await detail(item.title)
            aheadChecking -= 1
            if let a = freshAnswer(item.title) {
                await deliver(key, a)
            } else if item.tries < 3 {
                // No answer (the scrapers starting, busy): asked again after the 90 s retry window.
                aheadTitles[key] = (item.title, item.tries + 1, Date().addingTimeInterval(95))
                aheadOrder.append(key)
            } else {
                aheadTitles[key] = nil
                aheadAnswers[key] = nil
            }
        }
        aheadWorkers -= 1
    }

    private func deliver(_ key: String, _ a: Availability?) async {
        aheadTitles[key] = nil
        let calls = aheadAnswers.removeValue(forKey: key) ?? []
        for f in calls { await f(key, a) }
    }

    /// Waits while a Play runs or anything else is using the checks (the cards on screen, a title page: they always
    /// go first and get the scrapers' background lane to themselves).
    private func room() async {
        while true {
            let others = await CheckGate.shared.load() - aheadChecking
            if others <= 0 && aheadChecking < Self.aheadParallel && Date() >= holdUntil { return }
            try? await Task.sleep(nanoseconds: 400_000_000)
        }
    }

    func hold(_ seconds: TimeInterval, replace: Bool = false) {
        let until = Date().addingTimeInterval(seconds)
        holdUntil = replace ? until : max(holdUntil, until)
    }

    /// A Play pressed holds look-ahead checks for 2 minutes, the player's reports keep holding them, its close lets
    /// them go 3 s later.
    private func watchPlays() {
        guard !watching else { return }
        watching = true
        let nc = NotificationCenter.default
        _ = nc.addObserver(forName: .dreamPlayStarting, object: nil, queue: nil) { _ in Task { await AvailabilityChecker.shared.hold(120) } }
        _ = nc.addObserver(forName: .dreamPlayerReport, object: nil, queue: nil) { _ in Task { await AvailabilityChecker.shared.hold(30) } }
        _ = nc.addObserver(forName: .dreamPlayerClosed, object: nil, queue: nil) { _ in Task { await AvailabilityChecker.shared.hold(3, replace: true) } }
    }

    /// The answer when no check is due (no pill, unreleased, or fresh); nil: a check is due.
    private func freshAnswer(_ t: Title) -> Availability?? {
        guard Self.mayHavePill(t) else { return .some(nil) }
        if t.kind == .movie {
            guard let target = Self.cardTarget(t) else { return .some(nil) }
            if let local = Self.localEntry(target) { return .some(Self.state(local)) }
            if let e = cache[target.key], fresh(e, target.key) { return .some(Self.state(e)) }
            return nil
        }
        // A show: its target found within the hour (showTarget's own memory).
        guard let ref = showRefs[String(t.id)], Date().timeIntervalSince(ref.at) < 3600 else { return nil }
        guard let key = ref.key else { return .some(nil) }
        if let local = Self.localEntry(releaseAt: ref.releaseAt, date: ref.date) { return .some(Self.state(local)) }
        if let e = cache[key], fresh(e, key) { return .some(Self.state(e)) }
        return nil
    }

    /// A show's answer from its kept target: nil = not known yet, .some(nil) = no pill.
    private func showPeek(_ id: Int, first: String) -> Availability?? {
        guard first.count == 10, first >= DreamTime.ymdPlus(-Self.newShowDays) else { return .some(nil) }
        guard let ref = showRefs[String(id)] else { return nil }
        guard let key = ref.key else { return .some(nil) }
        if let local = Self.localEntry(releaseAt: ref.releaseAt, date: ref.date) { return .some(Self.state(local)) }
        // An aired episode past its 21 days: the next ask finds the show's new target (or none).
        guard Self.recentEpisode(at: ref.releaseAt, date: ref.date) else { return .some(nil) }
        guard let e = cache[key] else { return nil }
        return .some(Self.state(e))
    }

    // MARK: targets

    static func cardTarget(_ t: Title) -> Target? {
        guard t.kind == .movie, t.date.count == 10, t.date >= DreamTime.ymdPlus(-recentDays) else { return nil }
        return Target(key: "movie:\(t.id)", request: PlayRequest(title: t), releaseAt: nil, date: t.date)
    }

    static func recentEpisode(at: Date?, date: String, now: Date = Date()) -> Bool {
        if let at { return at <= now && now.timeIntervalSince(at) <= Double(episodeDays) * 86_400 }
        if date.count == 10 { return date <= DreamTime.ymdPlus(0) && date >= DreamTime.ymdPlus(-episodeDays) }
        return false
    }

    /// A brand-new show's pill: its latest episode while it aired in the last 21 days; not premiered yet: its premiere.
    /// TMDB not answering: the target kept from last time (a failed lookup never takes a pill away).
    private func showTarget(_ t: Title) async -> Target? {
        guard t.date.count == 10, t.date >= DreamTime.ymdPlus(-Self.newShowDays) else { return nil }
        if let s = shows[t.id], Date().timeIntervalSince(s.0) < 3600 { return s.1 }
        guard let d = try? await Tmdb.object("/tv/\(t.id)", "&append_to_response=external_ids") else {
            guard let ref = showRefs[String(t.id)], let key = ref.key else { return nil }
            let p = key.split(separator: ":").compactMap { Int($0) }
            guard p.count == 3 else { return nil }
            return Target(key: key, request: PlayRequest(title: t, season: p[1], episode: p[2]), releaseAt: ref.releaseAt, date: ref.date)
        }
        let air = await AirTimes.info(t.id)
        let imdb = Tmdb.str(d["external_ids"] as? [String: Any], "imdb_id")
        let first = Tmdb.str(d, "first_air_date").count == 10 ? Tmdb.str(d, "first_air_date") : t.date
        var target: Target?
        func ep(_ o: Any?) -> (Int, Int, String, Date?)? {
            guard let o = o as? [String: Any] else { return nil }
            let date = Tmdb.str(o, "air_date"); guard date.count == 10 else { return nil }
            return (o["season_number"] as? Int ?? 0, o["episode_number"] as? Int ?? 0, date, nil)
        }
        func req(_ s: Int, _ e: Int) -> PlayRequest { PlayRequest(title: t, imdbId: imdb.hasPrefix("tt") ? imdb : nil, season: s, episode: e) }
        if first <= DreamTime.ymdPlus(0) {
            if var last = ep(d["last_episode_to_air"]) {
                if let p = air?.prev, p.0 == last.0, p.1 == last.1 { last.3 = p.2 }
                if Self.recentEpisode(at: last.3, date: last.2) {
                    target = Target(key: "tv:\(t.id):\(last.0):\(last.1)", request: req(last.0, last.1), releaseAt: last.3, date: last.2)
                }
            }
        } else if var next = ep(d["next_episode_to_air"]) {
            if let n = air?.next, n.0 == next.0, n.1 == next.1 { next.3 = n.2 }
            target = Target(key: "tv:\(t.id):\(next.0):\(next.1)", request: req(next.0, next.1), releaseAt: next.3, date: next.2)
        } else {
            let today = DreamTime.ymdPlus(0)
            let s = (d["seasons"] as? [[String: Any]] ?? []).filter { Tmdb.str($0, "air_date").count == 10 && Tmdb.str($0, "air_date") > today }
                .min { ($0["season_number"] as? Int ?? 0) < ($1["season_number"] as? Int ?? 0) }
            if let s { let n = s["season_number"] as? Int ?? 1
                target = Target(key: "tv:\(t.id):\(n):1", request: req(n, 1), releaseAt: nil, date: Tmdb.str(s, "air_date")) }
        }
        shows[t.id] = (Date(), target)
        let old = showRefs[String(t.id)]
        let ref = ShowRef(key: target?.key, releaseAt: target?.releaseAt, date: target?.date ?? "", first: first, at: Date())
        showRefs[String(t.id)] = ref
        // Written when the target changed (the time alone is kept in memory until the next answer's save).
        if old?.key != ref.key || old?.releaseAt != ref.releaseAt || old?.date != ref.date { saveShows() }
        return target
    }

    // MARK: checking

    /// Not out yet: answered here, no network.
    static func localEntry(_ t: Target, now: Date = Date()) -> Entry? { localEntry(releaseAt: t.releaseAt, date: t.date, now: now) }

    static func localEntry(releaseAt: Date?, date: String, now: Date = Date()) -> Entry? {
        let ahead: Bool
        if let at = releaseAt { ahead = at > now } else { ahead = date.count == 10 && date > DreamTime.ymdPlus(0) }
        guard ahead else { return nil }
        let note = "Coming " + (releaseAt.map { DreamTime.airLabel($0) } ?? DreamTime.dateWords(date))
        return Entry(status: .none, best: nil, note: note, checkedAt: now, releaseAt: releaseAt ?? DreamTime.midnight(date), date: date)
    }

    /// Available Now lives a day; CAM Only and Not Available Yet an hour (a release can land any time); anything
    /// waiting on a release instant goes stale the moment it passes. A held lower answer counts as fresh for an hour.
    private func fresh(_ e: Entry, _ key: String? = nil, now: Date = Date()) -> Bool {
        if let r = e.releaseAt, r > e.checkedAt, r <= now { return false }
        if let key, let h = heldAt[key], now.timeIntervalSince(h) < 3600 { return true }
        let ttl: TimeInterval = e.status == .available ? 86_400 : 3600
        return now.timeIntervalSince(e.checkedAt) < ttl
    }

    private static func rank(_ s: Status) -> Int { s == .available ? 2 : (s == .cam ? 1 : 0) }

    private func want(_ t: Target) async -> Entry? {
        if let local = Self.localEntry(t) { cache[t.key] = local; return local }
        if let e = cache[t.key], fresh(e, t.key) { return e }
        // No answer last time (the scrapers still starting, busy, or slow): no pill, asked again after 90 s while the
        // card is on screen. A failed check is never shown as "Not Available Yet".
        if let f = failedAt[t.key], Date().timeIntervalSince(f) < 90 { return cache[t.key] }
        if let r = running[t.key] { return await r.value ?? cache[t.key] }
        let task = Task<Entry?, Never> {
            // Quick checks a few at a time, the newest ask first (the cards on screen now), never a flood.
            guard await CheckGate.shared.enter(t.key) else { _ = await CheckGate.shared.wasDropped(t.key); return nil }
            let e = await Self.check(t)
            await CheckGate.shared.leave()
            return self.settle(t, e)
        }
        running[t.key] = task
        let e = await task.value
        running[t.key] = nil
        return e ?? cache[t.key]
    }

    /// A check's answer into the cache (and every caller waiting on it). nil: no answer, the kept one stands.
    private func settle(_ t: Target, _ got: Entry?) -> Entry? {
        guard var e = got else { failedAt[t.key] = Date(); return nil }
        e.date = t.date
        failedAt[t.key] = nil
        if let old = cache[t.key], Self.rank(e.status) < Self.rank(old.status), Date().timeIntervalSince(old.checkedAt) < 72 * 3600 {
            // Lower than a recent answer (Available Now, then nothing): the quick check missing it, the shown pill
            // stays. A real change still shows once the old answer is 3 days old.
            heldAt[t.key] = Date()
            return old
        }
        heldAt[t.key] = nil
        // Gaps worker hook: a cached answer flipped to Available Now (the app's "Now Available" notice for My List movies).
        if let old = cache[t.key], old.status != .available, e.status == .available { AvailabilityNews.nowAvailable(t.request.title, best: e.best) }
        cache[t.key] = e; save()
        return e
    }

    private func save() {
        if cache.count > 3000 { cache = Dictionary(uniqueKeysWithValues: cache.sorted { $0.value.checkedAt > $1.value.checkedAt }.prefix(2500).map { ($0.key, $0.value) }) }
        DreamStore.shared.set(cache, "availability2")
    }

    private func saveShows() {
        if showRefs.count > 1500 { showRefs = Dictionary(uniqueKeysWithValues: showRefs.sorted { $0.value.at > $1.value.at }.prefix(1200).map { ($0.key, $0.value) }) }
        DreamStore.shared.set(showRefs, "availabilityShows")
    }

    private static func check(_ t: Target) async -> Entry? {
        guard let sv = await survey(t.request) else { return nil }
        let status: Status = sv.quality > 0 ? .available : (sv.cam > 0 ? .cam : .none)
        var note: String?
        var releaseAt: Date?
        if status != .available && t.request.season == nil {
            let digital = await digitalDate(t.request.title.id)
            let date = t.date
            let today = DreamTime.ymdPlus(0)
            if let digital, digital > today { releaseAt = DreamTime.midnight(digital); note = "Digital Release \(DreamTime.dateWords(digital))" }
            else if date.count == 10 && date > today { releaseAt = DreamTime.midnight(date); note = "Coming \(DreamTime.dateWords(date))" }
            else if date.count == 10 && date >= DreamTime.ymdPlus(-120) && digital == nil { note = "In Theaters Now" }
        }
        return Entry(status: status, best: status == .available ? sv.best : sv.bestCam, note: note, checkedAt: Date(), releaseAt: releaseAt)
    }

    /// Dream Core's survey when the playback module offers one, else the scrapers' quick check read here. nil = no
    /// answer (scrapers busy, failed or not running): no pill, asked again later.
    private static func survey(_ r: PlayRequest) async -> SourceSurvey? {
        if let s = await Services.shared.playback.survey(r) { return s }
        guard await Services.shared.sources.isActive() else { return nil }
        guard let all = await Services.shared.sources.quick(r) else { return nil }
        if all.isEmpty { return SourceSurvey(total: 0, quality: 0, cam: 0) }
        let cams = all.filter { isCam($0.torrentName + " " + ($0.fileName ?? "")) }
        let good = all.filter { c in !cams.contains { $0.id == c.id } }
        func qi(_ q: String) -> Int { ["4K": 0, "1080p": 1, "720p": 2][q] ?? 3 }
        let top = good.min { (qi($0.quality), -$0.seeders) < (qi($1.quality), -$1.seeders) }
        let topCam = cams.min { (qi($0.quality), -$0.seeders) < (qi($1.quality), -$1.seeders) }
        return SourceSurvey(total: all.count, quality: good.count, cam: cams.count,
                            best: top.map { [$0.quality, source($0.torrentName)].compactMap { $0 }.joined(separator: " ") },
                            bestCam: topCam.map { camTag($0.torrentName) + " " + $0.quality })
    }

    private static func has(_ t: String, _ words: [String]) -> Bool {
        let u = t.uppercased()
        return words.contains { u.range(of: "(?<![A-Z0-9])\(NSRegularExpression.escapedPattern(for: $0))(?![A-Z0-9])", options: .regularExpression) != nil }
    }
    static func isCam(_ t: String) -> Bool {
        has(t, ["CAM", "HQCAM", "CAMRIP", "CAM-RIP", "HDCAM", "HDTS", "TS", "TELESYNC", "HDTC", "TC", "TELECINE", "DVDSCR", "SCREENER", "PDVD", "PREDVD", "HDTSRIP"])
    }
    private static func source(_ t: String) -> String? {
        if has(t, ["REMUX"]) { return "Remux" }
        if has(t, ["BLURAY", "BLU-RAY", "BDRIP", "BRRIP"]) { return "BluRay" }
        if has(t, ["WEB-DL", "WEBDL"]) { return "WEB-DL" }
        if has(t, ["WEBRIP", "WEB-RIP"]) { return "WEBRip" }
        if has(t, ["WEB"]) { return "WEB" }
        if has(t, ["HDTV"]) { return "HDTV" }
        if has(t, ["DVDRIP"]) { return "DVDRip" }
        return nil
    }
    private static func camTag(_ t: String) -> String {
        ["HDCAM", "CAMRIP", "CAM", "HDTS", "TELESYNC", "TS", "HDTC", "TELECINE", "TC", "DVDSCR", "SCREENER", "SCR", "PDVD", "PREDVD"].first { has(t, [$0]) } ?? "CAM"
    }

    /// The movie's US digital release day (TMDB release_dates type 4), else the earliest anywhere.
    private static func digitalDate(_ id: Int) async -> String? {
        guard let o = try? await Tmdb.object("/movie/\(id)/release_dates") else { return nil }
        let all = o["results"] as? [[String: Any]] ?? []
        func digital(_ r: [String: Any]?) -> String? {
            (r?["release_dates"] as? [[String: Any]] ?? []).filter { $0["type"] as? Int == 4 }.map { String(Tmdb.str($0, "release_date").prefix(10)) }.min()
        }
        return digital(all.first { $0["iso_3166_1"] as? String == "US" }) ?? all.compactMap { digital($0) }.min()
    }

    // MARK: mapping (one fixed card label per state)

    static func state(_ e: Entry) -> Availability {
        switch e.status {
        case .available: return .availableNow
        case .cam: return .camOnly
        case .none:
            if let r = e.releaseAt, r > Date() { return .coming(DreamTime.ymd(r)) }
            if e.note == "In Theaters Now" { return .notAvailableYet }
            if e.releaseAt == nil, e.note?.hasPrefix("Coming") == true { return .comingSoon }
            return .notAvailableYet
        }
    }

    static func detail(_ e: Entry) -> AvailabilityDetail { AvailabilityDetail(state: state(e), best: e.best, note: e.note) }
}

/// The quick checks' gate (Availability.kt WORKERS / GAP_MS / MAX_QUEUE): two at once, starts at least 0.4 s apart,
/// the newest ask first (cards on screen now beat ones scrolled past), at most 150 waiting (the oldest dropped; a
/// dropped title is asked again the next time its card shows).
actor CheckGate {
    static let shared = CheckGate()
    private let workers = 4, maxWaiting = 150  // the TV ran 2; the scrapers' background lane runs 2 checks at once, 2 more wait at the slot
    private let gap: TimeInterval = 0.02
    private var running = 0
    private var waiting: [(key: String, go: CheckedContinuation<Bool, Never>)] = []
    private var nextStart = Date.distantPast
    private var dropped: Set<String> = []

    func enter(_ key: String) async -> Bool {
        if running < workers && waiting.isEmpty {
            running += 1
        } else {
            let go = await withCheckedContinuation { (c: CheckedContinuation<Bool, Never>) in
                waiting.append((key, c))
                if waiting.count > maxWaiting { let old = waiting.removeFirst(); dropped.insert(old.key); old.go.resume(returning: false) }
            }
            guard go else { return false }
        }
        let now = Date()
        let at = max(now, nextStart)
        nextStart = at.addingTimeInterval(gap)
        if at > now { try? await Task.sleep(nanoseconds: UInt64(at.timeIntervalSince(now) * 1_000_000_000)) }
        return true
    }

    /// A finished check hands its place to the newest waiting one.
    func leave() {
        if let next = waiting.popLast() { next.go.resume(returning: true) } else { running = max(0, running - 1) }
    }

    func wasDropped(_ key: String) -> Bool { dropped.remove(key) != nil }

    /// Checks running and waiting (the look-ahead starts one only while this is under 2: the cards on screen first).
    func load() -> Int { running + waiting.count }
}

// Instant pills: the catalog's side of the contract (DreamCore/PillsContract.swift).
extension ContentCatalog {
    func availabilityAhead(_ titles: [Title], answer: @escaping @Sendable (String, Availability?) async -> Void) async {
        await AvailabilityChecker.shared.ahead(titles, answer: answer)
    }

    func availabilityKnown() async -> [String: Availability] { await AvailabilityChecker.shared.known() }

    func mayHavePill(_ title: Title) -> Bool { AvailabilityChecker.mayHavePill(title) }
}
