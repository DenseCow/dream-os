import SwiftUI
import DreamCore

// Rows, grids, chips and page parts (CinemaKit.kt CinemaRow / CinemaChips / CinemaEndCap / empty, CinemaBrowse
// pageHeader). Rows are real touch scrollers: cards snap to the gutter line, the last card peeks in at the edge.

// MARK: Row

/// A row's title on the page's left line.
public struct RowHeader: View {
    let title: String
    public init(_ title: String) { self.title = title }
    public var body: some View {
        Text(title)
            .font(Cinema.Fonts.rowTitle).foregroundStyle(Palette.text)
            .lineLimit(1)
            .padding(.horizontal, Cinema.gutter)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }
}

/// A titled horizontal row of anything: `content` builds each item. `onNearEnd` runs as the last few come into view.
public struct HorizontalRow<Item: Identifiable, Card: View>: View {
    let title: String?
    let items: [Item]
    let spacing: CGFloat
    let onNearEnd: (() -> Void)?
    let card: (Int, Item) -> Card

    public init(_ title: String?, items: [Item], spacing: CGFloat = Cinema.cardGap, onNearEnd: (() -> Void)? = nil,
                @ViewBuilder card: @escaping (Int, Item) -> Card) {
        self.title = title; self.items = items; self.spacing = spacing; self.onNearEnd = onNearEnd; self.card = card
    }

    public var body: some View {
        // The near-end mark counts the cards shown: measured against `items`, repeats pushed it past the last card.
        let shown = items.uniqued()
        VStack(alignment: .leading, spacing: Cinema.rowTitleGap) {
            if let title { RowHeader(title) }
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: spacing) {
                    ForEach(Array(shown.enumerated()), id: \.element.id) { i, item in
                        card(i, item)
                            .onAppear { if i >= shown.count - 4 { onNearEnd?() } }
                    }
                }
                .scrollTargetLayout()
                .padding(.vertical, 6)
            }
            .contentMargins(.horizontal, Cinema.gutter, for: .scrollContent)
            .scrollTargetBehavior(.viewAligned)
            .scrollIndicators(.hidden)
            .scrollClipDisabled()
            .padding(.vertical, -6)
        }
    }
}

/// A catalog row drawn in its style. `open` runs when a card is tapped; `menu` gives each card's long-press actions.
public struct CatalogRowView: View {
    let row: CatalogRow
    let open: (CatalogItem) -> Void
    let openHub: (HubTile) -> Void
    let menu: (CatalogItem) -> [CardMenuAction]
    let onNearEnd: (() -> Void)?
    @Environment(\.cinemaLayout) private var layout

    public init(_ row: CatalogRow, open: @escaping (CatalogItem) -> Void, openHub: @escaping (HubTile) -> Void = { _ in },
                menu: @escaping (CatalogItem) -> [CardMenuAction] = { _ in [] }, onNearEnd: (() -> Void)? = nil) {
        self.row = row; self.open = open; self.openHub = openHub; self.menu = menu; self.onNearEnd = onNearEnd
    }

    public var body: some View {
        if row.isSpotlight, let item = row.items.first {
            Button { open(item) } label: { SpotlightCard(item, width: layout.spotlightW) }
                .buttonStyle(CardButtonStyle(onPress: { CardLookAhead.title?(item.title) }))
                .cardMenu(menu(item))
                .padding(.horizontal, Cinema.gutter)
        } else if !row.hubs.isEmpty {
            HorizontalRow(row.name, items: hubEntries, onNearEnd: onNearEnd) { _, e in
                switch e {
                case .hub(let h):
                    Button { openHub(h) } label: { HubCard(h, width: layout.landW) }.buttonStyle(CardButtonStyle())
                case .item(let it):
                    cardButton(it) { LandscapeCard(it, width: layout.landW) }
                }
            }
        } else {
            HorizontalRow(row.name, items: row.items, onNearEnd: row.hasMore ? onNearEnd : nil) { i, item in
                switch row.style {
                case .poster: cardButton(item) { PosterCard(item.title, width: layout.posterW, sub: item.subtitle, availability: item.availability, badge: item.badge, progress: item.progress) }
                case .landscape, .continueWatching: cardButton(item) { LandscapeCard(item, width: layout.landW) }
                case .big: cardButton(item) { BigCard(item, width: layout.bigW) }
                case .top10: cardButton(item) { RankedCard(item, rank: i + 1, posterWidth: layout.rankPosterW, slot: layout.rankSlot) }
                case .brand: cardButton(item) { BrandCard(name: item.title.name, width: layout.brandW) }
                }
            }
        }
    }

