import SwiftUI
import DreamCore
import DreamUI
import DreamContent
import DreamPlayback
import DreamScrapers
import DreamPlayer

@main
struct DreamOSApp: App {
    @UIApplicationDelegateAdaptor(OrientationDelegate.self) private var orientation

    init() {
        Keys.adoptOwnersKeys() // the owner's builds: their TMDB key saved as their own, before anything asks TMDB
        // Real services replace the stand-ins here as each module lands (docs/PORT.md).
        DreamContent.register()
        DreamPlayback.register()
        DreamScrapers.register()
        Wiring.install()
        PillAhead.seed() // the stored pill answers into the cards' memory before Home's first rows draw
        LanguagePills.warm() // the title pages' saved language answers, read before the first page opens
        EpisodeAlerts.registerBackground() // new-episode alerts: the background look + tap routing (Notices/EpisodeAlerts.swift)
        InstantPlayRefresh.register() // Continue Watching's links kept fresh in the background on Wi-Fi (Shell/InstantPlayRefresh.swift)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .preferredColorScheme(.dark)
        }
    }
}

/// Browsing is portrait only, always (owner, 2026-10-27). The one exception is Dream Player: its controller asks for
/// landscape from the moment the player starts loading, so whatever is on top decides, and anything else is portrait.
final class OrientationDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        var top = window?.rootViewController
        while let next = top?.presentedViewController { top = next }
        if let mask = top?.supportedInterfaceOrientations, !mask.contains(.portrait) { return mask }
        return .portrait
    }
}

/// The app shell (tabs) lives in Shell/; this is the entry point only.
struct RootView: View {
    @Environment(\.scenePhase) private var phase
    /// First run: the setup wizard until it's finished (SetupGate), then the shell.
    @State private var setupDone = SetupGate.isDone
    /// Who's Watching at launch (MainActivity.onIntroDone): shown when there is a choice to make (two or more
    /// profiles) or the current profile is PIN-locked. nil while that's being read (a local read, a few ms).
    @State private var askWho: Bool?
    /// The setup's send-off plays on every cold launch once setup is done (owner, 2026-10-28); Home loads behind it.
    @State private var intro = SetupGate.isDone

    var body: some View {
        ZStack {
            content
                .onChange(of: phase) { _, p in if p == .background { ImageStore.shared.trimMemory() } }
            if intro {
                ZStack {
                    Palette.page.ignoresSafeArea()
                    Celebration(name: "")
                }
                // The installed version and build channel, small and still in the bottom-right corner, inside the safe
                // area (read from the bundle: "Version 4.1 (DEV)", or "(PUBLIC)" in a release.sh build).
                .overlay(alignment: .bottomTrailing) {
                    VersionTag(size: 12.5)
                        .padding(.trailing, 20)
                        .padding(.bottom, 14)
                }
                .transition(.opacity)
                .zIndex(1)
                .task {
                    try? await Task.sleep(for: .seconds(2.0))  // owner: 2 seconds
                    withAnimation(.easeInOut(duration: 0.45)) { intro = false }
                }
            }
        }
    }

    @ViewBuilder private var content: some View {
        if !setupDone {
            SetupWizardView(firstRun: true) { askWho = false; setupDone = true }
        } else if askWho == false {
            AppShell()
        } else if askWho == true {
            ProfilePickerView(trustedId: nil) { _ in askWho = false }
                .background(DreamBackground())
        } else {
            DreamBackground().task {
                let ps = Services.shared.profiles
                let all = await ps.profiles()
                // Owner (2026-10-28): every launch asks Who's Watching when there are two or more profiles; a single
                // profile signs in by itself unless it's PIN-locked, then Who's Watching asks for the PIN (or lets
                // the viewer add another profile).
                let me = await ps.current()
                askWho = all.count > 1 || me?.hasPin == true
            }
        }
    }
}
