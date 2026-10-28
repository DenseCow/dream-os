package com.firehome.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Netflix / Hulu style auto-scrolling for text that doesn't fit.
 *
 *  - [AutoScrollText]: a synopsis box that keeps its height (its max lines). When the text overflows and
 *    the viewer rests for [REST_MS], it glides slowly up to the last line, holds, fades back to the top;
 *    at most [CYCLES] times, then it stays at the top until the next rest. Any key or focus move eases it
 *    back to the top. Soft fade edges instead of the "…".
 *  - [MarqueeText]: a one-line card caption. Ellipsized and still at rest; while its card is focused it
 *    glides sideways to the end, pauses, returns, at most [CYCLES] times.
 *  - Big titles are [AutoScrollText]s too (a fixed 2-line box): at most a 10% step down when that alone
 *    makes them fit, otherwise the same calm glide. A title and a synopsis take turns, never move together.
 *
 * Cost on the stick: one TextView's scroll offset animates (a cheap re-record of a few lines, no
 * re-layout, no offscreen buffer for the text). A hardware layer would be re-rendered every frame here
 * since the text itself moves inside the view, so none is used. The fade edges are drawn into the text's
 * own ink ([InkFade]): no offscreen layer, right over any background. Runs end on their own (only the details synopsis, [AutoScrollText.loop], keeps going while at rest) and everything stops when the view is
 * detached, hidden, the window loses focus (player, another activity) or something covers the screen.
 */
object AutoScroll {
    private const val TAG = "FireHome"

    /** The viewer rests this long before a synopsis starts to glide. */
    const val REST_MS = 4_000L
    /** A focused synopsis (the viewer went there to read it) starts sooner. */
    const val FOCUSED_REST_MS = 1_500L
    /** Held at the last line before fading back to the top. */
    const val HOLD_MS = 5_000L
    /** Glides per rest (synopsis and caption alike). */
    const val CYCLES = 2
    /** Average synopsis glide speed (the curve eases in and out around it): slow enough to read along (was 12). */
    const val SPEED_DP = 5f
    /** A key press or focus move: back to the top. */
    const val SNAP_MS = 250L
    const val FADE_OUT_MS = 300L
    const val FADE_IN_MS = 420L

    /** A focused card waits this long before its caption starts to move. */
    const val TITLE_DELAY_MS = 1_000L
    /** Pause at the end of a caption (and at its start between runs). */
    const val TITLE_HOLD_MS = 2_000L
    const val TITLE_SPEED_DP = 20f
    const val TITLE_FADE_DP = 22

    /** Gentle ease in and out: starts and lands softly, never a jolt. */
    val GLIDE = PathInterpolator(0.42f, 0f, 0.58f, 1f)

    private val boxes = ArrayList<AutoScrollText>(4)
    private var moving = 0

    /** Something is gliding right now (the idle GPU trim waits for it). */
    val busy: Boolean get() = moving > 0

    /** Every key press: synopses ease back to the top and wait for the next rest. Main thread. */
    fun poke() {
        for (i in boxes.indices.reversed()) boxes.getOrNull(i)?.rest()
    }

    /** Never under the player, the screensaver, a menu or a sheet. */
    fun blocked(): Boolean = DreamPlayerActivity.visible || DreamSaver.dreaming || HeroTrailer.blocked()

    /** Another box is mid-run (gliding, holding or fading back): boxes take turns. */
    internal fun othersRunning(me: AutoScrollText) = boxes.any { it !== me && it.inRun }

    internal fun add(b: AutoScrollText) { if (b !in boxes) boxes.add(b) }
    internal fun remove(b: AutoScrollText) { boxes.remove(b) }
    internal fun moving(on: Boolean) { moving = if (on) moving + 1 else maxOf(0, moving - 1) }
    internal fun log(msg: String) = Log.w(TAG, "AutoScroll: $msg")
}

/**
 * Soft fade edges drawn into the text's own ink: while there is more text past an edge, the TextView's paint
 * gets a gradient in its text color that fades out over the last [len] px of the visible box. Works over any
 * background (art, gradients) with no offscreen layer and no strip painted over the page (Android's fading
 * edges cost a layer per edge on every frame; docs/STANDARDS.md). Plain one-color text only (a span's color
 * would take the gradient's). The gradients are built once per color/length; each draw moves them by matrix.
 */
internal class InkFade(private val vertical: Boolean) {
    private var color = 0
    private var len = 0
    private var head: LinearGradient? = null // ink: clear -> text color over len
    private var tail: LinearGradient? = null // mask: opaque -> clear over len
    private var both: Shader? = null
    private val mh = Matrix()
    private val mt = Matrix()

