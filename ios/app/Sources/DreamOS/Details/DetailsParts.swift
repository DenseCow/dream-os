import SwiftUI
import DreamCore
import DreamUI

// Title page parts: scores (Ratings.kt: the tomato, the audience badge, no stars), the age-rating box, the
// availability pill, a wrapping meta line, art, cards. Local until DreamUI ships the shared ones (pages.md).

/// The age rating in a thin box ("TV-MA").
struct CertBox: View {
    var cert: String
    var body: some View {
        Text(cert)
            .font(.system(size: 11.5, weight: .semibold))
            .foregroundStyle(Palette.text.opacity(0.9))
            .padding(.horizontal, 7).padding(.vertical, 3)
            .overlay(RoundedRectangle(cornerRadius: 4).strokeBorder(Palette.text.opacity(0.55), lineWidth: 1.2))
    }
}

/// The status pill (Availability.Pill look): a dot and the fixed label, in the status color.
struct DetailStatusPill: View {
    var state: Availability
    var text: String? = nil
    var large = false
    var body: some View {
        let (fill, ink): (Color, Color) = switch state.tone {
        case .green: (Palette.green, Palette.greenText)
        case .amber: (Palette.amber, Palette.amberText)
        case .red: (Palette.red, Palette.redText)
        }
        HStack(spacing: 8) {
            Circle().fill(fill).frame(width: large ? 8 : 6, height: large ? 8 : 6)
            Text(text ?? state.labels.first ?? "")
                .font(.system(size: large ? 13.5 : 11.5, weight: .semibold))
                .foregroundStyle(ink)
                .lineLimit(1)
        }
        .padding(.horizontal, large ? 12 : 8).padding(.vertical, large ? 6 : 4)
        .background(Capsule().fill(Color.black.opacity(0.72)))
        .overlay(Capsule().strokeBorder(fill.opacity(0.45), lineWidth: 1))
    }
}

/// The attribution TMDB's where-to-watch data requires (it comes from JustWatch): a small dim line under every place
/// that shows it (the title page's Where to Watch, a service page's rows). Words only, no logo.
struct JustWatchCredit: View {
    var body: some View {
        Text("Streaming Availability From JustWatch")
            .font(.system(size: 11.5, weight: .medium))
            .foregroundStyle(Palette.textTertiary)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// Lays children out left to right and wraps to a new line when they don't fit (the meta line on a narrow phone).
struct WrapLayout: Layout {
    var spacing: CGFloat = 10
    var lineSpacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, lineH: CGFloat = 0, maxX: CGFloat = 0
        for s in subviews {
            let sz = s.sizeThatFits(.unspecified)
            if x > 0 && x + sz.width > width { x = 0; y += lineH + lineSpacing; lineH = 0 }
            x += sz.width + spacing
            maxX = max(maxX, x - spacing)
            lineH = max(lineH, sz.height)
        }
        return CGSize(width: min(maxX, width), height: y + lineH)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, lineH: CGFloat = 0
        var line: [(LayoutSubviews.Element, CGSize, CGFloat)] = []
        func flush() {
            for (s, sz, px) in line { s.place(at: CGPoint(x: px, y: y + (lineH - sz.height) / 2), proposal: ProposedViewSize(sz)) }
            line.removeAll()
        }
        for s in subviews {
            let sz = s.sizeThatFits(.unspecified)
            if x > bounds.minX && x + sz.width > bounds.maxX {
                flush(); x = bounds.minX; y += lineH + lineSpacing; lineH = 0
            }
            line.append((s, sz, x))
            x += sz.width + spacing
            lineH = max(lineH, sz.height)
        }
        flush()
    }
}

/// TMDB art with a quiet placeholder and a short fade-in.
struct PageImage: View {
    var url: URL?
    var placeholder: String? = nil
    var radius: CGFloat = 10
    var body: some View {
        AsyncImage(url: url, transaction: Transaction(animation: .easeOut(duration: 0.25))) { phase in
            switch phase {
            case .success(let img): img.resizable().scaledToFill()
            default:
                ZStack {
                    Palette.surface
                    if let placeholder, url == nil || phase.error != nil {
                        Text(placeholder)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Palette.textSecondary)
                            .multilineTextAlignment(.center)
                            .lineLimit(2)
                            .padding(10)
                    }
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

/// A card's press look: the rainbow border on the art, a small scale (cards get the border only).
struct CardPressStyle: ButtonStyle {
    var radius: CGFloat = 10
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .rainbowBorder(configuration.isPressed, radius: radius, width: 2.5)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.14), value: configuration.isPressed)
    }
}

/// A poster with its title and a dim line under it (More Like This, a person's titles).
struct PosterTile: View {
    var title: Title
    var width: CGFloat
    var pill: Availability? = nil
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PageImage(url: title.poster("w342"), placeholder: title.name)
                .frame(width: width, height: width * 1.5)
                .overlay(alignment: .bottomLeading) {
                    // A watched title: the gold Watched pill (it replaces the availability one).
                    if WatchedState.shared.title(title) { WatchedPill().frame(maxWidth: width - 12, alignment: .leading).padding(6) }
                    else if let pill { AvailabilityPill(pill).padding(6) }
                }
            Text(title.name)
                .font(.system(size: 13.5, weight: .medium))
                .foregroundStyle(Palette.text)
                .lineLimit(1)
                .padding(.top, 9)
            Text(TitleMeta.subline(title))
                .font(.system(size: 12))
                .foregroundStyle(Palette.textSecondary)
                .lineLimit(1)
                .padding(.top, 3)
        }
        .frame(width: width, alignment: .leading)
        .contentShape(Rectangle())
    }
}

