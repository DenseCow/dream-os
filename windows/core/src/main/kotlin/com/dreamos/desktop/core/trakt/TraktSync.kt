package com.dreamos.desktop.core.trakt

import com.dreamos.desktop.core.content.MyListStore
import com.dreamos.desktop.core.content.Tmdb
import com.dreamos.desktop.core.content.WatchHistory
import com.dreamos.desktop.core.content.Watched
import com.dreamos.desktop.core.model.HistoryEntry
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.util.*
import kotlinx.serialization.json.*
import java.io.IOException

/**
 * Trakt's sync, queue and hooks (fire-home Trakt.kt "Sync", "State and queue", "My List and Continue Watching hooks";
 * the iPhone's TraktSync.swift). Work for one profile ([pid] arguments); a sync that finds the profile switched stops
 * and keeps its marks, so the next one pulls again.
 */
internal object TraktSync {
    private const val WATCHLIST_PAGE = 500
    private const val WATCHLIST_PAGES = 20
    private const val QUEUE_MAX = 200
    private const val LEDGER_MAX = 300
    /** A pushed watchlist / removal / queued change waits this long before the sync after playback. */
    private const val AFTER_PLAY_MS = 20_000L

    private const val TRAKT = "trakt"

    private val pid get() = DreamStore.currentProfile

    // =====================================================================================
    // Triggers
    // =====================================================================================

    /** A sync if the last one is old or something changed since. Returns at once (the state is read on the sync thread). */
    fun syncIfDue(reason: String) {
        if (!Trakt.signedIn() || Trakt.syncing.get()) return
        val p = pid
        Trakt.syncer.execute {
            val st = TraktVault.state(p)
            if (st.dirty || System.currentTimeMillis() - st.at >= Trakt.SYNC_EVERY_MS) syncNow(p, reason)
        }
    }

    fun sync(p: String, reason: String, force: Boolean) {
        Trakt.syncer.execute {
            if (force) markDirty(p)
            syncNow(p, reason)
        }
    }

