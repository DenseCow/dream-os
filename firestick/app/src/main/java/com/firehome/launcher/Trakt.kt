package com.firehome.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Trakt sync, per Dream OS profile.
 *
 *  - Sign-in: Trakt's device-code flow ([startDevice] / [pollDevice]); the page is [TraktPage]. Tokens are
 *    sealed with an Android Keystore key (AES-GCM) in a private preference file, never logged, and
 *    refreshed an hour before they expire (or on a 401).
 *  - Scrobble: Dream Player reports play / pause / close ([onPlayer], [onPlayerClosed]); start, pause and
 *    stop go out on a background thread, never in playback's way. A stop or pause that can't be sent is
 *    queued (a finished one as a history entry with its real time) and sent with the next sync.
 *  - Sync ([sync]): /sync/last_activities first, then only what changed: recent history and playback
 *    progress into Continue Watching ([WatchHistory.mergeRemote], last write wins by timestamp), and the
 *    watchlist both ways with My List (rules at [syncWatchlist]). On sign-in, at Home (throttled) and
 *    after playback.
 *  - [followedShowTmdbIds]: watchlist shows and shows watched lately, for the new-episode banners.
 *
 * Credentials ([keys]): trakt.client_id / trakt.client_secret in local.properties (BuildConfig) win
 * when present. Open-source builds have none: the viewer brings their own free Trakt app on the
 * Trakt page ([tryOwnKeys]); those keys are Keystore-sealed like the tokens and never logged. Every
 * call (sign-in, scrobble, sync) goes through [keys]; without any, nothing Trakt runs.
 */
object Trakt {
    private const val TAG = "FireHome"
    private const val API = "https://api.trakt.tv"
    private const val REDIRECT = "urn:ietf:wg:oauth:2.0:oob"
    private const val PREFS = "trakt"
    /** Home opening syncs at most this often (a finished playback skips the wait). */
    private const val HOME_EVERY_MS = 15 * 60_000L
    /** Watchlist pages of [WATCHLIST_PAGE] read per type (10,000 titles; past that the sync leaves My List alone). */
    private const val WATCHLIST_PAGE = 500
    private const val WATCHLIST_PAGES = 20
    /** Refresh the access token when it has less than this left. */
    private const val REFRESH_AHEAD_MS = 60 * 60_000L
    /** Trakt counts a stop at this percentage or more as watched. */
    private const val WATCHED_PCT = 80f
    /** History older than this doesn't come into Continue Watching. */
    private const val HISTORY_DAYS = 180L

    /** The viewer's own Trakt app keys (sealed), one for the whole Fire TV. */
    private const val OWN_KEYS = "app_keys"

    /** A Trakt app's client id and secret ([secret] is empty for a PKCE-only app, which has none). */
    class Keys(val id: String, val secret: String)

    /** Sends the secret only when the app has one (Trakt apps made since 2026 are PKCE-only, with no secret). */
    private fun JSONObject.putSecret(k: Keys): JSONObject = apply { if (k.secret.isNotEmpty()) put("client_secret", k.secret) }

    /**
     * This build carries its own Trakt app (local.properties): its keys always win, no setup step. The
     * client id alone is enough: the device-code sign-in works without a secret.
     */
    fun hasBuiltInKeys() = BuildConfig.TRAKT_CLIENT_ID.isNotBlank()

    private val builtIn by lazy { Keys(BuildConfig.TRAKT_CLIENT_ID.trim(), BuildConfig.TRAKT_CLIENT_SECRET.trim()) }
    private val keysLock = Any()
    @Volatile private var ownKeys: Keys? = null
    @Volatile private var ownLoaded = false

    /** The keys every Trakt call uses: built-in first, else the viewer's own app. Null when neither. */
    fun keys(ctx: Context): Keys? = if (hasBuiltInKeys()) builtIn else ownKeys(ctx)

    private fun ownKeys(ctx: Context): Keys? {
        if (ownLoaded) return ownKeys
        synchronized(keysLock) {
            if (!ownLoaded) {
                ownKeys = try {
                    prefs(ctx).getString(OWN_KEYS, null)?.let { Vault.open(it) }?.let { JSONObject(it) }
                        ?.let { Keys(it.getString("id"), it.getString("secret")) }
                } catch (e: Exception) { null }
                ownLoaded = true
            }
            return ownKeys
        }
    }

    /** Trakt can be used: built-in keys, or the viewer's own app. */
    fun configured(ctx: Context) = keys(ctx) != null

    /** Running on the viewer's own Trakt app (no built-in keys): the page offers Remove Trakt App Keys. */
    fun usesOwnKeys(ctx: Context) = !hasBuiltInKeys() && ownKeys(ctx) != null

    private val main = Handler(Looper.getMainLooper())
    /** Scrobbles, the queue and small pushes: one thread, in order. */
    private val io = executor("trakt")
    /** Syncs (TMDB lookups can take a while): their own thread, so a scrobble never waits behind one. */
    private val syncer = executor("trakt-sync")
    private val syncing = AtomicBoolean(false)
    /** Guards read-modify-write of the stored state and queue. */
    private val store = Any()

