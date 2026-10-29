import SwiftUI
import Observation
import DreamCore
import DreamUI

/// One catalog page's data (CinemaPages.kt CinemaRowsPage): its hero titles, rows, and the genre chips under the hero
/// (Movies / TV Shows: All, Action, Comedy...). Loads when the page opens; rows page on as their end comes into view.
@MainActor @Observable
final class CatalogPageModel {
    enum Phase: Equatable { case loading, ready, failed(String) }

    let page: CatalogPage
    var phase: Phase = .loading
    var hero: [CatalogItem] = []
    var rows: [CatalogRow] = []
    var chips: [Genre] = []
    /// The chip picked (index into `chips`); nil = All.
    var chip: Int?
    var chipRows: [CatalogRow] = []
    var chipPhase: Phase = .ready
    private var paging: Set<String> = []
    private var finished: Set<String> = []
    /// The running fill (`start`), and when the last full fill finished.
    private var loading: Task<Void, Never>?
    private var filledAt: Date?
    /// The kids settings the rows showing were saved under (nil: nothing from a snapshot showing).
    private var snapLimits: String??

    /// Speed pass: the page's last rows and hero (PageSnapshots, saved on disk) show on the first frame, then the page
    /// fills again behind them and swaps each row in place.
    init(page: CatalogPage) {
        self.page = page
        if let snap = PageSnapshots.get(page), PageSnapshots.usable(snap) {
            snapLimits = snap.limits
            hero = snap.hero
            rows = snap.rows
            if !snap.rows.isEmpty { phase = .ready }
        }
    }

    /// The rows showing under the chips.
    var shownRows: [CatalogRow] { chip == nil ? rows : chipRows }
    var shownPhase: Phase { chip == nil ? phase : chipPhase }

    /// Fills the page unless a full fill finished in the last 10 minutes. The fill is the model's own task, so leaving
    /// the page for a title page never stops it: coming back finds every row there.
    func start() {
        if loading != nil { return }
        if let at = filledAt, Date().timeIntervalSince(at) < 600 { return }
        if filledAt == nil, !rows.isEmpty {
            // First open with the snapshot showing: its art into memory now.
            ArtAhead.page(hero: hero, rows: rows)
            if page == .home { Speed.noteLaunch("Home showing \(rows.count) rows from disk") }
            var pills = PillAhead.Tally()
            for r in rows { pills.add(r) }
            pills.log("\(page.name) from disk")
        }
        loading = Task { [weak self] in
            await self?.load()
            self?.loading = nil
        }
    }

    /// Returns once the running fill (if any) is done.
    func waitLoaded() async { await loading?.value }

    func load() async {
        let c = Services.shared.catalog
        let t0 = Date()
        // Rows saved under other kids settings (the profile changed since) go before anything else.
        let limits = await PageSnapshots.fingerprint()
        if let saved = snapLimits, saved != limits {
            rows = []; hero = []
            PageSnapshots.drop(page)
        }
        snapLimits = nil
        if rows.isEmpty { phase = .loading }
        // The hero and chips show the moment they arrive, not after the last row.
        let p = page
        let heroTask = Task { [weak self] in
            let h = (try? await c.spotlight(for: p)) ?? []
            guard let self else { return }
            // An empty answer (offline) keeps the hero already showing.
            if !h.isEmpty || self.hero.isEmpty, h != self.hero { self.hero = h }
            if !h.isEmpty { ArtAhead.hero(h); Speed.note("\(p.name) hero", t0) }
            // The hero's slides wear pills too: first in the queue (the hero is the first thing on the page).
            if !h.isEmpty { Task(priority: .background) { await PillAhead.ask(items: Array(h.prefix(8))) } }
        }
        let chipTask = Task { [weak self] in
            let ch = await c.chips(for: p)
            if self?.chips != ch { self?.chips = ch }
        }
        finished = []
        // Rows show as they arrive, top first, each swapping in over the copy from disk.
        var first = true
        let got = await RowFill.run(p, base: rows) { [weak self] r, filled in
            guard let self else { return }
            if self.rows != r { self.rows = r }
            if !r.isEmpty { self.phase = .ready }
            if first, filled > 0 {
                first = false
                Speed.note("\(p.name) first row", t0)
                if p == .home { Speed.noteLaunch("Home first fresh row") }
            }
        } patch: { [weak self] id, pills in
            self?.patch(id, pills, chip: false)
        }
        await heroTask.value
        await chipTask.value
        if got == 0 && rows.isEmpty {
            do {
                rows = try await c.rows(for: page).filter { !$0.items.isEmpty || !$0.hubs.isEmpty }
                phase = .ready
            } catch {
                phase = .failed(Self.words(error))
            }
        } else {
            phase = .ready
        }
        if got > 0 {
            // A fill cut short (its task cancelled) shows what it got but fills again next time.
            if !Task.isCancelled { filledAt = Date() }
            PageSnapshots.put(page, hero: hero, rows: rows, complete: true, limits: limits)
            ArtAhead.page(hero: [], rows: rows)
        }
        Speed.note("\(p.name) all rows (\(rows.count))", t0)
    }

