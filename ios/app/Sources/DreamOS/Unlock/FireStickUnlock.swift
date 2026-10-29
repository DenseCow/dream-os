import Foundation
import Observation
@preconcurrency import Network
import DreamCore

// Unlock Fire Stick: the one-time grants a Fire TV only gives over ADB, done from this iPhone over the Wi-Fi.
// Some Fire OS builds (7.7.1.7 on the Fire TV Stick HD) refuse ADB from the stick itself, so Dream OS on the TV
// can't do it alone (fire-home DreamUnlock.kt sends people here). Same grants as DreamUnlock.unlock() plus what
// fire-home tools/deploy.sh sets: the Home-button accessibility service, the assistant slot, the screensaver.

/// A Fire TV that announced itself on the Wi-Fi (Dream OS's Unlock screen announces `_dreamos-adb._tcp`).
struct FoundStick: Identifiable, Hashable, @unchecked Sendable {
    let name: String
    let endpoint: NWEndpoint
    var id: String { name }
}

/// What went wrong, in the viewer's words.
enum UnlockProblem: Equatable {
    case badAddress, notFound, adbOff, localNetwork, notAllowed, noAnswer, notInstalled, dropped

    var title: String {
        switch self {
        case .badAddress: "Check the Address"
        case .notFound: "Couldn't Reach the Fire Stick"
        case .adbOff: "Turn On ADB Debugging"
        case .localNetwork: "Allow Local Network"
        case .notAllowed: "Not Allowed on the TV"
        case .noAnswer: "No Answer From the TV"
        case .notInstalled: "Install Dream OS First"
        case .dropped: "The Connection Dropped"
        }
    }

    var message: String {
        switch self {
        case .badAddress: "Enter the address the way the Fire Stick shows it, like 192.168.1.20."
        case .notFound: "Make sure the Fire Stick is on and this iPhone is on the same Wi-Fi, then try again."
        case .adbOff: "On the Fire Stick, open Settings › My Fire TV › Developer Options and turn on ADB Debugging, then try again."
        case .localNetwork: "Dream needs Local Network access to reach the Fire Stick. Turn it on in Settings › Privacy & Security › Local Network on this iPhone, then try again."
        case .notAllowed: "Try again. When the TV asks, check “Always allow from this computer”, then choose OK with the remote."
        case .noAnswer: "The TV's question wasn't answered in time. Try again, then choose OK on the TV with the remote."
        case .notInstalled: "Dream OS isn't on this Fire Stick yet. Install it there, then try again."
        case .dropped: "Keep the Fire Stick on and near your Wi-Fi, then try again."
        }
    }
}

/// One thing the unlock turns on (the TV's DreamUnlock.FEATURES, same names).
struct UnlockFeature: Identifiable, Sendable {
    let title: String
    let on: Bool
    var id: String { title }
}

/// The unlock itself: connect, sign in (the TV asks once), run the grants, read back what's on.
enum FireStickUnlock {
    static let package = "com.firehome.launcher"
    static let service = "_dreamos-adb._tcp"
    static let port: UInt16 = 5555

    enum Stage: Sendable { case connecting, allow, unlocking }

    private static var homeService: String { "\(package)/\(package).HomeRedirectService" }
    private static var voice: String { "\(package)/.FireVoiceService" }

