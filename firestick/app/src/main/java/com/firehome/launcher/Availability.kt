package com.firehome.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Is it out yet? For new and upcoming titles, what the torrent index Dream Core uses actually has
 * ([NativeResolver.survey]: index only, nothing sent to a debrid service), as one of three statuses:
 *  - Available Now (green): a quality release (WEB-DL, WEBRip, BluRay, Remux, HDTV...) exists;
 *  - CAM Only (amber): only camera / telesync / telecine / screener grade releases;
 *  - Not Available Yet (red): not released, or nothing found. A note says when ("Coming Friday at
 *    9:00 PM", "In Theaters Now", "Digital Release Nov 14").
 *
 * Shown as a small pill ([Pill]: dark glass, a status disc with a glyph, a label in the status color; the user asked for colored text,
 * an allowed exception to the rainbow-only rule, like NOW PLAYING's green) on cards of movies released in
 * the last [RECENT_DAYS] days or still to come ([cardTarget]) and of new episodes, on the title page
 * ([TitleStatus]) and on Home's New Episode banner. Older catalog titles get no pill (assumed out).
 *
 * Only new titles: movies out in the last [RECENT_DAYS] days or still to come, shows with an episode out this
 * week or one announced ([attachTitle]); older catalog titles get no pill and no lookup.
 *
 * Cheap by design: unreleased titles are decided locally (no network); the rest are queued as their row is
 * built ([batch]) and go to [WORKERS] background workers (the quick check: two fast providers, short limits),
 * starts at least [GAP_MS] apart, never while something plays or starts; a focused card or an open title
 * page goes first. Results are kept per title
 * ([AVAILABLE_TTL] / [CAM_TTL] / [OTHER_TTL], sooner after a known release instant) in a small file. CAM Only /
 * Not Available Yet titles in view or on My List are asked again every [HOT_TTL] (the sweep, a few at a time), and
 * an open title page asks again past [PAGE_TTL], so a real release flips the pill soon. A result only updates the
 * pills that show that title (static views; a change crossfades with one glow pulse, nothing keeps running).
 *
 * Developer (PingReceiver): `--es avail force --ei movie <id> --es status available|cam|none [--es best "2160p WEB-DL HDR"]`, or
 * `--ei show <id> --ei season S --ei episode E`; `--es avail dump`; `--es avail clear`. Logs: "Avail: ...".
 */
object Availability {
    private const val TAG = "FireHome"
    private const val FILE = "availability.json"
    private const val RECENT_DAYS = 90
    private const val AVAILABLE_TTL = 24 * 60 * 60_000L
    private const val OTHER_TTL = 150 * 60_000L
    /** CAM Only (or out but Not Available Yet) titles nobody is looking at: asked again this often. */
    private const val CAM_TTL = 3 * 60 * 60_000L
    /** ...and while in view (a shown pill, an open title page) or on My List: so it flips to Available Now soon after a real release. */
    private const val HOT_TTL = 50 * 60_000L
    /** An open title page asks again when its answer (other than Available Now) is older than this. */
    private const val PAGE_TTL = 15 * 60_000L
    /** ...and an Available Now one this old (a 1080p that may have become 4K). */
    private const val PAGE_AVAILABLE_TTL = 3 * 60 * 60_000L
    /** The hot sweep: how often, and how many titles it may queue each time (never a flood). */
    private const val SWEEP_MS = 5 * 60_000L
    private const val SWEEP_MAX = 12
    /** The launch sweep: once per process, this long after the first ask, at most this many titles. */
    private const val LAUNCH_SWEEP_DELAY_MS = 25_000L
    private const val LAUNCH_MAX = 40
    /** An index that didn't answer: not asked again for this title for this long. */
    private const val FAILED_TTL = 90_000L
    /** Quick checks: this many at once, starts at least [GAP_MS] apart (no waiting for the remote to rest). */
    private const val WORKERS = 2
    private const val GAP_MS = 400L
    private const val MAX_QUEUE = 150
    private const val KEEP = 400

    /** [color]: the system status color (glyph disc, hairline, glow); [tint]: a lighter one for the label, legible on dark glass. */
    enum class Status(val label: String, val color: Int, val tint: Int) {
        AVAILABLE("Available Now", 0xFF30D158.toInt(), 0xFF6BEB8F.toInt()),
        CAM("CAM Only", 0xFFFFD60A.toInt(), 0xFFFFE266.toInt()),
        NONE("Not Available Yet", 0xFFFF453A.toInt(), 0xFFFF8178.toInt()),
    }

    /** "4K", "1080p", "720p" from a best release like "2160p WEB-DL"; null when unknown. */
    fun resolution(best: String?): String? {
        val b = best?.uppercase() ?: return null
        return when {
            Regex("(?<![A-Z0-9])(2160P|4K|UHD)(?![A-Z0-9])").containsMatchIn(b) -> "4K"
            "1080P" in b -> "1080p"
            "720P" in b -> "720p"
            else -> null
        }
    }

    /** "Dolby Vision" / "HDR" when the best release has it (by name); else null. */
    fun dynamicRange(best: String?): String? {
        val b = best?.uppercase() ?: return null
        return when {
            Regex("(?<![A-Z0-9])(DV|DOVI|DOLBY VISION)(?![A-Z0-9])").containsMatchIn(b) -> "Dolby Vision"
            Regex("(?<![A-Z0-9])HDR").containsMatchIn(b) -> "HDR"
            else -> null
        }
    }

    /**
     * The pill's words for [e], longest first (a narrow card takes the first that fits; then the words without
     * the status disc; nothing fits: no pill, never the disc alone). Cards ([dated]) say one fixed thing per state, the same length everywhere:
     * "Available Now" / "Available"; "CAM Only" / "CAM"; not out yet [whenForms]: "Coming Nov 14" / "Nov 14" /
     * "Soon", "Not Available Yet", "Coming Soon" / "Soon". The title page's large pill (not [dated]) keeps the detail:
     * "Available Now · 4K HDR" ... "Available Now"; "CAM Only"; "Not Available Yet" (its note says when).
     */
    fun labels(e: Entry, dated: Boolean = true): List<String> = when (e.status) {
        Status.AVAILABLE -> {
            val res = resolution(e.best)
            if (dated || res == null) listOf(e.status.label, "Available")
            else {
                val dr = dynamicRange(e.best)
                val base = "${e.status.label} · $res"
                listOfNotNull(dr?.let { "$base $it" }, if (dr == "Dolby Vision") "$base HDR" else null, base, e.status.label)
            }
        }
        Status.CAM -> listOf(e.status.label, "CAM")
        Status.NONE -> if (dated) whenForms(e) else listOf(e.status.label, "Not Yet")
    }

    /**
     * A not-yet-out title's card words, longest first, all in the viewer's zone (DreamTime): a known day ahead
     * (a release, a premiere, an episode's air time): "Coming Nov 14", "Nov 14", "Soon" ("Coming Nov 14, 2027",
     * "Nov 2027" past this year); a year only: "Coming 2027", "2027"; in theaters with nothing found: "Not Available Yet"; else "Coming Soon", "Soon".
     */
    fun whenForms(e: Entry, now: Long = System.currentTimeMillis()): List<String> {
        val note = e.note
        val at = e.releaseAt
        if (at > now) {
            val d = Date(at)
            val thisYear = DreamTime.fmt("yyyy").format(d) == DreamTime.fmt("yyyy").format(Date(now))
            val date = DreamTime.fmt(if (thisYear) "MMM d" else "MMM d, yyyy").format(d)
            val short = if (thisYear) date else DreamTime.fmt("MMM yyyy").format(d)
            return listOf("Coming $date", short, "Soon").distinct()
        }
        // In theaters with no copy found: it's out, just not watchable at home yet (the row already says In Theaters).
        if (note == "In Theaters Now") return listOf("Not Available Yet", "Not Yet")
        if (at == 0L && note != null && note.startsWith("Coming ") && note.length <= 12) return listOf(note, note.removePrefix("Coming "), "Soon").distinct()
        // Not out and no date known (an upcoming episode "Date to Be Announced"): "Coming Soon". Out (its day passed, or
        // the checker found nothing for a released title): "Not Available Yet".
        if (at == 0L && (note == Upcoming.TBA || note?.startsWith("Coming") == true)) return listOf("Coming Soon", "Soon")
        return listOf(Status.NONE.label, "Not Yet")
    }

    /**
     * One title's answer. [note]: when it comes / where it is ("In Theaters Now"); [releaseAt]: a known release
     * instant ahead (re-checked right after); [exact]: [releaseAt] is a real air time (an episode), not a day's start.
     */
    class Entry(val status: Status, val best: String?, val note: String?, val checkedAt: Long, val releaseAt: Long = 0, val forced: Boolean = false,
                val exact: Boolean = false) {
        fun toJson(key: String): JSONObject = JSONObject().put("k", key).put("s", status.name).put("b", best ?: "").put("n", note ?: "")
            .put("at", checkedAt).put("r", releaseAt).put("f", forced).put("x", exact)

        companion object {
            fun from(o: JSONObject) = try {
                Entry(Status.valueOf(o.optString("s")), o.optString("b").ifEmpty { null }, o.optString("n").ifEmpty { null },
                    o.optLong("at"), o.optLong("r"), o.optBoolean("f"), o.optBoolean("x"))
            } catch (e: Exception) { null }
        }
    }

    /**
     * A title to check. [releaseAt]: the exact instant (an episode, TVmaze) or 0; [date]: its release / air
     * day "yyyy-MM-dd" ("" unknown). Built by [movie], [episode], [cardTarget].
     */
    class Target(val key: String, val request: PlaybackRequest, val releaseAt: Long, val date: String)

    fun movie(t: Title) = Target("movie:${t.id}", PlaybackRequest(title = t.name, type = "movie", tmdbId = t.id, year = t.year.take(4).toIntOrNull()), 0, t.date)

    fun episode(showId: Int, show: String, imdb: String?, season: Int, number: Int, at: Long, date: String) = Target(
        "tv:$showId:$season:$number",
        PlaybackRequest(title = show, type = "episode", tmdbId = showId, imdb = imdb, season = season, episode = number), at, date)

    fun episode(i: NewEpisodes.Item) = episode(i.show.id, i.show.name, i.show.imdb, i.ep.season, i.ep.number, i.ep.at, i.ep.airDate)

    /** A row card's title worth a pill: a movie out in the last [RECENT_DAYS] days or still to come. Cheap. */
    fun cardTarget(t: Title): Target? {
        if (t.type != "movie" || t.date.length != 10) return null
        if (t.date < day(-RECENT_DAYS)) return null
        return movie(t)
    }

    /**
     * How far back a movie's main date may be and still have a recent digital release (theaters to digital usually
     * takes 17-120 days). STANDARDS §5: a movie is new for ~90 days after its digital OR theatrical release, so one in
     * theaters months ago but digital only recently (Jul 29 / Oct 6) keeps its pill.
     */
    private const val DIGITAL_LOOKBACK = 240
    /** Movie id to its digital release day ("" = none), asked once per launch. */
    private val digitalDays = ConcurrentHashMap<Int, String>()

    /** A movie past [cardTarget]'s window whose digital release could still be recent: worth one release-dates lookup. */
    private fun mayBeDigitalNew(t: Title) =
        t.type == "movie" && t.date.length == 10 && t.date < day(-RECENT_DAYS) && t.date >= day(-DIGITAL_LOOKBACK)

    private fun recentDigital(d: String) = d.length == 10 && d >= day(-RECENT_DAYS)

    /** [t]'s target when its digital release is recent or ahead; null otherwise. Blocking (one cached TMDB call). */
    private fun digitalTarget(app: Context, t: Title): Target? {
        if (!mayBeDigitalNew(t)) return null
        val d = digitalDays[t.id] ?: (try { digitalDate(app, t.id) } catch (e: Exception) { null }).orEmpty().also { digitalDays[t.id] = it }
        return if (recentDigital(d)) movie(t) else null
    }

    // ---- cache ----

    private val cache = ConcurrentHashMap<String, Entry>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    @Volatile private var loaded = false
    private val fileLock = Any()

    private fun ensure(ctx: Context) {
        if (loaded) return
        synchronized(fileLock) {
            if (loaded) return
            try {
                val f = File(ctx.applicationContext.filesDir, FILE)
                if (f.exists()) {
                    val a = JSONArray(f.readText())
                    for (i in 0 until a.length()) a.optJSONObject(i)?.let { o -> Entry.from(o)?.let { cache[o.optString("k")] = it } }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Avail: cache unreadable ($e)")
            }
            loaded = true
        }
    }

    private fun save(ctx: Context) = synchronized(fileLock) {
        try {
            val keep = cache.entries.sortedByDescending { it.value.checkedAt }.take(KEEP)
            if (keep.size < cache.size) { val ks = keep.map { it.key }.toSet(); cache.keys.retainAll(ks) }
            val a = JSONArray().apply { keep.forEach { put(it.value.toJson(it.key)) } }
            val dir = ctx.applicationContext.filesDir
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(a.toString())
            tmp.renameTo(File(dir, FILE))
        } catch (e: Exception) {
            Log.w(TAG, "Avail: cache not saved ($e)")
        }
    }

    /** The last answer for [key] (fresh or not; a stale one shows until the new one lands). Cheap after the first call (file read once). */
    fun peek(ctx: Context, key: String): Entry? { ensure(ctx); return cache[key] }

    /** [hot]: the title is in view or on My List: a CAM Only / Not Available Yet answer goes stale sooner ([HOT_TTL]). */
    private fun fresh(e: Entry, now: Long = System.currentTimeMillis(), hot: Boolean = false): Boolean {
        if (e.forced) return true
        if (e.releaseAt in (e.checkedAt + 1)..now) return false // released since: look again
        return now - e.checkedAt < when {
            e.status == Status.AVAILABLE -> AVAILABLE_TTL
            hot -> HOT_TTL
            e.status == Status.CAM -> CAM_TTL
            else -> OTHER_TTL
        }
    }

    // ---- in view / hot (pills on screen, My List) ----

    /** Keys of attached pills (cards, the banner, the title page) and how many show each. Main thread writes. */
    private val inView = ConcurrentHashMap<String, Int>()
    /** My List's titles' keys ("movie:12" / "tv:34"), refreshed by the sweep. */
    @Volatile private var myListKeys: Set<String> = emptySet()
    /** Every title asked about lately, so the sweep can ask again (bounded, most recent last). */
    private val targets = object : LinkedHashMap<String, Target>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Target>?) = size > KEEP
    }
    /** Keys an open title page wants looked up now even though the answer is still fresh by the normal clock. */
    private val pageWanted = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var sweeping = false

    internal fun shown(key: String, on: Boolean) {
        if (on) inView.merge(key, 1, Int::plus) else inView.computeIfPresent(key) { _, n -> if (n <= 1) null else n - 1 }
    }

    /** "movie:12" -> "movie:12"; "tv:34:2:5" -> "tv:34" (My List holds the show). */
    private fun titleKey(key: String) = if (key.startsWith("tv:")) key.split(':').take(2).joinToString(":") else key

    private fun hot(key: String) = inView.containsKey(key) || titleKey(key) in myListKeys

    /**
     * Every [SWEEP_MS]: CAM Only / Not Available Yet titles in view or on My List whose answer is older than
     * [HOT_TTL] go back in the queue (at most [SWEEP_MAX], in view first), so a real release flips their pills
     * soon. The worker still asks one at a time, idle only, [GAP_MS] apart.
     */
    private fun sweep(app: Context) {
        // Dream Player in front: nothing this round (playback keeps the stick to itself); the next one comes as usual.
        if (DreamPlayerActivity.isActive()) { main.postDelayed({ sweep(app) }, SWEEP_MS); return }
        Net.run {
            try {
                myListKeys = Tmdb.myList(app).map { it.key }.toHashSet()
                val now = System.currentTimeMillis()
                val list = synchronized(targets) { targets.values.toList() }
                val due = list.filter { t ->
                    val e = cache[t.key]
                    val notOut = if (t.releaseAt > 0) t.releaseAt > now else t.date.length == 10 && t.date > day(0) // answered locally anyway
                    !notOut && hot(t.key) && e != null && e.status != Status.AVAILABLE && !fresh(e, now, hot = true) &&
                        failedAt[t.key]?.let { now - it < FAILED_TTL } != true
                }.sortedByDescending { inView.containsKey(it.key) }.take(SWEEP_MAX)
                if (due.isNotEmpty()) Log.w(TAG, "Avail: re-checking ${due.size} CAM Only / Not Available Yet title(s) in view or on My List")
                due.forEach { want(app, it) }
            } catch (e: Exception) {
                Log.w(TAG, "Avail: sweep failed ($e)")
            }
        }
        main.postDelayed({ sweep(app) }, SWEEP_MS)
    }

    /**
     * Once per launch, [LAUNCH_SWEEP_DELAY_MS] after the first ask (Home has settled): every cached CAM Only and
     * released Not Available Yet title is asked again whatever its age (My List and on-screen first, at most
     * [LAUNCH_MAX]; the rest keep the normal cadence), so stale pills flip to Available Now right away. Same
     * worker rules (idle only, one at a time, [GAP_MS] apart). Then the regular [sweep] every [SWEEP_MS].
     */
    private fun launchSweep(app: Context) {
        // Started while something plays: it waits until playback is over (checked again every minute).
        if (DreamPlayerActivity.isActive()) { main.postDelayed({ launchSweep(app) }, 60_000L); return }
        Net.run {
            try {
                myListKeys = Tmdb.myList(app).map { it.key }.toHashSet()
                val now = System.currentTimeMillis()
                val due = cache.entries.filter { (k, e) ->
                    !e.forced && failedAt[k]?.let { now - it < FAILED_TTL } != true && (e.status == Status.CAM ||
                        // Not Available Yet but out (in theaters, or its day has come): not a locally answered "Coming ...".
                        e.status == Status.NONE && e.releaseAt <= now && (e.note?.startsWith("Coming ") != true || e.releaseAt in 1..now))
                }.sortedWith(compareByDescending<Map.Entry<String, Entry>> { titleKey(it.key) in myListKeys }
                    .thenByDescending { inView.containsKey(it.key) }.thenByDescending { it.value.checkedAt })
                    .take(LAUNCH_MAX)
                val cams = due.count { it.value.status == Status.CAM }
                Log.w(TAG, "Avail: launch sweep, $cams CAM Only / ${due.size - cams} not yet re-checked")
                for ((k, _) in due) {
                    val t = synchronized(targets) { targets[k] } ?: rebuild(app, k) ?: continue
                    want(app, t, force = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Avail: launch sweep failed ($e)")
            }
        }
        main.postDelayed({ sweep(app) }, SWEEP_MS)
    }

    /** A [Target] for a cached [key] no card has asked about this launch, from TMDB's details (cached). Blocking. */
    private fun rebuild(app: Context, key: String): Target? {
        val p = key.split(':')
        val id = p.getOrNull(1)?.toIntOrNull() ?: return null
        return when {
            p[0] == "movie" -> WatchHistory.details(app, "movie", id)?.let { d ->
                val name = d.optString("title").takeIf { it.isNotEmpty() } ?: return null
                val date = d.optString("release_date")
                Target(key, PlaybackRequest(title = name, type = "movie", tmdbId = id, year = date.take(4).toIntOrNull()), 0, date)
            }
            p[0] == "tv" && p.size == 4 -> WatchHistory.details(app, "tv", id)?.let { d ->
                val name = d.optString("name").takeIf { it.isNotEmpty() } ?: return null
                val imdb = d.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") }
                episode(id, name, imdb, p[2].toIntOrNull() ?: return null, p[3].toIntOrNull() ?: return null, 0, "")
            }
            else -> null
        }
    }

    // ---- asking ----

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Target>()
    private val queued = HashSet<String>()
    /** Running workers and the last lookup's start (both under the queue's lock). */
    private var workers = 0
    private var lastStart = 0L

    /** A row's new titles queued together ([batch]): logged once all are answered. */
    private class Batch(val name: String) {
        val keys: MutableSet<String> = ConcurrentHashMap.newKeySet()
        @Volatile var total = 0
        val t0 = SystemClock.elapsedRealtime()
    }
    /** The batch being built (main thread only), and those waiting for answers. */
    private var building: Batch? = null
    private val open = java.util.concurrent.CopyOnWriteArrayList<Batch>()

    /**
     * Runs [make] (a row's cards, main thread); the titles it queues for a lookup form one batch, logged when
     * all are answered: "Avail: row 'Top 10 Today' 4 new titles queued, answered in 1.3 s".
     */
    fun <T> batch(name: String, make: () -> T): T {
        val b = Batch(name)
        val prev = building
        building = b
        try {
            return make()
        } finally {
            building = prev
            b.total = b.keys.size
            if (b.total > 0) open += b
        }
    }

    /** [key]'s lookup ended (answered or failed): closes the batches waiting for it. */
    private fun answered(key: String) {
        for (b in open) if (b.keys.remove(key) && b.keys.isEmpty()) {
            open.remove(b)
            Log.w(TAG, "Avail: row '${b.name}' ${b.total} new title${if (b.total == 1) "" else "s"} queued, answered in ${
                String.format(Locale.US, "%.1f s", (SystemClock.elapsedRealtime() - b.t0) / 1000.0)}")
        }
    }

    /**
     * Wants [t]'s status: nothing when a fresh answer exists; an unreleased title is answered at once (no
     * network); else queued for the worker ([front]: next in line: a focused card, an open title page).
     * [page]: an open title page: asks again when the answer is older than [PAGE_TTL] (Available Now: [PAGE_AVAILABLE_TTL]).
     */
    fun want(ctx: Context, t: Target, front: Boolean = false, page: Boolean = false, force: Boolean = false) {
        val app = ctx.applicationContext
        if (!Tmdb.hasKey(app)) return
        ensure(app)
        synchronized(targets) {
            targets[t.key] = t
            if (!sweeping) { sweeping = true; main.postDelayed({ launchSweep(app) }, LAUNCH_SWEEP_DELAY_MS) } // once per launch, then sweep() every SWEEP_MS
        }
        val now = System.currentTimeMillis()
        cache[t.key]?.let {
            val pageStale = page && !it.forced && now - it.checkedAt > if (it.status == Status.AVAILABLE) PAGE_AVAILABLE_TTL else PAGE_TTL
            // [force]: the launch sweep asks again whatever the answer's age.
            if (pageStale || force && !it.forced) pageWanted += t.key else if (fresh(it, now, hot(t.key))) return
        }
        failedAt[t.key]?.let { if (now - it < FAILED_TTL) return }
        // Not out yet: no lookup needed.
        val ahead = if (t.releaseAt > 0) t.releaseAt > now else t.date.length == 10 && t.date > day(0)
        if (ahead) {
            val note = "Coming " + if (t.releaseAt > 0) AirTimes.label(t.releaseAt) else dateWords(t.date)
            record(app, t.key, Entry(Status.NONE, null, note, now, if (t.releaseAt > 0) t.releaseAt else midnight(t.date), exact = t.releaseAt > 0),
                t.request.title, quiet = true)
            return
        }
        synchronized(queue) {
            if (t.key in queued) {
                if (front) { queue.removeAll { it.key == t.key }; queue.addFirst(t) }
            } else {
                if (front) queue.addFirst(t) else queue.addLast(t)
                queued += t.key
                while (queue.size > MAX_QUEUE) queued -= queue.removeLast().key
            }
            if (Looper.myLooper() == Looper.getMainLooper()) building?.keys?.add(t.key)
            if (workers < WORKERS && workers < queue.size) {
                workers++
                Thread({ work(app) }, "availability").apply { isDaemon = true; start() }
            }
        }
    }

    /** Sets [key]'s answer from elsewhere (Instant Play found a stream: it's out) and tells its pills. */
    fun record(ctx: Context, key: String, e: Entry, name: String? = null, quiet: Boolean = false) {
        val app = ctx.applicationContext
        ensure(app)
        val old = cache[key]
        if (old?.forced == true && !e.forced) return
        cache[key] = e
        val who = name ?: synchronized(targets) { targets[key] }?.let { t ->
            t.request.let { r -> if (r.type == "episode") "${r.title} S${r.season}E${r.episode}" else r.title }
        } ?: key
        if (old?.status != e.status && !quiet) Log.w(TAG, "Avail: $who " + (old?.let { "${it.status.label} -> " } ?: "-> ") + e.status.label +
            listOfNotNull(e.best, e.note).joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" })
        saveSoon(app)
        if (old == null || old.status != e.status || old.note != e.note || labels(old) != labels(e)) main.post { notifyKey(key, e) }
        if (old != null && old.status != Status.AVAILABLE && e.status == Status.AVAILABLE && !quiet) nowAvailable(app, key, who, e)
    }

    /**
     * A My List movie went from CAM Only / Not Available Yet to Available Now: one notice ("Now Available in 4K ·
     * Wicked For Good"). DreamNotices holds its banner while Dream Player is in front, so never during playback.
     */
    private fun nowAvailable(app: Context, key: String, name: String, e: Entry) {
        if (!key.startsWith("movie:")) return // a followed show's new episode has its own notice (NewEpisodeBanner)
        Net.run {
            try {
                if (Tmdb.myList(app).none { it.key == key }) return@run
                val res = resolution(e.best)
                val title = "Now Available" + (res?.let { " in $it" } ?: "")
                // A forced (developer) flip: a one-off key, not kept, so the real one still comes later.
                val nk = if (e.forced) "avail:test:$key:${System.currentTimeMillis()}" else "avail:$key"
                if (DreamNotices.post(app, nk, DreamNotices.TYPE_EPISODES, title, name, store = !e.forced)) Log.w(TAG, "Avail: notice \"$title · $name\"")
            } catch (x: Exception) {
                Log.w(TAG, "Avail: notice failed ($x)")
            }
        }
    }

    @Volatile private var savePending = false

    /** One write a few seconds after a burst of answers. */
    private fun saveSoon(app: Context) {
        if (savePending) return
        savePending = true
        main.postDelayed({ savePending = false; Net.run { save(app) } }, 5_000)
    }

    /**
     * Quick checks run while browsing, but not in the middle of a scroll (each is ~1 s of scraper work that
     * competed with drawing the scroll on this 4-core stick): they wait until the remote has rested [REST_MS].
     * Never while something plays or a Play resolves.
     */
    private fun free(): Boolean = !DreamPlayerActivity.isActive() && PreResolve.sinceKeyMs() >= REST_MS &&
        PlayFlow.session?.let { !it.done && !it.cancelled && it.failure == null } != true
    private const val REST_MS = 800L

    private fun work(app: Context) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        var emptySince = SystemClock.elapsedRealtime()
        while (true) {
            val t = synchronized(queue) {
                val n = queue.removeFirstOrNull()
                if (n == null && SystemClock.elapsedRealtime() - emptySince > 30_000) { workers--; return }
                n?.also { queued -= it.key }
            }
            if (t == null) { Thread.sleep(500); continue }
            // Starts spaced [GAP_MS] apart across the workers (the index is shared with Instant Play).
            while (true) {
                if (free()) {
                    val go = synchronized(queue) {
                        val now = SystemClock.elapsedRealtime()
                        (now - lastStart >= GAP_MS).also { if (it) lastStart = now }
                    }
                    if (go) break
                }
                // Waiting out playback: a slow poll (no 150 ms wake-ups next to the decoder and the Bluetooth encoder).
                Thread.sleep(if (DreamPlayerActivity.isActive()) 2_000L else 150L)
            }
            val known = cache[t.key]
            val page = pageWanted.remove(t.key)
            if (known != null && !page && fresh(known, hot = hot(t.key))) { answered(t.key); emptySince = SystemClock.elapsedRealtime(); continue }
            try { check(app, t) } catch (e: Exception) { Log.w(TAG, "Avail: ${t.request.title}: $e") }
            answered(t.key)
            emptySince = SystemClock.elapsedRealtime()
        }
    }

    private fun check(app: Context, t: Target) {
        val r = t.request
        val name = if (r.type == "episode") "${r.title} S${r.season}E${r.episode}" else r.title
        val sv = NativeResolver.survey(app, r)
        if (sv == null) { failedAt[t.key] = System.currentTimeMillis(); return }
        val status = when { sv.quality > 0 -> Status.AVAILABLE; sv.cam > 0 -> Status.CAM; else -> Status.NONE }
        var note: String? = null
        var releaseAt = 0L
        if (status != Status.AVAILABLE && r.type == "movie") {
            // Where the movie is: a digital release ahead, in theaters, or still to come.
            val digital = digitalDate(app, r.tmdbId)
            val date = t.date.ifEmpty { WatchHistory.details(app, "movie", r.tmdbId)?.optString("release_date").orEmpty() }
            note = when {
                digital != null && digital > day(0) -> { releaseAt = midnight(digital); "Digital Release ${dateWords(digital)}" }
                date.length == 10 && date > day(0) -> { releaseAt = midnight(date); "Coming ${dateWords(date)}" }
                date.length == 10 && date >= day(-120) && digital == null -> "In Theaters Now"
                else -> null
            }
        }
        record(app, t.key, Entry(status, if (status == Status.AVAILABLE) sv.best else sv.bestCam, note, System.currentTimeMillis(), releaseAt), name)
        if (status == Status.NONE && sv.total == 0) Log.w(TAG, "Avail: $name: the index has nothing yet")
    }

    /** The movie's US digital release day (TMDB release_dates type 4), else the earliest anywhere; null if none. */
    private fun digitalDate(app: Context, id: Int): String? {
        val res = WatchHistory.tmdb(app, "/movie/$id/release_dates")?.optJSONArray("results") ?: return null
        fun digitalIn(o: JSONObject?) = o?.optJSONArray("release_dates")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optInt("type") == 4 }.map { it.optString("release_date").take(10) }.minOrNull()
        }
        val all = (0 until res.length()).mapNotNull { res.optJSONObject(it) }
        return digitalIn(all.firstOrNull { it.optString("iso_3166_1") == "US" }) ?: all.mapNotNull { digitalIn(it) }.minOrNull()
    }

    // ---- who listens (main thread) ----

    /** A view showing some title's status. */
    interface Watcher { fun availChanged(key: String, e: Entry) }

    private val watchers = WeakHashMap<Watcher, String>()

    fun watch(w: Watcher, key: String) { watchers[w] = key }
    fun unwatch(w: Watcher) { watchers.remove(w) }

    private fun notifyKey(key: String, e: Entry) {
        for ((w, k) in watchers.entries.toList()) if (k == key) w.availChanged(key, e)
    }

    /** Card pills that follow their title's Watched state ([Watched]): re-read when the ledger changes. */
    private val watchedPills = WeakHashMap<Pill, Title>()

    private fun followWatched(pill: Pill, t: Title) { watchedPills[pill] = t }

    /** Main thread, from [Watched]: a title finished or a show's air state moved; every following pill redoes its look. */
    internal fun watchedChanged() {
        for ((p, t) in watchedPills.entries.toList()) p.watched(Watched.title(p.context, t))
    }

    /** The gold Watched pill at the bottom-left of [frame] (a title page's watched episode card). */
    fun attachWatched(frame: FrameLayout) {
        place(frame, null, Pill(frame.context).also { it.watched(true) })
    }

    /**
     * A pill on [card] (bottom-left of its art, clear of the top-left badges) for [t], filled from the cache
     * now and from the worker later; focusing the card moves its check to the front.
     */
    fun attach(card: CinemaCard, t: Target) {
        val ctx = card.context
        want(ctx, t) // an unreleased title is answered right here, so its pill shows on the first frame
        val pill = Pill(ctx).also { it.bind(t.key, peek(ctx, t.key)) }
        place(card.frame, card.statusSlot, pill)
        card.onFocus { has -> if (has) want(ctx, t, front = true) }
    }

    /**
     * Bottom-left of the art ([frame]), inset 8 dp from its edges; a card that writes text over its art (the
     * Spotlight) passes its text column's [slot] instead, so the pill never covers words or a logo.
     */
    private fun place(frame: FrameLayout, slot: LinearLayout?, pill: Pill) {
        val ctx = frame.context
        if (slot != null) slot.addView(pill, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = Ui.dp(ctx, 14) })
        else frame.addView(pill, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            val m = Ui.dp(ctx, 8); setMargins(m, m, m, m)
        })
    }

    /** [t]'s pill at the bottom-left of [frame] (a title page's episode card); returns the focus hook (its check to the front). */
    fun attachTarget(frame: FrameLayout, t: Target): (Boolean) -> Unit {
        val ctx = frame.context
        want(ctx, t)
        place(frame, null, Pill(ctx).also { it.bind(t.key, peek(ctx, t.key)) })
        return { has -> if (has) want(ctx, t, front = true) }
    }

    /** Any row / grid / search card for [t]: a new title's pill ([attachTitle]); focusing it moves its check to the front. */
    fun attachTitle(card: CinemaCard, t: Title) {
        attachTitle(card.frame, t, card.statusSlot)?.let { card.onFocus(it) }
    }

    /**
     * A pill on a title card's art [frame] (or in its text column [slot]), only for a new title: a movie out in the
     * last [RECENT_DAYS] days or still to come ([cardTarget]); a show with an episode out in the last week (that
     * episode's status) or one announced ([showTarget]: "Coming Fri · 9 PM", "Season 3 · Mar 4"). Anything else:
     * no pill and no lookup. Returns the card's focus hook (a movie's check moves to the front), or null.
     */
    fun attachTitle(frame: FrameLayout, t: Title, slot: LinearLayout? = null): ((Boolean) -> Unit)? {
        val ctx = frame.context
        // Watched (a finished movie, a show caught up with nothing new): the gold Watched pill instead, on the first
        // frame from the local ledger, and no availability check for it. A watched show's air state is asked again when due.
        if (Watched.title(ctx, t)) {
            place(frame, slot, Pill(ctx).also { followWatched(it, t); it.watched(true) })
            if (t.type == "tv") Watched.checkShow(ctx, t.id)
            return null
        }
        cardTarget(t)?.let { target ->
            want(ctx, target)
            val pill = Pill(ctx).also { it.bind(target.key, peek(ctx, target.key)) }
            followWatched(pill, t)
            place(frame, slot, pill)
            return { has -> if (has) want(ctx, target, front = true) }
        }
        // A show: only a brand-new one (first aired in the last [NEW_SHOW_DAYS] days, or not premiered yet). A long-running
        // show with a new season gets no pill anywhere; its next episode's date lives on its page. Either way an empty
        // (gone) pill follows the ledger, so a title finished while its card is up shows Watched there.
        // A movie out in theaters months ago but on digital only recently: its pill once its release dates say so.
        if (mayBeDigitalNew(t) && Tmdb.hasKey(ctx)) {
            val pill = Pill(ctx)
            followWatched(pill, t)
            place(frame, slot, pill)
            var target: Target? = null
            fun bind(tg: Target) { target = tg; want(ctx, tg); pill.bind(tg.key, peek(ctx, tg.key)) }
            val known = digitalDays[t.id]
            if (known != null) { if (recentDigital(known)) bind(movie(t)) }
            else pill.whenShown {
                val app = ctx.applicationContext
                Net.background({ digitalTarget(app, t) }) { tg -> if (tg != null) bind(tg) }
            }
            return { has -> if (has) target?.let { want(ctx, it, front = true) } }
        }
        if (t.type != "tv" || !Tmdb.hasKey(ctx) || t.date.length != 10 || t.date < day(-NEW_SHOW_DAYS)) {
            place(frame, slot, Pill(ctx).also { followWatched(it, t) })
            return null
        }
        val pill = Pill(ctx)
        followWatched(pill, t)
        place(frame, slot, pill)
        val known = shows[t.id]
        if (known != null && System.currentTimeMillis() - known.at < SHOW_TTL) { known.target?.let { bindShow(pill, it, known.upcoming) }; return null }
        // Once the card is on screen: one TMDB details call (cached), the episode's own check only for one out this week.
        pill.whenShown {
            val app = ctx.applicationContext
            Net.background({ showTarget(app, t) }) { s ->
                val d = s ?: ShowPick(null, false)
                shows[t.id] = d
                d.target?.let { bindShow(pill, it, d.upcoming) }
            }
        }
        return null
    }

    /** A show card's pick: [target] the episode its pill follows (null: none), [upcoming] answered here (no lookup). */
    private class ShowPick(val target: Target?, val upcoming: Boolean, val at: Long = System.currentTimeMillis())
    private val shows = ConcurrentHashMap<Int, ShowPick>()
    private const val SHOW_TTL = 60 * 60_000L
    /** A show is brand new for this long after its premiere (its card may carry a pill). */
    private const val NEW_SHOW_DAYS = 365
    /** An aired episode carries a status pill (title page list, show card, New Episode banner) this long after it aired. */
    const val EPISODE_DAYS = 21

    /** An episode aired in the last [EPISODE_DAYS] days ([at]: its instant, else its day [date]). */
    fun recentEpisode(at: Long, date: String, now: Long = System.currentTimeMillis()): Boolean = when {
        at > 0 -> at <= now && now - at <= EPISODE_DAYS * 86_400_000L
        date.length == 10 -> date <= day(0) && date >= day(-EPISODE_DAYS)
        else -> false
    }

    private fun bindShow(pill: Pill, target: Target, upcoming: Boolean) {
        val ctx = pill.context
        pill.bind(target.key, peek(ctx, target.key))
        if (!upcoming) want(ctx, target)
    }

    /**
     * Blocking. What a brand-new show's card pill follows. Premiered: its latest episode when it aired in the last
     * [EPISODE_DAYS] days (checked like a New Episodes card), else nothing (never a red date pill on a show that's
     * out: the next episode's date is the title page's). Not premiered yet: its premiere (recorded here as Not
     * Available Yet with its time: TVmaze's exact one when already known, AirTimes.peek, no fetch).
     */
    private fun showTarget(app: Context, t: Title): ShowPick? {
        val d = WatchHistory.details(app, "tv", t.id) ?: return null
        val air = AirTimes.peek(t.id)
        val imdb = d.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") }
        val now = System.currentTimeMillis()
        val first = d.optString("first_air_date").takeIf { it.length == 10 } ?: t.date
        if (first < day(-NEW_SHOW_DAYS)) return ShowPick(null, false)
        val last = NewEpisodes.Ep.fromTmdb(d.optJSONObject("last_episode_to_air"))?.let { e ->
            air?.prev?.takeIf { e.same(it.season, it.number) }?.let { e.withAt(it.at) } ?: e
        }
        if (first <= day(0)) {
            if (last != null && recentEpisode(last.at, last.airDate, now))
                return ShowPick(episode(t.id, t.name, imdb, last.season, last.number, last.at, last.airDate), false)
            return ShowPick(null, false)
        }
        val next = NewEpisodes.Ep.fromTmdb(d.optJSONObject("next_episode_to_air"))?.let { e ->
            air?.next?.takeIf { e.same(it.season, it.number) }?.let { e.withAt(it.at) } ?: e
        }
        var season = 0; var number = 0; var at = 0L; var date = ""
        if (next != null && !next.aired(now) && (next.at > 0 || next.airDate.length == 10)) {
            season = next.season; number = next.number; at = next.at; date = next.airDate
        } else {
            // An announced season with a premiere day ahead (no episode listed yet).
            val a = d.optJSONArray("seasons")
            val today = day(0)
            val s = a?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i) } }
                ?.filter { it.optInt("season_number") > (last?.season ?: 0) && it.optString("air_date").let { x -> x.length == 10 && x > today } }
                ?.minByOrNull { it.optInt("season_number") } ?: return ShowPick(null, false)
            season = s.optInt("season_number"); number = 1; date = s.optString("air_date")
        }
        val target = episode(t.id, t.name, imdb, season, number, at, date)
        val releaseAt = if (at > 0) at else midnight(date)
        val note = if (number == 1) "Season $season" else "Coming " + if (at > 0) AirTimes.label(at) else dateWords(date)
        ensure(app)
        val old = cache[target.key]
        if (old == null || old.status != Status.NONE || old.releaseAt != releaseAt || old.note != note)
            record(app, target.key, Entry(Status.NONE, null, note, now, releaseAt, exact = at > 0), "${t.name} S${season}E$number", quiet = true)
        return ShowPick(target, true)
    }

    // ---- developer ----

    /** [best]: the release to pretend was found ("2160p WEB-DL HDR"), so the pill shows its quality; default "forced". */
    fun force(ctx: Context, key: String, status: Status, best: String? = null) {
        record(ctx, key, Entry(status, best?.takeIf { it.isNotBlank() } ?: "forced", null, System.currentTimeMillis(), forced = true))
    }

    fun dump(ctx: Context) {
        ensure(ctx)
        val now = System.currentTimeMillis()
        Log.w(TAG, "Avail: ${cache.size} cached, ${synchronized(queue) { queue.size }} queued")
        for ((k, e) in cache.entries.sortedByDescending { it.value.checkedAt }) {
            Log.w(TAG, "Avail:   $k ${e.status.label}${e.best?.let { " ($it)" } ?: ""}${e.note?.let { " [$it]" } ?: ""} " +
                "${(now - e.checkedAt) / 60_000} min ago${if (e.forced) " forced" else ""}${if (fresh(e, now)) "" else " stale"}")
        }
    }

    fun clear(ctx: Context) {
        ensure(ctx)
        cache.clear()
        failedAt.clear()
        saveSoon(ctx.applicationContext)
        Log.w(TAG, "Avail: cache cleared")
    }

    // ---- dates ----

    private fun day(offset: Int): String = DreamTime.ymdPlus(offset)

    /** The start of a date-only day in the viewer's chosen zone (the date itself never shifts). */
    internal fun midnight(date: String): Long = try { DreamTime.fmt("yyyy-MM-dd").parse(date)?.time ?: 0L } catch (e: Exception) { 0L }

    /** "Nov 14" ("Nov 14, 2027" past this year). */
    fun dateWords(date: String): String = try {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!
        val year = SimpleDateFormat("yyyy", Locale.US)
        SimpleDateFormat(if (year.format(d) == DreamTime.fmt("yyyy").format(Date())) "MMM d" else "MMM d, yyyy", Locale.US).format(d)
    } catch (e: Exception) { date }

    // ---- views ----

    /**
     * The status pill, drawn in code (crisp at any density, no bitmaps, no emoji fonts): a dark glass capsule
     * with a hairline and a faint wash in the status color, a status disc with its glyph knocked out (a check:
     * Available Now; a video camera: CAM Only; a clock: Not Available Yet) and the label in a lighter tint of
     * that color ([labels]: "Available Now · 4K HDR"; a narrow card takes the longest one that fits).
     * Card size 22 dp tall, 10.5 sp; [large] (the title page) 30 dp, 13 sp. Static: it changes only when its
     * title's answer does, then crossfades to the new one with one soft glow pulse in the new color (one short
     * animator, nothing left running). Gone while there's no answer. Attached with a key ([bind]) it counts as
     * "in view" for the checker (sooner re-checks while CAM Only / Not Available Yet).
     */
    class Pill(ctx: Context, private val large: Boolean = false, private val dated: Boolean = !large) : View(ctx), Watcher {
        private val dm = ctx.resources.displayMetrics
        private fun dp(v: Float) = v * dm.density
        private val pillH = dp(if (large) 30f else 22f)
        private val padS = dp(if (large) 8f else 5.5f)
        private val glyph = dp(if (large) 16f else 12f)
        private val gap = dp(if (large) 8f else 5.5f)
        private val padE = dp(if (large) 13f else 9f)
        private val hair = maxOf(1f, dp(1f))
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Ui.semibold(Theme.typeface(ctx))
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, if (large) 13f else 10.5f, dm)
            letterSpacing = if (large) 0.02f else 0.03f
        }
        private val glass = Paint(Paint.ANTI_ALIAS_FLAG)
        private val wash = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = hair }
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val rect = RectF()
        private val path = Path()

        private var key: String? = null
        /** What the pill says (during a change: the new answer). */
        private var entry: Entry? = null
        /** What is laid out and drawn (during the first part of a change: still the old answer). */
        private var drawn: Entry? = null
        private var label = ""
        /** No room for the status disc beside the words: the words alone (never the disc alone). */
        private var bare = false
        private var contentAlpha = 1f
        private var glow = 0f
        private var anim: ValueAnimator? = null
        /** The gold Watched look ([watched]): it replaces whatever status the pill would say. */
        private var isWatched = false

        init {
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        /**
         * The owner-approved Watched pill (STANDARDS §2 / §5, the iPhone's WatchedPill): a dark graphite-gold capsule
         * ([WATCHED_FILL] #2A2412) with a faint gold edge, a drawn gold check (CheckInk's stroke) and "Watched" in gold
         * ([GOLD] #E8C468); a narrow card takes the word alone. Static, no animation; off: back to the status (if any).
         */
        fun watched(on: Boolean) {
            if (on == isWatched) return
            isWatched = on
            anim?.let { anim = null; it.cancel() }
            settle()
        }

        /** Shows [e] for [key] and follows that title from now on. */
        fun bind(key: String, e: Entry?) {
            val moved = key != this.key
            if (moved && isAttachedToWindow) { this.key?.let { shown(it, false) }; shown(key, true) }
            this.key = key
            watch(this, key)
            if (!moved) { show(e); return }
            // Another title (the banner's next episode): no transition, that's not a status change.
            anim?.let { anim = null; it.cancel() }
            entry = e
            contentDescription = e?.let { labels(it, dated).first() }
            settle()
        }

        fun show(e: Entry?) {
            val was = entry
            if (e == null && was == null) return
            if (e != null && was != null && e.status == was.status && labels(e, dated) == labels(was, dated)) { entry = e; if (anim == null) drawn = e; return }
            entry = e
            contentDescription = e?.let { labels(it, dated).first() }
            anim?.let { anim = null; it.cancel() }
            if (isWatched || was == null || e == null || !isAttachedToWindow || windowVisibility != View.VISIBLE || !isShown) { settle(); return }
            // A live change: the old words fade, the new ones (and the width) come in, one glow pulse in the new color.
            drawn = was
            var swapped = false
            val a = ValueAnimator.ofFloat(0f, 1f).setDuration(if (large) 950L else 850L)
            a.interpolator = LinearInterpolator()
            a.addUpdateListener { v ->
                val p = v.animatedValue as Float
                if (p < SWAP) {
                    contentAlpha = 1f - p / SWAP
                } else {
                    if (!swapped) { swapped = true; drawn = entry; requestLayout() }
                    val q = (p - SWAP) / (1f - SWAP)
                    contentAlpha = ease(minOf(1f, q / 0.35f))
                    glow = if (q < 0.2f) ease(q / 0.2f) else (1f - (q - 0.2f) / 0.8f).let { it * it }
                    val s = 1f + 0.05f * glow
                    scaleX = s; scaleY = s
                }
                invalidate()
            }
            a.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(x: Animator) { if (anim === a) settle() }
            })
            anim = a
            a.start()
        }

        private fun settle() {
            anim = null
            drawn = entry
            contentAlpha = 1f
            glow = 0f
            scaleX = 1f; scaleY = 1f
            visibility = if (entry == null && !isWatched) View.GONE else View.VISIBLE
            contentDescription = if (isWatched) WATCHED else entry?.let { labels(it, dated).first() }
            requestLayout()
            invalidate()
        }

        override fun availChanged(key: String, e: Entry) = show(e)

        private var onShown: (() -> Unit)? = null

        /** Runs [f] once this pill is on screen (attached), now if it already is: a show card's lookup waits for that. */
        fun whenShown(f: () -> Unit) { if (isAttachedToWindow) f() else onShown = f }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            key?.let { shown(it, true) }
            onShown?.let { onShown = null; it() }
        }

        override fun onDetachedFromWindow() {
            key?.let { shown(it, false) }
            anim?.let { anim = null; it.cancel(); settle() }
            super.onDetachedFromWindow()
        }

        /** Icon, gap, the words and the end padding. */
        private fun widthFor(s: String) = padS + glyph + gap + textPaint.measureText(s) + padE

        /** The words alone, evenly padded (a narrow card with no room for the disc). */
        private fun bareWidth(s: String) = padE + textPaint.measureText(s) + padE

        /**
         * The width the pill may take: what its parent offers (the card minus the pill's margins), or, in an
         * unbounded pass, the laid-out parent's inner width minus the margins: never wider than the card.
         */
        private fun room(widthSpec: Int): Int {
            if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) return MeasureSpec.getSize(widthSpec)
            val p = parent as? View ?: return Int.MAX_VALUE
            if (p.width <= 0) return Int.MAX_VALUE
            val lp = layoutParams as? android.view.ViewGroup.MarginLayoutParams
            return (p.width - p.paddingLeft - p.paddingRight - (lp?.leftMargin ?: 0) - (lp?.rightMargin ?: 0)).coerceAtLeast(0)
        }

        /** The Watched look's check box, gap and start padding (the check has no disc, so a touch more room). */
        private val checkBox = dp(if (large) 16f else 12f)
        private val checkGap = dp(if (large) 7f else 5f)
        private val checkPadS = dp(if (large) 9f else 6.5f)

        private fun watchedWidth() = checkPadS + checkBox + checkGap + textPaint.measureText(WATCHED) + padE

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            if (isWatched) {
                // "Watched" beside its check; no room: the word alone; still none: no pill (never the check alone).
                val max = room(widthSpec)
                val full = watchedWidth()
                bare = full > max
                label = if (!bare || bareWidth(WATCHED) <= max) WATCHED else ""
                if (label.isEmpty()) { setMeasuredDimension(0, 0); return }
                setMeasuredDimension(minOf(ceil(if (bare) bareWidth(WATCHED) else full).toInt(), max), ceil(pillH).toInt())
                return
            }
            val e = drawn
            if (e == null) { setMeasuredDimension(0, 0); return }
            val max = room(widthSpec)
            // The longest words that fit whole beside the disc (text is never cut); then the shortest words
            // alone; nothing fits: no pill at all (never an icon-only pill).
            val all = labels(e, dated)
            val withDisc = all.firstOrNull { widthFor(it) <= max }
            val alone = if (withDisc == null) all.firstOrNull { bareWidth(it) <= max } else null
            label = withDisc ?: alone ?: ""
            bare = withDisc == null && alone != null
            if (label.isEmpty()) { setMeasuredDimension(0, 0); return }
            val w = if (bare) bareWidth(label) else widthFor(label)
            setMeasuredDimension(minOf(ceil(w).toInt(), max), ceil(pillH).toInt())
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            // Glass: a touch lighter at the top, like Apple TV's badges.
            glass.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.argb(226, 36, 36, 40), Color.argb(226, 12, 12, 14), Shader.TileMode.CLAMP)
            pivotX = 0f
            pivotY = h / 2f
        }

        override fun onDraw(c: Canvas) {
            if (isWatched) { drawWatched(c); return }
            val e = drawn ?: return
            if (label.isEmpty() || width <= 0) return
            val st = e.status
            val r = height / 2f
            rect.set(hair / 2, hair / 2, width - hair / 2, height - hair / 2)
            c.drawRoundRect(rect, r, r, glass)
            wash.color = fade(st.color, 0.09f + 0.30f * glow)
            c.drawRoundRect(rect, r, r, wash)
            line.color = fade(st.color, 0.36f + 0.54f * glow)
            c.drawRoundRect(rect, r, r, line)

            val cy = height / 2f
            if (!bare) {
                val cx = padS + glyph / 2
                val gr = glyph / 2
                disc.color = fade(st.color, contentAlpha)
                c.drawCircle(cx, cy, gr, disc)
                ink.color = fade(INK, contentAlpha)
                drawGlyph(c, st, cx, cy, gr)
            }

            textPaint.color = fade(st.tint, contentAlpha)
            val x = if (bare) padE else padS + glyph + gap
            val base = cy - (textPaint.ascent() + textPaint.descent()) / 2
            c.save()
            c.clipRect(x, 0f, width - padE / 2, height.toFloat())
            c.drawText(label, x, base, textPaint)
            c.restore()
        }

        /** The Watched look: graphite-gold glass, a gold hairline, CheckInk's stroke in gold, the word in gold. */
        private fun drawWatched(c: Canvas) {
            if (label.isEmpty() || width <= 0) return
            val r = height / 2f
            rect.set(hair / 2, hair / 2, width - hair / 2, height - hair / 2)
            wash.color = WATCHED_FILL
            c.drawRoundRect(rect, r, r, wash)
            line.color = fade(GOLD, 0.34f)
            c.drawRoundRect(rect, r, r, line)
            val cy = height / 2f
            if (!bare) {
                // CheckInk's stroke (-22,1 -> -7,15 -> 22,-14 on a 100 grid) spread across the check box.
                val k = 0.95f * checkBox / 44f
                val cx = checkPadS + checkBox / 2
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = checkBox * 0.15f
                ink.color = GOLD
                path.reset()
                path.moveTo(cx - 22 * k, cy + 1 * k)
                path.lineTo(cx - 7 * k, cy + 15 * k)
                path.lineTo(cx + 22 * k, cy - 14 * k)
                c.drawPath(path, ink)
            }
            textPaint.color = GOLD
            val x = if (bare) padE else checkPadS + checkBox + checkGap
            val base = cy - (textPaint.ascent() + textPaint.descent()) / 2
            c.drawText(label, x, base, textPaint)
        }

        /** The knocked-out glyph inside the disc (radius [u]): a check, a video camera, a clock. */
        private fun drawGlyph(c: Canvas, st: Status, cx: Float, cy: Float, u: Float) {
            path.reset()
            when (st) {
                Status.AVAILABLE -> {
                    ink.style = Paint.Style.STROKE
                    ink.strokeWidth = u * 0.30f
                    path.moveTo(cx - 0.44f * u, cy + 0.03f * u)
                    path.lineTo(cx - 0.13f * u, cy + 0.33f * u)
                    path.lineTo(cx + 0.45f * u, cy - 0.31f * u)
                    c.drawPath(path, ink)
                }
                Status.CAM -> {
                    ink.style = Paint.Style.FILL
                    rect.set(cx - 0.56f * u, cy - 0.32f * u, cx + 0.16f * u, cy + 0.32f * u)
                    c.drawRoundRect(rect, 0.13f * u, 0.13f * u, ink)
                    path.moveTo(cx + 0.10f * u, cy)
                    path.lineTo(cx + 0.56f * u, cy - 0.30f * u)
                    path.lineTo(cx + 0.56f * u, cy + 0.30f * u)
                    path.close()
                    c.drawPath(path, ink)
                }
                Status.NONE -> {
                    ink.style = Paint.Style.STROKE
                    ink.strokeWidth = u * 0.22f
                    path.moveTo(cx, cy - 0.56f * u)
                    path.lineTo(cx, cy)
                    path.lineTo(cx + 0.38f * u, cy + 0.20f * u)
                    c.drawPath(path, ink)
                }
            }
        }

        private companion object {
            const val SWAP = 0.22f
            const val INK = 0xFF0D0D0F.toInt()
            /** The Watched pill's gold (words, check, edge at 34%) and its graphite-gold glass at 92%; same on the iPhone. */
            const val GOLD = 0xFFE8C468.toInt()
            const val WATCHED_FILL = 0xEB2A2412.toInt()
            const val WATCHED = "Watched"
            fun fade(color: Int, f: Float) = (color and 0xFFFFFF) or ((Color.alpha(color) * f.coerceIn(0f, 1f)).toInt() shl 24)
            fun ease(x: Float): Float { val v = 1f - x.coerceIn(0f, 1f); return 1f - v * v * v }
        }
    }

    /**
     * The title page's status line, under the buttons: the pill and a quiet note ("In Theaters Now",
     * "Digital Release Nov 14", "Next Episode: S2 · E7 · Friday at 9:00 PM", "New Season: Season 3 · March 4").
     * A movie: its own status. A show: its latest episode's when that came out in the last week, and when the
     * next one airs (or the next season starts, when TMDB has its date). Gone while there's nothing to say.
     */
    class TitleStatus(ctx: Context, private val t: Title) : LinearLayout(ctx), Watcher {
        private val pill = Pill(ctx, large = true)
        private val note = TextView(ctx).apply {
            textSize = CinemaStyle.T_META + 0.5f
            typeface = Ui.medium(Theme.typeface(ctx))
            setTextColor(CinemaStyle.textDim(ctx))
            includeFontPadding = false
            maxLines = 1
        }
        private var key: String? = null
        private var fixedNote: String? = null
        /** The viewer watched this title (Watched): the gold Watched pill replaces the status; a show keeps its note. */
        private val watched = Watched.title(ctx, t)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(pill, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(note, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = Ui.dp(ctx, 12) })
            followWatched(pill, t)
            if (watched) { pill.watched(true); render(null) }
            // Only a new movie (out in the last RECENT_DAYS days, or still to come) has a status; a catalog one has none.
            if (t.type == "movie") {
                if (!watched) cardTarget(t)?.let { start(it, null) } ?: run {
                    // In theaters months ago, on digital only recently: still new (digitalTarget).
                    val app = ctx.applicationContext
                    Net.background({ digitalTarget(app, t) }) { tg -> if (tg != null && key == null) start(tg, null) }
                }
            }
            else { showLine(); Watched.checkShow(ctx, t.id) }
        }

        private fun start(target: Target, extra: String?) {
            key = target.key
            fixedNote = extra
            watch(this, target.key)
            pill.bind(target.key, peek(context, target.key)) // in view: CAM Only / Not Available Yet re-checked sooner
            render(peek(context, target.key))
            // An open title page asks again when its answer is older than PAGE_TTL, so a fresh release shows here first.
            want(context, target, front = true, page = true)
        }

        /** A show: the latest episode (if new) and the next one's time, from TMDB and TVmaze in the background. */
        private fun showLine() {
            val app = context.applicationContext
            Net.background({
                val d = WatchHistory.details(app, "tv", t.id) ?: return@background null
                val air = try { AirTimes.info(app, t.id) } catch (e: Exception) { null }
                val imdb = d.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") }
                val last = NewEpisodes.Ep.fromTmdb(d.optJSONObject("last_episode_to_air"))?.let { e ->
                    air?.prev?.takeIf { e.same(it.season, it.number) }?.let { e.withAt(it.at) } ?: e
                }
                val next = NewEpisodes.Ep.fromTmdb(d.optJSONObject("next_episode_to_air"))?.let { e ->
                    air?.next?.takeIf { e.same(it.season, it.number) }?.let { e.withAt(it.at) } ?: e
                }
                val recent = last?.takeIf { recentEpisode(it.at, it.airDate) } // aired in the last EPISODE_DAYS days
                // "Next Episode: S2 · E7 · Friday at 9:00 PM"; a premiere, or an announced season with a date: "New Season: Season 3 · March 4".
                val nextWords = next?.takeIf { !it.aired() }?.let { e ->
                    val w = if (e.at > 0) AirTimes.label(e.at) else Upcoming.dayWords(e.airDate)
                    if (e.number == 1) "New Season: Season ${e.season} · $w" else "Next Episode: S${e.season} · E${e.number} · $w"
                } ?: newSeason(d, last?.season ?: 0)
                if (nextWords != null) Log.w(TAG, "Upcoming: ${t.name}: \"$nextWords\"" + if ((next?.at ?: 0L) > 0) " (tvmaze time)" else "")
                Triple(recent?.let { episode(t.id, t.name, imdb, it.season, it.number, it.at, it.airDate) }, recent, nextWords)
            }) { res ->
                if (res == null || !isAttachedToWindow && parent == null) return@background
                val (target, ep, nextWords) = res
                if (target != null && ep != null) start(target, nextWords ?: "S${ep.season} · E${ep.number}")
                else if (nextWords != null) { fixedNote = nextWords; render(null) }
            }
        }

        private fun render(e: Entry?) {
            pill.show(e)
            val words = fixedNote ?: e?.note?.takeIf { e.status != Status.AVAILABLE }
            note.text = words.orEmpty()
            note.visibility = if (words.isNullOrEmpty()) View.GONE else View.VISIBLE
            (note.layoutParams as LayoutParams).marginStart = if (e == null && !watched) 0 else Ui.dp(context, 12)
            visibility = if (e == null && !watched && words.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        override fun availChanged(key: String, e: Entry) { if (key == this.key) render(e) }

        /** The first announced season after [lastOut] with a premiere day ahead: "New Season: Season 3 · March 4". */
        private fun newSeason(d: JSONObject, lastOut: Int): String? {
            val a = d.optJSONArray("seasons") ?: return null
            val today = day(0)
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                .filter { it.optInt("season_number") > maxOf(lastOut, 0) && it.optString("air_date").let { x -> x.length == 10 && x > today } }
                .minByOrNull { it.optInt("season_number") }
                ?.let { "New Season: Season ${it.optInt("season_number")} · " + Upcoming.longDate(it.optString("air_date")) }
        }
    }
}
