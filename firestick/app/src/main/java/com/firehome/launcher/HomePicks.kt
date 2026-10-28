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
        val pool = (Tmdb.trending(ctx) + Tmdb.list(ctx, "/trending/all/week", "&page=2"))
            .filter { it.backdropPath != null && RowMix.released(it) && !RowMix.isAnime(it) && !it.adult }.distinctBy { it.key }
        return pool.withIndex().sortedByDescending { (i, t) ->
            (1.0 - i / 40.0) + (if (t.genreIds.any { it in m.genres }) 1.0 else 0.0) + (if (t.type == "movie") 0.6 * m.movies else 0.0) +
                0.8 * taste.strength * taste.affinity(t) + 0.4 * Taste.quality(t)
        }.map { it.value }.take(n)
    }

    /**
     * Top Picks for You: TMDB's recommendations for up to [PICK_SEEDS] titles watched most recently
     * (and then My List), blended. A title recommended by several of them ranks first; within that,
     * the most recent seed's picks lead. Nothing already watched, in progress or saved.
     */
    fun topPicks(ctx: android.content.Context): List<Title> {
        val watched = WatchHistory.recent(ctx)
        val saved = Tmdb.myList(ctx)
        val seeds = (watched.filter { it.tmdbId > 0 && WatchHistory.watchedEnough(it) && !BecauseYouWatched.removed(it) }.map { (if (it.isShow) "tv" else "movie") to it.tmdbId } +
            saved.map { it.type to it.id }).distinct().take(PICK_SEEDS)
        if (seeds.isEmpty()) return emptyList()
        val skip = HashSet<String>().apply { watched.forEach { add(it.key) }; saved.forEach { add(it.key) } }
        val score = LinkedHashMap<String, Pair<Title, Double>>()
        for ((i, s) in seeds.withIndex()) {
            val recs = RowMix.recs(ctx, s.first, s.second, null) // recent, widely rated, tidied
            for ((rank, t) in recs.withIndex()) {
                if (t.key in skip || (t.backdropPath == null && t.posterPath == null)) continue
                // Each seed adds more for its top picks, and newer seeds count a little more.
                val add = (1.0 - rank / 40.0) * (1.0 - i / (2.0 * PICK_SEEDS))
                score[t.key] = t to ((score[t.key]?.second ?: 0.0) + add)
            }
        }
        return score.values.sortedByDescending { it.second }.map { it.first }
    }
}
