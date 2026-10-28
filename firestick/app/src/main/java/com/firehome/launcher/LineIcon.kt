package com.firehome.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

/**
 * Crisp stroked icon drawn from a 24-unit grid. The path is mapped to pixel space once per
 * bounds change, so drawing is a single drawPath with no canvas scaling.
 */
class LineIcon(private val shape: Int) : Drawable() {
    companion object {
        const val HOME = 0; const val FILM = 1; const val TV = 2; const val LIVE = 3; const val APPS = 4
        const val BOOKMARK = 5; const val SEARCH = 6; const val GEAR = 7; const val GAUGE = 8; const val HEADPHONES = 9
        const val BELL = 10; const val CLOCK = 11
        private const val STROKE = 1.8f // in grid units

        private fun build(shape: Int): Path = Path().apply {
            fun rr(l: Float, t: Float, r: Float, b: Float, rad: Float) = addRoundRect(RectF(l, t, r, b), rad, rad, Path.Direction.CW)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) { moveTo(x1, y1); lineTo(x2, y2) }
            fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) =
                arcTo(RectF(cx - r, cy - r, cx + r, cy + r), start, sweep, true)
            when (shape) {
                HOME -> {
                    moveTo(4.5f, 10.2f); lineTo(12f, 4f); lineTo(19.5f, 10.2f); lineTo(19.5f, 19.5f)
                    lineTo(14.5f, 19.5f); lineTo(14.5f, 14.5f); lineTo(9.5f, 14.5f); lineTo(9.5f, 19.5f)
                    lineTo(4.5f, 19.5f); close()
                }
                FILM -> {
                    rr(3.5f, 3.5f, 20.5f, 20.5f, 2.5f)
                    line(8f, 3.5f, 8f, 20.5f); line(16f, 3.5f, 16f, 20.5f)
                    line(3.5f, 12f, 20.5f, 12f)
                    line(3.5f, 7.75f, 8f, 7.75f); line(3.5f, 16.25f, 8f, 16.25f)
                    line(16f, 7.75f, 20.5f, 7.75f); line(16f, 16.25f, 20.5f, 16.25f)
                }
                TV -> {
                    rr(3f, 7.5f, 21f, 19.5f, 2.5f)
                    moveTo(8f, 3f); lineTo(12f, 7.5f); lineTo(16f, 3f)
                }
                LIVE -> {
                    addCircle(12f, 12f, 2f, Path.Direction.CW)
                    arc(12f, 12f, 5.2f, -45f, 90f); arc(12f, 12f, 5.2f, 135f, 90f)
                    arc(12f, 12f, 8.8f, -45f, 90f); arc(12f, 12f, 8.8f, 135f, 90f)
                }
                APPS -> {
                    rr(4f, 4f, 10.5f, 10.5f, 1.8f); rr(13.5f, 4f, 20f, 10.5f, 1.8f)
                    rr(4f, 13.5f, 10.5f, 20f, 1.8f); rr(13.5f, 13.5f, 20f, 20f, 1.8f)
                }
                BOOKMARK -> {
                    moveTo(6f, 20.5f); lineTo(6f, 5.5f); quadTo(6f, 3.5f, 8f, 3.5f); lineTo(16f, 3.5f)
                    quadTo(18f, 3.5f, 18f, 5.5f); lineTo(18f, 20.5f); lineTo(12f, 16.3f); close()
                }
                SEARCH -> {
                    addCircle(10.5f, 10.5f, 6.5f, Path.Direction.CW)
                    line(15.3f, 15.3f, 20f, 20f)
                }
                GEAR -> {
                    // Eight blunt teeth around a hub.
                    val rIn = 7.1f; val rOut = 9.4f
                    for (i in 0 until 8) {
                        val a = i * 45f
                        fun pt(r: Float, deg: Float): Pair<Float, Float> {
                            val rad = Math.toRadians(deg.toDouble())
                            return Pair(12f + r * cos(rad).toFloat(), 12f + r * sin(rad).toFloat())
                        }
                        val (x0, y0) = pt(rIn, a - 15f)
                        val (x1, y1) = pt(rOut, a - 9f)
                        val (x2, y2) = pt(rOut, a + 9f)
                        val (x3, y3) = pt(rIn, a + 15f)
                        if (i == 0) moveTo(x0, y0) else lineTo(x0, y0)
                        lineTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3)
                        arcTo(RectF(12f - rIn, 12f - rIn, 12f + rIn, 12f + rIn), a + 15f, 15f, false)
                    }
                    close()
                    addCircle(12f, 12f, 3f, Path.Direction.CW)
                }
                HEADPHONES -> {
                    // Band over the head, then two rounded ear cups.
                    arc(12f, 13f, 8f, 180f, 180f)
                    rr(3.5f, 13f, 8f, 20.5f, 2f)
                    rr(16f, 13f, 20.5f, 20.5f, 2f)
                }
                BELL -> {
                    // A rounded bell on a short stem, its rim, and the clapper under it.
                    moveTo(6.5f, 16.8f); lineTo(6.5f, 11f); quadTo(6.5f, 5.6f, 12f, 5.6f); quadTo(17.5f, 5.6f, 17.5f, 11f); lineTo(17.5f, 16.8f)
                    line(4.8f, 16.8f, 19.2f, 16.8f)
                    line(12f, 3.4f, 12f, 5.6f)
                    arc(12f, 18.6f, 2.1f, 0f, 180f)
                }
                CLOCK -> {
                    addCircle(12f, 12f, 8.5f, Path.Direction.CW)
                    line(12f, 7.2f, 12f, 12f); line(12f, 12f, 15.4f, 14f)
                }
                GAUGE -> {
                    arc(12f, 13.5f, 8.5f, 140f, 260f)
                    addCircle(12f, 13.5f, 1.6f, Path.Direction.CW)
                    line(13.2f, 12.3f, 16f, 9.5f)
                    line(12f, 5f, 12f, 6.6f); line(4.5f, 13.5f, 6.1f, 13.5f); line(17.9f, 13.5f, 19.5f, 13.5f)
                }
            }
        }
    }

    private val unit = build(shape)
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    override fun onBoundsChange(b: Rect) {
        val m = Matrix()
        m.setScale(b.width() / 24f, b.height() / 24f)
        m.postTranslate(b.left.toFloat(), b.top.toFloat())
        unit.transform(m, path)
        paint.strokeWidth = STROKE * b.width() / 24f
    }

    override fun draw(canvas: Canvas) = canvas.drawPath(path, paint)

    private var tints: ColorStateList? = null

    /** Follows state-based tints too (e.g. ink on a focused button's glow). */
    override fun setTintList(tint: ColorStateList?) {
        tints = tint
        applyTint(state)
    }

    override fun isStateful() = tints?.isStateful == true

    override fun onStateChange(state: IntArray) = applyTint(state)

    private fun applyTint(state: IntArray): Boolean {
        val t = tints
        val c = t?.getColorForState(state, t.defaultColor) ?: Color.WHITE
        if (paint.color == c) return false
        paint.color = c
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
