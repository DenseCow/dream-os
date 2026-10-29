import Foundation
import UIKit
import AVFoundation

// The seam between Dream Player's screen and what decodes the video (fire-home PlayerContract.kt `PlayerEngine`):
// libmpv (MPVEngine: MKV, HEVC, DTS, TrueHD, ASS subtitles).

/// What to play. `headers` are request headers (a URL may also carry them after '|', as on the Fire TV).
public struct EngineSource: Sendable, Hashable {
    public var url: URL
    public var headers: [String: String]
    /// External subtitle files to load with the stream.
    public var subtitles: [SubtitleFile]
    /// A separate audio stream (trailers: adaptive video + audio).
    public var audioURL: URL?
    public init(url: URL, headers: [String: String] = [:], subtitles: [SubtitleFile] = [], audioURL: URL? = nil) {
        self.url = url; self.headers = headers; self.subtitles = subtitles; self.audioURL = audioURL
    }

    /// "https://host/file.mkv|User-Agent=Foo%20Bar&Referer=..." -> the URL and its headers (StreamUrls.headers).
    public static func parse(_ raw: String) -> (URL?, [String: String]) {
        guard let bar = raw.firstIndex(of: "|") else { return (URL(string: raw), [:]) }
        let base = String(raw[..<bar])
        var out: [String: String] = [:]
        for pair in raw[raw.index(after: bar)...].split(separator: "&") {
            guard let eq = pair.firstIndex(of: "="), eq != pair.startIndex else { continue }
            let k = String(pair[..<eq]).removingPercentEncoding?.trimmingCharacters(in: .whitespaces) ?? ""
            let v = String(pair[pair.index(after: eq)...]).removingPercentEncoding?.trimmingCharacters(in: .whitespaces) ?? ""
            // Player options, not HTTP headers.
            if k.isEmpty || k.lowercased() == "verifypeer" || k.lowercased() == "seekable" || k.hasPrefix("!") { continue }
            out[k] = v
        }
        return (URL(string: base), out)
    }
}

/// An external subtitle file (PlayerContract.kt `SubtitleFile`). `online`: fetched by Dream Subtitles.
public struct SubtitleFile: Sendable, Hashable {
    public var url: URL
    public var language: String?
    public var label: String?
    public var online: Bool
    /// False: listed, not chosen by itself.
    public var asDefault: Bool
    public init(url: URL, language: String? = nil, label: String? = nil, online: Bool = false, asDefault: Bool = true) {
        self.url = url; self.language = language; self.label = label; self.online = online; self.asDefault = asDefault
    }
}

/// One audio or subtitle track of the current stream (PlayerContract.kt `Track`).
public struct PlayerTrack: Sendable, Hashable, Identifiable {
    public enum Kind: String, Sendable { case audio, text, video }
    public var id: String
    public var kind: Kind
    /// "English · Dolby Digital 5.1"
    public var label: String
    /// ISO 639-1 where known ("en").
    public var language: String?
    public var selected: Bool
    public var supported: Bool
    /// Audio: the language and the track's own name when it says more ("English · Director's Commentary").
    public var title: String?
    /// Audio: codec and channels ("Dolby Digital 5.1").
    public var detail: String?
    public var forced: Bool = false
    public var external: Bool = false
}

/// What is playing, for the stream badges and Info (ExoEngine.videoInfo, StreamBadges.facts).
public struct StreamFacts: Sendable, Hashable {
    public var width = 0
    public var height = 0
    /// "HEVC", "H.264", "AV1"...
    public var videoCodec: String?
    /// "Dolby Vision", "HDR10", "HLG" or nil (SDR).
    public var hdr: String?
    /// "Dolby Atmos", "Dolby Digital Plus", "DTS-HD"...
    public var audioCodec: String?
    /// Short audio codec for the info line ("E-AC3", "TrueHD").
    public var audioCodecShort: String?
    public var channels = 0
    public init() {}

    /// "2160p · HEVC · HDR10 · E-AC3 5.1"
    public var summary: String {
        var parts: [String] = []
        if height > 0 { parts.append(Labels.resolution(width, height)) }
        if let videoCodec { parts.append(videoCodec) }
        if let hdr { parts.append(hdr) }
        let a = [audioCodecShort ?? audioCodec, Labels.channels(channels)].compactMap { $0 }.joined(separator: " ")
        if !a.isEmpty { parts.append(a) }
        return parts.joined(separator: " · ")
    }
}

/// The player's subtitle look (PlayerPrefs subtitle style), applied by the engine.
public struct SubtitleStyle: Sendable, Hashable, Codable {
    /// S / M / L / XL = 0.78 / 1.0 / 1.25 / 1.5 (PlayerPrefs SIZES).
    public var scale: Double = 1
    /// Classic and Boxed white, Yellow #FFE45C.
    public var color: UInt32 = 0xFFFFFF
    /// 0 = outline (Classic, Yellow), else the Boxed background's opacity (190/255).
    public var background: Double = 0
    /// Bottom margin as a fraction of the picture: Low 0.045, Standard 0.08, Raised 0.16.
    public var position: Double = 0.08
    /// Raised above the controls while they show (at least 0.30).
    public var raised = false
    public init() {}
}