    /// Pills that answered after their row showed, put on the row's cards (rows scrolled to later have them already).
    private func patch(_ rowId: String, _ pills: [String: Availability], chip: Bool) {
        func apply(_ list: inout [CatalogRow]) {
            guard let i = list.firstIndex(where: { $0.id == rowId }) else { return }
            var r = list[i]
            var changed = false
            for j in r.items.indices where r.items[j].availability == nil {
                if let a = pills[r.items[j].id] { r.items[j].availability = a; changed = true }
            }
            if changed { list[i] = r }
        }
        if chip { apply(&chipRows) } else { apply(&rows) }
    }

    /// Fills a page's first `rows` rows and its hero and keeps them as the page's snapshot (Browse's look-ahead: the
    /// page then opens with its first screen already there). Skipped when the page has a recent snapshot.
    private static var prefetching: Set<String> = []

    static func prefetch(_ p: CatalogPage, rows n: Int = 3) async {
        let limits = await PageSnapshots.fingerprint()
        if let s = PageSnapshots.get(p), s.limits == limits, Date().timeIntervalSince(s.at) < 1800, s.rows.count >= n { return }
        if Task.isCancelled || prefetching.contains(p.storeKey) { return }
        prefetching.insert(p.storeKey)
        defer { prefetching.remove(p.storeKey) }
        let t0 = Date()
        let c = Services.shared.catalog
        async let heroAsk = (try? await c.spotlight(for: p)) ?? []
        var got: [CatalogRow] = []
        _ = await RowFill.run(p, base: [], prefix: n, pillWait: 0.5) { r, _ in got = r } patch: { _, _ in }
        let hero = await heroAsk
        if Task.isCancelled && got.isEmpty { return }
        guard !got.isEmpty else { return }
        // A full snapshot saved meanwhile (the page opened) wins.
        if let s = PageSnapshots.get(p), s.complete, s.at > t0 { return }
        PageSnapshots.put(p, hero: hero, rows: got, complete: false, limits: limits)
        ArtAhead.page(hero: hero, rows: got)
        Speed.note("look-ahead \(p.name) (\(got.count) rows)", t0)
    }

    /// A row arrives with its pills already on (owner: pills never load in on screen). Every card that can wear one is
    /// queued for its check ahead of the screen (PillAhead, in row order, background priority; answers still fresh
    /// come back at once, and a card already wearing a stale answer keeps it while it's checked again). The row waits
    /// at most `wait` seconds for the cards with no answer yet; `late` gets those that came after the wait (up to
    /// 8 s), which the page puts on the row. Later answers reach the cards' memory and the saved pages.
    nonisolated static func withPills(_ row: CatalogRow, wait: Double = 0.35,
                                      late: (@Sendable ([String: Availability]) async -> Void)? = nil) async -> CatalogRow {
        let cards = row.items.indices.filter { row.items[$0].episode == nil && row.items[$0].progress == nil }
        guard !cards.isEmpty else { return row }
        var seen = Set<String>()
        let titles = cards.map { row.items[$0].title }.filter { seen.insert($0.key).inserted }
        let want = cards.filter { row.items[$0].availability == nil }
        guard !want.isEmpty else {
            Task(priority: .background) { await PillAhead.ask(titles) }
            return row
        }
        var byKey: [String: [Int]] = [:]
        for i in want { byKey[row.items[i].title.key, default: []].append(i) }
        let slots = byKey
        let box = PillBox(count: want.count)
        await withCheckedContinuation { (k: CheckedContinuation<Void, Never>) in
            let once = Once()
            Task(priority: .background) {
                await PillAhead.ask(titles) { key, a in
                    for i in slots[key] ?? [] where await box.put(i, a) { once.run { k.resume() } }
                }
            }
            Task {
                try? await Task.sleep(for: .seconds(wait))
                once.run { k.resume() }
            }
        }
        var r = row
        let now = await box.all()
        for (i, a) in now { r.items[i].availability = a }
        if let late, await !box.complete {
            let items = row.items
            Task {
                await withCheckedContinuation { (k: CheckedContinuation<Void, Never>) in
                    let once = Once()
                    Task { await box.whenComplete { once.run { k.resume() } } }
                    Task {
                        try? await Task.sleep(for: .seconds(8))
                        once.run { k.resume() }
                    }
                }
                var out: [String: Availability] = [:]
                for (i, a) in await box.all() where now[i] == nil { out[items[i].id] = a }
                if !out.isEmpty { await late(out) }
            }
        }
        return r
    }

