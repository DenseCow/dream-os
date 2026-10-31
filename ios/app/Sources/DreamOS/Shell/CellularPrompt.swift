import SwiftUI
import UIKit
import DreamCore
import DreamUI
import DreamPlayback
import DreamPlayer

/// Cellular Data Saver's question (data saver worker, 2026-10-28): with the mode on Ask Me, the first time in a
/// mobile-data session Dream sees mobile data (it opens on it, or Wi-Fi drops) it asks "You're on Mobile Data". Shown at
/// once, over the player too (paused while it's up; compact and centered in landscape); with the app in the background
/// (Picture in Picture) it asks on return; a Play pressed while the answer is due asks first and then plays with it.
/// Use Data Saver over a title above the limit switches that title to a copy within it at the same spot (the player's
/// source switch, with the curtain), or keeps the copy and says so in Info when nothing smaller is ready in time.
/// Back on Wi-Fi after the saver was on: the quiet "Back on Wi-Fi · Full Quality Again" notice.
@MainActor
enum CellularPrompt {
    private static var shown: CellularPromptController?
    private static var waiting: [() -> Void] = []
    private static var observers: [NSObjectProtocol] = []
    /// How long Use Data Saver waits for a smaller copy of the playing title before it keeps the current one.
    static let switchWaitMs = 6_000

