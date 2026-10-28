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

    public var isShowing: Bool { controller != nil }
    /// A title floats in Picture in Picture while the viewer browses.
    public var isFloating: Bool { parked != nil }

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
            return s
        }
        let s = PlayerSession()
        session = s
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
        PlayerHostController.unlock()
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
