package com.dreamos.desktop.scrapers

import com.dreamos.desktop.core.playback.PlayTarget
import com.dreamos.desktop.core.playback.ScrapeJob
import com.dreamos.desktop.core.playback.ScrapeMode
import com.dreamos.desktop.core.playback.ScrapeQuery
import com.dreamos.desktop.core.playback.ScrapeState
import com.dreamos.desktop.core.playback.ScrapedSource
import com.dreamos.desktop.core.playback.SourceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * [SourceEngine] (core/playback): the viewer's scraper repositories as torrents for Dream Core (the gather half of fire-home's
 * NativeResolver; the iPhone's ScraperSources). One scrape per title / episode is shared by everyone who asks for
 * 30 minutes (an empty one is redone after two minutes; one that got no answer at all after 20 s, or at once for
 * a Play); switching providers or installing drops them.
 */
class ScraperSources(private val scrapers: Scrapers, private val info: ScraperInfo) : SourceEngine {
    private class Gather(val at: Long, val job: ScraperEngine.Job)
    private val gathers = ConcurrentHashMap<String, Gather>()
    private val gatherLock = Any()

    init {
        scrapers.changeHooks += { scrapersChanged() }
    }

    fun isActive(): Boolean = scrapers.active()

    // ---- core/playback's SourceEngine ----

    override fun active(): Boolean = scrapers.active()

    override fun warm() = scrapers.warm()

    override fun trim() { Thread({ try { scrapers.engine.trimNow() } catch (e: Exception) {} }, "scrapers-trim").apply { isDaemon = true }.start() }

    override fun onChanged(listener: () -> Unit) { scrapers.changeHooks += listener }

    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Starts (or reuses) the scrape for [query] and returns at once: the TMDB details the scrape needs are fetched on a
     * background thread. PLAY: a viewer is waiting (never queues behind look-aheads); FULL: a look-ahead; QUICK: the
     * pills' quick check (the fastest providers, short limits).
     */
    override fun start(query: ScrapeQuery, mode: ScrapeMode): ScrapeJob = CoreJob(query.target.copy(imdb = query.imdb), mode)

    /** One scrape as Dream Core reads it: the engine's job mirrored into a [ScrapeState] flow. */
    private inner class CoreJob(val target: PlayTarget, val mode: ScrapeMode) : ScrapeJob {
        private val flow = MutableStateFlow(ScrapeState(startedAtMs = System.nanoTime() / 1_000_000))
        override val state: StateFlow<ScrapeState> get() = flow
        @Volatile private var job: ScraperEngine.Job? = null
        @Volatile private var cancelled = false
        @Volatile private var promoted = mode == ScrapeMode.PLAY
        private val runner = adapterScope.launch {
            val j = runInterruptible(Dispatchers.IO) {
                if (!scrapers.active()) null else gather(target, play = mode != ScrapeMode.QUICK, light = mode == ScrapeMode.QUICK, urgent = mode == ScrapeMode.PLAY)
            }
            if (j == null) { flow.value = flow.value.copy(done = true, version = flow.value.version + 1); return@launch }
            job = j
            if (cancelled) j.cancel() else if (promoted && !j.urgent) j.promote()
            var v = flow.value.version
            j.version.collect {
                v++
                val items = j.items()
                flow.value = flow.value.copy(sources = candidates(items), version = v, done = j.done, cancelled = j.cancelled,
                    answered = if (j.done && j.failedAll) 0 else j.answered, total = j.total,
                    firstResultAtMs = flow.value.firstResultAtMs ?: if (items.isNotEmpty()) System.nanoTime() / 1_000_000 else null)
                if (j.done) throw kotlinx.coroutines.CancellationException("done")
            }
        }
        override fun cancel() {
            cancelled = true
            job?.let { if (!it.done) it.cancel() }
            if (job == null) { runner.cancel(); flow.value = flow.value.copy(done = true, cancelled = true, version = flow.value.version + 1) }
        }
        override fun promote() { promoted = true; job?.let { if (!it.done && !it.urgent) it.promote() } }
    }

