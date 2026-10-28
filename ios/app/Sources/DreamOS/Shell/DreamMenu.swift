import SwiftUI
import DreamCore
import DreamUI

/// One choice in a Dream menu.
struct DreamMenuItem: Identifiable, Hashable {
    let id: String
    var label: String
    /// Quiet text on the right ("10 Episodes", "Coming Mar 4").
    var detail: String? = nil
    /// A drawn genre glyph (`GenreGlyph` name) before the label, in `tint`. When one row has a glyph, give every row
    /// one so they line up (STANDARDS §3: no empty icon slots).
    var glyph: String? = nil
    var tint: UInt32 = 0xFFFFFF
}

/// Dream's own picker (never the system menu): a glass capsule naming the current choice with a chevron, opening
/// `DreamMenu` over the page. Used by the title page's season picker and Browse's Every Movie and Show filters.
struct DreamPickerButton: View {
    /// The menu's heading ("Seasons", "Genres").
    let heading: String
    let items: [DreamMenuItem]
    let selected: String?
    /// The button's words (default: the current choice's label).
    var label: String? = nil
    var accessibilityHint = "Opens a list of choices"
    let onPick: (String) -> Void
    @State private var open = false

    var body: some View {
        let text = label ?? items.first { $0.id == selected }?.label ?? heading
        Button {
            PageHaptics.tap()
            present(true)
        } label: {
            HStack(spacing: 9) {
                Text(text)
                Image(systemName: "chevron.down").font(.system(size: 12, weight: .bold))
            }
            .font(.system(size: 15, weight: .semibold))
            .lineLimit(1)
            .fixedSize()
            .padding(.horizontal, 16)
            .frame(height: 40)
        }
        .buttonStyle(DreamPickerButtonStyle())
        .accessibilityLabel(text)
        .accessibilityHint(accessibilityHint)
        .fullScreenCover(isPresented: $open) {
            DreamMenu(heading: heading, items: items, selected: selected, onPick: onPick, close: { present(false) })
                .presentationBackground(.clear)
        }
    }

    /// The cover comes and goes without the system's slide; the menu draws its own fade and scale.
    private func present(_ on: Bool) {
        var t = Transaction()
        t.disablesAnimations = true
        withTransaction(t) { open = on }
    }
}

/// The picker button: glass at rest; pressed, a pearl capsule with dark ink and a rainbow border.
struct DreamPickerButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        let p = configuration.isPressed
        configuration.label
            .foregroundStyle(p ? Palette.ink : Palette.text)
            .background(Capsule().fill(p ? Palette.pearl : Color.white.opacity(0.10)))
            .overlay(Capsule().strokeBorder(Color.white.opacity(p ? 0 : 0.14), lineWidth: 1))
            .rainbowBorder(p, radius: 20, width: 2)
            .contentShape(Capsule())
            .scaleEffect(p ? 0.97 : 1)
            .animation(.easeOut(duration: 0.14), value: p)
    }
}

/// Dream's menu panel: black smoked glass over a dimmed page, a heading, one row per choice (its drawn glyph, the
/// label, quiet detail on the right). The current choice is a pearl row with dark ink and a rainbow border; a row
/// being pressed looks the same. A tap outside, a swipe or VoiceOver's escape closes it; it fades and scales in and
/// out. A list taller than the screen scrolls with soft edges, opened on the current choice.
/// Show it in a `fullScreenCover` with a clear background and no system animation (`DreamPickerButton` does).
struct DreamMenu: View {
    let heading: String
    let items: [DreamMenuItem]
    let selected: String?
    let onPick: (String) -> Void
    let close: () -> Void

    @State private var shown = false
    @State private var drag: CGFloat = 0
    @State private var leaving = false

    private let rowH: CGFloat = 52
    private let rowGap: CGFloat = 4
    private let listPad: CGFloat = 10
    private let headerH: CGFloat = 48
    private let radius: CGFloat = 22

