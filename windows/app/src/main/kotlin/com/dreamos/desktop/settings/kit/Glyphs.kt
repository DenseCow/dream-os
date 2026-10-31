package com.dreamos.desktop.settings.kit

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

/** Drawn line icons (STANDARDS 2: icons are drawn, never text characters). Monoline, round caps. */
enum class Glyph { Back, ChevronRight, ChevronLeft, ChevronDown, Check, Plus, Play, Restart, Lock, Pencil, Speaker, Warning, Info, Open, Close, Film, Sources, Copy, Face, Bell, Trash, Person, Clock, Key, Pc, Nodes, Bolt, Grid, Snow, Sliders, People, SpeakerOff }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp = 20.dp, modifier: Modifier = Modifier, weight: Float = 1f) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, color, weight) }
}

fun DrawScope.drawGlyph(glyph: Glyph, color: Color, weight: Float = 1f) {
    val s = size.minDimension
    val w = s * 0.09f * weight
    val st = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(vararg pts: Float, close: Boolean = false) = Path().apply {
        moveTo(pts[0] * s, pts[1] * s)
        var i = 2
        while (i < pts.size) { lineTo(pts[i] * s, pts[i + 1] * s); i += 2 }
        if (close) close()
    }
    when (glyph) {
        Glyph.Back -> { drawPath(p(0.62f, 0.2f, 0.32f, 0.5f, 0.62f, 0.8f), color, style = st) }
        Glyph.ChevronLeft -> drawPath(p(0.6f, 0.22f, 0.34f, 0.5f, 0.6f, 0.78f), color, style = st)
        Glyph.ChevronRight -> drawPath(p(0.4f, 0.22f, 0.66f, 0.5f, 0.4f, 0.78f), color, style = st)
        Glyph.ChevronDown -> drawPath(p(0.22f, 0.4f, 0.5f, 0.66f, 0.78f, 0.4f), color, style = st)
        Glyph.Check -> drawPath(p(0.2f, 0.53f, 0.42f, 0.74f, 0.8f, 0.28f), color, style = st)
        Glyph.Plus -> {
            drawLine(color, Offset(0.5f * s, 0.2f * s), Offset(0.5f * s, 0.8f * s), w, StrokeCap.Round)
            drawLine(color, Offset(0.2f * s, 0.5f * s), Offset(0.8f * s, 0.5f * s), w, StrokeCap.Round)
        }
        Glyph.Close -> {
            drawLine(color, Offset(0.26f * s, 0.26f * s), Offset(0.74f * s, 0.74f * s), w, StrokeCap.Round)
            drawLine(color, Offset(0.74f * s, 0.26f * s), Offset(0.26f * s, 0.74f * s), w, StrokeCap.Round)
        }
        Glyph.Play -> drawPath(p(0.3f, 0.18f, 0.82f, 0.5f, 0.3f, 0.82f, close = true), color)
        Glyph.Restart -> {
            drawArc(color, 200f, 290f, false, Offset(0.2f * s, 0.2f * s), Size(0.6f * s, 0.6f * s), style = st)
            drawPath(p(0.14f, 0.26f, 0.23f, 0.45f, 0.42f, 0.37f), color, style = st)
        }
        Glyph.Lock -> {
            drawRoundRect(color, Offset(0.22f * s, 0.44f * s), Size(0.56f * s, 0.42f * s), CornerRadius(0.08f * s), style = st)
            drawArc(color, 180f, 180f, false, Offset(0.33f * s, 0.16f * s), Size(0.34f * s, 0.4f * s), style = st)
            drawLine(color, Offset(0.33f * s, 0.36f * s), Offset(0.33f * s, 0.44f * s), w)
            drawLine(color, Offset(0.67f * s, 0.36f * s), Offset(0.67f * s, 0.44f * s), w)
        }
        Glyph.Pencil -> {
            drawPath(p(0.22f, 0.78f, 0.26f, 0.62f, 0.66f, 0.22f, 0.78f, 0.34f, 0.38f, 0.74f, close = true), color, style = st)
            drawLine(color, Offset(0.58f * s, 0.3f * s), Offset(0.7f * s, 0.42f * s), w)
        }
        Glyph.Speaker -> {
            drawPath(p(0.16f, 0.4f, 0.32f, 0.4f, 0.52f, 0.22f, 0.52f, 0.78f, 0.32f, 0.6f, 0.16f, 0.6f, close = true), color, style = st)
            drawArc(color, -45f, 90f, false, Offset(0.46f * s, 0.32f * s), Size(0.24f * s, 0.36f * s), style = st)
            drawArc(color, -50f, 100f, false, Offset(0.44f * s, 0.2f * s), Size(0.4f * s, 0.6f * s), style = st)
        }
        Glyph.Warning -> {
            drawPath(p(0.5f, 0.14f, 0.88f, 0.82f, 0.12f, 0.82f, close = true), color, style = st)
            drawLine(color, Offset(0.5f * s, 0.4f * s), Offset(0.5f * s, 0.58f * s), w, StrokeCap.Round)
            drawCircle(color, w * 0.65f, Offset(0.5f * s, 0.7f * s))
        }
        Glyph.Info -> {
            drawCircle(color, 0.36f * s, Offset(0.5f * s, 0.5f * s), style = st)
            drawLine(color, Offset(0.5f * s, 0.46f * s), Offset(0.5f * s, 0.68f * s), w, StrokeCap.Round)
            drawCircle(color, w * 0.65f, Offset(0.5f * s, 0.33f * s))
        }
        Glyph.Open -> {
            drawPath(p(0.46f, 0.22f, 0.22f, 0.22f, 0.22f, 0.78f, 0.78f, 0.78f, 0.78f, 0.54f), color, style = st)
            drawPath(p(0.58f, 0.2f, 0.8f, 0.2f, 0.8f, 0.42f), color, style = st)
            drawLine(color, Offset(0.8f * s, 0.2f * s), Offset(0.48f * s, 0.52f * s), w, StrokeCap.Round)
        }
        Glyph.Film -> {
            drawRoundRect(color, Offset(0.16f * s, 0.24f * s), Size(0.68f * s, 0.52f * s), CornerRadius(0.08f * s), style = st)
            drawPath(p(0.44f, 0.38f, 0.62f, 0.5f, 0.44f, 0.62f, close = true), color)
        }
        Glyph.Sources -> {
            for (i in 0..2) {
                val y = (0.28f + i * 0.22f) * s
                drawLine(color, Offset(0.32f * s, y), Offset(0.82f * s, y), w, StrokeCap.Round)
                drawCircle(color, w * 0.75f, Offset(0.19f * s, y))
            }
        }
        Glyph.Copy -> {
            drawRoundRect(color, Offset(0.32f * s, 0.32f * s), Size(0.48f * s, 0.5f * s), CornerRadius(0.07f * s), style = st)
            drawPath(p(0.22f, 0.66f, 0.2f, 0.66f, 0.2f, 0.18f, 0.66f, 0.18f, 0.66f, 0.22f), color, style = st)
        }
        Glyph.Face -> {
            // Windows Hello: a face in a frame of corners.
            val c = 0.18f; val e = 0.82f; val k = 0.16f
            drawPath(p(c, c + k, c, c, c + k, c), color, style = st)
            drawPath(p(e - k, c, e, c, e, c + k), color, style = st)
            drawPath(p(c, e - k, c, e, c + k, e), color, style = st)
            drawPath(p(e - k, e, e, e, e, e - k), color, style = st)
            drawCircle(color, w * 0.7f, Offset(0.4f * s, 0.43f * s))
            drawCircle(color, w * 0.7f, Offset(0.6f * s, 0.43f * s))
            drawArc(color, 20f, 140f, false, Offset(0.38f * s, 0.46f * s), Size(0.24f * s, 0.16f * s), style = st)
        }
        Glyph.Bell -> {
            drawPath(Path().apply {
                moveTo(0.24f * s, 0.7f * s); lineTo(0.3f * s, 0.6f * s); lineTo(0.3f * s, 0.44f * s)
                cubicTo(0.3f * s, 0.24f * s, 0.7f * s, 0.24f * s, 0.7f * s, 0.44f * s)
                lineTo(0.7f * s, 0.6f * s); lineTo(0.76f * s, 0.7f * s); close()
            }, color, style = st)
            drawArc(color, 0f, 180f, false, Offset(0.42f * s, 0.7f * s), Size(0.16f * s, 0.12f * s), style = st)
        }
        Glyph.Trash -> {
            drawLine(color, Offset(0.2f * s, 0.28f * s), Offset(0.8f * s, 0.28f * s), w, StrokeCap.Round)
            drawPath(p(0.28f, 0.28f, 0.32f, 0.82f, 0.68f, 0.82f, 0.72f, 0.28f), color, style = st)
            drawPath(p(0.4f, 0.28f, 0.42f, 0.18f, 0.58f, 0.18f, 0.6f, 0.28f), color, style = st)
        }
        Glyph.Clock -> {
            drawCircle(color, 0.34f * s, Offset(0.5f * s, 0.5f * s), style = st)
            drawPath(p(0.5f, 0.3f, 0.5f, 0.5f, 0.64f, 0.6f), color, style = st)
        }
        Glyph.Key -> {
            drawCircle(color, 0.15f * s, Offset(0.32f * s, 0.5f * s), style = st)
            drawPath(p(0.47f, 0.5f, 0.84f, 0.5f, 0.84f, 0.62f), color, style = st)
            drawLine(color, Offset(0.72f * s, 0.5f * s), Offset(0.72f * s, 0.6f * s), w, StrokeCap.Round)
        }
        Glyph.Pc -> {
            drawRoundRect(color, Offset(0.14f * s, 0.2f * s), Size(0.72f * s, 0.46f * s), CornerRadius(0.06f * s), style = st)
            drawLine(color, Offset(0.5f * s, 0.66f * s), Offset(0.5f * s, 0.78f * s), w, StrokeCap.Round)
            drawLine(color, Offset(0.34f * s, 0.8f * s), Offset(0.66f * s, 0.8f * s), w, StrokeCap.Round)
        }
        Glyph.Person -> {
            drawCircle(color, 0.15f * s, Offset(0.5f * s, 0.34f * s), style = st)
            drawArc(color, 180f, 180f, false, Offset(0.22f * s, 0.56f * s), Size(0.56f * s, 0.44f * s), style = st)
        }
        Glyph.Nodes -> {
            // Dream Core Engine: three sources joined into one (the iPhone's point.3.connected).
            val a = Offset(0.24f * s, 0.7f * s); val b = Offset(0.5f * s, 0.26f * s); val c = Offset(0.76f * s, 0.7f * s)
            drawLine(color, a, b, w, StrokeCap.Round); drawLine(color, b, c, w, StrokeCap.Round); drawLine(color, a, c, w, StrokeCap.Round)
            for (o in listOf(a, b, c)) drawCircle(color, 0.1f * s, o)
        }
        Glyph.Bolt -> drawPath(p(0.56f, 0.14f, 0.26f, 0.56f, 0.48f, 0.56f, 0.42f, 0.86f, 0.74f, 0.42f, 0.52f, 0.42f, close = true), color, style = st)
        Glyph.Grid -> {
            for (x in 0..1) for (y in 0..1) drawRoundRect(color, Offset((0.2f + x * 0.33f) * s, (0.2f + y * 0.33f) * s), Size(0.27f * s, 0.27f * s), CornerRadius(0.05f * s), style = st)
        }
        Glyph.Snow -> {
            for (i in 0 until 3) {
                val a = Math.toRadians(90.0 + i * 60.0)
                val dx = (kotlin.math.cos(a) * 0.34f).toFloat() * s; val dy = (kotlin.math.sin(a) * 0.34f).toFloat() * s
                drawLine(color, Offset(0.5f * s - dx, 0.5f * s - dy), Offset(0.5f * s + dx, 0.5f * s + dy), w, StrokeCap.Round)
            }
            for (i in 0 until 6) {
                val a = Math.toRadians(90.0 + i * 60.0)
                val tip = Offset(0.5f * s + (kotlin.math.cos(a) * 0.22f).toFloat() * s, 0.5f * s + (kotlin.math.sin(a) * 0.22f).toFloat() * s)
                for (side in listOf(-1, 1)) {
                    val b = a + side * Math.toRadians(45.0)
                    drawLine(color, tip, Offset(tip.x + (kotlin.math.cos(b) * 0.1f).toFloat() * s, tip.y + (kotlin.math.sin(b) * 0.1f).toFloat() * s), w * 0.85f, StrokeCap.Round)
                }
            }
        }
        Glyph.Sliders -> {
            listOf(0.3f to 0.62f, 0.5f to 0.34f, 0.7f to 0.56f).forEach { (y, k) ->
                drawLine(color, Offset(0.18f * s, y * s), Offset(0.82f * s, y * s), w, StrokeCap.Round)
                drawCircle(color, 0.085f * s, Offset(k * s, y * s))
            }
        }
        Glyph.People -> {
            drawCircle(color, 0.12f * s, Offset(0.38f * s, 0.36f * s), style = st)
            drawArc(color, 180f, 180f, false, Offset(0.16f * s, 0.56f * s), Size(0.44f * s, 0.36f * s), style = st)
            drawArc(color, 200f, 290f, false, Offset(0.56f * s, 0.25f * s), Size(0.2f * s, 0.2f * s), style = st)
            drawArc(color, 270f, 90f, false, Offset(0.5f * s, 0.56f * s), Size(0.34f * s, 0.36f * s), style = st)
        }
        Glyph.SpeakerOff -> {
            drawPath(p(0.14f, 0.4f, 0.3f, 0.4f, 0.5f, 0.22f, 0.5f, 0.78f, 0.3f, 0.6f, 0.14f, 0.6f, close = true), color, style = st)
            drawLine(color, Offset(0.62f * s, 0.38f * s), Offset(0.86f * s, 0.62f * s), w, StrokeCap.Round)
            drawLine(color, Offset(0.86f * s, 0.38f * s), Offset(0.62f * s, 0.62f * s), w, StrokeCap.Round)
        }
    }
}
