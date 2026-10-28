import SwiftUI
import AVFoundation
import UIKit
import DreamCore
import DreamUI
import DreamPlayer

/// A title's trailer playing behind the title page's hero, where its backdrop was (HeroTrailer.kt, Netflix style).
///
/// - Starts 4 s after the page rests with its hero on screen (the stream is looked up after half a second, so it's
///   ready on time), only with Dream in front, the page showing and Dream Player neither up nor floating.
/// - The backdrop stays under it until real picture is on screen, then the video dissolves in over 900 ms; at the
///   end (or the moment the hero scrolls away, the page goes, the app leaves the front, Play is pressed) the still
///   fades back over it and the player is released. No loops: after a trailer ends, the next one waits until the
///   page is opened again.
/// - Sound follows "Background Trailers" (per profile): On, Muted (the phone's default, so a page never starts
///   talking out loud), On with Sound (volume 0.8, faded up over 1.8 s), or Off. A small speaker button on the hero
///   turns the sound on or off while it plays and keeps that choice.
/// - Streams come only from Dream Player's public `Trailers.resolver` (the service behind it is never named).
/// - The Trailer button continues the same video full screen from where the hero left it (within 5 minutes).
struct HeroTrailerLayer: View {
    /// The page's trailer state (held by the page, shared with `HeroTrailerSound`).
    let trailer: HeroTrailerModel
    let title: Title
    let key: String?
    var width: CGFloat
    var height: CGFloat
    @Environment(\.scenePhase) private var phase
    /// The shell keeps every tab's pages alive (hidden): another tab showing counts as the page being away.
    @Environment(ShellModel.self) private var shell: ShellModel?
    @State private var myTab: ShellModel.Tab?

    var body: some View {
        HeroVideoView(player: trailer.player)
            .frame(width: width, height: height)
            .clipped()
            .opacity(trailer.revealed ? 1 : 0)
            .animation(.easeInOut(duration: trailer.revealed ? 0.9 : 0.32), value: trailer.revealed)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .onGeometryChange(for: Bool.self) { proxy in
                // Resting on the hero: at least ~60% of it on screen.
                let f = proxy.frame(in: .global)
                return f.maxY > f.height * 0.6
            } action: { onScreen in
                trailer.set(onScreen: onScreen)
            }
            .onAppear {
                if myTab == nil { myTab = shell?.tab }
                trailer.set(title: title, key: key)
                trailer.set(pageShowing: true)
            }
            .onChange(of: shell?.tab) { _, t in trailer.set(pageShowing: myTab == nil || t == myTab) }
            .onDisappear { trailer.set(pageShowing: false) }
            .onChange(of: key) { _, k in trailer.set(title: title, key: k) }
            .onChange(of: phase) { _, p in trailer.set(active: p == .active) }
            .onReceive(NotificationCenter.default.publisher(for: .dreamHeroTrailerStop)) { _ in trailer.stopNow() }
    }

    /// The Trailer button: the same video full screen, from where the hero left it (HeroTrailer.handoff), else from
    /// the start through the app's trailer hook.
    @MainActor static func openFull(_ title: Title, key: String, fallback: (Title, String) -> Void) {
        let spot = HeroTrailerModel.handoff(title: title, key: key)
        NotificationCenter.default.post(name: .dreamHeroTrailerStop, object: nil)
        if let spot { DreamPlayerPresenter.shared.playTrailer(of: title, keys: [key], start: spot) } else { fallback(title, key) }
    }

    /// Something is about to play (Play, Start Over, an episode): the hero's trailer stops at once.
    @MainActor static func stopAll() { NotificationCenter.default.post(name: .dreamHeroTrailerStop, object: nil) }
}

