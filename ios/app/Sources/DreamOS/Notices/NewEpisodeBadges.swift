import SwiftUI
import Observation
import DreamCore

/// "NEW EPISODE" on show cards in every other row (NewEpisodeSpot.badged, CinemaPages titleCards): the followed shows
/// with an episode Home's New Episode banner would offer. Filled by `NewEpisodeSpot` on each load; a show leaves the set
/// once its episode is played or dismissed. Rows that carry their own episode (Continue Watching, New Episodes for
/// You) and cards that already wear a badge keep theirs.
@MainActor @Observable
final class NewEpisodeBadges {
    static let shared = NewEpisodeBadges()
    static let word = "NEW EPISODE"

    /// TMDB ids of shows whose cards wear the badge (this profile).
    private(set) var shows: Set<Int> = []
    @ObservationIgnored private var profile = DreamStore.shared.profileId

    func set(_ offered: [NewEpisodeInfo]) {
        profile = DreamStore.shared.profileId
        let s = Set(offered.map(\.show.id))
        if s != shows { shows = s }
    }

    func remove(_ showId: Int) {
        if shows.contains(showId) { shows.remove(showId) }
    }

    /// [rows] with the badge on the followed shows' cards.
    func apply(_ rows: [CatalogRow]) -> [CatalogRow] {
        // Another profile's set never shows (the next load refills it).
        guard !shows.isEmpty, profile == DreamStore.shared.profileId else { return rows }
        return rows.map { row in
            guard row.style != .continueWatching, row.items.contains(where: { $0.title.kind == .tv && shows.contains($0.title.id) }) else { return row }
            var r = row
            for i in r.items.indices where r.items[i].title.kind == .tv && r.items[i].badge == nil && r.items[i].episode == nil
                && shows.contains(r.items[i].title.id) {
                r.items[i].badge = Self.word
            }
            return r
        }
    }
}
