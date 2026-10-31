package com.dreamos.desktop.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.Dp
import com.dreamos.desktop.shell.BarSolid
import com.dreamos.desktop.shell.Motion
import com.dreamos.desktop.shell.ShellState
import com.dreamos.desktop.shell.data.CardItem
import com.dreamos.desktop.core.content.Discover
import com.dreamos.desktop.core.model.DiscoverCategory
import com.dreamos.desktop.core.model.DiscoverFilter
import com.dreamos.desktop.core.model.DiscoverSort
import com.dreamos.desktop.core.model.Genre
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.ServiceSpec
import com.dreamos.desktop.shell.data.RowStyle
import com.dreamos.desktop.ui.kit.SkeletonRow
import com.dreamos.desktop.shell.data.Hub
import com.dreamos.desktop.shell.data.Title
import com.dreamos.desktop.shell.openHub
import com.dreamos.desktop.ui.kit.BrandTile
import com.dreamos.desktop.ui.kit.CardRow
import com.dreamos.desktop.ui.kit.EmptyState
import com.dreamos.desktop.ui.kit.EndCap
import com.dreamos.desktop.ui.kit.EntryTile
import com.dreamos.desktop.ui.kit.GenreTile
import com.dreamos.desktop.ui.kit.Icon
import com.dreamos.desktop.ui.kit.KeepClear
import com.dreamos.desktop.ui.kit.LocalMetrics
import com.dreamos.desktop.ui.kit.Metrics
import com.dreamos.desktop.ui.kit.PageHeader
import com.dreamos.desktop.ui.kit.PickerButton
import com.dreamos.desktop.ui.kit.PickerItem
import com.dreamos.desktop.ui.kit.mix
import com.dreamos.desktop.ui.kit.Rainbow
import com.dreamos.desktop.ui.kit.Type
import com.dreamos.desktop.ui.kit.posterLines
import com.dreamos.desktop.ui.kit.skeletonLines
import kotlinx.coroutines.launch

/** Browse by Genre's cards in the Fire Stick's order (GenreSpec.SHORTCUTS). */
val GENRES = listOf(
    "Action", "Adventure", "Comedy", "Drama", "Horror", "Thriller", "Sci-Fi", "Fantasy", "Romance", "Crime", "Mystery",
    "Animation", "Anime", "Family", "Kids", "Documentary", "Reality", "History", "War", "Western", "Music",
)

/** The brand colors the service tiles take as a soft tint (fire-home ServiceSpec); a service not listed gets gray. */
val SERVICE_COLORS = mapOf(
    "Netflix" to Color(0xFFE50914), "Hulu" to Color(0xFF1CE783), "Prime Video" to Color(0xFF00A8E1), "Apple TV" to Color(0xFF8E8E93),
    "Disney+" to Color(0xFF113CCF), "HBO Max" to Color(0xFF002BE7), "Peacock" to Color(0xFFF6B700), "Paramount+" to Color(0xFF0064FF),
    "AMC+" to Color(0xFF2F80ED), "Crunchyroll" to Color(0xFFF47521), "Tubi" to Color(0xFF7B3FE4),
)

/** A genre's rainbow hue: its place in the Fire Stick's order, so a genre keeps its color everywhere. */
fun genreHue(name: String) = Rainbow.hue(GENRES.indexOf(name).let { if (it < 0) name.length else it })

/** Browse's Discover wall: its filters (kept while the app runs) and the pages loaded so far. */
class DiscoverModel {
    val list = LazyListState()
    var filter by mutableStateOf(DiscoverFilter(sort = Discover.SORT_DEFAULT))
    val titles = mutableStateListOf<Title>()
    var page = 0
    var pages = 1
    var loading by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var tv by mutableStateOf<Title?>(null)
    var movie by mutableStateOf<Title?>(null)
    var genres by mutableStateOf<List<Genre>>(emptyList())
    var services by mutableStateOf<List<ServiceSpec>>(emptyList())
    var wallGenres by mutableStateOf<List<DiscoverCategory>>(emptyList())
}

