import SwiftUI
import CoreText
import DreamCore

// Dream Cinema's cards for touch (CinemaKit.kt). A card is its art in a rounded frame with its caption underneath
// (every card names its title at all times). Wrap a card in a Button with `CardButtonStyle`: while pressed, the art
// (only the art) takes the rainbow border and settles in a touch, with a light haptic.

// MARK: Press

private struct CardPressedKey: EnvironmentKey { static let defaultValue = false }

public extension EnvironmentValues {
    /// True while the card's button is pressed (set by `CardButtonStyle`; `CardFrame` draws it).
    var cardPressed: Bool {
        get { self[CardPressedKey.self] }
        set { self[CardPressedKey.self] = newValue }
    }
}

public struct CardButtonStyle: ButtonStyle {
    let onPress: (@MainActor () -> Void)?
    /// `onPress` runs the moment a finger lands on the card (before the tap ends): the look-ahead for what it opens.
    public init(onPress: (@MainActor () -> Void)? = nil) { self.onPress = onPress }
    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .environment(\.cardPressed, configuration.isPressed)
            .contentShape(Rectangle())
            .pressHaptic(configuration.isPressed)
            .onChange(of: configuration.isPressed) { _, down in if down { onPress?() } }
    }
}

/// What a title card's press-down fetches ahead (the app sets it: the title page's details, season and art).
@MainActor
public enum CardLookAhead {
    public static var title: ((Title) -> Void)?
}

/// One entry of a card's long-press menu.
public struct CardMenuAction: Identifiable {
    public let id = UUID()
    public var title: String
    public var systemImage: String
    public var destructive: Bool
    public var action: () -> Void
    public init(_ title: String, systemImage: String, destructive: Bool = false, action: @escaping () -> Void) {
        self.title = title; self.systemImage = systemImage; self.destructive = destructive; self.action = action
    }
}

public extension View {
    /// The card's long-press menu (nothing when `actions` is empty).
    @ViewBuilder func cardMenu(_ actions: [CardMenuAction]) -> some View {
        if actions.isEmpty { self } else {
            contextMenu {
                ForEach(actions) { a in
                    Button(role: a.destructive ? .destructive : nil, action: a.action) { Label(a.title, systemImage: a.systemImage) }
                }
            }
        }
    }
}

// MARK: Frame

/// A card's rounded art frame of `width` × `height`: the tile fill, the content clipped to the corners, the press look.
public struct CardFrame<Content: View>: View {
    let width: CGFloat
    let height: CGFloat
    let radius: CGFloat
    let content: Content
    @Environment(\.cardPressed) private var pressed

    public init(width: CGFloat, height: CGFloat, radius: CGFloat = Cinema.rCard, @ViewBuilder content: () -> Content) {
        self.width = width; self.height = height; self.radius = radius; self.content = content()
    }

    public var body: some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        ZStack { content }
            .frame(width: width, height: height)
            .background(Palette.tile)
            .clipShape(shape)
            .overlay(shape.strokeBorder(Color.white.opacity(0.06), lineWidth: 1))
            .rainbowBorder(pressed, radius: radius, width: 3)
            .scaleEffect(pressed ? Cinema.pressScale : 1)
            .animation(Cinema.pressAnimation, value: pressed)
    }
}

/// The name inside a tile for a title with no art.
public struct ArtPlaceholder: View {
    let name: String
    public init(_ name: String) { self.name = name }
    public var body: some View {
        Text(name)
            .font(.footnote.weight(.medium)).foregroundStyle(Palette.textSecondary)
            .multilineTextAlignment(.center).lineLimit(2)
            .padding(.horizontal, 10)
    }
}

/// Title art: the URLs in order (the first that loads), the name while none has.
public struct TitleArt: View {
    let urls: [URL?]
    let kinds: [String]
    let name: String
    /// `kinds`: what each URL is (keyArt / backdrop / poster / still), for the art log.
    public init(_ urls: [URL?], kinds: [String] = [], name: String) { self.urls = urls; self.kinds = kinds; self.name = name }
    public var body: some View {
        if urls.allSatisfy({ $0 == nil }) { ArtPlaceholder(name) } else { RemoteImage(urls, kinds: kinds, label: name) }
    }
}

// MARK: Overlays

