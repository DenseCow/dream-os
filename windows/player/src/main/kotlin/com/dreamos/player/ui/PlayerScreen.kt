package com.dreamos.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamos.player.session.PlayerSession
import kotlinx.coroutines.delay

/** What the screen can ask of the windows around it (PlayerStage). */
interface StageControl {
    val fullScreen: Boolean
    /** Esc / Backspace steps out of full screen before closing (only a full screen the player itself entered). */
    val backLeavesFullScreen: Boolean get() = fullScreen
    val mini: Boolean
    fun toggleFullScreen()
    fun enterMini()
    fun expand()
    fun dragMini(dx: Int, dy: Int)
}

/**
 * Dream Player's overlay, drawn in the transparent window over the video. Everything a viewer touches: the controls,
 * the curtain, sheets, post-play. The whole surface takes the mouse (a 1/255 black wash), so mpv's window never does.
 */
@Composable
fun PlayerScreen(session: PlayerSession, stage: StageControl, frameAt: (Long) -> androidx.compose.ui.graphics.ImageBitmap? = { null }) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // One proportion for every window size: the layout is designed at 1600x900 and scales between 0.8x and 1.3x.
        val k = minOf(maxWidth.value / 1600f, maxHeight.value / 900f).coerceIn(0.8f, 1.3f)
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density * k, base.fontScale)) {
            if (stage.mini) MiniPlayer(session, stage) else FullPlayer(session, stage, frameAt)
        }
    }
}

private val blankCursor: PointerIcon by lazy {
    val img = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    PointerIcon(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(img, java.awt.Point(0, 0), "none"))
}

