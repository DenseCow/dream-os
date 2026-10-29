package com.firehome.launcher

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The Dream Core Resolver (its name everywhere users see it): Dream OS's own stream resolver, Play
 * to a direct debrid HTTPS link in a second or two. It is the only resolver: what it can't find, nothing plays.
 *
 * The user's scrapers (the repositories they add: [Scrapers], run by [ScraperEngine]) are asked for
 * the title's torrents, which stream in as each provider answers. The user's own source filters
 * (DreamFilters) drop what they wouldn't play, the rest is ranked (best allowed quality, a size that
 * streams well over Wi-Fi, English, an MKV before an MP4, seeders) and the top [TOP] go to the linked
 * debrid services in turn (the user's order, by default Premiumize, TorBox, Real-Debrid, then AllDebrid;
 * one that starts streams clearly faster for this user goes first: [services]), which unlock the first
 * one they have cached. A found link is remembered for [LINK_TTL].
 *
 * Checking a torrent at AllDebrid or Real-Debrid adds it to the user's account (AllDebrid caps an
 * account at 1000 magnets, recently deleted ones included), so what a lookup may ask depends on why
 * it runs ([DebridPurpose]): browsing and opening a title only scrape and rank (kept [INDEX_TTL], so
 * Play goes straight to the debrid step; a details page also asks Premiumize, whose cache check adds
 * nothing); a real Play, the next episode and the source list's top rows ask the services. AllDebrid
 * at its magnet limit is skipped for the others ([AllDebridService.limited]).
 */
object NativeResolver {
    private const val TAG = "FireHome"
    /** How many ranked candidates the services see: enough to almost always hit a cached one, one upload call. */
    private const val TOP = 10
    private const val LINK_TTL = 40 * 60_000L   // debrid links stay valid for hours; this keeps them fresh
    /** A scrape (and so the ranking) for a title: long enough for a details page to be read before Play. */
    private const val INDEX_TTL = 30 * 60_000L
    private const val FAILED_TTL = 60 * 60_000L
    private const val GB = 1L shl 30

    private class Stamped<T>(val value: T, val at: Long = SystemClock.elapsedRealtime()) {
        fun fresh(ttl: Long) = SystemClock.elapsedRealtime() - at < ttl
    }

    /**
     * A scraped torrent with what ranking needs beyond [Candidate]. [text] is upper case (labels, names).
     * [pack]: a season pack (its size is the whole season's, so unknown per episode); [provider]: which
     * scraper found it ("module:provider").
     */
    private class Parsed(val c: Candidate, val text: String, val cam: Boolean, val dubbed: Boolean,
                         val pack: Boolean = false, val provider: String? = null) {
        /** The audio language the release name says (null: none said, usually the original). */
        val lang: String? by lazy { try { AudioLanguages.fromTrackName(c.torrentName) } catch (e: Exception) { null } }
        /** Language and verdict together: two separate fields could pair one language with another's verdict. */
        @Volatile private var audioFor: Pair<String, AnimeDubs.Audio>? = null
        /** What the name says about a dub in [want] ([AnimeDubs.audio]), kept for the last language asked. */
        fun audio(want: String): AnimeDubs.Audio {
            audioFor?.takeIf { it.first == want }?.let { return it.second }
            return AnimeDubs.audio(c.torrentName, want).also { audioFor = want to it }
        }

        // The filters and the sort read these on every pass (the sort per comparison): the name is scanned once.
        @Volatile private var bits = -1
        private fun bit(f: Int): Boolean {
            var b = bits
            if (b < 0) { b = flagsOf(this); bits = b }
            return b and f != 0
        }
        val hevc get() = bit(F_HEVC)
        val anyHdr get() = bit(F_ANY_HDR)
        val hdr10 get() = bit(F_HDR10)
        val dv get() = bit(F_DV)
        val av1 get() = bit(F_AV1)
        val avc get() = bit(F_AVC)
        val threeD get() = bit(F_3D)
        val upscaled get() = bit(F_UPSCALED)
        val collection get() = bit(F_COLLECTION)
        val mp4 get() = bit(F_MP4)
        val disc get() = bit(F_DISC)
    }

    private const val F_HEVC = 1
    private const val F_ANY_HDR = 1 shl 1
    private const val F_HDR10 = 1 shl 2
    private const val F_DV = 1 shl 3
    private const val F_AV1 = 1 shl 4
    private const val F_AVC = 1 shl 5
    private const val F_3D = 1 shl 6
    private const val F_UPSCALED = 1 shl 7
    private const val F_COLLECTION = 1 shl 8
    private const val F_MP4 = 1 shl 9
    private const val F_DISC = 1 shl 10

    private fun flagsOf(p: Parsed): Int {
        val t = p.text
        fun f(on: Boolean, bit: Int) = if (on) bit else 0
        return f(hevc(t), F_HEVC) or f(hdr(t), F_ANY_HDR) or f(has(t, "HDR", "HDR10"), F_HDR10) or
            f(has(t, "DV", "DOVI", "DOLBY VISION"), F_DV) or f(has(t, "AV1"), F_AV1) or
            f(has(t, "AVC", "X264", "H264", "H.264"), F_AVC) or f(has(t, "3D"), F_3D) or f(upscaled(t), F_UPSCALED) or
            f(has(t, "TRILOGY", "DUOLOGY", "QUADRILOGY", "PENTALOGY", "HEXALOGY", "COLLECTION", "SAGA", "BOXSET", "ANTHOLOGY", "COMPLETE"), F_COLLECTION) or
            f(mp4(p), F_MP4) or
            f(DISC.containsMatchIn(p.c.torrentName) || has(t, "ISO", "BDMV", "BD25", "BD50", "BD66", "BD100"), F_DISC)
    }

    private val links = ConcurrentHashMap<String, Stamped<Found>>()
    /** Per title/episode: info hashes whose link failed to play, with when. */
    private val failed = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val imdbs = ConcurrentHashMap<String, String>()
    private val inFlight = ConcurrentHashMap<String, Lookup>()

    /**
     * One lookup under way. [purpose] and [deadline] may be raised by a caller that joins it (Play pressed while
     * the page's ranking runs: the Play's own budget applies from then); [ran] is the purpose the debrid step
     * used, fixed by its first round (null: it never got there).
     */
    private class Lookup(@Volatile var purpose: DebridPurpose, @Volatile var deadline: Long) {
        lateinit var task: FutureTask<Found?>
        @Volatile var ran: DebridPurpose? = null
        /** It ended below Play with sources found but none passing the user's filters: a Play re-runs it (it may relax them). */
        @Volatile var strictEmpty = false
    }

    /**
     * Why a Play's lookup came back empty (read by PlayFlow right after [find]): [NOTHING] the sources had nothing
     * playable at all (not out yet), [NOT_READY] none of what was found is ready on the debrid service(s),
     * [NO_ANSWER] the scrapers didn't answer (every provider failed or timed out: never "not out yet");
     * absent: unknown (budget over...).
     */
    enum class Miss { NOTHING, NOT_READY, NO_ANSWER }
    private val misses = ConcurrentHashMap<String, Miss>()

    /** The last Play lookup's [Miss] for [req], or null (found, or the reason isn't certain). */
    fun miss(req: PlaybackRequest): Miss? = misses[key(req)]

    /**
     * A link played with the user's filters set aside (nothing passed them): [cam] it's a camera / screener copy.
     * Keyed by title, valid while [hash] is that title's link. PlayFlow shows a short note for it.
     */
    class Relaxed(val hash: String, val cam: Boolean)
    private val relaxedLinks = ConcurrentHashMap<String, Relaxed>()

    /** [found]'s relaxation note for [req], if it was played with the filters relaxed. */
    fun relaxed(req: PlaybackRequest, found: Found): Relaxed? = relaxedLinks[key(req)]?.takeIf { it.hash == found.hash }

    // ---- public API ----

    @Volatile private var known: Boolean? = null
    @Volatile private var checkedAt = 0L
    @Volatile private var checking = false

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("dream_core", Context.MODE_PRIVATE)

