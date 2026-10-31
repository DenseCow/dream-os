package com.dreamos.desktop.preload

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.dreamos.desktop.ui.kit.Images

/**
 * Counts what the viewer would see load in (the pop-in harness, `DREAM_POPIN`; off and free in the app). Each probe is
 * one element on screen (a row, a card's art, a pill, a logo, a score line); it notes when it was first composed and
 * when it first had its content. An element is "late" when its content came after both its first frame and the
 * moment the page became visible ([visibleAt]: the intro's fade, a click). Never-filled art is "missing".
 */
object PopIn {
    val on: Boolean = System.getenv("DREAM_POPIN") != null

    class Rec(val kind: String, val firstAt: Long) {
        @Volatile var readyAt: Long = -1
        @Volatile var expected = false
    }

    private val recs = java.util.Collections.synchronizedList(ArrayList<Rec>())

    /** When the page under test became visible to the viewer (ms, System.currentTimeMillis). */
    @Volatile var visibleAt: Long = 0

    fun start(kind: String): Rec = Rec(kind, System.currentTimeMillis()).also { recs += it }

    fun ready(r: Rec) { if (r.readyAt < 0) r.readyAt = System.currentTimeMillis() }

    /** Starts a new measurement window (a page opened, the page scrolled). */
    fun reset(visible: Long = System.currentTimeMillis()) { recs.clear(); visibleAt = visible }

    /** Grace for content that lands together with its card's first frames (a pill fading in with its art). */
    private const val TOGETHER_MS = 120L

    /** [lateMs]: the latest arrival past the element's first visible frame. */
    data class Count(var complete: Int = 0, var late: Int = 0, var missing: Int = 0, var lateMs: Long = 0)

    fun report(): Map<String, Count> {
        val out = sortedMapOf<String, Count>()
        synchronized(recs) {
            for (r in recs) {
                val c = out.getOrPut(r.kind) { Count() }
                val shown = maxOf(r.firstAt, visibleAt)
                when {
                    r.readyAt < 0 -> if (r.expected) c.missing++
                    r.readyAt <= shown + TOGETHER_MS -> c.complete++
                    else -> { c.late++; c.lateMs = maxOf(c.lateMs, r.readyAt - shown) }
                }
            }
        }
        return out
    }
}

/** One element: [ready] is whether its content is on screen now (read in composition, so it follows the state). */
@Composable
fun PopProbe(kind: String, ready: Boolean, key: Any? = null) {
    if (!PopIn.on) return
    val rec = remember(key) { PopIn.start(kind) }
    SideEffect { if (ready) PopIn.ready(rec) }
}

/** An image element: [url] is on screen once it is decoded in memory (the loader shares one fetch per URL). */
@Composable
fun ArtProbe(kind: String, url: String?) {
    if (!PopIn.on || url == null) return
    val rec = remember(url) { PopIn.start(kind).also { it.expected = true; if (Images.cached(url) != null) PopIn.ready(it) } }
    LaunchedEffect(url) { if (rec.readyAt < 0 && Images.load(url) != null) PopIn.ready(rec) }
}
