package com.firehome.launcher

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "New Episodes": recently aired and upcoming episodes of the shows the user follows (TV shows on
 * My List plus shows from the watch history). Show data comes from TMDB and is cached in a JSON
 * file for [MAX_AGE_MS]; which episodes count as "New" (not yet focused/opened) is per profile. An episode the
 * viewer is already past ([caughtUp]: they finished it or a later one) is left out until a newer one airs.
 * Everything that touches the network ([load], [imdb]) blocks: call it from Net.background.
 */
object NewEpisodes {
    private const val TAG = "NewEpisodes"
    private const val API = "https://api.themoviedb.org/3"
    const val MAX_AGE_MS = 6 * 60 * 60_000L
    /**
     * A show whose cached next episode has aired by today (local) is fetched again this often, not every
     * [MAX_AGE_MS]: TMDB moves it to last_episode_to_air on its air date, and that is when it's new.
     */
    private const val DUE_RETRY_MS = 60 * 60_000L
    private const val RECENT_DAYS = 7
    private const val UPCOMING_DAYS = 30
    private const val MAX_SHOWS = 40
    private const val FILE = "new_episodes.json"

    /**
     * One episode. [airDate] is the local air day; [at] the exact instant it airs or drops (epoch ms, from
     * TVmaze: [AirTimes]), 0 when only TMDB's date is known.
     */
    class Ep(val season: Int, val number: Int, val name: String, val airDate: String, val still: String?, val overview: String, val at: Long = 0) {
        fun toJson() = JSONObject().put("s", season).put("e", number).put("n", name).put("d", airDate)
            .put("st", still ?: "").put("o", overview).put("at", at)

        /** This episode at the exact instant [t] (its air day becomes [t]'s local day). */
        fun withAt(t: Long) = Ep(season, number, name, AirTimes.localDay(t), still, overview, t)

        /** Out by [now]: past its instant when known, else on or after its air day. */
        fun aired(now: Long = System.currentTimeMillis()) = if (at > 0) at <= now else daysFromToday(airDate) <= 0

        fun same(season: Int, number: Int) = this.season == season && this.number == number

        companion object {
            fun fromJson(o: JSONObject?) = o?.let {
                Ep(it.optInt("s"), it.optInt("e"), it.optString("n"), it.optString("d"), it.optString("st").ifEmpty { null }, it.optString("o"), it.optLong("at"))
            }

            /** From a TMDB episode object (last_episode_to_air, a season's episodes...). */
            fun fromTmdb(o: JSONObject?): Ep? {
                if (o == null) return null
                val date = o.optString("air_date").takeIf { it.length == 10 } ?: return null
                return Ep(o.optInt("season_number"), o.optInt("episode_number"), o.optString("name"), date,
                    o.optString("still_path").takeIf { it.isNotEmpty() && it != "null" }, o.optString("overview"))
            }
        }
    }

