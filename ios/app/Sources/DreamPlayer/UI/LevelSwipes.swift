import SwiftUI
import UIKit
import AVFoundation
import MediaPlayer
import DreamCore

// Brightness / volume swipes in Dream Player (VLC / Infuse style; owner-picked 2026-10-28).
//
// Swipe up or down on the LEFT third of the picture for the screen's brightness, the RIGHT third for the volume.
// The middle third belongs to the mini player's swipe-down (its own worker); sideways drags anywhere stay the scrub.
// A drag is only claimed once it has moved 14 pt and is clearly vertical (1.6x more up/down than sideways); a drag
// that goes sideways first is left to the scrub, and while a level swipe runs the scrub stays out of it
// (`LevelSwipeStatus.swiping`). Touches that start in the top bar, the bottom controls, the screen's edges (system
// gestures) or the middle third are never taken. Off while a sheet, X-Ray, the pause screen, post-play or the curtain
// is up, and when the player isn't full screen (mini player / Picture in Picture).
//
// Brightness: the screen's own brightness. The viewer's level is put back when the player closes, steps aside
// (Picture in Picture, mini player) or the app goes to the background; Dream's level comes back on return.
// Volume: the system volume, through a hidden MPVolumeView's slider, so the side buttons and Control Center stay in
// step. That view also keeps iOS's own volume picture away while the player is full screen; the side buttons then show
// Dream's bar instead. Settings > Playback > "Swipe for Brightness and Volume" (default on) turns it all off.

/// What PlayerScreen needs to know: a level swipe is running (the scrub stays out), and a bar is on screen (the
/// controls step aside so nothing touches it).
struct LevelSwipeStatus: Equatable {
    var swiping = false
    var shown = false
}

public extension PlayerPrefs {
    /// Swipe for Brightness and Volume (default on), per profile like the other player choices.
    static var levelSwipes: Bool {
        get { DreamStore.shared.get(Bool.self, "player.levelSwipes", perProfile: true) ?? true }
        set { DreamStore.shared.set(newValue, "player.levelSwipes", perProfile: true) }
    }
}

extension View {
    /// Adds the level swipes to the player's gesture layer (alongside its taps, scrub drag and pinch).
    /// `enabled` false (a sheet, X-Ray...) only stops the swipes; `minimized` true (the mini player) also hands the
    /// viewer's brightness back until it's false again.
    func levelSwipes(enabled: Bool, minimized: Bool = false, controlsUp: Bool, scrubbing: Bool,
                     status: Binding<LevelSwipeStatus>) -> some View {
        modifier(LevelSwipes(enabled: enabled, minimized: minimized, controlsUp: controlsUp, scrubbing: scrubbing, status: status))
    }
}

struct LevelSwipes: ViewModifier {
    let enabled: Bool
    let minimized: Bool
    let controlsUp: Bool
    let scrubbing: Bool
    @Binding var status: LevelSwipeStatus

    @StateObject private var levels = LevelControl()
    @State private var size: CGSize = .zero
    @State private var drag: Drag = .idle
    @GestureState private var touching = false

    private enum Drag: Equatable {
        case idle
        /// Started in a side third; not yet clear which way it goes.
        case pending(LevelKind)
        /// Not ours (sideways, a dead zone, or off).
        case rejected
        /// Ours: the level when it was claimed and the drag's height at that moment (so nothing jumps).
        case active(LevelKind, base: Double, from: CGFloat)
    }

    /// Movement before a drag is claimed, and how much more vertical than sideways it must be.
    static let claimDistance: CGFloat = 14
    static let verticalRatio: CGFloat = 1.6
    /// The screen's edges (system gestures).
    static let edge: CGFloat = 18

    /// Full screen landscape player; a smaller frame is the mini player (or a transition), where swipes are off.
    private var fullScreen: Bool { !minimized && size.width >= 480 && size.height >= 260 }
    private var on: Bool { levels.pref && fullScreen }
    private var live: Bool { on && enabled }

