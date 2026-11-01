package com.firehome.launcher

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Exact air times. TMDB knows only an episode's date; TVmaze (free, no key) knows the instant it airs or
 * drops (`airstamp`, UTC: a broadcast at 9 PM ET, a streaming drop at 12 AM PT). A TMDB show is matched to
 * TVmaze once through its IMDb id (else its TheTVDB id) and the match is kept for good (a miss is retried
 * after a week); the show's previous and next episode come in one call (`embed`), kept [INFO_TTL].
 * Everything here that touches the network blocks: call it from a background thread. TVmaze allows ~20
 * calls per 10 s; calls here are at least [GAP_MS] apart. Nothing is shown when TVmaze has nothing: the
 * TMDB date stays the rule then. An episode TVmaze lists without an `airtime` has only a day (its airstamp
 * is a noon stand-in), so it gets no exact instant either. [episodes] gives a show's whole list (the title
 * page's upcoming episodes, [Upcoming]) and [specials] the specials beside it ([Specials]); both come out of
 * the one call, which asks for specials too (without `?specials=1` TVmaze leaves them out entirely).
 */
object AirTimes {
    private const val TAG = "FireHome"
    private const val API = "https://api.tvmaze.com"
    private const val INFO_TTL = 3 * 60 * 60_000L
    private const val MISS_RETRY_MS = 7 * 24 * 60 * 60_000L
    private const val GAP_MS = 550L
    /** Full episode lists kept in memory (a long-running show's is ~100 KB): the shows looked at most recently. */
    private const val MAX_SCHEDULES = 24

    /**
     * One episode's exact instant (epoch ms). [special]: a special, which TVmaze gives no number ([number] is
     * then 0 and [season] is the season it sits beside, not its own; [Specials] numbers it S00E[n]).
     */
    class Air(val season: Int, val number: Int, val at: Long, val special: Boolean = false)

    /** A show's last aired and next episode by TVmaze. */
    class Info(val prev: Air?, val next: Air?, val fetchedAt: Long)

    /**
     * One episode from a show's full TVmaze list: [date] its air day "yyyy-MM-dd" ("" unknown), [at] its exact
     * instant (0 when TVmaze knows only the day), [name] TVmaze's title (often there before TMDB's).
     */
    class Episode(val season: Int, val number: Int, val name: String, val date: String, val at: Long)

    /**
     * One special from the same list ([Specials] turns it into an episode-shaped object): TVmaze gives specials
     * no number, and its `season` is the season the special sits beside, kept here as [parentSeason]. [id] is
     * TVmaze's own episode id, which identifies a special TMDB has no record of.
     */
    class Special(val id: Int, val parentSeason: Int, val name: String, val date: String, val at: Long,
                  val runtime: Int, val summary: String)

    private class Schedule(val eps: List<Episode>, val specials: List<Special>, val fetchedAt: Long)

    private val memory = ConcurrentHashMap<Int, Info>()
    private val schedules = ConcurrentHashMap<Int, Schedule>()
    /** One lookup per show at a time: a second caller waits, then reads the cache. */
    private val showLocks = ConcurrentHashMap<Int, Any>()
    private val gate = Any()
    private var lastCallAt = 0L

    private fun lockFor(tmdbId: Int): Any = showLocks.getOrPut(tmdbId) { Any() }

    /**
     * Every regular episode of TMDB show [tmdbId] with its air day and, when TVmaze has one, its exact
     * instant; null when TVmaze doesn't have the show. Specials are listed on their own ([specials]): they
     * have no episode number, so they never belong in a season's numbered list. One call per show, kept
     * [INFO_TTL]. Blocking; cached.
     */
    fun episodes(ctx: Context, tmdbId: Int): List<Episode>? = schedule(ctx, tmdbId)?.eps

    /**
     * Every special TVmaze lists for TMDB show [tmdbId], in its order (air order), or null when TVmaze doesn't
     * have the show. Shares [episodes]' one call and cache. Blocking.
     */
    fun specials(ctx: Context, tmdbId: Int): List<Special>? = schedule(ctx, tmdbId)?.specials

