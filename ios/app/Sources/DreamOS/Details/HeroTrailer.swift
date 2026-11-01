import SwiftUI
import AVFoundation
import UIKit
import DreamCore
import DreamUI
import DreamPlayer

/// A title's trailer playing behind the title page's hero, where its backdrop was (HeroTrailer.kt).
///
/// - Starts after the page rests 1 s with its hero on screen, only with Dream in front, the page showing and Dream
///   Player neither up nor floating. The stream is usually readied ahead (`TrailerAhead`: a card pressed, a hero
///   slide on screen); during the rest the player opens hidden and muted, runs to its first picture and waits there
///   paused, so the trailer fades in the moment the rest ends. A title not readied yet shows as soon as its picture
///   is up.
/// - The backdrop stays under it until real picture is on screen, then the video dissolves in over 900 ms; at the
///   end (or the moment the hero scrolls away, the page goes, the app leaves the front, Play is pressed) the still
///   fades back over it and the player is released. No loops: after a trailer ends, the next one waits until the
///   page is opened again.
/// - The page keeps its UI the whole time (owner, 2026-10-31): the trailer is a background layer where the backdrop
///   was, so the title block and Play stay the page's focal point and keep the scrim that gives them their contrast.
///   Nothing hides itself and nothing has to be woken by a touch.
/// - Sound follows "Background Trailers" (per profile): On, Muted (the phone's default, so a page never starts
///   talking out loud), On with Sound (volume 0.8, faded up over 1.8 s), or Off. A small speaker button on the hero
///   turns the sound on or off while it plays and keeps that choice.
/// - Streams come only from Dream Player's public `Trailers.resolver` (the service behind it is never named).
/// - The Trailer button shows the trailer that is already playing: the live player and the layer it draws in move
///   into Dream Player's full screen as they are, with the sound brought up (no second player, no reload, no
///   re-buffer). With nothing playing it resolves and starts full screen from where the hero left it (5 minutes).
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
        HeroVideoView(layer: trailer.videoLayer)
            .frame(width: width, height: height)
            .clipped()
            // The video's own dissolve over the still (the page's words and buttons never fade).
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

    /// Something is about to play (Play, Start Over, an episode): the hero's trailer stops at once.
    @MainActor static func stopAll() { NotificationCenter.default.post(name: .dreamHeroTrailerStop, object: nil) }
}

/// The speaker button on the hero while its trailer plays: sound on or off (kept for this profile). The glyph and its
/// label both carry the state, so it never reads by color alone.
struct HeroTrailerSound: View {
    let trailer: HeroTrailerModel
    var body: some View {
        if trailer.revealed {
            Button { trailer.toggleSound() } label: {
                Image(systemName: trailer.sound ? "speaker.wave.2.fill" : "speaker.slash.fill")
                    .font(.system(size: 13, weight: .semibold))
            }
            .buttonStyle(HeroSoundStyle())
            .transition(.opacity)
            .accessibilityLabel(trailer.sound ? "Turn Trailer Sound Off" : "Turn Trailer Sound On")
        }
    }
}

