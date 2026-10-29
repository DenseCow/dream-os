import SwiftUI
import UIKit
import DreamCore

/// The way into Dream Player from any screen:
///
///     DreamPlayerPresenter.shared.play(PlayRequest(title: t, imdbId: id, season: 1, episode: 2))
///     DreamPlayerPresenter.shared.playTrailer(of: title, keys: [details.trailerKey].compactMap { $0 })
///
/// Presents full screen in landscape over whatever is showing (its own UIKit controller, so no modifier is
/// needed on the app side), plays through Dream Core, records Continue Watching, and returns when done.
@MainActor
public final class DreamPlayerPresenter {
    public static let shared = DreamPlayerPresenter()

    /// Opens a title page (X-Ray's Known For, More Like This shows). Set by the app; the player closes first.
    public var openTitle: ((Title) -> Void)?
    /// Opens Settings at the accounts / scrapers page from the curtain's error (optional).
    public var openSettings: (() -> Void)?

    private weak var controller: PlayerHostController?
    private var session: PlayerSession?
    /// Held while Picture in Picture floats and the player is out of the way (started from the button).
    private var parked: PlayerHostController?
    /// Held while the player is minimized into the mini player above the tab bar (PlayerPresentation).
    private var minimized: PlayerHostController?

    /// The player is up full screen (not minimized).
    public var isShowing: Bool { controller != nil && minimized == nil }
    /// A title plays while the viewer browses: floating in Picture in Picture, or in the mini player.
    public var isFloating: Bool { parked != nil || minimized != nil }
    /// The player is minimized into the mini player.
    public var isMinimized: Bool { minimized != nil }
    /// The open player's session (nil when closed): Cellular Data Saver pauses it and switches its copy (DreamOS).
    public var currentSession: PlayerSession? { controller != nil ? session : nil }
    /// Full screen and in front, so the pull-down / chevron may minimize it.
    var canMinimize: Bool { controller?.presentingViewController != nil && parked == nil && minimized == nil }

    public func play(_ request: PlayRequest) {
        present().play(request)
    }

    public func playTrailer(of title: Title, keys: [String], start: Double = 0) {
        present().playTrailer(of: title, keys: keys, start: start)
    }

    /// Closes the player (saves the spot).
    public func dismiss() { session?.close() }

    private func present() -> PlayerSession {
        if let s = session, controller != nil {
            // Something new from the app while a title floats: the player comes back, the window closes.
            if parked != nil { unpark {}; s.pip.stop() }
            // Something new from the app while minimized: the player comes back full screen and plays it.
            if minimized != nil { expand() }
            return s
        }
        // A session whose screen is gone without a close (its presentation was refused): ended before a new one,
        // or its mpv keeps playing unseen.
        if session != nil { finish() }
        let s = PlayerSession()
        session = s
        VideoDock.shared.spot = .full
        PlayerPresentation.shared.attach(s)
        s.pip.parkPlayer = { [weak self] in self?.park() }
        s.pip.restorePlayer = { [weak self] done in
            if let self { self.unpark(done) } else { done() }
        }
        s.pip.isParked = { [weak self] in self?.parked != nil }
        let vc = PlayerHostController(rootView: AnyView(PlayerScreen(session: s)))
        s.onClose = { [weak self, weak vc] in
            guard let self else { return }
            vc?.presentingViewController?.dismiss(animated: true)
            self.finish()
        }
        controller = vc
        if let top = Self.topController() {
            top.present(vc, animated: true) {
                vc.lockLandscape()
            }
        }
        return s
    }

    private func finish() {
        session?.shutdown()
        session = nil
        controller = nil
        parked = nil
        if minimized != nil {
            // Closed from the mini player: the full screen's onDisappear already ran, so Auto-Lock comes back here.
            minimized = nil
            UIApplication.shared.isIdleTimerDisabled = false
        }
        VideoDock.shared.spot = .full
        PlayerPresentation.shared.detach()
        PlayerHostController.unlock()
    }

