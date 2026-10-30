import SwiftUI
import DreamCore
import DreamUI

/// The Dream Cinema title page for a phone (CinemaDetails.kt): the backdrop and title logo, one meta line with the
/// scores, the availability line, Play / Resume / Start Over (where playback starts is the watch history's call),
/// My List and Trailer, the synopsis, Episodes, More Like This, Cast, Information and Where to Watch.
/// The TV's stage + sliding sheet becomes one scrolling page; focus bands become touch.
struct TitleDetailsView: View {
    let title: Title
    @State private var model: TitleDetailsModel
    @State private var message: String?
    @State private var expanded = false
    @State private var showEpisodes = false
    @State private var heroTrailer = HeroTrailerModel()
    /// Bumped by the My List button: the mark animates only for a tap (MyListMark).
    @State private var myListTaps = 0
    /// The bar's black glass: up by the time the text under the hero reaches Back.
    @State private var bar = BarGlassModel(lead: 60, fade: 60)
    @Environment(\.verticalSizeClass) private var vSize
    @Environment(\.scenePhase) private var phase

    init(title: Title) {
        self.title = title
        _model = State(initialValue: TitleDetailsModel(title: title))
    }

    var body: some View {
        GeometryReader { geo in
            let wide = geo.size.width > 600
            let gutter: CGFloat = wide ? 32 : 20
            let cardW = EpisodeCard.width(screen: geo.size, insets: geo.safeAreaInsets)
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        hero(width: geo.size.width, height: geo.size.height, topInset: geo.safeAreaInsets.top)
                        VStack(alignment: .leading, spacing: 0) {
                            headline
                            actions(proxy: proxy)
                            synopsis
                            if model.isShow { episodes(gutter: gutter, cardW: cardW).id("episodes") }
                            more
                            cast
                            information(cols: wide ? 4 : 2).id("information")
                            whereToWatch
                            if let f = model.failed, model.details == nil { failure(f) }
                        }
                        .barGlassMarker(bar)
                        .frame(maxWidth: 820, alignment: .leading)
                        .padding(.horizontal, gutter)
                        .frame(maxWidth: .infinity)
                        .padding(.bottom, 48)
                        // The trailer has the screen (Fire Stick HeroTrailer): the page fades out, a touch wakes it.
                        .opacity(heroTrailer.immersive ? 0 : 1)
                        .allowsHitTesting(!heroTrailer.immersive)
                        .animation(.easeInOut(duration: heroTrailer.immersive ? 0.9 : 0.32), value: heroTrailer.immersive)
                    }
                }
                .scrollIndicators(.hidden)
                .simultaneousGesture(TapGesture().onEnded { heroTrailer.wake() })
                .simultaneousGesture(DragGesture(minimumDistance: 8).onChanged { _ in heroTrailer.wake() })
                .ignoresSafeArea(edges: .top)
            }
        }
        .background(DreamBackground())
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .principal) { BarGlassTitle(bar: bar, text: model.shown.name) } }
        .barGlass(bar)
        .task { await model.load() }
        .onAppear { Task { await model.refreshHistory() } }
        .onChange(of: phase) { _, p in if p == .active { Task { await model.refreshHistory() } } }
        .onReceive(NotificationCenter.default.publisher(for: .dreamHistoryChanged)) { _ in Task { await model.refreshHistory() } }
        .onReceive(NotificationCenter.default.publisher(for: .dreamMyListChanged)) { _ in
            Task { model.inMyList = await Services.shared.myList.contains(title.key) }
        }
        .alert("Not Ready to Play", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) { message = nil }
        } message: {
            Text(message ?? "")
        }
    }

    // MARK: Hero

    private func hero(width: CGFloat, height: CGFloat, topInset: CGFloat) -> some View {
        // Portrait: the 16:9 backdrop plus the status bar; landscape: never more than about 70% of the screen.
        let h = min(width * 9 / 16 + topInset + 40, max(260, (height + topInset) * (vSize == .compact ? 0.72 : 0.62)))
        let backdrop = model.shown.backdrop("w1280") ?? model.details?.title.backdrop("w1280")
        return ZStack(alignment: .bottomLeading) {
            // Through ImageStore (press-down already fetched it ahead), so the episode cards can show it at once
            // while a still loads, and it never flashes in on a page opened again.
            RemoteImage([backdrop]) { Palette.surface }
                .frame(width: width, height: h)
                .clipped()
            HeroTrailerLayer(trailer: heroTrailer, title: model.shown, key: model.details?.trailerKey, width: width, height: h)
            LinearGradient(stops: [
                .init(color: .black.opacity(0.35), location: 0),
                .init(color: .clear, location: 0.28),
                .init(color: .black.opacity(0.55), location: 0.7),
                .init(color: Palette.page, location: 1),
            ], startPoint: .top, endPoint: .bottom)
            .opacity(heroTrailer.immersive ? 0 : 1)
            logo
                .padding(.horizontal, width > 600 ? 32 : 20)
                .padding(.bottom, 6)
                .opacity(heroTrailer.immersive ? 0 : 1)
            HeroTrailerSound(trailer: heroTrailer)
                .frame(maxWidth: .infinity, alignment: .trailing)
                .padding(.trailing, width > 600 ? 24 : 12)
                .opacity(heroTrailer.immersive ? 0 : 1)
                .allowsHitTesting(!heroTrailer.immersive)
        }
        .animation(.easeInOut(duration: heroTrailer.immersive ? 0.9 : 0.32), value: heroTrailer.immersive)
        .frame(width: width, height: h)
    }

    @ViewBuilder private var logo: some View {
        if let url = model.details?.logo("w500") {
            AsyncImage(url: url, transaction: Transaction(animation: .easeOut(duration: 0.28))) { p in
                if case .success(let img) = p {
                    img.resizable().scaledToFit().frame(maxWidth: 280, maxHeight: 96, alignment: .bottomLeading)
                } else {
                    typedTitle.opacity(p.error == nil ? 0 : 1)
                }
            }
            .frame(maxWidth: 280, maxHeight: 96, alignment: .bottomLeading)
            .accessibilityLabel(model.shown.name)
        } else {
            typedTitle
        }
    }

    private var typedTitle: some View {
        Text(model.shown.name)
            .font(.system(size: model.shown.name.count > 24 ? 28 : 34, weight: .bold))
            .tracking(-0.3)
            .foregroundStyle(Palette.text)
            .lineLimit(2)
            .minimumScaleFactor(0.8)
            .shadow(color: .black.opacity(0.5), radius: 8)
    }

    // MARK: Meta, status

    private var headline: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Every part whole (STANDARDS §3): a part that doesn't fit starts the next line, and a dot never starts or
            // ends a line (MetaFlow leaves those out).
            MetaFlow(spacing: 9, lineSpacing: 8) {
                ForEach(Array(metaParts.enumerated()), id: \.offset) { i, part in
                    if i > 0 { MetaDot(color: Palette.textTertiary) }
                    part
                }
            }
            if WatchedState.shared.title(model.shown) {
                // Watched replaces the availability pill; a show keeps its next-episode note beside it (or under it
                // when the line is short; the note wraps whole, never cut).
                MetaFlow(spacing: 12, lineSpacing: 8) {
                    WatchedPill(large: true)
                    if model.isShow, let note = model.availability?.note, !note.isEmpty { statusNote(note) }
                    languagePills
                }
            } else if let a = model.shownAvailability {
                MetaFlow(spacing: 12, lineSpacing: 8) {
                    DetailStatusPill(state: a.state, text: a.pageLabels.first, large: true)
                    if let note = a.note, !note.isEmpty { statusNote(note) }
                    languagePills
                }
            } else if !model.languagePills.isEmpty {
                MetaFlow(spacing: 12, lineSpacing: 8) { languagePills }
            }
        }
        .padding(.top, 14)
        .animation(.easeOut(duration: 0.24), value: model.scores)
        .animation(.easeOut(duration: 0.24), value: model.languagePills)
    }

    /// What Play plays when it isn't in the viewer's audio language (LanguagePills); none for a match.
    private var languagePills: some View {
        ForEach(model.languagePills) { LanguagePill($0).transition(.opacity) }
    }

    private func statusNote(_ note: String) -> some View {
        Text(note).font(.system(size: 13.5, weight: .medium)).foregroundStyle(Palette.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var metaParts: [AnyView] {
        var out: [AnyView] = []
        func text(_ s: String) -> AnyView {
            AnyView(Text(s).font(.system(size: 14, weight: .medium)).foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true))
        }
        if let y = model.metaYear { out.append(text(y)) }
        if let c = model.cert { out.append(AnyView(CertBox(cert: c))) }
        if let l = model.metaLength { out.append(text(l)) }
        if let g = model.metaGenres { out.append(text(g)) }
        if let s = model.scores, s.tomatometer != nil || s.audienceBadge != nil {
            out.append(AnyView(ScoreBadges(s, font: .system(size: 14, weight: .medium))))
        }
        return out
    }

    // MARK: Actions

    private func actions(proxy: ScrollViewProxy) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            PagePrimaryButton(title: model.playLabel, systemImage: "play.fill") { play(model.playRequest) }
            if let words = model.progressWords, let e = model.resumeEntry {
                HStack(spacing: 12) {
                    ProgressTrack(value: e.progress).frame(width: 120, height: 4)
                    Text(words).font(.system(size: 13, weight: .medium)).foregroundStyle(Palette.textSecondary).lineLimit(1)
                }
                .padding(.vertical, 2)
                PageSecondaryButton(title: "Start Over", systemImage: "arrow.counterclockwise") {
                    var r = model.playRequest
                    r.fromStart = true
                    play(r)
                }
            }
            HStack(spacing: 0) {
                ActionIcon(symbol: model.inMyList ? "checkmark" : "plus", label: model.inMyList ? "Added" : "My List",
                           widest: "My List", lit: model.inMyList,
                           glyph: AnyView(MyListMark(saved: model.inMyList, motion: myListTaps))) {
                    myListTaps += 1
                    Task { await model.toggleMyList() }
                }
                if let key = model.details?.trailerKey, let playTrailer = PagesServices.playTrailer {
                    ActionIcon(symbol: "film", label: "Trailer") { HeroTrailerLayer.openFull(model.shown, key: key, fallback: playTrailer) }
                }
                if model.isShow && !model.allSeasons.isEmpty {
                    ActionIcon(symbol: "list.bullet", label: "Episodes") {
                        withAnimation(.easeInOut(duration: 0.4)) { proxy.scrollTo("episodes", anchor: .top) }
                    }
                }
                // The Fire Stick's Information tab: release dates, run time, rating, people, studios, money.
                if model.details?.info != nil {
                    ActionIcon(symbol: "info.circle", label: "Info") {
                        withAnimation(.easeInOut(duration: 0.4)) { proxy.scrollTo("information", anchor: .top) }
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.top, 8)
        }
        .padding(.top, 18)
    }

    private func play(_ r: PlayRequest) {
        HeroTrailerLayer.stopAll()
        PlayLauncher.play(r) { message = $0 }
    }

    // MARK: Synopsis

    @ViewBuilder private var synopsis: some View {
        let text = (model.details?.title.overview).flatMap { $0.isEmpty ? nil : $0 } ?? (title.overview.isEmpty ? nil : title.overview)
        let tagline = model.details?.info?.tagline ?? ""
        if text != nil || !tagline.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                if !tagline.isEmpty {
                    Text(tagline).font(.system(size: 15, weight: .semibold)).foregroundStyle(Palette.text).lineLimit(2)
                }
                if let text {
                    Text(text)
                        .font(.system(size: 15))
                        .foregroundStyle(Palette.text.opacity(0.84))
                        .lineSpacing(3)
                        .lineLimit(expanded ? nil : 3)
                        .fixedSize(horizontal: false, vertical: true)
                        .onTapGesture { withAnimation(.easeOut(duration: 0.25)) { expanded.toggle() } }
                        .accessibilityAddTraits(.isButton)
                        .accessibilityHint(expanded ? "Shows less" : "Shows the whole description")
                    if !expanded && text.count > 160 {
                        Button("More") { withAnimation(.easeOut(duration: 0.25)) { expanded = true } }
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Palette.text)
                    }
                }
            }
            .padding(.top, 22)
        }
    }

    // MARK: Episodes

    private func episodes(gutter: CGFloat, cardW: CGFloat) -> some View {
        let seasons = model.allSeasons.uniqued()
        return Group {
            if !seasons.isEmpty {
                VStack(alignment: .leading, spacing: 0) {
                    DetailsHeading(text: "Episodes")
                    SeasonPicker(seasons: seasons, selected: model.season, ahead: model.seasonsAhead,
                                 loaded: model.episodes[model.season]?.count) { n in
                        model.pick(season: n)
                    }
                    .padding(.top, 14)
                    episodeList(gutter: gutter, cardW: cardW)
                        .padding(.top, 18)
                }
                .padding(.top, 30)
            }
        }
    }

    @ViewBuilder private func episodeList(gutter: CGFloat, cardW: CGFloat) -> some View {
        let n = model.season
        if let eps = model.episodes[n] {
            let shown = eps.uniqued().sorted { $0.number < $1.number }
            if shown.isEmpty {
                let ahead = model.seasonsAhead.first { $0.number == n }
                Text(ahead.map { s in "Season \(s.number)  ·  " + (PageTime.shortDate(s.airDate).map { "Coming \($0)" } ?? "Coming Soon") }
                     ?? "No episodes of Season \(n) to watch yet.")
                    .font(.system(size: 15)).foregroundStyle(Palette.textSecondary)
                    .padding(.vertical, 12)
            } else {
                EpisodeRail(episodes: shown, cardW: cardW, gutter: gutter,
                            target: shown.first { model.isUpNext($0) }?.id) { ep in
                    EpisodeCard(episode: ep,
                                width: cardW,
                                upcoming: ep.upcoming || (!ep.outEarly && !PageTime.aired(ep.airDate)),
                                progress: model.progress(for: ep),
                                upNext: model.isUpNext(ep),
                                pill: model.progress(for: ep) == nil ? model.episodePills[ep.id] : nil,
                                watched: model.progress(for: ep) == nil && !ep.upcoming
                                    && WatchedState.shared.episode(show: model.title.id, season: ep.season, episode: ep.number),
                                fallback: [model.shown.backdrop("w1280")] + model.episodeFallbackArt(season: n),
                                language: model.languageWords(for: ep)) {
                        play(model.request(for: ep))
                    }
                }
                .id("season-\(n)")
                .transition(.opacity)
            }
        } else if model.seasonFailed.contains(n) {
            HStack(spacing: 14) {
                Text("Couldn't load this season.").font(.system(size: 15)).foregroundStyle(Palette.textSecondary)
                Button("Try Again") { Task { await model.loadSeason(n) } }.buttonStyle(PearlButtonStyle())
            }
            .padding(.vertical, 8)
        } else {
            EpisodeRailSkeleton(cardW: cardW, gutter: gutter)
        }
    }

    // MARK: More Like This, Cast

    @ViewBuilder private var more: some View {
        let recs = model.details?.recommendations ?? []
        if !recs.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                DetailsHeading(text: "More Like This")
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 12) {
                        ForEach(Array(recs.uniqued().prefix(24))) { t in
                            NavigationLink { TitleDetailsView(title: t) } label: { PosterTile(title: t, width: 116) }
                                .buttonStyle(CardPressStyle())
                                .simultaneousGesture(TapGesture().onEnded { PageHaptics.tap() })
                        }
                    }
                    .padding(.vertical, 6)
                    .padding(.horizontal, 2)
                }
                .scrollIndicators(.hidden)
            }
            .padding(.top, 32)
        }
    }

    @ViewBuilder private var cast: some View {
        let people = model.details?.cast ?? []
        if !people.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                DetailsHeading(text: "Cast")
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 14) {
                        ForEach(Array(people.uniqued().prefix(15))) { c in
                            NavigationLink { PersonView(person: c, fromTitle: model.shown.key) } label: { CastTile(member: c) }
                                .buttonStyle(CardPressStyle(radius: 46))
                                .simultaneousGesture(TapGesture().onEnded { PageHaptics.tap() })
                        }
                    }
                    .padding(.vertical, 6)
                    .padding(.horizontal, 2)
                }
                .scrollIndicators(.hidden)
            }
            .padding(.top, 30)
        }
    }

    // MARK: Information, Where to Watch

    @ViewBuilder private func information(cols: Int) -> some View {
        if let info = model.details?.info {
            let cells = InfoWords.cleaned(info).cells(scores: model.scores, episodeMin: model.episodeMinutes)
                .filter { $0.label != "Where to Watch" }
            if !cells.isEmpty {
                VStack(alignment: .leading, spacing: 18) {
                    DetailsHeading(text: "Information")
                    InfoGridView(cells: cells, cols: cols)
                }
                .padding(.top, 32)
            }
        }
    }

    @ViewBuilder private var whereToWatch: some View {
        if let info = model.details?.info {
            let clean = InfoWords.cleaned(info)
            let groups: [(String, [String])] = [
                ("Streaming", clean.streaming), ("Free", clean.free.filter { !clean.streaming.contains($0) }), ("Rent or Buy", clean.rentBuy),
            ].filter { !$0.1.isEmpty }
            if !groups.isEmpty {
                VStack(alignment: .leading, spacing: 14) {
                    DetailsHeading(text: "Where to Watch")
                    ForEach(groups, id: \.0) { g in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(g.0.uppercased())
                                .font(.system(size: 11, weight: .semibold)).tracking(1.3)
                                .foregroundStyle(Palette.textSecondary)
                            WrapLayout(spacing: 8, lineSpacing: 8) {
                                ForEach(g.1.prefix(8), id: \.self) { name in
                                    Text(name)
                                        .font(.system(size: 13.5, weight: .medium))
                                        .foregroundStyle(Palette.text.opacity(0.9))
                                        .padding(.horizontal, 12).padding(.vertical, 7)
                                        .background(Capsule().fill(Color.white.opacity(0.07)))
                                        .overlay(Capsule().strokeBorder(Color.white.opacity(0.10), lineWidth: 1))
                                }
                            }
                        }
                    }
                    JustWatchCredit().padding(.top, 2)
                }
                .padding(.top, 32)
            }
        }
    }

    private func failure(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(text).font(.system(size: 15)).foregroundStyle(Palette.textSecondary)
            Button("Try Again") { Task { await model.load() } }.buttonStyle(PearlButtonStyle())
        }
        .padding(.top, 28)
    }
}

