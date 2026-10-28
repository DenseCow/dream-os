package com.firehome.launcher

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * A profile's taste (Taste.swift on the iPhone): what it finishes, keeps on My List and opens, turned into genre,
 * language and era affinities that rank every discovery row and choose Home's "for you" rows. Positive signals only:
 * finishing, saving, opening (and Trakt's history, which lands in the watch history). Nothing is learned from a title
 * that was started and left (owner's rule): taste is what you liked, never what you didn't finish.
 */
class TasteProfile(
    /** TMDB genre id -> 0..1 (1 = the genre this profile watches most). */
    val genre: Map<Int, Double> = emptyMap(),
    /** Original language -> 0..1. */
    val lang: Map<String, Double> = emptyMap(),
    /** Decade (1990, 2000, ...) -> 0..1. */
    val era: Map<Int, Double> = emptyMap(),
    /** Share of the signal that is shows (0.5 = even). */
    val shows: Double = 0.5,
    /** 0 = nothing known (cold start: popularity and quality alone), 1 = a well-known taste. */
    val strength: Double = 0.0,
    val signature: String = "",
    val builtAt: Long = 0L,
) {
    /** How much this profile likes [t]: the mean of its genres' affinities, its language and its era (0..1). */
    fun affinity(t: Title): Double {
        val g = if (t.genreIds.isEmpty()) 0.0 else t.genreIds.sumOf { genre[it] ?: 0.0 } / t.genreIds.size
        val l = if (t.lang.isEmpty()) 0.0 else lang[t.lang] ?: 0.0
        val e = t.year.take(4).toIntOrNull()?.let { era[it / 10 * 10] } ?: 0.0
        return minOf(1.0, 0.7 * g + 0.2 * l + 0.1 * e)
    }

    /** Affinity for a Browse category (its movie and TV genre ids), the higher of the two. */
    fun affinity(movie: Int, tv: Int): Double =
        maxOf(if (movie > 0) genre[movie] ?: 0.0 else 0.0, if (tv > 0) genre[tv] ?: 0.0 else 0.0)

    fun toJson(): JSONObject = JSONObject().apply {
        put("genre", JSONObject().apply { genre.forEach { (k, v) -> put(k.toString(), v) } })
        put("lang", JSONObject().apply { lang.forEach { (k, v) -> put(k, v) } })
        put("era", JSONObject().apply { era.forEach { (k, v) -> put(k.toString(), v) } })
        put("shows", shows); put("strength", strength); put("signature", signature); put("builtAt", builtAt)
    }

    companion object {
        val COLD = TasteProfile()

        fun fromJson(o: JSONObject): TasteProfile {
            fun ints(j: JSONObject?) = j?.keys()?.asSequence()?.mapNotNull { k -> k.toIntOrNull()?.let { it to j.optDouble(k) } }?.toMap().orEmpty()
            val l = o.optJSONObject("lang")
            return TasteProfile(ints(o.optJSONObject("genre")), l?.keys()?.asSequence()?.associateWith { l.optDouble(it) }.orEmpty(),
                ints(o.optJSONObject("era")), o.optDouble("shows", 0.5), o.optDouble("strength", 0.0),
                o.optString("signature"), o.optLong("builtAt"))
        }
    }
}

object Taste {
    private const val TAG = "FireHome"
    private const val PREFS = "taste"
    private const val DAY_MS = 86_400_000L

    /** Rows that keep their own order: charts, dated rows and the viewer's own lists. */
    private val KEEP_ORDER = listOf("Trending", "Top 10", "Coming Soon", "Continue Watching", "My List", "New Episodes for You", "In Theaters")

    fun ranks(row: String) = KEEP_ORDER.none { row.startsWith(it) }

    // ---- scoring ----

    /**
     * TMDB's score steadied by its vote count (a Bayesian average toward 6.4): 7.9 from 90 votes reads about 7.1,
     * 7.6 from 10,000 stays 7.6. Mapped 5.5..8.5 to 0..1.
     */
    fun quality(t: Title): Double {
        val m = if (t.type == "movie") 300.0 else 100.0
        val v = maxOf(0, t.votes).toDouble()
        val q = if (t.rating > 0 && v > 0) (v * t.rating + m * 6.4) / (v + m) else 6.0
        return ((q - 5.5) / 3.0).coerceIn(0.0, 1.0)
    }

    /**
     * [list] re-ranked for [p]: the list's own order (TMDB's intent) still counts for half, taste for a quarter (scaled
     * by how well it is known), steadied quality for a fifth and freshness a little; then franchises are spread out.
     * Stable: equal scores keep the list's order, so a cold profile sees popularity tempered by quality, never a shuffle.
     */
    fun rank(p: TasteProfile, list: List<Title>): List<Title> {
        if (list.size <= 2) return list
        // Freshness: 1 within a year, 0.6 within three, 0.3 within ten.
        val n = maxOf(1, list.size - 1).toDouble()
        val y1 = RowMix.yearsAgo(1); val y3 = RowMix.yearsAgo(3); val y10 = RowMix.yearsAgo(10)
        fun fresh(t: Title) = if (t.date.length != 10) 0.0 else if (t.date >= y1) 1.0 else if (t.date >= y3) 0.6 else if (t.date >= y10) 0.3 else 0.0
        val scored = list.mapIndexed { i, t ->
            val order = 1 - i / n
            Triple(t, 0.50 * order + 0.25 * p.strength * p.affinity(t) + 0.20 * quality(t) + 0.05 * fresh(t), i)
        }.sortedWith(compareByDescending<Triple<Title, Double, Int>> { it.second }.thenBy { it.third }).map { it.first }
        return spread(scored)
    }

    /** Titles of one franchise apart: the first of a stem keeps its place, the rest go to the end in order. */
    fun spread(list: List<Title>): List<Title> {
        val seen = HashSet<String>()
        val kept = ArrayList<Title>(list.size)
        val later = ArrayList<Title>()
        for (t in list) if (seen.add(stem(t.name))) kept += t else later += t
        return kept + later
    }

    private val STOP = setOf("the", "a", "an", "of", "and", "in", "on", "to", "for", "with", "at", "from", "by", "&")
    private val PART = Regex("\\s+(part|chapter|vol\\.?|volume|episode)\\s+[0-9ivx]+$")
    private val NUMBER = Regex("\\s+([0-9]+|ii|iii|iv|v|vi|vii|viii|ix)$")

    /**
     * A franchise key: the name up to a colon or dash, without a trailing number, then its first two telling words.
     * "John Wick: Chapter 4" -> "john wick"; "Harry Potter and the Goblet of Fire" -> "harry potter". Only spreads a
     * row, never drops a title.
     */
    fun stem(name: String): String {
        var s = name.lowercase()
        for (sep in listOf(":", " - ", " – ", " — ")) s.indexOf(sep).takeIf { it >= 0 }?.let { s = s.substring(0, it) }
        s = s.replace(PART, "").replace(NUMBER, "")
        val words = s.split(Regex("\\s+")).filter { it.isNotEmpty() && it !in STOP }
        return if (words.isEmpty()) s.trim() else words.take(2).joinToString(" ")
    }

    // ---- "for you" rows ----

    /** Row names per Browse category that say why the row is there. */
    val ROW_NAMES = mapOf(
        "Action" to "Action-Packed Picks for You", "Adventure" to "Adventures Worth Taking", "Comedy" to "Laugh-Out-Loud Comedies",
        "Drama" to "Gripping Dramas for You", "Crime" to "Dark Crime Stories", "Thriller" to "Edge-of-Your-Seat Thrillers",
        "Horror" to "Horror Picks for You", "Sci-Fi" to "Sci-Fi Worlds to Explore", "Fantasy" to "Fantasy Epics for You",
        "Romance" to "Love Stories for You", "Mystery" to "Mysteries to Unravel", "Animation" to "Animated Favorites for You",
        "Family" to "Family Favorites for You", "Documentary" to "Documentaries for You", "History" to "Period Pieces for You",
        "War" to "War Stories for You", "Western" to "Westerns for You", "Music" to "Music & Musicals for You",
        "Reality" to "Reality Picks for You", "Kids" to "Kids' Favorites",
    )

    /**
     * Up to [n] categories this profile would want a row of now (category name to row name): by affinity, with a nod
     * to the time of day ([moment]'s genres). Anime stays in its own sections; Kids is for kids profiles. Nothing for
     * a cold profile.
     */
    fun forYou(p: TasteProfile, kids: Boolean, moment: Set<Int>, n: Int = 2): List<Pair<String, String>> {
        if (p.strength < 0.25) return emptyList()
        return Discover.CATEGORIES.filter { it.name != "Anime" && (it.name != "Kids" || kids) && ROW_NAMES.containsKey(it.name) }
            .map { c ->
                val boost = if ((c.movie > 0 && c.movie in moment) || (c.tv > 0 && c.tv in moment)) 0.15 else 0.0
                c to p.affinity(c.movie, c.tv) + boost
            }
            .filter { it.second >= 0.35 }
            .sortedByDescending { it.second }
            .take(n)
            .map { it.first.name to ROW_NAMES.getValue(it.first.name) }
    }

    // ---- the opened ledger ----

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun openedKey(ctx: Context) = "opened" + Profiles.suffix(ctx)
    private fun snapshotKey(ctx: Context) = "v1" + Profiles.suffix(ctx)

    private fun opened(ctx: Context): JSONArray = try { JSONArray(prefs(ctx).getString(openedKey(ctx), "[]")) } catch (e: Exception) { JSONArray() }

    /** A title page opened ([Tmdb.details]): a weak signal, kept 60 days, at most 80. Off the main thread. */
    fun noteOpened(ctx: Context, t: Title, genreIds: List<Int>) {
        if (t.id <= 0) return
        val app = ctx.applicationContext
        Net.run {
            synchronized(this) {
                val now = System.currentTimeMillis()
                val old = opened(app)
                val out = JSONArray()
                out.put(JSONObject().put("k", t.key).put("g", JSONArray(genreIds)).put("l", t.lang).put("y", t.year.take(4)).put("at", now))
                for (i in 0 until old.length()) {
                    if (out.length() >= 80) break
                    val o = old.optJSONObject(i) ?: continue
                    if (o.optString("k") == t.key || now - o.optLong("at") > 60 * DAY_MS) continue
                    out.put(o)
                }
                prefs(app).edit().putString(openedKey(app), out.toString()).apply()
            }
        }
    }

    // ---- building ----

    private val memory = HashMap<String, TasteProfile>()
    private val building = HashSet<String>()
    /** When [current] last compared the snapshot with the history: rows load in bursts, one check covers a burst. */
    private val checkedAt = HashMap<String, Long>()

    /**
     * The profile's taste now: the last snapshot (memory, then prefs), rebuilt in the background when what it was built
     * from changed or it is 6 h old. Never waits on the network; [TasteProfile.COLD] until the first build lands.
     */
    fun current(ctx: Context): TasteProfile {
        val app = ctx.applicationContext
        val pid = Profiles.suffix(app)
        val t0 = System.currentTimeMillis()
        synchronized(memory) {
            val p = memory[pid]
            if (p != null && t0 - (checkedAt[pid] ?: 0L) < 5_000) return p
            checkedAt[pid] = t0
        }
        val history = WatchHistory.recent(app)
        val myList = Tmdb.myList(app)
        val sig = signature(app, history, myList)
        val have = synchronized(memory) { memory[pid] } ?: try {
            prefs(app).getString(snapshotKey(app), null)?.let { TasteProfile.fromJson(JSONObject(it)) }
        } catch (e: Exception) { null }?.also { p -> synchronized(memory) { memory[pid] = p } }
        val stale = have == null || have.signature != sig || System.currentTimeMillis() - have.builtAt > 6 * 3_600_000L
        if (stale && synchronized(memory) { building.add(pid) }) {
            Net.run {
                try {
                    val built = build(app, history, myList, sig)
                    synchronized(memory) { memory[pid] = built }
                    if (Profiles.suffix(app) == pid) prefs(app).edit().putString(snapshotKey(app), built.toJson().toString()).apply()
                    Log.i(TAG, "Taste: built from ${history.size} watched, ${myList.size} saved (strength ${"%.2f".format(built.strength)})")
                } finally { synchronized(memory) { building.remove(pid) } }
            }
        }
        return have ?: TasteProfile.COLD
    }

    private fun signature(ctx: Context, history: List<WatchHistory.Entry>, myList: List<Title>): String {
        val newest = history.maxOfOrNull { it.updatedAt } ?: 0L
        val openedAt = opened(ctx).optJSONObject(0)?.optLong("at") ?: 0L
        return "${history.size}|${newest / 1000}|${myList.size}|${openedAt / 1000}"
    }

    private class Seed(val type: String, val id: Int, var genres: List<Int>, var lang: String, var year: String, val weight: Double)

    /**
     * Weighted seeds -> affinities. Finished titles count most (1.0), then the next episode queued (0.9), then anything
     * watched a meaningful while (10 min, 0.6), then a short play (0.25); age halves a signal every 45 days (60
     * newest). A title removed from Continue Watching before finishing seeds nothing (owner). My List is a steady 0.5;
     * an opened page 0.15, halving every 14 days. Blocking: background only.
     */
    private fun build(ctx: Context, history: List<WatchHistory.Entry>, myList: List<Title>, signature: String): TasteProfile {
        val now = System.currentTimeMillis()
        val seeds = ArrayList<Seed>()
        val seen = HashSet<String>()
        for (e in history.sortedByDescending { it.updatedAt }) {
            if (e.tmdbId <= 0 || !seen.add(e.key)) continue
            if (e.hidden && e.progress < WatchHistory.FINISHED && !e.upNext) continue
            val w = when {
                e.progress >= WatchHistory.FINISHED || (e.hidden && e.progress >= 0.5f) -> 1.0
                e.upNext -> 0.9
                e.positionMs >= 10 * 60_000L -> 0.6
                else -> 0.25
            }
            val age = (now - e.updatedAt).coerceAtLeast(0) / DAY_MS.toDouble()
            seeds += Seed(if (e.isShow) "tv" else "movie", e.tmdbId, emptyList(), "", e.year?.toString().orEmpty(), w * Math.pow(0.5, age / 45))
            if (seeds.size >= 60) break
        }
        for (t in myList) if (t.id > 0 && seen.add(t.key)) seeds += Seed(t.type, t.id, t.genreIds, t.lang, t.year.take(4), 0.5)
        val opened = opened(ctx)
        for (i in 0 until opened.length()) {
            val o = opened.optJSONObject(i) ?: continue
            val k = o.optString("k")
            if (!seen.add(k)) continue
            val type = k.substringBefore(':')
            val id = k.substringAfter(':').toIntOrNull() ?: continue
            val g = o.optJSONArray("g")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
            val age = (now - o.optLong("at")).coerceAtLeast(0) / DAY_MS.toDouble()
            seeds += Seed(type, id, g, o.optString("l"), o.optString("y"), 0.15 * Math.pow(0.5, age / 14))
        }
        // Genres for seeds that came without them (the history and My List keep little), from the cached details call.
        val missing = seeds.filter { it.genres.isEmpty() && it.weight >= 0.1 }
        if (missing.isNotEmpty()) {
            val tasks = missing.map { s -> { WatchHistory.details(ctx, s.type, s.id) } }.toTypedArray()
            val found = try { RowPaging.all(*tasks) } catch (e: Exception) { emptyList() }
            for ((s, d) in missing.zip(found)) {
                d ?: continue
                s.genres = d.optJSONArray("genres")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optInt("id") } }.orEmpty()
                if (s.lang.isEmpty()) s.lang = d.optString("original_language")
                if (s.year.isEmpty()) s.year = d.optString(if (s.type == "movie") "release_date" else "first_air_date").take(4)
            }
        }
        val genre = HashMap<Int, Double>()
        val lang = HashMap<String, Double>()
        val era = HashMap<Int, Double>()
        var total = 0.0
        var shows = 0.0
        for (s in seeds) {
            total += s.weight
            if (s.type == "tv") shows += s.weight
            val anime = RowMix.isAnime(Title(s.id, s.type, "", "", null, null, s.year, 0.0, genreIds = s.genres, lang = s.lang))
            for (g in s.genres) if (g != 16 || !anime) genre[g] = (genre[g] ?: 0.0) + s.weight
            if (s.lang.isNotEmpty()) lang[s.lang] = (lang[s.lang] ?: 0.0) + s.weight
            s.year.toIntOrNull()?.takeIf { it > 1900 }?.let { era[it / 10 * 10] = (era[it / 10 * 10] ?: 0.0) + s.weight }
        }
        fun <K> norm(m: Map<K, Double>): Map<K, Double> { val mx = m.values.maxOrNull() ?: 0.0; return if (mx > 0) m.mapValues { it.value / mx } else emptyMap() }
        return TasteProfile(norm(genre), norm(lang), norm(era), if (total > 0) shows / total else 0.5, minOf(1.0, total / 6), signature, now)
    }
}
