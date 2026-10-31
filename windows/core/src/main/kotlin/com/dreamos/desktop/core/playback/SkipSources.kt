package com.dreamos.desktop.core.playback

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/*
 * Where skip markers come from (fire-home SkipSources.kt, iPhone SkipMarkers.swift). Parsing and the plausibility
 * rules are pure; [SkipMarkers] does the caching, the merging and the learned skips.
 *
 * Sources (verified by the Fire Stick and the iPhone, Oct 2026; none needs a key or a client id):
 *  - TheIntroDB  GET https://api.theintrodb.org/v4/media?tmdb_id=&season=&episode=[&duration_ms=]
 *                30 req / 10 s and 500 req / day per IP. 404 = nothing submitted. Segments intro / recap / credits /
 *                preview, each [{start_ms|null, end_ms|null}] (null start = from 0, null end = to the end).
 *  - AniSkip     GET https://api.aniskip.com/v2/skip-times/{malId}/{ep}?types[]=op&types[]=ed&types[]=recap&episodeLength=
 *                Keyed by MyAnimeList id; episodeLength (s) drops submissions for other cuts.
 *  - ARM         GET https://arm.haglund.dev/api/v2/themoviedb?id={tmdbShowId} -> MAL / Kitsu ids per anime entry.
 *  - Kitsu       GET https://kitsu.io/api/edge/anime?filter[id]=a,b -> episodeCount + startDate (cour splits).
 * The stream's own chapters come from mpv (`chapter-list`), as on the iPhone; the Fire Stick reads them with Range
 * requests only because its player ignores Matroska chapters.
 */

/** What a marker is. [PREVIEW]: the next-episode preview after the credits (TheIntroDB, chapters named "Preview"). */
enum class SkipKind { INTRO, RECAP, CREDITS, PREVIEW }

/** One marker for the player, positions in ms. [estimated]: a guess for Up Next timing only (no tick, no label). */
@Serializable
data class SkipMark(val kind: SkipKind, val startMs: Long, val endMs: Long, val estimated: Boolean = false)

/** One chapter of the playing file as mpv reports it (`chapter-list`: time, title); [endMs] null when unknown. */
data class SkipChapter(val startMs: Long, val endMs: Long? = null, val title: String)

/**
 * The markers for one title plus where they came from: [introLearned] (from the viewer's own skips, so never
 * auto-skipped) and [creditsEstimated] (Up Next timing only).
 */
data class SkipInfo(val marks: List<SkipMark>, val introLearned: Boolean = false, val creditsEstimated: Boolean = false) {
    fun of(kind: SkipKind): SkipMark? = marks.firstOrNull { it.kind == kind }

    companion object {
        val NONE = SkipInfo(emptyList())
    }
}

/** Raw markers, ms (fire-home `Markers` plus the preview start). */
@Serializable
data class Markers(
    val introStartMs: Long? = null,
    val introEndMs: Long? = null,
    val recapEndMs: Long? = null,
    val creditsStartMs: Long? = null,
    val previewStartMs: Long? = null,
) {
    val hasAny get() = introEndMs != null || recapEndMs != null || creditsStartMs != null || previewStartMs != null

    /**
     * As the player's marks. A recap after the opening (common in anime) starts where the intro ends; credits run to
     * the preview when there is one, else to the end.
     */
    fun marks(durationMs: Long, creditsEstimated: Boolean): List<SkipMark> {
        val out = ArrayList<SkipMark>(4)
        introEndMs?.let { out += SkipMark(SkipKind.INTRO, introStartMs ?: 0, it) }
        recapEndMs?.let { r ->
            val s = if (introEndMs != null && introEndMs < r && (introStartMs ?: 0) < r) introEndMs else 0
            out += SkipMark(SkipKind.RECAP, s, r)
        }
        val preview = previewStartMs?.takeIf { p -> creditsStartMs == null || p > creditsStartMs }
        creditsStartMs?.let { c -> out += SkipMark(SkipKind.CREDITS, c, (preview ?: maxOf(durationMs, c)), estimated = creditsEstimated) }
        preview?.let { p -> out += SkipMark(SkipKind.PREVIEW, p, maxOf(durationMs, p)) }
        return out
    }

    companion object {
        /** Field-wise: [a] wins, [b] fills gaps. The intro start / end pair always comes from one source. */
        fun merge(a: Markers?, b: Markers?): Markers? {
            if (a == null) return b
            if (b == null) return a
            val introA = a.introEndMs != null
            return Markers(
                introStartMs = if (introA) a.introStartMs else b.introStartMs,
                introEndMs = if (introA) a.introEndMs else b.introEndMs,
                recapEndMs = a.recapEndMs ?: b.recapEndMs,
                creditsStartMs = a.creditsStartMs ?: b.creditsStartMs,
                previewStartMs = a.previewStartMs ?: b.previewStartMs,
            )
        }
    }
}

object SkipSources {
    /** Thrown on 429 so the caller backs off without keeping a miss. */
    class RateLimited(val seconds: Int) : Exception("rate limited for $seconds s")

