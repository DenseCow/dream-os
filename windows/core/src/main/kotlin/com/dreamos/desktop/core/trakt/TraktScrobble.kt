package com.dreamos.desktop.core.trakt

import com.dreamos.desktop.core.model.PlayRequest
import com.dreamos.desktop.core.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Dream Player -> Trakt scrobble (fire-home Trakt.onPlayer / onPlayerClosed, the iPhone's TraktScrobbler): start when
 * a title settles into playing, pause when it settles paused, stop when it stops, changes or the player closes. The
 * state lives on Trakt's timer thread; the calls go out on Trakt's own thread, never in playback's way.
 */
internal object TraktScrobbler {
    /**
     * Play / pause must hold this long before Trakt hears of it: rapid toggles (a headset sending play/pause every few
     * seconds) send at most one state, the one it settles on.
     */
    @Volatile var settleMs = 3_000L
    private const val MIN_DURATION_MS = 5 * 60_000L

    private class Play(val key: String, val item: TraktItem, val label: String, val pid: String) {
        var sent: String? = null
        /** 0..100 */
        var progress = 0.0
        /** The state the player settled on (null: not told yet). */
        var playing: Boolean? = null
        var flush: Future<*>? = null
    }

    /** Timer thread only. */
    private var play: Play? = null
    /** Titles whose harmless 422 on a pause was logged already. */
    private val quiet422 = java.util.Collections.synchronizedSet(HashSet<String>())

    fun report(req: PlayRequest, progress: Double, action: ScrobbleAction, durationMs: Long) {
        val pid = Trakt.pid
        Trakt.timer.execute {
            try { onReport(req, progress, action, durationMs, pid) } catch (e: Exception) {
                Log.w("Trakt", "scrobble skipped: ${e.javaClass.simpleName}")
            }
        }
    }

    fun closed() {
        Trakt.timer.execute {
            val p = play ?: return@execute
            play = null
            try { close(p) } catch (e: Exception) { Log.w("Trakt", "stop skipped: ${e.javaClass.simpleName}") }
        }
    }

    private fun onReport(req: PlayRequest, progress: Double, action: ScrobbleAction, durationMs: Long, pid: String) {
        val t = req.title
        if (t.id <= 0 || durationMs in 1 until MIN_DURATION_MS) return
        val ep = t.isShow
        if (ep && (req.season == null || req.episode == null || req.season <= 0 || req.episode <= 0)) return
        val key = if (ep) "tv:${t.id}:${req.season}:${req.episode}" else "movie:${t.id}"
        var p = play
        if (p == null || p.key != key) {
            p?.let { close(it) }
            play = null
            if (action == ScrobbleAction.STOP || !Trakt.signedIn(pid)) return
            p = Play(key, TraktItem(!ep, t.id, req.season ?: 0, req.episode ?: 0),
                if (ep) "${t.name} S${req.season}E${req.episode}" else t.name, pid)
            play = p
        }
        if (progress > 0) p.progress = (progress * 100).coerceIn(0.0, 100.0)
        if (action == ScrobbleAction.STOP) {
            play = null
            close(p)
            return
        }
        val playing = action == ScrobbleAction.START
        if (p.playing == playing) return
        p.playing = playing
        p.flush?.cancel(false)
        val pp = p
        p.flush = Trakt.timer.schedule({
            pp.flush = null
            if (play !== pp) return@schedule
            when {
                pp.playing == true && pp.sent != "start" -> send(pp, "start")
                pp.playing == false && pp.sent == "start" -> send(pp, "pause")
            }
        }, settleMs, TimeUnit.MILLISECONDS)
    }

    private fun close(p: Play) {
        p.flush?.cancel(false) // a state still settling: the stop says it all
        p.flush = null
        if (p.sent == null || p.sent == "stop") return
        send(p, "stop")
    }

    private fun send(p: Play, action: String) {
        p.sent = action
        val progress = Math.round(p.progress * 100) / 100.0
        val item = p.item
        val label = p.label
        val pid = p.pid
        val key = p.key
        val happened = System.currentTimeMillis() // when this pause / stop happened here (a queued paused point keeps it)
        Log.i("Trakt", "scrobble $action $label ${progress.toInt()}%")
        Trakt.io.execute { scrobbleNow(pid, action, item, label, progress, key, happened) }
    }

    private fun scrobbleNow(pid: String, action: String, item: TraktItem, label: String, progress: Double, key: String, happened: Long) {
        val body = JsonObject(item.json() + ("progress" to JsonPrimitive(progress)))
        val r = try { Trakt.authed(pid, "POST", "/scrobble/$action", body) } catch (e: Exception) { null }
        when {
            r == null || r.transient -> {
                // Offline: a start means nothing later; a pause or stop is kept (finished = a history entry at its real time).
                if (action == "start" || TraktVault.tokens(pid) == null) return
                if (progress >= Trakt.WATCHED_PCT && action == "stop") TraktSync.queue(pid, TraktOp("history", at = happened, item = item))
                // "when": a paused point sent later gets Trakt's time of sending, so the flush drops it once it's stale.
                else TraktSync.queue(pid, TraktOp("progress", item = item, progress = progress, `when` = happened))
                Log.w("Trakt", "couldn't reach Trakt, queued $action $label")
            }
            r.ok || r.code == 409 -> { // 409: already scrobbled a moment ago
                // Trakt has this title's newer state now: an older paused point still queued for it must never follow.
                if (action != "start") TraktSync.dropQueuedProgress(pid, item)
                if (action == "stop") {
                    if (progress >= Trakt.WATCHED_PCT) Log.i("Trakt", "$label marked watched")
                    TraktSync.markDirty(pid)
                    TraktSync.syncSoon(pid, "after playback")
                }
            }
            // 422 on a pause: Trakt has nothing playing to pause (it ended or was paused already). Harmless: logged once.
            r.code == 422 && action == "pause" -> if (quiet422.add(key)) Log.i("Trakt", "scrobble pause $label not needed, ignored")
            else -> Log.w("Trakt", "scrobble $action $label not accepted (${r.code})")
        }
    }
}
