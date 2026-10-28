package com.firehome.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/**
 * Soft sparkles that drift upward and twinkle. Only used during the boot intro, when nothing
 * else is animating, so it can afford ~30 fps of small draws.
 */
class SparkleView(ctx: Context) : View(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private val rnd = Random(42)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    // One unit-radius glow per tint, positioned per sparkle with a reused matrix (no per-frame allocations).
    private val glows = Array(TINTS.size) { i ->
        val c = TINTS[i]
        RadialGradient(0f, 0f, 1f, Color.argb(85, Color.red(c), Color.green(c), Color.blue(c)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }
    private val m = Matrix()
    private var running = false
    private var startedAt = 0L

    // x, y (0..1), radius dp, rise speed, twinkle phase, drift phase, tint index
    private val sparkles = Array(46) {
        floatArrayOf(
            rnd.nextFloat(), rnd.nextFloat(), 0.8f + rnd.nextFloat() * 2.2f,
            0.010f + rnd.nextFloat() * 0.025f, rnd.nextFloat() * 6.28f, rnd.nextFloat() * 6.28f,
            rnd.nextInt(TINTS.size).toFloat(),
        )
    }

    fun start() {
        if (running) return
        running = true
        startedAt = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    fun stop() {
        running = false
    }

    override fun onDraw(canvas: Canvas) {
        if (!running) return
        val t = (SystemClock.uptimeMillis() - startedAt) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()
        val fadeIn = (t / 1.5f).coerceIn(0f, 1f)
        for (s in sparkles) {
            val y = ((s[1] - t * s[3]) % 1f + 1f) % 1f
            val x = s[0] + 0.012f * sin(t * 0.7f + s[5])
            val twinkle = 0.25f + 0.75f * (0.5f + 0.5f * sin(t * 2.2f + s[4]))
            // Fade out near the top/bottom edges so sparkles never pop in or out.
            val edge = (minOf(y, 1f - y) * 6f).coerceIn(0f, 1f)
            val a = (twinkle * edge * fadeIn * 255).toInt()
            if (a <= 2) continue
            val cx = x * w
            val cy = y * h
            val r = s[2] * density
            val g = glows[s[6].toInt()]
            m.setScale(r * 5, r * 5)
            m.postTranslate(cx, cy)
            g.setLocalMatrix(m)
            glow.shader = g
            glow.alpha = a
            canvas.drawCircle(cx, cy, r * 5, glow)
            paint.color = Color.argb(a, 255, 255, 255)
            canvas.drawCircle(cx, cy, r, paint)
        }
        postInvalidateDelayed(33)
    }

    companion object {
        /** The logo's rainbow (plus white, so some stay plain starlight): halos tint, the cores stay white. */
        private val TINTS = Rainbow.COLORS + Color.WHITE
    }
}
