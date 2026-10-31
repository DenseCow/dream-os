package com.dreamos.desktop.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamos.desktop.settings.kit.Dream
import com.dreamos.desktop.settings.kit.DreamType
import com.dreamos.desktop.settings.kit.Glyph
import com.dreamos.desktop.settings.kit.GlyphIcon
import com.dreamos.desktop.settings.kit.MenuItem
import com.dreamos.desktop.settings.kit.OptionsMenu
import com.dreamos.desktop.settings.kit.Pill
import com.dreamos.desktop.settings.kit.PillKind
import com.dreamos.desktop.settings.kit.ProgressLine
import com.dreamos.desktop.settings.kit.Radii
import com.dreamos.desktop.settings.kit.RemoteImage
import com.dreamos.desktop.settings.kit.dreamClick
import com.dreamos.desktop.settings.kit.lit
import com.dreamos.desktop.settings.kit.rainbowEdge
import com.dreamos.desktop.settings.kit.rainbowNow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A sideways row for the mouse, by the shell's wheel rule (PORT.md): the vertical wheel always scrolls the page; a
 * sideways wheel (Shift+wheel, a tilt wheel, a trackpad) scrolls the row, and so does the wheel over an edge arrow
 * (handing the notch back to the page at the row's end). Arrows appear on hover when there is more that way, and
 * keyboard focus scrolls the focused card into view. [gutter] lines the first card up with the page's text; [lift]
 * is the room for a lit card's glow.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HoverLane(
    gutter: Dp,
    gap: Dp = 16.dp,
    lift: Dp = 18.dp,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var width by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxWidth().hoverable(hover).onSizeChanged { width = it.width }) {
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(gap),
            contentPadding = PaddingValues(horizontal = gutter, vertical = lift),
            modifier = Modifier.fillMaxWidth().onPointerEvent(PointerEventType.Scroll) { ev ->
                // Only a sideways wheel moves the row here; the vertical wheel is the page's.
                val d = ev.changes.first().scrollDelta
                if (abs(d.x) <= abs(d.y)) return@onPointerEvent
                val amount = d.x * 90f
                val canGo = if (amount > 0) state.canScrollForward else state.canScrollBackward
                if (amount != 0f && canGo) {
                    scope.launch { state.scrollBy(amount) }
                    ev.changes.forEach { it.consume() }
                }
            },
            content = content,
        )
        val page = width * 0.8f
        /** The wheel over an arrow: the row moves, until its end, where the notch goes to the page. */
        val arrowWheel: Modifier = Modifier.onPointerEvent(PointerEventType.Scroll) { ev ->
            val d = ev.changes.first().scrollDelta
            val amount = (if (abs(d.y) > abs(d.x)) d.y else d.x) * 90f
            val canGo = if (amount > 0) state.canScrollForward else state.canScrollBackward
            if (amount != 0f && canGo) {
                scope.launch { state.scrollBy(amount) }
                ev.changes.forEach { it.consume() }
            }
        }
        AnimatedVisibility(hovered && state.canScrollBackward, Modifier.align(Alignment.CenterStart).padding(start = 10.dp), enter = fadeIn(tween(140)), exit = fadeOut(tween(140))) {
            LaneArrow(Glyph.ChevronLeft, arrowWheel) { scope.launch { state.animateScrollBy(-page, tween(320)) } }
        }
        AnimatedVisibility(hovered && state.canScrollForward, Modifier.align(Alignment.CenterEnd).padding(end = 10.dp), enter = fadeIn(tween(140)), exit = fadeOut(tween(140))) {
            LaneArrow(Glyph.ChevronRight, arrowWheel) { scope.launch { state.animateScrollBy(page, tween(320)) } }
        }
    }
}

