import SwiftUI

/// The setup's illustrations in the Dream OS line-art look (SetupArt.kt + GlyphPen): one monoline pearl icon with
/// round caps and joins, lots of air, no fills, no shading, centered on a dark-glass disc ringed in a thin line of the
/// fading rainbow with a faint glow of it behind (the brand signature). Green and red appear only as status (the
/// check's result). Drawn in a 100 × 100 box, crisp at any size, no bitmaps.
///
/// Motion: the ring's hue fades (15 steps a second while the page shows, held under Reduce Motion); the check's arc
/// turns while it runs (30 a second), then closes into a check (or a mark).
public struct SetupArt: View {
    public enum Kind: String, Sendable, CaseIterable {
        case clock, accounts, scrapers, check, personalize, done, intro, playback, speed, profiles, backup, unlock
    }
    /// The check's state. `idle`: a quiet pearl check (a page that only names the step).
    public enum CheckState: Sendable, Equatable { case idle, running, ok, fail }

    let kind: Kind
    let size: CGFloat
    let check: CheckState
    let gauge: Double
    @State private var startedAt = Date()
    @State private var closedAt: Date?
    /// Flipped once the closing has run, so the timeline stops (nothing redraws while idle).
    @State private var settled = false

    public init(_ kind: Kind, size: CGFloat = 96, check: CheckState = .idle, gauge: Double = 0) {
        self.kind = kind; self.size = size; self.check = check; self.gauge = min(1, max(0, gauge))
    }

    @Environment(\.pageActive) private var active
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var turning: Bool { kind == .check && check == .running }

    public var body: some View {
        let _ = settled
        let moving = turning || closing(Date()).isRunning
        TimelineView(.animation(minimumInterval: moving ? 1.0 / 30 : 1.0 / 15, paused: !moving && (!active || reduceMotion))) { tl in
            Canvas { ctx, sz in
                let s = min(sz.width, sz.height) / 100
                ctx.translateBy(x: sz.width / 2 - 50 * s, y: sz.height / 2 - 50 * s)
                ctx.scaleBy(x: s, y: s)
                let t = tl.date.timeIntervalSince(startedAt)
                let pen = GlyphPen(weight: 3, ring: 1.7)
                pen.backdrop(&ctx, hue: Rainbow.fadeColor(tl.date), glow: kind == .done ? 1.5 : 1)
                draw(&ctx, pen, t: t, closing: closing(tl.date).value)
            }
        }
        .frame(width: size, height: size)
        .onChange(of: check) { _, c in closedAt = (c == .ok || c == .fail) ? Date() : nil; if c == .running { startedAt = Date() } }
        .task(id: closedAt) { if closedAt != nil { try? await Task.sleep(for: .milliseconds(760)); settled.toggle() } }
        .accessibilityHidden(true)
    }

    /// The check's arc closing (0...1 over 700 ms, settling).
    private func closing(_ now: Date) -> (value: Double, isRunning: Bool) {
        guard kind == .check, let at = closedAt else { return (1, false) }
        let x = min(1, now.timeIntervalSince(at) / 0.7)
        let e = 1 - pow(1 - x, 3)
        return (e, x < 1)
    }

    // MARK: The glyphs (100 × 100; the disc is r 39, the icon keeps to about 28...72)

