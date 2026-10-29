package com.firehome.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * What the user has been watching, for the Continue Watching row (and the New Episodes row via
 * [recentShowTmdbIds]). Fed by Dream Player (what Dream OS plays itself) and AppWatch (official apps'
 * media sessions). One entry per movie or per show (a show keeps only its latest episode).
 * Stored per profile as JSON.
 */
object WatchHistory {
    class Entry(
        val type: String,          // "movie" or "episode"
        val tmdbId: Int,           // movie id, or the show's id for episodes
        val title: String,         // movie title or show name
        val episodeTitle: String?,
        val season: Int,
        val episode: Int,
        val year: Int?,
        val imdb: String?,
        val progress: Float,       // 0..1
        val positionMs: Long,
        val durationMs: Long,
        val updatedAt: Long,
        val source: String,        // [DREAM] (no dot: Dream OS's own) or the app's package
        val upNext: Boolean,       // queued next episode, not started yet
        val hidden: Boolean,       // finished or removed by the user
        val posterPath: String?,
        val backdropPath: String?,
        val stillPath: String?,
        val overview: String,
        val rating: Double,
        val deepLink: String?,
        val player: String? = null, // the source that played it, if known
    ) {
        val isShow get() = type == "episode"
        /** "tv:123" / "movie:123", same as [Title.key]. */
        val key get() = (if (isShow) "tv:" else "movie:") + tmdbId

        fun toTitle() = Title(tmdbId, if (isShow) "tv" else "movie", title, overview, posterPath, backdropPath, year?.toString() ?: "", rating)

        /** Episode still if there is one, else the show/movie backdrop, else its poster. */
        fun image(): String? = images().firstOrNull()

        /** Every landscape-card image in order of preference (the next one is tried if one fails). */
        fun images(): List<String> = listOfNotNull(
            stillPath?.let { Tmdb.IMG + "w300" + it },
            backdropPath?.let { Tmdb.IMG + "w300" + it },
            posterPath?.let { Tmdb.IMG + "w342" + it },
        )

        fun toJson(): JSONObject = JSONObject().apply {
            put("type", type); put("tmdb", tmdbId); put("title", title); put("epTitle", episodeTitle ?: "")
            put("season", season); put("episode", episode); put("year", year ?: 0); put("imdb", imdb ?: "")
            put("progress", progress.toDouble()); put("pos", positionMs); put("dur", durationMs); put("at", updatedAt)
            put("source", source); put("upNext", upNext); put("hidden", hidden)
            put("poster", posterPath ?: ""); put("backdrop", backdropPath ?: ""); put("still", stillPath ?: "")
            put("overview", overview); put("rating", rating); put("link", deepLink ?: ""); put("player", player ?: "")
        }

        fun copy(
            episodeTitle: String? = this.episodeTitle, season: Int = this.season, episode: Int = this.episode,
            progress: Float = this.progress, positionMs: Long = this.positionMs, durationMs: Long = this.durationMs,
            updatedAt: Long = this.updatedAt, source: String = this.source, upNext: Boolean = this.upNext,
            hidden: Boolean = this.hidden, stillPath: String? = this.stillPath, deepLink: String? = this.deepLink,
            player: String? = this.player,
        ) = Entry(type, tmdbId, title, episodeTitle, season, episode, year, imdb, progress, positionMs, durationMs,
            updatedAt, source, upNext, hidden, posterPath, backdropPath, stillPath, overview, rating, deepLink, player)

        companion object {
            fun fromJson(o: JSONObject) = Entry(
                o.getString("type"), o.getInt("tmdb"), o.getString("title"), o.optString("epTitle").ifEmpty { null },
                o.optInt("season"), o.optInt("episode"), o.optInt("year").takeIf { it > 0 }, o.optString("imdb").ifEmpty { null },
                o.optDouble("progress").toFloat(), o.optLong("pos"), o.optLong("dur"), o.optLong("at"),
                o.optString("source"), o.optBoolean("upNext"), o.optBoolean("hidden"),
                o.optString("poster").ifEmpty { null }, o.optString("backdrop").ifEmpty { null }, o.optString("still").ifEmpty { null },
                o.optString("overview"), o.optDouble("rating", 0.0), o.optString("link").ifEmpty { null },
                o.optString("player").ifEmpty { null },
            )
        }
    }

