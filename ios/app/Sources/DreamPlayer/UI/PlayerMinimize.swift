import SwiftUI
import UIKit
import DreamCore

/// The mini player's way in from the full player: pull the picture down from the center third (the left and right
/// thirds belong to brightness and volume), or the chevron in the top bar. The player follows the finger, shrinking;
/// let go far enough (or flick) and it shrinks away into the mini player above the tab bar, else it springs back.
@MainActor
enum PlayerMinimize {
    /// A real title playing full screen (not a trailer, not floating in Picture in Picture).
    static func canMinimize(_ s: PlayerSession) -> Bool {
        s.phase == .playing && !s.isTrailer && !(s.pip?.isActive ?? false) && DreamPlayerPresenter.shared.canMinimize
    }

    static func gesture(_ s: PlayerSession, size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 18)
            .onChanged { v in
                let p = PlayerPresentation.shared
                if p.pullOwned == nil {
                    // Decided once per drag, on its first move: downward, mostly vertical, from the center third.
                    let x = v.startLocation.x
                    let center = x > size.width / 3 && x < size.width * 2 / 3
                    let t = v.translation
                    let down = t.height > 0 && t.height > abs(t.width) * 1.3
                    p.pullOwned = center && down && canMinimize(s)
                    if p.pullOwned == true { haptic(.light) }
                }
                guard p.pullOwned == true else { return }
                p.pull = min(1, max(0, v.translation.height / max(1, size.height * 0.8)))
            }
            .onEnded { v in
                let p = PlayerPresentation.shared
                let mine = p.pullOwned == true
                p.pullOwned = nil
                guard mine else { return }
                let far = v.translation.height > size.height * 0.22 || v.predictedEndTranslation.height > size.height * 0.55
                if far, canMinimize(s) {
                    minimize(s)
                } else {
                    withAnimation(.spring(response: 0.34, dampingFraction: 0.86)) { p.pull = 0 }
                }
            }
    }

    /// The shrink-out, then the presenter hands the picture to the mini player and the app turns back to portrait.
    static func minimize(_ s: PlayerSession) {
        guard canMinimize(s) else { return }
        let p = PlayerPresentation.shared
        haptic(.medium)
        Log.player.info("Mini player: minimizing")
        withAnimation(.easeIn(duration: 0.2)) {
            p.leaving = true
        } completion: {
            DreamPlayerPresenter.shared.minimize()
        }
    }
}

/// The full player's look while it's pulled down: smaller, lower and a little dimmer; on release it keeps shrinking
/// toward the bottom and fades (the mini player then flies in from the middle of the portrait screen).
struct PullShrink: ViewModifier {
    let height: CGFloat

    func body(content: Content) -> some View {
        let p = PlayerPresentation.shared
        let t = p.leaving ? 1 : p.pull
        content
            .scaleEffect(p.leaving ? 0.34 : 1 - 0.42 * t, anchor: .center)
            .offset(y: height * (p.leaving ? 0.36 : 0.30 * t))
            .opacity(p.leaving ? 0 : 1 - 0.18 * t)
    }
}

extension View {
    /// The pull-down that minimizes Dream Player (center third of the picture).
    func minimizeSwipe(_ session: PlayerSession, size: CGSize) -> some View {
        simultaneousGesture(PlayerMinimize.gesture(session, size: size))
    }

    /// The player's shrink while it's pulled down (PullShrink).
    func minimizeShrink(height: CGFloat) -> some View {
        modifier(PullShrink(height: height))
    }
}