    private func draw(_ c: inout GraphicsContext, _ g: GlyphPen, t: Double, closing: Double) {
        switch kind {
        case .clock:
            // Your Time Zone: a globe with a meridian and the equator, a small clock in front.
            g.pen(&c, GlyphPen.circle(45, 46, 16))
            var m = Path(ellipseIn: CGRect(x: 38.5, y: 30, width: 13, height: 32))
            m.move(to: CGPoint(x: 29, y: 46)); m.addLine(to: CGPoint(x: 61, y: 46))
            g.pen(&c, m, alpha: 110 / 255, w: g.weight * 0.8)
            let face = GlyphPen.circle(60, 60, 11)
            g.knock(&c, face)
            g.pen(&c, face)
            g.pen(&c, GlyphPen.lines([(60, 54.5), (60, 60), (64.5, 62.5)]))
        case .accounts:
            // Connect Your Accounts: a key (bow with a hole, a shaft, two teeth), at an angle.
            let r = GlyphPen.rotation(-45)
            g.pen(&c, GlyphPen.circle(37, 50, 9.5).applying(r))
            g.pen(&c, GlyphPen.circle(37, 50, 3).applying(r), alpha: 150 / 255, w: g.weight * 0.8)
            var p = Path()
            p.move(to: CGPoint(x: 46.5, y: 50)); p.addLine(to: CGPoint(x: 71, y: 50))
            p.move(to: CGPoint(x: 64, y: 50)); p.addLine(to: CGPoint(x: 64, y: 57))
            p.move(to: CGPoint(x: 70, y: 50)); p.addLine(to: CGPoint(x: 70, y: 55.5))
            g.pen(&c, p.applying(r))
        case .scrapers:
            // Set Up Scrapers: a magnifier over a play symbol (searching for streams).
            g.pen(&c, GlyphPen.circle(46, 46, 13.5))
            g.pen(&c, GlyphPen.play(47, 46, 11), alpha: 200 / 255, w: g.weight * 0.9)
            g.pen(&c, GlyphPen.lines([(56, 56), (67, 67)]), w: g.weight * 1.3)
        case .check:
            checkGlyph(&c, g, t: t, closing: closing)
        case .personalize:
            // Personalize: a person and three sliders.
            g.pen(&c, GlyphPen.person(37, 33, 0.8))
            for (y, x) in [(39.0, 66.0), (48.5, 57.0), (58.0, 63.0)] {
                g.pen(&c, GlyphPen.lines([(52, y), (72, y)]), alpha: 120 / 255)
                g.dot(&c, x, y, g.weight * 1.25)
            }
        case .done:
            g.pen(&c, GlyphPen.play(50, 50, 26))
        case .intro:
            // Before You Start: a page with a folded corner and a check badge.
            var page = Path()
            page.move(to: CGPoint(x: 54, y: 28)); page.addLine(to: CGPoint(x: 39, y: 28))
            GlyphPen.arcTo(&page, CGRect(x: 34, y: 28, width: 8, height: 8), 270, -90)
            page.addLine(to: CGPoint(x: 34, y: 67))
            GlyphPen.arcTo(&page, CGRect(x: 34, y: 64, width: 8, height: 8), 180, -90)
            page.addLine(to: CGPoint(x: 57, y: 72))
            GlyphPen.arcTo(&page, CGRect(x: 54, y: 64, width: 8, height: 8), 90, -90)
            page.addLine(to: CGPoint(x: 62, y: 36)); page.closeSubpath()
            page.move(to: CGPoint(x: 54, y: 28)); page.addLine(to: CGPoint(x: 54, y: 36)); page.addLine(to: CGPoint(x: 62, y: 36))
            g.pen(&c, page)
            var lines = GlyphPen.lines([(40, 44), (55, 44)])
            lines.addPath(GlyphPen.lines([(40, 51), (55, 51)])); lines.addPath(GlyphPen.lines([(40, 58), (48, 58)]))
            g.pen(&c, lines, alpha: 120 / 255)
            let badge = GlyphPen.circle(62, 64, 9.5)
            g.knock(&c, badge, gap: 2)
            g.pen(&c, badge)
            g.pen(&c, GlyphPen.lines([(58, 64.5), (61, 67.5), (66, 61.5)]))
        case .playback:
            // Playback Preferences: a screen with Play and quality bars on it.
            g.pen(&c, GlyphPen.roundRect(28, 32, 72, 61, 5))
            g.pen(&c, GlyphPen.play(41, 46.5, 12))
            var bars = GlyphPen.lines([(55, 53), (55, 49)])
            bars.addPath(GlyphPen.lines([(60.5, 53), (60.5, 44.5)])); bars.addPath(GlyphPen.lines([(66, 53), (66, 40)]))
            g.pen(&c, bars)
            g.pen(&c, GlyphPen.lines([(42, 68), (58, 68)]), alpha: 140 / 255)
        case .speed:
            // Speed: a gauge whose arc and needle show `gauge` (0...1).
            let rect = CGRect(x: 32, y: 34, width: 36, height: 36)
            g.arc(&c, rect, 150, 240, alpha: 46 / 255)
            if gauge > 0.01 { g.arc(&c, rect, 150, 240 * gauge) }
            var ticks = Path()
            for i in 0...6 {
                let a = (150.0 + Double(i) * 40) * .pi / 180
                ticks.move(to: CGPoint(x: 50 + 13.5 * cos(a), y: 52 + 13.5 * sin(a)))
                ticks.addLine(to: CGPoint(x: 50 + 11.5 * cos(a), y: 52 + 11.5 * sin(a)))
            }
            g.pen(&c, ticks, alpha: 110 / 255, w: g.weight * 0.6)
            let a = (150.0 + 240 * gauge) * .pi / 180
            g.pen(&c, GlyphPen.lines([(50, 52), (50 + 11.5 * cos(a), 52 + 11.5 * sin(a))]))
            g.dot(&c, 50, 52, g.weight * 1.1)
        case .profiles:
            // Who's Watching: three people, the one in front in full pearl.
            g.pen(&c, GlyphPen.person(35, 35, 0.65), alpha: 130 / 255)
            g.pen(&c, GlyphPen.person(65, 35, 0.65), alpha: 130 / 255)
            let front = GlyphPen.person(50, 38, 0.9)
            g.knock(&c, front, gap: 2.6)
            g.pen(&c, front)
        case .backup:
            // Two screens, an arrow carrying everything from the old one (quiet) to the new one.
            for (l, alpha) in [(27.0, 130.0 / 255), (55.0, 1.0)] {
                g.pen(&c, GlyphPen.roundRect(l, 49, l + 18, 62, 3), alpha: alpha)
                g.pen(&c, GlyphPen.lines([(l + 5, 66), (l + 13, 66)]), alpha: alpha)
            }
            var arrow = Path()
            arrow.move(to: CGPoint(x: 36, y: 43))
            arrow.addCurve(to: CGPoint(x: 64, y: 43), control1: CGPoint(x: 40, y: 33), control2: CGPoint(x: 60, y: 33))
            arrow.addPath(GlyphPen.lines([(59.4, 40), (64, 43), (65.3, 37.6)]))
            g.pen(&c, arrow)
        case .unlock:
            // An open padlock (the shackle lifted out on one side).
            g.pen(&c, GlyphPen.roundRect(36, 48, 64, 70, 5.5))
            var sh = Path()
            sh.move(to: CGPoint(x: 42, y: 48)); sh.addLine(to: CGPoint(x: 42, y: 37))
            GlyphPen.arcTo(&sh, CGRect(x: 42, y: 29, width: 16, height: 16), 180, 180)
            sh.addLine(to: CGPoint(x: 58, y: 39))
            g.pen(&c, sh)
            g.pen(&c, GlyphPen.lines([(50, 55), (50, 62)]))
        }
    }

