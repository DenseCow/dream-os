package com.dreamos.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamos.desktop.core.playback.SourceRow
import com.dreamos.player.engine.Track
import com.dreamos.player.session.PlayerPrefs
import com.dreamos.player.session.PlayerSession
import com.dreamos.player.session.PlayerSession.Sheet

/** The sheets slide in from the right over a light dim (PlayerMenus); X-Ray rises from the bottom. */
@Composable
fun Sheets(s: PlayerSession) {
    val sheet = s.sheet
    var last by remember { mutableStateOf(sheet) }
    if (sheet != null) last = sheet
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            sheet != null && sheet != Sheet.XRAY, Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(220)),
            exit = slideOutHorizontally(tween(220)) { it / 3 } + fadeOut(tween(180)),
        ) {
            Column(
                Modifier.fillMaxHeight().width(500.dp).padding(vertical = 24.dp).padding(end = 24.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xF7121214), Look.Sheet)))
                    .border(1.dp, Look.Hairline, RoundedCornerShape(28.dp))
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
            ) {
                when (last) {
                    Sheet.AUDIO_SUBS -> AudioSubs(s)
                    Sheet.PLAYBACK -> Playback(s)
                    Sheet.INFO -> Info(s)
                    Sheet.EPISODES -> Episodes(s)
                    Sheet.SOURCE -> Sources(s)
                    else -> {}
                }
            }
        }
        AnimatedVisibility(sheet == Sheet.XRAY, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(260)), exit = fadeOut(tween(200))) {
            XRay(s)
        }
    }
}

@Composable
private fun SheetHeader(kicker: String?, title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 20.dp, top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            if (kicker != null) { Text(kicker, style = Look.Small, color = Look.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(4.dp)) }
            Text(title, style = Look.SheetTitle, color = Look.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        RoundButton(Glyph.CLOSE, "Close", onClick = onClose, size = 40.dp, iconSize = 16.dp)
    }
}

/** A scrolling sheet body whose ends melt into the panel (soft edges), with a slim scrollbar for the mouse. */
@Composable
private fun SheetList(content: LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = state, contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 28.dp)) { content() }
        Box(Modifier.fillMaxWidth().height(16.dp).background(Brush.verticalGradient(listOf(Look.Sheet.copy(alpha = 0.9f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Look.Sheet))))
        VerticalScrollbar(
            rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 12.dp, horizontal = 4.dp),
            style = ScrollbarStyle(4.dp, 6.dp, RoundedCornerShape(3.dp), 300, Color(0x33FFFFFF), Color(0x66FFFFFF)),
        )
    }
}

private fun LazyListScope.heading(text: String) = item {
    Text(text, style = Look.Heading, color = Look.Text3, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp))
}

@Composable
private fun CheckSlot(on: Boolean, ink: Color) {
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { if (on) GlyphIcon(Glyph.CHECK, ink, 20.dp) }
}

@Composable
private fun TrackRow(title: String, detail: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    SheetRow(title, onClick, enabled = enabled, selected = selected, modifier = Modifier.fillMaxWidth()) { ink, dim ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            CheckSlot(selected, ink)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = Look.Line, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail.isNotEmpty()) Text(detail, style = Look.Small, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---- Audio & Subtitles ----

@Composable
private fun AudioSubs(s: PlayerSession) {
    SheetHeader(null, "Audio & Subtitles") { s.closeSheet() }
    val audio = s.audioTracks
    val subs = s.subTracks
    val p = s.prefs
    SheetList {
        heading("Audio")
        if (audio.isEmpty()) item { TrackRow("Default Audio", "", true) {} }
        items(audio, key = { "a${it.id}" }) { t ->
            TrackRow(t.title, t.detail, t.selected) { s.pickAudio(t) }
        }
        item {
            SheetRow("Volume Boost", { s.cycleBoost() }, modifier = Modifier.fillMaxWidth()) { ink, dim ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp)) { GlyphIcon(Glyph.VOLUME, ink, 20.dp) }
                    Spacer(Modifier.width(12.dp))
                    Text("Volume Boost", style = Look.Line, color = ink, modifier = Modifier.weight(1f))
                    Text(PlayerPrefs.BOOST_LABELS[p.boost], style = Look.Line, color = dim)
                }
            }
        }
        heading("Subtitles")
        item { TrackRow("Off", "", subs.none { it.selected }) { s.pickSub(null) } }
        items(subs, key = { "s${it.id}" }) { t -> TrackRow(t.title, t.detail, t.selected) { s.pickSub(t) } }
        heading("Subtitle Appearance")
        item { SubPreview(p) }
        item { Segmented("Size", PlayerPrefs.SIZE_LABELS, p.subSize) { s.updatePrefs(p.copy(subSize = it)) } }
        item { Segmented("Style", PlayerPrefs.STYLE_LABELS, p.subStyle) { s.updatePrefs(p.copy(subStyle = it)) } }
        item { Segmented("Position", PlayerPrefs.POS_LABELS, p.subPos) { s.updatePrefs(p.copy(subPos = it)) } }
    }
}

