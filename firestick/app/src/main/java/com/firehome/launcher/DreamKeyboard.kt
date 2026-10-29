package com.firehome.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/**
 * The search text. Never takes focus itself (so the system keyboard never shows): the Dream
 * keyboard edits it, and anything that asks it for focus (the phone remote's "type" box) is sent
 * to the keyboard instead via [focusRedirect]. Draws one steady caret (no blinking: idle
 * animations redraw the whole screen on this GPU).
 */
class QueryField(ctx: Context) : EditText(ctx) {
    var focusRedirect: (() -> Boolean)? = null
    /** Runs a search for this text on the owning page, focus landing on the results (Alexa's query). */
    var onSearch: ((String) -> Unit)? = null
    /** Shows (true) or clears (false) the "Alexa is listening" state on the owning page. */
    var onAlexa: ((Boolean) -> Unit)? = null
    /** Alexa has heard: the owning page shows it's searching, and gives the part of it that keeps moving. */
    var onAlexaSearching: (() -> ScreenCover.Live?)? = null
    /** Draws the owning page as it will look once Alexa has heard, without showing it (see CinemaSearch). */
    var drawAsSearching: (((View?) -> Unit) -> Unit)? = null
    var caretColor = 0
    var caretShown = true
        set(v) { if (field != v) { field = v; invalidate() } }
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caretRect = RectF()
    /** Longest text (searches: [MAX_LEN]; repository addresses typed in setup need more). */
    var maxLen = MAX_LEN
        set(v) { field = v; filters = arrayOf<InputFilter>(InputFilter.LengthFilter(v)) }

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        showSoftInputOnFocus = false
        isCursorVisible = false
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters = arrayOf<InputFilter>(InputFilter.LengthFilter(MAX_LEN))
        background = null
        includeFontPadding = false
    }

    override fun requestFocus(direction: Int, previouslyFocusedRect: Rect?): Boolean =
        focusRedirect?.invoke() ?: false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = layout ?: return
        if (!caretShown || caretColor == 0) return
        val off = selectionStart.coerceIn(0, text.length)
        // The canvas is already in content coordinates (scrolled), like the text.
        val x = compoundPaddingLeft + l.getPrimaryHorizontal(off)
        val h = textSize * 1.05f
        val cy = scrollY + height / 2f
        val w = resources.displayMetrics.density * 2.2f
        caretRect.set(x, cy - h / 2f, x + w, cy + h / 2f)
        caretPaint.color = caretColor
        canvas.drawRoundRect(caretRect, w, w, caretPaint)
    }

    /** Inserts at the caret. */
    fun insert(s: String) {
        val t = text ?: return
        val at = selectionStart.coerceIn(0, t.length)
        if (t.length + s.length > maxLen) return
        t.insert(at, s)
        setSelection((at + s.length).coerceAtMost(text.length))
    }

    /** Deletes before the caret ([word]: back to the previous space). False when there was nothing to delete. */
    fun backspace(word: Boolean = false): Boolean {
        val t = text ?: return false
        val at = selectionStart.coerceIn(0, t.length)
        if (at == 0) return false
        var from = at - 1
        if (word) {
            while (from > 0 && t[from - 1] == ' ') from--
            while (from > 0 && t[from - 1] != ' ') from--
        }
        t.delete(from, at)
        setSelection(from)
        return true
    }

    fun moveCaret(by: Int) {
        val n = (selectionStart + by).coerceIn(0, text.length)
        setSelection(n)
        invalidate()
    }

    fun setQuery(s: String) {
        setText(s.take(maxLen))
        setSelection(text.length)
    }

    companion object { const val MAX_LEN = 60 }
}

