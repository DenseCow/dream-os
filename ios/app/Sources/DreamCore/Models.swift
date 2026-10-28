import Foundation

// The shared data every module speaks. Mirrors the Fire TV app's types (fire-home Tmdb.kt, TitleInfo.kt,
// DebridApi.kt, WatchHistory.kt) so behaviour ports 1:1. Add fields with defaults; never rename or remove one.

public enum MediaKind: String, Codable, Sendable, Hashable {
    case movie, tv
}

public enum TMDBImage {
    public static let base = "https://image.tmdb.org/t/p/"
    public static func url(_ path: String?, _ size: String) -> URL? {
        guard let path, !path.isEmpty else { return nil }
        return URL(string: base + size + path)
    }
}

/// A movie or show as list results describe it (Tmdb.kt `Title`).
public struct Title: Codable, Sendable, Hashable, Identifiable {
    public var id: Int
    public var kind: MediaKind
    public var name: String
    public var overview: String
    public var posterPath: String?
    public var backdropPath: String?
    /// "2024"; empty when unknown.
    public var year: String
    /// TMDB vote average (0-10).
    public var rating: Double
    /// Release (movies) or first air date (shows), "yyyy-MM-dd"; empty when unknown.
    public var date: String
    public var votes: Int
    public var genreIds: [Int]
    /// Original language (ISO 639-1), "" when unknown.
    public var lang: String
    public var adult: Bool
    /// Origin countries (ISO 3166-1), used by the anime filter for non-Japanese animation.
    public var originCountry: [String]

    public init(id: Int, kind: MediaKind, name: String, overview: String = "", posterPath: String? = nil,
                backdropPath: String? = nil, year: String = "", rating: Double = 0, date: String = "", votes: Int = 0,
                genreIds: [Int] = [], lang: String = "", adult: Bool = false, originCountry: [String] = []) {
        self.id = id; self.kind = kind; self.name = name; self.overview = overview; self.posterPath = posterPath
        self.backdropPath = backdropPath; self.year = year; self.rating = rating; self.date = date; self.votes = votes
        self.genreIds = genreIds; self.lang = lang; self.adult = adult; self.originCountry = originCountry
    }

    /// "movie:603" / "tv:1396": the key history, My List and caches use (same as the Fire TV app).
    public var key: String { "\(kind.rawValue):\(id)" }
    public func poster(_ size: String = "w342") -> URL? { TMDBImage.url(posterPath, size) }
    public func backdrop(_ size: String = "w780") -> URL? { TMDBImage.url(backdropPath, size) }
}

public struct Provider: Codable, Sendable, Hashable, Identifiable {
    public var id: Int
    public var name: String
    public var logoPath: String?
    public init(id: Int, name: String, logoPath: String? = nil) { self.id = id; self.name = name; self.logoPath = logoPath }
}

public struct CastMember: Codable, Sendable, Hashable, Identifiable {
    public var id: Int
    public var name: String
    public var character: String
    public var profilePath: String?
    public init(id: Int, name: String, character: String, profilePath: String? = nil) {
        self.id = id; self.name = name; self.character = character; self.profilePath = profilePath
    }
    public func profile(_ size: String = "w185") -> URL? { TMDBImage.url(profilePath, size) }
}

public struct SeasonSummary: Codable, Sendable, Hashable, Identifiable {
    public var number: Int
    public var name: String
    public var episodeCount: Int
    /// "yyyy-MM-dd" or "".
    public var airDate: String
    public var posterPath: String?
    public var id: Int { number }
    public init(number: Int, name: String, episodeCount: Int, airDate: String = "", posterPath: String? = nil) {
        self.number = number; self.name = name; self.episodeCount = episodeCount; self.airDate = airDate; self.posterPath = posterPath
    }
}

