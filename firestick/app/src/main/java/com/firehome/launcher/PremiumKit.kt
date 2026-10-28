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
    val btnSecondary = Theme.get(ctx, Theme.BTN_SECONDARY)
    /** Text on the focus glow: deep ink on light highlights, white on dark ones. */
    val onSelect = Ui.onColor(ctx, select)
    val font: Typeface = Theme.typeface(ctx)
    val bold: Typeface = Theme.typeface(ctx, Typeface.BOLD)
    val medium: Typeface = Ui.medium(font)
    val semibold: Typeface = Ui.semibold(font)
    /** Small caps labels: a light tint of the highlight (near-white in Dream Cinema) that reads on the dark. */
    val kickerColor = Ui.mix(Ui.mix(select, blush, 0.5f), text, 0.45f)
    private val density = ctx.resources.displayMetrics.density
    val corner = listOf(0, 12, 22)[Theme.get(ctx, Theme.CORNERS)]
    val glow = Theme.get(ctx, Theme.GLOW) == 1
    val lift = listOf(1f, Ui.LIFT_CARD, Ui.LIFT_BIG)[Theme.get(ctx, Theme.ZOOM)]
    val tileScale = listOf(0.85f, 1f, 1.18f)[Theme.get(ctx, Theme.TILE_SIZE)]

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
            if (lines > 0) {
                maxLines = lines
                ellipsize = TextUtils.TruncateAt.END
            }
        }

    /** "FEATURED · SERIES": tiny tracked caps in the dreamy tint. */
    fun kicker(t: String) = label(t.uppercase(), Ui.T_MICRO, kickerColor, semibold, Ui.TRACK_CAPS + 0.06f)

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

    // ---- cards ----

    /**
     * A title card's art, cropped to fill. With art to show, the card has no tile or name inside
     * ([ArtView.tileless]): it stays clear until the art fades in. Only a title with no art at all
     * keeps the tile and its name ([placeholder]). [fallbacks] are tried in turn if [url] fails.
     */
    fun art(card: FrameLayout, url: String?, fallbacks: List<String> = emptyList()): ImageView = ArtView(ctx, card, corner.dp.toFloat()).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        if (url != null) ArtView.tileless(card, corner.dp.toFloat())
        card.addView(this, MATCH_PARENT, MATCH_PARENT)
        Net.image(ctx, this, url, fallbacks)
    }

    /** Name shown on a card until (or instead of) its art. */
    fun placeholder(card: FrameLayout, name: String) {
        card.addView(label(name, Ui.T_CAPTION, dim, medium, lines = 2).apply {
            gravity = Gravity.CENTER
            setPadding(10.dp, 0, 10.dp, 0)
            tag = PLACEHOLDER
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
    }

    /** Rounded progress capsule floating above the card's bottom edge. */
    fun progress(card: FrameLayout, wDp: Int, p: Float) {
        val inner = ((wDp * tileScale).toInt() - 24)
        card.addView(FrameLayout(ctx).apply {
            background = Ui.rounded(ctx, Color.argb(90, 0, 0, 0), Ui.R_PILL)
            addView(View(ctx).apply { background = Ui.focusFill(ctx, select, Ui.R_PILL) },
                FrameLayout.LayoutParams((inner * p.coerceIn(0.04f, 1f)).toInt().dp, MATCH_PARENT))
        }, FrameLayout.LayoutParams(MATCH_PARENT, 4.dp, Gravity.BOTTOM).apply { setMargins(12.dp, 0, 12.dp, 9.dp) })
    }

    /** A horizontal row of cards that never clips a lifted card. */
    fun row(cards: List<View>, startDp: Int, vPadDp: Int = 22) = EdgeRow(ctx, startDp.dp, CARD_GAP.dp, text).apply {
        isHorizontalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        setPadding(0, vPadDp.dp, 0, vPadDp.dp)
        addView(FlatLinear(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(startDp.dp, 0, startDp.dp, 0)
            for (c in cards) addView(c)
        })
    }

    companion object {
        /** Tag of a card's name label (see [ArtView]). */
        const val PLACEHOLDER = "art-placeholder"
        const val CARD_GAP = 16
        /** Room between a card's art and its caption (dp). */
        const val CAPTION_GAP = 8
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

/**
 * A card's art. Cards draw without an offscreen layer (they're [FlatFrame]s), so while a row fades
 * every part of a card is faded on its own: a tile under the art would show through it as a faint
 * box. Title cards ([tileless]) have no tile at all: the card stays clear until its art fades in
 * (alpha only, at its final size), with just the focus ring while it loads; the focus shadow joins
 * once the art is in. Cards that keep a tile (app banners) let it step aside once the art covers it.
 */
class ArtView(ctx: Context, private val card: FrameLayout, private val radius: Float) : ImageView(ctx) {
    private var tile: Drawable? = null
    private var bare = false
    private val settle: Runnable = object : Runnable {
        override fun run() {
            if (drawable?.opacity != android.graphics.PixelFormat.OPAQUE) return
            // A fade held up by a busy frame (fast scroll) isn't over yet: look again shortly.
            if (alpha >= 1f) setBare(true) else postDelayed(this, 120)
        }
    }

    /**
     * Set while a drawable swaps in on a laid-out art view of fixed size. ImageView asks for a layout
     * whenever the new image's size differs from the old one's (always, from or to no image), and that
     * re-measures the whole path up to the page: every art landing, and every row letting go of its
     * art or getting it back, was a layout pass. The art fills its card, so its size never changes.
     */
    private var swapping = false

    override fun requestLayout() {
        if (!swapping) super.requestLayout()
    }

    /** This view's size can't depend on its image: it fills (or is set to) a card of fixed size. */
    private fun fixedSize(): Boolean {
        val lp = layoutParams ?: return false
        val c = card.layoutParams ?: return false
        return isLaidOut && lp.width != WRAP_CONTENT && lp.height != WRAP_CONTENT && c.width > 0 && c.height > 0
    }

    override fun setImageDrawable(d: Drawable?) {
        @Suppress("SENSELESS_COMPARISON") if (card == null) { super.setImageDrawable(d); return } // called from ImageView's constructor
        // The matrix still follows the new image: ImageView computes it from the current size as it swaps.
        swapping = fixedSize()
        try { super.setImageDrawable(d) } finally { swapping = false }
        removeCallbacks(settle)
        when {
            // Only art that covers the card completely (opaque, cropped to fill) can stand in for the tile.
            d == null || d.opacity != android.graphics.PixelFormat.OPAQUE -> setBare(false)
            alpha >= 1f -> setBare(true) // shown at once (from memory)
            else -> postDelayed(settle, Ui.FADE_IMAGE + 60) // after its fade-in
        }
    }

    private fun setBare(on: Boolean) {
        if (on == bare) return
        bare = on
        (card.background as? OutlineOnly)?.takeIf { it.clear }?.let {
            // A title card: nothing to swap, only the focus shadow follows the art.
            it.shadow = on
            card.invalidateOutline()
            return
        }
        if (on) {
            tile = card.background
            card.background = OutlineOnly(radius)
        } else {
            tile?.let { card.background = it }
            tile = null
        }
        for (i in 0 until card.childCount) {
            val c = card.getChildAt(i)
            if (c.tag == PremiumKit.PLACEHOLDER) c.visibility = if (on) View.INVISIBLE else View.VISIBLE
        }
    }

    companion object {
        /**
         * Makes [card] a clear title card: no tile, no name inside (the caption under it names it).
         * It keeps its rounded outline (clip, and the focus ring in its foreground).
         */
        fun tileless(card: FrameLayout, radius: Float) {
            card.background = OutlineOnly(radius, clear = true)
            for (i in card.childCount - 1 downTo 0) if (card.getChildAt(i).tag == PremiumKit.PLACEHOLDER) card.removeViewAt(i)
        }
    }

    /**
     * Draws nothing; gives the card its rounded outline (clip and focus shadow) like the tile did.
     * A [clear] one (title card) casts its shadow only once the art is in, so a loading card shows
     * just its ring.
     */
    private class OutlineOnly(private val r: Float, val clear: Boolean = false) : Drawable() {
        var shadow = !clear
        override fun draw(canvas: android.graphics.Canvas) {}
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSPARENT
        override fun getOutline(outline: android.graphics.Outline) {
            outline.setRoundRect(bounds, r)
            outline.alpha = if (shadow) 1f else 0f
        }
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

/**
 * The sideways row used everywhere ([PremiumKit.row]). Its visible area is the page between its
 * margins ([inset] each side), so every row lines up with the page around it:
 *  - cards of one size are fitted so a whole number of them (the nearest to their own size) exactly
 *    fills the space between the margins; rows of mixed sizes keep theirs;
 *  - it glides itself: focus moves the row just enough to bring the card to a margin (never to the
 *    screen's edge), and every new press re-aims the same glide, so fast presses can't leave it
 *    between cards;
 *  - cards not wholly inside the margins where the row is headed fade out (never the focused one),
 *    so no card is cut off by the screen's edge, and small chevrons in the margins say there's more;
 *  - Left or Right past the last card stays put (never jumps to another row).
 * Like [FlatRow], its fade is applied per child (no offscreen layer).
 */
class EdgeRow(ctx: Context, private val inset: Int, private val gap: Int, arrowColor: Int) : HorizontalScrollView(ctx) {
    private val heading = java.util.WeakHashMap<View, Float>()
    /** Each card's own size (width, height) before fitting, so fitting never compounds. */
    private val natural = java.util.WeakHashMap<View, IntArray>()
    /** Where the row is gliding to (or resting); the fades and chevrons follow this, not the frames in between. */
    private var target = 0
    private var glide: android.animation.ValueAnimator? = null

    private val density = ctx.resources.displayMetrics.density
    private val arrowSize = (ARROW_DP * density).toInt()
    private val chevron: Drawable? = ctx.getDrawable(R.drawable.ic_chevron_left)?.mutate()?.apply { setTint(arrowColor) }
    private var arrowL = 0f
    private var arrowR = 0f
    private var wantL = false
    private var wantR = false
    private var arrowAnim: android.animation.ValueAnimator? = null

    private val strip get() = getChildAt(0) as? ViewGroup
    private fun maxScroll() = maxOf(0, (strip?.width ?: 0) - width)

    init { isHorizontalScrollBarEnabled = false }

    override fun hasOverlappingRendering() = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val total = MeasureSpec.getSize(widthMeasureSpec)
        val s = strip
        if (total > 0 && s != null && s.childCount > 0) fit(s, total - 2 * inset)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** The card a strip child shows: the child itself, or the card at the top of a captioned holder. */
    private fun cardIn(k: View): View {
        val g = k as? LinearLayout ?: return k
        val first = g.getChildAt(0) ?: return k
        return if (g.orientation == LinearLayout.VERTICAL && first.layoutParams?.width == k.layoutParams?.width) first else k
    }

    private fun sizeOf(k: View): IntArray? {
        val w = k.layoutParams?.width ?: return null
        val h = cardIn(k).layoutParams?.height ?: return null
        return if (w > 0 && h > 0) intArrayOf(w, h) else null
    }

    /** Cards of one fixed width: the nearest whole number of them fills [avail] exactly, each keeping its shape. */
    private fun fit(s: ViewGroup, avail: Int) {
        // Runs on every measure of the row: no lists, and the params are only written when they change.
        val n = s.childCount
        var w0 = -1
        var mixed = false
        for (i in 0 until n) {
            val k = s.getChildAt(i)
            val size = natural[k] ?: (sizeOf(k) ?: return).also { natural[k] = it }
            if (w0 < 0) w0 = size[0] else if (size[0] != w0) mixed = true
        }
        if (mixed || avail <= w0) return
        val cols = maxOf(1, Math.round((avail + gap).toFloat() / (w0 + gap)))
        val w = (avail - (cols - 1) * gap) / cols
        // The pixels left over by rounding go to the first gaps of each screenful, so the last card still meets the margin.
        val extra = avail - cols * w - (cols - 1) * gap
        for (i in 0 until n) {
            val k = s.getChildAt(i)
            val lp = k.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            val size = natural[k] ?: continue
            // The card first: cardIn matches a captioned holder by its width, which changes next.
            val card = cardIn(k)
            val end = if (i == n - 1) 0 else gap + if (i % cols < extra) 1 else 0
            val h = Math.round(size[1] * w.toFloat() / size[0])
            if (lp.width != w) lp.width = w
            if (lp.marginEnd != end) lp.marginEnd = end
            val clp = card.layoutParams
            if (card !== k && clp.width != w) clp.width = w
            if (clp.height != h) clp.height = h
        }
    }

    // The framework's own scroll-to-focus jumps are off: the row moves only by [glideTo].
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect) = 0

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        val s = strip ?: return
        var card: View? = focused
        while (card != null && card.parent !== s) card = card.parent as? View
        if (card == null) return
        val left = s.left + card.left
        val right = left + card.width
        var t = target
        if (right > t + width - inset) t = right - (width - inset)
        if (left < t + inset) t = left - inset
        glideTo(t.coerceIn(0, maxScroll()))
    }

    private fun glideTo(t: Int) {
        if (t == target && (glide?.isRunning == true || scrollX == t)) { settle(animate = true); return }
        target = t
        settle(animate = true)
        glide?.cancel()
        glide = android.animation.ValueAnimator.ofInt(scrollX, t).apply {
            duration = GLIDE_MS
            interpolator = Ui.EASE
            addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
            start()
        }
    }

    /** Left or Right with no card left that way: stay on this card. */
    override fun focusSearch(focused: View?, direction: Int): View? {
        val next = super.focusSearch(focused, direction)
        if ((direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) && focused != null && holds(focused) &&
            (next == null || !holds(next))) return focused
        return next
    }

    private fun holds(v: View): Boolean {
        var p: Any? = v.parent
        while (p is View) { if (p === this) return true; p = p.parent }
        return false
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        target = target.coerceIn(0, maxScroll())
        if (glide?.isRunning != true && scrollX != target) scrollTo(target, 0)
        settle(animate = false)
    }

    private fun settle(animate: Boolean) {
        val s = strip ?: return
        if (width == 0) return
        arrows(target > 0, target < maxScroll())
        for (i in 0 until s.childCount) {
            val c = s.getChildAt(i)
            val x = s.left + c.left - target
            val inside = c.hasFocus() || (x >= inset - SLACK && x + c.width <= width - inset + SLACK)
            val alpha = if (inside) 1f else 0f
            val was = heading[c]
            if (was == alpha) continue
            heading[c] = alpha
            // A card first seen inside keeps whatever it's doing (a row's slide-in fade). Duration 0 rather
            // than cancel() when not animating: cancel() would also stop the card's focus lift.
            if (was == null && inside) continue
            c.animate().alpha(alpha).setStartDelay(0).setDuration(if (!animate) 0L else if (inside) 220L else 140L)
                .setInterpolator(Ui.EASE).start()
        }
    }

    // ---- the more-to-scroll chevrons, drawn in the margins (only where a margin has room for one) ----

    private fun arrows(left: Boolean, right: Boolean) {
        if (left == wantL && right == wantR) return
        wantL = left
        wantR = right
        val fromL = arrowL
        val fromR = arrowR
        arrowAnim?.cancel()
        arrowAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = Ui.EASE
            addUpdateListener {
                val f = it.animatedValue as Float
                arrowL = fromL + ((if (wantL) 1f else 0f) - fromL) * f
                arrowR = fromR + ((if (wantR) 1f else 0f) - fromR) * f
                invalidate()
            }
            start()
        }
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        val d = chevron ?: return
        if ((arrowL <= 0f && arrowR <= 0f) || inset < arrowSize + (8 * density).toInt()) return
        val s = strip ?: return
        val first = s.getChildAt(0) ?: return
        val card = cardIn(first)
        // Level with the middle of the cards (not of their captions).
        val cy = s.top + first.top + (if (card !== first) card.top else 0) + card.height / 2
        val half = arrowSize / 2
        if (arrowL > 0f) {
            val cx = scrollX + inset / 2
            d.setBounds(cx - half, cy - half, cx + half, cy + half)
            d.alpha = (255 * ARROW_ALPHA * arrowL).toInt()
            d.draw(canvas)
        }
        if (arrowR > 0f) {
            val cx = scrollX + width - inset / 2
            canvas.save()
            canvas.scale(-1f, 1f, cx.toFloat(), cy.toFloat())
            d.setBounds(cx - half, cy - half, cx + half, cy + half)
            d.alpha = (255 * ARROW_ALPHA * arrowR).toInt()
            d.draw(canvas)
            canvas.restore()
        }
    }

    private companion object {
        /** Pixels a card may sit past a margin and still count as inside (rounding). */
        const val SLACK = 3
        const val GLIDE_MS = 300L
        const val ARROW_DP = 22
        const val ARROW_ALPHA = 0.75f
    }
}

open class FlatLinear(ctx: Context) : LinearLayout(ctx) {
    override fun hasOverlappingRendering() = false
}

open class FlatFrame(ctx: Context) : FrameLayout(ctx) {
    override fun hasOverlappingRendering() = false
}
