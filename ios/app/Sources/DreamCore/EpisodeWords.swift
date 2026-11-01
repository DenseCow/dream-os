import Foundation

/// The one place an episode's place in a show is written for a viewer (the Fire TV app's `PlayFlow.episodeCode`).
/// "Season 0" and "S0" appear nowhere a viewer can see.
///
/// A special has no place in the order to write: its air-order number is a view that moves when a source backfills
/// an earlier special, so what a viewer sees is the special's own title, and `code` is nil for one. The word
/// "Specials" names the group (the episode picker, a count), never one special.
public enum EpisodeWords {
    /// What a viewer sees in place of a season for the group of specials.
    public static let specials = "Specials"

    /// "S2 E5", nil for a movie or a special. [separator] goes between the two parts.
    public static func code(_ season: Int?, _ episode: Int?, separator: String = " ") -> String? {
        guard let s = season, let e = episode else { return nil }
        return "S\(s)" + separator + "E\(e)"
    }

    /// What names one episode on a card or a log line: its code for a numbered one, its own title for a special
    /// (falling back to "Special" when a source lists it without a name). Nil for a movie.
    public static func name(_ kind: WatchKind, season: Int?, episode: Int?, title: String?, separator: String = " ") -> String? {
        if kind.isSpecial { return title.flatMap { $0.isEmpty ? nil : $0 } ?? "Special" }
        return code(season, episode, separator: separator)
    }

    /// The line under a title while something plays or is queued: "S2 E5  ·  Name" for an episode, the special's own
    /// name alone for a special, nil for a movie (the caller then shows the year).
    public static func playing(_ kind: WatchKind, season: Int?, episode: Int?, title: String?,
                               separator: String = " ", join: String = "  ·  ") -> String? {
        guard let first = name(kind, season: season, episode: episode, title: title, separator: separator) else { return nil }
        guard kind.isSeries, let t = title, !t.isEmpty else { return first }
        return first + join + t
    }

    /// A badge's season: "S3", or "Specials" for the group.
    public static func short(_ season: Int, special: Bool = false) -> String { special ? specials : "S\(season)" }

    /// A heading or a picker row: "Season 3", or "Specials".
    public static func season(_ season: Int, special: Bool = false) -> String { special ? specials : "Season \(season)" }

    /// Where an episode sits: "Season 3, Episode 7". A special sits outside the order, so it is just "Specials".
    public static func place(_ season: Int, _ episode: Int, special: Bool = false) -> String {
        special ? specials : "Season \(season), Episode \(episode)"
    }

    /// "10 Episodes", "1 Special", "3 Specials".
    public static func count(_ n: Int, special: Bool = false) -> String {
        if special { return n == 1 ? "1 Special" : "\(n) Specials" }
        return n == 1 ? "1 Episode" : "\(n) Episodes"
    }
}
