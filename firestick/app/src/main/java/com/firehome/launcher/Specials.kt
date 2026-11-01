package com.firehome.launcher

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A show's specials, the one catalog every surface reads. TMDB is the app's episode list, but it has no record
 * of many of them: a returning show's `/tv/{id}/season/0` answered 404 while the service was already streaming
 * a 30-minute special of it that day (2026-10-31). So TMDB's season 0 is used where it exists (it carries
 * stills and overviews) and TVmaze fills in the rest ([AirTimes.specials], which also knows the exact drop
 * time); the two are matched on the name, or on the air day when one of them has no name.
 *
 * A special is its own kind, never a season (STANDARDS section 5), and what identifies one here is [ID], the
 * id of the source that listed it. Its place in air order is a view that moves whenever an older special is
 * added later, so nothing is ever stored under it: it is worked out at the edge, for the three forms that
 * demand season 0 ([numberOf] feeds the index request and release-name matching, [PARENT] the S01E00 form).
 *
 * The shape is TMDB's own episode JSON, so episode cards, [Upcoming.split] and the watch history take it
 * unchanged. Everything here blocks (TMDB and TVmaze, both cached): call it off the main thread.
 */
object Specials {
    private const val TAG = "FireHome"
    /**
     * The Specials row's own selector on the title page and in the player's episode list: a row, not a season,
     * and it never reaches the model.
     */
    const val ROW = -1
    /** On a special's JSON: what identifies it, "tmdb:<episode id>" or "tvmaze:<episode id>". */
    const val ID = "_specialId"
    /**
     * On a special's JSON: an older id the same special was identified by. [ID] is the canonical one, TMDB's,
     * which is also the only one Trakt can sync; the alias is the TVmaze id it was first saved under, before
     * TMDB listed it. [byId] accepts either and [adopt] moves what is stored onto the canonical one, because a
     * special the viewer watched must never read as unwatched just because its id improved.
     */
    const val ALIAS = "_specialAlias"
    /** On a special's JSON: the season it aired beside, for the S01E00 form of its release name (0 unknown). */
    const val PARENT = "_parentSeason"
    /** On a special's JSON: its exact drop instant in epoch ms (0 when only the day is known). */
    const val AT = "_airstamp"
    /** On a special's JSON: TMDB's own episode number for it, when it isn't the air-order one. */
    const val TMDB_NUMBER = "_tmdbNumber"
    private const val TTL = 3 * 60 * 60_000L
    private const val MAX_SHOWS = 24
    private val TAGS = Regex("<[^>]*>")

    private class Cached(val eps: List<JSONObject>, val at: Long)

    private val memory = ConcurrentHashMap<Int, Cached>()
    private val locks = ConcurrentHashMap<Int, Any>()

    /**
     * One list of episodes: a show's specials when [special], else TMDB's season [season] as before. Null when
     * there are none. Blocking; cached.
     */
    fun list(ctx: Context, showId: Int, special: Boolean, season: Int?): List<JSONObject>? =
        if (special) of(ctx, showId).ifEmpty { null }
        else season?.let { WatchHistory.seasonEpisodes(ctx, showId, it) }

    /** Show [showId]'s specials in air order, as TMDB-style episode JSON (empty: it has none). Blocking; cached. */
    fun of(ctx: Context, showId: Int): List<JSONObject> {
        if (showId <= 0) return emptyList()
        fresh(showId)?.let { return it }
        val app = ctx.applicationContext
        synchronized(locks.getOrPut(showId) { Any() }) {
            fresh(showId)?.let { return it }
            // TMDB answers 404 when a show has no season 0 at all; that is the usual case for a new special.
            val tmdb = try { WatchHistory.seasonEpisodes(app, showId, 0) } catch (e: Exception) { null }.orEmpty()
            val maze = try { AirTimes.specials(app, showId) } catch (e: Exception) { null }.orEmpty()
            val out = merge(tmdb, maze)
            memory[showId] = Cached(out, System.currentTimeMillis())
            trim()
            for (o in out) o.optString(ALIAS).takeIf { it.isNotEmpty() }?.let { adopt(app, showId, it, o.optString(ID)) }
            if (out.isNotEmpty()) Log.w(TAG, "Specials: show $showId has ${out.size} " +
                "(${tmdb.size} from TMDB, ${out.size - tmdb.size} more from tvmaze)")
            return out
        }
    }

    /** The special [id] identifies, by its canonical id or an older one ([ALIAS]), or null. Blocking; cached. */
    fun byId(ctx: Context, showId: Int, id: String): JSONObject? =
        of(ctx, showId).firstOrNull { it.optString(ID) == id || it.optString(ALIAS) == id }

