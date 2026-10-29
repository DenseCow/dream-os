package com.firehome.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Dream Cinema's home screen.
 *
 *  - The top bar (Disney+, Apple TV): the Dream OS logo on the left, icon-and-label tabs in the middle
 *    (Home, Search, Movies, TV Shows, My List, Apps), the time, Settings and the profile on the right.
 *    It floats over the hero and slides away once you browse into the rows; Up at the top brings it back.
 *    A tab switches its page once focus rests on it (OK switches at once and goes into the page).
 *  - Pages ([CPage]) fill the screen edge to edge: Home and the browse pages are a full-bleed hero
 *    carousel followed by rows, Movies and TV Shows add Hulu's category chips, My List and Apps are
 *    calm grids, Search is the keyboard on the left with results on the right.
 *  - Focus glows in a travelling rainbow ([CinemaStyle.focusGlow]).
 *  - Back is always one obvious step: from a row to the top of the page, from the page to the bar, from
 *    the bar to Home.
 *
 * Pages are built when first visited and kept (only the visible one is attached).
 */
class CinemaShell(private val act: Activity, private val host: HomeShell.Host) : HomeShell {
    val kit = CinemaKit(act)
    /** The Dream keyboard takes the shared [PremiumKit]. */
    val premiumKit by lazy { PremiumKit(act) }
    private val handler = Handler(Looper.getMainLooper())
    private val Int.dp get() = (this * act.resources.displayMetrics.density).toInt()

