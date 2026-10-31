package com.dreamos.desktop.core.catalog

import com.dreamos.desktop.core.content.*
import com.dreamos.desktop.core.model.*
import com.dreamos.desktop.core.service.Catalog
import com.dreamos.desktop.core.time.DreamTime
import com.dreamos.desktop.core.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The [Catalog] service: pages as lazy rows ([rowSlots] then [fill] near the screen), deep rows ([more]), heroes and
 * Browse. One [PageSession] per page at a time: a new [rowSlots] call starts the page afresh (new claims). Row loads run
 * on [Dispatchers.IO] (TMDB is paced by TmdbGate, lists are cached 30 min in memory and longer on disk).
 *
 * [warm] fills Home, Movies and TV Shows ahead at launch; the next [rowSlots] / [fill] / [spotlight] of such a page
 * answer from it at once.
 */
class CatalogImpl(@Volatile var browse: BrowseRows = BasicBrowseRows) : Catalog {
    private val sessions = ConcurrentHashMap<CatalogPage, PageSession>()

    /** A page filled ahead by [warm]: its session, each row's answer (null: left out) and the hero, used once. */
    private class Prepared(
        val session: PageSession, val rows: ConcurrentHashMap<String, Filled>, @Volatile var hero: List<CatalogItem>?,
        val stamp: String, val at: Long,
    )
    private class Filled(val row: CatalogRow?)

    /** Waiting for the next [rowSlots], one per page. Worst case: Home, Movies and TV Shows filled, 2-3 MB. */
    private val prepared = ConcurrentHashMap<CatalogPage, Prepared>()
    /** Handed to the page now showing: [fill] and [spotlight] answer from it. */
    private val serving = ConcurrentHashMap<CatalogPage, Prepared>()
    /** Pages [warm] is filling now: a [rowSlots] meanwhile waits for it (up to [WARM_WAIT_MS]) rather than starting over. */
    private val warming = ConcurrentHashMap<CatalogPage, kotlinx.coroutines.CompletableDeferred<Unit>>()

    private fun build(page: CatalogPage): PageSession = when (page) {
        CatalogPage.Home -> HomePage(browse).session()
        else -> {
            val claims = browse.claims(page)
            PageSession(page, claims, extraRows(page) + browse.pageRows(page, claims))
        }
    }

    /** Movies / TV Shows add Continue Watching and Because You Watched (fire-home CinemaCategory "All"). */
    private fun extraRows(page: CatalogPage): List<PageRow> {
        val kind = when (page) { CatalogPage.Movies -> MediaKind.MOVIE; CatalogPage.Shows -> MediaKind.TV; else -> return emptyList() }
        return listOf(Personal.continueWatching("${kind.path}:continue", kind))
    }

    private fun session(page: CatalogPage): PageSession = sessions[page] ?: synchronized(sessions) {
        sessions[page] ?: build(page).also { sessions[page] = it }
    }

    override suspend fun rowSlots(page: CatalogPage): List<CatalogRow> = withContext(Dispatchers.IO) {
        if (!Tmdb.hasKey()) return@withContext emptyList()
        warming[page]?.let { w -> kotlinx.coroutines.withTimeoutOrNull(WARM_WAIT_MS) { w.await() } }
        serving.remove(page)
        val ready = prepared.remove(page)?.takeIf { it.stamp == stamp() && System.currentTimeMillis() - it.at < PREPARED_MS }
        val s = ready?.session ?: build(page)
        sessions[page] = s
        if (ready != null) serving[page] = ready
        s.rows.map { it.slot() }
    }

    override suspend fun fill(row: CatalogRow, page: CatalogPage): CatalogRow? = withContext(Dispatchers.IO) {
        val s = session(page)
        serving[page]?.takeIf { it.session === s }?.rows?.remove(row.id)?.let { return@withContext it.row }
        val r = s.row(row.id) ?: return@withContext null
        fillRow(s, r)
    }

