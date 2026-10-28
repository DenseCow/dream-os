import Foundation

/*
 * Seasonal & event hubs, the calendar half: plain Swift (no UI types), so the whole year can be checked
 * off-device as well as on the phone (`SeasonCalendar.selfCheck`). The hubs themselves are `SeasonCatalog`.
 * Ported 1:1 from the Fire TV app's SeasonCalendar.kt.
 */

/// A calendar day (proleptic Gregorian, no time zone): the season system's clock.
struct SDay: Comparable, Hashable, Sendable, CustomStringConvertible {
    /// Days since 1970-01-01.
    let epoch: Int
    let y: Int
    let m: Int
    let d: Int

    private init(epoch: Int) {
        self.epoch = epoch
        // Howard Hinnant's civil_from_days.
        let z = epoch + 719468
        let era = SDay.floorDiv(z, 146097)
        let doe = z - era * 146097
        let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let dd = doy - (153 * mp + 2) / 5 + 1
        let mm = mp < 10 ? mp + 3 : mp - 9
        self.d = dd
        self.m = mm
        self.y = yoe + era * 400 + (mm <= 2 ? 1 : 0)
    }

    /// Month and day as one number (October 26 = 1026), for fixed windows.
    var md: Int { m * 100 + d }

    /// ISO weekday: 1 Monday … 7 Sunday (1970-01-01 was a Thursday).
    var dow: Int { SDay.floorMod(epoch + 3, 7) + 1 }

    static func + (lhs: SDay, days: Int) -> SDay { SDay(epoch: lhs.epoch + days) }
    static func - (lhs: SDay, days: Int) -> SDay { SDay(epoch: lhs.epoch - days) }
    static func += (lhs: inout SDay, days: Int) { lhs = lhs + days }
    static func -= (lhs: inout SDay, days: Int) { lhs = lhs - days }

    /// Days from `o` to this day (positive when this is later).
    func since(_ o: SDay) -> Int { epoch - o.epoch }

    static func < (lhs: SDay, rhs: SDay) -> Bool { lhs.epoch < rhs.epoch }
    static func == (lhs: SDay, rhs: SDay) -> Bool { lhs.epoch == rhs.epoch }
    func hash(into hasher: inout Hasher) { hasher.combine(epoch) }

    /// "yyyy-MM-dd".
    var description: String { "\(SDay.pad(y, 4))-\(SDay.pad(m, 2))-\(SDay.pad(d, 2))" }

    static func of(_ y: Int, _ m: Int, _ d: Int) -> SDay {
        // Howard Hinnant's days_from_civil.
        let yy = m <= 2 ? y - 1 : y
        let era = floorDiv(yy, 400)
        let yoe = yy - era * 400
        let mp = (m + 9) % 12
        let doy = (153 * mp + 2) / 5 + d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return SDay(epoch: era * 146097 + doe - 719468)
    }

    /// "yyyy-MM-dd", or nil.
    static func parse(_ s: String?) -> SDay? {
        guard let s else { return nil }
        let p = s.trimmingCharacters(in: .whitespacesAndNewlines)
            .split(separator: "-", omittingEmptySubsequences: false)
            .compactMap { Int32($0).map(Int.init) }
        guard p.count == 3, (1...12).contains(p[1]), (1...31).contains(p[2]) else { return nil }
        let day = of(p[0], p[1], p[2])
        return day.m == p[1] ? day : nil
    }

    static func lengthOfMonth(_ y: Int, _ m: Int) -> Int {
        (m == 12 ? of(y + 1, 1, 1) : of(y, m + 1, 1)).since(of(y, m, 1))
    }

    /// The `n`th `dow` (1 Monday … 7 Sunday) of month `m`: Thanksgiving is the 4th Thursday of November.
    static func nthWeekday(_ y: Int, _ m: Int, _ dow: Int, _ n: Int) -> SDay {
        let first = of(y, m, 1)
        return first + (floorMod(dow - first.dow, 7) + (n - 1) * 7)
    }

    /// Western (Gregorian) Easter Sunday: the anonymous Gregorian algorithm (Meeus/Jones/Butcher).
    static func easter(_ y: Int) -> SDay {
        let a = y % 19
        let b = y / 100
        let c = y % 100
        let d = b / 4
        let e = b % 4
        let f = (b + 8) / 25
        let g = (b - f + 1) / 3
        let h = (19 * a + b - d - g + 15) % 30
        let i = c / 4
        let k = c % 4
        let l = (32 + 2 * e + 2 * i - h - k) % 7
        let mm = (a + 11 * h + 22 * l) / 451
        let month = (h + l - 7 * mm + 114) / 31
        let day = (h + l - 7 * mm + 114) % 31 + 1
        return of(y, month, day)
    }

