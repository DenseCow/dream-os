import SwiftUI
import DreamCore
import DreamUI

/// Dream Cinema's shell for the phone (CinemaShell.kt): Home, Browse, Search and My List under a bottom tab bar, one
/// NavigationStack per tab (each keeps its place), the profile face opening profiles and Settings. Tapping the current
/// tab goes back to its first page, then to that page's top.
struct AppShell: View {
    @State private var model = ShellModel()

    init() {
        #if DEBUG
        DebugCatalog.applyAtLaunch()
        #endif
        // A title card's press-down fetches its page ahead (speed pass, Home/PageSpeed.swift).
        CardLookAhead.title = { TitleAhead.warm($0) }
        // "Now Available" notices for My List movies, and the phone's new-episode alerts (Notices/).
        NowAvailableNotices.install()
        EpisodeAlerts.shared.install()
    }

    var body: some View {
        @Bindable var model = model
        ZStack {
            DreamBackground()
            ForEach(ShellModel.Tab.allCases) { t in
                if model.visited.contains(t) {
                    stack(t)
                        .opacity(model.tab == t ? 1 : 0)
                        .allowsHitTesting(model.tab == t)
                        .accessibilityHidden(model.tab != t)
                }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            // The mini player (Dream Player minimized, Shell/MiniPlayer.swift) rides above the tab bar in the same
            // inset, so pages keep room for both; `tabBarTop` is the top of the two together.
            VStack(spacing: 0) {
                MiniPlayerDock()
                DreamTabBar(selected: model.tab) { model.select($0) }
            }
            .onGeometryChange(for: CGFloat.self) { $0.frame(in: .global).minY.rounded() } action: { model.tabBarTop = $0 }
        }
        // Dream Notices: banners over the shell and the Notifications sheet (Notices/NoticeBanner.swift).
        .overlay(alignment: .top) { NoticeHost() }
        .environment(model)
        .sheet(isPresented: $model.showSettings, onDismiss: {
            Task { await model.refreshMyList() }
            // The profile's kids settings read again first, so pages never show rows saved under the old ones.
            Task {
                _ = await PageSnapshots.fingerprint()
                model.reloadToken += 1
            }
        }) {
            SettingsSheet()
        }
        .alert("Playback Isn't Ready", isPresented: Binding(get: { model.playMessage != nil }, set: { if !$0 { model.playMessage = nil } })) {
            Button("OK", role: .cancel) { model.playMessage = nil }
        } message: {
            Text(model.playMessage ?? "")
        }
        .task { await model.refreshMyList() }
        .dynamicTypeSize(.xSmall ... .xxLarge)
        .preferredColorScheme(.dark)
        .tint(Palette.text)
    }

    @ViewBuilder
    private func stack(_ t: ShellModel.Tab) -> some View {
        NavigationStack(path: Binding(get: { model.path(t) }, set: { model.setPath(t, $0) })) {
            root(t)
                .navigationDestination(for: Route.self) { RouteView(route: $0) }
        }
    }

    @ViewBuilder
    private func root(_ t: ShellModel.Tab) -> some View {
        switch t {
        case .home: HomeView()
        case .browse: BrowseView()
        case .search: SearchView()
        case .myList: MyListView()
        }
    }
}
