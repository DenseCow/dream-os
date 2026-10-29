package com.firehome.launcher

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
 * What the user sees of [DreamUpdate]:
 *  - [prompt]: the new-version notice on Home, the standard notification banner with its buttons:
 *    "There's a New Version Available", "Dream OS 4.1", Install Now (the rainbow) and Later (not again
 *    until tomorrow; it stays in the notifications list, with View Changes).
 *  - [open]: Settings › Updates: the version, its status, Install Now while a newer one waits, Check for
 *    Updates (with the last check), the Automatic Updates switch and What's New for the installed version.
 *    Installing shows its progress here, and guides the user to Fire TV's "Install unknown apps" switch
 *    when Dream OS isn't allowed yet.
 *  - [CHANGES]: the new version's notes on a clean page, with Install Now.
 *  - [settingsItems]: the Updates category of the Cinema / Premium Settings page.
 */
object UpdatePage {
    const val CHECK = "check"
    const val INSTALL = "install"
    const val NOTES = "notes"
    const val CHANGES = "changes"

    private var page: View? = null

    fun isOpen() = page != null

    // ---- Settings rows ----

    /** The "Updates" category. [refresh] re-reads the Settings page. */
    fun settingsItems(a: Activity, refresh: () -> Unit): List<PremiumSettings.Item> {
        val av = DreamUpdate.available(a)
        val cur = DreamUpdate.currentVersion()
        val err = DreamUpdate.lastError(a)
        val last = DreamUpdate.lastChecked(a)
        val auto = DreamUpdate.autoEnabled(a)
        return listOfNotNull(
            PremiumSettings.Item("Dream OS", "Version $cur", SettingsGlyph.SPARKLE, null,
                when {
                    av != null -> "Version ${av.version} Is Available"
                    err != null -> "Couldn't Check Last Time"
                    last > 0 -> "Up to Date"
                    else -> "Checks for a New Version Once a Day"
                }) { open(a, null, refresh) },
            if (av != null) PremiumSettings.Item("Install Version ${av.version}", DreamUpdate.sizeText(av.sizeBytes).ifEmpty { null },
                R.drawable.ic_bolt, null, "Your Fire Stick Asks You to Confirm") { open(a, INSTALL, refresh) } else null,
            PremiumSettings.Item("Check for Updates", null, LineIcon.CLOCK, null,
                if (last > 0) "Last Checked ${whenText(a, last)}" else "Not Checked Yet") { open(a, CHECK, refresh) },
            PremiumSettings.Item("Automatic Updates", null, R.drawable.ic_bolt, auto, "Downloads New Versions, Then Asks Before Installing") {
                DreamUpdate.setAuto(a, !DreamUpdate.autoEnabled(a)); refresh()
            },
            PremiumSettings.Item("What's New", "Version $cur", R.drawable.ic_info, null, "The Changes in the Version You Have") { open(a, NOTES, refresh) },
        )
    }

    /** "Today at 6:42 PM", "Yesterday at 9 AM", "Oct 21 at 8:05 PM" (Dream OS's time zone and clock format). */
    fun whenText(ctx: Context, ms: Long): String {
        val t = DreamTime.formatTime(ctx, ms)
        return when (DreamTime.dayDelta(ctx, ms)) {
            0 -> "Today at $t"
            -1 -> "Yesterday at $t"
            else -> DreamTime.fmt(ctx, "MMM d").format(ms) + " at $t"
        }
    }

    private fun releasedText(ctx: Context, r: DreamUpdate.Release): String = listOfNotNull(
        DreamUpdate.sizeText(r.sizeBytes).ifEmpty { null },
        if (r.publishedAt > 0) "Released " + DreamTime.fmt(ctx, "MMM d").format(r.publishedAt) else null,
    ).joinToString("  ·  ")

    // ---- the notice on Home ----

