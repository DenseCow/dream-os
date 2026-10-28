package com.firehome.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ScrollView
import java.util.WeakHashMap

/**
 * Dream OS design tokens and the shared motion helpers.
 *
 * One spacing scale, one radius scale, one type scale, one set of durations and curves.
 * Every animation here ends on its own (no idle loops: the stick's GPU redraws the whole
 * screen for any running animation).
 *
 * Colors never live here: they come from [Theme] so everything stays customizable.
 */
object Ui {

    // ---- motion ----

    /** Emphasized decelerate: quick start, long soft landing. Scrolls, pages, overlays. */
    val EASE: PathInterpolator get() = SmoothScrollView.EASE
    /** Focus-in: a fast ease-out that runs ~3% past the target and settles (feels springy, never wobbly). */
    val SPRING = PathInterpolator(0.22f, 1.28f, 0.4f, 1f)
    /** Focus-out and small settles: a softer, overshoot-free ease-out. */
    val SETTLE = PathInterpolator(0.3f, 0f, 0.2f, 1f)
    /** Things leaving the screen: soft accelerate. */
    val EXIT = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    const val FOCUS_IN = 180L
    const val FOCUS_OUT = 150L
    const val PRESS = 70L
    const val RELEASE = 170L
    const val PAGE = 300L
    const val OVERLAY_IN = 320L
    const val OVERLAY_OUT = 220L
    const val SHEET_IN = 300L
    const val SHEET_OUT = 180L
    const val STAGGER = 32L
    const val FADE_IMAGE = 260L

    // ---- spacing (dp) ----

    const val S1 = 4
    const val S2 = 8
    const val S3 = 12
    const val S4 = 16
    const val S5 = 24
    const val S6 = 32
    const val S7 = 48

    // ---- corner radius (dp) ----

    const val R_XS = 6
    const val R_SM = 10
    const val R_MD = 14
    const val R_LG = 20
    const val R_XL = 28
    const val R_PILL = 100

    // ---- type scale (sp) ----

    const val T_DISPLAY = 34f
    const val T_TITLE = 28f
    const val T_HEADLINE = 22f
    const val T_SECTION = 18f
    const val T_BODY = 15f
    const val T_LABEL = 14f
    const val T_CAPTION = 13f
    const val T_MICRO = 11f
    /** Small all-caps labels ("FEATURED", "NEXT 12 HOURS"). */
    const val TRACK_CAPS = 0.18f
    /** Large titles sit a hair tighter. */
    const val TRACK_TITLE = -0.01f

    // ---- elevation (dp) ----

    const val Z_FOCUS = 10
    const val Z_SHEET = 24

    // ---- focus scales ----

    /** Lift for large cards / small controls; buttons and rows sit in between. */
    const val LIFT_CARD = 1.08f
    const val LIFT_BIG = 1.14f
    const val LIFT_PILL = 1.06f
    const val LIFT_ROW = 1.02f
    const val LIFT_ICON = 1.12f
    const val PRESS_DIP = 0.95f

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    fun dpf(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density

    // ---- color helpers (all derived from theme colors) ----

    /** [c] with its alpha set to [a] (0..1), whatever it was. */
    fun alpha(c: Int, a: Float) = Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    /** [c] with its existing alpha multiplied by [a]. */
    fun fade(c: Int, a: Float) = Color.argb((Color.alpha(c) * a).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )

    /** Glass surface for sheets and cards: the theme's tile color lifted a touch toward the text color. */
    fun surface(ctx: Context, a: Float = 0.96f): Int {
        val tile = Theme.get(ctx, Theme.TILE)
        val bg = Theme.get(ctx, Theme.BG_TOP)
        return alpha(mix(mix(tile, bg, 0.35f), Theme.get(ctx, Theme.TEXT), 0.03f), a)
    }

    /**
     * The highlight's dream partner: [c] turned ~50° toward pink (lavender → blush, blue → violet),
     * a touch lighter. Muted grays stay as they are.
     */
    fun dreamShift(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        if (hsv[1] < 0.12f) return c
        hsv[0] = (hsv[0] + 50f) % 360f
        hsv[2] = minOf(1f, hsv[2] * 1.04f)
        return Color.HSVToColor(Color.alpha(c), hsv)
    }

    /** Readable text on a [fill]: deep ink (the theme's background, darkened) on light fills, else white. */
    fun onColor(ctx: Context, fill: Int): Int =
        if (Theme.contrastOn(fill) == Color.BLACK) mix(Theme.get(ctx, Theme.BG_TOP), Color.BLACK, 0.25f) else Color.WHITE

    /** Premium focus fill: a soft left-to-right glow from the highlight color into its dream partner. */
    fun focusFill(ctx: Context, select: Int, radiusDp: Int) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(select, dreamShift(select))).apply {
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    fun medium(base: Typeface): Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, 500, false) else base

