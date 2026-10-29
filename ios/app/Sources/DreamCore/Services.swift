import Foundation

// Service contracts: the seams between modules. Screens (DreamOS) and the player (DreamPlayer) talk only to these;
// DreamContent / DreamPlayback / DreamScrapers implement them and the app registers the real ones at launch
// (`Services.shared`). Until then the empty stand-ins below keep everything compiling and running.
// Extend a protocol by adding a requirement WITH a default implementation in an extension, so other modules keep
// building. Never rename or remove a requirement.

/// TMDB catalog: rows, details, search, browse (DreamContent).
public protocol CatalogService: Sendable {
    /// The rows a page shows, in order, already filtered for the current profile (anime rule, Kids limits).
    func rows(for page: CatalogPage) async throws -> [CatalogRow]
    /// The next page of a row's items.
    func more(row: CatalogRow, page: CatalogPage) async throws -> [CatalogItem]
    /// Hero / Spotlight titles for a page.
    func spotlight(for page: CatalogPage) async throws -> [CatalogItem]
    func details(kind: MediaKind, id: Int) async throws -> TitleDetails
    func episodes(showId: Int, season: Int) async throws -> [Episode]
    func search(_ query: String) async throws -> [Title]
    func scores(for details: TitleDetails) async -> Scores
    func availability(for title: Title) async -> Availability?
    /// Browse: genre cards and services.
    func genres() async -> [Genre]
    func services() async -> [ServiceSpec]
    /// Filmography of a cast member.
    func person(id: Int) async throws -> [Title]
    /// The genre chips under a page's hero (Movies / TV Shows: "Action", "Comedy"...; CinemaPages.kt MOVIE_CHIPS /
    /// TV_CHIPS), without "All". Picking one shows `rows(for: .genre(chip))` under the chips. Empty: no chip line.
    func chips(for page: CatalogPage) async -> [Genre]
    /// Search's "Trending Now" while the field is empty (Tmdb.trending, posters only).
    func trending() async -> [Title]
    /// An episode's status pill (Availability.episode). Title pages ask only for episodes aired in the last 21 days.
    /// Added by the Pages worker; default nil (no pill).
    func episodeAvailability(_ episode: Episode, of show: Title) async -> Availability?

    // Content worker additions (defaults in ContentContract.swift).
    /// The page's rows in order as empty placeholders (id, name, style); fill each as it nears the screen.
    /// Calling it starts a fresh page (rows claim their titles anew: each title shows in one row only).
    func rowSlots(for page: CatalogPage) async -> [CatalogRow]
    /// One placeholder's titles; nil = the row is left out (too few titles).
    func fill(row: CatalogRow, page: CatalogPage) async throws -> CatalogRow?
    /// Search as CinemaSearch shows it: spoken-title search, a person's titles, a genre's rows.
    func searchAll(_ query: String) async -> SearchResults
    /// Genre names that begin with what's typed (the suggestions).
    func suggestions(_ query: String) async -> [String]
    /// Browse's Discover wall, one page.
    func discover(_ filter: DiscoverFilter, page: Int) async throws -> DiscoverPage
    /// The wall's genre chips for a kind ("Any Genre" is id 0, not listed).
    func discoverCategories(_ kind: MediaKind) async -> [DiscoverCategory]
    /// A seasonal hub's masthead / tile.
    func hub(id: String) async -> HubTile?
    /// Every seasonal hub on today for this profile, the one Home shows first (Browse's Seasons & Events row).
    func hubs() async -> [HubTile]
    /// The title page's availability (with best release and note).
    func availabilityDetail(for title: Title) async -> AvailabilityDetail?
    /// A New Episodes for You card was seen (its "NEW" badge goes).
    func markEpisodeSeen(_ item: CatalogItem) async
    /// The title's English logo art path (transparent PNG), nil when none.
    func titleLogo(kind: MediaKind, id: Int) async -> String?
    /// The current profile may open [title] (Kids limit); fetches its rating when unknown.
    func allowed(_ title: Title) async -> Bool

    // Notices worker addition (default in NewEpisodesContract.swift).
    /// The followed shows' recent and upcoming episodes (Home's New Episode banner and its notice).
    func newEpisodes() async -> NewEpisodesSnapshot

