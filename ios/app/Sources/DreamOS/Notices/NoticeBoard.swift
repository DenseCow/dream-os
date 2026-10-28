import SwiftUI
import Observation
import UIKit
import DreamCore
import DreamUI

/// What the banner shows right now (DreamNoticesUi's banner views as state).
struct BannerState: Identifiable, Equatable {
    enum Face: Equatable {
        /// The notice's own drawn glyph.
        case glyph
        /// The rainbow loader of a live banner, while its work is under way.
        case working
        /// A live banner's result: a green check (good) or the red warning glyph.
        case result(good: Bool)
    }
    let id = UUID()
    var notice: Notice
    var face: Face = .glyph
    /// The icon alternates with the smiley every `NoticeBoard.swapSeconds` while the banner is up.
    var smiling = false
    /// A live banner's result line color (green good, red bad); nil = the usual white.
    var titleTint: Color?
    var live = false
    /// Bumped to make the severity halo glow once (on landing, and when a live banner shows its result).
    var glow = 0
}

/// A banner for work under way, changed in place when it's done (DreamNotices.Live). Main actor.
@MainActor
final class NoticeLive {
    fileprivate weak var board: NoticeBoard?
    fileprivate let bannerId: UUID
    fileprivate var done = false
    fileprivate init(board: NoticeBoard, bannerId: UUID) { self.board = board; self.bannerId = bannerId }

    /// The result in place: [title] in green with a check ([good], no haptic) or in red with the notice haptic; the
    /// banner leaves [hold] seconds later, then [then] runs.
    func finish(_ title: String, good: Bool, hold: Double = 3.5, then: (() -> Void)? = nil) {
        guard !done else { return }
        done = true
        board?.finishLive(bannerId, title: title, good: good, hold: hold, then: then)
    }

    /// Leaves quietly (nothing worth saying).
    func dismiss() {
        guard !done else { return }
        done = true
        board?.dismissLive(bannerId)
    }
}

/// Banners and the list's live state (DreamNoticesUi's presenter half). The host (NoticeHost in AppShell) tells it
/// when Dream OS is free; banners wait while it isn't (Dream Player, Settings, the Notifications list, background).
@MainActor @Observable
final class NoticeBoard {
    static let shared = NoticeBoard()

    /// A banner rests this long, then leaves on its own.
    static let bannerSeconds = 6.0
    /// Its icon swaps with the smiley this often (each swap a short morph).
    static let swapSeconds = 2.0

    /// Every notice, newest first (the list and the bell's badge).
    private(set) var items: [Notice] = NoticeCenter.shared.all()
    private(set) var unread: Int = NoticeCenter.shared.unreadCount()
    /// The banner on screen.
    private(set) var banner: BannerState?
    /// What the notices sheet shows (the list, a renewal page, Accounts); nil = closed.
    var route: NoticeRoute?
    /// The list is open somewhere (the sheet, or pushed inside Settings): banners wait.
    var listOpen = 0
    /// Set by the host: Dream OS's own screen is in front and nothing covers it.
    @ObservationIgnored var isFree: () -> Bool = { false }
    /// Set by the host: runs a notice's action (opens its page). False when nothing could run it.
    @ObservationIgnored var runAction: (Notice) -> Void = { _ in }

    @ObservationIgnored private var pending: [Notice] = []
    @ObservationIgnored private var queue: [Notice] = []
    @ObservationIgnored private var hideTask: Task<Void, Never>?
    @ObservationIgnored private var swapTask: Task<Void, Never>?
    @ObservationIgnored private var retryTask: Task<Void, Never>?
    @ObservationIgnored private var leaving = false

    private init() {}

    func canShow() -> Bool { isFree() && route == nil && listOpen == 0 }

    // ---- the store changed ----

    func changed(dropStale: Bool = false) {
        items = NoticeCenter.shared.all()
        unread = items.filter { !$0.read }.count
        if dropStale { pending.removeAll { !NoticeCenter.shared.stillNew($0.key) } }
    }

