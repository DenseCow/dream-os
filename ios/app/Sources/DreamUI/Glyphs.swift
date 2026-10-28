import SwiftUI

// Drawn status marks (fire-home StatusGlyph / CheckInk in DreamSetupPages.kt). Never text characters.

/// BrandRow.Mark: a solid disc in the status color with the shape knocked out in near-black.
public enum StatusMarkKind: Sendable, Hashable {
    /// Green disc with a check.
    case ok
    /// Red disc with an exclamation mark.
    case warn
    /// White disc with three dots (working).
    case busy
    /// A quiet hollow ring: not on yet (same footprint, so lists line up).
    case off
    /// Mid-gray disc with a check: picked (reads on dark rows and on the pearl pressed row).
    case pick
}

public struct StatusMark: View {
    let kind: StatusMarkKind
    public init(_ kind: StatusMarkKind) { self.kind = kind }

    public var body: some View {
        Canvas { c, size in
            let s = min(size.width, size.height)
            let cx = size.width / 2, cy = size.height / 2
            let u = s / 100
            let ring = CGRect(x: cx - s / 2, y: cy - s / 2, width: s, height: s)
            if kind == .off {
                let w = s * 0.09
                c.stroke(Path(ellipseIn: ring.insetBy(dx: w / 2, dy: w / 2)), with: .color(.white.opacity(0.30)), lineWidth: w)
                return
            }
            let disc: Color = switch kind {
            case .ok: Palette.green
            case .warn: Palette.red
            case .pick: Color(red: 150 / 255, green: 150 / 255, blue: 158 / 255)
            default: .white.opacity(0.85)
            }
            c.fill(Path(ellipseIn: ring), with: .color(disc))
            let ink = Color(red: 14 / 255, green: 14 / 255, blue: 16 / 255)
            let style = StrokeStyle(lineWidth: s * 0.13, lineCap: .round, lineJoin: .round)
            switch kind {
            case .ok, .pick:
                var p = Path()
                p.move(to: CGPoint(x: cx - 22 * u, y: cy + 1 * u))
                p.addLine(to: CGPoint(x: cx - 7 * u, y: cy + 15 * u))
                p.addLine(to: CGPoint(x: cx + 22 * u, y: cy - 14 * u))
                c.stroke(p, with: .color(ink), style: style)
            case .warn:
                var p = Path()
                p.move(to: CGPoint(x: cx, y: cy - 22 * u))
                p.addLine(to: CGPoint(x: cx, y: cy + 4 * u))
                c.stroke(p, with: .color(ink), style: style)
                let r = s * 0.075
                c.fill(Path(ellipseIn: CGRect(x: cx - r, y: cy + 20 * u - r, width: 2 * r, height: 2 * r)), with: .color(ink))
            case .busy:
                let r = s * 0.07
                for i in -1...1 {
                    let x = cx + CGFloat(i) * 20 * u
                    c.fill(Path(ellipseIn: CGRect(x: x - r, y: cy - r, width: 2 * r, height: 2 * r)), with: .color(ink))
                }
            case .off: break
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityHidden(true)
    }
}

/// A bare drawn check (no disc), StatusMark's shape: on a swatch, a key, or over a disc that's already there.
/// Use it as a Shape: `CheckInk().stroke(color, style: CheckInk.style(size: 20))`, or the `CheckMark` view.
public struct CheckInk: Shape {
    public init() {}
    public func path(in r: CGRect) -> Path {
        let s = min(r.width, r.height), u = s / 100
        let cx = r.midX, cy = r.midY
        var p = Path()
        p.move(to: CGPoint(x: cx - 22 * u, y: cy + 1 * u))
        p.addLine(to: CGPoint(x: cx - 7 * u, y: cy + 15 * u))
        p.addLine(to: CGPoint(x: cx + 22 * u, y: cy - 14 * u))
        return p
    }
    /// The stroke for a check drawn `size` pt wide ([weight] 0.12 as on the TV).
    public static func style(size: CGFloat, weight: CGFloat = 0.12) -> StrokeStyle {
        StrokeStyle(lineWidth: size * weight, lineCap: .round, lineJoin: .round)
    }
}

/// A drawn check `size` pt in `color`.
public struct CheckMark: View {
    let color: Color
    let size: CGFloat
    let weight: CGFloat
    public init(color: Color = Palette.text, size: CGFloat = 20, weight: CGFloat = 0.12) { self.color = color; self.size = size; self.weight = weight }
    public var body: some View {
        CheckInk().stroke(color, style: CheckInk.style(size: size, weight: weight)).frame(width: size, height: size).accessibilityHidden(true)
    }
}