    fun stream(request: PlayTarget, urgent: Boolean = false): Flow<List<ScrapedSource>> = channelFlow {
        if (!scrapers.active()) return@channelFlow
        val job = runInterruptible(Dispatchers.IO) { gather(request, play = true, light = false, urgent = urgent) } ?: return@channelFlow
        val sent = HashSet<String>()
        var any = false
        suspend fun flush() {
            val fresh = job.items().filter { sent.add(it.hash) }
            if (fresh.isNotEmpty()) { any = true; send(candidates(fresh)) }
        }
        withTimeoutOrNull(STREAM_LIMIT_MS) {
            job.version.takeWhile { !job.done }.collect { flush() }
        }
        flush()
        if (!any && job.done && !job.failedAll) send(emptyList())
    }.flowOn(Dispatchers.IO)

    suspend fun find(request: PlayTarget): List<ScrapedSource> = find(request, 21_000)

    suspend fun find(request: PlayTarget, budgetMs: Long, light: Boolean = false): List<ScrapedSource> {
        if (!scrapers.active()) return emptyList()
        val job = withContext(Dispatchers.IO) { gather(request, play = !light, light = light) } ?: return emptyList()
        job.awaitDoneSuspending(job.startedAt + budgetMs - System.currentTimeMillis())
        return candidates(job.items())
    }

    /** Blocking [find] for callers outside coroutines (the setup check, tests). */
    fun findBlocking(request: PlayTarget, budgetMs: Long, urgent: Boolean = true): List<ScrapedSource> {
        if (!scrapers.active()) return emptyList()
        val job = gather(request, play = true, light = false, urgent = urgent) ?: return emptyList()
        job.awaitDone(job.startedAt + budgetMs - System.currentTimeMillis())
        return candidates(job.items())
    }

    /** The running or finished scrape for [request] (tests, diagnostics). */
    internal fun jobFor(request: PlayTarget): ScraperEngine.Job? = gathers[key(request)]?.job

    suspend fun quick(request: PlayTarget): List<ScrapedSource>? {
        if (!scrapers.active()) return null
        // A full scrape of this title (a Play, a title page's look-ahead) with sources counts at once.
        usable(gathers[key(request)])?.takeIf { it.count > 0 }?.let { return candidates(it.items()) }
        val job = withContext(Dispatchers.IO) { gather(request, play = false, light = true) } ?: return null
        val until = maxOf(job.startedAt + 16_000, System.currentTimeMillis() + 7_000)
        val ended = job.awaitDoneSuspending(until - System.currentTimeMillis())
        val items = job.items()
        if (items.isEmpty() && (!ended || job.failedAll)) return null
        return candidates(items)
    }

    fun quickKnown(request: PlayTarget): List<ScrapedSource>? =
        usable(gathers[key(request) + "#quick"])?.takeIf { it.count > 0 }?.let { candidates(it.items()) }

    fun cancel(request: PlayTarget) {
        val job = gathers.remove(key(request))?.job ?: return
        if (!job.done) { ScraperLog.w("job ${job.id} ${job.label}: cancelled (a Play needs the room)"); job.cancel() }
    }

    fun promote(request: PlayTarget) {
        val job = gathers[key(request)]?.job ?: return
        if (!job.done && !job.urgent) job.promote()
    }

    /** Providers switched or sources installed / removed: earlier scrapes no longer say what the viewer would get. */
    private fun scrapersChanged() {
        val old = gathers.values.map { it.job }
        gathers.clear()
        old.filter { !it.done }.forEach { it.cancel() }
    }

    private fun key(r: PlayTarget): String =
        if (r.isEpisode && r.season != null && r.episode != null) "tv:${r.tmdbId}:${r.season}:${r.episode}" else "movie:${r.tmdbId}:${r.imdb.orEmpty()}"

    /** A kept scrape that still answers for its title, or null (then a new one starts). */
    private fun usable(g: Gather?, urgent: Boolean = false): ScraperEngine.Job? {
        g ?: return null
        val age = System.currentTimeMillis() - g.at
        val j = g.job
        if (age > INDEX_TTL_MS || j.cancelled) return null
        if (j.done && j.count == 0 && age > 120_000) return null
        // One that got no answer at all (busy, Python down, every provider failed) is never reused for long: a Play
        // asks again at once, everyone else after 20 s.
        if (j.done && j.count == 0 && j.failedAll && (urgent || age > 20_000)) return null
        return j
    }

