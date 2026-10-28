import SwiftUI
import DreamCore
import DreamUI
import DreamPlayback

/// Status words for a debrid service (SetupGate.status): (value, tone, mark), and the caption under the name.
enum AccountWords {
    static func status(_ s: DebridAccountState, checking: Bool) -> (String, Color?, PageMark) {
        if !s.linked { return ("Not Connected", nil, .none) }
        if checking { return ("Checking…", nil, .busy) }
        switch s.health {
        case .unchecked: return ("Connected · Not Checked Yet", nil, .none)
        case .invalid: return ("Sign In Again", Palette.redText, .warn)
        default: break
        }
        if s.expired { return ("Expired", Palette.redText, .warn) }
        if s.health == .offline { return ("Couldn't Check Right Now", nil, .none) }
        if let until = s.premiumUntil { return ("Connected · Premium Until \(DreamTime.format(until, "MMM d, yyyy"))", Palette.greenText, .ok) }
        return ("Connected · Premium", Palette.greenText, .ok)
    }
}

/// Connect Your Accounts (AccountsPages.overview): the debrid services with their live status, What's a Debrid
/// Service?, and Trakt. Used by Settings and by setup (`inSetup` checks every linked service once).
struct AccountsList: View {
    var inSetup = false
    var changed: () -> Void = {}
    @State private var states: [String: DebridAccountState] = [:]
    @State private var checking: Set<String> = []
    @State private var checkedOnce = false
    @State private var linking: String?
    @State private var traktTick = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PageSection(title: "Debrid Services", required: true)
            VStack(spacing: PageSpace.row) {
                ForEach(DebridServices.all, id: \.self) { key in
                    let s = states[key] ?? DebridAccountState(service: key, linked: false)
                    let (words, tone, mark) = AccountWords.status(s, checking: checking.contains(key))
                    let plan = words.components(separatedBy: " · ").dropFirst().joined(separator: " · ")
                    let caption = s.linked
                        ? ([s.user.map { "Signed In as \($0)" }, plan.isEmpty ? nil : plan].compactMap { $0 }.joined(separator: "  ·  "))
                        : DebridServices.site(key)
                    if s.linked {
                        PageLinkRow(title: DebridServices.name(key), caption: caption.isEmpty ? DebridServices.site(key) : caption,
                                    value: words.components(separatedBy: " · ").first, valueTone: tone,
                                    lead: .monogram(DebridServices.monogram(key)), trailingMark: mark) {
                            AccountDetailView(service: key) { Task { await load(); changed() } }
                        }
                    } else {
                        PageRow(title: DebridServices.name(key), caption: caption, value: "Connect",
                                lead: .monogram(DebridServices.monogram(key)), chevron: true) { linking = key }
                    }
                }
                PageLinkRow(title: "What's a Debrid Service?", caption: "What It Does, and Where to Get One") { DebridInfoView() }
                PageLinkRow(title: "Which Service Works Best?", caption: "Premiumize Gives the Fastest Play") { DebridBestView() }
            }
            .padding(.top, 10)
            // Trakt (DreamSetupPages accounts overview): optional, for this profile.
            PageSection(title: "Trakt", required: false).padding(.top, PageSpace.group)
            PageNote(text: "Keeps your watch history and watchlist in sync across your devices.", size: 14).padding(.top, 8)
            let trakt = Trakt.shared
            PageLinkRow(title: "Trakt", caption: trakt.signedIn ? trakt.lastSyncedText : "trakt.tv",
                        value: trakt.signedIn ? "Connected" : TraktWords.value, valueTone: TraktWords.tone,
                        lead: .monogram("T"), trailingMark: trakt.signedIn ? .ok : .none) { TraktView() }
                .padding(.top, 10)
                .id(traktTick)
        }
        .onReceive(NotificationCenter.default.publisher(for: .dreamTraktChanged)) { _ in traktTick &+= 1 }
        .task { await load(firstOpen: true) }
        .onReceive(NotificationCenter.default.publisher(for: .dreamAccountsChanged)) { _ in Task { await load() } }
        .sheet(item: Binding(get: { linking.map(LinkTarget.init) }, set: { linking = $0?.id })) { t in
            NavigationStack { LinkAccountView(service: t.id) { Task { await load(); changed() } } }
                .dreamNavigationLook()
                .presentationBackground(Palette.page)
        }
    }

    struct LinkTarget: Identifiable { let id: String }

    private func load(firstOpen: Bool = false) async {
        let d = PagesServices.debrid
        for key in DebridServices.all { states[key] = await d.state(key) }
        // Setup checks every linked service once when it opens (the gate needs a live answer); later, only unchecked ones.
        if firstOpen && !checkedOnce {
            checkedOnce = true
            for key in DebridServices.all where states[key]?.linked == true && (inSetup || states[key]?.health == .unchecked) {
                checking.insert(key)
                states[key] = await d.check(key)
                checking.remove(key)
            }
            changed()
        }
    }
}

