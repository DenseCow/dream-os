import SwiftUI
import UIKit
import UserNotifications
import BackgroundTasks
import DreamCore

/// The phone's own notifications for new episodes (iPhone only: the TV has the in-app banner alone). The same rules
/// as Home's New Episode banner (NewEpisodeSpot): a followed show's episode, the viewer caught up with the show, never
/// one they played or dismissed. Two ways one arrives:
/// - An episode with a known air time is scheduled ahead, so the alert comes the minute it airs even with Dream closed.
/// - An episode found by a load (in front, or the background refresh about every hour or two) that no alert covered
///   yet comes at once while Dream is in the background. While Dream is in front, the in-app banner tells it instead.
/// Permission is asked once, at a moment it makes sense: right after the viewer adds a show to My List, or when the
/// shows they follow have episodes on the way. Off with "New Episode Alerts" in Settings > Notifications.
@MainActor
final class EpisodeAlerts: NSObject, UNUserNotificationCenterDelegate {
    static let shared = EpisodeAlerts()
    /// The background refresh (Info.plist BGTaskSchedulerPermittedIdentifiers).
    nonisolated static let taskId = "com.dreamos.DreamOS.newEpisodes"
    private static let prefix = "newep."

    private var installed = false
    private var observers: [NSObjectProtocol] = []
    /// What the last load booked ahead (profile, episodes, air times).
    private var lastBooking: String?

    // ---- the setting ----

    /// "New Episode Alerts" (on by default; nothing is sent until iOS allows it).
    static var enabled: Bool {
        get { DreamStore.shared.get(Bool.self, "episodeAlerts.on") ?? true }
        set {
            DreamStore.shared.set(newValue, "episodeAlerts.on")
            if !newValue { Task { await shared.clearPending() } }
        }
    }

    /// What iOS allows now.
    static func permission() async -> UNAuthorizationStatus {
        await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }

    /// Asks iOS once (the system's own question). True when alerts may be sent.
    @discardableResult
    func ask() async -> Bool {
        let c = UNUserNotificationCenter.current()
        let status = await c.notificationSettings().authorizationStatus
        if status == .authorized || status == .provisional || status == .ephemeral { return true }
        guard status == .notDetermined else { return false }
        DreamStore.shared.set(true, "episodeAlerts.asked")
        let ok = (try? await c.requestAuthorization(options: [.alert, .sound])) ?? false
        Log.app.notice("Episode alerts: permission \(ok ? "given" : "declined", privacy: .public)")
        return ok
    }

    private func maybeAsk() {
        guard Self.enabled, DreamStore.shared.get(Bool.self, "episodeAlerts.asked") != true,
              UIApplication.shared.applicationState == .active else { return }
        Task { await ask() }
    }

    // ---- launch ----

    /// Registers the background refresh. Call from the app's init (before launch finishes, as iOS requires).
    static func registerBackground() {
        // Set before launch finishes, so a tap that opens Dream still reaches its show.
        UNUserNotificationCenter.current().delegate = shared
        registerTask()
    }

