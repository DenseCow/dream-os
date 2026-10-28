#if DEBUG
import Foundation
import DreamCore
import DreamUI

/// A small fixed catalog for building the pages before DreamContent lands (debug builds only). Switch it on or off by
/// holding the Dream OS mark on Home for 1.5 seconds; the choice is kept (UserDefaults "DreamOS.debugCatalog").
/// While on, Continue Watching and My List come from small in-memory stand-ins too.
enum DebugCatalog {
    private static let key = "DreamOS.debugCatalog"
    nonisolated(unsafe) private static var saved: (CatalogService, HistoryService, MyListService)?

    static var isOn: Bool { UserDefaults.standard.bool(forKey: key) }

    /// Call at launch (after the real services register): puts the mock in place if it was left on.
    static func applyAtLaunch() { if isOn { install() } }

    static func toggle() {
        UserDefaults.standard.set(!isOn, forKey: key)
        if isOn { install() } else { uninstall() }
    }

    private static func install() {
        let s = Services.shared
        if saved == nil { saved = (s.catalog, s.history, s.myList) }
        s.catalog = MockCatalog()
        s.history = MockHistory()
        s.myList = MockMyList.shared
    }

    private static func uninstall() {
        guard let (c, h, m) = saved else { return }
        let s = Services.shared
        s.catalog = c; s.history = h; s.myList = m
        saved = nil
    }

    static let titles: [Title] = [
        Title(id: 155, kind: .movie, name: "The Dark Knight", overview: "Batman raises the stakes in his war on crime, until a criminal mastermind known as the Joker plunges Gotham into anarchy.",
              posterPath: "/qJ2tW6WMUDux911r6m7haRef0WH.jpg", backdropPath: "/nMKdUUepR0i5zn0y1T4CsSB5chy.jpg", year: "2008", rating: 8.5, date: "2008-07-16", votes: 33000, genreIds: [18, 28, 80, 53]),
        Title(id: 1396, kind: .tv, name: "Breaking Bad", overview: "A chemistry teacher diagnosed with a terminal illness turns to making and selling methamphetamine to secure his family's future.",
              posterPath: "/ggFHVNu6YYI5L9pCfOacjizRGt.jpg", backdropPath: "/tsRy63Mu5cu8etL1X7ZLyf7UP1M.jpg", year: "2008", rating: 8.9, date: "2008-01-20", votes: 14000, genreIds: [18, 80]),
        Title(id: 157336, kind: .movie, name: "Interstellar", overview: "A team of explorers travels through a wormhole in space in an attempt to ensure humanity's survival.",
              posterPath: "/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg", backdropPath: "/xJHokMbljvjADYdit5fK5VQsXEG.jpg", year: "2014", rating: 8.4, date: "2014-11-05", votes: 36000, genreIds: [12, 18, 878]),
        Title(id: 66732, kind: .tv, name: "Stranger Things", overview: "When a young boy vanishes, a small town uncovers a mystery involving secret experiments, terrifying forces and one strange little girl.",
              posterPath: "/49WJfeN0moxb9IPfGn8AIqMGskD.jpg", backdropPath: "/56v2KjBlU4XaOv9rVYEQypROD7P.jpg", year: "2016", rating: 8.6, date: "2016-07-15", votes: 17000, genreIds: [18, 10765, 9648]),
        Title(id: 603, kind: .movie, name: "The Matrix", overview: "A hacker learns from mysterious rebels about the true nature of his reality and his role in the war against its controllers.",
              posterPath: "/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg", backdropPath: "/fNG7i7RqMErkcqhohV2a6cV1Ehy.jpg", year: "1999", rating: 8.2, date: "1999-03-30", votes: 25000, genreIds: [28, 878]),
        Title(id: 680, kind: .movie, name: "Pulp Fiction", overview: "The lives of two mob hitmen, a boxer, a gangster and his wife intertwine in four tales of violence and redemption.",
              posterPath: "/d5iIlFn5s0ImszYzBPb8JPIfbXD.jpg", backdropPath: "/suaEOtk1N1sgg2MTM7oZd2cfVp3.jpg", year: "1994", rating: 8.5, date: "1994-09-10", votes: 27000, genreIds: [53, 80]),
        Title(id: 550, kind: .movie, name: "Fight Club", overview: "An insomniac office worker and a devil-may-care soap maker form an underground fight club that evolves into much more.",
              posterPath: "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg", backdropPath: "/hZkgoQYus5vegHoetLkCJzb17zJ.jpg", year: "1999", rating: 8.4, date: "1999-10-15", votes: 29000, genreIds: [18, 53]),
        Title(id: 278, kind: .movie, name: "The Shawshank Redemption", overview: "Imprisoned in the 1940s for a double murder he didn't commit, a banker spends nearly two decades in Shawshank prison.",
              posterPath: "/q6y0Go1tsGEsmtFryDOJo3dEmqu.jpg", backdropPath: "/kXfqcdQKsToO0OUXHcrrNCHDBzO.jpg", year: "1994", rating: 8.7, date: "1994-09-23", votes: 27000, genreIds: [18, 80]),
        Title(id: 1399, kind: .tv, name: "Game of Thrones", overview: "Seven noble families fight for control of the mythical land of Westeros.",
              posterPath: "/1XS1oqL89opfnbLl8WnZY1O1uJx.jpg", backdropPath: "/2OMB0ynKlyIenMJWI2Dy9IWT4c.jpg", year: "2011", rating: 8.5, date: "2011-04-17", votes: 24000, genreIds: [10765, 18, 10759]),
        Title(id: 238, kind: .movie, name: "The Godfather", overview: "The aging patriarch of an organized crime dynasty transfers control of his empire to his reluctant son.",
              posterPath: "/3bhkrj58Vtu7enYsRolD1fZdja1.jpg", backdropPath: "/tmU7GeKVybMWFButWEGl2M4GeiP.jpg", year: "1972", rating: 8.7, date: "1972-03-14", votes: 20000, genreIds: [18, 80]),
        Title(id: 27205, kind: .movie, name: "Inception", overview: "A thief who steals corporate secrets through dream-sharing technology is given the inverse task of planting an idea.",
              posterPath: "/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg", backdropPath: "/8ZTVqvKDQ8emSGUEMjsS4yHAwrp.jpg", year: "2010", rating: 8.4, date: "2010-07-15", votes: 36000, genreIds: [28, 878, 12]),
        Title(id: 94997, kind: .tv, name: "House of the Dragon", overview: "The Targaryen dynasty is at the absolute apex of its power, two centuries before the events of Game of Thrones.",
              posterPath: "/t9XkeE7HzOsdQcDDDapDYh8Rrmt.jpg", backdropPath: "/etj8E2o0Bud0HkONVQPjyCkIvpv.jpg", year: "2022", rating: 8.3, date: "2022-08-21", votes: 4600, genreIds: [10765, 18, 10759]),
    ]

