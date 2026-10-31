package com.dreamos.desktop.core.service

import com.dreamos.desktop.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

// The seams between modules. Screens (app) and the player talk only to these; core implements them and the app
// registers the real ones at launch ([Services]). Until then the empty stand-ins keep everything compiling and running.
// Extend an interface by adding a member WITH a default body, so other modules keep building. Never rename or remove one.
// Every suspend function is safe to call from the UI thread: implementations switch to IO themselves.

/** Rows, heroes and Browse (fire-home CinemaPages / RowMix / HomePicks / GenreShelves / SeasonHub / Discover). */
interface Catalog {
    /**
     * The page's rows in order as empty placeholders (`filled = false`): id, name, style, isSpotlight. Fill each with
     * [fill] as it nears the screen. Calling it starts a fresh page: rows claim their titles anew, each title shows in one row only.
     */
    suspend fun rowSlots(page: CatalogPage): List<CatalogRow>

    /** One placeholder's cards; null = the row is left out (too few titles). Already filtered for the profile (anime, Kids). */
    suspend fun fill(row: CatalogRow, page: CatalogPage): CatalogRow?

    /** Every row of a page, filled, in order. */
    suspend fun rows(page: CatalogPage): List<CatalogRow> = rowSlots(page).mapNotNull { fill(it, page) }

    /** The next cards of a row with `hasMore`. */
    suspend fun more(row: CatalogRow, page: CatalogPage): List<CatalogItem> = emptyList()

    /** The page's hero titles (Home: the featured titles for the time of day; Movies / TV: trending; a service: "On Netflix"). */
    suspend fun spotlight(page: CatalogPage): List<CatalogItem>

    /** Genre chips under the Movies / TV Shows hero (without "All"); picking one shows `rows(GenrePage(chip))`. */
    suspend fun chips(page: CatalogPage): List<Genre> = emptyList()

    /** Browse: genre cards. */
    suspend fun genres(): List<Genre> = emptyList()

    /** Browse: Popular Categories (those in season) and Through the Decades, opened as `GenrePage`. */
    suspend fun categories(): List<Genre> = emptyList()
    suspend fun decades(): List<Genre> = emptyList()

    /** Browse: service pages (Netflix, Hulu, Crunchyroll...). */
    suspend fun services(): List<ServiceSpec> = emptyList()

    /** Every seasonal hub on today, the one Home shows first. */
    suspend fun hubs(): List<HubTile> = emptyList()

    suspend fun hub(id: String): HubTile? = hubs().firstOrNull { it.id == id }

    /** Browse's Discover wall, one page (1-based). */
    suspend fun discover(filter: DiscoverFilter, page: Int): DiscoverPage = DiscoverPage(emptyList(), page, 0, 0)

    /** The wall's genre choices for a kind ("Any Genre" is id 0, not listed). */
    suspend fun discoverCategories(kind: MediaKind): List<DiscoverCategory> = emptyList()

    /**
     * Emits when shown rows may be stale: profile switched, history / My List / Trakt changed, a content setting changed
     * (anime rule, Kids level), the day turned. Screens rebuild their rows (keeping focus on the same title).
     */
    val changes: Flow<Unit> get() = emptyFlow()

    object Empty : Catalog {
        override suspend fun rowSlots(page: CatalogPage) = emptyList<CatalogRow>()
        override suspend fun fill(row: CatalogRow, page: CatalogPage): CatalogRow? = null
        override suspend fun spotlight(page: CatalogPage) = emptyList<CatalogItem>()
    }
}

/** Search (fire-home CinemaSearch / Tmdb.searchSpoken / searchPerson / GenreSearch). */
interface Search {
    /** Spoken-title search ("yellow jackets" finds Yellowjackets), a person's titles, a genre's rows. */
    suspend fun searchAll(query: String): SearchResults

    /** Genre names that begin with what's typed. */
    suspend fun suggestions(query: String): List<String> = emptyList()

    /** "Trending Now" while the field is empty (posters, released titles only, anime rule applied). */
    suspend fun trending(): List<Title> = emptyList()

    object Empty : Search {
        override suspend fun searchAll(query: String) = SearchResults()
    }
}