    // Speed worker addition (default in Speed.swift).
    /// A row's fetches run ahead without claiming titles (the page then fills every row at once, claims top-down).
    func warm(row: CatalogRow, page: CatalogPage) async

    // Instant pills addition (defaults in PillsContract.swift).
    /// Availability for [titles] ahead of the screen, in the order given, at background priority (never in a Play's
    /// way). `answer(titleKey, state)` runs once per title with a real answer (nil: no pill); a failed check isn't one.
    func availabilityAhead(_ titles: [Title], answer: @escaping @Sendable (String, Availability?) async -> Void) async
    /// Every stored card answer by title key, for showing pills on the first frame after a launch.
    func availabilityKnown() async -> [String: Availability]
    /// The title can wear an availability pill at all (STANDARDS §5: new movies and brand-new shows). No lookup.
    func mayHavePill(_ title: Title) -> Bool
    /// A card's earlier answer with its best copy, no network (a title page's first frame).
    func availabilityPeek(for title: Title) async -> AvailabilityDetail?

    // Gaps worker additions (defaults in PeopleContract.swift).
    /// One person's facts, biography and best-known titles (not [titleKey], the title they're seen in), nil when unknown.
    func personDetails(id: Int, excluding titleKey: String?) async -> PersonDetails?
    /// Who's in what plays (X-Ray): an episode's own cast and guest stars (the show's whole cast when TMDB lists fewer
    /// than three), a movie's cast; top billing first, 24 at most.
    func cast(for request: PlayRequest) async -> [CastMember]

    // Watched worker addition (default in Watched.swift).
    /// A show's latest aired episode and its next air day (the Watched pill's "caught up" rule); nil when unknown.
    func showAirState(showId: Int) async -> ShowAirState?
}

public extension CatalogService {
    func episodeAvailability(_ episode: Episode, of show: Title) async -> Availability? { nil }
    func chips(for page: CatalogPage) async -> [Genre] { [] }
    func trending() async -> [Title] {
        let rows = (try? await rows(for: .home)) ?? []
        return (rows.first { $0.name.hasPrefix("Trending") } ?? rows.first)?.items.map(\.title) ?? []
    }
}

/// Profiles, Kids limits, PINs (DreamPlayback).
public protocol ProfileService: Sendable {
    func profiles() async -> [Profile]
    func current() async -> Profile?
    func select(_ id: String, pin: String?) async throws
    func save(_ profile: Profile, pin: String?) async throws
    func delete(_ id: String) async throws
    /// Checks a profile's PIN without switching to it (editing a locked profile, a Kids profile's PIN to leave).
    /// Throws a DreamError with the plain reason ("Wrong PIN, try again", "Too many tries..."). Added by the Pages
    /// worker; the default falls back to `select`, so implementations should override it.
    func checkPin(_ id: String, pin: String) async throws
    /// Face ID for profile PINs (owner pick, 2026-10-28). True when opening [id] may use the phone's Face ID / Touch ID:
    /// the profile whose PIN it needs (with `switches`, the one `select` asks for; without, [id]'s own) has Unlock With
    /// Face ID on, the phone can check, and its enrolled faces haven't changed since. Default: false.
    func faceIdReady(_ id: String, switches: Bool) async -> Bool
    /// Runs the phone's Face ID / Touch ID check for [id] (as in `faceIdReady`) and, when it passes, switches to it
    /// (`switches`) or only confirms. Throws a DreamError with the plain reason when it fails, CancellationError when
    /// the viewer cancelled (the PIN pad just stays). Default: throws (no Face ID).
    func unlockWithFaceId(_ id: String, switches: Bool) async throws
}

public extension ProfileService {
    func checkPin(_ id: String, pin: String) async throws { try await select(id, pin: pin) }
    func faceIdReady(_ id: String, switches: Bool) async -> Bool { false }
    func unlockWithFaceId(_ id: String, switches: Bool) async throws { throw DreamError("Enter the PIN.") }
}

