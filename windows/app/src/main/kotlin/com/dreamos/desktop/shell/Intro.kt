package com.dreamos.desktop.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.dreamos.desktop.ui.kit.LocalMetrics
import com.dreamos.desktop.ui.kit.Palette
import com.dreamos.desktop.ui.kit.Rainbow
import com.dreamos.desktop.ui.kit.Type
import kotlinx.coroutines.delay

/** The build's version and channel (from the build, never written in code): "4.3.4", "(DEV)" or "(PUBLIC)". */
object Build {
    val version: String = System.getProperty("dream.version") ?: "dev"
    val public: Boolean = System.getProperty("dream.channel") == "PUBLIC"
    val channelTag: String get() = if (public) "(PUBLIC)" else "(DEV)"
}

/**
 * The launch intro, like the other apps: the rainbow-glass cloud logo easing in on black, the version and channel small
 * in the bottom-right corner, then a fade to the app. Home loads behind it and the intro never makes it wait: it holds
 * only until [ready] (Home's hero and first rows are in), at least [INTRO_MIN_MS] so the logo registers, at most
 * [INTRO_MAX_MS] (then Home's own loading shapes take over).
 */
@Composable
fun Intro(reduced: Boolean, ready: () -> Boolean = { true }, done: () -> Unit) {
    val m = LocalMetrics.current
    val a = remember { Animatable(0f) }
    val out = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        com.dreamos.desktop.notices.UiSounds.swell() // Settings › Intro Sound
        a.animateTo(1f, tween(if (reduced) 0 else 650))
        while (true) {
            val shown = System.currentTimeMillis() - start
            if (shown >= INTRO_MAX_MS || (shown >= INTRO_MIN_MS && ready())) break
            delay(32)
        }
        com.dreamos.desktop.notices.UiSounds.chime()
        out.animateTo(0f, tween(if (reduced) 0 else 420))
        done()
    }
    // The version in the fading rainbow (one hue at a time, as the Fire Stick's boot screen), lifted to read on black.
    val version = com.dreamos.desktop.ui.kit.rainbowText()
    Box(Modifier.fillMaxSize().alpha(out.value).background(Palette.page)) {
        Image(
            painterResource("dream_logo.png"), "Dream OS",
            Modifier.align(Alignment.Center).fillMaxWidth(0.34f).alpha(a.value)
                .graphicsLayer { val s = 0.96f + 0.04f * a.value; scaleX = s; scaleY = s },
        )
        val line = buildAnnotatedString {
            append("Dream OS  ·  Version ")
            withStyle(SpanStyle(color = version, fontWeight = FontWeight.Bold)) { append(Build.version) }
            append(" ")
            withStyle(SpanStyle(color = if (Build.public) Palette.greenText else Palette.redText, fontWeight = FontWeight.Bold)) { append(Build.channelTag) }
        }
        Text(
            line, style = Type.style(13f, FontWeight.Medium, color = Color(0xE1FFFFFF)),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = m.u(24), bottom = m.u(20)).alpha(a.value)
                .clip(RoundedCornerShape(m.u(14))).background(Color(0x96121214))
                .border(m.u(1), Color(0x22FFFFFF), RoundedCornerShape(m.u(14)))
                .padding(horizontal = m.u(14), vertical = m.u(6)),
        )
    }
}

/** The logo eases in (650 ms) and stays still a moment before the fade. */
const val INTRO_MIN_MS = 1_100L
/** Past this the intro fades whether or not Home is in. */
const val INTRO_MAX_MS = 4_000L
