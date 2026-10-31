package com.dreamos.desktop.core.trakt

import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.platform.Secrets
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.util.parseJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.io.IOException
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

// Trakt's storage and HTTP (fire-home Trakt.kt: Keys, Tokens, HTTP, State and queue; the iPhone's TraktStore.swift).
// Per profile, like the TV: every profile signs in on its own. The viewer's Trakt Client ID is one per PC
// (`trakt_app.json`); a profile's sign-in is sealed with DPAPI in its secrets file (`secrets[_<id>].json`, key
// `trakt.tokens`), its sync state and the queue of changes still to send in `trakt[_<id>].json`. Round 1 kept the
// sign-in plain in `trakt_auth[_<id>].json`: moved over and deleted on first read. Nothing here is ever logged.

/** The signed-in Trakt account (shown on the Trakt page). */
@Serializable
data class TraktUser(val username: String, val name: String? = null, val avatar: String? = null)

@Serializable
internal data class TraktTokens(val a: String, val r: String, /** Expires at, epoch ms. */ val e: Long)

/** One title for the scrobble and history calls: a movie, or one episode of a show (TMDB ids). */
@Serializable
internal data class TraktItem(val movie: Boolean, val tmdb: Int, val season: Int = 0, val number: Int = 0) {
    /** {"movie": {"ids": {"tmdb": 1}}} / {"show": {"ids": {"tmdb": 1}}, "episode": {"season": 1, "number": 2}} */
    fun json(): JsonObject = buildJsonObject {
        if (movie) putJsonObject("movie") { putJsonObject("ids") { put("tmdb", tmdb) } }
        else {
            putJsonObject("show") { putJsonObject("ids") { put("tmdb", tmdb) } }
            putJsonObject("episode") { put("season", season); put("number", number) }
        }
    }

    /** The history key of the movie or show ("movie:603", "tv:1396"). */
    val key: String get() = (if (movie) "movie:" else "tv:") + tmdb
}

/** A change still to send: "history", "progress", "wl_add", "wl_remove", "pb_remove". */
@Serializable
internal data class TraktOp(
    val op: String,
    val id: Long = 0,
    /** wl_*: the title key ("movie:603"). */
    val key: String? = null,
    /** wl_*: when it changed here (ms); history: when it was watched (ms). */
    val at: Long? = null,
    val item: TraktItem? = null,
    /** progress: 0..100. */
    val progress: Double? = null,
    /** pb_remove: Trakt's playback id. */
    val pbId: Long? = null,
    /**
     * progress: when the pause happened here (ms). Trakt stamps a paused point with the time it's sent, so one sent
     * late is dropped once the history moved past it ([TraktSync.staleProgress]).
     */
    val `when`: Long? = null,
) {
    /** Newest wins for the same title and kind. */
    val sameKey: String
        get() = when (op) {
            "wl_add", "wl_remove" -> "wl:" + key.orEmpty()
            "pb_remove" -> "pb:" + (pbId ?: 0)
            else -> op + ":" + (item?.let { "${it.movie}:${it.tmdb}:${it.season}:${it.number}" } ?: "")
        }
}

@Serializable
internal data class TraktMark(val inList: Boolean, val at: Long)

/** What the last sync knew, per profile (fire-home's "state" JSON). */
@Serializable
internal data class TraktState(
    /** The last /sync/last_activities answer, as Trakt sent it. */
    val la: JsonObject? = null,
    /** When the last sync finished (ms). */
    val at: Long = 0,
    /** Something played or changed since: the next timer tick doesn't wait. */
    val dirty: Boolean = false,
    /** TMDB show id -> when an episode was last watched (ms), for the followed shows. */
    val shows: Map<String, Long> = emptyMap(),
    /** "tv:1396" / "movie:603" -> Trakt's playback id (a removal here clears it there). */
    val pb: Map<String, Long> = emptyMap(),
    /** What was on the Trakt watchlist at the last sync (null before the first one). */
    val wl: List<String>? = null,
    /** My List changes made here: key -> (on the list, when). */
    val ledger: Map<String, TraktMark> = emptyMap(),
)

/** Settings access for Trakt. Read-modify-write of a profile's state and queue holds [store]. */
internal object TraktVault {
    private const val APP = "trakt_app"
    private val opsSer = ListSerializer(TraktOp.serializer())

    /** Guards read-modify-write of the state and queue. */
    val store = Any()

    private const val TOKENS = "trakt.tokens"
    private const val LEGACY_AUTH = "trakt_auth"

    private fun app() = DreamStore.shared.file(APP)
    private fun data(pid: String) = DreamStore.shared.profileFile("trakt", pid)
    private fun secrets(pid: String) = Secrets.profile(pid)
    private val tokenJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    // ---- the viewer's Trakt app (one per PC) ----

    fun clientId(): String = app().string("client_id").trim()
    fun setClientId(id: String) = app().putString("client_id", id.ifEmpty { null })

    /** The redirect URI the last renewal worked with. */
    fun redirect(): String? = app().getString("redirect")
    fun setRedirect(uri: String) = app().putString("redirect", uri)

    /** Profiles that ever signed in (signing everyone out when the Client ID changes). */
    fun profiles(): List<String> = app().getStringList("profiles")
    fun remember(pid: String) {
        val p = profiles()
        if (pid !in p) app().putStringList("profiles", p + pid)
    }

    // ---- a profile's sign-in ----

