package com.firehome.launcher

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.Log
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Settings › Time & Region: the time zone and clock format every time in Dream OS follows ([DreamTime]).
 *
 *  - [settingsItems]: the category's rows for the Cinema / Premium Settings page.
 *  - [open]: the Time Zone page over whatever shows (Settings).
 *  - [picker] / [attach]: the same zone list for any container, e.g. the setup's "Your Time Zone" step.
 *
 * The list: Automatic (the Fire TV's own zone) on top, then a short, friendly list (United States first,
 * then major world cities by region), each with the time there right now. The chosen one has a check.
 */
object TimeSettings {
    private const val TAG = "FireHome"
    private var openPage: View? = null

    fun isOpen() = openPage != null

    // ---- Settings rows ----

    /** "Time & Region" rows. [refresh] re-reads the Settings page. */
    fun settingsItems(a: Activity, refresh: () -> Unit): List<PremiumSettings.Item> = listOf(
        PremiumSettings.Item("Time Zone", DreamTime.summary(a), SettingsGlyph.CLOCK, null,
            "New Episode Times, the Clock and Reminders") { open(a) { refresh() } },
        PremiumSettings.Item("Clock Format", clockValue(a), SettingsGlyph.CLOCK, null,
            "Now ${DreamTime.formatTime(a, System.currentTimeMillis())}") { cycleClock(a); refresh() },
    )

    /** "Automatic" / "12-Hour" / "24-Hour" (the setting itself, not what Automatic resolves to). */
    fun clockValue(ctx: Context): String = when (DreamTime.override24h(ctx)) { null -> "Automatic"; true -> "24-Hour"; false -> "12-Hour" }

    /** Automatic → 12-Hour → 24-Hour → Automatic. */
    fun cycleClock(ctx: Context) {
        DreamTime.set24h(ctx, when (DreamTime.override24h(ctx)) { null -> false; false -> true; true -> null })
    }

    // ---- reusable picker ----

    /** The zone list in a box of [heightDp] (scrolls inside); [focus] is the chosen row. Call [release] when it goes away. */
    class Picker internal constructor(val view: View, private val screen: SetupScreen) {
        val focus: View get() = screen.focus() ?: view
        fun release() { screen.alive = false; screen.onHide() }
    }

