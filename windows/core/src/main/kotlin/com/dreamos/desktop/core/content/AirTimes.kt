package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.util.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Exact air times (fire-home AirTimes.kt). TMDB knows only an episode's day; TVmaze (free, no key) knows the instant
 * it airs or drops (`airstamp`: a broadcast at 9 PM ET, a streaming drop at 12 AM PT). A TMDB show is matched to
 * TVmaze once through its IMDb id (else its TheTVDB id) and the match is kept for good in `tvmaze_ids`; only TVmaze's
 * own "no such show" (404) is kept as a miss, for a week; no answer at all asks again next time. An episode TVmaze
 * lists without an `airtime` has only a day (its airstamp is a noon stand-in), so it gets no exact instant.
 *
 * Calls are at least [GAP_MS] apart (TVmaze allows ~20 per 10 s). Nothing shows when TVmaze has nothing: the TMDB
 * day stays the rule then. Blocking: IO threads only.
 *
 * Bounded: [MAX_SCHEDULES] full episode lists in memory (a long show's is ~100 KB parsed: ~2.4 MB worst case),
 * [MAX_INFOS] previous / next pairs (tiny). `tvmaze_ids` keeps two numbers per show ever looked up (a few thousand
 * shows: ~100 KB).
 */
object AirTimes {
    private const val API = "https://api.tvmaze.com"
    private const val INFO_TTL = 3 * 3_600_000L
    private const val MISS_RETRY_MS = 7 * 86_400_000L
    private const val GAP_MS = 550L
    private const val MAX_SCHEDULES = 24
    private const val MAX_INFOS = 256

    /** One episode's exact instant (epoch ms). */
    data class Air(val season: Int, val number: Int, val at: Long)

    /** A show's last aired and next episode by TVmaze. */
    data class Info(val prev: Air?, val next: Air?, val fetchedAt: Long)

    /**
     * One episode from a show's TVmaze list: [date] its air day "yyyy-MM-dd" ("" unknown), [at] its exact instant
     * (null when TVmaze knows only the day), [name] TVmaze's title (often there before TMDB's).
     */
    data class MazeEpisode(val season: Int, val number: Int, val name: String, val date: String, val at: Long?)

    private class Schedule(val eps: List<MazeEpisode>, val fetchedAt: Long)

    private val infos = ConcurrentHashMap<Int, Info>()
    private val schedules = ConcurrentHashMap<Int, Schedule>()
    /** One lookup per show at a time: a second caller waits, then reads the cache. */
    private val showLocks = ConcurrentHashMap<Int, Any>()
    private val gate = Any()
    private var lastCallAt = 0L

    private fun lockFor(tmdbId: Int): Any = showLocks.getOrPut(tmdbId) { Any() }

    /**
     * Every regular episode of TMDB show [tmdbId] (specials left out) with its air day and, when TVmaze has one, its
     * exact instant; null when TVmaze doesn't have the show or can't be reached. Kept [INFO_TTL]. Blocking.
     */
    fun episodes(tmdbId: Int): List<MazeEpisode>? {
        fun hit() = schedules[tmdbId]?.takeIf { System.currentTimeMillis() - it.fetchedAt < INFO_TTL }?.eps
        hit()?.let { return it }
        synchronized(lockFor(tmdbId)) {
            hit()?.let { return it }
            val maze = mazeId(tmdbId, null, null) ?: return null
            val a = tryFetch("$API/shows/$maze/episodes")?.let { runCatching { parseJson(it) as? JsonArray }.getOrNull() }
                ?: return schedules[tmdbId]?.eps
            val eps = parseEpisodes(a)
            schedules[tmdbId] = Schedule(eps, System.currentTimeMillis())
            trim(schedules, MAX_SCHEDULES) { it.fetchedAt }
            return eps
        }
    }

    /** A TVmaze episode list as [MazeEpisode]s (numbered episodes only). */
    fun parseEpisodes(a: JsonArray): List<MazeEpisode> = a.objects().mapNotNull { o ->
        val n = o.int("number", -1).takeIf { it > 0 } ?: return@mapNotNull null
        MazeEpisode(o.int("season"), n, o.str("name").takeIf { it != "null" }.orEmpty(),
            o.str("airdate").takeIf { it.length == 10 }.orEmpty(), exact(o))
    }

    /** What TVmaze said last about [tmdbId]'s previous / next episode, any age, no network. */
    fun peek(tmdbId: Int): Info? = infos[tmdbId]

    /**
     * [tmdbId]'s previous and next episode with exact instants, or null when TVmaze doesn't have the show.
     * [imdb] / [tvdb] are the show's ids (looked up from TMDB when both are missing). Kept [INFO_TTL]. Blocking.
     */
    fun info(tmdbId: Int, imdb: String? = null, tvdb: Int? = null): Info? {
        infos[tmdbId]?.takeIf { System.currentTimeMillis() - it.fetchedAt < INFO_TTL }?.let { return it }
        val maze = synchronized(lockFor(tmdbId)) { mazeId(tmdbId, imdb, tvdb) } ?: return null
        val o = tryFetch("$API/shows/$maze?embed%5B%5D=previousepisode&embed%5B%5D=nextepisode")
            ?.let { runCatching { parseJson(it) as? JsonObject }.getOrNull() } ?: return infos[tmdbId]
        val emb = o.o("_embedded")
        val info = Info(air(emb.o("previousepisode")), air(emb.o("nextepisode")), System.currentTimeMillis())
        infos[tmdbId] = info
        trim(infos, MAX_INFOS) { it.fetchedAt }
        return info
    }

    private fun air(o: JsonObject?): Air? {
        if (o == null) return null
        val at = exact(o) ?: return null
        val n = o.int("number", -1).takeIf { it >= 0 } ?: return null
        return Air(o.int("season"), n, at)
    }

    /**
     * A TVmaze episode's exact instant, or null when it has only a day: with no `airtime`, TVmaze's airstamp is a
     * stand-in (noon on the show's local day), not a time anyone announced.
     */
    fun exact(o: JsonObject): Long? {
        if (o.str("airtime").let { it.isBlank() || it == "null" }) return null
        return parseStamp(o.str("airstamp"))
    }

    /** "2026-10-27T01:00:00+00:00" as epoch ms. */
    fun parseStamp(stamp: String?): Long? {
        if (stamp.isNullOrBlank() || stamp == "null") return null
        return runCatching { OffsetDateTime.parse(stamp).toInstant().toEpochMilli() }.getOrNull()
    }

    // ---- TMDB id -> TVmaze id, kept for good ----

    private fun ids() = DreamStore.shared.file("tvmaze_ids")

    private fun mazeId(tmdbId: Int, imdb: String?, tvdb: Int?): Int? {
        val p = ids()
        val known = p.getInt("m$tmdbId", 0)
        if (known > 0) return known
        if (known < 0 && System.currentTimeMillis() - p.getLong("t$tmdbId", 0) < MISS_RETRY_MS) return null
        var im = imdb?.takeIf { it.startsWith("tt") }
        var tv = tvdb?.takeIf { it > 0 }
        if (im == null && tv == null) {
            // TMDB didn't answer: nothing is known yet, so no miss is recorded.
            val ext = WatchHistory.tmdb("/tv/$tmdbId/external_ids") ?: return null
            im = ext.str("imdb_id").takeIf { it.startsWith("tt") }
            tv = ext.int("tvdb_id").takeIf { it > 0 }
        }
        val body = try {
            im?.let { fetch("$API/lookup/shows?imdb=$it") } ?: tv?.let { fetch("$API/lookup/shows?thetvdb=$it") }
        } catch (e: Exception) {
            Log.w("AirTimes", "TVmaze unavailable (${e.javaClass.simpleName})")
            return null
        }
        val found = body?.let { runCatching { parseJson(it) as? JsonObject }.getOrNull() }?.int("id")?.takeIf { it > 0 }
        p.edit { putInt("m$tmdbId", found ?: -1); putLong("t$tmdbId", System.currentTimeMillis()) }
        return found
    }

    /** At most [max] entries: the oldest go. */
    private fun <V> trim(map: ConcurrentHashMap<Int, V>, max: Int, at: (V) -> Long) {
        val over = map.size - max
        if (over > 0) map.entries.sortedBy { at(it.value) }.take(over).forEach { map.remove(it.key) }
    }

    private fun tryFetch(url: String): String? = try { fetch(url) } catch (e: Exception) {
        Log.w("AirTimes", "TVmaze unavailable (${e.javaClass.simpleName})")
        null
    }

    /**
     * A TVmaze GET at least [GAP_MS] after the last one: the body, or null when TVmaze says 404. Throws when TVmaze
     * can't be reached or answers with another error.
     */
    private fun fetch(url: String): String? {
        synchronized(gate) {
            val wait = lastCallAt + GAP_MS - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastCallAt = System.currentTimeMillis()
        }
        val r = Net.request("GET", url, timeoutMs = 8_000)
        if (r.code == 404) return null
        if (!r.ok) throw java.io.IOException("TVmaze answered ${r.code}")
        return r.body
    }
}
