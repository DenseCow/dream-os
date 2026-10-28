package com.firehome.launcher

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
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
 */
object DreamUnlock {
    private const val TAG = "FireHome"
    private const val HOST = "127.0.0.1"
    private const val PORT = 5555

    /** One thing the unlock turns on, in the viewer's words (short enough for a two-column grid). */
    class Feature(val title: String, val caption: String, val on: (Context) -> Boolean)

    val FEATURES = listOf(
        Feature("Home Button", "Takes the place of Amazon's home screen.") { homeOn(it) },
        Feature("Starts in Dream OS", "Skips the Amazon home screen at startup.") { homeOn(it) },
        Feature("Voice Search", "The microphone button searches Dream OS.") { voiceOn(it) },
        Feature("Continue Watching", "Includes shows from your other apps.") { listenerOn(it) },
        Feature("Screensaver", "Rests on the Dream OS screensaver.") { screensaverOn(it) },
        Feature("Stays Set Up", "Restores the Home button after Fire TV updates.") { secureOn(it) },
    )

    // ---- what's on ----

    fun secureOn(ctx: Context) = ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    fun homeOn(ctx: Context) = HomeRedirectService.current() != null || accessibilityListed(ctx)
    fun voiceOn(ctx: Context) = secure(ctx, "voice_interaction_service") == "${ctx.packageName}/.FireVoiceService"
    fun listenerOn(ctx: Context) = secure(ctx, "enabled_notification_listeners").orEmpty().contains("${ctx.packageName}/")
    fun screensaverOn(ctx: Context) = secure(ctx, "screensaver_components").orEmpty().contains(DreamSaver::class.java.name)
    fun usageOn(ctx: Context) = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) { false }
    fun installsOn(ctx: Context) = try { ctx.packageManager.canRequestPackageInstalls() } catch (e: Exception) { false }

    /** Everything unlocked. */
    fun allOn(ctx: Context) = FEATURES.all { it.on(ctx) }
    fun anyLocked(ctx: Context) = !allOn(ctx)
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
        /** User-facing, plain words. */
        class Failed(val message: String) : Result()
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
     * [progress] gets plain status lines ("Waiting for you to choose Allow…").
     */
    fun unlock(ctx: Context, progress: (String) -> Unit): Result {
        val app = ctx.applicationContext
        if (!adbOn(app)) return Result.Failed("Turn on ADB Debugging first, then try again.")
        val t0 = System.currentTimeMillis()
        val pkg = app.packageName
        return try {
            progress("Connecting to your Fire TV…")
            // The socket timeout covers waiting for the viewer to choose Allow on Fire TV's prompt.
            Dadb.create(HOST, PORT, keyPair(app), connectTimeout = 4_000, socketTimeout = 90_000).use { adb ->
                progress("On the next screen, check “Always allow from this computer”, then choose OK.")
                val hello = adb.shell("echo ready")
                if (!hello.output.contains("ready")) return Result.Failed("Your Fire TV didn't answer. Try again.")
                progress("Unlocking…")
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
            Result.Failed("Fire TV didn't allow the connection. Try again and choose OK, with “Always allow” checked.")
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
    private var openPage: View? = null

    fun value(ctx: Context) = DreamUnlock.lockedCount(ctx).let { if (it == 0) "All Unlocked" else "$it Locked" }

    fun open(a: android.app.Activity, onClose: (() -> Unit)? = null) {
        if (openPage != null) return
        val k = PremiumKit(a)
        val ui = SetupUi(a, k)
        val panel = SetupPanel(ui)
        val parts = SetupParts(a, k)
        val back = a.currentFocus
        val dp = { v: Int -> Ui.dp(a, v) }
        lateinit var root: OverlayRoot
        fun close() {
            val r = root
            openPage = null
            r.animate().alpha(0f).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT).withEndAction {
                panel.release()
                (r.parent as? android.view.ViewGroup)?.removeView(r)
            }.start()
            if (back != null && back.isAttachedToWindow) back.requestFocus()
            onClose?.invoke()
        }
        root = OverlayRoot(a) { if (!panel.pop()) close() }
        root.isClickable = true
        SetupBrand.backdrop(root)
        val row = android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(dp(PremiumKit.EDGE), dp(44), dp(56), dp(36))
        }
        val left = android.widget.LinearLayout(a).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val wrap = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        val match = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        left.addView(SetupArt(a, SetupArt.Kind.UNLOCK), android.widget.LinearLayout.LayoutParams(dp(132), dp(132)))
        left.addView(k.label("SETTINGS", 12f, Ui.alpha(android.graphics.Color.WHITE, 0.62f), k.semibold, Ui.TRACK_CAPS),
            android.widget.LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(18) })
        left.addView(k.label("Unlock Features", 32f, SetupBrand.TEXT, k.semibold, Ui.TRACK_TITLE, lines = 2),
            android.widget.LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(8) })
        left.addView(parts.note("Let Dream OS fully take over your Fire TV: the Home button, startup, voice search and more. A one-time step.", 16f)
            .apply { setPadding(0, 0, 0, 0) }, android.widget.LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) })
        row.addView(left, android.widget.LinearLayout.LayoutParams(0, wrap, 0.36f))
        row.addView(panel, android.widget.LinearLayout.LayoutParams(0, match, 0.64f).apply { marginStart = dp(56) })
        root.addView(row, android.widget.FrameLayout.LayoutParams(match, match, android.view.Gravity.TOP))
        Ui.unclip(row)
        (a.window.decorView as android.widget.FrameLayout).addView(root, match, match)
        openPage = root
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        Log.w("FireHome", "Unlock: Settings page opened (${value(a)})")
        panel.push(UnlockPages(a, ui, panel, onChange = {}).overview(), animate = false)
    }
}

