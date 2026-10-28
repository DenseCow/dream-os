import Foundation
import DreamCore

/// Search by genre (GenreSearch.kt): "horror", "horror movies", "crime shows", "romcom", "anime horror". A title search is
/// never taken over: the whole query has to name a category (or, from four letters, begin exactly one's words).
enum GenreSearch {
    struct Target: Sendable { let spec: GenreSpec; let label: String; var shelf: String? = nil; var with: GenreSpec? = nil
        var anime: Bool { spec.anime || with?.anime == true } }
    struct Match: Sendable { let target: Target; let movies: Bool; let shows: Bool }

    private static let movieWords: Set<String> = ["movies", "movie", "films", "film", "flicks"]
    private static let showWords: Set<String> = ["shows", "show", "series", "tv", "television", "programs"]
    private static let filler: Set<String> = ["best", "top", "good", "great", "popular", "some", "all"]

    private static let wordsFor: [String: [String]] = [
        "Action": ["action", "action adventure", "action and adventure"], "Adventure": ["adventure", "adventures"],
        "Comedy": ["comedy", "comedies", "funny"], "Drama": ["drama", "dramas"], "Horror": ["horror", "scary", "spooky", "scary stuff"],
        "Thriller": ["thriller", "thrillers", "suspense"], "Sci-Fi": ["sci fi", "science fiction", "scifi"], "Fantasy": ["fantasy", "fantasies"],
        "Romance": ["romance", "romantic", "romances", "love stories"], "Crime": ["crime", "crimes"],
        "Mystery": ["mystery", "mysteries", "whodunit", "whodunits", "whodunnit"], "Animation": ["animation", "animated", "cartoon", "cartoons"],
        "Anime": ["anime", "animes"], "Family": ["family", "family friendly"], "Kids": ["kids", "kid", "children", "childrens", "for kids"],
        "Documentary": ["documentary", "documentaries", "docs", "docuseries", "docu series"], "Reality": ["reality", "reality tv"],
        "History": ["history", "historical", "period", "period dramas", "period pieces"], "War": ["war"],
        "Western": ["western", "westerns", "cowboy", "cowboys"], "Music": ["music"], "Halloween": ["halloween"],
        "Holiday": ["holiday", "holidays", "christmas", "xmas"],
        "Adult Animation": ["adult animation", "adult animated", "adult cartoons", "adult cartoon", "animation for adults"],
        "K-Drama": ["k drama", "k dramas", "korean drama", "korean dramas", "korean"], "True Crime": ["true crime"],
        "Stand-Up Comedy": ["stand up", "stand up comedy", "stand up specials", "comedy specials", "standup"], "Sitcoms": ["sitcom", "sitcoms"],
        "Superheroes": ["superhero", "superheroes", "superheros", "super heroes", "super hero"], "Musicals": ["musical", "musicals"],
        "Based on a True Story": ["based on a true story", "based on true story", "based on true events", "true story", "true stories"],
        "Feel-Good": ["feel good", "feelgood", "uplifting", "heartwarming"],
        "Mind-Bending": ["mind bending", "mind benders", "mind bender", "mind blowing", "mindbending"], "Sports": ["sports", "sport"],
        "Teen": ["teen", "teens", "teenage", "high school"], "LGBTQ+": ["lgbt", "lgbtq", "lgbtq+", "gay", "queer"],
        "’70s Classics": ["70s", "1970s", "seventies", "70s classics"], "’80s Classics": ["80s", "1980s", "eighties", "80s classics"],
        "’90s Classics": ["90s", "1990s", "nineties", "90s classics"], "2000s Classics": ["2000s", "00s", "noughties"],
    ]

