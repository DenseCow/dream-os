import Foundation
import DreamCore

/// Turns a trailer key (TMDB's video key) into playable streams. Swappable: set `Trailers.resolver` at launch.
/// Nothing on screen ever names the service behind it.
public protocol TrailerStreamResolver: Sendable {
    func stream(forKey key: String) async throws -> TrailerStream
    /// Readies what every look-up needs (a few KB, once per run at most) so the first one is as quick as the rest.
    func warm() async
}

public extension TrailerStreamResolver {
    func warm() async {}
}

public enum Trailers {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var _resolver: TrailerStreamResolver = {
        #if canImport(YouTubeKit)
        return KeyedTrailerResolver()
        #else
        return NoTrailerResolver()
        #endif
    }()
    /// The resolver Dream Player asks (the on-device extractor when linked, else NoTrailerResolver).
    public static var resolver: TrailerStreamResolver {
        get { lock.withLock { _resolver } }
        set { lock.withLock { _resolver = newValue } }
    }

    /// Called a few seconds after launch, off the main thread, at low priority (the app skips it while Dream Player is
    /// up, in Low Power Mode, when trailers are off and when look-ahead may not use the network).
    public static func warm() async { await resolver.warm() }
}

/// Stand-in: every trailer is unavailable (the curtain shows "Trailer Unavailable").
public struct NoTrailerResolver: TrailerStreamResolver {
    public init() {}
    public func stream(forKey key: String) async throws -> TrailerStream { throw DreamError("Trailer Unavailable") }
}