    static func thanksgiving(_ y: Int) -> SDay { nthWeekday(y, 11, 4, 4) }
    static func mothersDay(_ y: Int) -> SDay { nthWeekday(y, 5, 7, 2) }
    static func fathersDay(_ y: Int) -> SDay { nthWeekday(y, 6, 7, 3) }

    /// The Academy Awards ceremony: the dates the Academy has announced, else the second Sunday of March
    /// (where recent ceremonies have mostly landed). Only the Awards Season hub's last day and its countdown
    /// depend on it; add a year here once its date is announced.
    static func awardsNight(_ y: Int) -> SDay {
        switch y {
        case 2025: return of(2025, 3, 2)
        case 2026: return of(2026, 3, 15)
        case 2027: return of(2027, 3, 14)
        default: return nthWeekday(y, 3, 7, 2)
        }
    }

    // MARK: - Math helpers (Java's Math.floorDiv / floorMod, String.format's %0Nd)

    fileprivate static func floorDiv(_ a: Int, _ b: Int) -> Int {
        let q = a / b
        return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q
    }

    fileprivate static func floorMod(_ a: Int, _ b: Int) -> Int {
        a - floorDiv(a, b) * b
    }

    /// Zero-padded to `width` characters, the sign counted in the width (as "%0Nd" does).
    fileprivate static func pad(_ n: Int, _ width: Int) -> String {
        let digits = String(n.magnitude)
        let room = (n < 0 ? width - 1 : width) - digits.count
        let zeros = room > 0 ? String(repeating: "0", count: room) : ""
        return (n < 0 ? "-" : "") + zeros + digits
    }
}

/// When a hub is on: `contains` says for any day; `start` gives where its window begins in a year (logs, the self-check).
struct SeasonWindow: Sendable {
    let contains: @Sendable (SDay) -> Bool
    private let first: @Sendable (Int) -> SDay?

    init(contains: @escaping @Sendable (SDay) -> Bool, first: @escaping @Sendable (Int) -> SDay?) {
        self.contains = contains
        self.first = first
    }

    /// Where the window that's on (or next) in year `y` starts.
    func start(_ y: Int) -> SDay? { first(y) }

    /// Month-day to month-day, inclusive (0101 = January 1st); may run over New Year (1227 to 0102). Feb 29 is fine as an end.
    static func fixed(_ from: Int, _ to: Int) -> SeasonWindow {
        SeasonWindow(contains: { d in
            from <= to ? (from...to).contains(d.md) : (d.md >= from || d.md <= to)
        }, first: { y in SDay.of(y, from / 100, from % 100) })
    }

    /// `before` days before a computed day through `after` days after it (Easter, Thanksgiving).
    static func around(_ anchor: @escaping @Sendable (Int) -> SDay, _ before: Int, _ after: Int) -> SeasonWindow {
        SeasonWindow(contains: { d in
            ((d.y - 1)...(d.y + 1)).contains { yy in
                let a = anchor(yy)
                return d >= a - before && d <= a + after
            }
        }, first: { y in anchor(y) - before })
    }

    /// From a fixed month-day through a computed day of the same year (Awards Season: Feb 15 to Awards Night).
    static func until(_ from: Int, _ end: @escaping @Sendable (Int) -> SDay) -> SeasonWindow {
        SeasonWindow(contains: { d in
            d >= SDay.of(d.y, from / 100, from % 100) && d <= end(d.y)
        }, first: { y in SDay.of(y, from / 100, from % 100) })
    }
}

/// A hub's countdown: "5 Nights Until Halloween", then `greeting` on the day ("Happy Halloween"). `unit` is
/// "Night" or "Day"; `until` reads after "Until" ("Halloween", "the New Year"). `eve` replaces the "1 Day"
/// line; `after` shows on the window's days after the target (else `greeting`).
struct Countdown: Sendable {
    let target: @Sendable (Int) -> SDay
    let unit: String
    let until: String
    let greeting: String
    let eve: String?
    let after: String?

    private static let grace = 7

