package com.firehome.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The Watched pill's memory: what the viewer has finished, kept per profile, read instantly (no network) by every
 * card. [WatchHistory] keeps one entry per title and forgets a finished one once it's started again, so this ledger
 * is separate and never shrinks: a finished title the viewer starts again keeps its Watched pill (as on Netflix).
 *  - A movie: finished once ([WatchHistory.FINISHED], or Trakt's history).
 *  - An episode: only one the viewer actually finished (kept one by one as "S:E"; nothing is inferred from a later
 *    one, so starting a show at S3E5 marks nothing else).
 *  - A show: every aired episode is in that set (TMDB's seasons up to its latest aired episode) and nothing newer has
 *    aired. The air state is TMDB's, asked again every 6 h and once the announced day passes ([checkShow]); a show
 *    with a new episode loses Watched (the New Episode badge or pill takes over).
 * The same rules and values as the iPhone app's WatchedLedger. Shown by [Availability.Pill] in its watched look.
 */
object Watched {
    private const val TAG = "FireHome"
    private const val AIR_TTL = 6 * 3600_000L
    /** An announced day that passed: asked again at most this often (TMDB may not have the episode listed yet). */
    private const val PASSED_TTL = 3600_000L

    /**
     * [episodes]: finished ones, "S:E". Air state (TMDB): the latest aired episode, the episode counts of the
     * seasons before it ([counts], index 0 = Season 1: all aired), the next air day ("" none), when it was asked.
     */
    private class Show(val episodes: HashSet<String> = HashSet(), var lastSeason: Int = 0, var lastEpisode: Int = 0,
                       var counts: List<Int> = emptyList(), var nextAir: String = "", var checked: Long = 0L)

    private class Book(val movies: HashSet<Int> = HashSet(), val shows: HashMap<Int, Show> = HashMap(), var seeded: Boolean = false) {
        fun toJson(): JSONObject {
            val s = JSONObject()
            for ((id, v) in shows) s.put("$id", JSONObject().put("eps", JSONArray(v.episodes.toList())).put("ls", v.lastSeason)
                .put("le", v.lastEpisode).put("cn", JSONArray(v.counts)).put("n", v.nextAir).put("c", v.checked))
            return JSONObject().put("m", JSONArray(movies.toList())).put("s", s).put("seeded", seeded)
        }

        companion object {
            fun from(o: JSONObject): Book {
                val b = Book(seeded = o.optBoolean("seeded"))
                o.optJSONArray("m")?.let { a -> for (i in 0 until a.length()) a.optInt(i).takeIf { it > 0 }?.let(b.movies::add) }
                o.optJSONObject("s")?.let { s ->
                    for (k in s.keys()) {
                        val id = k.toIntOrNull() ?: continue
                        val v = s.optJSONObject(k) ?: continue
                        val eps = HashSet<String>()
                        v.optJSONArray("eps")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.contains(':') }?.let(eps::add) }
                        val cn = v.optJSONArray("cn")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
                        b.shows[id] = Show(eps, v.optInt("ls"), v.optInt("le"), cn, v.optString("n"), v.optLong("c"))
                    }
                }
                return b
            }
        }
    }

    private val lock = Any()
    private var book: Book? = null
    private var bookSfx: String? = null
    private val main = Handler(Looper.getMainLooper())
    /** Shows whose air state is being asked right now. */
    private val asking = ConcurrentHashMap.newKeySet<Int>()

    /** Bumped on every change. */
    @Volatile var version = 0L
        private set

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("watch", Context.MODE_PRIVATE)

    /** The current profile's book (under [lock]); the first read of a profile fills it from its watch history. */
    private fun current(ctx: Context): Book {
        val sfx = Profiles.suffix(ctx)
        book?.let { if (bookSfx == sfx) return it }
        val b = try { prefs(ctx).getString("watched$sfx", null)?.let { Book.from(JSONObject(it)) } } catch (e: Exception) { null } ?: Book()
        if (!b.seeded) {
            for (e in WatchHistory.recent(ctx)) seedFrom(b, e)
            b.seeded = true
            prefs(ctx).edit().putString("watched$sfx", b.toJson().toString()).apply()
            Log.w(TAG, "watched: seeded ${b.movies.size} movies, ${b.shows.size} shows from the watch history")
        }
        book = b
        bookSfx = sfx
        return b
    }

    /** Only what was really finished: finished movies and episodes, and the episode right before an Up Next one in the same season. */
    private fun seedFrom(b: Book, e: WatchHistory.Entry) {
        if (!e.isShow) { if (e.progress >= WatchHistory.FINISHED) b.movies.add(e.tmdbId); return }
        if (e.season <= 0 || e.episode <= 0) return
        if (e.upNext) { if (e.episode > 1) add(b, e.tmdbId, e.season, e.episode - 1) }
        else if (e.progress >= WatchHistory.FINISHED) add(b, e.tmdbId, e.season, e.episode)
    }

    private fun add(b: Book, show: Int, season: Int, episode: Int): Boolean {
        if (show <= 0 || season <= 0 || episode <= 0) return false
        return b.shows.getOrPut(show) { Show() }.episodes.add("$season:$episode")
    }

    private fun edit(ctx: Context, f: (Book) -> Boolean) {
        val changed = synchronized(lock) {
            val b = current(ctx)
            if (!f(b)) return@synchronized false
            prefs(ctx).edit().putString("watched" + Profiles.suffix(ctx), b.toJson().toString()).apply()
            version++ // under the lock: edits come from several threads (history, Trakt, air checks)
            true
        }
        if (changed) main.post { Availability.watchedChanged() }
    }

    private fun <T> read(ctx: Context, f: (Book) -> T): T = synchronized(lock) { f(current(ctx)) }

    // ---- writing ----

    /** [WatchHistory]: the viewer finished [e] (a movie, or that one episode; a show's air state is asked when due). */
    internal fun finished(ctx: Context, e: WatchHistory.Entry) {
        if (!e.isShow) { edit(ctx) { it.movies.add(e.tmdbId) }; return }
        if (e.season <= 0 || e.episode <= 0) return
        edit(ctx) { add(it, e.tmdbId, e.season, e.episode) }
        checkShow(ctx, e.tmdbId)
    }

    /** Trakt's history: every movie and every episode ([episodes]: show id to its season / episode pairs) it lists as watched. */
    internal fun fromTrakt(ctx: Context, movies: Collection<Int>, episodes: Map<Int, Collection<Pair<Int, Int>>>) {
        if (movies.isEmpty() && episodes.isEmpty()) return
        edit(ctx) { b ->
            var n = false
            for (m in movies) if (m > 0 && b.movies.add(m)) n = true
            for ((id, list) in episodes) for (p in list) if (add(b, id, p.first, p.second)) n = true
            n
        }
    }

    /** TMDB details of a show the viewer has finished episodes of ("last_episode_to_air", "next_episode_to_air", "seasons"). */
    internal fun noteAir(ctx: Context, show: Int, d: JSONObject) {
        val last = d.optJSONObject("last_episode_to_air")
        val ls = last?.optInt("season_number") ?: 0
        val le = last?.optInt("episode_number") ?: 0
        val today = DreamTime.todayYmd()
        val seasons = d.optJSONArray("seasons")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        var next = d.optJSONObject("next_episode_to_air")?.optString("air_date")?.takeIf { it.length == 10 }.orEmpty()
        if (next.isEmpty()) {
            next = seasons.filter { it.optInt("season_number") > ls && it.optString("air_date").let { x -> x.length == 10 && x > today } }
                .minOfOrNull { it.optString("air_date") }.orEmpty()
        }
        // Every season before the latest aired one is out in full: its episode count (0 when TMDB doesn't list it).
        val counts = (1 until maxOf(ls, 1)).map { n -> seasons.firstOrNull { it.optInt("season_number", -1) == n }?.optInt("episode_count") ?: 0 }
        edit(ctx) { b ->
            val s = b.shows[show] ?: return@edit false
            s.lastSeason = ls; s.lastEpisode = le; s.counts = counts; s.nextAir = next; s.checked = System.currentTimeMillis()
            true
        }
    }

    /** A show with finished episodes on screen: its air state asked again (fresh from TMDB, in the background) when due. */
    fun checkShow(ctx: Context, show: Int) {
        if (!needsAirCheck(ctx, show) || !Tmdb.hasKey(ctx) || !asking.add(show)) return
        val app = ctx.applicationContext
        Net.background({
            try { noteAir(app, show, Net.getJson("https://api.themoviedb.org/3/tv/$show?api_key=${Tmdb.key(app)}&language=en-US")) }
            finally { asking.remove(show) }
        }) {}
    }

    // ---- reading (instant, local) ----

    fun movie(ctx: Context, id: Int): Boolean = read(ctx) { id in it.movies }

    /** The viewer actually finished this episode. */
    fun episode(ctx: Context, show: Int, season: Int, episode: Int): Boolean =
        read(ctx) { b -> b.shows[show]?.episodes?.contains("$season:$episode") ?: false }

    /** The latest episode of [show] the viewer finished (season to episode), null when none ([NewEpisodes.caughtUp]). */
    fun furthest(ctx: Context, show: Int): Pair<Int, Int>? = read(ctx) { b ->
        b.shows[show]?.episodes?.mapNotNull { k ->
            val s = k.substringBefore(':').toIntOrNull()
            val e = k.substringAfter(':').toIntOrNull()
            if (s != null && e != null) s to e else null
        }?.maxWithOrNull(compareBy<Pair<Int, Int>>({ it.first }, { it.second }))
    }

    /** Every aired episode finished, and nothing newer has aired since TMDB was asked. */
    fun show(ctx: Context, id: Int): Boolean = read(ctx) { b ->
        val s = b.shows[id] ?: return@read false
        if (s.lastSeason <= 0 || s.lastEpisode <= 0 || s.counts.size < s.lastSeason - 1) return@read false
        if (s.nextAir.isNotEmpty() && s.nextAir <= today()) return@read false
        for (season in 1 until s.lastSeason) {
            val n = s.counts[season - 1]
            for (e in 1..n) if ("$season:$e" !in s.episodes) return@read false
        }
        (1..s.lastEpisode).all { "${s.lastSeason}:$it" in s.episodes }
    }

    /** [t] is watched: a finished movie, or a show with every aired episode finished (a NEW EPISODE badge always wins). */
    fun title(ctx: Context, t: Title): Boolean =
        if (t.type == "tv") t.id !in NewEpisodeSpot.badged && show(ctx, t.id) else movie(ctx, t.id)

    /** A show with finished episodes whose air state is unknown, older than 6 h, or whose announced day has come. */
    fun needsAirCheck(ctx: Context, show: Int): Boolean = read(ctx) { b ->
        val s = b.shows[show] ?: return@read false
        val age = System.currentTimeMillis() - s.checked
        s.checked == 0L || age > AIR_TTL || s.nextAir.isNotEmpty() && s.nextAir <= today() && age > PASSED_TTL
    }

    // Today's date, kept a minute (cards ask it often; a formatter per card is wasted work on the stick).
    @Volatile private var todayAt = 0L
    @Volatile private var todayYmd = ""
    private fun today(): String {
        val now = System.currentTimeMillis()
        if (now - todayAt > 60_000L) { todayYmd = DreamTime.todayYmd(now); todayAt = now }
        return todayYmd
    }
}
