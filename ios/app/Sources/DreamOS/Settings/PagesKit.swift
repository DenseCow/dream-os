import SwiftUI
import DreamCore
import DreamUI

// Small building blocks the Pages screens share (Details, Settings, Setup, Profiles). They port fire-home's
// BrandRow / PageMarkGlyph / SetupParts (DreamSetupPages.kt) to touch. Built here because DreamUI didn't have them
// yet; once the Shell worker's DreamUI has the same parts, these can move there (docs/port/pages.md).

// MARK: Status marks (PageMarkGlyph: drawn, never a text check)

/// BrandRow.Mark: NONE leaves no gap; OFF is a quiet hollow ring (keeps a list aligned); PICK a neutral check (a choice).
enum PageMark: Equatable {
    case none, ok, warn, busy, off, pick
}

/// A solid disc in the status color with the shape cut out in near-black (fire-home PageMarkGlyph).
struct PageMarkGlyph: View {
    var mark: PageMark
    var size: CGFloat = 22

    var body: some View {
        Canvas { ctx, sz in
            let s = min(sz.width, sz.height)
            let c = CGPoint(x: sz.width / 2, y: sz.height / 2)
            let u = s / 100
            if mark == .off {
                let w = s * 0.09
                ctx.stroke(Path(ellipseIn: CGRect(x: c.x - s / 2 + w / 2, y: c.y - s / 2 + w / 2, width: s - w, height: s - w)),
                           with: .color(.white.opacity(0.30)), lineWidth: w)
                return
            }
            let disc: Color = switch mark {
            case .ok: Palette.green
            case .warn: Palette.red
            case .pick: Color(red: 150 / 255, green: 150 / 255, blue: 158 / 255)
            default: .white.opacity(0.85)
            }
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - s / 2, y: c.y - s / 2, width: s, height: s)), with: .color(disc))
            let ink = Color(red: 14 / 255, green: 14 / 255, blue: 16 / 255)
            let style = StrokeStyle(lineWidth: s * 0.13, lineCap: .round, lineJoin: .round)
            switch mark {
            case .ok, .pick:
                var p = Path()
                p.move(to: CGPoint(x: c.x - 22 * u, y: c.y + 1 * u))
                p.addLine(to: CGPoint(x: c.x - 7 * u, y: c.y + 15 * u))
                p.addLine(to: CGPoint(x: c.x + 22 * u, y: c.y - 14 * u))
                ctx.stroke(p, with: .color(ink), style: style)
            case .warn:
                var p = Path()
                p.move(to: CGPoint(x: c.x, y: c.y - 22 * u))
                p.addLine(to: CGPoint(x: c.x, y: c.y + 4 * u))
                ctx.stroke(p, with: .color(ink), style: style)
                let r = s * 0.075
                ctx.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y + 20 * u - r, width: 2 * r, height: 2 * r)), with: .color(ink))
            case .busy:
                let r = s * 0.07
                for i in -1...1 {
                    let x = c.x + CGFloat(i) * 20 * u
                    ctx.fill(Path(ellipseIn: CGRect(x: x - r, y: c.y - r, width: 2 * r, height: 2 * r)), with: .color(ink))
                }
            default: break
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// A bare drawn check (PageCheck).
struct PageCheck: View {
    var color: Color
    var weight: CGFloat = 0.12
    var body: some View {
        Canvas { ctx, sz in
            let s = min(sz.width, sz.height), u = s / 100
            let c = CGPoint(x: sz.width / 2, y: sz.height / 2)
            var p = Path()
            p.move(to: CGPoint(x: c.x - 22 * u, y: c.y + 1 * u))
            p.addLine(to: CGPoint(x: c.x - 7 * u, y: c.y + 15 * u))
            p.addLine(to: CGPoint(x: c.x + 22 * u, y: c.y - 14 * u))
            ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: s * weight, lineCap: .round, lineJoin: .round))
        }
        .accessibilityHidden(true)
    }
}

/// A drawn chevron for rows that open a page (never a text "›").
struct PageChevron: View {
    var color: Color
    var body: some View {
        Canvas { ctx, sz in
            var p = Path()
            p.move(to: CGPoint(x: sz.width * 0.32, y: sz.height * 0.18))
            p.addLine(to: CGPoint(x: sz.width * 0.68, y: sz.height * 0.5))
            p.addLine(to: CGPoint(x: sz.width * 0.32, y: sz.height * 0.82))
            ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
        }
        .frame(width: 10, height: 16)
        .accessibilityHidden(true)
    }
}

