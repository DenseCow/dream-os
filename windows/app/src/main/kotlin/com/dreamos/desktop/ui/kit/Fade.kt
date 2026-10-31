package com.dreamos.desktop.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.Dp
import com.dreamos.desktop.shell.Motion
import com.dreamos.player.ui.RainbowClock
import com.dreamos.player.ui.rainbowHue
import com.dreamos.player.ui.rainbowUser

/**
 * The shell's side of the one rainbow clock ([RainbowClock], in Dream Player's module so the player and the shell
 * share it): every rainbow in the shell is ONE color fading from hue to hue (owner, 2026-10-30, as the iPhone's
 * Rainbow.fadeHex and the Fire Stick's Rainbow.fade), never the spectrum side by side. The shell's uses hold still
 * while the window is minimized ([minimized], Main's window listener), Dream Player is up over it ([covered],
 * AppServices.playerShowing) or Windows' animation effects are off.
 */
object ShellRainbow {
    @Volatile var minimized = false
        set(v) { field = v; sync() }
    @Volatile var covered = false
        set(v) { field = v; sync() }

    private fun sync() { RainbowClock.shellHeld = minimized || covered || Motion.reduced }

    init { sync() }

    val hue: State<Color> get() = RainbowClock.hue
    /** Words and icons on a fade fill ([RainbowClock.inkOn]), changing only when the choice flips. */
    val inkNow: State<Color> get() = RainbowClock.inkNow
}

/** Paints the fade behind the content in [shape], counting toward the clock while it is on screen. */
fun Modifier.rainbowFill(shape: Shape = RectangleShape): Modifier = touchShell().rainbowUser().drawBehind {
    drawOutline(shape.createOutline(size, layoutDirection, this), RainbowClock.hue.value)
}

/** A Play-type button's fill: the fade behind the content, rounded by [radius]. */
fun Modifier.playFade(radius: Dp): Modifier = rainbowFill(androidx.compose.foundation.shape.RoundedCornerShape(radius))

/** The fade's hue in composition while [active] (the clock runs for it); a still hue otherwise. */
@Composable
fun shellHue(active: Boolean = true): Color { ShellRainbow; return rainbowHue(active) }

/** The fade as words on the dark page or glass (a kicker, a title in the brand's color), lifted to read at 4.5:1. */
@Composable
fun rainbowText(): Color = RainbowClock.text(shellHue())

/** Makes sure the shell's hold conditions are in the clock before a shell user counts. */
private fun Modifier.touchShell(): Modifier { ShellRainbow; return this }
