package com.firehome.launcher

import android.content.Context
import android.media.MediaCodecList
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * Trailers' source: a title's YouTube trailer (picked from TMDB's videos), resolved to its streams with
 * NewPipe Extractor. Normally one small DASH manifest (video + audio adaptation sets, played by ExoPlayer's
 * DashMediaSource); when a video has no indexed streams, its HLS master or a muxed MP4 (see [TrailerPick]).
 *
 * Why DASH: YouTube throttles open-ended progressive downloads to about the stream's bitrate, while
 * bounded ranges come at full speed. DASH SegmentBase (the sidx index) makes ExoPlayer fetch ~5 s
 * chunks with exact ranges, and lets it switch between 1080p and 720p on a slow network.
 *
 * TMDB lists several videos per title (trailers, teasers, clips): they're tried best first until one
 * resolves, and one that fails to resolve or play is skipped for a while (per video, not per title), so
 * the next try takes the next one. Everything blocking runs on [Net.misc]; callbacks come back on the main thread.
 */
object TrailerEngine {
    private const val TAG = "Trailer"

    /** A resolved trailer: which video, how to play it, until when its links hold. */
    class Resolved(val videoKey: String, val plan: TrailerPick.Plan, val expiresAt: Long) {
        val isDash get() = plan is TrailerPick.Plan.Dash

        /** The DASH manifest with video up to [maxHeight] (DASH plans only). */
        fun manifest(maxHeight: Int): String = (plan as TrailerPick.Plan.Dash).manifest(maxHeight)
    }

    private val main = Handler(Looper.getMainLooper())
    /** Title key -> resolved trailer, or a miss (null) remembered briefly so a title without one isn't retried at once. */
    private class Entry(val r: Resolved?, val until: Long)
    private val cache = LruCache<String, Entry>(24)
    private val waiting = HashMap<String, MutableList<(Resolved?) -> Unit>>()
    /** Video id -> its last failure (resolving or playing). Shared by all titles; read on [Net.misc]. */
    private val fails = ConcurrentHashMap<String, TrailerPick.Fail>()
    /** Title key -> TMDB's videos for it, best first, and until when. */
    private val lists = LruCache<String, Pair<List<TrailerPick.Candidate>, Long>>(32)
    @Volatile private var initialized = false

    fun cached(t: Title): Resolved? = cache.get(t.key)?.takeIf { SystemClock.elapsedRealtime() < it.until }?.r

    /** Forget [t]'s result (its links may have expired): the next resolve looks it up again. */
    fun forget(t: Title) { cache.remove(t.key) }

    /** [videoKey] (of [t]) failed to play: it's skipped for a while, and the next resolve of [t] takes the next video. */
    fun failed(t: Title, videoKey: String, why: String) {
        val reason = TrailerPick.clean(why)
        fails[videoKey] = TrailerPick.Fail(SystemClock.elapsedRealtime() + TrailerPick.SOFT_FAIL_MS, false, "playback: $reason")
        cache.remove(t.key)
        Log.w(TAG, "${t.key}: $videoKey failed to play ($reason), the next try takes another video")
    }

    /**
     * Resolves [t]'s trailer; [done] gets null when none of its videos can be played. [retry] (the user asked
     * again): the title's remembered miss is ignored, and videos that failed softly are tried once more after
     * the fresh ones. Main thread.
     */
    fun resolve(ctx: Context, t: Title, retry: Boolean = false, done: (Resolved?) -> Unit) {
        if (retry && cache.get(t.key)?.r == null) cache.remove(t.key)
        cache.get(t.key)?.let { e ->
            if (SystemClock.elapsedRealtime() < e.until) { done(e.r); return }
            cache.remove(t.key)
        }
        waiting[t.key]?.let { it += done; return }
        waiting[t.key] = mutableListOf(done)
        val app = ctx.applicationContext
        Net.misc.execute {
            val started = SystemClock.elapsedRealtime()
            var listed = true
            val r = try {
                val cands = candidates(app, t)
                listed = cands.isNotEmpty()
                if (cands.isEmpty()) { Log.w(TAG, "${t.key}: TMDB lists no YouTube videos"); null } else fetch(t, cands, retry)
            } catch (e: Throwable) {
                Log.w(TAG, "resolve ${t.key} failed: ${e.javaClass.simpleName}: ${TrailerPick.clean(e.message)}")
                null
            }
            main.post {
                val now = SystemClock.elapsedRealtime()
                // A miss is remembered briefly (each video's own failure is remembered per video); longer when TMDB has none.
                cache.put(t.key, Entry(r, if (r != null) minOf(r.expiresAt, now + HIT_TTL) else now + if (listed) MISS_TTL else NONE_TTL))
                if (r != null) Log.w(TAG, "resolved ${t.name} (${r.videoKey}) in ${now - started} ms: ${r.plan.label}")
                else Log.w(TAG, "no playable trailer for ${t.key} after ${now - started} ms")
                waiting.remove(t.key)?.forEach { it(r) }
            }
        }
    }

    // ---- candidates ----

    /** TMDB's videos for [t], best first (see [TrailerPick.order]); the details' trailer key when the list fails. */
    private fun candidates(ctx: Context, t: Title): List<TrailerPick.Candidate> {
        lists.get(t.key)?.let { (l, until) -> if (SystemClock.elapsedRealtime() < until) return l }
        val lang = Locale.getDefault().language.ifEmpty { "en" }
        val langs = if (lang == "en") "en,null" else "$lang,en,null"
        val list = try {
            val json = Net.getJson("https://api.themoviedb.org/3/${t.type}/${t.id}/videos?api_key=${Tmdb.key(ctx)}&include_video_language=$langs")
            val a = json.optJSONArray("results")
            val all = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }
                .filter { it.optString("site") == "YouTube" && it.optString("key").isNotEmpty() }
                .map { o: JSONObject ->
                    TrailerPick.Candidate(o.optString("key"), o.optString("type"), o.optBoolean("official"),
                        o.optString("iso_639_1").takeIf { it.isNotEmpty() && it != "null" }, o.optInt("size"),
                        o.optString("published_at"), o.optString("name"))
                }
            TrailerPick.order(all, lang)
        } catch (e: Exception) {
            Log.w(TAG, "videos ${t.key}: ${e.javaClass.simpleName}: ${TrailerPick.clean(e.message)}")
            null
        }
        val result = list?.takeIf { it.isNotEmpty() }
            ?: (try { Tmdb.details(ctx, t).trailerKey } catch (e: Exception) { null })
                ?.let { listOf(TrailerPick.Candidate(it, "Trailer", true, null, 0, "", "")) }
            ?: emptyList()
        if (list != null) lists.put(t.key, result to SystemClock.elapsedRealtime() + LIST_TTL)
        return result
    }

    // ---- extraction ----

    private fun fetch(t: Title, cands: List<TrailerPick.Candidate>, retry: Boolean): Resolved? {
        init()
        val lang = Locale.getDefault().language
        val (c, plan) = TrailerPick.firstPlayable(cands, fails, SystemClock.elapsedRealtime(), retry, MAX_TRIES,
            log = { Log.w(TAG, "${t.key} try $it") }) { c ->
            // From the id, not a URL: parsing a watch URL goes through URLDecoder.decode(String, Charset), which
            // Android only has from API 33 (this stick is API 28).
            val ex = ServiceList.YouTube.getStreamExtractor(YoutubeStreamLinkHandlerFactory.getInstance().fromId(c.key))
            ex.fetchPage()
            TrailerPick.plan(ex, lang, hasVp9) { url ->
                try { NewPipe.getDownloader().get(url).takeIf { it.responseCode() == 200 }?.responseBody() } catch (e: Exception) { null }
            }
        } ?: return null
        // Stream URLs carry their expiry (unix seconds, as ?expire= or /expire/); keep a 10-minute margin.
        val expire = Regex("[?&/]expire[=/](\\d+)").find(plan.anyUrl)?.groupValues?.get(1)?.toLongOrNull()
        val expiresAt = if (expire != null) SystemClock.elapsedRealtime() + (expire * 1000 - System.currentTimeMillis()) - 10 * 60_000
            else SystemClock.elapsedRealtime() + HIT_TTL
        return Resolved(c.key, plan, expiresAt)
    }

    private val hasVp9: Boolean by lazy {
        try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { c ->
                !c.isEncoder && c.supportedTypes.any { it.equals("video/x-vnd.on2.vp9", true) }
            }
        } catch (e: Exception) { false }
    }

    // ---- NewPipe setup ----

    @Synchronized
    private fun init() {
        if (initialized) return
        val loc = Locale.getDefault()
        NewPipe.init(Http, Localization.fromLocale(loc), ContentCountry(loc.country.ifEmpty { "US" }))
        initialized = true
    }

    /** NewPipe's HTTP client: plain HttpURLConnection (no extra libraries). */
    private object Http : Downloader() {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

        override fun execute(request: Request): Response {
            val conn = URL(request.url()).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.useCaches = false
            conn.instanceFollowRedirects = true
            conn.requestMethod = request.httpMethod()
            var hasUa = false
            for ((name, values) in request.headers()) {
                if (name.equals("User-Agent", true)) hasUa = true
                conn.setRequestProperty(name, values.joinToString(", "))
            }
            if (!hasUa) conn.setRequestProperty("User-Agent", UA)
            try {
                request.dataToSend()?.let { body ->
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body) }
                }
                val code = conn.responseCode
                if (code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
                val raw = (if (code >= 400) conn.errorStream else conn.inputStream)
                val input = if (raw != null && conn.contentEncoding.equals("gzip", true)) GZIPInputStream(raw) else raw
                val body = input?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                val headers = conn.headerFields.filterKeys { it != null }
                return Response(code, conn.responseMessage, headers, body, conn.url.toString())
            } finally {
                conn.disconnect()
            }
        }
    }

    private const val HIT_TTL = 2 * 60 * 60_000L
    /** A title whose videos all failed: looked up again after this (each video's own failure lasts longer). */
    private const val MISS_TTL = 2 * 60_000L
    /** A title TMDB lists no videos for. */
    private const val NONE_TTL = 30 * 60_000L
    private const val LIST_TTL = 60 * 60_000L
    /** Videos extracted per resolve at most (each takes a second or two). */
    private const val MAX_TRIES = 6
}
