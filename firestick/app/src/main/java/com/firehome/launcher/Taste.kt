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
    /** Share of the signal that is shows (0.5 = even). Read by the hero ([HomePicks.heroScore]). */
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
        return minOf(1.0, AlgoRules.AFF_GENRE * g + AlgoRules.AFF_LANG * l + AlgoRules.AFF_ERA * e)
    }

    /** Affinity for a Browse category (its movie and TV genre ids), the higher of the two. */
    fun affinity(movie: Int, tv: Int): Double =
        maxOf(if (movie > 0) genre[movie] ?: 0.0 else 0.0, if (tv > 0) genre[tv] ?: 0.0 else 0.0)

    /** The profile's lean to [type] against the other type, -1..1 (0 for an even or unknown taste), scaled by [strength]. */
    fun typeLean(type: String): Double = strength * 2 * ((if (type == "tv") shows else 1 - shows) - 0.5)

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
     * TMDB's score steadied by its vote count (a Bayesian average toward [AlgoRules.QUALITY_PRIOR]): 7.9 from 90 votes
     * reads about 7.1, 7.6 from 10,000 stays 7.6. Mapped 5.5..8.5 to 0..1.
     */
    fun quality(t: Title): Double {
        val m = if (t.type == "movie") AlgoRules.QUALITY_VOTES_MOVIE else AlgoRules.QUALITY_VOTES_TV
        val v = maxOf(0, t.votes).toDouble()
        val q = if (t.rating > 0 && v > 0) (v * t.rating + m * AlgoRules.QUALITY_PRIOR) / (v + m) else AlgoRules.QUALITY_UNKNOWN
        return ((q - AlgoRules.QUALITY_LOW) / AlgoRules.QUALITY_SPAN).coerceIn(0.0, 1.0)
    }

    /** The rank's weights as one value, so the harness can run another set through the same code. */
    class Weights(
        val order: Double = AlgoRules.W_ORDER, val taste: Double = AlgoRules.W_TASTE, val quality: Double = AlgoRules.W_QUALITY,
        val fresh: Double = AlgoRules.W_FRESH, val art: Double = AlgoRules.ART_PENALTY, val rotation: Double = AlgoRules.DAY_ROTATION,
        val gap: Int = AlgoRules.SPREAD_GAP,
    ) {
        companion object { val DEFAULT = Weights() }
    }

    /** Freshness: 1 within a year, 0.6 within three, 0.3 within ten, counted back from [today] ("yyyy-MM-dd"). */
    fun fresh(t: Title, today: String): Double {
        if (t.date.length != 10 || today.length != 10) return 0.0
        val y = today.take(4).toIntOrNull() ?: return 0.0
        fun yearsBack(n: Int) = (y - n).toString().padStart(4, '0') + today.substring(4)
        return when {
            t.date >= yearsBack(1) -> AlgoRules.FRESH_1Y
            t.date >= yearsBack(3) -> AlgoRules.FRESH_3Y
            t.date >= yearsBack(10) -> AlgoRules.FRESH_10Y
            else -> 0.0
        }
    }

    /**
     * A title's place in the day's rotation, 0..1: fixed for [key] on [day], unrelated to the day before. The same
     * all day (nothing moves while anyone looks), another order tomorrow. String.hashCode is specified, so the three
     * apps can share it.
     */
    fun rotation(key: String, day: Int): Double {
        var h = key.hashCode() * 31 + day
        h = h xor (h ushr 16); h *= 0x45d9f3b; h = h xor (h ushr 16); h *= 0x45d9f3b; h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535.0
    }

    /**
     * [list] re-ranked for [p]: the list's own order (TMDB's intent) still counts most, taste a quarter (scaled by how
     * well it is known), steadied quality a fifth, freshness a little, a small daily rotation, and for a landscape row
     * ([artFirst]) a small penalty on a title with no backdrop. Stable: equal scores keep the list's order, so a cold
     * profile sees popularity tempered by quality, never a shuffle. Then franchises are spread out.
     */
    fun rank(p: TasteProfile, list: List<Title>, artFirst: Boolean = false, today: String = DreamTime.todayYmd(),
             day: Int = RowMix.dayNumber(), w: Weights = Weights.DEFAULT): List<Title> {
        if (list.size <= 1) return list
        val n = (list.size - 1).toDouble()
        val scored = list.mapIndexed { i, t -> Triple(t, score(p, t, i, n, artFirst, today, day, w), i) }
            .sortedWith(compareByDescending<Triple<Title, Double, Int>> { it.second }.thenBy { it.third }).map { it.first }
        return spread(scored, w.gap)
    }

    /** One title's score in [rank]: [i] of a list whose last index is [n]. */
    fun score(p: TasteProfile, t: Title, i: Int, n: Double, artFirst: Boolean, today: String, day: Int, w: Weights = Weights.DEFAULT): Double =
        w.order * (1 - i / n) + w.taste * p.strength * p.affinity(t) + w.quality * quality(t) + w.fresh * fresh(t, today) +
            w.rotation * rotation(t.key, day) - (if (artFirst && t.backdropPath == null) w.art else 0.0)

    /**
     * Titles of one franchise ([stem]) at least [gap] cards apart where the list allows it: each slot takes the best
     * remaining title whose stem was last placed [gap] or more slots ago, else the one placed longest ago (a row of
     * eight entries of one saga still shows every one). Titles with no shared stem keep their order; nothing is dropped.
     */
    fun spread(list: List<Title>, gap: Int = AlgoRules.SPREAD_GAP, key: (Title) -> String = { stem(it.name) }): List<Title> {
        if (list.size <= 2 || gap <= 1) return list
        val stems = list.map(key)
        if (stems.toHashSet().size == stems.size) return list
        val out = ArrayList<Title>(list.size)
        val left = list.indices.toMutableList()
        val lastAt = HashMap<String, Int>()
        while (left.isNotEmpty()) {
            val slot = out.size
            var pick = -1
            var oldest = Int.MAX_VALUE
            var oldestIdx = 0
            for ((k, i) in left.withIndex()) {
                val at = lastAt[stems[i]]
                if (at == null || slot - at >= gap) { pick = k; break }
                if (at < oldest) { oldest = at; oldestIdx = k }
            }
            val i = left.removeAt(if (pick >= 0) pick else oldestIdx)
            lastAt[stems[i]] = slot
            out += list[i]
        }
        return out
    }

    private val STOP = setOf("the", "a", "an", "of", "and", "in", "on", "to", "for", "with", "at", "from", "by", "&")
    /** Words a sequel adds to its franchise's name, dropped from the end ("a hero returns" -> "a hero"). */
    private val SEQUEL = setOf("begins", "returns", "forever", "rises", "reloaded", "revolutions", "resurrection", "resurrections",
        "reborn", "origins", "awakens", "unleashed", "evolution", "revenge", "legacy", "generations", "revisited", "untold")
    private const val NUMBER_WORDS = "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen"
    private val PART = Regex("\\s+(part|chapter|vol\\.?|volume|episode)\\s+([0-9ivx]+|$NUMBER_WORDS)$")
    private val NUMBER = Regex("\\s+([0-9]+|ii|iii|iv|v|vi|vii|viii|ix|$NUMBER_WORDS)$")
    private val SPACES = Regex("\\s+")
    /** A subtitle starts at a colon, a dash, or "and the" (the shape of many sequel names). */
    private val SEPARATORS = listOf(":", " - ", " – ", " — ", " and the ")

    /**
     * A franchise key: the name up to a colon, dash or "and the", without a trailing number, part or sequel word, then
     * its first two telling words. "The Saga: Chapter 4" -> "saga"; "The Saga and the Long Subtitle" -> "saga"; "Saga
     * Thirteen" -> "saga"; "Hero Returns" -> "hero". Only spreads a row, never drops a title.
     */
    fun stem(name: String): String {
        var s = name.lowercase().trim()
        for (sep in SEPARATORS) s.indexOf(sep).takeIf { it >= 0 }?.let { s = s.substring(0, it) }
        s = s.replace(PART, "").replace(NUMBER, "").trim()
        val words = s.split(SPACES).filter { it.isNotEmpty() && it !in STOP }.toMutableList()
        while (words.size > 1 && words.last() in SEQUEL) words.removeAt(words.size - 1)
        return if (words.isEmpty()) s else words.take(2).joinToString(" ")
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
    fun forYou(p: TasteProfile, kids: Boolean, moment: Set<Int>, n: Int = AlgoRules.FOR_YOU_ROWS): List<Pair<String, String>> {
        if (p.strength < AlgoRules.FOR_YOU_MIN_STRENGTH) return emptyList()
        return Discover.CATEGORIES.filter { it.name != "Anime" && (it.name != "Kids" || kids) && ROW_NAMES.containsKey(it.name) }
            .map { c ->
                val boost = if ((c.movie > 0 && c.movie in moment) || (c.tv > 0 && c.tv in moment)) AlgoRules.FOR_YOU_MOMENT_BOOST else 0.0
                c to p.affinity(c.movie, c.tv) + boost
            }
            .filter { it.second >= AlgoRules.FOR_YOU_MIN_AFFINITY }
            .sortedByDescending { it.second }
            .take(n)
            .map { it.first.name to ROW_NAMES.getValue(it.first.name) }
    }

    // ---- the opened ledger ----

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun openedKey(ctx: Context) = "opened" + Profiles.suffix(ctx)
    private fun snapshotKey(ctx: Context) = "v1" + Profiles.suffix(ctx)

    private fun opened(ctx: Context): JSONArray = try { JSONArray(prefs(ctx).getString(openedKey(ctx), "[]")) } catch (e: Exception) { JSONArray() }

    /** A title page opened ([Tmdb.details]): a weak signal, kept [AlgoRules.OPENED_KEEP_DAYS] days, at most [AlgoRules.OPENED_MAX]. Off the main thread. */
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
                    if (out.length() >= AlgoRules.OPENED_MAX) break
                    val o = old.optJSONObject(i) ?: continue
                    if (o.optString("k") == t.key || now - o.optLong("at") > AlgoRules.OPENED_KEEP_DAYS * DAY_MS) continue
                    out.put(o)
                }
                prefs(app).edit().putString(openedKey(app), out.toString()).apply()
            }
        }
    }

    /** One opened page as [seeds] reads it. */
    class Opened(val key: String, val genres: List<Int>, val lang: String, val year: String, val at: Long)

    private fun openedList(ctx: Context): List<Opened> {
        val a = opened(ctx)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Opened(o.optString("k"), o.optJSONArray("g")?.let { g -> (0 until g.length()).map { g.optInt(it) } }.orEmpty(),
                o.optString("l"), o.optString("y"), o.optLong("at"))
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
        val opened = openedList(app)
        val sig = signature(history, myList, opened)
        val have = synchronized(memory) { memory[pid] } ?: try {
            prefs(app).getString(snapshotKey(app), null)?.let { TasteProfile.fromJson(JSONObject(it)) }
        } catch (e: Exception) { null }?.also { p -> synchronized(memory) { memory[pid] = p } }
        val stale = have == null || have.signature != sig || System.currentTimeMillis() - have.builtAt > AlgoRules.SNAPSHOT_MAX_AGE_MS
        if (stale && synchronized(memory) { building.add(pid) }) {
            Net.run {
                try {
                    val seeds = seeds(history, myList, opened, System.currentTimeMillis()) { show -> Watched.episodeCount(app, show) }
                    fillDetails(app, seeds)
                    val built = profileOf(seeds, sig, System.currentTimeMillis())
                    synchronized(memory) { memory[pid] = built }
                    if (Profiles.suffix(app) == pid) prefs(app).edit().putString(snapshotKey(app), built.toJson().toString()).apply()
                    val top = built.genre.entries.sortedByDescending { it.value }.take(3)
                        .joinToString { "${Discover.genreName("movie", it.key) ?: Discover.genreName("tv", it.key) ?: it.key} ${"%.2f".format(it.value)}" }
                    Log.i(TAG, "Taste: built from ${seeds.count { it.kind == Seed.WATCH }} finished, ${seeds.count { it.kind == Seed.SAVED }} saved, " +
                        "${seeds.count { it.kind == Seed.OPENED }} opened: strength ${"%.2f".format(built.strength)}, shows ${"%.2f".format(built.shows)}; $top")
                } finally { synchronized(memory) { building.remove(pid) } }
            }
        }
        return have ?: TasteProfile.COLD
    }

    /**
     * A fingerprint of what a profile is built from: every history entry with how it seeds and its day, every saved
     * title, every opened page with its day. One title swapped for another on My List changes it; a count alone didn't.
     */
    fun signature(history: List<WatchHistory.Entry>, myList: List<Title>, opened: List<Opened>): String {
        val sb = StringBuilder()
        for (e in history) {
            if (e.tmdbId <= 0) continue
            sb.append(e.key).append(':').append(seedState(e)).append(':').append(e.updatedAt / DAY_MS).append(';')
        }
        sb.append('|')
        for (t in myList) sb.append(t.key).append(';')
        sb.append('|')
        for (o in opened) sb.append(o.key).append(':').append(o.at / DAY_MS).append(';')
        // FNV-1a over the whole string: a 64-bit fingerprint, stable across the three apps.
        var h = -0x340d631b7bdddcdbL
        for (c in sb) { h = h xor c.code.toLong(); h *= 0x100000001b3L }
        return "${history.size}|${myList.size}|${opened.size}|${java.lang.Long.toHexString(h)}"
    }

    /** How [e] seeds taste: 'f' finished, 'e' a show with finished episodes (Up Next queued), '-' nothing. */
    private fun seedState(e: WatchHistory.Entry): Char = when {
        BecauseYouWatched.removed(e) -> '-'
        e.progress >= WatchHistory.FINISHED -> 'f'
        e.upNext -> 'e'
        else -> '-'
    }

    /** One weighted signal: a title ([type], [id]) with what is known of it and why it counts ([kind]). */
    class Seed(val type: String, val id: Int, var genres: List<Int>, var lang: String, var year: String, var origin: List<String>, val weight: Double, val kind: Int) {
        companion object { const val WATCH = 0; const val SAVED = 1; const val OPENED = 2 }
    }

    /**
     * Weighted seeds, STANDARDS section 5: a finished movie counts [AlgoRules.SEED_FINISHED]; a show the viewer
     * finishes episodes of (the entry finished, an Up Next queued, or episodes in the Watched ledger:
     * [finishedEpisodes]) [AlgoRules.SEED_EPISODES]; age halves a signal every 45 days (the [AlgoRules.HISTORY_SEEDS]
     * newest). A finished special counts as a title of its own, not as progress through its show. A title only
     * started, or removed from Continue Watching before finishing, seeds nothing. My List a
     * steady 0.5; an opened page 0.15, halving every 14 days. Pure: no network, no Context.
     */
    fun seeds(history: List<WatchHistory.Entry>, myList: List<Title>, opened: List<Opened>, now: Long, finishedEpisodes: (Int) -> Int = { 0 }): List<Seed> {
        val seeds = ArrayList<Seed>()
        val seen = HashSet<String>()
        for (e in history.sortedByDescending { it.updatedAt }) {
            if (e.tmdbId <= 0 || !seen.add(e.key)) continue
            val w = when {
                // Clearing a card is "not for me" only while it could still be a sample. Past
                // [AlgoRules.KEPT_AFTER_EPISODES] finished episodes it means "done with it for now", which is
                // something the viewer liked, so the show keeps its signal.
                BecauseYouWatched.removed(e)
                    && !(e.isSeries && finishedEpisodes(e.tmdbId) >= AlgoRules.KEPT_AFTER_EPISODES) -> continue
                e.progress >= WatchHistory.FINISHED -> if (e.isSeries) AlgoRules.SEED_EPISODES else AlgoRules.SEED_FINISHED
                e.upNext || (e.isSeries && finishedEpisodes(e.tmdbId) > 0) -> AlgoRules.SEED_EPISODES
                else -> continue
            }
            val age = (now - e.updatedAt).coerceAtLeast(0) / DAY_MS.toDouble()
            seeds += Seed(if (e.ofShow) "tv" else "movie", e.tmdbId, emptyList(), "", e.year?.toString().orEmpty(), emptyList(),
                w * Math.pow(0.5, age / AlgoRules.WATCH_HALF_LIFE_DAYS), Seed.WATCH)
            if (seeds.size >= AlgoRules.HISTORY_SEEDS) break
        }
        for (t in myList) if (t.id > 0 && seen.add(t.key)) seeds += Seed(t.type, t.id, t.genreIds, t.lang, t.year.take(4), t.originCountry, AlgoRules.SEED_SAVED, Seed.SAVED)
        for (o in opened) {
            if (!seen.add(o.key)) continue
            val type = o.key.substringBefore(':')
            val id = o.key.substringAfter(':').toIntOrNull() ?: continue
            val age = (now - o.at).coerceAtLeast(0) / DAY_MS.toDouble()
            seeds += Seed(type, id, o.genres, o.lang, o.year, emptyList(), AlgoRules.SEED_OPENED * Math.pow(0.5, age / AlgoRules.OPENED_HALF_LIFE_DAYS), Seed.OPENED)
        }
        return seeds
    }

    /**
     * Genres, language, year and origin for seeds that came without them (the history keeps little), from the cached
     * details call. Only seeds that weigh enough to matter; a seed the call fails for stays as it is. Blocking.
     */
    fun fillDetails(ctx: Context, seeds: List<Seed>) {
        val missing = seeds.filter { it.genres.isEmpty() && it.weight >= 0.1 }
        if (missing.isEmpty()) return
        val tasks = missing.map { s -> { WatchHistory.details(ctx, s.type, s.id) } }.toTypedArray()
        val found = try { RowPaging.all(*tasks) } catch (e: Exception) { emptyList() }
        for ((s, d) in missing.zip(found)) {
            d ?: continue
            s.genres = d.optJSONArray("genres")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optInt("id") } }.orEmpty()
            if (s.lang.isEmpty()) s.lang = d.optString("original_language")
            if (s.year.isEmpty()) s.year = d.optString(if (s.type == "movie") "release_date" else "first_air_date").take(4)
            if (s.origin.isEmpty()) s.origin = Tmdb.countries(d)
        }
    }

    /**
     * [seeds] -> affinities (each map normalised to its strongest entry), the show share, and strength =
     * 1 - exp(-weight / [AlgoRules.STRENGTH_SCALE]) where opened pages count only up to [AlgoRules.OPENED_STRENGTH_CAP].
     * Anime's Animation genre is not counted toward Animation. Pure.
     */
    fun profileOf(seeds: List<Seed>, signature: String, now: Long, scale: Double = AlgoRules.STRENGTH_SCALE): TasteProfile {
        val genre = HashMap<Int, Double>()
        val lang = HashMap<String, Double>()
        val era = HashMap<Int, Double>()
        var watched = 0.0
        var openedTotal = 0.0
        var shows = 0.0
        for (s in seeds) {
            if (s.kind == Seed.OPENED) openedTotal += s.weight else watched += s.weight
            if (s.type == "tv") shows += s.weight
            val anime = RowMix.animeBy(s.genres, s.lang, s.type, s.origin)
            for (g in s.genres) if (g != 16 || !anime) genre[g] = (genre[g] ?: 0.0) + s.weight
            if (s.lang.isNotEmpty()) lang[s.lang] = (lang[s.lang] ?: 0.0) + s.weight
            s.year.toIntOrNull()?.takeIf { it > 1900 }?.let { era[it / 10 * 10] = (era[it / 10 * 10] ?: 0.0) + s.weight }
        }
        fun <K> norm(m: Map<K, Double>): Map<K, Double> { val mx = m.values.maxOrNull() ?: 0.0; return if (mx > 0) m.mapValues { it.value / mx } else emptyMap() }
        val all = watched + openedTotal
        val counted = watched + minOf(openedTotal, AlgoRules.OPENED_STRENGTH_CAP)
        return TasteProfile(norm(genre), norm(lang), norm(era), if (all > 0) shows / all else 0.5, strength(counted, scale), signature, now)
    }

    /** The confidence curve: diminishing returns, no ceiling at six titles. [scale] is a parameter only so the harness can compare curves. */
    fun strength(weight: Double, scale: Double = AlgoRules.STRENGTH_SCALE): Double = if (weight <= 0) 0.0 else 1 - Math.exp(-weight / scale)

    /**
     * One log line per ranked row (page 1): what the rank moved into the first screen and how far. Reading it against
     * the row on the TV is how a ranking decision is checked.
     */
    fun explain(row: String, p: TasteProfile, before: List<Title>, after: List<Title>) {
        if (before.isEmpty() || RowPaging.page > 1) return
        val was = HashMap<String, Int>().apply { before.forEachIndexed { i, t -> put(t.key, i) } }
        val moves = after.take(6).withIndex().filter { (i, t) -> (was[t.key] ?: i) - i >= 3 }
            .joinToString { (i, t) -> "${t.key} (${(was[t.key] ?: i) + 1} -> ${i + 1})" }
        val noArt = after.take(6).count { it.backdropPath == null }
        Log.i(TAG, "Taste: '$row' ranked ${after.size} (strength ${"%.2f".format(p.strength)})" +
            (if (moves.isNotEmpty()) ", up: $moves" else "") + (if (noArt > 0) ", $noArt without a backdrop in the first six" else ""))
    }
}
