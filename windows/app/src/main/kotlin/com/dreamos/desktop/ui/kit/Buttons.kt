package com.dreamos.desktop.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.dreamos.desktop.shell.data.Pill

/**
 * Play: the rainbow's fade at all times, its ink following the hue (owner, 2026-10-30; the iPhone's PlayButtonStyle),
 * so the primary action is always the brightest thing on the page. Lit (hovered, or focused from the keyboard) it
 * lifts and wears the rainbow ring outside its edge, like every other control; pressed it gives a little.
 */
@Composable
fun PlayButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, requester: FocusRequester? = null) {
    val src = remember { MutableInteractionSource() }
    val on = src.lit()
    val pressed by src.collectIsPressedAsState()
    val ink by ShellRainbow.inkNow
    val m = LocalMetrics.current
    val scale by animateFloatAsState(if (pressed) 0.97f else if (on) 1.04f else 1f, tween(160))
    val ringA by animateFloatAsState(if (on) 1f else 0f, tween(180))
    Row(
        modifier
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .ring(ringA, if (ringA > 0f) ShellRainbow.hue.value else Color.Transparent, m.radius, m)
            .height(m.u(48))
            .playFade(m.radius)
            .clip(RoundedCornerShape(m.radius))
            .dreamClick(src, onClick)
            .padding(horizontal = m.u(26)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineIcon(Icon.PLAY, ink, Modifier.size(m.u(18)))
        Spacer(Modifier.width(m.u(10)))
        Text(label, style = Type.button().copy(color = ink), maxLines = 1)
    }
}

/**
 * Every other button: smoked glass at rest; lit, the pearl box with dark ink and the rainbow ring (the Fire Stick's
 * PearlFocus). [icon] sits [Metrics.u](10) from the label: nothing touches.
 */
@Composable
fun PearlButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: Icon? = null, requester: FocusRequester? = null) {
    val src = remember { MutableInteractionSource() }
    val on = src.lit()
    val m = LocalMetrics.current
    val fill by animateColorAsState(if (on) Palette.pearl else Color(0x2EFFFFFF), tween(160))
    val ink by animateColorAsState(if (on) Palette.ink else Palette.text, tween(160))
    val ringA by animateFloatAsState(if (on) 1f else 0f, tween(180))
    val hue by rainbowWhile(ringA > 0f)
    Row(
        modifier
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .ring(ringA, hue, m.radius, m)
            .height(m.u(48))
            .clip(RoundedCornerShape(m.radius))
            .background(fill)
            .dreamClick(src, onClick)
            .padding(horizontal = m.u(22)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            LineIcon(icon, ink, Modifier.size(m.u(18)))
            Spacer(Modifier.width(m.u(10)))
        }
        Text(label, style = Type.button().copy(color = ink), maxLines = 1)
    }
}

/** A round icon control (bar buttons, row arrows, hero arrows): glass at rest, pearl and ringed when lit. */
@Composable
fun RoundButton(icon: Icon, name: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Float = 42f, solid: Boolean = false) {
    val src = remember { MutableInteractionSource() }
    val on = src.lit()
    val m = LocalMetrics.current
    val fill by animateColorAsState(if (on) Palette.pearl else if (solid) Color(0xE6161618) else Color(0x33FFFFFF), tween(160))
    val ink by animateColorAsState(if (on) Palette.ink else Palette.text, tween(160))
    val ringA by animateFloatAsState(if (on) 1f else 0f, tween(180))
    val hue by rainbowWhile(ringA > 0f)
    Box(
        modifier
            .semantics { contentDescription = name }
            .ring(ringA, hue, m.u(size / 2), m)
            .size(m.u(size))
            .clip(CircleShape)
            .background(fill)
            .dreamClick(src, onClick),
        contentAlignment = Alignment.Center,
    ) {
        LineIcon(icon, ink, Modifier.size(m.u(size * 0.46f)))
    }
}

/** A filter or category chip: the current one is solid white with ink; lit, the pearl box and ring. */
@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val src = remember { MutableInteractionSource() }
    val on = src.lit()
    val m = LocalMetrics.current
    val fill by animateColorAsState(when { on -> Palette.pearl; selected -> Color(0xF2FFFFFF); else -> Color(0x1FFFFFFF) }, tween(160))
    val ink = if (on || selected) Palette.ink else Palette.text
    val ringA by animateFloatAsState(if (on) 1f else 0f, tween(180))
    val hue by rainbowWhile(ringA > 0f)
    Box(
        modifier
            .ring(ringA, hue, m.u(19), m)
            .height(m.u(38))
            .defaultMinSize(minWidth = m.u(44))
            .clip(RoundedCornerShape(m.u(19)))
            .background(fill)
            .dreamClick(src, onClick)
            .padding(horizontal = m.u(18)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.chip().copy(color = ink, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium), maxLines = 1)
    }
}

/**
 * A status pill (fire-home Availability.Pill): one fixed label per state, the short form on narrow cards, never
 * icon-only. Status colors only for status; gold only for Watched.
 */
@Composable
fun StatusPill(pill: Pill, narrow: Boolean, modifier: Modifier = Modifier) {
    val m = LocalMetrics.current
    val (fg, bg, edge) = when (pill.tone) {
        Pill.Tone.GOOD -> Triple(Palette.greenText, Color(0xE00E2416), Palette.green.copy(alpha = 0.40f))
        Pill.Tone.WARN -> Triple(Palette.amberText, Color(0xE0262009), Palette.amber.copy(alpha = 0.36f))
        Pill.Tone.BAD -> Triple(Palette.redText, Color(0xE02A0F0D), Palette.red.copy(alpha = 0.40f))
        Pill.Tone.WATCHED -> Triple(Palette.gold, Palette.goldFill, Palette.gold.copy(alpha = 0.34f))
        Pill.Tone.PLAIN -> Triple(Palette.text, Color(0xD9161618), Palette.hairline)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(m.u(6)))
            .background(bg)
            .border(m.u(1), edge, RoundedCornerShape(m.u(6)))
            .padding(horizontal = m.u(8), vertical = m.u(3)),
    ) {
        Text(if (narrow) pill.labels.last() else pill.labels.first(), style = Type.badge().copy(color = fg), maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/** A card's small word badge ("NEW EPISODE", "UP NEXT"): pearl on dark glass, top-left. */
@Composable
fun CardBadge(text: String, modifier: Modifier = Modifier) {
    val m = LocalMetrics.current
    Box(modifier.clip(RoundedCornerShape(m.u(5))).background(Color(0xE6101012)).padding(horizontal = m.u(7), vertical = m.u(3))) {
        Text(text, style = Type.badge(), maxLines = 1)
    }
}

/** A horizontal row of buttons with real spacing between them. */
@Composable
fun ButtonRow(content: @Composable () -> Unit) {
    val m = LocalMetrics.current
    Row(horizontalArrangement = Arrangement.spacedBy(m.u(12)), verticalAlignment = Alignment.CenterVertically) { content() }
}
