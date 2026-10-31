package com.dreamos.player.thumbs

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.dreamos.player.engine.LibMpv
import com.dreamos.player.engine.MpvCore
import com.dreamos.player.engine.MpvRenderParam
import com.dreamos.player.engine.PlayerLog
import com.dreamos.player.session.PlayerSession
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

/**
 * Scrub previews from a second, small libmpv (iPhone ScrubThumbnails / MPVThumbnailer): it opens the same URL and
 * headers with no sound, no subtitles and no cache, seeks to keyframes only and draws each frame at 320x180 with
 * libmpv's software renderer. The spot under the pointer always goes first ("latest wins": an older wanted spot is
 * dropped, not queued); between those, a walk fills the timeline coarse to fine while the main picture has 12 s or
 * more buffered. HDR frames (PQ / HLG) are mapped to SDR here, since the software renderer doesn't tone map.
 *
 * Dense on a PC: a spot every 3 s (a 2 h film: 2,400 spots), the ones around where the viewer is first, then the
 * whole timeline coarse to fine. Frames are kept as JPEG (384x216 at quality 82, 10 to 20 KB each) and decoded when
 * shown, through a small cache of [DECODED] pictures, so [MAX_FRAMES] frames cost about 50 MB instead of 1 GB raw.
 * Worst case: 3,000 frames x 20 KB = 60 MB plus 32 decoded x 324 KB = 10 MB. Off for clips under 5 minutes and for
 * Dolby Vision profile 5 (no normal picture underneath: it would be green and purple).
 */
