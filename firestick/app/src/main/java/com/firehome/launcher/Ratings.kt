package com.firehome.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.URLEncoder
import java.lang.ref.WeakReference

/**
 * Ratings for a movie or show: the age rating and score (TMDB), the Rotten Tomatoes critic score and
 * the IMDb rating (both from OMDb, which needs a free key: Settings / setup, or local.properties omdb.key).
 *
 * OMDb's free key allows 1,000 lookups a day, so they are spent carefully. Every title card that is
 * built joins one prefetch queue ([prefetch]); a single worker drains it slowly (about one OMDb
 * lookup a second, cards nearest the focus first), resting while keys are pressed and while the
 * player, a trailer or the screensaver is up, so scores are usually cached before the user lands on a
 * title. A focused hero title jumps the queue ([bump]); details pages ask on open. An IMDb id is asked
 * at most once per session, answers are cached on disk (7 days for titles from the last 12 months,
 * 60 days for older ones, 14 days for misses), and a daily budget stops the prefetch at 700 so the
 * rest are kept for details pages. Nothing ever reports an error to the user.
 */
object Ratings {
    private const val TAG = "Ratings"
    private const val DAY = 24L * 60 * 60_000
    private const val TMDB_TTL = 7 * DAY
    private const val RECENT_TTL = 7 * DAY
    private const val OLD_TTL = 60 * DAY
    private const val MISS_TTL = 14 * DAY
    private const val PREFETCH_BUDGET = 700
    private const val DAY_BUDGET = 1000
    private const val MAX_DISK = 800
    private const val OMDB = "https://www.omdbapi.com/"
    /** TMDB scores from fewer votes are noise: no ring for them. */
    const val MIN_VOTES = 20

    /** Who is asking: [NONE] = TMDB only (plus whatever OMDb data is cached), [PREFETCH] the queue, [DETAILS] a details page. */
    enum class Omdb { NONE, PREFETCH, DETAILS }

    /**
     * [cert] "PG-13" (empty when TMDB has none), [score]/[votes] TMDB's, [released] "yyyy-MM-dd",
     * [rt] critic score 0..100 and [imdb] "7.8" from OMDb as of [omdbAt] (0 = never asked), TMDB part as of [at];
     * [rtWeb] the critic score from rottentomatoes.com (the details page's fallback when OMDb has none) as of [rtWebAt].
     */
    data class Rating(
        val cert: String,
        val score: Double,
        val votes: Int,
        val imdbId: String?,
        val released: String,
        val rt: Int?,
        val imdb: String?,
        val omdbAt: Long,
        val at: Long,
        val rtWeb: Int? = null,
        val rtWebAt: Long = 0L,
        /** For the hero's meta line: "2h 10m" / "9 Seasons", and genre names. */
        val runtime: String = "",
        val genres: List<String> = emptyList(),
    ) {
        /** The meta-line half of the TMDB details, for pages that never fetched the full ones. */
        fun asDetails(t: Title) = Details(t, genres, runtime, null, emptyList())

        /** The Tomatometer to show: OMDb's, else the website's. */
        val tomatometer: Int? get() = rt ?: rtWeb
    }

    // ---- OMDb key (Settings, else local.properties omdb.key) ----

    fun omdbKey(ctx: Context): String =
        ctx.getSharedPreferences("tmdb", Context.MODE_PRIVATE).getString("omdbKey", null)
            ?.takeIf { it.isNotBlank() } ?: BuildConfig.OMDB_KEY

    fun setOmdbKey(ctx: Context, key: String) {
        ctx.getSharedPreferences("tmdb", Context.MODE_PRIVATE).edit().putString("omdbKey", key.trim()).apply()
        omdbBackoffUntil = 0L
        synchronized(tried) { tried.clear() } // failures under the old key shouldn't block the new one
        prefetchStoppedDay = ""
    }

    /** RT / IMDb badges show (and replace TMDB's score ring) only when an OMDb key is set. */
    fun omdbEnabled(ctx: Context) = omdbKey(ctx).isNotBlank()

    /** True when [key] answers a real lookup (The Godfather). Blocking: call off the main thread. */
    fun testKey(key: String): Boolean = key.isNotBlank() && try {
        Net.getJson("$OMDB?i=tt0068646&apikey=${URLEncoder.encode(key.trim(), "UTF-8")}").optString("Response") == "True"
    } catch (e: Exception) { false }

    // ---- freshness ----

    fun hasOmdbScores(r: Rating) = r.rt != null || r.imdb != null

