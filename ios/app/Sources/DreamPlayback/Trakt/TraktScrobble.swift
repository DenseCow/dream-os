import Foundation
import DreamCore

/// Dream Player -> Trakt scrobble (fire-home Trakt.onPlayer / onPlayerClosed). Fed by `PlayerReports` (DreamCore):
/// start when a title settles into playing, pause when it settles paused, stop when it changes or the player closes.
/// Everything goes out on Trakt's own line, never in playback's way.
@MainActor
final class TraktScrobbler {
    static let shared = TraktScrobbler()

    /// Play / pause must hold this long before Trakt hears of it: rapid toggles (a headset sending play/pause every few
    /// seconds) send at most one state, the one it settles on.
    private static let settleMs: Int64 = 3_000

    private final class Play {
        let key: String
        let item: TraktItem
        let label: String
        /// The profile it plays for (the one signed in when it started).
        let pid: String
        var sent: String?
        /// 0...100
        var progress = 0.0
        /// The state the player settled on (nil: not told yet).
        var playing: Bool?
        var flush: Task<Void, Never>?
        init(key: String, item: TraktItem, label: String, pid: String) { self.key = key; self.item = item; self.label = label; self.pid = pid }
    }

    private var play: Play?
    /// A 422 on a pause was logged for this title (harmless: logged once, never retried).
    nonisolated(unsafe) private static var quiet422: Set<String> = []
    nonisolated private static let quietLock = NSLock()

    /// The player's state, on every play / pause change and with its progress. Returns at once. Unknown titles and
    /// anything under 5 minutes are skipped.
    func report(_ r: PlayerReport) {
        let req = r.request
        guard req.title.id > 0, r.durationMs >= 5 * 60_000 else { return }
        let ep = req.title.kind == .tv
        if ep && (req.season == nil || req.episode == nil) { return }
        let trakt = Trakt.shared
        guard trakt.signedIn else { return }
        let key = ep ? "tv:\(req.title.id):\(req.season!):\(req.episode!)" : "movie:\(req.title.id)"
        var p: Play
        if let cur = play, cur.key == key { p = cur } else {
            if let old = play { close(old) }
            let item = TraktItem(movie: !ep, tmdb: req.title.id, season: req.season ?? 0, number: req.episode ?? 0)
            p = Play(key: key, item: item, label: ep ? "\(req.title.name) S\(req.season!)E\(req.episode!)" : req.title.name, pid: trakt.pid)
            play = p
        }
        if r.positionMs > 0 { p.progress = min(max(Double(r.positionMs) * 100 / Double(r.durationMs), 0), 100) }
        if p.playing != r.playing {
            p.playing = r.playing
            p.flush?.cancel()
            p.flush = Task { @MainActor [weak self] in
                await sleepMs(Self.settleMs)
                guard !Task.isCancelled, let self, self.play === p else { return }
                p.flush = nil
                if p.playing == true && p.sent != "start" { self.send(p, "start") }
                else if p.playing == false && p.sent == "start" { self.send(p, "pause") }
            }
        }
    }

    /// Dream Player closed: the current title's stop, with its last progress.
    func closed() {
        guard let p = play else { return }
        play = nil
        close(p)
    }

    private func close(_ p: Play) {
        p.flush?.cancel() // a state still settling: the stop says it all
        p.flush = nil
        if p.sent == nil || p.sent == "stop" { return }
        send(p, "stop")
    }

    private func send(_ p: Play, _ action: String) {
        p.sent = action
        let progress = (p.progress * 100).rounded() / 100
        let item = p.item, label = p.label, pid = p.pid, key = p.key
        plog.info("Trakt: scrobble \(action, privacy: .public) \(label, privacy: .public) \(Int(progress))%")
        Trakt.shared.io.run { await Self.scrobbleNow(pid, action, item, label, progress, key) }
    }

    private nonisolated static func scrobbleNow(_ pid: String, _ action: String, _ item: TraktItem, _ label: String, _ progress: Double, _ key: String) async {
        let trakt = Trakt.shared
        var body = item.json
        body["progress"] = progress
        let r = try? await trakt.authed(pid, "POST", "/scrobble/\(action)", TraktHTTP.body(body))
        guard let r, !r.transient else {
            // Offline: a start means nothing later; a pause or stop is kept (finished = a history entry at its real time).
            if action == "start" || trakt.vault.tokens(pid) == nil { return }
            if progress >= Trakt.watchedPct && action == "stop" { trakt.queue(pid, TraktOp(op: "history", at: wallMs(), item: item)) }
            else { trakt.queue(pid, TraktOp(op: "progress", item: item, progress: progress)) }
            plog.notice("Trakt: couldn't reach Trakt, queued \(action, privacy: .public) \(label, privacy: .public)")
            return
        }
        if r.ok || r.code == 409 { // 409: already scrobbled a moment ago
            if action == "stop" {
                if progress >= Trakt.watchedPct { plog.info("Trakt: \(label, privacy: .public) marked watched") }
                trakt.markDirty(pid)
                Task.detached(priority: .utility) {
                    await sleepMs(20_000)
                    trakt.syncer.run { await Trakt.shared.syncNow(pid, "after playback") }
                }
            }
        } else if r.code == 422 && action == "pause" {
            // Trakt has nothing playing to pause (it ended or was paused already). Harmless: logged once, not retried.
            let first = quietLock.withLock { quiet422.insert(key).inserted }
            if first { plog.info("Trakt: scrobble pause \(label, privacy: .public) not needed (422), ignored") }
        } else {
            plog.notice("Trakt: scrobble \(action, privacy: .public) \(label, privacy: .public) not accepted (\(r.code, privacy: .public))")
        }
    }
}
