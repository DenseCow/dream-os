import Foundation
import DreamCore

/// Title pages' data (Tmdb.fetchDetails + TitleInfo.parse), seasons' episodes (WatchHistory.seasonEpisodes +
/// Upcoming.split with TVmaze's exact air times) and logos.
enum Details {
    static func fetch(_ kind: MediaKind, _ id: Int) async throws -> TitleDetails {
        let ratingsPart = kind == .movie ? "release_dates" : "content_ratings"
        let o = try await Tmdb.object("/\(kind.rawValue)/\(id)",
                                      "&append_to_response=videos,watch/providers,credits,recommendations,external_ids,\(ratingsPart)")
        guard var t = Tmdb.parse(o, kind) else { throw DreamError("This title isn't available right now.") }
        t.genreIds = (o["genres"] as? [[String: Any]])?.compactMap { $0["id"] as? Int } ?? t.genreIds
        let genres = (o["genres"] as? [[String: Any]])?.compactMap { $0["name"] as? String } ?? []
        // Trailers play in Dream Player (TrailerEngine); the key is the video's id, never shown as a site name.
        let videos = ((o["videos"] as? [String: Any])?["results"] as? [[String: Any]] ?? []).filter { $0["site"] as? String == "YouTube" }
        let trailer = (videos.first { $0["type"] as? String == "Trailer" } ?? videos.first)?["key"] as? String
        let us = (((o["watch/providers"] as? [String: Any])?["results"] as? [String: Any])?["US"]) as? [String: Any]
        let providers = (us?["flatrate"] as? [[String: Any]] ?? []).compactMap { p -> Provider? in
            guard let pid = p["provider_id"] as? Int else { return nil }
            return Provider(id: pid, name: p["provider_name"] as? String ?? "", logoPath: Tmdb.path(p, "logo_path"))
        }
        let cast = ((o["credits"] as? [String: Any])?["cast"] as? [[String: Any]] ?? []).prefix(15).compactMap { c -> CastMember? in
            let name = Tmdb.str(c, "name"); if name.isEmpty { return nil }
            return CastMember(id: c["id"] as? Int ?? 0, name: name, character: Tmdb.str(c, "character"), profilePath: Tmdb.path(c, "profile_path"))
        }
        let recRaw = ((o["recommendations"] as? [String: Any])?["results"] as? [[String: Any]] ?? []).compactMap { r in
            Tmdb.parse(r, MediaKind(rawValue: r["media_type"] as? String ?? "") ?? kind)
        }.filter { $0.key != t.key }.prefix(20)
        let recs = await KidsFilter.titles(Array(recRaw))
        let seasons = (o["seasons"] as? [[String: Any]] ?? []).map { s in
            SeasonSummary(number: s["season_number"] as? Int ?? 0, name: Tmdb.str(s, "name"), episodeCount: s["episode_count"] as? Int ?? 0,
                          airDate: Tmdb.str(s, "air_date"), posterPath: Tmdb.path(s, "poster_path"))
        }
        let ext = o["external_ids"] as? [String: Any]
        let imdb = [Tmdb.str(ext, "imdb_id"), Tmdb.str(o, "imdb_id")].first { $0.hasPrefix("tt") }
        let runtime: Int
        if kind == .movie { runtime = o["runtime"] as? Int ?? 0 }
        else { runtime = median((o["episode_run_time"] as? [Int]) ?? []) }
        let info = parseInfo(o, t, genres)
        if !info.cert.isEmpty || kind == .movie { await KidsFilter.remember(t, usCert(o, kind)) }
        let logo = await logoPath(kind, id)
        return TitleDetails(title: t, genres: genres, runtime: runtime > 0 ? runtime : info.runtime, trailerKey: trailer, providers: providers,
                            cast: Array(cast), recommendations: recs, seasons: seasons, imdbId: imdb, logoPath: logo, info: info)
    }

    private static func usCert(_ o: [String: Any], _ kind: MediaKind) -> String {
        Certs.usCert((o[kind == .movie ? "release_dates" : "content_ratings"] as? [String: Any]) ?? [:], tv: kind == .tv)
    }

    static func median(_ v: [Int]) -> Int {
        let s = v.filter { $0 > 0 }.sorted()
        if s.isEmpty { return 0 }
        let m = s.count / 2
        return s.count % 2 == 1 ? s[m] : Int((Double(s[m - 1] + s[m]) / 2).rounded())
    }

