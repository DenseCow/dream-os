package com.dreamos.desktop.ui.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.dreamos.desktop.shell.data.HeroItem
import com.dreamos.desktop.shell.data.Title
import kotlinx.coroutines.delay

/** What the hero's buttons do; the shell wires them to Nav. */
class HeroActions(val play: (Title) -> Unit, val info: (Title) -> Unit, val toggleList: (Title) -> Unit, val inList: (Title) -> Boolean)

/**
 * The full-bleed hero: the title's backdrop edge to edge, fading into the page at the bottom and the left; over it the
 * kicker, the title logo (or name), a meta line, two lines of synopsis, Play, More Info and My List. It moves to the
 * next title every [ADVANCE_MS] while the viewer isn't pointing at or focused on it (pointing pauses it, the
 * control for motion that lasts); arrows on hover and the page dots step by hand. [overlap] lets the first row tuck
 * up into the faded bottom.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun Hero(
    items: List<HeroItem>, height: Dp, overlap: Dp, actions: HeroActions, reducedMotion: Boolean, meta: (HeroItem) -> String,
    entry: androidx.compose.ui.focus.FocusRequester? = null,
    /** Drawn after the meta line on the same line (the slide's kept scores); read once as the slide comes in. */
    afterMeta: @Composable (HeroItem) -> Unit = {},
) {
    val m = LocalMetrics.current
    var index by remember(items) { mutableIntStateOf(0) }
    // Pointing at the hero pauses it and shows its arrow; the top bar's band is the bar's, not the hero's. With plain
    // hover the gaps between the bar's tabs counted as the hero, so moving along the tabs blinked the arrow and
    // paused and restarted the slides (the owner's flicker while hovering Home and Browse).
    var hovered by remember { androidx.compose.runtime.mutableStateOf(false) }
    var heroTop by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val barPx = with(LocalDensity.current) { m.barH.toPx() }
    var focusInside by remember { androidx.compose.runtime.mutableStateOf(false) }
    val dens = LocalDensity.current
    val wPx = with(dens) { m.widthDp * density }
    val logoPx = with(dens) { m.u(440).toPx() }

    LaunchedEffect(items, index, hovered, focusInside, reducedMotion) {
        if (items.size < 2 || hovered || focusInside || reducedMotion) return@LaunchedEffect
        // The next slide's backdrop and logo, so it crossfades in whole.
        val next = items[(index + 1) % items.size]
        val art = listOf(Tmdb.backdrop(next.title.backdropPath, wPx), Tmdb.logo(next.logoPath, logoPx))
        Images.prefetch(art)
        delay(ADVANCE_MS)
        // The advance waits until that art is decoded (bounded; nearly always already in), never showing a half slide.
        Images.warm(art, NEXT_SLIDE_WAIT_MS)
        index = (index + 1) % items.size
    }
    LaunchedEffect(items) { Images.prefetch(items.flatMap { listOf(Tmdb.backdrop(it.title.backdropPath, wPx), Tmdb.logo(it.logoPath, logoPx)) }) }
    // The slide on screen for a moment: its title page's trailer is readied, for More Info (background lane).
    val shown = items.getOrNull(index)?.title
    LaunchedEffect(shown?.id, shown?.isShow) {
        val t = shown ?: return@LaunchedEffect
        delay(HERO_TRAILER_AHEAD_MS)
        com.dreamos.desktop.trailer.TrailerAhead.now(t.id, t.isShow, t.name)
    }

    Box(
        Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val h = height.roundToPx()
                val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                layout(p.width, h - overlap.roundToPx()) { p.place(0, 0) }
            }
            .onGloballyPositioned { heroTop = it.positionInWindow().y }
            .onPointerEvent(PointerEventType.Enter) { e -> hovered = below(e, heroTop, barPx) }
            .onPointerEvent(PointerEventType.Move) { e -> hovered = below(e, heroTop, barPx) }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .onFocusChanged { focusInside = it.hasFocus },
    ) {
        val item = items.getOrNull(index) ?: return@Box
        Crossfade(item, animationSpec = if (reducedMotion) snap() else tween(700), label = "hero") { it2 ->
            com.dreamos.desktop.preload.ArtProbe("hero-backdrop", Tmdb.backdrop(it2.title.backdropPath, wPx))
            DreamImage(listOf(Tmdb.backdrop(it2.title.backdropPath, wPx)), Modifier.fillMaxSize(), alignment = Alignment.TopCenter)
        }
        // Scrims: the left third for the text, the bottom melting into the page, a light veil under the bar.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xEB000000), 0.46f to Color(0xB3000000), 0.76f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x8C000000), 0.16f to Color.Transparent, 0.58f to Color.Transparent, 0.86f to Color(0xD9000000), 1f to Palette.page)))

        // The logo gives way when the hero is short, so the text never reaches up under the bar.
        val room = height - overlap - m.u(40) - m.barH - m.u(20) - m.u(190)
        val logoH = room.coerceIn(m.u(48), m.u(130))
        val colW = minOf(m.u(560), (m.widthDp * 0.46f).let { Dp(it) })
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val synStyle = Type.synopsis().copy(color = Palette.overArt)
        val longStyle = synStyle.copy(fontSize = synStyle.fontSize * LONG_SYNOPSIS, lineHeight = synStyle.lineHeight * LONG_SYNOPSIS)
        Column(
            Modifier.align(Alignment.BottomStart)
                .padding(start = m.gutter, bottom = overlap + m.u(40))
                .width(colW),
        ) {
            Crossfade(item, animationSpec = if (reducedMotion) snap() else tween(500), label = "heroText") { h ->
                val colPx = with(dens) { colW.roundToPx() }
                val syn = remember(h.title.key, colPx, synStyle) { heroSynopsis(h.title.overview, measurer, synStyle, longStyle, colPx) }
                val twoH = with(dens) { (synStyle.lineHeight.toPx() * 2).toDp() }
                val synH = if (syn.long) with(dens) { (longStyle.lineHeight.toPx() * 3).toDp() } else twoH
                Column {
                    Text(h.kicker, style = Type.kicker().copy(color = Palette.overArt), maxLines = 1)
                    Spacer(Modifier.height(m.u(12)))
                    // A three-line synopsis takes its extra height from the logo box: nothing above moves toward the bar.
                    TitleLogo(h.title, h.logoPath, m.u(440), (logoH - (synH - twoH)).coerceAtLeast(m.u(40)), Type.heroTitle())
                    Spacer(Modifier.height(m.u(16)))
                    // Two genres, then one, then none: the scores after the line are never squeezed or cut (the Fire Stick's).
                    var drop by remember(h.title.key) { mutableIntStateOf(0) }
                    val parts = remember(h.title.key) { meta(h).split(DOT) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(parts.dropLast(drop).joinToString(DOT), style = Type.meta().copy(color = Palette.overArt), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                            onTextLayout = { if (it.hasVisualOverflow && drop < 2 && parts.size - drop > 2) drop++ })
                        afterMeta(h)
                    }
                    Spacer(Modifier.height(m.u(10)))
                    Text(syn.text, style = if (syn.long) longStyle else synStyle, maxLines = if (syn.long) 3 else 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.height(synH))
                }
            }
            Spacer(Modifier.height(m.u(24)))
            ButtonRow {
                PlayButton("Play", { actions.play(item.title) }, requester = entry)
                PearlButton("More Info", { actions.info(item.title) }, icon = Icon.INFO)
                val saved = actions.inList(item.title)
                RoundButton(if (saved) Icon.CHECK else Icon.PLUS, if (saved) "Remove from My List" else "Add to My List", { actions.toggleList(item.title) }, size = 48f)
            }
        }

        if (items.size > 1) {
            val showArrows = hovered && !InputMode.keyboard
            AnimatedVisibility(showArrows, Modifier.align(Alignment.CenterEnd).padding(end = m.u(20), bottom = overlap), enter = fadeIn(tween(140)), exit = fadeOut(tween(180))) {
                RoundButton(Icon.RIGHT, "Next Title", { index = (index + 1) % items.size }, size = 48f)
            }
            Dots(items.size, index, Modifier.align(Alignment.BottomEnd).padding(end = m.gutter, bottom = overlap + m.u(48))) { index = it }
        }
    }
}

