package com.dreamos.desktop.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp

/**
 * Which input the viewer is using right now. A key press switches to keyboard (the focus ring shows where focus is);
 * moving the mouse switches back (the ring follows the pointer instead). One highlight at a time either way, the
 * desktop version of the web's focus-visible.
 */
object InputMode {
    var keyboard by mutableStateOf(false)
}

/** Opens a card's options (the Fire Stick's Options): [at] is the point in the window, or null from the keyboard. */
fun interface MenuOpener { fun open(at: Offset?) }

/** The control whose options menu is open: focus goes back to it when the menu closes. */
object MenuReturn { var to: FocusRequester? = null }

/**
 * The rainbow's current hue from the one shared clock ([ShellRainbow]), counted toward it only while [active];
 * otherwise a still hue that never recomposes its reader (nothing animates on an idle screen).
 */
@Composable
fun rainbowWhile(active: Boolean): State<Color> {
    shellHue(active)
    return if (active) ShellRainbow.hue else remember(active) { mutableStateOf(com.dreamos.player.ui.RainbowClock.peek()) }
}

/** Lit: hovered while the mouse is in use, or focused while the keyboard is. */
@Composable
fun MutableInteractionSource.lit(): Boolean {
    val hovered by collectIsHoveredAsState()
    val focused by collectIsFocusedAsState()
    return if (InputMode.keyboard) focused else hovered
}

/**
 * Every card's interaction: hover and keyboard focus light it (a gentle lift and the rainbow ring outside its edge),
 * a left click or Enter opens it, a right click, the Menu key or Shift+F10 opens its options. The ring is drawn
 * outside the card by a small gap plus a soft halo, so every scroller leaves [Metrics.liftRoom] around its cards.
 */
fun Modifier.dreamCard(
    radius: Dp,
    onClick: () -> Unit,
    onMenu: MenuOpener? = null,
    lift: Float = 1.045f,
    requester: FocusRequester? = null,
    onLit: ((Boolean) -> Unit)? = null,
): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val on = src.lit()
    LaunchedEffect(on) { onLit?.invoke(on) }
    val scale by animateFloatAsState(if (on) lift else 1f, tween(170))
    val ringA by animateFloatAsState(if (on) 1f else 0f, tween(if (on) 160 else 240))
    val hue by rainbowWhile(ringA > 0f)
    val m = LocalMetrics.current
    this
        .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .ring(ringA, hue, radius, m)
        .dreamClick(src, onClick, onMenu)
}

/**
 * Click, Enter, right click and the Menu key for any control: [src] carries hover and focus for its look. The hand
 * cursor marks everything that can be clicked.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.dreamClick(src: MutableInteractionSource, onClick: () -> Unit, onMenu: MenuOpener? = null): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val back = remember { FocusRequester() }
    val menu = onMenu?.let { m -> MenuOpener { at -> MenuReturn.to = back; m.open(at) } }
    this
        .focusRequester(back)
        // The control holding keyboard focus, so a layer that borrows focus can hand it back ([Focus.last]).
        .onFocusChanged { if (it.isFocused) Focus.last = back else if (Focus.last === back) Focus.last = null }
        .onGloballyPositioned { origin = it.boundsInWindow().topLeft }
        .pointerHoverIcon(PointerIcon.Hand)
        .then(
            if (menu == null) Modifier else Modifier
                .onPointerEvent(PointerEventType.Press) { e ->
                    if (e.button == PointerButton.Secondary) menu.open(e.changes.first().position + origin)
                }
                .onKeyEvent { k ->
                    val menuKey = k.key == Key.Menu || (k.key == Key.F10 && k.isShiftPressed)
                    if (menuKey && k.type == KeyEventType.KeyUp) menu.open(null)
                    menuKey
                },
        )
        .clickable(interactionSource = src, indication = null, onClick = onClick)
}

/** The rainbow light edge: a crisp ring [gap] outside the shape and a soft halo past it, faded by [a]. */
fun Modifier.ring(a: Float, hue: Color, radius: Dp, m: Metrics): Modifier = drawWithContent {
    drawContent()
    if (a <= 0f) return@drawWithContent
    val gap = m.u(3.5f).toPx()
    val w = m.u(2.5f).toPx()
    val r = radius.toPx() + gap
    // The halo: a few widening, fading strokes read as a soft glow without an offscreen blur.
    for (i in 3 downTo 1) {
        val spread = gap + w + i * m.u(3f).toPx()
        drawRoundRect(
            hue.copy(alpha = 0.10f * a / i), Offset(-spread, -spread), Size(size.width + 2 * spread, size.height + 2 * spread),
            CornerRadius(radius.toPx() + spread), style = Stroke(m.u(4f).toPx()),
        )
    }
    drawRoundRect(hue.copy(alpha = a), Offset(-gap, -gap), Size(size.width + 2 * gap, size.height + 2 * gap), CornerRadius(r), style = Stroke(w))
}