@Composable
private fun FullPlayer(session: PlayerSession, stage: StageControl, frameAt: (Long) -> androidx.compose.ui.graphics.ImageBitmap?) {
    val s = session
    val es = s.engineState
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var lastClick by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    // The volume slider has the keyboard: its arrows, Home and End are its own, not the player's seek and volume.
    var sliderKeys by remember { mutableStateOf(false) }
    val showControls = s.controlsShown && s.curtain == null && s.postPlay != PlayerSession.PostPlay.StillWatching && !s.pauseScreen
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Subtitles ride above the controls while they show.
    LaunchedEffect(showControls) { s.applySubStyle(if (showControls) 0.20 else 0.0) }
    // Hidden under sheets, X-Ray and post-play only (PlayerOverlay.syncSubtitles); the pause screen keeps them.
    val subsHidden = s.sheet != null || s.postPlay != null
    LaunchedEffect(subsHidden) { s.engine.subtitlesVisible(!subsHidden) }

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 1f / 255f))
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                s.input()
                if (sliderKeys && e.key in VolumeSliderKeys) return@onPreviewKeyEvent false
                val sheetOpen = s.sheet != null
                when (e.key) {
                    Key.Escape, Key.Backspace -> {
                        when {
                            sheetOpen -> s.closeSheet()
                            // Up Next / More Like This: Back keeps the credits (closes after the end); Still Watching leaves.
                            s.postPlay != null -> s.backFromPostPlay()
                            stage.backLeavesFullScreen -> stage.toggleFullScreen()
                            else -> s.close()
                        }
                        true
                    }
                    Key.Spacebar, Key.K -> if (sheetOpen) false else { s.togglePlay(); true }
                    // The remote's Captions key: subtitles off, or back on in the viewer's language.
                    Key.C -> if (sheetOpen || e.isCtrlPressed) false else { s.toggleSubtitles(); true }
                    // X-Ray (the stick's Up on the timeline or while paused).
                    Key.X -> if (sheetOpen || e.isCtrlPressed) false else { s.openSheet(PlayerSession.Sheet.XRAY); true }
                    Key.DirectionLeft -> if (sheetOpen) { focusManager.moveFocus(FocusDirection.Left); true } else { s.nudge(-10); true }
                    Key.DirectionRight -> if (sheetOpen) { focusManager.moveFocus(FocusDirection.Right); true } else { s.nudge(10); true }
                    Key.DirectionUp -> if (sheetOpen) { focusManager.moveFocus(FocusDirection.Up); true } else { s.volumeBy(5); true }
                    Key.DirectionDown -> if (sheetOpen) { focusManager.moveFocus(FocusDirection.Down); true } else { s.volumeBy(-5); true }
                    Key.F, Key.F11 -> { stage.toggleFullScreen(); true }
                    Key.M -> if (e.isCtrlPressed) false else { s.toggleMute(); true }
                    Key.I -> if (sheetOpen) false else { s.openSheet(PlayerSession.Sheet.INFO); true }
                    // The remote's Menu key opens Audio & Subtitles.
                    Key.A -> if (sheetOpen || e.isCtrlPressed) false else { s.openSheet(PlayerSession.Sheet.AUDIO_SUBS); true }
                    // Keyboards that send media keys as keys (the system media controls catch the rest).
                    Key.MediaPlayPause -> { s.mediaButton(com.dreamos.player.host.MediaControls.Button.TOGGLE); true }
                    Key.MediaPlay -> { s.mediaButton(com.dreamos.player.host.MediaControls.Button.PLAY); true }
                    Key.MediaPause -> { s.mediaButton(com.dreamos.player.host.MediaControls.Button.PAUSE); true }
                    Key.MediaStop -> { s.mediaButton(com.dreamos.player.host.MediaControls.Button.STOP); true }
                    Key.MediaNext -> { s.mediaButton(com.dreamos.player.host.MediaControls.Button.NEXT); true }
                    Key.MediaPrevious -> { s.mediaButton(com.dreamos.player.host.MediaControls.Button.PREVIOUS); true }
                    else -> false
                }
            }
            // Enter that no focused control took: the stick's OK on what it has selected by itself, the Skip pill
            // while it's offered, Up Next's Play, Still Watching's Continue Watching.
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || (e.key != Key.Enter && e.key != Key.NumPadEnter) || s.sheet != null) return@onKeyEvent false
                when (s.postPlay) {
                    is PlayerSession.PostPlay.UpNext -> { s.upNextPlay(); true }
                    PlayerSession.PostPlay.StillWatching -> { s.continueWatching(); true }
                    null -> if (s.skipOffer != null && s.curtain == null) { s.skip(); true } else false
                    else -> false
                }
            }
            .pointerHoverIcon(if (showControls || s.sheet != null || s.curtain != null || s.postPlay != null) PointerIcon.Default else blankCursor)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        when (e.type) {
                            PointerEventType.Move, PointerEventType.Enter -> s.input()
                            PointerEventType.Scroll -> {
                                val dy = e.changes.first().scrollDelta.y
                                if (s.sheet == null && dy != 0f) s.volumeBy(if (dy < 0) 5 else -5)
                            }
                            PointerEventType.Release -> {
                                // Only clicks on the picture itself reach here (controls consume theirs).
                                if (e.changes.any { it.isConsumed }) continue
                                val now = System.currentTimeMillis()
                                if (s.sheet != null) { s.closeSheet(); continue }
                                if (s.curtain != null || s.postPlay != null) continue
                                if (now - lastClick < 350) {
                                    // Double click: full screen. Undo the first click's play / pause.
                                    s.togglePlay(); stage.toggleFullScreen(); lastClick = 0
                                } else { s.togglePlay(); lastClick = now }
                            }
                        }
                    }
                }
            },
    ) {
        // Dims (PlayerOverlay.restDim): pause screen 0.66, X-Ray card 0.66, X-Ray strip 0.16, paused or a sheet 0.28.
        val paused = es.paused && es.firstFrame && s.curtain == null && s.postPlay == null
        val dim by animateFloatAsState(
            when {
                s.pauseScreen -> 0.66f
                s.sheet == PlayerSession.Sheet.XRAY -> if (s.person != null) 0.66f else 0.16f
                s.sheet != null || paused -> 0.28f
                else -> 0f
            }, tween(700),
        )
        if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))

        AnimatedVisibility(showControls && s.sheet == null, enter = fadeIn(tween(220)), exit = fadeOut(tween(420))) {
            Controls(s, stage, frameAt, onScrubbing = { scrubbing = it }, onSliderFocus = { sliderKeys = it })
        }

        // Skip Intro / Recap / Credits: bottom-right, riding above the controls when they show.
        val offer = s.skipOffer
        AnimatedVisibility(
            offer != null && s.curtain == null && s.sheet == null && s.postPlay == null,
            Modifier.align(Alignment.BottomEnd).padding(end = 64.dp, bottom = if (showControls) 196.dp else 72.dp),
            enter = fadeIn(tween(240)), exit = fadeOut(tween(240)),
        ) {
            val last = remember { mutableStateOf(offer) }
            if (offer != null) last.value = offer
            PillButton(last.value?.label ?: "", onClick = { s.skip() }, glyph = Glyph.NEXT, height = 52.dp)
        }
        // "Intro Skipped": a quiet pill where the Skip button lives, fading by itself (SkipController.showNotice).
        AnimatedVisibility(
            s.skipNotice != null && s.curtain == null,
            Modifier.align(Alignment.BottomEnd).padding(end = 64.dp, bottom = if (showControls) 196.dp else 72.dp),
            enter = fadeIn(tween(240)), exit = fadeOut(tween(300)),
        ) {
            val last = remember { mutableStateOf(s.skipNotice) }
            s.skipNotice?.let { last.value = it }
            Box(
                Modifier.clip(RoundedCornerShape(26.dp)).background(Color(0xE60E0E10)).border(1.dp, Look.Edge, RoundedCornerShape(26.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            ) { Text(last.value ?: "", style = Look.Line, color = Look.Text) }
        }

        CenterFlash(s)
        SeekBubble(s)
        BufferingRing(es.buffering && es.loaded && s.curtain == null && !scrubbing)
        VolumeHud(s)
        PauseScreen(s)
        PostPlayLayer(s)
        Sheets(s)
        Toasts(s, Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
        Curtain(s, stage)
    }
}

// ================= Controls =================

@Composable
private fun Controls(s: PlayerSession, stage: StageControl, frameAt: (Long) -> androidx.compose.ui.graphics.ImageBitmap?, onScrubbing: (Boolean) -> Unit, onSliderFocus: (Boolean) -> Unit) {
    val es = s.engineState
    val t = s.target
    val d = s.details
    Box(Modifier.fillMaxSize()) {
        // Scrims: top for the title block and clock, bottom for the timeline (shade 80% fading to clear).
        Box(Modifier.fillMaxWidth().height(260.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Black.copy(alpha = 0.32f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(300.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.42f), Color.Black.copy(alpha = 0.86f)))))

        // Top-left: Now Playing, logo or title, the quiet line, compact badges.
        Column(Modifier.align(Alignment.TopStart).padding(start = 64.dp, top = 48.dp).widthIn(max = 720.dp)) {
            // The green "NOW PLAYING" for every item; a trailer adds its kicker under it (PlayerOverlay.cinemaTitle).
            Text("NOW PLAYING", style = Look.NowPlaying, color = Look.NowGreen)
            if (s.trailer) { Spacer(Modifier.height(10.dp)); Text("Trailer", style = Look.Kicker, color = Look.Text2) }
            Spacer(Modifier.height(12.dp))
            TitleMark(d?.logoUrl, t.title, maxW = 380.dp, maxH = 92.dp, textSize = 32)
            Spacer(Modifier.height(10.dp))
            val line = when {
                s.trailer -> null
                t.isEpisode -> "S${t.season} E${t.episode}" + ((d?.episodeName ?: t.episodeTitle)?.let { "$SEP$it" } ?: "")
                else -> listOfNotNull("Movie", (d?.year ?: t.year)?.toString()).joinToString(SEP)
            }
            if (line != null) Text(line, style = Look.Line, color = Look.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val badges = badgesFor(s, full = false)
            if (badges.isNotEmpty() && !s.trailer) {
                Spacer(Modifier.height(14.dp))
                BadgeRow(badges)
            }
        }

        // Top-right: window buttons, then the clock and "Ends at".
        Column(Modifier.align(Alignment.TopEnd).padding(end = 48.dp, top = 40.dp), horizontalAlignment = Alignment.End) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundButton(Glyph.MINI, "Mini Player", onClick = { stage.enterMini() }, size = 44.dp, iconSize = 20.dp)
                RoundButton(if (stage.fullScreen) Glyph.UNFULL else Glyph.FULL, if (stage.fullScreen) "Exit Full Screen" else "Full Screen", onClick = { stage.toggleFullScreen() }, size = 44.dp, iconSize = 20.dp)
                RoundButton(Glyph.CLOSE, "Close Player", onClick = { s.close() }, size = 44.dp, iconSize = 18.dp)
            }
            Spacer(Modifier.height(18.dp))
            ClockBlock(s)
        }

        // Bottom: timeline, times, the action row.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 64.dp).padding(bottom = 40.dp)) {
            Scrubber(
                positionMs = es.positionMs, durationMs = es.durationMs, bufferedToMs = es.bufferedToMs,
                markers = s.markers, onSeek = { s.seekTo(it) }, onScrubbing = onScrubbing, frameAt = frameAt,
            )
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(clockText(es.positionMs), style = Look.Time, color = Look.Text)
                Spacer(Modifier.weight(1f))
                Text(if (es.durationMs > 0) "−" + clockText(es.durationMs - es.positionMs) else "LIVE", style = Look.Time, color = Look.Text2)
            }
            Spacer(Modifier.height(18.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // What the row holds decides how much of it fits: the volume slider goes first (the speaker slides it
                // out), then the sheet buttons' words (round icons that name themselves on hover). Start Over is
                // counted from the start, so the row doesn't change shape when it appears after the first minute.
                val sheetLabels = if (s.trailer) emptyList() else listOfNotNull(
                    "Episodes".takeIf { t.isEpisode }, "Audio & Subtitles", "Change Source".takeIf { t.tmdbId > 0 }, "Info", "Playback",
                )
                val fit = rowFit(sheetLabels, startOver = !s.trailer, nextEpisode = s.nextEpisode != null, room = maxWidth)
                val compact = fit == RowFit.ICONS
                val volumeCompact = fit != RowFit.ALL
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoundButton(if (es.paused) Glyph.PLAY else Glyph.PAUSE, if (es.paused) "Play" else "Pause", onClick = { s.togglePlay() }, size = 56.dp, iconSize = 24.dp)
                    VolumeControl(s, volumeCompact, onSliderFocus)
                    Spacer(Modifier.width(4.dp))
                    if (!s.trailer) {
                        if (t.isEpisode) ActionButton("Episodes", Glyph.EPISODES, compact) { s.openSheet(PlayerSession.Sheet.EPISODES) }
                        ActionButton("Audio & Subtitles", Glyph.AUDIO_SUBS, compact) { s.openSheet(PlayerSession.Sheet.AUDIO_SUBS) }
                        if (t.tmdbId > 0) ActionButton("Change Source", Glyph.SOURCE, compact) { s.openSheet(PlayerSession.Sheet.SOURCE) }
                        ActionButton("Info", Glyph.INFO, compact) { s.openSheet(PlayerSession.Sheet.INFO) }
                        ActionButton("Playback", Glyph.SPEED, compact) { s.openSheet(PlayerSession.Sheet.PLAYBACK) }
                    }
                    Spacer(Modifier.weight(1f))
                    // Next Episode, then Start Over alone in the far right corner (past the first minute).
                    s.nextEpisode?.let { n -> PillButton("Next Episode", { s.playOther(n) }, Glyph.NEXT) }
                    if (!s.trailer && es.durationMs > 0 && es.positionMs > 60_000) ActionButton("Start Over", Glyph.RESTART, compact) { s.startOver() }
                }
            }
        }
    }
}

