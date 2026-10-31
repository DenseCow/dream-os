package com.dreamos.desktop.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/**
 * Dream OS's palette (fire-home STANDARDS section 2): black and dark neutral grays, the rainbow as the only accent,
 * status colors only for status, gold only for the Watched pill.
 */
object Palette {
    val page = Color(0xFF000000)
    val pageLow = Color(0xFF050506)
    /** Card and tile placeholders: one step up from the page. */
    val surface = Color(0xFF111113)
    val surfaceHigh = Color(0xFF1A1A1D)
    /** The glass bar and menus: near-black at most of full opacity, so art behind reads only as a shadow. */
    val glass = Color(0xE00B0B0D)
    val hairline = Color(0x1FFFFFFF)
    val text = Color(0xFFF5F5F7)
    /** Secondary text: 74% white on black is 13.8:1, captions stay well above AA. */
    val textDim = Color(0xBDFFFFFF)
    /** Tertiary text (meta on cards): 62% white on black, 9.4:1. */
    val textFaint = Color(0x9EFFFFFF)
    /**
     * Text over art (hero, Spotlight): near-white, not the dim gray used on black. Over the scrim's lightest spot (a
     * white backdrop at the text column's right edge, about #4D4D4D after the scrim) it is still 7.1:1.
     */
    val overArt = Color(0xEBFFFFFF)
    /** Pearl focus box (buttons, chips, menu rows) and its ink. */
    val pearl = Color(0xFFFBF8FF)
    val ink = Color(0xFF16161A)

    val green = Color(0xFF30D158)
    val greenText = Color(0xFF6BEB8F)
    val amber = Color(0xFFFFD60A)
    val amberText = Color(0xFFFFE266)
    val red = Color(0xFFFF453A)
    val redText = Color(0xFFFF8178)
    val gold = Color(0xFFE8C468)
    val goldFill = Color(0xEB2A2412)
}

/** The Dream rainbow (fire-home Rainbow.COLORS): electric blue, violet, magenta, coral, orange, yellow, lime, aqua. */
object Rainbow {
    val colors = listOf(
        Color(0xFF2F7BFF), Color(0xFF8B5CFF), Color(0xFFFF3DC8), Color(0xFFFF4D6D),
        Color(0xFFFF8A1F), Color(0xFFFFD21F), Color(0xFF7CFF4F), Color(0xFF19E3D6),
    )
    private const val HUE_MS = 2_200L

    /** One solid hue easing into the next, from one wall clock so every ring and Play button agree. */
    fun fade(nowMs: Long = System.currentTimeMillis()): Color {
        val n = colors.size
        val pos = (nowMs % (HUE_MS * n)) / HUE_MS.toFloat()
        val i = pos.toInt()
        val t = pos - i
        return lerp(colors[i], colors[(i + 1) % n], t * t * (3 - 2 * t))
    }

    /** Hue [i] of the rainbow, wrapping (genre tiles take them in turn). */
    fun hue(i: Int) = colors[((i % colors.size) + colors.size) % colors.size]

    /** White or ink, whichever reads better on [fill] (WCAG luminance). */
    fun inkOn(fill: Color): Color {
        fun lin(c: Float) = if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        val l = 0.2126f * lin(fill.red) + 0.7152f * lin(fill.green) + 0.0722f * lin(fill.blue)
        val onWhite = 1.05f / (l + 0.05f)
        val onInk = (l + 0.05f) / (0.0087f + 0.05f)
        return if (onInk >= onWhite) Palette.ink else Color.White
    }
}

fun mix(a: Color, b: Color, f: Float) = lerp(a, b, f)

/**
 * Sizes that follow the window. [s] grows with the width but slower than it (power 0.6), so a bigger window shows
 * both larger type and more cards per row: about 4.5 landscape cards at 1024 wide, 5.5 at 1920, 7 at 3840.
 * Compose's dp already includes the Windows display scale, so a 4K TV at 150% lays out like a 2560 window.
 */
@Immutable
class Metrics(val widthDp: Float, val heightDp: Float) {
    val s: Float = (widthDp / 1280f).pow(0.6f).coerceIn(0.84f, 2.2f)
    fun u(v: Float): Dp = (v * s).dp
    fun u(v: Int): Dp = (v * s).dp
    fun t(v: Float): TextUnit = (v * s).sp

    val gutter: Dp get() = u(48)
    val cardGap: Dp get() = u(14)
    val rowGap: Dp get() = u(30)
    val titleGap: Dp get() = u(12)
    /** Room a lifted card's ring and halo need inside every scroller (never clipped, STANDARDS section 3). */
    val liftRoom: Dp get() = u(16)
    val barH: Dp get() = u(68)
    val radius: Dp get() = u(10)

