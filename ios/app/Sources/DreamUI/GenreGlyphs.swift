import SwiftUI

// The Browse by Genre and seasonal hub glyphs (fire-home GenreGlyphs.kt, SeasonGlyphs.kt, GenreArt.kt), shape for
// shape. One visual language, Apple TV / Disney+ category style: a 100 × 100 box, every glyph filling about the same
// circle, soft top-to-bottom gradients, rounded joins and caps, two line weights, a 3-tone pastel from the card's tint
// plus pearl, and a faint soft shadow straight down under filled silhouettes. Static: drawn once, nothing animates.

/// A glyph by name ("Horror", "Anime", "Season:Snowflake"...), tinted `tint` (0xRRGGBB). Unknown names: a soft sparkle.
public struct GenreGlyph: View {
    let parts: [GlyphPart]
    public init(_ name: String, tint: UInt32) { parts = GlyphBook.build(name, tint: 0xFF00_0000 | tint) }

    public var body: some View {
        Canvas { c, size in
            let s = min(size.width, size.height) / 100
            c.translateBy(x: size.width / 2 - 50 * s, y: size.height / 2 - 50 * s)
            c.scaleBy(x: s, y: s)
            let shadow = Color(argb: 0x2E0A_0618)
            for p in parts where p.shadow {
                for dy in [1.6, 3.2] as [CGFloat] {
                    let moved = p.path.offsetBy(dx: 0, dy: dy)
                    c.fill(moved, with: .color(shadow))
                    if p.line > 0 { c.stroke(moved, with: .color(shadow), style: StrokeStyle(lineWidth: p.line, lineCap: .round, lineJoin: .round)) }
                }
            }
            for p in parts {
                let grad = p.top != p.bottom
                let shading: GraphicsContext.Shading = grad
                    ? .linearGradient(Gradient(colors: [Color(argb: p.top), Color(argb: p.bottom)]), startPoint: CGPoint(x: 0, y: 16), endPoint: CGPoint(x: 0, y: 84))
                    : .color(Color(argb: p.top))
                if p.top != 0 { c.fill(p.path, with: shading) }
                if p.line > 0 {
                    let style = StrokeStyle(lineWidth: p.line, lineCap: .round, lineJoin: .round)
                    if p.lineColor == GlyphBook.fillSentinel { c.stroke(p.path, with: shading, style: style) }
                    else { c.stroke(p.path, with: .color(Color(argb: p.lineColor)), style: style) }
                }
            }
        }
        .accessibilityHidden(true)
    }
}

extension Color {
    /// 0xAARRGGBB.
    init(argb: UInt32) { self.init(hex: argb & 0xFFFFFF, alpha: Double((argb >> 24) & 0xFF) / 255) }
}

struct GlyphPart {
    var path: Path
    var top: UInt32
    var bottom: UInt32
    var line: CGFloat = 0
    var lineColor: UInt32 = 0
    var shadow = false
}

/// GenreGlyphs.GPath: path commands in the 100 × 100 box, optionally turned `rot` degrees about (px, py).
struct GP {
    var path = Path()
    var rot: CGFloat = 0
    var px: CGFloat = 0
    var py: CGFloat = 0
    init(_ rot: CGFloat = 0, _ px: CGFloat = 0, _ py: CGFloat = 0) { self.rot = rot; self.px = px; self.py = py }

    func m(_ x: CGFloat, _ y: CGFloat) -> GP { var g = self; g.path.move(to: CGPoint(x: x, y: y)); return g }
    func l(_ x: CGFloat, _ y: CGFloat) -> GP { var g = self; g.path.addLine(to: CGPoint(x: x, y: y)); return g }
    func q(_ x1: CGFloat, _ y1: CGFloat, _ x: CGFloat, _ y: CGFloat) -> GP {
        var g = self; g.path.addQuadCurve(to: CGPoint(x: x, y: y), control: CGPoint(x: x1, y: y1)); return g
    }
    func c(_ x1: CGFloat, _ y1: CGFloat, _ x2: CGFloat, _ y2: CGFloat, _ x: CGFloat, _ y: CGFloat) -> GP {
        var g = self; g.path.addCurve(to: CGPoint(x: x, y: y), control1: CGPoint(x: x1, y: y1), control2: CGPoint(x: x2, y: y2)); return g
    }
    func close() -> GP { var g = self; g.path.closeSubpath(); return g }
    func circle(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat) -> GP {
        var g = self; g.path.addEllipse(in: CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r)); return g
    }
    func oval(_ l: CGFloat, _ t: CGFloat, _ r: CGFloat, _ b: CGFloat) -> GP {
        var g = self; g.path.addEllipse(in: CGRect(x: l, y: t, width: r - l, height: b - t)); return g
    }
    func rr(_ l: CGFloat, _ t: CGFloat, _ r: CGFloat, _ b: CGFloat, _ rad: CGFloat) -> GP {
        var g = self
        g.path.addRoundedRect(in: CGRect(x: l, y: t, width: r - l, height: b - t), cornerSize: CGSize(width: rad, height: rad), style: .circular)
        return g
    }
    /// An arc of the ellipse in (l, t, r, b), degrees clockwise on screen from 3 o'clock (Android's arcTo);
    /// `connect`: a line from the current point to its start, else a new contour.
    func arc(_ l: CGFloat, _ t: CGFloat, _ r: CGFloat, _ b: CGFloat, _ start: CGFloat, _ sweep: CGFloat, connect: Bool = false) -> GP {
        var g = self
        let cx = (l + r) / 2, cy = (t + b) / 2, rx = (r - l) / 2, ry = (b - t) / 2
        func pt(_ deg: CGFloat) -> CGPoint { let a = deg * .pi / 180; return CGPoint(x: cx + rx * cos(a), y: cy + ry * sin(a)) }
        let p0 = pt(start)
        if connect, g.path.currentPoint != nil { g.path.addLine(to: p0) } else { g.path.move(to: p0) }
        let segs = max(1, Int(ceil(abs(sweep) / 90)))
        let step = sweep / CGFloat(segs)
        var a0 = start
        for _ in 0..<segs {
            let a1 = a0 + step
            let r0 = a0 * .pi / 180, r1 = a1 * .pi / 180
            let k = 4.0 / 3.0 * tan((r1 - r0) / 4)
            let c1 = CGPoint(x: cx + rx * (cos(r0) - k * sin(r0)), y: cy + ry * (sin(r0) + k * cos(r0)))
            let c2 = CGPoint(x: cx + rx * (cos(r1) + k * sin(r1)), y: cy + ry * (sin(r1) - k * cos(r1)))
            g.path.addCurve(to: pt(a1), control1: c1, control2: c2)
            a0 = a1
        }
        return g
    }

    var final: Path {
        guard rot != 0 else { return path }
        let t = CGAffineTransform(translationX: px, y: py).rotated(by: rot * .pi / 180).translatedBy(x: -px, y: -py)
        return path.applying(t)
    }
}