    private fun recent(date: String): Boolean {
        val t = try { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(date)?.time } catch (e: Exception) { null }
            ?: return true // unknown: refresh like a new title
        return System.currentTimeMillis() - t < 365 * DAY
    }

    private fun omdbTtl(r: Rating) = when { !hasOmdbScores(r) -> MISS_TTL; recent(r.released) -> RECENT_TTL; else -> OLD_TTL }

    private fun omdbFresh(r: Rating) = r.omdbAt > 0 && System.currentTimeMillis() - r.omdbAt < omdbTtl(r)

    /** An OMDb lookup could still add something: key set, IMDb id known, cached answer stale, not tried this session. */
    fun wantsOmdb(ctx: Context, r: Rating?): Boolean = r?.imdbId != null && omdbEnabled(ctx) && !omdbFresh(r) &&
        synchronized(tried) { r.imdbId !in tried } && System.currentTimeMillis() >= omdbBackoffUntil

    // ---- cache ----

    // Small objects: room for every title a session shows (and the whole disk cache, see [warm]).
    private val memory = LruCache<String, Rating>(1024)
    private val waiting = HashMap<String, MutableList<(Rating?) -> Unit>>()
    /** IMDb ids already sent to OMDb this session (answered, missed or failed): never asked twice. */
    private val tried = HashSet<String>()
    /** After a bad key or a network error, OMDb rests for a while. */
    @Volatile private var omdbBackoffUntil = 0L
    @Volatile private var warmed = false

    /** Loads the disk cache into memory once (in the background), so [peek] finds every cached title. */
    private fun warm(ctx: Context) {
        if (warmed) return
        warmed = true
        val app = ctx.applicationContext
        Net.run {
            for ((k, v) in prefs(app).all) if (memory.get(k) == null) (v as? String)?.let { parse(it) }?.let { memory.put(k, it) }
        }
    }

    /** The rating when it's in memory with a current TMDB part; no I/O, safe on the main thread. */
    fun peek(ctx: Context, t: Title): Rating? {
        warm(ctx)
        return memory.get(t.key)?.takeIf { System.currentTimeMillis() - it.at < TMDB_TTL }
    }

