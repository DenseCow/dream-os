package com.firehome.launcher

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.TreeMap

/**
 * Netflix-style preview frames for scrubbing, made on the device: our streams are plain MKV / MP4 over
 * HTTPS with no trickplay track, so a [MediaMetadataRetriever] on the same URL and headers pulls the
 * keyframe nearest a time (OPTION_CLOSEST_SYNC: no decoding up to an exact frame), scaled small.
 *
 * Playback always wins: one worker thread at background priority, never more than one extraction at a
 * time, and the sparse background grid (one frame a minute, nearest the playhead first) only runs while
 * [healthy] says the player's buffer is comfortable. A scrub's own request ([want]) jumps the queue.
 * Frames are RGB_565, ~115 KB each, at most [MAX] kept (the farthest from the playhead go first).
 *
 * OFF ([ENABLED]) on the Fire TV Stick: measured 2026-10-25 on a Breaking Bad 1080p MKV, the retriever
 * opens in ~0.45 s but its first frame takes 25–31 s (it reads through the file to index it, competing
 * with playback for the network) and each later keyframe ~2.0–2.2 s; worse, every frame comes back with
 * scrambled rows (getScaledFrameAtTime and getFrameAtTime alike: the platform's frame conversion on Fire
 * OS 7). The next step is our own keyframe decoder (MediaExtractor + a software MediaCodec, YUV sampled
 * down by hand); the overlay's card, cache and scheduling here are ready for it.
 */
class PlayerThumbs(private val url: String, private val headers: Map<String, String>, private val durationMs: Long) {
    private val main = Handler(Looper.getMainLooper())
    private val frames = TreeMap<Long, Bitmap>() // guarded by itself
    /** The player's buffer is comfortable (set from the main thread as playback reports progress). */
    @Volatile var healthy = false
    /** Where the playhead is, so the grid starts there. */
    @Volatile var around = 0L
    /** A frame landed (main thread): its time and image. */
    var onFrame: ((Long, Bitmap) -> Unit)? = null
    @Volatile private var wanted = -1L
    @Volatile private var closed = false
    @Volatile private var failed = false
    private val lock = Object()
    private var thread: Thread? = null
    /** Extraction times (ms), for the log: is this device fast enough? */
    private var count = 0
    private var totalMs = 0L

    fun start() {
        if (thread != null) return
        thread = Thread({ loop() }, "DreamThumbs").apply { isDaemon = true; start() }
    }

    fun stop() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
        main.removeCallbacksAndMessages(null)
        onFrame = null
    }

    /** The closest frame already made to [ms] (within [reach]), or null. */
    fun nearest(ms: Long, reach: Long = 90_000L): Pair<Long, Bitmap>? = synchronized(frames) {
        val lo = frames.floorEntry(ms)
        val hi = frames.ceilingEntry(ms)
        val best = listOfNotNull(lo, hi).minByOrNull { kotlin.math.abs(it.key - ms) } ?: return null
        if (kotlin.math.abs(best.key - ms) > reach) null else best.key to best.value
    }

    /** The scrub target moved: make its frame next (unless one within a few seconds is already here). */
    fun want(ms: Long) {
        if (failed || closed) return
        val have = nearest(ms, NEAR)
        if (have != null) return
        wanted = ms
        synchronized(lock) { lock.notifyAll() }
    }

    /** Pokes the worker (the buffer turned healthy). */
    fun wake() = synchronized(lock) { lock.notifyAll() }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_LESS_FAVORABLE)
        val mmr = MediaMetadataRetriever()
        try {
            val t0 = SystemClock.elapsedRealtime()
            mmr.setDataSource(url, headers)
            Log.w(TAG, "thumbs: opened in ${SystemClock.elapsedRealtime() - t0} ms")
            while (!closed) {
                val target = next()
                if (target == null) { synchronized(lock) { if (!closed) lock.wait(2_000) }; continue }
                val t = SystemClock.elapsedRealtime()
                // The full frame, scaled here: the stick's getScaledFrameAtTime returns scrambled rows for these
                // streams. The big frame's pixels live in native memory and are freed at once (recycle).
                val bmp = try {
                    mmr.getFrameAtTime(target * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                        if (count == 0) Log.w(TAG, "thumbs: source frame ${full.width}x${full.height} ${full.config}")
                        Bitmap.createScaledBitmap(full, W, H, true).also { if (it !== full) full.recycle() }
                    }
                } catch (e: Throwable) { null }
                val took = SystemClock.elapsedRealtime() - t
                if (bmp == null) {
                    // Can't read frames from this stream: stop asking (no cost for the rest of the session).
                    if (count == 0) { failed = true; Log.w(TAG, "thumbs: no frame at ${target / 1000}s after $took ms; off for this stream"); return }
                    synchronized(frames) { frames[target] = EMPTY }
                    continue
                }
                val small = if (bmp.config == Bitmap.Config.RGB_565) bmp else bmp.copy(Bitmap.Config.RGB_565, false).also { bmp.recycle() }
                count++; totalMs += took
                if (count <= 3 || count % 20 == 0) Log.w(TAG, "thumbs: frame at ${target / 1000}s in $took ms (avg ${totalMs / count} ms over $count)")
                store(target, small)
                main.post { if (!closed) onFrame?.invoke(target, small) }
            }
        } catch (e: Throwable) {
            failed = true
            Log.w(TAG, "thumbs: off ($e)")
        } finally {
            try { mmr.release() } catch (e: Throwable) {}
        }
    }

    /**
     * Nothing while the buffer is low (a scrub's request waits too: the picture comes first). Then a scrub's
     * request; else the grid point nearest the playhead still missing.
     */
    private fun next(): Long? {
        if (!healthy || durationMs <= 0) return null
        val w = wanted
        if (w >= 0) { wanted = -1L; if (nearest(w, NEAR) == null) return w }
        val here = around
        synchronized(frames) {
            if (frames.size >= MAX) return null
            var best: Long? = null
            var g = 0L
            while (g < durationMs - 5_000) {
                val have = frames.floorKey(g + GRID / 2 - 1)?.takeIf { it > g - GRID / 2 }
                if (have == null && (best == null || kotlin.math.abs(g - here) < kotlin.math.abs(best - here))) best = g
                g += GRID
            }
            return best?.coerceAtLeast(1_000L)
        }
    }

    private fun store(ms: Long, b: Bitmap) = synchronized(frames) {
        frames[ms] = b
        while (frames.size > MAX) {
            val here = around
            val far = if (kotlin.math.abs(frames.firstKey() - here) > kotlin.math.abs(frames.lastKey() - here)) frames.firstKey() else frames.lastKey()
            frames.remove(far)
        }
    }

    companion object {
        private const val TAG = "DreamPlayer"
        /** See the class note: this device returns scrambled frames, so the preview card stays text-only. */
        const val ENABLED = false
        /** Frame size (px): a 16:9 card, sharp enough at its on-screen size. */
        const val W = 320
        const val H = 180
        private const val GRID = 60_000L
        /** A frame this close to a scrub target is close enough (keyframes are seconds apart anyway). */
        private const val NEAR = 6_000L
        private const val MAX = 100
        /** Marks a time whose frame couldn't be read, so the grid doesn't ask again. */
        private val EMPTY: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
    }
}
