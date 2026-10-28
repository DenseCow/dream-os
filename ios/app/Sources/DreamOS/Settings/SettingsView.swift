import SwiftUI
import DreamCore
import DreamUI
import DreamPlayback

/// Settings (PremiumSettings.kt + MainActivity.settingsSections), grouped the same way minus the Fire TV-only parts:
/// who's watching, the accounts and scrapers Dream Core plays from, how things play, the look, time and region,
/// your own keys, About. Every row does something; values sit on the right, a short caption under the name.
/// Push it inside a NavigationStack (or present `SettingsSheet`).
struct SettingsView: View {
    @State private var profile: Profile?
    @State private var profileCount = 0
    @State private var accounts = "Not Connected"
    @State private var accountsTone: Color?
    @State private var scrapers = "Not Set Up"
    @State private var scrapersTone: Color?
    @State private var accountSummary = NoticePrefs.accountSummary
    @State private var premium: String?
    @State private var premiumTone: Color?
    @State private var checkingPremium = false
    @State private var trailers = BackgroundTrailers.mode
    @State private var episodeAlerts = EpisodeAlerts.enabled
    @State private var clock = DreamTime.clockSummary()
    @State private var zone = DreamTime.summary()
    @State private var tmdbSet = SecureStore.get("tmdb.key") != nil
    @State private var omdbSet = SecureStore.get("omdb.key") != nil
    @State private var choosingProfile = false
    @State private var runningSetup = false
    @State private var trakt = (value: TraktWords.value, caption: TraktWords.caption, tone: TraktWords.tone)

