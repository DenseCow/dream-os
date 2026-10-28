import Foundation

// Instant pills (docs/port/speed.md "Instant pills"): availability answered ahead of the screen and kept between
// launches. Defaults for the CatalogService additions, and the Play signal background look-aheads step aside for.

public extension CatalogService {
    func availabilityAhead(_ titles: [Title], answer: @escaping @Sendable (String, Availability?) async -> Void) async {}
    func availabilityKnown() async -> [String: Availability] { [:] }
    func mayHavePill(_ title: Title) -> Bool { false }
}

public extension Notification.Name {
    /// A Play was pressed (posted by the app on the main thread): background look-aheads hold off until the player
    /// closes, so a Play never shares the scrapers with them.
    static let dreamPlayStarting = Notification.Name("dream.playStarting")
}
