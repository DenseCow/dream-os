#if canImport(Libmpv)
import Foundation
import UIKit
import QuartzCore
import AVFoundation
import Libmpv
import os
import DreamCore

/// libmpv (MPVKit) behind `PlayerEngine`: plays what debrid links usually are (MKV, HEVC, Dolby Vision,
/// DTS / TrueHD decoded to PCM, ASS subtitles drawn by libass). Renders with gpu-next on Vulkan (MoltenVK)
/// into a CAMetalLayer, hardware decoding with VideoToolbox (MPVKit's iOS recipe).
@MainActor
final class MPVEngine: PlayerEngine {
    let name = "mpv"
    private let surface = MPVSurfaceView()
    /// Picture in Picture: the floating layer and mpv's software frame feed (see MPVFrameFeed.swift).
    private let samples: SampleBufferView
    private let container = MPVContainerView()
    private let feed: MPVFrameFeed
    private let core: MPVCore
    /// mpv draws into the sample buffer layer (vo=libmpv) instead of the Metal layer.
    private var pipMode = false
    /// The app is in front (setVideoEnabled); with Picture in Picture off, no picture while it isn't.
    private var videoOn = true
    /// Back from Picture in Picture: waiting for gpu-next's first picture before the Metal layer comes forward.
    private var awaitingMetal = false
    private var pipW = 0, pipH = 0
    var onPiPFrame: (() -> Void)?
    private(set) var state = EngineState()
    var onEvent: ((EngineEvent) -> Void)?

    private var trackList: [MPVValue] = []
    private var videoParams: [String: MPVValue] = [:]
    private var audioParams: [String: MPVValue] = [:]
    private var videoCodec = ""
    private var audioCodec = ""
    private var firstFrameSent = false
    private var loadStartedAt = Date()
    private var loading = false
    private var lastProgress: CFTimeInterval = 0
    private var lastHTTPStatus: Int?
    private var retries = 0
    private var source: EngineSource?
    private var subtitleStyle = SubtitleStyle()
    private var fill = false

    var view: UIView { container }
    var pipContent: PiPContent? { .sampleBuffer(samples.displayLayer) }

    // Watching upgrades (2026-10-28): buffer sized to the phone, HDR / Dolby Vision on the screen, scrub previews.
    /// This screen shows HDR (EDR) and the owner's switch allows it: mpv asks for an HDR picture for HDR streams.
    private let hdrOutput: Bool
    private var plan: BufferPlan?
    /// Two-second housekeeping while a stream is loaded: buffer readings, scrub preview gate.
    private var tick: Timer?
    private var cacheAheadBytes = 0, cacheTotalBytes = 0, cacheInputRate = 0
    private var cacheRanges: [(Double, Double)] = []
    private var cachedToEnd = false
    private var lastBufferLog: CFTimeInterval = 0
    /// A seek the viewer made: where to, when, and whether it landed inside what was buffered.
    private var pendingSeek: (target: Double, at: CFTimeInterval, inside: Bool)?
    private var stallStarted: CFTimeInterval?
    private var stalls = 0
    private var hdrLogged = false
    let thumbs = ScrubThumbnails()
    var thumbnails: ScrubThumbnails? { thumbs }
    private var thumbDesk: ThumbDesk?
    private var thumbWorkers: [MPVThumbnailer] = []
    private var lastStallAt: CFTimeInterval?
    private var thumbsTried = false
    private var stableTicks = 0
    private var firstFrameAt: CFTimeInterval = 0
    private var memoryObserver: NSObjectProtocol?

