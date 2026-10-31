package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.KidsLevel
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.net.HttpError
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.util.*
import kotlinx.serialization.json.*
import java.util.concurrent.*

/**
 * The age-rating filter for a profile with a maximum rating (fire-home KidsFilter): every title list from TMDB keeps
 * only what [ContentPrefs.allows] at that level, by the US certification. Unknown or unrated titles are left out.
 *
 * Certifications are cached in memory and in `kids_certs` (30 days; "" = TMDB has no US rating). Worst case: one entry
 * per title ever listed while a Kids profile was on, ~30 bytes each; trimmed to the newest 20,000 (~600 KB).
 * A profile without a limit costs nothing: every call returns its input.
 */
object KidsFilter {
    private const val TTL = 30L * 86_400_000L
    private const val WAIT_MS = 8_000L
    private const val NONE = -1
    private const val MAX_CERTS = 20_000

    /** The current profile's limit (index into [KidsLevel.ALL]), or -1 when it has none. */
    fun level(): Int {
        val l = try { ContentPrefs.kidsLevel() } catch (e: Exception) { KidsLevel.ALL.size - 1 }
        return if (l < KidsLevel.ALL.size - 1) l else NONE
    }

    /** True when the current profile has a maximum rating. */
    fun active(): Boolean = level() != NONE

    private class Entry(val cert: String, val at: Long)
    private val certs = ConcurrentHashMap<String, Entry>()
    @Volatile private var loaded = false
    @Volatile private var saveQueued = false
    private val inFlight = ConcurrentHashMap<String, Future<*>>()
    private val pool = ThreadPoolExecutor(6, 6, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "kids-cert").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
    private val saver = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "kids-save").apply { isDaemon = true } }

    private fun key(id: Int, tv: Boolean) = (if (tv) "t" else "m") + id
    private fun key(t: Title) = key(t.id, t.isShow)
    private fun file() = DreamStore.shared.file("kids_certs")

    private fun load() {
        if (loaded) return
        synchronized(certs) {
            if (loaded) return
            val now = System.currentTimeMillis()
            val f = file()
            for (k in f.keys) {
                val a = f.getJson(k) as? JsonArray ?: continue
                val at = (a.getOrNull(1) as? JsonPrimitive)?.longOrNull ?: continue
                val c = (a.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: continue
                if (now - at < TTL) certs.putIfAbsent(k, Entry(c, at))
            }
            loaded = true
        }
    }

    private fun save() {
        if (saveQueued) return
        saveQueued = true
        saver.schedule({
            saveQueued = false
            val keep = certs.entries.sortedByDescending { it.value.at }.take(MAX_CERTS)
            file().edit {
                clear()
                for ((k, e) in keep) putJson(k, JsonArray(listOf(JsonPrimitive(e.cert), JsonPrimitive(e.at))))
            }
        }, 3, TimeUnit.SECONDS)
    }

    /** The cached US certification ("" = none), or null when it isn't known yet. */
    fun cached(id: Int, tv: Boolean): String? {
        load()
        val e = certs[key(id, tv)] ?: return null
        return if (System.currentTimeMillis() - e.at < TTL) e.cert else null
    }

    /** Keeps [cert] (US, "" when none) for [t]; title pages feed it as they load. */
    fun remember(t: Title, cert: String) {
        load()
        certs[key(t)] = Entry(cert.trim(), System.currentTimeMillis())
        save()
    }

    /** One title's US certification from TMDB; null when TMDB couldn't be reached. */
    private fun fetch(id: Int, tv: Boolean): String? {
        val path = if (tv) "tv/$id/content_ratings" else "movie/$id/release_dates"
        val json = try {
            Net.getJson("${Tmdb.API}/$path?api_key=${Tmdb.key()}")
        } catch (e: HttpError) {
            return if (e.code == 404) "" else null // a title TMDB no longer has is simply unrated
        } catch (e: Exception) {
            return null
        }
        for (o in json.a("results").objects()) {
            if (o.str("iso_3166_1") != "US") continue
            if (tv) return o.str("rating").trim()
            return o.a("release_dates").objects()
                .sortedBy { when (it.int("type")) { 3 -> 0; 4 -> 1; 2 -> 2; 5 -> 3; else -> 4 } }
                .map { it.str("certification").trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        }
        return ""
    }

    private fun submit(t: Title): Future<*> {
        val k = key(t)
        inFlight[k]?.let { return it }
        val f = FutureTask {
            try {
                val c = fetch(t.id, t.isShow)
                if (c != null) { certs[k] = Entry(c, System.currentTimeMillis()); save() }
            } finally { inFlight.remove(k) }
        }
        inFlight.putIfAbsent(k, f)?.let { return it }
        pool.execute(f)
        return f
    }

    /** Whether [t] may show on this profile, from cached ratings only (no network). */
    fun allowed(t: Title): Boolean = allowed(t.id, t.isShow)

    fun allowed(tmdbId: Int, isTv: Boolean): Boolean {
        val lvl = level()
        if (lvl == NONE) return true
        val c = cached(tmdbId, isTv) ?: return false
        return ContentPrefs.allows(lvl, c, isTv)
    }

    /** Whether [t] may open on this profile, fetching its rating when unknown (up to ~8 s). Blocking. */
    fun allowedFetching(t: Title): Boolean {
        val lvl = level()
        if (lvl == NONE) return true
        if (cached(t.id, t.isShow) == null) try { submit(t).get(WAIT_MS, TimeUnit.MILLISECONDS) } catch (e: Exception) {}
        return allowed(t)
    }

    /**
     * [list] without what this profile's rating limit leaves out. Missing ratings are fetched first (up to ~8 s;
     * anything still unknown is left out this time and cached for the next). [trusted]: TMDB already applied the limit
     * (a movie /discover with [discoverClause]); only adult titles go. Blocking.
     */
    fun titles(list: List<Title>, trusted: Boolean = false): List<Title> {
        val lvl = level()
        if (lvl == NONE || list.isEmpty()) return list
        if (trusted) return list.filterNot { it.adult }
        load()
        val missing = list.filter { cached(it.id, it.isShow) == null }.distinctBy { it.key }
        if (missing.isNotEmpty()) {
            val futures = missing.map { submit(it) }
            val until = System.currentTimeMillis() + WAIT_MS
            for (f in futures) try { f.get(maxOf(1L, until - System.currentTimeMillis()), TimeUnit.MILLISECONDS) } catch (e: Exception) {}
        }
        val out = list.filter { t -> !t.adult && cached(t.id, t.isShow)?.let { ContentPrefs.allows(lvl, it, t.isShow) } == true }
        if (out.size < list.size) Log.d("Kids", "${KidsLevel.ALL[lvl].name} limit kept ${out.size} of ${list.size}")
        return out
    }

    /**
     * Extra /discover parameters that make TMDB apply the limit itself, so filtered rows stay full ("" without a
     * limit). Movies are then trusted ([titles]); shows are still checked one by one.
     */
    fun discoverClause(type: String): String {
        val lvl = level()
        if (lvl == NONE) return ""
        val allowed = if (type == MediaKind.TV.path) KidsLevel.ALL[lvl].tv else ContentPrefs.movieCerts(lvl)
        return "&include_adult=false&certification_country=US&certification=" + allowed.joinToString("|")
    }
}
