import SwiftUI
import DreamCore
import DreamUI

// Speed pass (docs/port/speed.md): how a page's rows fill (every visible row at once, claims still top-down), the
// pages kept on disk between launches, and the look-ahead (art, Browse's pages, a title on press-down).

// MARK: Filling

/// Fills a page's rows many at once. Each row first fetches without claiming (`CatalogService.warm`), then waits its
/// turn (the rows above it done, at most `Turns.grace`), then fills from the cache and claims its titles. So the page
/// keeps the TV's top-down claims (a title shows in the highest row that wants it) while a slow row never holds up
/// the rows under it for more than a moment.
@MainActor
enum RowFill {
    /// Rows in flight at once: the first screen and the next together, then one more as each lands.
    static let together = 12

    /// Fills `p` (its first `prefix` rows when given). `base` is what shows now (the snapshot): a row not filled yet
    /// keeps its old copy, so the page swaps row by row instead of emptying. `publish` gets the rows to show and how
    /// many fresh rows are in them; `patch` gets pills that answered after their row showed. Returns the rows filled.
    static func run(_ p: CatalogPage, base: [CatalogRow], prefix: Int? = nil, pillWait: Double? = nil,
                    publish: @escaping @MainActor ([CatalogRow], Int) -> Void,
                    patch: @escaping @MainActor @Sendable (String, [String: Availability]) -> Void) async -> Int {
        let c = Services.shared.catalog
        let slots = await c.rowSlots(for: p)
        guard !slots.isEmpty else { return 0 }
        let n = min(prefix ?? slots.count, slots.count)
        let old = Dictionary(base.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        var out = [CatalogRow?](repeating: nil, count: n)
        var resolved = [Bool](repeating: false, count: n)
        var fresh = 0
        var pills = PillAhead.Tally()
        let turns = Turns(count: n)
        func shows(_ r: CatalogRow) -> Bool { !r.items.isEmpty || !r.hubs.isEmpty }
        func compose() -> [CatalogRow] {
            (0..<n).compactMap { resolved[$0] ? out[$0] : old[slots[$0].id] }.filter(shows)
        }
        await withTaskGroup(of: (Int, CatalogRow?).self) { g in
            var next = 0
            func add() {
                let j = next
                next += 1
                let r = slots[j]
                // The first screen waits a moment for its pills; rows further down show at once and get theirs later.
                let wait = pillWait ?? (j < 4 ? 0.35 : 0.1)
                g.addTask {
                    await c.warm(row: r, page: p)
                    await turns.wait(j)
                    let row = try? await c.fill(row: r, page: p)
                    await turns.finish(j)
                    guard let row else { return (j, nil) }
                    let id = row.id
                    return (j, await CatalogPageModel.withPills(row, wait: wait) { pills in await patch(id, pills) })
                }
            }
            while next < min(n, together) { add() }
            for await (j, r) in g {
                out[j] = r
                resolved[j] = true
                if let r, shows(r) { fresh += 1; pills.add(r) }
                publish(compose(), fresh)
                if next < n, !Task.isCancelled { add() }
            }
        }
        pills.log("\(p.name)\(prefix == nil ? "" : " look-ahead")")
        return out.compactMap { $0 }.count
    }
}

/// Row turns for RowFill: row j may claim once rows 0..<j have, or after `grace` (a slow row above loses its titles
/// to the rows under it instead of holding them up; no title still ever shows twice).
actor Turns {
    static let grace: Duration = .milliseconds(350)
    private var done: [Bool]
    private var low = 0
    private var nextId = 0
    private var waiters: [(j: Int, id: Int, k: CheckedContinuation<Void, Never>)] = []

    init(count: Int) { done = Array(repeating: false, count: count) }

    func wait(_ j: Int) async {
        if low >= j { return }
        let id = nextId
        nextId += 1
        await withCheckedContinuation { (k: CheckedContinuation<Void, Never>) in
            waiters.append((j, id, k))
            Task { [weak self] in
                try? await Task.sleep(for: Self.grace)
                await self?.expire(id)
            }
        }
    }

    private func expire(_ id: Int) {
        if let i = waiters.firstIndex(where: { $0.id == id }) { waiters.remove(at: i).k.resume() }
    }

    func finish(_ j: Int) {
        guard done.indices.contains(j) else { return }
        done[j] = true
        while low < done.count && done[low] { low += 1 }
        let go = waiters.filter { $0.j <= low }
        waiters.removeAll { $0.j <= low }
        for w in go { w.k.resume() }
    }
}

// MARK: Pages kept between launches

/// A page's last rows and hero.
struct PageSnapshot: Codable, Sendable {
    var hero: [CatalogItem]
    var rows: [CatalogRow]
    var at: Date
    /// False: only the first rows (Browse's look-ahead).
    var complete: Bool
    /// The profile's kids settings when it was saved (`PageSnapshots.fingerprint`): rows saved under another limit
    /// are dropped before anything else happens.
    var limits: String? = nil
}

/// Every page's last rows and hero, per profile, in memory and on disk (DreamStore), so a page shows on its first
/// frame and the app's relaunch opens on a full Home. A week old at most; the page fills again behind it anyway.
@MainActor
enum PageSnapshots {
    private static var memory: [String: PageSnapshot] = [:]
    private static var missing: Set<String> = []
    static let maxAge: TimeInterval = 7 * 86_400
    /// Cards kept per row (a deep row pages on again from there).
    static let maxItems = 24

