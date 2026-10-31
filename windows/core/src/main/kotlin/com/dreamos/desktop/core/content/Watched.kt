package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.HistoryEntry
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.ShowAirState
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.time.DreamTime
import com.dreamos.desktop.core.util.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The Watched pill's memory (fire-home Watched.kt, the iPhone's WatchedLedger): what the viewer has finished, per
 * profile, read instantly (no network) by every card. Separate from [WatchHistory] and never shrinks: a finished
 * title started again keeps its pill.
 *  - A movie: finished once (92 %, or Trakt's history).
 *  - An episode: only one the viewer actually finished ("S:E"; nothing is inferred from a later one).
 *  - A show: every aired episode finished and nothing newer has aired. The air state is TMDB's, asked again every
 *    6 h and once the announced day passes; a show with a new episode loses Watched.
 * Worst case: a few thousand episode keys per profile (~50 KB).
 */
object Watched {
    private const val AIR_TTL = 6 * 3600_000L
    private const val PASSED_TTL = 3600_000L

    @Serializable
    private data class Show(
        val eps: MutableSet<String> = HashSet(),
        var ls: Int = 0, var le: Int = 0, var cn: List<Int> = emptyList(), var n: String = "", var c: Long = 0L,
    )

    @Serializable
    private data class Book(val m: MutableSet<Int> = HashSet(), val s: MutableMap<Int, Show> = HashMap(), var seeded: Boolean = false)

    private val lock = Any()
    private var book: Book? = null
    private var bookProfile: String? = null
    private val asking = ConcurrentHashMap.newKeySet<Int>()
    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "watched-air").apply { isDaemon = true } }
    private val _version = MutableStateFlow(0L)

    /** Bumped on every change (pills redraw). */
    val version: StateFlow<Long> get() = _version

    /** Shows wearing a NEW EPISODE badge right now (NewEpisodes): the badge always wins over Watched. */
    @Volatile var badged: Set<Int> = emptySet()

    private fun prefs() = DreamStore.shared.profileFile("watch")

    private fun current(): Book {
        val pid = DreamStore.currentProfile
        book?.let { if (bookProfile == pid) return it }
        val b = prefs().get("watched", Book.serializer()) ?: Book()
        if (!b.seeded) {
            for (e in WatchHistory.recent()) seedFrom(b, e)
            b.seeded = true
            prefs().put("watched", Book.serializer(), b)
            Log.i("Watched", "seeded ${b.m.size} movies, ${b.s.size} shows from the watch history")
        }
        book = b
        bookProfile = pid
        return b
    }

    /** Only what was really finished: finished movies and episodes, and the episode before an Up Next one. */
    private fun seedFrom(b: Book, e: HistoryEntry) {
        if (!e.isShow) { if (e.progress >= HistoryEntry.FINISHED) b.m.add(e.title.id); return }
        if (e.season <= 0 || e.episode <= 0) return
        if (e.upNext) { if (e.episode > 1) add(b, e.title.id, e.season, e.episode - 1) }
        else if (e.progress >= HistoryEntry.FINISHED) add(b, e.title.id, e.season, e.episode)
    }

    private fun add(b: Book, show: Int, season: Int, episode: Int): Boolean {
        if (show <= 0 || season <= 0 || episode <= 0) return false
        return b.s.getOrPut(show) { Show() }.eps.add("$season:$episode")
    }

    private fun edit(f: (Book) -> Boolean) {
        synchronized(lock) {
            val b = current()
            if (!f(b)) return
            prefs().put("watched", Book.serializer(), b)
            _version.value++
        }
    }

    private fun <T> read(f: (Book) -> T): T = synchronized(lock) { f(current()) }

    /** The profile changed: the next read loads its book. */
    fun profileChanged() = synchronized(lock) { book = null; bookProfile = null }

    // ---- writing ----

    /** The viewer finished [e] (a movie, or that one episode; a show's air state is asked when due). */
    fun finished(e: HistoryEntry) {
        if (!e.isShow) { edit { it.m.add(e.title.id) }; return }
        if (e.season <= 0 || e.episode <= 0) return
        edit { add(it, e.title.id, e.season, e.episode) }
        checkShow(e.title.id)
    }

    /** Trakt's history: every movie and every episode ([episodes]: show id to its season / episode pairs). */
    fun fromTrakt(movies: Collection<Int>, episodes: Map<Int, Collection<Pair<Int, Int>>>) {
        if (movies.isEmpty() && episodes.isEmpty()) return
        edit { b ->
            var n = false
            for (m in movies) if (m > 0 && b.m.add(m)) n = true
            for ((id, list) in episodes) for (p in list) if (add(b, id, p.first, p.second)) n = true
            n
        }
    }

    /** TMDB details of a show the viewer has finished episodes of. */
    fun noteAir(show: Int, d: JsonObject) {
        val last = d.o("last_episode_to_air")
        val ls = last.int("season_number")
        val le = last.int("episode_number")
        val today = DreamTime.todayYmd()
        val seasons = d.a("seasons").objects()
        var next = d.o("next_episode_to_air").str("air_date").takeIf { it.length == 10 }.orEmpty()
        if (next.isEmpty()) {
            next = seasons.filter { it.int("season_number") > ls && it.str("air_date").let { x -> x.length == 10 && x > today } }
                .minOfOrNull { it.str("air_date") }.orEmpty()
        }
        val counts = (1 until maxOf(ls, 1)).map { n -> seasons.firstOrNull { it.int("season_number", -1) == n }?.int("episode_count") ?: 0 }
        edit { b ->
            val s = b.s[show] ?: return@edit false
            s.ls = ls; s.le = le; s.cn = counts; s.n = next; s.c = System.currentTimeMillis()
            true
        }
    }

    /** A show with finished episodes on screen: its air state asked again in the background when due. */
    fun checkShow(show: Int) {
        if (!needsAirCheck(show) || !Tmdb.hasKey() || !asking.add(show)) return
        io.execute {
            try { WatchHistory.tmdb("/tv/$show")?.let { noteAir(show, it) } } finally { asking.remove(show) }
        }
    }

    // ---- reading (instant, local) ----

    fun movie(id: Int): Boolean = read { id in it.m }

    /** The viewer actually finished this episode. */
    fun episode(show: Int, season: Int, episode: Int): Boolean = read { b -> b.s[show]?.eps?.contains("$season:$episode") ?: false }

    /** The latest episode of [show] the viewer finished (season to episode), null when none ([NewEpisodes.caughtUp]). */
    fun furthest(show: Int): Pair<Int, Int>? = read { b ->
        b.s[show]?.eps?.mapNotNull { k ->
            val s = k.substringBefore(':').toIntOrNull()
            val e = k.substringAfter(':').toIntOrNull()
            if (s != null && e != null) s to e else null
        }?.maxWithOrNull(compareBy<Pair<Int, Int>>({ it.first }, { it.second }))
    }

    /** Every aired episode finished, and nothing newer has aired since TMDB was asked. */
    fun show(id: Int): Boolean = read { b ->
        val s = b.s[id] ?: return@read false
        if (s.ls <= 0 || s.le <= 0 || s.cn.size < s.ls - 1) return@read false
        if (s.n.isNotEmpty() && s.n <= DreamTime.todayYmd()) return@read false
        for (season in 1 until s.ls) {
            val n = s.cn[season - 1]
            for (e in 1..n) if ("$season:$e" !in s.eps) return@read false
        }
        (1..s.le).all { "${s.ls}:$it" in s.eps }
    }

    /** [t] is watched: a finished movie, or a show with every aired episode finished (a NEW EPISODE badge always wins). */
    fun title(t: Title): Boolean = if (t.kind == MediaKind.TV) t.id !in badged && show(t.id) else movie(t.id)

    /** A show with finished episodes whose air state is unknown, over 6 h old, or whose announced day has come. */
    fun needsAirCheck(show: Int): Boolean = read { b ->
        val s = b.s[show] ?: return@read false
        val age = System.currentTimeMillis() - s.c
        s.c == 0L || age > AIR_TTL || s.n.isNotEmpty() && s.n <= DreamTime.todayYmd() && age > PASSED_TTL
    }

    /** The air state as last asked (the UI's "caught up" rule), null when never asked. */
    fun airState(show: Int): ShowAirState? = read { b ->
        b.s[show]?.takeIf { it.c > 0 }?.let { ShowAirState(it.ls, it.le, listOf(0) + it.cn, it.n) }
    }
}
