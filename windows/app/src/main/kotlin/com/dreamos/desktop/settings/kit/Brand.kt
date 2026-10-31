package com.dreamos.desktop.settings.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.dreamos.desktop.ui.kit.DreamFont
import com.dreamos.desktop.ui.kit.Palette
import com.dreamos.desktop.ui.kit.Rainbow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Dream OS's look for the pages (title page, settings, setup, profiles, about, notices), ported from the Fire Stick's
 * CinemaStyle / PearlFocus / Rainbow. Black and dark grays; the rainbow is the only accent (Play, the focus and hover
 * edge, progress). The shared values come from ui/kit ([Palette], [Rainbow], [DreamFont]) so the pages and the shell
 * agree; the rest are the pages' own surfaces and text steps.
 */
object Dream {
    val Black = Palette.page
    /** The page's lower end (STANDARDS: #000000 to #050506, never banded). */
    val Page = Palette.pageLow
    /** Raised panels (sheets, menus): a neutral graphite, no tint. */
    val Panel = Palette.surface
    val PanelEdge = Color.White.copy(alpha = 0.10f)

    /** Resting control surface: neutral dark glass with a hairline (PearlFocus.glass). */
    val Glass = Color.White.copy(alpha = 0.06f)
    val GlassEdge = Color.White.copy(alpha = 0.10f)
    /** Under the mouse: the glass brightens (keyboard focus turns pearl instead). */
    val GlassHover = Color.White.copy(alpha = 0.13f)
    val GlassPressed = Color.White.copy(alpha = 0.18f)

    val Text = Palette.text
    /** 66% white: captions, step counters (8.6:1 on black). */
    val TextDim = Color.White.copy(alpha = 0.66f)
    /** 52% white: notes, upcoming chapters (5.3:1 on black). */
    val TextFaint = Color.White.copy(alpha = 0.52f)

    /** Play's resting fill and the focused face of every other control. */
    val Pearl = Palette.pearl
    val Ink = Palette.ink
    val InkDim = Color(0xFF55555E)

    // Status colors: only for status (STANDARDS 2).
    val Green = Palette.green
    val GreenText = Palette.greenText
    val Amber = Palette.amber
    val AmberText = Palette.amberText
    val Red = Palette.red
    val RedText = Palette.redText
    /** The Watched pill only (owner-approved, 2026-10-28). */
    val Gold = Palette.gold
    val GoldFill = Palette.goldFill

    /** The rainbow: electric blue, violet, magenta, coral, orange, yellow, lime, aqua (Rainbow.COLORS). */
    val Hues = Rainbow.colors


    /** The fade's color at [now]: one hue easing into the next, from one clock so every lit control agrees (the shell's too). */
    fun fadeAt(now: Long): Color = Rainbow.fade(now)

    /** Readable ink on [fill]: white or [Ink], whichever has the higher contrast. */
    fun inkOn(fill: Color): Color = Rainbow.inkOn(fill)

    fun luminance(c: Color): Double {
        fun lin(v: Float): Double = if (v <= 0.03928f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }
}

/**
 * The rainbow's color right now while [active], from the one shared clock ([com.dreamos.player.ui.RainbowClock]);
 * a still hue otherwise, so an idle page draws nothing per frame for it.
 */
@Composable
fun rainbowNow(active: Boolean = true): Color = com.dreamos.desktop.ui.kit.shellHue(active)

/** The type scale (dp-based sp, laid out in the page's scaled space, see PageFrame), in the shell's face (Segoe UI Variable). */
object DreamType {
    val Display = TextStyle(fontFamily = DreamFont, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, lineHeight = 40.sp, color = Dream.Text)
    val Title = TextStyle(fontFamily = DreamFont, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 34.sp, color = Dream.Text)
    val SubTitle = TextStyle(fontFamily = DreamFont, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp, color = Dream.Text)
    val Big = TextStyle(fontFamily = DreamFont, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp, color = Dream.Text)
    val Section = TextStyle(fontFamily = DreamFont, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, color = Dream.Text)
    val Body = TextStyle(fontFamily = DreamFont, fontSize = 16.5.sp, lineHeight = 24.sp, color = Dream.Text)
    val Row = TextStyle(fontFamily = DreamFont, fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 21.sp, color = Dream.Text)
    val Button = TextStyle(fontFamily = DreamFont, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, color = Dream.Text)
    val Caption = TextStyle(fontFamily = DreamFont, fontSize = 14.sp, lineHeight = 19.sp, color = Dream.TextDim)
    val Meta = TextStyle(fontFamily = DreamFont, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp, color = Dream.TextDim)
    val Small = TextStyle(fontFamily = DreamFont, fontSize = 12.5.sp, lineHeight = 16.sp, color = Dream.TextFaint)
    val Pill = TextStyle(fontFamily = DreamFont, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 15.sp, letterSpacing = 0.2.sp)
}
