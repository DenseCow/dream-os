package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.HistoryEntry
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.PlayRequest
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.time.DreamTime
import com.dreamos.desktop.core.util.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

/**
 * What the viewer has been watching (fire-home WatchHistory + PlayFlow's history rules): Continue Watching, Up Next,
 * where playback starts, and the seeds of Because You Watched and Taste. One entry per movie or per show (a show keeps
 * only its latest episode), stored per profile in `watch[_<id>]`, the newest [MAX].
 *
 * Rules (STANDARDS §5):
 * - Real watching only: a title joins Continue Watching after 90 s of actual playback (the player's realWatchedMs;
 *   seeks and skips add nothing). One already on the row keeps saving as before.
 * - A show never moves back by itself: an older episode's finish, a Trakt point or a late report never replaces a
 *   later episode ([later]); an older episode started by hand plays 90 s for real before it takes the row back.
 * - Up Next: a finished episode rolls the show on to the next aired episode (the one next-episode rule, [episodeAfter]).
 * - Where playback starts is the history's call ([startPositionMs]), never a spot a page captured.
 */
object WatchHistory {
    /** Watched this far, a title counts as finished. */
    const val FINISHED = HistoryEntry.FINISHED
    const val DREAM = "dream"
    /** Real playback before a title joins Continue Watching (or an older episode takes a show back). */
    const val REAL_WATCH_MS = 90_000L
    /** Watched this far into something, it counts toward recommendations. */
    private const val MEANINGFUL_MS = 10 * 60_000L
    /** Entries kept per profile (worst case ~40 x 1 KB). */
    private const val MAX = 40
    /** A remote report this close after ours, for the same episode, is our own Trakt scrobble coming back. */
    private const val ECHO_MS = 3 * 60_000L
    /** Progress reports that moved under 1 % and under this long since the last save aren't written. */
    private const val SAVE_EVERY_MS = 55_000L

    private val lock = Any()
    private val ser = ListSerializer(HistoryEntry.serializer())
    private val _version = MutableStateFlow(0L)

    /** Bumped on every change of any profile's history. */
    val version: StateFlow<Long> get() = _version

    /** The playback side's "this episode is up before its air day" (a source decides); set by win-playback. */
    @Volatile var outEarly: ((showId: Int, season: Int, episode: Int) -> Boolean)? = null

    /**
     * The playback side's quick check for an episode still ahead by its date ([airDate] "yyyy-MM-dd" or ""): true when a
     * source is already up. Blocking and bounded (about 1 s, 10 s at worst); asked again only after a while. Set by
     * win-playback; the title page's season list uses it ([Details.episodes] with `askEarly`).
     */
    @Volatile var earlyRelease: ((showId: Int, season: Int, episode: Int, airDate: String) -> Boolean)? = null

    /** Told after a change on the IO thread that made it (Trakt follows Continue Watching removals). */
    @Volatile var onRemoved: ((key: String) -> Unit)? = null

    private fun prefs(profileId: String = DreamStore.currentProfile) = DreamStore.shared.profileFile("watch", profileId)

    private fun load(): MutableList<HistoryEntry> = (prefs().get("history", ser) ?: emptyList()).toMutableList()

    private fun save(list: List<HistoryEntry>) {
        prefs().put("history", ser, list.sortedByDescending { it.updatedAt }.take(MAX))
        _version.value++
    }

    /** Continue Watching: newest first, finished and removed ones left out. */
    fun entries(): List<HistoryEntry> = synchronized(lock) { load() }.filter { !it.hidden }.sortedByDescending { it.updatedAt }

    /** Everything watched, newest first, finished and removed ones included. */
    fun recent(): List<HistoryEntry> = synchronized(lock) { load() }.sortedByDescending { it.updatedAt }

    fun entry(key: String): HistoryEntry? = recent().firstOrNull { it.key == key }

