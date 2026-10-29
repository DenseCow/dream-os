import SwiftUI
import UIKit
import Combine
import Observation
import DreamCore

/// How Dream Player is showing: full screen (landscape), minimized into the mini player above the tab bar (the app's
/// `Shell/MiniPlayer.swift` draws it; portrait, browsing), or not at all. The same session, engine and picture in
/// both: minimizing moves the engine's video view into the mini player's slot (`VideoDock`), so nothing restarts,
/// re-resolves or decodes twice.
///
/// The app reads `mode` and the mini player's words and numbers from here; `expand()`, `close()` and `togglePlay()`
/// are its buttons. The pull-down that minimizes lives in `UI/PlayerMinimize.swift`.
@MainActor @Observable
public final class PlayerPresentation {
    public static let shared = PlayerPresentation()

    public enum Mode: Equatable { case none, full, mini }
    public private(set) var mode: Mode = .none

    // What the mini player shows (kept current only while minimized).
    public private(set) var title = ""
    /// "S1 E2  ·  Episode Name" or "Movie  ·  2024".
    public private(set) var line = ""
    /// 0...1 of the running time.
    public private(set) var progress: Double = 0
    public private(set) var isPlaying = false
    /// Finding the next episode's stream, or buffering.
    public private(set) var isLoading = false
    /// The curtain's error title when the stream failed (tap expands to its Try Again / Change Source).
    public private(set) var problem: String?
    /// Bumped on every minimize (the mini player flies in from the middle of the screen each time).
    public private(set) var arrival = 0

    // The pull-down in the full player (PlayerMinimize): 0...1 while the viewer drags, `leaving` for the shrink-out.
    var pull: CGFloat = 0
    var leaving = false
    /// Whether the drag in progress is the pull-down (decided on its first move), nil between drags.
    @ObservationIgnored var pullOwned: Bool?

    @ObservationIgnored private weak var session: PlayerSession?
    @ObservationIgnored private var watch: AnyCancellable?
    @ObservationIgnored private var queued = false
    @ObservationIgnored private var endClose: Task<Void, Never>?
    // The picture watch (sound playing over a black picture, seen 2026-10-28 after PiP -> mini player).
    // Event-driven since 2026-10-28 evening (a constant checker re-set mpv up on a 5 pt layout wobble, owner): it only
    // runs for a few seconds after the picture moved hosts, PiP ended or the app came back, and acts once per event.
    @ObservationIgnored private var pictureWatch: Timer?
    @ObservationIgnored private var watchUntil: CFTimeInterval = 0
    @ObservationIgnored private var watchWhy = ""
    @ObservationIgnored private var pictureMisses = 0
    @ObservationIgnored private var redrawDone = false
    @ObservationIgnored private var sizeDone = false
    @ObservationIgnored private var sizeOkLogged = false
    @ObservationIgnored private var lastLayer: CGSize = .zero
    @ObservationIgnored private var layerSince: CFTimeInterval = 0
    @ObservationIgnored private var offSince: CFTimeInterval?
    @ObservationIgnored private var foregroundObserver: NSObjectProtocol?

    private init() {}

    // MARK: The mini player's buttons

    /// Back to full screen at the same spot (landscape again).
    public func expand() { DreamPlayerPresenter.shared.expand() }

    /// Stops and saves the spot, exactly like the player's X (Continue Watching, Trakt, Watched rules unchanged).
    public func close() { DreamPlayerPresenter.shared.dismiss() }

    public func togglePlay() {
        guard let s = session else { return }
        s.userActed()
        s.togglePlay()
    }

    // MARK: Called by the presenter

    /// The engine's picture, for the mini player's slot.
    var videoView: UIView? { session?.engine.view }