    // ---- banners ----

    /// A new notice: shown now, or once Dream OS is free.
    func enqueue(_ n: Notice) {
        pending.removeAll { $0.key == n.key }
        pending.append(n)
        flush()
    }

    /// Banners that waited show now, in order. Several waited (a long movie): the newest two, not a parade.
    func flush() {
        guard !pending.isEmpty else { return }
        guard canShow() else { armRetry(); return }
        let show = pending.suffix(2)
        pending.removeAll()
        queue.append(contentsOf: show)
        if banner == nil { next() }
    }

    /// Dream OS is free again (back in front, a sheet closed, the player gone): waiting banners slide in after a moment.
    func homeBack() {
        retryTask?.cancel()
        retryTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1.4))
            guard let self, !Task.isCancelled else { return }
            self.retryTask = nil
            if self.banner == nil, !self.queue.isEmpty, self.canShow() { self.next() }
            self.flush()
        }
    }

    /// Dream OS went to the background: the banner goes at once; anything still queued waits.
    func paused() { hide(animated: false) }

    /// Something still covers Dream OS: look again every 2 s for a couple of minutes.
    private func armRetry() {
        guard retryTask == nil else { return }
        retryTask = Task { [weak self] in
            for _ in 0..<60 {
                try? await Task.sleep(for: .seconds(2))
                guard let self, !Task.isCancelled else { return }
                if self.canShow() {
                    self.retryTask = nil
                    if self.banner == nil, !self.queue.isEmpty { self.next() }
                    self.flush()
                    return
                }
            }
            self?.retryTask = nil
        }
    }

    private func next() {
        guard banner == nil, !queue.isEmpty else { return }
        let n = queue.removeFirst()
        guard NoticeCenter.shared.stillNew(n.key), canShow() else {
            NoticeCenter.shared.bannerDone(n.key)
            next()
            return
        }
        NoticeCenter.shared.bannerDone(n.key) // a banner-only notice lives only as this banner
        leaving = false
        banner = BannerState(notice: n, glow: 1)
        NoticeHaptics.arrived(n.severity)
        startSwaps()
        scheduleHide(after: Self.bannerSeconds)
    }

    private func scheduleHide(after seconds: Double, then: (() -> Void)? = nil) {
        hideTask?.cancel()
        hideTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard let self, !Task.isCancelled else { return }
            self.hide(animated: true)
            if let then {
                try? await Task.sleep(for: .seconds(0.45))
                then()
            }
        }
    }

    /// Every `swapSeconds` while the banner is up, its icon morphs between its glyph and the smiley. One timer, nothing
    /// per frame in between. Each tick also checks that Dream OS is still free (Play covers it: the banner goes at once).
    private func startSwaps() {
        swapTask?.cancel()
        swapTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(Self.swapSeconds))
                guard let self, !Task.isCancelled, self.banner != nil, !self.leaving else { return }
                if !self.isFree() { self.hide(animated: false); return }
                if self.banner?.face == .working { continue }
                self.banner?.smiling.toggle()
            }
        }
    }

    /// Takes the banner away ([animated] off: at once). It always ends on the smiley: if the glyph is up as it leaves, one
    /// last morph first, then a short rest. The next waiting banner follows.
    func hide(animated: Bool, quick: Bool = false) {
        hideTask?.cancel(); hideTask = nil
        swapTask?.cancel(); swapTask = nil
        guard banner != nil, !leaving || !animated else { return }
        if !animated {
            var t = Transaction(); t.disablesAnimations = true
            withTransaction(t) { banner = nil }
            leaving = false
            return
        }
        leaving = true
        let lastMorph = !quick && banner?.smiling == false && banner?.face != .working
        if lastMorph { banner?.smiling = true }
        let id = banner?.id
        Task { [weak self] in
            if lastMorph { try? await Task.sleep(for: .seconds(1.4)) }
            guard let self, self.banner?.id == id else { return }
            self.banner = nil
            self.leaving = false
            try? await Task.sleep(for: .seconds(0.42))
            if self.banner == nil, !self.queue.isEmpty, self.canShow() { self.next() }
        }
    }

    /// The banner was tapped: it's read, it leaves, and its action runs (else the list opens for a kept notice).
    func tapBanner() {
        guard let b = banner, !b.live || b.face != .working else { return }
        let n = b.notice
        hide(animated: true, quick: true)
        guard !b.live else { return }
        NoticeCenter.shared.markRead(n.key)
        if n.action != nil { runAction(n) }
        else if NoticeCenter.shared.find(n.key) != nil { route = .list }
    }

    /// Swiped up: it leaves at once (still unread in the list).
    func swipeAway() { hide(animated: true, quick: true) }

    // ---- live banners ----

    /// A live banner right now: the usual banner, the rainbow loader in its icon slot ("Checking for Updates…"), never
    /// stored. Nil when Dream OS isn't free or another banner is up.
    func live(_ title: String) -> NoticeLive? {
        guard banner == nil, canShow() else { return nil }
        let n = Notice(key: "live:\(wallNow())", type: .info, title: title, body: "", severity: .info, at: wallNow(), read: true, action: nil)
        leaving = false
        let b = BannerState(notice: n, face: .working, live: true)
        banner = b
        startSwaps()
        return NoticeLive(board: self, bannerId: b.id)
    }

    fileprivate func finishLive(_ id: UUID, title: String, good: Bool, hold: Double, then: (() -> Void)?) {
        guard banner?.id == id, !leaving else { then?(); return } // it went away meanwhile: the next step decides again
        withAnimation(.easeOut(duration: 0.22)) {
            banner?.notice.title = title
            banner?.titleTint = good ? Palette.greenText : Palette.redText
            banner?.notice.severity = good ? .info : .critical
            banner?.face = .result(good: good)
            banner?.smiling = false
            banner?.glow += 1
        }
        if !good { NoticeHaptics.arrived(.critical) }
        startSwaps()
        scheduleHide(after: hold, then: then)
    }

    fileprivate func dismissLive(_ id: UUID) {
        guard banner?.id == id else { return }
        hide(animated: true, quick: true)
    }
}

