#if canImport(YouTubeKit)
import Foundation
import YouTubeKit
import DreamCore

/// Trailer streams for a TMDB video key, found on the device (the Fire TV used NewPipe for the same job).
///
/// Two paths, same pick (TrailerPick, so the quality is the same either way: 1080p H.264 + AAC for a usual trailer):
/// 1. `TrailerDirect`: one request, streams that come ready, no player script and no descrambler (0.2-1.2 s).
/// 2. The full extractor (YouTubeKit) when the direct path fails. It downloads the watch page and rebuilds its
///    descrambler from the player script inside every look-up (about 1.5 s per stream list on the phone, two lists a
///    look-up, far longer when anything else is busy), so it runs one at a time and gives up after 12 s; the miss is
///    then remembered briefly by TrailerAhead and asked again later.
/// Three direct failures in a row skip the direct path for 15 minutes (straight to the extractor).
struct KeyedTrailerResolver: TrailerStreamResolver {
    func stream(forKey key: String) async throws -> TrailerStream {
        if TrailerFallback.shared.directAllowed {
            do {
                let a = try await TrailerDirect.shared.formats(forKey: key)
                if let c = TrailerPick.pick(a.formats) {
                    TrailerFallback.shared.directWorked()
                    let visitor = a.visitorMs.map { "visitor id \(a.visitorSource) in \($0) ms" } ?? "visitor id \(a.visitorSource)"
                    Log.player.notice("Trailer lookup: direct in \(a.ms, privacy: .public) ms (\(visitor, privacy: .public)), \(c.label, privacy: .public), no descrambler needed")
                    return TrailerStream(video: c.video, audio: c.audio)
                }
                TrailerFallback.shared.directFailed()
                Log.player.notice("Trailer lookup: direct gave nothing playable in \(a.ms, privacy: .public) ms; full extractor next")
            } catch {
                TrailerFallback.shared.directFailed()
                Log.player.notice("Trailer lookup: direct failed (\(String(describing: error), privacy: .public)); full extractor next")
            }
        }
        return try await TrailerFallback.shared.extract(key)
    }

    func warm() async {
        guard TrailerFallback.shared.directAllowed else { return }
        if let ms = await TrailerDirect.shared.warm() {
            Log.player.notice("Trailer lookup: warmed (visitor id fetched in \(ms, privacy: .public) ms)")
        }
    }
}

/// The full extractor, one at a time and capped, plus the direct path's failure count.
final class TrailerFallback: @unchecked Sendable {
    static let shared = TrailerFallback()
    static let cap: Double = 12
    static let skipDirectFor: TimeInterval = 15 * 60

    private let lock = NSLock()
    private var tail: Task<Void, Never>?
    private var directFailures = 0
    private var skipDirectUntil: Date?

    var directAllowed: Bool { lock.withLock { skipDirectUntil.map { Date() >= $0 } ?? true } }

    func directWorked() { lock.withLock { directFailures = 0; skipDirectUntil = nil } }

    func directFailed() {
        let skip: Bool = lock.withLock {
            directFailures += 1
            guard directFailures >= 3 else { return false }
            directFailures = 0
            skipDirectUntil = Date().addingTimeInterval(Self.skipDirectFor)
            return true
        }
        if skip { Log.player.notice("Trailer lookup: direct failed 3 times in a row; full extractor only for 15 min") }
    }

    /// Waits for the extraction before it (never two at once: two descrambler builds side by side took 7-11 s each
    /// on the phone), then runs this one for at most `cap` seconds.
    func extract(_ key: String) async throws -> TrailerStream {
        let asked = Date()
        let run: Task<TrailerStream, Error> = lock.withLock {
            let previous = tail
            let t = Task<TrailerStream, Error>(priority: .utility) {
                await previous?.value
                let waited = TrailerDirect.ms(asked)
                let t0 = Date()
                do {
                    let s = try await Self.capped(key)
                    Log.player.notice("Trailer lookup: full extractor in \(TrailerDirect.ms(t0), privacy: .public) ms after \(waited, privacy: .public) ms in line (it rebuilds its descrambler every time)")
                    return s
                } catch {
                    Log.player.notice("Trailer lookup: full extractor gave up after \(TrailerDirect.ms(t0), privacy: .public) ms (\(String(describing: error), privacy: .public))")
                    throw error
                }
            }
            tail = Task { _ = try? await t.value }
            return t
        }
        return try await run.value
    }

    /// Races the extraction against the cap with unstructured tasks (a task group would wait for the slow child).
    private static func capped(_ key: String) async throws -> TrailerStream {
        let once = Once()
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<TrailerStream, Error>) in
            let work = Task(priority: .utility) {
                do {
                    let s = try await extractNow(key)
                    if once.claim() { cont.resume(returning: s) }
                } catch {
                    if once.claim() { cont.resume(throwing: error) }
                }
            }
            Task {
                try? await Task.sleep(for: .seconds(cap))
                if once.claim() {
                    work.cancel()
                    cont.resume(throwing: TrailerDirect.Failure("over \(Int(cap)) s"))
                }
            }
        }
    }

    private static func extractNow(_ key: String) async throws -> TrailerStream {
        let streams = try await YouTube(videoID: key, methods: [.local]).streams
        func video(_ c: VideoCodec) -> String {
            switch c { case .avc1: "avc1"; case .vp9: "vp09"; case .av1: "av01"; case .mp4v: "mp4v"; case .unknown(let s): s }
        }
        func audio(_ c: AudioCodec) -> String {
            switch c { case .mp4a: "mp4a"; case .opus: "opus"; case .ec3: "ec-3"; case .ac3: "ac-3"; case .unknown(let s): s }
        }
        let formats = streams.map { s -> TrailerFormat in
            let codecs = [s.videoCodec.map(video), s.audioCodec.map(audio)].compactMap { $0 }.joined(separator: ", ")
            let kind = s.videoCodec != nil ? "video" : "audio"
            let itag = URLComponents(url: s.url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "itag" }?.value.flatMap(Int.init) ?? 0
            return TrailerFormat(itag: itag, url: s.url, mimeType: "\(kind)/x; codecs=\"\(codecs)\"",
                                 height: s.videoResolution, bitrate: s.averageBitrate ?? s.bitrate)
        }
        guard let c = TrailerPick.pick(formats) else { throw DreamError("Trailer Unavailable") }
        return TrailerStream(video: c.video, audio: c.audio)
    }
}

/// True for the first caller only.
private final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    func claim() -> Bool { lock.withLock { if done { return false }; done = true; return true } }
}
#endif