    /** Loads [t]'s rating in the background ([mode] says whether OMDb may be asked) and calls [done] on the main thread (null on failure). */
    fun load(ctx: Context, t: Title, mode: Omdb, done: (Rating?) -> Unit) {
        peek(ctx, t)?.let { if (mode == Omdb.NONE || !wantsOmdb(ctx, it)) { done(it); return } }
        val app = ctx.applicationContext
        val id = "${t.key}|$mode"
        synchronized(waiting) {
            waiting[id]?.let { it += done; return }
            waiting[id] = mutableListOf(done)
        }
        Net.background({ fetch(app, t, mode) }) { r ->
            val calls = synchronized(waiting) { waiting.remove(id) }.orEmpty()
            for (c in calls) c(r)
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ratings", Context.MODE_PRIVATE)

    /** Background thread. Sets [lastAskedOmdb] when it spent an OMDb lookup. */
    private fun fetch(ctx: Context, t: Title, mode: Omdb): Rating? {
        // One TMDB call per title even when the meta line, the ratings row and the queue ask at once
        // (held only for the TMDB step, so the meta line never waits on OMDb).
        val lock = synchronized(locks) { locks.get(t.key) ?: Any().also { locks.put(t.key, it) } }
        val base = synchronized(lock) { tmdbPart(ctx, t) } ?: return null
        var r = base
        if (mode != Omdb.NONE && wantsOmdb(ctx, r)) {
            if (spend(ctx, mode)) { r = askOmdb(ctx, r); lastAskedOmdb = true }
            else if (mode == Omdb.PREFETCH) prefetchStoppedDay = today()
        }
        if (r !== base) { memory.put(t.key, r); writeDisk(ctx, t.key, r) }
        return r
    }

    private val locks = LruCache<String, Any>(64)

    /** The cached entry with a current TMDB part (fetched when stale); a stale one when TMDB fails; null when there's nothing. */
    private fun tmdbPart(ctx: Context, t: Title): Rating? {
        val now = System.currentTimeMillis()
        val old = memory.get(t.key) ?: readDisk(ctx, t.key)
        var r = old
        if (r == null || now - r.at >= TMDB_TTL) {
            val c = try { Tmdb.certification(ctx, t) } catch (e: Exception) { Log.w(TAG, "certification failed for ${t.key}: $e"); null }
            if (c != null) {
                // The OMDb part survives a TMDB refresh (it has its own lifetime) unless the IMDb id changed.
                val keep = r != null && r.imdbId == c.imdbId
                r = if (keep) r!!.copy(cert = c.rating, score = c.score, votes = c.votes, released = c.released, at = now,
                    runtime = c.runtime, genres = c.genres)
                else Rating(c.rating, c.score, c.votes, c.imdbId, c.released, null, null, 0L, now, runtime = c.runtime, genres = c.genres)
            }
        }
        if (r == null) return null
        memory.put(t.key, r)
        if (r !== old) writeDisk(ctx, t.key, r)
        return r
    }

    /** One OMDb lookup for [r]'s IMDb id; returns [r] updated (or unchanged on errors). */
    private fun askOmdb(ctx: Context, r: Rating): Rating {
        val id = r.imdbId ?: return r
        synchronized(tried) { if (!tried.add(id)) return r }
        val key = omdbKey(ctx)
        val now = System.currentTimeMillis()
        try {
            val json = Net.getJson("$OMDB?i=${URLEncoder.encode(id, "UTF-8")}&apikey=${URLEncoder.encode(key, "UTF-8")}")
            if (json.optString("Response") == "True") {
                return r.copy(rt = parseRt(json), imdb = parseImdb(json), omdbAt = now)
            }
            val err = json.optString("Error")
            when {
                err.contains("not found", ignoreCase = true) ->
                    return r.copy(rt = null, imdb = null, omdbAt = now) // a miss, kept 14 days
                err.contains("limit", ignoreCase = true) -> exhaust(ctx)
                else -> omdbBackoffUntil = now + 10 * 60_000
            }
            Log.w(TAG, "omdb: $err")
        } catch (e: Exception) {
            // Bad keys come back as HTTP 401; rest either way.
            omdbBackoffUntil = now + 2 * 60_000
            Log.w(TAG, "omdb failed for $id: ${e.toString().replace(key, "<key>")}") // never log the key
        }
        return r
    }

    private fun parseRt(json: JSONObject): Int? {
        val a = json.optJSONArray("Ratings") ?: return null
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("Source") == "Rotten Tomatoes") return o.optString("Value").trimEnd('%').toIntOrNull()?.coerceIn(0, 100)
        }
        return null
    }

    private fun parseImdb(json: JSONObject): String? =
        json.optString("imdbRating").toDoubleOrNull()?.takeIf { it > 0 }?.let { String.format(java.util.Locale.US, "%.1f", it) }

    // ---- rottentomatoes.com fallback (details pages, and series on the hero; see RtWeb) ----

    private const val WEB_HIT_TTL = 30 * DAY
    private const val WEB_MISS_TTL = 14 * DAY

    private fun rtWebFresh(r: Rating) = r.rtWebAt > 0 &&
        System.currentTimeMillis() - r.rtWebAt < if (r.rtWeb != null) WEB_HIT_TTL else WEB_MISS_TTL

    /** OMDb answered without a Tomatometer, and the website hasn't been asked lately (or this session). */
    fun wantsRtWeb(ctx: Context, t: Title, r: Rating?): Boolean =
        r != null && omdbEnabled(ctx) && r.omdbAt > 0 && r.rt == null && !rtWebFresh(r) && RtWeb.mayTry(t)

    /** Details page or a rested series hero: asks rottentomatoes.com for [t]'s score in the background; [done] on the main thread. */
    fun loadRtWeb(ctx: Context, t: Title, done: (Rating?) -> Unit) {
        val app = ctx.applicationContext
        Net.background({
            val r = memory.get(t.key) ?: readDisk(app, t.key)
            if (r == null || !wantsRtWeb(app, t, r)) r
            else RtWeb.lookup(app, t)?.let { res ->
                r.copy(rtWeb = res.score, rtWebAt = System.currentTimeMillis()).also { memory.put(t.key, it); writeDisk(app, t.key, it) }
            } ?: r
        }, done)
    }

    // ---- daily budget (OMDb's free key: 1,000 a day) ----

    private fun budget(ctx: Context) = ctx.getSharedPreferences("ratings_budget", Context.MODE_PRIVATE)

    private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    private fun usedToday(ctx: Context): Int {
        val p = budget(ctx)
        return if (p.getString("day", "") == today()) p.getInt("count", 0) else 0
    }

