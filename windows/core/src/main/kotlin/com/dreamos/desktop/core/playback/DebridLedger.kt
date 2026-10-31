package com.dreamos.desktop.core.playback

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Locale

/** Picking the right file inside a torrent (packs hold a whole season or series): fire-home DebridFiles. */
object DebridFiles {
    private val VIDEO = Regex("\\.(mkv|mp4|m4v|webm|avi|mov|ts)$", RegexOption.IGNORE_CASE)
    private val JUNK = Regex("(?i)(^|[^a-z])(sample|trailer|featurette|extras?)([^a-z]|$)")

    fun isVideo(name: String) = VIDEO.containsMatchIn(name)
    fun base(path: String) = path.substringAfterLast('/').substringAfterLast('\\')

    /** A bare-hash magnet; the name helps the service label the item if it ever shows in the account. */
    fun magnet(s: Source) = "magnet:?xt=urn:btih:${s.hash}" + if (s.torrentName.isNotBlank()) "&dn=" + DebridHttp.enc(s.torrentName) else ""

    /** S02E01 / 2x01 / s2e1 match for [season]/[episode] in a file name, built once per pick. */
    private fun episodeMatcher(season: Int, episode: Int): (String) -> Boolean {
        val sxe = Regex("(?i)s0*$season[ ._-]*e0*$episode(?![0-9])")
        val nxn = Regex("(?i)(?<![0-9])${season}x0*$episode(?![0-9])")
        return { name -> base(name).let { sxe.containsMatchIn(it) || nxn.containsMatchIn(it) } }
    }

    /**
     * Among [files] (path or name, size in bytes), the one to play for [s]: the scraper's own file name when it's there,
     * else for an episode the largest video file named for it, else the largest video file that isn't a sample. -1 none.
     */
    fun pick(files: List<Pair<String, Long>>, s: Source): Int {
        s.fileName?.let { want -> files.indexOfFirst { base(it.first).equals(base(want), true) }.let { if (it >= 0) return it } }
        val videos = files.withIndex().filter { isVideo(it.value.first) && !JUNK.containsMatchIn(base(it.value.first)) }
        val se = s.season; val ep = s.episode
        val pool = if (se != null && ep != null) episodeMatcher(se, ep).let { m -> videos.filter { m(it.value.first) } } else videos
        return pool.maxByOrNull { it.value.second }?.index ?: -1
    }
}

/**
 * What Dream OS added to the viewer's debrid accounts, kept across restarts (fire-home / iPhone DebridLedger). Every
 * info hash is recorded here BEFORE it is uploaded, so whatever an account holds that isn't in the ledger is the
 * viewer's own and is never touched; a hash the viewer already had ([users]) is never claimed. Also kept: what each
 * service said about a hash's cache state (ready ~7 days, not ready ~6 h; at most 1500 per service), a rolling 24 h
 * upload count ([allowance]) and a service's "account limit reached" state. Scopes are "<service>:<token fingerprint>";
 * tokens themselves are never stored or logged here.
 */
class DebridLedger(private val store: KeyValueStore, private val clock: Clock) {
    @Serializable data class Own(val i: String? = null, val t: Long = 0, val p: Boolean = false)
    @Serializable data class Users(val h: List<String> = emptyList(), val at: Long = 0)
    @Serializable data class Seen(val r: Boolean, val t: Long)
    @Serializable data class Upload(val at: Long, val n: Int, val play: Boolean)

    companion object {
        const val READY_TTL = 7 * 24 * 3_600_000L
        const val NOT_READY_TTL = 6 * 3_600_000L
        const val DAY = 24 * 3_600_000L
        /** Uploads in 24 h above which only real Plays upload, and the hard stop for everything but Play. */
        const val SOFT_DAY = 300
        const val HARD_DAY = 600
        private const val MAX_SEEN = 1500

        fun scope(service: String, token: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            return service + ":" + d.take(6).joinToString("") { "%02x".format(Locale.US, it) }
        }

        private val OWN = MapSerializer(String.serializer(), Own.serializer())
        private val SEEN = MapSerializer(String.serializer(), Seen.serializer())
        private val UPS = ListSerializer(Upload.serializer())
    }

    /** Info hashes of links handed out recently (maybe playing): cleanup never removes these. */
    @Volatile var inUse: () -> Set<String> = { emptySet() }

    private val mem = HashMap<String, Any>()
    private fun <T : Any> load(key: String, ser: KSerializer<T>, def: () -> T): T {
        @Suppress("UNCHECKED_CAST")
        return mem.getOrPut(key) { try { store.get("ledger.$key")?.let { Json.decodeFromString(ser, it) } } catch (e: Exception) { null } ?: def() } as T
    }
    private fun <T : Any> save(key: String, ser: KSerializer<T>, v: T) { mem[key] = v; store.put("ledger.$key", Json.encodeToString(ser, v)) }

