package com.firehome.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * A Browse by Genre card's glyph ([GenreGlyphs]) as a Drawable. Paths and gradients are built
 * once, in a 100 × 100 box that's scaled to the bounds; nothing animates, so the renderer records
 * it once and a card's focus lift only moves the cached display list. No bitmaps.
 */
class GenreArt(genre: String, tint: Int) : Drawable() {
    private class Piece(val path: Path, val fill: Paint?, val line: Paint?, val shadow: Boolean)

    private val pieces: List<Piece>
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 10, 6, 24); style = Paint.Style.FILL }
    private val shadowLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(46, 10, 6, 24); style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }

    init {
        pieces = (SeasonGlyphs.build(genre, tint) ?: GenreGlyphs.build(genre, tint)).map { part ->
            val path = Path()
            part.path.replay(AndroidSink(path))
            if (part.path.rotate != 0f) path.transform(Matrix().apply { setRotate(part.path.rotate, part.path.px, part.path.py) })
            val shader = if (part.top != 0 && part.top != part.bottom)
                LinearGradient(0f, 16f, 0f, 84f, part.top, part.bottom, Shader.TileMode.CLAMP) else null
            val fill = if (part.top == 0) null else Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                if (shader != null) this.shader = shader else color = part.top
            }
            val line = if (part.line <= 0f) null else Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = part.line
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                if (part.lineColor == GenreGlyphs.FILL) { if (shader != null) this.shader = shader else color = part.top } else color = part.lineColor
            }
            Piece(path, fill, line, part.shadow)
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val s = minOf(b.width(), b.height()) / 100f
        val save = canvas.save()
        canvas.translate(b.exactCenterX() - 50f * s, b.exactCenterY() - 50f * s)
        canvas.scale(s, s)
        // A faint soft shadow straight down under each silhouette (two offsets read as a blur).
        for (p in pieces) if (p.shadow) {
            for (dy in SHADOW) {
                canvas.save()
                canvas.translate(0f, dy)
                canvas.drawPath(p.path, shadowPaint)
                p.line?.let { shadowLine.strokeWidth = it.strokeWidth; canvas.drawPath(p.path, shadowLine) }
                canvas.restore()
            }
        }
        for (p in pieces) {
            p.fill?.let { canvas.drawPath(p.path, it) }
            p.line?.let { canvas.drawPath(p.path, it) }
        }
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private class AndroidSink(val p: Path) : GenreGlyphs.Sink {
        override fun moveTo(x: Float, y: Float) = p.moveTo(x, y)
        override fun lineTo(x: Float, y: Float) = p.lineTo(x, y)
        override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = p.quadTo(x1, y1, x, y)
        override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = p.cubicTo(x1, y1, x2, y2, x, y)
        override fun close() = p.close()
        override fun circle(cx: Float, cy: Float, r: Float) = p.addCircle(cx, cy, r, Path.Direction.CW)
        override fun oval(l: Float, t: Float, r: Float, b: Float) = p.addOval(RectF(l, t, r, b), Path.Direction.CW)
        override fun roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) = p.addRoundRect(RectF(l, t, r, b), rad, rad, Path.Direction.CW)
        override fun arc(l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float, connect: Boolean) =
            p.arcTo(RectF(l, t, r, b), start, sweep, !connect)
    }

    companion object {
        private val SHADOW = floatArrayOf(1.6f, 3.2f)
    }
}
