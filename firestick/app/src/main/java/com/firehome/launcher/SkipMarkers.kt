package com.firehome.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Finds intro/recap/credits timestamps for a title. Calls back on the main thread (at once, inside the call,
 * when everything is cached, so it's cheap to call repeatedly).
 *
 * Per field, the first source that has it wins:
 *  1. chapters named Intro/Opening/Recap/Credits/Ending in the stream itself (see [attachStreamUrl]),
 *     since they're exact for this very file;
 *  2. TheIntroDB (any show or movie, by TMDB id + season/episode);
 *  3. AniSkip for anime (TMDB → MyAnimeList via ARM/Kitsu), only asked when 2 had no intro;
 *  4. learned skips: where the user jumped past the intro on earlier episodes of the show;
 *  5. credits estimate (autoplay timing only, see [creditsEstimated]).
 */
object SkipMarkers {
    private const val TAG = "SkipMarkers"
    private val main = Handler(Looper.getMainLooper())

    // ---------------------------------------------------------------- public API

    fun get(ctx: Context, req: PlaybackRequest, durationMs: Long, cb: (Markers) -> Unit) =
        resolve(ctx, req, durationMs) { cb(it.markers) }

    /**
     * For the player core: call when the stream URL for [req] is known (before or after playback starts).
     * Matroska streams get their chapters read with one or two small HTTP Range requests (64 KB, plus at most
     * 256 KB when the chapters sit further in), using [headers] (User-Agent, Cookie, Referer...). A
     * "url|Header=value&..." suffix is understood too. HLS/DASH/MP4 are skipped; other non-MKV files cost one
     * 64 KB read at most. Without [req], the stream belongs to the next title asked for.
     */
    fun attachStreamUrl(url: String, headers: Map<String, String> = emptyMap(), req: PlaybackRequest? = null) {
        val pipe = url.indexOf('|')
        val clean = if (pipe >= 0) url.substring(0, pipe) else url
        val all = HashMap(headers)
        // Read the way the player reads them, so the chapter request sends what the player's does.
        if (pipe >= 0) for ((k, v) in StreamUrls.headers(url.substring(pipe + 1))) all.putIfAbsent(k, v)
        val path = clean.substringBefore('?').lowercase()
        val streamable = clean.startsWith("http") && !path.contains("/manifest") &&
            listOf(".m3u8", ".mpd", ".mp4", ".m4v", ".ts").none { path.endsWith(it) }
        synchronized(this) {
            val s = stream
            if (s != null && s.url == clean) { if (req != null) s.key = keyOf(req); return }
            val task = FutureTask {
                if (!streamable) return@FutureTask emptyList<MkvChapters.Chapter>()
                try {
                    MkvChapters.fetch(clean, all).also { Log.w(TAG, "stream chapters: ${it.size}") }
                } catch (e: Exception) {
                    Log.w(TAG, "chapters unavailable: $e"); emptyList()
                }
            }
            stream = Stream(clean, req?.let { keyOf(it) }, task)
            Net.misc.execute(task)
        }
    }

    /** True when [req]'s creditsStartMs is only an estimate: fine for "Up next" timing, don't tick/label it. */
    fun creditsEstimated(req: PlaybackRequest): Boolean = keyOf(req)?.let { synchronized(this) { estimated[it] } } ?: true

    /** "Auto-skip intros" (default off), per profile. For a settings toggle in the player UI. */
    fun autoSkipIntros(ctx: Context) = prefs(ctx).getBoolean("auto_skip_intro" + Profiles.suffix(ctx), false)

    fun setAutoSkipIntros(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean("auto_skip_intro" + Profiles.suffix(ctx), on).apply()

    // ---------------------------------------------------------------- internals

    private class Stream(val url: String, @Volatile var key: String?, val task: FutureTask<List<MkvChapters.Chapter>>)

    private var stream: Stream? = null
    private val pending = HashMap<String, MutableList<(SkipInfo) -> Unit>>()
    private val estimated = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 16
    }
    @Volatile private var blockedUntil = 0L // TheIntroDB 429 back-off

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dream_player", Context.MODE_PRIVATE)

    internal fun keyOf(req: PlaybackRequest): String? {
        val id = when {
            req.tmdbId > 0 -> "t${req.tmdbId}"
            !req.imdb.isNullOrBlank() -> "i${req.imdb}"
            else -> return null
        }
        return if (req.type == "episode") {
            if (req.season == null || req.episode == null) null else "e:$id:${req.season}:${req.episode}"
        } else "m:$id"
    }

    /** Durations within a couple of minutes are the same cut (TheIntroDB clusters within 5 min). */
    private fun cacheKey(key: String, durationMs: Long) = "$key@${if (durationMs > 0) (durationMs + 60_000) / 120_000 else 0}"

    internal fun resolve(ctx: Context, req: PlaybackRequest, durationMs: Long, cb: (SkipInfo) -> Unit) {
        val app = ctx.applicationContext
        val key = keyOf(req)
        if (key == null) { deliver(cb, compose(app, req, null, null, durationMs)); return }
        val ck = cacheKey(key, durationMs)
        val s = synchronized(this) { stream?.also { if (it.key == null) it.key = key } } // bind on first use
        val chaptersReady = s == null || s.key != key || s.task.isDone
        val cached = RemoteCache.peek(ck)
        if (cached != null && chaptersReady) {
            deliver(cb, compose(app, req, cached.markers, chaptersFor(key), durationMs)); return
        }
        synchronized(this) {
            pending[ck]?.let { it.add(cb); return }
            pending[ck] = mutableListOf(cb)
        }
        Net.run {
            val remote = try { if (cached != null) cached.markers else fetchRemote(app, req, ck, durationMs) } catch (e: Exception) {
                Log.w(TAG, "markers lookup failed: $e"); null
            }
            val chapters = chaptersFor(key, waitMs = 8000)
            val info = compose(app, req, remote, chapters, durationMs)
            Log.w(TAG, "$ck -> ${info.markers} learned=${info.introLearned} estCredits=${info.creditsEstimated}")
            main.post {
                val cbs = synchronized(this) { pending.remove(ck) }.orEmpty()
                for (c in cbs) c(info)
            }
        }
    }

    private fun deliver(cb: (SkipInfo) -> Unit, info: SkipInfo) {
        if (Looper.myLooper() == Looper.getMainLooper()) cb(info) else main.post { cb(info) }
    }

    private fun chaptersFor(key: String, waitMs: Long = 0): List<MkvChapters.Chapter>? {
        val s = synchronized(this) { stream } ?: return null
        if (s.key != key) return null
        return try {
            if (waitMs > 0) s.task.get(waitMs, TimeUnit.MILLISECONDS) else if (s.task.isDone) s.task.get() else null
        } catch (e: Exception) { null }
    }

    private fun compose(ctx: Context, req: PlaybackRequest, remote: Markers?, chapters: List<MkvChapters.Chapter>?, durationMs: Long): SkipInfo {
        val fromFile = chapters?.let { MkvChapters.toMarkers(it, durationMs) }
        var m = SkipSources.merge(fromFile, remote) ?: Markers()
        var learned = false
        if (m.introEndMs == null && req.type == "episode") Learned.get(ctx, req)?.let { (s, e) ->
            if (durationMs <= 0 || e < durationMs / 2) {
                m = m.copy(introStartMs = (s - LEARN_LEAD_MS).coerceAtLeast(0), introEndMs = e); learned = true
            }
        }
        var est = false
        if (m.creditsStartMs == null) SkipSources.estimateCredits(req.type, durationMs)?.let { m = m.copy(creditsStartMs = it); est = true }
        keyOf(req)?.let { synchronized(this) { estimated[it] = est } }
        return SkipInfo(m, learned, est)
    }

    /** Network sources, through the disk cache. Null when nobody has data (cached as a miss). */
    private fun fetchRemote(ctx: Context, req: PlaybackRequest, ck: String, durationMs: Long): Markers? {
        RemoteCache.get(ctx, ck)?.let { return it.markers }
        if (SystemClock.elapsedRealtime() < blockedUntil) return null
        val ep = req.type == "episode"
        var m: Markers? = null
        val url = SkipSources.theIntroDbUrl(req.tmdbId, req.imdb, if (ep) req.season else null, if (ep) req.episode else null, durationMs)
        if (url != null) {
            m = try { SkipSources.theIntroDb(url, durationMs) } catch (e: SkipSources.RateLimited) {
                blockedUntil = SystemClock.elapsedRealtime() + e.seconds * 1000L
                Log.w(TAG, "TheIntroDB: $e")
                return null
            }
        }
        var transient = false
        if (ep && m?.introEndMs == null && req.tmdbId > 0) {
            val anime = try { animeMarkers(ctx, req, durationMs) } catch (e: Exception) {
                Log.w(TAG, "AniSkip path: $e"); transient = true; null
            }
            m = SkipSources.merge(m, anime)
        }
        val found = m?.takeIf { SkipSources.hasAny(it) }
        if (found != null || !transient) RemoteCache.put(ctx, ck, found)
        return found
    }

    /** AniSkip for Japanese animation only (TMDB says so), mapped to a MyAnimeList entry + episode. */
    private fun animeMarkers(ctx: Context, req: PlaybackRequest, durationMs: Long): Markers? {
        val season = req.season ?: return null
        val episode = req.episode ?: return null
        if (season < 1 || !Tmdb.hasKey(ctx)) return null
        // The show's cached details (genres, original language, seasons): no TMDB call per episode. Unavailable is
        // thrown, not returned, so it isn't cached as "no markers".
        val show = WatchHistory.details(ctx.applicationContext, "tv", req.tmdbId) ?: throw IllegalStateException("TMDB details unavailable")
        val genres = show.optJSONArray("genres")
        val animation = genres != null && (0 until genres.length()).any { genres.optJSONObject(it)?.optInt("id") == 16 }
        if (!animation || show.optString("original_language") != "ja") return null
        val seasons = show.optJSONArray("seasons")
        var absolute = episode
        if (seasons != null) for (i in 0 until seasons.length()) {
            val s = seasons.optJSONObject(i) ?: continue
            if (s.optInt("season_number") in 1 until season) absolute += s.optInt("episode_count")
        }
        val entries = SkipSources.armEntries(req.tmdbId)
        val (mal, malEp) = SkipSources.mapToMal(entries, season, episode, absolute) { SkipSources.kitsuCounts(it) } ?: return null
        Log.w(TAG, "anime S${season}E$episode -> MAL $mal ep $malEp")
        return SkipSources.aniSkip(mal, malEp, durationMs)
    }

    // ---------------------------------------------------------------- learned skips

    internal const val LEARN_LEAD_MS = 10_000L

    internal fun learn(ctx: Context, req: PlaybackRequest, fromMs: Long, toMs: Long) = Learned.record(ctx, req, fromMs, toMs)
    internal fun confirmLearned(ctx: Context, req: PlaybackRequest) = Learned.confirm(ctx, req)

    /**
     * Per show: where the user jumps past the intro. One record per show [start, end, confidence n, last used],
     * at most 100 shows in one SharedPreferences JSON; the least recently used is dropped.
     * A new jump within 25 s of the stored start (and of similar length) averages in and raises n; a different
     * one lowers n first and only replaces the record when n reaches 0, so one odd episode can't undo it.
     */
    private object Learned {
        private const val MAX_SHOWS = 100
        private var cache: JSONObject? = null

        private fun showKey(req: PlaybackRequest) = when {
            req.tmdbId > 0 -> "t${req.tmdbId}"
            !req.imdb.isNullOrBlank() -> "i${req.imdb}"
            req.title.isNotBlank() -> "n" + req.title.lowercase().trim()
            else -> null
        }

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("dream_player", Context.MODE_PRIVATE)

        @Synchronized private fun all(ctx: Context): JSONObject = cache ?: try {
            JSONObject(prefs(ctx).getString("learned_skips", "{}")!!)
        } catch (e: Exception) { JSONObject() }.also { cache = it }

        @Synchronized private fun save(ctx: Context, o: JSONObject) {
            while (o.length() > MAX_SHOWS) {
                var oldest: String? = null
                var t = Long.MAX_VALUE
                for (k in o.keys()) { val v = o.optJSONArray(k)?.optLong(3) ?: 0; if (v < t) { t = v; oldest = k } }
                o.remove(oldest ?: break)
            }
            prefs(ctx).edit().putString("learned_skips", o.toString()).apply()
        }

        @Synchronized fun get(ctx: Context, req: PlaybackRequest): Pair<Long, Long>? {
            val a = all(ctx).optJSONArray(showKey(req) ?: return null) ?: return null
            return a.optLong(0) to a.optLong(1)
        }

        @Synchronized fun record(ctx: Context, req: PlaybackRequest, from: Long, to: Long) {
            val k = showKey(req) ?: return
            val o = all(ctx)
            val now = System.currentTimeMillis()
            val old = o.optJSONArray(k)
            val rec = if (old == null) longArrayOf(from, to, 1, now) else {
                val s = old.optLong(0)
                val e = old.optLong(1)
                val n = old.optLong(2)
                val similar = kotlin.math.abs(s - from) <= 25_000 && kotlin.math.abs((e - s) - (to - from)) <= 20_000
                when {
                    similar -> { val w = n.coerceAtMost(5); longArrayOf((s * w + from) / (w + 1), (e * w + to) / (w + 1), n + 1, now) }
                    n > 1 -> longArrayOf(s, e, n - 1, now)
                    else -> longArrayOf(from, to, 1, now)
                }
            }
            o.put(k, JSONArray().apply { rec.forEach { put(it) } })
            Log.w(TAG, "learned intro for $k: ${rec.toList()}")
            save(ctx, o)
        }

        @Synchronized fun confirm(ctx: Context, req: PlaybackRequest) {
            val k = showKey(req) ?: return
            val o = all(ctx)
            val a = o.optJSONArray(k) ?: return
            a.put(2, a.optLong(2) + 1)
            a.put(3, System.currentTimeMillis())
            save(ctx, o)
        }
    }

    // ---------------------------------------------------------------- disk cache

    /**
     * TheIntroDB/AniSkip results, one small JSON file in cacheDir (~60 bytes per entry, at most 400 entries,
     * least recently used dropped). Hits keep 21 days (the community data gets refined), misses 2 days.
     * Errors and 429s are never stored. The map is loaded off the main thread on first use.
     */
    private object RemoteCache {
        class Entry(val markers: Markers?)

        private const val MAX = 400
        private const val HIT_TTL = 21L * 24 * 3600_000
        private const val MISS_TTL = 2L * 24 * 3600_000
        private var map: LinkedHashMap<String, Pair<Long, Markers?>>? = null
        private var file: File? = null
        /** One writer at a time: two puts sharing the .tmp file could leave it with another's tail. */
        private val writeLock = Any()

        @Synchronized private fun load(ctx: Context): LinkedHashMap<String, Pair<Long, Markers?>> {
            map?.let { return it }
            val f = File(ctx.cacheDir, "skip_markers.json").also { file = it }
            val m = LinkedHashMap<String, Pair<Long, Markers?>>(64, 0.75f, true)
            try {
                if (f.exists()) {
                    val o = JSONObject(f.readText())
                    for (k in o.keys()) {
                        val e = o.optJSONObject(k) ?: continue
                        m[k] = e.optLong("t") to e.optJSONArray("m")?.let { SkipSources.fromJson(it) }
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "skip cache unreadable: $e") }
            map = m
            return m
        }

        private fun fresh(e: Pair<Long, Markers?>) = System.currentTimeMillis() - e.first < if (e.second != null) HIT_TTL else MISS_TTL

        /** Main-thread safe: only answers once the file has been loaded. A known miss is Entry(null). */
        @Synchronized fun peek(key: String): Entry? = map?.get(key)?.takeIf { fresh(it) }?.let { Entry(it.second) }

        @Synchronized fun get(ctx: Context, key: String): Entry? = load(ctx)[key]?.takeIf { fresh(it) }?.let { Entry(it.second) }

        fun put(ctx: Context, key: String, m: Markers?) {
            val text: String
            synchronized(this) {
                val all = load(ctx)
                all[key] = System.currentTimeMillis() to m
                val iter = all.entries.iterator()
                while (all.size > MAX && iter.hasNext()) { iter.next(); iter.remove() }
                val o = JSONObject()
                for ((k, v) in all) {
                    val e = JSONObject().put("t", v.first)
                    v.second?.let { e.put("m", SkipSources.toJson(it)) }
                    o.put(k, e)
                }
                text = o.toString()
            }
            try {
                val f = file ?: return
                synchronized(writeLock) {
                    val tmp = File(f.path + ".tmp")
                    tmp.writeText(text)
                    tmp.renameTo(f)
                }
            } catch (e: Exception) { Log.w(TAG, "skip cache write failed: $e") }
        }
    }
}

/**
 * Shows "Skip Intro" / "Skip Recap" at the right moments via [PlayerHost.showSkip], auto-skips intros when the
 * setting is on, and learns intros from manual skips. No timers: it runs off the engine's ~2 Hz onProgress.
 */
class SkipController(private val host: PlayerHost) {
    private companion object {
        const val END_MARGIN = 2_000L       // hide the button this long before the segment ends
        const val ZERO_SNAP = 3_000L        // an intro starting this early counts as "from the start"
        const val SEEK_MIN = 5_000L         // a progress jump beyond normal playback by this much is a seek
        const val COALESCE = 4_000L         // forward seeks this close together are one skip (repeated →)
        const val LEARN_MIN = 30_000L
        const val LEARN_MAX = 150_000L
        const val LEARN_BEFORE = 12 * 60_000L
    }

    private val ctx get() = host.overlayRoot.context
    private var started = false
    private var reqKey: String? = null
    private var req: PlaybackRequest? = null
    private var info: SkipInfo? = null
    private var loading = false
    private var gen = 0
    private var shown: String? = null
    private var doneIntro = false
    private var doneRecap = false
    private var autoSkipped = false
    private var lastPos = -1L
    private var lastAt = 0L
    private var jumpFrom = -1L
    private var jumpTo = -1L
    private var jumpAt = 0L
    private var selfTarget = -1L
    private var selfAt = 0L
    private var notice: View? = null

    private val listener = object : PlayerEngine.Listener {
        override fun onState() { if (started) ensureLoaded() }
        override fun onProgress(positionMs: Long) { if (started) tick(positionMs) }
        override fun onEnded() { commitJump(); hide() }
    }

    fun start() {
        if (started) return
        started = true
        host.engine.addListener(listener)
        ensureLoaded()
    }

    fun stop() {
        if (!started) return
        started = false
        host.engine.removeListener(listener)
        commitJump()
        reset()
        reqKey = null
        notice?.let { host.overlayRoot.removeView(it) }
        notice = null
    }

    private fun reset() {
        hide()
        gen++
        info = null; loading = false
        doneIntro = false; doneRecap = false; autoSkipped = false
        lastPos = -1; jumpFrom = -1; selfTarget = -1
    }

    private fun ensureLoaded() {
        val r = host.request ?: return
        val key = SkipMarkers.keyOf(r) ?: ("?" + r.title)
        if (key != reqKey) { commitJump(); reset(); reqKey = key; req = r } // a new item (Up next)
        val dur = host.engine.durationMs
        if (dur <= 0 || info != null || loading) return
        loading = true
        val g = gen
        SkipMarkers.resolve(ctx, r, dur) { result ->
            if (g != gen || !started) return@resolve
            loading = false
            info = result
            evaluate(host.engine.positionMs)
        }
    }

    private fun tick(pos: Long) {
        ensureLoaded()
        trackSeeks(pos)
        evaluate(pos)
    }

    private fun evaluate(pos: Long) {
        val i = info ?: return
        val m = i.markers
        val introEnd = m.introEndMs
        val introStart = (m.introStartMs ?: 0).let { if (it < ZERO_SNAP) 0 else it }
        val recapEnd = m.recapEndMs
        // A recap after the opening (common in anime) starts where the intro ends.
        val recapStart = if (recapEnd != null && introEnd != null && introEnd < recapEnd && introStart < recapEnd) introEnd else 0L

        if (introEnd != null && pos < introStart - 5_000) { doneIntro = false; autoSkipped = false }
        if (recapEnd != null && recapStart > 0 && pos < recapStart - 5_000) doneRecap = false

        when {
            introEnd != null && !doneIntro && pos >= introStart && pos < introEnd - END_MARGIN -> {
                if (!i.introLearned && !autoSkipped && SkipMarkers.autoSkipIntros(ctx)) {
                    autoSkipped = true
                    doneIntro = true
                    skipTo(introEnd)
                    showNotice("Intro Skipped")
                } else show("Skip Intro") {
                    doneIntro = true
                    if (i.introLearned) req?.let { SkipMarkers.confirmLearned(ctx, it) }
                    skipTo(introEnd)
                }
            }
            recapEnd != null && !doneRecap && pos >= recapStart && pos < recapEnd - END_MARGIN -> show("Skip Recap") {
                doneRecap = true
                skipTo(recapEnd)
            }
            else -> hide()
        }
    }

    private fun show(label: String, onSkip: () -> Unit) {
        if (shown == label) return
        shown = label
        host.showSkip(label) { if (started) onSkip() }
    }

    private fun hide() {
        if (shown == null) return
        shown = null
        host.hideSkip()
    }

    private fun skipTo(ms: Long) {
        selfTarget = ms
        selfAt = SystemClock.elapsedRealtime()
        hide()
        host.engine.seekTo(ms)
    }

    /**
     * Learned skips: a forward seek (or a quick run of them) of 30–150 s that starts in the first 12 minutes of
     * an episode with no real intro data is remembered for the show. Our own skips don't count.
     */
    private fun trackSeeks(pos: Long) {
        val now = SystemClock.elapsedRealtime()
        if (lastPos >= 0) {
            // Ticks come ~2x/s; allow up to 2.5 s of real playback (covers 2x speed and a late tick).
            val jump = pos - lastPos - minOf(now - lastAt, 2_500L)
            if (jump > SEEK_MIN) {
                val self = selfTarget >= 0 && kotlin.math.abs(pos - selfTarget) < 3_000 && now - selfAt < 8_000
                when {
                    self -> { selfTarget = -1; jumpFrom = -1 }
                    jumpFrom >= 0 && now - jumpAt < COALESCE -> { jumpTo = pos; jumpAt = now }
                    else -> { commitJump(); jumpFrom = lastPos; jumpTo = pos; jumpAt = now }
                }
            } else if (pos < lastPos - SEEK_MIN) jumpFrom = -1 // went back: not an intro skip
        }
        if (jumpFrom >= 0 && now - jumpAt >= COALESCE) commitJump()
        lastPos = pos
        lastAt = now
    }

    private fun commitJump() {
        val from = jumpFrom
        val to = jumpTo
        jumpFrom = -1
        val r = req ?: return
        val i = info ?: return
        if (from < 0 || r.type != "episode") return
        if (to - from !in LEARN_MIN..LEARN_MAX || from >= LEARN_BEFORE) return
        if (i.markers.introEndMs != null && !i.introLearned) return  // real data already
        val recapEnd = i.markers.recapEndMs
        if (recapEnd != null && from < recapEnd) return               // skipping the recap by hand
        if (i.introLearned) {
            // Jumping by hand near a learned window we offered: refine it (far away = something else).
            val s = (i.markers.introStartMs ?: 0) + SkipMarkers.LEARN_LEAD_MS
            if (kotlin.math.abs(s - from) > 90_000) return
        }
        SkipMarkers.learn(ctx, r, from, to)
    }

    /** A quiet frosted pill, bottom-right where the skip button lives, that fades away by itself. */
    private fun showNotice(text: String) {
        val root = host.overlayRoot
        notice?.let { root.removeView(it) }
        val kit = PremiumKit(ctx)
        val v = kit.label(text, Ui.T_LABEL, kit.text, kit.medium).apply {
            background = PlayerLook.sheet(ctx, Ui.R_PILL)
            setPadding(Ui.dp(ctx, Ui.S5), Ui.dp(ctx, Ui.S3), Ui.dp(ctx, Ui.S5), Ui.dp(ctx, Ui.S3))
            alpha = 0f
            translationY = Ui.dpf(ctx, Ui.S2.toFloat())
        }
        root.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply {
            val m = Ui.dp(ctx, Ui.S7)
            setMargins(m, m, m, m * 2)
        })
        notice = v
        v.animate().alpha(1f).translationY(0f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.SETTLE).withEndAction {
            v.animate().alpha(0f).setStartDelay(1600).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT).withEndAction {
                if (notice === v) { root.removeView(v); notice = null }
            }.start()
        }.start()
    }
}