// MARK: Small parts

/// A thin progress bar: pearl fill on a faint track (static, nothing animates at rest).
struct ProgressTrack: View {
    var value: Double
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Palette.text.opacity(0.22))
                Capsule().fill(Palette.pearl).frame(width: g.size.width * min(1, max(0.03, value)))
            }
        }
        .accessibilityHidden(true)
    }
}

/// An icon over a label (My List, Trailer, Episodes), Netflix style. The label keeps the wider word's width.
struct ActionIcon: View {
    var symbol: String
    var label: String
    var widest: String? = nil
    var lit = false
    /// A drawn glyph in place of the symbol (My List's MyListMark).
    var glyph: AnyView? = nil
    var action: () -> Void
    var body: some View {
        Button {
            PageHaptics.tap()
            action()
        } label: {
            VStack(spacing: 8) {
                if let glyph {
                    glyph.frame(height: 24)
                } else {
                    Image(systemName: symbol)
                        .font(.system(size: 21, weight: .semibold))
                        .frame(height: 24)
                        .contentTransition(.symbolEffect(.replace))
                }
                ZStack {
                    if let widest { Text(widest).hidden() }
                    Text(label)
                }
                .font(.system(size: 12, weight: .medium))
                .lineLimit(1)
            }
            .frame(minWidth: 76, minHeight: 58)
            .padding(.horizontal, 4)
        }
        .buttonStyle(ActionIconStyle())
        .accessibilityLabel(label)
    }
}