    private fun now() = clock.wallMs()

    // ---- ownership ----

    @Synchronized fun owned(scope: String): Map<String, Own> = load("own.$scope", OWN) { emptyMap() }

    /** Before an upload: [hashes] are Dream OS's from now on (the viewer's own are skipped). Returns the ones claimed. */
    @Synchronized fun claim(scope: String, hashes: Collection<String>): Set<String> {
        val users = users(scope)
        val o = owned(scope).toMutableMap()
        val out = HashSet<String>()
        for (h in hashes) {
            if (h in users) continue
            out += h
            if (h !in o) o[h] = Own(t = now()) // an existing entry (kept, played) keeps its age
        }
        save("own.$scope", OWN, o)
        return out
    }

    @Synchronized fun setId(scope: String, hash: String, id: String) {
        val o = owned(scope).toMutableMap()
        o[hash] = (o[hash] ?: Own(t = now())).copy(i = id)
        save("own.$scope", OWN, o)
    }

    @Synchronized fun markPlayed(scope: String, hash: String) {
        val o = owned(scope).toMutableMap()
        val e = o[hash] ?: return
        o[hash] = e.copy(p = true, t = now())
        save("own.$scope", OWN, o)
    }

    /** [hashes] are gone from the account (deleted, or no longer there), or never got there. */
    @Synchronized fun release(scope: String, hashes: Collection<String>) {
        if (hashes.isEmpty()) return
        save("own.$scope", OWN, owned(scope) - hashes.toSet())
    }

    // ---- the viewer's own items ----

    @Synchronized fun usersKnown(scope: String) = load("user.$scope", Users.serializer()) { Users() }.at > 0
    @Synchronized fun users(scope: String): Set<String> = load("user.$scope", Users.serializer()) { Users() }.h.toSet()
    /** From an account listing: everything in it that Dream OS never claimed. */
    @Synchronized fun setUsers(scope: String, hashes: Set<String>) = save("user.$scope", Users.serializer(), Users(hashes.toList(), now()))

    // ---- cache state per hash ----

    /** True: seen ready within ~7 days; false: seen not cached within ~6 h; null: not known. */
    @Synchronized fun cached(service: String, hash: String): Boolean? {
        val e = load("cache.$service", SEEN) { emptyMap() }[hash] ?: return null
        val age = now() - e.t
        return if (age < (if (e.r) READY_TTL else NOT_READY_TTL)) e.r else null
    }

    @Synchronized fun seen(service: String, results: Map<String, Boolean>) {
        if (results.isEmpty()) return
        val t = now()
        var o = load("cache.$service", SEEN) { emptyMap() }.toMutableMap()
        for ((h, r) in results) o[h] = Seen(r, t)
        if (o.size > MAX_SEEN) {
            o.entries.removeAll { t - it.value.t > (if (it.value.r) READY_TTL else NOT_READY_TTL) }
            if (o.size > MAX_SEEN) o = o.entries.sortedByDescending { it.value.t }.take(1200).associate { it.key to it.value }.toMutableMap()
        }
        save("cache.$service", SEEN, o)
    }

    // ---- the daily upload budget ----

    @Volatile private var budgetLogged = 0

    /** [n] hashes sent to [service]; [play]: for a real Play. */
    @Synchronized fun counted(service: String, n: Int, play: Boolean) {
        if (n <= 0) return
        val t = now()
        save("up.$service", UPS, load("up.$service", UPS) { emptyList() }.filter { t - it.at < DAY } + Upload(t, n, play))
    }

    /** (all, not for a Play) hashes sent to [service] in the last 24 h. */
    @Synchronized fun uploads(service: String): Pair<Int, Int> {
        val t = now()
        val live = load("up.$service", UPS) { emptyList() }.filter { t - it.at < DAY }
        return live.sumOf { it.n } to live.filter { !it.play }.sumOf { it.n }
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
            PlaybackLog.w("Dream Core: $all uploads to ${service.uppercase()} in 24 h ($other ahead of a Play); " +
                if (level == 2) "only real Plays upload now" else "Instant Play and next-episode lookups paused; Play still works")
        }
        return 0
    }

    // ---- account limit ----

    @Synchronized fun limitedUntil(scope: String): Long = store.get("ledger.limit.$scope")?.toLongOrNull() ?: 0
    @Synchronized fun setLimited(scope: String, until: Long) = store.put("ledger.limit.$scope", until.toString())
}