enum GlyphBook {
    static let line: CGFloat = 5
    static let fine: CGFloat = 3.6
    static let fillSentinel: UInt32 = 1

    static func mix(_ a: UInt32, _ b: UInt32, _ f: CGFloat) -> UInt32 {
        func ch(_ s: UInt32) -> UInt32 {
            let x = CGFloat((a >> s) & 0xFF), y = CGFloat((b >> s) & 0xFF)
            return UInt32(max(0, min(255, x * (1 - f) + y * f))) << s
        }
        return ch(24) | ch(16) | ch(8) | ch(0)
    }
    static func alpha(_ c: UInt32, _ a: CGFloat) -> UInt32 {
        (c & 0x00FF_FFFF) | (UInt32(max(0, min(255, CGFloat((c >> 24) & 0xFF) * a))) << 24)
    }

    struct Tones {
        let pearl, light, mid, deep, coral: UInt32
        init(_ tint: UInt32) {
            pearl = mix(0xFFFF_FFFF, tint, 0.08)
            light = mix(tint, 0xFFFF_FFFF, 0.66)
            mid = mix(tint, 0xFFFF_FFFF, 0.40)
            deep = mix(tint, 0xFF2A_2340, 0.50)
            coral = mix(0xFFFF_7A86, tint, 0.15)
        }
    }

    static func cosd(_ d: CGFloat) -> CGFloat { cos(d * .pi / 180) }
    static func sind(_ d: CGFloat) -> CGFloat { sin(d * .pi / 180) }

    static func star(_ cx: CGFloat, _ cy: CGFloat, _ outer: CGFloat, _ inner: CGFloat, _ points: Int, _ rot: CGFloat = -90) -> GP {
        var p = GP()
        for i in 0..<(points * 2) {
            let r = i % 2 == 0 ? outer : inner
            let a = rot + CGFloat(i) * 180 / CGFloat(points)
            let x = cx + r * cosd(a), y = cy + r * sind(a)
            p = i == 0 ? p.m(x, y) : p.l(x, y)
        }
        return p.close()
    }

    static func heart(_ cx: CGFloat, _ cy: CGFloat, _ w: CGFloat, _ rot: CGFloat = 0) -> GP {
        let h = w * 0.9
        return GP(rot, cx, cy).m(cx, cy + h * 0.5)
            .c(cx - w * 0.72, cy + h * 0.02, cx - w * 0.52, cy - h * 0.6, cx, cy - h * 0.2)
            .c(cx + w * 0.52, cy - h * 0.6, cx + w * 0.72, cy + h * 0.02, cx, cy + h * 0.5).close()
    }

    /// A heart whose point is at (px, py), turned `rot` about its point (SeasonGlyphs.heartAt).
    static func heartAt(_ px: CGFloat, _ py: CGFloat, _ w: CGFloat, _ rot: CGFloat = 0) -> GP {
        let h = w * 0.9, cy = py - h * 0.5
        return GP(rot, px, py).m(px, py)
            .c(px - w * 0.72, cy + h * 0.02, px - w * 0.52, cy - h * 0.6, px, cy - h * 0.2)
            .c(px + w * 0.52, cy - h * 0.6, px + w * 0.72, cy + h * 0.02, px, py).close()
    }