    private fun schedule(ctx: Context, tmdbId: Int): Schedule? {
        schedules[tmdbId]?.takeIf { System.currentTimeMillis() - it.fetchedAt < INFO_TTL }?.let { return it }
        synchronized(lockFor(tmdbId)) {
            schedules[tmdbId]?.takeIf { System.currentTimeMillis() - it.fetchedAt < INFO_TTL }?.let { return it }
            val app = ctx.applicationContext
            val maze = mazeId(app, tmdbId, null, null) ?: return null
            // `specials=1` or TVmaze leaves them out of the list altogether.
            val a = tryFetch("$API/shows/$maze/episodes?specials=1")?.let { try { JSONArray(it) } catch (e: Exception) { null } }
                ?: return schedules[tmdbId]
            val eps = ArrayList<Episode>(a.length())
            val specials = ArrayList<Special>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val date = o.optString("airdate").takeIf { it.length == 10 }.orEmpty()
                val name = o.optString("name").takeIf { it != "null" }.orEmpty()
                // A special has no number of its own; its "season" is the season it sits beside.
                if (o.isNull("number") || o.optInt("number", -1) <= 0) {
                    specials += Special(o.optInt("id"), o.optInt("season"), name, date, exact(o), o.optInt("runtime"),
                        o.optString("summary").takeIf { it != "null" }.orEmpty())
                } else {
                    eps += Episode(o.optInt("season"), o.optInt("number"), name, date, exact(o))
                }
            }
            val s = Schedule(eps, specials, System.currentTimeMillis())
            schedules[tmdbId] = s
            trimSchedules()
            return s
        }
    }

    /** The cached schedule for TMDB show [tmdbId], no network (null: not fetched yet, or TVmaze has none). */
    fun peek(tmdbId: Int): Info? = memory[tmdbId]

    /**
     * [tmdbId]'s previous and next episode with exact instants, or null when TVmaze doesn't have the show.
     * [imdb] / [tvdb] are the show's ids (looked up from TMDB when both are missing). Blocking; cached.
     */
    fun info(ctx: Context, tmdbId: Int, imdb: String? = null, tvdb: Int? = null): Info? {
        memory[tmdbId]?.takeIf { System.currentTimeMillis() - it.fetchedAt < INFO_TTL }?.let { return it }
        val app = ctx.applicationContext
        // Under the show's lock: New Episodes, a pill and a title page asking for one show at once share one call.
        synchronized(lockFor(tmdbId)) {
            memory[tmdbId]?.takeIf { System.currentTimeMillis() - it.fetchedAt < INFO_TTL }?.let { return it }
            val maze = mazeId(app, tmdbId, imdb, tvdb) ?: return null
            val o = tryFetch("$API/shows/$maze?embed%5B%5D=previousepisode&embed%5B%5D=nextepisode")
                ?.let { try { JSONObject(it) } catch (e: Exception) { null } } ?: return memory[tmdbId]
            val emb = o.optJSONObject("_embedded")
            val info = Info(air(emb?.optJSONObject("previousepisode")), air(emb?.optJSONObject("nextepisode")), System.currentTimeMillis())
            memory[tmdbId] = info
            return info
        }
    }

    private fun air(o: JSONObject?): Air? {
        if (o == null) return null
        val at = exact(o).takeIf { it > 0 } ?: return null
        // A special comes through as the show's previous or next episode with a null number (a JSON null reads
        // as -1): it is kept, marked, with the season it sits beside ([Specials] gives it its S00E[n] place).
        if (o.isNull("number")) return Air(o.optInt("season"), 0, at, special = true)
        val n = o.optInt("number", -1).takeIf { it > 0 } ?: return null
        return Air(o.optInt("season"), n, at)
    }

    /**
     * A TVmaze episode's exact instant, or 0 when it has only a day: with no `airtime`, TVmaze's airstamp is a
     * stand-in (noon on the show's local day), not a time anyone announced.
     */
    private fun exact(o: JSONObject): Long {
        if (o.optString("airtime").let { it.isBlank() || it == "null" }) return 0L
        return parse(o.optString("airstamp")) ?: 0L
    }

    /** "2026-10-27T01:00:00+00:00" as epoch ms. */
    fun parse(stamp: String?): Long? {
        if (stamp.isNullOrBlank() || stamp == "null") return null
        return try { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(stamp)?.time } catch (e: Exception) { null }
    }

    // ---- TMDB id -> TVmaze id, kept for good ----

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tvmaze_ids", Context.MODE_PRIVATE)

    private fun mazeId(app: Context, tmdbId: Int, imdb: String?, tvdb: Int?): Int? {
        val p = prefs(app)
        val known = p.getInt("m$tmdbId", 0)
        if (known > 0) return known
        if (known < 0 && System.currentTimeMillis() - p.getLong("t$tmdbId", 0) < MISS_RETRY_MS) return null
        var im = imdb?.takeIf { it.startsWith("tt") }
        var tv = tvdb?.takeIf { it > 0 }
        if (im == null && tv == null) {
            // TMDB didn't answer: nothing is known yet, so no miss is recorded.
            val ext = WatchHistory.tmdb(app, "/tv/$tmdbId/external_ids", "") ?: return null
            im = ext.optString("imdb_id").takeIf { it.startsWith("tt") }
            tv = ext.optInt("tvdb_id").takeIf { it > 0 }
        }
        // Only TVmaze's own "no such show" (404) is a miss kept for a week; no answer at all asks again next time.
        val body = try {
            im?.let { fetch("$API/lookup/shows?imdb=$it") } ?: tv?.let { fetch("$API/lookup/shows?thetvdb=$it") }
        } catch (e: Exception) {
            Log.w(TAG, "Airtime: TVmaze unavailable (${e.javaClass.simpleName})")
            return null
        }
        val found = body?.let { try { JSONObject(it) } catch (e: Exception) { null } }?.optInt("id")?.takeIf { it > 0 }
        p.edit().putInt("m$tmdbId", found ?: -1).putLong("t$tmdbId", System.currentTimeMillis()).apply()
        return found
    }

    /** At most [MAX_SCHEDULES] lists: expired ones go first, then the oldest. */
    private fun trimSchedules() {
        if (schedules.size <= MAX_SCHEDULES) return
        val now = System.currentTimeMillis()
        schedules.entries.removeAll { now - it.value.fetchedAt >= INFO_TTL }
        val over = schedules.size - MAX_SCHEDULES
        if (over > 0) schedules.entries.sortedBy { it.value.fetchedAt }.take(over).forEach { schedules.remove(it.key) }
    }

    /** [fetch], with no answer logged and read as null. */
    private fun tryFetch(url: String): String? = try { fetch(url) } catch (e: Exception) {
        Log.w(TAG, "Airtime: TVmaze unavailable (${e.javaClass.simpleName})")
        null
    }

    /**
     * A TVmaze GET (redirects followed), at least [GAP_MS] after the last one: the body, or null when TVmaze
     * says 404. Throws when TVmaze can't be reached or answers with another error.
     */
    private fun fetch(url: String): String? {
        synchronized(gate) {
            val wait = lastCallAt + GAP_MS - SystemClock.elapsedRealtime()
            if (wait > 0) Thread.sleep(wait)
            lastCallAt = SystemClock.elapsedRealtime()
        }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6_000
            c.readTimeout = 8_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "DreamOS")
            val code = c.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // ---- words ----

    /** [at] as the viewer's local "yyyy-MM-dd" (an episode's local air day). */
    fun localDay(at: Long): String = DreamTime.fmt("yyyy-MM-dd").format(Date(at))

    /** [at] as "2026-10-27 21:00" local, for log lines. */
    fun stamp(at: Long): String = DreamTime.fmt("yyyy-MM-dd HH:mm").format(Date(at))

    /**
     * When [at] is, as a viewer says it: "Tonight at 9:00 PM" (today, from 5 PM), "Today at 3:00 PM",
     * "Tomorrow at 3:00 AM", "Friday at 9:00 PM" (within a week), else "Nov 14 at 9:00 PM".
     */
    fun label(at: Long, now: Long = System.currentTimeMillis()): String {
        val time = DreamTime.formatTime(at)
        val c = DreamTime.calendar(at)
        val days = DreamTime.dayDelta(at, now).toLong()
        return when {
            days == 0L -> (if (c.get(Calendar.HOUR_OF_DAY) >= 17) "Tonight" else "Today") + " at $time"
            days == 1L -> "Tomorrow at $time"
            days in 2..6 -> DreamTime.fmt("EEEE").format(Date(at)) + " at $time"
            else -> DreamTime.fmt("MMM d").format(Date(at)) + " at $time"
        }
    }
}