    static func items(_ range: Range<Int>, shift: Int = 0) -> [CatalogItem] {
        range.map { i in CatalogItem(title: titles[(i + shift) % titles.count]) }
    }
}

struct MockCatalog: CatalogService {
    func rows(for page: CatalogPage) async throws -> [CatalogRow] {
        try? await Task.sleep(for: .milliseconds(350))
        let t = DebugCatalog.titles
        var top = DebugCatalog.items(0..<10)
        top[0].availability = .availableNow
        top[3].availability = .camOnly
        top[5].availability = .coming("2026-11-14")
        var big = DebugCatalog.items(0..<6, shift: 2)
        big[0].kicker = "Critically Acclaimed"; big[1].kicker = "New Series"
        var spot = CatalogItem(title: t[9]); spot.kicker = "Spotlight"
        let services = CatalogRow(id: "brands", name: "Browse by Service", style: .brand,
                                  items: ["Netflix", "Hulu", "Disney+", "HBO Max", "Prime Video"].enumerated().map { CatalogItem(title: Title(id: 9000 + $0, kind: .movie, name: $1)) })
        return [
            CatalogRow(id: "picks", name: "Top Picks for You", style: .big, items: big),
            CatalogRow(id: "new", name: "New Releases", style: .landscape, items: DebugCatalog.items(0..<12, shift: 4), hasMore: true),
            CatalogRow(id: "top10", name: "Top 10 Today", style: .top10, items: top),
            CatalogRow(id: "trending", name: "Trending Now", style: .landscape, items: DebugCatalog.items(0..<12, shift: 1)),
            CatalogRow(id: "spot", name: "Spotlight", style: .big, items: [spot], isSpotlight: true),
            CatalogRow(id: "posters", name: "Critically Acclaimed", style: .poster, items: DebugCatalog.items(0..<12, shift: 7)),
            services,
            CatalogRow(id: "gems", name: "Hidden Gems", style: .landscape, items: DebugCatalog.items(0..<12, shift: 5)),
        ].filter { page == .home || $0.id != "brands" }
    }
    func more(row: CatalogRow, page: CatalogPage) async throws -> [CatalogItem] { [] }
    func spotlight(for page: CatalogPage) async throws -> [CatalogItem] {
        let picks = page == .shows ? [1, 3, 8, 11] : page == .movies ? [0, 2, 4, 10] : [2, 1, 10, 3, 0]
        return picks.map { i in
            var it = CatalogItem(title: DebugCatalog.titles[i])
            it.kicker = "Tonight\(Cinema.dot)\(DebugCatalog.titles[i].kind == .tv ? "Series" : "Movie")"
            return it
        }
    }
    func details(kind: MediaKind, id: Int) async throws -> TitleDetails {
        guard let t = DebugCatalog.titles.first(where: { $0.id == id && $0.kind == kind }) else { throw DreamError("This title isn't available right now.") }
        return TitleDetails(title: t, genres: ["Drama"], runtime: 120, recommendations: Array(DebugCatalog.titles.prefix(8)))
    }
    func episodes(showId: Int, season: Int) async throws -> [Episode] { [] }
    func search(_ query: String) async throws -> [Title] {
        try? await Task.sleep(for: .milliseconds(250))
        return DebugCatalog.titles.filter { $0.name.localizedCaseInsensitiveContains(query) }
    }
    func scores(for details: TitleDetails) async -> Scores { Scores(tomatometer: 94, audienceBadge: "IMDb \(String(format: "%.1f", details.title.rating))") }
    func availability(for title: Title) async -> Availability? { nil }
    func genres() async -> [Genre] {
        ["Action", "Comedy", "Drama", "Horror", "Sci-Fi", "Thriller", "Romance", "Animation", "Anime", "Documentary", "Family", "Crime"]
            .map { Genre(id: $0.lowercased(), name: $0) }
    }
    func services() async -> [ServiceSpec] {
        ["Netflix", "Hulu", "Prime Video", "Disney+", "HBO Max", "Apple TV", "Peacock", "Paramount+", "Crunchyroll"].map { ServiceSpec(id: $0.lowercased(), name: $0) }
    }
    func person(id: Int) async throws -> [Title] { DebugCatalog.titles }
    func chips(for page: CatalogPage) async -> [Genre] {
        switch page {
        case .movies: ["Action", "Comedy", "Drama", "Sci-Fi", "Thriller", "Horror"].map { Genre(id: "movie:" + $0, name: $0) }
        case .shows: ["Drama", "Comedy", "Crime", "Sci-Fi & Fantasy", "Mystery"].map { Genre(id: "tv:" + $0, name: $0) }
        default: []
        }
    }
    func trending() async -> [Title] { DebugCatalog.titles }
}

