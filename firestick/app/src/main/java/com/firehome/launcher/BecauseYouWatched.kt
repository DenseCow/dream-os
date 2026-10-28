package com.firehome.launcher

/**
 * Which watched titles seed the "Because You Watched" rows, Netflix style:
 *  - only titles watched in the last [MAX_AGE_DAYS] days (a meaningful while: [WatchHistory.watchedEnough]); the
 *    weight halves every [HALF_LIFE_DAYS]. No "abandoned" signal (owner): only recency weighs a seed, and a title
 *    removed from Continue Watching before it was finished seeds nothing ([removed]);
 *  - the most recently watched title is always a seed;
 *  - the rest are a weighted pick that stays the same all day for this profile (seeded by the
 *    date, the profile and the newest title), so the page doesn't reshuffle between visits, and
 *    changes as soon as something new is watched.
 */
object BecauseYouWatched {
    const val MAX_ROWS = 3
    private const val MAX_AGE_DAYS = 30
    private const val HALF_LIFE_DAYS = 7.0
    private const val DAY_MS = 86_400_000L

    /** [key] changes whenever the pick would (a new day, profile or newest title). */
    class Seeds(val key: String, val entries: List<WatchHistory.Entry>)

    fun key(ctx: android.content.Context, shows: Boolean): String = pool(ctx, shows).let { keyOf(ctx, it) }

    fun pick(ctx: android.content.Context, shows: Boolean): Seeds {
        val now = System.currentTimeMillis()
        val pool = pool(ctx, shows)
        val key = keyOf(ctx, pool)
        if (pool.isEmpty()) return Seeds(key, emptyList())
        fun weight(e: WatchHistory.Entry): Double {
            val ageDays = (now - e.updatedAt).coerceAtLeast(0) / DAY_MS.toDouble()
            return Math.pow(0.5, ageDays / HALF_LIFE_DAYS)
        }
        val picked = ArrayList<WatchHistory.Entry>()
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

    /** Titles watched in the last month, newest first, one sighting (the latest) per title. */
    private fun pool(ctx: android.content.Context, shows: Boolean): List<WatchHistory.Entry> {
        val now = System.currentTimeMillis()
        return WatchHistory.recent(ctx)
            .filter { it.isShow == shows && it.title.isNotBlank() && it.tmdbId > 0 && now - it.updatedAt <= MAX_AGE_DAYS * DAY_MS }
            .filter { WatchHistory.watchedEnough(it) && !removed(it) } // a quick sample isn't a taste
            .distinctBy { it.tmdbId }
    }

    /** Removed from Continue Watching before finishing (a test, a mistake): it seeds nothing (owner, 2026-10-28). */
    fun removed(e: WatchHistory.Entry) = e.hidden && e.progress < WatchHistory.FINISHED && !e.upNext

    private fun keyOf(ctx: android.content.Context, pool: List<WatchHistory.Entry>) =
        DreamTime.calendar().let { "${it.get(java.util.Calendar.YEAR)}-${it.get(java.util.Calendar.DAY_OF_YEAR)}" } +
            "|${Profiles.suffix(ctx)}|${pool.firstOrNull()?.key}"
}