    /** Counts one lookup if today's budget allows it: the prefetch stops at [PREFETCH_BUDGET], everything at [DAY_BUDGET]. */
    private fun spend(ctx: Context, mode: Omdb): Boolean = synchronized(this) {
        val used = usedToday(ctx)
        if (used >= DAY_BUDGET || (mode == Omdb.PREFETCH && used >= PREFETCH_BUDGET)) return false
        val n = used + 1
        budget(ctx).edit().putString("day", today()).putInt("count", n).apply()
        if (n == PREFETCH_BUDGET) Log.w(TAG, "omdb: $PREFETCH_BUDGET lookups today, prefetch paused until tomorrow (details pages still ask)")
        if (n == DAY_BUDGET) Log.w(TAG, "omdb: $DAY_BUDGET lookups today, all lookups paused until tomorrow")
        true
    }

    /** OMDb says the key's limit is reached: spend the rest of today's budget. */
    private fun exhaust(ctx: Context) = synchronized(this) {
        budget(ctx).edit().putString("day", today()).putInt("count", DAY_BUDGET).apply()
    }

    // ---- prefetch queue (main thread) ----

    /** Set by the host: true while the player, a trailer or the screensaver is up (the queue rests then). */
    var paused: () -> Boolean = { false }

    /** Last key press (uptime ms), from MainActivity.dispatchKeyEvent: the queue rests while the remote is busy. */
    @Volatile private var lastKeyAt = 0L

    fun onKey() { lastKeyAt = SystemClock.uptimeMillis() }

    private const val KEY_IDLE_MS = 1200L
    private const val OMDB_GAP_MS = 1000L
    private const val TMDB_GAP_MS = 300L
    private const val MAX_QUEUE = 400

    private class Job(val t: Title, val card: WeakReference<View>?, val seq: Long)

    private val main = Handler(Looper.getMainLooper())
    private val jobs = LinkedHashMap<String, Job>()
    /** Hero titles, newest first: they skip the line (and the key-press rest). */
    private val front = ArrayList<Title>()
    private val frontCalls = HashMap<String, MutableList<(Rating?) -> Unit>>()
    private var appCtx: Context? = null
    private var busy = false
    private var scheduled = false
    private var nextAt = 0L
    private var seq = 0L
    private var prefetched = 0
    @Volatile private var lastAskedOmdb = false
    @Volatile private var prefetchStoppedDay = ""

    /** Queues [t]'s lookup (a card was just built for it; [card] ranks it by distance from the focus). */
    fun prefetch(ctx: Context, t: Title, card: View? = null) {
        if (!omdbEnabled(ctx) || prefetchStoppedDay == today()) return
        appCtx = ctx.applicationContext
        peek(ctx, t)?.let { if (!wantsOmdb(ctx, it)) return }
        if (jobs.size >= MAX_QUEUE) dropDetached()
        if (jobs.size >= MAX_QUEUE) jobs.remove(jobs.keys.first())
        jobs.remove(t.key)
        jobs[t.key] = Job(t, card?.let { WeakReference(it) }, seq++)
        kick(0L)
    }

    /** The hero is showing [t] without cached scores: look it up next, then call [done] (main thread). */
    fun bump(ctx: Context, t: Title, done: (Rating?) -> Unit) {
        appCtx = ctx.applicationContext
        jobs.remove(t.key)
        front.removeAll { it.key == t.key }
        front.add(0, t)
        // Only the latest few heroes matter; older ones go back to the queue's order.
        while (front.size > 3) frontCalls.remove(front.removeAt(front.size - 1).key)
        frontCalls.getOrPut(t.key) { ArrayList() } += done
        kick(0L)
    }

