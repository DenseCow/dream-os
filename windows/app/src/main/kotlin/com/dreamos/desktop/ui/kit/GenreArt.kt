package com.dreamos.desktop.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb

/**
 * A genre or seasonal glyph ([GenreGlyphs], [SeasonGlyphs], shared shape for shape with the Fire Stick and iPhone):
 * built once per name and tint in its 100 x 100 box, drawn scaled. Static: nothing animates.
 */
@Composable
fun GenreGlyph(name: String, tint: Color, modifier: Modifier = Modifier) {
    val pieces = remember(name, tint) { build(name, tint.toArgb()) }
    Canvas(modifier) {
        val s = size.minDimension / 100f
        translate(size.width / 2 - 50f * s, size.height / 2 - 50f * s) {
            scale(s, s, pivot = Offset.Zero) {
                val shadow = Color(0x2E08080A)
                for (p in pieces) if (p.shadow) for (dy in floatArrayOf(1.6f, 3.2f)) translate(0f, dy) {
                    drawPath(p.path, shadow)
                    if (p.line > 0f) drawPath(p.path, shadow, style = Stroke(p.line, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                for (p in pieces) {
                    p.fill?.let { drawPath(p.path, it) }
                    p.lineBrush?.let { drawPath(p.path, it, style = Stroke(p.line, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                }
            }
        }
    }
}

private class Piece(val path: Path, val fill: Brush?, val lineBrush: Brush?, val line: Float, val shadow: Boolean)

private fun build(name: String, tint: Int): List<Piece> =
    (SeasonGlyphs.build(name, tint) ?: GenreGlyphs.build(name, tint)).map { part ->
        val path = Path()
        part.path.replay(ComposeSink(path))
        if (part.path.rotate != 0f) {
            val m = Matrix().apply {
                translate(part.path.px, part.path.py)
                rotateZ(part.path.rotate)
                translate(-part.path.px, -part.path.py)
            }
            path.transform(m)
        }
        val shade: Brush? = when {
            part.top == 0 -> null
            part.top != part.bottom -> Brush.verticalGradient(listOf(Color(part.top), Color(part.bottom)), 16f, 84f)
            else -> androidx.compose.ui.graphics.SolidColor(Color(part.top))
        }
        val line = when {
            part.line <= 0f -> null
            part.lineColor == GenreGlyphs.FILL -> shade ?: androidx.compose.ui.graphics.SolidColor(Color(part.top))
            else -> androidx.compose.ui.graphics.SolidColor(Color(part.lineColor))
        }
        Piece(path, shade, line, part.line, part.shadow)
    }

private class ComposeSink(val p: Path) : GenreGlyphs.Sink {
    override fun moveTo(x: Float, y: Float) = p.moveTo(x, y)
    override fun lineTo(x: Float, y: Float) = p.lineTo(x, y)
    override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = p.quadraticTo(x1, y1, x, y)
    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = p.cubicTo(x1, y1, x2, y2, x, y)
    override fun close() = p.close()
    override fun circle(cx: Float, cy: Float, r: Float) = p.addOval(Rect(cx - r, cy - r, cx + r, cy + r))
    override fun oval(l: Float, t: Float, r: Float, b: Float) = p.addOval(Rect(l, t, r, b))
    override fun roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) = p.addRoundRect(RoundRect(l, t, r, b, CornerRadius(rad)))
    override fun arc(l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float, connect: Boolean) =
        p.arcTo(Rect(l, t, r, b), start, sweep, !connect)
}
