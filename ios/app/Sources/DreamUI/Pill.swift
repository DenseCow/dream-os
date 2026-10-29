import SwiftUI
import DreamCore

/// The availability pill (Availability.Pill): a dark glass capsule with a hairline and a faint wash in the status color,
/// a status disc with its glyph knocked out (a check: Available Now; a video camera: CAM Only; a clock: not out yet) and
/// the label in a lighter tint of that color. One fixed label per state (STANDARDS §5); a narrow card takes the longest
/// form that fits whole ("Available Now" / "Available"), then the words alone, never an icon-only pill.
public struct AvailabilityPill: View {
    let state: Availability
    let large: Bool

    public init(_ state: Availability, large: Bool = false) { self.state = state; self.large = large }

    enum Tone { case available, cam, none }

    var tone: Tone {
        switch state {
        case .availableNow: .available
        case .camOnly: .cam
        default: .none
        }
    }

    /// Long to short ("Coming Nov 14", "Nov 14", "Soon").
    public static func labels(_ state: Availability) -> [String] {
        switch state {
        case .availableNow: return ["Available Now", "Available"]
        case .camOnly: return ["CAM Only", "CAM"]
        case .notAvailableYet: return ["Not Available Yet", "Not Yet"]
        case .comingSoon: return ["Coming Soon", "Soon"]
        case .coming(let ymd):
            let words = DreamTime.dateWords(ymd)
            var short = words
            if let p = DreamTime.parts(ymd), p.y != DreamTime.thisYear() { short = DreamTime.formatDay(ymd, "MMM yyyy") ?? words }
            var out = ["Coming \(words)", short, "Soon"]
            out = out.reduce(into: []) { if !$0.contains($1) { $0.append($1) } }
            return out
        }
    }

    var color: Color { switch tone { case .available: Palette.green; case .cam: Palette.amber; case .none: Palette.red } }
    var tint: Color { switch tone { case .available: Palette.greenText; case .cam: Palette.amberText; case .none: Palette.redText } }

    public var body: some View {
        let labels = Self.labels(state)
        ViewThatFits(in: .horizontal) {
            // Ids by position: the last fallback repeats a label ("Soon"), and a repeated id inside ViewThatFits
            // trips SwiftUI's assertion (the Home scroll crash).
            ForEach(Array(labels.enumerated()), id: \.offset) { capsule($0.element, disc: true) }
            capsule(labels.last ?? "", disc: false)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(labels.first ?? "")
    }

    private func capsule(_ label: String, disc: Bool) -> some View {
        let h: CGFloat = large ? 30 : 22
        let glyph: CGFloat = large ? 16 : 12
        return HStack(spacing: large ? 8 : 5.5) {
            if disc { PillGlyph(tone: tone, color: color).frame(width: glyph, height: glyph) }
            Text(label)
                .font(.system(size: large ? 13 : 10.5, weight: .semibold))
                .tracking(large ? 0.26 : 0.3)
                .foregroundStyle(tint)
                .lineLimit(1)
                .fixedSize()
        }
        .padding(.leading, disc ? (large ? 8 : 5.5) : (large ? 13 : 9))
        .padding(.trailing, large ? 13 : 9)
        .frame(height: h)
        .background {
            Capsule().fill(LinearGradient(colors: [Color(red: 36 / 255, green: 36 / 255, blue: 40 / 255).opacity(0.89),
                                                   Color(red: 12 / 255, green: 12 / 255, blue: 14 / 255).opacity(0.89)],
                                          startPoint: .top, endPoint: .bottom))
            Capsule().fill(color.opacity(0.09))
            Capsule().strokeBorder(color.opacity(0.36), lineWidth: 1)
        }
        .fixedSize()
    }
}

/// A title page language pill (`LanguagePills`; fire-home Availability.Pill.language) in the status pills' look:
/// audio in amber with a drawn speaker ("Korean Audio Only"), a dub or subtitles found in green with the status check
/// ("English Dub Available", "English Subtitles"), none found plain (graphite edge, gray words, no disc). Informational.
public struct LanguagePill: View {
    let pill: LanguagePills.Pill
    public init(_ pill: LanguagePills.Pill) { self.pill = pill }