private enum class RowFit { ALL, SPEAKER, ICONS }

/**
 * How much of the action row fits in [room]: everything with the volume slider out ([RowFit.ALL]), the slider folded
 * into the speaker ([RowFit.SPEAKER]), or also the sheet buttons as round icons ([RowFit.ICONS]). Measured from the
 * pills' own words (PillButton: 18 dp sides, a 20 dp glyph and a 10 dp gap), with at least 24 dp left between the
 * groups so the right-hand buttons never meet the left ones.
 */
@Composable
private fun rowFit(sheetLabels: List<String>, startOver: Boolean, nextEpisode: Boolean, room: androidx.compose.ui.unit.Dp): RowFit {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 16)
    val density = LocalDensity.current
    return remember(sheetLabels, startOver, nextEpisode, room, density) {
        fun pill(label: String) = with(density) { measurer.measure(label, Look.Button, maxLines = 1).size.width.toDp() } + 66.dp
        val gap = 12.dp
        // Play / Pause, the volume, the 4 dp spacer, the sheet buttons, the flexible spacer, Next Episode, Start Over.
        fun need(words: Boolean, slider: Boolean): androidx.compose.ui.unit.Dp {
            val items = buildList {
                add(56.dp); add(if (slider) 170.dp else 44.dp); add(4.dp)
                sheetLabels.forEach { add(if (words) pill(it) else 44.dp) }
                add(24.dp)
                if (nextEpisode) add(pill("Next Episode"))
                if (startOver) add(if (words) pill("Start Over") else 44.dp)
            }
            return items.fold(0.dp) { a, b -> a + b } + gap * (items.size - 1)
        }
        when {
            need(words = true, slider = true) <= room -> RowFit.ALL
            need(words = true, slider = false) <= room -> RowFit.SPEAKER
            else -> RowFit.ICONS
        }
    }
}