@Composable
private fun SubPreview(p: PlayerPrefs) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(110.dp).clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2A2A30), Color(0xFF101012)))),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val size = (20 * PlayerPrefs.SIZE_SCALE[p.subSize]).sp
        val color = if (p.subStyle == 2) Color(0xFFFFE45C) else Color.White
        val bottom = (8 + 60 * PlayerPrefs.POS_BOTTOM[p.subPos]).dp
        Text(
            "I'll be right there.", color = color, fontSize = size, fontWeight = FontWeight.SemiBold,
            style = androidx.compose.ui.text.TextStyle(shadow = if (p.subStyle != 1) androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 4f) else null),
            modifier = Modifier.padding(bottom = bottom)
                .then(if (p.subStyle == 1) Modifier.background(Color(0xBE0A0A0C), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp) else Modifier),
        )
    }
}

/** Label and a row of choices; the chosen one is pearl. Arrow keys move between them. */
@Composable
private fun Segmented(label: String, options: List<String>, chosen: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Look.Line, color = Look.Text, modifier = Modifier.width(96.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, o ->
                Modifier.control("$label $o", onClick = { onPick(i) }) { m, st ->
                    val on = i == chosen
                    Box(
                        m.height(38.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (on || st.lit) Look.Pearl else Look.Glass)
                            .litEdge(st.lit, 2.dp, RoundedCornerShape(10.dp), rest = Look.Edge)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(o, style = Look.Small, color = if (on || st.lit) Look.Ink else Look.Text) }
                }
            }
        }
    }
}

// ---- Playback: speed and intros ----

private val SPEEDS = listOf(0.5 to "0.5×", 0.75 to "0.75×", 1.0 to "Normal", 1.25 to "1.25×", 1.5 to "1.5×", 1.75 to "1.75×", 2.0 to "2.0×")

@Composable
private fun Playback(s: PlayerSession) {
    SheetHeader(null, "Playback") { s.closeSheet() }
    val speed = s.engineState.speed
    var autoSkip by remember { mutableStateOf(s.prefs.autoSkipIntros) }
    SheetList {
        heading("Speed")
        items(SPEEDS) { (v, label) ->
            TrackRow(label, "", kotlin.math.abs(speed - v) < 0.01) {
                s.engine.setSpeed(v)
                s.showToast("Speed$SEP$label")
                s.closeSheet()
            }
        }
        heading("Intros")
        item {
            SheetRow("Skip Intros Automatically", {
                autoSkip = !autoSkip
                s.updatePrefs(s.prefs.copy(autoSkipIntros = autoSkip))
                s.showToast(if (autoSkip) "Intros Skip Automatically" else "Intros Show a Skip Button")
            }, modifier = Modifier.fillMaxWidth()) { ink, dim ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Skip Intros Automatically", style = Look.Line, color = ink)
                        Text("When Dream Player knows where the intro ends", style = Look.Small, color = dim)
                    }
                    Spacer(Modifier.width(12.dp))
                    Toggle(autoSkip)
                }
            }
        }
    }
}