    /**
     * Fills [pages] ahead (launch, behind the intro), so their first [rowSlots] / [fill] / [spotlight] answer at once.
     * Two passes per page: every row's lists in parallel (network or disk, 8 rows at a time, the pages together), then
     * the rows filled again in page order from memory, so each title lands in the row it would on screen. Returns the
     * filled rows by page, for the shell's art prefetch ([artPaths]). A page filled ahead is used once, and dropped
     * when the profile, history, My List or a content setting changes, or after [PREPARED_MS].
     *
     * The first page (Home: what the viewer sees first) asks TMDB in the foreground lane, the others in the background
     * lane, so on a first launch Home's rows never queue behind the other pages' (TmdbGate). [onPage] hears each page
     * as soon as it is filled, so the shell can start its art (Home's first).
     */
    suspend fun warm(
        pages: List<CatalogPage> = WARM_PAGES,
        onPage: (CatalogPage, List<CatalogRow>) -> Unit = { _, _ -> },
    ): Map<CatalogPage, List<CatalogRow>> = withContext(Dispatchers.IO) {
        if (!Tmdb.hasKey()) return@withContext emptyMap()
        val t0 = System.currentTimeMillis()
        pages.mapIndexed { i, page ->
            async(if (i == 0) kotlin.coroutines.EmptyCoroutineContext else com.dreamos.desktop.core.net.Lane.element()) {
                val rows = warmPage(page)
                Log.i("Catalog", "warm: $page, ${rows.size} rows in ${System.currentTimeMillis() - t0} ms")
                onPage(page, rows)
                page to rows
            }
        }.awaitAll().toMap()
    }

    private suspend fun warmPage(page: CatalogPage): List<CatalogRow> {
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        warming[page] = done
        try {
            return fillAhead(page)
        } finally {
            warming.remove(page, done)
            done.complete(Unit)
        }
    }

    private suspend fun fillAhead(page: CatalogPage): List<CatalogRow> = coroutineScope {
        val stamp = stamp()
        val gate = Semaphore(8)
        // Pass 1: every row's lists in parallel, on a throwaway session.
        val scout = build(page)
        val hero = async { gate.withPermit { spotlightNow(page) } }
        // Card art isn't waited for: its asks run in the background lane behind the lists (a first launch builds these
        // rows with the backdrops; the remembered key art serves every later launch).
        scout.rows.map { r -> async { gate.withPermit { try { fillRow(scout, r, keyArtWaitMs = 0) } catch (e: Exception) { null } } } }.awaitAll()
        // Pass 2: in page order, from memory.
        val s = build(page)
        val rows = ConcurrentHashMap<String, Filled>()
        for (r in s.rows) rows[r.id] = Filled(try { fillRow(s, r, keyArtWaitMs = 0) } catch (e: Exception) { null })
        if (stamp() == stamp) prepared[page] = Prepared(s, rows, hero.await(), stamp, System.currentTimeMillis())
        s.rows.mapNotNull { rows[it.id]?.row }
    }

    /** The Anime page, an anime genre or chip, Crunchyroll's page: anime belongs there. */
    private fun keepsAnime(page: CatalogPage): Boolean = when (page) {
        CatalogPage.Anime -> true
        is CatalogPage.GenrePage -> RowMix.animeRow(page.genre.name) || BrowsePages.parseChip(page.genre.id)?.second == Discover.ANIME
        is CatalogPage.Service -> RowMix.animeRow(page.spec.name)
        else -> false
    }

    /** Drops pages filled ahead (tests). */
    internal fun forgetPrepared() { prepared.clear(); serving.clear(); sessions.clear() }

    /** What shown rows depend on besides TMDB: a change makes a page filled ahead stale. */
    private fun stamp(): String = listOf(
        ProfileStore.current.value.id, ContentPrefs.version.value, WatchHistory.version.value, MyListStore.flow.value.size,
        MyListStore.flow.value.firstOrNull()?.key, DreamTime.version.value, DreamTime.todayYmd(), Tmdb.key().hashCode(),
    ).joinToString("|")