    /** A raw "the user is watching X" report from a tracker; [record] matches it to TMDB. */
    class Sighting(
        val source: String,
        val title: String,           // movie title or show name
        val isEpisode: Boolean?,     // null = unknown (decide from TMDB)
        val season: Int = 0,
        val episode: Int = 0,
        val episodeTitle: String? = null,
        val year: Int? = null,
        val tmdbId: Int? = null,     // movie id, or show id for episodes
        val imdb: String? = null,    // movie imdb, or show imdb for episodes
        val positionMs: Long,
        val durationMs: Long,        // 0 = unknown (filled from TMDB runtime)
        val deepLink: String? = null,
        val player: String? = null,  // the source playing it, if known
        /** Dream Player stopping, pausing or leaving: written as is, never skipped as "barely moved" (exact resume). */
        val exact: Boolean = false,
    )

    const val FINISHED = 0.92f
    /** [Entry.source] of what Dream Player plays. */
    const val DREAM = "dream"

    /** Played in Dream OS itself (any source that isn't an app's package name, older history included): resumed by the play flow. */
    fun ours(e: Entry) = '.' !in e.source

    /** [e] (a show's entry) is for an episode after S[season]E[episode]: the viewer has moved on past that one. */
    fun later(e: Entry, season: Int, episode: Int) = e.isShow && (e.season > season || e.season == season && e.episode > episode)
    private const val MAX = 40
    /** Watched this far into something, it counts toward recommendations ([watchedEnough]). */
    private const val MEANINGFUL_MS = 10 * 60_000L
    private const val TAG = "FireHome"

