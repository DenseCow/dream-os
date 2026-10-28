package com.firehome.launcher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SweepGradient
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Dream Player's timeline: remaining track, buffered and played portions (the highlight glow),
 * marker ticks (intro / credits) and, while scrubbing, a lighter "ghost" run from the playhead to
 * the target with the thumb parked on the target. Everything is preallocated; onDraw only measures.
 * Focus grows the bar and brings the thumb in with the shared spring.
 */
class PlayerScrubber(ctx: Context, private val kit: PremiumKit) : View(ctx) {
    var durationMs = 0L; private set
    var positionMs = 0L; private set
    var bufferedMs = 0L; private set
    /** Scrub target, or -1 when not scrubbing. */
    var targetMs = -1L; private set
    private var markers = LongArray(0)

    private val d = ctx.resources.displayMetrics.density
    private val thinH = 4f * d
    private val thickH = 8f * d
    private val thumbR = 8.5f * d
    private val track = RectF()
    private val part = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.alpha(kit.text, 0.20f) }
    private val bufPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.alpha(kit.text, 0.36f) }
    private val playPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.alpha(Ui.mix(kit.select, kit.text, 0.35f), 0.55f) }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.alpha(kit.text, 0.9f) }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = kit.text }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.alpha(kit.select, 0.30f) }
    private var focusT = 0f
    private var focusAnim: ValueAnimator? = null

    init {
        isFocusable = true
        id = generateViewId()
        // Nothing else draws here; no offscreen layers needed for the fade of the controls.
    }

    override fun hasOverlappingRendering() = false

    /*
     * The played run wears Play's hue, the shared rainbow clock ([Rainbow.fadeColor]), read whenever the bar
     * draws anyway (the time ticks twice a second while the controls show, and on focus or scrub moves): it
     * follows the clock with no animator of its own.
     */
    private fun hue(c: Int) {
        playPaint.color = c
        ghostPaint.color = Ui.alpha(Ui.mix(c, kit.text, 0.45f), 0.55f)
        haloPaint.color = Ui.alpha(c, 0.34f)
    }

    fun set(position: Long, duration: Long, buffered: Long) {
        if (position == positionMs && duration == durationMs && buffered == bufferedMs) return
        positionMs = position
        durationMs = duration
        bufferedMs = buffered
        invalidate()
    }

    fun setTarget(ms: Long) {
        if (ms == targetMs) return
        targetMs = ms
        invalidate()
    }

    fun setMarkers(m: Markers?) {
        markers = if (m == null) LongArray(0) else listOfNotNull(m.introStartMs?.takeIf { it > 0 }, m.introEndMs, m.recapEndMs, m.creditsStartMs)
            .distinct().toLongArray()
        invalidate()
    }

    /** X (in this view) of [ms] on the track. */
    fun xOf(ms: Long): Float {
        val l = paddingLeft + thumbR
        val r = width - paddingRight - thumbR
        if (durationMs <= 0) return l
        return l + (r - l) * (ms.coerceIn(0, durationMs).toFloat() / durationMs)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        focusAnim?.cancel()
        focusAnim = ValueAnimator.ofFloat(focusT, if (gainFocus) 1f else 0f).apply {
            duration = if (gainFocus) Ui.FOCUS_IN else Ui.FOCUS_OUT
            interpolator = if (gainFocus) Ui.SPRING else Ui.SETTLE
            addUpdateListener { focusT = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        focusAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        hue(Rainbow.fadeColor())
        val t = focusT.coerceIn(0f, 1.1f)
        val h = thinH + (thickH - thinH) * t
        val cy = height / 2f
        val l = paddingLeft + thumbR
        val r = width - paddingRight - thumbR
        if (r <= l) return
        val rad = h / 2f
        track.set(l, cy - rad, r, cy + rad)
        c.drawRoundRect(track, rad, rad, trackPaint)
        if (durationMs > 0) {
            val bx = xOf(bufferedMs)
            if (bx > l + 1f) { part.set(l, track.top, bx, track.bottom); c.drawRoundRect(part, rad, rad, bufPaint) }
            val px = xOf(positionMs)
            val scrubbing = targetMs >= 0
            if (scrubbing) {
                val tx = xOf(targetMs)
                val playEnd = minOf(px, tx)
                if (playEnd > l) { part.set(l, track.top, maxOf(l + h, playEnd), track.bottom); c.drawRoundRect(part, rad, rad, playPaint) }
                val gl = minOf(px, tx)
                val gr = maxOf(px, tx)
                if (gr - gl > 1f) { part.set(gl, track.top, maxOf(gr, gl + h), track.bottom); c.drawRoundRect(part, rad, rad, ghostPaint) }
            } else if (px > l) {
                part.set(l, track.top, maxOf(l + h, px), track.bottom)
                c.drawRoundRect(part, rad, rad, playPaint)
            }
            // Marker ticks: slim pearl capsules standing just above the bar.
            val tw = 1.5f * d
            for (m in markers) {
                val x = xOf(m)
                part.set(x - tw, track.top - 5f * d, x + tw, track.top - 1.5f * d)
                c.drawRoundRect(part, tw, tw, tickPaint)
            }
            val thumbT = if (scrubbing) 1f else t
            if (thumbT > 0.02f) {
                val x = if (scrubbing) xOf(targetMs) else px
                val rr = thumbR * thumbT.coerceAtMost(1.05f)
                if (scrubbing) c.drawCircle(x, cy, rr * 1.75f, haloPaint)
                c.drawCircle(x, cy, rr, thumbPaint)
            }
        }
    }
}

/** Calm buffering ring: one arc in the Dream rainbow, rotating only while it is visible. */
class PlayerSpinner(ctx: Context, kit: PremiumKit) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val oval = RectF()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 10, 10, 12) } // neutral smoked disc (rebrand: no purple)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
        color = Ui.alpha(kit.text, 0.14f)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * d
        color = Ui.mix(kit.select, kit.blush, 0.4f)
    }
    private var spin: ObjectAnimator? = null

    override fun hasOverlappingRendering() = false

    fun begin() {
        if (spin?.isRunning == true) return
        spin = ObjectAnimator.ofFloat(this, View.ROTATION, 0f, 360f).apply {
            duration = 1100
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    fun end() {
        spin?.cancel()
        spin = null
    }

    override fun onDetachedFromWindow() {
        end()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        c.drawCircle(cx, cy, r, bgPaint)
        val ar = r * 0.48f
        oval.set(cx - ar, cy - ar, cx + ar, cy + ar)
        c.drawCircle(cx, cy, ar, ringPaint)
        c.drawArc(oval, -90f, 100f, false, arcPaint)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        // The arc runs through the Dream rainbow; the whole view rotates, so the colors travel with it.
        val n = Rainbow.COLORS.size
        val pos = FloatArray(n) { it / (n - 1f) * (100f / 360f) } // the whole spectrum inside the 100-degree arc
        arcPaint.shader = SweepGradient(w / 2f, h / 2f, Rainbow.COLORS, pos).apply {
            setLocalMatrix(Matrix().apply { setRotate(-90f, w / 2f, h / 2f) })
        }
    }
}

/**
 * Player glyphs on the same 24-unit grid and stroke as [LineIcon] (rounded caps and joins), so
 * the player reads as part of Dream OS. Play/pause/next are solid shapes with rounded corners.
 */
class PlayerIcon(private val shape: Int) : Drawable() {
    companion object {
        const val PLAY = 0; const val PAUSE = 1; const val EPISODES = 2; const val SUBTITLES = 3; const val SPEED = 4
        const val INFO = 5; const val RESTART = 6; const val NEXT = 7; const val CHECK = 8; const val BACK10 = 9
        const val FWD10 = 10; const val SKIP = 11; const val AUDIO = 12; const val SOURCE = 13; const val CAST = 14
        private const val STROKE = 1.8f

        private fun solid(shape: Int) = shape == PLAY || shape == PAUSE || shape == NEXT || shape == SKIP

        private fun build(shape: Int): Path = Path().apply {
            fun rr(l: Float, t: Float, r: Float, b: Float, rad: Float) = addRoundRect(RectF(l, t, r, b), rad, rad, Path.Direction.CW)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) { moveTo(x1, y1); lineTo(x2, y2) }
            fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) =
                arcTo(RectF(cx - r, cy - r, cx + r, cy + r), start, sweep, true)
            when (shape) {
                PLAY -> { moveTo(8f, 5.6f); lineTo(18.6f, 12f); lineTo(8f, 18.4f); close() }
                PAUSE -> { rr(6.5f, 5.2f, 9.9f, 18.8f, 1.4f); rr(14.1f, 5.2f, 17.5f, 18.8f, 1.4f) }
                NEXT, SKIP -> { moveTo(5.8f, 6.2f); lineTo(14.6f, 12f); lineTo(5.8f, 17.8f); close(); rr(16.4f, 6.2f, 18.6f, 17.8f, 1.1f) }
                EPISODES -> {
                    rr(3.5f, 8f, 16.5f, 19.5f, 2.2f)
                    moveTo(7f, 4.8f); lineTo(18f, 4.8f); quadTo(20f, 4.8f, 20f, 6.8f); lineTo(20f, 15.5f)
                }
                SUBTITLES -> {
                    rr(3f, 5.5f, 21f, 18.5f, 3f)
                    line(6.8f, 10.6f, 10.8f, 10.6f); line(13.2f, 10.6f, 17.2f, 10.6f)
                    line(6.8f, 14.2f, 9.2f, 14.2f); line(11.6f, 14.2f, 17.2f, 14.2f)
                }
                AUDIO -> {
                    moveTo(4f, 9.5f); lineTo(7.5f, 9.5f); lineTo(12f, 5.5f); lineTo(12f, 18.5f); lineTo(7.5f, 14.5f); lineTo(4f, 14.5f); close()
                    arc(12.5f, 12f, 4f, -50f, 100f); arc(12.5f, 12f, 7.5f, -50f, 100f)
                }
                SPEED -> {
                    arc(12f, 13.5f, 8.5f, 140f, 260f)
                    addCircle(12f, 13.5f, 1.6f, Path.Direction.CW)
                    line(13.2f, 12.3f, 16.2f, 9.3f)
                }
                INFO -> {
                    addCircle(12f, 12f, 9f, Path.Direction.CW)
                    line(12f, 11f, 12f, 16.5f)
                    addCircle(12f, 7.7f, 0.15f, Path.Direction.CW)
                }
                RESTART, BACK10, FWD10 -> {
                    // Circular arrow: shaft runs clockwise from the top, the head points back (left).
                    arc(12f, 12.6f, 7.4f, 270f, 290f)
                    moveTo(14.4f, 2.9f); lineTo(11.4f, 5.2f); lineTo(14.4f, 7.5f)
                }
                CHECK -> { moveTo(5f, 12.6f); lineTo(9.8f, 17.2f); lineTo(19f, 7.4f) }
                CAST -> {
                    // A person: the head, and the shoulders as an open arc (X-Ray's cast).
                    addCircle(12f, 8.6f, 3.6f, Path.Direction.CW)
                    arc(12f, 21.2f, 7.6f, 200f, 140f)
                }
                SOURCE -> {
                    // Swap arrows: one stream out, another in.
                    line(4.5f, 8.5f, 18.5f, 8.5f); moveTo(15f, 5f); lineTo(18.5f, 8.5f); lineTo(15f, 12f)
                    line(19.5f, 15.5f, 5.5f, 15.5f); moveTo(9f, 12f); lineTo(5.5f, 15.5f); lineTo(9f, 19f)
                }
            }
            if (shape == FWD10) transform(Matrix().apply { setScale(-1f, 1f, 12f, 12f) })
        }
    }

    private val unit = build(shape)
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = if (solid(shape)) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    override fun onBoundsChange(b: Rect) {
        val m = Matrix()
        m.setScale(b.width() / 24f, b.height() / 24f)
        m.postTranslate(b.left.toFloat(), b.top.toFloat())
        unit.transform(m, path)
        paint.strokeWidth = (if (solid(shape)) 1.6f else STROKE) * b.width() / 24f
    }

    override fun draw(canvas: Canvas) = canvas.drawPath(path, paint)

    private var tints: ColorStateList? = null

    override fun setTintList(tint: ColorStateList?) {
        tints = tint
        applyTint(state)
    }

    override fun setTint(tintColor: Int) = setTintList(ColorStateList.valueOf(tintColor))

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
