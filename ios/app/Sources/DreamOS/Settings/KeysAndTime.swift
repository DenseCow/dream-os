import SwiftUI
import DreamCore
import DreamUI

/// Your own TMDB / OMDb key (Settings › Accounts & Scrapers, setup's Your Shows step). The key is checked with one request and
/// kept in the Keychain (`SecureStore` "tmdb.key" / "omdb.key"); a key that fails is never saved. Remove goes back to
/// Dream OS's built-in key.
struct KeyEntryView: View {
    enum Kind {
        case tmdb, omdb
        var store: String { self == .tmdb ? "tmdb.key" : "omdb.key" }
        var title: String { self == .tmdb ? "TMDB Key" : "OMDb Key" }
        var note: String {
            self == .tmdb
                ? "Dream OS gets posters, details and search from TMDB. Your key is free: on TMDB's API page, copy the key labeled API Key (32 letters and numbers), not the much longer API Read Access Token, and paste it here."
                : "Rotten Tomatoes and IMDb scores come from OMDb. Your key is free and arrives by email: tap the link in that email to activate it, then copy the key and paste it here."
        }
        var site: URL { URL(string: self == .tmdb ? "https://www.themoviedb.org/settings/api" : "https://www.omdbapi.com/apikey.aspx")! }
        var siteButton: String { self == .tmdb ? "Open TMDB's API Page" : "Get a Free OMDb Key" }
        /// What to try when the key didn't pass its check, next to the error.
        var help: [String] {
            self == .tmdb
                ? ["Copy API Key again: the short one with 32 letters and numbers, not the long API Read Access Token. Paste it with nothing before or after it.",
                   "Still not working? Check that Wi-Fi or cellular data is on, then tap Save Key again."]
                : ["Open OMDb's email and tap the link in it to activate your key, then tap Save Key again. The email can take a few minutes.",
                   "Copy only the key, with nothing before or after it."]
        }
    }

    let kind: Kind
    var changed: () -> Void = {}
    @Environment(\.openURL) private var openURL
    @State private var key = ""
    @State private var state: PageMark = .none
    @State private var line: String?
    @State private var saved = false

    var body: some View {
        PageScaffold(title: kind.title) {
            VStack(alignment: .leading, spacing: 16) {
                PageNote(text: kind.note)
                PageSecondaryButton(title: kind.siteButton, systemImage: "safari") { openURL(kind.site) }
                SetupDisclosure(title: "Show Me How", caption: kind == .tmdb ? "Get your free key from TMDB" : "Get your free key from OMDb",
                                symbol: "list.number") {
                    GuideSteps(steps: kind == .tmdb ? SetupGuide.tmdbSteps : SetupGuide.omdbSteps,
                               intro: kind == .tmdb ? SetupGuide.tmdbIntro : nil)
                }
                PageTextField(placeholder: "Paste or Type Your Key", text: $key) { Task { await check() } }
                switch state {
                case .busy: PageInfoRow(title: "Checking Your Key", caption: "One Moment…", lead: .mark(.busy))
                case .ok: PageInfoRow(title: "Key Added", caption: line, lead: .mark(.ok))
                case .warn:
                    PageInfoRow(title: "That Key Didn't Work", caption: line, lead: .mark(.warn))
                    ErrorHelp(lines: kind.help)
                default: EmptyView()
                }
                PageButtonPair(spacing: 10) {
                    PageSecondaryButton(title: "Paste", systemImage: "doc.on.clipboard") {
                        if let s = UIPasteboard.general.string { key = s.trimmingCharacters(in: .whitespacesAndNewlines) }
                    }
                    PagePrimaryButton(title: "Save Key") { Task { await check() } }
                }
                // TMDB has no built-in key to go back to (Keys.tmdb is the viewer's own); only OMDb offers this.
                if saved && kind == .omdb {
                    PageRow(title: "Use the Built-In Key", caption: "Removes your own key from this iPhone") {
                        SecureStore.set(kind.store, nil)
                        saved = false
                        key = ""
                        state = .none
                        changed()
                    }
                }
            }
        }
        .onAppear { saved = SecureStore.get(kind.store) != nil }
    }

    private func check() async {
        let k = key.filter { !$0.isWhitespace }
        guard !k.isEmpty, state != .busy else { return }
        state = .busy
        let ok = await Self.test(kind, k)
        if ok {
            SecureStore.set(kind.store, k)
            saved = true
            state = .ok
            line = kind == .tmdb ? "Posters and details are ready." : "Rotten Tomatoes and IMDb scores are on."
            PageHaptics.success()
            changed()
        } else {
            state = .warn
            line = "Check it and try again."
            PageHaptics.warning()
        }
    }

    static func test(_ kind: Kind, _ key: String) async -> Bool {
        let q = key.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? key
        switch kind {
        case .tmdb:
            guard let u = URL(string: "https://api.themoviedb.org/3/configuration?api_key=\(q)") else { return false }
            return (try? await Net.data(u)) != nil
        case .omdb:
            guard let u = URL(string: "https://www.omdbapi.com/?apikey=\(q)&i=tt0111161"),
                  let o = try? await Net.object(u) else { return false }
            return (o["Response"] as? String) == "True"
        }
    }
}

/// Time Zone (TimeSettings.picker): Automatic, then the curated zones by region, each with its time now.
struct TimeZoneView: View {
    @Environment(\.dismiss) private var dismiss
    var pickedAndStay = false
    var picked: () -> Void = {}
    @State private var selected: String? = DreamTime.isAutomatic ? nil : DreamTime.zone.identifier

    var body: some View {
        PageScaffold(title: "Time Zone", subtitle: "Dream OS uses this to show when new episodes and movies come out.") {
            TimeZoneList(selected: $selected) { if !pickedAndStay { dismiss() }; picked() }
        }
    }
}

struct TimeZoneList: View {
    @Binding var selected: String?
    var picked: () -> Void

    var body: some View {
        let groups = Dictionary(grouping: DreamTime.choices(), by: { DreamTime.region($0.id) })
        let order = ["United States", "Americas", "Europe & Africa", "Asia & Pacific", "Other"]
        // Each zone's time moves on with the clock while the page is open.
        TimelineView(.everyMinute) { tick in
            let now = tick.date
            VStack(alignment: .leading, spacing: 0) {
                PageRow(title: "Automatic", caption: "\(DreamTime.labelFor(DreamTime.detected().identifier))  ·  \(DreamTime.formatTimeIn(now, DreamTime.detected()))",
                        lead: .mark(selected == nil ? .pick : .off)) { choose(nil) }
                ForEach(order.filter { groups[$0] != nil }, id: \.self) { region in
                    PageSection(title: region).padding(.top, PageSpace.group)
                    VStack(spacing: PageSpace.row) {
                        ForEach(groups[region] ?? []) { c in
                            let tz = TimeZone(identifier: c.id) ?? .current
                            PageRow(title: c.label, caption: DreamTime.offsetText(tz), value: DreamTime.formatTimeIn(now, tz),
                                    lead: .mark(selected == c.id ? .pick : .off)) { choose(c.id) }
                        }
                    }
                    .padding(.top, 10)
                }
            }
        }
    }

    private func choose(_ id: String?) {
        PageHaptics.select()
        selected = id
        DreamTime.setZone(id)
        picked()
    }
}

// Trakt lives in TraktView.swift.
