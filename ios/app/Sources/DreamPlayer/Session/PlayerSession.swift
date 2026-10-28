import Foundation
import SwiftUI
import UIKit
import AudioToolbox
import DreamCore
import DreamUI

/// One Dream Player screen's brain (fire-home DreamPlayerActivity + the logic halves of PlayerOverlay, PostPlay,
/// SkipController, PlayFlow's curtain and SleepTimer). The SwiftUI screen only draws what this publishes.
@MainActor
public final class PlayerSession: ObservableObject {
    // MARK: Published state

    public enum Phase: Equatable {
        /// Curtain up: finding the stream / waiting for the first frame.
        case loading
        /// Curtain with the error block.
        case failed(title: String, message: String)
        /// The picture is up.
        case playing
    }

    public enum PostPlay: Equatable {
        case none
        case upNext(countdown: Bool)
        case moreLikeThis
        case stillWatching
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var request: PlayRequest?
    @Published public private(set) var isTrailer = false
    /// Logo, synopsis, cast, recommendations (TMDB via the catalog), loaded once per item.
    @Published public private(set) var details: TitleDetails?
    /// X-Ray's faces for what's playing (`catalog.cast(for:)`: an episode's own cast and guest stars), looked up
    /// ahead like the TV's `PlayerXRay.prefetch`; nil until it answers.
    @Published public private(set) var xrayCast: [CastMember]?
    @Published public private(set) var episodeInfo: Episode?
    @Published public private(set) var next: PlayRequest?
    @Published public private(set) var nextEpisode: Episode?
    @Published public private(set) var markers: [SkipMarker] = []
    @Published public private(set) var skipLabel: String?
    @Published public private(set) var postPlay: PostPlay = .none
    @Published public private(set) var ended = false
    @Published public private(set) var toast: Toast?
    /// The curtain's amber line once the stream is chosen outside the viewer's filters.
    @Published public private(set) var note: String?
    @Published public private(set) var link: StreamLink?
    @Published public private(set) var speed: Double = 1
    @Published public private(set) var fill = false
    /// Sleep timer: when it fires, and the expiry card's countdown (nil = not showing).
    @Published public private(set) var sleepEndsAt: Date?
    @Published public private(set) var sleepCountdown: Int?
    /// Bumped on every engine event so views re-read `engine.state`.
    @Published public private(set) var tick = 0

    public struct Toast: Equatable {
        public var text: String
        public var tint: Color
        public var id = UUID()
    }

    public let engine: PlayerEngine
    public var state: EngineState { engine.state }
    /// Picture in Picture (the player's button, and the system's start on leaving the app).
    public private(set) var pip: PictureInPicture!
    /// Closes the screen (set by the presenter).
    var onClose: (() -> Void)?

    // MARK: Private state

    private var resolveTask: Task<Void, Never>?
    private var metaTask: Task<Void, Never>?
    private var castTask: Task<[CastMember], Never>?
    private var gen = 0
    private var firstFrame = false
    /// Seconds actually played this item (normal playback only: seeks and skips add nothing). A title joins Continue
    /// Watching (and the taste profile) only after `realWatchToSave` of it, unless it's already on the row (owner).
    private var realWatched: Double = 0
    private var lastTickPos: Double = -1
    private var onRow = false
    static let realWatchToSave: Double = 90
    private var loadStartedAt = Date()
    private var lastSaveAt = Date.distantPast
    private var finished = false
    private var finishedAt: Double = 0
    private var userAudio = false, userText = false, audioApplied = false
    private var seenText = Set<String>()
    /// The subtitle choices as read when this stream started (Dream Subtitles + "in the video first").
    private var subPrefs: DreamSubtitles.Prefs?
    private var trailerKeys: [String] = []
    private var trailerStart: Double = 0
    private var trailerAdvances = 0
    private var pendingStart: Double = 0
    /// A stream that ended before its first frame from a saved spot was tried once more from the beginning.
    private var retriedFromStart = false
    // Instant Play's fallback (2026-10-28): links switched to behind the curtain this item, the start watchdog, and
    // whether the current link's start was served from the stream cache (for the log).
    private var fallbacks = 0
    private var startWatch: Task<Void, Never>?
    private var headStart = false
    static let maxFallbacks = 3
    /// No first frame this long after a load, with another link ready: switch to it.
    static let startWatchSeconds: Double = 12
    private var looseNext = false
    // Post-play
    private var creditsAt: Double?
    private var prepared = false
    private var preparing = false
    private var dismissedPostPlay = false
    private var stillAsked = false
    private var itemStartedAt: Date?
    private var advanced = false
    private var idleClose: Task<Void, Never>?
    private var countdownTask: Task<Void, Never>?
    // Skip
    private var doneIntro = false, doneRecap = false, autoSkipped = false
    private var sleepChosen = 0
    private var sleepTask: Task<Void, Never>?

