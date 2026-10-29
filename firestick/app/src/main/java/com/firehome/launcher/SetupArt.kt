package com.firehome.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.TextView

/**
 * The setup's illustrations in the Dream OS line-art look ([GlyphPen]): one monoline pearl icon with
 * round caps and joins, lots of air, no fills, no shading, centered on a dark-glass disc ringed in a
 * thin rainbow with a faint rainbow glow behind (the brand signature). Green and red appear only as
 * status (Final Check's result). Drawn in a 100 × 100 box, crisp at any size, no bitmaps.
 *
 * Motion: Final Check's ring turns while the check runs, then its arc closes into a check (or a
 * mark); Done's ring turns slowly. Both only while on screen. Everything else is still (no per-frame
 * work at all).
 */
class SetupArt(ctx: Context, private val kind: Kind) : View(ctx) {
    enum class Kind { CLOCK, ACCOUNTS, SCRAPERS, CHECK, PERSONALIZE, DONE, INTRO, PLAYBACK, SPEED, PROFILES, BACKUP, UNLOCK }
    enum class CheckState { RUNNING, OK, FAIL }

    private val g = GlyphPen(weight = 3f, ring = 1.7f)
    private val rect = RectF()

    /** Final Check only: what the ring shows. */
    var checkState = CheckState.RUNNING
        set(v) {
            if (field == v) return
            field = v
            close?.cancel()
            if (v == CheckState.RUNNING) { closing = 0f; invalidate(); tick(); return }
            close = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 700
                interpolator = Ui.SETTLE
                addUpdateListener { closing = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    private var closing = 0f
    private var close: ValueAnimator? = null
    private val startedAt = SystemClock.uptimeMillis()
    private var turn = 0f

    private val animated get() = kind == Kind.DONE || (kind == Kind.CHECK && checkState == CheckState.RUNNING)

    override fun onAttachedToWindow() { super.onAttachedToWindow(); tick() }
    override fun onDetachedFromWindow() { close?.cancel(); super.onDetachedFromWindow() }
    private fun tick() { if (animated && isAttachedToWindow) postInvalidateOnAnimation() }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height) / 100f
        if (s <= 0f) return
        val save = canvas.save()
        canvas.translate(width / 2f - 50f * s, height / 2f - 50f * s)
        canvas.scale(s, s)
        // The ring turns only while something is happening; otherwise it stays where it stopped.
        if (animated) turn = ((SystemClock.uptimeMillis() - startedAt) / 1000f * TURN_DEG_PER_S) % 360f
        g.backdrop(canvas, turn, glow = if (kind == Kind.DONE) 1.5f else 1f)
        when (kind) {
            Kind.CLOCK -> clock(canvas)
            Kind.ACCOUNTS -> accounts(canvas)
            Kind.SCRAPERS -> scrapers(canvas)
            Kind.CHECK -> check(canvas)
            Kind.PERSONALIZE -> personalize(canvas)
            Kind.DONE -> g.pen(canvas, GlyphPen.play(50f, 50f, 26f))
            Kind.INTRO -> intro(canvas)
            Kind.PLAYBACK -> playback(canvas)
            Kind.SPEED -> speed(canvas)
            Kind.PROFILES -> profiles(canvas)
            Kind.BACKUP -> backup(canvas)
            Kind.UNLOCK -> unlock(canvas)
        }
        canvas.restoreToCount(save)
        if (animated) postInvalidateDelayed(33)
    }

    // ---- the glyphs (100 × 100; the disc is r 39, the icon keeps to about 28..72) ----

    /** Your Time Zone: a globe with a meridian and the equator, a small clock in front. */
    private fun clock(c: Canvas) {
        g.pen(c, GlyphPen.circle(45f, 46f, 16f))
        val m = Path().apply { addOval(RectF(38.5f, 30f, 51.5f, 62f), Path.Direction.CW); moveTo(29f, 46f); lineTo(61f, 46f) }
        g.pen(c, m, alpha = 110, w = g.weight * 0.8f)
        val face = GlyphPen.circle(60f, 60f, 11f)
        g.knock(c, face)
        g.pen(c, face)
        g.pen(c, Path().apply { moveTo(60f, 54.5f); lineTo(60f, 60f); lineTo(64.5f, 62.5f) })
    }