    init() {
        let samples = SampleBufferView()
        self.samples = samples
        feed = MPVFrameFeed(layer: samples.displayLayer)
        core = MPVCore()
        hdrOutput = Self.screenShowsHDR()
        container.install(samples: samples, metal: surface)
        feed.prime()
        NetPath.shared.start()
        core.sink = { [weak self] events in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.handle(events) } }
        }
        surface.layerHDR.allowed = hdrOutput
        surface.layerHDR.onChange = { [weak self] kind, name, format in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.pictureColorspaceChanged(kind, name: name, format: format) } }
        }
        core.start(layer: surface.metalLayer, hdr: hdrOutput)
        feed.onFirstFrame = { [weak self] in MainActor.assumeIsolated { self?.firstPiPFrame() } }
        thumbs.onFirstWant = { [weak self] in self?.thumbsWanted() }
        memoryObserver = NotificationCenter.default.addObserver(forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.thumbDesk != nil else { return }
                Log.player.info("Thumbs: memory warning, previews decoder stopped (\(self.thumbs.count, privacy: .public) frames kept)")
                self.stopThumbs()
            }
        }
        Log.player.info("HDR: screen \(self.hdrOutput ? "shows HDR, HDR streams get an HDR picture" : "SDR only (or switched off), HDR streams are toned down as before", privacy: .public) (headroom \(String(format: "%.1f", Self.edrHeadroom()), privacy: .public))")
    }

    /// The screen can show brighter than white (EDR) and the switch (DreamStore "player.edr", default on) allows it.
    private static func screenShowsHDR() -> Bool {
        let allowed = DreamStore.shared.get(Bool.self, "player.edr") ?? true
        return allowed && edrHeadroom() > 1.05
    }

    private static func edrHeadroom() -> Double { Double(UIScreen.main.potentialEDRHeadroom) }

    /// The first software frame is on the sample layer: only now does it come in front of the Metal picture
    /// (before that it would show black), then the window may open.
    private func firstPiPFrame() {
        guard pipMode else { return }
        container.samplesInFront(true)
        onPiPFrame?()
    }

    // MARK: Loading

    func load(_ source: EngineSource, start: Double, autoplay: Bool) {
        self.source = source
        retries = 0
        startFile(source, start: start, autoplay: autoplay)
    }

    private func startFile(_ src: EngineSource, start: Double, autoplay: Bool) {
        firstFrameSent = false
        loading = true
        loadStartedAt = Date()
        lastHTTPStatus = nil
        trackList = []; videoParams = [:]; audioParams = [:]
        state = EngineState(hasSource: true, paused: !autoplay, position: start, speed: state.speed)
        var headers = src.headers
        let ua = headers.first { $0.key.lowercased() == "user-agent" }?.value ?? "DreamOS/0.1 (iOS) DreamPlayer"
        headers = headers.filter { $0.key.lowercased() != "user-agent" }
        core.set("user-agent", ua)
        core.command(["set", "http-header-fields", ""])
        for (k, v) in headers { core.command(["change-list", "http-header-fields", "append", "\(k): \(v)"]) }
        core.set("pause", autoplay ? "no" : "yes")
        applyBufferPlan()
        resetWatchState()
        var opts = ["start=\(String(format: "%.3f", max(0, start)))"]
        if let a = src.audioURL { opts.append("audio-file=" + Self.quoteOpt(a.absoluteString)) }
        // Instant Play's head start (DreamCore StreamCache): when the stream's first MBs are on disk, mpv reads it
        // through the loopback server (disk first, then the link itself with the same headers); else the link directly.
        let target = src.audioURL == nil ? (StreamCache.shared.localURL(for: src.url, fileName: src.url.lastPathComponent) ?? src.url) : src.url
        core.command(["loadfile", target.absoluteString, "replace", "-1", opts.joined(separator: ",")])
        for s in src.subtitles { addSub(s) }
        Log.player.info("mpv: load \(src.url.host() ?? "file", privacy: .public) at \(Int(start), privacy: .public)s, \(headers.count, privacy: .public) headers, \(src.subtitles.count, privacy: .public) subs\(target != src.url ? ", head start from disk" : "", privacy: .public)")
        fire(.state)
    }

    /// mpv per-file option values with commas/equals need %n% length quoting.
    private static func quoteOpt(_ s: String) -> String { "%\(s.utf8.count)%\(s)" }

    // MARK: Transport

    func play() {
        if state.ended { seek(to: 0); state.ended = false }
        state.paused = false
        core.set("pause", "no")
        fire(.state)
    }

    func pause() {
        state.paused = true
        core.set("pause", "yes")
        fire(.state)
    }

    func seek(to seconds: Double) {
        let d = state.duration
        let t = d > 0 ? min(max(0, seconds), d) : max(0, seconds)
        state.position = t
        if d <= 0 || t < d - 1 { state.ended = false }
        if firstFrameSent { pendingSeek = (t, CACurrentMediaTime(), isBuffered(t)) }
        core.command(["seek", String(format: "%.3f", t), "absolute"])
    }

    func setSpeed(_ speed: Double) {
        state.speed = min(max(speed, 0.25), 2)
        core.set("speed", String(format: "%.2f", state.speed))
    }

    // MARK: Tracks

    func tracks(_ kind: PlayerTrack.Kind) -> [PlayerTrack] {
        let want = kind == .text ? "sub" : kind.rawValue
        var out: [PlayerTrack] = []
        for t in trackList {
            guard case .map(let m) = t, m["type"]?.string == want, let id = m["id"]?.int else { continue }
            let lang = Labels.normLang(m["lang"]?.string)
            let own = m["title"]?.string?.trimmingCharacters(in: .whitespaces)
            let selected = m["selected"]?.bool ?? false
            let codec = m["codec"]?.string ?? ""
            let external = m["external"]?.bool ?? false
            let forced = (m["forced"]?.bool ?? false) || (own?.lowercased().contains("forced") ?? false)
            if kind == .audio {
                let langName = lang.map { Labels.languageName($0) } ?? "Unknown Language"
                let ownExtra = own.flatMap { o -> String? in
                    let l = o.lowercased()
                    if o.isEmpty || l == langName.lowercased() || l == (m["lang"]?.string ?? "").lowercased() { return nil }
                    // Plain names like "English 5.1" or "AC3" add nothing to the detail line.
                    if Self.isPlainTrackName(o, lang: langName) { return nil }
                    return o
                }
                let title = ownExtra.map { "\(langName) · \($0)" } ?? langName
                let ch = m["demux-channel-count"]?.int ?? 0
                let profile = m["codec-profile"]?.string ?? ""
                var detailParts: [String] = []
                let cl = [Self.audioCodecLong(codec, profile: profile, title: own ?? ""), Labels.channelsLong(ch)].compactMap { $0 }.joined(separator: " ")
                if !cl.isEmpty { detailParts.append(cl) }
                if m["visual-impaired"]?.bool == true { detailParts.append("Audio Description") }
                if (own ?? "").lowercased().contains("comment"), ownExtra == nil { detailParts.append("Commentary") }
                let detail = detailParts.joined(separator: " · ")
                out.append(PlayerTrack(id: "\(id)", kind: kind, label: detail.isEmpty ? title : "\(title) · \(detail)", language: lang,
                                       selected: selected, supported: true, title: title, detail: detail, forced: false, external: external))
            } else if kind == .text {
                var parts = [lang.map { Labels.languageName($0) } ?? "Unknown Language"]
                if forced { parts.append("Forced") }
                let ownL = own?.lowercased() ?? ""
                if (m["hearing-impaired"]?.bool ?? false) || ownL.contains("sdh") { parts.append("SDH") }
                if external && !(m["title"]?.string?.hasPrefix("dl:") ?? false) { parts.append("External") }
                if let o = own, !o.isEmpty, !o.hasPrefix("dl:"), !parts.contains(where: { $0.lowercased() == ownL }),
                   ownL != "forced", ownL != "sdh", !Self.isPlainTrackName(o, lang: parts[0]) {
                    parts.append(o)
                }
                // Dream Subtitles carry their extra word in the title as "dl:SDH".
                if let o = own, o.hasPrefix("dl:"), o.count > 3 { parts.append(String(o.dropFirst(3))) }
                // Bitmap subtitles (PGS / VobSub) draw fine with libass' overlay path.
                out.append(PlayerTrack(id: "\(id)", kind: kind, label: parts.joined(separator: " · "), language: lang,
                                       selected: selected, supported: true, title: nil, detail: nil, forced: forced, external: external))
            } else {
                out.append(PlayerTrack(id: "\(id)", kind: kind, label: codec, language: lang, selected: selected, supported: true))
            }
        }
        // Same label twice: number them.
        var counts: [String: Int] = [:]
        for t in out { counts[t.label, default: 0] += 1 }
        var seen: [String: Int] = [:]
        return out.map { t in
            guard (counts[t.label] ?? 0) > 1 else { return t }
            seen[t.label, default: 0] += 1
            var c = t
            let n = " (\(seen[t.label]!))"
            c.label += n
            c.title = c.title.map { $0 + n }
            return c
        }
    }

    private static func isPlainTrackName(_ name: String, lang: String) -> Bool {
        let n = name.lowercased()
        let words = n.split(whereSeparator: { !$0.isLetter && !$0.isNumber }).map(String.init)
        let plain: Set<String> = ["ac3", "eac3", "e", "dts", "hd", "ma", "truehd", "atmos", "aac", "flac", "opus", "mp3", "stereo",
                                  "mono", "surround", "5", "1", "7", "2", "0", "ch", "dd", "ddp", "dolby", "digital", "plus", "audio",
                                  "track", "lc", "x", "pcm", lang.lowercased()]
        return !words.isEmpty && words.allSatisfy { plain.contains($0) }
    }

    func select(_ kind: PlayerTrack.Kind, id: String?) {
        let prop = kind == .audio ? "aid" : kind == .text ? "sid" : "vid"
        core.set(prop, id ?? "no")
    }

    func addSubtitles(_ files: [SubtitleFile]) {
        for f in files { addSub(f) }
    }

    private func addSub(_ f: SubtitleFile) {
        // Online ones are titled "dl:<extra word>" so the menu doesn't call them External.
        let title = f.online ? "dl:" + (f.label ?? "") : (f.label ?? "")
        core.command(["sub-add", f.url.isFileURL ? f.url.path : f.url.absoluteString, f.asDefault ? "select" : "auto", title, f.language ?? ""])
    }

    // MARK: Look

    func facts() -> StreamFacts {
        var f = StreamFacts()
        f.width = videoParams["w"]?.int ?? 0
        f.height = videoParams["h"]?.int ?? 0
        let vc = videoCodec.lowercased()
        f.videoCodec = vc.isEmpty ? nil : Self.videoCodecName(vc)
        let gamma = videoParams["gamma"]?.string ?? ""
        if vc.contains("dvh") || vc.contains("dolby") || (videoParams["dovi"] != nil) || dvProfile != nil {
            f.hdr = "Dolby Vision"
        } else if gamma == "pq" {
            f.hdr = "HDR10"
        } else if gamma == "hlg" {
            f.hdr = "HLG"
        }
        let current = selectedTrack("audio")
        let ac = (current?["codec"]?.string ?? audioCodec).lowercased()
        let profile = current?["codec-profile"]?.string ?? ""
        f.audioCodec = Self.audioCodecLong(ac, profile: profile, title: current?["title"]?.string ?? "")
        f.audioCodecShort = Self.audioCodecShort(ac, profile: profile)
        f.channels = audioParams["channel-count"]?.int ?? current?["demux-channel-count"]?.int ?? 0
        return f
    }

    private func selectedTrack(_ type: String) -> [String: MPVValue]? {
        for t in trackList { if case .map(let m) = t, m["type"]?.string == type, m["selected"]?.bool == true { return m } }
        return nil
    }

    func setFill(_ fill: Bool) {
        self.fill = fill
        core.set("panscan", fill ? "1.0" : "0.0")
    }

    func setSubtitleStyle(_ style: SubtitleStyle) {
        subtitleStyle = style
        core.set("sub-scale", String(format: "%.2f", style.scale))
        core.set("sub-color", String(format: "#%06X", style.color))
        if style.background > 0 {
            core.set("sub-border-style", "background-box")
            core.set("sub-back-color", String(format: "#%02X080612", Int(style.background * 255)))
            core.set("sub-outline-size", "0")
        } else {
            core.set("sub-border-style", "outline-and-shadow")
            core.set("sub-outline-color", "#E6000000")
            core.set("sub-outline-size", "2.2")
            core.set("sub-shadow-offset", "1")
        }
        core.set("sub-margin-y", "\(Int(style.marginFraction * 720))")
    }

    func setBoost(_ db: Double) {
        // +3 / +6 / +10 dB as mpv volume (100 = unity).
        let v = 100 * pow(10, max(0, db) / 20)
        core.set("volume", String(format: "%.0f", v))
    }

    func setPreferredLanguages(audio: String?, text: String?) {
        if let audio { core.set("alang", Self.langList(audio)) }
        if let text {
            if text == "off" { core.set("sid", "no"); core.set("slang", "") }
            else { core.set("slang", Self.langList(text)); core.set("sid", "auto") }
        }
    }

    /// mpv matches language tags as written in the file: give it both the 2- and 3-letter forms.
    private static func langList(_ code: String) -> String {
        var l = [code]
        if let three = Locale.LanguageCode(code).identifier(.alpha3) { l.append(three) }
        if code == "pt" { l.append("pob") }
        return l.joined(separator: ",")
    }

    func setVideoEnabled(_ on: Bool) {
        videoOn = on
        updateThumbGate()
        // Picture in Picture keeps drawing (on the CPU) while the app is away.
        if pipMode { return }
        // MPVKit: drawing into a hidden layer leaves a black picture after returning (and the GPU is off limits in
        // the background); switch video off meanwhile. Sound keeps playing.
        core.set("vid", on ? "auto" : "no")
    }

    // MARK: Picture in Picture

    // Every mpv change here goes through asynchronous commands: a `vo` change rebuilds the whole video chain
    // on mpv's core thread, and the main thread must never wait for it (the player must stay usable whatever
    // mpv does). Failures come back as command replies (logged in `handle`).

    func pipWillStart() {
        guard !pipMode else { return }
        guard state.hasSource else { Log.player.info("PiP: nothing playing, not switching"); return }
        guard core.withHandle({ feed.attach(to: $0) }) == true else { return }
        pipMode = true
        awaitingMetal = false
        updateThumbGate()
        updateFeedSize()
        feed.syncClock(position: state.position, rate: state.isPlaying ? state.speed : 0)
        feed.setFeeding(true)
        Log.player.info("PiP: mpv -> software frames at \(Int(self.state.position), privacy: .public) s (picture on: \(self.videoOn, privacy: .public))")
        core.command(["set", "sws-fast", "yes"])
        core.command(["set", "vo", "libmpv"], tag: MPVCore.tagPiPSwitch)
        if !videoOn { core.command(["set", "vid", "auto"]) }
        resumeAfterSwitch()
    }

    func pipDidStop() {
        guard pipMode else { return }
        pipMode = false
        feed.setFeeding(false)
        // Back in the app (restore can land before willEnterForeground): the Metal picture again.
        if UIApplication.shared.applicationState != .background { videoOn = true }
        Log.player.info("PiP: mpv -> Metal (app in front: \(self.videoOn, privacy: .public))")
        core.command(["set", "sws-fast", "no"])
        if videoOn {
            awaitingMetal = true
            core.command(["set", "vo", "gpu-next"], tag: MPVCore.tagPiPSwitch)
            resumeAfterSwitch()
            // Fallback in case "vo-configured" never flips (no video right now).
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
                MainActor.assumeIsolated { self?.metalReady() }
            }
        } else {
            // Still in the background: no picture (no GPU work there), gpu-next comes back with the app.
            core.command(["set", "vid", "no"])
            core.command(["set", "vo", "gpu-next"], tag: MPVCore.tagPiPSwitch)
            container.samplesInFront(false)
        }
    }

    /// A `vo` change rebuilds the video chain, decoder included, which then waits for the next keyframe (a
    /// black gap of seconds on streams with long GOPs). An exact seek to the current spot brings the picture
    /// back at once, like mpv itself does after a hwdec change.
    private func resumeAfterSwitch() {
        guard state.hasSource, !state.ended else { return }
        core.command(["seek", String(format: "%.3f", max(0, state.position)), "absolute+exact"])
    }

    func pipRenderSize(width: Int, height: Int) {
        pipW = width; pipH = height
        if pipMode { updateFeedSize() }
    }

    private func metalReady() {
        guard awaitingMetal, !pipMode else { return }
        awaitingMetal = false
        container.samplesInFront(false)
        feed.flush()
        feed.prime()
    }

    /// The video's shape at the floating window's size: long edge 640...1280 pixels.
    private func updateFeedSize() {
        var dw = Double(videoParams["dw"]?.int ?? videoParams["w"]?.int ?? 0)
        var dh = Double(videoParams["dh"]?.int ?? videoParams["h"]?.int ?? 0)
        if dw <= 0 || dh <= 0 { dw = 16; dh = 9 }
        let wanted = Double(max(pipW, pipH))
        let long = min(1280, max(640, wanted > 0 ? wanted : 960))
        let (w, h) = dw >= dh ? (long, long * dh / dw) : (long * dw / dh, long)
        feed.setSize(width: Int(w.rounded()), height: Int(h.rounded()))
    }

    func stop() {
        core.command(["stop"])
        source = nil
        loading = false
        state = EngineState(speed: state.speed)
        trackList = []
        resetWatchState()
        tick?.invalidate(); tick = nil
        fire(.state)
    }

    func shutdown() {
        onEvent = nil
        onPiPFrame = nil
        tick?.invalidate(); tick = nil
        stopThumbs()
        thumbs.onFirstWant = nil
        if let o = memoryObserver { NotificationCenter.default.removeObserver(o); memoryObserver = nil }
        surface.layerHDR.onChange = nil
        // The render context must go before the mpv handle; freeing it while vo=libmpv still uses it (player
        // closed from the floating window) waits for mpv's core, so it happens on mpv's queue, never on main.
        let feed = self.feed
        core.shutdown { feed.detach() }
    }

    // MARK: Events

    private func handle(_ events: [MPVEvent]) {
        var changed = false
        for e in events {
            switch e {
            case .property(let name, let value):
                changed = apply(name, value) || changed
            case .fileLoaded:
                loading = false
                Log.player.info("mpv: file loaded \(String(format: "%.2f", Date().timeIntervalSince(self.loadStartedAt)), privacy: .public) s after load (\(self.trackList.count) tracks)")
                fire(.tracks)
            case .playbackRestart:
                if !firstFrameSent && state.hasSource {
                    firstFrameSent = true
                    retries = 0
                    firstFrameAt = CACurrentMediaTime()
                    Log.player.info("mpv: playback started \(String(format: "%.2f", Date().timeIntervalSince(self.loadStartedAt)), privacy: .public) s after load")
                    fire(.firstFrame)
                    fire(.format)
                    startTick()
                } else if let p = pendingSeek {
                    pendingSeek = nil
                    Log.player.info("Seek: to \(Labels.time(p.target), privacy: .public) \(p.inside ? "inside the buffer" : "outside the buffer", privacy: .public), picture after \(String(format: "%.2f", CACurrentMediaTime() - p.at), privacy: .public) s")
                }
            case .propertyReply(let tag, let value):
                if tag == MPVCore.tagCacheState { readCacheState(value) }
            case .endFile(let reason, let error):
                endFile(reason: reason, error: error)
            case .log(let level, let prefix, let text):
                if let r = text.range(of: #"HTTP error (\d{3})"#, options: .regularExpression) {
                    lastHTTPStatus = Int(text[r].suffix(3))
                }
                // Never the stream URL itself: hosts and messages only.
                Log.player.info("mpv[\(prefix, privacy: .public)] \(level, privacy: .public): \(Self.redact(text), privacy: .public)")
            case .commandReply(let tag, let error):
                if error < 0 {
                    Log.player.error("mpv: command failed (tag \(tag, privacy: .public)): \(String(cString: mpv_error_string(error)), privacy: .public)")
                    if tag == MPVCore.tagPiPSwitch, pipMode {
                        // mpv refused the software output: no floating picture possible; back to the Metal layer.
                        Log.player.error("PiP: vo switch refused, giving up")
                        pipDidStop()
                    }
                } else if tag == MPVCore.tagPiPSwitch {
                    Log.player.info("PiP: vo switch applied (software frames: \(self.pipMode, privacy: .public))")
                }
            case .shutdown:
                break
            }
        }
        if changed { fire(.state) }
        if pipMode { feed.syncClock(position: state.position, rate: state.isPlaying ? state.speed : 0) }
    }

    private func apply(_ name: String, _ v: MPVValue) -> Bool {
        switch name {
        case "pause":
            state.paused = v.bool ?? state.paused
            return refreshPlaying()
        case "paused-for-cache", "seeking", "core-idle":
            switch name {
            case "paused-for-cache":
                cacheWait = v.bool ?? false
                noteStall(cacheWait)
            case "seeking": seeking = v.bool ?? false
            default: idle = v.bool ?? false
            }
            return refreshPlaying()
        case "time-pos":
            if let t = v.double {
                state.position = t
                let now = CACurrentMediaTime()
                if state.isPlaying && now - lastProgress >= 0.45 { lastProgress = now; fire(.progress(t)) }
            }
            return false
        case "duration":
            state.duration = v.double ?? 0
            return true
        case "demuxer-cache-time":
            state.buffered = v.double ?? 0
            return false
        case "eof-reached":
            if v.bool == true, state.hasSource, !state.ended {
                state.ended = true
                _ = refreshPlaying()
                fire(.ended)
            }
            return true
        case "track-list":
            if case .array(let a) = v { trackList = a } else { trackList = [] }
            fire(.tracks)
            fire(.format)
            return false
        case "video-params":
            if case .map(let m) = v { videoParams = m } else { videoParams = [:] }
            if pipMode { updateFeedSize() }
            noteHDRStream()
            fire(.format)
            return false
        case "vo-configured":
            if v.bool == true, awaitingMetal {
                // gpu-next is up; give it a moment to draw its first picture, then bring the Metal layer forward.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
                    MainActor.assumeIsolated { self?.metalReady() }
                }
            }
            return false
        case "audio-params":
            if case .map(let m) = v { audioParams = m } else { audioParams = [:] }
            fire(.format)
            return false
        case "video-codec":
            videoCodec = v.string ?? ""
            fire(.format)
            return false
        case "audio-codec-name":
            audioCodec = v.string ?? ""
            fire(.format)
            return false
        case "speed":
            state.speed = v.double ?? state.speed
            return false
        default:
            return false
        }
    }

    private var cacheWait = false
    private var seeking = false
    private var idle = true

    private func refreshPlaying() -> Bool {
        let before = (state.isPlaying, state.isBuffering)
        let buffering = state.hasSource && !state.ended && (loading || cacheWait || (seeking && !state.paused))
        state.isBuffering = buffering
        state.isPlaying = state.hasSource && !state.paused && !state.ended && !buffering && !idle
        return before != (state.isPlaying, state.isBuffering)
    }

    private func endFile(reason: Int32, error: Int32) {
        guard reason == Int32(MPV_END_FILE_REASON_ERROR.rawValue), state.hasSource else { return }
        let http = lastHTTPStatus
        Log.player.error("mpv: end-file error \(error, privacy: .public) (\(String(cString: mpv_error_string(error)), privacy: .public)), http \(http ?? 0, privacy: .public)")
        // Transient network trouble: try again a few times from the same spot (ExoEngine's retry policy).
        let transient = http.map { $0 >= 500 || $0 == 408 || $0 == 429 } ?? (error == MPV_ERROR_LOADING_FAILED.rawValue)
        if transient, retries < 4, let src = source {
            retries += 1
            let at = state.position
            let wasPaused = state.paused
            let n = retries
            Log.player.info("mpv: retry \(n, privacy: .public)/4")
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5 * Double(n)) { [weak self] in
                MainActor.assumeIsolated {
                    guard let self, self.source == src else { return }
                    let r = self.retries
                    self.startFile(src, start: at, autoplay: !wasPaused)
                    self.retries = r
                }
            }
            return
        }
        let msg: String
        switch http {
        case 401, 403: msg = "The source refused the stream. The link may have expired."
        case 404, 410: msg = "The file is no longer available at this source."
        case .some: msg = "This source stopped answering. Try another one."
        case nil:
            switch error {
            case MPV_ERROR_UNKNOWN_FORMAT.rawValue: msg = "This file format isn't supported."
            case MPV_ERROR_NOTHING_TO_PLAY.rawValue: msg = "This file is damaged or incomplete."
            case MPV_ERROR_LOADING_FAILED.rawValue: msg = "Couldn't reach the stream. Check your connection, or try another source."
            default: msg = "The video couldn't be decoded."
            }
        }
        loading = false
        state.error = msg
        _ = refreshPlaying()
        fire(.error(msg))
    }

    private func fire(_ e: EngineEvent) { onEvent?(e) }

    // MARK: Buffer (sized to this iPhone, docs/port/player.md "Watching upgrades")

    private func applyBufferPlan() {
        let p = BufferPlan.current()
        // Live properties: mpv's demuxer picks new limits up at once, and the next file starts with them.
        core.set("demuxer-max-bytes", p.aheadOption)
        core.set("demuxer-max-back-bytes", p.behindOption)
        core.set("cache-secs", "\(p.aheadSeconds)")
        core.set("demuxer-readahead-secs", "\(p.aheadSeconds)")
        if p != plan { Log.player.info("\(p.logLine, privacy: .public)") }
        plan = p
    }

    /// A new file (or none): buffer readings, seek timing and the scrub previews start over.
    private func resetWatchState() {
        cacheAheadBytes = 0; cacheTotalBytes = 0; cacheInputRate = 0
        cacheRanges = []; cachedToEnd = false
        pendingSeek = nil
        stallStarted = nil; stalls = 0; lastStallAt = nil
        hdrLogged = false
        stopThumbs()
        thumbs.reset()
        thumbsTried = false
        stableTicks = 0
    }

    private func startTick() {
        tick?.invalidate()
        let t = Timer(timeInterval: 2, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.onTick() }
        }
        t.tolerance = 0.4
        RunLoop.main.add(t, forMode: .common)
        tick = t
    }

    private func onTick() {
        guard state.hasSource else { return }
        // Read without waiting: the reply arrives as an event (never blocks the main thread on mpv's core).
        core.getAsync("demuxer-cache-state", tag: MPVCore.tagCacheState)
        updateThumbGate()
        maybeStartThumbs()
    }

    private func readCacheState(_ v: MPVValue) {
        guard case .map(let m) = v else { return }
        cacheAheadBytes = m["fw-bytes"]?.int ?? 0
        cacheTotalBytes = m["total-bytes"]?.int ?? 0
        cacheInputRate = m["raw-input-rate"]?.int ?? 0
        cachedToEnd = m["eof-cached"]?.bool ?? (m["eof"]?.bool ?? false)
        var ranges: [(Double, Double)] = []
        if case .array(let a)? = m["seekable-ranges"] {
            for r in a {
                if case .map(let rm) = r, let s = rm["start"]?.double, let e = rm["end"]?.double { ranges.append((s, e)) }
            }
        }
        cacheRanges = ranges
        let now = CACurrentMediaTime()
        if state.isPlaying, now - lastBufferLog >= 30 {
            lastBufferLog = now
            let ahead = max(0, (m["cache-end"]?.double ?? state.buffered) - state.position)
            let mb = 1 << 20
            let behind = max(0, cacheTotalBytes - cacheAheadBytes)
            Log.player.info("Buffer: \(Labels.time(ahead), privacy: .public) ahead (\(self.cacheAheadBytes / mb, privacy: .public) of \((self.plan?.aheadBytes ?? 0) / mb, privacy: .public) MB), \(behind / mb, privacy: .public) MB behind, \(ranges.count, privacy: .public) jump range(s), reading \(String(format: "%.1f", Double(self.cacheInputRate) / Double(mb)), privacy: .public) MB/s\(self.cachedToEnd ? ", whole rest loaded" : "", privacy: .public), stalls \(self.stalls, privacy: .public)")
        }
    }

    /// The spot is inside what mpv already holds (a jump there needs no network).
    private func isBuffered(_ t: Double) -> Bool {
        if cacheRanges.contains(where: { t >= $0.0 && t <= $0.1 }) { return true }
        return t >= state.position && t <= state.buffered
    }

    private func noteStall(_ waiting: Bool) {
        guard firstFrameSent else { return }
        let now = CACurrentMediaTime()
        if waiting, stallStarted == nil, pendingSeek == nil {
            stallStarted = now
            lastStallAt = now
            stalls += 1
            Log.player.info("Buffer: stalled at \(Labels.time(self.state.position), privacy: .public) (reading \(String(format: "%.1f", Double(self.cacheInputRate) / 1_048_576), privacy: .public) MB/s)")
        } else if !waiting, let s = stallStarted {
            stallStarted = nil
            Log.player.info("Buffer: playing again after \(String(format: "%.1f", now - s), privacy: .public) s")
        }
    }

    // MARK: HDR / Dolby Vision

    /// The video track's Dolby Vision profile (5, 7, 8...), nil when it has none.
    private var dvProfile: Int? {
        selectedTrack("video")?["dolby-vision-profile"]?.int
    }

    /// Once per stream: what HDR arrived, and its peak for the screen's tone mapping.
    private func noteHDRStream() {
        let gamma = videoParams["gamma"]?.string ?? ""
        guard gamma == "pq" || gamma == "hlg" else { return }
        let cll = videoParams["max-cll"]?.double ?? 0
        let luma = videoParams["max-luma"]?.double ?? 0
        let peak = cll > 0 ? cll : (luma > 0 ? luma : 1000)
        surface.layerHDR.contentPeak = Float(min(10000, max(400, peak)))
        guard !hdrLogged else { return }
        hdrLogged = true
        let matrix = videoParams["colormatrix"]?.string ?? "?"
        var dv = ""
        if let p = dvProfile {
            // libplacebo applies Dolby Vision's colour data (its "RPU") when it reaches the frames; mpv then reports
            // the dolbyvision matrix. Profile 5 without it would show green and purple.
            let carried = matrix == "dolbyvision"
            dv = ", Dolby Vision profile \(p) (colour data \(carried ? "applied" : "missing"))"
            if p == 5 && !carried { Log.player.error("HDR: Dolby Vision profile 5 arrived without its colour data; the picture may look wrong") }
        }
        Log.player.info("HDR: stream \(gamma, privacy: .public) / \(self.videoParams["primaries"]?.string ?? "?", privacy: .public), peak \(Int(peak), privacy: .public) nits\(dv, privacy: .public); \(self.hdrOutput ? "HDR picture on this screen" : "toned down to SDR", privacy: .public)")
    }

    /// mpv's output (MoltenVK) set the screen layer's colours (main queue): log it, and for a linear EDR picture
    /// tell mpv how bright the screen can go (PQ and HLG carry their own levels).
    private func pictureColorspaceChanged(_ kind: MPVMetalLayer.HDRKind, name: String, format: UInt) {
        let headroom = Double(UIScreen.main.currentEDRHeadroom)
        if edrOn != (kind != .sdr) { edrOn = kind != .sdr; fire(.format) }
        let key = "\(name) \(format) \(kind)"
        guard key != lastPicture else { return }
        lastPicture = key
        Log.player.info("HDR: picture now \(name, privacy: .public) (format \(format, privacy: .public)), \(kind == .sdr ? "normal range" : "EDR on", privacy: .public), headroom now \(String(format: "%.1f", headroom), privacy: .public) of \(String(format: "%.1f", Self.edrHeadroom()), privacy: .public)")
        // Extended linear: 1.0 = normal white (203 nits in libplacebo); peak = the screen's headroom. Only set on a
        // real change: a new value re-hints the output, which may rebuild the swapchain and call back here.
        let peak = kind == .linear ? "\(Int(203 * max(1, Self.edrHeadroom())))" : "auto"
        if peak != targetPeak {
            targetPeak = peak
            core.set("target-peak", peak)
        }
    }
    private var targetPeak = "auto"
    /// The screen layer is in an HDR colour space with EDR on (MPVMetalLayer).
    private var edrOn = false
    /// What the badge and Info say: the screen shows this HDR stream in HDR right now.
    var hdrOnScreen: Bool {
        let g = videoParams["gamma"]?.string ?? ""
        return edrOn && !pipMode && (g == "pq" || g == "hlg")
    }
    private var lastPicture = ""

    // MARK: Scrub previews

    /// The main picture's health decides how much the previews decoders may do: nothing while it loads, seeks,
    /// buffers or is away; only the thumb's spots with little buffered or for 30 s after a stall; also the walk once
    /// 12 s are buffered (or the rest of the file is in).
    private func thumbGate() -> ThumbDesk.Gate {
        guard state.hasSource, firstFrameSent, videoOn, !pipMode, !loading, !state.isBuffering, !seeking else { return .closed }
        if let s = lastStallAt, CACurrentMediaTime() - s < 30 { return .wants }
        let ahead = state.buffered - state.position
        return cachedToEnd || ahead >= 12 ? .open : .wants
    }

    private func updateThumbGate() {
        thumbDesk?.setGate(thumbGate())
    }

    /// Starts the previews once playback is steady (2 healthy ticks, at least 5 s after the first frame).
    private func maybeStartThumbs() {
        guard thumbDesk == nil, !thumbsTried, firstFrameSent else { return }
        stableTicks = thumbGate() == .open ? stableTicks + 1 : 0
        if stableTicks >= 2, CACurrentMediaTime() - firstFrameAt >= 5 { startThumbs(early: false) }
    }

    /// The viewer started dragging before the previews ran: start now if the picture is up and not buffering.
    private func thumbsWanted() {
        guard thumbDesk == nil, !thumbsTried, firstFrameSent, CACurrentMediaTime() - firstFrameAt >= 2,
              thumbGate() != .closed else { return }
        startThumbs(early: true)
    }

    private func startThumbs(early: Bool) {
        thumbsTried = true
        guard let src = source else { return }
        let d = state.duration
        guard src.audioURL == nil, d >= 300 else { Log.player.info("Thumbs: off (short clip or trailer)"); return }
        if let p = dvProfile, p != 7 && p != 8 {
            // Profile 5 has no normal picture underneath; without the main picture's processing it is green and purple.
            Log.player.info("Thumbs: off for Dolby Vision profile \(p, privacy: .public) (no normal picture underneath)")
            return
        }
        if dvProfile == nil, videoParams["colormatrix"]?.string == "dolbyvision" {
            Log.player.info("Thumbs: off (Dolby Vision of unknown profile)")
            return
        }
        let mb = 1 << 20
        let free = Int(os_proc_available_memory())
        if free > 0, free < 450 * mb { Log.player.info("Thumbs: off (\(free / mb, privacy: .public) MB free)"); return }
        let phys = Int(ProcessInfo.processInfo.physicalMemory)
        // Frames stay in memory, sized to the phone like the art memory: a 4 GB iPhone ~32 MB, 6 GB and up 48 MB.
        let width = 288, frameBytes = 288 * 162 * 4
        let budget = min(48 * mb, phys / 128)
        let cap = max(40, budget / frameBytes)
        let interval = max(8, d / Double(cap))
        let grid = Int((d / interval).rounded(.up))
        let dw = Double(videoParams["dw"]?.int ?? 16), dh = Double(videoParams["dh"]?.int ?? 9)
        thumbs.prepare(grid: grid, interval: interval, aspect: dw > 0 && dh > 0 ? CGFloat(dw / dh) : 16 / 9)
        let unmetered = NetPath.shared.unmetered
        // Metered: the coarse pass only (about one frame per 1.5 % of the runtime), then only where the viewer drags.
        let near = min(grid - 1, max(0, Int(state.position / interval)))
        let desk = ThumbDesk(grid: grid, near: near, walkLimit: unmetered ? grid : min(grid, 64))
        // As many decoders as the phone has room for: drag decoders follow only the thumb (never stuck behind the walk).
        let gb = 1 << 30
        let roomy = phys >= 6 * gb && (free <= 0 || free >= 1200 * mb)
        let some = phys >= 4 * gb && (free <= 0 || free >= 800 * mb)
        let roles: [ThumbDesk.Role] = roomy ? [.drag, .drag, .walk] : some ? [.drag, .walk] : [.both]
        let ua = src.headers.first { $0.key.lowercased() == "user-agent" }?.value ?? "DreamOS/0.1 (iOS) DreamPlayer"
        let cfg = MPVThumbnailer.Config(
            url: src.url, headers: src.headers.filter { $0.key.lowercased() != "user-agent" }, userAgent: ua,
            duration: d, interval: interval, width: width,
            // Dolby Vision 7 / 8 carry a normal HDR10 picture: decode that one, without the Dolby Vision data.
            filter: dvProfile != nil ? "format=colormatrix=bt.2020-ncl:dolbyvision=no" : nil)
        let thumbs = self.thumbs
        var workers: [MPVThumbnailer] = []
        for (n, role) in roles.enumerated() {
            let name = role == .walk ? "walk" : role == .drag ? "drag\(n + 1)" : "both"
            let w = MPVThumbnailer(cfg, desk: desk, role: role, name: name)
            w.onFrame = { i, img, t, aspect in
                let box = ThumbBox(image: img)
                DispatchQueue.main.async {
                    MainActor.assumeIsolated {
                        thumbs.setAspect(CGFloat(aspect))
                        thumbs.add(i, box.image, time: t)
                    }
                }
            }
            w.onEnd = { [weak self, weak w] reason in
                DispatchQueue.main.async {
                    MainActor.assumeIsolated {
                        Log.player.info("Thumbs: stopped (\(reason, privacy: .public))")
                        guard let self, let w else { return }
                        self.thumbWorkers.removeAll { $0 === w }
                        if self.thumbWorkers.isEmpty, self.thumbDesk === desk { self.stopThumbs() }
                    }
                }
            }
            workers.append(w)
        }
        thumbs.onWant = { [weak desk] i, next in desk?.want(i, next: next) }
        thumbDesk = desk
        thumbWorkers = workers
        desk.setGate(thumbGate())
        for w in workers { w.start() }
        Log.player.info("Thumbs: starting\(early ? " (viewer dragging)" : "", privacy: .public) \(Int(CACurrentMediaTime() - self.firstFrameAt), privacy: .public) s after the first frame, \(grid, privacy: .public) spots every \(Int(interval), privacy: .public) s, decoders \(roles.count, privacy: .public) (\(roles.map { "\($0)" }.joined(separator: ", ")), privacy: .public), walk \(unmetered ? "whole timeline" : "coarse pass only (metered network)", privacy: .public)")
    }

    private func stopThumbs() {
        thumbDesk?.cancel()
        thumbDesk = nil
        thumbWorkers = []
        thumbs.onWant = nil
    }


    /// Log lines may quote the stream URL: cut every http(s) URL down to its host.
    static func redact(_ s: String) -> String {
        guard s.contains("http") else { return s }
        var out = s
        while let r = out.range(of: #"https?://[^\s'"]+"#, options: .regularExpression) {
            let host = URL(string: String(out[r]))?.host() ?? "link"
            out.replaceSubrange(r, with: "<\(host)>")
        }
        return out
    }

    // MARK: Names

    static func videoCodecName(_ c: String) -> String {
        if c.contains("hevc") || c.contains("h265") { return "HEVC" }
        if c.contains("h264") || c.contains("avc") { return "H.264" }
        if c.contains("vp9") { return "VP9" }
        if c.contains("av1") { return "AV1" }
        if c.contains("mpeg2") { return "MPEG-2" }
        if c.contains("vp8") { return "VP8" }
        return c.split(separator: " ").first.map { $0.uppercased() } ?? c.uppercased()
    }

    static func audioCodecLong(_ c: String, profile: String, title: String) -> String? {
        let p = profile.lowercased(), t = title.lowercased()
        switch c.lowercased() {
        case "": return nil
        case "ac3": return "Dolby Digital"
        case "eac3": return p.contains("atmos") || p.contains("joc") ? "Dolby Atmos" : "Dolby Digital Plus"
        case "ac4": return "Dolby AC-4"
        case "truehd", "mlp": return "Dolby TrueHD"
        case "dts", "dca":
            if p.contains("dts:x") || t.contains("dts:x") || t.contains("dts-x") { return "DTS:X" }
            if p.contains("express") { return "DTS Express" }
            if p.contains("hd") || p.contains("ma") { return "DTS-HD" }
            return "DTS"
        case "aac": return "AAC"
        case "flac": return "FLAC"
        case "opus": return "Opus"
        case "vorbis": return "Vorbis"
        case "mp3", "mp2": return "MP3"
        default: return c.hasPrefix("pcm") ? "PCM" : c.uppercased()
        }
    }

    static func audioCodecShort(_ c: String, profile: String) -> String? {
        switch c.lowercased() {
        case "ac3": return "AC3"
        case "eac3": return profile.lowercased().contains("atmos") ? "E-AC3 Atmos" : "E-AC3"
        case "truehd", "mlp": return "TrueHD"
        default: return audioCodecLong(c, profile: profile, title: "")
        }
    }
}

