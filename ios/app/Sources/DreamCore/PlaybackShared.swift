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

// MARK: Stream badges

/// What's actually playing, as small badges: "4K", "Dolby Vision", "Dolby Atmos", "WEB-DL" (StreamBadges.kt).
/// Picture and sound come from what the player decodes; the source type from the release name Dream Core picked
/// (`StreamLink.fileName`), else the stream URL's own file name.
public enum StreamBadges {
    public struct Badge: Sendable, Hashable {
        public let text: String
        /// CAM: shown in the amber of "CAM Only".
        public let warn: Bool
    }
    public struct Facts: Sendable, Hashable {
        public let badges: [Badge]
        /// "4.2 GB", for the Pause Screen; nil when unknown.
        public let size: String?
        public var summary: String { badges.map(\.text).joined(separator: " · ") }
    }
    /// The video the player decodes. [codec]: mpv's codec name ("hevc", "h264", "av1"); [transfer]: mpv's
    /// `video-params/gamma` ("pq", "hlg", "bt.1886"...); [dolbyVision]: the player knows it is Dolby Vision.
    public struct Video: Sendable, Hashable {
        public var width: Int, height: Int, codec: String?, transfer: String?, dolbyVision: Bool
        public init(width: Int, height: Int, codec: String? = nil, transfer: String? = nil, dolbyVision: Bool = false) {
            self.width = width; self.height = height; self.codec = codec; self.transfer = transfer; self.dolbyVision = dolbyVision
        }
    }
    /// The audio the player decodes. [codec]: mpv's codec name ("eac3", "truehd", "ac3", "dts", "aac"...);
    /// [profile]: the codec profile when known ("DTS-HD MA", "DTS:X", "E-AC-3 JOC"); [channels]: 0 = unknown.
    public struct Audio: Sendable, Hashable {
        public var codec: String?, profile: String?, channels: Int
        public init(codec: String? = nil, profile: String? = nil, channels: Int = 0) { self.codec = codec; self.profile = profile; self.channels = channels }
    }

    /// A release name as people read it (SourcePicker.prettyName): no extension, dots and underscores between words as
    /// spaces (kept inside numbers: 5.1, H.264), e.g. "Breaking Bad S01E01 1080p BluRay x264-ROVERS".
    public static func prettyName(_ name: String) -> String {
        stripExt(name).replacingOccurrences(of: "(?<![0-9])[._]|[._](?![0-9])", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
    }

    /// "4.2 GB" / "700 MB" for the source list; "" when unknown.
    public static func sizeLabel(_ bytes: Int64) -> String { bytes > 0 ? sizeText(bytes) : "" }

    /// The badges for what plays now; nil before anything is known.
    public static func facts(releaseName: String?, url: URL?, sizeBytes: Int64 = 0, video: Video?, audio: Audio?) -> Facts? {
        let name = releaseName.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 } ?? url.flatMap { fileName($0) }
        let tags = name.map { " " + stripExt($0).uppercased().replacingOccurrences(of: "[^A-Z0-9+]+", with: " ", options: .regularExpression) + " " } ?? ""
        var out: [Badge] = []
        if let r = resolution(video) { out.append(Badge(text: r, warn: false)) }
        if out.isEmpty && audio == nil { return nil }
        if let h = hdr(video, tags) { out.append(Badge(text: h, warn: false)) }
        if let a = sound(audio, tags) { out.append(Badge(text: a, warn: false)) }
        if let s = source(tags) { out.append(Badge(text: s, warn: s == "CAM")) }
        return Facts(badges: out, size: sizeBytes > 0 ? sizeText(sizeBytes) : nil)
    }

    /// The source type a release name says ("Remux", "BluRay", "WEBRip", "WEB-DL", "HDTV", "CAM", "DVD"), or nil.
    public static func sourceType(_ releaseName: String) -> String? {
        source(" " + stripExt(releaseName).uppercased().replacingOccurrences(of: "[^A-Z0-9+]+", with: " ", options: .regularExpression) + " ")
    }

    static func resolution(_ v: Video?) -> String? {
        guard let v, v.height > 0 else { return nil }
        let w = v.width, h = v.height
        if h >= 2000 || w >= 3200 { return "4K" }
        if h >= 1000 || w >= 1800 { return "1080p" }
        if h >= 700 || w >= 1200 { return "720p" }
        return "SD"
    }

