package com.firehome.launcher

import android.animation.ValueAnimator
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Dream OS's rainbow: the vivid glow palette (Dream Cinema's focus colors) as text that flows, for the
 * moments that used to be flat green (the boot screen's status word and version, the player curtain's
 * Now Playing). Only the spanned characters take the rainbow. Moving it is a shader matrix change plus
 * one invalidate per frame: no relayout, no layers, nothing for the stick's GPU to redo but that line.
 */
object Rainbow {
    val COLORS = intArrayOf(
        0xFF2F7BFF.toInt(), 0xFF8B5CFF.toInt(), 0xFFFF3DC8.toInt(), 0xFFFF4D6D.toInt(),
        0xFFFF8A1F.toInt(), 0xFFFFD21F.toInt(), 0xFF7CFF4F.toInt(), 0xFF19E3D6.toInt(),
    )

    /** One full cycle of the palette over [periodPx], repeating, so it tiles seamlessly as it moves. */
    fun gradient(periodPx: Float) = LinearGradient(0f, 0f, periodPx, 0f, COLORS + COLORS[0], null, Shader.TileMode.REPEAT)

    private class Span(val shader: Shader) : CharacterStyle(), UpdateAppearance {
        override fun updateDrawState(tp: TextPaint) { tp.shader = shader }
    }

    private val running = WeakHashMap<TextView, ValueAnimator>()

    /** How long the fade rests on each hue on its way round ([fade]). */
    private const val FADE_HUE_MS = 2_200L

    /** The fade's color right now: one solid hue easing into the next, from one clock so every Play button agrees. */
    fun fadeColor(now: Long = android.os.SystemClock.uptimeMillis()): Int {
        val n = COLORS.size
        val pos = (now % (FADE_HUE_MS * n)) / FADE_HUE_MS.toFloat()
        val i = pos.toInt()
        val t = pos - i
        val eased = t * t * (3 - 2 * t) // linger on each hue, glide between them
        return android.animation.ArgbEvaluator().evaluate(eased, COLORS[i], COLORS[(i + 1) % n]) as Int
    }

    /**
     * Readable ink on [fill]: white or the dark ink ([PearlFocus.INK]), whichever has the higher WCAG contrast
     * ratio against it (never a blend: a mid-gray between them reads on neither). Called on every step of the
     * fade, so the ink follows the hue.
     */
    fun inkOn(fill: Int): Int {
        val l = luminance(fill)
        val onWhite = 1.05 / (l + 0.05)
        val onInk = (l + 0.05) / (INK_LUM + 0.05)
        return if (onInk >= onWhite) PearlFocus.INK else android.graphics.Color.WHITE
    }

    /** WCAG relative luminance of [c] (sRGB channels linearised). */
    private fun luminance(c: Int): Double {
        fun lin(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin(android.graphics.Color.red(c)) + 0.7152 * lin(android.graphics.Color.green(c)) + 0.0722 * lin(android.graphics.Color.blue(c))
    }

    private val INK_LUM by lazy { luminance(PearlFocus.INK) }

    /** Play's resting fill: pearl white (the rainbow only runs while it has focus). */
    const val PLAY_REST = 0xFFFBF8FF.toInt()
    private const val BLEND_MS = 260L
    /** The fade's own step once blended in (~15 a second), used only where Cinema's shared clock is stopped. */
    private const val STEP_MS = 66L

    /** Play's fill, switched by [Fade.focus]. */
    class Fade internal constructor(private val view: View, private val fill: android.graphics.drawable.GradientDrawable, private val ink: (Int) -> Unit) {
        private var focused = false
        private var since = 0L
        private var from = PLAY_REST
        // Frame by frame (vsync, in the frames the focus lift draws anyway) only for the short white <-> rainbow
        // blend. Then it steps on Cinema's shared glow clock (CinemaStyle.tick, every 50 ms), in the very
        // frames that re-tint the focus glow, the tab underline, the wordmark, the hero dots and the drift:
        // one invalidate pass per tick, never a frame of its own (its own 66 ms timer used to add frames
        // between the clock's). Its own timer is only the fallback where that clock doesn't run: Home paused
        // (the player's Up Next card in front of it).
        private var blending = false
        private var onClock = false
        private var timing = false
        private val self = java.lang.ref.WeakReference(this)
        private val blendFrame = Runnable { blendStep() }
        private val timer = object : Runnable {
            override fun run() {
                if (!timing) return
                step()
                if (timing) view.postDelayed(this, STEP_MS)
            }
        }

        /** The color on the fill now (0 until the first paint: init always paints). */
        private var shown = 0

        init {
            paint(PLAY_REST)
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { if (focused || shown != PLAY_REST) run() }
                override fun onViewDetachedFromWindow(v: View) { halt() }
            })
        }

