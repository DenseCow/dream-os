import SwiftUI
import DreamCore
import DreamUI

// Sheets over a step: a key, Help (one step per page), Where Your Information Goes.

// MARK: Keys

enum SetupKeyKind: String, Identifiable {
    case tmdb, omdb
    var id: String { rawValue }
    var kind: KeyEntryView.Kind { self == .tmdb ? .tmdb : .omdb }
}

/// Enter a TMDB or OMDb key: Paste checks it at once (iOS's own Paste button, so no paste prompt). A key that passes
/// shows Key Added, and the sheet closes by itself. Setup never offers a built-in key.
struct SetupKeySheet: View {
    let kind: SetupKeyKind
    @Environment(\.dismiss) private var dismiss
    @State private var key = ""
    @State private var state: PageMark = .none
    @State private var howTo = false
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            PageScaffold(title: kind == .tmdb ? "Enter Your TMDB Key" : "Add Rotten Tomatoes Scores") {
                VStack(alignment: .leading, spacing: SetupSpace.group) {
                    Text(kind == .tmdb
                         ? "On TMDB's API page, copy the key labeled API Key (32 letters and numbers), then tap Paste."
                         : "Scores come from OMDb. Your free key arrives by email: tap the link in it to activate the key, copy it, then tap Paste.")
                        .font(.body).foregroundStyle(Palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    switch state {
                    case .busy:
                        SetupResult(mark: .busy, headline: "Checking Your Key…", line: nil)
                    case .ok:
                        SetupResult(mark: .ok, headline: "Key Added",
                                    line: kind == .tmdb ? "Posters, details and search are ready." : "Rotten Tomatoes and IMDb scores are on.")
                    default:
                        if state == .warn {
                            SetupResult(mark: .warn, headline: "That Key Didn't Work", line: "Check it and try again.")
                            ErrorHelp(lines: kind.kind.help)
                        }
                        TextField("", text: $key, prompt: Text("Or Type Your Key").foregroundStyle(Color.white.opacity(0.55)))
                            .font(.body.monospaced())
                            .foregroundStyle(Palette.text)
                            .tint(Palette.pearl)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .submitLabel(.done)
                            .focused($focused)
                            .onSubmit { Task { await check() } }
                            .padding(.horizontal, 16)
                            .frame(minHeight: 50)
                            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color.white.opacity(0.07)))
                            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .strokeBorder(focused ? Palette.pearl.opacity(0.75) : Color.white.opacity(0.16), lineWidth: focused ? 1.5 : 1))
                            .accessibilityLabel(kind == .tmdb ? "TMDB key" : "OMDb key")
                        // One primary at a time: Paste while the field is empty, Save Key once something is typed.
                        if key.trimmingCharacters(in: .whitespaces).isEmpty {
                            PasteButton(payloadType: String.self) { strings in
                                guard let s = strings.first else { return }
                                Task { @MainActor in
                                    key = s.trimmingCharacters(in: .whitespacesAndNewlines)
                                    await check()
                                }
                            }
                            .buttonBorderShape(.roundedRectangle(radius: Cinema.rButton))
                            .controlSize(.large)
                            // A secure system control draws white words on its tint: a dark gray keeps them legible.
                            .tint(Color(hex: 0x3A3A3C))
                            .labelStyle(.titleAndIcon)
                            .frame(maxWidth: .infinity)
                        } else {
                            SetupPrimaryButton(title: "Save Key") { Task { await check() } }
                        }
                        PageRow(title: kind == .tmdb ? "Open TMDB's API Page" : "Get a Free OMDb Key",
                                caption: kind == .tmdb ? "themoviedb.org/settings/api" : "omdbapi.com/apikey.aspx",
                                lead: .symbol("safari")) { UIApplication.shared.open(kind.kind.site) }
                        PageRow(title: "Show Me How", caption: "Every step, one at a time", lead: .symbol("list.number"), chevron: true) {
                            howTo = true
                        }
                    }
                }
            }
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .navigationDestination(isPresented: $howTo) {
                WalkthroughPage(title: "Show Me How",
                                intro: kind == .tmdb ? SetupGuide.tmdbIntro : nil,
                                steps: kind == .tmdb ? SetupGuide.tmdbSetupSteps : SetupGuide.omdbSetupSteps) { howTo = false }
            }
        }
        .dreamNavigationLook()
        .presentationBackground(Palette.page)
        .interactiveDismissDisabled(state == .busy)
    }

    private func check() async {
        let k = key.filter { !$0.isWhitespace }
        guard !k.isEmpty, state != .busy else { return }
        focused = false
        state = .busy
        if await KeyEntryView.test(kind.kind, k) {
            SecureStore.set(kind.kind.store, k)
            state = .ok
            PageHaptics.success()
            try? await Task.sleep(for: .seconds(1.5))
            dismiss()
        } else {
            state = .warn
            PageHaptics.warning()
        }
    }
}

// MARK: Help

