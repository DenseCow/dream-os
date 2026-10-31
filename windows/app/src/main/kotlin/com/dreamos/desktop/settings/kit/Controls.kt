package com.dreamos.desktop.settings.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dreamos.desktop.ui.kit.ShellRainbow
import com.dreamos.desktop.ui.kit.playFade
import com.dreamos.desktop.ui.kit.rainbowFill
import kotlinx.coroutines.delay

/** Control corner radii: buttons 10, rows 12, cards 12, pills full. */
object Radii { val Button = 10.dp; val Row = 12.dp; val Card = 12.dp }

/**
 * The face of a lit control. Keyboard focus: the pearl box with dark ink inside the rainbow edge (PearlFocus).
 * Mouse hover: the glass brightens and the rainbow edge shows without the halo (a pearl flip under a sweeping
 * mouse reads as flashing). Pressed: a touch brighter.
 */
@Composable
fun litColors(lit: Lit, rest: Color = Dream.Glass): Pair<Color, Color> = when {
    lit.focused -> Dream.Pearl to Dream.Ink
    lit.pressed -> Dream.GlassPressed to Dream.Text
    lit.hovered -> Dream.GlassHover to Dream.Text
    else -> rest to Dream.Text
}

/**
 * A button. [primary] is the Play type: the rainbow's fade at all times with its ink following the hue (owner,
 * 2026-10-30; the iPhone's PlayButtonStyle, one shared clock, [ShellRainbow]); lit, it lifts and the halo joins it, and
 * keyboard focus adds the rainbow ring a few points outside its edge, where it shows on every hue. Others are quiet
 * glass. Exactly one primary per screen (setup-redesign.md).
 */
