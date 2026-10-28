import SwiftUI
import Observation
import DreamCore
import DreamUI

/// Browse's "Every Movie and Show" (CinemaBrowse.kt `wall()`): three Dream pickers (type, order, genre; each names its
/// current choice and opens Dream's menu panel; saved per profile) over a poster wall of every title, the name under each, that pages in as its end comes near. Only
/// full lines go up while more pages are coming (the very last line may be short), and nothing already on the wall
/// moves. Titles are tidied like every row by the catalog (`discover`: nothing adult, finished or obscure, anime only
/// when the genre is Anime).
struct DiscoverWall: View {
    var onTop: () -> Void
    @State private var model = DiscoverWallModel()
    @Environment(ShellModel.self) private var shell
    @Environment(\.cinemaLayout) private var layout

    var body: some View {
        let cols = layout.columns(104, min: 3).count
        VStack(alignment: .leading, spacing: 14) {
            RowHeader("Every Movie and Show")
            filters(cols: cols)
                .padding(.horizontal, Cinema.gutter)
                .padding(.bottom, 12)
            wall(cols: cols)
        }
        .task(id: shell.reloadToken) { await model.start(cols: cols, token: shell.reloadToken) }
    }

    /// The filters as one tidy line of Dream picker buttons ("Movies", "Popular & Favorites", "Any Genre"), wrapping
    /// to a second line on a narrow phone. Each opens Dream's menu panel (`DreamMenu`); a pick restarts the wall.
    private func filters(cols: Int) -> some View {
        let f = model.filter
        let kinds = [DreamMenuItem(id: "movie", label: "Movies"), DreamMenuItem(id: "tv", label: "TV Shows")]
        let sorts = DiscoverSort.pickerOrder.map { DreamMenuItem(id: "\($0.rawValue)", label: $0.name) }
        let genres = [DreamMenuItem(id: "0", label: "Any Genre", glyph: "Any", tint: Rainbow.mixHex(Rainbow.hue(0), 0xFFFFFF, 0.35))]
            + model.categories.uniqued().enumerated().map { i, c in
                DreamMenuItem(id: "\(c.id)", label: c.name, glyph: c.name, tint: Rainbow.mixHex(Rainbow.hue(i + 1), 0xFFFFFF, 0.35))
            }
        return WrapLayout(spacing: 10, lineSpacing: 10) {
            DreamPickerButton(heading: "Movies or TV Shows", items: kinds, selected: f.kind == .tv ? "tv" : "movie",
                              accessibilityHint: "Chooses movies or TV shows") { id in
                Task { await model.setKind(id == "tv" ? .tv : .movie, cols: cols) }
            }
            DreamPickerButton(heading: "Sort By", items: sorts, selected: "\(f.sort.rawValue)",
                              accessibilityHint: "Chooses the order") { id in
                if let raw = Int(id), let s = DiscoverSort(rawValue: raw) { Task { await model.setSort(s, cols: cols) } }
            }
            DreamPickerButton(heading: "Genres", items: genres, selected: "\(f.genre)",
                              accessibilityHint: "Chooses a genre") { id in
                if let g = Int(id) { Task { await model.setGenre(g, cols: cols) } }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private func wall(cols: Int) -> some View {
        if model.titles.isEmpty {
            switch model.state {
            case .loading: placeholders(cols: cols)
            case .failed:
                EmptyState(systemImage: "magnifyingglass", title: "Nothing to Show", message: "Check the connection, or try another genre.",
                           actionTitle: "Try Again") { Task { await model.reset(cols: cols) } }
            case .ready, .end:
                EmptyState(systemImage: "magnifyingglass", title: "Nothing Matches", message: "Try another genre or order.")
            }
        } else {
            PosterGrid(model.titles, open: { shell.open($0) }, menu: shell.menu(for:),
                       onNearEnd: { Task { await model.next(cols: cols) } })
                .id(model.generation)
            if model.state == .loading { placeholders(cols: cols, lines: 1).padding(.top, 18) }
            if model.state == .end, model.titles.count > cols * 4 { EndCap(onTop: onTop) }
        }
    }

    /// A line or two of poster shapes while the next titles load.
    private func placeholders(cols: Int, lines: Int = 2) -> some View {
        let w = layout.columns(104, min: 3).width
        return LazyVGrid(columns: Array(repeating: GridItem(.fixed(w), spacing: Cinema.cardGap, alignment: .top), count: cols),
                         alignment: .leading, spacing: 18) {
            ForEach(0..<(cols * lines), id: \.self) { _ in
                VStack(alignment: .leading, spacing: Cinema.captionGap) {
                    RoundedRectangle(cornerRadius: Cinema.rCard, style: .continuous).fill(Color.white.opacity(0.06))
                        .frame(width: w, height: w * 1.5)
                    RoundedRectangle(cornerRadius: 3).fill(Color.white.opacity(0.05)).frame(width: w * 0.7, height: 10)
                }
            }
        }
        .padding(.horizontal, Cinema.gutter)
        .accessibilityHidden(true)
    }
}

/// The wall's data: the saved filters, the titles up so far, and the pages behind them.
@MainActor @Observable
final class DiscoverWallModel {
    enum State: Equatable { case loading, ready, end, failed }

    private(set) var filter: DiscoverFilter = DiscoverWallModel.saved()
    private(set) var categories: [DiscoverCategory] = []
    private(set) var titles: [Title] = []
    private(set) var state: State = .loading
    /// Bumped when the wall starts over (the grid is rebuilt rather than diffed).
    private(set) var generation = 0
    @ObservationIgnored private var keys = Set<String>()
    /// Titles waiting for a full line.
    @ObservationIgnored private var buffer: [Title] = []
    @ObservationIgnored private var page = 0
    @ObservationIgnored private var totalPages = 1
    @ObservationIgnored private var busy = false
    @ObservationIgnored private var startedWith: Int?

    /// The first open, or the pages behind Browse changed (a profile switch, another catalog): the wall starts over
    /// with that profile's saved filters.
    func start(cols: Int, token: Int) async {
        if startedWith == token, !titles.isEmpty { return }
        if startedWith != nil, startedWith != token { filter = Self.saved() }
        startedWith = token
        categories = await Services.shared.catalog.discoverCategories(filter.kind)
        // A saved genre the kind no longer lists: Any Genre.
        if filter.genre != 0, !categories.contains(where: { $0.id == filter.genre }) { filter.genre = 0 }
        await reset(cols: cols)
    }

    func setKind(_ kind: MediaKind, cols: Int) async {
        guard kind != filter.kind else { return }
        // The same category on the other kind (Horror is on TV Shows too).
        let name = categories.first { $0.id == filter.genre }?.name
        categories = await Services.shared.catalog.discoverCategories(kind)
        filter.kind = kind
        filter.genre = name.flatMap { n in categories.first { $0.name == n }?.id } ?? 0
        changed()
        await reset(cols: cols)
    }

    func setSort(_ sort: DiscoverSort, cols: Int) async {
        guard sort != filter.sort else { return }
        filter.sort = sort
        changed()
        await reset(cols: cols)
    }

    func setGenre(_ id: Int, cols: Int) async {
        guard id != filter.genre else { return }
        filter.genre = id
        changed()
        await reset(cols: cols)
    }

    func reset(cols: Int) async {
        generation += 1
        titles = []; keys = []; buffer = []
        page = 0; totalPages = 1
        busy = false
        state = .loading
        await next(cols: cols)
    }

    /// The next page (lines of posters under the last). A page that made no full line, or a wall still short,
    /// fetches on.
    func next(cols: Int) async {
        guard !busy, page < totalPages else { return }
        busy = true
        state = .loading
        let gen = generation
        let f = filter
        let n = page + 1
        let got = try? await Services.shared.catalog.discover(f, page: n)
        guard gen == generation else { return }
        busy = false
        guard let got else {
            state = titles.isEmpty ? .failed : .ready
            return
        }
        page = got.page
        totalPages = max(got.page, got.totalPages)
        let last = page >= totalPages
        buffer += got.titles.filter { keys.insert($0.key).inserted }
        let whole = last ? buffer.count : buffer.count - buffer.count % max(1, cols)
        if whole > 0 {
            titles += buffer.prefix(whole)
            buffer.removeFirst(whole)
        }
        state = last ? .end : .ready
        if !last, whole == 0 || titles.count < cols * 3 { await next(cols: cols) }
    }

    // ---- saved per profile (Discover.load / save) ----

    private func changed() { DreamStore.shared.set(filter, "discover.filter", perProfile: true) }

    nonisolated static func saved() -> DiscoverFilter { DreamStore.shared.get(DiscoverFilter.self, "discover.filter", perProfile: true) ?? DiscoverFilter() }
}
