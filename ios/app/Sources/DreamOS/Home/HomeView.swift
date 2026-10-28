import SwiftUI
import DreamCore
import DreamUI

/// Home (CinemaPages.kt CinemaHome, Netflix-style on the phone): the Dream OS mark and the profile face over the hero,
/// TV Shows and Movies chips that switch the page in place (the X goes back to Home), then the rows, with Continue
/// Watching near the top and My List among them. The bar turns to black glass as the page scrolls.
struct HomeView: View {
    @Environment(ShellModel.self) private var shell
    @State private var feed: CatalogPage = .home
    /// Home's model exists from the first frame, holding the rows saved last time (PageSnapshots).
    @State private var models: [CatalogPage: CatalogPageModel] = [.home: CatalogPageModel(page: .home)]
    @State private var lookedAhead = false
    @State private var continueRow: CatalogRow?
    @State private var myListRow: CatalogRow?
    @State private var barGlass: Double = 0
    @State private var debugToggles = 0

    private var model: CatalogPageModel {
        if let m = models[feed] { return m }
        return CatalogPageModel(page: feed)
    }

    var body: some View {
        CinemaScrollPage(tab: .home) { heroH, safeTop, toTop in
            CatalogFeed(model: model, heroHeight: heroH, topRoom: safeTop + 56,
                        leading: continueRow.map { [$0] } ?? [], inserted: feed == .home ? (myListRow.map { [$0] } ?? []) : [],
                        showChips: false, underHero: feed == .home ? AnyView(NewEpisodeBannerView()) : nil, onTop: toTop)
                .id(feed)
                .transition(.opacity)
                .onGeometryChange(for: Double.self) { g in
                    let y = Double(g.frame(in: .named(Cinema.scrollSpace)).minY)
                    let v: Double = min(1, max(0, -y / 140))
                    return (v * 10).rounded() / 10
                } action: { v in if v != barGlass { barGlass = v } }
        }
        .overlay(alignment: .top) { topBar }
        .toolbar(.hidden, for: .navigationBar)
        .task(id: feed) { await ensureLoaded(feed) }
        .task(id: shell.reloadToken) { await refreshPersonal() }
        .onAppear {
            if !lookedAhead { Speed.noteLaunch("Home first frame") }
            Task { await refreshPersonal() }
        }
        .onReceive(NotificationCenter.default.publisher(for: .dreamHistoryChanged)) { _ in Task { await refreshPersonal() } }
        .refreshable {
            await models[feed]?.load()
            await refreshPersonal()
        }
        .onChange(of: shell.reloadToken) { _, _ in
            models = [:]
            Task { await ensureLoaded(feed) }
        }
    }

    // MARK: Bar

    private var topBar: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                DreamMark(height: 30)
                    .onLongPressGesture(minimumDuration: 1.5) { toggleDebugCatalog() }
                Spacer(minLength: 8)
                NoticeBell()
                ProfileButton()
            }
            .padding(.leading, Cinema.gutter)
            .padding(.trailing, Cinema.gutter - 6)
            // No TV Shows / Movies chips here (owner: they looked bad); Browse's big tiles open those pages.
        }
        .padding(.bottom, 10)
        .background {
            ZStack {
                Rectangle().fill(.ultraThinMaterial)
                Color.black.opacity(0.55)
            }
            .overlay(alignment: .bottom) { Rectangle().fill(Palette.hairline).frame(height: 1) }
            .opacity(barGlass)
            .ignoresSafeArea(edges: .top)
        }
        .animation(.easeOut(duration: 0.15), value: barGlass)
    }

    private var feedChips: some View {
        HStack(spacing: 10) {
            if feed != .home {
                Button { switchFeed(.home) } label: {
                    Image(systemName: "xmark").font(.system(size: 13, weight: .bold))
                }
                .buttonStyle(RoundButtonStyle(size: 36))
                .accessibilityLabel("Back to Home")
                .transition(.scale.combined(with: .opacity))
            }
            ForEach([CatalogPage.shows, .movies], id: \.self) { p in
                if feed == .home || feed == p {
                    Button(p.name) { switchFeed(feed == p ? .home : p) }
                        .buttonStyle(ChipButtonStyle(selected: feed == p))
                        .accessibilityAddTraits(feed == p ? .isSelected : [])
                }
            }
            if let m = models[feed], feed != .home, !m.chips.isEmpty {
                Menu {
                    Button("All") { Task { await m.pick(nil) } }
                    ForEach(Array(m.chips.enumerated()), id: \.element.id) { i, g in
                        Button(g.name) { Task { await m.pick(i) } }
                    }
                } label: {
                    HStack(spacing: 8) {
                        Text(m.chip.map { m.chips[$0].name } ?? "All Genres")
                        Image(systemName: "chevron.down").font(.system(size: 11, weight: .bold))
                    }
                }
                .buttonStyle(ChipButtonStyle(selected: m.chip != nil))
            }
        }
        .padding(.horizontal, Cinema.gutter)
        .animation(.easeOut(duration: 0.22), value: feed)
        .sensoryFeedback(.selection, trigger: feed)
    }

    // MARK: Data

    private func switchFeed(_ p: CatalogPage) {
        withAnimation(Cinema.pageAnimation) { feed = p }
        Task { await refreshPersonal() }
    }

    private func ensureLoaded(_ p: CatalogPage) async {
        let m = models[p] ?? CatalogPageModel(page: p)
        if models[p] == nil { models[p] = m }
        m.start()
        // Once Home has filled, Browse's first pages are fetched ahead in the background (the next likely screens).
        if p == .home, !lookedAhead {
            lookedAhead = true
            Task(priority: .background) {
                await m.waitLoaded()
                await BrowseAhead.run(genres: 4, services: 2)
            }
        }
    }

    /// Continue Watching (filtered to the feed's type) and My List, from the viewer's own history and list.
    private func refreshPersonal() async {
        let entries = await Services.shared.history.continueWatching()
        let kind: MediaKind? = feed == .movies ? .movie : feed == .shows ? .tv : nil
        let shown = entries.filter { kind == nil || $0.title.kind == kind }.prefix(15)
        continueRow = shown.isEmpty ? nil
            : CatalogRow(id: "personal:continue", name: "Continue Watching", style: .continueWatching, items: shown.map { ContinueCard.item($0) })
        let saved = await Services.shared.myList.items()
        myListRow = saved.isEmpty ? nil
            : CatalogRow(id: "personal:mylist", name: "My List", style: .landscape, items: saved.prefix(20).map { CatalogItem(title: $0) })
        await shell.refreshMyList()
        if feed == .home { await NewEpisodeSpot.shared.refresh() }
    }

    private func toggleDebugCatalog() {
        #if DEBUG
        DebugCatalog.toggle()
        shell.reloadToken += 1
        #endif
    }
}
