import SwiftUI
import DreamCore
import DreamUI
import DreamPlayback

// Settings › Accounts & Scrapers › Trakt (fire-home TraktPage.kt + TraktPages): sign in with a short code, the
// connected account, Sync Now and Disconnect, for the current profile only. Without Trakt keys in the build, the
// viewer's own free Trakt app comes first (Use Your Own Trakt App), then the sign-in.

/// Trakt's live words for rows elsewhere (Settings, Accounts).
enum TraktWords {
    static var value: String { Trakt.shared.statusText }
    static var caption: String { Trakt.shared.captionText }
    static var tone: Color? {
        let t = Trakt.shared
        if t.signedIn { return Palette.greenText }
        if t.expired { return Palette.amberText }
        return nil
    }
}

/// The Trakt page.
struct TraktView: View {
    @State private var tick = 0
    @State private var asked = false
    @State private var profileName: String?
    @State private var confirmSignOut = false
    @State private var flow: TraktFlow?

    var body: some View {
        let t = Trakt.shared
        let _ = tick
        PageScaffold(title: "Trakt") {
            VStack(alignment: .leading, spacing: 0) {
                if t.signedIn {
                    TraktAccountHeader(user: t.user)
                } else {
                    HStack(spacing: 16) {
                        PageMonogram(text: "T", size: 56)
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Trakt").font(.system(size: 20, weight: .semibold)).foregroundStyle(Palette.text).lineLimit(1)
                            Text("trakt.tv").font(.system(size: 14)).foregroundStyle(Palette.textSecondary).lineLimit(1)
                        }
                    }
                }
                PageNote(text: intro(t)).padding(.top, 16)

                if !t.configured {
                    PageSection(title: "Get Started").padding(.top, PageSpace.group)
                    VStack(spacing: PageSpace.row) {
                        PageRow(title: "Use Your Own Trakt App", caption: "Free, and Takes About a Minute",
                                lead: .symbol("key"), chevron: true) { flow = .ownApp }
                    }
                    .padding(.top, 10)
                } else if !t.signedIn {
                    PageSection(title: "Account").padding(.top, PageSpace.group)
                    VStack(spacing: PageSpace.row) {
                        PageRow(title: t.expired ? "Sign In Again" : "Sign In", caption: "Shows a Short Code to Enter on trakt.tv",
                                lead: .symbol("person.crop.circle.badge.checkmark"), chevron: true) { flow = .signIn(nil) }
                    }
                    .padding(.top, 10)
                    advanced(t)
                } else {
                    let busy = asked || t.isSyncing
                    PageSection(title: "Sync").padding(.top, PageSpace.group)
                    VStack(spacing: PageSpace.row) {
                        PageRow(title: "Sync Now", caption: !busy && t.lastSyncFailed ? "Couldn't Reach Trakt. Try Again." : t.lastSyncedText,
                                value: busy ? "Syncing…" : nil, lead: .symbol("arrow.triangle.2.circlepath"),
                                trailingMark: busy ? .busy : .none) { syncNow() }
                        PageInfoRow(title: "Watched History", caption: "Movies and Episodes You Finish Are Marked Watched",
                                    lead: .symbol("checkmark.circle"))
                        PageInfoRow(title: "Continue Watching", caption: "Pick Up Where You Left Off on Any Device", lead: .symbol("play.circle"))
                        PageInfoRow(title: "My List", caption: "Stays in Step With Your Trakt Watchlist", lead: .symbol("bookmark"))
                    }
                    .padding(.top, 10)
                    PageSection(title: "Account").padding(.top, PageSpace.group)
                    VStack(spacing: PageSpace.row) {
                        PageRow(title: "Disconnect", caption: "Stops Syncing This Profile With Trakt",
                                lead: .symbol("xmark.circle"), chevron: true) { confirmSignOut = true }
                    }
                    .padding(.top, 10)
                    advanced(t)
                }
            }
        }
        .task { profileName = await Services.shared.profiles.current()?.name }
        .onAppear { tick &+= 1 }
        .onReceive(NotificationCenter.default.publisher(for: .dreamTraktChanged)) { _ in
            if asked && !Trakt.shared.isSyncing { asked = false }
            tick &+= 1
        }
        .onReceive(NotificationCenter.default.publisher(for: .dreamProfileChanged)) { _ in
            Task { profileName = await Services.shared.profiles.current()?.name; tick &+= 1 }
        }
        .confirmationDialog("Disconnect Trakt?", isPresented: $confirmSignOut, titleVisibility: .visible) {
            Button("Disconnect", role: .destructive) {
                Trakt.shared.signOut()
                PageHaptics.success()
                tick &+= 1
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Dream OS stops syncing this profile with Trakt. Everything already on this iPhone stays, and your Trakt account keeps its history. You can sign in again anytime.")
        }
        .sheet(item: $flow) { f in
            NavigationStack { TraktConnectView(start: f) { tick &+= 1 } }
                .dreamNavigationLook()
                .presentationBackground(Palette.page)
        }
    }

