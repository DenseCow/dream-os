import Foundation

/// Dream OS time zone + clock format (fire-home DreamTime.kt). Stored per device, not per profile.
/// Every displayed time and every "Today / Tomorrow / weekday" decision goes through here, so the whole app follows
/// the zone picked in Settings > Time & Region (or setup).
///
/// Two kinds of values:
/// - **Instants** (`Date`): worded in the chosen zone (`formatTime`, `format(_:_:)`, `dayDelta`).
/// - **Date-only TMDB days** ("yyyy-MM-dd"): the date is the date. They are parsed and worded without any zone shift
///   (`formatDay`, `dayWords`, `longDate`, `daysFromToday`), so "2026-11-14" reads "Nov 14" everywhere on Earth.
///
/// Month and day names are US English, like the Fire TV app. Changes post `DreamTime.changed` (main thread).
public enum DreamTime {
    public struct Choice: Sendable, Hashable, Identifiable {
        public let id: String
        public let label: String
        public init(id: String, label: String) { self.id = id; self.label = label }
    }

    /// Posted on the main queue after the zone or the clock format changes.
    public static let changed = Notification.Name("DreamTime.changed")

    // MARK: stored choice

    private static let zoneKey = "time.zone"     // absent = Automatic
    private static let clockKey = "time.clock24" // absent = follow the device; "1" / "0"

    private final class State: @unchecked Sendable {
        let lock = NSLock()
        var loaded = false
        var zoneId: String?
        var clock24: Bool?
    }
    private static let state = State()

    private static func load() {
        if state.loaded { return }
        state.zoneId = DreamStore.shared.get(String.self, zoneKey)
        state.clock24 = DreamStore.shared.get(String.self, clockKey).map { $0 == "1" }
        state.loaded = true
    }

    private static func storedZoneId() -> String? { state.lock.withLock { load(); return state.zoneId } }

    /// The device zone (what "Automatic" follows).
    public static func detected() -> TimeZone { TimeZone.current }

    public static var isAutomatic: Bool { storedZoneId() == nil }

    /// The user's pick, or the device zone when Automatic.
    public static var zone: TimeZone {
        guard let id = storedZoneId(), let tz = TimeZone(identifier: id) else { return detected() }
        return tz
    }

    /// nil = Automatic.
    public static func setZone(_ zoneId: String?) {
        let clean = zoneId.flatMap { id -> String? in
            guard !id.trimmingCharacters(in: .whitespaces).isEmpty, TimeZone(identifier: id) != nil else { return nil }
            return id
        }
        state.lock.withLock { load(); state.zoneId = clean }
        DreamStore.shared.set(clean, zoneKey)
        Log.core.info("Time: zone set to \(clean.map { "\($0) (manual)" } ?? "\(detected().identifier) (automatic)", privacy: .public)")
        notifyChanged()
    }

    /// null = follow the device, true/false = forced.
    public static var override24h: Bool? { state.lock.withLock { load(); return state.clock24 } }

