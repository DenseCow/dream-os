import SwiftUI
import DreamCore

// The hero (CinemaKit.kt CinemaHero) and the Spotlight card (CinemaKit.spotlight), for touch: full-bleed art that
// stretches when the page is pulled down and drifts slower than the page when it scrolls (parallax), the title's logo
// art (or its name), the meta line with scores, two lines of synopsis, Play and More Info. Swipe for the next title:
// the art crossfades, the words slide (as on the TV). Nothing advances by itself and nothing moves at rest.

/// The coordinate space a page's vertical ScrollView names, so the hero can stretch and drift with it.
public extension Cinema {
    static let scrollSpace = "dream.page"
}

// MARK: Details cache (logo art, scores)

/// What the hero and Spotlight add to a list title: its logo art and scores, from `catalog.details` once per title.
public final class TitleExtras: @unchecked Sendable {
    public struct Extras: Sendable, Hashable {
        public var logo: URL?
        public var scores: Scores
    }
    public static let shared = TitleExtras()
    private let lock = NSLock()
    private var cache: [String: Extras] = [:]

    public func cached(_ t: Title) -> Extras? { lock.withLock { cache[t.key] } }

    public func extras(_ t: Title) async -> Extras? {
        if let hit = cached(t) { return hit }
        let catalog = Services.shared.catalog
        guard let d = try? await catalog.details(kind: t.kind, id: t.id) else { return nil }
        let e = Extras(logo: d.logo("w500"), scores: await catalog.scores(for: d))
        lock.withLock { cache[t.key] = e }
        if let logo = e.logo { ImageStore.shared.prefetch(logo) }
        return e
    }
}

/// The title's logo art, fitted into `maxWidth` × `height` at the bottom-left; its name in bold when there is none.
/// It waits a moment for the logo before showing the name (no flash of text).
public struct HeroLogo: View {
    let title: Title
    let maxWidth: CGFloat
    let height: CGFloat
    let font: Font
    @State private var extras: TitleExtras.Extras?
    @State private var showName = false

    public init(_ title: Title, maxWidth: CGFloat, height: CGFloat, font: Font = Cinema.Fonts.heroTitle) {
        self.title = title; self.maxWidth = maxWidth; self.height = height; self.font = font
    }

    public var body: some View {
        let ex = extras ?? TitleExtras.shared.cached(title)
        ZStack(alignment: .bottomLeading) {
            if let logo = ex?.logo, let img = ImageStore.shared.cached(logo) {
                Image(uiImage: img).resizable().aspectRatio(contentMode: .fit)
                    .frame(maxWidth: maxWidth, maxHeight: height, alignment: .bottomLeading)
                    .transition(.opacity)
                    .accessibilityLabel(title.name)
            } else if showName || (ex != nil && ex?.logo == nil) {
                Text(title.name)
                    .font(font).tracking(-0.4)
                    .foregroundStyle(.white)
                    .lineLimit(2).minimumScaleFactor(0.7)
                    .frame(maxWidth: maxWidth, alignment: .bottomLeading)
                    .transition(.opacity)
            }
        }
        .frame(width: maxWidth, height: height, alignment: .bottomLeading)
        .task(id: title.key) {
            showName = false
            let waitName = Task { try? await Task.sleep(for: .milliseconds(700)); if !Task.isCancelled { withAnimation(.easeOut(duration: 0.2)) { showName = true } } }
            let e = await TitleExtras.shared.extras(title)
            if let logo = e?.logo { _ = await ImageStore.shared.image(logo) }
            waitName.cancel()
            withAnimation(.easeOut(duration: 0.26)) { extras = e; if e?.logo == nil { showName = true } }
        }
    }
}

/// The meta line with the title's scores after it, fixed height so nothing moves while scores land.
public struct MetaLine: View {
    let title: Title
    let availability: Availability?
    @State private var scores: Scores?
    public init(_ title: Title, availability: Availability? = nil) { self.title = title; self.availability = availability }
    public var body: some View {
        HStack(spacing: 14) {
            Text(TitleMeta.meta(title)).font(Cinema.Fonts.meta).foregroundStyle(.white.opacity(0.86)).lineLimit(1)
            if let s = scores ?? TitleExtras.shared.cached(title)?.scores { ScoreBadges(s).transition(.opacity) }
            CardPill(title, known: availability)
        }
        .frame(minHeight: 22)
        .task(id: title.key) {
            let e = await TitleExtras.shared.extras(title)
            withAnimation(.easeOut(duration: 0.2)) { scores = e?.scores }
        }
    }
}