/// Settings › Accounts.
struct AccountsView: View {
    var body: some View {
        PageScaffold(title: "Accounts", subtitle: "Dream OS plays everything through your own debrid account. Connect the ones you have.") {
            AccountsList()
        }
    }
}

/// One linked service (AccountsPages.service): status, Check Again, Use First, Link Again, Disconnect.
struct AccountDetailView: View {
    let service: String
    var changed: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var state: DebridAccountState?
    @State private var checking = false
    @State private var order: [String] = []
    @State private var relinking = false
    @State private var confirmUnlink = false

    var body: some View {
        let name = DebridServices.name(service)
        PageScaffold(title: name) {
            VStack(alignment: .leading, spacing: 0) {
                let s = state ?? DebridAccountState(service: service, linked: true)
                let (words, tone, mark) = AccountWords.status(s, checking: checking)
                VStack(spacing: PageSpace.row) {
                    PageInfoRow(title: s.user ?? name, caption: DebridServices.site(service), lead: .monogram(DebridServices.monogram(service)))
                    PageInfoRow(title: "Status", value: words, valueTone: tone, lead: .mark(mark))
                }
                if tone == Palette.redText {
                    PageNote(text: words == "Expired" ? "Renew your plan on \(name)'s website, then choose Check Again." : "Link \(name) again to sign back in.", size: 14)
                        .padding(.top, 12)
                }
                PageSection(title: "Actions").padding(.top, PageSpace.group)
                VStack(spacing: PageSpace.row) {
                    PageRow(title: "Check Again", caption: "Asks \(name) for Your Account's Status", value: checking ? "Checking…" : nil) {
                        Task { await check() }
                    }
                    if order.count > 1 && order.first != service {
                        PageRow(title: "Use First", caption: "Dream OS Asks \(name) Before Your Other Services") {
                            Task { await PagesServices.debrid.makePrimary(service); await load(); changed() }
                        }
                    }
                    PageRow(title: "Link Again", caption: "Sign In Again, or With Another Account", chevron: true) { relinking = true }
                    PageRow(title: "Disconnect", caption: "Dream OS Stops Using This Account", chevron: true) { confirmUnlink = true }
                }
                .padding(.top, 10)
            }
        }
        .task { await load() }
        .confirmationDialog("Disconnect \(name)?", isPresented: $confirmUnlink, titleVisibility: .visible) {
            Button("Disconnect", role: .destructive) {
                Task {
                    await PagesServices.debrid.unlink(service)
                    NotificationCenter.default.post(name: .dreamAccountsChanged, object: nil)
                    changed()
                    dismiss()
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Dream OS stops using \(name) on this iPhone. You can connect it again anytime.")
        }
        .sheet(isPresented: $relinking) {
            NavigationStack { LinkAccountView(service: service) { Task { await load(); changed() } } }
                .dreamNavigationLook()
                .presentationBackground(Palette.page)
        }
    }

    private func load() async {
        state = await PagesServices.debrid.state(service)
        order = await PagesServices.debrid.linkedOrder()
    }

    private func check() async {
        guard !checking else { return }
        checking = true
        state = await PagesServices.debrid.check(service)
        checking = false
        changed()
    }
}

/// Connect a service (AccountsPages.link): its short code and website (Open Website opens it in Safari, the code can be
/// copied), waiting for approval, then the account check. Premiumize without a device sign-in takes its API key.
struct LinkAccountView: View {
    let service: String
    var changed: () -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var link: DebridDeviceLink?
    @State private var phase: Phase = .starting
    @State private var key = ""
    @State private var verified: DebridAccountState?
    @State private var copied = false

    enum Phase: Equatable { case starting, waiting, saving, checking, done, failed(String), keyEntry, keyChecking }

    var body: some View {
        let name = DebridServices.name(service)
        PageScaffold(title: "Connect \(name)") {
            VStack(alignment: .leading, spacing: 18) {
                switch phase {
                case .starting:
                    PageResult(mark: .busy, headline: "Getting a Code…")
                case .waiting, .saving:
                    if let link { codeCard(link) }
                    PageInfoRow(title: phase == .saving ? "Approved. Saving…" : "Waiting for You to Approve…",
                                caption: "Dream OS connects by itself as soon as you approve it on \(name)'s website.", lead: .mark(.busy))
                    PageNote(text: "The code works for about 10 minutes. If it runs out, tap Cancel and choose \(name) again for a new one.", size: 14)
                case .checking:
                    PageResult(mark: .busy, headline: "Checking Your Account…")
                    PageNote(text: "One moment while Dream OS asks \(name) about your plan.")
                case .done:
                    let ok = verified?.verified == true
                    PageResult(mark: ok ? .ok : .warn, headline: ok ? "\(name) Is Connected" : "\(name) Isn't Ready Yet")
                    PageNote(text: doneLine(name))
                    PagePrimaryButton(title: "Done") { dismiss() }
                    if !ok { PageSecondaryButton(title: "Check Again") { Task { await verify() } } }
                case .failed(let msg):
                    PageResult(mark: .warn, headline: "Couldn't Connect")
                    PageNote(text: msg)
                    ErrorHelp(lines: Self.help(msg, name: name, key: !PagesServices.debrid.usesDeviceSignIn(service)))
                    PagePrimaryButton(title: "Try Again") { Task { await start() } }
                case .keyEntry, .keyChecking:
                    GuideSteps(steps: [
                        GuideStep("Tap Open Your \(name) Account and sign in.",
                                  links: [GuideLink("Open Your \(name) Account", "https://www.premiumize.me/account")]),
                        GuideStep("Find API Key on your account page and copy it.",
                                  see: "Press and hold the key, then tap Copy. Copy only the key, with nothing before or after it."),
                        GuideStep("Come back here, tap Paste, then Connect.", see: "Dream checks the key with \(name) and shows your plan."),
                    ])
                    PageTextField(placeholder: "Your \(name) Key", text: $key, secure: true) { Task { await linkKey() } }
                    if phase == .keyChecking {
                        PageInfoRow(title: "Checking Your Key", caption: "One Moment…", lead: .mark(.busy))
                    } else {
                        HStack(spacing: 10) {
                            PageSecondaryButton(title: "Paste", systemImage: "doc.on.clipboard") {
                                if let s = UIPasteboard.general.string { key = s.trimmingCharacters(in: .whitespacesAndNewlines) }
                            }
                            PagePrimaryButton(title: "Connect") { Task { await linkKey() } }
                        }
                    }
                }
            }
        }
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        .task { await start() }
    }

    private func codeCard(_ l: DebridDeviceLink) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Tap Open Website: Dream copies this code for you. Sign in on the website, paste the code, and approve Dream.")
                .font(.system(size: 15)).foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Text(l.code)
                .font(.system(size: 40, weight: .semibold, design: .monospaced))
                .tracking(6)
                .foregroundStyle(Palette.text)
                .textSelection(.enabled)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(l.url.host().map { $0.replacingOccurrences(of: "www.", with: "") } ?? l.url.absoluteString)
                .font(.system(size: 15, weight: .medium)).foregroundStyle(Palette.text.opacity(0.8))
            HStack(spacing: 12) {
                PagePrimaryButton(title: "Open Website", systemImage: "safari") {
                    UIPasteboard.general.string = l.code
                    openURL(l.url)
                }
                PageSecondaryButton(title: copied ? "Copied" : "Copy Code", systemImage: copied ? "checkmark" : "doc.on.doc") {
                    UIPasteboard.general.string = l.code
                    copied = true
                    PageHaptics.success()
                }
            }
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color.white.opacity(0.045)))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Palette.hairline, lineWidth: 1))
    }

    /// What to try under a failed link, in plain words, matched to DreamPlayback's message.
    static func help(_ message: String, name: String, key: Bool) -> [String] {
        let m = message.lowercased()
        if m.contains("expired") {
            return ["Codes work for about 10 minutes. Tap Try Again for a new code, then enter it on the website right away."]
        }
        if m.contains("declined") {
            return ["Tap Try Again, and this time choose to allow Dream on \(name)'s website."]
        }
        if m.contains("key") {
            return ["Copy the key again from the API Key line on your \(name) account page, with nothing before or after it.",
                    "Make sure your \(name) plan is active, then tap Try Again."]
        }
        if m.contains("answer") || m.contains("network") || m.contains("internet") {
            return ["Check that Wi-Fi or cellular data is on, then tap Try Again.",
                    "If it still doesn't work, \(name) may be busy for a while: try again in a few minutes."]
        }
        return [key ? "Check your key, then tap Try Again." : "Tap Try Again for a new code.",
                "If it keeps happening, connect another service for now and try \(name) again later."]
    }

    private func doneLine(_ name: String) -> String {
        guard let s = verified else { return "" }
        if s.verified {
            let (words, _, _) = AccountWords.status(s, checking: false)
            return [s.user.map { "Signed in as \($0)." }, words.replacingOccurrences(of: "Connected · ", with: "") + "."].compactMap { $0 }.joined(separator: " ")
        }
        if s.expired { return "This account doesn't have an active premium plan. Renew it on \(name)'s website, then choose Check Again." }
        return "Dream OS couldn't confirm your plan. Check your internet connection, then choose Check Again."
    }

    private func start() async {
        let d = PagesServices.debrid
        guard d.usesDeviceSignIn(service) else { phase = .keyEntry; return }
        phase = .starting
        do {
            let l = try await d.startDeviceLink(service)
            link = l
            phase = .waiting
            await poll(l)
        } catch let e as DreamError {
            phase = .failed(e.message)
        } catch {
            phase = .failed("\(DebridServices.name(service)) didn't answer. Check your internet connection and try again.")
        }
    }

    private func poll(_ l: DebridDeviceLink) async {
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(max(2, l.intervalSeconds)))
            if Task.isCancelled { return }
            switch await PagesServices.debrid.poll(l) {
            case .waiting: continue
            case .failed(let m): phase = .failed(m); return
            case .done:
                phase = .saving
                PageHaptics.success()
                NotificationCenter.default.post(name: .dreamAccountsChanged, object: nil)
                await verify()
                return
            }
        }
    }

    private func verify() async {
        phase = .checking
        verified = await PagesServices.debrid.check(service)
        phase = .done
        changed()
    }

    private func linkKey() async {
        let k = key.filter { !$0.isWhitespace }
        guard !k.isEmpty, phase != .keyChecking else { return }
        phase = .keyChecking
        do {
            try await PagesServices.debrid.link(service, apiKey: k)
            NotificationCenter.default.post(name: .dreamAccountsChanged, object: nil)
            await verify()
        } catch let e as DreamError {
            phase = .failed(e.message)
        } catch {
            phase = .failed("That key didn't work. Check it and try again.")
        }
    }
}