    /// The check: a progress arc turning on a faint track, closing into a green check (or a red mark).
    private func checkGlyph(_ c: inout GraphicsContext, _ g: GlyphPen, t: Double, closing: Double) {
        let rect = CGRect(x: 31, y: 31, width: 38, height: 38)
        let tick = GlyphPen.lines([(41.5, 50.5), (47.5, 56.5), (59, 44.5)])
        switch check {
        case .idle:
            g.pen(&c, GlyphPen.circle(50, 50, 19), alpha: 70 / 255)
            g.pen(&c, tick, w: g.weight * 1.15)
        case .running:
            g.pen(&c, GlyphPen.circle(50, 50, 19), alpha: 40 / 255)
            g.arc(&c, rect, (t * 220).truncatingRemainder(dividingBy: 360), 70 + 40 * sin(t * 2))
        case .ok, .fail:
            let tone = check == .ok ? Palette.green : Palette.red
            g.arc(&c, rect, -90, 360 * closing, color: tone)
            let mark = check == .ok ? tick : GlyphPen.lines([(50, 41), (50, 52)])
            let f = min(1, max(0, (closing - 0.35) / 0.65))
            if f > 0 { g.pen(&c, mark.trimmedPath(from: 0, to: f), color: tone, w: g.weight * 1.15) }
            if check == .fail && closing > 0.9 { g.dot(&c, 50, 59, g.weight * 0.7, color: tone) }
        }
    }
}

/// The Dream OS line-art pen (GlyphPen.kt), shared by `SetupArt` and any drawn illustration. Everything is in a
/// 100 × 100 box: `backdrop` lays the dark-glass disc (r 39) with its thin ring and faint glow in the fading rainbow; `pen`
/// strokes a monoline shape in pearl (round caps and joins); `knock` clears a gap around a shape in front so
/// overlapping lines never touch.
public struct GlyphPen: Sendable {
    public let weight: CGFloat
    public let ring: CGFloat
    public static let radius: CGFloat = 39
    public init(weight: CGFloat = 3, ring: CGFloat = 1.7) { self.weight = weight; self.ring = ring }

    private static let glass = Gradient(colors: [Color(hex: 0x2A2A31), Color(hex: 0x0B0B0E)])
    private var glassShading: GraphicsContext.Shading {
        .linearGradient(Self.glass, startPoint: CGPoint(x: 50, y: 11), endPoint: CGPoint(x: 50, y: 89))
    }

    /// The disc: a soft glow, the dark glass lit faintly from the top, the thin ring; glow and ring in [hue], the
    /// fading rainbow's color of the moment (`Rainbow.fadeColor`).
    public func backdrop(_ c: inout GraphicsContext, hue: Color, glow: Double = 1) {
        let rainbow = GraphicsContext.Shading.color(hue)
        let edge = Self.circle(50, 50, Self.radius + 1)
        for (w, a) in [(12.0, 20.0), (6.0, 46.0)] {
            var g = c
            g.opacity = min(1, a * glow / 255)
            g.stroke(edge, with: rainbow, lineWidth: w)
        }
        let disc = Self.circle(50, 50, Self.radius)
        c.fill(disc, with: glassShading)
        c.fill(disc, with: .radialGradient(Gradient(colors: [Color.white.opacity(22.0 / 255), .clear]), center: CGPoint(x: 50, y: 14),
                                           startRadius: 0, endRadius: 46))
        c.stroke(disc, with: rainbow, lineWidth: ring)
    }