private struct ActionIconStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(configuration.isPressed ? Palette.ink : Palette.text)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(configuration.isPressed ? Palette.pearl : .clear))
            .rainbowBorder(configuration.isPressed, radius: 12, width: 2)
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(.easeOut(duration: 0.14), value: configuration.isPressed)
            .environment(\.myListMarkOnPearl, configuration.isPressed)
    }
}

// MARK: Seasons

/// The season picker: one glass button naming the season on show with a chevron, opening Dream's own season menu
/// (`DreamMenu`); the season's episode count on the right. A show with one season shows its name alone.
struct SeasonPicker: View {
    var seasons: [SeasonSummary]
    var selected: Int
    var ahead: [SeasonSummary]
    /// Episodes loaded for the season on show (nil: not loaded yet, TMDB's count is used).
    var loaded: Int?
    var onPick: (Int) -> Void

    var body: some View {
        let current = seasons.first { $0.number == selected } ?? seasons[0]
        HStack(alignment: .center, spacing: 14) {
            if seasons.count > 1 {
                DreamPickerButton(heading: "Seasons", items: menuItems, selected: "\(selected)",
                                  label: Self.name(current), accessibilityHint: "Chooses a season") { id in
                    if let n = Int(id) { onPick(n) }
                }
            } else {
                Text(Self.name(current))
                    .font(.system(size: 16, weight: .semibold)).foregroundStyle(Palette.text).lineLimit(1)
            }
            Spacer(minLength: 8)
            Text(Self.count(current, ahead: ahead, loaded: loaded))
                .font(.system(size: 13, weight: .medium)).foregroundStyle(Palette.textSecondary)
                .lineLimit(1)
        }
    }

