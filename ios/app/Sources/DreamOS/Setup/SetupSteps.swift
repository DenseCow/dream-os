import SwiftUI
import DreamCore
import DreamUI
import DreamPlayback

// Welcome, Get Ready and Connect. Finished sign-ins, keys, repositories and the check move on by themselves.

// MARK: Welcome

struct WelcomeStep: View {
    @Environment(SetupFlow.self) private var flow

    private static let features: [(String, String)] = [
        ("point.3.connected.trianglepath.dotted", "Dream Core Engine"), ("bolt", "Instant Play"),
        ("play.rectangle", "Dream Player"), ("square.grid.2x2", "Everything in One Place"),
        ("bell", "Never Miss an Episode"), ("snowflake", "Seasonal Collections"),
        ("slider.horizontal.3", "Sources & Quality"), ("person.2", "Profiles for Everyone"),
    ]

    var body: some View {
        SetupFrame(primary: SetupAction(title: "Start Setup") { flow.forward() }, centered: true) {
            DreamMark(height: 52).padding(.top, 16)
            Text("Welcome to Dream OS")
                .font(.largeTitle.weight(.bold))
                .foregroundStyle(Palette.text)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 22)
                .accessibilityAddTraits(.isHeader)
            Text("Your movies and shows in one place, ready to play in seconds. Setup takes about 15 minutes, and each step shows you how.")
                .font(.body)
                .foregroundStyle(Palette.textSecondary)
                .multilineTextAlignment(.center)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 10)
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12, alignment: .leading), GridItem(.flexible(), alignment: .leading)],
                      alignment: .leading, spacing: 14) {
                ForEach(Self.features, id: \.1) { f in
                    HStack(spacing: 10) {
                        Image(systemName: f.0)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Palette.pearl)
                            .frame(width: 32, height: 32)
                            .background(Circle().fill(Color.white.opacity(0.08)))
                            .accessibilityHidden(true)
                        Text(f.1)
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(Palette.text)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .padding(.top, 30)
        }
    }
}

// MARK: Get Ready

struct NeedStep: View {
    @Environment(SetupFlow.self) private var flow
    @State private var privacy = false

    var body: some View {
        SetupFrame(primary: SetupAction(title: "I Understand") {
                       SetupGate.introAccepted = true
                       flow.forward()
                   },
                   secondary: [SetupAction(title: "Where Your Information Goes") { privacy = true }]) {
            SetupHead(art: .intro, counter: flow.counter, title: "Here's What You'll Need",
                      sentence: "Dream OS plays from accounts you connect yourself. It doesn't include any movies, shows or sources, and you're responsible for what you add.")
            VStack(spacing: SetupSpace.row) {
                NeedCard(number: 1, title: "A TMDB Key", caption: "Posters, details and search", facts: "Free  ·  About 5 Minutes")
                NeedCard(number: 2, title: "A Debrid Service", caption: "Streams what you watch in full quality", facts: "About $3 to $12 a Month")
                NeedCard(number: 3, title: "A Scraper Repository", caption: "Free add-ons that find the streams", facts: "Free  ·  About 2 Minutes")
            }
            .padding(.top, SetupSpace.headToBody)
            Text("Trakt, Rotten Tomatoes scores and the rest are optional. Add them later in Settings.")
                .font(.subheadline)
                .foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, SetupSpace.group)
        }
        .sheet(isPresented: $privacy) { PrivacySheet() }
    }
}

struct ZoneStep: View {
    @Environment(SetupFlow.self) private var flow
    @State private var tick = 0

    var body: some View {
        SetupFrame(primary: SetupAction(title: "Continue") { flow.forward() },
                   secondary: [SetupAction(title: "Change Time Zone") { flow.path.append(.zones) }]) {
            SetupHead(art: .clock, counter: flow.counter, title: "Check Your Time Zone",
                      sentence: "Dream OS uses this to show when new episodes and movies come out.")
            TimelineView(.everyMinute) { ctx in
                let _ = tick
                VStack(alignment: .leading, spacing: 6) {
                    Text(DreamTime.label(DreamTime.zone))
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(Palette.text)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(DreamTime.formatTime(ctx.date))
                        .font(.system(.largeTitle, weight: .bold).monospacedDigit())
                        .foregroundStyle(Palette.text)
                    Text(DreamTime.isAutomatic ? "Set automatically from your iPhone." : "Chosen by you.")
                        .font(.subheadline)
                        .foregroundStyle(Palette.textSecondary)
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.white.opacity(0.05)))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Palette.hairline, lineWidth: 1))
                .accessibilityElement(children: .combine)
            }
            .padding(.top, SetupSpace.headToBody)
        }
        .onReceive(NotificationCenter.default.publisher(for: DreamTime.changed)) { _ in tick += 1 }
    }
}