/// A small pearl capsule in the top-left corner ("NEW", "UP NEXT", "NEW EPISODE").
public struct CardBadge: View {
    let text: String
    public init(_ text: String) { self.text = text }
    public var body: some View {
        Text(text.uppercased())
            .font(Cinema.Fonts.badge).tracking(0.8)
            .foregroundStyle(Palette.ink)
            .lineLimit(1)
            .padding(.horizontal, 7).padding(.vertical, 4)
            .background(Palette.pearl, in: Capsule())
    }
}

/// Progress along the bottom of the art: pearl at rest, the rainbow's hue while the card is pressed.
public struct CardProgress: View {
    let value: Double
    @Environment(\.cardPressed) private var pressed
    public init(_ value: Double) { self.value = value }
    public var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.43))
                TimelineView(.animation(minimumInterval: 1 / 30, paused: !pressed)) { ctx in
                    Capsule().fill(pressed ? Rainbow.fadeColor(ctx.date) : Palette.pearl)
                }
                .frame(width: g.size.width * min(1, max(0.04, value)))
            }
        }
        .frame(height: 4)
        .accessibilityLabel("\(Int((value * 100).rounded())) percent watched")
    }
}

// MARK: Caption

/// The caption under a card: an optional kicker, the title (one line, or two) and a dim sub line.
public struct CardCaption: View {
    let kicker: String?
    let title: String
    let sub: String?
    let width: CGFloat
    let titleLines: Int
    public init(_ title: String, sub: String? = nil, kicker: String? = nil, width: CGFloat, titleLines: Int = 1) {
        self.title = title; self.sub = sub; self.kicker = kicker; self.width = width; self.titleLines = titleLines
    }
    public var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            if let kicker, !kicker.isEmpty { Kicker(kicker).padding(.bottom, 1) }
            Text(title).font(Cinema.Fonts.cardTitle).foregroundStyle(Palette.text).lineLimit(titleLines)
                .fixedSize(horizontal: false, vertical: true)
            if let sub, !sub.isEmpty {
                Text(sub).font(Cinema.Fonts.cardMeta).foregroundStyle(Palette.textSecondary).lineLimit(1)
            }
        }
        .frame(width: width, alignment: .leading)
        .multilineTextAlignment(.leading)
    }
}

// MARK: Title cards

extension CatalogItem {
    /// A plain title card looks its pill up; a card pointing at an episode or a spot in one (Continue Watching,
    /// New Episodes for You) shows only what its row sent.
    var livePill: Bool { episode == nil && progress == nil }
    /// The NEW EPISODE badge: the show isn't caught up, so no Watched pill.
    var newEpisodeBadge: Bool { badge?.hasPrefix("NEW EPISODE") == true }
}

/// A 2:3 poster with its title (and optional sub line) underneath. Pill bottom-left (inset 6), badge top-left.
public struct PosterCard: View {
    let title: Title
    let width: CGFloat
    var sub: String?
    var showCaption: Bool
    var availability: Availability?
    var badge: String?
    var progress: Double?
    var livePill: Bool

    /// The pill shows `availability` first, then looks the title up itself (`livePill: false`: only `availability`).
    public init(_ title: Title, width: CGFloat, sub: String? = nil, caption: Bool = true, availability: Availability? = nil,
                badge: String? = nil, progress: Double? = nil, livePill: Bool = true) {
        self.title = title; self.width = width; self.sub = sub; self.showCaption = caption
        self.availability = availability; self.badge = badge; self.progress = progress; self.livePill = livePill
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: Cinema.captionGap) {
            CardFrame(width: width, height: (width * 1.5).rounded()) {
                TitleArt([title.poster("w342"), title.backdrop("w780")], kinds: ["poster", "backdrop"], name: title.name)
            }
            .overlay(alignment: .topLeading) { if let badge { CardBadge(badge).padding(6) } }
            .overlay(alignment: .bottomLeading) {
                VStack(alignment: .leading, spacing: 6) {
                    CardPill(title, known: availability, live: livePill, maxWidth: width - 12,
                             newEpisode: badge?.hasPrefix("NEW EPISODE") == true)
                    if let progress { CardProgress(progress) }
                }
                .padding(6)
            }
            if showCaption { CardCaption(title.name, sub: sub, width: width) }
        }
        .accessibilityElement(children: .combine)
    }
}

