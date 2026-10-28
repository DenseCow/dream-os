import SwiftUI

/// Dream Cinema's design tokens for the phone (fire-home CinemaStyle.kt, scaled for touch): one gutter, one card gap,
/// one set of radii, iOS text styles (so Dynamic Type works; the shell caps it at .xxLarge), and card sizes that
/// follow the page width: whole cards plus a small peek of the next, so a cut last card reads as intentional.
public enum Cinema {
    // ---- layout (pt) ----
    public static let gutter: CGFloat = 16
    public static let rowGap: CGFloat = 26
    public static let rowTitleGap: CGFloat = 10
    public static let cardGap: CGFloat = 10
    public static let captionGap: CGFloat = 8
    /// The share of a card that peeks in at a row's right edge.
    public static let peek: CGFloat = 0.3

    // ---- shape ----
    public static let rCard: CGFloat = 10
    public static let rButton: CGFloat = 10
    public static let rChip: CGFloat = 100

    // ---- motion ----
    public static let pressScale: CGFloat = 0.97
    public static let pressAnimation = Animation.easeOut(duration: 0.16)
    public static let pageAnimation = Animation.easeOut(duration: 0.26)

    // ---- type (iOS text styles, so Dynamic Type scales them) ----
    public enum Fonts {
        public static let heroTitle = Font.largeTitle.weight(.bold)
        public static let pageTitle = Font.largeTitle.weight(.bold)
        public static let pageSubtitle = Font.subheadline.weight(.medium)
        /// Row titles: semibold, Title Case.
        public static let rowTitle = Font.title3.weight(.semibold)
        public static let cardTitle = Font.footnote.weight(.semibold)
        public static let cardMeta = Font.caption.weight(.medium)
        /// "2024 · Series · Drama" on the hero and big cards.
        public static let meta = Font.footnote.weight(.medium)
        public static let synopsis = Font.subheadline
        /// "NEW SERIES": small tracked caps.
        public static let kicker = Font.caption2.weight(.semibold)
        public static let chip = Font.subheadline.weight(.medium)
        public static let badge = Font.caption2.weight(.bold)
        public static let tab = Font.caption2.weight(.medium)
    }

    /// Tracking for kicker caps.
    public static let kickerTracking: CGFloat = 1.4

    /// " · " between meta parts (two spaces each side, as on the TV).
    public static let dot = "  ·  "
}

/// Card sizes for a page `width` (the content width inside the safe area). Each kind aims at a target width and is
/// fitted so whole cards and a `Cinema.peek` of the next fill the row from the gutter to the screen's edge.
public struct CinemaLayout: Equatable, Sendable {
    public var width: CGFloat
    public init(width: CGFloat) { self.width = max(320, width) }

    public static let phone = CinemaLayout(width: 393)

    /// Width of each card when cards of about `target` pt fill the row (+ a peek).
    public func fit(_ target: CGFloat, minAcross: CGFloat = 1) -> CGFloat {
        let avail = width - Cinema.gutter
        let n = max(minAcross, floor((avail + Cinema.cardGap) / (target + Cinema.cardGap)))
        return floor((avail - n * Cinema.cardGap) / (n + Cinema.peek))
    }

    /// Columns of about `target` pt filling the page between both gutters exactly (grids).
    public func columns(_ target: CGFloat, min: Int = 2) -> (count: Int, width: CGFloat) {
        let avail = width - 2 * Cinema.gutter
        let n = Swift.max(min, Int((avail + Cinema.cardGap) / (target + Cinema.cardGap)))
        return (n, floor((avail - CGFloat(n - 1) * Cinema.cardGap) / CGFloat(n)))
    }

    public var posterW: CGFloat { fit(104) }
    public var landW: CGFloat { fit(158) }
    public var bigW: CGFloat { fit(300) }
    public var brandW: CGFloat { fit(140) }
    /// Top 10: the poster beside its numeral.
    public var rankPosterW: CGFloat { fit(160) - rankSlot }
    public var rankSlot: CGFloat { 54 }
    /// The Spotlight card: the page between the gutters.
    public var spotlightW: CGFloat { width - 2 * Cinema.gutter }
    /// The hero's height at this width and screen height.
    public func heroHeight(screenHeight: CGFloat) -> CGFloat {
        if width > screenHeight { return min(width * 9 / 16, screenHeight * 0.86) }
        return min(width * 1.18, screenHeight * 0.68)
    }
}

private struct CinemaLayoutKey: EnvironmentKey {
    static let defaultValue = CinemaLayout.phone
}

public extension EnvironmentValues {
    /// The page's card sizes; set once per page with `.cinemaLayout()`.
    var cinemaLayout: CinemaLayout {
        get { self[CinemaLayoutKey.self] }
        set { self[CinemaLayoutKey.self] = newValue }
    }
}

public extension View {
    /// Measures the width this view gets and hands it down as `cinemaLayout`.
    func cinemaLayout() -> some View { modifier(CinemaLayoutReader()) }
}

private struct CinemaLayoutReader: ViewModifier {
    @State private var width: CGFloat = CinemaLayout.phone.width
    func body(content: Content) -> some View {
        content
            .environment(\.cinemaLayout, CinemaLayout(width: width))
            .background(GeometryReader { g in
                Color.clear
                    .onAppear { width = g.size.width }
                    .onChange(of: g.size.width) { _, w in width = w }
            })
    }
}

/// "NEW SERIES": a kicker line.
public struct Kicker: View {
    let text: String
    var color: Color
    public init(_ text: String, color: Color = .white.opacity(0.72)) { self.text = text; self.color = color }
    public var body: some View {
        Text(text.uppercased()).font(Cinema.Fonts.kicker).tracking(Cinema.kickerTracking).foregroundStyle(color).lineLimit(1)
    }
}
