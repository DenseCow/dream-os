package com.firehome.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ScrollView

/**
 * ScrollView whose focus-driven scrolling is a long, eased glide instead of the stock jump.
 * When [manual] is set, focus changes don't scroll at all and the owner calls [glideTo].
 */
class SmoothScrollView(ctx: Context) : ScrollView(ctx) {
    var manual = false
    /** Extra room kept above/below a focused view when scrolling it into view. */
    var margin = 0
    var onScrolled: (() -> Unit)? = null
    /**
     * False: the arrow keys only ever move focus, never scroll the page on their own (at the last
     * row Down simply does nothing instead of nudging the layout).
     */
    var keyScroll = true
    private var anim: ValueAnimator? = null

    /**
     * True: the content is clipped to this view's own bounds even when a parent lets its children draw
     * outside theirs (clipChildren off for focus halos). Without it a scrolled list draws over whatever sits
     * above or below it (the Settings title, the card's header).
     */
    var clipViewport = false
        set(v) { field = v; clipBounds = if (v && width > 0) Rect(0, 0, width, height) else null }

    // Soft edges painted over the content in the surface's own color ([softEdges]): no offscreen layer.
    private var edgeLen = 0
    private val edgePaint = Paint()
    private var topShader: Shader? = null
    private var bottomShader: Shader? = null

    /**
     * Cheap soft edges: where there is more to scroll, a [lengthPx] gradient into the surface color under
     * the list ([top] at its top edge, [bottom] at its bottom) is painted over the content, so rows melt into
     * the panel instead of being cut. Android's own fading edges do this with an offscreen layer per edge on
     * every frame, which this stick's GPU pays for on every scroll step and every focus-glow tick.
     * Only for lists on a solid (or near-solid) surface; the colors must match it.
     */
    fun softEdges(top: Int, bottom: Int, lengthPx: Int) {
        isVerticalFadingEdgeEnabled = false
        edgeLen = lengthPx
        topShader = LinearGradient(0f, 0f, 0f, lengthPx.toFloat(), top, top and 0x00FFFFFF, Shader.TileMode.CLAMP)
        bottomShader = LinearGradient(0f, 0f, 0f, lengthPx.toFloat(), bottom and 0x00FFFFFF, bottom, Shader.TileMode.CLAMP)
        invalidate()
    }

    /**
     * Cheap soft edges for a list straight on a full-screen page (the setup pages): the page's own
     * background is painted back over the list's edges through a gradient mask, so rows melt into the
     * page exactly, whatever its gradient, with no offscreen layer.
     */
    fun softEdgesOverPage(lengthPx: Int) {
        isVerticalFadingEdgeEnabled = false
        edgeLen = lengthPx
        overPage = true
        invalidate()
    }

