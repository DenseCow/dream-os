package com.dreamos.player.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** What the picture and sound are right now, for the badges, Info's Now Playing and the HDR line. */
data class StreamFacts(
    val width: Int = 0,
    val height: Int = 0,
    val videoCodec: String = "",
    /** "pq", "hlg", "bt.1886"...: the stream's transfer. */
    val gamma: String = "",
    val primaries: String = "",
    val dvProfile: Int? = null,
    /** What mpv sends to the screen: "pq" when the swap chain is HDR. */
    val outGamma: String = "",
    val peakNits: Double = 0.0,
    val audioCodec: String = "",
    val audioProfile: String = "",
    val audioChannels: Int = 0,
    val audioName: String = "",
    val fps: Double = 0.0,
    val hwdec: String = "",
) {
    val hdrKind: String? get() = when {
        dvProfile != null -> "Dolby Vision"
        gamma == "pq" -> "HDR10"
        gamma == "hlg" -> "HLG"
        else -> null
    }
    /** The stream is HDR and mpv is sending HDR to the screen (Windows HDR on, an HDR swap chain). */
    val hdrOnScreen: Boolean get() = hdrKind != null && (outGamma == "pq" || outGamma == "hlg")
}

/** A chapter of the file (mpv's chapter-list): where it starts and its name. */
data class Chapter(val startMs: Long, val title: String)

data class EngineState(
    val loaded: Boolean = false,
    /** The first picture is on screen for the current file. */
    val firstFrame: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val paused: Boolean = false,
    /** Waiting for data (start, a seek outside the buffer, a stall). */
    val buffering: Boolean = true,
    val bufferedToMs: Long = 0,
    val ended: Boolean = false,
    val seeking: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val volume: Int = 100,
    val muted: Boolean = false,
    val speed: Double = 1.0,
    val facts: StreamFacts = StreamFacts(),
)

sealed interface EngineEvent {
    data object FirstFrame : EngineEvent
    data object Ended : EngineEvent
    /** The file failed to open or play; [detail] goes to the log only. */
    data class Failed(val detail: String) : EngineEvent
    data object TracksChanged : EngineEvent
}

/**
 * Dream Player's engine: one libmpv drawing into a native window (`wid`), Direct3D 11 + gpu-next, hardware decoding,
 * HDR passthrough on HDR screens and tone mapping on SDR ones (target-colorspace-hint), Dolby Vision through
 * libplacebo. State is a [StateFlow] safe to read from any thread; position updates are throttled to ~5 a second.
 */
class MpvEngine(wid: Long?) {
    private val core = MpvCore("main")
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    /** The latest exact position (every frame), for code that must not wait for the throttled state. */
    private val exactPos = AtomicLong(0)
    private var lastPublish = 0L
    @Volatile private var loadGen = 0
    @Volatile private var awaitingFrame = false

    init {
        with(core) {
            if (wid != null) optionWid(wid) else {
                // Headless (dev checks): decode but show and play nothing.
                option("vo", "null"); option("ao", "null")
            }
            if (wid != null) option("vo", "gpu-next")
            // Dev and test runs with a real window but no sound (-Ddream.player.silent=true).
            if (System.getProperty("dream.player.silent") == "true") option("ao", "null")
            option("gpu-api", "d3d11")
            option("gpu-context", "d3d11")
            option("hwdec", "d3d11va,auto-safe")
            option("target-colorspace-hint", "yes")
            option("force-window", if (wid != null) "yes" else "no")
            option("idle", "yes")
            option("keep-open", "yes")
            option("background-color", "#000000")
            option("input-default-bindings", "no")
            option("input-vo-keyboard", "no")
            option("input-cursor", "no")
            option("cursor-autohide", "no")
            option("osc", "no")
            option("osd-level", "0")
            option("terminal", "no")
            option("ytdl", "no")
            option("cache", "yes")
            option("demuxer-seekable-cache", "yes")
            option("demuxer-max-bytes", bufferBytes())
            option("demuxer-max-back-bytes", backBytes())
            // A PC has the memory: read ahead up to 10 minutes (the byte limit above comes first on big files).
            option("demuxer-readahead-secs", "600")
            // -Ddream.player.quality=fast drops back to mpv's defaults; frames dropping on this GPU do it by themselves
            // (checkSmoothness).
            if (wid != null && System.getProperty("dream.player.quality") != "fast") {
                picture = Picture.BEST
                // The best picture mpv offers, on the GPU a PC has: high-quality scalers (ewa_lanczossharp),
                // libplacebo's peak detection and gamut mapping for HDR on SDR screens, debanding, temporal dither.
                option("profile", "high-quality")
                option("hdr-compute-peak", "yes")
                option("deband", "yes")
            }
            option("cache-pause-wait", "1")
            option("network-timeout", "20")
            option("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=6")
            option("user-agent", "DreamOS/${System.getProperty("dream.version") ?: "4"} DreamPlayer")
            option("volume-max", "100")
            option("volume-gain-max", "12")
            option("sub-ass-override", "force")
            option("sub-font", "Segoe UI Semibold")
            option("sub-auto", "no")
            option("audio-display", "no")
            option("hr-seek", "yes")
            initialize()
            logs("warn")
            observe("time-pos", LibMpv.FORMAT_DOUBLE)
            observe("duration", LibMpv.FORMAT_DOUBLE)
            observe("pause", LibMpv.FORMAT_FLAG)
            observe("paused-for-cache", LibMpv.FORMAT_FLAG)
            observe("seeking", LibMpv.FORMAT_FLAG)
            observe("eof-reached", LibMpv.FORMAT_FLAG)
            observe("demuxer-cache-time", LibMpv.FORMAT_DOUBLE)
            observe("track-list", LibMpv.FORMAT_STRING)
            observe("chapter-list", LibMpv.FORMAT_STRING)
            observe("volume", LibMpv.FORMAT_DOUBLE)
            observe("mute", LibMpv.FORMAT_FLAG)
            observe("speed", LibMpv.FORMAT_DOUBLE)
            observe("video-params/gamma", LibMpv.FORMAT_STRING)
            observe("video-target-params/gamma", LibMpv.FORMAT_STRING)
            observe("audio-params/channel-count", LibMpv.FORMAT_INT64)
            observe("core-idle", LibMpv.FORMAT_FLAG)
            start(::onEvent)
        }
    }

