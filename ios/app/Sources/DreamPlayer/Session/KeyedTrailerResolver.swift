#if canImport(YouTubeKit)
import Foundation
import YouTubeKit
import DreamCore

/// Trailer streams for a TMDB video key, extracted on the device (the Fire TV used NewPipe for the same job).
/// Picks like TrailerPick.kt: video-only AVC up to 1080 (VP9 only when it reaches higher than AVC), paired
/// with the best AAC audio; else the best muxed stream up to 1080. mpv plays the pair as one.
struct KeyedTrailerResolver: TrailerStreamResolver {
    func stream(forKey key: String) async throws -> TrailerStream {
        let streams = try await YouTube(videoID: key, methods: [.local]).streams
        func codec(_ s: YouTubeKit.Stream) -> String { s.videoCodec.map { String(describing: $0).lowercased() } ?? "" }
        func acodec(_ s: YouTubeKit.Stream) -> String { s.audioCodec.map { String(describing: $0).lowercased() } ?? "" }
        let videoOnly = streams.filter { $0.videoCodec != nil && $0.audioCodec == nil && ($0.videoResolution ?? 0) <= 1080 }
        let avc = videoOnly.filter { codec($0).contains("avc") }
        let vp9 = videoOnly.filter { codec($0).contains("vp9") || codec($0).contains("vp09") }
        let best = { (l: [YouTubeKit.Stream]) -> YouTubeKit.Stream? in
            l.max { ($0.videoResolution ?? 0, $0.averageBitrate ?? $0.bitrate ?? 0) < ($1.videoResolution ?? 0, $1.averageBitrate ?? $1.bitrate ?? 0) }
        }
        let topAvc = best(avc), topVp9 = best(vp9)
        let video: YouTubeKit.Stream? = {
            guard let a = topAvc else { return topVp9 }
            if (a.videoResolution ?? 0) >= 1080 || (a.videoResolution ?? 0) >= (topVp9?.videoResolution ?? 0) { return a }
            return topVp9
        }()
        let audioOnly = streams.filter { $0.videoCodec == nil && $0.audioCodec != nil }
        let aac = audioOnly.filter { acodec($0).contains("mp4a") || acodec($0).contains("aac") }
        let audio = (aac.isEmpty ? audioOnly : aac).max { ($0.averageBitrate ?? $0.bitrate ?? 0) < ($1.averageBitrate ?? $1.bitrate ?? 0) }
        if let video, let audio { return TrailerStream(video: video.url, audio: audio.url) }
        let muxed = streams.filter { $0.isProgressive && ($0.videoResolution ?? 0) <= 1080 }
        if let m = best(muxed) { return TrailerStream(video: m.url) }
        throw DreamError("Trailer Unavailable")
    }
}
#endif
