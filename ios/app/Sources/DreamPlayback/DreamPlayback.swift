import DreamCore

/// Module entry: registers this module's real services in `Services.shared` at launch.
public enum DreamPlayback {
    public static func register() {
        _ = Profiles.shared // sets DreamStore.shared.profileId to the current profile before anything reads per-profile data
        _ = NativeResolver.shared // the ledger's "in use" links, the scrapers-changed observer
        Services.shared.profiles = DreamProfileService()
        Services.shared.history = DreamHistoryService()
        WatchedLedger.seed = { WatchHistory.shared.watchedSeeds() } // the Watched pill starts from what the history knows
        Services.shared.myList = DreamMyListService()
        Services.shared.playback = DreamCorePlayback()
        Services.shared.lookAhead = PlayAhead.shared // Instant Play: title pages and Continue Watching resolve ahead
        Trakt.shared.install() // Trakt sync: history, My List, Dream Player's scrobble, New Episodes (Trakt/)
        PlayAhead.shared.start()
    }

    /// The app came to the front: the premium check when it's due (at most every six hours; network only then).
    public static func appActive() { DebridExpiry.maybeCheck(); PlayAhead.shared.appActive() }
}