    fun semibold(base: Typeface): Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, 600, false) else Typeface.create(base, Typeface.BOLD)

    fun rounded(ctx: Context, fill: Int, radiusDp: Int, strokeDp: Float = 0f, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(ctx, radiusDp).toFloat()
        setColor(fill)
        if (strokeDp > 0f) setStroke(maxOf(1, dpf(ctx, strokeDp).toInt()), stroke)
    }

    // ---- focus motion ----

    // Resting focus scale per view, so the OK "click" returns to exactly the lifted size.
    private val lifted = WeakHashMap<View, Float>()
    // Glow depth (translationZ, px) of each focused view, so a glow held back ([dropGlow]) can return.
    private val glowZ = WeakHashMap<View, Float>()

    /** Focus lift: springy scale up (with optional depth) on focus, soft settle back on blur. */
    fun lift(v: View, has: Boolean, scale: Float, zDp: Int = 0) {
        val z = if (zDp > 0) dpf(v.context, zDp.toFloat()) else 0f
        if (has) {
            lifted[v] = scale
            if (zDp > 0) glowZ[v] = z
            val a = v.animate().scaleX(scale).scaleY(scale).setStartDelay(0).setDuration(FOCUS_IN).setInterpolator(SPRING)
            if (zDp > 0) a.translationZ(z)
            a.start()
        } else {
            lifted.remove(v)
            glowZ.remove(v)
            val a = v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(FOCUS_OUT).setInterpolator(SETTLE)
            if (zDp > 0) a.translationZ(0f)
            a.start()
        }
    }

    /**
     * Takes the focus glow (elevation shadow) off [v] and everything under it this very frame.
     *
     * A shadow is drawn by the caster's parent at the caster's own alpha: it ignores a fade applied
     * by an ancestor (rows fade per child, see FlatRow), so while a row fades its lifted card's glow
     * would stay at full strength and, once the card turns translucent, the shadow's umbra shows
     * through it as a sharp purple box. Z goes to 0 at once, not animated: any overlap of a shadow
     * with a translucent card is visible. The lift's own Z animation (queued or running) is replaced
     * by a no-op on the same animator, so it can't push Z back up; its scale settle runs on untouched.
     */
    fun dropGlow(v: View) {
        if (v.translationZ != 0f || glowZ.containsKey(v)) {
            v.translationZ = 0f
            v.animate().translationZ(0f)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) dropGlow(v.getChildAt(i))
    }

    /** Brings [v]'s glow back after [dropGlow], if it still has focus (a row that finished fading in). */
    fun restoreGlow(v: View) {
        val z = glowZ[v] ?: return
        if (!v.isFocused || v.translationZ == z) return
        v.animate().translationZ(z).setStartDelay(0).setDuration(FOCUS_IN).setInterpolator(SPRING).start()
    }

    private val pressed = WeakHashMap<View, Float>()

    /** OK pressed: a quick dip... */
    fun press(v: View) {
        val base = lifted[v] ?: v.scaleX
        if (base <= 0f || v.width <= 0) return
        pressed[v] = base
        v.animate().scaleX(base * PRESS_DIP).scaleY(base * PRESS_DIP).setStartDelay(0).setDuration(PRESS).setInterpolator(SETTLE).start()
    }

    /** ...and back up when released (call before the click runs, so the click's own animations win). */
    fun release(v: View) {
        val base = pressed.remove(v) ?: return
        val to = lifted[v] ?: base
        v.animate().scaleX(to).scaleY(to).setStartDelay(0).setDuration(RELEASE).setInterpolator(SPRING).start()
    }

    // ---- lift room ----
    //
    // A focused view grows past its own bounds (scale, glow), so every ancestor that clips must leave
    // room around it, or the lifted view shows a flat, sharp edge on that side. A view is clipped at an
    // ancestor's bounds when that ancestor's parent has clipChildren on, and at an ancestor's padding
    // edge when that ancestor has clipToPadding on (scrollers clip there while scrolling).

    /**
     * Room for focused children to grow into inside a container its parent clips (typically a
     * scroller in a card): [hDp]/[vDp] of padding on each side, drawn into rather than clipped
     * (clipToPadding off, here and on the parent), and the same amount of negative margin so the
     * content stays exactly where it was. The container's own outer edge is then the clip line.
     * Call once it is in its parent (needs its layout params).
     */
    fun liftRoom(v: ViewGroup, hDp: Int, vDp: Int = 0) {
        val h = dp(v.context, hDp)
        val vv = dp(v.context, vDp)
        v.clipToPadding = false
        v.setPadding(v.paddingLeft + h, v.paddingTop + vv, v.paddingRight + h, v.paddingBottom + vv)
        (v.parent as? ViewGroup)?.clipToPadding = false
        val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        lp.leftMargin -= h
        lp.rightMargin -= h
        if (lp.isMarginRelative) {
            lp.marginStart -= h
            lp.marginEnd -= h
        }
        lp.topMargin -= vv
        lp.bottomMargin -= vv
        if (lp.width > 0) lp.width += 2 * h
        if (lp.height > 0) lp.height += 2 * vv
        v.layoutParams = lp
    }

    /**
     * A scroller that clips its content at its own outer edge (outline), so its parents can stop
     * clipping for lifted siblings without scrolled content spilling out.
     */
    fun clipSelf(v: View) {
        v.outlineProvider = ViewOutlineProvider.BOUNDS
        v.clipToOutline = true
    }

    /**
     * For a card or sheet whose own edge is clipped by its host: lets lifted views draw past their
     * parents everywhere under [root] (clipping off on every plain container), while scrollers keep
     * clipping their content at their own edge ([clipSelf]). Content rebuilt later is covered too.
     */
    fun unclip(root: ViewGroup) {
        unclipTree(root)
        val relayout = ViewTreeObserver.OnGlobalLayoutListener { unclipTree(root) }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { v.viewTreeObserver.addOnGlobalLayoutListener(relayout) }
            override fun onViewDetachedFromWindow(v: View) { v.viewTreeObserver.removeOnGlobalLayoutListener(relayout) }
        })
        if (root.isAttachedToWindow) root.viewTreeObserver.addOnGlobalLayoutListener(relayout)
    }

    private val keepClipping = WeakHashMap<View, Boolean>()

    /** [unclip] leaves [v]'s own clipping alone (a container whose children are meant to be cut at its edge). */
    fun <T : ViewGroup> keepClip(v: T): T = v.also { keepClipping[it] = true }

    private fun unclipTree(v: View) {
        // A cropped image draws past its bounds unless its parent clips: it clips itself instead.
        if (v is ImageView && (v.scaleType == ImageView.ScaleType.CENTER_CROP || v.scaleType == ImageView.ScaleType.MATRIX)) v.cropToPadding = true
        if (v !is ViewGroup) return
        if (keepClipping[v] != true) {
            if (v is ScrollView || v is HorizontalScrollView) {
                if (!v.clipToOutline) clipSelf(v)
            } else {
                v.clipToPadding = false
            }
            v.clipChildren = false
        }
        for (i in 0 until v.childCount) unclipTree(v.getChildAt(i))
    }

    /** Fades [views] up into place one after another (first appearance only; every animation ends). */
    fun rise(views: List<View>, fromDp: Int = 14, startDelay: Long = 0L, step: Long = STAGGER, duration: Long = 300L, layer: Boolean = false) {
        for ((i, v) in views.withIndex()) {
            v.alpha = 0f
            v.translationY = dpf(v.context, fromDp.toFloat())
            val a = v.animate().alpha(1f).translationY(0f).setStartDelay(startDelay + i * step)
                .setDuration(duration).setInterpolator(EASE)
            if (layer) a.withLayer()
            a.start()
        }
    }

    /** Same as [rise], sliding in from the right (row entrances). */
    fun slideIn(views: List<View>, fromDp: Int = 28, startDelay: Long = 0L, step: Long = STAGGER, duration: Long = 320L, layer: Boolean = false) {
        for ((i, v) in views.withIndex()) {
            v.alpha = 0f
            v.translationX = dpf(v.context, fromDp.toFloat())
            val a = v.animate().alpha(1f).translationX(0f).setStartDelay(startDelay + i * step)
                .setDuration(duration).setInterpolator(EASE)
            if (layer) a.withLayer()
            a.start()
        }
    }
}

