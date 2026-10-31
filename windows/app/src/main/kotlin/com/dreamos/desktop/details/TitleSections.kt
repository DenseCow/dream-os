package com.dreamos.desktop.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamos.desktop.settings.kit.Dream
import com.dreamos.desktop.settings.kit.DreamButton
import com.dreamos.desktop.settings.kit.DreamType
import com.dreamos.desktop.settings.kit.MenuItem
import com.dreamos.desktop.settings.kit.dreamClick
import com.dreamos.desktop.settings.kit.lit
import com.dreamos.desktop.settings.kit.rainbowEdge
import com.dreamos.desktop.settings.kit.rainbowNow
import kotlinx.coroutines.flow.conflate

private sealed interface SeasonLoad {
    data object Loading : SeasonLoad
    data class Ready(val episodes: List<EpisodeInfo>) : SeasonLoad
    data object Failed : SeasonLoad
}

/**
 * Episodes: the season tabs (seasons out plus up to two announced ones, "Coming Mar 4"), the episode lane opened on
 * Up Next / Resume, and the blurb of the episode under the pointer or the keyboard. An announced season that opens
 * with episodes already up (out early) is reported through [onSeasonOut], which drops its "Coming ..." words.
 */
@Composable
fun EpisodesSection(d: TitleDetails, source: TitleSource, gutter: Dp, onPlay: (EpisodeInfo) -> Unit, onSources: (EpisodeInfo) -> Unit, onSeasonOut: (Int) -> Unit = {},
                    onSeasonLoaded: (Int, List<EpisodeInfo>) -> Unit = { _, _ -> }) {
    val first = d.progress?.season?.takeIf { s -> d.seasons.any { it.number == s && it.comingWords == null } } ?: d.defaultSeason ?: d.seasons.first().number
    var season by remember(d.ref) { mutableIntStateOf(first) }
    // A season readied ahead (the page's opening one, a tab the pointer rested on) draws in the first frame.
    var load by remember(d.ref, season) { mutableStateOf<SeasonLoad>(source.seasonNow(d.ref, season)?.let { SeasonLoad.Ready(it) } ?: SeasonLoad.Loading) }
    var attempt by remember(d.ref, season) { mutableIntStateOf(0) }
    // Episode pills follow new answers in place.
    LaunchedEffect(d.ref, season) {
        source.pillUpdates.conflate().collect {
            (load as? SeasonLoad.Ready)?.let { r -> load = SeasonLoad.Ready(source.liveEpisodePills(d.ref, r.episodes)) }
            kotlinx.coroutines.delay(250)
        }
    }
    LaunchedEffect(d.ref, season, attempt) {
        // Asked again behind a readied season (an equal answer changes nothing); a failed refresh keeps what shows.
        val fresh = runCatching { source.season(d.ref, season) }
        load = fresh.fold({ SeasonLoad.Ready(source.liveEpisodePills(d.ref, it)) }, { if (load is SeasonLoad.Ready) load else SeasonLoad.Failed })
        val l = load
        if (l is SeasonLoad.Ready) onSeasonLoaded(season, l.episodes)
        if (l is SeasonLoad.Ready && l.episodes.any { !it.upcoming } && d.seasons.any { it.number == season && it.comingWords != null }) onSeasonOut(season)
    }
    com.dreamos.desktop.preload.PopProbe("season", load !is SeasonLoad.Loading, season)
    Column {
        SectionTitle("Episodes", gutter)
        // The season tabs, one included (CinemaDetails' chips: a lone season still shows its name, "Limited Series").
        if (d.seasons.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.padding(horizontal = gutter - 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                d.seasons.forEach { s -> SeasonTab(s, s.number == season, onRest = { com.dreamos.desktop.preload.TitlePageAhead.season(source, d.ref, s.number) }) { season = s.number } }
            }
        }
        when (val l = load) {
            SeasonLoad.Loading -> HoverLane(gutter) { items(4) { Box(Modifier.width(300.dp).aspectRatio(16f / 9f).background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))) } }
            SeasonLoad.Failed -> Column(Modifier.padding(horizontal = gutter, vertical = 18.dp)) {
                Text("Couldn't load this season.", style = DreamType.Body.copy(color = Dream.TextDim))
                Spacer(Modifier.height(12.dp))
                DreamButton("Try Again", { attempt++ })
            }
            is SeasonLoad.Ready -> {
                val eps = l.episodes
                val ahead = d.seasons.firstOrNull { it.number == season && it.comingWords != null }
                if (eps.isEmpty() && ahead != null) {
                    // An announced season with nothing listed yet: one "New Season" card and its blurb.
                    HoverLane(gutter) { item { SeasonComingCard(ahead, 300.dp) {} } }
                    Text("Season ${ahead.number}${TitleWords.DOT}${ahead.comingLong ?: TitleWords.TBA}", style = DreamType.Meta, modifier = Modifier.padding(horizontal = gutter))
                } else if (eps.isEmpty()) {
                    Text("No Episodes of Season $season to Watch Yet", style = DreamType.Body.copy(color = Dream.TextDim), modifier = Modifier.padding(horizontal = gutter, vertical = 18.dp))
                } else {
                    val p = d.progress
                    val upNext = when {
                        p != null && p.inProgress && p.season == season -> p.episode
                        d.upNext?.first == season -> d.upNext.second
                        else -> null
                    }
                    val startAt = eps.indexOfFirst { it.number == upNext }.coerceAtLeast(0)
                    var shown by remember(eps) { mutableIntStateOf(startAt) }
                    val lane = rememberLazyListState(initialFirstVisibleItemIndex = (startAt - 1).coerceAtLeast(0))
                    HoverLane(gutter, state = lane) {
                        items(eps.size) { i ->
                            val e = eps[i]
                            val tag = when {
                                e.number != upNext -> null
                                e.progress > 0f && e.progress < TitleProgress.FINISHED -> "Resume"
                                else -> "Up Next"
                            }
                            EpisodeCard(e, 300.dp, tag, onPlay = { onPlay(e) }, onFocusOrHover = { shown = i }, options = listOf(
                                MenuItem("Play") { onPlay(e) },
                                MenuItem("Choose a Source") { onSources(e) },
                            ))
                        }
                    }
                    eps.getOrNull(shown)?.let { e ->
                        // The card above already names it: the blurb leads with its facts (CinemaDetails.Blurb).
                        Column(Modifier.padding(horizontal = gutter).widthIn(max = 880.dp)) {
                            Text(TitleWords.episodeBlurb(e), style = DreamType.Meta.copy(color = Dream.Text.copy(alpha = 0.9f)))
                            if (e.overview.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(e.overview, style = DreamType.Body.copy(color = Dream.Text.copy(alpha = 0.82f))) }
                        }
                    }
                }
            }
        }
    }
}

