package com.dreamos.desktop.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset

/**
 * A card's options: its name, the actions in order, and where the right click was (null: from the keyboard).
 * [removes]: the labels of actions that take the card off the page (Remove from Continue Watching, Remove from My
 * List); focus moves to a neighbor before they run, never back to the card that is about to go.
 * [breakBefore]: the labels that get a thin line above them, setting them apart from the rest (Exit Dream OS).
 */
class MenuSpec(
    val title: String, val items: List<Pair<String, () -> Unit>>, val at: Offset?, val removes: Set<String> = emptySet(),
    val breakBefore: Set<String> = emptySet(),
) {
    companion object {
        /** A [breakBefore] line's height: the hairline and the room above and below it. */
        const val BREAK_H = 13
    }
}

class MenuState {
    var spec by mutableStateOf<MenuSpec?>(null)
    fun show(s: MenuSpec) { spec = s }

    /** Closes it; focus goes back to the card it was opened on (when that card is still on the page). */
    fun close() {
        spec = null
        val to = MenuReturn.to
        MenuReturn.to = null
        Focus.request(to, "menu: back to the card")
    }

    /**
     * Closes it for an action that removes the card: focus goes to the next card, else the one before, else the
     * page's root, and only then may the card leave. A removed card that still held focus left Compose's focus tree
     * with a parent pointing at nothing ("ActiveParent with no focused child"), and the next click crashed.
     */
    fun closeRemoving(fm: FocusManager) {
        spec = null
        val to = MenuReturn.to
        MenuReturn.to = null
        val moved = Focus.request(to, "menu: to the card before removing") &&
            (Focus.move(fm, FocusDirection.Right, "menu: next card") || Focus.move(fm, FocusDirection.Left, "menu: card before"))
        if (!moved) Focus.request(Focus.park, "menu: park focus")
    }
}

/**
 * The options menu over the whole window: opens at the pointer for a right click (kept inside the window) or centered
 * for the Menu key. The first action has focus; Up and Down move, Enter picks, Esc or a click outside closes. Focus
 * stays inside while it is open.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MenuLayer(state: MenuState) {
    val spec = state.spec ?: return
    val m = LocalMetrics.current
    val first = remember(spec) { FocusRequester() }
    val fm = LocalFocusManager.current
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Color(0x66000000))
            // A click outside closes it. A press on the menu reaches here too (a parent hears it after its children,
            // consumed or not): closing then took the menu away before the release, so a clicked action never ran.
            .onPointerEvent(PointerEventType.Press) { e -> if (e.changes.none { it.isConsumed }) state.close() }
            .onPreviewKeyEvent { k ->
                if (k.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (k.key) {
                    Key.Escape, Key.Backspace -> { state.close(); true }
                    Key.DirectionDown -> { Focus.move(fm, FocusDirection.Down, "menu: down"); true }
                    Key.DirectionUp -> { Focus.move(fm, FocusDirection.Up, "menu: up"); true }
                    Key.DirectionLeft, Key.DirectionRight -> true
                    else -> false
                }
            },
    ) {
        // Here, inside BoxWithConstraints: its content composes at layout time, so an effect outside it would ask
        // before the first row exists ("FocusRequester is not initialized") and the keyboard path had no focus.
        LaunchedEffect(spec) { Focus.request(first, "menu: first action") }
        val dens = LocalDensity.current
        val w = m.u(300)
        val h = m.u(64) + m.u(50) * spec.items.size + m.u(MenuSpec.BREAK_H) * spec.items.count { it.first in spec.breakBefore }
        val pos = spec.at?.let { at ->
            with(dens) {
                val x = at.x.toDp().coerceIn(m.u(12), maxWidth - w - m.u(12))
                val y = at.y.toDp().coerceIn(m.u(12), (maxHeight - h - m.u(12)).coerceAtLeast(m.u(12)))
                IntOffset(x.roundToPx(), y.roundToPx())
            }
        }
        Column(
            (if (pos == null) Modifier.align(Alignment.Center) else Modifier.offset { pos })
                .width(w)
                .clip(RoundedCornerShape(m.u(14)))
                .background(Color(0xF2141416))
                .border(m.u(1), Palette.hairline, RoundedCornerShape(m.u(14)))
                .onPointerEvent(PointerEventType.Press) { it.changes.forEach { c -> c.consume() } }
                // Focus stays inside while it is open; once it is closing, focus may leave for the button it came from.
                .focusProperties { exit = { if (state.spec != null) FocusRequester.Cancel else FocusRequester.Default } }
                .focusGroup()
                .padding(m.u(8)),
        ) {
            Text(spec.title, style = Type.kicker(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = m.u(14), end = m.u(14), top = m.u(10), bottom = m.u(10)))
            spec.items.forEachIndexed { i, (label, act) ->
                if (label in spec.breakBefore) {
                    Box(Modifier.fillMaxWidth().height(m.u(MenuSpec.BREAK_H)).padding(horizontal = m.u(14)), contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxWidth().height(m.u(1)).background(Palette.hairline))
                    }
                }
                MenuRow(label, if (i == 0) first else null) {
                    if (label in spec.removes) state.closeRemoving(fm) else state.close()
                    act()
                }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, requester: FocusRequester?, onClick: () -> Unit) {
    val m = LocalMetrics.current
    val src = remember { MutableInteractionSource() }
    val on = src.lit()
    val fill by animateColorAsState(if (on) Palette.pearl else Color.Transparent, tween(140))
    val ringA by animateFloatAsState(if (on) 1f else 0f, tween(160))
    val hue by rainbowWhile(ringA > 0f)
    Box(
        Modifier.fillMaxWidth()
            .padding(vertical = m.u(2))
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .ring(ringA, hue, m.u(9), m)
            .height(m.u(46))
            .clip(RoundedCornerShape(m.u(9)))
            .background(fill)
            .dreamClick(src, onClick)
            .padding(horizontal = m.u(14)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, style = Type.button().copy(color = if (on) Palette.ink else Palette.text), maxLines = 1)
    }
}

