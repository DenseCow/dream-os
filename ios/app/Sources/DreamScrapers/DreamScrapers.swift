import DreamCore

/// Module entry: registers this module's real services in `Services.shared` at launch.
/// The settings pages use `Scrapers` (status, add / update / remove, provider switches, test).
public enum DreamScrapers {
    public static func register() {
        Services.shared.sources = ScraperSources.shared
    }
}
