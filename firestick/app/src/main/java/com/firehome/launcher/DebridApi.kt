package com.firehome.launcher

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The seams of Dream OS's own resolver ([NativeResolver]): what a torrent index found ([Candidate]),
 * what a debrid service turns one into ([Found]), the services themselves ([DebridService]), the
 * debrid accounts the user linked ([DebridAccounts], kept by Dream OS itself)
 * and a small JSON-over-HTTPS helper ([DebridHttp]). Tokens and stream URLs are never logged in full.
 */

/** One torrent the index offers for the request, best first in the list it comes in. */
class Candidate(
    val hash: String,               // info hash, lowercase hex
    val torrentName: String,        // the torrent's own name
    val fileName: String?,          // the file inside it the index matched (packs), when known
    val fileIdx: Int?,              // that file's index in the torrent, when known
    val sizeBytes: Long,            // the file's size (0 = unknown)
    val quality: String,            // "4K" | "1080p" | "720p" | "SD"
    val seeders: Int,
    val season: Int?,               // what was asked for (episodes)
    val episode: Int?,
)

/** A playable direct HTTPS link to the video file. */
class Found(
    url: String,
    val service: String,            // "ad" | "pm" | "tb" | "rd"
    val fileName: String,
    val sizeBytes: Long,
    val quality: String,
    val hash: String,
) {
    /** All four services serve their files over HTTPS too: a plain-HTTP link is upgraded (cleartext is off app-wide). */
    val url: String = if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    // The player's stream badges read the release name and size back by URL (whichever path opens it).
    init { StreamBadges.note(this.url, fileName, sizeBytes) }
}

/** A debrid service the resolver can ask. */
internal interface DebridService {
    /** "ad" | "pm" | "tb" | "rd". */
    val key: String

    /**
     * Blocking. The first of [candidates] (best first) the service has cached, unlocked to a direct
     * link, or null. Only cached torrents count (nothing is left downloading); anything added to the
     * user's account and not used is removed again. Gives up at [deadline] (SystemClock.elapsedRealtime).
     */
    fun resolve(candidates: List<Candidate>, deadline: Long): Found?
}

/** The debrid accounts the user linked, held by Dream OS ([DreamAccounts], under the provider's setting ids). Cached for a minute. */
internal object DebridAccounts {

    class Accounts(val raw: Map<String, String>) {
        private fun on(service: String, token: String) = raw[token].orEmpty().isNotBlank() && raw["$service.enable"] != "false"
        val allDebrid get() = raw["alldebridtoken"].orEmpty().takeIf { on("alldebrid", "alldebridtoken") }
        val premiumize get() = raw["premiumizetoken"].orEmpty().takeIf { on("premiumize", "premiumizetoken") }
        val torBox get() = raw["torboxtoken"].orEmpty().takeIf { on("torbox", "torboxtoken") }
        val realDebrid get() = raw["realdebridtoken"].orEmpty().takeIf { on("realdebrid", "realdebridtoken") }
        val realDebridRefresh get() = raw["realdebridrefresh"].orEmpty()
        val realDebridClientId get() = raw["realdebrid.clientid"].orEmpty()
        val realDebridSecret get() = raw["realdebridsecret"].orEmpty()
        val any get() = allDebrid != null || premiumize != null || torBox != null || realDebrid != null
    }

    @Volatile private var cached: Accounts? = null
    @Volatile private var cachedAt = 0L

    /**
     * Dream OS's own accounts ([DreamAccounts]; the provider's saved ones until the one-time copy has
     * landed). Cached for a minute, dropped at once on a link / unlink. Null when nothing can be read.
     */
    fun get(ctx: Context): Accounts? {
        val now = SystemClock.elapsedRealtime()
        cached?.let { if (now - cachedAt < 60_000) return it }
        val raw = DreamAccounts.values(ctx.applicationContext) ?: return cached
        return Accounts(raw).also { cached = it; cachedAt = now }
    }

    /** Forget the cached read (an account was just linked or unlinked). */
    fun invalidate() { cachedAt = 0; cached = null }
}

/** JSON over HTTPS for the index and the debrid services: short timeouts, kept-alive connections. */
internal object DebridHttp {
    const val UA = "Dream OS"
    /** Marks a stored Premiumize credential that is the account's API key, not an OAuth token (DebridLink). */
    const val API_KEY_PREFIX = "apikey:"