    private func intro(_ t: Trakt) -> String {
        let who = profileName.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
        let base: String
        if t.expired { base = "Your Trakt sign-in has ended. Sign in again to keep this profile in sync." }
        else { base = "Keep what you watch in step across your devices: finished movies and episodes, where you left off, and your watchlist." }
        return base + (who.map { " Signed in for \($0)'s profile only." } ?? "")
    }

    /// Advanced: the viewer's own Trakt app keys (always reachable, so a build's keys can be replaced).
    @ViewBuilder private func advanced(_ t: Trakt) -> some View {
        PageSection(title: "Advanced").padding(.top, PageSpace.group)
        VStack(spacing: PageSpace.row) {
            PageRow(title: "Your Trakt App",
                    caption: t.usesOwnKeys ? "Client ID \(t.clientIdHint ?? "")" : "Use Your Own Trakt App Keys",
                    lead: .symbol("key"), chevron: true) { flow = .ownApp }
        }
        .padding(.top, 10)
    }

    private func syncNow() {
        guard !asked, !Trakt.shared.isSyncing else { return }
        asked = true
        Trakt.shared.sync(force: true)
        Task {
            try? await Task.sleep(for: .seconds(90))
            asked = false
        }
    }
}

/// The signed-in account: avatar (or its initial), name, "@username · Connected".
struct TraktAccountHeader: View {
    var user: TraktUser?