/** A title page and its parts (fire-home Tmdb.details, TitleInfo, Ratings, person pages). */
interface Titles {
    /** Throws [DreamError] with a plain sentence when TMDB can't be reached or the title is gone. */
    suspend fun details(kind: MediaKind, id: Int): TitleDetails

    /** Cached details without the network (a page's first frame), null when not known yet. */
    fun cachedDetails(kind: MediaKind, id: Int): TitleDetails? = null

    /** A season's episodes, still-to-come ones marked `upcoming` (or `outEarly` when the index already has them). */
    suspend fun episodes(showId: Int, season: Int): List<Episode>

    /**
     * [episodes] for the title page: the first episode still ahead gets one quick, bounded look in the index first, so
     * one already up (out early) plays now. Slower than [episodes] when the season has episodes to come.
     */
    suspend fun episodesChecked(showId: Int, season: Int): List<Episode> = episodes(showId, season)

    /** Rotten Tomatoes and the audience score (OMDb with the viewer's key, RT fallback, TMDB after 150 votes). */
    suspend fun scores(details: TitleDetails): Scores = Scores()

    /** One person's facts and best-known titles (not [excludingKey], the title they're seen in). */
    suspend fun person(id: Int, excludingKey: String? = null): PersonDetails? = null

    /** The title's English logo art path (transparent PNG), null when none. */
    suspend fun titleLogo(kind: MediaKind, id: Int): String? = null

    /** The current profile may open [title] (Kids limit); fetches its rating when unknown. */
    suspend fun allowed(title: Title): Boolean = true

    /** A show's latest aired episode and its next air day. */
    suspend fun showAirState(showId: Int): ShowAirState? = null

    /** Who's in what plays (X-Ray): an episode's cast and guest stars, a movie's cast; top billing first, 24 at most. */
    suspend fun cast(request: PlayRequest): List<CastMember> = emptyList()

    object Empty : Titles {
        override suspend fun details(kind: MediaKind, id: Int): TitleDetails = throw DreamError("This title isn't available right now.")
        override suspend fun episodes(showId: Int, season: Int) = emptyList<Episode>()
    }
}

/** Profiles, Kids limits, PINs (fire-home Profiles / KidsProfiles). PINs are stored as salted hashes. */
interface Profiles {
    val all: StateFlow<List<Profile>>
    val current: StateFlow<Profile>

    /** False until setup has finished once. */
    fun isSetUp(): Boolean = true
    fun markSetUp() {}

    /** Switches to [id]. A locked profile needs its PIN; a Kids profile with exitPin needs a grown-up's PIN to leave. */
    suspend fun select(id: String, pin: String? = null): PinResult

    /**
     * Switches to [id] after a Windows Hello pass, in place of [id]'s own PIN (only when that profile turned Windows
     * Hello on). Never stands in for a grown-up's PIN to leave a Kids profile: then [PinResult.Wrong] and no switch; ask
     * for the PIN [pinNeeded] names. Call it only with a real Hello pass.
     */
    suspend fun selectTrusted(id: String): PinResult = PinResult.Wrong

    /** Checks [id]'s PIN without switching (editing a locked profile). */
    suspend fun checkPin(id: String, pin: String): PinResult

    /** The PIN [select] needs before switching to [id], or null when none: [id]'s own, or a grown-up's to leave a Kids profile. */
    fun pinNeeded(id: String): String? = null

    /** Adds a profile (new id) and returns it. */
    suspend fun add(name: String): Profile

    /** Saves name, emoji, color, Kids settings. [pin]: null keeps the PIN, "" removes it, 4 digits sets it. */
    suspend fun save(profile: Profile, pin: String? = null)

    /** Removes a profile and its data. The first profile can't be removed. */
    suspend fun delete(id: String)

    object Empty : Profiles {
        private val me = Profile(Profile.FIRST, "Me")
        override val all = MutableStateFlow(listOf(me))
        override val current = MutableStateFlow(me)
        override suspend fun select(id: String, pin: String?) = PinResult.Ok
        override suspend fun checkPin(id: String, pin: String) = PinResult.Ok
        override suspend fun add(name: String) = me
        override suspend fun save(profile: Profile, pin: String?) {}
        override suspend fun delete(id: String) {}
    }
}

