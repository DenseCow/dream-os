import Foundation
import DreamCore

// Notices worker addition (additive): New Episodes as the shared snapshot Home's New Episode banner and its notice read
// (`CatalogService.newEpisodes`). Same load as the New Episodes for You row (NewEpisodes.shared).

extension ContentCatalog {
    func newEpisodes() async -> NewEpisodesSnapshot {
        let snap = await NewEpisodes.shared.load()
        let followed = await NewEpisodes.shared.followed().map(\.id)
        func info(_ i: NewEpisodes.Item) -> NewEpisodeInfo {
            NewEpisodeInfo(show: i.show, season: i.season, number: i.number, lastNumber: i.lastNumber, name: i.name, airDate: i.airDate,
                           at: i.at, stillPath: i.still, overview: i.overview, upcoming: i.upcoming)
        }
        return NewEpisodesSnapshot(recent: snap.recent.map(info), upcoming: snap.upcoming.map(info), followedIds: followed)
    }
}
