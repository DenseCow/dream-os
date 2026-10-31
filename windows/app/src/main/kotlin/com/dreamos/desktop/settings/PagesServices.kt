package com.dreamos.desktop.settings

import androidx.compose.runtime.staticCompositionLocalOf
import com.dreamos.desktop.settings.kit.Mark

/**
 * What Settings, Setup, Profiles, About and Notices need from core (win-content) and playback (win-playback).
 * Stand-ins until their interfaces merge; [FakeServices] implements them all for previews and until the
 * main session wires the real ones (requests.md). Every call here is cheap or suspends off the UI thread.
 */

/** Key-value preferences. Core keeps per-profile keys under the current profile; the page doesn't care which. */
interface Prefs {
    fun bool(key: String, default: Boolean): Boolean
    fun setBool(key: String, value: Boolean)
    fun str(key: String, default: String): String
    fun setStr(key: String, value: String)
}

enum class Debrid(val title: String, val site: String, val mono: String, val signUp: String, val usesCode: Boolean) {
    AllDebrid("AllDebrid", "alldebrid.com", "AD", "https://alldebrid.com/offer/", true),
    RealDebrid("Real-Debrid", "real-debrid.com", "RD", "https://real-debrid.com/premium", true),
    Premiumize("Premiumize", "premiumize.me", "PM", "https://www.premiumize.me/premium", false),
    TorBox("TorBox", "torbox.app", "TB", "https://torbox.app/pricing", true),
}

/** One debrid account's state, in the words the pages show ("Connected · Premium Until Jan 10, 2027"). */
data class DebridState(
    val service: Debrid,
    val linked: Boolean,
    val user: String? = null,
    val plan: String? = null,
    val word: String = "Not Connected",
    val mark: Mark? = null,
    /** "Sign In Again", "Expired", "Approve This Network": the row the viewer should look at. */
    val needsAttention: Boolean = false,
)

/** A sign-in code: [expiresAt] epoch ms, 0 when the service doesn't say. */
data class LinkCode(val code: String, val url: String, val shortUrl: String, val expiresAt: Long = 0)

sealed interface LinkResult {
    data object Waiting : LinkResult
    data class Connected(val ready: Boolean) : LinkResult
    data class Failed(val message: String) : LinkResult
}

interface DebridAccounts {
    fun states(): List<DebridState>
    /** The linked services in the viewer's order (Use First), the order Dream Core asks them in. */
    fun linkedInOrder(): List<Debrid> = states().filter { it.linked }.map { it.service }
    suspend fun check(s: Debrid): DebridState
    suspend fun disconnect(s: Debrid)
    fun useFirst(s: Debrid)
    suspend fun startLink(s: Debrid): LinkCode
    suspend fun pollLink(s: Debrid): LinkResult
    suspend fun linkWithKey(s: Debrid, key: String): LinkResult
    /** True when [s] signs in with a code in this build; false: it takes the account's key (Premiumize without a client id). */
    fun usesCode(s: Debrid): Boolean = s.usesCode
}

data class TraktState(val connected: Boolean, val user: String? = null, val synced: String = "Not Synced Yet", val ended: Boolean = false)

interface TraktAccount {
    fun state(): TraktState
    /** Dream OS ships no Trakt keys: the viewer's own Trakt app's Client ID is needed first. */
    val needsClientId: Boolean get() = false
    /** Saves the viewer's own Trakt app's Client ID ("" removes it and signs every profile out of Trakt). */
    fun setClientId(id: String) {}
    /** The saved Client ID's last characters ("Ending in 3f9c"), null when none is saved. */
    val clientIdHint: String? get() = null
    /** What Trakt's New Application form asks for as the Redirect URI. */
    val redirectUri: String get() = "http://localhost"
    /** Where a Trakt account makes its own apps. */
    val appsUrl: String get() = "https://trakt.tv/oauth/applications"
    suspend fun startSignIn(): LinkCode
    suspend fun pollSignIn(): LinkResult
    suspend fun syncNow(): Boolean
    suspend fun disconnect()
}

