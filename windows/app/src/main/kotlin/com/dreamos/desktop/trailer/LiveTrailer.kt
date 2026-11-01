package com.dreamos.desktop.trailer

import androidx.compose.ui.graphics.ImageBitmap
import com.dreamos.desktop.core.trailer.TrailerRef

/**
 * One trailer picture playing in a libmpv of its own, as the hero and the full-screen surface use it. The real one is
 * [BackdropVideo]; the checks use a stand-in, so the hand-over can be checked without libmpv.
 *
 * It is a handle to a live decoder: whoever holds it is the one who must let it go ([destroy]), and only one holder
 * at a time. The Trailer button moves the hero's handle to the full-screen surface rather than opening a second one.
 */
internal interface LiveVideo {
    /** Draw at [w] x [h] pixels. */
    fun size(w: Int, h: Int)
    fun load(url: String)
    fun setVolume(v: Int)
    fun pause(on: Boolean)
    /** On screen now: read the full minute ahead. */
    fun playing()
    /** Opened muted: turns the sound path on, still at volume 0. False when no audio device may be opened. */
    fun enableSound(): Boolean
    fun setZoom(z: Float)
    /** The whole picture inside what's drawn (no cover crop, no letterbox zoom), as a player shows a film. */
    fun fit()
    /** The hero's way round: cover the whole hero, with [zoom] over it again for baked-in letterbox bars. */
    fun cover(zoom: Float)
    val positionMs: Long
    val durationMs: Long
    fun probeFrames(check: ((BackdropVideo.Sampler) -> Unit)?)
    fun <T> withFrame(paint: (ImageBitmap?) -> T): T
    /** Hands the frames and the ends to a new listener, picture and sound running. */
    fun listen(onFrame: (Long) -> Unit, onEvent: (BackdropVideo.Event) -> Unit)
    /** Stops the sound at once and lets go of the decoder; safe to call twice. */
    fun destroy()
}

/**
 * A hero trailer handed over while it plays (`TrailerBackdropState.promote`): the live decoder, which title and which
 * video it is playing, and whether it was opened without an audio device. The page has let go of it by the time this
 * exists, so the surface that takes it owns it.
 */
internal class LiveTrailer(
    val video: LiveVideo,
    val ref: TrailerRef,
    val videoKey: String,
    /** The hero was playing silently ("On, Muted"): the taker opens the audio device itself. */
    val muted: Boolean,
    /**
     * The full-screen trailer is over, and the hero takes it from here. [still] is the decoder, still playing, to
     * carry on behind the picture (the promotion in reverse: the same instance, nothing loaded again); null when
     * there is nothing live to give back (it ended or failed), and the taker has let go of it itself.
     * [soundOpen] says whether the decoder has an audio device by now, so the hero knows the speaker need not open one.
     */
    val back: (still: LiveVideo?, soundOpen: Boolean) -> Unit,
)
