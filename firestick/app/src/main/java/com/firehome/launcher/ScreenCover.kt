package com.firehome.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import java.lang.ref.WeakReference

/**
 * The one screen cover: an accessibility overlay showing exactly what the user is looking at (Dream
 * OS's window drawn into a picture), put up while something else (Amazon's search results) comes and
 * goes underneath, and lifted on Dream OS's next real frame, never on a timer. A [Live] part keeps
 * moving on top of the picture. Fire OS 7 refuses PixelCopy of the window (ERROR_UNKNOWN on a Fire TV
 * Stick HD), so the window draws itself: ~350 ms there, every pixel as on screen.
 *
 * One owner at a time ([show]'s owner, e.g. "alexa" for
 * the voice-search hand-off): a second show keeps the same cover and takes it over; a hide from an
 * owner that no longer has it is ignored, so the two never pull each other's cover away.
 */
object ScreenCover {
    private const val TAG = "FireHome"
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var picture: ImageView? = null
    private var owner: String? = null
    /** Bumped by every hide: a cover still waiting for its picture doesn't go up after it. */
    private var gen = 0

    /**
     * A small view the cover keeps drawing over its picture: [view] at ([x], [y]) on screen, [w] x [h],
     * standing in for [stand], the page's own copy, which is left out of the picture.
     */
    class Live(val view: View, val x: Int, val y: Int, val w: Int, val h: Int, val stand: View?)

    /** The Dream OS screen in front right now (MainActivity or Dream Player), and the last one of each. */
    private var resumed: WeakReference<Activity>? = null
    private var lastHome: WeakReference<Activity>? = null
    /** One reused picture buffer (RGB_565: half the memory; a cover is up for a moment only). */
    private var shot: Bitmap? = null
    @Volatile private var shotOk = false

    val isUp get() = view != null
    fun ownedBy(o: String) = view != null && owner == o

    /**
     * MainActivity reports in from onResume / onPause, so a cover can picture what's on screen and lift on
     * its next real frame; without it a cover can only show the bare background (black).
     */
    fun screenResumed(a: Activity) {
        resumed = WeakReference(a)
        lastHome = WeakReference(a)
        onResumed?.let { onResumed = null; it() }
    }

    /** A lift waiting for Home to be back in front ([hideOnFrame]). */
    private var onResumed: (() -> Unit)? = null

    fun screenPaused(a: Activity) {
        if (resumed?.get() === a) resumed = null
    }

    fun resumedScreen(): Activity? = resumed?.get()

    /**
     * Home while its window is still on screen, resumed or not: Amazon's results can start (pausing Home)
     * before Alexa's window is seen to go, and Home is still what the viewer sees until they draw.
     */
    fun homeScreen(): Activity? =
        lastHome?.get()?.takeIf { !it.isFinishing && !it.isDestroyed && it.window?.decorView?.isShown == true }

    /** [a]'s window drawn into the cover's picture, without [leaveOut]; false if it couldn't be. */
    private fun capture(a: Activity, leaveOut: View?): Boolean {
        if (a.isFinishing || a.isDestroyed) return false
        if (view != null) return false // the picture is on screen in the cover: never drawn over
        val dv = a.window?.decorView
        if (dv == null || dv.width <= 0 || dv.height <= 0) return false
        val b = shot?.takeIf { it.width == dv.width && it.height == dv.height && !it.isRecycled }
            ?: try { Bitmap.createBitmap(dv.width, dv.height, Bitmap.Config.RGB_565) } catch (e: Throwable) { null }
            ?: return false
        shot = b
        val t0 = android.os.SystemClock.uptimeMillis()
        val was = leaveOut?.alpha ?: 1f
        shotOk = try {
            // Within this one message: the window itself never draws the page without it.
            leaveOut?.alpha = 0f
            b.eraseColor(Color.BLACK)
            dv.draw(Canvas(b))
            !blank(b)
        } catch (e: Throwable) {
            Log.w(TAG, "Screen cover: drawing failed: $e")
            false
        } finally {
            leaveOut?.alpha = was
        }
        Log.w(TAG, "Screen cover: picture drawn in ${android.os.SystemClock.uptimeMillis() - t0} ms${if (shotOk) "" else ", blank"}")
        return shotOk
    }

    /** No light anywhere on a grid across the picture: no Dream OS screen looks like that. */
    private fun blank(b: Bitmap): Boolean {
        for (j in 1 until 14) for (i in 1 until 24) {
            val c = b.getPixel(b.width * i / 24, b.height * j / 14)
            if (Color.red(c) + Color.green(c) + Color.blue(c) > 48) return false
        }
        return true
    }

    /**
     * Puts the cover up for [who]: a fresh picture of [source] when it's on screen (as it is now, changes
     * just made included), else the last picture (unless [useShot] is false), else [fallback] (null: the
     * theme's background, as Dream OS draws it), with [live] drawn on top. [then] runs once the cover has
     * drawn, so whatever comes next happens under it.
     */
    /** A picture drawn ahead ([prepare]) and still current: a cover can go up with it within a frame. */
    private var prepared = false
    val hasPrepared get() = prepared && shotOk

    /**
     * Draws [a]'s window into the picture now, ahead of the cover that will show it, through [dress] (which
     * sets the page as it will look, calls the draw, and puts the page back, all in this one message).
     */
    fun prepare(a: Activity, dress: ((View?) -> Unit) -> Unit) {
        if (view != null) return
        prepared = false
        dress { leaveOut -> prepared = capture(a, leaveOut) }
    }

    /** The page moved on (a key): the picture drawn ahead no longer matches it. */
    fun dropPrepared() { prepared = false }