    /** Connect Your Accounts: a key (bow with a hole, a shaft, two teeth), at an angle. */
    private fun accounts(c: Canvas) {
        c.save()
        c.rotate(-45f, 50f, 50f)
        g.pen(c, GlyphPen.circle(37f, 50f, 9.5f))
        g.pen(c, GlyphPen.circle(37f, 50f, 3f), alpha = 150, w = g.weight * 0.8f)
        g.pen(c, Path().apply {
            moveTo(46.5f, 50f); lineTo(71f, 50f)
            moveTo(64f, 50f); lineTo(64f, 57f)
            moveTo(70f, 50f); lineTo(70f, 55.5f)
        })
        c.restore()
    }

    /** Set Up Scrapers: a magnifier over a play symbol (searching for streams). */
    private fun scrapers(c: Canvas) {
        g.pen(c, GlyphPen.circle(46f, 46f, 13.5f))
        g.pen(c, GlyphPen.play(47f, 46f, 11f), alpha = 200, w = g.weight * 0.9f)
        g.pen(c, Path().apply { moveTo(56f, 56f); lineTo(67f, 67f) }, w = g.weight * 1.3f)
    }

    // The check's paths, built once: it draws every frame (30 fps) while the check runs and while it closes.
    private val checkTrack by lazy { GlyphPen.circle(50f, 50f, 19f) }
    private val okMark by lazy { PathMeasure(Path().apply { moveTo(41.5f, 50.5f); lineTo(47.5f, 56.5f); lineTo(59f, 44.5f) }, false) }
    private val failMark by lazy { PathMeasure(Path().apply { moveTo(50f, 41f); lineTo(50f, 52f) }, false) }
    private val markPart = Path()

    /** Final Check: a progress arc turning on a faint track, closing into a green check (or a red mark). */
    private fun check(c: Canvas) {
        rect.set(31f, 31f, 69f, 69f)
        if (checkState == CheckState.RUNNING) {
            val t = (SystemClock.uptimeMillis() - startedAt) / 1000f
            g.pen(c, checkTrack, alpha = 40)
            g.arc(c, rect, (t * 220f) % 360f, 70f + 40f * Math.sin(t * 2.0).toFloat())
            return
        }
        val tone = if (checkState == CheckState.OK) GREEN else RED
        g.arc(c, rect, -90f, 360f * closing, color = tone)
        val pm = if (checkState == CheckState.OK) okMark else failMark
        markPart.reset()
        pm.getSegment(0f, pm.length * ((closing - 0.35f) / 0.65f).coerceIn(0f, 1f), markPart, true)
        g.pen(c, markPart, color = tone, w = g.weight * 1.15f)
        if (checkState == CheckState.FAIL && closing > 0.9f) g.dot(c, 50f, 59f, g.weight * 0.7f, tone)
    }

    /** Personalize: a person and three sliders. */
    private fun personalize(c: Canvas) {
        g.pen(c, GlyphPen.person(37f, 33f, 0.8f))
        for ((y, x) in listOf(39f to 66f, 48.5f to 57f, 58f to 63f)) {
            g.pen(c, Path().apply { moveTo(52f, y); lineTo(72f, y) }, alpha = 120)
            g.dot(c, x, y, g.weight * 1.25f)
        }
    }

    /** Before You Start: a page with a folded corner and a check badge. */
    private fun intro(c: Canvas) {
        val page = Path().apply {
            moveTo(54f, 28f); lineTo(39f, 28f); arcTo(RectF(34f, 28f, 42f, 36f), 270f, -90f, false)
            lineTo(34f, 67f); arcTo(RectF(34f, 64f, 42f, 72f), 180f, -90f, false)
            lineTo(57f, 72f); arcTo(RectF(54f, 64f, 62f, 72f), 90f, -90f, false)
            lineTo(62f, 36f); close()
            moveTo(54f, 28f); lineTo(54f, 36f); lineTo(62f, 36f)
        }
        g.pen(c, page)
        g.pen(c, Path().apply {
            moveTo(40f, 44f); lineTo(55f, 44f)
            moveTo(40f, 51f); lineTo(55f, 51f)
            moveTo(40f, 58f); lineTo(48f, 58f)
        }, alpha = 120)
        val badge = GlyphPen.circle(62f, 64f, 9.5f)
        g.knock(c, badge, 2f)
        g.pen(c, badge)
        g.pen(c, Path().apply { moveTo(58f, 64.5f); lineTo(61f, 67.5f); lineTo(66f, 61.5f) })
    }

