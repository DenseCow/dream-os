import Foundation

// Instant pills (docs/port/speed.md "Instant pills"): availability answered ahead of the screen and kept between
// launches. Defaults for the CatalogService additions, and the Play signal background look-aheads step aside for.

public extension CatalogService {
    func availabilityAhead(_ titles: [Title], answer: @escaping @Sendable (String, Availability?) async -> Void) async {}
    func availabilityKnown() async -> [String: Availability] { [:] }
    func mayHavePill(_ title: Title) -> Bool { false }
    /// The answer a card already showed for [title] (with its best copy, for "Available in 1080p"), kept from earlier
    /// checks of any age; no network. A title page shows it on its first frame, then `availabilityDetail` refreshes it.
    func availabilityPeek(for title: Title) async -> AvailabilityDetail? { nil }
}

public extension Notification.Name {
    /// A Play was pressed (posted by the app on the main thread): background look-aheads hold off until the player
    /// closes, so a Play never shares the scrapers with them.
    static let dreamPlayStarting = Notification.Name("dream.playStarting")
    /// Dream Core found a link for a real release, not a CAM copy (Instant Play, the next episode, a Play). userInfo:
    /// "key" ("movie:603", "tv:1396:1:2"), "best" ("1080p WEB-DL"), "title" (`Title`). A pill that said otherwise
    /// flips to Available Now without waiting for its next check.
    static let dreamStreamFound = Notification.Name("dream.streamFound")
}
