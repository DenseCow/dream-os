import SwiftUI
import DreamCore
import DreamUI

/// What "ready to watch" means and where setup left off (SetupGate). Stored in DreamStore (device-wide).
@MainActor
enum SetupGate {
    static var isDone: Bool {
        get { DreamStore.shared.get(Bool.self, "setup.done") ?? false }
        set { DreamStore.shared.set(newValue, "setup.done") }
    }
    static var savedStep: String? {
        get { DreamStore.shared.get(String.self, "setup.step") }
        set { DreamStore.shared.set(newValue, "setup.step") }
    }
    /// The step on screen right now, saved on every change: if iOS closes Dream while the viewer is in Safari (a
    /// sign-in, a key), setup reopens on that same step (owner, 2026-10-28).
    static var currentStep: String? {
        get { DreamStore.shared.get(String.self, "setup.current") }
        set { DreamStore.shared.set(newValue, "setup.current") }
    }
    /// Setup restores its place once per launch, never again when the screen reappears.
    @MainActor static var restored = false
    static var checkPassed: Bool {
        get { DreamStore.shared.get(Bool.self, "setup.checkOK") ?? false }
        set { DreamStore.shared.set(newValue, "setup.checkOK") }
    }
    static var introAccepted: Bool {
        get { DreamStore.shared.get(Bool.self, "setup.introOK") ?? false }
        set { DreamStore.shared.set(newValue, "setup.introOK") }
    }

    /// What still keeps the Accounts step from being done, in plain words; nil when it's done.
    static func accountsBlocker() async -> String? {
        let d = PagesServices.debrid
        let linked = await d.linkedOrder()
        var states: [DebridAccountState] = []
        for s in linked { states.append(await d.state(s)) }
        if states.contains(where: \.verified) { return nil }
        guard let first = states.first else { return "Connect at least one debrid service to continue." }
        let n = DebridServices.name(first.service)
        switch first.health {
        case .unchecked: return "Checking your account…"
        case .invalid: return "Sign in to \(n) again, or connect another service, to continue."
        default:
            if first.expired { return "Your \(n) plan has expired. Renew it, or connect another service, to continue." }
            return "Couldn't reach \(n). Check your internet connection, then choose \(n) to check again."
        }
    }

    static func scrapersBlocker() async -> String? {
        await PagesServices.scrapers.status().ready ? nil : "Add a scraper repository and turn on at least one provider to continue."
    }
}

/// First-run setup (SetupWizard.kt), one idea per screen, for a phone: Welcome, Before You Start, Your Time Zone,
/// Connect Your Accounts, Set Up Scrapers, Playback Preferences (optional), Getting You Ready (one real search, then
/// the connection speed card), Create Your Profile, Who's Watching?,
/// Your Shows (TMDB / OMDb keys), You're All Set. Every choice is saved the moment it's made; the required steps hold
/// Continue until they're met. Fire TV-only steps (Home button, unlock, Screen Fit, phone typing, AirPods, ad
/// blocking, moving to a new stick) are left out.
struct SetupWizardView: View {
    var firstRun: Bool
    var finish: () -> Void

    enum Step: String, CaseIterable {
        case welcome, intro, zone, accounts, scrapers, playback, check, profile, who, content, done
    }

    @State private var step: Step = .welcome
    @State private var forward = true
    @State private var blocker: String?
    @State private var shake = 0
    @State private var name = ""
    @State private var emoji = ProfileLook.emojis[0]
    @State private var color = ProfileLook.colors[0]
    @State private var me: Profile?
    @State private var check: ScraperTestResult?
    @State private var checking = false
    @State private var checkLine = ""
    @State private var zone: String? = DreamTime.isAutomatic ? nil : DreamTime.zone.identifier
    @State private var celebrating = false

    /// A build without a TMDB key (an open-source build) shows nothing until one is added; Continue waits for one.
    private static let needsTMDB = Keys.tmdb.trimmingCharacters(in: .whitespaces).isEmpty
    /// Owner's order (2026-10-28): the time zone first, then every account step back to back (TMDB key, debrid,
    /// scrapers, the check that they work together), then the steps that have nothing to do with accounts.
    private let steps: [Step] = [.welcome, .intro, .zone, .content, .accounts, .scrapers, .check, .playback, .profile, .who, .done]