    func attach(_ s: PlayerSession) {
        session = s
        mode = .full
        pull = 0; leaving = false; pullOwned = nil
        // Every change the session publishes (about twice a second while playing) refreshes the mini player's
        // words and line, read on the next turn of the main queue (objectWillChange fires before the change).
        watch = s.objectWillChange.sink { [weak self] _ in
            // Sent on the main thread (the session is main-actor): no Task per change; queueRefresh defers itself.
            MainActor.assumeIsolated { self?.queueRefresh() }
        }
        if foregroundObserver == nil {
            foregroundObserver = NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification,
                                                                        object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.watchPicture("back in front") }
            }
        }
    }

    func detach() {
        pictureWatch?.invalidate(); pictureWatch = nil
        watchUntil = 0
        watch = nil
        session = nil
        endClose?.cancel(); endClose = nil
        mode = .none
        pull = 0; leaving = false; pullOwned = nil
        problem = nil
        isLoading = false
    }

    func didMinimize() {
        refresh()
        arrival &+= 1
        mode = .mini
    }

    func didExpand() {
        endClose?.cancel(); endClose = nil
        mode = .full
    }

    /// The full player is off screen: its pull-down look goes back to rest for the next time it shows.
    func resetPull() {
        pull = 0
        leaving = false
        pullOwned = nil
    }

    // MARK: Picture watch (event-driven)

    /// How long the watch looks after an event, how far mpv's size may differ from the layer's before it counts as a
    /// corner box (smaller gaps are scaled by Core Animation and invisible), and how long a real mismatch must hold
    /// with the layer's size steady before mpv is set up again.
    static let watchSeconds: CFTimeInterval = 5
    static let sizeTolerance: CGFloat = 0.10
    static let sizeHold: CFTimeInterval = 1.5
    /// No frame for this long while sound plays (after the event) -> redraw once.
    static let staleSeconds: Double = 2.0

    /// Starts (or extends) a short watch after the picture moved hosts, PiP ended or the app came back. Each check
    /// acts at most once per event; nothing runs during steady playback.
    func watchPicture(_ why: String) {
        guard session != nil else { return }
        let now = CACurrentMediaTime()
        watchUntil = now + Self.watchSeconds
        watchWhy = why
        pictureMisses = 0
        redrawDone = false
        sizeDone = false
        sizeOkLogged = false
        offSince = nil
        lastLayer = .zero
        layerSince = now
        if pictureWatch == nil {
            pictureWatch = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
                MainActor.assumeIsolated { self?.checkPicture() }
            }
        }
    }

    private func checkPicture() {
        let now = CACurrentMediaTime()
        guard now <= watchUntil, session != nil else {
            pictureWatch?.invalidate(); pictureWatch = nil
            return
        }
        guard let s = session, mode != .none, UIApplication.shared.applicationState == .active,
              s.phase == .playing, !s.ended, !(s.pip?.isActive ?? false) else {
            pictureMisses = 0
            offSince = nil
            return
        }
        checkSize(s, now: now)
        checkFrames(s)
    }

    /// A real corner box only: mpv more than 10% off the layer in either direction, held 1.5 s with the layer's size
    /// unchanged (never mid-rotation or mid-layout), then one re-set-up for this event. Within tolerance: nothing
    /// (one log line). Setting the drawable alone while mpv runs would only make a smaller box, so it's left alone.
    private func checkSize(_ s: PlayerSession, now: CFTimeInterval) {
        guard !sizeDone, let z = s.engine.pictureSizes else { offSince = nil; return }
        if z.layer != lastLayer { lastLayer = z.layer; layerSince = now; offSince = nil }
        let off = max(abs(z.layer.width - z.drawing.width) / z.layer.width, abs(z.layer.height - z.drawing.height) / z.layer.height)
        if off <= Self.sizeTolerance {
            offSince = nil
            if !sizeOkLogged {
                sizeOkLogged = true
                Log.player.info("Picture: size ok within tolerance after \(self.watchWhy, privacy: .public) (\(String(format: "%.1f", off * 100), privacy: .public)% off; \(s.engine.pictureSizeLine() ?? "?", privacy: .public))")
            }
            return
        }
        if offSince == nil { offSince = now }
        guard let since = offSince, now - since >= Self.sizeHold, now - layerSince >= Self.sizeHold else { return }
        sizeDone = true
        Log.player.error("Picture: size off after \(self.watchWhy, privacy: .public) in the \(self.mode == .mini ? "mini player" : "full screen", privacy: .public) (\(String(format: "%.0f", off * 100), privacy: .public)% off; \(s.engine.pictureSizeLine() ?? "?", privacy: .public); \(VideoDock.shared.owner, privacy: .public)), setting it up again once")
        s.engine.refitPicture()
    }

    /// Sound playing but the picture isn't in its host on screen, or hasn't drawn for 2 s: put back and redrawn once.
    private func checkFrames(_ s: PlayerSession) {
        guard !redrawDone, s.state.isPlaying else { pictureMisses = 0; return }
        let dock = VideoDock.shared
        let since = s.engine.secondsSincePicture
        if dock.isPlaced && (since ?? 0) <= Self.staleSeconds { pictureMisses = 0; return }
        pictureMisses += 1
        guard pictureMisses >= 2 else { return }
        redrawDone = true
        let gap = since.map { $0 >= 999 ? "sample layer in front" : String(format: "%.1f s", $0) } ?? "unknown"
        Log.player.error("Mini player: no picture while playing after \(self.watchWhy, privacy: .public) (\(self.mode == .mini ? "mini" : "full screen", privacy: .public); last frame: \(gap, privacy: .public); \(dock.owner, privacy: .public)), re-attaching and redrawing once")
        dock.place()
        s.engine.redrawPicture()
    }

    /// Picture in Picture just ended (restored or closed): the picture goes back to its host for the current spot and
    /// is watched for a few seconds.
    func pipEnded() {
        Log.player.info("PiP: ended with the player \(self.mode == .mini ? "minimized" : self.mode == .full ? "full screen" : "closed", privacy: .public) (\(VideoDock.shared.owner, privacy: .public))")
        VideoDock.shared.place()
        watchPicture("Picture in Picture")
    }

    /// "Picture: size" for the log, now and once more after the move has settled (rotation, layout).
    func logPictureSize(_ why: String) {
        guard let e = session?.engine else { return }
        Log.player.info("Picture: size, \(why, privacy: .public) (\(e.pictureSizeLine() ?? "unknown", privacy: .public))")
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(700))
            guard let e = self?.session?.engine else { return }
            Log.player.info("Picture: size, \(why, privacy: .public), settled (\(e.pictureSizeLine() ?? "unknown", privacy: .public))")
        }
    }

    // MARK: Refresh

    private func queueRefresh() {
        guard mode == .mini, !queued else { return }
        queued = true
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.queued = false
            self.refresh()
        }
    }

    private func refresh() {
        guard let s = session else { return }
        let r = s.request
        let name = r?.title.name ?? "Dream Player"
        if title != name { title = name }
        let l = Self.line(for: s)
        if line != l { line = l }
        let st = s.state
        let p = st.duration > 0 ? min(1, max(0, st.position / st.duration)) : 0
        if abs(progress - p) > 0.0004 || (p == 0) != (progress == 0) { progress = p }
        let playing = st.isPlaying || (st.isBuffering && !st.paused)
        if isPlaying != playing { isPlaying = playing }
        let loading = s.phase == .loading || (s.phase == .playing && st.isBuffering && !s.ended)
        if isLoading != loading { isLoading = loading }
        var failed: String?
        if case .failed(let t, _) = s.phase { failed = t }
        if problem != failed { problem = failed }
        // The video ended while minimized with nothing playing next (autoplay advances at once and clears `ended`):
        // nobody sees More Like This down here, so the mini player closes like the player would (spot saved).
        if mode == .mini, s.ended {
            if endClose == nil {
                endClose = Task { [weak self] in
                    try? await Task.sleep(for: .seconds(2))
                    guard let self, !Task.isCancelled else { return }
                    self.endClose = nil
                    if self.mode == .mini, self.session?.ended == true {
                        Log.player.info("Mini player: video ended, nothing next, closing")
                        self.close()
                    }
                }
            }
        } else if endClose != nil {
            endClose?.cancel(); endClose = nil
        }
    }

    static func line(for s: PlayerSession) -> String {
        guard let r = s.request else { return "" }
        if s.isTrailer { return "Trailer" }
        if let se = r.season, let e = r.episode {
            let name = r.episodeName ?? s.episodeInfo?.name
            return ["S\(se) E\(e)", name].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "  ·  ")
        }
        return ["Movie", r.title.year.isEmpty ? nil : r.title.year].compactMap { $0 }.joined(separator: "  ·  ")
    }
}