    static func run(_ session: AdbSession, stage: @escaping @Sendable (Stage) -> Void) async throws -> [UnlockFeature] {
        let key = try AdbKey.privateKey()
        try await session.connect(key: key, name: "Dream@iPhone") { stage(.allow) }
        stage(.unlocking)
        guard try await session.shell("pm path \(package)").contains("package:") else { throw UnlockProblem.notInstalled }
        let p = package
        let grants = [
            "pm grant \(p) android.permission.WRITE_SECURE_SETTINGS",
            "cmd notification allow_listener \(p)/\(p).AppWatchService",
            "appops set \(p) GET_USAGE_STATS allow",
            "appops set \(p) REQUEST_INSTALL_PACKAGES allow",
            "pm grant \(p) android.permission.ACCESS_FINE_LOCATION",
            "settings put secure voice_interaction_service \(voice)",
            "settings put secure assistant \(voice)",
            "settings put secure screensaver_components \(p)/\(p).DreamSaver",
            "settings put secure screensaver_enabled 1",
        ]
        for g in grants {
            let out = try await session.shell("\(g); echo DREAM_EXIT=$?")
            let what = g.split(separator: " ").prefix(3).joined(separator: " ")
            Log.app.info("Unlock Fire Stick: \(what, privacy: .public) -> \(out.contains("DREAM_EXIT=0") ? "ok" : "failed", privacy: .public)")
        }
        // The Home-button service joins whatever is already on (never replaces another app's service).
        let list = try await session.shell("settings get secure enabled_accessibility_services")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let services = list == "null" ? [] : list.split(separator: ":").map(String.init).filter { !$0.isEmpty }
        if !services.contains(where: { $0.caseInsensitiveCompare(homeService) == .orderedSame }) {
            let joined = (services + [homeService]).joined(separator: ":")
            _ = try await session.shell("settings put secure enabled_accessibility_services '\(joined)'")
        }
        _ = try await session.shell("settings put secure accessibility_enabled 1")
        return try await check(session)
    }

    /// What's on now, read from the TV (the same checks Dream OS makes on the TV).
    static func check(_ s: AdbSession) async throws -> [UnlockFeature] {
        func get(_ key: String) async throws -> String {
            try await s.shell("settings get secure \(key)").trimmingCharacters(in: .whitespacesAndNewlines)
        }
        let listed = try await get("enabled_accessibility_services").lowercased().contains(homeService.lowercased())
        let enabled = try await get("accessibility_enabled") == "1"
        let home = listed && enabled
        let voiceOn = try await get("voice_interaction_service") == voice
        let listener = try await get("enabled_notification_listeners").contains("\(package)/")
        let saver = try await get("screensaver_components").contains("DreamSaver")
        let secure = try await s.shell("dumpsys package \(package) | grep -c 'WRITE_SECURE_SETTINGS: granted=true'")
            .trimmingCharacters(in: .whitespacesAndNewlines) != "0"
        return [
            UnlockFeature(title: "Home Button", on: home),
            UnlockFeature(title: "Starts in Dream OS", on: home),
            UnlockFeature(title: "Voice Search", on: voiceOn),
            UnlockFeature(title: "Your Other Apps", on: listener),
            UnlockFeature(title: "Screensaver", on: saver),
            UnlockFeature(title: "Stays Set Up", on: secure),
        ]
    }

    /// "192.168.1.20" or "192.168.1.20:5555" as an endpoint; nil when it can't be an address.
    static func endpoint(_ typed: String) -> NWEndpoint? {
        let t = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, !t.contains(" ") else { return nil }
        var host = t
        var port = Self.port
        if let colon = t.lastIndex(of: ":"), t.filter({ $0 == ":" }).count == 1 {
            host = String(t[..<colon])
            guard let p = UInt16(t[t.index(after: colon)...]) else { return nil }
            port = p
        }
        guard !host.isEmpty, let nwPort = NWEndpoint.Port(rawValue: port) else { return nil }
        return .hostPort(host: NWEndpoint.Host(host), port: nwPort)
    }

    static func problem(_ error: Error) -> UnlockProblem {
        if let p = error as? UnlockProblem { return p }
        if let f = error as? AdbLink.Failure {
            switch f {
            case .refused: return .adbOff
            case .localNetworkDenied: return .localNetwork
            case .unreachable: return .notFound
            case .closed, .timedOut: return .dropped
            }
        }
        if let f = error as? AdbSession.Failure { return f == .notAllowed ? .notAllowed : .noAnswer }
        return .dropped
    }
}

extension UnlockProblem: Error {}

/// The Unlock Fire Stick page's state: Fire TVs found on the Wi-Fi, the address typed, and the unlock's progress.
@MainActor @Observable
final class FireStickUnlockModel {
    enum Phase: Equatable {
        case pick
        case working(FireStickUnlock.Stage)
        case done
        case failed(UnlockProblem)
    }

    var phase: Phase = .pick
    var found: [FoundStick] = []
    var address = UserDefaults.standard.string(forKey: "unlock.address") ?? ""
    var features: [UnlockFeature] = []
    /// The Fire TV being unlocked, by name or address.
    var target = ""
    var adbTurnedOff = false
    var turningOff = false

