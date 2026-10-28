import Foundation

// Catalog contract additions (Content worker). Additive only: new types, new CatalogService requirements with
// defaults below, so every module keeps building.

// MARK: Lazy rows

public extension CatalogService {
    /// Default: everything at once.
    func rowSlots(for page: CatalogPage) async -> [CatalogRow] { (try? await rows(for: page)) ?? [] }
    func fill(row: CatalogRow, page: CatalogPage) async throws -> CatalogRow? { row.items.isEmpty ? nil : row }
    func searchAll(_ query: String) async -> SearchResults { SearchResults(titles: (try? await search(query)) ?? []) }
    func suggestions(_ query: String) async -> [String] { [] }
    func discover(_ filter: DiscoverFilter, page: Int) async throws -> DiscoverPage { DiscoverPage(titles: [], page: page, totalPages: 1, totalResults: 0) }
    func discoverCategories(_ kind: MediaKind) async -> [DiscoverCategory] { [] }
    func hub(id: String) async -> HubTile? { nil }
    func hubs() async -> [HubTile] { [] }
    func availabilityDetail(for title: Title) async -> AvailabilityDetail? { nil }
    func markEpisodeSeen(_ item: CatalogItem) async {}
    func titleLogo(kind: MediaKind, id: Int) async -> String? { nil }
    func allowed(_ title: Title) async -> Bool { true }
}

// MARK: Discover wall (Browse's "Every Movie and Show")

public enum DiscoverSort: Int, Codable, Sendable, Hashable, CaseIterable {
    case popular = 0, topRated = 1, newest = 2, comingSoon = 3, mixed = 4
    public var name: String { ["Most Popular", "Top Rated", "Newest", "Coming Soon", "Popular & Favorites"][rawValue] }
    /// The order the Sort By picker lists them in.
    public static let pickerOrder: [DiscoverSort] = [.mixed, .popular, .topRated, .newest, .comingSoon]
}

public struct DiscoverFilter: Codable, Sendable, Hashable {
    public var kind: MediaKind
    /// A `DiscoverCategory.id` for this kind, 0 = any genre.
    public var genre: Int
    public var sort: DiscoverSort
    public init(kind: MediaKind = .movie, genre: Int = 0, sort: DiscoverSort = .mixed) { self.kind = kind; self.genre = genre; self.sort = sort }
}

public struct DiscoverCategory: Codable, Sendable, Hashable, Identifiable {
    public var id: Int
    public var name: String
    public init(id: Int, name: String) { self.id = id; self.name = name }
}

public struct DiscoverPage: Codable, Sendable, Hashable {
    public var titles: [Title]
    public var page: Int
    public var totalPages: Int
    public var totalResults: Int
    public init(titles: [Title], page: Int, totalPages: Int, totalResults: Int) {
        self.titles = titles; self.page = page; self.totalPages = totalPages; self.totalResults = totalResults
    }
}

// MARK: Search

/// Everything one search shows (CinemaSearch): titles, a person's titles, a genre's rows.
public struct SearchResults: Codable, Sendable, Hashable {
    public struct Person: Codable, Sendable, Hashable {
        public var name: String
        /// "Movies & Shows with Brad Pitt", "Movies Directed by Greta Gerwig".
        public var heading: String
        public var titles: [Title]
        public init(name: String, heading: String, titles: [Title]) { self.name = name; self.heading = heading; self.titles = titles }
    }
    public struct GenreHits: Codable, Sendable, Hashable {
        /// "Horror", "Romantic Comedies", "Anime Horror".
        public var label: String
        /// "Browse Horror" chips: open `.genre(genre)`.
        public var browse: [Genre]
        public var movieHeading: String
        public var movies: [Title]
        public var showHeading: String
        public var shows: [Title]
        public init(label: String, browse: [Genre], movieHeading: String, movies: [Title], showHeading: String, shows: [Title]) {
            self.label = label; self.browse = browse; self.movieHeading = movieHeading; self.movies = movies
            self.showHeading = showHeading; self.shows = shows
        }
    }
    public var titles: [Title]
    public var person: Person?
    /// Set only when it has enough titles to lead the page (CinemaSearch GENRE_MIN).
    public var genre: GenreHits?
    public init(titles: [Title], person: Person? = nil, genre: GenreHits? = nil) { self.titles = titles; self.person = person; self.genre = genre }
}