// MARK: - The picture's home

/// Where the engine's video view lives: the full player's surface or the mini player's slot. One view, moved between
/// two hosts (UIKit keeps a view in one place), so the picture keeps playing through the move.
@MainActor
final class VideoDock {
    static let shared = VideoDock()
    enum Spot { case full, mini }

    /// Where the picture should be. A host for that spot takes it as soon as it's in a window.
    var spot: Spot = .full {
        didSet { if spot != oldValue { place() } }
    }

    private struct Weak { weak var host: DockHostView? }
    private var hosts: [Weak] = []

    /// The full player's last landscape size (points), seen by its host.
    var fullSize: CGSize?

    /// The size the picture keeps in the mini player: the full player's (or the screen's landscape size before the
    /// full player was ever laid out).
    var renderSize: CGSize {
        if let f = fullSize { return f }
        let s = (UIApplication.shared.connectedScenes.first { $0 is UIWindowScene } as? UIWindowScene)?.screen.bounds.size
            ?? CGSize(width: 852, height: 393)
        return CGSize(width: max(s.width, s.height), height: min(s.width, s.height))
    }

    func register(_ h: DockHostView) {
        hosts.removeAll { $0.host == nil }
        hosts.append(Weak(host: h))
        if h.spot == spot { h.adopt() }
    }

    /// The newest host for the current spot that is on screen takes the picture (one off screen waits for
    /// `didMoveToWindow`, so the picture stays visible where it is until then).
    func place() {
        hosts.removeAll { $0.host == nil }
        if let h = hosts.compactMap(\.host).last(where: { $0.spot == spot && $0.window != nil }) {
            h.adopt()
        } else {
            Log.player.info("Mini player: picture waits for the \(Self.name(self.spot), privacy: .public) host (\(self.owner, privacy: .public))")
        }
    }

