package com.dreamos.desktop.mylist

import com.dreamos.desktop.ui.kit.rainbowFill
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import com.dreamos.desktop.core.content.MyListView
import com.dreamos.desktop.core.model.HistoryEntry
import com.dreamos.desktop.core.service.Services
import com.dreamos.desktop.shell.BarSolid
import com.dreamos.desktop.shell.Motion
import com.dreamos.desktop.shell.ShellState
import com.dreamos.desktop.shell.data.CardItem
import com.dreamos.desktop.shell.data.Title
import com.dreamos.desktop.ui.kit.CardArt
import com.dreamos.desktop.ui.kit.CardBadge
import com.dreamos.desktop.ui.kit.CardMarks
import com.dreamos.desktop.ui.kit.Caption
import com.dreamos.desktop.ui.kit.Chip
import com.dreamos.desktop.ui.kit.DOT
import com.dreamos.desktop.ui.kit.EmptyState
import com.dreamos.desktop.ui.kit.Icon
import com.dreamos.desktop.ui.kit.KeepClear
import com.dreamos.desktop.ui.kit.LocalMetrics
import com.dreamos.desktop.ui.kit.Metrics
import com.dreamos.desktop.ui.kit.PageHeader
import com.dreamos.desktop.ui.kit.Palette
import com.dreamos.desktop.ui.kit.Rainbow
import com.dreamos.desktop.ui.kit.Type
import com.dreamos.desktop.ui.kit.cardChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** My List's chips (kept per profile) and the saved titles part way through. */
class MyListModel {
    val list = LazyListState()
    var type by mutableStateOf("all")
    var order by mutableStateOf("recent")
    var going by mutableStateOf<List<HistoryEntry>>(emptyList())
}

/**
 * My List (fire-home CinemaMyList, iPhone MyListView): the saved titles, organized. Chips on top (All, Movies, TV
 * Shows, then Recently Added and A–Z; the choice is kept per profile), then the sections: Continue Watching (saved
 * titles part way through, most recently watched first; a click carries on), Movies and TV Shows, each headed with its
 * count ("TV Shows  ·  12"). Each section is a poster wall with the title alone under each poster. Right-click (or the
 * Menu key) on a poster removes it; the page follows the list and the history as they change anywhere in the app.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MyListPage(state: ShellState) {
    val c = state.catalog
    val m = LocalMetrics.current
    val dens = LocalDensity.current
    val v = state.model("mylist") { MyListModel() }
    val saved by c.myList.collectAsState()
    val profile by Services.profiles.current.collectAsState()
    val history by Services.history.version.collectAsState()
    val scope = rememberCoroutineScope()
    BarSolid()
    LaunchedEffect(state.toTop) { if (state.toTop > 0) v.list.animateScrollToItem(0) }
    LaunchedEffect(profile.id) {
        val (t, o) = withContext(Dispatchers.IO) { runCatching { MyListView.load() }.getOrDefault("all" to "recent") }
        v.type = t; v.order = o
    }
    // Saved titles part way through (or with an episode up next), most recently watched first.
    LaunchedEffect(profile.id, history, saved) {
        val keys = saved.mapTo(HashSet()) { it.key }
        v.going = if (keys.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
            runCatching { Services.history.continueWatching() }.getOrDefault(emptyList())
                .filter { it.key in keys && (it.upNext || it.progress > 0.0) }.distinctBy { it.key }
        }
    }
    fun pick(type: String, order: String) {
        if (type == v.type && order == v.order) return
        v.type = type; v.order = order
        MyListView.save(type, order)
        scope.launch { v.list.animateScrollToItem(0) }
    }
    val clear = remember(m, dens) { with(dens) { KeepClear((m.barH + m.u(8)).toPx(), m.u(24).toPx(), Motion.reduced) } }
    // Smaller than the Browse wall's posters (the Fire Stick: eight across here, seven there).
    val (cols, pw) = m.columns(150f, min = 5)
    CompositionLocalProvider(LocalBringIntoViewSpec provides clear) {
        LazyColumn(state = v.list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = m.barH + m.u(28), bottom = m.u(48))) {
            item(key = "head") { PageHeader("My List", summary(saved), Modifier.padding(bottom = m.u(10))) }
            if (saved.isEmpty()) item(key = "empty") {
                EmptyState(Icon.BOOKMARK, "Nothing Saved Yet", "Open any movie or show and choose My List. It will wait for you here.")
            } else {
                item(key = "chips") { Chips(v.type, v.order, m, ::pick) }
                sections(v, saved, cols, pw, m) { scope.launch { v.list.animateScrollToItem(0) } }
            }
        }
    }
}

/** The sections for the chips now: Continue Watching, Movies, TV Shows; the end cap past two lines. */
private fun LazyListScope.sections(v: MyListModel, saved: List<Title>, cols: Int, pw: Dp, m: Metrics, toTop: () -> Unit) {
    val byKey = saved.associateBy { it.key }
    val keep = { t: Title -> v.type == "all" || t.isShow == (v.type == "tv") }
    val going = v.going.filter { e -> byKey[e.key]?.let(keep) == true }
    fun sort(l: List<Title>) = if (v.order == "az") l.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { MyListView.sortName(it.name) }) else l
    val movies = sort(saved.filter { !it.isShow })
    val shows = sort(saved.filter { it.isShow })
    var lines = 0
    fun section(key: String, name: String, count: Int?, cards: List<CardItem>) {
        item(key = "$key:head") { SectionHeading(name, count, m) }
        for ((i, line) in cards.chunked(cols).withIndex()) {
            item(key = "$key:$i:${v.order}:${line.first().title.key}") {
                Row(
                    Modifier.fillMaxWidth().padding(start = m.gutter, end = m.gutter, top = m.liftRoom, bottom = m.liftRoom - m.u(6)),
                    horizontalArrangement = Arrangement.spacedBy(m.cardGap),
                ) { for (it in line) ListPoster(it, pw, m) }
            }
            lines++
        }
    }
    if (going.isNotEmpty()) section("going", "Continue Watching", null, going.map { e ->
        CardItem(
            byKey.getValue(e.key), progress = e.progress.toFloat().takeIf { !e.upNext && it > 0f }, upNext = e.upNext,
            season = e.season.takeIf { it > 0 }, episode = e.episode.takeIf { it > 0 },
        )
    })
    if (v.type != "tv" && movies.isNotEmpty()) section("movies", "Movies", movies.size, movies.map { CardItem(it) })
    if (v.type != "movie" && shows.isNotEmpty()) section("shows", "TV Shows", shows.size, shows.map { CardItem(it) })
    if (v.type == "movie" && movies.isEmpty()) item(key = "noMovies") {
        EmptyState(Icon.FILM, "No Movies Saved Yet", "Open any movie and choose My List. It will wait for you here.")
    }
    if (v.type == "tv" && shows.isEmpty()) item(key = "noShows") {
        EmptyState(Icon.TV, "No TV Shows Saved Yet", "Open any show and choose My List. It will wait for you here.")
    }
    if (lines > 2) item(key = "end") { com.dreamos.desktop.notices.AllCaughtUp(onBackToTop = toTop) }
}

