package com.firehome.launcher

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The age-rating filter for a profile with a maximum rating ([KidsProfiles.level] below Everyone):
 * every title list from TMDB ([Tmdb.list], [Tmdb.discover], person search, More Like This) keeps only
 * what [KidsProfiles.allows] at that level, using the US certification (movies: release_dates,
 * theatrical first; shows: content_ratings). Unknown or unrated titles are left out.
 *
 * Certifications are cached in memory and in a small file (30 days; "" = TMDB has no US rating).
 * Missing ones are fetched in parallel (a small pool) off the main thread; on the main thread only
 * cached answers count. A profile without a limit costs nothing: every call returns its input.
 */
object KidsFilter {
    private const val TAG = "FireHome"
    private const val TTL = 30L * 86_400_000L
    private const val WAIT_MS = 8_000L
    private const val NONE = -1

    // ---- is a limit on? (re-read whenever the profiles store changes) ----

    @Volatile private var cachedLevel: Int? = null
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** The current profile's limit (index into [KidsProfiles.LEVELS]), or -1 when it has none. */
    fun level(ctx: Context): Int {
        cachedLevel?.let { return it }
        val app = ctx.applicationContext
        synchronized(this) {
            if (listener == null) {
                val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> cachedLevel = null }
                listener = l
                app.getSharedPreferences("profiles", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(l)
            }
        }
        val l = try { KidsProfiles.level(app) } catch (e: Exception) { KidsProfiles.LEVELS.size - 1 }
        val v = if (l < KidsProfiles.LEVELS.size - 1) l else NONE
        cachedLevel = v
        return v
    }

    /** True when the current profile has a maximum rating (a kids profile, or a limit set by hand). */
    fun active(ctx: Context): Boolean = level(ctx) != NONE

    // ---- the certification cache ----