    /**
     * The new-version notice on Home: the standard notification banner ("There's a New Version Available",
     * "Dream OS 4.3") with Install Now (the rainbow, focused) and Later on it (DreamNotices.prompt). Install Now
     * opens Settings › Updates and starts the verified install; Later and Back keep it off Home until tomorrow
     * (it stays in the notifications list, where View Changes is too). [sound]: the notification sound (the
     * first time per version). Never shown over playback ([DreamUpdate] only calls it on a free Home). True
     * when it showed.
     */
    fun prompt(a: Activity, r: DreamUpdate.Release, sound: Boolean): Boolean {
        val key = DreamUpdate.NOTICE_PREFIX + r.version
        val n = DreamNotices.Notice(key, DreamNotices.TYPE_UPDATE, DreamUpdate.NOTICE_TITLE, DreamUpdate.noticeBody(r),
            DreamNotices.Severity.INFO, System.currentTimeMillis(), false, null)
        fun later() = DreamUpdate.snooze(a, r)
        return DreamNotices.prompt(n, listOf(
            DreamNotices.Choice("Install Now", primary = true) { DreamNotices.markRead(a, key); open(a, INSTALL) },
            DreamNotices.Choice("Later") { later() },
        ), sound) { later() }
    }

    // ---- the page ----

    /**
     * Settings › Updates. [action]: [CHECK] checks at once, [INSTALL] installs the waiting version, [NOTES]
     * lands on What's New, [CHANGES] opens on the new version's notes (Back then closes the page).
     */
    fun open(a: Activity, action: String? = null, onClose: (() -> Unit)? = null) {
        if (page != null) return
        Page(a, action, onClose)
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

    private class Page(val a: Activity, action: String?, val onClose: (() -> Unit)?) {
        val k = PremiumKit(a)
        val ui = SetupUi(a, k)
        val panel = SetupPanel(ui)
        val back: View? = a.currentFocus
        val dp = { v: Int -> Ui.dp(a, v) }
        val ink = PearlFocus.INK
        val root = Root(a) { if (!(changesOnly && panel.depth == 1 && panel.top === changes) && panel.pop()) Unit else close() }
        val main = SetupScreen(ui, null, "Dream OS")
        var changes: SetupScreen? = null
        var changesOnly = action == CHANGES
        val leftTitle: TextView
        val leftNote: TextView
        val leftVersion: TextView

        // install / check state
        var installing = false
        var handedOff = false
        var needPermission = false
        var installMsg: String? = null
        var installFailed = false
        var checkingHere = false
        var checkMsg: String? = null
        var mineNotes: String? = DreamUpdate.installedNotes(a)
        var mineLoading = false

        val listener: () -> Unit = { if (main.alive) paint() }
        val progress = ui.progress()
        val progressText = k.label("", Ui.T_CAPTION, k.dim, k.font, lines = 2)
        val tick = object : Runnable {
            override fun run() {
                if (page !== root) return
                if (DreamUpdate.downloading()) progress.set(DreamUpdate.downloadFraction())
                ui.main.postDelayed(this, 200)
            }
        }

        init {
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
            leftTitle = k.label("Updates", 30f, k.text, k.semibold, Ui.TRACK_TITLE, lines = 2)
            left.addView(leftTitle, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
            leftNote = k.label("Dream OS looks for a new version once a day and never updates while you're watching. " +
                "Your Fire Stick asks you to confirm every update.", 16f, k.dim, k.font, lines = 0).apply { setLineSpacing(dp(4).toFloat(), 1f) }
            left.addView(leftNote, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
            leftVersion = k.label("Dream OS ${DreamUpdate.currentVersion()}", 18f, k.text, k.medium, lines = 2)
            left.addView(leftVersion, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(24) })
            row.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.36f))
            row.addView(panel, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.64f).apply { marginStart = dp(52) })
            root.addView(row, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.TOP))
            Ui.unclip(row) // focused pills and rows lift past their rows: only the screen edge clips
            (a.window.decorView as FrameLayout).addView(root, MATCH_PARENT, MATCH_PARENT)
            page = root
            Ui.onDetach(root) { if (page === root) page = null } // Activity recreated with it open: it can open again
            DreamUpdate.addListener(listener)
            root.alpha = 0f
            root.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
            ui.main.post(tick)