    /** Network trouble or an unexpected answer: not kept as a miss, asked again next time. */
    class Unavailable(what: String) : Exception(what)

    // ---- TheIntroDB ----

    fun theIntroDbUrl(tmdbId: Int, imdb: String?, season: Int?, episode: Int?, durationMs: Long): String? {
        val id = when {
            tmdbId > 0 -> "tmdb_id=$tmdbId"
            !imdb.isNullOrBlank() && imdb.matches(Regex("tt\\d{6,9}")) -> "imdb_id=$imdb"
            else -> return null
        }
        val ep = if (season != null && episode != null) "&season=$season&episode=$episode" else ""
        val dur = if (durationMs in 5 * 60_000L..6 * 3_600_000L) "&duration_ms=$durationMs" else ""
        return "https://api.theintrodb.org/v4/media?$id$ep$dur"
    }

    fun parseTheIntroDb(o: JsonElement?, durationMs: Long): Markers {
        fun segs(name: String): List<Pair<Long?, Long?>> = o[name].arr.mapNotNull { it.obj }.map {
            nullableLong(it["start_ms"]) to nullableLong(it["end_ms"])
        }
        val intro = segs("intro").firstNotNullOfOrNull { (s, e) -> plausibleIntro(s ?: 0, e, durationMs) }
        val recap = segs("recap").firstNotNullOfOrNull { (s, e) -> plausibleRecap(s ?: 0, e) }
        val previews = segs("preview").mapNotNull { it.first }
        val credits = pickCredits(segs("credits").mapNotNull { it.first }, durationMs) ?: pickCredits(previews, durationMs)
        return Markers(intro?.first, intro?.second, recap, credits, pickCredits(previews, durationMs))
    }

    private fun nullableLong(e: JsonElement?): Long? =
        if (e == null || e is JsonNull || (e as? JsonPrimitive)?.content.isNullOrEmpty()) null else e.long

    // ---- AniSkip ----

    /** The brackets go percent-encoded: java.net.URI refuses raw ones in a query. */
    fun aniSkipUrl(malId: Int, episode: Int, durationMs: Long) =
        "https://api.aniskip.com/v2/skip-times/$malId/$episode?types%5B%5D=op&types%5B%5D=ed&types%5B%5D=recap" +
            "&episodeLength=${if (durationMs > 0) durationMs / 1000 else 0}"

    fun parseAniSkip(o: JsonElement?, durationMs: Long): Markers? {
        if (!o["found"].bool) return null
        var intro: Pair<Long, Long>? = null
        var recap: Long? = null
        val credits = ArrayList<Long>()
        for (r in o["results"].arr) {
            val iv = r["interval"]
            val s = (seconds(iv["startTime"]) * 1000).toLong()
            val e = (seconds(iv["endTime"]) * 1000).toLong()
            if (s < 0 || e <= s) continue
            when (r["skipType"].str) {
                "op" -> if (intro == null) intro = plausibleIntro(s, e, durationMs)
                "recap" -> if (recap == null) recap = plausibleRecap(s, e)
                "ed" -> credits.add(s)
            }
        }
        val m = Markers(intro?.first, intro?.second, recap, pickCredits(credits, durationMs))
        return m.takeIf { it.hasAny }
    }

    private fun seconds(e: JsonElement?): Double = (e as? JsonPrimitive)?.content?.toDoubleOrNull() ?: -1.0

    // ---- TMDB -> MyAnimeList ----

    class AnimeEntry(val mal: Int, val kitsu: Int?, val tmdbSeason: Int?)

    /** ARM entries for a TMDB show, TV series only (movies / OVAs / specials don't share TMDB's numbering). */
    fun parseArm(a: JsonElement?): List<AnimeEntry> = a.arr.mapNotNull { it.obj }
        .filter { it["media"].str == "TV" && it["myanimelist"].int > 0 }
        .map {
            val season = it["themoviedb-season"]
            AnimeEntry(it["myanimelist"].int, it["kitsu"].int.takeIf { k -> k > 0 },
                if (season == null || season is JsonNull) null else season.int)
        }

    fun parseKitsu(o: JsonElement?): Map<Int, Pair<String, Int?>> {
        val out = HashMap<Int, Pair<String, Int?>>()
        for (d in o["data"].arr) {
            val id = d["id"].str.toIntOrNull() ?: continue
            val at = d["attributes"].obj ?: continue
            val start = at["startDate"].str.takeIf { it.isNotEmpty() && it != "null" } ?: "9999"
            val count = at["episodeCount"]
            out[id] = start to (if (count == null || count is JsonNull) null else count.int.takeIf { it > 0 })
        }
        return out
    }

    fun kitsuUrl(ids: List<Int>) = "https://kitsu.io/api/edge/anime?filter%5Bid%5D=${ids.joinToString(",")}" +
        "&fields%5Banime%5D=episodeCount,startDate&page%5Blimit%5D=20"

    fun armUrl(tmdbShowId: Int) = "https://arm.haglund.dev/api/v2/themoviedb?id=$tmdbShowId"