    fileprivate func moved(_ h: DockHostView) {
        guard h.window != nil, h.spot == spot else { return }
        h.adopt()
    }

    /// The picture is in a host for the current spot, and that host is on screen.
    var isPlaced: Bool {
        hosts.compactMap(\.host).contains { $0.spot == spot && $0.window != nil && $0.video.superview === $0 }
    }

    /// Who holds the picture right now, for the log.
    var owner: String {
        let all = hosts.compactMap(\.host)
        guard let h = all.first(where: { $0.video.superview === $0 }) else {
            let v = all.last?.video
            return "picture in no host (\(v?.superview == nil ? "detached" : "elsewhere"), in window: \(v?.window != nil)); wanted: \(Self.name(spot))"
        }
        return "picture in the \(Self.name(h.spot)) host, \(h.window != nil ? "on screen" : "off screen"), \(Int(h.bounds.width))x\(Int(h.bounds.height)); wanted: \(Self.name(spot))"
    }

    static func name(_ s: Spot) -> String { s == .full ? "full screen" : "mini" }
}

/// A host the engine's video view can live in (the full player's `VideoSurface`, the mini player's `MiniVideo`).
final class DockHostView: UIView {
    let video: UIView
    let spot: VideoDock.Spot

    init(video: UIView, spot: VideoDock.Spot) {
        self.video = video
        self.spot = spot
        super.init(frame: .zero)
        backgroundColor = .black
        isUserInteractionEnabled = false
    }
    required init?(coder: NSCoder) { fatalError() }

    func adopt() {
        guard video.superview !== self else { return }
        addSubview(video)
        layoutVideo()
        Log.player.info("Mini player: picture -> \(VideoDock.name(self.spot), privacy: .public) host (\(self.window != nil ? "on screen" : "off screen", privacy: .public), \(Int(self.bounds.width), privacy: .public)x\(Int(self.bounds.height), privacy: .public))")
        PlayerPresentation.shared.logPictureSize("moved to the \(VideoDock.name(spot)) host")
        PlayerPresentation.shared.watchPicture("the move to the \(VideoDock.name(spot)) host")
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        VideoDock.shared.moved(self)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if spot == .full, window != nil {
            let f = fullScreenSize
            if f.width > f.height, f.height > 1 { VideoDock.shared.fullSize = f }
        }
        if video.superview === self { layoutVideo() }
    }

    /// The full player's picture size: the window's (the whole screen), whatever the host's own frame does with the
    /// controls, status bar or safe areas (it wobbled between 393 and 398 pt, and every wobble resized mpv's layer).
    /// The host's size only while the window still has the other orientation (mid-turn).
    private var fullScreenSize: CGSize {
        guard let w = window?.bounds.size, w.width > 1, w.height > 1,
              (w.width > w.height) == (bounds.width > bounds.height) else { return bounds.size }
        return w
    }

    /// Full screen: the picture is the whole screen's size, centered on the host. Mini player: the picture keeps the
    /// full screen's size (so mpv's size never changes between the two) and is scaled down to the host's height,
    /// centered and clipped: a 16:9 window onto it, which is the 16:9 picture mpv draws in the middle of the screen.
    private func layoutVideo() {
        if spot == .full {
            let r = fullScreenSize
            video.transform = .identity
            video.autoresizingMask = []
            if video.bounds.size != r { video.bounds = CGRect(origin: .zero, size: r) }
            video.center = CGPoint(x: bounds.midX, y: bounds.midY)
            return
        }
        let r = VideoDock.shared.renderSize
        video.autoresizingMask = []
        video.transform = .identity
        video.bounds = CGRect(origin: .zero, size: r)
        video.center = CGPoint(x: bounds.midX, y: bounds.midY)
        guard bounds.height >= 1, r.height >= 1 else { return }
        let s = bounds.height / r.height
        video.transform = CGAffineTransform(scaleX: s, y: s)
    }
}

/// The live picture for the mini player (the same engine view as the full player, moved here while minimized).
/// `cornerRadius` is in this view's own points (the mini player draws it large and scales it down).
public struct MiniVideo: UIViewRepresentable {
    var cornerRadius: CGFloat
    public init(cornerRadius: CGFloat = 0) { self.cornerRadius = cornerRadius }

    public func makeUIView(context: Context) -> UIView {
        guard let v = PlayerPresentation.shared.videoView else {
            let empty = UIView()
            empty.backgroundColor = .black
            return empty
        }
        let h = DockHostView(video: v, spot: .mini)
        h.layer.cornerCurve = .continuous
        h.layer.cornerRadius = cornerRadius
        h.clipsToBounds = true
        VideoDock.shared.register(h)
        return h
    }

    public func updateUIView(_ uiView: UIView, context: Context) {
        if uiView.layer.cornerRadius != cornerRadius { uiView.layer.cornerRadius = cornerRadius }
    }
}