    private static func name(_ p: CatalogPage) -> String { "page_" + p.storeKey }
    private static func slot(_ p: CatalogPage) -> String { DreamStore.shared.profileId + "|" + name(p) }

    static func get(_ p: CatalogPage) -> PageSnapshot? {
        let k = slot(p)
        if let m = memory[k] { return m }
        if missing.contains(k) { return nil }
        guard let s = DreamStore.shared.get(PageSnapshot.self, name(p), perProfile: true), Date().timeIntervalSince(s.at) < maxAge else {
            missing.insert(k)
            return nil
        }
        let c = cleaned(s, p)
        memory[k] = c
        return c
    }

    /// A snapshot saved before Browse pages lost their in-row Spotlight cards (owner, 2026-10-28) never brings
    /// them back: only Home and seasonal hubs keep spotlight rows.
    private static func cleaned(_ s: PageSnapshot, _ p: CatalogPage) -> PageSnapshot {
        guard !p.keepsSpotlight, s.rows.contains(where: { $0.isSpotlight }) else { return s }
        var s = s
        s.rows.removeAll { $0.isSpotlight }
        return s
    }

    /// The current profile's kids settings and Keep Anime in Anime Sections, as snapshots record them (also kept in
    /// `known`). The anime part is only there when it's off, so rows saved before the setting existed stay usable.
    static func fingerprint() async -> String {
        var f: String
        if let p = await Services.shared.profiles.current() {
            f = "\(p.id)|\(p.isKids)|\(p.ratingLimit ?? "")|\(p.kidsLevel.map(String.init) ?? "")"
        } else { f = "-" }
        if !AnimePrefs.apart { f += "|anime" }
        known = f
        return f
    }

    /// The last fingerprint seen this launch; a snapshot saved under different settings isn't shown at all.
    static var known: String?

    /// A snapshot fit to show now: saved under the settings last seen (or none seen yet this launch).
    static func usable(_ s: PageSnapshot) -> Bool { known == nil || s.limits == known }

    /// Forgets `p`'s snapshot (its rows were saved under other kids settings).
    static func drop(_ p: CatalogPage) {
        let k = slot(p)
        memory[k] = nil
        missing.insert(k)
        let n = name(p)
        Task.detached(priority: .utility) { DreamStore.shared.set(Optional<PageSnapshot>.none, n, perProfile: true) }
    }

    static func put(_ p: CatalogPage, hero: [CatalogItem], rows: [CatalogRow], complete: Bool, limits: String? = nil) {
        var trimmed = rows.filter { p.keepsSpotlight || !$0.isSpotlight }.map { r -> CatalogRow in var r = r; if r.items.count > maxItems { r.items = Array(r.items.prefix(maxItems)) }; return r }
        // Each card keeps its newest pill answer (the cards' memory is newer than what the row carried).
        var hero = hero
        _ = pilled(&hero)
        for i in trimmed.indices { _ = pilled(&trimmed[i].items) }
        let s = PageSnapshot(hero: hero, rows: trimmed, at: Date(), complete: complete, limits: limits)
        let k = slot(p)
        memory[k] = s
        missing.remove(k)
        let n = name(p)
        Task.detached(priority: .utility) { DreamStore.shared.set(s, n, perProfile: true) }
    }

    /// Puts the cards' newest pill answers (CardPills memory) on `items`; true when one changed.
    static func pilled(_ items: inout [CatalogItem]) -> Bool {
        var changed = false
        for i in items.indices where items[i].episode == nil && items[i].progress == nil {
            if let m = CardPills.known(items[i].title.key), m != items[i].availability { items[i].availability = m; changed = true }
        }
        return changed
    }

