package com.dreamos.desktop.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import com.dreamos.desktop.preload.BannerAhead
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dreamos.desktop.core.model.NewEpisodeInfo
import com.dreamos.desktop.core.service.Services
import com.dreamos.desktop.core.time.DreamTime
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.dreamos.desktop.ui.kit.ButtonRow
import com.dreamos.desktop.ui.kit.rainbowText
import com.dreamos.desktop.ui.kit.DreamImage
import com.dreamos.desktop.ui.kit.NamePlaceholder
import com.dreamos.desktop.ui.kit.Palette
import com.dreamos.desktop.ui.kit.PearlButton
import com.dreamos.desktop.ui.kit.PlayButton
import com.dreamos.desktop.ui.kit.Rainbow
import com.dreamos.desktop.ui.kit.Tmdb
import com.dreamos.desktop.ui.kit.Type
import com.dreamos.desktop.shell.ShellState
import com.dreamos.desktop.ui.kit.LocalMetrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The New Episode banner's words. Which episode it offers is core's (`Services.newEpisodes.bannerPick`, the Fire
 * Stick's NewEpisodeSpot: caught-up rule, out in the last 3 days, retired 48 h after it first showed).
 */
object NewEpisodeSpot {
    /** "S3 · E7"; a batch drop "S1 · E5-8" (the Fire Stick's code, one separator style). */
    fun code(e: NewEpisodeInfo): String = "S${e.season} · " + if (e.lastNumber > e.number) "E${e.number}-${e.lastNumber}" else "E${e.number}"

    /** The episode's name beside its code; a batch drop "4 Episodes". */
    fun name(e: NewEpisodeInfo): String =
        if (e.lastNumber > e.number) "${e.lastNumber - e.number + 1} Episodes" else e.name.ifBlank { "Episode ${e.number}" }

    /** "S3 · E7 · Episode Name"; a batch drop "S1 · E5-8 · 4 Episodes". */
    fun line(e: NewEpisodeInfo): String = "${code(e)} · ${name(e)}"

    /** When it came out, until the availability pill answers: "Out Tonight at 9:00 PM", "Out Today", "Out Now". */
    fun outWords(e: NewEpisodeInfo): String {
        val today = DreamTime.daysFromToday(e.airDate) == 0
        val at = e.at
        return when {
            today && at != null && at > 0 -> "Out " + DreamTime.airLabel(at)
            today -> "Out Today"
            else -> "Out Now"
        }
    }
}

/**
 * The New Episode banner under Home's hero (fire-home NewEpisodeBanner): the still on the left, "New Episode" in the
 * still rainbow (Title Case, normal tracking, like every kicker), the show, the episode line, when it came out, then
 * Play, Go to Show and Dismiss. Checked again every 30 minutes while Home is open.
 *
 * The pick runs from launch ([BannerAhead]); Home's hero waits for it, so the banner comes in with the hero. A pick
 * that lands after the hero is already showing ([heroShown]) is kept for Home's next visit instead of pushing the
 * rows down under the viewer.
 */
@Composable
fun NewEpisodeSlot(state: ShellState, heroShown: () -> Boolean = { false }) {
    val m = LocalMetrics.current
    var spot by remember { mutableStateOf(BannerAhead.now()) }
    LaunchedEffect(Unit) {
        if (!BannerAhead.settled) {
            val p = BannerAhead.await(60_000L)
            // The hero waits for the pick: still not showing means they arrive in the same frame.
            if (!heroShown()) spot = p
        }
        while (true) {
            delay(30 * 60_000L)
            spot = runCatching { Services.newEpisodes.bannerPick() }.getOrNull()
        }
    }
    com.dreamos.desktop.preload.PopProbe("banner", spot != null)
    val e = spot ?: return
    LaunchedEffect(e.key) { runCatching { Services.newEpisodes.bannerShown(e) } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Dismissed: the banner fades, then leaves (the Fire Stick's 200 ms); played: it goes with the player covering Home.
    var leaving by remember(e.key) { mutableStateOf(false) }
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        if (leaving) 0f else 1f, androidx.compose.animation.core.tween(if (leaving && !com.dreamos.desktop.shell.Motion.reduced) 200 else 0),
        finishedListener = { if (leaving) spot = null }, label = "banner",
    )
    fun done() { scope.launch { runCatching { Services.newEpisodes.bannerDone(e) } } }
    // Its availability pill, asked up front; until it answers, when it came out.
    LaunchedEffect(e.key) {
        state.catalog.askEpisode(e.show, e.season, e.number, e.name, e.airDate)
        // Its stream found ahead once Home has settled, so Play starts at once (fire-home NewEpisodeSpot.prepare).
        delay(BANNER_PREP_MS)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.dreamos.desktop.shell.AppServices.playback?.nextEpisodeAhead(com.dreamos.desktop.core.playback.PlayTarget(
                    com.dreamos.desktop.core.playback.MediaKind.EPISODE, e.show.id, e.show.name, e.show.year.toIntOrNull(),
                    season = e.season, episode = e.number, episodeTitle = e.name.ifBlank { null },
                    originalLanguage = e.show.lang.ifEmpty { null }, airDate = e.airDate.ifEmpty { null },
                    backdrop = e.show.backdrop("w1280"), poster = e.show.poster(),
                ))
            }
        }
    }
    state.pillVersion
    val pill = state.catalog.episodePill(e.show, e.season, e.number, e.name, e.airDate)
    val dens = LocalDensity.current
    val h = m.u(150)
    val shape = RoundedCornerShape(m.u(16))
    Row(
        Modifier.fillMaxWidth().padding(start = m.gutter, end = m.gutter, bottom = m.rowGap).height(h)
            .graphicsLayer { this.alpha = alpha }
            .clip(shape).background(Color(0xFF0E0E10)).border(m.u(1), Palette.hairline, shape).padding(m.u(12)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val stillW = (h - m.u(24)) * 16f / 9f
        DreamImage(
            listOf(Tmdb.backdrop(e.stillPath, stillW.value * dens.density), Tmdb.backdrop(e.show.backdropPath, stillW.value * dens.density)),
            Modifier.size(stillW, h - m.u(24)).clip(RoundedCornerShape(m.radius)),
            placeholder = { NamePlaceholder(e.show.name) },
        )
        Spacer(Modifier.width(m.u(20)))
        Column(Modifier.weight(1f)) {
            // The kicker, its status pill once known, else when it came out (the Fire Stick's first line).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (e.lastNumber > e.number) "New Episodes" else "New Episode",
                    style = Type.kicker().copy(color = rainbowText()), maxLines = 1)
                Spacer(Modifier.width(m.u(14)))
                if (pill != null) com.dreamos.desktop.ui.kit.StatusPill(pill, false)
                else Text(NewEpisodeSpot.outWords(e), style = Type.cardMeta(), maxLines = 1)
            }
            Spacer(Modifier.height(m.u(7)))
            Text(e.show.name, style = Type.style(21f, FontWeight.Bold, -0.2f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(m.u(5)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(NewEpisodeSpot.code(e), style = Type.style(15f, FontWeight.SemiBold, color = Palette.text.copy(alpha = 0.92f)), maxLines = 1)
                Spacer(Modifier.width(m.u(12)))
                Text(NewEpisodeSpot.name(e), style = Type.meta(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(m.u(16)))
        ButtonRow {
            PlayButton("Play", { done(); spot = null; state.playAt(e.show, e.season, e.number, e.name, fromStart = false) })
            PearlButton("Go to Show", { state.openTitle(e.show) }, icon = com.dreamos.desktop.ui.kit.Icon.INFO)
            PearlButton("Dismiss", { done(); leaving = true })
        }
    }
}

/** The banner's stream is looked for this long after it shows (Home settles first; the Fire Stick's first look). */
private const val BANNER_PREP_MS = 3_000L