    /** Fills [r] on this thread (blocking). Null when it has too few titles or its lists failed. */
    fun fillRow(s: PageSession, r: PageRow, keyArtWaitMs: Long = KEY_ART_WAIT_MS): CatalogRow? {
        val hubs = r.hubs()
        val items: List<CatalogItem> = try {
            r.personal?.let { p -> p() ?: return null } ?: run {
                val list = r.load?.invoke().orEmpty()
                when {
                    list.size < r.min -> emptyList()
                    r.spotlight -> listOfNotNull(r.pick(list)?.let { r.card(it, 0) })
                    else -> list.take(r.first).mapIndexed { i, t -> r.card(t, i) }
                }
            }
        } catch (e: Exception) {
            Log.w("Catalog", "${s.page} row ${r.name}: ${e.message ?: e}")
            emptyList()
        }
        // A row with hub tiles always shows (its tiles alone when its titles didn't come).
        if (items.isEmpty() && hubs.isEmpty()) return null
        val cards = withKeyArt(r.style, items, keyArtWaitMs)
        synchronized(s) {
            s.shown[r.id] = items.mapTo(HashSet()) { it.title.key }
            s.nextPage[r.id] = 2
        }
        return CatalogRow(r.id, r.rename?.invoke() ?: r.name, r.style, cards,
            hasMore = r.deep && r.personal == null && !r.spotlight && items.size < RowPaging.MAX_TITLES,
            isSpotlight = r.spotlight, hubs = hubs)
    }

    /**
     * Landscape and big cards carry their card art from the title's /images answer: key art (the backdrop with the
     * title's name on it; fire-home KeyArt.kt), which both show whole in their 16:9 frames, and plain art (a textless
     * backdrop) for a Spotlight's wider frame. Asked for the whole row at once, waiting at most [waitMs]; answers that
     * come later serve the next build (and the shell's row warm-up, which waits for them before a row shows). Known
     * answers (remembered 14 days) cost nothing. Episode cards keep their still.
     */
    private fun withKeyArt(style: RowStyle, items: List<CatalogItem>, waitMs: Long = KEY_ART_WAIT_MS): List<CatalogItem> {
        if (style != RowStyle.LANDSCAPE && style != RowStyle.BIG || items.isEmpty()) return items
        val need = items.filter { it.keyArtPath == null && it.episode == null }.map { it.title }
        if (need.isEmpty()) return items
        Details.primeKeyArt(need, waitMs)
        return items.map { i ->
            if (i.keyArtPath != null || i.episode != null) i
            else i.copy(keyArtPath = Details.knownKeyArt(i.title), plainArtPath = Details.knownPlainArt(i.title))
        }
    }

    override suspend fun more(row: CatalogRow, page: CatalogPage): List<CatalogItem> = withContext(Dispatchers.IO) {
        val s = session(page)
        val r = s.row(row.id) ?: return@withContext emptyList()
        val load = r.load ?: return@withContext emptyList()
        val (from, shown) = synchronized(s) {
            if (r.id in s.done) return@withContext emptyList()
            (s.nextPage[r.id] ?: 2) to HashSet(s.shown[r.id].orEmpty() + row.items.map { it.title.key })
        }
        val (got, fresh) = RowPaging.next(r.name, from, shown, load)
        val room = RowPaging.MAX_TITLES - shown.size
        val out = fresh.take(maxOf(0, room))
        synchronized(s) {
            if (out.isEmpty()) s.done += r.id
            s.nextPage[r.id] = got + 1
            s.shown.getOrPut(r.id) { HashSet() }.addAll(out.map { it.key })
        }
        val base = shown.size
        withKeyArt(r.style, out.mapIndexed { i, t -> r.card(t, base + i) })
    }

    override suspend fun spotlight(page: CatalogPage): List<CatalogItem> = withContext(Dispatchers.IO) {
        if (!Tmdb.hasKey()) return@withContext emptyList()
        val ahead = serving[page] ?: prepared[page]?.takeIf { it.stamp == stamp() }
        ahead?.hero?.let { h -> ahead.hero = null; if (h.isNotEmpty()) return@withContext h }
        spotlightNow(page)
    }