// MARK: Stretch

private struct HeroStretch: ViewModifier {
    let height: CGFloat
    func body(content: Content) -> some View {
        content.visualEffect { c, proxy in
            let y = proxy.frame(in: .named(Cinema.scrollSpace)).minY
            return c
                .scaleEffect(y > 0 ? (height + y) / height : 1, anchor: .bottom)
                .offset(y: y < 0 ? -y * 0.45 : 0)
        }
    }
}

// MARK: Hero

/// The page's hero: `items` (up to 6) as swipeable slides. `play` / `info` act on the slide showing; tapping the art
/// opens it too. Put it first in a vertical ScrollView named `Cinema.scrollSpace` that ignores the top safe area.
public struct SpotlightHero: View {
    let items: [CatalogItem]
    let height: CGFloat
    let playLabel: String
    let play: (CatalogItem) -> Void
    let info: (CatalogItem) -> Void
    @State private var current: String?
    /// The last move was the hero advancing by itself (no haptic for those).
    @State private var autoMoved = false
    @State private var swipes = 0
    @Environment(\.cinemaLayout) private var layout
    @Environment(\.scenePhase) private var phase
    /// CinemaStyle.HERO_ADVANCE_MS: the next title after 8 s resting on one.
    static let advanceSeconds: Double = 8

    public init(_ items: [CatalogItem], height: CGFloat, playLabel: String = "Play",
                play: @escaping (CatalogItem) -> Void, info: @escaping (CatalogItem) -> Void) {
        self.items = Array(items.prefix(6)); self.height = height; self.playLabel = playLabel; self.play = play; self.info = info
    }

    var index: Int { items.firstIndex { $0.id == current } ?? 0 }

    public var body: some View {
        let shown = items.isEmpty ? nil : items[min(index, items.count - 1)]
        let wide = layout.width > height * 1.3
        ZStack(alignment: .top) {
            // The art: the slide showing, crossfading; stretched and drifting with the page; melting into it.
            ZStack {
                Palette.tile
                if let shown {
                    RemoteImage([shown.title.backdrop("w1280"), shown.title.poster("w780")])
                        .id(shown.id)
                        .transition(.opacity)
                }
                LinearGradient(stops: [.init(color: .black.opacity(0.55), location: 0), .init(color: .clear, location: 0.3),
                                       .init(color: .clear, location: 0.42), .init(color: .black.opacity(0.55), location: 0.68),
                                       .init(color: Palette.page, location: 1)], startPoint: .top, endPoint: .bottom)
                if wide {
                    LinearGradient(colors: [.black.opacity(0.75), .black.opacity(0.35), .clear], startPoint: .leading, endPoint: UnitPoint(x: 0.7, y: 0.5))
                }
            }
            .frame(width: layout.width, height: height)
            .clipped()
            .modifier(HeroStretch(height: height))
            .mask(alignment: .bottom) { Rectangle().frame(height: height + 2000) }
            .animation(.easeInOut(duration: 0.32), value: shown?.id)
            .accessibilityHidden(true)

            // The words and buttons, one page per slide (swipe).
            ScrollView(.horizontal) {
                LazyHStack(spacing: 0) {
                    ForEach(items.uniqued()) { item in
                        HeroSlide(item: item, width: layout.width, wide: wide, playLabel: playLabel, play: play, info: info)
                            .frame(width: layout.width, height: height, alignment: .bottomLeading)
                            .contentShape(Rectangle())
                            .onTapGesture { info(item) }
                            .id(item.id)
                    }
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.paging)
            .scrollPosition(id: $current)
            .scrollIndicators(.hidden)
            .frame(height: height)
            .sensoryFeedback(.selection, trigger: swipes)

            if items.count > 1 {
                PageDots(count: items.count, index: index)
                    .padding(.trailing, Cinema.gutter).padding(.bottom, 14)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            }
        }
        .frame(height: height)
        .onChange(of: index) { _, i in
            if i + 1 < items.count { ImageStore.shared.prefetch(items[i + 1].title.backdrop("w1280")) }
            if autoMoved { autoMoved = false } else { swipes += 1 }
        }
        // Advances by itself like the Fire Stick's hero (every 8 s, wrapping round). Any swipe restarts the wait; it
        // rests while the app is in the background or the page is gone (the task ends with the view).
        .task(id: "\(current ?? "")|\(items.count)|\(phase == .active)") {
            guard items.count > 1, phase == .active else { return }
            try? await Task.sleep(for: .seconds(Self.advanceSeconds))
            guard !Task.isCancelled else { return }
            let next = items[(index + 1) % items.count].id
            autoMoved = true
            withAnimation(.easeInOut(duration: 0.6)) { current = next }
        }
    }
}

private struct HeroSlide: View {
    let item: CatalogItem
    let width: CGFloat
    let wide: Bool
    let playLabel: String
    let play: (CatalogItem) -> Void
    let info: (CatalogItem) -> Void