/// Watch history + Continue Watching (DreamPlayback; Trakt sync plugs in behind it).
public protocol HistoryService: Sendable {
    func continueWatching() async -> [HistoryEntry]
    func entry(for key: String) async -> HistoryEntry?
    func record(_ entry: HistoryEntry) async
    func remove(key: String) async
    /// Where playback starts (PlayFlow.historyStart): seconds, 0 = from the beginning.
    func startPosition(for request: PlayRequest) async -> Double
    /// Dream Player's report. [exact]: it stopped, paused or left (written as is, never skipped as "barely moved").
    func record(_ entry: HistoryEntry, exact: Bool) async
    /// Everything watched, newest first, finished and removed ones included ("Because You Watched").
    func recent() async -> [HistoryEntry]
    /// A title's main play button: "Play", "Resume", "Play S1 E1", "Resume S2 E5" (PlayFlow.playLabel).
    func playLabel(for title: Title) async -> String
}

public extension HistoryService {
    func record(_ entry: HistoryEntry, exact: Bool) async { await record(entry) }
    func recent() async -> [HistoryEntry] { await continueWatching() }
    func playLabel(for title: Title) async -> String { title.kind == .tv ? "Play S1 E1" : "Play" }
}

public protocol MyListService: Sendable {
    func items() async -> [Title]
    func contains(_ key: String) async -> Bool
    func toggle(_ title: Title) async -> Bool
}

/// Where torrents come from: the user's scraper repositories (DreamScrapers).
public protocol SourceService: Sendable {
    func find(_ request: PlayRequest) async -> [StreamCandidate]
    /// The same search as it streams in: each element is the candidates one provider (or a few) just added, so
    /// Dream Core can start on the first ones (a Play doesn't wait for every provider). Finishes when the search does.
    func stream(_ request: PlayRequest) -> AsyncStream<[StreamCandidate]>
    /// At least one scraper repository is added and on (otherwise Dream Core says "Add a Scraper Repository").
    func isActive() async -> Bool
    /// The quick check (the availability pills): a few fast providers with short limits. `nil` when the scrapers
    /// couldn't answer (not started, every provider failed or timed out, busy): the caller shows nothing and asks
    /// again later. An empty array is a real answer: they looked and found nothing.
    func quick(_ request: PlayRequest) async -> [StreamCandidate]?
    /// A speculative search for [request] is no longer wanted (a real Play for another title needs the room): the
    /// running scrape, if any, is stopped and forgotten.
    func cancel(_ request: PlayRequest)
    /// `stream`, flagged `urgent` when a viewer is waiting on it (a real Play): it never queues behind look-ahead
    /// searches. A search that ends without the scrapers answering at all (busy, not running, every provider failed)
    /// finishes without yielding anything; one that answered with nothing yields one empty batch first.
    func stream(_ request: PlayRequest, urgent: Bool) -> AsyncStream<[StreamCandidate]>
    /// A search for [request] already running (a look-ahead's) is a real Play's from now on: it goes first.
    func promote(_ request: PlayRequest)
    /// What the quick check (the availability pills) already found for [request], without starting anything:
    /// nil when none ran lately. A Play counts these at once, so the card's pill and Play agree.
    func quickKnown(_ request: PlayRequest) -> [StreamCandidate]?
}

public extension SourceService {
    func stream(_ request: PlayRequest) -> AsyncStream<[StreamCandidate]> {
        AsyncStream { c in
            let t = Task { c.yield(await find(request)); c.finish() }
            c.onTermination = { _ in t.cancel() }
        }
    }
    func isActive() async -> Bool { true }
    func quick(_ request: PlayRequest) async -> [StreamCandidate]? { nil }
    func cancel(_ request: PlayRequest) {}
    func stream(_ request: PlayRequest, urgent: Bool) -> AsyncStream<[StreamCandidate]> { stream(request) }
    func promote(_ request: PlayRequest) {}
    func quickKnown(_ request: PlayRequest) -> [StreamCandidate]? { nil }
}

/// Look-ahead (Instant Play): told what the viewer is looking at, so the likely Play's stream is found before Play is
/// pressed (DreamPlayback's PlayAhead). Every call returns at once.
public protocol LookAheadService: Sendable {
    /// A title page opened (or came back): the movie / the Up Next episode is scraped and ranked now.
    func titleOpened(_ title: Title)
    /// A Continue Watching row is on screen: its first items' streams are looked up, one at a time.
    func continueWatching(_ requests: [PlayRequest])
}