    class Reply(val code: Int, val body: String) {
        val ok get() = code in 200..299
        fun json(): JSONObject = if (body.isBlank()) JSONObject() else JSONObject(body)
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    fun form(fields: List<Pair<String, String>>) = fields.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }

    /** [timeoutMs] bounds connect and read each; [bearer] adds "Authorization: Bearer". Never throws for an HTTP status. */
    fun request(
        url: String, method: String = "GET", bearer: String? = null, body: ByteArray? = null,
        contentType: String? = null, timeoutMs: Int = 6_000, headers: Map<String, String> = emptyMap(),
    ): Reply {
        // A Premiumize account linked with its API key goes as the apikey parameter, not as a Bearer token.
        val apiKey = bearer?.takeIf { it.startsWith(API_KEY_PREFIX) }?.removePrefix(API_KEY_PREFIX)
        val target = if (apiKey != null) url + (if ('?' in url) "&" else "?") + "apikey=" + enc(apiKey) else url
        val c = (URL(target).openConnection() as HttpURLConnection)
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.useCaches = false
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json")
            if (bearer != null && apiKey == null) c.setRequestProperty("Authorization", "Bearer $bearer")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                if (contentType != null) c.setRequestProperty("Content-Type", contentType)
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val s = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Reply(code, s)
        } finally {
            // No disconnect(): it would close the kept-alive socket the next call reuses.
        }
    }

    fun get(url: String, bearer: String? = null, timeoutMs: Int = 6_000) = request(url, bearer = bearer, timeoutMs = timeoutMs)

    fun postForm(url: String, fields: List<Pair<String, String>>, bearer: String? = null, timeoutMs: Int = 6_000) =
        request(url, "POST", bearer, form(fields).toByteArray(), "application/x-www-form-urlencoded", timeoutMs)

    /** Timeout left until [deadline], at least 1 s, at most [cap]. */
    fun left(deadline: Long, cap: Int = 6_000): Int = (deadline - SystemClock.elapsedRealtime()).coerceIn(1_000L, cap.toLong()).toInt()
}

/** Picking the right file inside a torrent (packs hold a whole season or series). */
internal object DebridFiles {
    private val VIDEO = Regex("\\.(mkv|mp4|m4v|webm|avi|mov|ts)$", RegexOption.IGNORE_CASE)
    private val JUNK = Regex("(?i)(^|[^a-z])(sample|trailer|featurette|extras?)([^a-z]|$)")

    fun isVideo(name: String) = VIDEO.containsMatchIn(name)
    fun base(path: String) = path.substringAfterLast('/').substringAfterLast('\\')

    /** S02E01 / 2x01 / s2e1 style match for [season]/[episode] in [name]. */
    fun isEpisode(name: String, season: Int, episode: Int): Boolean {
        val n = base(name)
        return Regex("(?i)s0*$season[ ._-]*e0*$episode(?![0-9])").containsMatchIn(n) ||
            Regex("(?i)(?<![0-9])$season[x]0*$episode(?![0-9])").containsMatchIn(n)
    }

    /**
     * Among [files] (path or name, size in bytes), the one to play for [c]: the index's own file name
     * when it's there, else for an episode the largest video file named for it, else (a movie) the
     * largest video file that isn't a sample. -1 when none fits.
     */
    fun pick(files: List<Pair<String, Long>>, c: Candidate): Int {
        val want = c.fileName?.let { base(it) }
        if (want != null) files.indexOfFirst { base(it.first).equals(want, true) }.let { if (it >= 0) return it }
        val videos = files.withIndex().filter { isVideo(it.value.first) && !JUNK.containsMatchIn(base(it.value.first)) }
        val s = c.season; val e = c.episode
        val pool = if (s != null && e != null) videos.filter { isEpisode(it.value.first, s, e) } else videos
        return pool.maxByOrNull { it.value.second }?.index ?: -1
    }
}

/**
 * Why a lookup runs, which sets how many torrents it may add to the user's debrid account
 * ([DebridLedger.allowance]). Browsing and opening a title add nothing (the index is ranked ahead,
 * the debrid step waits for Play; only services whose cache check is free, Premiumize, run early);
 * the next episode and the source list's marks a few; a real Play is never held back.
 */