    /** Playback Preferences: a screen with Play and quality bars on it. */
    private fun playback(c: Canvas) {
        g.pen(c, GlyphPen.roundRect(28f, 32f, 72f, 61f, 5f))
        g.pen(c, GlyphPen.play(41f, 46.5f, 12f))
        g.pen(c, Path().apply {
            moveTo(55f, 53f); lineTo(55f, 49f)
            moveTo(60.5f, 53f); lineTo(60.5f, 44.5f)
            moveTo(66f, 53f); lineTo(66f, 40f)
        })
        g.pen(c, Path().apply { moveTo(42f, 68f); lineTo(58f, 68f) }, alpha = 140)
    }

    /** Speed test: a gauge whose arc and needle show [gauge] (0..1). */
    var gauge = 0f
        set(v) { val n = v.coerceIn(0f, 1f); if (field != n) { field = n; invalidate() } }

    private fun speed(c: Canvas) {
        rect.set(32f, 34f, 68f, 70f)
        g.arc(c, rect, 150f, 240f, alpha = 46)
        if (gauge > 0.01f) g.arc(c, rect, 150f, 240f * gauge)
        val ticks = Path()
        for (i in 0..6) {
            val a = Math.toRadians(150.0 + i * 40.0)
            val cos = Math.cos(a).toFloat(); val sin = Math.sin(a).toFloat()
            ticks.moveTo(50f + 13.5f * cos, 52f + 13.5f * sin); ticks.lineTo(50f + 11.5f * cos, 52f + 11.5f * sin)
        }
        g.pen(c, ticks, alpha = 110, w = g.weight * 0.6f)
        val a = Math.toRadians(150.0 + 240.0 * gauge)
        g.pen(c, Path().apply { moveTo(50f, 52f); lineTo(50f + 11.5f * Math.cos(a).toFloat(), 52f + 11.5f * Math.sin(a).toFloat()) })
        g.dot(c, 50f, 52f, g.weight * 1.1f)
    }

    /** Who's Watching: three people, the one in front in full pearl. */
    private fun profiles(c: Canvas) {
        g.pen(c, GlyphPen.person(35f, 35f, 0.65f), alpha = 130)
        g.pen(c, GlyphPen.person(65f, 35f, 0.65f), alpha = 130)
        val front = GlyphPen.person(50f, 38f, 0.9f)
        g.knock(c, front, 2.6f)
        g.pen(c, front)
    }

    /** Move to a New Stick: two TVs, an arrow carrying everything from the old one (quiet) to the new one. */
    private fun backup(c: Canvas) {
        fun tv(l: Float, t: Float, alpha: Int) {
            g.pen(c, GlyphPen.roundRect(l, t, l + 18f, t + 13f, 3f), alpha)
            g.pen(c, Path().apply { moveTo(l + 5f, t + 17f); lineTo(l + 13f, t + 17f) }, alpha)
        }
        tv(27f, 49f, 130)
        tv(55f, 49f, 255)
        g.pen(c, Path().apply {
            moveTo(36f, 43f); cubicTo(40f, 33f, 60f, 33f, 64f, 43f)
            moveTo(59.4f, 40f); lineTo(64f, 43f); lineTo(65.3f, 37.6f)
        })
    }

    /** Unlock Dream OS Features: an open padlock (the shackle lifted out on one side). */
    private fun unlock(c: Canvas) {
        g.pen(c, GlyphPen.roundRect(36f, 48f, 64f, 70f, 5.5f))
        g.pen(c, Path().apply { moveTo(42f, 48f); lineTo(42f, 37f); arcTo(RectF(42f, 29f, 58f, 45f), 180f, 180f, false); lineTo(58f, 39f) })
        g.pen(c, Path().apply { moveTo(50f, 55f); lineTo(50f, 62f) })
    }

    companion object {
        /** How fast the ring turns while something is happening (degrees a second): slow. */
        private const val TURN_DEG_PER_S = 30f
        val PEARL = PearlFocus.PEARL
        val INK = PearlFocus.INK
        val GREEN = 0xFF30D158.toInt()
        val RED = 0xFFFF453A.toInt()

        /** A service's badge: a neutral dark-glass disc with its monogram ("RD"), never a brand logo. */
        fun monogram(ctx: Context, letters: String, sizeDp: Int = 40): TextView = TextView(ctx).apply {
            text = letters
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.WHITE)
            textSize = sizeDp * 0.34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.04f
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(46, 46, 53), Color.rgb(18, 18, 22))).apply {
                shape = GradientDrawable.OVAL
                setStroke(maxOf(1, Ui.dp(ctx, 1)), Color.argb(56, 255, 255, 255))
            }
        }
    }
}

