import SwiftUI
import DreamCore
import DreamUI
import DreamPlayback

/// The notices sheet (the bell, a banner's action): its own navigation stack and a Done button.
struct NoticeSheet: View {
    let route: NoticeRoute
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                switch route {
                case .list: NoticesView()
                case .renew(let s, let k): NoticeRenewView(service: s, noticeKey: k)
                case .accounts: AccountsView()
                }
            }
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .dreamNavigationLook()
        .preferredColorScheme(.dark)
    }
}

/// Notifications (DreamNoticesUi's panel for touch): rows with their drawn icon, title, detail and "2 Days Ago";
/// Mark All Read and Clear All; tap runs a row's action, swipe removes it. Pushes inside any NavigationStack.
struct NoticesView: View {
    @State private var renew: NoticeRoute?
    @State private var accounts = false
    @State private var summary = NoticePrefs.accountSummary
    @State private var haptic = NoticePrefs.haptic
    /// The bar's black glass, up once the list scrolls under the (collapsed) title.
    @State private var bar = BarGlassModel(lead: 0, fade: 20)
    private var board: NoticeBoard { NoticeBoard.shared }

    var body: some View {
        ZStack {
            DreamBackground()
            List {
                header
                    .barGlassMarker(bar)
                    .listRowInsets(EdgeInsets(top: 4, leading: 20, bottom: 8, trailing: 20))
                    .plainRow()
                if board.items.isEmpty {
                    empty.plainRow()
                } else {
                    ForEach(board.items) { n in
                        NoticeRow(notice: n) { open(n) }
                            .listRowInsets(EdgeInsets(top: 5, leading: 20, bottom: 5, trailing: 20))
                            .plainRow()
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                Button(role: .destructive) { NoticeCenter.shared.dismiss(n.key) } label: { Label("Remove", systemImage: "trash") }
                            }
                            .contextMenu {
                                if !n.read { Button("Mark as Read", systemImage: "envelope.open") { NoticeCenter.shared.markRead(n.key) } }
                                Button("Remove Notice", systemImage: "trash", role: .destructive) { NoticeCenter.shared.dismiss(n.key) }
                            }
                    }
                }
                settings
                    .listRowInsets(EdgeInsets(top: 22, leading: 20, bottom: 30, trailing: 20))
                    .plainRow()
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .scrollIndicators(.hidden)
            .animation(.easeOut(duration: 0.22), value: board.items)
        }
        .navigationTitle("Notifications")
        .navigationBarTitleDisplayMode(.large)
        .barGlass(bar)
        .navigationDestination(item: $renew) { r in
            if case .renew(let s, let k) = r { NoticeRenewView(service: s, noticeKey: k) }
        }
        .navigationDestination(isPresented: $accounts) { AccountsView() }
        .onAppear { board.listOpen += 1; board.changed() }
        .onDisappear { board.listOpen = max(0, board.listOpen - 1); board.homeBack() }
    }

    private var countLine: String {
        let unread = board.unread
        if board.items.isEmpty { return "Nothing New" }
        return unread == 0 ? "All Caught Up" : unread == 1 ? "1 Unread" : "\(unread) Unread"
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(countLine).font(.system(size: 15)).foregroundStyle(Palette.textSecondary)
            if !board.items.isEmpty {
                HStack(spacing: 10) {
                    Button("Mark All Read") { PageHaptics.tap(); NoticeCenter.shared.markAllRead() }
                        .buttonStyle(ChipButtonStyle(selected: false))
                        .disabled(board.unread == 0)
                        .opacity(board.unread == 0 ? 0.5 : 1)
                    Button("Clear All") { PageHaptics.tap(); NoticeCenter.shared.clearAll() }
                        .buttonStyle(ChipButtonStyle(selected: false))
                }
            }
        }
    }

    private var empty: some View {
        VStack(spacing: 16) {
            ZStack {
                Circle().fill(LinearGradient(colors: [Color(hex: 0x1C1C1F), Color(hex: 0x0E0E10)], startPoint: .topLeading, endPoint: .bottomTrailing))
                Circle().strokeBorder(Rainbow.gradient, lineWidth: 1.8)
                NoticeGlyph(kind: .bell, weight: 5).frame(width: 44, height: 44)
            }
            .frame(width: 96, height: 96)
            Text("You're All Caught Up").font(.system(size: 20, weight: .semibold)).foregroundStyle(Palette.text)
            Text("Account alerts and other news from Dream OS show up here.")
                .font(.system(size: 15)).foregroundStyle(Palette.textSecondary).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 36)
        .padding(.horizontal, 24)
    }

    private var settings: some View {
        VStack(alignment: .leading, spacing: 0) {
            PageSection(title: "Settings")
            VStack(spacing: PageSpace.row) {
                PageRow(title: "Account Summary at Startup", caption: "Your Accounts and Their Time Left, Once per Start",
                        lead: .symbol("calendar"), toggle: summary) {
                    summary.toggle(); NoticePrefs.accountSummary = summary
                }
                PageRow(title: "Haptic for New Notices", caption: "A Light Tap as a Notice Arrives",
                        lead: .symbol("hand.tap"), toggle: haptic) {
                    haptic.toggle(); NoticePrefs.haptic = haptic
                }
            }
            .padding(.top, 10)
        }
    }

    private func open(_ n: Notice) {
        NoticeCenter.shared.markRead(n.key)
        guard let target = n.action?.target else { return }
        if target.hasPrefix("renew:") { renew = .renew(service: String(target.dropFirst(6)), key: n.key) }
        else if target == "accounts" { accounts = true }
        else { board.runAction(n) }
    }
}