enum class DebridPurpose(val maxUploads: Int) {
    /** Focus resting on a card: the index only, nothing sent to any service. */
    BROWSE(0),
    /** The details page's Instant Play pre-resolve: index and ranking; only free cache checks (Premiumize, TorBox). */
    PAGE(0),
    /** The next episode, while one plays. */
    AHEAD(2),
    /** The source list's "Ready" marks (its top rows only). */
    LIST(5),
    /** A real Play (or a source the user picked): never rate-limited. */
    PLAY(10),
}

/**
 * What Dream OS added to the user's debrid accounts, kept across restarts (app-private prefs through
 * [store]; an in-memory map until the app installs one). Every info hash is recorded here BEFORE it
 * is uploaded, so whatever an account holds that isn't in the ledger is the user's own and is never
 * touched; a hash the user already had ([users]) is never claimed, even when a lookup sends it again
 * (AllDebrid answers with the user's own magnet id). Also kept here: what each service said about a
 * hash's cache state ([cached]: ready ~7 days, not ready ~6 h), a rolling 24 h upload count
 * ([allowance]) and a service's "account limit reached" state ([limitedUntil]).
 * Scopes are "<service>:<token fingerprint>"; tokens themselves are never stored or logged here.
 */
internal object DebridLedger {
    interface Store { fun get(key: String): String?; fun put(key: String, value: String) }