@Composable
private fun LaneArrow(glyph: Glyph, wheel: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val lit = src.lit()
    Box(
        wheel.size(48.dp)
            .background(if (lit.hovered) Dream.Pearl else Color(0xD9101012), CircleShape)
            .border(1.dp, Dream.GlassEdge, CircleShape)
            .dreamClick(src, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(glyph, if (lit.hovered) Dream.Ink else Dream.Text, 22.dp) }
}

/** A card's lit look: lift and the rainbow edge (cards and images take the edge only, never the pearl face). */
@Composable
fun Modifier.cardLight(src: MutableInteractionSource, corner: Dp = Radii.Card, scaleTo: Float = 1.05f): Modifier {
    val lit = src.lit()
    val glow = rainbowNow(lit.any)
    val s by animateFloatAsState(if (lit.any) scaleTo else 1f, tween(160))
    return this.scale(s).rainbowEdge(lit.any, glow, corner, edge = if (lit.focused) 3.dp else 2.5.dp, halo = if (lit.focused) 18.dp else 10.dp)
}

/**
 * A poster (More Like This, Known For): pill bottom-left, name and year under it. Right-click: the title's options,
 * built as the menu opens (so Add / Remove from My List says what it will do now). [onLit]: pointed at or focused.
 */
@Composable
fun PosterCard(card: TitleCard, width: Dp, onOpen: () -> Unit, options: () -> List<MenuItem>, onLit: () -> Unit = {}) {
    val src = remember { MutableInteractionSource() }
    val shownAt = remember { System.currentTimeMillis() }
    var menu by remember { mutableStateOf(false) }
    val lit = src.lit()
    androidx.compose.runtime.LaunchedEffect(lit.any) { if (lit.any) onLit() }
    Column(Modifier.width(width)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .cardLight(src)
                .clip(RoundedCornerShape(Radii.Card))
                .dreamClick(src, onSecondary = { menu = true }, onClick = onOpen),
        ) {
            RemoteImage(card.posterUrl, Modifier.fillMaxSize())
            com.dreamos.desktop.preload.ArtProbe("similar-poster", card.posterUrl)
            com.dreamos.desktop.preload.PopProbe("similar-pill", card.pill != null)
            if (card.posterUrl == null) {
                Text(card.name, style = DreamType.Row, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(12.dp))
            }
            card.pill?.let { Box(Modifier.align(Alignment.BottomStart).padding(8.dp)) { com.dreamos.desktop.preload.Arriving(shownAt) { Pill(it.text, it.kind, it.glyph) } } }
        }
        Spacer(Modifier.height(10.dp))
        Text(card.name, style = DreamType.Row.copy(fontSize = 14.5.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(card.year?.toString(), if (card.ref.isShow) "Series" else "Movie").joinToString(TitleWords.DOT), style = DreamType.Small.copy(color = Dream.TextDim))
        if (menu) OptionsMenu(card.name, remember { options() }, onDismiss = { menu = false })
    }
}

/** A cast face: the round portrait (or initials), name and role. Opens the person page. */
@Composable
fun CastFace(m: CastMember, size: Dp, onOpen: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Column(Modifier.width(size + 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).cardLight(src, corner = size / 2).clip(CircleShape).background(Color(0xFF1C1C20))
                .dreamClick(src, onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            com.dreamos.desktop.preload.ArtProbe("cast-photo", m.photoUrl)
            if (m.photoUrl != null) RemoteImage(m.photoUrl, Modifier.fillMaxSize())
            else Text(initials(m.name), style = DreamType.Big.copy(color = Dream.TextDim))
        }
        Spacer(Modifier.height(10.dp))
        Text(m.name, style = DreamType.Row.copy(fontSize = 14.5.sp), maxLines = 2, textAlign = TextAlign.Center)
        if (m.role.isNotBlank()) Text(m.role, style = DreamType.Small.copy(color = Dream.TextDim), maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** "Friday" / "Oct 30" / "9:00 PM" (the parts known), or "Date to Be Announced", centered on an upcoming card. */
@Composable
private fun DateStamp(e: EpisodeInfo, modifier: Modifier = Modifier) {
    val parts = TitleWords.dateStamp(e)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (parts == null) { Text(TitleWords.TBA, style = DreamType.Row.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Dream.Text.copy(alpha = 0.88f))); return@Column }
        Text(parts.first, style = DreamType.Small.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp, color = Dream.TextDim))
        Spacer(Modifier.height(6.dp))
        Text(parts.second, style = DreamType.Title.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Dream.Text.copy(alpha = 0.92f)))
        parts.third?.let { Spacer(Modifier.height(6.dp)); Text(it, style = DreamType.Small.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Dream.TextDim)) }
    }
}

/**
 * An announced season with no episodes listed yet (CinemaDetails.seasonComingCard): one card, "New Season" over its
 * date ("March 4, 2027", "To Be Announced"), "Season 3" and "Coming March 4, 2027" under it. It can't play.
 */
