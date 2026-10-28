package com.firehome.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/*
 * Where skip markers come from. Everything here is blocking and Android-free (bar org.json) so it can be
 * checked on a plain JVM; SkipMarkers.kt does the threading, caching and the on-device learning.
 *
 * Sources, verified live (Oct 2026):
 *  - TheIntroDB  GET https://api.theintrodb.org/v4/media?tmdb_id=&season=&episode=[&duration_ms=]
 *                No key. 30 req / 10 s and 500 req / day per IP. 404 = nothing submitted / no such episode.
 *                Segments: intro / recap / credits / preview, each [{start_ms|null, end_ms|null}] (null start =
 *                from 0, null end = to the end). duration_ms picks the closest release cut (within 5 min).
 *  - AniSkip     GET https://api.aniskip.com/v2/skip-times/{malId}/{ep}?types[]=op&types[]=ed&types[]=recap&episodeLength=
 *                No key, 120 req per window. episodeLength (s) filters out submissions for other cuts, which
 *                also guards against a wrong episode mapping. Keyed by MyAnimeList id.
 *  - ARM         GET https://arm.haglund.dev/api/v2/themoviedb?id={tmdbShowId} -> MAL/Kitsu ids per anime entry.
 *  - Kitsu       GET https://kitsu.io/api/edge/anime?filter[id]=a,b → episodeCount + startDate (only to place an
 *                episode inside cour splits, e.g. TMDB "Attack on Titan" S3E15 = MAL 38524 ep 3).
 */

/** Markers plus where they came from, for the controller (no auto-skip on guesses, no "estimated" ticks). */
internal class SkipInfo(
    val markers: Markers,
    val introLearned: Boolean = false,
    val creditsEstimated: Boolean = false,
)

internal object SkipSources {
    private const val TAG = "SkipMarkers"
    private val UA = "DreamOS/${BuildConfig.VERSION_NAME} (Fire TV launcher)"

    class Http(val code: Int, val body: String?, val retryAfterSec: Int = 0)