    private static let subs: [([String], String, String)] = [
        (["romcom", "romcoms", "rom com", "rom coms", "romantic comedy", "romantic comedies"], "Comedy", "Romantic Comedies"),
        (["zombie", "zombies"], "Horror", "Zombies"), (["slasher", "slashers"], "Horror", "Slashers"), (["found footage"], "Horror", "Found Footage"),
        (["vampire", "vampires", "werewolf", "werewolves"], "Horror", "Vampires & Werewolves"),
        (["haunted house", "haunted houses", "ghost", "ghosts"], "Horror", "Haunted Houses"), (["heist", "heists"], "Action", "Heists"),
        (["spy", "spies", "secret agent", "secret agents"], "Action", "Spies & Secret Agents"), (["martial arts", "kung fu"], "Action", "Martial Arts"),
        (["disaster", "disaster movies"], "Action", "Disaster Movies"), (["time travel"], "Sci-Fi", "Time Travel"),
        (["aliens", "alien invasion"], "Sci-Fi", "Aliens & First Contact"), (["dystopian", "dystopia"], "Sci-Fi", "Dystopian Futures"),
        (["post apocalyptic", "apocalypse"], "Sci-Fi", "Post-Apocalyptic"), (["cyberpunk"], "Sci-Fi", "Cyberpunk"),
        (["space opera", "space operas"], "Sci-Fi", "Space Operas"), (["dinosaur", "dinosaurs"], "Adventure", "Dinosaurs"),
        (["pirate", "pirates"], "Adventure", "Pirates & Swashbucklers"), (["dark comedy", "dark comedies", "black comedy"], "Comedy", "Dark Comedies"),
        (["mockumentary", "mockumentaries"], "Comedy", "Mockumentaries"), (["biopic", "biopics"], "Drama", "Biopics"),
        (["legal drama", "legal dramas", "courtroom"], "Drama", "Legal Dramas"), (["medical drama", "medical dramas"], "Drama", "Medical Dramas"),
        (["coming of age"], "Drama", "Coming of Age"), (["serial killer", "serial killers"], "Crime", "Serial Killers"),
        (["gangster", "gangsters", "mafia", "mob"], "Crime", "Gangsters & the Mob"), (["detective", "detectives"], "Crime", "Detectives"),
        (["cop shows", "police", "police procedural", "police procedurals"], "Crime", "Police Procedurals"), (["isekai"], "Anime", "Isekai"),
        (["shonen", "shounen"], "Anime", "Shounen"), (["mecha"], "Anime", "Mecha"), (["slice of life"], "Anime", "Slice of Life"),
        (["dating shows", "dating show"], "Reality", "Dating Shows"),
        (["cooking shows", "cooking show", "cooking competitions", "cooking"], "Reality", "Cooking Competitions"),
        (["competition shows", "game shows"], "Reality", "Competition Shows"), (["ww2", "wwii", "world war 2", "world war ii"], "War", "World War II"),
    ]

    private static func words(_ s: String) -> [String] {
        s.lowercased().replacingOccurrences(of: "’", with: "").replacingOccurrences(of: "'", with: "")
            .replacingOccurrences(of: "&", with: " and ").replacingOccurrences(of: "+", with: "")
            .split(whereSeparator: { !($0.isASCII && ($0.isLetter || $0.isNumber)) }).map(String.init)
    }
    private static func squash(_ s: String) -> String { words(s).joined() }

    private static let index: [(String, Target)] = {
        var out: [(String, Target)] = []
        var seen = Set<String>()
        func put(_ k: String, _ t: Target) { if seen.insert(k).inserted { out.append((k, t)) } }
        let all = GenreSpec.shortcuts + GenreSpec.categories + GenreSpec.decades
        for spec in all {
            let t = Target(spec: spec, label: spec.name)
            put(squash(spec.name), t)
            for w in wordsFor[spec.name] ?? [] { put(squash(w), t) }
        }
        for (ws, name, shelf) in subs {
            guard let spec = all.first(where: { $0.name == name }), GenreShelves.curated(spec).contains(where: { $0.title == shelf }) else { continue }
            let t = Target(spec: spec, label: shelf, shelf: shelf)
            for w in ws { put(squash(w), t) }
        }
        return out
    }()
    private static func lookup(_ k: String) -> Target? { index.first { $0.0 == k }?.1 }

    private static func prefix(_ part: String) -> Target? {
        guard part.count >= 4 else { return nil }
        var labels = Set<String>(); var hit: Target?
        for (k, t) in index where k.hasPrefix(part) { if labels.insert(t.label).inserted { hit = t } }
        return labels.count == 1 ? hit : nil
    }

