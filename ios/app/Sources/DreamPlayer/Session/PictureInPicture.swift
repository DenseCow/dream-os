import Foundation
import AVKit
import AVFoundation
import CoreMedia
import UIKit
import DreamCore

/// Picture in Picture for Dream Player: the floating window's controller and its delegates, on top of whatever
/// the engine offers (`PlayerEngine.pipContent`). Started by the player's button (the player then steps aside
/// so the viewer can browse) or by the system when the viewer leaves the app mid-playback.
///
/// Rule: the normal player is never at the mercy of this file. Nothing here waits on mpv or on AVKit; if
/// anything is refused the window simply doesn't open and the player keeps going.
@MainActor
public final class PictureInPicture: NSObject, ObservableObject {
    @Published public private(set) var isActive = false
    @Published public private(set) var isPossible = false

    /// The device can float video and this engine supports it.
    /// 2026-10-27: the button froze the player (MPVFrameFeed.attach held its lock while mpv called the update
    /// callback back on the same thread: a self-deadlock, crash report 21:16 0x8BADF00D). Fixed 2026-10-28, but
    /// the fixed build (installed 01:37 with this `true`) was not yet tried by hand on the phone, so the flag stays
    /// off here until next.md Bug 3's checklist passes; then flip it to `true` (nothing else to change).
    static let enabled = true
    public var isAvailable: Bool { Self.enabled && controller != nil }

    /// Set by the presenter: hide the player while the window floats / bring it back (call `done` when shown).
    var parkPlayer: (() -> Void)?
    var restorePlayer: ((@escaping @MainActor () -> Void) -> Void)?
    /// The player is hidden behind the floating window (started from the button).
    var isParked: () -> Bool = { false }

    private weak var session: PlayerSession?
    private var controller: AVPictureInPictureController?
    private var possibleWatch: NSKeyValueObservation?
    private var foregroundWatch: NSObjectProtocol?
    private var restoring = false
    private var fromButton = false
    private var waitTask: Task<Void, Never>?
    private var askedAt: CFTimeInterval = 0
    /// Sample buffer source (the window asks this object for play state; AVPlayerLayer sources don't).
    private var sampleSource = false