    /** Really watched, so it may shape recommendations: 10 minutes in, finished, or its next episode queued. */
    fun watchedEnough(e: HistoryEntry) = e.upNext || e.progress >= FINISHED || e.positionMs >= MEANINGFUL_MS

    /** TMDB ids of shows watched recently, newest first, finished ones included. */
    fun recentShowIds(): List<Int> = recent().filter { it.isShow }.map { it.title.id }.distinct()

    /** [e] (a show's entry) is for an episode after S[season]E[episode]: the viewer has moved on past that one. */
    fun later(e: HistoryEntry, season: Int, episode: Int) = e.isShow && (e.season > season || e.season == season && e.episode > episode)

    /** Takes the title off the row (it comes back if the viewer watches it again). An unfinished one seeds no taste. */
    fun remove(key: String) {
        val removed = synchronized(lock) {
            val list = load()
            val i = list.indexOfFirst { it.key == key }
            if (i < 0 || list[i].hidden) return@synchronized null
            list[i] = list[i].copy(hidden = true)
            save(list)
            list[i]
        }
        if (removed == null) {
            Log.w("History", "remove: $key isn't on Continue Watching")
            return
        }
        Log.i("History", "removed ${removed.title.name} from Continue Watching")
        try { onRemoved?.invoke(key) } catch (e: Exception) {}
    }

    // ---- the player's reports ----

