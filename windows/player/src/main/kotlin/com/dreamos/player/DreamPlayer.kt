package com.dreamos.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dreamos.desktop.core.playback.DebridServiceId
import com.dreamos.desktop.core.playback.PlayRequest
import com.dreamos.desktop.core.playback.PlayTarget
import com.dreamos.desktop.core.playback.PlaybackApi
import com.dreamos.desktop.core.playback.Stream
import com.dreamos.player.api.PlayerEvents
import com.dreamos.player.api.PlayerServices
import com.dreamos.player.engine.MpvEngine
import com.dreamos.player.engine.PlayerLog
import com.dreamos.player.host.MediaControls
import com.dreamos.player.host.PlayerStage
import com.dreamos.player.host.PlayerDisplay
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import com.dreamos.player.session.PlayerSession
import com.dreamos.player.thumbs.ScrubThumbnails
import com.dreamos.player.ui.PlayerScreen
import com.dreamos.player.ui.StageControl
import javax.swing.SwingUtilities

/**
 * Dream Player's front door. Two ways in, both on the Swing / Compose UI thread:
 *
 * ```
 * // The usual one: the curtain shows at once, Dream Core resolves behind it, the history decides where to start.
 * DreamPlayer.play(appWindow, target, playback, CorePlayerServices(playback.core.langs, openTitle), events)
 * // A request already resolved (a look-ahead, the stream head start):
 * DreamPlayer.open(appWindow, request, playback, services, events)
 * ```
 * [PlaybackApi] is core's Dream Core (fallbacks to the next ready link, firstFrame, playerActive, Change Source);
 * [PlayerServices] is everything else (details, history reports, Trakt, X-Ray); [CorePlayerServices] wires it to core.
 * One player at a time: a new request while one plays (or sits in the mini player) plays in the same windows, full size.
 */
object DreamPlayer {
    private var current: Running? = null

    val isOpen: Boolean get() = current != null

    /**
     * Plays [request] over [owner] (the app's window; null for a free-standing player). [fullScreen]: start covering the
     * monitor. A picked source plays as given; startMs 0 starts where the watch history says, 1 from the beginning.
     * [trailer]: the Fire Stick's trailer mode with the trailers' own [playback] (core's TrailerPlayback: an empty
     * stream resolves behind the curtain, fallbacks ride as alternates, Try Again asks it again): the lighter controls,
     * nothing saved, no history, the end closes. [trailerAudio]: the sound as its own link when the request's link has
     * none (mpv plays both in step); TrailerPlayback joins them into one EDL link itself and needs none.
     */
    fun open(
        owner: java.awt.Window?,
        request: PlayRequest,
        playback: PlaybackApi,
        services: PlayerServices,
        events: PlayerEvents = object : PlayerEvents {},
        fullScreen: Boolean = false,
        trailer: Boolean = false,
        trailerAudio: String? = null,
    ) {
        check(SwingUtilities.isEventDispatchThread()) { "DreamPlayer must be opened on the UI thread" }
        // A trailer never takes over what plays in the same windows: what plays closes (saving its spot) first.
        if (trailer) current?.session?.close()
        current?.let { r ->
            PlayerLog.i("DreamPlayer: replacing ${r.session.target.label} with ${request.target.label}")
            if (r.stage.mode == PlayerStage.Mode.Mini) r.control.expand()
            r.session.replace(request)
            return
        }
        current = Running(owner, request, playback, services, events, fullScreen, trailer, trailerAudio)
    }

    /**
     * Plays [target]: the curtain (backdrop, NOW PLAYING, the logo) shows at once while Dream Core finds the stream and
     * the watch history says where to start ([startMs] > 0 overrides it, e.g. Start Over from a page: pass 1).
     */
    fun play(
        owner: java.awt.Window?,
        target: PlayTarget,
        playback: PlaybackApi,
        services: PlayerServices,
        events: PlayerEvents = object : PlayerEvents {},
        fullScreen: Boolean = false,
        startMs: Long = 0,
    ) {
        val pending = PlayRequest(target, Stream(url = "", service = DebridServiceId.REAL_DEBRID, fileName = ""), startMs)
        current?.let { r ->
            if (r.stage.mode == PlayerStage.Mode.Mini) r.control.expand()
            r.session.playOther(target, startMs.takeIf { it > 0 })
            return
        }
        open(owner, pending, playback, services, events, fullScreen)
    }

    // Dev runner hooks (dev/PlayerDemo.kt).
    internal val session: PlayerSession? get() = current?.session
    internal val control: StageControl? get() = current?.control
    internal val stageBounds: java.awt.Rectangle? get() = current?.stage?.video?.bounds
    internal fun engineSmoothness(): String? = current?.engine?.smoothness()
    internal val thumbsOpened: Boolean get() = current?.thumbs?.opened == true

