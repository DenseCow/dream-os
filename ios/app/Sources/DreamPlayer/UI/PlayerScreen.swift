import SwiftUI
import UIKit
import DreamCore
import DreamUI

/// Dream Player's screen (fire-home PlayerOverlay for touch): tap to show / hide the controls, double-tap a
/// side for ±10 s, drag sideways to scrub, pinch to fill, haptics. The session owns all the logic.
struct PlayerScreen: View {
    @ObservedObject var session: PlayerSession
    @State private var controls = false
    @State private var hideTask: Task<Void, Never>?
    @State private var pauseCard = false
    @State private var sheet: PlayerSheet?
    @State private var scrubTarget: Double?
    @State private var dragBase: Double = 0
    @State private var tapOffset: Double = 0
    @State private var tapSide = 0
    @State private var tapCommit: Task<Void, Never>?
    @State private var pauseWait: Task<Void, Never>?
    @State private var flash: Bool?
    @State private var xray = false
    @State private var levels = LevelSwipeStatus()

    static let hideAfter: Double = 4
    /// The controls fade in and out with the same curve and length.
    static let fade = Animation.easeInOut(duration: 0.32)
    static let pauseCardAfter: Double = 8

    private var s: EngineState { session.state }
    private var ready: Bool { session.phase == .playing }
    private var paused: Bool { ready && !s.isPlaying && !s.isBuffering && !session.ended }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black.ignoresSafeArea()
                VideoSurface(view: session.engine.view).ignoresSafeArea()
                Color.black.opacity(dim).ignoresSafeArea().animation(.easeInOut(duration: pauseCard ? 0.7 : 0.3), value: dim)
                    .allowsHitTesting(false)
                gestureLayer(geo.size)
                    // Brightness (left third) / volume (right third) swipes and their bar (UI/LevelSwipes.swift).
                    .levelSwipes(enabled: ready && sheet == nil && !xray && !pauseCard && session.postPlay == .none,
                                 minimized: PlayerPresentation.shared.mode == .mini || PlayerPresentation.shared.leaving,
                                 controlsUp: controls, scrubbing: scrubTarget != nil, status: $levels)
                    // Pull down from the middle third: the mini player (UI/PlayerMinimize.swift).
                    .minimizeSwipe(session, size: geo.size)
                // Every view that comes and goes has a fixed zIndex: without one SwiftUI draws a view that is being
                // removed at the back of the ZStack, under the opaque video surface, so it vanished instead of fading.
                if ready { bubbles(geo.size).allowsHitTesting(false).zIndex(2) }
                // The flash only when the controls (and their center Play/Pause) are hidden; otherwise both icons showed.
                if let f = flash, !controls { CenterFlash(play: f).allowsHitTesting(false).zIndex(3) }
                if ready && s.isBuffering && !session.ended { BufferRing().allowsHitTesting(false).zIndex(4) }
                if controls && ready && session.postPlay == .none && !xray {
                    ControlsOverlay(session: session, scrubTarget: $scrubTarget, sheet: openSheet,
                                    onInteract: bump, onScrub: scrub, onCommit: commitScrub)
                        .transition(.opacity)
                        .zIndex(5)
                }
                if pauseCard && !controls && sheet == nil { PauseCard(session: session).transition(.opacity).zIndex(6) }
                if let label = session.skipLabel, ready, sheet == nil, session.postPlay == .none, !xray {
                    SkipPill(label: label) { haptic(.medium); session.skip() }
                        .padding(.bottom, controls ? 150 : 28).padding(.trailing, 28)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                        .animation(Self.fade, value: controls)
                        .zIndex(7)
                }
                PostPlayLayer(session: session).zIndex(8)
                if xray { XRayView(session: session, isOpen: $xray).zIndex(9) }
                if let t = session.toast {
                    ToastView(toast: t).padding(.top, 22)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                        .allowsHitTesting(false)
                        .zIndex(10)
                }
                if let sheet {
                    SheetHost(session: session, sheet: sheet, close: { closeSheet() }, openXRay: { closeSheet(show: false); xray = true })
                        .zIndex(11)
                }
                if session.phase != .playing { Curtain(session: session, openSources: { sheet = .source }).transition(.opacity).zIndex(12) }
            }
            .animation(Self.fade, value: controls)
            .animation(.easeOut(duration: 0.38), value: session.phase)
            .minimizeShrink(height: geo.size.height)
        }
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .preferredColorScheme(.dark)
        // Like Netflix, the screen never sleeps while the player is open (loading, playing, paused, Up Next), whatever
        // the phone's Auto-Lock setting says (owner). Closing the player hands Auto-Lock back.
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        .onChange(of: paused) { _, p in
            // One wait per pause: a pause, play, pause again within 8 s no longer brought the card early.
            pauseWait?.cancel()
            if p {
                pauseWait = Task {
                    try? await Task.sleep(for: .seconds(Self.pauseCardAfter))
                    guard !Task.isCancelled, paused, sheet == nil, !xray else { return }
                    controls = false
                    pauseCard = true
                }
            } else { pauseCard = false; bump() }
        }
        .onChange(of: session.phase) { _, p in if p != .playing { controls = false; pauseCard = false; xray = false } }
        .onChange(of: controls) { _, c in session.controlsShown(c) }
        // The level bar never shares the screen with the controls (nothing touches the skip pill or the bars).
        .onChange(of: levels.shown) { _, on in if on { hide() } }
        // The session handles background / foreground itself (sound on, picture off or floating).
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
            UIApplication.shared.isIdleTimerDisabled = true
            if ready { show() }
        }
    }

    private var dim: Double {
        if xray { return 0.16 }
        if pauseCard { return 0.66 }
        if paused && session.postPlay == .none { return 0.28 }
        return 0
    }

    // MARK: Gestures

    private func gestureLayer(_ size: CGSize) -> some View {
        Color.clear.contentShape(Rectangle())
            .onTapGesture(count: 2) { location in
                guard ready, s.duration > 0 else { return }
                doubleTap(dir: location.x < size.width / 2 ? -1 : 1)
            }
            .onTapGesture {
                session.userActed()
                if pauseCard { pauseCard = false; show(); return }
                if controls { hide() } else { show() }
            }
            .gesture(
                DragGesture(minimumDistance: 18)
                    .onChanged { v in
                        guard ready, s.duration > 0, !levels.swiping, abs(v.translation.width) > abs(v.translation.height) else { return }
                        if scrubTarget == nil { dragBase = s.position; show(); haptic(.light) }
                        // A full-width swipe covers ~10 minutes, finer near the start of the drag.
                        let span = min(600, s.duration)
                        scrub(dragBase + Double(v.translation.width / size.width) * span)
                    }
                    .onEnded { _ in if scrubTarget != nil { commitScrub() } }
            )
            .simultaneousGesture(
                MagnifyGesture().onEnded { v in
                    guard ready else { return }
                    let fill = v.magnification > 1
                    if fill != session.fill { session.setFill(fill); haptic(.soft) }
                }
            )
    }

    private func doubleTap(dir: Int) {
        session.userActed()
        haptic(.light)
        if tapSide != dir { tapOffset = 0 }
        tapSide = dir
        tapOffset += Double(dir) * 10
        tapCommit?.cancel()
        tapCommit = Task {
            try? await Task.sleep(for: .milliseconds(700))
            guard !Task.isCancelled else { return }
            session.seek(to: s.position + tapOffset)
            try? await Task.sleep(for: .milliseconds(420))
            guard !Task.isCancelled else { return }
            tapOffset = 0; tapSide = 0
        }
    }

    private func bubbles(_ size: CGSize) -> some View {
        HStack {
            if tapSide < 0 { SeekBubble(text: Labels.offset(tapOffset)) } else { Spacer().frame(width: 88) }
            Spacer()
            if tapSide > 0 { SeekBubble(text: Labels.offset(tapOffset)) } else { Spacer().frame(width: 88) }
        }
        .padding(.horizontal, size.width * 0.12)
        .animation(.spring(response: 0.25, dampingFraction: 0.7), value: tapOffset)
    }

    private func scrub(_ t: Double) {
        let target = min(max(0, t), max(0, s.duration - 2))
        if let old = scrubTarget, Int(old / 30) != Int(target / 30) { haptic(.soft) }
        scrubTarget = target
        bump()
    }

    private func commitScrub() {
        if let t = scrubTarget { session.seek(to: t); haptic(.light) }
        scrubTarget = nil
        bump()
    }

    // MARK: Controls visibility

    private func show() {
        guard ready, session.postPlay == .none else { return }
        pauseCard = false
        withAnimation(Self.fade) { controls = true }
        bump()
    }

    private func hide() {
        withAnimation(Self.fade) { controls = false }
        hideTask?.cancel()
    }

    /// Restarts the hide timer: 4 s while playing (buffering keeps them a little longer); paused they stay.
    private func bump() {
        session.userActed()
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(for: .seconds(Self.hideAfter))
            while !Task.isCancelled {
                if scrubTarget != nil || sheet != nil { try? await Task.sleep(for: .seconds(1)); continue }
                if s.isPlaying { withAnimation(Self.fade) { controls = false }; return }
                if s.isBuffering { try? await Task.sleep(for: .seconds(1.5)); continue }
                return
            }
        }
    }

    private func openSheet(_ k: PlayerSheet) {
        haptic(.light)
        if k == .playPause {
            let play = !(s.isPlaying || s.isBuffering && !s.paused)
            session.togglePlay()
            flash = play
            Task { try? await Task.sleep(for: .milliseconds(700)); flash = nil }
            bump()
            return
        }
        if k == .startOver { session.startOver(); bump(); return }
        if k == .nextEpisode { if let n = session.next { controls = false; session.playNext(n) }; return }
        controls = false
        sheet = k
    }

    private func closeSheet(show again: Bool = true) {
        sheet = nil
        if again { show() }
    }
}