    class Show(
        val id: Int, val name: String, val year: Int?, val overview: String, val rating: Double,
        val poster: String?, val backdrop: String?, val imdb: String?,
        val providers: List<Int>, val networks: List<String>,
        /** Episodes released together on the most recent air date (a whole season for binge drops). */
        val latest: List<Ep>,
        val next: Ep?, val fetchedAt: Long,
    ) {
        fun toJson() = JSONObject().apply {
            put("id", id); put("name", name); put("year", year ?: 0); put("overview", overview); put("rating", rating)
            put("poster", poster ?: ""); put("backdrop", backdrop ?: ""); put("imdb", imdb ?: "")
            put("providers", JSONArray(providers)); put("networks", JSONArray(networks))
            put("latest", JSONArray().apply { latest.forEach { put(it.toJson()) } })
            next?.let { put("next", it.toJson()) }
            put("at", fetchedAt)
        }

        /** As a Title, for the details page, preview card and backdrop. */
        fun title() = Title(id, "tv", name, overview, poster, backdrop, year?.toString() ?: "", rating)

        companion object {
            fun fromJson(o: JSONObject): Show {
                fun ints(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.optInt(i) } }.orEmpty()
                fun strs(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.optString(i) } }.orEmpty()
                val latest = o.optJSONArray("latest")?.let { a -> (0 until a.length()).mapNotNull { Ep.fromJson(a.optJSONObject(it)) } }.orEmpty()
                return Show(o.getInt("id"), o.optString("name"), o.optInt("year").takeIf { it > 0 }, o.optString("overview"),
                    o.optDouble("rating", 0.0), o.optString("poster").ifEmpty { null }, o.optString("backdrop").ifEmpty { null },
                    o.optString("imdb").ifEmpty { null }, ints(o.optJSONArray("providers")), strs(o.optJSONArray("networks")),
                    latest, Ep.fromJson(o.optJSONObject("next")), o.optLong("at"))
            }
        }
    }

    /** One card: a released episode (or batch of episodes), or an upcoming one. */
    class Item(val show: Show, val ep: Ep, val lastNumber: Int, val upcoming: Boolean) {
        val key get() = "${show.id}:s${ep.season}e${ep.number}"

        /** "S2 · E5 · Aired yesterday", "S1 · E1–8 · Aired today", "S3 · E1 · Tonight". */
        fun badge(): String {
            val eps = if (lastNumber > ep.number) "E${ep.number}–$lastNumber" else "E${ep.number}"
            val days = daysFromToday(ep.airDate)
            val time = if (upcoming) when {
                ep.at > 0 -> AirTimes.label(ep.at)
                days <= 0 -> "Tonight"
                days == 1 -> "Tomorrow"
                days < 7 -> fmt("EEE", ep.airDate)
                else -> fmt("MMM d", ep.airDate)
            } else when {
                days >= 0 -> "Aired today"
                days == -1 -> "Aired yesterday"
                else -> "Aired " + fmt("EEE", ep.airDate)
            }
            return "S${ep.season}  ·  $eps  ·  $time"
        }

        /** "S2E5", or "S1E1–8" for a batch. */
        fun code() = "S${ep.season}E${ep.number}" + if (lastNumber > ep.number) "–$lastNumber" else ""

        fun image(size: String = "w300") = (ep.still ?: show.backdrop)?.let { "${Tmdb.IMG}$size$it" }
    }

    /** [nextAt]: the soonest exact air instant still ahead among [upcoming] (0: none known); worth a look right after it. */
    class Snapshot(val recent: List<Item>, val upcoming: List<Item>, val announce: List<Item>, val nextAt: Long = 0) {
        val isEmpty get() = recent.isEmpty() && upcoming.isEmpty()
    }

    private val refreshing = AtomicBoolean(false)
    private val lock = Any()
    @Volatile private var memory: Map<Int, Show>? = null

    // ---- public API ----

    /** Last refresh is older than [MAX_AGE_MS] (cheap: no network, no file read). */
    fun isStale(ctx: Context) = System.currentTimeMillis() - prefs(ctx).getLong("refreshed" + Profiles.suffix(ctx), 0) > MAX_AGE_MS

    /**
     * Cached episodes, refreshed from TMDB first when stale or when the followed shows changed
     * (and [refresh] is set). Blocking. [Snapshot.announce] lists episodes that turned up since the
     * last refresh and haven't been announced yet; call [announced] once they've been shown.
     */
    fun load(ctx: Context, refresh: Boolean = true): Snapshot {
        val tracked = tracked(ctx)
        var shows = cachedShows(ctx)
        if (refresh && tracked.isNotEmpty() && (isStale(ctx) || tracked.any { it !in shows || shows[it]?.let(::due) == true })) {
            if (refreshing.compareAndSet(false, true)) {
                try {
                    shows = refresh(ctx, tracked, shows)
                } finally {
                    refreshing.set(false)
                }
            }
        }
        val followed = shows.filterKeys { it in tracked }.values
        val now = System.currentTimeMillis()
        // Caught up (the drop's last episode, or a later one, finished): not new to this viewer until a newer one airs.
        val history = try { WatchHistory.recent(ctx) } catch (e: Exception) { emptyList() }
        fun unseen(i: Item) = !caughtUp(ctx, i.show.id, i.ep.season, i.lastNumber, history)
        val recent = followed.mapNotNull { recentItem(it, now) }.filter(::unseen)
            .sortedWith(compareByDescending<Item> { it.ep.airDate }.thenByDescending { it.ep.at }.thenBy { it.show.name })
        val upcoming = followed.mapNotNull { upcomingItem(it, now) }.filter(::unseen)
            .filter { up -> recent.none { it.key == up.key } }.sortedWith(compareBy<Item> { it.ep.airDate }.thenBy { it.ep.at })
        val pending = stringSet(ctx, "announce")
        val nextAt = upcoming.mapNotNull { it.ep.at.takeIf { t -> t > now } }.minOrNull() ?: 0L
        return Snapshot(recent, upcoming, recent.filter { it.key in pending }, nextAt)
    }

    fun isNew(ctx: Context, item: Item) = !item.upcoming && item.key in stringSet(ctx, "fresh")

    /** Clears the "New" badge once the user has focused or opened the episode. */
    fun markSeen(ctx: Context, item: Item) = synchronized(lock) {
        val fresh = stringSet(ctx, "fresh")
        if (fresh.remove(item.key)) putSet(ctx, "fresh", fresh)
    }

    /** The banner for these has been shown; don't show it again. */
    fun announced(ctx: Context, items: List<Item>) = synchronized(lock) {
        val pending = stringSet(ctx, "announce")
        if (pending.removeAll(items.map { it.key }.toSet())) putSet(ctx, "announce", pending)
    }

    /**
     * The viewer is past S[season]E[episode] of [show], so it isn't a new episode to them: they finished it or a later
     * one ([Watched], Trakt's history included), or their watch history is on a later episode (in progress or Up
     * Next), or it holds this very episode finished or removed from Continue Watching. The one rule every "new
     * episode" surface goes by (New Episodes for You, the banner and its notice, NEW EPISODE badges, the general New
     * Episodes rows); the iPhone and Windows apps keep the same one. Local and instant; pass [history] for many shows.
     */
    fun caughtUp(ctx: Context, show: Int, season: Int, episode: Int,
                 history: List<WatchHistory.Entry> = WatchHistory.recent(ctx)): Boolean =
        past(Watched.furthest(ctx, show), history.firstOrNull { it.isShow && it.tmdbId == show }, season, episode)

    /** [caughtUp] on its inputs: [finished] the latest finished episode (season to number), [entry] the show's history entry. */
    fun past(finished: Pair<Int, Int>?, entry: WatchHistory.Entry?, season: Int, episode: Int): Boolean {
        if (finished != null && (finished.first > season || finished.first == season && finished.second >= episode)) return true
        val e = entry ?: return false
        if (WatchHistory.later(e, season, episode)) return true
        return e.season == season && e.episode == episode && !e.upNext && (e.hidden || e.progress >= WatchHistory.FINISHED)
    }

    /** Installed apps that stream [show] (TMDB US flatrate providers), one per app. */
    fun services(show: Show, installed: Set<String>): List<Tmdb.Service> =
        show.providers.mapNotNull { id -> Tmdb.SERVICES.firstOrNull { it.providerId == id } }
            .filter { it.pkg in installed }.distinctBy { it.pkg }

    /** The show's IMDb id, from the cache or TMDB's external_ids. Blocking. */
    fun imdb(ctx: Context, show: Show): String? = show.imdb ?: try {
        Net.getJson(url(ctx, "/tv/${show.id}/external_ids")).optString("imdb_id").takeIf { it.startsWith("tt") }
    } catch (e: Exception) {
        null
    }

    // ---- which shows ----

    /** More places followed shows come from (a Trakt watchlist...): TMDB show ids, best first, for the current profile. Blocking. */
    private val followSources = CopyOnWriteArrayList<(Context) -> List<Int>>()

    /** Adds a source of followed shows ([followedShows] asks it after My List and the watch history). Register once. */
    fun addFollowSource(source: (Context) -> List<Int>) { followSources += source }

    /**
     * The shows the current profile follows, as TMDB ids: TV shows on My List, then Trakt's followed shows,
     * then shows the user has been watching (newest first), then any [addFollowSource] source; at most [MAX_SHOWS]. The one
     * place "followed" is decided (New Episodes row, the New Episode banner, notices). Blocking (prefs).
     */
    fun followedShows(ctx: Context): List<Int> {
        val fromList = try { Tmdb.myList(ctx).filter { it.type == "tv" }.map { it.id } } catch (e: Exception) { emptyList() }
        // Trakt: watchlist shows, shows paused part way, shows watched lately (the last sync's data, no network).
        val trakt = try { Trakt.followedShowTmdbIds(ctx) } catch (e: Exception) { emptyList() }
        val watched = try { WatchHistory.recentShowTmdbIds(ctx) } catch (e: Exception) { emptyList() }
        val more = followSources.flatMap { src -> try { src(ctx) } catch (e: Exception) { emptyList() } }
        return (fromList + trakt + watched + more).filter { it > 0 }.distinct().take(MAX_SHOWS)
    }

    private fun tracked(ctx: Context): List<Int> = followedShows(ctx)

    /**
     * Worth asking TMDB again before [MAX_AGE_MS]: [s]'s next episode is past its exact instant and the copy
     * is from before it (once, right after it airs), or, with only a date, it aired by today and the copy is
     * over [DUE_RETRY_MS] old.
     */
    private fun due(s: Show): Boolean {
        val n = s.next ?: return false
        val now = System.currentTimeMillis()
        if (n.at > 0) return now >= n.at && s.fetchedAt < n.at
        return daysFromToday(n.airDate) <= 0 && now - s.fetchedAt > DUE_RETRY_MS
    }

    /** [id]'s show from the cache, else from TMDB (developer test banner). Blocking. */
    internal fun show(ctx: Context, id: Int): Show? = cachedShows(ctx)[id] ?: try { fetch(ctx, id, null) } catch (e: Exception) { null }

    // ---- refresh ----

    private fun refresh(ctx: Context, tracked: List<Int>, old: Map<Int, Show>): Map<Int, Show> {
        val now = System.currentTimeMillis()
        val shows = LinkedHashMap<Int, Show>()
        var failures = 0
        for (id in tracked) {
            val cached = old[id]
            if (cached != null && now - cached.fetchedAt < MAX_AGE_MS && !due(cached)) { shows[id] = cached; continue }
            val fetched = try { fetch(ctx, id, cached) } catch (e: Exception) { failures++; null }
            (fetched ?: cached)?.let { shows[id] = it }
        }
        if (failures > 0) Log.w(TAG, "refresh: $failures of ${tracked.size} shows failed")
        save(ctx, shows)
        // Most fetches failed (offline?): try again on the next Home visit instead of in 6 hours.
        if (failures * 2 <= tracked.size) prefs(ctx).edit().putLong("refreshed" + Profiles.suffix(ctx), now).apply()
        markFound(ctx, shows, old)
        return shows
    }

    private fun fetch(ctx: Context, id: Int, cached: Show?): Show {
        val o = Net.getJson(url(ctx, "/tv/$id", "&append_to_response=external_ids,watch/providers"))
        val last = Ep.fromTmdb(o.optJSONObject("last_episode_to_air"))
        // A recent release: look at the whole season, since a binge drop puts many episodes on one day.
        var latest = listOfNotNull(last)
        if (last != null && daysFromToday(last.airDate) >= -RECENT_DAYS) {
            try {
                val eps = Net.getJson(url(ctx, "/tv/$id/season/${last.season}")).optJSONArray("episodes")
                val same = eps?.let { a -> (0 until a.length()).mapNotNull { Ep.fromTmdb(a.optJSONObject(it)) } }
                    ?.filter { it.airDate == last.airDate }?.sortedBy { it.number }
                if (!same.isNullOrEmpty()) latest = same
            } catch (e: Exception) {
            }
        }
        // Exact instants from TVmaze (TMDB has dates only); a TMDB copy that hasn't moved on yet is corrected here.
        var next = Ep.fromTmdb(o.optJSONObject("next_episode_to_air"))
        val showName = o.optString("name")
        val imdbId = o.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") } ?: cached?.imdb
        val air = try { AirTimes.info(ctx, id, imdbId, o.optJSONObject("external_ids")?.optInt("tvdb_id")) } catch (e: Exception) { null }
        air?.prev?.let { p ->
            val hit = latest.firstOrNull { it.same(p.season, p.number) }
            val n0 = next
            if (hit != null) latest = latest.map { if (it.airDate == hit.airDate) it.withAt(p.at) else it }
            else if (n0 != null && n0.same(p.season, p.number)) { latest = listOf(n0.withAt(p.at)); next = null }
        }
        air?.next?.let { n ->
            val n0 = next
            if (n0 != null && n0.same(n.season, n.number)) next = n0.withAt(n.at)
            else if (latest.firstOrNull()?.same(n.season, n.number) == true) latest = latest.map { it.withAt(n.at) }
        }
        val soon = listOfNotNull(next, latest.firstOrNull()).filter { it.at > System.currentTimeMillis() - 86_400_000L }
        for (e in soon) Log.w("FireHome", "Airtime: $showName S${e.season}E${e.number} at ${AirTimes.stamp(e.at)} local (tvmaze)")
        val us = o.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject("US")
        val providers = us?.optJSONArray("flatrate")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optInt("provider_id") } }.orEmpty()
        val networks = o.optJSONArray("networks")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } }.orEmpty()
        return Show(
            id = id,
            name = o.optString("name"),
            year = o.optString("first_air_date").take(4).toIntOrNull(),
            overview = o.optString("overview"),
            rating = o.optDouble("vote_average", 0.0),
            poster = o.optString("poster_path").takeIf { it.isNotEmpty() && it != "null" },
            backdrop = o.optString("backdrop_path").takeIf { it.isNotEmpty() && it != "null" },
            imdb = imdbId,
            providers = providers,
            networks = networks,
            latest = latest,
            next = next,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Episodes the refresh hasn't seen before get the "New" badge. Ones from shows that were already
     * followed are also queued for the banner; the very first refresh and newly followed shows only
     * get the badge, so adding a show doesn't announce its old episodes.
     */
    private fun markFound(ctx: Context, shows: Map<Int, Show>, old: Map<Int, Show>) = synchronized(lock) {
        val firstRun = !prefs(ctx).contains("known" + Profiles.suffix(ctx))
        val known = stringSet(ctx, "known")
        val fresh = stringSet(ctx, "fresh")
        val pending = stringSet(ctx, "announce")
        val current = shows.values.mapNotNull { recentItem(it) }
        for (item in current) {
            if (item.key in known) continue
            fresh += item.key
            if (!firstRun && item.show.id in old) pending += item.key
        }
        val keys = current.map { it.key }.toSet()
        // Forget episodes that have left the window so the sets don't grow forever.
        putSet(ctx, "known", keys.toMutableSet())
        putSet(ctx, "fresh", fresh.filterTo(HashSet()) { it in keys })
        putSet(ctx, "announce", pending.filterTo(HashSet()) { it in keys })
    }

    /**
     * The show's latest release, if it came out in the last [RECENT_DAYS] days: its next episode once past
     * its exact instant (before TMDB catches up), else its latest batch unless that is still ahead today.
     */
    private fun recentItem(s: Show, now: Long = System.currentTimeMillis()): Item? {
        s.next?.takeIf { it.at in 1..now }?.let { return Item(s, it, it.number, upcoming = false) }
        val first = s.latest.firstOrNull() ?: return null
        if (!first.aired(now) || daysFromToday(first.airDate) !in -RECENT_DAYS..0) return null
        return Item(s, first, s.latest.last().number, upcoming = false)
    }

    /** What comes next within [UPCOMING_DAYS]: a latest batch still ahead today (exact time), else the next episode. */
    private fun upcomingItem(s: Show, now: Long): Item? {
        s.latest.firstOrNull()?.takeIf { it.at > now }?.let { return Item(s, it, s.latest.last().number, upcoming = true) }
        val n = s.next ?: return null
        if (n.at in 1..now || daysFromToday(n.airDate) !in 0..UPCOMING_DAYS) return null
        return Item(s, n, n.number, upcoming = true)
    }

    // ---- storage ----

    private fun cachedShows(ctx: Context): Map<Int, Show> {
        memory?.let { return it }
        val map = HashMap<Int, Show>()
        try {
            val f = File(ctx.filesDir, FILE)
            if (f.exists()) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) Show.fromJson(a.getJSONObject(i)).let { map[it.id] = it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cache read failed: $e")
        }
        memory = map
        return map
    }

    private fun save(ctx: Context, shows: Map<Int, Show>) {
        // Keep shows other profiles follow too: the file is shared, only the badges are per profile.
        val all = HashMap(cachedShows(ctx)).apply { putAll(shows) }
            .values.sortedByDescending { it.fetchedAt }.take(MAX_SHOWS * 2)
        memory = all.associateBy { it.id }
        try {
            val f = File(ctx.filesDir, FILE)
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(JSONArray().apply { all.forEach { put(it.toJson()) } }.toString())
            tmp.renameTo(f)
        } catch (e: Exception) {
            Log.w(TAG, "cache write failed: $e")
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("newepisodes", Context.MODE_PRIVATE)

    private fun stringSet(ctx: Context, name: String): MutableSet<String> =
        HashSet(prefs(ctx).getStringSet(name + Profiles.suffix(ctx), emptySet()) ?: emptySet())

    private fun putSet(ctx: Context, name: String, set: Set<String>) =
        prefs(ctx).edit().putStringSet(name + Profiles.suffix(ctx), HashSet(set)).apply()

    private fun url(ctx: Context, path: String, query: String = "") =
        "$API$path?api_key=${Tmdb.key(ctx)}&language=en-US$query"

    // ---- dates (TMDB air dates are plain yyyy-MM-dd) ----

    private fun dayFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** Whole days from today (chosen zone, DreamTime) to [date]: 0 today, -1 yesterday, 1 tomorrow. */
    fun daysFromToday(date: String): Int {
        val f = dayFormat()
        val today = DreamTime.todayYmd()   // today in the viewer's chosen zone; the air date itself never shifts
        return try {
            ((f.parse(date)!!.time - f.parse(today)!!.time) / 86_400_000L).toInt()
        } catch (e: Exception) {
            Int.MIN_VALUE / 2
        }
    }

    private fun fmt(pattern: String, date: String): String = try {
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(dayFormat().parse(date)!!)
    } catch (e: Exception) {
        date
    }
}
