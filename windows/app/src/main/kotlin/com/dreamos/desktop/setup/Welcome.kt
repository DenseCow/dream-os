package com.dreamos.desktop.setup

import com.dreamos.player.ui.rainbowUser
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamos.desktop.about.dreamMark
import com.dreamos.desktop.settings.kit.Dream
import com.dreamos.desktop.settings.kit.DreamType
import com.dreamos.desktop.settings.kit.Glyph
import kotlinx.coroutines.launch

/** The Welcome features, the iPhone's and the Fire Stick's names in their order, each with its drawn glyph. */
private val WelcomeFeatures = listOf(
    Glyph.Nodes to "Dream Core Engine", Glyph.Bolt to "Instant Play", Glyph.Film to "Dream Player", Glyph.Grid to "Everything in One Place",
    Glyph.Bell to "Never Miss an Episode", Glyph.Snow to "Seasonal Collections", Glyph.Sliders to "Sources & Quality", Glyph.People to "Profiles for Everyone",
)

/**
 * Setup's first screen, centered as one composition: the cloud mark large over a soft rainbow aura (the screen's one
 * focal point and the brand's only color), the title, the sentence, the features as two rows of four emblems (the
 * Fire Stick's "glyph over its name"), and Start Setup. Spacing between the three groups grows with the window, so
 * 1024 x 640 and 4K hold the same balance. It settles in once (fade and a short rise, under a second); nothing
 * moves after that.
 */
@Composable
fun WelcomeScreen(button: @Composable () -> Unit) {
    val mark = remember { dreamMark() }
    val reveal = remember { List(4) { Animatable(0f) } }
    LaunchedEffect(Unit) {
        reveal.forEachIndexed { i, a -> launch { kotlinx.coroutines.delay(i * 110L); a.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) } }
    }
    fun Modifier.rise(i: Int) = graphicsLayer { val t = reveal[i].value; alpha = t; translationY = (1f - t) * 14.dp.toPx() }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(0.9f))
        Box(Modifier.rise(0).size(width = 168.dp, height = 104.dp), contentAlignment = Alignment.Center) {
            // The aura: the fading rainbow (one hue at a time) blurred far past its edge, at low strength so the mark
            // stays the subject.
            // requiredSize lets it spill past the mark without taking layout room; the oval sits well inside its
            // canvas so the blur fades out before the layer's edge.
            Canvas(Modifier.requiredSize(width = 460.dp, height = 320.dp).rainbowUser().alpha(0.32f).blur(52.dp, BlurredEdgeTreatment.Unbounded)) {
                drawOval(com.dreamos.player.ui.RainbowClock.hue.value, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.33f, size.height * 0.32f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.34f, size.height * 0.36f))
            }
            if (mark != null) Image(mark, "Dream OS", Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(30.dp))
        Column(Modifier.rise(1), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Welcome to Dream OS", style = DreamType.Display.copy(fontSize = 46.sp, lineHeight = 54.sp, fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Text(
                "Your movies and shows in one place, ready to play in seconds. Setup takes about 15 minutes, and each step shows you how.",
                style = DreamType.Body.copy(fontSize = 18.5.sp, lineHeight = 27.sp, color = Dream.Text.copy(alpha = 0.80f)),
                textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 640.dp),
            )
        }
        Spacer(Modifier.weight(0.8f))
        Column(Modifier.rise(2), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            WelcomeFeatures.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { row.forEach { (g, t) -> Feature(g, t) } }
            }
        }
        Spacer(Modifier.weight(0.8f))
        Box(Modifier.rise(3)) { button() }
        Spacer(Modifier.weight(0.6f))
    }
}

@Composable
private fun Feature(glyph: Glyph, name: String) {
    Column(Modifier.width(184.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Emblem(glyph, 56.dp)
        Spacer(Modifier.height(12.dp))
        Text(name, style = DreamType.Row.copy(fontSize = 16.sp, lineHeight = 21.sp, color = Dream.Text.copy(alpha = 0.92f)), textAlign = TextAlign.Center, minLines = 2, maxLines = 2)
    }
}
