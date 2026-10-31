package com.dreamos.desktop.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Dates and lists the availability checker needs from win-content (DreamTime, TMDB release dates, My List). Every date
 * is "yyyy-MM-dd" in the viewer's time zone; date-only TMDB dates never shift a day.
 */
interface ReleaseInfo {
    /** Today in the viewer's zone. */
    fun today(): LocalDate = LocalDate.now()
    /** Epoch ms of [date]'s midnight in the viewer's zone. */
    fun midnight(date: String): Long = LocalDate.parse(date).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    /** "Nov 14" this year, "Nov 14, 2027" past it (DreamTime). */
    fun dateWords(date: String): String
    /** "Friday at 9:00 PM", "Nov 14 at 9:00 PM" for an exact air time (AirTimes.label). */
    fun airWords(at: Long): String
    /** "Nov 14" / "Nov 14, 2027" and the short form ("Nov 14" / "Nov 2027") for a card's "Coming ..." pill. */
    fun cardDate(at: Long): Pair<String, String>
    /** The movie's US digital release day (TMDB release_dates type 4), else the earliest anywhere; null if none. */
    suspend fun digitalDate(tmdbId: Int): String? = null
    /** "movie:12" / "tv:34" keys on the profile's My List (its titles are re-checked sooner). */
    fun myListKeys(): Set<String> = emptySet()
}

/**
 * Is it out yet? (fire-home Availability.kt with the iPhone's instant-pills rules; STANDARDS §5.) For new and upcoming
 * titles, what the viewer's scrapers actually have ([DreamCore.survey]: no debrid service asked, no account needed):
 * Available Now (a real release), CAM Only (only camera / screener copies), else Not Available Yet with a note of when.
 * **A source decides, not the date** (owner, 2026-10-30): a title still ahead shows "Coming ..." at once (a stand-in
 * that's never fresh) and is looked up like any other; a source found early makes it Available.
 *
 * Answers are kept on disk ([KEEP] titles, ~80 KB) so cards show their pill on the first frame; checks run on [WORKERS]
 * background workers, starts [GAP_MS] apart, cards on screen and title pages first, none while Dream Player plays.
 * Freshness: Available Now 24 h; CAM Only / Not Available Yet 1 h ([HOT_TTL] in view or on My List); a title page asks
 * again past [PAGE_TTL]. A check with no answer is tried again after [FAILED_TTL], never written.
 */