    fun tokens(pid: String): TraktTokens? {
        migrate(pid)
        val text = secrets(pid).get(TOKENS) ?: return null
        return try { tokenJson.decodeFromString(TraktTokens.serializer(), text).takeIf { it.a.isNotEmpty() } } catch (e: Exception) { null }
    }

    fun setTokens(pid: String, t: TraktTokens?) {
        migrate(pid)
        if (t == null) secrets(pid).remove(TOKENS)
        else {
            secrets(pid).put(TOKENS, tokenJson.encodeToString(TraktTokens.serializer(), t))
            data(pid).remove("expired")
        }
    }

    /** Round 1's plain `trakt_auth[_<id>]` file: its sign-in moves into the sealed store once, then the file goes. */
    private val migrated: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun migrate(pid: String) {
        val store = DreamStore.shared
        val name = LEGACY_AUTH + DreamStore.suffix(pid)
        if (!migrated.add(store.dir.toString() + "|" + name) || !store.exists(name)) return
        synchronized(store) {
            if (!store.exists(name)) return
            val old = store.file(name).get("tokens", TraktTokens.serializer())
            if (old != null && old.a.isNotEmpty() && secrets(pid).get(TOKENS) == null) {
                secrets(pid).put(TOKENS, tokenJson.encodeToString(TraktTokens.serializer(), old))
            }
            store.delete(name)
        }
    }

    fun user(pid: String): TraktUser? = data(pid).get("user", TraktUser.serializer())
    fun setUser(pid: String, u: TraktUser?) = if (u == null) data(pid).remove("user") else data(pid).put("user", TraktUser.serializer(), u)

    fun expired(pid: String) = data(pid).getBoolean("expired")
    fun setExpired(pid: String, on: Boolean) = if (on) data(pid).putBoolean("expired", true) else data(pid).remove("expired")

    // ---- sync state and queue ----

    fun state(pid: String): TraktState = data(pid).get("state", TraktState.serializer()) ?: TraktState()
    fun setState(pid: String, s: TraktState?) = if (s == null) data(pid).remove("state") else data(pid).put("state", TraktState.serializer(), s)

    fun queue(pid: String): List<TraktOp> = data(pid).get("queue", opsSer) ?: emptyList()
    fun setQueue(pid: String, q: List<TraktOp>?) = if (q.isNullOrEmpty()) data(pid).remove("queue") else data(pid).put("queue", opsSer, q)

    /** Forgets [pid]'s sign-in, account and sync state; returns the tokens it had (to revoke). */
    fun wipe(pid: String): TraktTokens? = synchronized(store) {
        val t = tokens(pid)
        secrets(pid).remove(TOKENS)
        data(pid).edit { remove("user"); remove("state"); remove("queue"); remove("expired") }
        t
    }
}

/** One Trakt answer. */
internal class Reply(val code: Int, val body: String, val retryAfterS: Int) {
    val ok get() = code in 200..299
    /** Worth trying again later (network trouble, rate limit, Trakt down). */
    val transient get() = code == 408 || code == 429 || code >= 500
    fun obj(): JsonObject? = try { parseJson(body) as? JsonObject } catch (e: Exception) { null }
    fun arr(): JsonArray? = try { parseJson(body) as? JsonArray } catch (e: Exception) { null }
}

internal object TraktHttp {
    const val API = "https://api.trakt.tv"
    /** Sign-in, renewal and sign-out: Trakt answers these only on its auth host (an approved code polled on the API host comes back 418). */
    const val AUTH = "https://auth.trakt.tv"
    /** Redirect URIs a Trakt app may carry: new development apps must use localhost, older ones have the oob one. */
    const val REDIRECT = "http://localhost"
    val REDIRECTS = listOf(REDIRECT, "urn:ietf:wg:oauth:2.0:oob")
    private const val TIMEOUT_MS = 20_000L

    /** [o] plus the client id (Dream OS apps have no Client Secret: the device sign-in works without one). */
    fun client(clientId: String, o: Map<String, Any>): JsonObject = buildJsonObject {
        for ((k, v) in o) when (v) {
            is String -> put(k, v)
            is Number -> put(k, v)
            is Boolean -> put(k, v)
            is JsonElement -> put(k, v)
        }
        put("client_id", clientId)
    }

    /** Throws IOException when Trakt can't be reached (offline, timeout). Blocking. */
    fun send(clientId: String, method: String, path: String, body: JsonObject?, token: String? = null): Reply {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json"
        headers["Accept"] = "application/json"
        headers["trakt-api-version"] = "2"
        headers["trakt-api-key"] = clientId
        if (token != null) headers["Authorization"] = "Bearer $token"
        val url = (if (path.startsWith("/oauth/")) AUTH else API) + path
        val r = try {
            Net.request(method, url, body?.toString(), headers, TIMEOUT_MS)
        } catch (e: IOException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted")
        } catch (e: Exception) {
            throw IOException(e.javaClass.simpleName)
        }
        return Reply(r.code, r.body, r.header("Retry-After")?.toIntOrNull() ?: 0)
    }
}

/** A single daemon thread that runs jobs in order and goes away when idle (fire-home's executors). */
internal fun serialExecutor(name: String) = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
    Thread(r, name).apply { isDaemon = true }
}.apply { allowCoreThreadTimeOut(true) }

internal object TraktTime {
    fun ms(s: String?): Long = try {
        if (s.isNullOrEmpty() || s == "null") 0L else Instant.parse(s).toEpochMilli()
    } catch (e: Exception) { 0L }

    fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()
}
