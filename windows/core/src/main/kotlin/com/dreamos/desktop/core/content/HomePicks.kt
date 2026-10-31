package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.HistoryEntry
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.time.DreamTime
import kotlin.math.pow

/**
 * What Home features (fire-home HomePicks.kt): the hero titles for the time of day, Top Picks for You, and the services
 * Home always has rows for. Blocking (TMDB): IO threads.
 */
object HomePicks {
    /** "Popular on" rows Home always has, in this order (Tmdb.SERVICES names). A PC has no installed apps to prefer. */
    val HOME_SERVICES = listOf("Netflix", "Hulu", "Disney+", "HBO Max", "Prime Video", "Apple TV", "Paramount+", "Peacock", "AMC+")
    /** "Popular on" rows on Home. */
    const val MAX_SERVICES = 6
    /** Recent titles Top Picks blends recommendations from, and the fewest picks it shows. */
    const val PICK_SEEDS = 6
    const val MIN_PICKS = 6

    /** "This Morning", "This Afternoon", "Tonight" or "Late Night", with the TMDB genres that suit it and how much it leans to movies. */
    class Moment(val name: String, val genres: Set<Int>, val movies: Double)

    fun moment(hour: Int = DreamTime.hourNow()): Moment = when (hour) {
        in 5..11 -> Moment("This Morning", setOf(35, 10751, 16, 12, 10762), 0.0)
        in 12..16 -> Moment("This Afternoon", setOf(35, 12, 10759, 878, 10765, 14), 0.0)
        in 17..21 -> Moment("Tonight", setOf(28, 18, 53, 80, 10759, 9648), 1.0)
        else -> Moment("Late Night", setOf(27, 53, 9648, 80, 878, 10765), 0.5)
    }

    /** Home's services, as [Tmdb.Service]s, one per name, [MAX_SERVICES] of them. */
    fun services(): List<Tmdb.Service> =
        HOME_SERVICES.mapNotNull { n -> Tmdb.SERVICES.firstOrNull { it.name == n } }.take(MAX_SERVICES)

    /**
     * [n] trending titles with art, the ones that suit the time of day (and this profile's taste) first; popularity still
     * counts. Only what's out, no anime outside its sections, nothing adult.
     */
    fun featured(n: Int = 6): List<Title> {
        val pool = RowPaging.all({ Tmdb.trending() }, { Tmdb.list("/trending/all/week", "&page=2") }).flatten()
        return rankFeatured(pool, moment(), Taste.current(), n)
    }

    /** [featured]'s ordering on a given pool (pure: unit-tested). */
    fun rankFeatured(trending: List<Title>, m: Moment, taste: TasteProfile, n: Int): List<Title> {
        val pool = trending.filter { it.backdropPath != null && RowMix.released(it) && !RowMix.animeHidden(it) && !it.adult }.distinctBy { it.key }
        return pool.withIndex().sortedByDescending { (i, t) ->
            (1.0 - i / 40.0) + (if (t.genreIds.any { it in m.genres }) 1.0 else 0.0) + (if (t.kind == MediaKind.MOVIE) 0.6 * m.movies else 0.0) +
                0.8 * taste.strength * taste.affinity(t) + 0.4 * Taste.quality(t)
        }.map { it.value }.take(n)
    }

    /**
     * Top Picks for You: TMDB's recommendations for up to [PICK_SEEDS] titles watched most recently (and then My List),
     * blended. A title recommended by several of them ranks first; within that, the most recent seed's picks lead.
     * Nothing already watched, in progress or saved. Seeds are real watching only (never a removed, unfinished title).
     */
    fun topPicks(): List<Title> {
        val watched = WatchHistory.recent()
        val saved = MyListStore.items()
        val seeds = pickSeeds(watched, saved)
        if (seeds.isEmpty()) return emptyList()
        val all = RowPaging.all(*seeds.map { s -> { RowMix.recs(s.first, s.second, null) } }.toTypedArray())
        return blend(all, watched.map { it.key }.toSet() + saved.map { it.key })
    }

    /** The seeds Top Picks uses (pure). */
    fun pickSeeds(watched: List<HistoryEntry>, saved: List<Title>): List<Pair<MediaKind, Int>> =
        (watched.filter { it.title.id > 0 && WatchHistory.watchedEnough(it) && !BecauseYouWatched.removed(it) }.map { it.title.kind to it.title.id } +
            saved.map { it.kind to it.id }).distinct().take(PICK_SEEDS)

