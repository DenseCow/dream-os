import Foundation

/// Release-name words shared by Dream Core's pick, the availability pills and the source badges (fire-home
/// NativeResolver CAM_WORDS / JUNK_WORDS, 2026-10-29), so a CAM can't count as a real copy in one place and not another.
public enum ReleaseWords {
    /// Camera, telesync, telecine and screener releases, whatever the name calls them. TamilMV's "HQ PreDVD" and
    /// "HQ Pre" are theater recordings sold as 1080p; "KinoRip" is the Czech word for the same.
    public static let cam = ["CAM", "HDCAM", "HQCAM", "CAMRIP", "CAM-RIP", "TS", "HDTS", "TSRIP", "HDTSRIP", "TELESYNC",
                             "TC", "HDTC", "TELECINE", "SCR", "DVDSCR", "HDTSCR", "SCREENER", "PDVD", "PREDVD", "PRE-DVD",
                             "PREDVDRIP", "HQ PRE", "KINORIP"]

    /// Not the movie at all: recap and review videos, betting-site ads posing as rips.
    public static let junk = ["RECAP", "REVIEW", "1XBET", "4RABET", "4RABETRIP", "FLIXMOMO"]

    /// The label a CAM copy gets on the Choose a Source list and the loading note, most specific first.
    static let camLabels = ["HDCAM", "CAMRIP", "CAM", "HDTS", "TELESYNC", "TS", "HDTC", "TELECINE", "TC", "DVDSCR", "SCREENER",
                            "SCR", "PDVD", "PREDVD", "PRE-DVD", "HQ PRE", "KINORIP"]

    /// A camera or screener copy, by its torrent / file name (whole words, any case; a title word like "CAMERA" isn't).
    public static func isCam(_ name: String) -> Bool { has(strip(name), cam) }

    /// A recap, review or betting-site video: never a source.
    public static func isJunk(_ name: String) -> Bool { has(strip(name), junk) }

    /// "HDCAM", "TS", "HQ PRE"... for a CAM copy's name; "CAM" when none is named.
    public static func camLabel(_ name: String) -> String {
        let t = strip(name)
        return camLabels.first { has(t, [$0]) } ?? "CAM"
    }

    /// [words] in a badge tag (upper case, every separator one space, padded: " HQ PRE DVD ").
    public static func inTag(_ tag: String, _ words: [String]) -> Bool {
        words.contains { tag.contains(" " + $0.replacingOccurrences(of: "-", with: " ") + " ") }
    }

    /// Any of [words] as a whole token in [text]; a space in a word matches any separator ("HQ.Pre", "HQ_PRE").
    public static func has(_ text: String, _ words: [String]) -> Bool {
        guard !words.isEmpty, let re = regex(words) else { return false }
        let u = text.uppercased()
        return re.firstMatch(in: u, range: NSRange(u.startIndex..., in: u)) != nil
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var compiled: [[String]: NSRegularExpression] = [:]

    /// One compiled regex per word list (the ranking asks for every scraped name).
    private static func regex(_ words: [String]) -> NSRegularExpression? {
        if let r = lock.withLock({ compiled[words] }) { return r }
        let alts = words.map { w in w.split(separator: " ").map { NSRegularExpression.escapedPattern(for: String($0)) }.joined(separator: "[ ._-]") }
        guard let r = try? NSRegularExpression(pattern: "(?<![A-Z0-9])(?:" + alts.joined(separator: "|") + ")(?![A-Z0-9])") else { return nil }
        lock.withLock { compiled[words] = r }
        return r
    }

    /// A video extension at the end goes first: "Movie.ts" is a container, not a telesync.
    private static func strip(_ name: String) -> String {
        for ext in [".ts", ".mkv", ".mp4", ".m4v", ".avi", ".mov", ".webm"] where name.lowercased().hasSuffix(ext) {
            return String(name.dropLast(ext.count))
        }
        return name
    }
}