    private val main = Handler(Looper.getMainLooper())
    // Single thread (writes stay serialized); it exits after 30 s idle instead of living forever.
    private val io = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "watch-history").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
    private val lock = Any()

    // Progress reports that moved < 1 % and < ~60 s since the last save aren't written; the newest skipped
    // one is replayed a minute later, so the position saved when playback stops is never more than one poll old.
    private const val SAVE_EVERY_MS = 55_000L // AppWatch reports every 60 s; leave room for timer jitter
    private var pending: Pair<Context, Sighting>? = null
    private val flushPending = Runnable {
        val p = synchronized(lock) { pending.also { pending = null } }
        if (p != null) record(p.first, p.second)
    }

    /** Bumped on every change, so the Home row can tell whether it needs rebuilding. */
    @Volatile var version = 0L
        private set

    /** Main-thread callback after any change (MainActivity refreshes its row). */
    var onChanged: (() -> Unit)? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("watch", Context.MODE_PRIVATE)
    private fun prefKey(ctx: Context) = "history" + Profiles.suffix(ctx)

    private fun load(ctx: Context): MutableList<Entry> = try {
        val a = JSONArray(prefs(ctx).getString(prefKey(ctx), "[]"))
        (0 until a.length()).mapNotNull { try { Entry.fromJson(a.getJSONObject(it)) } catch (e: Exception) { null } }.toMutableList()
    } catch (e: Exception) {
        mutableListOf()
    }

    private fun save(ctx: Context, list: List<Entry>) {
        val a = JSONArray()
        list.sortedByDescending { it.updatedAt }.take(MAX).forEach { a.put(it.toJson()) }
        prefs(ctx).edit().putString(prefKey(ctx), a.toString()).apply()
        version++
        main.post { onChanged?.invoke() }
    }

    /** What the Continue Watching row shows: newest first, finished/removed ones left out. */
    fun entries(ctx: Context): List<Entry> = synchronized(lock) { load(ctx) }
        .filter { !it.hidden }.sortedByDescending { it.updatedAt }

    /**
     * Whether [e] was really watched, so it may shape recommendations: 10 minutes in, or finished
     * (an Up Next card means the episode before it was). A few minutes' sampling doesn't count.
     */
    fun watchedEnough(e: Entry) = e.upNext || e.progress >= FINISHED || e.positionMs >= MEANINGFUL_MS

    /** Everything watched, newest first, finished and removed ones included (for "Because You Watched"). */
    fun recent(ctx: Context): List<Entry> = synchronized(lock) { load(ctx) }.sortedByDescending { it.updatedAt }

    /** TMDB ids of shows watched recently (Dream Player or apps), newest first, including finished ones. */
    fun recentShowTmdbIds(ctx: Context): List<Int> = synchronized(lock) { load(ctx) }
        .filter { it.isShow }.sortedByDescending { it.updatedAt }.map { it.tmdbId }.distinct()

    /** Takes the title off the row (it comes back if the user watches it again). */
    fun remove(ctx: Context, key: String) = synchronized(lock) {
        val list = load(ctx)
        val i = list.indexOfFirst { it.key == key }
        if (i >= 0) {
            list[i] = list[i].copy(hidden = true)
            save(ctx, list)
            try { Trakt.onContinueWatchingRemoved(ctx, key) } catch (e: Exception) {} // its Trakt progress goes too
        }
    }

    /** A remote report this close after ours, for the same episode, is our own Trakt scrobble coming back. */
    private const val ECHO_MS = 3 * 60_000L

    /**
     * Trakt sync (Trakt.kt): titles watched or paused on other devices, already matched to TMDB, with
     * [Entry.updatedAt] = when that happened there. Last write wins: one replaces ours only when it's
     * newer. Finished ones leave the row (a movie) or move on to Up Next (a show), like [record].
     * A show never goes back: an earlier episode's leftover progress or finish (a paused point Trakt kept, a
     * scrobble sent late) never replaces a later episode we have, in progress, up next or finished.
     * Returns how many entries changed. Blocking (TMDB lookups for Up Next).
     */
    internal fun mergeRemote(ctx: Context, items: List<Entry>): Int {
        fun newer(e: Entry, old: Entry?) = old == null || e.updatedAt > old.updatedAt &&
            !(old.season == e.season && old.episode == e.episode && e.updatedAt - old.updatedAt < ECHO_MS) &&
            !later(old, e.season, e.episode)
        for (e in items) if (e.progress >= FINISHED) Watched.finished(ctx, e) // Trakt's finished ones count as watched
        val current = synchronized(lock) { load(ctx) }.associateBy { it.key }
        val resolved = items.filter { newer(it, current[it.key]) }.map { e ->
            when {
                e.progress < FINISHED -> e
                !e.isShow -> e.copy(hidden = true)
                else -> nextEpisode(ctx, e) ?: e.copy(hidden = true)
            }
        }
        if (resolved.isEmpty()) return 0
        synchronized(lock) {
            val list = load(ctx)
            var n = 0
            for (e in resolved) {
                val old = list.firstOrNull { it.key == e.key }
                if (!newer(e, old)) continue // checked again: a local report may have landed meanwhile
                list.removeAll { it.key == e.key }
                // Same episode as ours: keep how we play it (the add-on, the app's link).
                list.add(if (old != null && old.season == e.season && old.episode == e.episode)
                    e.copy(source = old.source, player = old.player, deepLink = old.deepLink) else e)
                n++
            }
            if (n > 0) save(ctx, list)
            return n
        }
    }

    /** Matches [s] to TMDB and saves it, on a background thread. */
    fun record(ctx: Context, s: Sighting) {
        val app = ctx.applicationContext
        io.execute {
            try {
                recordNow(app, s)
            } catch (e: Exception) {
                Log.w(TAG, "watch history: couldn't record ${s.title}: $e")
            }
        }
    }

    private fun recordNow(ctx: Context, s: Sighting) {
        if (!Tmdb.hasKey(ctx) || s.title.isBlank() && s.tmdbId == null && s.imdb == null) return
        val m = match(ctx, s) ?: run { Log.w(TAG, "watch history: no TMDB match for '${s.title}'"); return }
        val isShow = m.optString("_type") == "tv"
        val tmdbId = m.getInt("id")
        val now = System.currentTimeMillis()
        var season = s.season
        var episode = s.episode
        var epTitle = s.episodeTitle
        var still: String? = null
        var duration = s.durationMs
        if (isShow && season > 0 && episode > 0) {
            seasonEpisodes(ctx, tmdbId, season)?.firstOrNull { it.optInt("episode_number") == episode }?.let { ep ->
                if (epTitle.isNullOrBlank()) epTitle = ep.optString("name").ifEmpty { null }
                still = ep.optString("still_path").takeIf { it.isNotEmpty() && it != "null" }
                if (duration <= 0) duration = ep.optInt("runtime") * 60_000L
            }
        } else if (!isShow && duration <= 0) {
            duration = m.optInt("runtime") * 60_000L
        }
        val progress = if (duration > 0) (s.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val date = m.optString(if (isShow) "first_air_date" else "release_date")
        val base = Entry(
            type = if (isShow) "episode" else "movie", tmdbId = tmdbId,
            title = m.optString(if (isShow) "name" else "title").ifEmpty { s.title },
            episodeTitle = epTitle, season = season, episode = episode,
            year = date.take(4).toIntOrNull() ?: s.year,
            imdb = (m.optJSONObject("external_ids")?.optString("imdb_id")?.ifEmpty { null } ?: m.optString("imdb_id").ifEmpty { null })
                ?.takeIf { it.startsWith("tt") } ?: s.imdb,
            progress = progress, positionMs = s.positionMs, durationMs = duration, updatedAt = now,
            source = s.source, upNext = false, hidden = false,
            posterPath = m.optString("poster_path").takeIf { it.isNotEmpty() && it != "null" },
            backdropPath = m.optString("backdrop_path").takeIf { it.isNotEmpty() && it != "null" },
            stillPath = still, overview = m.optString("overview"), rating = m.optDouble("vote_average", 0.0),
            deepLink = s.deepLink, player = s.player,
        )
        // The Watched pill (outside [lock]: the ledger reads the history the first time).
        if (progress >= FINISHED) Watched.finished(ctx, base)
        synchronized(lock) {
            val list = load(ctx)
            val old = list.firstOrNull { it.key == base.key }
            // Keep the add-on that played it when this report doesn't know.
            val base = if (base.player == null && old?.player != null && old.source == base.source) base.copy(player = old.player) else base
            // An older episode finishing (credits still rolling after the next one was queued, a late save) never takes
            // the show back from a later one: the Up Next card, or the later episode's own spot, stays.
            if (old != null && isShow && !old.hidden && progress >= FINISHED && later(old, season, episode)) return
            // Ignore a peek (under 90 s) at something that isn't on the row yet.
            if ((old == null || old.hidden) && s.positionMs < 90_000 && progress < FINISHED) return
            // Same thing still playing and barely moved: skip the write (and the Home row rebuild it triggers).
            if (!s.exact && old != null && !old.hidden && !old.upNext && progress < FINISHED && old.progress < FINISHED &&
                old.season == season && old.episode == episode && old.source == base.source && old.player == base.player &&
                Math.abs(progress - old.progress) < 0.01f && now - old.updatedAt in 0 until SAVE_EVERY_MS
            ) {
                pending = ctx to s
                main.removeCallbacks(flushPending)
                main.postDelayed(flushPending, 61_000)
                return
            }
            pending = null
            val firstOrDone = old == null || old.hidden || progress >= FINISHED
            list.removeAll { it.key == base.key }
            when {
                progress < FINISHED -> list.add(base)
                !isShow -> list.add(base.copy(hidden = true)) // finished movie: off the row
                else -> list.add(nextEpisode(ctx, base) ?: base.copy(hidden = true))
            }
            save(ctx, list)
            // Fire OS hides Log.d: only the add/finish transitions are worth a visible line, not every poll.
            if (firstOrDone) Log.w(TAG, "watch history: ${base.title} S${season}E$episode ${(progress * 100).toInt()}% via ${s.source}")
        }
    }

    /** The episode after [e] as an "Up next" entry, if it exists and has aired. */
    private fun nextEpisode(ctx: Context, e: Entry): Entry? {
        val today = DreamTime.todayYmd(ctx)
        fun aired(ep: JSONObject) = ep.optString("air_date").let { it.isNotEmpty() && it != "null" && it <= today }
        val eps = seasonEpisodes(ctx, e.tmdbId, e.season).orEmpty()
        var next = eps.firstOrNull { it.optInt("episode_number") == e.episode + 1 }
        var season = e.season
        if (next == null) {
            season = e.season + 1
            next = seasonEpisodes(ctx, e.tmdbId, season)?.firstOrNull { it.optInt("episode_number") == 1 }
        }
        if (next == null || !aired(next)) return null
        return e.copy(
            season = season, episode = next.optInt("episode_number"),
            episodeTitle = next.optString("name").ifEmpty { null },
            stillPath = next.optString("still_path").takeIf { it.isNotEmpty() && it != "null" },
            progress = 0f, positionMs = 0, durationMs = next.optInt("runtime") * 60_000L, upNext = true,
            deepLink = null,
        )
    }

    // ---- TMDB lookups ----

    private val cache = HashMap<String, JSONObject>()

    internal fun tmdb(ctx: Context, path: String, query: String = ""): JSONObject? {
        val url = "https://api.themoviedb.org/3$path?api_key=${Tmdb.key(ctx)}&language=en-US$query"
        synchronized(cache) { cache[url]?.let { return it } }
        val json = try { Net.getJson(url) } catch (e: Exception) { return null }
        synchronized(cache) {
            if (cache.size > 200) cache.clear()
            cache[url] = json
        }
        return json
    }

    internal fun seasonEpisodes(ctx: Context, showId: Int, season: Int): List<JSONObject>? {
        val a = tmdb(ctx, "/tv/$showId/season/$season")?.optJSONArray("episodes") ?: return null
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    /** Full TMDB details (with external_ids) tagged with "_type" = "tv" or "movie". */
    internal fun details(ctx: Context, type: String, id: Int): JSONObject? =
        tmdb(ctx, "/$type/$id", "&append_to_response=external_ids")?.put("_type", type)

    private fun match(ctx: Context, s: Sighting): JSONObject? {
        val wantTv = s.isEpisode
        fun byImdb(): JSONObject? {
            val imdb = s.imdb?.takeIf { it.startsWith("tt") } ?: return null
            val f = tmdb(ctx, "/find/$imdb", "&external_source=imdb_id") ?: return null
            f.optJSONArray("tv_results")?.optJSONObject(0)?.let { return details(ctx, "tv", it.getInt("id")) }
            f.optJSONArray("tv_episode_results")?.optJSONObject(0)?.let { return details(ctx, "tv", it.getInt("show_id")) }
            f.optJSONArray("movie_results")?.optJSONObject(0)?.let { return details(ctx, "movie", it.getInt("id")) }
            return null
        }
        fun byTmdb(): JSONObject? = s.tmdbId?.let { details(ctx, if (wantTv == true) "tv" else "movie", it) }
        // A tracker may report an episode's tmdb id instead of the show's; its imdb id resolves either way.
        (if (wantTv == true) byImdb() ?: byTmdb() else byTmdb() ?: byImdb())?.let { return it }
        val q = cleanTitle(s.title)
        if (q.isEmpty()) return null
        val enc = URLEncoder.encode(q, "UTF-8")
        val results = when (wantTv) {
            true -> tmdb(ctx, "/search/tv", "&query=$enc")?.optJSONArray("results")?.let { tag(it, "tv") }
            false -> tmdb(ctx, "/search/movie", "&query=$enc" + (s.year?.let { "&year=$it" } ?: ""))?.optJSONArray("results")?.let { tag(it, "movie") }
            null -> tmdb(ctx, "/search/multi", "&query=$enc")?.optJSONArray("results")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("media_type") in setOf("tv", "movie") }
                    .onEach { it.put("_type", it.optString("media_type")) }
            }
        }.orEmpty()
        if (results.isEmpty()) return null
        val norm = norm(q)
        fun name(o: JSONObject) = o.optString(if (o.optString("_type") == "tv") "name" else "title")
        val best = results.firstOrNull { norm(name(it)) == norm && (s.year == null || yearOf(it) == s.year) }
            ?: results.firstOrNull { norm(name(it)) == norm }
            ?: results.first()
        return details(ctx, best.optString("_type"), best.getInt("id"))
    }

    private fun tag(a: JSONArray, type: String) = (0 until a.length()).map { a.getJSONObject(it).put("_type", type) }

    private fun yearOf(o: JSONObject) = o.optString(if (o.optString("_type") == "tv") "first_air_date" else "release_date").take(4).toIntOrNull()

    private fun norm(s: String) = s.lowercase(Locale.US).replace("&", "and").replace(Regex("[^a-z0-9]"), "")

    /** Strips "S1 E2"-style suffixes, years in brackets and app decorations from a title. */
    fun cleanTitle(raw: String): String = raw
        .replace(Regex("\\s*[\\(\\[]\\d{4}[\\)\\]]"), "")
        .replace(Regex("(?i)\\s*[-:·|,]?\\s*(S(eason)?\\s*\\d+\\s*[:,.·-]?\\s*E(p(isode)?)?\\s*\\d+).*$"), "")
        .trim()
}