    /**
     * Dream Player's report for [r]. [realWatchedMs]: actual playback this session. [exact]: it stopped, paused or
     * left (written as is, never skipped as "barely moved"). Blocking (a finished episode looks up its Up Next).
     */
    fun report(r: PlayRequest, positionMs: Long, durationMs: Long, realWatchedMs: Long, exact: Boolean) {
        if (r.title.id <= 0) return
        val isShow = r.title.isShow
        val season = r.season ?: 0
        val episode = r.episode ?: 0
        if (isShow && (season <= 0 || episode <= 0)) return
        val now = System.currentTimeMillis()
        val progress = if (durationMs > 0) (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0
        var still: String? = null
        var epName = r.episodeName
        if (isShow) seasonEpisodes(r.title.id, season)?.firstOrNull { it.int("episode_number") == episode }?.let { ep ->
            if (epName.isNullOrBlank()) epName = ep.str("name").ifEmpty { null }
            still = ep.strOrNull("still_path")
        }
        val base = HistoryEntry(
            title = r.title, season = season, episode = episode, episodeName = epName, stillPath = still,
            imdbId = r.imdbId, positionMs = positionMs.coerceAtLeast(0), durationMs = durationMs.coerceAtLeast(0),
            updatedAt = now, source = DREAM,
        )
        if (progress >= FINISHED) Watched.finished(base)
        val rolled = if (isShow && progress >= FINISHED) nextEpisode(base) else null
        synchronized(lock) {
            val list = load()
            val old = list.firstOrNull { it.key == base.key }
            // An older episode finishing (credits rolling after the next one was queued, a late save) never takes the
            // show back from a later one.
            if (old != null && isShow && !old.hidden && progress >= FINISHED && later(old, season, episode)) return
            // Real watching only: a title not on the row joins after 90 s of actual playback.
            if ((old == null || old.hidden) && realWatchedMs < REAL_WATCH_MS && progress < FINISHED) return
            // An older episode started by hand plays 90 s for real before it takes the row back.
            if (old != null && !old.hidden && isShow && later(old, season, episode) && realWatchedMs < REAL_WATCH_MS) return
            // Same thing still playing and barely moved: skip the write (and the row rebuild it triggers).
            if (!exact && old != null && !old.hidden && !old.upNext && progress < FINISHED && old.progress < FINISHED &&
                old.season == season && old.episode == episode && kotlin.math.abs(progress - old.progress) < 0.01 &&
                now - old.updatedAt in 0 until SAVE_EVERY_MS
            ) return
            val firstOrDone = old == null || old.hidden || progress >= FINISHED
            list.removeAll { it.key == base.key }
            when {
                progress < FINISHED -> list.add(base)
                !isShow -> list.add(base.copy(hidden = true)) // finished movie: off the row
                else -> list.add(rolled ?: base.copy(hidden = true))
            }
            save(list)
            if (firstOrDone) Log.i("History", "${base.title.name} S${season}E$episode ${(progress * 100).toInt()}%")
        }
    }

    /**
     * Trakt sync: entries watched or paused elsewhere, already matched to TMDB, [HistoryEntry.updatedAt] = when that
     * happened there. Last write wins, a show never goes back ([later]), our own scrobble's echo is ignored. Finished
     * ones leave the row (a movie) or move on to Up Next (a show). Returns how many changed. Blocking.
     */
    fun mergeRemote(items: List<HistoryEntry>): Int {
        fun newer(e: HistoryEntry, old: HistoryEntry?) = old == null || e.updatedAt > old.updatedAt &&
            !(old.season == e.season && old.episode == e.episode && e.updatedAt - old.updatedAt < ECHO_MS) &&
            !later(old, e.season, e.episode)
        for (e in items) if (e.progress >= FINISHED) Watched.finished(e)
        val current = synchronized(lock) { load() }.associateBy { it.key }
        val resolved = items.filter { newer(it, current[it.key]) }.map { e ->
            when {
                e.progress < FINISHED -> e
                !e.isShow -> e.copy(hidden = true)
                else -> nextEpisode(e) ?: e.copy(hidden = true)
            }
        }
        if (resolved.isEmpty()) return 0
        synchronized(lock) {
            val list = load()
            var n = 0
            for (e in resolved) {
                val old = list.firstOrNull { it.key == e.key }
                if (!newer(e, old)) continue
                list.removeAll { it.key == e.key }
                list.add(e)
                n++
            }
            if (n > 0) save(list)
            return n
        }
    }

    /** The episode after [e] as an Up Next entry, if it exists and has aired (or is up early). */
    private fun nextEpisode(e: HistoryEntry): HistoryEntry? {
        val (season, next) = episodeAfter(e.title.id, e.season, e.episode) ?: return null
        return e.copy(
            season = season, episode = next.int("episode_number"), episodeName = next.str("name").ifEmpty { null },
            stillPath = next.strOrNull("still_path"), positionMs = 0, durationMs = next.int("runtime") * 60_000L,
            upNext = true, hidden = false,
        )
    }

    // ---- where playback starts, what Play says ----

    /**
     * Where the history says [r] starts (ms): its saved spot while in progress; 0 once finished, taken off Continue
     * Watching, or the show moved on past that episode (also when never played here). A spot a page captured loses.
     */
    fun startPositionMs(r: PlayRequest): Long {
        if (r.fromStart) return 0L
        val e = entry(r.title.key) ?: return 0L
        if (e.source != DREAM && e.source != "trakt") return 0L
        if (r.title.isShow) {
            val s = r.season ?: 0
            val ep = r.episode ?: 0
            if (later(e, s, ep)) return 0L
            if (e.season != s || e.episode != ep) return 0L
        }
        if (e.hidden || e.upNext || e.progress >= FINISHED) return 0L
        return if (e.progress > 0) e.positionMs.coerceAtLeast(0) else 0L
    }

    /**
     * A show the viewer caught up on (finished its latest episode with nothing after it yet, so it left Continue
     * Watching): the episode out after it now (aired, or out early with a source), as an Up Next entry, so Play jumps
     * there ("Play S2 E1"). Null when the show is still on the row, the viewer removed it from the row (that one starts
     * fresh at S1 E1), or nothing newer is out (fire-home WatchHistory.caughtUpNext). Blocking (TMDB, cached).
     */
    fun caughtUpNext(key: String): HistoryEntry? {
        val e = entry(key) ?: return null
        if (!e.isShow || !e.hidden || e.upNext || e.progress < FINISHED || e.season <= 0 || e.episode <= 0) return null
        return nextEpisode(e)
    }

    /**
     * The entry Play goes by for [show] (the one rule on all three apps): its Continue Watching entry (in progress:
     * Resume it; Up Next: play it), else a caught-up show's newer episode ([caughtUpNext]); null: S1 E1. A show taken
     * off the row starts fresh. Blocking.
     */
    fun playEntry(show: Title): HistoryEntry? =
        entries().firstOrNull { it.key == show.key && it.season > 0 } ?: caughtUpNext(show.key)

    /** What Play opens for [show]: [playEntry]'s episode, else S1 E1. Blocking. */
    fun nextFor(show: Title): PlayRequest {
        val e = playEntry(show) ?: return PlayRequest(show, season = 1, episode = 1)
        return PlayRequest(show, e.imdbId, e.season, e.episode, e.episodeName)
    }

    /** A title's main button: "Play", "Resume", "Play S1 E1", "Resume S2 E4", "Play S2 E1" (caught up, a new one out). */
    fun playLabel(t: Title): String {
        if (!t.isShow) {
            val e = entries().firstOrNull { it.key == t.key }
            return if (e != null && e.progress > 0 && e.progress < FINISHED) "Resume" else "Play"
        }
        val next = playEntry(t) ?: return "Play S1 E1"
        val verb = if (next.upNext || next.progress <= 0) "Play" else "Resume"
        return "$verb S${next.season} E${next.episode}"
    }

    // ---- the one next-episode rule ----

    /**
     * The aired episode after [season] [episode] of [showId] and the season it's in: the next one in that season, else
     * the lowest episode of the next season the show lists (specials never count). Null at the end of the series or
     * when it hasn't aired (an episode up early counts as aired). Continue Watching, the player and post-play share it.
     */
    fun episodeAfter(showId: Int, season: Int, episode: Int): Pair<Int, JsonObject>? {
        val today = DreamTime.todayYmd()
        fun aired(ep: JsonObject) = ep.str("air_date").let { it.isNotEmpty() && it <= today } ||
            outEarly?.invoke(showId, ep.int("season_number", season), ep.int("episode_number")) == true
        var s = season
        var next = seasonEpisodes(showId, season)?.firstOrNull { it.int("episode_number") == episode + 1 }
        if (next == null) {
            s = details(MediaKind.TV, showId).a("seasons").objects().map { it.int("season_number") }.filter { it > season }.minOrNull()
                ?: return null
            next = seasonEpisodes(showId, s)?.minByOrNull { it.int("episode_number") }
        }
        if (next == null || !aired(next)) return null
        return s to next
    }

    // ---- TMDB lookups (bounded: a season's JSON runs to tens of KB; the newest 256, ~10 MB) ----

    private val cache = object : LinkedHashMap<String, Pair<Long, JsonObject>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, JsonObject>>?) = size > 256
    }
    private const val TTL = 3 * 3_600_000L

    fun tmdb(path: String, query: String = ""): JsonObject? {
        val url = "${Tmdb.API}$path?api_key=${Tmdb.key()}&language=en-US$query"
        synchronized(cache) { cache[url]?.takeIf { System.currentTimeMillis() - it.first < TTL }?.let { return it.second } }
        val json = try { Net.getJson(url) } catch (e: Exception) { return null }
        synchronized(cache) { cache[url] = System.currentTimeMillis() to json }
        return json
    }

    fun seasonEpisodes(showId: Int, season: Int): List<JsonObject>? = tmdb("/tv/$showId/season/$season")?.a("episodes")?.objects()

    /** Full TMDB details with external_ids; null when TMDB couldn't be reached. */
    fun details(type: MediaKind, id: Int): JsonObject? = tmdb("/${type.path}/$id", "&append_to_response=external_ids")

    /** Drops the TMDB lookups (a new key). */
    fun clearCache() = synchronized(cache) { cache.clear() }
}