    /**
     * Read-ahead sized to the PC (not the stick's or phone's): a sixth of the RAM, 512 MiB to 3 GiB ahead, and a third
     * of that kept behind for instant jumps back. Worst case 4 GiB on a 24 GB+ PC; 16 GB gets 2.7 GiB ahead + 0.9 behind.
     */
    private fun bufferBytes(): String = "${aheadMiB()}MiB"
    private fun backBytes(): String = "${aheadMiB() / 3}MiB"
    private fun aheadMiB(): Long = (ramBytes / 6 / (1 shl 20)).coerceIn(512, 3072)

    /**
     * Opens [url] at [startMs]; [headers] go with every request. Earlier loads are replaced. [audioUrl]: the sound in
     * a separate stream (a trailer's video-only and audio-only links), played in step as the file's own audio.
     */
    fun load(url: String, headers: Map<String, String>, startMs: Long, audioUrl: String? = null) {
        val gen = ++loadGen
        awaitingFrame = true
        _state.update { EngineState(volume = it.volume, muted = it.muted, speed = it.speed) }
        val fields = headers.entries.joinToString(",") { (k, v) -> "$k: ${v.replace(",", "\\,")}" }
        core.set("http-header-fields", fields)
        val opts = buildList {
            if (startMs > 0) add("start=${startMs / 1000.0}")
            // %n% quotes the value byte-exact (a link full of commas and equals signs).
            if (!audioUrl.isNullOrEmpty()) add("audio-files-append=%${audioUrl.toByteArray(Charsets.UTF_8).size}%$audioUrl")
        }.joinToString(",")
        PlayerLog.i("Engine: load #$gen ${PlayerLog.host(url)} at ${startMs / 1000}s" + if (audioUrl != null) " with separate audio" else "")
        if (opts.isEmpty()) core.command("loadfile", url, "replace") else core.command("loadfile", url, "replace", "-1", opts)
    }

    fun play() { core.set("pause", "no") }
    fun pause() { core.set("pause", "yes") }
    fun togglePause() = if (_state.value.paused) play() else pause()
    fun stop() { core.command("stop") }

    /** Exact seek to [ms] (keyframe + decode forward: the picture lands where the viewer asked). */
    fun seek(ms: Long, exact: Boolean = true) {
        val dur = _state.value.durationMs
        val to = if (dur > 0) ms.coerceIn(0, (dur - END_GUARD_MS).coerceAtLeast(0)) else ms.coerceAtLeast(0)
        exactPos.set(to)
        _state.update { it.copy(positionMs = to, ended = false) }
        core.commandAsync(0, "seek", (to / 1000.0).toString(), if (exact) "absolute+exact" else "absolute+keyframes")
    }

    val positionMs: Long get() = exactPos.get()