    /** Blocking: the scrape for [request], one running or recent, else a new one. Null without enough title info. */
    private fun gather(request: PlayTarget, play: Boolean, light: Boolean, urgent: Boolean = false): ScraperEngine.Job? {
        val k = key(request) + if (light) "#quick" else ""
        usable(gathers[k], urgent)?.let { if (urgent) it.promote(); return it }
        val episode = request.isEpisode
        if (episode && (request.season == null || request.episode == null)) return null
        // Alternative titles / the episode's air date: a Play waits at most a moment for them.
        val infoJson = info.json(request, if (play) 1_200 else 2_500) ?: return null
        synchronized(gatherLock) {
            usable(gathers[k], urgent)?.let { if (urgent) it.promote(); return it }
            val refs = if (light) scrapers.quickRefs() else scrapers.enabledRefs()
            val job = scrapers.engine.start(infoJson, refs, label(request), light, play, urgent,
                timeoutMs = if (light) 5_000 else 20_000, perProviderMs = if (light) 4_000 else 12_000, packs = episode && !light)
            gathers[k] = Gather(System.currentTimeMillis(), job)
            if (gathers.size > 40) gathers.entries.removeIf { System.currentTimeMillis() - it.value.at > INDEX_TTL_MS }
            return job
        }
    }

    private fun label(r: PlayTarget) = if (r.season != null && r.episode != null) "${r.title} S${r.season}E${r.episode}" else r.title

    /** The engine's items as core/playback reads them: the provider's own label, raw provider id ("module:provider"). */
    private fun candidates(items: List<ScraperEngine.Item>): List<ScrapedSource> = items.map { i ->
        ScrapedSource(hash = i.hash, name = i.name, quality = i.quality.ifBlank { quality(i) }, sizeBytes = if (i.pack) 0 else i.bytes,
            seeders = i.seeders, provider = i.provider, pack = i.pack)
    }

    companion object {
        private const val INDEX_TTL_MS = 30 * 60_000L
        private const val STREAM_LIMIT_MS = 22_000L

        /** "4K" | "1080p" | "720p" | "SD" from the provider's label, else the release name. CAM-type labels are "SD". */
        fun quality(i: ScraperEngine.Item): String = when (i.quality.uppercase()) {
            "4K", "2160P" -> "4K"
            "1080P" -> "1080p"
            "720P" -> "720p"
            "CAM", "SCR", "TELESYNC", "TS" -> "SD"
            else -> {
                val t = i.name.uppercase()
                when {
                    has(t, "2160P", "4K", "UHD") -> "4K"
                    has(t, "1080P") -> "1080p"
                    has(t, "720P") -> "720p"
                    else -> "SD"
                }
            }
        }

        private fun has(text: String, vararg words: String) =
            words.any { w -> Regex("(?<![A-Z0-9])" + Regex.escape(w) + "(?![A-Z0-9])").containsMatchIn(text) }
    }
}

/** win-content's TMDB access (Net.getText through the TMDB gate): [path] like "/movie/603", no key / language added. */
fun interface TmdbFetch {
    fun get(path: String, query: Map<String, String>, timeoutMs: Long): String?
}

/**
 * What a Kodi-format scraper module expects from its caller: imdb, tmdb, title, localtitle, aliases, year, and for
 * an episode tvshowtitle, season, episode, premiered (its air date), tvdb, total_seasons. Built from TMDB (one
 * details call with external ids and alternative titles, plus the episode), cached 6 h (60 titles, then anything
 * older than an hour goes). Without [tmdb], from the request alone (needs its IMDb id).
 */
class ScraperInfo(private val tmdb: TmdbFetch?) {
    private class Hit(val at: Long, val json: JsonObject)
    private val cache = ConcurrentHashMap<String, Hit>()

