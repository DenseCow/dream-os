package com.dreamos.desktop.setup

import com.dreamos.player.ui.rainbowUser
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamos.desktop.settings.kit.Dream
import com.dreamos.desktop.settings.kit.DreamButton
import com.dreamos.desktop.settings.kit.DreamType
import com.dreamos.desktop.settings.kit.Glyph
import com.dreamos.desktop.settings.kit.LocalPagesNav
import com.dreamos.desktop.settings.kit.QrImage
import com.dreamos.desktop.settings.kit.drawGlyph
import com.dreamos.desktop.settings.kit.softEdges
import kotlinx.coroutines.launch

val Chapters = listOf("Get Ready", "Connect", "Your PC", "Make It Yours")

/**
 * The chapter rail: four labeled parts over a thin track; the finished share is the rainbow, stretched over exactly
 * what's done. Current chapter white and semibold, finished 70%, upcoming 52%.
 */
@Composable
fun ChapterRail(chapter: Int, step: Int, steps: Int, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Chapters.forEachIndexed { i, c ->
                Text(c, modifier = Modifier.weight(1f), style = DreamType.Meta.copy(
                    color = when { i == chapter -> Dream.Text; i < chapter -> Color.White.copy(alpha = 0.7f); else -> Color.White.copy(alpha = 0.52f) },
                    fontWeight = if (i == chapter) FontWeight.SemiBold else FontWeight.Medium))
            }
        }
        Spacer(Modifier.height(8.dp))
        val done = (chapter + (step + 1f) / steps.coerceAtLeast(1)) / Chapters.size
        Canvas(Modifier.fillMaxWidth().height(4.dp).rainbowUser()) {
            drawRoundRect(Color.White.copy(alpha = 0.16f), cornerRadius = CornerRadius(2.dp.toPx()))
            drawRoundRect(com.dreamos.player.ui.RainbowClock.hue.value, size = Size(size.width * done, size.height), cornerRadius = CornerRadius(2.dp.toPx()))
        }
    }
}

/** The step's emblem: Dream OS line art, a monoline glyph on a dark-glass disc inside a thin ring in the fading rainbow. */
@Composable
fun Emblem(glyph: Glyph, size: Dp = 52.dp) {
    Canvas(Modifier.size(size).rainbowUser()) {
        val s = this.size.minDimension
        drawCircle(Color(0xFF141417), s / 2)
        drawCircle(com.dreamos.player.ui.RainbowClock.hue.value, s / 2 - 1.dp.toPx(), style = Stroke(1.6.dp.toPx()))
        val inset = s * 0.27f
        inset(inset, inset, inset, inset) { drawGlyph(glyph, Color(0xFFF2F0F7), 0.9f) }
    }
}

/**
 * One setup step (SetupWizard.Frame): the rail, then one block of emblem + "Step n of m" + title, one or two
 * sentences, the body, the gate line and the buttons under it. [gate] shakes the gate line when it changes.
 * The block never needs to scroll at 1024 x 640; it can, softly, if a window is smaller still.
 */
@Composable
fun StepFrame(
    chapter: Int?,
    step: Int,
    steps: Int,
    glyph: Glyph?,
    title: String,
    sentence: String?,
    gate: String?,
    gateBump: Int,
    buttons: @Composable () -> Unit,
    big: Boolean = false,
    body: @Composable ColumnScope.() -> Unit,
) {
    val shake = remember { Animatable(0f) }
    LaunchedEffect(gateBump) { if (gateBump > 0) for (x in listOf(12f, -12f, 8f, -8f, 3f, 0f)) shake.animateTo(x, tween(50)) }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 880.dp).fillMaxWidth().fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp)) {
            if (chapter != null) ChapterRail(chapter, step, steps, Modifier.padding(horizontal = 20.dp))
            // The step is one block (heading, body, buttons right under it) sitting a little above the middle of the
            // room under the rail, so a short step doesn't leave its buttons stranded at the bottom of a tall window.
            // A step taller than the room scrolls, softly, as one; 20 dp each side keep focus halos inside the scroller.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val scroll = rememberScrollState()
                Column(
                    Modifier.align(androidx.compose.ui.BiasAlignment(0f, -0.4f)).fillMaxWidth().softEdges(scroll).verticalScroll(scroll)
                        .padding(start = 20.dp, end = 20.dp, top = if (chapter != null) 28.dp else 8.dp, bottom = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (glyph != null) { Emblem(glyph); Spacer(Modifier.width(20.dp)) }
                        Column {
                            if (chapter != null) Text("Step ${step + 1} of $steps", style = DreamType.Meta.copy(fontSize = 15.sp))
                            Text(title, style = if (big) DreamType.Display else DreamType.Title)
                        }
                    }
                    if (sentence != null) { Spacer(Modifier.height(12.dp)); Text(sentence, style = DreamType.Body.copy(fontSize = 17.sp, lineHeight = 25.sp, color = Dream.Text.copy(alpha = 0.88f))) }
                    Spacer(Modifier.height(22.dp))
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp)) { body() }
                    Spacer(Modifier.height(30.dp))
                    if (gate != null) {
                        Text(gate, style = DreamType.Row.copy(color = Dream.AmberText), modifier = Modifier.offset(x = shake.value.dp))
                        Spacer(Modifier.height(12.dp))
                    }
                    // Room for the focused button's halo inside the scroller.
                    Row(Modifier.padding(start = 4.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) { buttons() }
                }
            }
        }
    }
}