public struct Episode: Codable, Sendable, Hashable, Identifiable {
    public var showId: Int
    public var season: Int
    public var number: Int
    public var name: String
    public var overview: String
    public var stillPath: String?
    /// "yyyy-MM-dd" or "".
    public var airDate: String
    /// Minutes, 0 = unknown.
    public var runtime: Int
    /// The exact instant it airs or drops (TVmaze), nil when only `airDate` is known.
    public var airsAt: Date? = nil
    /// Still to come (DreamContent's Upcoming split): not playable yet; word it with `DreamTime`.
    public var upcoming: Bool = false
    public var id: String { "\(showId):\(season):\(number)" }
    public init(showId: Int, season: Int, number: Int, name: String, overview: String = "", stillPath: String? = nil,
                airDate: String = "", runtime: Int = 0, airsAt: Date? = nil, upcoming: Bool = false) {
        self.showId = showId; self.season = season; self.number = number; self.name = name; self.overview = overview
        self.stillPath = stillPath; self.airDate = airDate; self.runtime = runtime; self.airsAt = airsAt; self.upcoming = upcoming
    }
    public func still(_ size: String = "w300") -> URL? { TMDBImage.url(stillPath, size) }
}

/// Everything a title page's Information section shows (TitleInfo.kt). Dates are raw "yyyy-MM-dd";
/// word them with `DreamTime`.
public struct TitleInfo: Codable, Sendable, Hashable {
    public var premiere = ""
    public var theatrical = ""
    public var digital = ""
    public var cert = ""
    public var certNote = ""
    public var runtime = 0
    public var runtimeApprox = false
    public var genres: [String] = []
    public var directors: [String] = []
    public var writers: [String] = []
    public var basedOn = ""
    public var producers: [String] = []
    public var creators: [String] = []
    public var studios: [String] = []
    public var networks: [String] = []
    public var countries: [String] = []
    public var language = ""
    public var originalTitle = ""
    public var budget: Int64 = 0
    public var revenue: Int64 = 0
    public var collection = ""
    public var status = ""
    public var lastAired = ""
    public var nextSeason = 0
    public var nextEpisode = 0
    public var nextDate = ""
    public var seasons = 0
    public var episodes = 0
    public var streaming: [String] = []
    public var free: [String] = []
    public var rentBuy: [String] = []
    /// A show's info (else a movie's).
    public var isShow = false
    /// TMDB's one-line tagline ("Long live the fighters."), "" when none.
    public var tagline = ""
    /// TMDB's own score and vote count (the Ratings cell's "TMDB 8.1" from 20 votes).
    public var score: Double = 0
    public var votes = 0
    public init() {}
}

/// A title page's data (Tmdb.kt `Details`).
public struct TitleDetails: Codable, Sendable, Hashable {
    public var title: Title
    public var genres: [String]
    /// Minutes; a show's episode length. 0 = unknown.
    public var runtime: Int
    public var trailerKey: String?
    public var providers: [Provider]
    public var cast: [CastMember]
    public var recommendations: [Title]
    public var seasons: [SeasonSummary]
    public var imdbId: String?
    /// The title's logo art (transparent PNG path), when TMDB has an English one.
    public var logoPath: String?
    public var info: TitleInfo?

    public init(title: Title, genres: [String] = [], runtime: Int = 0, trailerKey: String? = nil, providers: [Provider] = [],
                cast: [CastMember] = [], recommendations: [Title] = [], seasons: [SeasonSummary] = [], imdbId: String? = nil,
                logoPath: String? = nil, info: TitleInfo? = nil) {
        self.title = title; self.genres = genres; self.runtime = runtime; self.trailerKey = trailerKey
        self.providers = providers; self.cast = cast; self.recommendations = recommendations; self.seasons = seasons
        self.imdbId = imdbId; self.logoPath = logoPath; self.info = info
    }
    public func logo(_ size: String = "w500") -> URL? { TMDBImage.url(logoPath, size) }
}

/// Scores as Dream OS shows them (Ratings.kt): the Rotten Tomatoes tomato, then the audience score exactly as
/// published ("IMDb 6.5", else "TMDB 7.1" after 150 votes). No stars anywhere.
public struct Scores: Codable, Sendable, Hashable {
    /// Tomatometer 0-100, nil when unknown.
    public var tomatometer: Int?
    /// "IMDb 6.5" / "TMDB 7.1", nil when there is nothing worth showing.
    public var audienceBadge: String?
    public init(tomatometer: Int? = nil, audienceBadge: String? = nil) { self.tomatometer = tomatometer; self.audienceBadge = audienceBadge }
}

/// Availability card states (Availability.kt). Only these labels exist (docs STANDARDS §5).
public enum Availability: Codable, Sendable, Hashable {
    case availableNow
    case camOnly
    /// Coming on a known date ("yyyy-MM-dd"); the label words it with DreamTime.
    case coming(String)
    case notAvailableYet
    case comingSoon
}