@Composable
fun DreamButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    glyph: Glyph? = null,
    enabled: Boolean = true,
    big: Boolean = false,
    /** Over a picture (the hero): a darker resting glass so the words stay readable on bright art. */
    onImage: Boolean = false,
    /** Right click, the Menu key and Shift+F10 (the Fire Stick's long press). */
    onSecondary: (() -> Unit)? = null,
) {
    val src = remember { MutableInteractionSource() }
    val lit = src.lit()
    val fading = primary && enabled
    val glow = if (fading) (if (lit.any) ShellRainbow.hue.value else Color.Transparent) else rainbowNow(lit.any && enabled)
    val shape = RoundedCornerShape(Radii.Button)
    val (fill, ink) = when {
        !enabled -> Dream.Glass to Dream.TextFaint
        fading -> Color.Transparent to ShellRainbow.inkNow.value
        onImage && !lit.focused -> (if (lit.any) Color(0xE629292C) else Color(0xB8141416)) to Dream.Text
        else -> litColors(lit)
    }
    val press by animateFloatAsState(if (lit.pressed) 0.97f else if (fading && lit.any) 1.03f else 1f, tween(if (lit.pressed) 90 else 160))
    Row(
        modifier
            .scale(press)
            .rainbowEdge(enabled && lit.any && !primary, glow, Radii.Button, edge = if (lit.focused) 3.dp else 2.dp, halo = if (lit.focused) 12.dp else 0.dp)
            .rainbowEdge(fading && lit.any, glow, Radii.Button, edge = 0.dp, halo = 16.dp, haloAlpha = 0.45f)
            .then(if (fading && lit.focused) Modifier.outerRing(glow, Radii.Button) else Modifier)
            .then(if (fading) Modifier.playFade(Radii.Button) else Modifier)
            .background(fill, shape)
            .then(if (!primary && !lit.any) Modifier.border(1.dp, Dream.GlassEdge, shape) else Modifier)
            .dreamClick(src, enabled, onSecondary = onSecondary, onClick = onClick)
            .defaultMinSize(minHeight = if (big) 54.dp else 46.dp)
            .padding(horizontal = if (big) 28.dp else 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) {
            GlyphIcon(glyph, ink, if (big) 20.dp else 18.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = DreamType.Button.copy(color = ink, fontSize = if (big) DreamType.Button.fontSize * 1.08f else DreamType.Button.fontSize), maxLines = 1)
    }
}

/** The focus ring for a filled button: a stroke in [color] [gap] outside the shape, so it reads against the page, not the fill. */
private fun Modifier.outerRing(color: Color, corner: Dp, gap: Dp = 3.dp, width: Dp = 2.5.dp): Modifier = drawBehind {
    val g = gap.toPx() + width.toPx() / 2
    drawRoundRect(color, Offset(-g, -g), Size(size.width + 2 * g, size.height + 2 * g), CornerRadius(corner.toPx() + g), style = Stroke(width.toPx()))
}

/** A drawn status mark (BrandRow.Mark): OK green check, WARN amber, BUSY a turning rainbow arc, OFF a gray ring, PICK a ring that fills when chosen. */
enum class Mark { OK, WARN, BAD, BUSY, OFF, PICK, PICKED }

@Composable
fun StatusMark(mark: Mark, size: Dp = 22.dp, onPearl: Boolean = false) {
    var turn by remember { mutableFloatStateOf(0f) }
    if (mark == Mark.BUSY) LaunchedEffect(Unit) { while (true) { turn = (turn + 9f) % 360f; delay(33) } }
    val busyColor = rainbowNow(mark == Mark.BUSY)
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.1f
        val c = Offset(s / 2, s / 2)
        val r = s / 2 - w
        fun check(color: Color) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0.3f * s, 0.52f * s); lineTo(0.45f * s, 0.66f * s); lineTo(0.71f * s, 0.37f * s)
            }
            drawPath(path, color, style = Stroke(w * 1.1f, cap = StrokeCap.Round))
        }
        when (mark) {
            Mark.OK -> { drawCircle(Dream.Green, r + w / 2, c); check(Color.White) }
            Mark.WARN -> {
                drawCircle(Dream.Amber, r + w / 2, c)
                drawLine(Dream.Ink, Offset(s / 2, 0.28f * s), Offset(s / 2, 0.56f * s), w * 1.1f, StrokeCap.Round)
                drawCircle(Dream.Ink, w * 0.7f, Offset(s / 2, 0.72f * s))
            }
            Mark.BAD -> {
                drawCircle(Dream.Red, r + w / 2, c)
                drawLine(Color.White, Offset(0.36f * s, 0.36f * s), Offset(0.64f * s, 0.64f * s), w * 1.1f, StrokeCap.Round)
                drawLine(Color.White, Offset(0.64f * s, 0.36f * s), Offset(0.36f * s, 0.64f * s), w * 1.1f, StrokeCap.Round)
            }
            Mark.BUSY -> {
                drawCircle(Color.White.copy(alpha = 0.14f), r, c, style = Stroke(w))
                drawArc(busyColor, turn, 100f, false, Offset(w, w), Size(s - 2 * w, s - 2 * w), style = Stroke(w, cap = StrokeCap.Round))
            }
            Mark.OFF -> drawCircle(if (onPearl) Dream.InkDim else Dream.TextFaint, r, c, style = Stroke(w))
            Mark.PICK -> drawCircle(if (onPearl) Dream.InkDim else Dream.TextDim, r, c, style = Stroke(w))
            Mark.PICKED -> {
                drawCircle(if (onPearl) Dream.Ink else Dream.Text, r, c, style = Stroke(w))
                drawCircle(if (onPearl) Dream.Ink else Dream.Text, r * 0.5f, c)
            }
        }
    }
}

/**
 * A settings / setup row (BrandRow): [mark], title, optional caption, a value on the right and a chevron when it
 * opens a page. The whole row is the target (click, Enter); [onSecondary] for right-click options.
 */