extension SubtitleStyle {
    /// Bottom margin as a fraction of the picture height (PlayerPrefs positions; raised above the controls).
    var marginFraction: Double { raised ? max(position, 0.30) : position }
}

// MARK: - The mpv handle and its event pump (off the main thread)

/// A Sendable mpv value (the parts of mpv_node Dream Player reads).
enum MPVValue: Sendable {
    case none
    case flag(Bool)
    case int(Int)
    case double(Double)
    case string(String)
    case array([MPVValue])
    case map([String: MPVValue])

    var bool: Bool? { if case .flag(let b) = self { return b }; return nil }
    var int: Int? {
        switch self { case .int(let i): return i; case .double(let d): return Int(d); default: return nil }
    }
    var double: Double? {
        switch self { case .double(let d): return d; case .int(let i): return Double(i); default: return nil }
    }
    var string: String? { if case .string(let s) = self { return s }; return nil }

    init(node: mpv_node) {
        switch node.format {
        case MPV_FORMAT_FLAG: self = .flag(node.u.flag != 0)
        case MPV_FORMAT_INT64: self = .int(Int(node.u.int64))
        case MPV_FORMAT_DOUBLE: self = .double(node.u.double_)
        case MPV_FORMAT_STRING: self = node.u.string.map { .string(String(cString: $0)) } ?? .none
        case MPV_FORMAT_NODE_ARRAY:
            guard let list = node.u.list?.pointee else { self = .array([]); return }
            self = .array((0..<Int(list.num)).map { MPVValue(node: list.values[$0]) })
        case MPV_FORMAT_NODE_MAP:
            guard let list = node.u.list?.pointee else { self = .map([:]); return }
            var m: [String: MPVValue] = [:]
            for i in 0..<Int(list.num) {
                if let k = list.keys?[i] { m[String(cString: k)] = MPVValue(node: list.values[i]) }
            }
            self = .map(m)
        default: self = .none
        }
    }
}