    /** A special's id ([ID]) as its JSON carries it ("" when it has none). */
    fun idOf(o: JSONObject): String = o.optString(ID)

    /**
     * Special [id]'s place in air order, 1-based: the number the index is asked for (S00E[n]) and the one
     * release names use. 0 when the catalog doesn't have it. Blocking; cached.
     */
    fun numberOf(ctx: Context, showId: Int, id: String): Int = byId(ctx, showId, id)?.optInt("episode_number") ?: 0

    /** What was already looked up, no network (null: not asked yet). */
    fun peek(showId: Int): List<JSONObject>? = fresh(showId)

    private fun fresh(showId: Int): List<JSONObject>? =
        memory[showId]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.eps

    private fun trim() {
        if (memory.size <= MAX_SHOWS) return
        val now = System.currentTimeMillis()
        memory.entries.removeAll { now - it.value.at >= TTL }
        val over = memory.size - MAX_SHOWS
        if (over > 0) memory.entries.sortedBy { it.value.at }.take(over).forEach { memory.remove(it.key) }
    }

    /**
     * TMDB's season 0 plus the specials only TVmaze lists, in air order. A TVmaze special TMDB also has is the
     * same special: TMDB's entry wins (it has the still, the overview and the id Trakt wants) and takes
     * TVmaze's parent season and exact instant.
     */
    private fun merge(tmdb: List<JSONObject>, maze: List<AirTimes.Special>): List<JSONObject> {
        val out = ArrayList<JSONObject>(tmdb.size + maze.size)
        for (ep in tmdb) {
            // TMDB's cached objects are shared: a copy, never written to.
            val o = JSONObject(ep.toString())
            o.put("season_number", 0)
            o.put(ID, "tmdb:" + o.optInt("id"))
            val m = maze.firstOrNull { same(it, o) }
            if (m != null) { o.put(PARENT, m.parentSeason); o.put(AT, m.at); o.put(ALIAS, "tvmaze:${m.id}") }
            out += o
        }
        for (s in maze) {
            if (tmdb.any { same(s, it) }) continue
            out += JSONObject()
                .put("season_number", 0)
                .put("episode_number", 0)
                .put(ID, "tvmaze:${s.id}")
                .put("name", s.name)
                .put("air_date", s.date)
                .put("overview", plain(s.summary))
                .put("runtime", s.runtime)
                .put(PARENT, s.parentSeason)
                .put(AT, s.at)
        }
        // Air order, undated ones last. The place in that order is what the index is asked for, and only that.
        val sorted = out.sortedWith(compareBy({ Upcoming.date(it).ifEmpty { "9999" } }, { it.optLong(AT) }))
        for ((i, o) in sorted.withIndex()) {
            val tmdbNumber = o.optInt("episode_number")
            o.put("episode_number", i + 1)
            if (tmdbNumber > 0 && tmdbNumber != i + 1) o.put(TMDB_NUMBER, tmdbNumber)
        }
        return sorted
    }

    /**
     * TMDB has caught up with a special TVmaze listed first, so everything saved under the old id moves onto
     * the canonical one: its Continue Watching row, its watched mark, and the keys that remember it was already
     * found and announced. Without all four, a watched special would read as unwatched, or a special still on
     * the row would announce itself as new again.
     */
    private fun adopt(ctx: Context, showId: Int, from: String, to: String) {
        if (from == to || to.isEmpty()) return
        WatchHistory.adoptSpecialId(ctx, showId, from, to)
        Watched.adoptSpecialId(ctx, showId, from, to)
        NewEpisodes.adoptSpecialId(ctx, showId, from, to)
        NewEpisodeSpot.adoptSpecialId(ctx, showId, from, to)
    }

    /**
     * The same special from both sources. The name decides when both have one, since two specials can share a
     * day (a holiday special in two parts); a day alone only counts when one of them is unnamed.
     */
    private fun same(s: AirTimes.Special, ep: JSONObject): Boolean {
        val mine = norm(s.name)
        val theirs = norm(ep.optString("name"))
        if (mine.isNotEmpty() && theirs.isNotEmpty()) return mine == theirs
        return s.date.isNotEmpty() && s.date == Upcoming.date(ep)
    }

    private fun norm(s: String) = s.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

    /** TVmaze summaries are HTML; the blurb wants plain words. */
    private fun plain(html: String): String = TAGS.replace(html, "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").trim()
}