    fun get(url: String, accept: String = "application/json"): Http {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 7000
        conn.readTimeout = 9000
        conn.useCaches = false // our own bounded disk cache holds the results
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", accept)
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }
            val retry = conn.getHeaderField("Retry-After")?.toIntOrNull()
                ?: conn.getHeaderField("X-UsageLimit-Reset")?.toIntOrNull()
                ?: conn.getHeaderField("X-RateLimit-Reset")?.toIntOrNull() ?: 0
            return Http(code, body, retry)
        } finally {
            conn.disconnect()
        }
    }

    /** Thrown on 429 so the caller can back off without caching a miss. */
    class RateLimited(val seconds: Int) : Exception("rate limited for ${seconds}s")

    // ---------------------------------------------------------------- TheIntroDB

    fun theIntroDbUrl(tmdbId: Int, imdb: String?, season: Int?, episode: Int?, durationMs: Long): String? {
        val id = when {
            tmdbId > 0 -> "tmdb_id=$tmdbId"
            !imdb.isNullOrBlank() && imdb.matches(Regex("tt\\d{6,9}")) -> "imdb_id=$imdb"
            else -> return null
        }
        val ep = if (season != null && episode != null) "&season=$season&episode=$episode" else ""
        val dur = if (durationMs in 5 * 60_000L..6 * 3600_000L) "&duration_ms=$durationMs" else ""
        return "https://api.theintrodb.org/v4/media?$id$ep$dur"
    }

    /** Null = no data (404). Throws on network trouble or 429, so those aren't cached as misses. */
    fun theIntroDb(url: String, durationMs: Long): Markers? {
        val r = get(url)
        if (r.code == 429) throw RateLimited(r.retryAfterSec.coerceIn(10, 24 * 3600))
        if (r.code == 404) return null
        if (r.code !in 200..299 || r.body == null) throw IllegalStateException("TheIntroDB HTTP ${r.code}")
        return parseTheIntroDb(JSONObject(r.body), durationMs)
    }

    fun parseTheIntroDb(o: JSONObject, durationMs: Long): Markers {
        fun segs(name: String): List<Pair<Long?, Long?>> {
            val a = o.optJSONArray(name) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                (if (it.isNull("start_ms")) null else it.optLong("start_ms")) to
                    (if (it.isNull("end_ms")) null else it.optLong("end_ms"))
            }
        }
        val intro = segs("intro").firstNotNullOfOrNull { (s, e) -> plausibleIntro(s ?: 0, e, durationMs) }
        val recap = segs("recap").firstNotNullOfOrNull { (s, e) -> plausibleRecap(s ?: 0, e) }
        val credits = pickCredits(segs("credits").mapNotNull { it.first }, durationMs)
            ?: pickCredits(segs("preview").mapNotNull { it.first }, durationMs)
        return Markers(intro?.first, intro?.second, recap, credits)
    }

    // ---------------------------------------------------------------- AniSkip

    fun aniSkipUrl(malId: Int, episode: Int, durationMs: Long) =
        "https://api.aniskip.com/v2/skip-times/$malId/$episode?types[]=op&types[]=ed&types[]=recap" +
            "&episodeLength=${if (durationMs > 0) durationMs / 1000 else 0}"

    fun aniSkip(malId: Int, episode: Int, durationMs: Long): Markers? {
        val r = get(aniSkipUrl(malId, episode, durationMs))
        if (r.code == 429) throw RateLimited(r.retryAfterSec.coerceIn(10, 3600))
        if (r.code == 404) return null
        if (r.code !in 200..299 || r.body == null) throw IllegalStateException("AniSkip HTTP ${r.code}")
        return parseAniSkip(JSONObject(r.body), durationMs)
    }

    fun parseAniSkip(o: JSONObject, durationMs: Long): Markers? {
        if (!o.optBoolean("found")) return null
        val a = o.optJSONArray("results") ?: return null
        var intro: Pair<Long, Long>? = null
        var recap: Long? = null
        val credits = ArrayList<Long>()
        for (i in 0 until a.length()) {
            val r = a.optJSONObject(i) ?: continue
            val iv = r.optJSONObject("interval") ?: continue
            val s = (iv.optDouble("startTime", -1.0) * 1000).toLong()
            val e = (iv.optDouble("endTime", -1.0) * 1000).toLong()
            if (s < 0 || e <= s) continue
            when (r.optString("skipType")) {
                "op" -> if (intro == null) intro = plausibleIntro(s, e, durationMs)
                "recap" -> if (recap == null) recap = plausibleRecap(s, e)
                "ed" -> credits.add(s)
            }
        }
        val m = Markers(intro?.first, intro?.second, recap, pickCredits(credits, durationMs))
        return m.takeIf { it.introEndMs != null || it.recapEndMs != null || it.creditsStartMs != null }
    }

    // ---------------------------------------------------------------- TMDB → MyAnimeList mapping

    class AnimeEntry(val mal: Int, val kitsu: Int?, val tmdbSeason: Int?)

    /** ARM entries for a TMDB show, TV series only (movies/OVAs/specials don't share TMDB's numbering). */
    fun parseArm(a: JSONArray): List<AnimeEntry> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        .filter { it.optString("media") == "TV" && it.optInt("myanimelist") > 0 }
        .map {
            AnimeEntry(it.optInt("myanimelist"), it.optInt("kitsu").takeIf { k -> k > 0 },
                if (it.isNull("themoviedb-season") || !it.has("themoviedb-season")) null else it.optInt("themoviedb-season"))
        }

    /**
     * Picks (malId, episode) for TMDB [season]/[episode]. [absolute] is the episode counted across TMDB's
     * regular seasons. [counts] lazily gives (startDate, episodeCount?) per Kitsu id for cour splits.
     */
    fun mapToMal(
        entries: List<AnimeEntry>, season: Int, episode: Int, absolute: Int,
        counts: (List<Int>) -> Map<Int, Pair<String, Int?>>,
    ): Pair<Int, Int>? {
        if (entries.isEmpty() || season < 1 || episode < 1) return null
        if (entries.size == 1) {
            val only = entries[0]
            return when (only.tmdbSeason) {
                null -> only.mal to absolute           // one long-running entry (One Piece, Naruto)
                season -> only.mal to episode
                else -> null
            }
        }
        entries.filter { it.tmdbSeason == season }.singleOrNull()?.let { return it.mal to episode }
        // Cour splits or TMDB folding several MAL entries into one season: walk them in air order.
        if (entries.size > 20 || entries.any { it.kitsu == null }) return null
        val info = counts(entries.map { it.kitsu!! })
        if (info.size < entries.size) return null
        // Announced-but-unaired entries (no start date) can't hold the episode being played.
        val ordered = entries.filter { info[it.kitsu]!!.first != "9999" }.sortedBy { info[it.kitsu]!!.first }
        var left = absolute
        for ((i, e) in ordered.withIndex()) {
            val n = info[e.kitsu]!!.second
            if (n == null) return if (i == ordered.lastIndex) e.mal to left else null // airing: must be the last
            if (left <= n) return e.mal to left
            left -= n
        }
        return null
    }

    fun parseKitsu(o: JSONObject): Map<Int, Pair<String, Int?>> {
        val a = o.optJSONArray("data") ?: return emptyMap()
        val out = HashMap<Int, Pair<String, Int?>>()
        for (i in 0 until a.length()) {
            val d = a.optJSONObject(i) ?: continue
            val id = d.optString("id").toIntOrNull() ?: continue
            val at = d.optJSONObject("attributes") ?: continue
            val start = at.optString("startDate").takeIf { it.isNotEmpty() && it != "null" } ?: "9999"
            out[id] = start to (if (at.isNull("episodeCount")) null else at.optInt("episodeCount").takeIf { it > 0 })
        }
        return out
    }

    fun kitsuCounts(ids: List<Int>): Map<Int, Pair<String, Int?>> {
        val r = get("https://kitsu.io/api/edge/anime?filter%5Bid%5D=${ids.joinToString(",")}" +
            "&fields%5Banime%5D=episodeCount,startDate&page%5Blimit%5D=20", "application/vnd.api+json")
        if (r.code !in 200..299 || r.body == null) throw IllegalStateException("Kitsu HTTP ${r.code}")
        return parseKitsu(JSONObject(r.body))
    }

    fun armEntries(tmdbShowId: Int): List<AnimeEntry> {
        val r = get("https://arm.haglund.dev/api/v2/themoviedb?id=$tmdbShowId")
        if (r.code == 404) return emptyList()
        if (r.code !in 200..299 || r.body == null) throw IllegalStateException("ARM HTTP ${r.code}")
        return parseArm(JSONArray(r.body))
    }

    // ---------------------------------------------------------------- plausibility + merging

    /** Intro: 5 s – 5 min long, ending in the first half (when the duration is known). */
    fun plausibleIntro(s: Long, e: Long?, durationMs: Long): Pair<Long, Long>? {
        if (e == null || s < 0 || e - s !in 5_000..300_000) return null
        if (durationMs > 0 && e > durationMs / 2) return null
        return s to e
    }

    /** Recap: ends in the first 12 minutes, 5 s – 10 min long. Returns its end. */
    fun plausibleRecap(s: Long, e: Long?): Long? {
        if (e == null || e - s !in 5_000..600_000 || e > 12 * 60_000) return null
        return e
    }

    /**
     * Credits start: the latest candidate in the second half of the runtime, so "credits" over an anime OP
     * (Demon Slayer has both) don't trigger Up Next at 0:30. Without a duration, the latest one past 1 min.
     */
    fun pickCredits(starts: List<Long>, durationMs: Long): Long? =
        if (durationMs > 0) starts.filter { it >= durationMs / 2 && it < durationMs - 3_000 }.maxOrNull()
        else starts.filter { it >= 60_000 }.maxOrNull()

    /** Field-wise: [a] wins, [b] fills gaps. The intro start/end pair always comes from one source. */
    fun merge(a: Markers?, b: Markers?): Markers? {
        if (a == null) return b
        if (b == null) return a
        val introA = a.introEndMs != null
        return Markers(
            introStartMs = if (introA) a.introStartMs else b.introStartMs,
            introEndMs = if (introA) a.introEndMs else b.introEndMs,
            recapEndMs = a.recapEndMs ?: b.recapEndMs,
            creditsStartMs = a.creditsStartMs ?: b.creditsStartMs,
        )
    }

    fun hasAny(m: Markers?) = m != null && (m.introEndMs != null || m.recapEndMs != null || m.creditsStartMs != null)

    /**
     * Credits estimate for autoplay timing only. Movies: the last 8% (1–10 min). Episodes: the last 4%
     * (30–90 s), i.e. ~55 s on a 23-min episode and 90 s on a 45-min one.
     */
    fun estimateCredits(type: String, durationMs: Long): Long? {
        if (durationMs < 5 * 60_000) return null
        val tail = if (type == "movie") (durationMs * 0.08).toLong().coerceIn(60_000, 600_000)
        else (durationMs * 0.04).toLong().coerceIn(30_000, 90_000)
        return durationMs - tail
    }

    // Markers <-> compact JSON for the disk cache: [introStart, introEnd, recapEnd, creditsStart], -1 = null.
    fun toJson(m: Markers) = JSONArray().apply {
        for (v in listOf(m.introStartMs, m.introEndMs, m.recapEndMs, m.creditsStartMs)) put(v ?: -1L)
    }

    fun fromJson(a: JSONArray): Markers {
        fun at(i: Int) = a.optLong(i, -1).takeIf { it >= 0 }
        return Markers(at(0), at(1), at(2), at(3))
    }
}