/**
 * The Dream OS line-art pen, shared by [SetupArt] and the Welcome page's [WelcomeGlyph]. Everything is
 * in a 100 × 100 box: [backdrop] lays the dark-glass disc (r [R]) with its thin rainbow ring and faint
 * rainbow glow; [pen] strokes a monoline shape in pearl ([weight] wide, round caps and joins);
 * [knock] clears a gap around a shape in front so overlapping lines never touch.
 */
internal class GlyphPen(val weight: Float, private val ring: Float) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val glass = LinearGradient(0f, 11f, 0f, 89f, 0xFF2A2A31.toInt(), 0xFF0B0B0E.toInt(), Shader.TileMode.CLAMP)
    private val light = RadialGradient(50f, 14f, 46f, Color.argb(22, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    private val rainbow = SweepGradient(50f, 50f, Rainbow.COLORS + Rainbow.COLORS[0], null)
    private val spin = Matrix()
    private var turned = Float.NaN

    /** The disc: a soft rainbow glow, the dark glass lit faintly from the top, the thin ring. [turn] in degrees. */
    fun backdrop(c: Canvas, turn: Float = 0f, glow: Float = 1f) {
        if (turn != turned) { turned = turn; spin.setRotate(turn, 50f, 50f); rainbow.setLocalMatrix(spin) }
        line.shader = rainbow; line.color = Color.WHITE
        for ((w, a) in GLOW) {
            line.strokeWidth = w; line.alpha = (a * glow).toInt().coerceAtMost(255)
            c.drawCircle(50f, 50f, R + 1f, line)
        }
        fill.shader = glass; c.drawCircle(50f, 50f, R, fill)
        fill.shader = light; c.drawCircle(50f, 50f, R, fill)
        fill.shader = null
        line.alpha = 255; line.strokeWidth = ring
        c.drawCircle(50f, 50f, R, line)
        line.shader = null
    }

    private fun tone(color: Int, alpha: Int) {
        line.shader = null; line.color = color; line.alpha = Color.alpha(color) * alpha / 255
    }

    fun pen(c: Canvas, p: Path, alpha: Int = 255, color: Int = PearlFocus.PEARL, w: Float = weight) {
        tone(color, alpha); line.strokeWidth = w
        c.drawPath(p, line)
    }

    fun arc(c: Canvas, oval: RectF, start: Float, sweep: Float, alpha: Int = 255, color: Int = PearlFocus.PEARL, w: Float = weight) {
        tone(color, alpha); line.strokeWidth = w
        c.drawArc(oval, start, sweep, false, line)
    }

    fun dot(c: Canvas, x: Float, y: Float, r: Float, color: Int = PearlFocus.PEARL) {
        fill.shader = null; fill.color = color
        c.drawCircle(x, y, r, fill)
    }

    /** The glass around [p] ([gap] wider than the line on each side), so a shape in front sits clear of what's behind. */
    fun knock(c: Canvas, p: Path, gap: Float = 3.2f) {
        fill.shader = glass; c.drawPath(p, fill); fill.shader = null
        line.shader = glass; line.color = Color.WHITE; line.strokeWidth = weight + gap * 2f
        c.drawPath(p, line)
        line.shader = null
    }

    companion object {
        /** The disc's radius; the ring sits on its edge and the glow just outside. */
        const val R = 39f
        private val GLOW = listOf(12f to 20, 6f to 46)

        fun circle(cx: Float, cy: Float, r: Float) = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }

        fun roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) = Path().apply { addRoundRect(RectF(l, t, r, b), rad, rad, Path.Direction.CW) }

        /** Head and shoulders: a round head over an open arch. [s] = 1 is 24 wide and 31 tall from [top]. */
        fun person(cx: Float, top: Float, s: Float) = Path().apply {
            addCircle(cx, top + 6.5f * s, 6.5f * s, Path.Direction.CW)
            moveTo(cx - 12f * s, top + 31f * s)
            cubicTo(cx - 12f * s, top + 22f * s, cx - 6.5f * s, top + 17.5f * s, cx, top + 17.5f * s)
            cubicTo(cx + 6.5f * s, top + 17.5f * s, cx + 12f * s, top + 22f * s, cx + 12f * s, top + 31f * s)
        }

        /** A play triangle [h] tall, optically centered on ([cx], [cy]). */
        fun play(cx: Float, cy: Float, h: Float) = Path().apply {
            val w = h * 0.9f
            val x0 = cx - w * 0.42f
            moveTo(x0, cy - h / 2f); lineTo(x0 + w, cy); lineTo(x0, cy + h / 2f); close()
        }
    }
}
