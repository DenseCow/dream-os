import SwiftUI
import DreamUI

/// The black glass behind a pushed page's bar (the system Back button, the page's name): Home's top-bar look
/// (ultra-thin material, 55% black, a hairline), fading in as the page's content slides under the bar, so Back and
/// the name never draw over buttons or text. Every pushed page hides the system bar background and uses this instead.
///
/// Tracked pages: hold a `@State BarGlassModel`, put `.barGlassMarker(bar)` on the first content that must not go
/// under the bar (the text under a hero, a settings page's column), and `.barGlass(bar)` on the page. Pages that
/// already know when they have scrolled use `.barGlass(amount)`.
@MainActor @Observable
final class BarGlassModel {
    /// 0 ... 1 in tenths (only the glass and the bar's name read it, so the page itself never redraws while scrolling).
    fileprivate(set) var amount: Double = 0
    /// Starts fading in this far before the content reaches the bar...
    @ObservationIgnored private let lead: CGFloat
    /// ...and is fully up after this much more.
    @ObservationIgnored private let fade: CGFloat
    @ObservationIgnored private var barBottom: CGFloat?
    @ObservationIgnored private var contentTop: CGFloat?

    init(lead: CGFloat = 0, fade: CGFloat = 24) {
        self.lead = lead
        self.fade = fade
    }

    fileprivate func bar(_ y: CGFloat) { barBottom = y; update() }
    fileprivate func content(_ y: CGFloat) { contentTop = y; update() }

    private func update() {
        guard let barBottom, let contentTop else { return }
        let v = min(1, max(0, (barBottom - contentTop + lead) / max(1, fade)))
        let q = (v * 10).rounded() / 10
        if q != amount { amount = q }
    }
}

extension View {
    /// The glass behind the bar, its strength read from `bar` (see `barGlassMarker`).
    func barGlass(_ bar: BarGlassModel) -> some View {
        self.toolbarBackground(.hidden, for: .navigationBar)
            .overlay(alignment: .top) {
                BarGlassLayer(bar: bar)
                    .onGeometryChange(for: CGFloat.self) { $0.frame(in: .global).minY.rounded() } action: { bar.bar($0) }
            }
    }

    /// The glass behind the bar at a known strength (0 ... 1, animated).
    func barGlass(_ amount: Double) -> some View {
        self.toolbarBackground(.hidden, for: .navigationBar)
            .overlay(alignment: .top) { BarGlassShape(amount: amount) }
    }

    /// Marks the content whose top decides the glass: when it reaches the bar, the glass is up.
    func barGlassMarker(_ bar: BarGlassModel) -> some View {
        onGeometryChange(for: CGFloat.self) { $0.frame(in: .global).minY.rounded() } action: { bar.content($0) }
    }
}

/// Reads the model (so only this layer redraws as the glass fades).
private struct BarGlassLayer: View {
    let bar: BarGlassModel
    var body: some View { BarGlassShape(amount: bar.amount) }
}

/// A zero-height line at the top of the page's safe area whose background reaches up through the bar and the status
/// bar: the glass sits under the system's Back button and never takes a touch.
private struct BarGlassShape: View {
    let amount: Double
    var body: some View {
        Color.clear
            .frame(maxWidth: .infinity)
            .frame(height: 0)
            .background(alignment: .bottom) {
                ZStack {
                    Rectangle().fill(.ultraThinMaterial)
                    Color.black.opacity(0.55)
                }
                .overlay(alignment: .bottom) { Rectangle().fill(Palette.hairline).frame(height: 1) }
                .opacity(amount)
                .ignoresSafeArea(edges: .top)
            }
            .animation(.easeOut(duration: 0.15), value: amount)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .environment(\.colorScheme, .dark)
    }
}

/// The page's name in the bar, shown once the glass is up (title pages, person pages).
struct BarGlassTitle: View {
    let bar: BarGlassModel
    let text: String
    var body: some View {
        Text(text)
            .font(.headline)
            .foregroundStyle(Palette.text)
            .lineLimit(1)
            .opacity(bar.amount >= 0.9 ? 1 : 0)
            .animation(.easeOut(duration: 0.18), value: bar.amount >= 0.9)
            .accessibilityHidden(bar.amount < 0.9)
    }
}
