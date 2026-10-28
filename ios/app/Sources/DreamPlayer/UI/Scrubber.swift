import SwiftUI
import DreamCore
import DreamUI

/// The timeline (fire-home PlayerScrubber): remaining white 20%, buffered white 36%, played in the rainbow
/// fade, intro / recap / credits ticks above the bar, a white thumb. Drag anywhere on it to scrub; the
/// readout rides above the thumb.
struct Scrubber: View {
    let position: Double
    let duration: Double
    let buffered: Double
    let target: Double?
    let markers: [SkipMarker]
    /// Scrub previews (a frame above the thumb); nil or none for this stream = the plain time readout.
    var preview: ScrubThumbnails? = nil
    let onScrub: (Double) -> Void
    let onCommit: () -> Void
    @State private var touching = false

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let inset: CGFloat = 9
            let track = max(1, w - inset * 2)
            let h: CGFloat = touching || target != nil ? 8 : 4
            let x = { (t: Double) -> CGFloat in duration > 0 ? inset + track * CGFloat(min(1, max(0, t / duration))) : inset }
            let hue = Rainbow.fadeColor()
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.20)).frame(width: track, height: h).offset(x: inset)
                Capsule().fill(.white.opacity(0.36)).frame(width: max(0, x(buffered) - inset), height: h).offset(x: inset)
                Capsule().fill(hue).frame(width: max(h, x(min(position, target ?? position)) - inset), height: h).offset(x: inset)
                if let t = target, t > position {
                    Rectangle().fill(hue.mix(.white, 0.45).opacity(0.55)).frame(width: x(t) - x(position), height: h).offset(x: x(position))
                }
                ForEach(ticks, id: \.self) { t in
                    Capsule().fill(.white.opacity(0.9)).frame(width: 3, height: 5).offset(x: x(t) - 1.5, y: -h / 2 - 4.5)
                }
                if touching || target != nil {
                    let tx = x(target ?? position)
                    Circle().fill(hue.opacity(0.34)).frame(width: 30, height: 30).offset(x: tx - 15)
                    Circle().fill(.white).frame(width: 17, height: 17).offset(x: tx - 8.5)
                    if let t = target {
                        let label = chapter(t) ?? Labels.offset(t - position)
                        if let p = preview, p.enabled {
                            // The frame card rides above the thumb, kept fully on screen at both ends; its foot
                            // clears the thumb's 30 pt halo.
                            let half = ScrubPreviewCard.width / 2
                            ScrubPreviewCard(thumbs: p, time: t, label: label)
                                .position(x: min(max(tx, half + 2), w - half - 2),
                                          y: geo.size.height / 2 - 15 - 6 - ScrubPreviewCard.height(p.aspect) / 2)
                                .transition(.opacity)
                        } else {
                            Readout(time: t, label: label)
                                .fixedSize()
                                .position(x: min(max(tx, 60), w - 60), y: -40)
                        }
                    }
                }
            }
            .frame(height: geo.size.height)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { v in
                        guard duration > 0 else { return }
                        touching = true
                        onScrub(Double((v.location.x - inset) / track) * duration)
                    }
                    .onEnded { _ in touching = false; onCommit() }
            )
            .animation(.spring(response: 0.18, dampingFraction: 0.7), value: touching)
        }
    }

    private var ticks: [Double] {
        var t: [Double] = []
        for m in markers {
            switch m.kind {
            case .intro: if m.start > 0 { t.append(m.start) }; t.append(m.end)
            case .recap: t.append(m.end)
            case .credits: t.append(m.start)
            case .preview: break
            }
        }
        return Array(Set(t.filter { $0 > 0 && $0 < duration })).sorted()
    }

    private func chapter(_ t: Double) -> String? {
        if let r = markers.first(where: { $0.kind == .recap }), t < r.end { return "Recap" }
        if let i = markers.first(where: { $0.kind == .intro }), t >= i.start, t < i.end { return "Intro" }
        if let c = markers.first(where: { $0.kind == .credits }), t >= c.start { return "Credits" }
        return nil
    }
}

private struct Readout: View {
    let time: Double
    let label: String
    var body: some View {
        VStack(spacing: 2) {
            Text(Labels.time(time)).font(.system(size: 22, weight: .semibold)).monospacedDigit()
            Text(label).font(.system(size: 12, weight: .medium)).monospacedDigit().foregroundStyle(.white.opacity(0.72))
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 14).padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color(white: 0.06).opacity(0.95)))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(.white.opacity(0.10), lineWidth: 1))
    }
}

extension Color {
    /// Linear mix in sRGB (Ui.mix).
    func mix(_ other: Color, _ t: Double) -> Color {
        let a = UIColor(self), b = UIColor(other)
        var r1: CGFloat = 0, g1: CGFloat = 0, b1: CGFloat = 0, a1: CGFloat = 0
        var r2: CGFloat = 0, g2: CGFloat = 0, b2: CGFloat = 0, a2: CGFloat = 0
        a.getRed(&r1, green: &g1, blue: &b1, alpha: &a1)
        b.getRed(&r2, green: &g2, blue: &b2, alpha: &a2)
        let f = CGFloat(t)
        return Color(red: r1 + (r2 - r1) * f, green: g1 + (g2 - g1) * f, blue: b1 + (b2 - b1) * f)
    }
}
