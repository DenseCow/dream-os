package com.dreamos.desktop.ui.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.dreamos.desktop.shell.data.CardItem
import com.dreamos.desktop.shell.data.Row as DataRow
import com.dreamos.desktop.shell.data.RowStyle
import kotlinx.coroutines.launch
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.foundation.lazy.LazyListState

/**
 * Where keyboard focus scrolls a list to: a focused card stays at least [lead] px from the leading edge (the top
 * bar's bottom on pages, the gutter in rows) and [trail] px from the trailing edge, so it is never under the bar or
 * cut by the window's edge (STANDARDS section 3). Mouse scrolling is untouched.
 */
@OptIn(ExperimentalFoundationApi::class)
class KeepClear(private val lead: Float, private val trail: Float, reduced: Boolean) : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(if (reduced) 0 else 260)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = offset - lead
        val end = offset + size - (containerSize - trail)
        return when {
            size > containerSize - lead - trail -> start
            start < 0 -> start
            end > 0 -> end
            else -> 0f
        }
    }
}

/** A row's title: semibold, Title Case, on the gutter. */
@Composable
fun RowHeader(text: String, modifier: Modifier = Modifier) {
    val m = LocalMetrics.current
    Text(text, style = Type.rowTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = m.gutter, end = m.gutter, bottom = m.titleGap - m.liftRoom.coerceAtMost(m.titleGap)))
}

/**
 * A horizontal row of cards. The vertical wheel always scrolls the page, wherever the pointer is (owner, 2026-10-30:
 * people rest the pointer on rows while they read). A row scrolls sideways only on purpose: the wheel over one of
 * its edge arrows (one card a notch; at the row's end the notch goes to the page), a click on an edge arrow (the
 * cards in view less one), Shift+wheel, a tilt wheel or a touchpad's sideways swipe anywhere over the row, and the
 * keyboard's Left / Right (focus card by card, the row follows). The edge arrows are as tall as the art above the
 * pill band, on a soft dark fade, and show while the row is hovered and has more that way. The scroller keeps
 * [Metrics.liftRoom] on every side so a lit card is never clipped, and the first card starts on the gutter.
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun CardRow(
    title: String?,
    count: Int,
    cardH: Dp,
    prefetch: (Int) -> List<String?> = { emptyList() },
    onNearEnd: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val m = LocalMetrics.current
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val glide = remember(state) { RowGlide(state) }
    val rowFocus = remember { RowFocus() }
    rowFocus.trim(count)
    val nearEnd = androidx.compose.runtime.rememberUpdatedState(onNearEnd)
    val itemCount = androidx.compose.runtime.rememberUpdatedState(count)
    // Art for the cards just past the right edge, so scrolling never shows a blank card.
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            Images.prefetch((last + 1..last + 16).flatMap(prefetch))
            // A deep row fetches its next cards while six are still ahead.
            val more = nearEnd.value
            if (more != null && last >= itemCount.value - 6) more()
        }
    }
    // The mouse scrolled the keyboard's card out of view: focus goes to the first card in view (or the page's root),
    // so it never sits on a card the viewer can't see and the next arrow key starts where they are looking.
    fun refocus() {
        val f = rowFocus.focused
        if (f < 0) return
        val full = state.fullyVisible()
        if (f in full) return
        val to = full.firstOrNull()
        if (to == null || !Focus.request(rowFocus.requester(to), "row: first card in view")) Focus.request(Focus.park, "row: park focus")
    }
    fun scrollBy(px: Float) { if (px != 0f) scope.launch { glide.add(px); refocus() } }
    val dens = LocalDensity.current
    val clear = remember(m, dens) { with(dens) { KeepClear(m.gutter.toPx(), m.gutter.toPx(), false) } }
    val gap = with(dens) { m.cardGap.toPx() }
    // The arrow zones: the art's height above the pill band (pills and badges sit on the art's bottom and top-left
    // corners; captions under it), the gutter's width at each edge.
    val zoneTop = m.liftRoom
    val zoneH = (cardH - m.u(44)).coerceAtLeast(m.u(56))
    val zoneW = m.gutter
    Column(Modifier.fillMaxWidth()) {
        if (title != null) RowHeader(title)
        Box(
            Modifier.fillMaxWidth().hoverable(hover)
                // Initial pass: the row decides before the page's list (or LazyRow's own wheel) sees the notch.
                .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { e ->
                    val c = e.changes.firstOrNull() ?: return@onPointerEvent
                    if (c.isConsumed) return@onPointerEvent
                    val d = c.scrollDelta
                    // Sideways input always belongs to the row (Shift+wheel, a tilt wheel, a touchpad swipe).
                    val sideways = if (d.x != 0f) d.x else if (e.keyboardModifiers.isShiftPressed) d.y else 0f
                    val onArrow = with(dens) {
                        val p = c.position
                        p.y >= zoneTop.toPx() && p.y <= (zoneTop + zoneH).toPx() &&
                            (p.x <= zoneW.toPx() || p.x >= size.width - zoneW.toPx())
                    }
                    val delta = when {
                        sideways != 0f -> sideways
                        // The vertical wheel over an edge arrow: down goes forward, up goes back; at the row's end
                        // the notch is left to the page.
                        onArrow && d.y != 0f && (if (d.y > 0) state.canScrollForward else state.canScrollBackward) -> d.y
                        else -> return@onPointerEvent
                    }
                    c.consume()
                    // About one card a notch (a touchpad's small steps add up to the same).
                    scrollBy(delta * state.cardStride(gap))
                },
        ) {
          CompositionLocalProvider(LocalBringIntoViewSpec provides clear, LocalRowFocus provides rowFocus) {
            LazyRow(
                state = state,
                contentPadding = PaddingValues(start = m.gutter, end = m.gutter, top = m.liftRoom, bottom = m.liftRoom),
                horizontalArrangement = Arrangement.spacedBy(m.cardGap),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
                content = content,
            )
          }
            // A page is the cards in view less one, so the last card in view becomes the first and the viewer keeps
            // their place.
            val showL = hovered && !InputMode.keyboard && state.canScrollBackward
            val showR = hovered && !InputMode.keyboard && state.canScrollForward && count > 1
            EdgeArrow(showL, left = true, Modifier.align(Alignment.TopStart).padding(top = zoneTop).size(zoneW, zoneH)) {
                scrollBy(-state.pageDistance(gap, forward = false))
            }
            EdgeArrow(showR, left = false, Modifier.align(Alignment.TopEnd).padding(top = zoneTop).size(zoneW, zoneH)) {
                scrollBy(state.pageDistance(gap, forward = true))
            }
        }
    }
}

/** The indexes of the cards wholly in view (from the gutter to the far edge). */
private fun LazyListState.fullyVisible(): List<Int> {
    val info = layoutInfo
    val end = info.viewportEndOffset - info.afterContentPadding
    return info.visibleItemsInfo.filter { it.offset >= -1 && it.offset + it.size <= end + 1 }.map { it.index }
}

