package com.firehome.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

/**
 * The AirPods card, opened from the top bar's AirPods status: model, connection and last known
 * battery; "Send to iPhone" / "Bring to TV" (logic in [AirPodsLink]); the AirPods options; and
 * listening statistics. Colors come from [Theme].
 * The host shows [view] in its modal, calls [refresh] when the AirPods' state or battery changes,
 * and [handleBack] on BACK (false = close the card).
 */
class AirPodsPanel(
    private val ctx: Context,
    private val airPods: AirPods,
    private val accent: Int,
    private val select: Int,
    private val font: Typeface,
    private val fontBold: Typeface,
    /** Reports a result when the card was closed before the operation finished. */
    private val notify: (String) -> Unit,
) {
    val view: LinearLayout
    private val ink = Theme.get(ctx, Theme.TEXT)
    private val dim = Theme.get(ctx, Theme.TEXT_DIM)
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var busy = false

    private val title = label("", 24f, ink, bold = true)
    private val stateDot = View(ctx)
    private val stateLine = label("", 13f, dim)
    private val gauges = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val batteryAge = label("", 12f, dim)
    private val sendBtn: LinearLayout
    private val bringBtn: LinearLayout
    private val result = label("", 13f, ink)
    private val progress = View(ctx)
    private val listenRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val details = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private var info: AirPodsLink.Info? = null
    private lateinit var scroll: SmoothScrollView
    /** Where the scroll is headed (not where a running glide happens to be), so steps never stack up. */
    private var scrollTarget = 0
    private var glideUntil = 0L
    private var lastToggle: View? = null
    private val focusWatcher = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, now -> if (now != null) keepVisible(now) }

    private val onLinkChanged: () -> Unit = { if (!closed) { refresh(); readInfo() } }
    private val ticker = object : Runnable {
        override fun run() {
            if (closed) return
            fillStats()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        sendBtn = actionButton("send", "Send to iPhone", R.drawable.ic_phone) { sendToPhone() }
        bringBtn = actionButton("bring", "Bring to TV", R.drawable.ic_tv) { bringToTv() }
        view = object : LinearLayout(ctx) {
            // Never taller than the screen less a margin: the middle (scrolling) part gives way instead.
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val max = resources.displayMetrics.heightPixels - 2 * SCREEN_MARGIN.dp
                val size = MeasureSpec.getSize(heightSpec)
                val spec = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED || size > max)
                    MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST) else heightSpec
                super.onMeasure(widthSpec, spec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(ctx, Ui.surface(ctx), Ui.R_XL, 1f, Ui.alpha(ink, 0.08f))
            setPadding(30.dp, 22.dp, 30.dp, 16.dp)
            // Header: icon, model, connection state.
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_headphones)
                    setColorFilter(ink)
                }, LinearLayout.LayoutParams(30.dp, 30.dp).apply { marginEnd = 14.dp })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title.apply {
                        includeFontPadding = false
                        typeface = Ui.semibold(font)
                        letterSpacing = Ui.TRACK_TITLE
                    })
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, 5.dp, 0, 0)
                        addView(stateDot, LinearLayout.LayoutParams(8.dp, 8.dp).apply { marginEnd = 8.dp })
                        addView(stateLine)
                    })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(WIDTH.dp, WRAP_CONTENT))
            addView(View(ctx).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Ui.fade(select, 0.9f), Ui.fade(accent, 0.35f), Color.TRANSPARENT))
            }, LinearLayout.LayoutParams(WIDTH.dp, maxOf(1, Ui.dpf(ctx, 1.5f).toInt())).apply { topMargin = 14.dp; bottomMargin = 14.dp })
            // Two columns: battery, moving the AirPods and options on the left; statistics on the right.
            scroll = SmoothScrollView(ctx).apply {
                // The card scrolls itself (keepVisible), so focus changes never trigger a second, stock scroll.
                manual = true
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isFocusable = false
                clipChildren = false
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                    addView(leftColumn(), LinearLayout.LayoutParams(LEFT.dp, WRAP_CONTENT))
                    addView(rightColumn(), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = 26.dp })
                }, FrameLayout.LayoutParams(WIDTH.dp, WRAP_CONTENT))
            }
            addView(scroll, LinearLayout.LayoutParams(WIDTH.dp, WRAP_CONTENT, 1f))
            // Room for the focus lift: the card clips the scroller at its edges, so without it a lifted
            // button touching the edge loses its rounded corners on that side.
            Ui.liftRoom(scroll, LIFT_ROOM, LIFT_ROOM_V)
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.viewTreeObserver.addOnGlobalFocusChangeListener(focusWatcher)
                    AirPodsLink.addListener(onLinkChanged)
                    handler.removeCallbacks(ticker)
                    handler.postDelayed(ticker, TICK_MS)
                }

                override fun onViewDetachedFromWindow(v: View) {
                    v.viewTreeObserver.removeOnGlobalFocusChangeListener(focusWatcher)
                    closed = true
                    AirPodsLink.removeListener(onLinkChanged)
                    handler.removeCallbacks(ticker)
                }
            })
        }
        refresh()
        readInfo()
        // Asks for a fresh battery reading (only happens when not streaming; never scans during A2DP).
        airPods.refresh()
        Ui.rise(listOf(gauges, sendBtn, bringBtn), fromDp = 10, startDelay = 80L, duration = Ui.OVERLAY_IN)
    }

    /** Focus on open: the action that makes sense right now. */
    val focusView: View get() = if (AirPodsLink.connected) sendBtn else bringBtn

    /** Nothing nested: BACK closes the card (an operation still running reports through [notify]). */
    fun handleBack(): Boolean {
        closed = true
        AirPodsLink.removeListener(onLinkChanged)
        handler.removeCallbacks(ticker)
        return false
    }

    /**
     * Scrolls so [v] is fully visible with some room around it. The top row (Send / Bring) always
     * returns to exactly 0, so the battery above it shows again; the last option goes to the very
     * bottom, so the statistics below the fold show too.
     */
    private fun keepVisible(v: View) {
        if (closed || !::scroll.isInitialized) return
        val content = scroll.getChildAt(0) ?: return
        if (!isInside(v, content)) return
        val max = maxOf(0, content.height - (scroll.height - scroll.paddingTop - scroll.paddingBottom))
        val target = when {
            v === sendBtn || v === bringBtn -> 0
            v === lastToggle -> max
            else -> {
                val r = android.graphics.Rect()
                v.getDrawingRect(r)
                (content as android.view.ViewGroup).offsetDescendantRectToMyCoords(v, r)
                val pad = 20.dp
                // Mid-glide, measure from where it's headed; otherwise from where it is.
                val base = if (android.os.SystemClock.uptimeMillis() < glideUntil) scrollTarget else scroll.scrollY
                when {
                    r.top - pad < base -> r.top - pad
                    r.bottom + pad > base + scroll.height -> r.bottom + pad - scroll.height
                    else -> base
                }
            }
        }.coerceIn(0, max)
        scrollTarget = target
        if (target != scroll.scrollY) {
            glideUntil = android.os.SystemClock.uptimeMillis() + GLIDE_MS
            scroll.glideTo(target, GLIDE_MS)
        }
    }

    private fun isInside(v: View, parent: View): Boolean {
        var p: android.view.ViewParent? = v.parent
        while (p != null) { if (p === parent) return true; p = p.parent }
        return false
    }

    // ---- columns ----

    private fun leftColumn() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        // Battery: last known reading (AirPods only share it while the case is open).
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            background = group()
            addView(gauges, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(batteryAge.apply { setPadding(0, 8.dp, 0, 0); setLineSpacing(0f, 1.1f) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(section("Move audio"))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            addView(sendBtn, LinearLayout.LayoutParams(0, 50.dp, 1f).apply { marginEnd = 10.dp })
            addView(bringBtn, LinearLayout.LayoutParams(0, 50.dp, 1f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // Progress: one bar that fills while an operation runs and always finishes (no idle animation).
        addView(FrameLayout(ctx).apply {
            addView(progress.apply {
                background = Ui.rounded(ctx, select, Ui.R_PILL)
                pivotX = 0f
                scaleX = 0f
                alpha = 0f
            }, FrameLayout.LayoutParams(MATCH_PARENT, 3.dp))
        }, LinearLayout.LayoutParams(MATCH_PARENT, 3.dp).apply { topMargin = 8.dp })
        addView(result.apply { setPadding(2.dp, 6.dp, 0, 0) })
        addView(label("Send to iPhone makes the TV let go of them. Your iPhone usually takes them on its own; " +
            "if not, tap AirPods in the iPhone's Control Center.", 12f, dim).apply {
            setLineSpacing(0f, 1.12f)
            setPadding(2.dp, 4.dp, 0, 0)
        })
        addView(section("Options"))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = group()
            clipChildren = false
            clipToPadding = false
            val opts = listOf(
                toggleRow("sleep", "Sleep timer when put away", "10 minutes once they disconnect",
                    { Theme.get(ctx, Theme.AIRPODS_SLEEP) == 1 }) { Theme.set(ctx, Theme.AIRPODS_SLEEP, if (it) 1 else 0) },
                toggleRow("pause", "Pause playback when they disconnect", "Never resumes on its own",
                    { AirPodsLink.pauseOnDisconnect(ctx) }) { AirPodsLink.setPauseOnDisconnect(ctx, it) },
                toggleRow("auto", "Connect when Dream OS starts", "So the intro plays in your AirPods",
                    { AirPodsLink.autoConnect(ctx) }) { AirPodsLink.setAutoConnect(ctx, it) },
            )
            // Down from the last option has nowhere to go: don't let the ScrollView page past it on its own.
            lastToggle = opts.last().apply {
                setOnKeyListener { _, k, _ -> k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_DOWN }
            }
            for ((i, r) in opts.withIndex()) {
                if (i > 0) addView(View(ctx).apply { setBackgroundColor(Ui.alpha(ink, 0.06f)) },
                    LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { marginStart = 16.dp; marginEnd = 16.dp })
                addView(r, LinearLayout.LayoutParams(MATCH_PARENT, 54.dp))
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun rightColumn() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(section("Listening time").apply { setPadding(2.dp, 0, 0, 8.dp) })
        addView(listenRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(section("Details"))
        addView(details.apply {
            background = group()
            setPadding(16.dp, 10.dp, 16.dp, 10.dp)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    // ---- filling ----

    /** Redraws everything from the current state (cheap; no animation unless a value changed). */
    fun refresh() {
        if (closed) return
        val connected = AirPodsLink.connected
        val device = AirPodsLink.paired()
        val s = airPods.last
        val deviceName = try { device?.name } catch (e: Exception) { null }
        title.text = s?.model?.takeIf { it != "AirPods" } ?: airPods.rememberedModel ?: deviceName ?: "AirPods"
        val sent = AirPodsLink.sentToPhoneAt.takeIf { it > 0 && !connected && System.currentTimeMillis() - it < 30 * 60_000L }
        val (color, line) = when {
            device == null -> dim to "Not paired with this TV: pair them in Bluetooth settings"
            connected -> GREEN to (if (info?.streaming == true) "Connected to this TV  ·  Playing" else "Connected to this TV")
            sent != null -> dim to "Sent to iPhone ${ago(System.currentTimeMillis() - sent)}"
            else -> dim to "Not connected to this TV"
        }
        stateDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        if (stateLine.text.toString() != line && stateLine.text.isNotEmpty()) {
            stateLine.alpha = 0f
            stateLine.animate().alpha(1f).setDuration(220).setInterpolator(Ui.EASE).start()
        }
        stateLine.text = line
        stateLine.setTextColor(if (color == GREEN) GREEN else dim)
        fillBattery(s)
        if (!busy) {
            setButton(sendBtn, "Send to iPhone", primary = connected)
            setButton(bringBtn, if (connected) "On the TV" else "Bring to TV", primary = !connected && device != null)
        }
        fillStats()
    }

    private fun fillBattery(s: AirPods.Status?) {
        gauges.removeAllViews()
        if (s == null) {
            gauges.addView(label("No battery reading yet", 16f, ink))
            batteryAge.text = "Open the case near the TV to refresh"
            return
        }
        val parts = if (s.single) listOf(Triple("Battery", s.left ?: s.right, s.leftCharging || s.rightCharging))
        else listOf(Triple("Left", s.left, s.leftCharging), Triple("Right", s.right, s.rightCharging), Triple("Case", s.case, s.caseCharging))
        for ((i, p) in parts.withIndex()) {
            gauges.addView(gauge(p.first, p.second, p.third, !s.recent), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = 14.dp
            })
        }
        val at = DreamTime.formatTime(ctx, s.seenAt)
        val day = when (daysAgo(s.seenAt)) { 0 -> ""; 1 -> "yesterday "; else -> "${shortDate(s.seenAt)}, " }
        batteryAge.text = "As of $day$at (${ago(s.ageMs)})  ·  open the case near the TV to refresh" +
            if (!s.exact) "  ·  10% steps" else ""
    }

    private fun gauge(name: String, value: Int?, charging: Boolean, stale: Boolean): View {
        val low = value != null && value <= 20 && !charging
        val color = when {
            value == null -> Ui.alpha(ink, 0.3f)
            charging -> GREEN
            low -> RED
            else -> ink
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            alpha = if (stale) 0.75f else 1f
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                addView(label(value?.let { "$it%" } ?: "–", 22f, if (low) RED else ink, bold = true).apply { includeFontPadding = false })
                addView(label(if (charging) "  ⚡ $name" else "  $name", 12f, dim).apply { includeFontPadding = false; setPadding(0, 0, 0, 2.dp) })
            })
            addView(FrameLayout(ctx).apply {
                background = Ui.rounded(ctx, Ui.alpha(ink, 0.12f), Ui.R_PILL)
                addView(View(ctx).apply {
                    background = Ui.rounded(ctx, color, Ui.R_PILL)
                    pivotX = 0f
                    scaleX = (value ?: 0) / 100f
                }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }, LinearLayout.LayoutParams(MATCH_PARENT, 5.dp).apply { topMargin = 7.dp })
        }
    }

    private fun fillStats() {
        if (closed) return
        val st = AirPodsLink.stats(ctx)
        listenRow.removeAllViews()
        for ((i, p) in listOf("Today" to st.todayMs, "Last 7 days" to st.weekMs, "All time" to st.allMs).withIndex()) {
            listenRow.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = group()
                setPadding(12.dp, 10.dp, 10.dp, 10.dp)
                addView(label(duration(p.second), 19f, ink, bold = true).apply { includeFontPadding = false; maxLines = 1 })
                addView(label(p.first, 11.5f, dim).apply { setPadding(0, 4.dp, 0, 0); maxLines = 1 })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = 8.dp })
        }
        val i = info
        val device = i?.device ?: AirPodsLink.paired()
        val rows = ArrayList<Pair<String, String>>()
        rows += "This session" to (st.sessionMs?.let { duration(it) } ?: "Not connected")
        rows += "Connections" to (if (st.since > 0) "${st.connections} since ${shortDate(st.since)}" else "${st.connections}")
        rows += "Last connected" to moment(st.lastConnected)
        rows += "Last disconnected" to moment(st.lastDisconnected)
        if (AirPodsLink.connected) {
            i?.codec?.let { rows += "Audio codec" to it }
            rows += "Audio" to listOfNotNull(if (i?.streaming == true) "Playing" else "Idle", if (i?.callAudio == true) "call audio linked" else null).joinToString(" · ")
        }
        device?.let { d ->
            (try { d.name } catch (e: Exception) { null })?.let { rows += "Name" to it }
            (try { d.address } catch (e: Exception) { null })?.let { rows += "Address" to it }
        }
        details.removeAllViews()
        for ((k, v) in rows) details.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 3.dp, 0, 3.dp)
            addView(label(k, 12.5f, dim), LinearLayout.LayoutParams(118.dp, WRAP_CONTENT))
            addView(label(v, 12.5f, ink).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
    }

    private fun readInfo() {
        AirPodsLink.info(ctx) { i ->
            if (closed) return@info
            info = i
            refresh()
        }
    }

    // ---- actions ----

    private fun sendToPhone() {
        if (busy) return
        if (AirPodsLink.paired() == null) return showResult(AirPodsLink.Result(false, "AirPods aren't paired with this TV"))
        start(sendBtn, "Sending…", "Letting go of your AirPods…", 8_000L)
        AirPodsLink.sendToPhone(ctx) { r -> finish(r, sendBtn) }
    }

    private fun bringToTv() {
        if (busy) return
        if (AirPodsLink.connected) return showResult(AirPodsLink.Result(true, "Already connected to the TV"))
        if (AirPodsLink.paired() == null) return showResult(AirPodsLink.Result(false, "Pair them in Bluetooth settings first"))
        start(bringBtn, "Connecting…", "Connecting… keep them in your ears or the case open", 15_000L)
        AirPodsLink.bringToTv(ctx) { r -> finish(r, bringBtn) }
    }

    private fun start(btn: LinearLayout, busyLabel: String, message: String, expectMs: Long) {
        busy = true
        setButton(btn, busyLabel, primary = true)
        result.setTextColor(ink)
        result.text = message
        progress.animate().cancel()
        progress.alpha = 1f
        progress.scaleX = 0f
        // Fills to 90% over the expected time; the result completes or fades it.
        progress.animate().scaleX(0.9f).setDuration(expectMs).setInterpolator(Ui.SETTLE).start()
    }

    private fun finish(r: AirPodsLink.Result, btn: LinearLayout) {
        busy = false
        if (closed) return notify(r.message)
        progress.animate().cancel()
        progress.animate().scaleX(if (r.ok) 1f else progress.scaleX).alpha(0f).setDuration(420).setInterpolator(Ui.EASE).start()
        showResult(r)
        refresh()
        readInfo()
        if (btn.isFocused) btn.post { focusView.takeIf { it.isShown }?.requestFocus() }
    }

    private fun showResult(r: AirPodsLink.Result) {
        result.text = r.message
        result.setTextColor(if (r.ok) GREEN else WARN)
        result.alpha = 0f
        result.animate().alpha(1f).setDuration(220).setInterpolator(Ui.EASE).start()
    }

    // ---- building blocks ----

    private fun actionButton(tag: String, label: String, icon: Int, onClick: () -> Unit) = LinearLayout(ctx).apply {
        this.tag = tag
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        setPadding(14.dp, 0, 14.dp, 0)
        addView(ImageView(ctx).apply { setImageResource(icon); isDuplicateParentStateEnabled = true },
            LinearLayout.LayoutParams(18.dp, 18.dp).apply { marginEnd = 10.dp })
        addView(label(label, 15f, ink, bold = true).apply { isDuplicateParentStateEnabled = true; maxLines = 1 })
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, has ->
            Ui.lift(v, has, Ui.LIFT_PILL)
        }
        // Up from the buttons has nowhere to go; keep focus in the card.
        setOnKeyListener { _, k, e -> k == KeyEvent.KEYCODE_DPAD_UP && e.action == KeyEvent.ACTION_DOWN }
    }

    /** A button on glass; focus shows the pearl face under the rainbow border. */
    private fun setButton(b: LinearLayout, label: String, primary: Boolean) {
        val onSelect = Theme.contrastOn(select)
        // Every button rests on glass (pearl only while focused): its resting text stays light.
        val states = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), IntArray(0)), intArrayOf(onSelect, ink))
        // The row-focus face owns the background (pearl focused, glass at rest, rainbow border).
        if (b.background !is android.graphics.drawable.LayerDrawable) cinemaRowFocus(b, 20, Ui.alpha(ink, if (primary) 0.16f else 0.08f), Ui.alpha(ink, 0.10f))
        (b.getChildAt(0) as? ImageView)?.imageTintList = states
        (b.getChildAt(1) as? TextView)?.apply { text = label; setTextColor(states) }
    }

    private fun toggleRow(tag: String, label: String, sub: String, get: () -> Boolean, set: (Boolean) -> Unit) = LinearLayout(ctx).apply {
        this.tag = tag
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(16.dp, 0, 14.dp, 0)
        isFocusable = true
        isClickable = true
        val onPill = Theme.contrastOn(select)
        fun states(f: Int, n: Int) = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), IntArray(0)), intArrayOf(f, n))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(ctx, select, Ui.R_MD))
            addState(IntArray(0), ColorDrawable(Color.TRANSPARENT))
        }
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isDuplicateParentStateEnabled = true
            addView(label(label, 15f, ink).apply { includeFontPadding = false; isDuplicateParentStateEnabled = true; setTextColor(states(onPill, ink)) })
            addView(label(sub, 11.5f, dim).apply {
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 4.dp, 0, 0)
                isDuplicateParentStateEnabled = true
                setTextColor(states(Ui.fade(onPill, 0.75f), dim))
            })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val sw = SwitchView(ctx, get())
        addView(sw, LinearLayout.LayoutParams(42.dp, 24.dp).apply { marginStart = 12.dp })
        setOnClickListener {
            val on = !get()
            sw.flip()
            set(on)
        }
        setOnFocusChangeListener { v, has ->
            Ui.lift(v, has, Ui.LIFT_ROW)
        }
        cinemaRowFocus(this, Ui.R_MD) // Dream Cinema: pearl face under the rainbow border
        // Left/Right don't change anything; keep focus inside the card.
        setOnKeyListener { _, k, _ -> k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT }
    }

    private fun group(): Drawable = Ui.rounded(ctx, Ui.alpha(ink, 0.06f), Ui.R_LG, 1f, Ui.alpha(ink, 0.07f))

    private fun section(t: String) = label(t.uppercase(), 11f, dim, bold = true).apply {
        letterSpacing = Ui.TRACK_CAPS
        setPadding(2.dp, 16.dp, 0, 8.dp)
    }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = t
        textSize = size
        setTextColor(color)
        typeface = if (bold) fontBold else font
    }

    // ---- formatting ----

    /** "2h 05m", "14m", "under a minute". */
    private fun duration(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 1 -> if (ms > 0) "<1m" else "0m"
            min < 60 -> "${min}m"
            else -> "${min / 60}h %02dm".format(min % 60)
        }
    }

    private fun ago(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 48 * 60 -> "${min / 60}h ago"
            else -> "${min / (24 * 60)} days ago"
        }
    }

    /** "Today 3:41 PM", "Yesterday 9:02 PM", "Oct 21, 9:02 PM", or "Never". */
    private fun moment(t: Long): String {
        if (t <= 0L) return "Never"
        val time = DreamTime.formatTime(ctx, t)
        return when (daysAgo(t)) {
            0 -> "Today $time"
            1 -> "Yesterday $time"
            else -> "${shortDate(t)}, $time"
        }
    }

    private fun shortDate(t: Long) = DreamTime.fmt(ctx, "MMM d").format(java.util.Date(t))

    private fun daysAgo(t: Long): Int = -DreamTime.dayDelta(ctx, t)

    private val Int.dp get() = (this * ctx.resources.displayMetrics.density).toInt()

    companion object {
        private const val WIDTH = 820 // content width; the host's modal wraps the card around it
        private const val LEFT = 440
        private const val LIFT_ROOM = 18 // dp each side for focused buttons to grow into (fits the card's 30dp padding)
        private const val LIFT_ROOM_V = 4 // dp above and below (the rows only grow ~1dp; the gaps around the scroller take it)
        private const val TICK_MS = 30_000L
        private const val GLIDE_MS = 360L
        private const val SCREEN_MARGIN = 24 // dp kept free above and below the card
        // Status colors (same meaning everywhere in Dream OS: connected / low / warning).
        private val GREEN = Color.parseColor("#4ADE80")
        private val RED = Color.parseColor("#F87171")
        private val WARN = Color.parseColor("#FBBF24")
    }
}

/**
 * On/off switch (the AirPods card): a capsule track in the theme's highlight color with a knob that
 * slides across (one short animation per flip; nothing allocated while drawing).
 */
class SwitchView(ctx: Context, private var on: Boolean) : View(ctx) {
    private val select = Theme.get(ctx, Theme.SELECT)
    private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val knob = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val shadow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 0, 0, 0) }
    private val rect = android.graphics.RectF()
    private var pos = if (on) 1f else 0f

    /** Slides to the other state (the owner applies the setting). */
    fun flip() {
        on = !on
        android.animation.ValueAnimator.ofFloat(pos, if (on) 1f else 0f).apply {
            duration = 170
            interpolator = Ui.SPRING
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        val p = pos.coerceIn(0f, 1f)
        track.color = Ui.mix(Color.parseColor("#3A3F55"), select, p)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)
        val inset = h * 0.12f
        val kr = r - inset
        val cx = r + (w - 2 * r) * pos
        canvas.drawCircle(cx, r + inset * 0.35f, kr, shadow)
        canvas.drawCircle(cx, r, kr, knob)
    }
}
