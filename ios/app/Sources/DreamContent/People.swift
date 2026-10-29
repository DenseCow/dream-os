import Foundation
import DreamCore

// People (PlayerXRay.kt `XRayData`, gaps worker): the person page's facts and Known For, and X-Ray's per-episode cast.
// TMDB answers come through `Tmdb.object` (memory + disk caches); the parsed results are kept in `PeopleCache`.

extension ContentCatalog {
    func personDetails(id: Int, excluding titleKey: String?) async -> PersonDetails? {
        guard Tmdb.hasKey else { return nil }
        if let hit = await PeopleCache.shared.person(id) { return await People.without(hit, titleKey) }
        guard let p = await People.fetch(id) else { return nil }
        await PeopleCache.shared.put(p)
        return await People.without(p, titleKey)
    }

    func cast(for request: PlayRequest) async -> [CastMember] {
        guard Tmdb.hasKey, request.title.id > 0 else { return [] }
        let key = "\(request.title.kind.rawValue):\(request.title.id):\(request.season ?? -1):\(request.episode ?? -1)"
        if let hit = await PeopleCache.shared.cast(key) { return hit }
        let (list, complete) = await People.cast(request)
        // Remembered only when every lookup answered (a network hiccup isn't "nobody's in it").
        if complete || list.count >= 3 { await PeopleCache.shared.put(key, list) }
        return list
    }
}

enum People {
    static let maxFaces = 24
    static let maxTitles = 14
    private static let skipGenres: Set<Int> = [10767, 10763] // talk, news

    /// An episode: its own cast, then its guest stars; when TMDB has fewer than three for the episode, the show's
    /// whole cast (each actor's most frequent role). A movie: its cast. Top billing first, no repeats.
    static func cast(_ r: PlayRequest) async -> ([CastMember], Bool) {
        var out: [CastMember] = []
        var ids = Set<Int>()
        var complete = true
        func add(_ rows: [[String: Any]]?, aggregate: Bool) {
            for o in (rows ?? []).sorted(by: { ($0["order"] as? Int ?? 999) < ($1["order"] as? Int ?? 999) }) {
                if out.count >= maxFaces { return }
                guard let id = o["id"] as? Int, id > 0, !ids.contains(id) else { continue }
                let name = Tmdb.str(o, "name")
                if name.isEmpty { continue }
                let character: String
                if aggregate {
                    let roles = o["roles"] as? [[String: Any]] ?? []
                    character = Tmdb.str(roles.max { ($0["episode_count"] as? Int ?? 0) < ($1["episode_count"] as? Int ?? 0) }, "character")
                } else {
                    character = Tmdb.str(o, "character")
                }
                ids.insert(id)
                out.append(CastMember(id: id, name: name, character: character, profilePath: Tmdb.path(o, "profile_path")))
            }
        }
        let id = r.title.id
        if r.title.kind == .tv {
            if let s = r.season, let e = r.episode {
                let c = try? await Tmdb.object("/tv/\(id)/season/\(s)/episode/\(e)/credits")
                if c == nil { complete = false }
                add(c?["cast"] as? [[String: Any]], aggregate: false)
                add(c?["guest_stars"] as? [[String: Any]], aggregate: false)
            }
            if out.count < 3 {
                let c = try? await Tmdb.object("/tv/\(id)/aggregate_credits")
                if c == nil { complete = false }
                add(c?["cast"] as? [[String: Any]], aggregate: true)
            }
        } else {
            let c = try? await Tmdb.object("/movie/\(id)/credits")
            if c == nil { complete = false }
            add(c?["cast"] as? [[String: Any]], aggregate: false)
        }
        return (out, complete)
    }

    /// One person: born / died and birthplace, the biography, and their best-known titles (Kids limits applied).
    static func fetch(_ id: Int) async -> PersonDetails? {
        guard let j = try? await Tmdb.object("/person/\(id)", "&append_to_response=combined_credits") else { return nil }
        let born = Tmdb.str(j, "birthday"), died = Tmdb.str(j, "deathday")
        // The title page's long date ("July 31, 1970"), date-only so it never shifts a day.
        let b = DreamTime.longDate(born), d = DreamTime.longDate(died)
        let life: String? = if let b, let d { "\(b) – \(d)" } else if let b { "Born \(b)" } else { nil }
        let place = Tmdb.str(j, "place_of_birth")
        let meta = [life, place.isEmpty ? nil : place].compactMap { $0 }.joined(separator: "  ·  ")
        let credits = j["combined_credits"] as? [String: Any]
        let selfRole = try? NSRegularExpression(pattern: "\\b(self|himself|herself|narrator|host)\\b", options: .caseInsensitive)
        func isSelf(_ s: String) -> Bool { selfRole?.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil }
        var seen = Set<String>()
        let rows = credits?["cast"] as? [[String: Any]] ?? []
        let all = rows.compactMap { o -> Title? in isSelf(Tmdb.str(o, "character")) ? nil : Tmdb.parse(o, nil) }
            .filter { t in t.posterPath != nil && !t.adult && !t.genreIds.contains(where: skipGenres.contains) && seen.insert(t.key).inserted }
            .sorted { $0.votes > $1.votes }
        // Known titles first; a newcomer's handful of small ones still shows.
        let known = { let k = all.filter { $0.votes >= 20 }; return k.count >= 4 ? k : all }()
        // Kept unfiltered (the cache is shared by every profile): `without` applies the current profile's limit.
        return PersonDetails(id: id, name: Tmdb.str(j, "name"), profilePath: Tmdb.path(j, "profile_path"), meta: meta,
                             bio: Tmdb.str(j, "biography"), knownFor: Array(known.prefix(40)), department: Tmdb.str(j, "known_for_department"))
    }

    static func without(_ p: PersonDetails, _ key: String?) async -> PersonDetails {
        var q = p
        q.knownFor = Array(await KidsFilter.titles(p.knownFor.filter { $0.key != key }).prefix(maxTitles))
        return q
    }
}


/// Parsed people and casts for the session (XRayData's small LRUs, a little larger).
actor PeopleCache {
    static let shared = PeopleCache()
    private var people: [Int: PersonDetails] = [:]
    private var casts: [String: [CastMember]] = [:]

    func person(_ id: Int) -> PersonDetails? { people[id] }
    func put(_ p: PersonDetails) { if people.count > 64 { people.removeAll() }; people[p.id] = p }
    func cast(_ key: String) -> [CastMember]? { casts[key] }
    func put(_ key: String, _ list: [CastMember]) { if casts.count > 32 { casts.removeAll() }; casts[key] = list }
}