    var body: some View {
        NavigationStack { wizard.toolbar(.hidden, for: .navigationBar) }
            .dreamNavigationLook()
            .onChange(of: step) { _, s in if firstRun { SetupGate.currentStep = s == .done ? nil : s.rawValue } }
    }

    private var wizard: some View {
        ZStack {
            DreamBackground()
            if celebrating {
                Celebration(name: name).transition(.opacity)
            } else {
                VStack(spacing: 0) {
                    topBar
                    ScrollViewReader { reader in
                        ScrollView {
                            Color.clear.frame(height: 0).id("setup.top")
                            content
                                .frame(maxWidth: 620, alignment: .leading)
                                .frame(maxWidth: .infinity)
                                .padding(.horizontal, 24)
                                .padding(.top, 12)
                                .padding(.bottom, 24)
                                .id(step)
                                .transition(.asymmetric(insertion: .move(edge: forward ? .trailing : .leading).combined(with: .opacity),
                                                        removal: .opacity))
                        }
                        .scrollIndicators(.hidden)
                        .scrollDismissesKeyboard(.interactively)
                        // Every new step starts at its top (owner, 2026-10-28), not where the last one was scrolled.
                        .onChange(of: step) { _, _ in reader.scrollTo("setup.top", anchor: .top) }
                    }
                    bottomBar
                }
            }
        }
        .task { await prepare() }
    }

    // MARK: Chrome