    /// Dream's season menu (DreamMenu): every season with its episode count; announced ones say when they come.
    private var menuItems: [DreamMenuItem] {
        seasons.uniqued().map { s in
            DreamMenuItem(id: "\(s.number)", label: Self.name(s),
                          detail: Self.count(s, ahead: ahead, loaded: s.number == selected ? loaded : nil))
        }
    }

    /// "Season 2", or the season's own name when it has a short one ("Limited Series").
    static func name(_ s: SeasonSummary) -> String {
        let n = s.name
        return (!n.isEmpty && !n.hasPrefix("Season") && n.count <= 22) ? n : "Season \(s.number)"
    }

    static func coming(_ s: SeasonSummary) -> String {
        PageTime.shortDate(s.airDate).map { "Coming \($0)" } ?? "Coming Soon"
    }

    /// "10 Episodes", or "Coming Mar 4" for an announced season with nothing listed yet. `loaded` is the season on
    /// show's real count (nil for the others: TMDB's).
    static func count(_ s: SeasonSummary, ahead: [SeasonSummary], loaded: Int?) -> String {
        if ahead.contains(s) && (loaded ?? 0) == 0 { return coming(s) }
        let n = loaded ?? s.episodeCount
        return n == 1 ? "1 Episode" : "\(n) Episodes"
    }
}

