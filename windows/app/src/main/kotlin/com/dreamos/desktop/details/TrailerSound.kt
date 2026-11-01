package com.dreamos.desktop.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dreamos.desktop.settings.kit.Glyph
import com.dreamos.desktop.trailer.TrailerBackdropState
import com.dreamos.desktop.trailer.TrailerGlassButton

/**
 * The hero trailer's speaker (owner, 2026-10-30): a round button while a trailer plays, the muted speaker or the
 * speaker with sound, named "Turn Sound On" / "Turn Sound Off". Glass at rest; lit (hover or focus) it turns pearl in
 * the rainbow ring, like the shell's round buttons. Gone when no trailer plays; it fades with the rest of the page's
 * UI (the caller puts it under trailerUi). The M key does the same (TrailerBackdrop's trailerWatch). The full-screen
 * trailer's controls are the same button (trailer/TrailerFullScreen.kt).
 */
@Composable
fun TrailerSoundButton(state: TrailerBackdropState, modifier: Modifier = Modifier) {
    AnimatedVisibility(state.playing, modifier, enter = fadeIn(tween(240)), exit = fadeOut(tween(200))) {
        val on = state.soundOn
        TrailerGlassButton(
            if (on) "Turn Sound Off" else "Turn Sound On",
            if (on) Glyph.Speaker else Glyph.SpeakerOff,
            { state.toggleSound() },
        )
    }
}
