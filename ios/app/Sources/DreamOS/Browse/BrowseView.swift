import SwiftUI
import DreamCore
import DreamUI

/// Browse (CinemaBrowse.kt): two big entry tiles (TV Shows and Movies, wearing this week's most popular art), Browse by
/// Genre (cards tinted with the rainbow's hues, each with its drawn glyph) and Browse by Service (the brand tiles).
/// Categories beyond the genres live inside the Movies, TV Shows and service pages, not here.
struct BrowseView: View {
    @Environment(ShellModel.self) private var shell
    @State private var genres: [Genre] = []
    @State private var services: [ServiceSpec] = []
    @State private var hubs: [HubTile] = []
    @State private var showsArt: URL?
    @State private var moviesArt: URL?
    @State private var loaded = false
    /// The last full load (its reload token and time): coming back from a genre or service page doesn't ask again.
    @State private var lastLoad: (token: Int, at: Date)?

    var body: some View {
        GeometryReader { geo in
            let layout = CinemaLayout(width: geo.size.width)
            ScrollViewReader { reader in
                ScrollView {
                    VStack(alignment: .leading, spacing: 28) {
                        PageHeader("Browse", subtitle: "Movies, shows, genres and services, all in one place") { ProfileButton() }
                            .padding(.top, 8)
                            .id("top")
                        entryTiles(layout)
                        if !hubs.isEmpty { HubShelf(hubs: hubs) }
                        if !genres.isEmpty { genreGrid(layout) }
                        if !services.isEmpty { serviceGrid(layout) }
                        if loaded, genres.isEmpty, services.isEmpty {
                            EmptyState(systemImage: "square.grid.2x2", title: "Genres and Services Are on Their Way",
                                       message: "They will appear here as soon as Dream OS can reach its catalog.")
                        }
                        DiscoverWall { withAnimation { reader.scrollTo("top", anchor: .top) } }
                    }
                    .padding(.bottom, 24)
                }
                .scrollIndicators(.hidden)
                .onChange(of: shell.topToken[.browse, default: 0]) { _, _ in withAnimation { reader.scrollTo("top", anchor: .top) } }
            }
            .environment(\.cinemaLayout, layout)
        }
        .background(DreamBackground())
        .toolbar(.hidden, for: .navigationBar)
        .task(id: shell.reloadToken) { await load() }
        // The pages behind the first tiles, fetched ahead while Browse shows (stops when the viewer leaves it).
        .task(id: shell.reloadToken, priority: .utility) { await BrowseAhead.run() }
    }

    private func entryTiles(_ layout: CinemaLayout) -> some View {
        let w = floor((layout.width - 2 * Cinema.gutter - Cinema.cardGap) / 2)
        let h = (w * 0.68).rounded()
        return HStack(spacing: Cinema.cardGap) {
            Button { shell.push(.page(.shows)) } label: {
                EntryTile("TV Shows", sub: "New seasons and every series", art: showsArt, hues: (Rainbow.hexes[0], Rainbow.hexes[1]), width: w, height: h)
            }
            Button { shell.push(.page(.movies)) } label: {
                EntryTile("Movies", sub: "New releases and all-time favorites", art: moviesArt, hues: (Rainbow.hexes[3], Rainbow.hexes[4]), width: w, height: h)
            }
        }
        .buttonStyle(CardButtonStyle())
        .padding(.horizontal, Cinema.gutter)
    }

    /// Genres as a two-line row you swipe through (Apple TV app style), not a wall of cards.
    private func genreGrid(_ layout: CinemaLayout) -> some View {
        let w: CGFloat = 156
        let list = genres.uniqued()
        let lines = list.count > 6 ? 2 : 1
        return VStack(alignment: .leading, spacing: Cinema.rowTitleGap) {
            RowHeader("Browse by Genre")
            ScrollView(.horizontal) {
                LazyHGrid(rows: Array(repeating: GridItem(.flexible(), spacing: Cinema.cardGap), count: lines), spacing: Cinema.cardGap) {
                    ForEach(Array(list.enumerated()), id: \.element.id) { i, g in
                        Button { shell.push(.page(.genre(g))) } label: { GenreCard(g.name, hue: Rainbow.hue(i), width: w) }
                            .buttonStyle(CardButtonStyle())
                    }
                }
                .padding(.horizontal, Cinema.gutter)
                .padding(.vertical, 6)
            }
            .scrollIndicators(.hidden)
        }
    }

    /// Services as one swipeable row of brand tiles.
    private func serviceGrid(_ layout: CinemaLayout) -> some View {
        let w: CGFloat = 150
        return VStack(alignment: .leading, spacing: Cinema.rowTitleGap) {
            RowHeader("Browse by Service")
            ScrollView(.horizontal) {
                LazyHStack(spacing: Cinema.cardGap) {
                    ForEach(services.uniqued()) { s in
                        Button { shell.push(.page(.service(s))) } label: { BrandCard(s, width: w) }
                            .buttonStyle(CardButtonStyle())
                    }
                }
                .padding(.horizontal, Cinema.gutter)
                .padding(.vertical, 6)
            }
            .scrollIndicators(.hidden)
        }
    }

    private func load() async {
        if let l = lastLoad, l.token == shell.reloadToken, Date().timeIntervalSince(l.at) < 600 { return }
        let c = Services.shared.catalog
        // The tiles wear the art Movies and TV Shows showed last time at once (their snapshots), then today's.
        if showsArt == nil { showsArt = PageSnapshots.get(.shows)?.hero.first { $0.title.backdropPath != nil }?.title.backdrop("w780") }
        if moviesArt == nil { moviesArt = PageSnapshots.get(.movies)?.hero.first { $0.title.backdropPath != nil }?.title.backdrop("w780") }
        async let g = c.genres()
        async let s = c.services()
        async let h = c.hubs()
        async let shows = try? c.spotlight(for: .shows)
        async let movies = try? c.spotlight(for: .movies)
        genres = await g
        services = await s
        hubs = await h
        loaded = true
        if let u = (await shows)?.first(where: { $0.title.backdropPath != nil })?.title.backdrop("w780"), u != showsArt { showsArt = u }
        if let u = (await movies)?.first(where: { $0.title.backdropPath != nil })?.title.backdrop("w780"), u != moviesArt { moviesArt = u }
        // An empty answer (offline) is asked again on the next visit.
        if !Task.isCancelled, !genres.isEmpty || !services.isEmpty { lastLoad = (shell.reloadToken, Date()) }
    }
}