@Composable
private fun Toggle(on: Boolean) {
    Box(Modifier.width(46.dp).height(28.dp).clip(CircleShape).background(if (on) SolidColor(rainbowHue(player = true)) else SolidColor(Color(0x40FFFFFF))), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.padding(3.dp).size(22.dp).clip(CircleShape).background(Color.White))
    }
}

// ---- Info ----

@Composable
private fun Info(s: PlayerSession) {
    val t = s.target
    val d = s.details
    val es = s.engineState
    val kicker = if (t.isEpisode) "${t.title}$SEP" + "S${t.season} · E${t.episode}" else listOfNotNull("Movie", (d?.year ?: t.year)?.toString()).joinToString(SEP)
    SheetHeader(kicker, if (t.isEpisode) (d?.episodeName ?: t.episodeTitle ?: t.title) else t.title) { s.closeSheet() }
    SheetList {
        if (!s.trailer && t.tmdbId > 0) item {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                PillButton("Cast & Characters", { s.openSheet(Sheet.XRAY) }, Glyph.CAST, height = 40.dp)
            }
        }
        item {
            // PlayerMeta.load: an episode's air date and runtime ("Mar 1, 2024  ·  45 min"; its own TMDB score needs 150
            // votes, which the show's average is not); a movie's year, runtime and two genres.
            val meta = if (t.isEpisode) listOfNotNull(
                d?.episodeAirDate?.takeIf { it.isNotEmpty() }?.let { s.services.date(it) },
                d?.episodeRuntimeMin?.takeIf { it > 0 }?.let(::runtimeText),
            ) else listOfNotNull((d?.year ?: t.year)?.toString(), d?.runtimeMin?.takeIf { it > 0 }?.let(::runtimeText), d?.genres?.take(2)?.joinToString(", ")?.ifEmpty { null })
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (meta.isNotEmpty()) Text(meta.joinToString(SEP), style = Look.Small, color = Look.Text2)
                Spacer(Modifier.height(10.dp))
                val syn = if (d == null) "Loading…" else ((if (t.isEpisode) d.episodeOverview else null) ?: d.overview).ifBlank { "No synopsis available." }
                Text(syn, style = Look.Body, color = Look.Text2)
            }
        }
        heading("Now Playing")
        item {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                BadgeRow(badgesFor(s, full = true).take(6))
            }
        }
        val facts = buildList {
            add("Audio" to (s.audioTracks.firstOrNull { it.selected }?.let { listOf(it.title, it.detail).filter(String::isNotEmpty).joinToString(SEP) } ?: "Default"))
            add("Subtitles" to (s.subTracks.firstOrNull { it.selected }?.title ?: "Off"))
            if (kotlin.math.abs(es.speed - 1.0) > 0.01) add("Speed" to (SPEEDS.firstOrNull { kotlin.math.abs(it.first - es.speed) < 0.01 }?.second ?: "${es.speed}×"))
            pictureLine(es.facts)?.let { add("Picture" to it) }
            add("Source" to s.request.stream.service.displayName)
        }
        items(facts) { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)) {
                Text(k, style = Look.Small, color = Look.Text3, modifier = Modifier.width(96.dp))
                Text(v, style = Look.Small, color = Look.Text)
            }
        }
    }
}

/** Info's Picture line (iPhone): "Dolby Vision  ·  Shown in HDR", "HDR10  ·  Shown in Standard Range", "Standard Range". */
fun pictureLine(f: com.dreamos.player.engine.StreamFacts): String? {
    if (f.width == 0) return null
    val kind = f.hdrKind ?: return "Standard Range"
    return kind + SEP + if (f.hdrOnScreen) "Shown in HDR" else "Shown in Standard Range"
}

// ---- Episodes ----

