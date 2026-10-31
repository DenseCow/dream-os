import Foundation
import AVFoundation
import DreamCore
import DreamPlayer
import DreamPlayback

/// Title page trailers readied before the page asks (the Windows app's TrailerAhead), so the hero's trailer fades in
/// the moment the page's 1 s rest ends: the trailer's streams looked up, the start of its picture (and sound, when it
/// plays with sound) opened ahead in AVFoundation, and for the card just pressed a hidden, muted player already
/// buffered to its first picture, which the hero's layer takes over.
///
/// Asked when a card is pressed (touch-down) and when a hero slide has been on screen 1.5 s. The page's own hero asks
/// through `player(for:_:)` once its details bring the key, and joins a look-up already under way.
///
/// Bounds: at most 2 look-ups at a time; a card pressed while they run replaces the one waiting (the newest is the one
/// likely to open). A look-up gives up after 14 s (remembered as a miss for 2 min, then asked again). 32 streams kept
/// (2 h, the links' life), 8 titles' opened streams (a hero has up to 6 slides), one buffered player (the last card
/// pressed, dropped after 60 s unused). Nothing ahead when Background Trailers is Off, in Low Power Mode, on a metered
/// network unless the viewer chose full use of mobile data (`CellularSaver.lookAheadAllowed`), or while Dream Player
/// is up or floating (Play opens it at once): Play always wins. While a trailer is being readied, below-the-fold art
/// and the stream head start wait (`TrailerWindow`, 2 s at most).
@MainActor
enum TrailerAhead {
    static let maxRunning = 2
    static let maxStreams = 32
    static let maxOpened = 8
    static let streamLife: TimeInterval = 2 * 3600
    static let missLife: TimeInterval = 120
    nonisolated static let lookupCap: Double = 14
    static let primedLife: Double = 60

    private struct Found { let stream: TrailerStream?; let at: Date }
    private struct Opened { let key: String; let video: AVURLAsset; let audio: AVURLAsset? }
    private final class Primed {
        let key: String
        let player: AVPlayer
        let window: Int
        var watch: NSKeyValueObservation?
        init(key: String, player: AVPlayer, window: Int) { self.key = key; self.player = player; self.window = window }
    }
    private struct Ask { let title: Title; let prime: Bool }

    private static var running = 0
    private static var waiting: Ask?
    private static var found: [String: Found] = [:]
    private static var lookups: [String: Task<TrailerStream?, Never>] = [:]
    private static var opened: [Opened] = []
    private static var primed: Primed?
    /// The player the hero is using now (its key): no second one is buffered for it.
    private static var heroKey: String?
    private static weak var heroPlayer: AVPlayer?
    /// Title key -> trailer key (nil: TMDB lists none), from the details the look-ahead read.
    private static var keys: [String: String?] = [:]

    // ---- asks ----

    /// A card pressed: readied in the cards' small queue, its player buffered to the first picture.
    static func card(_ t: Title) {
        guard wanted(t, prime: true) else { return }
        waiting = Ask(title: t, prime: true)
        pump()
    }

    /// A hero slide on screen 1.5 s: same queue, streams only (slides change every few seconds).
    static func heroSlide(_ t: Title) {
        guard wanted(t, prime: false) else { return }
        if waiting?.prime == true { return }
        waiting = Ask(title: t, prime: false)
        pump()
    }

    /// A few seconds after launch: what every look-up needs (a visitor id, a few KB), off the main thread, low
    /// priority. Skipped when the trailer look-ahead itself would be.
    static func warmSoon() {
        Task(priority: .background) {
            try? await Task.sleep(for: .seconds(4))
            guard BackgroundTrailers.mode != .off, !ProcessInfo.processInfo.isLowPowerModeEnabled, !playerUp else { return }
            await Task.detached(priority: .background) {
                guard CellularSaver.lookAheadAllowed else { return }
                await Trailers.warm()
            }.value
        }
    }

