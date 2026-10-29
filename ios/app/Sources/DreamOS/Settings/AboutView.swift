import SwiftUI
import Observation
import DreamCore
import DreamUI

// Settings > About: what Dream is, its version (read from the bundle at run time, never written in code), whether a
// newer version is out (GitHub's latest release of the public repository), the source code and license, the
// disclaimer and the data credits. Dream for iPhone has no updater of its own: a new version comes from the release
// page (and SideStore re-installs it), so About only says what's out and opens the page.

/// The installed version, from Info.plist (tools/set-version.sh writes it; the same number as the Fire Stick's).
enum AppVersion {
    /// "4.1" (CFBundleShortVersionString).
    static var short: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "" }
    /// "40100" (CFBundleVersion, the build number: major*10000 + minor*100 + patch).
    static var build: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "" }
    /// "Version 4.1".
    static var text: String { short.isEmpty ? "" : "Version \(short)" }
    /// "Version 4.1 (40100)".
    static var full: String {
        guard !short.isEmpty else { return "" }
        return build.isEmpty || build == short ? text : "\(text) (\(build))"
    }
    /// The build channel (Info.plist DreamBuildChannel): "PUBLIC" only in builds published by dream-os-public's
    /// tools/release.sh (its IPA step writes it); every other build, the ones installed straight on a phone, is "DEV".
    static var isPublic: Bool {
        (Bundle.main.object(forInfoDictionaryKey: "DreamBuildChannel") as? String)?.uppercased() == "PUBLIC"
    }
    /// "(PUBLIC)" / "(DEV)".
    static var channelTag: String { isPublic ? "(PUBLIC)" : "(DEV)" }
    /// Status green for PUBLIC, status red for DEV (STANDARDS §2).
    static var channelColor: Color { isPublic ? Palette.green : Palette.red }
}

/// "Version 4.1 (DEV)": the version in [color], the channel tag in its status color (launch intro, Settings > About).
struct VersionTag: View {
    var size: CGFloat = 13
    var color: Color = Palette.textTertiary
    var body: some View {
        if !AppVersion.short.isEmpty {
            (Text(AppVersion.text + " ").foregroundColor(color)
             + Text(AppVersion.channelTag).foregroundColor(AppVersion.channelColor).fontWeight(.semibold))
                .font(.system(size: size, weight: .medium))
                .lineLimit(1)
                .fixedSize()
                .accessibilityLabel("\(AppVersion.text), \(AppVersion.isPublic ? "Public" : "Developer") Build")
        }
    }
}

/// The public repository's addresses.
enum DreamLinks {
    static let repo = "DenseCow/dream-os"
    static let source = URL(string: "https://github.com/\(repo)")!
    static let releases = URL(string: "https://github.com/\(repo)/releases")!
    static let latest = URL(string: "https://github.com/\(repo)/releases/latest")!
    static let disclaimer = URL(string: "https://github.com/\(repo)/blob/main/DISCLAIMER.md")!
    static let api = URL(string: "https://api.github.com/repos/\(repo)/releases/latest")!
    static let sideStore = URL(string: "sidestore://")!
}

/// Is a newer Dream out? Asks GitHub for the public repository's latest release (no account, a few hundred bytes with
/// an ETag), at most every six hours on its own (`checkIfDue`, when Dream comes to the front) or when the viewer asks
/// (About's Check Again). Offline it fails quietly and keeps the last answer. A newer version posts one Dream notice
/// per version.
@MainActor @Observable
final class DreamUpdates {
    static let shared = DreamUpdates()

    enum Status: Equatable {
        case unknown, upToDate, available(String), failed
    }

    private struct Saved: Codable {
        var latest: String?
        var page: String?
        var etag: String?
        /// The last answer from GitHub, ms since 1970 (0 = never).
        var checkedAt: Int64 = 0
        /// The last try didn't get an answer.
        var failed = false
    }

    private static let key = "about.update"
    private static let every: Int64 = 6 * 3_600_000

    private var saved: Saved
    private(set) var checking = false

    private init() {
        saved = DreamStore.shared.get(Saved.self, Self.key) ?? Saved()
    }

