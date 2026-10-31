package com.dreamos.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamos.player.session.PlayerSession
import kotlinx.coroutines.delay

// ================= Loading curtain (PlayFlow.LoadingCurtain) =================

/**
 * The curtain: near-black, the backdrop fading in, a left and a bottom scrim, bottom-left "Now Playing" with a small
 * rainbow ring, the logo or title and one line. No stage text, no minimum time. In 320 ms, out 380 ms.
 * Errors: one plain heading, one sentence, and the ways forward as pills.
 */
@Composable
fun Curtain(s: PlayerSession, stage: StageControl) {
    val c = s.curtain
    var last by remember { mutableStateOf(c) }
    if (c != null) last = c
    val a by animateFloatAsState(if (c != null) 1f else 0f, tween(if (c != null) 320 else 380))
    if (a == 0f) return
    val shown = last ?: return
    val t = s.target
    val d = s.details
    Box(Modifier.fillMaxSize().alpha(a).background(Color(0xFF050506)).pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
    }) {
        NetImage(d?.backdropUrl ?: t.backdrop, Modifier.fillMaxSize())
        Box(Modifier.fillMaxHeight().fillMaxWidth(0.62f).background(Brush.horizontalGradient(listOf(Color(0xCC050506), Color(0x6E050506), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.42f).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6050506)))))
        Row(Modifier.align(Alignment.TopEnd).padding(end = 48.dp, top = 40.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundButton(if (stage.fullScreen) Glyph.UNFULL else Glyph.FULL, if (stage.fullScreen) "Exit Full Screen" else "Full Screen", onClick = { stage.toggleFullScreen() }, size = 44.dp, iconSize = 20.dp)
            RoundButton(Glyph.CLOSE, "Close Player", onClick = { s.close() }, size = 44.dp, iconSize = 18.dp)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 84.dp).widthIn(max = 760.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.trailer) Text("Trailer", style = Look.Kicker, color = Look.Text2)
                    else Text("NOW PLAYING", style = Look.NowPlaying, color = Look.NowGreen)
                if (shown is PlayerSession.Curtain.Loading) {
                    Spacer(Modifier.width(12.dp))
                    Spinner(20)
                }
            }
            Spacer(Modifier.height(14.dp))
            TitleMark(d?.logoUrl ?: t.logo, t.title, maxW = 440.dp, maxH = 140.dp, textSize = 38)
            Spacer(Modifier.height(12.dp))
            val line = when {
                s.trailer -> "Trailer"
                t.isEpisode -> "S${t.season} · E${t.episode}" + ((d?.episodeName ?: t.episodeTitle)?.let { "    $it" } ?: "")
                else -> (d?.year ?: t.year)?.toString()
            }
            if (line != null) Text(line, style = Look.Line, color = Look.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            s.curtainNote?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = if (s.noteCam) Look.AmberText else Look.Text.copy(alpha = 0.92f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            if (shown is PlayerSession.Curtain.Error) {
                Spacer(Modifier.height(28.dp))
                Text(shown.title, color = Look.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(shown.message, color = Look.Text2, fontSize = 15.sp)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Try Again", { s.tryAgain() }, primary = true)
                    if (!s.trailer) {
                        if (shown.canSearchAll) PillButton("Search All Sources", { s.tryAgain(loose = true) })
                        if (t.tmdbId > 0) PillButton("Change Source", { s.openSheet(PlayerSession.Sheet.SOURCE) })
                    }
                    PillButton("Back", { s.close() })
                }
            }
        }
        // Change Source opened from the error block draws above the curtain.
        if (s.sheet == PlayerSession.Sheet.SOURCE) Sheets(s)
    }
}

/** A small rainbow ring turning once a second (the curtain's "loading" mark). */
@Composable
fun Spinner(sizeDp: Int) {
    var angle by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { val t0 = System.nanoTime(); while (true) { delay(16); angle = ((System.nanoTime() - t0) / 1e6f / 1000f * 360f) % 360f } }
    Canvas(Modifier.size(sizeDp.dp).rainbowUser(player = true)) {
        val st = 2.5.dp.toPx()
        rainbowArc(angle, 110f, st, st)
    }
}

// ================= Pause screen =================

/** 8 s paused with no input: picture dims, a card bottom-left says what's playing and how much is left. */
@Composable
fun PauseScreen(s: PlayerSession) {
    AnimatedVisibility(s.pauseScreen, enter = fadeIn(tween(700)), exit = fadeOut(tween(300))) {
        val t = s.target
        val d = s.details
        val es = s.engineState
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.align(Alignment.BottomStart).padding(start = 72.dp, bottom = 88.dp).widthIn(max = 640.dp)) {
                Text("You're Watching", style = Look.Kicker, color = Look.Text2)
                Spacer(Modifier.height(14.dp))
                // A trailer: its name and the time left only (the stick's trailer request carries nothing else).
                TitleMark(if (s.trailer) null else d?.logoUrl, t.title, maxW = 420.dp, maxH = 120.dp, textSize = 40)
                if (!s.trailer) PauseFacts(s)
                if (es.durationMs > 0) {
                    Spacer(Modifier.height(16.dp))
                    Text(leftText(es.durationMs - es.positionMs), style = Look.Small, color = Look.Text)
                }
            }
        }
    }
}

