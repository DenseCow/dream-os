package com.firehome.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Fire OS won't let a third-party app become the real home screen, and the remote's
 * Home key never reaches accessibility services. So this service watches for the
 * Amazon launcher coming to the front (Home press, boot, app exit) and bounces to
 * FireHome. Being the registered assistant (see AssistantSlot.kt) makes that instant.
 */
class HomeRedirectService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var idleTicks = 0

    /**
     * Keeps checking while the Amazon launcher might be on screen. Needed because a Home
     * press while Amazon's home is already in front fires no event (so rapid presses can
     * win the race against a single redirect), and because leaving Amazon's settings
     * (Back or Home) happens inside the same launcher window.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (amazonHome(this@HomeRedirectService)) return // Dream OS was closed: Fire TV's own home stays
            val root = rootInActiveWindow
            val onLauncher = root?.packageName == AMAZON_LAUNCHER
            if (amazonSettingsOpen) {
                val inGrace = SystemClock.uptimeMillis() - pausedAt < GRACE_MS
                if (onLauncher && !inGrace && !showsAmazonSettings(root!!)) {
                    amazonSettingsOpen = false
                    goHome()
                }
                handler.postDelayed(this, CHECK_MS)
                return
            }
            if (onLauncher) {
                idleTicks = 0
                goHome()
            } else if (++idleTicks >= IDLE_TICKS_TO_STOP) {
                return // something else has stayed in front; stop until the next launcher event
            }
            handler.postDelayed(this, CHECK_MS)
        }
    }

    private var forceStopDeadline = 0L
    private var forceStopLabel = ""

    /**
     * Normal apps can't force-stop others, so FireHome opens the app's Fire TV info page
     * and this presses its "Force stop" row, then returns home.
     */
    private val forceStopper = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() > forceStopDeadline) return // give up; page stays open
            val root = rootInActiveWindow
            // Only act once the page is showing the app we were asked to stop.
            val title = root?.takeIf { it.packageName == AMAZON_SETTINGS }
                ?.findAccessibilityNodeInfosByViewId("$AMAZON_SETTINGS:id/left_pane_title")
                ?.firstOrNull()?.text?.toString()
            if (root != null && title == forceStopLabel) {
                var target = root.findAccessibilityNodeInfosByText("Force stop")
                    .firstOrNull { it.text?.toString() == "Force stop" }
                while (target != null && !target.isClickable) target = target.parent
                if (target != null) {
                    if (target.isEnabled) target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    handler.postDelayed({ goHome() }, 500)
                    return
                }
            }
            handler.postDelayed(this, 250)
        }
    }

    private var sleepTimer: SleepTimer? = null
    @Volatile private var lastPkg: String? = null // last app to bring up a window

    /**
     * The earliest sign of the remote's mic button: Alexa's listening overlay (a com.amazon.vizzini
     * window) appears the moment it's pressed and goes when Alexa is done. Checked only on
     * TYPE_WINDOWS_CHANGED, by looking at the window list (no tree walks). Dream OS never records.
     */
    private var alexaWindowShown = false

    /**
     * The window scan asks every window's app for its root (a blocking call into that app). Off the main
     * thread: on a Home press it would otherwise wait on Amazon's launcher while it starts, holding up the redirect.
     */
    private val windowsHandler by lazy { Handler(android.os.HandlerThread("a11y-windows").apply { start() }.looper) }
    @Volatile private var windowsCheckQueued = false

    private fun onWindowsChanged() {
        if (windowsCheckQueued) return
        windowsCheckQueued = true
        windowsHandler.post {
            windowsCheckQueued = false
            val shown = try {
                windows.any { w -> w.root?.let { r -> (r.packageName == VIZZINI).also { r.recycle() } } == true }
            } catch (e: Exception) { false }
            handler.post { onAlexaWindow(shown) }
        }
    }

    private fun onAlexaWindow(shown: Boolean) {
        if (amazonHome(this)) return
        if (shown == alexaWindowShown) return
        alexaWindowShown = shown
        val dreamInFront = lastAppPkg == packageName && lastAppClass?.endsWith(".MainActivity") == true
        Log.w(DreamVoice.TAG, "Alexa window ${if (shown) "shown" else "gone"} (front: $lastAppPkg ${lastAppClass?.substringAfterLast('.')})")
        AlexaSearch.onAlexaListening(this, shown, dreamInFront)
    }

    /**
     * Content-change events for the moment Alexa's search results are read (AlexaSearch): on only
     * then, since they're frequent everywhere else.
     */
    fun watchContent(on: Boolean) {
        val info = serviceInfo ?: return
        val t = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        val want = if (on) info.eventTypes or t else info.eventTypes and t.inv()
        if (want == info.eventTypes) return
        info.eventTypes = want
        serviceInfo = info
    }

    /** The last real app screen in front (not Alexa's overlays, not Dream OS's own overlay windows). */
    @Volatile private var lastAppPkg: String? = null
    @Volatile private var lastAppClass: String? = null

    override fun onServiceConnected() {
        // Switched on (again): the user's earlier "off" no longer stands.
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_USER_OFF, false).apply()
        sleepTimer = SleepTimer(this)
        Log.w(TAG, "service connected")
        // Back after Dream OS was closed from Settings (Fire OS restarts this service in a new process): stay idle.
        if (amazonHome(this)) return
        if (rootInActiveWindow?.packageName == AMAZON_LAUNCHER) {
            goHome()
            startWatchdog()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) { onWindowsChanged(); return }
        // Only switched on while Alexa's search results are being read (watchContent).
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (event.packageName == AMAZON_LAUNCHER) AlexaSearch.onContent(this)
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        lastPkg = pkg
        val cls = event.className?.toString()
        val fromApp = lastAppPkg // the screen in front before this one (Home's cover shows Dream OS only if it was us)
        // Dream OS's own overlay windows (views, not activities) don't change which screen is in front.
        if (pkg != VIZZINI && (pkg != packageName || cls?.contains("Activity") == true)) {
            lastAppPkg = pkg
            lastAppClass = cls
        }
        // Dream OS closed from Settings: no redirect, no Alexa hand-off until it's opened again.
        if (amazonHome(this)) return

        if (pkg != AMAZON_LAUNCHER) {
            // Another app on screen means the user has left Amazon's settings for good.
            if (amazonSettingsOpen && pkg != packageName && pkg != AMAZON_SETTINGS &&
                SystemClock.uptimeMillis() - pausedAt > GRACE_MS
            ) {
                amazonSettingsOpen = false
            }
            return
        }
        // Alexa heard a search ("the office"): Dream Search takes it, with the spoken query (AlexaSearch.kt).
        if (AlexaSearch.isResults(event.className?.toString())) {
            AlexaSearch.onResults(this)
            return
        }
        // Alexa couldn't take the request and fell back to Amazon's text search: Dream Search instead.
        if (event.className?.toString()?.contains("TextSearch") == true) {
            Log.w(DreamVoice.TAG, "Amazon text search opened: Dream Search instead")
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                .putExtra(MainActivity.EXTRA_SEARCH_QUERY, ""))
            return
        }
        if (amazonSettingsOpen) {
            startWatchdog()
            return
        }
        // Only the main home screen; leave other launcher screens (e.g. Live TV guide) alone.
        if (event.className?.toString()?.contains("HomeActivity") == true) {
            // Already sent back a moment ago (from Dream OS's onPause, or this same Home's other event):
            // a second start would only pause and resume Dream OS once more on its way in.
            if (SystemClock.uptimeMillis() - lastGoHomeAt > DEDUPE_MS) goHome(fromApp == packageName)
            startWatchdog()
        }
    }

    /** Keys seen before any app: the mic and Continue Watching's OK note. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        // The mic button, seen before any app (never consumed: Fire OS routes it on regardless).
        if (DreamVoice.isVoiceKey(event.keyCode)) {
            Log.w(DreamVoice.TAG, "a11y voice key ${event.keyCode} action=${event.action} repeat=${event.repeatCount} fg=$lastPkg")
            if (event.action == KeyEvent.ACTION_UP) DreamVoice.onMicKey(false)
            else if (event.repeatCount == 0) DreamVoice.onMicKey(true)
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) handler.post { AlexaSearch.onUserKey() }
        // Continue Watching: note what's selected when OK starts something in a streaming app (posted, never blocks the key).
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) handler.post { AppWatch.onOkPressed(this) }
        return false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        sleepTimer?.cancel()
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(forceStopper)
        super.onDestroy()
    }

    /** Back to Dream OS. [fromDreamOs]: Home was pressed inside Dream OS, so it lands at the top of Home. */
    private fun goHome(fromDreamOs: Boolean = false) {
        lastGoHomeAt = SystemClock.uptimeMillis()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                .putExtra(MainActivity.EXTRA_HOME_TOP, fromDreamOs)
        )
    }

    private fun startWatchdog() {
        idleTicks = 0
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, CHECK_MS)
    }

    private fun focusedNode(): AccessibilityNodeInfo? =
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

    /** Some screens wait for the first key press before focusing anything; start from the first focusable view. */
    private fun firstFocusable(): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootInActiveWindow ?: return null)
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 300) {
            val n = queue.removeFirst()
            if (n.isFocusable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun showsAmazonSettings(root: AccessibilityNodeInfo): Boolean =
        root.findAccessibilityNodeInfosByViewId("$AMAZON_LAUNCHER:id/settings_card_view").isNotEmpty() ||
            root.findAccessibilityNodeInfosByViewId("$AMAZON_LAUNCHER:id/settings_search_input").isNotEmpty()

    companion object {
        private const val VIZZINI = "com.amazon.vizzini"
        const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"
        private const val AMAZON_SETTINGS = "com.amazon.tv.settings.v2"
        private const val ANDROID_SETTINGS = "com.android.settings"
        /** After an unbind the user didn't choose: long enough for Fire OS to finish handling a force-stop. */
        private const val REENABLE_MS = 1500L
        private const val TAG = "FireHome"
        private const val GRACE_MS = 2000L
        private const val CHECK_MS = 800L
        private const val IDLE_TICKS_TO_STOP = 4
        private const val DEDUPE_MS = 700L

        /** When Dream OS was last sent for (uptime). */
        @Volatile private var lastGoHomeAt = 0L

        /** Off the main thread: the usage-events lookup is a binder call into a busy system_server. */
        private val early: Handler by lazy {
            Handler(android.os.HandlerThread("home-bounce").apply { start() }.looper)
        }

        /**
         * Dream OS's screen [a] is being paused. If what's coming up instead is Amazon's launcher home
         * (the remote's Home button: Fire OS always starts it), Dream OS is sent for right away, the same
         * redirect the window event below makes ~250 ms later, after Amazon has drawn. That early it lands
         * before Dream OS is even stopped, so its window comes back as it was, with nothing to redraw.
         * Anything else coming up (an app Dream OS or Alexa opened, the player, Amazon's settings,
         * the screensaver) is left alone: only the exact case the redirect would bounce anyway.
         */
        fun bounceBackIfHome(a: android.app.Activity) {
            val s = instance ?: return
            if (amazonSettingsOpen || a.isFinishing || amazonHome(a)) return
            val usm = a.getSystemService(android.app.usage.UsageStatsManager::class.java) ?: return
            val me = a.componentName.className
            val start = SystemClock.uptimeMillis()
            early.post(object : Runnable {
                override fun run() {
                    val fg = try { lastForeground(usm) } catch (e: Exception) { null }
                    when {
                        // The screen taking over isn't reported yet: look again shortly (a short while only).
                        fg == null || fg.second == me ->
                            if (SystemClock.uptimeMillis() - start < 250) early.postDelayed(this, 10)
                        fg.first == AMAZON_LAUNCHER && fg.second.contains("HomeActivity") && !amazonSettingsOpen -> {
                            // From this thread: Dream OS's main thread is still busy pausing.
                            if (instance === s) { s.goHome(true); s.handler.post { s.startWatchdog() } }
                        }
                        // Anything else in front (an app, the player, Amazon's settings) is left alone.
                    }
                }
            })
        }

        /** The last screen reported in front (package, class), from the last few seconds of usage events. */
        private fun lastForeground(usm: android.app.usage.UsageStatsManager): Pair<String, String>? {
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 10_000, now + 1_000) ?: return null
            val e = android.app.usage.UsageEvents.Event()
            var last: Pair<String, String>? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) last = Pair(e.packageName ?: "", e.className ?: "")
            }
            return last
        }

        private var instance: HomeRedirectService? = null

        /** The running service, for drawing overlays over other apps (null if it isn't bound). */
        fun current(): HomeRedirectService? = instance

        /**
         * Force-stopping Dream OS makes Fire OS drop this service and our assistant slot (which skips
         * the ~5 s freeze after Home), handing the Home button back to Amazon. Puts both back
         * (needs WRITE_SECURE_SETTINGS, granted by deploy) - but never after the user turned the
         * service off themselves (Fire OS Settings > Accessibility; see [onUnbind]), unless [userAsked]
         * (the user pressed Turn On in Dream OS's own setup).
         */
        fun ensureEnabled(ctx: Context, userAsked: Boolean = false) {
            if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // Builds before 2026-10-26 also took a force-stop for the user's "off": that old mark is dropped once.
            if (!prefs.getBoolean(KEY_OFF_CHECKED, false)) prefs.edit().remove(KEY_USER_OFF).putBoolean(KEY_OFF_CHECKED, true).commit()
            if (userAsked) prefs.edit().putBoolean(KEY_USER_OFF, false).commit()
            else if (prefs.getBoolean(KEY_USER_OFF, false)) return
            val cr = ctx.contentResolver
            try {
                val voice = "${ctx.packageName}/.FireVoiceService"
                for (key in arrayOf("voice_interaction_service", "assistant")) {
                    if (Settings.Secure.getString(cr, key) != voice) Settings.Secure.putString(cr, key, voice)
                }
                if (instance != null) return
                val me = "${ctx.packageName}/${HomeRedirectService::class.java.name}"
                val list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                if (list.split(':').any { it.equals(me, ignoreCase = true) }) return
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (list.isBlank()) me else "$list:$me")
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                Log.i(TAG, "re-enabled Home redirect")
            } catch (e: SecurityException) {
            }
        }

        // Amazon's full settings menu lives inside its launcher, so the redirect must
        // stand down while it's open. Set by MainActivity, cleared when the user leaves.
        @Volatile var amazonSettingsOpen = false
            set(value) {
                field = value
                if (value) pausedAt = SystemClock.uptimeMillis()
            }
        private var pausedAt = 0L

        /** FireHome is about to open Amazon's settings menu: stand down until the user leaves it. */
        fun enterAmazonSettings() {
            amazonSettingsOpen = true
            instance?.startWatchdog()
        }

        fun forceStop(ctx: Context, pkg: String, label: String) {
            instance?.let {
                it.forceStopLabel = label
                it.forceStopDeadline = SystemClock.uptimeMillis() + 6000
                it.handler.removeCallbacks(it.forceStopper)
                it.handler.postDelayed(it.forceStopper, 400)
            }
            ctx.startActivity(
                // CLEAR_TASK: otherwise Fire TV may resurface a stale page for a different app.
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }


        /** Sleep timer in minutes (0 cancels). Lives in the service so it can prompt over any app. */
        fun setSleepTimer(minutes: Int) {
            instance?.sleepTimer?.set(minutes)
        }

        fun sleepMinutesLeft(): Int = instance?.sleepTimer?.minutesLeft() ?: 0

        /** Back in whatever app is showing (used by the phone remote). */
        fun globalBack() {
            instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }

        fun globalHome() {
            instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }

        /** Package of the app on screen, or null if the service isn't running. */
        fun foregroundPackage(): String? =
            instance?.let { it.rootInActiveWindow?.packageName?.toString() ?: it.lastPkg }

        /**
         * D-pad for other apps (phone remote): Fire OS blocks key injection, so move input focus the
         * way a key press would. direction is View.FOCUS_UP etc. Works for apps built on normal views.
         */
        fun moveFocus(direction: Int) {
            val s = instance ?: return
            val from = s.focusedNode()
            val next = if (from != null) from.focusSearch(direction) else s.firstFocusable()
            next?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }

        /** OK for other apps: clicks the focused view, or the nearest clickable parent. */
        fun clickFocused() {
            var n = instance?.focusedNode()
            while (n != null && !n.isClickable) n = n.parent
            n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        /** Fills the focused text field in another app; false if nothing editable has focus. */
        fun setFocusedText(text: String): Boolean {
            val n = instance?.focusedNode()?.takeIf { it.isEditable } ?: return false
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        /** Play/pause for whichever app owns the active media session. Doesn't need the service. */
        fun mediaPlayPause(ctx: Context) {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        }

        /** Called when FireHome is in front, which means the user isn't in Amazon's settings. */
        fun onFireHomeVisible(ctx: Context) {
            amazonSettingsOpen = false
            if (amazonHome(ctx)) setAmazonHome(ctx, false) // opened again: Dream OS is home once more
        }

        /**
         * Settings › Close Dream OS: Fire TV's own home until Dream OS is opened again. Kept on disk,
         * since closing ends Dream OS's process and Fire OS brings this service back in a new one.
         */
        @Volatile private var amazonHomeCache: Boolean? = null

        fun amazonHome(ctx: Context): Boolean = amazonHomeCache
            ?: ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AMAZON_HOME, false).also { amazonHomeCache = it }

        fun setAmazonHome(ctx: Context, on: Boolean) {
            amazonHomeCache = on
            // commit(), not apply(): the process may end right after.
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AMAZON_HOME, on).commit()
            if (on) instance?.let { it.handler.removeCallbacks(it.watchdog) }
        }

        private const val PREFS = "home_redirect"
        private const val KEY_AMAZON_HOME = "amazon_home"
        /** The user switched the service off in Fire OS Settings: Dream OS doesn't switch it back on by itself. */
        private const val KEY_USER_OFF = "user_turned_off"
        private const val KEY_OFF_CHECKED = "user_off_checked_v2"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        handler.removeCallbacks(watchdog)
        // A force-stop takes the service off the enabled list too (Fire OS's accessibility manager does that when
        // it handles the stop, sometimes seconds later, after a new Dream OS process has already put it back), so a
        // missing entry alone doesn't mean the user chose it. Only the user's own switch in Fire OS Settings counts:
        // the service is gone from the list while a Settings screen is in front. Anything else (a force-stop, a crash,
        // an update) is put back a moment later, as before.
        try {
            val me = "$packageName/${HomeRedirectService::class.java.name}"
            val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val listed = list.split(':').any { it.equals(me, ignoreCase = true) }
            val inSettings = lastAppPkg == AMAZON_SETTINGS || lastAppPkg == ANDROID_SETTINGS
            if (!listed && inSettings) {
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_USER_OFF, true).commit()
                Log.w(TAG, "Home redirect switched off in Settings: not re-enabling it")
            } else {
                Log.w(TAG, "Home redirect unbound by the system (listed=$listed, front=$lastAppPkg): re-enabling it")
                val app = applicationContext
                Handler(Looper.getMainLooper()).postDelayed({ ensureEnabled(app) }, REENABLE_MS)
            }
        } catch (e: Exception) {}
        return super.onUnbind(intent)
    }
}