    var status: Status {
        if saved.failed && saved.checkedAt == 0 { return .failed }
        if let v = saved.latest, Self.newer(v, than: AppVersion.short) { return .available(v) }
        if saved.failed { return .failed }
        return saved.checkedAt > 0 ? .upToDate : .unknown
    }

    /// When GitHub last answered.
    var checkedAt: Date? { saved.checkedAt > 0 ? Date(timeIntervalSince1970: Double(saved.checkedAt) / 1000) : nil }

    /// The newest release's page (falls back to the latest-release address).
    var releasePage: URL { saved.page.flatMap(URL.init(string:)) ?? DreamLinks.latest }

    /// On its own: only when the last answer is over six hours old (or the last try failed and an hour has passed).
    func checkIfDue() {
        let age = wallNow() - saved.checkedAt
        guard !checking, age >= Self.every || (saved.failed && age >= 3_600_000) else { return }
        Task { await check() }
    }

    /// Asks GitHub now.
    func check() async {
        guard !checking else { return }
        checking = true
        defer { checking = false }
        var req = URLRequest(url: DreamLinks.api, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 12)
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.setValue("Dream-iPhone/\(AppVersion.short)", forHTTPHeaderField: "User-Agent")
        if saved.latest != nil, let tag = saved.etag { req.setValue(tag, forHTTPHeaderField: "If-None-Match") }
        var next = saved
        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            switch code {
            case 200:
                let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
                let tag = (o["tag_name"] as? String) ?? (o["name"] as? String) ?? ""
                guard let v = Self.number(tag) else { throw URLError(.cannotParseResponse) }
                next.latest = v
                next.page = (o["html_url"] as? String).flatMap { $0.hasPrefix("https://github.com/") ? $0 : nil }
                next.etag = (resp as? HTTPURLResponse)?.value(forHTTPHeaderField: "ETag")
            case 304:
                break // same release as last time
            case 404:
                next.latest = nil; next.page = nil; next.etag = nil // nothing published yet
            default:
                throw URLError(.badServerResponse)
            }
            next.checkedAt = wallNow()
            next.failed = false
            Log.app.notice("About: version check: installed \(AppVersion.short, privacy: .public), latest \(next.latest ?? "none", privacy: .public) (\(code))")
        } catch {
            next.failed = true
            Log.app.notice("About: version check failed: \(error.localizedDescription, privacy: .public)")
        }
        saved = next
        DreamStore.shared.set(saved, Self.key)
        if case .available(let v) = status {
            // Once per version (a notice key is posted only once, even after it's removed).
            NoticeCenter.shared.post(key: "update:\(v)", type: .update, title: "There's a New Version Available",
                                     body: "Version \(v) is out. Settings › About has the link.", severity: .info)
        }
    }

    /// "4.1" out of "v4.1", "Dream OS 4.1.2", "4.1-beta".
    static func number(_ s: String) -> String? {
        guard let r = s.range(of: #"\d+(?:\.\d+)*"#, options: .regularExpression) else { return nil }
        return String(s[r])
    }

    /// True when version [a] is newer than [b], part by part as numbers (4.10 > 4.9).
    static func newer(_ a: String, than b: String) -> Bool {
        guard let x = number(a)?.split(separator: ".").map({ Int($0) ?? 0 }) else { return false }
        guard let y = number(b)?.split(separator: ".").map({ Int($0) ?? 0 }) else { return true }
        for i in 0..<max(x.count, y.count) {
            let d = (i < x.count ? x[i] : 0) - (i < y.count ? y[i] : 0)
            if d != 0 { return d > 0 }
        }
        return false
    }

    /// "Today at 6:42 PM", "Yesterday at 9:05 AM", "Oct 21 at 8:05 PM" (Dream's time zone and clock format).
    static func whenText(_ d: Date) -> String {
        if Date().timeIntervalSince(d) < 60 { return "Just Now" }
        let t = DreamTime.formatTime(d)
        switch DreamTime.dayDelta(d) {
        case 0: return "Today at \(t)"
        case -1: return "Yesterday at \(t)"
        default: return "\(DreamTime.format(d, "MMM d")) at \(t)"
        }
    }

    /// Settings' About row: "Up to Date", "Version 4.3 Available", "Couldn't Check", or nil before the first check.
    var rowCaption: String? {
        switch status {
        case .upToDate: "Up to Date"
        case .available(let v): "Version \(v) Available"
        case .failed: "Couldn't Check for Updates"
        case .unknown: nil
        }
    }
}

