package com.firehome.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Settings › Accounts & Scrapers › Trakt: sign in (device code + QR), the connected account, Sync Now
 * and Sign Out, for the current profile. The same page shell and screens as Streaming Accounts
 * ([SetupPanel] / [SetupScreen]). Always in Settings: a build without its own Trakt keys (open
 * source) first asks for the viewer's own free Trakt app ([TraktPages.ownApp]), then signs in.
 */
object TraktPage {
    private var openPage: View? = null
    /** The key field on screen (Client ID / Client Secret): Phone Remote's typing goes here. */
    private var activeField: QueryField? = null

    /** The row's value: "Not Connected", "Connected as @user" or "Sign In Again". */
    fun status(ctx: Context): String = when {
        Trakt.signedIn(ctx) -> Trakt.user(ctx)?.username?.let { "Connected as @$it" } ?: "Connected"
        Trakt.expired(ctx) -> "Sign In Again"
        else -> "Not Connected"
    }

    /** The Settings row (always shown). [refresh] re-reads the Settings rows. */
    fun settingsItems(a: Activity, refresh: () -> Unit): List<PremiumSettings.Item> {
        val caption = when {
            Trakt.signedIn(a) -> Trakt.lastSyncedText(a)
            Trakt.expired(a) -> "Your Trakt Sign-In Has Ended"
            else -> "Sync Watched History, Progress and My List"
        }
        return listOf(PremiumSettings.Item("Trakt", status(a), R.drawable.ic_tv, null, caption = caption) { open(a, refresh) })
    }

    /** Text typed on the phone remote: fills the key field on screen, if one is. */
    fun phoneText(text: String): Boolean {
        val f = activeField ?: return false
        if (openPage == null || !f.isAttachedToWindow) return false
        f.setQuery(text.filterNot { it.isWhitespace() })
        return true
    }

    internal fun fieldShown(f: QueryField) { activeField = f }
    internal fun fieldHidden(f: QueryField) { if (activeField === f) activeField = null }

    fun isOpen() = openPage != null

    /** Opens the page over the current screen; [onClose] runs once it's gone. */
    fun open(a: Activity, onClose: (() -> Unit)? = null) {
        if (openPage != null) return
        val k = PremiumKit(a)
        val ui = SetupUi(a, k)
        val panel = SetupPanel(ui)
        val pages = TraktPages(a, ui, panel)
        val back = a.currentFocus
        val dp = { v: Int -> Ui.dp(a, v) }
        lateinit var root: Root
        fun close() {
            val r = root
            openPage = null
            activeField = null
            Trakt.onChanged = null
            r.animate().alpha(0f).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT).withEndAction {
                panel.release() // stops the sign-in's polling and countdown with the screens
                (r.parent as? ViewGroup)?.removeView(r)
            }.start()
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
        left.addView(k.label("Trakt", 30f, k.text, k.semibold, Ui.TRACK_TITLE, lines = 2),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        val who = Profiles.current(a).name.takeIf { it.isNotBlank() }
        left.addView(k.label("Keep what you watch in step across your devices: finished movies and episodes, where you left off, and your watchlist." +
            (who?.let { " Signed in for $it's profile only." } ?: ""), 16f, k.dim, k.font, lines = 0).apply { setLineSpacing(dp(4).toFloat(), 1f) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        row.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.36f))
        row.addView(panel, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.64f).apply { marginStart = dp(52) })
        root.addView(row, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.TOP))
        Ui.unclip(row) // focused pills and rows lift past their rows: only the screen edge clips
        (a.window.decorView as FrameLayout).addView(root, MATCH_PARENT, MATCH_PARENT)
        openPage = root
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        panel.push(pages.overview(), animate = false)
    }

    /** Full-screen root: keeps focus inside, and takes Back (one screen, then closes). */
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
}

