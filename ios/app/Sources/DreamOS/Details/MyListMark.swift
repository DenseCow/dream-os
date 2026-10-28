import SwiftUI
import DreamUI

/// My List's glyph on the title page (fire-home MyListMark.kt): "+" when the title isn't saved, a green check when it
/// is. Adding: the + turns 45° and fades while the check draws itself stroke by stroke (a path trim, over the later
/// part of 420 ms), with a small spring pop (1.0 -> 1.15 -> 1.0) and one soft green ring that grows and fades.
/// Removing runs the same motion backwards (300 ms, no ring, no pop). Nothing animates outside a change.
///
/// The + follows the button's ink (the foreground style); the check stays green: fresh on dark glass, deeper on the
/// pressed pearl box. `motion` is bumped by the button's tap: a change of `saved` without a tap (the page learning
/// the title is saved) shows at once, without the motion.
struct MyListMark: View {
    var saved: Bool
    var motion: Int
    var size: CGFloat = 24

    @Environment(\.myListMarkOnPearl) private var onPearl
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// 0 = "+", 1 = check.
    @State private var t: Double
    /// The ring's clock (0...1) while adding; nil otherwise.
    @State private var ring: Double?
    @State private var scale: CGFloat = 1
    @State private var seenMotion: Int

    static let green = Color(hex: 0x4ADE80)
    static let greenDeep = Color(hex: 0x15A34A)

    init(saved: Bool, motion: Int, size: CGFloat = 24) {
        self.saved = saved; self.motion = motion; self.size = size
        _t = State(initialValue: saved ? 1 : 0)
        _seenMotion = State(initialValue: motion)
    }

    var body: some View {
        let green = onPearl ? Self.greenDeep : Self.green
        MarkDrawing(t: t, green: green)
            .frame(width: size, height: size)
            .background {
                if let ring { MarkRing(ring: ring, color: green).frame(width: size, height: size) }
            }
            .scaleEffect(scale)
            .onChange(of: saved) { _, on in
                if motion != seenMotion, !reduceMotion {
                    seenMotion = motion
                    animate(to: on)
                } else {
                    seenMotion = motion
                    ring = nil; scale = 1
                    t = on ? 1 : 0
                }
            }
            .accessibilityHidden(true)
    }

    /// Moves to `on` from wherever a motion under way has got to.
    private func animate(to on: Bool) {
        let to: Double = on ? 1 : 0
        let duration = max(0.06, (on ? 0.42 : 0.30) * abs(to - t))
        // Linear clock: each part eases on its own window of it (MarkDrawing).
        withAnimation(.linear(duration: duration)) { t = to }
        if on {
            // The pop: up to 1.15 quickly, then settles back with a touch of overshoot.
            withAnimation(.timingCurve(0.2, 0, 0, 1, duration: 0.13)) { scale = 1.15 }
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(130))
                withAnimation(.spring(response: 0.3, dampingFraction: 0.5)) { scale = 1 }
            }
            // The ring grows out from the glyph and fades, a moment after the tap.
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(70))
                guard saved else { return }
                ring = 0
                withAnimation(.timingCurve(0.2, 0, 0, 1, duration: 0.46)) { ring = 1 } completion: { ring = nil }
            }
        } else {
            ring = nil
            withAnimation(.easeOut(duration: 0.12)) { scale = 1 }
        }
    }
}

/// The + and the check at clock `t` (interpolated frame by frame).
private struct MarkDrawing: View, Animatable {
    var t: Double
    var green: Color
    nonisolated var animatableData: Double {
        get { t }
        set { t = newValue }
    }

    private static func ease(_ x: Double) -> Double { let v = min(1, max(0, x)); return 1 - (1 - v) * (1 - v) * (1 - v) }

    var body: some View {
        // The + turns 45° and fades over the first part of the clock.
        let pf = Self.ease(t / 0.55)
        // The check draws itself along its path over the later part.
        let cf = Self.ease((t - 0.25) / 0.62)
        ZStack {
            if pf < 1 {
                PlusShape().stroke(style: StrokeStyle(lineWidth: 2.3, lineCap: .round))
                    .rotationEffect(.degrees(45 * pf))
                    .opacity(1 - pf)
            }
            if cf > 0 {
                CheckShape().trim(from: 0, to: cf)
                    .stroke(green, style: StrokeStyle(lineWidth: 2.6, lineCap: .round, lineJoin: .round))
            }
        }
    }
}

/// The adding ring: radius 0.42 -> 0.92 of the glyph, alpha 0.55 -> 0.
private struct MarkRing: View, Animatable {
    var ring: Double
    var color: Color
    nonisolated var animatableData: Double {
        get { ring }
        set { ring = newValue }
    }
    var body: some View {
        GeometryReader { g in
            let s = min(g.size.width, g.size.height)
            let d = s * (0.84 + ring)
            Circle().stroke(color.opacity(0.55 * (1 - ring)), lineWidth: 2)
                .frame(width: d, height: d)
                .position(x: g.size.width / 2, y: g.size.height / 2)
        }
    }
}

/// A + on the 24-unit grid (arms 7 units from the middle).
private struct PlusShape: Shape {
    func path(in r: CGRect) -> Path {
        let s = min(r.width, r.height), arm = s * 7 / 24
        var p = Path()
        p.move(to: CGPoint(x: r.midX - arm, y: r.midY)); p.addLine(to: CGPoint(x: r.midX + arm, y: r.midY))
        p.move(to: CGPoint(x: r.midX, y: r.midY - arm)); p.addLine(to: CGPoint(x: r.midX, y: r.midY + arm))
        return p
    }
}

/// A check on the 24-unit grid: the short stroke down, then the long one up to the right.
private struct CheckShape: Shape {
    func path(in r: CGRect) -> Path {
        let s = min(r.width, r.height)
        let ox = r.minX + (r.width - s) / 2, oy = r.minY + (r.height - s) / 2
        func pt(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: ox + s * x / 24, y: oy + s * y / 24) }
        var p = Path()
        p.move(to: pt(4.8, 12.6))
        p.addLine(to: pt(9.6, 17.4))
        p.addLine(to: pt(19.4, 6.8))
        return p
    }
}

private struct MyListMarkOnPearlKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    /// True while the action button holding the mark is pressed (the pearl box): the check turns a deeper green.
    var myListMarkOnPearl: Bool {
        get { self[MyListMarkOnPearlKey.self] }
        set { self[MyListMarkOnPearlKey.self] = newValue }
    }
}
