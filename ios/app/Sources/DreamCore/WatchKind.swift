import Foundation

/// What a watch-history row, or something being played, is. Three-valued on purpose: a special is its own kind, and
/// without a third case it falls into a movie's or an episode's branch by default and is misfiled (the Fire TV app's
/// `isShow` boolean did exactly that in three places). Switch over it so a missed case is a compile error, and never
/// ask "is this a show" without saying which half you meant.
///
/// A special behaves like a movie that belongs to a show: its own Continue Watching card with its own progress, off
/// the row when finished, never Up Next, never rolling on to a next episode, and the series' own position untouched
/// (docs/port/specials.md).
public enum WatchKind: String, Codable, Sendable, Hashable, CaseIterable {
    case movie, episode, special

    /// A numbered episode: the only kind with a place in the show's order. Series-position rules ask for this one.
    public var isSeries: Bool { self == .episode }
    public var isSpecial: Bool { self == .special }
    /// Part of a show, numbered or not. Presentation, taste and row building ask for this one.
    public var ofShow: Bool { self != .movie }

    /// Where the watch history keeps a row. A movie's and a numbered episode's is the title's own key, unchanged; a
    /// special gets its own, so it never takes the show's place on the row.
    public static func historyKey(title: Title, kind: WatchKind, specialId: String?) -> String {
        guard kind == .special, let id = specialId, !id.isEmpty else { return title.key }
        return "\(title.key)/sp:\(id)"
    }
}

public extension HistoryEntry {
    /// The row as an episode a card can show, nil for a movie. The one conversion, so two places that build a
    /// Continue Watching card can't come to different conclusions about what a special is.
    var asEpisode: Episode? {
        switch kind {
        case .movie:
            return nil
        case .special:
            var e = Episode(showId: title.id, season: 0, number: 0, name: episodeName ?? "", stillPath: stillPath)
            e.isSpecial = true
            e.specialId = specialId ?? ""
            return e
        case .episode:
            guard let s = season, let n = episode else { return nil }
            return Episode(showId: title.id, season: s, number: n, name: episodeName ?? "", stillPath: stillPath)
        }
    }
}

public extension Array where Element == HistoryEntry {
    /// The show's own row: where the viewer is in the numbered episodes. What every series-position rule wants (the
    /// caught-up rule, the new-episode banner, Play's words), never a special's row.
    func series(_ showId: Int) -> HistoryEntry? {
        first { $0.title.kind == .tv && $0.title.id == showId && $0.kind.isSeries }
    }

    /// One special's own row. Every id it answers to is accepted (`Episode.specialIds`), because a row written before
    /// a source backfilled the special carries the old id.
    func special(_ showId: Int, anyOf ids: [String]) -> HistoryEntry? {
        guard !ids.isEmpty else { return nil }
        return first { $0.title.id == showId && $0.kind.isSpecial && $0.specialId.map(ids.contains) == true }
    }

    func special(_ showId: Int, _ specialId: String) -> HistoryEntry? { special(showId, anyOf: [specialId]) }

    /// The row a playback request resumes from: the special's own for a special, else the title's.
    func forRequest(_ r: PlayRequest) -> HistoryEntry? {
        if r.isSpecial { return special(r.title.id, anyOf: r.specialIds) }
        return first { $0.key == r.title.key }
    }
}
