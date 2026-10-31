package com.dreamos.desktop.notices

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamos.desktop.details.PillSpec
import com.dreamos.desktop.settings.Notice
import com.dreamos.desktop.settings.kit.Dream
import com.dreamos.desktop.settings.kit.DreamButton
import com.dreamos.desktop.settings.kit.DreamType
import com.dreamos.desktop.settings.kit.Glyph
import com.dreamos.desktop.settings.kit.Pill
import com.dreamos.desktop.settings.kit.RemoteImage
import com.dreamos.desktop.settings.kit.dreamClick
import com.dreamos.desktop.settings.kit.lit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Smoked = Color(0xF0141417)

/**
 * A notice banner at the top right (DreamNotices): its emoji in a dark disc with a soft halo for warnings, title and
 * body (both wrap in full), swapping to a smile every 2 s and ending on it. It rests 6 s (longer while the pointer is
 * on it), never takes focus; a click runs its action. Place it in a Box over the page.
 */
@Composable
fun BoxScope.NoticeBanner(notice: Notice?, onClick: (Notice) -> Unit, onGone: (Notice) -> Unit) {
    var shown by remember { mutableStateOf<Notice?>(null) }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var smile by remember { mutableStateOf(false) }
    LaunchedEffect(notice?.key) {
        val n = notice ?: return@LaunchedEffect
        shown = n; smile = false
        UiSounds.notice() // Settings › Notification Sound
        repeat(3) { delay(2000); smile = !smile }
        smile = true
        while (hovered) delay(500)
        shown = null; delay(300); onGone(n)
    }
    val k = shellScale()
    AnimatedVisibility(shown != null, Modifier.align(Alignment.TopEnd).padding(top = 84.dp * k, end = 28.dp * k),
        enter = slideInHorizontally(tween(320)) { it / 3 } + fadeIn(tween(320)), exit = slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(260))) {
        val n = shown ?: return@AnimatedVisibility
        Scaled(k) {
        val src = remember { MutableInteractionSource() }
        val lit = src.lit()
        Row(
            Modifier.widthIn(max = 460.dp).hoverable(hover)
                .background(if (lit.hovered) Color(0xF71C1C20) else Smoked, RoundedCornerShape(16.dp)).border(1.dp, Dream.PanelEdge, RoundedCornerShape(16.dp))
                .dreamClick(src, onClick = { onClick(n) }).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center) {
                // The halo says how much it matters: red when a plan has ended, amber for a warning (DreamNoticesUi).
                if (n.warning) Box(Modifier.size(52.dp).background(Brush.radialGradient(listOf((if (n.critical) Dream.Red else Dream.Amber).copy(alpha = 0.35f), Color.Transparent)), CircleShape))
                Box(Modifier.size(42.dp).background(Color(0xFF0C0C0E), CircleShape), contentAlignment = Alignment.Center) {
                    Crossfade(smile, animationSpec = tween(600)) { s -> Text(if (s) (if (n.critical) "😊" else "🙂") else noticeEmoji(n), fontSize = 20.sp) }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(n.title, style = DreamType.Row.copy(fontWeight = FontWeight.SemiBold))
                if (n.body.isNotBlank()) { Spacer(Modifier.height(2.dp)); Text(n.body, style = DreamType.Caption) }
            }
        }
        }
    }
}

/**
 * The shell's size step for what the pages draw straight on the shell (banners, the update prompt, You're All Caught
 * Up): the shell's [com.dreamos.desktop.ui.kit.Metrics] scale, so they grow with the window as its own text does.
 * Inside a page's own scaled frame it is 1 (already scaled).
 */
@Composable
internal fun shellScale(): Float =
    if (com.dreamos.desktop.settings.kit.LocalPageScale.current != null) 1f else com.dreamos.desktop.ui.kit.LocalMetrics.current.s

@Composable
internal fun Scaled(k: Float, content: @Composable () -> Unit) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    if (k == 1f) content()
    else androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * k, d.fontScale), content = content)
}

/**
 * The new-version prompt (owner's wording): "There's a New Version Available", "Dream OS 4.4", Get the Update and
 * Later, at the bottom right (Windows' place for it, and clear of the notice banners at the top right). When the
 * release carries the installer, Get the Update downloads it here with its progress, then the installer takes over
 * and Dream OS closes (the About page's path); otherwise [onGet] opens the release page. Never over playback (the
 * shell decides when it shows).
 */