data class ScraperRepo(val name: String, val address: String, val version: String?, val id: String = address)
data class ScraperProvider(val id: String, val name: String, val kind: String, val on: Boolean, val recommended: Boolean)

interface Scrapers {
    fun repos(): List<ScraperRepo>
    fun providers(): List<ScraperProvider>
    /** Adds the repository at [address]; [stage] gets "Starting…", "Downloading…". */
    suspend fun add(address: String, stage: (String) -> Unit): Result<String>
    suspend fun update(repo: ScraperRepo): Result<String>
    suspend fun remove(repo: ScraperRepo)
    fun setProvider(id: String, on: Boolean)
    fun useRecommended()
    fun setAll(on: Boolean)
    val recommendedOn: Boolean
}

interface Keys {
    val tmdbAdded: Boolean
    val omdbAdded: Boolean
    /** Checks [key] with one request and saves it when it works. */
    suspend fun saveTmdb(key: String): Boolean
    suspend fun saveOmdb(key: String): Boolean
    fun removeOmdb()
}

data class Profile(
    val id: String,
    val name: String,
    val avatar: String,
    val color: Long,
    val locked: Boolean = false,
    val kidsLevel: Int = 3,
    val pinToLeave: Boolean = false,
    val hello: Boolean = false,
)

/** The four rating levels (KidsProfiles): name, movie limit, caption. Index 3 is Everyone. */
object KidsLevels {
    val all = listOf(
        Triple("Little Kids", "G", "Movies up to G  ·  shows up to TV-G"),
        Triple("Kids", "PG", "Movies up to PG  ·  shows up to TV-PG"),
        Triple("Teens", "PG-13", "Movies up to PG-13  ·  shows up to TV-14"),
        Triple("Everyone", "", "No limit"),
    )
}

sealed interface PinCheck {
    data object Ok : PinCheck
    data object Wrong : PinCheck
    data class Wait(val seconds: Int) : PinCheck
}

interface ProfilesService {
    fun all(): List<Profile>
    fun currentId(): String
    /** Switches to [id]; a locked profile (or leaving a Kids one that asks) needs [pin]. */
    suspend fun select(id: String, pin: String? = null): PinCheck
    /**
     * Switches to [id] after Windows Hello confirmed the viewer (no PIN typed): core's Profiles.selectTrusted, which
     * takes Hello for the profile's own PIN when its Hello is on. Wrong when core can't (then the pad asks for the PIN).
     */
    suspend fun selectTrusted(id: String): PinCheck = select(id, null)
    /** Checks [id]'s PIN without switching (editing a locked profile). */
    suspend fun checkPin(id: String, pin: String): PinCheck
    /** The profile whose PIN switching to [id] needs (its own, or a grown-up's to leave Kids), or null. */
    fun pinNeededFor(id: String): String? = all().firstOrNull { it.id == id && it.locked }?.id
    /** Saves [p] (a blank id adds it); [pin] = new PIN to set, "" = remove the lock, null = keep. Returns the saved profile. */
    suspend fun save(p: Profile, pin: String?): Profile
    suspend fun remove(id: String)
    /** Windows Hello is set up on this PC (a face, fingerprint or PIN is enrolled). */
    val helloReady: Boolean
    /** Shows Windows Hello with [reason]; true when the viewer confirmed. */
    suspend fun helloVerify(reason: String): Boolean
}

sealed interface UpdateState {
    data object Never : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedWords: String) : UpdateState
    data class Newer(val version: String, val pageUrl: String, val checkedWords: String) : UpdateState
    data class Failed(val checkedWords: String) : UpdateState
}