/**
 * One soft diagonal highlight that sweeps across a control once when it gains focus.
 * Used as a foreground on buttons and chips only (never over cover art). The gradient is
 * built once per size; each frame only moves a matrix.
 */
class SheenDrawable(private val radiusPx: Float, private val strength: Float = 0.22f) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private val rect = RectF()
    private var progress = -1f
    private var anim: ValueAnimator? = null

    override fun onBoundsChange(b: Rect) {
        rect.set(b)
        val band = maxOf(1f, b.width() * 0.45f)
        val white = Color.argb((strength * 255).toInt(), 255, 255, 255)
        paint.shader = LinearGradient(0f, 0f, band, band * 0.35f,
            intArrayOf(Color.TRANSPARENT, white, Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    /** Runs the sweep once (~520 ms); cancels a sweep already running. */
    fun sweep(delay: Long = 60L) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay
            duration = 560
            interpolator = Ui.SETTLE
            addUpdateListener { progress = it.animatedValue as Float; invalidateSelf() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { progress = -1f; invalidateSelf() }
            })
            start()
        }
    }

    fun cancel() {
        anim?.cancel()
        progress = -1f
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (progress < 0f || paint.shader == null) return
        val w = rect.width()
        val band = w * 0.45f
        m.setTranslate(rect.left - band + progress * (w + band), rect.top)
        paint.shader.setLocalMatrix(m)
        canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Screen fit (overscan): some TVs crop the picture edges. This is an extra inset, in percent of
 * the screen per side, that interactive content (bars, text, buttons, row starts, panels) keeps
 * clear of the edges. Backdrops and page backgrounds always stay full-bleed. 0 % = the normal
 * design margins. Saved per device (not per profile).
 */
object ScreenFit {
    const val MAX = 10
    const val DEFAULT = 3
    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("device", android.content.Context.MODE_PRIVATE)

    fun get(ctx: android.content.Context) = prefs(ctx).getInt("screenFit", DEFAULT).coerceIn(0, MAX)

    fun set(ctx: android.content.Context, percent: Int) = prefs(ctx).edit().putInt("screenFit", percent.coerceIn(0, MAX)).apply()

    /** [horizontal, vertical] inset in pixels for [percent]. */
    fun insets(ctx: android.content.Context, percent: Int = get(ctx)): IntArray {
        val dm = ctx.resources.displayMetrics
        val p = percent.coerceIn(0, MAX) / 100f
        return intArrayOf((dm.widthPixels * p).toInt(), (dm.heightPixels * p).toInt())
    }
}