    var body: some View {
        let name = user?.name ?? user?.username ?? "Trakt"
        HStack(spacing: 16) {
            ZStack {
                Circle().fill(Color.white.opacity(0.10))
                Text(String(name.prefix(1)).uppercased())
                    .font(.system(size: 22, weight: .semibold)).foregroundStyle(Palette.text)
                if let a = user?.avatar, let url = URL(string: a) {
                    AsyncImage(url: url) { img in img.resizable().scaledToFill() } placeholder: { Color.clear }
                }
            }
            .frame(width: 56, height: 56)
            .clipShape(Circle())
            VStack(alignment: .leading, spacing: 6) {
                Text(name).font(.system(size: 20, weight: .semibold)).foregroundStyle(Palette.text).lineLimit(1)
                HStack(spacing: 8) {
                    PageMarkGlyph(mark: .ok, size: 16)
                    Text([user.map { "@\($0.username)" }, "Connected"].compactMap { $0 }.joined(separator: "  ·  "))
                        .font(.system(size: 14)).foregroundStyle(Palette.textSecondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 4)
    }
}

/// Which screen the connect sheet opens on.
enum TraktFlow: Identifiable, Hashable {
    /// The device sign-in; a code already fetched (checking the viewer's own keys gets one) shows at once.
    case signIn(Trakt.DeviceCode?)
    case ownApp
    var id: String {
        switch self {
        case .signIn: "signIn"
        case .ownApp: "ownApp"
        }
    }
}

/// The connect sheet: the viewer's own Trakt app (keys) and the device sign-in, one after the other.
struct TraktConnectView: View {
    let start: TraktFlow
    var changed: () -> Void
    @State private var screen: TraktFlow?

    var body: some View {
        Group {
            switch screen ?? start {
            case .ownApp: TraktOwnAppView(changed: changed) { dc in withAnimation(.easeOut(duration: 0.2)) { screen = .signIn(dc) } }
            case .signIn(let dc): TraktSignInView(ready: dc, changed: changed) { withAnimation(.easeOut(duration: 0.2)) { screen = .ownApp } }
                .id(dc?.deviceCode ?? "new")
            }
        }
    }
}

// MARK: Sign in (device code)

/// Trakt's device sign-in (TraktPages.signIn): the short code and trakt.tv/activate (Open Website copies the code and
/// opens Safari), waiting for approval with a countdown, then Connected and back on its own.
struct TraktSignInView: View {
    var ready: Trakt.DeviceCode?
    var changed: () -> Void
    var ownApp: () -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var phase: Phase = .starting
    @State private var copied = false
    @State private var now = Date()
    @State private var attempt = 0

    enum Phase {
        case starting
        case waiting(Trakt.DeviceCode)
        case done(TraktUser?)
        case failed(String, newCode: Bool, badKeys: Bool)
    }

    var body: some View {
        PageScaffold(title: "Sign In to Trakt") {
            VStack(alignment: .leading, spacing: 18) {
                switch phase {
                case .starting:
                    PageResult(mark: .busy, headline: "Getting a Code…")
                case .waiting(let dc):
                    codeCard(dc)
                    PageInfoRow(title: "Waiting for You to Approve…", caption: countdown(dc), lead: .mark(.busy))
                    PageNote(text: "Approve Dream OS on Trakt and this page connects by itself.", size: 14)
                case .done(let u):
                    PageResult(mark: .ok, headline: "Connected")
                    PageNote(text: (u.map { "Signed in as \($0.username). " } ?? "Signed in to Trakt. ") + "Your history and watchlist are syncing now.")
                case .failed(let msg, let newCode, let badKeys):
                    PageResult(mark: .warn, headline: "Couldn't Sign In")
                    PageNote(text: msg)
                    if badKeys {
                        PagePrimaryButton(title: Trakt.shared.usesOwnKeys ? "Enter Keys Again" : "Use Your Own Trakt App") { ownApp() }
                        PageSecondaryButton(title: "Try Again") { attempt &+= 1 }
                    } else {
                        PagePrimaryButton(title: newCode ? "Get a New Code" : "Try Again") { attempt &+= 1 }
                    }
                }
            }
        }
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        .task(id: attempt) { await run(useReady: attempt == 0) }
    }

    private func codeCard(_ dc: Trakt.DeviceCode) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("On this iPhone or any computer, go to the website below and enter this code:")
                .font(.system(size: 15)).foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Text(dc.userCode)
                .font(.system(size: 40, weight: .semibold, design: .monospaced))
                .tracking(6)
                .foregroundStyle(Palette.text)
                .textSelection(.enabled)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(dc.site)
                .font(.system(size: 15, weight: .medium)).foregroundStyle(Palette.text.opacity(0.8)).lineLimit(1)
            HStack(spacing: 12) {
                PagePrimaryButton(title: "Open Website", systemImage: "safari") {
                    UIPasteboard.general.string = dc.userCode
                    copied = true
                    openURL(dc.url)
                }
                PageSecondaryButton(title: copied ? "Copied" : "Copy Code", systemImage: copied ? "checkmark" : "doc.on.doc") {
                    UIPasteboard.general.string = dc.userCode
                    copied = true
                    PageHaptics.success()
                }
            }
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color.white.opacity(0.045)))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Palette.hairline, lineWidth: 1))
    }

    private func countdown(_ dc: Trakt.DeviceCode) -> String {
        let left = max(0, Int((Double(dc.expiresAt) / 1000 - now.timeIntervalSince1970).rounded(.down)))
        return String(format: "Code Expires in %d:%02d", left / 60, left % 60)
    }

    private func run(useReady: Bool) async {
        copied = false
        var dc: Trakt.DeviceCode?
        if useReady, let r = ready, Double(r.expiresAt) / 1000 - Date().timeIntervalSince1970 > 60 {
            dc = r
        } else {
            phase = .starting
            dc = await Trakt.shared.startDevice()
            if dc == nil, !Task.isCancelled {
                // Trakt didn't answer: one calm retry by itself, then say so.
                try? await Task.sleep(for: .seconds(3))
                if Task.isCancelled { return }
                dc = await Trakt.shared.startDevice()
            }
        }
        guard !Task.isCancelled else { return }
        guard let dc else { phase = .failed("Couldn't reach Trakt. Try again.", newCode: false, badKeys: false); return }
        phase = .waiting(dc)
        var interval = dc.interval
        var nextPoll = Date().addingTimeInterval(Double(interval))
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(1))
            if Task.isCancelled { return }
            now = Date()
            if Double(dc.expiresAt) / 1000 <= now.timeIntervalSince1970 {
                phase = .failed("The code expired. Get a new one to try again.", newCode: true, badKeys: false)
                return
            }
            guard now >= nextPoll else { continue }
            switch await Trakt.shared.poll(dc) {
            case .waiting: nextPoll = Date().addingTimeInterval(Double(interval))
            case .slowDown: interval += 2; nextPoll = Date().addingTimeInterval(Double(interval))
            case .failed(let m, let expired, let badKeys):
                phase = .failed(m, newCode: expired, badKeys: badKeys)
                PageHaptics.warning()
                return
            case .done(let u):
                phase = .done(u)
                PageHaptics.success()
                changed()
                // The check shows for a moment, then the sheet goes back on its own.
                try? await Task.sleep(for: .seconds(1.5))
                if !Task.isCancelled { dismiss() }
                return
            }
        }
    }
}

// MARK: The viewer's own Trakt app