    func pick(_ i: Int?) async {
        chip = i
        guard let i, chips.indices.contains(i) else { chipRows = []; return }
        let cp = CatalogPage.genre(chips[i])
        // A chip seen before (or looked ahead) shows its rows from the snapshot at once.
        let snap = PageSnapshots.get(cp)?.rows ?? []
        chipRows = snap
        chipPhase = snap.isEmpty ? .loading : .ready
        let t0 = Date()
        let got = await RowFill.run(cp, base: snap) { [weak self] r, _ in
            guard let self, self.chip == i else { return }
            let shown = r.filter { !$0.items.isEmpty }
            if self.chipRows != shown { self.chipRows = shown }
            if !self.chipRows.isEmpty { self.chipPhase = .ready }
        } patch: { [weak self] id, pills in
            guard let self, self.chip == i else { return }
            self.patch(id, pills, chip: true)
        }
        Speed.note("\(cp.name) chip rows (\(got))", t0)
        guard chip == i else { return }
        if got > 0 { PageSnapshots.put(cp, hero: [], rows: chipRows, complete: true, limits: PageSnapshots.known) }
        if got == 0 {
            do {
                chipRows = try await Services.shared.catalog.rows(for: .genre(chips[i])).filter { !$0.items.isEmpty }
                chipPhase = .ready
            } catch {
                chipPhase = .failed(Self.words(error))
            }
        } else {
            chipPhase = .ready
        }
    }

    /// The next page of `row`'s titles, appended (titles already in the row are skipped).
    func more(_ row: CatalogRow) async {
        guard row.hasMore, !paging.contains(row.id), !finished.contains(row.id) else { return }
        paging.insert(row.id)
        defer { paging.remove(row.id) }
        let inChip = chip != nil
        let from: CatalogPage = inChip ? .genre(chips[chip ?? 0]) : page
        let got = (try? await Services.shared.catalog.more(row: row, page: from)) ?? []
        func add(_ list: inout [CatalogRow]) {
            guard let i = list.firstIndex(where: { $0.id == row.id }) else { return }
            let have = Set(list[i].items.map(\.id))
            let fresh = got.filter { !have.contains($0.id) }
            if fresh.isEmpty { finished.insert(row.id); return }
            list[i].items += fresh
        }
        if inChip { add(&chipRows) } else { add(&rows) }
    }

    static func words(_ error: Error) -> String {
        (error as? DreamError)?.message ?? "Couldn't load this page. Check your connection and try again."
    }
}

/// A page's rows as one column: hero, chips, rows (with Home's personal rows placed among them), the end cap.
struct CatalogFeed: View {
    let model: CatalogPageModel
    let heroHeight: CGFloat
    /// Room above the first section when there is no hero (the bars over the page).
    let topRoom: CGFloat
    /// Rows the page adds right under the chips (Continue Watching) and after the `insertAfter`th row (My List).
    var leading: [CatalogRow] = []
    var inserted: [CatalogRow] = []
    var insertAfter = 5
    /// Shown over the rows (Home's Movies / TV Shows chips sit in the bar instead of here).
    var showChips = true
    /// Shown right under the hero (Home's New Episode banner, Notices/NewEpisodeBannerView.swift).
    var underHero: AnyView? = nil
    let onTop: () -> Void
    @Environment(ShellModel.self) private var shell
    @Environment(\.cinemaLayout) private var layout

