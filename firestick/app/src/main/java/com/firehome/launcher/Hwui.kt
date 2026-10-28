package com.firehome.launcher

import android.util.Log
import java.lang.reflect.Method

/**
 * Asks HWUI to drop its GPU caches, exactly what ActivityThread.handleTrimMemory does for a normal
 * background app. Dream OS never gets that callback: the system binds FireVoiceService, so the process
 * stays at oom_adj 100 and its ~70 MB of purgeable Skia cache would otherwise sit there while another app plays.
 * Hidden API, so it's resolved by reflection with fallbacks and fails soft (logs once, then does nothing).
 */
object Hwui {
    private const val TAG = "FireHome"

    private class Target(val method: Method, val receiver: Any?)

    private val target: Target? by lazy { resolve() }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var stillOk: (() -> Boolean)? = null
    private val stillTrim: Runnable = Runnable {
        // Never mid-glide (a long synopsis or caption auto-scrolling): try again once it's still.
        if (AutoScroll.busy) { main.postDelayed(stillRetry, 3_000L); return@Runnable }
        val ok = stillOk
        stillOk = null
        if (ok?.invoke() == true) trim(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
    }

    /**
     * Something just moved (a key, a resume). Once the screen has been still for [STILL_MS] and [ok]
     * agrees, Skia's leftover scratch surfaces (the offscreen buffers of finished fades, page moves and
     * sheets: tens of MB on a 1080p screen) are purged. UI_HIDDEN keeps the GL context, every window
     * and the art textures; the next fade simply allocates its buffer again. Main thread only.
     */
    private val stillRetry = Runnable { stillTrim.run() }

    fun trimWhenStill(ok: () -> Boolean) {
        stillOk = ok
        main.removeCallbacks(stillTrim)
        main.removeCallbacks(stillRetry)
        main.postDelayed(stillTrim, STILL_MS)
    }

    private const val STILL_MS = 8_000L

    /** [level] is a ComponentCallbacks2.TRIM_MEMORY_* value. Main thread only. */
    fun trim(level: Int) {
        val t = target ?: return
        try {
            t.method.invoke(t.receiver, level)
        } catch (e: Throwable) {
            Log.w(TAG, "HWUI trim($level) failed: $e")
        }
    }

    private fun resolve(): Target? {
        // 1) WindowManagerGlobal.getInstance().trimMemory(level): destroys every window's hardware
        //    resources, then ThreadedRenderer.trimMemory (COMPLETE also frees the GL context).
        try {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val instance = method(wmg, "getInstance")?.invoke(null)
            val trim = method(wmg, "trimMemory", Int::class.javaPrimitiveType!!)
            if (instance != null && trim != null) {
                Log.w(TAG, "HWUI trim: using WindowManagerGlobal.trimMemory")
                return Target(trim, instance)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "HWUI trim: WindowManagerGlobal unavailable: $e")
        }
        // 2) ThreadedRenderer.trimMemory(level) (static): frees the caches without touching the windows.
        try {
            val tr = Class.forName("android.view.ThreadedRenderer")
            val trim = method(tr, "trimMemory", Int::class.javaPrimitiveType!!)
            if (trim != null) {
                Log.w(TAG, "HWUI trim: using ThreadedRenderer.trimMemory")
                return Target(trim, null)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "HWUI trim: ThreadedRenderer unavailable: $e")
        }
        Log.w(TAG, "HWUI trim: not available on this ROM")
        return null
    }

    /** Plain reflection first; if the hidden-API check blocks it, the same lookup through
     *  Class.getDeclaredMethod itself (the caller is then java.lang.Class, which Android 9/10 exempt). */
    private fun method(cls: Class<*>, name: String, vararg params: Class<*>): Method? {
        try {
            return cls.getDeclaredMethod(name, *params).apply { isAccessible = true }
        } catch (e: Throwable) {
            // fall through to the meta-reflection form
        }
        return try {
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java,
            )
            (getDeclaredMethod.invoke(cls, name, arrayOf(*params)) as Method).apply { isAccessible = true }
        } catch (e: Throwable) {
            null
        }
    }
}
