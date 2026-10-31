package com.dreamos.desktop.core.playback

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * Instant Play on a PC (fire-home PreResolve, iPhone PlayAhead): the stream for what the viewer is looking at is found
 * before Play is pressed.
 *  - A title page opening, and Continue Watching's newest [CONTINUE_ITEMS], run Dream Core's lookup as PAGE: the full
 *    scrape and ranking, and only the free cache checks (Premiumize, TorBox). Nothing is added to any account; Play then
 *    goes straight to the debrid step (or finds a Premiumize / TorBox link already remembered).
 *  - The pointer resting on a card: the scrapers' quick check only (shared with the pills).
 *  - The next episode, ~90 s before an episode's Up Next card: AHEAD (a torrent or two at the services), so autoplay
 *    is instant.
 *  - Continue Watching's links are looked up again every [REFRESH_MS] once their remembered link ran out.
 * Bounds, sized for a PC (the Fire Stick runs one look-ahead at a time, the iPhone two): [MAX_AT_ONCE] look-aheads
 * at once (a newer title page replaces an older waiting one; nothing running is cancelled, a Play may have joined it),
 * Continue Watching's newest [CONTINUE_ITEMS] side by side within those; [CARD_WORKERS] card checks at once (the
 * scrapers' background lane has three slots) with [MAX_CARDS] waiting (the newest kept); a title is looked up at most
 * once a minute. Worst case: 5 scrapes + 3 quick checks in flight, each bounded by its budget. While Dream Player
 * plays, only the next episode's look-ahead runs.
 */
class LookAhead(private val core: DreamCore) {
    companion object {
        const val CONTINUE_ITEMS = 5
        const val MAX_AT_ONCE = 5
        const val MAX_CARDS = 24
        const val CARD_WORKERS = 3
        const val REFRESH_MS = 15 * 60_000L
        const val PAGE_BUDGET_MS = 30_000L
    }

    private val scope = core.kit.scope
    private fun now() = core.kit.clock.monotonicMs()
    private val slots = Semaphore(MAX_AT_ONCE)
    private val lock = Any()
    private val running = HashMap<String, Long>()
    @Volatile private var waitTicket = 0
    @Volatile private var playerActive = false
    private var continueList: List<PlayTarget> = emptyList()
    private var refreshLoop: Job? = null
    private val cards = ArrayDeque<PlayTarget>()
    private var cardWorkers = 0

    fun playerActive(active: Boolean) {
        playerActive = active
        if (active) { core.trimForPlayback(); try { core.engine.trim() } catch (e: Exception) {} }
    }

    /** A title page opened: its likely Play (the movie, or the Up Next episode) is looked up now. */
    fun titleOpened(t: PlayTarget) {
        if (!core.available() || playerActive) return
        core.warm()
        scope.launch { run(t, "title page", newest = true) }
    }

    /** Continue Watching is on screen: its newest items, side by side within [MAX_AT_ONCE]; kept for the refresh loop. */
    fun continueWatching(targets: List<PlayTarget>) {
        val list = targets.take(CONTINUE_ITEMS)
        synchronized(lock) { continueList = list }
        if (!core.available() || playerActive) return
        for (t in list) scope.launch { run(t, "Continue Watching") }
        startRefreshLoop()
    }

    /** The pointer rested on a card: the quick check (no account touched), [CARD_WORKERS] at once, the newest [MAX_CARDS] waiting. */
    fun cardRested(t: PlayTarget) {
        if (playerActive || !core.engine.active()) return
        synchronized(lock) {
            cards.removeAll { it.key == t.key }
            cards.addFirst(t)
            while (cards.size > MAX_CARDS) cards.removeLast()
            if (cardWorkers >= CARD_WORKERS) return
            cardWorkers++
        }
        scope.launch {
            try {
                while (true) {
                    val next = synchronized(lock) { cards.removeFirstOrNull() } ?: break
                    if (playerActive) { synchronized(lock) { cards.clear() }; break }
                    if (core.peek(next) == null) core.find(next, 6_000, DebridPurpose.BROWSE)
                }
            } finally { synchronized(lock) { cardWorkers-- } }
        }
    }

    /** ~90 s before the Up Next card: the next episode's link, found while the credits roll. */
    fun nextEpisode(next: PlayTarget) {
        if (!core.available() || core.peek(next) != null) return
        if (!claim(next.key)) return
        scope.launch {
            val f = core.find(next, 30_000, DebridPurpose.AHEAD)
            synchronized(lock) { running.remove(next.key) }
            if (f != null) {
                PlaybackLog.i("Next episode: link ready for ${next.label} (${f.service.key.uppercase()} ${f.quality.label})")
                core.warmStream(next, f) // its opening seconds on disk before the credits end
            }
        }
    }

    private fun claim(k: String): Boolean = synchronized(lock) {
        val at = running[k]
        if (at != null && now() - at < 60_000) false else { running[k] = now(); true }
    }

    /** One look-ahead lookup; [newest]: a title page (it replaces an older waiting page, never a running lookup). */
    private suspend fun run(t: PlayTarget, why: String, newest: Boolean = false, budgetMs: Long = PAGE_BUDGET_MS) {
        if (core.peek(t) != null || !claim(t.key)) return
        val ticket = if (newest) synchronized(lock) { ++waitTicket } else -1
        try {
            while (!slots.tryAcquire()) {
                if (playerActive || (newest && ticket != waitTicket)) {
                    PlaybackLog.i("Instant Play: skipped ${t.label} (${if (playerActive) "playing" else "a newer title page came first"})")
                    return
                }
                delay(250)
            }
            try {
                val t0 = now()
                PlaybackLog.i("Instant Play: looking up ${t.label} ($why)")
                val f = core.find(t, budgetMs, DebridPurpose.PAGE)
                if (f != null) {
                    PlaybackLog.i("Instant Play: link ready for ${t.label} after ${DreamCore.secs(now() - t0)} (${f.service.key.uppercase()} ${f.quality.label})")
                    core.warmStream(t, f) // the stream's head start: its first seconds on disk before Play
                }
                else PlaybackLog.i("Instant Play: ${t.label} ranked after ${DreamCore.secs(now() - t0)}; Play goes straight to the debrid step")
            } finally { slots.release() }
        } finally {
            synchronized(lock) { running.remove(t.key) }
        }
    }

    /** While the app runs: Continue Watching's newest items looked up again once their links ran out (40 min). */
    private fun startRefreshLoop() = synchronized(lock) {
        if (refreshLoop?.isActive == true) return
        refreshLoop = scope.launch {
            while (isActive) {
                delay(REFRESH_MS)
                if (playerActive) continue
                val list = synchronized(lock) { continueList }
                for (t in list) if (core.peek(t) == null) run(t, "refresh")
            }
        }
    }
}
