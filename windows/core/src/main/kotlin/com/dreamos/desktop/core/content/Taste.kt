package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.HistoryEntry
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.util.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.concurrent.Executors
import kotlin.math.pow

/**
 * A profile's taste (fire-home Taste.kt, iPhone Taste.swift): what it finishes, keeps on My List and opens, turned into
 * genre, language and era affinities that rank every discovery row and choose Home's "for you" rows. Positive signals
 * only: nothing is learned from a title started and left (owner's rule), and a title removed from Continue Watching
 * before it was finished seeds nothing.
 */
@Serializable
data class TasteProfile(
    /** TMDB genre id -> 0..1 (1 = the genre this profile watches most). */
    val genre: Map<Int, Double> = emptyMap(),
    /** Original language -> 0..1. */
    val lang: Map<String, Double> = emptyMap(),
    /** Decade (1990, 2000, ...) -> 0..1. */
    val era: Map<Int, Double> = emptyMap(),
    /** Share of the signal that is shows (0.5 = even). */
    val shows: Double = 0.5,
    /** 0 = nothing known (cold start), 1 = a well-known taste. */
    val strength: Double = 0.0,
    val signature: String = "",
    val builtAt: Long = 0L,
) {
    /** How much this profile likes [t] (0..1): its genres' mean affinity, its language and its era. */
    fun affinity(t: Title): Double {
        val g = if (t.genreIds.isEmpty()) 0.0 else t.genreIds.sumOf { genre[it] ?: 0.0 } / t.genreIds.size
        val l = if (t.lang.isEmpty()) 0.0 else lang[t.lang] ?: 0.0
        val e = t.year.take(4).toIntOrNull()?.let { era[it / 10 * 10] } ?: 0.0
        return minOf(1.0, 0.7 * g + 0.2 * l + 0.1 * e)
    }

    /** Affinity for a Browse category (its movie and TV genre ids), the higher of the two. */
    fun affinity(movie: Int, tv: Int): Double =
        maxOf(if (movie > 0) genre[movie] ?: 0.0 else 0.0, if (tv > 0) genre[tv] ?: 0.0 else 0.0)

    companion object {
        val COLD = TasteProfile()
    }
}

object Taste {
    private const val DAY_MS = 86_400_000L

    /** Rows that keep their own order: charts, dated rows and the viewer's own lists. */
    private val KEEP_ORDER = listOf("Trending", "Top 10", "Coming Soon", "Continue Watching", "My List", "New Episodes for You", "In Theaters")

    fun ranks(row: String) = KEEP_ORDER.none { row.startsWith(it) }

    // ---- scoring ----

    /** TMDB's score steadied by its vote count (Bayesian average toward 6.4), mapped 5.5..8.5 to 0..1. */
    fun quality(t: Title): Double {
        val m = if (t.kind == MediaKind.MOVIE) 300.0 else 100.0
        val v = maxOf(0, t.votes).toDouble()
        val q = if (t.rating > 0 && v > 0) (v * t.rating + m * 6.4) / (v + m) else 6.0
        return ((q - 5.5) / 3.0).coerceIn(0.0, 1.0)
    }