    private fun kick(delay: Long) {
        if (busy || scheduled) return
        scheduled = true
        main.postDelayed(tick, maxOf(delay, nextAt - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    private val tick = Runnable { scheduled = false; drain() }

    private fun drain() {
        val ctx = appCtx ?: return
        if (busy) return
        if (!omdbEnabled(ctx)) { jobs.clear(); front.clear(); flushFront(null); return }
        if (paused()) { kick(1000L); return }
        val hero = front.removeFirstOrNull()
        val t = hero ?: run {
            val idle = SystemClock.uptimeMillis() - lastKeyAt
            if (idle < KEY_IDLE_MS) { kick(KEY_IDLE_MS - idle); return }
            if (prefetchStoppedDay == today()) { jobs.clear(); return }
            pickNearest()
        } ?: return
        busy = true
        lastAskedOmdb = false
        Net.background({ fetch(ctx, t, Omdb.PREFETCH) }) { r ->
            busy = false
            val asked = lastAskedOmdb
            nextAt = SystemClock.uptimeMillis() + if (asked) OMDB_GAP_MS else TMDB_GAP_MS
            frontCalls.remove(t.key)?.forEach { it(r) }
            if (asked && ++prefetched % 50 == 0) Net.run {
                Log.w(TAG, "prefetch: $prefetched looked up this session, ${prefs(ctx).all.size} cached, ${usedToday(ctx)} of $DAY_BUDGET used today")
            }
            if (front.isNotEmpty() || jobs.isNotEmpty()) kick(0L)
        }
    }

    private fun flushFront(r: Rating?) {
        val calls = frontCalls.values.flatten()
        frontCalls.clear()
        for (c in calls) c(r)
    }

    private fun dropDetached() {
        jobs.values.removeAll { j -> j.card != null && j.card.get()?.isAttachedToWindow != true }
    }

    private val loc = IntArray(2)

    /**
     * The queued card nearest the focus: same row first (rows weigh 4× columns), then the rows above and
     * below. Cards no longer on a screen leave the queue; cards on hidden pages or far away wait.
     */
    private fun pickNearest(): Title? {
        dropDetached()
        if (jobs.isEmpty()) return null
        var best: Job? = null
        var bestScore = Long.MAX_VALUE
        var focusX = 0
        var focusY = 0
        var screenH = 1080
        var haveFocus = false
        for (j in jobs.values) {
            val v = j.card?.get() ?: continue
            if (!haveFocus) {
                screenH = v.rootView.height.takeIf { it > 0 } ?: screenH
                v.rootView.findFocus()?.let { f -> f.getLocationOnScreen(loc); focusX = loc[0] + f.width / 2; focusY = loc[1] + f.height / 2 }
                    ?: run { focusX = v.rootView.width / 2; focusY = screenH / 2 }
                haveFocus = true
            }
            if (!v.isShown) continue // its page is hidden: keep it for later
            v.getLocationOnScreen(loc)
            val dy = Math.abs(loc[1] + v.height / 2 - focusY).toLong()
            if (dy > 3L * screenH) continue // far off: deprioritized until the user scrolls near
            val score = dy * 4 + Math.abs(loc[0] + v.width / 2 - focusX)
            if (score < bestScore) { bestScore = score; best = j }
        }
        // Cards without a view (none today) go in the order they came.
        val pick = best ?: jobs.values.firstOrNull { it.card == null } ?: return null
        jobs.remove(pick.t.key)
        return pick.t
    }

    // ---- disk ----

    private fun parse(s: String): Rating? = try {
        val o = JSONObject(s)
        // Entries from before the TMDB score / runtime were stored: the TMDB part counts as expired (the OMDb part stays).
        val at = if (o.has("v") && o.has("rn")) o.optLong("t") else 0L
        // Older entries kept only an "answered" flag: they were answered when the entry was written.
        val omdbAt = if (o.has("a")) o.optLong("a") else if (o.optBoolean("o")) o.optLong("t") else 0L
        Rating(o.optString("c"), o.optDouble("s", 0.0), o.optInt("v"), o.optString("i").ifEmpty { null }, o.optString("d"),
            if (o.has("r")) o.getInt("r") else null, o.optString("m").ifEmpty { null }, omdbAt, at,
            if (o.has("w")) o.getInt("w") else null, o.optLong("wa"),
            o.optString("rn"), o.optString("g").split('|').filter { it.isNotEmpty() })
    } catch (e: Exception) { null }

    private fun readDisk(ctx: Context, key: String): Rating? = prefs(ctx).getString(key, null)?.let { parse(it) }

    private fun writeDisk(ctx: Context, key: String, r: Rating) {
        val o = JSONObject().put("c", r.cert).put("s", r.score).put("v", r.votes).put("i", r.imdbId ?: "")
            .put("d", r.released).put("m", r.imdb ?: "").put("a", r.omdbAt).put("t", r.at)
            .put("rn", r.runtime).put("g", r.genres.joinToString("|"))
        r.rt?.let { o.put("r", it) }
        r.rtWeb?.let { o.put("w", it) }
        if (r.rtWebAt > 0) o.put("wa", r.rtWebAt)
        val p = prefs(ctx)
        val e = p.edit().putString(key, o.toString())
        // Keep the file small: past the cap, drop the oldest quarter.
        val all = p.all
        if (all.size > MAX_DISK) {
            all.entries.sortedBy { (it.value as? String)?.let { s -> try { JSONObject(s).optLong("t") } catch (x: Exception) { 0L } } ?: 0L }
                .take(MAX_DISK / 4).forEach { e.remove(it.key) }
        }
        e.apply()
    }

    // ---- colours ----

    /** Score colour (Rotten Tomatoes and TMDB): ≥75 green, 60–74 amber, below 60 red, a touch toward the theme's text. */
    /**
     * The audience score beside the tomato, exactly as published (never rounded into stars, which read harsher
     * than the number): IMDb's ("IMDb" to "6.5"), else TMDB's once [AUDIENCE_VOTES] people voted (early TMDB
     * scores run high). Null when neither.
     */
    fun audienceFor(r: Rating?, t: Title): Pair<String, String>? {
        r?.imdb?.let { return "IMDb" to it }
        val s = t.rating.toFloat().takeIf { it > 0 && t.votes >= AUDIENCE_VOTES } ?: return null
        return "TMDB" to String.format(java.util.Locale.US, "%.1f", s)
    }
    private const val AUDIENCE_VOTES = 150

    /** [audienceFor] drawn for a meta line of [sp]: the source as a small filled tag, then the number (as [RatingsRow]). */
    fun audienceBadge(ctx: Context, a: Pair<String, String>, sp: Float, text: Int, bold: Typeface, semibold: Typeface): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = Ui.dp(ctx, 7); val padV = Ui.dp(ctx, 2)
            addView(TextView(ctx).apply {
                this.text = a.first; textSize = sp * 0.72f; typeface = bold; letterSpacing = 0.04f
                setTextColor(Ui.alpha(text, 0.95f)); includeFontPadding = false
                setPadding(padH, padV, padH, padV)
                background = Ui.rounded(ctx, Ui.alpha(text, 0.16f), Ui.R_PILL)
            })
            addView(TextView(ctx).apply {
                this.text = a.second; textSize = sp; typeface = semibold
                setTextColor(Ui.alpha(text, 0.92f)); includeFontPadding = false
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = Ui.dp(ctx, 7) })
        }

    fun scoreColor(kit: PremiumKit, score: Int): Int = scoreColor(kit.text, score)

    /** The quality colour for a 0-100 score (green 75+, amber 60+, red below), eased a touch toward [text]. */
    fun scoreColor(text: Int, score: Int): Int = Ui.mix(when {
        score >= 75 -> Color.parseColor("#4ADE80")
        score >= 60 -> Color.parseColor("#FBBF24")
        else -> Color.parseColor("#F87171")
    }, text, 0.08f)
}

/**
 * A ratings badge row. A meta line uses two: a [lead] row before the text holding TMDB's score
 * ring (when no OMDb key is set; it shows at once from the list data), and a tail row after it with
 * the outlined age-rating pill, then the tomato and its score and the IMDb tag and rating (or TMDB's
 * ring when those aren't known). Only one score style is ever visible. A [hero] row shows what's
 * cached at once and, when the scores aren't cached yet, moves its title to the front of the prefetch
 * queue and swaps them in when they land. Fades in only what's new; takes no width while empty.
 */
class RatingsRow(
    private val kit: PremiumKit,
    private val textSp: Float = 14f,
    private val tagSp: Float = 11.5f,
    private val tagPadV: Int = 3,
    private val lead: Boolean = false,
    private val hero: Boolean = false,
) : LinearLayout(kit.ctx) {
    /** Space before the first badge (a tail row sits after the meta text). */
    var leadingGapDp = 16
        set(v) {
            field = v
            (getChildAt(0)?.layoutParams as? LayoutParams)?.let { it.marginStart = v.dp; getChildAt(0).layoutParams = it }
        }
    /** Space after the last badge (a lead row sits before the meta text). */
    var trailingGapDp = 0
    private var bound: String? = null
    private var boundAt = 0L
    private var pending: Runnable? = null
    private var sigs = emptyList<String>()
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Shows [t]'s ratings (null clears). Cached ones appear at once; others load after [delayMs] and fade in. */
    fun bind(t: Title?, delayMs: Long = 0L) {
        if (t?.key == bound) return
        bound = t?.key
        boundAt = android.os.SystemClock.uptimeMillis()
        pending?.let { removeCallbacks(it) }
        pending = null
        removeAllViews()
        sigs = emptyList()
        if (t == null) return
        val cached = Ratings.peek(context, t)
        // Heroes show the TMDB ring at once when nothing better is cached; details wait for their lookup.
        show(t, cached, loaded = cached != null || hero, fade = false)
        when {
            // The ring only needs a fetch when the list didn't carry a vote count.
            lead -> if (cached == null && t.votes == 0) later(delayMs) { fetch(t, Ratings.Omdb.NONE) }
            hero -> when {
                Ratings.omdbEnabled(context) && (cached == null || Ratings.wantsOmdb(context, cached)) -> later(delayMs) {
                    Ratings.bump(context, t) { r ->
                        if (bound != t.key) return@bump
                        show(t, r, loaded = true, fade = true)
                        showWeb(t, r)
                    }
                }
                cached == null -> later(delayMs) { fetch(t, Ratings.Omdb.NONE) }
                else -> showWeb(t, cached)
            }
            else -> when {
                cached == null || Ratings.wantsOmdb(context, cached) -> later(delayMs) { fetch(t, Ratings.Omdb.DETAILS) }
                Ratings.wantsRtWeb(context, t, cached) -> later(delayMs) { rtWeb(t) }
            }
        }
    }

    private fun later(ms: Long, work: () -> Unit) {
        pending?.let { removeCallbacks(it) }
        val r = Runnable { pending = null; work() }
        pending = r
        if (ms > 0) postDelayed(r, ms) else r.run()
    }

    private fun fetch(t: Title, mode: Ratings.Omdb) {
        Ratings.load(context, t, mode) { r ->
            if (bound != t.key) return@load
            show(t, r, loaded = true, fade = true)
            // Details page, OMDb has no Tomatometer: try rottentomatoes.com (the tomato fades in if found).
            if (mode == Ratings.Omdb.DETAILS && Ratings.wantsRtWeb(context, t, r)) rtWeb(t)
        }
    }

    /**
     * Hero, shows only: OMDb rarely has a Tomatometer for series, so once the title has been on screen
     * [WEB_AFTER_MS] the website is asked (never from the bulk prefetch; shares RtWeb's daily cap).
     */
    private fun showWeb(t: Title, r: Ratings.Rating?) {
        if (t.type != "tv" || !Ratings.wantsRtWeb(context, t, r)) return
        later(maxOf(0L, boundAt + WEB_AFTER_MS - android.os.SystemClock.uptimeMillis())) {
            if (bound == t.key && isShown) rtWeb(t)
        }
    }

    private companion object {
        const val WEB_AFTER_MS = 1500L
    }

    private fun rtWeb(t: Title) {
        Ratings.loadRtWeb(context, t) { r -> if (bound == t.key && r != null) show(t, r, loaded = true, fade = true) }
    }

    private fun show(t: Title, r: Ratings.Rating?, loaded: Boolean, fade: Boolean) {
        val items = ArrayList<Pair<String, () -> android.view.View>>()
        // TMDB's score: the fetched one when there is one, else the list's.
        val score = if (r != null && r.votes > 0) r.score else t.rating
        val votes = if (r != null && r.votes > 0) r.votes else t.votes
        val pct = Math.round(score * 10).toInt()
        val ring = votes >= Ratings.MIN_VOTES && pct > 0
        val omdb = Ratings.omdbEnabled(context)
        if (lead) {
            if (!omdb && ring) items += "s$pct" to { scoreRing(pct) }
        } else {
            r?.cert?.ifEmpty { null }?.let { c -> items += "c$c" to { tag(c, filled = false) } }
            if (omdb) {
                r?.tomatometer?.let { v -> items += "r$v" to { tomato(v) } }
                r?.imdb?.let { v -> items += "i$v" to { imdb(v) } }
                if (loaded && r?.tomatometer == null && r?.imdb == null && ring) items += "s$pct" to { scoreRing(pct) }
            }
        }
        val was = sigs
        sigs = items.map { it.first }
        if (sigs == was) return
        removeAllViews()
        for ((i, item) in items.withIndex()) {
            val v = item.second()
            addView(v, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = (if (i == 0) leadingGapDp else 16).dp
                if (i == items.size - 1) marginEnd = trailingGapDp.dp
            })
            if (fade && item.first !in was) {
                v.alpha = 0f
                v.animate().alpha(1f).setStartDelay(i * 40L).setDuration(260).setInterpolator(Ui.EASE).start()
            }
        }
    }

    /** A fully rounded tag: outlined for the age rating, quietly filled for "IMDb". */
    private fun tag(s: String, filled: Boolean) = kit.label(s, tagSp, Ui.alpha(kit.text, if (filled) 0.95f else 0.88f),
        if (filled) kit.bold else kit.semibold, 0.04f).apply {
        setPadding(8.dp, tagPadV.dp, 8.dp, tagPadV.dp)
        background = if (filled) Ui.rounded(context, Ui.alpha(kit.text, 0.16f), Ui.R_PILL)
        else Ui.rounded(context, Color.TRANSPARENT, Ui.R_PILL, 1f, Ui.alpha(kit.text, 0.5f))
    }

    private fun glyphPx(scale: Float) =
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, textSp * scale, resources.displayMetrics).toInt()

