import Foundation

// New Episodes for the Notices worker (NewEpisodeBanner.kt / NewEpisodeSpot): the followed shows' recent and upcoming
// episodes as one snapshot. Additive: `CatalogService.newEpisodes()` has a default (nothing) below.

/// One followed show's episode (NewEpisodes.kt `Item`).
public struct NewEpisodeInfo: Codable, Sendable, Hashable, Identifiable {
    public var show: Title
    public var season: Int
    /// The first episode of the drop; `lastNumber` > `number` for a batch released the same day.
    public var number: Int
    public var lastNumber: Int
    public var name: String
    /// "yyyy-MM-dd" (date only, never shifted).
    public var airDate: String
    /// The exact instant it airs, when known.
    public var at: Date?
    public var stillPath: String?
    public var overview: String
    public var upcoming: Bool
    /// A special only: its source id (`Episode.specialId`), which is what marks this item as one.
    public var specialId: String?
    /// A special's other source id (`Episode.specialAlias`).
    public var specialAlias: String?
    /// Numbered episode or special; a new-episode item is never a movie.
    public var kind: WatchKind { specialId != nil ? .special : .episode }
    /// Every id a special answers to, preferred first.
    public var specialIds: [String] { [specialId, specialAlias].compactMap { $0 }.filter { !$0.isEmpty } }
    /// "1396:s5e14", or "1396/sp:tmdb:4921" for a special: the key the "NEW" badge and the banner's marks use. A
    /// special's is its source id, never its air-order place, which moves when a source backfills an earlier one.
    public var key: String { specialId.map { "\(show.id)/sp:\($0)" } ?? "\(show.id):s\(season)e\(number)" }
    /// Every key this item has been remembered under: a special's alias too, so one already seen under the other
    /// source's id is not announced again as new.
    public var keys: [String] { kind.isSpecial ? specialIds.map { "\(show.id)/sp:\($0)" } : [key] }
    public var id: String { key }

    public init(show: Title, season: Int, number: Int, lastNumber: Int, name: String, airDate: String, at: Date? = nil,
                stillPath: String? = nil, overview: String = "", upcoming: Bool = false, specialId: String? = nil,
                specialAlias: String? = nil) {
        self.show = show; self.season = season; self.number = number; self.lastNumber = lastNumber; self.name = name
        self.airDate = airDate; self.at = at; self.stillPath = stillPath; self.overview = overview; self.upcoming = upcoming
        self.specialId = specialId; self.specialAlias = specialAlias
    }

    /// The episode as cards and Play see it.
    public var episode: Episode {
        var e = Episode(showId: show.id, season: season, number: number, name: name, overview: overview, stillPath: stillPath,
                        airDate: airDate, airsAt: at, upcoming: upcoming)
        if let id = specialId {
            e.isSpecial = true
            e.specialId = id
            e.specialAlias = specialAlias ?? ""
        }
        return e
    }
}

/// What a New Episodes load found (NewEpisodes.kt `Snapshot`), for the current profile.
public struct NewEpisodesSnapshot: Sendable, Hashable {
    /// Aired in the last week, newest first.
    public var recent: [NewEpisodeInfo]
    /// Coming in the next month, soonest first.
    public var upcoming: [NewEpisodeInfo]
    /// The followed shows' TMDB ids (My List shows, then watched shows).
    public var followedIds: [Int]
    public init(recent: [NewEpisodeInfo] = [], upcoming: [NewEpisodeInfo] = [], followedIds: [Int] = []) {
        self.recent = recent; self.upcoming = upcoming; self.followedIds = followedIds
    }
    /// The soonest exact air instant still ahead (worth a look right after it), nil when none is known.
    public var nextAt: Date? { upcoming.compactMap(\.at).filter { $0 > Date() }.min() }
}

public extension CatalogService {
    func newEpisodes() async -> NewEpisodesSnapshot { NewEpisodesSnapshot() }
}