    static func match(_ query: String) -> Match? {
        var w = words(query)
        if w.isEmpty { return nil }
        var movies = false, shows = false
        while w.count > 1 {
            if let l = w.last, movieWords.contains(l) { movies = true; w.removeLast() }
            else if let l = w.last, showWords.contains(l) { shows = true; w.removeLast() }
            else if movieWords.contains(w[0]) { movies = true; w.removeFirst() }
            else if showWords.contains(w[0]) { shows = true; w.removeFirst() }
            else if filler.contains(w[0]) { w.removeFirst() }
            else { break }
        }
        if movies && shows { movies = false; shows = false }
        let both = !movies && !shows
        let whole = w.joined()
        guard let target = lookup(whole) ?? combo(w) ?? ((w.count == 1 || index.contains { $0.0.hasPrefix(whole) }) ? prefix(whole) : nil) else { return nil }
        let hasMovie = filter(target, .movie) != nil, hasTv = filter(target, .tv) != nil
        if !hasMovie && !hasTv { return nil }
        return Match(target: target, movies: (both || movies) && hasMovie || (shows && !hasTv), shows: (both || shows) && hasTv || (movies && !hasMovie))
    }

    private static func combo(_ w: [String]) -> Target? {
        guard w.count >= 2 else { return nil }
        for i in 1..<w.count {
            guard let a = lookup(w[..<i].joined()), a.shelf == nil, let b = lookup(w[i...].joined()), b.shelf == nil else { continue }
            if a.spec.name == b.spec.name || (!a.spec.year.isEmpty && !b.spec.year.isEmpty) { continue }
            let (lead, with) = (!b.spec.lang.isEmpty || !b.spec.year.isEmpty) ? (b, a) : (a, b)
            return Target(spec: lead.spec, label: "\(lead.spec.adj) \(with.spec.name)", with: with.spec)
        }
        return nil
    }

    private static func filter(_ t: Target, _ type: MediaKind) -> String? {
        if let name = t.shelf { return GenreShelves.curated(t.spec).first { $0.title == name }?.query(type) }
        guard let a = GenreShelves.clause(t.spec, type) else { return nil }
        guard let with = t.with else { return GenreShelves.ofType(t.spec, type)?.query(type) }
        guard let b = GenreShelves.clause(with, type) else { return nil }
        return GenreShelves.merge(a, b) + "&vote_count.gte=\(type == .movie ? 40 : 20)"
    }

    static func heading(_ m: Match, _ type: MediaKind) -> String {
        let t = m.target
        if t.shelf != nil { return m.movies && m.shows && type == .tv ? "\(t.label) on TV" : t.label }
        if t.with != nil { return "\(t.label) \(type == .movie ? "Movies" : "Shows")" }
        return t.spec.rowTitle(type)
    }

    static func load(_ m: Match, _ type: MediaKind, _ ctx: ProfileContext) async -> [Title] {
        guard let q = filter(m.target, type) else { return [] }
        let shelf = Shelf(title: heading(m, type), mode: .variety, movie: type == .movie ? q : nil, tv: type == .tv ? q : nil)
        return RowMix.tidy(ctx, await GenreShelves.load(m.target.spec, shelf).filter { $0.kind == type }, artFirst: false, anime: m.target.anime)
    }

    /// The genre rows for a search, when it names one and they bring enough titles (CinemaSearch GENRE_MIN = 5).
    static func hits(_ q: String) async -> SearchResults.GenreHits? {
        guard let m = match(q) else { return nil }
        let ctx = await ProfileContext.current()
        async let mv = m.movies ? load(m, .movie, ctx) : []
        async let tv = m.shows ? load(m, .tv, ctx) : []
        let (a, b) = (await mv, await tv)
        if a.count + b.count < 5 { return nil }
        let browse = [m.target.spec, m.target.with].compactMap { $0 }.map { Genre(id: $0.name, name: $0.name) }
        return SearchResults.GenreHits(label: m.target.label, browse: browse, movieHeading: heading(m, .movie), movies: a, showHeading: heading(m, .tv), shows: b)
    }

    /// Category names that begin with what's typed (2+ letters).
    static func suggest(_ query: String, max: Int = 2) -> [String] {
        let q = squash(query)
        guard q.count >= 2 else { return [] }
        let all = GenreSpec.shortcuts + GenreSpec.categories.filter { $0.inSeason() } + GenreSpec.decades
        let named = all.filter { squash($0.name).hasPrefix(q) && squash($0.name) != q }.map(\.name)
        let byWord = q.count < 3 ? [] : all.filter { s in !named.contains(s.name) && (wordsFor[s.name] ?? []).contains { squash($0).hasPrefix(q) && squash($0) != q } }.map(\.name)
        return Array((named + byWord).prefix(max))
    }
}
