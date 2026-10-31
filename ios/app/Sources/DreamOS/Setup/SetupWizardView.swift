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
    static var hasTMDBKey: Bool { !Keys.tmdb.trimmingCharacters(in: .whitespaces).isEmpty }

    /// What still keeps the debrid step from being done, in plain words; nil when it's done.
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
        case .blocked: return "Approve this network in the email from \(n), then choose \(n) to check again."
        default:
            if first.expired { return "Your \(n) plan has expired. Renew it, or connect another service, to continue." }
            return "Couldn't reach \(n). Check your internet connection, then choose \(n) to check again."
        }
    }

    static func scrapersBlocker() async -> String? {
        await PagesServices.scrapers.status().ready ? nil : "Add a scraper repository and turn on at least one provider to continue."
    }
}

/// Setup's steps. Raw values are what `SetupGate` saves, so an interrupted setup resumes after an update.
enum SetupStep: String, CaseIterable {
    case welcome, intro, zone, content, accounts, scrapers, check, unlock, alerts, saver, profile, who, playback, extras, done

    var chapter: SetupChapter? {
        switch self {
        case .intro, .zone: .getReady
        case .content, .accounts, .scrapers, .check: .connect
        case .unlock, .alerts, .saver: .device
        case .profile, .who, .playback, .extras: .makeItYours
        case .welcome, .done: nil
        }
    }
}

/// Pages pushed on setup's own navigation stack (long lists live on a page of their own; swipe back returns).
enum SetupPage: Hashable {
    case zones, quality, audio, sizes, allSourceSettings, manageScrapers, unlockFireStick
}

/// Where setup is and what it holds between steps. Steps decide when they're done and call `forward()`.
@MainActor @Observable
final class SetupFlow {
    static let order: [SetupStep] = [.welcome, .intro, .zone, .content, .accounts, .scrapers, .check,
                                     .unlock, .alerts, .saver, .profile, .who, .playback, .extras, .done]

    let firstRun: Bool
    let finish: () -> Void
    var step: SetupStep = .welcome
    /// How the step on screen was reached. Finished tasks only move on by themselves after a forward move.
    var cameForward = true
    /// Changes on every step change, so a delayed auto-advance knows the viewer is still on the same visit.
    var visit = 0
    var blocker: String?
    var shake = 0
    var path: [SetupPage] = []
    var celebrating = false

    var name = ""
    var emoji = ProfileLook.emojis[0]
    var color = ProfileLook.colors[0]
    var me: Profile?

    init(firstRun: Bool, finish: @escaping () -> Void) {
        self.firstRun = firstRun
        self.finish = finish
    }

    var index: Int { Self.order.firstIndex(of: step) ?? 0 }

    /// "Step 2 of 4" inside the current chapter.
    var counter: String {
        guard let c = step.chapter else { return "" }
        let inChapter = Self.order.filter { $0.chapter == c }
        return "Step \((inChapter.firstIndex(of: step) ?? 0) + 1) of \(inChapter.count)"
    }

    var chapterFraction: Double {
        guard let c = step.chapter else { return 0 }
        let inChapter = Self.order.filter { $0.chapter == c }
        return Double((inChapter.firstIndex(of: step) ?? 0) + 1) / Double(inChapter.count)
    }

    func forward() {
        guard index + 1 < Self.order.count else { return }
        SetupGate.savedStep = step.rawValue
        move(to: Self.order[index + 1], forward: true)
        PageHaptics.tap()
    }

    func back() {
        guard index > 0 else { return }
        move(to: Self.order[index - 1], forward: false)
    }

    /// Moves on after `delay` (the result shows first), only if the viewer is still on this visit of the step.
    func forward(after delay: Double) {
        let v = visit
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(delay))
            guard visit == v, path.isEmpty, !celebrating else { return }
            forward()
        }
    }

    private func move(to s: SetupStep, forward: Bool) {
        blocker = nil
        cameForward = forward
        visit += 1
        withAnimation(.easeInOut(duration: 0.32)) { step = s }
    }

    /// The one line that says what's missing, with a short shake (never a disabled button).
    func block(_ why: String) {
        PageHaptics.warning()
        withAnimation(.easeOut(duration: 0.2)) { blocker = why }
        withAnimation(.linear(duration: 0.4)) { shake += 1 }
    }

    func complete() async {
        SetupGate.isDone = true
        SetupGate.savedStep = nil
        SetupGate.currentStep = nil
        PageHaptics.success()
        withAnimation(.easeOut(duration: 0.4)) { celebrating = true }
        try? await Task.sleep(for: .seconds(2.6))
        finish()
    }

    func load() async {
        me = await Services.shared.profiles.current()
        if let me {
            name = (firstRun && me.name == "Me") ? "" : me.name
            if !me.avatar.isEmpty { emoji = me.avatar }
            if let c = me.color { color = c }
        }
        guard firstRun, !SetupGate.restored else { return }
        SetupGate.restored = true
        if let c = SetupGate.currentStep, let cur = SetupStep(rawValue: c), cur != .welcome {
            step = cur // reopens exactly where the viewer was
        } else if let s = SetupGate.savedStep, let saved = SetupStep(rawValue: s),
                  let i = Self.order.firstIndex(of: saved), i + 1 < Self.order.count {
            step = Self.order[i + 1] // an interrupted setup picks up after the last finished step
        }
    }
}

/// First-run setup (setup-redesign.md, adapted to touch). Steps are in `SetupFlow.order`; chapters in `SetupStep`.
struct SetupWizardView: View {
    @State private var flow: SetupFlow
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(firstRun: Bool, finish: @escaping () -> Void) {
        _flow = State(initialValue: SetupFlow(firstRun: firstRun, finish: finish))
    }