    /// The English logo (else any, never SVG).
    static func logoPath(_ kind: MediaKind, _ id: Int) async -> String? {
        guard let o = try? await Tmdb.object("/\(kind.rawValue)/\(id)/images", "&include_image_language=en,null") else { return nil }
        let logos = (o["logos"] as? [[String: Any]] ?? []).filter { !Tmdb.str($0, "file_path").hasSuffix(".svg") }
        return (logos.first { $0["iso_639_1"] as? String == "en" } ?? logos.first).flatMap { Tmdb.path($0, "file_path") }
    }

    // MARK: TitleInfo.parse

    private static let basedOnJobs: [(String, String)] = [("Novel", "Novel"), ("Book", "Book"), ("Graphic Novel", "Graphic Novel"),
        ("Comic Book", "Comic Book"), ("Short Story", "Short Story"), ("Play", "Play"), ("Article", "Article")]

    static func parseInfo(_ o: [String: Any], _ t: Title, _ genres: [String]) -> TitleInfo {
        let show = t.kind == .tv
        func objs(_ a: Any?) -> [[String: Any]] { a as? [[String: Any]] ?? [] }
        var i = TitleInfo()
        i.isShow = show
        if !show {
            let us = objs((o["release_dates"] as? [String: Any])?["results"]).first { $0["iso_3166_1"] as? String == "US" }
            let dates = objs(us?["release_dates"])
            func first(_ types: Int...) -> String {
                dates.filter { types.contains($0["type"] as? Int ?? 0) }.map { String(Tmdb.str($0, "release_date").prefix(10)) }.filter { $0.count == 10 }.min() ?? ""
            }
            i.theatrical = first(3).isEmpty ? first(2) : first(3)
            i.digital = first(4)
            let pick = Certs.sortedDates(dates).first { !Tmdb.str($0, "certification").isEmpty }
            i.cert = Tmdb.str(pick, "certification")
            i.certNote = descriptors(pick?["descriptors"])
        } else {
            let us = objs((o["content_ratings"] as? [String: Any])?["results"]).first { $0["iso_3166_1"] as? String == "US" }
            i.cert = Tmdb.str(us, "rating")
            i.certNote = descriptors(us?["descriptors"])
        }
        let crew = objs((o["credits"] as? [String: Any])?["crew"])
        func jobs(_ js: String...) -> [String] {
            var seen = Set<String>()
            return js.flatMap { j in crew.filter { $0["job"] as? String == j }.map { Tmdb.str($0, "name") } }.filter { !$0.isEmpty && seen.insert($0).inserted }
        }
        let based = basedOnJobs.lazy.compactMap { (job, word) -> String? in
            let p = jobs(job); return p.isEmpty ? nil : "\(word) by " + p.prefix(2).joined(separator: " and ")
        }.first ?? ""
        let code = Tmdb.str(o, "original_language")
        let spoken = objs(o["spoken_languages"]).first { $0["iso_639_1"] as? String == code }.map { Tmdb.str($0, "english_name") } ?? ""
        i.language = !spoken.isEmpty ? spoken : (code.isEmpty ? "" : (Locale(identifier: "en_US").localizedString(forLanguageCode: code) ?? ""))
        i.spoken = objs(o["spoken_languages"]).map { Tmdb.str($0, "iso_639_1") }.filter { !$0.isEmpty }
        let countries: [String] = show ? ((o["origin_country"] as? [String]) ?? []).filter { $0.count == 2 }.map { countryName($0) }
            : objs(o["production_countries"]).map { countryName(Tmdb.str($0, "iso_3166_1"), Tmdb.str($0, "name")) }
        let original = Tmdb.str(o, show ? "original_name" : "original_title")
        let shown = Tmdb.str(o, show ? "name" : "title").isEmpty ? t.name : Tmdb.str(o, show ? "name" : "title")
        let runtimeShow = median((o["episode_run_time"] as? [Int]) ?? [])
        let lastEpRuntime = (o["last_episode_to_air"] as? [String: Any])?["runtime"] as? Int ?? 0
        let next = o["next_episode_to_air"] as? [String: Any]
        let providers = (((o["watch/providers"] as? [String: Any])?["results"] as? [String: Any])?["US"]) as? [String: Any]
        i.premiere = Tmdb.str(o, show ? "first_air_date" : "release_date")
        i.runtime = show ? (runtimeShow > 0 ? runtimeShow : lastEpRuntime) : (o["runtime"] as? Int ?? 0)
        i.runtimeApprox = show && runtimeShow <= 0 && lastEpRuntime > 0
        i.genres = genres
        i.directors = show ? [] : jobs("Director")
        i.writers = show ? [] : Array(jobs("Screenplay", "Writer", "Teleplay", "Story").prefix(3))
        i.basedOn = show ? "" : based
        i.producers = show ? [] : Array(jobs("Producer").prefix(3))
        i.creators = objs(o["created_by"]).map { Tmdb.str($0, "name") }.filter { !$0.isEmpty }
        i.studios = unique(objs(o["production_companies"]).map { Tmdb.str($0, "name") }.filter { !$0.isEmpty })
        i.networks = unique(objs(o["networks"]).map { Tmdb.str($0, "name") }.filter { !$0.isEmpty })
        i.countries = unique(countries.filter { !$0.isEmpty })
        i.originalTitle = !original.isEmpty && original.lowercased() != shown.lowercased() ? original : ""
        i.budget = Int64(o["budget"] as? Int ?? 0)
        i.revenue = Int64(o["revenue"] as? Int ?? 0)
        i.collection = Tmdb.str(o["belongs_to_collection"] as? [String: Any], "name")
        i.status = Tmdb.str(o, "status")
        i.lastAired = Tmdb.str(o, "last_air_date")
        i.nextSeason = next?["season_number"] as? Int ?? 0
        i.nextEpisode = next?["episode_number"] as? Int ?? 0
        i.nextDate = Tmdb.str(next, "air_date")
        i.seasons = o["number_of_seasons"] as? Int ?? 0
        i.episodes = o["number_of_episodes"] as? Int ?? 0
        i.streaming = providerNames(providers?["flatrate"])
        i.free = unique(providerNames(providers?["free"]) + providerNames(providers?["ads"]))
        i.rentBuy = unique(providerNames(providers?["rent"]) + providerNames(providers?["buy"]))
        i.score = (o["vote_average"] as? Double) ?? 0
        i.votes = o["vote_count"] as? Int ?? 0
        i.tagline = Tmdb.str(o, "tagline")
        return i
    }