    private var plain: Bool { pill.kind == .noSubs }
    private var color: Color { pill.kind == .audio ? Palette.amber : Palette.green }
    private var tint: Color { plain ? Color(red: 196 / 255, green: 196 / 255, blue: 202 / 255) : pill.kind == .audio ? Palette.amberText : Palette.greenText }

    public var body: some View {
        ViewThatFits(in: .horizontal) {
            ForEach(Array(pill.labels.enumerated()), id: \.offset) { capsule($0.element) }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(pill.labels.first ?? "")
    }

    private func capsule(_ label: String) -> some View {
        HStack(spacing: 8) {
            if !plain {
                Group {
                    if pill.kind == .audio { SpeakerGlyph(color: color) } else { PillGlyph(tone: .available, color: color) }
                }
                .frame(width: 16, height: 16)
            }
            Text(label)
                .font(.system(size: 13, weight: .semibold))
                .tracking(0.26)
                .foregroundStyle(tint)
                .lineLimit(1)
                .fixedSize()
        }
        .padding(.leading, plain ? 13 : 8)
        .padding(.trailing, 13)
        .frame(height: 30)
        .background {
            Capsule().fill(LinearGradient(colors: [Color(red: 36 / 255, green: 36 / 255, blue: 40 / 255).opacity(0.89),
                                                   Color(red: 12 / 255, green: 12 / 255, blue: 14 / 255).opacity(0.89)],
                                          startPoint: .top, endPoint: .bottom))
            if plain {
                Capsule().strokeBorder(Color.white.opacity(0.28), lineWidth: 1)
            } else {
                Capsule().fill(color.opacity(0.09))
                Capsule().strokeBorder(color.opacity(0.36), lineWidth: 1)
            }
        }
        .fixedSize()
    }
}

/// The audio pill's glyph: a speaker (box, cone, one sound arc) knocked out of its amber disc.
struct SpeakerGlyph: View {
    let color: Color
    var body: some View {
        Canvas { c, size in
            let u = min(size.width, size.height) / 2
            let cx = size.width / 2, cy = size.height / 2
            c.fill(Path(ellipseIn: CGRect(x: cx - u, y: cy - u, width: 2 * u, height: 2 * u)), with: .color(color))
            let ink = Color(red: 17 / 255, green: 17 / 255, blue: 20 / 255)
            var p = Path()
            p.move(to: CGPoint(x: cx - 0.56 * u, y: cy - 0.18 * u))
            p.addLine(to: CGPoint(x: cx - 0.30 * u, y: cy - 0.18 * u))
            p.addLine(to: CGPoint(x: cx + 0.02 * u, y: cy - 0.46 * u))
            p.addLine(to: CGPoint(x: cx + 0.02 * u, y: cy + 0.46 * u))
            p.addLine(to: CGPoint(x: cx - 0.30 * u, y: cy + 0.18 * u))
            p.addLine(to: CGPoint(x: cx - 0.56 * u, y: cy + 0.18 * u))
            p.closeSubpath()
            c.fill(p, with: .color(ink))
            var arc = Path()
            arc.addArc(center: CGPoint(x: cx + 0.20 * u, y: cy), radius: 0.40 * u,
                       startAngle: .degrees(-50), endAngle: .degrees(50), clockwise: false)
            c.stroke(arc, with: .color(ink), style: StrokeStyle(lineWidth: u * 0.17, lineCap: .round))
        }
        .accessibilityHidden(true)
    }
}

/// The Watched pill (owner-approved, STANDARDS §2 / §5; fire-home Availability.Pill's watched look): a dark
/// graphite-gold capsule (#2A2412) with a faint gold edge, a drawn gold check (CheckInk's shape, never a text mark)
/// and "Watched" in gold (#E8C468). It replaces the availability pill on a title the viewer has watched. The same
/// size as the status pills; a narrow card takes the word alone, never the check alone.
public struct WatchedPill: View {
    let large: Bool
    public init(large: Bool = false) { self.large = large }