enum PlayerSheet: Equatable {
    case audio, speed, info, episodes, source
    // Actions routed through the same callback.
    case playPause, startOver, nextEpisode
}

@MainActor func haptic(_ style: UIImpactFeedbackGenerator.FeedbackStyle) {
    UIImpactFeedbackGenerator(style: style).impactOccurred()
}

/// The engine's UIView in SwiftUI. The host takes the view back when the player returns from the mini player
/// (VideoDock, Session/PlayerPresentation.swift).
struct VideoSurface: UIViewRepresentable {
    let view: UIView
    func makeUIView(context: Context) -> UIView {
        let host = DockHostView(video: view, spot: .full)
        VideoDock.shared.register(host)
        return host
    }
    func updateUIView(_ uiView: UIView, context: Context) {}
}

// MARK: - Controls

struct ControlsOverlay: View {
    @ObservedObject var session: PlayerSession
    @Binding var scrubTarget: Double?
    let sheet: (PlayerSheet) -> Void
    let onInteract: () -> Void
    let onScrub: (Double) -> Void
    let onCommit: () -> Void

    private var s: EngineState { session.state }

    var body: some View {
        ZStack {
            VStack(spacing: 0) {
                LinearGradient(colors: [.black.opacity(0.80), .black.opacity(0)], startPoint: .top, endPoint: .bottom).frame(height: 170)
                Spacer()
                LinearGradient(colors: [.black.opacity(0), .black.opacity(0.94)], startPoint: .top, endPoint: .bottom).frame(height: 220)
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)
            VStack(alignment: .leading, spacing: 0) {
                // While scrubbing only the timeline and its preview card show (the card rises into this space).
                topBar.opacity(scrubbing ? 0 : 1).allowsHitTesting(!scrubbing)
                Spacer(minLength: 0)
                bottomBar
            }
            .padding(.horizontal, 24).padding(.vertical, 18)
            centerTransport.opacity(scrubbing ? 0 : 1).allowsHitTesting(!scrubbing)
        }
        .animation(.easeInOut(duration: 0.2), value: scrubbing)
    }