    init(
        _ target: @escaping @Sendable (Int) -> SDay,
        _ unit: String,
        _ until: String,
        _ greeting: String,
        eve: String? = nil,
        after: String? = nil
    ) {
        self.target = target
        self.unit = unit
        self.until = until
        self.greeting = greeting
        self.eve = eve
        self.after = after
    }

    /// The target this countdown is about on `today` (this year's, or next year's once this year's is a week gone).
    func next(_ today: SDay) -> SDay {
        let t = target(today.y)
        return t < today - Countdown.grace ? target(today.y + 1) : t
    }

    func daysTo(_ today: SDay) -> Int { next(today).since(today) }

    func text(_ today: SDay) -> String {
        let n = daysTo(today)
        if n > 1 { return "\(n) \(unit)s Until \(until)" }
        if n == 1 { return eve ?? "1 \(unit) Until \(until)" }
        if n == 0 { return greeting }
        return after ?? greeting
    }
}

/// One row of a hub. `movie` and `tv` are TMDB Discover filters for each type (nil: none of that type), with
/// "{LASTYEAR}" (the year the hub looks back on), "{YEARSAGO:n}" ("yyyy-MM-dd" n years ago) filled in when it
/// loads (`SeasonCalendar.fill`). `kind` is how it's filled; `ids` are a `.picks` row's titles ("movie:603").
/// `kids`: safe for a kids profile (only these rows show there). `genre`: a TMDB movie genre whose trending
/// titles lead a `.trending` row.
struct SeasonRow: Hashable, Sendable {
    let title: String
    let kind: Kind
    let movie: String?
    let tv: String?
    let kids: Bool
    let ids: [String]
    let genre: Int

    init(
        _ title: String,
        _ kind: Kind,
        _ movie: String?,
        _ tv: String? = nil,
        kids: Bool = false,
        ids: [String] = [],
        genre: Int = 0
    ) {
        self.title = title
        self.kind = kind
        self.movie = movie
        self.tv = tv
        self.kids = kids
        self.ids = ids
        self.genre = genre
    }

    enum Kind: String, Hashable, Sendable, CaseIterable {
        /// What's hot: TMDB's trending titles of `genre`, topped up from the last two years' most popular.
        case trending = "TRENDING"
        /// Released in the last year, most popular first.
        case new = "NEW"
        /// Popular now alternating with long-time favorites (RowMix.variety).
        case variety = "VARIETY"
        /// The filter as asked (its own sort_by, else most popular).
        case popular = "POPULAR"
        /// The most voted of all: the classics everyone knows.
        case topVoted = "TOP_VOTED"
        /// Recent, highly rated and widely seen (RowMix.acclaimed).
        case acclaimed = "ACCLAIMED"
        /// A hand-picked list of titles (`ids`), in order.
        case picks = "PICKS"
    }
}

/// A seasonal or event hub: Halloween's "31 Nights of Horror", Christmas, Pride, a base season. `priority`
/// decides between hubs that are on at once (a holiday beats its base season; `boost` more within `boostDays`
/// of the countdown's target, so Valentine's week wins over Black History Month). `base`: one of the four
/// seasons, so some hub is always on. `glyph`: its GenreArt drawing; `hue`: the rainbow hue the glyph is
/// tinted from. `art`: a title ("movie:948") whose backdrop is the hub's art, else Tonight's Pick's. `pick`:
/// the pool Tonight's Pick comes from (one a day, the same all day), `kidsPick` for a kids profile.
/// `genres`: Browse genre pages that link here while it's on. `nights`: Halloween's "Night 26 of 31".
struct SeasonalHub: Identifiable, Sendable {
    let id: String
    let title: String
    let kicker: String
    let tagline: String
    let glyph: String
    let hue: Int
    let priority: Int
    let window: SeasonWindow
    let rows: [SeasonRow]
    let pick: SeasonRow
    let kidsPick: SeasonRow?
    let countdown: Countdown?
    let boostDays: Int
    let boost: Int
    let base: Bool
    let art: String?
    let genres: Set<String>
    let nights: Bool

