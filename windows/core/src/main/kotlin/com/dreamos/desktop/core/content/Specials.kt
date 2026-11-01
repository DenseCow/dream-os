package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.Episode
import com.dreamos.desktop.core.model.EpisodeCode
import com.dreamos.desktop.core.model.EpisodeKind
import com.dreamos.desktop.core.model.SeasonSummary
import com.dreamos.desktop.core.playback.EpisodeForm
import com.dreamos.desktop.core.time.DreamTime
import com.dreamos.desktop.core.util.*
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A show's specials, the one catalog every surface reads (fire-home Specials.kt). TMDB is the app's episode list, but
 * it has no record of many specials: a returning show's `/tv/{id}/season/0` answered 404 while a streaming service was
 * already playing a 30-minute special of it that day (2026-10-31). So TMDB's season 0 is used where it exists (it
 * carries the stills and overviews) and TVmaze's `?specials=1` list fills in the rest ([AirTimes.specials], which also
 * knows the exact drop time); the two are matched on the name, or on the air day when a name is missing.
 *
 * A special is its own kind and it is identified by a **source id** ([Special.id]: "tmdb:<episode id>", else
 * "tvmaze:<episode id>"), never by its place in air order. That place is a view that moves: the sources backfill
 * specials, and one that turns up with an earlier air day shifts every later one. The place is computed only when a
 * wire form needs it ([numberOf], [formsFor]).
 *
 * Season 0 reaches three boundaries and stops: TMDB's request below, the index request the resolver builds, and
 * release-name matching ([formsFor]).
 *
 * Bounded: [MAX_SHOWS] shows' lists in memory for [TTL] (a few dozen small entries each, well under 1 MB).
 * Blocking (TMDB and TVmaze, both cached): IO threads only.
 */
object Specials {
    private const val TTL = 3 * 3_600_000L
    private const val MAX_SHOWS = 48
    private val TAGS = Regex("<[^>]*>")
    /** A name that identifies nothing ("Special", "Episode 2", "TBA"): such a special is matched on its day instead. */
    private val GENERIC = Regex("^(specials?|episode|ep\\.?)\\s*#?\\d*$|^(tba|tbd|untitled)$", RegexOption.IGNORE_CASE)

    /**
     * One special from the catalog. [id] is what identifies it everywhere in the app. [parentSeason] is the season it
     * aired beside (0 when TVmaze doesn't say), which its release names use. [tmdbNumber] is TMDB's own season-0
     * number, 0 when only TVmaze lists it. [number] is its place in air order, a view of the catalog as it stands.
     */
    data class Special(
        val showId: Int,
        val id: String,
        /**
         * Ids this same special has also been known by. One TVmaze lists first is "tvmaze:<id>"; once TMDB adds it its
         * preferred id becomes "tmdb:<id>" and the TVmaze one stays here, so a watch record, a history row or a badge
         * written under the older id still resolves to it ([idsFor]).
         */
        val aliasIds: List<String> = emptyList(),
        val number: Int,
        val name: String,
        val overview: String = "",
        val stillPath: String? = null,
        val airDate: String = "",
        val runtime: Int = 0,
        val airsAt: Long? = null,
        val parentSeason: Int = 0,
        val tmdbNumber: Int = 0,
    ) {
        fun episode() = Episode(showId, parentSeason, number, name, overview, stillPath, airDate, runtime, airsAt,
            kind = EpisodeKind.SPECIAL, specialId = id)
    }

    private class Cached(val list: List<Special>, val at: Long)

    private val memory = ConcurrentHashMap<Int, Cached>()
    private val locks = ConcurrentHashMap<Int, Any>()

