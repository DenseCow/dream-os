package com.dreamos.desktop.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dream OS's line icons (fire-home LineIcon): one stroke weight on a 24-unit grid, rounded caps and joins. Drawn,
 * never text characters (STANDARDS section 2).
 */
enum class Icon { HOME, FILM, TV, BOOKMARK, SEARCH, GEAR, PLAY, INFO, PLUS, CHECK, LEFT, RIGHT, UP, DOWN, CLOSE, PERSON, BELL }

@Composable
fun LineIcon(icon: Icon, tint: Color, modifier: Modifier = Modifier, stroke: Float = 1.8f) {
    Canvas(modifier) {
        val k = size.minDimension / 24f
        scale(k, k, pivot = Offset.Zero) {
            val p = path(icon)
            if (icon == Icon.PLAY) drawPath(p, tint, style = Fill)
            else drawPath(p, tint, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private fun path(icon: Icon): Path = Path().apply {
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) { moveTo(x1, y1); lineTo(x2, y2) }
    fun rr(l: Float, t: Float, r: Float, b: Float, rad: Float) = addRoundRect(RoundRect(l, t, r, b, CornerRadius(rad)))
    fun circle(cx: Float, cy: Float, r: Float) = addOval(Rect(cx - r, cy - r, cx + r, cy + r))
    when (icon) {
        Icon.HOME -> {
            moveTo(4.5f, 10.2f); lineTo(12f, 4f); lineTo(19.5f, 10.2f); lineTo(19.5f, 19.5f)
            lineTo(14.5f, 19.5f); lineTo(14.5f, 14.5f); lineTo(9.5f, 14.5f); lineTo(9.5f, 19.5f); lineTo(4.5f, 19.5f); close()
        }
        Icon.FILM -> {
            rr(3.5f, 3.5f, 20.5f, 20.5f, 2.5f)
            line(8f, 3.5f, 8f, 20.5f); line(16f, 3.5f, 16f, 20.5f); line(3.5f, 12f, 20.5f, 12f)
            line(3.5f, 7.75f, 8f, 7.75f); line(3.5f, 16.25f, 8f, 16.25f); line(16f, 7.75f, 20.5f, 7.75f); line(16f, 16.25f, 20.5f, 16.25f)
        }
        Icon.TV -> { rr(3f, 7.5f, 21f, 19.5f, 2.5f); moveTo(8f, 3f); lineTo(12f, 7.5f); lineTo(16f, 3f) }
        Icon.BOOKMARK -> {
            moveTo(6f, 20.5f); lineTo(6f, 5.5f); quadraticTo(6f, 3.5f, 8f, 3.5f); lineTo(16f, 3.5f)
            quadraticTo(18f, 3.5f, 18f, 5.5f); lineTo(18f, 20.5f); lineTo(12f, 16.3f); close()
        }
        Icon.SEARCH -> { circle(10.5f, 10.5f, 6.5f); line(15.3f, 15.3f, 20f, 20f) }
        Icon.GEAR -> {
            val rIn = 7.1f; val rOut = 9.4f
            fun pt(r: Float, deg: Float): Offset {
                val rad = Math.toRadians(deg.toDouble())
                return Offset(12f + r * cos(rad).toFloat(), 12f + r * sin(rad).toFloat())
            }
            for (i in 0 until 8) {
                val a = i * 45f
                val p0 = pt(rIn, a - 15f); val p1 = pt(rOut, a - 9f); val p2 = pt(rOut, a + 9f); val p3 = pt(rIn, a + 15f)
                if (i == 0) moveTo(p0.x, p0.y) else lineTo(p0.x, p0.y)
                lineTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y)
                arcTo(Rect(12f - rIn, 12f - rIn, 12f + rIn, 12f + rIn), a + 15f, 15f, false)
            }
            close()
            circle(12f, 12f, 3f)
        }
        Icon.PLAY -> {
            moveTo(7.5f, 4.8f); quadraticTo(7.5f, 3.6f, 8.6f, 4.2f); lineTo(19.2f, 11f)
            quadraticTo(20.2f, 12f, 19.2f, 13f); lineTo(8.6f, 19.8f); quadraticTo(7.5f, 20.4f, 7.5f, 19.2f); close()
        }
        Icon.INFO -> { circle(12f, 12f, 8.5f); line(12f, 11f, 12f, 16.5f); circle(12f, 7.6f, 0.4f) }
        Icon.PLUS -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
        Icon.CHECK -> { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) }
        Icon.LEFT -> { moveTo(14.5f, 5f); lineTo(7.5f, 12f); lineTo(14.5f, 19f) }
        Icon.RIGHT -> { moveTo(9.5f, 5f); lineTo(16.5f, 12f); lineTo(9.5f, 19f) }
        Icon.UP -> { moveTo(5f, 15f); lineTo(12f, 8f); lineTo(19f, 15f) }
        Icon.DOWN -> { moveTo(5f, 9f); lineTo(12f, 16f); lineTo(19f, 9f) }
        Icon.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
        Icon.BELL -> {
            moveTo(6.5f, 16.8f); lineTo(6.5f, 11f); quadraticTo(6.5f, 5.6f, 12f, 5.6f); quadraticTo(17.5f, 5.6f, 17.5f, 11f); lineTo(17.5f, 16.8f)
            line(4.8f, 16.8f, 19.2f, 16.8f)
            line(12f, 3.4f, 12f, 5.6f)
            arcTo(Rect(9.9f, 16.5f, 14.1f, 20.7f), 0f, 180f, true)
        }
        Icon.PERSON -> { circle(12f, 8.5f, 4f); moveTo(4.5f, 20f); quadraticTo(5.5f, 13.8f, 12f, 13.8f); quadraticTo(18.5f, 13.8f, 19.5f, 20f) }
    }
}
