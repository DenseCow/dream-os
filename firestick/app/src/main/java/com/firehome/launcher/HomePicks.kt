package com.firehome.launcher

/**
 * What Home features ([CinemaHome]): the hero titles for the time of day, Top Picks for You, and the
 * services Home always has rows for. Blocking (TMDB): call off the main thread.
 */
object HomePicks {
    /** "Popular on" rows Home always has, installed or not, in this order (Tmdb.SERVICES names). */
    val HOME_SERVICES = listOf("Netflix", "Hulu", "Disney+", "HBO Max", "Prime Video", "Apple TV", "Paramount+", "Peacock", "AMC+")
    /** Recent titles Top Picks blends recommendations from, and the fewest picks it shows. */
    const val PICK_SEEDS = 6
    const val MIN_PICKS = 6
    /** A seed's recommendations count down from their first to this many places; seeds count down from the newest. */
    const val PICK_DEPTH = 40.0

    /** "This Morning", "This Afternoon", "Tonight" or "Late Night", with the TMDB genres that suit it and how much it leans to movies. */
    class Moment(val name: String, val genres: Set<Int>, val movies: Double)

    fun moment(): Moment = when (DreamTime.hourNow()) {
        // Light and easy: comedy, family, animation, adventure.
        in 5..11 -> Moment("This Morning", setOf(35, 10751, 16, 12, 10762), 0.0)
        in 12..16 -> Moment("This Afternoon", setOf(35, 12, 10759, 878, 10765, 14), 0.0)
        // Movie night: drama, action, crime, thrillers.
        in 17..21 -> Moment("Tonight", setOf(28, 18, 53, 80, 10759, 9648), 1.0)
        // After dark: thrillers, horror, mystery, sci-fi.
        else -> Moment("Late Night", setOf(27, 53, 9648, 80, 878, 10765), 0.5)
    }

    /**
     * [n] trending titles with art, the ones that suit the time of day (and this profile's taste) first; popularity
     * still counts. Only what's out (the chart lists films months ahead), no anime outside its sections, nothing adult.
     */
    fun featured(ctx: android.content.Context, n: Int = 6): List<Title> {
        val m = moment()
        val taste = Taste.current(ctx)
        val day = RowMix.dayNumber()
        // Nothing the viewer has watched (finished, in progress or removed): Top Picks and Because You Watched skip the same.
        val watched = WatchHistory.recent(ctx).mapTo(HashSet()) { it.key }
        val pool = (Tmdb.trending(ctx) + Tmdb.list(ctx, "/trending/all/week", "&page=2"))
            .filter { it.backdropPath != null && RowMix.released(it) && !RowMix.animeHidden(ctx, it) && !it.adult }.distinctBy { it.key }
        val out = heroPick(pool, watched, m, taste, day, n)
        out.firstOrNull()?.let { android.util.Log.i("FireHome", "Hero: ${m.name}, leads with ${it.key} (strength ${"%.2f".format(taste.strength)}, shows ${"%.2f".format(taste.shows)})") }
        return out
    }

    /** [pool] (a chart, in its order) less [skip], the best [n] by [heroScore]; a title's chart place is its place before the skip. Pure. */
    fun heroPick(pool: List<Title>, skip: Set<String>, m: Moment, taste: TasteProfile, day: Int, n: Int): List<Title> =
        pool.withIndex().filter { it.value.key !in skip }.sortedByDescending { (i, t) -> heroScore(i, t, m, taste, day) }.map { it.value }.take(n)

    /**
     * A hero candidate's score: its chart place ([i] of the pool), the time of day's genres and lean to movies (the
     * clock's say shrinks as the taste is known: [AlgoRules.HERO_MOMENT_FLOOR] of it stays to break ties), the
     * profile's taste, its steadied quality, the profile's own lean to movies or shows ([TasteProfile.typeLean]), and
     * the day's rotation so the same slot doesn't open on the same title every evening. Pure.
     */
    fun heroScore(i: Int, t: Title, m: Moment, taste: TasteProfile, day: Int): Double {
        val clock = maxOf(AlgoRules.HERO_MOMENT_FLOOR, 1 - taste.strength)
        return (1.0 - i / AlgoRules.HERO_ORDER_POOL) + clock * (if (t.genreIds.any { it in m.genres }) AlgoRules.HERO_MOMENT else 0.0) +
            clock * (if (t.type == "movie") AlgoRules.HERO_MOVIE_LEAN * m.movies else 0.0) +
            AlgoRules.HERO_TASTE * taste.strength * taste.affinity(t) + AlgoRules.HERO_QUALITY * Taste.quality(t) +
            AlgoRules.HERO_TYPE * taste.typeLean(t.type) + AlgoRules.HERO_ROTATION * Taste.rotation(t.key, day)
    }

    /**
     * Top Picks for You: TMDB's recommendations for up to [PICK_SEEDS] titles watched most recently
     * (and then My List), blended. A title recommended by several of them ranks first; within that,
     * the most recent seed's picks lead. Nothing already watched, in progress or saved.
     */
    fun topPicks(ctx: android.content.Context): List<Title> {
        val watched = WatchHistory.recent(ctx)
        val saved = Tmdb.myList(ctx)
        val seeds = (watched.filter { it.tmdbId > 0 && WatchHistory.watchedEnough(it) && !BecauseYouWatched.removed(it) }.map { (if (it.ofShow) "tv" else "movie") to it.tmdbId } +
            saved.map { it.type to it.id }).distinct().take(PICK_SEEDS)
        if (seeds.isEmpty()) return emptyList()
        val skip = HashSet<String>().apply { watched.forEach { add(it.key) }; saved.forEach { add(it.key) } }
        val score = LinkedHashMap<String, Pair<Title, Double>>()
        // Every seed's recommendations at once (one after another they were most of Home's first-row wait).
        val all = RowPaging.all(*seeds.map { s -> { RowMix.recs(ctx, s.first, s.second, null) } }.toTypedArray()) // recent, widely rated, tidied
        for ((i, recs) in all.withIndex()) {
            for ((rank, t) in recs.withIndex()) {
                if (t.key in skip || (t.backdropPath == null && t.posterPath == null)) continue
                // Each seed adds more for its top picks, and newer seeds count a little more.
                val add = (1.0 - rank / PICK_DEPTH) * (1.0 - i / (2.0 * PICK_SEEDS))
                score[t.key] = t to ((score[t.key]?.second ?: 0.0) + add)
            }
        }
        return score.values.sortedByDescending { it.second }.map { it.first }
    }
}