class AvailabilityChecker(
    private val core: DreamCore,
    private val releases: ReleaseInfo,
    private val store: KeyValueStore,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : AvailabilityApi {
    companion object {
        const val RECENT_DAYS = 90L
        const val DIGITAL_LOOKBACK = 240L
        const val NEW_SHOW_DAYS = 365L
        const val EPISODE_DAYS = 21L
        const val AVAILABLE_TTL = 24 * 3_600_000L
        const val OTHER_TTL = 60 * 60_000L
        const val HOT_TTL = 50 * 60_000L
        const val PAGE_TTL = 15 * 60_000L
        const val PAGE_AVAILABLE_TTL = 3 * 3_600_000L
        const val FAILED_TTL = 95_000L
        const val SWEEP_MS = 5 * 60_000L
        const val SWEEP_MAX = 12
        const val LAUNCH_SWEEP_DELAY_MS = 25_000L
        const val LAUNCH_MAX = 40
        const val WORKERS = 2
        const val GAP_MS = 400L
        const val MAX_QUEUE = 400
        const val KEEP = 400
        private const val STORE_KEY = "availability2"
        private val MAP = MapSerializer(String.serializer(), AvailabilityAnswer.serializer())
    }

    /** Set while Dream Player plays: no checks start (they wait), the sweeps skip their turn. */
    @Volatile var paused = false

    private val cache = java.util.concurrent.ConcurrentHashMap<String, AvailabilityAnswer>()
    private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val shownAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val targets = object : LinkedHashMap<String, PlayTarget>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PlayTarget>?) = size > KEEP
    }
    private val pageWanted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val queue = ArrayDeque<PlayTarget>()
    private val queued = HashSet<String>()
    private var workers = 0
    @Volatile private var lastStart = 0L
    @Volatile private var sweeping = false
    private val _updates = MutableSharedFlow<Pair<String, AvailabilityAnswer>>(extraBufferCapacity = 256)
    override val updates: SharedFlow<Pair<String, AvailabilityAnswer>> get() = _updates

    private fun wall() = clock.wallMs()
    private fun day(offset: Long) = releases.today().plusDays(offset).toString()

    init {
        try { store.get(STORE_KEY)?.let { Json.decodeFromString(MAP, it) }?.let { cache.putAll(it) } } catch (e: Exception) {
            PlaybackLog.w("Avail: kept answers unreadable (${e.javaClass.simpleName}); starting over")
        }
        core.foundListener = { t, best ->
            // A link found for a real release: its pill says Available Now at once.
            if (peek(t.key)?.status != AvailabilityStatus.AVAILABLE) record(t, AvailabilityAnswer(AvailabilityStatus.AVAILABLE, best, null, wall()))
        }
    }

    // ---- which titles get a pill (STANDARDS §5) ----

    /**
     * A movie released (its listed date) in the last [RECENT_DAYS] or still to come; an episode aired in the last
     * [EPISODE_DAYS] or still to come. Older catalog titles get no pill and no lookup. A movie between 90 and 240 days
     * gets one when its digital release is recent ([digitalRecent], asked by [check]).
     */
    fun mayHavePill(t: PlayTarget): Boolean {
        val d = t.airDate?.takeIf { it.length == 10 } ?: return false
        return if (t.isEpisode) d >= day(-EPISODE_DAYS) else d >= day(-RECENT_DAYS) || digitalRecent[t.tmdbId] == true
    }

    private val digitalRecent = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    private fun ahead(t: PlayTarget) = t.airDate?.takeIf { it.length == 10 }?.let { it > day(0) } == true

    // ---- reads ----

    override fun peek(key: String): AvailabilityAnswer? = cache[key]

    override fun cardPill(target: PlayTarget, narrow: Boolean): Pill? {
        if (!mayHavePill(target)) return null
        shownAt[target.key] = clock.monotonicMs()
        val e = cache[target.key] ?: return null
        val l = labels(e, dated = true)
        return Pill(if (narrow) l.drop(1).ifEmpty { l } else l, e.status.tone, glyph(e))
    }

    override fun pagePill(target: PlayTarget): Pill? {
        val e = cache[target.key] ?: return null
        if (!mayHavePill(target) && e.status == AvailabilityStatus.NONE) return null
        return Pill(labels(e, dated = false), e.status.tone, glyph(e))
    }

    private fun glyph(e: AvailabilityAnswer) = when (e.status) {
        AvailabilityStatus.AVAILABLE -> PillGlyph.CHECK
        AvailabilityStatus.CAM -> PillGlyph.CAM
        AvailabilityStatus.NONE -> PillGlyph.CLOCK
    }

    /** An episode or movie whose kept answer says a source exists, whatever its listed date (out early). No lookup. */
    override fun outEarly(key: String) = cache[key]?.status.let { it == AvailabilityStatus.AVAILABLE || it == AvailabilityStatus.CAM }

    override fun languagePills(target: PlayTarget): LanguagePills =
        core.hooks.audioLang()?.let { core.langs.pills(target.key, target.originalLanguage, it) } ?: LanguagePills(null, null)

    /**
     * The pill's words, longest first. Cards ([dated]) say one fixed thing per state: "Available Now" / "Available";
     * "CAM Only" / "CAM"; not out yet: "Coming Nov 14" / "Nov 14" / "Soon", "Not Available Yet", "Coming Soon" / "Soon".
     * The title page's large pill keeps the detail: "Available Now · 4K HDR" ... "Available Now".
     */
    fun labels(e: AvailabilityAnswer, dated: Boolean): List<String> = when (e.status) {
        AvailabilityStatus.AVAILABLE -> {
            val res = resolution(e.best)
            if (dated || res == null) listOf(e.status.label, "Available") else {
                val dr = dynamicRange(e.best)
                val base = "${e.status.label} · $res"
                listOfNotNull(dr?.let { "$base $it" }, if (dr == "Dolby Vision") "$base HDR" else null, base, e.status.label)
            }
        }
        AvailabilityStatus.CAM -> listOf(e.status.label, "CAM")
        AvailabilityStatus.NONE -> if (dated) whenForms(e) else listOf(e.status.label, "Not Yet")
    }

    private fun whenForms(e: AvailabilityAnswer): List<String> {
        val note = e.note
        if (e.releaseAt > wall()) {
            val (date, short) = releases.cardDate(e.releaseAt)
            return listOf("Coming $date", short, "Soon").distinct()
        }
        if (note == "In Theaters Now") return listOf("Not Available Yet", "Not Yet")
        if (note != null && note.startsWith("Coming")) return listOf("Coming Soon", "Soon")
        return listOf(AvailabilityStatus.NONE.label, "Not Yet")
    }

    private fun resolution(best: String?): String? {
        val b = best?.uppercase() ?: return null
        return when {
            Regex("(?<![A-Z0-9])(2160P|4K|UHD)(?![A-Z0-9])").containsMatchIn(b) -> "4K"
            "1080P" in b -> "1080p"
            "720P" in b -> "720p"
            else -> null
        }
    }

    private fun dynamicRange(best: String?): String? {
        val b = best?.uppercase() ?: return null
        return when {
            Regex("(?<![A-Z0-9])(DV|DOVI|DOLBY VISION)(?![A-Z0-9])").containsMatchIn(b) -> "Dolby Vision"
            Regex("(?<![A-Z0-9])HDR").containsMatchIn(b) -> "HDR"
            else -> null
        }
    }

    // ---- freshness ----

    private fun hot(key: String): Boolean {
        val seen = shownAt[key]?.let { clock.monotonicMs() - it < 30 * 60_000L } == true
        val title = if (key.startsWith("tv:")) key.split(':').take(2).joinToString(":") else key
        return seen || title in releases.myListKeys()
    }

    private fun fresh(e: AvailabilityAnswer, hot: Boolean): Boolean {
        if (e.forced) return true
        val now = wall()
        if (e.releaseAt in (e.checkedAt + 1)..now) return false // released since: look again
        return now - e.checkedAt < when {
            e.status == AvailabilityStatus.AVAILABLE -> AVAILABLE_TTL
            hot -> HOT_TTL
            else -> OTHER_TTL
        }
    }

    // ---- asking ----

    override fun ask(targets: List<PlayTarget>, priority: Boolean) {
        for (t in (if (priority) targets.reversed() else targets)) want(t, front = priority, page = priority && targets.size == 1)
    }

    /**
     * Wants [t]'s answer: nothing when a fresh one exists; else queued ([front]: next in line). A title still ahead shows
     * its "Coming ..." pill at once (a stand-in, never fresh), then is looked up like any other.
     */
    fun want(t: PlayTarget, front: Boolean = false, page: Boolean = false, force: Boolean = false) {
        if (!mayHavePill(t) && !page) return
        synchronized(this.targets) { this.targets[t.key] = t }
        if (!sweeping) { sweeping = true; scope.launch { delay(LAUNCH_SWEEP_DELAY_MS); launchSweep(); sweepLoop() } }
        val now = wall()
        cache[t.key]?.let {
            val pageStale = page && !it.forced && now - it.checkedAt > if (it.status == AvailabilityStatus.AVAILABLE) PAGE_AVAILABLE_TTL else PAGE_TTL
            if (pageStale || (force && !it.forced)) pageWanted += t.key else if (fresh(it, hot(t.key))) return
        }
        failedAt[t.key]?.let { if (clock.monotonicMs() - it < FAILED_TTL) return }
        if (ahead(t) && cache[t.key] == null) {
            val date = t.airDate!!
            record(t, AvailabilityAnswer(AvailabilityStatus.NONE, null, "Coming " + releases.dateWords(date), 0L, releases.midnight(date)), quiet = true)
        }
        synchronized(queue) {
            if (t.key in queued) {
                if (front) { queue.removeAll { it.key == t.key }; queue.addFirst(t) }
            } else {
                if (front) queue.addFirst(t) else queue.addLast(t)
                queued += t.key
                while (queue.size > MAX_QUEUE) queued -= queue.removeLast().key
            }
            if (workers < WORKERS && workers < queue.size) { workers++; scope.launch { work() } }
        }
    }

    override suspend fun check(target: PlayTarget): AvailabilityAnswer? {
        withTimeoutOrNull(10_000) { check0(target) }
        return cache[target.key]
    }

    private suspend fun work() {
        var idleSince = clock.monotonicMs()
        while (true) {
            val t = synchronized(queue) {
                val n = queue.removeFirstOrNull()
                if (n == null && clock.monotonicMs() - idleSince > 30_000) { workers--; return }
                n?.also { queued -= it.key }
            }
            if (t == null) { delay(500); continue }
            while (true) {
                if (!paused) {
                    val go = synchronized(queue) { val n = clock.monotonicMs(); (n - lastStart >= GAP_MS).also { if (it) lastStart = n } }
                    if (go) break
                }
                delay(if (paused) 2_000 else 150)
            }
            val known = cache[t.key]
            val page = pageWanted.remove(t.key)
            if (known == null || page || !fresh(known, hot(t.key))) try { check0(t) } catch (e: Exception) { PlaybackLog.w("Avail: ${t.label}: ${e.javaClass.simpleName}") }
            idleSince = clock.monotonicMs()
        }
    }

    private suspend fun check0(t: PlayTarget) {
        if (!t.isEpisode && t.airDate != null && t.airDate < day(-RECENT_DAYS) && t.airDate >= day(-DIGITAL_LOOKBACK) && digitalRecent[t.tmdbId] == null) {
            digitalRecent[t.tmdbId] = releases.digitalDate(t.tmdbId)?.let { it >= day(-RECENT_DAYS) } == true
        }
        val sv = core.survey(t)
        if (sv == null) { failedAt[t.key] = clock.monotonicMs(); return }
        val status = when { sv.quality > 0 -> AvailabilityStatus.AVAILABLE; sv.cam > 0 -> AvailabilityStatus.CAM; else -> AvailabilityStatus.NONE }
        var note: String? = null
        var releaseAt = 0L
        if (status != AvailabilityStatus.AVAILABLE && !t.isEpisode) {
            // Where the movie is: a digital release ahead, in theaters, or still to come.
            val digital = releases.digitalDate(t.tmdbId)
            val date = t.airDate.orEmpty()
            note = when {
                digital != null && digital > day(0) -> { releaseAt = releases.midnight(digital); "Digital Release ${releases.dateWords(digital)}" }
                date.length == 10 && date > day(0) -> { releaseAt = releases.midnight(date); "Coming ${releases.dateWords(date)}" }
                date.length == 10 && date >= day(-120) && digital == null -> "In Theaters Now"
                else -> null
            }
        }
        if (status != AvailabilityStatus.AVAILABLE && t.isEpisode && ahead(t)) {
            // Not due yet and not up early: still says when it comes.
            releaseAt = releases.midnight(t.airDate!!)
            note = "Coming " + releases.dateWords(t.airDate)
        }
        if (status != AvailabilityStatus.NONE && ahead(t)) PlaybackLog.w("Avail: ${t.label} is out early (due ${t.airDate})")
        record(t, AvailabilityAnswer(status, if (status == AvailabilityStatus.AVAILABLE) sv.best else sv.bestCam, note, wall(), releaseAt))
        if (status == AvailabilityStatus.NONE && sv.total == 0) PlaybackLog.i("Avail: ${t.label}: the index has nothing yet")
    }

    /** Sets [t]'s answer (a check, or Dream Core found a stream: it's out) and tells the cards. */
    fun record(t: PlayTarget, e: AvailabilityAnswer, quiet: Boolean = false) {
        val old = cache[t.key]
        if (old?.forced == true && !e.forced) return
        cache[t.key] = e
        if (old?.status != e.status && !quiet) PlaybackLog.w("Avail: ${t.label} " + (old?.let { "${it.status.label} -> " } ?: "-> ") + e.status.label +
            listOfNotNull(e.best, e.note).joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" })
        _updates.tryEmit(t.key to e)
        saveSoon()
    }

    @Volatile private var savePending = false

    private fun saveSoon() {
        if (savePending) return
        savePending = true
        Background.after(5_000) {
            savePending = false
            val keep = cache.entries.sortedByDescending { it.value.checkedAt }.take(KEEP)
            if (keep.size < cache.size) cache.keys.retainAll(keep.map { it.key }.toSet())
            store.put(STORE_KEY, Json.encodeToString(MAP, keep.associate { it.key to it.value }))
        }
    }

    // ---- sweeps ----

    /** Once per launch: every kept CAM Only and released Not Available Yet title asked again (My List first, at most [LAUNCH_MAX]). */
    private fun launchSweep() {
        if (paused) return
        val now = wall()
        val myList = releases.myListKeys()
        val due = cache.entries.filter { (_, e) -> !e.forced && (e.status == AvailabilityStatus.CAM ||
            e.status == AvailabilityStatus.NONE && e.releaseAt <= now && (e.note?.startsWith("Coming ") != true || e.releaseAt in 1..now)) }
            .sortedWith(compareByDescending<Map.Entry<String, AvailabilityAnswer>> { (if (it.key.startsWith("tv:")) it.key.split(':').take(2).joinToString(":") else it.key) in myList }
                .thenByDescending { it.value.checkedAt }).take(LAUNCH_MAX)
        val list = due.mapNotNull { synchronized(targets) { targets[it.key] } }
        if (list.isNotEmpty()) PlaybackLog.w("Avail: launch sweep, ${list.size} CAM Only / not yet re-checked")
        list.forEach { want(it, force = true) }
    }

    /** Every [SWEEP_MS]: CAM Only / Not Available Yet titles in view or on My List, older than [HOT_TTL], asked again. */
    private suspend fun sweepLoop() {
        while (true) {
            delay(SWEEP_MS)
            if (paused) continue
            val list = synchronized(targets) { targets.values.toList() }
            val due = list.filter { t ->
                val e = cache[t.key]
                hot(t.key) && e != null && e.status != AvailabilityStatus.AVAILABLE && !fresh(e, hot = true) &&
                    failedAt[t.key]?.let { clock.monotonicMs() - it < FAILED_TTL } != true
            }.take(SWEEP_MAX)
            if (due.isNotEmpty()) PlaybackLog.i("Avail: re-checking ${due.size} CAM Only / Not Available Yet title(s) in view or on My List")
            due.forEach { want(it) }
        }
    }

    /**
     * An episode not due yet, for the title page (out-early rule): a source up before the listed day makes it playable.
     * A quick check (bounded by [check]), not repeated within [HOT_TTL] or [FAILED_TTL] after a failed one, never while
     * Dream Player plays (the kept answer only); at most 200 titles remembered.
     */
    override suspend fun earlyRelease(t: PlayTarget): Boolean {
        if (outEarly(t.key)) return true
        if (paused) return false
        earlyRunning[t.key]?.let { return it.await() } // the page and its season tab asking at once: one check
        val now = clock.monotonicMs()
        if (earlyAsked[t.key]?.let { now - it < HOT_TTL } == true) return false
        if (failedAt[t.key]?.let { now - it < FAILED_TTL } == true) return false
        val job = kotlinx.coroutines.CompletableDeferred<Boolean>()
        earlyRunning.putIfAbsent(t.key, job)?.let { return it.await() }
        try {
            if (earlyAsked.size >= 200) earlyAsked.clear()
            earlyAsked[t.key] = now
            synchronized(targets) { targets[t.key] = t } // the sweeps ask again while it's in view
            check(t)
            return outEarly(t.key).also { job.complete(it) }
        } finally {
            job.complete(false)
            earlyRunning.remove(t.key, job)
        }
    }

    private val earlyAsked = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val earlyRunning = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()
}