/**
 * Browse: the TV Shows and Movies entry tiles (wearing the week's most popular art), Browse by Genre (drawn genre art,
 * the rainbow's hues running across), Browse by Service, then Discover: type, order and genre pickers over a poster wall
 * that pages in as the viewer nears its end.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowsePage(state: ShellState) {
    val c = state.catalog
    val m = LocalMetrics.current
    val dens = LocalDensity.current
    val d = state.model("browse") { DiscoverModel() }
    BarSolid()
    LaunchedEffect(state.toTop) { if (state.toTop > 0) d.list.animateScrollToItem(0) }
    LaunchedEffect(Unit) {
        // The genre and service tiles are fixed lists: they show with or without a TMDB key (the Fire Stick's).
        if (d.genres.isEmpty()) d.genres = runCatching { c.genres() }.getOrDefault(emptyList())
        if (d.services.isEmpty()) d.services = runCatching { c.services() }.getOrDefault(emptyList())
        if (!c.ready) return@LaunchedEffect
        if (d.tv == null) d.tv = runCatching { c.entryArt(MediaKind.TV) }.getOrNull()
        if (d.movie == null) d.movie = runCatching { c.entryArt(MediaKind.MOVIE) }.getOrNull()
    }
    LaunchedEffect(d.filter.kind) { if (c.ready) d.wallGenres = runCatching { c.discoverCategories(d.filter.kind) }.getOrDefault(emptyList()) }
    val (cols, pw) = m.columns(170f, min = 4)
    val wallCols = remember { intArrayOf(cols) }.also { it[0] = cols }
    suspend fun more() {
        // A page that made no full line (the tidying took most of it), or a wall still under three lines: fetch on
        // (fire-home CinemaBrowse.loadWall), a few pages at most per call.
        repeat(5) {
            if (d.loading || d.page >= d.pages || !c.ready) return
            d.loading = true
            val f = d.filter
            val before = d.titles.size / wallCols[0]
            val r = runCatching { c.discover(f, d.page + 1) }.getOrNull()
            if (f == d.filter) {
                if (r == null) d.failed = true
                else { d.failed = false; d.page = r.page; d.pages = r.totalPages.coerceAtLeast(r.page); d.titles += r.titles.filter { t -> d.titles.none { it.key == t.key } } }
            }
            d.loading = false
            val lines = d.titles.size / wallCols[0]
            if (r == null || f != d.filter || (lines > before && lines >= 3)) return
        }
    }
    LaunchedEffect(d.filter) { if (d.titles.isEmpty()) more() }
    LaunchedEffect(d.list) {
        snapshotFlow { d.list.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - 3 } }
            .collect { near -> if (near) more() }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun setFilter(f: DiscoverFilter) {
        if (f == d.filter) return
        d.filter = f; d.titles.clear(); d.page = 0; d.pages = 1; d.failed = false
        scope.launch { more() }
        // Saved for this profile (fire-home: type, order and genre; any other saved filters untouched).
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val saved = com.dreamos.desktop.core.content.Discover.load()
                saved.type = f.kind; saved.genre = f.genre; saved.sort = f.sort
                com.dreamos.desktop.core.content.Discover.save(saved)
            }
        }
    }
    // The wall opens on this profile's saved choice (Popular & Favorites when there's none).
    val filtersFor = state.model("browse:filtersFor") { arrayOf<String?>(null) }
    LaunchedEffect(Unit) {
        com.dreamos.desktop.core.service.Services.profiles.current.collect { p ->
            if (filtersFor[0] == p.id) return@collect
            filtersFor[0] = p.id
            val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.dreamos.desktop.core.content.Discover.load() }.getOrNull() }
                ?: return@collect
            setFilter(d.filter.copy(kind = saved.type, genre = saved.genre, sort = saved.sort))
        }
    }

    val clear = remember(m, dens) { with(dens) { KeepClear((m.barH + m.u(8)).toPx(), m.u(24).toPx(), Motion.reduced) } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides clear) {
        LazyColumn(state = d.list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = m.barH + m.u(28))) {
            item(key = "head") { PageHeader("Browse", "Movies, shows, genres and services, all in one place") }
            item(key = "entry") {
                val w = Dp((m.widthDp - 2 * m.gutter.value - m.cardGap.value) / 2)
                Row(Modifier.padding(start = m.gutter, end = m.gutter, top = m.u(24) + m.liftRoom, bottom = m.liftRoom), horizontalArrangement = Arrangement.spacedBy(m.cardGap)) {
                    EntryTile("TV Shows", "New seasons and every series", d.tv, Rainbow.hue(0) to Rainbow.hue(1), w, w * 9f / 32f + m.u(60)) { state.openHub(Hub.shows) }
                    EntryTile("Movies", "New releases and all-time favorites", d.movie, Rainbow.hue(3) to Rainbow.hue(4), w, w * 9f / 32f + m.u(60)) { state.openHub(Hub.movies) }
                }
            }
            item(key = "genres") {
                Box(Modifier.padding(top = m.rowGap - m.liftRoom)) {
                    val g = d.genres
                    if (g.isEmpty()) SkeletonRow("Browse by Genre", RowStyle.LANDSCAPE)
                    else CardRow("Browse by Genre", g.size, m.genreW * 9f / 16f) {
                        items(g.size, key = { g[it].id }) { i ->
                            Box(Modifier.restAhead(Hub.genre(g[i]))) {
                                GenreTile(g[i].name, Rainbow.hue(i), m.genreW, glyph = g[i].glyph.ifEmpty { g[i].name }) { state.openHub(Hub.genre(g[i])) }
                            }
                        }
                    }
                }
            }
            item(key = "services") {
                Box(Modifier.padding(top = m.rowGap - m.liftRoom)) {
                    val sv = d.services
                    if (sv.isEmpty()) SkeletonRow("Browse by Service", RowStyle.LANDSCAPE)
                    else CardRow("Browse by Service", sv.size, m.brandW * 9f / 16f) {
                        items(sv.size, key = { sv[it].id }) { i ->
                            Box(Modifier.restAhead(Hub.service(sv[i]))) {
                                BrandTile(sv[i].name, SERVICE_COLORS[sv[i].name] ?: Color(0xFF8E8E93), m.brandW) { state.openHub(Hub.service(sv[i])) }
                            }
                        }
                    }
                }
            }
            item(key = "discover") { DiscoverHeader(d.filter, d.wallGenres, m, ::setFilter) }
            when {
                !c.ready -> item(key = "nokey") { EmptyState(Icon.FILM, "Add a TMDB Key", "Open Settings and add your TMDB key to browse every movie and show here.") }
                d.titles.isEmpty() && d.failed -> item(key = "fail") {
                    EmptyState(Icon.SEARCH, "Nothing to Show", "Check the connection, or try another genre.", "Try Again", { scope.launch { d.failed = false; more() } })
                }
                d.titles.isEmpty() && !d.loading && d.page > 0 && d.page >= d.pages -> item(key = "none") {
                    EmptyState(Icon.SEARCH, "Nothing Matches", "Try another genre or order.")
                }
                else -> {
                    // Only full lines go up while more pages are coming; the end of everything may be short.
                    val last = d.page >= d.pages
                    val shown = if (last) d.titles.size else d.titles.size / cols * cols
                    if (shown > 0) posterLines("wall", d.titles.take(shown).map { CardItem(it) }, cols, pw, m)
                    if (!last) skeletonLines("more", cols, pw, m, lines = if (shown == 0) 2 else 1)
                    else item(key = "end") { com.dreamos.desktop.notices.AllCaughtUp(onBackToTop = { scope.launch { d.list.animateScrollToItem(0) } }) }
                }
            }
        }
    }
}

/**
 * Resting on a genre or service tile (the pointer over it, or keyboard focus on it) for [TileAhead.REST_MS] fills its
 * page ahead, behind the viewer, so it usually opens filled (fire-home CinemaBrowse.prefetchOnRest). Moving on before
 * then costs nothing; a page filled in the last 20 minutes isn't filled again.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun Modifier.restAhead(hub: Hub): Modifier = this
    .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter) { TileAhead.rest(hub, true) }
    .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) { TileAhead.rest(hub, false) }
    .onFocusChanged { TileAhead.rest(hub, it.hasFocus) }

private object TileAhead {
    const val REST_MS = 450L
    private const val FRESH_MS = 20 * 60_000L
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private val filledAt = HashMap<String, Long>()
    private var waiting: kotlinx.coroutines.Job? = null
    private var on: String? = null

    fun rest(hub: Hub, has: Boolean) {
        val key = hub.toString()
        if (!has) { if (on == key) { waiting?.cancel(); on = null }; return }
        if (on == key) return
        waiting?.cancel()
        on = key
        if (!com.dreamos.desktop.core.content.Tmdb.hasKey()) return
        val now = System.currentTimeMillis()
        if (filledAt[key]?.let { now - it < FRESH_MS } == true) return
        waiting = scope.launch {
            kotlinx.coroutines.delay(REST_MS)
            filledAt[key] = System.currentTimeMillis()
            com.dreamos.desktop.core.catalog.ContentServices.warmPage(hub.page)
        }
    }
}

/**
 * The wall's title ("Every Movie and Show") and its filters: one line of three Dream pickers (the iPhone's words and order), each naming its
 * current choice and opening a panel under it. The line wraps only when the window is too narrow for all three.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoverHeader(f: DiscoverFilter, genres: List<DiscoverCategory>, m: Metrics, set: (DiscoverFilter) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = m.rowGap + m.u(8))) {
        // The Fire Stick's and iPhone's row title, with no sub line.
        Text("Every Movie and Show", style = Type.rowTitle(), modifier = Modifier.padding(horizontal = m.gutter))
        val kinds = listOf(PickerItem("movie", "Movies"), PickerItem("tv", "TV Shows"))
        val sorts = Discover.SORT_ORDER.map { PickerItem(it.name, it.label) }
        // Every genre wears its drawn glyph in a soft rainbow tint, Any Genre first (as on the iPhone).
        fun tint(i: Int) = mix(Rainbow.hue(i), Color.White, 0.35f)
        val genreItems = listOf(PickerItem("0", "Any Genre", "Any", tint(0))) +
            genres.distinctBy { it.id }.mapIndexed { i, g -> PickerItem(g.id.toString(), g.name, g.name, tint(i + 1)) }
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = m.gutter, vertical = m.u(18)),
            horizontalArrangement = Arrangement.spacedBy(m.u(12)),
            verticalArrangement = Arrangement.spacedBy(m.u(12)),
        ) {
            PickerButton("Movies or TV Shows", kinds, if (f.kind == MediaKind.TV) "tv" else "movie", "Chooses movies or TV shows", { id ->
                // The genre comes along where the other kind has it (Horror stays Horror), as on the Fire Stick.
                val to = if (id == "tv") MediaKind.TV else MediaKind.MOVIE
                set(f.copy(kind = to, genre = Discover.mapGenre(f.genre, to)))
            })
            PickerButton("Sort By", sorts, f.sort.name, "Chooses the order", { id -> set(f.copy(sort = DiscoverSort.valueOf(id))) })
            PickerButton("Genres", genreItems, f.genre.toString(), "Chooses a genre", { id -> set(f.copy(genre = id.toInt())) })
        }
    }
}
