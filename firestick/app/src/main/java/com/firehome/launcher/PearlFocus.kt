package com.firehome.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView

/**
 * The Dream focus look for controls outside the Cinema pages (the user's rule, one look everywhere):
 * at rest neutral dark glass; focused, a pearl box with dark ink and the rainbow glowing border around
 * it ([CinemaStyle.focusGlow], the same glow and hue clock as Play and the Cinema cards). Fill and glow
 * share one corner radius, so the border hugs the box. Play-type primary actions use [Rainbow.fade]
 * instead (the full rainbow fill); cards and images take the glow alone.
 *
 * Cost: a static StateListDrawable plus the nine-sliced glow, which only repaints while it fades.
 */
object PearlFocus {
    /** The focused fill: Play's resting pearl, so a focused row and a resting Play button match. */
    const val PEARL = Rainbow.PLAY_REST
    /** Dark ink on [PEARL]: neutral near-black, not tinted. */
    const val INK = 0xFF16161A.toInt()
    /** The ink's quieter shade for captions and values on [PEARL]. */
    const val INK_DIM = 0xFF55555E.toInt()

    private val FOCUSED = intArrayOf(android.R.attr.state_focused)

    /** Neutral dark glass with a hairline: the resting surface of a control on the black theme. */
    fun glass(ctx: Context, radiusDp: Int, strength: Float = 1f): Drawable =
        Ui.rounded(ctx, Ui.alpha(Color.WHITE, 0.06f * strength), radiusDp, 1f, Ui.alpha(Color.WHITE, 0.10f * strength))

    /** Focused-vs-rest text colors (pearl box: [INK]). */
    fun states(focused: Int, rest: Int) = android.content.res.ColorStateList(arrayOf(FOCUSED, IntArray(0)), intArrayOf(focused, rest))

    /**
     * Gives [v] the look: background = glow behind a pearl-when-focused / [rest]-otherwise fill of corner
     * [radiusDp]. Returns the glow; the caller's focus listener calls [focus] with it (see [bind] when [v]
     * has no listener of its own). [fill] overrides the focused fill (Play's [Rainbow.fade]).
     * The face switches at once, both ways, like [CinemaRowFocus]: the halo paints a band inside its edge
     * that only an opaque face at the glow's own radius and bounds may cover, and a state list fading inside
     * another drawable can lose its callback and freeze mid-fade (a control stuck pearl). The glow drops
     * at once on focus loss too ([focus]).
     */
    fun apply(v: View, radiusDp: Int, rest: Drawable = glass(v.context, radiusDp), fill: Drawable? = null,
              haloDp: Int = CinemaStyle.GLOW_HALO_SMALL): CinemaGlow {
        val ctx = v.context
        val glow = CinemaStyle.glowDrawable(ctx, radiusDp = radiusDp, haloDp = haloDp)
        val face = fill ?: StateListDrawable().apply {
            addState(FOCUSED, Ui.rounded(ctx, PEARL, radiusDp))
            addState(IntArray(0), rest)
        }
        v.background = LayerDrawable(arrayOf(glow, face))
        unclip(v)
        return glow
    }

    /** [apply] plus a focus listener that runs the glow, the lift, and then [also]. */
    fun bind(v: View, radiusDp: Int, lift: Float = Ui.LIFT_ROW, rest: Drawable = glass(v.context, radiusDp),
             also: ((View, Boolean) -> Unit)? = null): CinemaGlow {
        val glow = apply(v, radiusDp, rest)
        v.setOnFocusChangeListener { view, has ->
            focus(glow, has)
            Ui.lift(view, has, lift)
            also?.invoke(view, has)
        }
        return glow
    }

    /**
     * A focused control's glow: in, it fades up on the shared hue clock ([CinemaStyle.focusGlow]); out, it
     * drops at once, like [CinemaRowFocus] (a fading halo over a face going back to glass reads as a box).
     */
    fun focus(glow: CinemaGlow, has: Boolean) {
        if (has) CinemaStyle.focusGlow(glow, true)
        else { glow.snap(glow.hue, false); CinemaStyle.setLive(glow, false) }
    }

    /** A Play-type action's fill: [Rainbow.fade] on a capsule of [radiusDp] (its focus listener calls `focus`). */
    fun rainbowFill(ctx: Context, radiusDp: Int) = GradientDrawable().apply { cornerRadius = Ui.dpf(ctx, radiusDp.toFloat()) }

    /**
     * The halo reaches past the view: once attached, the parents up to the nearest scroller stop clipping
     * it (and their padding), else it would be cut square at the row's edge.
     */
    private fun unclip(v: View) {
        fun run() {
            var p = v.parent as? ViewGroup
            var n = 0
            while (p != null && p !is ScrollView && n++ < 3) {
                p.clipChildren = false
                p.clipToPadding = false
                p = p.parent as? ViewGroup
            }
        }
        if (v.isAttachedToWindow) run()
        else v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(x: View) { run(); x.removeOnAttachStateChangeListener(this) }
            override fun onViewDetachedFromWindow(x: View) {}
        })
    }
}