/// Dark glass at rest, so the glyph stays readable over a bright trailer frame; pressed, it takes the house pearl,
/// dark ink and rainbow border, which is the one state a touch control has (no hover on a phone).
private struct HeroSoundStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        let p = configuration.isPressed
        configuration.label
            .foregroundStyle(p ? Palette.ink : Palette.text)
            .frame(width: 34, height: 34)
            .background {
                if p { Circle().fill(Palette.pearl) } else { Circle().fill(.ultraThinMaterial) }
            }
            .overlay(Circle().strokeBorder(Color.white.opacity(p ? 0 : 0.18), lineWidth: 1))
            .rainbowBorder(p, radius: 17, width: 2)
            .scaleEffect(p ? 0.94 : 1)
            .animation(Cinema.pressAnimation, value: p)
            // 44 pt of touch around a 34 pt circle (the tap target, not the look).
            .frame(width: 44, height: 44)
            .contentShape(Circle())
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
    /// The layer the trailer draws in. The model owns it so the Trailer button can move it, with its player, into
    /// Dream Player's full screen without the picture ever stopping.
    private(set) var videoLayer: AVPlayerLayer?
    /// Real picture is on screen: the still dissolves away.
    private(set) var revealed = false
    private(set) var sound = BackgroundTrailers.mode == .sound

    @ObservationIgnored private var title: Title?
    @ObservationIgnored private var key: String?
    @ObservationIgnored private var onScreen = true
    /// A trailer just handed back from full screen is left alone until this instant. The player closes by
    /// starting its dismissal and handing the trailer over in the same breath, so at that moment the page is
    /// still behind a landscape modal: `onScreen` and `pageShowing` describe where the hero was under it, not
    /// where it is about to be, and acting on them would stop the trailer that was just given back.
    @ObservationIgnored private var settleUntil: Date?
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
    /// The rest is over: the picture shows the moment it's up.
    @ObservationIgnored private var restOver = false
    /// The hidden player reached its first picture during the rest and waits there paused.
    @ObservationIgnored private var prerolled = false
    /// When the page opened (timing log: page open to the trailer on screen).
    @ObservationIgnored private var pageAt = Date()
    /// For the timing log: the streams were found ahead ("yes"), joined a look-up under way ("joined") or not ("no"),
    /// and whether the player came buffered from a card press.
    @ObservationIgnored private var ahead = "no"
    @ObservationIgnored private var primedPlayer = false
    /// Other look-ahead waits while this trailer is looked up and loads its first picture (TrailerWindow).
    @ObservationIgnored private var window: Int?

    /// The TV and the phone both rest 1 s (owner); the phone's stream is looked up at once (usually readied ahead).
    static let restSeconds = 1.0
    static let startTimeout = 15.0
    static let volume: Float = 0.8

    // ---- handoff (where the last trailer stopped, for the Trailer button) ----

    private struct Spot { var titleKey: String; var key: String; var seconds: Double; var at: Date }
    private static var last: Spot?

    static func handoff(title: Title, key: String) -> Double? {
        guard let s = last, s.titleKey == title.key, s.key == key, Date().timeIntervalSince(s.at) < 300, s.seconds > 1 else { return nil }
        return s.seconds
    }

    // ---- the way back (full screen closing hands the same player back) ----

    /// The hero that handed its trailer to full screen, waiting to be given it back, and what it handed over. Weak:
    /// a page that has gone takes nothing back and the player is released instead.
    private static weak var handedOver: HeroTrailerModel?
    private static var handedOverWhat: (titleKey: String, key: String)?

    /// Registers [m] as the hero to hand the live trailer back to, and installs the hook the player calls on close.
    /// Installed here rather than at launch because there is nothing to hand back until a hand-over has happened.
    private static func expect(_ m: HeroTrailerModel) {
        handedOver = m
        handedOverWhat = m.title.map { ($0.key, m.key ?? "") }
        guard DreamPlayerPresenter.handBackTrailer == nil else { return }
        DreamPlayerPresenter.handBackTrailer = { live in
            MainActor.assumeIsolated {
                guard let m = HeroTrailerModel.handedOver, let what = HeroTrailerModel.handedOverWhat else {
                    Log.app.notice("Hero trailer: nobody waiting for the way back (page gone)")
                    return false
                }
                HeroTrailerModel.handedOver = nil
                HeroTrailerModel.handedOverWhat = nil
                return m.takeBack(live, what)
            }
        }
    }

    /// Full screen closed and the trailer is still live: the same player, layer and sound come back here and carry on
    /// behind the artwork, the way in run backwards (owner, 2026-11-01). Nothing is re-prepared or sought, and the
    /// sound goes back to the viewer's Background Trailers setting, because resting behind a hero is not the
    /// deliberate watch that raised it. False when this page can no longer show it, and the player is released.
    func takeBack(_ live: LiveTrailer, _ what: (titleKey: String, key: String)) -> Bool {
        // Still this page, still the same video, nothing already playing here, and trailers not switched off.
        // Deliberately not `onScreen` / `pageShowing` / `blocked`: the page has not come back from behind the player
        // yet, so those three still describe it as it was under a landscape full screen and are false for every
        // hand-back. The settling window below asks them again once they mean something.
        guard title?.key == what.titleKey, key == what.key, player == nil,
              live.player.currentItem != nil, BackgroundTrailers.mode != .off else {
            Log.app.notice("Hero trailer: refused the way back (page \(self.title?.key ?? "nil") vs \(what.titleKey), video \(self.key ?? "nil") vs \(what.key), playing \(self.player != nil), item \(live.player.currentItem != nil))")
            return false
        }
        // The latch that stopped a second trailer starting while full screen had the player: the player is back, so
        // it clears, and `wanted` is true again. `evaluate` then sees `player != nil` and starts nothing new.
        waitForUser = false
        arm?.cancel(); arm = nil
        watch?.cancel(); watch = nil
        gen += 1
        let g = gen
        player = live.player
        videoLayer = live.layer
        audio = live.audio
        stream = live.stream
        live.layer.videoGravity = .resizeAspectFill
        // It is already past the rest and already showing picture.
        prerolled = true
        restOver = true
        revealed = true
        sound = BackgroundTrailers.mode == .sound
        watchItem(live.player)
        // Back to the setting, never the volume full screen raised it to; muted is the default and stays it.
        applySound(fade: false)
        if live.player.timeControlStatus == .paused { live.player.play() }
        resumeWatching(live.player, gen: g)
        // The closing player lets the audio session go up to 2 s after this (`BackgroundAudio.release`), which would
        // silence the trailer it has just handed over. With sound on, the page takes the session back afterwards.
        if sound {
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(2.4))
                guard let self, g == self.gen, self.sound, self.player != nil else { return }
                self.setSession(sound: true)
            }
        }
        // The page settles, turns back to portrait and reports its geometry again within this; then the ordinary
        // rules decide, and a page that really is gone stops it then, as it stops any other trailer.
        settleUntil = Date().addingTimeInterval(Self.settleSeconds)
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.settleSeconds + 0.1))
            guard let self, g == self.gen else { return }
            self.settleUntil = nil
            self.evaluate()
        }
        Log.app.notice("Hero trailer: back from full screen, still playing")
        return true
    }

    /// The end-of-item and failure watches the page keeps on its own player (set when it opens one, and again when
    /// one comes back from full screen).
    private func watchItem(_ p: AVPlayer) {
        readyWatch = nil
        observers.forEach(NotificationCenter.default.removeObserver)
        observers = []
        guard let item = p.currentItem else { return }
        readyWatch = item.observe(\.status) { [weak self] it, _ in
            if it.status == .failed { Task { @MainActor in self?.failed("could not open") } }
        }
        observers.append(NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.ended() }
        })
    }

    /// The page's own loop over a playing trailer: it keeps the spot for the Trailer button and stops the trailer if
    /// the player comes up. The shortened form of `begin`'s watch, for a trailer that is already on screen.
    private func resumeWatching(_ p: AVPlayer, gen g: Int) {
        watch = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(250))
                guard let self, g == self.gen, self.player === p else { return }
                if self.blocked { self.stopNow(); return }
                if let t = self.title, let k = self.key {
                    Self.last = Spot(titleKey: t.key, key: k, seconds: p.currentTime().seconds, at: Date())
                }
            }
        }
    }

    /// The Trailer button (owner, 2026-10-31): the trailer playing behind the hero becomes the full screen itself.
    /// The live player, its layer and its sound are handed over as they are, so there is no drop-out, no black frame
    /// and no buffering wait. Nothing playing here (trailers Off, still being looked up, stopped, or a different
    /// video): the full screen resolves and starts as before, from where the hero left it within 5 minutes.
    ///
    /// Closing full screen hands the same player **back** here and it carries on behind the artwork (`takeBack`), so
    /// the picture continues in both directions (owner, 2026-11-01). Only when there is nothing live to come back
    /// does the page keep its still and wait for the viewer.
    func openFull(_ title: Title, key: String, fallback: (Title, String) -> Void) {
        let live = handOverLive(title: title, key: key)
        Log.app.notice("Hero trailer: Trailer pressed, live hand-over \(live != nil)")
        // Any other page kept alive behind this one stops too (this page has already let go of its player).
        NotificationCenter.default.post(name: .dreamHeroTrailerStop, object: nil)
        if let live {
            DreamPlayerPresenter.shared.playLive(live, of: title, keys: [key])
            return
        }
        if let spot = Self.handoff(title: title, key: key) {
            DreamPlayerPresenter.shared.playTrailer(of: title, keys: [key], start: spot)
        } else {
            fallback(title, key)
        }
    }

    /// The live trailer for this title and video when one is really playing here (its picture on screen): the page
    /// lets go of the player, its layer and its sound without pausing or emptying any of them, and asks to have them
    /// back when full screen closes (`takeBack`).
    private func handOverLive(title: Title, key: String) -> LiveTrailer? {
        guard revealed, let p = player, let l = videoLayer, let s = stream,
              let item = p.currentItem, item.status != .failed,
              self.title?.key == title.key, self.key == key else { return nil }
        let live = LiveTrailer(player: p, layer: l, audio: audio, stream: s)
        Self.expect(self)
        // Everything the page ran for it stops; the player itself keeps playing, now Dream Player's.
        arm?.cancel(); arm = nil
        watch?.cancel(); watch = nil
        closeWindow()
        gen += 1
        readyWatch = nil
        observers.forEach(NotificationCenter.default.removeObserver)
        observers = []
        player = nil
        videoLayer = nil
        audio = nil
        stream = nil
        revealed = false
        prerolled = false
        restOver = false
        // Full screen has the video now, so there is no hero spot while it is away, and nothing starts a second
        // trailer here. `takeBack` clears this latch when the same player comes home; the spot starts being kept
        // again from that moment, and only matters once the trailer is genuinely stopped.
        Self.last = nil
        waitForUser = true
        return live
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
        if pageShowing && !self.pageShowing { pageAt = Date() }
        self.pageShowing = pageShowing
        evaluate()
    }

    func set(active: Bool) {
        self.active = active
        evaluate()
    }

    func stopNow() {
        waitForUser = true
        TrailerWindow.closeAll()
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

    /// How long a handed-back trailer is left alone while the page comes back and turns round again.
    private static let settleSeconds: TimeInterval = 1.5

    private func evaluate() {
        // Just handed back: the page's own state is not trustworthy yet (`settleUntil`).
        if let settleUntil, player != nil, Date() < settleUntil { return }
        guard wanted else { stop(); return }
        guard arm == nil, player == nil, let key else { return }
        gen += 1
        let g = gen
        sound = BackgroundTrailers.mode == .sound
        let armedAt = Date()
        ahead = TrailerAhead.readiness(key)
        if window == nil { window = TrailerWindow.begin() }
        arm = Task { [weak self] in
            // Usually readied ahead (a card pressed, a hero slide); else looked up now, joining a look-up under way.
            let found = await TrailerAhead.stream(forKey: key)
            guard let self, !Task.isCancelled, g == self.gen else { return }
            self.arm = nil
            guard let found else {
                Log.app.notice("Hero trailer: no stream")
                self.closeWindow()
                self.waitForUser = true
                return
            }
            // The player opens now, hidden and muted, and runs to its first picture, where it waits paused for the
            // rest to end: the picture is there on time instead of a buffering wait after it.
            self.begin(found, gen: g, playAt: armedAt.addingTimeInterval(Self.restSeconds))
        }
    }

    private func begin(_ s: TrailerStream, gen g: Int, playAt: Date = Date()) {
        guard wanted, g == gen else { return }
        // Dream Player up (it counts a moment longer after closing): look again shortly.
        if blocked { arm = Task { [weak self] in try? await Task.sleep(for: .seconds(1)); self?.arm = nil; self?.evaluate() }; return }
        stream = s
        guard let key else { return }
        // Buffered ahead from the card press when there is one; else a new player on the asset opened ahead.
        let (p, wasPrimed) = TrailerAhead.player(for: key, s)
        guard let item = p.currentItem else { failed("could not open"); return }
        primedPlayer = wasPrimed
        item.preferredForwardBufferDuration = 6
        p.isMuted = true
        p.volume = 0
        p.allowsExternalPlayback = false
        p.preventsDisplaySleepDuringVideoPlayback = true
        // Hidden, it starts from the first data instead of a long buffer; turned back on once its picture is up.
        p.automaticallyWaitsToMinimizeStalling = false
        player = p
        let l = AVPlayerLayer(player: p)
        l.videoGravity = .resizeAspectFill
        videoLayer = l
        let started = Date()
        watchItem(p)
        setSession(sound: false)
        // Hidden and muted, it plays to its first picture now; the watch below pauses it there until the rest ends.
        prerolled = false
        restOver = playAt.timeIntervalSinceNow <= 0.05
        p.play()
        if !restOver {
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(playAt.timeIntervalSinceNow))
                guard let self, g == self.gen, self.player === p else { return }
                self.restOver = true
                if self.prerolled { p.play(); self.show(started) }
            }
        }
        // Watch for the first picture, the player coming up, and a start that never happens (every 100 ms until the
        // picture is on screen, then every 250 ms).
        watch = Task { [weak self] in
            while !Task.isCancelled {
                let step = self?.revealed == true ? 250 : 100
                try? await Task.sleep(for: .milliseconds(step))
                guard let self, g == self.gen else { return }
                if self.blocked { self.stopNow(); return }
                if !self.revealed {
                    if !self.prerolled, p.currentItem?.status == .readyToPlay, p.currentTime().seconds > 0.15 {
                        if self.restOver {
                            self.show(started)
                        } else {
                            // Picture is up during the rest: it waits here, paused and hidden.
                            p.pause()
                            p.automaticallyWaitsToMinimizeStalling = true
                            self.prerolled = true
                            self.closeWindow()
                            Log.app.notice("Hero trailer: ready hidden \(Self.ms(self.pageAt), privacy: .public) ms after the page opened")
                        }
                    } else if !self.prerolled, Date().timeIntervalSince(started) > Self.startTimeout {
                        self.failed("no picture in time")
                        return
                    }
                } else if let t = self.title, let k = self.key {
                    Self.last = Spot(titleKey: t.key, key: k, seconds: p.currentTime().seconds, at: Date())
                }
            }
        }
    }

    /// On screen: the still dissolves away and the sound (if on) fades up.
    private func show(_ started: Date) {
        guard !revealed else { return }
        revealed = true
        player?.automaticallyWaitsToMinimizeStalling = true
        closeWindow()
        // The page keeps its UI over the trailer (owner): nothing to fade out here.
        applySound(fade: true)
        Log.app.notice("Hero trailer: on screen \(Self.ms(self.pageAt), privacy: .public) ms after the page opened (player open \(Self.ms(started), privacy: .public) ms, readied ahead \(self.ahead, privacy: .public), buffered from the card press \(self.primedPlayer ? "yes" : "no", privacy: .public))")
    }

    private func closeWindow() {
        if let w = window { TrailerWindow.close(w); window = nil }
    }

    private static func ms(_ since: Date) -> Int { Int(Date().timeIntervalSince(since) * 1000) }

    private func failed(_ why: String) {
        Log.app.notice("Hero trailer: \(why, privacy: .public)")
        if let key { TrailerAhead.forget(key) }
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
            self.pageAt = Date() // the replay's timing counts from its own rest
            self.evaluate()
        }
    }

    /// The still fades back over the video, then the players go.
    private func stop() {
        settleUntil = nil
        arm?.cancel(); arm = nil
        watch?.cancel(); watch = nil
        closeWindow()
        gen += 1
        prerolled = false
        restOver = false
        guard player != nil else { return }
        if let p = player, let t = title, let k = key, revealed, p.currentTime().seconds > 1 {
            Self.last = Spot(titleKey: t.key, key: k, seconds: p.currentTime().seconds, at: Date())
        }
        revealed = false
        let p = player, a = audio, l = videoLayer
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
            l?.player = nil
            l?.removeFromSuperlayer()
            if let self, self.player === p { self.player = nil; self.videoLayer = nil }
        }
    }

    // ---- sound ----

    /// Sound on: the separate audio stream (or the muxed one's own track) joins at the video's spot and fades up.
    private func applySound(fade: Bool) {
        guard let p = player else { return }
        if !sound {
            audio?.pause()
            p.isMuted = true
            // The volume goes down too, not just the mute: a player coming back from full screen arrives at the
            // volume a deliberate watch raised it to, and muted has to mean muted whatever it was before.
            p.volume = 0
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

/// The trailer's layer filling the hero like the backdrop (aspect fill, centered). The layer belongs to
/// `HeroTrailerModel`, not to this view, so the Trailer button can move it to full screen with the player still
/// drawing in it; Core Animation takes it out of here the moment its new owner adds it.
struct HeroVideoView: UIViewRepresentable {
    let layer: AVPlayerLayer?

    final class LayerView: UIView {
        private weak var held: AVPlayerLayer?

        func hold(_ l: AVPlayerLayer?) {
            guard held !== l else { setNeedsLayout(); return }
            if let old = held, old.superlayer === layer { old.removeFromSuperlayer() }
            held = l
            if let l {
                layer.addSublayer(l)
                l.frame = bounds
            }
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            if let l = held, l.superlayer === layer { l.frame = bounds }
        }
    }

    func makeUIView(context: Context) -> LayerView {
        let v = LayerView()
        v.backgroundColor = .clear
        v.isUserInteractionEnabled = false
        v.hold(layer)
        return v
    }

    func updateUIView(_ v: LayerView, context: Context) {
        v.hold(layer)
    }
}
