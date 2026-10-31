package com.dreamos.desktop.details

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import com.dreamos.desktop.settings.kit.Mark
import com.dreamos.desktop.settings.kit.MenuItem
import com.dreamos.desktop.settings.kit.PageFrame
import com.dreamos.desktop.settings.kit.RemoteImage
import com.dreamos.desktop.settings.kit.StatusMark
import kotlinx.coroutines.launch

/**
 * A person's page (iPhone PersonView; the Fire Stick's X-Ray card): portrait, name, life line, a bio that ends on a
 * whole sentence (More shows all), Known For, then every movie and show as a poster grid.
 * [asRole] is the character when opened from a cast face.
 */
@Composable
fun PersonPage(personId: Int, name: String, source: TitleSource, onBack: () -> Unit, asRole: String? = null, from: com.dreamos.desktop.settings.kit.TitleRef? = null) {
    var person by remember(personId) { mutableStateOf<PersonDetails?>(null) }
    var failed by remember(personId) { mutableStateOf(false) }
    var attempt by remember(personId) { mutableIntStateOf(0) }
    LaunchedEffect(personId, attempt) {
        failed = false
        runCatching { source.person(personId, from) }.onSuccess { person = it }.onFailure { failed = true }
    }
    val nav = LocalPagesNav.current
    val scope = rememberCoroutineScope()
    PageFrame(onBack) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pageW = maxWidth
            val gutter = (pageW * 0.05f).coerceIn(40.dp, 96.dp)
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(top = 92.dp, bottom = 48.dp)) {
                val p = person
                Row(Modifier.padding(horizontal = gutter), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(132.dp).clip(CircleShape).background(Color(0xFF1C1C20)), contentAlignment = Alignment.Center) {
                        if (p?.photoUrl != null) RemoteImage(p.photoUrl, Modifier.fillMaxSize())
                        else Text(initials(p?.name ?: name), style = DreamType.Title.copy(color = Dream.TextDim))
                    }
                    Spacer(Modifier.width(28.dp))
                    Column(Modifier.widthIn(max = 760.dp)) {
                        asRole?.takeIf { it.isNotBlank() }?.let { Text("As $it", style = DreamType.Meta); Spacer(Modifier.height(4.dp)) }
                        Text(p?.name ?: name, style = DreamType.Title.copy(fontSize = 32.sp, fontWeight = FontWeight.Bold), maxLines = 2)
                        p?.let { it.metaLine ?: TitleWords.lifeLine(it) }?.let { Spacer(Modifier.height(6.dp)); Text(it, style = DreamType.Meta) }
                        p?.biography?.takeIf { it.isNotBlank() }?.let { Bio(it) }
                    }
                }
                when {
                    failed -> Column(Modifier.padding(horizontal = gutter, vertical = 28.dp)) {
                        Text("Couldn't load $name's movies and shows right now.", style = DreamType.Body.copy(color = Dream.TextDim))
                        Spacer(Modifier.height(14.dp))
                        DreamButton("Try Again", { attempt++ }, primary = true)
                    }
                    p == null -> Row(Modifier.padding(horizontal = gutter, vertical = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusMark(Mark.BUSY); Spacer(Modifier.width(12.dp)); Text("Loading Movies and Shows", style = DreamType.Meta)
                    }
                    p.credits.isEmpty() -> Text("No other movies or shows to show for ${p.name}.", style = DreamType.Body.copy(color = Dream.TextDim), modifier = Modifier.padding(horizontal = gutter, vertical = 28.dp))
                    else -> {
                        fun options(c: TitleCard): () -> List<MenuItem> = { titleOptions(c, source, nav, scope) }
                        if (p.knownFor.size >= 2) {
                            SectionTitle("Known For", gutter)
                            HoverLane(gutter) { items(p.knownFor.size) { i -> val c = p.knownFor[i]; PosterCard(c, 168.dp, { nav.openTitle(c.ref) }, options(c)) } }
                        }
                        SectionTitle("Movies & Shows", gutter)
                        Spacer(Modifier.height(18.dp))
                        // Bounded: the first 60 by popularity, then all on request (every poster is a download).
                        var all by remember(p.id) { mutableStateOf(false) }
                        val shown = if (all) p.credits else p.credits.take(60)
                        PosterGrid(shown, gutter, pageW) { c, w -> PosterCard(c, w, { nav.openTitle(c.ref) }, options(c)) }
                        if (!all && p.credits.size > shown.size) {
                            Spacer(Modifier.height(24.dp))
                            DreamButton("Show All ${p.credits.size}", { all = true }, modifier = Modifier.padding(horizontal = gutter))
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(4.dp), style = DreamScrollbar)
            val back = remember { FocusRequester() }
            DreamButton("Back", onBack, glyph = Glyph.Back, modifier = Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 22.dp).focusRequester(back))
            LaunchedEffect(Unit) { runCatching { back.requestFocus() } }
        }
    }
}

@Composable
private fun Bio(bio: String) {
    var all by remember(bio) { mutableStateOf(false) }
    val lead = TitleWords.bioLead(bio)
    Spacer(Modifier.height(14.dp))
    Text(if (all) bio.trim() else lead, style = DreamType.Body.copy(color = Dream.Text.copy(alpha = 0.86f)))
    if (lead.length < bio.trim().length) {
        Spacer(Modifier.height(10.dp))
        DreamButton(if (all) "Less" else "More", { all = !all })
    }
}

/** Posters in rows of as many as fit at about 160 wide (at least three). */
@Composable
private fun PosterGrid(cards: List<TitleCard>, gutter: Dp, pageWidth: Dp, card: @Composable (TitleCard, Dp) -> Unit) {
    val inner = pageWidth - gutter * 2
    val gap = 18.dp
    val cols = ((inner + gap) / (160.dp + gap)).toInt().coerceAtLeast(3)
    val w = (inner - gap * (cols - 1)) / cols
    Column(Modifier.padding(horizontal = gutter), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        cards.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) { row.forEach { card(it, w) } }
        }
    }
}
