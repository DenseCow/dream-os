package com.dreamos.desktop.details

import androidx.compose.ui.text.font.FontWeight
import com.dreamos.desktop.settings.kit.PageRow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamos.desktop.settings.kit.Dream
import com.dreamos.desktop.settings.kit.DreamButton
import com.dreamos.desktop.settings.kit.DreamType
import com.dreamos.desktop.settings.kit.Glyph
import com.dreamos.desktop.settings.kit.LocalPagesNav
import com.dreamos.desktop.settings.kit.Mark
import com.dreamos.desktop.settings.kit.Pill
import com.dreamos.desktop.settings.kit.PlayRequest
import com.dreamos.desktop.settings.kit.Radii
import com.dreamos.desktop.settings.kit.StatusMark
import com.dreamos.desktop.settings.kit.dreamClick
import com.dreamos.desktop.settings.kit.lit
import com.dreamos.desktop.settings.kit.litColors
import com.dreamos.desktop.settings.kit.rainbowEdge
import com.dreamos.desktop.settings.kit.rainbowNow
import com.dreamos.desktop.settings.kit.softEdges

/**
 * Choose a Source: a panel from the right listing every copy Dream Core found for the title (or the episode), best
 * first, with its quality, size, badges and release name. Picking one plays that copy. Esc or a click outside closes.
 */
@Composable
fun SourcesPanel(d: TitleDetails, season: Int?, episode: Int?, source: TitleSource, onClose: () -> Unit) {
    val nav = LocalPagesNav.current
    var rows by remember { mutableStateOf<List<SourceOption>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var loose by remember { mutableStateOf(false) }
    /** The picked row while it gets ready, and a row whose pick didn't work (its line says so for a moment). */
    var starting by remember { mutableStateOf<String?>(null) }
    var refused by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(attempt, loose) {
        failed = false; rows = null
        runCatching { source.sources(d.ref, season, episode, loose) }.onSuccess { rows = it }.onFailure { failed = true }
    }
    fun pick(s: SourceOption) {
        if (starting != null) return
        val fallback = PlayRequest(d.ref, season, episode, sourceId = s.id)
        starting = s.id; refused = null
        scope.launch {
            runCatching { source.prepare(d.ref, season, episode, s.id) }
                .onSuccess { req -> onClose(); if (req != null) nav.playPrepared(req, fallback) else nav.play(fallback) }
                .onFailure { e ->
                    starting = null
                    refused = s.id to ((e as? com.dreamos.desktop.core.model.DreamError)?.message ?: "Not Available Right Now")
                    delay(2_600); if (refused?.first == s.id) refused = null
                }
        }
    }
    val first = remember { FocusRequester() }
    com.dreamos.desktop.settings.kit.BackHandler(onClose)
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && (e.key == Key.Escape || e.key == Key.Backspace)) { onClose(); true } else false },
    ) {
        val w = (maxWidth * 0.46f).coerceIn(460.dp, 640.dp)
        Column(
            Modifier.align(Alignment.CenterEnd).width(w).fillMaxHeight()
                .background(Dream.Panel)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(top = 28.dp, start = 28.dp, end = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (loose) "All Sources" else "Choose a Source", style = DreamType.SubTitle)
                    Spacer(Modifier.height(4.dp))
                    val what = if (season != null && episode != null) d.name + TitleWords.DOT + TitleWords.se(season, episode) else d.name
                    Text(what, style = DreamType.Meta)
                }
                DreamButton("Close", onClose, glyph = Glyph.Close, modifier = Modifier.focusRequester(first))
            }
            Spacer(Modifier.height(18.dp))
            val list = rows
            when {
                failed -> Column {
                    Text("Couldn't look for sources. Try again.", style = DreamType.Body.copy(color = Dream.TextDim))
                    Spacer(Modifier.height(14.dp))
                    DreamButton("Try Again", { attempt++ }, primary = true)
                }
                list == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusMark(Mark.BUSY, 22.dp); Spacer(Modifier.width(12.dp))
                    Text("Looking for Sources", style = DreamType.Row)
                }
                list.isEmpty() -> Column {
                    Text("No sources are available right now.", style = DreamType.Body.copy(color = Dream.TextDim))
                    if (!loose) {
                        Spacer(Modifier.height(16.dp))
                        PageRow("Search All Sources", { loose = true }, caption = "Includes Ones Your Source Settings Usually Hide")
                    }
                }
                else -> {
                    val scroll = rememberScrollState()
                    // Releases named for another episode wait behind "Show N More" (the Fire Stick's source sheet).
                    var showAll by remember(list) { mutableStateOf(false) }
                    val shown = if (showAll) list else list.filter { !it.otherEpisode }
                    val hidden = list.size - shown.size
                    Column(Modifier.fillMaxSize().softEdges(scroll).verticalScroll(scroll).padding(vertical = 12.dp, horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (shown.isEmpty()) Text("Every source found is for a different title or episode.", style = DreamType.Body.copy(color = Dream.TextDim), modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
                        shown.forEach { s ->
                            val line = when { starting == s.id -> "Getting Ready"; refused?.first == s.id -> refused!!.second; else -> null }
                            SourceRow(s, line, busy = starting == s.id) { pick(s) }
                        }
                        if (hidden > 0) PageRow("Show $hidden More", { showAll = true }, caption = "For a Different Title or Episode")
                        if (!loose) {
                            Spacer(Modifier.height(10.dp))
                            PageRow("Search All Sources", { loose = true }, caption = "Includes Ones Your Source Settings Usually Hide")
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun SourceRow(s: SourceOption, line: String?, busy: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val lit = src.lit()
    val glow = rainbowNow(lit.any)
    val (fill, ink) = litColors(lit)
    val dim = if (lit.focused) Dream.InkDim else Dream.TextDim
    Row(
        Modifier.fillMaxWidth()
            .rainbowEdge(lit.any, glow, Radii.Row, edge = if (lit.focused) 3.dp else 2.dp, halo = if (lit.focused) 12.dp else 0.dp)
            .background(fill, RoundedCornerShape(Radii.Row))
            .dreamClick(src, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.headline, style = DreamType.Row.copy(color = ink, fontSize = 16.5.sp))
            if (s.badges.isNotBlank()) { Spacer(Modifier.height(3.dp)); Text(s.badges, style = DreamType.Caption.copy(color = dim)) }
            Spacer(Modifier.height(3.dp))
            Text(s.releaseName, style = DreamType.Small.copy(color = dim), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (line != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusMark(if (busy) Mark.BUSY else Mark.WARN, 16.dp, onPearl = lit.focused); Spacer(Modifier.width(8.dp))
                    Text(line, style = DreamType.Caption.copy(color = ink, fontWeight = FontWeight.Medium))
                }
            }
        }
        if (s.pills.isNotEmpty()) {
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.pills.forEach { Pill(it.text, it.kind, it.glyph, Modifier.border(0.dp, Color.Transparent)) }
            }
        }
    }
}
