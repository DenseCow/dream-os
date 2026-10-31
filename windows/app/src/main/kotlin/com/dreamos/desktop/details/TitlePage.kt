package com.dreamos.desktop.details

import com.dreamos.desktop.trailer.TrailerBackdrop
import com.dreamos.desktop.trailer.trailerUi
import com.dreamos.desktop.trailer.trailerWatch
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamos.desktop.settings.kit.Dream
import com.dreamos.desktop.settings.kit.DreamButton
import com.dreamos.desktop.settings.kit.DreamScrollbar
import com.dreamos.desktop.settings.kit.DreamType
import com.dreamos.desktop.settings.kit.Glyph
import com.dreamos.desktop.settings.kit.LocalPagesNav
import com.dreamos.desktop.settings.kit.PageFrame
import com.dreamos.desktop.settings.kit.Pill
import com.dreamos.desktop.settings.kit.PlayRequest
import com.dreamos.desktop.settings.kit.ProgressLine
import com.dreamos.desktop.settings.kit.RemoteImage
import com.dreamos.desktop.settings.kit.StatusMark
import com.dreamos.desktop.settings.kit.Mark
import com.dreamos.desktop.settings.kit.TitleRef
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.conflate
import androidx.compose.ui.graphics.graphicsLayer
import com.dreamos.desktop.settings.kit.PillKind

private sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data object Failed : Load<Nothing>
}

/**
 * The title page (CinemaDetails, iPhone TitleDetailsView), for the mouse and the keyboard: one scrolling page with
 * the full-bleed hero (backdrop, logo, meta line, status band, buttons, progress, synopsis), then Episodes,
 * More Like This, Cast, Information and Where to Watch. Esc goes back.
 *
 * [initial] is what the card already knew (name, poster) so the page never opens blank.
 */
@Composable
fun TitlePage(ref: TitleRef, source: TitleSource, onBack: () -> Unit, initial: TitleDetails? = null) {
    // A page built moments ago (a hover readied it) shows in the first frame; it is asked again behind it.
    var state by remember(ref) { mutableStateOf<Load<TitleDetails>>(source.instant(ref)?.let { Load.Ready(it) } ?: Load.Loading) }
    var attempt by remember(ref) { mutableIntStateOf(0) }
    // The hero trailer's lookup starts now, beside the details (usually a card's rest started it already).
    LaunchedEffect(ref) { com.dreamos.desktop.trailer.TrailerAhead.now(ref.tmdbId, ref.isShow, ref.name) }
    LaunchedEffect(ref, attempt) {
        // The hero art and the opening season come in with the details (bounded), so the page lands complete.
        val fresh = runCatching { source.details(ref).also { if (state !is Load.Ready) com.dreamos.desktop.preload.TitlePageAhead.ready(source, it) else com.dreamos.desktop.preload.TitlePageAhead.behind(source, it) } }
        // A failed refresh keeps the page already showing.
        state = fresh.fold({ Load.Ready(it) }, { if (state is Load.Ready) state else Load.Failed })
    }
    com.dreamos.desktop.preload.PopProbe("title-body", state is Load.Ready, ref)
    PageFrame(onBack) {
        when (val s = state) {
            is Load.Ready -> TitleBody(s.value, source, onBack)
            Load.Loading -> TitleLoading(initial ?: TitleDetails(ref, ref.name), onBack)
            Load.Failed -> TitleFailed(ref.name, onBack) { state = Load.Loading; attempt++ }
        }
    }
}

/** The page's side margin: about 5% of the width, between 40 and 96. */
private fun gutterFor(w: Dp): Dp = (w * 0.05f).coerceIn(40.dp, 96.dp)