// MARK: Haptics

@MainActor
enum PageHaptics {
    static func tap() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func select() { UISelectionFeedbackGenerator().selectionChanged() }
    static func success() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    static func warning() { UINotificationFeedbackGenerator().notificationOccurred(.warning) }
}

// MARK: Row press look (PearlFocus for rows)

/// A list row: quiet glass at rest; pressed (or `selected`), a pearl box with dark ink inside the rainbow border.
struct PageRowStyle: ButtonStyle {
    var selected = false
    var radius: CGFloat = 14
    func makeBody(configuration: Configuration) -> some View {
        let lit = configuration.isPressed || selected
        configuration.label
            .environment(\.pageRowLit, lit)
            .foregroundStyle(lit ? Palette.ink : Palette.text)
            .background(lit ? Palette.pearl : Color.white.opacity(0.045),
                        in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
            .rainbowBorder(configuration.isPressed, radius: radius, width: 2)
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .animation(.easeOut(duration: 0.14), value: configuration.isPressed)
    }
}

private struct PageRowLitKey: EnvironmentKey { static let defaultValue = false }
extension EnvironmentValues {
    /// True while the row holding this view is pressed (pearl face): text turns dark ink.
    var pageRowLit: Bool {
        get { self[PageRowLitKey.self] }
        set { self[PageRowLitKey.self] = newValue }
    }
}

// MARK: DreamRow (BrandRow)

/// What sits at the start of a row: a monogram badge, a status mark, or nothing.
enum PageLead {
    case none
    case mark(PageMark)
    case monogram(String)
    case symbol(String)
    case custom(AnyView)
}

/// The row's content: lead, title, caption, value, switch or chevron. Nothing ends in "…" (owner, 2026-10-28): the
/// title and caption wrap, and the value sits beside the title only while the title fits whole on one line next to
/// it; otherwise it moves under the caption (`PageRowText`).
struct PageRowContent: View {
    var title: String
    var caption: String? = nil
    var value: String? = nil
    var valueTone: Color? = nil
    var lead: PageLead = .none
    var trailingMark: PageMark = .none
    var chevron = false
    var toggle: Bool? = nil
    @Environment(\.pageRowLit) private var lit

