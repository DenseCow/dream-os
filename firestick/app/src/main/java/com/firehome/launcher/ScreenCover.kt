package com.firehome.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.ImageView
import java.lang.ref.WeakReference

/**
 * The one screen cover: an accessibility overlay showing exactly what the user is looking at (a
 * PixelCopy of Dream OS's window), put up while something else (Amazon's search
 * results) comes and goes underneath, and lifted on Dream OS's next real frame, never on a timer.
 *
 * One owner at a time ([show]'s owner, e.g. "alexa" for
 * the voice-search hand-off): a second show keeps the same cover and takes it over; a hide from an
 * owner that no longer has it is ignored, so the two never pull each other's cover away.
 */
object ScreenCover {
    private const val TAG = "FireHome"
    /** How long the screen sits still before the cover snapshot is re-taken. */
    private const val REFRESH_MS = 1_500L
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var owner: String? = null

    /** The Dream OS screen in front right now (MainActivity or Dream Player), and the last one of each. */
    private var resumed: WeakReference<Activity>? = null
    private var lastPlayer: WeakReference<Activity>? = null
    private var lastHome: WeakReference<Activity>? = null
    /** One reused snapshot buffer (RGB_565: half the memory; a cover is up for a moment only). */
    private var shot: Bitmap? = null
    @Volatile private var shotOk = false

    val isUp get() = view != null
    fun ownedBy(o: String) = view != null && owner == o

    /** Dream OS's screens report in (MainActivity, Dream Player), so a cover can show what's on screen. */
    fun screenResumed(a: Activity) {
        resumed = WeakReference(a)
        if (a is DreamPlayerActivity) lastPlayer = WeakReference(a) else lastHome = WeakReference(a)
        // The snapshot is kept: Home bounces off Amazon's launcher, and by the time Dream OS is paused its
        // window has no surface left to copy ("no backing surface"), so the cover must already hold a
        // recent picture. [refresh] keeps it current while Dream OS sits still in front.
        refresh(a)
    }

    private val refreshRun = Runnable { resumed?.get()?.let { a -> if (view == null) capture(a) {} } }

    /** Re-takes the snapshot once the screen has been still for a moment (after a key press or a resume). */
    fun refresh(a: Activity) {
        if (resumed?.get() !== a) return
        main.removeCallbacks(refreshRun)
        main.postDelayed(refreshRun, REFRESH_MS)
    }

    /** Still drawn at onPause: kept as the cover in case something else then comes to the front. */
    fun screenPaused(a: Activity) {
        if (resumed?.get() === a) resumed = null
        capture(a) {}
    }

    fun resumedScreen(): Activity? = resumed?.get()

    /** A fresh snapshot of [a] (on screen now) for the next cover; [done] says whether it worked. */
    fun capture(a: Activity, done: (Boolean) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 26 || a.isFinishing || a.isDestroyed) { done(false); return }
        val dv = a.window?.decorView
        if (dv == null || dv.width <= 0 || dv.height <= 0) { done(false); return }
        val b = shot?.takeIf { it.width == dv.width && it.height == dv.height && !it.isRecycled }
            ?: try { Bitmap.createBitmap(dv.width, dv.height, Bitmap.Config.RGB_565) } catch (e: Throwable) { null }
            ?: run { done(false); return }
        shot = b
        try {
            PixelCopy.request(a.window, b, { r -> shotOk = r == PixelCopy.SUCCESS; done(shotOk) }, main)
        } catch (e: Exception) {
            Log.w(TAG, "Screen cover: snapshot failed: $e")
            done(false)
        }
    }

    /**
     * Puts the cover up for [who]: a fresh snapshot of [source] when it's on screen, else the last
     * snapshot (unless [useShot] is false), else [fallback] (null: the theme's background, as Dream OS
     * draws it). [then] runs once the cover has drawn, so whatever comes next happens under it.
     */
    fun show(who: String, source: Activity?, fallback: Int?, useShot: Boolean = true, then: () -> Unit) {
        if (view != null) { owner = who; then(); return }
        val s = HomeRedirectService.current() ?: run { then(); return }
        fun add(fresh: Boolean) {
            if (view != null) { owner = who; then(); return }
            val v = ImageView(s).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                val b = shot?.takeIf { shotOk && !it.isRecycled && (useShot || fresh) }
                when {
                    b != null -> setImageBitmap(b)
                    fallback != null -> setBackgroundColor(fallback)
                    else -> background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                        Theme.get(s, Theme.BG_TOP) or 0xFF000000.toInt(), Theme.get(s, Theme.BG_BOTTOM) or 0xFF000000.toInt()))
                }
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
                owner = who
            } catch (e: Exception) {
                Log.w(TAG, "Screen cover: no overlay: $e")
                then()
                return
            }
            afterFrame(v) { then() }
        }
        if (source != null && !source.isFinishing && source.window?.decorView?.isShown == true) capture(source) { ok -> add(ok) } else add(false)
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
     * Fades [who]'s cover once Dream OS (the player when [player], else Home) has drawn its next real
     * frame, over [fadeMs]. Gives up after [maxMs] (Dream OS didn't come back) so nothing is left up.
     */
    fun hideOnFrame(who: String, player: Boolean, fadeMs: Long = Ui.OVERLAY_IN, maxMs: Long = 1500) {
        val a = (if (player) lastPlayer else lastHome)?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
        val dv = a?.window?.decorView
        if (dv == null) { main.postDelayed({ hide(who, fadeMs) }, 400); return }
        var done = false
        val lift = { if (!done) { done = true; hide(who, fadeMs) } }
        afterFrame(dv) { Choreographer.getInstance().postFrameCallback { lift() } }
        main.postDelayed({ lift() }, maxMs)
    }

    /** Lifts the cover if [who] still owns it (null: whoever does). */
    fun hide(who: String?, fadeMs: Long = Ui.OVERLAY_IN) {
        val v = view ?: return
        if (who != null && owner != who) return
        view = null
        owner = null
        val remove = Runnable {
            try { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) {}
            (v as? ImageView)?.setImageDrawable(null)
        }
        if (fadeMs <= 0) remove.run()
        else v.animate().alpha(0f).setDuration(fadeMs).setInterpolator(Ui.SETTLE).withEndAction(remove).start()
    }
}