/** The pointer is on the hero below the top bar's band ([top]: the hero's top in the window). */
private fun below(e: PointerEvent, top: Float, barPx: Float): Boolean =
    e.changes.firstOrNull()?.let { top + it.position.y >= barPx } ?: false

/** Page dots: the current one a longer pearl bar. Each dot is a real target (its click area is larger than the bar). */
@Composable
private fun Dots(count: Int, current: Int, modifier: Modifier, pick: (Int) -> Unit) {
    val m = LocalMetrics.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(m.u(2)), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val src = remember { MutableInteractionSource() }
            val on = src.lit()
            val w by animateDpAsState(if (i == current) m.u(22) else m.u(7), tween(240))
            val c by animateColorAsState(if (i == current || on) Palette.pearl else Color(0x73FFFFFF), tween(160))
            Box(
                Modifier.semantics { contentDescription = "Title ${i + 1} of $count" }
                    .size(m.u(30), m.u(28)).dreamClick(src, { pick(i) }),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(w, m.u(7)).clip(RoundedCornerShape(50)).background(c))
            }
        }
    }
}

/** The hero while its titles load: the same frame, dark, so the page doesn't jump when they arrive. */
@Composable
fun HeroSkeleton(height: Dp, overlap: Dp) {
    val m = LocalMetrics.current
    Box(
        Modifier.fillMaxWidth()
            .layout { measurable, constraints ->
                val h = height.roundToPx()
                val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                layout(p.width, h - overlap.roundToPx()) { p.place(0, 0) }
            }
            .background(Brush.verticalGradient(listOf(Color(0xFF0E0E10), Palette.page))),
    ) {
        Column(Modifier.align(Alignment.BottomStart).padding(start = m.gutter, bottom = overlap + m.u(40))) {
            Box(Modifier.size(m.u(120), m.u(12)).clip(RoundedCornerShape(50)).background(Palette.surfaceHigh))
            Spacer(Modifier.height(m.u(18)))
            Box(Modifier.size(m.u(360), m.u(56)).clip(RoundedCornerShape(m.radius)).background(Palette.surfaceHigh))
            Spacer(Modifier.height(m.u(18)))
            Box(Modifier.size(m.u(300), m.u(12)).clip(RoundedCornerShape(50)).background(Palette.surface))
        }
    }
}