    fun setVolume(v: Int) { core.set("volume", v.coerceIn(0, 100).toString()) }
    fun setMuted(m: Boolean) { core.set("mute", if (m) "yes" else "no") }
    fun setSpeed(s: Double) { core.set("speed", s.toString()) }
    /** Volume Boost in dB (0, 3, 6, 10). */
    fun setGainDb(db: Int) { core.set("volume-gain", db.toString()) }
    fun selectAudio(id: Int) { core.set("aid", id.toString()) }
    fun selectSub(id: Int?) { core.set("sid", id?.toString() ?: "no") }
    /** Subtitles hide under sheets, X-Ray and post-play (they'd sit on top of them otherwise). */
    fun subtitlesVisible(on: Boolean) { core.set("sub-visibility", if (on) "yes" else "no") }
    fun addSubtitle(url: String, title: String, lang: String?, select: Boolean) {
        core.commandAsync(0, "sub-add", url, if (select) "select" else "auto", title, lang ?: "")
    }

    /** Subtitle look: [scale] of the base size, [style] 0 Classic / 1 Boxed / 2 Yellow, [bottom] fraction of the height. */
    fun subtitleStyle(scale: Double, style: Int, bottom: Double) {
        core.set("sub-scale", "%.2f".format(java.util.Locale.ROOT, scale))
        when (style) {
            1 -> {
                core.set("sub-color", "#FFFFFFFF")
                core.set("sub-border-style", "background-box")
                core.set("sub-back-color", "#BE0A0A0C")
                core.set("sub-shadow-offset", "0")
                core.set("sub-border-size", "0")
            }
            else -> {
                core.set("sub-color", if (style == 2) "#FFFFE45C" else "#FFFFFFFF")
                core.set("sub-border-style", "outline-and-shadow")
                core.set("sub-border-color", "#E6000000")
                core.set("sub-border-size", "2.6")
                core.set("sub-shadow-offset", "1")
                core.set("sub-back-color", "#00000000")
            }
        }
        // sub-margin-y is in script pixels of a 720-line frame; bottom is a fraction of the screen.
        core.set("sub-margin-y", (bottom * 720).toInt().coerceIn(8, 400).toString())
    }

    fun string(key: String) = core.string(key)
    fun long(key: String) = core.long(key)
    fun double(key: String) = core.double(key)

    /** Smoothness counters for the log: dropped by the renderer, dropped by the decoder, late frames. */
    fun smoothness(): String = "fps ${"%.2f".format(core.double("estimated-vf-fps") ?: 0.0)}, " +
        "dropped ${core.long("frame-drop-count") ?: 0} (decoder ${core.long("decoder-frame-drop-count") ?: 0}), " +
        "late ${core.long("vo-delayed-frame-count") ?: 0}"

    fun destroy() {
        PlayerLog.i("Engine: closing (${smoothness()})")
        core.destroy()
    }

    private fun onEvent(e: MpvCore.Event) {
        when (e) {
            is MpvCore.Event.Property -> onProperty(e.name, e.value)
            MpvCore.Event.FileLoaded -> {
                _state.update { it.copy(loaded = true) }
                refreshFacts()
            }
            MpvCore.Event.PlaybackRestart -> {
                _state.update { it.copy(buffering = false, seeking = false) }
                if (awaitingFrame) {
                    awaitingFrame = false
                    _state.update { it.copy(firstFrame = true) }
                    refreshFacts()
                    PlayerLog.i("Engine: first frame (${_state.value.facts.let { f -> "${f.width}x${f.height} ${f.videoCodec} ${f.gamma}, hwdec ${f.hwdec}" }})")
                    // A picture this big already dropped frames with the best scaling on this PC: start light.
                    if (picture == Picture.BEST && _state.value.facts.height >= fastFromHeight) usePicture(Picture.FAST, "a ${_state.value.facts.height}p picture dropped frames earlier")
                    _events.tryEmit(EngineEvent.FirstFrame)
                }
            }
            MpvCore.Event.VideoReconfig -> refreshFacts()
            MpvCore.Event.AudioReconfig -> refreshFacts()
            is MpvCore.Event.EndFile -> when (e.reason) {
                LibMpv.END_ERROR -> {
                    PlayerLog.w("Engine: file failed (${LibMpv.api.mpv_error_string(e.error)})")
                    _events.tryEmit(EngineEvent.Failed(LibMpv.api.mpv_error_string(e.error)))
                }
                LibMpv.END_EOF -> _events.tryEmit(EngineEvent.Ended)
                else -> {}
            }
            is MpvCore.Event.Log -> PlayerLog.w("mpv[${e.prefix}] ${e.text}")
            else -> {}
        }
    }