    private fun spotlightNow(page: CatalogPage): List<CatalogItem> {
        if (!Tmdb.hasKey()) return emptyList()
        return try {
            val items = if (page == CatalogPage.Home) {
                val m = HomePicks.moment()
                HomePicks.featured().map { t -> CatalogItem(t, kicker = m.name + if (t.isShow) "  ·  Series" else "  ·  Movie") }
            } else browse.hero(page)
            // Anime only in the anime sections, heroes included (each source filters too; this is the last word).
            if (keepsAnime(page)) items else items.filter { !RowMix.animeHidden(it.title) }
        } catch (e: Exception) {
            Log.w("Catalog", "hero $page: ${e.message ?: e}")
            emptyList()
        }
    }

    override suspend fun chips(page: CatalogPage) = withContext(Dispatchers.IO) { browse.chips(page) }
    override suspend fun genres() = withContext(Dispatchers.IO) { browse.genres() }
    override suspend fun categories() = withContext(Dispatchers.IO) { browse.categories() }
    override suspend fun decades() = withContext(Dispatchers.IO) { browse.decades() }
    override suspend fun hub(id: String) = withContext(Dispatchers.IO) { browse.hub(id) }
    override suspend fun services() = withContext(Dispatchers.IO) { browse.services() }
    override suspend fun hubs() = withContext(Dispatchers.IO) { browse.hubs() }
    override suspend fun discover(filter: DiscoverFilter, page: Int) = withContext(Dispatchers.IO) { browse.discover(filter, page) }
    override suspend fun discoverCategories(kind: MediaKind) = withContext(Dispatchers.IO) { browse.discoverCategories(kind) }

    /**
     * Shown rows may be stale: profile switched, history / My List / Watched changed, the clock settings or the day
     * changed, a content setting changed (the anime rule, a Kids level). Debounced a little: a sync lands in bursts.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override val changes: Flow<Unit> = merge(
        ProfileStore.current.map { it.id }.distinctUntilChanged().drop(1).map { },
        WatchHistory.version.drop(1).map { },
        MyListStore.flow.drop(1).map { },
        DreamTime.version.drop(1).map { },
        ContentPrefs.version.drop(1).map { },
        newDay(),
    ).debounce(250)

    companion object {
        /** The pages filled at launch. */
        val WARM_PAGES = listOf(CatalogPage.Home, CatalogPage.Movies, CatalogPage.Shows)
        /** A page filled ahead is used within this long, else built afresh. */
        const val PREPARED_MS = 10 * 60_000L
        /** Longest a landscape row waits for key art it doesn't know yet. */
        const val KEY_ART_WAIT_MS = 400L
        /** Longest a page's [rowSlots] waits for [warm] already filling it. */
        const val WARM_WAIT_MS = 2_500L

        /**
         * The TMDB image path each card shows first, in row order, for an art prefetch (null: the card has none; the
         * shell sizes each by the row's style): a poster in poster and Top 10 rows, else a backdrop (Continue Watching
         * and episode cards: the still; a Spotlight: the textless one; landscape and big cards: key art first). The same
         * choice as the shell's cards (ui/kit Cards.kt `cardChoice`).
         */
        fun artPaths(row: CatalogRow): List<String?> = row.items.map { i ->
            when {
                row.style == RowStyle.POSTER || row.style == RowStyle.TOP10 -> i.title.posterPath
                row.isSpotlight -> i.plainArtPath ?: i.title.backdropPath
                row.style == RowStyle.CONTINUE -> i.episode?.stillPath ?: i.title.backdropPath
                else -> i.episode?.stillPath ?: i.keyArtPath ?: i.title.backdropPath
            }
        }
    }

    /** Emits just after each midnight in the viewer's zone (daily pages, "new" windows and charts move on). */
    private fun newDay(): Flow<Unit> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            val next = DreamTime.midnight(DreamTime.ymdPlus(1, now)) ?: (now + 3_600_000L)
            kotlinx.coroutines.delay((next - now).coerceIn(60_000L, 3_600_000L * 25) + 5_000L)
            if (DreamTime.todayYmd() != DreamTime.ymd(now)) emit(Unit)
        }
    }
}
