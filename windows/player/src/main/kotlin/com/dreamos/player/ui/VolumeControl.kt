package com.dreamos.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.dreamos.player.session.PlayerPrefs
import com.dreamos.player.session.PlayerSession
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Keys the volume slider keeps for itself while it has keyboard focus (the player's own Left / Right seek otherwise). */
val VolumeSliderKeys = setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown, Key.MoveHome, Key.MoveEnd)

private val SliderW = 120.dp
private val ThumbR = 7.dp
/** From the slider's edge to the track's ends: the thumb and the focus ring both fit inside at 0 and 100%. */
private val TrackInset = 13.dp

/**
 * The volume in the controls row (Windows only): the speaker button, then the slider. [compact] (a narrow window) keeps
 * only the speaker in the row; hovering or focusing it slides the slider out over the buttons beside it on a glass
 * pill, so nothing in the row moves. The slider reads the engine's level, so the wheel, Up / Down, M and a profile's
 * saved level all move it. [onSliderFocus]: the player hands its arrow keys to the slider while it is focused.
 */
@Composable
fun VolumeControl(s: PlayerSession, compact: Boolean, onSliderFocus: (Boolean) -> Unit) {
    val es = s.engineState
    val silent = es.muted || es.volume == 0
    val speaker: @Composable () -> Unit = {
        RoundButton(if (silent) "Unmute" else "Mute", onClick = { s.toggleMute(hud = false) }, size = 44.dp) { ink ->
            SpeakerIcon(es.volume, es.muted, ink, 22.dp)
        }
    }
    var dragging by remember { mutableStateOf(false) }
    if (!compact) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            speaker()
            Spacer(Modifier.width(6.dp))
            VolumeSlider(s, onDragging = { dragging = it }, onFocus = onSliderFocus)
        }
        return
    }
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    var focusWithin by remember { mutableStateOf(false) }
    val want = hovered || focusWithin || dragging
    // Closes a moment after the pointer leaves, so a pointer grazing the edge doesn't make it flicker.
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(want) { if (want) open = true else { delay(350); open = false } }
    val pill = RoundedCornerShape(22.dp)
    // Takes one button's width in the row; the open pill is drawn over the buttons to its right (and above them).
    Box(
        Modifier.zIndex(1f).layout { m, c ->
            val p = m.measure(c.copy(minWidth = 0, maxWidth = Constraints.Infinity))
            layout(44.dp.roundToPx(), p.height) { p.place(0, 0) }
        },
    ) {
        Row(
            // Not clipped: the value above the thumb stands out of the pill.
            Modifier.hoverable(src)
                .onFocusChanged { focusWithin = it.hasFocus }
                .then(if (open) Modifier.background(Color(0xF20E0E10), pill).border(1.dp, Look.Edge, pill) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            speaker()
            AnimatedVisibility(
                open,
                enter = expandHorizontally(tween(180), expandFrom = Alignment.Start, clip = false) + fadeIn(tween(180)),
                exit = shrinkHorizontally(tween(160), shrinkTowards = Alignment.Start, clip = false) + fadeOut(tween(120)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(6.dp))
                    VolumeSlider(s, onDragging = { dragging = it }, onFocus = onSliderFocus)
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
    }
}

/**
 * The slider (the timeline's look at a smaller size): the dim glass track, the level in the fading rainbow, a white
 * thumb on hover, drag or focus. Click anywhere to jump, drag to set (the level follows the pointer at once and is
 * saved on release), wheel over it as anywhere over the picture. Focused: Left / Right / Up / Down 5%, Home / End 0 /
 * 100%, the value shown above the thumb; a rainbow ring marks the focus. Muted, it shows 0 and works from there.
 */
@Composable
private fun VolumeSlider(s: PlayerSession, onDragging: (Boolean) -> Unit, onFocus: (Boolean) -> Unit) {
    val es = s.engineState
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val focused by src.collectIsFocusedAsState()
    var dragV by remember { mutableStateOf<Int?>(null) }
    val level = dragV ?: if (es.muted) 0 else es.volume
    val active = hovered || focused || dragV != null
    val barH by animateDpAsState(if (active) 6.dp else 4.dp, tween(140))

    DisposableEffect(focused) {
        onFocus(focused)
        onDispose { if (focused) onFocus(false) }
    }
    // Holding the thumb still keeps the controls up as moving it does.
    LaunchedEffect(dragV != null) { while (dragV != null) { s.input(); delay(500) } }

    fun valueAt(x: Float, inset: Float, width: Float): Int = (((x - inset) / (width - 2 * inset)).coerceIn(0f, 1f) * 100).roundToInt()
    fun step(to: Int) = s.setVolume(to.coerceIn(0, 100))
    val boost = s.prefs.boost
    val said = if (es.muted && dragV == null) "Muted" else "$level%"

    BoxWithConstraints(Modifier.width(SliderW).height(44.dp)) {
        Canvas(
            Modifier.fillMaxSize()
                .semantics {
                    contentDescription = "Volume"
                    stateDescription = if (boost > 0) "$said, Boost ${PlayerPrefs.BOOST_LABELS[boost]}" else said
                    progressBarRangeInfo = ProgressBarRangeInfo(level.toFloat(), 0f..100f, steps = 19)
                    setProgress { v -> step(v.roundToInt()); true }
                }
                .focusable(interactionSource = src)
                .hoverable(src)
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionRight, Key.DirectionUp -> { step(level + 5); true }
                        Key.DirectionLeft, Key.DirectionDown -> { step(level - 5); true }
                        Key.MoveHome -> { step(0); true }
                        Key.MoveEnd -> { step(100); true }
                        else -> false
                    }
                }
                .pointerHoverIcon(PointerIcon.Hand)
                .rainbowUser(player = true)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            val x = e.changes.first().position.x
                            fun at() = valueAt(x, TrackInset.toPx(), size.width.toFloat())
                            when (e.type) {
                                PointerEventType.Press -> {
                                    val v = at(); dragV = v; onDragging(true); s.setVolume(v, save = false)
                                }
                                PointerEventType.Move -> dragV?.let { old ->
                                    val v = at()
                                    if (v != old) { dragV = v; s.setVolume(v, save = false) }
                                }
                                PointerEventType.Release -> dragV?.let { v ->
                                    s.setVolume(v, save = true); dragV = null; onDragging(false)
                                }
                            }
                            // The picture under the controls never sees a press here as a click (play / pause).
                            if (e.type != PointerEventType.Scroll) e.changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            val r = ThumbR.toPx()
            val inset = TrackInset.toPx()
            val h = barH.toPx()
            val y = size.height / 2 - h / 2
            val w = size.width - 2 * inset
            val cr = CornerRadius(h / 2)
            drawRoundRect(Color.White.copy(alpha = 0.20f), Offset(inset, y), Size(w, h), cr)
            val hue = RainbowClock.hue.value
            val filled = w * level / 100f
            if (filled > 0f) drawRoundRect(hue, Offset(inset, y), Size(maxOf(filled, h), h), cr)
            val tx = inset + filled
            if (active) {
                if (dragV != null) drawCircle(hue.copy(alpha = 0.34f), r * 1.75f, Offset(tx, size.height / 2))
                drawCircle(Color.White, r, Offset(tx, size.height / 2))
            }
            // Keyboard focus: the rainbow ring every control wears when lit, around the slider's pill.
            if (focused) {
                val sw = 2.dp.toPx()
                val top = size.height / 2 - r - 4.dp.toPx()
                drawRoundRect(hue, Offset(sw / 2, top), Size(size.width - sw, size.height - 2 * top), CornerRadius(size.height / 2 - top), style = Stroke(sw))
            }
        }
        // The value above the thumb while dragging or focused ("65%"), clear of the thumb's halo.
        if (dragV != null || focused) {
            val inset = with(LocalDensity.current) { TrackInset.toPx() }
            val tx = inset + (constraints.maxWidth - 2 * inset) * level / 100f
            ValueTip(
                if (boost > 0 && !(es.muted && dragV == null)) "$said  ·  Boost ${PlayerPrefs.BOOST_LABELS[boost]}" else said,
                Modifier.layout { m, c ->
                    val p = m.measure(Constraints())
                    layout(c.maxWidth, c.maxHeight) { p.place((tx - p.width / 2f).roundToInt(), -p.height - 2.dp.roundToPx()) }
                },
            )
        }
    }
}

@Composable
private fun ValueTip(text: String, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xF00E0E10)).border(1.dp, Look.Edge, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) { Text(text, style = Look.Time, color = Look.Text, maxLines = 1) }
}