/** A website in a step or a guide: QR code, its short address, and Open in Browser (this is a PC). */
@Composable
fun SiteCard(title: String, url: String, qr: Dp = 128.dp) {
    val nav = LocalPagesNav.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        QrImage(url, qr)
        Spacer(Modifier.width(20.dp))
        Column {
            Text(title, style = DreamType.Row)
            Text(short(url), style = DreamType.Caption)
            Spacer(Modifier.height(10.dp))
            DreamButton("Open in Browser", { nav.openUrl(url) }, glyph = Glyph.Open)
        }
    }
}

fun short(url: String) = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

/** One page of a walkthrough: what to do, what you'll see, its websites, an optional picture. */
data class GuideStep(val text: String, val see: String? = null, val links: List<Pair<String, String>> = emptyList(), val art: (@Composable () -> Unit)? = null)

/** A problem and its fix (If Something Goes Wrong). */
data class Trouble(val problem: String, val fix: String)

/**
 * Show Me How: one step per page, "Step n of m", the step as the headline, the "see" line under it, its websites,
 * then Next Step / Done. Left and Right arrows page too; Esc returns.
 */
@Composable
fun Walkthrough(title: String, steps: List<GuideStep>, intro: String?, onDone: () -> Unit) {
    var i by remember { mutableIntStateOf(0) }
    val s = steps[i]
    Column(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionRight -> { if (i < steps.lastIndex) i++; true }
            Key.DirectionLeft -> { if (i > 0) i--; true }
            else -> false
        }
    }) {
        Text(title, style = DreamType.Meta)
        Spacer(Modifier.height(4.dp))
        Text("Step ${i + 1} of ${steps.size}", style = DreamType.Meta.copy(fontSize = 15.sp))
        Spacer(Modifier.height(8.dp))
        if (i == 0 && intro != null) { Text(intro, style = DreamType.Caption); Spacer(Modifier.height(10.dp)) }
        Text(s.text, style = DreamType.Big.copy(fontSize = 23.sp, lineHeight = 30.sp))
        s.see?.let { Spacer(Modifier.height(10.dp)); Text(it, style = DreamType.Body.copy(color = Dream.TextDim)) }
        Spacer(Modifier.height(18.dp))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            s.links.forEach { (t, u) -> SiteCard(t, u, if (s.links.size > 1) 96.dp else 128.dp) }
        }
        s.art?.let { Spacer(Modifier.height(16.dp)); it() }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (i < steps.lastIndex) DreamButton("Next Step", { i++ }, primary = true) else DreamButton("Done", onDone, primary = true)
            if (i > 0) DreamButton("Previous Step", { i-- })
        }
    }
}

/** If Something Goes Wrong: every problem in semibold with its fix under it. */
@Composable
fun TroubleList(items: List<Trouble>) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items.forEach { t ->
            Column {
                Text(t.problem, style = DreamType.Row.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(4.dp))
                Text(t.fix, style = DreamType.Body.copy(color = Dream.TextDim))
            }
        }
    }
}

/** A numbered "what you'll need" card: the number in a ringed circle, title, caption, facts. */
@Composable
fun NeedCard(n: Int, title: String, caption: String, facts: String, modifier: Modifier = Modifier) {
    Column(modifier.background(Dream.Glass, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(20.dp)) {
        Box(Modifier.size(34.dp).background(Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(34.dp).rainbowUser()) { drawCircle(com.dreamos.player.ui.RainbowClock.hue.value, size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.6.dp.toPx())) }
            Text("$n", style = DreamType.Row.copy(fontWeight = FontWeight.SemiBold))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = DreamType.Section)
        Spacer(Modifier.height(4.dp))
        Text(caption, style = DreamType.Caption)
        Spacer(Modifier.height(10.dp))
        Text(facts, style = DreamType.Meta.copy(color = Dream.Text))
    }
}