/** The pause screen's line, badges and synopsis. */
@Composable
private fun PauseFacts(s: PlayerSession) {
    val t = s.target
    val d = s.details
    Spacer(Modifier.height(12.dp))
    val line = if (t.isEpisode) "S${t.season} · E${t.episode}" + ((d?.episodeName ?: t.episodeTitle)?.let { "  $it" } ?: "")
    else listOfNotNull((d?.year ?: t.year)?.toString(), d?.runtimeMin?.takeIf { it > 0 }?.let(::runtimeText), d?.genres?.take(2)?.joinToString(", ")?.ifEmpty { null }).joinToString(SEP)
    Text(line, style = Look.Line, color = Look.Text)
    Spacer(Modifier.height(14.dp))
    BadgeRow(badgesFor(s, full = true))
    val synopsis = (if (t.isEpisode) d?.episodeOverview else null) ?: d?.overview
    if (!synopsis.isNullOrBlank()) {
        Spacer(Modifier.height(16.dp))
        Text(synopsis, style = Look.Body, color = Look.Text2, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

// ================= Post-play =================

@Composable
fun PostPlayLayer(s: PlayerSession) {
    val pp = s.postPlay ?: return
    Box(Modifier.fillMaxSize()) {
        when (pp) {
            is PlayerSession.PostPlay.UpNext -> UpNextCard(s, pp, Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = 56.dp))
            is PlayerSession.PostPlay.MoreLikeThis -> MoreLikeThis(s, pp)
            PlayerSession.PostPlay.StillWatching -> StillWatching(s)
        }
    }
}

/** Up Next (PostPlay): bottom-right card, the next still, a countdown line filling with the rainbow, Play and Credits. */
@Composable
private fun UpNextCard(s: PlayerSession, u: PlayerSession.PostPlay.UpNext, modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(u.countdownFrom) { while (u.countdownFrom != null) { now = System.currentTimeMillis(); delay(50) } }
    val n = u.next
    val nd = s.nextDetails ?: u.nextDetails
    AnimatedVisibility(true, modifier, enter = slideInHorizontally(tween(320)) { it / 4 } + fadeIn(tween(320))) {
        Column(
            Modifier.width(460.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xF00E0E10)).border(1.dp, Look.Hairline, RoundedCornerShape(20.dp)).padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(168.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF18181B))) {
                    NetImage(nd?.episodeStillUrl ?: nd?.backdropUrl ?: n.backdrop, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Next Episode", style = Look.Small, color = Look.Text2)
                    Spacer(Modifier.height(6.dp))
                    // One line (two at most): "S1 E3  ·  Name" in white, "  ·  45 min" after it in gray (PostPlay.showUpNext).
                    val name = (nd?.episodeName ?: n.episodeTitle)?.takeIf { it.isNotBlank() }
                    val ep = "S${n.season} E${n.episode}" + (name?.let { "$SEP$it" } ?: "")
                    // "·  15 min" stays whole and never starts a line: a long name wraps before it instead.
                    val rt = nd?.episodeRuntimeMin?.takeIf { it > 0 }?.let { "\u00A0\u00A0·\u00A0\u00A0" + runtimeText(it).replace(' ', '\u00A0') }.orEmpty()
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            append(ep)
                            if (rt.isNotEmpty()) pushStyle(androidx.compose.ui.text.SpanStyle(color = Look.Text3)).also { append(rt); pop() }
                        },
                        style = Look.Line, color = Look.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (u.countdownFrom != null) {
                Spacer(Modifier.height(14.dp))
                val f = ((now - u.countdownFrom).toFloat() / PlayerSession.UP_NEXT_COUNTDOWN_MS).coerceIn(0f, 1f)
                Canvas(Modifier.fillMaxWidth().height(3.dp).rainbowUser(player = true)) {
                    drawRect(Color.White.copy(alpha = 0.27f))
                    rainbowLine(f)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Play", { s.upNextPlay() }, Glyph.PLAY, primary = true)
                PillButton("Credits", { s.dismissPostPlay() })
            }
        }
    }
}

/** More Like This: four backdrops after a movie (or a show's last episode). */
@Composable
private fun MoreLikeThis(s: PlayerSession, m: PlayerSession.PostPlay.MoreLikeThis) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000), Color(0xF5000000))))) {
        Column(Modifier.align(Alignment.BottomStart).padding(start = 64.dp, end = 64.dp, bottom = 56.dp)) {
            val heading = if (s.target.isEpisode) "You've reached the end of ${s.target.title}" else "More Like This"
            Text(heading, style = Look.SheetTitle, color = Look.Text)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (r in m.recs.take(4)) {
                    Modifier.control(r.title, onClick = { s.openRec(r) }) { mod, st ->
                        Column(mod.width(300.dp)) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF18181B))
                                    .litEdge(st.lit, 2.dp, RoundedCornerShape(12.dp)),
                            ) { NetImage(r.backdropUrl, Modifier.fillMaxSize()) }
                            Spacer(Modifier.height(8.dp))
                            Text(r.title, style = Look.Line, color = Look.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (m.ended) PillButton("Watch Again", { s.watchAgain() }, Glyph.RESTART) else PillButton("Watch Credits", { s.dismissPostPlay() })
                PillButton("Back to Browse", { s.close() })
            }
        }
    }
}

@Composable
private fun StillWatching(s: PlayerSession) {
    val t = s.target
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(24.dp)).background(Look.Sheet).border(1.dp, Look.Hairline, RoundedCornerShape(24.dp)).padding(32.dp),
        ) {
            Text(t.title, style = Look.Small, color = Look.Text2)
            Spacer(Modifier.height(8.dp))
            Text("Are You Still Watching?", style = Look.SheetTitle, color = Look.Text)
            Spacer(Modifier.height(8.dp))
            Text("S${t.season} E${t.episode}" + ((s.details?.episodeName ?: t.episodeTitle)?.let { "$SEP$it" } ?: ""), style = Look.Line, color = Look.Text2)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Continue Watching", { s.continueWatching() }, Glyph.PLAY, primary = true)
                PillButton("Back to Browse", { s.close() })
            }
        }
    }
}
