import SwiftUI
import DreamCore
import DreamUI
import DreamPlayback

// Make It Yours and You're All Set.

// MARK: Create Your Profile

struct ProfileStep: View {
    @Environment(SetupFlow.self) private var flow
    @FocusState private var nameFocused: Bool

    var body: some View {
        @Bindable var flow = flow
        SetupFrame(primary: SetupAction(title: "Continue") { Task { await next() } },
                   blocker: flow.blocker, shake: flow.shake) {
            SetupHead(art: .personalize, counter: flow.counter, title: "Create Your Profile",
                      sentence: "A name and a picture. Each profile keeps its own list, history and look.")
            HStack(spacing: 16) {
                ProfileFace(emoji: flow.emoji, color: flow.color, size: 64)
                VStack(alignment: .leading, spacing: 6) {
                    Text("Your Name").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.textSecondary)
                    TextField("", text: $flow.name, prompt: Text("Name").foregroundStyle(Color.white.opacity(0.55)))
                        .font(.body)
                        .foregroundStyle(Palette.text)
                        .tint(Palette.pearl)
                        .textInputAutocapitalization(.words)
                        .autocorrectionDisabled()
                        .submitLabel(.continue)
                        .focused($nameFocused)
                        .onSubmit { Task { await next() } }
                        .padding(.horizontal, 16)
                        .frame(minHeight: 48)
                        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color.white.opacity(0.07)))
                        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(nameFocused ? Palette.pearl.opacity(0.75) : Color.white.opacity(0.16), lineWidth: nameFocused ? 1.5 : 1))
                        .accessibilityLabel("Your Name")
                }
            }
            .padding(.top, SetupSpace.headToBody)
            label("Picture").padding(.top, SetupSpace.headToBody)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 6), spacing: 8) {
                ForEach(ProfileLook.emojis, id: \.self) { e in
                    Button {
                        PageHaptics.select()
                        flow.emoji = e
                    } label: {
                        Text(e).font(.system(size: 26))
                            .frame(width: 48, height: 48)
                            .background(Circle().fill(Color.white.opacity(e == flow.emoji ? 0.18 : 0.05)))
                            .overlay(Circle().strokeBorder(e == flow.emoji ? Palette.pearl.opacity(0.8) : .clear, lineWidth: 1.5))
                    }
                    .buttonStyle(CardPressStyle(radius: 24))
                    .accessibilityAddTraits(e == flow.emoji ? .isSelected : [])
                }
            }
            .padding(.top, 10)
            label("Color").padding(.top, SetupSpace.group)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 4), spacing: 6) {
                ForEach(Array(ProfileLook.colors.enumerated()), id: \.element) { i, c in
                    Button {
                        PageHaptics.select()
                        flow.color = c
                    } label: {
                        Circle().fill(Color(hex: UInt32(c)))
                            .frame(width: 34, height: 34)
                            .overlay { if c == flow.color { PageCheck(color: .white, weight: 0.11).frame(width: 34, height: 34) } }
                            .frame(width: 48, height: 48)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(CardPressStyle(radius: 24))
                    .accessibilityLabel("Color \(i + 1)")
                    .accessibilityAddTraits(c == flow.color ? .isSelected : [])
                }
            }
            .padding(.top, 6)
        }
    }

    private func label(_ s: String) -> some View {
        Text(s).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.textSecondary)
            .accessibilityAddTraits(.isHeader)
    }

    private func next() async {
        let n = flow.name.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty else {
            flow.block("Type your name first.")
            nameFocused = true
            return
        }
        nameFocused = false
        var p = flow.me ?? Profile(id: "p1", name: n)
        p.name = String(n.prefix(20))
        p.avatar = flow.emoji
        p.color = flow.color
        try? await Services.shared.profiles.save(p, pin: nil)
        flow.me = p
        NotificationCenter.default.post(name: .dreamProfileChanged, object: nil)
        flow.forward()
    }
}

// MARK: Who Else Watches?

struct WhoStep: View {
    @Environment(SetupFlow.self) private var flow

    var body: some View {
        SetupFrame(primary: SetupAction(title: "Continue") { flow.forward() }) {
            SetupHead(art: .profiles, counter: flow.counter, title: "Who Else Watches?",
                      sentence: "Add a profile for everyone at home. A Kids profile only shows movies and shows up to the rating you choose.")
            WhoList().padding(.top, SetupSpace.headToBody)
        }
    }
}

/// Every profile and Add a Profile (ProfilesPages.overview); each opens the profile editor in a sheet.
private struct WhoList: View {
    @State private var profiles: [Profile] = []
    @State private var current: String?
    @State private var edit: ProfilePickerView.EditTarget?