        private fun paint(c: Int) { if (c == shown) return; shown = c; fill.setColor(c); ink(inkOn(c)) }

        /** Focus on: eases from white into the fading rainbow. Focus off: eases back to white, then stops. */
        fun focus(on: Boolean) {
            if (on == focused) return
            focused = on
            from = shown
            since = android.os.SystemClock.uptimeMillis()
            run()
        }

        private fun run() {
            if (!view.isAttachedToWindow) return
            if (!blending) { blending = true; view.postOnAnimation(blendFrame) }
            if (!focused || onClock || timing) return
            // Only under Dream Cinema, whose shell starts and stops that clock with the screen. The callback holds
            // the fade weakly (the clock's map is keyed weakly by the view, which it must not pin).
            if (CinemaStyle.clockRunning) { onClock = true; CinemaStyle.followGlow(view) { _, c -> self.get()?.clockStep(c) } }
            else { timing = true; view.postDelayed(timer, STEP_MS) }
        }

        private fun halt() {
            blending = false
            view.removeCallbacks(blendFrame)
            timing = false
            view.removeCallbacks(timer)
            if (onClock) { onClock = false; CinemaStyle.unfollowGlow(view) }
        }

        private fun blendStep() {
            if (!blending) return
            if (step()) view.postOnAnimation(blendFrame) else blending = false
        }

        /** The shared clock's step, in its color (so Play and every glowing border agree to the pixel). */
        private fun clockStep(c: Int) { if (!blending) step(c) }

        /** Paints the fill for now; true while the white <-> rainbow blend is still under way. */
        private fun step(clock: Int? = null): Boolean {
            val now = android.os.SystemClock.uptimeMillis()
            val t = ((now - since) / BLEND_MS.toFloat()).coerceIn(0f, 1f)
            val target = if (focused) clock ?: fadeColor(now) else PLAY_REST
            paint(if (t >= 1f) target else android.animation.ArgbEvaluator().evaluate(t, from, target) as Int)
            if (!focused && t >= 1f) halt() // resting white: nothing runs
            return t < 1f
        }
    }

    /**
     * Play's fill: white at rest; while focused, [fill] (a solid GradientDrawable) slowly fades through the
     * rainbow one hue at a time, and [ink] gets the matching readable text/icon color. Call [Fade.focus] from
     * the button's focus listener. Only the focused Play button updates, on Cinema's shared clock; it pauses off screen.
     */
    fun fade(view: View, fill: android.graphics.drawable.GradientDrawable, ink: (Int) -> Unit) = Fade(view, fill, ink)

    /** [text] with [start, end) in a still rainbow over [periodPx] (the version number). */
    fun still(text: CharSequence, start: Int, end: Int, periodPx: Float): CharSequence =
        SpannableString(text).apply { setSpan(Span(gradient(periodPx)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

    /**
     * [text] with [start, end) in a rainbow that flows across it every [cycleMs] while [view] shows it.
     * Set the result as [view]'s text. A later call for the same view replaces the earlier flow, and it
     * stops by itself when the view leaves the window.
     */
    fun flow(view: TextView, text: CharSequence, start: Int, end: Int, periodPx: Float, cycleMs: Long = 2600): CharSequence {
        val shader = gradient(periodPx)
        val out = SpannableString(text).apply { setSpan(Span(shader), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        running.remove(view)?.cancel()
        val m = Matrix()
        val anim = ValueAnimator.ofFloat(0f, periodPx).apply {
            duration = cycleMs
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                m.setTranslate(it.animatedValue as Float, 0f)
                shader.setLocalMatrix(m)
                view.invalidate()
            }
        }
        running[view] = anim
        if (view.isAttachedToWindow) anim.start()
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { if (running[view] === anim && !anim.isStarted) anim.start() }
            override fun onViewDetachedFromWindow(v: View) {
                anim.cancel()
                if (running[view] === anim) running.remove(view)
                v.removeOnAttachStateChangeListener(this)
            }
        })
        return out
    }
}