interface Updates {
    val version: String
    /** "PUBLIC" for a release build, "DEV" otherwise. */
    val channel: String
    fun state(): UpdateState
    suspend fun check(): UpdateState
    /**
     * Get the Update: downloads the new setup (DreamOS-Windows-Setup.exe), checks it, starts it silently (apart from
     * the app) and quits Dream OS, which holds the files the setup replaces. [progress] gets
     * 0..1 while downloading. Returns a plain sentence when it can't; false-y [hasInstaller] means open the page.
     */
    suspend fun install(progress: (Float) -> Unit): String? = "This build can't install updates. Open the release page instead."
    /** The newest release carries a Windows installer the app can download. */
    val hasInstaller: Boolean get() = false
    /** Asks GitHub only when the last answer is over 6 hours old (About opening, the daily check). */
    suspend fun checkIfDue(): UpdateState = state()
    /** Epoch ms of the last check, 0 = never. */
    val checkedAt: Long get() = 0
    /** The newest release's notes (clean text), its size ("82.4 MB") and release time; empty / 0 when unknown. */
    val newestNotes: String get() = ""
    val newestSize: String get() = ""
    val newestReleased: Long get() = 0
    /** The notes of [version]'s release; "" when it published none, null when GitHub couldn't be reached. */
    suspend fun notesFor(version: String): String? = null
    /** Automatic Updates: downloads the newest setup ahead, so Get the Update installs at once. True when it's ready. */
    suspend fun prefetch(): Boolean = false
}

enum class NoticeType { Expiry, Limit, Episodes, Update, Summary, Info }

data class Notice(
    val key: String,
    val type: NoticeType,
    val title: String,
    val body: String,
    val agoWords: String,
    val read: Boolean,
    val actionLabel: String? = null,
    val warning: Boolean = false,
    /** Where the action goes: "renew:pm", "accounts", "home", "title:<kind>:<id>:<name>", "update". */
    val target: String? = null,
    /** Critical (a plan has ended): the red halo and ⚠️, and 😊 at the end (the Fire Stick's banner). */
    val critical: Boolean = false,
)

interface Notices {
    fun list(): List<Notice>
    fun markAllRead()
    fun clearAll()
    fun remove(key: String)
    fun open(key: String)
}

/** The setup check's answer: [found] sources, [ready] ready to play; [message] the plain line when it didn't pass. */
data class CheckOutcome(val ok: Boolean, val found: Int = 0, val ready: Int = 0, val message: String = "")

/** Setup's "Making Sure It All Works": one real search with the viewer's services, then a short speed test. */
interface SetupCheck {
    /** Runs the search; [stage] gets "Starting Up…", "Searching…", "Checking Your Account…". */
    suspend fun run(stage: (String) -> Unit): CheckOutcome
    /** Measures the connection in Mbps (about five seconds), null when it couldn't. [live] gets the reading as it goes. */
    suspend fun speed(live: (Int) -> Unit): Int? = com.dreamos.desktop.setup.SpeedTest.run(live)
}

/** Everything the pages call, in one place. */
class PagesServices(
    val prefs: Prefs,
    val debrid: DebridAccounts,
    val trakt: TraktAccount,
    val scrapers: Scrapers,
    val keys: Keys,
    val profiles: ProfilesService,
    val updates: Updates,
    val notices: Notices,
    /** Opens or leaves Dream OS when the viewer signs in to Windows (PC-only setting). */
    val startWithWindows: (Boolean?) -> Boolean,
    val check: SetupCheck = object : SetupCheck { override suspend fun run(stage: (String) -> Unit) = CheckOutcome(false, message = "Something went wrong. Try again.") },
    /** The viewer's subtitle languages changed (first and second choice, names): Dream Core ranks copies by them. */
    val subtitleLanguages: (List<String>) -> Unit = {},
    /** Marks setup finished (core's Profiles.markSetUp) and whether it ever was. */
    val setupDone: (Boolean?) -> Boolean = { false },
    /** Settings › Your PC › Display Mode, per PC: sets the mode by its label (null: just reads), returns the current one. */
    val displayMode: (String?) -> String = MemoryDisplayMode(),
)

/** Display Mode for previews and fakes: kept in memory. */
class MemoryDisplayMode : (String?) -> String {
    private val now = androidx.compose.runtime.mutableStateOf(com.dreamos.desktop.shell.DisplayMode.DEFAULT.label)
    override fun invoke(set: String?): String { if (set != null) now.value = set; return now.value }
}

/** The app provides `CoreServices.create(playback.accounts)` at launch; previews provide [FakeServices]. */
val LocalPagesServices = staticCompositionLocalOf<PagesServices> { CoreServices.create(accounts = null) }
