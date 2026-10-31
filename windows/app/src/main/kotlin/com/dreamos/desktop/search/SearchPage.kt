package com.dreamos.desktop.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.dreamos.desktop.core.content.RecentSearches
import com.dreamos.desktop.core.model.SearchResults
import com.dreamos.desktop.core.service.Services
import com.dreamos.desktop.core.time.DreamTime
import com.dreamos.desktop.shell.BarSolid
import com.dreamos.desktop.shell.Motion
import com.dreamos.desktop.shell.ShellState
import com.dreamos.desktop.shell.TextEditing
import com.dreamos.desktop.shell.data.CardItem
import com.dreamos.desktop.shell.data.Hub
import com.dreamos.desktop.shell.data.Title
import com.dreamos.desktop.shell.openHub
import com.dreamos.desktop.ui.kit.CardActions
import com.dreamos.desktop.ui.kit.Chip
import com.dreamos.desktop.ui.kit.EmptyState
import com.dreamos.desktop.ui.kit.Focus
import com.dreamos.desktop.ui.kit.Icon
import com.dreamos.desktop.ui.kit.KeepClear
import com.dreamos.desktop.ui.kit.LineIcon
import com.dreamos.desktop.ui.kit.LocalCardActions
import com.dreamos.desktop.ui.kit.LocalMetrics
import com.dreamos.desktop.ui.kit.Metrics
import com.dreamos.desktop.ui.kit.Palette
import com.dreamos.desktop.ui.kit.RoundButton
import com.dreamos.desktop.ui.kit.Type
import com.dreamos.desktop.ui.kit.dreamClick
import com.dreamos.desktop.ui.kit.lit
import com.dreamos.desktop.ui.kit.posterLines
import com.dreamos.desktop.ui.kit.rainbowWhile
import com.dreamos.desktop.ui.kit.ring
import com.dreamos.desktop.ui.kit.skeletonLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/** Search's state, kept while the app runs: what was typed and what came back. */
class SearchModel {
    val list = LazyListState()
    var query by mutableStateOf("")
    /** The last answer, and the query it answers (it stays on screen while the next one loads, as on the Fire Stick). */
    var results by mutableStateOf<SearchResults?>(null)
    var searched by mutableStateOf("")
    var loading by mutableStateOf(false)
    var failed by mutableStateOf(false)
    /** Trending Now, for the profile and day in [trendingKey]. */
    var trending by mutableStateOf<List<Title>?>(null)
    var trendingKey = ""
    /** A genre whose name starts with what's typed (core's GenreSearch.suggest, one at most). */
    var genreHint by mutableStateOf<List<String>>(emptyList())
    /** This profile's recent searches, newest first. */
    var recent by mutableStateOf<List<String>>(emptyList())

    /** A search the viewer engaged with (opened a result, picked a suggestion, pressed Enter) joins Recent. */
    fun remember(q: String) {
        if (q.trim().length < 2) return
        RecentSearches.add(q)
        recent = RecentSearches.list()
    }
}

