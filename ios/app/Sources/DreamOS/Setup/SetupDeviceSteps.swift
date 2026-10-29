import SwiftUI
import UserNotifications
import DreamCore
import DreamUI
import DreamPlayback

// Your iPhone: the TV's Your Fire Stick chapter, with the iPhone's own device steps in the same place.

// MARK: Unlock Your Fire Stick (the TV's Unlock Dream OS Features, done from this iPhone)

struct FireStickStep: View {
    @Environment(SetupFlow.self) private var flow

    private static let features: [(String, String)] = [
        ("house", "Home Button"), ("power", "Opens at Startup"), ("mic", "Voice Search"), ("moon", "Screensaver"),
    ]

    var body: some View {
        // Optional and about another device, so Continue leads; unlocking is the quiet action.
        SetupFrame(primary: SetupAction(title: "Continue") { flow.forward() },
                   secondary: [SetupAction(title: "Unlock My Fire Stick") { flow.path.append(.unlockFireStick) }]) {
            SetupHead(art: .unlock, counter: flow.counter, title: "Unlock Your Fire Stick",
                      sentence: "Have a Fire TV with Dream OS? This iPhone unlocks it over your Wi-Fi in about a minute. It's optional, and it's in Settings too.")
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
                        Text(f.1).font(.subheadline.weight(.medium)).foregroundStyle(Palette.text)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .padding(.top, SetupSpace.headToBody)
        }
    }
}

// MARK: Get New Episode Alerts

struct AlertsStep: View {
    @Environment(SetupFlow.self) private var flow
    @Environment(\.openURL) private var openURL
    @State private var status: UNAuthorizationStatus?
    @State private var on = EpisodeAlerts.enabled

    var body: some View {
        SetupFrame(primary: primary, secondary: secondary) {
            SetupHead(art: .clock, counter: flow.counter, title: "Get New Episode Alerts",
                      sentence: "Your iPhone tells you when a show you follow has a new episode, even when Dream OS is closed.")
            Group {
                switch status {
                case .authorized, .provisional, .ephemeral:
                    VStack(alignment: .leading, spacing: SetupSpace.group) {
                        SetupResult(mark: on ? .ok : .off, headline: on ? "Alerts Are On" : "Alerts Are Off",
                                    line: "Only for shows you follow.")
                        PageRow(title: "New Episode Alerts", caption: "On this iPhone", lead: .symbol("bell"), toggle: on) {
                            on.toggle(); EpisodeAlerts.enabled = on
                        }
                    }
                case .denied:
                    SetupResult(mark: .off, headline: "Alerts Are Off",
                                line: "Notifications for Dream are off in iOS Settings. Turn them on there anytime.")
                default:
                    PageInfoRow(title: "New Episode Alerts", caption: "Only for shows you follow", lead: .symbol("bell"))
                }
            }
            .padding(.top, SetupSpace.headToBody)
        }
        .task { await refresh() }
    }

    private var undecided: Bool { status == .notDetermined }

    private var primary: SetupAction {
        guard undecided else { return SetupAction(title: "Continue") { flow.forward() } }
        return SetupAction(title: "Turn On Alerts") {
            Task {
                EpisodeAlerts.enabled = true
                on = true
                await EpisodeAlerts.shared.ask()
                await refresh()
                // iOS answered: the result shows, then setup moves on.
                flow.forward(after: 1.5)
            }
        }
    }

    private var secondary: [SetupAction] {
        if undecided { return [SetupAction(title: "Not Now") { flow.forward() }] }
        if status == .denied {
            return [SetupAction(title: "Open iOS Settings") {
                if let u = URL(string: UIApplication.openSettingsURLString) { openURL(u) }
            }]
        }
        return []
    }

    private func refresh() async {
        status = await EpisodeAlerts.permission()
        on = EpisodeAlerts.enabled
    }
}

// MARK: Cellular Data Saver

/// A single choice: picking one saves it, shows the check and moves on.
struct SaverStep: View {
    @Environment(SetupFlow.self) private var flow
    @State private var current = CellularSaver.mode.rawValue

    var body: some View {
        SetupFrame(primary: SetupAction(title: "Continue") { flow.forward() }) {
            SetupHead(art: .speed, counter: flow.counter, title: "Cellular Data Saver",
                      sentence: "On mobile data, Dream OS can play a smaller copy so you use less data. Wi-Fi always plays your Maximum Quality.")
            VStack(spacing: SetupSpace.row) {
                ForEach(CellularSaver.modeChoices, id: \.id) { c in
                    PageRow(title: c.label, caption: caption(c.id), lead: .mark(current == c.id ? .pick : .off)) {
                        PageHaptics.select()
                        CellularSaver.setMode(id: c.id)
                        current = c.id
                        flow.forward(after: 0.45)
                    }
                    .accessibilityAddTraits(current == c.id ? .isSelected : [])
                }
            }
            .padding(.top, SetupSpace.headToBody)
        }
    }

    private func caption(_ id: String) -> String {
        switch id {
        case "ask": "Dream OS asks the first time it sees mobile data"
        case "on": "Plays smaller copies on mobile data, up to \(CellularSaver.limit)"
        default: "Always plays your Maximum Quality"
        }
    }
}