// MARK: Availability words (STANDARDS §5: one fixed card label per state)

public extension Availability {
    /// Status tone: green Available Now, amber CAM Only, red otherwise.
    enum Tone: Sendable { case green, amber, red }
    var tone: Tone {
        switch self {
        case .availableNow: return .green
        case .camOnly: return .amber
        default: return .red
        }
    }

    /// The card pill's words, longest first: a narrow card takes the first that fits, never an icon alone.
    var labels: [String] {
        switch self {
        case .availableNow: return ["Available Now", "Available"]
        case .camOnly: return ["CAM Only", "CAM"]
        case .notAvailableYet: return ["Not Available Yet", "Not Yet"]
        case .comingSoon: return ["Coming Soon", "Soon"]
        case .coming(let ymd):
            guard let p = DreamTime.parts(ymd) else { return ["Coming Soon", "Soon"] }
            if p.y == DreamTime.thisYear() {
                let d = DreamTime.formatDay(ymd, "MMM d") ?? ymd
                return ["Coming \(d)", d, "Soon"]
            }
            let d = DreamTime.formatDay(ymd, "MMM d, yyyy") ?? ymd
            return ["Coming \(d)", DreamTime.formatDay(ymd, "MMM yyyy") ?? d, "Soon"]
        }
    }
}

/// A title's availability with the title page's detail (Availability.kt `Entry`).
public struct AvailabilityDetail: Codable, Sendable, Hashable {
    /// The card state (pill words: `state.labels`).
    public var state: Availability
    /// The best release found ("2160p WEB-DL HDR", "HDTS 1080p"), nil when none.
    public var best: String?
    /// Where it is / when it comes: "In Theaters Now", "Digital Release Nov 14", "Coming Friday at 9:00 PM".
    public var note: String?
    public init(state: Availability, best: String? = nil, note: String? = nil) { self.state = state; self.best = best; self.note = note }

    /// "4K" / "1080p" / "720p" from [best].
    public var resolution: String? {
        guard let b = best?.uppercased() else { return nil }
        if b.range(of: "(?<![A-Z0-9])(2160P|4K|UHD)(?![A-Z0-9])", options: .regularExpression) != nil { return "4K" }
        if b.contains("1080P") { return "1080p" }
        if b.contains("720P") { return "720p" }
        return nil
    }

    /// "Dolby Vision" / "HDR" by the best release's name.
    public var dynamicRange: String? {
        guard let b = best?.uppercased() else { return nil }
        if b.range(of: "(?<![A-Z0-9])(DV|DOVI|DOLBY VISION)(?![A-Z0-9])", options: .regularExpression) != nil { return "Dolby Vision" }
        if b.range(of: "(?<![A-Z0-9])HDR", options: .regularExpression) != nil { return "HDR" }
        return nil
    }

    /// The title page's large pill, longest first: "Available Now · 4K HDR" ... "Available Now"; "CAM Only";
    /// "Not Available Yet" (its note says when).
    public var pageLabels: [String] {
        switch state {
        case .availableNow:
            guard let res = resolution else { return ["Available Now", "Available"] }
            let base = "Available Now · \(res)"
            let dr = dynamicRange
            return [dr.map { "\(base) \($0)" }, dr == "Dolby Vision" ? "\(base) HDR" : nil, base, "Available Now"].compactMap { $0 }
        case .camOnly: return ["CAM Only", "CAM"]
        default: return ["Not Available Yet", "Not Yet"]
        }
    }
}

