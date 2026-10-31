package com.dreamos.desktop.core.trailer

import com.dreamos.desktop.core.content.ContentPrefs
import com.dreamos.desktop.core.content.Tmdb
import com.dreamos.desktop.core.net.Lane
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.util.Log
import com.dreamos.desktop.core.util.a
import com.dreamos.desktop.core.util.bool
import com.dreamos.desktop.core.util.int
import com.dreamos.desktop.core.util.objects
import com.dreamos.desktop.core.util.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/** Which title's trailer: TMDB id and kind. [hintKey]: the details' trailer video, used when TMDB's list can't be read. */
data class TrailerRef(val tmdbId: Int, val isShow: Boolean, val name: String = "", val hintKey: String? = null) {
    /** "movie:603" / "tv:1396", like every other cache key in Dream OS. */
    val key: String get() = (if (isShow) "tv:" else "movie:") + tmdbId
}

/**
 * One way to play a resolved trailer in mpv: [url] is ready for `loadfile` (a local link, or an `edl://` that joins
 * the separate video and audio links). [cls] the picture's quality class, 0 when mpv picks (HLS). Never shown.
 */
data class TrailerSource(val url: String, val label: String, val cls: Int, val sound: Boolean)

/** A title's trailer, resolved: which video, its streams, and until when its links hold (epoch ms). */
class Trailer(val titleKey: String, val videoKey: String, val streams: TrailerPick.Streams, val expiresAt: Long) {
    val durationMs: Long get() = streams.durationMs

    /**
     * Ways to play it, best first, picture up to [maxClass]: the video and audio streams joined (full speed through
     * [TrailerProxy]), then YouTube's HLS cut to [maxClass] (segments; slower to start), then the muxed MP4. A plan
     * without sound comes last and only when nothing else exists.
     */
    fun sources(maxClass: Int): List<TrailerSource> {
        val out = ArrayList<TrailerSource>(3)
        val v = streams.videoUpTo(maxClass)
        val a = streams.audio.firstOrNull()
        if (v != null && a != null) {
            val vu = TrailerProxy.stream(v.url, v.mime, v.length)
            val au = TrailerProxy.stream(a.url, a.mime, a.length)
            out += TrailerSource(edl(vu, au), "${v.label} + ${a.label}", v.cls, true)
        }
        streams.hlsUrl?.let { out += TrailerSource(TrailerProxy.hls(it, maxClass), "HLS up to ${maxClass}p", 0, true) }
        streams.muxed?.let { m -> out += TrailerSource(TrailerProxy.stream(m.url, m.mime, m.length), "MP4 ${m.label}", m.cls, true) }
        if (v != null && a == null) out += TrailerSource(TrailerProxy.stream(v.url, v.mime, v.length), "${v.label}, no sound", v.cls, false)
        return out
    }

    /**
     * Fetches the first bytes of the picture and sound [sources] would play first at [maxClass] ([TrailerProxy.warmHead]),
     * so the hero's first frame doesn't wait for the network.
     */
    fun warmHeads(maxClass: Int) {
        streams.videoUpTo(maxClass)?.let { TrailerProxy.warmHead(it.url, it.length) }
        streams.audio.firstOrNull()?.let { TrailerProxy.warmHead(it.url, it.length) }
    }

    companion object {
        /** mpv's EDL: two parallel streams (picture and sound) played as one, each URL length-prefixed (`%n%url`). */
        fun edl(video: String, audio: String): String {
            fun esc(u: String) = "%${u.toByteArray(Charsets.UTF_8).size}%$u"
            return "edl://!new_stream;!no_clip;!no_chapters;${esc(video)};!new_stream;!no_clip;!no_chapters;${esc(audio)}"
        }
    }
}

/**
 * Trailers' source (fire-home TrailerEngine, iPhone Trailers): a title's trailer picked from TMDB's videos and resolved
 * to its streams with NewPipe Extractor, cached until the links expire. Shown and spoken of only as "Trailer"; the
 * host is never named on screen.
 *
 * TMDB lists several videos per title (trailers, teasers, clips): they're tried best first until one resolves, and
 * one that fails to resolve or play is skipped for a while (per video, not per title), so the next try takes the
 * next one. Every lookup is bounded; nothing throws to the caller.
 *
 * Memory: [MAX_TITLES] resolved trailers (a few KB each), [MAX_LISTS] TMDB lists, one failure mark per bad video.
 */
object TrailerEngine {
    private const val TAG = "Trailer"
    private const val HIT_TTL = 2 * 60 * 60_000L
    /** A title whose videos all failed: looked up again after this (each video's own failure lasts longer). */
    private const val MISS_TTL = 2 * 60_000L
    /** A title TMDB lists no videos for. */
    private const val NONE_TTL = 30 * 60_000L
    private const val LIST_TTL = 60 * 60_000L
    /** Videos extracted per lookup at most (each takes one to two seconds). */
    private const val MAX_TRIES = 6
    private const val MAX_TITLES = 64
    private const val MAX_LISTS = 128
    /** How long a stopped hero trailer's spot is kept for the Trailer button. */
    private const val SPOT_MS = 5 * 60_000L