    override val content: FrameLayout = object : FrameLayout(act) {
        // Left / Right never leave a page for the bar; Up into the bar lands on the current page's tab
        // (a neighbouring tab would swap the page a moment later).
        override fun focusSearch(focused: View?, direction: Int): View? {
            val r = super.focusSearch(focused, direction)
            if (r != null && inBar(r)) {
                if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) return focused
                if (direction == View.FOCUS_UP) return barEntry() ?: r
            }
            return r
        }
    }.apply { clipChildren = false }
    private val bar: FrameLayout
    private val tabViews = LinkedHashMap<HomeShell.Tab, View>()
    private val tabStyles = HashMap<HomeShell.Tab, (Boolean) -> Unit>()
    private var clock: TextView? = null
    /** The page's background melting the content away under the top edge once the bar has gone. */
    private val topFade = View(act).apply { alpha = 0f; isFocusable = false }
    override val root: FrameLayout

    /** Built pages by key: a [HomeShell.Tab] name. */
    private val pages = HashMap<String, CPage>()
    /** The installed apps as the pages last saw them ([appsChanged]); declared before init, which sets it. */
    private var appsSig: String? = null
    private var key = HOME
    override val currentTab: HomeShell.Tab get() = if (key == "SEASON") barTab() else HomeShell.Tab.valueOf(key)
    private var page: CPage? = null
    private var barShown = true
    private var pendingTab: Runnable? = null
    /** The service the Service page shows (the last brand tile opened), and whether it came from Home or Browse. */
    private var serviceSpec: ServiceSpec? = null
    private var serviceFromHome = true
    /** The genre the Genre page shows (the last genre card opened in Browse). */
    private var genreSpec: GenreSpec? = null
    /** The genre page was opened from Search (a genre search's Browse tile): Back returns there. */
    private var genreFromSearch = false
    /** The seasonal hub the Season page shows, the page that opened it and the card it was opened from (Back returns there). */
    private var seasonHub: SeasonalHub? = null
    private var seasonFrom = HOME
    private var seasonOpener: java.lang.ref.WeakReference<View>? = null
    // Declared before init: buildBar() fills them.
    private var airPodsChip: TextView? = null
    /** Headphones line icon, tinted by the state */
    private val airPodsDot = LineIcon(LineIcon.HEADPHONES).apply { setBounds(0, 0, 17.dp, 17.dp) }
    private var airPodsOn = false
    private var airPodsName = ""
    /** The bell's unread count (DreamNotices), kept current by [noticesChanged]. */
    private val noticeBadge = DreamNoticesUi.badge(act)
    /** The gear's dot: a new Dream OS version waits (DreamUpdate; its notice also refreshes this). */
    private val updateDot = View(act).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Rainbow.COLORS[0], Rainbow.COLORS[1], Rainbow.COLORS[2])).apply {
            shape = GradientDrawable.OVAL
            setStroke((1.5f * act.resources.displayMetrics.density).toInt().coerceAtLeast(1), 0xFF0C0C0E.toInt())
        }
        visibility = View.GONE
    }
    private val noticesChanged: () -> Unit = {
        DreamNoticesUi.update(noticeBadge, DreamNotices.unreadCount(act))
        updateDot.visibility = if (DreamUpdate.available(act) != null) View.VISIBLE else View.GONE
    }

    /** Only while Search is the page showing: a Search built ahead (hidden) never takes phone-remote typing. */
    override val searchInput: EditText? get() = (pages["SEARCH"] as? CinemaSearch)?.takeIf { key == "SEARCH" }?.input
    /** The window's background as this shell draws it (see [PageBackground]); installed on the first frame. */
    private var pageBg: PageBackground? = null
    private val visibleRect = android.graphics.Rect()
    /** Declared before init, which registers it. */
    private val bgSync = android.view.ViewTreeObserver.OnPreDrawListener { syncBackground(); true }



    init {
        bar = buildBar()
        // Opaque over its first third (nothing of the row above ghosts through), then a soft fall-off.
        topFade.background = kit.bgFade(kit.screenW, TOP_FADE.dp, 0, "top") { _, v -> 1f - CinemaKit.ease((v - 0.35f) / 0.65f) }
        root = FrameLayout(act).apply {
            addView(content, MATCH_PARENT, MATCH_PARENT)
            addView(topFade, FrameLayout.LayoutParams(MATCH_PARENT, TOP_FADE.dp, Gravity.TOP))
            addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, CinemaStyle.BAR_H.dp, Gravity.TOP))
        }
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> onFocus(now) }
        root.viewTreeObserver.addOnPreDrawListener(bgSync)
        appsSig = appsSig()
        DreamNotices.addListener(noticesChanged)
        noticesChanged()
        Seasons.shell = java.lang.ref.WeakReference(this)

        updateClock()
    }

    // ---- top bar ----

    private fun buildBar(): FrameLayout {
        val logo = ImageView(act).apply {
            // The rainbow glass cloud alone (the full lockup's lettering is too small to read at bar size).
            setImageResource(R.drawable.dream_mark)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val tabs = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        for ((t, icon) in TABS) {
            val v = tabView(t, icon)
            tabViews[t] = v
            tabs.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, TAB_H.dp).apply { if (tabs.childCount > 0) marginStart = 2.dp })
        }
        clock = kit.label("", 17f, kit.text, kit.semibold)
        val pk = premiumKit
        // The AirPods status (look, colors, place: right after the time).
        airPodsChip = pk.label("", 12.5f, pk.text, pk.semibold).apply {
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER_VERTICAL
            setPadding(9.dp, 0, 10.dp, 0)
            compoundDrawablePadding = 8.dp
            maxWidth = 210.dp
            setCompoundDrawables(airPodsDot, null, null, null)
            // The chip and status colors; focused, the same rainbow light edge as every highlight.
            val glow = CinemaStyle.glowDrawable(act, radiusDp = 16, haloDp = CinemaStyle.GLOW_HALO_SMALL)
            background = LayerDrawable(arrayOf(glow, StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(act, Rainbow.PLAY_REST, Ui.R_PILL))
                addState(IntArray(0), pk.glass(Ui.R_PILL, 96))
                setEnterFadeDuration(Ui.FOCUS_IN.toInt())
                setExitFadeDuration(Ui.FOCUS_OUT.toInt())
            }))
            visibility = View.GONE
            setOnClickListener { host.airPods() }
            setOnFocusChangeListener { v, has ->
                CinemaStyle.focusGlow(glow, has)
                Ui.lift(v, has, 1.06f)
                styleAirPods(v as TextView)
            }
        }
        val right = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(clock, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = 14.dp })
            addView(airPodsChip, LinearLayout.LayoutParams(WRAP_CONTENT, 32.dp).apply { marginEnd = 12.dp })
            // Notifications: the bell (same round control), its unread count on a small rainbow disc at the top right.
            addView(FrameLayout(act).apply {
                clipChildren = false
                clipToPadding = false
                addView(roundButton(LineIcon(LineIcon.BELL).apply { setTint(kit.text) }, null) { host.notices() }, FrameLayout.LayoutParams(36.dp, 36.dp))
                addView(noticeBadge, FrameLayout.LayoutParams(WRAP_CONTENT, 17.dp, Gravity.TOP or Gravity.END).apply { topMargin = (-4).dp; marginEnd = (-5).dp })
            }, LinearLayout.LayoutParams(36.dp, 36.dp).apply { marginEnd = 8.dp })
            addView(roundButton(LineIcon(LineIcon.GAUGE).apply { setTint(kit.text) }, null) { show(HomeShell.Tab.SYSTEM, focusContent = true) }, LinearLayout.LayoutParams(36.dp, 36.dp).apply { marginEnd = 8.dp })
            addView(FrameLayout(act).apply {
                clipChildren = false
                clipToPadding = false
                addView(roundButton(LineIcon(LineIcon.GEAR).apply { setTint(kit.text) }, null) { host.settings() }, FrameLayout.LayoutParams(36.dp, 36.dp))
                addView(updateDot, FrameLayout.LayoutParams(10.dp, 10.dp, Gravity.TOP or Gravity.END).apply { topMargin = (-1).dp; marginEnd = (-1).dp })
            }, LinearLayout.LayoutParams(36.dp, 36.dp).apply { marginEnd = 12.dp })
            val p = Profiles.current(act)
            addView(roundButton(null, p.emoji) { host.profileMenu() }.apply {
                (this as TextView).let { tv ->
                    tv.background = LayerDrawable(arrayOf((tv.background as LayerDrawable).getDrawable(0), GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(p.color); setStroke(1.dp, Ui.alpha(kit.text, 0.28f))
                    }))
                }
            }, LinearLayout.LayoutParams(36.dp, 36.dp))
        }
        // The logo, the tabs a clear gap after it (Search leading), the free space, then
        // the time, AirPods and the controls; the two groups always keep a gap, however long the AirPods name.
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(kit.gutter, 0, kit.gutter, 0)
            addView(brandMark(logo), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = 30.dp })
            addView(tabs, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
            addView(right, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 16.dp })
        }
        val b = FrameLayout(act).apply {
            clipChildren = false
            clipToPadding = false
            addView(row, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        // Down anywhere in the bar goes into the page (focus search could pick an odd target).
        val down = View.OnKeyListener { _, code, e ->
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN) { page?.focusFirst(); true } else false
        }
        fun hook(v: View) {
            if (v.isFocusable) v.setOnKeyListener(down)
            if (v is ViewGroup) for (i in 0 until v.childCount) hook(v.getChildAt(i))
        }
        hook(b)
        updateAirPods()
        return b
    }


    /**
     * Connected: icon and "<name> Connected" all in green. Disconnected: the name in the text color,
     * "Disconnected" in red, the icon dimmed. On the light focus glow the same colors go deep.
     */
    private fun styleAirPods(chip: TextView) {
        val pk = premiumKit
        val focused = chip.isFocused
        if (airPodsOn) {
            val c = if (focused) AIRPODS_GREEN_DEEP else AIRPODS_GREEN
            chip.text = "$airPodsName Connected"
            chip.setTextColor(c)
            airPodsDot.setTint(c)
            return
        }
        val name = if (focused) kit.ink else pk.text
        val red = if (focused) AIRPODS_RED_DEEP else AIRPODS_RED
        chip.text = android.text.SpannableString("$airPodsName Disconnected").apply {
            setSpan(android.text.style.ForegroundColorSpan(red), airPodsName.length + 1, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        chip.setTextColor(name)
        airPodsDot.setTint(Ui.alpha(name, 0.75f))
    }

    /** The headphones chip: hidden only when nothing was ever paired; long names ellipsize. */
    override fun updateAirPods() {
        val chip = airPodsChip ?: return
        val label = host.airPodsLabel()
        if (label == null) { chip.visibility = View.GONE; return }
        airPodsOn = label.contains("Connected") && !label.contains("Disconnected")
        airPodsName = label.substringBefore(" ·").removeSuffix(" Disconnected").removeSuffix(" Connected").trim().ifEmpty { "AirPods" }
        styleAirPods(chip)
        if (chip.visibility != View.VISIBLE) {
            chip.visibility = View.VISIBLE
            chip.alpha = 0f
            chip.animate().alpha(1f).setDuration(300).start()
        }
    }

    /** Focus the AirPods chip (after its card closes); false if it isn't showing. */
    override fun focusAirPods(): Boolean {
        val chip = airPodsChip?.takeIf { it.visibility == View.VISIBLE && it.isAttachedToWindow } ?: return false
        setBarShown(true)
        return chip.requestFocus()
    }

    /**
     * The bar's brand: the rainbow glass cloud with a small, wide-tracked "DREAM OS" under it whose color is the
     * fading rainbow (the shared clock, like Play and every glowing border), so they always match.
     */
    private fun brandMark(logo: ImageView): View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(logo, LinearLayout.LayoutParams(WRAP_CONTENT, 24.dp))
        val word = kit.label("DREAM OS", 10f, CinemaStyle.glowNow, kit.semibold, tracking = 0.18f).apply { gravity = Gravity.CENTER }
        addView(word, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 4.dp })
        CinemaStyle.followGlow(word) { v, c -> (v as TextView).setTextColor(c) }
    }

    /**
     * A tab: its line icon and label. Focused, a pearl capsule with ink text and a small glow; the current
     * page's tab is in full white with a short underline in the glow's hue (focused or not); the others sit back.
     */
    private fun tabView(t: HomeShell.Tab, iconShape: Int): View {
        val icon = LineIcon(iconShape)
        val iconView = ImageView(act).apply { setImageDrawable(icon) }
        // Search is its icon alone; the others are icon and label.
        val iconOnly = t == HomeShell.Tab.SEARCH
        val label = kit.label(t.label, CinemaStyle.T_TAB, kit.text, kit.medium).apply { if (iconOnly) visibility = View.GONE }
        // No focus bubble: a focused tab is its brighter, bolder label with the rainbow line under it (the
        // bubble around the label looked off with the centered line).
        val pill = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (iconOnly) setPadding(10.dp, 0, 10.dp, 0) else setPadding(10.dp, 0, 12.dp, 0)
            isDuplicateParentStateEnabled = true
            addView(iconView, LinearLayout.LayoutParams(if (iconOnly) 17.dp else 16.dp, if (iconOnly) 17.dp else 16.dp).apply { if (!iconOnly) marginEnd = 8.dp })
            addView(label)
        }
        val underline = View(act).apply {
            background = GradientDrawable().apply { cornerRadius = 2.dp.toFloat(); setColor(CinemaStyle.glowNow) }
            alpha = 0f
        }
        CinemaStyle.followGlow(underline) { v, c -> (v.background as? GradientDrawable)?.setColor(c) }
        val v = FlatFrame(act).apply {
            clipChildren = false
            clipToPadding = false
            isFocusable = true
            isClickable = true
            id = View.generateViewId()
            addView(pill, FrameLayout.LayoutParams(WRAP_CONTENT, PILL_H.dp, Gravity.TOP))
            addView(underline, FrameLayout.LayoutParams(18.dp, 3.dp, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }
        // The line sits exactly under the label's text (Search: under its icon), not under the whole
        // capsule with its icon and padding; same 18 dp line for every tab.
        pill.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val anchor = if (iconOnly) iconView else label
            underline.translationX = pill.left + anchor.left + anchor.width / 2f - v.width / 2f
        }
        val style = { focused: Boolean ->
            val current = barTab() == t
            val c = if (focused || current) kit.text else Ui.alpha(kit.text, 0.62f)
            label.setTextColor(c)
            label.typeface = if (current || focused) kit.semibold else kit.medium
            icon.setTint(c)
            // The line marks the current page, and the focused tab while focus is on the bar.
            underline.animate().alpha(if (current || focused) 1f else 0f).setDuration(200).start()
        }
        tabStyles[t] = style
        style(false)
        v.setOnClickListener { show(t, focusContent = true) }
        v.setOnFocusChangeListener { _, has ->
            style(has)
            v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(CinemaStyle.FOCUS_MS).setInterpolator(Ui.EASE).start()
            if (has) {
                pendingTab?.let { handler.removeCallbacks(it) }
                // A page already built switches almost at once (showing it is only a visibility flip and a
                // fade); one still to build waits for focus to rest, so sweeping across doesn't build every page.
                val delay = if (ready(tabKey(t))) TAB_READY_MS else TAB_REST_MS
                pendingTab = Runnable { if (v.isFocused && barTab() != t) show(t) }.also { handler.postDelayed(it, delay) }
            }
        }
        return v
    }

    /** A round control in the bar (Settings, the profile): glows like everything else when focused. */
    private fun roundButton(icon: android.graphics.drawable.Drawable?, emoji: String?, onClick: () -> Unit): View {
        val glow = CinemaStyle.glowDrawable(act, radiusDp = 18, haloDp = CinemaStyle.GLOW_HALO_SMALL).apply { haloAlpha = 0.45f }
        val v: View = if (emoji != null) kit.label(emoji, 17f).apply { gravity = Gravity.CENTER } else ImageView(act).apply {
            setImageDrawable(icon)
            val p = 8.dp
            setPadding(p, p, p, p)
        }
        v.background = LayerDrawable(arrayOf(glow, StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Rainbow.PLAY_REST) })
            addState(IntArray(0), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) })
        }))
        v.isFocusable = true
        v.isClickable = true
        v.id = View.generateViewId()
        v.setOnClickListener { onClick() }
        v.setOnFocusChangeListener { _, has ->
            CinemaStyle.focusGlow(glow, has)
            // On the pearl circle the line icon turns to dark ink.
            icon?.setTint(if (has) kit.ink else kit.text)
            v.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f).setDuration(CinemaStyle.FOCUS_MS).setInterpolator(Ui.EASE).start()
        }
        return v
    }

    private fun updateTabs() { for ((t, v) in tabViews) tabStyles[t]?.invoke(v.isFocused) }

    /** The bar's tab for the page showing: Browse's pages mark Browse, a service marks where it was opened from. */
    private fun barTab(): HomeShell.Tab = when (key) {
        "GENRE" -> if (genreFromSearch) HomeShell.Tab.SEARCH else HomeShell.Tab.BROWSE
        "MOVIES", "TV" -> HomeShell.Tab.BROWSE
        "SERVICE" -> if (serviceFromHome) HomeShell.Tab.HOME else HomeShell.Tab.BROWSE
        "SEASON" -> when (seasonFrom) {
            "GENRE" -> if (genreFromSearch) HomeShell.Tab.SEARCH else HomeShell.Tab.BROWSE
            "MOVIES", "TV", "BROWSE" -> HomeShell.Tab.BROWSE
            else -> HomeShell.Tab.HOME
        }
        else -> HomeShell.Tab.valueOf(key)
    }

    /** Pages opened from Browse (Back returns to the hub, onto the tile that opened them). */
    private fun inBrowse() = (key in BROWSE_CHILDREN && !(key == "GENRE" && genreFromSearch)) || (key == "SERVICE" && !serviceFromHome)

    /** Where Up from the top of a page lands: the current page's tab. */
    fun barEntry(): View? = (tabViews[barTab()] ?: tabViews[HomeShell.Tab.HOME])?.takeIf { it.isAttachedToWindow }

    private fun setBarShown(show: Boolean) {
        if (barShown == show) return
        barShown = show
        bar.animate().translationY(if (show) 0f else -(CinemaStyle.BAR_H + 8).dp.toFloat()).alpha(if (show) 1f else 0f)
            .setDuration(CinemaStyle.BAR_FADE_MS + if (show) 80 else 0).setInterpolator(if (show) Ui.EASE else Ui.EXIT).start()
        topFade.animate().alpha(if (show || page?.fadesTop() != true) 0f else 1f).setDuration(CinemaStyle.BAR_FADE_MS).start()
    }

    /** The page's [CPage.fadesTop] changed while the bar is away: the top veil follows it. */
    fun topVeilChanged() {
        topFade.animate().alpha(if (barShown || page?.fadesTop() != true) 0f else 1f).setDuration(CinemaStyle.BAR_FADE_MS).start()
    }

    override fun focusBar() {
        setBarShown(true)
        page?.toTop()
        barEntry()?.requestFocus()
    }

    private fun inBar(v: View) = HomeShell.isInside(v, bar)

    // ---- pages ----

    override fun warmArt(tab: HomeShell.Tab): () -> Boolean {
        val k = tabKey(tab)
        if (k == key) return { true } // on screen already
        val p = pages[k] ?: build(k).also { pages[k] = it; it.view.visibility = View.INVISIBLE; it.prepare() }
        if (p.view.parent !== content) { PremiumKit.detach(p.view); content.addView(p.view, MATCH_PARENT, MATCH_PARENT) }
        offscreenLater.remove(p)?.let { handler.removeCallbacks(it) }
        Net.rebind(act, p.view)
        val h = act.resources.displayMetrics.heightPixels
        // Laid out, its first screen has pictures (a page still fetching its list has none yet), all in and
        // none still rising in (rows that just arrived fade up).
        return { p.view.isLaidOut && countArt(p.view, h, missing = false) > 0 && countArt(p.view, h, missing = true) == 0 }
    }

    /**
     * Pictures in [v]'s first screen ([h] px) that will show: all of them, or ([missing]) those still loading
     * plus anything there still fading in.
     */
    private fun countArt(v: View, h: Int, missing: Boolean, top: Int = 0): Int {
        if (v.visibility == View.GONE || top > h) return 0
        if (v.alpha == 0f || v.width == 0 || v.height == 0) return 0 // kept hidden on purpose, or not laid out
        if (missing && v.alpha < 0.99f) return 1 // still fading in
        if (v is android.widget.ImageView) return if (v.tag is String && (!missing || v.drawable == null)) 1 else 0
        if (v !is android.view.ViewGroup) return 0
        var n = 0
        for (i in 0 until v.childCount) { val c = v.getChildAt(i); n += countArt(c, h, missing, top + c.top) }
        return n
    }

    override fun homePressed() {
        show(HomeShell.Tab.HOME)
        page?.toTop()
        page?.focusFirst()
    }

    override fun show(requested: HomeShell.Tab, force: Boolean, focusContent: Boolean, instant: Boolean) =
        showKey(tabKey(requested), force, focusContent, instant)

    private fun tabKey(t: HomeShell.Tab) = t.name

    /** A page that is built and current: showing it costs no build. */
    private fun ready(k: String) = pages[k]?.stale() == false

    /**
     * Built pages stay attached to [content] and hidden ones are INVISIBLE: they are neither drawn nor
     * measured afresh, and keep their display lists and layout, so a switch is a visibility flip and a
     * short fade (not a detach, re-attach, whole-page measure and re-record of every view, as it was).
     * A hidden page keeps its art for [OFFSCREEN_MS] (back-and-forth decodes nothing), then lets it go.
     */
    private fun showKey(k: String, force: Boolean = false, focusContent: Boolean = false, instant: Boolean = false) {
        val t0 = android.os.SystemClock.uptimeMillis()
        val cur = page
        if (k == key && cur != null && !force && cur.showing()) {
            if (focusContent) cur.focusFirst()
            return
        }
        cur?.let { hide(key, it) }
        key = k
        if (force || pages[k]?.stale() == true) drop(k)
        val built = pages[k] == null
        val p = pages.getOrPut(k) { build(k) }
        val t1 = android.os.SystemClock.uptimeMillis()
        page = p
        offscreenLater.remove(p)?.let { handler.removeCallbacks(it) }
        if (p.view.parent !== content) {
            PremiumKit.detach(p.view)
            content.addView(p.view, MATCH_PARENT, MATCH_PARENT)
        }
        Net.rebind(act, p.view)
        val tr = android.os.SystemClock.uptimeMillis()
        // A short fade up from a hair lower, on a hardware layer for just its length: the page is drawn
        // once into the layer and the fade only composites it (no per-view alpha passes on the stick's GPU).
        val v = p.view
        v.animate().cancel()
        v.visibility = View.VISIBLE
        if (instant) { v.alpha = 1f; v.translationY = 0f }
        else {
            v.alpha = 0f
            v.translationY = 10.dp.toFloat()
            v.animate().alpha(1f).translationY(0f).setDuration(PAGE_FADE_MS).setInterpolator(Ui.EASE).withLayer().start()
        }
        val tv = android.os.SystemClock.uptimeMillis()
        p.onShow()
        val ts = android.os.SystemClock.uptimeMillis()
        updateTabs()
        barShown = false // force the bar back in
        setBarShown(true)
        if (focusContent) p.focusFirst()
        val t2 = android.os.SystemClock.uptimeMillis()
        // The cost of the switch's first frame (layout, record, draw) lands before the second frame callback.
        android.view.Choreographer.getInstance().postFrameCallback {
            android.view.Choreographer.getInstance().postFrameCallback {
                android.util.Log.i("FireHome", "tab $k ${if (built) "built" else "kept"}: build ${t1 - t0} ms, show ${t2 - t1} ms (rebind ${tr - t1}, visible ${tv - tr}, onShow ${ts - tv}), first frame ${android.os.SystemClock.uptimeMillis() - t0} ms")
            }
        }
        schedulePrebuild()
    }

    /** Takes the outgoing page off screen: still attached, not drawn; its art goes a while later. */
    private fun hide(k: String, p: CPage) {
        p.onHide()
        p.view.animate().cancel()
        p.view.visibility = View.INVISIBLE
        p.view.alpha = 1f
        p.view.translationY = 0f
        if (p.view.parent !== content) return
        releaseArtLater(k, p)
    }

    /** [p] (hidden) lets go of its art after [OFFSCREEN_MS] unless it's shown before then. */
    private fun releaseArtLater(k: String, p: CPage) {
        offscreenLater.remove(p)?.let { handler.removeCallbacks(it) }
        offscreenLater[p] = Runnable { offscreenLater.remove(p); if (page !== p && pages[k] === p) p.offscreen() }.also { handler.postDelayed(it, OFFSCREEN_MS) }
    }

    /** Lets a built page go: off [content], released, rebuilt on its next visit. */
    private fun drop(k: String) {
        val p = pages.remove(k) ?: return
        offscreenLater.remove(p)?.let { handler.removeCallbacks(it) }
        if (p.view.parent === content) content.removeView(p.view)
        p.release()
    }

    private val offscreenLater = HashMap<CPage, Runnable>()

    /**
     * Records a hidden, built-ahead page's display lists (building a hardware layer records the whole tree;
     * the layer itself is let go at once). Children keep their recordings, so the first show only composites.
     */
    private fun warm(k: String, p: CPage) {
        val v = p.view
        if (destroyed || page === p || pages[k] !== p || v.parent !== content || !host.resumed()) return
        // Only in a quiet moment (it holds the main thread for a few hundred ms on the stick).
        val quietFor = android.os.SystemClock.uptimeMillis() - lastFocusAt
        val held = holdUntil - android.os.SystemClock.uptimeMillis()
        if (!v.isLaidOut || quietFor < PREBUILD_QUIET_MS || !host.idle() || held > 0) {
            handler.postDelayed({ warm(k, p) }, maxOf(WARM_DELAY_MS, PREBUILD_QUIET_MS - quietFor, held))
            return
        }
        val t0 = android.os.SystemClock.uptimeMillis()
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        v.buildLayer()
        v.setLayerType(View.LAYER_TYPE_NONE, null)
        android.util.Log.i("FireHome", "tab $k warmed in ${android.os.SystemClock.uptimeMillis() - t0} ms")
    }

    // ---- the window's background, left out under an opaque hero ----

    /**
     * Before every frame: the window's background (the theme's full-screen gradient or photo) skips the
     * rectangle that the current page's hero covers with opaque art ([CPage.opaqueArea]), so the hero area
     * is drawn once (the art) instead of twice. Anything else (a page fading or moving, no art yet, the hero
     * scrolled partly away, a cover over Home) narrows or drops the cut in the same frame, before it draws.
     * MainActivity setting the background again (the screensaver closing, a theme change) is picked up here.
     */
    private fun syncBackground() {
        if (destroyed) return
        val win = act.window ?: return
        val cur = win.decorView.background ?: return // the screensaver took it away while it covers Home
        var bg = pageBg
        if (cur !== bg) {
            if (!root.isShown) return
            bg = PageBackground(if (cur is PageBackground) cur.base else cur)
            pageBg = bg
            win.setBackgroundDrawable(bg)
        }
        val a = page?.opaqueArea()
        if (a == null || !settled(a) || !a.getGlobalVisibleRect(visibleRect)) { bg.clearCut(); return }
        bg.setCut(visibleRect)
    }

    /** [v] and everything above it draw where they're laid out: opaque, visible, no fade, move or scale under way. */
    private fun settled(v: View): Boolean {
        var x: View? = v
        while (x != null) {
            if (x.alpha < 1f || x.visibility != View.VISIBLE || !x.matrix.isIdentity) return false
            x = x.parent as? View
        }
        return true
    }

    // ---- building the likely next pages ahead ----

    private val prebuildTimer = Runnable { prebuildNext() }
    private var prebuildIdle: android.os.MessageQueue.IdleHandler? = null
    private var destroyed = false
    /** Pages may still want building: focus moving puts it off again (it waits for a quiet moment). */
    private var prebuildArmed = false
    /** When focus last moved (user activity): work ahead waits for a quiet spell. */
    private var lastFocusAt = 0L

    /** Once things have been quiet a moment (no focus moving), build the next page in idle time. */
    private fun schedulePrebuild() {
        handler.removeCallbacks(prebuildTimer)
        prebuildIdle?.let { Looper.myQueue().removeIdleHandler(it) }
        prebuildIdle = null
        prebuildArmed = !destroyed
        if (!destroyed) handler.postDelayed(prebuildTimer, PREBUILD_QUIET_MS)
    }

    /** No work ahead (page prebuilds, warming) until [ms] from now: e.g. while the boot intro fades out. */
    private var holdUntil = 0L

    fun holdWorkAhead(ms: Long) {
        holdUntil = android.os.SystemClock.uptimeMillis() + ms
    }

    /** How long work ahead is still held (0 = free to run); also the boot intro, which isn't done until [introDone]. */
    fun workAheadWait(): Long = if (!introDone) 1_000L else maxOf(0L, holdUntil - android.os.SystemClock.uptimeMillis())

    /** False while the boot intro is up (the host sets it around the intro); true otherwise. */
    @Volatile var introDone = true

    private fun prebuildNext() {
        if (destroyed || !host.resumed()) return // onResume starts it again
        val wait = holdUntil - android.os.SystemClock.uptimeMillis()
        if (wait > 0) { handler.removeCallbacks(prebuildTimer); handler.postDelayed(prebuildTimer, wait); return }
        val idle = android.os.MessageQueue.IdleHandler {
            prebuildIdle = null
            val k = PREBUILD.firstOrNull { it != key && !ready(it) }
            // A menu or details over Home: try again once it's gone (quiet again).
            if (k != null && (!host.idle() || page?.showing() != true)) schedulePrebuild()
            else if (k != null && !destroyed && host.resumed()) {
                val t0 = android.os.SystemClock.uptimeMillis()
                drop(k) // a stale one
                val p = build(k)
                pages[k] = p
                p.view.visibility = View.INVISIBLE
                content.addView(p.view, MATCH_PARENT, MATCH_PARENT)
                p.prepare()
                android.util.Log.i("FireHome", "tab $k prebuilt in ${android.os.SystemClock.uptimeMillis() - t0} ms")
                // Once it's laid out (a frame or two on), record its views' display lists in idle time too:
                // otherwise its first show records the whole page on the switch (~170 ms for Browse).
                handler.postDelayed({ warm(k, p) }, WARM_DELAY_MS)
                // Its art (bound so a quick visit shows ready) goes like a hidden page's if it isn't visited: it was
                // held until the page had been shown and hidden once.
                releaseArtLater(k, p)
                // One page per quiet spell: the next waits for its own (its layout lands in a later frame).
                schedulePrebuild()
            } else if (k == null) prebuildArmed = false
            false
        }
        prebuildIdle = idle
        Looper.myQueue().addIdleHandler(idle)
    }

    /** A brand tile (on Home or in Browse): that service's page (one kept at a time; Back returns where it came from). */
    fun openService(s: ServiceSpec, fromHome: Boolean) {
        if (serviceSpec?.name != s.name) drop("SERVICE")
        serviceSpec = s
        serviceFromHome = fromHome
        showKey("SERVICE", focusContent = true)
    }

    /** A genre card in Browse (or a genre search's Browse tile, [fromSearch]): its page (one kept at a time). */
    fun openGenre(s: GenreSpec, fromSearch: Boolean = false) {
        if (genreSpec?.name != s.name) drop("GENRE")
        genreSpec = s
        genreFromSearch = fromSearch
        showKey("GENRE", focusContent = true)
    }

    /** A seasonal hub's tile (Home, a genre page, another hub's page): that hub's page; Back returns to [opener]. */
    fun openSeason(hub: SeasonalHub, opener: View?) {
        if (key != "SEASON") { seasonFrom = key; seasonOpener = opener?.let { java.lang.ref.WeakReference(it) } }
        if (seasonHub?.id != hub.id || key == "SEASON") drop("SEASON")
        seasonHub = hub
        showKey("SEASON", force = key == "SEASON", focusContent = true)
    }

    /** The developer's season override changed (Seasons.override): Home and the pages showing a hub are rebuilt. */
    fun seasonsChanged() {
        if (destroyed) return
        for (k in listOf("SEASON", "GENRE")) if (k != key) drop(k)
        if (key == HOME) showKey(HOME, force = true) else drop(HOME)
    }

    /** Browse's TV Shows / Movies tiles (keys "TV", "MOVIES"). */
    fun openBrowseChild(k: String) = showKey(k, focusContent = true)

    private fun build(k: String): CPage = when (k) {
        "BROWSE" -> CinemaBrowse(this)
        "MOVIES" -> CinemaCategory(this, "movie")
        "TV" -> CinemaCategory(this, "tv")

        "GENRE" -> CinemaGenre(this, genreSpec ?: GenreSpec.SHORTCUTS.first())
        "MYLIST" -> CinemaMyList(this)
        "APPS" -> CinemaGrid(this, HomeShell.Tab.APPS)
        "SEARCH" -> CinemaSearch(this)
        "SYSTEM" -> CinemaSystem(this)
        "SERVICE" -> CinemaService(this, serviceSpec ?: ServiceSpec.SHORTCUTS.first())
        "SEASON" -> CinemaSeason(this, seasonHub ?: Seasons.active(act))
        else -> CinemaHome(this)
    }

    override fun focusContent() { page?.focusFirst() }

    override fun applyInsets(h: Int, v: Int) {
        // The gutter is already clear of overscan at the default fit; only a larger fit moves things in.
        val extra = maxOf(0, h - kit.gutter)
        content.clipToPadding = false
        content.setPadding(extra, 0, 0, 0)
        bar.setPadding(extra, maxOf(0, v - 8.dp), extra, 0)
    }

    private fun onFocus(now: View?) {
        lastFocusAt = android.os.SystemClock.uptimeMillis()
        if (prebuildArmed) schedulePrebuild()
        val p = page
        p?.focusMoved(now)
        now ?: return
        if (inBar(now)) { setBarShown(true); return }
        if (p == null || !HomeShell.isInside(now, p.view)) return
        p.onFocus(now)
        setBarShown(p.barVisible())
    }

    /** Back, one step at a time: the page's own step (a row → the top) → the bar → Home. */
    override fun handleBack(): Boolean {
        val f = act.currentFocus
        val p = page
        if (f != null && p != null && HomeShell.isInside(f, p.view)) {
            if (p.handleBack(f)) return true
            if (key == "GENRE" && genreFromSearch) {
                showKey("SEARCH")
                if ((page as? CinemaSearch)?.focusBack() != true) page?.focusFirst()
                return true
            }
            if (key == "SEASON") {
                // Back to the page that opened the hub, onto the hub's card there.
                val opener = seasonOpener?.get()
                showKey(seasonFrom)
                if (opener?.isAttachedToWindow != true || !opener.requestFocus()) page?.focusFirst()
                return true
            }
            if (key == "SERVICE" && serviceFromHome) {
                showKey(HOME)
                page?.focusFirst()
                return true
            }
            if (inBrowse()) {
                val from = key
                showKey("BROWSE")
                if ((page as? CinemaBrowse)?.focusTile(from, if (from == "SERVICE") serviceSpec?.name else genreSpec?.name) != true) page?.focusFirst()
                return true
            }
            focusBar()
            return true
        }
        if (f != null && inBar(f)) {
            if (key != HOME) {
                showKey(HOME)
                tabViews[HomeShell.Tab.HOME]?.requestFocus()
            } else p?.focusFirst()
            return true
        }
        p?.focusFirst()
        return true
    }


    // ---- clock and lifecycle ----

    private fun updateClock() {
        clock?.text = DreamTime.formatClock(act)
    }

    override fun onTick() = updateClock()

    override fun onResume() {
        updateClock()
        CinemaStyle.pause(false)
        pages.values.forEach { it.resume() }
        schedulePrebuild()
    }

    override fun onPause() {
        CinemaStyle.pause(true)
        pages.values.forEach { it.pause() }
        handler.removeCallbacks(prebuildTimer)
        prebuildIdle?.let { Looper.myQueue().removeIdleHandler(it) }
        prebuildIdle = null
    }

    /** Home has been hidden a while: the pages not showing (Home aside, where Home lands) go; rebuilt on the next visit. */
    override fun releaseGraphics() {
        for (t in pages.keys.filter { it != key && it != HOME }) drop(t)
        HeroArt.evict()

    }

    override fun destroy() {
        destroyed = true
        DreamNotices.removeListener(noticesChanged)
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(bgSync)
        pageBg?.let { bg -> act.window?.let { w -> if (w.decorView.background === bg) w.setBackgroundDrawable(bg.base) } }
        prebuildIdle?.let { Looper.myQueue().removeIdleHandler(it) }
        prebuildIdle = null
        offscreenLater.clear()
        handler.removeCallbacksAndMessages(null)
        pages.values.forEach { it.release() }
        pages.clear()
    }

    override fun historyChanged() { (pages[HOME] as? CinemaHome)?.refreshContinue() }

    override fun continueRemoved() { (pages[HOME] as? CinemaHome)?.continueRemoved() }

    /** What the pages show of the installed apps: a package broadcast that changes none of it rebuilds nothing. */
    private fun appsSig() = host.apps().joinToString("|") { it.pkg + "=" + it.label } + "#" + Apps.hidden(act).sorted().joinToString(",")

    override fun appsChanged() { appsChanged(resuming = false) }

    /**
     * Back in front after package broadcasts while away (MainActivity's resume): the same check as
     * [appsChanged], so a background system update rebuilds nothing; a real install, uninstall or hidden-app
     * change also rebuilds the page showing if it lists apps (it isn't under the user's hands yet).
     */
    fun appsResumed() = appsChanged(resuming = true)

    private fun appsChanged(resuming: Boolean) {
        // The stick updates system components in the background (each a package broadcast): only a change
        // to the app list or the hidden apps rebuilds Home, Browse and Apps.
        val sig = appsSig()
        val same = sig == appsSig
        appsSig = sig
        if (same) {
            if (resuming) android.util.Log.i("FireHome", "apps unchanged on resume: nothing rebuilt")
            return
        }
        // Apps lists them, and Home and Browse order the services by what's installed: rebuilt next visit (never under you).
        for (t in APP_PAGES) if (t != key) drop(t)
        if (key == "APPS" || (resuming && key in APP_PAGES)) {
            android.util.Log.i("FireHome", "apps changed: tab $key rebuilt")
            showKey(key, force = true)
        }
    }

    // ---- shared helpers for pages ----

    fun post(r: Runnable, delay: Long) = handler.postDelayed(r, delay)
    fun cancel(r: Runnable) = handler.removeCallbacks(r)
    fun host() = host
    fun activity() = act

    companion object {
        /** The tabs, in order, with their line icons. */
        private const val AIRPODS_GREEN = Ui.GREEN_TEXT
        private val AIRPODS_GREEN_DEEP = Color.parseColor("#0B6B38")
        private const val AIRPODS_RED = Ui.RED_TEXT
        private val AIRPODS_RED_DEEP = Color.parseColor("#B3122E")
        private val TABS = listOf(
            HomeShell.Tab.SEARCH to LineIcon.SEARCH,
            HomeShell.Tab.HOME to LineIcon.HOME,
            HomeShell.Tab.BROWSE to LineIcon.FILM,
            HomeShell.Tab.MYLIST to LineIcon.BOOKMARK,
            HomeShell.Tab.APPS to LineIcon.APPS,
        )
        private const val HOME = "HOME"
        private val BROWSE_CHILDREN = setOf("MOVIES", "TV", "GENRE")
        private const val TAB_H = 44
        private const val PILL_H = 36
        private const val TAB_REST_MS = 320L
        /** A built page: just enough to let a held D-pad's repeats pass without switching on each. */
        private const val TAB_READY_MS = 70L
        /** The pages built ahead, in idle time once Home is quiet: the bar's neighbours to the right, then Search (its field takes no typing until it shows). */
        private val PREBUILD = listOf("BROWSE", "MYLIST", "APPS", "SEARCH")
        /** The pages that show the installed apps (Apps lists them; Home and Browse order services by them). */
        private val APP_PAGES = listOf(HOME, "APPS", "BROWSE")
        /** The page fade-up (on a hardware layer, so only composited): quick, but still a fade, not a cut. */
        private const val PAGE_FADE_MS = 180L
        /**
         * Building or warming a tab ahead holds the main thread 120-400 ms on the stick: only after this long with no
         * focus moving (someone has stopped browsing), never in the short pause between presses (1.5 s froze the
         * first scroll down Home: Continue Watching -> Top Picks).
         */
        private const val PREBUILD_QUIET_MS = 4_000L
        /** How long a hidden page keeps its art (a quick trip back shows it at once, nothing decoded). */
        private const val OFFSCREEN_MS = 45_000L
        private const val WARM_DELAY_MS = 400L
        /** The fade under the top edge once rows scroll under it (dp). */
        private const val TOP_FADE = 64
    }
}

