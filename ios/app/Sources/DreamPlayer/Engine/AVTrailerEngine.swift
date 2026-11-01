import Foundation
import AVFoundation
import UIKit
import DreamCore

/// The trailer a title page has playing behind its hero, on its way to full screen: the live `AVPlayer`, the
/// `AVPlayerLayer` it is drawing in, the sound player beside it when the page already had sound on, and the streams
/// it is playing. The page hands these over and keeps nothing: the layer travels with the player, so the frame on
/// screen is still the same frame after the move.
public struct LiveTrailer {
    public var player: AVPlayer
    public var layer: AVPlayerLayer
    /// The separate sound stream's player, when the page was already playing with sound.
    public var audio: AVPlayer?
    /// What it is playing (the sound URL is needed when the page was muted).
    public var stream: TrailerStream
    public init(player: AVPlayer, layer: AVPlayerLayer, audio: AVPlayer? = nil, stream: TrailerStream) {
        self.player = player; self.layer = layer; self.audio = audio; self.stream = stream
    }

    public var position: Double {
        let t = player.currentTime().seconds
        return t.isFinite ? max(0, t) : 0
    }

    /// Nothing can take it over (a player is already up): the live player and its layer go.
    @MainActor public func drop() {
        player.pause()
        audio?.pause()
        player.replaceCurrentItem(with: nil)
        audio?.replaceCurrentItem(with: nil)
        layer.player = nil
        layer.removeFromSuperlayer()
    }
}

/// Dream Player's engine for trailers that are already playing: AVFoundation, not mpv, because the title page's hero
/// plays its background trailer in an `AVPlayer` and the Trailer button has to show *that* player full screen rather
/// than open the stream again (owner, 2026-10-31: "it doesn't reload the trailer, it just puts the trailer already
/// playing in full screen"). mpv cannot adopt a running AVFoundation player, so the player and its layer move here
/// instead and this engine drives them for the rest of the session.
///
/// It is used only for that hand-over; a trailer opened cold still goes through the normal engine. Every control the
/// trailer screen shows works here (transport, scrubber, Close, and Try Again through `load`); subtitles, audio
/// tracks, boost and Picture in Picture have nothing to say for a trailer and do nothing.
@MainActor
public final class AVTrailerEngine: PlayerEngine {
    public let name = "AVPlayer"
    public var onEvent: ((EngineEvent) -> Void)?
    public private(set) var state = EngineState()
    public var view: UIView { host }

    private let host = TrailerLayerHost()
    private let player: AVPlayer
    private let layer: AVPlayerLayer
    /// The trailer's sound: a second player on the separate sound stream (the picture stream carries none).
    private var audio: AVPlayer?
    private var stream: TrailerStream?
    private var wantedSpeed: Double = 1
    private var pausedByUser = false
    /// The live trailer went back to the page's hero (`handBack`): this engine drives nothing any more.
    private var handedBack = false
    /// This engine took a page's running trailer rather than opening one, so it has one to give back.
    private var adopted = false
    private var reportedFirstFrame = false
    private var ticker: Any?
    private var watches: [NSKeyValueObservation] = []
    private var notes: [NSObjectProtocol] = []

    /// How far the sound may drift from the picture before it is lined up again.
    static let maxDrift: Double = 0.35

    /// Takes over the page's running trailer: the live layer is re-parented into this engine's view, still showing
    /// the frame it was showing, and nothing about the player's item, buffer or position is touched.
    public init(live: LiveTrailer) {
        player = live.player
        layer = live.layer
        audio = live.audio
        stream = live.stream
        adopted = true
        layer.videoGravity = .resizeAspect
        host.take(layer)
        reportedFirstFrame = true
        state.hasSource = true
        wantedSpeed = player.rate > 0 ? Double(player.rate) : 1
        watch()
        refresh(emit: false)
    }

    // MARK: Sound

    /// Full screen is a deliberate watch, so the sound comes up even when the page's trailer was muted (the phone's
    /// default). With sound already on, that player simply stays; muted, the trailer's sound stream joins the
    /// picture at its spot, and the picture itself never stops.
    public func bringUpSound() {
        player.volume = 1
        if let a = audio {
            a.isMuted = false
            a.volume = 1
            align(a)
            if player.timeControlStatus != .paused { a.play(); a.rate = Float(wantedSpeed) }
            return
        }
        if let url = stream?.audio {
            let a = AVPlayer(url: url)
            a.volume = 1
            a.allowsExternalPlayback = false
            a.automaticallyWaitsToMinimizeStalling = false
            audio = a
            align(a)
            if player.timeControlStatus != .paused { a.play(); a.rate = Float(wantedSpeed) }
        } else {
            // One file with its own sound track.
            player.isMuted = false
        }
    }

    private func align(_ a: AVPlayer) {
        a.seek(to: player.currentTime(), toleranceBefore: .zero, toleranceAfter: .zero)
    }

