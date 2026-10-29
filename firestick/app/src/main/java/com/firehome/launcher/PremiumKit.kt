package com.firehome.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The UI kit setup, Settings, sheets and the player are built from (pills, badges, labels, rows,
 * glass and frost). The name is historical (it began as the Dream Premium theme's kit). Colors come
 * from [Theme] at build time, sizes and motion from [Ui].
 *
 * Palette roles: SELECT is the highlight (with its dream partner [Ui.dreamShift]); ACCENT is the pearl
 * focus ring; TEXT/TEXT_DIM the text; TILE and BG_TOP/BG_BOTTOM the surfaces.
 *
 * Dream Cinema wears the brand: black and neutral grays, the rainbow the only color.
 * Its pills and icon buttons take the Dream focus look ([PearlFocus]): Play-type primaries the fading
 * rainbow fill ([Rainbow.fade]), everything else a pearl face with dark ink inside the rainbow border.
 */
class PremiumKit(val ctx: Context) {
    val text = Theme.get(ctx, Theme.TEXT)
    val dim = Theme.get(ctx, Theme.TEXT_DIM)
    val select = Theme.get(ctx, Theme.SELECT)
    val blush = Ui.dreamShift(select)
    val accent = Theme.get(ctx, Theme.ACCENT)
    val tile = Theme.get(ctx, Theme.TILE)
    val bgTop = Theme.get(ctx, Theme.BG_TOP)
    val bgBottom = Theme.get(ctx, Theme.BG_BOTTOM)
    val btnPrimary = Theme.get(ctx, Theme.BTN_PRIMARY)
    /** Text on the focus glow: deep ink on light highlights, white on dark ones. */
    val onSelect = Ui.onColor(ctx, select)
    val font: Typeface = Theme.typeface(ctx)
    val bold: Typeface = Theme.typeface(ctx, Typeface.BOLD)
    val medium: Typeface = Ui.medium(font)
    val semibold: Typeface = Ui.semibold(font)
    /** Small caps labels: a light tint of the highlight (near-white in Dream Cinema) that reads on the dark. */
    val kickerColor = Ui.mix(Ui.mix(select, blush, 0.5f), text, 0.45f)
    private val density = ctx.resources.displayMetrics.density

    val Int.dp get() = (this * density).toInt()
    val Float.dpf get() = this * density

    // ---- type ----

    fun label(t: CharSequence, size: Float, color: Int = text, face: Typeface = font, tracking: Float = 0f, lines: Int = 1) =
        TextView(ctx).apply {
            text = t
            textSize = size
            typeface = face
            setTextColor(color)
            letterSpacing = tracking
            includeFontPadding = false
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE // Android 9 hyphenates mid-word by default
            if (lines > 0) {
                maxLines = lines
                ellipsize = TextUtils.TruncateAt.END
            }
        }

    /** "FEATURED · SERIES": tiny tracked caps in the dreamy tint. */
    /** A small Title Case line over a heading (STANDARDS §4 labels: no tracked caps). */
    fun kicker(t: String) = label(t, Ui.T_MICRO, kickerColor, semibold)

    // ---- surfaces ----

    /** Quiet glass capsule (top-bar groups, badges over art): neutral near-black. */
    fun glass(radiusDp: Int = Ui.R_PILL, a: Int = 84) =
        Ui.rounded(ctx, Color.argb(a, 18, 18, 20), radiusDp, 1f, Ui.alpha(text, 0.12f))

    /** Sheet / card surface: glass in the theme's tile color with a hairline edge, lighter at the top like the sky. */
    fun sheet(radiusDp: Int = Ui.R_XL) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Ui.alpha(Ui.mix(tile, text, 0.06f), 0.97f), Ui.alpha(Ui.mix(tile, bgTop, 0.4f), 0.97f))).apply {
        cornerRadius = radiusDp.dp.toFloat()
        setStroke(maxOf(1, 1.dp), Ui.alpha(text, 0.10f))
    }

    // ---- controls ----

    /**
     * The one button: the focus glow (the highlight into its dream shift) with ink text when focused,
     * pearl (primary) or glass (secondary) at rest. Springs up, one light sweep across as it lands.
     * Always the Dream focus look ([PearlFocus]), a primary being Play-type ([dream] is kept for callers; it changes nothing).
     */
    @Suppress("UNUSED_PARAMETER")
    fun pill(label: String, icon: Drawable?, primary: Boolean, heightDp: Int = 46, dream: Boolean = true, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = Ui.T_BODY
        typeface = semibold
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        icon?.let { setCompoundDrawables(it.mutate().apply { setBounds(0, 0, 18.dp, 18.dp) }, null, null, null) }
        compoundDrawablePadding = 10.dp
        setPadding(if (icon != null) 20.dp else 26.dp, 0, 26.dp, 0)
        dreamPill(this, primary, heightDp)
        isFocusable = true
        isClickable = true
        id = View.generateViewId()
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, heightDp.dp).apply { marginEnd = 14.dp }
        setOnClickListener { onClick() }
    }

    /**
     * [pill]'s Dream look: a capsule of half its height. Primary (Play-type): pearl at rest, the fading
     * rainbow fill while focused, its label and icon in the fill's readable ink. Otherwise neutral glass at
     * rest, the pearl face with dark ink when focused. Both wear the rainbow border while focused, dropped
     * at once on the way out. No colored shadow: the glow is the depth.
     */
    private fun dreamPill(v: TextView, primary: Boolean, heightDp: Int) {
        val r = heightDp / 2
        val sheen = SheenDrawable((heightDp / 2f).dpf)
        v.foreground = sheen
        var fade: Rainbow.Fade? = null
        val glow = if (primary) {
            val fill = PearlFocus.rainbowFill(ctx, r)
            PearlFocus.apply(v, r, fill = fill).also {
                fade = Rainbow.fade(v, fill) { c -> v.setTextColor(c); v.compoundDrawableTintList = ColorStateList.valueOf(c) }
            }
        } else {
            val colors = PearlFocus.states(PearlFocus.INK, text)
            v.setTextColor(colors)
            v.compoundDrawableTintList = colors
            PearlFocus.apply(v, r)
        }
        v.outlineProvider = null
        v.setOnFocusChangeListener { x, has ->
            Ui.lift(x, has, Ui.LIFT_PILL)
            PearlFocus.focus(glow, has)
            fade?.focus(has)
            if (has) sheen.sweep() else sheen.cancel()
        }
    }

    fun icon(res: Int): Drawable = ctx.getDrawable(res)!!.mutate()

    /** Small glass capsule with text (S2 · E5, 42 min left, NEW in the highlight glow; Cinema: pearl with ink). */
    fun badge(t: String, highlight: Boolean = false) =
        label(t, 10.5f, if (!highlight) text else PearlFocus.INK, bold, 0.08f).apply {
        setPadding(8.dp, 4.dp, 8.dp, 4.dp)
        background = if (!highlight) glass(Ui.R_PILL, 150)
            else Ui.rounded(ctx, PearlFocus.PEARL, Ui.R_PILL)
    }

    companion object {
        /** Page side margin (dp): titles, rows and the info block all start on this line. */
        const val EDGE = 56

        private val main = Handler(Looper.getMainLooper())

        /**
         * Frost: one small, blurred snapshot of what's on screen, taken once when a sheet opens
         * (GPU readback scaled straight into a 1/8-size bitmap, then a quick box blur on the CPU).
         * Shown scaled up behind the sheet; the caller drops the bitmap when the sheet closes.
         */
        fun frost(window: Window, done: (Bitmap?) -> Unit) {
            val decor = window.decorView
            val w = decor.width / 8
            val h = decor.height / 8
            if (w <= 0 || h <= 0 || Build.VERSION.SDK_INT < 26) return done(null)
            val bmp = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (e: Throwable) { return done(null) }
            try {
                PixelCopy.request(window, bmp, { result ->
                    if (result != PixelCopy.SUCCESS) { bmp.recycle(); done(null); return@request }
                    Net.run {
                        blur(bmp, 3)
                        blur(bmp, 3)
                        main.post { done(bmp) }
                    }
                }, main)
            } catch (e: Exception) {
                bmp.recycle()
                done(null)
            }
        }

        /** In-place box blur (horizontal then vertical), for tiny bitmaps only. */
        private fun blur(b: Bitmap, r: Int) {
            val w = b.width
            val h = b.height
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            val out = IntArray(w * h)
            fun pass(src: IntArray, dst: IntArray, len: Int, lines: Int, step: Int, lineStep: Int) {
                for (l in 0 until lines) {
                    var rs = 0; var gs = 0; var bs = 0
                    val base = l * lineStep
                    for (i in -r..r) {
                        val c = src[base + i.coerceIn(0, len - 1) * step]
                        rs += (c shr 16) and 255; gs += (c shr 8) and 255; bs += c and 255
                    }
                    val n = 2 * r + 1
                    for (i in 0 until len) {
                        dst[base + i * step] = (255 shl 24) or ((rs / n) shl 16) or ((gs / n) shl 8) or (bs / n)
                        val a = src[base + (i - r).coerceIn(0, len - 1) * step]
                        val z = src[base + (i + r + 1).coerceIn(0, len - 1) * step]
                        rs += ((z shr 16) and 255) - ((a shr 16) and 255)
                        gs += ((z shr 8) and 255) - ((a shr 8) and 255)
                        bs += (z and 255) - (a and 255)
                    }
                }
            }
            pass(px, out, w, h, 1, w)
            pass(out, px, h, w, w, 1)
            b.setPixels(px, 0, w, 0, 0, w, h)
        }

        /** Removes [v] from its parent, if it has one. */
        fun detach(v: View) { (v.parent as? ViewGroup)?.removeView(v) }
    }
}

/*
 * Containers that fade as a whole (rows sliding out of the way, the hero buttons). Telling the
 * renderer their children don't overlap lets it apply the fade per child instead of drawing the
 * whole row into an offscreen buffer every frame: a big saving in GPU memory and fill on the stick.
 */
class FlatRow(ctx: Context) : HorizontalScrollView(ctx) {
    override fun hasOverlappingRendering() = false
}

open class FlatLinear(ctx: Context) : LinearLayout(ctx) {
    override fun hasOverlappingRendering() = false
}

open class FlatFrame(ctx: Context) : FrameLayout(ctx) {
    override fun hasOverlappingRendering() = false
}
