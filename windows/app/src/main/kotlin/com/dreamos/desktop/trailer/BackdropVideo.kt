package com.dreamos.desktop.trailer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.dreamos.desktop.core.util.Log
import com.dreamos.player.engine.LibMpv
import com.dreamos.player.engine.MpvCore
import com.dreamos.player.engine.MpvRenderParam
import com.dreamos.player.engine.PlayerLog
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.lang.ref.Reference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One muted-or-soft trailer picture for a title page's hero: a libmpv of its own that decodes (GPU decode, copied
 * back) and draws each frame with mpv's software renderer straight into a Skia bitmap the page paints. No window, no
 * swap chain: the hero's scrims, title and buttons are ordinary Compose on top of it.
 *
 * Why not the player's own window: Dream Player puts mpv in a native window under a transparent one; a title page
 * is one Compose surface, and a native window can't sit under part of it. A trailer behind a hero is SDR and at most
 * the hero's size, so the CPU path is cheap here (measured in docs/port/trailers.md).
 *
 * Two bitmaps (front shown, back drawn into) at the hero's size, capped at [MAX_W] x [MAX_H]: 2 x 8.4 MB at 1080p.
 * mpv's buffer is capped at 96 MiB. [destroy] stops sound at once and lets go of the decoder on its own thread.
 *
 * The libmpv instance is Dream Player's [MpvCore]: every property read and write holds its lock, so the handle is never
 * freed under a call from the UI thread (position, duration, volume). The render context is used by the render thread
 * alone and freed only after that thread has ended.
 */