    var body: some View {
        HStack(spacing: 0) {
            leadView
            PageRowText {
                Text(title)
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(lit ? Palette.ink : Palette.text)
                if let caption, !caption.isEmpty {
                    Text(caption)
                        .font(.system(size: 13.5))
                        .foregroundStyle(lit ? Palette.ink.opacity(0.62) : Palette.textSecondary)
                        .layoutValue(key: PageRowText.RoleKey.self, value: .caption)
                }
                if hasValue, let value {
                    // The mark travels with its value ("✓ Connected"), beside the title or under the caption.
                    HStack(spacing: 10) {
                        if trailingMark != .none { PageMarkGlyph(mark: trailingMark, size: 20) }
                        Text(value)
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(lit ? Palette.ink.opacity(0.7) : (valueTone ?? Palette.text.opacity(0.82)))
                            .multilineTextAlignment(.leading)
                    }
                    .layoutValue(key: PageRowText.RoleKey.self, value: .value)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if trailingMark != .none && !hasValue {
                PageMarkGlyph(mark: trailingMark, size: 20).padding(.leading, 12)
            }
            if let toggle {
                PageSwitch(on: toggle, lit: lit).padding(.leading, 14)
            }
            if chevron {
                PageChevron(color: lit ? Palette.ink.opacity(0.55) : .white.opacity(0.34)).padding(.leading, 14)
            }
        }
        .padding(.leading, 14).padding(.trailing, 16).padding(.vertical, 11)
        .frame(minHeight: 60)
    }

    private var hasValue: Bool { !(value ?? "").isEmpty }

    @ViewBuilder private var leadView: some View {
        switch lead {
        case .none:
            EmptyView()
        case .mark(let m):
            if m != .none {
                PageMarkGlyph(mark: m, size: 24).padding(.leading, 2).padding(.trailing, 14)
            }
        case .monogram(let s):
            PageMonogram(text: s, size: 40).padding(.trailing, 14)
        case .symbol(let name):
            Image(systemName: name)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(lit ? Palette.ink : Palette.text.opacity(0.85))
                .frame(width: 36, height: 36)
                .background(Circle().fill(lit ? Palette.ink.opacity(0.10) : Color.white.opacity(0.07)))
                .padding(.trailing, 14)
        case .custom(let v):
            v.frame(width: 40, height: 40).padding(.trailing, 14)
        }
    }
}

/// A row's words: the title, then its caption, then (role `.value`) the value. The value sits on the right, beside
/// the title, when the title fits whole on one line next to it and the value takes at most half the row; otherwise
/// the value goes under the caption, on the left. Title and caption always wrap onto more lines rather than end
/// in "…" (STANDARDS §3), so nothing is cut on a 375 pt phone, a 440 pt phone or with larger text.
struct PageRowText: Layout {
    var gap: CGFloat = 12
    var spacing: CGFloat = 4

    enum Role { case title, caption, value }
    struct RoleKey: LayoutValueKey { static let defaultValue = Role.title }

    private struct Slot { var index: Int; var origin: CGPoint; var size: CGSize }

    private func plan(_ proposed: CGFloat?, _ subviews: Subviews) -> (size: CGSize, slots: [Slot]) {
        let width: CGFloat? = (proposed?.isFinite ?? false) ? proposed : nil
        var text: [Int] = [], value: Int?
        for i in subviews.indices {
            if subviews[i][RoleKey.self] == .value { value = i } else { text.append(i) }
        }
        let valueIdeal = value.map { subviews[$0].sizeThatFits(.unspecified) } ?? .zero
        var inline = true
        if value != nil, let width, let t = text.first {
            let titleIdeal = subviews[t].sizeThatFits(.unspecified).width
            inline = titleIdeal + gap + valueIdeal.width <= width + 0.5 && valueIdeal.width <= width * 0.5
        }
        var slots: [Slot] = []
        var y: CGFloat = 0
        if inline {
            let side = value == nil ? 0 : gap + valueIdeal.width
            let textW = width.map { max(0, $0 - side) }
            var widest: CGFloat = 0
            for (n, i) in text.enumerated() {
                if n > 0 { y += spacing }
                let sz = subviews[i].sizeThatFits(ProposedViewSize(width: textW, height: nil))
                slots.append(Slot(index: i, origin: CGPoint(x: 0, y: y), size: sz))
                y += sz.height
                widest = max(widest, sz.width)
            }
            let h = max(y, valueIdeal.height)
            let dy = (h - y) / 2
            for k in slots.indices { slots[k].origin.y += dy }
            let w = width ?? (widest + side)
            if let value {
                slots.append(Slot(index: value, origin: CGPoint(x: w - valueIdeal.width, y: (h - valueIdeal.height) / 2), size: valueIdeal))
            }
            return (CGSize(width: w, height: h), slots)
        }
        // Stacked: only when the width is known (above).
        let w = width ?? 0
        for (n, i) in (text + (value.map { [$0] } ?? [])).enumerated() {
            if n > 0 { y += i == value ? spacing + 2 : spacing }
            let sz = subviews[i].sizeThatFits(ProposedViewSize(width: w, height: nil))
            slots.append(Slot(index: i, origin: CGPoint(x: 0, y: y), size: CGSize(width: min(sz.width, w), height: sz.height)))
            y += sz.height
        }
        return (CGSize(width: w, height: y), slots)
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        plan(proposal.width, subviews).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for s in plan(bounds.width, subviews).slots {
            subviews[s.index].place(at: CGPoint(x: bounds.minX + s.origin.x, y: bounds.minY + s.origin.y), proposal: ProposedViewSize(s.size))
        }
    }
}

/// Two (or more) buttons side by side in equal widths when every label fits whole that way; otherwise one above the
/// other at full width. Never a squeezed "Open W…" (owner, 2026-10-28).
struct PageButtonPair: Layout {
    var spacing: CGFloat = 10

    private func sideBySide(_ width: CGFloat, _ subviews: Subviews) -> Bool {
        let n = CGFloat(subviews.count)
        let widest = subviews.map { $0.sizeThatFits(.unspecified).width }.max() ?? 0
        return widest * n + spacing * (n - 1) <= width + 0.5
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard !subviews.isEmpty else { return .zero }
        let n = CGFloat(subviews.count)
        guard let width = proposal.width, width.isFinite else {
            let widest = subviews.map { $0.sizeThatFits(.unspecified).width }.max() ?? 0
            let h = subviews.map { $0.sizeThatFits(.unspecified).height }.max() ?? 0
            return CGSize(width: widest * n + spacing * (n - 1), height: h)
        }
        if sideBySide(width, subviews) {
            let cw = (width - spacing * (n - 1)) / n
            let h = subviews.map { $0.sizeThatFits(ProposedViewSize(width: cw, height: nil)).height }.max() ?? 0
            return CGSize(width: width, height: h)
        }
        let hs = subviews.map { $0.sizeThatFits(ProposedViewSize(width: width, height: nil)).height }
        return CGSize(width: width, height: hs.reduce(0, +) + spacing * (n - 1))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard !subviews.isEmpty else { return }
        let n = CGFloat(subviews.count)
        if sideBySide(bounds.width, subviews) {
            let cw = (bounds.width - spacing * (n - 1)) / n
            for (k, s) in subviews.enumerated() {
                s.place(at: CGPoint(x: bounds.minX + CGFloat(k) * (cw + spacing), y: bounds.minY),
                        proposal: ProposedViewSize(width: cw, height: bounds.height))
            }
        } else {
            var y = bounds.minY
            for s in subviews {
                let h = s.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil)).height
                s.place(at: CGPoint(x: bounds.minX, y: y), proposal: ProposedViewSize(width: bounds.width, height: h))
                y += h + spacing
            }
        }
    }
}

/// A tappable row (BrandRow with an action).
struct PageRow: View {
    var title: String
    var caption: String? = nil
    var value: String? = nil
    var valueTone: Color? = nil
    var lead: PageLead = .none
    var trailingMark: PageMark = .none
    var chevron = false
    var toggle: Bool? = nil
    var action: () -> Void