/** A labelled pill, or in a narrow window a round icon button whose name shows on hover. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionButton(label: String, glyph: Glyph, compact: Boolean, onClick: () -> Unit) {
    if (!compact) { PillButton(label, onClick, glyph); return }
    TooltipArea(
        tooltip = {
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xF00E0E10)).border(1.dp, Look.Edge, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(label, style = Look.Small, color = Look.Text)
            }
        },
        delayMillis = 350,
        tooltipPlacement = TooltipPlacement.ComponentRect(Alignment.TopCenter, Alignment.TopCenter, androidx.compose.ui.unit.DpOffset(0.dp, (-8).dp)),
    ) { RoundButton(glyph, label, onClick = onClick, size = 44.dp, iconSize = 20.dp) }
}

@Composable
fun TitleMark(logoUrl: String?, title: String, maxW: androidx.compose.ui.unit.Dp, maxH: androidx.compose.ui.unit.Dp, textSize: Int, lines: Int = 2) {
    var logoShown by remember(logoUrl) { mutableStateOf(logoUrl != null && PlayerImages.cached(logoUrl) != null) }
    Box {
        if (logoUrl != null) NetImage(
            logoUrl, Modifier.widthIn(max = maxW).heightIn(max = maxH), contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart, description = title, onState = { logoShown = it },
        )
        if (!logoShown) Text(
            title, color = Look.Text, fontSize = textSize.sp, fontWeight = FontWeight.Bold, lineHeight = (textSize + 4).sp,
            maxLines = lines, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = maxW + 220.dp),
        )
    }
}

@Composable
fun BadgeRow(badges: List<Badge>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in badges) when {
            b.cam -> Capsule(b.text, color = Look.AmberText, fill = Look.Amber.copy(alpha = 0.16f), edge = SolidColor(Look.Amber.copy(alpha = 0.6f)))
            // Lit only while the screen really shows HDR: a sun and an edge in the whole rainbow.
            b.hdr -> Capsule(b.text, color = Look.Text, edge = SolidColor(rainbowHue(player = true))) { GlyphIcon(Glyph.SUN, Look.Text, 14.dp) }
            else -> Capsule(b.text)
        }
    }
}

@Composable
private fun ClockBlock(s: PlayerSession) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(5_000) } }
    val es = s.engineState
    Text(s.services.clock(now), style = TextStylePlus.clock, color = Look.Text)
    if (es.durationMs > 0 && !s.trailer) {
        val left = ((es.durationMs - es.positionMs) / es.speed.coerceAtLeast(0.25)).toLong()
        Text("Ends at ${s.services.clock(now + left)}", style = Look.Small, color = Look.Text2)
    }
}

private object TextStylePlus {
    val clock = Look.Line.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
}

// ================= Small overlays =================

@Composable
private fun BoxScopeCenter(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }

@Composable
private fun CenterFlash(s: PlayerSession) {
    val f = s.flash ?: return
    var visible by remember(f.id) { mutableStateOf(true) }
    LaunchedEffect(f.id) { delay(410); visible = false }
    BoxScopeCenter {
        AnimatedVisibility(visible, enter = scaleIn(tween(150), 0.8f) + fadeIn(tween(150)), exit = fadeOut(tween(320))) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(Color(0xB30E0E10)).border(1.dp, Look.Edge, CircleShape), contentAlignment = Alignment.Center) {
                GlyphIcon(if (f.playing) Glyph.PLAY else Glyph.PAUSE, Look.Text, 40.dp)
            }
        }
    }
}

/** ±10 s taps add up in a round bubble on that side ("+30", "−1:20"), as the stick's bubbleL / bubbleR. */
@Composable
private fun SeekBubble(s: PlayerSession) {
    val by = s.seekBubble ?: return
    Box(Modifier.fillMaxSize().padding(horizontal = 154.dp), contentAlignment = if (by > 0) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(Color(0xB30E0E10)).border(1.dp, Look.Edge, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(offsetText(by * 1000L), style = Look.Time.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold), color = Look.Text) }
    }
}

