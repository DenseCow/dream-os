package com.firehome.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "New Episode": the moment a followed show's new episode is out ([NewEpisodes] finds them; "followed" is
 * [NewEpisodes.followedShows]). This decides which one Home's banner offers ([NewEpisodeBanner]), tells the
 * user once through a notice ([DreamNotices], banner only: it waits for Home, never over playback), marks
 * show cards in rows ([badged]) and has the episode's stream found ahead while Home sits idle, so Play
 * starts at once ([prepare], through [PreResolve.idleAhead]).
 *
 * An episode is offered when it came out in the last [BANNER_DAYS] days, the user hasn't played or
 * dismissed it, it was first shown less than [RETIRE_MS] ago (no nagging), and the user is caught up: a show
 * only on My List always counts; a watched one only when this is the episode right after the last one
 * finished (or the Up Next one). Played and dismissed are kept per profile.
 *
 * Developer test (adb, see PingReceiver): `--es newep fake [--ei show <tmdb id> --ei season S --ei episode E]`
 * puts a test episode (aired today) on the banner; `--es newep reset` forgets the test and every played or
 * dismissed mark. Log lines start with "NewEp:" (tag FireHome).
 */
object NewEpisodeSpot {
    private const val TAG = "FireHome"
    /** Offered while at most this many days old (0 = today). */
    private const val BANNER_DAYS = 3
    /** Retired this long after the banner first showed it, played or not. */
    private const val RETIRE_MS = 48 * 60 * 60_000L
    /** Home's refresh while it stays open (network only when NewEpisodes says a show is due). */
    private const val CHECK_MS = 30 * 60_000L
    /** The remote must have rested this long before a pre-resolve starts (nothing shares frames with a scroll). */
    private const val REST_MS = 4_000L
    /** Nothing found (the episode isn't cached at the debrid service yet): tried again after this, times the tries. */
    private const val RETRY_MS = 20 * 60_000L
    private const val MAX_TRIES = 4
    /** A link that expired is found again only if the remote was used this recently (someone is watching). */
    private const val PRESENT_MS = 10 * 60_000L

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** TMDB ids of shows with an episode the banner would offer: their cards in rows wear "NEW EPISODE". */
    @Volatile var badged: Set<Int> = emptySet()
        private set

    /** The developer's test episode (PingReceiver), offered first. */
    @Volatile private var fake: NewEpisodes.Item? = null
    @Volatile private var lastLogged: String? = null

    // ---- which episode ----

    /**
     * After a New Episodes load: posts the notice for episodes found since the last refresh (once), updates
     * [badged] and returns the episode the banner should offer (the newest), or null. Blocking (prefs).
     */
    fun onSnapshot(ctx: Context, s: NewEpisodes.Snapshot): NewEpisodes.Item? {
        val app = ctx.applicationContext
        val history = try { WatchHistory.recent(app) } catch (e: Exception) { emptyList() }
        val done = stringSet(app, "done")
        val first = firstShown(app)
        val now = System.currentTimeMillis()
        fun offered(i: NewEpisodes.Item) = !i.upcoming && i.key !in done && NewEpisodes.daysFromToday(i.ep.airDate) in -BANNER_DAYS..0 &&
            (first.optLong(i.key, now) > now - RETIRE_MS) && caughtUp(history, i)
        val list = (listOfNotNull(fake) + s.recent.filter(::offered)).distinctBy { it.key }
        badged = list.mapTo(HashSet()) { it.show.id }
        // Found by this refresh: one notice (it shows over Home only, never over playback).
        if (s.announce.isNotEmpty()) {
            val news = s.announce.filter(::offered)
            if (news.isNotEmpty()) notice(app, news)
            NewEpisodes.announced(app, s.announce)
        }
        prune(app, s)
        val nextAt = s.nextAt
        if (nextAt > 0) main.post { soonest(nextAt) }
        val pick = list.firstOrNull()
        val sig = pick?.key ?: "-"
        if (sig != lastLogged) {
            lastLogged = sig
            Log.w(TAG, "NewEp: ${s.recent.size} recent, ${list.size} for the banner" + (pick?.let { ": ${label(it)}" } ?: ""))
        }
        return pick
    }

