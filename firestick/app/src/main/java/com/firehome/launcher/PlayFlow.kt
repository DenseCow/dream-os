package com.firehome.launcher

import android.app.ActivityManager
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.animation.LinearInterpolator
import android.graphics.SweepGradient
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single entry point for "Play": opens Dream Player at once and finds the stream behind it.
 *
 * The journey: [play] opens DreamPlayerActivity with the request ([DreamPlayerActivity.intentFor]; it shows the
 * [LoadingCurtain]), then resolves in the background ([resolve]) with Dream Core ([NativeResolver]: the
 * user's scrapers and debrid services over HTTPS, a second or two). The start of the stream goes to disk
 * first ([StreamCache.fastStart]), then the stream reaches the player as a VIEW intent carrying the request
 * ([streamIntent]). When nothing is found, the curtain says so in plain words ("This Title Isn't Available
 * Yet", "Couldn't Find a Ready Stream") with Try Again, Search All Sources and Change Source.
 *
 * The next episode is found while one plays ([PreResolve.ahead], from the post-play controller once the
 * next episode is known), so autoplay opens it at once. A stream resolved ahead on a details page or a
 * rested card (Instant Play) is kept by [PreResolve] and taken by [play].
 */
object PlayFlow {
    private const val TAG = "FireHome"
    private const val PREFS = "play_flow"
    /** The PlaybackRequest as JSON ([toJson]) on the intent that opens Dream Player ([requestFrom] reads it). */
    const val EXTRA_REQUEST = "dream.request"
    /** Set on an intent that only brings Dream Player back to the front (X-Ray's title page); no new request. */
    const val EXTRA_FRONT_ONLY = "dream.front_only"
    /** The stream file's size in bytes on a Dream Core stream intent (the fast start plans its ranges with it). */
    const val EXTRA_SIZE = "dream.size"
    private val PLAYER_CLASS = DreamPlayerActivity::class.java.name
    const val RESOLVE_TIMEOUT_MS = 45_000L
    private val main = Handler(Looper.getMainLooper())

    // ---- settings (Settings › Watching, next to the Dream Player switch) ----

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** "Autoplay next episode" (default on). */
    fun autoplayNext(ctx: Context) = prefs(ctx).getBoolean("autoplayNext", true)
    fun setAutoplayNext(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("autoplayNext", on).apply()

    /** "Are you still watching?" after 3 autoplayed episodes without a key press (default on). */
    fun stillWatchingCheck(ctx: Context) = prefs(ctx).getBoolean("stillWatching", true)
    fun setStillWatchingCheck(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("stillWatching", on).apply()

    // ---- requests ----

    fun toJson(r: PlaybackRequest): JSONObject = JSONObject().apply {
        put("title", r.title); put("type", r.type); put("tmdb", r.tmdbId)
        r.imdb?.let { put("imdb", it) }; r.year?.let { put("year", it) }
        r.season?.let { put("season", it) }; r.episode?.let { put("episode", it) }
        r.episodeTitle?.let { put("epTitle", it) }
        r.backdrop?.let { put("backdrop", it) }; r.poster?.let { put("poster", it) }; r.logo?.let { put("logo", it) }
        put("startMs", r.startMs); r.player?.let { put("player", it) }; put("autoplayNext", r.autoplayNext)
    }

    fun fromJson(o: JSONObject): PlaybackRequest = PlaybackRequest(
        title = o.optString("title"), type = o.optString("type", "movie"), tmdbId = o.optInt("tmdb"),
        imdb = o.optString("imdb").ifEmpty { null }, year = o.optInt("year").takeIf { it > 0 },
        season = if (o.has("season")) o.optInt("season") else null, episode = if (o.has("episode")) o.optInt("episode") else null,
        episodeTitle = o.optString("epTitle").ifEmpty { null },
        backdrop = o.optString("backdrop").ifEmpty { null }, poster = o.optString("poster").ifEmpty { null },
        logo = o.optString("logo").ifEmpty { null }, startMs = o.optLong("startMs"),
        player = o.optString("player").ifEmpty { null }, autoplayNext = o.optBoolean("autoplayNext", true),
    )

    /** The request on an intent that opens Dream Player, or null. */
    fun requestFrom(intent: Intent?): PlaybackRequest? =
        intent?.getStringExtra(EXTRA_REQUEST)?.let { try { fromJson(JSONObject(it)) } catch (e: Exception) { null } }

    /** A TMDB image path (or a full URL) as a URL at [size]. */
    fun img(path: String?, size: String) =
        path?.takeIf { it.isNotEmpty() && it != "null" }?.let { if (it.startsWith("http")) it else Tmdb.IMG + size + it }

    /**
     * A Dream Player request from Dream OS's play buttons: a movie ([tv] false), or a show's [season] / [episode]
     * ([tmdbId] is the show's), plus what the caller knows (artwork paths, the episode's title).
     */
    fun request(
        tv: Boolean, tmdbId: Int, title: String, year: Int?, imdb: String?, season: Int = 0, episode: Int = 0,
        startMs: Long = 0, backdropPath: String? = null, posterPath: String? = null, episodeTitle: String? = null,
    ) = PlaybackRequest(
        title = title, type = if (tv) "episode" else "movie", tmdbId = tmdbId, imdb = imdb, year = year,
        season = if (tv) season else null, episode = if (tv) episode else null, episodeTitle = episodeTitle,
        backdrop = img(backdropPath, "w1280"), poster = img(posterPath, "w342"), startMs = startMs,
    )

    private fun tmdbType(r: PlaybackRequest) = if (r.type == "episode") "tv" else "movie"

    /** Progress Dream OS can resume: played in Dream Player, not an app's own (package names have dots). */
    private fun ours(e: WatchHistory.Entry) = '.' !in e.source

    /** Where to resume [r] from: Dream OS's watch history for the same title (and episode) when it's in progress. */
    fun resumePosition(ctx: Context, r: PlaybackRequest): Long = historyStart(ctx, r) ?: 0L

    /**
     * Where the watch history says [r] starts: its saved spot while in progress; 0 once it's finished, taken off
     * Continue Watching, or the show has moved on past that episode; null when the history has no say (never played
     * here, another app's, not started). A spot a page or card carried loses to this: a details page opened before
     * the end of a movie still holds its old Resume spot, and the movie must not start there again.
     */
    private fun historyStart(ctx: Context, r: PlaybackRequest): Long? {
        val key = (if (r.type == "episode") "tv:" else "movie:") + r.tmdbId
        val e = WatchHistory.recent(ctx).firstOrNull { it.key == key } ?: return null
        if (!ours(e)) return null
        if (r.type == "episode") {
            val s = r.season ?: 0
            val ep = r.episode ?: 0
            if (e.season > s || e.season == s && e.episode > ep) return 0L // the show moved on: that one was watched
            if (e.season != s || e.episode != ep) return null
        }
        if (e.hidden || e.upNext || e.progress >= WatchHistory.FINISHED) return 0L
        return if (e.progress > 0f) e.positionMs.coerceAtLeast(0) else null
    }

    /** Fills ids and artwork (backdrop, poster, title logo, episode title) from TMDB. Blocking; WatchHistory's bounded cache. */
    fun complete(ctx: Context, r: PlaybackRequest): PlaybackRequest {
        if (!Tmdb.hasKey(ctx)) return r
        var out = r
        val d = WatchHistory.details(ctx, tmdbType(r), r.tmdbId)
        if (d != null) {
            val imdb = d.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") }
            val year = d.optString(if (r.type == "episode") "first_air_date" else "release_date").take(4).toIntOrNull()
            out = out.copy(
                imdb = out.imdb ?: imdb, year = out.year ?: year,
                backdrop = out.backdrop ?: img(d.optString("backdrop_path"), "w1280"),
                poster = out.poster ?: img(d.optString("poster_path"), "w342"),
            )
        }
        if (out.logo == null) out = out.copy(logo = logoUrl(ctx, r))
        if (r.type == "episode" && out.episodeTitle == null && r.season != null && r.episode != null) {
            WatchHistory.seasonEpisodes(ctx, r.tmdbId, r.season)?.firstOrNull { it.optInt("episode_number") == r.episode }
                ?.optString("name")?.ifEmpty { null }?.let { out = out.copy(episodeTitle = it) }
        }
        return out
    }

    /** The English (or language-neutral) title logo, if TMDB has one. Blocking. */
    private fun logoUrl(ctx: Context, r: PlaybackRequest): String? {
        val a = WatchHistory.tmdb(ctx, "/${tmdbType(r)}/${r.tmdbId}/images", "&include_image_language=en,null")?.optJSONArray("logos") ?: return null
        val logos = (0 until a.length()).map { a.getJSONObject(it) }.filter { !it.optString("file_path").endsWith(".svg") }
        val best = logos.firstOrNull { it.optString("iso_639_1") == "en" } ?: logos.firstOrNull() ?: return null
        return img(best.optString("file_path"), "w500")
    }

    // One logo kept decoded (ARGB, unlike Net's RGB_565 posters: logos are transparent PNGs).
    private var logoCache: Pair<String, Bitmap>? = null

    /** Decodes a title logo (cached on disk next to Net's images); [done] on the main thread. */
    fun logo(ctx: Context, url: String, done: (Bitmap?) -> Unit) {
        synchronized(this) { logoCache?.takeIf { it.first == url }?.let { done(it.second); return } }
        val dir = File(ctx.cacheDir, "img").apply { mkdirs() }
        Net.background({
            val f = File(dir, "logo_" + Integer.toHexString(url.hashCode()))
            if (!f.exists()) {
                val c = URL(url).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 8000; c.readTimeout = 10000; c.useCaches = false
                    if (c.responseCode in 200..299) {
                        val tmp = File(dir, f.name + ".tmp")
                        c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                        tmp.renameTo(f)
                    }
                } finally { c.disconnect() }
            }
            if (f.exists()) BitmapFactory.decodeFile(f.path) else null
        }) { bmp ->
            if (bmp != null) synchronized(this) { logoCache = url to bmp }
            done(bmp)
        }
    }

    /** What comes after an episode: the next request, its still, overview and runtime. */
    class NextUp(val request: PlaybackRequest, val still: String?, val overview: String, val runtimeMin: Int)

    /**
     * The aired episode after [season] [episode] of show [showId] and the season it's in: the next one in that season,
     * else the lowest episode of the next season the show lists (specials, season 0, never count). Null at the end
     * of the series or when it hasn't aired. Continue Watching's Up Next uses the same rule. Blocking.
     */
    fun episodeAfter(ctx: Context, showId: Int, season: Int, episode: Int): Pair<Int, JSONObject>? {
        val today = DreamTime.todayYmd(ctx)
        fun aired(ep: JSONObject) = ep.optString("air_date").let { it.isNotEmpty() && it != "null" && it <= today } ||
            Availability.outEarly(ctx, showId, ep.optInt("season_number"), ep.optInt("episode_number")) // up before its air day
        var s = season
        var next = WatchHistory.seasonEpisodes(ctx, showId, season)?.firstOrNull { it.optInt("episode_number") == episode + 1 }
        if (next == null) {
            val seasons = WatchHistory.details(ctx, "tv", showId)?.optJSONArray("seasons")
            s = seasons?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optInt("season_number") }.filter { it > season }.minOrNull() }
                ?: return null
            next = WatchHistory.seasonEpisodes(ctx, showId, s)?.minByOrNull { it.optInt("episode_number") }
        }
        if (next == null || !aired(next)) return null
        return s to next
    }

    /** The episode after [r] ([episodeAfter]), or null at the end of the series / when the next one hasn't aired. Blocking. */
    fun nextEpisode(ctx: Context, r: PlaybackRequest): NextUp? {
        if (r.type != "episode") return null
        val (s, next) = episodeAfter(ctx, r.tmdbId, r.season ?: return null, r.episode ?: return null) ?: return null
        val req = r.copy(season = s, episode = next.optInt("episode_number"), episodeTitle = next.optString("name").ifEmpty { null }, startMs = 0)
        return NextUp(req, img(next.optString("still_path"), "w500"), next.optString("overview"), next.optInt("runtime"))
    }

    // ---- playing ----

    /** One resolve: what the curtain listens to. */
    class Session(val request: PlaybackRequest) {
        @Volatile var cancelled = false
        @Volatile var done = false
        @Volatile var failure: String? = null
        /** What the curtain shows for [failure] (title, one calm line); null: its usual wording. */
        @Volatile var failureText: Pair<String, String>? = null
        /** A short note about what plays ("Only a CAM Version Is Available"): on the curtain, then once as the picture starts. */
        @Volatile var note: String? = null

        fun isFor(r: PlaybackRequest?) = r != null && r.tmdbId == request.tmdbId && r.season == request.season && r.episode == request.episode
    }

    @Volatile var session: Session? = null
        private set

    /**
     * Main-thread listener (the curtain) for the current session's status changes and failures. Not per
     * session: the player puts its curtain up for the next episode before [play] starts that resolve.
     */
    var listener: ((Session) -> Unit)? = null

    /** Consecutive episodes the countdown autoplayed without a key press ("Are you still watching?"). */
    @Volatile var autoplayedInARow = 0

    /**
     * Plays [req] in Dream Player: opens the player screen now and resolves the stream with Dream Core
     * behind it. Resumes from the watch history: its saved spot is the freshest, so it wins over a position a
     * card or page carried (a Continue Watching card built before the last viewing still holds the older spot),
     * and a title it has as finished starts from the beginning ([historyStart]).
     */
    fun play(ctx: Context, req: PlaybackRequest) {
        val saved = historyStart(ctx, req)
        val r = if (saved != null) req.copy(startMs = saved) else req
        Log.w(TAG, "Play flow: ${r.title}${if (r.type == "episode") " S${r.season}E${r.episode}" else ""}${if (r.startMs > 0) " from ${r.startMs / 1000} s" else ""}")
        // Instant Play: the stream was found while the details page was read; the player opens with it.
        PreResolve.take(r)?.let { link ->
            session?.let { if (!it.done) it.cancelled = true }
            session = Session(r) // a clean one: the curtain checks the current session for failures
            try {
                deliver(ctx, r, link)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Play flow: couldn't open Dream Player with the ready stream: $e")
            }
        }
        try {
            // Its own task (taskAffinity): leaving it always lands back in Dream OS.
            ctx.startActivity(DreamPlayerActivity.intentFor(ctx, r))
        } catch (e: Exception) {
            Log.w(TAG, "Play flow: couldn't open Dream Player: $e")
            return
        }
        resolve(ctx, r)
    }

    /**
     * Opens Dream Player with a stream resolved ahead ([PreResolve]): the link's VIEW intent, carrying
     * [r] (so it resumes from [r]'s startMs). Any thread.
     *
     * A link whose start didn't make it to disk (the prefetch was behind, or never ran) first gets
     * the fast start ([StreamCache.fastStart]) behind the curtain, like a cold resolve: on the main
     * thread the player screen opens waiting and the fetch runs in the background.
     */
    internal fun deliver(ctx: Context, r: PlaybackRequest, link: PreResolve.Link) {
        Log.w(TAG, "Play flow: instant play, stream ready ${(SystemClock.elapsedRealtime() - link.at) / 1000} s ago")
        val app = ctx.applicationContext
        val send = {
            app.startActivity(Intent(link.intent).setClass(app, DreamPlayerActivity::class.java)
                .putExtra(EXTRA_REQUEST, toJson(r).toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
        val url = link.intent.dataString
        if (url == null || StreamCache.ready(url)) return send()
        NativeResolver.peek(r)?.takeIf { it.url == url }?.let { NativeResolver.handing(it) }
        val s = session
        val fast = {
            StreamCache.fastStart(app, url, emptyMap(), r.startMs, knownDuration(app, r), link.intent.getLongExtra(EXTRA_SIZE, 0))
            if (s?.cancelled != true && session === s) send()
        }
        if (Looper.myLooper() != Looper.getMainLooper()) return fast()
        ctx.startActivity(DreamPlayerActivity.intentFor(ctx, r))
        Net.runNow(fast)
    }

    /** The title's duration from the watch history (0 = unknown): where a resume position falls in the file. */
    internal fun knownDuration(app: Context, r: PlaybackRequest): Long =
        if (r.startMs <= 0) 0L
        else WatchHistory.entries(app).firstOrNull { it.tmdbId == r.tmdbId && (it.type == "episode") == (r.type == "episode") }?.durationMs ?: 0L

    /**
     * The intent that opens Dream Player with a stream Dream Core found ([NativeResolver]): a VIEW intent
     * (data = the URL, type video) carrying [r] as [EXTRA_REQUEST] and the file's size. Its own task, no
     * animation. Also the intent a [PreResolve.Link] keeps.
     */
    internal fun streamIntent(ctx: Context, url: String, r: PlaybackRequest, sizeBytes: Long = 0): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*").putExtra("title", r.title).putExtra(EXTRA_SIZE, sizeBytes)
            .putExtra(EXTRA_REQUEST, toJson(r).toString())
            .setClass(ctx.applicationContext, DreamPlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    /**
     * Resolves [req] in the background with Dream Core; the stream reaches the player as a VIEW intent
     * ([streamIntent]). Also for the player itself without opening the screen again ([PlayerHost.playNext],
     * Try Again). [loose] (Search All Sources): the same, with the source filters set aside for this one search.
     * Nothing found: the curtain says why ([NOT_YET_TEXT], [NOT_READY_TEXT], or what's missing in setup).
     */
    fun resolve(ctx: Context, req: PlaybackRequest, loose: Boolean = false): Session {
        val app = ctx.applicationContext
        session?.let { if (!it.done) it.cancelled = true } // superseded
        val s = Session(req)
        session = s
        Net.runNow {
            // Instant Play: a link that got ready in the meantime goes to the player now; a lookup for this very
            // title under way is joined by find() below; one for another title is dropped.
            if (PreResolve.handOver(app, req)) return@runNow
            val t0 = SystemClock.elapsedRealtime()
            // Concurrent lookups for the same title share one (a pre-resolve under way, the player's retry).
            val found = if (loose) NativeResolver.findLoose(app, req) else NativeResolver.find(app, req)
            val secs = (SystemClock.elapsedRealtime() - t0) / 100 / 10.0
            if (s.cancelled || session !== s) return@runNow
            if (found != null) {
                Log.w(TAG, "Play flow: Dream Core stream (${found.service.uppercase()} ${found.quality}) in $secs s")
                // Nothing passed the user's filters (only a CAM copy, say): played anyway, with a short note.
                NativeResolver.relaxed(req, found)?.let { rx ->
                    s.note = if (rx.cam) CAM_NOTE else BEST_NOTE
                    main.post { if (session === s) listener?.invoke(s) }
                }
                // Fast start: what the player reads before the first frame goes to disk first, in parallel (bounded).
                NativeResolver.handing(found)
                StreamCache.fastStart(app, found.url, emptyMap(), req.startMs, knownDuration(app, req), found.sizeBytes)
                if (s.cancelled || session !== s) return@runNow
                try {
                    app.startActivity(streamIntent(app, found.url, req, found.sizeBytes))
                } catch (e: Exception) {
                    Log.w(TAG, "Play flow: couldn't open Dream Player with the Dream Core stream: $e")
                    fail(s, "couldn't open the player: $e")
                }
                return@runNow
            }
            when {
                // Setup isn't finished: say what's missing (Settings has both).
                DebridAccounts.get(app)?.any != true -> fail(s, "no debrid service linked", NO_ACCOUNT_TEXT)
                !Scrapers.active(app) -> fail(s, "no scraper sources added", NO_SCRAPERS_TEXT)
                // AllDebrid is at its magnet limit and no other linked service had it (remembered ~30 min, then tried again).
                NativeResolver.limitBlocked(app) -> fail(s, "AllDebrid magnet limit; nothing elsewhere", LIMIT_TEXT)
                NativeResolver.miss(req) == NativeResolver.Miss.NOTHING -> fail(s, "Dream Core: nothing playable found in $secs s", NOT_YET_TEXT)
                // A search that got no answer is never "not out yet" (a failed or timed-out check says nothing of the title).
                NativeResolver.miss(req) == NativeResolver.Miss.NO_ANSWER -> fail(s, "Dream Core: the scrapers didn't answer in $secs s", NO_ANSWER_TEXT)
                else -> fail(s, "Dream Core: nothing ready to stream in $secs s", NOT_READY_TEXT)
            }
        }
        return s
    }

    /** The curtain's words when the debrid account is at its magnet limit. */
    private val LIMIT_TEXT = "Can't Play Right Now" to "Your AllDebrid account has reached its magnet limit. It frees up automatically; try again later."
    /** No debrid service linked yet. */
    private val NO_ACCOUNT_TEXT = "Connect a Debrid Service" to "Add one in Settings › Accounts & Scrapers, then press Play again."
    /** No scraper repository added yet. */
    private val NO_SCRAPERS_TEXT = "Add a Scraper Repository" to "Add one in Settings › Accounts & Scrapers › Scrapers, then press Play again."
    /** Nothing playable anywhere (not released, or no copy at all yet). */
    internal val NOT_YET_TEXT = "This Title Isn't Available Yet" to "There's no version to stream yet. Check back soon."
    /** The scrapers didn't answer (every provider failed or timed out). */
    internal val NO_ANSWER_TEXT = "Couldn't Search for Streams" to "Your scrapers didn't answer. Try again in a moment."
    /** Copies exist but none is ready to stream (also the curtain's own timeout). */
    internal val NOT_READY_TEXT = "Couldn't Find a Ready Stream" to "Try again in a moment, or choose another source."
    const val CAM_NOTE = "Only a CAM Version Is Available"
    const val BEST_NOTE = "Playing the Best Available Version"

    private fun fail(s: Session, msg: String, text: Pair<String, String>? = null) {
        if (s.cancelled || s.done) return
        Log.w(TAG, "Play flow: $msg")
        s.failureText = text
        s.failure = msg
        main.post { if (session === s) listener?.invoke(s) }
    }

    /** The stream is playing (the curtain lifted). */
    fun markPlaying() {
        session?.done = true
    }

    /**
     * Whether [req] belongs to a resolve the user cancelled (BACK on the curtain): a stream that still
     * arrives for it is ignored. For Dream Player when a URL arrives.
     */
    fun isCancelled(req: PlaybackRequest?): Boolean {
        val s = session ?: return false
        return s.cancelled && req != null && s.request.tmdbId == req.tmdbId && s.request.season == req.season && s.request.episode == req.episode
    }

    /** The user backed out (BACK on the curtain): the resolve's result is dropped (its lookup finishes and is remembered). */
    fun cancel(ctx: Context) {
        val s = session ?: return
        if (s.done || s.cancelled) return
        s.cancelled = true
        Log.w(TAG, "Play flow: cancelled ${s.request.title}")
    }

    /** Brings the paused Dream Player back to the front (Back from a title page X-Ray opened). */
    internal fun bringPlayerToFront(app: Context) {
        main.post {
            val am = app.getSystemService(ActivityManager::class.java)
            val task = try {
                am?.appTasks?.firstOrNull { t ->
                    val info = t.taskInfo
                    info.topActivity?.className == PLAYER_CLASS || info.baseActivity?.className == PLAYER_CLASS
                }
            } catch (e: Exception) { null }
            try {
                if (task != null) task.moveToFront()
                else app.startActivity(Intent().setClassName(app.packageName, PLAYER_CLASS).putExtra(EXTRA_FRONT_ONLY, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                Log.w(TAG, "Play flow: Dream Player back in front (${if (task != null) "task" else "intent"})")
            } catch (e: Exception) {
                Log.w(TAG, "Play flow: couldn't bring Dream Player back: $e")
            }
        }
    }

    /** Pre-opens Dream Core's connections (and its scraper engine), so the next resolve starts at once. Returns at once. */
    fun warmUp(ctx: Context) = NativeResolver.warm(ctx.applicationContext)

    /**
     * Leaves the player for Dream OS's details page of [t]. [backToPlayer] (X-Ray): Back from that page brings the
     * paused player back instead of going on to Home ([MainActivity.EXTRA_BACK_TO_PLAYER]).
     */
    fun openDetails(ctx: Context, t: Title, backToPlayer: Boolean = false) {
        val o = JSONObject().put("id", t.id).put("type", t.type).put("name", t.name).put("overview", t.overview)
            .put("poster", t.posterPath ?: "").put("backdrop", t.backdropPath ?: "").put("year", t.year).put("rating", t.rating)
        try {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_TITLE, o.toString())
                .putExtra(MainActivity.EXTRA_BACK_TO_PLAYER, backToPlayer)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) {
            Log.w(TAG, "Play flow: couldn't open details: $e")
        }
    }

    /** [MainActivity.EXTRA_OPEN_TITLE]'s JSON back to a Title. */
    fun titleFrom(json: String?): Title? = try {
        JSONObject(json ?: "").let {
            Title(it.getInt("id"), it.getString("type"), it.optString("name"), it.optString("overview"),
                it.optString("poster").ifEmpty { null }, it.optString("backdrop").ifEmpty { null }, it.optString("year"), it.optDouble("rating", 0.0))
        }
    } catch (e: Exception) { null }

    // ---- labels for Dream OS's play buttons ----

    /**
     * Label for a title's main play button ("Play", "Resume", "Play S1 E1", "Resume S2 E5"). [next]:
     * the show's in-progress / up-next episode from the watch history.
     */
    fun playLabel(ctx: Context, t: Title, next: WatchHistory.Entry?): String {
        if (t.type != "tv") {
            val e = WatchHistory.entries(ctx).firstOrNull { it.key == t.key }
            val resume = e != null && e.progress > 0f && e.progress < WatchHistory.FINISHED && ours(e)
            return if (resume) "Resume" else "Play"
        }
        if (next == null) return "Play S1 E1"
        val verb = if (next.upNext || next.progress <= 0f) "Play" else "Resume"
        return "$verb S${next.season} E${next.episode}"
    }
}

/**
 * Full-screen loading curtain shown while Dream Core finds a stream; also shows errors.
 * The backdrop (never tinted: only a side and a bottom scrim under the text), a small spinner with
 * "Now Playing" in green, then the title logo (or title) and S·E. No status text: BACK still cancels.
 * The spinner turns only while the curtain is up and loading (stopped on [hide], errors and detach).
 */
class LoadingCurtain(private val host: PlayerHost) {
    private val ctx = host.overlayRoot.context
    private val kit = PremiumKit(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private val Int.dp get() = (this * density).toInt()

    private var root: FrameLayout? = null
    private var req: PlaybackRequest? = null
    private var spinner: Spinner? = null
    private var nowRow: View? = null
    private var errorBlock: LinearLayout? = null
    private var errorTitle: TextView? = null
    private var errorDetail: TextView? = null
    private var errorRow: LinearLayout? = null
    private var noteView: TextView? = null
    private var buttons: List<TextView> = emptyList()
    private var selected = 0
    private var resolving = false

    val isShowing get() = root != null

    private val timeout = Runnable {
        if (root == null || !resolving) return@Runnable
        // The stream already came and is loading (slow start): keep waiting for its first frame.
        if (host.engine.isBuffering || host.engine.durationMs > 0) { main.postDelayed(timeoutAgain, 15_000); return@Runnable }
        PlayFlow.cancel(ctx)
        // Never a spinner forever: the resolve's time is up, the curtain says so plainly.
        showError("resolve timed out", PlayFlow.NOT_READY_TEXT)
    }
    private val timeoutAgain: Runnable = Runnable { timeout.run() }

    fun show(req: PlaybackRequest?) {
        main.removeCallbacksAndMessages(null)
        spinner?.stop()
        root?.let { old -> root = null; (old.parent as? ViewGroup)?.removeView(old) }
        this.req = req
        resolving = true
        // A trailer: Dream Player resolves it itself (no play flow).
        val trailer = req?.trailer == true
        val screenW = ctx.resources.displayMetrics.widthPixels
        val screenH = ctx.resources.displayMetrics.heightPixels
        // Dream Cinema: the Dream sky under the art (not flat black) and scrims in its deepest tone.
        val shade = PlayerLook.shade(ctx)
        val r = FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Ui.mix(Theme.get(ctx, Theme.BG_TOP), Color.BLACK, 0.3f), Ui.mix(Theme.get(ctx, Theme.BG_BOTTOM), Color.BLACK, 0.2f)))
            // The error buttons lift (scale + glow) past the text column's edge: the curtain never cuts them.
            clipChildren = false
            clipToPadding = false
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            // Keys reach the curtain while it holds focus, even if the player doesn't route them here.
            setOnKeyListener { _, _, e -> onKey(e) }
        }
        r.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                // The player went away mid-resolve: the resolve is cancelled too.
                if (root === v && trailer) {
                    main.removeCallbacksAndMessages(null)
                    spinner?.stop()
                    resolving = false
                    root = null
                } else if (root === v) {
                    main.removeCallbacksAndMessages(null)
                    spinner?.stop()
                    PlayFlow.listener = null // static: mustn't keep the player alive
                    if (resolving) { resolving = false; PlayFlow.cancel(ctx) }
                    root = null
                }
            }
        })
        val backdrop = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        r.addView(backdrop, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Side and bottom scrims under the text only; the rest of the artwork stays untouched.
        r.addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Ui.alpha(shade, 0.80f), Ui.alpha(shade, 0.43f), Color.TRANSPARENT))
        }, FrameLayout.LayoutParams((screenW * 0.62f).toInt(), MATCH_PARENT, Gravity.START))
        r.addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Ui.alpha(shade, 0.75f), Color.TRANSPARENT))
        }, FrameLayout.LayoutParams(MATCH_PARENT, (screenH * 0.42f).toInt(), Gravity.BOTTOM))

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        // Spinner + "NOW PLAYING" (a green kicker), above the title.
        val sp = Spinner(ctx, kit.select, kit.blush, 2.5f * density, rainbow = true)
        val now = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // The label keeps the page's left line; the spinner follows it, centered on the text.
            addView(kit.label("NOW PLAYING", Ui.T_CAPTION, NOW_GREEN, kit.semibold, Ui.TRACK_CAPS))
            addView(sp, LinearLayout.LayoutParams(20.dp, 20.dp).apply { marginStart = 12.dp })
        }
        col.addView(now, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = Ui.S4.dp })
        val logoView = ImageView(ctx).apply {
            adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START; visibility = View.GONE
            maxWidth = 440.dp; maxHeight = 140.dp
        }
        col.addView(logoView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = Ui.S5.dp })
        val titleView = kit.label(req?.title ?: "", Ui.T_DISPLAY + 4f, kit.text, kit.bold, Ui.TRACK_TITLE, 2)
        col.addView(titleView, LinearLayout.LayoutParams(560.dp, WRAP_CONTENT).apply { bottomMargin = Ui.S3.dp })
        val subView = kit.label("", Ui.T_SECTION, Ui.alpha(kit.text, 0.86f), kit.medium, 0f, 1)
        col.addView(subView, LinearLayout.LayoutParams(620.dp, WRAP_CONTENT))
        fun setSub(q: PlaybackRequest?) {
            val s = subtitle(q)
            subView.text = s
            subView.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
        }
        setSub(req)
        // What plays when the user's filters had to be set aside ("Only a CAM Version Is Available"): amber, like the CAM Only pill.
        val nv = kit.label("", Ui.T_BODY, Availability.Status.CAM.tint, kit.semibold, 0f, 1).apply { visibility = View.GONE }
        col.addView(nv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = Ui.S3.dp })
        noteView = nv
        // Error (hidden while loading): a plain title, one calm line, Try Again / Search All Sources / Change Source / Back.
        val eb = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; clipChildren = false; clipToPadding = false }
        val et = kit.label(if (trailer) "Trailer Unavailable" else ERROR_TITLE, Ui.T_SECTION, kit.text, kit.semibold)
        eb.addView(et, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = Ui.S1.dp })
        val ed = kit.label(if (trailer) "This trailer can't be played right now. Please try again in a moment."
            else ERROR_DETAIL, Ui.T_BODY, Ui.alpha(kit.text, 0.7f), kit.medium, 0f, 2)
        eb.addView(ed, LinearLayout.LayoutParams(560.dp, WRAP_CONTENT).apply { bottomMargin = Ui.S5.dp })
        errorTitle = if (trailer) null else et; errorDetail = if (trailer) null else ed
        val err = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        eb.addView(err, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        col.addView(eb, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = Ui.S6.dp })
        r.addView(col, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = PremiumKit.EDGE.dp; bottomMargin = 84.dp
        })
        root = r; spinner = sp; nowRow = now; errorBlock = eb; errorRow = err; buttons = emptyList()
        host.overlayRoot.addView(r, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        r.requestFocus()
        r.alpha = 0f
        r.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        sp.start()

        fun art(q: PlaybackRequest) {
            if (root !== r) return
            if (backdrop.tag == null) q.backdrop?.let { url ->
                backdrop.tag = url
                Net.bitmap(ctx, url) { bmp ->
                    if (bmp == null || root !== r) return@bitmap
                    backdrop.setImageBitmap(bmp)
                    backdrop.animate().alpha(1f).setDuration(Ui.FADE_IMAGE + 240).setInterpolator(Ui.EASE).start()
                }
            }
            if (logoView.tag == null) q.logo?.let { url ->
                logoView.tag = url
                PlayFlow.logo(ctx, url) { bmp ->
                    if (bmp == null || root !== r) return@logo
                    logoView.setImageBitmap(bmp)
                    logoView.visibility = View.VISIBLE
                    titleView.visibility = View.GONE
                }
            }
            if (subView.visibility == View.GONE || q.episodeTitle != null) setSub(q)
        }
        if (trailer) {
            // Dream Player reports the result itself (first frame or showError); the play flow isn't involved.
            art(req!!)
            return
        }
        if (req != null) {
            art(req)
            if (req.backdrop == null || req.logo == null || (req.type == "episode" && req.episodeTitle == null)) {
                Net.background({ PlayFlow.complete(ctx, req) }) { full -> if (full != null) art(full) }
            }
        }
        // Failures (and notes) from the play flow.
        PlayFlow.listener = { onSession(it) }
        // A resolve already under way (or failed) for this title; checked after the caller's play() has run.
        main.post { PlayFlow.session?.let { onSession(it) } }
        main.postDelayed(timeout, PlayFlow.RESOLVE_TIMEOUT_MS)
    }

    private fun onSession(s: PlayFlow.Session) {
        if (root == null || !resolving || (req != null && !s.isFor(req))) return
        s.note?.let { n ->
            noteView?.takeIf { it.text != n }?.let { v ->
                v.text = n
                v.visibility = View.VISIBLE
                v.alpha = 0f
                v.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
            }
        }
        s.failure?.let { showError(it, s.failureText) }
    }

    private fun subtitle(r: PlaybackRequest?): String {
        if (r == null) return ""
        if (r.trailer) return "Trailer"
        if (r.type != "episode" || r.season == null || r.episode == null) return r.year?.toString() ?: ""
        return "S${r.season} · E${r.episode}" + (r.episodeTitle?.let { "    $it" } ?: "")
    }

    fun hide() {
        val r = root ?: return
        resolving = false
        if (req?.trailer != true) {
            PlayFlow.markPlaying()
            PlayFlow.listener = null
        }
        main.removeCallbacksAndMessages(null)
        spinner?.stop()
        root = null
        r.animate().alpha(0f).setDuration(Ui.OVERLAY_IN + 60).setInterpolator(Ui.SETTLE).withEndAction {
            (r.parent as? FrameLayout)?.removeView(r)
        }.start()
    }

    /** The error state; [message] is only logged (the screen never shows technical wording). */
    fun showError(message: String, text: Pair<String, String>? = null) {
        if (root == null) show(req ?: host.request)
        val r = root ?: return
        errorTitle?.text = text?.first ?: ERROR_TITLE
        errorDetail?.text = text?.second ?: ERROR_DETAIL
        if (message.isNotBlank()) Log.w("FireHome", "Play flow: curtain error: $message")
        resolving = false
        main.removeCallbacks(timeout)
        main.removeCallbacks(timeoutAgain)
        PlayFlow.listener = null
        spinner?.stop()
        nowRow?.visibility = View.GONE
        noteView?.visibility = View.GONE
        errorBlock?.let { b ->
            b.visibility = View.VISIBLE
            b.alpha = 0f
            b.translationY = Ui.S3.dp.toFloat()
            b.animate().alpha(1f).translationY(0f).setDuration(Ui.SHEET_IN).setInterpolator(Ui.EASE).start()
        }
        val row = errorRow ?: return
        row.removeAllViews()
        val q = req ?: host.request
        val player = host as? DreamPlayerActivity
        if (q?.trailer == true) {
            // A trailer: look it up again, or go back to the details page. Nothing else.
            val again = button("Try Again") { player?.retryTrailer() ?: host.close() }
            val back = button("Back") { host.close() }
            row.addView(again)
            row.addView(back)
            buttons = listOf(again, back)
            select(0)
            r.requestFocus()
            return
        }
        val retry = button("Try Again") {
            when {
                player != null -> player.tryAnotherSource() // saves the spot, curtain back up, play flow again
                q != null -> { PlayFlow.resolve(ctx, q); show(q) }
            }
        }
        row.addView(retry)
        // One search with the user's source filters set aside (their settings stay as they are).
        val all = if (player != null) button("Search All Sources") { player.searchAllSources() } else null
        all?.let { row.addView(it) }
        // Pick a source by hand (Dream Core's list, in a side sheet over this curtain).
        val change = if (q != null && q.tmdbId > 0) button("Change Source") { SourcePicker.open(host) } else null
        change?.let { row.addView(it) }
        // Back to where the user was.
        val back = button("Back") { host.close() }
        row.addView(back)
        buttons = listOfNotNull(retry, all, change, back)
        select(0)
        r.requestFocus()
    }

    private fun button(label: String, onClick: () -> Unit) = kit.label(label, Ui.T_BODY, kit.text, kit.semibold).apply {
        gravity = Gravity.CENTER
        setPadding(26.dp, 0, 26.dp, 0)
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, 46.dp).apply { marginEnd = Ui.S3.dp }
        setOnClickListener { onClick() }
    }

    private fun select(i: Int) {
        selected = i.coerceIn(0, (buttons.size - 1).coerceAtLeast(0))
        buttons.forEachIndexed { j, b -> PostPlayStyle.pillState(kit, b, j == selected) }
    }

    /** Keys while the curtain is up (the player may route them here first); true if consumed. */
    fun onKey(event: KeyEvent): Boolean {
        if (root == null) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (buttons.isNotEmpty()) select(selected - 1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (buttons.isNotEmpty()) select(selected + 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                buttons.getOrNull(selected)?.performClick()
            else -> {}
        }
        return true // nothing under the curtain should react
    }

    /** BACK on the curtain: cancels the resolve and leaves the player. True if handled. */
    fun onBack(): Boolean {
        if (root == null) return false
        if (resolving) { resolving = false; if (req?.trailer != true) PlayFlow.cancel(ctx) }
        main.removeCallbacksAndMessages(null)
        spinner?.stop()
        host.close()
        return true
    }

    /**
     * A thin ring with a soft tail (theme select → blush, rounded head over a faint track), drawn
     * once: turning rotates the view's render node, so nothing is redrawn per frame. Turns only
     * between [start] and [stop], and never while detached or its window is hidden.
     */
    /** [rainbow]: the arc runs through the Dream rainbow (from a clear tail) instead of [tail] into [head]. */
    internal class Spinner(ctx: Context, private val head: Int, private val tail: Int, private val stroke: Float, private val rainbow: Boolean = false) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = Color.argb(46, 255, 255, 255)
        }
        private val oval = RectF()
        private var spin: ObjectAnimator? = null
        private var wanted = false
        /**
         * [rainbow]: the ring is an AnimatedVectorDrawable (res/drawable/spinner_rainbow.xml, drawn for 20 dp with a
         * 2.5 dp stroke), whose turn runs on the RenderThread: it keeps turning while the main thread is busy
         * (Dream Player's start, ExoPlayer's setup), where an ObjectAnimator would freeze.
         */
        private val ring = if (rainbow) (ctx.getDrawable(R.drawable.spinner_rainbow)?.mutate() as? AnimatedVectorDrawable) else null
        private var ringOn = false

        init { ring?.let { background = it } }

        fun start() { wanted = true; sync() }
        fun stop() { wanted = false; sync() }

        private fun sync() {
            val run = wanted && isAttachedToWindow && windowVisibility == VISIBLE
            ring?.let { r ->
                if (run && !ringOn) { ringOn = true; r.start() } else if (!run && ringOn) { ringOn = false; r.stop() }
                return
            }
            if (run && spin == null) {
                spin = ObjectAnimator.ofFloat(this, ROTATION, rotation, rotation + 360f).apply {
                    duration = 900
                    interpolator = LinearInterpolator()
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            } else if (!run && spin != null) {
                spin?.cancel()
                spin = null
            }
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); sync() }
        // Detached (the screen went away): pause, resume on attach.
        override fun onDetachedFromWindow() { spin?.cancel(); spin = null; if (ringOn) { ringOn = false; ring?.stop() }; super.onDetachedFromWindow() }
        override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); sync() }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            if (ring != null) return
            // Transparent tail to the head over the 300° arc (a sweep starts at 3 o'clock).
            paint.shader = if (rainbow) SweepGradient(w / 2f, h / 2f, intArrayOf(Color.TRANSPARENT) + Rainbow.COLORS, null)
            else SweepGradient(w / 2f, h / 2f,
                intArrayOf(Color.TRANSPARENT, Ui.alpha(tail, 0.55f), head, head), floatArrayOf(0f, 0.45f, 0.8f, 1f))
        }

        override fun onDraw(c: Canvas) {
            if (ring != null) return // the background draws it
            val inset = stroke / 2f + 1f
            oval.set(inset, inset, width - inset, height - inset)
            c.drawOval(oval, track)
            c.drawArc(oval, 0f, 300f, false, paint)
        }
    }

    private companion object {
        /** The soft green Dream OS uses for "Connected" / "Loading Complete". */
        val NOW_GREEN = Color.parseColor("#4ADE80")
        const val ERROR_TITLE = "This Title Isn't Available Right Now"
        const val ERROR_DETAIL = "Try again in a moment, or choose another source."
    }
}