/** Watch history, Continue Watching, Up Next and the Watched pill (fire-home WatchHistory / PlayFlow / Watched). */
interface History {
    /** Bumps on every change of the current profile's history (rebuild Continue Watching, redo a title page's Resume). */
    val version: StateFlow<Long>

    /** Continue Watching, newest first: real watching only (90 s of actual playback), finished titles off. */
    suspend fun continueWatching(): List<HistoryEntry>

    suspend fun entry(key: String): HistoryEntry?

    /** Everything watched, newest first, finished and removed ones included. */
    suspend fun recent(): List<HistoryEntry>

    /**
     * Dream Player's report. [realWatchedMs]: actual playback time this session (seeks and skips add nothing); a title
     * not on the row yet joins only past 90 s. [exact]: it stopped, paused or left (written as is).
     */
    suspend fun report(request: PlayRequest, positionMs: Long, durationMs: Long, realWatchedMs: Long, exact: Boolean)

    /** Off Continue Watching (the viewer removed it). An unfinished removed title seeds no taste. */
    suspend fun remove(key: String)

    /** Where playback starts, ms (0 = from the beginning). Always the history's call, never a spot a page captured. */
    suspend fun startPositionMs(request: PlayRequest): Long

    /** What Play opens for a show: its Continue Watching episode (Resume, or Up Next), else a caught-up show's newer episode once out, else S1 E1. Null for movies. */
    suspend fun nextFor(show: Title): PlayRequest? = null

    /** A title's main button: "Play", "Resume", "Play S1 E1", "Resume S2 E5". */
    suspend fun playLabel(title: Title): String = if (title.isShow) "Play S1 E1" else "Play"

    /** Watched pill: a finished movie; a show when every aired episode is finished; an episode the viewer finished. */
    suspend fun isWatched(title: Title): Boolean = false
    suspend fun isEpisodeWatched(showId: Int, season: Int, episode: Int): Boolean = false

    object Empty : History {
        override val version = MutableStateFlow(0L)
        override suspend fun continueWatching() = emptyList<HistoryEntry>()
        override suspend fun entry(key: String): HistoryEntry? = null
        override suspend fun recent() = emptyList<HistoryEntry>()
        override suspend fun report(request: PlayRequest, positionMs: Long, durationMs: Long, realWatchedMs: Long, exact: Boolean) {}
        override suspend fun remove(key: String) {}
        override suspend fun startPositionMs(request: PlayRequest) = 0L
    }
}

/** My List, per profile; the Trakt watchlist follows. */
interface MyList {
    val items: StateFlow<List<Title>>
    fun contains(key: String): Boolean = items.value.any { it.key == key }

    /** Adds or removes; returns true when it's on the list afterwards. */
    suspend fun toggle(title: Title): Boolean

    object Empty : MyList {
        override val items = MutableStateFlow(emptyList<Title>())
        override suspend fun toggle(title: Title) = false
    }
}

/** New episodes of followed shows (Home's New Episode banner, New Episodes for You, its notice). */
interface NewEpisodes {
    suspend fun snapshot(): NewEpisodesSnapshot
    /** A New Episodes for You card was seen: its "NEW" badge goes. */
    suspend fun markSeen(item: CatalogItem) {}

    /**
     * The episode Home's New Episode banner offers now, or null (fire-home NewEpisodeSpot: out in the last 3 days, not
     * played or dismissed, retired 48 h after it first showed, and only when the viewer is caught up with the show).
     * Also posts the "New Episode" notice once per episode found. Ask again every 30 minutes while Home is open.
     */
    suspend fun bannerPick(): NewEpisodeInfo? = null

    /** The banner is showing [episode] (its 48 h starts the first time). */
    suspend fun bannerShown(episode: NewEpisodeInfo) {}

    /** Played or dismissed from the banner: never offered again on this profile. */
    suspend fun bannerDone(episode: NewEpisodeInfo) {}

    /** Shows whose cards wear "NEW EPISODE" (the banner would offer one of their episodes). */
    fun badgedShows(): Set<Int> = emptySet()

    object Empty : NewEpisodes {
        override suspend fun snapshot() = NewEpisodesSnapshot()
    }
}