struct AboutView: View {
    @Environment(\.openURL) private var openURL
    private var updates = DreamUpdates.shared

    var body: some View {
        PageScaffold(title: "About") {
            VStack(alignment: .leading, spacing: 0) {
                header
                section("Updates")
                updateCard
                section("Open Source")
                VStack(spacing: PageSpace.row) {
                    PageRow(title: "Source Code", caption: "Open Source · GPL-3.0", value: "GitHub",
                            lead: .symbol("chevron.left.forwardslash.chevron.right"), chevron: true) { openURL(DreamLinks.source) }
                    PageRow(title: "All Releases", caption: "Every Version and What Changed",
                            lead: .symbol("shippingbox"), chevron: true) { openURL(DreamLinks.releases) }
                    PageRow(title: "Disclaimer", caption: "Please Read Before Using Dream",
                            lead: .symbol("doc.text"), chevron: true) { openURL(DreamLinks.disclaimer) }
                }
                .padding(.top, 10)
                section("Credits")
                VStack(spacing: PageSpace.row) {
                    // TMDB's terms: attribution wherever its data is used, its own sentence, shown whole.
                    PageInfoRow(title: "Movie and Show Data", caption: "This product uses the TMDB API but is not endorsed or certified by TMDB.",
                                value: "TMDB", lead: .symbol("film.stack"))
                    // JustWatch's terms: the where-to-watch data TMDB passes on (STANDARDS §4).
                    PageInfoRow(title: "Where to Watch", caption: "Streaming Availability From JustWatch",
                                value: "JustWatch", lead: .symbol("tv"))
                    PageInfoRow(title: "Scores", caption: "Rotten Tomatoes and IMDb Scores From OMDb",
                                value: "OMDb", lead: .symbol("star.leadinghalf.filled"))
                }
                .padding(.top, 10)
            }
        }
        .task { updates.checkIfDue() }
    }

    // MARK: Parts