// MARK: Title page Information grid (TitleInfo.kt cells)

public extension TitleInfo {
    struct Cell: Sendable, Hashable {
        public var label: String
        public var value: String
        public var span: Int
        public init(label: String, value: String, span: Int = 1) { self.label = label; self.value = value; self.span = span }
    }

    static let ratingsLabel = "Ratings"
    static let episodeLengthLabel = "Episode Length"

    /// The US day it comes out (movies: the earliest US theatrical or digital day), else TMDB's date.
    var releaseDay: String {
        if isShow { return premiere }
        return [theatrical, digital].filter { $0.count == 10 }.min() ?? premiere
    }

    /// The grid's cells in reading order. [scores]: the tomato / IMDb when known; [nextAt]: the next episode's exact
    /// time; [episodeMin]: the median episode length of a loaded season (0: TMDB's figure).
    func cells(scores: Scores? = nil, imdb: String? = nil, nextAt: Date? = nil, episodeMin: Int = 0) -> [Cell] {
        var out: [Cell] = []
        func add(_ label: String, _ value: String?, _ span: Int = 1) {
            if let v = value, !v.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { out.append(Cell(label: label, value: v, span: span)) }
        }
        func names(_ l: [String], _ max: Int = 3) -> String {
            var seen = Set<String>(); return l.filter { seen.insert($0).inserted }.prefix(max).joined(separator: ", ")
        }
        let rated = cert.isEmpty ? "" : (certNote.isEmpty ? cert : "\(cert)  ·  \(certNote)")
        if !isShow {
            add(DreamTime.isAhead(releaseDay) ? "Release Date" : "Released", releasedWords(), 2)
            add("Run Time", Self.runtimeWords(runtime))
            add("Rated", rated)
            add("Genres", names(genres, 4))
            add(directors.count > 1 ? "Directors" : "Director", names(directors))
            add(writers.count > 1 ? "Writers" : "Writer", names(writers))
            add("Based On", basedOn)
            add("Producers", names(producers))
            add(studios.count > 1 ? "Studios" : "Studio", names(studios, 2))
            add(countries.count > 1 ? "Countries" : "Country", names(countries))
            add("Original Language", language)
            add("Original Title", originalTitle)
            add("Budget", Self.money(budget))
            add("Box Office", Self.money(revenue))
            add("Collection", collection.isEmpty ? nil : (collection.hasSuffix("Collection") ? "Part of the \(collection)" : "Part of \(collection)"))
            add("Status", status == "Released" ? nil : status)
        } else {
            add("First Aired", DreamTime.longDate(premiere).map { DreamTime.isAhead(premiere) ? "Coming \($0)" : $0 })
            add("Last Aired", lastAired == premiere ? nil : DreamTime.longDate(lastAired))
            add("Status", status)
            add("Seasons", [seasons > 0 ? "\(seasons) Season" + (seasons > 1 ? "s" : "") : nil,
                            episodes > 0 ? "\(episodes) Episode" + (episodes > 1 ? "s" : "") : nil].compactMap { $0 }.joined(separator: ", "))
            add("Next Episode", nextWords(nextAt), 2)
            add(Self.episodeLengthLabel, episodeLengthWords(episodeMin))
            add("Rated", rated)
            add("Genres", names(genres, 4))
            add("Created By", names(creators))
            add(networks.count > 1 ? "Networks" : "Network", names(networks))
            add(studios.count > 1 ? "Studios" : "Studio", names(studios, 2))
            add(countries.count > 1 ? "Countries" : "Country", names(countries))
            add("Original Language", language)
            add("Original Title", originalTitle)
        }
        add("Where to Watch", watchWords(), 2)
        add(Self.ratingsLabel, ratingsWords(scores, imdb: imdb), 2)
        return out
    }