    private var topBar: some View {
        HStack {
            Button {
                back()
            } label: {
                Image(systemName: "chevron.left").font(.system(size: 18, weight: .semibold)).frame(width: 44, height: 44)
            }
            .foregroundStyle(Palette.text)
            .opacity(index > 0 ? 1 : 0)
            .disabled(index == 0)
            .accessibilityLabel("Back")
            Spacer()
            HStack(spacing: 8) {
                ForEach(steps.indices, id: \.self) { i in
                    Circle().fill(Palette.text)
                        .frame(width: 6, height: 6)
                        .opacity(i == index ? 1 : (i < index ? 0.5 : 0.2))
                        .scaleEffect(i == index ? 1.35 : 1)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Step \(index + 1) of \(steps.count)")
            Spacer()
            if !firstRun {
                Button("Close") { finish() }.font(.system(size: 16, weight: .medium)).foregroundStyle(Palette.text).frame(minWidth: 44, minHeight: 44)
            } else {
                Color.clear.frame(width: 44, height: 44)
            }
        }
        .padding(.horizontal, 12)
        .animation(.easeOut(duration: 0.3), value: index)
    }

    private var bottomBar: some View {
        VStack(spacing: 10) {
            if let blocker {
                Text(blocker)
                    .font(.system(size: 14))
                    .foregroundStyle(Palette.textSecondary)
                    .multilineTextAlignment(.center)
                    .modifier(Shake(amount: CGFloat(shake)))
                    .transition(.opacity)
            }
            PagePrimaryButton(title: primaryLabel) { Task { await next() } }
            if let skip = skipLabel {
                Button(skip) { Task { await next(skipping: true) } }
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(Palette.textSecondary)
                    .frame(minHeight: 36)
            }
        }
        .frame(maxWidth: 620)
        .padding(.horizontal, 24)
        .padding(.top, 10)
        .padding(.bottom, 12)
        .background(LinearGradient(colors: [Palette.page.opacity(0), Palette.page], startPoint: .top, endPoint: .center).ignoresSafeArea())
    }

    private var index: Int { steps.firstIndex(of: step) ?? 0 }

    private var primaryLabel: String {
        switch step {
        case .welcome: "Get Started"
        case .intro: "I Understand"
        case .check: SetupGate.checkPassed ? "Continue" : (checking ? "Checking…" : (check == nil ? "Check Now" : "Try Again"))
        case .done: "Start Watching"
        default: "Continue"
        }
    }

    private var skipLabel: String? {
        switch step {
        case .playback, .profile, .who: "Skip"
        // Required steps never offer Skip (owner, 2026-10-28): the TMDB step (passes at once when a key is built in)
        // and the final check (Check Later removed now that the scrapers answer).
        case .content, .check: nil
        default: nil
        }
    }

    // MARK: Steps

    @ViewBuilder private var content: some View {
        switch step {
        case .welcome:
            StepHead(symbol: "sparkles", title: "Welcome to Dream OS",
                     text: "Dream OS turns your iPhone into your own streaming service: every movie and show in one beautiful place, ready to play in seconds. Setup takes about 15 minutes, and every step shows you how.")
            VStack(spacing: PageSpace.row) {
                PageInfoRow(title: "Everything in One Place", caption: "Movies, shows and anime from every service", lead: .symbol("square.grid.2x2"))
                PageInfoRow(title: "Plays in Seconds", caption: "Straight from your own debrid account", lead: .symbol("bolt"))
                PageInfoRow(title: "Made for Everyone at Home", caption: "Profiles, Kids limits and PINs", lead: .symbol("person.2"))
            }
            .padding(.top, 24)
        case .intro:
            StepHead(symbol: "hand.raised", title: "Before You Start", tags: ["Required"],
                     text: "Dream OS doesn't include or host any movies, shows or sources. You connect your own accounts and add your own scraper repositories, and you're responsible for what you add.")
            // What You'll Need (owner, 2026-10-28): what's required, what gives the full recommended experience, what
            // each piece does and what it costs, before the steps ask for them.
            PageSection(title: "What You'll Need", required: true).padding(.top, 24)
            VStack(spacing: PageSpace.row) {
                // TMDB is always a requirement (owner, 2026-10-28).
                PageInfoRow(title: "A TMDB Key", caption: "Lets Dream show every poster, detail and search. Takes about 5 minutes", value: "Free")
                PageInfoRow(title: "A Debrid Service", caption: "The paid account that streams what you watch, in full quality. About $3 to $12 a month",
                            value: "Paid")
                PageInfoRow(title: "A Scraper Repository", caption: "A web address for the add-ons that find streams for your debrid service",
                            value: "Free")
            }
            .padding(.top, 10)
            PageSection(title: "For the Full Viewing Experience").padding(.top, 22)
            VStack(spacing: PageSpace.row) {
                PageInfoRow(title: "Premiumize", caption: "The debrid service with the fastest Play. TorBox works the same way",
                            value: "Recommended")
                PageInfoRow(title: "An OMDb Key", caption: Keys.omdb.isEmpty ? "Rotten Tomatoes and IMDb scores on every title"
                            : "Rotten Tomatoes and IMDb scores on every title. Already included", value: "Free")
                PageInfoRow(title: "Trakt", caption: "Your history, progress and watchlist, the same on every device and app", value: "Free")
                PageInfoRow(title: "A Profile for Everyone", caption: "Their own Continue Watching, recommendations and Kids limits")
            }
            .padding(.top, 10)
            PageNote(text: "Each step explains what it needs and shows you how, tap by tap. You'll make the keys and accounts on their own websites in Safari, and Dream keeps your place while you're away. Anything optional can be added later in Settings.", size: 13.5).padding(.top, 12)
            PageSection(title: "Your Privacy").padding(.top, 24)
            Text("Where your information goes:").font(.system(size: 16, weight: .medium)).foregroundStyle(Palette.text).padding(.top, 10).padding(.leading, 6)
            VStack(spacing: PageSpace.row) {
                ForEach([("TMDB", "Posters, artwork and details"), ("TVmaze", "New-episode air times"),
                         ("Your Debrid Service", "Finds and plays what you choose"), ("Your Scrapers", "Searches only the sites you add"),
                         ("Trakt", "Only if you link it")], id: \.0) { p in
                    PageInfoRow(title: p.0, caption: p.1)
                }
            }
            .padding(.top, 10)
            PageNote(text: "A few free services provide extras such as subtitles. Dream OS has no ads, tracking or analytics.", size: 13.5).padding(.top, 12)
        case .zone:
            StepHead(symbol: "clock", title: "Your Time Zone", text: "Dream OS uses this to show when new episodes and movies come out.")
            Text("\(DreamTime.label(DreamTime.zone))  ·  \(DreamTime.formatTime(Date()))")
                .font(.system(size: 20, weight: .semibold)).foregroundStyle(Palette.text).padding(.top, 18).padding(.leading, 6)
            TimeZoneList(selected: $zone) {}.padding(.top, 16)
        case .accounts:
            StepHead(symbol: "key", title: "Connect Your Accounts", tags: ["Required", "Paid", "About 5 Minutes"],
                     text: "A debrid service is a paid online account that streams movies and shows to Dream in full quality, with no waiting. Dream needs one to play anything. Most cost about $3 to $12 a month.")
            PageSection(title: "Which One to Pick").padding(.top, 22)
            PageNote(text: SetupGuide.pickLine, size: 15).padding(.top, 8)
            VStack(spacing: PageSpace.row) {
                // Open until setup's check has passed once (owner: beginners shouldn't have to find the help).
                SetupDisclosure(title: "Show Me How", caption: "Sign up, then connect it to Dream", symbol: "list.number",
                                startOpen: !SetupGate.checkPassed) {
                    GuideSteps(steps: SetupGuide.debridSteps())
                }
                SetupDisclosure(title: "If Something Goes Wrong", caption: "Expired codes, plans and keys", symbol: "questionmark.circle") {
                    TroubleList(tips: SetupGuide.debridTrouble)
                }
            }
            .padding(.top, 16)
            AccountsList(inSetup: true) { Task { await refreshBlocker() } }.padding(.top, 26)
            SetupDisclosure(title: "Show Me How to Connect Trakt", caption: "Optional and free. Add it now or later in Settings",
                            symbol: "list.number") {
                GuideSteps(steps: SetupGuide.traktSteps())
            }
            .padding(.top, 10)
        case .scrapers:
            StepHead(symbol: "magnifyingglass", title: "Set Up Scrapers", tags: ["Required", "Free", "About 2 Minutes"],
                     text: "Scrapers are free add-ons that search the web for streams of what you pick, so your debrid service can play them. They come in a repository: a web address you add here. Dream OS doesn't include any.")
            AddressExamples().padding(.top, 22)
            PageNote(text: "Repositories are shared by the people who make them, usually on a web page or on GitHub. If someone told you about Dream, ask them for the address they use.", size: 15)
                .padding(.top, 12)
            VStack(spacing: PageSpace.row) {
                SetupDisclosure(title: "Show Me How", caption: "Add it, then choose your providers", symbol: "list.number",
                                startOpen: !SetupGate.checkPassed) {
                    GuideSteps(steps: SetupGuide.scraperSteps)
                }
                SetupDisclosure(title: "If Something Goes Wrong", caption: "Addresses that won't install", symbol: "questionmark.circle") {
                    TroubleList(tips: SetupGuide.scraperTrouble)
                }
            }
            .padding(.top, 16)
            ScrapersList { Task { await refreshBlocker() } }.padding(.top, 26)
        case .playback:
            // SetupWizard.playbackStep: the Sources & Quality page's own groups, saved the moment they change.
            StepHead(symbol: "slider.horizontal.3", title: "Playback Preferences", tags: ["Optional"],
                     text: "Choose picture quality, languages, subtitles and sound. The defaults suit most people.")
            SourcesQualityRows().padding(.top, 22)
        case .check:
            StepHead(symbol: "checkmark.seal", title: "Getting You Ready", tags: ["Required", "About 30 Seconds"],
                     text: "Dream OS runs one real search with your debrid service and scrapers, so you know everything works before you start watching.")
            VStack(alignment: .leading, spacing: 10) {
                if SetupGate.checkPassed && check == nil {
                    PageResult(mark: .ok, headline: "Ready to Watch")
                } else if checking {
                    PageResult(mark: .busy, headline: "Checking…")
                    if !checkLine.isEmpty { PageNote(text: checkLine, size: 14) }
                } else if let r = check {
                    PageResult(mark: r.ok ? .ok : .warn, headline: r.ok ? "Found \(r.found) Sources  ·  \(r.ready) Ready to Play" : "Not Quite Ready")
                    PageNote(text: r.message, size: 14)
                    if !r.ok { ErrorHelp(lines: SetupGuide.checkHelp(r.message)).padding(.top, 6) }
                }
                // After the check passes: a short speed test, with "Use 1080p" when it's too slow for Maximum Quality.
                if SetupGate.checkPassed && !checking {
                    SetupSpeedCard().padding(.top, 16)
                }
                if !SetupGate.checkPassed && !checking && check == nil {
                    SetupDisclosure(title: "Show Me How", caption: "What happens when you check", symbol: "list.number", startOpen: true) {
                        GuideSteps(steps: SetupGuide.checkSteps)
                    }
                }
            }
            .padding(.top, 24)
        case .profile:
            StepHead(symbol: "person.crop.circle", title: "Create Your Profile", text: "A name and a picture. Each profile keeps its own list, history and look.")
            HStack(spacing: 16) {
                ProfileFace(emoji: emoji, color: color, size: 72)
                PageTextField(placeholder: "Your Name", text: $name, capitalization: .words)
            }
            .padding(.top, 22)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 6), spacing: 10) {
                ForEach(ProfileLook.emojis, id: \.self) { e in
                    Button { PageHaptics.select(); emoji = e } label: {
                        Text(e).font(.system(size: 26)).frame(width: 48, height: 48)
                            .background(Circle().fill(Color.white.opacity(e == emoji ? 0.16 : 0.05)))
                            .overlay(Circle().strokeBorder(e == emoji ? Palette.pearl.opacity(0.7) : .clear, lineWidth: 1.5))
                    }
                    .buttonStyle(CardPressStyle(radius: 24))
                }
            }
            .padding(.top, 18)
            HStack(spacing: 12) {
                ForEach(ProfileLook.colors, id: \.self) { c in
                    Button { PageHaptics.select(); color = c } label: {
                        Circle().fill(Color(hex: UInt32(c))).frame(width: 32, height: 32)
                            .overlay { if c == color { PageCheck(color: .white, weight: 0.11).frame(width: 32, height: 32) } }
                    }
                    .buttonStyle(CardPressStyle(radius: 16))
                }
            }
            .padding(.top, 14)
        case .who:
            StepHead(symbol: "person.2", title: "Who's Watching?", tags: ["Optional"],
                     text: "Add a profile for everyone at home. A Kids profile only shows movies and shows up to the rating you choose.")
            WhoList().padding(.top, 22)
        case .content:
            StepHead(symbol: "film", title: "Connect Movie & Show Info", tags: ["Required", "Free", "About 5 Minutes"],
                     text: Keys.tmdb.isEmpty
                        ? "TMDB is a free movie and TV website. Every poster, detail and search in Dream comes from it, so Dream needs your own free TMDB key before it can show anything."
                        : "TMDB is a free movie and TV website. Every poster, detail and search in Dream comes from it. Your TMDB key is added, so you can continue.")
            PageSection(title: "Your TMDB Key", required: true).padding(.top, 22)
            VStack(spacing: PageSpace.row) {
                NavigationStackLink(title: "Movie & Show Info", caption: "Posters, details and search", kind: .tmdb)
                SetupDisclosure(title: "Show Me How", caption: "Get your free key from TMDB", symbol: "list.number",
                                startOpen: Keys.tmdb.isEmpty) {
                    GuideSteps(steps: SetupGuide.tmdbSteps, intro: SetupGuide.tmdbIntro)
                }
                SetupDisclosure(title: "If Something Goes Wrong", caption: "Keys that don't work", symbol: "questionmark.circle") {
                    TroubleList(tips: SetupGuide.tmdbTrouble)
                }
            }
            .padding(.top, 10)
            PageSection(title: "Ratings", required: false).padding(.top, PageSpace.group)
            PageNote(text: "A free OMDb key adds Rotten Tomatoes and IMDb scores to every title. You can add it now or later in Settings.", size: 15)
                .padding(.top, 8)
            VStack(spacing: PageSpace.row) {
                NavigationStackLink(title: "Ratings", caption: "Rotten Tomatoes and IMDb scores", kind: .omdb)
                SetupDisclosure(title: "Show Me How", caption: "Get your free key from OMDb", symbol: "list.number") {
                    VStack(alignment: .leading, spacing: 22) {
                        GuideSteps(steps: SetupGuide.omdbSteps)
                        VStack(alignment: .leading, spacing: 12) {
                            PageSection(title: "If Something Goes Wrong").padding(.leading, -6)
                            TroubleList(tips: SetupGuide.omdbTrouble)
                        }
                    }
                }
            }
            .padding(.top, 10)
        case .done:
            VStack(spacing: 14) {
                ProfileFace(emoji: emoji, color: color, size: 100).padding(.top, 30)
                Text(name.isEmpty ? "You're All Set" : "You're All Set, \(name)")
                    .font(.system(size: 30, weight: .bold)).foregroundStyle(Palette.text).multilineTextAlignment(.center)
                Text("Change anything anytime in Settings.").font(.system(size: 16)).foregroundStyle(Palette.textSecondary)
                DoneChecks().padding(.top, 14)
            }
            .frame(maxWidth: .infinity)
        }
    }