/** One card and its gap, in px (the row's first visible card). */
private fun LazyListState.cardStride(gap: Float): Float =
    (layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 300).toFloat() + gap

/** How far the arrows page: the cards in view less one, landing a card on the gutter. */
private fun LazyListState.pageDistance(gap: Float, forward: Boolean): Float {
    val first = layoutInfo.visibleItemsInfo.firstOrNull { it.offset >= -1 } ?: return 0f
    val stride = first.size + gap
    val n = (fullyVisible().size - 1).coerceAtLeast(1)
    // first.offset: how far the first whole card sits past the gutter now (0 when aligned).
    return if (forward) first.offset + n * stride else n * stride - first.offset
}

/**
 * A row's edge arrow: the whole zone (the gutter's width, the art's height above the pill band) is the target, on a
 * soft fade from the page's black at the edge to clear inside, with the round arrow in its middle. Hovering the zone
 * lights the arrow (pearl and ring). The zone also takes the vertical wheel (CardRow). Never focusable: a mouse
 * click would otherwise move focus onto an arrow that hides itself at the row's end, and a focused control that goes
 * away corrupted the focus tree. Keyboard users move card by card instead.
 */
@Composable
private fun EdgeArrow(show: Boolean, left: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val m = LocalMetrics.current
    AnimatedVisibility(show, modifier.focusProperties { canFocus = false }, enter = fadeIn(tween(140)), exit = fadeOut(tween(180))) {
        val src = remember { MutableInteractionSource() }
        val zoneHover by src.collectIsHoveredAsState()
        val edge = Palette.page.copy(alpha = 0.78f)
        Box(
            Modifier.fillMaxSize()
                .background(Brush.horizontalGradient(if (left) listOf(edge, Color.Transparent) else listOf(Color.Transparent, edge)))
                .semantics { contentDescription = if (left) "Scroll Left" else "Scroll Right" }
                .hoverable(src)
                .focusProperties { canFocus = false }
                .dreamClick(src, onClick),
            contentAlignment = Alignment.Center,
        ) {
            val fill by animateColorAsState(if (zoneHover) Palette.pearl else Color(0xE6161618), tween(160))
            val ink by animateColorAsState(if (zoneHover) Palette.ink else Palette.text, tween(160))
            val ringA by animateFloatAsState(if (zoneHover) 1f else 0f, tween(180))
            val hue by rainbowWhile(ringA > 0f)
            Box(
                Modifier.ring(ringA, hue, m.u(20), m).size(m.u(40)).clip(CircleShape).background(fill),
                contentAlignment = Alignment.Center,
            ) { LineIcon(if (left) Icon.LEFT else Icon.RIGHT, ink, Modifier.size(m.u(19))) }
        }
    }
}