    /** TMDB's background lane ([Lane]): a page's own details, images' lists and clicks go first. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + Lane.element())

    /** The picture class the title page's hero plays (1080, or 1440 for a tall hero); its warm heads are fetched ahead. */
    @Volatile var heroClass: Int = 1080

    private class Entry(val t: Trailer?, val until: Long)
    private val cache = lru<String, Entry>(MAX_TITLES)
    private val lists = lru<String, Pair<List<TrailerPick.Candidate>, Long>>(MAX_LISTS)
    private val inFlight = ConcurrentHashMap<String, Deferred<Trailer?>>()
    /** Video id -> its last failure (resolving or playing), shared by all titles. Pruned as it's read. */
    private val fails = ConcurrentHashMap<String, TrailerPick.Fail>()
    @Volatile private var initialized = false

    /** Epoch ms; tests may replace it. */
    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    private fun <K, V> lru(max: Int) = object : LinkedHashMap<K, V>(max, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
    }

    /** The resolved trailer for [ref] when one is cached and its links still hold; never waits. */
    fun cached(ref: TrailerRef): Trailer? = synchronized(cache) { cache[ref.key] }?.takeIf { clock() < it.until }?.t

    /** Starts a lookup for [ref] now (a title page opened) so it's ready when wanted. Returns at once. */
    fun prefetch(ref: TrailerRef) {
        if (cached(ref) == null) scope.launch { ahead(ref) }
    }

    /**
     * Readies [ref]'s trailer for the hero: looked up (or the cached answer), the proxy's links made and the first
     * bytes of the hero's picture and sound fetched ([Trailer.warmHeads]). Background lane; null when there is none.
     */
    suspend fun ahead(ref: TrailerRef): Trailer? {
        val t = resolve(ref) ?: return null
        t.sources(heroClass)
        t.warmHeads(heroClass)
        return t
    }

    /**
     * [ref]'s trailer, or null when none of its videos can be played. [retry] (the viewer asked again): the title's
     * remembered miss is ignored, and videos that failed softly are tried once more after the fresh ones.
     */
    suspend fun resolve(ref: TrailerRef, retry: Boolean = false): Trailer? {
        val k = ref.key
        synchronized(cache) {
            val e = cache[k]
            if (e != null && clock() < e.until && !(retry && e.t == null)) return e.t
            if (e != null) cache.remove(k)
        }
        val job = inFlight.computeIfAbsent(k) {
            scope.async(start = CoroutineStart.LAZY) {
                try { lookUp(ref, retry) } finally { inFlight.remove(k, coroutineContext[Job]) }
            }
        }
        job.start()
        return job.await()
    }

    /** [videoKey] (of [ref]) failed to play: it's skipped for a while, and the next lookup takes the next video. */
    fun failed(ref: TrailerRef, videoKey: String, why: String) {
        val reason = TrailerPick.clean(why)
        fails[videoKey] = TrailerPick.Fail(clock() + TrailerPick.SOFT_FAIL_MS, false, "playback: $reason")
        synchronized(cache) { cache.remove(ref.key) }
        Log.w(TAG, "${ref.key}: $videoKey failed to play ($reason); the next try takes another video")
    }

    /** Forget [ref]'s lookup (its links may have expired): the next one asks again. */
    fun forget(ref: TrailerRef) { synchronized(cache) { cache.remove(ref.key) } }

    // ---- the hero's spot, for the Trailer button ----

    private class Spot(val titleKey: String, val videoKey: String, val ms: Long, val at: Long)
    @Volatile private var spot: Spot? = null

    /** The hero trailer of [ref] stopped at [ms] into [videoKey]: the Trailer button continues from there for a while. */
    fun rememberSpot(ref: TrailerRef, videoKey: String, ms: Long) {
        spot = if (ms > 0) Spot(ref.key, videoKey, ms, clock()) else null
    }

    /** Where [ref]'s hero trailer stopped ([videoKey], ms), if it did in the last few minutes. */
    fun spot(ref: TrailerRef): Pair<String, Long>? =
        spot?.takeIf { it.titleKey == ref.key && clock() - it.at < SPOT_MS }?.let { it.videoKey to it.ms }

    // ---- lookup ----

    private fun lookUp(ref: TrailerRef, retry: Boolean): Trailer? {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        var listed = true
        val r = try {
            val cands = candidates(ref)
            listed = cands.isNotEmpty()
            if (cands.isEmpty()) { Log.i(TAG, "${ref.key}: TMDB lists no trailer videos"); null } else extract(ref, cands, retry)
        } catch (e: Throwable) {
            Log.w(TAG, "${ref.key}: lookup failed: ${e.javaClass.simpleName}: ${TrailerPick.clean(e.message)}")
            null
        }
        val now = clock()
        val until = if (r != null) minOf(r.expiresAt, now + HIT_TTL) else now + if (listed) MISS_TTL else NONE_TTL
        synchronized(cache) { cache[ref.key] = Entry(r, until) }
        if (r != null) Log.i(TAG, "${ref.name.ifEmpty { ref.key }} (${r.videoKey}) ready in ${ms()} ms: ${r.streams.label}")
        else Log.w(TAG, "no playable trailer for ${ref.key} after ${ms()} ms")
        return r
    }