    /** A sync 20 s from now (after a stop went through). */
    fun syncSoon(p: String, reason: String) {
        if (!Trakt.background) return
        Trakt.timer.schedule({ Trakt.syncer.execute { syncNow(p, reason) } }, AFTER_PLAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    // =====================================================================================
    // State and queue
    // =====================================================================================

    private inline fun editState(p: String, edit: (TraktState) -> TraktState) = synchronized(TraktVault.store) {
        if (TraktVault.tokens(p) == null) return@synchronized // signed out meanwhile: nothing to keep
        TraktVault.setState(p, edit(TraktVault.state(p)))
    }

    fun markDirty(p: String) = editState(p) { it.copy(dirty = true) }

    /** Adds [op] (newest wins for the same title and kind), at most [QUEUE_MAX] kept. */
    fun queue(p: String, op: TraktOp) = synchronized(TraktVault.store) {
        if (TraktVault.tokens(p) == null) return@synchronized
        val o = op.copy(id = System.nanoTime())
        val q = TraktVault.queue(p).filter { it.sameKey != o.sameKey } + o
        TraktVault.setQueue(p, q.takeLast(QUEUE_MAX))
    }

    /** Removes a queued paused point for [item] (the scrobble that just went through says more). */
    fun dropQueuedProgress(p: String, item: TraktItem) {
        val same = TraktOp("progress", item = item).sameKey
        synchronized(TraktVault.store) {
            val q = TraktVault.queue(p)
            val kept = q.filter { it.sameKey != same }
            if (kept.size != q.size) TraktVault.setQueue(p, kept)
        }
    }

    private fun dropFromQueue(p: String, ids: Set<Long>) {
        if (ids.isEmpty()) return
        synchronized(TraktVault.store) { TraktVault.setQueue(p, TraktVault.queue(p).filter { it.id !in ids }) }
    }

    /**
     * A queued paused point that's out of date: Trakt stamps it with the time it's sent, so sent late it would look like
     * the newest thing watched and pull Continue Watching back to it (an older episode, an older spot). Stale when the
     * watch history here has moved on since: a later episode of the show, or the same title saved again more than
     * 60 s after the pause.
     */
    fun staleProgress(p: String, o: TraktOp): Boolean {
        if (o.op != "progress" || p != pid) return false
        val item = o.item ?: return false
        if (item.tmdb <= 0) return false
        val e = WatchHistory.recent().firstOrNull { it.key == item.key } ?: return false
        if (!item.movie && WatchHistory.later(e, item.season, item.number)) return true
        return e.updatedAt > (o.`when` ?: 0) + 60_000
    }

    private val flushLock = Any()

    /**
     * Sends what's queued, oldest first; stops at the first network failure (the rest waits for the next try). Ops
     * Trakt refuses for good (unknown title, full watchlist) are dropped. One flush at a time: nothing is sent twice.
     */
    fun flush(p: String): Boolean = synchronized(flushLock) { flushLocked(p) }

    private fun flushLocked(p: String): Boolean {
        val q = TraktVault.queue(p)
        if (q.isEmpty()) return true
        val done = HashSet<Long>()
        var allSent = true
        try {
            for (o in q) {
                if (staleProgress(p, o)) {
                    done += o.id
                    Log.i("Trakt", "queued paused point dropped (watched further here since)")
                    continue
                }
                val r = sendOp(p, o)
                if (r == null || r.transient) { allSent = false; break }
                done += o.id
                if (!r.ok && r.code != 409) Log.w("Trakt", "queued ${o.op} not accepted (${r.code}), dropped")
                if (r.ok) afterOp(p, o)
            }
        } catch (e: Exception) {
            // Whatever stopped it, what already went out is never sent again.
            allSent = false
        }
        dropFromQueue(p, done)
        if (done.isNotEmpty()) Log.i("Trakt", "sent ${done.size} queued change${if (done.size == 1) "" else "s"}")
        return allSent
    }

    private fun sendOp(p: String, o: TraktOp): Reply? = when (o.op) {
        "history" -> {
            val item = o.item ?: return Reply(204, "", 0)
            val at = TraktTime.iso(o.at ?: System.currentTimeMillis())
            val body = buildJsonObject {
                if (item.movie) putJsonArray("movies") {
                    addJsonObject { putJsonObject("ids") { put("tmdb", item.tmdb) }; put("watched_at", at) }
                } else putJsonArray("shows") {
                    addJsonObject {
                        putJsonObject("ids") { put("tmdb", item.tmdb) }
                        putJsonArray("seasons") {
                            addJsonObject {
                                put("number", item.season)
                                putJsonArray("episodes") { addJsonObject { put("number", item.number); put("watched_at", at) } }
                            }
                        }
                    }
                }
            }
            Trakt.authed(p, "POST", "/sync/history", body)
        }
        "progress" -> {
            val item = o.item ?: return Reply(204, "", 0)
            Trakt.authed(p, "POST", "/scrobble/pause", JsonObject(item.json() + ("progress" to JsonPrimitive(o.progress ?: 0.0))))
        }
        "wl_add", "wl_remove" -> Trakt.authed(p, "POST", if (o.op == "wl_add") "/sync/watchlist" else "/sync/watchlist/remove",
            watchlistBody(listOf(o.key.orEmpty())))
        "pb_remove" -> Trakt.authed(p, "DELETE", "/sync/playback/${o.pbId ?: 0}").let { r ->
            if (r != null && r.code == 404) Reply(204, "", 0) else r // already gone
        }
        else -> Reply(204, "", 0)
    }

    /** A pushed watchlist change: the snapshot of what's on Trakt follows. */
    private fun afterOp(p: String, o: TraktOp) {
        if (o.op != "wl_add" && o.op != "wl_remove") return
        val key = o.key ?: return
        editState(p) { st ->
            val snap = st.wl ?: return@editState st
            st.copy(wl = if (o.op == "wl_add") (snap.toSet() + key).toList() else snap.filter { it != key })
        }
    }

    private fun watchlistBody(keys: Collection<String>): JsonObject {
        val movies = ArrayList<JsonObject>()
        val shows = ArrayList<JsonObject>()
        for (k in keys) {
            val id = k.substringAfter(':').toIntOrNull() ?: continue
            val o = buildJsonObject { putJsonObject("ids") { put("tmdb", id) } }
            if (k.startsWith("movie:")) movies += o else if (k.startsWith("tv:")) shows += o
        }
        return buildJsonObject { put("movies", JsonArray(movies)); put("shows", JsonArray(shows)) }
    }

    // =====================================================================================
    // My List and Continue Watching hooks
    // =====================================================================================

    /** MyListStore.toggle: a title was added to or removed from My List here. Returns at once. */
    fun onMyListChanged(t: Title, inList: Boolean) {
        if (t.id <= 0 || !Trakt.signedIn()) return
        val p = pid
        val now = System.currentTimeMillis()
        Trakt.io.execute {
            try {
                editState(p) { st ->
                    var ledger = st.ledger + (t.key to TraktMark(inList, now))
                    if (ledger.size > LEDGER_MAX) ledger = ledger.entries.sortedByDescending { it.value.at }.take(LEDGER_MAX).associate { it.key to it.value }
                    st.copy(ledger = ledger)
                }
                queue(p, TraktOp(if (inList) "wl_add" else "wl_remove", key = t.key, at = now))
                flush(p)
            } catch (e: Exception) { Log.w("Trakt", "My List change not sent yet: ${e.javaClass.simpleName}") }
        }
    }

    /** WatchHistory.remove: the title left Continue Watching here; its Trakt paused point goes too. Returns at once. */
    fun onContinueWatchingRemoved(key: String) {
        if (!Trakt.signedIn()) return
        val p = pid
        Trakt.io.execute {
            try {
                var id = 0L
                editState(p) { st ->
                    id = st.pb[key] ?: 0L
                    if (id > 0) st.copy(pb = st.pb - key) else st
                }
                if (id > 0) {
                    queue(p, TraktOp("pb_remove", pbId = id))
                    flush(p)
                }
            } catch (e: Exception) { Log.w("Trakt", "paused point removal not sent yet: ${e.javaClass.simpleName}") }
        }
    }

    // =====================================================================================
    // Sync
    // =====================================================================================

    /** Blocking, on the sync thread. Signed out or another profile current: nothing. */
    fun syncNow(p: String, reason: String) {
        if (pid != p || !Trakt.signedIn(p)) return
        if (!Trakt.syncing.compareAndSet(false, true)) return
        Trakt.changed()
        val started = System.currentTimeMillis()
        var ok = false
        try {
            if (!flush(p)) { Log.w("Trakt", "sync ($reason) waits: couldn't reach Trakt"); return }
            val r = Trakt.authed(p, "GET", "/sync/last_activities") ?: return
            if (!r.ok) { Log.w("Trakt", "sync ($reason) waits: Trakt answered ${r.code}"); return }
            val la = r.obj() ?: return
            val st0 = TraktVault.state(p)
            val prev = st0.la
            fun at(o: JsonObject?, group: String, field: String) = o.o(group).str(field)
            fun moved(group: String, field: String) = prev == null || at(prev, group, field) != at(la, group, field)

            Trakt.ensureUser(p)
            var st = st0
            var watched = 0
            var progress = 0
            var listed = 0
            if (Tmdb.hasKey()) {
                if (moved("episodes", "watched_at") || moved("movies", "watched_at")) {
                    val (n, s) = pullHistory(p, st, prev); watched = n; st = s
                }
                if (moved("episodes", "paused_at") || moved("movies", "paused_at")) {
                    val (n, s) = pullPlayback(p, st); progress = n; st = s
                }
                if (moved("movies", "watchlisted_at") || moved("shows", "watchlisted_at") || st.wl == null) {
                    val (n, s) = syncWatchlist(p, st, la); listed = n; st = s
                }
            }
            // Saved as one: the activity marks move on only once everything they stand for is in.
            synchronized(TraktVault.store) {
                // Signed out, or another profile took over mid-sync (its pulls were skipped): the marks stay, so the next sync pulls again.
                if (TraktVault.tokens(p) == null || pid != p) return
                val cur = TraktVault.state(p)
                TraktVault.setState(p, cur.copy(shows = st.shows, pb = st.pb, wl = st.wl, la = la, at = System.currentTimeMillis(), dirty = false))
            }
            ok = true
            Log.i("Trakt", "sync ($reason) pulled $watched watched, $progress in progress, $listed watchlist in ${System.currentTimeMillis() - started} ms")
        } catch (e: Exception) {
            Log.w("Trakt", "sync ($reason) stopped: ${e.javaClass.simpleName}")
        } finally {
            Trakt.lastSyncFailed = !ok && pid == p // a sync cut short by a profile switch isn't a failure to show
            Trakt.syncing.set(false)
            Trakt.changed()
        }
    }

    /** Recent history (since the last sync) into Continue Watching and the Watched pill; also "shows watched lately". */
    private fun pullHistory(p: String, st: TraktState, prev: JsonObject?): Pair<Int, TraktState> {
        val cutoff = System.currentTimeMillis() - Trakt.HISTORY_DAYS * 86_400_000L
        fun since(group: String) = prev.o(group).str("watched_at").takeIf { it.isNotEmpty() }?.let { "&start_at=" + Net.encode(it) } ?: ""
        val shows = st.shows.toMutableMap()
        val entries = ArrayList<HistoryEntry>()

        val latestPerShow = LinkedHashMap<Int, Pair<Long, JsonObject>>()
        // Everything Trakt lists as watched counts for the Watched pill, not only what reaches Continue Watching.
        val watchedEps = HashMap<Int, HashSet<Pair<Int, Int>>>()
        val watchedMovies = HashSet<Int>()
        for (o in Trakt.getArray(p, "/sync/history/episodes?limit=100" + since("episodes")).objects()) {
            val sid = o.o("show").o("ids").int("tmdb")
            val at = TraktTime.ms(o.str("watched_at"))
            if (sid <= 0 || at <= 0) continue
            o.o("episode")?.let { e ->
                val s = e.int("season"); val n = e.int("number")
                if (s > 0 && n > 0) watchedEps.getOrPut(sid) { HashSet() }.add(s to n)
            }
            if (at > (shows["$sid"] ?: 0)) shows["$sid"] = at
            if ((latestPerShow[sid]?.first ?: 0) < at) latestPerShow[sid] = at to o
        }
        for ((sid, pair) in latestPerShow.entries.sortedByDescending { it.value.first }.take(15)) {
            if (pair.first < cutoff) continue
            val ep = pair.second.o("episode") ?: continue
            entryFor(MediaKind.TV, sid, ep.int("season"), ep.int("number"), 1.0, pair.first)?.let(entries::add)
        }

        val seen = HashSet<Int>()
        for (o in Trakt.getArray(p, "/sync/history/movies?limit=30" + since("movies")).objects()) {
            val id = o.o("movie").o("ids").int("tmdb")
            val at = TraktTime.ms(o.str("watched_at"))
            if (id > 0) watchedMovies.add(id)
            if (id <= 0 || at < cutoff || !seen.add(id) || seen.size > 8) continue
            entryFor(MediaKind.MOVIE, id, 0, 0, 1.0, at)?.let(entries::add)
        }
        if (pid == p) Watched.fromTrakt(watchedMovies, watchedEps)

        // Keep the 80 most recent shows.
        val kept = if (shows.size > 80) shows.entries.sortedByDescending { it.value }.take(80).associate { it.key to it.value } else shows
        return merge(p, entries) to st.copy(shows = kept)
    }

    /** Trakt's paused points (other devices) into Continue Watching; the ids are kept so a removal here clears them there. */
    private fun pullPlayback(p: String, st: TraktState): Pair<Int, TraktState> {
        val newest = LinkedHashMap<String, Triple<Long, JsonObject, Double>>()
        for (o in Trakt.getArray(p, "/sync/playback").objects()) {
            val pct = o.dbl("progress") / 100.0
            val at = TraktTime.ms(o.str("paused_at"))
            if (pct < 0.02 || at <= 0) continue
            val key = when (o.str("type")) {
                "movie" -> o.o("movie").o("ids").int("tmdb").takeIf { it > 0 }?.let { "movie:$it" }
                "episode" -> o.o("show").o("ids").int("tmdb").takeIf { it > 0 }?.let { "tv:$it" }
                else -> null
            } ?: continue
            if ((newest[key]?.first ?: 0) < at) newest[key] = Triple(at, o, pct)
        }
        val pb = LinkedHashMap<String, Long>()
        val entries = ArrayList<HistoryEntry>()
        for ((key, t) in newest.entries.sortedByDescending { it.value.first }.take(20)) {
            pb[key] = t.second.long("id")
            val id = key.substringAfter(':').toInt()
            val e = if (key.startsWith("movie:")) entryFor(MediaKind.MOVIE, id, 0, 0, t.third, t.first)
            else t.second.o("episode")?.let { entryFor(MediaKind.TV, id, it.int("season"), it.int("number"), t.third, t.first) }
            e?.let(entries::add)
        }
        return merge(p, entries) to st.copy(pb = pb)
    }

    private fun merge(p: String, entries: List<HistoryEntry>): Int {
        if (entries.isEmpty() || pid != p) return 0
        return WatchHistory.mergeRemote(entries)
    }

    /** A Continue Watching entry for a Trakt item, filled from TMDB (cached). Blocking. */
    private fun entryFor(kind: MediaKind, tmdbId: Int, season: Int, episode: Int, progress: Double, at: Long): HistoryEntry? {
        val isShow = kind == MediaKind.TV
        if (isShow && (season <= 0 || episode <= 0)) return null
        val d = WatchHistory.details(kind, tmdbId) ?: return null
        val title = titleFrom(d, kind) ?: return null
        var epName: String? = null
        var still: String? = null
        var duration = if (isShow) 0L else d.int("runtime") * 60_000L
        if (isShow) {
            WatchHistory.seasonEpisodes(tmdbId, season)?.firstOrNull { it.int("episode_number") == episode }?.let { ep ->
                epName = ep.str("name").ifEmpty { null }
                still = ep.strOrNull("still_path")
                duration = ep.int("runtime") * 60_000L
            }
            if (duration <= 0) duration = d.a("episode_run_time").ints().firstOrNull()?.let { it * 60_000L } ?: 0L
        }
        val pr = progress.coerceIn(0.0, 1.0)
        // Unknown length: a finished one still has to read as finished (progress comes from position / duration).
        if (duration <= 0 && pr >= HistoryEntry.FINISHED) duration = 1
        val imdb = (d.o("external_ids").str("imdb_id").ifEmpty { d.str("imdb_id") }).takeIf { it.startsWith("tt") }
        return HistoryEntry(
            title = title, season = season, episode = episode, episodeName = epName, stillPath = still, imdbId = imdb,
            positionMs = if (duration > 0) (duration * pr).toLong() else 0L, durationMs = duration,
            updatedAt = at, source = TRAKT,
        )
    }

    /** TMDB details as a [Title] (genres from the details' list). */
    private fun titleFrom(d: JsonObject, kind: MediaKind): Title? {
        val t = Tmdb.parse(d, kind) ?: return null
        if (t.name.isEmpty()) return null
        val genres = d.a("genres").objects().map { it.int("id") }.filter { it > 0 }
        return if (t.genreIds.isEmpty() && genres.isNotEmpty()) t.copy(genreIds = genres) else t
    }

    /**
     * Trakt watchlist <-> My List. Last write wins:
     *  - On Trakt, not here: added here, unless it was removed here after Trakt listed it (then removed on Trakt).
     *  - Here, not on Trakt, and it was on Trakt at the last sync (removed there): removed here, unless it was added
     *    here after Trakt's last watchlist change (then added back on Trakt).
     *  - Here, never seen on Trakt (added before signing in, or the first sync): added on Trakt.
     * Removals on Trakt carry no time of their own; Trakt's last watchlist activity stands in for it.
     */
    private fun syncWatchlist(p: String, st: TraktState, la: JsonObject): Pair<Int, TraktState> {
        val remote = LinkedHashMap<String, Long>()
        for ((path, type, field) in listOf(Triple("movies", "movie", "movie"), Triple("shows", "tv", "show"))) {
            // Every page: a title missing from the read would count as removed on Trakt and leave My List.
            var page = 1
            while (page <= WATCHLIST_PAGES) {
                val a = Trakt.getArray(p, "/sync/watchlist/$path?page=$page&limit=$WATCHLIST_PAGE")
                for (o in a.objects()) {
                    val id = o.o(field).o("ids").int("tmdb")
                    if (id > 0) remote["$type:$id"] = TraktTime.ms(o.str("listed_at"))
                }
                if (a.size < WATCHLIST_PAGE) break
                page++
            }
            // Past the last page read, the rest is unknown: no two-way sync rather than removals that aren't real.
            if (page > WATCHLIST_PAGES) { Log.w("Trakt", "watchlist over ${WATCHLIST_PAGES * WATCHLIST_PAGE} $path, not synced"); return 0 to st }
        }
        if (pid != p) throw IOException("profile switched")
        val snap = st.wl?.toSet()
        val ledger = TraktVault.state(p).ledger
        val localTitles = MyListStore.items()
        val local = localTitles.map { it.key }
        val removedAt = maxOf(TraktTime.ms(la.o("movies").str("watchlisted_at")), TraktTime.ms(la.o("shows").str("watchlisted_at")))
        val addHere = ArrayList<String>()
        val removeHere = ArrayList<String>()
        val pushAdd = ArrayList<String>()
        val pushRemove = ArrayList<String>()
        val localSet = local.toSet()
        for ((k, listedAt) in remote) if (k !in localSet) {
            val l = ledger[k]
            if (l != null && !l.inList && l.at > listedAt) pushRemove += k else addHere += k
        }
        for (k in local) if (k !in remote) {
            val l = ledger[k]
            if (snap != null && k in snap) {
                if (l != null && l.inList && l.at > removedAt) pushAdd += k else removeHere += k
            } else pushAdd += k
        }
        // Newest-listed last, so it ends up first (My List puts each addition at the top).
        val titles = addHere.sortedBy { remote[it] ?: 0 }.takeLast(60).mapNotNull { titleFor(it) }
        if (pid != p) throw IOException("profile switched")
        // MyListStore.set never tells Trakt back: these changes came from Trakt.
        for (t in titles) MyListStore.set(t, true)
        for (k in removeHere) localTitles.firstOrNull { it.key == k }?.let { MyListStore.set(it, false) }
        val onTrakt = remote.keys.toMutableSet()
        if (pushAdd.isNotEmpty()) Trakt.authed(p, "POST", "/sync/watchlist", watchlistBody(pushAdd))?.let { r ->
            if (r.ok) onTrakt += pushAdd
            else if (r.code == 420) Log.w("Trakt", "watchlist is at its limit, ${pushAdd.size} title(s) stay only in My List")
        }
        if (pushRemove.isNotEmpty()) Trakt.authed(p, "POST", "/sync/watchlist/remove", watchlistBody(pushRemove))?.let { r ->
            if (r.ok) onTrakt -= pushRemove.toSet()
        }
        if (pushAdd.isNotEmpty() || pushRemove.isNotEmpty()) Log.i("Trakt", "watchlist pushed ${pushAdd.size} added, ${pushRemove.size} removed")
        return (titles.size + removeHere.size) to st.copy(wl = onTrakt.toList())
    }

    private fun titleFor(key: String): Title? {
        val kind = MediaKind.of(key.substringBefore(':')) ?: return null
        val id = key.substringAfter(':').toIntOrNull() ?: return null
        val d = WatchHistory.details(kind, id) ?: return null
        return titleFrom(d, kind)
    }
}