    var body: some View {
        LazyVStack(alignment: .leading, spacing: Cinema.rowGap) {
            if !model.hero.isEmpty {
                SpotlightHero(model.hero, height: heroHeight, play: { shell.play($0) }, info: { shell.open($0.title) })
                    .padding(.bottom, -10)
            } else if model.phase == .loading {
                HeroSkeleton(height: heroHeight).padding(.bottom, -10)
            } else {
                Color.clear.frame(height: topRoom - Cinema.rowGap)
            }

            if let underHero { underHero }

            if showChips, !model.chips.isEmpty {
                ChipLine(["All"] + model.chips.map(\.name), selected: (model.chip ?? -1) + 1) { i in
                    Task { await model.pick(i == 0 ? nil : i - 1) }
                }
            }

            switch model.shownPhase {
            case .loading:
                SkeletonRow(.big)
                SkeletonRow(.landscape)
                SkeletonRow(.landscape)
            case .failed(let message):
                LoadFailed(message) { Task { if model.chip == nil { await model.load() } else { await model.pick(model.chip) } } }
            case .ready:
                let rows = arranged
                if rows.isEmpty {
                    EmptyState(systemImage: "film", title: "Nothing Here Yet",
                               message: "Movies and shows will appear here as soon as Dream OS can reach its catalog. Check your connection and try again.",
                               actionTitle: "Try Again") { Task { await model.load() } }
                } else {
                    ForEach(rows.uniqued()) { row in
                        CatalogRowView(row, open: { open($0, in: row) }, openHub: { shell.push(.page(.season($0.id))) },
                                       menu: { menu($0, in: row) }, onNearEnd: { Task { await model.more(row) } })
                    }
                    // A service page is built from where-to-watch data (JustWatch's, through TMDB): its credit.
                    if case .service = model.page {
                        JustWatchCredit().padding(.horizontal, Cinema.gutter).padding(.top, 4)
                    }
                    if rows.count > 3 { EndCap(onTop: onTop) }
                }
            }
        }
        .padding(.bottom, 12)
    }

    /// The catalog's rows with the page's own placed among them (never twice: a row the catalog already has wins).
    var arranged: [CatalogRow] {
        var out = model.shownRows
        guard model.chip == nil else { return NewEpisodeBadges.shared.apply(out) }
        // The page's own personal rows are live (the watch history right now); the catalog's copy of the same row can
        // be from its cache or the saved page, with old progress bars. The live one replaces it in place.
        var lead: [CatalogRow] = []
        for r in leading {
            if let i = out.firstIndex(where: { $0.name == r.name || (isResumeRow($0) && isResumeRow(r)) }) { out[i] = r } else { lead.append(r) }
        }
        for r in inserted.reversed() {
            if let i = out.firstIndex(where: { $0.name == r.name }) { out[i] = r } else { out.insert(r, at: min(insertAfter, out.count)) }
        }
        // "NEW EPISODE" on followed shows' cards (NewEpisodeSpot.badged).
        return NewEpisodeBadges.shared.apply(lead + out)
    }

    private func isResumeRow(_ row: CatalogRow) -> Bool { row.style == .continueWatching && row.name == "Continue Watching" }

    private func open(_ item: CatalogItem, in row: CatalogRow) {
        if isResumeRow(row) { shell.resume(item) }
        else if row.style == .brand {
            // A service tile: that service's page (its name is the item's title).
            shell.push(.page(.service(ServiceSpec(id: item.title.name.lowercased(), name: item.title.name, logoPath: item.title.posterPath))))
        } else { shell.open(item.title) }
    }

    private func menu(_ item: CatalogItem, in row: CatalogRow) -> [CardMenuAction] {
        var m = shell.menu(for: item.title)
        if isResumeRow(row) {
            m.insert(CardMenuAction("Remove from Continue Watching", systemImage: "xmark.circle", destructive: true) {
                Task {
                    await Services.shared.history.remove(key: item.title.key)
                    NotificationCenter.default.post(name: .dreamHistoryChanged, object: nil)
                }
            }, at: 0)
        }
        return m
    }
}

// (No local `dreamHistoryChanged` here: DreamCore's is the one the watch history posts. A copy here hid it, so
// Continue Watching and Resume never refreshed after watching.)

/// A scrolling page that starts under the status bar: the hero full-bleed at the top, named `Cinema.scrollSpace` (the
/// hero's stretch), with the card sizes for its width, Back to Top, and the current tab's tap-again.
struct CinemaScrollPage<Content: View>: View {
    let tab: ShellModel.Tab?
    @ViewBuilder let content: (_ heroHeight: CGFloat, _ safeTop: CGFloat, _ toTop: @escaping () -> Void) -> Content
    @Environment(ShellModel.self) private var shell