public extension LookAheadService {
    func titleOpened(_ title: Title) {}
    func continueWatching(_ requests: [PlayRequest]) {}
}

struct EmptyLookAhead: LookAheadService {}

/// Dream Core Resolver: sources -> debrid -> a playable link (DreamPlayback).
public protocol PlaybackService: Sendable {
    /// The best ready link, reporting stages for the loading screen ("Now Playing").
    func resolve(_ request: PlayRequest) async throws -> StreamLink
    /// Every candidate, ranked, with debrid cache state (the source picker).
    func candidates(_ request: PlayRequest) async throws -> [StreamCandidate]
    func link(for candidate: StreamCandidate, request: PlayRequest) async throws -> StreamLink
    /// The next episode after this one, when there is one (autoplay / post-play).
    func nextEpisode(after request: PlayRequest) async -> PlayRequest?
    func skipMarkers(for request: PlayRequest) async -> [SkipMarker]

    /// A debrid account is linked (without one nothing can play).
    func isAvailable() async -> Bool
    /// Search All Sources: `resolve` with the viewer's source filters set aside for this one search.
    func resolveAll(_ request: PlayRequest) async throws -> StreamLink
    /// The source list without waiting for the "Ready" marks; [loose]: Search All Sources (filters off, what they'd
    /// hide marked `outsideFilters`). Fill the marks with `readyHashes`.
    func sources(_ request: PlayRequest, loose: Bool) async -> [StreamCandidate]
    /// Which of the list's top rows are ready to stream now (info hashes), or nil when that can't be told cheaply.
    func readyHashes(_ candidates: [StreamCandidate]) async -> Set<String>?
    /// The link handed out for [request] didn't play: it's dropped and its torrent skipped for this title for an hour.
    func forget(_ request: PlayRequest) async
    /// The link goes to the player now (its first frame is timed from here).
    func handing(_ link: StreamLink) async
    /// The player showed [link]'s first frame: counts toward which service starts this viewer's streams fastest.
    func firstFrame(_ request: PlayRequest, link: StreamLink) async
    /// Post-play, ahead of the credits: the next episode (its link is found now, so autoplay is instant) or,
    /// for a movie / the last episode, a few titles to offer.
    func postPlay(after request: PlayRequest) async -> PostPlayPlan
    /// Skip markers with where they came from; [chapters]: the stream's own chapters (mpv's chapter list).
    func skipInfo(for request: PlayRequest, durationMs: Int64, chapters: [SkipChapter]) async -> SkipInfo
    /// The viewer jumped past an intro by hand (learned skips).
    func learnSkip(_ request: PlayRequest, fromMs: Int64, toMs: Int64) async
    /// The viewer used a learned "Skip Intro".
    func confirmLearnedSkip(_ request: PlayRequest) async
    /// What the scrapers find for [request] (counts, best release), no debrid service asked: the availability checker.
    func survey(_ request: PlayRequest) async -> SourceSurvey?
}

public extension PlaybackService {
    func isAvailable() async -> Bool { false }
    func resolveAll(_ request: PlayRequest) async throws -> StreamLink { try await resolve(request) }
    func sources(_ request: PlayRequest, loose: Bool) async -> [StreamCandidate] { (try? await candidates(request)) ?? [] }
    func readyHashes(_ candidates: [StreamCandidate]) async -> Set<String>? { nil }
    func forget(_ request: PlayRequest) async {}
    func handing(_ link: StreamLink) async {}
    func firstFrame(_ request: PlayRequest, link: StreamLink) async {}
    func postPlay(after request: PlayRequest) async -> PostPlayPlan {
        PostPlayPlan(next: await nextEpisode(after: request).map { NextUp(request: $0) })
    }
    func skipInfo(for request: PlayRequest, durationMs: Int64, chapters: [SkipChapter]) async -> SkipInfo {
        SkipInfo(markers: await skipMarkers(for: request))
    }
    func learnSkip(_ request: PlayRequest, fromMs: Int64, toMs: Int64) async {}
    func confirmLearnedSkip(_ request: PlayRequest) async {}
    func survey(_ request: PlayRequest) async -> SourceSurvey? { nil }
}