    /// The mark, the name, the version and one line on what Dream is.
    private var header: some View {
        VStack(spacing: 10) {
            DreamMark(height: 64)
                .padding(.bottom, 4)
            Text("Dream")
                .font(.system(size: 30, weight: .bold))
                .foregroundStyle(Palette.text)
            VersionTag(size: 15, color: Palette.textSecondary)
            if !AppVersion.build.isEmpty {
                Text("Build \(AppVersion.build)")
                    .font(.system(size: 13))
                    .foregroundStyle(Palette.textTertiary)
            }
            Text("Every movie and show in one place, played from your own accounts and sources.")
                .font(.system(size: 15))
                .foregroundStyle(Palette.textSecondary)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 4)
                .padding(.horizontal, 12)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 8)
        .padding(.bottom, 4)
    }

    private func section(_ title: String) -> some View {
        PageSection(title: title).padding(.top, PageSpace.group)
    }

    /// Up to Date / Version 4.3 Available / Couldn't Check, when it was asked, and what to do.
    private var updateCard: some View {
        let status = updates.status
        return VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center, spacing: 14) {
                statusGlyph(status)
                VStack(alignment: .leading, spacing: 4) {
                    Text(headline(status))
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(tone(status))
                        .fixedSize(horizontal: false, vertical: true)
                    Text(detail(status))
                        .font(.system(size: 13.5))
                        .foregroundStyle(Palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            if case .available = status {
                PagePrimaryButton(title: "Get the Update", systemImage: "arrow.down.circle") { openURL(updates.releasePage) }
                if UIApplication.shared.canOpenURL(DreamLinks.sideStore) {
                    PageSecondaryButton(title: "Open SideStore", systemImage: "arrow.triangle.2.circlepath") { openURL(DreamLinks.sideStore) }
                }
            }
            // Always there: asks GitHub now (past the six-hour wait; the ETag still saves the download).
            CheckForUpdatesButton(checking: updates.checking) { Task { await updates.check() } }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color.white.opacity(0.045)))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Color.white.opacity(0.08), lineWidth: 1))
        .padding(.top, 10)
        .animation(.easeOut(duration: 0.2), value: status)
    }

    @ViewBuilder private func statusGlyph(_ s: DreamUpdates.Status) -> some View {
        if updates.checking {
            PageMarkGlyph(mark: .busy, size: 34)
        } else {
            switch s {
            case .upToDate: PageMarkGlyph(mark: .ok, size: 34)
            case .available: NewVersionGlyph(size: 34)
            case .failed: PageMarkGlyph(mark: .off, size: 34)
            case .unknown: PageMarkGlyph(mark: .off, size: 34)
            }
        }
    }

    private func headline(_ s: DreamUpdates.Status) -> String {
        if updates.checking { return "Checking for Updates…" }
        return switch s {
        case .upToDate: "Up to Date"
        case .available(let v): "Version \(v) Available"
        case .failed: "Couldn't Check"
        case .unknown: "Not Checked Yet"
        }
    }

    private func detail(_ s: DreamUpdates.Status) -> String {
        let last = updates.checkedAt.map { "Last Checked \(DreamUpdates.whenText($0))" }
        switch s {
        case .available:
            return ["You have \(AppVersion.text). Download the new version from its release page, then install it with SideStore.",
                    last].compactMap { $0 }.joined(separator: " ")
        case .failed:
            return ["Dream couldn't reach GitHub. Check your connection and try again.", last].compactMap { $0 }.joined(separator: " ")
        case .upToDate:
            return last ?? "You have the newest version."
        case .unknown:
            return "Dream looks for a new version every few hours."
        }
    }

    private func tone(_ s: DreamUpdates.Status) -> Color {
        if updates.checking { return Palette.text }
        return switch s {
        case .upToDate: Palette.greenText
        case .failed: Palette.amberText
        default: Palette.text
        }
    }
}

/// "Check for Updates" (pearl press), with a small spinner in place of its icon while the check runs.
private struct CheckForUpdatesButton: View {
    var checking: Bool
    var action: () -> Void
    var body: some View {
        Button {
            PageHaptics.tap()
            action()
        } label: {
            HStack(spacing: 10) {
                if checking {
                    ProgressView().controlSize(.small).tint(Palette.text)
                } else {
                    Image(systemName: "arrow.clockwise").font(.system(size: 15, weight: .semibold))
                }
                Text(checking ? "Checking…" : "Check for Updates").lineLimit(1)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(PearlButtonStyle())
        .disabled(checking)
        .accessibilityLabel(checking ? "Checking for Updates" : "Check for Updates")
    }
}

/// A new version waits: a drawn up arrow in a thin rainbow ring on dark glass (the rainbow is Dream's only accent).
private struct NewVersionGlyph: View {
    var size: CGFloat
    var body: some View {
        ZStack {
            Circle().fill(Palette.surfaceRaised)
            Circle().strokeBorder(Rainbow.gradient, lineWidth: max(2, size * 0.08))
            Canvas { ctx, sz in
                let u = min(sz.width, sz.height) / 100
                let c = CGPoint(x: sz.width / 2, y: sz.height / 2)
                var p = Path()
                p.move(to: CGPoint(x: c.x, y: c.y + 20 * u))
                p.addLine(to: CGPoint(x: c.x, y: c.y - 20 * u))
                p.move(to: CGPoint(x: c.x - 15 * u, y: c.y - 5 * u))
                p.addLine(to: CGPoint(x: c.x, y: c.y - 20 * u))
                p.addLine(to: CGPoint(x: c.x + 15 * u, y: c.y - 5 * u))
                ctx.stroke(p, with: .color(Palette.pearl), style: StrokeStyle(lineWidth: 8 * u, lineCap: .round, lineJoin: .round))
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
