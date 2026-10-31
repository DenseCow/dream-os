package com.firehome.launcher

import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.view.View
import android.widget.TextView

/**
 * Dream OS's rainbow: one color at a time, fading from hue to hue round the glow palette (owner, 2026-10-30: "all
 * should be color cycle"). Never a static spectrum (hues side by side, sweeps, gradients across the palette); the
 * logo artwork is the only place the hues show together. Play's fill, the focus glow, the emblem rings, the setup
 * rail, the badges and the brand words all read [fadeColor], stepped by Cinema's shared clock
 * ([CinemaStyle.followFade]): a color change and an invalidate, no relayout, no layers, no timer of their own.
 */
object Rainbow {
    val COLORS = intArrayOf(
        0xFF2F7BFF.toInt(), 0xFF8B5CFF.toInt(), 0xFFFF3DC8.toInt(), 0xFFFF4D6D.toInt(),
        0xFFFF8A1F.toInt(), 0xFFFFD21F.toInt(), 0xFF7CFF4F.toInt(), 0xFF19E3D6.toInt(),
    )

    /** How long the fade rests on each hue on its way round ([fade]). */
    private const val FADE_HUE_MS = 2_200L

    /** The fade's color right now: one solid hue easing into the next, from one clock so every Play button agrees. */
    fun fadeColor(now: Long = android.os.SystemClock.uptimeMillis()): Int {
        val n = COLORS.size
        val pos = (now % (FADE_HUE_MS * n)) / FADE_HUE_MS.toFloat()
        val i = pos.toInt()
        val t = pos - i
        val eased = t * t * (3 - 2 * t) // linger on each hue, glide between them
        return EVAL.evaluate(eased, COLORS[i], COLORS[(i + 1) % n]) as Int
    }

    private val EVAL = android.animation.ArgbEvaluator()

    /**
     * Readable ink on [fill]: white or [PLAY_INK], whichever has the higher WCAG contrast ratio against it (never a
     * blend: a mid-gray between them reads on neither). Called on every step of the fade, so the ink follows the hue.
     */
    fun inkOn(fill: Int): Int {
        val l = luminance(fill)
        val onWhite = 1.05 / (l + 0.05)
        val onInk = (l + 0.05) / (INK_LUM + 0.05)
        return if (onInk >= onWhite) PLAY_INK else android.graphics.Color.WHITE
    }

    /** WCAG relative luminance of [c] (sRGB channels linearised). */
    private fun luminance(c: Int): Double {
        fun lin(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin(android.graphics.Color.red(c)) + 0.7152 * lin(android.graphics.Color.green(c)) + 0.0722 * lin(android.graphics.Color.blue(c))
    }

    /**
     * The words and icon on the fade: a deeper near-black than [PearlFocus.INK]. The fade's darkest moment is the violet
     * itself (#8A5CFF: ArgbEvaluator blends in linear light on Android 8+; an sRGB blend would dip to #6F65FF), where
     * #16161A gives only 4.34:1; #0A0A0C holds 4.76:1 there (4.69 at #6F65FF) and up to 15.3:1 round the cycle
     * (antislop contrast-check over the whole fade), so 10 sp badge counts and 15 sp words pass AA all the way round.
     */
    const val PLAY_INK = 0xFF0A0A0C.toInt()
    private val INK_LUM by lazy { luminance(PLAY_INK) }

    /** The pearl of a focused row or chip ([PearlFocus.PEARL]); Play itself no longer rests on it. */
    const val PLAY_REST = 0xFFFBF8FF.toInt()
    /** The fade's own step (~15 a second), only for a focused Play where Cinema's shared clock is stopped. */
    private const val STEP_MS = 66L

    /**
     * Play's fill: the fade at all times (owner, 2026-10-30, as the iPhone), in focus or not.
     *
     * At rest it steps on Cinema's shared glow clock ([CinemaStyle.followFade]) in the very frames that re-tint the
     * focus glow, the tab underline and the hero dots: never a frame of its own. The clock touches it only while it
     * is attached, shown and on screen, and only while that clock runs (Home in front, or setup over it): with the
     * screensaver, Dream Player or another app in front, a resting Play holds its hue. Resting, the clock steps at
     * [CinemaStyle.FADE_IDLE_TICK_MS] (8 a second) while anything in the fade shows, else as before.
     *
     * Focused, the halo and lift of its own button carry the focus (the fill is already the rainbow); the fill keeps
     * stepping on the clock, which runs at full rate while the remote is in use. Only where that clock is stopped
     * (the player's Up Next card, Home paused behind it) does a focused Play step on its own timer.
     */
    class Fade internal constructor(private val view: View, private val fill: android.graphics.drawable.GradientDrawable, private val ink: (Int) -> Unit) {
        private var focused = false
        private var timing = false
        private val self = java.lang.ref.WeakReference(this)
        private val timer = object : Runnable {
            override fun run() {
                // The shared clock came back: it steps the fill from now on.
                if (!timing || CinemaStyle.clockRunning) { timing = false; return }
                paint(fadeColor())
                view.postDelayed(this, STEP_MS)
            }
        }

        /** The color on the fill now (0 until the first paint: init always paints). */
        private var shown = 0

        init {
            paint(fadeColor())
            // The callback holds the fade weakly (the clock's map is keyed weakly by the view, which it must not pin).
            CinemaStyle.followFade(view) { _, c -> self.get()?.paint(c) }
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { paint(fadeColor()); CinemaStyle.tick(); ownTimer() }
                override fun onViewDetachedFromWindow(v: View) { timing = false; view.removeCallbacks(timer) }
            })
        }