    /**
     * Before TextView.onDraw: [boxStart]..[boxEnd] is the visible box in the layout's coordinates (scroll
     * included), [kStart] / [kEnd] how strongly each edge fades (0..1). [clear] after the draw.
     */
    fun apply(p: Paint, ink: Int, length: Int, boxStart: Float, boxEnd: Float, kStart: Float, kEnd: Float) {
        if (length <= 0 || (kStart <= 0f && kEnd <= 0f)) { p.shader = null; return }
        val c = ink or 0xFF000000.toInt() // the paint's own alpha still applies on top
        if (both == null || c != color || length != len) {
            color = c; len = length
            val l = length.toFloat()
            val x = if (vertical) 0f else l
            val y = if (vertical) l else 0f
            val h = LinearGradient(0f, 0f, x, y, c and 0x00FFFFFF, c, Shader.TileMode.CLAMP)
            val t = LinearGradient(0f, 0f, x, y, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
            head = h; tail = t
            both = ComposeShader(h, t, PorterDuff.Mode.DST_IN)
        }
        val l = len.toFloat()
        // A weaker edge slides its ramp out past the box instead of fading its alpha: same look, no rebuild.
        val a = boxStart - l * (1f - kStart.coerceIn(0f, 1f))
        val b = boxEnd - l * kEnd.coerceIn(0f, 1f)
        if (vertical) { mh.setTranslate(0f, a); mt.setTranslate(0f, b) } else { mh.setTranslate(a, 0f); mt.setTranslate(b, 0f) }
        head?.setLocalMatrix(mh)
        tail?.setLocalMatrix(mt)
        p.shader = both
    }

    fun clear(p: Paint) { p.shader = null }
}

/** Shared by both scrollers: one reusable 0..1 animator whose eased fraction drives the offset. */
private class Glide(private val onFrame: (Float) -> Unit, private val onEnd: () -> Unit) {
    private var canceled = false
    private var counted = false
    val anim: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        addUpdateListener { onFrame(it.animatedFraction) }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(a: Animator) { canceled = false; count(true) }
            override fun onAnimationCancel(a: Animator) { canceled = true }
            override fun onAnimationEnd(a: Animator) { count(false); if (!canceled) onEnd() }
        })
    }
    private fun count(on: Boolean) { if (counted != on) { counted = on; AutoScroll.moving(on) } }
    fun start(ms: Long, curve: android.animation.TimeInterpolator) {
        anim.cancel()
        anim.duration = ms
        anim.interpolator = curve
        anim.start()
    }
    fun cancel() = anim.cancel()
    val running get() = anim.isRunning
}

/**
 * A synopsis box: the same height as before (its max lines), the rest revealed by a slow glide after
 * the viewer rests. Text that fits never moves. See [AutoScroll].
 */