    /** Closes the player (saving progress), as the viewer's Close would. */
    fun close() { current?.session?.close() }

    /**
     * Closes it as [close] does, then runs [then] on the UI thread once mpv has let go and the player's windows are
     * gone (the app's window may then close: disposing it first would take the player's windows down under a live
     * swap chain). Nothing open: [then] runs at once.
     */
    fun close(then: () -> Unit) {
        val r = current ?: return then()
        r.afterRelease = then
        r.session.close()
    }

    private class Running(
        owner: java.awt.Window?, request: PlayRequest, playback: PlaybackApi, services: PlayerServices,
        events: PlayerEvents, fullScreen: Boolean, trailer: Boolean, trailerAudio: String?,
    ) {
        val stage = PlayerStage(owner)
        val engine: MpvEngine
        val session: PlayerSession
        val thumbs: ScrubThumbnails
        /** Media keys and the system media overlay (null off Windows). */
        private var media: MediaControls? = null
        private var mode by mutableStateOf(PlayerStage.Mode.Window)
        private val host = PlayerDisplay.host.takeIf { owner != null }
        /** The app's full screen as the controls show it (followed through [DisplayHost.listen]). */
        private var appFull by mutableStateOf(host?.fullScreen == true)
        /** The player itself put the app in full screen (from Windowed): Esc steps back out of it. */
        private var enteredHere by mutableStateOf(false)
        private var stopHost: () -> Unit = {}
        val control = object : StageControl {
            private var beforeMini = PlayerStage.Mode.Window
            override val fullScreen get() = if (host != null) appFull else mode == PlayerStage.Mode.FullScreen
            override val backLeavesFullScreen get() = if (host != null) appFull && enteredHere else fullScreen
            override val mini get() = mode == PlayerStage.Mode.Mini
            override fun toggleFullScreen() {
                if (host == null) { stage.setMode(if (fullScreen) PlayerStage.Mode.Window else PlayerStage.Mode.FullScreen); return }
                enteredHere = !appFull
                host.toggleFullScreen()
            }
            override fun enterMini() { beforeMini = stage.mode; stage.setMode(PlayerStage.Mode.Mini) }
            override fun expand() = stage.setMode(beforeMini)
            override fun dragMini(dx: Int, dy: Int) = stage.dragMini(dx, dy)
        }

        init {
            stage.onModeChanged = { m -> mode = m }
            val hwnd = stage.show()
            stopHost = host?.listen { val now = host.fullScreen; if (!now) enteredHere = false; appFull = now } ?: {}
            if (fullScreen) { if (host == null) stage.setMode(PlayerStage.Mode.FullScreen) else if (!host.fullScreen) control.toggleFullScreen() }
            engine = MpvEngine(hwnd)
            session = PlayerSession(request, playback, services, events, engine, trailer, trailerAudio, onClose = ::shutdown)
            thumbs = ScrubThumbnails(session)
            stage.onCloseRequest = { session.close() }
            stage.setContent { PlayerScreen(session, control, frameAt = thumbs::frameNear) }
            media = MediaControls.attach(com.sun.jna.Native.getWindowID(stage.video)) { b ->
                SwingUtilities.invokeLater { if (current?.session === session) session.mediaButton(b) }
            }
            media?.let { m -> session.scope.launch { snapshotFlow { session.mediaInfo() }.collect { m.update(it) } } }
        }

        private var shut = false

        /**
         * Closing, in an order that is always safe (session.close calls it once, on the UI thread):
         *  1. UI thread, at once: the player's windows leave the screen in any mode (mini, full screen) and the app's
         *     window comes to the front; the previews and the media keys are told to stop.
         *  2. A thread of its own: the previews' libmpv is freed by its thread, the main libmpv is terminated (it
         *     lets go of the video window; this is the part that can take a while on a big stream).
         *  3. UI thread: the two windows are disposed, never before mpv let go of the video window (a window
         *     destroyed under a live swap chain can hang).
         * The UI thread never waits on mpv ([Teardown]).
         */
        /** Runs on the UI thread after the windows are disposed ([close] with a follow-up). */
        var afterRelease: (() -> Unit)? = null

        private fun shutdown() {
            if (shut) return
            shut = true
            if (current === this) current = null
            stopHost()
            media?.let { m -> Thread({ m.close() }, "dream-media-close").apply { isDaemon = true }.start() }
            stage.hide()
            Teardown.begin(engine, thumbs) { try { stage.dispose() } finally { afterRelease?.invoke() } }
        }
    }
}
