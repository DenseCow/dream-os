package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.HubTile
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.RowStyle
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.time.DreamTime
import com.dreamos.desktop.core.util.Log
import com.dreamos.desktop.core.util.a
import com.dreamos.desktop.core.util.int
import com.dreamos.desktop.core.util.objects

/**
 * Seasonal & event hubs (fire-home SeasonHub.kt): which hub is on for this profile today ([SeasonCalendar] over
 * [SeasonCatalog]), their tiles ([HubTile]), loading a hub's rows from TMDB (through [GenreShelves]' recipes, tidied and
 * without anime like every row), Tonight's Pick, a hub's page rows, Home's hub row, and the developer override.
 *
 * Today is the viewer's day in their chosen zone (DreamTime), so a hub goes live at their midnight. The developer
 * override (the Fire Stick's PingReceiver) is [override]: a forced hub and / or a pretend day, kept per PC.
 */
object Seasons {
    private const val TAG = "Season"
    /** Genres a kids profile never sees in a hub row, whatever TMDB filed the title under. */
    private val NOT_FOR_KIDS = setOf(27, 53, 80, 10752)
    private const val BASE = "&include_adult=false&include_video=false&sort_by=popularity.desc"

    private fun prefs() = DreamStore.shared.file("seasons")

    /** Today on the viewer's clock (their chosen zone), or the developer's pretend day. */
    fun today(): SDay {
        SDay.parse(prefs().getString("date"))?.let { return it }
        val d = DreamTime.zoned().toLocalDate()
        return SDay.of(d.year, d.monthValue, d.dayOfMonth)
    }

    /** A kids profile: its own Kids switch; one never set either way keeps the guess from its name. */
    fun kids(): Boolean = ContentPrefs.isKids()

    private val logged = HashSet<String>()

    /** Every hub on for this profile today, the one Home shows first (the developer's forced hub leads). */
    fun all(today: SDay = today(), kids: Boolean = kids()): List<SeasonalHub> {
        val forced = SeasonCalendar.find(prefs().getString("force"))?.takeIf { !kids || it.forKids() }
        val on = SeasonCalendar.active(today, kids)
        val list = if (forced != null) listOf(forced) + on.filter { it !== forced } else on
        report(today, kids, list, forced != null)
        return list
    }

    /** The hub Home shows: the highest priority one on today (there always is one: a base season). */
    fun active(today: SDay = today(), kids: Boolean = kids()): SeasonalHub = all(today, kids).firstOrNull() ?: SeasonCalendar.top(today)

    /** The hub (not a base season) a genre page links to while it's on: Horror to 31 Nights of Horror. */
    fun forGenre(genre: String, today: SDay = today(), kids: Boolean = kids()): SeasonalHub? =
        all(today, kids).firstOrNull { !it.base && genre in it.genres }

    /** Once a day per profile and hub: what's on. */
    private fun report(today: SDay, kids: Boolean, list: List<SeasonalHub>, forced: Boolean) {
        val top = list.firstOrNull() ?: return
        val key = "$today|$kids|${top.id}|$forced"
        synchronized(logged) { if (!logged.add(key)) return }
        Log.i(TAG, "${top.id} active${if (forced) " (forced)" else ""} on $today${if (kids) " (kids profile)" else ""}, " +
            "priority ${top.priorityOn(today)}, ${top.rowsFor(kids).size} rows, \"${top.status(today)}\"" +
            (top.night(today)?.let { ", $it" } ?: "") + "; also on: ${list.drop(1).joinToString { it.id }.ifEmpty { "-" }}")
    }

    /**
     * The developer override: a forced hub ([force], any id in [SeasonCatalog]) and / or a pretend day ([date],
     * "yyyy-MM-dd"); [reset] goes back to the real calendar. False when [force] or [date] can't be read.
     */
    fun override(force: String? = null, date: String? = null, reset: Boolean = false): Boolean {
        if (force != null && SeasonCalendar.find(force) == null) { Log.w(TAG, "no hub \"$force\""); return false }
        if (date != null && SDay.parse(date) == null) { Log.w(TAG, "\"$date\" isn't a yyyy-MM-dd day"); return false }
        prefs().edit {
            if (reset) { remove("force"); remove("date") }
            if (force != null) putString("force", force)
            if (date != null) putString("date", date)
        }
        synchronized(logged) { logged.clear() }
        synchronized(picks) { picks.clear() }
        Log.i(TAG, "override: force=${prefs().getString("force") ?: "-"} date=${prefs().getString("date") ?: "-"}")
        return true
    }

    // ---- tiles ----

