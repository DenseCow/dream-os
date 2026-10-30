package com.firehome.launcher

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Instant Play: the stream for the title the user is looking at is found before Play is pressed.
 *
 * When a debrid account is linked ([NativeResolver.available]), [begin] runs [NativeResolver.find] on
 * its own thread. Checking a torrent at AllDebrid or Real-Debrid adds it to the user's account
 * (AllDebrid caps it at 1000 magnets), so focus resting on a card ([speculate]) and the details page
 * ([start]) only scrape and rank (kept: Play goes straight to the debrid step, ~1 s; a details page also
 * asks the services whose check is free (Premiumize, TorBox, in the viewer's order) and keeps its link as a [Link] holding the VIEW intent the play
 * flow would send, [PlayFlow.streamIntent]). The next episode while one plays ([ahead], from the
 * post-play controller) does the full lookup, a torrent or two.
 *
 * The link waits for Play, which [take]s it and opens the player with it straight away (PlayFlow), and
 * meanwhile the start of the stream is fetched to disk ([StreamCache]), so the player opens from local
 * data (not while the player is on screen: no bandwidth taken from playback). A link is dropped after
 * [LINK_TTL_MS] or once used. Debrid links are single-user stream URLs: never logged in full.
 *
 * A pre-resolve under way when Play is pressed is handed over, not raced: PlayFlow.resolve asks
 * [handOver] first. A lookup for the title is left to finish for the flow's own find(), which joins
 * the same lookup; one for another title just has its result dropped.
 */
object PreResolve {
    private const val TAG = "FireHome"
    /** How long a ready link is kept. */
    private const val LINK_TTL_MS = 45 * 60_000L
    /** Longest a job counts as running. */
    private const val JOB_MAX_MS = 60_000L

    /** One pre-resolve. [prefetch]: whether its stream's start goes to disk. */
    private class Job(val request: PlaybackRequest, val prefetch: Boolean = true, val purpose: DebridPurpose = DebridPurpose.PAGE) {
        val startedAt = SystemClock.elapsedRealtime()
        @Volatile var done = false
        @Volatile var cancelled = false
        /** A real play of the same title took it over: the play flow's own find() has its result. */
        @Volatile var adopted = false
        @Volatile var failure: String? = null

        val running get() = !done && !cancelled && !adopted && failure == null && SystemClock.elapsedRealtime() - startedAt < JOB_MAX_MS
    }

    /** A stream resolved ahead: the VIEW intent the play flow would send ([PlayFlow.streamIntent]). */
    class Link(val request: PlaybackRequest, val intent: Intent, val at: Long) {
        val fresh get() = SystemClock.elapsedRealtime() - at < LINK_TTL_MS
    }

    /** Ready links kept at once (a sweep along a row, then back; StreamCache holds only the newest one's start). */
    private const val LINKS = 6
    /** Speculative starts (browsing) come no closer together than this. */
    private const val SPECULATE_COOLDOWN_MS = 1_000L

    private val lock = Any()
    private var job: Job? = null
    /** Newest last. */
    private val links = ArrayList<Link>()
    private var lastStartAt = 0L

    private fun linkFor(tmdbId: Int, type: String): Link? = links.lastOrNull { it.fresh && it.request.tmdbId == tmdbId && it.request.type == type }
    private fun linkFor(r: PlaybackRequest): Link? = links.lastOrNull { it.fresh && same(it.request, r) }

    private fun same(a: PlaybackRequest, b: PlaybackRequest) =
        a.tmdbId == b.tmdbId && a.type == b.type && a.season == b.season && a.episode == b.episode

    private fun name(r: PlaybackRequest) = r.title + if (r.type == "episode") " S${r.season}E${r.episode}" else ""

    /** A play or resolve is under way (its curtain is up): nothing speculative starts over it. Under [lock] or not. */
    private fun playStarting() = PlayFlow.session?.let { !it.done && !it.cancelled && it.failure == null } == true

    // ---- from the details page ----

    /** A fresh link or a job already covers this title (MainActivity skips building the request). */
    fun covers(tmdbId: Int, show: Boolean): Boolean = synchronized(lock) {
        val type = if (show) "episode" else "movie"
        linkFor(tmdbId, type) != null || job?.let { it.running && it.purpose >= DebridPurpose.PAGE && it.request.tmdbId == tmdbId && it.request.type == type } == true
    }

    /**
     * The user is on [t]'s details page: a job still searching for another title is cancelled (the
     * page's own pre-resolve follows at once). A ready link for this title gets its stream fetched
     * again if another title's took its place.
     */
    fun lookingAt(ctx: Context, t: Title) {
        val show = t.type == "tv"
        val toStop: Job?
        val again: Link?
        synchronized(lock) {
            links.removeAll { !it.fresh }
            toStop = job?.takeIf { it.running && (it.request.tmdbId != t.id || (it.request.type == "episode") != show) }
            toStop?.cancelled = true
            again = linkFor(t.id, if (show) "episode" else "movie")
        }
        if (toStop != null) ended(toStop, "page left")
        if (again != null && !DreamPlayerActivity.visible) prefetch(ctx.applicationContext, again)
    }

    /** [j] was cancelled: its lookup finishes on its own (and is remembered); only its result is dropped here. */
    private fun ended(j: Job, why: String) {
        Log.w(TAG, "Instant play: dropped the lookup for ${name(j.request)} ($why)")
    }

    @Volatile private var lastKeyAt = 0L

    /** A key press: the stream prefetch holds off for a moment. */
    fun keyPressed() {
        lastKeyAt = SystemClock.elapsedRealtime()
    }

    /** The remote was used within the last moment: background transfers keep out of the page's way. */
    fun userBusy(): Boolean = SystemClock.elapsedRealtime() - lastKeyAt < 1_500

    /**
     * Resolves [request] ahead (see the class comment for when). A job still looking for another title
     * is dropped first (this is the page the user is on). Returns at once; the work runs on its own
     * thread. False when something that passes (a play still starting) is in the way: worth asking
     * again shortly.
     */
    fun start(ctx: Context, request: PlaybackRequest): Boolean = begin(ctx, request, speculative = false)

    /**
     * Browsing: focus rested on [request]'s title. Like [start], but no closer than the speculative
     * cooldown to the last start (false: ask again later), so sweeping a row never hammers the
     * scrapers or the debrid service.
     */
    fun speculate(ctx: Context, request: PlaybackRequest): Boolean = begin(ctx, request, speculative = true)

    private fun begin(ctx: Context, request: PlaybackRequest, speculative: Boolean): Boolean {
        val app = ctx.applicationContext
        if (!NativeResolver.available(app)) return true // nothing to find with: nothing to ask again either
        val j: Job
        val stop: Job?
        synchronized(lock) {
            if (linkFor(request) != null) return true
            val purpose = if (speculative) DebridPurpose.BROWSE else DebridPurpose.PAGE
            job?.let { if (it.running && same(it.request, request) && it.purpose >= purpose) return true }
            // A play starting takes precedence.
            if (playStarting()) return false
            val now = SystemClock.elapsedRealtime()
            if (speculative && now - lastStartAt < SPECULATE_COOLDOWN_MS) return false
            stop = job?.takeIf { it.running }
            stop?.cancelled = true
            j = Job(request, purpose = purpose)
            job = j
            lastStartAt = now
        }
        StreamCache.warm(app) // its index is open by the time the stream is prefetched
        Thread({
            if (stop != null) ended(stop, "${name(request)} instead")
            runNative(app, j)
        }, "pre-resolve").apply { isDaemon = true }.start()
        return true
    }

    /**
     * The next episode, while one plays: the link kept for [PlayerHost.playNext] (PlayFlow.play takes
     * it), the stream's start not fetched (playback has the bandwidth). Returns at once; nothing when
     * the resolver isn't available or the link exists.
     */
    fun ahead(ctx: Context, request: PlaybackRequest) {
        val app = ctx.applicationContext
        if (!NativeResolver.available(app)) return
        val j: Job
        val stop: Job?
        synchronized(lock) {
            if (linkFor(request) != null) return
            job?.let { if (it.running && same(it.request, request)) return }
            stop = job?.takeIf { it.running }
            stop?.cancelled = true
            j = Job(request, prefetch = false, purpose = DebridPurpose.AHEAD)
            job = j
        }
        if (stop != null) ended(stop, "${name(request)} is next")
        Thread({ runNative(app, j) }, "pre-resolve").apply { isDaemon = true }.start()
    }

    // ---- from Home: a followed show's new episode (NewEpisodeSpot, the New Episode banner) ----

    /**
     * Home is idle and [request] (a followed show's new episode, offered on Home's banner) is likely to be
     * played next: its stream is found the way the next episode's is during playback (Dream Core only, a
     * torrent or two at the debrid service: [DebridPurpose.AHEAD]) and its start fetched to disk, on a
     * background-priority thread. Never cancels another job and never starts over a play: false while a job
     * runs, a play is starting or the player is up (ask again later). True when it started, or a fresh link
     * is there already; then [done] runs once, on that thread, with whether a link is ready and, if not,
     * whether the job was interrupted (cancelled by browsing, or adopted by Play) rather than finding nothing.
     */
    fun idleAhead(ctx: Context, request: PlaybackRequest, done: (ready: Boolean, interrupted: Boolean) -> Unit): Boolean {
        val app = ctx.applicationContext
        if (!NativeResolver.available(app)) return false
        val j: Job
        synchronized(lock) {
            if (linkFor(request) == null) {
                if (job?.running == true || playStarting() || DreamPlayerActivity.isActive()) return false
                j = Job(request, prefetch = true, purpose = DebridPurpose.AHEAD)
                job = j
            } else {
                j = Job(request).also { it.done = true }
            }
        }
        if (j.done) { done(true, false); return true }
        StreamCache.warm(app)
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runNative(app, j)
            val ready = synchronized(lock) { linkFor(request) != null }
            done(ready, !ready && (j.cancelled || j.adopted))
        }, "pre-resolve").apply { isDaemon = true }.start()
        return true
    }

    /** A fresh link for exactly [request] is waiting: Play starts from it at once. */
    fun ready(request: PlaybackRequest): Boolean = synchronized(lock) { linkFor(request) != null }

    /** [request]'s ready link gets its start fetched to disk again if another title's took its place (focus on its Play). */
    fun rewarm(ctx: Context, request: PlaybackRequest) {
        val l = synchronized(lock) { linkFor(request) } ?: return
        if (!DreamPlayerActivity.isActive()) prefetch(ctx.applicationContext, l)
    }

    /** How long ago the remote was last used (ms). */
    fun sinceKeyMs(): Long = SystemClock.elapsedRealtime() - lastKeyAt

    /**
     * Dream Core's lookup for [j]. The link is stored only if the job is still wanted when the
     * result lands (cancelled or adopted: dropped, so a stream the play flow took and that failed
     * never comes back through [take]).
     */
    private fun runNative(app: Context, j: Job) {
        try {
            if (j.cancelled) return
            val found = NativeResolver.find(app, j.request, purpose = j.purpose)
            if (found == null) {
                // Browsing and a details page only rank (the debrid step is Play's: checking a torrent adds it to the
                // user's account).
                if (j.purpose <= DebridPurpose.PAGE) return drop(j, "ranked; the debrid step waits for Play", quiet = true)
                return drop(j, "nothing found")
            }
            val l: Link
            synchronized(lock) {
                if (!j.running) return // moved on, or Play took the lookup's result itself
                l = Link(j.request, PlayFlow.streamIntent(app, found.url, j.request, found.sizeBytes), SystemClock.elapsedRealtime())
                links.removeAll { !it.fresh || same(it.request, l.request) }
                links.add(l)
                while (links.size > LINKS) links.removeAt(0)
                j.done = true
            }
            Log.w(TAG, "Instant play: link ready for ${name(l.request)} after ${(l.at - j.startedAt) / 100 / 10.0} s " +
                "(${found.service.uppercase()} ${found.quality}, ${found.sizeBytes shr 20} MB)")
            // The stream's start to disk, unless something is playing (playback keeps the bandwidth).
            if (j.prefetch && !DreamPlayerActivity.isActive()) prefetch(app, l)
        } catch (e: Exception) {
            Log.w(TAG, "Instant play: $e")
            j.failure = e.toString()
        }
    }

    private fun drop(j: Job, why: String, quiet: Boolean = false) {
        j.failure = why
        synchronized(lock) { if (job === j) job = null }
        if (!quiet) Log.w(TAG, "Instant play: skipped ${name(j.request)}: $why")
    }

    /** The stream's start to disk (StreamCache skips it when that link's is there already). */
    private fun prefetch(app: Context, l: Link) {
        val url = l.intent.dataString ?: return
        // Bluetooth audio on 2.4 GHz Wi-Fi: this burst can make it stutter; fast start fetches it on play instead.
        if (BtAudio.crowded(app)) return BtAudio.held("Instant play prefetch")
        val start = PlayFlow.resumePosition(app, l.request)
        val duration = if (start > 0) WatchHistory.entries(app).firstOrNull { it.tmdbId == l.request.tmdbId && (it.type == "episode") == (l.request.type == "episode") }?.durationMs ?: 0L else 0L
        StreamCache.prefetch(app, url, emptyMap(), start, duration)
    }

    // ---- Continue Watching's links, kept fresh while Home is idle (the iPhone's background refresh) ----

    /** How often Continue Watching's newest items are looked at again while Dream OS is in front. */
    private const val REFRESH_MS = 15 * 60_000L
    private const val REFRESH_ITEMS = 2
    /** The remote must have rested this long; a check that finds Home busy asks again after [REFRESH_RETRY_MS]. */
    private const val REFRESH_REST_MS = 4_000L
    private const val REFRESH_RETRY_MS = 30_000L

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** Home's own idle test (nothing over it, the boot intro done); null while Dream OS isn't in front. */
    @Volatile private var homeIdle: (() -> Boolean)? = null
    @Volatile private var refreshCtx: Context? = null

    /**
     * Dream OS is in front: every [REFRESH_MS], when Home is idle ([idle]: nothing over it, the intro done), nothing
     * plays or starts and the remote has rested, Continue Watching's [REFRESH_ITEMS] newest items are looked up again
     * if their link has run out (links live 40 min). Only free cache checks ([DebridPurpose.PAGE]: Premiumize,
     * TorBox): never an upload. For an AllDebrid / Real-Debrid viewer this keeps the scrape and ranking fresh, so
     * Play goes straight to the debrid step. The newest item's stream start goes to disk as a page's would.
     */
    fun watchContinueWatching(ctx: Context, idle: () -> Boolean) {
        refreshCtx = ctx.applicationContext
        homeIdle = idle
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, REFRESH_MS)
    }

    /** Dream OS left the front: no refreshes until [watchContinueWatching] again. */
    fun stopWatching() {
        homeIdle = null
        refreshCtx = null
        main.removeCallbacks(refresh)
    }

    private val refresh = object : Runnable {
        override fun run() {
            val app = refreshCtx ?: return
            val idle = homeIdle ?: return
            main.removeCallbacks(this)
            val busy = !idle() || DreamPlayerActivity.isActive() || playStarting() || sinceKeyMs() < REFRESH_REST_MS ||
                synchronized(lock) { job?.running == true } || !InstantPlay.enabled(app) || !NativeResolver.available(app)
            if (busy) { main.postDelayed(this, REFRESH_RETRY_MS); return }
            main.postDelayed(this, REFRESH_MS)
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                try { refreshContinueWatching(app) } catch (e: Exception) { Log.w(TAG, "Instant play: refresh failed ($e)") }
            }, "cw-refresh").apply { isDaemon = true }.start()
        }
    }

    /** Background thread. One lookup at a time; stops as soon as the remote wakes up or something plays. */
    private fun refreshContinueWatching(app: Context) {
        val items = WatchHistory.recent(app).filter { !it.hidden }.take(REFRESH_ITEMS)
        var looked = 0
        for ((n, e) in items.withIndex()) {
            if (DreamPlayerActivity.isActive() || playStarting() || sinceKeyMs() < REFRESH_REST_MS || homeIdle?.invoke() != true) break
            val req = PlayFlow.request(e.isShow, e.tmdbId, e.title, e.year, e.imdb, e.season, e.episode,
                backdropPath = e.backdropPath, posterPath = e.posterPath, episodeTitle = e.episodeTitle)
            if (NativeResolver.peek(req) != null || ready(req)) continue
            val j: Job
            synchronized(lock) {
                if (job?.running == true) return
                j = Job(req, prefetch = n == 0, purpose = DebridPurpose.PAGE)
                job = j
            }
            looked++
            Log.w(TAG, "Instant play: Continue Watching refresh: looking at ${name(req)} (Home idle ${sinceKeyMs() / 1000} s)")
            runNative(app, j)
            synchronized(lock) { if (job === j) job = null }
        }
        if (looked > 0) Log.w(TAG, "Instant play: Continue Watching refresh done ($looked of ${items.size} looked up)")
    }

    // ---- from the play flow ----

    /** The ready link for exactly [req] (title, season, episode), consumed; null when there is none. */
    fun take(req: PlaybackRequest): Link? = synchronized(lock) {
        links.removeAll { !it.fresh }
        val l = linkFor(req) ?: return null
        links.remove(l)
        l
    }

    /**
     * A real resolve for [req] is about to start (PlayFlow.resolve, background). A link that got ready
     * meanwhile is delivered (true). A lookup for this title is left running but marked adopted (false:
     * the flow's own find() joins the same lookup and gets its result; the job's copy is dropped). A job
     * for another title is dropped first (false: resolve as usual).
     */
    fun handOver(app: Context, req: PlaybackRequest): Boolean {
        val stop: Job?
        val ready: Link?
        synchronized(lock) {
            val j = job
            if (j != null && !j.cancelled && same(j.request, req) && j.running) {
                j.adopted = true
                Log.w(TAG, "Instant play: Play pressed while looking up ${name(req)}; the play flow joins that lookup")
                return false
            }
            stop = j?.takeIf { it.running }
            stop?.cancelled = true
            ready = take(req)
        }
        if (ready != null) {
            Log.w(TAG, "Instant play: link for ${name(req)} got ready just in time")
            PlayFlow.deliver(app, req, ready)
            return true
        }
        if (stop != null) ended(stop, "${name(req)} plays")
        return false
    }
}