/**
 * Chapters from a Matroska/WebM stream. Media3 1.4.1's MatroskaExtractor ignores the Chapters element
 * (checked: it has no Chapters ids), so we read it ourselves from the first 64 KB of the file via an HTTP
 * Range request, plus one more request when the SeekHead says the chapters sit further in.
 */
internal object MkvChapters {
    class Chapter(val startMs: Long, val endMs: Long?, val names: List<String>)
    class Scan(val isMkv: Boolean, val chapters: List<Chapter>?, val chaptersOffset: Long?, val chaptersSize: Long?)

    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POS = 0x53ACL
    private const val CHAPTERS = 0x1043A770L
    private const val CLUSTER = 0x1F43B675L
    private const val EDITION = 0x45B9L
    private const val EDITION_HIDDEN = 0x45BDL
    private const val EDITION_DEFAULT = 0x45DBL
    private const val ATOM = 0xB6L
    private const val TIME_START = 0x91L
    private const val TIME_END = 0x92L
    private const val FLAG_HIDDEN = 0x98L
    private const val FLAG_ENABLED = 0x4598L
    private const val SEGMENT_UID = 0x6E67L
    private const val DISPLAY = 0x80L
    private const val STRING = 0x85L

    const val FIRST_BYTES = 64 * 1024
    private const val MAX_CHAPTERS_BYTES = 256 * 1024