    enum HubEntry: Identifiable {
        case hub(HubTile), item(CatalogItem)
        var id: String { switch self { case .hub(let h): "hub:" + h.id; case .item(let i): i.id } }
    }

    var hubEntries: [HubEntry] { row.hubs.map { .hub($0) } + row.items.map { .item($0) } }

    private func cardButton<C: View>(_ item: CatalogItem, @ViewBuilder _ card: () -> C) -> some View {
        // Press-down fetches the title page ahead (not for a service tile, which opens a page).
        let ahead = row.style != .brand
        return Button { open(item) } label: { card() }
            .buttonStyle(CardButtonStyle(onPress: ahead ? { CardLookAhead.title?(item.title) } : nil))
            .cardMenu(menu(item))
    }
}

/// Placeholders of a row's real size while it loads: static tiles, no shimmer (nothing animates at rest).
public struct SkeletonRow: View {
    let style: RowStyle
    let titled: Bool
    @Environment(\.cinemaLayout) private var layout
    public init(_ style: RowStyle = .landscape, titled: Bool = true) { self.style = style; self.titled = titled }

    public var body: some View {
        let (w, h): (CGFloat, CGFloat) = switch style {
        case .poster: (layout.posterW, layout.posterW * 1.5)
        case .big: (layout.bigW, layout.bigW * 9 / 16)
        case .top10: (layout.rankPosterW + layout.rankSlot, layout.rankPosterW * 1.5)
        case .brand: (layout.brandW, layout.brandW * 9 / 16)
        default: (layout.landW, layout.landW * 9 / 16)
        }
        let n = max(1, Int(ceil((layout.width - Cinema.gutter) / (w + Cinema.cardGap))))
        VStack(alignment: .leading, spacing: Cinema.rowTitleGap) {
            if titled {
                RoundedRectangle(cornerRadius: 4).fill(Palette.tile).frame(width: 140, height: 16)
                    .padding(.horizontal, Cinema.gutter).padding(.vertical, 3)
            }
            HStack(alignment: .top, spacing: Cinema.cardGap) {
                ForEach(0..<n, id: \.self) { _ in
                    VStack(alignment: .leading, spacing: Cinema.captionGap) {
                        RoundedRectangle(cornerRadius: Cinema.rCard, style: .continuous).fill(Palette.tile).frame(width: w, height: h)
                        RoundedRectangle(cornerRadius: 3).fill(Palette.tile).frame(width: w * 0.7, height: 10)
                    }
                }
            }
            .padding(.leading, Cinema.gutter)
            .frame(maxWidth: .infinity, alignment: .leading)
            .clipped()
        }
        .accessibilityHidden(true)
    }
}

// MARK: Grid

/// A poster grid: columns of about `target` pt fill the page between the gutters, the title under every poster.
public struct PosterGrid: View {
    let titles: [Title]
    let target: CGFloat
    let showSub: Bool
    let open: (Title) -> Void
    let menu: (Title) -> [CardMenuAction]
    let progress: (Title) -> Double?
    let onNearEnd: (() -> Void)?
    @Environment(\.cinemaLayout) private var layout

    public init(_ titles: [Title], target: CGFloat = 104, showSub: Bool = true, open: @escaping (Title) -> Void,
                menu: @escaping (Title) -> [CardMenuAction] = { _ in [] }, progress: @escaping (Title) -> Double? = { _ in nil },
                onNearEnd: (() -> Void)? = nil) {
        self.titles = titles; self.target = target; self.showSub = showSub; self.open = open; self.menu = menu
        self.progress = progress; self.onNearEnd = onNearEnd
    }