    private func checkDrift() {
        guard let a = audio, player.timeControlStatus == .playing else { return }
        let p = player.currentTime().seconds, s = a.currentTime().seconds
        guard p.isFinite, s.isFinite, abs(p - s) > Self.maxDrift else { return }
        align(a)
        if a.rate == 0 { a.play() }
        a.rate = Float(wantedSpeed)
    }

    // MARK: Transport

    public func load(_ source: EngineSource, start: Double, autoplay: Bool) {
        // Try Again, or the next key after a failure: the only path here that opens a stream.
        stream = TrailerStream(video: source.url, audio: source.audioURL)
        reportedFirstFrame = false
        state = EngineState()
        state.hasSource = true
        pausedByUser = false
        let item = AVPlayerItem(url: source.url)
        item.preferredForwardBufferDuration = 6
        player.replaceCurrentItem(with: item)
        player.isMuted = source.audioURL != nil
        player.volume = 1
        if let url = source.audioURL {
            let a = audio ?? AVPlayer()
            a.volume = 1
            a.allowsExternalPlayback = false
            audio = a
            a.replaceCurrentItem(with: AVPlayerItem(url: url))
        } else {
            audio?.replaceCurrentItem(with: nil)
            audio = nil
            player.isMuted = false
        }
        watch()
        if start > 0 {
            let t = CMTime(seconds: start, preferredTimescale: 600)
            player.seek(to: t, toleranceBefore: .zero, toleranceAfter: .zero)
            audio?.seek(to: t, toleranceBefore: .zero, toleranceAfter: .zero)
        }
        if autoplay { play() } else { refresh() }
    }

    public func play() {
        pausedByUser = false
        state.ended = false
        player.play()
        player.rate = Float(wantedSpeed)
        if let a = audio { align(a); a.play(); a.rate = Float(wantedSpeed) }
        refresh()
    }

    public func pause() {
        pausedByUser = true
        player.pause()
        audio?.pause()
        refresh()
    }

