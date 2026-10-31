import SwiftUI
import DreamUI

// Drawn notice glyphs (Dream OS line art: monoline pearl strokes, no emoji). The TV's banner shows each kind's emoji and
// swaps it with a smiley; here both are drawn: the kind's glyph, and a pearl face (DreamNoticesUi FaceDrawable).

enum NoticeGlyphKind: Equatable {
    case bell, hourglass, warning, tv, sparkle, calendar, gauge, check, smile, gentleSmile

    /// The banner's glyph for a notice (DreamNoticesUi.emojiFor).
    static func banner(_ n: Notice) -> NoticeGlyphKind {
        if n.type == .summary { return .calendar }
        if n.severity == .critical || n.type == .limit { return .warning }
        switch n.type {
        case .expiry: return .hourglass
        case .episodes: return .tv
        case .update: return .sparkle
        default: return .bell
        }
    }

    /// The list row's glyph (DreamNoticesUi.iconFor).
    static func row(_ n: Notice) -> NoticeGlyphKind {
        switch n.type {
        case .expiry: .hourglass
        case .limit: .gauge
        case .episodes: .tv
        case .update: .sparkle
        case .summary: .calendar
        case .info: .bell
        }
    }
}

/// One glyph, drawn in a 100-unit box scaled to its frame.
struct NoticeGlyph: View {
    var kind: NoticeGlyphKind
    var color: Color = Palette.pearl
    var weight: CGFloat = 7.5