class AutoScrollText(ctx: Context, private val slack: Float = 0f) : TextView(ctx) {
    private var ready = false
    /** Between a glide's start and the end of its fade back (see [AutoScroll.othersRunning]). */
    internal var inRun = false
    /** The size set by the caller; [slack] may step it down by up to that fraction to fit. */
    private var maxPx = 0f
    private var fitText: CharSequence? = null
    private var fitWidth = -1
    private var fitMax = -1f
    private val fitPaint = android.text.TextPaint()
    private var cycle = 0
    /** Keep running (hold, fade back, glide again) for as long as it's on screen and at rest (details page). */
    var loop = false
    /** The rest before a glide when not focused (ms); 0 = [AutoScroll.REST_MS]. The screensaver's is shorter. */
    var restMs = 0L
    /**
     * This box is itself the thing on top (the screensaver's synopsis): the covers that pause every other box
     * (the screensaver, a menu over Home, the system screensaver) don't apply to it. Only the player still does.
     */
    var onTop = false
    /** Gliding, holding or fading back right now (the screensaver waits for it before the next slide). */
    val reading: Boolean get() = inRun
    private var from = 0
    private var to = 0
    private val density = ctx.resources.displayMetrics.density
    private val glide = Glide({ f -> scrollTo(0, (from + (to - from) * f).toInt()) }, { postDelayed(fadeBack, AutoScroll.HOLD_MS) })
    private val snap = Glide({ f -> scrollTo(0, (from + (to - from) * f).toInt()) }, { arm() })
    private val startGlide = Runnable { glide() }
    private val fadeBack = Runnable { fadeBack() }
    private val focusWatch = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ -> rest() }
    /** The fade back to the top is running (counted as motion for [AutoScroll.busy]). */
    private var fading = false
    private fun fading(on: Boolean) { if (fading != on) { fading = on; AutoScroll.moving(on) } }

    init {
        ellipsize = null // the fade edge says "more"
        maxPx = textSize
        ready = true
    }

    /** How far the text runs past the box (0 when it fits). */
    private fun overflow(): Int {
        val l = layout ?: return 0
        if (l.lineCount <= maxLines) return 0
        return (l.height - (height - extendedPaddingTop - extendedPaddingBottom)).coerceAtLeast(0)
    }

    /** On screen and in front; overflow is checked when the glide is due (the layout may not exist yet). */
    private fun armable() = ready && isAttachedToWindow && isShown && (onTop || hasWindowFocus()) &&
        !(if (onTop) DreamPlayerActivity.visible else AutoScroll.blocked())
    private fun eligible() = armable() && overflow() > 0

    /**
     * A key press, a focus move or new text: calmly back to the top, then wait for the next rest. A focused
     * box is being read: it's never snapped back, a run under way carries on, else it glides after
     * [AutoScroll.FOCUSED_REST_MS].
     */
    fun rest() {
        if (!ready) return
        if (isFocused) {
            if (inRun || snap.running || fading) return
            cycle = 0
            arm()
            return
        }
        removeCallbacks(startGlide)
        removeCallbacks(fadeBack)
        cycle = 0
        inRun = false
        glide.cancel()
        if (fading) {
            animate().cancel()
            fading(false)
            animate().alpha(1f).setStartDelay(0).setDuration(AutoScroll.SNAP_MS).setInterpolator(Ui.EASE).withEndAction(null).start()
        }
        if (snap.running) return // already easing home; it re-arms when it lands
        if (scrollY != 0) {
            from = scrollY; to = 0
            snap.start(AutoScroll.SNAP_MS, Ui.EASE)
        } else arm()
    }

    private fun arm() {
        removeCallbacks(startGlide)
        if (armable()) postDelayed(startGlide, if (isFocused) AutoScroll.FOCUSED_REST_MS else if (restMs > 0) restMs else AutoScroll.REST_MS)
    }

    private fun glide() {
        if (!eligible()) return
        if (AutoScroll.othersRunning(this)) { postDelayed(startGlide, 1_000L); return } // its turn comes next
        val max = overflow()
        inRun = true
        cycle++
        from = scrollY; to = max
        val ms = ((max - scrollY) / (AutoScroll.SPEED_DP * density) * 1000f).toLong().coerceIn(5_000L, 90_000L)
        AutoScroll.log("glide ${max}px over ${ms}ms (run $cycle of ${AutoScroll.CYCLES})")
        glide.start(ms, AutoScroll.GLIDE)
    }

    private fun fadeBack() {
        if (!eligible()) { stop(); return }
        animate().cancel()
        fading(true)
        animate().alpha(0f).setStartDelay(0).setDuration(AutoScroll.FADE_OUT_MS).setInterpolator(Ui.EXIT).withEndAction {
            scrollTo(0, 0)
            animate().alpha(1f).setStartDelay(0).setDuration(AutoScroll.FADE_IN_MS).setInterpolator(Ui.EASE).withEndAction {
                fading(false)
                inRun = false
                if (loop || cycle < AutoScroll.CYCLES) { removeCallbacks(startGlide); if (armable()) postDelayed(startGlide, AutoScroll.HOLD_MS) }
            }.start()
        }.start()
    }

    /** Hidden, detached or covered: everything stops at once, back at the top. */
    private fun stop() {
        if (!ready) return
        removeCallbacks(startGlide)
        removeCallbacks(fadeBack)
        glide.cancel()
        snap.cancel()
        if (fading) { animate().cancel(); fading(false); alpha = 1f }
        cycle = 0
        inRun = false
        if (scrollY != 0) scrollTo(0, 0)
    }

    override fun hasOverlappingRendering() = false

    // Soft edges in the text's ink (InkFade), most of a line long: soft, the edge line still readable.
    private val fade = InkFade(vertical = true)

    override fun onDraw(canvas: Canvas) {
        val max = if (ready) overflow() else 0
        if (max <= 0) { super.onDraw(canvas); return }
        val len = (lineHeight * 0.8f).toInt()
        val box = (height - extendedPaddingTop - extendedPaddingBottom).toFloat()
        // The text is drawn in its layout's coordinates: the visible box starts at scrollY (top gravity once it overflows).
        fade.apply(paint, currentTextColor, len, scrollY.toFloat(), scrollY + box,
            scrollY.toFloat() / len, (max - scrollY).toFloat() / len)
        super.onDraw(canvas)
        fade.clear(paint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (ready) { stop(); arm() }
    }

    override fun setTextSize(unit: Int, size: Float) {
        super.setTextSize(unit, size)
        if (ready) maxPx = textSize
    }

    /** With [slack]: the set size, or up to [slack] smaller if that alone fits the text in its lines. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (ready && slack > 0f && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val w = MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight
            val t = text
            if (w > 0 && (t !== fitText || w != fitWidth || maxPx != fitMax)) {
                fitText = t; fitWidth = w; fitMax = maxPx
                var size = maxPx
                if (!t.isNullOrEmpty() && linesAt(t, w, maxPx) > maxLines) {
                    for (k in 1..2) { // 5%, then 10%
                        val s = maxPx * (1f - slack * k / 2f)
                        if (linesAt(t, w, s) <= maxLines) { size = s; break }
                    }
                }
                if (abs(size - textSize) > 0.5f) super.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Line count of [t] at [px] in [w] (only when the text, width or size changed). */
    private fun linesAt(t: CharSequence, w: Int, px: Float): Int {
        fitPaint.set(paint)
        fitPaint.textSize = px
        return android.text.StaticLayout.Builder.obtain(t, 0, t.length, fitPaint, w)
            .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
            .setIncludePad(includeFontPadding)
            .setBreakStrategy(breakStrategy)
            .setHyphenationFrequency(hyphenationFrequency)
            .build().lineCount
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        if (ready) { stop(); arm() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(focusWatch)
        AutoScroll.add(this)
        arm()
    }

    override fun onDetachedFromWindow() {
        stop()
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusWatch)
        AutoScroll.remove(this)
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) rest() else stop()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!ready) return
        if (isVisible) rest() else stop()
    }
}