/**
 * Search (fire-home CinemaSearch, iPhone SearchView): one wide field at the top, focused when the page opens. Before
 * typing: Recent (this profile's last searches) and Trending Now. While typing: suggestions under the field (a genre
 * whose name starts with what's typed, recent searches, matching titles), then the results as poster walls. A genre or
 * category ("horror", "crime shows") leads with its name, "Browse Horror" chips into its Browse page and its movies and
 * shows, unless a title is named exactly what was typed; a person ("brad pitt") brings "Movies & Shows with Brad Pitt";
 * the rest is Top Results.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchPage(state: ShellState) {
    val c = state.catalog
    val m = LocalMetrics.current
    val dens = LocalDensity.current
    val s = state.model("search") { SearchModel() }
    val profile by Services.profiles.current.collectAsState()
    BarSolid()
    LaunchedEffect(Unit) { Focus.request(state.entry, "search: field") }
    LaunchedEffect(profile.id) {
        s.recent = withContext(Dispatchers.IO) { runCatching { RecentSearches.list() }.getOrDefault(emptyList()) }
        // Kept per profile and day: a Kids profile never sees another profile's list, and the app runs for days.
        val key = profile.id + "|" + DreamTime.todayYmd()
        if (c.ready && (s.trending == null || s.trendingKey != key)) {
            s.trending = runCatching { c.trending().filter { it.posterPath != null }.take(MAX_TILES) }.getOrNull()
            s.trendingKey = key
        }
    }
    LaunchedEffect(s) {
        snapshotFlow { s.query.trim() }.distinctUntilChanged().collectLatest { q ->
            if (q.isEmpty()) { s.results = null; s.searched = ""; s.loading = false; s.failed = false; s.genreHint = emptyList(); return@collectLatest }
            // Genre names suggest at once; titles once the search answers.
            s.genreHint = c.suggestions(q).take(1)
            // One letter searches nothing (the Fire Stick's rule): "No Matches" until a second one.
            if (q.length < 2 || !c.ready) { s.results = SearchResults(); s.searched = q; s.loading = false; s.failed = false; return@collectLatest }
            s.loading = true
            delay(DEBOUNCE_MS)
            val r = runCatching { c.search(q) }
            if (q == s.query.trim()) { s.results = r.getOrNull(); s.failed = r.isFailure; s.searched = q; s.loading = false }
        }
    }
    // Opening a result counts the search as a recent one.
    val acts = remember(state, s) {
        object : CardActions by state {
            override fun open(item: CardItem) { s.remember(s.query); state.open(item) }
        }
    }
    val clear = remember(m, dens) { with(dens) { KeepClear((m.barH + m.u(8)).toPx(), m.u(24).toPx(), Motion.reduced) } }
    val (cols, pw) = m.columns(170f, min = 4)
    CompositionLocalProvider(LocalBringIntoViewSpec provides clear, LocalCardActions provides acts) {
        LazyColumn(state = s.list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = m.barH + m.u(28), bottom = m.u(48))) {
            item(key = "field") { SearchField(s, state, m) }
            val q = s.query.trim()
            if (q.isNotEmpty()) {
                val sugg = suggestions(s, q)
                if (sugg.isNotEmpty()) item(key = "suggest") {
                    ChipLine(m) {
                        for (x in sugg) SuggestionChip(highlight(x, q)) { s.remember(x); s.query = x }
                    }
                }
            }
            when {
                !c.ready -> item(key = "nokey") { EmptyState(Icon.SEARCH, "Add a TMDB Key", "Open Settings and add your TMDB key to search movies, shows and people.") }
                q.isEmpty() -> idle(s, cols, pw, m)
                else -> results(s, state, q, cols, pw, m)
            }
        }
    }
}

/** The empty field: Recent (with Clear), then Trending Now. */
private fun LazyListScope.idle(s: SearchModel, cols: Int, pw: androidx.compose.ui.unit.Dp, m: Metrics) {
    if (s.recent.isNotEmpty()) {
        item(key = "recentHead") {
            Row(Modifier.fillMaxWidth().padding(start = m.gutter, end = m.gutter, top = m.u(22)), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent", style = Type.rowTitle(), modifier = Modifier.weight(1f))
                Chip("Clear", false, { RecentSearches.clear(); s.recent = emptyList() })
            }
        }
        item(key = "recent") {
            ChipLine(m) { for (r in s.recent) Chip(r, false, { s.remember(r); s.query = r }) }
        }
    }
    val tr = s.trending
    when {
        tr == null -> { item(key = "trendHead") { Heading("Trending Now", m) }; skeletonLines("trend", cols, pw, m) }
        tr.isEmpty() -> item(key = "trendNone") {
            EmptyState(Icon.SEARCH, "Find Something to Watch", "Type a movie, a show, a person or a genre, and results appear as you type.")
        }
        else -> { item(key = "trendHead") { Heading("Trending Now", m) }; posterLines("trend", tr.map { CardItem(it) }, cols, pw, m) }
    }
}

