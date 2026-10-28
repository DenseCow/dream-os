import Foundation
import DreamCore

/// My List (Tmdb.myList / toggleMyList + CinemaMyList's data): the saved titles, newest first, per profile.
public final class MyList: @unchecked Sendable {
    public static let shared = MyList()
    private let lock = NSLock()

    public func items() -> [Title] { lock.withLock { DreamStore.shared.get([Title].self, "mylist", perProfile: true) ?? [] } }
    public func contains(_ key: String) -> Bool { items().contains { $0.key == key } }

    /// Adds [t] at the front, or takes it off. True when it is saved now.
    @discardableResult
    public func toggle(_ t: Title) -> Bool {
        let added: Bool = lock.withLock {
            var list = DreamStore.shared.get([Title].self, "mylist", perProfile: true) ?? []
            let had = list.contains { $0.key == t.key }
            if had { list.removeAll { $0.key == t.key } } else { list.insert(t, at: 0) }
            DreamStore.shared.set(list, "mylist", perProfile: true)
            return !had
        }
        Task { @MainActor in NotificationCenter.default.post(name: .dreamMyListChanged, object: nil) }
        if let s = WatchHistory.sync { Task.detached { await s.myListChanged(t, added: added) } } // the Trakt watchlist follows
        return added
    }

    // MARK: The My List page

    public enum Kind: String, CaseIterable, Sendable { case all, movie, tv }
    public enum Order: String, CaseIterable, Sendable { case recent, az }

    /// The page's chips, kept per profile ("All · Movies · TV Shows", "Recently Added · A–Z").
    public var view: (kind: Kind, order: Order) {
        get {
            let v = (DreamStore.shared.get(String.self, "mylist_view", perProfile: true) ?? "").split(separator: "|").map(String.init)
            return (Kind(rawValue: v.first ?? "") ?? .all, Order(rawValue: v.count > 1 ? v[1] : "") ?? .recent)
        }
        set { DreamStore.shared.set("\(newValue.kind.rawValue)|\(newValue.order.rawValue)", "mylist_view", perProfile: true) }
    }

    public struct Section: Sendable, Identifiable {
        public let id: String
        /// "Continue Watching", "Movies", "TV Shows".
        public let name: String
        public let titles: [Title]
        /// Continue Watching only: each title's entry (progress or Up Next).
        public let progress: [WatchHistory.Entry]
    }

    public struct Page: Sendable {
        public let sections: [Section]
        /// "12 Titles  ·  5 Movies  ·  7 TV Shows"; "" when nothing is saved.
        public let summary: String
        public let isEmpty: Bool
        /// The empty state for the chosen kind: (title, line), e.g. ("No Movies Saved Yet", "Open any movie ...").
        public let empty: (String, String)?
    }

    /// The page as CinemaMyList builds it: Continue Watching (saved titles part way through, newest first), Movies and
    /// TV Shows, filtered and ordered by the chips.
    public func page() -> Page {
        let saved = items()
        let (kind, order) = view
        let byKey = Dictionary(saved.map { ($0.key, $0) }, uniquingKeysWith: { a, _ in a })
        func keep(_ t: Title) -> Bool { kind == .all || t.kind.rawValue == kind.rawValue }
        var seen = Set<String>()
        let going = WatchHistory.shared.entries().filter { e in
            guard let t = byKey[e.key], keep(t), e.upNext || e.progress > 0 else { return false }
            return seen.insert(e.key).inserted
        }
        func sort(_ l: [Title]) -> [Title] {
            order == .az ? l.sorted { Self.sortName($0.name).localizedCaseInsensitiveCompare(Self.sortName($1.name)) == .orderedAscending } : l
        }
        let movies = sort(saved.filter { $0.kind == .movie })
        let shows = sort(saved.filter { $0.kind == .tv })
        var sections: [Section] = []
        if !going.isEmpty { sections.append(Section(id: "continue", name: "Continue Watching", titles: going.compactMap { byKey[$0.key] }, progress: going)) }
        if kind != .tv && !movies.isEmpty { sections.append(Section(id: "movies", name: "Movies", titles: movies, progress: [])) }
        if kind != .movie && !shows.isEmpty { sections.append(Section(id: "shows", name: "TV Shows", titles: shows, progress: [])) }
        let empty: (String, String)?
        if saved.isEmpty { empty = ("Nothing Saved Yet", "Open any movie or show and choose My List. It will wait for you here.") }
        else if kind == .movie && movies.isEmpty { empty = ("No Movies Saved Yet", "Open any movie and choose My List. It will wait for you here.") }
        else if kind == .tv && shows.isEmpty { empty = ("No TV Shows Saved Yet", "Open any show and choose My List. It will wait for you here.") }
        else { empty = nil }
        return Page(sections: sections, summary: Self.summary(movies: movies.count, shows: shows.count), isEmpty: saved.isEmpty, empty: empty)
    }

    static func summary(movies: Int, shows: Int) -> String {
        if movies + shows == 0 { return "" }
        func n(_ c: Int, _ one: String, _ many: String) -> String { "\(c) \(c == 1 ? one : many)" }
        return [n(movies + shows, "Title", "Titles"), movies > 0 ? n(movies, "Movie", "Movies") : nil, shows > 0 ? n(shows, "TV Show", "TV Shows") : nil]
            .compactMap { $0 }.joined(separator: "  ·  ")
    }

    /// Sorting name: "The Office" files under O, "A Quiet Place" under Q.
    public static func sortName(_ n: String) -> String {
        let s = n.trimmingCharacters(in: .whitespaces)
        for a in ["the ", "a ", "an "] where s.count > a.count && s.lowercased().hasPrefix(a) { return String(s.dropFirst(a.count)) }
        return s
    }
}

struct DreamMyListService: MyListService {
    func items() async -> [Title] { MyList.shared.items() }
    func contains(_ key: String) async -> Bool { MyList.shared.contains(key) }
    func toggle(_ title: Title) async -> Bool { MyList.shared.toggle(title) }
}
