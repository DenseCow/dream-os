import Foundation

// Added by the Trakt worker (additive only). Two small seams so DreamPlayback's Trakt sync can follow Dream Player and
// feed New Episodes without either module importing the other.

/// What Dream Player is playing right now (fire-home `Trakt.onPlayer`'s arguments). Posted by the player on its state
/// changes and with its progress, only for real titles (never trailers) and only after the first frame.
public struct PlayerReport: Sendable, Hashable {
    public var request: PlayRequest
    /// Playing, or about to (buffering while not paused).
    public var playing: Bool
    public var positionMs: Int64
    public var durationMs: Int64
    public init(request: PlayRequest, playing: Bool, positionMs: Int64, durationMs: Int64) {
        self.request = request; self.playing = playing; self.positionMs = positionMs; self.durationMs = durationMs
    }
}

public extension Notification.Name {
    /// `object` is a `PlayerReport`. Posted on the main thread.
    static let dreamPlayerReport = Notification.Name("dream.playerReport")
    /// Dream Player closed (fire-home `Trakt.onPlayerClosed`). Posted on the main thread.
    static let dreamPlayerClosed = Notification.Name("dream.playerClosed")
}

/// The player's side of the seam: one call per event, returns at once.
public enum PlayerReports {
    public static func report(_ request: PlayRequest, playing: Bool, positionMs: Int64, durationMs: Int64) {
        NotificationCenter.default.post(name: .dreamPlayerReport,
                                        object: PlayerReport(request: request, playing: playing, positionMs: positionMs, durationMs: durationMs))
    }

    public static func closed() { NotificationCenter.default.post(name: .dreamPlayerClosed, object: nil) }
}

/// Extra followed shows for New Episodes (fire-home `NewEpisodes.followedShows` asks Trakt after My List): TMDB ids,
/// most important first. Sources are added once at launch and must answer from memory or disk (no network).
public enum FollowedShows {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var sources: [@Sendable () -> [Int]] = []

    public static func add(_ source: @escaping @Sendable () -> [Int]) { lock.withLock { sources.append(source) } }

    /// Every source's ids, in order, duplicates left in (the caller dedupes).
    public static func ids() -> [Int] { lock.withLock { sources }.flatMap { $0() }.filter { $0 > 0 } }
}