@Composable
private fun Episodes(s: PlayerSession) {
    val t = s.target
    SheetHeader(t.title, "Episodes") { s.closeSheet() }
    val seasons = s.seasons.filter { it.episodeCount > 0 }.sortedBy { if (it.number == 0) Int.MAX_VALUE else it.number }.take(60)
    if (seasons.isNotEmpty()) {
        LazyRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(seasons) { se ->
                val label = when {
                    se.number == 0 -> "Specials"
                    !se.name.startsWith("Season") && se.name.length <= 18 && se.name.isNotBlank() -> se.name
                    else -> "Season ${se.number}"
                }
                val on = se.number == s.episodesSeason
                Modifier.control(label, onClick = { s.loadEpisodes(se.number) }) { m, st ->
                    Box(
                        m.height(36.dp).clip(RoundedCornerShape(18.dp)).background(if (on || st.lit) Look.Pearl else Look.Glass)
                            .litEdge(st.lit, 2.dp, RoundedCornerShape(18.dp), rest = Look.Edge).padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, style = Look.Small, color = if (on || st.lit) Look.Ink else Look.Text) }
                }
            }
        }
    }
    // Only episodes that are out (an episode up early counts); the first one still to come is named under the list.
    val eps = s.episodes.filter { !it.upcoming }
    val coming = s.episodes.firstOrNull { it.upcoming }
    if (eps.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(28.dp)) {
            if (s.seasons.isNotEmpty() && (s.episodes.isNotEmpty() || s.episodesLoaded)) {
                Text("No Episodes to Watch Yet", style = Look.Line, color = Look.Text2)
                coming?.let { Spacer(Modifier.height(12.dp)); Text(comingLine(s, it), style = Look.Small, color = Look.Text3) }
            } else Spinner(24)
        }
        return
    }
    SheetList {
        items(eps, key = { "${it.season}:${it.number}" }) { e ->
            val et = t.atEpisode(e.season, e.number, e.name)
            val playing = et.key == t.key
            val watched = if (playing) null else s.watched[et.key]
            SheetRow("Episode ${e.number}, ${e.name}", { s.playEpisode(e) }, enabled = !e.upcoming, selected = playing, ring = playing, modifier = Modifier.fillMaxWidth()) { ink, dim ->
                Row {
                    Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1A1A1D)), contentAlignment = Alignment.Center) {
                        if (e.stillUrl == null) Text("E${e.number}", style = Look.Line, color = Look.Text3)
                        NetImage(e.stillUrl, Modifier.fillMaxSize())
                        val frac = when {
                            playing -> s.engineState.let { if (it.durationMs > 0) it.positionMs.toFloat() / it.durationMs else 0f }
                            else -> watched ?: 0f
                        }
                        if (frac > 0f) Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).rainbowUser(player = true)) {
                            drawRect(Color.White.copy(alpha = 0.25f)); rainbowLine(frac)
                        }
                        // The playing one's badge on its still (PlayerMenus.episodeRow).
                        if (playing) Box(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                            Text(
                                "PLAYING", color = Look.Text, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                                modifier = Modifier.clip(CircleShape).background(Color(0x960E0E10)).padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${e.number}.  ${e.name.ifBlank { "Episode ${e.number}" }}", style = Look.Line, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(
                            e.runtimeMin.takeIf { it > 0 }?.let(::runtimeText),
                            e.airDate.takeIf { it.isNotEmpty() }?.let { s.services.date(it) },
                            "Watched".takeIf { (watched ?: 0f) >= 1f },
                        )
                        if (meta.isNotEmpty()) Text(meta.joinToString(SEP), style = Look.Small, color = dim)
                        if (e.overview.isNotBlank()) Text(e.overview, style = Look.Small, color = dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        coming?.let { c -> item { Text(comingLine(s, c), style = Look.Small, color = Look.Text3, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) } }
    }
}

/** "Next Episode:  E7  ·  Ace of Spades  ·  Friday at 9:00 PM" under the list (PlayerMenus.comingLine); not a button. */
private fun comingLine(s: PlayerSession, e: com.dreamos.player.api.EpisodeItem): String {
    val title = e.name.takeIf { !com.dreamos.desktop.core.content.Details.isPlaceholder(it) }
    val whenWords = e.airsAt?.takeIf { it > 0 }?.let { com.dreamos.desktop.core.time.DreamTime.airLabel(it) }
        ?: e.airDate.takeIf { it.length == 10 }?.let { com.dreamos.desktop.core.time.DreamTime.dayWords(it) }
        ?: e.airDate.takeIf { it.length == 4 }
        ?: "Date to Be Announced"
    return "Next Episode:  " + listOfNotNull("E${e.number}", title, whenWords).joinToString(SEP)
}