    static func hdr(_ v: Video?, _ tags: String) -> String? {
        if let v {
            let c = (v.codec ?? "").lowercased()
            let t = (v.transfer ?? "").lowercased()
            if v.dolbyVision || c.hasPrefix("dvh") || c.hasPrefix("dav1") || c.contains("dolby") { return "Dolby Vision" }
            if t == "pq" || t.contains("2084") { return has(tags, "HDR10+", "HDR10PLUS") ? "HDR10+" : "HDR10" }
            if t == "hlg" || t.contains("b67") { return "HLG" }
            if ["bt.1886", "srgb", "gamma2.2", "gamma2.4", "linear", "sdr"].contains(t) { return nil } // the stream says SDR
        }
        // The container didn't say: the release name does.
        if has(tags, "DV", "DOVI", "DOLBY VISION") { return "Dolby Vision" }
        if has(tags, "HDR10+", "HDR10PLUS") { return "HDR10+" }
        if has(tags, "HDR", "HDR10") { return "HDR10" }
        if has(tags, "HLG") { return "HLG" }
        return nil
    }

    static func channels(_ n: Int) -> String? {
        switch n {
        case ...0: return nil
        case 1: return "Mono"
        case 2: return "Stereo"
        case 3: return "2.1"
        case 6: return "5.1"
        case 7: return "6.1"
        case 8: return "7.1"
        default: return "\(n)ch"
        }
    }

    static func sound(_ a: Audio?, _ tags: String) -> String? {
        guard let a else { return nil }
        let ch = channels(a.channels)
        // Matroska doesn't flag Atmos (E-AC3 JOC / TrueHD): the release name does.
        let atmos = has(tags, "ATMOS") || (a.profile ?? "").localizedCaseInsensitiveContains("atmos")
        func with(_ name: String) -> String { [name, ch].compactMap { $0 }.joined(separator: " ") }
        let c = (a.codec ?? "").lowercased()
        let p = (a.profile ?? "").lowercased()
        if c == "eac3" || c == "e-ac-3" {
            if p.contains("joc") || atmos { return "Dolby Atmos" }
            return with("Dolby Digital Plus")
        }
        if c == "truehd" || c == "mlp" { return atmos ? "Dolby Atmos" : with("Dolby TrueHD") }
        if c == "ac3" { return with("Dolby Digital") }
        if c == "ac4" { return with("Dolby AC-4") }
        if c.hasPrefix("dts") {
            if p.contains("dts:x") || p.contains("dts-x") || p.contains("dtsx") { return "DTS:X" }
            if p.contains("hd") || p.contains("ma") || c.contains("hd") { return with("DTS-HD") }
            return with("DTS")
        }
        return ch
    }

    static func source(_ tags: String) -> String? {
        if tags.trimmingCharacters(in: .whitespaces).isEmpty { return nil }
        if has(tags, "REMUX", "BDREMUX") { return "Remux" }
        if has(tags, "BLURAY", "BLU RAY", "BDRIP", "BRRIP", "BD25", "BD50") { return "BluRay" }
        if has(tags, "WEBRIP", "WEB RIP") { return "WEBRip" }
        if has(tags, "WEB DL", "WEBDL", "WEB") { return "WEB-DL" }
        if has(tags, "HDTV") { return "HDTV" }
        if has(tags, "CAM", "HDCAM", "CAMRIP", "TS", "HDTS", "TELESYNC", "TC", "HDTC", "TELECINE", "SCR", "SCREENER", "DVDSCR") { return "CAM" }
        if has(tags, "DVDRIP", "DVD") { return "DVD" }
        return nil
    }

    private static func has(_ tags: String, _ words: String...) -> Bool { words.contains { tags.contains(" \($0) ") } }

    private static let videoExt: Set<String> = ["mkv", "mp4", "m4v", "avi", "mov", "webm", "ts"]
    private static func isVideo(_ name: String) -> Bool {
        guard let dot = name.lastIndex(of: ".") else { return false }
        return videoExt.contains(name[name.index(after: dot)...].lowercased())
    }
    /// "Name.2160p.WEB-DL.mkv" -> "Name.2160p.WEB-DL" (a torrent name without an extension keeps its last tag).
    private static func stripExt(_ name: String) -> String {
        guard isVideo(name), let dot = name.lastIndex(of: ".") else { return name }
        return String(name[..<dot])
    }
    private static func fileName(_ url: URL) -> String? {
        let last = url.lastPathComponent.removingPercentEncoding ?? url.lastPathComponent
        return isVideo(last) ? last : nil
    }
    static func sizeText(_ b: Int64) -> String {
        let gb = Double(b) / 1_073_741_824
        return gb >= 1 ? String(format: "%.1f GB", gb) : "\(b >> 20) MB"
    }
}
