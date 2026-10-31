package com.dreamos.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Dream Player's icons, drawn as monoline strokes (Dream OS line art, STANDARDS §2: icons are drawn, never text).
 * Each is laid out on a 24-unit grid so they share one weight and one optical size.
 */
enum class Glyph { PLAY, PAUSE, EPISODES, AUDIO_SUBS, SOURCE, INFO, NEXT, RESTART, CLOSE, FULL, UNFULL, MINI, EXPAND, VOLUME, MUTED, SPEED, BACK, CHECK, CAST, SUN, FILM }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, color) }
}

/** The speaker for a volume level, as Windows draws its own: muted a cross; else no wave at 0, then 1 to 3 waves. */
@Composable
fun SpeakerIcon(volume: Int, muted: Boolean, color: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    val waves = when {
        muted -> -1
        volume <= 0 -> 0
        volume <= 33 -> 1
        volume <= 66 -> 2
        else -> 3
    }
    Canvas(modifier.size(size)) { drawSpeaker(waves, color) }
}

/** The speaker on the 24-unit grid: the cone, then [waves] arcs from its mouth (-1: the cross of a muted speaker). */
fun DrawScope.drawSpeaker(waves: Int, color: Color) {
    val u = size.minDimension / 24f
    val stroke = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val cone = Path().apply {
        moveTo(2.8f * u, 9.4f * u); lineTo(6.4f * u, 9.4f * u); lineTo(10.6f * u, 5.6f * u)
        lineTo(10.6f * u, 18.4f * u); lineTo(6.4f * u, 14.6f * u); lineTo(2.8f * u, 14.6f * u); close()
    }
    drawPath(cone, color, style = stroke)
    if (waves < 0) {
        drawLine(color, Offset(15.2f * u, 9.4f * u), Offset(20.4f * u, 14.6f * u), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(20.4f * u, 9.4f * u), Offset(15.2f * u, 14.6f * u), stroke.width, StrokeCap.Round)
        return
    }
    // Arcs centred on the cone's mouth, one radius step apart; the outer one ends inside the grid.
    for (i in 0 until waves.coerceAtMost(3)) {
        val r = (3.4f + i * 3.3f) * u
        drawArc(color, -48f, 96f, false, Offset(10.6f * u - r, 12f * u - r), Size(r * 2, r * 2), style = stroke)
    }
}

fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val u = size.minDimension / 24f
    val stroke = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(vararg pts: Float, close: Boolean = false) = Path().apply {
        moveTo(pts[0] * u, pts[1] * u)
        var i = 2
        while (i < pts.size) { lineTo(pts[i] * u, pts[i + 1] * u); i += 2 }
        if (close) close()
    }
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), stroke.width, StrokeCap.Round)
    when (glyph) {
        Glyph.PLAY -> drawPath(p(7.5f, 4.5f, 19.5f, 12f, 7.5f, 19.5f, close = true), color)
        Glyph.PAUSE -> {
            drawRoundRect(color, Offset(6.5f * u, 4.5f * u), Size(3.6f * u, 15f * u), CornerRadius(1.2f * u))
            drawRoundRect(color, Offset(13.9f * u, 4.5f * u), Size(3.6f * u, 15f * u), CornerRadius(1.2f * u))
        }
        Glyph.EPISODES -> {
            drawRoundRect(color, Offset(3.5f * u, 5f * u), Size(6f * u, 4.5f * u), CornerRadius(1f * u), style = stroke)
            drawRoundRect(color, Offset(3.5f * u, 14.5f * u), Size(6f * u, 4.5f * u), CornerRadius(1f * u), style = stroke)
            line(12.5f, 6.2f, 20.5f, 6.2f); line(12.5f, 8.6f, 17.5f, 8.6f)
            line(12.5f, 15.7f, 20.5f, 15.7f); line(12.5f, 18.1f, 17.5f, 18.1f)
        }
        Glyph.AUDIO_SUBS -> {
            drawRoundRect(color, Offset(3f * u, 5f * u), Size(18f * u, 13f * u), CornerRadius(3f * u), style = stroke)
            line(6.5f, 11f, 9.5f, 11f); line(11.5f, 11f, 17.5f, 11f)
            line(6.5f, 14.2f, 13.5f, 14.2f); line(15.5f, 14.2f, 17.5f, 14.2f)
        }
        Glyph.SOURCE -> {
            drawPath(p(12f, 3.5f, 20.5f, 8f, 12f, 12.5f, 3.5f, 8f, close = true), color, style = stroke)
            drawPath(p(3.5f, 12f, 12f, 16.5f, 20.5f, 12f), color, style = stroke)
            drawPath(p(3.5f, 16f, 12f, 20.5f, 20.5f, 16f), color, style = stroke)
        }
        Glyph.INFO -> {
            drawCircle(color, 9f * u, Offset(12f * u, 12f * u), style = stroke)
            line(12f, 11f, 12f, 16.5f)
            drawCircle(color, 1.25f * u, Offset(12f * u, 7.7f * u))
        }
        Glyph.NEXT -> {
            drawPath(p(5f, 5f, 15.5f, 12f, 5f, 19f, close = true), color)
            drawRoundRect(color, Offset(16.8f * u, 5f * u), Size(2.6f * u, 14f * u), CornerRadius(1f * u))
        }
        Glyph.RESTART -> {
            drawArc(color, -60f, 300f, false, Offset(4f * u, 4f * u), Size(16f * u, 16f * u), style = stroke)
            drawPath(p(16f, 3f, 16.4f, 6.6f, 12.8f, 7.4f), color, style = stroke)
        }
        Glyph.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
        Glyph.FULL -> {
            drawPath(p(4f, 9f, 4f, 4f, 9f, 4f), color, style = stroke); drawPath(p(15f, 4f, 20f, 4f, 20f, 9f), color, style = stroke)
            drawPath(p(20f, 15f, 20f, 20f, 15f, 20f), color, style = stroke); drawPath(p(9f, 20f, 4f, 20f, 4f, 15f), color, style = stroke)
        }
        Glyph.UNFULL -> {
            drawPath(p(4f, 9f, 9f, 9f, 9f, 4f), color, style = stroke); drawPath(p(15f, 4f, 15f, 9f, 20f, 9f), color, style = stroke)
            drawPath(p(20f, 15f, 15f, 15f, 15f, 20f), color, style = stroke); drawPath(p(9f, 20f, 9f, 15f, 4f, 15f), color, style = stroke)
        }
        Glyph.MINI -> {
            drawRoundRect(color, Offset(3f * u, 5f * u), Size(18f * u, 14f * u), CornerRadius(2.5f * u), style = stroke)
            drawRoundRect(color, Offset(11.5f * u, 11.5f * u), Size(7f * u, 5f * u), CornerRadius(1.2f * u))
        }
        Glyph.EXPAND -> {
            drawPath(p(13f, 4f, 20f, 4f, 20f, 11f), color, style = stroke); line(20f, 4f, 13f, 11f)
            drawPath(p(11f, 20f, 4f, 20f, 4f, 13f), color, style = stroke); line(4f, 20f, 11f, 13f)
        }
        Glyph.VOLUME, Glyph.MUTED -> {
            drawPath(p(4f, 9.5f, 7.5f, 9.5f, 12f, 5.5f, 12f, 18.5f, 7.5f, 14.5f, 4f, 14.5f, close = true), color, style = stroke)
            if (glyph == Glyph.VOLUME) {
                drawArc(color, -45f, 90f, false, Offset(10f * u, 8f * u), Size(8f * u, 8f * u), style = stroke)
                drawArc(color, -50f, 100f, false, Offset(8.5f * u, 4.5f * u), Size(13f * u, 15f * u), style = stroke)
            } else { line(15.5f, 9.5f, 20.5f, 14.5f); line(20.5f, 9.5f, 15.5f, 14.5f) }
        }
        Glyph.SPEED -> {
            drawArc(color, 150f, 240f, false, Offset(3.5f * u, 4.5f * u), Size(17f * u, 17f * u), style = stroke)
            line(12f, 13f, 16f, 8.5f)
            drawCircle(color, 1.4f * u, Offset(12f * u, 13f * u))
        }
        Glyph.BACK -> drawPath(p(15f, 5f, 8f, 12f, 15f, 19f), color, style = stroke)
        Glyph.CHECK -> drawPath(p(5f, 12.5f, 10f, 17.5f, 19f, 7f), color, style = Stroke(2.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        Glyph.CAST -> {
            drawCircle(color, 3.5f * u, Offset(12f * u, 8.5f * u), style = stroke)
            drawArc(color, 200f, 140f, false, Offset(5f * u, 14f * u), Size(14f * u, 12f * u), style = stroke)
        }
        Glyph.SUN -> {
            drawCircle(color, 3.4f * u, Offset(12f * u, 12f * u))
            for (k in 0 until 8) {
                val a = Math.toRadians(k * 45.0)
                val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat()
                line(12f + c * 6.2f, 12f + s * 6.2f, 12f + c * 8.6f, 12f + s * 8.6f)
            }
        }
        Glyph.FILM -> {
            drawRoundRect(color, Offset(4f * u, 5f * u), Size(16f * u, 14f * u), CornerRadius(2f * u), style = stroke)
            line(8f, 5f, 8f, 19f); line(16f, 5f, 16f, 19f)
        }
    }
}