    var body: some View {
        GeometryReader { geo in
            ScrollViewReader { reader in
                ScrollView {
                    Color.clear.frame(height: 0).id("top")
                    content(CinemaLayout(width: geo.size.width).heroHeight(screenHeight: geo.size.height + geo.safeAreaInsets.top),
                            geo.safeAreaInsets.top,
                            { withAnimation(.easeInOut(duration: 0.4)) { reader.scrollTo("top", anchor: .top) } })
                }
                .coordinateSpace(.named(Cinema.scrollSpace))
                .scrollIndicators(.hidden)
                // Room under the last section (the end cap's Back to Top) on top of the tab bar and home indicator
                // insets, in portrait and landscape: nothing at the page's end sits against or under the bar.
                .contentMargins(.bottom, 20 + Self.underBar(geo, shell.tabBarTop), for: .scrollContent)
                .onChange(of: tab.map { shell.topToken[$0, default: 0] } ?? 0) { _, _ in
                    withAnimation(.easeInOut(duration: 0.4)) { reader.scrollTo("top", anchor: .top) }
                }
            }
            .environment(\.cinemaLayout, CinemaLayout(width: geo.size.width))
        }
        .ignoresSafeArea(edges: .top)
        .background(DreamBackground())
    }

    /// How far the bottom of the page's safe area reaches under the tab bar: 0 when the bar's inset reached the
    /// scroll view (the usual case), else the overlap, so the page's end still clears the bar.
    static func underBar(_ geo: GeometryProxy, _ barTop: CGFloat) -> CGFloat {
        guard barTop < .greatestFiniteMagnitude else { return 0 }
        let visibleBottom = geo.frame(in: .global).maxY - geo.safeAreaInsets.bottom
        return max(0, min(160, visibleBottom - barTop))
    }
}

/// A pushed catalog page (Movies, TV Shows, Anime, a service, a genre, a seasonal hub): hero, chips, rows; its name
/// shows in the bar once the page scrolls past the hero.
struct CatalogPageView: View {
    let page: CatalogPage
    @State private var model: CatalogPageModel
    @State private var scrolled = false
    /// Scrolled at all (a page with no hero has its rows right under the bar).
    @State private var moved = false
    @Environment(ShellModel.self) private var shell

    init(page: CatalogPage) {
        self.page = page
        _model = State(initialValue: CatalogPageModel(page: page))
    }

    var body: some View {
        CinemaScrollPage(tab: nil) { heroH, safeTop, toTop in
            CatalogFeed(model: model, heroHeight: heroH, topRoom: safeTop + 56, onTop: toTop)
                .onGeometryChange(for: Bool.self) { $0.frame(in: .named(Cinema.scrollSpace)).minY < -(heroH * 0.6) } action: { scrolled = $0 }
                .onGeometryChange(for: Bool.self) { $0.frame(in: .named(Cinema.scrollSpace)).minY < -12 } action: { moved = $0 }
        }
        .navigationTitle(page.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text(page.name).font(.headline).foregroundStyle(Palette.text).opacity(scrolled || model.hero.isEmpty ? 1 : 0)
            }
        }
        // Dream's black glass (BarGlass), faded in once the page has scrolled past its top.
        .barGlass(scrolled || (moved && model.hero.isEmpty) ? 1 : 0)
        .toolbarRole(.editor)
        .task { model.start() }
        .refreshable { await model.load() }
        .onChange(of: shell.reloadToken) { _, _ in Task { await model.load() } }
    }
}

/// The answers `CatalogPageModel.withPills` collects; `put` says true once every card has answered.
private actor PillBox {
    private var waiters: [@Sendable () -> Void] = []
    var complete: Bool { left == 0 }
    /// Runs `f` once every card has answered (at once if they have).
    func whenComplete(_ f: @escaping @Sendable () -> Void) {
        if left == 0 { f() } else { waiters.append(f) }
    }
    private var answers: [Int: Availability] = [:]
    private var left: Int
    init(count: Int) { left = count }
    func put(_ i: Int, _ a: Availability?) -> Bool {
        if let a { answers[i] = a }
        left -= 1
        if left == 0 { let w = waiters; waiters = []; for f in w { f() } }
        return left == 0
    }
    func all() -> [Int: Availability] { answers }
}
