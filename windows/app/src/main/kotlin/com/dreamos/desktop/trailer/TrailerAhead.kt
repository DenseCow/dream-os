package com.dreamos.desktop.trailer

import com.dreamos.desktop.core.trailer.TrailerEngine
import com.dreamos.desktop.core.trailer.TrailerRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Title page trailers readied before the page asks ([TrailerEngine.ahead]: looked up, first bytes fetched), so the
 * hero's trailer can start a second after the page opens. Callers: a card rested on (TitleAhead), Home's hero slide on
 * screen, the title page opening. Nothing when Background Trailers is Off. All of it runs in TMDB's background lane,
 * off the UI thread.
 *
 * Cards: at most [MAX_CARDS] lookups at a time; a card rested on while they run replaces the one waiting (the
 * newest is the one likely to be clicked), so sweeping the pointer along a row never queues a lookup per card.
 */
object TrailerAhead {
    private const val MAX_CARDS = 2
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = 0
    private var waiting: TrailerRef? = null

    private fun wanted(ref: TrailerRef) =
        TrailerEngine.cached(ref) == null && BackgroundTrailers.current() != BackgroundTrailers.OFF

    /** A title page opened, or Home's hero shows this title: readied now. */
    fun now(tmdbId: Int, isShow: Boolean, name: String) {
        val ref = TrailerRef(tmdbId, isShow, name)
        warmPlayer()
        scope.launch { if (wanted(ref)) TrailerEngine.ahead(ref) }
    }

    /** A card rested on (after its 300 ms rest): readied in the cards' small queue. */
    fun card(tmdbId: Int, isShow: Boolean, name: String) {
        val ref = TrailerRef(tmdbId, isShow, name)
        warmPlayer()
        scope.launch {
            if (!wanted(ref)) return@launch
            synchronized(this@TrailerAhead) { waiting = ref }
            pump()
        }
    }

    private fun pump() {
        val next = synchronized(this) {
            if (running >= MAX_CARDS) return
            val r = waiting ?: return
            waiting = null
            running++
            r
        }
        scope.launch {
            try { TrailerEngine.ahead(next) } finally {
                synchronized(this@TrailerAhead) { running-- }
                pump()
            }
        }
    }

    /** Loads libmpv here, off the UI thread (about 60 ms the first time), so the hero's first check doesn't. */
    private fun warmPlayer() {
        if (!playerWarm) { playerWarm = true; scope.launch { BackdropVideo.available } }
    }
    @Volatile private var playerWarm = false
}