    private class El(val id: Long, val start: Int, val data: Int, val size: Long) {
        val end: Long get() = if (size < 0) Long.MAX_VALUE else data + size
    }

    private fun vintLen(first: Int): Int {
        for (i in 0 until 8) if (first and (0x80 shr i) != 0) return i + 1
        return 0
    }

    private fun readEl(b: ByteArray, p: Int, limit: Int): El? {
        if (p < 0 || p >= limit) return null
        val idLen = vintLen(b[p].toInt() and 0xFF)
        if (idLen == 0 || idLen > 4 || p + idLen >= limit) return null
        var id = 0L
        for (i in 0 until idLen) id = (id shl 8) or (b[p + i].toLong() and 0xFF)
        val sp = p + idLen
        val s0 = b[sp].toInt() and 0xFF
        val sLen = vintLen(s0)
        if (sLen == 0 || sp + sLen > limit) return null
        val mask = 0xFF shr sLen
        var size = (s0 and mask).toLong()
        var allOnes = (s0 and mask) == mask
        for (i in 1 until sLen) {
            val v = b[sp + i].toLong() and 0xFF
            size = (size shl 8) or v
            if (v != 0xFFL) allOnes = false
        }
        return El(id, p, sp + sLen, if (allOnes) -1 else size)
    }

    private fun uint(b: ByteArray, e: El): Long {
        var v = 0L
        for (i in 0 until e.size.toInt().coerceAtMost(8)) v = (v shl 8) or (b[e.data + i].toLong() and 0xFF)
        return v
    }

