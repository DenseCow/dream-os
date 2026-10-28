package com.firehome.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView

/**
 * Plays a short chime (and shows an optional message) over whatever is on screen, e.g. so the
 * developer can signal "update ready" from the PC:
 *   adb shell am broadcast -a com.firehome.launcher.PING -n com.firehome.launcher/.PingReceiver --es msg "Hi"
 * Only adb (which holds DUMP) may send it; see the manifest.
 */
class PingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // One-time data carry-over across the signing-key switch (tools/migrate-signing.sh):
        // ... PingReceiver --es migrate export|import --es key <64 hex digits>   (then: adb logcat -s FireHome | grep Migrate)
        intent.getStringExtra("migrate")?.let { what ->
            val app = context.applicationContext
            val key = intent.getStringExtra("key")
            val pending = goAsync()
            Thread {
                try { if (what == "export") DataMigration.export(app, key) else if (what == "import") DataMigration.import(app, key) }
                finally { pending.finish() }
            }.start()
            return
        }
        // Time zone / clock format for the developer: ... PingReceiver --es time_zone America/Los_Angeles|auto [--es clock 12|24|auto]
        // (then: adb logcat -s FireHome | grep "Time:").
        if (intent.hasExtra("time_zone") || intent.hasExtra("clock")) {
            TimeSettings.devSet(context.applicationContext, intent.getStringExtra("time_zone"), intent.getStringExtra("clock"))
            return
        }
        // Silent memory report for the developer:  ... PingReceiver --ez mem true  (then: adb logcat -s FireHomeMem)
        if (intent.getBooleanExtra("mem", false)) { memoryReport(); return }
        // Scraper sources for the developer (Scrapers): ... PingReceiver --es scrapers scrape --ei movie 603
        // (or --ei show 1399 --ei season 1 --ei episode 2): a fresh Play lookup through the installed scrapers, timed;
        // also: status | warm | test | survey (same ids) | add --es url <address> | update | remove --es id <repo id>
        // | enable --es id <provider id> --ez on true|false
        // Then: adb logcat -s FireHome | grep -E "Scrapers|Dream Core"
        intent.getStringExtra("scrapers")?.let { what ->
            val app = context.applicationContext
            val movie = intent.getIntExtra("movie", 0)
            val show = intent.getIntExtra("show", 0)
            val req = when {
                movie > 0 -> PlaybackRequest(title = intent.getStringExtra("title") ?: "", type = "movie", tmdbId = movie)
                show > 0 -> PlaybackRequest(title = intent.getStringExtra("title") ?: "", type = "episode", tmdbId = show,
                    season = intent.getIntExtra("season", 1), episode = intent.getIntExtra("episode", 1))
                else -> null
            }
            val url = intent.getStringExtra("url"); val id = intent.getStringExtra("id"); val on = intent.getBooleanExtra("on", true)
            // Its own thread, no goAsync: a scrape or an install can outlast a receiver's time limit (the launcher stays up).
            Thread({
                try { NativeResolver.dev(app, what, req, url, id, on) } catch (e: Exception) { android.util.Log.w("FireHome", "Scrapers: dev hook failed: $e") }
            }, "scrapers-dev").start()
            return
        }
        // "Are You Still Watching?" preview for the developer: ... PingReceiver --ez still true (Dream Player must be playing).
        if (intent.getBooleanExtra("still", false)) {
            Handler(Looper.getMainLooper()).post { DreamPlayerActivity.onScreen?.get()?.debugStillWatching() }
            return
        }
        // Play-speed check for the developer: ... PingReceiver --ei play 27205 [--ei season 2 --ei episode 1] [--el startMs 1800000] --es title "Inception"
        // (then: adb logcat -s FireHome | grep -E "Play flow|Dream Core:|Fast start|first frame"). Pressing Play, minus the remote.
        intent.getIntExtra("play", 0).takeIf { it > 0 }?.let { id ->
            val s = intent.getIntExtra("season", 0)
            val ep = s > 0
            PlayFlow.play(context.applicationContext, PlaybackRequest(
                title = intent.getStringExtra("title") ?: "Test", type = if (ep) "episode" else "movie", tmdbId = id,
                season = if (ep) s else null, episode = if (ep) intent.getIntExtra("episode", 1) else null,
                startMs = intent.getLongExtra("startMs", 0L).coerceAtLeast(0L),
            ))
            return
        }
        // Held-key check for the developer (adb's `input keyevent` only taps): ... PingReceiver --ei hold 22 [--ei holdMs 4500]
        // (22 = DPAD_RIGHT, 21 = DPAD_LEFT; then: adb logcat -s FireHome | grep "Player: hold"). Dream Player must be on screen.
        intent.getIntExtra("hold", 0).takeIf { it > 0 }?.let { code -> holdKey(code, intent.getIntExtra("holdMs", 4500).coerceIn(100, 30_000)); return }
        // Seasonal hubs for the developer (see Seasons): ... PingReceiver --es season christmas | --es seasonDate 2026-12-24
        // | --ez seasonReset true | --ez seasonCheck true   (then: adb logcat -s FireHome | grep Season)
        val season = intent.getStringExtra("season")
        val seasonDate = intent.getStringExtra("seasonDate")
        val seasonReset = intent.getBooleanExtra("seasonReset", false)
        if (season != null || seasonDate != null || seasonReset) {
            Seasons.override(context.applicationContext, force = season, date = seasonDate, reset = seasonReset)
            return
        }
        if (intent.getBooleanExtra("seasonCheck", false)) { Seasons.selfCheck(windows = true); return }
        // Self-updater for the developer (DreamUpdate.dev): ... PingReceiver --es update check [--es repo owner/name | build]
        // | prompt | install | reset   (then: adb logcat -s FireHome | grep Update)
        intent.getStringExtra("update")?.let { what -> DreamUpdate.dev(context.applicationContext, what, intent.getStringExtra("repo")); return }
        // Notification preview for the developer: ... PingReceiver --es notice demo   (or clear-demo; check: runs the premium check now)
        intent.getStringExtra("notice")?.let { what ->
            val app = context.applicationContext
            when (what) {
                "demo" -> DreamNotices.demo(app)
                "clear-demo" -> DreamNotices.clearDemo(app)
                "check" -> Net.run { DebridExpiry.checkBlocking(app) }
            }
            return
        }
        // Availability checker for the developer: ... PingReceiver --es avail force --ei movie 12345 --es status cam   (available | cam | none;
        // an episode: --ei show 1399 --ei season 3 --ei episode 7), --es avail dump, --es avail clear. Then: adb logcat -s FireHome | grep Avail
        intent.getStringExtra("avail")?.let { what ->
            val app = context.applicationContext
            when (what) {
                "force" -> {
                    val movie = intent.getIntExtra("movie", 0)
                    val show = intent.getIntExtra("show", 0)
                    val key = if (movie > 0) "movie:$movie" else if (show > 0) "tv:$show:${intent.getIntExtra("season", 1)}:${intent.getIntExtra("episode", 1)}" else null
                    val st = when (intent.getStringExtra("status")?.lowercase()) {
                        "available" -> Availability.Status.AVAILABLE; "cam" -> Availability.Status.CAM; "none" -> Availability.Status.NONE; else -> null
                    }
                    if (key != null && st != null) Availability.force(app, key, st, intent.getStringExtra("best")) // optional: --es best "2160p WEB-DL HDR"
                }
                "dump" -> Availability.dump(app)
                "clear" -> Availability.clear(app)
            }
            return
        }
        // New Episode banner test for the developer: ... PingReceiver --es newep fake [--ei show 1399 --ei season 3 --ei episode 7]
        // (a test episode, aired today, on Home's banner plus its notice; reset: forgets it and every played / dismissed mark).
        // Then: adb logcat -s FireHome | grep NewEp
        intent.getStringExtra("newep")?.let { what ->
            val app = context.applicationContext
            when (what) {
                "fake" -> NewEpisodeSpot.fake(app, intent.getIntExtra("show", 0), intent.getIntExtra("season", 0), intent.getIntExtra("episode", 0))
                "reset" -> NewEpisodeSpot.reset(app)
            }
            return
        }
        chime(context)
        intent.getStringExtra("msg")?.let { banner(it) }
    }

    /**
     * Dispatches to the Dream Player on screen what the framework delivers for [code] held [holdMs]: ACTION_DOWN,
     * then from the long-press timeout on a repeat every key-repeat delay (~50 ms) with a growing repeatCount, the
     * first carrying FLAG_LONG_PRESS as KeyEvent.dispatch sets it, then ACTION_UP. The same downTime throughout.
     * Developer only (adb holds DUMP); nothing in normal use comes here.
     */
    private fun holdKey(code: Int, holdMs: Int) {
        val main = Handler(Looper.getMainLooper())
        main.post {
            val a = DreamPlayerActivity.onScreen?.get()
            if (a == null) { android.util.Log.w("FireHome", "Player: hold hook: Dream Player isn't on screen"); return@post }
            val downTime = SystemClock.uptimeMillis()
            val longPress = ViewConfiguration.getLongPressTimeout().toLong()
            val every = ViewConfiguration.getKeyRepeatDelay().toLong().coerceAtLeast(10L)
            fun event(action: Int, at: Long, repeat: Int, flags: Int) =
                KeyEvent(downTime, downTime + at, action, code, repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD)
            android.util.Log.w("FireHome", "Player: hold hook: key $code held $holdMs ms (repeats from $longPress ms, every $every ms)")
            a.dispatchKeyEvent(event(KeyEvent.ACTION_DOWN, 0, 0, 0))
            var repeat = 0
            var at = longPress
            while (at < holdMs) {
                repeat++
                val r = repeat; val t = at
                main.postDelayed({
                    if (DreamPlayerActivity.onScreen?.get() === a) a.dispatchKeyEvent(event(KeyEvent.ACTION_DOWN, t, r, if (r == 1) KeyEvent.FLAG_LONG_PRESS else 0))
                }, t)
                at += every
            }
            val repeats = repeat
            main.postDelayed({
                if (DreamPlayerActivity.onScreen?.get() !== a) return@postDelayed
                a.dispatchKeyEvent(event(KeyEvent.ACTION_UP, holdMs.toLong(), 0, 0))
                android.util.Log.w("FireHome", "Player: hold hook: released after $holdMs ms, $repeats repeats")
            }, holdMs.toLong())
        }
    }

    private fun memoryReport() {
        val tag = "FireHomeMem"
        android.util.Log.w(tag, "native alloc ${"%.1f".format(android.os.Debug.getNativeHeapAllocatedSize() / 1048576f)} MB, " +
            "java ${"%.1f".format((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576f)} MB")
        android.util.Log.w(tag, "images: ${Net.memoryStats()}")
        android.util.Log.w(tag, "app art: ${Apps.artStats()}")
    }

    private fun chime(ctx: Context) {
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            ctx.resources.openRawResourceFd(R.raw.welcome_chime).use { p.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            p.setOnCompletionListener { it.release(); if (player === it) player = null }
            p.prepare()
            p.start()
            // Held until it finishes: an unreferenced MediaPlayer can be collected mid-sound.
            player?.release()
            player = p
        } catch (e: Exception) {
            p.release()
        }
    }

    /** Pill at the top of the screen, drawn by the accessibility service so it shows over any app. */
    private fun banner(text: String) {
        val service = HomeRedirectService.current() ?: return
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = service.resources.displayMetrics.density
        val view = TextView(service).apply {
            this.text = text
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding((26 * d).toInt(), (14 * d).toInt(), (26 * d).toInt(), (14 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 40 * d; setColor(Color.argb(235, 16, 16, 18)); setStroke((1 * d).toInt().coerceAtLeast(1), Color.argb(40, 255, 255, 255)) }
            alpha = 0f
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (40 * d).toInt()
        }
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            return
        }
        view.animate().alpha(1f).setDuration(300).start()
        Handler(Looper.getMainLooper()).postDelayed({
            view.animate().alpha(0f).setDuration(400).withEndAction {
                try { wm.removeView(view) } catch (e: Exception) {}
            }.start()
        }, 6000)
    }

    companion object {
        private var player: MediaPlayer? = null
    }
}
