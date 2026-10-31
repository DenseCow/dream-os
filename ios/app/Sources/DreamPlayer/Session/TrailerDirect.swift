import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The trailer look-up's fast path: one player request with the client whose stream URLs come ready to play (no
/// cipher, no throttling parameter), so no page download, no player script and no descrambler at all. Measured from
/// the owner's network (2026-10-30, 6 trailers): 220-1200 ms per look-up, every stream range readable to the end,
/// the same 1080p H.264 + AAC streams the full extractor picks.
///
/// The only state is a visitor id (the service asks for one before it answers this client): kept on disk for 3 days,
/// fetched once per run at most (`warm()` shortly after launch), and replaced from the refusal itself when the
/// service turns it down. Foundation only (no DreamCore), so `tools/trailer-check.sh` compiles and runs it on Linux.
final class TrailerDirect: @unchecked Sendable {
    static let shared = TrailerDirect()

    struct Failure: Error, CustomStringConvertible {
        let description: String
        init(_ d: String) { description = d }
    }

    /// One look-up's answer plus where its time went (for the log line).
    struct Answer {
        let formats: [TrailerFormat]
        let ms: Int
        let visitorMs: Int?
        let visitorSource: String
    }

    static let visitorLife: TimeInterval = 3 * 24 * 3600
    static let requestTimeout: TimeInterval = 5
    private static let userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
    private static let storeKey = "trailer.direct.visitor"

