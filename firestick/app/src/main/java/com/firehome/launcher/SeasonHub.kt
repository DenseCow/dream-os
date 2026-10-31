package com.firehome.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Seasonal & event hubs on the TV: which hub is on for this profile today ([SeasonCalendar] over
 * [SeasonCatalog]), loading a hub's rows from TMDB (through [GenreShelves]' recipes, tidied and without
 * anime like every row), Tonight's Pick, and the developer override.
 *
 * Developer override (adb only, PingReceiver):
 *   ... PingReceiver --es season christmas          show that hub as if it were on (any id in [SeasonCatalog])
 *   ... PingReceiver --es seasonDate 2026-12-24     pretend today is that day (the calendar decides)
 *   ... PingReceiver --ez seasonReset true          back to the real calendar
 *   ... PingReceiver --ez seasonCheck true          log the calendar self-check and the year's windows
 * Each one rebuilds Home (and drops the hub and genre pages) so the change shows at once.
 */
object Seasons {
    private const val TAG = "FireHome"
    /** Genres a kids profile never sees in a hub row, whatever TMDB filed the title under. */
    private val NOT_FOR_KIDS = setOf(27, 53, 80, 10752)
    private const val BASE = "&include_adult=false&include_video=false&sort_by=popularity.desc"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("seasons", Context.MODE_PRIVATE)

    /** The shell to rebuild when the override changes (weak: never keeps an Activity alive). */
    @Volatile var shell: java.lang.ref.WeakReference<CinemaShell>? = null