/// How a row's cards look (CinemaPages.kt `RowKind`).
public enum RowStyle: String, Codable, Sendable, Hashable {
    case poster        // 2:3 posters
    case landscape     // 16:9 backdrops with title
    case big           // Hulu-style big wide cards with a kicker line
    case top10         // ranked with big numerals
    case continueWatching
    case brand         // service / network logos
}

public struct CatalogItem: Codable, Sendable, Hashable, Identifiable {
    public var title: Title
    /// Continue Watching / New Episodes: the episode it points at.
    public var episode: Episode?
    /// 0...1 watch progress for Continue Watching.
    public var progress: Double?
    public var availability: Availability?
    /// "For fans of comedies" above big cards.
    public var kicker: String?
    /// The caption line under the card when the row sets its own ("Nov 14 · Movie" in Coming Soon,
    /// "S2  ·  E5  ·  Aired yesterday" on New Episodes for You); nil = the card's usual year · type line.
    public var subtitle: String? = nil
    /// A corner badge in caps: "NEW EPISODE", "TONIGHT'S PICK", "NEW" (an unseen new episode).
    public var badge: String? = nil
    /// Key art: TMDB's English backdrop with the title's name on it (DreamContent KeyArt). Landscape and big cards
    /// show it before the textless backdrop; nil = none known (episode cards keep their still).
    public var keyArtPath: String? = nil
    public var id: String { title.key + (episode.map { ":\($0.season):\($0.number)" } ?? "") }
    public func keyArt(_ size: String = "w780") -> URL? { TMDBImage.url(keyArtPath, size) }
    public init(title: Title, episode: Episode? = nil, progress: Double? = nil, availability: Availability? = nil, kicker: String? = nil,
                subtitle: String? = nil, badge: String? = nil) {
        self.title = title; self.episode = episode; self.progress = progress; self.availability = availability; self.kicker = kicker
        self.subtitle = subtitle; self.badge = badge
    }
}

public struct CatalogRow: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var name: String
    public var style: RowStyle
    public var items: [CatalogItem]
    /// More pages can be fetched with `CatalogService.more(row:)`.
    public var hasMore: Bool
    /// Apple TV's Spotlight: one title across the page (style `.big`, one item; its `kicker` is the module's
    /// line: "Spotlight", "Coming Soon", "Tonight's Pick  ·  Night 26 of 31"). The row has no heading.
    public var isSpotlight: Bool = false
    /// Seasonal hub tiles shown before the items (Home's hub row: one tile, then Tonight's Pick marked
    /// "TONIGHT'S PICK"; a hub page's "More to Celebrate": tiles only). Opening one opens `.season(tile.id)`.
    public var hubs: [HubTile] = []
    public init(id: String, name: String, style: RowStyle = .poster, items: [CatalogItem], hasMore: Bool = false,
                isSpotlight: Bool = false, hubs: [HubTile] = []) {
        self.id = id; self.name = name; self.style = style; self.items = items; self.hasMore = hasMore
        self.isSpotlight = isSpotlight; self.hubs = hubs
    }
}

/// A seasonal hub (SeasonHub.kt): its tile, and its page's masthead.
public struct HubTile: Codable, Sendable, Hashable, Identifiable {
    /// "halloween", "winter" (`CatalogPage.season(id)`).
    public var id: String
    public var title: String
    /// "Halloween", "Winter".
    public var kicker: String
    public var tagline: String
    /// Line-art glyph id ("Season:Pumpkin"), drawn by DreamUI.
    public var glyph: String
    /// Index into the rainbow's colors for the glyph.
    public var hue: Int
    /// Under the tile and on the masthead: the countdown ("5 Nights Until Halloween") or the tagline.
    public var status: String
    /// "Night 26 of 31" while a nightly hub runs, else nil.
    public var night: String?
    /// The hub counts down to a day (the masthead then shows the tagline on its own line).
    public var hasCountdown: Bool
    /// The tile's small second line: the night, else the kicker (a countdown hub), else "Seasonal Collection".
    public var sub: String
    public init(id: String, title: String, kicker: String, tagline: String, glyph: String, hue: Int, status: String,
                night: String? = nil, hasCountdown: Bool = false, sub: String = "") {
        self.id = id; self.title = title; self.kicker = kicker; self.tagline = tagline; self.glyph = glyph; self.hue = hue
        self.status = status; self.night = night; self.hasCountdown = hasCountdown; self.sub = sub
    }
}

