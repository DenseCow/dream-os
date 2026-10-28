import SwiftUI
import DreamCore

/// The scores after a meta line (Ratings.kt): the tomato with its colored Tomatometer, then the audience score exactly as
/// published ("IMDb" tag + "6.5", else "TMDB" + "7.1"). No stars anywhere.
public struct ScoreBadges: View {
    let scores: Scores
    let font: Font
    public init(_ scores: Scores, font: Font = Cinema.Fonts.meta) { self.scores = scores; self.font = font }

    public var body: some View {
        HStack(spacing: 14) {
            if let v = scores.tomatometer {
                HStack(spacing: 6) {
                    TomatoGlyph().frame(width: 15, height: 15)
                    Text("\(v)%").font(font.weight(.semibold)).foregroundStyle(Self.scoreColor(v)).fixedSize()
                }
                .fixedSize()
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("Rotten Tomatoes \(v) percent")
            }
            if let badge = scores.audienceBadge, let space = badge.firstIndex(of: " ") {
                let tag = String(badge[..<space]), number = String(badge[badge.index(after: space)...])
                HStack(spacing: 7) {
                    Text(tag)
                        .font(.system(size: 9.5, weight: .bold)).tracking(0.4)
                        .foregroundStyle(.white.opacity(0.95))
                        .lineLimit(1).fixedSize()
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.white.opacity(0.16), in: Capsule())
                    Text(number).font(font.weight(.semibold)).foregroundStyle(.white.opacity(0.92)).fixedSize()
                }
                .fixedSize()
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(badge)
            }
        }
        .lineLimit(1)
        // Whole, always: a crowded row wraps or moves the badges to the next line, never squeezes them ("31...", "TI").
        .fixedSize()
    }

    /// Green 75+, amber 60+, red below, eased a touch toward white.
    public static func scoreColor(_ score: Int) -> Color {
        let c: UInt32 = score >= 75 ? 0x4ADE80 : score >= 60 ? 0xFBBF24 : 0xF87171
        return Color(hex: Rainbow.mixHex(c, 0xFFFFFF, 0.08))
    }
}

/// A tomato drawn in code: a round red body with a soft highlight, a five-leaf calyx and a stem.
public struct TomatoGlyph: View {
    public init() {}
    public var body: some View {
        Canvas { c, size in
            let w = size.width, h = size.height
            c.fill(Path(ellipseIn: CGRect(x: w * 0.06, y: h * 0.2, width: w * 0.88, height: h * 0.78)), with: .color(Color(hex: 0xF2553F)))
            c.fill(Path(ellipseIn: CGRect(x: w * 0.2, y: h * 0.34, width: w * 0.22, height: h * 0.18)), with: .color(.white.opacity(0.31)))
            let cx = w * 0.5, cy = h * 0.27, outer = w * 0.3, inner = w * 0.07
            var calyx = Path()
            for i in 0..<10 {
                let r = i % 2 == 0 ? outer : inner
                let a = (-90.0 + Double(i) * 36.0) * .pi / 180
                let p = CGPoint(x: cx + r * cos(a), y: cy + r * sin(a) * 0.55)
                if i == 0 { calyx.move(to: p) } else { calyx.addLine(to: p) }
            }
            calyx.closeSubpath()
            let leaf = Color(hex: 0x4CC46A)
            c.fill(calyx, with: .color(leaf))
            var stem = Path()
            stem.move(to: CGPoint(x: cx, y: cy))
            stem.addLine(to: CGPoint(x: cx + w * 0.07, y: h * 0.05))
            c.stroke(stem, with: .color(leaf), style: StrokeStyle(lineWidth: w * 0.09, lineCap: .round))
        }
        .accessibilityHidden(true)
    }
}
