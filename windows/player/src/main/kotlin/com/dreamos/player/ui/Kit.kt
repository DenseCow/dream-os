package com.dreamos.player.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Hover, keyboard focus and press for one control: what every Dream Player control shares. */
class ControlState(val hovered: Boolean, val focused: Boolean, val pressed: Boolean, val enabled: Boolean) {
    /** Lit = the pearl box with the rainbow ring (PearlFocus): the pointer is on it or the keyboard is. */
    val lit get() = enabled && (hovered || focused)
}

@Composable
fun Modifier.control(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable (Modifier, ControlState) -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val focused by src.collectIsFocusedAsState()
    val pressed by src.collectIsPressedAsState()
    val state = ControlState(hovered, focused, pressed, enabled)
    // Lit, its ring takes the fading rainbow: the clock runs while it shows.
    rainbowHue(state.lit, player = true)
    val scale by animateFloatAsState(if (pressed && enabled) 0.95f else if (state.lit) 1.04f else 1f, tween(140))
    content(
        this.scale(scale)
            .semantics { contentDescription = label }
            .hoverable(src, enabled)
            .clickable(src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
        state,
    )
}

/** Round icon control (Play / Pause, window buttons). Rest: dark glass + hairline. Lit: pearl, ink icon, rainbow ring. */
@Composable
fun RoundButton(glyph: Glyph, label: String, onClick: () -> Unit, size: Dp = 48.dp, iconSize: Dp = 22.dp, modifier: Modifier = Modifier) =
    RoundButton(label, onClick, size, modifier) { ink -> GlyphIcon(glyph, ink, iconSize) }

/** [RoundButton] with an icon of its own drawing (the speaker's waves), given the ink for its state. */
@Composable
fun RoundButton(label: String, onClick: () -> Unit, size: Dp = 48.dp, modifier: Modifier = Modifier, icon: @Composable (ink: Color) -> Unit) {
    modifier.control(label, onClick = onClick) { m, s ->
        Box(
            m.size(size).clip(CircleShape)
                .background(if (s.lit) Look.Pearl else Look.Glass)
                .border(if (s.lit) BorderStroke(2.dp, Look.rainbowBrush()) else BorderStroke(1.dp, Look.Edge), CircleShape),
            contentAlignment = Alignment.Center,
        ) { icon(if (s.lit) Look.Ink else Look.Text) }
    }
}

/**
 * A labelled pill: glass at rest, pearl with ink and the rainbow ring when lit. [primary]: Play-type actions (STANDARDS
 * §2), the rainbow's fade at all times with its ink following the hue ([RainbowClock]); lit, the rainbow ring a few points
 * outside its edge and the lift.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    glyph: Glyph? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
) {
    modifier.control(text, enabled, onClick) { m, s ->
        val shape = RoundedCornerShape(height / 2)
        val fading = primary && enabled
        val fill: Brush = when {
            !enabled -> SolidColor(Look.Glass.copy(alpha = 0.35f))
            fading -> SolidColor(Color.Transparent)
            s.lit -> SolidColor(Look.Pearl)
            else -> SolidColor(Look.Glass)
        }
        val ink = when {
            !enabled -> Look.Text3
            fading -> RainbowClock.inkNow.value
            s.lit -> Look.Ink
            else -> Look.Text
        }
        Row(
            m.then(if (fading && s.lit) Modifier.outerRainbowRing(height / 2) else Modifier)
                .heightIn(min = height)
                .then(if (fading) Modifier.playFade(height / 2) else Modifier)
                .clip(shape).background(fill)
                .border(if (s.lit && !primary) BorderStroke(2.dp, Look.rainbowBrush()) else BorderStroke(1.dp, if (primary) Color.Transparent else Look.Edge), shape)
                .padding(horizontal = if (glyph != null) 18.dp else 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (glyph != null) {
                GlyphIcon(glyph, ink, 20.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = Look.Button, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A full-width row in a sheet: pearl with ink and the rainbow ring when lit (PlayerLook.row). [ring]: the rainbow
 * edge at rest too (the episode that is playing, as on the iPhone).
 */
@Composable
fun SheetRow(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
    ring: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable (ink: Color, dim: Color) -> Unit,
) {
    modifier.control(label, enabled, onClick) { m, s ->
        val shape: Shape = RoundedCornerShape(14.dp)
        Box(
            m.clip(shape)
                .background(if (s.lit) Look.Pearl else if (selected) Color(0x0FFFFFFF) else Color.Transparent)
                .then(if (ring && !s.lit) Modifier.rainbowUser(player = true) else Modifier)
                .border(if (s.lit || ring) BorderStroke(2.dp, Look.rainbowBrush()) else BorderStroke(0.dp, Color.Transparent), shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) { content(if (s.lit) Look.Ink else if (enabled) Look.Text else Look.Text3, if (s.lit) Color(0xFF55555E) else Look.Text3) }
    }
}

/** The rainbow ring while [lit] (cards, chips, faces), else a hairline in [rest]. */
fun Modifier.litEdge(lit: Boolean, width: Dp, shape: Shape, rest: Color = Look.Hairline): Modifier =
    if (lit) border(width, Look.rainbowBrush(), shape) else border(1.dp, rest, shape)

/** A glass capsule with words (badges, pills). */
@Composable
fun Capsule(text: String, color: Color = Look.Text2, fill: Color = Color(0x960E0E10), edge: Brush = SolidColor(Look.Hairline), lead: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(7.dp)).background(fill).border(1.dp, edge, RoundedCornerShape(7.dp))
            .padding(horizontal = 9.dp).height(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lead != null) { lead(); Spacer(Modifier.width(8.dp)) }
        Text(text, style = Look.Small, color = color, maxLines = 1)
    }
}