/// One row in a Help sheet.
enum HelpTopic {
    case walkthrough(title: String, caption: String, intro: String?, steps: [GuideStep])
    case trouble(title: String, caption: String, tips: [TroubleTip])
    case page(title: String, caption: String, view: AnyView)

    var title: String {
        switch self {
        case .walkthrough(let t, _, _, _), .trouble(let t, _, _), .page(let t, _, _): t
        }
    }
    var caption: String {
        switch self {
        case .walkthrough(_, let c, _, _), .trouble(_, let c, _), .page(_, let c, _): c
        }
    }
    var symbol: String {
        switch self {
        case .walkthrough: "list.number"
        case .trouble: "questionmark.circle"
        case .page: "info.circle"
        }
    }
}

/// A step's Help: a short list of topics, each on a page of its own (swipe back returns).
struct SetupHelpSheet: View {
    var topics: [HelpTopic]
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            PageScaffold(title: "Help") {
                VStack(spacing: SetupSpace.row) {
                    ForEach(Array(topics.enumerated()), id: \.offset) { _, t in
                        PageLinkRow(title: t.title, caption: t.caption, lead: .symbol(t.symbol)) { destination(t) }
                    }
                }
            }
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }
        .dreamNavigationLook()
        .presentationBackground(Palette.page)
    }

    @ViewBuilder private func destination(_ t: HelpTopic) -> some View {
        switch t {
        case .walkthrough(let title, _, let intro, let steps):
            WalkthroughPage(title: title, intro: intro, steps: steps) { dismiss() }
        case .trouble(let title, _, let tips):
            PageScaffold(title: title) { TroubleList(tips: tips) }
        case .page(_, _, let view):
            view
        }
    }
}

/// Help as a walkthrough: one step per page, "Step 2 of 6", the website button on the page, and one button (Next
/// Step, then Done). Pages also swipe sideways.
struct WalkthroughPage: View {
    var title: String
    var intro: String?
    var steps: [GuideStep]
    var done: () -> Void
    @State private var page = 0
    @Environment(\.openURL) private var openURL

    var body: some View {
        ZStack {
            DreamBackground()
            VStack(spacing: 0) {
                TabView(selection: $page) {
                    ForEach(Array(steps.enumerated()), id: \.offset) { i, s in
                        ScrollView {
                            VStack(alignment: .leading, spacing: 14) {
                                Text("Step \(i + 1) of \(steps.count)")
                                    .font(.subheadline.weight(.medium))
                                    .foregroundStyle(Palette.textSecondary)
                                if i == 0, let intro {
                                    Text(intro).font(.subheadline).foregroundStyle(Palette.textSecondary)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                                Text(s.text)
                                    .font(.title3.weight(.semibold))
                                    .foregroundStyle(Palette.text)
                                    .fixedSize(horizontal: false, vertical: true)
                                    .accessibilityAddTraits(.isHeader)
                                if let see = s.see {
                                    Text(see).font(.body).foregroundStyle(Palette.textSecondary).lineSpacing(2)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                                if s.keyArt { TMDBKeyArt().padding(.top, 4) }
                                if !s.links.isEmpty {
                                    VStack(spacing: SetupSpace.row) {
                                        ForEach(s.links, id: \.self) { l in
                                            PageRow(title: l.title, caption: l.url.host()?.replacingOccurrences(of: "www.", with: ""),
                                                    lead: .symbol("safari")) { openURL(l.url) }
                                        }
                                    }
                                    .padding(.top, 4)
                                }
                            }
                            .frame(maxWidth: 560, alignment: .leading)
                            .frame(maxWidth: .infinity)
                            .padding(.horizontal, SetupSpace.gutter)
                            .padding(.top, 8)
                            .padding(.bottom, 16)
                        }
                        .scrollBounceBehavior(.basedOnSize)
                        .tag(i)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                SetupPrimaryButton(title: page < steps.count - 1 ? "Next Step" : "Done") {
                    if page < steps.count - 1 { withAnimation(.easeInOut(duration: 0.3)) { page += 1 } } else { done() }
                }
                .frame(maxWidth: 560)
                .padding(.horizontal, SetupSpace.gutter)
                .padding(.top, 12)
                .padding(.bottom, 10)
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: Where Your Information Goes

struct PrivacySheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            PageScaffold(title: "Where Your Information Goes") {
                VStack(alignment: .leading, spacing: SetupSpace.row) {
                    ForEach([("TMDB", "Posters, artwork and details"), ("TVmaze", "New-episode air times"),
                             ("Your Debrid Service", "Finds and plays what you choose"), ("Your Scrapers", "Searches only the sites you add"),
                             ("Trakt", "Only if you link it")], id: \.0) { p in
                        PageInfoRow(title: p.0, caption: p.1)
                    }
                    PageNote(text: "A few free services provide extras such as subtitles. Dream OS has no ads, tracking or analytics.", size: 15)
                        .padding(.top, 8)
                }
            }
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }
        .dreamNavigationLook()
        .presentationBackground(Palette.page)
    }
}
