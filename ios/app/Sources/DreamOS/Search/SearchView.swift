import SwiftUI
import DreamCore
import DreamUI

/// Search (CinemaSearch.kt for touch): the field with the iOS keyboard, live results as a poster grid while typing,
/// recent searches and Trending Now while the field is empty. While typing, suggestions run under the field (a genre
/// whose name starts with what's typed, recent searches, matching titles). A person ("brad pitt movies") brings their
/// titles under "Movies & Shows with Brad Pitt"; a genre or category ("horror", "crime shows", "romcom") leads with
/// "Browse Horror" chips into its Browse page, then its movies and its shows, above the title results.
struct SearchView: View {
    @Environment(ShellModel.self) private var shell
    @State private var query = ""
    @State private var results = SearchResults(titles: [])
    /// The plain title search's quick answer (it fills Top Results and the suggestions before the full search).
    @State private var quick: [Title] = []
    /// A genre whose name starts with what's typed (GenreSearch.suggest, one at most).
    @State private var genreHint: [String] = []
    @State private var searching = false
    @State private var searched = ""
    @State private var trending: [Title] = []
    @State private var recent: [String] = RecentSearches.list()
    @FocusState private var focused: Bool

    private var trimmed: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        GeometryReader { geo in
            let layout = CinemaLayout(width: geo.size.width)
            ScrollViewReader { reader in
                ScrollView {
                    VStack(alignment: .leading, spacing: 22) {
                        PageHeader("Search") { ProfileButton() }.padding(.top, 8).id("top")
                        VStack(alignment: .leading, spacing: 12) {
                            field
                            if !trimmed.isEmpty { suggestionLine }
                        }
                        if trimmed.isEmpty { idle } else { live(layout) }
                    }
                    .padding(.bottom, 24)
                }
                .scrollIndicators(.hidden)
                .scrollDismissesKeyboard(.immediately)
                .onChange(of: shell.topToken[.search, default: 0]) { _, _ in
                    withAnimation { reader.scrollTo("top", anchor: .top) }
                    focused = true
                }
            }
            .environment(\.cinemaLayout, layout)
        }
        .background(DreamBackground())
        .toolbar(.hidden, for: .navigationBar)
        .task(id: shell.reloadToken) {
            trending = Array(await Services.shared.catalog.trending().filter { $0.posterPath != nil }.prefix(SearchLook.maxTiles))
        }
        .task(id: trimmed) { await run(trimmed) }
        .onReceive(NotificationCenter.default.publisher(for: .dreamProfileChanged)) { _ in recent = RecentSearches.list() }
    }

    // MARK: Field

    private var field: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").font(.body.weight(.semibold)).foregroundStyle(Palette.textSecondary)
            TextField("", text: $query, prompt: Text("Movies, shows and people").foregroundStyle(Palette.textTertiary))
                .font(.body.weight(.medium))
                .foregroundStyle(Palette.text)
                .focused($focused)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .onSubmit { remember(trimmed) }
            if !query.isEmpty {
                Button { query = ""; focused = true } label: {
                    Image(systemName: "xmark.circle.fill").font(.body).foregroundStyle(Palette.textSecondary)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear")
            }
        }
        .padding(.leading, 14).padding(.trailing, 6)
        .frame(height: 48)
        .background(Color.white.opacity(0.10), in: RoundedRectangle(cornerRadius: Cinema.rButton, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: Cinema.rButton, style: .continuous)
                .strokeBorder(focused ? Color.white.opacity(0.34) : Color.white.opacity(0.12), lineWidth: 1)
        }
        .padding(.horizontal, Cinema.gutter)
        .animation(.easeOut(duration: 0.15), value: focused)
    }

    // MARK: Suggestions (CinemaSearch.showSuggestions)

    /// Netflix-style autocomplete: a genre whose name starts with what's typed ("hor" → Horror), then recent searches
    /// and titles that match, prefix matches first, no repeats.
    private var suggestions: [String] {
        let q = trimmed
        guard !q.isEmpty else { return [] }
        func prefix(_ s: String) -> Bool {
            s.range(of: q, options: [.caseInsensitive, .anchored]) != nil
                || s.split(whereSeparator: { " :-".contains($0) }).contains { $0.range(of: q, options: [.caseInsensitive, .anchored]) != nil }
        }
        let titles = (searched == q ? results.titles : quick).map(\.name)
        let all = recent.filter { $0.localizedCaseInsensitiveContains(q) && $0.caseInsensitiveCompare(q) != .orderedSame } + titles
        var seen = Set<String>()
        return (genreHint + all.filter(prefix) + all.filter { !prefix($0) })
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty && $0.caseInsensitiveCompare(q) != .orderedSame && seen.insert($0.lowercased()).inserted }
            .prefix(SearchLook.maxSuggest).map { $0 }
    }

    @ViewBuilder private var suggestionLine: some View {
        let list = suggestions
        if !list.isEmpty {
            ScrollView(.horizontal) {
                HStack(spacing: 10) {
                    ForEach(list, id: \.self) { s in
                        Button { pickSuggestion(s) } label: { highlighted(s, trimmed) }
                            .buttonStyle(ChipButtonStyle())
                            .accessibilityLabel(s)
                    }
                }
                .padding(.vertical, 4)
            }
            .contentMargins(.horizontal, Cinema.gutter, for: .scrollContent)
            .scrollIndicators(.hidden)
            .scrollClipDisabled()
        }
    }

    /// The typed part stays as it is; the rest of the suggestion is bold (what picking it adds).
    private func highlighted(_ s: String, _ q: String) -> Text {
        guard !q.isEmpty, let r = s.range(of: q, options: .caseInsensitive) else { return Text(s).bold() }
        return Text(String(s[s.startIndex..<r.lowerBound])).bold() + Text(String(s[r])) + Text(String(s[r.upperBound...])).bold()
    }

    private func pickSuggestion(_ s: String) {
        RecentSearches.add(s)
        recent = RecentSearches.list()
        query = s
        focused = false
    }

    // MARK: Empty field

    @ViewBuilder private var idle: some View {
        if !recent.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Kicker("Recent", color: Palette.textSecondary)
                    Spacer()
                    Button("Clear") { RecentSearches.clear(); recent = [] }
                        .font(.footnote.weight(.semibold)).foregroundStyle(Palette.textSecondary)
                        .buttonStyle(.plain)
                }
                .padding(.horizontal, Cinema.gutter)
                ForEach(recent, id: \.self) { q in
                    Button { query = q } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "clock.arrow.circlepath").font(.subheadline).foregroundStyle(Palette.textSecondary)
                            Text(q).font(.body).foregroundStyle(Palette.text).lineLimit(1)
                            Spacer(minLength: 8)
                            Image(systemName: "arrow.up.left").font(.footnote.weight(.semibold)).foregroundStyle(Palette.textTertiary)
                        }
                        .padding(.horizontal, Cinema.gutter)
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(RowPressStyle())
                }
            }
        }
        if trending.isEmpty {
            EmptyState(systemImage: "magnifyingglass", title: "Find Something to Watch",
                       message: "Type a movie, a show, a person or a genre, and results appear as you type.")
        } else {
            section("Trending Now", trending)
        }
    }

    // MARK: Results (CinemaSearch.showResults)

    @ViewBuilder private func live(_ layout: CinemaLayout) -> some View {
        let done = searched == trimmed
        let r = done ? results : SearchResults(titles: quick)
        let cols = layout.columns(104, min: 3).count
        let genre = r.genre.flatMap { $0.movies.count + $0.shows.count >= SearchLook.genreMin ? $0 : nil }
        if let genre {
            // The genre leads, unless a title is named exactly what was typed: then that title does.
            let exact = r.titles.first.flatMap { SearchLook.squash($0.name) == SearchLook.squash(trimmed) ? $0 : nil }
            let rest = exact != nil ? Array(r.titles.dropFirst(cols)) : r.titles
            VStack(alignment: .leading, spacing: 26) {
                if exact != nil { section("Top Results", Array(r.titles.prefix(cols))) }
                genreSection(genre, cols: cols)
                if let p = r.person, !p.titles.isEmpty { section(p.heading, Array(p.titles.prefix(SearchLook.maxTiles / 2))) }
                if !rest.isEmpty { section(exact != nil ? "More Results" : "Top Results", Array(rest.prefix(SearchLook.maxTiles / 2))) }
            }
        } else if let p = r.person, !p.titles.isEmpty {
            VStack(alignment: .leading, spacing: 26) {
                section(p.heading, Array(p.titles.prefix(SearchLook.maxTiles)))
                if !r.titles.isEmpty { section("Top Results", Array(r.titles.prefix(SearchLook.maxTiles / 2))) }
            }
        } else if !r.titles.isEmpty {
            section("Top Results", Array(r.titles.prefix(SearchLook.maxTiles)))
        } else if !done || searching {
            HStack(spacing: 10) {
                ProgressView().tint(Palette.textSecondary)
                Text("Searching…").font(.subheadline.weight(.medium)).foregroundStyle(Palette.textSecondary)
            }
            .padding(.horizontal, Cinema.gutter)
        } else {
            EmptyState(systemImage: "magnifyingglass", title: "No Matches for “\(trimmed)”", message: "Try fewer letters, or another spelling.")
        }
    }

    /// A genre search's rows: "Browse Horror" chips into its Browse page (both, for a pair), then its movies and its
    /// shows under their own headings. Two lines of each when both show; four when the search asked for one type.
    private func genreSection(_ g: SearchResults.GenreHits, cols: Int) -> some View {
        let both = !g.movies.isEmpty && !g.shows.isEmpty
        let n = (both ? 2 : 4) * cols
        return VStack(alignment: .leading, spacing: 26) {
            VStack(alignment: .leading, spacing: Cinema.rowTitleGap + 4) {
                RowHeader(g.label)
                if !g.browse.isEmpty {
                    ScrollView(.horizontal) {
                        HStack(spacing: 10) {
                            ForEach(g.browse) { spec in
                                Button {
                                    remember(trimmed)
                                    shell.push(.page(.genre(spec)))
                                } label: {
                                    HStack(spacing: 8) {
                                        Text("Browse \(spec.name)")
                                        Image(systemName: "chevron.right").font(.caption.weight(.bold))
                                    }
                                }
                                .buttonStyle(ChipButtonStyle(selected: true))
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    .contentMargins(.horizontal, Cinema.gutter, for: .scrollContent)
                    .scrollIndicators(.hidden)
                    .scrollClipDisabled()
                }
            }
            if !g.movies.isEmpty { section(g.movieHeading, Array(g.movies.prefix(n))) }
            if !g.shows.isEmpty { section(g.showHeading, Array(g.shows.prefix(n))) }
        }
    }

    private func section(_ heading: String, _ titles: [Title]) -> some View {
        VStack(alignment: .leading, spacing: Cinema.rowTitleGap + 4) {
            RowHeader(heading)
            PosterGrid(titles, open: openTitle, menu: shell.menu(for:))
        }
    }

    private func openTitle(_ t: Title) {
        remember(trimmed)
        shell.open(t)
    }

    private func remember(_ q: String) {
        guard q.count >= 2 else { return }
        RecentSearches.add(q)
        recent = RecentSearches.list()
    }

    /// Debounced: a search runs once typing rests for a moment; an older answer never replaces a newer one. TMDB's
    /// plain title search answers first (Top Results and the suggestions), then the whole search (a person, a genre).
    private func run(_ q: String) async {
        // A search cut short by the next keystroke left this on: "Searching…" stayed under a one-letter query for good.
        searching = false
        guard !q.isEmpty else { results = SearchResults(titles: []); quick = []; genreHint = []; searched = ""; return }
        let c = Services.shared.catalog
        // Genre names suggest at once.
        genreHint = Array(await c.suggestions(q).prefix(1))
        quick = quick.filter { $0.name.localizedCaseInsensitiveContains(q) }
        guard q.count >= 2 else { results = SearchResults(titles: []); searched = q; return }
        try? await Task.sleep(for: .milliseconds(160))
        if Task.isCancelled { return }
        searching = true
        async let all = c.searchAll(q)
        let first = (try? await c.search(q)) ?? []
        if Task.isCancelled { return }
        if searched != q, !first.isEmpty { withAnimation(.easeOut(duration: 0.18)) { quick = Array(first.prefix(60)) } }
        let full = await all
        if Task.isCancelled { return }
        // Their pills: stored answers show at once (the cards' memory, seeded at launch); the cards on screen ask
        // for theirs first, and the settled results (not every keystroke's) are checked ahead for the scroll.
        let ahead = Array(full.titles.prefix(36))
        Task(priority: .background) { await PillAhead.ask(ahead) }
        withAnimation(.easeOut(duration: 0.18)) {
            results = full
            searched = q
            searching = false
        }
    }
}

/// CinemaSearch's numbers for a phone.
enum SearchLook {
    /// A genre search leads with its rows only with at least this many titles between movies and shows.
    static let genreMin = 5
    static let maxTiles = 24
    static let maxSuggest = 4
    static func squash(_ s: String) -> String { s.lowercased().filter { $0.isLetter || $0.isNumber } }
}

/// A list row's press: a soft pearl wash.
struct RowPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? Color.white.opacity(0.10) : .clear)
            .animation(Cinema.pressAnimation, value: configuration.isPressed)
    }
}

/// Recent searches, newest first (RecentSearches.kt), per profile, eight at most.
enum RecentSearches {
    private static var key: String { "DreamOS.recentSearches." + DreamStore.shared.profileId }
    static func list() -> [String] { UserDefaults.standard.stringArray(forKey: key) ?? [] }
    static func add(_ q: String) {
        let t = q.trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.count >= 2 else { return }
        var l = list().filter { $0.caseInsensitiveCompare(t) != .orderedSame }
        l.insert(t, at: 0)
        UserDefaults.standard.set(Array(l.prefix(8)), forKey: key)
    }
    static func clear() { UserDefaults.standard.removeObject(forKey: key) }
}