    /** Width of each card when cards of about [target] fill the row from the gutter to the window edge, plus a peek. */
    fun fit(target: Float, peek: Float = 0.32f, minAcross: Int = 1): Dp {
        val avail = widthDp - gutter.value
        val gap = cardGap.value
        val tw = target * s
        val n = max(minAcross.toFloat(), floor((avail + gap) / (tw + gap)))
        return floor((avail - n * gap) / (n + peek)).dp
    }

    /** Columns of about [target] filling the page between both gutters exactly (grids). */
    fun columns(target: Float, min: Int = 2): Pair<Int, Dp> {
        val avail = widthDp - 2 * gutter.value
        val gap = cardGap.value
        val n = max(min, ((avail + gap) / (target * s + gap)).toInt())
        return n to floor((avail - (n - 1) * gap) / n).dp
    }

    val landW: Dp get() = fit(250f)
    val posterW: Dp get() = fit(158f)
    val bigW: Dp get() = fit(340f)
    val brandW: Dp get() = fit(200f)
    val genreW: Dp get() = fit(250f)
    val rankSlot: Dp get() = u(64)
    val rankPosterW: Dp get() = (fit(198f).value - rankSlot.value - u(6).value).dp

    /** How far the first row tucks up into the hero's faded bottom. */
    val heroOverlap: Dp get() = u(72)

    /**
     * The hero: the window's height less the first row (its title, cards and captions), so Home opens on the hero
     * with one whole row under it and nothing cut by the window's bottom edge; never taller than 82% of the window.
     * The first row is Continue Watching (landscape cards) or Top Picks (big cards) depending on the viewer, and the
     * hero shows before either, so it leaves room for the taller of the two.
     */
    val heroH: Dp get() {
        val firstRow = u(30).value + 2 * liftRoom.value + maxOf(landW.value, bigW.value) * 9f / 16f + u(48).value
        return (heightDp - firstRow + heroOverlap.value).coerceAtMost(heightDp * 0.82f).coerceAtMost(widthDp * 0.6f).coerceAtLeast(u(430).value).dp
    }
}

val LocalMetrics = staticCompositionLocalOf { Metrics(1280f, 720f) }

/** Windows' "Animation effects" is off: crossfades and slides become cuts, the hero stops advancing by itself. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Segoe UI Variable: Windows 11's own face, so Dream OS reads as at home on the PC, the way the Fire Stick uses the
 * platform face and the iPhone uses SF. Older Windows falls back to Segoe UI; other systems to their default.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val DreamFont: FontFamily = run {
    val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    val name = if (windows) "Segoe UI Variable Display" else "System"
    if (!windows) FontFamily.Default
    else FontFamily(
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { SystemFont(name, it) },
    )
}

/** The type scale; every size goes through [Metrics.t] so it follows the window. */
object Type {
    @Composable private fun m() = LocalMetrics.current
    @Composable fun heroTitle() = style(46f, FontWeight.Bold, -0.6f)
    @Composable fun pageTitle() = style(36f, FontWeight.Bold, -0.4f)
    @Composable fun pageSub() = style(15f, FontWeight.Medium, color = Palette.textDim)
    @Composable fun rowTitle() = style(20f, FontWeight.SemiBold, -0.1f)
    @Composable fun cardTitle() = style(14.5f, FontWeight.SemiBold)
    @Composable fun cardMeta() = style(13f, FontWeight.Medium, color = Palette.textFaint)
    @Composable fun meta() = style(14.5f, FontWeight.Medium, color = Palette.textDim)
    @Composable fun synopsis() = style(16f, FontWeight.Normal, color = Palette.textDim, lineHeight = 1.42f)
    /** Kickers ("Tonight  ·  Movie", "New Episode"): Title Case, normal tracking (STANDARDS section 4; fire-home c2cfcd4). */
    @Composable fun kicker() = style(13f, FontWeight.SemiBold, color = Palette.textDim)
    @Composable fun tab() = style(15.5f, FontWeight.Medium)
    @Composable fun button() = style(15.5f, FontWeight.SemiBold)
    @Composable fun chip() = style(14f, FontWeight.Medium)
    @Composable fun body() = style(15f, FontWeight.Normal, color = Palette.textDim, lineHeight = 1.45f)
    @Composable fun badge() = style(11.5f, FontWeight.Bold, 0.4f)

    @Composable fun style(size: Float, weight: FontWeight, tracking: Float = 0f, color: Color = Palette.text, lineHeight: Float = 1.25f): TextStyle {
        val mm = m()
        return TextStyle(
            fontFamily = DreamFont,
            fontSize = mm.t(size),
            fontWeight = weight,
            letterSpacing = tracking.sp,
            color = color,
            lineHeight = mm.t(size * lineHeight),
        )
    }
}

/** " · " between meta parts, with room either side (nothing touches, STANDARDS section 3). */
const val DOT = "  ·  "

@Composable
fun DreamTheme(metrics: Metrics, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMetrics provides metrics, content = content)
}