    /// The device's own clock format (iOS: Settings > General > Date & Time > 24-Hour Time).
    public static var device24h: Bool {
        let f = DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: .current) ?? "h a"
        return !f.contains("a")
    }

    /// Default: follow the device setting.
    public static var use24h: Bool { override24h ?? device24h }

    /// nil = follow the device.
    public static func set24h(_ on: Bool?) {
        state.lock.withLock { load(); state.clock24 = on }
        DreamStore.shared.set(on.map { $0 ? "1" : "0" }, clockKey)
        Log.core.info("Time: clock format \(on == nil ? "automatic" : (on! ? "24-hour" : "12-hour"), privacy: .public)")
        notifyChanged()
    }

    private static func notifyChanged() {
        DispatchQueue.main.async { NotificationCenter.default.post(name: changed, object: nil) }
    }

    // MARK: instants (worded in the chosen zone)

    private static let us = Locale(identifier: "en_US_POSIX")

    private final class Formatters: @unchecked Sendable {
        let lock = NSLock()
        var byKey: [String: DateFormatter] = [:]
    }
    private static let formatters = Formatters()

    /// A formatter already set to the chosen zone (US English month/day names), e.g. "EEEE, MMMM d". Shared per
    /// pattern and zone (row filters ask for today's date once per title): use it, never change its settings.
    public static func formatter(_ pattern: String, zone tz: TimeZone? = nil) -> DateFormatter {
        let z = tz ?? zone
        let key = pattern + "|" + z.identifier
        return formatters.lock.withLock {
            if let f = formatters.byKey[key] { return f }
            let f = DateFormatter()
            f.locale = us
            f.calendar = Calendar(identifier: .gregorian)
            f.timeZone = z
            f.dateFormat = pattern
            // About 20 patterns in the app per zone; a few zone changes later it starts over.
            if formatters.byKey.count >= 64 { formatters.byKey.removeAll() }
            formatters.byKey[key] = f
            return f
        }
    }

    /// [date] in [pattern] in the chosen zone: `DreamTime.format(d, "MMM d")`.
    public static func format(_ date: Date, _ pattern: String) -> String { formatter(pattern).string(from: date) }

    /// A Gregorian calendar in the chosen zone.
    public static var calendar: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = zone
        c.locale = us
        return c
    }

    /// "9:00 PM" / "21:00" in the chosen zone.
    public static func formatTime(_ date: Date) -> String { formatTimeIn(date, zone) }

    /// Same as `formatTime` in an explicit zone (the picker previews each zone).
    public static func formatTimeIn(_ date: Date, _ tz: TimeZone) -> String {
        formatter(use24h ? "HH:mm" : "h:mm a", zone: tz).string(from: date)
    }

    /// A big clock face: "9:00" (12-hour, no AM/PM) or "21:00".
    public static func formatClock(_ date: Date = Date()) -> String { format(date, use24h ? "HH:mm" : "h:mm") }

    /// "9 PM" style: drops ":00" on the hour in 12-hour mode.
    public static func formatTimeShort(_ date: Date) -> String {
        if use24h { return formatTime(date) }
        return calendar.component(.minute, from: date) == 0 ? format(date, "h a") : format(date, "h:mm a")
    }

    /// Hour of day (0-23) now in the chosen zone (greetings, Tonight vs Today).
    public static func hourNow(_ now: Date = Date()) -> Int { calendar.component(.hour, from: now) }

    /// Today in the chosen zone as "yyyy-MM-dd".
    public static func todayYmd(_ now: Date = Date()) -> String { ymd(now) }

    /// [date]'s day in the chosen zone, "yyyy-MM-dd".
    public static func ymd(_ date: Date) -> String { format(date, "yyyy-MM-dd") }

    /// "yyyy-MM-dd" for today + [offsetDays] in the chosen zone (calendar days, DST-safe).
    public static func ymdPlus(_ offsetDays: Int, from now: Date = Date()) -> String {
        let c = calendar
        let d = c.date(byAdding: .day, value: offsetDays, to: now) ?? now
        return ymd(d)
    }

    /// "yyyy-MM-dd" [years] ago today (RowMix.yearsAgo; the chosen zone's today).
    public static func yearsAgo(_ years: Int, from now: Date = Date()) -> String {
        let d = calendar.date(byAdding: .year, value: -years, to: now) ?? now
        return ymd(d)
    }

    /// Days between today (chosen zone) and [date]'s day: 0 today, 1 tomorrow, -1 yesterday.
    public static func dayDelta(_ date: Date, now: Date = Date()) -> Int {
        guard let a = dayNumber(ymd(date)), let b = dayNumber(ymd(now)) else { return 0 }
        return a - b
    }

    /// The start of a date-only day in the chosen zone (the date itself never shifts). nil when unreadable.
    public static func midnight(_ ymd: String) -> Date? {
        guard let p = parts(ymd) else { return nil }
        var dc = DateComponents()
        dc.year = p.y; dc.month = p.m; dc.day = p.d
        return calendar.date(from: dc)
    }

    // MARK: date-only days ("yyyy-MM-dd": never shifted)

    /// (year, month, day) of "yyyy-MM-dd" (longer strings: their first 10 characters). nil when unreadable.
    public static func parts(_ ymd: String) -> (y: Int, m: Int, d: Int)? {
        let s = ymd.prefix(10).split(separator: "-")
        guard s.count == 3, let y = Int(s[0]), let m = Int(s[1]), let d = Int(s[2]), (1...12).contains(m), (1...31).contains(d) else { return nil }
        return (y, m, d)
    }

    /// A zone-independent day number for "yyyy-MM-dd" (days since 1970-01-01).
    public static func dayNumber(_ ymd: String) -> Int? {
        guard let p = parts(ymd) else { return nil }
        // Days from civil (Howard Hinnant's algorithm): no zones, no calendars.
        let y = p.m <= 2 ? p.y - 1 : p.y
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let mp = (p.m + 9) % 12
        let doy = (153 * mp + 2) / 5 + p.d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /// Whole days from today (chosen zone) to the date-only [ymd]: 0 today, -1 yesterday, 1 tomorrow; nil when unreadable.
    public static func daysFromToday(_ ymd: String, now: Date = Date()) -> Int? {
        guard let a = dayNumber(ymd), let b = dayNumber(todayYmd(now)) else { return nil }
        return a - b
    }

    /// Same as `daysFromToday` (DreamTime.kt's name).
    public static func dayDeltaForDate(_ ymd: String, now: Date = Date()) -> Int? { daysFromToday(ymd, now: now) }

    private static let utc = TimeZone(identifier: "UTC")!

    /// The date-only [ymd] worded with [pattern] ("MMM d", "EEEE", "MMMM d, yyyy"): parsed and worded in one fixed
    /// zone, so the day never shifts. nil when unreadable.
    public static func formatDay(_ ymd: String, _ pattern: String) -> String? {
        guard let p = parts(ymd) else { return nil }
        var c = Calendar(identifier: .gregorian)
        c.timeZone = utc
        var dc = DateComponents()
        dc.year = p.y; dc.month = p.m; dc.day = p.d; dc.hour = 12
        guard let d = c.date(from: dc) else { return nil }
        return formatter(pattern, zone: utc).string(from: d)
    }

    /// The chosen zone's current year.
    public static func thisYear(_ now: Date = Date()) -> Int { calendar.component(.year, from: now) }

    /// "Nov 14" ("Nov 14, 2027" past this year) for a date-only day (Availability.dateWords). The input when unreadable.
    public static func dateWords(_ ymd: String, now: Date = Date()) -> String {
        guard let p = parts(ymd) else { return ymd }
        return formatDay(ymd, p.y == thisYear(now) ? "MMM d" : "MMM d, yyyy") ?? ymd
    }

    /// "March 1, 2024" for a date-only day (TitleInfo.longDate); nil when unreadable.
    public static func longDate(_ ymd: String?) -> String? {
        guard let ymd, ymd.count >= 10 else { return nil }
        return formatDay(ymd, "MMMM d, yyyy")
    }

    /// "March 4" ("March 4, 2027" past this year); a bare year as is (Upcoming.longDate).
    public static func longDateShort(_ ymd: String, now: Date = Date()) -> String {
        if ymd.count == 4 { return ymd }
        guard let p = parts(ymd) else { return ymd }
        return formatDay(ymd, p.y == thisYear(now) ? "MMMM d" : "MMMM d, yyyy") ?? ymd
    }

    /// "Nov 14" for "yyyy-MM-dd" (Discover.shortDate), "" when unreadable.
    public static func shortDate(_ ymd: String) -> String { formatDay(ymd, "MMM d") ?? "" }

    /// "Today", "Tomorrow", "Friday" (this week), else "Nov 14" ("Nov 14, 2027" past this year) (Upcoming.dayWords).
    public static func dayWords(_ ymd: String, now: Date = Date()) -> String {
        switch daysFromToday(ymd, now: now) {
        case 0: return "Today"
        case 1: return "Tomorrow"
        case .some(let n) where (2...6).contains(n): return formatDay(ymd, "EEEE") ?? ymd
        default: return dateWords(ymd, now: now)
        }
    }

    /// True when the date-only [ymd] is after today (chosen zone).
    public static func isAhead(_ ymd: String?, now: Date = Date()) -> Bool {
        guard let ymd, ymd.count >= 10 else { return false }
        return String(ymd.prefix(10)) > todayYmd(now)
    }

    // MARK: instants as a viewer says them

    /// When [at] is (AirTimes.label): "Tonight at 9:00 PM" (today, from 5 PM), "Today at 3:00 PM", "Tomorrow at 3:00 AM",
    /// "Friday at 9:00 PM" (within a week), else "Nov 14 at 9:00 PM".
    public static func airLabel(_ at: Date, now: Date = Date()) -> String {
        let time = formatTime(at)
        let days = dayDelta(at, now: now)
        switch days {
        case 0: return (calendar.component(.hour, from: at) >= 17 ? "Tonight" : "Today") + " at \(time)"
        case 1: return "Tomorrow at \(time)"
        case 2...6: return format(at, "EEEE") + " at \(time)"
        default: return format(at, "MMM d") + " at \(time)"
        }
    }

    // MARK: zone names

    /// "GMT−5" / "GMT+5:30" for [tz] right now (small secondary text only).
    public static func offsetText(_ tz: TimeZone, now: Date = Date()) -> String {
        let mins = tz.secondsFromGMT(for: now) / 60
        if mins == 0 { return "GMT" }
        let sign = mins < 0 ? "−" : "+"
        let a = abs(mins)
        return a % 60 == 0 ? "GMT\(sign)\(a / 60)" : "GMT\(sign)\(a / 60):" + String(format: "%02d", a % 60)
    }

    private static let curated: [Choice] = [
        // United States
        Choice(id: "America/New_York", label: "Eastern Time"),
        Choice(id: "America/Chicago", label: "Central Time"),
        Choice(id: "America/Denver", label: "Mountain Time"),
        Choice(id: "America/Phoenix", label: "Arizona"),
        Choice(id: "America/Los_Angeles", label: "Pacific Time"),
        Choice(id: "America/Anchorage", label: "Alaska"),
        Choice(id: "Pacific/Honolulu", label: "Hawaii"),
        Choice(id: "America/Puerto_Rico", label: "Puerto Rico"),
        // Americas
        Choice(id: "America/Halifax", label: "Atlantic Time (Halifax)"),
        Choice(id: "America/St_Johns", label: "Newfoundland"),
        Choice(id: "America/Toronto", label: "Toronto"),
        Choice(id: "America/Vancouver", label: "Vancouver"),
        Choice(id: "America/Mexico_City", label: "Mexico City"),
        Choice(id: "America/Bogota", label: "Bogotá"),
        Choice(id: "America/Lima", label: "Lima"),
        Choice(id: "America/Santiago", label: "Santiago"),
        Choice(id: "America/Sao_Paulo", label: "São Paulo"),
        Choice(id: "America/Argentina/Buenos_Aires", label: "Buenos Aires"),
        // Europe & Africa
        Choice(id: "Europe/London", label: "London"),
        Choice(id: "Europe/Dublin", label: "Dublin"),
        Choice(id: "Europe/Lisbon", label: "Lisbon"),
        Choice(id: "Europe/Paris", label: "Paris"),
        Choice(id: "Europe/Berlin", label: "Berlin"),
        Choice(id: "Europe/Madrid", label: "Madrid"),
        Choice(id: "Europe/Rome", label: "Rome"),
        Choice(id: "Europe/Amsterdam", label: "Amsterdam"),
        Choice(id: "Europe/Stockholm", label: "Stockholm"),
        Choice(id: "Europe/Athens", label: "Athens"),
        Choice(id: "Europe/Istanbul", label: "Istanbul"),
        Choice(id: "Europe/Moscow", label: "Moscow"),
        Choice(id: "Africa/Lagos", label: "Lagos"),
        Choice(id: "Africa/Cairo", label: "Cairo"),
        Choice(id: "Africa/Johannesburg", label: "Johannesburg"),
        Choice(id: "Africa/Nairobi", label: "Nairobi"),
        // Asia & Pacific
        Choice(id: "Asia/Dubai", label: "Dubai"),
        Choice(id: "Asia/Karachi", label: "Karachi"),
        Choice(id: "Asia/Kolkata", label: "India"),
        Choice(id: "Asia/Bangkok", label: "Bangkok"),
        Choice(id: "Asia/Singapore", label: "Singapore"),
        Choice(id: "Asia/Manila", label: "Manila"),
        Choice(id: "Asia/Shanghai", label: "China"),
        Choice(id: "Asia/Hong_Kong", label: "Hong Kong"),
        Choice(id: "Asia/Seoul", label: "Seoul"),
        Choice(id: "Asia/Tokyo", label: "Tokyo"),
        Choice(id: "Australia/Perth", label: "Perth"),
        Choice(id: "Australia/Adelaide", label: "Adelaide"),
        Choice(id: "Australia/Brisbane", label: "Brisbane"),
        Choice(id: "Australia/Sydney", label: "Sydney"),
        Choice(id: "Pacific/Auckland", label: "Auckland"),
        Choice(id: "UTC", label: "Coordinated Universal Time"),
    ]

    private static let usIds: Set<String> = ["America/New_York", "America/Chicago", "America/Denver", "America/Phoenix",
                                             "America/Los_Angeles", "America/Anchorage", "Pacific/Honolulu", "America/Puerto_Rico"]

    private static let usOtherPrefixes = ["America/Indiana/", "America/Kentucky/", "America/North_Dakota/"]
    private static let usOther: Set<String> = ["America/Menominee", "America/Juneau", "America/Sitka", "America/Nome", "America/Yakutat",
                                               "America/Metlakatla"]

    private static let aliases: [String: String] = [
        "US/Eastern": "America/New_York", "US/Central": "America/Chicago", "US/Mountain": "America/Denver",
        "US/Arizona": "America/Phoenix", "US/Pacific": "America/Los_Angeles", "US/Alaska": "America/Anchorage",
        "US/Hawaii": "Pacific/Honolulu", "America/Detroit": "America/New_York", "America/Indiana/Indianapolis": "America/New_York",
        "America/Kentucky/Louisville": "America/New_York", "America/Boise": "America/Denver", "Asia/Calcutta": "Asia/Kolkata",
        "Etc/UTC": "UTC", "GMT": "UTC", "Europe/Kiev": "Europe/Athens", "Europe/Kyiv": "Europe/Athens",
    ]

    /// Curated, friendly list (US first, then major world zones by region).
    public static func choices() -> [Choice] { curated }

    /// Region header for a curated id (the picker groups rows by it).
    public static func region(_ id: String) -> String {
        if usIds.contains(id) { return "United States" }
        if id.hasPrefix("America/") { return "Americas" }
        if id.hasPrefix("Europe/") || id.hasPrefix("Africa/") { return "Europe & Africa" }
        if id == "UTC" { return "Other" }
        return "Asia & Pacific"
    }

    /// Friendly name for a zone: "Eastern Time", "London", or the city part of the id as a fallback.
    public static func label(_ tz: TimeZone) -> String { labelFor(tz.identifier) }

    public static func labelFor(_ id: String) -> String {
        if let c = curated.first(where: { $0.id == id }) { return c.label }
        if let a = aliases[id] { return labelFor(a) }
        // A US zone with the same rules as a curated one right now (America/Indiana/Knox -> Central Time). Only US
        // zones: Caracas or Hermosillo share a US zone's rules but aren't "Puerto Rico" or "Arizona".
        if usOtherPrefixes.contains(where: { id.hasPrefix($0) }) || usOther.contains(id), let tz = TimeZone(identifier: id) {
            let now = Date()
            if let c = curated.first(where: { c in
                guard usIds.contains(c.id), let o = TimeZone(identifier: c.id) else { return false }
                return o.secondsFromGMT(for: now) - (o.isDaylightSavingTime(for: now) ? Int(o.daylightSavingTimeOffset(for: now)) : 0)
                    == tz.secondsFromGMT(for: now) - (tz.isDaylightSavingTime(for: now) ? Int(tz.daylightSavingTimeOffset(for: now)) : 0)
                    && (o.nextDaylightSavingTimeTransition != nil) == (tz.nextDaylightSavingTimeTransition != nil)
            }) { return c.label }
        }
        let city = id.split(separator: "/").last.map(String.init)?.replacingOccurrences(of: "_", with: " ") ?? ""
        return city.trimmingCharacters(in: .whitespaces).isEmpty ? "Local Time" : city
    }

    /// "Automatic (Eastern Time)" or "Pacific Time".
    public static func summary() -> String {
        isAutomatic ? "Automatic (\(labelFor(detected().identifier)))" : labelFor(zone.identifier)
    }

    /// "Automatic (12-Hour)" / "12-Hour" / "24-Hour".
    public static func clockSummary() -> String {
        switch override24h {
        case nil: return "Automatic (\(use24h ? "24-Hour" : "12-Hour"))"
        case .some(true): return "24-Hour"
        case .some(false): return "12-Hour"
        }
    }
}