/** The hero's synopsis as shown: [long] when it is a size smaller on up to three lines. */
class HeroSynopsis(val text: String, val long: Boolean)

/** A synopsis too long for two lines in its first sentence shows that sentence this much smaller (the Fire Stick's 13.5 / 15). */
private const val LONG_SYNOPSIS = 0.9f
private val SENTENCE_END = Regex("[.!?…][\"”’)]*(?=\\s)")

/**
 * Whole sentences only, from the first word (the Fire Stick's hero): as many as fit two lines; when even the first
 * doesn't, that sentence a size smaller on up to three lines. Never cut mid-sentence unless a first sentence outruns
 * even that. The title page has it all.
 */
fun heroSynopsis(
    overview: String, measurer: androidx.compose.ui.text.TextMeasurer,
    style: androidx.compose.ui.text.TextStyle, longStyle: androidx.compose.ui.text.TextStyle, widthPx: Int,
): HeroSynopsis {
    val text = overview.trim()
    if (text.isEmpty() || widthPx <= 0) return HeroSynopsis(text, false)
    fun lines(s: String, st: androidx.compose.ui.text.TextStyle) =
        measurer.measure(s, st, constraints = androidx.compose.ui.unit.Constraints(maxWidth = widthPx)).lineCount
    fun fit(st: androidx.compose.ui.text.TextStyle, max: Int): String? {
        if (lines(text, st) <= max) return text
        var best: String? = null
        for (m in SENTENCE_END.findAll(text)) {
            val s = text.substring(0, m.range.last + 1)
            if (lines(s, st) <= max) best = s else break
        }
        return best
    }
    fit(style, 2)?.let { return HeroSynopsis(it, false) }
    return HeroSynopsis(fit(longStyle, 3) ?: text, true)
}

const val ADVANCE_MS = 8_000L
/** How long a hero slide is on screen before its title's trailer is readied (a quick click-through readies none). */
private const val HERO_TRAILER_AHEAD_MS = 1_500L

/** The longest an advance waits for the next slide's art before it moves on anyway. */
const val NEXT_SLIDE_WAIT_MS = 2_000L
