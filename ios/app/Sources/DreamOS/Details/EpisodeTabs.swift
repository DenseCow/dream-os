import Foundation
import DreamCore

/// Which group of a show's episodes the title page and the player's list are showing: one numbered season, or the
/// show's specials. A case rather than a season number, so "nothing picked yet" needs no sentinel and specials are
/// never a season (docs/port/specials.md).
enum EpisodeTab: Hashable {
    case season(Int)
    case specials

    /// What the picker uses as a row id, and what the episode cache is keyed on.
    var id: String {
        switch self {
        case .season(let n): return "s\(n)"
        case .specials: return "sp"
        }
    }

    init?(id: String) {
        if id == "sp" { self = .specials; return }
        guard id.hasPrefix("s"), let n = Int(id.dropFirst()) else { return nil }
        self = .season(n)
    }

    var isSpecials: Bool { self == .specials }
    /// The season number, nil for the specials.
    var seasonNumber: Int? {
        switch self {
        case .season(let n): return n
        case .specials: return nil
        }
    }
}

/// One row of the episode picker. The specials row is always last, after the seasons that are out and the ones still
/// to come, so the season on show stays Season 1 and Play keeps naming a real episode.
struct EpisodeTabItem: Identifiable, Hashable {
    let tab: EpisodeTab
    /// "Season 2", "Limited Series", "Specials".
    let label: String
    /// How many the source lists, 0 when it hasn't said yet.
    let count: Int
    /// "yyyy-MM-dd" the season starts, "" for the specials and for a season with no date.
    let airDate: String
    let posterPath: String?

    var id: String { tab.id }

    /// A numbered season, named by its own short name when it has one ("Limited Series").
    init(_ s: SeasonSummary) {
        tab = .season(s.number)
        let n = s.name
        label = (!n.isEmpty && !n.hasPrefix("Season") && n.count <= 22) ? n : EpisodeWords.season(s.number)
        count = s.episodeCount
        airDate = s.airDate
        posterPath = s.posterPath
    }

    /// The specials row.
    init(specials count: Int) {
        tab = .specials
        label = EpisodeWords.specials
        self.count = count
        airDate = ""
        posterPath = nil
    }

    /// "10 Episodes", "3 Specials", or "Coming Mar 4" for an announced season with nothing listed yet. [loaded] is
    /// the real count of the group on show (nil for the others: the source's own).
    func detail(ahead: Bool, loaded: Int?) -> String {
        if ahead && (loaded ?? 0) == 0 { return PageTime.shortDate(airDate).map { "Coming \($0)" } ?? "Coming Soon" }
        return EpisodeWords.count(loaded ?? count, special: tab.isSpecials)
    }
}