/** Small stroked glyphs for the keyboard's function keys, drawn on a 24-unit grid. */
class KeyGlyph(private val shape: Int, private val sizePx: Int = 0) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    var color: Int
        get() = paint.color
        set(v) { paint.color = v; invalidateSelf() }

    override fun onBoundsChange(b: Rect) {
        // Centered at [sizePx] when given (e.g. a key's foreground fills the whole key), else fills the bounds.
        val s = (if (sizePx > 0) sizePx.toFloat() else min(b.width(), b.height()).toFloat()) / 24f
        paint.strokeWidth = 1.9f * s
        path.reset()
        val ox = b.left + (b.width() - 24 * s) / 2f
        val oy = b.top + (b.height() - 24 * s) / 2f
        fun x(v: Float) = ox + v * s
        fun y(v: Float) = oy + v * s
        when (shape) {
            MIC -> {
                path.addRoundRect(RectF(x(9f), y(3f), x(15f), y(14.5f)), 3f * s, 3f * s, Path.Direction.CW)
                path.moveTo(x(5.5f), y(11f))
                path.cubicTo(x(5.5f), y(15.2f), x(8.5f), y(17.8f), x(12f), y(17.8f))
                path.cubicTo(x(15.5f), y(17.8f), x(18.5f), y(15.2f), x(18.5f), y(11f))
                path.moveTo(x(12f), y(17.8f)); path.lineTo(x(12f), y(21f))
            }
            DELETE -> {
                path.moveTo(x(8.5f), y(5.5f)); path.lineTo(x(20f), y(5.5f)); path.lineTo(x(20f), y(18.5f))
                path.lineTo(x(8.5f), y(18.5f)); path.lineTo(x(3f), y(12f)); path.close()
                path.moveTo(x(11.5f), y(9.5f)); path.lineTo(x(16.5f), y(14.5f))
                path.moveTo(x(16.5f), y(9.5f)); path.lineTo(x(11.5f), y(14.5f))
            }
            SPACE -> {
                path.moveTo(x(4f), y(11f)); path.lineTo(x(4f), y(15f)); path.lineTo(x(20f), y(15f)); path.lineTo(x(20f), y(11f))
            }
        }
    }

    override fun draw(canvas: Canvas) { canvas.drawPath(path, paint) }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object { const val MIC = 0; const val DELETE = 1; const val SPACE = 2 }
}

/**
 * Dream keyboard: a 6-column grid of fully rounded keys driven by the D-pad. One highlight glides
 * under the keys to the focused one (a short eased move, then the keyboard is still again).
 *
 * Remote: OK types; hold OK on delete to keep deleting (whole words after a moment); rewind /
 * fast-forward move the caret; the mic key (or the remote's mic button) starts voice search.
 * Letters typed on a hardware keyboard or the phone remote go straight in.
 */
