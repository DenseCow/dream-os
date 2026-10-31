package com.dreamos.desktop.core.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.math.abs

/** What the anime path needs about a show from TMDB (cached details, no call per episode). */
data class ShowFacts(
    val animation: Boolean,
    /** ISO 639-1, "" when unknown. */
    val originalLanguage: String,
    /** Regular seasons only: season number to its episode count. */
    val episodeCounts: Map<Int, Int>,
)

/**
 * Intro / recap / credits / preview markers for a title (fire-home SkipMarkers.kt, iPhone SkipMarkers.swift). Per
 * field, the first source that has it wins:
 *  1. the file's own chapters named Intro / Opening / Recap / Credits / Preview (exact for this very file; the player
 *     passes mpv's `chapter-list`);
 *  2. TheIntroDB (any show or movie, by TMDB id + season / episode, the cut picked by duration);
 *  3. AniSkip for Japanese animation (TMDB to MyAnimeList through ARM / Kitsu), only asked when 2 had no intro;
 *  4. learned skips: where the viewer jumped past the intro on earlier episodes of the show ([learn]);
 *  5. a credits estimate (Up Next timing only, [SkipInfo.creditsEstimated]).
 *
 * Cached: network answers [MAX_CACHED] deep (hits 21 days, misses 2; errors and 429s never kept), learned intros for
 * [MAX_SHOWS] shows. Worst case on disk ~120 KB + ~15 KB in the store; in memory the same. TheIntroDB's 429 backs off
 * for as long as it asks. Every call is bounded by the HTTP timeouts (about 10 s each, at most 5 calls in a row for a
 * cour-split anime) and never throws.
 */