/// Engine state the screen reads. Seconds throughout.
public struct EngineState: Sendable, Equatable {
    public var hasSource = false
    public var isPlaying = false
    public var isBuffering = false
    /// The viewer (or the app) asked for pause; buffering keeps this false.
    public var paused = false
    public var position: Double = 0
    /// 0 when unknown.
    public var duration: Double = 0
    /// How far ahead is buffered (absolute seconds).
    public var buffered: Double = 0
    public var ended = false
    public var error: String?
    public var speed: Double = 1
}

public enum EngineEvent: Sendable {
    /// Play / pause / buffering changed.
    case state
    /// About twice a second while playing.
    case progress(Double)
    case tracks
    /// The first picture is on screen (hide the curtain).
    case firstFrame
    /// Video/audio format changed (badges).
    case format
    case ended
    /// A plain sentence for the viewer (no codes); the detail went to the log.
    case error(String)
}

@MainActor
public protocol PlayerEngine: AnyObject {
    /// "mpv" / "AVPlayer", for the log only.
    var name: String { get }
    /// The video surface; the screen puts it at the back of its view stack.
    var view: UIView { get }
    var state: EngineState { get }
    var onEvent: ((EngineEvent) -> Void)? { get set }

    /// Starts `source` at `start` seconds, replacing whatever was playing.
    func load(_ source: EngineSource, start: Double, autoplay: Bool)
    func play()
    func pause()
    func seek(to seconds: Double)
    func setSpeed(_ speed: Double)
    func tracks(_ kind: PlayerTrack.Kind) -> [PlayerTrack]
    /// Selects a track; for `.text`, nil turns subtitles off.
    func select(_ kind: PlayerTrack.Kind, id: String?)
    func addSubtitles(_ files: [SubtitleFile])
    func facts() -> StreamFacts
    /// Pinch to fill: crop the edges to fill the screen.
    func setFill(_ fill: Bool)
    func setSubtitleStyle(_ style: SubtitleStyle)
    /// Extra loudness, 0 = off (the Audio sheet's Volume Boost), in decibels.
    func setBoost(_ db: Double)
    /// Preferred languages for the next load (ISO 639-1; text "off" = no subtitles).
    func setPreferredLanguages(audio: String?, text: String?)
    /// The app went to the background / came back (the picture stops drawing; sound may continue later).
    func setVideoEnabled(_ on: Bool)
    /// Drops the stream; the engine stays usable.
    func stop()
    /// Ends the engine for good.
    func shutdown()

    // Picture in Picture (defaults: not supported).
    /// The layer the system floats: an AVPlayerLayer, or a sample buffer layer the engine feeds. nil = none.
    var pipContent: PiPContent? { get }
    /// Picture in Picture is about to start (or the button asked for it): draw into the floating layer.
    func pipWillStart()
    /// Picture in Picture ended: back to the normal picture (or no picture while in the background).
    func pipDidStop()
    /// The floating window's size in pixels.
    func pipRenderSize(width: Int, height: Int)
    /// The first frame reached the floating layer after `pipWillStart` (main queue).
    var onPiPFrame: (() -> Void)? { get set }

    /// Scrub previews for the playing stream (nil: this engine has none).
    var thumbnails: ScrubThumbnails? { get }
    /// The screen is showing this stream in HDR right now (EDR on). False = standard range (or not known).
    var hdrOnScreen: Bool { get }

    /// Seconds since the on-screen picture last drew a frame; nil when not known (or Picture in Picture / a switch
    /// back from it is running). The picture watch (PlayerPresentation) uses it.
    var secondsSincePicture: Double? { get }
    /// Puts the picture back on screen: the right layer in front, the video chain re-armed, an exact seek to the spot.
    func redrawPicture()
    /// The picture's sizes for the log (layer, drawable, the size the engine draws at); nil when not known.
    func pictureSizeLine() -> String?
    /// The layer's size and the size the engine draws at, in pixels (nil: not known, PiP or a switch running). Far
    /// apart means a picture in a corner box (or cropped).
    var pictureSizes: (layer: CGSize, drawing: CGSize)? { get }
    /// Reconfigures drawing at the layer's real size.
    func refitPicture()
}

/// What Picture in Picture floats for an engine.
public enum PiPContent {
    case sampleBuffer(AVSampleBufferDisplayLayer)
}

public extension PlayerEngine {
    var pipContent: PiPContent? { nil }
    func pipWillStart() {}
    func pipDidStop() {}
    func pipRenderSize(width: Int, height: Int) {}
    var onPiPFrame: (() -> Void)? { get { nil } set {} }
    var thumbnails: ScrubThumbnails? { nil }
    var hdrOnScreen: Bool { false }
    var secondsSincePicture: Double? { nil }
    func redrawPicture() {}
    func pictureSizeLine() -> String? { nil }
    var pictureSizes: (layer: CGSize, drawing: CGSize)? { nil }
    func refitPicture() {}
}

@MainActor
public enum Engines {
    public static func make() -> PlayerEngine { MPVEngine() }
}