// MARK: Episodes

/// A season's episodes as a side-scroll (Netflix / Apple TV): cards snap to the page's left line, the next card peeks
/// in at the right, the page gutter at both ends. Opens on the Up Next / Resume episode.
struct EpisodeRail<Card: View>: View {
    let episodes: [Episode]
    let cardW: CGFloat
    let gutter: CGFloat
    /// The Up Next / Resume episode's id (nil: start at the first).
    let target: String?
    let card: (Episode) -> Card

    /// Room inside the scroller for the pressed card's rainbow glow.
    private let glow: CGFloat = 12
    /// The card on the left line (the scroll-target system keeps it on the gutter, like a swipe's snap).
    @State private var pos: String?

    init(episodes: [Episode], cardW: CGFloat, gutter: CGFloat, target: String?, @ViewBuilder card: @escaping (Episode) -> Card) {
        self.episodes = episodes; self.cardW = cardW; self.gutter = gutter; self.target = target; self.card = card
    }

    var body: some View {
        ScrollView(.horizontal) {
            // A plain HStack: a season is a few dozen cards at most, and the row's height must not change as cards
            // scroll in.
            HStack(alignment: .top, spacing: EpisodeCard.gap) {
                ForEach(episodes) { ep in card(ep) }
            }
            .scrollTargetLayout()
            .padding(.vertical, glow)
        }
        .contentMargins(.horizontal, gutter, for: .scrollContent)
        .scrollTargetBehavior(.viewAligned)
        .scrollPosition(id: $pos)
        .scrollIndicators(.hidden)
        .padding(.vertical, -glow)
        .padding(.horizontal, -gutter)
        .onAppear {
            guard let t = target, t != episodes.first?.id else { return }
            DispatchQueue.main.async { pos = t }
        }
        .onChange(of: target) { _, t in
            guard let t else { return }
            withAnimation(.easeInOut(duration: 0.35)) { pos = t }
        }
    }
}

