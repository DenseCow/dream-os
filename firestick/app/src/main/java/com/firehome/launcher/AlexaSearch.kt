package com.firehome.launcher

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Voice search with the remote's mic button, through Alexa, seamless.
 *
 * The button belongs to Alexa (vizzini): it records the remote and does the speech-to-text; Dream OS
 * never touches the mic. The hand-off, with the shared [ScreenCover] ("alexa"):
 *  1. Alexa's listening window appears (HomeRedirectService's window watch, at the press). If Dream
 *     OS's home screen is in front, Dream Search shows "Listening…" ([Screen.alexaListening]). Other
 *     apps (Dream Player, Prime…) are left alone.
 *  2. Alexa's window goes (it has heard): Dream Search shows "Searching…" with its glow breathing
 *     ([Screen.alexaSearching]) and is covered with a picture of itself, drawn by Dream OS, plus a live
 *     twin of the glow, ~0.3 s later and well before Amazon's results draw (~0.9 s).
 *  3. Amazon's results open (com.amazon.tv.launcher/.deeplink.activity.DirectiveSearchResultsActivityDI);
 *     from another app they're covered with the theme's background. The spoken query is read from the
 *     results' own search field (id/search_text), never from result titles, off the main thread: on
 *     that screen's content events and every [POLL_MS] besides, so it's taken the moment Amazon fills it.
 *  4. Dream Search gets the query while still hidden ([Screen.alexaQuery]), Back closes Amazon's
 *     results (they open in a task of their own), Dream OS's task comes forward with no animation if
 *     it isn't already, and the cover fades on Dream Search's next real frame: "Searching…" becoming
 *     the words and the results filling in are the only visible change.
 *  5. No results within [NO_RESULTS_MS] after Alexa's window goes (not a search: "what time is it"):
 *     the cover goes and the page is put back as it was ([Screen.alexaCancelled]). If Alexa shows an
 *     answer card meanwhile, that happens at once, under the card; a key press lifts the cover at once.
 */
object AlexaSearch {
    interface Screen {
        /** Alexa is listening: show Dream Search listening. [prevPkg]: what was in front before. */
        fun alexaListening(prevPkg: String?)
        /** Alexa has heard: Dream Search shows it's searching; returns the part of the page that keeps moving. */
        fun alexaSearching(): ScreenCover.Live?
        /** Runs the draw with Dream Search as it will look once Alexa has heard, without showing it. */
        fun drawAsSearching(draw: (android.view.View?) -> Unit)
        /** Alexa heard a search: Dream Search with [q] (may be empty if it couldn't be read). */
        fun alexaQuery(q: String)
        /** Alexa finished without a search: undo the listening state, back to where the user was. */
        fun alexaCancelled()
        /** Dream OS's home screen is the one in front (resumed). */
        fun isFront(): Boolean
    }

    private const val COVER = "alexa"
    private const val LAUNCHER = "com.amazon.tv.launcher"
    private const val QUERY_ID = "$LAUNCHER:id/search_text"
    /** Amazon fills its field once its results have loaded (~0.9 s on a Fire TV Stick HD): room for a slow network. */
    private const val READ_MS = 2500L
    private const val POLL_MS = 100L
    private const val NO_RESULTS_MS = 4000L
    private const val CLOSE_MS = 120L
    private const val CROSSFADE_MS = 120L

    var ui: Screen? = null

    private val main = Handler(Looper.getMainLooper())
    /** Reads Amazon's screen: each read is a call into Amazon's busy process, never made from the main thread. */
    private val reader by lazy { Handler(HandlerThread("alexa-read").apply { start() }.looper) }
    @Volatile private var reading = false
    private var listening = false
    private var takingOver = false
    private var handing = false
    /** When Dream OS last had a request of its own going (an answer card can end the listening before results come). */
    private var askedAt = 0L
    private var service: HomeRedirectService? = null
    private val cancel = Runnable { finishListening() }
    private val readTimeout = Runnable { service?.let { handOver(it, devQuery ?: "") } }
    private val poll = object : Runnable {
        override fun run() {
            val s = service ?: return
            if (!takingOver || handing) return
            tryRead(s)
            main.postDelayed(this, POLL_MS)
        }
    }

    fun isResults(className: String?) = className?.contains("DirectiveSearchResults") == true