    /// Once, from `Wiring.install`.
    static func install() {
        guard observers.isEmpty else { return }
        let nc = NotificationCenter.default
        observers.append(nc.addObserver(forName: .dreamCellularSaverAsk, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { consider("mobile data detected") }
        })
        observers.append(nc.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { consider("back in Dream on mobile data") }
        })
        observers.append(nc.addObserver(forName: .dreamCellularSaverWifiBack, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { wifiBack() }
        })
        observers.append(nc.addObserver(forName: .dreamCellularSaverSessionEnded, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { withdraw() }
        })
        CellularSaver.start() // the network monitor runs from launch, so opening on mobile data is seen
    }

    /// A Play from a page (`PagesServices.play`): asks first when the answer is due, then plays with it.
    /// Only on mobile data read fresh now (never Wi-Fi, a hotspot, Low Data Mode or Wi-Fi Assist); a Play pressed in the
    /// first moments of a switch to mobile data waits out the rest of the settle time (at most 2.5 s) and decides then.
    static func beforePlay(_ go: @escaping () -> Void) {
        if shown != nil { waiting.append(go); return }
        if CellularSaver.answer == .none, CellularSaver.mode == .ask, let left = CellularSaver.mobileDataSettleLeftMs, left > 0 {
            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(left) + 50)) {
                MainActor.assumeIsolated { beforePlay(go) }
            }
            return
        }
        guard CellularSaver.shouldAsk("before Play"), present("before Play") else { go(); return }
        waiting.append(go)
    }

    /// Mobile data seen (or Dream is back in front): ask now when it's due and Dream can show it.
    static func consider(_ why: String) {
        guard shown == nil, SetupGate.isDone, UIApplication.shared.applicationState == .active else { return }
        let p = DreamPlayerPresenter.shared
        let place = p.isShowing ? "over the player" : p.isMinimized ? "over the mini player" : why
        guard CellularSaver.shouldAsk(place) else { return } // fresh read: really on mobile data right now
        _ = present(place)
    }

    /// Wi-Fi is back while the question is up: it goes away without an answer (nothing is set; the next mobile-data
    /// session asks again); a paused title resumes and a waiting Play goes ahead at full quality.
    private static func withdraw() {
        guard let vc = shown else { return }
        Log.core.info("Sources: cellular saver prompt withdrawn (back on Wi-Fi; \(CellularSaver.pathDescription, privacy: .public))")
        let paused = pausedSession
        pausedSession = nil
        let go = waiting
        waiting = []
        let done = {
            shown = nil
            if let paused, DreamPlayerPresenter.shared.currentSession === paused { paused.engine.play() }
            for g in go { g() }
        }
        if vc.presentingViewController != nil { vc.dismiss(animated: true) { MainActor.assumeIsolated { done() } } } else { done() }
    }

    // MARK: Showing

    private static var pausedSession: PlayerSession?

    @discardableResult
    private static func present(_ why: String) -> Bool {
        guard shown == nil, let top = topController() else { return false }
        // The player (full screen, mini player or Picture in Picture in front) pauses while the question is up.
        if let s = DreamPlayerPresenter.shared.currentSession, s.state.isPlaying || (s.state.isBuffering && !s.state.paused) {
            s.engine.pause()
            pausedSession = s
        }
        // Over the full-screen player (or one of its sheets): landscape like it; anywhere else portrait.
        let landscape = DreamPlayerPresenter.shared.isShowing || !top.supportedInterfaceOrientations.contains(.portrait)
        let view = CellularPromptView(limit: CellularSaver.limit) { use, limit, always, dontAsk in
            answered(use: use, limit: limit, always: always, dontAsk: dontAsk)
        }
        let vc = CellularPromptController(rootView: AnyView(view))
        vc.mask = landscape ? .landscape : .portrait
        vc.hideStatusBar = top.prefersStatusBarHidden
        vc.modalPresentationStyle = .overFullScreen
        vc.modalTransitionStyle = .crossDissolve
        vc.view.backgroundColor = .clear
        shown = vc
        top.present(vc, animated: true)
        CellularSaver.promptShown(why)
        return true
    }

    private static func answered(use: Bool, limit: String, always: Bool, dontAsk: Bool) {
        CellularSaver.answer(use: use, limit: use ? limit : nil, always: always, dontAsk: dontAsk)
        let vc = shown
        let paused = pausedSession
        pausedSession = nil
        let go = waiting
        waiting = []
        let done = {
            shown = nil
            // The playing title: a smaller copy at the same spot, or it plays on.
            if let s = DreamPlayerPresenter.shared.currentSession {
                if use, go.isEmpty { switchToSmaller(s, resume: paused === s) } else if paused === s { s.engine.play() }
            }
            for g in go { g() }
        }
        if let vc, vc.presentingViewController != nil { vc.dismiss(animated: true) { MainActor.assumeIsolated { done() } } } else { done() }
    }

    /// Use Data Saver over a title above the limit: the capped lookup (on the scrape Dream Core already kept), then the
    /// player's source switch at the same spot; nothing within the limit in time keeps the copy (Info says so).
    private static func switchToSmaller(_ s: PlayerSession, resume: Bool) {
        guard !s.isTrailer, let r = s.request, let cur = s.link, CellularSaver.above(cur.quality) else {
            if !s.isTrailer, let cur = s.link { CellularSaver.markPlaying(cur) } // already within it: Info says so
            if resume { s.engine.play() }
            return
        }
        let at = s.resumeSpot()
        Log.core.info("Sources: cellular saver switching \(r.title.name, privacy: .public) from \(cur.quality, privacy: .public) to a copy within \(CellularSaver.limit, privacy: .public) at \(Int(at), privacy: .public)s")
        Task { @MainActor in
            let found = await within(r, ms: switchWaitMs)
            guard DreamPlayerPresenter.shared.currentSession === s, let now = s.request, now.title.id == r.title.id,
                  now.season == r.season, now.episode == r.episode, s.link?.hash == cur.hash else { return }
            if let f = found, f.hash != cur.hash, !CellularSaver.above(f.quality) {
                Log.core.info("Sources: cellular saver switched \(r.title.name, privacy: .public) to \(f.quality, privacy: .public) at \(Int(at), privacy: .public)s")
                s.play(link: f, for: r, at: at)
            } else {
                CellularSaver.keptBigger(cur)
                if resume { s.engine.play() }
            }
        }
    }

    /// Dream Core's Play lookup for [r] with the saver's answer, or nil after [ms] (the lookup still finishes and
    /// remembers what it finds). Two unstructured tasks race (a task group would wait for the slower one).
    private static func within(_ r: PlayRequest, ms: Int) async -> StreamLink? {
        let once = Once()
        return await withCheckedContinuation { (c: CheckedContinuation<StreamLink?, Never>) in
            Task.detached {
                let l = try? await Services.shared.playback.resolve(r)
                once.run { c.resume(returning: l) }
            }
            Task.detached {
                try? await Task.sleep(for: .milliseconds(ms))
                once.run { c.resume(returning: nil) }
            }
        }
    }

    private static func wifiBack() {
        NoticeCenter.shared.post(key: "wifi-back:\(wallNow())", type: .info, title: "Back on Wi-Fi · Full Quality Again", body: "",
                                 store: false)
    }

    private static func topController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first { $0.isKeyWindow } ?? scenes.first?.windows.first
        var top = window?.rootViewController
        while let p = top?.presentedViewController, !p.isBeingDismissed { top = p }
        return top
    }
}

/// Holds the question over whatever is in front, in that screen's orientation (landscape over the player).
final class CellularPromptController: UIHostingController<AnyView> {
    var mask: UIInterfaceOrientationMask = .portrait
    var hideStatusBar = false
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { mask }
    override var prefersStatusBarHidden: Bool { hideStatusBar }
    override var prefersHomeIndicatorAutoHidden: Bool { hideStatusBar }
}

/// "You're on Mobile Data": dark glass card, the drawn antenna in a thin rainbow ring, the quality choice, the
/// buttons. Portrait: one column. Landscape (or any short screen): two columns, compact, centered; it scrolls rather
/// than clip if a screen is shorter still.
struct CellularPromptView: View {
    @State var limit: String
    let answer: (_ use: Bool, _ limit: String, _ always: Bool, _ dontAsk: Bool) -> Void
    @State private var always = false
    @State private var appeared = false

