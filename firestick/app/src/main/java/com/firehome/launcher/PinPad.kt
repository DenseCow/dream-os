package com.firehome.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Implemented by MainActivity: shows [view] full-screen over the app, locks focus underneath and focuses [focus]. */
interface ModalHost { fun show(view: android.view.View, focus: android.view.View); fun hide(view: android.view.View) }

/**
 * Full-screen 4-digit PIN entry: dim scrim + centered card with dots and a D-pad friendly keypad.
 * [onEntered] fires automatically once 4 digits are in (or on ✓).
 */
class PinPad(
    private val ctx: Context,
    title: String,
    subtitle: String?,
    private val onEntered: (String) -> Unit,
) {
    private val digits = StringBuilder()
    private val dots = ArrayList<View>()
    private var submitting = false
    private val select = Theme.get(ctx, Theme.SELECT)
    /** Dream Cinema: the brand's neutral grays (no navy card, no blue-gray text). */
    private val cardBg = Color.parseColor("#1C1C1E")
    private val dim = Color.parseColor("#A1A1A6")

    private val titleView = text(title, 24f, Color.WHITE, bold = true).apply {
        gravity = Gravity.CENTER
        typeface = Ui.semibold(Typeface.DEFAULT)
        letterSpacing = Ui.TRACK_TITLE
    }
    private val subtitleView = text(subtitle ?: " ", 15f, dim).apply {
        gravity = Gravity.CENTER
        setPadding(0, 6.dp, 0, 0)
    }
    private val dotsRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, 22.dp, 0, 22.dp)
    }

    /** Root view to pass to [ModalHost.show]. Digit / delete hardware keys are handled here. */
    val view: FrameLayout = object : FrameLayout(ctx) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val digit = when (event.keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> event.keyCode - KeyEvent.KEYCODE_0
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> event.keyCode - KeyEvent.KEYCODE_NUMPAD_0
                else -> -1
            }
            if (digit >= 0) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) append(digit)
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                if (event.action == KeyEvent.ACTION_DOWN) backspace()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    /** The "1" key; give it to [ModalHost.show] as the initial focus. */
    val firstFocus: View

    init {
        repeat(4) {
            val d = View(ctx)
            dots += d
            dotsRow.addView(d, LinearLayout.LayoutParams(16.dp, 16.dp).apply { marginStart = 9.dp; marginEnd = 9.dp })
        }
        refreshDots(popIndex = -1)

        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val keyRows = ArrayList<View>()
        var one: View? = null
        val layout = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓"))
        for (rowKeys in layout) {
            grid.addView(LinearLayout(ctx).apply {
                keyRows += this
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                for (k in rowKeys) {
                    val key = when (k) {
                        "⌫" -> key(k, accent = false, desc = "Delete") { backspace() }
                        // Done: a drawn check (never a text "✓"), ink on the focused face.
                        "✓" -> key("", accent = true, desc = "Done") { submit(fromButton = true) }.apply {
                            foreground = CheckInk(Color.WHITE, PearlFocus.INK, weight = 0.06f)
                        }
                        else -> key(k, accent = false, desc = k) { append(k.toInt()) }
                    }
                    if (k == "1") one = key
                    addView(key, LinearLayout.LayoutParams(64.dp, 64.dp).apply {
                        marginStart = 8.dp; marginEnd = 8.dp; topMargin = 6.dp; bottomMargin = 6.dp
                    })
                }
            })
        }
        firstFocus = one!!

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(36.dp, 28.dp, 36.dp, 24.dp)
            background = Ui.rounded(ctx, cardBg, Ui.R_XL, 1f, HAIRLINE)
            addView(titleView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(subtitleView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(dotsRow, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(grid, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            // Dream Cinema shows no remote hints (Back simply cancels).
        }
        // Focused keys and popping dots grow past their rows: only the card's own edge clips.
        Ui.unclip(card)
        view.apply {
            isClickable = true // swallow touches meant for the app underneath
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            addView(card, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        }
        // The host fades the scrim in; the card rises and the key rows follow a beat behind.
        Ui.rise(listOf(card), fromDp = 16, duration = Ui.OVERLAY_IN)
        Ui.rise(keyRows, fromDp = 10, startDelay = 60L, duration = Ui.OVERLAY_IN)
    }

    /** Shakes the dots, clears the digits and shows [message] in red. */
    fun showError(message: String) {
        submitting = false
        digits.setLength(0)
        refreshDots(popIndex = -1)
        subtitleView.text = message
        subtitleView.setTextColor(ERROR)
        shake()
    }

    /** Resets the pad for another step (e.g. "Confirm your PIN"). */
    fun setText(title: String, subtitle: String?) {
        submitting = false
        digits.setLength(0)
        refreshDots(popIndex = -1)
        titleView.text = title
        subtitleView.text = subtitle ?: " "
        subtitleView.setTextColor(dim)
    }

    // ---- input ----

    private fun append(d: Int) {
        if (submitting || digits.length >= 4) return
        digits.append(d)
        refreshDots(popIndex = digits.length - 1)
        if (digits.length == 4) submit(fromButton = false)
    }

    private fun backspace() {
        if (submitting || digits.isEmpty()) return
        digits.setLength(digits.length - 1)
        refreshDots(popIndex = -1)
    }

    private fun submit(fromButton: Boolean) {
        if (submitting) return
        if (digits.length < 4) {
            if (fromButton) shake()
            return
        }
        submitting = true
        val pin = digits.toString()
        // Short pause so the 4th dot visibly fills before the result shows.
        view.postDelayed({
            // Cancelled (hidden) meanwhile, or reset by setText/showError: drop it.
            if (submitting && view.isAttachedToWindow && digits.toString() == pin) {
                submitting = false
                onEntered(pin)
            }
        }, 140)
    }

    // ---- visuals ----

    private fun refreshDots(popIndex: Int) {
        for ((i, d) in dots.withIndex()) {
            d.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (i < digits.length) setColor(Color.WHITE)
                else { setColor(Color.parseColor("#14FFFFFF")); setStroke(maxOf(1, 1.5f.dp.toInt()), Color.parseColor("#66FFFFFF")) }
            }
            if (i == popIndex) {
                d.animate().cancel()
                // Fills from small to full with a slight spring.
                d.scaleX = 0.55f
                d.scaleY = 0.55f
                d.animate().scaleX(1f).scaleY(1f).setDuration(Ui.FOCUS_IN).setInterpolator(Ui.SPRING).start()
            }
        }
    }

    /** ~300 ms horizontal shake of the dots row, built from short chained steps. */
    private fun shake() {
        dotsRow.animate().cancel()
        dotsRow.translationX = 0f
        val steps = floatArrayOf(16f, -16f, 11f, -11f, 5f, 0f)
        fun step(i: Int) {
            if (i >= steps.size) return
            dotsRow.animate().translationX(steps[i].dp).setDuration(50).withEndAction { step(i + 1) }.start()
        }
        step(0)
    }

    private fun key(label: String, accent: Boolean, desc: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = if (label.length == 1 && label[0].isDigit()) 24f else 22f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        contentDescription = desc
        isFocusable = true
        isClickable = true
        // Focused key fills with the selection color; idle keys are quiet glass discs.
        typeface = Ui.medium(Typeface.DEFAULT)
        includeFontPadding = false
        setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), IntArray(0)),
            intArrayOf(PearlFocus.INK, Color.WHITE),
        ))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(select)
            })
            addState(IntArray(0), GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (accent) Ui.alpha(select, 0.32f) else Color.parseColor("#14FFFFFF"))
                setStroke(1.dp, if (accent) Ui.alpha(select, 0.6f) else HAIRLINE)
            })
        }
        setOnFocusChangeListener { v, has -> Ui.lift(v, has, Ui.LIFT_ICON) }
        // Dream Cinema: a pearl disc under the rainbow border (keys are 64 dp: radius 32).
        cinemaRowFocus(this, 32, if (accent) Ui.alpha(select, 0.32f) else Color.parseColor("#14FFFFFF"), if (accent) Ui.alpha(select, 0.6f) else HAIRLINE)
        setOnClickListener { onClick() }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private val Int.dp get() = (this * ctx.resources.displayMetrics.density).toInt()
    private val Float.dp get() = this * ctx.resources.displayMetrics.density

    companion object {
        private val ERROR = Color.parseColor("#F87171")
        private val HAIRLINE = Color.parseColor("#17FFFFFF")
    }
}