    // MARK: Flow

    private func prepare() async {
        me = await Services.shared.profiles.current()
        if let me {
            name = (firstRun && me.name == "Me") ? "" : me.name
            if !me.avatar.isEmpty { emoji = me.avatar }
            if let c = me.color { color = c }
        }
        guard firstRun, !SetupGate.restored else { return }
        SetupGate.restored = true
        if let c = SetupGate.currentStep, let cur = Step(rawValue: c), steps.contains(cur) {
            step = cur // reopens exactly where the viewer was
        } else if let s = SetupGate.savedStep, let saved = Step(rawValue: s),
           let i = steps.firstIndex(of: saved), i + 1 < steps.count {
            step = steps[i + 1] // an interrupted setup picks up after the last finished step
        }
    }

    private func back() {
        guard index > 0 else { return }
        blocker = nil
        forward = false
        withAnimation(.easeInOut(duration: 0.32)) { step = steps[index - 1] }
    }

    private func refreshBlocker() async {
        guard blocker != nil else { return }
        blocker = await blockerFor(step)
    }

    private func blockerFor(_ s: Step) async -> String? {
        switch s {
        case .accounts: return await SetupGate.accountsBlocker()
        case .scrapers: return await SetupGate.scrapersBlocker()
        case .check: return SetupGate.checkPassed ? nil : "Run the check to continue."
        case .profile: return name.trimmingCharacters(in: .whitespaces).isEmpty ? "Type your name first." : nil
        case .content: return Keys.tmdb.trimmingCharacters(in: .whitespaces).isEmpty ? "Add your TMDB key to continue." : nil
        default: return nil
        }
    }

