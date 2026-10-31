package com.dreamos.player.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamos.player.api.SkipMarker

/**
 * The timeline (PlayerScrubber): 4 dp at rest, 8 dp under the pointer; remaining white 20%, buffered white 36%, played
 * in the rainbow (the whole rainbow lies across the track and the played part uncovers it); marker ticks above the bar
 * for intro, recap and credits. Hovering shows a preview card (a frame
 * from the preview decoder when one is ready, the time, the chapter); pressing and dragging scrubs, and the seek happens
 * on release. Keyboard: focus it with Tab, Left / Right move 10 s.
 */
@Composable
fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    bufferedToMs: Long,
    markers: List<SkipMarker>,
    onSeek: (Long) -> Unit,
    onScrubbing: (Boolean) -> Unit,
    frameAt: (Long) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    var hoverX by remember { mutableStateOf<Float?>(null) }
    var dragX by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableStateOf(1f) }
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val active = hoverX != null || dragX != null || focused
    val barH by animateDpAsState(if (active) 8.dp else 4.dp, tween(140))
    val dur = durationMs.coerceAtLeast(1)
    fun msAt(x: Float) = ((x / widthPx).coerceIn(0f, 1f) * dur).toLong()
    val shownMs = dragX?.let(::msAt) ?: positionMs

    BoxWithConstraints(modifier.fillMaxWidth().height(40.dp)) {
        val density = LocalDensity.current
        Canvas(
            Modifier.fillMaxWidth().height(40.dp)
                .semantics { contentDescription = "Timeline, ${clockText(positionMs)} of ${clockText(durationMs)}" }
                .focusable(interactionSource = src)
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> { onSeek(positionMs - 10_000); true }
                        Key.DirectionRight -> { onSeek(positionMs + 10_000); true }
                        else -> false
                    }
                }
                .pointerHoverIcon(PointerIcon.Hand)
                .rainbowUser(player = true)
                .pointerInput(durationMs) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            val x = e.changes.first().position.x
                            widthPx = size.width.toFloat()
                            when (e.type) {
                                PointerEventType.Enter, PointerEventType.Move -> {
                                    hoverX = x
                                    if (dragX != null) dragX = x
                                }
                                PointerEventType.Exit -> hoverX = null
                                PointerEventType.Press -> { dragX = x; onScrubbing(true) }
                                PointerEventType.Release -> {
                                    dragX?.let { onSeek(msAt(it)) }
                                    dragX = null
                                    onScrubbing(false)
                                }
                            }
                            e.changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            val h = barH.toPx()
            val y = size.height / 2 - h / 2
            val r = CornerRadius(h / 2)
            val w = size.width
            drawRoundRect(Color.White.copy(alpha = 0.20f), Offset(0f, y), Size(w, h), r)
            val buf = (bufferedToMs.toFloat() / dur).coerceIn(0f, 1f) * w
            if (buf > 0) drawRoundRect(Color.White.copy(alpha = 0.36f), Offset(0f, y), Size(buf, h), r)
            val played = (positionMs.toFloat() / dur).coerceIn(0f, 1f) * w
            val rainbow = RainbowClock.hue.value
            if (played > 0f) drawRoundRect(rainbow, Offset(0f, y), Size(maxOf(played, h), h), r)
            // Dragging ahead: the rainbow still to come, at half strength, up to the pointer.
            dragX?.let { dx -> if (dx > played) drawRect(rainbow, Offset(played, y), Size(dx - played, h), alpha = 0.45f) }
            // Marker ticks: 3 wide, white 90%, just above the bar.
            for (m in markers) {
                if (m.estimated) continue
                val pts = when (m.kind) {
                    SkipMarker.Kind.INTRO -> listOf(m.startMs, m.endMs)
                    SkipMarker.Kind.RECAP -> listOf(m.endMs)
                    SkipMarker.Kind.CREDITS -> listOf(m.startMs)
                }
                for (p in pts) {
                    val mx = (p.toFloat() / dur).coerceIn(0f, 1f) * w
                    drawRect(Color.White.copy(alpha = 0.9f), Offset(mx - 1.5f, y - 7.dp.toPx()), Size(3f, 5.dp.toPx()))
                }
            }
            val tx = (dragX ?: played)
            if (active) {
                if (dragX != null) drawCircle(rainbow.copy(alpha = 0.34f), 8.5.dp.toPx() * 1.75f, Offset(tx, size.height / 2))
                drawCircle(Color.White, 8.5.dp.toPx(), Offset(tx, size.height / 2))
            }
        }
        // Preview card above the pointer (or the drag), clamped inside the bar's width.
        val px = dragX ?: hoverX
        if (px != null && durationMs > 0) {
            val ms = msAt(px)
            val cardW = 208.dp
            val cardWPx = with(density) { cardW.toPx() }
            val left = (px - cardWPx / 2).coerceIn(0f, (constraints.maxWidth - cardWPx).coerceAtLeast(0f))
            PreviewCard(
                ms = ms,
                // The chapter there, else how far from the picture's spot (PlayerOverlay.updatePreview).
                chapter = chapterAt(markers, ms) ?: offsetText(ms - positionMs),
                frame = frameAt(ms),
                // Measured without the bar's 40 dp height limit and placed above it, its foot clear of the thumb.
                modifier = Modifier.layout { m, c ->
                    val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                    layout(c.maxWidth, c.maxHeight) { p.place(left.toInt(), -p.height + 4.dp.roundToPx()) }
                }.width(cardW),
            )
        }
    }
}

private fun chapterAt(markers: List<SkipMarker>, ms: Long): String? = markers.firstOrNull { !it.estimated && ms in it.startMs until it.endMs }?.let {
    when (it.kind) { SkipMarker.Kind.INTRO -> "Intro"; SkipMarker.Kind.RECAP -> "Recap"; SkipMarker.Kind.CREDITS -> "Credits" }
}

/** The scrub preview (iPhone ScrubPreviewCard): dark glass, the frame behind a hairline, the time and the chapter. */
@Composable
private fun PreviewCard(ms: Long, chapter: String?, frame: ImageBitmap?, modifier: Modifier) {
    Column(
        modifier.shadow(18.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Color(0xF0101012))
            .border(1.dp, Look.Hairline, RoundedCornerShape(16.dp)).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().height(108.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF161618))
                .border(1.dp, Look.Edge, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (frame != null) Image(frame, null, Modifier.fillMaxWidth().height(108.dp), contentScale = ContentScale.Crop)
            else GlyphIcon(Glyph.FILM, Look.Text3.copy(alpha = 0.5f), 26.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(clockText(ms), color = Look.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(chapter ?: " ", style = Look.Small, color = Look.Text3)
        Spacer(Modifier.size(2.dp))
    }
}