            main.onShow = { paint(); leftForMain() }
            main.onResume = {
                // Back from Fire TV's installer without installing (Cancel): Install Now is offered again.
                handedOff = false
                // Back from Fire TV's settings: allowed now, so the install goes on by itself.
                if (needPermission && DreamUpdate.canInstall(a)) { needPermission = false; startInstall() } else paint()
            }
            val av = DreamUpdate.available(a)
            if (action == CHANGES && av != null) {
                panel.push(changesScreen(av), animate = false)
            } else {
                changesOnly = false
                panel.push(main, animate = false)
                when (action) {
                    CHECK -> startCheck()
                    INSTALL -> startInstall()
                    NOTES -> ui.main.postDelayed({ notesFocus()?.requestFocus() }, 120)
                }
            }
        }

        fun close() {
            if (page !== root) return
            page = null
            DreamUpdate.removeListener(listener)
            ui.main.removeCallbacks(tick)
            panel.release()
            root.animate().alpha(0f).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT).withEndAction { (root.parent as? ViewGroup)?.removeView(root) }.start()
            if (back != null && back.isAttachedToWindow) back.requestFocus()
            onClose?.invoke()
            DreamNotices.screenFreed()
        }

        fun leftForMain() {
            leftTitle.text = "Updates"
            leftNote.text = "Dream OS looks for a new version once a day and never updates while you're watching. Your Fire Stick asks you to confirm every update."
            leftVersion.text = "Dream OS ${DreamUpdate.currentVersion()}"
        }

        // ---- main screen ----

        /** Every keyed part in its place once, top to bottom (later ones only show or hide, never append out of order). */
        var laidOut = false
        fun layoutOnce() {
            if (laidOut) return
            laidOut = true
            main.row("status", "Dream OS", null, static = true)
            main.view("actions", 12) {
                ui.pillRow(ui.pill("Install Now", primary = true) { startInstall() },
                    ui.pill("View Changes", primary = false) { DreamUpdate.available(a)?.let { panel.push(changesScreen(it)) } })
                    .apply { setPadding(dp(6), dp(2), 0, dp(2)); clipChildren = false; clipToPadding = false }
            }
            main.view("progress", 14) {
                LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(6), 0, dp(6), 0)
                    addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, dp(6)))
                    addView(progressText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
                }
            }
            main.row("perm", DreamUpdate.MSG_PERMISSION, null, null, SetupUi.Mark.WARN, chevron = true, top = 12) {}
            main.row("check", "Check for Updates", top = 16) { startCheck() }
            main.row("auto", "Automatic Updates") {}
            main.view("wn-k", 22) { ui.kicker("What's New in Version ${DreamUpdate.currentVersion()}") }
            main.view("wn", 10) { LinearLayout(a).apply { orientation = LinearLayout.VERTICAL } }
            main.hide("actions"); main.hide("progress"); main.hide("perm")
        }

        fun paint() {
            layoutOnce()
            val av = DreamUpdate.available(a)
            val cur = DreamUpdate.currentVersion()
            val checking = checkingHere || DreamUpdate.checking()
            val err = checkMsg ?: DreamUpdate.lastError(a)
            when {
                checking -> main.row("status", "Checking for Updates…", "Version $cur", mark = SetupUi.Mark.BUSY, static = true)
                handedOff && av != null -> main.row("status", "Your Fire Stick Is Asking to Confirm",
                    "Choose Install on the Fire Stick's screen. Dream OS restarts on its own when it's done.", mark = SetupUi.Mark.BUSY, static = true)
                installing && av != null -> main.row("status", "Installing Version ${av.version}", installMsg ?: "Getting Ready…", mark = SetupUi.Mark.BUSY, static = true)
                av != null -> main.row("status", "Version ${av.version} Is Available",
                    if (installFailed && installMsg != null) installMsg else releasedText(a, av) + if (DreamUpdate.ready(a)) "  ·  Downloaded" else "",
                    mark = if (installFailed) SetupUi.Mark.WARN else SetupUi.Mark.NONE, static = true)
                err != null && DreamUpdate.lastChecked(a) > 0 -> main.row("status", "Couldn't Check for Updates", err, mark = SetupUi.Mark.WARN, static = true)
                else -> main.row("status", "Dream OS Is Up to Date", "Version $cur", mark = SetupUi.Mark.OK, static = true)
            }
            // Install Now and View Changes while a newer version waits.
            if (av != null && !handedOff && !installing) main.view("actions", 12) { View(a) } else main.hide("actions")
            if (installing || DreamUpdate.downloading()) {
                main.view("progress", 14) { View(a) }
                progressText.text = installMsg ?: if (DreamUpdate.downloading()) "Downloading in the Background…" else ""
                if (DreamUpdate.downloading()) progress.set(DreamUpdate.downloadFraction())
            } else main.hide("progress")
            if (needPermission && !DreamUpdate.canInstall(a)) {
                main.row("perm", DreamUpdate.MSG_PERMISSION,
                    "Your Fire Stick asks once. On the next screen, turn on Dream OS under Install Unknown Apps, then come back here.",
                    "Open Settings", SetupUi.Mark.WARN, chevron = true, top = 12) {
                    if (!DreamUpdate.openInstallSettings(a)) {
                        installMsg = "Open Settings  ›  My Fire TV  ›  Developer Options  ›  Install Unknown Apps, then turn on Dream OS."
                        installFailed = true
                        paint()
                    }
                }
            } else main.hide("perm")
            val last = DreamUpdate.lastChecked(a)
            main.row("check", "Check for Updates", if (last > 0) "Last Checked ${whenText(a, last)}" else "Not Checked Yet",
                if (checking) "Checking…" else null, top = 16) { startCheck() }
            val auto = DreamUpdate.autoEnabled(a)
            main.row("auto", "Automatic Updates", "Downloads new versions in the background, then asks before installing.",
                if (auto) "On" else "Off") { DreamUpdate.setAuto(a, !DreamUpdate.autoEnabled(a)) }
            // What's New for the version you have.
            val notesBox = main.view("wn", 10) { LinearLayout(a) } as LinearLayout
            val notes = mineNotes
            val want = notes ?: if (mineLoading) "…" else ""
            if (notesBox.tag != want) {
                notesBox.tag = want
                notesBox.removeAllViews()
                when {
                    notes != null && notes.isBlank() -> notesBox.addView(ui.note("No notes were published for this version."))
                    notes != null -> fillNotes(notesBox, notes)
                    mineLoading -> notesBox.addView(ui.note("Getting the notes…"))
                    else -> {
                        notesBox.addView(ui.note("The notes for this version will show here."))
                        loadMine()
                    }
                }
            }
        }

        fun notesFocus(): View? = (main.view("wn", 10) { LinearLayout(a) } as? ViewGroup)?.let { SetupScreen.firstFocusable(it) } ?: main.rowFor("check")?.view

        fun loadMine() {
            if (mineLoading || !DreamUpdate.enabled(a)) return
            mineLoading = true
            Net.run {
                val n = DreamUpdate.fetchInstalledNotes(a)
                ui.main.post { mineLoading = false; if (n != null) mineNotes = n; if (n != null && main.alive) paint() }
            }
        }

        fun startCheck() {
            if (checkingHere) return
            checkingHere = true
            checkMsg = null
            paint()
            Net.run {
                var msg: String? = null
                DreamUpdate.checkNow(a) { msg = it }
                ui.main.post {
                    checkingHere = false
                    checkMsg = msg?.takeIf { it.startsWith("Couldn't") }
                    if (page !== root) return@post
                    installFailed = false
                    installMsg = null
                    if (main.alive) {
                        paint()
                        // A new version: focus lands on Install Now.
                        if (DreamUpdate.available(a) != null) main.focus()?.requestFocus()
                    }
                }
            }
        }

        fun startInstall() {
            val r = DreamUpdate.available(a) ?: run { paint(); return }
            if (installing) return
            installing = true
            installFailed = false
            handedOff = false
            installMsg = "Getting Ready…"
            progress.set(0f)
            paint()
            Net.run {
                val ok = DreamUpdate.install(a, r) { m -> ui.main.post { if (installing) { installMsg = m; if (main.alive) { progressText.text = m } } } }
                ui.main.post {
                    installing = false
                    handedOff = ok
                    if (!ok) {
                        needPermission = installMsg == DreamUpdate.MSG_PERMISSION
                        installFailed = !needPermission
                    }
                    if (page !== root) return@post
                    if (main.alive) {
                        paint()
                        if (needPermission) main.rowFor("perm")?.view?.requestFocus()
                        else if (!ok) main.focus()?.requestFocus()
                    }
                }
            }
        }

        // ---- the new version's notes ----

        fun changesScreen(r: DreamUpdate.Release): SetupScreen {
            val s = SetupScreen(ui, null, "Version ${r.version}")
            changes = s
            s.view("install", 4) {
                ui.pillRow(ui.pill("Install Now", primary = true) {
                    if (panel.depth > 1) panel.pop() else { changesOnly = false; panel.replace(main) }
                    startInstall()
                }).apply { setPadding(dp(6), dp(2), 0, dp(2)); clipChildren = false; clipToPadding = false }
            }
            s.view("notes", 18) {
                LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    if (r.notes.isBlank()) addView(ui.note("No notes were published for this version."))
                    else fillNotes(this, r.notes)
                }
            }
            s.onShow = {
                leftTitle.text = "What's New"
                leftNote.text = "Dream OS ${r.version}" + releasedText(a, r).let { if (it.isEmpty()) "" else "  ·  $it" }
                leftVersion.text = "You have version ${DreamUpdate.currentVersion()}"
            }
            return s
        }

        /**
         * Notes as a calm list: each bullet, and each run of plain lines, one focusable block (so the list
         * scrolls with the remote), the Dream focus on it like every row.
         */
        fun fillNotes(box: LinearLayout, notes: String) {
            val blocks = ArrayList<String>()
            val para = StringBuilder()
            fun flush() { if (para.isNotEmpty()) { blocks += para.toString(); para.setLength(0) } }
            for (l in notes.lines()) {
                when {
                    l.isBlank() -> flush()
                    l.startsWith("•") -> { flush(); blocks += l }
                    else -> { if (para.isNotEmpty()) para.append('\n'); para.append(l) }
                }
            }
            flush()
            val focused = intArrayOf(android.R.attr.state_focused)
            for ((i, b) in blocks.withIndex()) {
                val bullet = b.startsWith("•")
                val text = if (bullet) b.removePrefix("•").trim() else b
                val v = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(14), dp(10), dp(18), dp(10))
                    isFocusable = true
                    isClickable = true
                    background = StateListDrawable().apply {
                        addState(focused, Ui.focusFill(a, k.select, Ui.R_MD))
                        addState(IntArray(0), Ui.rounded(a, Color.TRANSPARENT, Ui.R_MD))
                        setEnterFadeDuration(Ui.FOCUS_IN.toInt())
                        setExitFadeDuration(Ui.FOCUS_OUT.toInt())
                    }
                }
                val states = ColorStateList(arrayOf(focused, IntArray(0)), intArrayOf(ink, Ui.alpha(k.text, if (bullet) 0.92f else 0.80f)))
                // A drawn dot beside the first line (never a text "•"); ink on the focused face.
                if (bullet) v.addView(View(a).apply {
                    isDuplicateParentStateEnabled = true
                    background = StateListDrawable().apply {
                        addState(focused, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ink) })
                        addState(IntArray(0), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.alpha(k.text, 0.92f)) })
                    }
                }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginStart = dp(2); marginEnd = dp(14); topMargin = dp(9) })
                v.addView(k.label(text, 15f, k.text, if (bullet) k.font else k.medium, lines = 0).apply {
                    setTextColor(states)
                    isDuplicateParentStateEnabled = true
                    setLineSpacing(dp(3).toFloat(), 1f)
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                cinemaRowFocus(v, Ui.R_MD)
                box.addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (i > 0) topMargin = dp(4) })
            }
        }
    }
}