    /// The streams for trailer `key`: remembered, else the look-up under way, else a new one. Nil when it has none.
    static func stream(forKey key: String) async -> TrailerStream? {
        if let f = found[key] {
            let life = f.stream == nil ? missLife : streamLife
            if Date().timeIntervalSince(f.at) < life { return f.stream }
            found[key] = nil
        }
        if let l = lookups[key] { return await l.value }
        let l = Task<TrailerStream?, Never>(priority: .userInitiated) { await capped(key) }
        lookups[key] = l
        let s = await l.value
        if lookups[key] == l {
            lookups[key] = nil
            remember(key, s)
        }
        return s
    }

    /// For the hero's timing log: "yes" (streams already found), "joined" (a look-up under way), "no".
    static func readiness(_ key: String) -> String {
        if found[key]?.stream != nil { return "yes" }
        return lookups[key] != nil ? "joined" : "no"
    }

    /// The hero's player for these streams: the one buffered ahead for this trailer when there is one (`primed`),
    /// else a new one on the asset opened ahead (its header already read), else on a new asset.
    static func player(for key: String, _ s: TrailerStream) -> (AVPlayer, primed: Bool) {
        if let p = primed, p.key == key, p.player.currentItem?.status != .failed,
           (p.player.currentItem?.asset as? AVURLAsset)?.url == s.video {
            primed = nil
            p.watch = nil
            TrailerWindow.close(p.window) // the hero holds its own window until its picture is up
            heroKey = key
            heroPlayer = p.player
            return (p.player, true)
        }
        let item = AVPlayerItem(asset: asset(for: s.video))
        let p = AVPlayer(playerItem: item)
        heroKey = key
        heroPlayer = p
        return (p, false)
    }

    /// A stream that failed to play: forgotten, so the next try looks it up again.
    static func forget(_ key: String) {
        found[key] = nil
        opened.removeAll { $0.key == key }
        if primed?.key == key { release() }
    }

    // ---- the queue ----

    private static var playerUp: Bool {
        DreamPlayerPresenter.shared.isShowing || DreamPlayerPresenter.shared.isFloating
    }

    private static func wanted(_ t: Title, prime: Bool) -> Bool {
        guard BackgroundTrailers.mode != .off, !ProcessInfo.processInfo.isLowPowerModeEnabled, !playerUp else { return false }
        guard let known = keys[t.key] else { return true }
        // TMDB lists no trailer, or everything is readied already (for a card: its player buffered or playing).
        guard let key = known else { return false }
        let ready = found[key]?.stream != nil && opened.contains { $0.key == key }
        return !(ready && (!prime || primed?.key == key || (heroKey == key && heroPlayer != nil)))
    }

    private static func pump() {
        guard running < maxRunning, let ask = waiting else { return }
        waiting = nil
        running += 1
        Task(priority: .utility) {
            await readyAhead(ask)
            running -= 1
            pump()
        }
    }

    private static func readyAhead(_ ask: Ask) async {
        let t = ask.title
        let t0 = Date()
        let allowed = await Task.detached(priority: .utility) { CellularSaver.lookAheadAllowed }.value
        guard allowed, !playerUp else { return }
        guard let d = try? await Services.shared.catalog.details(kind: t.kind, id: t.id) else { return }
        remember(title: t.key, key: d.trailerKey)
        guard let key = d.trailerKey, !playerUp else { return }
        let had = found[key]?.stream != nil
        // The look-up and the first picture go ahead of other look-ahead traffic (closed when buffered, 3 s at most).
        let window = TrailerWindow.begin()
        guard let s = await stream(forKey: key), !playerUp else { TrailerWindow.close(window); return }
        open(key, s)
        if !had {
            Log.app.notice("Trailer ahead: \(t.name, privacy: .public) ready in \(String(format: "%.1f", Date().timeIntervalSince(t0)), privacy: .public) s")
        }
        if ask.prime { prime(key, s, name: t.name, t0: t0, window: window) } else { TrailerWindow.close(window) }
    }