// MARK: Connect: Movie & Show Info (TMDB)

struct TMDBStep: View {
    @Environment(SetupFlow.self) private var flow
    @Environment(\.openURL) private var openURL
    @State private var hasKey = SetupGate.hasTMDBKey
    @State private var keySheet: SetupKeyKind?
    @State private var help = false

    var body: some View {
        SetupFrame(primary: hasKey ? SetupAction(title: "Continue") { flow.forward() }
                                   : SetupAction(title: "Enter My Key") { keySheet = .tmdb },
                   secondary: secondary) {
            SetupHead(art: .accounts, counter: flow.counter, title: "Connect Movie & Show Info",
                      sentence: "Posters, details and search come from TMDB, a free movie website. Dream OS needs your own free key, made in a few minutes.")
            Group {
                if hasKey {
                    SetupResult(mark: .ok, headline: "Your TMDB Key Is Added", line: "Posters, details and search are ready.")
                } else {
                    VStack(alignment: .leading, spacing: SetupSpace.group) {
                        ShortSteps(steps: ["Make a free account on TMDB.",
                                           "On TMDB's API page, copy the key labeled API Key.",
                                           "Come back and tap Enter My Key."])
                        PageRow(title: "Open TMDB's API Page", caption: "themoviedb.org/settings/api", lead: .symbol("safari")) {
                            openURL(URL(string: "https://www.themoviedb.org/settings/api")!)
                        }
                    }
                }
            }
            .padding(.top, SetupSpace.headToBody)
        }
        .sheet(item: $keySheet, onDismiss: keyClosed) { SetupKeySheet(kind: $0) }
        .sheet(isPresented: $help) {
            SetupHelpSheet(topics: [
                .walkthrough(title: "Show Me How", caption: "Get your free key from TMDB", intro: SetupGuide.tmdbIntro, steps: SetupGuide.tmdbSetupSteps),
                .trouble(title: "If Something Goes Wrong", caption: "Keys that don't work", tips: SetupGuide.tmdbTrouble),
            ])
        }
    }

    private var secondary: [SetupAction] {
        var a = [SetupAction(title: "Help") { help = true }]
        // Only when this build has no ratings key; setup never offers a built-in one.
        if Keys.omdb.isEmpty { a.append(SetupAction(title: "Add Rotten Tomatoes Scores") { keySheet = .omdb }) }
        return a
    }

    private func keyClosed() {
        let had = hasKey
        hasKey = SetupGate.hasTMDBKey
        // The sheet already showed Key Added: a newly added key moves straight on.
        if hasKey && !had { flow.forward() }
    }
}

// MARK: Connect: a debrid service

struct DebridStep: View {
    @Environment(SetupFlow.self) private var flow
    @State private var states: [String: DebridAccountState] = [:]
    @State private var checking: Set<String> = []
    @State private var ready = false
    @State private var readyAtOpen = false
    @State private var linking: ServiceTarget?
    @State private var detail: ServiceTarget?
    @State private var help = false
    @State private var trakt = false
    @State private var checkedOnce = false

    struct ServiceTarget: Identifiable { let id: String }