/// A row heading on the title page.
struct DetailsHeading: View {
    var text: String
    var body: some View {
        Text(text)
            .font(.system(size: 20, weight: .bold))
            .foregroundStyle(Palette.text)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }
}

/// The Information grid (InfoGrid): small tracked caps over values, `cols` columns, a cell may span two.
struct InfoGridView: View {
    var cells: [TitleInfo.Cell]
    var cols: Int
    var body: some View {
        let rows = InfoWords.pack(cells, cols: cols)
        Grid(alignment: .topLeading, horizontalSpacing: 22, verticalSpacing: 20) {
            ForEach(rows.indices, id: \.self) { r in
                GridRow(alignment: .top) {
                    ForEach(rows[r], id: \.label) { c in
                        VStack(alignment: .leading, spacing: 7) {
                            Text(c.label.uppercased())
                                .font(.system(size: 11, weight: .semibold))
                                .tracking(1.3)
                                .foregroundStyle(Palette.textSecondary)
                                .lineLimit(1)
                            Text(c.value)
                                .font(.system(size: 14.5, weight: .medium))
                                .foregroundStyle(Palette.text.opacity(0.94))
                                .lineSpacing(2)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .topLeading)
                        .gridCellColumns(min(max(c.span, 1), cols))
                        .accessibilityElement(children: .combine)
                    }
                    let used = rows[r].reduce(0) { $0 + min(max($1.span, 1), cols) }
                    if used < cols {
                        Color.clear.frame(height: 1).gridCellColumns(cols - used)
                    }
                }
            }
        }
    }
}

/// Where the Play button goes: Dream Player when it's wired (PagesServices.play), else Dream Core is asked directly
/// so the page can say plainly what's missing.
@MainActor
enum PlayLauncher {
    static func play(_ request: PlayRequest, onError: @escaping @MainActor (String) -> Void) {
        // Background look-aheads (the pills' checks) step aside until the player closes.
        NotificationCenter.default.post(name: .dreamPlayStarting, object: nil)
        if let play = PagesServices.play { play(request); return }
        Task { @MainActor in
            do {
                _ = try await Services.shared.playback.resolve(request)
                onError("Dream Player is getting ready. Try again in a moment.")
            } catch let e as DreamError {
                onError(e.message)
            } catch {
                onError("Couldn't start playback. Try again.")
            }
        }
    }
}