/// The speaker button on the hero while its trailer plays: sound on or off (kept for this profile).
struct HeroTrailerSound: View {
    let trailer: HeroTrailerModel
    var body: some View {
        if trailer.revealed {
            Button { trailer.toggleSound() } label: {
                Image(systemName: trailer.sound ? "speaker.wave.2.fill" : "speaker.slash.fill")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Palette.text)
                    .frame(width: 34, height: 34)
                    .background(.ultraThinMaterial, in: Circle())
                    .overlay(Circle().strokeBorder(Color.white.opacity(0.18), lineWidth: 1))
                    .frame(width: 44, height: 44)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .transition(.opacity)
            .accessibilityLabel(trailer.sound ? "Turn Trailer Sound Off" : "Turn Trailer Sound On")
        }
    }
}

extension Notification.Name {
    static let dreamHeroTrailerStop = Notification.Name("dream.heroTrailer.stop")
}

/// "Background Trailers" for the current profile (HeroTrailer.mode).
enum BackgroundTrailers: Int, Codable, CaseIterable {
    case sound = 0, muted = 1, off = 2

    var label: String {
        switch self { case .sound: "On with Sound"; case .muted: "On, Muted"; case .off: "Off" }
    }

    static var mode: BackgroundTrailers {
        get { DreamStore.shared.get(BackgroundTrailers.self, "heroTrailer.mode", perProfile: true) ?? .muted }
        set { DreamStore.shared.set(newValue, "heroTrailer.mode", perProfile: true) }
    }

    /// The settings row: On, Muted -> On with Sound -> Off -> On, Muted.
    static func cycle() {
        mode = switch mode { case .muted: .sound; case .sound: .off; case .off: .muted }
    }
}

/// The trailer's state machine (HeroTrailer.kt phases: idle, armed, resolving, starting, playing, stopping).
@MainActor @Observable
final class HeroTrailerModel {
    private(set) var player: AVPlayer?
    /// Real picture is on screen: the still dissolves away.
    private(set) var revealed = false
    private(set) var sound = BackgroundTrailers.mode == .sound
    /// The trailer has the screen (HeroTrailer.kt onImmersive): the page's words, buttons and scrims fade out.
    /// A tap wakes the UI back over the still-playing trailer; after 4 s with no touch it fades out again.
    private(set) var immersive = false
    @ObservationIgnored private var rehide: Task<Void, Never>?
    static let uiIdleSeconds = 4.0