    public static let label = "Watched"

    public var body: some View {
        ViewThatFits(in: .horizontal) {
            capsule(check: true)
            capsule(check: false)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Self.label)
    }

    private func capsule(check: Bool) -> some View {
        let h: CGFloat = large ? 30 : 22
        let glyph: CGFloat = large ? 16 : 12
        return HStack(spacing: large ? 7 : 5) {
            if check { WatchedCheck().frame(width: glyph, height: glyph) }
            Text(Self.label)
                .font(.system(size: large ? 13 : 10.5, weight: .semibold))
                .tracking(large ? 0.26 : 0.3)
                .foregroundStyle(Palette.gold)
                .lineLimit(1)
                .fixedSize()
        }
        .padding(.leading, check ? (large ? 9 : 6.5) : (large ? 13 : 9))
        .padding(.trailing, large ? 13 : 9)
        .frame(height: h)
        .background {
            Capsule().fill(Palette.goldFill.opacity(0.92))
            Capsule().strokeBorder(Palette.gold.opacity(0.34), lineWidth: 1)
        }
        .fixedSize()
    }
}

/// The Watched pill's check: CheckInk's stroke (-22,1 -> -7,15 -> 22,-14 on a 100 grid) spread across the glyph box.
struct WatchedCheck: View {
    var body: some View {
        Canvas { c, size in
            let g = min(size.width, size.height)
            let k = 0.95 * g / 44
            let cx = size.width / 2, cy = size.height / 2
            var p = Path()
            p.move(to: CGPoint(x: cx - 22 * k, y: cy + 1 * k))
            p.addLine(to: CGPoint(x: cx - 7 * k, y: cy + 15 * k))
            p.addLine(to: CGPoint(x: cx + 22 * k, y: cy - 14 * k))
            c.stroke(p, with: .color(Palette.gold), style: StrokeStyle(lineWidth: g * 0.15, lineCap: .round, lineJoin: .round))
        }
        .accessibilityHidden(true)
    }
}

/// The pill's knocked-out glyph inside its status disc.
struct PillGlyph: View {
    let tone: AvailabilityPill.Tone
    let color: Color
    var body: some View {
        Canvas { c, size in
            let u = min(size.width, size.height) / 2
            let cx = size.width / 2, cy = size.height / 2
            c.fill(Path(ellipseIn: CGRect(x: cx - u, y: cy - u, width: 2 * u, height: 2 * u)), with: .color(color))
            let ink = Color(red: 17 / 255, green: 17 / 255, blue: 20 / 255)
            var p = Path()
            switch tone {
            case .available:
                p.move(to: CGPoint(x: cx - 0.44 * u, y: cy + 0.03 * u))
                p.addLine(to: CGPoint(x: cx - 0.13 * u, y: cy + 0.33 * u))
                p.addLine(to: CGPoint(x: cx + 0.45 * u, y: cy - 0.31 * u))
                c.stroke(p, with: .color(ink), style: StrokeStyle(lineWidth: u * 0.30, lineCap: .round, lineJoin: .round))
            case .cam:
                c.fill(Path(roundedRect: CGRect(x: cx - 0.56 * u, y: cy - 0.32 * u, width: 0.72 * u, height: 0.64 * u), cornerRadius: 0.13 * u), with: .color(ink))
                p.move(to: CGPoint(x: cx + 0.10 * u, y: cy))
                p.addLine(to: CGPoint(x: cx + 0.56 * u, y: cy - 0.30 * u))
                p.addLine(to: CGPoint(x: cx + 0.56 * u, y: cy + 0.30 * u))
                p.closeSubpath()
                c.fill(p, with: .color(ink))
            case .none:
                p.move(to: CGPoint(x: cx, y: cy - 0.56 * u))
                p.addLine(to: CGPoint(x: cx, y: cy))
                p.addLine(to: CGPoint(x: cx + 0.38 * u, y: cy + 0.20 * u))
                c.stroke(p, with: .color(ink), style: StrokeStyle(lineWidth: u * 0.22, lineCap: .round, lineJoin: .round))
            }
        }
    }
}
