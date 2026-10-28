import Foundation
import BackgroundTasks
import Network
import UIKit
import DreamCore
import DreamPlayback

/// Instant Play's background link refresh (2026-10-28): while Dream is in the background on Wi-Fi, iOS wakes it now
/// and then (BGAppRefreshTask) and Continue Watching's newest items are looked up again, so the next episode's link
/// and the first seconds of its stream are ready when the viewer comes back. Booked when the app leaves the front;
/// skipped on cellular or a metered hotspot (nothing is fetched there). Identifier in Info.plist
/// (BGTaskSchedulerPermittedIdentifiers), alongside the new-episode look.
enum InstantPlayRefresh {
    static let taskId = "com.dreamos.DreamOS.instantPlay"
    /// What one wake may spend (iOS gives about 30 s).
    static let budgetMs: Int64 = 22_000

    /// Registers the handler. Call from the app's init (before launch finishes, as iOS requires).
    nonisolated static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
            nonisolated(unsafe) let t = task
            let work = Task {
                schedule()
                if await onWiFi() {
                    await PlayAhead.shared.refreshNow(budgetMs: budgetMs)
                } else {
                    Log.app.info("Instant Play: background refresh skipped (not on Wi-Fi)")
                }
                t.setTaskCompleted(success: true)
            }
            t.expirationHandler = { work.cancel(); t.setTaskCompleted(success: false) }
        }
        NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: nil) { _ in
            schedule()
        }
    }

    /// Books the next wake (about half an hour on; iOS decides the moment).
    nonisolated static func schedule() {
        let r = BGAppRefreshTaskRequest(identifier: taskId)
        r.earliestBeginDate = Date(timeIntervalSinceNow: 30 * 60)
        do { try BGTaskScheduler.shared.submit(r) } catch { Log.app.notice("Instant Play: no background refresh (\(error.localizedDescription, privacy: .public))") }
    }

    /// The current path is Wi-Fi (or wired) and not metered.
    nonisolated static func onWiFi() async -> Bool {
        await withCheckedContinuation { (c: CheckedContinuation<Bool, Never>) in
            let m = NWPathMonitor()
            nonisolated(unsafe) var done = false
            m.pathUpdateHandler = { p in
                guard !done else { return }
                done = true
                c.resume(returning: p.status == .satisfied && !p.isExpensive && !p.isConstrained &&
                         (p.usesInterfaceType(.wifi) || p.usesInterfaceType(.wiredEthernet)))
                m.cancel()
            }
            m.start(queue: DispatchQueue(label: "dreamos.instantplay.path"))
        }
    }
}