@Composable
fun PageRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    value: String? = null,
    valueColor: Color? = null,
    mark: Mark? = null,
    leading: (@Composable (ink: Color) -> Unit)? = null,
    chevron: Boolean = true,
    enabled: Boolean = true,
    onSecondary: (() -> Unit)? = null,
    trailing: (@Composable RowScope.(ink: Color, lit: Lit) -> Unit)? = null,
) {
    val src = remember { MutableInteractionSource() }
    val lit = src.lit()
    val glow = rainbowNow(lit.any)
    val shape = RoundedCornerShape(Radii.Row)
    val (fill, ink) = litColors(lit)
    val dim = if (lit.focused) Dream.InkDim else Dream.TextDim
    Row(
        modifier
            .fillMaxWidth()
            .rainbowEdge(lit.any && enabled, glow, Radii.Row, edge = if (lit.focused) 3.dp else 2.dp, halo = if (lit.focused) 12.dp else 0.dp)
            .background(fill, shape)
            .dreamClick(src, enabled, onSecondary, onClick)
            .defaultMinSize(minHeight = 60.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mark != null) { StatusMark(mark, onPearl = lit.focused); Spacer(Modifier.width(14.dp)) }
        if (leading != null) { leading(ink); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = DreamType.Row.copy(color = if (enabled) ink else Dream.TextFaint))
            if (caption != null) {
                Spacer(Modifier.height(3.dp))
                Text(caption, style = DreamType.Caption.copy(color = dim))
            }
        }
        if (value != null) {
            Spacer(Modifier.width(16.dp))
            Text(
                value,
                style = DreamType.Row.copy(color = if (lit.focused) Dream.InkDim else (valueColor ?: Dream.TextDim), fontWeight = FontWeight.Normal),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) { Spacer(Modifier.width(14.dp)); trailing(ink, lit) }
        if (chevron) { Spacer(Modifier.width(10.dp)); GlyphIcon(Glyph.ChevronRight, dim, 18.dp) }
    }
}

/**
 * An on/off switch, the Fire Stick's SetupSwitch: no rainbow (it clashed with the lit row's pearl and edge). At rest, on
 * is a pearl track with an ink knob and off a faint track with a light knob; on a lit row's pearl box ([onPearl]) on
 * is an ink track with a pearl knob and off a faint ink track with an ink knob. Colors blend as the knob slides.
 */
@Composable
fun DreamSwitch(on: Boolean, onPearl: Boolean = false) {
    val t by animateFloatAsState(if (on) 1f else 0f, tween(200))
    Canvas(Modifier.size(width = 46.dp, height = 28.dp)) {
        val h = size.height
        val r = androidx.compose.ui.geometry.CornerRadius(h / 2)
        val track = if (onPearl) lerp(Dream.Ink.copy(alpha = 0.26f), Dream.Ink, t) else lerp(Dream.Text.copy(alpha = 0.18f), Dream.Pearl, t)
        val knob = if (onPearl) lerp(Dream.Ink, Dream.Pearl, t) else lerp(Dream.Text, Dream.Ink, t)
        drawRoundRect(track, cornerRadius = r)
        val pad = h * 0.13f
        val kr = h / 2 - pad
        val x = pad + kr + t * (size.width - 2 * (pad + kr))
        drawCircle(knob, kr, Offset(x, h / 2))
    }
}

/** A row with a switch: the whole row toggles. */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, caption: String? = null, modifier: Modifier = Modifier) {
    PageRow(
        title, onClick = { onChange(!checked) }, modifier = modifier, caption = caption, chevron = false,
        trailing = { _, lit -> DreamSwitch(checked, onPearl = lit.focused) },
    )
}

/**
 * A choice that cycles (setup-redesign.md: one row that Left/Right steps through, "‹ Lavender ›"). Click steps
 * forward; the arrows are their own targets; Left / Right keys step while the row has focus.
 */
@Composable
fun CycleRow(title: String, value: String, onPrev: () -> Unit, onNext: () -> Unit, caption: String? = null, modifier: Modifier = Modifier) {
    PageRow(
        title, onClick = onNext, caption = caption, chevron = false,
        modifier = modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) { Key.DirectionLeft -> { onPrev(); true }; Key.DirectionRight -> { onNext(); true }; else -> false }
        },
        trailing = { ink, lit ->
            val dim = if (lit.focused) Dream.InkDim else Dream.TextDim
            ArrowHit(Glyph.ChevronLeft, if (lit.any) ink else dim, onPrev)
            Text(value, style = DreamType.Row.copy(color = ink), modifier = Modifier.padding(horizontal = 6.dp), maxLines = 1)
            ArrowHit(Glyph.ChevronRight, if (lit.any) ink else dim, onNext)
        },
    )
}

