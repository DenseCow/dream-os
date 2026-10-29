package com.firehome.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View

/**
 * Dream Cinema's look for Dream Player ([Ui.cinema]): the controls, the side sheets, the Source picker
 * and Up Next share these surfaces so the player reads as part of the Cinema shell. Sheets are the
 * theme's black-to-purple sky as glass, focus is the rainbow glow ([CinemaStyle.focusGlow]) over a soft
 * pearl wash, buttons go pearl with ink when focused (like [CinemaKit.button]).
 *
 * Kept cheap on purpose (video plays underneath): every surface is a static drawable, the glow halo is
 * the cached mask, and only alpha / color animate, and only when focus moves.
 */
object PlayerLook {
    private val FOCUSED = intArrayOf(android.R.attr.state_focused)
    private val SELECTED = intArrayOf(android.R.attr.state_selected)

    /** Ink on a pearl (focused) fill: the sky, deepened (same as [CinemaKit.ink]). */
    fun ink(ctx: Context) = Ui.mix(CinemaStyle.bgTop(ctx), Color.BLACK, 0.35f)

    /** The deepest tone of the sky: scrims and the round glass behind glyphs. */
    fun shade(ctx: Context) = Ui.mix(CinemaStyle.bgTop(ctx), Color.BLACK, 0.55f)

