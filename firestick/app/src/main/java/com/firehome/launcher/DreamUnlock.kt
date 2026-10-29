package com.firehome.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import android.view.View
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * "Unlock Dream OS Features": the one-time grants a Fire TV only gives over ADB (tools/deploy.sh does
 * them from a PC). Here Dream OS does them itself, with no PC: the viewer turns on ADB Debugging, Dream
 * OS connects to the stick's own ADB at 127.0.0.1:5555 with its own key (dadb), Fire TV asks "Allow USB
 * debugging?", and once allowed Dream OS grants itself the permissions below, then offers to turn ADB
 * Debugging off again (the grants stay).
 *
 * Only WRITE_SECURE_SETTINGS, the notification listener and two app-ops need ADB. With
 * WRITE_SECURE_SETTINGS Dream OS turns on the Home-button service, its voice search and screensaver
 * itself ([HomeRedirectService.ensureEnabled]), and puts them back after a Fire OS update.
 *
 * Nothing leaves the Fire TV: the connection is to the stick itself, and the key stays in Dream OS's
 * private storage.
 *
 * Some Fire OS builds (7.7.1.7 on the Fire TV Stick HD) hang up on every ADB connection that starts on
 * the stick itself, before AUTH and without a prompt. [probeLocal] spots that, and the viewer finishes
 * from another device on the Wi-Fi: the Dream app on an iPhone (Settings › Unlock Fire Stick, which runs
 * the same grants) or a computer. [Announce] lets the iPhone find this TV without typing its address.
 */
object DreamUnlock {
    private const val TAG = "FireHome"
    private const val HOST = "127.0.0.1"
    const val PORT = 5555

    /** One thing the unlock turns on, in the viewer's words (short enough for a two-column grid). */
    class Feature(val title: String, val caption: String, val on: (Context) -> Boolean)

    val FEATURES = listOf(
        Feature("Home Button", "Takes the place of Amazon's home screen.") { homeOn(it) },
        Feature("Starts in Dream OS", "Skips the Amazon home screen at startup.") { homeOn(it) },
        Feature("Voice Search", "The microphone button searches Dream OS.") { voiceOn(it) },
        // Dream OS's own Continue Watching never needs the unlock; this adds what plays in other apps.
        Feature("Your Other Apps", "Adds their shows to Continue Watching.") { listenerOn(it) },
        Feature("Screensaver", "Rests on the Dream OS screensaver.") { screensaverOn(it) },
        Feature("Stays Set Up", "Restores the Home button after Fire Stick updates.") { secureOn(it) },
    )

    // ---- what's on ----

    fun secureOn(ctx: Context) = ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    fun homeOn(ctx: Context) = HomeRedirectService.current() != null || accessibilityListed(ctx)
    fun voiceOn(ctx: Context) = secure(ctx, "voice_interaction_service") == "${ctx.packageName}/.FireVoiceService"
    fun listenerOn(ctx: Context) = secure(ctx, "enabled_notification_listeners").orEmpty().contains("${ctx.packageName}/")
    fun screensaverOn(ctx: Context) = secure(ctx, "screensaver_components").orEmpty().contains(DreamSaver::class.java.name)

    /** Everything unlocked. */
    fun allOn(ctx: Context) = FEATURES.all { it.on(ctx) }
    fun lockedCount(ctx: Context) = FEATURES.count { !it.on(ctx) }

    fun adbOn(ctx: Context) = try { Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 } catch (e: Exception) { false }