/// One episode card: the 16:9 still (progress along its foot once started, dimmed before it airs; rainbow border while
/// pressed), then "4. Name" (two lines at most), the runtime and air date led by Up Next or Resume, the status pill
/// for a recent episode (the 21-day rule lives in the model), and two lines of synopsis.
struct EpisodeCard: View {
    var episode: Episode
    var width: CGFloat
    var upcoming: Bool
    var progress: Double?
    var upNext: Bool
    var pill: Availability?
    /// The viewer watched it (WatchedLedger): the gold Watched pill instead of the status pill.
    var watched = false
    /// Art when TMDB has no still for this episode (or it won't load): the show's backdrop, the season's poster.
    var fallback: [URL?] = []
    /// "Japanese Audio" once Dream Core has read this episode's copies and they don't carry the viewer's language (LanguagePills).
    var language: String? = nil
    var play: () -> Void

    static let gap: CGFloat = 14
    /// The still's TMDB size (the model fetches the same one ahead).
    static let stillSize = "w500"

    /// A fixed card width: about 1.3 cards across a portrait phone (so the next one peeks in), more in landscape.
    /// Taken from the screen's short side so it is the same width either way round.
    static func width(screen: CGSize, insets: EdgeInsets) -> CGFloat {
        let w = screen.width + insets.leading + insets.trailing
        let h = screen.height + insets.top + insets.bottom
        let short = min(w, h)
        let gutter: CGFloat = short > 600 ? 32 : 20
        return min(320, max(230, ((short - gutter - gap) / 1.3).rounded()))
    }