private extension View {
    func plainRow() -> some View {
        listRowBackground(Color.clear).listRowSeparator(.hidden)
    }
}

/// One notice: its drawn icon on a soft disc, the title (bold while unread, with the pearl unread dot), the detail or the
/// summary's columns, and "2 Days Ago · Renew". Pressed, the pearl face with ink.
struct NoticeRow: View {
    let notice: Notice
    var action: () -> Void

    var body: some View {
        Button(action: { PageHaptics.tap(); action() }) { NoticeRowContent(notice: notice) }
            .buttonStyle(PageRowStyle())
    }
}

private struct NoticeRowContent: View {
    let notice: Notice
    @Environment(\.pageRowLit) private var lit

    var body: some View {
        let n = notice
        let accent = n.severity == .info ? Color.white : NoticeStyle.severityColor(n.severity)
        HStack(alignment: .top, spacing: 16) {
            NoticeGlyph(kind: NoticeGlyphKind.row(n), color: lit ? Palette.ink : (n.severity == .info ? Color.white.opacity(0.9) : accent), weight: 7)
                .padding(10)
                .frame(width: 42, height: 42)
                .background(Circle().fill((lit ? Palette.ink : accent).opacity(0.14)))
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 12) {
                    Text(NoticeStyle.colorize(n.title, base: lit ? Palette.ink : (n.read ? Color.white.opacity(0.86) : Palette.text), plain: lit))
                        .font(.system(size: 16, weight: n.read ? .medium : .semibold))
                        .lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if !n.read {
                        Circle().fill(lit ? Palette.ink : Palette.pearl).frame(width: 8, height: 8)
                    }
                }
                if n.isSummary {
                    NoticeSummaryGrid(text: n.body, size: 14.5, ink: lit).padding(.top, 8)
                } else if !n.body.isEmpty {
                    Text(NoticeStyle.colorize(n.body, base: lit ? Palette.inkDim : NoticeStyle.detail, plain: lit))
                        .font(.system(size: 14))
                        .lineSpacing(2)
                        .lineLimit(3)
                        .padding(.top, 6)
                }
                Text(NoticeStyle.meta(n))
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundStyle(lit ? Palette.inkDim : Color.white.opacity(0.5))
                    .lineLimit(1)
                    .padding(.top, 8)
            }
        }
        .padding(.leading, 14).padding(.trailing, 16).padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .multilineTextAlignment(.leading)
    }
}

/// The renewal page a premium-expiry notice opens (the TV's QR sheet; on the phone the page opens directly): the
/// service's end date, its renewal page, Remove Notice.
struct NoticeRenewView: View {
    let service: String
    var noticeKey: String?
    @Environment(\.openURL) private var openURL
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let name = DebridServices.name(service)
        let url = DebridExpiry.renewUrl(service)
        let n = noticeKey.flatMap { NoticeCenter.shared.find($0) }
        PageScaffold(title: "Renew \(name)") {
            VStack(alignment: .leading, spacing: 18) {
                ZStack {
                    Circle().fill(LinearGradient(colors: [Color(hex: 0x1C1C1F), Color(hex: 0x0E0E10)], startPoint: .topLeading, endPoint: .bottomTrailing))
                    Circle().strokeBorder(Rainbow.gradient, lineWidth: 1.8)
                    NoticeGlyph(kind: .hourglass, weight: 5).frame(width: 46, height: 46)
                }
                .frame(width: 96, height: 96)
                .frame(maxWidth: .infinity)
                .padding(.bottom, 4)
                if let status = DebridExpiry.caption(service) {
                    Text(NoticeStyle.colorize(status, base: n.map { $0.severity == .info ? Color.white.opacity(0.8) : NoticeStyle.severityColor($0.severity) }
                                                ?? Color.white.opacity(0.8)))
                        .font(.system(size: 16, weight: .semibold))
                }
                PageNote(text: "Open \(name)'s renewal page to keep your premium plan, and streaming, going.")
                Text(url.absoluteString.replacingOccurrences(of: "https://", with: "").trimmingCharacters(in: CharacterSet(charactersIn: "/")))
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Palette.text)
                    .padding(.horizontal, 6)
                VStack(spacing: 10) {
                    PagePrimaryButton(title: "Open Renewal Page", systemImage: "safari") { openURL(url) }
                    if let key = noticeKey, NoticeCenter.shared.find(key) != nil {
                        PageSecondaryButton(title: "Remove Notice") { NoticeCenter.shared.dismiss(key); dismiss() }
                    }
                }
                .padding(.top, 8)
            }
        }
    }
}
