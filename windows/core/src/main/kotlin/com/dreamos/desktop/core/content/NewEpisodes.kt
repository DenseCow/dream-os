package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.HistoryEntry
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.NewEpisodeInfo
import com.dreamos.desktop.core.model.NewEpisodesSnapshot
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.time.DreamTime
import com.dreamos.desktop.core.util.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * New Episodes (fire-home NewEpisodes.kt): recently aired and upcoming episodes of the shows the viewer follows (TV
 * shows on My List, Trakt's followed shows, shows from the watch history; at most [MAX_SHOWS]). Show data comes from
 * TMDB (exact instants from TVmaze through [airInfo]) and is cached [MAX_AGE_MS] in `new_episodes`; which episodes are
 * "New" (not yet seen) and which still need their banner is per profile. Worst case: 80 shows x ~2 KB.
 * Everything that touches the network blocks: IO threads.
 */
object NewEpisodes {
    const val MAX_AGE_MS = 6 * 60 * 60_000L
    /** A show whose cached next episode aired by today is fetched again this often (TMDB moves it on its air date). */
    private const val DUE_RETRY_MS = 60 * 60_000L
    const val RECENT_DAYS = 7
    const val UPCOMING_DAYS = 30
    const val MAX_SHOWS = 40

    /** One episode: [airDate] the local air day, [at] its exact instant (epoch ms, TVmaze), 0 when only the date is known. */
    @Serializable
    data class Ep(val season: Int, val number: Int, val name: String = "", val airDate: String = "", val still: String? = null,
                  val overview: String = "", val at: Long = 0) {
        fun withAt(t: Long) = copy(airDate = DreamTime.ymd(t), at = t)
        fun aired(now: Long = System.currentTimeMillis()) = if (at > 0) at <= now else (DreamTime.daysFromToday(airDate, now) ?: 1) <= 0
        fun same(season: Int, number: Int) = this.season == season && this.number == number

        companion object {
            fun fromTmdb(o: JsonObject?): Ep? {
                if (o == null) return null
                val date = o.str("air_date").takeIf { it.length == 10 } ?: return null
                return Ep(o.int("season_number"), o.int("episode_number"), o.str("name"), date, o.strOrNull("still_path"), o.str("overview"))
            }
        }
    }

    @Serializable
    data class Show(
        val id: Int, val name: String, val year: Int? = null, val overview: String = "", val rating: Double = 0.0,
        val poster: String? = null, val backdrop: String? = null, val imdb: String? = null,
        val providers: List<Int> = emptyList(), val networks: List<String> = emptyList(),
        /** Episodes released together on the most recent air date (a whole season for binge drops). */
        val latest: List<Ep> = emptyList(),
        val next: Ep? = null, val fetchedAt: Long = 0,
    ) {
        fun title() = Title(id, MediaKind.TV, name, overview, poster, backdrop, year?.toString() ?: "", rating)
    }

    /** One card: a released episode (or batch), or an upcoming one. */
    class Item(val show: Show, val ep: Ep, val lastNumber: Int, val upcoming: Boolean) {
        val key get() = "${show.id}:s${ep.season}e${ep.number}"

        /** "S2  ·  E5  ·  Aired yesterday", "S1  ·  E1–8  ·  Aired today" (the Fire Stick's words), "S3  ·  E1  ·  Tonight at 9:00 PM". */
        fun badge(now: Long = System.currentTimeMillis()): String {
            val eps = if (lastNumber > ep.number) "E${ep.number}–$lastNumber" else "E${ep.number}"
            val days = DreamTime.daysFromToday(ep.airDate, now) ?: 0
            val time = if (upcoming) when {
                ep.at > 0 -> DreamTime.airLabel(ep.at, now)
                days <= 0 -> "Tonight"
                days == 1 -> "Tomorrow"
                days < 7 -> DreamTime.formatDay(ep.airDate, "EEE") ?: ep.airDate
                else -> DreamTime.formatDay(ep.airDate, "MMM d") ?: ep.airDate
            } else when {
                days >= 0 -> "Aired today"
                days == -1 -> "Aired yesterday"
                else -> "Aired " + (DreamTime.formatDay(ep.airDate, "EEE") ?: ep.airDate)
            }
            return "S${ep.season}  ·  $eps  ·  $time"
        }

        fun info() = NewEpisodeInfo(show.title(), ep.season, ep.number, lastNumber, ep.name, ep.airDate, ep.at.takeIf { it > 0 },
            ep.still, ep.overview, upcoming)
    }

    class Snapshot(val recent: List<Item>, val upcoming: List<Item>, val announce: List<Item>, val followed: List<Int>, val nextAt: Long = 0) {
        val isEmpty get() = recent.isEmpty() && upcoming.isEmpty()
        fun model() = NewEpisodesSnapshot(recent.map { it.info() }, upcoming.map { it.info() }, followed)
    }

    /** TVmaze's exact air instants for a show (the previous and next episode), set to AirTimes by the app wiring. */
    class AirEp(val season: Int, val number: Int, val at: Long)
    class AirInfo(val prev: AirEp?, val next: AirEp?)
    @Volatile var airInfo: ((showId: Int, imdb: String?, tvdb: Int?) -> AirInfo?)? = null

    private val refreshing = AtomicBoolean(false)
    private val lock = Any()
    @Volatile private var memory: Map<Int, Show>? = null
    private val showSer = ListSerializer(Show.serializer())

    private fun shared() = DreamStore.shared.file("new_episodes")
    private fun mine() = DreamStore.shared.profileFile("newepisodes")

    fun isStale() = System.currentTimeMillis() - mine().getLong("refreshed") > MAX_AGE_MS

    /**
     * Cached episodes, refreshed from TMDB first when stale or when the followed shows changed (and [refresh] is set).
     * [Snapshot.announce] lists episodes that turned up since the last refresh and haven't been announced; call
     * [announced] once shown. Blocking.
     */
    fun load(refresh: Boolean = true): Snapshot {
        val tracked = followedShows()
        var shows = cachedShows()
        if (refresh && tracked.isNotEmpty() && (isStale() || tracked.any { it !in shows || shows[it]?.let(::due) == true })) {
            if (refreshing.compareAndSet(false, true)) {
                try { shows = refresh(tracked, shows) } finally { refreshing.set(false) }
            }
        }
        val followed = shows.filterKeys { it in tracked }.values
        val now = System.currentTimeMillis()
        // Caught up (the drop's last episode, or a later one, finished): not new to this viewer until a newer one airs.
        val history = try { WatchHistory.recent() } catch (e: Exception) { emptyList() }
        fun unseen(i: Item) = !caughtUp(i.show.id, i.ep.season, i.lastNumber, history)
        val recent = followed.mapNotNull { recentItem(it, now) }.filter(::unseen)
            .sortedWith(compareByDescending<Item> { it.ep.airDate }.thenByDescending { it.ep.at }.thenBy { it.show.name })
        val upcoming = followed.mapNotNull { upcomingItem(it, now) }.filter(::unseen)
            .filter { up -> recent.none { it.key == up.key } }.sortedWith(compareBy<Item> { it.ep.airDate }.thenBy { it.ep.at })
        val pending = set("announce")
        val nextAt = upcoming.mapNotNull { it.ep.at.takeIf { t -> t > now } }.minOrNull() ?: 0L
        Watched.badged = recent.filter { isNew(it) }.map { it.show.id }.toSet()
        return Snapshot(recent, upcoming, recent.filter { it.key in pending }, tracked, nextAt)
    }

    /**
     * The viewer is past S[season]E[episode] of [show], so it isn't a new episode to them (fire-home
     * NewEpisodes.caughtUp, the iPhone's NewEpisodes.caughtUp): they finished it or a later one ([Watched], Trakt's
     * history included), or their watch history is on a later episode (in progress or Up Next), or it holds this very
     * episode finished or removed from Continue Watching. The one rule every "new episode" surface goes by (New
     * Episodes for You, the banner, NEW EPISODE badges, the general New Episodes rows). Local and instant.
     */
    fun caughtUp(show: Int, season: Int, episode: Int, history: List<HistoryEntry> = WatchHistory.recent()): Boolean =
        past(Watched.furthest(show), history.firstOrNull { it.isShow && it.title.id == show }, season, episode)

    /** [caughtUp] on its inputs: [finished] the latest finished episode (season to number), [entry] the show's history entry. */
    fun past(finished: Pair<Int, Int>?, entry: HistoryEntry?, season: Int, episode: Int): Boolean {
        if (finished != null && (finished.first > season || finished.first == season && finished.second >= episode)) return true
        val e = entry ?: return false
        if (WatchHistory.later(e, season, episode)) return true
        return e.season == season && e.episode == episode && !e.upNext && (e.hidden || e.progress >= WatchHistory.FINISHED)
    }

    /** Forgets the in-memory show cache (tests). */
    internal fun clearCache() { memory = null }

    fun isNew(item: Item) = !item.upcoming && item.key in set("fresh")
    fun isNew(key: String) = key in set("fresh")

    /** Clears the "NEW" badge once the viewer has seen or opened the episode. */
    fun markSeen(key: String) = synchronized(lock) {
        val fresh = set("fresh")
        if (fresh.remove(key)) putSet("fresh", fresh)
    }

    /** The banner for these has been shown; don't show it again. */
    fun announced(keys: Collection<String>) = synchronized(lock) {
        val pending = set("announce")
        if (pending.removeAll(keys.toSet())) putSet("announce", pending)
    }

    // ---- which shows ----

    private val followSources = CopyOnWriteArrayList<() -> List<Int>>()

    /** Adds a source of followed shows (Trakt's watchlist and paused shows). Register once. */
    fun addFollowSource(source: () -> List<Int>) { followSources += source }

    /** The shows the current profile follows: My List shows, then other sources (Trakt), then watched shows; at most [MAX_SHOWS]. */
    fun followedShows(): List<Int> {
        val fromList = MyListStore.items().filter { it.isShow }.map { it.id }
        val more = followSources.flatMap { src -> try { src() } catch (e: Exception) { emptyList() } }
        val watched = WatchHistory.recentShowIds()
        return (fromList + more + watched).filter { it > 0 }.distinct().take(MAX_SHOWS)
    }

    private fun due(s: Show): Boolean {
        val n = s.next ?: return false
        val now = System.currentTimeMillis()
        if (n.at > 0) return now >= n.at && s.fetchedAt < n.at
        return (DreamTime.daysFromToday(n.airDate) ?: 1) <= 0 && now - s.fetchedAt > DUE_RETRY_MS
    }

    // ---- refresh ----

    private fun refresh(tracked: List<Int>, old: Map<Int, Show>): Map<Int, Show> {
        val now = System.currentTimeMillis()
        val todo = tracked.filter { id -> old[id].let { it == null || now - it.fetchedAt >= MAX_AGE_MS || due(it) } }
        val fetched = RowPaging.all(*todo.map { id -> { try { fetch(id, old[id]) } catch (e: Exception) { null } } }.toTypedArray())
        val byId = todo.zip(fetched).toMap()
        val shows = LinkedHashMap<Int, Show>()
        for (id in tracked) (byId[id] ?: old[id])?.let { shows[id] = it }
        val failures = fetched.count { it == null }
        if (failures > 0) Log.w("NewEpisodes", "refresh: $failures of ${todo.size} shows failed")
        save(shows)
        // Most fetches failed (offline?): try again on the next Home visit instead of in 6 hours.
        if (failures * 2 <= todo.size) mine().putLong("refreshed", now)
        markFound(shows, old)
        return shows
    }

    private fun url(path: String, query: String = "") = "${Tmdb.API}$path?api_key=${Tmdb.key()}&language=en-US$query"

    private fun fetch(id: Int, cached: Show?): Show {
        val o = Net.getJson(url("/tv/$id", "&append_to_response=external_ids,watch/providers"))
        val last = Ep.fromTmdb(o.o("last_episode_to_air"))
        var latest = listOfNotNull(last)
        // A recent release: the whole season, since a binge drop puts many episodes on one day.
        if (last != null && (DreamTime.daysFromToday(last.airDate) ?: -99) >= -RECENT_DAYS) {
            try {
                val same = Net.getJson(url("/tv/$id/season/${last.season}")).a("episodes").objects().mapNotNull { Ep.fromTmdb(it) }
                    .filter { it.airDate == last.airDate }.sortedBy { it.number }
                if (same.isNotEmpty()) latest = same
            } catch (e: Exception) {}
        }
        var next = Ep.fromTmdb(o.o("next_episode_to_air"))
        val ext = o.o("external_ids")
        val imdbId = ext.str("imdb_id").takeIf { it.startsWith("tt") } ?: cached?.imdb
        val air = try { airInfo?.invoke(id, imdbId, ext.int("tvdb_id").takeIf { it > 0 }) } catch (e: Exception) { null }
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
        val providers = o.o("watch/providers").o("results").o("US").a("flatrate").objects().map { it.int("provider_id") }
        val networks = o.a("networks").objects().map { it.str("name") }
        return Show(id, o.str("name"), o.str("first_air_date").take(4).toIntOrNull(), o.str("overview"), o.dbl("vote_average"),
            o.strOrNull("poster_path"), o.strOrNull("backdrop_path"), imdbId, providers, networks, latest, next, System.currentTimeMillis())
    }

    /**
     * Episodes the refresh hasn't seen before get the "NEW" badge. Ones from shows already followed are also queued for
     * the banner; the very first refresh and newly followed shows only get the badge (adding a show doesn't announce
     * its old episodes).
     */
    private fun markFound(shows: Map<Int, Show>, old: Map<Int, Show>) = synchronized(lock) {
        val firstRun = !mine().contains("known")
        val known = set("known")
        val fresh = set("fresh")
        val pending = set("announce")
        val current = shows.values.mapNotNull { recentItem(it) }
        for (item in current) {
            if (item.key in known) continue
            fresh += item.key
            if (!firstRun && item.show.id in old) pending += item.key
        }
        val keys = current.map { it.key }.toSet()
        mine().edit {
            putStringList("known", keys.toList())
            putStringList("fresh", fresh.filter { it in keys })
            putStringList("announce", pending.filter { it in keys })
        }
    }

    /** The show's latest release from the last [RECENT_DAYS] days: its next episode once past its exact instant, else its latest batch. */
    private fun recentItem(s: Show, now: Long = System.currentTimeMillis()): Item? {
        s.next?.takeIf { it.at in 1..now }?.let { return Item(s, it, it.number, upcoming = false) }
        val first = s.latest.firstOrNull() ?: return null
        if (!first.aired(now) || (DreamTime.daysFromToday(first.airDate, now) ?: -99) !in -RECENT_DAYS..0) return null
        return Item(s, first, s.latest.last().number, upcoming = false)
    }

    /** What comes next within [UPCOMING_DAYS]: a latest batch still ahead today, else the next episode. */
    private fun upcomingItem(s: Show, now: Long): Item? {
        s.latest.firstOrNull()?.takeIf { it.at > now }?.let { return Item(s, it, s.latest.last().number, upcoming = true) }
        val n = s.next ?: return null
        if (n.at in 1..now || (DreamTime.daysFromToday(n.airDate, now) ?: -1) !in 0..UPCOMING_DAYS) return null
        return Item(s, n, n.number, upcoming = true)
    }

    // ---- storage ----

    private fun cachedShows(): Map<Int, Show> {
        memory?.let { return it }
        val map = (shared().get("shows", showSer) ?: emptyList()).associateBy { it.id }
        memory = map
        return map
    }

    private fun save(shows: Map<Int, Show>) {
        // Keep shows other profiles follow too: the file is shared, only the badges are per profile.
        val all = HashMap(cachedShows()).apply { putAll(shows) }.values.sortedByDescending { it.fetchedAt }.take(MAX_SHOWS * 2)
        memory = all.associateBy { it.id }
        shared().put("shows", showSer, all)
    }

    private fun set(name: String): MutableSet<String> = mine().getStringList(name).toHashSet()
    private fun putSet(name: String, set: Set<String>) = mine().putStringList(name, set.toList())
}