    var body: some View {
        @Bindable var flow = flow
        NavigationStack(path: $flow.path) {
            root
                .toolbar(.hidden, for: .navigationBar)
                .navigationDestination(for: SetupPage.self) { page(for: $0) }
        }
        .dreamNavigationLook()
        .environment(flow)
        .dynamicTypeSize(...DynamicTypeSize.xxLarge)
        .preferredColorScheme(.dark)
        .onChange(of: flow.step) { _, s in if flow.firstRun { SetupGate.currentStep = s == .done ? nil : s.rawValue } }
        .task { await flow.load() }
    }

    private var root: some View {
        ZStack {
            DreamBackground()
            if flow.celebrating {
                Celebration(name: flow.name).transition(.opacity)
            } else {
                VStack(spacing: 0) {
                    topBar
                    ZStack {
                        stepView
                            .id(flow.step)
                            .transition(reduceMotion ? .opacity
                                        : .asymmetric(insertion: .move(edge: flow.cameForward ? .trailing : .leading).combined(with: .opacity),
                                                      removal: .opacity))
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    // Swiping in from the left edge goes back one step, like a pushed page.
                    .overlay(alignment: .leading) {
                        if flow.index > 0 {
                            Color.clear
                                .frame(width: 16)
                                .contentShape(Rectangle())
                                .gesture(DragGesture(minimumDistance: 12).onEnded { g in
                                    if g.translation.width > 70, abs(g.translation.height) < 80 { flow.back() }
                                })
                                .accessibilityHidden(true)
                        }
                    }
                }
            }
        }
    }

    private var topBar: some View {
        HStack(spacing: 8) {
            Button {
                flow.back()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .foregroundStyle(Palette.text)
            .opacity(flow.index > 0 ? 1 : 0)
            .disabled(flow.index == 0)
            .accessibilityLabel("Back")
            if let c = flow.step.chapter {
                ChapterRail(chapter: c, fraction: flow.chapterFraction, counterText: flow.counter)
                    .padding(.top, 6)
                    .padding(.trailing, flow.firstRun ? 12 : 0)
            } else {
                Spacer()
            }
            // First run has no Close, so the four chapter labels get that room.
            if !flow.firstRun {
                Button("Close") { flow.finish() }
                    .font(.body.weight(.medium))
                    .foregroundStyle(Palette.text)
                    .frame(minWidth: 44, minHeight: 44)
            } else if flow.step.chapter == nil {
                Color.clear.frame(width: 44, height: 44)
            }
        }
        .padding(.horizontal, 8)
    }

    @ViewBuilder private var stepView: some View {
        switch flow.step {
        case .welcome: WelcomeStep()
        case .intro: NeedStep()
        case .zone: ZoneStep()
        case .content: TMDBStep()
        case .accounts: DebridStep()
        case .scrapers: ScrapersStep()
        case .check: CheckStep()
        case .unlock: FireStickStep()
        case .alerts: AlertsStep()
        case .saver: SaverStep()
        case .profile: ProfileStep()
        case .who: WhoStep()
        case .playback: PictureSoundStep()
        case .extras: ExtrasStep()
        case .done: DoneStep()
        }
    }

    @ViewBuilder private func page(for p: SetupPage) -> some View {
        switch p {
        case .zones:
            TimeZonePickPage()
        case .quality:
            PageChoiceList(title: "Maximum Quality",
                           note: "Dream OS plays the best video up to this quality. Choose a lower one if videos pause to load.",
                           options: PlaybackPrefs.qualities.map { .init(id: $0.id, label: $0.label) },
                           current: { PlaybackPrefs.quality ?? "" }) { PlaybackPrefs.setQuality($0) }
        case .audio:
            PageChoiceList(title: "Audio Language", note: nil,
                           options: PlaybackPrefs.audio.map { .init(id: $0.code, label: $0.name) },
                           current: { PlaybackPrefs.audioLang }) { PlaybackPrefs.setAudioLang($0) }
        case .sizes:
            SizeLimitsView()
        case .allSourceSettings:
            SourcesQualityView()
        case .manageScrapers:
            ScrapersView()
        case .unlockFireStick:
            UnlockFireStickView()
        }
    }
}

/// The time zone list on its own page: picking one saves it, comes back and moves on.
private struct TimeZonePickPage: View {
    @Environment(SetupFlow.self) private var flow
    @State private var selected: String? = DreamTime.isAutomatic ? nil : DreamTime.zone.identifier

    var body: some View {
        PageScaffold(title: "Time Zone", subtitle: "Dream OS uses this to show when new episodes and movies come out.") {
            TimeZoneList(selected: $selected) {
                Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(250))
                    flow.path.removeAll()
                    flow.forward(after: 0.45)
                }
            }
        }
    }
}

/// The send-off: "Dream OS" in the middle, the halo, "Welcome to Dream OS", both in the fading rainbow.
struct Celebration: View {
    var name: String
    @State private var on = false
    var body: some View {
        VStack(spacing: 22) {
            ZStack {
                RainbowFade { h in Circle().fill(Color(hex: h)) }.blur(radius: 30).opacity(on ? 0.55 : 0).frame(width: 180, height: 180)
                RainbowFade { h in Text("Dream OS").font(.system(size: 40, weight: .bold)).foregroundStyle(Color(hex: h)) }
                    .scaleEffect(on ? 1 : 0.9).opacity(on ? 1 : 0)
            }
            Text("Welcome to Dream OS").font(.system(size: 18, weight: .medium)).foregroundStyle(Palette.textSecondary).opacity(on ? 1 : 0)
        }
        .onAppear { withAnimation(.easeOut(duration: 1.6)) { on = true } }
    }
}
