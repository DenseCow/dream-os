package com.dreamos.desktop.core.trakt

import com.dreamos.desktop.core.content.MyListStore
import com.dreamos.desktop.core.content.WatchHistory
import com.dreamos.desktop.core.model.DreamError
import com.dreamos.desktop.core.model.PlayRequest
import com.dreamos.desktop.core.model.TraktDeviceCode
import com.dreamos.desktop.core.model.TraktStatus
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What the player tells Trakt ([Trakt.scrobble]). */
enum class ScrobbleAction { START, PAUSE, STOP }

/**
 * Trakt sync, per Dream OS profile (fire-home Trakt.kt, with the iPhone's fixes).
 *
 *  - Sign-in: Trakt's device code on auth.trakt.tv with the viewer's own Trakt app Client ID (Dream OS ships none, and
 *    there is no Client Secret: the device sign-in works without one). [startSignIn] / [awaitApproval]. Tokens are
 *    renewed an hour before they expire (or on a 401); a refused renewal ends the sign-in ("Sign In Again").
 *  - Scrobble: the player's start / pause / stop ([scrobble]) go out on Trakt's own thread, never in playback's way.
 *    A pause or stop that can't be sent is queued (a finished one as a history entry with its real time) and sent with
 *    the next sync; a queued paused point the history has moved past is dropped, never sent late.
 *  - Sync ([TraktSync]): /sync/last_activities first, then only what changed: recent history and paused points into
 *    Continue Watching ([WatchHistory.mergeRemote]) and the Watched pill, the watchlist both ways with My List. On
 *    sign-in, every 15 minutes while signed in (sooner after playback), on Sync Now.
 *  - [followedShowTmdbIds]: watchlist shows, paused shows and shows watched lately, for New Episodes.
 *
 * Launch: [install] once. Profile switch: [profileChanged]. Everything here returns at once or blocks only on IO threads.
 */
object Trakt {
    /** A sync runs at most this often by itself; something played or changed skips the wait. */
    const val SYNC_EVERY_MS = 15 * 60_000L
    /** Renew the access token when it has less than this left. */
    internal const val REFRESH_AHEAD_MS = 60 * 60_000L
    /** Trakt counts a stop at this percentage or more as watched. */
    internal const val WATCHED_PCT = 80.0
    /** History older than this doesn't come into Continue Watching. */
    internal const val HISTORY_DAYS = 180L
    /** Where a Trakt account makes its own apps (the Trakt page's Open Trakt Apps). */
    const val APPS_URL = "https://trakt.tv/oauth/applications"
    /** The redirect URI Trakt's app form asks for: only renewal sends it, and development apps take only localhost. */
    const val REDIRECT_URI = TraktHttp.REDIRECT
    /** Trakt's Client IDs are 64 characters; anything under 40 is a part of one (Trakt's page wraps it onto two lines). */
    const val CLIENT_ID_MIN = 40

    internal const val MSG_OFFLINE = "Couldn't reach Trakt. Try again."
    internal const val MSG_BAD_ID = "That Client ID didn't work. Copy the whole Client ID from your Trakt app and try again."
    internal const val MSG_NO_ID = "Enter your Trakt app's Client ID first."
    internal const val MSG_EXPIRED_CODE = "The code expired. Get a new one to try again."
    internal const val MSG_ENDED = "Your Trakt sign-in has ended. Sign in again."