    private var scrubbing: Bool { scrubTarget != nil }

    /// The big Play / Pause in the middle with Back 10 / Forward 10 beside it (Apple TV / Netflix style).
    private var centerTransport: some View {
        HStack(spacing: 56) {
            RoundIcon(system: "gobackward.10", label: "Back 10 Seconds", diameter: 60, glyph: 24) {
                haptic(.light); session.seek(to: s.position - 10); onInteract()
            }
            RoundIcon(system: s.isPlaying || (s.isBuffering && !s.paused) ? "pause.fill" : "play.fill", label: "Play or Pause",
                      diameter: 84, glyph: 34) { sheet(.playPause) }
            RoundIcon(system: "goforward.10", label: "Forward 10 Seconds", diameter: 60, glyph: 24) {
                haptic(.light); session.seek(to: s.position + 10); onInteract()
            }
        }
    }

    private var topBar: some View {
        HStack(alignment: .top, spacing: 16) {
            // Minimize: the mini player above the tab bar (UI/PlayerMinimize.swift).
            if PlayerMinimize.canMinimize(session) {
                RoundIcon(system: "chevron.down", label: "Minimize") { PlayerMinimize.minimize(session) }
            }
            VStack(alignment: .leading, spacing: 8) {
                NowPlayingLabel()
                TitleBlock(session: session, logoHeight: 44, titleSize: 24)
                Text(subLine).font(.system(size: 15, weight: .medium)).foregroundStyle(.white.opacity(0.78)).lineLimit(1)
                    .opacity(subLine.isEmpty ? 0 : 1)
                BadgeRow(session: session, compact: true)
            }
            Spacer(minLength: 12)
            HStack(alignment: .top, spacing: 14) {
                TimelineView(.periodic(from: .now, by: 15)) { ctx in
                    VStack(alignment: .trailing, spacing: 4) {
                        Text(DreamTime.formatTime(ctx.date)).font(.system(size: 20, weight: .semibold)).monospacedDigit()
                        if s.duration > 0 {
                            Text("Ends at " + DreamTime.formatTime(ctx.date.addingTimeInterval(max(0, s.duration - s.position) / max(0.25, session.speed))))
                                .font(.system(size: 13, weight: .medium)).foregroundStyle(.white.opacity(0.66)).monospacedDigit()
                        }
                    }
                }
                .foregroundStyle(.white)
                .padding(.trailing, 4)
                if session.pip.isAvailable {
                    RoundIcon(system: "pip.enter", label: "Picture in Picture") { haptic(.light); session.pip.toggle() }
                }
                RoundIcon(system: "xmark", label: "Close") { session.close() }
            }
        }
    }