    /**
     * Whether the user is caught up with [i]'s show: not in the history (followed from My List) yes; else
     * [i] is the Up Next episode, or the one right after the last one finished (a new season's first after
     * the last season). Watching it already, or behind: no (Continue Watching has it).
     */
    private fun caughtUp(history: List<WatchHistory.Entry>, i: NewEpisodes.Item): Boolean {
        val e = history.firstOrNull { it.isShow && it.tmdbId == i.show.id } ?: return true
        val s = i.ep.season
        val n = i.ep.number
        if (e.upNext) return e.season == s && e.episode == n
        if (e.season > s || (e.season == s && e.episode >= n)) return false
        val finished = e.progress >= WatchHistory.FINISHED
        return finished && ((e.season == s && e.episode == n - 1) || (e.season == s - 1 && n == 1))
    }

    private fun notice(app: Context, items: List<NewEpisodes.Item>, key: String = "newep:" + items.joinToString(",") { it.key }) {
        val names = items.map { it.show.name }.distinct()
        val title = if (items.size == 1) "New Episode" else "${items.size} New Episodes"
        val body = when {
            items.size == 1 -> "${items[0].show.name}  ·  ${code(items[0])}"
            names.size == 1 -> names[0]
            names.size == 2 -> "${names[0]} and ${names[1]}"
            else -> "${names[0]}, ${names[1]} and ${names.size - 2} More"
        }
        if (DreamNotices.post(app, key, DreamNotices.TYPE_EPISODES, title, body, store = false)) {
            Log.w(TAG, "NewEp: notice \"$title: $body\"")
        }
    }

    /** The banner showed [i] (its retirement clock starts the first time). Any thread. */
    fun displayed(ctx: Context, i: NewEpisodes.Item) = synchronized(lock) {
        val app = ctx.applicationContext
        val first = firstShown(app)
        if (first.has(i.key)) return@synchronized
        first.put(i.key, System.currentTimeMillis())
        prefs(app).edit().putString("first" + Profiles.suffix(app), first.toString()).apply()
    }

    /** Played or dismissed: never offered again (this profile). Any thread. */
    fun done(ctx: Context, i: NewEpisodes.Item, why: String) {
        val app = ctx.applicationContext
        synchronized(lock) {
            val set = stringSet(app, "done")
            if (set.add(i.key)) putSet(app, "done", set)
        }
        if (fake?.key == i.key) fake = null
        badged = badged - i.show.id
        Log.w(TAG, "NewEp: ${label(i)} $why")
    }

    /**
     * Keeps the per-profile marks to episodes still in the window (they leave it after a week anyway). Read again
     * under the lock: a Dismiss or a first showing written since [onSnapshot] read them must not be lost.
     */
    private fun prune(app: Context, s: NewEpisodes.Snapshot) = synchronized(lock) {
        val done = stringSet(app, "done")
        val first = firstShown(app)
        val keep = s.recent.mapTo(HashSet()) { it.key }.apply { fake?.let { add(it.key) } }
        val d = done.filterTo(HashSet()) { it in keep }
        if (d.size != done.size) putSet(app, "done", d)
        val stale = first.keys().asSequence().filter { it !in keep }.toList()
        if (stale.isNotEmpty()) {
            stale.forEach { first.remove(it) }
            prefs(app).edit().putString("first" + Profiles.suffix(app), first.toString()).apply()
        }
    }

    // ---- playing ----

    /** The exact request Play sends for [i] (the first episode of a batch drop). */
    fun request(i: NewEpisodes.Item) = PlaybackRequest(
        title = i.show.name, type = "episode", tmdbId = i.show.id, imdb = i.show.imdb, year = i.show.year,
        season = i.ep.season, episode = i.ep.number, episodeTitle = i.ep.name.ifBlank { null },
        backdrop = PlayFlow.img(i.show.backdrop, "w1280"), poster = PlayFlow.img(i.show.poster, "w342"),
    )

