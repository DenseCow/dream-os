package com.firehome.launcher

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.FocusFinder
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

/**
 * Settings › About: what Dream OS is, its version (BuildConfig, never written in code), whether it's up to date
 * ([DreamUpdate.status]: the updater's own check, or in a build without an updater a read-only look at the public
 * repository, with no Install offered there), the source code, releases and disclaimer as QR codes with short
 * addresses (the TV has no browser), and the data credits (TMDB, JustWatch, OMDb). Same items as the iPhone's
 * Settings › About (DreamOS-iOS AboutView.swift).
 */
object AboutPage {
    private const val SITE = "github.com/" + DreamUpdate.PUBLIC_REPO
    private const val SOURCE_URL = "https://$SITE"
    private const val RELEASES_URL = "https://$SITE/releases"
    private const val DISCLAIMER_URL = "https://$SITE/blob/main/DISCLAIMER.md"

    private var page: View? = null

    fun isOpen() = page != null

    /** "Version 4.1 (DEV)" with the channel in its status color (the boot intro has the same tag). */
    fun versionText(): CharSequence = android.text.SpannableStringBuilder("Version ${BuildConfig.VERSION_NAME} ").apply {
        val at = length
        append(channelTag())
        setSpan(android.text.style.ForegroundColorSpan(channelColor()), at, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** "Build 40100" (BuildConfig.VERSION_CODE). */
    fun buildText() = "Build ${BuildConfig.VERSION_CODE}"

    /** True for builds published by dream-os-public's tools/release.sh (build.channel=PUBLIC); every other build is DEV. */
    fun isPublic() = BuildConfig.BUILD_CHANNEL == "PUBLIC"

    /** "(PUBLIC)" / "(DEV)". */
    fun channelTag() = if (isPublic()) "(PUBLIC)" else "(DEV)"

    /** Status green for PUBLIC, status red for DEV (STANDARDS §2). */
    fun channelColor() = if (isPublic()) 0xFF30D158.toInt() else 0xFFFF453A.toInt()

    /** Settings' About row caption: "Up to Date", "Version 4.3 Available", "Couldn't Check for Updates", … */
    fun statusText(ctx: Context): String {
        val s = DreamUpdate.status(ctx)
        return when {
            DreamUpdate.checking() -> "Checking for Updates…"
            s.newer -> "Version ${s.latest} Available"
            s.failed && s.checkedAt > 0 -> "Couldn't Check for Updates"
            s.checkedAt > 0 -> "Up to Date"
            else -> "Version, Updates and Credits"
        }
    }

    @Volatile private var asked = 0L
    private val mainThread = android.os.Handler(android.os.Looper.getMainLooper())

    /** Settings is showing: About's status is asked again in the background when it's over six hours old. */
    fun refreshIfDue(ctx: Context, done: () -> Unit) {
        val app = ctx.applicationContext
        val now = android.os.SystemClock.elapsedRealtime()
        if (!DreamUpdate.statusDue(app) || (asked != 0L && now - asked < 60_000L)) return
        asked = now
        Net.run {
            DreamUpdate.checkStatus(app)
            mainThread.post(done)
        }
    }

    fun open(a: Activity, onClose: (() -> Unit)? = null) {
        if (page != null) return
        Page(a, onClose)
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

    private enum class Link(val title: String, val address: String, val url: String) {
        SOURCE("Source Code", SITE, SOURCE_URL),
        RELEASES("All Releases", "$SITE/releases", RELEASES_URL),
        DISCLAIMER("Disclaimer", "DISCLAIMER.md at $SITE", DISCLAIMER_URL),
    }

    private class Page(val a: Activity, val onClose: (() -> Unit)?) {
        val k = PremiumKit(a)
        val ui = SetupUi(a, k)
        val panel = SetupPanel(ui)
        val back: View? = a.currentFocus
        val dp = { v: Int -> Ui.dp(a, v) }
        val root = Root(a) { if (!panel.pop()) close() }
        val main = SetupScreen(ui, null, "Dream OS")
        val qrBox = FrameLayout(a)
        val qrTitle: TextView = k.label("", 16f, k.text, k.semibold)
        val qrAddress: TextView = k.label("", 13.5f, k.dim, k.font, lines = 2)
        var shown: Link? = null
        var checkingHere = false
        val listener: () -> Unit = { if (main.alive) paint() }

        init {
            root.isClickable = true
            root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Ui.alpha(k.bgTop, 0.985f), Ui.alpha(Ui.mix(k.bgTop, k.bgBottom, 0.7f), 0.99f)))
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(PremiumKit.EDGE), dp(44), dp(56), dp(36))
                clipChildren = false
                clipToPadding = false
            }
            row.addView(left(), LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.36f))
            row.addView(panel, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.64f).apply { marginStart = dp(52) })
            root.addView(row, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.TOP))
            Ui.unclip(row) // focused rows lift past their rows: only the screen edge clips
            (a.window.decorView as FrameLayout).addView(root, MATCH_PARENT, MATCH_PARENT)
            page = root
            Ui.onDetach(root) { if (page === root) page = null } // Activity recreated with it open: it can open again
            DreamUpdate.addListener(listener)
            root.alpha = 0f
            root.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
            showLink(Link.SOURCE)
            main.onShow = { paint() }
            panel.push(main, animate = false)
            if (DreamUpdate.statusDue(a)) startCheck()
        }

        /** The mark, the name and version, one line on what Dream OS is, and the QR code of the chosen link. */
        fun left() = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(k.kicker("Settings"))
            addView(k.label("About", 30f, k.text, k.semibold, Ui.TRACK_TITLE),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // dream_mark.png is 175 x 108: the rainbow cloud, the brand itself.
                addView(ImageView(a).apply {
                    setImageResource(R.drawable.dream_mark)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                }, LinearLayout.LayoutParams(dp(68), dp(42)))
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(k.label("Dream OS", 22f, k.text, k.semibold))
                    addView(k.label(versionText(), 15f, k.dim, k.medium),
                        LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) })
                    addView(k.label(buildText(), 13f, Ui.alpha(k.dim, 0.8f), k.font),
                        LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(3) })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) })
            addView(k.label("Every movie and show in one place, played from your own accounts and sources.", 15f, k.dim, k.font, lines = 0)
                .apply { setLineSpacing(dp(3).toFloat(), 1f) },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(qrBox, LinearLayout.LayoutParams(dp(112), dp(112)))
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(qrTitle)
                    addView(qrAddress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
                    addView(k.label("Scan With Your Phone's Camera", 12.5f, Ui.alpha(k.dim, 0.8f), k.font, lines = 2),
                        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(22) })
        }

        /** The left QR code shows [l] (Source Code at first; the link rows switch it). */
        fun showLink(l: Link) {
            if (shown == l) return
            shown = l
            qrBox.removeAllViews()
            ui.qr(l.url, 112)?.let { qrBox.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
            qrTitle.text = l.title
            qrAddress.text = l.address
        }

        fun close() {
            if (page !== root) return
            page = null
            DreamUpdate.removeListener(listener)
            panel.release()
            root.animate().alpha(0f).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT).withEndAction { (root.parent as? ViewGroup)?.removeView(root) }.start()
            if (back != null && back.isAttachedToWindow) back.requestFocus()
            onClose?.invoke()
        }

        /** Every keyed part in its place once, top to bottom (later ones only show or hide, never append out of order). */
        var laidOut = false
        fun layoutOnce() {
            if (laidOut) return
            laidOut = true
            main.row("status", "Dream OS", null, static = true)
            main.row("install", "Install", chevron = true, top = 8) {}
            main.row("check", "Check for Updates", top = 8) { startCheck() }
            main.view("os-k", 22) { ui.kicker("Open Source") }
            main.row("source", Link.SOURCE.title, "Open Source  ·  GPL-3.0", "QR Code", top = 10) { showLink(Link.SOURCE) }
            main.row("releases", Link.RELEASES.title, "Every Version and What Changed", "QR Code") { showLink(Link.RELEASES) }
            main.row("disclaimer", Link.DISCLAIMER.title, "Please Read Before Using Dream OS", "QR Code") { showLink(Link.DISCLAIMER) }
            main.view("cr-k", 22) { ui.kicker("Credits") }
            // TMDB's terms: its own sentence, shown whole. JustWatch: the where-to-watch credit (STANDARDS §4).
            main.row("tmdb", "Movie and Show Data", "This product uses the TMDB API but is not endorsed or certified by TMDB.", "TMDB", top = 10)
            main.row("justwatch", "Where to Watch", "Streaming Availability From JustWatch", "JustWatch")
            main.row("omdb", "Scores", "Rotten Tomatoes and IMDb Scores From OMDb", "OMDb")
            main.hide("install")
        }

        fun paint() {
            layoutOnce()
            val s = DreamUpdate.status(a)
            val checking = checkingHere || DreamUpdate.checking()
            val cur = BuildConfig.VERSION_NAME
            when {
                checking -> main.row("status", "Checking for Updates…", "You have version $cur", mark = SetupUi.Mark.BUSY, static = true)
                s.newer -> main.row("status", "Version ${s.latest} Available", "You have version $cur", mark = SetupUi.Mark.WARN, static = true)
                s.failed && s.checkedAt > 0 -> main.row("status", "Couldn't Check for Updates",
                    "Check your internet connection, then try again.", mark = SetupUi.Mark.WARN, static = true)
                s.checkedAt > 0 -> main.row("status", "Dream OS Is Up to Date", "You have version $cur", mark = SetupUi.Mark.OK, static = true)
                else -> main.row("status", "Not Checked Yet", "You have version $cur", mark = SetupUi.Mark.OFF, static = true)
            }
            // Install only where this build has the updater (update.repo set); the owner's own build just shows the line.
            val av = if (DreamUpdate.enabled(a)) DreamUpdate.available(a) else null
            if (av != null && !checking) {
                main.row("install", "Install Version ${av.version}", "Your Fire Stick asks you to confirm",
                    DreamUpdate.sizeText(av.sizeBytes).ifEmpty { null }, chevron = true) {
                    UpdatePage.open(a, UpdatePage.INSTALL) { if (main.alive) paint() }
                }
            } else main.hide("install")
            // Always there: a forced check now (no six-hour wait), the result in the status row above.
            main.row("check", "Check for Updates",
                if (s.checkedAt > 0) "Last Checked ${UpdatePage.whenText(a, s.checkedAt)}" else "Not Checked Yet",
                if (checking) "Checking…" else null) { startCheck() }
        }

        fun startCheck() {
            if (checkingHere || DreamUpdate.checking()) return
            checkingHere = true
            if (main.alive) paint()
            Net.run {
                DreamUpdate.checkStatus(a)
                ui.main.post {
                    checkingHere = false
                    if (page === root && main.alive) paint()
                }
            }
        }
    }
}