/// A streaming service page (Netflix, Hulu, Crunchyroll...), CinemaPages.kt `ServiceSpec`.
public struct ServiceSpec: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var name: String
    /// TMDB watch provider id(s) / network id(s).
    public var providerIds: [Int]
    public var networkIds: [Int]
    public var logoPath: String?
    public init(id: String, name: String, providerIds: [Int] = [], networkIds: [Int] = [], logoPath: String? = nil) {
        self.id = id; self.name = name; self.providerIds = providerIds; self.networkIds = networkIds; self.logoPath = logoPath
    }
}

public struct Genre: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var name: String
    public init(id: String, name: String) { self.id = id; self.name = name }
}

/// The pages the catalog fills with rows.
public enum CatalogPage: Codable, Sendable, Hashable {
    case home
    case movies
    case shows
    case anime
    case kids
    case service(ServiceSpec)
    case genre(Genre)
    /// Seasonal hubs (Halloween, Holidays...), SeasonHub.kt.
    case season(String)
}

extension CatalogPage {
    /// Pages that keep in-row Spotlight cards (the big card with Play): Home and the seasonal hubs. Browse pages
    /// (Movies, TV Shows, genres, categories, services) show their hero only (owner, 2026-10-28).
    public var keepsSpotlight: Bool {
        switch self {
        case .home, .season: true
        default: false
        }
    }
}

// MARK: Profiles

public struct Profile: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var name: String
    /// Avatar art id (ProfilePicker.kt).
    public var avatar: String
    public var isKids: Bool
    /// Kids rating limit ("G", "PG", "TV-Y7"...), KidsFilter.kt; nil = no limit.
    public var ratingLimit: String?
    public var hasPin: Bool
    /// The avatar's color: an index into the profile palette (`ProfileStyle.colors`, DreamPlayback). nil = by position.
    public var color: Int?
    /// Kids level (index into `KidsLevels.all`; the last one, Everyone, is no limit). nil = from `isKids`.
    public var kidsLevel: Int?
    /// A kids profile asks for a grown-up's PIN before switching away (KidsProfiles.exitPin).
    public var exitPin: Bool?
    /// "Unlock With Face ID" (Touch ID on Touch ID phones): the phone's face / fingerprint check opens this PIN-locked
    /// profile, the PIN pad stays the fallback. Read back from the Keychain like `hasPin`; nil = off.
    public var faceId: Bool?
    public init(id: String, name: String, avatar: String = "", isKids: Bool = false, ratingLimit: String? = nil, hasPin: Bool = false,
                color: Int? = nil, kidsLevel: Int? = nil, exitPin: Bool? = nil, faceId: Bool? = nil) {
        self.id = id; self.name = name; self.avatar = avatar; self.isKids = isKids; self.ratingLimit = ratingLimit; self.hasPin = hasPin
        self.color = color; self.kidsLevel = kidsLevel; self.exitPin = exitPin; self.faceId = faceId
    }
}

// MARK: Watch history

/// WatchHistory.kt `Entry`.
public struct HistoryEntry: Codable, Sendable, Hashable, Identifiable {
    public var title: Title
    public var season: Int?
    public var episode: Int?
    public var episodeName: String?
    public var stillPath: String?
    public var positionMs: Int64
    public var durationMs: Int64
    /// The next episode is queued (the last one finished).
    public var upNext: Bool
    public var updatedAt: Date
    /// Off Continue Watching: finished, or removed by the viewer (WatchHistory.kt `hidden`). Still counts for
    /// Because You Watched / Top Picks and for leaving finished titles out of rows.
    public var hidden: Bool = false
    public var id: String { title.key }
    public var progress: Double { durationMs > 0 ? min(1, Double(positionMs) / Double(durationMs)) : 0 }
    public init(title: Title, season: Int? = nil, episode: Int? = nil, episodeName: String? = nil, stillPath: String? = nil,
                positionMs: Int64 = 0, durationMs: Int64 = 0, upNext: Bool = false, updatedAt: Date = Date(), hidden: Bool = false) {
        self.title = title; self.season = season; self.episode = episode; self.episodeName = episodeName
        self.stillPath = stillPath; self.positionMs = positionMs; self.durationMs = durationMs; self.upNext = upNext
        self.updatedAt = updatedAt; self.hidden = hidden
    }
}