/**
 * A one-line card caption: ellipsized and still at rest; while its card is focused (after
 * [AutoScroll.TITLE_DELAY_MS]) it glides to the end, pauses, fades back to the start, at most
 * [AutoScroll.CYCLES] times. Soft fade edges while it moves. Driven by [cardFocus].
 */
class MarqueeText(ctx: Context) : TextView(ctx) {
    private var ready = false
    private var focused = false
    /** Ellipsis dropped, fade edges on (only while the card is focused). */
    private var active = false
    private var cycle = 0
    private var from = 0
    private var to = 0
    private val density = ctx.resources.displayMetrics.density
    private val glide = Glide({ f -> scrollTo((from + (to - from) * f).toInt(), 0) }, { postDelayed(back, AutoScroll.TITLE_HOLD_MS) })
    private val home = Glide({ f -> scrollTo((from + (to - from) * f).toInt(), 0) }, { if (!focused) deactivate() })
    private val begin = Runnable { begin() }
    private val run = Runnable { run() }
    private val back = Runnable { back() }
    private var fading = false
    private fun fading(on: Boolean) { if (fading != on) { fading = on; AutoScroll.moving(on) } }

    init {
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        ready = true
    }

    private fun eligible() = ready && focused && isAttachedToWindow && isShown && hasWindowFocus() && !AutoScroll.blocked()

    fun cardFocus(has: Boolean) {
        if (!ready) return
        focused = has
        removeCallbacks(begin); removeCallbacks(run); removeCallbacks(back)
        cycle = 0
        if (has) {
            if (fading) { animate().cancel(); fading(false); alpha = 1f }
            if (eligible()) postDelayed(begin, AutoScroll.TITLE_DELAY_MS)
        } else settle()
    }

    /** Drops the "…" (a one-off relayout), then starts once the full line is laid out. */
    private fun begin() {
        if (!eligible()) return
        val l = layout ?: return
        if (!active) {
            if (l.lineCount == 0 || l.getEllipsisCount(0) == 0) return // it fits: nothing to do
            active = true
            ellipsize = null
            postDelayed(run, 120) // a few frames for the new line to land
        } else run()
    }

    private fun overflow(): Int {
        val l = layout ?: return 0
        if (l.lineCount == 0) return 0
        return ceil(l.getLineWidth(0) - (width - compoundPaddingLeft - compoundPaddingRight)).toInt().coerceAtLeast(0)
    }

    private fun run() {
        if (!eligible()) return
        val over = overflow()
        if (over <= 0) { deactivate(); return }
        home.cancel()
        cycle++
        from = scrollX; to = over
        val ms = ((over - scrollX) / (AutoScroll.TITLE_SPEED_DP * density) * 1000f).toLong().coerceIn(900L, 9_000L)
        glide.start(ms, AutoScroll.GLIDE)
    }