    var body: some View {
        let col = min(width - 2 * Cinema.gutter, wide ? 460 : 520)
        VStack(alignment: .leading, spacing: 0) {
            if let k = item.kicker, !k.isEmpty { Kicker(k, color: .white.opacity(0.8)).padding(.bottom, 8) }
            HeroLogo(item.title, maxWidth: min(col * 0.86, 330), height: wide ? 70 : 84)
            MetaLine(item.title, availability: item.availability).padding(.top, 12)
            if !item.title.overview.isEmpty {
                Text(item.title.overview)
                    .font(Cinema.Fonts.synopsis).foregroundStyle(.white.opacity(0.8))
                    .lineLimit(2).lineSpacing(2)
                    .frame(maxWidth: col, alignment: .leading)
                    .padding(.top, 8)
            }
            HStack(spacing: 12) {
                Button { play(item) } label: { DreamLabel(playLabel, systemImage: "play.fill") }
                    .buttonStyle(PlayButtonStyle(fullWidth: !wide))
                Button { info(item) } label: { DreamLabel("More Info", systemImage: "info.circle") }
                    .buttonStyle(PearlButtonStyle(fullWidth: !wide))
            }
            .frame(maxWidth: wide ? nil : col, alignment: .leading)
            .padding(.top, 18)
        }
        .padding(.horizontal, Cinema.gutter)
        .padding(.bottom, 34)
    }
}

/// A placeholder hero while the page loads: the dark tile melting into the page. Static.
public struct HeroSkeleton: View {
    let height: CGFloat
    public init(height: CGFloat) { self.height = height }
    public var body: some View {
        ZStack(alignment: .bottomLeading) {
            LinearGradient(colors: [Palette.tile, Palette.page], startPoint: .top, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 12) {
                RoundedRectangle(cornerRadius: 6).fill(Color.white.opacity(0.07)).frame(width: 220, height: 54)
                RoundedRectangle(cornerRadius: 4).fill(Color.white.opacity(0.06)).frame(width: 180, height: 12)
                HStack(spacing: 12) {
                    RoundedRectangle(cornerRadius: Cinema.rButton).fill(Color.white.opacity(0.08)).frame(height: 46)
                    RoundedRectangle(cornerRadius: Cinema.rButton).fill(Color.white.opacity(0.06)).frame(height: 46)
                }
                .frame(maxWidth: 360)
            }
            .padding(.horizontal, Cinema.gutter).padding(.bottom, 34)
        }
        .frame(height: height)
        .accessibilityHidden(true)
    }
}

// MARK: Spotlight card

/// Apple TV's feature module: one title across the page, its art full width with the logo (or name), meta and two
/// lines of synopsis over a soft scrim, bottom-left. The pill sits at the end of the meta line, never over the words.
public struct SpotlightCard: View {
    let item: CatalogItem
    let width: CGFloat
    public init(_ item: CatalogItem, width: CGFloat) { self.item = item; self.width = width }

    public var body: some View {
        let h = (width * 0.62).rounded()
        let t = item.title
        CardFrame(width: width, height: h) {
            RemoteImage([t.backdrop("w1280"), t.poster("w500")])
            LinearGradient(stops: [.init(color: .clear, location: 0.25), .init(color: .black.opacity(0.88), location: 1)],
                           startPoint: .top, endPoint: .bottom)
            LinearGradient(colors: [.black.opacity(0.55), .clear], startPoint: .leading, endPoint: .trailing)
            VStack(alignment: .leading, spacing: 6) {
                Kicker(item.kicker ?? "Spotlight", color: .white.opacity(0.8))
                HeroLogo(t, maxWidth: min(width * 0.62, 260), height: 46, font: .title2.weight(.bold))
                MetaLine(t, availability: item.availability)
                if !t.overview.isEmpty {
                    Text(t.overview).font(.footnote).foregroundStyle(.white.opacity(0.8)).lineLimit(2)
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
        }
        .accessibilityElement(children: .combine)
    }
}