    var body: some View {
        PageScaffold(title: "Settings") {
            VStack(alignment: .leading, spacing: 0) {
                group("Profiles") {
                    PageRow(title: profileCount > 1 ? "Switch Profile" : "Profiles", caption: "Add, Edit or Switch Who's Watching",
                            value: profile?.name, lead: .symbol("person.2"), chevron: true) { choosingProfile = true }
                    PageRow(title: "Run Setup Again", caption: "Walk Through Dream OS Setup Step by Step",
                            lead: .symbol("sparkles"), chevron: true) { runningSetup = true }
                }
                group("Accounts & Scrapers") {
                    PageLinkRow(title: "Accounts", caption: "Your Debrid Services", value: accounts, valueTone: accountsTone,
                                lead: .symbol("key")) { AccountsView() }
                    PageLinkRow(title: "Trakt", caption: trakt.caption, value: trakt.value, valueTone: trakt.tone,
                                lead: .monogram("T")) { TraktView() }
                    PageLinkRow(title: "Scrapers", caption: "Your Scraper Repositories and Providers", value: scrapers, valueTone: scrapersTone,
                                lead: .symbol("magnifyingglass")) { ScrapersView() }
                    // DreamSetupMore.kt: the same page as setup's Playback Preferences.
                    PageLinkRow(title: "Sources & Quality", caption: "Quality, Languages, Subtitles and Size Limits",
                                value: PlaybackPrefs.summary, lead: .symbol("slider.horizontal.3")) { SourcesQualityView() }
                }
                group("Notifications") {
                    // Dream Notices (Notices/NoticesView.swift): account alerts and news, the startup summary switch.
                    PageLinkRow(title: "Notifications", caption: "Account Alerts and New Episodes",
                                value: unreadText, lead: .symbol("bell")) { NoticesView() }
                    PageRow(title: "Account Summary at Startup", caption: "Time Left on Each Account When Dream OS Starts",
                            lead: .symbol("calendar"), toggle: accountSummary) {
                        accountSummary.toggle(); NoticePrefs.accountSummary = accountSummary
                    }
                    // The phone's own alerts for new episodes (Notices/EpisodeAlerts.swift).
                    PageRow(title: "New Episode Alerts", caption: "On This iPhone When a Show You Follow Has a New Episode",
                            lead: .symbol("tv"), toggle: episodeAlerts) {
                        episodeAlerts.toggle(); EpisodeAlerts.enabled = episodeAlerts
                        if episodeAlerts { Task { await EpisodeAlerts.shared.ask() } }
                    }
                    PageRow(title: "Check Premium Accounts", caption: "Asks Each Linked Service When Its Plan Ends",
                            value: checkingPremium ? "Checking…" : premium, valueTone: premiumTone, lead: .symbol("checkmark.shield")) {
                        checkPremium()
                    }
                }
                // Playback and Sources & Quality (Settings/SourcesQualityView.swift).
                PlaybackSettingsGroups()
                group("Appearance") {
                    PageLinkRow(title: "Themes", caption: "Your Look · More Themes Are Coming", value: "Dream Cinema",
                                lead: .symbol("paintpalette")) { ThemesView() }
                    // HeroTrailer.kt's setting (Details/HeroTrailer.swift), per profile.
                    PageRow(title: "Background Trailers", caption: "Behind the Picture at the Top of Title Pages",
                            value: trailers.label, lead: .symbol("play.rectangle")) {
                        BackgroundTrailers.cycle(); trailers = BackgroundTrailers.mode
                    }
                }
                group("Time & Region") {
                    PageLinkRow(title: "Time Zone", caption: "New Episode Times, the Clock and Reminders", value: zone, lead: .symbol("clock")) {
                        TimeZoneView()
                    }
                    PageRow(title: "Clock Format", caption: "Now \(DreamTime.formatTime(Date()))", value: clock, lead: .symbol("clock.badge")) {
                        cycleClock()
                    }
                }
                group("Advanced") {
                    PageLinkRow(title: "TMDB Key", caption: "Titles, Artwork and Search", value: tmdbSet ? "Set" : "Built In",
                                lead: .symbol("film")) { KeyEntryView(kind: .tmdb) }
                    PageLinkRow(title: "OMDb Key", caption: "Rotten Tomatoes and IMDb Scores", value: omdbSet ? "Set" : "Built In",
                                lead: .symbol("star.leadinghalf.filled")) { KeyEntryView(kind: .omdb) }
                }
                group("About") {
                    PageInfoRow(title: "Dream OS", caption: "For iPhone", value: AppVersion.text, lead: .symbol("info.circle"))
                    // TMDB's terms: attribution wherever its data is used, its own sentence, shown whole.
                    PageInfoRow(title: "Movie and Show Data", caption: nil, value: "TMDB", lead: .symbol("film.stack"))
                    PageNote(text: "This product uses the TMDB API but is not endorsed or certified by TMDB.", size: 13.5)
                        .padding(.top, 4)
                }
            }
        }
        .task { await refresh() }
        .onAppear { Task { await refresh() } }
        .onReceive(NotificationCenter.default.publisher(for: DreamTime.changed)) { _ in
            zone = DreamTime.summary(); clock = DreamTime.clockSummary()
        }
        .onReceive(NotificationCenter.default.publisher(for: .dreamTraktChanged)) { _ in
            trakt = (TraktWords.value, TraktWords.caption, TraktWords.tone)
        }
        .fullScreenCover(isPresented: $choosingProfile) {
            ProfilePickerView(trustedId: profile?.id) { _ in
                choosingProfile = false
                Task { await refresh() }
            }
            .overlay(alignment: .topTrailing) {
                Button("Close") { choosingProfile = false }.buttonStyle(PearlButtonStyle()).padding(20)
            }
        }
        .fullScreenCover(isPresented: $runningSetup) {
            SetupWizardView(firstRun: false) { runningSetup = false; Task { await refresh() } }
        }
    }

    @ViewBuilder private func group<C: View>(_ title: String, @ViewBuilder _ rows: () -> C) -> some View {
        PageSection(title: title).padding(.top, title == "Profiles" ? 4 : PageSpace.group)
        VStack(spacing: PageSpace.row) { rows() }.padding(.top, 10)
    }

    /// "All Caught Up", "1 Unread", "3 Unread" (the TV's row value).
    private var unreadText: String {
        let n = NoticeBoard.shared.unread
        return n == 0 ? "All Caught Up" : "\(n) Unread"
    }