enum MPVEvent: Sendable {
    case property(String, MPVValue)
    case fileLoaded
    case playbackRestart
    case endFile(reason: Int32, error: Int32)
    case log(String, String, String)
    /// An asynchronous command finished (only tagged ones and failures are reported).
    case commandReply(tag: UInt64, error: Int32)
    /// An asynchronous property read came back (`MPVCore.getAsync`).
    case propertyReply(tag: UInt64, value: MPVValue)
    case shutdown
}

/// Owns the mpv handle. Commands go straight to mpv (its API is thread-safe); events are read on a serial
/// queue and handed to `sink` in batches.
final class MPVCore: @unchecked Sendable {
    private var handle: OpaquePointer?
    private let queue = DispatchQueue(label: "dreamos.mpv", qos: .userInitiated)
    var sink: (@Sendable ([MPVEvent]) -> Void)?

    /// `hdr`: the screen shows HDR, so HDR streams get an HDR picture (mpv tells the output what the stream is,
    /// libplacebo picks an HDR10 / extended range swapchain and MoltenVK sets the layer's colours; the layer
    /// turns EDR on, MPVMetalLayer). If the output can't, libplacebo keeps today's SDR picture by itself.
    func start(layer: CAMetalLayer, hdr: Bool = false) {
        guard let h = mpv_create() else { Log.player.error("mpv: create failed"); return }
        handle = h
        var wid = Int64(Int(bitPattern: Unmanaged.passUnretained(layer).toOpaque()))
        mpv_set_option(h, "wid", MPV_FORMAT_INT64, &wid)
        let options: [(String, String)] = [
            ("target-colorspace-hint", hdr ? "yes" : "no"),
            ("vo", "gpu-next"), ("gpu-api", "vulkan"), ("gpu-context", "moltenvk"),
            // VideoToolbox straight into gpu-next; with the Picture in Picture software renderer (no GPU interop)
            // mpv falls through to the copying variant, still decoded by VideoToolbox.
            ("hwdec", "videotoolbox,videotoolbox-copy"),
            // VP9 isn't decoded by VideoToolbox here: trying it first cost ~4 s before the software fallback.
            ("hwdec-codecs", "h264,hevc,mpeg1video,mpeg2video,mpeg4,av1,prores"),
            ("video-rotate", "no"), ("keep-open", "yes"), ("idle", "yes"), ("input-default-bindings", "no"),
            ("input-vo-keyboard", "no"), ("osc", "no"), ("osd-level", "0"), ("config", "no"), ("load-scripts", "no"),
            ("ytdl", "no"), ("sub-auto", "no"), ("audio-file-auto", "no"), ("terminal", "no"),
            // Debrid streams: start quickly, then read far ahead. The byte limits are sized to the phone per load
            // (BufferPlan, MPVEngine.applyBufferPlan); these are only the floor until the first load sets them.
            // The seekable cache keeps everything loaded jumpable (back buffer included), so those seeks are instant.
            ("cache", "yes"), ("cache-pause-initial", "no"), ("cache-pause-wait", "3"),
            ("demuxer-readahead-secs", "120"), ("demuxer-max-bytes", "192MiB"), ("demuxer-max-back-bytes", "48MiB"),
            ("demuxer-seekable-cache", "yes"), ("demuxer-donate-buffer", "yes"),
            ("network-timeout", "20"), ("force-seekable", "yes"),
            ("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=4"),
            ("volume-max", "400"), ("hr-seek", "yes"),
            // Subtitles: the viewer's style wins over what the file asks for.
            ("sub-ass-override", "force"), ("sub-font", "Helvetica Neue"), ("sub-font-size", "46"),
            ("sub-bold", "no"), ("sub-use-margins", "no"), ("subs-fallback", "yes"),
            ("alang", "en,eng"), ("audio-channels", "auto-safe"),
        ]
        for (k, v) in options {
            let r = mpv_set_option_string(h, k, v)
            if r < 0 { Log.player.error("mpv: option \(k, privacy: .public) failed: \(String(cString: mpv_error_string(r)), privacy: .public)") }
        }
        mpv_request_log_messages(h, "warn")
        let r = mpv_initialize(h)
        if r < 0 { Log.player.error("mpv: initialize failed: \(String(cString: mpv_error_string(r)), privacy: .public)") }
        let props: [(String, mpv_format)] = [
            ("pause", MPV_FORMAT_FLAG), ("paused-for-cache", MPV_FORMAT_FLAG), ("seeking", MPV_FORMAT_FLAG),
            ("core-idle", MPV_FORMAT_FLAG), ("eof-reached", MPV_FORMAT_FLAG), ("time-pos", MPV_FORMAT_DOUBLE),
            ("duration", MPV_FORMAT_DOUBLE), ("demuxer-cache-time", MPV_FORMAT_DOUBLE), ("speed", MPV_FORMAT_DOUBLE),
            ("track-list", MPV_FORMAT_NODE), ("video-params", MPV_FORMAT_NODE), ("audio-params", MPV_FORMAT_NODE),
            ("video-codec", MPV_FORMAT_STRING), ("audio-codec-name", MPV_FORMAT_STRING),
            ("vo-configured", MPV_FORMAT_FLAG),
        ]
        for (name, fmt) in props { mpv_observe_property(h, 0, name, fmt) }
        mpv_set_wakeup_callback(h, { ctx in
            guard let ctx else { return }
            Unmanaged<MPVCore>.fromOpaque(ctx).takeUnretainedValue().wake()
        }, Unmanaged.passUnretained(self).toOpaque())
    }