    private var browser: NWBrowser?
    private var job: Task<Void, Never>?
    private var lastEndpoint: NWEndpoint?
    /// The attempt's connection, closed on Cancel so the TV's question goes away with it.
    private var inFlight: AdbLink?
    /// Kept after a successful unlock so Turn Off ADB Debugging needs no second Allow.
    private var session: (link: AdbLink, adb: AdbSession)?

    /// Lists Fire TVs showing Dream OS's Unlock screen. The first browse is also when iOS asks for Local Network access.
    func startLooking() {
        guard browser == nil else { return }
        let b = NWBrowser(for: .bonjour(type: FireStickUnlock.service, domain: nil), using: .tcp)
        // Started on the main queue, so its answers arrive on the main actor.
        b.browseResultsChangedHandler = { [weak self] results, _ in
            let list = results.compactMap { r -> FoundStick? in
                if case .service(let name, _, _, _) = r.endpoint { return FoundStick(name: name, endpoint: r.endpoint) }
                return nil
            }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
            MainActor.assumeIsolated { self?.found = list }
        }
        b.start(queue: .main)
        browser = b
    }

    func stopLooking() {
        browser?.cancel()
        browser = nil
    }

    func unlock(_ stick: FoundStick) { begin(stick.endpoint, name: stick.name) }

    func unlockTyped() {
        guard let e = FireStickUnlock.endpoint(address) else { phase = .failed(.badAddress); return }
        UserDefaults.standard.set(address.trimmingCharacters(in: .whitespacesAndNewlines), forKey: "unlock.address")
        begin(e, name: address.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    func tryAgain() {
        if let e = lastEndpoint { begin(e, name: target) } else { phase = .pick }
    }

    func chooseAnother() {
        close()
        phase = .pick
    }

    func cancel() {
        job?.cancel()
        inFlight?.close()
        close()
        phase = .pick
    }

    /// Leaving the page: an attempt still running stops (and the TV's question with it); a kept connection closes.
    func cancelIfWorking() {
        if case .working = phase { cancel() } else { close() }
    }

    private func begin(_ endpoint: NWEndpoint, name: String) {
        job?.cancel()
        inFlight?.close()
        close()
        lastEndpoint = endpoint
        target = name
        features = []
        adbTurnedOff = false
        phase = .working(.connecting)
        Log.app.info("Unlock Fire Stick: connecting")
        let link = AdbLink(endpoint)
        let adb = AdbSession(link)
        inFlight = link
        // Holds the model until the attempt ends; Cancel and leaving the page close the link, which ends it at once.
        job = Task {
            do {
                try await link.open(timeout: 12)
                let result = try await FireStickUnlock.run(adb) { stage in
                    Task { @MainActor in
                        guard case .working = self.phase, !Task.isCancelled else { return }
                        self.phase = .working(stage)
                    }
                }
                guard !Task.isCancelled else { link.close(); return }
                session = (link, adb)
                features = result
                phase = .done
                PageHaptics.success()
                Log.app.info("Unlock Fire Stick: done, \(result.filter(\.on).count)/\(result.count) on")
            } catch {
                link.close()
                guard !Task.isCancelled else { return }
                let p = FireStickUnlock.problem(error)
                Log.app.info("Unlock Fire Stick: \(String(describing: error), privacy: .public) -> \(p.title, privacy: .public)")
                phase = .failed(p)
                PageHaptics.warning()
            }
        }
    }

    /// Turns ADB Debugging off on the TV (everything stays unlocked). Its own connection ends with it.
    func turnOffAdb() {
        guard !turningOff, !adbTurnedOff, let s = session else { return }
        turningOff = true
        Task {
            // ADB stops as soon as the setting changes, so the shell may never answer: either way it's off.
            _ = try? await s.adb.shell("settings put global adb_enabled 0", timeout: 4)
            s.link.close()
            if session?.link === s.link { session = nil }
            turningOff = false
            adbTurnedOff = true
            PageHaptics.success()
        }
    }

    func close() {
        session?.link.close()
        session = nil
    }
}
