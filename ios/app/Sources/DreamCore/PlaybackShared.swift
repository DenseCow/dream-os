import Foundation

// Playback types and small pure helpers several modules need (the player can't see DreamPlayback, the catalog can't
// either). Added by the Playback worker; additive only, like the rest of DreamCore.

// MARK: Post-play

/// What comes after an episode (PlayFlow.kt `NextUp`): the request, its still, overview and runtime.
public struct NextUp: Codable, Sendable, Hashable {
    public var request: PlayRequest
    public var stillPath: String?
    public var overview: String
    /// Minutes, 0 = unknown.
    public var runtime: Int
    public init(request: PlayRequest, stillPath: String? = nil, overview: String = "", runtime: Int = 0) {
        self.request = request; self.stillPath = stillPath; self.overview = overview; self.runtime = runtime
    }
    public func still(_ size: String = "w500") -> URL? { TMDBImage.url(stillPath, size) }
}

/// PostPlay.prepare's answer: the next episode, or (a movie, the last episode) up to four titles with backdrops.
public struct PostPlayPlan: Sendable, Hashable {
    public var next: NextUp?
    public var more: [Title]
    public init(next: NextUp? = nil, more: [Title] = []) { self.next = next; self.more = more }
}

/// When post-play shows: the credits marker in the second half, else the last 30 s of an episode / the last ~6 %
/// (at most 3 min) of a movie (PostPlay.kt `fallbackCredits`). Milliseconds.
public enum PostPlayTiming {
    public static func creditsStart(kind: MediaKind, durationMs: Int64, markers: [SkipMarker]) -> Int64 {
        if let c = markers.first(where: { $0.kind == .credits }) {
            let ms = Int64(c.start * 1000)
            if ms >= durationMs / 2 && ms < durationMs { return ms }
        }
        if kind == .tv { return max(durationMs - 30_000, durationMs * 9 / 10) }
        return max(durationMs - min(180_000, durationMs * 6 / 100), durationMs * 9 / 10)
    }
}

// MARK: Skip markers

/// A chapter of the stream itself (mpv's chapter list): exact for this very file.
public struct SkipChapter: Codable, Sendable, Hashable {
    public var title: String
    /// Seconds.
    public var start: Double
    /// Seconds; nil = until the next chapter.
    public var end: Double?
    public init(title: String, start: Double, end: Double? = nil) { self.title = title; self.start = start; self.end = end }
}

/// Markers plus where they came from (SkipSources.kt `SkipInfo`): no auto-skip on a learned intro, no tick for an
/// estimated credits start.
public struct SkipInfo: Codable, Sendable, Hashable {
    public var markers: [SkipMarker]
    public var introLearned: Bool
    public var creditsEstimated: Bool
    public init(markers: [SkipMarker] = [], introLearned: Bool = false, creditsEstimated: Bool = false) {
        self.markers = markers; self.introLearned = introLearned; self.creditsEstimated = creditsEstimated
    }
    public func marker(_ kind: SkipMarker.Kind) -> SkipMarker? { markers.first { $0.kind == kind } }
}

// MARK: Availability survey

/// What the scrapers find for a title (NativeResolver.Survey): [quality] releases that aren't camera grade;
/// [best] / [bestCam] read like "1080p WEB-DL" / "HDTS 1080p".
public struct SourceSurvey: Codable, Sendable, Hashable {
    public var total: Int
    public var quality: Int
    public var cam: Int
    public var best: String?
    public var bestCam: String?
    public init(total: Int, quality: Int, cam: Int, best: String? = nil, bestCam: String? = nil) {
        self.total = total; self.quality = quality; self.cam = cam; self.best = best; self.bestCam = bestCam
    }
}

// MARK: Settings the player and Dream Core share

