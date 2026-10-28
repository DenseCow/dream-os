import SwiftUI

/// A meta line that wraps instead of squeezing (STANDARDS §3: nothing clipped, nothing touching, no "..."): its parts
/// sit left to right at their full size and a part that doesn't fit whole starts a new line. Each part is measured
/// against the full line width, so a `ViewThatFits` part (the hero's words) takes its longest form that fits a line.
/// `MetaDot()` separators show only between two parts on the same line: one that would start or end a line is left
/// out, so a wrapped line never begins or ends with a dot. A part with no size (a pill not known yet) takes no space.
public struct MetaFlow: Layout {
    public var spacing: CGFloat
    public var lineSpacing: CGFloat

    public init(spacing: CGFloat = 10, lineSpacing: CGFloat = 8) { self.spacing = spacing; self.lineSpacing = lineSpacing }

    struct Slot { var index: Int; var size: CGSize; var x: CGFloat }
    struct Line { var slots: [Slot] = []; var width: CGFloat = 0; var height: CGFloat = 0 }

    func lines(width: CGFloat, subviews: Subviews) -> (lines: [Line], hidden: [Int]) {
        let cap: CGFloat? = width.isFinite ? width : nil
        var out: [Line] = [], cur = Line(), hidden: [Int] = []
        var dot: Slot?
        for (i, s) in subviews.enumerated() {
            if s[MetaDotKey.self] {
                if let d = dot { hidden.append(d.index) }
                if cur.slots.isEmpty { hidden.append(i); dot = nil } else { dot = Slot(index: i, size: s.sizeThatFits(.unspecified), x: 0) }
                continue
            }
            var sz = s.sizeThatFits(ProposedViewSize(width: cap, height: nil))
            if let cap { sz.width = min(sz.width, cap) }
            if sz.width <= 0 && sz.height <= 0 {
                // Nothing to show yet: parked at the end of the line, no gap.
                cur.slots.append(Slot(index: i, size: .zero, x: cur.width))
                continue
            }
            if cur.slots.allSatisfy({ $0.size == .zero }) {
                if let d = dot { hidden.append(d.index); dot = nil }
                cur.slots.append(Slot(index: i, size: sz, x: 0))
                cur.width = sz.width; cur.height = max(cur.height, sz.height)
                continue
            }
            let dotW = dot.map { $0.size.width + spacing } ?? 0
            if cur.width + spacing + dotW + sz.width <= width + 0.5 {
                if var d = dot {
                    d.x = cur.width + spacing
                    cur.slots.append(d)
                    cur.width = d.x + d.size.width
                    cur.height = max(cur.height, d.size.height)
                }
                cur.slots.append(Slot(index: i, size: sz, x: cur.width + spacing))
                cur.width += spacing + sz.width
                cur.height = max(cur.height, sz.height)
            } else {
                if let d = dot { hidden.append(d.index) }
                out.append(cur)
                cur = Line(slots: [Slot(index: i, size: sz, x: 0)], width: sz.width, height: sz.height)
            }
            dot = nil
        }
        if let d = dot { hidden.append(d.index) }
        if !cur.slots.isEmpty { out.append(cur) }
        return (out, hidden)
    }

    public func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        let ls = lines(width: width, subviews: subviews).lines.filter { $0.height > 0 }
        guard !ls.isEmpty else { return .zero }
        let w = ls.map(\.width).max() ?? 0
        let h = ls.map(\.height).reduce(0, +) + lineSpacing * CGFloat(ls.count - 1)
        return CGSize(width: width.isFinite ? min(w, width) : w, height: h)
    }

    public func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let (ls, hidden) = lines(width: bounds.width, subviews: subviews)
        var y = bounds.minY
        for line in ls {
            for slot in line.slots {
                subviews[slot.index].place(at: CGPoint(x: bounds.minX + slot.x, y: y + (line.height - slot.size.height) / 2),
                                           proposal: ProposedViewSize(slot.size))
            }
            if line.height > 0 { y += line.height + lineSpacing }
        }
        // A left-out dot is a shape, so a zero proposal draws nothing.
        for i in hidden { subviews[i].place(at: CGPoint(x: bounds.minX, y: bounds.minY), proposal: .zero) }
    }
}

struct MetaDotKey: LayoutValueKey { static let defaultValue = false }

/// The separator dot between meta parts in a `MetaFlow` (left out at a line's start or end).
public struct MetaDot: View {
    let color: Color
    let size: CGFloat
    public init(color: Color = .white.opacity(0.55), size: CGFloat = 3.5) { self.color = color; self.size = size }
    public var body: some View {
        // Flexible up to `size`: drawn at full size when placed, nothing at a zero proposal (a left-out dot).
        Circle().fill(color)
            .frame(idealWidth: size, maxWidth: size, idealHeight: size, maxHeight: size)
            .accessibilityHidden(true)
            .layoutValue(key: MetaDotKey.self, value: true)
    }
}