@Composable
private fun FloatingBack(onBack: () -> Unit, modifier: Modifier = Modifier) {
    DreamButton("Back", onBack, glyph = Glyph.Back, onImage = true, modifier = modifier)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun TitleBody(start: TitleDetails, source: TitleSource, onBack: () -> Unit) {
    val nav = LocalPagesNav.current
    val scope = rememberCoroutineScope()
    var d by remember(start) { mutableStateOf(start) }
    var sourcesFor by remember { mutableStateOf<Pair<Int?, Int?>?>(null) }
    var playMenu by remember { mutableStateOf<PlayMenuState?>(null) }
    val scroll = rememberScrollState()
    // The title's trailer behind the hero (docs/port/trailers.md): shows after a 1 s rest.
    val trailer = com.dreamos.desktop.trailer.rememberTrailerBackdropState()
    val play = remember { FocusRequester() }
    val episodesAt = remember { BringIntoViewRequester() }
    val infoAt = remember { BringIntoViewRequester() }
    // A season listed as still to come that's already up (out early) loses its "Coming ..." words; Play's menu lists it.
    val seasonOut: (Int) -> Unit = { n -> d = d.copy(seasons = d.seasons.map { if (it.number == n) it.copy(comingWords = null) else it }) }
    // Information's Episode Length: the real median of the first season loaded (the opening one), written in place.
    var firstSeason by remember(start.ref) { mutableStateOf<List<EpisodeInfo>?>(null) }
    // Pills follow new answers in place (their room is kept, they fade in): once now, then as answers arrive.
    LaunchedEffect(start.ref) {
        d = source.livePills(d)
        source.pillUpdates.conflate().collect { d = source.livePills(d); kotlinx.coroutines.delay(PILL_REFRESH_MS) }
    }
    LaunchedEffect(start.ref) {
        val next = d.seasons.filter { it.comingWords != null }.minByOrNull { it.number } ?: return@LaunchedEffect
        if (source.seasonUpEarly(d.ref, next.number)) seasonOut(next.number)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Dream.Black).trailerWatch(trailer)) {
        val pageW = maxWidth
        val gutter = gutterFor(pageW)
        val heroH = (maxHeight * 0.86f).coerceAtLeast(500.dp)
        val textW = (maxWidth * 0.5f).coerceIn(520.dp, 760.dp)
        val buttonsW = (maxWidth - gutter * 2).coerceAtMost(maxOf(textW, maxWidth * 0.72f))
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            // ---- Hero ----
            Box(Modifier.fillMaxWidth().height(heroH)) {
                RemoteImage(d.backdropUrl, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopEnd, placeholder = Color(0xFF0B0B0D))
                com.dreamos.desktop.preload.ArtProbe("title-backdrop", d.backdropUrl)
                com.dreamos.desktop.preload.ArtProbe("title-logo", d.logoUrl)
                com.dreamos.desktop.preload.PopProbe("title-pill", d.statusPill != null)
                com.dreamos.desktop.preload.PopProbe("title-scores", d.scores.tomato != null || d.scores.badge != null)
                TrailerBackdrop(trailer, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().trailerUi(trailer).background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.42f to Color.Black.copy(alpha = 0.55f), 0.75f to Color.Transparent)))
                Box(Modifier.fillMaxSize().trailerUi(trailer).background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Dream.Black)))
                Column(Modifier.align(Alignment.BottomStart).trailerUi(trailer).padding(start = gutter, bottom = 28.dp).widthIn(max = buttonsW)) {
                    Column(Modifier.widthIn(max = textW)) {
                        TitleLogo(d)
                        Spacer(Modifier.height(18.dp))
                        MetaLine(d)
                        StatusBand(d, source.pillExpected(d))
                    }
                    Spacer(Modifier.height(22.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val p = d.progress
                        val resuming = p != null && p.inProgress
                        Box {
                            DreamButton(TitleWords.playLabel(d), { trailer.stopNow(); nav.play(playRequest(d, fromStart = false)) }, primary = true, glyph = Glyph.Play, big = true,
                                modifier = Modifier.focusRequester(play), onSecondary = { playMenu = PlayMenuState.Root })
                            PlayOptions(d, source, playMenu, { playMenu = it }, onPlay = { trailer.stopNow(); nav.play(it) }, onSources = { trailer.stopNow(); sourcesFor = it })
                        }
                        if (resuming) DreamButton("Start Over", { trailer.stopNow(); nav.play(playRequest(d, fromStart = true)) }, glyph = Glyph.Restart, big = true, onImage = true)
                        if (d.ref.isShow && d.seasons.isNotEmpty()) DreamButton("Episodes", { scope.launch { episodesAt.bringIntoView() } }, big = true, onImage = true)
                        d.trailerKey?.let { k -> DreamButton("Trailer", { trailer.stopNow(); nav.playTrailer(d.ref, k) }, glyph = Glyph.Film, big = true, onImage = true) }
                        MyListButton(d.onMyList) { on -> scope.launch { d = d.copy(onMyList = source.setMyList(d.ref, on)) } }
                        DreamButton("Choose a Source", { trailer.stopNow(); sourcesFor = playEpisode(d) }, glyph = Glyph.Sources, big = true, onImage = true)
                    }
                    TitleWords.progressLine(d)?.let { line ->
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProgressLine(d.progress!!.fraction.coerceAtLeast(0.03f), Modifier.width(150.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(line, style = DreamType.Meta.copy(color = Dream.Text))
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Column(Modifier.widthIn(max = textW)) {
                        d.tagline?.let { Text(it, style = DreamType.Row.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)); Spacer(Modifier.height(6.dp)) }
                        Text(d.overview.ifBlank { "No description available." }, style = DreamType.Body.copy(color = Dream.Text.copy(alpha = 0.86f)))
                    }
                }
                // The trailer's speaker, bottom right of the hero, after the info column in the Tab order, clear of the info column (which stops at buttonsW).
                TrailerSoundButton(trailer, Modifier.align(Alignment.BottomEnd).trailerUi(trailer).padding(end = gutter, bottom = 34.dp))
            }
            val restLimit = with(androidx.compose.ui.platform.LocalDensity.current) { heroH.toPx() * 0.4f }
            androidx.compose.runtime.SideEffect {
                trailer.update(com.dreamos.desktop.core.trailer.TrailerRef(d.ref.tmdbId, d.ref.isShow, d.ref.name, hintKey = d.trailerKey),
                    resting = scroll.value < restLimit, visible = sourcesFor == null && playMenu == null)
            }
            // ---- Sections ----
            if (d.ref.isShow && d.seasons.isNotEmpty()) {
                Box(Modifier.bringIntoViewRequester(episodesAt)) {
                    EpisodesSection(d, source, gutter, onPlay = { e -> trailer.stopNow(); nav.play(PlayRequest(d.ref, e.season, e.number)) }, onSources = { e -> trailer.stopNow(); sourcesFor = e.season to e.number }, onSeasonOut = seasonOut,
                        onSeasonLoaded = { _, eps -> if (firstSeason == null) firstSeason = eps })
                }
            }
            if (d.similar.isNotEmpty()) {
                SectionTitle("More Like This", gutter)
                // The pointed-at or focused poster's facts and synopsis under the lane (CinemaDetails.describeRec).
                var rec by remember(d.ref) { mutableIntStateOf(0) }
                HoverLane(gutter) {
                    items(d.similar.size) { i ->
                        val c = d.similar[i]
                        PosterCard(c, 168.dp, onOpen = { nav.openTitle(c.ref) }, options = { titleOptions(c, source, nav, scope) }, onLit = { rec = i })
                    }
                }
                d.similar.getOrNull(rec)?.let { RecBlurb(it, gutter) }
            }
            if (d.cast.isNotEmpty()) {
                SectionTitle("Cast", gutter)
                HoverLane(gutter, gap = 12.dp) {
                    items(d.cast.size) { i -> val m = d.cast[i]; CastFace(m, 112.dp) { nav.openPerson(m.id, m.name, m.role, d.ref) } }
                }
            }
            Box(Modifier.bringIntoViewRequester(infoAt)) { InformationSection(firstSeason?.let { TitleWords.withEpisodeLength(d, it) } ?: d, gutter, pageW) }
            WhereToWatchSection(d.whereToWatch, gutter)
            Spacer(Modifier.height(56.dp))
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(4.dp), style = DreamScrollbar)
        // Once the hero has scrolled up, a soft top shade keeps Back readable over whatever passes under it.
        val shade = (scroll.value / 240f).coerceIn(0f, 1f)
        if (shade > 0f) Box(Modifier.fillMaxWidth().height(96.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f * shade), Color.Transparent))))
        FloatingBack(onBack, Modifier.align(Alignment.TopStart).trailerUi(trailer).padding(start = 28.dp, top = 22.dp))
        sourcesFor?.let { (s, e) -> SourcesPanel(d, s, e, source, onClose = { sourcesFor = null }) }
    }
    LaunchedEffect(Unit) { runCatching { play.requestFocus() } }
}