    var body: some View {
        VStack(spacing: SetupSpace.row) {
            ForEach(Array(profiles.enumerated()), id: \.element.id) { i, p in
                PageRow(title: p.name.isEmpty ? "Profile" : p.name, caption: ProfileLook.caption(p, current: current),
                        lead: .custom(AnyView(ProfileFace(emoji: ProfileLook.emoji(p, index: i), color: ProfileLook.color(p, index: i), size: 40))),
                        chevron: true) { edit = .existing(p) }
            }
            if profiles.count < ProfileLook.maxProfiles {
                PageRow(title: "Add a Profile", caption: "For someone else at home, or a Kids profile", lead: .symbol("plus"), chevron: true) { edit = .add }
            }
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

// MARK: Picture and Sound

/// The defaults as rows the viewer can change in one tap: Maximum Quality and Audio Language open their lists (picking
/// saves and comes back), Subtitles switches in place. Change Settings opens the full Sources & Quality page.
struct PictureSoundStep: View {
    @Environment(SetupFlow.self) private var flow
    @State private var tick = 0

    var body: some View {
        SetupFrame(primary: SetupAction(title: "Continue") { flow.forward() },
                   secondary: [SetupAction(title: "Change Settings") { flow.path.append(.allSourceSettings) }]) {
            let _ = tick
            SetupHead(art: .playback, counter: flow.counter, title: "Picture and Sound",
                      sentence: "These defaults suit most iPhones. Change them now, or anytime in Settings.")
            VStack(spacing: SetupSpace.row) {
                PageRow(title: "Maximum Quality", value: PlaybackPrefs.qualityLabel(PlaybackPrefs.quality),
                        lead: .symbol("sparkles.tv"), chevron: true) { flow.path.append(.quality) }
                PageRow(title: "Audio Language", value: PlaybackPrefs.audioLabel,
                        lead: .symbol("speaker.wave.2"), chevron: true) { flow.path.append(.audio) }
                PageRow(title: "Subtitles", caption: PlaybackPrefs.subsOn ? PlaybackPrefs.subLabel(PlaybackPrefs.subLang() ?? "English") : "Off",
                        lead: .symbol("captions.bubble"), toggle: PlaybackPrefs.subsOn) {
                    PlaybackPrefs.setSubsOn(!PlaybackPrefs.subsOn); tick += 1
                }
            }
            .padding(.top, SetupSpace.headToBody)
        }
        .onAppear { tick += 1 }
        .onChange(of: flow.path) { _, p in if p.isEmpty { tick += 1 } }
    }
}

// MARK: A Few Extras

/// The TV's A Few Extras: the anime options first (owner, 2026-10-29; fire-home 70e1b54), then the iPhone's own
/// extras. Each row is the same setting Settings shows. Four rows, so the step fits an iPhone SE without scrolling.
struct ExtrasStep: View {
    @Environment(SetupFlow.self) private var flow
    @State private var tick = 0

    var body: some View {
        SetupFrame(primary: SetupAction(title: "Continue") { flow.forward() }) {
            let _ = tick
            SetupHead(art: .personalize, counter: flow.counter, title: "A Few Extras",
                      sentence: "Small touches, already set the way most people like them. They're all in Settings too.")
            VStack(spacing: SetupSpace.row) {
                // AnimePrefs.apart, per profile; Home and Browse reload with it (PageSnapshots fingerprint).
                PageRow(title: "Keep Anime in Anime Sections", caption: "Anime shows only on the Anime page and its rows",
                        lead: .symbol("line.3.horizontal.decrease"), toggle: AnimePrefs.apart) {
                    AnimePrefs.apart.toggle(); tick += 1
                }
                // AnimeDubs (DreamCore), per profile: hidden when the audio language is Japanese.
                if AnimeDubs.offered {
                    PageRow(title: AnimeDubs.settingLabel, caption: AnimeDubs.settingCaption,
                            lead: .symbol("person.wave.2"), toggle: AnimeDubs.preferDubs) {
                        AnimeDubs.preferDubs.toggle(); tick += 1
                    }
                }
                // Per profile; a tap steps through On, Muted > On with Sound > Off, as in Settings.
                PageRow(title: "Background Trailers", caption: "Behind the picture at the top of title pages",
                        value: BackgroundTrailers.mode.label, lead: .symbol("play.rectangle")) {
                    BackgroundTrailers.cycle(); tick += 1
                }
                PageRow(title: "Autoplay Next Episode", caption: "Up Next counts down in the final minute",
                        lead: .symbol("play"), toggle: PlaybackSettings.autoplayNext) {
                    PlaybackSettings.autoplayNext.toggle(); tick += 1
                }
            }
            .padding(.top, SetupSpace.headToBody)
        }
        .onAppear { tick += 1 }
    }
}

// MARK: You're All Set

struct DoneStep: View {
    @Environment(SetupFlow.self) private var flow
    @State private var items: [(String, Bool)] = []

    var body: some View {
        SetupFrame(primary: SetupAction(title: "Start Watching") { Task { await flow.complete() } }, centered: true) {
            ProfileFace(emoji: flow.emoji, color: flow.color, size: 96).padding(.top, 20)
            Text(flow.name.isEmpty ? "You're All Set" : "You're All Set, \(flow.name)")
                .font(.largeTitle.weight(.bold))
                .foregroundStyle(Palette.text)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 22)
                .accessibilityAddTraits(.isHeader)
            Text("Change anything anytime in Settings.")
                .font(.body).foregroundStyle(Palette.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12, alignment: .leading), GridItem(.flexible(), alignment: .leading)],
                      alignment: .leading, spacing: 14) {
                ForEach(items, id: \.0) { it in
                    HStack(spacing: 10) {
                        PageMarkGlyph(mark: it.1 ? .ok : .off, size: 20)
                        Text(it.0)
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(it.1 ? Palette.text : Palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(it.1 ? it.0 : "\(it.0), not set up")
                }
            }
            .padding(.top, 28)
        }
        .task {
            let accounts = await SetupGate.accountsBlocker() == nil
            let scrapers = await PagesServices.scrapers.status().ready
            let ratings = !Keys.omdb.isEmpty
            let p = await EpisodeAlerts.permission()
            let alerts = EpisodeAlerts.enabled && (p == .authorized || p == .provisional || p == .ephemeral)
            items = [("Profile", true),
                     ("Movie & Show Info", SetupGate.hasTMDBKey),
                     (ratings ? "Ratings" : "Ratings Not Added", ratings),
                     (accounts ? "Accounts Connected" : "Accounts", accounts),
                     (scrapers ? "Scrapers Ready" : "Scrapers", scrapers),
                     (SetupGate.checkPassed ? "Ready to Watch" : "Final Check", SetupGate.checkPassed),
                     (alerts ? "New Episode Alerts On" : "New Episode Alerts Off", alerts)]
        }
    }
}