    init(
        id: String,
        title: String,
        kicker: String,
        tagline: String,
        glyph: String,
        hue: Int,
        priority: Int,
        boostDays: Int = 0,
        boost: Int = 0,
        base: Bool = false,
        nights: Bool = false,
        window: SeasonWindow,
        genres: Set<String> = [],
        countdown: Countdown? = nil,
        art: String? = nil,
        pick: SeasonRow,
        kidsPick: SeasonRow? = nil,
        rows: [SeasonRow]
    ) {
        self.id = id
        self.title = title
        self.kicker = kicker
        self.tagline = tagline
        self.glyph = glyph
        self.hue = hue
        self.priority = priority
        self.window = window
        self.rows = rows
        self.pick = pick
        self.kidsPick = kidsPick
        self.countdown = countdown
        self.boostDays = boostDays
        self.boost = boost
        self.base = base
        self.art = art
        self.genres = genres
        self.nights = nights
    }

    func on(_ today: SDay) -> Bool { window.contains(today) }

    func priorityOn(_ today: SDay) -> Int {
        guard let c = countdown else { return priority }
        let n = c.daysTo(today)
        return priority + (boost > 0 && n >= 0 && n <= boostDays ? boost : 0)
    }

    /// The rows a profile sees: a kids profile only the kids-safe ones.
    func rowsFor(kids: Bool) -> [SeasonRow] { kids ? rows.filter { $0.kids } : rows }

    /// Shown to a kids profile at all: it has a row for them.
    func forKids() -> Bool { rows.contains { $0.kids } }

    /// The line under the title: the countdown when there is one, else the tagline.
    func status(_ today: SDay) -> String { countdown?.text(today) ?? tagline }

    /// "Night 26 of 31" (Halloween), else nil.
    func night(_ today: SDay) -> String? {
        if !nights || !on(today) { return nil }
        var start = today
        while on(start - 1) && today.since(start) < 62 { start -= 1 }
        var end = today
        while on(end + 1) && end.since(today) < 62 { end += 1 }
        return "Night \(today.since(start) + 1) of \(end.since(start) + 1)"
    }
}

/// Which hub is on: the highest-priority one whose window holds the day; every day has one (a base season).
enum SeasonCalendar {
    /// Every hub that is on, highest priority first (a kids profile: only the hubs with rows for kids).
    /// Ties keep catalog order, base seasons after the rest (a stable sort, as in the Kotlin).
    static func active(_ today: SDay, kids: Bool = false, all: [SeasonalHub] = SeasonCatalog.all) -> [SeasonalHub] {
        let on: [(index: Int, hub: SeasonalHub, priority: Int)] = all.enumerated()
            .filter { $0.element.on(today) && (!kids || $0.element.forKids()) }
            .map { (index: $0.offset, hub: $0.element, priority: $0.element.priorityOn(today)) }
        return on.sorted { a, b in
            if a.priority != b.priority { return a.priority > b.priority }
            let ab = a.hub.base ? 1 : 0
            let bb = b.hub.base ? 1 : 0
            if ab != bb { return ab < bb }
            return a.index < b.index
        }.map { $0.hub }
    }

    static func top(_ today: SDay, kids: Bool = false) -> SeasonalHub {
        active(today, kids: kids).first ?? SeasonCatalog.all.first { $0.base }!
    }

    static func find(_ id: String?) -> SeasonalHub? {
        guard let i = id?.lowercased() else { return nil }
        return SeasonCatalog.all.first { $0.id.lowercased() == i }
    }

    /// `q` (a row's filter or title) with its placeholders filled for hub `s` on `today`: "{LASTYEAR}" is the year
    /// the hub looks back on (the one before its countdown's target: New Year's and Awards Season look back on
    /// the year just gone), "{YEARSAGO:n}" the day n years ago ("yyyy-MM-dd").
    static func fill(_ q: String, _ s: SeasonalHub, _ today: SDay) -> String {
        if !q.contains("{") { return q }
        let last = String((s.countdown?.next(today) ?? today).y - 1)
        var rest = Substring(q.replacingOccurrences(of: "{LASTYEAR}", with: last))
        // The Kotlin's Regex("\\{YEARSAGO:(\\d+)\\}"), by hand.
        let open = "{YEARSAGO:"
        var out = ""
        while let r = rest.range(of: open) {
            out += rest[..<r.lowerBound]
            var i = r.upperBound
            var digits = ""
            while i < rest.endIndex, let a = rest[i].asciiValue, a >= 48 && a <= 57 {
                digits.append(rest[i])
                i = rest.index(after: i)
            }
            if !digits.isEmpty, i < rest.endIndex, rest[i] == "}", let n = Int(digits) {
                let y = today.y - n
                out += SDay.of(y, today.m, min(today.d, SDay.lengthOfMonth(y, today.m))).description
                rest = rest[rest.index(after: i)...]
            } else {
                out += open
                rest = rest[r.upperBound...]
            }
        }
        out += rest
        return out
    }