    private var subLine: String {
        guard let r = session.request else { return "" }
        if session.isTrailer { return "Trailer" }
        if let se = r.season, let e = r.episode {
            let name = r.episodeName ?? session.episodeInfo?.name
            return ["S\(se) E\(e)", name].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "  ·  ")
        }
        return ["Movie", r.title.year.isEmpty ? nil : r.title.year].compactMap { $0 }.joined(separator: "  ·  ")
    }

    private var bottomBar: some View {
        VStack(spacing: 6) {
            Scrubber(position: s.position, duration: s.duration, buffered: s.buffered, target: scrubTarget,
                     markers: session.isTrailer ? [] : session.markers,
                     preview: session.isTrailer ? nil : session.engine.thumbnails, onScrub: onScrub, onCommit: onCommit)
                .frame(height: 30)
            HStack {
                Text(Labels.time(scrubTarget ?? s.position)).monospacedDigit()
                Spacer()
                Text(s.duration > 0 ? "\u{2212}" + Labels.time(max(0, s.duration - (scrubTarget ?? s.position))) : "LIVE").monospacedDigit()
                    .foregroundStyle(.white.opacity(0.62))
            }
            .font(.system(size: 13, weight: .medium)).foregroundStyle(.white.opacity(0.86))
            HStack(spacing: 14) {
                if !session.isTrailer {
                    if session.request?.episode != nil { RoundIcon(system: "rectangle.stack", label: "Episodes") { sheet(.episodes) } }
                    RoundIcon(system: "captions.bubble", label: "Audio & Subtitles") { sheet(.audio) }
                    RoundIcon(system: "arrow.left.arrow.right", label: "Change Source") { sheet(.source) }
                    RoundIcon(system: "info.circle", label: "Info") { sheet(.info) }
                }
                Spacer(minLength: 8)
                if !session.isTrailer, session.next != nil {
                    Button { sheet(.nextEpisode) } label: {
                        Label("Next Episode", systemImage: "forward.end.fill").labelStyle(GapLabelStyle())
                    }
                    .buttonStyle(PearlButtonStyle())
                }
                if !session.isTrailer, s.duration > 0, s.position > 60 {
                    RoundIcon(system: "arrow.counterclockwise", label: "Start Over") { sheet(.startOver) }
                }
            }
            .padding(.top, 8)
        }
    }
}