    /** TMDB's videos for [ref], best first ([TrailerPick.order]); the details' trailer key when the list can't be read. */
    private fun candidates(ref: TrailerRef): List<TrailerPick.Candidate> {
        synchronized(lists) { lists[ref.key] }?.let { (l, until) -> if (clock() < until) return l }
        val lang = ContentPrefs.viewerLang()
        val list = if (!Tmdb.hasKey()) null else try {
            val langs = if (lang == "en") "en,null" else "$lang,en,null"
            val json = Net.getJson(Tmdb.url("/${if (ref.isShow) "tv" else "movie"}/${ref.tmdbId}/videos", "&include_video_language=$langs"))
            parseVideos(json.a("results").objects().map { o ->
                Video(o.str("site"), o.str("key"), o.str("type"), o.bool("official"), o.str("iso_639_1"), o.int("size"),
                    o.str("published_at"), o.str("name"))
            }, lang)
        } catch (e: Exception) {
            Log.w(TAG, "videos ${ref.key}: ${e.javaClass.simpleName}: ${TrailerPick.clean(e.message)}")
            null
        }
        val result = list?.takeIf { it.isNotEmpty() }
            ?: ref.hintKey?.takeIf { it.isNotBlank() }?.let { listOf(TrailerPick.Candidate(it, "Trailer", true, null, 0, "", "")) }
            ?: emptyList()
        if (list != null) synchronized(lists) { lists[ref.key] = result to clock() + LIST_TTL }
        return result
    }

    /** One row of TMDB's /videos answer. */
    class Video(val site: String, val key: String, val type: String, val official: Boolean, val lang: String,
                val size: Int, val published: String, val name: String)

    /** TMDB's rows -> candidates best first: only the one video host NewPipe resolves, rows with a key. */
    fun parseVideos(rows: List<Video>, lang: String): List<TrailerPick.Candidate> = TrailerPick.order(
        rows.filter { it.site == "YouTube" && it.key.isNotEmpty() }.map {
            TrailerPick.Candidate(it.key, it.type, it.official, it.lang.takeIf { l -> l.isNotEmpty() && l != "null" },
                it.size, it.published, it.name)
        }, lang)

    private fun extract(ref: TrailerRef, cands: List<TrailerPick.Candidate>, retry: Boolean): Trailer? {
        init()
        val lang = ContentPrefs.viewerLang()
        fails.entries.removeIf { clock() >= it.value.until }
        val (c, streams) = TrailerPick.firstPlayable(cands, fails, clock(), retry, MAX_TRIES,
            log = { Log.i(TAG, "${ref.key} try $it") }) { c ->
            val ex = ServiceList.YouTube.getStreamExtractor(YoutubeStreamLinkHandlerFactory.getInstance().fromId(c.key))
            ex.fetchPage()
            TrailerPick.streams(ex, lang)
        } ?: return null
        // Links carry their expiry (unix seconds); keep a 10-minute margin.
        val expire = streams.anyUrl?.let { TrailerPick.expiry(it) }
        val expiresAt = if (expire != null) expire * 1000 - 10 * 60_000 else clock() + HIT_TTL
        return Trailer(ref.key, c.key, streams, expiresAt)
    }

    // ---- NewPipe setup ----

    @Synchronized
    private fun init() {
        if (initialized) return
        val loc = Locale.getDefault()
        NewPipe.init(Http, Localization.fromLocale(loc), ContentCountry(loc.country.ifEmpty { "US" }))
        initialized = true
    }

    /** NewPipe's HTTP client (the JDK's own; no extra libraries). */
    private object Http : Downloader() {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
        private val client: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        override fun execute(request: Request): Response {
            val b = HttpRequest.newBuilder(URI(request.url())).timeout(Duration.ofSeconds(10))
            var hasUa = false
            for ((name, values) in request.headers()) {
                if (name.equals("User-Agent", true)) hasUa = true
                // The JDK client sets these itself and refuses them from callers.
                if (name.equals("Host", true) || name.equals("Content-Length", true) || name.equals("Connection", true)) continue
                b.header(name, values.joinToString(", "))
            }
            if (!hasUa) b.header("User-Agent", UA)
            val body = request.dataToSend()
            b.method(request.httpMethod(), if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
            val r = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
            if (r.statusCode() == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
            val gz = r.headers().firstValue("Content-Encoding").orElse("").equals("gzip", true)
            val text = (if (gz) GZIPInputStream(r.body().inputStream()).use { it.readBytes() } else r.body()).toString(Charsets.UTF_8)
            return Response(r.statusCode(), "", r.headers().map(), text, r.uri().toString())
        }
    }
}