    /** Fades out at the end, jumps home, fades back in; the last run restores the "…" while hidden. */
    private fun back() {
        if (!eligible()) { hardStop(); return }
        fading(true)
        animate().alpha(0f).setStartDelay(0).setDuration(180).setInterpolator(Ui.EXIT).withEndAction {
            scrollTo(0, 0)
            val last = cycle >= AutoScroll.CYCLES
            if (last) deactivate()
            animate().alpha(1f).setStartDelay(0).setDuration(260).setInterpolator(Ui.EASE).withEndAction {
                fading(false)
                if (!last && eligible()) postDelayed(run, AutoScroll.TITLE_HOLD_MS)
            }.start()
        }.start()
    }

    /** Focus left: an easy glide home, then the "…" again. */
    private fun settle() {
        glide.cancel()
        if (fading) { animate().cancel(); fading(false) }
        if (alpha < 1f) animate().alpha(1f).setStartDelay(0).setDuration(150).setInterpolator(Ui.EASE).withEndAction(null).start()
        if (scrollX != 0 && isShown) {
            from = scrollX; to = 0
            home.start(AutoScroll.SNAP_MS, Ui.EASE)
        } else {
            if (scrollX != 0) scrollTo(0, 0)
            deactivate()
        }
    }

    private fun deactivate() {
        if (!active) return
        active = false
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun hardStop() {
        if (!ready) return
        removeCallbacks(begin); removeCallbacks(run); removeCallbacks(back)
        glide.cancel()
        home.cancel()
        if (fading || alpha < 1f) { animate().cancel(); fading(false); alpha = 1f }
        cycle = 0
        if (scrollX != 0) scrollTo(0, 0)
        deactivate()
    }

    private fun Int.dp() = (this * density).toInt()

    override fun hasOverlappingRendering() = false

    // Soft side edges in the text's ink (InkFade) while the caption glides.
    private val fade = InkFade(vertical = false)

    override fun onDraw(canvas: Canvas) {
        if (!ready || !active) { super.onDraw(canvas); return }
        val len = AutoScroll.TITLE_FADE_DP.dp()
        val box = (width - compoundPaddingLeft - compoundPaddingRight).toFloat()
        fade.apply(paint, currentTextColor, len, scrollX.toFloat(), scrollX + box,
            scrollX.toFloat() / len, (overflow() - scrollX).toFloat() / len)
        super.onDraw(canvas)
        fade.clear(paint)
    }

    override fun onDetachedFromWindow() {
        hardStop()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!ready) return
        if (!hasWindowFocus) hardStop() else if (focused) cardFocus(true)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (ready && !isVisible) hardStop()
    }
}

// ---- builders, same shape as PremiumKit.label so call sites swap one word ----

/** [PremiumKit.label] for a synopsis: the same [lines] tall, the overflow revealed by [AutoScrollText]. */
fun PremiumKit.scrollLabel(t: CharSequence, size: Float, color: Int = text, face: Typeface = font, tracking: Float = 0f, lines: Int = 3) =
    AutoScrollText(ctx).apply {
        textSize = size
        typeface = face
        setTextColor(color)
        letterSpacing = tracking
        includeFontPadding = false
        maxLines = lines
        text = t
    }

/**
 * [PremiumKit.label] for a big title: a fixed [lines]-tall box at the set size (at most 10% smaller if
 * that alone makes it fit); a title that still overflows auto-scrolls like a synopsis.
 */
fun PremiumKit.fitTitle(t: CharSequence, size: Float, color: Int = text, face: Typeface = font, tracking: Float = 0f, lines: Int = 2) =
    AutoScrollText(ctx, slack = 0.10f).apply {
        typeface = face
        setTextColor(color)
        letterSpacing = tracking
        includeFontPadding = false
        maxLines = lines
        textSize = size
        text = t
    }

/**
 * [PremiumKit.label] for a card caption: one-line captions glide while [card] is focused
 * ([MarqueeText]); multi-line ones stay plain labels. Chains onto the card's focus listener.
 */
fun PremiumKit.marqueeLabel(card: View, t: CharSequence, size: Float, color: Int = text, face: Typeface = font, tracking: Float = 0f,
                            lines: Int = 1): TextView {
    if (lines != 1) return label(t, size, color, face, tracking, lines)
    val m = MarqueeText(ctx).apply {
        textSize = size
        typeface = face
        setTextColor(color)
        letterSpacing = tracking
        includeFontPadding = false
        text = t
    }
    val base = card.onFocusChangeListener
    card.setOnFocusChangeListener { v, has ->
        base?.onFocusChange(v, has)
        m.cardFocus(has)
    }
    return m
}
