package com.dreamos.desktop.settings.kit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.onClick
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter

/** What the pointer and the keyboard are doing to one control. */
@Immutable
data class Lit(val hovered: Boolean, val focused: Boolean, val pressed: Boolean) {
    /** Lit by either path: the mouse over it or the keyboard on it. */
    val any: Boolean get() = hovered || focused
}

@Composable
fun MutableInteractionSource.lit(): Lit {
    val h by collectIsHoveredAsState()
    val f by collectIsFocusedAsState()
    val p by collectIsPressedAsState()
    return Lit(h, f, p)
}

/**
 * One control's mouse and keyboard behavior: the hand cursor, left click, right click ([onSecondary], also the
 * Menu key and Shift+F10), Enter and Space. [source] carries hover / focus / press for the look.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.dreamClick(
    source: MutableInteractionSource,
    enabled: Boolean = true,
    onSecondary: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    var m = this.pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
    if (enabled) {
        m = m.onPreviewKeyEvent { e ->
            when {
                (e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Spacebar) -> {
                    if (e.type == KeyEventType.KeyDown) onClick()
                    true
                }
                onSecondary != null && e.type == KeyEventType.KeyDown &&
                    (e.key == Key.Menu || (e.key == Key.F10 && e.isShiftPressed)) -> { onSecondary(); true }
                else -> false
            }
        }
        if (onSecondary != null) m = m.onClick(matcher = PointerMatcher.mouse(PointerButton.Secondary), onClick = onSecondary)
    }
    return m.clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

/**
 * The rainbow light around a lit control (CinemaStyle.focusGlow): a soft halo of [halo] past the edge plus a bright
 * [edge] stroke just outside it, in [color]. Drawn behind, so every parent on the way must leave room (no clipping).
 */
fun Modifier.rainbowEdge(
    show: Boolean,
    color: Color,
    corner: Dp,
    edge: Dp = 3.dp,
    halo: Dp = 12.dp,
    haloAlpha: Float = 0.34f,
): Modifier = if (!show) this else drawBehind {
    val e = edge.toPx()
    val r = corner.toPx() + e / 2
    if (halo > 0.dp && haloAlpha > 0f) {
        val paint = Paint().apply { this.color = color.copy(alpha = haloAlpha) }
        paint.asFrameworkPaint().maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, halo.toPx() / 2.2f)
        drawIntoCanvas { c ->
            c.drawRoundRect(-e, -e, size.width + e, size.height + e, r, r, paint)
        }
    }
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(-e / 2, -e / 2),
        size = androidx.compose.ui.geometry.Size(size.width + e, size.height + e),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = e),
    )
}