    private func next(skipping: Bool = false) async {
        if step == .check && !SetupGate.checkPassed && !skipping { await runCheck(); return }
        if !skipping, let why = await blockerFor(step) {
            PageHaptics.warning()
            withAnimation(.easeOut(duration: 0.2)) { blocker = why }
            withAnimation(.linear(duration: 0.4)) { shake += 1 }
            return
        }
        blocker = nil
        switch step {
        case .intro: SetupGate.introAccepted = true
        case .profile where !skipping: await saveProfile()
        case .done:
            SetupGate.isDone = true
            SetupGate.savedStep = nil
            PageHaptics.success()
            withAnimation(.easeOut(duration: 0.4)) { celebrating = true }
            try? await Task.sleep(for: .seconds(2.6))
            finish()
            return
        default: break
        }
        SetupGate.savedStep = step.rawValue
        forward = true
        PageHaptics.tap()
        withAnimation(.easeInOut(duration: 0.32)) { step = steps[min(index + 1, steps.count - 1)] }
    }

    private func saveProfile() async {
        let n = name.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty else { return }
        var p = me ?? Profile(id: "p1", name: n)
        p.name = String(n.prefix(20))
        p.avatar = emoji
        p.color = color
        try? await Services.shared.profiles.save(p, pin: nil)
        me = p
        NotificationCenter.default.post(name: .dreamProfileChanged, object: nil)
    }