    private static func unique(_ l: [String]) -> [String] { var s = Set<String>(); return l.filter { s.insert($0).inserted } }

    private static func descriptors(_ a: Any?) -> String {
        ((a as? [String]) ?? []).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty && $0 != "null" }.prefix(3).joined(separator: ", ")
    }

    private static func countryName(_ code: String, _ fallback: String = "") -> String {
        if code == "US" { return "United States" }
        if code == "GB" { return "United Kingdom" }
        if code.count == 2, let n = Locale(identifier: "en_US").localizedString(forRegionCode: code), n != code { return n }
        return fallback
    }

    /// Provider names as people say them; Dream OS's known services first; channels, ad tiers and YouTube left out
    /// (YouTube TV stays: a real streaming service; owner, 2026-10-28).
    private static func providerNames(_ a: Any?) -> [String] {
        let list = (a as? [[String: Any]] ?? []).sorted { x, y in
            let kx = Tmdb.services.contains { $0.providerId == x["provider_id"] as? Int } ? 0 : 1
            let ky = Tmdb.services.contains { $0.providerId == y["provider_id"] as? Int } ? 0 : 1
            if kx != ky { return kx < ky }
            return (x["display_priority"] as? Int ?? 999) < (y["display_priority"] as? Int ?? 999)
        }
        return unique(list.compactMap { o in
            let id = o["provider_id"] as? Int ?? 0
            if let s = Tmdb.services.first(where: { $0.providerId == id }) { return s.name }
            var n = Tmdb.str(o, "provider_name")
            let youTube = n.localizedCaseInsensitiveContains("YouTube") && n.caseInsensitiveCompare("YouTube TV") != .orderedSame
            if n.isEmpty || id == 192 || youTube || n.localizedCaseInsensitiveContains("Channel") ||
                n.localizedCaseInsensitiveContains("with Ads") { return nil }
            n = n.replacingOccurrences(of: "Paramount Plus", with: "Paramount+")
                .replacingOccurrences(of: "\\s+(Premium|Essential|Standard|Basic)$", with: "", options: .regularExpression)
            switch n {
            case "Amazon Video": return "Amazon"
            case "Amazon Prime Video": return "Prime Video"
            case "Apple TV Store": return "Apple TV"
            case "Google Play Movies": return "Google Play"
            case "tru TV": return "truTV"
            case "fuboTV": return "Fubo"
            default: return n
            }
        })
    }

    // MARK: episodes

    private static let placeholder = "^(episode|ep\\.?)\\s*#?\\d+$|^(tba|tbd|untitled)$"
    static func isPlaceholder(_ s: String?) -> Bool {
        guard let s = s?.trimmingCharacters(in: .whitespaces), !s.isEmpty, s != "null" else { return true }
        return s.range(of: placeholder, options: [.regularExpression, .caseInsensitive]) != nil
    }

    /// A season's episodes; ones still to come are marked `upcoming` with TVmaze's exact instant when known.
    static func episodes(_ showId: Int, _ season: Int) async throws -> [Episode] {
        let o = try await Tmdb.object("/tv/\(showId)/season/\(season)")
        let raw = o["episodes"] as? [[String: Any]] ?? []
        let now = Date()
        let today = DreamTime.todayYmd(now)
        let open = raw.contains { let d = Tmdb.str($0, "air_date"); return d.count != 10 || d >= today }
        let maze = open ? await AirTimes.episodes(showId) : nil
        return raw.map { e in
            let num = e["episode_number"] as? Int ?? 0
            var d = Tmdb.str(e, "air_date")
            var name = Tmdb.str(e, "name")
            var ep = Episode(showId: showId, season: season, number: num, name: name, overview: Tmdb.str(e, "overview"),
                             stillPath: Tmdb.path(e, "still_path"), airDate: d.count == 10 ? d : "", runtime: e["runtime"] as? Int ?? 0)
            guard d.count != 10 || d >= today else { return ep }
            let m = maze?.first { $0.season == season && $0.number == num }
            let at = m?.at
            let out = at.map { $0 <= now } ?? (d.count == 10 && d <= today)
            if out { ep.airsAt = at; return ep }
            if d.isEmpty, let md = m?.date, !md.isEmpty { d = md }
            if isPlaceholder(name), let mn = m?.name, !isPlaceholder(mn) { name = mn }
            ep.airDate = d; ep.name = name; ep.airsAt = at; ep.upcoming = true
            return ep
        }
    }
}