    func body(content: Content) -> some View {
        content
            .simultaneousGesture(
                DragGesture(minimumDistance: 10)
                    .updating($touching) { _, t, _ in t = true }
                    .onChanged { changed($0) }
                    .onEnded { _ in finish() }
            )
            .overlay { bars.allowsHitTesting(false) }
            .background { SystemVolumeHost(volume: levels.volume, suppressHUD: live).frame(width: 1, height: 1).allowsHitTesting(false) }
            .onGeometryChange(for: CGSize.self) { $0.size } action: { size = $0 }
            .onAppear { levels.appear(); levels.fullScreen(fullScreen) }
            .onDisappear { finish(); levels.disappear() }
            .onChange(of: fullScreen) { _, f in levels.fullScreen(f) }
            .onChange(of: live) { _, l in levels.hardwareBar = l; if !l { finish(); levels.hideNow() } }
            .onChange(of: levels.shown) { _, k in status.shown = k != nil }
            // A drag the system took over (no onEnded): end it all the same.
            .onChange(of: touching) { _, t in if !t { finish() } }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in
                finish(); levels.background()
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
                levels.foreground()
            }
            .onReceive(NotificationCenter.default.publisher(for: UIScreen.brightnessDidChangeNotification)) { _ in
                levels.brightnessChangedOutside()
            }
            .task { levels.hardwareBar = live }
    }

    private var bars: some View {
        HStack(spacing: 0) {
            if levels.shown == .brightness { LevelBar(kind: .brightness, level: levels.level).transition(.opacity) }
            Spacer(minLength: 0)
            if levels.shown == .volume { LevelBar(kind: .volume, level: levels.level).transition(.opacity) }
        }
        .padding(.horizontal, 28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .animation(.easeOut(duration: levels.shown == nil ? 0.35 : 0.18), value: levels.shown)
    }

    // MARK: The drag

    private func changed(_ v: DragGesture.Value) {
        switch drag {
        case .rejected:
            return
        case .idle:
            guard let kind = side(of: v.startLocation) else { drag = .rejected; return }
            drag = .pending(kind)
            decide(kind, v)
        case .pending(let kind):
            decide(kind, v)
        case .active(let kind, let base, let from):
            // A full bar is about three quarters of the picture's height.
            let span = max(200, size.height * 0.75)
            levels.set(kind, base - Double((v.translation.height - from) / span))
        }
    }

    private func decide(_ kind: LevelKind, _ v: DragGesture.Value) {
        guard live, !scrubbing else { drag = .rejected; return }
        let dx = abs(v.translation.width), dy = abs(v.translation.height)
        if dy >= Self.claimDistance && dy > dx * Self.verticalRatio {
            drag = .active(kind, base: levels.current(kind), from: v.translation.height)
            status.swiping = true
            levels.begin(kind)
        } else if dx >= Self.claimDistance && dx >= dy {
            drag = .rejected
        }
    }

    private func finish() {
        if case .active = drag { levels.end() }
        drag = .idle
        if status.swiping { status.swiping = false }
    }

    /// Which level a touch starting here may change, or nil (middle third, top bar, bottom controls, screen edges).
    private func side(of p: CGPoint) -> LevelKind? {
        let w = size.width, h = size.height
        guard w > 0, h > 0 else { return nil }
        // With the controls up the top bar (title, badges, clock, buttons) and the bottom bar (timeline, times,
        // buttons) are out; with them hidden, just the system's top and bottom edges.
        let top = controlsUp ? min(150, h * 0.38) : min(48, h * 0.13)
        let bottom = controlsUp ? min(156, h * 0.40) : min(54, h * 0.14)
        guard p.y > top, p.y < h - bottom, p.x > Self.edge, p.x < w - Self.edge else { return nil }
        if p.x < w / 3 { return .brightness }
        if p.x > w * 2 / 3 { return .volume }
        return nil
    }
}

// MARK: - Levels

/// The two levels and the bar: brightness held only while the player is full screen and the app in front, the
/// system volume, and the 1 s fade after the finger lifts (or a side button press).
@MainActor
final class LevelControl: ObservableObject {
    @Published private(set) var shown: LevelKind?
    @Published private(set) var level: Double = 0

    let volume = SystemVolume()
    let pref = PlayerPrefs.levelSwipes
    /// Side button presses show Dream's bar (iOS's own is kept away then).
    var hardwareBar = false

    private var swiping: LevelKind?
    private var hideTask: Task<Void, Never>?
    private var volumeWatch: NSKeyValueObservation?
    private var lastVolumeSet: Float = -1
    /// When Dream last set the volume: the system's late reports of a swipe's own steps aren't side button presses.
    private var lastVolumeSetAt = Date.distantPast

    /// The viewer's own brightness, put back whenever Dream steps away; nil until Dream first changes it.
    private var viewerBrightness: CGFloat?
    /// Dream's brightness for this player (kept while it steps aside, so it comes back on return).
    private var chosenBrightness: CGFloat?
    /// Dream's level is on the screen right now.
    private var holding = false
    private var visible = false
    private var isFullScreen = true
    private var inBackground = false

    func current(_ k: LevelKind) -> Double {
        switch k {
        case .brightness: return Double(Self.screen?.brightness ?? 0.5)
        case .volume: return Double(volume.level)
        }
    }

    func begin(_ k: LevelKind) {
        hideTask?.cancel()
        swiping = k
        level = current(k)
        shown = k
    }