struct MockHistory: HistoryService {
    func continueWatching() async -> [HistoryEntry] {
        let t = DebugCatalog.titles
        return [
            HistoryEntry(title: t[1], season: 2, episode: 5, episodeName: "Breakage", positionMs: 1_200_000, durationMs: 2_820_000),
            HistoryEntry(title: t[2], positionMs: 3_600_000, durationMs: 10_140_000),
            HistoryEntry(title: t[3], season: 1, episode: 4, upNext: true),
        ]
    }
    func entry(for key: String) async -> HistoryEntry? { await continueWatching().first { $0.title.key == key } }
    func record(_ entry: HistoryEntry) async {}
    func remove(key: String) async {}
    func startPosition(for request: PlayRequest) async -> Double { 0 }
}

final class MockMyList: MyListService, @unchecked Sendable {
    static let shared = MockMyList()
    private let lock = NSLock()
    private var list: [Title] = [DebugCatalog.titles[0], DebugCatalog.titles[1], DebugCatalog.titles[8], DebugCatalog.titles[6], DebugCatalog.titles[2]]
    func items() async -> [Title] { lock.withLock { list } }
    func contains(_ key: String) async -> Bool { lock.withLock { list.contains { $0.key == key } } }
    func toggle(_ title: Title) async -> Bool {
        lock.withLock {
            if let i = list.firstIndex(where: { $0.key == title.key }) { list.remove(at: i); return false }
            list.insert(title, at: 0); return true
        }
    }
}
#endif
