package com.firehome.launcher

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Dream Player's engine: ExoPlayer (Media3) behind [PlayerEngine]. One instance per player screen; the
 * ExoPlayer inside is created by [load] and dropped by [releasePlayer] (screen hidden) and brought back
 * by [restore] at the same spot, so listeners and the surface survive. Main thread only.
 *
 * - URLs with headers: "url|User-Agent=…&Referer=…" become request headers (plus any from intent extras).
 * - MKV/MP4/WebM/TS (sniffed), HLS (.m3u8, or retried as HLS when the container isn't recognized), DASH (.mpd).
 * - Fast start (2.5 s) with a large but bounded buffer (debrid streams), transient network errors retried.
 * - Audio the device can't decode (DTS/TrueHD without passthrough) is listed as unsupported and never picked.
 */
class ExoEngine(context: Context) : PlayerEngine {
    private val ctx = context.applicationContext

    /** What to play. [mimeHint] is an intent type like application/x-mpegURL, or null. */
    class Source(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val mimeHint: String? = null,
        val subtitles: List<SubtitleFile> = emptyList(),
    )

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<PlayerEngine.Listener>()

    var player: ExoPlayer? = null
        private set
    private var source: Source? = null
    private var streamUrl = ""
    private var headers: Map<String, String> = emptyMap()
    private var forceHls = false
    private val subs = ArrayList<SubtitleFile>()
    private var surfaceView: SurfaceView? = null
    private var speed = 1f
    private var savedPosition = 0L
    private var savedPlay = true
    private var retries = 0
    private var audioErrorShown = false
    private var lastError: String? = null
    private var loadedAt = 0L

    /** Subtitle cues for the activity's SubtitleView. */
    var onCues: ((List<Cue>) -> Unit)? = null
    /** Video size / pixel ratio, for the aspect-ratio frame. */
    var onVideoSize: ((width: Int, height: Int, pixelRatio: Float) -> Unit)? = null
    /** The first picture is on screen (hide the loading curtain). */
    var onFirstFrame: (() -> Unit)? = null
    /** The video or audio format changed (tracks picked, decoder input, video size): the stream badges ([StreamBadges]). */
    var onFormat: (() -> Unit)? = null

    /** onProgress only runs while the screen is visible. */
    var progressEnabled = false
        set(v) { field = v; scheduleTick() }

    // ---- loading ----

    /** Starts [src] at [startMs]. Replaces whatever was playing. */
    fun load(src: Source, startMs: Long, playWhenReady: Boolean = true) {
        StreamCache.playerOpening() // a prefetch still running never competes with the player for the host
        loadedAt = SystemClock.elapsedRealtime()
        source = src
        val bar = src.url.indexOf('|')
        streamUrl = if (bar > 0) src.url.substring(0, bar) else src.url
        headers = LinkedHashMap<String, String>().apply {
            putAll(src.headers)
            if (bar > 0) putAll(StreamUrls.headers(src.url.substring(bar + 1)))
        }
        subs.clear()
        subs.addAll(src.subtitles)
        forceHls = false
        retries = 0
        audioErrorShown = false
        lastError = null
        savedPosition = startMs
        savedPlay = playWhenReady
        releaseExo()
        prepare(startMs, playWhenReady)
    }

    /** Recreates the player after [releasePlayer], at the saved position. */
    fun restore(playWhenReady: Boolean) {
        if (player != null || source == null) return
        prepare(savedPosition, playWhenReady)
    }

    /** Frees the decoder and buffers (screen hidden); [restore] picks up where it was. */
    fun releasePlayer() {
        val p = player ?: return
        savedPosition = p.currentPosition
        savedPlay = p.playWhenReady
        releaseExo()
    }

    /** Drops the current stream (the next title is being resolved). Listeners and surface stay. */
    fun clear() {
        releaseExo()
        source = null
        streamUrl = ""
        savedPosition = 0
        lastError = null
    }

    /** Stops everything for good (screen destroyed). */
    fun release() {
        releaseExo()
        listeners.clear()
        onCues = null; onVideoSize = null; onFirstFrame = null; onPassthrough = null
        surfaceView = null
        source = null
    }

    val hasSource get() = source != null
    val url get() = streamUrl
    /** The request headers the stream is read with (the scrub preview frames ask the server the same way). */
    val requestHeaders: Map<String, String> get() = headers

    fun setSurfaceView(view: SurfaceView?) {
        surfaceView = view
        player?.setVideoSurfaceView(view)
    }

    private fun releaseExo() {
        main.removeCallbacks(tick)
        main.removeCallbacks(retryRun)
        main.removeCallbacks(boostRun)
        releaseBoost()
        passthrough = 0
        player?.let {
            it.removeListener(exoListener)
            it.release()
        }
        player = null
    }

    private fun prepare(startMs: Long, playWhenReady: Boolean) {
        val p = buildPlayer()
        player = p
        p.setVideoSurfaceView(surfaceView)
        p.setPlaybackParameters(PlaybackParameters(speed))
        p.setMediaItem(mediaItem(), startMs.coerceAtLeast(0))
        p.playWhenReady = playWhenReady
        p.prepare()
        logCapabilities()
        // The volume boost attaches to the new audio session right after the start, never in its way.
        boostMb = PlayerPrefs.boostMb(ctx)
        if (boostMb > 0) main.post(boostRun)
    }

    // ---- volume boost ----

    /** Extra loudness in millibels (0 = off): a [android.media.audiofx.LoudnessEnhancer] on the player's audio session. */
    var boostMb = 0
        private set
    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null
    private var enhancerSession = 0
    private val boostRun = Runnable { applyBoost() }

    /**
     * The audio leaves as a bitstream (Dolby / DTS passthrough to a soundbar or receiver): no effect can touch it.
     * 0 = PCM or not known yet, else the AudioTrack's encoding (C.ENCODING_*).
     */
    var passthrough = 0
        private set
    /** Called when [passthrough] changes (the Audio sheet greys the boost row out). */
    var onPassthrough: (() -> Unit)? = null

    /** Sets the boost and applies it at once to what plays. */
    fun setBoost(mb: Int) {
        boostMb = mb.coerceIn(0, PlayerPrefs.BOOST_MAX_MB)
        applyBoost()
    }

    private fun applyBoost() {
        val session = player?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET
        if (boostMb <= 0 || session == C.AUDIO_SESSION_ID_UNSET || session == 0) {
            if (enhancer != null) Log.w(TAG, "Player: volume boost off")
            releaseBoost()
            return
        }
        try {
            if (enhancer == null || enhancerSession != session) {
                releaseBoost()
                enhancer = android.media.audiofx.LoudnessEnhancer(session)
                enhancerSession = session
            }
            enhancer?.setTargetGain(boostMb)
            enhancer?.enabled = true
            Log.w(TAG, "Player: volume boost ${PlayerPrefs.boostLabel(boostMb)} ($boostMb mB) session $session" +
                if (passthrough != 0) " (bitstream audio: no effect)" else "")
        } catch (e: Throwable) {
            Log.w(TAG, "Player: volume boost unavailable on session $session: $e")
            releaseBoost()
        }
    }

    private fun releaseBoost() {
        val e = enhancer ?: return
        enhancer = null
        enhancerSession = 0
        try { e.enabled = false } catch (x: Throwable) {}
        try { e.release() } catch (x: Throwable) {}
    }

    /** A new audio session (new player, source or Up Next) and the AudioTrack's real output format. */
    private val audioWatch = object : AnalyticsListener {
        override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
            if (boostMb > 0) applyBoost()
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            val enc = audioTrackConfig.encoding
            val pcm = enc == C.ENCODING_PCM_16BIT || enc == C.ENCODING_PCM_FLOAT || enc == C.ENCODING_PCM_8BIT ||
                enc == C.ENCODING_PCM_24BIT || enc == C.ENCODING_PCM_32BIT || enc == C.ENCODING_INVALID
            val now = if (pcm) 0 else enc
            if (now != passthrough) {
                passthrough = now
                Log.w(TAG, "Player: audio out ${if (pcm) "PCM (volume boost applies)" else "bitstream, encoding $enc (volume boost can't apply)"}")
                onPassthrough?.invoke()
            }
        }

        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) { onFormat?.invoke() }

        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) { onFormat?.invoke() }
    }

    private fun buildPlayer(): ExoPlayer {
        val selector = DefaultTrackSelector(ctx)
        // The user's languages (Player menus, per profile) from the first frame, so nothing switches mid-start.
        val subLang = PlayerPrefs.subLang(ctx)
        selector.setParameters(
            selector.buildUponParameters()
                .setPreferredAudioLanguage(PlayerPrefs.audioLang(ctx) ?: Locale.getDefault().language)
                .setPreferredTextLanguage(subLang?.takeIf { it != PlayerPrefs.OFF })
                // Tunneling glitches on several MediaTek Fire TV builds; plain surface playback is stable.
                .setTunnelingEnabled(false)
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
        )
        // Settings › Sources & Quality › Dolby Audio to Your Soundbar (PlaybackPrefs): off = the TV decodes everything to PCM.
        val renderers = object : DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
                if (PlaybackPrefs.passthrough(context)) super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                else @Suppress("DEPRECATION") androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        // Debrid streams are fast but bursty: begin after 1 s (the first frame sooner, like a streaming app),
        // 3 s after a stall, then keep up to ~2 min ahead, capped in bytes (Java heap is 192 MB on this stick;
        // ExoPlayer's buffer lives there).
        val heap = Runtime.getRuntime().maxMemory()
        val targetBytes = (heap * 0.35).toLong().coerceIn(32L shl 20, 96L shl 20).toInt()
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 1_000, 3_000)
            .setTargetBufferBytes(targetBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(10_000, true)
            .build()
        val http = httpFactory(headers)
        // Streams Instant Play fetched ahead start from the disk cache (StreamCache); the rest comes over HTTP as before.
        val data: DataSource.Factory = DefaultDataSource.Factory(ctx, http).let { if (streamUrl.startsWith("http")) StreamCache.readOnlyFactory(ctx, it, streamUrl) else it }
        // An MKV whose index StreamCache can build from the bytes it fetched ahead skips the extractor's slow Cues read.
        val extractors: ExtractorsFactory = if (streamUrl.startsWith("http")) Extractors(ctx, streamUrl, http) else DefaultExtractorsFactory()
        val sources = DefaultMediaSourceFactory(data, extractors)
            .setLoadErrorHandlingPolicy(RetryPolicy())
        return ExoPlayer.Builder(ctx, renderers)
            .setTrackSelector(selector)
            .setLoadControl(load)
            .setMediaSourceFactory(sources)
            // Pauses on audio focus loss and when headphones/AirPods disconnect (the activity saves on pause).
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .also { it.addListener(exoListener); it.addAnalyticsListener(BtAudio.underrunLogger(ctx)); it.addAnalyticsListener(audioWatch) } // "Audio: underrun" marker
    }

    private fun mediaItem(): MediaItem {
        val lower = streamUrl.lowercase(Locale.US).substringBefore('?')
        val hint = source?.mimeHint?.lowercase(Locale.US)
        val mime = when {
            forceHls || lower.endsWith(".m3u8") || hint?.contains("mpegurl") == true -> MimeTypes.APPLICATION_M3U8
            lower.endsWith(".mpd") || hint == "application/dash+xml" -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        return MediaItem.Builder()
            .setUri(toUri(streamUrl))
            .apply { if (mime != null) setMimeType(mime) }
            .setSubtitleConfigurations(subs.map { subtitleConfig(it) })
            .build()
    }

    private fun subtitleConfig(s: SubtitleFile): MediaItem.SubtitleConfiguration {
        val ext = s.uri.lowercase(Locale.US).substringBefore('?').substringAfterLast('.', "")
        val mime = when (ext) {
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt", "webvtt" -> MimeTypes.TEXT_VTT
            "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val subsOff = PlayerPrefs.subLang(ctx) == PlayerPrefs.OFF
        return MediaItem.SubtitleConfiguration.Builder(toUri(s.uri))
            .setMimeType(mime)
            .setLanguage(languageCode(s.language))
            .setLabel(s.label)
            // Subtitles fetched for this title are wanted, unless the user turned subs off.
            .setSelectionFlags(if (subsOff || !s.asDefault) 0 else C.SELECTION_FLAG_DEFAULT)
            .setId((if (s.online) "dl:" else "ext:") + s.uri.hashCode())
            .build()
    }

    private fun toUri(s: String): Uri = if (s.startsWith("/")) Uri.fromFile(File(s)) else Uri.parse(s)

    // ---- PlayerEngine ----

    override val isPlaying get() = player?.isPlaying == true
    override val isBuffering get() = player?.playbackState == Player.STATE_BUFFERING
    override val positionMs get() = player?.currentPosition ?: savedPosition
    override val durationMs get() = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
    override val bufferedMs get() = player?.bufferedPosition ?: 0L

    override fun play() {
        val p = player ?: run { restore(true); return }
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        p.play()
    }

    override fun pause() { player?.pause() }

    override fun seekTo(ms: Long) {
        val p = player ?: run { savedPosition = ms; return }
        val d = durationMs
        p.seekTo(if (d > 0) ms.coerceIn(0, d) else ms.coerceAtLeast(0))
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed.coerceIn(0.25f, 2f)
        player?.setPlaybackParameters(PlaybackParameters(this.speed))
    }

    override fun tracks(kind: String): List<Track> {
        val p = player ?: return emptyList()
        val type = trackType(kind) ?: return emptyList()
        val out = ArrayList<Track>()
        // Every track of the stream, playable or not (the menu greys out what this TV can't decode).
        p.currentTracks.groups.forEachIndexed { gi, g ->
            if (g.type != type) return@forEachIndexed
            for (ti in 0 until g.length) {
                val f = g.getTrackFormat(ti)
                val supported = g.isTrackSupported(ti)
                if (kind == "audio") {
                    val lang = languageCode(f.language) ?: AudioLanguages.fromTrackName(f.label)
                    val title = audioTitle(f, lang)
                    val detail = audioDetail(f)
                    val label = if (detail.isEmpty()) title else "$title · $detail"
                    out.add(Track("$gi:$ti", kind, label, lang, g.isTrackSelected(ti), supported, title, detail))
                } else {
                    out.add(Track("$gi:$ti", kind, label(f, kind), languageCode(f.language), g.isTrackSelected(ti), supported))
                }
            }
        }
        // Same label twice (e.g. two English AC3 tracks): number them so they can be told apart.
        val counts = out.groupingBy { it.label }.eachCount()
        val seen = HashMap<String, Int>()
        return out.map { t ->
            if ((counts[t.label] ?: 0) < 2) t
            else {
                val n = " (" + seen.merge(t.label, 1, Int::plus) + ")"
                t.copy(label = t.label + n, title = t.title?.let { it + n })
            }
        }
    }

    /** "English", "Unknown Language", plus the track's own name when it says more ("English · Director's Commentary"). */
    private fun audioTitle(f: Format, lang: String?): String {
        val name = languageName(lang) ?: "Unknown Language"
        val own = f.label?.trim()?.takeIf { l -> l.isNotEmpty() && !AudioLanguages.isPlainTrackName(l, lang) && !l.equals(name, true) }
        return if (own != null) "$name · $own" else name
    }

    /** "Dolby Digital 5.1", "AAC Stereo", plus Commentary / Audio Description from the track's flags. */
    private fun audioDetail(f: Format): String {
        val parts = ArrayList<String>()
        listOfNotNull(audioCodecLong(f), channelsLong(f.channelCount)).joinToString(" ").takeIf { it.isNotBlank() }?.let { parts.add(it) }
        if (f.roleFlags and C.ROLE_FLAG_COMMENTARY != 0 && f.label?.contains("comment", true) != true) parts.add("Commentary")
        if (f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0) parts.add("Audio Description")
        return parts.joinToString(" · ")
    }

    override fun selectTrack(kind: String, id: String?) {
        val p = player ?: return
        val type = trackType(kind) ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (id == null) {
            if (type != C.TRACK_TYPE_TEXT) return
            p.trackSelectionParameters = b.clearOverridesOfType(type).setTrackTypeDisabled(type, true).build()
            return
        }
        val parts = id.split(':')
        val g = p.currentTracks.groups.getOrNull(parts.getOrNull(0)?.toIntOrNull() ?: return) ?: return
        val ti = parts.getOrNull(1)?.toIntOrNull() ?: return
        if (g.type != type || ti !in 0 until g.length) return
        if (type == C.TRACK_TYPE_AUDIO && !g.isTrackSupported(ti)) return // can't be decoded here; keep the current one
        p.trackSelectionParameters = b.setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, ti)).build()
        // (Remembering the choice for the next title is the controls' job: PlayerPrefs, read in buildPlayer.)
    }

    override fun addExternalSubtitle(uri: String, language: String?, label: String?) {
        val path = uri
        if (subs.any { it.uri == path }) return
        subs.add(SubtitleFile(path, language ?: StreamUrls.langFromName(path), label))
        val p = player ?: return
        // Subtitle files are part of the media item: swap it in place (keeps the position; a brief rebuffer).
        val playing = p.playWhenReady
        p.setMediaItem(mediaItem(), false)
        p.prepare()
        p.playWhenReady = playing
    }

    /** Several at once, with one swap of the media item (Dream Subtitles: [DreamSubtitles.Session] picks a moment it can't be seen). */
    fun addExternalSubtitles(list: List<SubtitleFile>) {
        val add = list.filter { s -> subs.none { it.uri == s.uri } }
        if (add.isEmpty()) return
        subs.addAll(add)
        val p = player ?: return
        val playing = p.playWhenReady
        p.setMediaItem(mediaItem(), false)
        p.prepare()
        p.playWhenReady = playing
    }

    override fun videoInfo(): String {
        val p = player ?: return ""
        val parts = ArrayList<String>()
        p.videoFormat?.let { v ->
            if (v.height > 0) parts.add(resolution(v.width, v.height))
            videoCodec(v)?.let { parts.add(it) }
            hdr(v)?.let { parts.add(it) }
        }
        p.audioFormat?.let { a -> parts.add(listOfNotNull(audioCodecShort(a), channels(a.channelCount)).joinToString(" ")) }
        return parts.filter { it.isNotBlank() }.joinToString(" · ")
    }

    override fun addListener(l: PlayerEngine.Listener) { if (l !in listeners) listeners.add(l) }
    override fun removeListener(l: PlayerEngine.Listener) { listeners.remove(l) }

    /** The last error message (null while fine). */
    val error get() = lastError

    // ---- ExoPlayer events ----

    private val exoListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            scheduleTick()
            fire { it.onState() }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Another app may re-open its audio sink right as playback starts, a moment before or after the player
            // asks for focus: a focus loss this early is nobody pausing. Ask again.
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS && SystemClock.elapsedRealtime() - loadedAt < 5_000) {
                Log.w(TAG, "audio focus lost right after the start; playing on")
                main.post { player?.play() }
            }
            fire { it.onState() }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) { retries = 0; lastError = null }
            fire { it.onState() }
            if (state == Player.STATE_ENDED) fire { it.onEnded() }
        }

        override fun onTracksChanged(tracks: Tracks) {
            logAudioTracks(tracks)
            checkAudio(tracks)
            fire { it.onTracks() }
            onFormat?.invoke()
        }

        override fun onCues(cueGroup: CueGroup) { onCues?.invoke(cueGroup.cues) }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) onVideoSize?.invoke(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
            onFormat?.invoke()
        }

        override fun onRenderedFirstFrame() { onFirstFrame?.invoke() }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            Log.w(TAG, "dream player: error ${error.errorCodeName} ${error.cause}")
            when {
                // Not a recognized container and no type hint: many debrid/IPTV HLS links have no .m3u8 in the path.
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && !forceHls &&
                    source?.mimeHint == null && !streamUrl.lowercase(Locale.US).substringBefore('?').endsWith(".mpd") -> {
                    forceHls = true
                    val pos = p.currentPosition
                    p.setMediaItem(mediaItem(), pos)
                    p.prepare()
                }
                error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                    p.seekToDefaultPosition()
                    p.prepare()
                }
                transient(error) && retries < MAX_RETRIES -> {
                    retries++
                    Log.w(TAG, "dream player: retry $retries/$MAX_RETRIES after ${error.errorCodeName}")
                    main.postDelayed(retryRun, 1500L * retries)
                    fire { it.onState() }
                }
                else -> {
                    val msg = describe(error)
                    lastError = msg
                    fire { it.onError(msg) }
                }
            }
        }
    }

    private val retryRun = Runnable { player?.prepare() }

    private fun transient(e: PlaybackException): Boolean {
        val code = e.errorCode
        if (code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            code == PlaybackException.ERROR_CODE_TIMEOUT
        ) return true
        val http = httpCode(e)
        return http != null && (http >= 500 || http == 408 || http == 429)
    }

    private fun httpCode(e: Throwable?): Int? {
        var t = e
        while (t != null) {
            if (t is HttpDataSource.InvalidResponseCodeException) return t.responseCode
            t = t.cause
        }
        return null
    }

    private var loggedAudio = ""

    /** Every audio stream in the file, once per stream (logcat "dream player: audio"), to compare with what the menu lists. */
    private fun logAudioTracks(tracks: Tracks) {
        val parts = ArrayList<String>()
        for (g in tracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            for (ti in 0 until g.length) {
                val f = g.getTrackFormat(ti)
                parts += "${f.language ?: "und"} ${f.sampleMimeType?.substringAfter('/') ?: "?"} ${f.channelCount}ch" +
                    (f.label?.let { " '$it'" } ?: "") + (if (g.isTrackSelected(ti)) " (playing)" else "") +
                    (if (!g.isTrackSupported(ti)) " (can't decode)" else "")
            }
        }
        if (parts.isEmpty()) return
        val line = parts.joinToString(" | ")
        if (line == loggedAudio) return
        loggedAudio = line
        Log.w(TAG, "dream player: audio ${parts.size} track(s): $line")
    }

    /** Audio exists but none of it can be decoded here (no passthrough for DTS/TrueHD): say so instead of playing silent. */
    private fun checkAudio(tracks: Tracks) {
        if (audioErrorShown) return
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audio.isEmpty() || audio.any { g -> (0 until g.length).any { g.isTrackSupported(it) } }) return
        audioErrorShown = true
        val name = audio.firstOrNull()?.takeIf { it.length > 0 }?.getTrackFormat(0)?.let { audioCodecLong(it) } ?: "this format"
        val msg = "This source's audio ($name) can't play on this TV. Try another source."
        lastError = msg
        player?.pause()
        fire { it.onError(msg) }
    }

    private fun describe(e: PlaybackException): String {
        val http = httpCode(e)
        return when {
            http == 401 || http == 403 -> "The source refused the stream (HTTP $http). The link may have expired."
            http == 404 || http == 410 || e.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "The file is no longer available at this source."
            http != null -> "The source answered with an error (HTTP $http)."
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Couldn't reach the stream. Check your connection, or try another source."
            e.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "This stream isn't allowed over plain HTTP."
            e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "This file format isn't supported."
            e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "This file is damaged or incomplete."
            e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                e.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                "This video's format can't be decoded on this device."
            e.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED -> "The video couldn't be decoded."
            e.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "The audio output failed to start."
            e.errorCode in 6000..6999 -> "This stream is copy-protected and can't play here."
            else -> "Playback failed (${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase(Locale.US).replace('_', ' ')})."
        }
    }

    /**
     * Media3's stock extractors, with the Matroska one told to leave the Cues alone when [StreamCache.cuesAhead]
     * can build the index itself. Stock, MatroskaExtractor reads the Cues before the first cluster through its
     * EBML reader a few bytes per call: a seek to the file's end, ~0.35 s of reading for a 31 KB index on this
     * stick, a seek back, all before the player is prepared. With the flag it reaches the first cluster right
     * after the Tracks and reports the stream unseekable; [CuesAhead] swaps in the index StreamCache parses from
     * the same bytes in one read (milliseconds) at that very moment, so the player is prepared with the full
     * index exactly as before: seeking, a resume start, the duration and Instant Play are unchanged. A link
     * whose head isn't on disk, or an MKV without a usable SeekHead, gets the stock extractor.
     */
    private class Extractors(private val ctx: Context, private val url: String, private val http: DataSource.Factory) : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> = createExtractors(Uri.EMPTY, emptyMap())

        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
            val ahead = StreamCache.cuesAhead(ctx, url, http)
            val factory = DefaultExtractorsFactory()
            if (ahead) factory.setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
            val list = factory.createExtractors(uri, responseHeaders)
            if (!ahead) return list
            return Array(list.size) { i ->
                val e = list[i]
                if (e.underlyingImplementation is MatroskaExtractor) CuesAhead(e) { StreamCache.mkvSeekMap(ctx, url, http) } else e
            }
        }
    }

    /** [inner] with its first seek map replaced by [index]'s when the extractor reports the stream unseekable (it skipped the Cues). */
    private class CuesAhead(private val inner: Extractor, private val index: () -> SeekMap?) : Extractor {
        override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)
        override fun getSniffFailureDetails(): List<SniffFailure> = inner.sniffFailureDetails

        override fun init(output: ExtractorOutput) = inner.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = output.track(id, type)
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) {
                // Seekable already (Cues ahead of the clusters, parsed inline): kept. Unseekable: ours, when it can be built.
                output.seekMap(if (seekMap.isSeekable) seekMap else (index() ?: seekMap))
            }
        })

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)
        override fun seek(position: Long, timeUs: Long) = inner.seek(position, timeUs)
        override fun release() = inner.release()
        override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
    }

    private class RetryPolicy : DefaultLoadErrorHandlingPolicy(6) {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            var t: Throwable? = info.exception
            while (t != null) {
                // Expired/forbidden/missing links won't fix themselves: fail fast so the user can pick another source.
                if (t is HttpDataSource.InvalidResponseCodeException && t.responseCode in 400..499 &&
                    t.responseCode != 408 && t.responseCode != 429
                ) return C.TIME_UNSET
                t = t.cause
            }
            val base = super.getRetryDelayMsFor(info)
            return if (base == C.TIME_UNSET) base else minOf(500L + 750L * (info.errorCount - 1), 4_000L)
        }
    }

    // ---- progress ----

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!p.isPlaying || !progressEnabled) return
            val pos = p.currentPosition
            fire { it.onProgress(pos) }
            main.postDelayed(this, PROGRESS_MS)
        }
    }

    private fun scheduleTick() {
        main.removeCallbacks(tick)
        if (progressEnabled && player?.isPlaying == true) main.postDelayed(tick, PROGRESS_MS)
    }

    private inline fun fire(crossinline f: (PlayerEngine.Listener) -> Unit) {
        for (l in listeners) try { f(l) } catch (e: Exception) { Log.w(TAG, "dream player: listener failed: $e") }
    }

    // ---- labels ----

    private fun trackType(kind: String) = when (kind) {
        "audio" -> C.TRACK_TYPE_AUDIO
        "text" -> C.TRACK_TYPE_TEXT
        "video" -> C.TRACK_TYPE_VIDEO
        else -> null
    }

    private fun label(f: Format, kind: String): String {
        val parts = ArrayList<String>()
        val lang = languageName(f.language)
        parts.add(lang ?: "Unknown Language")
        if (kind == "audio") {
            listOfNotNull(audioCodecLong(f), channels(f.channelCount)).joinToString(" ").takeIf { it.isNotBlank() }?.let { parts.add(it) }
            if (f.roleFlags and C.ROLE_FLAG_COMMENTARY != 0) parts.add("Commentary")
            if (f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0) parts.add("Audio Description")
        } else if (kind == "text") {
            if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0 || f.label?.contains("forced", true) == true) parts.add("Forced")
            if (f.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0 || f.label?.contains("sdh", true) == true) parts.add("SDH")
            if (f.id?.startsWith("ext:") == true || f.id?.contains(":ext:") == true) parts.add("External")
        }
        // The track's own name ("Director's commentary", "Signs & Songs") when it says more than the above.
        f.label?.trim()?.takeIf { l ->
            l.isNotEmpty() && parts.none { it.equals(l, true) } && !l.equals(f.language, true) &&
                !(lang != null && l.equals(lang, true)) && !l.equals("forced", true) && !l.equals("sdh", true)
        }?.let { parts.add(it) }
        return parts.joinToString(" · ")
    }

    companion object {
        private const val TAG = "FireHome"
        private const val PROGRESS_MS = 500L
        private const val MAX_RETRIES = 4
        /** The User-Agent streams are read with, unless the URL brings its own. */
        private const val DEFAULT_UA = "DreamOS/4.0 (Linux; Android 9; AFTKA) DreamPlayer"

        /** The HTTP source the player reads streams with ([headers] from the URL); StreamCache prefetches with the same. */
        internal fun httpFactory(headers: Map<String, String>): DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
            .setUserAgent(headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: DEFAULT_UA)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        private var capsLogged = false

        fun languageName(code: String?): String? {
            val c = languageCode(code) ?: return null
            val name = Locale.forLanguageTag(c).getDisplayLanguage(Locale.getDefault())
            return name.takeIf { it.isNotBlank() && !it.equals(c, true) }?.replaceFirstChar { it.titlecase(Locale.getDefault()) } ?: code
        }

        /** ISO code from a code ("eng", "en-US") or an English name ("English"); null for und/unknown. */
        fun languageCode(lang: String?): String? {
            val l = lang?.trim()?.takeIf { it.isNotEmpty() && !it.equals("und", true) && !it.equals("unknown", true) } ?: return null
            if (l.length > 3 && !l.contains('-') && !l.contains('_')) {
                Locale.getISOLanguages().firstOrNull { Locale(it).getDisplayLanguage(Locale.ENGLISH).equals(l, true) }?.let { return it }
                return l
            }
            val loc = Locale.forLanguageTag(l.replace('_', '-'))
            return loc.language.ifEmpty { l }.lowercase(Locale.US).let { code ->
                // 3-letter codes to 2-letter where one exists ("eng" -> "en").
                if (code.length == 3) Locale.getISOLanguages().firstOrNull { Locale(it).isO3Language == code } ?: code else code
            }
        }

        fun resolution(w: Int, h: Int): String = when {
            h >= 2000 || w >= 3800 -> "2160p"
            h >= 1400 || w >= 2500 -> "1440p"
            h >= 1000 || w >= 1900 -> "1080p"
            h >= 700 || w >= 1260 -> "720p"
            else -> "${h}p"
        }

        fun videoCodec(f: Format): String? = when (f.sampleMimeType) {
            MimeTypes.VIDEO_H265 -> "HEVC"
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_VP9 -> "VP9"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
            MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
            MimeTypes.VIDEO_VP8 -> "VP8"
            null -> null
            else -> f.sampleMimeType!!.substringAfter('/').uppercase(Locale.US)
        }

        fun hdr(f: Format): String? = when {
            f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || f.codecs?.startsWith("dvh") == true -> "Dolby Vision"
            f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
            f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }

        fun audioCodecLong(f: Format): String? = when (f.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
            MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
            MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
            MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            MimeTypes.AUDIO_DTS -> "DTS"
            MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
            MimeTypes.AUDIO_DTS_X -> "DTS:X"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_VORBIS -> "Vorbis"
            MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MP3"
            MimeTypes.AUDIO_RAW -> "PCM"
            null -> null
            else -> f.sampleMimeType!!.substringAfter('/').uppercase(Locale.US)
        }

        fun audioCodecShort(f: Format): String? = when (f.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> "AC3"
            MimeTypes.AUDIO_E_AC3 -> "E-AC3"
            MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3 Atmos"
            MimeTypes.AUDIO_TRUEHD -> "TrueHD"
            MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            else -> audioCodecLong(f)
        }

        /** Channels as the audio menu says them: Mono, Stereo, 5.1, 7.1. */
        fun channelsLong(n: Int): String? = when (n) {
            Format.NO_VALUE, 0 -> null
            1 -> "Mono"
            2 -> "Stereo"
            else -> channels(n)
        }

        fun channels(n: Int): String? = when (n) {
            Format.NO_VALUE, 0 -> null
            1 -> "Mono"
            2 -> "2.0"
            3 -> "2.1"
            6 -> "5.1"
            7 -> "6.1"
            8 -> "7.1"
            else -> "${n}ch"
        }
    }

    private fun logCapabilities() {
        if (capsLogged) return
        capsLogged = true
        try {
            @Suppress("DEPRECATION")
            val caps = AudioCapabilities.getCapabilities(ctx)
            val enc = listOf("AC3" to C.ENCODING_AC3, "E-AC3" to C.ENCODING_E_AC3, "E-AC3-JOC" to C.ENCODING_E_AC3_JOC,
                "TrueHD" to C.ENCODING_DOLBY_TRUEHD, "DTS" to C.ENCODING_DTS, "DTS-HD" to C.ENCODING_DTS_HD)
                .filter { caps.supportsEncoding(it.second) }.joinToString { it.first }
            Log.w(TAG, "dream player: audio out ${caps.maxChannelCount}ch, passthrough: ${enc.ifEmpty { "none (decoding to PCM)" }}")
        } catch (e: Exception) {}
    }
}