    /**
     * [hub]'s tile (Home's hub row, a genre page's hub row, More to Celebrate) and its page's masthead: under it the
     * countdown ("5 Nights Until Halloween") or the tagline, and "Night 26 of 31" or its kicker.
     */
    fun tile(hub: SeasonalHub, today: SDay = today()): HubTile {
        val night = hub.night(today)
        return HubTile(
            id = hub.id, title = hub.title, kicker = hub.kicker, tagline = hub.tagline, glyph = hub.glyph, hue = hub.hue,
            status = hub.status(today), night = night, hasCountdown = hub.countdown != null,
            sub = night ?: if (hub.countdown != null) hub.kicker else "Seasonal Collection",
        )
    }

    /** Every hub on for this profile today as tiles, the leading one first. */
    fun tiles(): List<HubTile> {
        val today = today()
        return all(today).map { tile(it, today) }
    }

    /** [id]'s tile (a hub page's masthead), on or not; null for an unknown id. */
    fun hub(id: String): HubTile? = SeasonCalendar.find(id)?.let { tile(it) }

    // ---- loading (IO threads) ----

    /** [hub]'s row [r] for this profile: movies and shows interleaved, tidied (no anime, nothing adult or finished). */
    fun load(hub: SeasonalHub, r: SeasonRow, today: SDay = today(), kids: Boolean = kids()): List<Title> {
        val movie = r.movie?.let { SeasonCalendar.fill(it, hub, today) }
        val tv = r.tv?.let { SeasonCalendar.fill(it, hub, today) }
        val raw = when (r.kind) {
            // Hand-picked titles come from details calls, not Tmdb.list: the profile's rating limit is applied here.
            SeasonRow.Kind.PICKS -> if (RowPaging.page > 1) emptyList() else KidsFilter.titles(picked(r.ids))
            SeasonRow.Kind.POPULAR -> RowMix.interleave(RowPaging.all(*listOfNotNull(movie?.let { MediaKind.MOVIE to it }, tv?.let { MediaKind.TV to it }).map { (type, q) ->
                { popular(type, q) }
            }.toTypedArray()))
            else -> {
                val spec = GenreSpec(hub.title, if (movie != null) (r.genre.takeIf { it > 0 } ?: Discover.ANY) else 0, if (tv != null) Discover.ANY_TV else 0)
                GenreShelves.load(spec, Shelf(SeasonCalendar.fill(r.title, hub, today), mode(r.kind), movie, tv))
            }
        }
        return RowMix.tidy(raw).filter { !kids || it.genreIds.none { g -> g in NOT_FOR_KIDS } }
    }

    private fun mode(k: SeasonRow.Kind) = when (k) {
        SeasonRow.Kind.TRENDING -> Shelf.Mode.TRENDING
        SeasonRow.Kind.NEW -> Shelf.Mode.NEW
        SeasonRow.Kind.TOP_VOTED -> Shelf.Mode.TOP_VOTED
        SeasonRow.Kind.ACCLAIMED -> Shelf.Mode.ACCLAIMED
        else -> Shelf.Mode.VARIETY
    }

    /** The filter as asked (its own sort_by wins over the base's), two pages; a modest vote floor unless it sets one. */
    private fun popular(type: MediaKind, q: String): List<Title> {
        val votes = if (q.contains("vote_count.gte=")) "" else "&vote_count.gte=${if (type == MediaKind.MOVIE) 50 else 20}"
        val query = BASE + q + votes
        val path = "/discover/${type.path}"
        return RowPaging.all({ Tmdb.list(path, query, type) }, { Tmdb.list(path, "$query&page=2", type) }).flatten()
    }

    /** Hand-picked titles ("movie:603"), fetched side by side (TMDB's details, cached), in their list's order. */
    private fun picked(ids: List<String>): List<Title> =
        RowPaging.all(*ids.map { key -> { byId(key) } }.toTypedArray()).filterNotNull()