    static func build(_ name: String, tint: UInt32) -> [GlyphPart] {
        let t = Tones(tint)
        var out: [GlyphPart] = []
        func body(_ p: GP, _ top: UInt32? = nil, _ bottom: UInt32? = nil) {
            out.append(GlyphPart(path: p.final, top: top ?? t.light, bottom: bottom ?? t.mid, line: 2.4, lineColor: fillSentinel, shadow: true))
        }
        func flat(_ p: GP, _ c: UInt32) { out.append(GlyphPart(path: p.final, top: c, bottom: c, line: 2.4, lineColor: fillSentinel)) }
        func dot(_ p: GP, _ c: UInt32) { out.append(GlyphPart(path: p.final, top: c, bottom: c)) }
        func stroke(_ p: GP, _ c: UInt32, _ w: CGFloat = line) { out.append(GlyphPart(path: p.final, top: 0, bottom: 0, line: w, lineColor: c)) }
        func gleam(_ l: CGFloat, _ tp: CGFloat, _ r: CGFloat, _ b: CGFloat) {
            let c = alpha(0xFFFF_FFFF, 0.55); out.append(GlyphPart(path: GP().oval(l, tp, r, b).final, top: c, bottom: c))
        }
        func blossom(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat) {
            var petals = GP()
            for i in 0..<5 {
                let a = CGFloat(-90 + i * 72)
                petals = petals.circle(cx + r * 0.52 * cosd(a), cy + r * 0.52 * sind(a), r * 0.46)
            }
            body(petals, t.pearl, t.light)
            dot(GP().circle(cx, cy, r * 0.2), t.mid)
        }
        func sparkle(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat) { flat(star(cx, cy, r, r * 0.31, 4), t.pearl) }
        let rose = mix(t.light, t.coral, 0.55), roseDeep = mix(t.mid, t.coral, 0.7)
        let fine = Self.fine, line = Self.line

        switch name {
        case "Action":
            body(GP().circle(24, 73, 11).circle(37, 80, 8.5).circle(16, 62, 7), t.pearl, t.light)
            body(star(55, 45, 34, 23, 9, -72))
            flat(star(55, 45, 16, 12, 8, -64), t.pearl)
        case "Comedy":
            body(GP().circle(50, 50, 31))
            stroke(GP().arc(33, 37, 45, 49, 200, 140), t.deep, fine)
            stroke(GP().arc(55, 37, 67, 49, 200, 140), t.deep, fine)
            flat(GP().m(34, 56).q(50, 59, 66, 56).q(62, 78, 50, 78).q(38, 78, 34, 56).close(), t.deep)
            dot(GP().oval(43, 68, 57, 76), t.coral)
            gleam(33, 27, 44, 33)
        case "Drama":
            body(GP().m(22, 26).q(50, 15, 78, 26).q(81, 68, 50, 84).q(19, 68, 22, 26).close())
            stroke(GP().arc(31, 42, 45, 54, 20, 140), t.deep, fine)
            stroke(GP().arc(55, 42, 69, 54, 20, 140), t.deep, fine)
            stroke(GP().arc(39, 64, 61, 80, 205, 130), t.deep, fine)
            flat(GP().m(37, 57).q(32, 65, 37, 68).q(42, 65, 37, 57).close(), t.pearl)
            gleam(30, 24, 42, 29)
        case "Sci-Fi", "Sci-Fi & Fantasy":
            stroke(GP(-20, 44, 58).arc(7, 47, 81, 69, 180, 180), t.pearl, fine)
            body(GP().circle(44, 58, 23))
            stroke(GP(-20, 44, 58).arc(7, 47, 81, 69, 0, 180), t.pearl, fine)
            gleam(31, 42, 42, 48)
            flat(GP(40, 79, 22).m(74, 35).q(79, 48, 84, 35).close(), t.mid)
            body(GP(40, 79, 22).m(79, 5).q(91, 15, 86, 35).l(72, 35).q(67, 15, 79, 5).close(), t.pearl, t.light)
            dot(GP(40, 79, 22).circle(79, 19, 3.6), t.mid)
        case "Horror":
            body(GP().m(22, 80).l(22, 46).c(22, 11, 78, 11, 78, 46).l(78, 80)
                .q(73.3, 86, 68.7, 81).q(64, 76, 59.3, 81).q(54.7, 86, 50, 81)
                .q(45.3, 76, 40.7, 81).q(36, 86, 31.3, 81).q(26.7, 76, 22, 80).close(), t.pearl, t.light)
            dot(GP().oval(36.5, 39, 44.5, 50).oval(55.5, 39, 63.5, 50), t.deep)
            dot(GP().oval(46, 55, 54, 63), t.deep)
            gleam(31, 24, 43, 30)
        case "Thriller":
            flat(GP().rr(44, 12, 56, 24, 3), t.mid)
            flat(GP().rr(38, 9, 62, 15, 3), t.light)
            body(GP().circle(50, 56, 29))
            flat(GP().circle(50, 56, 22), t.pearl)
            stroke(GP().m(31, 58).l(39, 58).l(44, 48).l(50, 68).l(55, 52).l(59, 58).l(69, 58), t.deep, fine)
        case "Romance":
            body(heart(43, 56, 46, -8), mix(t.light, t.coral, 0.55), mix(t.mid, t.coral, 0.7))
            gleam(27, 42, 37, 48)
            body(heart(74, 29, 25, 14), t.pearl, mix(t.light, t.coral, 0.35))
        case "Animation":
            body(star(37, 39, 27, 12.5, 5), t.pearl, t.light)
            dot(GP().circle(56, 57, 2).circle(61.5, 62.5, 2), t.pearl)
            flat(GP(-45, 72, 72).rr(90, 66, 99, 78, 3), t.light)
            body(GP(-45, 72, 72).rr(62, 66, 93, 78, 1.5))
            flat(GP(-45, 72, 72).m(62, 66).l(51, 72).l(62, 78).close(), t.pearl)
            dot(GP(-45, 72, 72).m(55.5, 69.6).l(51, 72).l(55.5, 74.4).close(), t.deep)
        case "Anime":
            stroke(GP().m(14, 84).c(36, 70, 54, 52, 86, 20), t.deep, line)
            stroke(GP().m(47, 57).q(53, 69, 64, 74), t.deep, fine)
            blossom(66, 31, 19)
            blossom(33, 56, 15)
            blossom(67, 74, 11)
        case "Documentary":
            stroke(GP().m(44, 62).l(30, 88).m(44, 62).l(44, 90).m(44, 62).l(58, 88), t.deep, fine)
            body(GP().circle(32, 25, 9).circle(55, 25, 9), t.pearl, t.light)
            body(GP().rr(18, 34, 70, 62, 7))
            flat(GP().m(70, 42).l(85, 35).l(85, 61).l(70, 54).close(), t.mid)
            dot(GP().circle(28, 43, 3), t.coral)
        case "Family":
            flat(GP().rr(63, 22, 71, 38, 2), t.mid)
            body(GP().rr(27, 47, 73, 84, 4), t.pearl, t.light)
            body(GP().m(20, 50).l(50, 23).l(80, 50).close(), t.light, t.mid)
            flat(GP().rr(56, 65, 66, 84, 3), t.mid)
            dot(heart(41, 65, 14), t.coral)
        case "Crime":
            let r: [CGFloat] = [5, 10.5, 16, 21.5, 27]
            let start: [CGFloat] = [190, 175, 160, 185, 168]
            let sweep: [CGFloat] = [300, 290, 250, 280, 235]
            for i in r.indices {
                stroke(GP().arc(50 - r[i] * 0.8, 52 - r[i], 50 + r[i] * 0.8, 52 + r[i], start[i], sweep[i]), i % 2 == 0 ? t.light : t.mid, fine)
            }
            stroke(GP().arc(24, 26, 76, 96, 205, 130), t.pearl, fine)
        case "Mystery":
            stroke(GP().m(64, 64).l(82, 82), t.mid, 9)
            dot(GP().circle(44, 44, 25), alpha(t.pearl, 0.30))
            stroke(GP().circle(44, 44, 25), t.light, line + 1)
            stroke(GP().arc(37, 30, 51, 44, 180, 230).l(44, 47).l(44, 50), t.pearl, fine + 0.4)
            dot(GP().circle(44, 57, 2.8), t.pearl)
        case "Reality":
            stroke(GP().m(50, 32).l(37, 15).m(50, 32).l(63, 15), t.deep, fine)
            dot(GP().circle(37, 14, 3.4).circle(63, 14, 3.4), t.light)
            flat(GP().rr(28, 80, 36, 87, 2).rr(64, 80, 72, 87, 2), t.mid)
            body(GP().rr(17, 32, 83, 82, 11))
            flat(GP().rr(25, 40, 63, 74, 7), t.deep)
            dot(GP().circle(31.5, 46.5, 3.2), t.coral)
            dot(GP().circle(73, 49, 4).circle(73, 62, 4), t.pearl)
        case "Kids":
            body(GP().circle(29, 31, 11).circle(71, 31, 11), t.mid, t.mid)
            dot(GP().circle(29, 31, 5.5).circle(71, 31, 5.5), t.light)
            body(GP().circle(50, 54, 29))
            flat(GP().oval(38, 57, 62, 76), t.pearl)
            dot(GP().oval(45, 59, 55, 65.5), t.deep)
            dot(GP().circle(39.5, 47, 3.3).circle(60.5, 47, 3.3), t.deep)
            gleam(35, 31, 46, 36)
        case "Adventure", "Action & Adventure":
            flat(GP().rr(44.5, 15, 55.5, 22, 3), t.mid)
            body(GP().circle(50, 53, 31))
            flat(GP().circle(50, 53, 24), t.pearl)
            dot(GP(35, 50, 53).m(50, 33).l(56, 53).l(44, 53).close(), t.coral)
            dot(GP(35, 50, 53).m(50, 73).l(56, 53).l(44, 53).close(), t.deep)
            dot(GP().circle(50, 53, 3), t.pearl)
            gleam(31, 33, 42, 38)
        case "Fantasy":
            body(GP().rr(19, 40, 33, 85, 2).rr(67, 40, 81, 85, 2), t.light, t.mid)
            body(GP().rr(29, 56, 71, 85, 2), t.pearl, t.light)
            body(GP().rr(42, 31, 58, 85, 2), t.light, t.mid)
            flat(GP().m(16, 41).l(26, 19).l(36, 41).close().m(64, 41).l(74, 19).l(84, 41).close(), t.mid)
            flat(GP().m(39, 32).l(50, 9).l(61, 32).close(), t.mid)
            flat(GP().m(44, 85).l(44, 74).q(50, 66, 56, 74).l(56, 85).close(), t.deep)
            dot(GP().rr(24, 50, 28, 57, 2).rr(72, 50, 76, 57, 2).rr(48, 41, 52, 48, 2), t.deep)
            flat(star(84, 15, 9, 2.8, 4), t.pearl)
        case "History":
            body(GP().m(17, 34).l(50, 15).l(83, 34).close(), t.light, t.mid)
            flat(GP().rr(17, 34, 83, 40, 2), t.pearl)
            body(GP().rr(23, 42, 31, 76, 2).rr(37.7, 42, 45.7, 76, 2).rr(54.3, 42, 62.3, 76, 2).rr(69, 42, 77, 76, 2), t.pearl, t.light)
            flat(GP().rr(17, 77, 83, 83, 2), t.mid)
            flat(GP().rr(12, 84, 88, 89, 2), t.light)
            dot(GP().circle(50, 28, 3), t.deep)
        case "Music":
            body(GP().m(36, 22).l(79, 14).l(79, 24).l(36, 32).close(), t.light, t.mid)
            stroke(GP().m(38, 28).l(38, 70).m(77, 20).l(77, 62), t.light, line)
            body(GP(-20, 28, 72).oval(16, 63, 40, 81))
            body(GP(-20, 67, 64).oval(55, 55, 79, 73))
            gleam(20, 66, 28, 70)
        case "War", "War & Politics":
            flat(GP().m(29, 11).l(45, 11).l(58, 46).l(46, 50).close(), t.mid)
            flat(GP().m(71, 11).l(55, 11).l(42, 46).l(54, 50).close(), t.coral)
            body(GP().circle(50, 64, 23))
            flat(star(50, 65.5, 15, 6.5, 5), t.pearl)
            gleam(36, 48, 46, 53)
        case "Western":
            body(GP().m(30, 63).l(33, 33).q(35, 24, 43, 27).q(50, 32, 57, 27).q(65, 24, 67, 33).l(70, 63).close(), t.pearl, t.light)
            flat(GP().m(31.3, 50).l(68.7, 50).l(69.5, 58).l(30.5, 58).close(), t.deep)
            dot(GP().circle(50, 54, 2.6), t.coral)
            body(GP().m(9, 57).q(13, 72, 30, 72).l(70, 72).q(87, 72, 91, 57).q(81, 63, 70, 62).l(30, 62).q(19, 63, 9, 57).close(), t.light, t.mid)
            gleam(37, 33, 44, 41)
        case "Halloween", "Season:Pumpkin", "Season:Halloween":
            let warm = mix(t.coral, 0xFFFF_B25C, 0.55)
            flat(GP().rr(46, 14, 54, 30, 3), t.deep)
            stroke(GP().m(54, 22).q(62, 15, 69, 20), t.mid, fine)
            body(GP().oval(14, 28, 58, 84).oval(42, 28, 86, 84), mix(t.light, warm, 0.6), mix(t.mid, warm, 0.75))
            body(GP().oval(30, 26, 70, 86), mix(t.pearl, warm, 0.45), mix(t.light, warm, 0.7))
            dot(GP().m(33, 52).l(41, 42).l(46, 53).close().m(54, 53).l(59, 42).l(67, 52).close(), t.deep)
            dot(GP().m(31, 61).q(50, 82, 69, 61).l(62, 64).l(58, 60).l(54, 66).l(50, 61).l(46, 66).l(42, 60).l(38, 64).close(), t.deep)
            gleam(36, 32, 46, 37)
        case "Holiday":
            body(GP().rr(20, 44, 80, 86, 5))
            flat(GP().rr(16, 34, 84, 47, 4), t.pearl)
            flat(GP().rr(45, 34, 55, 86, 2), t.coral)
            body(GP().m(50, 34).c(38, 14, 22, 22, 33, 33).close().m(50, 34).c(62, 14, 78, 22, 67, 33).close(), mix(t.light, t.coral, 0.5), mix(t.mid, t.coral, 0.7))
            dot(GP().circle(50, 33, 4.5), t.coral)
            flat(star(84, 16, 8, 2.6, 4), t.pearl)
        case "Adult Animation":
            body(GP().m(26, 18).l(74, 18).q(88, 18, 88, 32).l(88, 56).q(88, 70, 74, 70)
                .l(44, 70).l(26, 86).l(30, 70).l(26, 70).q(12, 70, 12, 56).l(12, 32).q(12, 18, 26, 18).close(), t.pearl, t.light)
            dot(star(31, 44, 10, 4.2, 5), t.coral)
            dot(GP().m(53, 30).l(45, 46).l(51, 46).l(47, 59).l(57, 41).l(51, 41).close(), t.deep)
            stroke(GP().arc(63, 36, 79, 52, 180, 270).l(71, 44), t.mid, fine)
            dot(GP().circle(73, 59, 2.6), t.deep)
        case "K-Drama":
            flat(GP(-12, 18, 34).rr(18, 24, 82, 36, 3), t.light)
            dot(GP(-12, 18, 34).m(28, 24).l(36, 24).l(31, 36).l(23, 36).close()
                .m(44, 24).l(52, 24).l(47, 36).l(39, 36).close()
                .m(60, 24).l(68, 24).l(63, 36).l(55, 36).close(), t.deep)
            body(GP().rr(18, 40, 82, 84, 6))
            dot(heart(50, 63, 26), t.coral)
            gleam(24, 44, 34, 48)
        case "True Crime":
            body(GP().m(50, 14).q(64, 22, 80, 22).q(82, 62, 50, 86).q(18, 62, 20, 22).q(36, 22, 50, 14).close())
            flat(GP().m(50, 22).q(61, 28, 73, 28).q(74, 59, 50, 78).q(26, 59, 27, 28).q(39, 28, 50, 22).close(), t.pearl)
            dot(star(50, 50, 15, 6.5, 5), t.deep)
            gleam(28, 26, 38, 31)
        case "Stand-Up Comedy", "Stand-Up":
            stroke(GP().m(50, 58).l(50, 84).m(36, 86).l(64, 86), t.deep, line)
            stroke(GP().arc(30, 30, 70, 64, 20, 140), t.mid, fine)
            body(GP().rr(38, 14, 62, 58, 12))
            dot(GP().rr(38, 34, 62, 39, 1), t.deep)
            gleam(42, 18, 50, 24)
            flat(star(80, 20, 9, 2.8, 4), t.pearl)
        case "Sitcoms":
            body(GP().rr(22, 30, 78, 60, 9), t.light, t.mid)
            body(GP().rr(12, 46, 30, 78, 7).rr(70, 46, 88, 78, 7), t.mid, t.mid)
            body(GP().rr(26, 56, 74, 76, 5), t.pearl, t.light)
            stroke(GP().m(50, 58).l(50, 74), t.mid, fine)
            flat(GP().rr(18, 78, 24, 86, 2).rr(76, 78, 82, 86, 2), t.deep)
            gleam(30, 34, 42, 39)
        case "Superheroes":
            body(GP().m(18, 30).l(50, 14).l(82, 30).q(80, 66, 50, 86).q(20, 66, 18, 30).close())
            dot(GP().m(56, 22).l(36, 54).l(49, 54).l(42, 78).l(66, 44).l(53, 44).close(), t.pearl)
            gleam(27, 30, 37, 35)
        case "Based on a True Story", "True Stories":
            body(GP().m(50, 30).q(32, 20, 12, 26).l(12, 76).q(32, 70, 50, 80).close()
                .m(50, 30).q(68, 20, 88, 26).l(88, 76).q(68, 70, 50, 80).close(), t.pearl, t.light)
            stroke(GP().m(20, 40).q(32, 36, 42, 41).m(20, 50).q(32, 46, 42, 51)
                .m(58, 41).q(68, 36, 80, 40).m(58, 51).q(68, 46, 80, 50), t.mid, fine)
            flat(GP().m(66, 24).l(74, 23).l(74, 44).l(70, 40).l(66, 44).close(), t.coral)
            stroke(GP().m(50, 30).l(50, 80), t.mid, fine)
        case "Feel-Good":
            var rays = GP()
            for i in 0..<8 {
                let a = CGFloat(i * 45)
                rays = rays.m(50 + 29 * cosd(a), 50 + 29 * sind(a)).l(50 + 38 * cosd(a), 50 + 38 * sind(a))
            }
            stroke(rays, t.light, line)
            body(GP().circle(50, 50, 22), t.pearl, t.light)
            dot(GP().circle(42.5, 46, 2.8).circle(57.5, 46, 2.8), t.deep)
            stroke(GP().arc(39, 44, 61, 62, 25, 130), t.deep, fine)
            dot(GP().circle(37, 55, 3).circle(63, 55, 3), alpha(t.coral, 0.7))
        case "Mind-Bending":
            body(GP().circle(50, 50, 34), t.light, t.mid)
            var spiral = GP()
            var r: CGFloat = 4
            for i in 0..<8 {
                let cx: CGFloat = i % 2 == 0 ? 50 : 47
                spiral = spiral.arc(cx - r, 50 - r, cx + r, 50 + r, i % 2 == 0 ? 180 : 0, 180, connect: i > 0)
                r += 3
            }
            stroke(spiral, t.pearl, fine)
            dot(GP().circle(50, 50, 3), t.deep)
        case "Sports":
            body(GP().circle(50, 50, 33), mix(t.light, t.coral, 0.35), mix(t.mid, t.coral, 0.5))
            stroke(GP().m(17, 50).l(83, 50).m(50, 17).l(50, 83), t.deep, fine)
            stroke(GP().arc(-2, 22, 36, 78, -60, 120).arc(64, 22, 102, 78, 120, 120), t.deep, fine)
            gleam(30, 26, 41, 32)
        case "Teen":
            stroke(GP().arc(20, 18, 80, 78, 180, 180), t.mid, line + 1)
            body(GP().rr(14, 46, 34, 80, 9).rr(66, 46, 86, 80, 9))
            flat(GP().rr(20, 52, 28, 74, 4).rr(72, 52, 80, 74, 4), t.pearl)
            dot(heart(50, 64, 14), t.coral)
        case "Musicals":
            body(GP().rr(32, 22, 68, 66, 4), t.light, t.mid)
            flat(GP().rr(32, 54, 68, 62, 1), t.coral)
            body(GP().rr(18, 64, 82, 74, 5), t.mid, t.mid)
            stroke(GP().m(72, 86).l(84, 36).q(86, 28, 79, 28), t.pearl, fine)
            flat(star(22, 24, 10, 3, 4), t.pearl)
            gleam(36, 26, 44, 34)
        case "LGBTQ+", "Season:Pride":
            stroke(GP().arc(16, 28, 84, 96, 180, 180), t.coral, line)
            stroke(GP().arc(23, 35, 77, 89, 180, 180), t.light, line)
            stroke(GP().arc(30, 42, 70, 82, 180, 180), t.mid, line)
            stroke(GP().arc(37, 49, 63, 75, 180, 180), t.pearl, line)
            body(GP().circle(26, 68, 9).circle(36, 62, 11).circle(44, 70, 8).rr(18, 66, 50, 78, 6), t.pearl, t.light)
        case "’70s Classics", "'70s Classics":
            stroke(GP().m(50, 8).l(50, 20), t.deep, fine)
            body(GP().circle(50, 52, 31), t.light, t.mid)
            stroke(GP().m(19, 52).l(81, 52).m(23, 37).l(77, 37).m(23, 67).l(77, 67).m(50, 21).l(50, 83), t.pearl, 2.2)
            stroke(GP().arc(34, 21, 66, 83, 90, 180).arc(34, 21, 66, 83, 270, 180), t.pearl, 2.2)
            gleam(32, 30, 43, 36)
            flat(star(84, 22, 9, 2.8, 4), t.pearl)
        case "’80s Classics", "'80s Classics":
            body(GP().rr(12, 26, 88, 76, 7))
            flat(GP().rr(20, 33, 80, 58, 4), t.pearl)
            dot(GP().rr(30, 40, 70, 52, 6), t.deep)
            dot(GP().circle(38, 46, 4).circle(62, 46, 4), t.pearl)
            flat(GP().m(28, 76).l(33, 64).l(67, 64).l(72, 76).close(), t.mid)
            dot(GP().rr(20, 35, 80, 38, 1), t.coral)
        case "’90s Classics", "'90s Classics":
            body(GP().m(30, 34).l(70, 34).q(88, 34, 90, 56).q(92, 76, 78, 74).q(70, 72, 64, 62)
                .l(36, 62).q(30, 72, 22, 74).q(8, 76, 10, 56).q(12, 34, 30, 34).close())
            dot(GP().rr(22, 45, 38, 50, 2).rr(27.5, 39.5, 32.5, 55.5, 2), t.deep)
            dot(GP().circle(68, 43, 3.6), t.coral)
            dot(GP().circle(76, 50, 3.6), t.pearl)
            gleam(24, 37, 34, 41)
        case "2000s Classics":
            body(GP().circle(50, 50, 33), t.pearl, t.light)
            stroke(GP().arc(25, 25, 75, 75, 200, 60).arc(31, 31, 69, 69, 20, 60), alpha(t.mid, 0.8), fine)
            flat(GP().circle(50, 50, 11), t.mid)
            dot(GP().circle(50, 50, 4.5), t.deep)
            gleam(30, 28, 41, 34)

        // ---- seasonal hubs (SeasonGlyphs.kt) ----
        case "Season:Snowflake":
            var arms = GP(), twigs = GP()
            for i in 0..<6 {
                let a = CGFloat(-90 + i * 60)
                arms = arms.m(50, 50).l(50 + 34 * cosd(a), 50 + 34 * sind(a))
                for (at, len) in [(CGFloat(17), CGFloat(9)), (26, 6.5)] {
                    let bx = 50 + at * cosd(a), by = 50 + at * sind(a)
                    for side in [CGFloat(-45), 45] { twigs = twigs.m(bx, by).l(bx + len * cosd(a + side), by + len * sind(a + side)) }
                }
            }
            stroke(arms, t.light, line)
            stroke(twigs, t.pearl, fine)
            var hex = GP()
            for i in 0..<6 {
                let a = CGFloat(-90 + i * 60)
                hex = i == 0 ? hex.m(50 + 9 * cosd(a), 50 + 9 * sind(a)) : hex.l(50 + 9 * cosd(a), 50 + 9 * sind(a))
            }
            body(hex.close(), t.pearl, t.light)
            dot(GP().circle(50, 50, 3), t.mid)
        case "Season:Blossom":
            stroke(GP().m(50, 58).q(51, 74, 49, 90), t.deep, line)
            body(GP().m(49, 86).q(28, 82, 24, 60).q(42, 64, 49, 80).close(), t.mid, t.mid)
            body(GP().m(30, 18).l(40, 28).l(50, 12).l(60, 28).l(70, 18).q(75, 52, 50, 60).q(25, 52, 30, 18).close(),
                 mix(t.pearl, rose, 0.35), mix(t.light, roseDeep, 0.6))
            stroke(GP().m(40, 30).q(42, 46, 50, 56).m(60, 30).q(58, 46, 50, 56), alpha(t.pearl, 0.7), fine - 1)
            gleam(35, 26, 42, 36)
        case "Season:Sun":
            var rays = GP()
            for i in 0..<10 {
                let a = CGFloat(-180 + i * 20)
                if a > 0 { continue }
                rays = rays.m(50 + 27 * cosd(a), 52 + 27 * sind(a)).l(50 + 37 * cosd(a), 52 + 37 * sind(a))
            }
            stroke(rays, t.light, line)
            body(GP().arc(30, 32, 70, 72, 180, 180).close(), t.pearl, t.light)
            gleam(36, 38, 46, 44)
            stroke(GP().m(14, 62).q(23, 56, 32, 62).q(41, 68, 50, 62).q(59, 56, 68, 62).q(77, 68, 86, 62), t.mid, line)
            stroke(GP().m(22, 75).q(31, 69, 40, 75).q(49, 81, 58, 75).q(67, 69, 78, 75), t.light, line)
        case "Season:Leaf":
            stroke(GP().m(50, 66).q(51, 80, 46, 90), t.deep, fine)
            body(GP().m(50, 70).l(31, 74).l(34, 64).l(15, 52).l(22, 47).l(17, 34).l(30, 37).l(32, 29).l(41, 36).l(38, 19)
                .l(44, 21).l(50, 9).l(56, 21).l(62, 19).l(59, 36).l(68, 29).l(70, 37).l(83, 34).l(78, 47).l(85, 52)
                .l(66, 64).l(69, 74).close(), mix(t.light, t.coral, 0.25), mix(t.mid, t.coral, 0.45))
            stroke(GP().m(50, 68).l(50, 22).m(50, 58).l(25, 42).m(50, 58).l(75, 42).m(50, 64).l(31, 66).m(50, 64).l(69, 66), alpha(t.pearl, 0.75), 2.2)
        case "Season:NewYear":
            for (rot, x) in [(CGFloat(-14), CGFloat(38)), (14, 62)] {
                flat(GP(rot, x, 60).rr(x - 1.6, 52, x + 1.6, 78, 1.6), t.mid)
                flat(GP(rot, x, 60).rr(x - 9, 76, x + 9, 81, 2.5), t.mid)
                body(GP(rot, x, 60).m(x - 10, 16).l(x + 10, 16).q(x + 10.5, 46, x, 54).q(x - 10.5, 46, x - 10, 16).close(), t.pearl, t.light)
                flat(GP(rot, x, 60).m(x - 9.4, 28).l(x + 9.4, 28).q(x + 9, 46, x, 51).q(x - 9, 46, x - 9.4, 28).close(), t.mid)
                dot(GP(rot, x, 60).circle(x - 2, 38, 1.6).circle(x + 3, 44, 1.3).circle(x, 33, 1.2), t.pearl)
            }
            sparkle(50, 10, 9)
            dot(GP().circle(36, 8, 2.2).circle(65, 7, 1.8), t.pearl)
        case "Season:Valentine":
            stroke(GP().m(14, 82).l(48, 52), t.pearl, fine)
            body(heartAt(50, 82, 60), rose, roseDeep)
            gleam(30, 40, 40, 47)
            stroke(GP().m(56, 46).l(78, 26), t.pearl, fine)
            flat(GP().m(86, 18).l(72, 22).l(82, 32).close(), t.pearl)
            stroke(GP().m(14, 82).l(10, 74).m(14, 82).l(22, 86).m(19, 78).l(15, 70).m(19, 78).l(27, 82), t.light, fine - 0.6)
        case "Season:Stories":
            body(GP().m(50, 50).q(32, 41, 12, 47).l(12, 84).q(32, 78, 50, 88).close()
                .m(50, 50).q(68, 41, 88, 47).l(88, 84).q(68, 78, 50, 88).close(), t.pearl, t.light)
            stroke(GP().m(20, 60).q(32, 56, 42, 61).m(20, 70).q(32, 66, 42, 71)
                .m(58, 61).q(68, 56, 80, 60).m(58, 71).q(68, 66, 80, 70), t.mid, fine)
            stroke(GP().m(50, 50).l(50, 88), t.mid, fine)
            body(star(50, 25, 15, 6.5, 5), t.pearl, mix(t.light, t.coral, 0.25))
            dot(GP().circle(30, 32, 2.2).circle(70, 30, 2.6).circle(38, 16, 1.6), t.light)
        case "Season:Award":
            stroke(GP().m(29, 24).q(12, 24, 15, 38).q(18, 50, 33, 52).m(71, 24).q(88, 24, 85, 38).q(82, 50, 67, 52), t.mid, line)
            flat(GP().rr(45, 62, 55, 76, 2), t.mid)
            body(GP().rr(30, 76, 70, 88, 4), t.light, t.mid)
            flat(GP().rr(36, 79, 64, 83, 2), alpha(t.pearl, 0.6))
            body(GP().m(26, 14).l(74, 14).l(72, 36).q(68, 62, 50, 64).q(32, 62, 28, 36).close(), t.pearl, t.light)
            flat(star(50, 36, 11, 4.6, 5), t.coral)
            gleam(32, 18, 39, 32)
        case "Season:Women":
            stroke(GP().circle(48, 38, 21), t.light, line + 1.5)
            stroke(GP().m(48, 60).l(48, 90).m(36, 77).l(60, 77), t.light, line + 1.5)
            stroke(GP().arc(33, 23, 63, 53, 200, 50), t.pearl, fine - 0.6)
            sparkle(80, 20, 10)
            dot(GP().circle(80, 40, 2.2), t.pearl)
        case "Season:Shamrock":
            stroke(GP().m(50, 54).q(54, 74, 66, 88), t.deep, line)
            for rot in [CGFloat(0), 120, 240] { body(heartAt(50, 52, 34, rot), t.light, t.mid) }
            dot(GP().circle(50, 52, 3.2), t.mid)
            gleam(43, 27, 49, 31)
        case "Season:Easter":
            body(GP().m(50, 12).c(70, 12, 78, 50, 76, 64).c(74, 80, 63, 88, 50, 88)
                .c(37, 88, 26, 80, 24, 64).c(22, 50, 30, 12, 50, 12).close(), t.pearl, t.light)
            stroke(GP().m(26, 52).l(33, 45).l(41, 53).l(50, 45).l(59, 53).l(67, 45).l(74, 52), t.mid, fine)
            dot(GP().circle(32, 66, 3).circle(43, 68, 3).circle(57, 68, 3).circle(68, 66, 3), t.coral)
            stroke(GP().m(30, 76).q(50, 82, 70, 76), t.mid, fine - 0.6)
            gleam(36, 22, 46, 32)
        case "Season:Mother":
            stroke(GP().m(50, 80).l(30, 42).m(50, 80).l(50, 28).m(50, 80).l(70, 42), t.deep, fine)
            func flower(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat) {
                var petals = GP()
                for i in 0..<5 {
                    let a = CGFloat(-90 + i * 72)
                    petals = petals.circle(cx + r * 0.55 * cosd(a), cy + r * 0.55 * sind(a), r * 0.48)
                }
                body(petals, t.pearl, rose)
                dot(GP().circle(cx, cy, r * 0.24), mix(t.mid, t.coral, 0.4))
            }
            flower(28, 40, 14)
            flower(72, 40, 14)
            flower(50, 22, 15)
            flat(GP().m(50, 68).c(40, 58, 34, 70, 46, 72).close().m(50, 68).c(60, 58, 66, 70, 54, 72).close(), t.coral)
            flat(GP().m(48, 70).l(42, 86).l(47, 84).close().m(52, 70).l(58, 86).l(53, 84).close(), t.coral)
            dot(GP().circle(50, 69, 3), mix(t.coral, 0xFFFF_FFFF, 0.2))
        case "Season:Father":
            flat(GP().m(30, 10).l(50, 18).l(70, 10).l(66, 20).l(50, 24).l(34, 20).close(), t.pearl)
            body(GP().m(42, 20).l(58, 20).l(55, 32).l(45, 32).close(), t.light, t.mid)
            body(GP().m(45, 32).l(55, 32).l(64, 76).l(50, 90).l(36, 76).close(), t.light, t.mid)
            stroke(GP().m(44, 46).l(57, 40).m(42, 58).l(60, 50).m(40, 70).l(62, 60), alpha(t.pearl, 0.8), fine - 0.4)
            gleam(46, 36, 50, 46)
        case "Season:Fireworks":
            stroke(GP().m(44, 92).q(40, 72, 42, 56), alpha(t.light, 0.7), fine - 0.6)
            var big = GP(), tips = GP()
            for i in 0..<12 {
                let a = CGFloat(i * 30)
                big = big.m(42 + 9 * cosd(a), 40 + 9 * sind(a)).l(42 + 26 * cosd(a), 40 + 26 * sind(a))
                tips = tips.circle(42 + 31 * cosd(a), 40 + 31 * sind(a), 2.4)
            }
            stroke(big, t.light, line - 0.6)
            dot(tips, t.pearl)
            dot(GP().circle(42, 40, 4), t.pearl)
            var small = GP()
            for i in 0..<8 {
                let a = 22.5 + CGFloat(i) * 45
                small = small.m(76 + 5 * cosd(a), 72 + 5 * sind(a)).l(76 + 14 * cosd(a), 72 + 14 * sind(a))
            }
            stroke(small, t.coral, fine)
            dot(GP().circle(76, 72, 2.6), t.pearl)
            sparkle(82, 18, 8)
        case "Season:School":
            flat(GP().rr(16, 76, 84, 88, 3), t.mid)
            flat(GP().rr(22, 64, 80, 76, 3), t.light)
            stroke(GP().m(28, 82).l(78, 82), alpha(t.pearl, 0.7), 2)
            stroke(GP().m(28, 70).l(74, 70), alpha(t.pearl, 0.7), 2)
            body(GP().m(50, 30).c(60, 22, 76, 26, 74, 44).c(72, 58, 60, 64, 50, 60)
                .c(40, 64, 28, 58, 26, 44).c(24, 26, 40, 22, 50, 30).close(), rose, roseDeep)
            stroke(GP().m(50, 30).q(49, 22, 53, 16), t.deep, fine)
            flat(GP().m(53, 22).q(60, 12, 70, 16).q(62, 26, 53, 22).close(), t.mid)
            gleam(32, 34, 40, 42)
        case "Season:Voices":
            body(GP().m(22, 14).l(60, 14).q(72, 14, 72, 26).l(72, 42).q(72, 54, 60, 54)
                .l(34, 54).l(22, 66).l(25, 54).q(10, 54, 10, 42).l(10, 26).q(10, 14, 22, 14).close(), t.light, t.mid)
            body(GP().m(44, 44).l(78, 44).q(90, 44, 90, 56).l(90, 68).q(90, 80, 78, 80)
                .l(76, 80).l(80, 92).l(64, 80).l(44, 80).q(32, 80, 32, 68).l(32, 56).q(32, 44, 44, 44).close(), t.pearl, t.light)
            dot(GP().circle(49, 62, 3.4).circle(61, 62, 3.4).circle(73, 62, 3.4), t.mid)
            dot(heartAt(41, 38, 14), t.coral)
            gleam(16, 18, 26, 23)
        case "Season:Turkey":
            let fan: [CGFloat] = [-150, -125, -100, -80, -55, -30]
            for (i, a) in fan.enumerated() {
                let x = 50 + 24 * cosd(a), y = 58 + 24 * sind(a)
                let c = i % 2 == 0 ? t.mid : mix(t.light, t.coral, 0.35)
                body(GP(a + 90, x, y).oval(x - 8, y - 17, x + 8, y + 17), c, c)
            }
            stroke(GP().m(44, 78).l(42, 90).m(56, 78).l(58, 90), t.deep, fine)
            body(GP().circle(50, 64, 19), t.light, t.mid)
            body(GP().circle(50, 44, 10), t.pearl, t.light)
            dot(GP().circle(46.5, 42, 1.8).circle(53.5, 42, 1.8), t.deep)
            flat(GP().m(47, 46).l(53, 46).l(50, 51).close(), mix(t.pearl, 0xFFFF_B25C, 0.5))
            dot(GP().m(53, 48).q(57, 54, 53, 57).q(50, 53, 53, 48).close(), t.coral)
            gleam(42, 58, 50, 63)
        case "Season:Christmas":
            flat(GP().rr(45, 78, 55, 90, 2), t.deep)
            body(GP().m(50, 44).l(80, 80).l(20, 80).close(), t.light, t.mid)
            body(GP().m(50, 30).l(73, 60).l(27, 60).close(), t.light, t.mid)
            body(GP().m(50, 18).l(66, 41).l(34, 41).close(), t.pearl, t.light)
            stroke(GP().m(33, 70).q(50, 76, 69, 66).m(36, 52).q(50, 57, 63, 48), alpha(t.pearl, 0.8), 2.2)
            dot(GP().circle(40, 66, 3).circle(60, 74, 3).circle(55, 52, 2.6), t.coral)
            dot(GP().circle(64, 64, 2.6).circle(44, 50, 2.4).circle(50, 34, 2.2), t.pearl)
            body(star(50, 13, 9, 3.8, 5), t.pearl, mix(t.pearl, 0xFFFF_D21F, 0.35))
        default:
            body(star(50, 52, 32, 9, 4), t.pearl, t.light)
            flat(star(77, 25, 11, 3.5, 4), t.pearl)
        }
        return out
    }
}