/** "Movie  ·  2024  ·  IMDb 7.5" and up to three lines of the synopsis, under More Like This (CinemaDetails.describeRec). */
@Composable
private fun RecBlurb(c: TitleCard, gutter: Dp) {
    Column(Modifier.padding(horizontal = gutter).widthIn(max = 880.dp)) {
        Text(listOfNotNull(if (c.ref.isShow) "Series" else "Movie", c.year?.toString(), c.audience).joinToString(TitleWords.DOT),
            style = DreamType.Meta.copy(color = Dream.Text.copy(alpha = 0.9f)))
        if (c.overview.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(c.overview, style = DreamType.Body.copy(color = Dream.Text.copy(alpha = 0.8f)), maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/**
 * A poster's options (the Fire Stick's title menu): Details, Add to My List or Remove from My List as it stands now,
 * Play Trailer (Dream Player's trailer mode finds the title's trailer).
 */
fun titleOptions(c: TitleCard, source: TitleSource, nav: com.dreamos.desktop.settings.kit.PagesNav, scope: kotlinx.coroutines.CoroutineScope): List<com.dreamos.desktop.settings.kit.MenuItem> {
    val saved = source.onMyList(c.ref)
    return listOf(
        com.dreamos.desktop.settings.kit.MenuItem("Details") { nav.openTitle(c.ref) },
        com.dreamos.desktop.settings.kit.MenuItem(if (saved) "Remove from My List" else "Add to My List") { scope.launch { source.setMyList(c.ref, !saved) } },
        com.dreamos.desktop.settings.kit.MenuItem("Play Trailer") { nav.playTrailer(c.ref, "") },
    )
}

/** The episode Play would start for a show (for Choose a Source): history's, else up next, else the first. */
private fun playEpisode(d: TitleDetails): Pair<Int?, Int?> {
    if (!d.ref.isShow) return null to null
    val p = d.progress
    if (p != null && p.inProgress && p.season != null) return p.season to p.episode
    val n = d.upNext ?: ((d.defaultSeason ?: 1) to 1)
    return n.first to n.second
}

private fun playRequest(d: TitleDetails, fromStart: Boolean): PlayRequest {
    val (s, e) = playEpisode(d)
    return PlayRequest(d.ref, s, e, fromStart = fromStart)
}

/** The title's logo, or its name typed while there is none (and while the logo comes in). */
@Composable
private fun TitleLogo(d: TitleDetails) {
    var logo by remember(d.logoUrl) { mutableStateOf(d.logoUrl?.let { com.dreamos.desktop.settings.kit.PageImages.cached(it) }) }
    LaunchedEffect(d.logoUrl) { if (logo == null && d.logoUrl != null) logo = com.dreamos.desktop.settings.kit.PageImages.load(d.logoUrl) }
    val img = logo
    if (img != null) {
        // The logo's own shape decides its box: wide logos take the width, tall ones the height.
        val ratio = img.width.toFloat() / img.height.coerceAtLeast(1)
        val h = (440.dp / ratio).coerceAtMost(140.dp)
        androidx.compose.foundation.Image(img, d.name, Modifier.height(h).width(h * ratio), contentScale = ContentScale.Fit)
    } else TypedTitle(d.name)
}

@Composable
private fun TypedTitle(name: String) {
    Text(name, style = DreamType.Display.copy(fontSize = if (name.length > 24) 36.sp else 44.sp, lineHeight = if (name.length > 24) 42.sp else 50.sp, fontWeight = FontWeight.Bold), maxLines = 3)
}

/**
 * The availability or Watched pill, its note, and the language pills. When a pill is [expected] but not answered yet,
 * the band keeps one pill's room (an invisible pill) so the logo and meta line never move when it lands; anything
 * answered after the band first showed fades in. Hidden when there's nothing to say and nothing coming.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusBand(d: TitleDetails, expected: Boolean) {
    val any = d.statusPill != null || d.statusNote != null || d.languagePills.isNotEmpty()
    if (!any && !expected) return
    val since = remember(d.ref) { System.currentTimeMillis() }
    Spacer(Modifier.height(16.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!any) Box(Modifier.graphicsLayer { alpha = 0f }) { Pill("Available Now", PillKind.Green) }
        d.statusPill?.let { com.dreamos.desktop.preload.Arriving(since) { Pill(it.text, it.kind, it.glyph) } }
        d.statusNote?.let { com.dreamos.desktop.preload.Arriving(since) { Text(it, style = DreamType.Meta.copy(color = Dream.TextDim), modifier = Modifier.padding(end = 6.dp)) } }
        d.languagePills.forEach { com.dreamos.desktop.preload.Arriving(since) { Pill(it.text, it.kind, it.glyph) } }
    }
}

/** At most one page pill rebuild this often (answers arrive in bursts while cards are checked). */
private const val PILL_REFRESH_MS = 250L

/** My List: "My List" with a plus; on the list, "Added" with the check. */
@Composable
private fun MyListButton(on: Boolean, onChange: (Boolean) -> Unit) {
    DreamButton(if (on) "Added" else "My List", { onChange(!on) }, glyph = if (on) Glyph.Check else Glyph.Plus, big = true, onImage = true, modifier = Modifier.widthIn(min = 132.dp))
}

@Composable
fun SectionTitle(text: String, gutter: Dp, modifier: Modifier = Modifier) {
    Text(text, style = DreamType.Section.copy(fontSize = 21.sp), modifier = modifier.padding(start = gutter, end = gutter, top = 36.dp))
}

@Composable
private fun TitleLoading(d: TitleDetails, onBack: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gutter = gutterFor(maxWidth)
        // What the card already knew (its backdrop, the same image the page then keeps) under the page's own scrims,
        // so the top is there at once and the details settle onto it.
        Box(Modifier.fillMaxWidth().height((maxHeight * 0.86f).coerceAtLeast(500.dp))) {
            RemoteImage(d.backdropUrl, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopEnd, placeholder = Color(0xFF0B0B0D))
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.42f to Color.Black.copy(alpha = 0.55f), 0.75f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Dream.Black)))
        }
        Column(Modifier.align(Alignment.TopStart).padding(start = gutter, top = maxHeight * 0.86f - 260.dp)) {
            if (d.name.isNotBlank()) TypedTitle(d.name)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusMark(Mark.BUSY, 22.dp)
                Spacer(Modifier.width(12.dp))
                Text("Loading This Title", style = DreamType.Meta)
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(170.dp, 132.dp, 132.dp).forEach { w -> Box(Modifier.size(w, 54.dp).background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))) }
            }
        }
        FloatingBack(onBack, Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 22.dp))
    }
}

@Composable
private fun TitleFailed(name: String, onBack: () -> Unit, onRetry: () -> Unit) {
    val retry = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (name.isNotBlank()) { Text(name, style = DreamType.Title, maxLines = 2); Spacer(Modifier.height(12.dp)) }
            Text("Couldn't load this title. Check your internet connection and try again.", style = DreamType.Body.copy(color = Dream.TextDim), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DreamButton("Try Again", onRetry, primary = true, modifier = Modifier.focusRequester(retry))
                DreamButton("Back", onBack)
            }
        }
        FloatingBack(onBack, Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 22.dp))
    }
    LaunchedEffect(Unit) { runCatching { retry.requestFocus() } }
}