    /** Today on the TV's clock, or the developer's pretend day. */
    fun today(ctx: Context): SDay {
        SDay.parse(prefs(ctx).getString("date", null))?.let { return it }
        val c = DreamTime.calendar(ctx) // the viewer's chosen time zone decides when a hub goes live
        return SDay.of(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
    }

    /** A kids profile: its own Kids switch ([KidsProfiles]); one never set either way keeps the guess from its name. */
    fun kids(ctx: Context): Boolean = KidsProfiles.isKids(ctx)

    private val logged = HashSet<String>()
    @Volatile private var checked = false

    /** Every hub on for this profile today, the one Home shows first (the developer's forced hub leads). */
    fun all(ctx: Context): List<SeasonalHub> {
        val today = today(ctx)
        val kids = kids(ctx)
        val forced = SeasonCalendar.find(prefs(ctx).getString("force", null))?.takeIf { !kids || it.forKids() }
        val on = SeasonCalendar.active(today, kids)
        val list = if (forced != null) listOf(forced) + on.filter { it !== forced } else on
        report(today, kids, list, forced != null)
        return list
    }

    /** The hub Home shows: the highest priority one on today (there always is one: a base season). */
    fun active(ctx: Context): SeasonalHub = all(ctx).firstOrNull() ?: SeasonCalendar.top(today(ctx))

    /** The hub (not a base season) a genre page links to while it's on: Horror → 31 Nights of Horror. */
    fun forGenre(ctx: Context, genre: String): SeasonalHub? = all(ctx).firstOrNull { !it.base && genre in it.genres }

    /** Once a day per profile and hub: what's on, and (once per process) the calendar's self-check. */
    private fun report(today: SDay, kids: Boolean, list: List<SeasonalHub>, forced: Boolean) {
        val top = list.firstOrNull() ?: return
        val key = "$today|$kids|${top.id}|$forced"
        synchronized(logged) { if (!logged.add(key)) return }
        Log.i(TAG, "Season: ${top.id} active${if (forced) " (forced)" else ""} on $today${if (kids) " (kids profile)" else ""}, " +
            "priority ${top.priorityOn(today)}, ${top.rowsFor(kids).size} rows, \"${top.status(today)}\"" +
            (top.night(today)?.let { ", $it" } ?: "") + "; also on: ${list.drop(1).joinToString { it.id }.ifEmpty { "-" }}")
        // Two years of days through the calendar: off the main thread (Home is building).
        if (!checked) { checked = true; Thread({ selfCheck() }, "season-check").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start() }
    }

    /** The calendar's self-check in the log: "Season check: OK ..." or one "Season check: FAIL ..." line per problem. */
    fun selfCheck(windows: Boolean = false) {
        val (bad, summary) = SeasonCalendar.selfCheck(2026..2027)
        if (bad.isEmpty()) Log.i(TAG, "Season check: OK, $summary")
        else bad.take(20).forEach { Log.w(TAG, "Season check: FAIL $it") }
        if (windows) for (y in 2026..2027) SeasonCalendar.describe(y).forEach { Log.i(TAG, "Season window $y: $it") }
    }

    /** The developer override (PingReceiver): a forced hub and/or a pretend day; null clears that part. */
    fun override(ctx: Context, force: String? = null, date: String? = null, reset: Boolean = false) {
        val e = prefs(ctx).edit()
        if (reset) e.remove("force").remove("date")
        if (force != null) {
            if (SeasonCalendar.find(force) == null) { Log.w(TAG, "Season: no hub \"$force\" (ids: ${SeasonCatalog.ALL.joinToString { it.id }})"); return }
            e.putString("force", force)
        }
        if (date != null) {
            if (SDay.parse(date) == null) { Log.w(TAG, "Season: \"$date\" isn't a yyyy-MM-dd day"); return }
            e.putString("date", date)
        }
        e.apply()
        synchronized(logged) { logged.clear() }
        synchronized(picks) { picks.clear() }
        Log.i(TAG, "Season override: force=${prefs(ctx).getString("force", null) ?: "-"} date=${prefs(ctx).getString("date", null) ?: "-"}")
        android.os.Handler(android.os.Looper.getMainLooper()).post { shell?.get()?.seasonsChanged() }
    }

    // ---- loading (background threads) ----

    /** [hub]'s row [r] for this profile: movies and shows interleaved, tidied (no anime, nothing adult or finished). */
    fun load(ctx: Context, hub: SeasonalHub, r: SeasonRow, today: SDay = today(ctx)): List<Title> {
        val movie = r.movie?.let { SeasonCalendar.fill(it, hub, today) }
        val tv = r.tv?.let { SeasonCalendar.fill(it, hub, today) }
        val raw = when (r.kind) {
            // Hand-picked titles come from details calls, not Tmdb.list: the profile's rating limit is applied here.
            SeasonRow.Kind.PICKS -> if (RowPaging.page > 1) emptyList() else KidsFilter.titles(ctx, picked(ctx, r.ids))
            SeasonRow.Kind.POPULAR -> RowMix.interleave(RowPaging.all(*listOfNotNull(movie?.let { "movie" to it }, tv?.let { "tv" to it }).map { (type, q) ->
                { popular(ctx, type, q) }
            }.toTypedArray()))
            else -> {
                val spec = GenreSpec(hub.title, if (movie != null) (r.genre.takeIf { it > 0 } ?: Discover.ANY) else 0, if (tv != null) Discover.ANY_TV else 0)
                GenreShelves.load(ctx, spec, Shelf(SeasonCalendar.fill(r.title, hub, today), mode(r.kind), movie, tv))
            }
        }
        val kids = kids(ctx)
        return RowMix.tidy(ctx, raw).filter { !kids || it.genreIds.none { g -> g in NOT_FOR_KIDS } }
    }

    private fun mode(k: SeasonRow.Kind) = when (k) {
        SeasonRow.Kind.TRENDING -> Shelf.Mode.TRENDING
        SeasonRow.Kind.NEW -> Shelf.Mode.NEW
        SeasonRow.Kind.TOP_VOTED -> Shelf.Mode.TOP_VOTED
        SeasonRow.Kind.ACCLAIMED -> Shelf.Mode.ACCLAIMED
        else -> Shelf.Mode.VARIETY
    }

    /** The filter as asked (its own sort_by wins over the base's), two pages; a modest vote floor unless it sets one. */
    private fun popular(ctx: Context, type: String, q: String): List<Title> {
        val votes = if (q.contains("vote_count.gte=")) "" else "&vote_count.gte=${if (type == "movie") 50 else 20}"
        val query = BASE + q + votes
        return RowPaging.all({ Tmdb.list(ctx, "/discover/$type", query, type) }, { Tmdb.list(ctx, "/discover/$type", "$query&page=2", type) }).flatten()
    }

    /** Hand-picked titles ("movie:603"), fetched side by side (TMDB's details, cached), in their list's order. */
    private fun picked(ctx: Context, ids: List<String>): List<Title> =
        RowPaging.all(*ids.map { key -> { byId(ctx, key) } }.toTypedArray()).filterNotNull()

    private val titles = android.util.LruCache<String, Title>(256)

    private fun byId(ctx: Context, key: String): Title? {
        titles.get(key)?.let { return it }
        val type = key.substringBefore(':')
        val id = key.substringAfter(':').toIntOrNull() ?: return null
        val o = try { WatchHistory.details(ctx, type, id) } catch (e: Exception) { null } ?: return null
        val date = o.optString(if (type == "movie") "release_date" else "first_air_date")
        fun path(k: String) = o.optString(k).takeIf { it.isNotEmpty() && it != "null" }
        val t = Title(
            id = id, type = type, name = o.optString(if (type == "movie") "title" else "name"), overview = o.optString("overview"),
            posterPath = path("poster_path"), backdropPath = path("backdrop_path"), year = date.take(4),
            rating = o.optDouble("vote_average", 0.0), date = date.takeIf { it.length >= 10 }.orEmpty(), votes = o.optInt("vote_count", 0),
            genreIds = o.optJSONArray("genres")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optInt("id") } }.orEmpty(),
            lang = o.optString("original_language"), adult = o.optBoolean("adult", false), originCountry = Tmdb.countries(o),
        )
        titles.put(key, t)
        return t
    }

    private val picks = HashMap<String, Title>()

    /**
     * Tonight's Pick: one title from the hub's pick pool (a kids profile: its kids pool, else its kids rows),
     * chosen by the date, so it's the same all day and moves on tomorrow. Null when nothing loads.
     */
    fun pick(ctx: Context, hub: SeasonalHub): Title? {
        val today = today(ctx)
        val kids = kids(ctx)
        val key = "${hub.id}|$today|$kids"
        synchronized(picks) { picks[key]?.let { return it } }
        val pool = (if (kids) hub.kidsPick ?: hub.rows.firstOrNull { it.kids } else hub.pick)?.let { r ->
            try { RowPaging.at("Tonight's Pick ${hub.id}", 1) { load(ctx, hub, r, today) } } catch (e: Exception) { emptyList() }
        }.orEmpty().filter { it.backdropPath != null }.sortedBy { it.key }
        if (pool.isEmpty()) return null
        val t = pool[Math.floorMod("$today|${hub.id}".hashCode(), pool.size)]
        synchronized(picks) { picks[key] = t }
        Log.i(TAG, "Season: ${hub.id} Tonight's Pick for $today is ${t.name} (${t.key}, 1 of ${pool.size})")
        return t
    }

    /** The pick already chosen today (no loading), for marking its card. */
    fun pickedKey(ctx: Context, hub: SeasonalHub): String? = synchronized(picks) { picks["${hub.id}|${today(ctx)}|${kids(ctx)}"]?.key }

    /** Home's (and a genre page's) hub row: Tonight's Pick, then the hub's first row for this profile. */
    fun homeTitles(ctx: Context, hub: SeasonalHub, take: (String, List<Title>) -> List<Title> = { _, l -> l }): List<Title> {
        val r = hub.rowsFor(kids(ctx)).firstOrNull() ?: return emptyList()
        val (p, lead) = RowPaging.all<Any?>({ if (RowPaging.page > 1) null else pick(ctx, hub) }, { load(ctx, hub, r) })
        @Suppress("UNCHECKED_CAST") val rest = take(hub.title, (lead as List<Title>).filter { it.key != (p as? Title)?.key })
        return listOfNotNull(p as? Title) + rest
    }
}