class SkipMarkers(
    private val http: Http,
    private val store: KeyValueStore,
    private val clock: Clock = Clock.SYSTEM,
    private val showFacts: suspend (showId: Int) -> ShowFacts? = { null },
) {
    companion object {
        /** The PC keeps more than the Fire Stick's 400: a whole binge's worth of seasons. */
        const val MAX_CACHED = 1500
        const val MAX_SHOWS = 300
        const val HIT_TTL = 21L * 86_400_000
        const val MISS_TTL = 2L * 86_400_000
        /** A learned intro is offered from this long before the viewer's own jump. */
        const val LEARN_LEAD_MS = 10_000L
        private const val CACHE_KEY = "skip.markers"
        private const val LEARNED_KEY = "skip.learned"
        private const val TIMEOUT_MS = 9_000L
        private val JSON = Json { ignoreUnknownKeys = true }
    }

    @Serializable private data class Cached(val t: Long, val m: Markers? = null)
    /** [s] start, [e] end, [n] confidence, [u] last used (wall ms). */
    @Serializable private data class Learned(val s: Long, val e: Long, val n: Int, val u: Long)

    private val lock = Any()
    private val cache: LinkedHashMap<String, Cached> = LinkedHashMap(64, 0.75f, true)
    private val learned = HashMap<String, Learned>()
    private val inFlight = HashMap<String, CompletableDeferred<Markers?>>()
    @Volatile private var blockedUntil = 0L

    init {
        try {
            store.get(CACHE_KEY)?.let { JSON.decodeFromString(MapSerializer(String.serializer(), Cached.serializer()), it) }?.let { m ->
                val now = clock.wallMs()
                m.entries.sortedBy { it.value.t }.forEach { (k, v) -> if (fresh(v, now)) cache[k] = v }
            }
            store.get(LEARNED_KEY)?.let { JSON.decodeFromString(MapSerializer(String.serializer(), Learned.serializer()), it) }?.let { learned.putAll(it) }
        } catch (e: Exception) { PlaybackLog.w("Skip markers: kept answers unreadable (${e.javaClass.simpleName}); starting over") }
    }

    private fun fresh(c: Cached, now: Long) = now - c.t < if (c.m != null) HIT_TTL else MISS_TTL

    /** "e:t1396:1:2" / "m:t603": the title's key; null without an id. */
    private fun keyOf(t: PlayTarget): String? {
        val id = when {
            t.tmdbId > 0 -> "t${t.tmdbId}"
            !t.imdb.isNullOrBlank() -> "i${t.imdb}"
            else -> return null
        }
        return if (t.isEpisode) { if (t.season == null || t.episode == null) null else "e:$id:${t.season}:${t.episode}" } else "m:$id"
    }

    /** Durations within a couple of minutes are the same cut (TheIntroDB clusters within 5 min). */
    private fun cacheKey(key: String, durationMs: Long) = "$key@${if (durationMs > 0) (durationMs + 60_000) / 120_000 else 0}"

    /**
     * The markers for [target] once its duration is known. [chapters]: the file's own (mpv `chapter-list`). Answers
     * from the cache at once when it can; concurrent asks for one title share one lookup.
     */
    suspend fun info(target: PlayTarget, durationMs: Long, chapters: List<SkipChapter> = emptyList()): SkipInfo {
        val key = keyOf(target) ?: return compose(target, null, chapters, durationMs)
        val ck = cacheKey(key, durationMs)
        val hit = synchronized(lock) { cache[ck]?.takeIf { fresh(it, clock.wallMs()) } }
        val remote = if (hit != null) hit.m else {
            val (d, owner) = synchronized(lock) {
                inFlight[ck]?.let { it to false } ?: CompletableDeferred<Markers?>().also { inFlight[ck] = it }.let { it to true }
            }
            if (owner) {
                var m: Markers? = null
                try { m = fetchRemote(target, ck, durationMs) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    PlaybackLog.w("Skip markers: lookup failed (${e.javaClass.simpleName})")
                } finally {
                    synchronized(lock) { inFlight.remove(ck) }
                    d.complete(m)
                }
            }
            d.await()
        }
        val i = compose(target, remote, chapters, durationMs)
        PlaybackLog.i("Skip markers: ${target.label} ${i.marks.joinToString(" ") { "${it.kind.name.lowercase()} ${it.startMs / 1000}-${it.endMs / 1000}s" }.ifEmpty { "none" }}" +
            (if (i.introLearned) ", intro learned" else "") + (if (i.creditsEstimated) ", credits estimated" else ""))
        return i
    }

    private fun compose(t: PlayTarget, remote: Markers?, chapters: List<SkipChapter>, durationMs: Long): SkipInfo {
        var m = Markers.merge(SkipSources.fromChapters(chapters, durationMs), remote) ?: Markers()
        var fromViewer = false
        if (m.introEndMs == null && t.isEpisode) learnedFor(t)?.let { (s, e) ->
            if (durationMs <= 0 || e < durationMs / 2) { m = m.copy(introStartMs = (s - LEARN_LEAD_MS).coerceAtLeast(0), introEndMs = e); fromViewer = true }
        }
        var est = false
        if (m.creditsStartMs == null) SkipSources.estimateCredits(t.isEpisode, durationMs)?.let { m = m.copy(creditsStartMs = it); est = true }
        return SkipInfo(m.marks(durationMs, est), fromViewer, est)
    }

    /** Network sources through the cache. Null when nobody has data (kept as a miss unless a source failed). */
    private suspend fun fetchRemote(t: PlayTarget, ck: String, durationMs: Long): Markers? {
        if (clock.monotonicMs() < blockedUntil) return null
        val ep = t.isEpisode
        var m: Markers? = null
        val url = SkipSources.theIntroDbUrl(t.tmdbId, t.imdb, if (ep) t.season else null, if (ep) t.episode else null, durationMs)
        var transient = false
        if (url != null) {
            try { m = theIntroDb(url, durationMs) } catch (e: SkipSources.RateLimited) {
                blockedUntil = clock.monotonicMs() + e.seconds * 1000L
                PlaybackLog.w("Skip markers: TheIntroDB asks to wait ${e.seconds} s")
                return null
            } catch (e: SkipSources.Unavailable) { PlaybackLog.w("Skip markers: ${e.message}"); transient = true }
        }
        if (ep && m?.introEndMs == null && t.tmdbId > 0) {
            val anime = try { animeMarkers(t, durationMs) } catch (e: Exception) {
                PlaybackLog.w("Skip markers: anime lookup failed (${e.message ?: e.javaClass.simpleName})"); transient = true; null
            }
            m = Markers.merge(m, anime)
        }
        val found = m?.takeIf { it.hasAny }
        if (found != null || !transient) put(ck, found)
        return found
    }

    private suspend fun get(url: String, accept: String = "application/json"): HttpReply =
        http.send(HttpReq(url, headers = mapOf("Accept" to accept), timeoutMs = TIMEOUT_MS))

    private fun retryAfter(r: HttpReply) =
        (r.header("Retry-After") ?: r.header("X-UsageLimit-Reset") ?: r.header("X-RateLimit-Reset"))?.trim()?.toIntOrNull() ?: 0

    private suspend fun theIntroDb(url: String, durationMs: Long): Markers? {
        val r = get(url)
        if (r.code == 429) throw SkipSources.RateLimited(retryAfter(r).coerceIn(10, 24 * 3600))
        if (r.code == 404) return null
        if (!r.ok) throw SkipSources.Unavailable("TheIntroDB answered ${r.code}")
        return SkipSources.parseTheIntroDb(r.json(), durationMs)
    }

    /** AniSkip for Japanese animation only (TMDB says so), mapped to a MyAnimeList entry and episode. */
    private suspend fun animeMarkers(t: PlayTarget, durationMs: Long): Markers? {
        val season = t.season ?: return null
        val episode = t.episode ?: return null
        if (season < 1) return null
        val facts = showFacts(t.tmdbId)
        val animation = facts?.animation ?: t.isAnime
        val lang = facts?.originalLanguage?.ifEmpty { null } ?: t.originalLanguage
        if (!animation || lang != "ja") return null
        // Without the season list only the simple mappings work (one entry per TMDB season).
        val absolute = episode + (facts?.episodeCounts?.filterKeys { it in 1 until season }?.values?.sum() ?: 0)
        val arm = get(SkipSources.armUrl(t.tmdbId))
        if (arm.code == 404) return null
        if (!arm.ok) throw SkipSources.Unavailable("ARM answered ${arm.code}")
        val entries = SkipSources.parseArm(arm.json())
        val (mal, malEp) = SkipSources.mapToMal(entries, season, episode, absolute) { ids ->
            val k = get(SkipSources.kitsuUrl(ids), "application/vnd.api+json")
            if (!k.ok) throw SkipSources.Unavailable("Kitsu answered ${k.code}")
            SkipSources.parseKitsu(k.json())
        } ?: return null
        PlaybackLog.i("Skip markers: ${t.label} is MyAnimeList $mal episode $malEp")
        val r = get(SkipSources.aniSkipUrl(mal, malEp, durationMs))
        if (r.code == 429) throw SkipSources.RateLimited(retryAfter(r).coerceIn(10, 3600))
        if (r.code == 404) return null
        if (!r.ok) throw SkipSources.Unavailable("AniSkip answered ${r.code}")
        return SkipSources.parseAniSkip(r.json(), durationMs)
    }

    // ---- the cache ----

    @Volatile private var saving = false

    private fun put(ck: String, m: Markers?) {
        synchronized(lock) {
            cache[ck] = Cached(clock.wallMs(), m)
            val it = cache.entries.iterator()
            while (cache.size > MAX_CACHED && it.hasNext()) { it.next(); it.remove() }
        }
        save()
    }

    /** One write 2 s after the first change of a burst. */
    private fun save() {
        synchronized(lock) { if (saving) return; saving = true }
        Background.after(2_000) {
            val (c, l) = synchronized(lock) { saving = false; LinkedHashMap(cache) to HashMap(learned) }
            store.put(CACHE_KEY, JSON.encodeToString(MapSerializer(String.serializer(), Cached.serializer()), c))
            store.put(LEARNED_KEY, JSON.encodeToString(MapSerializer(String.serializer(), Learned.serializer()), l))
        }
    }

    // ---- learned skips ----

    private fun showKey(t: PlayTarget) = when {
        t.tmdbId > 0 -> "t${t.tmdbId}"
        !t.imdb.isNullOrBlank() -> "i${t.imdb}"
        t.title.isNotBlank() -> "n" + t.title.lowercase().trim()
        else -> null
    }

    private fun learnedFor(t: PlayTarget): Pair<Long, Long>? = synchronized(lock) { learned[showKey(t) ?: return null]?.let { it.s to it.e } }

    /**
     * The viewer jumped from [fromMs] to [toMs] past what looks like an intro ([SkipLearner] decides). One record per
     * show: a jump within 25 s of the kept start (and of similar length) averages in and raises the confidence; a
     * different one lowers it first and only replaces the record when it reaches 0, so one odd episode can't undo it.
     */
    fun learn(t: PlayTarget, fromMs: Long, toMs: Long) {
        val k = showKey(t) ?: return
        val now = clock.wallMs()
        val rec = synchronized(lock) {
            val old = learned[k]
            val rec = when {
                old == null -> Learned(fromMs, toMs, 1, now)
                abs(old.s - fromMs) <= 25_000 && abs((old.e - old.s) - (toMs - fromMs)) <= 20_000 -> {
                    val w = old.n.coerceAtMost(5)
                    Learned((old.s * w + fromMs) / (w + 1), (old.e * w + toMs) / (w + 1), old.n + 1, now)
                }
                old.n > 1 -> old.copy(n = old.n - 1, u = now)
                else -> Learned(fromMs, toMs, 1, now)
            }
            learned[k] = rec
            if (learned.size > MAX_SHOWS) learned.entries.minByOrNull { it.value.u }?.let { learned.remove(it.key) }
            rec
        }
        PlaybackLog.i("Skip markers: learned the intro of ${t.title} at ${rec.s / 1000}-${rec.e / 1000}s (seen ${rec.n}x)")
        save()
    }

    /** The viewer used a learned Skip Intro: its confidence goes up. */
    fun confirmLearned(t: PlayTarget) {
        val k = showKey(t) ?: return
        synchronized(lock) { learned[k]?.let { learned[k] = it.copy(n = it.n + 1, u = clock.wallMs()) } ?: return }
        save()
    }
}

