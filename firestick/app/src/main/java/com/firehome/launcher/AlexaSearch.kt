package com.firehome.launcher

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Voice search with the remote's mic button, through Alexa, seamless.
 *
 * The button belongs to Alexa (vizzini): it records the remote and does the speech-to-text; Dream OS
 * never touches the mic. The hand-off, with the shared [ScreenCover] ("alexa"):
 *  1. Alexa's listening window appears (HomeRedirectService's window watch, at the press). If Dream
 *     OS's home screen is in front, Dream Search shows "Listening…" ([Screen.alexaListening]). Other
 *     apps (Dream Player, Prime…) are left alone.
 *  2. Alexa's window goes (it has heard): Dream Search is covered with a fresh snapshot of itself,
 *     invisible, before Amazon's results can draw.
 *  3. Amazon's results open (com.amazon.tv.launcher/.deeplink.activity.DirectiveSearchResultsActivityDI);
 *     from another app they're covered with the theme's background. The spoken query is read from the
 *     results' own search field (id/search_text) on the accessibility events of that screen (content
 *     changes are watched only for this moment), never from result titles.
 *  4. Dream Search gets the query while still hidden ([Screen.alexaQuery]), Back closes Amazon's
 *     results (they open in a task of their own), Dream OS's task comes forward with no animation if
 *     it isn't already, and the cover fades on Dream Search's next real frame: the field going from
 *     "Listening…" to the query and the results filling in are the only visible change.
 *  5. No results within [NO_RESULTS_MS] after Alexa's window goes (not a search: "what time is it"):
 *     the cover goes (invisibly) and the page is put back as it was ([Screen.alexaCancelled]). If
 *     Alexa shows an answer card meanwhile, or a key is pressed, the cover goes at once.
 */
object AlexaSearch {
    interface Screen {
        /** Alexa is listening: show Dream Search listening. [prevPkg]: what was in front before. */
        fun alexaListening(prevPkg: String?)
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
    private const val READ_MS = 1500L
    private const val NO_RESULTS_MS = 4000L
    private const val CLOSE_MS = 120L
    private const val CROSSFADE_MS = 120L

    var ui: Screen? = null

    private val main = Handler(Looper.getMainLooper())
    private var listening = false
    private var takingOver = false
    private var handing = false
    private var service: HomeRedirectService? = null
    private val cancel = Runnable { finishListening() }
    private val readTimeout = Runnable { service?.let { handOver(it, "") } }

    fun isResults(className: String?) = className?.contains("DirectiveSearchResults") == true

    /** From HomeRedirectService: Alexa's listening window appeared ([started]) or went away. */
    fun onAlexaListening(s: HomeRedirectService, started: Boolean, dreamInFront: Boolean) {
        if (takingOver) return
        if (started) {
            main.removeCallbacks(cancel)
            if (listening) { ScreenCover.hide(COVER, 0); return } // Alexa is back (an answer card): let it show
            if (!(dreamInFront || ui?.isFront() == true)) return
            listening = true
            ui?.alexaListening(s.packageName)
        } else if (listening) {
            // Alexa has heard: Dream Search covered with itself, before Amazon's results can draw.
            ScreenCover.show(COVER, ScreenCover.resumedScreen(), null) {}
            main.removeCallbacks(cancel)
            main.postDelayed(cancel, NO_RESULTS_MS)
        }
    }

    /** Amazon's results came up: keep showing Dream Search over them, read the query, hand over. */
    fun onResults(s: HomeRedirectService) {
        if (takingOver) return
        takingOver = true
        handing = false
        service = s
        main.removeCallbacks(cancel)
        // From another app there's no Dream Search on screen to show: the theme's background.
        if (!ScreenCover.ownedBy(COVER)) ScreenCover.show(COVER, null, null, useShot = listening) {}
        s.watchContent(true)
        main.postDelayed(readTimeout, READ_MS)
        tryRead(s)
    }

    /** Amazon's results screen changed (its search field may have its text now). */
    fun onContent(s: HomeRedirectService) {
        if (takingOver && !handing) tryRead(s)
    }

    /** A key while the cover is up but nothing is being taken over: the user is moving on. */
    fun onUserKey() {
        if (!takingOver && ScreenCover.ownedBy(COVER)) ScreenCover.hide(COVER, 0)
    }

    private fun tryRead(s: HomeRedirectService) {
        read(s)?.let { handOver(s, it) }
    }

    private fun handOver(s: HomeRedirectService, q: String) {
        if (handing) return
        handing = true
        main.removeCallbacks(readTimeout)
        s.watchContent(false)
        Log.w(DreamVoice.TAG, "Alexa search query: ${q.length} chars") // never the words themselves (bug reports carry logcat)
        listening = false
        ui?.alexaQuery(q) // the page has the query before it's seen again
        // Amazon's results open in a task of their own: Back ends them (left open they'd come back
        // later with an old query) and shows what was under them, all under the cover.
        s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        main.postDelayed({
            if (ui?.isFront() != true) bringDreamOs(s)
            ScreenCover.hideOnFrame(COVER, player = false, fadeMs = CROSSFADE_MS, maxMs = 2500)
            takingOver = false
            handing = false
        }, CLOSE_MS)
    }

    private fun finishListening() {
        if (!listening) return
        listening = false
        ScreenCover.hide(COVER, 0) // the same picture as the screen under it: invisible
        Log.w(DreamVoice.TAG, "Alexa finished without a search: back to where the user was")
        ui?.alexaCancelled()
    }

    /** The spoken query: the results screen's own search field, nothing else (never a result title). */
    private fun read(s: HomeRedirectService): String? {
        val root = s.rootInActiveWindow ?: return null
        if (root.packageName != LAUNCHER) return null
        val t = root.findAccessibilityNodeInfosByViewId(QUERY_ID).firstOrNull()?.text?.toString()?.trim()
        return t?.takeIf { it.isNotEmpty() }?.trim('“', '”', '"')?.take(QueryField.MAX_LEN)
    }

    /** Dream OS's task to the front with no animation (its own task when there is one). */
    private fun bringDreamOs(s: Context) {
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
                .putExtra(MainActivity.EXTRA_ALEXA, true))
        } catch (e: Exception) {
            Log.w(DreamVoice.TAG, "couldn't bring Dream OS forward: $e")
        }
    }
}