    var body: some View {
        SetupFrame(primary: SetupAction(title: "Continue") { Task { await next() } },
                   secondary: secondary, blocker: flow.blocker, shake: flow.shake) {
            SetupHead(art: .accounts, counter: flow.counter, title: "Connect a Debrid Service",
                      sentence: "A debrid service is a paid account that streams movies and shows to Dream OS in full quality. Choose the one you have.")
            VStack(spacing: SetupSpace.row) {
                ForEach(DebridServices.all, id: \.self) { key in row(key) }
            }
            .padding(.top, SetupSpace.headToBody)
        }
        .task { await load(checkLinked: true) }
        .onReceive(NotificationCenter.default.publisher(for: .dreamAccountsChanged)) { _ in Task { await load() } }
        .sheet(item: $linking, onDismiss: { Task { await linkClosed() } }) { t in
            NavigationStack { LinkAccountView(service: t.id, autoClose: true) { Task { await load() } } }
                .dreamNavigationLook()
                .presentationBackground(Palette.page)
        }
        .sheet(item: $detail, onDismiss: { Task { await load() } }) { t in
            NavigationStack {
                AccountDetailView(service: t.id) { Task { await load() } }
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { detail = nil } } }
            }
            .dreamNavigationLook()
            .presentationBackground(Palette.page)
        }
        .sheet(isPresented: $help) {
            SetupHelpSheet(topics: [
                .page(title: "What's a Debrid Service?", caption: "What it does, and where to get one", view: AnyView(DebridInfoView())),
                .page(title: "Which Service Works Best?", caption: "Premiumize gives the fastest Play", view: AnyView(DebridBestView())),
                .walkthrough(title: "Show Me How", caption: "Sign up, then connect it to Dream OS", intro: nil, steps: SetupGuide.debridSteps()),
                .trouble(title: "If Something Goes Wrong", caption: "Expired codes, plans and keys", tips: SetupGuide.debridTrouble),
            ])
        }
        .sheet(isPresented: $trakt) {
            NavigationStack {
                TraktView().toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { trakt = false } } }
            }
            .dreamNavigationLook()
            .presentationBackground(Palette.page)
        }
    }

    private var secondary: [SetupAction] {
        var a = [SetupAction(title: "Help Me Choose") { help = true }]
        if !Trakt.shared.signedIn { a.append(SetupAction(title: "Connect Trakt") { trakt = true }) }
        return a
    }

    private func row(_ key: String) -> some View {
        let s = states[key] ?? DebridAccountState(service: key, linked: false)
        let (words, tone, mark) = AccountWords.status(s, checking: checking.contains(key))
        let caption = s.linked ? (s.user.map { "Signed in as \($0)" } ?? DebridServices.site(key)) : DebridServices.site(key)
        return Button {
            PageHaptics.tap()
            if s.linked { detail = ServiceTarget(id: key) } else { readyAtOpen = ready; linking = ServiceTarget(id: key) }
        } label: {
            PageRowContent(title: DebridServices.name(key), caption: caption,
                           value: words.components(separatedBy: " · ").first, valueTone: tone,
                           lead: .monogram(DebridServices.monogram(key)), trailingMark: mark, chevron: true)
        }
        .buttonStyle(PageRowStyle())
        .accessibilityHint(s.linked ? "Shows this account" : "Signs in to \(DebridServices.name(key))")
    }

    private func load(checkLinked: Bool = false) async {
        let d = PagesServices.debrid
        for key in DebridServices.all { states[key] = await d.state(key) }
        // The step checks every linked service once when it opens: the gate needs a live answer.
        if checkLinked && !checkedOnce {
            checkedOnce = true
            for key in DebridServices.all where states[key]?.linked == true {
                checking.insert(key)
                states[key] = await d.check(key)
                checking.remove(key)
            }
        }
        ready = await SetupGate.accountsBlocker() == nil
        if flow.blocker != nil, flow.step == .accounts { flow.blocker = await SetupGate.accountsBlocker() }
    }

    private func linkClosed() async {
        await load()
        // A sign-in that's approved and verified moves on (the sheet showed the result first).
        if ready && !readyAtOpen { flow.forward() }
    }

    private func next() async {
        if let why = await SetupGate.accountsBlocker() { flow.block(why) } else { flow.forward() }
    }
}

// MARK: Connect: scrapers

struct ScrapersStep: View {
    @Environment(SetupFlow.self) private var flow
    @State private var st = ScraperStatus()
    @State private var loaded = false
    @State private var adding = false
    @State private var readyAtOpen = false
    @State private var help = false