    /// Check Premium Accounts: asks every linked service now; the row says how it went.
    private func checkPremium() {
        guard !checkingPremium else { return }
        checkingPremium = true
        Task {
            let linked = await PagesServices.debrid.linkedOrder()
            let answered = linked.isEmpty ? 0 : await DebridExpiry.checkNow()
            checkingPremium = false
            if linked.isEmpty { premium = "No Account Linked"; premiumTone = nil; PageHaptics.warning() }
            else if answered > 0 { premium = "Checked"; premiumTone = Palette.greenText; PageHaptics.success() }
            else { premium = "No Answer Right Now"; premiumTone = Palette.amberText; PageHaptics.warning() }
            await refresh()
        }
    }

    private func cycleClock() {
        // Automatic -> 12-Hour -> 24-Hour -> Automatic.
        switch DreamTime.override24h {
        case nil: DreamTime.set24h(false)
        case false?: DreamTime.set24h(true)
        case true?: DreamTime.set24h(nil)
        }
        clock = DreamTime.clockSummary()
    }

    private func refresh() async {
        let ps = Services.shared.profiles
        profile = await ps.current()
        profileCount = await ps.profiles().count
        let d = PagesServices.debrid
        let linked = await d.linkedOrder()
        if linked.isEmpty {
            accounts = "Not Connected"; accountsTone = nil
        } else {
            var states: [DebridAccountState] = []
            for s in linked { states.append(await d.state(s)) }
            if states.contains(where: { $0.linked && ($0.expired || $0.health == .invalid) }) {
                accounts = "Needs Attention"; accountsTone = Palette.redText
            } else {
                accounts = linked.count == 1 ? DebridServices.name(linked[0]) : "\(linked.count) Connected"
                accountsTone = Palette.greenText
            }
        }
        let st = await PagesServices.scrapers.status()
        if st.repos.isEmpty { scrapers = "Not Set Up"; scrapersTone = nil }
        else if st.ready { scrapers = "\(st.providers.filter(\.enabled).count) On"; scrapersTone = Palette.greenText }
        else { scrapers = "Turn One On"; scrapersTone = Palette.amberText }
        tmdbSet = SecureStore.get("tmdb.key") != nil
        omdbSet = SecureStore.get("omdb.key") != nil
        trailers = BackgroundTrailers.mode
        zone = DreamTime.summary()
        clock = DreamTime.clockSummary()
        trakt = (TraktWords.value, TraktWords.caption, TraktWords.tone)
    }
}

/// Settings presented on its own (a sheet from the profile avatar): its own navigation stack and a Done button.
struct SettingsSheet: View {
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            SettingsView()
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .dreamNavigationLook()
    }
}

enum AppVersion {
    static var text: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
        return "Version \(v)"
    }
}

/// Themes: one Dream Cinema card and what's coming (Theme.FUTURE_NOTE).
struct ThemesView: View {
    var body: some View {
        PageScaffold(title: "Themes") {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 14) {
                    ZStack(alignment: .bottomLeading) {
                        LinearGradient(colors: [Palette.page, Color(hex: 0x111113)], startPoint: .top, endPoint: .bottom)
                        VStack(alignment: .leading, spacing: 8) {
                            Capsule().fill(Rainbow.linear).frame(width: 64, height: 4)
                            RoundedRectangle(cornerRadius: 6).fill(Palette.pearl).frame(width: 92, height: 22)
                            HStack(spacing: 6) {
                                ForEach(0..<4, id: \.self) { _ in RoundedRectangle(cornerRadius: 5).fill(Color.white.opacity(0.10)).frame(width: 44, height: 62) }
                            }
                        }
                        .padding(16)
                    }
                    .frame(height: 150)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Rainbow.gradient, lineWidth: 2))
                    HStack(spacing: 10) {
                        Text("Dream Cinema").font(.system(size: 19, weight: .semibold)).foregroundStyle(Palette.text)
                        Spacer(minLength: 8)
                        PageMarkGlyph(mark: .pick, size: 22)
                        Text("In Use").font(.system(size: 14, weight: .medium)).foregroundStyle(Palette.textSecondary)
                    }
                }
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color.white.opacity(0.045)))
                PageNote(text: "In a future update, you'll be able to download themes made by others and create your own.")
            }
        }
    }
}