    private let lock = NSLock()
    private var visitor: String?
    private var visitorAt: Date?
    private var visitorTask: Task<String, Error>?
    private let defaults: UserDefaults
    let session: URLSession

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = Self.requestTimeout
        c.timeoutIntervalForResource = Self.requestTimeout * 2
        c.httpShouldSetCookies = false
        c.urlCache = nil
        session = URLSession(configuration: c)
        if let saved = defaults.dictionary(forKey: Self.storeKey), let v = saved["v"] as? String,
           let at = saved["at"] as? Double, Date().timeIntervalSince1970 - at < Self.visitorLife {
            visitor = v
            visitorAt = Date(timeIntervalSince1970: at)
        }
    }

    /// Fetches the visitor id now when none is kept (and opens the connection the first look-up reuses).
    /// Returns the ms it took, nil when one was already kept.
    @discardableResult
    func warm() async -> Int? {
        if currentVisitor() != nil { return nil }
        let t0 = Date()
        _ = try? await visitorID()
        return Self.ms(t0)
    }

    /// The streams for video `key`.
    func formats(forKey key: String) async throws -> Answer {
        let t0 = Date()
        var visitorMs: Int?
        var source = "kept"
        var v = currentVisitor()
        if v == nil {
            let tv = Date()
            v = try? await visitorID()
            visitorMs = Self.ms(tv)
            source = v == nil ? "none" : "fetched"
        }
        var reply = try await player(key, visitor: v)
        if reply.status != "OK", let fresh = reply.visitorData, fresh != v {
            // Turned down (usually the bot check): its answer carries a new visitor id, which the retry uses.
            keep(fresh)
            source = "renewed"
            reply = try await player(key, visitor: fresh)
        }
        guard reply.status == "OK" else { throw Failure("status \(reply.status ?? "none")\(reply.reason.map { ": \($0)" } ?? "")") }
        guard reply.videoID == key else { throw Failure("answered for another video") }
        guard !reply.formats.isEmpty else { throw Failure("no streams") }
        return Answer(formats: reply.formats, ms: Self.ms(t0), visitorMs: visitorMs, visitorSource: source)
    }

    // ---- visitor id ----

    func currentVisitor() -> String? {
        lock.withLock {
            guard let v = visitor, let at = visitorAt, Date().timeIntervalSince(at) < Self.visitorLife else { return nil }
            return v
        }
    }

    private func keep(_ v: String) {
        let now = Date()
        lock.withLock { visitor = v; visitorAt = now; visitorTask = nil }
        defaults.set(["v": v, "at": now.timeIntervalSince1970], forKey: Self.storeKey)
    }

    /// One fetch at a time: a second caller joins the one under way.
    private func visitorID() async throws -> String {
        let task: Task<String, Error> = lock.withLock {
            if let t = visitorTask { return t }
            let t = Task<String, Error> { [session] in
                var req = URLRequest(url: URL(string: "https://www.youtube.com/sw.js_data")!)
                req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
                let (data, _) = try await session.data(for: req)
                guard let v = Self.visitor(fromServiceWorkerData: data) else { throw Failure("no visitor id") }
                return v
            }
            visitorTask = t
            return t
        }
        do {
            let v = try await task.value
            keep(v)
            return v
        } catch {
            lock.withLock { if visitorTask == task { visitorTask = nil } }
            throw error
        }
    }

    /// `)]}'` + a JSON array; the id sits at [0][2][0][0][13]. Falls back to the first id-shaped string in it.
    static func visitor(fromServiceWorkerData data: Data) -> String? {
        guard var text = String(data: data, encoding: .utf8) else { return nil }
        if let open = text.firstIndex(of: "[") { text = String(text[open...]) }
        if let json = try? JSONSerialization.jsonObject(with: Data(text.utf8)),
           let v = ((((json as? [Any])?.first as? [Any]).flatMap { $0.count > 2 ? $0[2] as? [Any] : nil }?.first as? [Any])?.first as? [Any])
            .flatMap({ $0.count > 13 ? $0[13] as? String : nil }), !v.isEmpty {
            return v
        }
        guard let r = text.range(of: #"Cg[A-Za-z0-9_\-]{8,}%3D%3D|Cg[A-Za-z0-9_\-]{8,}={0,2}"#, options: .regularExpression) else { return nil }
        return String(text[r]).removingPercentEncoding
    }

    // ---- the player request ----

    private struct Reply {
        var status: String?
        var reason: String?
        var videoID: String?
        var visitorData: String?
        var formats: [TrailerFormat]
    }

    private struct Response: Decodable {
        struct Status: Decodable { let status: String?; let reason: String? }
        struct Details: Decodable { let videoId: String? }
        struct Context: Decodable { let visitorData: String? }
        struct Streaming: Decodable {
            struct Format: Decodable {
                let itag: Int
                let url: String?
                let mimeType: String
                let width: Int?
                let height: Int?
                let bitrate: Int?
                let averageBitrate: Int?
                let audioTrack: Track?
                struct Track: Decodable { let displayName: String? }
            }
            let formats: [Format]?
            let adaptiveFormats: [Format]?
        }
        let playabilityStatus: Status?
        let videoDetails: Details?
        let responseContext: Context?
        let streamingData: Streaming?
    }

    private func player(_ key: String, visitor: String?) async throws -> Reply {
        var client: [String: Any] = [
            "clientName": "VISIONOS", "clientVersion": "1.02", "deviceMake": "Apple", "deviceModel": "RealityDevice17,1",
            "osName": "visionOS", "osVersion": "26.5.23O471", "hl": "en", "gl": "US",
        ]
        if let visitor { client["visitorData"] = visitor }
        let body: [String: Any] = ["context": ["client": client], "videoId": key, "contentCheckOk": true, "racyCheckOk": true]
        var req = URLRequest(url: URL(string: "https://www.youtube.com/youtubei/v1/player?prettyPrint=false")!)
        req.httpMethod = "POST"
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("101", forHTTPHeaderField: "X-Youtube-Client-Name")
        req.setValue("1.02", forHTTPHeaderField: "X-Youtube-Client-Version")
        req.setValue("https://www.youtube.com", forHTTPHeaderField: "Origin")
        req.setValue("en-US,en", forHTTPHeaderField: "Accept-Language")
        if let visitor { req.setValue(visitor, forHTTPHeaderField: "X-Goog-Visitor-Id") }
        let (data, response) = try await session.data(for: req)
        if let h = response as? HTTPURLResponse, h.statusCode != 200 { throw Failure("HTTP \(h.statusCode)") }
        let r = try JSONDecoder().decode(Response.self, from: data)
        let raw = (r.streamingData?.formats ?? []) + (r.streamingData?.adaptiveFormats ?? [])
        let formats = raw.compactMap { f -> TrailerFormat? in
            // Only streams that come ready (a ciphered one has no url); dubbed sound tracks left out like the extractor.
            guard let s = f.url, let url = URL(string: s) else { return nil }
            if let name = f.audioTrack?.displayName, !name.lowercased().hasSuffix("original") { return nil }
            return TrailerFormat(itag: f.itag, url: url, mimeType: f.mimeType, height: f.height,
                                 bitrate: f.averageBitrate ?? f.bitrate)
        }
        return Reply(status: r.playabilityStatus?.status, reason: r.playabilityStatus?.reason, videoID: r.videoDetails?.videoId,
                     visitorData: r.responseContext?.visitorData, formats: formats)
    }

    static func ms(_ since: Date) -> Int { Int(Date().timeIntervalSince(since) * 1000) }
}

