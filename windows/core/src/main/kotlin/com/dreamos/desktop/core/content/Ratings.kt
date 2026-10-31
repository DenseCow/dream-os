package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.Scores
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.model.TitleDetails
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.time.DreamTime
import com.dreamos.desktop.core.util.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * Scores (fire-home Ratings.kt, the iPhone's Ratings actor): the Rotten Tomatoes critic score and the IMDb rating
 * from OMDb with the viewer's own key (free: 1,000 lookups a day), the tomatometer from rottentomatoes.com when OMDb
 * has none ([RtWeb], title pages only), and the audience score exactly as published: "IMDb 6.5", else "TMDB 7.1"
 * once 150 people voted. No stars anywhere (STANDARDS §5). The age rating and TMDB's own score come with the title's
 * details, so no TMDB request is made here.
 *
 * Spending: an IMDb id is sent to OMDb at most once a session; a daily budget stops prefetching at
 * [PREFETCH_BUDGET] and everything at [DAY_BUDGET]; a wrong key or no answer rests OMDb a few minutes. Nothing ever
 * reports an error to the viewer.
 *
 * Caches: OMDb answers live 7 days for titles from the last 12 months, 60 days for older ones, 14 days for misses;
 * the website's score 30 days (14 for a miss). Memory: [MAX_MEMORY] entries (~150 bytes each: ~150 KB). Disk: the
 * `ratings` file, [MAX_DISK] entries (~120 KB; past the cap the oldest quarter goes). The session's tried set holds
 * at most a day's budget of ids (1,000).
 */
object Ratings {
    private const val DAY = 86_400_000L
    private const val RECENT_TTL = 7 * DAY
    private const val OLD_TTL = 60 * DAY
    private const val MISS_TTL = 14 * DAY
    private const val WEB_HIT_TTL = 30 * DAY
    private const val WEB_MISS_TTL = 14 * DAY
    const val PREFETCH_BUDGET = 700
    const val DAY_BUDGET = 1000
    private const val MAX_DISK = 800
    private const val MAX_MEMORY = 1024
    private const val OMDB = "https://www.omdbapi.com/"
    /** TMDB's score counts as the audience score only from this many votes (early TMDB scores run high). */
    const val AUDIENCE_VOTES = 150
    /** A Tomatometer this high is Fresh, below it Rotten (Rotten Tomatoes' own line). */
    const val FRESH_FROM = 60

    /**
     * What OMDb and the website said about one title. [rt] critic score 0-100 and [imdb] "7.8" as of [omdbAt] (0 =
     * never asked); [rtWeb] the website's score as of [rtWebAt]; [at] when this entry was last written.
     */
    @Serializable
    data class Entry(
        val imdbId: String? = null,
        val released: String = "",
        val rt: Int? = null,
        val imdb: String? = null,
        val omdbAt: Long = 0,
        val rtWeb: Int? = null,
        val rtWebAt: Long = 0,
        val at: Long = 0,
    ) {
        /** The Tomatometer to show: OMDb's, else the website's. */
        val tomatometer: Int? get() = rt ?: rtWeb
    }

    // ---- the viewer's OMDb key ----

    private fun keys() = DreamStore.shared.file("omdb")

    /** The viewer's own OMDb key ("" until setup's Connect chapter or Settings saves one). */
    fun key(): String = keys().getString("key")?.trim().orEmpty()

    fun hasKey() = key().isNotBlank()

    /** Saves [k]; failures under the old key no longer hold anything back. */
    fun setKey(k: String) {
        keys().putString("key", k.trim())
        backoffUntil = 0L
        synchronized(tried) { tried.clear() }
    }

    /**
     * True when OMDb answers a real lookup with [k] (The Godfather), false when it says the key is wrong. Throws when
     * OMDb can't be reached. Blocking.
     */
    fun checkKey(k: String): Boolean {
        if (k.isBlank()) return false
        val r = Net.request("GET", "$OMDB?i=tt0068646&apikey=${Net.encode(k.trim())}", timeoutMs = 8_000)
        if (r.code == 401) return false
        if (!r.ok) throw java.io.IOException("OMDb answered ${r.code}")
        val o = runCatching { parseJson(r.body) as? JsonObject }.getOrNull() ?: throw java.io.IOException("OMDb answer unreadable")
        return o.str("Response") == "True"
    }

    // ---- the scores ----

    private val memory = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > MAX_MEMORY
    }
    /** IMDb ids already sent to OMDb this session (answered, missed or failed): never asked twice. */
    private val tried = HashSet<String>()
    /** After a wrong key or no answer, OMDb rests for a while. */
    @Volatile private var backoffUntil = 0L

    /**
     * [d]'s scores, asking OMDb (and, for a title page, the website) when the cached answer is stale and the budget
     * allows. [prefetch]: a card or hero asking ahead (stops at [PREFETCH_BUDGET], never asks the website). Blocking.
     */
    fun scores(d: TitleDetails, prefetch: Boolean = false): Scores {
        val t = d.title
        val old = entry(t.key)
        // The OMDb part survives as long as the IMDb id is the same.
        var e = if (old != null && old.imdbId == d.imdbId) old.copy(released = t.date) else Entry(imdbId = d.imdbId, released = t.date)
        var changed = old == null || old.imdbId != d.imdbId
        if (wantsOmdb(e) && spend(prefetch)) {
            val asked = askOmdb(e)
            if (asked !== e) { e = asked; changed = true }
        }
        if (!prefetch && wantsRtWeb(t, e)) {
            RtWeb.lookup(t)?.let { res ->
                e = e.copy(rtWeb = res.score, rtWebAt = System.currentTimeMillis())
                changed = true
            }
        }
        if (changed && (e.omdbAt > 0 || e.rtWebAt > 0)) save(t.key, e)
        else synchronized(memory) { memory[t.key] = e }
        return scoresOf(e, t, certificationOf(d))
    }

    /** What's known about [d]'s scores without asking anyone (a page's first frame, a card). */
    fun cached(d: TitleDetails): Scores = scoresOf(entry(d.title.key), d.title, certificationOf(d))

    /** The same for a bare title (a card): the tomato and IMDb as cached, else TMDB's score after 150 votes. */
    fun cached(t: Title, certification: String = ""): Scores = scoresOf(entry(t.key), t, certification)

    private fun certificationOf(d: TitleDetails): String =
        d.info?.cert?.ifEmpty { null } ?: Details.certification(d.title.kind, d.title.id).orEmpty()

    /** The model's [Scores] from a cached [e] (null: nothing asked yet). */
    fun scoresOf(e: Entry?, t: Title, certification: String = ""): Scores {
        val tomato = e?.tomatometer
        return Scores(tomatometer = tomato, tomato = tomato(tomato), audienceBadge = audience(e?.imdb, t.rating, t.votes),
            certification = certification)
    }

    /** Fresh from [FRESH_FROM], else Rotten; null when unknown. (Certified Fresh isn't published by either source.) */
    fun tomato(score: Int?): Scores.Tomato? = when {
        score == null -> null
        score >= FRESH_FROM -> Scores.Tomato.FRESH
        else -> Scores.Tomato.ROTTEN
    }

    /**
     * The audience score beside the tomato, exactly as published, never rounded into stars: IMDb's ("IMDb 6.5"), else
     * TMDB's once [AUDIENCE_VOTES] people voted ("TMDB 7.1"). Null when neither.
     */
    fun audience(imdb: String?, tmdbScore: Double, tmdbVotes: Int): String? {
        imdb?.trim()?.takeIf { it.isNotEmpty() && it != "N/A" }?.let { return "IMDb $it" }
        if (tmdbScore <= 0 || tmdbVotes < AUDIENCE_VOTES) return null
        return "TMDB " + String.format(Locale.US, "%.1f", tmdbScore)
    }

    // ---- freshness ----

    private fun recent(released: String): Boolean = DreamTime.daysFromToday(released)?.let { it > -365 } ?: true

    private fun omdbTtl(e: Entry) = when {
        e.rt == null && e.imdb == null -> MISS_TTL
        recent(e.released) -> RECENT_TTL
        else -> OLD_TTL
    }

    private fun omdbFresh(e: Entry) = e.omdbAt > 0 && System.currentTimeMillis() - e.omdbAt < omdbTtl(e)

    /** An OMDb lookup could still add something: key set, IMDb id known, cached answer stale, not tried this session. */
    private fun wantsOmdb(e: Entry): Boolean = e.imdbId != null && hasKey() && !omdbFresh(e) &&
        synchronized(tried) { e.imdbId !in tried } && System.currentTimeMillis() >= backoffUntil

    private fun rtWebFresh(e: Entry) = e.rtWebAt > 0 &&
        System.currentTimeMillis() - e.rtWebAt < if (e.rtWeb != null) WEB_HIT_TTL else WEB_MISS_TTL

    /** OMDb answered without a Tomatometer, and the website hasn't been asked lately (or this session). */
    private fun wantsRtWeb(t: Title, e: Entry): Boolean =
        hasKey() && e.omdbAt > 0 && e.rt == null && !rtWebFresh(e) && RtWeb.mayTry(t)

    // ---- OMDb ----

    /** One OMDb lookup for [e]'s IMDb id: [e] updated, or [e] itself when nothing was learned. */
    private fun askOmdb(e: Entry): Entry {
        val id = e.imdbId ?: return e
        synchronized(tried) { if (!tried.add(id)) return e }
        val now = System.currentTimeMillis()
        try {
            val r = Net.request("GET", "$OMDB?i=${Net.encode(id)}&apikey=${Net.encode(key())}", timeoutMs = 8_000)
            if (!r.ok) {
                // A wrong key comes back as 401: rest either way.
                backoffUntil = now + 2 * 60_000
                Log.w("Ratings", "OMDb answered ${r.code} for $id")
                return e
            }
            val json = parseJson(r.body) as? JsonObject ?: return e
            parseOmdb(json)?.let { (rt, imdb) -> return e.copy(rt = rt, imdb = imdb, omdbAt = now) }
            val err = json.str("Error")
            when {
                err.contains("not found", ignoreCase = true) -> return e.copy(rt = null, imdb = null, omdbAt = now) // a miss, kept 14 days
                err.contains("limit", ignoreCase = true) -> exhaust()
                else -> backoffUntil = now + 10 * 60_000
            }
            Log.w("Ratings", "OMDb: $err")
        } catch (x: Exception) {
            backoffUntil = now + 2 * 60_000
            Log.w("Ratings", "OMDb unavailable for $id (${x.javaClass.simpleName})") // never the URL: it holds the key
        }
        return e
    }

    /** An OMDb answer's (Tomatometer, IMDb rating); null when it isn't a found title ("Response": "False"). */
    fun parseOmdb(json: JsonObject): Pair<Int?, String?>? {
        if (json.str("Response") != "True") return null
        val rt = json.a("Ratings").objects().firstOrNull { it.str("Source") == "Rotten Tomatoes" }
            ?.str("Value")?.trim()?.trimEnd('%')?.toIntOrNull()?.coerceIn(0, 100)
        val imdb = json.str("imdbRating").toDoubleOrNull()?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f", it) }
        return rt to imdb
    }

    // ---- daily budget (OMDb's free key: 1,000 a day), shared file with RtWeb ----

    internal fun budget() = DreamStore.shared.file("ratings_budget")

    /** OMDb lookups spent today. */
    fun usedToday(): Int {
        val p = budget()
        return if (p.getString("day") == DreamTime.todayYmd()) p.getInt("count") else 0
    }

    /** Counts one lookup if today's budget allows it: a prefetch stops at [PREFETCH_BUDGET], everything at [DAY_BUDGET]. */
    private fun spend(prefetch: Boolean): Boolean = synchronized(this) {
        val used = usedToday()
        if (used >= DAY_BUDGET || (prefetch && used >= PREFETCH_BUDGET)) return false
        val n = used + 1
        budget().edit { putString("day", DreamTime.todayYmd()); putInt("count", n) }
        if (n == PREFETCH_BUDGET) Log.w("Ratings", "OMDb: $PREFETCH_BUDGET lookups today, prefetch paused until tomorrow")
        if (n == DAY_BUDGET) Log.w("Ratings", "OMDb: $DAY_BUDGET lookups today, paused until tomorrow")
        true
    }

    /** OMDb says the key's limit is reached: the rest of today's budget is spent. */
    private fun exhaust() = synchronized(this) {
        budget().edit { putString("day", DreamTime.todayYmd()); putInt("count", DAY_BUDGET) }
    }

    // ---- cache ----

    private fun disk() = DreamStore.shared.file("ratings")

    private fun entry(key: String): Entry? {
        synchronized(memory) { memory[key] }?.let { return it }
        val e = disk().get(key, Entry.serializer()) ?: return null
        synchronized(memory) { memory[key] = e }
        return e
    }

    private fun save(key: String, e0: Entry) {
        val e = e0.copy(at = System.currentTimeMillis())
        synchronized(memory) { memory[key] = e }
        val f = disk()
        val drop = if (f.keys.size < MAX_DISK) emptyList() else f.keys.filter { it != key }
            .sortedBy { f.get(it, Entry.serializer())?.at ?: 0L }.take(MAX_DISK / 4)
        f.edit {
            putJson(key, com.dreamos.desktop.core.store.Prefs.JSON.encodeToJsonElement(Entry.serializer(), e))
            drop.forEach { remove(it) }
        }
    }
}