    /// The launch handler is built outside the main actor: iOS calls it on a background queue.
    nonisolated private static func registerTask() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
            nonisolated(unsafe) let t = task
            let work = Task { @MainActor in
                Self.scheduleRefresh()
                await NewEpisodeSpot.shared.refresh()
                t.setTaskCompleted(success: true)
            }
            t.expirationHandler = { work.cancel(); t.setTaskCompleted(success: false) }
        }
    }

    /// Once the shell is up: tapping an alert opens its show; My List changes may ask for permission; leaving the
    /// front books the next background look.
    func install() {
        guard !installed else { return }
        installed = true
        UNUserNotificationCenter.current().delegate = self
        let nc = NotificationCenter.default
        observers.append(nc.addObserver(forName: .dreamMyListChanged, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated {
                _ = Task {
                    let followsShow = await Services.shared.myList.items().contains { $0.kind == .tv }
                    if followsShow { EpisodeAlerts.shared.maybeAsk() }
                }
            }
        })
        observers.append(nc.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { EpisodeAlerts.scheduleRefresh() }
        })
    }

    static func scheduleRefresh() {
        guard enabled else { return }
        let r = BGAppRefreshTaskRequest(identifier: taskId)
        r.earliestBeginDate = Date(timeIntervalSinceNow: 60 * 60)
        do { try BGTaskScheduler.shared.submit(r) } catch { Log.app.notice("Episode alerts: no background look (\(error.localizedDescription, privacy: .public))") }
    }

    // ---- what a load found (NewEpisodeSpot.onSnapshot) ----

    /// [s]: the load; [news]: episodes found since the last load, already filtered like the banner's.
    func update(_ s: NewEpisodesSnapshot, news: [NewEpisodeInfo]) {
        guard Self.enabled else { return }
        let inFront = UIApplication.shared.applicationState == .active
        if inFront, !s.recent.isEmpty || !s.upcoming.isEmpty { maybeAsk() }
        let profile = DreamStore.shared.profileId
        Task {
            guard await Self.permission() == .authorized else { return }
            let history = await Services.shared.history.recent()
            let done = Set(DreamStore.shared.get([String].self, "newep_banner.done", perProfile: true) ?? [])
            var sent = DreamStore.shared.get([String].self, "episodeAlerts.sent", perProfile: true) ?? []
            var booked = DreamStore.shared.get([String].self, "episodeAlerts.booked", perProfile: true) ?? []
            let c = UNUserNotificationCenter.current()
            // Ahead: every caught-up episode with an exact time still to come (a month at most, 40 at most).
            let ahead = Array(s.upcoming.filter { i in
                guard let at = i.at, at > Date(), at.timeIntervalSinceNow < 31 * 86_400 else { return false }
                return !done.contains(i.key) && NewEpisodeSpot.caughtUp(history, i)
            }.prefix(40))
            let want = Set(ahead.map { Self.id(profile, $0) })
            // Booked before but no longer wanted (watched, moved, unfollowed): taken back.
            let pending = await c.pendingNotificationRequests().map(\.identifier).filter { $0.hasPrefix(Self.prefix + profile + ".") }
            let gone = pending.filter { !want.contains($0) }
            c.removePendingNotificationRequests(withIdentifiers: gone)
            booked.removeAll { k in gone.contains(Self.prefix + profile + "." + k) }
            // Booked again when something changed (the same id replaces the old one: a moved air time moves the alert).
            // Home loads on every return from a title page; the same set still pending is left alone (was up to 40
            // notification requests each time).
            let signature = profile + "|" + ahead.map { "\($0.key)@\(Int($0.at?.timeIntervalSince1970 ?? 0))" }.joined(separator: ",")
            if signature != lastBooking || !want.isSubset(of: Set(pending)) {
                for i in ahead {
                    let wait = max(5, (i.at ?? Date()).timeIntervalSinceNow)
                    try? await c.add(UNNotificationRequest(identifier: Self.id(profile, i), content: Self.content(i),
                                                           trigger: UNTimeIntervalNotificationTrigger(timeInterval: wait, repeats: false)))
                    if !booked.contains(i.key) { booked.append(i.key) }
                }
                lastBooking = signature
            }
            // Now: what a load found that no alert covered. In front, the in-app banner tells it instead.
            for i in news where !sent.contains(i.key) && !booked.contains(i.key) && !done.contains(i.key) {
                sent.append(i.key)
                if inFront { continue }
                try? await c.add(UNNotificationRequest(identifier: Self.id(profile, i), content: Self.content(i), trigger: nil))
                Log.app.notice("Episode alerts: sent \(i.show.name, privacy: .public) S\(i.season)E\(i.number)")
            }
            DreamStore.shared.set(Array(sent.suffix(300)), "episodeAlerts.sent", perProfile: true)
            DreamStore.shared.set(Array(booked.suffix(300)), "episodeAlerts.booked", perProfile: true)
            if !ahead.isEmpty { Log.app.notice("Episode alerts: \(ahead.count) booked ahead") }
        }
    }

    private func clearPending() async {
        let c = UNUserNotificationCenter.current()
        let ids = await c.pendingNotificationRequests().map(\.identifier).filter { $0.hasPrefix(Self.prefix) }
        c.removePendingNotificationRequests(withIdentifiers: ids)
    }

    private static func id(_ profile: String, _ i: NewEpisodeInfo) -> String { prefix + profile + "." + i.key }

    /// "New Episode" / "Severance  ·  S2 · E7  Episode Name" (the in-app notice's words).
    private static func content(_ i: NewEpisodeInfo) -> UNMutableNotificationContent {
        let c = UNMutableNotificationContent()
        c.title = i.lastNumber > i.number ? "New Episodes" : "New Episode"
        c.body = [i.show.name, NewEpisodeSpot.code(i), i.lastNumber > i.number ? nil : (i.name.isEmpty ? nil : i.name)]
            .compactMap { $0 }.joined(separator: "  ·  ")
        c.sound = .default
        c.threadIdentifier = "newep.\(i.show.id)"
        c.userInfo = ["target": NoticeActions.titleTarget(i.show)]
        return c
    }

    // ---- UNUserNotificationCenterDelegate ----

    /// In front, the in-app banner says it: no system banner on top.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async
        -> UNNotificationPresentationOptions { [.list] }

    /// Tapped: its show's page opens (the notice's own action).
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard let target = response.notification.request.content.userInfo["target"] as? String else { return }
        let title = response.notification.request.content.title
        let n = await MainActor.run {
            Notice(key: "alert:" + target, type: .episodes, title: title, body: "", severity: .info,
                           at: Int64(Date().timeIntervalSince1970 * 1000), read: true, action: .init(label: "Go to Show", target: target))
        }
        // A tap that launched Dream: the shell wires the notices a moment later.
        for _ in 0..<20 {
            if await MainActor.run(body: { NoticeBoard.shared.isFree() }) { break }
            try? await Task.sleep(for: .milliseconds(250))
        }
        await MainActor.run { NoticeBoard.shared.runAction(n) }
    }
}