    private func runCheck() async {
        guard !checking else { return }
        checking = true
        checkLine = ""
        blocker = nil
        let r = await PagesServices.scrapers.test { msg in Task { @MainActor in checkLine = msg } }
        SetupGate.checkPassed = r.ok
        check = r
        checking = false
        if r.ok { PageHaptics.success() } else { PageHaptics.warning() }
    }
}

/// A step's illustration, title, its facts ("Required", "Free", "About 5 Minutes") and one plain explanation.
private struct StepHead: View {
    var symbol: String
    var title: String
    var tags: [String] = []
    var text: String
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            PageArt(symbol: symbol, size: 84)
            Text(title).font(.system(size: 30, weight: .bold)).tracking(-0.3).foregroundStyle(Palette.text).padding(.top, 8)
                .fixedSize(horizontal: false, vertical: true)
            if !tags.isEmpty {
                WrapLayout(spacing: 8, lineSpacing: 8) {
                    ForEach(Array(tags.enumerated()), id: \.offset) { i, t in
                        SetupTag(text: t, strong: i == 0 && t == "Required")
                    }
                }
            }
            Text(text).font(.system(size: 16)).foregroundStyle(Palette.textSecondary).lineSpacing(3).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A key row that opens the key page in a sheet (setup has no navigation stack of its own).
private struct NavigationStackLink: View {
    var title: String
    var caption: String
    var kind: KeyEntryView.Kind
    @State private var open = false
    @State private var set = false
    var body: some View {
        // TMDB has no built-in key (Keys.tmdb is the viewer's own); OMDb may.
        PageRow(title: title, caption: caption, value: set ? "Added" : (kind == .tmdb || Keys.omdb.isEmpty ? "Not Added" : "Built In"),
                valueTone: set ? Palette.greenText : nil,
                trailingMark: set ? .ok : .none, chevron: true) { open = true }
            .onAppear { set = SecureStore.get(kind.store) != nil }
            .sheet(isPresented: $open, onDismiss: { set = SecureStore.get(kind.store) != nil }) {
                NavigationStack { KeyEntryView(kind: kind).toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { open = false } } } }
                    .dreamNavigationLook()
                    .presentationBackground(Palette.page)
            }
    }
}