    var body: some View {
        GeometryReader { geo in
            let maxH = max(180, geo.size.height - 72)
            let n = CGFloat(items.count)
            let listH = n * rowH + max(0, n - 1) * rowGap + listPad * 2
            let fits = headerH + listH <= maxH
            ZStack {
                Color.black.opacity(shown ? 0.6 : 0)
                    .ignoresSafeArea()
                    .contentShape(Rectangle())
                    .onTapGesture { dismiss(nil) }
                    .gesture(swipe)
                    .accessibilityElement()
                    .accessibilityLabel("Close")
                    .accessibilityAddTraits(.isButton)
                    .accessibilityAction { dismiss(nil) }
                panel(fits: fits, listH: min(listH, maxH - headerH))
                    .frame(maxWidth: 360)
                    .padding(.horizontal, 24)
                    .offset(y: drag)
                    .scaleEffect(shown ? 1 : 0.92)
                    .opacity(shown ? max(0.35, 1 - Double(abs(drag)) / 260) : 0)
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .environment(\.colorScheme, .dark)
        .onAppear { withAnimation(.spring(response: 0.34, dampingFraction: 0.86)) { shown = true } }
        .accessibilityAction(.escape) { dismiss(nil) }
    }

    private func panel(fits: Bool, listH: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(heading)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Palette.text)
                .accessibilityAddTraits(.isHeader)
                .padding(.horizontal, 26)
                .frame(maxWidth: .infinity, minHeight: headerH, alignment: .bottomLeading)
                .padding(.bottom, -4)
                .contentShape(Rectangle())
                .gesture(swipe)
            if fits {
                rows.padding(listPad).gesture(swipe)
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        rows.padding(listPad)
                    }
                    .scrollIndicators(.hidden)
                    .frame(height: listH)
                    .mask(softEdges(height: listH))
                    .onAppear { if let selected { proxy.scrollTo(selected, anchor: .center) } }
                }
            }
        }
        .padding(.bottom, 2)
        .background {
            RoundedRectangle(cornerRadius: radius, style: .continuous).fill(.ultraThinMaterial)
            RoundedRectangle(cornerRadius: radius, style: .continuous).fill(Palette.surface.opacity(0.88))
        }
        .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(Color.white.opacity(0.10), lineWidth: 1))
        .shadow(color: .black.opacity(0.55), radius: 24, y: 10)
    }

    private var rows: some View {
        VStack(spacing: rowGap) {
            ForEach(items.uniqued()) { item in
                let current = item.id == selected
                Button { pick(item.id) } label: {
                    HStack(spacing: 12) {
                        if let g = item.glyph {
                            GenreGlyph(g, tint: item.tint)
                                .frame(width: 30, height: 30)
                                .accessibilityHidden(true)
                        }
                        Text(item.label)
                            .font(.system(size: 16, weight: .semibold))
                            .lineLimit(1)
                        Spacer(minLength: 12)
                        if let d = item.detail {
                            Text(d)
                                .font(.system(size: 13.5, weight: .medium))
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                    .padding(.horizontal, item.glyph == nil ? 16 : 12)
                    .frame(maxWidth: .infinity, minHeight: rowH, maxHeight: rowH)
                }
                .buttonStyle(DreamMenuRowStyle(current: current))
                .accessibilityAddTraits(current ? .isSelected : [])
                .id(item.id)
            }
        }
    }

    /// The list melts into the panel at its top and bottom when it scrolls.
    private func softEdges(height: CGFloat) -> some View {
        let f = min(0.2, 16 / max(1, height))
        return LinearGradient(stops: [
            .init(color: .clear, location: 0), .init(color: .black, location: f),
            .init(color: .black, location: 1 - f), .init(color: .clear, location: 1),
        ], startPoint: .top, endPoint: .bottom)
    }

    private var swipe: some Gesture {
        DragGesture(minimumDistance: 10)
            .onChanged { g in if !leaving { drag = g.translation.height * 0.7 } }
            .onEnded { g in
                if abs(g.translation.height) > 70 || abs(g.predictedEndTranslation.height) > 180 {
                    dismiss(nil)
                } else {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { drag = 0 }
                }
            }
    }

    private func pick(_ id: String) {
        PageHaptics.select()
        dismiss(id == selected ? nil : id)
    }

    /// Fades and scales the panel out, then closes the cover (the choice takes effect behind the fading panel).
    private func dismiss(_ pick: String?) {
        guard !leaving else { return }
        leaving = true
        if let pick { onPick(pick) }
        withAnimation(.easeIn(duration: 0.18)) { shown = false }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.19) { close() }
    }
}

/// A menu row: plain at rest; the current choice (and a row being pressed) is a pearl box with dark ink and a
/// rainbow border.
private struct DreamMenuRowStyle: ButtonStyle {
    var current: Bool
    func makeBody(configuration: Configuration) -> some View {
        let lit = current || configuration.isPressed
        configuration.label
            .foregroundStyle(lit ? Palette.ink : Palette.text, lit ? Palette.inkDim : Palette.textSecondary)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(lit ? Palette.pearl : .clear))
            .rainbowBorder(lit, radius: 12, width: 2)
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.14), value: configuration.isPressed)
    }
}