/**
 * Learning intros from the viewer's own jumps (fire-home SkipController's seek tracking), for one item. The player
 * feeds it the position about twice a second ([tick]), says when it seeks for a Skip button itself ([selfSeek]), and
 * calls [end] when the item ends or changes. A forward jump (or a quick run of them) of 30 s to 2.5 min that starts in
 * the first 12 minutes of an episode with no real intro data goes to [SkipMarkers.learn]. No timers, no threads.
 */
class SkipLearner(
    private val target: PlayTarget,
    private val info: SkipInfo,
    private val learn: (fromMs: Long, toMs: Long) -> Unit,
) {
    private companion object {
        const val SEEK_MIN = 5_000L
        const val COALESCE = 4_000L
        const val LEARN_MIN = 30_000L
        const val LEARN_MAX = 150_000L
        const val LEARN_BEFORE = 12 * 60_000L
    }

    private var lastPos = -1L
    private var lastAt = 0L
    private var jumpFrom = -1L
    private var jumpTo = -1L
    private var jumpAt = 0L
    private var selfTarget = -1L
    private var selfAt = 0L

    /** The player is about to seek to [toMs] for its own Skip button (not the viewer's jump). [nowMs]: monotonic. */
    fun selfSeek(toMs: Long, nowMs: Long) { selfTarget = toMs; selfAt = nowMs }

    /** The position now ([nowMs]: monotonic). */
    fun tick(posMs: Long, nowMs: Long) {
        if (lastPos >= 0) {
            // Ticks come about twice a second: allow 2.5 s of real playback (2x speed, a late tick).
            val jump = posMs - lastPos - minOf(nowMs - lastAt, 2_500L)
            if (jump > SEEK_MIN) {
                val self = selfTarget >= 0 && abs(posMs - selfTarget) < 3_000 && nowMs - selfAt < 8_000
                when {
                    self -> { selfTarget = -1; jumpFrom = -1 }
                    jumpFrom >= 0 && nowMs - jumpAt < COALESCE -> { jumpTo = posMs; jumpAt = nowMs }
                    else -> { commit(); jumpFrom = lastPos; jumpTo = posMs; jumpAt = nowMs }
                }
            } else if (posMs < lastPos - SEEK_MIN) jumpFrom = -1 // went back: not an intro skip
        }
        if (jumpFrom >= 0 && nowMs - jumpAt >= COALESCE) commit()
        lastPos = posMs
        lastAt = nowMs
    }

    /** The item ended, closed or changed. */
    fun end() = commit()

    private fun commit() {
        val from = jumpFrom
        val to = jumpTo
        jumpFrom = -1
        if (from < 0 || !target.isEpisode) return
        if (to - from !in LEARN_MIN..LEARN_MAX || from >= LEARN_BEFORE) return
        val intro = info.of(SkipKind.INTRO)
        if (intro != null && !info.introLearned) return // real data already
        val recap = info.of(SkipKind.RECAP)
        if (recap != null && from < recap.endMs) return // skipping the recap by hand
        // Near a learned window that was offered: refine it (far away is something else).
        if (intro != null && abs(intro.startMs + SkipMarkers.LEARN_LEAD_MS - from) > 90_000) return
        learn(from, to)
    }
}