    /** A Dream glass sheet: the sky from its top hue down to its bottom one, nearly opaque, with a hairline edge. */
    fun sheet(ctx: Context, radiusDp: Int = Ui.R_XL): GradientDrawable {
        val text = CinemaStyle.text(ctx)
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            Ui.alpha(Ui.mix(CinemaStyle.bgTop(ctx), text, 0.06f), 0.95f),
            Ui.alpha(Ui.mix(CinemaStyle.bg(ctx), Color.BLACK, 0.12f), 0.96f),
        )).apply {
            cornerRadius = Ui.dpf(ctx, radiusDp.toFloat())
            setStroke(maxOf(1, Ui.dp(ctx, 1)), Ui.alpha(text, 0.10f))
        }
    }

    /** Behind a sheet at the right: the sky deepening toward it, the picture still showing at the left. */
    fun sideScrim(ctx: Context): GradientDrawable {
        val s = shade(ctx)
        return GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, intArrayOf(Ui.alpha(s, 0.80f), Ui.alpha(s, 0.46f), Color.TRANSPARENT))
    }

    /**
     * A list row's background: while focused a pearl fill with the fading rainbow border on the same corner
     * radius, [rest] otherwise (a faint fill marks the chosen row). Text on it turns to [ink] (callers'
     * color state lists).
     */
    fun row(ctx: Context, radiusDp: Int, rest: Drawable? = null): Pair<Drawable, CinemaGlow> {
        // Edge only, no halo: the halo's nine slices also tint a band just inside the edge, which on a row
        // (taller than twice its corner radius) reads as a hard-cornered box whenever the fill isn't fully
        // opaque, e.g. for the instant it fades in or out on every Up / Down.
        val glow = CinemaStyle.glowDrawable(ctx, radiusDp = radiusDp, haloDp = ROW_HALO).apply { haloAlpha = 0f }
        val states = StateListDrawable().apply {
            addState(FOCUSED, Ui.rounded(ctx, Rainbow.PLAY_REST, radiusDp))
            addState(IntArray(0), rest ?: ColorDrawable(Color.TRANSPARENT))
            setEnterFadeDuration(Ui.FOCUS_IN.toInt())
            setExitFadeDuration(Ui.FOCUS_OUT.toInt())
        }
        return LayerDrawable(arrayOf(glow, states)) to glow
    }

    /** The faint fill of the chosen row in a list. */
    fun chosen(ctx: Context, radiusDp: Int) = Ui.rounded(ctx, Ui.alpha(CinemaStyle.text(ctx), 0.06f), radiusDp)

    /** Text colors for a row: [rest], and [ink] (at [inkAlpha]) while the row is focused (pearl). */
    fun rowText(ctx: Context, rest: Int, inkAlpha: Float = 1f) = android.content.res.ColorStateList(
        arrayOf(FOCUSED, IntArray(0)), intArrayOf(Ui.alpha(ink(ctx), inkAlpha), rest))

    /**
     * Gives [v] a [row] background and its focus: the pearl fill and the fading rainbow border, no scale,
     * so a row's check and text stay exactly where they are, focused or not ([extra] runs too).
     */
    fun glowRow(v: View, radiusDp: Int, rest: Drawable? = null, extra: ((Boolean) -> Unit)? = null) {
        val (bg, glow) = row(v.context, radiusDp, rest)
        v.background = bg
        v.setOnFocusChangeListener { view, has ->
            border(view, glow, has)
            extra?.invoke(has)
        }
    }

    // ---- the fading rainbow border ----
    //
    // Focus borders in the player take Play's hue: one solid color fading through the rainbow on the shared
    // clock ([Rainbow.fadeColor]). Only the focused one moves, ~15 steps a second, and only while it is on
    // screen (the controls fading out stop it; [resume] picks it up when they come back).

    private var tinted: CinemaGlow? = null
    private var tintedView: java.lang.ref.WeakReference<View>? = null
    // A posted step every 66 ms (an endless animator woke the UI thread on every frame just to skip most of them).
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var ticking = false
    private val step = object : Runnable {
        override fun run() {
            val v = tintedView?.get()
            val g = tinted
            if (v == null || g == null || !v.isShown) { ticking = false; return }
            g.tint(Rainbow.fadeColor())
            main.postDelayed(this, 66)
        }
    }
    private fun startTicker() { if (!ticking) { ticking = true; main.postDelayed(step, 66) } }

    /** [glow] on [v] shows (in Play's current hue, then following it) or hides. */
    fun border(v: View, glow: CinemaGlow, has: Boolean) {
        if (has) {
            val c = Rainbow.fadeColor()
            glow.show(c, c)
            tinted = glow
            tintedView = java.lang.ref.WeakReference(v)
            startTicker()
        } else {
            glow.hide()
            if (tinted === glow) { tinted = null; tintedView = null }
        }
    }

    /** The focused border's view is back on screen (the controls showed again): keep its hue moving. */
    fun resume() {
        if (tinted != null && tintedView?.get()?.isShown == true) startTicker()
    }

    /**
     * A control's background (round icon button, pill, chip): dark glass at rest, pearl when focused with
     * the fading rainbow border on the same radius. [selected] adds the brighter chosen state (a season chip).
     */
    fun control(ctx: Context, radiusDp: Int, selected: Boolean = false): Pair<Drawable, CinemaGlow> {
        val text = CinemaStyle.text(ctx)
        val glow = CinemaStyle.glowDrawable(ctx, radiusDp = radiusDp, haloDp = CinemaStyle.GLOW_HALO_SMALL).apply { haloAlpha = 0.44f }
        val states = StateListDrawable().apply {
            addState(FOCUSED, Ui.rounded(ctx, Rainbow.PLAY_REST, radiusDp))
            if (selected) addState(SELECTED, Ui.rounded(ctx, Ui.alpha(text, 0.16f), radiusDp, 1f, Ui.alpha(text, 0.30f)))
            addState(IntArray(0), Ui.rounded(ctx, Ui.alpha(Ui.mix(shade(ctx), text, 0.10f), 0.62f), radiusDp, 1f, Ui.alpha(text, 0.16f)))
            setEnterFadeDuration(Ui.FOCUS_IN.toInt())
            setExitFadeDuration(Ui.FOCUS_OUT.toInt())
        }
        return LayerDrawable(arrayOf(glow, states)) to glow
    }

    /** Focus motion for a [control]: glow in, a touch of scale (no elevation: the glow is the depth). */
    fun focus(v: View, glow: CinemaGlow, has: Boolean, scale: Float = 1.08f) {
        border(v, glow, has)
        val s = if (has) scale else 1f
        v.animate().scaleX(s).scaleY(s).setStartDelay(0).setDuration(CinemaStyle.FOCUS_MS).setInterpolator(Ui.EASE).start()
    }

    /** Halo reach for list rows (dp): within the lift room of the player's scrollers, so it is never cut. */
    const val ROW_HALO = 8
}