    private class Entry(val cert: String, val at: Long)
    private val certs = ConcurrentHashMap<String, Entry>()
    @Volatile private var loaded = false
    @Volatile private var saveQueued = false
    private val inFlight = ConcurrentHashMap<String, Future<*>>()
    private val main = Handler(Looper.getMainLooper())
    private val pool = ThreadPoolExecutor(6, 6, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "kids-cert").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }.apply { allowCoreThreadTimeOut(true) }

    private fun key(id: Int, tv: Boolean) = (if (tv) "t" else "m") + id
    private fun key(t: Title) = key(t.id, t.type == "tv")
    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "kids_certs.json")

    private fun load(ctx: Context) {
        if (loaded) return
        synchronized(certs) {
            if (loaded) return
            try {
                val f = file(ctx)
                if (f.exists()) {
                    val o = JSONObject(f.readText())
                    val now = System.currentTimeMillis()
                    for (k in o.keys()) {
                        val a = o.optJSONArray(k) ?: continue
                        val at = a.optLong(1)
                        if (now - at < TTL) certs.putIfAbsent(k, Entry(a.optString(0), at))
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "Kids: couldn't read the rating cache: $e") }
            loaded = true
        }
    }

    private fun save(ctx: Context) {
        if (saveQueued) return
        saveQueued = true
        val app = ctx.applicationContext
        main.postDelayed({
            Net.run {
                saveQueued = false
                try {
                    val o = JSONObject()
                    for ((k, e) in certs) o.put(k, JSONArray().put(e.cert).put(e.at))
                    val f = file(app)
                    val tmp = File(f.path + ".tmp")
                    tmp.writeText(o.toString())
                    tmp.renameTo(f)
                } catch (e: Exception) { Log.w(TAG, "Kids: couldn't save the rating cache: $e") }
            }
        }, 3000)
    }

    /** The cached US certification ("" = none), or null when it isn't known yet. */
    private fun cached(id: Int, tv: Boolean): String? {
        val e = certs[key(id, tv)] ?: return null
        return if (System.currentTimeMillis() - e.at < TTL) e.cert else null
    }

    /** Keeps [cert] (US, "" when none) for [t]; [Tmdb.certification] feeds it as title pages load. */
    fun remember(ctx: Context, t: Title, cert: String) {
        load(ctx)
        certs[key(t)] = Entry(cert.trim(), System.currentTimeMillis())
        save(ctx)
    }

    /** One title's US certification from TMDB; null when TMDB couldn't be reached. */
    private fun fetch(ctx: Context, id: Int, tv: Boolean): String? {
        val path = if (tv) "tv/$id/content_ratings" else "movie/$id/release_dates"
        val json = try {
            Net.getJson("https://api.themoviedb.org/3/$path?api_key=${Tmdb.key(ctx)}")
        } catch (e: Exception) {
            // A title TMDB no longer has is simply unrated; anything else is tried again next time.
            return if (e.message?.contains("HTTP 404") == true) "" else null
        }
        val results = json.optJSONArray("results") ?: return ""
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            if (o.optString("iso_3166_1") != "US") continue
            if (tv) return o.optString("rating").trim()
            val dates = o.optJSONArray("release_dates") ?: return ""
            return (0 until dates.length()).mapNotNull { dates.optJSONObject(it) }
                .sortedBy { when (it.optInt("type")) { 3 -> 0; 4 -> 1; 2 -> 2; 5 -> 3; else -> 4 } }
                .map { it.optString("certification").trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        }
        return ""
    }

    /** Starts fetching [t]'s certification (one request per title at a time). */
    private fun submit(ctx: Context, t: Title): Future<*> {
        val k = key(t)
        inFlight[k]?.let { return it }
        val app = ctx.applicationContext
        val tv = t.type == "tv"
        val f = java.util.concurrent.FutureTask<Unit> {
            try {
                val c = fetch(app, t.id, tv)
                if (c != null) { certs[k] = Entry(c, System.currentTimeMillis()); save(app) }
            } finally { inFlight.remove(k) }
        }
        // Registered before it runs, so a quick finish can't leave a stale entry behind.
        inFlight.putIfAbsent(k, f)?.let { return it }
        pool.execute(f)
        return f
    }

    // ---- filtering ----

    /** Whether [t] may show on this profile, from cached ratings only (no network). */
    fun allowed(ctx: Context, t: Title): Boolean = allowed(ctx, t.id, t.type == "tv")

    /** Whether TMDB title [tmdbId] may show on this profile, from cached ratings only (no network). */
    fun allowed(ctx: Context, tmdbId: Int, isTv: Boolean): Boolean {
        val lvl = level(ctx)
        if (lvl == NONE) return true
        load(ctx)
        val c = cached(tmdbId, isTv) ?: return false
        return KidsProfiles.allows(lvl, c, isTv)
    }

    /**
     * [list] without what this profile's rating limit leaves out. Off the main thread, missing ratings are
     * fetched first (up to ~8 s; anything still unknown is left out this time and cached for the next).
     * [trusted]: TMDB already applied the limit (a movie /discover with [discoverClause]); only adult titles go.
     */
    fun titles(ctx: Context, list: List<Title>, trusted: Boolean = false): List<Title> {
        val lvl = level(ctx)
        if (lvl == NONE || list.isEmpty()) return list
        if (trusted) return list.filterNot { it.adult }
        load(ctx)
        val missing = list.filter { cached(it.id, it.type == "tv") == null }.distinctBy { it.key }
        if (missing.isNotEmpty()) {
            val futures = missing.map { submit(ctx, it) }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                val until = System.currentTimeMillis() + WAIT_MS
                for (f in futures) try { f.get(maxOf(1L, until - System.currentTimeMillis()), TimeUnit.MILLISECONDS) } catch (e: Exception) {}
            }
        }
        val out = list.filter { t ->
            !t.adult && cached(t.id, t.type == "tv")?.let { KidsProfiles.allows(lvl, it, t.type == "tv") } == true
        }
        if (out.size < list.size) Log.i(TAG, "Kids: ${KidsProfiles.LEVELS[lvl].name} limit kept ${out.size} of ${list.size}")
        return out
    }

    /**
     * Extra /discover parameters that make TMDB apply the limit itself, so filtered rows stay full
     * ("" without a limit). Movies are then trusted ([titles]); shows are still checked one by one.
     */
    fun discoverClause(ctx: Context, type: String): String {
        val lvl = level(ctx)
        if (lvl == NONE) return ""
        val l = KidsProfiles.LEVELS[lvl]
        val allowed = if (type == "tv") l.tv else MOVIE_ORDER.take(MOVIE_ORDER.indexOf(l.movie) + 1)
        return "&include_adult=false&certification_country=US&certification=" + allowed.joinToString("|")
    }

    private val MOVIE_ORDER = listOf("G", "PG", "PG-13", "R", "NC-17")

    /**
     * Before a title page opens: false = go ahead (no limit, or [t] is allowed). True = handled here:
     * either [t] is over the limit (a short "Not Available on This Profile" note), or its rating is
     * being looked up, after which [reopen] runs on the main thread if it's allowed.
     */
    fun gate(ctx: Context, t: Title, reopen: () -> Unit): Boolean {
        val lvl = level(ctx)
        if (lvl == NONE) return false
        load(ctx)
        val tv = t.type == "tv"
        val c = cached(t.id, tv)
        if (c != null) {
            if (KidsProfiles.allows(lvl, c, tv)) return false
            blocked(ctx, t, c)
            return true
        }
        val app = ctx.applicationContext
        Net.run {
            try { submit(app, t).get(WAIT_MS, TimeUnit.MILLISECONDS) } catch (e: Exception) {}
            main.post {
                val now = cached(t.id, tv)
                if (now != null && KidsProfiles.allows(level(app), now, tv)) reopen() else blocked(ctx, t, now)
            }
        }
        return true
    }

    private fun blocked(ctx: Context, t: Title, cert: String?) {
        Log.i(TAG, "Kids: ${t.name} not opened (${cert?.ifEmpty { "unrated" } ?: "rating unknown"})")
        try { Toast.makeText(ctx, "Not Available on This Profile", Toast.LENGTH_SHORT).show() } catch (e: Exception) {}
    }
}