    /** "S3 · E7" ("S1 · E1–8" for a batch). */
    fun code(i: NewEpisodes.Item) = "S${i.ep.season} · E${i.ep.number}" + if (i.lastNumber > i.ep.number) "–${i.lastNumber}" else ""

    private fun label(i: NewEpisodes.Item) = "${i.show.name} S${i.ep.season}E${i.ep.number}"

    // ---- the stream, found ahead while Home is idle (one at a time, main thread) ----

    private class Prep {
        var running = false
        var startedAt = 0L
        var readyAt = 0L
        var failures = 0
        var interruptions = 0
        var retryAt = 0L
    }

    private val preps = HashMap<String, Prep>()
    private var owner: WeakReference<NewEpisodeBanner>? = null

    /**
     * One step of the banner's pre-resolve for [i]; returns when to ask again (ms), or -1 to stop. [idle]:
     * Home is in front, nothing is over it and nothing plays. Starts only after the remote rested
     * [REST_MS], never over another job, and finds an expired link again only while someone is around.
     * Main thread; cheap (no I/O).
     */
    fun prepare(ctx: Context, i: NewEpisodes.Item, idle: Boolean): Long {
        val app = ctx.applicationContext
        if (!InstantPlay.enabled(app) || !NativeResolver.available(app)) return 60_000
        val p = preps.getOrPut(i.key) { Prep() }
        if (p.running) return 2_000
        val req = request(i)
        val now = SystemClock.elapsedRealtime()
        if (PreResolve.ready(req)) return 60_000
        if (p.readyAt > 0) {
            // The link expired (or Play used it): again, but only for someone who's here.
            if (PreResolve.sinceKeyMs() > PRESENT_MS) return 60_000
            p.readyAt = 0
            Log.w(TAG, "NewEp: link for ${label(i)} expired; finding it again")
        }
        if (now < p.retryAt) return p.retryAt - now
        if (p.failures >= MAX_TRIES) return -1
        if (!idle) return 3_000
        val rest = PreResolve.sinceKeyMs()
        if (rest < REST_MS) return REST_MS - rest + 50
        val started = PreResolve.idleAhead(app, req) { ready, interrupted -> main.post { finished(i, p, ready, interrupted) } }
        if (!started) return 5_000
        p.running = true
        p.startedAt = now
        Log.w(TAG, "NewEp: pre-resolving ${label(i)} (Home idle ${rest / 1000} s)")
        return 2_000
    }

    private fun finished(i: NewEpisodes.Item, p: Prep, ready: Boolean, interrupted: Boolean) {
        p.running = false
        val now = SystemClock.elapsedRealtime()
        val ms = now - p.startedAt
        when {
            ready -> {
                p.readyAt = now
                p.failures = 0
                Log.w(TAG, "NewEp: pre-resolved ${label(i)} in $ms ms")
            }
            interrupted -> {
                p.interruptions++
                p.retryAt = now + if (p.interruptions > 5) 10 * 60_000L else 15_000L
                Log.w(TAG, "NewEp: pre-resolve of ${label(i)} interrupted after $ms ms; again once Home is idle")
            }
            else -> {
                p.failures++
                p.retryAt = now + RETRY_MS * p.failures
                Log.w(TAG, "NewEp: no stream yet for ${label(i)} after $ms ms (try ${p.failures} of $MAX_TRIES)")
            }
        }
        owner?.get()?.prepared(i.key, ready)
    }

    /** Play from the banner: logs whether the stream was ready. */
    fun playing(i: NewEpisodes.Item) {
        val ready = PreResolve.ready(request(i))
        Log.w(TAG, "NewEp: Play ${label(i)} (${if (ready) "stream ready: instant start" else "no stream ahead: resolving now"})")
    }

    // ---- Home's periodic check (while Dream OS is in front) ----

