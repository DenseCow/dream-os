package com.dreamos.desktop.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * One choice in a [PickerButton]'s panel (the iPhone's DreamMenuItem). [glyph]: a drawn genre glyph ([GenreGlyph]
 * name) before the label in [tint]; when one item has a glyph every item gets one, so the labels line up.
 */
class PickerItem(val id: String, val label: String, val glyph: String? = null, val tint: Color = Color.White)

/** Where each picker button (by heading) and open row (by label) sits in the window, for the offscreen harness (Snap). */
object PickerSpots { val at = HashMap<String, Rect>() }

/**
 * Dream's own picker (the iPhone's DreamPickerButton): a glass capsule naming the current choice with a chevron.
 * A click, Enter or Space opens its panel right under it (above it when the window has more room there): the
 * [heading], then one row per choice, the current one marked with a drawn check. Up and Down move, Enter picks,
 * Esc or a click outside closes, and focus comes back to the button.
 */
@Composable
fun PickerButton(
    heading: String,
    items: List<PickerItem>,
    selected: String?,
    hint: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val m = LocalMetrics.current
    val src = remember { MutableInteractionSource() }
    val self = remember { FocusRequester() }
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val on = src.lit() || open
    val fill by animateColorAsState(if (on) Palette.pearl else Color(0x1AFFFFFF), tween(160))
    val ink by animateColorAsState(if (on) Palette.ink else Palette.text, tween(160))
    val ringA by animateFloatAsState(if (on) 1f else 0f, tween(180))
    val hue by rainbowWhile(ringA > 0f)
    val text = items.firstOrNull { it.id == selected }?.label ?: heading
    val shape = RoundedCornerShape(m.u(20))

    fun close() {
        open = false
        Focus.request(self, "picker: back to $heading")
    }

    Box(modifier) {
        Row(
            Modifier
                .semantics(mergeDescendants = true) {
                    role = Role.DropdownList
                    contentDescription = "$heading: $text"
                    stateDescription = hint
                }
                .focusRequester(self)
                .onGloballyPositioned { anchor = it.boundsInWindow(); PickerSpots.at[heading] = anchor }
                .onPreviewKeyEvent { k ->
                    // Space opens it too (Enter is the click's own key).
                    if (k.key != Key.Spacebar) return@onPreviewKeyEvent false
                    if (k.type == KeyEventType.KeyDown && !open) open = true
                    true
                }
                .ring(ringA, hue, m.u(20), m)
                .height(m.u(40))
                .clip(shape)
                .background(fill)
                .border(m.u(1), if (on) Color.Transparent else Color(0x24FFFFFF), shape)
                .dreamClick(src, { open = true })
                .padding(start = m.u(16), end = m.u(14)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = Type.button().copy(color = ink), maxLines = 1)
            Spacer(Modifier.width(m.u(10)))
            LineIcon(Icon.DOWN, ink, Modifier.size(m.u(14)), stroke = 2.4f)
        }
        if (open) PickerPanel(heading, items, selected, anchor, onPick = { id -> close(); if (id != selected) onPick(id) }, onClose = ::close)
    }
}