    public var body: some View {
        let cols = layout.columns(target, min: 3)
        let shown = titles.uniqued(by: \.key)
        LazyVGrid(columns: Array(repeating: GridItem(.fixed(cols.width), spacing: Cinema.cardGap, alignment: .top), count: cols.count),
                  alignment: .leading, spacing: 18) {
            ForEach(Array(shown.enumerated()), id: \.element.key) { i, t in
                Button { open(t) } label: {
                    PosterCard(t, width: cols.width, sub: showSub ? TitleMeta.subline(t) : nil, progress: progress(t))
                }
                .buttonStyle(CardButtonStyle(onPress: { CardLookAhead.title?(t) }))
                .cardMenu(menu(t))
                .onAppear { if i >= shown.count - 6 { onNearEnd?() } }
            }
        }
        .padding(.horizontal, Cinema.gutter)
    }
}

/// "TV Shows  ·  12": a section heading, the count quieter.
public struct SectionHeading: View {
    let name: String
    let count: Int?
    public init(_ name: String, count: Int? = nil) { self.name = name; self.count = count }
    public var body: some View {
        (Text(name).foregroundStyle(Palette.text) + Text(count.map { "\(Cinema.dot)\($0)" } ?? "").foregroundStyle(Palette.textSecondary))
            .font(Cinema.Fonts.rowTitle)
            .lineLimit(1)
            .padding(.horizontal, Cinema.gutter)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }
}

// MARK: Chips

/// Hulu's category chip: an outline at rest, a soft fill when current, pearl with ink and a rainbow border while pressed.
public struct ChipButtonStyle: ButtonStyle {
    var selected: Bool
    public init(selected: Bool = false) { self.selected = selected }
    public func makeBody(configuration: Configuration) -> some View {
        let p = configuration.isPressed
        configuration.label
            .font(Cinema.Fonts.chip)
            .lineLimit(1)
            .foregroundStyle(p ? Palette.ink : selected ? Palette.text : Palette.text.opacity(0.78))
            .padding(.horizontal, 16)
            .frame(minHeight: 36)
            .background {
                Capsule().fill(p ? Palette.pearl : selected ? Color.white.opacity(0.18) : Color.clear)
                Capsule().strokeBorder(Color.white.opacity(p ? 0 : selected ? 0.32 : 0.24), lineWidth: 1)
            }
            .rainbowBorder(p, radius: 18, width: 2)
            .contentShape(Capsule())
            .scaleEffect(p ? 0.96 : 1)
            .animation(Cinema.pressAnimation, value: p)
            .pressHaptic(p)
    }
}

/// A scrolling line of chips, one current (`selected`; nil = none). Starts on the gutter line.
public struct ChipLine: View {
    let labels: [String]
    let selected: Int?
    let onPick: (Int) -> Void
    public init(_ labels: [String], selected: Int?, onPick: @escaping (Int) -> Void) {
        self.labels = labels; self.selected = selected; self.onPick = onPick
    }
    public var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 10) {
                ForEach(Array(labels.enumerated()), id: \.offset) { i, l in
                    Button(l) { onPick(i) }
                        .buttonStyle(ChipButtonStyle(selected: i == selected))
                        .accessibilityAddTraits(i == selected ? .isSelected : [])
                }
            }
            .padding(.vertical, 4)
        }
        .contentMargins(.horizontal, Cinema.gutter, for: .scrollContent)
        .scrollIndicators(.hidden)
        .scrollClipDisabled()
        .sensoryFeedback(.selection, trigger: selected)
    }
}

// MARK: Page parts

/// A page title (Browse, My List): the name large, a line about it.
public struct PageHeader<Trailing: View>: View {
    let title: String
    let subtitle: String?
    let trailing: Trailing
    public init(_ title: String, subtitle: String? = nil, @ViewBuilder trailing: () -> Trailing) {
        self.title = title; self.subtitle = subtitle; self.trailing = trailing()
    }
    public var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(Cinema.Fonts.pageTitle).foregroundStyle(Palette.text).lineLimit(1)
                    .accessibilityAddTraits(.isHeader)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle).font(Cinema.Fonts.pageSubtitle).foregroundStyle(Palette.textSecondary).lineLimit(2)
                }
            }
            Spacer(minLength: 8)
            trailing
        }
        .padding(.horizontal, Cinema.gutter)
    }
}