    /// A touch while the trailer has the screen: the UI fades back in, the trailer keeps playing.
    func wake() {
        guard revealed else { return }
        immersive = false
        rehide?.cancel()
        rehide = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.uiIdleSeconds))
            guard let self, !Task.isCancelled, self.revealed else { return }
            self.immersive = true
        }
    }

    @ObservationIgnored private var title: Title?
    @ObservationIgnored private var key: String?
    @ObservationIgnored private var onScreen = true
    @ObservationIgnored private var pageShowing = false
    @ObservationIgnored private var active = true
    /// A trailer ended (or failed) by itself on this page: no other one until the page opens again.
    @ObservationIgnored private var waitForUser = false
    @ObservationIgnored private var arm: Task<Void, Never>?
    @ObservationIgnored private var watch: Task<Void, Never>?
    @ObservationIgnored private var stream: TrailerStream?
    @ObservationIgnored private var audio: AVPlayer?
    @ObservationIgnored private var observers: [NSObjectProtocol] = []
    @ObservationIgnored private var readyWatch: NSKeyValueObservation?
    @ObservationIgnored private var gen = 0

    /// The TV rests 4 s; the phone 1 s (owner: start sooner), the stream looked up at once and buffered meanwhile.
    static let restSeconds = 1.0
    static let lookupSeconds = 0.0
    /// Streams found this session, by trailer key: a replay or a return to the page starts without a new lookup.
    @MainActor static var found: [String: TrailerStream] = [:]
    static let startTimeout = 15.0
    static let volume: Float = 0.8

    // ---- handoff (where the last trailer stopped, for the Trailer button) ----

    private struct Spot { var titleKey: String; var key: String; var seconds: Double; var at: Date }
    private static var last: Spot?

    static func handoff(title: Title, key: String) -> Double? {
        guard let s = last, s.titleKey == title.key, s.key == key, Date().timeIntervalSince(s.at) < 300, s.seconds > 1 else { return nil }
        return s.seconds
    }

    // ---- what the page reports ----

    func set(title: Title, key: String?) {
        if title.key != self.title?.key || key != self.key {
            self.title = title
            self.key = key
            waitForUser = false
            stop()
        }
        evaluate()
    }

    func set(onScreen: Bool) {
        guard onScreen != self.onScreen else { return }
        self.onScreen = onScreen
        // Scrolling back up to the hero counts as resting again.
        if onScreen { waitForUser = false }
        evaluate()
    }

    func set(pageShowing: Bool) {
        self.pageShowing = pageShowing
        evaluate()
    }

    func set(active: Bool) {
        self.active = active
        evaluate()
    }

    func stopNow() {
        waitForUser = true
        stop()
    }

    func toggleSound() {
        sound.toggle()
        BackgroundTrailers.mode = sound ? .sound : .muted
        applySound(fade: sound)
    }

    // ---- when ----

    private var wanted: Bool {
        title != nil && key != nil && onScreen && pageShowing && active && !waitForUser && BackgroundTrailers.mode != .off
    }

    private var blocked: Bool { DreamPlayerPresenter.shared.isShowing || DreamPlayerPresenter.shared.isFloating }

    private func evaluate() {
        guard wanted else { stop(); return }
        guard arm == nil, player == nil, let key else { return }
        gen += 1
        let g = gen
        sound = BackgroundTrailers.mode == .sound
        arm = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.lookupSeconds))
            guard !Task.isCancelled else { return }
            // Looked up early so the trailer can start right on time.
            let armedAt = Date().addingTimeInterval(-Self.lookupSeconds)
            var found = await MainActor.run { Self.found[key] }
            if found == nil { found = try? await Trailers.resolver.stream(forKey: key) }
            if let f = found { await MainActor.run { Self.found[key] = f } }
            guard let self, !Task.isCancelled, g == self.gen else { return }
            self.arm = nil
            guard let found else {
                Log.app.notice("Hero trailer: no stream")
                self.waitForUser = true
                return
            }
            // The player opens and buffers now (paused, hidden); it starts the moment the rest is over, so the
            // picture is there on time instead of a buffering wait after it.
            self.begin(found, gen: g, playAt: armedAt.addingTimeInterval(Self.restSeconds))
        }
    }

    private func begin(_ s: TrailerStream, gen g: Int, playAt: Date = Date()) {
        guard wanted, g == gen else { return }
        // Dream Player up (it counts a moment longer after closing): look again shortly.
        if blocked { arm = Task { [weak self] in try? await Task.sleep(for: .seconds(1)); self?.arm = nil; self?.evaluate() }; return }
        stream = s
        let item = AVPlayerItem(url: s.video)
        item.preferredForwardBufferDuration = 6
        let p = AVPlayer(playerItem: item)
        p.isMuted = true
        p.volume = 0
        p.allowsExternalPlayback = false
        p.preventsDisplaySleepDuringVideoPlayback = true
        player = p
        let started = Date()
        readyWatch = item.observe(\.status) { [weak self] it, _ in
            if it.status == .failed { Task { @MainActor in self?.failed("could not open") } }
        }
        observers.append(NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.ended() }
        })
        setSession(sound: false)
        let wait = playAt.timeIntervalSinceNow
        if wait > 0.05 {
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(wait))
                guard let self, g == self.gen, self.player === p else { return }
                p.play()
            }
        } else {
            p.play()
        }
        // Watch for the first frame, the player coming up, and a start that never happens.
        watch = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(250))
                guard let self, g == self.gen else { return }
                if self.blocked { self.stopNow(); return }
                if !self.revealed {
                    if p.currentItem?.status == .readyToPlay, p.currentTime().seconds > 0.15 {
                        self.revealed = true
                        // No immersive fade on the phone (owner): the page stays up over the trailer.
                        self.applySound(fade: true)
                        Log.app.notice("Hero trailer: playing after \(String(format: "%.1f", Date().timeIntervalSince(started)), privacy: .public) s")
                    } else if Date().timeIntervalSince(started) > Self.startTimeout {
                        self.failed("no picture in time")
                        return
                    }
                } else if let t = self.title, let k = self.key {
                    Self.last = Spot(titleKey: t.key, key: k, seconds: p.currentTime().seconds, at: Date())
                }
            }
        }
    }

    private func failed(_ why: String) {
        Log.app.notice("Hero trailer: \(why, privacy: .public)")
        waitForUser = true
        stop()
    }

    /// The trailer ran to its end: the still fades back, the same rest passes, and it plays again from the start
    /// (owner, 2026-10-28), for as long as the viewer stays on the page.
    private func ended() {
        stop()
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(400)) // the stop's fade frees the player
            guard let self else { return }
            self.evaluate()
        }
    }

    /// The still fades back over the video, then the players go.
    private func stop() {
        arm?.cancel(); arm = nil
        watch?.cancel(); watch = nil
        gen += 1
        guard player != nil else { return }
        if let p = player, let t = title, let k = key, revealed, p.currentTime().seconds > 1 {
            Self.last = Spot(titleKey: t.key, key: k, seconds: p.currentTime().seconds, at: Date())
        }
        revealed = false
        immersive = false
        rehide?.cancel(); rehide = nil
        let p = player, a = audio
        p?.volume = 0; a?.volume = 0
        readyWatch = nil
        observers.forEach(NotificationCenter.default.removeObserver)
        observers = []
        audio = nil
        stream = nil
        // The fade-out runs over the still, then the decoder is released.
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(340))
            p?.pause(); a?.pause()
            p?.replaceCurrentItem(with: nil); a?.replaceCurrentItem(with: nil)
            if let self, self.player === p { self.player = nil }
        }
    }

    // ---- sound ----

    /// Sound on: the separate audio stream (or the muxed one's own track) joins at the video's spot and fades up.
    private func applySound(fade: Bool) {
        guard let p = player else { return }
        if !sound {
            audio?.pause()
            p.isMuted = true
            setSession(sound: false)
            return
        }
        setSession(sound: true)
        if let url = stream?.audio {
            let a = audio ?? AVPlayer(url: url)
            audio = a
            a.volume = fade ? 0 : Self.volume
            a.play()
            a.seek(to: p.currentTime(), toleranceBefore: .zero, toleranceAfter: .zero)
            if fade { ramp(a) }
        } else {
            p.isMuted = false
            p.volume = fade ? 0 : Self.volume
            if fade { ramp(p) }
        }
    }

    private func ramp(_ target: AVPlayer) {
        let g = gen
        Task { [weak self] in
            for i in 1...18 {
                try? await Task.sleep(for: .milliseconds(100))
                guard let self, g == self.gen, self.sound else { return }
                target.volume = Self.volume * Float(i) / 18
            }
        }
    }

    /// Muted: the ambient session (the viewer's music keeps playing, the silent switch is respected). Sound: playback.
    private func setSession(sound: Bool) {
        guard !blocked else { return }
        let a = AVAudioSession.sharedInstance()
        if sound { try? a.setCategory(.playback, mode: .moviePlayback) } else { try? a.setCategory(.ambient, mode: .default, options: [.mixWithOthers]) }
        try? a.setActive(true)
    }
}

/// An AVPlayerLayer filling the hero like the backdrop (aspect fill, centered).
struct HeroVideoView: UIViewRepresentable {
    let player: AVPlayer?

    final class LayerView: UIView {
        override static var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    func makeUIView(context: Context) -> LayerView {
        let v = LayerView()
        v.backgroundColor = .clear
        v.isUserInteractionEnabled = false
        v.playerLayer.videoGravity = .resizeAspectFill
        v.playerLayer.player = player
        return v
    }

    func updateUIView(_ v: LayerView, context: Context) {
        if v.playerLayer.player !== player { v.playerLayer.player = player }
    }
}