    /**
     * [list] re-ranked for [p]: the list's own order still counts for half, taste for a quarter (scaled by how well it
     * is known), steadied quality for a fifth and freshness a little; then franchises are spread out. Stable.
     */
    fun rank(p: TasteProfile, list: List<Title>): List<Title> {
        if (list.size <= 2) return list
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
    private val SPACES = Regex("\\s+")
    private val SEPARATORS = listOf(":", " - ", " – ", " — ")

    /** A franchise key: "John Wick: Chapter 4" -> "john wick"; "Harry Potter and the Goblet of Fire" -> "harry potter". */
    fun stem(name: String): String {
        var s = name.lowercase()
        for (sep in SEPARATORS) s.indexOf(sep).takeIf { it >= 0 }?.let { s = s.substring(0, it) }
        s = s.replace(PART, "").replace(NUMBER, "")
        val words = s.split(SPACES).filter { it.isNotEmpty() && it !in STOP }
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

    /** A Browse category for [forYou]: its name and its movie / TV genre ids (Discover.CATEGORIES). */
    class Category(val name: String, val movie: Int, val tv: Int)

    /**
     * Up to [n] categories this profile would want a row of now (category name to row name): by affinity, with a nod
     * to the time of day ([moment]'s genres). Anime stays in its own sections; Kids is for kids profiles. Nothing for
     * a cold profile.
     */
    fun forYou(p: TasteProfile, kids: Boolean, moment: Set<Int>, categories: List<Category>, n: Int = 2): List<Pair<String, String>> {
        if (p.strength < 0.25) return emptyList()
        return categories.filter { it.name != "Anime" && (it.name != "Kids" || kids) && ROW_NAMES.containsKey(it.name) }
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

    @Serializable
    private data class Opened(val k: String, val g: List<Int> = emptyList(), val l: String = "", val y: String = "", val at: Long = 0)

    private val openedSer = ListSerializer(Opened.serializer())
    private fun prefs() = DreamStore.shared.profileFile("taste")
    private fun opened(): List<Opened> = prefs().get("opened", openedSer) ?: emptyList()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "taste").apply { isDaemon = true } }

    /** A title page opened: a weak signal, kept 60 days, at most 80. */
    fun noteOpened(t: Title, genreIds: List<Int>) {
        if (t.id <= 0) return
        io.execute {
            val now = System.currentTimeMillis()
            val out = listOf(Opened(t.key, genreIds, t.lang, t.year.take(4), now)) +
                opened().filter { it.k != t.key && now - it.at <= 60 * DAY_MS }.take(79)
            prefs().put("opened", openedSer, out)
        }
    }

    // ---- building ----

    private val memory = HashMap<String, TasteProfile>()
    private val building = HashSet<String>()
    private val checkedAt = HashMap<String, Long>()

    /**
     * The profile's taste now: the last snapshot (memory, then the store), rebuilt in the background when what it was
     * built from changed or it is 6 h old. Never waits on the network; [TasteProfile.COLD] until the first build lands.
     */
    fun current(): TasteProfile {
        val pid = DreamStore.currentProfile
        val t0 = System.currentTimeMillis()
        synchronized(memory) {
            val p = memory[pid]
            if (p != null && t0 - (checkedAt[pid] ?: 0L) < 5_000) return p
            checkedAt[pid] = t0
        }
        val history = WatchHistory.recent()
        val myList = MyListStore.items()
        val sig = signature(history, myList)
        val have = synchronized(memory) { memory[pid] } ?: prefs().get("v1", TasteProfile.serializer())
            ?.also { p -> synchronized(memory) { memory[pid] = p } }
        val stale = have == null || have.signature != sig || System.currentTimeMillis() - have.builtAt > 6 * 3_600_000L
        if (stale && synchronized(memory) { building.add(pid) }) {
            io.execute {
                try {
                    val built = build(history, myList, sig)
                    synchronized(memory) { memory[pid] = built }
                    if (DreamStore.currentProfile == pid) prefs().put("v1", TasteProfile.serializer(), built)
                    Log.i("Taste", "built from ${history.size} watched, ${myList.size} saved (strength ${"%.2f".format(built.strength)})")
                } catch (e: Exception) {
                    Log.w("Taste", "build failed: $e")
                } finally { synchronized(memory) { building.remove(pid) } }
            }
        }
        return have ?: TasteProfile.COLD
    }

    /** Builds now and waits (tests, and the first Home load right after setup). */
    fun buildNow(): TasteProfile {
        val history = WatchHistory.recent()
        val myList = MyListStore.items()
        val built = build(history, myList, signature(history, myList))
        synchronized(memory) { memory[DreamStore.currentProfile] = built; checkedAt[DreamStore.currentProfile] = System.currentTimeMillis() }
        return built
    }

    private fun signature(history: List<HistoryEntry>, myList: List<Title>): String {
        val newest = history.maxOfOrNull { it.updatedAt } ?: 0L
        val openedAt = opened().firstOrNull()?.at ?: 0L
        return "${history.size}|${newest / 1000}|${myList.size}|${openedAt / 1000}"
    }

    private class Seed(val type: MediaKind, val id: Int, var genres: List<Int>, var lang: String, var year: String, val weight: Double)

    /**
     * Weighted seeds -> affinities. Finished titles count most (1.0), then the next episode queued (0.9), then anything
     * watched a meaningful while (10 min, 0.6), then a short play (0.25); age halves a signal every 45 days (60
     * newest). A title removed before finishing seeds nothing. My List is a steady 0.5; an opened page 0.15, halving
     * every 14 days. Blocking: background only.
     */
    internal fun build(history: List<HistoryEntry>, myList: List<Title>, signature: String, now: Long = System.currentTimeMillis(),
                       lookup: Boolean = true): TasteProfile {
        val seeds = ArrayList<Seed>()
        val seen = HashSet<String>()
        for (e in history.sortedByDescending { it.updatedAt }) {
            if (e.title.id <= 0 || !seen.add(e.key)) continue
            if (e.hidden && e.progress < HistoryEntry.FINISHED && !e.upNext) continue
            val w = when {
                e.progress >= HistoryEntry.FINISHED || (e.hidden && e.progress >= 0.5) -> 1.0
                e.upNext -> 0.9
                e.positionMs >= 10 * 60_000L -> 0.6
                else -> 0.25
            }
            val age = (now - e.updatedAt).coerceAtLeast(0) / DAY_MS.toDouble()
            seeds += Seed(e.title.kind, e.title.id, e.title.genreIds, e.title.lang, e.title.year, w * 0.5.pow(age / 45))
            if (seeds.size >= 60) break
        }
        for (t in myList) if (t.id > 0 && seen.add(t.key)) seeds += Seed(t.kind, t.id, t.genreIds, t.lang, t.year.take(4), 0.5)
        for (o in opened()) {
            if (!seen.add(o.k)) continue
            val type = MediaKind.of(o.k.substringBefore(':')) ?: continue
            val id = o.k.substringAfter(':').toIntOrNull() ?: continue
            val age = (now - o.at).coerceAtLeast(0) / DAY_MS.toDouble()
            seeds += Seed(type, id, o.g, o.l, o.y, 0.15 * 0.5.pow(age / 14))
        }
        // Genres for seeds that came without them, from the cached details call.
        val missing = if (lookup) seeds.filter { it.genres.isEmpty() && it.weight >= 0.1 } else emptyList()
        if (missing.isNotEmpty()) {
            val found = try { RowPaging.all(*missing.map { s -> { WatchHistory.details(s.type, s.id) } }.toTypedArray()) } catch (e: Exception) { emptyList() }
            for ((s, d) in missing.zip(found)) {
                d ?: continue
                s.genres = d.a("genres").objects().map { it.int("id") }.filter { it > 0 }
                if (s.lang.isEmpty()) s.lang = d.str("original_language")
                if (s.year.isEmpty()) s.year = d.str(if (s.type == MediaKind.MOVIE) "release_date" else "first_air_date").take(4)
            }
        }
        val genre = HashMap<Int, Double>()
        val lang = HashMap<String, Double>()
        val era = HashMap<Int, Double>()
        var total = 0.0
        var shows = 0.0
        for (s in seeds) {
            total += s.weight
            if (s.type == MediaKind.TV) shows += s.weight
            val anime = RowMix.isAnime(Title(s.id, s.type, "", year = s.year, genreIds = s.genres, lang = s.lang))
            for (g in s.genres) if (g != 16 || !anime) genre[g] = (genre[g] ?: 0.0) + s.weight
            if (s.lang.isNotEmpty()) lang[s.lang] = (lang[s.lang] ?: 0.0) + s.weight
            s.year.toIntOrNull()?.takeIf { it > 1900 }?.let { era[it / 10 * 10] = (era[it / 10 * 10] ?: 0.0) + s.weight }
        }
        fun <K> norm(m: Map<K, Double>): Map<K, Double> { val mx = m.values.maxOrNull() ?: 0.0; return if (mx > 0) m.mapValues { it.value / mx } else emptyMap() }
        return TasteProfile(norm(genre), norm(lang), norm(era), if (total > 0) shows / total else 0.5, minOf(1.0, total / 6), signature, now)
    }
}
