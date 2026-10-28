import Foundation
import DreamCore

// Title page wording helpers. The Information grid's cells, runtimes and money come from the Content worker's
// `TitleInfo.cells` (DreamCore/ContentContract.swift); dates go through `DreamTime`. These are the few page-only bits.

enum PageTime {
    static func todayYmd() -> String { DreamTime.todayYmd() }

    static func longDate(_ ymd: String?) -> String? { DreamTime.longDate(ymd) }

    /// "Nov 14" this year, "Nov 14, 2027" otherwise.
    static func shortDate(_ ymd: String?) -> String? {
        guard let ymd, let p = DreamTime.parts(ymd) else { return nil }
        return DreamTime.formatDay(ymd, p.y == DreamTime.thisYear() ? "MMM d" : "MMM d, yyyy")
    }

    static func isAhead(_ ymd: String?) -> Bool { DreamTime.isAhead(ymd) }

    /// Out already (a date-only day, never shifted).
    static func aired(_ ymd: String?) -> Bool {
        guard let ymd, ymd.count >= 10 else { return false }
        return String(ymd.prefix(10)) <= DreamTime.todayYmd()
    }

    /// Aired in the last 21 days (Availability.recentEpisode): episode pills expire by themselves.
    static func recentEpisode(_ ymd: String?) -> Bool {
        guard let ymd, aired(ymd), let d = DreamTime.daysFromToday(String(ymd.prefix(10))) else { return false }
        return d >= -21
    }
}

enum InfoWords {
    static func runtime(_ min: Int) -> String? { TitleInfo.runtimeWords(min) }

    /// "Coming March 1, 2027" for a day still ahead, else nil.
    static func coming(_ ymd: String?) -> String? {
        PageTime.isAhead(ymd) ? PageTime.longDate(ymd).map { "Coming \($0)" } : nil
    }

    /// Provider names, each once, never YouTube (STANDARDS §1) except YouTube TV, a real streaming service (owner,
    /// 2026-10-28: the no-YouTube rule covers trailers).
    static func cleanProviders(_ names: [String]) -> [String] {
        var seen = Set<String>()
        return names.filter { n in
            let youTube = n.lowercased().contains("youtube") && n.lowercased() != "youtube tv"
            guard !n.isEmpty, !youTube, !seen.contains(n) else { return false }
            seen.insert(n)
            return true
        }
    }

    /// The info with every provider list cleaned (cleanProviders).
    static func cleaned(_ info: TitleInfo) -> TitleInfo {
        var i = info
        i.streaming = cleanProviders(i.streaming)
        i.free = cleanProviders(i.free)
        i.rentBuy = cleanProviders(i.rentBuy)
        return i
    }

    /// Rows of `cols` units in reading order; a later one-column cell fills a hole a wide cell left (InfoGrid.pack).
    static func pack(_ cells: [TitleInfo.Cell], cols: Int) -> [[TitleInfo.Cell]] {
        var left = cells
        var rows: [[TitleInfo.Cell]] = []
        while !left.isEmpty {
            var row: [TitleInfo.Cell] = []
            var used = 0
            var i = 0
            while i < left.count && used < cols {
                let s = min(max(left[i].span, 1), cols)
                if used + s <= cols { row.append(left.remove(at: i)); used += s } else { i += 1 }
            }
            rows.append(row)
        }
        return rows
    }

    /// The median of the positive values (a season's episode length).
    static func median(_ v: [Int]) -> Int {
        let s = v.filter { $0 > 0 }.sorted()
        guard !s.isEmpty else { return 0 }
        let m = s.count / 2
        return s.count % 2 == 1 ? s[m] : Int((Double(s[m - 1] + s[m]) / 2).rounded())
    }
}