    var body: some View {
        let none = loaded && st.repos.isEmpty
        SetupFrame(primary: none ? SetupAction(title: "Add Repository") { readyAtOpen = st.ready; adding = true }
                                 : SetupAction(title: "Continue") { Task { await next() } },
                   secondary: none ? [helpAction] : [SetupAction(title: "Manage Scrapers") { flow.path.append(.manageScrapers) }, helpAction],
                   blocker: flow.blocker, shake: flow.shake) {
            SetupHead(art: .scrapers, counter: flow.counter, title: "Add Your Scrapers",
                      sentence: st.repos.isEmpty
                        ? "Scrapers are free add-ons that find the streams for your debrid service. They come in a repository: a web address you add here. Dream OS doesn't include any."
                        : "Your scrapers find the streams for your debrid service.")
            Group {
                if st.repos.isEmpty {
                    AddressExamples()
                } else {
                    VStack(spacing: SetupSpace.row) {
                        ForEach(st.repos) { r in
                            PageInfoRow(title: r.name,
                                        caption: [r.version.isEmpty ? nil : "Version \(r.version)", repoHost(r.url)].compactMap { $0 }.joined(separator: "  ·  "),
                                        lead: .monogram(String(r.name.prefix(1)).uppercased()), trailingMark: .ok)
                        }
                        let on = st.providers.filter(\.enabled).count
                        PageInfoRow(title: "Providers", caption: st.recommended ? "The recommended providers are on." : nil,
                                    value: "\(on) of \(st.providers.count) On", lead: .mark(on > 0 ? .ok : .warn))
                    }
                }
            }
            .padding(.top, SetupSpace.headToBody)
        }
        .task { await load() }
        .onReceive(NotificationCenter.default.publisher(for: .dreamScrapersChanged)) { _ in Task { await load() } }
        .sheet(isPresented: $adding, onDismiss: { Task { await addClosed() } }) {
            NavigationStack { AddRepoView(inSetup: true) { Task { await load() } } }
                .dreamNavigationLook()
                .presentationBackground(Palette.page)
        }
        .sheet(isPresented: $help) {
            SetupHelpSheet(topics: [
                .walkthrough(title: "Show Me How", caption: "Add your repository", intro: nil, steps: SetupGuide.scraperSteps),
                .trouble(title: "If Something Goes Wrong", caption: "Addresses that won't install", tips: SetupGuide.scraperTrouble),
            ])
        }
    }

    private var helpAction: SetupAction { SetupAction(title: "Help") { help = true } }

    private func load() async {
        st = await PagesServices.scrapers.status()
        loaded = true
        if flow.blocker != nil, flow.step == .scrapers { flow.blocker = st.ready ? nil : await SetupGate.scrapersBlocker() }
    }

    private func addClosed() async {
        await load()
        if st.ready && !readyAtOpen { flow.forward() }
    }

    private func next() async {
        if let why = await SetupGate.scrapersBlocker() { flow.block(why) } else { flow.forward() }
    }
}

// MARK: Connect: the check

struct CheckStep: View {
    @Environment(SetupFlow.self) private var flow
    @State private var checking = false
    @State private var line = ""
    @State private var result: ScraperTestResult?
    @State private var passed = SetupGate.checkPassed

    var body: some View {
        SetupFrame(primary: primary, blocker: flow.blocker, shake: flow.shake) {
            SetupHead(art: .check, check: checking ? .running : (passed ? .ok : (result == nil ? .idle : .fail)),
                      counter: flow.counter, title: "Making Sure It All Works",
                      sentence: "Dream OS runs one real search with your debrid service and scrapers, so you know everything plays before you start.")
            VStack(alignment: .leading, spacing: SetupSpace.group) {
                if checking || (!passed && result == nil) {
                    SetupResult(mark: .busy, headline: "Checking…", line: line)
                } else if passed {
                    SetupResult(mark: .ok, headline: "Ready to Watch",
                                line: result.map { "Found \($0.found) sources. \($0.ready) are ready to play." }
                                    ?? "Your services found streams the last time Dream OS checked.")
                    SetupSpeedCard { needsChoice in
                        // The check passed and the speed needs no choice: move on, unless the viewer came back here.
                        if !needsChoice, flow.cameForward { flow.forward(after: 1.5) }
                    } used: {
                        if flow.cameForward { flow.forward(after: 1.5) }
                    }
                } else if let r = result {
                    SetupResult(mark: .warn, headline: "Not Quite Ready", line: r.message)
                    ErrorHelp(lines: SetupGuide.checkHelp(r.message))
                }
            }
            .padding(.top, SetupSpace.headToBody)
        }
        .task { if !SetupGate.checkPassed { await run() } }
    }