    /**
     * A show whose catalog has just been looked up and has specials in it. A page that asked and gave up waiting
     * (the title page gives it a few hundred milliseconds, because TMDB has to answer 404 for season 0 before TVmaze
     * is even asked) listens here and shows its Specials tab when the answer lands, instead of only showing it on a
     * second visit once the cache is warm. The last one is replayed, because a page readied on hover can be built
     * before it listens and would otherwise miss its own answer. Dropping older ones is fine: the cache is the real
     * answer, this only says when it arrived.
     */
    val found = kotlinx.coroutines.flow.MutableSharedFlow<Int>(replay = 1, extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    /** Show [showId]'s specials in air order (empty: it has none). Blocking; cached. */
    fun of(showId: Int): List<Special> {
        if (showId <= 0) return emptyList()
        peek(showId)?.let { return it }
        synchronized(locks.getOrPut(showId) { Any() }) {
            peek(showId)?.let { return it }
            // TMDB answers 404 when a show has no season 0 at all, which is the usual case for a new special.
            val tmdb = try { WatchHistory.seasonEpisodes(showId, SEASON_0) } catch (e: Exception) { null }.orEmpty()
            val maze = try { AirTimes.specials(showId) } catch (e: Exception) { null }.orEmpty()
            val out = merge(showId, tmdb, maze)
            memory[showId] = Cached(out, System.currentTimeMillis())
            trim()
            if (out.isNotEmpty()) {
                Log.i("Specials", "show $showId has ${out.size} (${tmdb.size} from TMDB, ${out.size - tmdb.size} more from TVmaze)")
                found.tryEmit(showId)
            }
            return out
        }
    }

    /** What was already looked up, no network (null: not asked yet, or the answer is stale). */
    fun peek(showId: Int): List<Special>? =
        memory[showId]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.list

    /** The special [id] identifies, by its preferred id or any it has been known by. Blocking; cached. */
    fun byId(showId: Int, id: String): Special? = of(showId).firstOrNull { it.id == id || id in it.aliasIds }

    /**
     * Every id the special [id] names has been known by, [id] first: what a local, instant lookup compares against, so
     * a mark written under an older source id still finds it. From what is already cached only, no network.
     */
    fun idsFor(showId: Int, id: String): List<String> {
        val s = peek(showId)?.firstOrNull { it.id == id || id in it.aliasIds } ?: return listOf(id)
        return (listOf(id, s.id) + s.aliasIds).distinct()
    }

    /**
     * The place in air order the index numbers special [id] by (1-based; 0 when the catalog doesn't have it). Computed
     * at request time for the wire forms only, never stored: a backfilled earlier special moves it.
     */
    fun numberOf(showId: Int, id: String): Int = byId(showId, id)?.number ?: 0

    /**
     * Every way release names number special [id]: S00E{n} as the index answers for it, S{parent}E00 with its own
     * title in the file name as scene and P2P releases name it, and TMDB's own season-0 number where that differs.
     * Empty when the catalog doesn't have it. The release-name boundary (STANDARDS §5).
     */
    fun formsFor(showId: Int, id: String): List<EpisodeForm> {
        val s = byId(showId, id) ?: return emptyList()
        return listOfNotNull(
            EpisodeForm(SEASON_0, s.number),
            EpisodeForm(s.parentSeason, 0).takeIf { s.parentSeason > 0 },
            EpisodeForm(SEASON_0, s.tmdbNumber).takeIf { s.tmdbNumber > 0 && s.tmdbNumber != s.number },
        )
    }

    /**
     * Show [showId]'s specials as episodes, out or still to come. A special's air day is enough: an announced instant
     * never hides one that has been up since the morning (STANDARDS §5, a source decides, not the date), so only a day
     * still ahead is `upcoming`, and [early] (the index's quick look) brings even that one forward. Blocking.
     */
    fun episodes(
        showId: Int,
        nowMs: Long = DreamTime.now(),
        early: ((Int, String, String) -> Boolean)? = null,
        outEarly: ((Int, String) -> Boolean)? = null,
    ): List<Episode> {
        val today = DreamTime.todayYmd(nowMs)
        return of(showId).map { s ->
            val ep = s.episode()
            if (s.airDate.isNotEmpty() && s.airDate <= today) return@map ep
            val isEarly = try {
                outEarly?.invoke(showId, s.id) == true || early?.invoke(showId, s.id, s.airDate) == true
            } catch (e: Exception) { false }
            ep.copy(upcoming = !isEarly, outEarly = isEarly)
        }
    }

    /**
     * [seasons] as a viewer picks them: the real seasons in their order, then one "Specials" entry last, after the
     * seasons that are out and any still to come, so the chosen season stays Season 1 and Play keeps naming a real
     * episode. [count] is the catalog's own count (0: the show has none, or nothing was asked yet), which replaces
     * TMDB's season 0, since TMDB often lists fewer specials than there are.
     */
    fun ordered(seasons: List<SeasonSummary>, count: Int): List<SeasonSummary> {
        val tmdbZero = seasons.firstOrNull { it.number == SEASON_0 }
        val n = count.takeIf { it > 0 } ?: tmdbZero?.episodeCount ?: 0
        val rest = seasons.filter { it.number > 0 }
        if (n <= 0) return rest
        return rest + SeasonSummary(SEASON_0, EpisodeCode.SPECIALS, n, tmdbZero?.airDate.orEmpty(), tmdbZero?.posterPath)
    }

    /** Forgets what was looked up (tests, a new TMDB key). */
    fun clearCache() { memory.clear() }

    /** Puts a catalog in place without asking the sources (tests). */
    internal fun put(showId: Int, list: List<Special>) { memory[showId] = Cached(list, System.currentTimeMillis()) }

    /**
     * TMDB's season 0 plus the specials only TVmaze lists, in air order. A TVmaze special with the same name as a TMDB
     * one is the same special (the air day decides only when a name is missing or says nothing): TMDB's entry wins, it
     * has the still and the overview, and it takes TVmaze's parent season and exact instant. Matching on the day alone
     * would collapse a two-part special that dropped on one day into one entry.
     */
    internal fun merge(showId: Int, tmdb: List<JsonObject>, maze: List<AirTimes.MazeSpecial>): List<Special> {
        val out = ArrayList<Special>(tmdb.size + maze.size)
        for (o in tmdb) {
            val day = o.str("air_date").takeIf { it.length == 10 }.orEmpty()
            val name = o.str("name")
            val m = maze.firstOrNull { same(it.name, it.date, name, day) }
            // Matched to a TVmaze entry: TMDB's id is the preferred one from now on and the TVmaze one stays as an
            // alias, so anything written while only TVmaze knew this special still resolves to it.
            out += Special(showId, "tmdb:${o.int("id")}", listOfNotNull(m?.let { "tvmaze:${it.mazeId}" }),
                o.int("episode_number"), name, o.str("overview"), o.strOrNull("still_path"), day, o.int("runtime"),
                m?.at, m?.parentSeason ?: 0, tmdbNumber = o.int("episode_number"))
        }
        for (s in maze) {
            if (tmdb.any { same(s.name, s.date, it.str("name"), it.str("air_date")) }) continue
            out += Special(showId, "tvmaze:${s.mazeId}", emptyList(), 0, s.name, plain(s.summary), null, s.date,
                s.runtime, s.at, s.parentSeason)
        }
        // Air order, undated ones last; the place in that order is what the index numbers a special by.
        return out.sortedWith(compareBy({ it.airDate.ifEmpty { "9999" } }, { it.airsAt ?: 0L }))
            .mapIndexed { i, s -> s.copy(number = i + 1) }
    }

    /** The same special in both sources: the same name, or the same day when either name says nothing. */
    internal fun same(nameA: String, dayA: String, nameB: String, dayB: String): Boolean {
        val a = norm(nameA)
        val b = norm(nameB)
        if (a.isNotEmpty() && b.isNotEmpty() && !generic(nameA) && !generic(nameB)) return a == b
        return dayA.isNotEmpty() && dayA == dayB
    }

    private fun generic(name: String) = GENERIC.matches(name.trim())

    private fun norm(s: String) = s.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

    /** TVmaze summaries are HTML; a blurb wants plain words. */
    internal fun plain(html: String): String = TAGS.replace(html, "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").trim()

    private fun trim() {
        if (memory.size <= MAX_SHOWS) return
        val now = System.currentTimeMillis()
        memory.entries.removeAll { now - it.value.at >= TTL }
        val over = memory.size - MAX_SHOWS
        if (over > 0) memory.entries.sortedBy { it.value.at }.take(over).forEach { memory.remove(it.key) }
    }

    /** TMDB lists specials under season 0: one of the three places that number reaches, and it stops here. */
    private const val SEASON_0 = 0
}
