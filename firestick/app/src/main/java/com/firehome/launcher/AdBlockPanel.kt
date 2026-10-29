package com.firehome.launcher

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * "Ad blocking (DNS)" card for the Settings menu: current status, provider choice (OK picks),
 * a short note about what it can't block. Colors follow the theme's highlight colors.
 * The host shows [view] in its modal and calls [handleBack] on BACK.
 */
class AdBlockPanel(
    private val ctx: Context,
    private val accent: Int,
    private val select: Int,
    private val font: Typeface,
    private val fontBold: Typeface,
    /** Shows a result when the card was closed before a check finished. */
    private val notify: (String) -> Unit,
) {
    /** Dream Cinema: the brand's neutral gray for quiet text */
    private val dim = Color.parseColor("#A1A1A6")
    val view: LinearLayout
    private val statusDot = View(ctx)
    private val statusTitle = text("", 18f, Color.WHITE, bold = true)
    private val statusDetail = text("", 13f, dim)
    private val rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    /** Kept in [AdBlock] so a card reopened while a check still runs can't start a second one. */
    private var busy: Boolean
        get() = AdBlock.running
        set(v) { AdBlock.running = v }
    private var closed = false
    private var firstBuild = true

    /** Opened while an earlier card's check still runs: its result shows here once it lands. */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val watchEarlierCheck: Runnable = object : Runnable {
        override fun run() {
            if (closed) return
            if (busy) { main.postDelayed(this, 500); return }
            showStatus()
            buildRows(null)
        }
    }

    init {
        view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // The theme's glass sheet with a hairline edge.
            background = Ui.rounded(ctx, Ui.surface(ctx), Ui.R_XL, 1f, Color.argb(20, 255, 255, 255))
            setPadding(30.dp, 24.dp, 30.dp, 20.dp)
            addView(text("Ad Blocking (DNS)", 24f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                typeface = Ui.semibold(font)
                letterSpacing = Ui.TRACK_TITLE
            }, LinearLayout.LayoutParams(WIDTH.dp, WRAP_CONTENT))
            addView(View(ctx).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.TRANSPARENT, select, accent, Color.TRANSPARENT))
            }, LinearLayout.LayoutParams(96.dp, maxOf(1, Ui.dpf(ctx, 1.5f).toInt()))
                .apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = 10.dp; bottomMargin = 16.dp })
            // Status card: colored dot, headline, one line of detail.
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(18.dp, 14.dp, 18.dp, 14.dp)
                background = GradientDrawable().apply {
                    cornerRadius = Ui.R_LG.dp.toFloat()
                    setColor(Color.argb(20, 255, 255, 255))
                    setStroke(1, Color.argb(22, 255, 255, 255))
                }
                addView(statusDot, LinearLayout.LayoutParams(12.dp, 12.dp).apply { marginEnd = 14.dp })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(statusTitle)
                    addView(statusDetail.apply { setPadding(0, 2.dp, 0, 0) })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(WIDTH.dp, WRAP_CONTENT).apply { bottomMargin = 14.dp })
            addView(rows, LinearLayout.LayoutParams(WIDTH.dp, WRAP_CONTENT))
            addView(text("Blocks ads and trackers in many apps and websites. It does not block YouTube or Prime Video " +
                "in-stream ads. Those come from the same servers as the video.", 12.5f, dim).apply {
                setLineSpacing(0f, 1.15f)
                setPadding(6.dp, 14.dp, 6.dp, 0)
            }, LinearLayout.LayoutParams(WIDTH.dp, WRAP_CONTENT))
        }
        showStatus(if (busy) "The last change is still being checked." else null)
        buildRows(null)
        if (busy) main.postDelayed(watchEarlierCheck, 500)
    }

    /** The row to focus when the card opens: the current choice. */
    val focusView: View get() = rows.findViewWithTag(currentTag()) ?: rows.getChildAt(0)

    /** Nothing nested to step out of (the hostname prompt handles its own BACK): close the card. */
    fun handleBack(): Boolean {
        closed = true
        return false
    }

    private fun currentTag(): String {
        val s = AdBlock.status(ctx)
        return when {
            !s.on -> "off"
            s.provider != null -> s.provider.id
            else -> "custom"
        }
    }

    // ---- status ----

    private fun showStatus(progress: String? = null) {
        val s = AdBlock.status(ctx)
        val last = AdBlock.lastResult(ctx)
        val (color, title, detail) = when {
            !AdBlock.supported() -> Triple(dim, "Not Available", "Private DNS needs Fire OS 7 (Android 9) or newer.")
            progress != null -> Triple(accent, "Working…", progress)
            last?.kind == "ok" && s.on -> Triple(OK, last.message, "${s.label} · ${s.host}")
            last?.kind == "warn" && s.on -> Triple(WARN, "On · ${s.label}, not confirmed", last.message)
            last?.kind == "error" -> Triple(ERR, if (s.on) "On · ${s.label}" else "Off", last.message)
            s.on -> Triple(accent, "On · ${s.label}", "${s.host} · choose “Check again” to test it")
            else -> Triple(dim, "Off", "Using your network's normal DNS.")
        }
        statusDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        if (statusTitle.text.toString() != title) {
            statusTitle.alpha = 0f
            statusTitle.animate().alpha(1f).setDuration(220).setInterpolator(Ui.EASE).start()
        }
        statusTitle.text = title
        statusTitle.setTextColor(when (color) { dim -> Color.WHITE; OK -> OK_TEXT; WARN -> WARN_TEXT; ERR -> ERR_TEXT; else -> color })
        statusDetail.text = detail
    }

    // ---- choices ----

    private fun buildRows(focusTag: String?) {
        rows.removeAllViews()
        val s = AdBlock.status(ctx)
        val current = currentTag()
        val group = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = Ui.R_LG.dp.toFloat()
                setColor(Color.argb(20, 255, 255, 255))
                setStroke(1, Color.argb(22, 255, 255, 255))
            }
            // Focused rows lift a little past the group's edge.
            clipChildren = false
            clipToPadding = false
        }
        fun add(tag: String, label: String, sub: String, onClick: () -> Unit) {
            if (group.childCount > 0) group.addView(View(ctx).apply { setBackgroundColor(Color.argb(16, 255, 255, 255)) },
                LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { marginStart = 50.dp })
            group.addView(choiceRow(tag, label, sub, tag == current, onClick), LinearLayout.LayoutParams(MATCH_PARENT, 58.dp))
        }
        add("off", "Off", "Use the network's normal DNS") { turnOff() }
        for (p in AdBlock.PROVIDERS) add(p.id, p.name, "${p.blurb}  ·  ${p.host}") { turnOn(p.host, p.id) }
        val custom = if (current == "custom") s.host.orEmpty() else AdBlock.customHost(ctx)
        add("custom", "Custom hostname…", custom.ifEmpty { "Any DNS-over-TLS server" }) { askCustom() }
        if (s.on) add("check", "Check again", "Test that ads are being blocked") { recheck() }
        rows.addView(group)
        // First open only: the choices settle in one after another.
        if (firstBuild) Ui.rise((0 until group.childCount).map { group.getChildAt(it) }, fromDp = 10, startDelay = 60L, duration = Ui.OVERLAY_IN)
        firstBuild = false
        focusTag?.let { t -> rows.post { rows.findViewWithTag<View>(t)?.requestFocus() } }
    }

    private fun turnOn(host: String, tag: String) {
        if (busy || !AdBlock.supported()) return
        busy = true
        showStatus("Starting…")
        AdBlock.enableAndVerify(ctx, host, { p -> if (!closed) showStatus(p) }) { r -> finished(r, tag) }
    }

    private fun turnOff() {
        if (busy || !AdBlock.supported()) return
        if (!AdBlock.status(ctx).on) return
        val ok = AdBlock.disable(ctx)
        AdBlock.clearResult(ctx)
        if (!ok) Toast.makeText(ctx, "Couldn't change system DNS", Toast.LENGTH_SHORT).show()
        showStatus()
        buildRows("off")
    }

    private fun recheck() {
        if (busy) return
        busy = true
        showStatus("Checking…")
        AdBlock.recheck(ctx, { p -> if (!closed) showStatus(p) }) { r -> finished(r, "check") }
    }

    private fun finished(r: AdBlock.Result, tag: String) {
        busy = false
        if (closed) {
            notify(if (r.kind == "ok") "Ad Blocking Is On" else r.message)
            return
        }
        showStatus()
        // After a revert the choice is back on Off; otherwise stay on the row that was picked.
        buildRows(if (AdBlock.status(ctx).on) tag else "off")
    }

    /** Hostname prompt, same pattern as the TMDB key entry. */
    private fun askCustom() {
        if (busy || !AdBlock.supported()) return
        val input = EditText(ctx).apply {
            setText(AdBlock.customHost(ctx))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "e.g. dns.example.com"
        }
        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Private DNS hostname")
            .setMessage("A DNS-over-TLS server name (not an IP address).")
            .setView(input)
            .setPositiveButton("Use") { _, _ ->
                val host = AdBlock.normalizeHost(input.text.toString())
                if (!AdBlock.isValidHost(host)) {
                    Toast.makeText(ctx, "That doesn't look like a hostname", Toast.LENGTH_SHORT).show()
                    rows.post { rows.findViewWithTag<View>("custom")?.requestFocus() }
                    return@setPositiveButton
                }
                AdBlock.setCustomHost(ctx, host)
                buildRows("custom")
                turnOn(host, "custom")
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { rows.post { if (rows.findFocus() == null) rows.findViewWithTag<View>("custom")?.requestFocus() } }
            .show()
    }

    private fun choiceRow(tag: String, label: String, sub: String, selected: Boolean, onClick: () -> Unit) = LinearLayout(ctx).apply {
        this.tag = tag
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(16.dp, 0, 16.dp, 0)
        isFocusable = true
        isClickable = true
        val onPill = Theme.contrastOn(select)
        fun states(focused: Int, normal: Int) = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), IntArray(0)), intArrayOf(focused, normal))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(ctx, select, Ui.R_MD))
            addState(IntArray(0), ColorDrawable(Color.TRANSPARENT))
        }
        // The chosen option: the drawn pick check; the others a quiet ring (never a text "✓").
        addView(View(ctx).apply {
            background = StatusGlyph(if (selected) BrandRow.Mark.PICK else BrandRow.Mark.OFF)
        }, LinearLayout.LayoutParams(20.dp, 20.dp).apply { marginEnd = 14.dp })
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isDuplicateParentStateEnabled = true
            addView(TextView(ctx).apply {
                text = label
                textSize = 17f
                typeface = if (selected) fontBold else font
                includeFontPadding = false
                setTextColor(states(onPill, Color.argb(235, 255, 255, 255)))
                isDuplicateParentStateEnabled = true
            })
            addView(TextView(ctx).apply {
                text = sub
                textSize = 12f
                typeface = font
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 3.dp, 0, 0)
                setTextColor(states(Ui.fade(onPill, 0.75f), Color.argb(130, 255, 255, 255)))
                isDuplicateParentStateEnabled = true
            })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        setOnClickListener { onClick() }
        // Left/Right don't change anything; keep focus inside the card.
        setOnKeyListener { _, k, _ -> k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT }
        setOnFocusChangeListener { v, has -> Ui.lift(v, has, Ui.LIFT_ROW) }
        cinemaRowFocus(this, Ui.R_MD) // Dream Cinema: pearl face under the rainbow border
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = t
        textSize = size
        setTextColor(color)
        typeface = if (bold) fontBold else font
    }

    private val Int.dp get() = (this * ctx.resources.displayMetrics.density).toInt()

    companion object {
        private const val WIDTH = 540 // content width; the host's modal wraps the card around it
        // STANDARDS §2 status colors.
        private val OK = Ui.GREEN
        private val WARN = Ui.AMBER
        private val ERR = Ui.RED
        private val OK_TEXT = Ui.GREEN_TEXT
        private val WARN_TEXT = Ui.AMBER_TEXT
        private val ERR_TEXT = Ui.RED_TEXT
    }
}