        private fun paint(c: Int) { if (c == shown) return; shown = c; fill.setColor(c); ink(inkOn(c)) }

        /** Focus moved onto (true) or off this Play: the fill doesn't change, only who steps it when the clock is stopped. */
        fun focus(on: Boolean) {
            if (on == focused) return
            focused = on
            if (on) { CinemaStyle.wake(); ownTimer() } else { timing = false; view.removeCallbacks(timer) }
        }

        private fun ownTimer() {
            if (!focused || timing || CinemaStyle.clockRunning || !view.isAttachedToWindow) return
            timing = true
            view.postDelayed(timer, STEP_MS)
        }
    }

    /**
     * Play's fill: [fill] (a solid GradientDrawable) fades through the rainbow one hue at a time, always, and [ink]
     * gets the matching readable text/icon color. Call [Fade.focus] from the button's focus listener.
     */
    fun fade(view: View, fill: android.graphics.drawable.GradientDrawable, ink: (Int) -> Unit) = Fade(view, fill, ink)

    /** Text in the fade: the color is set on each step of the clock (no relayout, the line just redraws). */
    private class FadeSpan(private val bg: Int) : CharacterStyle(), UpdateAppearance {
        var color = textOn(fadeColor(), bg)
            private set
        fun step(c: Int): Boolean { val t = textOn(c, bg); if (t == color) return false; color = t; return true }
        override fun updateDrawState(tp: TextPaint) { tp.color = color }
    }

    /**
     * [text] with [start, end) in the fade, as words on [bg] (the surface under them): the hue is lifted toward white
     * only as far as it takes to read at 4.5:1 there ([textOn]). Set the result as [view]'s text; a later call for
     * the same view takes over its step.
     */
    fun fadeText(view: TextView, text: CharSequence, start: Int, end: Int, bg: Int): CharSequence {
        val span = FadeSpan(bg)
        follow(view) { v, c -> if (span.step(c)) v.invalidate() }
        return SpannableString(text).apply { setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    /**
     * [fill] (a dot, a badge, a bar) in the fade while [view] (the view that draws it) is on screen; [ink], if given,
     * gets the readable ink for each step ([inkOn]) with the view, which it must not capture.
     */
    fun fadeFill(view: View, fill: GradientDrawable, ink: ((View, Int) -> Unit)? = null) {
        val step: (View, Int) -> Unit = { v, c -> fill.setColor(c); ink?.invoke(v, inkOn(c)) }
        step(view, fadeColor())
        follow(view, step)
    }

    /** [view] draws in [fadeColor] itself (a ring, a rail): it is redrawn on each step of the clock while on screen. */
    fun redrawOnFade(view: View) = follow(view) { v, _ -> v.invalidate() }

    private fun follow(view: View, step: (View, Int) -> Unit) {
        CinemaStyle.followFade(view, step)
        if (hooked.put(view, true) == null) view.addOnAttachStateChangeListener(ATTACH)
    }

    /** Views given [ATTACH] already (a word re-set on every boot step keeps one listener). */
    private val hooked = java.util.WeakHashMap<View, Boolean>()

    /**
     * Shown (again): one step now, and the clock wakes once the view is laid out (before that it isn't on screen yet,
     * and a clock with nothing else showing would rest without it).
     */
    private val ATTACH = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { CinemaStyle.fadeNow(v); v.post { CinemaStyle.tick() } }
        override fun onViewDetachedFromWindow(v: View) {}
    }

    /**
     * [c] as words on [bg]: the hue itself when it reads at [TEXT_MIN]:1 there, else lifted toward white in small
     * steps until it does (the violet of the fade on a lit panel needs about 3%, antislop contrast-check).
     */
    fun textOn(c: Int, bg: Int): Int {
        val lb = luminance(bg)
        var t = c
        var k = 0
        while (k < 20) {
            val l = luminance(t)
            if ((maxOf(l, lb) + 0.05) / (minOf(l, lb) + 0.05) >= TEXT_MIN) return t
            k++
            t = android.graphics.Color.rgb(lift(android.graphics.Color.red(c), k), lift(android.graphics.Color.green(c), k), lift(android.graphics.Color.blue(c), k))
        }
        return t
    }

    private fun lift(v: Int, k: Int) = v + ((255 - v) * k * 2 + 50) / 100

    /** AA for normal text, with a hair of margin over 4.5 so rounding never lands under it. */
    private const val TEXT_MIN = 4.6
}