/// Icon and title with a real gap (STANDARDS: nothing touches).
struct GapLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 10) { configuration.icon; configuration.title }
    }
}

/// Round dark-glass icon button; pressed it turns pearl with dark ink and a rainbow border.
struct RoundIcon: View {
    let system: String
    let label: String
    var big = false
    /// Custom size (the center transport); nil = 44, or 54 when `big`.
    var diameter: CGFloat? = nil
    var glyph: CGFloat? = nil
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: system).font(.system(size: glyph ?? (big ? 22 : 18), weight: .semibold))
        }
        .buttonStyle(RoundGlassStyle(size: diameter ?? (big ? 54 : 44)))
        .accessibilityLabel(label)
    }
}

struct RoundGlassStyle: ButtonStyle {
    var size: CGFloat
    func makeBody(configuration: Configuration) -> some View {
        let p = configuration.isPressed
        configuration.label
            .foregroundStyle(p ? Palette.ink : .white)
            .frame(width: size, height: size)
            .background(Circle().fill(p ? AnyShapeStyle(Palette.pearl) : AnyShapeStyle(Color(white: 0.06).opacity(0.62))))
            .overlay(Circle().strokeBorder(.white.opacity(p ? 0 : 0.16), lineWidth: 1))
            .rainbowBorder(p, radius: size / 2, width: 2)
            .scaleEffect(p ? 0.94 : 1)
            .animation(.easeOut(duration: 0.16), value: p)
    }
}

/// The green "NOW PLAYING" (the curtain's and the controls').
struct NowPlayingLabel: View {
    var size: CGFloat = 13
    var body: some View {
        Text("NOW PLAYING").font(.system(size: size, weight: .bold)).tracking(size * 0.22)
            .foregroundStyle(Color(hex: 0x4ADE80)).lineLimit(1).fixedSize()
    }
}

/// The title's logo when TMDB has one, else its name.
struct TitleBlock: View {
    @ObservedObject var session: PlayerSession
    var logoHeight: CGFloat
    var titleSize: CGFloat
    var lines = 1
    var body: some View {
        let name = session.request?.title.name ?? "Dream Player"
        if let logo = session.details?.logo("w500") {
            AsyncImage(url: logo) { phase in
                if let img = phase.image {
                    img.resizable().scaledToFit().frame(maxWidth: logoHeight * 4.2, maxHeight: logoHeight, alignment: .leading)
                } else {
                    titleText(name)
                }
            }
        } else {
            titleText(name)
        }
    }
    private func titleText(_ n: String) -> some View {
        Text(n).font(.system(size: titleSize, weight: .bold)).foregroundStyle(.white).lineLimit(lines)
    }
}