public extension PageHeader where Trailing == EmptyView {
    init(_ title: String, subtitle: String? = nil) { self.init(title, subtitle: subtitle) { EmptyView() } }
}

/// A quiet empty state: a line icon in a soft disc, a title and a sentence, an optional action.
public struct EmptyState: View {
    let systemImage: String
    let title: String
    let message: String
    let actionTitle: String?
    let action: (() -> Void)?
    public init(systemImage: String, title: String, message: String, actionTitle: String? = nil, action: (() -> Void)? = nil) {
        self.systemImage = systemImage; self.title = title; self.message = message; self.actionTitle = actionTitle; self.action = action
    }
    public var body: some View {
        VStack(spacing: 0) {
            Image(systemName: systemImage)
                .font(.system(size: 30, weight: .light))
                .foregroundStyle(.white.opacity(0.9))
                .frame(width: 76, height: 76)
                .background(Color.white.opacity(0.08), in: Circle())
                .accessibilityHidden(true)
            Text(title).font(.title3.weight(.semibold)).foregroundStyle(Palette.text)
                .multilineTextAlignment(.center).padding(.top, 20)
            Text(message).font(.subheadline).foregroundStyle(Palette.textSecondary)
                .multilineTextAlignment(.center).lineSpacing(2).padding(.top, 8)
                .frame(maxWidth: 340)
            if let actionTitle, let action {
                Button(action: action) { DreamLabel(actionTitle) }.buttonStyle(PearlButtonStyle()).padding(.top, 22)
            }
        }
        .padding(.horizontal, 32)
        .padding(.vertical, 36)
        .frame(maxWidth: .infinity)
    }
}

/// The end of a long page: "You're All Caught Up" and Back to Top. It keeps its own room on every side (STANDARDS §3:
/// nothing clipped): the button never shrinks or truncates, its press glow has space, and the page's scroll content
/// ends well above the tab bar and the home indicator (`CinemaScrollPage` adds the bottom margin under it too).
public struct EndCap: View {
    let onTop: () -> Void
    public init(onTop: @escaping () -> Void) { self.onTop = onTop }
    public var body: some View {
        VStack(spacing: 0) {
            LinearGradient(colors: [.clear, .white.opacity(0.28), .clear], startPoint: .leading, endPoint: .trailing)
                .frame(width: 260, height: 1)
            Text("You're All Caught Up").font(.footnote.weight(.medium)).foregroundStyle(Palette.textSecondary)
                .lineLimit(1)
                .padding(.top, 18).padding(.bottom, 14)
            Button(action: onTop) { DreamLabel("Back to Top", systemImage: "arrow.up") }
                .buttonStyle(PearlButtonStyle())
                .fixedSize()
                .padding(6)
        }
        .padding(.horizontal, Cinema.gutter)
        .padding(.top, 12).padding(.bottom, 40)
        .frame(maxWidth: .infinity)
        .fixedSize(horizontal: false, vertical: true)
    }
}

/// A page's loading or failure state as one view: skeleton rows while loading, the message with Try Again on failure.
public struct LoadFailed: View {
    let message: String
    let retry: () -> Void
    public init(_ message: String, retry: @escaping () -> Void) { self.message = message; self.retry = retry }
    public var body: some View {
        EmptyState(systemImage: "wifi.exclamationmark", title: "Something Went Wrong", message: message, actionTitle: "Try Again", action: retry)
    }
}

/// Small dots for a paged hero: the current one a wider pearl capsule. Static.
public struct PageDots: View {
    let count: Int
    let index: Int
    public init(count: Int, index: Int) { self.count = count; self.index = index }
    public var body: some View {
        HStack(spacing: 6) {
            ForEach(0..<count, id: \.self) { i in
                Capsule().fill(i == index ? Palette.pearl : Color.white.opacity(0.32))
                    .frame(width: i == index ? 16 : 6, height: 6)
            }
        }
        .animation(.easeOut(duration: 0.2), value: index)
        .accessibilityHidden(true)
    }
}
