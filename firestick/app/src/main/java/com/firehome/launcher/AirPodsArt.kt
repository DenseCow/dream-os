package com.firehome.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import kotlin.math.PI
import kotlin.math.sin

/**
 * Setup's AirPods illustration, drawn in code (an original drawing, no Apple artwork): a pair of
 * stemmed earbuds beside an open charging case, pearl white with soft shading, over a static glow
 * in the theme's highlight.
 *
 * [turn] plays one slow "turntable" swing (~2.4 s): both parts turn on the Y axis together, the
 * earbuds a little further than the case, for a touch of depth. Each part is its own view and only
 * holds a hardware layer while it turns; otherwise everything is still. Paths and shaders are built
 * on size changes only, never while drawing.
 */
class AirPodsArt(ctx: Context, glow: Int) : FrameLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val buds = Buds(ctx)
    private val case = Case(ctx)
    private var anim: ValueAnimator? = null

    init {
        background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 150 * d
            colors = intArrayOf(Ui.alpha(glow, 0.26f), Ui.alpha(glow, 0.08f), Color.TRANSPARENT)
        }
        clipChildren = false
        val dist = 9000 * d
        for (v in listOf(buds, case)) v.cameraDistance = dist
        addView(buds, LayoutParams((128 * d).toInt(), (140 * d).toInt(), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = (14 * d).toInt() })
        addView(case, LayoutParams((134 * d).toInt(), (140 * d).toInt(), Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = (14 * d).toInt() })
    }

    /** One slow turntable swing; restarts if one is already running. */
    fun turn() {
        anim?.cancel()
        buds.setLayerType(LAYER_TYPE_HARDWARE, null)
        case.setLayerType(LAYER_TYPE_HARDWARE, null)
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400
            interpolator = Ui.EASE
            addUpdateListener { a ->
                val e = a.animatedFraction
                // A damped swing that comes to rest facing forward.
                val ang = -30f * sin(2.0 * PI * e).toFloat() * (1f - 0.4f * e)
                buds.rotationY = ang * 1.12f
                case.rotationY = ang * 0.86f
                buds.translationX = -ang * 0.22f * d
                case.translationX = ang * 0.16f * d
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = rest()
                override fun onAnimationCancel(animation: Animator) = rest()
            })
            start()
        }
    }

    private fun rest() {
        for (v in listOf(buds, case)) {
            v.rotationY = 0f
            v.translationX = 0f
            v.setLayerType(LAYER_TYPE_NONE, null)
        }
    }

    /** Stops any turn and drops the layers (the step is leaving). */
    fun stop() {
        anim?.cancel()
        anim = null
        rest()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    // ---- the drawing ----

    private abstract class Part(ctx: Context) : View(ctx) {
        val d = ctx.resources.displayMetrics.density
        val shell = Paint(Paint.ANTI_ALIAS_FLAG)
        val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2C2F38") }
        val silver = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B4B8C3") }
        val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = RectF()
        fun p(v: Float) = v * d
        /** Pearl shading across [w] px: bright on the left, cool gray at the far edge. */
        fun pearl(x0: Float, x1: Float) = LinearGradient(x0, 0f, x1, 0f,
            intArrayOf(Color.WHITE, Color.parseColor("#F1F2F6"), Color.parseColor("#C9CCD5")), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        fun shadowAt(cx: Float, cy: Float, rx: Float) {
            shadow.shader = RadialGradient(cx, cy, rx, Color.argb(90, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
    }

    /** Two earbuds leaning toward each other. Built in a 128 x 140 dp box. */
    private class Buds(ctx: Context) : Part(ctx) {
        private val bud = Path()
        private val grille = RectF()
        private val tip = RectF()
        private val gloss = RectF()

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            bud.reset()
            // One bud in its own 56 x 112 dp box: a rounded head with the stem running down its outer side.
            bud.addOval(RectF(p(2f), p(2f), p(50f), p(44f)), Path.Direction.CW)
            val stem = Path().apply { addRoundRect(RectF(p(31f), p(26f), p(47f), p(108f)), p(8f), p(8f), Path.Direction.CW) }
            bud.op(stem, Path.Op.UNION)
            grille.set(p(9f), p(15f), p(24f), p(31f))
            tip.set(p(32f), p(98f), p(46f), p(107f))
            gloss.set(p(10f), p(6f), p(30f), p(14f))
            shell.shader = pearl(0f, p(50f))
            shadowAt(w / 2f, h - p(10f), p(64f))
        }

        override fun onDraw(c: Canvas) {
            c.drawOval(p(6f), height - p(18f), width - p(6f), height - p(2f), shadow)
            drawBud(c, p(6f), p(10f), -7f, false)
            drawBud(c, width - p(6f), p(4f), 7f, true)
        }

        private fun drawBud(c: Canvas, x: Float, y: Float, tilt: Float, mirror: Boolean) {
            c.save()
            c.translate(x, y)
            if (mirror) c.scale(-1f, 1f)
            c.rotate(tilt, p(26f), p(56f))
            c.drawPath(bud, shell)
            c.drawOval(grille, dark)
            c.drawRoundRect(tip, p(7f), p(7f), silver)
            c.drawOval(gloss, shine)
            c.restore()
        }
    }

    /** The open charging case: lid tipped back, two wells for the earbuds. Built in a 134 x 140 dp box. */
    private class Case(ctx: Context) : Part(ctx) {
        private val lidInside = Paint(Paint.ANTI_ALIAS_FLAG)
        private val seam = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D3D6DE") }
        private val lid = RectF()
        private val lidRim = RectF()
        private val body = RectF()
        private val wellL = RectF()
        private val wellR = RectF()
        private val hinge = RectF()

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val l = p(12f)
            val rr = w - p(12f)
            lid.set(l + p(4f), p(10f), rr - p(4f), p(50f))
            lidRim.set(l + p(10f), p(16f), rr - p(10f), p(46f))
            body.set(l, p(56f), rr, p(128f))
            val cx = w / 2f
            wellL.set(cx - p(36f), p(60f), cx - p(10f), p(71f))
            wellR.set(cx + p(10f), p(60f), cx + p(36f), p(71f))
            hinge.set(cx - p(9f), p(49f), cx + p(9f), p(57f))
            shell.shader = pearl(l, rr)
            lidInside.shader = LinearGradient(0f, lid.top, 0f, lid.bottom, Color.parseColor("#E7E8EE"), Color.parseColor("#FAFAFC"), Shader.TileMode.CLAMP)
            shadowAt(cx, h - p(8f), p(74f))
        }

        override fun onDraw(c: Canvas) {
            c.drawOval(p(10f), height - p(16f), width - p(10f), height - p(1f), shadow)
            // Lid, seen from inside as it tips back.
            c.drawRoundRect(lid, p(20f), p(20f), shell)
            c.drawRoundRect(lidRim, p(15f), p(15f), lidInside)
            c.drawRoundRect(hinge, p(3f), p(3f), silver)
            // Body with its two wells.
            c.drawRoundRect(body, p(28f), p(28f), shell)
            c.drawRect(body.left + p(18f), body.top + p(17f), body.right - p(18f), body.top + p(18f), seam)
            c.drawOval(wellL, dark)
            c.drawOval(wellR, dark)
            c.drawCircle(width / 2f, body.top + p(46f), p(2.5f), silver)
            c.drawOval(body.left + p(12f), body.top + p(24f), body.left + p(22f), body.top + p(50f), shine)
        }
    }
}

/**
 * A ring showing the AirPods connection (never battery, which this Fire TV can't read): an empty
 * track when not connected; a slow partial sweep while connecting (only for as long as that lasts);
 * a green ring that fills with an eased sweep and a check mark once connected.
 */
class ConnectionRing(ctx: Context, private val k: PremiumKit) : View(ctx) {
    enum class State { IDLE, CONNECTING, CONNECTED }

    private val d = ctx.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6 * d; color = Ui.alpha(k.text, 0.12f) }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6 * d; strokeCap = Paint.Cap.ROUND }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5 * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = GREEN
    }
    private val rect = RectF()
    private val check = Path()
    private val icon: Drawable? = try { ctx.getDrawable(R.drawable.ic_headphones)?.mutate()?.apply { setTint(Ui.alpha(k.text, 0.5f)) } } catch (e: Exception) { null }

    var state = State.IDLE
        private set
    private var fill = 0f
    private var mark = 0f
    private var spin = 0f
    private var anim: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val inset = track.strokeWidth / 2f + d
        rect.set(inset, inset, w - inset, h - inset)
        val cx = w / 2f
        val cy = h / 2f
        val s = w * 0.17f
        check.reset()
        check.moveTo(cx - s, cy + s * 0.05f)
        check.lineTo(cx - s * 0.3f, cy + s * 0.7f)
        check.lineTo(cx + s, cy - s * 0.6f)
        val ic = (w * 0.34f).toInt()
        icon?.setBounds((cx - ic / 2).toInt(), (cy - ic / 2).toInt(), (cx + ic / 2).toInt(), (cy + ic / 2).toInt())
    }

    /** Moves to [s]; [animate] = sweep in (connected) rather than appear filled. */
    fun show(s: State, animate: Boolean = true) {
        if (s == state && (s != State.CONNECTED || fill >= 1f)) return
        anim?.cancel()
        anim = null
        state = s
        when (s) {
            State.IDLE -> { fill = 0f; mark = 0f; invalidate() }
            State.CONNECTING -> {
                fill = 0f; mark = 0f
                // A slow partial sweep, only while connecting (stopped by the next state or [stop]).
                anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1800
                    repeatCount = 8 // ~16 s at most: never outlives a connection attempt
                    interpolator = LinearInterpolator()
                    addUpdateListener { spin = it.animatedFraction; invalidate() }
                    start()
                }
            }
            State.CONNECTED -> {
                if (!animate) { fill = 1f; mark = 1f; invalidate(); return }
                fill = 0f; mark = 0f
                anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1100
                    interpolator = Ui.EASE
                    addUpdateListener {
                        val e = it.animatedFraction
                        fill = e
                        mark = ((e - 0.55f) / 0.45f).coerceIn(0f, 1f)
                        invalidate()
                    }
                    start()
                }
            }
        }
    }

    fun stop() {
        anim?.cancel()
        anim = null
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        c.drawOval(rect, track)
        when (state) {
            State.CONNECTING -> {
                arc.color = k.select
                c.drawArc(rect, spin * 360f - 90f, 70f, false, arc)
            }
            State.CONNECTED -> {
                arc.color = GREEN
                if (fill > 0f) c.drawArc(rect, -90f, 360f * fill, false, arc)
            }
            State.IDLE -> {}
        }
        if (mark > 0f) {
            tick.alpha = (mark * 255).toInt()
            c.save()
            val sc = 0.8f + 0.2f * mark
            c.scale(sc, sc, width / 2f, height / 2f)
            c.drawPath(check, tick)
            c.restore()
        }
        icon?.let {
            val a = ((1f - mark) * 255).toInt()
            if (a > 0) { it.alpha = a; it.draw(c) }
        }
    }

    private companion object {
        val GREEN = Color.parseColor("#4ADE80")
    }
}
