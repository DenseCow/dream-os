import Foundation
import DreamCore
import DreamPlayback

/// Where notices come from: premium expiry and account limits (DebridExpiry, Dream Core), the startup "Your Accounts"
/// summary, and New Episodes (NewEpisodeSpot). Installed once by NoticeHost.
@MainActor
enum NoticeSources {
    private static var installed = false
    private static var summaryDone = false
    private static let launchMs = wallNow()

    static func install() {
        guard !installed else { return }
        installed = true
        DebridExpiry.onNotice = { n in NoticeSources.post(n) }
        DebridExpiry.onDismissPrefix = { prefix in NoticeCenter.shared.dismissPrefix(prefix) }
    }

    /// Dream OS came to the front: the premium check when due (every six hours), the day's summary once per start,
    /// New Episodes when stale.
    static func appActive() {
        DreamPlayback.appActive()
        if !summaryDone {
            summaryDone = true
            Task { await startupSummary() }
        }
        NewEpisodeSpot.shared.refreshIfStale()
        // Settings > About's update line (GitHub's latest release, every six hours at most; a notice once per version).
        DreamUpdates.shared.checkIfDue()
    }

    /// DebridExpiry / Dream Core's notice: one live notice per service (a newer threshold replaces the earlier one).
    nonisolated static func post(_ n: DebridNotice) {
        let kind: Notice.Kind = n.key.hasPrefix("limit:") ? .limit : .expiry
        if let prefix = n.replacesPrefix { NoticeCenter.shared.dismissPrefix(prefix, except: n.key) }
        let sev: Notice.Severity = switch n.severity { case .info: .info; case .warning: .warning; case .critical: .critical }
        NoticeCenter.shared.post(key: n.key, type: kind, title: n.title, body: n.body, severity: sev,
                                 action: n.action.map { Notice.Action(label: "Renew", target: $0) })
    }

    /// On every start, after the first premium round: "Your Accounts", every linked service with its time left, soonest
    /// first ("Real-Debrid: 96 Days"). Uses this round's answers, else ones under six hours old; nothing known: skipped.
    /// A banner only. Notifications > Account Summary at Startup turns it off.
    private static func startupSummary() async {
        guard NoticePrefs.accountSummary else { return }
        // Wait for the round this start began (it saves its time), at most 40 s.
        for _ in 0..<20 {
            if (DreamStore.shared.get(Int64.self, "expiry._last") ?? 0) >= launchMs - 1_000 { break }
            try? await Task.sleep(for: .seconds(2))
        }
        let now = wallNow()
        let known = DebridExpiry.services.filter { DebridAccounts.linked($0) }
            .compactMap { DebridExpiry.status($0) }.filter { now - $0.checkedAt < 6 * 3_600_000 }
        guard !known.isEmpty else { return }
        let lines = known.sorted { ($0.until > 0 ? $0.until : .max) < ($1.until > 0 ? $1.until : .max) }.map { s -> String in
            let left = ExpiryRules.timeLeft(now: now, until: s.until, premium: s.premium, subscribed: s.subscribed)
            return "\(DebridAccounts.name(s.service)): \(s.subscribed && left != "Expired" ? "Renews Automatically" : left)"
        }
        // A premium warning just took the banner: the summary keeps quiet this time.
        let warned = NoticeBoard.shared.banner.map { $0.notice.type == .expiry } ?? false
        NoticeCenter.shared.post(key: "summary:\(DreamTime.todayYmd())", type: .summary, title: "Your Accounts",
                                 body: lines.joined(separator: "\n"), banner: !warned, replace: true, store: false)
    }
}