// ---- Change Source ----

@Composable
private fun Sources(s: PlayerSession) {
    val t = s.target
    SheetHeader(if (s.sourcesLoose) "All Sources" else "Change Source", if (t.isEpisode) "${t.title}$SEP" + "S${t.season} E${t.episode}" else t.title) { s.closeSheet() }
    val list = s.sources
    if (list == null) {
        Row(Modifier.padding(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Spinner(22); Spacer(Modifier.width(14.dp)); Text("Looking for Sources", style = Look.Line, color = Look.Text2)
        }
        return
    }
    var showOther by remember(list) { mutableStateOf(false) }
    val main = list.filter { !it.otherEpisode }
    val other = list.filter { it.otherEpisode }
    SheetList {
        if (main.isEmpty()) item {
            Text(
                if (other.isEmpty()) "No other sources are available right now." else "Every source found is for a different title or episode.",
                style = Look.Body, color = Look.Text2, modifier = Modifier.padding(16.dp),
            )
        }
        items(main, key = { it.source.hash + it.source.torrentName }) { SourceLine(s, it) }
        if (other.isNotEmpty()) {
            if (!showOther) item { ExtraRow("Show ${other.size} More", "For a Different Title or Episode") { showOther = true } }
            else items(other, key = { it.source.hash + it.source.torrentName }) { SourceLine(s, it) }
        }
        if (!s.sourcesLoose) item { ExtraRow("Search All Sources", "Includes Ones Your Source Settings Usually Hide") { s.loadSources(loose = true) } }
        item { ExtraRow("Find Another Source", "Dream OS Picks the Best One, From Where You Are") { s.findAnotherSource() } }
    }
}

@Composable
private fun ExtraRow(title: String, sub: String, onClick: () -> Unit) {
    SheetRow(title, onClick, modifier = Modifier.fillMaxWidth()) { ink, dim ->
        Column { Text(title, style = Look.Line, color = ink); Text(sub, style = Look.Small, color = dim) }
    }
}

@Composable
private fun SourceLine(s: PlayerSession, it: SourceRow) {
    val playing = s.isPlayingSource(it)
    val src = it.source
    SheetRow(src.torrentName, { s.pickSource(it) }, selected = playing, modifier = Modifier.fillMaxWidth()) { ink, dim ->
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(listOfNotNull(src.quality.label, src.sizeBytes.takeIf { b -> b > 0 }?.let(::sizeText)).joinToString(SEP), style = Look.Line, color = ink, modifier = Modifier.weight(1f))
                when {
                    playing -> StatusPill("Playing", Look.Good, Look.GoodText)
                    it.ready == true -> StatusPill("Ready", Look.Good, Look.GoodText)
                }
                if (it.outsideFilters) { Spacer(Modifier.width(6.dp)); StatusPill("Outside Your Filters", Look.Amber, Look.AmberText) }
                if (it.otherEpisode) { Spacer(Modifier.width(6.dp)); StatusPill("Other Episode", Look.Amber, Look.AmberText) }
            }
            val line2 = when {
                s.sourcePending == src.hash -> "Getting Ready"
                s.sourceFailed == src.hash -> "Not Available Right Now"
                else -> (it.badges + listOfNotNull(if (src.seeders == 1) "1 Seeder" else if (src.seeders > 1) "${src.seeders} Seeders" else null)).joinToString(SEP)
            }
            if (line2.isNotEmpty()) Text(line2, style = Look.Small, color = if (s.sourceFailed == src.hash) Look.AmberText else dim)
            Text(prettyName(src.fileName ?: src.torrentName), style = Look.Small, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StatusPill(text: String, edge: Color, ink: Color) = Capsule(text, color = ink, fill = edge.copy(alpha = 0.14f), edge = SolidColor(edge.copy(alpha = 0.55f)))

/** Release name for people: no extension, dots and underscores to spaces (but not between digits: "5.1", "H.264"). */
fun prettyName(name: String): String = name.replace(Regex("\\.(mkv|mp4|avi|m4v|ts|webm)$", RegexOption.IGNORE_CASE), "")
    .replace(Regex("(?<!\\d)[._]|[._](?!\\d)"), " ").replace(Regex("\\s+"), " ").trim()

// ---- X-Ray ----

@Composable
private fun XRay(s: PlayerSession) {
    val t = s.target
    Box(Modifier.fillMaxWidth().fillMaxHeight(0.72f).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x94000000), Color(0xDB000000))))) {
        val p = s.person
        if (p != null) { PersonCard(s, p.first, p.second); return@Box }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 48.dp)) {
            Row(Modifier.padding(start = 64.dp, end = 48.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(if (t.isEpisode) "${t.title}$SEP" + "S${t.season} · E${t.episode}" else t.title, style = Look.Small, color = Look.Text2)
                    Text(if (t.isEpisode) "In This Episode" else "Cast", style = Look.SheetTitle, color = Look.Text)
                }
                RoundButton(Glyph.CLOSE, "Close Cast", onClick = { s.closeSheet() }, size = 40.dp, iconSize = 16.dp)
            }
            Spacer(Modifier.height(18.dp))
            val cast = s.cast
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 56.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (cast == null) items(8) { Box(Modifier.size(108.dp).clip(CircleShape).background(Color(0x1FFFFFFF))) }
                else items(cast) { c ->
                    Modifier.control("${c.name} as ${c.character}", onClick = { s.openPerson(c) }) { m, st ->
                        Column(m.width(132.dp).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(108.dp).clip(CircleShape).background(Color(0xFF26262A)).litEdge(st.lit, 3.dp, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(c.name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString(""), style = Look.Line, color = Look.Text2)
                                NetImage(c.photoUrl, Modifier.fillMaxSize(), alignment = androidx.compose.ui.BiasAlignment(0f, -0.44f))
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(c.name, style = Look.Small.copy(fontWeight = FontWeight.SemiBold), color = Look.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Text(c.character, style = Look.Small, color = Look.Text3, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonCard(s: PlayerSession, c: com.dreamos.player.api.CastItem, p: com.dreamos.player.api.PersonItem?) {
    Column(Modifier.fillMaxSize().padding(start = 64.dp, end = 48.dp, bottom = 48.dp), verticalArrangement = Arrangement.Bottom) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(132.dp).clip(CircleShape).background(Color(0xFF26262A))) { NetImage(c.photoUrl, Modifier.fillMaxSize(), alignment = androidx.compose.ui.BiasAlignment(0f, -0.44f)) }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                if (c.character.isNotBlank()) Text("As ${c.character}", style = Look.Small, color = Look.Text2)
                Text(c.name, style = Look.SheetTitle, color = Look.Text)
                if (p == null) { Spacer(Modifier.height(10.dp)); Spinner(20) } else {
                    if (p.meta.isNotBlank()) Text(p.meta, style = Look.Small, color = Look.Text2)
                    if (p.bio.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(p.bio, style = Look.Body, color = Look.Text2, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                }
            }
            RoundButton(Glyph.BACK, "Back to Cast", onClick = { s.closePerson() }, size = 40.dp, iconSize = 18.dp)
        }
        if (p != null) {
            Spacer(Modifier.height(18.dp))
            Text("Known For", style = Look.Heading, color = Look.Text3)
            Spacer(Modifier.height(8.dp))
            if (p.knownFor.isEmpty()) Text(if (p.failed) "Couldn't load this person's titles right now" else "No other titles to show", style = Look.Small, color = Look.Text2)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(p.knownFor.take(14)) { r ->
                    Modifier.control(r.title, onClick = { s.openKnownFor(r) }) { m, st ->
                        // The poster, its title under it in two lines at most (PlayerXRay.titleCard).
                        Column(m.width(110.dp)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1A1A1D)).litEdge(st.lit, 2.dp, RoundedCornerShape(10.dp))) {
                                NetImage(r.posterUrl ?: r.backdropUrl, Modifier.fillMaxSize(), description = r.title)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                r.title, style = Look.Small, color = if (st.lit) Look.Text else Look.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