    /** Children of [parent] that lie fully inside the buffer. */
    private inline fun children(b: ByteArray, parent: El, limit: Int, each: (El) -> Unit) {
        val end = minOf(parent.end, limit.toLong()).toInt()
        var p = parent.data
        while (p < end) {
            val e = readEl(b, p, end) ?: return
            if (e.size < 0 || e.end > end) return
            each(e)
            p = e.end.toInt()
        }
    }

    /** Scans the start of a file: chapters if they're in the buffer, else where the SeekHead says they are. */
    fun scan(b: ByteArray, limit: Int): Scan {
        val hdr = readEl(b, 0, limit)
        if (hdr == null || hdr.id != EBML || hdr.size < 0) return Scan(false, null, null, null)
        val seg = readEl(b, hdr.end.toInt(), limit)
        if (seg == null || seg.id != SEGMENT) return Scan(true, null, null, null)
        val segStart = seg.data.toLong()
        var chaptersAt: Long? = null
        var p = seg.data
        while (p < limit) {
            val e = readEl(b, p, limit) ?: break
            when (e.id) {
                CHAPTERS -> return if (e.size >= 0 && e.end <= limit) Scan(true, parseChapters(b, e, limit), null, null)
                    else Scan(true, null, e.start.toLong(), if (e.size >= 0) e.end - e.start else null)
                SEEK_HEAD -> if (e.size >= 0 && e.end <= limit) children(b, e, limit) { s ->
                    if (s.id != SEEK) return@children
                    var target = 0L
                    var pos = -1L
                    children(b, s, limit) { f ->
                        when (f.id) {
                            SEEK_ID -> target = uint(b, f)
                            SEEK_POS -> pos = uint(b, f)
                        }
                    }
                    if (target == CHAPTERS && pos >= 0 && chaptersAt == null) chaptersAt = segStart + pos
                }
                CLUSTER -> break
            }
            if (e.size < 0 || e.end >= limit) break
            p = e.end.toInt()
        }
        return Scan(true, null, chaptersAt, null)
    }

    /** Parses a buffer that starts with the Chapters element (the second range request). */
    fun parseAt(b: ByteArray, limit: Int): List<Chapter>? {
        val e = readEl(b, 0, limit) ?: return null
        if (e.id != CHAPTERS || e.size < 0 || e.end > limit) return null
        return parseChapters(b, e, limit)
    }

    private fun parseChapters(b: ByteArray, chapters: El, limit: Int): List<Chapter> {
        class Edition(val hidden: Boolean, val default: Boolean, val atoms: List<Chapter>)
        val editions = ArrayList<Edition>()
        children(b, chapters, limit) { ed ->
            if (ed.id != EDITION) return@children
            var hidden = false
            var default = false
            val atoms = ArrayList<Chapter>()
            children(b, ed, limit) { c ->
                when (c.id) {
                    EDITION_HIDDEN -> hidden = uint(b, c) != 0L
                    EDITION_DEFAULT -> default = uint(b, c) != 0L
                    ATOM -> atom(b, c, limit, atoms)
                }
            }
            editions.add(Edition(hidden, default, atoms))
        }
        val pick = editions.firstOrNull { it.default && !it.hidden } ?: editions.firstOrNull { !it.hidden } ?: editions.firstOrNull()
        return pick?.atoms.orEmpty().sortedBy { it.startMs }
    }

    private fun atom(b: ByteArray, a: El, limit: Int, out: MutableList<Chapter>) {
        var start = -1L
        var end: Long? = null
        var hidden = false
        var enabled = true
        var linked = false
        val names = ArrayList<String>()
        val nested = ArrayList<El>()
        children(b, a, limit) { c ->
            when (c.id) {
                TIME_START -> start = uint(b, c) / 1_000_000
                TIME_END -> end = uint(b, c) / 1_000_000
                FLAG_HIDDEN -> hidden = uint(b, c) != 0L
                FLAG_ENABLED -> enabled = uint(b, c) != 0L
                SEGMENT_UID -> linked = true // times refer to another file
                DISPLAY -> children(b, c, limit) { d ->
                    if (d.id == STRING) names.add(String(b, d.data, d.size.toInt(), Charsets.UTF_8).trim())
                }
                ATOM -> nested.add(c)
            }
        }
        if (start >= 0 && !hidden && enabled && !linked) out.add(Chapter(start, end?.takeIf { it > start }, names))
        for (n in nested) atom(b, n, limit, out)
    }