    /**
     * [usePrepared]: the picture drawn ahead goes up at once, as if fresh. Otherwise as below.
     */
    fun show(who: String, source: Activity?, fallback: Int?, useShot: Boolean = true, live: Live? = null,
             usePrepared: Boolean = false, then: () -> Unit) {
        if (view != null) { owner = who; then(); return }
        val s = HomeRedirectService.current() ?: run { then(); return }
        val g = gen
        fun add(fresh: Boolean) {
            if (view != null) { owner = who; then(); return }
            if (g != gen) { then(); return }
            val img = ImageView(s).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                val b = shot?.takeIf { shotOk && !it.isRecycled && (useShot || fresh) }
                Log.w(TAG, "Screen cover up for $who: ${if (b != null) (if (fresh) "picture of the screen" else "earlier picture") else "background"}")
                devSave(b)
                when {
                    b != null -> setImageBitmap(b)
                    fallback != null -> setBackgroundColor(fallback)
                    else -> background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                        Theme.get(s, Theme.BG_TOP) or 0xFF000000.toInt(), Theme.get(s, Theme.BG_BOTTOM) or 0xFF000000.toInt()))
                }
            }
            val v = FrameLayout(s).apply {
                addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                // Only over the picture it belongs to (a fresh one): anywhere else it would float over nothing.
                if (fresh && live != null) addView(live.view, FrameLayout.LayoutParams(live.w, live.h).apply { leftMargin = live.x; topMargin = live.y })
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE,
            ).apply { windowAnimations = 0 }
            try {
                (s.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
                view = v
                picture = img
                owner = who
            } catch (e: Exception) {
                Log.w(TAG, "Screen cover: no overlay: $e")
                releaseShot()
                then()
                return
            }
            afterFrame(v) { then() }
        }
        val shown = source?.window?.decorView?.isShown == true
        if (usePrepared && hasPrepared) { prepared = false; add(true) } else add(shown && capture(source!!, live?.stand))
    }

    /** Developer (PingReceiver --es voice test): the next cover's picture is written to the app's external cache. */
    @Volatile var devSaveNext = false

    private fun devSave(b: Bitmap?) {
        if (!devSaveNext) return
        devSaveNext = false
        val s = HomeRedirectService.current() ?: return
        val copy = b?.copy(Bitmap.Config.ARGB_8888, false) ?: return
        Thread {
            try {
                java.io.File(s.externalCacheDir, "cover.png").outputStream().use { copy.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Log.w(TAG, "Screen cover: picture saved (${copy.width}x${copy.height})")
            } catch (e: Exception) { Log.w(TAG, "Screen cover: picture not saved: $e") }
        }.start()
    }

    /** [block] once [v] has drawn a frame that reached the screen (a draw, then the next vsync). */
    fun afterFrame(v: View, block: () -> Unit) {
        var ran = false
        val once = { if (!ran) { ran = true; block() } }
        val l = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                v.post {
                    try { v.viewTreeObserver.removeOnDrawListener(this) } catch (e: Exception) {}
                    Choreographer.getInstance().postFrameCallback { once() }
                }
            }
        }
        v.viewTreeObserver.addOnDrawListener(l)
        v.invalidate()
        main.postDelayed({ once() }, 600) // a view that never draws mustn't hold things up
    }

    /**
     * Fades [who]'s cover once Home is back in front and has drawn a frame after that, over [fadeMs]. Gives
     * up after [maxMs] (Dream OS didn't come back) so nothing is left up.
     */
    fun hideOnFrame(who: String, fadeMs: Long = Ui.OVERLAY_IN, maxMs: Long = 1500) {
        var done = false
        val lift = { why: String ->
            if (!done) { done = true; onResumed = null; Log.w(TAG, "Screen cover: Home is back ($why)"); hide(who, fadeMs) }
        }
        val drawn = {
            val dv = lastHome?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }?.window?.decorView
            if (dv == null) lift("no Home") else afterFrame(dv) { Choreographer.getInstance().postFrameCallback { lift("drawn") } }
        }
        // Drawn while still behind the app it's replacing would lift the cover onto that app.
        if (lastHome?.get()?.let { it === resumed?.get() } == true) drawn() else onResumed = drawn
        main.postDelayed({ lift("gave up") }, maxMs)
    }

    /**
     * Lets go of the last picture (about 8 MB at 1080p) when no cover shows it: after a voice request ends, when
     * Home is hidden and on low memory. The next cover draws a fresh one.
     */
    fun releaseShot() {
        if (view != null) return
        shot = null
        shotOk = false
    }

    /** Lifts the cover if [who] still owns it (null: whoever does). */
    fun hide(who: String?, fadeMs: Long = Ui.OVERLAY_IN) {
        if (who == null || owner == who || view == null) gen++
        val v = view ?: return
        if (who != null && owner != who) return
        Log.w(TAG, "Screen cover lifted ($owner, ${if (fadeMs > 0) "$fadeMs ms fade" else "at once"})")
        val img = picture
        view = null
        picture = null
        owner = null
        val remove = Runnable {
            try { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) {}
            img?.setImageDrawable(null)
        }
        if (fadeMs <= 0) { remove.run(); return }
        // The window fades, not its views: the window is opaque, so a view fade went through black (a dark
        // flash as the cover lifted); the window's own alpha blends it with the screen underneath.
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: run { remove.run(); return }
        val wm = v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
            duration = fadeMs
            interpolator = Ui.SETTLE
            addUpdateListener {
                lp.alpha = it.animatedValue as Float
                try { wm.updateViewLayout(v, lp) } catch (e: Exception) {}
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) = remove.run()
            })
            start()
        }
    }
}