    /** From HomeRedirectService: Alexa's listening window appeared ([started]) or went away. */
    fun onAlexaListening(s: HomeRedirectService, started: Boolean, dreamInFront: Boolean) {
        if (takingOver) return
        if (started) {
            if (listening) {
                // Alexa is back with an answer card ("what time is it"): not a search. The page goes back
                // as it was under the card.
                Log.w(DreamVoice.TAG, "Alexa answered without a search")
                finishListening()
                return
            }
            main.removeCallbacks(cancel)
            if (!(dreamInFront || ui?.isFront() == true)) return
            listening = true
            askedAt = SystemClock.uptimeMillis()
            ui?.alexaListening(s.packageName)
        } else if (listening) {
            heard()
            main.removeCallbacks(cancel)
            main.postDelayed(cancel, NO_RESULTS_MS)
        }
    }

    /**
     * Alexa has heard: Dream Search says so, then covers itself with that, before Amazon's results can draw.
     * The picture is drawn now (exact, ~0.3 s: Amazon draws ~0.9 s after Alexa's window goes). When Amazon
     * is already starting ([late]) the one drawn ahead while Alexa listened ([prepareCover]) goes up at once.
     */
    private fun heard(late: Boolean = false) {
        if (ScreenCover.ownedBy(COVER)) return
        val ahead = late && ScreenCover.hasPrepared
        Log.w(DreamVoice.TAG, "Alexa has heard: Searching…${if (late) " (results already starting${if (ahead) ", picture drawn ahead" else ""})" else ""}")
        askedAt = SystemClock.uptimeMillis()
        val live = ui?.alexaSearching()
        ScreenCover.show(COVER, ScreenCover.homeScreen(), null, live = live, usePrepared = ahead) {}
    }

    /** Dream Search is up and listening: its "Searching…" picture is drawn now, while nothing else happens. */
    fun prepareCover() {
        if (!listening || takingOver || ScreenCover.isUp) return
        val a = ScreenCover.homeScreen() ?: return
        ui?.let { u -> ScreenCover.prepare(a) { draw -> u.drawAsSearching(draw) } }
    }

    /**
     * Home is being paused while Alexa listens: Amazon's results are starting, sometimes before Alexa's
     * window is seen to go. The cover goes up now, before they draw.
     */
    fun onHomePaused() {
        if (!listening || takingOver) return
        heard(late = true)
        main.removeCallbacks(cancel)
        main.postDelayed(cancel, NO_RESULTS_MS)
    }

    /** Amazon's results came up: keep showing Dream Search over them, read the query, hand over. */
    fun onResults(s: HomeRedirectService) {
        if (takingOver) return
        takingOver = true
        handing = false
        service = s
        resultsAt = SystemClock.uptimeMillis()
        main.removeCallbacks(cancel)
        // Results can open before Alexa's window is seen to go: Dream Search's "Searching…" all the same.
        if (listening) heard(late = true)
        // From another app there's no Dream Search on screen to show: the theme's background.
        val fromDream = listening || SystemClock.uptimeMillis() - askedAt < 15_000
        if (!ScreenCover.ownedBy(COVER)) ScreenCover.show(COVER, null, null, useShot = fromDream) {}
        s.watchContent(true)
        main.postDelayed(readTimeout, READ_MS)
        main.removeCallbacks(poll)
        poll.run()
    }

    /** Amazon's results screen changed (its search field may have its text now). */
    fun onContent(s: HomeRedirectService) {
        if (takingOver && !handing) tryRead(s)
    }

    /** A key while the cover is up but nothing is being taken over: the user is moving on. */
    fun onUserKey() {
        ScreenCover.dropPrepared()
        if (!takingOver && ScreenCover.ownedBy(COVER)) ScreenCover.hide(COVER, 0)
    }

    private fun tryRead(s: HomeRedirectService) {
        if (reading) return
        reading = true
        reader.post {
            val q = try { read(s) } catch (e: Exception) { null }
            main.post {
                reading = false
                if (q != null && takingOver && !handing) handOver(s, q)
            }
        }
    }