/** One page of the Cinema shell. */
abstract class CPage(val shell: CinemaShell) {
    protected val kit = shell.kit
    protected val ctx: Activity = shell.activity()
    protected val host = shell.host()
    protected val Int.dp get() = (this * ctx.resources.displayMetrics.density).toInt()
    abstract val view: View
    /** On screen: attached and not one of the shell's hidden (INVISIBLE) kept pages. */
    fun showing() = view.isAttachedToWindow && view.visibility == View.VISIBLE
    /** Built ahead and still hidden: start what the first screen needs (loads), without animating anything. */
    open fun prepare() {}
    open fun onShow() {}
    open fun onHide() {}
    /** Focus on the page's natural first control. */
    abstract fun focusFirst()
    /** Focus moved inside the page. */
    open fun onFocus(v: View) {}
    /** Focus moved anywhere in the window while this page shows. */
    open fun focusMoved(now: View?) {}
    /** A page-level Back step (true = handled). */
    open fun handleBack(focused: View): Boolean = false
    /** Whether the bar should show for where the page is now. */
    open fun barVisible(): Boolean = true
    /** Whether content can scroll under the top edge (the shell fades it there once the bar has gone). */
    open fun fadesTop(): Boolean = true
    /** Back to the page's resting state (scrolled to the top). */
    open fun toTop() {}
    open fun resume() {}
    open fun pause() {}
    open fun release() {}
    /** True when the kept page is out of date and should be built fresh on its next visit. */
    open fun stale(): Boolean = false
    /** The page went off screen (kept for later): let go of art until it shows again. */
    open fun offscreen() = Net.unbind(view)
    /** A view the page fills edge to edge with opaque pixels right now (the hero's art), or null. */
    open fun opaqueArea(): View? = null
}