    /**
     * (malId, episode) for TMDB [season] / [episode]; [absolute] is the episode counted across TMDB's regular seasons.
     * [counts] gives (startDate, episodeCount?) per Kitsu id, only asked for cour splits.
     */
    suspend fun mapToMal(entries: List<AnimeEntry>, season: Int, episode: Int, absolute: Int,
                         counts: suspend (List<Int>) -> Map<Int, Pair<String, Int?>>): Pair<Int, Int>? {
        if (entries.isEmpty() || season < 1 || episode < 1) return null
        if (entries.size == 1) {
            val only = entries[0]
            return when (only.tmdbSeason) {
                null -> only.mal to absolute // one long-running entry (One Piece, Naruto)
                season -> only.mal to episode
                else -> null
            }
        }
        entries.filter { it.tmdbSeason == season }.singleOrNull()?.let { return it.mal to episode }
        // Cour splits, or TMDB folding several MAL entries into one season: walk them in air order.
        if (entries.size > 20 || entries.any { it.kitsu == null }) return null
        val info = counts(entries.map { it.kitsu!! })
        if (info.size < entries.size) return null
        // Announced but unaired entries (no start date) can't hold the episode being played.
        val ordered = entries.filter { info[it.kitsu]!!.first != "9999" }.sortedBy { info[it.kitsu]!!.first }
        var left = absolute
        for ((i, e) in ordered.withIndex()) {
            val n = info[e.kitsu]!!.second ?: return if (i == ordered.lastIndex) e.mal to left else null // airing: must be last
            if (left <= n) return e.mal to left
            left -= n
        }
        return null
    }

    // ---- plausibility ----

    /** Intro: 5 s to 5 min long, ending in the first half (when the duration is known). */
    fun plausibleIntro(s: Long, e: Long?, durationMs: Long): Pair<Long, Long>? {
        if (e == null || s < 0 || e - s !in 5_000..300_000) return null
        if (durationMs > 0 && e > durationMs / 2) return null
        return s to e
    }

    /** Recap: ends in the first 12 minutes, 5 s to 10 min long. Returns its end. */
    fun plausibleRecap(s: Long, e: Long?): Long? {
        if (e == null || e - s !in 5_000..600_000 || e > 12 * 60_000) return null
        return e
    }

    /**
     * Credits start: the latest candidate in the second half of the runtime, so "credits" over an anime OP don't
     * bring Up Next at 0:30. Without a duration, the latest one past 1 min.
     */
    fun pickCredits(starts: List<Long>, durationMs: Long): Long? =
        if (durationMs > 0) starts.filter { it >= durationMs / 2 && it < durationMs - 3_000 }.maxOrNull()
        else starts.filter { it >= 60_000 }.maxOrNull()

    /**
     * Credits estimate for Up Next timing only. Movies: the last 8 % (1 to 10 min). Episodes: the last 4 % (30 to 90 s),
     * about 55 s on a 23-min episode and 90 s on a 45-min one.
     */
    fun estimateCredits(episode: Boolean, durationMs: Long): Long? {
        if (durationMs < 5 * 60_000) return null
        val tail = if (!episode) (durationMs * 0.08).toLong().coerceIn(60_000, 600_000)
        else (durationMs * 0.04).toLong().coerceIn(30_000, 90_000)
        return durationMs - tail
    }

    // ---- chapter names ----

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
    private val NOT_WORD = Regex("[^a-z0-9]+")

    fun classify(name: String): SkipKind? {
        val n = name.lowercase().replace(NOT_WORD, " ").trim()
        return when {
            n.isEmpty() -> null
            INTRO.matches(n) -> SkipKind.INTRO
            RECAP.matches(n) -> SkipKind.RECAP
            CREDITS.matches(n) -> SkipKind.CREDITS
            PREVIEW.matches(n) -> SkipKind.PREVIEW
            else -> null
        }
    }

    /** Markers from the file's own chapters (exact for this very file); null when none are named. */
    fun fromChapters(chapters: List<SkipChapter>, durationMs: Long): Markers? {
        val chs = chapters.sortedBy { it.startMs }
        if (chs.size < 2) return null
        fun endOf(i: Int) = chs[i].endMs?.takeIf { it > chs[i].startMs } ?: chs.getOrNull(i + 1)?.startMs ?: durationMs.takeIf { it > 0 }
        var intro: Pair<Long, Long>? = null
        var recap: Long? = null
        val credits = ArrayList<Long>()
        val previews = ArrayList<Long>()
        for (i in chs.indices) {
            val s = chs[i].startMs
            when (classify(chs[i].title)) {
                SkipKind.INTRO -> if (intro == null) intro = plausibleIntro(s, endOf(i), durationMs)?.takeIf { it.second - it.first in 8_000..200_000 }
                SkipKind.RECAP -> if (recap == null) recap = plausibleRecap(s, endOf(i))
                SkipKind.CREDITS -> credits.add(s)
                SkipKind.PREVIEW -> previews.add(s)
                null -> {}
            }
        }
        val m = Markers(intro?.first, intro?.second, recap, pickCredits(credits, durationMs) ?: pickCredits(previews, durationMs),
            pickCredits(previews, durationMs))
        return m.takeIf { it.hasAny }
    }
}