    private fun handOver(s: HomeRedirectService, q: String) {
        if (handing) return
        handing = true
        devQuery = null
        main.removeCallbacks(readTimeout)
        main.removeCallbacks(poll)
        s.watchContent(false)
        Log.w(DreamVoice.TAG, "Alexa search query: ${q.length} chars, ${SystemClock.uptimeMillis() - resultsAt} ms after results opened") // never the words themselves (bug reports carry logcat)
        listening = false
        askedAt = 0L
        val told = ui?.also { it.alexaQuery(q) } != null // the page has the query before it's seen again
        // Amazon's results open in a task of their own: Back ends them (left open they'd come back
        // later with an old query) and shows what was under them, all under the cover.
        s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        main.postDelayed({
            // No Home to tell (Dream OS wasn't running): the query goes with the start, or it would be lost.
            if (ui?.isFront() != true) bringDreamOs(s, if (told) null else q)
            ScreenCover.hideOnFrame(COVER, fadeMs = CROSSFADE_MS, maxMs = 2500)
            takingOver = false
            handing = false
        }, CLOSE_MS)
    }

    private fun finishListening() {
        main.removeCallbacks(cancel)
        if (!listening) return
        listening = false
        devQuery = null // a developer run without results: the next real search reads Amazon's field
        ScreenCover.devSaveNext = false
        ScreenCover.hide(COVER, 0)
        ScreenCover.releaseShot() // a key press may have lifted the cover already, keeping its picture
        Log.w(DreamVoice.TAG, "Alexa finished without a search: back to where the user was")
        ui?.alexaCancelled()
    }

    /**
     * Developer (adb only, PingReceiver --es voice test): the whole hand-off without anyone speaking. Alexa
     * "listens" for [listenMs] and "goes"; Amazon's own title page deep link stands in for its results (only
     * Amazon may open those; this one is in the same launcher, opens in a task of its own the same way and
     * closes on Back), 590 ms later as Alexa opens them, and [q] for the field Amazon fills ~0.9 s after.
     * Without [results] nothing opens: a request that wasn't a search ("what time is it"). With [early] the
     * results open 155 ms before Alexa's window is seen to go, as a real search on the HD stick did.
     */
    @Volatile private var devQuery: String? = null
    @Volatile private var resultsAt = 0L

    fun devRun(q: String, listenMs: Long, results: Boolean, early: Boolean = false) {
        val s = HomeRedirectService.current() ?: run { Log.w(DreamVoice.TAG, "voice test: the service isn't running"); return }
        Log.w(DreamVoice.TAG, "voice test: listening for $listenMs ms")
        devQuery = q
        ScreenCover.devSaveNext = true
        onAlexaListening(s, true, ui?.isFront() == true)
        val open = {
            try {
                s.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("amzn://$LAUNCHER/detail"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                main.postDelayed({ onResults(s) }, if (early) 390 else 300)
            } catch (e: Exception) { Log.w(DreamVoice.TAG, "voice test: no stand-in screen: $e") }
        }
        if (results && early) main.postDelayed({ open(); main.postDelayed({ onAlexaListening(s, false, false) }, 155) }, listenMs)
        else main.postDelayed({
            onAlexaListening(s, false, false)
            if (results) main.postDelayed({ open() }, 590)
        }, listenMs)
    }

    /** The spoken query: the results screen's own search field, nothing else (never a result title). */
    private fun read(s: HomeRedirectService): String? {
        devQuery?.let { return if (SystemClock.uptimeMillis() - resultsAt >= 900) it else null }
        val root = s.rootInActiveWindow ?: return null
        if (root.packageName != LAUNCHER) return null
        val t = root.findAccessibilityNodeInfosByViewId(QUERY_ID).firstOrNull()?.text?.toString()?.trim()
        return t?.takeIf { it.isNotEmpty() }?.trim('“', '”', '"')?.take(QueryField.MAX_LEN)
    }

    /** Dream OS's task to the front with no animation (its own task when there is one). */
    private fun bringDreamOs(s: Context, query: String? = null) {
        try {
            val am = s.getSystemService(ActivityManager::class.java)
            val home = MainActivity::class.java.name
            @Suppress("DEPRECATION")
            val task = am?.appTasks?.firstOrNull { t -> t.taskInfo.baseActivity?.className == home || t.taskInfo.topActivity?.className == home }
            if (task != null) { task.moveToFront(); return }
        } catch (e: Exception) {}
        try {
            s.startActivity(Intent(s, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(MainActivity.EXTRA_ALEXA, true)
                .apply { if (query != null) putExtra(MainActivity.EXTRA_SEARCH_QUERY, query) })
        } catch (e: Exception) {
            Log.w(DreamVoice.TAG, "couldn't bring Dream OS forward: $e")
        }
    }
}