    /**
     * A linked debrid account exists (without one nothing can play). Any thread; on the main thread it
     * never does network I/O (last known answer, refreshed in the background).
     */
    fun available(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) return check(app)
        if (!checking && SystemClock.elapsedRealtime() - checkedAt > 5_000) {
            checking = true
            Net.run { try { check(app) } finally { checking = false } }
        }
        return known ?: false
    }

    private fun check(ctx: Context): Boolean =
        (DebridAccounts.get(ctx)?.any == true).also { known = it; checkedAt = SystemClock.elapsedRealtime() }

    /**
     * Blocking. Best cached stream for [req], or null (no account, no scrapers, nothing cached, budget
     * over). Concurrent calls for the same title/season/episode share ONE lookup (the second waits for
     * the first's result). A found link is remembered ~40 min, so a second call returns at once. Never throws.
     *
     * The lookup runs on a background thread and every caller waits at most its own [budgetMs], so the
     * budget holds even where a single request's timeout (at least 1 s) would overshoot it; a lookup
     * that outlives its caller still finishes (until its own deadline) and remembers what it finds.
     */
    fun find(ctx: Context, req: PlaybackRequest, budgetMs: Long = 8_000, purpose: DebridPurpose = DebridPurpose.PLAY): Found? {
        if (req.trailer) return null
        val k = key(req)
        val deadline = SystemClock.elapsedRealtime() + budgetMs
        val app = ctx.applicationContext
        ledger(app)
        if (purpose == DebridPurpose.PLAY) misses.remove(k)
        return try {
            peek(req) ?: run {
                var res: Found? = null
                for (attempt in 0..1) {
                    val mine = Lookup(purpose, deadline).also { l -> l.task = FutureTask<Found?> { lookup(app, req, k, l) } }
                    val l = inFlight.putIfAbsent(k, mine)
                        ?: mine.also { Net.runNow { try { mine.task.run() } finally { inFlight.remove(k, mine) } } }
                    if (l.purpose <= purpose) {
                        l.purpose = purpose
                        if (l.deadline < deadline) l.deadline = deadline
                    }
                    res = l.task.get((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
                    val ran = l.ran
                    // A details page's lookup that found only filtered-out sources: a Play runs its own (it may relax the filters).
                    val relaxable = ran == null && l.strictEmpty && purpose == DebridPurpose.PLAY
                    if (res != null || l === mine || (ran == null && !relaxable) || (ran != null && ran >= purpose)) break
                    // The lookup joined only ranked (a details page's): this one does the debrid step, on the ranking it left.
                    inFlight.remove(k, l)
                    misses.remove(k) // the joined lookup's answer isn't this Play's

                }
                res
            }
        } catch (e: TimeoutException) {
            Log.w(TAG, "Dream Core: ${label(req)} → nothing yet (budget of ${secs(budgetMs)} over; the lookup goes on and remembers what it finds)"); null
        } catch (e: ExecutionException) {
            Log.w(TAG, "Dream Core: ${label(req)} → failed (${e.cause?.javaClass?.simpleName})"); null
        } catch (e: Exception) {
            Log.w(TAG, "Dream Core: ${label(req)} → failed (${e.javaClass.simpleName})"); null
        }
    }

    /** The remembered link for [req], no network; null if none/expired. */
    fun peek(req: PlaybackRequest): Found? = links[key(req)]?.takeIf { it.fresh(LINK_TTL) }?.value

    /**
     * Forget a remembered link (it failed to play). Its torrent is also skipped for this title/episode
     * for [FAILED_TTL], so the next [find] ("Try another source") lands on the next candidate.
     */
    fun forget(req: PlaybackRequest) {
        val k = key(req)
        val f = links.remove(k)?.value ?: return
        failed.getOrPut(k) { ConcurrentHashMap() }[f.hash] = SystemClock.elapsedRealtime()
        Log.w(TAG, "Dream Core: ${label(req)}: ${f.service.uppercase()} ${f.quality} ${size(f.sizeBytes)} didn't play; skipped from now on")
    }

    @Volatile private var warmedAt = 0L

    /** Starts the scraper engine and opens connections ahead (DNS + TLS) to the linked service(s). Returns at once. */
    fun warm(ctx: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - warmedAt < 60_000) return // kept-alive sockets last minutes; no need to knock more often
        warmedAt = now
        val app = ctx.applicationContext
        try { Scrapers.warm(app) } catch (e: Exception) { Log.w(TAG, "Scrapers: warm failed ($e)") } // Python off the critical path
        Net.run {
            val a = DebridAccounts.get(app)
            ledger(app)
            a?.allDebrid?.let { t -> Net.run { AllDebridService.warm(t) } }
            a?.torBox?.let { t -> Net.run { TorBoxService.janitor(t) } }
            a?.takeIf { it.realDebrid != null }?.let { acc -> Net.run { RealDebridService(acc).sweep() } }
            listOfNotNull(
                a?.premiumize?.let { "https://www.premiumize.me/api/account/info" },
                a?.torBox?.let { "https://api.torbox.app/" },
                a?.realDebrid?.let { "https://api.real-debrid.com/rest/1.0/time" },
            ).forEach { u -> Net.run { try { DebridHttp.get(u, timeoutMs = 5_000) } catch (e: Exception) {} } }
            filters(app)
        }
    }

    // ---- the lookup ----

    /** Per title / episode; the same key AnimeDubs records its dub summaries under. */
    private fun key(r: PlaybackRequest) = AnimeDubs.key(r)

    /**
     * The release names of [req]'s good copies (no CAM, playable on this stick) for the title page's language pills
     * ([AnimeDubs.record] → [LangPills]): a dub only counts when Play could pick it.
     */
    private fun recordNames(ctx: Context, req: PlaybackRequest, all: List<Parsed>, want: String?) {
        if (want == null || all.isEmpty()) return
        val f = looseFilters(ctx)
        val ep = req.type == "episode"
        AnimeDubs.record(ctx, key(req), all.filter { !it.cam && allowed(it, f, ep) }.map { it.c.torrentName to it.c.hash }, want)
    }

    private fun label(r: PlaybackRequest) =
        if (r.type == "episode") "${r.title} S${r.season}E${r.episode}" else r.title + (r.year?.let { " ($it)" } ?: "")

    private fun now() = SystemClock.elapsedRealtime()

    /** A lookup that found nothing: logged, and for a Play its [Miss] kept for PlayFlow. */
    private fun none(req: PlaybackRequest, k: String, l: Lookup, why: String, miss: Miss? = null): Found? {
        if (miss != null && l.purpose == DebridPurpose.PLAY) misses[k] = miss
        Log.w(TAG, "Dream Core: ${label(req)} → nothing ($why)"); return null
    }

    private fun lookup(ctx: Context, req: PlaybackRequest, k: String, l: Lookup): Found? {
        val t0 = now()
        fun none(why: String, miss: Miss? = null) = none(req, k, l, why, miss)
        val acc = DebridAccounts.get(ctx)
        known = acc?.any == true; checkedAt = now()
        if (acc == null || !acc.any) return none("no debrid account linked")
        val ep = req.type == "episode"
        val s = req.season; val e = req.episode
        if (ep && (s == null || e == null)) return none("no season/episode")
        val imdb = imdbFor(ctx, req) ?: return none("no IMDb id")
        if (l.purpose != DebridPurpose.BROWSE) DreamSubtitles.prefetch(ctx, req, imdb) // alongside the scrape and the service: ready when the stream is
        // The user's installed scrapers are Dream Core's sources.
        if (!Scrapers.active(ctx)) return none("no scraper sources added", Miss.NOTHING)
        return scraperLookup(ctx, req, k, imdb, l, acc, t0)
    }

    /** The show's (episodes) or movie's IMDb id: the request's own, else TMDB's external ids. */
    internal fun imdbFor(ctx: Context, req: PlaybackRequest): String? {
        req.imdb?.takeIf { it.startsWith("tt") }?.let { return it }
        if (req.tmdbId <= 0) return null
        val type = if (req.type == "episode") "tv" else "movie"
        imdbs["$type:${req.tmdbId}"]?.let { return it }
        val d = WatchHistory.details(ctx, type, req.tmdbId) ?: return null
        val id = (d.optJSONObject("external_ids")?.optString("imdb_id") ?: "").ifEmpty { d.optString("imdb_id") }
        return id.takeIf { it.startsWith("tt") }?.also { imdbs["$type:${req.tmdbId}"] = it }
    }

    // ---- the user's scrapers (Scrapers / ScraperEngine) ----

    /** How long Play waits for more sources once the first arrived, with none / one of the best allowed grade in. */
    private const val GRACE_MS = 1_200L
    private const val GRACE_GOOD_MS = 250L
    /** Time kept for the debrid step before a Play's budget ends. */
    private const val DEBRID_RESERVE_MS = 2_500L

    /** Running and recent scrapes per title / episode ("#quick": the availability checker's quick check). */
    private val gathers = ConcurrentHashMap<String, Stamped<ScraperEngine.Job>>()
    private val parsedMemo = ConcurrentHashMap<String, Parsed>()

    /** Scrapes kept at most (a full scrape holds a few hundred sources); half an hour of browsing quick-checks more. */
    private const val MAX_GATHERS = 40
    /** Parsed sources kept (~0.7 KB each); past this the memo starts over. */
    private const val MAX_PARSED = 4000

    /** Drops the oldest finished scrapes until [n] are left (running ones stay: a lookup is reading them). */
    private fun keepNewest(n: Int) = synchronized(gathers) {
        val old = gathers.entries.filter { it.value.value.done }.sortedBy { it.value.at }
        for (e in old.take((gathers.size - n).coerceAtLeast(0))) gathers.remove(e.key, e.value)
    }

    /**
     * Playback started ([ScraperEngine.trimNow]): the parsed copies go (rebuilt on the next ranking) and only the
     * newest few scrapes stay, the playing title's among them (Change Source reads it).
     */
    internal fun trimForPlayback() {
        parsedMemo.clear()
        keepNewest(4)
    }

    /** Providers switched or sources installed / removed: earlier scrapes no longer say what the user would get. */
    internal fun scrapersChanged() {
        gathers.values.forEach { if (!it.value.done) it.value.cancel() }
        gathers.clear()
        parsedMemo.clear()
    }

    /**
     * The scrape for [req]: one already running or done in the last [INDEX_TTL] (an empty one is redone
     * after two minutes), else a new one. [play]: a user is waiting (its own thread lane); [light]: the
     * quick check (two fast providers, short limits, no season packs).
     */
    private fun gather(ctx: Context, req: PlaybackRequest, imdb: String, play: Boolean, light: Boolean = false): ScraperEngine.Job {
        val k = key(req) + if (light) "#quick" else ""
        // A scrape the scrapers never answered (every provider failed or timed out) is never reused: it says
        // nothing about the title, and reusing it failed every Play for two minutes at once.
        fun usable(j: Stamped<ScraperEngine.Job>?) = j != null && j.fresh(INDEX_TTL) && !j.value.cancelled &&
            !(j.value.done && j.value.count == 0 && (j.value.failedAll || !j.fresh(2 * 60_000L)))
        gathers[k]?.takeIf { usable(it) }?.let { return it.value }
        val ep = req.type == "episode"
        // Alternative titles / the episode's air date: a Play waits at most half a second for them (usually cached by then).
        val info = Scrapers.info(ctx, req, imdb, waitMs = if (play) 500 else 1_500)
        synchronized(gathers) {
            gathers[k]?.takeIf { usable(it) }?.let { return it.value }
            val refs = if (light) Scrapers.quickRefs(ctx) else Scrapers.enabledRefs(ctx)
            val job = ScraperEngine.start(ctx, info, refs, label(req), light, play,
                timeoutMs = if (light) 5_000 else 20_000, perProviderMs = if (light) 4_000 else 12_000, packs = ep && !light)
            gathers[k] = Stamped(job)
            if (gathers.size > MAX_GATHERS) gathers.entries.removeAll { !it.value.fresh(INDEX_TTL) }
            if (gathers.size > MAX_GATHERS) keepNewest(MAX_GATHERS)
            return job
        }
    }

    /**
     * [job]'s torrents as the ranking reads them (hosters need a hoster unlock Dream Core doesn't do yet). Junk
     * ([JUNK_WORDS]) never gets this far, so it can't be played, listed or counted as Available.
     */
    private fun parsedOf(job: ScraperEngine.Job, season: Int?, episode: Int?): List<Parsed> {
        if (parsedMemo.size > MAX_PARSED) parsedMemo.clear()
        return job.items().mapNotNull { it ->
            val h = it.hash ?: return@mapNotNull null
            parsedMemo.getOrPut("$h|$season|$episode|${it.provider}") { scraped(it, h, season, episode) }
        }.filterNot { has(it.text, *JUNK_WORDS) }
    }

    private fun merged(job: ScraperEngine.Job, quick: ScraperEngine.Job?, season: Int?, episode: Int?): List<Parsed> {
        val a = parsedOf(job, season, episode)
        if (quick == null || quick === job) return a
        val seen = a.mapTo(HashSet()) { it.c.hash }
        return a + parsedOf(quick, season, episode).filter { seen.add(it.c.hash) }
    }

    private fun scraped(i: ScraperEngine.Item, hash: String, season: Int?, episode: Int?): Parsed {
        val text = i.name.uppercase(java.util.Locale.US)
        var cam = false
        val quality = when (i.quality.uppercase(java.util.Locale.US)) {
            "4K", "2160P" -> "4K"
            "1080P" -> "1080p"
            "720P" -> "720p"
            "CAM", "SCR", "TELESYNC", "TS" -> { cam = true; "SD" }
            else -> when { has(text, "2160P", "4K", "UHD") -> "4K"; has(text, "1080P") -> "1080p"; has(text, "720P") -> "720p"; else -> "SD" }
        }
        cam = cam || has(text, *CAM_WORDS)
        // A season pack's size is the whole season's: unknown per episode (size limits and the Wi-Fi-friendly rank skip it).
        val bytes = if (i.pack) 0L else i.bytes
        val c = Candidate(hash, i.name, null, null, bytes, quality, i.seeders, season, episode)
        val dubbed = CYRILLIC.containsMatchIn(i.name) || has(text, "DUB", "DUBBED", "DUBLADO", "MULTI", "DUAL", "TRUEFRENCH", "FRENCH", "ITA", "LATINO")
        return Parsed(c, text, cam, dubbed, pack = i.pack, provider = i.provider)
    }

    /** The title's running time in seconds when the "fit my internet speed" size limit needs it (TMDB, cached); else 0. */
    private fun runtimeFor(ctx: Context, req: PlaybackRequest, f: DreamFilters.Values): Int {
        val ep = req.type == "episode"
        if (f.speedMbps(ep) == null || req.tmdbId <= 0) return 0
        return try {
            val d = WatchHistory.details(ctx.applicationContext, if (ep) "tv" else "movie", req.tmdbId) ?: return 0
            val min = if (ep) d.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 }
                ?: d.optJSONObject("last_episode_to_air")?.optInt("runtime")?.takeIf { it > 0 } ?: 0
            else d.optInt("runtime")
            min * 60
        } catch (e: Exception) { 0 }
    }

    /** Dream Player's preferred audio language (2-letter), else the device's. */
    private fun wantLang(ctx: Context): String? = try {
        PlayerPrefs.normLang(PlayerPrefs.audioLang(ctx.applicationContext) ?: java.util.Locale.getDefault().language)
    } catch (e: Exception) { null }

    /** Best tier the user's filters can give at all (what "a good result" means for the early exit). */
    private fun bestTier(f: DreamFilters.Values) = if (f.known) f.cap.coerceAtMost(3) else f.deviceCap

    /**
     * Dream Core through the user's scrapers. The scrape streams sources in; a Play doesn't wait for every
     * provider: once the first ones are in it waits a moment for more ([GRACE_GOOD_MS] when one of the best
     * allowed grade is there, else [GRACE_MS]), ranks what it has (the user's filters, [choose]) and asks
     * the debrid services. Nothing ready yet: the next sources to arrive get their turn (up to three rounds),
     * until the budget ends. The scrape itself runs on and is kept for Change Source.
     */
    private fun scraperLookup(ctx: Context, req: PlaybackRequest, k: String, imdb: String, l: Lookup,
                              acc: DebridAccounts.Accounts, t0: Long): Found? {
        fun none(why: String, miss: Miss? = null) = none(req, k, l, why, miss)
        // Read live: Play can join a details page's lookup and upgrade it (and its deadline) while it waits ([find]).
        // Only a real Play sets the user's filters aside when nothing passes them (never a pre-resolve: no speculative CAM unlocks).
        fun relaxOk() = l.purpose == DebridPurpose.PLAY
        val ep = req.type == "episode"
        val s = if (ep) req.season else null
        val e = if (ep) req.episode else null
        // Browsing (focus resting on a card): only the quick check (two fast providers); a details page or Play: the full scrape.
        if (l.purpose == DebridPurpose.BROWSE) { gather(ctx, req, imdb, play = false, light = true); return null }
        val job = gather(ctx, req, imdb, play = l.purpose >= DebridPurpose.LIST)
        // What the quick check already found counts at once (the full scrape adds to it as it comes in).
        val quick = gathers["$k#quick"]?.takeIf { it.fresh(INDEX_TTL) }?.value
        val f = filters(ctx)
        val rt = runtimeFor(ctx, req, f)
        val want = wantLang(ctx)
        val dub = AnimeDubs.rank(ctx, req)
        val resume = resuming(ctx, req)
        val best = bestTier(f)
        val skip = failed[k]?.let { m -> m.entries.removeAll { now() - it.value > FAILED_TTL }; m.keys.toSet() }.orEmpty()
        val tried = HashSet<String>()
        var services: List<DebridService>? = null
        var round = 0
        while (true) {
            var kept: List<Parsed>
            var all: List<Parsed>
            val avoid = skip + tried
            while (true) {
                val v = job.version
                all = merged(job, quick, s, e)
                kept = choose(all, f, ep, avoid, rt, want, resume = resume, dub = dub)
                val t = now()
                val until = l.deadline - DEBRID_RESERVE_MS
                if (job.done || t >= until) break
                var wake = until
                // A page's lookup checks every 250 ms whether a Play joined it, so that Play never waits on the slowest scraper.
                if (l.purpose == DebridPurpose.PAGE) wake = minOf(wake, t + 250)
                if (l.purpose != DebridPurpose.PAGE && kept.isNotEmpty()) {
                    if (round > 0) break // later rounds: whatever new arrives goes at once
                    val since = t - job.startedAt - job.firstMs
                    val good = kept.any { !it.cam && tier(it.c.quality, f) <= best } // a CAM never cuts the wait short
                    val grace = if (good) GRACE_GOOD_MS else GRACE_MS
                    if (since >= grace) break
                    wake = minOf(wake, t + (grace - since))
                }
                job.await(v, wake)
            }
            // Nothing passes the user's filters once the scrape is in (or the wait is over): a Play relaxes them ([chooseRelaxed]).
            var level = 0
            if (kept.isEmpty() && relaxOk()) chooseRelaxed(all, f, ep, avoid, rt, want, true, resume, dub).let { kept = it.first; level = it.second }
            if (kept.isEmpty()) {
                if (job.done || now() >= l.deadline) {
                    val n = job.count
                    if (l.purpose < DebridPurpose.PLAY && n > 0 && round == 0) l.strictEmpty = true
                    return if (l.purpose == DebridPurpose.PAGE) null
                    else if (n == 0) {
                        if (job.done && job.failedAll) none("the scrapers didn't answer", Miss.NO_ANSWER)
                        else none("the scrapers found nothing${if (job.done) "" else " yet"}", if (job.done) Miss.NOTHING else null)
                    }
                    else if (round == 0) none("nothing of $n passes your source filters${if (relaxOk()) ", even relaxed" else ""}", if (job.done) Miss.NOTHING else null)
                    else none("none of ${tried.size} cached", Miss.NOT_READY)
                }
                job.await(job.version, l.deadline)
                continue
            }
            val top = kept.take(TOP)
            recordNames(ctx, req, all, want)
            // The debrid step keeps the purpose of its first round: a Play that joins a page's lookup during it gets
            // this lookup's answer, then runs its own debrid step with every service ([find]).
            val ran = l.ran ?: l.purpose.also { l.ran = it }
            val svcs = services ?: services(ctx, acc, ran).also { services = it }
            if (svcs.isEmpty()) {
                if (ran == DebridPurpose.PAGE) { Log.w(TAG, "Instant play: ranked ${kept.size} for ${label(req)} (debrid step on Play)"); return null }
                return none("AllDebrid is at its magnet limit and no other service is linked")
            }
            for (svc in svcs) {
                val deadline = l.deadline
                if (now() >= deadline) break
                val cands = if (ran == DebridPurpose.AHEAD && svc.key == "rd") top.take(2).map { it.c } else top.map { it.c }
                val found = try { svc.resolve(cands, deadline) } catch (x: Exception) {
                    Log.w(TAG, "Dream Core: ${svc.key.uppercase()} failed (${x.javaClass.simpleName})"); null
                }
                if (found == null) { if (svc is AllDebridService && adLimited(acc)) limitNote(ctx, svcs.drop(svcs.indexOf(svc) + 1), svcs.size > 1); continue }
                links[k] = Stamped(found)
                if (links.size > 100) links.entries.removeAll { !it.value.fresh(LINK_TTL) }
                noteRelaxed(req, k, found, kept, level, f)
                kept.firstOrNull { it.c.hash == found.hash }?.let { noteAvailable(ctx, k, it) }
                val from = kept.firstOrNull { it.c.hash == found.hash }?.provider?.substringAfter(':') ?: "?"
                Log.w(TAG, "Scrapers: ${label(req)} first ready result in ${now() - t0} ms from $from")
                Log.w(TAG, "Dream Core: ${label(req)} → ${found.service.uppercase()} ${found.quality} ${size(found.sizeBytes)} in ${secs(now() - t0)} " +
                    "(scrapers: ${job.count} sources so far, ${kept.size} kept, round ${round + 1})")
                return found
            }
            tried += top.map { it.c.hash }
            round++
            if (ran == DebridPurpose.PAGE) { Log.w(TAG, "Instant play: ranked ${kept.size} for ${label(req)} (debrid step on Play)"); return null }
            val at = svcs.joinToString("/") { it.key.uppercase() }
            if (round >= 3) return none("none of ${tried.size} cached at $at", Miss.NOT_READY)
            if (now() >= l.deadline) return none("budget over; none of ${tried.size} cached at $at")
            if (job.done && chooseRelaxed(merged(job, quick, s, e), f, ep, skip + tried, rt, want, relaxOk(), resume, dub).first.isEmpty())
                return none("none of ${tried.size} cached at $at", Miss.NOT_READY)
        }
    }

    /** What the setup page's test saw: [found] sources, [ready] of the top ones ready on the account. */
    internal class TestOutcome(val found: Int, val ready: Int, val account: Boolean)

    /** Blocking. A full scrape of [req] and a readiness check of its best few, for Scrapers.test. */
    internal fun testScrape(ctx: Context, req: PlaybackRequest, budgetMs: Long): TestOutcome {
        val app = ctx.applicationContext
        val deadline = now() + budgetMs
        val imdb = imdbFor(app, req) ?: return TestOutcome(0, 0, false)
        val job = gather(app, req, imdb, play = true)
        job.awaitDone(deadline - 6_000)
        val found = job.count
        val acc = DebridAccounts.get(app)
        if (acc?.any != true || found == 0) return TestOutcome(found, 0, acc?.any == true)
        val top = choose(parsedOf(job, null, null), looseFilters(app), false, emptySet()).take(5).map { it.c }
        val ready = readyHashes(app, top, (deadline - now()).coerceAtLeast(3_000))?.size
            ?: if (unlock(app, req, top, maxOf(deadline, now() + 4_000), "test") != null) 1 else 0
        return TestOutcome(found, ready, true)
    }

    /** Developer hook (PingReceiver --es scrapers ...): blocking, logs only. */
    internal fun dev(ctx: Context, what: String, req0: PlaybackRequest?, url: String?, id: String?, on: Boolean) {
        val app = ctx.applicationContext
        fun progress(m: String) { Log.w(TAG, "Scrapers: dev: $m") }
        val req = req0?.let { r ->
            val ep = r.type == "episode"
            val d = if (r.title.isBlank()) WatchHistory.details(app, if (ep) "tv" else "movie", r.tmdbId) else null
            r.copy(title = r.title.ifBlank { d?.optString(if (ep) "name" else "title").orEmpty().ifEmpty { "TMDB ${r.tmdbId}" } },
                year = r.year ?: d?.optString(if (ep) "first_air_date" else "release_date")?.take(4)?.toIntOrNull())
        }
        when (what) {
            "status" -> {
                val st = Scrapers.status(app)
                progress("${st.repos.size} sources (${st.repos.joinToString { "${it.id} ${it.version}" }}), " +
                    "${st.providers.count { it.enabled }} of ${st.providers.size} providers on (${st.providers.filter { it.enabled }.joinToString { it.id }}), " +
                    "python ${if (st.pythonReady) "ready" else "not started"}, active ${Scrapers.active(app)}")
            }
            "warm" -> { val t0 = now(); ScraperEngine.ensure(app); progress("ensure took ${now() - t0} ms") }
            "add" -> progress("add: ${Scrapers.addRepository(app, url ?: "") { progress(it) }}")
            "update" -> progress("update: ${Scrapers.updateAll(app) { progress(it) }}")
            "remove" -> { Scrapers.removeRepository(app, id ?: ""); progress("removed ${id}") }
            "enable" -> { Scrapers.setEnabled(app, id ?: "", on); progress("${id} ${if (on) "on" else "off"}") }
            "test" -> progress("test: ${Scrapers.test(app) { progress(it) }}")
            "survey" -> {
                if (req == null) return progress("survey needs --ei movie or --ei show")
                val k = key(req); gathers.remove("$k#quick")
                val t0 = now()
                val sv = survey(app, req)
                progress("survey ${label(req)}: ${sv?.let { "${it.total} total, ${it.quality} quality, ${it.cam} cam, best ${it.best}" } ?: "no answer"} in ${now() - t0} ms")
            }
            "scrape" -> {
                if (req == null) return progress("scrape needs --ei movie or --ei show")
                val k = key(req)
                links.remove(k); gathers.remove(k)?.value?.cancel(); gathers.remove("$k#quick")
                val t0 = now()
                progress("Play lookup for ${label(req)} (python ${if (ScraperEngine.ready()) "warm" else "cold"})")
                val f = find(app, req, 15_000, DebridPurpose.PLAY)
                progress("${label(req)} → ${f?.let { "${it.service.uppercase()} ${it.quality} ${size(it.sizeBytes)}" } ?: "nothing"} in ${now() - t0} ms")
                val job = gathers[k]?.value ?: return
                job.awaitDone(now() + 30_000)
                val ep = req.type == "episode"
                val fl = filters(app)
                val ranked = choose(parsedOf(job, if (ep) req.season else null, if (ep) req.episode else null), fl, ep, emptySet(), runtimeFor(app, req, fl), wantLang(app),
                    dub = AnimeDubs.rank(app, req))
                progress("${job.count} sources, ${ranked.size} pass your filters; best: " + ranked.take(5).joinToString(" | ") {
                    "${it.provider?.substringAfter(':')} ${it.c.quality}${if (it.pack) " pack" else ""} ${size(it.c.sizeBytes)} ${it.c.seeders}s"
                })
            }
            else -> progress("unknown command $what")
        }
    }

    // ---- the source list (Choose a Source, Search All Sources) ----

    private const val MAX_LISTED = 80

    /**
     * One torrent for the source list. [tags]: what the release name says beyond quality, for badges
     * ("Dolby Vision", "HDR", "HEVC", "Remux", "Atmos", "5.1"); [outside]: the user's filters would hide it.
     */
    class Listed(val c: Candidate, val tags: List<String>, val outside: Boolean, val cam: Boolean = false)

    /** Search All Sources: every filter off but Hide CAM Copies, discs, archives and what this stick can't play; best quality first. */
    private fun looseFilters(ctx: Context) = DreamFilters.Values(mapOf("quality.max" to "4k", "quality.min" to "sd", "hide.av1" to "false", "hide.3d" to "false",
        "hide.cam" to filters(ctx).hideCam.toString()), DeviceVideo.capIndex(ctx))

    /**
     * Blocking. The torrents the user's scrapers find for [req], filtered and ranked exactly as [find] ranks
     * them; [loose]: the user's filters off, what they'd hide marked [Listed.outside]. Torrents that failed to
     * play for this title come last. Empty when no account is linked, there's no IMDb id or no scraper
     * answered. Never throws.
     */
    fun sources(ctx: Context, req: PlaybackRequest, loose: Boolean, budgetMs: Long = 8_000): List<Listed> = try {
        val app = ctx.applicationContext
        val deadline = now() + budgetMs
        val ep = req.type == "episode"
        val s = req.season; val e = req.episode
        val acc = DebridAccounts.get(app)
        val imdb = if (req.trailer || acc?.any != true || (ep && (s == null || e == null)) || !Scrapers.active(app)) null else imdbFor(app, req)
        if (imdb == null) emptyList() else {
            // Everything the scrapers find for it (the lookup's own scrape when one ran: Change Source opens at once).
            val job = gather(app, req, imdb, play = true)
            job.awaitDone(deadline)
            val all = parsedOf(job, if (ep) s else null, if (ep) e else null)
            val f = filters(app)
            val rt = runtimeFor(app, req, f)
            val bad = failed[key(req)]?.keys.orEmpty()
            // Search All Sources lists CAM copies too (after the rest, marked outside the filters); the user's own list,
            // when nothing passes their filters, shows what a Play falls back to ([chooseRelaxed]).
            val want = wantLang(app)
            val resume = resuming(app, req)
            val dub = AnimeDubs.rank(app, req)
            val anime = dub != AnimeDubs.RANK_NONE
            recordNames(app, req, all, want)
            val (ranked, level) = if (loose) choose(all, looseFilters(app), ep, emptySet(), rt, want, RELAX_QUALITY, resume, dub) to 0
                else chooseRelaxed(all, f, ep, emptySet(), rt, want, true, resume, dub)
            ranked.sortedBy { it.c.hash in bad }.take(MAX_LISTED).map {
                // The audio badge ("English Dub", "Dual Audio", "Subtitled") after the picture and sound ones.
                val audio = want?.let { w -> AnimeDubs.badge(it.c.torrentName, w, anime) }
                Listed(it.c, tags(it.text) + listOfNotNull(audio),(loose || level > 0) && (!allowed(it, f, ep, rt) || (f.hideSd && it.c.quality == "SD")), it.cam)
            }
        }
    } catch (x: Exception) {
        Log.w(TAG, "Dream Core: source list for ${label(req)} unavailable (${x.message ?: x.javaClass.simpleName})"); emptyList()
    }

    /**
     * What the scrapers' quick check finds for [req], for the availability checker (Availability): counts and the best
     * release of each grade, nothing sent to any debrid service and no account needed. [quality]: releases
     * that aren't camera or screener grade; [best] / [bestCam] read like "1080p WEB-DL" / "HDTS 1080p".
     */
    class Survey(val total: Int, val quality: Int, val cam: Int, val best: String?, val bestCam: String?)

    private fun source(t: String) = when {
        has(t, "REMUX") -> "Remux"
        has(t, "BLURAY", "BLU-RAY", "BDRIP", "BRRIP") -> "BluRay"
        has(t, "WEB-DL", "WEBDL") -> "WEB-DL"
        has(t, "WEBRIP", "WEB-RIP") -> "WEBRip"
        has(t, "WEB") -> "WEB"
        has(t, "HDTV") -> "HDTV"
        has(t, "DVDRIP") -> "DVDRip"
        else -> null
    }

    private fun camTag(t: String) = listOf("HDCAM", "CAMRIP", "CAM", "HDTS", "TELESYNC", "TS", "HDTC", "TELECINE", "TC", "DVDSCR", "SCREENER", "SCR",
        "PDVD", "PREDVD", "PRE-DVD", "HQ PRE", "KINORIP").firstOrNull { has(t, it) } ?: "CAM"

    /** Blocking. The scrapers' answer for [req] as a [Survey] (kept with the scrape, [INDEX_TTL]); null when they can't answer. */
    internal fun survey(ctx: Context, req: PlaybackRequest, budgetMs: Long = 8_000): Survey? = try {
        val app = ctx.applicationContext
        val ep = req.type == "episode"
        val s = req.season; val e = req.episode
        if (!Scrapers.active(app)) throw IllegalStateException("no scraper sources added") // asked again later
        val imdb = if (ep && (s == null || e == null)) null else imdbFor(app, req)
        if (imdb == null) null else {
            // The quick check: a full scrape already run for it, else the two fastest providers with short limits.
            val job = gathers[key(req)]?.takeIf { it.fresh(INDEX_TTL) }?.value ?: gather(app, req, imdb, play = false, light = true)
            val until = now() + budgetMs
            if (job.light) job.awaitDone(until)
            // A full scrape (a page's pre-resolve) runs up to 20 s: the first real release answers the pill.
            else while (!job.done) {
                val v = job.version
                if (parsedOf(job, if (ep) s else null, if (ep) e else null).any { !it.cam }) break
                if (!job.await(v, until)) break
            }
            if (job.count == 0 && (!job.done || job.failedAll)) throw IllegalStateException("the scrapers didn't answer") // asked again later
            val all = parsedOf(job, if (ep) s else null, if (ep) e else null)
            recordNames(app, req, all, wantLang(app))
            val (cams, good) = all.partition { it.cam }
            // The best release the user would play: Dolby Vision / HDR ones they hide don't count; within a quality, DV, then HDR first.
            val fl = filters(app)
            fun range(p: Parsed) = when { p.dv && !fl.hideDv -> "DV"; p.hdr10 && !fl.hideHdr -> "HDR"; else -> null }
            val allowedGood = good.filter { !(fl.hideDv && it.dv) && !(fl.hideHdr && it.hdr10) }.ifEmpty { good }
            val top = allowedGood.minByOrNull { qi(it.c.quality) * 10_000_000 + (when (range(it)) { "DV" -> 0; "HDR" -> 1; else -> 2 }) * 1_000_000 - minOf(it.c.seeders, 999_999) }
            val topCam = cams.minByOrNull { qi(it.c.quality) * 1_000_000 - minOf(it.c.seeders, 999_999) }
            Survey(all.size, good.size, cams.size,
                top?.let { listOfNotNull(it.c.quality, source(it.text), range(it)).joinToString(" ") },
                topCam?.let { camTag(it.text) + " " + it.c.quality })
        }
    } catch (x: Exception) {
        Log.w(TAG, "Avail: no scraper answer for ${label(req)} (${x.message ?: x.javaClass.simpleName})"); null
    }

    private val CHANNELS = Regex("(?<![0-9])([57])[ .]1(?![0-9])")

    private fun tags(t: String): List<String> = listOfNotNull(
        when { has(t, "DV", "DOVI", "DOLBY VISION") -> "Dolby Vision"; has(t, "HDR10+") -> "HDR10+"; has(t, "HDR", "HDR10") -> "HDR"; else -> null },
        when { hevc(t) -> "HEVC"; has(t, "AV1") -> "AV1"; else -> null },
        if (has(t, "REMUX")) "Remux" else null,
        if (has(t, "ATMOS")) "Atmos" else CHANNELS.find(t)?.let { "${it.groupValues[1]}.1" },
    )

    /**
     * Blocking. [c] (one of [sources]) unlocked through the linked debrid services in turn, cached
     * torrents only (nothing is left downloading). Remembered as [req]'s link like [find]'s, so the
     * player knows it's a Dream Core stream and "Try Another Source" skips it if it fails.
     */
    fun resolveCandidate(ctx: Context, req: PlaybackRequest, c: Candidate, budgetMs: Long = 15_000): Found? =
        unlock(ctx.applicationContext, req, listOf(c), now() + budgetMs, "picked")

    /**
     * Blocking. Search All Sources: [find] with the user's filters off ([sources] loose), torrents that
     * failed here skipped. AV1 stays out when the user hides it (it can restart this stick). Null when
     * nothing loose is cached either.
     */
    fun findLoose(ctx: Context, req: PlaybackRequest, budgetMs: Long = 12_000): Found? {
        val app = ctx.applicationContext
        val deadline = now() + budgetMs
        val bad = failed[key(req)]?.keys.orEmpty()
        val fl = filters(app)
        val av1 = fl.hideAv1
        val listed = sources(app, req, loose = true, budgetMs = budgetMs / 2)
            .filter { it.c.hash !in bad && !(av1 && "AV1" in it.tags) }.take(TOP * 2)
        val top = listed.map { it.c }
        if (top.isEmpty()) { Log.w(TAG, "Dream Core: ${label(req)} → nothing (loose: the scrapers found nothing playable)"); return null }
        val f = unlock(app, req, top, deadline, "loose") ?: return null
        // A CAM copy (listed last, so nothing better was ready): PlayFlow says so, unless the viewer lets CAM copies in.
        val k = key(req)
        if (fl.hideCam && listed.firstOrNull { it.c.hash == f.hash }?.cam == true) relaxedLinks[k] = Relaxed(f.hash, cam = true) else relaxedLinks.remove(k)
        return f
    }

    /** The first of [cands] a linked service has cached, unlocked and remembered as [req]'s link; or null. */
    private fun unlock(app: Context, req: PlaybackRequest, cands: List<Candidate>, deadline: Long, why: String): Found? {
        val acc = DebridAccounts.get(app) ?: return null
        val t0 = now()
        ledger(app)
        val services = services(app, acc, DebridPurpose.PLAY)
        for (svc in services) {
            if (now() >= deadline) break
            val f = try { svc.resolve(cands, deadline) } catch (x: Exception) {
                Log.w(TAG, "Dream Core: ${svc.key.uppercase()} failed (${x.javaClass.simpleName})"); null
            }
            if (f == null) { if (svc is AllDebridService && adLimited(acc)) limitNote(app, services.drop(services.indexOf(svc) + 1), services.size > 1); continue }
            links[key(req)] = Stamped(f)
            Log.w(TAG, "Dream Core: ${label(req)} → ${f.service.uppercase()} ${f.quality} ${size(f.sizeBytes)} in ${secs(now() - t0)} ($why)")
            return f
        }
        Log.w(TAG, "Dream Core: ${label(req)} → nothing cached of ${cands.size} ($why)")
        return null
    }

    /**
     * Blocking. Which of [cands] are ready to stream right now (info hashes), for the source list's
     * "Ready" marks: AllDebrid's one-call answer when it's linked (what it added is removed again),
     * else null (the other services would need a call per torrent).
     */
    fun readyHashes(ctx: Context, cands: List<Candidate>, budgetMs: Long = 6_000): Set<String>? = try {
        ledger(ctx.applicationContext)
        DebridAccounts.get(ctx.applicationContext)?.allDebrid?.let {
            AllDebridService(it, DebridPurpose.LIST).readyHashes(cands.take(DebridPurpose.LIST.maxUploads), now() + budgetMs)
        }
    } catch (x: Exception) { null }

    // ---- which services a lookup asks ----

    /**
     * The order linked services are asked in: the user's ([DreamAccounts.order], Settings); by default
     * those whose cache check adds nothing to the account first (Premiumize, TorBox), then Real-Debrid,
     * then AllDebrid (its check is an upload that counts against its 1000-magnet limit).
     */
    private val ORDER = listOf("pm", "tb", "rd", "ad")

    private fun order(ctx: Context): List<String> = try { DreamAccounts.order(ctx.applicationContext) } catch (e: Exception) { ORDER }

    /**
     * The linked services in [order], for [purpose]: browsing asks none; a details page only the
     * ones whose cache check is free (Premiumize, TorBox: no service is special); the rest everything, except
     * AllDebrid while it is at its magnet limit ([AllDebridService.limited]).
     */
    private fun services(ctx: Context, acc: DebridAccounts.Accounts, purpose: DebridPurpose): List<DebridService> {
        val adds = purpose >= DebridPurpose.AHEAD
        val ad = acc.allDebrid?.takeIf { adds }
        val keys = order(ctx).filter { it in ORDER }.distinct().let { o -> o + ORDER.filter { it !in o } }
        val list = keys.mapNotNull<String, DebridService> { k ->
            when (k) {
                "ad" -> ad?.takeIf { !AllDebridService.limited(it) }?.let { AllDebridService(it, purpose) }
                "pm" -> acc.premiumize?.takeIf { purpose != DebridPurpose.BROWSE }?.let { PremiumizeService(it) }
                "tb" -> acc.torBox?.takeIf { purpose != DebridPurpose.BROWSE }?.let { TorBoxService(it) } // free check; adds only a cached copy (janitor)
                "rd" -> acc.realDebrid?.takeIf { adds }?.let { RealDebridService(acc) }
                else -> null
            }
        }
        if (ad != null && AllDebridService.limited(ad)) limitNote(ctx, list)
        return fastestFirst(ctx, list)
    }

    /**
     * [list] with the service that starts this user's streams clearly fastest ([startTime]: a typical first
     * frame at least [CLEARLY_FASTER_MS] sooner than the first service's, both measured) moved to the front:
     * when several services have the release ready, the quicker one plays it. Otherwise [list] as it is.
     */
    private fun fastestFirst(ctx: Context, list: List<DebridService>): List<DebridService> {
        if (list.size < 2) return list
        val first = startTime(ctx, list[0].key) ?: return list
        val best = list.drop(1).mapNotNull { s -> startTime(ctx, s.key)?.let { s to it } }.minByOrNull { it.second } ?: return list
        if (first - best.second < CLEARLY_FASTER_MS) return list
        Log.w(TAG, "Dream Core: asking ${best.first.key.uppercase()} first (starts in ~${secs(best.second)}, ${list[0].key.uppercase()} ~${secs(first)})")
        return listOf(best.first) + list.filter { it !== best.first }
    }

    private fun adLimited(acc: DebridAccounts.Accounts) = acc.allDebrid?.let { AllDebridService.limited(it) } == true

    /** The log line for AllDebrid at its limit; [services]: the ones asked instead; [others]: any other one is linked. */
    private fun limitNote(ctx: Context, services: List<DebridService>, others0: Boolean = services.any { it !is AllDebridService }) {
        // One notice a day (the notice list keeps it once per key).
        try { DreamNotices.accountLimit(ctx.applicationContext, "ad") } catch (e: Exception) {}
        val others = services.filter { it !is AllDebridService }.map {
            when (it.key) { "pm" -> "Premiumize"; "tb" -> "TorBox"; "rd" -> "Real-Debrid"; else -> it.key.uppercase() }
        }
        Log.w(TAG, if (others.isEmpty()) "AllDebrid at its magnet limit: ${if (others0) "the other services had nothing either" else "no other service linked"}" else "AllDebrid at its magnet limit: using ${others.joinToString("/")}")
    }

    /**
     * AllDebrid is at its magnet limit (remembered ~30 min): a play that found nothing elsewhere fails
     * at once with a calm message.
     */
    fun limitBlocked(ctx: Context): Boolean = DebridAccounts.get(ctx.applicationContext)?.let { adLimited(it) } == true

    @Volatile private var ledgerReady = false

    /**
     * Dream OS's record of what it added to the debrid accounts ([DebridLedger]) lives in app-private
     * prefs; links handed out in the last 4 h (maybe playing) are never cleaned up.
     */
    private fun ledger(app: Context) {
        if (ledgerReady) return
        synchronized(this) {
            if (ledgerReady) return
            val p = app.getSharedPreferences("dream_debrid_ledger", Context.MODE_PRIVATE)
            DebridLedger.store = object : DebridLedger.Store {
                override fun get(key: String) = p.getString(key, null)
                override fun put(key: String, value: String) { p.edit().putString(key, value).apply() }
            }
            DebridLedger.inUse = { links.values.filter { now() - it.at < 4 * 3_600_000L }.mapTo(HashSet()) { it.value.hash } }
            ledgerReady = true
        }
    }

    // ---- measured start times per service ----

    /** Links handed to the player (the url without '|'), with when: the first frame is timed from there. */
    private val handedAt = ConcurrentHashMap<String, Long>()

    /** [f] goes to the player now (a play, before its fast start): its first frame ([firstFrame]) is timed from here. */
    fun handing(f: Found) {
        handedAt[f.url] = now()
        if (handedAt.size > 20) handedAt.entries.removeAll { now() - it.value > 120_000 }
    }

    /**
     * Dream Player's first frame of [url] for [req]: the time since [handing] counts for the service that
     * unlocked it (a rolling average per service, recent starts weighing more), so [services] can ask the
     * one that starts this user's streams clearly faster first.
     */
    fun firstFrame(ctx: Context, req: PlaybackRequest, url: String) {
        val clean = url.substringBefore('|')
        val at = handedAt.remove(clean) ?: return
        val f = peek(req)?.takeIf { it.url == clean } ?: return
        val ms = now() - at
        if (ms <= 0 || ms > 60_000) return
        val p = prefs(ctx)
        val n = p.getInt("ttff_n_${f.service}", 0)
        val avg = if (n == 0) ms else (p.getLong("ttff_${f.service}", ms) * 2 + ms) / 3
        p.edit().putLong("ttff_${f.service}", avg).putInt("ttff_n_${f.service}", (n + 1).coerceAtMost(99)).apply()
        Log.w(TAG, "Dream Core: ${f.service.uppercase()} first frame in ${secs(ms)} (typical ${secs(avg)} over ${n + 1} plays)")
    }

    /** [service]'s typical time to the first frame for this user, once it has a few plays behind it; else null. */
    private fun startTime(ctx: Context, service: String): Long? {
        val p = prefs(ctx)
        return if (p.getInt("ttff_n_$service", 0) >= MIN_TIMED) p.getLong("ttff_$service", 0).takeIf { it > 0 } else null
    }

    private const val MIN_TIMED = 3
    /** How much faster (typical first frame) a service must start streams to be asked before the user's first one. */
    private const val CLEARLY_FASTER_MS = 1_500L

    private val CYRILLIC = Regex("[\\u0400-\\u04FF]")

    /**
     * Camera, telesync, telecine and screener releases, whatever the name calls them. TamilMV's "HQ PreDVD" and
     * "HQ Pre" are theater recordings sold as 1080p; "KinoRip" is the Czech word for the same.
     */
    internal val CAM_WORDS = arrayOf("CAM", "HDCAM", "HQCAM", "CAMRIP", "CAM-RIP", "TS", "HDTS", "TSRIP", "HDTSRIP", "TELESYNC",
        "TC", "HDTC", "TELECINE", "SCR", "DVDSCR", "HDTSCR", "SCREENER", "PDVD", "PREDVD", "PRE-DVD", "PREDVDRIP", "HQ PRE", "KINORIP")

    /** Not the movie at all: recap and review videos, betting-site ads posing as rips. */
    private val JUNK_WORDS = arrayOf("RECAP", "REVIEW", "1XBET", "4RABET", "4RABETRIP", "FLIXMOMO")

    /** [w] as a whole token in upper-case [t]. */
    private fun has(t: String, vararg w: String) = w.any { word ->
        tokens.getOrPut(word) { Regex("(?<![A-Z0-9])" + Regex.escape(word) + "(?![A-Z0-9])") }.containsMatchIn(t)
    }
    private val tokens = ConcurrentHashMap<String, Regex>()

    // ---- the user's filters ----

    /**
     * Dream OS's own source filters (DreamFilters: set in Settings › Sources & Quality).
     * With no quality cap set: no cap, but 1080p ranked first (see [tier]); AV1 / 3D / SD hidden.
     */
    private fun filters(ctx: Context): DreamFilters.Values = DreamFilters.current(ctx)

    private fun qi(q: String) = when (q) { "4K" -> 0; "1080p" -> 1; "720p" -> 2; else -> 3 }

    /** Rank of a quality: best allowed first; with no settings, 1080p before 4K (streams on any stick, any Wi-Fi). */
    private fun tier(q: String, f: DreamFilters.Values) = if (f.known) qi(q) else when (q) { "1080p" -> 0; "4K" -> 1; "720p" -> 2; else -> 3 }

    private fun hdr(t: String) = has(t, "HDR", "HDR10", "HDR10+", "DV", "DOVI", "DOLBY VISION")
    private fun hevc(t: String) = has(t, "HEVC", "X265", "H265", "H.265")
    private val DISC = Regex("(?i)\\.(iso|img|rar|zip|7z|exe)$")

    /**
     * Whether the user would play [p] (their filters; no CAM/TS/SCR while Hide CAM Copies is on; never discs, archives, or episodes over 40 GB).
     * Size limits: a size range in GB, or "fit my internet speed" (the file's average bitrate over [runtimeSec],
     * the title's running time, must stay under the speed). Season packs carry no per-episode size: sizes don't apply.
     */
    private fun allowed(p: Parsed, f: DreamFilters.Values, ep: Boolean, runtimeSec: Int = 0, relax: Int = 0): Boolean {
        val c = p.c
        if (p.cam && f.hideCam && relax < RELAX_QUALITY) return false
        if (c.fileName != null) { if (!DebridFiles.isVideo(c.fileName)) return false }
        else if (p.disc) return false
        if (ep && c.sizeBytes > 40 * GB) return false
        // Above what this stick can decode never plays (a 4K file on the Fire TV Stick HD), so no relaxing lets it in.
        if (qi(c.quality) < f.deviceCap) return false
        if (relax < RELAX_QUALITY && qi(c.quality) < f.cap) return false
        if (relax < RELAX_SIZE) {
            val r = f.range(ep)
            if (r != null && c.sizeBytes > 0 && (c.sizeBytes.toDouble() / GB) !in r) return false
            val mbps = f.speedMbps(ep)
            if (mbps != null && runtimeSec > 0 && c.sizeBytes > 0 && c.sizeBytes * 8.0 / runtimeSec / 1_000_000.0 > mbps) return false
        }
        // Relaxed or not, what this TV can't show right stays out: HDR / Dolby Vision / 3D the user hides, and AV1 (crashes the stick).
        return !((relax < RELAX_FORMAT && f.hideHevc && p.hevc) ||
            (f.hideHdr && p.hdr10) ||
            (f.hideDv && p.dv) ||
            (f.hideAv1 && p.av1) ||
            (relax < RELAX_FORMAT && f.hideAvc && p.avc) ||
            (f.hide3d && p.threeD) ||
            (relax < RELAX_QUALITY && f.hideUpscaled && p.upscaled))
    }

    /** How far a Play may set the user's filters aside when nothing passes them: size limits, then quality / CAM, then codec choices. */
    private const val RELAX_SIZE = 1
    private const val RELAX_QUALITY = 2
    private const val RELAX_FORMAT = 3

    /**
     * [choose] at the strictest level that leaves something: the user's filters as set, then without the size
     * limits, then without the quality limits (CAM copies allowed, ranked after any real release), then without
     * the HEVC / AVC choices. [relaxOk] false (anything but a Play): the user's filters only. The level used is second.
     */
    private fun chooseRelaxed(all: List<Parsed>, f: DreamFilters.Values, ep: Boolean, skip: Set<String>, runtimeSec: Int,
                              want: String?, relaxOk: Boolean, resume: Boolean = false, dub: Int = AnimeDubs.RANK_NONE): Pair<List<Parsed>, Int> {
        for (level in 0..(if (relaxOk) RELAX_FORMAT else 0)) {
            val kept = choose(all, f, ep, skip, runtimeSec, want, level, resume, dub)
            if (kept.isNotEmpty()) return kept to level
        }
        return emptyList<Parsed>() to 0
    }

    /**
     * A link was found for a real release (not a CAM copy): its availability pill says Available Now at once
     * instead of waiting for its next check.
     */
    private fun noteAvailable(ctx: Context, k: String, p: Parsed) {
        if (p.cam) return
        try {
            if (Availability.peek(ctx, k)?.status == Availability.Status.AVAILABLE) return
            val best = listOfNotNull(p.c.quality, source(p.text)).joinToString(" ")
            Availability.record(ctx, k, Availability.Entry(Availability.Status.AVAILABLE, best, null, System.currentTimeMillis()))
        } catch (e: Exception) { Log.w(TAG, "Dream Core: availability note failed (${e.javaClass.simpleName})") }
    }

    /** After a link was found: when the filters were relaxed ([level] > 0), the log line and the note PlayFlow shows. */
    private fun noteRelaxed(req: PlaybackRequest, k: String, found: Found, kept: List<Parsed>, level: Int, f: DreamFilters.Values) {
        if (level <= 0) { relaxedLinks.remove(k); return }
        val p = kept.firstOrNull { it.c.hash == found.hash }
        val cam = p != null && p.cam
        // The CAM note only for a viewer who hides CAM copies; one who lets them in hears the general note.
        relaxedLinks[k] = Relaxed(found.hash, cam && f.hideCam)
        val what = p?.let { if (cam) camTag(it.text) + " " + it.c.quality else listOfNotNull(it.c.quality, source(it.text)).joinToString(" ") } ?: found.quality
        val relaxed = listOf("size", "quality", "formats").take(level).reversed().joinToString(", ")
        Log.w(TAG, "Dream Core: ${label(req)} nothing passes your filters, playing best available ($what) [relaxed: $relaxed]")
    }

    private fun upscaled(t: String) = has(t, "UPSCALE", "UPSCALED", "AI UPSCALED", "AI-UPSCALED", "AIUS")

    /**
     * [all] minus failed torrents and what the filters drop, best first: a film collection (which film?)
     * and files twice the Wi-Fi-friendly size go last; then quality tier; within one, Wi-Fi-friendly size
     * (movies up to 25 GB, episodes 6 GB), then the user's HEVC / HDR preference and clean (not dubbed, not
     * upscaled) releases, then an MKV before an MP4, then seeders. [resume]: the MP4 penalty comes right
     * after the quality tier (a resume seeks into the file, and an MP4's index must be read first).
     * [dub] ([AnimeDubs.rank]): a title in another language with Prefer Dubs on puts copies that say they carry [want] first
     * within a quality tier (never above a better tier, a real release or the filters); off, anime gets no
     * language preference, so the clean-release rule keeps undubbed copies ahead.
     */
    private fun choose(all: List<Parsed>, f: DreamFilters.Values, ep: Boolean, skip: Set<String>, runtimeSec: Int = 0, want: String? = null,
                       relax: Int = 0, resume: Boolean = false, dub: Int = AnimeDubs.RANK_NONE): List<Parsed> {
        val pass = all.filter { it.c.hash !in skip && allowed(it, f, ep, runtimeSec, relax) }
        // The provider's own rule: SD goes only when something better exists (with CAM copies let in: a real release, not a CAM).
        val camsIn = !f.hideCam || relax >= RELAX_QUALITY
        // Hide CAM Copies off: a CAM ranks like any copy, after the real releases of its quality; on (relaxed): after every real release.
        val camsLast = f.hideCam && camsIn
        val list = if (f.hideSd && pass.any { it.c.quality != "SD" && !(camsIn && it.cam) }) pass.filter { it.c.quality != "SD" } else pass
        val heavy = if (ep) 6 * GB else 25 * GB
        val preferHdr = f.preferHdr
        val preferHevc = f.preferHevc
        // A preferred audio language other than English (Dream Player's): releases that say it (or MULTI) first.
        val other = want != null && want != "en" && dub != AnimeDubs.RANK_PLAIN
        val dubFirst = dub == AnimeDubs.RANK_DUB && want != null
        // A copy carries the dub when its name says so or Dream Player heard the language in it before (LangPills.heard).
        fun dubMiss(p: Parsed) = dubFirst && p.audio(want!!) != AnimeDubs.Audio.DUB && !LangPills.heardIn(p.c.hash, want)
        fun langMiss(p: Parsed) = if (!other) 0 else if (p.lang == want || has(p.text, "MULTI", "DUAL") || LangPills.heardIn(p.c.hash, want!!)) 0 else 1
        // A film from a scraper never names its file: a collection (which film would play?) goes last.
        fun sink(p: Parsed) = p.c.sizeBytes > 2 * heavy || (!ep && p.collection)
        fun penalty(p: Parsed) = (if (p.dubbed && !other && !dubFirst) 2 else 0) + (if (p.upscaled) 2 else 0) +
            (if (preferHdr && !p.anyHdr) 1 else 0) + (if (preferHevc && !p.hevc) 1 else 0)
        // Otherwise equal, an MKV before an MP4: a feature-length MP4's index (the moov, ~10 MB, often at the end of
        // a BluRay encode) costs Dream Player its download plus ~1.5 s of parsing before the first frame (measured
        // 2026-10-25: 4.2 s to the first frame against 2.2 s for an MKV; a resume 8-10 s, 2026-10-26); an MKV's index
        // is tens of KB. Resuming, it outweighs size and preferences (the seek can't start without the index).
        // CAM copies let in (relaxed): every real release first, then the best CAM (quality, size sanity, seeders).
        return list.sortedWith(compareBy<Parsed>({ sink(it) }, { camsLast && it.cam }, { langMiss(it) }, { tier(it.c.quality, f) }, { it.cam }, { dubMiss(it) },
            { resume && it.mp4 }, { it.pack }, { it.c.sizeBytes > heavy }, { penalty(it) }, { it.mp4 }, { -it.c.seeders }))
    }

    /**
     * An MP4 (or M4V) by what's known up front: the file or torrent name, an "MP4" token in the release name,
     * or YTS, whose releases are always MP4.
     */
    private fun mp4(p: Parsed): Boolean {
        val name = (p.c.fileName ?: p.c.torrentName).lowercase(java.util.Locale.US)
        return name.endsWith(".mp4") || name.endsWith(".m4v") || has(p.text, "MP4") ||
            p.provider?.substringAfter(':')?.lowercase(java.util.Locale.US)?.startsWith("yts") == true || has(p.text, "YTS", "YTS.MX", "YIFY")
    }

    /** [req] resumes (its own start, or Continue Watching has a spot for it): an MP4 costs more. */
    private fun resuming(ctx: Context, req: PlaybackRequest): Boolean =
        req.startMs > 0 || try { PlayFlow.resumePosition(ctx, req) > 0 } catch (e: Exception) { false }

    // ---- log formatting ----

    private fun secs(ms: Long) = String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)

    private fun size(b: Long) = when {
        b <= 0 -> "size unknown"
        b < GB -> "${b / (1024 * 1024)} MB"
        else -> String.format(java.util.Locale.US, "%.1f GB", b.toDouble() / GB)
    }
}