    /** Scrobbles, the queue and small pushes: one thread, in order. */
    internal val io = serialExecutor("trakt")
    /** Syncs (TMDB lookups can take a while): their own thread, so a scrobble never waits behind one. */
    internal val syncer = serialExecutor("trakt-sync")
    /** The scrobble state, its 3 s settle and the sync timer. Never does network work itself. */
    internal val timer = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "trakt-timer").apply { isDaemon = true } }
    internal val syncing = AtomicBoolean(false)
    @Volatile internal var lastSyncFailed = false

    /** Tests: no background syncs (after sign-in, after playback). */
    @Volatile internal var background = true
    /** Tests: one "second" of the sign-in poll interval. */
    @Volatile internal var pollUnitMs = 1000L

    private val _status = MutableStateFlow(TraktStatus())
    /** The current profile's Trakt link (Settings, the Trakt page). */
    val status: StateFlow<TraktStatus> get() = _status

    /** The last sign-in's failure as a plain sentence, null when none. Cleared by a new sign-in or a profile switch. */
    @Volatile var lastSignInProblem: String? = null
        private set

    /** Device codes waiting for approval -> the profile that asked for them (the sign-in belongs to that profile). */
    private val codeProfiles = ConcurrentHashMap<String, String>()
    @Volatile private var installed = false
    @Volatile private var seenProfile: String? = null

    internal val pid: String get() = DreamStore.currentProfile

    // =====================================================================================
    // Launch
    // =====================================================================================

    /**
     * Once at launch: Trakt follows My List and Continue Watching removals, and a timer syncs while signed in (every
     * [SYNC_EVERY_MS], sooner after playback). Hooks already set by someone else keep running.
     */
    fun install() {
        synchronized(this) {
            if (installed) return
            installed = true
        }
        val prevList = MyListStore.onChanged
        MyListStore.onChanged = { t, on ->
            try { prevList?.invoke(t, on) } catch (e: Exception) {}
            TraktSync.onMyListChanged(t, on)
        }
        val prevRemoved = WatchHistory.onRemoved
        WatchHistory.onRemoved = { key ->
            try { prevRemoved?.invoke(key) } catch (e: Exception) {}
            TraktSync.onContinueWatchingRemoved(key)
        }
        timer.scheduleWithFixedDelay({ tick() }, 5, 60, TimeUnit.SECONDS)
        changed()
        Log.i("Trakt", "installed")
    }

    /** The profile switched: its own Trakt link shows, and it syncs if due. */
    fun profileChanged() {
        seenProfile = pid
        lastSignInProblem = null
        lastSyncFailed = false
        changed()
        TraktSync.syncIfDue("profile")
    }

    /** The app came to the front: a sync if one is due (fire-home's Home opened). Returns at once. */
    fun appInFront() = TraktSync.syncIfDue("front")

    private fun tick() {
        try {
            if (seenProfile != pid) { profileChanged(); return }
            TraktSync.syncIfDue("timer")
        } catch (e: Exception) {
            Log.w("Trakt", "timer: ${e.javaClass.simpleName}")
        }
    }

    /** Repaints [status] for the current profile. Any thread. */
    internal fun changed() {
        val p = pid
        val signed = signedIn(p)
        val ended = !signed && hasClientId && TraktVault.expired(p)
        _status.value = TraktStatus(
            signedIn = signed,
            user = if (signed) TraktVault.user(p)?.username.orEmpty() else "",
            lastSync = if (signed) TraktVault.state(p).at else 0,
            problem = when {
                signed && lastSyncFailed -> MSG_OFFLINE
                ended -> MSG_ENDED
                !signed -> lastSignInProblem
                else -> null
            },
            hasClientId = hasClientId,
        )
    }

    // =====================================================================================
    // The viewer's Trakt app (Client ID)
    // =====================================================================================

    val clientId: String get() = TraktVault.clientId()
    val hasClientId: Boolean get() = clientId.isNotEmpty()

    /**
     * Saves the viewer's own Trakt app Client ID ("" removes it). A different app signs every profile out (their
     * sign-ins belonged to the old app; revoked there, best effort).
     */
    fun setClientId(id: String) {
        val clean = id.filterNot { it.isWhitespace() }
        val old = clientId
        if (clean == old) return
        if (old.isNotEmpty()) signOutEveryone(old)
        TraktVault.setClientId(clean)
        lastSignInProblem = null
        Log.i("Trakt", if (clean.isEmpty()) "app Client ID removed" else "app Client ID saved")
        changed()
    }

    private fun signOutEveryone(oldId: String) {
        val ids = (TraktVault.profiles() + pid).toSet()
        for (p in ids) TraktVault.wipe(p)?.let { revoke(oldId, it) }
    }

    // =====================================================================================
    // Account state
    // =====================================================================================

    /** Signed in on [p] (the current profile by default). No network. */
    fun signedIn(p: String = pid): Boolean = hasClientId && TraktVault.tokens(p) != null

    /** The sign-in ended (Trakt refused to renew it): the page offers Sign In Again. */
    fun expired(): Boolean = hasClientId && !signedIn() && TraktVault.expired(pid)

    fun user(): TraktUser? = if (signedIn()) TraktVault.user(pid) else null

    fun isSyncing() = syncing.get()

    fun lastSyncedAt(): Long = TraktVault.state(pid).at

    /** "Last Synced 2 Min Ago" / "Not Synced Yet". */
    fun lastSyncedText(now: Long = System.currentTimeMillis()): String {
        val at = lastSyncedAt()
        if (at <= 0) return "Not Synced Yet"
        val m = ((now - at) / 60_000).coerceAtLeast(0)
        return when {
            m < 1 -> "Last Synced Just Now"
            m < 60 -> "Last Synced $m Min Ago"
            m < 48 * 60 -> (m / 60).let { "Last Synced $it Hour${if (it == 1L) "" else "s"} Ago" }
            else -> "Last Synced ${m / 1440} Days Ago"
        }
    }

    /** The Settings row's value: "Connected as @user", "Sign In Again" or "Not Connected". */
    fun statusText(): String = when {
        signedIn() -> user()?.let { "Connected as @${it.username}" } ?: "Connected"
        expired() -> "Sign In Again"
        else -> "Not Connected"
    }

    /** The Settings row's caption. */
    fun captionText(): String = when {
        signedIn() -> lastSyncedText()
        expired() -> "Your Trakt Sign-In Has Ended"
        else -> "Sync Watched History, Progress and My List"
    }

    /**
     * TMDB ids of the shows this profile follows on Trakt: watchlist shows first, then shows paused part way, then
     * shows with episodes watched in the last [HISTORY_DAYS] days (newest first). From the last sync, no network.
     */
    fun followedShowTmdbIds(now: Long = System.currentTimeMillis()): List<Int> {
        if (!signedIn()) return emptyList()
        val st = TraktVault.state(pid)
        val out = LinkedHashSet<Int>()
        st.wl?.forEach { k -> if (k.startsWith("tv:")) k.substringAfter(':').toIntOrNull()?.let(out::add) }
        st.pb.keys.sorted().forEach { k -> if (k.startsWith("tv:")) k.substringAfter(':').toIntOrNull()?.let(out::add) }
        val cutoff = now - HISTORY_DAYS * 86_400_000L
        st.shows.entries.filter { it.value >= cutoff }.sortedByDescending { it.value }.forEach { e -> e.key.toIntOrNull()?.let(out::add) }
        return out.filter { it > 0 }
    }

    // =====================================================================================
    // Tokens
    // =====================================================================================

    internal fun tokensFrom(o: kotlinx.serialization.json.JsonObject?): TraktTokens? {
        val a = o.str("access_token")
        val r = o.str("refresh_token")
        if (a.isEmpty() || r.isEmpty()) return null
        val created = o.long("created_at").takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)
        val life = o.long("expires_in").takeIf { it > 0 } ?: 86_400
        return TraktTokens(a, r, created * 1000 + life * 1000)
    }

    private val refreshLock = Any()

    /** A token good for the next hour, renewed if needed; null when signed out (or the sign-in ended). */
    private fun validTokens(p: String): TraktTokens? {
        val t = TraktVault.tokens(p) ?: return null
        if (t.e - System.currentTimeMillis() > REFRESH_AHEAD_MS) return t
        return refresh(p, t) ?: t.takeIf { it.e > System.currentTimeMillis() + 60_000 }
    }

    private fun refresh(p: String, old: TraktTokens): TraktTokens? = synchronized(refreshLock) { refreshLocked(p, old) }

    private fun refreshLocked(p: String, old: TraktTokens): TraktTokens? {
        val cur = TraktVault.tokens(p) ?: return null
        // Renewed meanwhile by another thread.
        if (cur.a != old.a && cur.e - System.currentTimeMillis() > REFRESH_AHEAD_MS) return cur
        val id = clientId.ifEmpty { return null }
        // Renewal must name the app's own redirect URI: the one that worked last first, then the other.
        val last = TraktVault.redirect()
        var reply: Reply? = null
        var used = TraktHttp.REDIRECT
        for (uri in TraktHttp.REDIRECTS.sortedByDescending { it == last }) {
            val body = TraktHttp.client(id, mapOf("refresh_token" to cur.r, "redirect_uri" to uri, "grant_type" to "refresh_token"))
            val got = try { TraktHttp.send(id, "POST", "/oauth/token", body) } catch (e: IOException) { return null }
            reply = got
            used = uri
            if (got.code != 400 && got.code != 401) break
        }
        val r = reply ?: return null
        return when (r.code) {
            200 -> {
                val t = tokensFrom(r.obj()) ?: return null
                // Signed out while the renewal was on its way: it must not sign back in (iPhone).
                if (TraktVault.tokens(p)?.r != cur.r) return null
                TraktVault.setTokens(p, t)
                TraktVault.setRedirect(used)
                Log.i("Trakt", "sign-in renewed")
                t
            }
            400, 401 -> {
                Log.w("Trakt", "sign-in ended (renewal refused, ${r.code}); Sign In Again needed")
                TraktVault.setTokens(p, null)
                TraktVault.setExpired(p, true)
                changed()
                null
            }
            else -> null
        }
    }

    /** A signed-in call (one renewal on 401, one short wait on 429). Null when signed out. Throws IOException offline. */
    internal fun authed(p: String, method: String, path: String, body: kotlinx.serialization.json.JsonObject? = null): Reply? {
        val id = clientId.ifEmpty { return null }
        var t = validTokens(p) ?: return null
        var r = TraktHttp.send(id, method, path, body, t.a)
        if (r.code == 401) {
            t = refresh(p, t) ?: return null
            r = TraktHttp.send(id, method, path, body, t.a)
        }
        if (r.code == 429 && r.retryAfterS in 1..10) {
            Thread.sleep(r.retryAfterS * 1000L)
            r = TraktHttp.send(id, method, path, body, t.a)
        }
        return r
    }

    internal fun getArray(p: String, path: String): kotlinx.serialization.json.JsonArray {
        val r = authed(p, "GET", path) ?: throw IOException("signed out")
        if (!r.ok) throw IOException("Trakt answered ${r.code} for ${path.substringBefore('?')}")
        return r.arr() ?: throw IOException("unreadable answer for ${path.substringBefore('?')}")
    }

    private fun revoke(id: String, t: TraktTokens) {
        io.execute {
            try { TraktHttp.send(id, "POST", "/oauth/revoke", TraktHttp.client(id, mapOf("token" to t.a))) } catch (e: Exception) { /* forgotten here either way */ }
        }
    }

    // =====================================================================================
    // Sign-in (device code)
    // =====================================================================================

    internal sealed class Poll {
        object Waiting : Poll()
        object SlowDown : Poll()
        object Done : Poll()
        class Failed(val message: String) : Poll()
    }

    /**
     * A sign-in code for the current profile. Blocking. Throws [DreamError] with a plain sentence: no Client ID yet,
     * Trakt can't be reached, or Trakt doesn't know the Client ID.
     */
    fun startSignIn(): TraktDeviceCode {
        val id = clientId
        if (id.isEmpty()) throw DreamError(MSG_NO_ID)
        lastSignInProblem = null
        val p = pid
        val r = try {
            TraktHttp.send(id, "POST", "/oauth/device/code", TraktHttp.client(id, emptyMap()))
        } catch (e: IOException) {
            Log.w("Trakt", "couldn't get a sign-in code (no network)")
            throw DreamError(MSG_OFFLINE, cause = e)
        }
        if (r.transient) throw DreamError(MSG_OFFLINE)
        val o = r.obj()
        if (!r.ok || o == null || o.str("device_code").isEmpty() || o.str("user_code").isEmpty()) {
            Log.w("Trakt", "device code refused (${r.code})")
            throw DreamError(if (r.ok) MSG_OFFLINE else MSG_BAD_ID)
        }
        val life = o.long("expires_in").takeIf { it > 0 } ?: 600
        val code = TraktDeviceCode(
            userCode = o.str("user_code"),
            verifyUrl = o.str("verification_url").takeIf { it.startsWith("https://") } ?: "https://trakt.tv/activate",
            expiresAt = System.currentTimeMillis() + life * 1000,
            interval = o.int("interval").takeIf { it > 0 }?.coerceIn(1, 30) ?: 5,
            deviceCode = o.str("device_code"),
        )
        codeProfiles[code.deviceCode] = p
        Log.i("Trakt", "sign-in code shown")
        return code
    }

    /** One poll. On approval the tokens are saved for the code's profile and the first sync starts. Blocking. */
    internal fun poll(dc: TraktDeviceCode): Poll {
        if (System.currentTimeMillis() > dc.expiresAt) return Poll.Failed(MSG_EXPIRED_CODE)
        val id = clientId.ifEmpty { return Poll.Failed(MSG_NO_ID) }
        val r = try {
            TraktHttp.send(id, "POST", "/oauth/device/token", TraktHttp.client(id, mapOf("code" to dc.deviceCode)))
        } catch (e: IOException) { return Poll.Waiting }
        return when (r.code) {
            200 -> {
                val p = codeProfiles[dc.deviceCode] ?: pid
                val t = tokensFrom(r.obj()) ?: return Poll.Failed("Couldn't finish signing in. Try again.")
                synchronized(TraktVault.store) {
                    TraktVault.setTokens(p, t)
                    TraktVault.setState(p, null)
                    TraktVault.setQueue(p, null)
                    TraktVault.setExpired(p, false)
                }
                TraktVault.remember(p)
                codeProfiles.remove(dc.deviceCode)
                val u = try { fetchUser(p) } catch (e: Exception) { null }
                Log.i("Trakt", "signed in" + (u?.let { " as @${it.username}" } ?: ""))
                changed()
                if (background) TraktSync.sync(p, "sign-in", force = true)
                Poll.Done
            }
            400 -> Poll.Waiting
            429 -> Poll.SlowDown
            404 -> Poll.Failed("That code didn't work. Get a new one to try again.")
            409 -> Poll.Failed("That code was already used. Get a new one to try again.")
            410 -> Poll.Failed(MSG_EXPIRED_CODE)
            // Declined, or (since Trakt's October 2026 change) a new Trakt app that only its own account may sign in to.
            418 -> Poll.Failed("Trakt didn't allow this sign-in. Make your Trakt app while signed in to the Trakt account you're signing in with, then try again.")
            401, 403 -> Poll.Failed(MSG_BAD_ID)
            else -> if (r.transient) Poll.Waiting else Poll.Failed(MSG_OFFLINE)
        }
    }

    /**
     * Polls [code] at its interval until it is approved (true), or expires, is declined or fails (false; the reason is
     * in [lastSignInProblem] and [status]). Cancelling the caller stops the polling.
     */
    suspend fun awaitApproval(code: TraktDeviceCode): Boolean {
        var interval = code.interval.coerceAtLeast(1)
        while (true) {
            delay(interval * pollUnitMs)
            when (val r = withContext(Dispatchers.IO) { poll(code) }) {
                Poll.Done -> return true
                Poll.Waiting -> {}
                Poll.SlowDown -> interval += 2
                is Poll.Failed -> {
                    codeProfiles.remove(code.deviceCode)
                    lastSignInProblem = r.message
                    Log.w("Trakt", "sign-in stopped: ${r.message}")
                    changed()
                    return false
                }
            }
        }
    }

    private fun fetchUser(p: String): TraktUser? {
        val r = authed(p, "GET", "/users/settings") ?: return null
        if (!r.ok) return null
        val u = r.obj().o("user") ?: return null
        val name = u.str("username").ifEmpty { return null }
        val avatar = u.o("images").o("avatar").str("full").takeIf { it.startsWith("https://") }
        val user = TraktUser(name, u.str("name").ifEmpty { null }, avatar)
        TraktVault.setUser(p, user)
        return user
    }

    internal fun ensureUser(p: String) {
        if (TraktVault.user(p) == null) try { fetchUser(p) } catch (e: Exception) {}
    }

    /**
     * Signs the current profile out: the sign-in is revoked on Trakt (best effort) and forgotten here with the sync
     * state. Everything already in Continue Watching and My List stays.
     */
    fun signOut() {
        val id = clientId
        val t = TraktVault.wipe(pid)
        lastSyncFailed = false
        Log.i("Trakt", "signed out")
        changed()
        if (t != null && id.isNotEmpty()) revoke(id, t)
    }

    // =====================================================================================
    // Sync and scrobble (the work is in TraktSync / TraktScrobbler)
    // =====================================================================================

    /** Starts a sync for the current profile in the background ([force]: even if nothing moved). Returns at once. */
    fun sync(reason: String = "sync now", force: Boolean = false) {
        if (signedIn()) TraktSync.sync(pid, reason, force)
    }

    /** A full sync for the current profile, waiting for it. Blocking (IO threads). */
    fun syncBlocking() {
        if (!signedIn()) return
        val p = pid
        try {
            syncer.submit {
                TraktSync.markDirty(p)
                TraktSync.syncNow(p, "sync now")
            }.get(3, TimeUnit.MINUTES)
        } catch (e: Exception) {
            Log.w("Trakt", "sync now: ${e.javaClass.simpleName}")
        }
    }

    /**
     * The player's state for Trakt: [ScrobbleAction.START] when it plays, PAUSE when it pauses, STOP when it stops or
     * closes (or moves to another title). [progress]: how far in, 0..1. [durationMs]: the length when known (anything
     * under 5 minutes is never scrobbled). Returns at once and never throws: playback never waits for Trakt. Play and
     * pause must hold for 3 s before Trakt hears of them (a headset toggling sends only the state it settles on).
     */
    fun scrobble(request: PlayRequest, progress: Double, action: ScrobbleAction, durationMs: Long = 0) {
        try { TraktScrobbler.report(request, progress, action, durationMs) } catch (e: Exception) {
            Log.w("Trakt", "scrobble skipped: ${e.javaClass.simpleName}")
        }
    }

    /** The player closed: the current title's stop with its last progress. Returns at once. */
    fun playerClosed() {
        try { TraktScrobbler.closed() } catch (e: Exception) {}
    }
}
