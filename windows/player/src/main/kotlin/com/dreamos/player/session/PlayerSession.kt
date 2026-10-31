package com.dreamos.player.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dreamos.player.api.CastItem
import com.dreamos.player.api.EpisodeItem
import com.dreamos.player.api.PersonItem
import com.dreamos.desktop.core.playback.PlayRequest
import com.dreamos.desktop.core.playback.Playback
import com.dreamos.desktop.core.playback.PlaybackApi
import com.dreamos.desktop.core.playback.SourceRow
import com.dreamos.desktop.core.playback.PlayTarget
import com.dreamos.desktop.core.playback.MediaKind
import com.dreamos.desktop.core.playback.PlaybackError
import com.dreamos.player.api.PlayState
import com.dreamos.player.api.PlayerDetails
import com.dreamos.player.api.PlayerEvents
import com.dreamos.player.api.PlayerServices
import com.dreamos.player.api.RecItem
import com.dreamos.player.api.SeasonItem
import com.dreamos.player.api.SkipMarker
import com.dreamos.desktop.core.playback.Stream
import com.dreamos.player.engine.EngineEvent
import com.dreamos.player.engine.EngineState
import com.dreamos.player.engine.MpvEngine
import com.dreamos.player.engine.PlayerLog
import com.dreamos.player.engine.Track
import com.dreamos.player.engine.Tracks
import com.dreamos.desktop.core.playback.SkipChapter
import com.dreamos.desktop.core.playback.SkipKind
import com.dreamos.desktop.core.playback.SkipLearner
import com.dreamos.player.subs.DreamSubtitles
import com.dreamos.player.ui.SEP
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One Dream Player session: what plays, the loading curtain, fallbacks, progress for Continue Watching, skip intro,
 * post-play, sheets, toasts. Ported from fire-home DreamPlayerActivity / PlayerOverlay / PostPlay / SkipMarkers and the
 * iPhone's PlayerSession (strings and timings: iOS docs/port/player-spec.md). Lives on the UI thread; all Compose state.
 */