    /// The look-up, given up after `lookupCap` (raced with unstructured tasks: a group would wait for the slow one).
    nonisolated private static func capped(_ key: String) async -> TrailerStream? {
        let once = Once()
        return await withCheckedContinuation { (cont: CheckedContinuation<TrailerStream?, Never>) in
            let work = Task(priority: .userInitiated) {
                let s = try? await Trailers.resolver.stream(forKey: key)
                once.run { cont.resume(returning: s) }
            }
            Task {
                try? await Task.sleep(for: .seconds(lookupCap))
                once.run {
                    work.cancel()
                    Log.app.notice("Trailer ahead: look-up gave up after \(Int(lookupCap), privacy: .public) s; asked again in 2 min")
                    cont.resume(returning: nil)
                }
            }
        }
    }

    // ---- the buffered player ----

    /// A hidden, muted player for the card just pressed, buffered to its first picture (no layer yet: nothing is
    /// shown or heard). The hero takes it over when the page opens; unused, it goes after `primedLife`.
    private static func prime(_ key: String, _ s: TrailerStream, name: String, t0: Date, window: Int) {
        if primed?.key == key || (heroKey == key && heroPlayer != nil) || playerUp { TrailerWindow.close(window); return }
        release()
        let item = AVPlayerItem(asset: asset(for: s.video))
        item.preferredForwardBufferDuration = 4
        let p = AVPlayer(playerItem: item)
        p.isMuted = true
        p.volume = 0
        p.allowsExternalPlayback = false
        // Starts from what's there instead of waiting for a long buffer (the hero turns this back on once it shows).
        p.automaticallyWaitsToMinimizeStalling = false
        let pr = Primed(key: key, player: p, window: window)
        pr.watch = item.observe(\.status) { it, _ in
            let status = it.status
            Task { @MainActor in
                // Taken by the hero meanwhile (or replaced): the hero plays it from here.
                guard let pr = primed, pr.window == window else { TrailerWindow.close(window); return }
                let p = pr.player
                switch status {
                case .readyToPlay:
                    guard p.status == .readyToPlay, p.rate == 0 else { TrailerWindow.close(window); return }
                    p.preroll(atRate: 1) { done in
                        Task { @MainActor in
                            TrailerWindow.close(window)
                            if done {
                                Log.app.notice("Trailer ahead: \(name, privacy: .public) first picture buffered in \(String(format: "%.1f", Date().timeIntervalSince(t0)), privacy: .public) s")
                            }
                        }
                    }
                case .failed:
                    release()
                default: break
                }
            }
        }
        primed = pr
        Task {
            try? await Task.sleep(for: .seconds(primedLife))
            if primed === pr { release() }
        }
    }

    private static func release() {
        guard let p = primed else { return }
        primed = nil
        p.watch = nil
        TrailerWindow.close(p.window)
        p.player.pause()
        p.player.replaceCurrentItem(with: nil)
    }

    // ---- memory ----

    private static func remember(title: String, key: String?) {
        if keys.count > 400 { keys.removeAll() }
        keys[title] = .some(key)
    }

    private static func remember(_ key: String, _ s: TrailerStream?) {
        found[key] = Found(stream: s, at: Date())
        guard found.count > maxStreams else { return }
        let oldest = found.min { $0.value.at < $1.value.at }?.key
        if let oldest { found[oldest] = nil; opened.removeAll { $0.key == oldest } }
    }

    /// The asset opened ahead for this URL (its header already read), else a new one.
    private static func asset(for url: URL) -> AVURLAsset {
        for o in opened {
            if o.video.url == url { return o.video }
            if let a = o.audio, a.url == url { return a }
        }
        return AVURLAsset(url: url)
    }

    /// Opens the start of the picture (and the sound when the trailer plays with sound): AVFoundation reads the
    /// stream's header now, on its own connection, and the hero's player item reuses the asset.
    private static func open(_ key: String, _ s: TrailerStream) {
        if opened.contains(where: { $0.key == key }) { return }
        let video = AVURLAsset(url: s.video)
        let audio = BackgroundTrailers.mode == .sound ? s.audio.map { AVURLAsset(url: $0) } : nil
        opened.append(Opened(key: key, video: video, audio: audio))
        if opened.count > maxOpened { opened.removeFirst(opened.count - maxOpened) }
        Task(priority: .utility) {
            _ = try? await video.load(.isPlayable, .duration)
            if let audio { _ = try? await audio.load(.isPlayable) }
        }
    }
}