    private fun secure(ctx: Context, key: String) = try { Settings.Secure.getString(ctx.contentResolver, key) } catch (e: Exception) { null }
    private fun accessibilityListed(ctx: Context): Boolean {
        val me = "${ctx.packageName}/${HomeRedirectService::class.java.name}"
        return secure(ctx, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').any { it.equals(me, ignoreCase = true) } &&
            secure(ctx, Settings.Secure.ACCESSIBILITY_ENABLED) == "1"
    }

    // ---- the unlock ----

    sealed class Result {
        object Done : Result()
        /** This Fire TV refuses ADB from itself: finish from an iPhone or a computer. */
        object Blocked : Result()
        /** User-facing, plain words. */
        class Failed(val message: String) : Result()
    }

    /** What the stick's own ADB does with a connection from Dream OS. */
    enum class Local { OPEN, BLOCKED, OFF }

    /**
     * Sends a bare CNXN to 127.0.0.1:5555. A working adbd answers at once (AUTH); a Fire OS that blocks
     * on-device ADB closes the socket with nothing sent, or never answers. Blocking: off the main thread.
     */
    fun probeLocal(): Local = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(HOST, PORT), 2_000)
            s.soTimeout = 3_000
            s.getOutputStream().apply { write(cnxn()); flush() }
            if (s.getInputStream().read() < 0) Local.BLOCKED else Local.OPEN
        }
    } catch (e: java.net.ConnectException) {
        Local.OFF
    } catch (e: java.io.IOException) {
        // Reset by peer, or no answer within 3 s: either way no prompt will ever show.
        Log.w(TAG, "Unlock: local ADB probe: $e")
        Local.BLOCKED
    }

    private fun cnxn(): ByteArray {
        val payload = "host::\u0000".toByteArray()
        val cmd = 0x4e584e43 // "CNXN"
        return java.nio.ByteBuffer.allocate(24 + payload.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd).putInt(0x01000000).putInt(256 * 1024).putInt(payload.size)
            .putInt(payload.sumOf { it.toInt() and 0xff }).putInt(cmd.inv()).put(payload).array()
    }

    /** This TV's address on the Wi-Fi, for the iPhone or a computer. */
    fun address(): String? = PhoneRemote.localIp()

    /**
     * Announces this TV as `_dreamos-adb._tcp` (port 5555) while an Unlock screen shows, so the Dream app on
     * an iPhone lists it by name. Main thread only. [stop] waits a moment, so moving between Unlock screens
     * doesn't withdraw and re-announce.
     */
    object Announce {
        const val SERVICE = "_dreamos-adb._tcp"
        private val main = android.os.Handler(android.os.Looper.getMainLooper())
        private var nsd: android.net.nsd.NsdManager? = null
        private var reg: android.net.nsd.NsdManager.RegistrationListener? = null
        private val stopLater = Runnable { stopNow() }

        fun start(ctx: Context) {
            main.removeCallbacks(stopLater)
            if (reg != null) return
            try {
                val app = ctx.applicationContext
                val m = app.getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager
                val l = object : android.net.nsd.NsdManager.RegistrationListener {
                    override fun onServiceRegistered(i: android.net.nsd.NsdServiceInfo) { Log.w(TAG, "Unlock: announced as ${i.serviceName}") }
                    override fun onRegistrationFailed(i: android.net.nsd.NsdServiceInfo, err: Int) { Log.w(TAG, "Unlock: announce failed ($err); the address still works") }
                    override fun onServiceUnregistered(i: android.net.nsd.NsdServiceInfo) {}
                    override fun onUnregistrationFailed(i: android.net.nsd.NsdServiceInfo, err: Int) {}
                }
                val info = android.net.nsd.NsdServiceInfo().apply {
                    serviceName = BackupTransfer.deviceName(app).take(60); serviceType = SERVICE; port = PORT
                }
                m.registerService(info, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, l)
                nsd = m
                reg = l
            } catch (e: Exception) { Log.w(TAG, "Unlock: announce failed: $e") }
        }

        fun stop() {
            main.removeCallbacks(stopLater)
            main.postDelayed(stopLater, 1_500)
        }

        private fun stopNow() {
            reg?.let { r -> try { nsd?.unregisterService(r) } catch (e: Exception) {} }
            reg = null
        }
    }

    /** Dream OS's own ADB key, made once, kept in private storage. */
    private fun keyPair(ctx: Context): AdbKeyPair {
        val dir = File(ctx.applicationContext.noBackupFilesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.isFile || !pub.isFile) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    /**
     * Connects to the stick's own ADB and grants what needs it. Blocking: run off the main thread.
     * [progress] gets a headline and a plain sentence under it.
     */
    fun unlock(ctx: Context, progress: (headline: String, note: String) -> Unit): Result {
        val app = ctx.applicationContext
        if (!adbOn(app)) return Result.Failed("Turn on ADB Debugging first, then try again.")
        val t0 = System.currentTimeMillis()
        val pkg = app.packageName
        progress("Connecting to Your Fire Stick", "")
        when (probeLocal()) {
            Local.BLOCKED -> { Log.w(TAG, "Unlock: this Fire OS refuses ADB from the stick itself"); return Result.Blocked }
            Local.OFF -> return Result.Failed("Dream OS couldn't reach ADB Debugging. Make sure it's on, then try again.")
            Local.OPEN -> {}
        }
        return try {
            // The socket timeout covers waiting for the viewer to choose Allow on Fire TV's prompt.
            Dadb.create(HOST, PORT, keyPair(app), connectTimeout = 4_000, socketTimeout = 90_000).use { adb ->
                progress("Choose OK on the Next Screen", "Check “Always allow from this computer” first, so it stays allowed.")
                val hello = adb.shell("echo ready")
                if (!hello.output.contains("ready")) return Result.Failed("Your Fire Stick didn't answer. Try again.")
                progress("Unlocking", "This takes a few seconds.")
                val cmds = listOf(
                    "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
                    "cmd notification allow_listener $pkg/$pkg.AppWatchService",
                    "appops set $pkg GET_USAGE_STATS allow",
                    "appops set $pkg REQUEST_INSTALL_PACKAGES allow",
                    "pm grant $pkg android.permission.ACCESS_FINE_LOCATION",
                    "settings put secure screensaver_components $pkg/${DreamSaver::class.java.name}",
                    "settings put secure screensaver_enabled 1",
                )
                for (c in cmds) {
                    val r = adb.shell(c)
                    Log.w(TAG, "Unlock: ${c.substringBefore(' ')} ${c.split(' ').getOrNull(1).orEmpty()} -> exit ${r.exitCode}")
                }
            }
            // Now Dream OS can set its own secure settings: the Home button service and voice search.
            HomeRedirectService.ensureEnabled(app, userAsked = true)
            val missing = FEATURES.filter { !it.on(app) }.map { it.title }
            Log.w(TAG, "Unlock: done in ${System.currentTimeMillis() - t0} ms, ${FEATURES.size - missing.size}/${FEATURES.size} on" +
                if (missing.isEmpty()) "" else " (still off: ${missing.joinToString()})")
            Result.Done
        } catch (e: java.net.ConnectException) {
            Log.w(TAG, "Unlock: no ADB at $HOST:$PORT: $e")
            Result.Failed("Dream OS couldn't reach ADB Debugging. Make sure it's on, then try again.")
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "Unlock: no answer to the prompt: $e")
            Result.Failed("The Allow prompt timed out. Try again, then choose OK.")
        } catch (e: Exception) {
            Log.w(TAG, "Unlock: failed: $e")
            Result.Failed("Your Fire Stick didn't allow the connection. Try again and choose OK, with “Always allow” checked.")
        }
    }

    /** Turns ADB Debugging back off (needs the unlock's WRITE_SECURE_SETTINGS). True when it's off. */
    fun turnOffAdb(ctx: Context): Boolean = try {
        Settings.Global.putInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0)
        Log.w(TAG, "Unlock: ADB Debugging turned off")
        !adbOn(ctx)
    } catch (e: SecurityException) { false }

    /** Fire TV's My Fire TV page, where Developer Options (and ADB Debugging) live. */
    fun openFireTvSettings(ctx: Context): Boolean = try {
        ctx.startActivity(Intent().setClassName("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.tv.device.DeviceActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        try { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e2: Exception) { false }
    }
}

/** Settings › Unlock Features: the [UnlockPages] as a full page over the app (the same look as Settings › Accounts). */
object UnlockSettings {
    fun isOpen() = DreamPanelPage.isOpen()

    fun value(ctx: Context) = DreamUnlock.lockedCount(ctx).let { if (it == 0) "All Unlocked" else "$it Locked" }

    fun open(a: android.app.Activity, onClose: (() -> Unit)? = null) = DreamPanelPage.open(a, SetupArt.Kind.UNLOCK, "Unlock Features",
        "Let Dream OS fully take over your Fire Stick: the Home button, startup, voice search and more. A one-time step.", onClose) { ui, panel, _ ->
        Log.w("FireHome", "Unlock: Settings page opened (${value(a)})")
        UnlockPages(a, ui, panel, onChange = {}).overview()
    }
}

/**
 * The Unlock screens for a [SetupPanel], shared by setup and Settings: why it's needed, what it turns
 * on, the steps, the unlock itself (or, on a Fire TV that refuses it, how to finish from an iPhone or a
 * computer), then the result per feature and "Turn Off ADB Debugging".
 *
 * Every screen is built once and updated in place: its buttons exist from the start, so focus only moves
 * when the viewer moves it (or once, onto Try Again, when an attempt fails).
 */
internal class UnlockPages(
    private val ctx: Context,
    private val ui: SetupUi,
    private val panel: SetupPanel,
    private val onChange: () -> Unit,
    /** After ADB Debugging was turned off here (setup moves focus on to Continue). */
    private val afterTurnOff: (() -> Unit)? = null,
    /**
     * First-run setup: the wizard's stage says why and holds the actions (Unlock Now, Show Me How, Not Now:
     * [begin], [howTo]), so the overview is only what it turns on, in three columns that fit without scrolling.
     */
    private val setup: Boolean = false,
) {
    private val k = ui.k
    private val parts = SetupParts(ctx, k)
    /** ADB was turned off from this page: its row stays, showing that, so focus never falls off the page. */
    private var turnedOff = false
    private fun app() = ctx.applicationContext
    private fun dp(v: Int) = Ui.dp(ctx, v)
    /** Bumped by every attempt; an older attempt's late answer is dropped. */
    private var attempt = 0

    /**
     * Fits without scrolling: the actions first (the only things to focus), then what it turns on as a
     * compact two-column grid that isn't focusable. All unlocked: a result line, and focus goes to Continue.
     */
    fun overview(): SetupScreen {
        val s = SetupScreen(ui, null, null)
        val rows = ScreenRows(s)
        val marks = ArrayList<View>()
        fun paint() {
            val all = DreamUnlock.allOn(app())
            val adb = DreamUnlock.adbOn(app())
            if (!setup) s.view("why", 0) {
                parts.note("Your Fire Stick keeps a few settings locked to outside apps. Dream OS unlocks them in about a minute, " +
                    "right here or from the Dream app on your iPhone.", 15f)
            }
            val done = s.view("done", 14) { parts.result(true, "Everything Is Unlocked") }
            done.visibility = if (all && !setup) View.VISIBLE else View.GONE
            val go = rows.row("go", 14) { parts.row(null, chevron = true) { start(replace = false) } }
            go.set("Unlock Now", if (adb) "Dream OS connects to your Fire Stick and asks you to allow it." else "Turn on ADB Debugging first.")
            val steps = rows.row("steps", 6) { parts.row(null, chevron = true) { panel.push(steps()) } }
            steps.set("How to Turn On ADB Debugging", "Four quick steps", if (adb) "On" else null,
                if (adb) SetupBrand.GREEN else null, if (adb) BrandRow.Mark.OK else BrandRow.Mark.NONE)
            go.visibility = if (all || setup) View.GONE else View.VISIBLE
            steps.visibility = if (all || setup) View.GONE else View.VISIBLE
            val off = rows.row("off", 10) { parts.row(null, chevron = true) { if (DreamUnlock.adbOn(app())) turnOff(s) } }
            if (adb) off.set("Turn Off ADB Debugging", "Recommended. Everything stays unlocked.")
            else off.set("ADB Debugging Is Off", "Everything stays unlocked.", null, SetupBrand.GREEN, BrandRow.Mark.OK)
            off.visibility = if (!setup && all && DreamUnlock.secureOn(app()) && (adb || turnedOff)) View.VISIBLE else View.GONE
            if (!setup) s.view("k:what", 20) { parts.section("What It Turns On", null) }
            s.view("grid", 10) { featureGrid(marks, if (setup) 3 else 2) }
            for ((i, f) in DreamUnlock.FEATURES.withIndex()) {
                val want = if (f.on(app())) BrandRow.Mark.OK else BrandRow.Mark.OFF
                marks.getOrNull(i)?.let { m -> if (m.tag != want) { m.tag = want; m.background = StatusGlyph(want) } }
            }
        }
        s.onShow = { DreamUnlock.Announce.start(app()); paint(); onChange() }
        s.onResume = { paint(); onChange() }
        s.onHide = { DreamUnlock.Announce.stop() }
        return s
    }

    /** The six features in [cols] columns: a status mark, a short title and its caption, wrapping in full. Not focusable. */
    private fun featureGrid(marks: MutableList<View>, cols: Int): View {
        val grid = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
        for (pair in DreamUnlock.FEATURES.chunked(cols)) {
            val line = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
            for ((j, f) in pair.withIndex()) {
                val cell = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.TOP
                    if (cols > 2) setPadding(dp(12), dp(10), dp(12), dp(10)) else setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = Ui.rounded(ctx, Ui.alpha(android.graphics.Color.WHITE, 0.035f), Ui.R_MD)
                }
                val mark = View(ctx)
                marks += mark
                cell.addView(mark, android.widget.LinearLayout.LayoutParams(dp(20), dp(20)).apply { topMargin = dp(2); marginEnd = dp(12) })
                val texts = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
                texts.addView(k.label(f.title, 15.5f, SetupBrand.TEXT, k.medium, lines = 0))
                texts.addView(k.label(f.caption, 13f, SetupBrand.DIM, k.font, lines = 0).apply {
                    setPadding(0, dp(3), 0, 0)
                    hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                })
                cell.addView(texts, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                line.addView(cell, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    .apply { if (j > 0) marginStart = dp(10) })
            }
            grid.addView(line, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (grid.childCount > 0) topMargin = dp(10) })
        }
        return grid
    }

    /**
     * One numbered step: the round badge and the whole sentence, never cut (no line cap). A one-line step
     * sits centered on its badge; a longer one runs down from the badge's top.
     */
    private fun step(n: Int, text: CharSequence, badgeDp: Int, size: Float) = numberedStep(ctx, k, n, text, badgeDp, size)

    /** Two buttons side by side; [first] may be hidden, and then [second] starts at the edge. */
    private fun actions(first: View, second: View) = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(first, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)).apply { marginEnd = dp(14) })
        addView(second, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)))
    }

    /** Room below a screen's last button for its focus lift and glow, inside the scroller (its edge clips). */
    private fun endRoom() = View(ctx).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(16))
    }

    /** Setup's Unlock Now: the unlock (or the ADB steps first when ADB Debugging is off). */
    fun begin() = start(replace = false)

    /** Setup's Turn Off ADB Debugging is offered: everything is unlocked and ADB is still on. */
    fun canTurnOff() = DreamUnlock.allOn(app()) && DreamUnlock.secureOn(app()) && DreamUnlock.adbOn(app())

    /** Setup's Turn Off ADB Debugging (the overview repaints; [afterTurnOff] moves focus on). */
    fun turnOffAdb() { panel.top?.let { turnOff(it) } }

    /** Setup's Show Me How: the steps for turning on ADB Debugging. */
    fun howTo() = panel.push(steps())

    /** The picture steps for turning on ADB Debugging. Sized to fit setup's panel at 1080p without scrolling. */
    private fun steps(): SetupScreen {
        val s = SetupScreen(ui, "Turn On ADB Debugging", null)
        val lines = listOf(
            "Open Settings › My Fire TV.",
            "Missing Developer Options? Choose About, then press Select on your Fire Stick's name 7 times.",
            "Go back, open Developer Options and turn on ADB Debugging.",
            "Come back to Dream OS and choose Unlock Now.",
        )
        s.onShow = {
            for ((i, l) in lines.withIndex()) s.view("s$i", if (i == 0) 0 else 8) { step(i + 1, l, 30, 16f) }
            s.view("open", 18) { parts.pills(parts.pill("Open Fire Stick Settings", true) { DreamUnlock.openFireTvSettings(ctx) }) }
            s.view("end", 0) { endRoom() }
        }
        s.onResume = { if (DreamUnlock.adbOn(app())) panel.pop() }
        return s
    }

    /**
     * The unlock on this TV. A status line, a sentence and the actions, made once and updated in place.
     * "Not Now" is there from the start; "Try Again" joins it after a failed attempt and takes focus once.
     * [replace] swaps it in for the screen on top (Try Again on the iPhone screen) instead of stacking it.
     */
    private fun start(replace: Boolean) {
        if (!DreamUnlock.adbOn(app())) { panel.push(steps()); return }
        val s = SetupScreen(ui, null, null)
        val glyph = View(ctx)
        val head = k.label("", 22f, SetupBrand.TEXT, k.semibold, lines = 0)
        val status = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(glyph, android.widget.LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(head, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
        }
        val note = parts.note("", 16f)
        var busy = false
        lateinit var run: () -> Unit
        val retry = parts.pill("Try Again", true) { run() }.apply { visibility = View.GONE }
        val skip = parts.pill("Not Now", false) { if (s.alive) panel.pop() }
        fun show(headline: String, sentence: String, mark: BrandRow.Mark) {
            if (head.text.toString() != headline) head.text = headline
            note.text = sentence
            note.visibility = if (sentence.isEmpty()) View.GONE else View.VISIBLE
            if (glyph.tag != mark) { glyph.tag = mark; glyph.background = StatusGlyph(mark) }
        }
        run = {
            if (!busy) {
                busy = true
                val me = ++attempt
                show("Connecting to Your Fire Stick", "", BrandRow.Mark.BUSY)
                Thread {
                    val r = DreamUnlock.unlock(app()) { h, n -> ui.main.post { if (s.alive && me == attempt) show(h, n, BrandRow.Mark.BUSY) } }
                    ui.main.post {
                        busy = false
                        if (me != attempt) return@post
                        when (r) {
                            is DreamUnlock.Result.Done -> { if (s.alive) panel.pop(); onChange() }
                            is DreamUnlock.Result.Blocked -> if (s.alive) panel.replace(phone())
                            is DreamUnlock.Result.Failed -> {
                                show("Unlock Didn't Finish", r.message, BrandRow.Mark.WARN)
                                if (retry.visibility != View.VISIBLE) {
                                    retry.visibility = View.VISIBLE
                                    if (s.alive) retry.requestFocus()
                                }
                            }
                        }
                    }
                }.start()
            }
        }
        show("Connecting to Your Fire Stick", "", BrandRow.Mark.BUSY)
        s.onShow = {
            DreamUnlock.Announce.start(app())
            s.view("status", 0) { status }
            s.view("note", 10) { note }
            s.view("actions", 24) { actions(retry, skip) }
            s.view("end", 0) { endRoom() }
        }
        s.onHide = { DreamUnlock.Announce.stop() }
        s.focus = { retry.takeIf { it.visibility == View.VISIBLE } ?: skip }
        if (replace) panel.replace(s) else panel.push(s)
        run()
    }

    /**
     * This Fire TV refuses ADB from itself, so the unlock comes from another device on the Wi-Fi: the Dream
     * app on an iPhone (it finds this TV by its announcement, or by the address shown here) or a computer.
     * The screen watches for the grants and moves on by itself once they land.
     */
    private fun phone(): SetupScreen {
        val s = SetupScreen(ui, null, null)
        val ip = DreamUnlock.address()
        val where: CharSequence = if (ip == null) "Choose this Fire Stick in the list." else android.text.SpannableStringBuilder("Choose this Fire Stick, or enter ").apply {
            val from = length
            append(ip)
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), from, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(" (port ${DreamUnlock.PORT}).")
        }
        val retry = parts.pill("Try Again", true) { if (s.alive) start(replace = true) }
        val skip = parts.pill("Not Now", false) { if (s.alive) panel.pop() }
        val lockedAtStart = DreamUnlock.lockedCount(app())
        var last = lockedAtStart
        var steady = 0
        var homeSet = false
        val watch = object : Runnable {
            override fun run() {
                if (!s.alive) return
                val a = app()
                // The iPhone grants WRITE_SECURE_SETTINGS first; with it Dream OS sets the rest itself.
                if (!homeSet && DreamUnlock.secureOn(a)) { homeSet = true; HomeRedirectService.ensureEnabled(a, userAsked = true) }
                val locked = DreamUnlock.lockedCount(a)
                steady = if (locked == last) steady + 1 else 0
                last = locked
                if (locked == 0 || (locked < lockedAtStart && steady >= 3)) {
                    Log.w("FireHome", "Unlock: finished from another device, $locked still locked")
                    panel.pop()
                    onChange()
                    return
                }
                ui.main.postDelayed(this, 2_000)
            }
        }
        s.onShow = {
            DreamUnlock.Announce.start(app())
            s.view("head", 0) { parts.result(null, "Unlock From Your iPhone") }
            s.view("why", 10) { parts.note("This Fire Stick only accepts the unlock from another device on your Wi-Fi.", 16f) }
            s.view("s1", 16) { step(1, "On your iPhone, open Dream, then Settings › Unlock Fire Stick.", 28, 15.5f) }
            s.view("s2", 8) { step(2, where, 28, 15.5f) }
            s.view("s3", 8) { step(3, "On this TV, check “Always allow from this computer”, then choose OK.", 28, 15.5f) }
            s.view("actions", 20) { actions(retry, skip) }
            s.view("pc", 14) {
                parts.note("Using a computer instead? Follow “Make Dream Your Home Screen” at github.com/DenseCow/dream-os.", 13.5f)
            }
            s.view("end", 0) { endRoom() }
            ui.main.removeCallbacks(watch)
            ui.main.postDelayed(watch, 2_000)
        }
        s.onResume = { ui.main.removeCallbacks(watch); watch.run() }
        s.onHide = { ui.main.removeCallbacks(watch); DreamUnlock.Announce.stop() }
        Log.w("FireHome", "Unlock: showing the iPhone steps (${ip ?: "no address"})")
        return s
    }

    private fun turnOff(s: SetupScreen) {
        val ok = DreamUnlock.turnOffAdb(app())
        if (ok) turnedOff = true
        s.onResume()
        if (!ok) Log.w("FireHome", "Unlock: couldn't turn ADB off") else afterTurnOff?.invoke()
    }
}