/** "12 Titles  ·  8 Movies  ·  4 TV Shows". */
private fun summary(saved: List<Title>): String? {
    if (saved.isEmpty()) return null
    val movies = saved.count { !it.isShow }
    val shows = saved.size - movies
    fun n(c: Int, one: String, many: String) = "$c ${if (c == 1) one else many}"
    return listOfNotNull(n(saved.size, "Title", "Titles"), if (movies > 0) n(movies, "Movie", "Movies") else null,
        if (shows > 0) n(shows, "TV Show", "TV Shows") else null).joinToString(DOT)
}

/** The type (All, Movies, TV Shows) and, after a quiet divider, the order (Recently Added, A–Z): one current chip in each. */
@Composable
private fun Chips(type: String, order: String, m: Metrics, pick: (String, String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(start = m.gutter, end = m.gutter, top = m.u(6), bottom = m.u(8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.u(10))) {
                for ((i, l) in listOf("All", "Movies", "TV Shows").withIndex()) Chip(l, type == MyListView.TYPES[i], { pick(MyListView.TYPES[i], order) })
                Box(Modifier.padding(horizontal = m.u(8)).width(m.u(1)).height(m.u(18)).background(Color(0x38FFFFFF)))
                for ((i, l) in listOf("Recently Added", "A–Z").withIndex()) Chip(l, order == MyListView.ORDERS[i], { pick(type, MyListView.ORDERS[i]) })
            }
        }
    }
}

/** "TV Shows  ·  12": the name in the row title's tone, the count quieter. */
@Composable
private fun SectionHeading(name: String, count: Int?, m: Metrics) {
    val text = buildAnnotatedString {
        append(name)
        if (count != null) withStyle(SpanStyle(color = Palette.textDim)) { append(DOT + count) }
    }
    Text(text, style = Type.rowTitle(), modifier = Modifier.padding(start = m.gutter, end = m.gutter, top = m.u(26), bottom = m.u(2)))
}

/**
 * A saved title's poster with its name alone under it (the section names the type). In Continue Watching it carries
 * the rainbow progress line, or UP NEXT, and a click carries on where the viewer left off.
 */
@Composable
private fun ListPoster(item: CardItem, w: Dp, m: Metrics) {
    val h = w * 1.5f
    val px = with(LocalDensity.current) { w.toPx() }
    val going = item.upNext || item.progress != null
    Column(Modifier.width(w)) {
        CardArt(item, w, h, cardChoice(item, com.dreamos.desktop.shell.data.RowStyle.POSTER, px)) {
            if (!going) CardMarks(item, narrow = w < m.u(170))
            if (item.upNext) CardBadge("UP NEXT", Modifier.padding(m.u(8)))
            val p = item.progress
            if (p != null && p > 0f) {
                Box(Modifier.align(Alignment.BottomStart).padding(horizontal = m.u(10), vertical = m.u(9)).fillMaxWidth().height(m.u(4))
                    .clip(RoundedCornerShape(50)).background(Color(0x4DFFFFFF))) {
                    Box(Modifier.fillMaxWidth(p.coerceIn(0.03f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).rainbowFill(RoundedCornerShape(50)))
                }
            }
        }
        Caption(item.title.name, null, w)
    }
}