    public func pen(_ c: inout GraphicsContext, _ p: Path, alpha: Double = 1, color: Color = Palette.pearl, w: CGFloat? = nil) {
        c.stroke(p, with: .color(color.opacity(alpha)), style: StrokeStyle(lineWidth: w ?? weight, lineCap: .round, lineJoin: .round))
    }

    /// An open arc in [oval] from [start] degrees (0 = 3 o'clock, clockwise) over [sweep] degrees.
    public func arc(_ c: inout GraphicsContext, _ oval: CGRect, _ start: Double, _ sweep: Double, alpha: Double = 1,
                    color: Color = Palette.pearl, w: CGFloat? = nil) {
        var p = Path()
        p.addArc(center: CGPoint(x: oval.midX, y: oval.midY), radius: oval.width / 2, startAngle: .degrees(start),
                 endAngle: .degrees(start + sweep), clockwise: sweep < 0)
        pen(&c, p, alpha: alpha, color: color, w: w)
    }

    public func dot(_ c: inout GraphicsContext, _ x: CGFloat, _ y: CGFloat, _ r: CGFloat, color: Color = Palette.pearl) {
        c.fill(Self.circle(x, y, r), with: .color(color))
    }

    /// The glass around [p] ([gap] wider than the line on each side), so a shape in front sits clear of what's behind.
    public func knock(_ c: inout GraphicsContext, _ p: Path, gap: CGFloat = 3.2) {
        c.fill(p, with: glassShading)
        c.stroke(p, with: glassShading, style: StrokeStyle(lineWidth: weight + gap * 2, lineCap: .round, lineJoin: .round))
    }

    // MARK: Shapes

    public static func circle(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat) -> Path {
        Path(ellipseIn: CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r))
    }

    public static func roundRect(_ l: CGFloat, _ t: CGFloat, _ r: CGFloat, _ b: CGFloat, _ rad: CGFloat) -> Path {
        Path(roundedRect: CGRect(x: l, y: t, width: r - l, height: b - t), cornerRadius: rad, style: .circular)
    }

    /// A polyline through [pts].
    public static func lines(_ pts: [(CGFloat, CGFloat)]) -> Path {
        var p = Path()
        guard let f = pts.first else { return p }
        p.move(to: CGPoint(x: f.0, y: f.1))
        for q in pts.dropFirst() { p.addLine(to: CGPoint(x: q.0, y: q.1)) }
        return p
    }

    /// Head and shoulders: a round head over an open arch. [s] = 1 is 24 wide and 31 tall from [top].
    public static func person(_ cx: CGFloat, _ top: CGFloat, _ s: CGFloat) -> Path {
        var p = circle(cx, top + 6.5 * s, 6.5 * s)
        p.move(to: CGPoint(x: cx - 12 * s, y: top + 31 * s))
        p.addCurve(to: CGPoint(x: cx, y: top + 17.5 * s), control1: CGPoint(x: cx - 12 * s, y: top + 22 * s), control2: CGPoint(x: cx - 6.5 * s, y: top + 17.5 * s))
        p.addCurve(to: CGPoint(x: cx + 12 * s, y: top + 31 * s), control1: CGPoint(x: cx + 6.5 * s, y: top + 17.5 * s), control2: CGPoint(x: cx + 12 * s, y: top + 22 * s))
        return p
    }

    /// A play triangle [h] tall, optically centered on ([cx], [cy]).
    public static func play(_ cx: CGFloat, _ cy: CGFloat, _ h: CGFloat) -> Path {
        let w = h * 0.9, x0 = cx - w * 0.42
        var p = Path()
        p.move(to: CGPoint(x: x0, y: cy - h / 2)); p.addLine(to: CGPoint(x: x0 + w, y: cy)); p.addLine(to: CGPoint(x: x0, y: cy + h / 2))
        p.closeSubpath()
        return p
    }

    /// Android's `Path.arcTo(oval, start, sweep)`: a line to the arc's start, then the arc (degrees, clockwise).
    public static func arcTo(_ p: inout Path, _ oval: CGRect, _ start: Double, _ sweep: Double) {
        p.addArc(center: CGPoint(x: oval.midX, y: oval.midY), radius: oval.width / 2, startAngle: .degrees(start),
                 endAngle: .degrees(start + sweep), clockwise: sweep < 0)
    }

    /// A rotation by [degrees] around the box's center.
    public static func rotation(_ degrees: Double) -> CGAffineTransform {
        CGAffineTransform(translationX: 50, y: 50).rotated(by: degrees * .pi / 180).translatedBy(x: -50, y: -50)
    }
}
