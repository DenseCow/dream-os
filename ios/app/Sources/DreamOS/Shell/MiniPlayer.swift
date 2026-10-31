import SwiftUI
import UIKit
import DreamUI
import DreamPlayer

/// Dream Player minimized (YouTube style): docked above the tab bar while the viewer browses, still playing with sound.
/// The live picture (the same engine view, moved here; no second decoder), the title and episode line, Play / Pause
/// and Close, a thin rainbow progress line along its foot. Tap (or swipe up) to expand back to full screen at the same
/// spot; swipe it away sideways or down, or Close, to stop and save the spot exactly like the player's X.
///
/// It lives in the shell's bottom safe-area inset with the tab bar (AppShell), so every page's scroll view keeps room
/// for it: the last row and Back to Top always end above it. State and buttons: `PlayerPresentation` (DreamPlayer).
struct MiniPlayerDock: View {
    var body: some View {
        let p = PlayerPresentation.shared
        if p.mode == .mini {
            MiniPlayerBar()
                .id(p.arrival)
                .padding(.horizontal, Cinema.gutter)
                .padding(.bottom, 8)
        }
    }
}

private struct MiniPlayerBar: View {
    private let p = PlayerPresentation.shared

    /// Docked; false while flying in from (or back up to) the middle of the screen.
    @State private var landed = false
    /// The picture's slot on screen (global points), measured at rest.
    @State private var slot: CGRect = .zero
    @State private var drag: CGSize = .zero
    @State private var leaving = false

    static let videoW: CGFloat = 104
    static let videoH: CGFloat = 104 * 9 / 16
    static let height: CGFloat = 74
    static let radius: CGFloat = 14
    /// The picture's corner in its own (large, scaled-down) points: about 6 pt docked.
    static let videoCorner: CGFloat = 24

    var body: some View {
        ZStack(alignment: .leading) {
            chrome
                .opacity(landed && !leaving ? 1 : 0)
                .allowsHitTesting(landed && !leaving)
            videoSlot.padding(.leading, 8)
        }
        .frame(height: Self.height)
        .contentShape(RoundedRectangle(cornerRadius: Self.radius, style: .continuous))
        .offset(drag)
        .opacity(fade)
        .onTapGesture { expand() }
        .gesture(swipe)
        .accessibilityElement(children: .contain)
        .accessibilityAction(named: "Expand Player") { expand() }
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .task { await flyIn() }
    }

    // MARK: Pieces

