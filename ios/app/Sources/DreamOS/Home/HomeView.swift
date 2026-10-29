import SwiftUI
import Observation
import DreamCore
import DreamUI

/// Home (CinemaPages.kt CinemaHome, Netflix-style on the phone): the Dream OS mark and the profile face over the hero,
/// then the rows, with Continue Watching near the top and My List among them. The bar turns to black glass as the page
/// scrolls. `feed` is always Home since the TV Shows / Movies chips left the bar (Browse opens those pages).
struct HomeView: View {
    @Environment(ShellModel.self) private var shell
    @State private var feed: CatalogPage = .home
    /// Home's model exists from the first frame, holding the rows saved last time (PageSnapshots).
    @State private var models: [CatalogPage: CatalogPageModel] = [.home: CatalogPageModel(page: .home)]
    @State private var lookedAhead = false
    @State private var continueRow: CatalogRow?
    @State private var myListRow: CatalogRow?
    /// Read only by the top bar, so scrolling past the hero redraws the bar and not the page.
    @State private var glass = HomeBarGlass()

    private var model: CatalogPageModel {
        if let m = models[feed] { return m }
        return CatalogPageModel(page: feed)
    }

    var body: some View {
        let glass = glass
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
                } action: { v in if v != glass.amount { glass.amount = v } }
        }
        .overlay(alignment: .top) { HomeTopBar(glass: glass) { toggleDebugCatalog() } }
        .toolbar(.hidden, for: .navigationBar)
        .task(id: feed) { await ensureLoaded(feed) }
        .onAppear {
            if !lookedAhead { Speed.noteLaunch("Home first frame") }
            Task { await refreshPersonal() }
        }
        .onReceive(NotificationCenter.default.publisher(for: .dreamHistoryChanged)) { _ in Task { await refreshPersonal() } }
        .onChange(of: shell.myListKeys) { _, _ in Task { await refreshMyListRow() } }
        .refreshable {
            await models[feed]?.load()
            await refreshPersonal()
        }
        .onChange(of: shell.reloadToken) { _, _ in
            models = [feed: CatalogPageModel(page: feed)]
            Task {
                await ensureLoaded(feed)
                await refreshPersonal()
            }
        }
    }

    // MARK: Data

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
        await refreshMyListRow()
        await shell.refreshMyList()
        if feed == .home { await NewEpisodeSpot.shared.refresh() }
    }

    private func refreshMyListRow() async {
        let saved = await Services.shared.myList.items()
        myListRow = saved.isEmpty ? nil
            : CatalogRow(id: "personal:mylist", name: "My List", style: .landscape, items: saved.prefix(20).map { CatalogItem(title: $0) })
    }

    private func toggleDebugCatalog() {
        #if DEBUG
        DebugCatalog.toggle()
        shell.reloadToken += 1
        #endif
    }
}

/// How far Home's top bar glass is up (0 ... 1 in tenths).
@MainActor @Observable
private final class HomeBarGlass {
    var amount: Double = 0
}

/// Home's bar: the Dream OS mark, the bell and the profile face over the hero, on black glass once the page scrolls.
/// No TV Shows / Movies chips here (owner: they looked bad); Browse's big tiles open those pages.
private struct HomeTopBar: View {
    let glass: HomeBarGlass
    let onMarkHeld: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            DreamMark(height: 30)
                .onLongPressGesture(minimumDuration: 1.5) { onMarkHeld() }
            Spacer(minLength: 8)
            NoticeBell()
            ProfileButton()
        }
        .padding(.leading, Cinema.gutter)
        .padding(.trailing, Cinema.gutter - 6)
        .padding(.bottom, 10)
        .background {
            ZStack {
                Rectangle().fill(.ultraThinMaterial)
                Color.black.opacity(0.55)
            }
            .overlay(alignment: .bottom) { Rectangle().fill(Palette.hairline).frame(height: 1) }
            .opacity(glass.amount)
            .ignoresSafeArea(edges: .top)
        }
        .animation(.easeOut(duration: 0.15), value: glass.amount)
    }
}