    /// The current profile's saved pages take the pills answered since they were saved (the checks ahead of the
    /// screen land over minutes), so the next launch shows them all on the first frame.
    static func refreshPills() {
        let me = DreamStore.shared.profileId + "|"
        for (k, s) in memory where k.hasPrefix(me) {
            var s = s
            var changed = pilled(&s.hero)
            for i in s.rows.indices where pilled(&s.rows[i].items) { changed = true }
            guard changed else { continue }
            memory[k] = s
            let n = String(k.dropFirst(me.count))
            let saved = s
            Task.detached(priority: .utility) { DreamStore.shared.set(saved, n, perProfile: true) }
        }
    }

    /// Reads `pages`' snapshots off the main thread, so opening them later decodes nothing.
    static func preload(_ pages: [CatalogPage]) async {
        let want = pages.filter { memory[slot($0)] == nil && !missing.contains(slot($0)) }
        guard !want.isEmpty else { return }
        let names = want.map(name)
        let got = await Task.detached(priority: .utility) { names.map { DreamStore.shared.get(PageSnapshot.self, $0, perProfile: true) } }.value
        for (p, s) in zip(want, got) {
            let k = slot(p)
            if let s, Date().timeIntervalSince(s.at) < maxAge { memory[k] = memory[k] ?? cleaned(s, p) } else { missing.insert(k) }
        }
    }
}

extension CatalogPage {
    /// A file-safe name for the page ("genre_Action", "service_Netflix").
    var storeKey: String {
        let raw: String = switch self {
        case .home: "home"
        case .movies: "movies"
        case .shows: "shows"
        case .anime: "anime"
        case .kids: "kids"
        case .service(let s): "service_\(s.name)"
        case .genre(let g): "genre_\(g.id)"
        case .season(let id): "season_\(id)"
        }
        return String(raw.map { $0.isLetter || $0.isNumber ? $0 : "_" })
    }
}

// MARK: Art ahead

/// Art fetched before it shows: the hero's first slides and logos, and the first cards of a page's first rows.
@MainActor
enum ArtAhead {
    /// The URL a card of `row` draws first (the same choice as the cards in DreamUI/Cards.swift).
    static func url(_ item: CatalogItem, in row: CatalogRow) -> URL? {
        let t = item.title
        if row.isSpotlight { return t.backdrop("w1280") ?? t.poster("w500") }
        switch row.style {
        case .poster, .top10: return t.poster("w342") ?? t.backdrop("w780")
        case .big: return item.keyArt("w780") ?? t.backdrop("w780") ?? t.poster("w500")
        case .landscape, .continueWatching:
            if let e = item.episode { return e.still("w780") ?? t.backdrop("w780") ?? t.poster("w342") }
            return item.keyArt("w780") ?? t.backdrop("w780") ?? t.poster("w342")
        case .brand: return nil
        }
    }

    /// The hero and the first `cards` cards of the first `rows` rows, decoded into memory.
    static func page(hero: [CatalogItem], rows: [CatalogRow], rowCount: Int = 8, cards: Int = 5) {
        self.hero(hero)
        for r in rows.prefix(rowCount) {
            for item in r.items.prefix(cards) { ImageStore.shared.prefetch(url(item, in: r)) }
        }
    }

    /// The hero's first two slides and their logos (TitleExtras keeps the logo and scores for HeroLogo).
    static func hero(_ items: [CatalogItem]) {
        for item in items.prefix(2) {
            ImageStore.shared.prefetch(item.title.backdrop("w1280"))
            let t = item.title
            if TitleExtras.shared.cached(t) == nil { Task(priority: .utility) { _ = await TitleExtras.shared.extras(t) } }
        }
        for item in items.dropFirst(2).prefix(4) { ImageStore.shared.warm(item.title.backdrop("w1280")) }
    }
}

// MARK: Title look-ahead (press-down)

/// A title's page fetched the moment its card is pressed, before the push: details (which also bring the logo and
/// scores), the likely season's episodes, and the backdrop. The page then opens with everything there.
@MainActor
enum TitleAhead {
    private static var recent: [String: Date] = [:]

