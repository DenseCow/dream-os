import DreamCore

/// Module entry: registers this module's real services in `Services.shared` at launch.
public enum DreamContent {
    public static func register() {
        Services.shared.catalog = ContentCatalog()
    }
}