    var body: some View {
        let stillH = (width * 9 / 16).rounded()
        Button {
            PageHaptics.tap()
            play()
        } label: {
            VStack(alignment: .leading, spacing: 0) {
                CardFrame(width: width, height: stillH) {
                    RemoteImage([episode.still(Self.stillSize), episode.still("w300")] + fallback) {
                        Text("E\(episode.number)")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(Palette.textSecondary)
                    }
                    if upcoming { Color.black.opacity(0.55) }
                    if let progress, !upcoming {
                        VStack {
                            Spacer(minLength: 0)
                            CardProgress(progress).padding(.horizontal, 10).padding(.bottom, 9)
                        }
                    }
                }
                Text("\(episode.number). \(episode.name.isEmpty ? "Episode \(episode.number)" : episode.name)")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Palette.text.opacity(upcoming ? 0.74 : 1))
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)
                meta
                    .font(.system(size: 12.5))
                    .foregroundStyle(Palette.textSecondary)
                    .lineLimit(1)
                    .padding(.top, 4)
                if watched, progress == nil {
                    WatchedPill().padding(.top, 8)
                } else if let pill, progress == nil {
                    AvailabilityPill(pill).padding(.top, 8)
                }
                if !episode.overview.isEmpty {
                    Text(episode.overview)
                        .font(.system(size: 13.5))
                        .foregroundStyle(Palette.text.opacity(0.72))
                        .lineSpacing(2)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 8)
                }
            }
            .frame(width: width, alignment: .leading)
            .accessibilityElement(children: .combine)
        }
        .buttonStyle(CardButtonStyle())
        .disabled(upcoming)
        .accessibilityHint(upcoming ? "" : "Plays this episode")
    }

    /// "Up Next  ·  47 min  ·  Oct 12" (the tag in pearl), or "Coming Fri 9 PM".
    private var meta: Text {
        let line = subline
        guard upNext && !upcoming else { return Text(line) }
        let tag = Text(progress != nil ? "Resume" : "Up Next").foregroundStyle(Palette.pearl).fontWeight(.semibold)
        return line.isEmpty ? tag : Text("\(tag)\(Cinema.dot)\(line)")
    }

    private var subline: String {
        if upcoming {
            if let at = episode.airsAt { return "Coming " + DreamTime.airLabel(at) }
            return PageTime.shortDate(episode.airDate).map { "Coming \($0)" } ?? "Coming Soon"
        }
        // Up early (its air day still ahead): no date from the future.
        let aired = episode.outEarly && !PageTime.aired(episode.airDate) ? nil : PageTime.shortDate(episode.airDate)
        return [InfoWords.runtime(episode.runtime), aired, language].compactMap { $0 }.joined(separator: Cinema.dot)
    }
}