    fun json(r: PlayTarget, waitMs: Long): JsonObject? {
        val ep = r.isEpisode
        val key = if (ep) "tv:${r.tmdbId}:${r.season}:${r.episode}" else "movie:${r.tmdbId}:${r.imdb.orEmpty()}"
        cache[key]?.takeIf { System.currentTimeMillis() - it.at < 6 * 3_600_000L }?.let { return it.json }
        val type = if (ep) "tv" else "movie"
        val id = r.tmdbId
        fun obj(s: String?): JsonObject? = s?.let { try { Json.parseToJsonElement(it) as? JsonObject } catch (e: Exception) { null } }
        // The details and the episode are asked at the same time; each gets at most [waitMs].
        val epiCall = if (tmdb != null && ep && id > 0) CompletableFuture.supplyAsync {
            tmdb.get("/tv/$id/season/${r.season}/episode/${r.episode}", emptyMap(), waitMs)
        } else null
        val d = if (tmdb != null && id > 0) obj(tmdb.get("/$type/$id", mapOf("append_to_response" to "external_ids,alternative_titles"), waitMs)) else null
        val e = epiCall?.let { obj(try { it.get(waitMs, TimeUnit.MILLISECONDS) } catch (x: Exception) { null }) }
        fun JsonObject?.str(k: String) = (this?.get(k) as? JsonPrimitive)?.contentOrNull.orEmpty()
        val ext = d?.get("external_ids") as? JsonObject
        val imdb = r.imdb?.takeIf { it.isNotBlank() } ?: d.str("imdb_id").ifEmpty { ext.str("imdb_id") }
        if (imdb.isEmpty()) { ScraperLog.w("no IMDb id for ${r.title}"); return null }
        val title = d.str(if (ep) "name" else "title").ifEmpty { r.title }
        val original = d.str(if (ep) "original_name" else "original_title")
        val year = r.year?.toString() ?: d.str(if (ep) "first_air_date" else "release_date").take(4)
        val aliases = ArrayList<JsonObject>()
        val seen = hashSetOf(title.lowercase())
        fun alias(t: String, country: String) {
            if (t.isNotBlank() && seen.add(t.lowercase()) && aliases.size < 12)
                aliases += buildJsonObject { put("title", JsonPrimitive(t)); put("country", JsonPrimitive(country)) }
        }
        alias(original, ((d?.get("origin_country") as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull?.lowercase().orEmpty())
        (d?.get("alternative_titles") as? JsonObject)?.let { alt ->
            ((alt["titles"] ?: alt["results"]) as? JsonArray)?.forEach { t ->
                val o = t as? JsonObject ?: return@forEach
                val c = o.str("iso_3166_1")
                if (c in setOf("US", "GB", "CA", "AU", "NZ", "IE")) alias(o.str("title"), c.lowercase())
            }
        }
        val out = buildJsonObject {
            put("imdb", JsonPrimitive(imdb)); put("tmdb", JsonPrimitive(if (id > 0) id.toString() else ""))
            put("year", JsonPrimitive(year)); put("aliases", JsonArray(aliases))
            if (ep) {
                put("tvshowtitle", JsonPrimitive(title))
                put("title", JsonPrimitive(r.episodeTitle ?: e.str("name").ifEmpty { "Episode ${r.episode}" }))
                put("localtitle", JsonPrimitive(title))
                put("season", JsonPrimitive(r.season.toString())); put("episode", JsonPrimitive(r.episode.toString()))
                put("premiered", JsonPrimitive(e.str("air_date")))
                put("tvdb", JsonPrimitive(ext?.get("tvdb_id")?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }?.toString() ?: ""))
                d?.get("number_of_seasons")?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }?.let { put("total_seasons", JsonPrimitive(it)) }
            } else {
                put("title", JsonPrimitive(title)); put("localtitle", JsonPrimitive(title))
            }
        }
        if (d != null && (!ep || e != null)) {
            cache[key] = Hit(System.currentTimeMillis(), out)
            if (cache.size > 60) cache.entries.removeIf { System.currentTimeMillis() - it.value.at > 3_600_000L }
        }
        return out
    }
}