class ScrubThumbnails(private val session: PlayerSession) {
    /** spot -> JPEG bytes. */
    private val frames = TreeMap<Long, ByteArray>()
    /** The last [DECODED] frames shown, ready to draw (hovering back and forth decodes nothing twice). */
    private val decoded = object : LinkedHashMap<Long, ImageBitmap>(DECODED, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ImageBitmap>?) = size > DECODED
    }
    /** Bumped when a frame lands, so a composition reading [frameNear] redraws. */
    private val version = mutableIntStateOf(0)
    private val wanted = AtomicLong(-1)
    /** The next spot in the drag direction (iPhone ThumbDesk's "one ahead"), made right after the pointer's own. */
    private val ahead = AtomicLong(-1)
    /** The pointer's spot when it last changed, and when (for the "shown N ms after" log line). */
    @Volatile private var lastSpot = -1L
    @Volatile private var lastSpotAt = 0L
    private val running = AtomicBoolean(true)
    @Volatile private var openedFor: String? = null
    @Volatile private var spacing = 10_000L
    @Volatile private var durationMs = 0L
    /** The previews' own libmpv has opened the stream (the close-loop check closes right after this). */
    @Volatile internal var opened = false
        private set
    private val thread = Thread(::loop, "dream-thumbs").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }

    /** The nearest frame already made to [ms] (any distance; never blank once one exists). Also asks for [ms]. */
    fun frameNear(ms: Long): ImageBitmap? {
        version.intValue
        if (durationMs > 0) {
            val spot = snap(ms)
            wanted.set(spot)
            val prev = lastSpot
            if (spot != prev) {
                // Moving: the next spot that way goes right after this one (an older "ahead" is dropped, not queued).
                val dir = if (prev < 0) 0 else if (spot > prev) 1 else -1
                val a = spot + dir * spacing
                ahead.set(if (dir != 0 && a in 0..durationMs && !has(a)) a else -1)
                lastSpot = spot
                lastSpotAt = if (has(spot)) 0L else System.nanoTime()
            }
        }
        val (spot, jpeg) = synchronized(frames) {
            if (frames.isEmpty()) return null
            val lo = frames.floorEntry(ms); val hi = frames.ceilingEntry(ms)
            when {
                lo == null -> hi
                hi == null -> lo
                ms - lo.key <= hi.key - ms -> lo
                else -> hi
            }?.let { it.key to it.value } ?: return null
        }
        synchronized(decoded) { decoded[spot]?.let { return it } }
        val img = runCatching { org.jetbrains.skia.Image.makeFromEncoded(jpeg).toComposeImageBitmap() }.getOrNull() ?: return null
        synchronized(decoded) { decoded[spot] = img }
        return img
    }

    /** Stops the previews: the grab under way is dropped and their libmpv freed on their own thread ([awaitClosed]). */
    fun close() { running.set(false); thread.interrupt() }

    /** Waits up to [ms] for the previews' thread (and with it their libmpv) to be gone; true when it is. */
    fun awaitClosed(ms: Long): Boolean { thread.join(ms); return !thread.isAlive }

    private fun snap(ms: Long) = (ms / spacing) * spacing

    private fun loop() {
        try {
            while (running.get()) {
                val url = readyUrl()
                if (url == null) { Thread.sleep(1000); continue }
                runFor(url)
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) { PlayerLog.e("Thumbs: stopped", t) }
    }

    /** The current stream once it has played steadily ~5 s and is long enough; null to wait. */
    private fun readyUrl(): String? {
        val es = session.engineState
        if (!es.firstFrame || session.curtain != null || session.trailer) return null
        if (es.durationMs < 5 * 60_000L) return null
        if (es.facts.dvProfile == 5) return null
        if (es.positionMs < 0) return null
        val url = session.request.url
        if (url == openedFor) return null
        return url
    }

    private fun runFor(url: String) {
        openedFor = url
        opened = false
        synchronized(frames) { frames.clear() }
        synchronized(decoded) { decoded.clear() }
        durationMs = session.engineState.durationMs
        spacing = maxOf(SPACING_MS, durationMs / MAX_FRAMES)
        val hdr = session.engineState.facts.gamma.let { it == "pq" || it == "hlg" }
        val hlg = session.engineState.facts.gamma == "hlg"
        // Two seconds of steady playback, then the previews' own connection (the iPhone waits five).
        Thread.sleep(2000)
        if (!running.get()) return
        val core = MpvCore("thumbs")
        val api = LibMpv.api
        var rctx: Pointer? = null
        val restarted = AtomicBoolean(false)
        val frameReady = AtomicBoolean(false)
        val updateCb = LibMpv.UpdateCallback { frameReady.set(true) }
        try {
            with(core) {
                option("vo", "libmpv")
                option("hwdec", "d3d11va-copy,no")
                option("ao", "null"); option("aid", "no"); option("sid", "no")
                option("cache", "no"); option("demuxer-max-bytes", "2MiB"); option("demuxer-readahead-secs", "0")
                option("stream-buffer-size", "64KiB")
                option("hr-seek", "no"); option("pause", "yes"); option("keep-open", "always")
                option("vd-lavc-skiploopfilter", "all"); option("vd-lavc-threads", "4")
                option("vd-queue-enable", "no")
                option("terminal", "no"); option("ytdl", "no"); option("input-default-bindings", "no")
                option("user-agent", "DreamOS/${System.getProperty("dream.version") ?: "4"} DreamPlayer")
                initialize()
                observe("duration", LibMpv.FORMAT_DOUBLE)
                start { e -> if (e == MpvCore.Event.PlaybackRestart) restarted.set(true) }
            }
            rctx = createSw(api, core) ?: run { PlayerLog.w("Thumbs: software renderer refused"); return }
            api.mpv_render_context_set_update_callback(rctx, updateCb, null)
            val headers = session.request.headers.entries.joinToString(",") { (k, v) -> "$k: ${v.replace(",", "\\,")}" }
            core.set("http-header-fields", headers)
            core.command("loadfile", url)
            val t0 = System.currentTimeMillis()
            // First frame: wait for the file to open.
            if (!await(restarted, 15_000)) { PlayerLog.w("Thumbs: the stream didn't open for previews"); return }
            PlayerLog.i("Thumbs: open in ${System.currentTimeMillis() - t0} ms, ${durationMs / spacing} spots every ${spacing / 1000} s")
            opened = true
            val pixels = Memory(W.toLong() * H * 4)
            val walk = walkOrder(durationMs, spacing, session.engineState.positionMs).iterator()
            var fails = 0
            var made = 0
            var dragMade = 0
            var tGrab = 0L
            while (running.get() && session.request.url == url) {
                val want = wanted.getAndSet(-1)
                val next = if (want >= 0 && !has(want)) -1 else ahead.getAndSet(-1)
                val spot: Long = when {
                    want >= 0 && !has(want) -> want
                    next >= 0 && !has(next) -> next
                    canWalk() && walk.hasNext() -> walk.next().takeIf { !has(it) } ?: continue
                    else -> { Thread.sleep(40); continue }
                }
                val dragged = spot == want || spot == next
                val g0 = System.nanoTime()
                restarted.set(false); frameReady.set(false)
                core.command("seek", (spot / 1000.0).toString(), "absolute+keyframes")
                // The new picture is ready once the seek has restarted and libmpv asked for a redraw (the restart event
                // doesn't always come for a spot on the keyframe already shown, so it only shortens the wait).
                await(restarted, 3000)
                val gotFrame = await(frameReady, 2000)
                val jumpMs = (System.nanoTime() - g0) / 1_000_000
                if (!gotFrame) {
                    if (++fails >= 6) { PlayerLog.w("Thumbs: stopped (6 failed grabs in a row)"); return }
                    continue
                }
                fails = 0
                val d0 = System.nanoTime()
                api.mpv_render_context_update(rctx)
                val rr = render(api, rctx, pixels)
                if (rr < 0) continue
                val jpeg = toJpeg(pixels, hdr, hlg) ?: continue
                val drawMs = (System.nanoTime() - d0) / 1_000_000
                synchronized(frames) {
                    frames[spot] = jpeg
                    while (frames.size > MAX_FRAMES) frames.pollFirstEntry()
                }
                version.intValue++
                made++
                val totalMs = (System.nanoTime() - g0) / 1_000_000
                if (dragged) {
                    dragMade++
                    PlayerLog.i("Thumbs: drag frame at ${clock(spot)} in $totalMs ms (jump $jumpMs, draw $drawMs)${if (spot == next) " [ahead]" else ""}")
                }
                if (spot == lastSpot && lastSpotAt > 0) {
                    PlayerLog.i("Thumbs: dragged spot ${clock(spot)} shown ${(System.nanoTime() - lastSpotAt) / 1_000_000} ms after the pointer reached it")
                    lastSpotAt = 0
                }
                tGrab += totalMs
                if (made == 1) PlayerLog.i("Thumbs: first frame ${(System.nanoTime() - g0) / 1_000_000} ms after asking")
                if (made % 10 == 0) PlayerLog.i("Thumbs: $made frames ($dragMade asked for by the pointer), avg ${tGrab / made} ms each")
                if (spot != want) Thread.sleep(30)
            }
        } finally {
            // A close lands as an interrupt; cleared here so the teardown's own waits aren't cut short (a skipped
            // mpv_terminate_destroy would leave this libmpv and its connection running).
            Thread.interrupted()
            rctx?.let { api.mpv_render_context_set_update_callback(it, null, null); api.mpv_render_context_free(it) }
            // libmpv holds only a raw pointer to the callback: it must stay reachable until mpv can no longer call it.
            java.lang.ref.Reference.reachabilityFence(updateCb)
            core.destroy()
        }
    }

    private fun has(spot: Long) = synchronized(frames) { frames.containsKey(spot) }

    private fun clock(ms: Long) = com.dreamos.player.ui.clockText(ms)

    /** The walk runs only while the main picture is healthy: playing or paused with 12 s or more buffered ahead. */
    private fun canWalk(): Boolean {
        val es = session.engineState
        return !es.buffering && !es.seeking && es.bufferedToMs - es.positionMs >= 8_000
    }

    private fun await(flag: AtomicBoolean, ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (!flag.get()) {
            if (System.currentTimeMillis() > end || !running.get()) return false
            Thread.sleep(5)
        }
        return true
    }

    private fun params(n: Int) = MpvRenderParam().toArray(n).map { it as MpvRenderParam }.toTypedArray()

    private val swName = Memory(3).apply { setString(0, "sw") }
    private val fmtName = Memory(5).apply { setString(0, "bgr0") }
    private val sizeMem = Memory(8).apply { setInt(0, W); setInt(4, H) }
    private val strideMem = Memory(8).apply { setLong(0, W.toLong() * 4) }

    private fun createSw(api: LibMpv, core: MpvCore): Pointer? {
        val p = params(2)
        p[0].type = LibMpv.RENDER_PARAM_API_TYPE; p[0].data = swName
        p[1].type = LibMpv.RENDER_PARAM_INVALID; p[1].data = null
        p.forEach { it.write() }
        val ref = PointerByReference()
        return if (api.mpv_render_context_create(ref, core.handle, p) >= 0) ref.value else null
    }

    private fun render(api: LibMpv, rctx: Pointer, pixels: Memory): Int {
        val p = params(5)
        p[0].type = LibMpv.RENDER_PARAM_SW_SIZE; p[0].data = sizeMem
        p[1].type = LibMpv.RENDER_PARAM_SW_FORMAT; p[1].data = fmtName
        p[2].type = LibMpv.RENDER_PARAM_SW_STRIDE; p[2].data = strideMem
        p[3].type = LibMpv.RENDER_PARAM_SW_POINTER; p[3].data = pixels
        p[4].type = LibMpv.RENDER_PARAM_INVALID; p[4].data = null
        p.forEach { it.write() }
        return api.mpv_render_context_render(rctx, p)
    }

    private fun toJpeg(pixels: Memory, hdr: Boolean, hlg: Boolean): ByteArray? {
        val bytes = pixels.getByteArray(0, W * H * 4)
        if (hdr) ThumbTone.map(bytes, hlg)
        var i = 3
        while (i < bytes.size) { bytes[i] = -1; i += 4 }
        val raster = org.jetbrains.skia.Image.makeRaster(ImageInfo(W, H, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), bytes, W * 4)
        return raster.use { it.encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 82)?.bytes }
    }

    companion object {
        const val W = 384
        const val H = 216
        const val MAX_FRAMES = 3_000
        const val SPACING_MS = 3_000L
        const val DECODED = 32
        /** Every spot this close to where the viewer is comes first (scrubbing is mostly near the spot). */
        private const val NEAR_MS = 4 * 60_000L

        /** Spots within [NEAR_MS] of [aroundMs] nearest first, then the rest coarse to fine (every 16th, 8th, 4th, 2nd, all). */
        fun walkOrder(dur: Long, step: Long, aroundMs: Long = -1): List<Long> {
            val n = (dur / step).toInt()
            val seen = HashSet<Int>()
            val out = ArrayList<Long>(n + 1)
            if (aroundMs >= 0) {
                val c = (aroundMs / step).toInt().coerceIn(0, n)
                val r = (NEAR_MS / step).toInt()
                for (d in 0..r) for (i in intArrayOf(c + d, c - d)) if (i in 0..n && seen.add(i)) out += i * step
            }
            for (stride in intArrayOf(16, 8, 4, 2, 1)) for (i in 0..n step stride) if (seen.add(i)) out += i * step
            return out
        }
    }
}