// MARK: Registry

public final class Services: @unchecked Sendable {
    public static let shared = Services()
    private let lock = NSLock()
    private var _catalog: CatalogService = EmptyCatalog()
    private var _profiles: ProfileService = EmptyProfiles()
    private var _history: HistoryService = EmptyHistory()
    private var _myList: MyListService = EmptyMyList()
    private var _sources: SourceService = EmptySources()
    private var _playback: PlaybackService = EmptyPlayback()
    private var _lookAhead: LookAheadService = EmptyLookAhead()

    public var lookAhead: LookAheadService { get { lock.withLock { _lookAhead } } set { lock.withLock { _lookAhead = newValue } } }
    public var catalog: CatalogService { get { lock.withLock { _catalog } } set { lock.withLock { _catalog = newValue } } }
    public var profiles: ProfileService { get { lock.withLock { _profiles } } set { lock.withLock { _profiles = newValue } } }
    public var history: HistoryService { get { lock.withLock { _history } } set { lock.withLock { _history = newValue } } }
    public var myList: MyListService { get { lock.withLock { _myList } } set { lock.withLock { _myList = newValue } } }
    public var sources: SourceService { get { lock.withLock { _sources } } set { lock.withLock { _sources = newValue } } }
    public var playback: PlaybackService { get { lock.withLock { _playback } } set { lock.withLock { _playback = newValue } } }
}

// MARK: Stand-ins (no data; replaced at launch)

struct EmptyCatalog: CatalogService {
    func rows(for page: CatalogPage) async throws -> [CatalogRow] { [] }
    func more(row: CatalogRow, page: CatalogPage) async throws -> [CatalogItem] { [] }
    func spotlight(for page: CatalogPage) async throws -> [CatalogItem] { [] }
    func details(kind: MediaKind, id: Int) async throws -> TitleDetails { throw DreamError("This title isn't available right now.") }
    func episodes(showId: Int, season: Int) async throws -> [Episode] { [] }
    func search(_ query: String) async throws -> [Title] { [] }
    func scores(for details: TitleDetails) async -> Scores { Scores() }
    func availability(for title: Title) async -> Availability? { nil }
    func genres() async -> [Genre] { [] }
    func services() async -> [ServiceSpec] { [] }
    func person(id: Int) async throws -> [Title] { [] }
}

struct EmptyProfiles: ProfileService {
    func profiles() async -> [Profile] { [] }
    func current() async -> Profile? { nil }
    func select(_ id: String, pin: String?) async throws {}
    func save(_ profile: Profile, pin: String?) async throws {}
    func delete(_ id: String) async throws {}
}

struct EmptyHistory: HistoryService {
    func continueWatching() async -> [HistoryEntry] { [] }
    func entry(for key: String) async -> HistoryEntry? { nil }
    func record(_ entry: HistoryEntry) async {}
    func remove(key: String) async {}
    func startPosition(for request: PlayRequest) async -> Double { 0 }
}

struct EmptyMyList: MyListService {
    func items() async -> [Title] { [] }
    func contains(_ key: String) async -> Bool { false }
    func toggle(_ title: Title) async -> Bool { false }
}

struct EmptySources: SourceService {
    func find(_ request: PlayRequest) async -> [StreamCandidate] { [] }
    func isActive() async -> Bool { false }
}

struct EmptyPlayback: PlaybackService {
    func resolve(_ request: PlayRequest) async throws -> StreamLink { throw DreamError("Nothing to play yet. Add a debrid account and a scraper in Settings.") }
    func candidates(_ request: PlayRequest) async throws -> [StreamCandidate] { [] }
    func link(for candidate: StreamCandidate, request: PlayRequest) async throws -> StreamLink { throw DreamError("This source isn't ready. Try another.") }
    func nextEpisode(after request: PlayRequest) async -> PlayRequest? { nil }
    func skipMarkers(for request: PlayRequest) async -> [SkipMarker] { [] }
}