    /// "47 min" from a loaded season's median, else TMDB's ("About 56 min" when one episode told it).
    func episodeLengthWords(_ episodeMin: Int = 0) -> String? {
        if episodeMin > 0 { return Self.runtimeWords(episodeMin) }
        return Self.runtimeWords(runtime).map { runtimeApprox ? "About \($0)" : $0 }
    }

    /// "In Theaters March 1, 2024  ·  Digital April 16, 2024", or TMDB's one date.
    func releasedWords() -> String? {
        var parts: [String] = []
        if let t = DreamTime.longDate(theatrical) { parts.append("In Theaters \(t)") }
        if let d = DreamTime.longDate(digital) { parts.append("Digital \(d)") }
        return parts.isEmpty ? DreamTime.longDate(premiere) : parts.joined(separator: "  ·  ")
    }

    /// "Season 2, Episode 7  ·  Friday, October 30 at 9:00 PM", else its day, else "Date to Be Announced".
    func nextWords(_ at: Date?) -> String? {
        guard nextSeason > 0, nextEpisode > 0 else { return nil }
        let when: String
        if let at { when = DreamTime.format(at, "EEEE, MMMM d") + " at " + DreamTime.formatTime(at) }
        else { when = DreamTime.longDate(nextDate) ?? "Date to Be Announced" }
        return "Season \(nextSeason), Episode \(nextEpisode)  ·  \(when)"
    }

    /// "Streaming on Netflix, HBO Max" and "Rent or Buy on Amazon, Apple TV" (two lines at most).
    func watchWords() -> String {
        let first = [streaming.isEmpty ? nil : "Streaming on " + streaming.prefix(4).joined(separator: ", "),
                     { let f = free.filter { !streaming.contains($0) }.prefix(2); return f.isEmpty ? nil : "Free on " + f.joined(separator: ", ") }()]
            .compactMap { $0 }.joined(separator: "  ·  ")
        let second = rentBuy.isEmpty ? "" : "Rent or Buy on " + rentBuy.prefix(3).joined(separator: ", ")
        return [first, second].filter { !$0.isEmpty }.joined(separator: "\n")
    }

    /// "Rotten Tomatoes 94%  ·  IMDb 8.5  ·  TMDB 8.1" (what's known).
    func ratingsWords(_ s: Scores?, imdb: String? = nil) -> String {
        var parts: [String] = []
        if let t = s?.tomatometer { parts.append("Rotten Tomatoes \(t)%") }
        let im = imdb ?? s?.audienceBadge.flatMap { $0.hasPrefix("IMDb ") ? String($0.dropFirst(5)) : nil }
        if let im, !im.isEmpty, im != "N/A" { parts.append("IMDb \(im)") }
        if score > 0 && votes >= 20 { parts.append("TMDB " + String(format: "%.1f", score)) }
        return parts.joined(separator: "  ·  ")
    }

    /// "2 hr 46 min", "2 hr", "56 min".
    static func runtimeWords(_ min: Int) -> String? {
        if min <= 0 { return nil }
        if min < 60 { return "\(min) min" }
        if min % 60 == 0 { return "\(min / 60) hr" }
        return "\(min / 60) hr \(min % 60) min"
    }

    /// "$190 million", "$1.2 billion", "$850,000".
    static func money(_ v: Int64) -> String? {
        func trim(_ x: Double) -> String { let s = String(format: "%.1f", x); return s.hasSuffix(".0") ? String(s.dropLast(2)) : s }
        if v <= 0 { return nil }
        if v >= 1_000_000_000 { return "$" + trim(Double(v) / 1e9) + " billion" }
        if v >= 1_000_000 { return "$" + trim(Double(v) / 1e6) + " million" }
        let f = NumberFormatter(); f.numberStyle = .decimal; f.locale = Locale(identifier: "en_US")
        return "$" + (f.string(from: NSNumber(value: v)) ?? "\(v)")
    }
}
