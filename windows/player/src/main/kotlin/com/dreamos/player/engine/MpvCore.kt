package com.dreamos.player.engine

import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * One libmpv instance: options, commands, properties and an event thread. Every libmpv call is thread-safe;
 * events arrive on the instance's own thread, so listeners must hand UI work to the UI thread themselves.
 *
 * Every call into libmpv holds [use]'s read side and checks [alive] inside it; [destroy] takes the write side before
 * mpv_terminate_destroy, so the handle is never freed under a call still running on another thread (the UI thread's
 * setters, the event thread's property reads, the previews' grabs).
 */
class MpvCore(val name: String) {
    private val mpv = LibMpv.api
    val handle: Pointer = mpv.mpv_create() ?: error("mpv_create failed")
    private val alive = AtomicBoolean(true)
    private val use = ReentrantReadWriteLock()
    private var events: Thread? = null

    /**
     * [block] with the handle held alive; [dead] when the instance is closing or closed. The first check keeps callers
     * from queueing behind a destroy that has already begun.
     */
    private inline fun <T> live(dead: T, block: () -> T): T =
        if (!alive.get()) dead else use.read { if (alive.get()) block() else dead }

    sealed interface Event {
        data class Property(val name: String, val value: Any?) : Event
        data object FileLoaded : Event
        data object StartFile : Event
        data class EndFile(val reason: Int, val error: Int) : Event
        data object VideoReconfig : Event
        data object AudioReconfig : Event
        data object Seek : Event
        data object PlaybackRestart : Event
        data class Log(val prefix: String, val level: String, val text: String) : Event
        data class CommandReply(val id: Long, val error: Int) : Event
        data object Shutdown : Event
    }

    fun option(key: String, value: String) {
        val r = mpv.mpv_set_option_string(handle, key, value)
        if (r < 0) PlayerLog.w("$name: option $key=$value refused (${mpv.mpv_error_string(r)})")
    }

    fun optionWid(hwnd: Long) {
        val m = Memory(8).apply { setLong(0, hwnd) }
        val r = mpv.mpv_set_option(handle, "wid", LibMpv.FORMAT_INT64, m)
        if (r < 0) PlayerLog.w("$name: wid refused (${mpv.mpv_error_string(r)})")
    }

    fun initialize() {
        val r = mpv.mpv_initialize(handle)
        check(r >= 0) { "mpv_initialize: ${mpv.mpv_error_string(r)}" }
    }

    fun command(vararg args: String): Int = live(-1) {
        val r = mpv.mpv_command(handle, arrayOf(*args, null))
        if (r < 0) PlayerLog.d("$name: command ${args.firstOrNull()} -> ${mpv.mpv_error_string(r)}")
        r
    }

    fun commandAsync(id: Long, vararg args: String) {
        live(Unit) { mpv.mpv_command_async(handle, id, arrayOf(*args, null)) }
    }

    fun set(key: String, value: String): Int = live(-1) {
        val r = mpv.mpv_set_property_string(handle, key, value)
        if (r < 0) PlayerLog.d("$name: set $key=$value -> ${mpv.mpv_error_string(r)}")
        r
    }

    fun string(key: String): String? = live(null) {
        val p = mpv.mpv_get_property_string(handle, key) ?: return@live null
        try { p.getString(0, "UTF-8") } finally { mpv.mpv_free(p) }
    }

    fun double(key: String): Double? = live(null) {
        val m = Memory(8)
        if (mpv.mpv_get_property(handle, key, LibMpv.FORMAT_DOUBLE, m) >= 0) m.getDouble(0) else null
    }

    fun long(key: String): Long? = live(null) {
        val m = Memory(8)
        if (mpv.mpv_get_property(handle, key, LibMpv.FORMAT_INT64, m) >= 0) m.getLong(0) else null
    }

    fun flag(key: String): Boolean? = live(null) {
        val m = Memory(4)
        if (mpv.mpv_get_property(handle, key, LibMpv.FORMAT_FLAG, m) >= 0) m.getInt(0) != 0 else null
    }

    fun observe(key: String, format: Int) {
        live(Unit) { mpv.mpv_observe_property(handle, 0, key, format) }
    }

    fun logs(level: String) { live(Unit) { mpv.mpv_request_log_messages(handle, level) } }

    /** Starts the event thread; [onEvent] runs on it, outside the handle's lock (it may call back in). */
    fun start(onEvent: (Event) -> Unit) {
        events = Thread({
            while (true) {
                // The event is decoded while the handle is held: its memory is mpv's until the next wait.
                val ev = live<Event?>(Event.Shutdown) { decode(mpv.mpv_wait_event(handle, 0.5)) } ?: continue
                try { onEvent(ev) } catch (t: Throwable) { PlayerLog.e("$name: event handler failed", t) }
                if (ev == Event.Shutdown) break
            }
        }, "mpv-$name").apply { isDaemon = true; start() }
    }

    private fun decode(e: MpvEvent): Event? = when (e.event_id) {
        LibMpv.EVENT_NONE -> null
        LibMpv.EVENT_SHUTDOWN -> Event.Shutdown
        LibMpv.EVENT_FILE_LOADED -> Event.FileLoaded
        LibMpv.EVENT_START_FILE -> Event.StartFile
        LibMpv.EVENT_VIDEO_RECONFIG -> Event.VideoReconfig
        LibMpv.EVENT_AUDIO_RECONFIG -> Event.AudioReconfig
        LibMpv.EVENT_SEEK -> Event.Seek
        LibMpv.EVENT_PLAYBACK_RESTART -> Event.PlaybackRestart
        LibMpv.EVENT_COMMAND_REPLY -> Event.CommandReply(e.reply_userdata, e.error)
        LibMpv.EVENT_END_FILE -> e.data?.let { MpvEventEndFile(it) }?.let { Event.EndFile(it.reason, it.error) }
        LibMpv.EVENT_LOG_MESSAGE -> e.data?.let { MpvEventLog(it) }
            ?.let { Event.Log(it.prefix.orEmpty(), it.level.orEmpty(), it.text.orEmpty().trimEnd()) }
        LibMpv.EVENT_PROPERTY_CHANGE -> e.data?.let { MpvEventProperty(it) }?.let { p ->
            val d = p.data
            val v: Any? = if (d == null) null else when (p.format) {
                LibMpv.FORMAT_DOUBLE -> d.getDouble(0)
                LibMpv.FORMAT_FLAG -> d.getInt(0) != 0
                LibMpv.FORMAT_INT64 -> d.getLong(0)
                LibMpv.FORMAT_STRING -> d.getPointer(0)?.getString(0, "UTF-8")
                else -> null
            }
            Event.Property(p.name.orEmpty(), v)
        }
        else -> null
    }

    /**
     * Stops playback and frees the instance; safe to call twice (the second call returns at once). Blocks until the
     * event thread has stopped, every call in flight on another thread has come back, and mpv has let go of its
     * window, so it belongs off the UI thread. Never call it from [start]'s event handler.
     */
    fun destroy() {
        if (!alive.compareAndSet(true, false)) return
        // No new call gets in from here on; the event thread wakes from its wait, sees the flag and stops.
        use.read { mpv.mpv_wakeup(handle) }
        events?.takeIf { it !== Thread.currentThread() }?.let { joinFully(it) }
        use.write { mpv.mpv_terminate_destroy(handle) }
    }

    /** Joins [t] even when this thread is interrupted meanwhile (a skipped terminate would leave mpv running). */
    private fun joinFully(t: Thread) {
        var interrupted = false
        while (t.isAlive) {
            try { t.join() } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
