import AVFoundation
import UIKit
import DreamCore

/// AVPlayer behind `PlayerEngine`: the fallback when libmpv isn't linked. Plays MP4 / MOV / HLS with AAC,
/// AC-3 and E-AC-3 only (no MKV, DTS or TrueHD); such streams fail with a plain "format" sentence.
@MainActor
final class AVEngine: PlayerEngine {
    let name = "AVPlayer"
    private let player = AVPlayer()
    private let surface = AVSurfaceView()
    private(set) var state = EngineState()
    var onEvent: ((EngineEvent) -> Void)?
    private var timeObserver: Any?
    private var observations: [NSKeyValueObservation] = []
    private var firstFrameSent = false
    private var boost: Double = 0

    var view: UIView { surface }

    init() {
        surface.playerLayer.player = player
        surface.playerLayer.videoGravity = .resizeAspect
        surface.backgroundColor = .black
        player.automaticallyWaitsToMinimizeStalling = true
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 2), queue: .main) { [weak self] t in
            MainActor.assumeIsolated { self?.tick(t) }
        }
        observations.append(player.observe(\.timeControlStatus) { [weak self] _, _ in
            DispatchQueue.main.async { self?.refresh() }
        })
        observations.append(surface.playerLayer.observe(\.isReadyForDisplay) { [weak self] layer, _ in
            let ready = layer.isReadyForDisplay
            DispatchQueue.main.async {
                guard let self, ready, !self.firstFrameSent, self.state.hasSource else { return }
                self.firstFrameSent = true
                self.onEvent?(.firstFrame)
                self.onEvent?(.format)
            }
        })
        NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: nil, queue: .main) { [weak self] n in
            let obj = ObjectIdentifier(n.object as AnyObject)
            MainActor.assumeIsolated {
                guard let self, let cur = self.player.currentItem, obj == ObjectIdentifier(cur) else { return }
                self.state.ended = true
                self.refresh()
                self.onEvent?(.ended)
            }
        }
        NotificationCenter.default.addObserver(forName: AVPlayerItem.failedToPlayToEndTimeNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.fail("The video couldn't be decoded.") }
        }
    }

    func load(_ source: EngineSource, start: Double, autoplay: Bool) {
        let opts: [String: Any] = source.headers.isEmpty ? [:] : ["AVURLAssetHTTPHeaderFieldsKey": source.headers]
        let item = AVPlayerItem(asset: AVURLAsset(url: source.url, options: opts))
        item.preferredForwardBufferDuration = 120
        observations.removeAll { _ in false }
        observations.append(item.observe(\.status) { [weak self] it, _ in
            DispatchQueue.main.async {
                guard let self, it === self.player.currentItem else { return }
                if it.status == .failed {
                    Log.player.error("AVPlayer item failed: \(String(describing: it.error), privacy: .public)")
                    self.fail("This file format isn't supported.")
                } else if it.status == .readyToPlay {
                    self.onEvent?(.tracks)
                    self.refresh()
                }
            }
        })
        firstFrameSent = false
        state = EngineState(hasSource: true, paused: !autoplay, position: start, speed: state.speed)
        player.replaceCurrentItem(with: item)
        if start > 0 { player.seek(to: CMTime(seconds: start, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero) }
        if autoplay { player.playImmediately(atRate: Float(state.speed)) }
        refresh()
    }

    func play() {
        if state.ended { seek(to: 0); state.ended = false }
        state.paused = false
        player.rate = Float(state.speed)
        refresh()
    }

    func pause() { state.paused = true; player.pause(); refresh() }

    func seek(to seconds: Double) {
        let d = state.duration
        let t = d > 0 ? min(max(0, seconds), d) : max(0, seconds)
        state.position = t
        if t < d - 1 { state.ended = false }
        player.seek(to: CMTime(seconds: t, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
    }

    func setSpeed(_ speed: Double) {
        state.speed = min(max(speed, 0.25), 2)
        if !state.paused { player.rate = Float(state.speed) }
    }

    func tracks(_ kind: PlayerTrack.Kind) -> [PlayerTrack] {
        guard let item = player.currentItem, kind != .video else { return [] }
        let ch: AVMediaCharacteristic = kind == .audio ? .audible : .legible
        guard let group = item.asset.mediaSelectionGroup(forMediaCharacteristic: ch) else { return [] }
        let sel = item.currentMediaSelection.selectedMediaOption(in: group)
        return group.options.enumerated().map { i, o in
            let lang = Labels.normLang(o.extendedLanguageTag ?? o.locale?.identifier)
            let name = o.displayName
            return PlayerTrack(id: "\(i)", kind: kind, label: name.isEmpty ? Labels.languageName(lang) : name, language: lang,
                               selected: o == sel, supported: o.isPlayable, title: name, detail: nil)
        }
    }

    func select(_ kind: PlayerTrack.Kind, id: String?) {
        guard let item = player.currentItem, kind != .video else { return }
        let ch: AVMediaCharacteristic = kind == .audio ? .audible : .legible
        guard let group = item.asset.mediaSelectionGroup(forMediaCharacteristic: ch) else { return }
        guard let id, let i = Int(id), i < group.options.count else {
            if kind == .text { item.select(nil, in: group) }
            return
        }
        item.select(group.options[i], in: group)
        onEvent?(.tracks)
    }

    /// AVPlayer can't side-load subtitle files into a stream; they're ignored here (libmpv handles them).
    func addSubtitles(_ files: [SubtitleFile]) {}

    func facts() -> StreamFacts {
        var f = StreamFacts()
        let size = player.currentItem?.presentationSize ?? .zero
        f.width = Int(size.width); f.height = Int(size.height)
        return f
    }

    func setFill(_ fill: Bool) { surface.playerLayer.videoGravity = fill ? .resizeAspectFill : .resizeAspect }
    func setSubtitleStyle(_ style: SubtitleStyle) {}
    func setBoost(_ db: Double) { boost = db; player.volume = 1 }
    func setPreferredLanguages(audio: String?, text: String?) {}
    func setVideoEnabled(_ on: Bool) {
        videoOn = on
        // Detached in the background so the sound keeps going; Picture in Picture needs the layer attached.
        if !pipActive { surface.playerLayer.player = on ? player : nil }
    }

    // Picture in Picture: AVKit floats the player layer itself.
    private var pipActive = false
    private var videoOn = true
    var onPiPFrame: (() -> Void)?
    var pipContent: PiPContent? { .playerLayer(surface.playerLayer) }
    func pipWillStart() {
        pipActive = true
        surface.playerLayer.player = player
        let cb = onPiPFrame
        DispatchQueue.main.async { cb?() }
    }
    func pipDidStop() {
        pipActive = false
        if !videoOn { surface.playerLayer.player = nil }
    }

    func stop() {
        player.pause()
        player.replaceCurrentItem(with: nil)
        state = EngineState(speed: state.speed)
        firstFrameSent = false
    }

    func shutdown() {
        stop()
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        timeObserver = nil
        observations.removeAll()
        NotificationCenter.default.removeObserver(self)
        onEvent = nil
    }

    private func tick(_ t: CMTime) {
        guard state.hasSource else { return }
        state.position = t.seconds.isFinite ? t.seconds : state.position
        if let item = player.currentItem {
            let d = item.duration.seconds
            state.duration = d.isFinite && d > 0 ? d : 0
            if let r = item.loadedTimeRanges.last?.timeRangeValue { state.buffered = (r.start + r.duration).seconds }
        }
        if state.isPlaying { onEvent?(.progress(state.position)) }
    }

    private func refresh() {
        let was = (state.isPlaying, state.isBuffering)
        state.isPlaying = player.timeControlStatus == .playing
        state.isBuffering = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
        if was != (state.isPlaying, state.isBuffering) || true { onEvent?(.state) }
    }

    private func fail(_ message: String) {
        state.error = message
        onEvent?(.error(message))
    }
}

/// A UIView whose layer is the AVPlayerLayer.
final class AVSurfaceView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}
