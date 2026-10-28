import SwiftUI
import DreamCore
import DreamUI
import DreamPlayer

/// The banner (DreamNoticesUi's banner for touch): black smoked glass that slides down from the top, as wide as its
/// words, the drawn icon in its rainbow ring, the title and one detail line. Tap acts, swipe up dismisses.
/// Compact like iOS's own banners (owner, 2026-10-28): a pill about 52 pt tall, small icon and type, tight padding.
/// The startup summary (several lines) keeps the same look as a rounded card.
struct NoticeBanner: View {
    let state: BannerState

    private var summary: Bool { state.notice.isSummary }
    private var radius: CGFloat { summary ? 20 : 26 }

    var body: some View {
        HStack(spacing: 10) {
            NoticeFace(state: state, size: 30)
            VStack(alignment: .leading, spacing: 1) {
                Text(NoticeStyle.colorize(state.notice.title, base: state.titleTint ?? Palette.text, plain: state.titleTint != nil))
                    .font(.system(size: 14, weight: .semibold))
                    .lineLimit(1)
                    .contentTransition(.opacity)
                if summary {
                    NoticeSummaryGrid(text: state.notice.body, size: 12).padding(.top, 3)
                } else if !state.notice.body.isEmpty {
                    Text(NoticeStyle.colorize(state.notice.body, base: NoticeStyle.detail))
                        .font(.system(size: 12.5, weight: .medium))
                        .lineLimit(1)
                }
            }
        }
        .padding(.leading, 8).padding(.trailing, 16).padding(.vertical, summary ? 9 : 7)
        .frame(minHeight: 44)
        .background {
            RoundedRectangle(cornerRadius: radius, style: .continuous).fill(.ultraThinMaterial)
            RoundedRectangle(cornerRadius: radius, style: .continuous).fill(NoticeStyle.smoke.opacity(0.88))
        }
        .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(Color.white.opacity(0.10), lineWidth: 1))
        .shadow(color: .black.opacity(0.5), radius: 12, y: 5)
        .environment(\.colorScheme, .dark)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}

/// Hosts the banner over the shell and the notices sheet, and tells NoticeBoard when Dream OS is free. One, in AppShell.
struct NoticeHost: View {
    @Environment(ShellModel.self) private var shell
    @Environment(\.scenePhase) private var phase
    @State private var drag: CGFloat = 0
    @State private var active = true
    private var board: NoticeBoard { NoticeBoard.shared }

    var body: some View {
        @Bindable var board = board
        ZStack(alignment: .top) {
            if let b = board.banner {
                NoticeBanner(state: b)
                    .frame(maxWidth: 400)
                    .offset(y: min(0, drag))
                    .opacity(drag < -20 ? max(0.2, 1 + Double(drag) / 120) : 1)
                    .contentShape(Rectangle())
                    .onTapGesture { board.tapBanner() }
                    .gesture(DragGesture(minimumDistance: 8)
                        .onChanged { drag = min(0, $0.translation.height) }
                        .onEnded { g in
                            if g.translation.height < -28 || g.predictedEndTranslation.height < -70 { board.swipeAway() }
                            withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { drag = 0 }
                        })
                    .id(b.id)
                    .transition(.asymmetric(
                        insertion: .move(edge: .top).combined(with: .scale(scale: 0.96, anchor: .top)).combined(with: .opacity),
                        removal: .move(edge: .top).combined(with: .opacity)))
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 16)
        .padding(.top, 2)
        .animation(.spring(response: 0.56, dampingFraction: 0.78), value: board.banner?.id)
        .sheet(item: $board.route, onDismiss: { board.homeBack() }) { route in
            NoticeSheet(route: route)
        }
        .onAppear {
            board.isFree = { [shell] in
                UIApplication.shared.applicationState == .active && !shell.showSettings && !DreamPlayerPresenter.shared.isShowing
            }
            board.runAction = { [shell] n in NoticeActions.run(n, shell: shell) }
            NoticeSources.install()
            NoticeSources.appActive()
            board.homeBack()
        }
        .onChange(of: phase) { _, p in
            if p == .active { NoticeSources.appActive(); board.homeBack() }
            else if p == .background { board.paused() }
        }
        .onChange(of: shell.showSettings) { _, open in if !open { board.homeBack() } }
    }
}

/// Runs a notice's action target (see NoticeCenter).
@MainActor
enum NoticeActions {
    static func run(_ n: Notice, shell: ShellModel) {
        guard let target = n.action?.target else { return }
        let board = NoticeBoard.shared
        if target.hasPrefix("renew:") {
            board.route = .renew(service: String(target.dropFirst(6)), key: n.key)
        } else if target == "accounts" {
            board.route = .accounts
        } else if target == "home" {
            board.route = nil
            shell.showSettings = false
            shell.select(.home)
            if !shell.homePath.isEmpty { shell.homePath = [] }
        } else if let t = title(target) {
            board.route = nil
            shell.showSettings = false
            shell.tab = .home
            shell.visited.insert(.home)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { shell.open(t) }
        }
    }

    /// "title:tv:1396:Breaking Bad".
    static func title(_ target: String) -> Title? {
        let p = target.split(separator: ":", maxSplits: 3, omittingEmptySubsequences: false)
        guard p.count >= 3, p[0] == "title", let kind = MediaKind(rawValue: String(p[1])), let id = Int(p[2]) else { return nil }
        return Title(id: id, kind: kind, name: p.count > 3 ? String(p[3]) : "")
    }

    static func titleTarget(_ t: Title) -> String { "title:\(t.kind.rawValue):\(t.id):\(t.name)" }
}