@Composable
fun BoxScope.UpdatePrompt(version: String, onGet: () -> Unit, onLater: () -> Unit) {
    val k = shellScale()
    val svc = com.dreamos.desktop.settings.LocalPagesServices.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    Box(Modifier.align(Alignment.BottomEnd).padding(bottom = 28.dp * k, end = 28.dp * k)) {
        Scaled(k) {
            Row(
                Modifier.widthIn(max = 520.dp).background(Smoked, RoundedCornerShape(16.dp)).border(1.dp, Dream.PanelEdge, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(42.dp).background(Color(0xFF0C0C0E), CircleShape), contentAlignment = Alignment.Center) { Text("✨", fontSize = 20.sp) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text("There's a New Version Available", style = DreamType.Row.copy(fontWeight = FontWeight.SemiBold))
                    val p = progress
                    Text(when {
                        p != null -> "Downloading Dream OS $version  ·  ${(p * 100).toInt()}%"
                        problem != null -> problem!!
                        else -> "Dream OS $version"
                    }, style = DreamType.Caption)
                    Spacer(Modifier.height(10.dp))
                    if (p == null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DreamButton("Get the Update", {
                            if (!svc.updates.hasInstaller) onGet()
                            else { problem = null; progress = 0f; scope.launch { problem = svc.updates.install { f -> progress = f }; progress = null } }
                        }, primary = true)
                        DreamButton("Later", onLater)
                    }
                }
            }
        }
    }
}

/** What the New Episode banner shows (NewEpisodeBanner): core decides who gets one. */
data class NewEpisodeCard(
    val showName: String,
    /** "S3 · E7" or, for several, "S1 · E1-8". */
    val episodeLine: String,
    /** The episode's name, or "8 Episodes" for several. */
    val episodeName: String,
    val stillUrl: String?,
    val fallbackArt: String? = null,
    val pill: PillSpec? = null,
    /** "Out Tonight at 9:00 PM", "Out Today", "Out Now" until a pill is known. */
    val outWords: String? = null,
    val several: Boolean = false,
)

/**
 * The New Episode banner under Home's hero: the still, "NEW EPISODE" in the still rainbow, the show, the episode,
 * its pill, then Play, Go to Show and Dismiss.
 */
@Composable
fun NewEpisodeBanner(card: NewEpisodeCard, onPlay: () -> Unit, onShow: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(156.dp).background(Color(0xFF0E0E10), RoundedCornerShape(16.dp)).border(1.dp, Dream.PanelEdge, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(card.stillUrl, Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)), fallbacks = listOfNotNull(card.fallbackArt))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(if (card.several) "NEW EPISODES" else "NEW EPISODE", style = DreamType.Pill.copy(color = com.dreamos.desktop.ui.kit.rainbowText(), letterSpacing = 1.2.sp))
            Spacer(Modifier.height(4.dp))
            Text(card.showName, style = DreamType.Section.copy(fontWeight = FontWeight.Bold), maxLines = 1)
            Text(card.episodeLine + "  " + card.episodeName, style = DreamType.Meta.copy(color = Dream.Text), maxLines = 2)
            Spacer(Modifier.height(8.dp))
            card.pill?.let { Pill(it.text, it.kind, it.glyph) } ?: card.outWords?.let { Text(it, style = DreamType.Caption) }
        }
        Spacer(Modifier.width(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DreamButton("Play", onPlay, primary = true, glyph = Glyph.Play)
            DreamButton("Go to Show", onShow)
            DreamButton("Dismiss", onDismiss)
        }
    }
}

/** The end of a long page: a thin fading line, "You're All Caught Up", Back to Top. */
@Composable
fun AllCaughtUp(onBackToTop: () -> Unit, modifier: Modifier = Modifier) = Scaled(shellScale()) {
    Column(modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(320.dp).height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.25f), Color.Transparent))))
        Spacer(Modifier.height(16.dp))
        Text("You're All Caught Up", style = DreamType.Caption.copy(fontSize = 13.5.sp))
        Spacer(Modifier.height(12.dp))
        DreamButton("Back to Top", onBackToTop)
    }
}