    func set(_ k: LevelKind, _ value: Double) {
        let v = min(max(value, 0), 1)
        // A light tick when the bar reaches either end.
        if (v == 0 || v == 1) && level != v { haptic(.light) }
        level = v
        switch k {
        case .brightness:
            guard let screen = Self.screen else { return }
            if viewerBrightness == nil { viewerBrightness = screen.brightness }
            chosenBrightness = CGFloat(v)
            holding = true
            screen.brightness = CGFloat(v)
        case .volume:
            lastVolumeSet = Float(v)
            lastVolumeSetAt = Date()
            volume.set(Float(v))
        }
    }

    func end() {
        swiping = nil
        scheduleHide()
    }

    func hideNow() {
        hideTask?.cancel()
        swiping = nil
        shown = nil
    }

    private func scheduleHide() {
        hideTask?.cancel()
        hideTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled, let self, self.swiping == nil else { return }
            self.shown = nil
        }
    }

    // MARK: Brightness comes and goes with the player

    func appear() {
        visible = true
        resume()
        watchVolume(true)
    }

    func disappear() {
        visible = false
        release()
        watchVolume(false)
        hideNow()
    }

    func fullScreen(_ f: Bool) {
        isFullScreen = f
        if f { resume() } else { release(); hideNow() }
    }

    func background() { inBackground = true; release() }

    func foreground() { inBackground = false; resume() }

    /// Control Center moved the brightness while Dream held it: that's the viewer's choice for the player now.
    func brightnessChangedOutside() {
        guard holding, swiping != .brightness, let b = Self.screen?.brightness, let c = chosenBrightness,
              abs(b - c) > 0.02 else { return }
        chosenBrightness = b
    }

    /// The viewer's level back on the screen (Dream's is kept for the return).
    private func release() {
        guard holding else { return }
        holding = false
        if let v = viewerBrightness { Self.screen?.brightness = v }
    }

    /// Dream's level again, after reading the viewer's (it may have changed while Dream was away).
    private func resume() {
        guard !holding, visible, isFullScreen, !inBackground, let c = chosenBrightness, let screen = Self.screen else { return }
        viewerBrightness = screen.brightness
        holding = true
        screen.brightness = c
    }

    // MARK: Volume from the side buttons

    private func watchVolume(_ on: Bool) {
        volumeWatch?.invalidate()
        volumeWatch = nil
        guard on, pref else { return }
        volumeWatch = AVAudioSession.sharedInstance().observe(\.outputVolume, options: [.new]) { [weak self] _, change in
            guard let v = change.newValue else { return }
            Task { @MainActor in self?.volumeChanged(v) }
        }
    }

    private func volumeChanged(_ v: Float) {
        // Dream's own swipe already shows it; only the side buttons (or Control Center) bring the bar up.
        guard hardwareBar, swiping == nil, abs(v - lastVolumeSet) > 0.005,
              Date().timeIntervalSince(lastVolumeSetAt) > 0.6 else { return }
        lastVolumeSet = -1
        if shown != .volume && shown != nil { return }
        level = Double(v)
        shown = .volume
        scheduleHide()
    }

    static var screen: UIScreen? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return (scenes.first { $0.activationState == .foregroundActive } ?? scenes.first)?.screen
    }
}

// MARK: - System volume

/// The phone's volume through MPVolumeView's slider (the only way an app may set it), so the side buttons, Control
/// Center and Dream always agree. While its view is in the window and not hidden, iOS doesn't draw its own volume
/// picture; it sits far off screen.
@MainActor
final class SystemVolume {
    let view: MPVolumeView = {
        let v = MPVolumeView(frame: CGRect(x: -4000, y: -4000, width: 120, height: 40))
        v.alpha = 0.01
        v.isUserInteractionEnabled = false
        v.isAccessibilityElement = false
        v.accessibilityElementsHidden = true
        return v
    }()

    var level: Float { AVAudioSession.sharedInstance().outputVolume }

    func set(_ v: Float) {
        guard let slider = Self.slider(in: view) else { return }
        slider.value = v
        slider.sendActions(for: .valueChanged)
    }

    private static func slider(in v: UIView) -> UISlider? {
        for s in v.subviews {
            if let slider = s as? UISlider { return slider }
            if let slider = slider(in: s) { return slider }
        }
        return nil
    }
}

/// Puts the volume view in the player's window (a 1 pt host; the view itself is off screen).
struct SystemVolumeHost: UIViewRepresentable {
    let volume: SystemVolume
    let suppressHUD: Bool

    func makeUIView(context: Context) -> UIView {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
        host.clipsToBounds = false
        host.isUserInteractionEnabled = false
        host.addSubview(volume.view)
        volume.view.isHidden = !suppressHUD
        return host
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        if volume.view.superview !== uiView { uiView.addSubview(volume.view) }
        volume.view.isHidden = !suppressHUD
    }
}