/** PQ / HLG frames to SDR for the preview card (iPhone ThumbTone): decode, BT.2020 to BT.709, soft highlight roll-off. */
internal object ThumbTone {
    private val pqLut = FloatArray(256) { i ->
        // SMPTE ST 2084 EOTF, normalised so 1.0 = 203 nits (SDR reference white).
        val m1 = 0.1593017578125; val m2 = 78.84375; val c1 = 0.8359375; val c2 = 18.8515625; val c3 = 18.6875
        val e = (i / 255.0).pow(1 / m2)
        val y = (maxOf(e - c1, 0.0) / (c2 - c3 * e)).pow(1 / m1) * 10000.0
        (y / 203.0).toFloat()
    }
    private val hlgLut = FloatArray(256) { i ->
        val e = i / 255.0
        val a = 0.17883277; val b = 0.28466892; val c = 0.55991073
        val v = if (e <= 0.5) e * e / 3 else (kotlin.math.exp((e - c) / a) + b) / 12
        (v * 1000.0 / 203.0 / 2.0).toFloat()
    }
    private val srgb = ByteArray(4096) { i ->
        val l = i / 4095.0
        val v = if (l <= 0.0031308) l * 12.92 else 1.055 * l.pow(1 / 2.4) - 0.055
        (v * 255 + 0.5).toInt().coerceIn(0, 255).toByte()
    }

