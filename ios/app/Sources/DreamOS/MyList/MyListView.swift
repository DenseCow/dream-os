import SwiftUI
import DreamCore
import DreamUI
import DreamPlayback

/// My List (CinemaMyList.kt): the saved titles, organized. Chips on top (All · Movies · TV Shows, then Recently Added ·
/// A–Z, kept per profile between visits, like the TV's `MyList.view`), then Continue Watching (saved titles part way
/// through), Movies and TV Shows, each a poster grid headed with its count. Hold a poster to remove it.
struct MyListView: View {
    @Environment(ShellModel.self) private var shell
    @State private var saved: [Title] = []
    @State private var going: [HistoryEntry] = []
    @State private var loaded = false
    /// 0 All, 1 Movies, 2 TV Shows.
    @State private var type = Self.storedType()
    /// 0 Recently Added, 1 A–Z.
    @State private var order = Self.storedOrder()

    private static let kinds: [MyList.Kind] = [.all, .movie, .tv]
    private static func storedType() -> Int { kinds.firstIndex(of: MyList.shared.view.kind) ?? 0 }
    private static func storedOrder() -> Int { MyList.shared.view.order == .az ? 1 : 0 }
    private func store() { MyList.shared.view = (Self.kinds[type], order == 1 ? .az : .recent) }

    var body: some View {
        GeometryReader { geo in
            ScrollViewReader { reader in
                ScrollView {
                    VStack(alignment: .leading, spacing: 24) {
                        PageHeader("My List", subtitle: summary) { ProfileButton() }.padding(.top, 8).id("top")
                        if loaded && saved.isEmpty {
                            EmptyState(systemImage: "bookmark", title: "Nothing Saved Yet",
                                       message: "Open any movie or show and choose My List. It will wait for you here.")
                        } else if loaded {
                            chips
                            sections
                        }
                    }
                    .padding(.bottom, 24)
                }
                .scrollIndicators(.hidden)
                .onChange(of: shell.topToken[.myList, default: 0]) { _, _ in withAnimation { reader.scrollTo("top", anchor: .top) } }
            }
            .environment(\.cinemaLayout, CinemaLayout(width: geo.size.width))
        }
        .background(DreamBackground())
        .toolbar(.hidden, for: .navigationBar)
        // Runs on every appearance and whenever the saved keys change (one load, not two).
        .task(id: shell.myListKeys) { await load() }
        .refreshable { await load() }
        .onReceive(NotificationCenter.default.publisher(for: .dreamProfileChanged)) { _ in
            type = Self.storedType(); order = Self.storedOrder()
            Task { await load() }
        }
        // Continue Watching follows progress, episode and Up Next (a resume from here comes back to this page).
        .onReceive(NotificationCenter.default.publisher(for: .dreamHistoryChanged)) { _ in Task { await load() } }
    }

    // MARK: Parts

    private var chips: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 10) {
                ForEach(Array(["All", "Movies", "TV Shows"].enumerated()), id: \.offset) { i, l in
                    Button(l) { withAnimation(Cinema.pageAnimation) { type = i }; store() }.buttonStyle(ChipButtonStyle(selected: type == i))
                }
                Rectangle().fill(Color.white.opacity(0.22)).frame(width: 1, height: 18).padding(.horizontal, 6)
                ForEach(Array(["Recently Added", "A–Z"].enumerated()), id: \.offset) { i, l in
                    Button(l) { withAnimation(Cinema.pageAnimation) { order = i }; store() }.buttonStyle(ChipButtonStyle(selected: order == i))
                }
            }
            .padding(.vertical, 4)
        }
        .contentMargins(.horizontal, Cinema.gutter, for: .scrollContent)
        .scrollIndicators(.hidden)
        .scrollClipDisabled()
        .sensoryFeedback(.selection, trigger: type * 10 + order)
    }

    @ViewBuilder private var sections: some View {
        let keep: (Title) -> Bool = { type == 0 || ($0.kind == .movie) == (type == 1) }
        let savedKeys = Set(saved.map(\.key))
        let inProgress = going.filter { savedKeys.contains($0.title.key) && keep($0.title) }
        let movies = sorted(saved.filter { $0.kind == .movie })
        let shows = sorted(saved.filter { $0.kind == .tv })
        let progress = Dictionary(inProgress.map { ($0.title.key, $0.progress) }, uniquingKeysWith: { a, _ in a })

        if !inProgress.isEmpty {
            section("Continue Watching", inProgress.map(\.title), count: nil, progress: progress) { t in
                if let e = inProgress.first(where: { $0.title.key == t.key }) { shell.resume(ContinueCard.item(e)) }
            }
        }
        if type != 2, !movies.isEmpty { section("Movies", movies, count: movies.count, progress: [:]) { shell.open($0) } }
        if type != 1, !shows.isEmpty { section("TV Shows", shows, count: shows.count, progress: [:]) { shell.open($0) } }
        if type == 1, movies.isEmpty {
            EmptyState(systemImage: "film", title: "No Movies Saved Yet", message: "Open any movie and choose My List. It will wait for you here.")
        }
        if type == 2, shows.isEmpty {
            EmptyState(systemImage: "tv", title: "No TV Shows Saved Yet", message: "Open any show and choose My List. It will wait for you here.")
        }
    }

    private func section(_ name: String, _ titles: [Title], count: Int?, progress: [String: Double],
                         open: @escaping (Title) -> Void) -> some View {
        VStack(alignment: .leading, spacing: Cinema.rowTitleGap + 4) {
            SectionHeading(name, count: count)
            PosterGrid(titles, showSub: false, open: open, menu: { t in
                [CardMenuAction("Remove from My List", systemImage: "minus.circle", destructive: true) { remove(t) },
                 CardMenuAction("More Info", systemImage: "info.circle") { shell.open(t) }]
            }, progress: { progress[$0.key] })
        }
    }

    // MARK: Data

    private var summary: String? {
        guard !saved.isEmpty else { return nil }
        let m = saved.filter { $0.kind == .movie }.count, s = saved.count - m
        func n(_ c: Int, _ one: String, _ many: String) -> String { "\(c) \(c == 1 ? one : many)" }
        return ([n(saved.count, "Title", "Titles")] + (m > 0 ? [n(m, "Movie", "Movies")] : []) + (s > 0 ? [n(s, "TV Show", "TV Shows")] : []))
            .joined(separator: Cinema.dot)
    }

    private func sorted(_ list: [Title]) -> [Title] {
        order == 1 ? list.sorted { TitleMeta.sortName($0.name).localizedCaseInsensitiveCompare(TitleMeta.sortName($1.name)) == .orderedAscending } : list
    }

    private func remove(_ t: Title) {
        withAnimation(Cinema.pageAnimation) { saved.removeAll { $0.key == t.key } }
        shell.toggleMyList(t)
    }

    private func load() async {
        let s = await Services.shared.myList.items()
        let g = await Services.shared.history.continueWatching().filter { $0.upNext || $0.progress > 0 }
        saved = s
        going = g
        loaded = true
    }
}