    /// The raw handle for the render API (nil after shutdown).
    func withHandle<T>(_ f: (OpaquePointer) -> T) -> T? {
        guard let h = handle else { return nil }
        return f(h)
    }

    func set(_ name: String, _ value: String) {
        guard let h = handle else { return }
        let r = mpv_set_property_string(h, name, value)
        if r < 0 { Log.player.error("mpv: set \(name, privacy: .public) failed: \(String(cString: mpv_error_string(r)), privacy: .public)") }
    }

    /// Reply tag of the Picture in Picture `vo` switches (`MPVEvent.commandReply`).
    static let tagPiPSwitch: UInt64 = 71
    /// Reply tag of the buffer readings (`demuxer-cache-state`).
    static let tagCacheState: UInt64 = 72

    /// Reads a property without waiting; the value comes back as `MPVEvent.propertyReply(tag:)`.
    func getAsync(_ name: String, tag: UInt64) {
        guard let h = handle else { return }
        _ = mpv_get_property_async(h, tag, name, MPV_FORMAT_NODE)
    }

    /// Runs a command asynchronously (never waits for mpv's core). `tag` comes back in the reply event.
    func command(_ args: [String], tag: UInt64 = 0) {
        guard let h = handle else { return }
        var cargs: [UnsafePointer<CChar>?] = args.map { UnsafePointer(strdup($0)) }
        cargs.append(nil)
        defer { for p in cargs { free(UnsafeMutablePointer(mutating: p)) } }
        let r = cargs.withUnsafeMutableBufferPointer { mpv_command_async(h, tag, $0.baseAddress) }
        if r < 0 { Log.player.error("mpv: \(args.first ?? "", privacy: .public) failed: \(String(cString: mpv_error_string(r)), privacy: .public)") }
    }