/**
 * The window's background (the theme's gradient or photo) with one rectangle left out: the opaque hero
 * art covering it ([CinemaShell.syncBackground]). Drawn as up to four clipped bands around that rectangle
 * (rectangular clips are a scissor on the GPU: no extra pass), each through [base] laid out over the whole
 * window, so the bands line up exactly with what the full background would have shown.
 */
private class PageBackground(val base: android.graphics.drawable.Drawable) : android.graphics.drawable.Drawable(), android.graphics.drawable.Drawable.Callback {
    private val cut = android.graphics.Rect()
    private var cutting = false

    init { base.callback = this }

    fun setCut(r: android.graphics.Rect) {
        if (cutting && cut == r) return
        cutting = true
        cut.set(r)
        invalidateSelf()
    }

    fun clearCut() {
        if (!cutting) return
        cutting = false
        invalidateSelf()
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        if (!cutting || !android.graphics.Rect.intersects(cut, b)) { base.draw(canvas); return }
        val top = maxOf(b.top, cut.top)
        val bottom = minOf(b.bottom, cut.bottom)
        band(canvas, b.left, b.top, b.right, top)
        band(canvas, b.left, top, maxOf(b.left, cut.left), bottom)
        band(canvas, minOf(b.right, cut.right), top, b.right, bottom)
        band(canvas, b.left, bottom, b.right, b.bottom)
    }

    private fun band(c: android.graphics.Canvas, l: Int, t: Int, r: Int, b: Int) {
        if (r <= l || b <= t) return
        val n = c.save()
        c.clipRect(l, t, r, b)
        base.draw(c)
        c.restoreToCount(n)
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) { base.bounds = bounds }
    override fun setAlpha(alpha: Int) { base.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { base.colorFilter = colorFilter }
    @Deprecated("Drawable.getOpacity") @Suppress("DEPRECATION")
    override fun getOpacity(): Int = base.opacity
    override fun getIntrinsicWidth() = base.intrinsicWidth
    override fun getIntrinsicHeight() = base.intrinsicHeight
    override fun getPadding(padding: android.graphics.Rect) = base.getPadding(padding)
    override fun invalidateDrawable(who: android.graphics.drawable.Drawable) = invalidateSelf()
    override fun scheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)
    override fun unscheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable) = unscheduleSelf(what)
}