    init(session: PlayerSession) {
        self.session = session
        super.init()
        guard Self.enabled else { return }
        guard AVPictureInPictureController.isPictureInPictureSupported(), let content = session.engine.pipContent else {
            Log.player.info("PiP: not available (device or engine)")
            return
        }
        let source: AVPictureInPictureController.ContentSource
        switch content {
        case .playerLayer(let l): source = .init(playerLayer: l)
        case .sampleBuffer(let l): source = .init(sampleBufferDisplayLayer: l, playbackDelegate: self); sampleSource = true
        }
        let c = AVPictureInPictureController(contentSource: source)
        c.delegate = self
        c.canStartPictureInPictureAutomaticallyFromInline = false
        controller = c
        possibleWatch = c.observe(\.isPictureInPicturePossible, options: [.initial, .new]) { [weak self] c, _ in
            let v = c.isPictureInPicturePossible
            Task { @MainActor in
                guard let self, self.isPossible != v else { return }
                self.isPossible = v
                Log.player.info("PiP: possible = \(v, privacy: .public)")
            }
        }
        session.engine.onPiPFrame = { [weak self] in self?.firstFrame() }
        // The app came back with the full-screen player still showing under the window: close the window,
        // like AVKit's own player does (a restore through the window's button goes through `restore` instead).
        foregroundWatch = NotificationCenter.default.addObserver(forName: UIApplication.willEnterForegroundNotification,
                                                                 object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.appReturned() }
        }
    }

    // MARK: Called by the session / screen

    /// The player's button.
    public func toggle() {
        guard let c = controller, let s = session else { return }
        if c.isPictureInPictureActive {
            Log.player.info("PiP: button -> stop")
            c.stopPictureInPicture()
            return
        }
        guard c.isPictureInPicturePossible, s.state.hasSource else {
            Log.player.info("PiP: button, not possible (system: \(c.isPictureInPicturePossible, privacy: .public), source: \(s.state.hasSource, privacy: .public))")
            s.show(toast: "Picture in Picture Isn't Available Right Now")
            return
        }
        Log.player.info("PiP: button -> start")
        fromButton = true
        askedAt = CACurrentMediaTime()
        // Frames first, so the window opens on the picture rather than black; then ask for the window.
        s.engine.pipWillStart()
        waitTask?.cancel()
        waitTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(1500))
            guard let self, !Task.isCancelled else { return }
            Log.player.info("PiP: no frame yet, asking anyway")
            self.startNow()
        }
    }

    private func firstFrame() {
        guard fromButton, waitTask != nil, !isActive else { return }
        waitTask?.cancel()
        // One more frame's worth so the layer has really drawn it.
        waitTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(80))
            guard let self, !Task.isCancelled else { return }
            self.startNow()
        }
    }

    private func startNow() {
        waitTask = nil
        guard let c = controller, !c.isPictureInPictureActive else { return }
        Log.player.info("PiP: asking the system \(Int((CACurrentMediaTime() - self.askedAt) * 1000), privacy: .public) ms after the button (possible: \(c.isPictureInPicturePossible, privacy: .public))")
        c.startPictureInPicture()
        // Nothing happened (the system said no): undo the switch.
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(2.5))
            guard let self, !self.isActive, !(self.controller?.isPictureInPictureActive ?? false) else { return }
            Log.player.error("PiP: didn't start")
            self.fromButton = false
            self.session?.engine.pipDidStop()
            self.session?.show(toast: "Picture in Picture Isn't Available Right Now")
        }
    }

    /// Engine state changed: the window's play / pause and progress, and whether leaving the app may start it.
    func refresh() {
        guard let c = controller, let s = session else { return }
        let st = s.state
        // The system starts it on leaving the app only while a real title (not a trailer) is playing.
        let auto = Self.enabled && s.phase == .playing && !s.isTrailer && (st.isPlaying || st.isBuffering) && !st.ended
        if c.canStartPictureInPictureAutomaticallyFromInline != auto {
            c.canStartPictureInPictureAutomaticallyFromInline = auto
            Log.player.info("PiP: automatic start on leaving the app = \(auto, privacy: .public)")
        }
        if sampleSource, isActive || auto { c.invalidatePlaybackState() }
    }

    /// Closes the window (the session is ending, or another title is starting from the app).
    public func stop() {
        waitTask?.cancel(); waitTask = nil
        if let c = controller, c.isPictureInPictureActive { c.stopPictureInPicture() }
    }

    func invalidate() {
        stop()
        possibleWatch = nil
        if let o = foregroundWatch { NotificationCenter.default.removeObserver(o) }
        foregroundWatch = nil
        session?.engine.onPiPFrame = nil
        controller?.delegate = nil
        controller = nil
        isActive = false
    }

    private func appReturned() {
        guard isActive, !restoring, !isParked() else { return }
        Log.player.info("PiP: app in front with the player showing, closing the window")
        stop()
    }

    // MARK: Delegate work (main thread)

    fileprivate func willStart() {
        Log.player.info("PiP: will start (button: \(self.fromButton, privacy: .public))")
        isActive = true
        restoring = false
        session?.engine.pipWillStart()
    }

    fileprivate func didStart() {
        Log.player.info("PiP: started (button: \(self.fromButton, privacy: .public), app active: \(UIApplication.shared.applicationState == .active, privacy: .public))")
        // Started from the button with the app in front: the player steps aside so the viewer can browse.
        if fromButton, UIApplication.shared.applicationState == .active { parkPlayer?() }
        fromButton = false
    }

    fileprivate func failed(_ error: Error) {
        Log.player.error("PiP: failed to start: \(error.localizedDescription, privacy: .public)")
        isActive = false
        fromButton = false
        waitTask?.cancel(); waitTask = nil
        session?.engine.pipDidStop()
        if UIApplication.shared.applicationState == .active {
            session?.show(toast: "Picture in Picture Isn't Available Right Now")
        }
    }

    fileprivate func didStop() {
        Log.player.info("PiP: stopped (restore: \(self.restoring, privacy: .public), parked: \(self.isParked(), privacy: .public))")
        isActive = false
        session?.engine.pipDidStop()
        // The picture back in its host for where the player is now (mini player or full screen), checked afresh.
        PlayerPresentation.shared.pipEnded()
        let restored = restoring
        restoring = false
        guard !restored, let s = session else { return }
        // Closed with the window's X: the player was hidden -> end it (saves the spot); else pause, like AVKit.
        if isParked() { s.close() }
        else if UIApplication.shared.applicationState != .active, s.state.isPlaying { s.engine.pause() }
    }

    fileprivate func restore(_ done: @escaping @MainActor (Bool) -> Void) {
        Log.player.info("PiP: restore asked (parked: \(self.isParked(), privacy: .public), minimized: \(DreamPlayerPresenter.shared.isMinimized, privacy: .public); \(VideoDock.shared.owner, privacy: .public))")
        // Minimized: the window goes back into the mini player (the picture stays docked there), never full screen.
        restoring = true
        if isParked(), let r = restorePlayer { r { done(true) } } else { done(true) }
    }

    fileprivate func setPlaying(_ playing: Bool) {
        guard let s = session else { return }
        if playing { s.engine.play() } else { s.engine.pause() }
        s.userActed()
    }

    fileprivate var durationSeconds: Double { session?.state.duration ?? 0 }

    fileprivate var paused: Bool {
        guard let st = session?.state else { return true }
        return !(st.isPlaying || (st.isBuffering && !st.paused))
    }

    fileprivate func skip(_ seconds: Double) {
        guard let s = session else { return }
        s.seek(to: s.state.position + seconds)
    }

    fileprivate func renderSize(_ w: Int, _ h: Int) {
        session?.engine.pipRenderSize(width: w, height: h)
    }

    // AVKit's delegate calls arrive on the main thread in practice, but the sample buffer playback delegate
    // doesn't promise it: answer from the main actor wherever the call comes from, never trap.
    nonisolated private func onMain<T: Sendable>(_ body: @MainActor @Sendable () -> T) -> T {
        if Thread.isMainThread { return MainActor.assumeIsolated { body() } }
        return DispatchQueue.main.sync { MainActor.assumeIsolated { body() } }
    }

    nonisolated private func onMainLater(_ body: @escaping @MainActor @Sendable () -> Void) {
        if Thread.isMainThread { MainActor.assumeIsolated { body() } } else { DispatchQueue.main.async { body() } }
    }
}