    private fun executor(name: String) = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, name).apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    /** Main-thread callback after a sync, sign-in or sign-out (the Trakt page repaints). */
    @Volatile var onChanged: (() -> Unit)? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun sfx(ctx: Context) = Profiles.suffix(ctx)
    private fun changed() = main.post { onChanged?.invoke() }

    // =====================================================================================
    // Account state
    // =====================================================================================

    class User(val username: String, val name: String?, val avatar: String?)

    /** Signed in on the current profile (cheap: no decryption, no network). */
    fun signedIn(ctx: Context) = configured(ctx) && prefs(ctx).contains("auth" + sfx(ctx))

    /** The sign-in ended (Trakt refused to renew it): the page offers Sign In Again. */
    fun expired(ctx: Context) = configured(ctx) && !signedIn(ctx) && prefs(ctx).getBoolean("expired" + sfx(ctx), false)

    fun user(ctx: Context): User? = try {
        prefs(ctx).getString("user" + sfx(ctx), null)?.let { JSONObject(it) }?.let {
            User(it.getString("username"), it.optString("name").ifEmpty { null }, it.optString("avatar").ifEmpty { null })
        }
    } catch (e: Exception) { null }

    fun isSyncing() = syncing.get()

    fun lastSyncedAt(ctx: Context): Long = state(ctx, sfx(ctx)).optLong("at", 0)

    /** "Last Synced 2 Min Ago" / "Not Synced Yet". */
    fun lastSyncedText(ctx: Context): String {
        val at = lastSyncedAt(ctx)
        if (at <= 0) return "Not Synced Yet"
        val m = ((System.currentTimeMillis() - at) / 60_000).coerceAtLeast(0)
        return when {
            m < 1 -> "Last Synced Just Now"
            m < 60 -> "Last Synced $m Min Ago"
            m < 48 * 60 -> (m / 60).let { "Last Synced $it Hour${if (it == 1L) "" else "s"} Ago" }
            else -> "Last Synced ${m / 1440} Days Ago"
        }
    }

    // =====================================================================================
    // Followed shows (for NewEpisodes' banners)
    // =====================================================================================

    /**
     * TMDB ids of the shows this profile follows on Trakt: watchlist shows first, then shows paused part
     * way, then shows with episodes watched in the last [HISTORY_DAYS] days (newest first). From the last sync, no network:
     * safe on any thread. Empty when Trakt isn't set up or signed in.
     */
    fun followedShowTmdbIds(ctx: Context): List<Int> {
        if (!signedIn(ctx)) return emptyList()
        val st = state(ctx, sfx(ctx))
        val out = LinkedHashSet<Int>()
        st.optJSONArray("wl")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.startsWith("tv:") }?.substringAfter(':')?.toIntOrNull()?.let(out::add) }
        // Shows paused part way on another device.
        st.optJSONObject("pb")?.keys()?.forEach { k -> if (k.startsWith("tv:")) k.substringAfter(':').toIntOrNull()?.let(out::add) }
        val cutoff = System.currentTimeMillis() - HISTORY_DAYS * 86_400_000L
        st.optJSONObject("shows")?.let { o ->
            o.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to o.optLong(k) } }
                .filter { it.second >= cutoff }.sortedByDescending { it.second }.forEach { out += it.first }
        }
        return out.filter { it > 0 }
    }

    // =====================================================================================
    // Tokens (Keystore-sealed)
    // =====================================================================================

    private class Tokens(val access: String, val refresh: String, val expiresAt: Long)

    private val tokenCache = HashMap<String, Tokens>()

    private fun tokens(ctx: Context, sfx: String): Tokens? {
        synchronized(tokenCache) { tokenCache[sfx]?.let { return it } }
        val sealed = prefs(ctx).getString("auth$sfx", null) ?: return null
        val t = try {
            JSONObject(Vault.open(sealed) ?: return null).let { Tokens(it.getString("a"), it.getString("r"), it.getLong("e")) }
        } catch (e: Exception) { null } ?: return null
        synchronized(tokenCache) { tokenCache[sfx] = t }
        return t
    }

    private fun saveTokens(ctx: Context, sfx: String, t: Tokens?) {
        synchronized(tokenCache) { if (t == null) tokenCache.remove(sfx) else tokenCache[sfx] = t }
        val e = prefs(ctx).edit()
        if (t == null) e.remove("auth$sfx")
        else {
            val sealed = Vault.seal(JSONObject().put("a", t.access).put("r", t.refresh).put("e", t.expiresAt).toString())
                ?: run { Log.w(TAG, "Trakt: couldn't secure the sign-in on this device"); return }
            e.putString("auth$sfx", sealed).remove("expired$sfx")
        }
        e.commit()
    }

    private fun tokensFrom(o: JSONObject): Tokens {
        val created = o.optLong("created_at", System.currentTimeMillis() / 1000) * 1000
        val life = o.optLong("expires_in", 86_400) * 1000
        return Tokens(o.getString("access_token"), o.getString("refresh_token"), created + life)
    }

    private val refreshLock = Any()

    /** A token good for the next hour, refreshed if needed; null when signed out (or the sign-in ended). */
    private fun validTokens(ctx: Context, sfx: String): Tokens? {
        val t = tokens(ctx, sfx) ?: return null
        if (t.expiresAt - System.currentTimeMillis() > REFRESH_AHEAD_MS) return t
        return refresh(ctx, sfx, t) ?: t.takeIf { it.expiresAt > System.currentTimeMillis() + 60_000 }
    }

    private fun refresh(ctx: Context, sfx: String, old: Tokens): Tokens? = synchronized(refreshLock) { refreshLocked(ctx, sfx, old) }

    private fun refreshLocked(ctx: Context, sfx: String, old: Tokens): Tokens? {
        val cur = tokens(ctx, sfx) ?: return null
        // Another thread refreshed it meanwhile.
        if (cur.access != old.access && cur.expiresAt - System.currentTimeMillis() > REFRESH_AHEAD_MS) return cur
        val keys = keys(ctx) ?: return null
        val body = JSONObject().put("refresh_token", cur.refresh).put("client_id", keys.id)
            .putSecret(keys).put("redirect_uri", REDIRECT).put("grant_type", "refresh_token")
        val r = try { http(keys, "POST", "/oauth/token", body.toString(), null) } catch (e: IOException) { return null }
        return when (r.code) {
            200 -> tokensFrom(JSONObject(r.body)).also { saveTokens(ctx, sfx, it); Log.w(TAG, "Trakt: sign-in renewed") }
            400, 401 -> {
                Log.w(TAG, "Trakt: sign-in ended (renewal refused, ${r.code}); Sign In Again needed")
                saveTokens(ctx, sfx, null)
                prefs(ctx).edit().putBoolean("expired$sfx", true).apply()
                changed()
                null
            }
            else -> null
        }
    }

    // =====================================================================================
    // HTTP
    // =====================================================================================

    private class Reply(val code: Int, val body: String, val retryAfterS: Int) {
        val ok get() = code in 200..299
        /** Worth trying again later (network trouble, rate limit, Trakt down). */
        val transient get() = code == 408 || code == 429 || code >= 500
    }

    private fun http(keys: Keys, method: String, path: String, body: String?, token: String?): Reply {
        val c = URL(API + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.useCaches = false // Net installs an HTTP cache; account calls never go through it
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "DreamOS/4.0 (Android TV)")
            c.setRequestProperty("trakt-api-version", "2")
            c.setRequestProperty("trakt-api-key", keys.id)
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = try {
                (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (e: IOException) { "" }
            return Reply(code, text, c.getHeaderField("Retry-After")?.toIntOrNull() ?: 0)
        } finally {
            c.disconnect()
        }
    }

    /** A signed-in call (one renewal on 401, one wait on 429). Null when signed out. Throws IOException offline. */
    private fun authed(ctx: Context, sfx: String, method: String, path: String, body: String? = null): Reply? {
        val keys = keys(ctx) ?: return null
        var t = validTokens(ctx, sfx) ?: return null
        var r = http(keys, method, path, body, t.access)
        if (r.code == 401) {
            t = refresh(ctx, sfx, t) ?: return null
            r = http(keys, method, path, body, t.access)
        }
        if (r.code == 429 && r.retryAfterS in 1..10) {
            Thread.sleep(r.retryAfterS * 1000L)
            r = http(keys, method, path, body, t.access)
        }
        return r
    }

    private fun getArray(ctx: Context, sfx: String, path: String): JSONArray {
        val r = authed(ctx, sfx, "GET", path) ?: throw IOException("signed out")
        if (!r.ok) throw IOException("HTTP ${r.code} for ${path.substringBefore('?')}")
        return JSONArray(r.body)
    }

    // =====================================================================================
    // Sign-in (device code)
    // =====================================================================================

    class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val expiresAt: Long, val intervalS: Int, val sfx: String)

    sealed class Poll {
        object Waiting : Poll()
        object SlowDown : Poll()
        class Done(val user: User?) : Poll()
        /** [badKeys]: Trakt refused the viewer's own app keys (the page offers to enter them again). */
        class Failed(val message: String, val expired: Boolean = false, val badKeys: Boolean = false) : Poll()
    }

    /** Blocking. A code for the current profile, or null when Trakt can't be reached. */
    fun startDevice(ctx: Context): DeviceCode? {
        val keys = keys(ctx) ?: return null
        val sfx = sfx(ctx)
        return try {
            val r = http(keys, "POST", "/oauth/device/code", JSONObject().put("client_id", keys.id).toString(), null)
            if (!r.ok) { Log.w(TAG, "Trakt: device code refused (${r.code})"); return null }
            Log.w(TAG, "Trakt: sign-in code shown")
            deviceCode(r.body, sfx)
        } catch (e: Exception) {
            Log.w(TAG, "Trakt: couldn't get a sign-in code: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun deviceCode(body: String, sfx: String): DeviceCode {
        val o = JSONObject(body)
        return DeviceCode(o.getString("device_code"), o.getString("user_code"), o.optString("verification_url", "https://trakt.tv/activate"),
            System.currentTimeMillis() + o.optLong("expires_in", 600) * 1000, o.optInt("interval", 5).coerceIn(1, 30), sfx)
    }

    // =====================================================================================
    // The viewer's own Trakt app (open-source builds without built-in keys)
    // =====================================================================================

    sealed class KeyCheck {
        /** The keys work and are saved; [code] is a live sign-in code for the current profile. */
        class Ok(val code: DeviceCode) : KeyCheck()
        class Failed(val message: String) : KeyCheck()
    }

    /**
     * Blocking. Checks the viewer's own Trakt app keys and saves them (sealed) when they work: a
     * sign-in code with the client id, then one token poll for the secret (a right one gets "still
     * waiting", a wrong one 401). The code goes straight on to the sign-in screen. Keys are never logged.
     */
    fun tryOwnKeys(ctx: Context, id: String, secret: String): KeyCheck {
        val keys = Keys(id.filterNot { it.isWhitespace() }, secret.filterNot { it.isWhitespace() })
        val bad = KeyCheck.Failed("Those keys didn't work. Check them and try again.")
        if (keys.id.isEmpty()) return bad // the secret is optional: newer Trakt apps don't have one
        val app = ctx.applicationContext
        return try {
            val r = http(keys, "POST", "/oauth/device/code", JSONObject().put("client_id", keys.id).toString(), null)
            if (r.transient) return KeyCheck.Failed("Couldn't reach Trakt. Try again.")
            if (!r.ok) { Log.w(TAG, "Trakt: own app keys refused (${r.code})"); return bad }
            val dc = deviceCode(r.body, sfx(app))
            val p = http(keys, "POST", "/oauth/device/token", JSONObject().put("code", dc.deviceCode).put("client_id", keys.id)
                .putSecret(keys).toString(), null)
            if (p.code == 401 || p.code == 403) { Log.w(TAG, "Trakt: own app secret refused (${p.code})"); return bad }
            val sealed = Vault.seal(JSONObject().put("id", keys.id).put("secret", keys.secret).toString())
                ?: return KeyCheck.Failed("Couldn't save the keys on this Fire Stick. Try again.")
            synchronized(keysLock) {
                prefs(app).edit().putString(OWN_KEYS, sealed).commit()
                ownKeys = keys
                ownLoaded = true
            }
            Log.w(TAG, "Trakt: own app keys saved; sign-in code shown")
            KeyCheck.Ok(dc)
        } catch (e: Exception) {
            Log.w(TAG, "Trakt: couldn't check own app keys: ${e.javaClass.simpleName}")
            KeyCheck.Failed("Couldn't reach Trakt. Try again.")
        }
    }

    /**
     * Forgets the viewer's own Trakt app. Every profile's sign-in belonged to it, so all of them are
     * signed out here (revoked on Trakt, best effort) with their sync state. Built-in keys are untouched.
     */
    fun removeOwnKeys(ctx: Context) {
        val app = ctx.applicationContext
        val keys = ownKeys(app)
        val p = prefs(app)
        val signIns = p.all.keys.filter { it.startsWith("auth") }.mapNotNull { tokens(app, it.removePrefix("auth")) }
        synchronized(tokenCache) { tokenCache.clear() }
        synchronized(store) {
            val e = p.edit()
            for (k in p.all.keys) if (k == OWN_KEYS || listOf("auth", "user", "state", "queue", "expired").any { k.startsWith(it) }) e.remove(k)
            e.commit()
        }
        synchronized(keysLock) { ownKeys = null; ownLoaded = true }
        Log.w(TAG, "Trakt: own app keys removed; every profile signed out")
        changed()
        if (keys != null && signIns.isNotEmpty()) io.execute {
            for (t in signIns) try {
                http(keys, "POST", "/oauth/revoke", JSONObject().put("token", t.access).put("client_id", keys.id)
                    .putSecret(keys).toString(), null)
            } catch (e: Exception) { /* forgotten here either way */ }
        }
    }

    /** Blocking. One poll; on approval the tokens are saved for the code's profile and the first sync starts. */
    fun pollDevice(ctx: Context, dc: DeviceCode): Poll {
        if (System.currentTimeMillis() > dc.expiresAt) return Poll.Failed("The code expired. Get a new one to try again.", expired = true)
        val keys = keys(ctx) ?: return Poll.Failed("Couldn't finish signing in. Try again.")
        val body = JSONObject().put("code", dc.deviceCode).put("client_id", keys.id)
            .putSecret(keys).toString()
        val r = try { http(keys, "POST", "/oauth/device/token", body, null) } catch (e: IOException) { return Poll.Waiting }
        return when (r.code) {
            200 -> {
                val app = ctx.applicationContext
                saveTokens(app, dc.sfx, tokensFrom(JSONObject(r.body)))
                if (!prefs(app).contains("auth${dc.sfx}")) return Poll.Failed("Couldn't finish signing in. Try again.")
                synchronized(store) { prefs(app).edit().remove("state${dc.sfx}").remove("queue${dc.sfx}").commit() }
                val u = try { fetchUser(app, dc.sfx) } catch (e: Exception) { null }
                Log.w(TAG, "Trakt: signed in${u?.let { " as ${it.username}" } ?: ""}")
                changed()
                sync(app, "sign-in", force = true)
                Poll.Done(u)
            }
            400 -> Poll.Waiting
            429 -> Poll.SlowDown
            404 -> Poll.Failed("That code didn't work. Get a new one to try again.", expired = true)
            409 -> Poll.Failed("That code was already used. Get a new one to try again.", expired = true)
            410 -> Poll.Failed("The code expired. Get a new one to try again.", expired = true)
            418 -> Poll.Failed("Sign-in was declined on Trakt.")
            401, 403 -> if (usesOwnKeys(ctx)) Poll.Failed("Those keys didn't work. Check them and try again.", badKeys = true)
                else Poll.Failed("Couldn't reach Trakt. Try again.")
            else -> if (r.transient) Poll.Waiting else Poll.Failed("Couldn't reach Trakt. Try again.")
        }
    }

    private fun fetchUser(ctx: Context, sfx: String): User? {
        val r = authed(ctx, sfx, "GET", "/users/settings") ?: return null
        if (!r.ok) return null
        val u = JSONObject(r.body).optJSONObject("user") ?: return null
        val avatar = u.optJSONObject("images")?.optJSONObject("avatar")?.optString("full")?.takeIf { it.startsWith("https://") }
        val user = User(u.optString("username"), u.optString("name").ifEmpty { null }, avatar)
        if (user.username.isEmpty()) return null
        prefs(ctx).edit().putString("user$sfx", JSONObject().put("username", user.username).put("name", user.name ?: "")
            .put("avatar", user.avatar ?: "").toString()).apply()
        return user
    }

    /** Signs the current profile out: tokens revoked on Trakt (best effort) and forgotten here, with the sync state. */
    fun signOut(ctx: Context) {
        val app = ctx.applicationContext
        val sfx = sfx(app)
        val t = tokens(app, sfx)
        val keys = keys(app)
        saveTokens(app, sfx, null)
        synchronized(store) {
            prefs(app).edit().remove("user$sfx").remove("state$sfx").remove("queue$sfx").remove("expired$sfx").apply()
        }
        Log.w(TAG, "Trakt: signed out")
        changed()
        if (t != null && keys != null) io.execute {
            try {
                http(keys, "POST", "/oauth/revoke", JSONObject().put("token", t.access).put("client_id", keys.id)
                    .putSecret(keys).toString(), null)
            } catch (e: Exception) { /* signed out here either way */ }
        }
    }

    // =====================================================================================
    // Scrobble (Dream Player)
    // =====================================================================================

    private class Play(val key: String, val item: JSONObject, val label: String, val sfx: String) {
        var sent: String? = null
        var progress = 0f
        /** The state the player settled on (null: not told yet); sent once it has held for [SCROBBLE_SETTLE_MS]. */
        var playing: Boolean? = null
        /** A 422 on a pause was logged for this title (it's harmless: logged once, never retried). */
        var quiet422 = false
        var flush: Runnable? = null
    }

    /**
     * Play / pause must hold this long before Trakt hears of it: rapid toggles (a half-connected Bluetooth
     * headset sending play/pause every few seconds) send at most one state, the one it settles on.
     */
    private const val SCROBBLE_SETTLE_MS = 3_000L

    /** Main thread only. */
    private var play: Play? = null

    /**
     * Dream Player's state: called on every play/pause change and with its periodic progress. Main
     * thread, returns at once (the call goes out on Trakt's own thread). Trailers and unknown titles are skipped.
     */
    fun onPlayer(ctx: Context, req: PlaybackRequest?, playing: Boolean, positionMs: Long, durationMs: Long) {
        try {
            if (req == null || req.trailer || req.tmdbId <= 0 || durationMs < 5 * 60_000L) return
            val ep = req.type == "episode"
            if (ep && (req.season == null || req.episode == null)) return
            if (!signedIn(ctx)) return
            val key = if (ep) "tv:${req.tmdbId}:${req.season}:${req.episode}" else "movie:${req.tmdbId}"
            var p = play
            if (p == null || p.key != key) {
                p?.let { close(ctx, it) }
                val item = if (ep) JSONObject().put("show", JSONObject().put("ids", JSONObject().put("tmdb", req.tmdbId)))
                    .put("episode", JSONObject().put("season", req.season).put("number", req.episode))
                else JSONObject().put("movie", JSONObject().put("ids", JSONObject().put("tmdb", req.tmdbId)))
                p = Play(key, item, if (ep) "${req.title} S${req.season}E${req.episode}" else req.title, sfx(ctx))
                play = p
            }
            if (positionMs > 0) p.progress = (positionMs * 100f / durationMs).coerceIn(0f, 100f)
            if (p.playing != playing) {
                p.playing = playing
                val app = ctx.applicationContext
                val pp = p
                p.flush?.let { main.removeCallbacks(it) }
                p.flush = Runnable {
                    pp.flush = null
                    if (play !== pp) return@Runnable
                    when {
                        pp.playing == true && pp.sent != "start" -> send(app, pp, "start")
                        pp.playing == false && pp.sent == "start" -> send(app, pp, "pause")
                    }
                }.also { main.postDelayed(it, SCROBBLE_SETTLE_MS) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Trakt: scrobble skipped: ${e.javaClass.simpleName}")
        }
    }

    /** Dream Player closed (or went away): the current title's stop, with its last progress. */
    fun onPlayerClosed(ctx: Context) {
        val p = play ?: return
        play = null
        try { close(ctx, p) } catch (e: Exception) { Log.w(TAG, "Trakt: stop skipped: ${e.javaClass.simpleName}") }
    }

    private fun close(ctx: Context, p: Play) {
        p.flush?.let { main.removeCallbacks(it) } // a state still settling: the stop says it all
        p.flush = null
        if (p.sent == null || p.sent == "stop") return
        send(ctx, p, "stop")
    }

    private fun send(ctx: Context, p: Play, action: String) {
        p.sent = action
        val app = ctx.applicationContext
        val progress = (Math.round(p.progress * 100) / 100f)
        val body = JSONObject(p.item.toString()).put("progress", progress.toDouble())
        val label = p.label
        val sfx = p.sfx
        Log.w(TAG, "Trakt: scrobble $action $label ${progress.toInt()}%")
        io.execute { scrobbleNow(app, sfx, action, body, label, progress, p) }
    }

    private fun scrobbleNow(app: Context, sfx: String, action: String, body: JSONObject, label: String, progress: Float, p: Play? = null) {
        val r = try { authed(app, sfx, "POST", "/scrobble/$action", body.toString()) } catch (e: Exception) { null }
        when {
            r == null || r.transient -> {
                // Offline: a start means nothing later; a pause or stop is kept (finished = a history entry at its real time).
                if (action == "start" || tokens(app, sfx) == null) return
                val item = JSONObject(body.toString()).apply { remove("progress") }
                if (progress >= WATCHED_PCT && action == "stop") queue(app, sfx, JSONObject().put("op", "history").put("item", item).put("at", iso(System.currentTimeMillis())))
                // "when" (ms): a paused point sent later gets Trakt's time of sending, so [flushLocked] drops it once it's stale.
                else queue(app, sfx, JSONObject().put("op", "progress").put("item", item).put("progress", progress.toDouble()).put("when", System.currentTimeMillis()))
                Log.w(TAG, "Trakt: couldn't reach Trakt, queued $action $label")
            }
            r.ok || r.code == 409 -> { // 409: already scrobbled a moment ago
                // Trakt has this title's newer state now: an older paused point still queued for it must never follow.
                if (action != "start") dropQueuedProgress(app, sfx, JSONObject(body.toString()).apply { remove("progress") })
                if (action == "stop") {
                    if (progress >= WATCHED_PCT) Log.w(TAG, "Trakt: $label marked watched")
                    markDirty(app, sfx)
                    main.postDelayed({ syncer.execute { syncNow(app, sfx, "after playback") } }, 20_000)
                }
            }
            // 422 on a pause: Trakt has nothing playing to pause (it ended or was paused already). Harmless: logged once, not retried.
            r.code == 422 && action == "pause" -> if (p?.quiet422 != true) {
                p?.quiet422 = true
                Log.w(TAG, "Trakt: scrobble pause $label not needed (422), ignored")
            }
            else -> Log.w(TAG, "Trakt: scrobble $action $label not accepted (${r.code})")
        }
    }

    // =====================================================================================
    // My List and Continue Watching hooks
    // =====================================================================================

    /** True while a sync writes My List itself (its own changes aren't pushed back). Main thread. */
    private var applyingRemote = false

    /** Tmdb.toggleMyList: a title was added to or removed from My List here. */
    fun onMyListChanged(ctx: Context, t: Title, inList: Boolean) {
        if (applyingRemote || !signedIn(ctx) || t.id <= 0) return
        val app = ctx.applicationContext
        val sfx = sfx(app)
        val now = System.currentTimeMillis()
        io.execute {
            try {
                editState(app, sfx) { st ->
                    val ledger = st.optJSONObject("ledger") ?: JSONObject().also { st.put("ledger", it) }
                    ledger.put(t.key, JSONObject().put("in", inList).put("at", now))
                    trimLedger(ledger)
                }
                queue(app, sfx, JSONObject().put("op", if (inList) "wl_add" else "wl_remove").put("key", t.key).put("at", now))
                flush(app, sfx)
            } catch (e: Exception) { Log.w(TAG, "Trakt: My List change not sent yet: ${e.javaClass.simpleName}") }
        }
    }

    /** WatchHistory.remove: the title left Continue Watching here; its Trakt playback progress goes too. */
    fun onContinueWatchingRemoved(ctx: Context, key: String) {
        if (!signedIn(ctx)) return
        val app = ctx.applicationContext
        val sfx = sfx(app)
        io.execute {
            try {
                var id = 0L
                editState(app, sfx) { st -> st.optJSONObject("pb")?.let { id = it.optLong(key); it.remove(key) } }
                if (id > 0) {
                    queue(app, sfx, JSONObject().put("op", "pb_remove").put("id", id))
                    flush(app, sfx)
                }
            } catch (e: Exception) { Log.w(TAG, "Trakt: progress removal not sent yet: ${e.javaClass.simpleName}") }
        }
    }

    // =====================================================================================
    // State and queue (prefs, per profile)
    // =====================================================================================

    private fun state(ctx: Context, sfx: String): JSONObject = try {
        JSONObject(prefs(ctx).getString("state$sfx", null) ?: "{}")
    } catch (e: Exception) { JSONObject() }

    private fun editState(ctx: Context, sfx: String, edit: (JSONObject) -> Unit) = synchronized(store) {
        if (!prefs(ctx).contains("auth$sfx")) return@synchronized // signed out meanwhile: nothing to keep
        val st = state(ctx, sfx)
        edit(st)
        prefs(ctx).edit().putString("state$sfx", st.toString()).apply()
    }

    private fun markDirty(ctx: Context, sfx: String) = editState(ctx, sfx) { it.put("dirty", true) }

    private fun trimLedger(l: JSONObject) {
        if (l.length() <= 300) return
        val old = l.keys().asSequence().toList().sortedBy { l.optJSONObject(it)?.optLong("at") ?: 0 }.take(l.length() - 300)
        old.forEach { l.remove(it) }
    }

    private fun loadQueue(ctx: Context, sfx: String): JSONArray = try {
        JSONArray(prefs(ctx).getString("queue$sfx", null) ?: "[]")
    } catch (e: Exception) { JSONArray() }

    /** Adds [op] (newest wins for the same title and kind), at most 200 kept. */
    private fun queue(ctx: Context, sfx: String, op: JSONObject) = synchronized(store) {
        if (!prefs(ctx).contains("auth$sfx")) return@synchronized
        op.put("id", System.nanoTime())
        val q = loadQueue(ctx, sfx)
        val same = opKey(op)
        val out = JSONArray()
        for (i in 0 until q.length()) q.optJSONObject(i)?.takeIf { opKey(it) != same }?.let { out.put(it) }
        out.put(op)
        val start = (out.length() - 200).coerceAtLeast(0)
        val kept = JSONArray().apply { for (i in start until out.length()) put(out.get(i)) }
        prefs(ctx).edit().putString("queue$sfx", kept.toString()).apply()
    }

    private fun opKey(o: JSONObject) = when (val op = o.optString("op")) {
        "wl_add", "wl_remove" -> "wl:" + o.optString("key")
        "pb_remove" -> "pb:" + o.optLong("id")
        else -> op + ":" + o.optJSONObject("item")?.toString()
    }

    /** Removes a queued paused point for [item] (the scrobble that just went through says more). */
    private fun dropQueuedProgress(ctx: Context, sfx: String, item: JSONObject) = synchronized(store) {
        val same = "progress:" + item.toString()
        val q = loadQueue(ctx, sfx)
        val ids = (0 until q.length()).mapNotNull { q.optJSONObject(it)?.takeIf { o -> opKey(o) == same }?.optLong("id") }.toSet()
        dropFromQueue(ctx, sfx, ids)
    }

    /**
     * A queued paused point that's out of date: Trakt stamps it with the time it's sent, so sent late it would look
     * like the newest thing watched and pull Continue Watching back to it (an older episode, an older spot). Stale when
     * the watch history here has moved on since: a later episode of the show, or the same title saved again well after.
     */
    private fun staleProgress(ctx: Context, sfx: String, o: JSONObject): Boolean {
        if (o.optString("op") != "progress" || Profiles.suffix(ctx) != sfx) return false
        val item = o.optJSONObject("item") ?: return false
        val at = o.optLong("when") // 0: queued by an older build, before it was kept
        val show = item.optJSONObject("show")?.optJSONObject("ids")?.optInt("tmdb") ?: 0
        val movie = item.optJSONObject("movie")?.optJSONObject("ids")?.optInt("tmdb") ?: 0
        val key = if (show > 0) "tv:$show" else if (movie > 0) "movie:$movie" else return false
        val e = WatchHistory.recent(ctx).firstOrNull { it.key == key } ?: return false
        if (show > 0) {
            val ep = item.optJSONObject("episode") ?: return false
            if (WatchHistory.later(e, ep.optInt("season"), ep.optInt("number"))) return true
        }
        return e.updatedAt > at + 60_000
    }

    private fun dropFromQueue(ctx: Context, sfx: String, ids: Set<Long>) = synchronized(store) {
        if (ids.isEmpty()) return@synchronized
        val q = loadQueue(ctx, sfx)
        val out = JSONArray()
        for (i in 0 until q.length()) q.optJSONObject(i)?.takeIf { it.optLong("id") !in ids }?.let { out.put(it) }
        prefs(ctx).edit().putString("queue$sfx", out.toString()).apply()
    }

    /**
     * Sends what's queued, oldest first; stops at the first network failure (the rest waits for the next
     * try). Ops Trakt refuses for good (unknown title, full watchlist) are dropped. Blocking.
     */
    private fun flush(ctx: Context, sfx: String): Boolean = synchronized(flushLock) { flushLocked(ctx, sfx) }

    private fun flushLocked(ctx: Context, sfx: String): Boolean {
        val q = loadQueue(ctx, sfx)
        if (q.length() == 0) return true
        val done = HashSet<Long>()
        var allSent = true
        try {
            for (i in 0 until q.length()) {
                val o = q.optJSONObject(i) ?: continue
                if (staleProgress(ctx, sfx, o)) {
                    done += o.optLong("id")
                    Log.w(TAG, "Trakt: queued paused point dropped (watched further here since)")
                    continue
                }
                val r = sendOp(ctx, sfx, o)
                if (r == null || r.transient) { allSent = false; break }
                done += o.optLong("id")
                if (!r.ok && r.code != 409) Log.w(TAG, "Trakt: queued ${o.optString("op")} not accepted (${r.code}), dropped")
                if (r.ok) afterOp(ctx, sfx, o)
            }
        } catch (e: Exception) {
            // Whatever stopped it, what already went out is never sent again.
            allSent = false
        }
        dropFromQueue(ctx, sfx, done)
        if (done.isNotEmpty()) Log.w(TAG, "Trakt: sent ${done.size} queued change${if (done.size == 1) "" else "s"}")
        return allSent
    }

    /** One flush at a time (the scrobble thread and the sync thread both flush): nothing is sent twice. */
    private val flushLock = Any()

    private fun sendOp(ctx: Context, sfx: String, o: JSONObject): Reply? = when (o.optString("op")) {
        "history" -> {
            val item = o.getJSONObject("item")
            val at = o.optString("at")
            val body = if (item.has("movie")) JSONObject().put("movies", JSONArray().put(JSONObject(item.getJSONObject("movie").toString()).put("watched_at", at)))
            else {
                val ep = item.getJSONObject("episode")
                JSONObject().put("shows", JSONArray().put(JSONObject().put("ids", item.getJSONObject("show").getJSONObject("ids"))
                    .put("seasons", JSONArray().put(JSONObject().put("number", ep.optInt("season"))
                        .put("episodes", JSONArray().put(JSONObject().put("number", ep.optInt("number")).put("watched_at", at)))))))
            }
            authed(ctx, sfx, "POST", "/sync/history", body.toString())
        }
        "progress" -> authed(ctx, sfx, "POST", "/scrobble/pause",
            JSONObject(o.getJSONObject("item").toString()).put("progress", o.optDouble("progress")).toString())
        "wl_add", "wl_remove" -> {
            val key = o.optString("key")
            authed(ctx, sfx, "POST", if (o.optString("op") == "wl_add") "/sync/watchlist" else "/sync/watchlist/remove", watchlistBody(listOf(key)).toString())
        }
        "pb_remove" -> authed(ctx, sfx, "DELETE", "/sync/playback/${o.optLong("id")}").let { r ->
            if (r != null && r.code == 404) Reply(204, "", 0) else r // already gone
        }
        else -> Reply(204, "", 0)
    }

    /** A pushed watchlist change: the snapshot of what's on Trakt follows. */
    private fun afterOp(ctx: Context, sfx: String, o: JSONObject) {
        val op = o.optString("op")
        if (op != "wl_add" && op != "wl_remove") return
        val key = o.optString("key")
        editState(ctx, sfx) { st ->
            val snap = st.optJSONArray("wl") ?: return@editState
            val set = (0 until snap.length()).map { snap.optString(it) }.toMutableSet()
            if (op == "wl_add") set += key else set -= key
            st.put("wl", JSONArray(set.toList()))
        }
    }

    private fun watchlistBody(keys: Collection<String>): JSONObject {
        val movies = JSONArray()
        val shows = JSONArray()
        for (k in keys) {
            val id = k.substringAfter(':').toIntOrNull() ?: continue
            val o = JSONObject().put("ids", JSONObject().put("tmdb", id))
            if (k.startsWith("movie:")) movies.put(o) else if (k.startsWith("tv:")) shows.put(o)
        }
        return JSONObject().put("movies", movies).put("shows", shows)
    }

    // =====================================================================================
    // Sync
    // =====================================================================================

    /** Home opened: a sync if the last one is old or something was played since. Returns at once (the state is read on the sync thread). */
    fun onHomeOpened(ctx: Context) {
        try {
            if (!signedIn(ctx) || syncing.get()) return
            val app = ctx.applicationContext
            val sfx = sfx(app)
            syncer.execute {
                val st = state(app, sfx)
                if (st.optBoolean("dirty") || System.currentTimeMillis() - st.optLong("at", 0) >= HOME_EVERY_MS) syncNow(app, sfx, "home")
            }
        } catch (e: Exception) { Log.w(TAG, "Trakt: home sync skipped: ${e.javaClass.simpleName}") }
    }

    /** Starts a sync for the current profile in the background (Sync Now: [force]). */
    fun sync(ctx: Context, reason: String, force: Boolean = false) {
        if (!signedIn(ctx)) return
        val app = ctx.applicationContext
        val sfx = sfx(app)
        syncer.execute {
            if (force) markDirty(app, sfx)
            syncNow(app, sfx, reason)
        }
    }

    /** The last sync couldn't finish (offline, Trakt down); the page says so in plain words. */
    @Volatile var lastSyncFailed = false
        private set

    private fun syncNow(app: Context, sfx: String, reason: String) {
        if (Profiles.suffix(app) != sfx || !prefs(app).contains("auth$sfx")) return // profile switched or signed out
        if (!syncing.compareAndSet(false, true)) return
        changed()
        val started = System.currentTimeMillis()
        var ok = false
        try {
            if (!flush(app, sfx)) { Log.w(TAG, "Trakt: sync ($reason) waits: couldn't reach Trakt"); return }
            val r = authed(app, sfx, "GET", "/sync/last_activities") ?: return
            if (!r.ok) { Log.w(TAG, "Trakt: sync ($reason) waits: Trakt answered ${r.code}"); return }
            val la = JSONObject(r.body)
            val st = state(app, sfx)
            val prev = st.optJSONObject("la")
            fun at(o: JSONObject?, group: String, field: String) = o?.optJSONObject(group)?.optString(field).orEmpty()
            fun moved(group: String, field: String) = prev == null || at(prev, group, field) != at(la, group, field)

            if (user(app) == null) try { fetchUser(app, sfx) } catch (e: Exception) {}
            var watched = 0
            var progress = 0
            var listed = 0
            if (Tmdb.hasKey(app)) {
                if (moved("episodes", "watched_at") || moved("movies", "watched_at")) watched = pullHistory(app, sfx, st, prev)
                if (moved("episodes", "paused_at") || moved("movies", "paused_at")) progress = pullPlayback(app, sfx, st)
                if (moved("movies", "watchlisted_at") || moved("shows", "watchlisted_at") || !st.has("wl")) listed = syncWatchlist(app, sfx, st, la)
            }
            // Saved as one: the activity marks move on only once everything they stand for is in.
            synchronized(store) {
                // Signed out, or another profile took over mid-sync (its pulls were skipped): the marks stay, so the next sync pulls again.
                if (!prefs(app).contains("auth$sfx") || Profiles.suffix(app) != sfx) return
                val cur = state(app, sfx)
                for (k in listOf("shows", "pb", "wl")) st.opt(k)?.let { cur.put(k, it) }
                cur.put("la", la).put("at", System.currentTimeMillis()).remove("dirty")
                prefs(app).edit().putString("state$sfx", cur.toString()).apply()
            }
            ok = true
            Log.w(TAG, "Trakt: sync ($reason) pulled $watched watched, $progress in progress, $listed watchlist in ${System.currentTimeMillis() - started} ms")
        } catch (e: Exception) {
            Log.w(TAG, "Trakt: sync ($reason) stopped: ${e.javaClass.simpleName}")
        } finally {
            lastSyncFailed = !ok && Profiles.suffix(app) == sfx // a sync cut short by a profile switch isn't a failure to show
            syncing.set(false)
            changed()
        }
    }

    /** Recent history (since the last sync) into Continue Watching; also the "shows watched lately" for [followedShowTmdbIds]. */
    private fun pullHistory(app: Context, sfx: String, st: JSONObject, prev: JSONObject?): Int {
        val cutoff = System.currentTimeMillis() - HISTORY_DAYS * 86_400_000L
        fun since(group: String) = prev?.optJSONObject(group)?.optString("watched_at")?.takeIf { it.isNotEmpty() }
            ?.let { "&start_at=" + URLEncoder.encode(it, "UTF-8") } ?: ""
        val shows = st.optJSONObject("shows") ?: JSONObject()
        val entries = ArrayList<WatchHistory.Entry>()

        val latestPerShow = LinkedHashMap<Int, Pair<Long, JSONObject>>()
        // Everything Trakt lists as watched counts for the Watched pill, not only what reaches Continue Watching.
        val watchedEps = HashMap<Int, HashSet<Pair<Int, Int>>>()
        val watchedMovies = HashSet<Int>()
        val eps = getArray(app, sfx, "/sync/history/episodes?limit=100" + since("episodes"))
        for (i in 0 until eps.length()) {
            val o = eps.optJSONObject(i) ?: continue
            val sid = o.optJSONObject("show")?.optJSONObject("ids")?.optInt("tmdb") ?: 0
            val at = iso(o.optString("watched_at"))
            if (sid <= 0 || at <= 0) continue
            o.optJSONObject("episode")?.let { e ->
                val s = e.optInt("season"); val n = e.optInt("number")
                if (s > 0 && n > 0) watchedEps.getOrPut(sid) { HashSet() }.add(s to n)
            }
            if (at > shows.optLong("$sid")) shows.put("$sid", at)
            if ((latestPerShow[sid]?.first ?: 0) < at) latestPerShow[sid] = at to o
        }
        for ((sid, p) in latestPerShow.entries.sortedByDescending { it.value.first }.take(15)) {
            if (p.first < cutoff) continue
            val ep = p.second.optJSONObject("episode") ?: continue
            entryFor(app, "episode", sid, ep.optInt("season"), ep.optInt("number"), 1f, p.first)?.let(entries::add)
        }

        val movies = getArray(app, sfx, "/sync/history/movies?limit=30" + since("movies"))
        val seen = HashSet<Int>()
        for (i in 0 until movies.length()) {
            val o = movies.optJSONObject(i) ?: continue
            val id = o.optJSONObject("movie")?.optJSONObject("ids")?.optInt("tmdb") ?: 0
            val at = iso(o.optString("watched_at"))
            if (id > 0) watchedMovies.add(id)
            if (id <= 0 || at < cutoff || !seen.add(id) || seen.size > 8) continue
            entryFor(app, "movie", id, 0, 0, 1f, at)?.let(entries::add)
        }
        if (Profiles.suffix(app) == sfx) Watched.fromTrakt(app, watchedMovies, watchedEps)

        // Keep the 80 most recent shows.
        if (shows.length() > 80) shows.keys().asSequence().toList().sortedBy { shows.optLong(it) }.take(shows.length() - 80).forEach { shows.remove(it) }
        st.put("shows", shows)
        return merge(app, sfx, entries)
    }

    /** Trakt's paused-at points (other devices) into Continue Watching; the ids are kept so a removal here clears them there. */
    private fun pullPlayback(app: Context, sfx: String, st: JSONObject): Int {
        val a = getArray(app, sfx, "/sync/playback")
        val pb = JSONObject()
        val newest = LinkedHashMap<String, Triple<Long, JSONObject, Float>>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val pct = o.optDouble("progress", 0.0).toFloat() / 100f
            val at = iso(o.optString("paused_at"))
            if (pct < 0.02f || at <= 0) continue
            val key = when (o.optString("type")) {
                "movie" -> o.optJSONObject("movie")?.optJSONObject("ids")?.optInt("tmdb")?.takeIf { it > 0 }?.let { "movie:$it" }
                "episode" -> o.optJSONObject("show")?.optJSONObject("ids")?.optInt("tmdb")?.takeIf { it > 0 }?.let { "tv:$it" }
                else -> null
            } ?: continue
            if ((newest[key]?.first ?: 0) < at) newest[key] = Triple(at, o, pct)
        }
        val entries = ArrayList<WatchHistory.Entry>()
        for ((key, t) in newest.entries.sortedByDescending { it.value.first }.take(20)) {
            pb.put(key, t.second.optLong("id"))
            val id = key.substringAfter(':').toInt()
            val e = if (key.startsWith("movie:")) entryFor(app, "movie", id, 0, 0, t.third, t.first)
            else t.second.optJSONObject("episode")?.let { entryFor(app, "episode", id, it.optInt("season"), it.optInt("number"), t.third, t.first) }
            e?.let(entries::add)
        }
        st.put("pb", pb)
        return merge(app, sfx, entries)
    }

    private fun merge(app: Context, sfx: String, entries: List<WatchHistory.Entry>): Int {
        if (entries.isEmpty() || Profiles.suffix(app) != sfx) return 0
        return WatchHistory.mergeRemote(app, entries)
    }

    /** A Continue Watching entry for a Trakt item, filled from TMDB (cached). Blocking. */
    private fun entryFor(app: Context, type: String, tmdbId: Int, season: Int, episode: Int, progress: Float, at: Long): WatchHistory.Entry? {
        val isShow = type == "episode"
        val d = WatchHistory.details(app, if (isShow) "tv" else "movie", tmdbId) ?: return null
        var epTitle: String? = null
        var still: String? = null
        var duration = if (isShow) 0L else d.optInt("runtime") * 60_000L
        if (isShow) {
            if (season <= 0 && episode <= 0) return null
            WatchHistory.seasonEpisodes(app, tmdbId, season)?.firstOrNull { it.optInt("episode_number") == episode }?.let { ep ->
                epTitle = ep.optString("name").ifEmpty { null }
                still = ep.optString("still_path").takeIf { it.isNotEmpty() && it != "null" }
                duration = ep.optInt("runtime") * 60_000L
            }
            if (duration <= 0) duration = (d.optJSONArray("episode_run_time")?.optInt(0) ?: 0) * 60_000L
        }
        fun img(k: String) = d.optString(k).takeIf { it.isNotEmpty() && it != "null" }
        val date = d.optString(if (isShow) "first_air_date" else "release_date")
        return WatchHistory.Entry(
            type = type, tmdbId = tmdbId, title = d.optString(if (isShow) "name" else "title"),
            episodeTitle = epTitle, season = season, episode = episode, year = date.take(4).toIntOrNull(),
            imdb = (d.optJSONObject("external_ids")?.optString("imdb_id") ?: d.optString("imdb_id")).takeIf { it.startsWith("tt") },
            progress = progress.coerceIn(0f, 1f), positionMs = if (duration > 0) (duration * progress).toLong() else 0L, durationMs = duration,
            updatedAt = at, source = WatchHistory.DREAM, upNext = false, hidden = false,
            posterPath = img("poster_path"), backdropPath = img("backdrop_path"), stillPath = still,
            overview = d.optString("overview"), rating = d.optDouble("vote_average", 0.0), deepLink = null,
        ).takeIf { it.title.isNotEmpty() }
    }

    /**
     * Trakt watchlist <-> My List. Last write wins:
     *  - On Trakt, not here: added here, unless it was removed here after Trakt listed it (then removed on Trakt).
     *  - Here, not on Trakt, and it was on Trakt at the last sync (removed there): removed here, unless it was
     *    added here after Trakt's last watchlist change (then added back on Trakt).
     *  - Here, never seen on Trakt (added before signing in, or the first sync): added on Trakt.
     * Removals on Trakt carry no time of their own; Trakt's last watchlist activity stands in for it.
     */
    private fun syncWatchlist(app: Context, sfx: String, st: JSONObject, la: JSONObject): Int {
        val remote = LinkedHashMap<String, Long>()
        for ((path, type, field) in listOf(Triple("movies", "movie", "movie"), Triple("shows", "tv", "show"))) {
            // Every page: a title missing from the read would count as removed on Trakt and leave My List.
            var page = 1
            while (page <= WATCHLIST_PAGES) {
                val a = getArray(app, sfx, "/sync/watchlist/$path?page=$page&limit=$WATCHLIST_PAGE")
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val id = o.optJSONObject(field)?.optJSONObject("ids")?.optInt("tmdb") ?: 0
                    if (id > 0) remote["$type:$id"] = iso(o.optString("listed_at"))
                }
                if (a.length() < WATCHLIST_PAGE) break
                page++
            }
            // Past the last page read, the rest is unknown: no two-way sync rather than removals that aren't real.
            if (page > WATCHLIST_PAGES) { Log.w(TAG, "Trakt: watchlist over ${WATCHLIST_PAGES * WATCHLIST_PAGE} $path, not synced"); return 0 }
        }
        val snap: Set<String>? = st.optJSONArray("wl")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
        val ledger = st.optJSONObject("ledger") ?: JSONObject()
        val local = onMain { if (Profiles.suffix(app) != sfx) null else Tmdb.myList(app).map { it.key } }
            ?: throw IOException("My List not readable for this profile")
        val removedAt = maxOf(iso(la.optJSONObject("movies")?.optString("watchlisted_at").orEmpty()),
            iso(la.optJSONObject("shows")?.optString("watchlisted_at").orEmpty()))
        val addHere = ArrayList<String>()
        val removeHere = ArrayList<String>()
        val pushAdd = ArrayList<String>()
        val pushRemove = ArrayList<String>()
        val localSet = local.toSet()
        for ((k, listedAt) in remote) if (k !in localSet) {
            val l = ledger.optJSONObject(k)
            if (l != null && !l.optBoolean("in") && l.optLong("at") > listedAt) pushRemove += k else addHere += k
        }
        for (k in local) if (k !in remote) {
            val l = ledger.optJSONObject(k)
            if (snap != null && k in snap) {
                if (l != null && l.optBoolean("in") && l.optLong("at") > removedAt) pushAdd += k else removeHere += k
            } else pushAdd += k
        }
        // Newest-listed last, so it ends up first (My List puts each addition at the top).
        val titles = addHere.sortedBy { remote[it] ?: 0 }.takeLast(60).mapNotNull { titleFor(app, it) }
        if (titles.isNotEmpty() || removeHere.isNotEmpty()) onMain {
            if (Profiles.suffix(app) != sfx) return@onMain
            applyingRemote = true
            try {
                for (t in titles) if (!Tmdb.inMyList(app, t)) Tmdb.toggleMyList(app, t)
                for (k in removeHere) Tmdb.myList(app).firstOrNull { it.key == k }?.let { Tmdb.toggleMyList(app, it) }
            } finally { applyingRemote = false }
        }
        val onTrakt = remote.keys.toMutableSet()
        if (pushAdd.isNotEmpty()) authed(app, sfx, "POST", "/sync/watchlist", watchlistBody(pushAdd).toString())?.let { r ->
            if (r.ok) onTrakt += pushAdd
            else if (r.code == 420) Log.w(TAG, "Trakt: watchlist is at its limit, ${pushAdd.size} title(s) stay only in My List")
        }
        if (pushRemove.isNotEmpty()) authed(app, sfx, "POST", "/sync/watchlist/remove", watchlistBody(pushRemove).toString())?.let { r ->
            if (r.ok) onTrakt -= pushRemove.toSet()
        }
        st.put("wl", JSONArray(onTrakt.toList()))
        if (pushAdd.isNotEmpty() || pushRemove.isNotEmpty()) Log.w(TAG, "Trakt: watchlist pushed ${pushAdd.size} added, ${pushRemove.size} removed")
        return titles.size + removeHere.size
    }

    private fun titleFor(app: Context, key: String): Title? {
        val type = key.substringBefore(':')
        val id = key.substringAfter(':').toIntOrNull() ?: return null
        val d = WatchHistory.details(app, type, id) ?: return null
        val date = d.optString(if (type == "movie") "release_date" else "first_air_date")
        fun img(k: String) = d.optString(k).takeIf { it.isNotEmpty() && it != "null" }
        return Title(id, type, d.optString(if (type == "movie") "title" else "name"), d.optString("overview"),
            img("poster_path"), img("backdrop_path"), date.take(4), d.optDouble("vote_average", 0.0),
            date = date.takeIf { it.length >= 10 }.orEmpty(), votes = d.optInt("vote_count", 0),
            lang = d.optString("original_language")).takeIf { it.name.isNotEmpty() }
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    private fun <T> onMain(work: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return work()
        val latch = CountDownLatch(1)
        var out: T? = null
        main.post {
            try { out = work() } catch (e: Exception) { Log.w(TAG, "Trakt: main-thread step failed: ${e.javaClass.simpleName}") }
            finally { latch.countDown() }
        }
        latch.await(5, TimeUnit.SECONDS)
        return out
    }

    private fun iso(s: String): Long = try {
        if (s.isEmpty() || s == "null") 0L else java.time.Instant.parse(s).toEpochMilli()
    } catch (e: Exception) { 0L }

    private fun iso(ms: Long): String = java.time.Instant.ofEpochMilli(ms).toString()

    /** AES-GCM with a key that never leaves the Android Keystore. */
    private object Vault {
        private const val ALIAS = "dreamos.trakt"

        @Synchronized private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            return g.generateKey()
        }

        fun seal(plain: String): String? = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        } catch (e: Exception) { null }

        fun open(sealed: String): String? = try {
            val iv = Base64.decode(sealed.substringBefore(':'), Base64.NO_WRAP)
            val ct = Base64.decode(sealed.substringAfter(':'), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }
}