/** A season tab: bright and bold with the rainbow line under it when current; announced ones carry "Coming Mar 4". */
@Composable
private fun SeasonTab(s: SeasonInfo, current: Boolean, onRest: () -> Unit = {}, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val lit = src.lit()
    // Pointed at or focused: that season's episodes load now, so clicking shows them at once.
    LaunchedEffect(lit.any) { if (lit.any && !current) onRest() }
    val glow = rainbowNow(current || lit.any)
    Row(
        Modifier.rainbowEdge(lit.focused, glow, 10.dp, edge = 2.dp, halo = 0.dp)
            .background(if (lit.hovered || lit.focused) Color.White.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(10.dp))
            .dreamClick(src, onClick = onClick)
            .drawBehind {
                if (current) {
                    val y = size.height - 3.dp.toPx()
                    drawLine(glow, Offset(12.dp.toPx(), y), Offset(size.width - 12.dp.toPx(), y), 3.dp.toPx())
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(s.name, style = DreamType.Row.copy(color = if (current || lit.any) Dream.Text else Dream.TextDim, fontWeight = if (current) FontWeight.Bold else FontWeight.Medium))
        s.comingWords?.let {
            Spacer(Modifier.width(10.dp))
            Text(it, style = DreamType.Small.copy(color = Dream.TextDim))
        }
    }
}

/** Information: label over value cells, four across (two in a narrow window); wide cells span two. */
@Composable
fun InformationSection(d: TitleDetails, gutter: Dp, pageWidth: Dp) {
    val cells = d.info
    if (cells.isEmpty()) return
    val cols = if (pageWidth - gutter * 2 < 760.dp) 2 else 4
    val gap = 28.dp
    // One column on fixed units (InfoGrid): every row's cells line up, a wide cell is two units and the gap between.
    val unit = (pageWidth - gutter * 2 - gap * (cols - 1)) / cols
    Column {
        SectionTitle("Information", gutter)
        Spacer(Modifier.height(16.dp))
        Column(Modifier.padding(horizontal = gutter), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            packCells(cells, cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { c ->
                        val span = if (c.wide && cols > 1) 2 else 1
                        Column(Modifier.width(unit * span + gap * (span - 1))) {
                            Text(c.label.uppercase(), style = DreamType.Small.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = Dream.TextDim))
                            Spacer(Modifier.height(5.dp))
                            Text(c.value, style = DreamType.Row.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium))
                        }
                    }
                }
            }
        }
    }
}

/** Fills rows of [cols] in order; a wide cell takes two; later single cells fill a gap a wide one left. */
private fun packCells(cells: List<InfoCell>, cols: Int): List<List<InfoCell>> {
    val rows = mutableListOf<MutableList<InfoCell>>()
    val left = cells.toMutableList()
    while (left.isNotEmpty()) {
        val row = mutableListOf<InfoCell>(); var used = 0
        val it = left.iterator()
        while (it.hasNext() && used < cols) {
            val c = it.next(); val span = if (c.wide && cols > 1) 2 else 1
            if (used + span <= cols) { row += c; used += span; it.remove() }
        }
        if (row.isEmpty()) row += left.removeAt(0)
        rows += row
    }
    return rows
}

/** Where to Watch: Streaming, Free, Rent or Buy, then the JustWatch credit (TMDB's data, from JustWatch). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhereToWatchSection(w: WhereToWatch, gutter: Dp) {
    if (w.isEmpty) return
    SectionTitle("Where to Watch", gutter)
    Column(Modifier.padding(horizontal = gutter).padding(top = 14.dp)) {
        listOf("Streaming" to w.streaming, "Free" to w.free, "Rent or Buy" to w.rentOrBuy).filter { it.second.isNotEmpty() }.forEach { (label, names) ->
            Text(label.uppercase(), style = DreamType.Small.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = Dream.TextDim))
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                names.forEach { n ->
                    Text(n, style = DreamType.Meta.copy(color = Dream.Text), modifier = Modifier.background(Dream.Glass, RoundedCornerShape(50)).border(1.dp, Dream.GlassEdge, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 7.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Text("Streaming Availability From JustWatch", style = DreamType.Small.copy(color = Dream.TextFaint))
    }
}
