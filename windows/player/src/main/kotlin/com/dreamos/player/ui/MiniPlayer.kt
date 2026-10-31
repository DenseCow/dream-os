package com.dreamos.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dreamos.player.session.PlayerSession
import kotlinx.coroutines.delay

/**
 * The mini player: the same session in a small always-on-top window (PlayerStage.Mode.Mini). The picture keeps
 * playing with sound; hovering shows Play / Pause, Expand and Close and the title; dragging moves the window;
 * a double click or Expand brings the full player back. A thin rainbow line shows progress at all times.
 */
@Composable
fun MiniPlayer(s: PlayerSession, stage: StageControl) {
    val es = s.engineState
    var hovered by remember { mutableStateOf(false) }
    var lastClick by remember { mutableLongStateOf(0L) }
    var hoverAt by remember { mutableLongStateOf(0L) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(hoverAt) { delay(2500); if (System.currentTimeMillis() - hoverAt >= 2400) hovered = false }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f / 255f))
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Spacebar, Key.K -> { s.togglePlay(); true }
                    Key.Escape -> { stage.expand(); true }
                    Key.DirectionLeft -> { s.nudge(-10); true }
                    Key.DirectionRight -> { s.nudge(10); true }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var down: androidx.compose.ui.geometry.Offset? = null
                    var moved = false
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.first()
                        when (e.type) {
                            PointerEventType.Enter, PointerEventType.Move -> {
                                hovered = true; hoverAt = System.currentTimeMillis()
                                val d = down
                                if (d != null && c.pressed) {
                                    val dx = (c.position.x - d.x).toInt(); val dy = (c.position.y - d.y).toInt()
                                    if (moved || kotlin.math.abs(dx) + kotlin.math.abs(dy) > 4) { moved = true; stage.dragMini(dx, dy) }
                                }
                            }
                            PointerEventType.Exit -> hovered = false
                            PointerEventType.Press -> { down = c.position; moved = false }
                            PointerEventType.Release -> {
                                down = null
                                if (!moved && !c.isConsumed) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastClick < 350) { lastClick = 0; stage.expand() } else lastClick = now
                                }
                            }
                        }
                    }
                }
            },
    ) {
        AnimatedVisibility(hovered || es.paused, enter = fadeIn(tween(160)), exit = fadeOut(tween(300))) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xB3000000), Color(0x33000000), Color(0xB3000000))))) {
                Text(
                    s.target.let { if (it.isEpisode) "${it.title}$SEP" + "S${it.season} E${it.episode}" else it.title },
                    style = Look.Small, color = Look.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 14.dp).widthIn(max = 220.dp),
                )
                Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoundButton(Glyph.EXPAND, "Expand Player", onClick = { stage.expand() }, size = 36.dp, iconSize = 16.dp)
                    RoundButton(Glyph.CLOSE, "Close Player", onClick = { s.close() }, size = 36.dp, iconSize = 14.dp)
                }
                RoundButton(
                    if (es.paused) Glyph.PLAY else Glyph.PAUSE, if (es.paused) "Play" else "Pause",
                    onClick = { s.togglePlay() }, size = 52.dp, iconSize = 22.dp, modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        if (es.durationMs > 0) {
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).rainbowUser(player = true)) {
                drawRect(Color.White.copy(alpha = 0.22f))
                rainbowLine(es.positionMs.toFloat() / es.durationMs)
            }
        }
        if (s.curtain != null) Box(Modifier.align(Alignment.Center)) { Spinner(28) }
    }
}