    @Volatile var store: Store = object : Store {
        private val m = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String) { m[key] = value }
    }

    /** Info hashes of links handed out recently (maybe playing right now): cleanup never removes these. */
    @Volatile var inUse: () -> Set<String> = { emptySet() }

    const val READY_TTL = 7 * 24 * 3_600_000L
    const val NOT_READY_TTL = 6 * 3_600_000L
    const val DAY = 24 * 3_600_000L
    /** Uploads in 24 h above which only real Plays upload, and the hard stop for everything but Play. */
    const val SOFT_DAY = 300
    const val HARD_DAY = 600

    class Own(val id: String?, val at: Long, val played: Boolean)

    fun wall() = System.currentTimeMillis()
    fun scope(service: String, token: String) = service + ":" + Integer.toHexString(token.hashCode())

    private val mem = HashMap<String, JSONObject>()

    private fun obj(key: String): JSONObject = mem.getOrPut(key) {
        try { store.get(key)?.let { JSONObject(it) } } catch (e: Exception) { null } ?: JSONObject()
    }
    private fun save(key: String) { try { store.put(key, obj(key).toString()) } catch (e: Exception) {} }

    /** Forget everything held in memory (the harness, between scenarios). */
    @Synchronized fun resetForTests() { mem.clear(); budgetLogged = 0 }

    // ---- ownership ----

    @Synchronized fun owned(scope: String): Map<String, Own> {
        val o = obj("own.$scope")
        return o.keys().asSequence().toList().associateWith { h ->
            val e = o.optJSONObject(h)
            Own(e?.optString("i")?.ifEmpty { null }, e?.optLong("t") ?: 0L, e?.optBoolean("p") == true)
        }
    }

    /** Before an upload: [hashes] are Dream OS's from now on (the user's own are skipped). Returns the ones claimed. */
    @Synchronized fun claim(scope: String, hashes: Collection<String>): Set<String> {
        val users = users(scope)
        val o = obj("own.$scope")
        val now = wall()
        val out = HashSet<String>()
        for (h in hashes) {
            if (h in users) continue
            out += h
            if (o.optJSONObject(h) == null) o.put(h, JSONObject().put("t", now)) // an existing entry (kept, played) keeps its age
        }
        save("own.$scope")
        return out
    }

    @Synchronized fun setId(scope: String, hash: String, id: String) {
        val o = obj("own.$scope")
        (o.optJSONObject(hash) ?: JSONObject().put("t", wall()).also { o.put(hash, it) }).put("i", id)
        save("own.$scope")
    }

    @Synchronized fun markPlayed(scope: String, hash: String) {
        obj("own.$scope").optJSONObject(hash)?.put("p", true)?.put("t", wall()) ?: return
        save("own.$scope")
    }

    /** [hashes] are gone from the account (deleted, or no longer there), or never got there. */
    @Synchronized fun release(scope: String, hashes: Collection<String>) {
        if (hashes.isEmpty()) return
        val o = obj("own.$scope")
        hashes.forEach { o.remove(it) }
        save("own.$scope")
    }

    // ---- the user's own items ----

    @Synchronized fun usersKnown(scope: String) = obj("user.$scope").has("at")
    @Synchronized fun users(scope: String): Set<String> {
        val a = obj("user.$scope").optJSONArray("h") ?: return emptySet()
        return (0 until a.length()).map { a.optString(it) }.toSet()
    }
    /** From an account listing: everything in it that Dream OS never claimed. */
    @Synchronized fun setUsers(scope: String, hashes: Set<String>) {
        obj("user.$scope").put("h", JSONArray(hashes.toList())).put("at", wall())
        save("user.$scope")
    }

    // ---- cache state per hash ----

    /** True: seen ready within ~7 days; false: seen not cached within ~6 h; null: not known. */
    @Synchronized fun cached(service: String, hash: String): Boolean? {
        val e = obj("cache.$service").optJSONObject(hash) ?: return null
        val age = wall() - e.optLong("t")
        val ready = e.optBoolean("r")
        return if (age < (if (ready) READY_TTL else NOT_READY_TTL)) ready else null
    }

    @Synchronized fun seen(service: String, results: Map<String, Boolean>) {
        if (results.isEmpty()) return
        val o = obj("cache.$service")
        val now = wall()
        for ((h, r) in results) o.put(h, JSONObject().put("r", r).put("t", now))
        if (o.length() > 1500) {
            val keys = o.keys().asSequence().toList()
            keys.filter { k -> o.optJSONObject(k)?.let { now - it.optLong("t") > (if (it.optBoolean("r")) READY_TTL else NOT_READY_TTL) } != false }
                .forEach { o.remove(it) }
            if (o.length() > 1500) o.keys().asSequence().toList().sortedBy { o.optJSONObject(it)?.optLong("t") ?: 0 }
                .take(o.length() - 1200).forEach { o.remove(it) }
        }
        save("cache.$service")
    }

    // ---- the daily upload budget ----

    @Volatile private var budgetLogged = 0

    /** [n] hashes sent to [service]; [play]: for a real Play. */
    @Synchronized fun counted(service: String, n: Int, play: Boolean) {
        if (n <= 0) return
        val o = obj("up.$service")
        val a = o.optJSONArray("u") ?: JSONArray()
        val now = wall()
        val keep = JSONArray()
        for (i in 0 until a.length()) a.optJSONArray(i)?.let { if (now - it.optLong(0) < DAY) keep.put(it) }
        keep.put(JSONArray().put(now).put(n).put(if (play) 1 else 0))
        o.put("u", keep)
        save("up.$service")
    }

    /** (all, not for a Play) hashes sent to [service] in the last 24 h. */
    @Synchronized fun uploads(service: String): Pair<Int, Int> {
        val a = obj("up.$service").optJSONArray("u") ?: return 0 to 0
        val now = wall()
        var all = 0; var other = 0
        for (i in 0 until a.length()) {
            val e = a.optJSONArray(i) ?: continue
            if (now - e.optLong(0) >= DAY) continue
            all += e.optInt(1); if (e.optInt(2) == 0) other += e.optInt(1)
        }
        return all to other
    }

    /** How many hashes a lookup for [purpose] may send to [service] (the 24 h budget applied). */
    fun allowance(service: String, purpose: DebridPurpose): Int {
        if (purpose == DebridPurpose.PLAY || purpose.maxUploads == 0) return purpose.maxUploads
        val (all, other) = uploads(service)
        val stop = if (purpose == DebridPurpose.LIST) other >= HARD_DAY else all >= SOFT_DAY || other >= HARD_DAY
        if (!stop) { if (all < SOFT_DAY) budgetLogged = 0; return purpose.maxUploads }
        val level = if (other >= HARD_DAY) 2 else 1
        if (budgetLogged < level) {
            budgetLogged = level
            android.util.Log.w("FireHome", "Dream Core: $all uploads to ${service.uppercase()} in 24 h ($other ahead of a Play); " +
                if (level == 2) "only real Plays upload now" else "Instant Play and next-episode lookups paused; Play still works")
        }
        return 0
    }

    // ---- account limit ----

    @Synchronized fun limitedUntil(scope: String): Long = obj("limit.$scope").optLong("until")
    @Synchronized fun setLimited(scope: String, until: Long) { obj("limit.$scope").put("until", until); save("limit.$scope") }
}