class PlayerSession(
    first: PlayRequest,
    val playback: PlaybackApi,
    val services: PlayerServices,
    private val events: PlayerEvents,
    val engine: MpvEngine,
    /**
     * A trailer (the Fire Stick's trailer mode): Play / Pause, the timeline and the window buttons only; no history,
     * Trakt, skip markers, subtitles download, previews, Change Source or post-play; the end closes.
     */
    val trailer: Boolean = false,
    /** A trailer link's sound when it comes as its own audio-only link (mpv plays it in step with the picture). */
    private val trailerAudio: String? = null,
    private val onClose: () -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    // ---- What plays ----
    var request by mutableStateOf(first); private set
    val target: PlayTarget get() = request.target
    var details by mutableStateOf<PlayerDetails?>(null); private set
    var engineState by mutableStateOf(EngineState()); private set

    sealed interface Curtain {
        data object Loading : Curtain
        data class Error(val title: String, val message: String, val canSearchAll: Boolean) : Curtain
    }
    /** Non-null while the curtain is up (loading, a source switch, an error). */
    var curtain by mutableStateOf<Curtain?>(Curtain.Loading); private set
    /** The curtain's note line (CAM / best version), amber when [noteCam]. */
    var curtainNote by mutableStateOf<String?>(null); private set
    var noteCam by mutableStateOf(false); private set

    // ---- Overlay state ----
    var controlsShown by mutableStateOf(true)
    var lastInputAt by mutableLongStateOf(System.currentTimeMillis()); private set
    var pauseScreen by mutableStateOf(false); private set
    var sheet by mutableStateOf<Sheet?>(null)
    var toast by mutableStateOf<Toast?>(null); private set
    var flash by mutableStateOf<Flash?>(null); private set
    var seekBubble by mutableStateOf<Int?>(null); private set
    var volumeHud by mutableStateOf<Long?>(null); private set
    var prefs by mutableStateOf(PlayerPrefs.load()); private set

    enum class Sheet { AUDIO_SUBS, PLAYBACK, INFO, EPISODES, SOURCE, XRAY }
    data class Toast(val text: String, val status: Status = Status.PLAIN, val id: Long = System.nanoTime())
    enum class Status { PLAIN, AMBER }
    data class Flash(val playing: Boolean, val id: Long = System.nanoTime())

    // ---- Skip ----
    var markers by mutableStateOf<List<SkipMarker>>(emptyList()); private set
    /** The pill offered now ("Skip Intro", "Skip Recap", "Skip Credits") and where it seeks. */
    data class SkipOffer(val marker: SkipMarker, val label: String, val toMs: Long)
    var skipOffer by mutableStateOf<SkipOffer?>(null); private set
    /** "Intro Skipped": shown where the Skip pill lives for 1.6 s after an automatic skip. */
    var skipNotice by mutableStateOf<String?>(null); private set
    private var skipNoticeUntil = 0L
    private val skipped = HashSet<SkipMarker.Kind>()
    /** Core's learner for this item: where the viewer jumps past an intro (fed ~2x a second). */
    private var learner: SkipLearner? = null
    private var learnerTickAt = 0L

    // ---- Post-play ----
    sealed interface PostPlay {
        data class UpNext(val next: PlayTarget, val nextDetails: PlayerDetails?, val countdownFrom: Long?) : PostPlay
        data class MoreLikeThis(val recs: List<RecItem>, val ended: Boolean) : PostPlay
        data object StillWatching : PostPlay
    }
    var postPlay by mutableStateOf<PostPlay?>(null); private set
    var nextEpisode by mutableStateOf<PlayTarget?>(null); private set
    /** The next episode's details, fetched 90 s ahead (Up Next reads them as they land). */
    var nextDetails by mutableStateOf<PlayerDetails?>(null); private set
    private var recs: List<RecItem> = emptyList()
    private var postPlayDismissedAt = -1L
    private var autoplayedInARow = 0
    private var endedAt = 0L

    // ---- Sheets' data ----
    var seasons by mutableStateOf<List<SeasonItem>>(emptyList()); private set
    var episodes by mutableStateOf<List<EpisodeItem>>(emptyList()); private set
    var episodesSeason by mutableStateOf<Int?>(null); private set
    /** The shown season's list has come back (empty or not). */
    var episodesLoaded by mutableStateOf(false); private set
    /** Saved progress of the listed episodes (read with the list, off the UI thread). */
    var watched by mutableStateOf<Map<String, Float>>(emptyMap()); private set
    var sources by mutableStateOf<List<SourceRow>?>(null); private set
    var sourcesLoose by mutableStateOf(false); private set
    var sourcePending by mutableStateOf<String?>(null); private set
    var sourceFailed by mutableStateOf<String?>(null); private set
    var cast by mutableStateOf<List<CastItem>?>(null); private set
    var person by mutableStateOf<Pair<CastItem, PersonItem?>?>(null); private set
    private var resumeAfterPerson = false

    // ---- Progress ----
    private var stream: Stream = first.stream
    private val fallbacks = ArrayDeque<Stream>()
    private var realWatchedMs = 0L
    private var lastTickPos = -1L
    private var lastTickAt = 0L
    private var lastSaveAt = 0L
    private var finished = false
    private var userPickedAudio = false
    private var userPickedSub = false
    private var autoAppliedAudio = false
    private var lastSubIds = ""
    private var openJob: Job? = null
    private val firstUrl = first.stream.url
    // Dream Subtitles: the list for [dsFor], added once per loaded file.
    private var dsPrefs = DreamSubtitles.prefs(prefs)
    private var dsFor: String? = null
    private var dsList: List<DreamSubtitles.Sub>? = null
    private var dsJob: Job? = null
    private var dsAdded = false
    /** Audio in another language turned subtitles on (or found none): said once per item. */
    private var foreignSaid = false
    private var curtainTimeout: Job? = null
    /** When the curtain went up for this Play: finding the stream and its first picture share one deadline. */
    private var curtainSince = 0L
    /** A link was loaded and its first picture is still to come (set at every load, cleared by that picture). */
    private var awaitingFrame = false
    /** Ticks the engine's state has said "first frame" while its signal hasn't come (a lost signal is healed). */
    private var unsignalledFrameTicks = 0
    private var closed = false
    private var switches = 0
    /** A link that ended before its first picture from a saved spot is tried once from the beginning (DreamPlayerActivity.onEnded). */
    private var retriedFromTop = false
    private var noPictureEndAt = 0L

    init {
        scope.launch { engine.state.collect { s -> engineState = s; onTick(s) } }
        scope.launch { engine.events.collect { onEngineEvent(it) } }
        scope.launch { while (isActive) { delay(1000); everySecond() } }
        applyPrefs()
        playback.playerActive(true)
        // A request without a stream (DreamPlayer.play): the curtain shows at once while Dream Core resolves it.
        when {
            first.stream.url.isEmpty() -> playOther(first.target, fromStart = first.startMs)
            else -> open(first, keepPosition = null, fromApp = true)
        }
    }

    // ================= Opening, fallbacks, switching =================

    /**
     * Plays [req]'s stream. [keepPosition]: a switch of source for the same item (the spot is kept). [fromApp]: a
     * request the app made (DreamPlayer.open: a source picked on the title page): startMs 0 then means "where the
     * watch history says" (as DreamPlayer.play), 1 from the beginning; the picked stream is always the one played.
     * [resolved]: Dream Core just found it behind the curtain (Play, Try Again): the curtain's deadline goes on.
     */
    private fun open(req: PlayRequest, keepPosition: Long?, fromApp: Boolean = false, resolved: Boolean = false) {
        openJob?.cancel()
        request = req
        stream = req.stream
        fallbacks.clear(); fallbacks.addAll(req.alternates)
        switches = 0
        retriedFromTop = false
        curtain = Curtain.Loading
        curtainNote = req.stream.note
        noteCam = req.stream.cam
        finished = false
        learner?.end(); learner = null
        skipped.clear(); skipOffer = null; markers = emptyList()
        postPlay = null; postPlayDismissedAt = -1L
        userPickedAudio = false; userPickedSub = false; autoAppliedAudio = false; lastSubIds = ""
        foreignSaid = false
        if (keepPosition == null) {
            details = null; nextEpisode = null; nextDetails = null; recs = emptyList(); cast = null
            realWatchedMs = 0
            endedAt = 0
            loadDetails(req.target)
        }
        playback.handing(req.stream)
        awaitingFrame = false
        armCurtainTimeout(fresh = !resolved)
        dsAdded = false
        startDreamSubtitles(req.target)
        if (fromApp && keepPosition == null && req.startMs == 0L && !trailer) {
            openJob = scope.launch {
                val spot = withContext(Dispatchers.IO) { withTimeoutOrNull(3000) { runCatching { services.historyStart(req.target) }.getOrNull() } } ?: 0L
                request = req.copy(startMs = spot)
                load(req.url, req.headers, spot, audioFor(req))
            }
        } else {
            load(req.url, req.headers, keepPosition ?: req.startMs, audioFor(req))
        }
    }

    /** Every link the session plays goes through here: its first picture is awaited from now. */
    private fun load(url: String, headers: Map<String, String>, startMs: Long, audioUrl: String? = null) {
        awaitingFrame = true
        unsignalledFrameTicks = 0
        engine.load(url, headers, startMs, audioUrl)
        engine.play()
    }

    private fun loadDetails(t: PlayTarget) {
        scope.launch {
            details = withContext(Dispatchers.IO) { withTimeoutOrNull(8000) { runCatching { services.details(t) }.getOrNull() } }
            if (trailer) return@launch
            nextEpisode = if (t.isEpisode) withContext(Dispatchers.IO) { withTimeoutOrNull(8000) { runCatching { services.nextEpisode(t) }.getOrNull() } } else null
        }
    }

    /**
     * The curtain never waits forever. 45 s (a trailer 25 s) from when it went up, for finding the stream and its first
     * frame together, 15 s more (3 times at most) while the stream is visibly coming in, then the next source or the
     * error. After 12 s with no picture while another ready link is in hand, that one plays instead
     * (DreamPlayerActivity.startWatch). Armed every time the curtain goes up (Play, Try Again, another source; [fresh]
     * starts the clock again, else the Play's clock goes on); cancelled by the first frame, the error and closing. It
     * runs on the UI thread's timer, so a lookup stuck on a background thread can't hold it up.
     */
    private fun armCurtainTimeout(fresh: Boolean = true) {
        curtainTimeout?.cancel()
        val armedAt = monoMs()
        if (fresh || curtainSince == 0L) curtainSince = armedAt
        curtainTimeout = scope.launch {
            var limit = if (trailer) 25_000L else curtainMs
            var extended = 0
            var buffered = 0L
            var startWatched = false
            while (true) {
                delay(1000)
                val es = engine.state.value
                val now = monoMs()
                if (!startWatched && awaitingFrame && now - armedAt >= START_WATCH_MS) {
                    startWatched = true
                    if (!trailer && !es.firstFrame && fallbacks.isNotEmpty() && switches < MAX_SWITCHES) {
                        failStream("no first frame after ${START_WATCH_MS / 1000} s and another source is ready")
                        return@launch
                    }
                }
                if (now - curtainSince < limit) continue
                if (awaitingFrame && es.bufferedToMs > buffered && extended < 3) { buffered = es.bufferedToMs; limit += 15_000; extended++; continue }
                curtainExpired((now - curtainSince) / 1000)
                return@launch
            }
        }
    }

    /** The curtain's deadline passed with the curtain still up: the picture, the next source or the error, never a wait. */
    private fun curtainExpired(waitedS: Long) {
        if (curtain != Curtain.Loading) return
        val es = engine.state.value
        val phase = when {
            !awaitingFrame -> if (openJob?.isActive == true) "finding a stream" else "a stream to load"
            es.firstFrame -> "the first frame's signal (the picture is up)"
            es.bufferedToMs > 0 -> "the first frame (buffered to ${es.bufferedToMs / 1000} s)"
            else -> "the first frame (nothing buffered)"
        }
        PlayerLog.w("Session: ${target.label}: curtain deadline after $waitedS s, still waiting for $phase")
        when {
            awaitingFrame && es.firstFrame -> onFirstFrame()
            awaitingFrame -> failStream("no first frame after $waitedS s")
            else -> {
                openJob?.cancel(); openJob = null
                // Try Again and the next Play start a fresh lookup instead of waiting on this one.
                playback.abandon(target)
                showError(Playback.NOT_READY)
            }
        }
    }

    private fun monoMs() = System.nanoTime() / 1_000_000

    /** The error the curtain shows once the quiet switches are spent ([damaged]: the file ended before any picture). */
    private fun failStream(why: String, damaged: Boolean = false) {
        PlayerLog.w("Session: ${target.label}: stream failed ($why)")
        playback.failed(target, stream)
        val keep = if (engineState.firstFrame || request.startMs > 0) engine.positionMs.takeIf { it > 0 } ?: request.startMs else null
        // At most 3 silent switches per item (core's rule, the iPhone's), then the error with its ways forward.
        val next = if (++switches > MAX_SWITCHES) null else fallbacks.removeFirstOrNull() ?: playback.nextStream(target)
        if (next != null) {
            PlayerLog.i("Session: falling back to the next ready link, keeping ${(keep ?: 0) / 1000}s")
            saveProgress(force = true)
            val req = request.copy(stream = next, alternates = fallbacks.toList(), localUrl = null)
            curtain = Curtain.Loading
            stream = next
            request = req
            playback.handing(next)
            dsAdded = false
            load(req.url, req.headers, keep ?: 0)
            armCurtainTimeout()
        } else showError(PlaybackError("This Title Isn't Available Right Now", if (damaged) DAMAGED_TEXT else "Try again in a moment, or choose another source."))
    }

    internal fun showError(e: PlaybackError) {
        curtainTimeout?.cancel()
        awaitingFrame = false
        engine.stop()
        curtain = if (trailer) Curtain.Error("Trailer Unavailable", "This trailer can't be played right now. Please try again in a moment.", false)
        else Curtain.Error(e.title, e.message ?: "", canSearchAll = true)
    }


    /** [trailerAudio] belongs to the link it came with (a fallback link carries its own sound). */
    private fun audioFor(req: PlayRequest): String? = trailerAudio?.takeIf { trailer && req.stream.url == firstUrl }

    /** Curtain's Try Again: the failed link is forgotten, Dream Core resolves again from the same spot. */
    fun tryAgain(loose: Boolean = false) {
        val spot = engine.positionMs.takeIf { it > 0 } ?: request.startMs
        curtain = Curtain.Loading
        openJob?.cancel()
        // A fresh lookup, never the one that just failed or got stuck.
        playback.abandon(target)
        awaitingFrame = false
        armCurtainTimeout()
        openJob = scope.launch {
            try {
                val req = withContext(Dispatchers.IO) { if (loose) playback.playLoose(target, spot) else playback.play(target, spot) }
                open(req.copy(startMs = spot), keepPosition = spot, resolved = true)
            } catch (e: CancellationException) { throw e } catch (e: PlaybackError) { showError(e) } catch (e: Exception) {
                PlayerLog.e("Session: try again failed", e)
                showError(PlaybackError("Couldn't Find a Ready Stream", "Try again in a moment, or choose another source."))
            }
        }
    }

    /** The app opened another request while this one plays (or sits in the mini player): it plays here instead. */
    fun replace(req: PlayRequest) {
        saveProgress(force = true)
        autoplayedInARow = 0
        sheet = null
        events.nowPlaying(req.target)
        open(req, keepPosition = null, fromApp = true)
    }

    /** Another title or episode in this same player (Next Episode, Up Next, Episodes, More Like This). */
    fun playOther(t: PlayTarget, startMs: Long? = null, automatic: Boolean = false, fromStart: Long? = null) {
        // Moving on past 80% counts this one as watched (DreamPlayerActivity.playNext).
        engineState.let { es -> if (!finished && es.firstFrame && es.durationMs > 0 && engine.positionMs > es.durationMs * 0.8) finished = true }
        saveProgress(force = true)
        stopScrobble()
        if (!automatic) autoplayedInARow = 0
        sheet = null; postPlay = null
        engine.pause()
        request = request.copy(target = t)
        details = null
        curtain = Curtain.Loading
        curtainNote = null
        endedAt = 0
        awaitingFrame = false
        loadDetails(t)
        events.nowPlaying(t)
        openJob?.cancel()
        armCurtainTimeout()
        openJob = scope.launch {
            try {
                // A trailer never asks the watch history (that is the title's own spot).
                val start = startMs ?: fromStart?.takeIf { it > 0 } ?: if (trailer) 0L else withContext(Dispatchers.IO) { services.historyStart(t) }
                val req = withContext(Dispatchers.IO) { playback.play(t, start) }
                open(req.copy(startMs = start), keepPosition = null, resolved = true)
            } catch (e: CancellationException) { throw e } catch (e: PlaybackError) { showError(e) } catch (e: Exception) {
                PlayerLog.e("Session: play ${t.label} failed", e)
                showError(PlaybackError("This Title Isn't Available Right Now", "Try again in a moment, or choose another source."))
            }
        }
    }

    // ================= Engine events and the clock =================

    private fun onEngineEvent(e: EngineEvent) {
        when (e) {
            // Once per load: the state check in [everySecond] may have heard it first.
            EngineEvent.FirstFrame -> if (awaitingFrame) onFirstFrame()
            is EngineEvent.Failed -> if (curtain !is Curtain.Error) failStream(e.detail)
            EngineEvent.Ended -> onEnded()
            EngineEvent.TracksChanged -> autoApplyTracks()
        }
    }

    private fun onFirstFrame() {
        awaitingFrame = false
        unsignalledFrameTicks = 0
        curtainTimeout?.cancel()
        if (curtain != null) {
            curtain = null
            curtainNote?.let { showToast(it, if (noteCam) Status.AMBER else Status.PLAIN, 3600) }
        }
        // A trailer's own PlaybackApi (TrailerPlayback) hears its first frame; its audio never marks the title's languages.
        playback.firstFrame(target, stream)
        if (!trailer) audioTracks.mapNotNull { it.lang }.toSet().takeIf { it.isNotEmpty() }?.let { playback.heardLanguages(target, stream.hash.ifEmpty { null }, it) }
        lastTickPos = engine.positionMs
        lastTickAt = System.currentTimeMillis()
        showControls()
        if (!trailer) loadMarkers()
        addDreamSubtitles()
    }

    /**
     * Core's markers (playback.skipMarkers: the file's own chapters from mpv first, then TheIntroDB, AniSkip, learned
     * skips, a credits estimate), once the duration is known; a learner for this item feeds core's learned intros.
     */
    private fun loadMarkers() {
        val t = target
        scope.launch {
            var dur = engineState.durationMs
            var waited = 0
            while (dur <= 0 && waited < 20) { delay(500); waited++; dur = engineState.durationMs }
            val chapters = engineState.chapters.map { SkipChapter(it.startMs, null, it.title) }
            val info = withContext(Dispatchers.IO) { withTimeoutOrNull(15_000) { runCatching { playback.skipMarkers(t, dur, chapters) }.getOrNull() } }
                ?: com.dreamos.desktop.core.playback.SkipInfo.NONE
            if (t != target) return@launch
            markers = info.marks.mapNotNull { m ->
                val kind = when (m.kind) {
                    SkipKind.INTRO -> SkipMarker.Kind.INTRO
                    SkipKind.RECAP -> SkipMarker.Kind.RECAP
                    SkipKind.CREDITS -> SkipMarker.Kind.CREDITS
                    SkipKind.PREVIEW -> null // core already ends the credits where a preview starts
                } ?: return@mapNotNull null
                SkipMarker(kind, m.startMs, m.endMs, m.estimated, learned = kind == SkipMarker.Kind.INTRO && info.introLearned)
            }
            learner?.end()
            learner = playback.skipLearner(t, info)
        }
    }

    private fun onTick(s: EngineState) {
        if (!s.firstFrame || curtain != null) return
        if (!s.buffering) scrobble(if (s.paused) PlayState.PAUSED else PlayState.PLAYING)
        val now = System.nanoTime() / 1_000_000
        if (now - learnerTickAt >= 450) { learnerTickAt = now; learner?.tick(s.positionMs, now) }
        updateSkip(s.positionMs)
        updatePostPlay(s)
    }

    private fun everySecond() {
        // The engine's first-frame signal can be dropped (its event queue full while the UI thread was busy): when its
        // state has said so for two ticks and the signal never came, the curtain lifts anyway.
        if (awaitingFrame && engine.state.value.firstFrame) {
            if (++unsignalledFrameTicks >= 2) {
                PlayerLog.w("Session: ${target.label}: first frame in the engine's state without its signal; lifting the curtain")
                onFirstFrame()
            }
        } else unsignalledFrameTicks = 0
        val s = engineState
        val now = System.currentTimeMillis()
        engine.checkSmoothness(steady = s.firstFrame && !s.paused && !s.buffering && !s.seeking && curtain == null && now - lastSeekAt > 3000)
        // Real watching: position moved forward about as much as time passed (seeks and skips add nothing).
        if (s.firstFrame && !s.paused && !s.buffering && curtain == null) {
            val p = engine.positionMs
            if (lastTickPos >= 0) {
                // Counted only when the picture moved about as far as the clock did (a seek jumps much further).
                val d = p - lastTickPos
                val wall = now - lastTickAt
                if (d > 0 && d <= (wall * s.speed).toLong() + 1500) realWatchedMs += d
            }
            lastTickPos = p
            lastTickAt = now
            if (now - lastSaveAt >= SAVE_EVERY_MS) saveProgress()
        } else { lastTickPos = engine.positionMs; lastTickAt = now }
        // Controls: hide after the pointer rests, unless paused or a sheet is open.
        val idle = now - lastInputAt
        val hideAfter = if (s.buffering) HIDE_MS + 1500 else HIDE_MS
        if (controlsShown && !s.paused && sheet == null && idle > hideAfter && curtain == null) controlsShown = false
        if (!pauseScreen && s.paused && s.firstFrame && curtain == null && sheet == null && postPlay == null && idle > PAUSE_SCREEN_MS) {
            pauseScreen = true
            controlsShown = false
        }
        // End of the line: nothing to do 90 s after the end.
        if (endedAt > 0 && now - endedAt > 90_000) close()
        // Up Next countdown.
        (postPlay as? PostPlay.UpNext)?.let { u ->
            if (u.countdownFrom != null && now - u.countdownFrom >= UP_NEXT_COUNTDOWN_MS) {
                autoplayedInARow++
                playOther(u.next, automatic = true)
            }
        }
        toast?.let { if (now > (toastUntil)) toast = null }
        skipNotice?.let { if (now > skipNoticeUntil) skipNotice = null }
        volumeHud?.let { if (now - it > 1200) volumeHud = null }
    }

    // ================= Input =================

    /** Any mouse move, click or key: controls come back, the pause screen leaves, Still Watching resets. */
    fun input() {
        lastInputAt = System.currentTimeMillis()
        if (pauseScreen) pauseScreen = false
        autoplayedInARow = 0
        if (!controlsShown) controlsShown = true
    }

    fun showControls() { lastInputAt = System.currentTimeMillis(); controlsShown = true }

    /** A media key or the system media overlay (MediaControls): play / pause, stop (closes), next episode. */
    fun mediaButton(b: com.dreamos.player.host.MediaControls.Button) {
        when (b) {
            com.dreamos.player.host.MediaControls.Button.PLAY -> if (engineState.paused) togglePlay()
            com.dreamos.player.host.MediaControls.Button.PAUSE -> if (!engineState.paused) togglePlay()
            com.dreamos.player.host.MediaControls.Button.TOGGLE -> togglePlay()
            com.dreamos.player.host.MediaControls.Button.STOP -> close()
            com.dreamos.player.host.MediaControls.Button.NEXT -> nextEpisode?.let { if (!trailer) playOther(it) }
            // The remote's Previous: Start Over (PlayerOverlay's MEDIA_PREVIOUS).
            com.dreamos.player.host.MediaControls.Button.PREVIOUS -> if (!trailer && curtain == null && engineState.firstFrame) startOver()
        }
    }

    /** The Captions key (PlayerOverlay.toggleSubtitles): subtitles off, or on in the viewer's language (else the first). */
    fun toggleSubtitles() {
        input()
        if (trailer || curtain != null) return
        val subs = subTracks
        if (subs.any { it.selected }) { pickSub(null); return }
        val want = prefs.subLang?.takeIf { it != PlayerPrefs.OFF } ?: prefs.audioLang ?: com.dreamos.desktop.core.content.ContentPrefs.viewerLang()
        val pick = subs.firstOrNull { it.lang == want } ?: subs.firstOrNull()
        if (pick == null) { showToast("No subtitles for this stream"); return }
        pickSub(pick)
    }

    /** What the system media overlay shows now (read in a snapshot: it follows the item, details and play state). */
    fun mediaInfo(): com.dreamos.player.host.MediaControls.Info {
        val t = target
        val d = details
        val sub = when {
            trailer -> "Trailer"
            t.isEpisode -> "S${t.season} E${t.episode}" + ((d?.episodeName ?: t.episodeTitle)?.let { "$SEP$it" } ?: "")
            else -> listOfNotNull("Movie", (d?.year ?: t.year)?.toString()).joinToString(SEP)
        }
        val art = (if (t.isEpisode) d?.episodeStillUrl else null) ?: d?.backdropUrl ?: t.backdrop
        return com.dreamos.player.host.MediaControls.Info(
            title = t.title, subtitle = sub.trim(), art = art?.takeIf { it.startsWith("https://") },
            playing = curtain == null && !engineState.paused, canNext = !trailer && nextEpisode != null,
        )
    }

    fun togglePlay() {
        input()
        if (curtain != null) return
        if (postPlay == PostPlay.StillWatching) { continueWatching(); return }
        val willPlay = engineState.paused
        if (willPlay) engine.play() else { engine.pause(); saveProgress(force = true) }
        flash = Flash(playing = willPlay)
    }

    private var bubbleJob: Job? = null
    /** ±10 s presses add up in a bubble ("+30"), committed 750 ms after the last. */
    fun nudge(seconds: Int) {
        input()
        if (curtain != null || !engineState.firstFrame) return
        seekBubble = (seekBubble ?: 0) + seconds
        bubbleJob?.cancel()
        bubbleJob = scope.launch {
            delay(750)
            val by = seekBubble ?: 0
            seekBubble = null
            seekTo(engine.positionMs + by * 1000L)
        }
    }

    private var lastSeekAt = 0L

    fun seekTo(ms: Long) {
        lastSeekAt = System.currentTimeMillis()
        input()
        engine.seek(ms)
        lastTickPos = -1
        // A seek more than 60 s before the finished point un-finishes; back before the credits removes post-play.
        val d = engineState.durationMs
        if (finished && d > 0 && ms < finishedPoint(d) - 60_000) finished = false
        val pp = postPlay
        if (pp != null && pp != PostPlay.StillWatching && ms < cardAt(d) - 5000) { postPlay = null; postPlayDismissedAt = -1; endedAt = 0 }
        else if (pp == null && postPlayDismissedAt >= 0 && ms < postPlayDismissedAt - 5000) postPlayDismissedAt = -1
        skipped.removeAll { k -> markers.any { it.kind == k && ms < it.startMs - 5000 } }
    }

    fun startOver() {
        seekTo(0)
        engine.play()
        showToast("Starting From The Beginning")
    }

    /** The last level above 0, for unmuting a volume that was taken all the way down. */
    private var lastAudible = prefs.volume.takeIf { it > 0 } ?: 50

    /** Wheel and Up / Down: a step, with the level card. Only a step up unmutes. */
    fun volumeBy(delta: Int) {
        setLevel(engineState.volume + delta, unmute = delta > 0, save = true)
        volumeHud = System.currentTimeMillis()
    }

    /**
     * The volume slider: an absolute level, no level card (the slider shows it). A level above 0 unmutes. A drag sets
     * the level on every move and saves the profile's choice once, on release ([save]).
     */
    fun setVolume(v: Int, save: Boolean = true) = setLevel(v, unmute = v > 0, save = save)

    private fun setLevel(v: Int, unmute: Boolean, save: Boolean) {
        input()
        val level = v.coerceIn(0, 100)
        engine.setVolume(level)
        if (level > 0) lastAudible = level
        if (engineState.muted && unmute) engine.setMuted(false)
        if (save) { val before = prefs; prefs = prefs.copy(volume = level); PlayerPrefs.save(prefs, before) }
    }

    /**
     * M and the slider's speaker. Mute keeps the level; unmuting a level of 0 brings back the last one heard, so the
     * speaker always makes a sound. [hud]: the level card (M); the speaker's own slider shows the level instead.
     */
    fun toggleMute(hud: Boolean = true) {
        if (engineState.volume == 0) setLevel(lastAudible, unmute = true, save = true)
        else { input(); engine.setMuted(!engineState.muted) }
        if (hud) volumeHud = System.currentTimeMillis()
    }

    fun openSheet(s: Sheet) {
        input()
        // A trailer has no sheets but Change Source from its error (which it never offers).
        if (trailer) return
        sheet = s
        when (s) {
            Sheet.EPISODES -> loadEpisodes(episodesSeason ?: target.season ?: 1)
            Sheet.SOURCE -> loadSources(loose = false)
            Sheet.XRAY -> loadCast()
            else -> {}
        }
    }

    fun closeSheet() {
        if (person != null) { closePerson(); return }
        sheet = null
        input()
    }

    // ================= Toasts =================
    private var toastUntil = 0L
    fun showToast(text: String, status: Status = Status.PLAIN, ms: Long = 1900) {
        toast = Toast(text, status)
        toastUntil = System.currentTimeMillis() + ms
    }

    // ================= Tracks and prefs =================

    private fun applyPrefs() {
        val p = prefs
        engine.setVolume(p.volume)
        engine.setGainDb(p.boostDb)
        applySubStyle()
    }

    /** Subtitles rise above the controls while they show (max(position, controls' height)). */
    fun applySubStyle(controlsFraction: Double = 0.0) {
        val p = prefs
        engine.subtitleStyle(p.subScale, p.subStyle, maxOf(p.subBottom, controlsFraction))
    }

    fun updatePrefs(p: PlayerPrefs) {
        val before = prefs
        prefs = p
        PlayerPrefs.save(p, before)
        if (before.subLang != p.subLang) dsPrefs = DreamSubtitles.prefs(p)
        applySubStyle()
    }

    val audioTracks: List<Track> get() = engineState.tracks.filter { it.type == Track.Type.AUDIO }
    val subTracks: List<Track> get() = engineState.tracks.filter { it.type == Track.Type.SUB }

    fun pickAudio(t: Track) {
        userPickedAudio = true
        engine.selectAudio(t.id)
        t.lang?.let { updatePrefs(prefs.copy(audioLang = it)) }
        showToast("Audio  ·  ${t.title}")
    }

    fun pickSub(t: Track?) {
        userPickedSub = true
        engine.selectSub(t?.id)
        updatePrefs(prefs.copy(subLang = t?.lang ?: if (t == null) PlayerPrefs.OFF else prefs.subLang))
        showToast(if (t == null) "Subtitles  ·  Off" else "Subtitles  ·  ${t.title}")
    }

    fun cycleBoost() {
        val next = (prefs.boost + 1) % 4
        updatePrefs(prefs.copy(boost = next))
        engine.setGainDb(prefs.boostDb)
        showToast("Volume Boost  ·  ${PlayerPrefs.BOOST_LABELS[next]}")
    }

    /**
     * Track prefs (fire-home PlayerPrefs.autoApply): audio once per stream when the chosen language is missing from
     * the selected track; subtitles on each new set of ids. Audio in another language than the viewer's turns their
     * language's subtitles on (unless they chose subtitles for this title).
     */
    private fun autoApplyTracks() {
        val p = prefs
        val audio = audioTracks
        // A trailer with its sound in a separate link: that link is the sound.
        if (trailer) {
            if (audioFor(request) != null) audio.lastOrNull { it.external }?.takeIf { !it.selected }?.let { engine.selectAudio(it.id) }
            return
        }
        if (!userPickedAudio && !autoAppliedAudio && audio.isNotEmpty()) {
            autoAppliedAudio = true
            val want = p.audioLang
            val sel = audio.firstOrNull { it.selected }
            if (want != null && sel?.lang != want) audio.firstOrNull { it.lang == want }?.let { engine.selectAudio(it.id) }
        }
        val subs = subTracks
        val ids = subs.joinToString(",") { it.id.toString() }
        if (!userPickedSub && ids != lastSubIds) {
            lastSubIds = ids
            val ds = dsPrefs
            val foreign = foreignAudio()
            val wants = when {
                p.subLang == PlayerPrefs.OFF -> emptyList()
                p.subLang != null -> listOf(p.subLang) + ds.langs
                ds.show -> ds.langs
                foreign != null -> listOf(foreign.second) + ds.langs
                else -> emptyList()
            }.map { if (it == "pob") "pt" else it }.distinct()
            val pick = pickSubtitle(subs, wants, ds)
            if (pick != null) {
                if (!pick.selected) engine.selectSub(pick.id)
                if (foreign != null && p.subLang == null && !ds.show && !foreignSaid) {
                    foreignSaid = true
                    showToast("${Tracks.langName(foreign.first)} Audio$SEP${Tracks.langName(pick.lang)} Subtitles On", ms = 3600)
                }
            } else if (p.subLang == PlayerPrefs.OFF && subs.any { it.selected && !it.forced }) engine.selectSub(null)
        }
    }

    /** The audio that plays isn't in the viewer's language and none is: (its language, the viewer's); else null. */
    private fun foreignAudio(): Pair<String, String>? {
        val audio = audioTracks
        val viewer = prefs.audioLang ?: com.dreamos.desktop.core.content.ContentPrefs.viewerLang()
        val playing = audio.firstOrNull { it.selected }?.lang ?: return null
        if (playing == viewer || audio.any { it.lang == viewer }) return null
        return playing to viewer
    }

    /**
     * The subtitle to show for [wants] (languages in order): "Use Subtitles in the Video First" puts the video's own
     * track ahead of a download (off: the download first); forced-only and SDH tracks as the settings prefer.
     */
    private fun pickSubtitle(subs: List<Track>, wants: List<String>, ds: DreamSubtitles.Prefs): Track? {
        for (l in wants) {
            val c = subs.filter { it.lang == l }
            if (c.isEmpty()) continue
            return c.sortedWith(compareBy({ it.external == ds.embeddedFirst }, { it.forced != ds.forced }, { it.title.contains("SDH") != ds.hi })).first()
        }
        return null
    }

    // ================= Dream Subtitles =================

    /** The index lookup and downloads for [t], behind the curtain (never for trailers). */
    private fun startDreamSubtitles(t: PlayTarget) {
        if (trailer) return
        if (dsFor == t.key && (dsList != null || dsJob?.isActive == true)) return
        dsJob?.cancel()
        dsFor = t.key
        dsList = null
        dsPrefs = DreamSubtitles.prefs(prefs)
        val p = dsPrefs
        dsJob = scope.launch {
            // The IMDb id: the target's, else the details' (they load beside this).
            var imdb = t.imdb
            var waited = 0
            while (imdb == null && waited < 80) { delay(100); waited++; imdb = details?.imdbId }
            val got = withContext(Dispatchers.IO) { runCatching { DreamSubtitles.fetch(t, imdb, p) }.getOrNull() }
            if (dsFor != t.key) return@launch
            got?.indexLangs?.let { playback.subtitleIndex(t, it) }
            dsList = got?.subs.orEmpty()
            addDreamSubtitles()
        }
    }

    /** Adds this item's downloads to the file now playing, once (a new file, after a source switch, gets them again). */
    private fun addDreamSubtitles() {
        val list = dsList ?: return
        if (trailer || dsAdded || !engineState.firstFrame || dsFor != target.key) return
        dsAdded = true
        val chosen = DreamSubtitles.pick(list, dsPrefs, stream.fileName.ifEmpty { stream.releaseName })
        for (s in chosen) engine.addSubtitle(s.path, DreamSubtitles.label(s), DreamSubtitles.mpvLang(s), select = false)
        if (chosen.isNotEmpty()) PlayerLog.i("Dream Subtitles: added ${chosen.size} to ${target.label}")
        // Audio in another language with nothing to read: say so once (fire-home audioCheck).
        val foreign = foreignAudio()
        if (chosen.isEmpty() && foreign != null && prefs.subLang == null && !dsPrefs.show && !foreignSaid &&
            subTracks.none { it.lang == foreign.second }
        ) {
            foreignSaid = true
            showToast("${Tracks.langName(foreign.first)} Audio$SEP" + "No ${Tracks.langName(foreign.second)} Subtitles Found", ms = 3600)
        }
    }

    // ================= Skip intro / recap / credits =================

    /**
     * fire-home SkipController: Skip Intro from the intro's start (from 0 when it starts in the first 3 s), Skip Recap
     * (a recap after the opening starts where the intro ends), each until 2 s before the segment ends; re-offered after
     * seeking back past it. Skip Intros Automatically skips a known intro (never a learned one) with an "Intro Skipped"
     * note. Skip Credits (the PC's own): real credits only, while no Up Next or More Like This covers them; it goes to
     * the credits' end, or to the end (where the next episode or post-play takes over).
     */
    private fun updateSkip(pos: Long) {
        val m = markers
        val dur = engineState.durationMs
        if (m.isEmpty() || trailer) { skipOffer = null; return }
        val intro = m.firstOrNull { it.kind == SkipMarker.Kind.INTRO }
        val recap = m.firstOrNull { it.kind == SkipMarker.Kind.RECAP }
        val credits = m.firstOrNull { it.kind == SkipMarker.Kind.CREDITS && !it.estimated }?.takeIf { dur > 0 && postPlay == null }
        fun window(k: SkipMarker): LongRange {
            val start = when (k.kind) {
                SkipMarker.Kind.INTRO -> if (k.startMs < 3000) 0 else k.startMs
                SkipMarker.Kind.RECAP -> if (intro != null && intro.endMs <= k.startMs + 1000 && intro.startMs < k.startMs) intro.endMs else k.startMs
                SkipMarker.Kind.CREDITS -> k.startMs
            }
            val end = if (k.kind == SkipMarker.Kind.CREDITS) minOf(k.endMs, dur) else k.endMs
            return start until (end - 2000)
        }
        val offer = listOfNotNull(intro, recap, credits).firstOrNull { it.kind !in skipped && pos in window(it) }
        if (offer == null) { skipOffer = null; return }
        if (offer.kind == SkipMarker.Kind.INTRO && prefs.autoSkipIntros && !offer.learned) {
            skipped += offer.kind
            skipTo(offer.endMs)
            PlayerLog.i("Skip: intro skipped automatically to ${offer.endMs / 1000}s")
            skipNotice = "Intro Skipped"
            skipNoticeUntil = System.currentTimeMillis() + 1600
            return
        }
        val to = if (offer.kind == SkipMarker.Kind.CREDITS && offer.endMs >= dur - 3000) dur else offer.endMs
        val label = when (offer.kind) { SkipMarker.Kind.INTRO -> "Skip Intro"; SkipMarker.Kind.RECAP -> "Skip Recap"; SkipMarker.Kind.CREDITS -> "Skip Credits" }
        if (skipOffer?.marker != offer) skipOffer = SkipOffer(offer, label, to)
    }

    fun skip() {
        val o = skipOffer ?: return
        skipped += o.marker.kind
        if (o.marker.kind == SkipMarker.Kind.INTRO && o.marker.learned) playback.introConfirmed(target)
        skipTo(o.toMs)
        input()
    }

    private fun skipTo(ms: Long) {
        skipOffer = null
        learner?.selfSeek(ms, System.nanoTime() / 1_000_000)
        engine.seek(ms)
    }

    // ================= Post-play =================

    /** Where post-play starts: real credits if in the back half, else an estimate (fire-home PostPlay). */
    fun creditsAt(dur: Long): Long {
        if (dur <= 0) return Long.MAX_VALUE
        val real = markers.firstOrNull { it.kind == SkipMarker.Kind.CREDITS && !it.estimated }?.startMs
        if (real != null && real > dur / 2 && real < dur) return real
        return if (target.isEpisode) maxOf(dur - 30_000, (dur * 0.9).toLong()) else maxOf(dur - minOf(180_000, (dur * 0.06).toLong()), (dur * 0.9).toLong())
    }

    /**
     * Where the post-play card comes up (PostPlay.cardAt, STANDARDS §5): an episode's Up Next only in its last minute,
     * whatever the credits markers, estimates or fallbacks say; a movie's More Like This at its credits.
     */
    fun cardAt(dur: Long): Long {
        if (dur <= 0) return Long.MAX_VALUE
        return if (target.isEpisode) maxOf(dur - UP_NEXT_LEAD_MS, dur / 2) else creditsAt(dur)
    }

    private fun finishedPoint(dur: Long): Long {
        val real = markers.firstOrNull { it.kind == SkipMarker.Kind.CREDITS && !it.estimated }?.startMs
        return if (real != null && real > dur * 0.6) real else (dur * FINISHED).toLong()
    }

    private var prepared = false
    private fun updatePostPlay(s: EngineState) {
        if (trailer) return
        val dur = s.durationMs
        if (dur <= 0) return
        val pos = s.positionMs
        val credits = cardAt(dur)
        // 90 s ahead of the card: the next episode's details and link (look-ahead), or recommendations.
        if (!prepared && pos > credits - 90_000) {
            prepared = true
            val t = target
            scope.launch {
                val n = nextEpisode
                if (n != null) {
                    playback.nextEpisodeAhead(n)
                    nextDetails = withContext(Dispatchers.IO) { withTimeoutOrNull(8000) { runCatching { services.details(n) }.getOrNull() } }
                } else recs = withContext(Dispatchers.IO) { withTimeoutOrNull(8000) { runCatching { services.recommendations(t) }.getOrNull() } }.orEmpty().take(4)
            }
        }
        if (pos < credits - 90_000) prepared = false
        // Still Watching: three automatic advances in a row, 3 s into the new item.
        if (target.isEpisode && autoplayedInARow >= 3 && services.stillWatching && pos > 3000 && postPlay == null && !s.paused) {
            engine.pause()
            postPlay = PostPlay.StillWatching
            controlsShown = false
            return
        }
        val showAt = credits
        if (postPlay == null && pos >= showAt && postPlayDismissedAt != showAt) {
            finished = true
            saveProgress(force = true)
            val n = nextEpisode
            postPlay = when {
                n != null -> PostPlay.UpNext(n, nextDetails, if (autoplay()) System.currentTimeMillis() else null)
                recs.isNotEmpty() -> PostPlay.MoreLikeThis(recs, ended = false)
                else -> null
            }
            postPlayDismissedAt = showAt
        }
    }

    private fun autoplay() = services.autoplayNext

    private fun onEnded() {
        if (trailer) { close(); return }
        // Ended before any picture (an empty, cut or fake file, or a saved spot past its end): a failure, never "watched".
        // From a saved spot the same link is tried once from the beginning; else the next source, then the error.
        if (!engineState.firstFrame) {
            // mpv may say "ended" twice for one file (end-file and eof-reached): one decision per file.
            val now = System.currentTimeMillis()
            if (now - noPictureEndAt < 1500) return
            noPictureEndAt = now
            if (request.startMs > 0 && !retriedFromTop) {
                retriedFromTop = true
                PlayerLog.w("Session: ${target.label} ended before its first frame from ${request.startMs / 1000}s; trying it from the beginning")
                request = request.copy(startMs = 0)
                load(request.url, request.headers, 0, audioFor(request))
                armCurtainTimeout()
            } else if (curtain !is Curtain.Error) failStream("stream ended before its first frame", damaged = true)
            return
        }
        finished = true
        saveProgress(force = true)
        val n = nextEpisode
        when {
            n != null && autoplay() -> { autoplayedInARow++; playOther(n, automatic = true) }
            n != null -> postPlay = PostPlay.UpNext(n, nextDetails, null)
            recs.isNotEmpty() -> postPlay = PostPlay.MoreLikeThis(recs, ended = true)
            else -> close()
        }
        endedAt = System.currentTimeMillis()
    }

    fun upNextPlay() { (postPlay as? PostPlay.UpNext)?.let { playOther(it.next) } }

    /** Up Next's Credits / More Like This' Watch Credits: if the video ended, close; else keep watching. */
    fun dismissPostPlay() {
        if (engineState.ended) { close(); return }
        postPlay = null
        input()
    }

    /** Esc / Backspace on post-play (PostPlayController.onBack): Up Next and More Like This keep the credits (after the end: close); Still Watching leaves. */
    fun backFromPostPlay() {
        when (postPlay) {
            null -> {}
            PostPlay.StillWatching -> close()
            else -> dismissPostPlay()
        }
    }

    fun continueWatching() {
        autoplayedInARow = 0
        postPlay = null
        engine.play()
        input()
    }

    fun watchAgain() { postPlay = null; endedAt = 0; seekTo(0); engine.play() }

    fun openRec(r: RecItem) {
        if (r.isShow) { services.openTitle(r.tmdbId, true); close() }
        else playOther(PlayTarget(MediaKind.MOVIE, r.tmdbId, r.title, r.year))
    }

    // ================= Sheets' loading =================

    fun loadEpisodes(season: Int) {
        val show = target.tmdbId
        episodesSeason = season
        scope.launch {
            if (seasons.isEmpty()) seasons = withContext(Dispatchers.IO) { runCatching { services.seasons(show) }.getOrNull().orEmpty() }
            episodes = emptyList()
            episodesLoaded = false
            val t = target
            val (list, marks) = withContext(Dispatchers.IO) {
                val l = runCatching { services.episodes(show, season) }.getOrNull().orEmpty()
                l to l.mapNotNull { e -> val et = t.atEpisode(e.season, e.number); services.watchedFraction(et)?.let { et.key to it } }.toMap()
            }
            watched = marks
            episodes = list
            episodesLoaded = true
        }
    }

    fun playEpisode(e: EpisodeItem) {
        val t = target.atEpisode(e.season, e.number, e.name, e.airDate)
        if (t.key == target.key) { sheet = null; return }
        playOther(t)
    }

    fun loadSources(loose: Boolean) {
        sources = null; sourcesLoose = loose; sourceFailed = null; sourcePending = null
        val t = target
        scope.launch { sources = withContext(Dispatchers.IO) { runCatching { playback.sources(t, loose) }.getOrNull().orEmpty() } }
    }

    fun isPlayingSource(s: SourceRow) = s.source.hash.isNotEmpty() && s.source.hash.equals(stream.hash, true)

    /** A picked source: playback continues while it's readied, then switches keeping the spot. */
    fun pickSource(s: SourceRow) {
        if (isPlayingSource(s)) { sheet = null; return }
        sourcePending = s.source.hash; sourceFailed = null
        val spot = engine.positionMs
        scope.launch {
            try {
                val req = withContext(Dispatchers.IO) { playback.playSource(target, s.source, spot) }
                sourcePending = null
                sheet = null
                saveProgress(force = true)
                open(req.copy(target = target, startMs = engine.positionMs), keepPosition = engine.positionMs)
            } catch (e: Exception) {
                sourcePending = null
                sourceFailed = s.source.hash
                delay(2600)
                if (sourceFailed == s.source.hash) sourceFailed = null
            }
        }
    }

    fun findAnotherSource() { sheet = null; playback.failed(target, stream); tryAgain() }

    private fun loadCast() {
        if (cast != null) return
        val t = target
        scope.launch {
            val c = withContext(Dispatchers.IO) { withTimeoutOrNull(8000) { runCatching { services.cast(t) }.getOrNull() } }.orEmpty()
            if (c.isEmpty()) { sheet = null; showToast("Cast details aren't available for this title"); return@launch }
            cast = c
        }
    }

    fun openPerson(c: CastItem) {
        resumeAfterPerson = !engineState.paused
        engine.pause()
        person = c to null
        val t = target
        scope.launch {
            val p = withContext(Dispatchers.IO) { withTimeoutOrNull(8000) { runCatching { services.person(c.personId, t) }.getOrNull() } }
            if (person?.first == c) person = c to (p ?: PersonItem(c.name, c.photoUrl, "", "", emptyList(), failed = true))
        }
    }

    fun closePerson() {
        person = null
        if (resumeAfterPerson) engine.play()
        resumeAfterPerson = false
    }

    fun openKnownFor(r: RecItem) { services.openTitle(r.tmdbId, r.isShow); close() }

    // ================= Progress and close =================

    private fun saveProgress(force: Boolean = false) {
        val s = engineState
        if (trailer || !s.firstFrame) return
        val dur = s.durationMs
        if (dur < MIN_HISTORY_MS) return
        val pos = engine.positionMs
        if (!finished && pos >= finishedPoint(dur)) finished = true
        lastSaveAt = System.currentTimeMillis()
        val t = target
        val at = if (finished) dur else pos
        val real = realWatchedMs
        // History writes (and a finished episode's Up Next lookup) never run on the UI thread.
        scope.launch(Dispatchers.IO) { services.report(t, at, dur, real, exact = force) }
        if (force) PlayerLog.i("Session: saved ${t.label} at ${pos / 1000}s${if (finished) " (finished)" else ""}, really watched ${real / 1000}s")
    }

    /** Trakt hears play / pause / stop as they settle (core holds each for 3 s). */
    private var scrobbled: PlayState? = null
    private fun scrobble(state: PlayState) {
        val s = engineState
        if (trailer || !s.firstFrame || s.durationMs <= 0 || scrobbled == state) return
        scrobbled = state
        services.scrobble(target, (engine.positionMs.toDouble() / s.durationMs).coerceIn(0.0, 1.0), state, s.durationMs)
    }
    private fun stopScrobble() { scrobble(PlayState.STOPPED); scrobbled = null }

    fun close() {
        if (closed) return
        closed = true
        learner?.end(); learner = null
        saveProgress(force = true)
        val pos = engine.positionMs
        PlayerLog.i("Session: closing ${target.label} at ${pos / 1000}s, really watched ${realWatchedMs / 1000}s")
        stopScrobble()
        services.playerClosed()
        playback.playerActive(false)
        events.closed(target, pos)
        scope.cancel()
        onClose()
    }

    companion object {
        /** The curtain's deadline before any extension (tests shorten it). */
        @Volatile internal var curtainMs = 45_000L
        const val SAVE_EVERY_MS = 30_000L
        const val MIN_HISTORY_MS = 10 * 60_000L
        const val FINISHED = 0.9
        /** Controls hide after the pointer rests this long while playing (TV: 4 s; a mouse moves more). */
        const val HIDE_MS = 3_000L
        const val PAUSE_SCREEN_MS = 8_000L
        const val UP_NEXT_COUNTDOWN_MS = 10_000L
        const val UP_NEXT_LEAD_MS = 60_000L
        const val MAX_SWITCHES = 3
        /** No picture this long while another ready link is in hand: that one plays (DreamPlayerActivity.START_WATCH_MS). */
        const val START_WATCH_MS = 12_000L
        const val DAMAGED_TEXT = "This file is damaged or incomplete. Try Again plays another copy."
    }
}