@Composable
fun SeasonComingCard(s: SeasonInfo, width: Dp, onFocusOrHover: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val lit = src.lit()
    androidx.compose.runtime.LaunchedEffect(lit.any) { if (lit.any) onFocusOrHover() }
    val long = s.comingLong ?: TitleWords.TBA
    Column(Modifier.width(width)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .cardLight(src, scaleTo = 1.04f)
                .clip(RoundedCornerShape(Radii.Card))
                .background(Brush.linearGradient(listOf(Color(0xFF1C1C1F), Color(0xFF0E0E10))))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(Radii.Card))
                .dreamClick(src, onClick = onFocusOrHover),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("New Season", style = DreamType.Small.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp, color = Dream.TextDim))
                Spacer(Modifier.height(8.dp))
                Text(if (long == TitleWords.TBA) "To Be Announced" else long.removePrefix("Coming "), style = DreamType.Title.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Dream.Text.copy(alpha = 0.9f)), textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Season ${s.number}", style = DreamType.Row.copy(fontSize = 15.sp, color = Dream.Text.copy(alpha = 0.74f)), maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(long, style = DreamType.Small.copy(color = Dream.TextDim, fontWeight = FontWeight.Medium))
    }
}

fun initials(name: String): String = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

/**
 * An episode card: the 16:9 still with Up Next / Resume, progress along the foot and its pill; "3. Name" and the
 * sub line under it. Upcoming episodes are dimmed with their date stamp and can't be played.
 */
@Composable
fun EpisodeCard(
    e: EpisodeInfo,
    width: Dp,
    tag: String?,
    onPlay: () -> Unit,
    onFocusOrHover: () -> Unit,
    options: List<MenuItem>,
) {
    val src = remember { MutableInteractionSource() }
    val shownAt = remember { System.currentTimeMillis() }
    val lit = src.lit()
    androidx.compose.runtime.LaunchedEffect(lit.any) { if (lit.any) onFocusOrHover() }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.width(width)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .cardLight(src, scaleTo = 1.04f)
                .clip(RoundedCornerShape(Radii.Card))
                .background(Color(0xFF151518))
                .dreamClick(src, onSecondary = if (options.isEmpty() || e.upcoming) null else ({ menu = true }), onClick = if (e.upcoming) onFocusOrHover else onPlay),
        ) {
            com.dreamos.desktop.preload.ArtProbe("episode-still", e.stillUrl)
            if (e.upcoming) {
                // Still to come (CinemaDetails.upcomingCard): its own still dimmed, else a dark neutral face (never the
                // show's art), the date stamp in the middle and the red pill saying when.
                if (e.stillUrl != null) {
                    RemoteImage(e.stillUrl, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.59f)))
                } else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1C1C1F), Color(0xFF0E0E10)))))
                DateStamp(e, Modifier.align(Alignment.Center).padding(bottom = 14.dp))
                Box(Modifier.align(Alignment.BottomStart).padding(8.dp)) { Pill(TitleWords.comingPill(e), PillKind.Red) }
            } else if (e.stillUrl != null || e.fallbackArt.isNotEmpty()) RemoteImage(e.stillUrl, Modifier.fillMaxSize(), fallbacks = e.fallbackArt)
            else Text("E${e.number}", style = DreamType.Title.copy(color = Dream.TextFaint), modifier = Modifier.align(Alignment.Center))
            if (tag != null && !e.upcoming) {
                // Up Next / Resume: white words on the dark glass tag (CinemaDetails.tag).
                Text(
                    tag, style = DreamType.Pill.copy(color = Dream.Text),
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp).background(Color(0xB8050506), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
            if (!e.upcoming) {
                val pill = if (e.watched && e.progress <= 0f) com.dreamos.desktop.details.PillSpec("Watched", com.dreamos.desktop.settings.kit.PillKind.Watched) else e.pill
                com.dreamos.desktop.preload.PopProbe("episode-pill", pill != null)
                pill?.let { Box(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = if (e.progress > 0f) 16.dp else 8.dp)) { com.dreamos.desktop.preload.Arriving(shownAt) { Pill(it.text, it.kind, it.glyph) } } }
                if (e.progress > 0f && e.progress < TitleProgress.FINISHED) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))))
                    ProgressLine(e.progress, Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 8.dp))
                }
            }
            if (lit.hovered && !e.upcoming) {
                Box(Modifier.align(Alignment.Center).size(52.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape).border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape), contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.Play, Color.White, 24.dp, Modifier.padding(start = 3.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(TitleWords.episodeCardTitle(e), style = DreamType.Row.copy(fontSize = 15.sp, color = if (e.upcoming) Dream.Text.copy(alpha = 0.74f) else Dream.Text), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(TitleWords.episodeSub(e), style = DreamType.Small.copy(color = Dream.TextDim, fontWeight = FontWeight.Medium))
        if (menu) OptionsMenu(TitleWords.episodeName(e), options, onDismiss = { menu = false })
    }
}
