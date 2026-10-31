package com.dreamos.player.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Dream Player's look (fire-home PlayerLook / CinemaStyle / Rainbow, STANDARDS §2). Black and graphite glass; the
 * rainbow is the only accent, and it is always ONE color fading from hue to hue ([RainbowClock], owner 2026-10-30,
 * as the iPhone and the Fire Stick), never a spectrum: the played part of every progress line, the hovered or
 * focused control's ring, the Play-type action's fill. Status colors only for status (CAM
 * and Outside Your Filters amber, Ready / Playing green in Change Source, as the other apps). Kickers are Title Case
 * with normal tracking in white or gray (fire-home c2cfcd4, iPhone 6b286df).
 */
object Look {
    // Surfaces
    val Page = Color(0xFF000000)
    /** Round control resting fill: mix(shade, white, .10) at 62% (PlayerLook.control). */
    val Glass = Color(0x9E1E1E22)
    val GlassHover = Color(0xCC2A2A30)
    /** Sheets: near-black glass, hairline edge (PlayerLook.sheet). */
    val Sheet = Color(0xF50B0B0D)
    val Hairline = Color(0x1AFFFFFF)
    val Edge = Color(0x29FFFFFF)
    /** Pearl: the hovered / focused row and button fill, with [Ink] text (PearlFocus). */
    val Pearl = Color(0xFFFBF8FF)
    val Ink = Color(0xFF16161A)

    // Text on black glass (all >= 4.5:1 on #0B0B0D, checked with antislop's contrast-check).
    val Text = Color(0xFFFFFFFF)
    val Text2 = Color(0xC7FFFFFF)
    val Text3 = Color(0x9EFFFFFF)

    // Status
    val Good = Color(0xFF30D158)
    val GoodText = Color(0xFF6BEB8F)
    val Amber = Color(0xFFFFD60A)
    val AmberText = Color(0xFFFFE266)

    val Rainbow = listOf(
        Color(0xFF2F7BFF), Color(0xFF8B5CFF), Color(0xFFFF3DC8), Color(0xFFFF4D6D),
        Color(0xFFFF8A1F), Color(0xFFFFD21F), Color(0xFF7CFF4F), Color(0xFF19E3D6),
    )

    /**
     * The rainbow right now as a brush: the one fading color of [RainbowClock] (never the spectrum side by side).
     * Lit rings and the toggles paint with it. Reading it subscribes the caller to the clock; the caller keeps the
     * clock going ([rainbowUser] / [rainbowHue]).
     */
    fun rainbowBrush(): Brush = SolidColor(RainbowClock.hue.value)

    // Type: Windows' own UI face (Segoe UI), the PC counterpart of the stick's system font.
    val Kicker = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    /** "NOW PLAYING": the one green, tracked-caps kicker, as on the Fire Stick and iPhone (STANDARDS §2). */
    val NowPlaying = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
    val NowGreen = Color(0xFF4ADE80)
    val Title = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp)
    val Line = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    val Small = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val Time = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
    val Button = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val SheetTitle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold)
    val Heading = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

/** "1:02:03" / "42:07". */
fun clockText(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** The pause screen's "38 min left", "1 hr 12 min left", "2 hr left", "Less than a minute left" (PlayerOverlay.timeLeft). */
fun leftText(ms: Long): String {
    val min = (ms.coerceAtLeast(0) + 30_000) / 60_000
    return when {
        min < 1 -> "Less than a minute left"
        min < 60 -> "$min min left"
        min % 60 == 0L -> "${min / 60} hr left"
        else -> "${min / 60} hr ${min % 60} min left"
    }
}

/** "45 min", "2h", "2h 5m" (PlayerMeta.runtime: Info, the pause screen, Episodes, Up Next). */
fun runtimeText(min: Int): String = when {
    min >= 60 -> if (min % 60 == 0) "${min / 60}h" else "${min / 60}h ${min % 60}m"
    else -> "$min min"
}

/** A jump from where the picture is: "+30", "−10", "+1:20" (the seek bubble and the scrub card's offset). */
fun offsetText(deltaMs: Long): String {
    val sign = if (deltaMs < 0) "−" else "+"
    val s = kotlin.math.abs(deltaMs) / 1000
    return sign + if (s < 60) "$s" else clockText(s * 1000)
}

const val SEP = "  ·  "