/** Card heights by style at the current window size (the arrows center on the art). */
@Composable
fun artHeight(style: RowStyle): Dp {
    val m = LocalMetrics.current
    return when (style) {
        RowStyle.BIG -> m.bigW * 9f / 16f
        RowStyle.POSTER -> m.posterW * 1.5f
        RowStyle.TOP10 -> m.rankPosterW * 1.5f
        RowStyle.SPOTLIGHT -> m.u(360)
        else -> m.landW * 9f / 16f
    }
}

/** Draws a loaded [row] in its style. [meta] gives a big card's meta line; [logo] a spotlight's logo. */
@Composable
fun DataRowView(
    first: DataRow,
    meta: (CardItem) -> String,
    logo: suspend (com.dreamos.desktop.shell.data.Title) -> String?,
) {
    val m = LocalMetrics.current
    // A deep row grows as the viewer nears its end (core's next page, one at a time, until it has no more).
    var row by remember(first) { mutableStateOf(first) }
    var paging by remember(first) { mutableStateOf(false) }
    var done by remember(first) { mutableStateOf(first.more == null) }
    val scope = rememberCoroutineScope()
    val nearEnd: (() -> Unit)? = if (done) null else ({
        if (!paging) {
            paging = true
            scope.launch {
                val next = runCatching { first.more!!() }.getOrDefault(emptyList())
                val seen = row.items.map { it.title.key }.toHashSet()
                val fresh = next.filter { it.title.key !in seen }
                if (fresh.isEmpty()) done = true else row = row.copy(items = row.items + fresh)
                paging = false
            }
        }
    })
    val dens = LocalDensity.current
    fun px(d: Dp) = with(dens) { d.toPx() }
    // Keys by title, so a card that leaves (Remove from Continue Watching) takes only itself out and every other card
    // keeps its node and its focus; a title listed twice gets a numbered key.
    // One snapshot of the cards for keys and content alike: LazyRow may run an older content lambda after the row
    // grew, and a key list from one composition with a count from another went out of bounds.
    val cards = row.items
    val keys = remember(cards) { stableKeys(cards) }
    when (row.style) {
        RowStyle.SPOTLIGHT -> {
            val item = row.items.first()
            Column(Modifier.padding(horizontal = m.gutter, vertical = m.liftRoom)) {
                SpotlightCard(item, (m.widthDp.toInt() - 2 * m.gutter.value.toInt()).let { androidx.compose.ui.unit.Dp(it.toFloat()) },
                    // The card's own module line ("Tonight's Pick  ·  Night 30 of 31", "Coming Soon"), else the row's.
                    kicker = item.kicker ?: row.name.takeIf { it != "Spotlight" } ?: "Spotlight", meta = meta(item), logo = logo)
            }
        }
        RowStyle.CONTINUE -> CardRow(row.name, row.items.size, artHeight(row.style), { i -> listOf(row.items.getOrNull(i)?.let { cardChoice(it, row.style, px(m.landW)).urls.firstOrNull() }) }, onNearEnd = nearEnd) {
            items(cards.size, key = { keys[it] }) { Box(rowSlot(it)) { ContinueCard(cards[it], m.landW) } }
        }
        RowStyle.BIG -> CardRow(row.name, row.items.size, artHeight(row.style), { i -> listOf(row.items.getOrNull(i)?.let { cardChoice(it, row.style, px(m.bigW)).urls.firstOrNull() }) }, onNearEnd = nearEnd) {
            items(cards.size, key = { keys[it] }) { Box(rowSlot(it)) { BigCard(cards[it], m.bigW, meta(cards[it])) } }
        }
        RowStyle.POSTER -> CardRow(row.name, row.items.size, artHeight(row.style), { i -> listOf(row.items.getOrNull(i)?.let { Tmdb.poster(it.title.posterPath, px(m.posterW)) }) }, onNearEnd = nearEnd) {
            items(cards.size, key = { keys[it] }) { Box(rowSlot(it)) { PosterCard(cards[it], m.posterW) } }
        }
        RowStyle.TOP10 -> CardRow(row.name, row.items.size, artHeight(row.style), { i -> listOf(row.items.getOrNull(i)?.let { Tmdb.poster(it.title.posterPath, px(m.rankPosterW)) }) }, onNearEnd = nearEnd) {
            items(cards.size, key = { keys[it] }) { Box(rowSlot(it)) { RankedCard(cards[it], it + 1, m.rankPosterW) } }
        }
        RowStyle.LANDSCAPE -> CardRow(row.name, row.items.size + row.hubs.size, artHeight(row.style), { i -> listOf(row.items.getOrNull(i)?.let { cardChoice(it, row.style, px(m.landW)).urls.firstOrNull() }) }, onNearEnd = nearEnd) {
            items(row.hubs.size, key = { "hub:" + row.hubs[it].id }) { Box(rowSlot(it)) { HubTileCard(row.hubs[it], m.landW) } }
            items(cards.size, key = { keys[it] }) { Box(rowSlot(row.hubs.size + it)) { LandscapeCard(cards[it], m.landW) } }
        }
    }
}