/** Buffering ring (PlayerLook): smoked disc, faint ring, a 100° arc in the rainbow turning once every 1.1 s; after 380 ms. */
@Composable
private fun BufferingRing(buffering: Boolean) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(buffering) { if (buffering) { delay(380); show = true } else show = false }
    if (!show) return
    var angle by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { val t0 = System.nanoTime(); while (true) { delay(16); angle = ((System.nanoTime() - t0) / 1e6f / 1100f * 360f) % 360f } }
    BoxScopeCenter {
        Canvas(Modifier.size(72.dp).rainbowUser(player = true)) {
            drawCircle(Color(0xA60E0E10))
            rainbowArc(angle, 100f, 4.dp.toPx(), 14.dp.toPx())
        }
    }
}

@Composable
private fun VolumeHud(s: PlayerSession) {
    if (s.volumeHud == null) return
    val es = s.engineState
    Box(Modifier.fillMaxSize().padding(top = 120.dp), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xD90E0E10)).border(1.dp, Look.Edge, RoundedCornerShape(24.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpeakerIcon(es.volume, es.muted, Look.Text, 22.dp)
            Spacer(Modifier.width(14.dp))
            Canvas(Modifier.width(180.dp).height(6.dp).rainbowUser(player = true)) {
                drawRoundRect(Color.White.copy(alpha = 0.2f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                if (!es.muted) rainbowLine(es.volume / 100f, 3.dp.toPx())
            }
            Spacer(Modifier.width(14.dp))
            Text(if (es.muted) "Muted" else "${es.volume}%", style = Look.Time, color = Look.Text, modifier = Modifier.widthIn(min = 48.dp))
        }
    }
}

@Composable
private fun Toasts(s: PlayerSession, modifier: Modifier) {
    val t = s.toast
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(t != null, modifier, enter = fadeIn(tween(200)), exit = fadeOut(tween(300))) {
            val last = remember { mutableStateOf(t) }
            if (t != null) last.value = t
            val shown = last.value ?: return@AnimatedVisibility
            val color = when (shown.status) {
                PlayerSession.Status.AMBER -> Look.AmberText
                PlayerSession.Status.PLAIN -> Look.Text.copy(alpha = 0.92f)
            }
            Box(
                Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xE60E0E10)).border(1.dp, Look.Edge, RoundedCornerShape(22.dp))
                    .padding(horizontal = 22.dp, vertical = 11.dp),
            ) { Text(shown.text, style = Look.Line, color = color) }
        }
    }
}