struct BadgeRow: View {
    @ObservedObject var session: PlayerSession
    var compact: Bool
    var body: some View {
        let b = session.isTrailer ? nil : StreamBadges.make(session.engine.facts(), fileName: session.link?.fileName, sizeBytes: session.link?.sizeBytes ?? 0)
        if let b, !b.badges.isEmpty {
            // The HDR badge lights up (rainbow edge, a small glow glyph) only while the screen really shows HDR.
            let lit = session.engine.hdrOnScreen
            HStack(spacing: compact ? 6 : 8) {
                ForEach(b.badges, id: \.self) { badge in
                    if lit && StreamBadges.hdrNames.contains(badge.text) {
                        HDRBadge(text: badge.text, compact: compact)
                    } else {
                        Text(badge.text)
                            .font(.system(size: compact ? 11.5 : 13, weight: .semibold)).tracking(0.3)
                            .foregroundStyle(badge.warn ? Palette.amberText : .white)
                            .padding(.horizontal, compact ? 9 : 11).padding(.vertical, compact ? 4 : 5)
                            .background(Capsule().fill(badge.warn ? Palette.amber.opacity(0.16) : Color(red: 14 / 255, green: 14 / 255, blue: 16 / 255).opacity(150 / 255)))
                            .overlay(Capsule().strokeBorder(badge.warn ? Palette.amber.opacity(0.6) : Palette.hairline, lineWidth: 1))
                    }
                }
                if !compact, let size = b.size {
                    Text(size).font(.system(size: 13, weight: .medium)).monospacedDigit().foregroundStyle(.white.opacity(0.62)).padding(.leading, 6)
                }
            }
        }
    }
}

/// "Dolby Vision" / "HDR10" / "HDR10+" / "HLG" while the screen shows it in HDR: the same capsule as the other
/// badges, with a small sun glyph (8 pt gap) and a 1 pt edge in the rainbow (the brand's only colour) plus its glow.
struct HDRBadge: View {
    let text: String
    let compact: Bool
    var body: some View {
        let size: CGFloat = compact ? 11.5 : 13
        HStack(spacing: 8) {
            Image(systemName: "sun.max.fill").font(.system(size: size - 2, weight: .semibold))
            Text(text).font(.system(size: size, weight: .semibold)).tracking(0.3)
        }
        .foregroundStyle(.white)
        .lineLimit(1).fixedSize()
        .padding(.horizontal, compact ? 10 : 12).padding(.vertical, compact ? 4 : 5)
        .background(Capsule().fill(Color(red: 14 / 255, green: 14 / 255, blue: 16 / 255).opacity(170 / 255)))
        .overlay(
            Capsule().strokeBorder(AngularGradient(colors: Rainbow.colors + [Rainbow.colors[0]], center: .center), lineWidth: 1)
                .shadow(color: Rainbow.fadeColor().opacity(0.45), radius: 5)
        )
        .accessibilityLabel("\(text), Shown in HDR")
    }
}

// MARK: - Small pieces

struct SeekBubble: View {
    let text: String
    var body: some View {
        Text(text).font(.system(size: 18, weight: .semibold)).monospacedDigit().foregroundStyle(.white)
            .frame(width: 88, height: 88)
            // No dark bubble (owner): the text floats on the picture with a soft shadow.
            .shadow(color: .black.opacity(0.7), radius: 8)
            .transition(.scale(scale: 0.86).combined(with: .opacity))
    }
}

struct CenterFlash: View {
    let play: Bool
    @State private var on = false
    var body: some View {
        Image(systemName: play ? "play.fill" : "pause.fill").font(.system(size: 40, weight: .semibold)).foregroundStyle(.white)
            .frame(width: 108, height: 108)
            .shadow(color: .black.opacity(0.7), radius: 10)
            .scaleEffect(on ? 1 : 0.82).opacity(on ? 1 : 0)
            .onAppear { withAnimation(.easeOut(duration: 0.15)) { on = true } }
    }
}

/// The calm buffering ring: smoked disc, faint track, a rainbow arc turning once every 1.1 s.
struct BufferRing: View {
    var body: some View {
        TimelineView(.animation) { ctx in
            let a = ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.1) / 1.1 * 360
            ZStack {
                Circle().fill(Color(red: 10 / 255, green: 10 / 255, blue: 12 / 255).opacity(120 / 255))
                Circle().stroke(.white.opacity(0.14), lineWidth: 3).padding(17)
                Circle().trim(from: 0, to: 100 / 360)
                    .stroke(AngularGradient(colors: Rainbow.colors, center: .center), style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .padding(17).rotationEffect(.degrees(a - 90))
            }
            .frame(width: 64, height: 64)
        }
    }
}