/** The panel under (or over) the button, kept inside the window; a list taller than the room scrolls. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PickerPanel(
    title: String,
    items: List<PickerItem>,
    selected: String?,
    anchor: Rect,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    val m = LocalMetrics.current
    val dens = LocalDensity.current
    val edge = m.u(12)
    val gap = m.u(8)
    // Room above and below the button, in the window's own size (Metrics is the window).
    val top = with(dens) { anchor.top.toDp() }
    val bottom = with(dens) { anchor.bottom.toDp() }
    val below = m.heightDp - bottom.value - gap.value - edge.value
    val above = top.value - gap.value - edge.value
    val rowH = m.u(46).value + m.u(4).value
    val need = m.u(52).value + rowH * items.size + m.u(16).value
    val down = below >= need || below >= above
    val maxH = (if (down) below else above).dp.coerceAtLeast(m.u(160))
    val w = m.u(300).coerceAtLeast(with(dens) { anchor.width.toDp() }).coerceAtMost(m.u(360))
    val gapPx = with(dens) { gap.roundToPx() }
    val edgePx = with(dens) { edge.roundToPx() }
    val place = remember(down, gapPx, edgePx) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = anchorBounds.left.coerceIn(edgePx, (windowSize.width - popupContentSize.width - edgePx).coerceAtLeast(edgePx))
                val y = if (down) anchorBounds.bottom + gapPx else anchorBounds.top - gapPx - popupContentSize.height
                return IntOffset(x, y.coerceIn(edgePx, (windowSize.height - popupContentSize.height - edgePx).coerceAtLeast(edgePx)))
            }
        }
    }
    val reduced = LocalReducedMotion.current
    Popup(
        popupPositionProvider = place,
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = true),
    ) {
        val fm = LocalFocusManager.current
        val current = remember { FocusRequester() }
        val fade = remember { Animatable(if (reduced) 1f else 0f) }
        DisposableEffect(Unit) { onDispose { PickerSpots.at.keys.removeAll { it.startsWith("row:") } } }
        LaunchedEffect(Unit) {
            Focus.request(current, "picker: current choice")
            if (!reduced) fade.animateTo(1f, tween(120))
        }
        val shape = RoundedCornerShape(m.u(14))
        Column(
            Modifier
                .graphicsLayer { alpha = fade.value; translationY = (1f - fade.value) * (if (down) -6f else 6f) * density }
                .width(w)
                .heightIn(max = maxH)
                // The one raised surface on the page: it sits over the poster wall, so it carries the shadow.
                .shadow(m.u(18), shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(PANEL)
                .border(m.u(1), Palette.hairline, shape)
                .onPointerEvent(PointerEventType.Move) { InputMode.keyboard = false }
                .onPreviewKeyEvent { k ->
                    if (k.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    InputMode.keyboard = true
                    when (k.key) {
                        Key.Escape, Key.Backspace -> { onClose(); true }
                        Key.DirectionDown -> { Focus.move(fm, FocusDirection.Down, "picker: down"); true }
                        Key.DirectionUp -> { Focus.move(fm, FocusDirection.Up, "picker: up"); true }
                        Key.DirectionLeft, Key.DirectionRight -> true
                        else -> false
                    }
                }
                .focusGroup(),
        ) {
            Text(
                title,
                style = Type.style(15f, androidx.compose.ui.text.font.FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }.padding(start = m.u(22), end = m.u(22), top = m.u(18), bottom = m.u(8)),
            )
            val list = rememberScrollState()
            val melt = m.u(22)
            Column(
                Modifier
                    // A list longer than the room melts into the panel at whichever end has more (the iPhone's soft edges).
                    .drawWithContent {
                        drawContent()
                        val f = melt.toPx()
                        if (list.canScrollBackward) drawRect(Brush.verticalGradient(listOf(PANEL, Color.Transparent), 0f, f), size = Size(size.width, f))
                        if (list.canScrollForward) drawRect(Brush.verticalGradient(listOf(Color.Transparent, PANEL), size.height - f, size.height),
                            topLeft = Offset(0f, size.height - f), size = Size(size.width, f))
                    }
                    .verticalScroll(list)
                    .padding(start = m.u(8), end = m.u(8), bottom = m.u(8)),
            ) {
                items.forEach { item ->
                    PickerRow(item, item.id == selected, if (item.id == selected || (selected == null && item === items.first())) current else null) { onPick(item.id) }
                }
            }
        }
    }
}

/** The panel's surface (also the color its list melts into at the edges). */
private val PANEL = Color(0xFA141416)

/** A choice: plain at rest, a drawn check on the current one; lit (hovered or focused), the pearl box and ring. */
@Composable
private fun PickerRow(item: PickerItem, current: Boolean, requester: FocusRequester?, onClick: () -> Unit) {
    val m = LocalMetrics.current
    val src = remember { MutableInteractionSource() }
    val on = src.lit()
    val fill by animateColorAsState(if (on) Palette.pearl else if (current) Color(0x17FFFFFF) else Color.Transparent, tween(140))
    val ink = if (on) Palette.ink else Palette.text
    val ringA by animateFloatAsState(if (on) 1f else 0f, tween(160))
    val hue by rainbowWhile(ringA > 0f)
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = m.u(2))
            .semantics(mergeDescendants = true) { selected = current }
            .onGloballyPositioned { PickerSpots.at["row:" + item.label] = it.boundsInWindow() }
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .ring(ringA, hue, m.u(9), m)
            .height(m.u(46))
            .clip(RoundedCornerShape(m.u(9)))
            .background(fill)
            .dreamClick(src, onClick)
            .padding(start = m.u(if (item.glyph != null) 10 else 14), end = m.u(14)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.glyph != null) {
            GenreGlyph(item.glyph, item.tint, Modifier.size(m.u(30)))
            Spacer(Modifier.width(m.u(12)))
        }
        Text(
            item.label,
            style = Type.style(15f, if (current) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium, color = ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (current) {
            Spacer(Modifier.width(m.u(12)))
            LineIcon(Icon.CHECK, ink, Modifier.size(m.u(18)), stroke = 2.4f)
        }
    }
}