@Composable
private fun ArrowHit(glyph: Glyph, color: Color, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val lit = src.lit()
    Box(
        Modifier.size(34.dp).background(if (lit.hovered) color.copy(alpha = 0.12f) else Color.Transparent, CircleShape)
            .dreamClick(src, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(glyph, color, 18.dp) }
}

/** A section heading with an optional Required / Optional word (SetupParts.section). */
@Composable
fun SectionHead(text: String, modifier: Modifier = Modifier, tag: String? = null) {
    Row(modifier.padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = DreamType.Section)
        if (tag != null) {
            Spacer(Modifier.width(12.dp))
            Text(tag, style = DreamType.Small.copy(color = Dream.TextDim, fontWeight = FontWeight.Medium))
        }
    }
}

/** Pill kinds: availability and status pills (Availability.Pill), the gold Watched pill, a quiet info pill. */
enum class PillKind { Green, Amber, Neutral, Watched, Red }

/** A pill: the word(s) on a dark capsule, with a drawn mark for the status kinds. Informational: never focusable. */
@Composable
fun Pill(text: String, kind: PillKind = PillKind.Neutral, glyph: Glyph? = null, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    val (bg, fg, edge) = when (kind) {
        PillKind.Green -> Triple(Color(0xE60E1F14), Dream.GreenText, Dream.Green.copy(alpha = 0.34f))
        PillKind.Amber -> Triple(Color(0xE6221D08), Dream.AmberText, Dream.Amber.copy(alpha = 0.34f))
        PillKind.Red -> Triple(Color(0xE6240E0D), Dream.RedText, Dream.Red.copy(alpha = 0.34f))
        PillKind.Watched -> Triple(Dream.GoldFill, Dream.Gold, Dream.Gold.copy(alpha = 0.34f))
        PillKind.Neutral -> Triple(Color(0xD91A1A1D), Dream.Text, Color.White.copy(alpha = 0.16f))
    }
    val mark = glyph ?: when (kind) { PillKind.Green, PillKind.Watched -> Glyph.Check; else -> null }
    Row(
        modifier.background(bg, shape).border(1.dp, edge, shape).padding(start = if (mark != null) 9.dp else 12.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mark != null) { GlyphIcon(mark, fg, 14.dp, weight = 1.3f); Spacer(Modifier.width(6.dp)) }
        Text(text, style = DreamType.Pill.copy(color = fg), maxLines = 1)
    }
}

/** A quiet line of text under a list or beside an action. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier, color: Color = Dream.TextDim) {
    Text(text, style = DreamType.Caption.copy(color = color), modifier = modifier)
}

/** A result line with its mark (a key that passed, a check that failed). */
@Composable
fun ResultLine(mark: Mark, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusMark(mark, 22.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = DreamType.Row.copy(
                color = when (mark) { Mark.OK -> Dream.GreenText; Mark.WARN -> Dream.AmberText; Mark.BAD -> Dream.RedText; else -> Dream.Text },
            ),
        )
    }
}

/** A thin horizontal progress bar: the fading rainbow over a quiet track. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(modifier.height(height).fillMaxWidth().background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).rainbowFill(RoundedCornerShape(50)))
    }
}

/** A row that only tells (credits, facts): the row's shape without a hover or a target. */
@Composable
fun InfoRow(title: String, caption: String? = null, value: String? = null, modifier: Modifier = Modifier, valueColor: Color = Dream.TextDim) {
    Row(
        modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(Radii.Row)).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = DreamType.Row)
            if (caption != null) { Spacer(Modifier.height(3.dp)); Text(caption, style = DreamType.Caption) }
        }
        if (value != null) { Spacer(Modifier.width(16.dp)); Text(value, style = DreamType.Row.copy(color = valueColor, fontWeight = FontWeight.Normal)) }
    }
}
