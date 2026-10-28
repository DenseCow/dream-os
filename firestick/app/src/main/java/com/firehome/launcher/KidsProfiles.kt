package com.firehome.launcher

import android.content.Context

/**
 * Kids profiles: a real per-profile flag ("kids_<id>" in the profiles store, the key SeasonHub already
 * read) and a maximum age rating, as one friendly level that covers movies and shows:
 *
 *   Little Kids  G       · TV-Y, TV-G
 *   Kids         PG      · TV-Y7, TV-PG
 *   Teens        PG-13   · TV-14
 *   Everyone     R       · TV-MA (no limit)
 *
 * A profile never set either way keeps the old guess from its name ("Kids", "Junior", ...), so an
 * existing kids profile stays one. Leaving a kids profile can ask for a PIN ([exitPin]).
 */
object KidsProfiles {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val KIDS_NAME = Regex("(?i)\\b(kids?|kiddos?|children|child|junior|juniors)\\b")

    class Level(val name: String, val movie: String, val tv: List<String>)

    val LEVELS = listOf(
        Level("Little Kids", "G", listOf("TV-Y", "TV-G")),
        Level("Kids", "PG", listOf("TV-Y", "TV-G", "TV-Y7", "TV-PG")),
        Level("Teens", "PG-13", listOf("TV-Y", "TV-G", "TV-Y7", "TV-PG", "TV-14")),
        Level("Everyone", "R", listOf("TV-Y", "TV-G", "TV-Y7", "TV-PG", "TV-14", "TV-MA")),
    )
    private val MOVIE_ORDER = listOf("G", "PG", "PG-13", "R", "NC-17")

    fun isKids(ctx: Context, id: String = Profiles.current(ctx).id): Boolean {
        val p = prefs(ctx)
        if (p.contains("kids_$id")) return p.getBoolean("kids_$id", false)
        val name = Profiles.all(ctx).firstOrNull { it.id == id }?.name.orEmpty()
        return KIDS_NAME.containsMatchIn(name)
    }

    fun setKids(ctx: Context, id: String, on: Boolean) = prefs(ctx).edit().putBoolean("kids_$id", on).apply()

    /** The profile's maximum level (index into [LEVELS]); a kids profile defaults to Kids, everyone else to no limit. */
    fun level(ctx: Context, id: String = Profiles.current(ctx).id): Int =
        prefs(ctx).getInt("maxlevel_$id", if (isKids(ctx, id)) 1 else LEVELS.size - 1).coerceIn(0, LEVELS.size - 1)

    fun setLevel(ctx: Context, id: String, level: Int) = prefs(ctx).edit().putInt("maxlevel_$id", level.coerceIn(0, LEVELS.size - 1)).apply()

    /** "Kids · PG, TV-PG" */
    fun levelText(i: Int) = LEVELS[i].let { "${it.name} · ${it.movie}, ${it.tv.last()}" }

    /** Whether [cert] (a US certification, movie or TV) is allowed at [level]. Unknown or unrated: only for Everyone. */
    fun allows(level: Int, cert: String?, tv: Boolean): Boolean {
        if (level >= LEVELS.size - 1) return true
        val c = cert?.trim()?.uppercase().orEmpty()
        if (c.isEmpty() || c == "NR" || c == "UNRATED") return false
        val l = LEVELS[level]
        return if (tv) c in l.tv else MOVIE_ORDER.indexOf(c).let { it in 0..MOVIE_ORDER.indexOf(l.movie) }
    }

    // ---- leaving a kids profile ----

    fun exitPin(ctx: Context, id: String) = prefs(ctx).getBoolean("kidsexitpin_$id", false)
    fun setExitPin(ctx: Context, id: String, on: Boolean) = prefs(ctx).edit().putBoolean("kidsexitpin_$id", on).apply()
}
