package com.dreamos.desktop.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dreamos.desktop.settings.kit.Dream
import com.dreamos.desktop.settings.kit.Glyph
import com.dreamos.desktop.settings.kit.GlyphIcon
import com.dreamos.desktop.settings.kit.dreamClick
import com.dreamos.desktop.settings.kit.lit
import com.dreamos.desktop.settings.kit.rainbowEdge
import com.dreamos.desktop.settings.kit.rainbowNow
import com.dreamos.desktop.trailer.TrailerBackdropState

/**
 * The hero trailer's speaker (owner, 2026-10-30): a round button while a trailer plays, the muted speaker or the
 * speaker with sound, named "Turn Sound On" / "Turn Sound Off". Glass at rest; lit (hover or focus) it turns pearl in
 * the rainbow ring, like the shell's round buttons. Gone when no trailer plays; it fades with the rest of the page's
 * UI (the caller puts it under trailerUi). The M key does the same (TrailerBackdrop's trailerWatch).
 */
@Composable
fun TrailerSoundButton(state: TrailerBackdropState, modifier: Modifier = Modifier) {
    AnimatedVisibility(state.playing, modifier, enter = fadeIn(tween(240)), exit = fadeOut(tween(200))) {
        val src = remember { MutableInteractionSource() }
        val lit = src.lit()
        val glow = rainbowNow(lit.any)
        val on = state.soundOn
        Box(
            Modifier
                .semantics { contentDescription = if (on) "Turn Sound Off" else "Turn Sound On" }
                .rainbowEdge(lit.any, glow, 26.dp, edge = if (lit.focused) 3.dp else 2.dp, halo = if (lit.focused) 12.dp else 0.dp)
                .size(52.dp)
                .clip(CircleShape)
                .background(if (lit.any) Dream.Pearl else Color(0x8C141416))
                .dreamClick(src) { state.toggleSound() },
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(if (on) Glyph.Speaker else Glyph.SpeakerOff, if (lit.any) Dream.Ink else Dream.Text, 24.dp)
        }
    }
}
