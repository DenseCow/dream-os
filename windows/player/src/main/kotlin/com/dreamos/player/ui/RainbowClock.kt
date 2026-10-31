package com.dreamos.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlin.math.pow

/**
 * The Dream rainbow as it shows everywhere on Windows (owner, 2026-10-30, as the iPhone's Rainbow.fadeHex and the
 * Fire Stick's Rainbow.fade): ONE color at a time, easing from hue to hue, 2.2 s on each of the eight. Never a
 * spectrum laid side by side. Play-type fills, focus rings and halos, progress lines, the tab underline, the
 * chapter rail, toggles, auras and kicker words all take [hue] from this one clock, so they always agree.
 *
 * The clock steps about 30 times a second and only while something that shows it is on screen. Shell users (the
 * shell's pages) hold still while [shellHeld] (the window minimized, Dream Player over it, Windows' animation
 * effects off); player users (Dream Player's own controls) keep it going while the player shows. Readers take
 * [hue] in their draw phase where they can, so a step repaints them and nothing else.
 */
object RainbowClock {
    private const val HUE_MS = 2_200L
    private val now = mutableStateOf(at(System.currentTimeMillis()))
    val hue: State<Color> get() = now

    private var shellUsers by mutableIntStateOf(0)
    private var playerUsers by mutableIntStateOf(0)
    /** The shell can't be seen or shouldn't move (the shell sets it). Player users keep the clock going regardless. */
    var shellHeld by mutableStateOf(false)
    private var started = false

    /** The fade at [ms]: smoothstep from one of [Look.Rainbow] to the next (the shell's Rainbow.fade). */
    fun at(ms: Long): Color {
        val n = Look.Rainbow.size
        val pos = (ms % (HUE_MS * n)) / HUE_MS.toFloat()
        val i = pos.toInt()
        val t = pos - i
        return lerp(Look.Rainbow[i], Look.Rainbow[(i + 1) % n], t * t * (3 - 2 * t))
    }

    /** The current hue without subscribing to it (a still value for something not lit). */
    fun peek(): Color = Snapshot.withoutReadObservation { now.value }

    /**
     * Words and icons ON the fade: near-black, or white where it reads better (never on today's hues). Deeper than
     * [Look.Ink]: the fade's darkest moment (#6F65FF, between blue and violet) gives #16161A 4.28:1, #0A0A0C 4.69:1
     * (antislop contrast-check; 4.78 to 15.3:1 on the eight hues).
     */
    val ink = Color(0xFF0A0A0C)
    val inkNow: State<Color> = derivedStateOf { inkOn(now.value) }

    fun inkOn(fill: Color): Color {
        val l = luminance(fill)
        return if ((l + 0.05f) / 0.0531f >= 1.05f / (l + 0.05f)) ink else Color.White
    }

    /** The fade as a text color on the dark page and glass (kickers, the version, "Dream OS"); see [TEXT_LIFT]. */
    fun text(c: Color): Color = lerp(c, Color.White, TEXT_LIFT)

    /** How far text in the fade is lifted toward white so its darkest moment still reads at 4.5:1 on dark glass. */
    const val TEXT_LIFT = 0.2f

    private fun luminance(c: Color): Float {
        fun lin(v: Float) = if (v <= 0.03928f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * lin(c.red) + 0.7152f * lin(c.green) + 0.0722f * lin(c.blue)
    }

    private fun start() {
        if (started) return
        started = true
        CoroutineScope(SupervisorJob() + Dispatchers.Swing).launch {
            snapshotFlow { playerUsers > 0 || (shellUsers > 0 && !shellHeld) }.distinctUntilChanged().collectLatest { run ->
                while (run) { now.value = at(System.currentTimeMillis()); delay(33) }
            }
        }
    }

    fun enter(player: Boolean) { start(); if (player) playerUsers++ else shellUsers++ }
    fun leave(player: Boolean) { if (player) playerUsers-- else shellUsers-- }
}

/**
 * Keeps the clock going while this element is at least partly inside the window (scrolled away, or its page gone,
 * it lets go). [player]: one of Dream Player's own controls.
 */
fun Modifier.rainbowUser(player: Boolean = false): Modifier = composed {
    var shown by remember { mutableStateOf(false) }
    val counted = shown
    DisposableEffect(counted) {
        if (counted) RainbowClock.enter(player)
        onDispose { if (counted) RainbowClock.leave(player) }
    }
    onGloballyPositioned { c -> shown = c.boundsInWindow().let { it.width > 0f && it.height > 0f } }
}

/**
 * The fade's hue, read in composition, while [active] (counted toward the clock); otherwise a still hue that never
 * recomposes its reader. For things that are lit (a hovered ring) or plainly on screen while composed.
 */
@Composable
fun rainbowHue(active: Boolean = true, player: Boolean = false): Color {
    DisposableEffect(active, player) {
        if (active) RainbowClock.enter(player)
        onDispose { if (active) RainbowClock.leave(player) }
    }
    return if (active) RainbowClock.hue.value else remember(active) { RainbowClock.peek() }
}

/** Paints the fade behind the content, rounded by [radius], counting toward the clock while on screen. */
fun Modifier.playFade(radius: Dp, player: Boolean = true): Modifier =
    rainbowUser(player).drawBehind { drawRoundRect(RainbowClock.hue.value, cornerRadius = CornerRadius(radius.toPx())) }

/** The lit ring for a filled button: the fade [gap] outside the shape, so it reads against the page on every hue. */
fun Modifier.outerRainbowRing(radius: Dp, gap: Dp = 3.dp, width: Dp = 2.dp): Modifier = drawBehind {
    val g = gap.toPx() + width.toPx() / 2
    drawRoundRect(RainbowClock.hue.value, Offset(-g, -g), Size(size.width + 2 * g, size.height + 2 * g), CornerRadius(radius.toPx() + g), style = Stroke(width.toPx()))
}