    init(limit: String, answer: @escaping (_ use: Bool, _ limit: String, _ always: Bool, _ dontAsk: Bool) -> Void) {
        _limit = State(initialValue: limit)
        self.answer = answer
    }

    var body: some View {
        GeometryReader { geo in
            let wide = geo.size.width > geo.size.height
            ZStack {
                Color.black.opacity(0.6).ignoresSafeArea()
                ViewThatFits(in: .vertical) {
                    card(wide: wide, width: geo.size.width)
                    ScrollView(showsIndicators: false) { card(wide: wide, width: geo.size.width).padding(.vertical, 12) }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .scaleEffect(appeared ? 1 : 0.96)
                .opacity(appeared ? 1 : 0)
            }
        }
        .environment(\.colorScheme, .dark)
        .onAppear { withAnimation(.spring(response: 0.4, dampingFraction: 0.86)) { appeared = true } }
    }

    private func card(wide: Bool, width: CGFloat) -> some View {
        Group {
            if wide {
                HStack(alignment: .center, spacing: 26) {
                    VStack(alignment: .leading, spacing: 12) {
                        mark(size: 46)
                        heading(size: 21, align: .leading)
                        explanation(align: .leading)
                        dontAsk(align: .leading).padding(.top, 2)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    VStack(spacing: 12) {
                        qualityChoice
                        alwaysRow
                        buttons
                    }
                    .frame(width: min(300, (width - 120) * 0.48))
                }
                .padding(.horizontal, 24).padding(.vertical, 20)
                .frame(maxWidth: min(680, width - 100))
            } else {
                VStack(spacing: 14) {
                    mark(size: 58)
                    heading(size: 22, align: .center)
                    explanation(align: .center)
                    qualityChoice.padding(.top, 4)
                    alwaysRow
                    buttons.padding(.top, 2)
                    dontAsk(align: .center)
                }
                .padding(.horizontal, 22).padding(.top, 24).padding(.bottom, 18)
                .frame(maxWidth: min(400, width - 32))
            }
        }
        .background {
            RoundedRectangle(cornerRadius: 26, style: .continuous).fill(.ultraThinMaterial)
            RoundedRectangle(cornerRadius: 26, style: .continuous).fill(Palette.surface.opacity(0.92))
        }
        .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).strokeBorder(Color.white.opacity(0.10), lineWidth: 1))
        .shadow(color: .black.opacity(0.55), radius: 24, y: 10)
    }

    private func mark(size: CGFloat) -> some View {
        ZStack {
            Circle().fill(Color.white.opacity(0.06))
            RainbowRing(lineWidth: 1.6)
            Image(systemName: "antenna.radiowaves.left.and.right")
                .font(.system(size: size * 0.4, weight: .regular))
                .foregroundStyle(Palette.pearl)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }

    private func heading(size: CGFloat, align: TextAlignment) -> some View {
        Text("You're on Mobile Data")
            .font(.system(size: size, weight: .bold))
            .foregroundStyle(Palette.text)
            .multilineTextAlignment(align)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func explanation(align: TextAlignment) -> some View {
        Text("Data Saver plays a smaller copy of each movie and show, so you use less data. It turns off by itself when you're back on Wi-Fi.")
            .font(.system(size: 15))
            .foregroundStyle(Palette.textSecondary)
            .multilineTextAlignment(align)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var qualityChoice: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Data Saver Quality")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Palette.textSecondary)
            HStack(spacing: 10) {
                ForEach(CellularSaver.limitChoices, id: \.id) { c in
                    Button { limit = c.id } label: { Text(c.label).lineLimit(1) }
                        .buttonStyle(PearlButtonStyle(selected: limit == c.id, fullWidth: true))
                        .accessibilityAddTraits(limit == c.id ? .isSelected : [])
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var alwaysRow: some View {
        Button { always.toggle() } label: {
            HStack(spacing: 12) {
                Text("Always Use on Mobile Data")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(Palette.text)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                PageSwitch(on: always)
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityValue(always ? "On" : "Off")
    }

    private var buttons: some View {
        VStack(spacing: 10) {
            Button { answer(true, limit, always, false) } label: { Text("Use Data Saver").lineLimit(1) }
                .buttonStyle(PearlButtonStyle(selected: true, fullWidth: true))
            Button { answer(false, limit, false, false) } label: { Text("Not Now").lineLimit(1) }
                .buttonStyle(PearlButtonStyle(fullWidth: true))
        }
    }

    private func dontAsk(align: TextAlignment) -> some View {
        VStack(alignment: align == .center ? .center : .leading, spacing: 3) {
            Button { answer(false, limit, false, true) } label: {
                Text("Don't Ask Again")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Palette.text.opacity(0.82))
                    .padding(.vertical, 6)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Text("You can change this in Settings › Sources & Quality.")
                .font(.system(size: 12))
                .foregroundStyle(Palette.textTertiary)
                .multilineTextAlignment(align)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: align == .center ? .center : .leading)
    }
}