    /** [b] is Home's banner: every [CHECK_MS] while Dream OS is in front, New Episodes refreshes if due and [b] hears of it. */
    fun watch(b: NewEpisodeBanner) {
        owner = WeakReference(b)
        main.removeCallbacks(check)
        main.postDelayed(check, CHECK_MS)
    }

    /** The next exact air instant ahead: Home looks again a minute and a half after it, not at the next half hour. */
    private fun soonest(at: Long) {
        val wait = at - System.currentTimeMillis() + 90_000L
        if (wait <= 0 || wait >= CHECK_MS || owner?.get() == null) return
        main.removeCallbacks(check)
        main.postDelayed(check, wait)
        Log.w(TAG, "NewEp: next look at ${AirTimes.stamp(at + 90_000L)} (an episode airs)")
    }

    private val check: Runnable = object : Runnable {
        override fun run() {
            val b = owner?.get() ?: return
            main.removeCallbacks(this)
            main.postDelayed(this, CHECK_MS)
            if (!b.homeInFront() || DreamPlayerActivity.isActive()) return
            val app = b.context.applicationContext
            Net.background({ NewEpisodes.load(app) }) { s -> if (s != null) owner?.get()?.fresh(s) }
        }
    }

    // ---- developer test (PingReceiver) ----

    /** Puts a test episode on the banner: [showId]'s (else the first followed show's) latest or given episode, as aired today. */
    fun fake(ctx: Context, showId: Int, season: Int, episode: Int) {
        val app = ctx.applicationContext
        Net.run {
            val id = showId.takeIf { it > 0 } ?: NewEpisodes.followedShows(app).firstOrNull()
            if (id == null) { Log.w(TAG, "NewEp: test: no followed show (add one to My List, or pass --ei show <tmdb id>)"); return@run }
            val show = NewEpisodes.show(app, id)
            if (show == null) { Log.w(TAG, "NewEp: test: show $id not found"); return@run }
            val ep = if (season > 0) {
                WatchHistory.seasonEpisodes(app, id, season)?.firstOrNull { it.optInt("episode_number") == episode.coerceAtLeast(1) }
                    ?.let { NewEpisodes.Ep.fromTmdb(it) }
            } else show.latest.firstOrNull() ?: show.next
            if (ep == null) { Log.w(TAG, "NewEp: test: no episode for ${show.name}"); return@run }
            val today = DreamTime.todayYmd()
            val item = NewEpisodes.Item(show, NewEpisodes.Ep(ep.season, ep.number, ep.name, today, ep.still, ep.overview), ep.number, upcoming = false)
            synchronized(lock) {
                val set = stringSet(app, "done")
                if (set.remove(item.key)) putSet(app, "done", set)
                val first = firstShown(app)
                if (first.remove(item.key) != null) prefs(app).edit().putString("first" + Profiles.suffix(app), first.toString()).apply()
            }
            fake = item
            lastLogged = null
            Log.w(TAG, "NewEp: test episode ${label(item)} (developer)")
            notice(app, listOf(item), key = "newep:test:${System.currentTimeMillis()}")
            main.post { preps.remove(item.key); owner?.get()?.reload() }
        }
    }

    /** Forgets the test episode and every played / dismissed mark (this profile). */
    fun reset(ctx: Context) {
        val app = ctx.applicationContext
        fake = null
        lastLogged = null
        synchronized(lock) {
            prefs(app).edit().remove("done" + Profiles.suffix(app)).remove("first" + Profiles.suffix(app)).apply()
        }
        main.post { preps.clear(); owner?.get()?.reload() }
        Log.w(TAG, "NewEp: reset (test episode and played / dismissed marks cleared)")
    }

    // ---- storage (per profile) ----

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("new_episode_banner", Context.MODE_PRIVATE)

    private fun stringSet(ctx: Context, name: String): MutableSet<String> =
        HashSet(prefs(ctx).getStringSet(name + Profiles.suffix(ctx), emptySet()) ?: emptySet())

    private fun putSet(ctx: Context, name: String, set: Set<String>) =
        prefs(ctx).edit().putStringSet(name + Profiles.suffix(ctx), HashSet(set)).apply()

