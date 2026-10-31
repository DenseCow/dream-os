package com.dreamos.desktop.core.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Dream Core Resolver: Play to a direct debrid HTTPS link in about a second (fire-home NativeResolver.kt, with the
 * iPhone's Instant Play pass: the debrid race, alternates, look-aheads that end early and bring a joining Play's
 * budget, source learning). It is the only resolver: what it can't find, nothing plays.
 *
 * The viewer's scrapers ([SourceEngine]) are asked for the title's torrents, which stream in as each provider answers.
 * The viewer's filters drop what they wouldn't play, the rest is ranked ([Ranking]) and the top [TOP] go to every
 * linked debrid service at once ([race]); the first with a link wins (the viewer's order breaks near-ties), the other
 * ready links become the player's next tries ([nextLink]). A found link is remembered [LINK_TTL].
 *
 * What a lookup may add to an account depends on why it runs ([DebridPurpose]): a card the pointer rests on only runs
 * the scrapers' quick check; a title page scrapes, ranks and asks only the free cache checks (Premiumize, TorBox); a
 * real Play, the next episode and the source list's top rows ask every service.
 *
 * Memory bounds (worst case): [MAX_GATHERS] kept scrapes (a few hundred sources each, ~0.5 MB), 100 links, 30 titles
 * of alternates (6 each), 200 titles of failed hashes.
 */
class DreamCore(
    val engine: SourceEngine,
    val kit: DebridKit,
    val filters: SourceFilters,
    val memory: SourceMemory,
    val langs: LanguagePillsStore,
    val hooks: PlaybackHooks,
    private val store: KeyValueStore,
) {
    companion object {
        /** How many ranked candidates the services see: enough to almost always hit a cached one. */
        const val TOP = 10
        const val LINK_TTL = 40 * 60_000L
        /** A scrape (and so the ranking) for a title: long enough for a title page to be read before Play. */
        const val INDEX_TTL = 30 * 60_000L
        const val FAILED_TTL = 60 * 60_000L
        /** How long Play waits for more sources once the first arrived, with none / one of the best allowed grade in. */
        const val GRACE_MS = 1_200L
        const val GRACE_GOOD_MS = 250L
        /** The same for a look-ahead (nobody waiting): longer, for a better ranking, never the whole 20 s scrape. */
        const val AHEAD_GRACE_MS = 5_000L
        const val AHEAD_GRACE_GOOD_MS = 2_000L
        /** How often a look-ahead's wait looks for a Play that joined it. */
        const val JOIN_CHECK_MS = 250L
        /** Time kept for the debrid step before a Play's budget ends. */
        const val DEBRID_RESERVE_MS = 2_500L
        /** Links unlocked side by side with a Play's: the player's next tries. */
        const val RACE_LINKS = 3
        /** A later service in the viewer's order that answers within this of an earlier one still answering doesn't win yet. */
        const val TIE_MS = 250L
        /** A service whose check adds to the account joins the race this much later when a free check is in it. */
        const val UPLOAD_DELAY_MS = 500L
        const val MAX_GATHERS = 40
        const val MAX_LISTED = 80
        const val MAX_ALTERNATES = 6
        /**
         * A lookup still in flight this long after its deadline is stuck (every wait in it ends by the deadline, the
         * race 1.5 s after): it is dropped, never joined, and the next caller starts a fresh one.
         */
        const val STALE_MS = 5_000L
        /** A scrape not done after this long is stuck (the engine ends its own by 20 s plus a 30 s slot wait and 15 s). */
        const val SCRAPE_STUCK_MS = 90_000L
        private const val MIN_TIMED = 3
        private const val CLEARLY_FASTER_MS = 1_500L

        fun secs(ms: Long) = String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)
        fun size(b: Long) = when {
            b <= 0 -> "size unknown"
            b < (1L shl 30) -> "${b / (1024 * 1024)} MB"
            else -> String.format(java.util.Locale.US, "%.1f GB", b.toDouble() / (1L shl 30))
        }
    }

    private val clock = kit.clock
    private val scope = kit.scope
    private fun now() = clock.monotonicMs()

    val adState = AllDebridState()
    val tbState = TorBoxState()
    val rdState = RealDebridState()

    private class Stamped<T>(val value: T, val at: Long)

    /** One lookup under way. [purpose] may be raised and [deadline] moved by a caller that joins it. */
    private class Lookup(val target: PlayTarget, @Volatile var purpose: DebridPurpose, @Volatile var deadline: Long, val startedAt: Long) {
        val result = CompletableDeferred<Stream?>()
        /** The purpose the debrid step used (null: it never got there). */
        @Volatile var ran: DebridPurpose? = null
        /** It was cancelled or dropped ([abandon], [STALE_MS]) before it answered: a caller that joined it looks again. */
        @Volatile var dropped = false
        /** Where it is, for the log line when it is dropped. */
        @Volatile var phase = "starting"
    }

    /** A scrape Dream Core reads, with when it started and when its first source arrived (Dream Core's clock). */
    inner class Gather(val job: ScrapeJob, val startedAt: Long) {
        @Volatile var firstAt: Long? = null
        init {
            scope.launch {
                val s = job.state.first { it.count > 0 || it.done }
                if (s.count > 0) firstAt = now()
            }
        }
        val state get() = job.state.value
        val done get() = state.done
        val count get() = state.count
        val version get() = state.version
        /** The scrapers looked (some provider answered), as opposed to no answer at all. */
        val answered get() = state.answered > 0

        /** Until a version other than [seen] exists, the scrape ends, or [until]. */
        suspend fun await(seen: Int, until: Long) {
            val ms = until - now()
            if (ms <= 0) return
            withTimeoutOrNull(ms) { job.state.first { it.version != seen || it.done } }
        }
        suspend fun awaitDone(until: Long) { val ms = until - now(); if (ms > 0) withTimeoutOrNull(ms) { job.state.first { it.done } } }
    }

    private val lock = Any()
    private val links = HashMap<String, Stamped<Stream>>()
    private val alternates = HashMap<String, Stamped<MutableList<Stream>>>()
    private val failed = HashMap<String, HashMap<String, Long>>()
    private val failedUrls = HashMap<String, MutableSet<String>>()
    private val misses = HashMap<String, Miss>()
    private val relaxedLinks = HashMap<String, Pair<String, Boolean>>()
    private val gathers = HashMap<String, Stamped<Gather>>()
    private val inFlight = HashMap<String, Lookup>()
    private val imdbs = HashMap<String, String>()
    private val handedAt = HashMap<String, Long>()

    /** The stream head start (set by [Playback.create]; null: links are opened directly). */
    @Volatile var streamCache: StreamCache? = null

    /** [s]'s head (and the resume window where the history says [t] starts) to disk, ahead of Play. */
    fun warmStream(t: PlayTarget, s: Stream) {
        val c = streamCache ?: return
        c.warm(s, hooks.resumeMs(t), t.runtimeMin * 60_000L, t.label)
    }

    /** Set by the availability checker: a link was found for a real release of a title (its pill says Available Now). */
    @Volatile var foundListener: ((PlayTarget, String) -> Unit)? = null

    init {
        kit.ledger.inUse = { val n = now(); synchronized(lock) { links.values.filter { n - it.at < 4 * 3_600_000L }.mapTo(HashSet()) { it.value.hash } } }
        engine.onChanged { scrapersChanged() }
    }

    // ---- public API ----

    /** A debrid account is linked (without one nothing can play). */
    fun available() = kit.accounts.any

    /** The last Play lookup's [Miss] for [t], or null (found, or the reason isn't certain). */
    fun miss(t: PlayTarget): Miss? = synchronized(lock) { misses[t.key] }

    /** [s] was played with the viewer's filters set aside: true it's a CAM copy, false the best available; null not relaxed. */
    fun relaxed(t: PlayTarget, s: Stream): Boolean? = synchronized(lock) { relaxedLinks[t.key]?.takeIf { it.first == s.hash }?.second }

    /** AllDebrid is resting at its magnet limit. */
    fun limitBlocked() = kit.accounts.token(DebridServiceId.ALLDEBRID)?.let { AllDebridService.limited(kit.ledger, it, clock.wallMs()) } == true

    /** AllDebrid waits for the owner to approve this network (AUTH_BLOCKED), and it's the only service that could play. */
    fun allDebridBlocked() = adState.blockedScope.isNotEmpty() && now() - adState.blockedAt < 10 * 60_000L

    /** The remembered link for [t], no network; null if none or expired. */
    fun peek(t: PlayTarget): Stream? = synchronized(lock) { links[t.key]?.takeIf { now() - it.at < LINK_TTL }?.value }

    /**
     * Forget a link that failed to play: its torrent is skipped for this title for [FAILED_TTL] and its URL never handed
     * out again; what its service added for it goes; the failure counts against its release's traits.
     */
    fun forget(t: PlayTarget, s: Stream? = null) {
        val k = t.key
        val f = synchronized(lock) {
            val cur = links[k]?.value
            val f = s ?: cur ?: return
            if (cur == null || cur.url == f.url) links.remove(k)
            failed.getOrPut(k) { HashMap() }[f.hash] = now()
            failedUrls.getOrPut(k) { HashSet() } += f.url
            if (failed.size > 200) failed.keys.take(failed.size - 200).forEach { failed.remove(it); failedUrls.remove(it) }
            f
        }
        PlaybackLog.w("Dream Core: ${t.label}: ${f.service.key.uppercase()} ${f.quality.label} ${size(f.sizeBytes)} didn't play; skipped from now on")
        memory.record(traits(k, f), ok = false, ms = null)
        streamCache?.forget(f.url)
        service(f.service)?.discard(listOf(f.hash))
    }

    /** The next ready link for [t] after its link failed ([forget] first), remembered as its link; null when none is left. */
    fun nextLink(t: PlayTarget): Stream? {
        val k = t.key
        val (f, left) = synchronized(lock) {
            val a = alternates[k]?.takeIf { now() - it.at < LINK_TTL } ?: run { alternates.remove(k); return null }
            val bad = failedUrls[k].orEmpty()
            val cur = links[k]?.value?.url
            while (a.value.isNotEmpty()) {
                val f = a.value.removeAt(0)
                if (f.url in bad || f.url == cur) continue
                if (a.value.isEmpty()) alternates.remove(k)
                links[k] = Stamped(f, now())
                return@synchronized f to a.value.size
            }
            alternates.remove(k)
            return null
        }
        PlaybackLog.w("Dream Core: ${t.label} → next source ${f.service.key.uppercase()} ${f.quality.label} ${size(f.sizeBytes)} ($left more ready)")
        return f
    }

    /** Whether [nextLink] has one (nothing taken). */
    fun hasAlternate(t: PlayTarget): Boolean = synchronized(lock) {
        val a = alternates[t.key]?.takeIf { now() - it.at < LINK_TTL } ?: return false
        val bad = failedUrls[t.key].orEmpty()
        val cur = links[t.key]?.value?.url
        a.value.any { it.url !in bad && it.url != cur }
    }

    /** The ready alternates for [t] (no network), for the player's PlayRequest. */
    fun alternatesFor(t: PlayTarget): List<Stream> = synchronized(lock) {
        val bad = failedUrls[t.key].orEmpty()
        val cur = links[t.key]?.value?.url
        alternates[t.key]?.takeIf { now() - it.at < LINK_TTL }?.value?.filter { it.url !in bad && it.url != cur }.orEmpty()
    }

    private fun addAlternates(k: String, more: List<Stream>) {
        if (more.isEmpty()) return
        synchronized(lock) {
            val a = alternates[k]?.takeIf { now() - it.at < LINK_TTL }?.value ?: ArrayList()
            val cur = links[k]?.value?.url
            // One entry per link: the same torrent from two services is two copies, each a try of its own.
            for (l in more) if (l.url != cur && a.none { it.url == l.url }) a += l
            while (a.size > MAX_ALTERNATES) a.removeAt(a.size - 1)
            if (a.isEmpty()) alternates.remove(k) else alternates[k] = Stamped(a, now())
            if (alternates.size > 30) alternates.entries.removeAll { now() - it.value.at >= LINK_TTL }
        }
    }

    /** The winner's first frame is up: alternates whose service added something (AllDebrid, TorBox) are dropped and cleaned. */
    private fun settleAlternates(k: String, winner: Stream) {
        val drop = synchronized(lock) {
            val a = alternates[k] ?: return
            val d = a.value.filter { it.service == DebridServiceId.ALLDEBRID || it.service == DebridServiceId.TORBOX || it.hash == winner.hash }
            a.value.removeAll(d.toSet())
            if (a.value.isEmpty()) alternates.remove(k)
            d.filter { it.hash != winner.hash }
        }
        drop.groupBy { it.service }.forEach { (svc, g) -> service(svc)?.discard(g.map { it.hash }) }
    }

    private fun traits(k: String, s: Stream): List<String> {
        val item = synchronized(lock) { gathers[k]?.value }?.state?.sources?.firstOrNull { it.hash.equals(s.hash, true) }
        return if (item != null) SourceMemory.traits(item.name, item.fileName ?: s.fileName, item.provider.ifEmpty { null },
            if (item.pack) 0 else if (s.sizeBytes > 0) s.sizeBytes else item.sizeBytes)
        else SourceMemory.traits(s.releaseName.ifEmpty { s.fileName }, s.fileName, null, s.sizeBytes)
    }

    // ---- find ----

    private class Box(val v: Stream?)

    /**
     * Best ready stream for [t], or null (no account, no scrapers, nothing cached, budget over). Concurrent calls for the
     * same title / episode share ONE lookup; every caller waits at most its own [budgetMs]; a lookup that outlives its
     * caller still finishes and remembers what it finds. A Play joining a look-ahead's lookup raises it to a Play and
     * brings its own budget. Never throws.
     */
    suspend fun find(t: PlayTarget, budgetMs: Long = 8_000, purpose: DebridPurpose = DebridPurpose.PLAY, startMs: Long? = null): Stream? {
        val k = t.key
        val deadline = now() + budgetMs
        if (purpose == DebridPurpose.PLAY) synchronized(lock) { misses.remove(k) }
        peek(t)?.let { return it }
        if (purpose == DebridPurpose.PLAY) preempt(k)
        var res: Stream? = null
        for (attempt in 0..1) {
            val mine = Lookup(t, purpose, deadline, now())
            var stale: Lookup? = null
            val (l, started) = synchronized(lock) {
                // One past its deadline is stuck: never joined (a Play, Try Again or look-ahead after it starts afresh).
                inFlight[k]?.takeIf { now() > it.deadline + STALE_MS }?.let { stale = it; inFlight.remove(k) }
                inFlight[k]?.let { it to false } ?: (mine.also { inFlight[k] = it } to true)
            }
            stale?.let { drop(it, "stuck") }
            if (started) {
                val job = scope.launch {
                    val r = try { lookup(t, k, l, startMs) } catch (e: CancellationException) { l.dropped = true; null } catch (e: Exception) {
                        PlaybackLog.w("Dream Core: ${t.label} → failed (${e.javaClass.simpleName}: ${e.message?.take(120)})"); null
                    }
                    l.result.complete(r)
                }
                // However it ends (an answer, an error, cancelled before it even ran), its callers hear and the entry goes.
                job.invokeOnCompletion { cause ->
                    if (cause != null && !l.result.isCompleted) l.dropped = true
                    l.result.complete(null)
                    synchronized(lock) { if (inFlight[k] === l) inFlight.remove(k) }
                }
            }
            val raised = l.purpose <= purpose
            if (l.purpose < purpose) l.purpose = purpose
            if (!started && purpose == DebridPurpose.PLAY) { l.deadline = deadline; gatherOf(k)?.job?.promote() }
            else if (!started && raised && l.deadline < deadline) l.deadline = deadline
            val w = withTimeoutOrNull((deadline - now()).coerceAtLeast(1)) { Box(l.result.await()) }
            if (w == null) {
                PlaybackLog.w("Dream Core: ${t.label} → nothing yet (budget of ${secs(budgetMs)} over; the lookup goes on and remembers what it finds)")
                return null
            }
            res = w.v
            // The joined lookup was cancelled or dropped before it answered: this caller looks again, fresh.
            if (res == null && l !== mine && l.dropped) continue
            val ran = l.ran
            if (res != null || l === mine || ran == null || ran >= purpose) break
            // The joined lookup asked fewer services (the next episode's): this one does its own debrid step.
            synchronized(lock) { if (inFlight[k] === l) inFlight.remove(k); misses.remove(k) }
        }
        return res
    }

    /**
     * The curtain gave up on [t], or the viewer chose Try Again: the lookup in flight for it is dropped (it still
     * finishes on its own and remembers what it finds; nobody joins it), so the next Play starts a fresh one.
     */
    fun abandon(t: PlayTarget) {
        val l = synchronized(lock) { inFlight.remove(t.key) } ?: return
        drop(l, "given up")
    }

    private fun drop(l: Lookup, why: String) {
        l.dropped = true
        l.result.complete(null)
        PlaybackLog.w("Dream Core: ${l.target.label}: dropped the lookup in flight ($why, ${secs(now() - l.startedAt)} old, ${l.phase}); the next one starts fresh")
    }

    /** A real Play for [k] starts: look-aheads for other titles give up their scrapes at once. */
    private fun preempt(k: String) {
        val others = synchronized(lock) {
            inFlight.filter { it.key != k && it.value.purpose <= DebridPurpose.PAGE }.map { (_, l) -> l to gathers.remove(l.target.key)?.value }
        }
        for ((l, g) in others) {
            // Off the Play's own thread: a cancel talks to the scrapers' helper, which must never hold up a Play.
            g?.job?.let { j -> scope.launch { j.cancel() } }
            PlaybackLog.i("Instant Play: dropped the lookup for ${l.target.label} (a Play starts)")
        }
    }

    private suspend fun imdbFor(t: PlayTarget): String? {
        t.imdb?.takeIf { it.startsWith("tt") }?.let { return it }
        val ik = (if (t.isEpisode) "tv:" else "movie:") + t.tmdbId
        synchronized(lock) { imdbs[ik] }?.let { return it }
        return hooks.imdb(t)?.takeIf { it.startsWith("tt") }?.also { synchronized(lock) { imdbs[ik] = it; if (imdbs.size > 2000) imdbs.clear() } }
    }

    private fun none(t: PlayTarget, k: String, l: Lookup, why: String, miss: Miss? = null): Stream? {
        if (miss != null && l.purpose == DebridPurpose.PLAY) synchronized(lock) { misses[k] = miss }
        if (l.purpose <= DebridPurpose.PAGE) PlaybackLog.i("Instant Play: ${t.label}: $why") else PlaybackLog.w("Dream Core: ${t.label} → nothing ($why)")
        return null
    }

    private suspend fun lookup(t: PlayTarget, k: String, l: Lookup, startMs: Long?): Stream? {
        val t0 = now()
        if (!kit.accounts.any) return none(t, k, l, "no debrid account linked")
        if (t.isEpisode && (t.season == null || t.episode == null)) return none(t, k, l, "no season/episode")
        l.phase = "finding its IMDb id"
        val imdb = imdbFor(t) ?: return none(t, k, l, "no IMDb id")
        if (!engine.active()) return none(t, k, l, "no scraper sources added", Miss.NOTHING)
        l.phase = "starting the scrape"
        if (l.purpose == DebridPurpose.BROWSE) { gather(t, imdb, ScrapeMode.QUICK); return null }
        return scraperLookup(t, k, imdb, l, t0, startMs)
    }

    // ---- scrapes ----

    private fun gatherOf(k: String) = synchronized(lock) { gathers[k]?.takeIf { now() - it.at < INDEX_TTL }?.value }

    /**
     * The scrape for [t]: one running or done in the last [INDEX_TTL] (an empty one is redone after two minutes; one the
     * scrapers never answered at once), else a new one. QUICK: the pills' quick check, kept under its own key.
     */
    private suspend fun gather(t: PlayTarget, imdb: String, mode: ScrapeMode): Gather {
        val k = t.key + if (mode == ScrapeMode.QUICK) "#quick" else ""
        fun usable(g: Stamped<Gather>?) = g != null && now() - g.at < INDEX_TTL && !g.value.state.cancelled &&
            !(g.value.done && g.value.count == 0 && (!g.value.answered || now() - g.at >= 2 * 60_000L)) &&
            !(!g.value.done && now() - g.value.startedAt > SCRAPE_STUCK_MS)
        // A scrape that never ended is stuck: dropped and stopped (here, in the lookup, never on a Play's own thread).
        synchronized(lock) { gathers[k]?.takeIf { !it.value.done && now() - it.value.startedAt > SCRAPE_STUCK_MS }?.also { gathers.remove(k) }?.value }?.let { g ->
            PlaybackLog.w("Dream Core: ${t.label}: dropped a scrape stuck for ${secs(now() - g.startedAt)}; a fresh one starts")
            g.job.cancel()
        }
        synchronized(lock) { gathers[k]?.takeIf { usable(it) }?.value }?.let { g ->
            if (mode == ScrapeMode.PLAY && !g.done) g.job.promote()
            return g
        }
        val q = hooks.scrapeQuery(t, imdb, mode == ScrapeMode.QUICK, if (mode == ScrapeMode.PLAY) 500 else 1_500)
        synchronized(lock) {
            gathers[k]?.takeIf { usable(it) }?.let { return it.value }
            val g = Gather(engine.start(q, mode), now())
            gathers[k] = Stamped(g, now())
            if (gathers.size > MAX_GATHERS) gathers.entries.removeAll { now() - it.value.at >= INDEX_TTL }
            if (gathers.size > MAX_GATHERS) gathers.entries.filter { it.value.value.done }.sortedBy { it.value.at }
                .take(gathers.size - MAX_GATHERS).forEach { gathers.remove(it.key) }
            return g
        }
    }

    /** Repositories or providers changed: earlier scrapes no longer say what the viewer would get. */
    fun scrapersChanged() {
        val old = synchronized(lock) { gathers.values.map { it.value }.also { gathers.clear() } }
        old.filter { !it.done }.forEach { it.job.cancel() }
    }

    /** Dream Player started: only the newest few scrapes stay (the playing title's among them, Change Source reads it). */
    fun trimForPlayback() = synchronized(lock) {
        val old = gathers.entries.filter { it.value.value.done }.sortedBy { it.value.at }
        old.take((gathers.size - 4).coerceAtLeast(0)).forEach { gathers.remove(it.key) }
    }

    private fun parsed(g: Gather?, season: Int?, episode: Int?) = g?.state?.sources.orEmpty().mapNotNull { Ranking.parse(it, season, episode) }

    /** The full scrape plus what the pills' quick check found (the card said Available Now from those: Play counts them). */
    private fun merged(g: Gather, k: String, season: Int?, episode: Int?): List<Ranking.Parsed> {
        val a = parsed(g, season, episode)
        val quick = synchronized(lock) { gathers["$k#quick"]?.takeIf { now() - it.at < INDEX_TTL }?.value }
        if (quick == null || quick === g) return a
        val seen = a.mapTo(HashSet()) { it.s.hash }
        return a + parsed(quick, season, episode).filter { seen.add(it.s.hash) }
    }

    private fun context(t: PlayTarget, f: SourceFilters.Values, skip: Set<String>, resume: Boolean): Ranking.Context {
        val want = hooks.audioLang()
        return Ranking.Context(f, t.isEpisode, skip, t.runtimeMin * 60, want, resume, Dubs.rank(t, want, hooks.preferDubs()),
            memory.snapshot(), langs::heardIn)
    }

    private fun skipSet(k: String): Set<String> = synchronized(lock) {
        val m = failed[k] ?: return emptySet()
        m.entries.removeAll { now() - it.value > FAILED_TTL }
        m.keys.toSet()
    }

    /** The release names of [t]'s good copies for the language pills: a dub only counts when Play could pick it. */
    private fun recordNames(t: PlayTarget, all: List<Ranking.Parsed>) {
        val want = hooks.audioLang() ?: return
        if (all.isEmpty()) return
        val loose = filters.loose()
        langs.record(t.key, all.filter { !it.cam && Ranking.allowed(it, loose, t.isEpisode) }.map { it.s.torrentName to it.s.hash }, want)
    }

    /**
     * Dream Core through the viewer's scrapers. A Play doesn't wait for every provider: once the first ones are in it
     * waits a moment for more ([GRACE_GOOD_MS] when one of the best allowed grade is there, else [GRACE_MS]), ranks what
     * it has and races the debrid services. Nothing ready yet: the next sources to arrive get their turn (up to three
     * rounds), until the budget ends. A look-ahead ends early too ([AHEAD_GRACE_MS]); the scrape itself goes on and is
     * kept for Change Source and the Play that follows.
     */
    private suspend fun scraperLookup(t: PlayTarget, k: String, imdb: String, l: Lookup, t0: Long, startMs: Long?): Stream? {
        // Read live: a Play that joins a look-ahead raises its l.purpose and brings its own l.deadline.
        // Only a real Play sets the filters aside when nothing passes them (no speculative CAM unlocks).
        val ep = t.isEpisode
        val s = if (ep) t.season else null
        val e = if (ep) t.episode else null
        val g = gather(t, imdb, if (l.purpose == DebridPurpose.PLAY) ScrapeMode.PLAY else ScrapeMode.FULL)
        l.phase = "waiting for the scrapers"
        val f = filters.current()
        val resume = (startMs ?: hooks.resumeMs(t)) > 0
        val best = Ranking.bestTier(f)
        val skip = skipSet(k)
        val tried = HashSet<String>()
        var services: List<DebridService>? = null
        var servicesFor: DebridPurpose? = null
        var round = 0
        while (true) {
            var kept: List<Ranking.Parsed>
            var cx = context(t, f, skip + tried, resume)
            while (true) {
                val v = g.version
                kept = Ranking.choose(merged(g, k, s, e), cx)
                val n = now()
                if (g.done || n >= l.deadline - DEBRID_RESERVE_MS) break
                var wake = l.deadline - DEBRID_RESERVE_MS
                // A look-ahead checks every 250 ms whether a Play joined it: the Play's l.deadline and relaxed filters apply at once.
                if (l.purpose == DebridPurpose.PAGE) wake = minOf(wake, n + JOIN_CHECK_MS)
                if (kept.isNotEmpty()) {
                    if (round > 0) break // later rounds: whatever new arrives goes at once
                    val since = n - (g.firstAt ?: n)
                    val good = kept.any { !it.cam && Ranking.tier(it.rank, f) <= best } // a CAM never cuts the wait short
                    val grace = if (l.purpose == DebridPurpose.PAGE) (if (good) AHEAD_GRACE_GOOD_MS else AHEAD_GRACE_MS)
                        else (if (good) GRACE_GOOD_MS else GRACE_MS)
                    if (since >= grace) break
                    wake = minOf(wake, n + minOf(grace - since, if (l.purpose == DebridPurpose.PAGE) JOIN_CHECK_MS else grace))
                }
                g.await(v, wake)
                cx = context(t, f, skip + tried, resume)
            }
            var level = 0
            if (kept.isEmpty() && (l.purpose == DebridPurpose.PLAY)) Ranking.chooseRelaxed(merged(g, k, s, e), cx, true).let { kept = it.first; level = it.second }
            if (kept.isEmpty()) {
                if (g.done || now() >= l.deadline) {
                    val n = merged(g, k, s, e).size
                    if (n == 0) {
                        // No answer at all (busy, not running, every provider failed) is unknown, never "not out yet".
                        if (g.done && !g.answered) return none(t, k, l, "the scrapers didn't answer", Miss.NO_ANSWER)
                        return none(t, k, l, "the scrapers found nothing${if (g.done) "" else " yet"}", if (g.done) Miss.NOTHING else null)
                    }
                    if (round == 0) return none(t, k, l, "nothing of $n passes your source filters${if ((l.purpose == DebridPurpose.PLAY)) ", even relaxed" else ""}", if (g.done) Miss.NOTHING else null)
                    return none(t, k, l, "none of ${tried.size} cached", Miss.NOT_READY)
                }
                g.await(g.version, l.deadline)
                continue
            }
            val top = kept.take(TOP)
            recordNames(t, merged(g, k, s, e))
            val p = l.purpose
            l.ran = p
            if (servicesFor != p) services = null
            val svcs = services ?: services(p).also { services = it; servicesFor = p }
            if (svcs.isEmpty()) {
                if (p <= DebridPurpose.PAGE) return none(t, k, l, "ranked ${kept.size} of ${g.count}; the debrid step waits for Play")
                return none(t, k, l, if (allDebridBlocked()) "AllDebrid waits for this network's approval and no other service is linked"
                    else "AllDebrid is at its magnet limit and no other service is linked")
            }
            l.phase = "asking ${svcs.joinToString("/") { it.key.uppercase() }}"
            val won = race(k, svcs, top.map { it.s }, p, l.deadline)
            l.phase = "waiting for the scrapers"
            if (won != null) {
                val (found, others) = won
                remember(k, found)
                val pf = kept.firstOrNull { it.s.hash == found.hash }
                if (pf != null && !pf.cam) Ranking.bestLabel(pf).let { hooks.streamFound(t, it); foundListener?.invoke(t, it) }
                addAlternates(k, others)
                noteRelaxed(t, k, found, kept, level, f)
                if (p == DebridPurpose.PLAY && pf != null && Ranking.aboveCap(pf, f))
                    PlaybackLog.i("Sources: prefer HDR picked ${pf.s.quality.label} ${ReleaseWords.tags(pf.s.torrentName).firstOrNull() ?: "HDR"} above Maximum Quality for ${t.label}")
                PlaybackLog.w("Dream Core: ${t.label} → ${found.service.key.uppercase()} ${found.quality.label} ${size(found.sizeBytes)} in ${secs(now() - t0)} " +
                    "(scrapers: ${g.count} sources so far, ${kept.size} kept, round ${round + 1}${if (others.isEmpty()) "" else ", ${others.size} more ready"}${if (p <= DebridPurpose.PAGE) ", look-ahead" else ""})")
                return found.copy(cam = pf?.cam == true)
            }
            if (limitBlocked() && svcs.any { it is AllDebridService }) limitNote(svcs)
            if (p <= DebridPurpose.PAGE) return none(t, k, l, "ranked ${kept.size} of ${g.count}, none cached free; the debrid step waits for Play")
            tried += top.map { it.s.hash }
            round++
            val names = svcs.joinToString("/") { it.key.uppercase() }
            if (round >= 3) return none(t, k, l, "none of ${tried.size} cached at $names", Miss.NOT_READY)
            if (now() >= l.deadline) return none(t, k, l, "budget over; none of ${tried.size} cached at $names")
            if (g.done && Ranking.chooseRelaxed(merged(g, k, s, e), context(t, f, skip + tried, resume), (l.purpose == DebridPurpose.PLAY)).first.isEmpty())
                return none(t, k, l, "none of ${tried.size} cached at $names", Miss.NOT_READY)
        }
    }

    private fun remember(k: String, s: Stream) = synchronized(lock) {
        links[k] = Stamped(s, now())
        if (links.size > 100) links.entries.removeAll { now() - it.value.at >= LINK_TTL }
    }

    private fun noteRelaxed(t: PlayTarget, k: String, found: Stream, kept: List<Ranking.Parsed>, level: Int, f: SourceFilters.Values) {
        if (level <= 0) { synchronized(lock) { relaxedLinks.remove(k) }; return }
        val p = kept.firstOrNull { it.s.hash == found.hash }
        val cam = p?.cam == true
        // The CAM note only for a viewer who hides CAM copies; one who lets them in hears the general note.
        synchronized(lock) { relaxedLinks[k] = found.hash to (cam && f.hideCam) }
        val relaxedWhat = listOf("size", "quality", "formats").take(level).reversed().joinToString(", ")
        PlaybackLog.w("Dream Core: ${t.label} nothing passes your filters, playing best available (${p?.let { Ranking.bestLabel(it) } ?: found.quality.label}) [relaxed: $relaxedWhat]")
    }

    // ---- the race ----

    /** What one race has heard so far, shared by its service tasks and the waiter. */
    private class RaceBox(val count: Int) {
        val signal = Channel<Unit>(Channel.CONFLATED)
        val results = HashMap<Int, List<Stream>>()
        var firstReadyAt: Long? = null
        var decided = false

        /** Service [i] is done; true when the race was decided already (its links go to the alternates). */
        fun finish(i: Int, got: List<Stream>, now: Long): Boolean {
            val late = synchronized(this) {
                results[i] = got
                if (firstReadyAt == null && got.isNotEmpty()) firstReadyAt = now
                decided
            }
            signal.trySend(Unit)
            return late
        }

        /** (winner index, winner, others) / null still waiting / ( -1 ) nothing. */
        fun decide(now: Long): Triple<Int, Stream?, List<Stream>>? = synchronized(this) {
            if (decided) return Triple(-1, null, emptyList())
            val ready = results.filter { it.value.isNotEmpty() }.keys.sorted()
            val best = ready.firstOrNull() ?: return if (results.size >= count) Triple(-1, null, emptyList()) else null
            val earlierPending = (0 until best).any { it !in results }
            if (earlierPending && firstReadyAt != null && now - firstReadyAt!! < TIE_MS) return null
            decided = true
            val won = results.getValue(best)
            Triple(best, won.first(), won.drop(1) + ready.drop(1).flatMap { results.getValue(it) })
        }

        fun close() = synchronized(this) { decided = true }
        val isDecided get() = synchronized(this) { decided }
    }

    /**
     * The debrid step: every service in [svcs] asked at once, the first with a link wins, the viewer's order breaking
     * near-ties ([TIE_MS]). A service whose check adds to the account (AllDebrid, Real-Debrid) joins [UPLOAD_DELAY_MS]
     * after the free checks: when Premiumize / TorBox has a link by then, nothing is uploaded at all. The winner's extra
     * links and every other service's (even ones that land after the decision) become the alternates. A Play races up
     * to [RACE_LINKS] per service; other purposes one.
     */
    private suspend fun race(k: String, svcs: List<DebridService>, cands: List<Source>, p: DebridPurpose, deadline: Long): Pair<Stream, List<Stream>>? {
        if (svcs.isEmpty() || cands.isEmpty()) return null
        val box = RaceBox(svcs.size)
        val limit = if (p == DebridPurpose.PLAY) RACE_LINKS else 1
        val anyFree = svcs.any { it.id == DebridServiceId.PREMIUMIZE || it.id == DebridServiceId.TORBOX }
        svcs.forEachIndexed { i, svc ->
            val mine = if (p == DebridPurpose.AHEAD && svc.id == DebridServiceId.REAL_DEBRID) cands.take(2) else cands
            val uploads = svc.id == DebridServiceId.ALLDEBRID || svc.id == DebridServiceId.REAL_DEBRID
            scope.launch {
                if (uploads && anyFree) {
                    delay(UPLOAD_DELAY_MS)
                    if (box.isDecided) { box.finish(i, emptyList(), now()); return@launch }
                }
                val got = try { svc.resolveMany(mine, deadline, limit) } catch (e: CancellationException) { emptyList() } catch (e: Exception) {
                    PlaybackLog.w("Dream Core: ${svc.key.uppercase()} failed (${e.javaClass.simpleName})"); emptyList()
                }
                if (box.finish(i, got, now()) && got.isNotEmpty()) {
                    addAlternates(k, got)
                    PlaybackLog.i("Dream Core: ${svc.key.uppercase()} answered after the race: ${got.size} more ready")
                }
            }
        }
        val end = deadline + 1_500
        while (true) {
            val n = now()
            val d = box.decide(n)
            if (d != null) {
                val (i, f, others) = d
                if (f == null) return null
                if (svcs.size > 1 || others.isNotEmpty()) PlaybackLog.i("Dream Core: race won by ${svcs[i].key.uppercase()} of ${svcs.joinToString("/") { it.key.uppercase() }} (${others.size} more ready)")
                return f to others
            }
            if (n >= end) { box.close(); return null }
            val wake = minOf(end, box.firstReadyAt?.let { it + TIE_MS } ?: end)
            withTimeoutOrNull((wake - n).coerceAtLeast(20)) { box.signal.receive() }
        }
    }

    // ---- which services a lookup asks ----

    /** The linked service [id] as a Play would ask it, or null. */
    private fun service(id: DebridServiceId): DebridService? = services(DebridPurpose.PLAY).firstOrNull { it.id == id }

    /**
     * The linked services in the viewer's order. A look-ahead (PAGE and below) may only ask what adds nothing to any
     * account: Premiumize and TorBox. The rest: every linked service, AllDebrid left out while at its magnet limit, the
     * clearly fastest first.
     */
    private fun services(purpose: DebridPurpose): List<DebridService> {
        val acc = kit.accounts
        val order = acc.order()
        if (purpose <= DebridPurpose.PAGE) return order.mapNotNull { id ->
            when (id) {
                DebridServiceId.PREMIUMIZE -> acc.token(id)?.let { PremiumizeService(kit, it) }
                DebridServiceId.TORBOX -> acc.token(id)?.let { TorBoxService(kit, it, tbState) }
                else -> null
            }
        }
        val list = order.mapNotNull<DebridServiceId, DebridService> { id ->
            val tok = acc.token(id) ?: return@mapNotNull null
            when (id) {
                DebridServiceId.ALLDEBRID -> if (AllDebridService.limited(kit.ledger, tok, clock.wallMs())) null else AllDebridService(kit, tok, purpose, adState)
                DebridServiceId.PREMIUMIZE -> PremiumizeService(kit, tok)
                DebridServiceId.TORBOX -> TorBoxService(kit, tok, tbState)
                DebridServiceId.REAL_DEBRID -> RealDebridService(kit, rdState)
            }
        }
        return fastestFirst(list)
    }

    private fun fastestFirst(list: List<DebridService>): List<DebridService> {
        if (list.size < 2) return list
        val first = startTime(list[0].id) ?: return list
        val best = list.drop(1).mapNotNull { s -> startTime(s.id)?.let { s to it } }.minByOrNull { it.second } ?: return list
        if (first - best.second < CLEARLY_FASTER_MS) return list
        PlaybackLog.i("Dream Core: asking ${best.first.key.uppercase()} first (starts in ~${secs(best.second)}, ${list[0].key.uppercase()} ~${secs(first)})")
        return listOf(best.first) + list.filter { it !== best.first }
    }

    @Volatile private var limitNotedDay = -1L

    private fun limitNote(svcs: List<DebridService>) {
        val day = clock.wallMs() / 86_400_000L
        if (limitNotedDay != day) {
            limitNotedDay = day
            hooks.accountNotice(DebridServiceId.ALLDEBRID, "AllDebrid Magnet Limit Reached", "It frees up automatically. Other linked services keep streaming.")
        }
        val others = svcs.filter { it !is AllDebridService }.map { it.id.displayName }
        PlaybackLog.w(if (others.isEmpty()) "AllDebrid at its magnet limit: no other service linked" else "AllDebrid at its magnet limit: using ${others.joinToString("/")}")
    }

    // ---- measured start times ----

    @Serializable private data class Timed(val avg: Long, val n: Int)

    /** [s] goes to the player now: its first frame is timed from here. */
    fun handing(s: Stream) = synchronized(lock) {
        handedAt[s.url] = now()
        if (handedAt.size > 20) handedAt.entries.removeAll { now() - it.value > 120_000 }
    }

    /**
     * Dream Player's first frame of [s] for [t]: the time since [handing] counts for the service that unlocked it (a
     * rolling average), its release's traits learn a clean start, and alternates that hold something in an account go.
     */
    fun firstFrame(t: PlayTarget, s: Stream) {
        val at = synchronized(lock) { handedAt.remove(s.url) } ?: return
        val ms = now() - at
        if (ms <= 0 || ms > 60_000) return
        val key = "core.ttff." + s.service.key
        val old = try { store.get(key)?.let { Json.decodeFromString(Timed.serializer(), it) } } catch (e: Exception) { null }
        val avg = old?.let { (it.avg * 2 + ms) / 3 } ?: ms
        val n = minOf((old?.n ?: 0) + 1, 99)
        store.put(key, Json.encodeToString(Timed.serializer(), Timed(avg, n)))
        PlaybackLog.i("Dream Core: ${s.service.key.uppercase()} first frame in ${secs(ms)} (typical ${secs(avg)} over $n plays)")
        memory.record(traits(t.key, s), ok = true, ms = ms)
        settleAlternates(t.key, s)
    }

    private fun startTime(id: DebridServiceId): Long? {
        val t = try { store.get("core.ttff." + id.key)?.let { Json.decodeFromString(Timed.serializer(), it) } } catch (e: Exception) { null } ?: return null
        return if (t.n >= MIN_TIMED && t.avg > 0) t.avg else null
    }

    // ---- the source list (Choose a Source, Search All Sources) ----

    /**
     * The torrents the viewer's scrapers find for [t], ranked exactly as [find] ranks them; [loose]: the viewer's filters
     * off, what they'd hide marked. Torrents that failed here come last; other episodes flagged; one row per release.
     * Empty when no account, no IMDb id or no scraper answered.
     */
    suspend fun sources(t: PlayTarget, loose: Boolean, budgetMs: Long = 8_000): List<SourceRow> {
        val deadline = now() + budgetMs
        val ep = t.isEpisode
        if (!kit.accounts.any || (ep && (t.season == null || t.episode == null)) || !engine.active()) return emptyList()
        val imdb = imdbFor(t) ?: return emptyList()
        val g = gather(t, imdb, ScrapeMode.PLAY)
        g.awaitDone(deadline)
        val all = merged(g, t.key, if (ep) t.season else null, if (ep) t.episode else null)
        val f = filters.current()
        val bad = synchronized(lock) { failed[t.key]?.keys?.toSet().orEmpty() }
        val cx = context(t, f, emptySet(), hooks.resumeMs(t) > 0)
        val want = cx.want
        val anime = cx.dub != Dubs.Rank.NONE
        recordNames(t, all)
        val (ranked, level) = if (loose) Ranking.choose(all, cx.copy(f = filters.loose()), Ranking.RELAX_QUALITY) to 0
            else Ranking.chooseRelaxed(all, cx, true)
        val ordered = ranked.filter { it.s.hash !in bad } + ranked.filter { it.s.hash in bad }
        val rows = ordered.take(MAX_LISTED).map { p ->
            val badges = listOfNotNull(if (p.cam) ReleaseWords.camLabel(p.s.torrentName) else null) + ReleaseWords.tags(p.s.torrentName) +
                listOfNotNull(want?.let { Dubs.badge(p.s.torrentName, it, anime) })
            val name = p.s.fileName?.let { DebridFiles.base(it) } ?: p.s.torrentName
            SourceRow(p.s, StreamBadges.camFirst(badges), (loose || level > 0) && (!Ranking.allowed(p, f, ep, cx.runtimeSec) || (f.hideSd && p.s.quality == Quality.SD)),
                p.cam, SourceNames.match(name, t) == SourceNames.Match.OTHER_EPISODE, providerName = SourceNames.provider(p.s.provider))
        }
        return SourceNames.dedupe(rows)
    }

    /** [source] (one of [sources]) unlocked through the linked services, cached only; remembered as [t]'s link. */
    suspend fun resolveSource(t: PlayTarget, source: Source, budgetMs: Long = 15_000): Stream? {
        val ep = t.isEpisode
        val c = source.copy(season = if (ep) t.season else null, episode = if (ep) t.episode else null)
        return unlock(t, listOf(c), now() + budgetMs, "picked")?.copy(cam = ReleaseWords.isCam(c.torrentName))
    }

    /** Search All Sources: [find] with the filters off (Hide CAM stays), failed torrents skipped, AV1 out when hidden. */
    suspend fun findLoose(t: PlayTarget, budgetMs: Long = 12_000): Stream? {
        val deadline = now() + budgetMs
        val bad = synchronized(lock) { failed[t.key]?.keys?.toSet().orEmpty() }
        val av1 = filters.current().hideAv1
        val listed = sources(t, loose = true, budgetMs = budgetMs / 2).filter { it.source.hash !in bad && !(av1 && "AV1" in it.badges) }.take(TOP * 2)
        if (listed.isEmpty()) { PlaybackLog.w("Dream Core: ${t.label} → nothing (loose: the scrapers found nothing playable)"); return null }
        val f = unlock(t, listed.map { it.source }, deadline, "loose") ?: return null
        val cam = listed.firstOrNull { it.source.hash == f.hash }?.cam == true
        synchronized(lock) { if (cam && filters.current().hideCam) relaxedLinks[t.key] = f.hash to true else relaxedLinks.remove(t.key) }
        return f.copy(cam = cam)
    }

    private suspend fun unlock(t: PlayTarget, cands: List<Source>, deadline: Long, why: String): Stream? {
        val t0 = now()
        val svcs = services(if (why == "test") DebridPurpose.LIST else DebridPurpose.PLAY)
        val k = t.key
        val won = race(k, svcs, cands, if (why == "test") DebridPurpose.LIST else DebridPurpose.PLAY, deadline)
        if (won != null) {
            remember(k, won.first)
            addAlternates(k, won.second)
            PlaybackLog.w("Dream Core: ${t.label} → ${won.first.service.key.uppercase()} ${won.first.quality.label} ${size(won.first.sizeBytes)} in ${secs(now() - t0)} ($why${if (won.second.isEmpty()) "" else ", ${won.second.size} more ready"})")
            return won.first
        }
        if (limitBlocked() && svcs.any { it is AllDebridService }) limitNote(svcs)
        PlaybackLog.w("Dream Core: ${t.label} → nothing cached of ${cands.size} ($why)")
        return null
    }

    /** Which of [rows]' top rows are ready now: AllDebrid's one-call answer when it's linked, else null. */
    suspend fun readyHashes(rows: List<Source>, budgetMs: Long = 6_000): Set<String>? {
        val tok = kit.accounts.token(DebridServiceId.ALLDEBRID) ?: return null
        return AllDebridService(kit, tok, DebridPurpose.LIST, adState).readyHashes(rows.take(DebridPurpose.LIST.maxUploads), now() + budgetMs)
    }

    // ---- the pills' survey ----

    /**
     * What the scrapers find for [t] (counts, best release), nothing sent to any service and no account needed. A full
     * scrape already running for it counts (it answers once a real copy has had a Play's grace for company); else the
     * quick check. Null when the scrapers didn't answer (no pill, asked again later): never "Not Available Yet".
     */
    suspend fun survey(t: PlayTarget, budgetMs: Long = 8_000): SourceSurvey? {
        val ep = t.isEpisode
        if (!engine.active() || (ep && (t.season == null || t.episode == null))) return null
        val imdb = imdbFor(t) ?: return null
        val s = if (ep) t.season else null
        val e = if (ep) t.episode else null
        val t0 = now()
        gatherOf(t.key)?.let { g ->
            val end = t0 + budgetMs
            while (!g.done && now() < end) {
                val v = g.version
                var wake = end
                val first = g.firstAt
                if (first != null) {
                    val since = now() - first
                    if (since >= GRACE_MS) { if (parsed(g, s, e).any { !it.cam }) break } else wake = minOf(end, now() + GRACE_MS - since)
                }
                g.await(v, wake)
            }
            if (g.count > 0) return summarize(t, parsed(g, s, e))
            if (!g.done) return null
        }
        val q = gather(t, imdb, ScrapeMode.QUICK)
        q.awaitDone(t0 + budgetMs)
        if (q.count == 0 && (!q.done || !q.answered)) { PlaybackLog.i("Avail: no scraper answer for ${t.label}"); return null }
        PlaybackLog.i("Avail: ${t.label} from the quick check in ${now() - t0} ms (${q.count} sources)")
        return summarize(t, parsed(q, s, e))
    }

    private fun summarize(t: PlayTarget, all: List<Ranking.Parsed>): SourceSurvey {
        recordNames(t, all)
        return Ranking.survey(all, filters.current())
    }

    /** What the setup page's test saw: [found] sources, [ready] of the top ones ready on the account. */
    data class TestOutcome(val found: Int, val ready: Int, val account: Boolean)

    /** A full scrape of [t] and a readiness check of its best few (Settings › Scrapers › Test, setup's check). */
    suspend fun testScrape(t: PlayTarget, budgetMs: Long = 30_000): TestOutcome {
        val deadline = now() + budgetMs
        val imdb = imdbFor(t) ?: return TestOutcome(0, 0, false)
        val g = gather(t, imdb, ScrapeMode.PLAY)
        g.awaitDone(deadline - 6_000)
        val n = g.count
        if (!kit.accounts.any || n == 0) return TestOutcome(n, 0, kit.accounts.any)
        val top = Ranking.choose(parsed(g, null, null), context(t, filters.loose(), emptySet(), false)).take(5).map { it.s }
        readyHashes(top, (deadline - now()).coerceAtLeast(3_000))?.let { return TestOutcome(n, it.size, true) }
        val one = unlock(t, top, maxOf(deadline, now() + 4_000), "test")
        return TestOutcome(n, if (one == null) 0 else 1, true)
    }

    // ---- warm-up ----

    @Volatile private var warmedAt = 0L

    /** Starts the scraper engine and opens connections ahead to the linked services; runs the janitors. Returns at once. */
    fun warm() {
        val n = now()
        if (n - warmedAt < 60_000) return
        warmedAt = n
        try { engine.warm() } catch (e: Exception) { PlaybackLog.w("Scrapers: warm failed (${e.javaClass.simpleName})") }
        val acc = kit.accounts
        acc.token(DebridServiceId.ALLDEBRID)?.let { tok -> kit.background("AllDebrid warm") { AllDebridService(kit, tok, DebridPurpose.PLAY, adState).warm() } }
        acc.token(DebridServiceId.TORBOX)?.let { tok -> kit.background("TorBox janitor") { TorBoxService(kit, tok, tbState).sweep("app start") } }
        if (acc.token(DebridServiceId.REAL_DEBRID) != null) kit.background("Real-Debrid janitor") { RealDebridService(kit, rdState).sweep() }
        listOfNotNull(
            acc.token(DebridServiceId.PREMIUMIZE)?.let { "${PremiumizeService.API}/account/info" },
            acc.token(DebridServiceId.TORBOX)?.let { "https://api.torbox.app/" },
            acc.token(DebridServiceId.REAL_DEBRID)?.let { "${RealDebridService.REST}time" },
        ).forEach { u -> kit.background("warm") { kit.http.get(u, timeoutMs = 5_000) } }
    }
}
