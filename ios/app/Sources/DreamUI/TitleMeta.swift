import Foundation
import DreamCore

/// The quiet lines under and over titles (CinemaKit.subline / meta), from list data alone (no lookups).
public enum TitleMeta {
    /// "2024 · Series": the line under a card. Cards show the year only (owner, 2026-10-28); the title page shows the
    /// full release date.
    public static func subline(_ t: Title) -> String {
        [t.year.isEmpty ? nil : t.year, t.kind == .tv ? "Series" : "Movie"].compactMap { $0 }.joined(separator: " · ")
    }

    /// "2024  ·  Series  ·  Drama  ·  Comedy": the hero's and big cards' meta line (year only, as on the cards).
    public static func meta(_ t: Title, genres: Int = 2) -> String {
        let g = t.genreIds.compactMap { genreName(t.kind, $0) }.prefix(genres)
        return ([t.year.isEmpty ? nil : t.year, t.kind == .tv ? "Series" : "Movie"].compactMap { $0 } + g).joined(separator: Cinema.dot)
    }

    /// TMDB's genre names (Discover.genreName).
    public static func genreName(_ kind: MediaKind, _ id: Int) -> String? {
        kind == .tv ? (tvGenres[id] ?? movieGenres[id]) : (movieGenres[id] ?? tvGenres[id])
    }

    static let movieGenres: [Int: String] = [
        28: "Action", 12: "Adventure", 16: "Animation", 35: "Comedy", 80: "Crime", 99: "Documentary", 18: "Drama",
        10751: "Family", 14: "Fantasy", 36: "History", 27: "Horror", 10402: "Music", 9648: "Mystery", 10749: "Romance",
        878: "Sci-Fi", 10770: "TV Movie", 53: "Thriller", 10752: "War", 37: "Western",
    ]
    static let tvGenres: [Int: String] = [
        10759: "Action & Adventure", 16: "Animation", 35: "Comedy", 80: "Crime", 99: "Documentary", 18: "Drama",
        10751: "Family", 10762: "Kids", 9648: "Mystery", 10763: "News", 10764: "Reality", 10765: "Sci-Fi & Fantasy",
        10766: "Soap", 10767: "Talk", 10768: "War & Politics", 37: "Western",
    ]

    /// Continue Watching's line: "Up Next · S1 · E4", "S2 · E5", "42 min left", else the subline.
    public static func continueLine(_ e: HistoryEntry) -> String {
        if e.title.kind == .tv, let s = e.season, let n = e.episode, s > 0, n > 0 {
            return (e.upNext ? "Up Next · " : "") + "S\(s) · E\(n)"
        }
        if e.durationMs > 0 {
            let left = (e.durationMs - e.positionMs) / 60_000
            if left > 0 { return "\(left) min left" }
        }
        return subline(e.title)
    }

    /// "The Office" files under O, "A Quiet Place" under Q (My List's A-Z).
    public static func sortName(_ n: String) -> String {
        let s = n.trimmingCharacters(in: .whitespaces)
        for a in ["the ", "a ", "an "] where s.count > a.count && s.lowercased().hasPrefix(a) { return String(s.dropFirst(a.count)) }
        return s
    }
}