internal class BackdropVideo(
    /** True: no sound at all (no audio device opened, no audio decoded): "On, Muted". */
    muted: Boolean,
    /** A new frame is in [frame]; called on the render thread. */
    private val onFrame: (Long) -> Unit,
    /** The file opened, played to its end or failed; called on mpv's event thread. */
    private val onEvent: (Event) -> Unit,
) {
    enum class Event { STARTED, ENDED, FAILED }

    private val mpv = LibMpv.api
    private val core = MpvCore("trailer")
    private val alive = AtomicBoolean(true)
    private val wake = Semaphore(0)
    @Volatile private var wantW = 0
    @Volatile private var wantH = 0
    private val lock = Any()
    private var front: Buffer? = null
    private var back: Buffer? = null
    private var frames = 0L
    @Volatile private var probe: ((Sampler) -> Unit)? = null
    /** JNA holds callbacks weakly: this field keeps it alive until the render context is freed ([release]). */
    private val updateCb = LibMpv.UpdateCallback { wake.release() }
    private val rctx: Pointer = try { setUp(muted) } catch (t: Throwable) { core.destroy(); throw t }
    private val renderThread: Thread
    private val released = CountDownLatch(1)

    private class Buffer(val w: Int, val h: Int) {
        val bitmap = Bitmap().apply {
            check(allocPixels(ImageInfo(w, h, ColorType.RGB_888X, ColorAlphaType.OPAQUE), w * 4)) { "bitmap $w x $h" }
        }
        val pointer = Pointer(bitmap.peekPixels()!!.addr)
        val image: ImageBitmap = bitmap.asComposeImageBitmap()
        fun close() = bitmap.close()
    }

    /** Reads a few pixels of the frame just drawn (letterbox and black-frame checks); render thread only. */
    class Sampler internal constructor(private val p: Pointer, val w: Int, val h: Int) {
        /** Luma 0..255 of the pixel at [x], [y]. */
        fun luma(x: Int, y: Int): Int {
            val v = p.getInt((y.toLong() * w + x) * 4)
            val r = v and 255; val g = (v shr 8) and 255; val b = (v shr 16) and 255
            return (r * 299 + g * 587 + b * 114) / 1000
        }
    }

    init {
        mpv.mpv_render_context_set_update_callback(rctx, updateCb, null)
        core.start(::onCoreEvent)
        renderThread = Thread(::renderLoop, RENDER_THREAD).apply { isDaemon = true; start() }
    }

    /** Options, initialize, the software renderer. On a failure the caller frees the instance. */
    private fun setUp(muted: Boolean): Pointer {
        fun opt(k: String, v: String) = core.option(k, v)
        opt("vo", "libmpv")
        opt("hwdec", "auto-copy")
        opt("config", "no"); opt("terminal", "no"); opt("input-default-bindings", "no"); opt("osc", "no")
        opt("load-scripts", "no"); opt("ytdl", "no"); opt("idle", "yes"); opt("keep-open", "no")
        opt("sid", "no"); opt("sub-auto", "no"); opt("audio-display", "no")
        if (muted || SILENT) { opt("aid", "no"); opt("ao", "null") } else opt("audio-client-name", "Dream OS")
        opt("volume", "0")
        // Cover the hero like its backdrop picture (crop, never bars); a letterboxed trailer is zoomed further later.
        opt("panscan", "1.0")
        // Opened hidden during the page's rest it reads only a little ahead; [playing] lets it read on once it shows.
        opt("cache", "yes"); opt("demuxer-max-bytes", "96MiB"); opt("demuxer-readahead-secs", HIDDEN_READAHEAD)
        opt("network-timeout", "15")
        core.initialize()
        return createRenderer() ?: error("software renderer refused")
    }

    /** Draw at [w] x [h] pixels (the hero's size; capped, kept a multiple of 16 for mpv's fast path). */
    fun size(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val scale = minOf(1f, MAX_W.toFloat() / w, MAX_H.toFloat() / h)
        wantW = maxOf(16, (w * scale).toInt() / 16 * 16)
        wantH = maxOf(16, (h * scale).toInt())
        wake.release()
    }

    fun load(url: String) { if (alive.get()) core.command("loadfile", url, "replace") }

    fun setVolume(v: Int) = set("volume", v.coerceIn(0, 100).toString())

    /** Holds the picture (and sound) where it is, or lets it run on. */
    fun pause(on: Boolean) = set("pause", if (on) "yes" else "no")

    /** On screen now: read ahead the full minute, as a trailer playing should. */
    fun playing() = set("demuxer-readahead-secs", "60")

    /** Opened muted (no audio device): turns the sound path on now, still at volume 0. False under -Ddream.trailer.silent. */
    fun enableSound(): Boolean {
        if (SILENT || !alive.get()) return false
        set("volume", "0")
        set("ao", "wasapi,")
        set("audio-client-name", "Dream OS")
        set("aid", "auto")
        return true
    }

    /** Extra zoom past the cover crop (letterbox bars baked into the video). */
    fun setZoom(z: Float) = set("video-zoom", (Math.log(z.toDouble()) / Math.log(2.0)).toString())

    val positionMs: Long get() = ((double("time-pos") ?: 0.0) * 1000).toLong()
    val durationMs: Long get() = ((double("duration") ?: 0.0) * 1000).toLong()
    /** Seconds buffered ahead (dev checks). */
    val cacheAheadSec: Double get() = double("demuxer-cache-duration") ?: 0.0
    /** The picture's height as decoded (dev checks). */
    val videoHeight: Long get() = (double("height") ?: 0.0).toLong()

    /** Checks each new frame with [check] until it's set to null (render thread). */
    fun probeFrames(check: ((Sampler) -> Unit)?) { probe = check }

    /** The picture to paint (null before the first frame), handed to [paint] under the frame lock. */
    fun <T> withFrame(paint: (ImageBitmap?) -> T): T = synchronized(lock) { paint(front?.image) }

    /**
     * Mutes now and returns at once; the rest (stop, render context, libmpv, bitmaps) is let go on a thread of its own.
     * Safe to call twice, and from any thread, the frame and event callbacks included. When the release is still going
     * after [SLOW_MS], every thread's stack goes to the log.
     */
    fun destroy() {
        if (!alive.compareAndSet(true, false)) return
        // Queued, not waited for: the caller is usually the UI thread.
        core.commandAsync(0, "set", "mute", "yes")
        wake.release()
        val t0 = System.nanoTime()
        val releaser = Thread({ release(t0) }, RELEASE_THREAD).apply { isDaemon = true; start() }
        Thread({
            try { releaser.join(SLOW_MS) } catch (_: InterruptedException) { return@Thread }
            if (releaser.isAlive) Log.w(TAG, "still letting go of the trailer after $SLOW_MS ms; every thread:\n${PlayerLog.threadDump()}")
        }, "dream-trailer-release-watch").apply { isDaemon = true; start() }
    }

    /** For checks: true once [destroy]'s release has finished within [ms]. */
    internal fun awaitReleased(ms: Long): Boolean = released.await(ms, TimeUnit.MILLISECONDS)

    /**
     * Stop, then the render thread ends (it sees [alive] at its next turn; a render in progress returns once its frame
     * is due), then the render context is freed with nothing inside it, then [MpvCore.destroy] joins the event thread
     * and terminates under its lock. No step proceeds while a thread may still be in libmpv: a stuck one leaks the
     * instance (and the watchdog logs it) rather than freeing it under that thread.
     */
    private fun release(t0: Long) {
        try {
            core.command("stop")
            joinFully(renderThread)
            mpv.mpv_render_context_set_update_callback(rctx, null, null)
            mpv.mpv_render_context_free(rctx)
            Reference.reachabilityFence(updateCb)
            core.destroy()
            synchronized(lock) {
                front?.close(); back?.close()
                front = null; back = null
            }
            Log.d(TAG, "trailer let go in ${(System.nanoTime() - t0) / 1_000_000} ms")
        } catch (t: Throwable) {
            Log.w(TAG, "letting go of the trailer failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            released.countDown()
        }
    }

    // ---- threads ----

    private fun renderLoop() {
        val sizeMem = Memory(8)
        val strideMem = Memory(8)
        val swFormat = Memory(5).apply { setString(0, "rgb0") }
        try {
            while (alive.get()) {
                wake.tryAcquire(250, TimeUnit.MILLISECONDS)
                wake.drainPermits()
                if (!alive.get()) break
                val flags = mpv.mpv_render_context_update(rctx)
                if (flags and 1L == 0L) continue
                val w = wantW; val h = wantH
                if (w <= 0 || h <= 0) continue
                var b = back
                if (b == null || b.w != w || b.h != h) {
                    b?.close()
                    b = Buffer(w, h)
                    back = b
                }
                sizeMem.setInt(0, w); sizeMem.setInt(4, h)
                strideMem.setLong(0, w * 4L)
                val p = params(5)
                p[0].type = LibMpv.RENDER_PARAM_SW_SIZE; p[0].data = sizeMem
                p[1].type = LibMpv.RENDER_PARAM_SW_FORMAT; p[1].data = swFormat
                p[2].type = LibMpv.RENDER_PARAM_SW_STRIDE; p[2].data = strideMem
                p[3].type = LibMpv.RENDER_PARAM_SW_POINTER; p[3].data = b.pointer
                p[4].type = LibMpv.RENDER_PARAM_INVALID; p[4].data = null
                p.forEach { it.write() }
                // Blocks until the frame is due (mpv paces the picture); the drawing itself is under a millisecond at 1080p.
                if (mpv.mpv_render_context_render(rctx, p) < 0) continue
                probe?.invoke(Sampler(b.pointer, w, h))
                b.bitmap.notifyPixelsChanged()
                val n: Long
                synchronized(lock) {
                    // The old front becomes the back: nothing paints it once the swap is done (painting holds the lock).
                    back = front?.takeIf { it.w == w && it.h == h }.also { old -> if (old == null) front?.close() }
                    front = b
                    n = ++frames
                }
                onFrame(n)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "render stopped: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** mpv's event thread; STOP / QUIT / REDIRECT ends are ours, and nothing is reported once [destroy] has begun. */
    private fun onCoreEvent(e: MpvCore.Event) {
        if (!alive.get()) return
        when (e) {
            MpvCore.Event.PlaybackRestart -> onEvent(Event.STARTED)
            is MpvCore.Event.EndFile -> when (e.reason) {
                LibMpv.END_ERROR -> onEvent(Event.FAILED)
                LibMpv.END_EOF -> onEvent(Event.ENDED)
            }
            else -> {}
        }
    }

    // ---- mpv calls ----

    private fun set(k: String, v: String) { if (alive.get()) core.set(k, v) }
    private fun double(k: String): Double? = if (alive.get()) core.double(k) else null

    private fun params(n: Int) = MpvRenderParam().toArray(n).map { it as MpvRenderParam }.toTypedArray()

    private fun createRenderer(): Pointer? {
        val sw = Memory(3).apply { setString(0, "sw") }
        val p = params(2)
        p[0].type = LibMpv.RENDER_PARAM_API_TYPE; p[0].data = sw
        p[1].type = LibMpv.RENDER_PARAM_INVALID; p[1].data = null
        p.forEach { it.write() }
        val ref = PointerByReference()
        return if (mpv.mpv_render_context_create(ref, core.handle, p) >= 0) ref.value else null
    }

    /** Joins [t] even when this thread is interrupted meanwhile: freeing the render context under it would crash. */
    private fun joinFully(t: Thread) {
        if (t === Thread.currentThread()) return
        var interrupted = false
        while (t.isAlive) {
            try { t.join() } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    companion object {
        private const val TAG = "Trailer"
        const val MAX_W = 2560
        const val MAX_H = 1440
        /** Seconds read ahead while the trailer is opened hidden (a paused 1080p trailer holds a few MB, not a minute's). */
        private const val HIDDEN_READAHEAD = "8"
        /** A release still going after this long logs every thread's stack. */
        const val SLOW_MS = 3_000L
        const val RENDER_THREAD = "dream-trailer-render"
        const val RELEASE_THREAD = "dream-trailer-release"
        /** MpvCore names its event thread "mpv-" + its name. */
        const val EVENT_THREAD = "mpv-trailer"

        /** -Ddream.trailer.silent=true: never open an audio device (offscreen checks on the owner's PC). */
        val SILENT: Boolean = System.getProperty("dream.trailer.silent") == "true"

        /** True when libmpv can be loaded here (the hero simply keeps its still otherwise). */
        val available: Boolean by lazy { runCatching { LibMpv.api.mpv_client_api_version() > 0 }.getOrDefault(false) }
    }
}
