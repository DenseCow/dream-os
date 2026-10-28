package com.firehome.launcher

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * Dream Player has started a stream: the rest of Dream OS steps back so playback has the stick to itself.
 * On a 1 GB Fire TV Stick, Bluetooth audio (AirPods) is the first thing to stutter when memory runs short.
 *
 * - Background work waits while the player is in front ([DreamPlayerActivity.isActive]): Availability's
 *   checks and sweeps, speculative pre-resolves (PreResolve checks the same; the next episode's prefetch
 *   near the end still runs, from post-play), ratings and the Home row work.
 * - A few seconds after the first frame (once the start has settled), idle memory goes back: decoded images
 *   not on screen (Net's bitmap cache, the hero's baked art), the scraper engine's caches and idle threads
 *   (Python keeps its modules, so the next Play starts as fast), HWUI's scratch surfaces, then one GC.
 *   PSS before and after goes to the log ("Player: memory trimmed ...").
 *
 * Once per stream, one short background thread; nothing runs while playing after that.
 */
object PlaybackTrim {
    private const val TAG = "FireHome"
    /** After the first frame: the start (and the play's own scrape) has finished by then. */
    private const val DELAY_MS = 8_000L
    private val main = Handler(Looper.getMainLooper())
    private val run = Runnable { if (DreamPlayerActivity.visible) trim() }

    /** The first frame of a stream (main thread). */
    fun started(@Suppress("UNUSED_PARAMETER") ctx: Context) {
        main.removeCallbacks(run)
        main.postDelayed(run, DELAY_MS)
    }

    private fun trim() {
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                val before = Debug.getPss()
                main.post {
                    Net.evictMemory() // views keep the images they show; the rest reload from the disk cache
                    HeroArt.evict()
                    Hwui.trim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) // GL context and windows stay
                }
                ScraperEngine.trimNow()
                Thread.sleep(400)
                Runtime.getRuntime().gc()
                Thread.sleep(1_200)
                val after = Debug.getPss()
                Log.w(TAG, "Player: memory trimmed for playback, PSS ${before / 1024} MB -> ${after / 1024} MB")
            } catch (e: Throwable) {
                Log.w(TAG, "Player: memory trim failed (${e.javaClass.simpleName})")
            }
        }, "playback-trim").start()
    }
}
