import Foundation

// People for the person page and X-Ray (gaps worker, additive). PlayerXRay.kt `XRayData`: who is in what's playing
// (an episode's own cast and guest stars), and one person's facts, biography and best-known titles.
// The requirements are declared in `CatalogService` (Services.swift); the defaults below keep every module building.

/// One person (XRayData.Person): "January 5, 1970 – January 1, 2020  ·  Place" / "Born January 5, 1970", the biography, and their
/// best-known titles (combined credits, no self / host / narrator roles, no talk or news shows, posters only, by
/// votes: only titles with 20+ votes when at least four have them, 14 at most).
public struct PersonDetails: Codable, Sendable, Hashable, Identifiable {
    public var id: Int
    public var name: String
    public var profilePath: String?
    /// Born / died and birthplace, "" when TMDB has none.
    public var meta: String
    /// The whole biography ("" when none); a card shows `PersonDetails.bioLead` of it.
    public var bio: String
    public var knownFor: [Title]
    /// "Acting", "Directing", ...
    public var department: String
    public init(id: Int, name: String, profilePath: String? = nil, meta: String = "", bio: String = "", knownFor: [Title] = [],
                department: String = "") {
        self.id = id; self.name = name; self.profilePath = profilePath; self.meta = meta; self.bio = bio
        self.knownFor = knownFor; self.department = department
    }
    public func profile(_ size: String = "w342") -> URL? { TMDBImage.url(profilePath, size) }

    /// The start of the biography ending on a whole sentence, at most about [maxChars] characters (PlayerXRay's
    /// fitBio: the longest run of full sentences that fits; when even the first is longer, whole words and "…").
    /// A sentence ends at . ! ? (maybe a closing quote or bracket) and a space, never after a lone initial ("J. ").
    public static func bioLead(_ full: String, maxChars: Int = 260) -> String {
        let text = full.replacingOccurrences(of: "\\s*\\n+\\s*", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
        if text.count <= maxChars { return text }
        let head = String(text.prefix(maxChars))
        let ns = (head + " ") as NSString
        let re = try? NSRegularExpression(pattern: "[.!?][\"')\\]”’]?\\s")
        var end: Int?
        for m in re?.matches(in: ns as String, range: NSRange(location: 0, length: ns.length)) ?? [] {
            let at = m.range.location
            let before = at >= 2 ? ns.substring(with: NSRange(location: at - 2, length: 2)) : ""
            // "J." after a space: an initial, not a sentence's end.
            if before.count == 2, before.first == " ", before.last?.isUppercase == true { continue }
            end = m.range.location + m.range.length - 1
        }
        if let end, end >= 40 { return ns.substring(to: end).trimmingCharacters(in: .whitespaces) }
        var words = head.split(separator: " ")
        if words.count > 1 { words.removeLast() }
        return words.joined(separator: " ").trimmingCharacters(in: CharacterSet(charactersIn: ",;:-— ")) + "…"
    }
}

public extension CatalogService {
    /// Default: no facts (the person page shows their titles only).
    func personDetails(id: Int, excluding titleKey: String?) async -> PersonDetails? { nil }
    /// Default: the title's own cast from its details (no per-episode credits).
    func cast(for request: PlayRequest) async -> [CastMember] {
        (try? await details(kind: request.title.kind, id: request.title.id))?.cast ?? []
    }
}

/// Followed-show news the app reacts to (gaps worker, additive). A My List movie that turned Available Now posts
/// "Now Available in 4K" (Availability.kt `nowAvailable`); DreamContent's availability checker calls `nowAvailable`
/// when a cached answer flips to Available Now, the app sets `onNowAvailable` at launch.
public enum AvailabilityNews {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var handler: (@Sendable (_ title: Title, _ best: String?) -> Void)?

    /// Set once by the app (DreamOS): receives every title whose answer went from not available to Available Now.
    public static var onNowAvailable: (@Sendable (_ title: Title, _ best: String?) -> Void)? {
        get { lock.withLock { handler } }
        set { lock.withLock { handler = newValue } }
    }

    /// Called by the checker: [title] was CAM Only / Not Available Yet and is now Available Now ([best] release name).
    public static func nowAvailable(_ title: Title, best: String?) { onNowAvailable?(title, best) }
}