/**
 * The Unlock screens for a [SetupPanel], shared by setup and Settings: why it's needed, what it turns
 * on, the steps, then the result per feature and "Turn Off ADB Debugging".
 */
internal class UnlockPages(
    private val ctx: Context,
    private val ui: SetupUi,
    private val panel: SetupPanel,
    private val onChange: () -> Unit,
    /** After ADB Debugging was turned off here (setup moves focus on to Continue). */
    private val afterTurnOff: (() -> Unit)? = null,
) {
    private val k = ui.k
    private val parts = SetupParts(ctx, k)
    /** ADB was turned off from this page: its row stays, showing that, so focus never falls off the page. */
    private var turnedOff = false
    private fun app() = ctx.applicationContext
    @Volatile private var running = false

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
            s.view("why", 0) {
                parts.note("Fire TV keeps a few settings locked to outside apps. Dream OS can unlock them itself. " +
                    "It takes about a minute, and nothing leaves your Fire TV.", 15f)
            }
            val done = s.view("done", 14) { parts.result(true, "Everything Is Unlocked") }
            done.visibility = if (all) View.VISIBLE else View.GONE
            val go = rows.row("go", 14) { parts.row(null, chevron = true) { start(s) } }
            go.set("Unlock Now", if (adb) "Dream OS connects to your Fire TV and asks you to allow it." else "Turn on ADB Debugging first.")
            val steps = rows.row("steps", 6) { parts.row(null, chevron = true) { panel.push(steps()) } }
            steps.set("How to Turn On ADB Debugging", "Four quick steps", if (adb) "On" else null,
                if (adb) SetupBrand.GREEN else null, if (adb) BrandRow.Mark.OK else BrandRow.Mark.NONE)
            go.visibility = if (all) View.GONE else View.VISIBLE
            steps.visibility = if (all) View.GONE else View.VISIBLE
            val off = rows.row("off", 10) { parts.row(null, chevron = true) { if (DreamUnlock.adbOn(app())) turnOff(s) } }
            if (adb) off.set("Turn Off ADB Debugging", "Recommended. Everything stays unlocked.")
            else off.set("ADB Debugging Is Off", "Everything stays unlocked.", null, SetupBrand.GREEN, BrandRow.Mark.OK)
            off.visibility = if (all && DreamUnlock.secureOn(app()) && (adb || turnedOff)) View.VISIBLE else View.GONE
            s.view("k:what", 20) { parts.section("What It Turns On", null) }
            s.view("grid", 10) { featureGrid(marks) }
            for ((i, f) in DreamUnlock.FEATURES.withIndex()) {
                val want = if (f.on(app())) BrandRow.Mark.OK else BrandRow.Mark.OFF
                marks.getOrNull(i)?.let { m -> if (m.tag != want) { m.tag = want; m.background = StatusGlyph(want) } }
            }
        }
        s.onShow = { paint(); onChange() }
        s.onResume = { paint(); onChange() }
        return s
    }

    /** Six features in two columns: a status mark, a short title and a one-line caption. Not focusable. */
    private fun featureGrid(marks: MutableList<View>): View {
        val dp = { v: Int -> Ui.dp(ctx, v) }
        val grid = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
        for (pair in DreamUnlock.FEATURES.chunked(2)) {
            val line = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
            for ((j, f) in pair.withIndex()) {
                val cell = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.TOP
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = Ui.rounded(ctx, Ui.alpha(android.graphics.Color.WHITE, 0.035f), Ui.R_MD)
                }
                val mark = View(ctx)
                marks += mark
                cell.addView(mark, android.widget.LinearLayout.LayoutParams(dp(20), dp(20)).apply { topMargin = dp(2); marginEnd = dp(12) })
                val texts = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
                texts.addView(k.label(f.title, 15.5f, SetupBrand.TEXT, k.medium, lines = 1))
                texts.addView(k.label(f.caption, 12.5f, SetupBrand.DIM, k.font, lines = 2).apply { setPadding(0, dp(3), 0, 0) })
                cell.addView(texts, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                line.addView(cell, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    .apply { if (j > 0) marginStart = dp(10) })
            }
            grid.addView(line, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (grid.childCount > 0) topMargin = dp(10) })
        }
        return grid
    }

    /** The picture steps for turning on ADB Debugging. */
    private fun steps(): SetupScreen {
        val s = SetupScreen(ui, "Turn On ADB Debugging", null)
        val lines = listOf(
            "1" to "Open Settings › My Fire TV.",
            "2" to "If you don't see Developer Options, choose About, then press your Fire TV's name 7 times.",
            "3" to "Go back, open Developer Options, and turn on ADB Debugging.",
            "4" to "Come back to Dream OS and choose Unlock Now.",
        )
        s.onShow = {
            for ((i, l) in lines.withIndex()) {
                s.view("s$i", if (i == 0) 0 else 10) { parts.result(null, l.second).apply {
                    // The step number in the same round badge the other numbered steps use.
                    removeViewAt(0)
                    addView(SetupArt.monogram(ctx, l.first, 40), 0, android.widget.LinearLayout.LayoutParams(Ui.dp(ctx, 40), Ui.dp(ctx, 40)))
                } }
            }
            s.view("open", 20) {
                parts.pills(parts.pill("Open Fire TV Settings", true) { DreamUnlock.openFireTvSettings(ctx) })
            }
        }
        s.onResume = { if (DreamUnlock.adbOn(app())) panel.pop() }
        return s
    }

    private fun start(from: SetupScreen) {
        if (running) return
        if (!DreamUnlock.adbOn(app())) { panel.push(steps()); return }
        val s = SetupScreen(ui, "Unlocking", null)
        val line = k.label("Connecting to your Fire TV…", 20f, SetupBrand.TEXT, k.semibold, lines = 3)
        s.onShow = { s.view("line", 0) { line } }
        panel.push(s)
        running = true
        Thread {
            val r = DreamUnlock.unlock(app()) { msg -> ui.main.post { line.text = msg } }
            ui.main.post {
                running = false
                when (r) {
                    is DreamUnlock.Result.Done -> { panel.pop(); onChange() }
                    is DreamUnlock.Result.Failed -> {
                        line.text = r.message
                        s.view("retry", 20) { parts.pills(parts.pill("Try Again", true) { panel.pop(); start(from) }) }
                    }
                }
            }
        }.start()
    }

    private fun turnOff(s: SetupScreen) {
        val ok = DreamUnlock.turnOffAdb(app())
        if (ok) turnedOff = true
        s.onResume()
        if (!ok) Log.w("FireHome", "Unlock: couldn't turn ADB off") else afterTurnOff?.invoke()
    }
}