    /// The pull-down / chevron (PlayerMinimize): the player steps aside into the mini player, still playing, and the
    /// app turns back to portrait. The picture moves into the mini player's slot (VideoDock); nothing restarts.
    func minimize() {
        guard let vc = controller, vc.presentingViewController != nil, parked == nil, minimized == nil else {
            Log.player.info("Mini player: minimize skipped")
            PlayerPresentation.shared.resetPull()
            return
        }
        Log.player.info("Mini player: player steps aside")
        minimized = vc
        VideoDock.shared.spot = .mini
        PlayerPresentation.shared.didMinimize()
        vc.presentingViewController?.dismiss(animated: false) {
            PlayerHostController.unlock()
            PlayerPresentation.shared.resetPull()
            // The screen never sleeps while the player is open (owner), minimized included. Again on the next turn,
            // after the full screen's onDisappear handed Auto-Lock back.
            UIApplication.shared.isIdleTimerDisabled = true
            DispatchQueue.main.async {
                UIApplication.shared.isIdleTimerDisabled = true
                Log.player.info("Mini player: minimized (\(VideoDock.shared.owner, privacy: .public))")
            }
        }
    }

    /// The mini player tapped (or a new Play while minimized): the same player, full screen and landscape again.
    func expand() {
        guard let vc = minimized else { return }
        guard vc.presentingViewController == nil, let top = Self.topController() else {
            Log.player.error("Mini player: can't bring the player back (presented: \(vc.presentingViewController != nil, privacy: .public))")
            return
        }
        Log.player.info("Mini player: player comes back (\(VideoDock.shared.owner, privacy: .public))")
        // Floating in Picture in Picture (software frames): the window closes first, so mpv is back on the Metal
        // picture by the time the full screen shows (the picture watch redraws if it isn't).
        if let s = session, s.pip.isActive {
            Log.player.info("Mini player: closing the Picture in Picture window before full screen")
            s.pip.stop()
        }
        minimized = nil
        // The full surface takes the picture back once it's in the window (the mini player keeps it until then).
        VideoDock.shared.spot = .full
        PlayerPresentation.shared.didExpand()
        // The controller is held by the presentation from here on (`controller` is weak).
        top.present(vc, animated: true) {
            vc.lockLandscape()
            VideoDock.shared.place()
            Log.player.info("Mini player: full screen again (\(VideoDock.shared.owner, privacy: .public))")
        }
    }

    /// Picture in Picture started from the button: the player steps aside (the session keeps playing).
    private func park() {
        guard parked == nil, let vc = controller, vc.presentingViewController != nil else {
            Log.player.info("PiP: park skipped (parked: \(self.parked != nil, privacy: .public), presented: \(self.controller?.presentingViewController != nil, privacy: .public))")
            return
        }
        Log.player.info("PiP: player steps aside")
        parked = vc
        vc.presentingViewController?.dismiss(animated: true) { PlayerHostController.unlock() }
    }

    /// Back from the floating window: the same player, full screen again.
    private func unpark(_ done: @escaping @MainActor () -> Void) {
        guard let vc = parked else { done(); return }
        parked = nil
        guard vc.presentingViewController == nil, let top = Self.topController() else {
            Log.player.error("PiP: can't bring the player back (presented: \(vc.presentingViewController != nil, privacy: .public))")
            done(); return
        }
        Log.player.info("PiP: player comes back")
        // The controller is held by the presentation from here on (`controller` is weak).
        top.present(vc, animated: true) {
            vc.lockLandscape()
            done()
        }
    }

    /// Hands a title to the app's page (closing the player first).
    func open(_ t: Title) {
        session?.close()
        let f = openTitle
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { f?(t) }
    }

    static func topController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first { $0.isKeyWindow } ?? scenes.first?.windows.first
        var top = window?.rootViewController
        while let p = top?.presentedViewController { top = p }
        return top
    }
}

/// The player's controller: landscape only, no status bar or home indicator, black.
final class PlayerHostController: UIHostingController<AnyView> {
    override init(rootView: AnyView) {
        super.init(rootView: rootView)
        modalPresentationStyle = .fullScreen
        modalTransitionStyle = .crossDissolve
        view.backgroundColor = .black
    }
    @MainActor required dynamic init?(coder: NSCoder) { fatalError() }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .landscape }
    override var preferredInterfaceOrientationForPresentation: UIInterfaceOrientation { .landscapeRight }
    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge { .all }

    func lockLandscape() {
        setNeedsUpdateOfSupportedInterfaceOrientations()
        view.window?.windowScene?.requestGeometryUpdate(.iOS(interfaceOrientations: .landscape)) { _ in }
        setNeedsUpdateOfHomeIndicatorAutoHidden()
    }

    static func unlock() {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        scene?.requestGeometryUpdate(.iOS(interfaceOrientations: .portrait)) { _ in }
        DreamPlayerPresenter.topController()?.setNeedsUpdateOfSupportedInterfaceOrientations()
    }
}