/// Exact air times from TVmaze (AirTimes.kt): free, no key. Matched once per show by IMDb id (else TheTVDB id).
actor AirTimes {
    static let shared = AirTimes()
    struct Ep: Sendable { let season: Int; let number: Int; let name: String; let date: String; let at: Date? }
    struct Info: Sendable { let prev: (Int, Int, Date)?; let next: (Int, Int, Date)?; let fetchedAt: Date }

    /// "tvmaze_ids2": "tvmaze_ids" also held lookups that never got an answer as "no such show" (2026-10-29); its
    /// matches carry over, its misses are asked again once.
    private var ids: [Int: Int] = AirTimes.storedIds()
    private static func storedIds() -> [Int: Int] {
        if let ids = DreamStore.shared.get([Int: Int].self, "tvmaze_ids2") { return ids }
        let old = (DreamStore.shared.get([Int: Int].self, "tvmaze_ids") ?? [:]).filter { $0.value > 0 }
        DreamStore.shared.set(old, "tvmaze_ids2")
        DreamStore.shared.set(nil as [Int: Int]?, "tvmaze_ids")
        return old
    }
    private var schedules: [Int: (Date, [Ep])] = [:]
    private var infos: [Int: Info] = [:]
    private var lastCall = Date.distantPast

    static func episodes(_ tmdbId: Int) async -> [Ep]? { await shared.episodes(tmdbId) }
    static func info(_ tmdbId: Int) async -> Info? { await shared.info(tmdbId) }

    private func fetch(_ url: String) async -> Any? {
        if case .found(let o) = await ask(url) { return o }
        return nil
    }

    private enum Asked { case found(Any?), notFound, noAnswer }

    /// TVmaze's answer, telling "it has no such show" (404) from "it didn't answer" (offline, timeout, 429).
    private func ask(_ url: String) async -> Asked {
        let wait = 0.55 - Date().timeIntervalSince(lastCall)
        lastCall = Date().addingTimeInterval(max(0, wait))
        if wait > 0 { try? await Task.sleep(nanoseconds: UInt64(wait * 1e9)) }
        guard let u = URL(string: url) else { return .noAnswer }
        do { return .found(try? JSONSerialization.jsonObject(with: try await Net.data(u))) }
        catch NetError.status(404, _) { return .notFound }
        catch { return .noAnswer }
    }

    private func mazeId(_ tmdbId: Int) async -> Int? {
        if let k = ids[tmdbId] { return k > 0 ? k : nil }
        guard let ext = try? await Tmdb.object("/tv/\(tmdbId)/external_ids") else { return nil }
        let imdb = Tmdb.str(ext, "imdb_id")
        let tvdb = ext["tvdb_id"] as? Int ?? 0
        var found: Int?
        // "No such show" is kept for good; a lookup that got no answer is asked again next time.
        var sure = true
        for url in [imdb.hasPrefix("tt") ? "https://api.tvmaze.com/lookup/shows?imdb=\(imdb)" : nil,
                    tvdb > 0 ? "https://api.tvmaze.com/lookup/shows?thetvdb=\(tvdb)" : nil].compactMap({ $0 }) where found == nil {
            switch await ask(url) {
            case .found(let o): found = (o as? [String: Any])?["id"] as? Int
            case .notFound: break
            case .noAnswer: sure = false
            }
        }
        guard found != nil || sure else { return nil }
        ids[tmdbId] = found ?? -1
        DreamStore.shared.set(ids, "tvmaze_ids2")
        return found
    }

    nonisolated(unsafe) private static let stamp: ISO8601DateFormatter = ISO8601DateFormatter() // read-only after init; thread-safe

    /// The exact instant, nil when TVmaze knows only the day (no airtime: its airstamp is a noon stand-in).
    private static func exact(_ o: [String: Any]) -> Date? {
        let airtime = o["airtime"] as? String ?? ""
        if airtime.trimmingCharacters(in: .whitespaces).isEmpty { return nil }
        return (o["airstamp"] as? String).flatMap { stamp.date(from: $0) }
    }

    func episodes(_ tmdbId: Int) async -> [Ep]? {
        if let s = schedules[tmdbId], Date().timeIntervalSince(s.0) < 3 * 3600 { return s.1 }
        guard let maze = await mazeId(tmdbId), let a = await fetch("https://api.tvmaze.com/shows/\(maze)/episodes") as? [[String: Any]] else { return schedules[tmdbId]?.1 }
        let eps = a.compactMap { o -> Ep? in
            guard let n = o["number"] as? Int, n > 0 else { return nil }
            let d = o["airdate"] as? String ?? ""
            return Ep(season: o["season"] as? Int ?? 0, number: n, name: o["name"] as? String ?? "", date: d.count == 10 ? d : "", at: Self.exact(o))
        }
        schedules[tmdbId] = (Date(), eps)
        return eps
    }

    /// What TVmaze said last, any age, without asking.
    func cached(_ tmdbId: Int) -> Info? { infos[tmdbId] }

    /// One call per show at a time: the New Episodes row, its banner and the pills ask for the same shows at launch.
    private var infoRunning: [Int: Task<Info?, Never>] = [:]

    func info(_ tmdbId: Int) async -> Info? {
        if let i = infos[tmdbId], Date().timeIntervalSince(i.fetchedAt) < 3 * 3600 { return i }
        if let r = infoRunning[tmdbId] { return await r.value }
        let t = Task { await self.loadInfo(tmdbId) }
        infoRunning[tmdbId] = t
        let i = await t.value
        infoRunning[tmdbId] = nil
        return i
    }

    private func loadInfo(_ tmdbId: Int) async -> Info? {
        guard let maze = await mazeId(tmdbId),
              let o = await fetch("https://api.tvmaze.com/shows/\(maze)?embed%5B%5D=previousepisode&embed%5B%5D=nextepisode") as? [String: Any] else { return infos[tmdbId] }
        let emb = o["_embedded"] as? [String: Any]
        func air(_ e: Any?) -> (Int, Int, Date)? {
            guard let e = e as? [String: Any], let at = Self.exact(e), let n = e["number"] as? Int else { return nil }
            return (e["season"] as? Int ?? 0, n, at)
        }
        let i = Info(prev: air(emb?["previousepisode"]), next: air(emb?["nextepisode"]), fetchedAt: Date())
        infos[tmdbId] = i
        return i
    }
}