/// Use Your Own Trakt App (TraktPages.ownApp): three short steps, the redirect URI to paste, then the Client ID and
/// Client Secret. Sign In checks them with Trakt, saves them in the Keychain and goes straight on to the sign-in code.
struct TraktOwnAppView: View {
    var changed: () -> Void
    var onCode: (Trakt.DeviceCode) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var id = ""
    @State private var secret = ""
    @State private var checking = false
    @State private var error: String?
    @State private var copiedUri = false
    @State private var confirmRemove = false
    @State private var tick = 0

    var body: some View {
        let t = Trakt.shared
        let _ = tick
        PageScaffold(title: "Your Trakt App") {
            VStack(alignment: .leading, spacing: 0) {
                PageNote(text: t.hasBuiltInKeys
                         ? "Connect through your own free Trakt app instead of the one built in. It takes about a minute, and your app is used only by this iPhone."
                         : "Dream OS doesn't come with Trakt keys. Your own Trakt app is free, takes about a minute, and is used only by this iPhone.")

                PageSection(title: "Create Your App").padding(.top, PageSpace.group)
                VStack(alignment: .leading, spacing: 14) {
                    step(1, "Go to trakt.tv/oauth/applications and choose New Application.")
                    PageSecondaryButton(title: "Open Trakt Apps", systemImage: "safari") { openURL(Trakt.appsURL) }
                    step(2, "Give it any name, and enter this Redirect URI:")
                    HStack(spacing: 12) {
                        Text(Trakt.redirectURI)
                            .font(.system(size: 17, weight: .semibold, design: .monospaced))
                            .foregroundStyle(Palette.text)
                            .textSelection(.enabled)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                        Spacer(minLength: 8)
                        Button {
                            UIPasteboard.general.string = Trakt.redirectURI
                            copiedUri = true
                            PageHaptics.success()
                        } label: {
                            Text(copiedUri ? "Copied" : "Copy").lineLimit(1)
                        }
                        .buttonStyle(PearlButtonStyle())
                    }
                    .padding(.horizontal, 16).padding(.vertical, 12)
                    .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.white.opacity(0.05)))
                    .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Color.white.opacity(0.10), lineWidth: 1))
                    step(3, "Save it, then copy its Client ID and Client Secret into the fields below.")
                }
                .padding(.top, 12)

                PageSection(title: "Your App Keys").padding(.top, PageSpace.group)
                VStack(alignment: .leading, spacing: 12) {
                    PageTextField(placeholder: "Client ID", text: $id)
                    PageTextField(placeholder: "Client Secret (Only if Your App Has One)", text: $secret, secure: true) { submit() }
                    if let error {
                        HStack(alignment: .top, spacing: 10) {
                            PageMarkGlyph(mark: .warn, size: 18)
                            Text(error).font(.system(size: 15)).foregroundStyle(Palette.text.opacity(0.85))
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(.top, 2)
                    }
                    if checking {
                        PageInfoRow(title: "Checking Your Keys", caption: "One Moment…", lead: .mark(.busy))
                    } else {
                        PagePrimaryButton(title: "Sign In") { submit() }
                    }
                }
                .padding(.top, 12)

                if t.usesOwnKeys {
                    PageSection(title: "Saved Keys").padding(.top, PageSpace.group)
                    VStack(spacing: PageSpace.row) {
                        PageInfoRow(title: "Your Trakt App", caption: "Client ID \(t.clientIdHint ?? "")", lead: .mark(.ok))
                        PageRow(title: "Remove Trakt App Keys", caption: "Signs Every Profile on This iPhone Out of Trakt",
                                lead: .symbol("trash"), chevron: true) { confirmRemove = true }
                    }
                    .padding(.top, 10)
                }
            }
        }
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        .confirmationDialog("Remove Trakt App Keys?", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Remove", role: .destructive) {
                Trakt.shared.removeOwnKeys()
                changed()
                tick &+= 1
                dismiss()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Dream OS forgets your Trakt app's keys, and every profile on this iPhone is signed out of Trakt. Everything already on this iPhone stays. You can enter keys again anytime.")
        }
    }

    private func step(_ n: Int, _ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text("\(n)")
                .font(.system(size: 14, weight: .bold)).foregroundStyle(Palette.ink)
                .frame(width: 24, height: 24)
                .background(Circle().fill(Palette.pearl))
            Text(text).font(.system(size: 16)).foregroundStyle(Palette.text)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func submit() {
        guard !checking else { return }
        let cleanId = id.filter { !$0.isWhitespace }
        if cleanId.isEmpty { error = "Enter your app's Client ID."; PageHaptics.warning(); return }
        error = nil
        checking = true
        Task {
            let r = await Trakt.shared.tryOwnKeys(id: cleanId, secret: secret)
            checking = false
            switch r {
            case .ok(let dc):
                changed()
                onCode(dc)
            case .failed(let m):
                error = m
                PageHaptics.warning()
            }
        }
    }
}