    var body: some View {
        Canvas { ctx, size in
            let s = min(size.width, size.height)
            let u = s / 100
            let ox = (size.width - s) / 2, oy = (size.height - s) / 2
            func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: ox + x * u, y: oy + y * u) }
            let line = StrokeStyle(lineWidth: weight * u, lineCap: .round, lineJoin: .round)
            func stroke(_ path: Path) { ctx.stroke(path, with: .color(color), style: line) }
            func dot(_ x: CGFloat, _ y: CGFloat, _ r: CGFloat, _ c: Color) {
                ctx.fill(Path(ellipseIn: CGRect(x: ox + (x - r) * u, y: oy + (y - r) * u, width: 2 * r * u, height: 2 * r * u)), with: .color(c))
            }
            switch kind {
            case .bell:
                var b = Path()
                b.move(to: p(24, 70)); b.addLine(to: p(76, 70))
                b.move(to: p(30, 70))
                b.addCurve(to: p(34, 47), control1: p(33, 64), control2: p(34, 58))
                b.addCurve(to: p(50, 27), control1: p(34, 35), control2: p(41, 27))
                b.addCurve(to: p(66, 47), control1: p(59, 27), control2: p(66, 35))
                b.addCurve(to: p(70, 70), control1: p(66, 58), control2: p(67, 64))
                b.move(to: p(50, 19)); b.addLine(to: p(50, 27))
                stroke(b)
                var c = Path()
                c.addArc(center: p(50, 74), radius: 7 * u, startAngle: .degrees(20), endAngle: .degrees(160), clockwise: false)
                stroke(c)
            case .hourglass:
                var h = Path()
                h.move(to: p(30, 18)); h.addLine(to: p(70, 18))
                h.move(to: p(30, 82)); h.addLine(to: p(70, 82))
                h.move(to: p(36, 18)); h.addCurve(to: p(50, 50), control1: p(36, 38), control2: p(50, 42))
                h.addCurve(to: p(36, 82), control1: p(50, 58), control2: p(36, 62))
                h.move(to: p(64, 18)); h.addCurve(to: p(50, 50), control1: p(64, 38), control2: p(50, 42))
                h.addCurve(to: p(64, 82), control1: p(50, 58), control2: p(64, 62))
                stroke(h)
                var sand = Path()
                sand.move(to: p(41, 79)); sand.addLine(to: p(59, 79)); sand.addLine(to: p(50, 67)); sand.closeSubpath()
                ctx.fill(sand, with: .color(color))
            case .warning:
                var t = Path()
                t.move(to: p(50, 17)); t.addLine(to: p(85, 80)); t.addLine(to: p(15, 80)); t.closeSubpath()
                stroke(t)
                var e = Path()
                e.move(to: p(50, 41)); e.addLine(to: p(50, 59))
                stroke(e)
                dot(50, 69, 4.6, color)
            case .tv:
                stroke(Path(roundedRect: CGRect(x: ox + 16 * u, y: oy + 30 * u, width: 68 * u, height: 44 * u), cornerRadius: 8 * u))
                var a = Path()
                a.move(to: p(38, 15)); a.addLine(to: p(50, 28)); a.addLine(to: p(62, 15))
                a.move(to: p(36, 84)); a.addLine(to: p(64, 84))
                stroke(a)
            case .sparkle:
                var s1 = Path()
                s1.move(to: p(44, 16))
                s1.addCurve(to: p(74, 50), control1: p(47, 38), control2: p(54, 47))
                s1.addCurve(to: p(44, 84), control1: p(54, 53), control2: p(47, 62))
                s1.addCurve(to: p(14, 50), control1: p(41, 62), control2: p(34, 53))
                s1.addCurve(to: p(44, 16), control1: p(34, 47), control2: p(41, 38))
                s1.closeSubpath()
                stroke(s1)
                var s2 = Path()
                s2.move(to: p(78, 14)); s2.addLine(to: p(78, 32))
                s2.move(to: p(69, 23)); s2.addLine(to: p(87, 23))
                stroke(s2)
            case .calendar:
                stroke(Path(roundedRect: CGRect(x: ox + 18 * u, y: oy + 24 * u, width: 64 * u, height: 58 * u), cornerRadius: 9 * u))
                var c = Path()
                c.move(to: p(18, 42)); c.addLine(to: p(82, 42))
                c.move(to: p(36, 16)); c.addLine(to: p(36, 30))
                c.move(to: p(64, 16)); c.addLine(to: p(64, 30))
                stroke(c)
                for (x, y) in [(36.0, 57.0), (50.0, 57.0), (64.0, 57.0), (36.0, 70.0), (50.0, 70.0)] { dot(x, y, 4, color) }
            case .gauge:
                var g = Path()
                g.addArc(center: p(50, 62), radius: 34 * u, startAngle: .degrees(180), endAngle: .degrees(0), clockwise: false)
                g.move(to: p(50, 62)); g.addLine(to: p(72, 40))
                stroke(g)
                dot(50, 62, 5.5, color)
            case .check:
                CheckPath.draw(ctx, rect: CGRect(x: ox, y: oy, width: s, height: s), color: color, weight: weight * u * 1.3)
            case .smile, .gentleSmile:
                // A pearl face with ink features (the smiley the TV swaps in).
                dot(50, 50, 40, color)
                let ink = Palette.ink
                if kind == .smile {
                    dot(36, 40, 5.5, ink); dot(64, 40, 5.5, ink)
                } else {
                    var eyes = Path()
                    eyes.addArc(center: p(36, 43), radius: 7 * u, startAngle: .degrees(200), endAngle: .degrees(340), clockwise: false)
                    eyes.move(to: p(57.4, 40.6))
                    eyes.addArc(center: p(64, 43), radius: 7 * u, startAngle: .degrees(200), endAngle: .degrees(340), clockwise: false)
                    ctx.stroke(eyes, with: .color(ink), style: StrokeStyle(lineWidth: 5 * u, lineCap: .round))
                }
                var m = Path()
                m.addArc(center: p(50, 52), radius: 20 * u, startAngle: .degrees(25), endAngle: .degrees(155), clockwise: false)
                ctx.stroke(m, with: .color(ink), style: StrokeStyle(lineWidth: 5.5 * u, lineCap: .round))
            }
        }
        .accessibilityHidden(true)
    }
}

