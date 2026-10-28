package com.firehome.launcher

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
import android.graphics.drawable.Drawable

/**
 * Settings category glyphs that [LineIcon] doesn't have, drawn the same way (a 24-unit grid, one
 * stroked path, round caps, the same stroke). Ids start at [FIRST] so they never meet LineIcon's
 * own constants; [PremiumSettings] picks this class for any icon id at or above it ([of]).
 */
class SettingsGlyph(shape: Int) : Drawable() {
    companion object {
        const val FIRST = 100
        const val PERSON = 100; const val KEY = 101; const val PLAY = 102; const val SLIDERS = 103
        const val WIFI = 104; const val INFO = 105; const val SPARKLE = 106; const val CHIP = 107
        const val CLOCK = 108
        private const val STROKE = 1.8f

        /** The drawable for a category / row icon id: a [SettingsGlyph] from [FIRST] on, else a [LineIcon]. */
        fun of(id: Int): Drawable = if (id >= FIRST) SettingsGlyph(id) else LineIcon(id)

        private fun build(shape: Int): Path = Path().apply {
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) { moveTo(x1, y1); lineTo(x2, y2) }
            fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) =
                arcTo(RectF(cx - r, cy - r, cx + r, cy + r), start, sweep, true)
            when (shape) {
                PERSON -> {
                    addCircle(12f, 8.2f, 3.9f, Path.Direction.CW)
                    moveTo(4.5f, 20f); quadTo(4.8f, 13.6f, 12f, 13.6f); quadTo(19.2f, 13.6f, 19.5f, 20f)
                }
                KEY -> {
                    addCircle(8f, 12f, 4.2f, Path.Direction.CW)
                    line(12.2f, 12f, 20.5f, 12f)
                    line(17.2f, 12f, 17.2f, 15.2f); line(20f, 12f, 20f, 14.4f)
                }
                PLAY -> {
                    addCircle(12f, 12f, 8.6f, Path.Direction.CW)
                    moveTo(10.2f, 8.6f); lineTo(15.6f, 12f); lineTo(10.2f, 15.4f); close()
                }
                SLIDERS -> {
                    line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 20f, 17f)
                    addCircle(9f, 7f, 1.9f, Path.Direction.CW)
                    addCircle(15.5f, 12f, 1.9f, Path.Direction.CW)
                    addCircle(7.5f, 17f, 1.9f, Path.Direction.CW)
                }
                WIFI -> {
                    arc(12f, 18.5f, 13f, -130f, 80f)
                    arc(12f, 18.5f, 8.6f, -130f, 80f)
                    arc(12f, 18.5f, 4.3f, -130f, 80f)
                    addCircle(12f, 18.5f, 0.9f, Path.Direction.CW)
                }
                INFO -> {
                    addCircle(12f, 12f, 8.6f, Path.Direction.CW)
                    line(12f, 11f, 12f, 16.5f)
                    addCircle(12f, 7.8f, 0.5f, Path.Direction.CW)
                }
                SPARKLE -> {
                    moveTo(12f, 3.5f); quadTo(12.9f, 11.1f, 20.5f, 12f); quadTo(12.9f, 12.9f, 12f, 20.5f)
                    quadTo(11.1f, 12.9f, 3.5f, 12f); quadTo(11.1f, 11.1f, 12f, 3.5f); close()
                }
                CLOCK -> {
                    addCircle(12f, 12f, 8.6f, Path.Direction.CW)
                    line(12f, 12f, 12f, 7f); line(12f, 12f, 15.4f, 14f)
                }
                CHIP -> {
                    addRoundRect(RectF(6.5f, 6.5f, 17.5f, 17.5f), 2f, 2f, Path.Direction.CW)
                    addRoundRect(RectF(9.8f, 9.8f, 14.2f, 14.2f), 0.8f, 0.8f, Path.Direction.CW)
                    for (p in listOf(9.5f, 12f, 14.5f)) {
                        line(p, 3.5f, p, 6.5f); line(p, 17.5f, p, 20.5f)
                        line(3.5f, p, 6.5f, p); line(17.5f, p, 20.5f, p)
                    }
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
    private var tints: ColorStateList? = null

    override fun onBoundsChange(b: Rect) {
        val m = Matrix()
        m.setScale(b.width() / 24f, b.height() / 24f)
        m.postTranslate(b.left.toFloat(), b.top.toFloat())
        unit.transform(m, path)
        paint.strokeWidth = STROKE * b.width() / 24f
    }

    override fun draw(canvas: Canvas) = canvas.drawPath(path, paint)

    override fun setTintList(tint: ColorStateList?) { tints = tint; applyTint(state) }
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