/// Which Service Works Best? (owner, 2026-10-28): why Premiumize (and TorBox) make Dream fastest. Their cache checks add
/// nothing to the account, so Instant Play and the pills can check ahead; AllDebrid / Real-Debrid checks need the title
/// added to the account, so Dream asks them only on Play (magnet safety).
struct DebridBestView: View {
    @Environment(\.openURL) private var openURL
    var body: some View {
        PageScaffold(title: "Which Service Works Best?") {
            VStack(alignment: .leading, spacing: 12) {
                PageNote(text: "Premiumize is the best match for Dream. Dream can ask Premiumize whether a title is ready without adding anything to your account, so your stream is ready the moment you open a title, and Available Now is checked before you scroll to it. Play starts almost instantly.")
                PageNote(text: "TorBox works the same way, so it's a great choice too.")
                PageNote(text: "AllDebrid and Real-Debrid answer that question only once a title is added to your account. To keep your account tidy, Dream asks them when you tap Play, so Play takes a second or two longer. Everything else works the same.")
                PageNote(text: "You can connect more than one. Dream asks them in the order you choose.")
                VStack(spacing: PageSpace.row) {
                    ForEach(["pm", "tb"], id: \.self) { key in
                        PageRow(title: DebridServices.name(key), caption: DebridServices.site(key), value: "Sign Up",
                                lead: .monogram(DebridServices.monogram(key)), chevron: true) {
                            if let u = DebridServices.signUpURL(key) { openURL(u) }
                        }
                    }
                }
                .padding(.top, 8)
            }
        }
    }
}

/// What's a Debrid Service? and where to sign up.
struct DebridInfoView: View {
    @Environment(\.openURL) private var openURL
    var body: some View {
        PageScaffold(title: "What's a Debrid Service?") {
            VStack(alignment: .leading, spacing: 12) {
                PageNote(text: "A debrid service is a paid account that keeps a huge library of movies and shows on fast servers. When you pick something, Dream OS finds it and plays it from your account in full quality, with no waiting.")
                PageNote(text: "Most cost about $3 to $12 a month, and Dream needs an active paid plan. Premiumize is recommended for the fastest Play.")
                PageNote(text: "You need at least one. If you don't have one yet, sign up with any of these, buy a plan, then come back and connect it.")
                VStack(spacing: PageSpace.row) {
                    ForEach(DebridServices.all, id: \.self) { key in
                        PageRow(title: DebridServices.name(key), caption: DebridServices.site(key), value: "Sign Up",
                                lead: .monogram(DebridServices.monogram(key)), chevron: true) {
                            if let u = DebridServices.signUpURL(key) { openURL(u) }
                        }
                    }
                }
                .padding(.top, 8)
            }
        }
    }
}