    public func seek(to seconds: Double) {
        let t = CMTime(seconds: max(0, seconds), preferredTimescale: 600)
        state.ended = false
        // The completion comes back on whatever thread AVFoundation uses, so the work hops to the main actor.
        player.seek(to: t, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                if let a = self.audio { self.align(a) }
                self.refresh()
            }
        }
    }

    public func setSpeed(_ speed: Double) {
        wantedSpeed = speed
        guard player.timeControlStatus != .paused else { return }
        player.rate = Float(speed)
        audio?.rate = Float(speed)
    }

    public func stop() {
        // Handed back to the page: the player is the hero's again and nothing here may touch it.
        guard !handedBack else { clearWatches(); state = EngineState(); onEvent?(.state); return }
        // The close path runs `PlayerSession.close` -> `endItem` -> here, and only then `onClose` -> the hand-back.
        // So on the way out this is called while the trailer is still the page's to take back, and emptying the
        // player here is emptying the very thing that is about to be handed over: the hand-back then finds no item
        // and refuses, which is why a trailer stopped dead on close instead of carrying on behind the artwork.
        // It is quieted and let go of; `shutdown` empties it if nobody took it.
        let mayGoBack = adopted && !state.ended && state.error == nil && player.currentItem != nil
        player.pause()
        audio?.pause()
        if !mayGoBack {
            player.replaceCurrentItem(with: nil)
            audio?.replaceCurrentItem(with: nil)
        }
        clearWatches()
        state = EngineState()
        onEvent?(.state)
    }

    public func shutdown() {
        // Handed back to the page: the player belongs to the hero again, so nothing here may empty it.
        guard !handedBack else { clearWatches(); state = EngineState(); return }
        stop()
        // Nobody took it: this is the end of the line, so what `stop` left playable for the hand-back goes now.
        player.replaceCurrentItem(with: nil)
        audio?.replaceCurrentItem(with: nil)
        audio = nil
        stream = nil
        layer.player = nil
        layer.removeFromSuperlayer()
    }

    /// Closing full screen gives the live trailer **back** to the page's hero, the same move as the way in and for
    /// the same reason: the picture carries on instead of re-buffering (owner, 2026-11-01, "when exiting the trailer
    /// it stops playing and doesn't continue like going into it"). Nothing is paused, released, re-prepared or
    /// sought; this engine only lets go of what it was driving, and afterwards owns nothing.
    ///
    /// Nil when there is nothing live left to give back (it ran to its end, it failed, or it was already stopped):
    /// the page keeps its still and waits for the viewer, exactly as before.
    public func handBack() -> LiveTrailer? {
        guard !handedBack, !state.ended, state.error == nil, let s = stream,
              let item = player.currentItem, item.status != .failed else { return nil }
        handedBack = true
        clearWatches()
        // The hero draws its trailer where the backdrop was, so it fills; full screen fits the whole frame.
        layer.videoGravity = .resizeAspectFill
        // The viewer may have paused in full screen. The hero has no paused state and a frozen frame behind the
        // artwork reads as a fault, so it goes back playing, which is the state it was in when they pressed Trailer.
        if player.timeControlStatus == .paused { player.play() }
        let live = LiveTrailer(player: player, layer: layer, audio: audio, stream: s)
        audio = nil
        stream = nil
        state = EngineState()
        return live
    }

    // MARK: Nothing to say for a trailer

    public func tracks(_ kind: PlayerTrack.Kind) -> [PlayerTrack] { [] }
    public func select(_ kind: PlayerTrack.Kind, id: String?) {}
    public func addSubtitles(_ files: [SubtitleFile]) {}
    public func setSubtitleStyle(_ style: SubtitleStyle) {}
    public func setBoost(_ db: Double) {}
    public func setPreferredLanguages(audio: String?, text: String?) {}
    public func setVideoEnabled(_ on: Bool) {}

    public func setFill(_ fill: Bool) {
        layer.videoGravity = fill ? .resizeAspectFill : .resizeAspect
    }

    public func facts() -> StreamFacts {
        var f = StreamFacts()
        let size = player.currentItem?.presentationSize ?? .zero
        f.width = Int(size.width)
        f.height = Int(size.height)
        return f
    }

    // MARK: Watching the player

    private func watch() {
        clearWatches()
        guard let item = player.currentItem else { return }
        watches.append(player.observe(\.timeControlStatus) { [weak self] _, _ in
            Task { @MainActor in self?.refresh() }
        })
        watches.append(item.observe(\.status) { [weak self] it, _ in
            let failed = it.status == .failed
            let why = it.error?.localizedDescription
            Task { @MainActor in
                guard let self else { return }
                if failed { self.failed(why) } else { self.refresh() }
            }
        })
        watches.append(item.observe(\.loadedTimeRanges) { [weak self] _, _ in
            Task { @MainActor in self?.refresh() }
        })
        watches.append(item.observe(\.duration) { [weak self] _, _ in
            Task { @MainActor in self?.refresh() }
        })
        watches.append(item.observe(\.presentationSize) { [weak self] _, _ in
            Task { @MainActor in self?.onEvent?(.format) }
        })
        let nc = NotificationCenter.default
        notes.append(nc.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.ended() }
        })
        notes.append(nc.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] n in
            let why = (n.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error)?.localizedDescription
            MainActor.assumeIsolated { self?.failed(why) }
        })
        ticker = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600),
                                                queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.checkDrift()
                self.refresh(progress: true)
            }
        }
    }

    private func clearWatches() {
        if let t = ticker { player.removeTimeObserver(t); ticker = nil }
        watches = []
        notes.forEach(NotificationCenter.default.removeObserver)
        notes = []
    }

    private func refresh(emit: Bool = true, progress: Bool = false) {
        let item = player.currentItem
        state.hasSource = item != nil
        let status = player.timeControlStatus
        state.isPlaying = status == .playing
        state.isBuffering = status == .waitingToPlayAtSpecifiedRate
        state.paused = pausedByUser
        let pos = player.currentTime().seconds
        state.position = pos.isFinite ? max(0, pos) : 0
        let d = item?.duration.seconds ?? 0
        state.duration = d.isFinite && d > 0 ? d : 0
        state.buffered = buffered(item)
        state.speed = wantedSpeed
        if !reportedFirstFrame, item?.status == .readyToPlay, state.position > 0.05 {
            reportedFirstFrame = true
            onEvent?(.firstFrame)
        }
        if emit { onEvent?(.state) }
        if progress { onEvent?(.progress(state.position)) }
    }

    private func buffered(_ item: AVPlayerItem?) -> Double {
        guard let r = item?.loadedTimeRanges.map(\.timeRangeValue).max(by: { $0.end.seconds < $1.end.seconds }) else { return 0 }
        let end = r.end.seconds
        return end.isFinite ? end : 0
    }

    private func ended() {
        state.ended = true
        state.isPlaying = false
        state.isBuffering = false
        audio?.pause()
        onEvent?(.ended)
    }

    private func failed(_ why: String?) {
        Log.player.error("Trailer: AVPlayer failed\(why.map { ": " + $0 } ?? "", privacy: .public)")
        state.error = why
        onEvent?(.error("This trailer can't be played right now."))
    }
}

/// Holds the live `AVPlayerLayer` at its own size (the dock resizes this view for full screen).
final class TrailerLayerHost: UIView {
    private weak var held: AVPlayerLayer?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        isUserInteractionEnabled = false
    }
    required init?(coder: NSCoder) { fatalError() }

    /// The layer leaves wherever it was drawing and keeps drawing here (Core Animation moves it as it is).
    func take(_ l: AVPlayerLayer) {
        if l.superlayer !== layer { layer.addSublayer(l) }
        held = l
        l.frame = bounds
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        held?.frame = bounds
    }
}