/**
 * Per-profile PIN flows (verify / set) shown through [host]. Only one pad at a time;
 * route BACK to [handleBack] while it is open.
 */
class PinPrompt(private val ctx: Context, private val host: ModalHost) {
    private var pad: PinPad? = null
    private var onCancel: (() -> Unit)? = null

    val isOpen get() = pad != null

    /** Runs [onOk] right away when profile [p] has no PIN, otherwise after its PIN is entered. */
    fun verify(p: Profiles.Profile, onOk: () -> Unit) {
        if (!Profiles.hasPin(ctx, p.id)) return onOk()
        open("Enter PIN", "${p.emoji}  ${p.name}", null) { entered, pp ->
            if (Profiles.checkPin(ctx, p.id, entered)) { close(); onOk() }
            else Profiles.pinWaitSeconds(ctx, p.id).let { w ->
                pp.showError(if (w <= 0) "Wrong PIN, try again" else if (w < 60) "Too many tries. Try again in $w seconds" else "Too many tries. Try again in ${(w + 59) / 60} minutes")
            }
        }
    }

    /** Choose + confirm a new PIN for profile [id]. [onDone] gets false if the user backs out. */
    fun setNew(id: String, onDone: (saved: Boolean) -> Unit) {
        var first: String? = null
        open(STEP1, null, { onDone(false) }) { entered, p ->
            val f = first
            when {
                f == null -> { first = entered; p.setText("Confirm your PIN", "Enter the same 4 digits again") }
                f == entered -> { Profiles.setPin(ctx, id, entered); close(); onDone(true) }
                else -> { first = null; p.setText(STEP1, null); p.showError("PINs didn't match") }
            }
        }
    }

    /** Cancels the open pad (its caller hears back). Returns false when no pad is showing. */
    fun handleBack(): Boolean {
        if (pad == null) return false
        val cancel = onCancel
        close()
        cancel?.invoke()
        return true
    }

    private fun open(title: String, subtitle: String?, cancel: (() -> Unit)?, onEntered: (String, PinPad) -> Unit) {
        handleBack() // replacing a pad counts as cancelling it, so its onCancel isn't lost
        lateinit var p: PinPad
        // The pad fires 140 ms after the 4th digit; ignore it if this pad was closed or replaced meanwhile.
        p = PinPad(ctx, title, subtitle) { if (pad === p) onEntered(it, p) }
        pad = p
        onCancel = cancel
        host.show(p.view, p.firstFocus)
    }

    private fun close() {
        pad?.let { host.hide(it.view) }
        pad = null
        onCancel = null
    }

    private companion object {
        const val STEP1 = "Choose a 4-digit PIN"
    }
}