// MARK: Playback

/// What the viewer asked to play.
public struct PlayRequest: Codable, Sendable, Hashable {
    public var title: Title
    public var imdbId: String?
    public var season: Int?
    public var episode: Int?
    public var episodeName: String?
    /// The viewer picked "Start Over" / "From the Beginning".
    public var fromStart: Bool
    public init(title: Title, imdbId: String? = nil, season: Int? = nil, episode: Int? = nil, episodeName: String? = nil, fromStart: Bool = false) {
        self.title = title; self.imdbId = imdbId; self.season = season; self.episode = episode
        self.episodeName = episodeName; self.fromStart = fromStart
    }
}

/// One torrent a source offers (DebridApi.kt `Candidate`).
public struct StreamCandidate: Codable, Sendable, Hashable, Identifiable {
    public var hash: String
    public var torrentName: String
    public var fileName: String?
    public var fileIdx: Int?
    public var sizeBytes: Int64
    /// "4K" | "1080p" | "720p" | "SD"
    public var quality: String
    public var seeders: Int
    /// The scraper / index it came from (shown in the source picker).
    public var origin: String
    /// Debrid has it cached (instant), nil = not checked.
    public var cached: Bool?
    /// Scrapers: the provider's own quality label as it gave it ("2160p", "CAM", "TS"...), when it gave one.
    public var scraperQuality: String?
    /// Scrapers: a season pack (its size is the whole season's).
    public var pack: Bool?
    /// Dream Core, for the source list: badges the release name carries ("Dolby Vision", "HEVC", "Remux", "Atmos", "5.1").
    public var tags: [String]?
    /// Dream Core, for the source list: the viewer's source filters would hide it (Search All Sources).
    public var outsideFilters: Bool?
    /// Dream Core, for the source list: a camera / screener copy.
    public var cam: Bool?
    /// Dream Core, for the source list: the release name says another episode (shown behind "Show More").
    public var otherEpisode: Bool?
    public var id: String { hash + (fileIdx.map { ":\($0)" } ?? "") }
    public init(hash: String, torrentName: String, fileName: String? = nil, fileIdx: Int? = nil, sizeBytes: Int64 = 0,
                quality: String = "SD", seeders: Int = 0, origin: String = "", cached: Bool? = nil,
                scraperQuality: String? = nil, pack: Bool? = nil) {
        self.hash = hash; self.torrentName = torrentName; self.fileName = fileName; self.fileIdx = fileIdx
        self.sizeBytes = sizeBytes; self.quality = quality; self.seeders = seeders; self.origin = origin; self.cached = cached
        self.scraperQuality = scraperQuality; self.pack = pack
    }
}

/// A playable direct HTTPS link (DebridApi.kt `Found`).
public struct StreamLink: Codable, Sendable, Hashable {
    public var url: URL
    /// "ad" | "pm" | "tb" | "rd"
    public var service: String
    public var fileName: String
    public var sizeBytes: Int64
    public var quality: String
    public var hash: String
    /// A short note the loading screen shows when the viewer's filters were set aside
    /// ("Only a CAM Version Is Available", "Playing the Best Available Version"); nil = nothing to say.
    public var note: String?
    public init(url: URL, service: String, fileName: String, sizeBytes: Int64 = 0, quality: String = "", hash: String = "", note: String? = nil) {
        self.url = url; self.service = service; self.fileName = fileName; self.sizeBytes = sizeBytes; self.quality = quality; self.hash = hash
        self.note = note
    }
}

/// Intro / recap / credits markers (SkipMarkers.kt), seconds.
public struct SkipMarker: Codable, Sendable, Hashable {
    public enum Kind: String, Codable, Sendable { case intro, recap, credits, preview }
    public var kind: Kind
    public var start: Double
    public var end: Double
    public init(kind: Kind, start: Double, end: Double) { self.kind = kind; self.start = start; self.end = end }
}

/// A user-facing failure: `message` is a plain sentence ("Couldn't reach Trakt. Try again."), never technical.
public struct DreamError: Error, Sendable, Hashable {
    public var message: String
    /// A Title Case heading for screens that show one ("This Title Isn't Available Yet"); nil = the screen's own.
    public var title: String?
    public init(_ message: String) { self.message = message }
    public init(title: String, _ message: String) { self.title = title; self.message = message }
}