/// Who's Watching in setup: every profile, Add a Profile (ProfilesPages.overview).
private struct WhoList: View {
    @State private var profiles: [Profile] = []
    @State private var current: String?
    @State private var edit: ProfilePickerView.EditTarget?
    var body: some View {
        VStack(spacing: PageSpace.row) {
            ForEach(Array(profiles.enumerated()), id: \.element.id) { i, p in
                PageRow(title: p.name.isEmpty ? "Profile" : p.name, caption: ProfileLook.caption(p, current: current),
                        lead: .custom(AnyView(ProfileFace(emoji: ProfileLook.emoji(p, index: i), color: ProfileLook.color(p, index: i), size: 40))),
                        chevron: true) { edit = .existing(p) }
            }
            if profiles.count < ProfileLook.maxProfiles {
                PageRow(title: "Add a Profile", caption: "For someone else at home, or a Kids profile", lead: .symbol("plus"), chevron: true) { edit = .add }
            }
            PageNote(text: "Each profile keeps its own list, history and look.", size: 14).padding(.top, 10)
        }
        .task { await load() }
        .sheet(item: $edit) { t in
            NavigationStack {
                switch t {
                case .existing(let p): ProfileEditView(profile: p, isFirst: profiles.first?.id == p.id, isCurrent: p.id == current) { Task { await load() } }
                case .add: ProfileEditView(profile: nil, isFirst: false, isCurrent: false) { Task { await load() } }
                }
            }
            .dreamNavigationLook()
            .presentationBackground(Palette.page)
        }
    }
    private func load() async {
        profiles = await Services.shared.profiles.profiles()
        current = await Services.shared.profiles.current()?.id
    }
}