/**
 * The Trakt screens: the overview (no Trakt app yet, signed out, or signed in), the viewer's own
 * Trakt app (open-source builds), the device sign-in, Advanced, and the confirmations.
 */
internal class TraktPages(
    private val ctx: Context,
    private val ui: SetupUi,
    private val panel: SetupPanel,
    /** Opened from an Accounts overview (setup or Settings › Accounts): a finished sign-in goes back there. */
    private val fromAccounts: Boolean = false,
) {
    private val k = ui.k
    private val Int.dp get() = Ui.dp(ctx, this)
    private fun app() = ctx.applicationContext

    // ---- overview ----

    fun overview(): SetupScreen {
        val s = SetupScreen(ui, null, "TRAKT")
        var asked = false
        /** Which state the body was built for ("setup", "out", "in"): a change rebuilds it, so sections stay in order. */
        var built = ""
        lateinit var paint: () -> Unit
        val clearAsked = Runnable { asked = false; if (s.alive) paint() }
        paint = paint@{
            val state = when {
                !Trakt.configured(app()) -> "setup"
                !Trakt.signedIn(app()) -> "out"
                else -> "in"
            }
            if (state != built) {
                val hadFocus = s.body.hasFocus()
                if (built.isNotEmpty()) s.clear()
                built = state
                if (hadFocus) ui.main.post { if (s.alive && !s.body.hasFocus()) s.focus()?.requestFocus() }
            }
            when (state) {
                "setup" -> {
                    s.view("note", 0) { ui.note("Sign in with Trakt and Dream OS keeps this profile's watched history, Continue Watching and My List in step with your Trakt account.") }
                    s.row("own", "Use Your Own Trakt App", "Free, and Takes About a Minute", chevron = true, top = 16) { panel.push(ownApp()) }
                }
                "out" -> {
                    val again = Trakt.expired(app())
                    (s.view("note", 0) { ui.note("") } as TextView).text =
                        if (again) "Your Trakt sign-in has ended. Sign in again to keep this profile in sync."
                        else "Sign in with Trakt and Dream OS keeps this profile's watched history, Continue Watching and My List in step with your Trakt account."
                    s.row("in", if (again) "Sign In Again" else "Sign In", "Opens a Short Code and a QR Code", chevron = true, top = 16) {
                        panel.push(signIn())
                    }
                    advancedRow(s)
                }
                else -> {
                    val header = s.view("acct", 0) { AccountHeader() } as AccountHeader
                    header.bind(Trakt.user(app()))
                    s.view("k1", 20) { ui.kicker("Sync") }
                    val busy = asked || Trakt.isSyncing()
                    val caption = when {
                        busy -> Trakt.lastSyncedText(app())
                        Trakt.lastSyncFailed -> "Couldn't Reach Trakt. Try Again."
                        else -> Trakt.lastSyncedText(app())
                    }
                    s.row("sync", "Sync Now", caption, if (busy) "Syncing…" else "",
                        if (busy) SetupUi.Mark.BUSY else SetupUi.Mark.NONE, top = 6) {
                        if (asked || Trakt.isSyncing()) return@row
                        asked = true
                        Trakt.sync(app(), "sync now", force = true)
                        ui.main.removeCallbacks(clearAsked)
                        ui.main.postDelayed(clearAsked, 90_000)
                        paint()
                    }
                    s.row("w", "Watched History", "Movies and episodes you finish are marked watched", static = true, top = 6)
                    s.row("c", "Continue Watching", "Pick up where you left off on any device", static = true, top = 6)
                    s.row("l", "My List", "Stays in step with your Trakt watchlist", static = true, top = 6)
                    s.view("k2", 20) { ui.kicker("Account") }
                    s.row("out", "Sign Out", "Stops syncing this profile with Trakt", chevron = true, top = 6) { panel.push(confirmSignOut()) }
                    advancedRow(s)
                }
            }
        }
        val listener: () -> Unit = {
            if (asked && !Trakt.isSyncing()) { asked = false; ui.main.removeCallbacks(clearAsked) }
            if (s.alive) paint()
        }
        s.onShow = {
            Trakt.onChanged = listener
            paint()
        }
        s.onResume = { paint() }
        // Covered or closed: Trakt no longer holds this screen (opened from Accounts, nothing else clears it).
        s.onHide = {
            ui.main.removeCallbacks(clearAsked)
            if (Trakt.onChanged === listener) Trakt.onChanged = null
        }
        s.focus = { s.rowFor("sync")?.view ?: s.rowFor("in")?.view ?: s.rowFor("own")?.view }
        return s
    }

    /** Advanced (only on the viewer's own Trakt app): the keys, and removing them. */
    private fun advancedRow(s: SetupScreen) {
        if (!Trakt.usesOwnKeys(app())) { s.hide("k3"); s.hide("adv"); return }
        s.view("k3", 20) { ui.kicker("Advanced") }
        s.row("adv", "Advanced", "Your Trakt app keys", chevron = true, top = 6) { panel.push(advanced()) }
    }

    /** The account: avatar (or its initial), name, and "@username · Connected". */
    private inner class AccountHeader : LinearLayout(ctx) {
        private val initial = k.label("", 22f, k.text, k.semibold).apply { gravity = Gravity.CENTER }
        private val avatar = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val name = k.label("", Ui.T_SECTION, k.text, k.semibold)
        private val handle = k.label("", Ui.T_CAPTION, k.dim, k.font)
        private var shown: String? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 4.dp, 14.dp, 4.dp)
            val disc = FrameLayout(ctx).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.alpha(k.text, 0.10f)) }
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) }
                }
                clipToOutline = true
                addView(initial, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(avatar, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            addView(disc, LayoutParams(56.dp, 56.dp))
            val col = LinearLayout(ctx).apply { orientation = VERTICAL }
            col.addView(name)
            col.addView(handle, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 6.dp })
            addView(col, LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = 16.dp })
            layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        fun bind(u: Trakt.User?) {
            val username = u?.username
            name.text = u?.name ?: username ?: "Trakt"
            handle.text = listOfNotNull(username?.let { "@$it" }, "Connected").joinToString(" · ")
            initial.text = (u?.name ?: username)?.firstOrNull()?.uppercase() ?: ""
            val url = u?.avatar
            if (url != shown) {
                shown = url
                avatar.setImageDrawable(null)
                if (url != null) Net.image(ctx, avatar, url)
            }
        }
    }

    // ---- the viewer's own Trakt app (no built-in keys) ----

    /**
     * Use Your Own Trakt App: three short steps with a QR code to Trakt's app page, the redirect URI
     * to paste, then the Client ID and Client Secret. Sign In checks them with Trakt, saves them
     * (sealed) and goes straight on to the sign-in code.
     */
    fun ownApp(): SetupScreen {
        val s = SetupScreen(ui, "Use Your Own Trakt App", "TRAKT")
        var id = ""
        var secret = ""
        var checking = false
        s.add(ui.note("Dream OS is open source, so it doesn't come with Trakt keys. Your own Trakt app is free and is used only by this Fire Stick."), 0)

        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        wrap.addView(ui.steps(listOf(
            "Scan the code or go to trakt.tv/oauth/applications, then choose New Application.",
            "Give it any name, and enter the Redirect URI below.",
            "Save it, then enter its Client ID and Client Secret here.",
        )), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        ui.qr(APPS_URL, 132)?.let { wrap.addView(it, LinearLayout.LayoutParams(132.dp, 132.dp).apply { marginStart = 20.dp }) }
        s.add(wrap, 14)

        // The redirect URI, large and on its own so it reads clearly from the couch.
        val uri = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            background = Ui.rounded(ctx, Ui.alpha(k.text, 0.05f), Ui.R_MD, 1f, Ui.alpha(k.text, 0.10f))
            addView(k.kicker("Redirect URI"))
            addView(k.label(REDIRECT_URI, 20f, k.text, k.semibold).apply { letterSpacing = 0.02f },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 8.dp })
        }
        s.add(uri, 14)

        s.add(ui.kicker("Your App Keys"), 20)
        val error = ui.note("").apply { visibility = View.GONE }
        fun paint() {
            s.row("id", "Client ID", if (id.isEmpty()) "On Your Trakt App's Page" else masked(id), if (id.isEmpty()) "Enter" else "Change",
                if (id.isEmpty()) SetupUi.Mark.OFF else SetupUi.Mark.OK, chevron = true, top = 6) {
                if (!checking) panel.push(keyEntry("Client ID", id) { id = it; error.visibility = View.GONE })
            }
            s.row("secret", "Client Secret", if (secret.isEmpty()) "Optional · Only if Your App Has One" else "Entered · Ends in ${secret.takeLast(4)}",
                if (secret.isEmpty()) "Enter" else "Change", if (secret.isEmpty()) SetupUi.Mark.OFF else SetupUi.Mark.OK, chevron = true, top = 6) {
                if (!checking) panel.push(keyEntry("Client Secret", secret) { secret = it; error.visibility = View.GONE })
            }
        }
        paint()
        s.add(error, 12)
        lateinit var go: TextView
        go = ui.pill("Sign In", true) {
            if (checking) return@pill
            if (id.isEmpty()) {
                error.text = "Enter your app's Client ID."
                error.visibility = View.VISIBLE
                return@pill
            }
            checking = true
            error.visibility = View.GONE
            go.text = "Checking…"
            s.bg({ Trakt.tryOwnKeys(app(), id, secret) }) { r ->
                checking = false
                go.text = "Sign In"
                when (r) {
                    is Trakt.KeyCheck.Ok -> panel.replace(signIn(r.code))
                    is Trakt.KeyCheck.Failed -> { error.text = r.message; error.visibility = View.VISIBLE }
                }
            }
        }
        s.add(ui.pillRow(go), 16)
        s.onShow = { paint() }
        s.onHide = { checking = false; go.text = "Sign In" }
        s.focus = { if (id.isNotEmpty()) go else s.rowFor("id")?.view }
        return s
    }

    private fun masked(v: String) = if (v.length <= 10) v else v.take(4) + "…" + v.takeLast(4)

    /**
     * One key typed with the Dream keyboard (Caps and _ for Trakt's newer keys), from Phone Remote, or with the
     * QR codes beside it (Trakt's apps page, Type on Your Phone); Done hands it back and returns.
     */
    private fun keyEntry(title: String, initial: String, done: (String) -> Unit): SetupScreen =
        setupEntry(ctx, ui, SetupParts(ctx, k), title, "YOUR TRAKT APP", "Your $title", initial, listOf("_", "-"),
            "Scan the code to open your apps on Trakt, then copy the $title from your app's page. Phone Remote can type it here.",
            "https://trakt.tv/oauth/applications") { typed ->
            done(typed.filterNot { it.isWhitespace() })
            panel.pop()
        }

    // ---- Advanced: the viewer's own app keys ----

    private fun advanced(): SetupScreen {
        val s = SetupScreen(ui, "Advanced", "TRAKT")
        val id = Trakt.keys(app())?.id.orEmpty()
        s.row("app", "Your Trakt App", if (id.isEmpty()) null else "Client ID ${masked(id)}", static = true, top = 0)
        s.row("remove", "Remove Trakt App Keys", "Signs every profile on this Fire Stick out of Trakt", chevron = true, top = 8) {
            panel.push(confirmRemoveKeys())
        }
        return s
    }

    private fun confirmRemoveKeys(): SetupScreen {
        val s = SetupScreen(ui, "Remove Trakt App Keys?", "TRAKT")
        s.add(ui.note("Dream OS forgets your Trakt app's keys, and every profile on this Fire Stick is signed out of Trakt. Everything already on this Fire Stick stays. You can enter keys again anytime."), 0)
        val remove = ui.pill("Remove", false) {
            Trakt.removeOwnKeys(app())
            panel.pop() // this confirmation
            panel.pop() // Advanced, back to the overview
        }
        val cancel = ui.pill("Cancel", false) { panel.pop() }
        s.add(ui.pillRow(cancel, remove), 20)
        s.focus = { cancel }
        return s
    }

    // ---- sign-in (device code) ----

    /** [ready]: a code already fetched (checking the viewer's own keys gets one), shown at once. */
    fun signIn(ready: Trakt.DeviceCode? = null): SetupScreen {
        val s = SetupScreen(ui, null, "TRAKT")
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val textCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val step = k.label("Getting a code…", Ui.T_LABEL, k.dim, k.font, lines = 2)
        val code = k.label("", 40f, k.text, k.semibold).apply { letterSpacing = 0.12f; visibility = View.GONE }
        val site = k.label("", Ui.T_BODY, k.text, k.medium, lines = 2)
        val status = k.label("", Ui.T_CAPTION, k.dim, k.font, lines = 2).apply { visibility = View.GONE }
        val countdown = k.label("", Ui.T_CAPTION, k.dim, k.font).apply { visibility = View.GONE }
        textCol.addView(step)
        textCol.addView(code, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        textCol.addView(site, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 8.dp })
        textCol.addView(status, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 12.dp })
        textCol.addView(countdown, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 6.dp })
        val qrBox = FrameLayout(ctx)
        wrap.addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        wrap.addView(qrBox, LinearLayout.LayoutParams(132.dp, 132.dp).apply { marginStart = 20.dp })
        s.add(wrap, 0)
        val button = ui.pill("Cancel", false) { panel.pop() }
        s.add(ui.pillRow(button), 20)
        s.focus = { button }
        val tag = "trakt-signin"
        var finished = false
        var started = false
        fun stop() {
            if (!finished) android.util.Log.w("FireHome", "Trakt: sign-in stopped by the user")
            finished = true
            ui.main.removeCallbacksAndMessages(tag)
        }
        s.onBack = { stop(); false }
        s.onHide = { stop() }

        fun fail(message: String, newCode: Boolean, badKeys: Boolean = false) {
            finished = true
            ui.main.removeCallbacksAndMessages(tag)
            qrBox.removeAllViews()
            step.text = message
            code.visibility = View.GONE
            site.text = ""
            status.visibility = View.GONE
            countdown.visibility = View.GONE
            button.text = when { badKeys -> "Enter Keys Again"; newCode -> "Get a New Code"; else -> "Try Again" }
            button.setOnClickListener { panel.replace(if (badKeys) ownApp() else signIn()) }
        }

        fun connected(u: Trakt.User?) {
            finished = true
            ui.main.removeCallbacksAndMessages(tag)
            qrBox.removeAllViews()
            step.text = "Connected"
            // The drawn green check where the code was (never a text "✓").
            code.text = ""
            code.setCompoundDrawablesRelative(StatusGlyph(BrandRow.Mark.OK).apply { setBounds(0, 0, 48.dp, 48.dp) }, null, null, null)
            code.visibility = View.VISIBLE
            site.text = u?.username?.let { "Signed in as $it" } ?: "Signed in to Trakt"
            status.text = "Your history and watchlist are syncing now."
            status.visibility = View.VISIBLE
            countdown.visibility = View.GONE
            // No Done button: the check shows for a moment, then the page goes back on its own (to the
            // Accounts overview when it was opened from there, its Trakt row now Connected). The pill
            // fades out but keeps focus, so a press meanwhile does nothing and focus never jumps.
            button.setOnClickListener(null)
            button.animate().alpha(0f).setDuration(Ui.OVERLAY_OUT).start()
            ui.main.postAtTime({
                if (!s.alive) return@postAtTime
                panel.pop()
                if (fromAccounts) panel.pop()
            }, tag, SystemClock.uptimeMillis() + 1500)
        }

        fun tick(dc: Trakt.DeviceCode) {
            if (finished || !s.alive) return
            val left = ((dc.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            if (left <= 0) { fail("The code expired. Get a new one to try again.", newCode = true); return }
            countdown.text = "Code Expires in %d:%02d".format(left / 60, left % 60)
            ui.main.postAtTime({ tick(dc) }, tag, SystemClock.uptimeMillis() + 1000)
        }

        fun poll(dc: Trakt.DeviceCode, intervalS: Int) {
            if (finished || !s.alive) return
            s.bg({ Trakt.pollDevice(app(), dc) }) { r ->
                if (finished) return@bg
                when (r) {
                    is Trakt.Poll.Waiting -> ui.main.postAtTime({ poll(dc, intervalS) }, tag, SystemClock.uptimeMillis() + intervalS * 1000L)
                    is Trakt.Poll.SlowDown -> (intervalS + 2).let { n -> ui.main.postAtTime({ poll(dc, n) }, tag, SystemClock.uptimeMillis() + n * 1000L) }
                    is Trakt.Poll.Done -> connected(r.user)
                    is Trakt.Poll.Failed -> fail(r.message, r.expired, r.badKeys)
                }
            }
        }

        fun show(dc: Trakt.DeviceCode) {
            step.text = "On your phone or computer, go to"
            site.text = dc.url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            code.text = dc.userCode
            code.visibility = View.VISIBLE
            status.text = "Then enter this code. Waiting for you to approve…"
            status.visibility = View.VISIBLE
            countdown.visibility = View.VISIBLE
            ui.qr(dc.url, 132)?.let { v -> v.alpha = 0f; qrBox.addView(v); v.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start() }
            tick(dc)
            ui.main.postAtTime({ poll(dc, dc.intervalS) }, tag, SystemClock.uptimeMillis() + dc.intervalS * 1000L)
        }

        fun begin(retries: Int) {
            s.bg({ Trakt.startDevice(app()) ?: Unit }) { dc ->
                if (finished) return@bg
                if (dc !is Trakt.DeviceCode) {
                    // Trakt didn't answer: one calm retry by itself, then say so.
                    if (retries > 0) ui.main.postAtTime({ if (s.alive && !finished) begin(retries - 1) }, tag, SystemClock.uptimeMillis() + 3000)
                    else fail("Couldn't reach Trakt. Try again.", newCode = false)
                    return@bg
                }
                show(dc)
            }
        }
        s.onShow = {
            if (!started) {
                started = true
                if (ready != null && ready.expiresAt - System.currentTimeMillis() > 60_000) show(ready) else begin(1)
            }
        }
        return s
    }

    // ---- sign out ----

    private fun confirmSignOut(): SetupScreen {
        val s = SetupScreen(ui, "Sign Out of Trakt?", "TRAKT")
        s.add(ui.note("Dream OS stops syncing this profile with Trakt. Everything already on this Fire Stick stays, and your Trakt account keeps its history. You can sign in again anytime."), 0)
        val out = ui.pill("Sign Out", false) {
            Trakt.signOut(app())
            panel.pop()
        }
        val cancel = ui.pill("Cancel", false) { panel.pop() }
        s.add(ui.pillRow(cancel, out), 20)
        s.focus = { cancel }
        return s
    }

    companion object {
        /** Where a Trakt account creates its own API apps. */
        const val APPS_URL = "https://trakt.tv/oauth/applications"
        /** The redirect URI Trakt asks for: only renewal sends it, and Trakt's development apps take only localhost. */
        const val REDIRECT_URI = "http://localhost"
    }
}