/** The viewer's own API keys (setup's Connect chapter). Dream OS ships none. */
interface Keys {
    val tmdb: StateFlow<String>
    val omdb: StateFlow<String>
    /** Checks a TMDB key with one request; false when TMDB says it's wrong. Throws [DreamError] when TMDB can't be reached. */
    suspend fun checkTmdb(key: String): Boolean
    suspend fun checkOmdb(key: String): Boolean
    fun setTmdb(key: String)
    fun setOmdb(key: String)

    object Empty : Keys {
        override val tmdb = MutableStateFlow("")
        override val omdb = MutableStateFlow("")
        override suspend fun checkTmdb(key: String) = false
        override suspend fun checkOmdb(key: String) = false
        override fun setTmdb(key: String) {}
        override fun setOmdb(key: String) {}
    }
}

/** Trakt (auth.trakt.tv device flow with the viewer's own Client ID; history, watchlist and paused points sync). */
interface TraktService {
    val status: StateFlow<TraktStatus>
    fun setClientId(id: String)
    /** Starts a device sign-in. Throws [DreamError] ("Couldn't reach Trakt. Try again.", or the Client ID is wrong). */
    suspend fun startSignIn(): TraktDeviceCode
    /** Waits for approval (polls at the code's interval); true when signed in, false when the code expired or was denied. */
    suspend fun awaitApproval(code: TraktDeviceCode): Boolean
    suspend fun signOut()
    suspend fun syncNow()

    object Empty : TraktService {
        override val status = MutableStateFlow(TraktStatus())
        override fun setClientId(id: String) {}
        override suspend fun startSignIn(): TraktDeviceCode = throw DreamError("Couldn't reach Trakt. Try again.")
        override suspend fun awaitApproval(code: TraktDeviceCode) = false
        override suspend fun signOut() {}
        override suspend fun syncNow() {}
    }
}

/** The notification center (fire-home DreamNotices). A new unread notice shows as a banner while no player is in front. */
interface NoticeCenter {
    /** Newest first, the last 50. */
    val notices: StateFlow<List<Notice>>
    /** Posts once per key, ever. */
    fun post(notice: Notice)
    /** [post] with control: [banner] slides one in, [store] keeps it in the list (New Episode banners aren't kept). */
    fun post(notice: Notice, banner: Boolean, store: Boolean) = post(notice)
    /** Notices to slide in as banners now (the shell shows them while no player or full-screen page is up). */
    val banners: Flow<Notice> get() = emptyFlow()
    fun markRead(key: String)
    fun markAllRead()
    fun dismiss(key: String)

    object Empty : NoticeCenter {
        override val notices = MutableStateFlow(emptyList<Notice>())
        override fun post(notice: Notice) {}
        override fun markRead(key: String) {}
        override fun markAllRead() {}
        override fun dismiss(key: String) {}
    }
}

/** The GitHub update check (DenseCow/dream-os, latest release, asset DreamOS-Windows-Setup.exe, every 6 h, ETag). */
interface Updates {
    val status: StateFlow<UpdateStatus>
    /** Checks now ([force]) or when the last check is over 6 h old. */
    suspend fun check(force: Boolean = false): UpdateStatus

    /**
     * Install Now: downloads the latest release's DreamOS-Windows-Setup.exe, checks its SHA-256 and starts it
     * silently as its own process. True: the setup runs, quit the app now. [progress] gets
     * 0..1 while it downloads. Throws [DreamError] with a plain sentence when it can't (no checksum: never installed).
     */
    suspend fun install(progress: (Float) -> Unit = {}): Boolean = false

    object Empty : Updates {
        override val status = MutableStateFlow(UpdateStatus(current = ""))
        override suspend fun check(force: Boolean) = status.value
    }
}

/** Where the app finds each service. The app sets the real ones at launch (`ContentServices.install`). */
object Services {
    @Volatile var catalog: Catalog = Catalog.Empty
    @Volatile var search: Search = Search.Empty
    @Volatile var titles: Titles = Titles.Empty
    @Volatile var profiles: Profiles = Profiles.Empty
    @Volatile var history: History = History.Empty
    @Volatile var myList: MyList = MyList.Empty
    @Volatile var newEpisodes: NewEpisodes = NewEpisodes.Empty
    @Volatile var keys: Keys = Keys.Empty
    @Volatile var trakt: TraktService = TraktService.Empty
    @Volatile var notices: NoticeCenter = NoticeCenter.Empty
    @Volatile var updates: Updates = Updates.Empty
}