/// Quiet static placeholders while a season loads (no shimmer), the shape of the episode cards.
struct EpisodeRailSkeleton: View {
    var cardW: CGFloat
    var gutter: CGFloat
    var body: some View {
        ScrollView(.horizontal) {
            HStack(alignment: .top, spacing: EpisodeCard.gap) {
                ForEach(0..<3, id: \.self) { _ in
                    VStack(alignment: .leading, spacing: 0) {
                        RoundedRectangle(cornerRadius: Cinema.rCard, style: .continuous).fill(Color.white.opacity(0.06))
                            .frame(width: cardW, height: (cardW * 9 / 16).rounded())
                        RoundedRectangle(cornerRadius: 4).fill(Color.white.opacity(0.06)).frame(width: cardW * 0.62, height: 13).padding(.top, 12)
                        RoundedRectangle(cornerRadius: 4).fill(Color.white.opacity(0.05)).frame(width: cardW * 0.36, height: 11).padding(.top, 8)
                        RoundedRectangle(cornerRadius: 4).fill(Color.white.opacity(0.04)).frame(width: cardW, height: 11).padding(.top, 12)
                        RoundedRectangle(cornerRadius: 4).fill(Color.white.opacity(0.04)).frame(width: cardW * 0.8, height: 11).padding(.top, 6)
                    }
                }
            }
        }
        .contentMargins(.horizontal, gutter, for: .scrollContent)
        .scrollDisabled(true)
        .scrollIndicators(.hidden)
        .padding(.horizontal, -gutter)
        .accessibilityHidden(true)
    }
}

/// A round portrait with the name and role under it.
struct CastTile: View {
    var member: CastMember
    var body: some View {
        VStack(spacing: 0) {
            CastPortrait(member: member, size: 92)
            Text(member.name)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(Palette.text.opacity(0.86))
                .lineLimit(1)
                .padding(.top, 10)
            if !member.character.isEmpty {
                Text(member.character)
                    .font(.system(size: 11.5))
                    .foregroundStyle(Palette.textSecondary)
                    .lineLimit(1)
                    .padding(.top, 3)
            }
        }
        .frame(width: 104)
    }
}

/// A round portrait, or the initials on a faint disc.
struct CastPortrait: View {
    var member: CastMember
    var size: CGFloat
    var body: some View {
        ZStack {
            Circle().fill(Palette.text.opacity(0.08))
            if member.profilePath != nil {
                PageImage(url: member.profile("w185"), radius: size / 2)
            } else {
                Text(initials).font(.system(size: size * 0.24, weight: .semibold)).foregroundStyle(Palette.textSecondary)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
    }
    private var initials: String {
        member.name.split(separator: " ").prefix(2).compactMap { $0.first.map(String.init) }.joined().uppercased()
    }
}