    private fun firstShown(ctx: Context): JSONObject =
        try { JSONObject(prefs(ctx).getString("first" + Profiles.suffix(ctx), null) ?: "{}") } catch (e: Exception) { JSONObject() }
}

/**
 * Home's New Episode banner (Dream Cinema), right under the hero: one followed show's new episode on a
 * dark panel, its still on the left, "NEW EPISODE" in a still rainbow, the show, "S3 · E7  Episode Name",
 * whether it's out, and Play (the rainbow), Go to Show and Dismiss. Gone while there is nothing to offer.
 * Never rebuilt under the user's focus. While it shows and Home is idle, the episode's stream is found
 * ahead ([NewEpisodeSpot.prepare]), so Play starts at once. [shown] shows / hides it without the page
 * jumping (CinemaRowsPage.setShown).
 */
class NewEpisodeBanner(
    private val kit: CinemaKit,
    private val host: HomeShell.Host,
    private val shown: (View, Boolean) -> Unit,
) : FrameLayout(kit.ctx), CinemaSection, Availability.Watcher {
    private val ctx = kit.ctx
    private val handler = Handler(Looper.getMainLooper())
    private val Int.dp get() = Ui.dp(ctx, this)

    /** Home's own New Episodes row hears of a periodic refresh too (CinemaHome fills it, then calls [update]). */
    var onFresh: ((NewEpisodes.Snapshot) -> Unit)? = null

    private var current: NewEpisodes.Item? = null
    private val still = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = Ui.rounded(ctx, kit.cardFill, CinemaStyle.R_CARD)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
    }
    /** The panel's surface (the kicker's fading words are lifted only as far as they need to read on it). */
    private val panelFill = Ui.mix(kit.bg, kit.text, 0.06f)
    private val kicker = kit.label("", CinemaStyle.T_CARD_KICKER, kit.text, kit.semibold, tracking = 0.14f)
    private val status = kit.label("", CinemaStyle.T_META, kit.dim, kit.medium)
    /** Available Now / CAM Only / Not Available Yet (Availability), once known. */
    private val pill = Availability.Pill(ctx)
    private val name = kit.label("", 22f, kit.text, kit.bold).apply { letterSpacing = -0.01f }
    private val code = kit.label("", 15f, Ui.alpha(kit.text, 0.92f), kit.semibold)
    private val epName = kit.label("", 15f, Ui.alpha(kit.text, 0.72f), kit.medium)
    private val buttons = FlatLinear(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
    private val play = kit.button("Play", R.drawable.ic_play, primary = true) { current?.let { playNow(it) } }
    private val show = kit.button("Go to Show", R.drawable.ic_info, primary = false) { current?.let { host.openDetails(it.show.title(), null) } }
    private val dismiss = kit.button("Dismiss", null, primary = false) { current?.let { dismissNow(it) } }

    init {
        clipChildren = false
        clipToPadding = false
        val panel = FlatLinear(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            // A dark neutral panel (no tint), a hairline edge: the page's own surface, one step up.
            background = Ui.rounded(ctx, panelFill, 16, 1f, Ui.alpha(kit.text, 0.08f))
            setPadding(PAD.dp, PAD.dp, PAD.dp, PAD.dp)
        }
        panel.addView(still, LinearLayout.LayoutParams(STILL_W.dp, STILL_H.dp))
        val text = FlatLinear(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        text.addView(FlatLinear(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(kicker, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(pill, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 14.dp })
            addView(status, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 14.dp })
        })
        text.addView(name, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 7.dp })
        text.addView(FlatLinear(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(code, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(epName, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 12.dp })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 5.dp })
        buttons.addView(play)
        buttons.addView(show)
        buttons.addView(dismiss)
        (dismiss.layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        // The focused Play steps on the shared clock: bounded, so each step redraws only around the buttons.
        text.addView(damageBox(buttons, BUTTONS_ROOM.dp), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        panel.addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = 24.dp })
        addView(panel, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { leftMargin = kit.gutter; rightMargin = kit.gutter })
        play.setOnFocusChangeListener(chain(play.onFocusChangeListener) { has -> if (has) current?.let { PreResolve.rewarm(ctx, NewEpisodeSpot.request(it)) } })
        NewEpisodeSpot.watch(this)
    }

    override fun entry(): View? = if (current == null) null else (if (play.visibility == View.VISIBLE) play else show)

    /** Left / Right stay among the banner's buttons. */
    override fun focusSearch(focused: View?, direction: Int): View? {
        val r = super.focusSearch(focused, direction)
        if (focused != null && (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) && (r == null || !HomeShell.isInside(r, this))) return focused
        return r
    }

    // ---- content ----

    /** A New Episodes load landed (CinemaHome's, or the periodic check's): the banner offers its pick. */
    fun update(s: NewEpisodes.Snapshot) {
        val app = ctx.applicationContext
        Net.background({ Holder(NewEpisodeSpot.onSnapshot(app, s)) }) { h -> if (h != null) offer(h.item) }
    }

    /** The periodic check's snapshot: Home's row first (which calls [update]), else just the banner. */
    internal fun fresh(s: NewEpisodes.Snapshot) { onFresh?.invoke(s) ?: update(s) }

    /** Picks again from the cached episodes (developer test, reset). */
    internal fun reload() {
        val app = ctx.applicationContext
        Net.background({ NewEpisodes.load(app, refresh = false) }) { s -> if (s != null) update(s) }
    }

    internal fun homeInFront() = host.resumed()

    private class Holder(val item: NewEpisodes.Item?)

    private fun offer(item: NewEpisodes.Item?) {
        if (item?.key == current?.key) return
        if (hasFocus()) return // never swapped under the user; the next load picks it up
        current = item
        handler.removeCallbacks(prepRun)
        if (item == null) { shown(this, false); return }
        bind(item)
        val was = visibility
        shown(this, true)
        if (was == View.GONE && isAttachedToWindow) {
            alpha = 0f
            animate().alpha(1f).setStartDelay(120).setDuration(360).setInterpolator(Ui.EASE).start()
        }
        Net.run { NewEpisodeSpot.displayed(ctx, item) }
        handler.postDelayed(prepRun, FIRST_PREP_MS)
    }

    private fun bind(item: NewEpisodes.Item) {
        val word = if (item.lastNumber > item.ep.number) "NEW EPISODES" else "NEW EPISODE"
        kicker.text = Rainbow.fadeText(kicker, word, 0, word.length, panelFill)
        name.text = item.show.name
        code.text = NewEpisodeSpot.code(item)
        epName.text = if (item.lastNumber > item.ep.number) "${item.lastNumber - item.ep.number + 1} Episodes"
            else item.ep.name.ifBlank { "Episode ${item.ep.number}" }
        val target = Availability.episode(item)
        if (PreResolve.ready(NewEpisodeSpot.request(item))) markAvailable(item)
        Availability.watch(this, target.key)
        // A status pill only while the episode is new (aired in the last Availability.EPISODE_DAYS days).
        if (Availability.recentEpisode(item.ep.at, item.ep.airDate)) {
            pill.bind(target.key, Availability.peek(ctx, target.key))
            Availability.want(ctx, target, front = true)
        } else pill.bind("old:${target.key}", null)
        setStatus(item)
        // Play shows whenever Dream OS can play titles (without an account the player says what to link).
        play.visibility = if (host.canPlay()) View.VISIBLE else View.GONE
        still.setImageDrawable(null)
        Net.image(ctx, still, item.image("w500"), listOfNotNull(item.show.backdrop?.let { Tmdb.IMG + "w780" + it }))
    }

    /**
     * Next to the pill: until the checker has answered, when it came out ("Out Today", "Out Tonight at 9:00 PM",
     * "Out Now"); with an answer, the pill says it all.
     */
    private fun setStatus(item: NewEpisodes.Item) {
        val known = Availability.recentEpisode(item.ep.at, item.ep.airDate) && Availability.peek(ctx, Availability.episode(item).key) != null
        status.text = when {
            known -> ""
            item.ep.at > 0 && NewEpisodes.daysFromToday(item.ep.airDate) == 0 -> "Out " + AirTimes.label(item.ep.at)
            NewEpisodes.daysFromToday(item.ep.airDate) == 0 -> "Out Today"
            else -> "Out Now"
        }
        status.visibility = if (known) View.GONE else View.VISIBLE
    }

    override fun availChanged(key: String, e: Availability.Entry) {
        val i = current ?: return
        if (Availability.episode(i).key == key) setStatus(i)
    }

    /** Its stream was found: it's out, whatever the index said a while ago. */
    private fun markAvailable(i: NewEpisodes.Item) {
        val key = Availability.episode(i).key
        if (Availability.peek(ctx, key)?.status == Availability.Status.AVAILABLE) return
        Availability.record(ctx, key, Availability.Entry(Availability.Status.AVAILABLE, "ready to play", null, System.currentTimeMillis()), "${i.show.name} S${i.ep.season}E${i.ep.number}")
    }

    /** [NewEpisodeSpot]: the pre-resolve for [key] ended. */
    internal fun prepared(key: String, ready: Boolean) {
        val i = current ?: return
        if (i.key == key && ready) markAvailable(i)
    }

    private fun playNow(item: NewEpisodes.Item) {
        NewEpisodeSpot.playing(item)
        NewEpisodes.markSeen(ctx, item)
        Net.run { NewEpisodeSpot.done(ctx, item, "played") }
        // The very episode, through the play flow (Instant Play takes the link found ahead).
        PlayFlow.play(ctx, NewEpisodeSpot.request(item))
        // Once the player covers Home, the banner leaves unseen (focus goes on to Continue Watching, where the episode is now).
        handler.postDelayed({
            if (current?.key == item.key && !host.resumed()) { current = null; handler.removeCallbacks(prepRun); shown(this, false) }
        }, 2_000)
    }

    private fun dismissNow(item: NewEpisodes.Item) {
        NewEpisodes.markSeen(ctx, item)
        Net.run { NewEpisodeSpot.done(ctx, item, "dismissed") }
        current = null
        handler.removeCallbacks(prepRun)
        // Fades, then leaves (focus goes on down to the next section first).
        animate().alpha(0f).setStartDelay(0).setDuration(200).setInterpolator(Ui.EXIT).withEndAction {
            if (current == null) shown(this, false)
            alpha = 1f
        }.start()
    }

    // ---- the stream, ahead ----

    private val prepRun = object : Runnable {
        override fun run() {
            val item = current ?: return
            if (!isAttachedToWindow) return // re-armed when Home shows again
            val idle = host.resumed() && host.idle() && isShown && !DreamPlayerActivity.isActive()
            val next = NewEpisodeSpot.prepare(ctx, item, idle)
            if (next >= 0) handler.postDelayed(this, next)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (current != null) { handler.removeCallbacks(prepRun); handler.postDelayed(prepRun, FIRST_PREP_MS) }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(prepRun)
        super.onDetachedFromWindow()
    }

    // ---- helpers ----

    private fun chain(first: View.OnFocusChangeListener?, then: (Boolean) -> Unit) =
        View.OnFocusChangeListener { v, has -> first?.onFocusChange(v, has); then(has) }

    /** As CinemaHero's: [v] in a clipping frame [room] px larger all round, so its redraws stay local. */
    private fun damageBox(v: View, room: Int): View {
        v.setPadding(v.paddingLeft + room, v.paddingTop + room, v.paddingRight + room, v.paddingBottom + room)
        return FlatFrame(ctx).apply {
            clipChildren = true
            clipToPadding = false
            addView(v, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(-room, -room, -room, -room) })
        }
    }

    private companion object {
        const val PAD = 16
        const val STILL_W = 224
        const val STILL_H = 126
        const val BUTTONS_ROOM = 20
        /** The first look at the stream, after the banner shows (Home settles first). */
        const val FIRST_PREP_MS = 3_000L
    }
}
