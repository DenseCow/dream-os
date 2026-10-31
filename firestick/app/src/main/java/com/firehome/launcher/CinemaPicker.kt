package com.firehome.launcher

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** One choice in a [CinemaPicker]'s panel. [glyph]: a drawn genre glyph ([GenreArt] name) before the label, in [tint]. */
class PickItem(val id: String, val label: String, val glyph: String? = null, val tint: Int = Color.WHITE)

/**
 * The TV's picker (owner, 2026-10-30; STANDARDS §3): a button naming the current choice with a drawn chevron, the
 * iPhone's DreamPickerButton for the remote. Select opens a full-height panel on the right: the heading, then one
 * big row per choice (the current one marked with a drawn check, genres with their glyphs). Up and Down move,
 * Select picks and closes, Back closes, and focus comes back to this button. Never a small drop-down.
 * The panel sits over [host] (the shell's root, above the bar) and exists only while open.
 */
class CinemaPicker(
    private val kit: CinemaKit,
    private val host: ViewGroup,
    val heading: String,
    items: List<PickItem>,
    selected: String,
    private val onPick: (String) -> Unit,
) : TextView(kit.ctx) {
    private val Int.dp get() = Ui.dp(context, this)
    var items: List<PickItem> = items
        private set
    var selected: String = selected
        private set
    private var sheet: Sheet? = null

    init {
        textSize = 15.5f
        typeface = kit.medium
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        maxLines = 1
        val colors = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), IntArray(0)), intArrayOf(PearlFocus.INK, kit.text))
        setTextColor(colors)
        setCompoundDrawables(null, null, Chevron(kit.text, PearlFocus.INK).apply { setBounds(0, 0, 14.dp, 14.dp) }, null)
        compoundDrawablePadding = 10.dp
        setPadding(20.dp, 0, 16.dp, 0)
        val glow = CinemaStyle.glowDrawable(context, radiusDp = CinemaKit.BUTTON_H / 2, haloDp = CinemaStyle.GLOW_HALO_SMALL)
        background = LayerDrawable(arrayOf(glow, StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(context, PearlFocus.PEARL, CinemaStyle.R_CHIP))
            addState(IntArray(0), Ui.rounded(context, Ui.alpha(kit.text, 0.08f), CinemaStyle.R_CHIP, 1f, Ui.alpha(kit.text, 0.16f)))
        }))
        isFocusable = true
        isClickable = true
        id = View.generateViewId()
        setOnClickListener { open() }
        setOnFocusChangeListener { v, has ->
            PearlFocus.focus(glow, has)
            val s = if (has) 1.06f else 1f
            v.animate().scaleX(s).scaleY(s).setStartDelay(0).setDuration(CinemaStyle.FOCUS_MS).setInterpolator(Ui.EASE).start()
        }
        showChoice()
    }

    /** New choices (the genres after Movies or TV Shows changed) and the current one. */
    fun set(items: List<PickItem>, selected: String) {
        this.items = items
        this.selected = selected
        showChoice()
    }

    private fun showChoice() {
        val label = items.firstOrNull { it.id == selected }?.label ?: heading
        text = label
        contentDescription = "$heading, $label"
    }

    fun open() {
        if (sheet != null || !isAttachedToWindow) return
        sheet = Sheet().also { it.show() }
    }

    /** Closes the panel at once (the page is going away); focus is left to the page that comes next. */
    fun dismiss() {
        sheet?.let { host.removeView(it) }
        sheet = null
    }

    private fun closed(pick: String?) {
        sheet = null
        requestFocus()
        if (pick != null && pick != selected) {
            selected = pick
            showChoice()
            onPick(pick)
        }
    }

    /** The full-height panel on the right over a dimmed screen. Focus stays inside it until it closes. */
    private inner class Sheet : FrameLayout(kit.ctx) {
        private val panelColor = 0xFF111113.toInt()
        private val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panelColor)
            clipChildren = false
        }
        private val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            // Room for the focused row's glow and lift on every side, inside the scroller.
            setPadding(24.dp, 18.dp, 24.dp, 28.dp)
        }
        private val scroll = SmoothScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            keyScroll = false
            margin = 28.dp
            clipChildren = false
            clipViewport = true
            softEdges(panelColor, panelColor, 28.dp)
            addView(list, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        private var current: View? = null
        private var leaving = false

        init {
            isClickable = true
            addView(View(context).apply { setBackgroundColor(Color.argb(150, 0, 0, 0)) }, MATCH_PARENT, MATCH_PARENT)
            val w = minOf(440.dp, (kit.screenW * 0.46f).toInt())
            addView(panel, LayoutParams(w, MATCH_PARENT, Gravity.END))
            // A hairline where the panel meets the dimmed page.
            addView(View(context).apply { setBackgroundColor(Ui.alpha(Color.WHITE, 0.08f)) },
                LayoutParams(1.dp.coerceAtLeast(1), MATCH_PARENT, Gravity.END).apply { marginEnd = w })
            panel.addView(kit.label(heading, 24f, kit.text, kit.semibold).apply { setPadding(48.dp, 48.dp, 32.dp, 6.dp) },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            panel.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            val glyphs = items.any { it.glyph != null }
            for (item in items) {
                val row = row(item, item.id == selected, glyphs)
                list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, ROW_H.dp).apply { bottomMargin = 8.dp })
                if (item.id == selected) current = row
            }
            if (current == null) current = list.getChildAt(0)
        }

        fun show() {
            host.addView(this, MATCH_PARENT, MATCH_PARENT)
            alpha = 0f
            panel.translationX = 48.dp.toFloat()
            animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
            panel.animate().translationX(0f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).withLayer().start()
            // Opens on the current choice, already in the middle of the panel (no glide down a long list).
            scroll.post {
                val row = current ?: return@post
                scroll.scrollTo(0, (row.top - (scroll.height - row.height) / 2).coerceAtLeast(0))
                row.requestFocus()
            }
        }

        private fun close(pick: String?) {
            if (leaving) return
            leaving = true
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            closed(pick)
            animate().alpha(0f).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT)
                .withEndAction { host.removeView(this) }.start()
        }

        /** Up and Down stay in the list; Left and Right go nowhere (nothing behind the panel takes focus). */
        override fun focusSearch(focused: View?, direction: Int): View? {
            val r = super.focusSearch(focused, direction)
            return if (r != null && HomeShell.isInside(r, this)) r else focused
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) close(null)
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        /** A choice: a big row, pearl with dark ink and the rainbow glow when focused; the current one has a drawn check. */
        private fun row(item: PickItem, isCurrent: Boolean, glyphs: Boolean): View = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(if (glyphs) 14.dp else 22.dp, 0, 22.dp, 0)
            isFocusable = true
            isClickable = true
            contentDescription = item.label
            isSelected = isCurrent
            val rest = Ui.rounded(context, if (isCurrent) Ui.alpha(kit.text, 0.08f) else Color.TRANSPARENT, CinemaStyle.R_CARD)
            val glow = PearlFocus.apply(this, CinemaStyle.R_CARD, rest)
            setOnFocusChangeListener { v, has ->
                PearlFocus.focus(glow, has)
                Ui.lift(v, has, Ui.LIFT_ROW)
            }
            setOnClickListener { close(item.id) }
            if (item.glyph != null) {
                addView(ImageView(context).apply {
                    setImageDrawable(GenreArt(item.glyph, item.tint))
                    isDuplicateParentStateEnabled = true
                }, LinearLayout.LayoutParams(38.dp, 38.dp).apply { marginEnd = 16.dp })
            }
            addView(kit.label(item.label, 18f, kit.text, if (isCurrent) kit.semibold else kit.medium).apply {
                setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), IntArray(0)), intArrayOf(PearlFocus.INK, kit.text)))
                isDuplicateParentStateEnabled = true
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (isCurrent) {
                addView(ImageView(context).apply {
                    setImageDrawable(CheckInk(kit.text, PearlFocus.INK))
                    isDuplicateParentStateEnabled = true
                }, LinearLayout.LayoutParams(24.dp, 24.dp).apply { marginStart = 16.dp })
            }
        }
    }

    /** The button's drawn chevron: [rest] at rest, [focusedColor] on the pearl face. */
    private class Chevron(private val rest: Int, private val focusedColor: Int) : Drawable() {
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = rest
        }
        private var focused = false

        override fun draw(c: Canvas) {
            val b = bounds
            val s = minOf(b.width(), b.height()).toFloat()
            val u = s / 24f
            val l = b.exactCenterX() - 12 * u
            val t = b.exactCenterY() - 12 * u
            pen.strokeWidth = 2.6f * u
            pen.color = if (focused) focusedColor else rest
            c.drawPath(Path().apply { moveTo(l + 5 * u, t + 9 * u); lineTo(l + 12 * u, t + 16 * u); lineTo(l + 19 * u, t + 9 * u) }, pen)
        }

        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean {
            val f = state.contains(android.R.attr.state_focused)
            if (f == focused) return false
            focused = f
            invalidateSelf()
            return true
        }
        override fun setAlpha(alpha: Int) { pen.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { pen.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object {
        /** A choice's height: big enough to read and aim at from the couch. */
        const val ROW_H = 64
    }
}

/**
 * A line of [CinemaPicker]s as one page section: Left and Right move along it (never off its ends), Up and Down
 * leave it like any row, and coming back lands on the picker last used.
 */
class CinemaPickerLine(kit: CinemaKit, pickers: List<CinemaPicker>) : FlatLinear(kit.ctx), CinemaSection {
    private val pickers = pickers.toList()
    private var last: CinemaPicker? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(kit.gutter, 0, kit.gutter, 0)
        for ((i, p) in this.pickers.withIndex()) {
            val base = p.onFocusChangeListener
            p.setOnFocusChangeListener { v, has -> base?.onFocusChange(v, has); if (has) last = p }
            addView(p, LayoutParams(WRAP_CONTENT, Ui.dp(context, CinemaKit.BUTTON_H)).apply { if (i > 0) marginStart = Ui.dp(context, 14) })
        }
    }

    override fun entry(): View? = last ?: pickers.firstOrNull()

    override fun focusSearch(focused: View?, direction: Int): View? {
        if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) {
            val i = pickers.indexOf(focused)
            if (i >= 0) return pickers.getOrNull(i + if (direction == View.FOCUS_RIGHT) 1 else -1) ?: focused
        }
        return super.focusSearch(focused, direction)
    }

    /** Closes any open panel at once (the page is hiding). */
    fun dismiss() = pickers.forEach { it.dismiss() }
}