/** One key per card from its title's key; a repeat gets "#2", "#3" so LazyRow never sees a key twice. */
fun stableKeys(items: List<CardItem>): List<String> {
    val seen = HashMap<String, Int>()
    return items.map { c ->
        val n = (seen[c.title.key] ?: 0) + 1
        seen[c.title.key] = n
        if (n == 1) c.title.key else "${c.title.key}#$n"
    }
}

/** The first art of a loaded row, warmed before the row appears so it shows complete (the art its cards draw). */
fun firstArt(row: DataRow, m: Metrics, density: Float, count: Int = 12): List<String?> = cardArt(row, m, density, count)

/**
 * A row while it loads: its title and quiet card shapes, so the page keeps its layout and nothing jumps when the
 * cards arrive. Still (no shimmer): STANDARDS, nothing animates on an idle screen.
 */
@Composable
fun SkeletonRow(title: String?, style: RowStyle) {
    val m = LocalMetrics.current
    val w = when (style) { RowStyle.BIG -> m.bigW; RowStyle.POSTER -> m.posterW; RowStyle.TOP10 -> m.rankPosterW; else -> m.landW }
    val h = artHeight(style)
    Column(Modifier.fillMaxWidth()) {
        if (title != null) RowHeader(title, Modifier.alpha(0.55f))
        Row(Modifier.padding(start = m.gutter, top = m.liftRoom, bottom = m.liftRoom), horizontalArrangement = Arrangement.spacedBy(m.cardGap)) {
            repeat(8) {
                Column {
                    if (style == RowStyle.TOP10) Spacer(Modifier.width(m.rankSlot))
                    Box(Modifier.size(w, h).clip(RoundedCornerShape(m.radius)).background(Brush.verticalGradient(listOf(Palette.surfaceHigh, Palette.surface))))
                    Spacer(Modifier.height(m.u(10)))
                    Box(Modifier.size(w * 0.6f, m.u(11)).clip(RoundedCornerShape(50)).background(Palette.surfaceHigh))
                }
            }
        }
    }
}

/** A row that couldn't load: says so in a sentence, with a way to try again. */
@Composable
fun RowError(title: String, retry: () -> Unit) {
    val m = LocalMetrics.current
    Column(Modifier.fillMaxWidth().padding(horizontal = m.gutter, vertical = m.u(8))) {
        Text(title, style = Type.rowTitle(), maxLines = 1)
        Spacer(Modifier.height(m.u(10)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Couldn't load this row. Check your connection and try again.", style = Type.body())
            Spacer(Modifier.width(m.u(16)))
            PearlButton("Try Again", retry)
        }
    }
}