    private val titles = object : LinkedHashMap<String, Title>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Title>?) = size > 256
    }

    private fun byId(key: String): Title? {
        synchronized(titles) { titles[key]?.let { return it } }
        val type = MediaKind.of(key.substringBefore(':')) ?: return null
        val id = key.substringAfter(':').toIntOrNull() ?: return null
        val o = WatchHistory.details(type, id) ?: return null
        // Details list their genres as objects, not list results' genre_ids.
        val t = Tmdb.parse(o, type)?.copy(genreIds = o.a("genres").objects().map { it.int("id") }) ?: return null
        synchronized(titles) { titles[key] = t }
        return t
    }

    private val picks = HashMap<String, Title>()

    /**
     * Tonight's Pick: one title from the hub's pick pool (a kids profile: its kids pool, else its kids rows), chosen by
     * the date, so it's the same all day and moves on tomorrow. Null when nothing loads.
     */
    fun pick(hub: SeasonalHub, today: SDay = today(), kids: Boolean = kids()): Title? {
        val key = "${hub.id}|$today|$kids"
        synchronized(picks) { picks[key]?.let { return it } }
        val pool = (if (kids) hub.kidsPick ?: hub.rows.firstOrNull { it.kids } else hub.pick)?.let { r ->
            try { RowPaging.at("Tonight's Pick ${hub.id}", 1) { load(hub, r, today, kids) } } catch (e: Exception) { emptyList() }
        }.orEmpty().filter { it.backdropPath != null }.sortedBy { it.key }
        if (pool.isEmpty()) return null
        val t = pool[pickIndex(today, hub.id, pool.size)]
        synchronized(picks) { picks[key] = t }
        Log.i(TAG, "${hub.id} Tonight's Pick for $today is ${t.name} (${t.key}, 1 of ${pool.size})")
        return t
    }

    /** Which of [size] pool titles is Tonight's Pick on [today] (the Fire Stick's: the same title on both). */
    fun pickIndex(today: SDay, hubId: String, size: Int) = Math.floorMod("$today|$hubId".hashCode(), size)

    /** The pick already chosen today (no loading), for marking its card. */
    fun pickedKey(hub: SeasonalHub, today: SDay = today(), kids: Boolean = kids()): String? =
        synchronized(picks) { picks["${hub.id}|$today|$kids"]?.key }

    /** Home's (and a genre page's) hub row titles: Tonight's Pick, then the hub's first row for this profile. */
    fun homeTitles(hub: SeasonalHub, take: (String, List<Title>) -> List<Title> = { _, l -> l }): List<Title> {
        val today = today()
        val kids = kids()
        val r = hub.rowsFor(kids).firstOrNull() ?: return emptyList()
        val (p, lead) = RowPaging.all<Any?>({ if (RowPaging.page > 1) null else pick(hub, today, kids) }, { load(hub, r, today, kids) })
        @Suppress("UNCHECKED_CAST") val rest = take(hub.title, (lead as List<Title>).filter { it.key != (p as? Title)?.key })
        return listOfNotNull(p as? Title) + rest
    }

    // ---- rows ----

    /**
     * A seasonal hub's row (Home; a genre page while a matching hub is on): the hub's tile first (it opens the hub),
     * then Tonight's Pick (badged "TONIGHT'S PICK") and the hub's lead titles. Never left out: the tile always shows.
     * [page] prefixes its id.
     */
    fun hubRow(hub: SeasonalHub, claims: RowClaims, page: String): RowSpec {
        val today = today()
        return RowSpec(
            id = "$page:hub:${hub.id}", name = hub.title, style = RowStyle.LANDSCAPE, hubs = listOf(tile(hub, today)),
            load = { homeTitles(hub) { n, l -> claims.take(n, l) } },
            badge = { t -> if (t.key == pickedKey(hub, today)) "TONIGHT'S PICK" else null },
        )
    }

    /** Home's hub row: the hub on today for this profile (there always is one). */
    fun homeSeasonRow(claims: RowClaims): RowSpec = hubRow(active(), claims, "home")

    /**
     * One hub's page (fire-home CinemaSeason), under its masthead ([hub]): Tonight's Pick across the page (a Spotlight
     * whose kicker is "Tonight's Pick  ·  Night 26 of 31"), the hub's rows for this profile (a kids profile sees only its
     * kids-safe rows; the first as big cards with the hub's kicker), then the other hubs on today ("More to Celebrate",
     * tiles only). Empty for an unknown id.
     */
    fun seasonPage(hubId: String, claims: RowClaims): List<RowSpec> {
        val hub = SeasonCalendar.find(hubId) ?: return emptyList()
        val today = today()
        val kids = kids()
        val p = "season:${hub.id}"
        val out = ArrayList<RowSpec>()
        val kick = listOfNotNull("Tonight's Pick", hub.night(today)).joinToString("  ·  ")
        out += RowSpec("$p:Tonight's Pick", "", RowStyle.BIG, spotlight = true, deep = false, kicker = { kick }, load = {
            pick(hub, today, kids)?.takeIf { it.backdropPath != null }?.let { claims.claim("Tonight's Pick", listOf(it)) }
        })
        for ((i, r) in hub.rowsFor(kids).withIndex()) {
            val title = SeasonCalendar.fill(r.title, hub, today)
            out += BrowsePages.titleRow(p, claims, title, if (i == 0) RowStyle.BIG else RowStyle.LANDSCAPE,
                kicker = if (i == 0) { _ -> hub.kicker } else null) { load(hub, r, today, kids) }
        }
        val others = all(today, kids).filter { it.id != hub.id }
        if (others.isNotEmpty()) {
            out += RowSpec("$p:More to Celebrate", "More to Celebrate", RowStyle.LANDSCAPE, deep = false,
                hubs = others.map { tile(it, today) }, load = { emptyList() })
        }
        Log.i(TAG, "page ${hub.id}, ${hub.rowsFor(kids).size} rows${if (kids) " (kids profile)" else ""}, more: ${others.joinToString { it.id }.ifEmpty { "-" }}")
        return out
    }
}