    /**
     * The zone list for any container (the setup's "Your Time Zone" step): picking a row saves it at once
     * ([DreamTime.setZone]) and calls [onDone]. Fixed [heightDp] so it fits a WRAP_CONTENT column.
     */
    fun picker(ctx: Context, heightDp: Int = 380, onDone: () -> Unit): Picker {
        val ui = SetupUi(ctx, PremiumKit(ctx))
        val s = zoneScreen(ui, null, onDone)
        val box = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
        box.addView(s.root, FrameLayout.LayoutParams(MATCH_PARENT, Ui.dp(ctx, heightDp)))
        box.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        s.alive = true
        s.shown()
        box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { if (!s.alive) { s.alive = true; s.shown() } }
            override fun onViewDetachedFromWindow(v: View) { s.alive = false; s.onHide() }
        })
        return Picker(box, s)
    }

    /** [picker] added to [container]; returns it (focus it with `picker.focus.requestFocus()`). */
    fun attach(container: ViewGroup, heightDp: Int = 380, onDone: () -> Unit): Picker =
        picker(container.context, heightDp, onDone).also { container.addView(it.view) }

    // ---- the zone screen ----

    /** Rows: Automatic, then each region's zones with the time there now. Ticks each minute while showing. */
    internal fun zoneScreen(ui: SetupUi, kicker: String?, onDone: () -> Unit): SetupScreen {
        val ctx = ui.ctx
        val k = ui.k
        val s = SetupScreen(ui, null, kicker)
        val focused = intArrayOf(android.R.attr.state_focused)
        val ink = PearlFocus.INK
        fun keyOf(id: String?) = if (id == null) "auto" else "z:$id"
        fun selectedKey() = keyOf(if (DreamTime.isAutomatic(ctx)) null else DreamTime.zone(ctx).id)

        /** The chosen row's neutral check (no status color: a pick, not a health mark); the rest keep its space. */
        fun check(m: TextView, on: Boolean) {
            // A drawn check (StatusGlyph's neutral pick), never a text "✓".
            m.text = ""
            m.background = if (on) StatusGlyph(BrandRow.Mark.PICK) else null
            m.visibility = if (on) View.VISIBLE else View.INVISIBLE
        }

        fun pick(id: String?) {
            DreamTime.setZone(ctx, id)
            onDone()
        }

        lateinit var paint: () -> Unit
        paint = {
            val now = System.currentTimeMillis()
            val sel = selectedKey()
            val dev = DreamTime.detected()
            val auto = s.row("auto", "Automatic", "Follows Your Fire TV · ${DreamTime.labelFor(dev.id)}",
                DreamTime.formatTimeIn(ctx, now, dev), top = 0) { pick(null); paint() }
            check(auto.mark, sel == "auto")
            // A zone set some other way (not in the list) still shows, right under Automatic.
            val custom = if (!DreamTime.isAutomatic(ctx)) DreamTime.zone(ctx).id.takeIf { id -> DreamTime.choices().none { it.id == id } } else null
            if (custom != null) {
                val tz = java.util.TimeZone.getTimeZone(custom)
                val r = s.row("z:$custom", DreamTime.labelFor(custom), DreamTime.offsetText(tz, now), DreamTime.formatTimeIn(ctx, now, tz), top = 6) {
                    pick(custom); paint()
                }
                check(r.mark, true)
            }
            var region = ""
            for (c in DreamTime.choices()) {
                val rg = DreamTime.region(c.id)
                if (rg != region) { region = rg; s.view("k:$rg", 18) { ui.kicker(rg) } }
                val tz = java.util.TimeZone.getTimeZone(c.id)
                val r = s.row(keyOf(c.id), c.label, DreamTime.offsetText(tz, now), DreamTime.formatTimeIn(ctx, now, tz), top = 6) {
                    pick(c.id); paint()
                }
                check(r.mark, sel == keyOf(c.id))
            }
        }

        // The times move on with the clock (on the minute) while the list shows.
        val tick = object : Runnable {
            override fun run() {
                if (!s.alive) return
                paint()
                ui.main.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 300L)
            }
        }
        s.onShow = {
            paint()
            ui.main.removeCallbacks(tick)
            ui.main.postDelayed(tick, 60_000L - System.currentTimeMillis() % 60_000L + 300L)
        }
        s.onResume = { paint() }
        s.onHide = { ui.main.removeCallbacks(tick) }
        s.focus = { s.rowFor(selectedKey())?.view ?: s.rowFor("auto")?.view }
        return s
    }

    // ---- the full page ----

    /**
     * Settings › Time & Region › Time Zone: the title and a live "now" line on the left, the list on the
     * right. Picking a zone saves it and closes the page a moment later; Back closes it as it is.
     */
    fun open(a: Activity, onClose: (() -> Unit)? = null) {
        if (openPage != null) return
        val k = PremiumKit(a)
        val ui = SetupUi(a, k)
        val panel = SetupPanel(ui)
        val back = a.currentFocus
        val dp = { v: Int -> Ui.dp(a, v) }
        lateinit var root: Root
        var nowLine: TextView? = null
        val onChange: () -> Unit = { nowLine?.text = nowText(a) }
        fun close() {
            val r = root
            if (openPage !== r) return
            openPage = null
            DreamTime.removeListener(onChange)
            panel.top?.let { it.alive = false; it.onHide() }
            panel.release()
            r.animate().alpha(0f).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT).withEndAction { (r.parent as? ViewGroup)?.removeView(r) }.start()
            if (back != null && back.isAttachedToWindow) back.requestFocus()
            onClose?.invoke()
        }
        root = Root(a) { if (!panel.pop()) close() }
        root.isClickable = true
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Ui.alpha(k.bgTop, 0.985f), Ui.alpha(Ui.mix(k.bgTop, k.bgBottom, 0.7f), 0.99f)))
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(PremiumKit.EDGE), dp(48), dp(56), dp(40))
            clipChildren = false
            clipToPadding = false
        }
        val left = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        left.addView(k.kicker("Settings"))
        left.addView(k.label("Time Zone", 30f, k.text, k.semibold, Ui.TRACK_TITLE, lines = 2),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        left.addView(k.label("New episode times, the clock, the screensaver and reminders all follow the time zone you choose.",
            16f, k.dim, k.font, lines = 0).apply { setLineSpacing(dp(4).toFloat(), 1f) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        nowLine = k.label(nowText(a), 18f, k.text, k.medium, lines = 2)
        left.addView(nowLine, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(24) })
        row.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.36f))
        row.addView(panel, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.64f).apply { marginStart = dp(52) })
        root.addView(row, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.TOP))
        Ui.unclip(row) // focused pills and rows lift past their rows: only the screen edge clips
        (a.window.decorView as FrameLayout).addView(root, MATCH_PARENT, MATCH_PARENT)
        openPage = root
        DreamTime.addListener(onChange)
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        panel.push(zoneScreen(ui, "Choose Your Time Zone") {
            // The check moves first, then the page steps away.
            ui.main.postDelayed({ close() }, 320L)
        }, animate = false)
    }

    /** "It's 6:42 PM · Eastern Time" (plus the GMT offset, quietly). */
    private fun nowText(ctx: Context): String {
        val z = DreamTime.zone(ctx)
        return "It's ${DreamTime.formatTime(ctx, System.currentTimeMillis())}  ·  ${DreamTime.labelFor(z.id)}"
    }

    /** Full-screen root: keeps focus inside, and takes Back. */
    private class Root(ctx: Context, private val onBack: () -> Unit) : FrameLayout(ctx) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun focusSearch(focused: View?, direction: Int): View? =
            FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused
    }

    // ---- dev hook ----

    /** PingReceiver: `--es time_zone America/Los_Angeles` (or "auto"), `--es clock 12|24|auto`. */
    fun devSet(ctx: Context, zone: String?, clock: String?) {
        if (zone != null) {
            if (zone.equals("auto", true) || zone.isBlank()) DreamTime.setZone(ctx, null)
            else if (java.util.TimeZone.getTimeZone(zone).id != zone) Log.w(TAG, "Time: unknown zone \"$zone\"")
            else DreamTime.setZone(ctx, zone)
        }
        when (clock?.lowercase()) {
            null -> {}
            "12" -> DreamTime.set24h(ctx, false)
            "24" -> DreamTime.set24h(ctx, true)
            else -> DreamTime.set24h(ctx, null)
        }
        val now = System.currentTimeMillis()
        Log.w(TAG, "Time: now ${DreamTime.formatTime(ctx, now)} in ${DreamTime.zone(ctx).id} (${DreamTime.summary(ctx)}, ${DreamTime.clockSummary(ctx)})")
    }
}