    fun map(px: ByteArray, hlg: Boolean) {
        val lut = if (hlg) hlgLut else pqLut
        var i = 0
        while (i < px.size) {
            val b = lut[px[i].toInt() and 255]; val g = lut[px[i + 1].toInt() and 255]; val r = lut[px[i + 2].toInt() and 255]
            // BT.2020 -> BT.709 primaries.
            var r7 = 1.6605f * r - 0.5876f * g - 0.0728f * b
            var g7 = -0.1246f * r + 1.1329f * g - 0.0083f * b
            var b7 = -0.0182f * r - 0.1006f * g + 1.1187f * b
            r7 = tone(r7); g7 = tone(g7); b7 = tone(b7)
            px[i] = srgb[(b7 * 4095).toInt().coerceIn(0, 4095)]
            px[i + 1] = srgb[(g7 * 4095).toInt().coerceIn(0, 4095)]
            px[i + 2] = srgb[(r7 * 4095).toInt().coerceIn(0, 4095)]
            px[i + 3] = -1
            i += 4
        }
    }

    /** Reinhard with a white point at 4x reference white: highlights roll off instead of clipping. */
    private fun tone(x: Float): Float {
        val v = maxOf(x, 0f)
        return (v * (1 + v / 16f) / (1 + v)).coerceIn(0f, 1f)
    }
}
