import SwiftUI
import Observation
import DreamCore
import DreamUI

/// The shell's state (CinemaShell.kt for touch): the tab showing, one navigation path per tab, the Settings sheet,
/// what's in My List (for card menus), and a message when Play can't start yet. In the environment of every page.
@MainActor @Observable
final class ShellModel {
    enum Tab: String, CaseIterable, Identifiable {
        case home, browse, search, myList
        var id: String { rawValue }
        var title: String {
            switch self { case .home: "Home"; case .browse: "Browse"; case .search: "Search"; case .myList: "My List" }
        }
        var icon: String {
            switch self { case .home: "house"; case .browse: "film"; case .search: "magnifyingglass"; case .myList: "bookmark" }
        }
        var iconSelected: String {
            switch self { case .home: "house.fill"; case .browse: "film.fill"; case .search: "magnifyingglass"; case .myList: "bookmark.fill" }
        }
    }

    var tab: Tab = .home
    /// Tabs opened at least once (a tab's pages load when it is first opened, not before).
    var visited: Set<Tab> = [.home]
    var homePath: [Route] = []
    var browsePath: [Route] = []
    var searchPath: [Route] = []
    var myListPath: [Route] = []
    /// Bumped to scroll a tab's root page back to its top (the current tab tapped again at its root).
    var topToken: [Tab: Int] = [:]
    /// The tab bar's top edge on screen (global points). Pages keep their last section above it even if the bar's
    /// safe-area inset doesn't reach their scroll view (`CinemaScrollPage`).
    var tabBarTop: CGFloat = .greatestFiniteMagnitude
    var showSettings = false
    /// Play couldn't start: the sentence to show.
    var playMessage: String?
    /// Saved titles' keys (card menus say Add or Remove).
    var myListKeys: Set<String> = []
    /// Bumped when the catalog behind the pages changed (the debug catalog switched): pages load again.
    var reloadToken = 0

    func select(_ t: Tab) {
        if t == tab {
            if path(t).isEmpty { topToken[t, default: 0] += 1 } else { setPath(t, []) }
        } else {
            visited.insert(t)
            tab = t
        }
    }

    func path(_ t: Tab) -> [Route] {
        switch t { case .home: homePath; case .browse: browsePath; case .search: searchPath; case .myList: myListPath }
    }

    func setPath(_ t: Tab, _ p: [Route]) {
        switch t { case .home: homePath = p; case .browse: browsePath = p; case .search: searchPath = p; case .myList: myListPath = p }
    }

    /// Opens `route` in the tab showing.
    func push(_ route: Route) { setPath(tab, path(tab) + [route]) }

    func open(_ title: Title) { push(.title(title)) }

    func play(_ item: CatalogItem) {
        PlayLauncher.play(item.playRequest) { [weak self] message in self?.playMessage = message }
    }

    // ---- My List ----

    func refreshMyList() async {
        myListKeys = Set(await Services.shared.myList.items().map(\.key))
    }

    func toggleMyList(_ t: Title) {
        Task {
            let on = await Services.shared.myList.toggle(t)
            if on { myListKeys.insert(t.key) } else { myListKeys.remove(t.key) }
        }
    }

    /// The long-press actions every title card has.
    func menu(for t: Title) -> [CardMenuAction] {
        let saved = myListKeys.contains(t.key)
        var actions = [
            CardMenuAction(saved ? "Remove from My List" : "Add to My List", systemImage: saved ? "minus.circle" : "plus.circle") { [weak self] in
                self?.toggleMyList(t)
            },
            CardMenuAction("More Info", systemImage: "info.circle") { [weak self] in self?.open(t) },
        ]
        if PagesServices.playTrailer != nil {
            actions.append(CardMenuAction("Play Trailer", systemImage: "film") { [weak self] in self?.playTrailer(t) })
        }
        return actions
    }

    /// Card menu › Play Trailer (MainActivity.playTrailerFor): the title's trailer in Dream Player. The details are
    /// usually cached by then; with no trailer the player itself says "Trailer Unavailable", as on the TV.
    func playTrailer(_ t: Title) {
        Task {
            let key = (try? await Services.shared.catalog.details(kind: t.kind, id: t.id))?.trailerKey ?? ""
            PagesServices.playTrailer?(t, key)
        }
    }
}
