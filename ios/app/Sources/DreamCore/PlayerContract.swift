import Foundation

// Optional extras Dream Player uses when the playback service offers them (added by the Player worker,
// additive only). A PlaybackService adopts `PlaybackExtras` to get the Fire TV behaviour 1:1; without it the
// player falls back to the plain `PlaybackService` calls.

/// A resolve failure worded for the loading curtain: a title and one plain line (PlayFlow's failure texts,
/// e.g. "Connect a Debrid Service" / "Add one in Settings › Accounts & Scrapers, then press Play again.").
/// A plain `DreamError` is shown as the line under "This Title Isn't Available Right Now".
public struct PlaybackProblem: Error, Sendable, Hashable {
    public var title: String
    public var message: String
    public init(title: String, message: String) { self.title = title; self.message = message }
}

public protocol PlaybackExtras: Sendable {
    /// Resolve with the viewer's source filters set aside for this one search ("Search All Sources").
    func resolve(_ request: PlayRequest, loose: Bool) async throws -> StreamLink
    /// Every candidate; `loose` includes ones the source settings usually hide (the picker's All Sources).
    func candidates(_ request: PlayRequest, loose: Bool) async throws -> [StreamCandidate]
    /// The link that just failed isn't handed out again; a retry takes the next torrent (NativeResolver.forget).
    func forget(_ request: PlayRequest)
    /// What plays came from outside the viewer's filters: "Only a CAM Version Is Available" /
    /// "Playing the Best Available Version"; nil normally. Asked once, right after `resolve`.
    func note(for link: StreamLink) -> String?
    /// The stream played its first frame after `seconds` (Dream Core prefers faster services next time).
    func started(_ request: PlayRequest, link: StreamLink, seconds: Double)
    /// Instant Play (2026-10-28): the next ready link for `request` after the current one failed to open or start,
    /// from the ones Dream Core unlocked alongside it (no network). nil when none is left: the player then shows the
    /// error. Call `forget` for the failed link first.
    func nextLink(for request: PlayRequest) -> StreamLink?
    /// Whether `nextLink` would have one (no network, nothing taken): the player's start watchdog only gives up on
    /// a slow link when another is ready.
    func hasNextLink(for request: PlayRequest) -> Bool
}

public extension PlaybackExtras {
    func candidates(_ request: PlayRequest, loose: Bool) async throws -> [StreamCandidate] { [] }
    func note(for link: StreamLink) -> String? { nil }
    func started(_ request: PlayRequest, link: StreamLink, seconds: Double) {}
    func nextLink(for request: PlayRequest) -> StreamLink? { nil }
    func hasNextLink(for request: PlayRequest) -> Bool { false }
}

/// Where trailers come from: a key (TMDB's video key) -> playable streams. Implemented in DreamPlayer;
/// the service behind it is never named on screen.
public struct TrailerStream: Sendable, Hashable {
    public var video: URL
    /// A separate audio stream when the video is video-only (adaptive).
    public var audio: URL?
    public init(video: URL, audio: URL? = nil) { self.video = video; self.audio = audio }
}