/// "Skip Intro" / "Skip Recap": one compact pill of dark glass with a skip glyph; pressed it turns pearl.
struct SkipPill: View {
    let label: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Text(label)
                Image(systemName: "forward.end.fill").font(.system(size: 13, weight: .semibold))
            }
        }
        .buttonStyle(PillStyle())
        .transition(.move(edge: .trailing).combined(with: .opacity))
    }
}

/// The curtain's and post-play's pills: dark glass at rest, pearl with ink and a rainbow border pressed.
struct PillStyle: ButtonStyle {
    var primary = false
    func makeBody(configuration: Configuration) -> some View {
        let lit = configuration.isPressed || primary
        configuration.label
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(lit ? Palette.ink : .white)
            .padding(.horizontal, 24).frame(minHeight: 46)
            .background(Capsule().fill(lit ? AnyShapeStyle(Palette.pearl) : AnyShapeStyle(Color(white: 0.11).opacity(0.80))))
            .overlay(Capsule().strokeBorder(.white.opacity(lit ? 0 : 0.16), lineWidth: 1))
            .rainbowBorder(configuration.isPressed, radius: 23, width: 2)
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(.easeOut(duration: 0.16), value: configuration.isPressed)
    }
}

struct ToastView: View {
    let toast: PlayerSession.Toast
    var body: some View {
        Text(toast.text).font(.system(size: 15, weight: .medium)).foregroundStyle(toast.tint)
            .padding(.horizontal, 18).padding(.vertical, 10)
            .background(Capsule().fill(Color(white: 0.06).opacity(0.92)))
            .overlay(Capsule().strokeBorder(Palette.hairline, lineWidth: 1))
            .transition(.move(edge: .top).combined(with: .opacity))
            .id(toast.id)
    }
}

/// The Pause Screen ("You're Watching"): after 8 s paused the picture dims and this rises in at the lower left.
struct PauseCard: View {
    @ObservedObject var session: PlayerSession
    var body: some View {
        let r = session.request
        VStack(alignment: .leading, spacing: 10) {
            Text("You're Watching").font(.system(size: 13, weight: .bold))
                .foregroundStyle(.white.opacity(0.62))
            TitleBlock(session: session, logoHeight: 64, titleSize: 32, lines: 2)
            if let line = pauseLine(r), !line.isEmpty {
                Text(line).font(.system(size: 17, weight: .medium)).foregroundStyle(.white.opacity(0.84)).lineLimit(1)
            }
            BadgeRow(session: session, compact: false)
            let synopsis = session.episodeInfo?.overview.nilIfEmpty ?? session.details?.title.overview.nilIfEmpty
            if let synopsis {
                Text(synopsis).font(.system(size: 15)).foregroundStyle(.white.opacity(0.74)).lineLimit(3).lineSpacing(3)
            }
            if let left = Labels.timeLeft(position: session.state.position, duration: session.state.duration) {
                Text(left).font(.system(size: 14, weight: .medium)).monospacedDigit().foregroundStyle(.white.opacity(0.62)).padding(.top, 4)
            }
        }
        .frame(maxWidth: 460, alignment: .leading)
        .padding(.leading, 32).padding(.bottom, 36)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
        .allowsHitTesting(false)
    }

    private func pauseLine(_ r: PlayRequest?) -> String? {
        guard let r else { return nil }
        if let s = r.season, let e = r.episode {
            return ["S\(s) · E\(e)", r.episodeName ?? session.episodeInfo?.name].compactMap { $0 }.joined(separator: "  ")
        }
        let d = session.details
        return [r.title.year.nilIfEmpty, Labels.runtime(d?.runtime ?? 0), d?.genres.prefix(2).joined(separator: ", ").nilIfEmpty]
            .compactMap { $0 }.joined(separator: "  ·  ")
    }
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