/// A 16:9 card from a row item: its backdrop (an episode's still), title and line underneath.
public struct LandscapeCard: View {
    let item: CatalogItem
    let width: CGFloat
    public init(_ item: CatalogItem, width: CGFloat) { self.item = item; self.width = width }

    public var body: some View {
        let t = item.title
        let h = (width * 9 / 16).rounded()
        VStack(alignment: .leading, spacing: Cinema.captionGap) {
            CardFrame(width: width, height: h) {
                // An episode's still; else the title's key art (its name on the art), the textless backdrop, the poster.
                TitleArt([item.episode?.still("w780"), item.episode == nil ? item.keyArt("w780") : nil, t.backdrop("w780"), t.poster("w342")], kinds: ["still", "keyArt", "backdrop", "poster"], name: t.name)
            }
            .overlay(alignment: .topLeading) { if let b = item.badge { CardBadge(b).padding(8) } }
            .overlay(alignment: .bottomLeading) {
                VStack(alignment: .leading, spacing: 6) {
                    CardPill(t, known: item.availability, live: item.livePill, maxWidth: width - 12, newEpisode: item.newEpisodeBadge)
                    if let p = item.progress { CardProgress(p).padding(.horizontal, 4) }
                }
                .padding(6)
            }
            CardCaption(t.name, sub: item.subtitle ?? TitleMeta.subline(t), width: width)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Hulu's big landscape card: the art large, an optional kicker, the title and the meta line (year, type, genres).
public struct BigCard: View {
    let item: CatalogItem
    let width: CGFloat
    public init(_ item: CatalogItem, width: CGFloat) { self.item = item; self.width = width }

    public var body: some View {
        let t = item.title
        VStack(alignment: .leading, spacing: Cinema.captionGap + 2) {
            CardFrame(width: width, height: (width * 9 / 16).rounded()) {
                TitleArt([item.keyArt("w780"), t.backdrop("w780"), t.poster("w500")], kinds: ["keyArt", "backdrop", "poster"], name: t.name)
            }
            .overlay(alignment: .topLeading) { if let b = item.badge { CardBadge(b).padding(8) } }
            .overlay(alignment: .bottomLeading) {
                CardPill(t, known: item.availability, live: item.livePill, maxWidth: width - 16, newEpisode: item.newEpisodeBadge).padding(8)
            }
            CardCaption(t.name, sub: item.subtitle ?? TitleMeta.meta(t), kicker: item.kicker, width: width)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Top 10: an outlined rank numeral beside the poster (only the poster is pressed), the title under the poster.
public struct RankedCard: View {
    let item: CatalogItem
    let rank: Int
    let posterWidth: CGFloat
    let slot: CGFloat
    public init(_ item: CatalogItem, rank: Int, posterWidth: CGFloat, slot: CGFloat = 54) {
        self.item = item; self.rank = rank; self.posterWidth = posterWidth; self.slot = slot
    }

    public var body: some View {
        let h = (posterWidth * 1.5).rounded()
        let slotW = rank >= 10 ? slot + 30 : slot
        HStack(alignment: .top, spacing: 0) {
            RankNumeral(rank: rank, gap: 8)
                .stroke(Color.white.opacity(0.75), lineWidth: 2)
                .frame(width: slotW, height: h)
                .accessibilityHidden(true)
            PosterCard(item.title, width: posterWidth, availability: item.availability, badge: item.badge)
        }
        .accessibilityLabel("Number \(rank), \(item.title.name)")
    }
}

/// A rank numeral as a path from the font's glyph outlines, measured by its ink: every rank the same size, the ink
/// ending `gap` pt before the slot's right edge and sitting on the slot's bottom (the poster's bottom edge).
public struct RankNumeral: Shape {
    let rank: Int
    let gap: CGFloat
    public init(rank: Int, gap: CGFloat = 8) { self.rank = rank; self.gap = gap }

    public func path(in rect: CGRect) -> Path {
        let font = UIFont.systemFont(ofSize: 100, weight: .heavy) as CTFont
        let raw = Self.glyphPath(String(rank), font: font)
        let ink = raw.boundingRect
        guard ink.width > 0, ink.height > 0 else { return Path() }
        // One size for every rank: the digits' height at 62% of the slot (the TV's ratio), narrowed only if it won't fit.
        var scale = rect.height * 0.62 / ink.height
        scale = min(scale, (rect.width - gap - 2) / ink.width)
        let t = CGAffineTransform(translationX: rect.maxX - gap - 1 - ink.maxX * scale, y: rect.maxY - 1 - ink.maxY * scale)
            .scaledBy(x: scale, y: scale)
        return raw.applying(t)
    }

    /// The text's outline in a y-down space (baseline at 0).
    static func glyphPath(_ s: String, font: CTFont) -> Path {
        let chars = Array(s.utf16)
        var glyphs = [CGGlyph](repeating: 0, count: chars.count)
        CTFontGetGlyphsForCharacters(font, chars, &glyphs, chars.count)
        var advances = [CGSize](repeating: .zero, count: glyphs.count)
        CTFontGetAdvancesForGlyphs(font, .horizontal, glyphs, &advances, glyphs.count)
        let out = CGMutablePath()
        var x: CGFloat = 0
        for (i, g) in glyphs.enumerated() {
            var t = CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: x, ty: 0)
            if let p = CTFontCreatePathForGlyph(font, g, &t) { out.addPath(p) }
            x += advances[i].width - 4
        }
        return Path(out)
    }
}

/// Continue Watching: art, the progress line (or UP NEXT), title and where you are underneath.
public struct ContinueCard: View {
    let item: CatalogItem
    let width: CGFloat
    public init(_ item: CatalogItem, width: CGFloat) { self.item = item; self.width = width }

    /// A history entry as a Continue Watching item.
    public static func item(_ e: HistoryEntry) -> CatalogItem {
        let ep = (e.season != nil && e.episode != nil)
            ? Episode(showId: e.title.id, season: e.season ?? 0, number: e.episode ?? 0, name: e.episodeName ?? "", stillPath: e.stillPath)
            : nil
        return CatalogItem(title: e.title, episode: ep, progress: e.upNext ? nil : (e.progress > 0 ? e.progress : nil),
                           subtitle: TitleMeta.continueLine(e), badge: e.upNext ? "Up Next" : nil)
    }

    public var body: some View { LandscapeCard(item, width: width) }
}

// MARK: Tiles

/// Brand tints for the service tiles (ServiceSpec.SHORTCUTS colors), by name.
public enum BrandTint {
    static let table: [String: UInt32] = [
        "Netflix": 0xE50914, "Hulu": 0x1CE783, "Prime Video": 0x00A8E1, "Apple TV": 0x8E8E93, "Apple TV+": 0x8E8E93,
        "Disney+": 0x113CCF, "HBO Max": 0x002BE7, "Max": 0x002BE7, "Peacock": 0xF6B700, "Paramount+": 0x0064FF,
        "AMC+": 0x2F80ED, "Crunchyroll": 0xF47521, "Tubi": 0x7B3FE4,
    ]
    public static func hex(for name: String) -> UInt32 { table[name] ?? 0x8E8E93 }
}

/// A service tile (Disney+): the name in bold on a deep tint of the brand color, a short bar of the color under it.
public struct BrandCard: View {
    let name: String
    let width: CGFloat
    public init(_ spec: ServiceSpec, width: CGFloat) { self.name = spec.name; self.width = width }
    public init(name: String, width: CGFloat) { self.name = name; self.width = width }

    public var body: some View {
        let c = BrandTint.hex(for: name)
        CardFrame(width: width, height: (width * 9 / 16).rounded()) {
            LinearGradient(colors: [Color(hex: Rainbow.mixHex(c, 0x151517, 0.40)), Color(hex: Rainbow.mixHex(c, 0x000000, 0.80))],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
            VStack(spacing: 8) {
                Text(name).font(.headline.weight(.bold)).foregroundStyle(.white).lineLimit(1).minimumScaleFactor(0.8)
                Capsule().fill(Color(hex: Rainbow.mixHex(c, 0xFFFFFF, 0.25))).frame(width: 22, height: 3)
            }
            .padding(.horizontal, 10)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(name)
    }
}

/// A genre card: a deep tint of one rainbow hue, the genre's glyph top-right, its name bottom-left.
public struct GenreCard: View {
    let name: String
    let hue: UInt32
    let width: CGFloat
    /// The glyph's name in the drawn set ("Horror", "Season:Snowflake"); defaults to `name`.
    let glyph: String?
    public init(_ name: String, hue: UInt32, width: CGFloat, glyph: String? = nil) {
        self.name = name; self.hue = hue; self.width = width; self.glyph = glyph
    }

    public var body: some View {
        let h = (width * 9 / 16).rounded()
        CardFrame(width: width, height: h) {
            LinearGradient(colors: [Color(hex: Rainbow.mixHex(hue, 0x151517, 0.50)), Color(hex: Rainbow.mixHex(hue, 0x000000, 0.80))],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
            GenreGlyph(glyph ?? name, tint: Rainbow.mixHex(hue, 0xFFFFFF, 0.35))
                .frame(width: h * 0.46, height: h * 0.46)
                .padding(.top, 10).padding(.trailing, 10)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            Text(name).font(.subheadline.weight(.semibold)).foregroundStyle(.white).lineLimit(1)
                .padding(.horizontal, 12).padding(.bottom, 10)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(name)
    }
}

/// A big entry tile (Browse's TV Shows and Movies): art (or a deep two-hue gradient) under a soft bottom scrim, the
/// name large and a line about it, bottom-left.
public struct EntryTile: View {
    let name: String
    let sub: String
    let art: URL?
    let hues: (UInt32, UInt32)
    let width: CGFloat
    let height: CGFloat
    public init(_ name: String, sub: String, art: URL?, hues: (UInt32, UInt32), width: CGFloat, height: CGFloat) {
        self.name = name; self.sub = sub; self.art = art; self.hues = hues; self.width = width; self.height = height
    }

    public var body: some View {
        CardFrame(width: width, height: height) {
            LinearGradient(colors: [Color(hex: Rainbow.mixHex(hues.0, 0x000000, 0.55)), Color(hex: Rainbow.mixHex(hues.1, 0x000000, 0.80))],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
            if let art { RemoteImage([art]) }
            LinearGradient(colors: [.clear, .black.opacity(0.35), .black.opacity(0.9)], startPoint: .top, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 4) {
                Text(name).font(.title2.weight(.bold)).foregroundStyle(.white).lineLimit(1)
                Text(sub).font(.footnote.weight(.medium)).foregroundStyle(.white.opacity(0.78)).lineLimit(1)
            }
            .padding(.horizontal, 14).padding(.bottom, 12)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
        }
        .accessibilityElement(children: .combine)
    }
}

/// A seasonal hub's tile (SeasonUi.tile): graphite, never a hue (the hub's flavor is its glyph, art and words), its
/// line-art glyph top right in its rainbow color, kicker and title bottom left, clear of the glyph; under it the
/// countdown ("5 Nights Until Halloween") and "Night 26 of 31", its kicker, or "Seasonal Collection".
public struct HubCard: View {
    let hub: HubTile
    let width: CGFloat
    public init(_ hub: HubTile, width: CGFloat) { self.hub = hub; self.width = width }

    /// The hub's glyph color (SeasonHub: Rainbow.COLORS[hue]).
    public static func tint(_ hub: HubTile) -> UInt32 { Rainbow.hue(hub.hue) }

    public var body: some View {
        let h = (width * 9 / 16).rounded()
        let glyph = (h * 0.42).rounded()
        VStack(alignment: .leading, spacing: Cinema.captionGap) {
            CardFrame(width: width, height: h) {
                LinearGradient(colors: [Color(hex: 0x1D1D20), Color(hex: 0x0B0B0D)], startPoint: .topLeading, endPoint: .bottomTrailing)
                GenreGlyph(hub.glyph, tint: Self.tint(hub))
                    .frame(width: glyph, height: glyph)
                    .padding(.top, 10).padding(.trailing, 10)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                VStack(alignment: .leading, spacing: 4) {
                    Kicker(hub.kicker, color: .white.opacity(0.66))
                    Text(hub.title).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
                        .lineLimit(2).minimumScaleFactor(0.85)
                        .fixedSize(horizontal: false, vertical: true)
                }
                // Never under the glyph: the column ends a gap before it.
                .frame(width: max(60, width - 12 - glyph - 10 - 10), alignment: .leading)
                .padding(.leading, 12).padding(.bottom, 10)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
            }
            CardCaption(hub.status, sub: hub.sub, width: width)
        }
        .accessibilityElement(children: .combine)
    }
}
