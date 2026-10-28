import Foundation

/// Shared HTTP: one URLSession with a disk cache, the Dream OS user agent, JSON decoding.
/// Screens fetch ahead (speed pass); a big URLCache keeps art and answers between launches.
public enum Net {
    public static let userAgent = "DreamOS/0.1 (iOS)"

    public static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.urlCache = URLCache(memoryCapacity: 64 << 20, diskCapacity: 1 << 30)
        c.httpMaximumConnectionsPerHost = 12
        c.requestCachePolicy = .useProtocolCachePolicy
        c.timeoutIntervalForRequest = 15
        c.httpAdditionalHeaders = ["User-Agent": userAgent]
        return URLSession(configuration: c)
    }()

    public static func data(_ request: URLRequest) async throws -> Data {
        let tmdb = request.url?.host() == "api.themoviedb.org"
        // TMDB answers 429 when look-ahead fires hundreds of requests at once: background work is paced under its
        // limit, what's on screen (user-initiated priority) never waits, and a 429 is retried after a short pause.
        if tmdb { await TmdbGate.shared.enter(urgent: Task.currentPriority >= .userInitiated) }
        var (data, response) = try await session.data(for: request)
        for attempt in 1...2 where tmdb && (response as? HTTPURLResponse)?.statusCode == 429 {
            let after = Double((response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Retry-After") ?? "") ?? 0
            await TmdbGate.shared.backOff(seconds: max(after, 0.6 * Double(attempt)))
            await TmdbGate.shared.enter(urgent: Task.currentPriority >= .userInitiated)
            (data, response) = try await session.data(for: request)
        }
        guard let http = response as? HTTPURLResponse else { throw DreamError("Couldn't connect. Check your internet and try again.") }
        guard (200..<300).contains(http.statusCode) else {
            Log.core.error("HTTP \(http.statusCode, privacy: .public) for \(request.url?.host() ?? "?", privacy: .public)")
            throw NetError.status(http.statusCode, data)
        }
        return data
    }

    public static func data(_ url: URL) async throws -> Data { try await data(URLRequest(url: url)) }

    public static func json<T: Decodable>(_ type: T.Type, _ request: URLRequest, decoder: JSONDecoder = JSONDecoder()) async throws -> T {
        try decoder.decode(T.self, from: try await data(request))
    }

    /// Untyped JSON for ports of org.json-style code.
    public static func object(_ url: URL) async throws -> [String: Any] {
        let any = try JSONSerialization.jsonObject(with: try await data(url))
        guard let o = any as? [String: Any] else { throw DreamError("Something went wrong. Try again.") }
        return o
    }
}

public enum NetError: Error, Sendable {
    case status(Int, Data)
}

/// TMDB's request pacing (owner, 2026-10-28). TMDB allows roughly 40-50 requests a second per address; past that it
/// answers 429 and the request is lost. Urgent requests (what's on screen) go straight through and only count;
/// background ones (look-ahead, pills, snapshots) wait for a free slot in the current second. After a 429 everyone
/// background waits out the pause.
public actor TmdbGate {
    public static let shared = TmdbGate()
    private let perSecond = 35
    private var stamps: [Date] = []
    private var pausedUntil = Date.distantPast

    public func enter(urgent: Bool) async {
        while true {
            let now = Date()
            stamps.removeAll { now.timeIntervalSince($0) >= 1 }
            if urgent || (now >= pausedUntil && stamps.count < perSecond) {
                stamps.append(now)
                return
            }
            let wait = max(pausedUntil.timeIntervalSince(now), (stamps.first.map { 1 - now.timeIntervalSince($0) } ?? 0.05))
            try? await Task.sleep(for: .seconds(min(max(wait, 0.02), 1.0)))
        }
    }

    public func backOff(seconds: Double) {
        let until = Date().addingTimeInterval(seconds)
        if until > pausedUntil { pausedUntil = until }
        Log.core.notice("TMDB: rate limited, pausing background requests \(String(format: "%.1f", seconds), privacy: .public) s")
    }
}