/// Settings › Watching and Dream Player choices (PlayFlow.kt prefs, PlayerPrefs, SkipMarkers.autoSkipIntros).
public enum PlaybackSettings {
    /// "Autoplay Next Episode" (default on).
    public static var autoplayNext: Bool {
        get { DreamStore.shared.get(Bool.self, "play.autoplayNext") ?? true }
        set { DreamStore.shared.set(newValue, "play.autoplayNext") }
    }
    /// "Are you still watching?" after 3 autoplayed episodes without a touch (default on).
    public static var stillWatchingCheck: Bool {
        get { DreamStore.shared.get(Bool.self, "play.stillWatching") ?? true }
        set { DreamStore.shared.set(newValue, "play.stillWatching") }
    }
    /// "Auto-Skip Intros" (default off), per profile.
    public static var autoSkipIntros: Bool {
        get { DreamStore.shared.get(Bool.self, "player.autoSkipIntro", perProfile: true) ?? false }
        set { DreamStore.shared.set(newValue, "player.autoSkipIntro", perProfile: true) }
    }
    /// Dream Player's preferred audio language (2-letter, "en"), per profile; nil = never chosen (the device's).
    /// Dream Core ranks releases in that language first.
    public static var audioLang: String? {
        get { DreamStore.shared.get(String.self, "player.audioLang", perProfile: true) }
        set { DreamStore.shared.set(newValue, "player.audioLang", perProfile: true) }
    }
}

public extension Notification.Name {
    /// The watch history changed (a page showing Resume / progress redoes it: PlayFlow.historyStart rules).
    static let dreamHistoryChanged = Notification.Name("dream.historyChanged")
    /// My List changed.
    static let dreamMyListChanged = Notification.Name("dream.myListChanged")
    /// The current profile changed (every per-profile page reloads).
    static let dreamProfileChanged = Notification.Name("dream.profileChanged")
    /// Scraper repositories were added / removed or providers switched (Dream Core drops its earlier scrapes).
    static let dreamScrapersChanged = Notification.Name("dream.scrapersChanged")
    /// A debrid account was linked or unlinked.
    static let dreamAccountsChanged = Notification.Name("dream.accountsChanged")
}

// MARK: Kids levels

/// Kids profiles' maximum rating as one friendly level for movies and shows (KidsProfiles.kt). The catalog's kids
/// filter keeps only what `allows` says at the current profile's level.
public enum KidsLevels {
    public struct Level: Sendable, Hashable {
        public let name: String
        public let movie: String
        public let tv: [String]
    }
    public static let all: [Level] = [
        Level(name: "Little Kids", movie: "G", tv: ["TV-Y", "TV-G"]),
        Level(name: "Kids", movie: "PG", tv: ["TV-Y", "TV-G", "TV-Y7", "TV-PG"]),
        Level(name: "Teens", movie: "PG-13", tv: ["TV-Y", "TV-G", "TV-Y7", "TV-PG", "TV-14"]),
        Level(name: "Everyone", movie: "R", tv: ["TV-Y", "TV-G", "TV-Y7", "TV-PG", "TV-14", "TV-MA"]),
    ]
    /// The last level: no limit.
    public static var everyone: Int { all.count - 1 }
    private static let movieOrder = ["G", "PG", "PG-13", "R", "NC-17"]

    /// A profile's level: its own, else Kids for a kids profile, else no limit.
    public static func level(of p: Profile) -> Int {
        min(max(p.kidsLevel ?? (p.isKids ? 1 : everyone), 0), everyone)
    }
    /// The level a `Profile.ratingLimit` names (its movie rating), or Everyone.
    public static func level(forLimit limit: String?) -> Int {
        guard let limit, let i = all.firstIndex(where: { $0.movie == limit.uppercased() }) else { return everyone }
        return i
    }
    /// "Kids · PG, TV-PG"
    public static func text(_ i: Int) -> String { let l = all[min(max(i, 0), everyone)]; return "\(l.name) · \(l.movie), \(l.tv.last ?? "")" }

    /// Whether [cert] (a US certification, movie or TV) is allowed at [level]. Unknown or unrated: only for Everyone.
    public static func allows(level: Int, cert: String?, tv: Bool) -> Bool {
        if level >= everyone { return true }
        let c = (cert ?? "").trimmingCharacters(in: .whitespaces).uppercased()
        if c.isEmpty || c == "NR" || c == "UNRATED" { return false }
        let l = all[max(level, 0)]
        if tv { return l.tv.contains(c) }
        guard let i = movieOrder.firstIndex(of: c), let max = movieOrder.firstIndex(of: l.movie) else { return false }
        return i <= max
    }
}