    private fun tomato(score: Int) = kit.label("$score%", textSp, Ratings.scoreColor(kit, score), kit.semibold).apply {
        val s = glyphPx(1.25f)
        setCompoundDrawables(TomatoGlyph().apply { setBounds(0, 0, s, s) }, null, null, null)
        compoundDrawablePadding = 8.dp
    }

    private fun imdb(score: String) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(tag("IMDb", filled = true))
        addView(kit.label(score, textSp, Ui.alpha(kit.text, 0.92f), kit.semibold),
            LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 8.dp })
    }

    /** TMDB-style: a progress ring in the quality colour, the percentage right after it. */
    private fun scoreRing(pct: Int) = kit.label("$pct%", textSp, Ratings.scoreColor(kit, pct), kit.semibold).apply {
        val s = glyphPx(1.2f)
        setCompoundDrawables(ScoreRing(Ratings.scoreColor(kit, pct), Ui.alpha(kit.text, 0.18f), pct / 100f,
            maxOf(2f, 2.2f * resources.displayMetrics.density)).apply { setBounds(0, 0, s, s) }, null, null, null)
        compoundDrawablePadding = 8.dp
    }
}

/** A circular progress ring drawn in code: a faint full track and a round-capped arc for [fraction] from the top. */
class ScoreRing(color: Int, track: Int, private val fraction: Float, private val stroke: Float) : Drawable() {
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = stroke
    }
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = track; style = Paint.Style.STROKE; strokeWidth = stroke }
    private val oval = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val half = stroke / 2f
        oval.set(b.left + half, b.top + half, b.right - half, b.bottom - half)
        canvas.drawOval(oval, base)
        canvas.drawArc(oval, -90f, 360f * fraction.coerceIn(0f, 1f), false, arc)
    }

    override fun setAlpha(alpha: Int) { arc.alpha = alpha; base.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { arc.colorFilter = cf; base.colorFilter = cf; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A simple tomato drawn in code: a round red body with a soft highlight, a five-leaf calyx and a stem. */
class TomatoGlyph : Drawable() {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F2553F") }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 255, 255, 255) }
    private val leaf = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4CC46A") }
    private val stem = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CC46A"); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val calyx = Path()
    private val oval = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val x = b.left.toFloat()
        val y = b.top.toFloat()
        // Body: a little wider than tall, sitting on the bottom of the box.
        oval.set(x + w * 0.06f, y + h * 0.2f, x + w * 0.94f, y + h * 0.98f)
        canvas.drawOval(oval, body)
        oval.set(x + w * 0.2f, y + h * 0.34f, x + w * 0.42f, y + h * 0.52f)
        canvas.drawOval(oval, shine)
        // Calyx: a five-point star of narrow leaves on top.
        val cx = x + w * 0.5f
        val cy = y + h * 0.27f
        val outer = w * 0.3f
        val inner = w * 0.07f
        calyx.reset()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outer else inner
            val a = Math.toRadians(-90.0 + i * 36.0)
            val px = cx + (r * Math.cos(a)).toFloat()
            val py = cy + (r * Math.sin(a) * 0.55).toFloat()
            if (i == 0) calyx.moveTo(px, py) else calyx.lineTo(px, py)
        }
        calyx.close()
        canvas.drawPath(calyx, leaf)
        stem.strokeWidth = w * 0.09f
        canvas.drawLine(cx, cy, cx + w * 0.07f, y + h * 0.05f, stem)
    }

    override fun setAlpha(alpha: Int) {
        for (p in listOf(body, leaf, stem)) p.alpha = alpha
        shine.alpha = alpha * 80 / 255
        invalidateSelf()
    }

    override fun setColorFilter(cf: ColorFilter?) {
        for (p in listOf(body, shine, leaf, stem)) p.colorFilter = cf
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