/** What came back (fire-home CinemaSearch.showResults), in the Fire Stick's order and amounts. */
private fun LazyListScope.results(s: SearchModel, state: ShellState, q: String, cols: Int, pw: androidx.compose.ui.unit.Dp, m: Metrics) {
    val r = s.results
    if (s.failed && !s.loading) {
        item(key = "fail") { EmptyState(Icon.SEARCH, "Couldn't Search", "Check your connection, then try again.", "Try Again", { val t = s.query; s.query = ""; s.query = t }) }
        return
    }
    val titles = r?.titles.orEmpty()
    val person = r?.person?.takeIf { it.titles.isNotEmpty() }
    val genre = r?.genre
    fun section(key: String, heading: String, list: List<Title>) {
        item(key = "$key:head") { Heading(heading, m) }
        posterLines(key, list.map { CardItem(it) }, cols, pw, m)
    }
    when {
        genre != null -> {
            // The genre leads, unless a title is named exactly what was typed: then that title does.
            val exact = titles.firstOrNull()?.takeIf { squash(it.name) == squash(q) }
            val rest = if (exact != null) titles.drop(cols) else titles
            if (exact != null) section("top", "Top Results", titles.take(cols))
            item(key = "genreHead") { Heading(genre.label, m) }
            if (genre.browse.isNotEmpty()) item(key = "genreBrowse") {
                ChipLine(m) {
                    for (b in genre.browse) Chip("Browse ${b.name}", true, { s.remember(s.query); state.openHub(Hub.genre(b)) })
                }
            }
            // Two lines of each when both show; four when the search asked for one type ("horror movies").
            val lines = if (genre.movies.isNotEmpty() && genre.shows.isNotEmpty()) 2 else 4
            if (genre.movies.isNotEmpty()) section("gmovies", genre.movieHeading.ifEmpty { "Movies" }, genre.movies.take(lines * cols))
            if (genre.shows.isNotEmpty()) section("gshows", genre.showHeading.ifEmpty { "Shows" }, genre.shows.take(lines * cols))
            if (person != null) section("person", person.heading, person.titles.take(MAX_TILES / 2))
            if (rest.isNotEmpty()) section("rest", if (exact != null) "More Results" else "Top Results", rest.take(MAX_TILES / 2))
        }
        person != null -> {
            section("person", person.heading, person.titles.take(MAX_TILES))
            if (titles.isNotEmpty()) section("top", "Top Results", titles.take(MAX_TILES / 2))
        }
        titles.isNotEmpty() -> section("top", "Top Results", titles.take(MAX_TILES))
        s.loading || s.searched != q -> { item(key = "searching") { Heading("Searching…", m) }; skeletonLines("results", cols, pw, m) }
        else -> item(key = "none") { EmptyState(Icon.SEARCH, "No Matches for “$q”", "Try fewer letters, or another spelling.") }
    }
}

/**
 * Netflix-style autocomplete (fire-home CinemaSearch.showSuggestions): a genre whose name starts with what's typed
 * ("hor": Horror), recent searches and titles that match, prefix matches first, no repeats.
 */
private fun suggestions(s: SearchModel, q: String): List<String> {
    fun prefix(x: String) = x.startsWith(q, ignoreCase = true) || x.split(' ', ':', '-').any { it.startsWith(q, ignoreCase = true) }
    val titles = if (s.searched == q) s.results?.titles.orEmpty().map { it.name } else emptyList()
    val all = s.recent.filter { it.contains(q, ignoreCase = true) && !it.equals(q, ignoreCase = true) } + titles
    val seen = HashSet<String>()
    return (s.genreHint + all.filter { prefix(it) } + all.filterNot { prefix(it) })
        .filter { it.isNotBlank() && !it.equals(q, ignoreCase = true) && seen.add(it.lowercase()) }
        .take(MAX_SUGGEST)
}

/** The typed part stays as it is; the rest of the suggestion is bold (what picking it adds). */
private fun highlight(x: String, q: String): AnnotatedString = buildAnnotatedString {
    val at = x.indexOf(q, ignoreCase = true)
    val bold = SpanStyle(fontWeight = FontWeight.Bold)
    if (at < 0) { withStyle(bold) { append(x) }; return@buildAnnotatedString }
    withStyle(bold) { append(x.substring(0, at)) }
    append(x.substring(at, at + q.length))
    withStyle(bold) { append(x.substring(at + q.length)) }
}