    /** Recommendation lists (newest seed first) blended into one ranking, without [skip] (pure). */
    fun blend(lists: List<List<Title>>, skip: Set<String>): List<Title> {
        val score = LinkedHashMap<String, Pair<Title, Double>>()
        for ((i, recs) in lists.withIndex()) {
            for ((rank, t) in recs.withIndex()) {
                if (t.key in skip || (t.backdropPath == null && t.posterPath == null)) continue
                val add = (1.0 - rank / 40.0) * (1.0 - i / (2.0 * PICK_SEEDS))
                score[t.key] = t to ((score[t.key]?.second ?: 0.0) + add)
            }
        }
        return score.values.sortedByDescending { it.second }.map { it.first }
    }

    /** A big card's kicker: what makes it worth a look (new, acclaimed), else its genre. */
    fun bigKicker(t: Title, genreName: (MediaKind, Int) -> String? = { _, _ -> null }): String {
        val recent = t.date.isNotEmpty() && t.date >= DreamTime.ymdPlus(-60) && t.date <= DreamTime.ymdPlus(0)
        return when {
            recent -> if (t.isShow) "New Series" else "New Movie"
            t.rating >= 7.8 && t.votes >= 200 -> "Critically Acclaimed"
            else -> t.genreIds.firstNotNullOfOrNull { genreName(t.kind, it) } ?: if (t.isShow) "Series" else "Movie"
        }
    }
}

/**
 * Which watched titles seed the "Because You Watched" rows (fire-home BecauseYouWatched.kt), Netflix style:
 *  - only titles watched a meaningful while in the last [MAX_AGE_DAYS] days; the weight halves every [HALF_LIFE_DAYS].
 *    A title removed from Continue Watching before it was finished seeds nothing ([removed]);
 *  - the most recently watched title is always a seed;
 *  - the rest are a weighted pick that stays the same all day for this profile (seeded by the date, the profile and
 *    the newest title), so the page doesn't reshuffle between visits, and changes when something new is watched.
 */
object BecauseYouWatched {
    const val MAX_ROWS = 3
    private const val MAX_AGE_DAYS = 30
    private const val HALF_LIFE_DAYS = 7.0
    private const val DAY_MS = 86_400_000L

    /** [key] changes whenever the pick would (a new day, profile or newest title). */
    class Seeds(val key: String, val entries: List<HistoryEntry>)

    fun pick(shows: Boolean, history: List<HistoryEntry> = WatchHistory.recent(), now: Long = System.currentTimeMillis()): Seeds {
        val pool = pool(history, shows, now)
        val key = keyOf(pool, now)
        if (pool.isEmpty()) return Seeds(key, emptyList())
        fun weight(e: HistoryEntry): Double = 0.5.pow((now - e.updatedAt).coerceAtLeast(0) / DAY_MS.toDouble() / HALF_LIFE_DAYS)
        val picked = ArrayList<HistoryEntry>()
        val rest = pool.toMutableList()
        picked += rest.removeAt(0)
        val rng = java.util.Random(key.hashCode().toLong())
        while (picked.size < MAX_ROWS && rest.isNotEmpty()) {
            var r = rng.nextDouble() * rest.sumOf { weight(it) }
            var i = 0
            while (i < rest.size - 1) {
                r -= weight(rest[i])
                if (r <= 0) break
                i++
            }
            picked += rest.removeAt(i)
        }
        return Seeds(key, picked)
    }

    /** Both kinds' seeds, newest first ([n] of them): Home's Because You Watched rows. */
    fun homeSeeds(n: Int = 2): List<HistoryEntry> {
        val h = WatchHistory.recent()
        return (pick(false, h).entries + pick(true, h).entries).sortedByDescending { it.updatedAt }.take(n)
    }

    /** One kind's seeds: the Movies / TV Shows pages' rows. */
    fun seeds(kind: MediaKind): List<HistoryEntry> = pick(kind == MediaKind.TV).entries

    fun key(shows: Boolean): String = keyOf(pool(WatchHistory.recent(), shows, System.currentTimeMillis()), System.currentTimeMillis())

    private fun pool(history: List<HistoryEntry>, shows: Boolean, now: Long): List<HistoryEntry> = history
        .sortedByDescending { it.updatedAt }
        .filter { it.isShow == shows && it.title.name.isNotBlank() && it.title.id > 0 && now - it.updatedAt <= MAX_AGE_DAYS * DAY_MS }
        .filter { WatchHistory.watchedEnough(it) && !removed(it) }
        .distinctBy { it.title.id }

    /** Removed from Continue Watching before finishing (a test, a mistake): it seeds nothing (owner, 2026-10-28). */
    fun removed(e: HistoryEntry) = e.hidden && e.progress < HistoryEntry.FINISHED && !e.upNext

    private fun keyOf(pool: List<HistoryEntry>, now: Long) =
        DreamTime.ymd(now) + "|${DreamStore.currentProfile}|${pool.firstOrNull()?.key}"
}