    private func wake() { queue.async { [weak self] in self?.drain() } }

    private func drain() {
        guard let h = handle else { return }
        var batch: [MPVEvent] = []
        while true {
            guard let ev = mpv_wait_event(h, 0)?.pointee, ev.event_id != MPV_EVENT_NONE else { break }
            switch ev.event_id {
            case MPV_EVENT_PROPERTY_CHANGE:
                guard let p = ev.data?.assumingMemoryBound(to: mpv_event_property.self).pointee else { continue }
                let name = String(cString: p.name)
                var v = MPVValue.none
                if let d = p.data {
                    switch p.format {
                    case MPV_FORMAT_FLAG: v = .flag(d.assumingMemoryBound(to: Int32.self).pointee != 0)
                    case MPV_FORMAT_DOUBLE: v = .double(d.assumingMemoryBound(to: Double.self).pointee)
                    case MPV_FORMAT_INT64: v = .int(Int(d.assumingMemoryBound(to: Int64.self).pointee))
                    case MPV_FORMAT_STRING:
                        if let s = d.assumingMemoryBound(to: UnsafePointer<CChar>?.self).pointee { v = .string(String(cString: s)) }
                    case MPV_FORMAT_NODE: v = MPVValue(node: d.assumingMemoryBound(to: mpv_node.self).pointee)
                    default: break
                    }
                }
                batch.append(.property(name, v))
            case MPV_EVENT_FILE_LOADED: batch.append(.fileLoaded)
            case MPV_EVENT_PLAYBACK_RESTART: batch.append(.playbackRestart)
            case MPV_EVENT_END_FILE:
                if let e = ev.data?.assumingMemoryBound(to: mpv_event_end_file.self).pointee {
                    batch.append(.endFile(reason: Int32(e.reason.rawValue), error: e.error))
                }
            case MPV_EVENT_LOG_MESSAGE:
                if let m = ev.data?.assumingMemoryBound(to: mpv_event_log_message.self).pointee {
                    let text = String(cString: m.text).trimmingCharacters(in: .whitespacesAndNewlines)
                    batch.append(.log(String(cString: m.level), String(cString: m.prefix), text))
                }
            case MPV_EVENT_GET_PROPERTY_REPLY:
                guard ev.error >= 0, let p = ev.data?.assumingMemoryBound(to: mpv_event_property.self).pointee,
                      p.format == MPV_FORMAT_NODE, let d = p.data else { continue }
                batch.append(.propertyReply(tag: ev.reply_userdata, value: MPVValue(node: d.assumingMemoryBound(to: mpv_node.self).pointee)))
            case MPV_EVENT_COMMAND_REPLY:
                if ev.error < 0 || ev.reply_userdata != 0 { batch.append(.commandReply(tag: ev.reply_userdata, error: ev.error)) }
            case MPV_EVENT_SHUTDOWN: batch.append(.shutdown)
            default: break
            }
        }
        if !batch.isEmpty { sink?(batch) }
    }