    var body: some View {
        Button {
            PageHaptics.tap()
            action()
        } label: {
            PageRowContent(title: title, caption: caption, value: value, valueTone: valueTone, lead: lead,
                            trailingMark: trailingMark, chevron: chevron, toggle: toggle)
        }
        .buttonStyle(PageRowStyle())
    }
}

/// A row that only informs (no press look).
struct PageInfoRow: View {
    var title: String
    var caption: String? = nil
    var value: String? = nil
    var valueTone: Color? = nil
    var lead: PageLead = .none
    var trailingMark: PageMark = .none
    var body: some View {
        PageRowContent(title: title, caption: caption, value: value, valueTone: valueTone, lead: lead, trailingMark: trailingMark)
            .background(Color.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

/// A row that opens a pushed page.
struct PageLinkRow<Destination: View>: View {
    var title: String
    var caption: String? = nil
    var value: String? = nil
    var valueTone: Color? = nil
    var lead: PageLead = .none
    var trailingMark: PageMark = .none
    @ViewBuilder var destination: () -> Destination

    var body: some View {
        NavigationLink {
            destination()
        } label: {
            PageRowContent(title: title, caption: caption, value: value, valueTone: valueTone, lead: lead,
                            trailingMark: trailingMark, chevron: true)
        }
        .buttonStyle(PageRowStyle())
        .simultaneousGesture(TapGesture().onEnded { PageHaptics.tap() })
    }
}

/// The settings switch: a capsule track with a knob; ink colors while its row is pressed.
struct PageSwitch: View {
    var on: Bool
    var lit = false
    var body: some View {
        ZStack(alignment: on ? .trailing : .leading) {
            Capsule().fill(track)
            Circle().fill(knob).padding(3.5)
        }
        .frame(width: 48, height: 29)
        .animation(.spring(response: 0.28, dampingFraction: 0.75), value: on)
        .accessibilityHidden(true)
    }
    private var track: Color {
        if lit { return on ? Palette.ink : Palette.ink.opacity(0.22) }
        return on ? Palette.pearl : Color.white.opacity(0.18)
    }
    private var knob: Color {
        if lit { return on ? Palette.pearl : Palette.ink }
        return on ? Palette.ink : Palette.text
    }
}

// MARK: Small parts (SetupParts)

/// A section heading in tracked caps, with its "Required" / "Optional" tag.
struct PageSection: View {
    var title: String
    var required: Bool? = nil
    var body: some View {
        HStack(spacing: 12) {
            Text(title.uppercased())
                .font(.system(size: 12, weight: .semibold))
                .tracking(1.2)
                .foregroundStyle(Palette.textSecondary)
            if let required {
                Text(required ? "Required" : "Optional")
                    .font(.system(size: 11.5, weight: .semibold))
                    .foregroundStyle(required ? Palette.text : Palette.textSecondary)
                    .padding(.horizontal, 10).padding(.vertical, 3.5)
                    .background(Capsule().fill(Color.white.opacity(required ? 0.14 : 0.05)))
                    .overlay(Capsule().strokeBorder(Color.white.opacity(required ? 0.30 : 0.16), lineWidth: 1))
            }
        }
        .padding(.leading, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityAddTraits(.isHeader)
    }
}

/// A plain explanation (sentence case).
struct PageNote: View {
    var text: String
    var size: CGFloat = 15
    var body: some View {
        Text(text)
            .font(.system(size: size))
            .foregroundStyle(Palette.textSecondary)
            .lineSpacing(3)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 6)
    }
}

/// A big result line: a status disc and a headline (link and test results).
struct PageResult: View {
    var mark: PageMark
    var headline: String
    var body: some View {
        HStack(spacing: 16) {
            PageMarkGlyph(mark: mark, size: 40)
            Text(headline)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(Palette.text)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// A round letter badge (SetupArt.monogram): pearl letters on dark glass in a thin rainbow ring.
struct PageMonogram: View {
    var text: String
    var size: CGFloat = 40
    var body: some View {
        ZStack {
            Circle().fill(Palette.surfaceRaised)
            RainbowRing(lineWidth: 1.5).opacity(0.85)
            Text(text)
                .font(.system(size: size * (text.count > 1 ? 0.34 : 0.42), weight: .bold))
                .foregroundStyle(Palette.pearl)
                .minimumScaleFactor(0.6)
                .lineLimit(1)
                .padding(4)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// Dream OS line art (SetupArt / GlyphPen): a monoline pearl glyph on a dark-glass disc in a thin rainbow ring.
/// The step's own drawing from DreamUI's `SetupArt` (named here by the symbol the page used before); a symbol with no
/// drawing yet keeps the old stand-in, same look.
struct PageArt: View {
    var symbol: String
    var size: CGFloat = 96
    var body: some View {
        if let kind = Self.kinds[symbol] {
            SetupArt(kind, size: size)
        } else {
            standIn
        }
    }

    /// Setup's steps (SetupWizardView StepHead symbols) to their SetupArt.kt drawings.
    static let kinds: [String: SetupArt.Kind] = [
        "sparkles": .done, "hand.raised": .intro, "clock": .clock, "key": .accounts, "magnifyingglass": .scrapers,
        "checkmark.seal": .check, "person.crop.circle": .personalize, "person.2": .profiles, "film": .playback,
        "slider.horizontal.3": .playback, "gauge.with.dots.needle.33percent": .speed, "lock.open": .unlock,
    ]

    private var standIn: some View {
        ZStack {
            Circle().fill(LinearGradient(colors: [Color(hex: 0x1C1C1F), Color(hex: 0x0E0E10)], startPoint: .topLeading, endPoint: .bottomTrailing))
            RainbowRing(lineWidth: max(1.5, size * 0.018))
            Image(systemName: symbol)
                .font(.system(size: size * 0.38, weight: .light))
                .foregroundStyle(Palette.pearl)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// A full-width primary action (Play type: pearl at rest, rainbow fade while pressed).
struct PagePrimaryButton: View {
    var title: String
    var systemImage: String? = nil
    var action: () -> Void
    var body: some View {
        Button {
            PageHaptics.tap()
            action()
        } label: {
            HStack(spacing: 10) {
                if let systemImage { Image(systemName: systemImage).font(.system(size: 16, weight: .bold)) }
                Text(title).lineLimit(1)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(PlayButtonStyle())
    }
}

/// A full-width secondary action (pearl press).
struct PageSecondaryButton: View {
    var title: String
    var systemImage: String? = nil
    var action: () -> Void
    var body: some View {
        Button {
            PageHaptics.tap()
            action()
        } label: {
            HStack(spacing: 10) {
                if let systemImage { Image(systemName: systemImage).font(.system(size: 15, weight: .semibold)) }
                Text(title).lineLimit(1)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(PearlButtonStyle())
    }
}

/// A text field in the Dream look: the iOS keyboard, a pill of glass with a hairline.
struct PageTextField: View {
    var placeholder: String
    @Binding var text: String
    var secure = false
    var keyboard: UIKeyboardType = .default
    var capitalization: TextInputAutocapitalization = .never
    var submit: () -> Void = {}
    @FocusState private var focused: Bool

    var body: some View {
        Group {
            if secure {
                SecureField("", text: $text, prompt: prompt)
            } else {
                TextField("", text: $text, prompt: prompt)
            }
        }
        .font(.system(size: 17))
        .foregroundStyle(Palette.text)
        .tint(Palette.pearl)
        .keyboardType(keyboard)
        .textInputAutocapitalization(capitalization)
        .autocorrectionDisabled()
        .submitLabel(.done)
        .onSubmit(submit)
        .focused($focused)
        .padding(.horizontal, 18)
        .frame(height: 50)
        .background(Capsule().fill(Color.white.opacity(0.07)))
        .overlay(Capsule().strokeBorder(focused ? Palette.pearl.opacity(0.7) : Color.white.opacity(0.14), lineWidth: focused ? 1.5 : 1))
    }

    private var prompt: Text { Text(placeholder).foregroundStyle(Color.white.opacity(0.36)) }
}

// MARK: Page scaffold

/// A pushed settings/setup page: the Dream background, a scrolling column with real gutters, the large title.
struct PageScaffold<Content: View>: View {
    var title: String
    var subtitle: String? = nil
    @ViewBuilder var content: () -> Content
    /// The bar's black glass, up once the column scrolls under the (collapsed) title.
    @State private var bar = BarGlassModel(lead: 0, fade: 20)

    var body: some View {
        let long = Self.titleTooLong(title)
        ZStack {
            DreamBackground()
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if long {
                        // iOS ends a large title that doesn't fit with "…": a long one is the page's own heading,
                        // wrapping, in the same type (owner, 2026-10-28).
                        Text(title)
                            .font(.largeTitle.weight(.bold))
                            .foregroundStyle(Palette.text)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.bottom, 16)
                            .accessibilityAddTraits(.isHeader)
                    }
                    if let subtitle {
                        PageNote(text: subtitle, size: 15).padding(.bottom, 22)
                    }
                    content()
                }
                .barGlassMarker(bar)
                .frame(maxWidth: 680, alignment: .leading)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 40)
            }
            .scrollIndicators(.hidden)
        }
        .navigationTitle(title)
        .modifier(PageTitleMode(long: long, title: title, bar: bar))
        .barGlass(bar)
    }

    /// True when the large title wouldn't fit on one line in the bar (the bar's large title: Large Title type in bold,
    /// at the viewer's text size, inside the bar's side margins).
    static func titleTooLong(_ title: String) -> Bool {
        let size = UIFont.preferredFont(forTextStyle: .largeTitle).pointSize
        let font = UIFont.systemFont(ofSize: size, weight: .bold)
        let w = (title as NSString).size(withAttributes: [.font: font]).width
        let screen = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.screen.bounds.width }.first ?? 375
        let margin: CGFloat = screen >= 414 ? 20 : 16
        return w > screen - 2 * margin - 6
    }
}

/// A short title stays the bar's large title; a long one is the page's own heading, with a small name in the bar
/// (scaled to fit, never "…") once the page scrolls under it.
private struct PageTitleMode: ViewModifier {
    let long: Bool
    let title: String
    let bar: BarGlassModel
    func body(content: Content) -> some View {
        if long {
            content
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .principal) {
                        PageBarName(bar: bar, text: title)
                    }
                }
        } else {
            content.navigationBarTitleDisplayMode(.large)
        }
    }
}

private struct PageBarName: View {
    let bar: BarGlassModel
    let text: String
    var body: some View {
        Text(text)
            .font(.headline)
            .foregroundStyle(Palette.text)
            .lineLimit(1)
            .minimumScaleFactor(0.5)
            .opacity(bar.amount >= 0.9 ? 1 : 0)
            .animation(.easeOut(duration: 0.18), value: bar.amount >= 0.9)
            .accessibilityHidden(bar.amount < 0.9)
    }
}

/// Spacing between rows in a group, and between groups.
enum PageSpace {
    static let row: CGFloat = 8
    static let group: CGFloat = 26
}

extension View {
    /// The Dream dark navigation look for stacks this module owns (sheets).
    func dreamNavigationLook() -> some View {
        self.toolbarColorScheme(.dark, for: .navigationBar)
            .tint(Palette.pearl)
    }
}