    private var chrome: some View {
        HStack(spacing: 12) {
            Color.clear.frame(width: Self.videoW, height: Self.videoH)
            VStack(alignment: .leading, spacing: 3) {
                Text(p.title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Palette.text)
                    .lineLimit(1)
                Text(p.problem ?? p.line)
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundStyle(p.problem == nil ? Palette.text.opacity(0.62) : Palette.amberText)
                    .lineLimit(1)
                    .opacity(p.line.isEmpty && p.problem == nil ? 0 : 1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: 8) {
                MiniRoundButton(system: p.isPlaying ? "pause.fill" : "play.fill", label: p.isPlaying ? "Pause" : "Play") {
                    miniHaptic(.light)
                    p.togglePlay()
                }
                MiniRoundButton(system: "xmark", label: "Close") { close(dx: 0, dy: 0) }
            }
        }
        .padding(.leading, 8)
        .padding(.trailing, 10)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background {
            ZStack {
                Rectangle().fill(.ultraThinMaterial)
                Color.black.opacity(0.62)
            }
        }
        .overlay(alignment: .bottom) { MiniProgressLine() }
        .clipShape(RoundedRectangle(cornerRadius: Self.radius, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Self.radius, style: .continuous).strokeBorder(Palette.hairline, lineWidth: 1))
        .shadow(color: .black.opacity(0.45), radius: 14, y: 4)
    }

    /// The picture: drawn at the hero size (the full screen's width) and scaled into its slot, so flying in and out
    /// is one smooth scale and move (and the docked picture is a sharp downscale, never an upscale).
    private var videoSlot: some View {
        Color.clear
            .frame(width: Self.videoW, height: Self.videoH)
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { r in
                if drag == .zero, !leaving { slot = r }
            }
            .overlay {
                if slot != .zero {
                    MiniVideo(cornerRadius: Self.videoCorner)
                        .frame(width: hero.width, height: hero.height)
                        .scaleEffect(landed ? Self.videoW / hero.width : 1)
                        .offset(landed ? .zero : heroOffset)
                        .shadow(color: .black.opacity(landed ? 0 : 0.5), radius: 24)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
            }
            .overlay {
                if p.isLoading && landed {
                    ZStack {
                        Color.black.opacity(0.5)
                        MiniRing()
                    }
                    .clipShape(RoundedRectangle(cornerRadius: Self.videoCorner * Self.videoW / hero.width, style: .continuous))
                    .transition(.opacity)
                    .allowsHitTesting(false)
                }
            }
            .animation(.easeOut(duration: 0.2), value: p.isLoading)
    }

    // MARK: Geometry

    /// The phone's portrait size (browsing is portrait only; during the turn back from landscape the screen may still
    /// report the landscape size).
    private var screen: CGSize {
        let s = (UIApplication.shared.connectedScenes.first { $0 is UIWindowScene } as? UIWindowScene)?.screen.bounds.size
            ?? CGSize(width: 390, height: 844)
        return CGSize(width: min(s.width, s.height), height: max(s.width, s.height))
    }

    /// The picture at full width, 16:9.
    private var hero: CGSize { CGSize(width: screen.width, height: (screen.width * 9 / 16).rounded()) }

    /// From the slot's center to the middle of the screen, where the picture starts (and ends when expanding).
    private var heroOffset: CGSize {
        CGSize(width: screen.width / 2 - slot.midX, height: screen.height * 0.42 - slot.midY)
    }

    private var fade: Double {
        if leaving && drag == .zero { return 0 }
        let w = abs(drag.width) / 260, h = max(0, drag.height) / 110
        return max(0, 1 - max(w, h))
    }

    // MARK: Motion

    private func flyIn() async {
        // The app turns back to portrait first (the full player was landscape), then the picture settles into its slot.
        for _ in 0..<24 {
            if let scene = UIApplication.shared.connectedScenes.first(where: { $0 is UIWindowScene }) as? UIWindowScene,
               scene.effectiveGeometry.interfaceOrientation.isPortrait, slot != .zero { break }
            try? await Task.sleep(for: .milliseconds(25))
        }
        try? await Task.sleep(for: .milliseconds(40))
        withAnimation(.spring(response: 0.5, dampingFraction: 0.86)) { landed = true }
    }

    private var swipe: some Gesture {
        DragGesture(minimumDistance: 12)
            .onChanged { v in
                guard landed, !leaving else { return }
                let t = v.translation
                if abs(t.width) > abs(t.height) {
                    drag = CGSize(width: t.width, height: 0)
                } else if t.height > 0 {
                    drag = CGSize(width: 0, height: t.height)
                } else {
                    // Up: a little give, then it expands on release.
                    drag = CGSize(width: 0, height: t.height * 0.3)
                }
            }
            .onEnded { v in
                guard landed, !leaving else { return }
                let t = v.translation, e = v.predictedEndTranslation
                if abs(t.width) > abs(t.height) {
                    if abs(t.width) > 90 || abs(e.width) > 240 {
                        close(dx: (t.width > 0 ? 1 : -1) * (screen.width + 40), dy: 0)
                        return
                    }
                } else if t.height > 0 {
                    if t.height > 36 || e.height > 130 { close(dx: 0, dy: 120); return }
                } else if t.height < -30 || e.height < -130 {
                    drag = .zero
                    expand()
                    return
                }
                withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { drag = .zero }
            }
    }

    /// Back to full screen: the picture flies up to the middle while the bar fades, then the player takes over.
    private func expand() {
        guard landed, !leaving else { return }
        miniHaptic(.medium)
        withAnimation(.easeInOut(duration: 0.26)) {
            landed = false
            drag = .zero
        } completion: {
            p.expand()
        }
    }

    /// Swiped away or Close: off it goes, then the player closes (spot saved, as with the player's X).
    private func close(dx: CGFloat, dy: CGFloat) {
        guard !leaving else { return }
        miniHaptic(.light)
        withAnimation(.easeOut(duration: 0.22)) {
            leaving = true
            drag = CGSize(width: dx, height: dy)
        } completion: {
            p.close()
        }
    }
}

/// The running time as a thin line in the fading rainbow along the bar's foot (its own view, so only it redraws).
private struct MiniProgressLine: View {
    var body: some View {
        let progress = PlayerPresentation.shared.progress
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Rectangle().fill(Color.white.opacity(0.12))
                RainbowFade { h in Rectangle().fill(Color(hex: h)) }.frame(width: g.size.width * progress)
            }
        }
        .frame(height: 2)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// A small round dark-glass button; pressed it turns pearl with dark ink and a rainbow border (STANDARDS).
private struct MiniRoundButton: View {
    let system: String
    let label: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: system).font(.system(size: 16, weight: .semibold))
        }
        .buttonStyle(MiniRoundStyle())
        .accessibilityLabel(label)
    }
}

private struct MiniRoundStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        let pressed = configuration.isPressed
        configuration.label
            .foregroundStyle(pressed ? Palette.ink : Palette.text)
            .frame(width: 40, height: 40)
            .background(Circle().fill(pressed ? AnyShapeStyle(Palette.pearl) : AnyShapeStyle(Color.white.opacity(0.08))))
            .rainbowBorder(pressed, radius: 20, width: 2)
            .scaleEffect(pressed ? 0.94 : 1)
            .animation(Cinema.pressAnimation, value: pressed)
            .contentShape(Circle())
    }
}

/// The calm loading ring, small: a faint track and an arc in the fading rainbow turning once every 1.1 s (the hue read
/// in the turn's own frames).
private struct MiniRing: View {
    var body: some View {
        TimelineView(.animation) { ctx in
            let a = ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.1) / 1.1 * 360
            ZStack {
                Circle().stroke(Color.white.opacity(0.16), lineWidth: 2.5)
                Circle().trim(from: 0, to: 100 / 360)
                    .stroke(Color(hex: Rainbow.fadeHex(ctx.date)), style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                    .rotationEffect(.degrees(a - 90))
            }
            .frame(width: 22, height: 22)
        }
    }
}

@MainActor private func miniHaptic(_ style: UIImpactFeedbackGenerator.FeedbackStyle) {
    UIImpactFeedbackGenerator(style: style).impactOccurred()
}
