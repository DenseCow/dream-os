package com.firehome.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator

/**
 * My List's glyph on the title page: "+" when the title isn't saved, a green check when it is. Drawn on
 * the canvas at the screen's own resolution (crisp on the pearl focus box and on the dark glass alike).
 *
 * Adding: the + turns 45° and fades while the check draws itself stroke by stroke (a path trim, ~260 ms),
 * with a small spring pop (1.0 → 1.15 → 1.0) and one soft green ring that grows and fades. Removing runs
 * the same motion backwards (no ring, no pop). Nothing animates outside a change; the view keeps its size.
 *
 * The + follows the button's ink ([setInk]); the check stays green in both states: fresh green on the dark
 * glass, a deeper green on pearl for contrast.
 */
class MyListMark(ctx: Context, saved: Boolean) : View(ctx) {
    private val density = ctx.resources.displayMetrics.density
    /** 0 = "+", 1 = check. */
    private var t = if (saved) 1f else 0f
    /** The ring's own clock (0..1), only while adding. */
    private var ring = -1f
    private var ink = 0xFFFFFFFF.toInt()
    private var onPearl = false
    private var anim: ValueAnimator? = null
    private var ringAnim: ValueAnimator? = null
    var saved = saved
        private set

    private val plusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.3f * density
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.6f * density
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val check = Path()
    private val part = Path()
    private val measure = PathMeasure()
    private var checkLen = 0f

    init {
        // The ring reaches a little past the glyph: drawn outside its bounds (the button row doesn't clip).
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val s = minOf(w, h).toFloat()
        val ox = (w - s) / 2f
        val oy = (h - s) / 2f
        // A check on the 24-unit grid: the short stroke down, then the long one up to the right.
        check.reset()
        check.moveTo(ox + s * 4.8f / 24f, oy + s * 12.6f / 24f)
        check.lineTo(ox + s * 9.6f / 24f, oy + s * 17.4f / 24f)
        check.lineTo(ox + s * 19.4f / 24f, oy + s * 6.8f / 24f)
        measure.setPath(check, false)
        checkLen = measure.length
    }

    /** The button's ink ([onPearl]: the focused pearl box, dark ink). */
    fun setInk(c: Int, pearl: Boolean) {
        if (c == ink && pearl == onPearl) return
        ink = c
        onPearl = pearl
        invalidate()
    }

    /** Shows [on] at once (a rebuild), no motion. */
    fun set(on: Boolean) {
        anim?.cancel(); ringAnim?.cancel()
        saved = on
        t = if (on) 1f else 0f
        ring = -1f
        scaleX = 1f; scaleY = 1f
        invalidate()
    }

    /** Moves to [on] with the add (or remove) motion, from wherever a motion under way has got to. */
    fun animateTo(on: Boolean) {
        if (on == saved && anim?.isRunning != true) { set(on); return }
        saved = on
        anim?.cancel()
        val from = t
        val to = if (on) 1f else 0f
        anim = ValueAnimator.ofFloat(from, to).apply {
            duration = ((if (on) ADD_MS else REMOVE_MS) * kotlin.math.abs(to - from)).toLong().coerceAtLeast(60L)
            interpolator = LinearInterpolator() // each part eases on its own window of the clock
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
        if (on) {
            pop()
            ringAnim?.cancel()
            ringAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = 70
                duration = RING_MS
                interpolator = Ui.EASE
                addUpdateListener { ring = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) { ring = -1f; invalidate() }
                })
                start()
            }
        } else {
            ringAnim?.cancel()
            ring = -1f
            animate().cancel()
            animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
    }

    /** The spring pop: up to 1.15 quickly, then settles back with a touch of overshoot. */
    private fun pop() {
        animate().cancel()
        animate().scaleX(POP).scaleY(POP).setStartDelay(0).setDuration(130).setInterpolator(Ui.EASE).withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2.6f)).start()
        }.start()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel(); ringAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val s = minOf(w, h)
        val green = if (onPearl) GREEN_DEEP else GREEN

        // The ring: soft, grows out from the glyph and fades (adding only).
        if (ring in 0f..1f) {
            ringPaint.color = green
            ringPaint.alpha = (255 * 0.55f * (1f - ring)).toInt()
            canvas.drawCircle(cx, cy, s * (0.42f + 0.5f * ring), ringPaint)
        }

        // The +: turns 45° and fades over the first part of the clock.
        val pf = ease(t / 0.55f)
        if (pf < 1f) {
            plusPaint.color = ink
            plusPaint.alpha = (255 * (1f - pf)).toInt()
            val arm = s * 7f / 24f
            canvas.save()
            canvas.rotate(45f * pf, cx, cy)
            canvas.drawLine(cx - arm, cy, cx + arm, cy, plusPaint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, plusPaint)
            canvas.restore()
        }

        // The check draws itself along its path over the later part of the clock (~260 ms of the add).
        val cf = ease((t - 0.25f) / 0.62f)
        if (cf > 0f && checkLen > 0f) {
            checkPaint.color = green
            part.reset()
            measure.getSegment(0f, checkLen * cf, part, true)
            canvas.drawPath(part, checkPaint)
        }
    }

    private fun ease(x: Float): Float { val v = x.coerceIn(0f, 1f); return 1f - (1f - v) * (1f - v) * (1f - v) }

    companion object {
        /** Fresh green on the dark glass. */
        const val GREEN = 0xFF4ADE80.toInt()
        /** A deeper green on the pearl focus box (reads against the light fill). */
        const val GREEN_DEEP = 0xFF15A34A.toInt()
        private const val ADD_MS = 420L
        private const val REMOVE_MS = 300L
        private const val RING_MS = 460L
        private const val POP = 1.15f
    }
}