/** The hub pieces Cinema shows: the hub's tile (Home, genre pages, the hub's own "More to Celebrate") and its masthead. */
object SeasonUi {
    /** Graphite, never a hue: the hub's flavor comes from its glyph, its art and its words. */
    private val TILE_TOP = Color.parseColor("#1D1D20")
    private val TILE_BOTTOM = Color.parseColor("#0B0B0D")

    /**
     * A hub's card, the size of a row's landscape card: a graphite tile, the hub's glyph top right, its kicker
     * and title bottom left; under it, the countdown ("5 Nights Until Halloween") and "Night 26 of 31" or its kicker.
     * Cards get the rainbow border on focus like every card (CinemaCard). [open] gets the card.
     */
    fun tile(kit: CinemaKit, hub: SeasonalHub, today: SDay, open: (CinemaCard) -> Unit): Pair<View, CinemaCard> {
        val ctx = kit.ctx
        val d = ctx.resources.displayMetrics.density
        fun Int.dp() = (this * d).toInt()
        val w = kit.fit(CinemaStyle.LAND_W)
        val card = CinemaCard(kit, w, w * 9 / 16)
        card.frame.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(TILE_TOP, TILE_BOTTOM))
            .apply { cornerRadius = CinemaStyle.R_CARD * d }
        card.frame.addView(View(ctx).apply { background = GenreArt(hub.glyph, Rainbow.COLORS[Math.floorMod(hub.hue, Rainbow.COLORS.size)]) },
            FrameLayout.LayoutParams(50.dp(), 50.dp(), Gravity.TOP or Gravity.END).apply { setMargins(0, 12.dp(), 12.dp(), 0) })
        card.frame.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(kit.kicker(hub.kicker, Ui.alpha(kit.text, 0.66f)))
            addView(kit.label(hub.title, 16.5f, kit.text, kit.semibold, lines = 2).apply { setLineSpacing(0f, 1.04f); setPadding(0, 5.dp(), 0, 0) })
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(14.dp(), 0, 14.dp(), 12.dp()) })
        card.frame.foreground = Ui.rounded(ctx, Color.TRANSPARENT, CinemaStyle.R_CARD, 1f, Ui.alpha(kit.text, 0.09f))
        card.setOnClickListener { open(card) }
        val sub = hub.night(today) ?: if (hub.countdown != null) hub.kicker else "Seasonal Collection"
        return kit.unit(card, null, hub.status(today), sub) to card
    }

    /**
     * The hub page's masthead under the bar: the glyph, then the kicker, the title large and the countdown or
     * tagline. Not focusable: focus starts on Tonight's Pick under it.
     */
    fun masthead(kit: CinemaKit, hub: SeasonalHub, today: SDay): View {
        val ctx = kit.ctx
        val d = ctx.resources.displayMetrics.density
        fun Int.dp() = (this * d).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.gutter, 88.dp(), kit.gutter, 0)
            addView(View(ctx).apply { background = GenreArt(hub.glyph, Rainbow.COLORS[Math.floorMod(hub.hue, Rainbow.COLORS.size)]) },
                LinearLayout.LayoutParams(68.dp(), 68.dp()))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(kit.kicker(hub.kicker))
                addView(kit.label(hub.title, 30f, kit.text, kit.bold).apply { letterSpacing = -0.01f; setPadding(0, 6.dp(), 0, 0) })
                val line = listOfNotNull(hub.countdown?.text(today), hub.night(today)).joinToString("  ·  ").ifEmpty { hub.tagline }
                addView(kit.label(line, 14.5f, kit.dim, kit.medium).apply { setPadding(0, 7.dp(), 0, 0) })
                if (hub.countdown != null) addView(kit.label(hub.tagline, 13.5f, Ui.alpha(kit.dim, 0.85f), kit.font).apply { setPadding(0, 4.dp(), 0, 0) })
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 20.dp() })
        }
    }
}