    /// Automatic advances in a row ("Are You Still Watching?" after 3); any touch resets it (PlayFlow.autoplayedInARow).
    static var autoplayedInARow = 0

    static let saveEvery: TimeInterval = 30
    static let minHistory: Double = 10 * 60
    static let finishedFraction = 0.9
    static let resolveTimeout: Double = 45
    static let trailerTimeout: Double = 25

    public init(engine: PlayerEngine = Engines.make()) {
        self.engine = engine
        engine.onEvent = { [weak self] e in self?.handle(e) }
        engine.setSubtitleStyle(PlayerPrefs.style(raised: false))
        engine.setBoost(PlayerPrefs.boostDb[PlayerPrefs.boost])
        BackgroundAudio.start()
        pip = PictureInPicture(session: self)
        // Here, not in the SwiftUI screen: the screen may be off-window (player hidden behind Picture in Picture).
        let nc = NotificationCenter.default
        appObservers.append(nc.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.didEnterBackground() }
        })
        appObservers.append(nc.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.willEnterForeground() }
        })
    }

    private var appObservers: [NSObjectProtocol] = []

    // MARK: Starting

    /// Plays `request` through Dream Core: curtain at once, then the stream (PlayFlow -> Dream Player).
    public func play(_ request: PlayRequest, loose: Bool = false, at start: Double? = nil) {
        endItem()
        isTrailer = false
        self.request = request
        beginItem()
        let g = gen
        let tapped = Date()
        resolveTask = Task { [weak self] in
            guard let self else { return }
            // Instant Play: the spot and the link are asked for together (a link found ahead comes back at once).
            async let spot: Double = {
                if let start { return start }
                if request.fromStart { return 0 }
                return await Services.shared.history.startPosition(for: request)
            }()
            do {
                let link = try await self.withTimeout(Self.resolveTimeout) {
                    if let x = Services.shared.playback as? PlaybackExtras { return try await x.resolve(request, loose: loose) }
                    return try await Services.shared.playback.resolve(request)
                }
                let from = await spot
                guard g == self.gen, !Task.isCancelled else { return }
                self.pendingStart = from
                Log.player.info("Player: link \(String(format: "%.2f", Date().timeIntervalSince(tapped)), privacy: .public) s after Play")
                self.start(link, at: from)
            } catch is CancellationError {
            } catch {
                let from = await spot
                guard g == self.gen else { return }
                self.pendingStart = from
                self.fail(error)
            }
        }
        loadMeta(for: request)
    }

    /// The picked source (Change Source): same spot, curtain until its first frame.
    public func play(link: StreamLink, for request: PlayRequest, at start: Double) {
        endItem()
        self.request = request
        beginItem()
        loadMeta(for: request)
        self.start(link, at: start)
    }

    /// A title's trailer, full screen (TrailerEngine). `keys` in preference order; `start` only applies to the first.
    public func playTrailer(of title: Title, keys: [String], start: Double = 0) {
        endItem()
        isTrailer = true
        trailerKeys = keys
        trailerStart = start
        trailerAdvances = 0
        request = PlayRequest(title: title)
        beginItem()
        resolveTrailer()
    }

    private func beginItem() {
        gen += 1
        phase = .loading
        firstFrame = false
        realWatched = 0
        lastTickPos = -1
        onRow = false
        if let key = request?.title.key {
            Task { [weak self] in
                let e = await Services.shared.history.entry(for: key)
                let on = e != nil && e?.hidden != true
                await MainActor.run { self?.onRow = on }
            }
        }
        finished = false
        ended = false
        note = nil
        link = nil
        details = nil
        xrayCast = nil
        castTask?.cancel(); castTask = nil
        episodeInfo = nil
        next = nil
        nextEpisode = nil
        markers = []
        skipLabel = nil
        postPlay = .none
        creditsAt = nil
        prepared = false; preparing = false; dismissedPostPlay = false; stillAsked = false; advanced = false
        itemStartedAt = nil
        doneIntro = false; doneRecap = false; autoSkipped = false
        userAudio = false; userText = false; audioApplied = false; seenText = []; subPrefs = nil
        lastSaveAt = .distantPast
        loadStartedAt = Date()
        fallbacks = 0
        cancelTimers()
    }

    private func start(_ link: StreamLink, at start: Double) {
        self.link = link
        let extras = Services.shared.playback as? PlaybackExtras
        if !isTrailer, let n = extras?.note(for: link) { note = n }
        let (url, headers) = EngineSource.parse(link.url.absoluteString)
        guard let url else { fail(DreamError("This title isn't available right now.")); return }
        let subs = DreamSubtitles.prefs()
        subPrefs = isTrailer ? nil : subs
        engine.setPreferredLanguages(audio: PlayerPrefs.audioLang ?? PlayerPrefs.deviceLang,
                                     text: isTrailer ? PlayerPrefs.subLang : DreamSubtitles.preferredText(subs))
        engine.setSpeed(speed)
        loadStartedAt = Date()
        headStart = !isTrailer && StreamCache.shared.ready(url)
        engine.load(EngineSource(url: url, headers: headers), start: start, autoplay: true)
        Log.player.info("Player: start '\(self.request?.title.name ?? "?", privacy: .public)' at \(Int(start), privacy: .public)s via \(link.service, privacy: .public)\(self.headStart ? ", head start from disk" : "", privacy: .public)")
        if !isTrailer, let r = request { Task { await self.loadSubtitles(for: r, link: link, prefs: subs) } }
        armStartWatch()
    }

    // MARK: Instant Play's fallback (next ready link)

    /// The link didn't open or start ([why]): it's forgotten (its torrent skipped for this title) and the next link
    /// Dream Core unlocked alongside it plays from the same spot behind the curtain, with no error screen while one
    /// is left (at most `maxFallbacks` per item). False when there is none: the caller shows the failure.
    private func recover(_ why: String) -> Bool {
        guard !isTrailer, let r = request, link != nil, let extras = Services.shared.playback as? PlaybackExtras else { return false }
        let at = resumeSpot()
        extras.forget(r)
        guard fallbacks < Self.maxFallbacks, let next = extras.nextLink(for: r) else { return false }
        fallbacks += 1
        Log.player.notice("Player: switching to the next source (\(next.service, privacy: .public) \(next.quality, privacy: .public)) at \(Int(at), privacy: .public)s: \(why, privacy: .public)")
        firstFrame = false
        retriedFromStart = false
        phase = .loading
        pendingStart = at
        start(next, at: at)
        return true
    }

    /// `startWatchSeconds` after a load with no first frame and another link ready, the next link plays instead;
    /// with none ready the stream keeps its chance until the curtain's own timeout.
    private func armStartWatch() {
        startWatch?.cancel()
        let g = gen
        startWatch = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.startWatchSeconds))
            guard let self, !Task.isCancelled, g == self.gen, !self.isTrailer, !self.firstFrame, self.phase == .loading,
                  let r = self.request, (Services.shared.playback as? PlaybackExtras)?.hasNextLink(for: r) == true else { return }
            _ = self.recover("no first frame after \(Int(Self.startWatchSeconds)) s")
        }
    }

    private func loadMeta(for r: PlayRequest) {
        metaTask?.cancel()
        let g = gen
        _ = loadCast(for: r)
        metaTask = Task { [weak self] in
            let catalog = Services.shared.catalog
            let d = try? await catalog.details(kind: r.title.kind, id: r.title.id)
            guard let self, g == self.gen else { return }
            self.details = d
            if let s = r.season, let e = r.episode {
                let eps = (try? await catalog.episodes(showId: r.title.id, season: s)) ?? []
                guard g == self.gen else { return }
                self.episodeInfo = eps.first { $0.number == e }
            }
            let m = await Services.shared.playback.skipMarkers(for: r)
            guard g == self.gen else { return }
            self.markers = m
            let n = await Services.shared.playback.nextEpisode(after: r)
            guard g == self.gen else { return }
            self.next = n
            if let n, let s = n.season, let e = n.episode {
                let eps = (try? await catalog.episodes(showId: n.title.id, season: s)) ?? []
                guard g == self.gen else { return }
                self.nextEpisode = eps.first { $0.number == e }
            }
        }
    }

    /// X-Ray's cast for `r` (one lookup per item, shared by the look-ahead and X-Ray opening before it answered).
    private func loadCast(for r: PlayRequest) -> Task<[CastMember], Never> {
        if let castTask { return castTask }
        let g = gen
        let t = Task { [weak self] () -> [CastMember] in
            let list = await Services.shared.catalog.cast(for: r)
            if let self, g == self.gen { self.xrayCast = list }
            return list
        }
        castTask = t
        return t
    }

    /// X-Ray opening: the cast of what's playing (at once when the look-ahead already answered).
    public func castForXRay() async -> [CastMember] {
        if let xrayCast { return xrayCast }
        guard let r = request, !isTrailer else { return [] }
        return await loadCast(for: r).value
    }

    // MARK: Trailers

    private func resolveTrailer() {
        let g = gen
        guard let key = trailerKeys.first else {
            phase = .failed(title: "Trailer Unavailable", message: "This trailer can't be played right now. Try again in a moment.")
            return
        }
        resolveTask = Task { [weak self] in
            guard let self else { return }
            do {
                let s = try await self.withTimeout(Self.trailerTimeout) { try await Trailers.resolver.stream(forKey: key) }
                guard g == self.gen else { return }
                let at = self.trailerAdvances == 0 ? self.trailerStart : 0
                self.engine.setSpeed(1)
                self.engine.load(EngineSource(url: s.video, audioURL: s.audio), start: at, autoplay: true)
                self.armTrailerTimeout(g)
            } catch {
                guard g == self.gen else { return }
                Log.player.error("Player: trailer \(key, privacy: .public) failed: \(String(describing: error), privacy: .public)")
                self.nextTrailer()
            }
        }
    }

    private func armTrailerTimeout(_ g: Int) {
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.trailerTimeout))
            guard let self, g == self.gen, self.isTrailer, !self.firstFrame else { return }
            self.nextTrailer()
        }
    }

    /// That video failed before its first frame: the next one behind the same curtain (at most 3 per press).
    private func nextTrailer() {
        if !trailerKeys.isEmpty { trailerKeys.removeFirst() }
        trailerAdvances += 1
        if trailerAdvances > 3 || trailerKeys.isEmpty {
            engine.stop()
            phase = .failed(title: "Trailer Unavailable", message: "This trailer can't be played right now. Try again in a moment.")
            return
        }
        gen += 1
        resolveTrailer()
    }

    // MARK: Curtain actions

    /// "Try Again": forget the failed link, look again from the same spot (DreamPlayerActivity.tryAnotherSource).
    public func tryAgain() {
        guard let r = request else { close(); return }
        if isTrailer, let t = request?.title { playTrailer(of: t, keys: trailerKeys.isEmpty ? [] : trailerKeys); return }
        (Services.shared.playback as? PlaybackExtras)?.forget(r)
        play(r, at: resumeSpot())
    }

    /// "Search All Sources": one search with the viewer's source filters set aside.
    public func searchAllSources() {
        guard let r = request, !isTrailer else { tryAgain(); return }
        play(r, loose: true, at: resumeSpot())
    }

    /// Where a retry or source switch resumes: the spot that played, else where this item meant to start.
    public func resumeSpot() -> Double { firstFrame && state.hasSource ? state.position : pendingStart }

    // MARK: Transport (called by the screen)

    public func togglePlay() {
        Self.autoplayedInARow = 0
        // A soft pop on pause and play (owner, 2026-10-27): the system's quiet "tock", which follows the silent switch.
        AudioServicesPlaySystemSound(1104)
        if state.isPlaying || (state.isBuffering && !state.paused) { engine.pause() } else { engine.play() }
    }

    public func seek(to t: Double) {
        let d = state.duration
        engine.seek(to: d > 0 ? min(max(0, t), max(0, d - 2)) : max(0, t))
    }

    public func setSpeed(_ s: Double) {
        speed = s
        engine.setSpeed(s)
        show(toast: "Speed  ·  \(Labels.speed(s))")
    }

    public func setFill(_ on: Bool) {
        fill = on
        engine.setFill(on)
    }

    public func startOver() {
        guard firstFrame else { return }
        engine.seek(to: 0)
        if !state.isPlaying { engine.play() }
        show(toast: "Starting From The Beginning")
    }

    public func controlsShown(_ shown: Bool) {
        engine.setSubtitleStyle(PlayerPrefs.style(raised: shown))
    }

    /// Any touch on the player (PostPlay: a key press resets the autoplay run).
    public func userActed() { Self.autoplayedInARow = 0 }

    // MARK: Tracks

    public func tracks(_ kind: PlayerTrack.Kind) -> [PlayerTrack] { engine.tracks(kind) }

    public func pickAudio(_ t: PlayerTrack) {
        guard !t.selected else { return }
        engine.select(.audio, id: t.id)
        PlayerPrefs.setAudioLang(t.language)
        userAudio = true
        show(toast: "Audio  ·  \(t.title ?? t.label)")
    }

    public func pickSubtitles(_ t: PlayerTrack?) {
        userText = true
        if let t {
            engine.select(.text, id: t.id)
            PlayerPrefs.setSubLang(t.language)
            show(toast: "Subtitles  ·  \(t.label)")
        } else {
            engine.select(.text, id: nil)
            PlayerPrefs.setSubLang(PlayerPrefs.off)
            show(toast: "Subtitles  ·  Off")
        }
    }

    public func cycleBoost() {
        PlayerPrefs.boost = (PlayerPrefs.boost + 1) % PlayerPrefs.boostLabels.count
        engine.setBoost(PlayerPrefs.boostDb[PlayerPrefs.boost])
        show(toast: "Volume Boost  ·  \(PlayerPrefs.boostLabels[PlayerPrefs.boost])")
    }

    public func subtitleStyleChanged(raised: Bool) { engine.setSubtitleStyle(PlayerPrefs.style(raised: raised)) }

    /// The profile's languages when a stream's tracks arrive (PlayerOverlay.applyTrackPrefs).
    private func applyTrackPrefs() {
        if !audioApplied && !userAudio {
            let audio = engine.tracks(.audio)
            if !audio.isEmpty {
                audioApplied = true
                if let want = PlayerPrefs.audioLang {
                    let sel = audio.first { $0.selected }
                    if sel == nil || sel?.supported == false || sel?.language != want,
                       let pick = audio.first(where: { $0.supported && $0.language == want }) {
                        engine.select(.audio, id: pick.id)
                    }
                }
            }
        }
        if !userText {
            let text = engine.tracks(.text)
            var fresh = false
            for t in text where seenText.insert(t.id).inserted { fresh = true }
            guard fresh, let want = PlayerPrefs.subLang else { return }
            let sel = text.first { $0.selected }
            if want == PlayerPrefs.off {
                if sel != nil { engine.select(.text, id: nil) }
            } else if sel == nil || sel?.language != want {
                if let pick = text.filter({ $0.supported && $0.language == want }).sorted(by: { !$0.forced && $1.forced }).first {
                    engine.select(.text, id: pick.id)
                }
            }
        }
    }

    // MARK: Engine events

    private func handle(_ e: EngineEvent) {
        switch e {
        case .state:
            if state.isPlaying, itemStartedAt == nil { itemStartedAt = Date() }
            if !state.isPlaying { saveProgress(force: true) }
            NowPlaying.update(self)
            pip?.refresh()
        case .progress(let t):
            onProgress(t)
        case .tracks:
            applyTrackPrefs()
        case .firstFrame:
            guard !firstFrame else { break }
            firstFrame = true
            startWatch?.cancel()
            phase = .playing
            if !isTrailer { applyEmbeddedFirst() }
            let secs = Date().timeIntervalSince(loadStartedAt)
            Log.player.info("Player: first frame after \(String(format: "%.2f", secs), privacy: .public) s, \(self.engine.facts().summary, privacy: .public)\(self.headStart ? " (head start from disk)" : "", privacy: .public)\(self.fallbacks > 0 ? " (source \(self.fallbacks + 1))" : "", privacy: .public)")
            if !isTrailer, let r = request, let l = link {
                (Services.shared.playback as? PlaybackExtras)?.started(r, link: l, seconds: secs)
                if let n = note { show(toast: n, tint: n.contains("CAM") ? Palette.amberText : Color.white.opacity(0.92), seconds: 3.6) }
            }
            NowPlaying.update(self)
            pip?.refresh()
        case .format:
            // Badges and Info read the engine's facts (and whether the screen shows HDR) on the next draw.
            tick &+= 1
        case .ended:
            // Ended before any picture: an empty, cut or unreadable file (or a start spot past its end), never
            // "finished" (that closed the player without a word). From a spot: once more from the beginning; else
            // the failure screen, like an error.
            if !firstFrame, !isTrailer, state.hasSource {
                if pendingStart > 0, !retriedFromStart, let l = link, let r = request {
                    retriedFromStart = true
                    Log.player.error("Player: stream ended before its first frame at \(Int(self.pendingStart), privacy: .public)s; once more from the beginning")
                    pendingStart = 0
                    play(link: l, for: r, at: 0)
                } else {
                    Log.player.error("Player: stream ended before its first frame (empty or unreadable file)")
                    handle(.error("This file is damaged or incomplete."))
                }
                break
            }
            onEnded()
            pip?.refresh()
        case .error(let message):
            Log.player.error("Player: engine error: \(message, privacy: .public)")
            if isTrailer {
                if !firstFrame { nextTrailer(); break }
                phase = .failed(title: "Trailer Unavailable", message: "This trailer can't be played right now. Try again in a moment.")
                break
            }
            startWatch?.cancel()
            saveProgress(force: true)
            // Instant Play: the next ready link, silently, while one is left.
            if recover(message) { break }
            if let r = request { (Services.shared.playback as? PlaybackExtras)?.forget(r) }
            phase = .failed(title: "This Title Isn't Available Right Now", message: "Try again in a moment, or choose another source.")
        }
        // Real watching time: only small forward steps while playing count (a seek or skip jumps and adds nothing).
        if state.isPlaying {
            let p = state.position
            if lastTickPos >= 0 { let d = p - lastTickPos; if d > 0 && d < 2.5 { realWatched += d } }
            lastTickPos = p
        } else {
            lastTickPos = state.position
        }
        tick &+= 1
        // Trakt's scrobble follows what plays (DreamCore PlayerReports; added by the Trakt worker, returns at once).
        if firstFrame, !isTrailer, let r = request, state.hasSource {
            PlayerReports.report(r, playing: state.isPlaying || (state.isBuffering && !state.paused),
                                 positionMs: Int64(state.position * 1000), durationMs: Int64(state.duration * 1000))
        }
    }

    private func fail(_ error: Error) {
        if let p = error as? PlaybackProblem { phase = .failed(title: p.title, message: p.message); return }
        if error is TimeoutError {
            phase = .failed(title: "Couldn't Find a Ready Stream", message: "Try again in a moment, or choose another source.")
            return
        }
        let line = (error as? DreamError)?.message ?? "Try again in a moment, or choose another source."
        phase = .failed(title: "This Title Isn't Available Right Now", message: line)
    }

    // MARK: Progress, finished, Continue Watching

    private func onProgress(_ pos: Double) {
        let d = state.duration
        let credits = realCreditsStart()
        if !finished && ((credits != nil && pos >= credits!) || (d >= Self.minHistory && pos >= d * Self.finishedFraction)) {
            creditsStarted()
        } else if finished && pos < finishedAt - 60 {
            finished = false
        }
        saveProgress(force: false)
        if !isTrailer {
            updateSkip(pos)
            updatePostPlay(pos)
        }
    }

    /// Real credits (not an estimate), if more than 60% in.
    private func realCreditsStart() -> Double? {
        let d = state.duration
        guard let c = markers.first(where: { $0.kind == .credits })?.start, d > 0, c > d * 0.6, c < d else { return nil }
        return c
    }

    /// The credits have started: counts as watched.
    func creditsStarted() {
        guard !finished, state.hasSource, !isTrailer else { return }
        finished = true
        finishedAt = state.position
        saveProgress(force: true)
    }

    func saveProgress(force: Bool) {
        guard !isTrailer, let r = request, firstFrame, state.hasSource else { return }
        let d = state.duration
        guard d >= Self.minHistory else { return }
        // Not on Continue Watching yet: it only joins after real watching (skipping ahead to test doesn't count).
        guard onRow || realWatched >= Self.realWatchToSave else { return }
        if !force && Date().timeIntervalSince(lastSaveAt) < Self.saveEvery { return }
        lastSaveAt = Date()
        let pos = finished ? d : state.position
        let entry = HistoryEntry(title: r.title, season: r.season, episode: r.episode,
                                 episodeName: r.episodeName ?? episodeInfo?.name, stillPath: episodeInfo?.stillPath,
                                 positionMs: Int64(pos * 1000), durationMs: Int64(d * 1000))
        Task { await Services.shared.history.record(entry) }
    }

    private func onEnded() {
        ended = true
        if isTrailer { close(); return }
        finished = true
        finishedAt = state.duration
        saveProgress(force: true)
        skipLabel = nil
        endOfVideo()
    }

    // MARK: Skip Intro / Skip Recap (SkipController)

    private func updateSkip(_ pos: Double) {
        let intro = markers.first { $0.kind == .intro }
        let recap = markers.first { $0.kind == .recap }
        var label: String?
        if let i = intro, i.end > 0 {
            let s = i.start < 3 ? 0 : i.start
            if pos < s - 5 { doneIntro = false; autoSkipped = false }
            if !doneIntro && pos >= s && pos < i.end - 2 {
                if PlayerPrefs.autoSkipIntros && !autoSkipped {
                    autoSkipped = true
                    doneIntro = true
                    engine.seek(to: i.end)
                    show(toast: "Intro Skipped", seconds: 1.6)
                } else {
                    label = "Skip Intro"
                }
            }
        }
        if label == nil, let rc = recap, rc.end > 0 {
            let introEnd = intro?.end ?? 0
            let s = introEnd > 0 && introEnd < rc.end ? introEnd : max(0, rc.start)
            if pos < s - 5 { doneRecap = false }
            if !doneRecap && pos >= s && pos < rc.end - 2 { label = "Skip Recap" }
        }
        if label != skipLabel { skipLabel = label }
    }

    public func skip() {
        Self.autoplayedInARow = 0
        if skipLabel == "Skip Intro", let i = markers.first(where: { $0.kind == .intro }) { doneIntro = true; engine.seek(to: i.end) }
        else if skipLabel == "Skip Recap", let r = markers.first(where: { $0.kind == .recap }) { doneRecap = true; engine.seek(to: r.end) }
        skipLabel = nil
    }

    // MARK: Post-play (PostPlay.kt)

    private var autoplayOn: Bool { PlayerPrefs.autoplayNext }

    private func computeCreditsAt() -> Double? {
        let d = state.duration
        guard d > 0 else { return nil }
        if let c = markers.first(where: { $0.kind == .credits })?.start, c > d / 2, c < d { return c }
        if request?.episode != nil { return max(d - 30, 0.9 * d) }
        return max(d - min(180, 0.06 * d), 0.9 * d)
    }

    private func updatePostPlay(_ pos: Double) {
        guard let r = request else { return }
        // Are You Still Watching?
        if !stillAsked, r.episode != nil, Self.autoplayedInARow >= 3, PlayerPrefs.stillWatching,
           let s = itemStartedAt, Date().timeIntervalSince(s) > 3 {
            stillAsked = true
            engine.pause()
            postPlay = .stillWatching
            return
        }
        if creditsAt == nil { creditsAt = computeCreditsAt() }
        guard let c = creditsAt else { return }
        if pos >= c - 90 { prepared = true }
        switch postPlay {
        case .upNext, .moreLikeThis:
            if pos < c - 5 { countdownTask?.cancel(); postPlay = .none; dismissedPostPlay = false }
        case .none:
            if dismissedPostPlay && pos < c - 5 { dismissedPostPlay = false }
            if !dismissedPostPlay && pos >= c { showPostPlay() }
        case .stillWatching:
            break
        }
    }

    private var recommendations: [Title] { (details?.recommendations ?? []).filter { $0.backdropPath != nil }.prefix(4).map { $0 } }
    public var moreLikeThis: [Title] { recommendations }

    private func showPostPlay() {
        creditsStarted()
        if request?.episode != nil, next != nil {
            startUpNext(countdown: autoplayOn)
        } else if !recommendations.isEmpty {
            postPlay = .moreLikeThis
        } else {
            dismissedPostPlay = true
        }
    }

    private func startUpNext(countdown: Bool) {
        postPlay = .upNext(countdown: countdown)
        countdownTask?.cancel()
        guard countdown else { return }
        countdownTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(10.3))
            guard let self, !Task.isCancelled, case .upNext = self.postPlay else { return }
            self.advance(auto: true)
        }
    }

    private func endOfVideo() {
        guard !advanced else { return }
        if request?.episode != nil, next != nil {
            if autoplayOn {
                let auto: Bool
                if case .upNext(let cd) = postPlay { auto = cd } else { auto = true }
                advance(auto: auto)
                return
            }
            if case .upNext = postPlay {} else { startUpNext(countdown: false) }
        } else if !recommendations.isEmpty {
            if postPlay != .moreLikeThis { postPlay = .moreLikeThis }
        } else {
            close()
            return
        }
        idleClose?.cancel()
        idleClose = Task { [weak self] in
            try? await Task.sleep(for: .seconds(90))
            guard let self, !Task.isCancelled else { return }
            self.close()
        }
    }

    /// Up Next's Play (or its countdown): the next episode, staying in the player.
    public func advance(auto: Bool) {
        guard let n = next, !advanced else { return }
        advanced = true
        if auto { Self.autoplayedInARow += 1 } else { Self.autoplayedInARow = 0 }
        countdownTask?.cancel()
        playNext(n)
    }

    /// Up Next's Credits / dismiss: keep watching the credits (closes when the video already ended).
    public func dismissPostPlay() {
        countdownTask?.cancel()
        if ended { close(); return }
        dismissedPostPlay = true
        postPlay = .none
    }

    /// "Watch Credits" / "Watch Again".
    public func watchCredits() {
        if ended { engine.seek(to: 0); engine.play(); ended = false; idleClose?.cancel() }
        dismissedPostPlay = true
        postPlay = .none
    }

    public func continueWatching() {
        Self.autoplayedInARow = 0
        postPlay = .none
        engine.play()
    }

    /// Plays another item in this player (Next Episode, an Episodes pick, a More Like This movie).
    public func playNext(_ r: PlayRequest) {
        if !finished && state.hasSource && state.duration > 0 { finished = state.position > state.duration * 0.8 }
        saveProgress(force: true)
        play(r)
    }

    // MARK: Sleep timer

    public static let sleepChoices = [0, 15, 30, 45, 60, 90, 120]
    public static func sleepLabel(_ m: Int) -> String {
        switch m {
        case 0: return "Off"
        case 60: return "1 Hour"
        case 90: return "1 h 30 min"
        case 120: return "2 Hours"
        default: return "\(m) Minutes"
        }
    }

    public var sleepMinutesLeft: Int? {
        guard let e = sleepEndsAt else { return nil }
        return max(0, Int(e.timeIntervalSinceNow / 60) + 1)
    }

    public func setSleep(_ minutes: Int) {
        sleepChosen = minutes
        armSleep(minutes)
        show(toast: minutes == 0 ? "Sleep Timer Off" : "Sleep Timer Set for \(minutes) Minutes")
    }

    private func armSleep(_ minutes: Int) {
        sleepTask?.cancel()
        sleepCountdown = nil
        guard minutes > 0 else { sleepEndsAt = nil; return }
        let end = Date().addingTimeInterval(Double(minutes) * 60)
        sleepEndsAt = end
        sleepTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(end.timeIntervalSinceNow))
            guard let self, !Task.isCancelled else { return }
            for n in stride(from: 60, through: 1, by: -1) {
                self.sleepCountdown = n
                try? await Task.sleep(for: .seconds(1))
                if Task.isCancelled { return }
            }
            self.sleepNow()
        }
    }

    public func keepWatching() { armSleep(max(sleepChosen, 90)) }

    public func sleepNow() {
        sleepTask?.cancel()
        sleepCountdown = nil
        sleepEndsAt = nil
        engine.pause()
        close()
    }

    // MARK: Toasts

    public func show(toast text: String, tint: Color = .white, seconds: Double = 1.9) {
        let t = Toast(text: text, tint: tint)
        toast = t
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            if self?.toast?.id == t.id { self?.toast = nil }
        }
    }

    // MARK: Leaving

    /// App to the background (or the screen locked): the sound keeps playing (lock screen and Control Center
    /// controls), the picture stops drawing unless Picture in Picture floats it. Trailers pause.
    func didEnterBackground() {
        saveProgress(force: true)
        if isTrailer, !(pip?.isActive ?? false), state.isPlaying { engine.pause() }
        engine.setVideoEnabled(false)
        NowPlaying.update(self)
    }

    func willEnterForeground() { engine.setVideoEnabled(true) }

    public func close() {
        saveProgress(force: true)
        endItem()
        onClose?()
    }

    private func endItem() {
        saveProgress(force: true)
        resolveTask?.cancel()
        metaTask?.cancel()
        startWatch?.cancel()
        cancelTimers()
        if state.hasSource { engine.stop() }
    }

    private func cancelTimers() {
        countdownTask?.cancel()
        idleClose?.cancel()
    }

    func shutdown() {
        PlayerReports.closed() // Trakt's scrobble stop (added by the Trakt worker)
        for o in appObservers { NotificationCenter.default.removeObserver(o) }
        appObservers = []
        pip?.invalidate()
        endItem()
        sleepTask?.cancel()
        engine.shutdown()
        NowPlaying.clear()
    }

    // MARK: Helpers

    struct TimeoutError: Error {}

    private func withTimeout<T: Sendable>(_ seconds: Double, _ op: @escaping @Sendable () async throws -> T) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await op() }
            group.addTask {
                try await Task.sleep(for: .seconds(seconds))
                throw TimeoutError()
            }
            defer { group.cancelAll() }
            guard let first = try await group.next() else { throw TimeoutError() }
            return first
        }
    }

    // MARK: Dream Subtitles

    /// Dream Subtitles come after the start (mpv adds them without a visible re-prepare). The best one shows when
    /// subtitles are on and the video has none of its own in the viewer's languages, or "Use Subtitles in the Video
    /// First" is off (DreamSubtitles.kt `preferDownload`); otherwise they're only listed in the menu.
    private func loadSubtitles(for r: PlayRequest, link: StreamLink, prefs p: DreamSubtitles.Prefs) async {
        var files = await DreamSubtitles.fetch(for: r, fileName: link.fileName, prefs: p)
        guard request == r, self.link == link, !files.isEmpty else { return }
        let langs = p.trackLangs
        let hasOwn = engine.tracks(.text).contains { !$0.external && $0.supported && $0.language.map(langs.contains) == true }
        let preferDownload = !(p.embeddedFirst && hasOwn)
        if p.show, preferDownload, !userText, PlayerPrefs.subLang != PlayerPrefs.off { files[0].asDefault = true }
        engine.addSubtitles(files)
        Log.player.info("Player: Dream Subtitles added \(files.count, privacy: .public), \(files[0].asDefault ? "showing the first" : "listed only", privacy: .public)")
    }

    /// The first frame (the tracks are known): with "Use Subtitles in the Video First" and subtitles on but none
    /// picked yet, the video's own track in the first of the viewer's languages it has (DreamSubtitles.kt
    /// `onFirstFrame`; mpv's own pick at the start only knows the first language).
    private func applyEmbeddedFirst() {
        guard let p = subPrefs, p.show, p.embeddedFirst, !userText, PlayerPrefs.subLang != PlayerPrefs.off else { return }
        let text = engine.tracks(.text)
        guard !text.contains(where: \.selected) else { return }
        for l in p.trackLangs {
            if let t = text.filter({ $0.supported && $0.language == l }).sorted(by: { !$0.forced && $1.forced }).first {
                engine.select(.text, id: t.id)
                return
            }
        }
    }
}