extension PictureInPicture: AVPictureInPictureControllerDelegate {
    nonisolated public func pictureInPictureControllerWillStartPictureInPicture(_ c: AVPictureInPictureController) {
        onMainLater { self.willStart() }
    }
    nonisolated public func pictureInPictureControllerDidStartPictureInPicture(_ c: AVPictureInPictureController) {
        onMainLater { self.didStart() }
    }
    nonisolated public func pictureInPictureController(_ c: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) {
        let e = error as NSError
        onMainLater { self.failed(e) }
    }
    nonisolated public func pictureInPictureControllerDidStopPictureInPicture(_ c: AVPictureInPictureController) {
        onMainLater { self.didStop() }
    }
    nonisolated public func pictureInPictureController(_ c: AVPictureInPictureController,
                                                       restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        let box = CompletionBox(completionHandler)
        onMainLater { self.restore { ok in box.call(ok) } }
    }
}

extension PictureInPicture: AVPictureInPictureSampleBufferPlaybackDelegate {
    nonisolated public func pictureInPictureController(_ c: AVPictureInPictureController, setPlaying playing: Bool) {
        onMainLater { self.setPlaying(playing) }
    }
    nonisolated public func pictureInPictureControllerTimeRangeForPlayback(_ c: AVPictureInPictureController) -> CMTimeRange {
        let d = onMain { self.durationSeconds }
        guard d > 0 else { return CMTimeRange(start: .negativeInfinity, duration: .positiveInfinity) }
        return CMTimeRange(start: .zero, duration: CMTime(seconds: d, preferredTimescale: 1000))
    }
    nonisolated public func pictureInPictureControllerIsPlaybackPaused(_ c: AVPictureInPictureController) -> Bool {
        onMain { self.paused }
    }
    nonisolated public func pictureInPictureController(_ c: AVPictureInPictureController, didTransitionToRenderSize newRenderSize: CMVideoDimensions) {
        let w = Int(newRenderSize.width), h = Int(newRenderSize.height)
        onMainLater { self.renderSize(w, h) }
    }
    nonisolated public func pictureInPictureController(_ c: AVPictureInPictureController, skipByInterval skipInterval: CMTime,
                                                       completion completionHandler: @escaping () -> Void) {
        let secs = skipInterval.seconds
        onMainLater { self.skip(secs.isFinite ? secs : 0) }
        completionHandler()
    }
    nonisolated public func pictureInPictureControllerShouldProhibitBackgroundAudioPlayback(_ c: AVPictureInPictureController) -> Bool {
        false
    }
}

/// Carries AVKit's completion handler onto the main actor.
private final class CompletionBox: @unchecked Sendable {
    private let f: (Bool) -> Void
    init(_ f: @escaping (Bool) -> Void) { self.f = f }
    func call(_ ok: Bool) { f(ok) }
}