    private var primary: SetupAction {
        if passed { return SetupAction(title: "Continue") { flow.forward() } }
        if checking || result == nil {
            return SetupAction(title: "Checking…") { flow.block("One moment. Dream OS is still checking.") }
        }
        return SetupAction(title: "Try Again") { Task { await run() } }
    }

    private func run() async {
        guard !checking else { return }
        checking = true
        line = ""
        let r = await PagesServices.scrapers.test { msg in Task { @MainActor in line = msg } }
        SetupGate.checkPassed = r.ok
        result = r
        passed = r.ok
        checking = false
        flow.blocker = nil
        if r.ok { PageHaptics.success() } else { PageHaptics.warning() }
    }
}

/// The check's connection card (DreamSetupMore.kt `SpeedCard`): the gauge, "Your Connection  ·  85 Mbps  ·  Great
/// for 4K", and a one-tap "Use 1080p" when the speed is too slow for the chosen Maximum Quality. The test starts when
/// the card appears and stops if the step is left. `finished` says whether a choice is waiting.
struct SetupSpeedCard: View {
    var finished: (Bool) -> Void = { _ in }
    var used: () -> Void = {}
    @State private var gauge = 0.0
    @State private var head = "Your Connection"
    @State private var line = "Testing your connection…"
    @State private var better: String?

    var body: some View {
        HStack(alignment: .center, spacing: 16) {
            SpeedGauge(gauge: gauge).frame(width: 56, height: 56)
            VStack(alignment: .leading, spacing: 4) {
                Text(head).font(.headline).foregroundStyle(Palette.text)
                    .fixedSize(horizontal: false, vertical: true)
                Text(line).font(.subheadline).foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let better {
                    Button("Use \(PlaybackPrefs.qualityLabel(better))") { use(better) }
                        .buttonStyle(PearlButtonStyle())
                        .padding(.top, 8)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.leading, 14).padding(.trailing, 18).padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.white.opacity(0.045)))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Color.white.opacity(0.08), lineWidth: 1))
        .task { await run() }
    }

    /// Log scale: 1 Mbps near the start, 200 Mbps at the end.
    private static func position(_ mbps: Double) -> Double { min(1, max(0, log(1 + mbps) / log(201))) }

    private func run() async {
        head = "Your Connection"
        line = "Testing your connection…"
        better = nil
        let mbps = await SpeedTest.measure(seconds: 4) { v in
            withAnimation(.easeInOut(duration: 0.24)) { gauge = Self.position(v) }
            line = "Testing…  \(Int(v)) Mbps"
        }
        if Task.isCancelled { return }
        guard let mbps else {
            withAnimation(.easeInOut(duration: 0.4)) { gauge = 0 }
            line = "The speed test couldn't run right now. Everything else is ready."
            finished(false)
            return
        }
        PlaybackPrefs.lastSpeed = mbps
        withAnimation(.easeInOut(duration: 0.6)) { gauge = Self.position(mbps) }
        head = "Your Connection  ·  \(Int(mbps)) Mbps  ·  \(SpeedTest.verdict(mbps))"
        // Not chosen yet ("Automatic"): Dream Core allows up to 4K, so the card measures against that.
        let q = PlaybackPrefs.quality ?? "4k"
        let label = PlaybackPrefs.qualityLabel(q)
        if let b = SpeedTest.suggestion(q, mbps) {
            line = "Too slow for smooth \(label). \(PlaybackPrefs.qualityLabel(b)) will play more smoothly."
            withAnimation(.easeOut(duration: 0.25)) { better = b }
            finished(true)
        } else {
            line = PlaybackPrefs.quality == nil ? "Fast enough for \(label)." : "Fast enough for \(label), your Maximum Quality."
            finished(false)
        }
    }

    private func use(_ q: String) {
        PageHaptics.success()
        PlaybackPrefs.setQuality(q)
        line = "Maximum Quality is now \(PlaybackPrefs.qualityLabel(q)). Change it anytime in Settings."
        withAnimation(.easeOut(duration: 0.25)) { better = nil }
        used()
    }
}

/// SetupArt's speed gauge with its needle eased between readings.
private struct SpeedGauge: View, Animatable {
    var gauge: Double
    nonisolated var animatableData: Double {
        get { gauge }
        set { gauge = newValue }
    }
    var body: some View { SetupArt(.speed, size: 56, gauge: gauge) }
}