    /// Destroys the handle on the event queue; `before` runs there first (render context teardown).
    func shutdown(before: (@Sendable () -> Void)? = nil) {
        guard let h = handle else { return }
        // The wakeup callback holds an unretained pointer to this object: detach it before anything else.
        mpv_set_wakeup_callback(h, nil, nil)
        handle = nil
        sink = nil
        let box = HandleBox(h)
        queue.async {
            before?()
            mpv_terminate_destroy(box.h)
        }
    }
}

private struct HandleBox: @unchecked Sendable {
    let h: OpaquePointer
    init(_ h: OpaquePointer) { self.h = h }
}

// MARK: - Surface

/// MPVKit's MetalLayer workaround: MoltenVK sets a 1x1 drawable to force presentation; ignore it.
///
/// HDR: when libplacebo picks an HDR swapchain, MoltenVK sets this layer's colour space (PQ, HLG or extended
/// linear) from mpv's video thread, but on iOS it never turns EDR on (its EDR switch is macOS-only, checked in
/// MoltenVK 1.4.2's binary) and never gives the system the content's levels. So the colour space setter does
/// it here, in the same call, before the first HDR frame is shown: EDR on plus HDR10 / HLG metadata, so iOS
/// tone maps to what this screen can show right now. Any SDR colour space turns it all off again.
final class MPVMetalLayer: CAMetalLayer {
    enum HDRKind: Sendable { case sdr, pq, hlg, linear }