private enum CheckPath {
    static func draw(_ ctx: GraphicsContext, rect: CGRect, color: Color, weight: CGFloat) {
        ctx.stroke(CheckInk().path(in: rect.insetBy(dx: -rect.width * 0.35, dy: -rect.height * 0.35)), with: .color(color),
                   style: StrokeStyle(lineWidth: weight, lineCap: .round, lineJoin: .round))
    }
}

/// An arc in the fading rainbow turning, the live banner's loader (runs only while a live banner is working; the hue is
/// read in the turn's own frames).
struct NoticeLoader: View {
    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30)) { t in
            let a = t.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.1) / 1.1 * 360
            Circle()
                .trim(from: 0, to: 0.72)
                .stroke(Color(hex: Rainbow.fadeHex(t.date)), style: StrokeStyle(lineWidth: 2.4, lineCap: .round))
                .rotationEffect(.degrees(a))
        }
        .accessibilityHidden(true)
    }
}

/// The icon slot: the severity's soft halo, a small dark disc in a thin rainbow ring, the glyph (or smiley) inside.
/// The swap is a short spin-out / pop-in morph; nothing moves between swaps.
struct NoticeFace: View {
    let state: BannerState
    var size: CGFloat = 38
    @State private var shown: NoticeGlyphKind?
    @State private var scale: CGFloat = 1
    @State private var turn: Double = 0
    @State private var alpha: Double = 1
    @State private var haloUp = false

    private var target: NoticeGlyphKind {
        switch state.face {
        case .working: return .bell
        case .result(let good) where !state.smiling: return good ? .check : .warning
        default:
            if state.smiling { return state.notice.severity == .critical ? .gentleSmile : .smile }
            return NoticeGlyphKind.banner(state.notice)
        }
    }

    private var accent: Color { NoticeStyle.severityColor(state.notice.severity) }

    var body: some View {
        ZStack {
            Circle().fill(accent.opacity(0.34))
                .frame(width: size, height: size)
                .scaleEffect(haloUp ? 1.22 : 1)
                .opacity(haloUp ? 0.9 : 0.35)
            Circle().fill(Color(hex: 0x26262C)).frame(width: size - 6, height: size - 6)
            RainbowRing(lineWidth: 1.2).opacity(0.8).frame(width: size - 6, height: size - 6)
            if state.face == .working {
                NoticeLoader().frame(width: size * 0.5, height: size * 0.5)
            } else {
                NoticeGlyph(kind: shown ?? target, color: glyphColor(shown ?? target))
                    .frame(width: size * 0.56, height: size * 0.56)
                    .scaleEffect(scale)
                    .rotationEffect(.degrees(turn))
                    .opacity(alpha)
            }
        }
        .frame(width: size, height: size)
        .onAppear { shown = target; glowOnce(delay: 0.26) }
        .onChange(of: target) { _, to in morph(to) }
        .onChange(of: state.glow) { _, _ in glowOnce(delay: 0.12) }
    }

    private func glyphColor(_ k: NoticeGlyphKind) -> Color {
        switch k {
        case .check: Palette.greenText
        case .warning where state.face == .result(good: false): Palette.redText
        default: Palette.pearl
        }
    }

    /// The icon spins down and out; the next pops in with a little spring bounce.
    private func morph(_ to: NoticeGlyphKind) {
        withAnimation(.easeIn(duration: 0.19)) {
            scale = 0.3; turn = -110; alpha = 0
        } completion: {
            shown = to
            turn = 40
            withAnimation(.spring(response: 0.42, dampingFraction: 0.5)) { scale = 1; turn = 0; alpha = 1 }
        }
    }

    private func glowOnce(delay: Double) {
        withAnimation(.easeOut(duration: 0.34).delay(delay)) { haloUp = true } completion: {
            withAnimation(.easeInOut(duration: 0.7)) { haloUp = false }
        }
    }
}