/// One stream as the pick sees it (from either look-up path).
struct TrailerFormat: Sendable {
    let itag: Int
    let url: URL
    /// e.g. `video/mp4; codecs="avc1.640028"`.
    let mimeType: String
    let height: Int?
    let bitrate: Int?

    var codecs: String {
        guard let r = mimeType.range(of: "codecs=\"") else { return "" }
        return String(mimeType[r.upperBound...].prefix { $0 != "\"" }).lowercased()
    }
    var hasVideo: Bool { mimeType.hasPrefix("video/") }
    /// Video with its sound in the same file (the progressive kind).
    var hasAudio: Bool { mimeType.hasPrefix("audio/") || codecs.contains("mp4a") || codecs.contains("opus") }
    var isAVC: Bool { hasVideo && codecs.contains("avc") }
    var isVP9: Bool { hasVideo && (codecs.contains("vp9") || codecs.contains("vp09")) }
}

/// Picks like TrailerPick.kt (unchanged from the extractor-only version, so quality stays the same): video-only H.264
/// up to 1080 (VP9 only when it reaches higher than H.264), paired with the best AAC sound; else the best
/// progressive stream up to 1080 (the last resort, today's 360p one).
enum TrailerPick {
    struct Choice: Equatable { let video: URL; let audio: URL?; let label: String }

    static func pick(_ all: [TrailerFormat]) -> Choice? {
        let videoOnly = all.filter { $0.hasVideo && !$0.hasAudio && ($0.height ?? 0) <= 1080 }
        func best(_ l: [TrailerFormat]) -> TrailerFormat? {
            l.max { ($0.height ?? 0, $0.bitrate ?? 0) < ($1.height ?? 0, $1.bitrate ?? 0) }
        }
        let topAVC = best(videoOnly.filter(\.isAVC)), topVP9 = best(videoOnly.filter(\.isVP9))
        let video: TrailerFormat? = {
            guard let a = topAVC else { return topVP9 }
            if (a.height ?? 0) >= 1080 || (a.height ?? 0) >= (topVP9?.height ?? 0) { return a }
            return topVP9
        }()
        let audioOnly = all.filter { !$0.hasVideo && $0.hasAudio }
        let aac = audioOnly.filter { $0.codecs.contains("mp4a") || $0.codecs.contains("aac") }
        let audio = (aac.isEmpty ? audioOnly : aac).max { ($0.bitrate ?? 0) < ($1.bitrate ?? 0) }
        if let video, let audio {
            return Choice(video: video.url, audio: audio.url, label: "\(video.height ?? 0)p \(video.isAVC ? "H.264" : "VP9") + \(audio.codecs.contains("mp4a") ? "AAC" : "sound") (\(video.itag)+\(audio.itag))")
        }
        let muxed = all.filter { $0.hasVideo && $0.hasAudio && ($0.height ?? 0) <= 1080 }
        if let m = best(muxed) { return Choice(video: m.url, audio: nil, label: "\(m.height ?? 0)p one file (\(m.itag))") }
        return nil
    }
}