    private fun onProperty(name: String, v: Any?) {
        when (name) {
            "time-pos" -> {
                val ms = ((v as? Double) ?: return).times(1000).toLong()
                exactPos.set(ms)
                val now = System.nanoTime()
                if (now - lastPublish > 190_000_000L || _state.value.seeking) {
                    lastPublish = now
                    _state.update { it.copy(positionMs = ms) }
                }
            }
            "duration" -> _state.update { it.copy(durationMs = ((v as? Double) ?: 0.0).times(1000).toLong()) }
            "pause" -> _state.update { it.copy(paused = v == true) }
            "paused-for-cache" -> _state.update { it.copy(buffering = v == true || (!it.firstFrame)) }
            "seeking" -> _state.update { it.copy(seeking = v == true) }
            "eof-reached" -> if (v == true) {
                _state.update { it.copy(ended = true) }
                _events.tryEmit(EngineEvent.Ended)
            } else _state.update { it.copy(ended = false) }
            "demuxer-cache-time" -> _state.update { it.copy(bufferedToMs = ((v as? Double) ?: 0.0).times(1000).toLong()) }
            "track-list" -> {
                _state.update { it.copy(tracks = Tracks.parse(v as? String)) }
                _events.tryEmit(EngineEvent.TracksChanged)
            }
            "chapter-list" -> _state.update { it.copy(chapters = Tracks.chapters(v as? String)) }
            "volume" -> _state.update { it.copy(volume = ((v as? Double) ?: 100.0).toInt()) }
            "mute" -> _state.update { it.copy(muted = v == true) }
            "speed" -> _state.update { it.copy(speed = (v as? Double) ?: 1.0) }
            "video-params/gamma", "video-target-params/gamma", "audio-params/channel-count" -> refreshFacts()
        }
    }

    private fun refreshFacts() {
        val c = core
        val vTrack = _state.value.tracks.firstOrNull { it.type == Track.Type.VIDEO && it.selected }
        val f = StreamFacts(
            width = (c.long("video-params/w") ?: vTrack?.width?.toLong() ?: 0).toInt(),
            height = (c.long("video-params/h") ?: vTrack?.height?.toLong() ?: 0).toInt(),
            videoCodec = Tracks.codecVideo(c.string("current-tracks/video/codec") ?: vTrack?.codec.orEmpty()),
            gamma = c.string("video-params/gamma").orEmpty(),
            primaries = c.string("video-params/primaries").orEmpty(),
            dvProfile = vTrack?.dvProfile,
            outGamma = c.string("video-target-params/gamma").orEmpty(),
            peakNits = c.double("video-target-params/max-luma") ?: 0.0,
            audioCodec = c.string("current-tracks/audio/codec").orEmpty(),
            audioProfile = c.string("current-tracks/audio/codec-profile").orEmpty(),
            audioChannels = (c.long("audio-params/channel-count") ?: 0).toInt(),
            audioName = c.string("current-tracks/audio/title").orEmpty(),
            fps = c.double("container-fps") ?: 0.0,
            hwdec = c.string("hwdec-current").orEmpty(),
        )
        _state.update { it.copy(facts = f) }
    }

    // ---------------------------------------------------------------- picture quality, with a way back

    private enum class Picture { DEFAULT, BEST, FAST }
    @Volatile private var picture = Picture.DEFAULT
    private var lastDrops = -1L
    private var lastLate = -1L
    private val windowDrops = ArrayDeque<Long>()

    /**
     * Called once a second by the session. While the picture plays steadily, renderer drops and late frames are
     * summed over 10 s; more than 12 (about 5% at 24 fps) with the best scaling on switches this stream to mpv's fast
     * profile at once (no reload, no black frame) and remembers the picture size for the rest of the run.
     */
    fun checkSmoothness(steady: Boolean) {
        if (picture != Picture.BEST) return
        val drops = core.long("frame-drop-count") ?: return
        val late = core.long("vo-delayed-frame-count") ?: 0
        if (!steady || lastDrops < 0) { lastDrops = drops; lastLate = late; windowDrops.clear(); return }
        windowDrops.addLast((drops - lastDrops) + (late - lastLate))
        lastDrops = drops; lastLate = late
        while (windowDrops.size > 10) windowDrops.removeFirst()
        val sum = windowDrops.sum()
        if (windowDrops.size >= 5 && sum > 12) {
            val h = _state.value.facts.height
            if (h > 0) fastFromHeight = minOf(fastFromHeight, h)
            usePicture(Picture.FAST, "$sum frames dropped or late in ${windowDrops.size} s at ${h}p")
        }
    }

    private fun usePicture(p: Picture, why: String) {
        picture = p
        core.command("apply-profile", "fast")
        core.set("deband", "no")
        core.set("hdr-compute-peak", "no")
        windowDrops.clear()
        PlayerLog.w("Engine: lighter picture scaling ($why)")
    }

    companion object {
        /** Pictures this tall or taller start with the fast profile (set when one dropped frames this run). */
        @Volatile private var fastFromHeight = Int.MAX_VALUE

        /** Physical memory, for the cache sizes (8 GB when it can't be read). */
        val ramBytes: Long by lazy {
            runCatching {
                (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).totalMemorySize
            }.getOrDefault(8L shl 30)
        }
        /** Seeks stop this far before the end, so a jump to the end never skips straight past the post-play card. */
        const val END_GUARD_MS = 2_000L
    }
}