    static func warm(_ t: Title) {
        if let at = recent[t.key], Date().timeIntervalSince(at) < 120 { return }
        if recent.count > 400 { recent.removeAll() }
        recent[t.key] = Date()
        ImageStore.shared.prefetch(t.backdrop("w1280"))
        ImageStore.shared.prefetch(t.poster("w342"))
        let c = Services.shared.catalog
        if t.kind == .tv {
            Task(priority: .userInitiated) {
                let season = max(1, await Services.shared.history.entry(for: t.key)?.season ?? 1)
                guard !SeasonCache.fresh(t.id, season) else { return }
                if let eps = try? await c.episodes(showId: t.id, season: season) { SeasonCache.put(t.id, season, eps) }
            }
        }
        Task(priority: .userInitiated) {
            let t0 = Date()
            _ = await TitleExtras.shared.extras(t)
            Speed.note("look-ahead \(t.name)", t0)
        }
    }
}

// MARK: Browse look-ahead

/// The pages Browse leads to, fetched ahead at low priority: Movies, TV Shows, the first genres and services (their
/// hero and first rows), so a tap shows the page's first screen at once. Stops when the task is cancelled (the viewer
/// left Browse); what already arrived stays.
enum BrowseAhead {
    static func run(genres: Int = 8, services: Int = 4) async {
        let c = Services.shared.catalog
        async let g = c.genres()
        async let s = c.services()
        let pages: [CatalogPage] = [.movies, .shows] + (await g).prefix(genres).map { .genre($0) } + (await s).prefix(services).map { .service($0) }
        await PageSnapshots.preload(pages)
        let t0 = Date()
        // Two pages at a time: each fills its first rows side by side already.
        await withTaskGroup(of: Void.self) { group in
            var it = pages.makeIterator()
            for _ in 0..<2 { if let p = it.next() { group.addTask { await CatalogPageModel.prefetch(p) } } }
            for await _ in group {
                if Task.isCancelled { group.cancelAll(); break }
                if let p = it.next() { group.addTask { await CatalogPageModel.prefetch(p) } }
            }
        }
        Speed.note("Browse look-ahead (\(pages.count) pages)", t0)
    }
}

// MARK: Pills ahead

/// Instant pills (speed.md "Instant pills"): every card that can wear a pill is checked ahead of the screen, in row
/// order, at background priority (`CatalogService.availabilityAhead`), as each page's rows land (Home at launch,
/// Browse's look-ahead pages, search results). Every answer goes into the cards' memory (CardPills, read on a card's
/// first frame) and, a moment later, into the saved pages, so a relaunch shows every pill at once.
@MainActor
enum PillAhead {
    /// The stored answers into the cards' memory (launch, before Home's rows draw).
    nonisolated static func seed() {
        Task.detached(priority: .userInitiated) {
            let t0 = Date()
            let known = await Services.shared.catalog.availabilityKnown()
            await MainActor.run { CardPills.seed(known) }
            Speed.note("pills: \(known.count) stored answers in the cards' memory", t0)
        }
    }

    /// Asks for `titles` ahead (in order). `then` also gets each answer (a row waiting for its pills).
    nonisolated static func ask(_ titles: [Title], then: (@Sendable (String, Availability?) async -> Void)? = nil) async {
        guard !titles.isEmpty else { return }
        await Services.shared.catalog.availabilityAhead(titles) { key, a in
            await MainActor.run {
                let before = CardPills.known(key)
                CardPills.remember(key, a)
                if before != .some(a) { changed() }
            }
            await then?(key, a)
        }
    }

    /// The cards of `items` that can have a pill, asked for ahead (personal cards keep what their row sent).
    nonisolated static func ask(items: [CatalogItem]) async {
        await ask(items.filter { $0.episode == nil && $0.progress == nil }.map(\.title))
    }

    private static var refreshing = false

    /// Answers changed: the saved pages take them a few seconds later (one write for a burst of answers).
    private static func changed() {
        guard !refreshing else { return }
        refreshing = true
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(3))
            refreshing = false
            PageSnapshots.refreshPills()
        }
    }

    /// "Speed: pills <page>: a of n cards answered when their row showed": n = cards that can wear a pill, a = the
    /// ones whose answer was there (on the row or in the cards' memory) the moment the row appeared.
    @MainActor struct Tally {
        var cards = 0, answered = 0, rows = 0
        mutating func add(_ r: CatalogRow) {
            let c = Services.shared.catalog
            rows += 1
            for item in r.items where item.episode == nil && item.progress == nil && c.mayHavePill(item.title) {
                cards += 1
                if item.availability != nil || CardPills.known(item.title.key) != nil { answered += 1 }
            }
        }
        func log(_ what: String) {
            guard cards > 0 else { return }
            Log.app.notice("Speed: pills \(what, privacy: .public): \(answered) of \(cards) cards answered when their row showed (\(rows) rows)")
        }
    }
}