class DreamKeyboard(
    private val kit: PremiumKit,
    private val field: QueryField,
    widthDp: Int,
    keyHDp: Int = KEY_H,
    gapDp: Int = GAP,
    /** The mic key's action; null: no mic key (space takes its place). */
    private val onVoice: (() -> Unit)?,
) : FrameLayout(kit.ctx) {

    private val ctx = kit.ctx
    private val Int.dp get() = (this * ctx.resources.displayMetrics.density).toInt()
    private val keyW: Int
    private val keyH = keyHDp.dp
    private val gap = gapDp.dp
    private val letterSize = if (keyHDp < KEY_H) 17f else 19f
    private val glide = Glide()
    private val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val charKeys = ArrayList<TextView>()
    private var symbols = false
    private lateinit var modeKey: TextView
    /** The highlight's two colors (left, right); null: the theme's highlight glow. Dream Cinema gives the rainbow hue. */
    var glideColors: (() -> IntArray)? = null
    /** True: the highlight is a solid box in the theme's highlight with a bright edge in [glideColors] (Dream Cinema), not a gradient glow. */
    var glideStroke = false

    /** The highlight's colors changed (Dream Cinema's fading rainbow): repaint it, nothing else. */
    fun refreshGlide() = glide.refresh()

    /** The key focus was on when it last left the keyboard (results → Left/Back comes back here). */
    var lastKey: View? = null
        private set

    init {
        keyW = (widthDp.dp - gap * (COLS - 1)) / COLS
        clipChildren = false
        clipToPadding = false
        grid.clipChildren = false
        grid.clipToPadding = false
        addView(glide, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(grid, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        for (r in 0 until LETTERS.size) {
            val row = row(r > 0)
            for (c in 0 until COLS) {
                val k = key(LETTERS[r][c].toString(), 1) { field.insert((it as TextView).text.toString()) }
                charKeys += k
                row.addView(k, keyParams(1, c > 0))
            }
        }
        val last = row(true)
        val voice = onVoice
        if (voice != null) last.addView(glyphKey(KeyGlyph.MIC, "Voice search") { voice() }, keyParams(1, false))
        modeKey = key("123", 1) { toggleSymbols() }.apply { textSize = Ui.T_CAPTION; typeface = kit.semibold }
        last.addView(modeKey, keyParams(1, voice != null))
        last.addView(glyphKey(KeyGlyph.SPACE, "Space") { field.insert(" ") }, keyParams(if (voice != null) 2 else 3, true))
        last.addView(deleteKey(), keyParams(1, true))
        last.addView(key("Clear", 1) { field.setQuery("") }.apply { textSize = Ui.T_CAPTION; typeface = kit.semibold }, keyParams(1, true))
    }

    /** The key to land on when the keyboard gets focus. */
    fun entryKey(): View = lastKey?.takeIf { it.isAttachedToWindow } ?: charKeys.first()

    fun focusEntry(): Boolean = entryKey().requestFocus()

    fun isKey(v: View) = HomeShell.isInside(v, grid)

    private fun row(top: Boolean) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        grid.addView(this, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (top) topMargin = gap })
    }

    private fun keyParams(span: Int, lead: Boolean) =
        LinearLayout.LayoutParams(keyW * span + gap * (span - 1), keyH).apply { if (lead) marginStart = gap }

    private fun keyBackground(): Drawable = StateListDrawable().apply {
        // Focused: clear, so the gliding highlight underneath shows through.
        addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(0))
        addState(IntArray(0), Ui.rounded(ctx, Ui.alpha(kit.text, 0.075f), Ui.R_MD, 1f, Ui.alpha(kit.text, 0.05f)))
        setEnterFadeDuration(Ui.FOCUS_IN.toInt())
        setExitFadeDuration(Ui.FOCUS_OUT.toInt())
    }

    private fun base(v: View, onClick: (View) -> Unit) {
        v.isFocusable = true
        v.isClickable = true
        v.background = keyBackground()
        v.id = View.generateViewId()
        v.setOnClickListener { onClick(it) }
        v.setOnKeyListener { _, code, e -> remoteKey(code, e) }
    }

    private fun key(label: String, span: Int, onClick: (View) -> Unit) = TextView(ctx).apply {
        text = label
        textSize = letterSize
        typeface = kit.medium
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), IntArray(0)),
            intArrayOf(kit.onSelect, Ui.alpha(kit.text, 0.9f))))
        base(this, onClick)
        setOnFocusChangeListener { v, has -> focusMoved(v, has) }
        tag = span
    }

    private fun glyphKey(shape: Int, desc: String, onClick: (View) -> Unit) = TextView(ctx).apply {
        val g = KeyGlyph(shape, 22.dp).apply { color = Ui.alpha(kit.text, 0.9f) }
        gravity = Gravity.CENTER
        contentDescription = desc
        base(this, onClick)
        foreground = g
        setOnFocusChangeListener { v, has ->
            g.color = if (has) kit.onSelect else Ui.alpha(kit.text, 0.9f)
            focusMoved(v, has)
        }
    }

    /** Delete: OK deletes one; holding OK keeps deleting, by whole words after a moment. */
    private fun deleteKey(): View = glyphKey(KeyGlyph.DELETE, "Delete") { field.backspace() }.apply {
        setOnKeyListener { v, code, e ->
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (e.action == KeyEvent.ACTION_DOWN) {
                    val n = e.repeatCount
                    when {
                        n == 0 -> field.backspace()
                        n > WORD_AFTER -> if (n % 3 == 0) field.backspace(word = true)
                        else -> field.backspace()
                    }
                    if (n == 0) Ui.press(v)
                } else {
                    Ui.release(v)
                }
                true
            } else remoteKey(code, e)
        }
    }

    /** Keys from the remote (or a keyboard) that edit the text wherever the keyboard's focus is. */
    private fun remoteKey(code: Int, e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) {
            return code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || code == KeyEvent.KEYCODE_DEL
        }
        when (code) {
            KeyEvent.KEYCODE_MEDIA_REWIND -> { field.moveCaret(-1); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { field.moveCaret(1); return true }
            KeyEvent.KEYCODE_DEL -> { field.backspace(); return true }
            KeyEvent.KEYCODE_SPACE -> { field.insert(" "); return true }
        }
        val ch = e.unicodeChar
        if (ch != 0 && !Character.isISOControl(ch) && code !in NAV) {
            field.insert(String(Character.toChars(ch))) // as typed: keys and addresses can need capitals
            return true
        }
        return false
    }

    /** Capital letters on the letter keys (typed addresses can need them). */
    var caps = false
        set(v) {
            field = v
            if (!symbols) for ((i, k) in charKeys.withIndex()) LETTERS[i / COLS][i % COLS].toString().let { k.text = if (v) it.uppercase() else it }
        }

    private fun toggleSymbols() {
        symbols = !symbols
        val src = if (symbols) SYMBOLS else LETTERS
        for ((i, k) in charKeys.withIndex()) k.text = src[i / COLS][i % COLS].toString().let { if (caps && !symbols) it.uppercase() else it }
        modeKey.text = if (symbols) "abc" else "123"
    }

    private fun focusMoved(v: View, has: Boolean) {
        Ui.lift(v, has, KEY_LIFT)
        if (has) {
            lastKey = v
            // Focused before the first layout (page just shown): place the highlight once it has a size.
            if (v.width == 0) { v.post { if (v.isFocused) focusMoved(v, true) }; return }
            val r = Rect(0, 0, v.width, v.height)
            offsetDescendantRectToMyCoords(v, r)
            glide.moveTo(RectF(r))
        } else {
            // Leaving the keyboard altogether: the highlight fades where it is.
            post { if (findFocus() == null) glide.hide() }
        }
    }

    /** The one highlight: glides between keys, scaled with the key's lift. */
    private inner class Glide : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val from = RectF()
        private val to = RectF()
        private val now = RectF()
        private var shown = false
        private var anim: ValueAnimator? = null
        private val radius = Ui.R_MD * ctx.resources.displayMetrics.density

        init { alpha = 0f }

        fun moveTo(r: RectF) {
            // Grows with the key's lift, about its center.
            val dx = r.width() * (KEY_LIFT - 1f) / 2f
            val dy = r.height() * (KEY_LIFT - 1f) / 2f
            to.set(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy)
            anim?.cancel()
            if (!shown) {
                shown = true
                now.set(to)
                updateShader()
                invalidate()
                animate().alpha(1f).setDuration(Ui.FOCUS_IN).setInterpolator(Ui.EASE).start()
                return
            }
            animate().alpha(1f).setDuration(Ui.FOCUS_IN).start()
            from.set(now)
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = GLIDE_MS
                interpolator = Ui.SPRING
                addUpdateListener {
                    val t = it.animatedValue as Float
                    now.set(from.left + (to.left - from.left) * t, from.top + (to.top - from.top) * t,
                        from.right + (to.right - from.right) * t, from.bottom + (to.bottom - from.bottom) * t)
                    if (now.width() - from.width() != 0f || t >= 1f) updateShader()
                    invalidate()
                }
                start()
            }
        }

        fun hide() {
            shown = false
            anim?.cancel()
            animate().alpha(0f).setDuration(Ui.FOCUS_OUT).setInterpolator(Ui.SETTLE).start()
        }

        // Dream Cinema's focused key: a pearl box (the theme's highlight there) with the fading rainbow edge.
        private val surface = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = kit.select }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * ctx.resources.displayMetrics.density }
        private val inner = RectF()

        private fun updateShader() {
            val c = glideColors?.invoke()
            if (glideStroke) { edge.color = c?.getOrNull(0) ?: kit.accent; return }
            paint.shader = LinearGradient(now.left, 0f, now.right, 0f, c?.getOrNull(0) ?: kit.select, c?.getOrNull(1) ?: kit.blush, Shader.TileMode.CLAMP)
        }

        fun refresh() {
            if (!shown) return
            updateShader()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (now.isEmpty) return
            if (glideStroke) {
                canvas.drawRoundRect(now, radius, radius, surface)
                val h = edge.strokeWidth / 2f
                inner.set(now.left + h, now.top + h, now.right - h, now.bottom - h)
                canvas.drawRoundRect(inner, radius - h, radius - h, edge)
                return
            }
            canvas.drawRoundRect(now, radius, radius, paint)
        }
    }

    companion object {
        const val COLS = 6
        const val KEY_H = 44
        const val GAP = 8
        private const val KEY_LIFT = 1.08f
        private const val GLIDE_MS = 200L
        private const val WORD_AFTER = 14
        private val NAV = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK)
        private val LETTERS = listOf("abcdef", "ghijkl", "mnopqr", "stuvwx", "yz'-.&")
        private val SYMBOLS = listOf("123456", "7890.,", "!?:;'\"", "()/+-&", "#@$%*=")
    }
}