    private var overPage = false
    private var page: View? = null
    private var pageArt: Drawable? = null
    private var pageBitmap: Bitmap? = null
    private var pageScale = 1f
    private var artShader: BitmapShader? = null
    private var topEdge: Shader? = null
    private var bottomEdge: Shader? = null
    private val maskTop = LinearGradient(0f, 0f, 0f, 1f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    private val maskBottom = LinearGradient(0f, 0f, 0f, 1f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
    private val m = Matrix()
    private val here = IntArray(2)
    private val there = IntArray(2)

    /** The nearest full-screen ancestor with a background (the page), and that background as a bitmap. */
    private fun pageShader(): BitmapShader? {
        var p = page
        if (p == null || !p.isAttachedToWindow) {
            p = null
            val root = rootView
            var v = parent as? View
            while (v != null) {
                if (v.background != null && v.width >= root.width * 0.9f && v.height >= root.height * 0.9f) { p = v; break }
                v = v.parent as? View
            }
            page = p
        }
        val art = p?.background ?: return null
        if (art !== pageArt || artShader == null) {
            pageArt = art
            val bmp = (art as? BitmapDrawable)?.bitmap ?: pageCache[art] ?: run {
                // Drawn once at half size: page backgrounds are soft gradients.
                val w = maxOf(1, p.width / 2)
                val h = maxOf(1, p.height / 2)
                try {
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                        val c = Canvas(b)
                        c.scale(0.5f, 0.5f)
                        val old = Rect(art.bounds)
                        art.setBounds(0, 0, p.width, p.height)
                        art.draw(c)
                        art.bounds = old
                        pageCache[art] = b
                    }
                } catch (e: Throwable) { return null }
            }
            pageBitmap = bmp
            pageScale = p.width / bmp.width.toFloat()
            val sh = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            artShader = sh
            topEdge = ComposeShader(sh, maskTop, PorterDuff.Mode.DST_IN)
            bottomEdge = ComposeShader(sh, maskBottom, PorterDuff.Mode.DST_IN)
        }
        return artShader
    }

    private fun drawPageEdges(canvas: Canvas, topK: Float, botK: Float) {
        val art = pageShader() ?: return
        val p = page ?: return
        // Page bitmap pixels -> this list's content coordinates (the canvas here is scrolled).
        getLocationInWindow(here)
        p.getLocationInWindow(there)
        m.setScale(pageScale, pageScale)
        m.postTranslate((there[0] - here[0] + scrollX).toFloat(), (there[1] - here[1] + scrollY).toFloat())
        art.setLocalMatrix(m)
        val len = edgeLen.toFloat()
        val l = scrollX.toFloat()
        val r = l + width
        if (topK > 0f) {
            val y = (scrollY + if (clipToPadding) paddingTop else 0).toFloat()
            m.setScale(1f, len); m.postTranslate(0f, y)
            maskTop.setLocalMatrix(m)
            edgePaint.shader = topEdge
            edgePaint.alpha = (255 * topK).toInt()
            canvas.drawRect(l, y, r, y + len, edgePaint)
        }
        if (botK > 0f) {
            val y = (scrollY + height - (if (clipToPadding) paddingBottom else 0)).toFloat() - len
            m.setScale(1f, len); m.postTranslate(0f, y)
            maskBottom.setLocalMatrix(m)
            edgePaint.shader = bottomEdge
            edgePaint.alpha = (255 * botK).toInt()
            canvas.drawRect(l, y, r, y + len, edgePaint)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (clipViewport) clipBounds = Rect(0, 0, w, h)
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (edgeLen <= 0) return
        val content = getChildAt(0) ?: return
        val range = content.height - (height - paddingTop - paddingBottom)
        if (range <= 0) return
        val len = edgeLen.toFloat()
        val w = width.toFloat()
        val topK = (scrollY / len).coerceIn(0f, 1f)
        val botK = ((range - scrollY) / len).coerceIn(0f, 1f)
        if (overPage) { if (topK > 0f || botK > 0f) drawPageEdges(canvas, topK, botK); return }
        // The canvas is in content coordinates: the visible box starts at scrollY.
        if (topK > 0f) {
            val y = (scrollY + if (clipToPadding) paddingTop else 0).toFloat()
            edgePaint.shader = topShader
            edgePaint.alpha = (255 * topK).toInt()
            canvas.translate(scrollX.toFloat(), y)
            canvas.drawRect(0f, 0f, w, len, edgePaint)
            canvas.translate(-scrollX.toFloat(), -y)
        }
        if (botK > 0f) {
            val y = (scrollY + height - (if (clipToPadding) paddingBottom else 0)).toFloat() - len
            edgePaint.shader = bottomShader
            edgePaint.alpha = (255 * botK).toInt()
            canvas.translate(scrollX.toFloat(), y)
            canvas.drawRect(0f, 0f, w, len, edgePaint)
            canvas.translate(-scrollX.toFloat(), -y)
        }
    }

    // With clipToPadding off (room for the focus lift), the fading edges must cover the padding too, or the
    // strip of a cut-off row drawn in the padding stays unfaded: a faint "ghost box" under the fade.
    override fun isPaddingOffsetRequired() = !clipToPadding
    override fun getTopPaddingOffset() = -paddingTop
    override fun getBottomPaddingOffset() = paddingBottom

    override fun executeKeyEvent(event: android.view.KeyEvent): Boolean {
        if (!keyScroll && event.keyCode in ARROWS) return false
        return super.executeKeyEvent(event)
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (manual) return 0
        // With soft edges, the focused view keeps clear of them too: never left half under the fade. (At the
        // list's very ends the fade is gone, and the scroll range clamps the extra room away.)
        val room = (height - paddingTop - paddingBottom - rect.height()) / 2
        val pad = if (edgeLen > 0) (margin + edgeLen).coerceAtMost(maxOf(margin, room)) else margin
        val padded = Rect(rect).apply { top -= pad; bottom += pad }
        val delta = super.computeScrollDeltaToGetChildRectOnScreen(padded)
        if (delta != 0) glideTo(scrollY + delta)
        return 0
    }

    /** True while a [glideTo] is moving the page. */
    val gliding: Boolean get() = anim?.isRunning == true

    fun glideTo(y: Int, duration: Long = 440) {
        // Same range as ScrollView's own (padding included: it may be lift room, see Ui.liftRoom).
        val max = maxOf(0, (getChildAt(0)?.height ?: 0) - (height - paddingTop - paddingBottom))
        val target = y.coerceIn(0, max)
        if (target == scrollY && anim?.isRunning != true) return
        anim?.cancel()
        anim = ValueAnimator.ofInt(scrollY, target).apply {
            this.duration = duration
            interpolator = EASE
            addUpdateListener { scrollTo(0, it.animatedValue as Int) }
            start()
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrolled?.invoke()
    }

    companion object {
        /** Page backgrounds drawn as bitmaps for [softEdgesOverPage], shared by every list on the same page. */
        private val pageCache = java.util.WeakHashMap<Drawable, Bitmap>()
        /** "Emphasized decelerate": quick start, long soft landing. */
        val EASE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        private val ARROWS = setOf(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_PAGE_UP, android.view.KeyEvent.KEYCODE_PAGE_DOWN, android.view.KeyEvent.KEYCODE_SPACE,
            android.view.KeyEvent.KEYCODE_MOVE_HOME, android.view.KeyEvent.KEYCODE_MOVE_END)
    }
}