private fun squash(x: String) = x.lowercase().filter { it.isLetterOrDigit() }

@Composable
private fun Heading(text: String, m: Metrics) {
    Text(text, style = Type.rowTitle(), modifier = Modifier.padding(start = m.gutter, end = m.gutter, top = m.u(22), bottom = m.u(2)))
}

/** A line of chips between the gutters, scrolling sideways when it runs long. */
@Composable
private fun ChipLine(m: Metrics, content: @Composable () -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(start = m.gutter, end = m.gutter, top = m.u(14), bottom = m.u(4)),
        horizontalArrangement = Arrangement.spacedBy(m.u(10)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(m.u(10)), verticalAlignment = Alignment.CenterVertically) { content() } }
    }
}

/** A suggestion: the kit's chip, its label set in two weights ([highlight]). */
@Composable
private fun SuggestionChip(label: AnnotatedString, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val on = src.lit()
    val m = LocalMetrics.current
    val fill by animateColorAsState(if (on) Palette.pearl else Color(0x1FFFFFFF), tween(160))
    val ringA by animateFloatAsState(if (on) 1f else 0f, tween(180))
    val hue by rainbowWhile(ringA > 0f)
    Box(
        Modifier
            .semantics { contentDescription = label.text }
            .ring(ringA, hue, m.u(19), m)
            .height(m.u(38))
            .defaultMinSize(minWidth = m.u(44))
            .clip(RoundedCornerShape(m.u(19)))
            .background(fill)
            .dreamClick(src, onClick)
            .padding(horizontal = m.u(18)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.chip().copy(color = if (on) Palette.ink else Palette.text, fontWeight = FontWeight.Normal), maxLines = 1)
    }
}

/** The field: smoked glass, a drawn search icon, the rainbow ring while it has focus, a clear button once typed in. */
@Composable
private fun SearchField(s: SearchModel, state: ShellState, m: Metrics) {
    var focused by remember { mutableStateOf(false) }
    val ringA by animateFloatAsState(if (focused) 1f else 0f, tween(180))
    val hue by rainbowWhile(focused)
    val edge by animateColorAsState(if (focused) hue else Palette.hairline, tween(160))
    Row(
        Modifier.padding(horizontal = m.gutter).fillMaxWidth().height(m.u(64))
            .clip(RoundedCornerShape(m.u(14)))
            .background(Color(0x1FFFFFFF))
            .border(m.u(if (focused) 2f else 1f), edge.copy(alpha = if (focused) ringA else edge.alpha), RoundedCornerShape(m.u(14)))
            .padding(horizontal = m.u(20)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineIcon(Icon.SEARCH, Palette.textDim, Modifier.size(m.u(24)))
        Spacer(Modifier.width(m.u(16)))
        Box(Modifier.weight(1f)) {
            if (s.query.isEmpty()) Text(HINT, style = Type.style(20f, FontWeight.Normal, color = Palette.textFaint), maxLines = 1)
            BasicTextField(
                s.query, { s.query = it },
                singleLine = true,
                textStyle = Type.style(20f, FontWeight.Medium),
                cursorBrush = SolidColor(Palette.text),
                modifier = Modifier.fillMaxWidth().focusRequester(state.entry)
                    .semantics { contentDescription = "Search" }
                    .onFocusChanged { focused = it.isFocused; TextEditing.active = it.isFocused }
                    // Enter counts the search as a recent one (the iPhone's Search key).
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) s.remember(s.query)
                        false
                    },
            )
        }
        if (s.query.isNotEmpty()) {
            Spacer(Modifier.width(m.u(12)))
            RoundButton(Icon.CLOSE, "Clear Search", { s.query = ""; Focus.request(state.entry, "search: field after clearing") }, size = 36f)
        }
    }
}

private const val HINT = "Movies, shows and people"
private const val DEBOUNCE_MS = 250L
/** The most titles a section shows (fire-home CinemaSearch.MAX_TILES); a second section shows half. */
private const val MAX_TILES = 24
/** Suggestions in the line under the field (the iPhone's four; the TV fits three under its keyboard). */
private const val MAX_SUGGEST = 4