/**
 * The listening light in the search field: a mic disc that breathes with the voice. It only moves
 * when the audio level changes, and rests still between words. While Alexa looks up what it heard
 * ([searching]) the glow breathes on its own, timed by the clock so a copy of it on the hand-off's
 * cover (AlexaSearch) breathes in step with this one.
 */
class ListenIndicator(private val kit: PremiumKit) : View(kit.ctx) {
    private val d = kit.ctx.resources.displayMetrics.density
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = KeyGlyph(KeyGlyph.MIC)
    private var level = 0f
    private var anim: ValueAnimator? = null
    /** Listening (glow on) or resting (a quiet mic glyph). */
    var active = false
        set(v) {
            if (field == v) return
            field = v
            if (!v) { setLevel(0f); searching = false }
            glyph.color = if (v) kit.onSelect else Ui.alpha(kit.text, 0.55f)
            invalidate()
        }
    /** Alexa has heard and the search is on its way: the glow breathes until it lands (only while [active]). */
    var searching = false
        set(v) { if (field != v) { field = v; invalidate() } }

    init { glyph.color = Ui.alpha(kit.text, 0.55f) }

    fun setLevel(l: Float) {
        val target = l.coerceIn(0f, 1f)
        if (kotlin.math.abs(target - level) < 0.02f && anim?.isRunning != true) return
        anim?.cancel()
        anim = ValueAnimator.ofFloat(level, target).apply {
            duration = 90
            interpolator = Ui.SETTLE
            addUpdateListener { level = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val s = (20 * d).toInt()
        glyph.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2)
        disc.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, kit.select, kit.blush, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        if (active) {
            val l = if (searching) breath() else level
            halo.color = Ui.alpha(kit.select, 0.18f + 0.22f * l)
            canvas.drawCircle(cx, cy, r * (0.62f + 0.38f * l), halo)
            canvas.drawCircle(cx, cy, r * 0.56f, disc)
            if (searching) postInvalidateOnAnimation()
        }
        glyph.draw(canvas)
    }

    private fun breath(): Float {
        val t = (android.os.SystemClock.uptimeMillis() % BREATH_MS) / BREATH_MS.toFloat()
        return 0.5f - 0.5f * kotlin.math.cos(t * 2f * Math.PI.toFloat())
    }

    private companion object {
        const val BREATH_MS = 1400L
    }
}