/** A whole-surface fade used by sheets and cards. */
@Composable
fun Fading(visible: Boolean, content: @Composable () -> Unit) {
    val a by animateFloatAsState(if (visible) 1f else 0f, tween(260))
    if (a > 0f) Box(Modifier.fillMaxSize().alpha(a)) { content() }
}

/** A progress line filling [fraction] of the canvas width in the fading rainbow (its Canvas is a [rainbowUser]). */
fun androidx.compose.ui.graphics.drawscope.DrawScope.rainbowLine(fraction: Float, radius: Float = 0f) {
    val f = fraction.coerceIn(0f, 1f)
    if (f <= 0f) return
    drawRoundRect(
        RainbowClock.hue.value, size = Size(maxOf(size.width * f, radius * 2), size.height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
    )
}

/** A loading arc of [sweep] degrees from [start] in the fading rainbow (its Canvas is a [rainbowUser]). */
fun androidx.compose.ui.graphics.drawscope.DrawScope.rainbowArc(start: Float, sweep: Float, stroke: Float, inset: Float, track: Boolean = true) {
    val sz = Size(size.width - inset * 2, size.height - inset * 2)
    if (track) drawArc(Color.White.copy(alpha = 0.16f), 0f, 360f, false, Offset(inset, inset), sz, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
    rotate(start) {
        drawArc(
            RainbowClock.hue.value, 0f, sweep, false, Offset(inset, inset), sz,
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}