/// What the notices sheet shows.
enum NoticeRoute: Identifiable, Hashable {
    case list
    /// A service's renewal page; [key]: the notice it came from (offers Remove Notice).
    case renew(service: String, key: String?)
    case accounts
    var id: String {
        switch self {
        case .list: "list"
        case .renew(let s, let k): "renew:\(s):\(k ?? "")"
        case .accounts: "accounts"
        }
    }
}

/// The TV's notice sound becomes a subtle haptic on the phone (Settings: Haptic for New Notices).
@MainActor
enum NoticeHaptics {
    static func arrived(_ s: Notice.Severity) {
        guard NoticePrefs.haptic else { return }
        switch s {
        case .critical: UINotificationFeedbackGenerator().notificationOccurred(.warning)
        case .warning: UIImpactFeedbackGenerator(style: .medium).impactOccurred(intensity: 0.7)
        case .info: UIImpactFeedbackGenerator(style: .soft).impactOccurred(intensity: 0.6)
        }
    }
}

/// The two settings the TV keeps for notices (Theme.NOTICE_SOUND, Theme.ACCOUNT_SUMMARY), both on by default.
enum NoticePrefs {
    static var haptic: Bool {
        get { DreamStore.shared.get(Bool.self, "notices.haptic") ?? true }
        set { DreamStore.shared.set(newValue, "notices.haptic") }
    }
    static var accountSummary: Bool {
        get { DreamStore.shared.get(Bool.self, "notices.summary") ?? true }
        set { DreamStore.shared.set(newValue, "notices.summary") }
    }
}