/**
 * One seasonal hub's page (Home's hub row or a matching genre page opens it): the masthead, Tonight's Pick
 * across the page (Apple TV's feature module), the hub's rows for this profile (a kids profile sees only its
 * kids-safe rows), then the other hubs that are on ("More to Celebrate"). Rows load lazily as focus nears them,
 * page on as focus nears their end, and hold a title only once, like every Cinema page.
 */
class CinemaSeason(shell: CinemaShell, val hub: SeasonalHub) : CinemaRowsPage(shell) {
    private val claims = RowClaims(ctx)
    private val today = Seasons.today(ctx)
    private val kids = Seasons.kids(ctx)

    init {
        if (Tmdb.hasKey(ctx)) build()
        else add(kit.empty(LineIcon.FILM, "Add a TMDB Key", "Open Settings and add a TMDB key to see ${hub.title}.").apply { setPadding(0, 120.dp, 0, 0) })
    }

    private fun titles(title: String, kind: RowKind, lazy: Boolean, load: () -> List<Title>) =
        row(title, kind, lazy, load = { claims.take(title, load().filter { it.backdropPath != null || it.posterPath != null }).takeIf { it.size >= MIN_ROW } }) { _, list ->
            titleCards(list) { if (kind == RowKind.BIG) kit.big(it, hub.kicker) else kit.landscape(it) }
        }

    private fun build() {
        add(SeasonUi.masthead(kit, hub, today), gap = 24)
        val kick = listOfNotNull("Tonight's Pick", hub.night(today)).joinToString("  ·  ")
        spotlight(kick, load = { Seasons.pick(ctx, hub)?.let { claims.claim("Tonight's Pick", listOf(it)) } })
        val rows = hub.rowsFor(kids)
        for ((i, r) in rows.withIndex()) {
            val title = SeasonCalendar.fill(r.title, hub, today)
            titles(title, if (i == 0) RowKind.BIG else RowKind.STANDARD, lazy = i > 1) { Seasons.load(ctx, hub, r, today) }
        }
        val others = Seasons.all(ctx).filter { it.id != hub.id }
        if (others.isNotEmpty()) row("More to Celebrate", RowKind.STANDARD, lazy = true, load = { others }) { _, list ->
            list.map { o -> SeasonUi.tile(kit, o, today) { c -> shell.openSeason(o, c) } }
        }
        endCap()
        android.util.Log.i("FireHome", "Season page: ${hub.id}, ${rows.size} rows${if (kids) " (kids profile)" else ""}, more: ${others.joinToString { it.id }.ifEmpty { "-" }}")
    }

}