    let hdr = LayerHDR()

    override var drawableSize: CGSize {
        get { super.drawableSize }
        set { if Int(newValue.width) > 1 && Int(newValue.height) > 1 { super.drawableSize = newValue } }
    }

    override var colorspace: CGColorSpace? {
        get { super.colorspace }
        set {
            super.colorspace = newValue
            applyEDR(newValue, notify: true)
        }
    }

    /// The content's peak changed (main queue): refresh the levels the system tone maps with.
    func refreshEDR() { applyEDR(super.colorspace, notify: false) }

    static func kind(of name: String) -> HDRKind {
        if name.contains("PQ") { return .pq }
        if name.contains("HLG") { return .hlg }
        if name.contains("Extended") { return .linear }
        return .sdr
    }

    private func applyEDR(_ cs: CGColorSpace?, notify: Bool) {
        let name = (cs?.name as String?) ?? "none"
        let kind = Self.kind(of: name)
        let s = hdr.snapshot
        let on = s.allowed && kind != .sdr
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        wantsExtendedDynamicRangeContent = on
        switch on ? kind : .sdr {
        case .pq: edrMetadata = CAEDRMetadata.hdr10(minLuminance: 0.005, maxLuminance: s.peak, opticalOutputScale: 100)
        case .hlg: edrMetadata = CAEDRMetadata.hlg
        default: edrMetadata = nil
        }
        CATransaction.commit()
        if notify, let cb = s.onChange {
            let short = name.replacingOccurrences(of: "kCGColorSpace", with: "")
            cb(on ? kind : .sdr, short, pixelFormat.rawValue)
        }
    }
}

/// The screen layer's HDR settings: written on the main queue, read on mpv's video thread.
final class LayerHDR: @unchecked Sendable {
    struct Snapshot {
        var allowed: Bool
        var peak: Float
        var onChange: (@Sendable (MPVMetalLayer.HDRKind, String, UInt) -> Void)?
    }
    private let lock = NSLock()
    private var _allowed = false
    private var _peak: Float = 1000
    private var _onChange: (@Sendable (MPVMetalLayer.HDRKind, String, UInt) -> Void)?

    var snapshot: Snapshot { lock.withLock { Snapshot(allowed: _allowed, peak: _peak, onChange: _onChange) } }
    /// The screen shows HDR and the owner's switch allows it.
    var allowed: Bool {
        get { lock.withLock { _allowed } }
        set { lock.withLock { _allowed = newValue } }
    }
    /// The stream's brightest level in nits (MaxCLL, else mastering peak, else 1000).
    var contentPeak: Float {
        get { lock.withLock { _peak } }
        set {
            let changed: Bool = lock.withLock { let c = abs(_peak - newValue) > 1; _peak = newValue; return c }
            if changed { DispatchQueue.main.async { [weak self] in self?.layer?.refreshEDR() } }
        }
    }
    /// Told (on mpv's video thread) when the picture's colour space changes: kind, name, Metal pixel format.
    var onChange: (@Sendable (MPVMetalLayer.HDRKind, String, UInt) -> Void)? {
        get { lock.withLock { _onChange } }
        set { lock.withLock { _onChange = newValue } }
    }
    weak var layer: MPVMetalLayer?
}

/// The engine's view: the Metal surface in front, the Picture in Picture sample layer behind it (in the window,
/// so the system can float it). While the floating window runs, the sample layer comes to the front.
final class MPVContainerView: UIView {
    private weak var samples: UIView?
    private weak var metal: UIView?
    func install(samples s: UIView, metal m: UIView) {
        backgroundColor = .black
        isUserInteractionEnabled = false
        for v in [s, m] {
            v.frame = bounds
            v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            addSubview(v)
        }
        samples = s; metal = m
    }
    func samplesInFront(_ front: Bool) {
        guard let s = samples, let m = metal else { return }
        if front { bringSubviewToFront(s) } else { bringSubviewToFront(m) }
    }
}

final class MPVSurfaceView: UIView {
    override class var layerClass: AnyClass { MPVMetalLayer.self }
    var metalLayer: CAMetalLayer { layer as! CAMetalLayer }
    var layerHDR: LayerHDR { (layer as! MPVMetalLayer).hdr }
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        (layer as! MPVMetalLayer).hdr.layer = layer as? MPVMetalLayer
        metalLayer.framebufferOnly = true
        metalLayer.backgroundColor = UIColor.black.cgColor
        isUserInteractionEnabled = false
    }
    required init?(coder: NSCoder) { fatalError() }
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if let s = window?.screen { contentScaleFactor = s.nativeScale }
    }
}
#endif