    // ---------------------------------------------------------------- names → markers

    private val INTRO = Regex(
        "^(intro|introduction|intro song|opening|opening credits|opening titles|opening theme|opening song|" +
            "op|op ?\\d{1,2}|main titles?|main title sequence|title sequence|titles|theme song|theme|opening sequence)$"
    )
    private val RECAP = Regex("^(recap|previously|previously on\\b.*|last time\\b.*|story so far|the story so far|summary)$")
    private val CREDITS = Regex(
        "^(credits|end credits|ending|ending credits|ending theme|ending song|ed|ed ?\\d{1,2}|closing credits|" +
            "closing titles|end titles|outro|credits roll)$"
    )
    private val PREVIEW = Regex("^(preview|next episode|next episode preview|next time\\b.*|next on\\b.*|trailer next episode)$")

    fun classify(name: String): String? {
        val n = name.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        return when {
            n.isEmpty() -> null
            INTRO.matches(n) -> "intro"
            RECAP.matches(n) -> "recap"
            CREDITS.matches(n) -> "credits"
            PREVIEW.matches(n) -> "preview"
            else -> null
        }
    }

    fun toMarkers(chs: List<Chapter>, durationMs: Long): Markers? {
        if (chs.size < 2) return null
        fun endOf(i: Int) = chs[i].endMs ?: chs.getOrNull(i + 1)?.startMs ?: durationMs.takeIf { it > 0 }
        fun kind(i: Int) = chs[i].names.firstNotNullOfOrNull { classify(it) }
        var intro: Pair<Long, Long>? = null
        var recap: Long? = null
        val credits = ArrayList<Long>()
        val previews = ArrayList<Long>()
        for (i in chs.indices) {
            val s = chs[i].startMs
            when (kind(i)) {
                "intro" -> if (intro == null) intro = SkipSources.plausibleIntro(s, endOf(i), durationMs)
                    ?.takeIf { it.second - it.first in 8_000..200_000 }
                "recap" -> if (recap == null) recap = SkipSources.plausibleRecap(s, endOf(i))
                "credits" -> credits.add(s)
                "preview" -> previews.add(s)
            }
        }
        val m = Markers(intro?.first, intro?.second, recap,
            SkipSources.pickCredits(credits, durationMs) ?: SkipSources.pickCredits(previews, durationMs))
        return m.takeIf { SkipSources.hasAny(it) }
    }

    // ---------------------------------------------------------------- network

    /** Bytes [from, from+len) of [url]; null if the server won't do ranges (except from 0, read and cut). */
    fun range(url: String, headers: Map<String, String>, from: Long, len: Int): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        conn.useCaches = false
        conn.instanceFollowRedirects = true
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        conn.setRequestProperty("Range", "bytes=$from-${from + len - 1}")
        conn.setRequestProperty("Accept-Encoding", "identity")
        try {
            val code = conn.responseCode
            if (code != 206 && !(code == 200 && from == 0L)) return null
            val out = ByteArrayOutputStream(minOf(len, 64 * 1024))
            val buf = ByteArray(16 * 1024)
            conn.inputStream.use { input ->
                while (out.size() < len) {
                    val n = input.read(buf, 0, minOf(buf.size, len - out.size()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    /** Chapters of a stream, or an empty list when it isn't Matroska / has none. */
    fun fetch(url: String, headers: Map<String, String>): List<Chapter> {
        val first = range(url, headers, 0, FIRST_BYTES) ?: return emptyList()
        val scan = scan(first, first.size)
        if (!scan.isMkv) return emptyList()
        scan.chapters?.let { return it }
        val at = scan.chaptersOffset ?: return emptyList()
        val size = (scan.chaptersSize ?: (32L * 1024)).coerceIn(64, MAX_CHAPTERS_BYTES.toLong()).toInt()
        val more = range(url, headers, at, size) ?: return emptyList()
        return parseAt(more, more.size).orEmpty()
    }
}