/// The final check's connection card (DreamSetupMore.kt `SpeedCard`): the gauge, "Your Connection  ·  85 Mbps  ·  Great
/// for 4K", and a one-tap "Use 1080p" when the speed is too slow for the chosen Maximum Quality. The test starts when
/// the card appears and stops if the step is left.
private struct SetupSpeedCard: View {
    @State private var gauge = 0.0
    @State private var head = "Your Connection"
    @State private var line = "Testing your connection…"
    @State private var better: String?

    var body: some View {
        HStack(alignment: .center, spacing: 16) {
            SpeedGauge(gauge: gauge).frame(width: 64, height: 64)
            VStack(alignment: .leading, spacing: 4) {
                Text(head).font(.system(size: 17, weight: .semibold)).foregroundStyle(Palette.text)
                    .fixedSize(horizontal: false, vertical: true)
                Text(line).font(.system(size: 14)).foregroundStyle(Palette.textSecondary)
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
        } else {
            line = PlaybackPrefs.quality == nil ? "Fast enough for \(label)." : "Fast enough for \(label), your Maximum Quality."
        }
    }

    private func use(_ q: String) {
        PageHaptics.success()
        PlaybackPrefs.setQuality(q)
        line = "Maximum Quality is now \(PlaybackPrefs.qualityLabel(q)). Change it anytime in Settings."
        withAnimation(.easeOut(duration: 0.25)) { better = nil }
    }
}

/// SetupArt's speed gauge with its needle eased between readings.
private struct SpeedGauge: View, Animatable {
    var gauge: Double
    nonisolated var animatableData: Double {
        get { gauge }
        set { gauge = newValue }
    }
    var body: some View { SetupArt(.speed, size: 64, gauge: gauge) }
}

/// The summary on You're All Set: a drawn check when done, a quiet ring when not.
private struct DoneChecks: View {
    @State private var items: [(String, Bool)] = []
    var body: some View {
        WrapLayout(spacing: 10, lineSpacing: 10) {
            ForEach(items, id: \.0) { it in
                HStack(spacing: 8) {
                    PageMarkGlyph(mark: it.1 ? .ok : .off, size: 15)
                    Text(it.0).font(.system(size: 13.5, weight: .medium)).foregroundStyle(it.1 ? Palette.text : Palette.textSecondary)
                }
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(Capsule().fill(Color.white.opacity(0.06)))
            }
        }
        .task {
            let accounts = await SetupGate.accountsBlocker() == nil
            let scrapers = await PagesServices.scrapers.status().ready
            let omdb = SecureStore.get("omdb.key") != nil
            items = [("Profile", true),
                     (accounts ? "Accounts Connected" : "Accounts", accounts),
                     (scrapers ? "Scrapers Ready" : "Scrapers", scrapers),
                     (SetupGate.checkPassed ? "Ready to Watch" : "Final Check", SetupGate.checkPassed),
                     (omdb ? "Your Ratings Key" : "Built-In Ratings", true)]
        }
    }
}

/// The send-off: "You're All Set" in the middle, the rainbow halo, "Welcome to Dream OS".
struct Celebration: View {
    var name: String
    @State private var on = false
    var body: some View {
        VStack(spacing: 22) {
            ZStack {
                Circle().fill(Rainbow.gradient).blur(radius: 30).opacity(on ? 0.55 : 0).frame(width: 180, height: 180)
                    .rotationEffect(.degrees(on ? 90 : 0))
                Text("Dream OS").font(.system(size: 40, weight: .bold)).foregroundStyle(Rainbow.linear)
                    .scaleEffect(on ? 1 : 0.9).opacity(on ? 1 : 0)
            }
            Text("Welcome to Dream OS").font(.system(size: 18, weight: .medium)).foregroundStyle(Palette.textSecondary).opacity(on ? 1 : 0)
        }
        .onAppear { withAnimation(.easeOut(duration: 1.6)) { on = true } }
    }
}