    /// "halloween 10-01..10-31, winter 12-01..02-28": each hub's days in `year` as ranges (logs, the report).
    static func describe(_ year: Int, all: [SeasonalHub] = SeasonCatalog.all) -> [String] {
        all.map { s in
            var ranges: [String] = []
            var d = SDay.of(year, 1, 1)
            let end = SDay.of(year, 12, 31)
            var from: SDay? = nil
            while d <= end {
                let on = s.on(d)
                if on && from == nil { from = d }
                if (!on || d == end), let f = from {
                    let to = on ? d : d - 1
                    ranges.append("\(fmt(f))..\(fmt(to))")
                    from = nil
                }
                d += 1
            }
            return "\(s.id) \(ranges.joined(separator: ", "))"
        }
    }

    private static func fmt(_ d: SDay) -> String {
        (d.m < 10 ? "0" : "") + String(d.m) + "-" + (d.d < 10 ? "0" : "") + String(d.d)
    }

    /// The calendar's own test: every day of `years` has exactly one base season on and a hub on top (for adults
    /// and for kids), every hub is on at least once, and the computed days are right for 2026 and 2027.
    /// Returns the problems (empty: all good) and a summary line.
    static func selfCheck(years: ClosedRange<Int> = 2026...2027) -> (problems: [String], summary: String) {
        var bad: [String] = []
        let expect: [(String, SDay, SDay)] = [
            ("Easter 2026", SDay.easter(2026), SDay.of(2026, 4, 5)),
            ("Easter 2027", SDay.easter(2027), SDay.of(2027, 3, 28)),
            ("Mother's Day 2026", SDay.mothersDay(2026), SDay.of(2026, 5, 10)),
            ("Mother's Day 2027", SDay.mothersDay(2027), SDay.of(2027, 5, 9)),
            ("Father's Day 2026", SDay.fathersDay(2026), SDay.of(2026, 6, 21)),
            ("Father's Day 2027", SDay.fathersDay(2027), SDay.of(2027, 6, 20)),
            ("Thanksgiving 2026", SDay.thanksgiving(2026), SDay.of(2026, 11, 26)),
            ("Thanksgiving 2027", SDay.thanksgiving(2027), SDay.of(2027, 11, 25)),
            ("Easter 2024", SDay.easter(2024), SDay.of(2024, 3, 31)),
            ("Easter 2025", SDay.easter(2025), SDay.of(2025, 4, 20)),
        ]
        for (name, got, want) in expect where got != want { bad.append("\(name) is \(got), expected \(want)") }
        if SDay.of(2026, 10, 26).dow != 1 { bad.append("2026-10-26 should be a Monday") }
        var seen = Set<String>()
        for y in years {
            var d = SDay.of(y, 1, 1)
            while d.y == y {
                let bases = SeasonCatalog.all.filter { $0.base && $0.on(d) }.count
                if bases != 1 { bad.append("\(d) has \(bases) base seasons") }
                let on = active(d)
                if let first = on.first { seen.insert(first.id) } else { bad.append("\(d) has no hub") }
                if active(d, kids: true).isEmpty { bad.append("\(d) has no hub for kids") }
                if let s = on.first, s.rows.count < 5 { bad.append("\(s.id) has only \(s.rows.count) rows") }
                d += 1
            }
        }
        let range = "\(years.lowerBound)..\(years.upperBound)"
        for s in SeasonCatalog.all where !seen.contains(s.id) { bad.append("\(s.id) never leads in \(range)") }
        let ids = SeasonCatalog.all.map { $0.id }
        if ids.count != Set(ids).count { bad.append("duplicate hub ids") }
        let summary = "\(SeasonCatalog.all.count) hubs, \(years.lowerBound)-\(years.upperBound) every day covered, " +
            "Easter \(SDay.easter(2026))/\(SDay.easter(2027)), Mother's Day \(SDay.mothersDay(2026))/\(SDay.mothersDay(2027)), " +
            "Father's Day \(SDay.fathersDay(2026))/\(SDay.fathersDay(2027)), Thanksgiving \(SDay.thanksgiving(2026))/\(SDay.thanksgiving(2027)), " +
            "Awards Night \(SDay.awardsNight(2026))/\(SDay.awardsNight(2027))"
        return (bad, summary)
    }
}
