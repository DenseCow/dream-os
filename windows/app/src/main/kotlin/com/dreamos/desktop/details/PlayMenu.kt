package com.dreamos.desktop.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dreamos.desktop.settings.kit.MenuItem
import com.dreamos.desktop.settings.kit.OptionsMenu
import com.dreamos.desktop.settings.kit.PlayRequest

/** Which part of Play's options is open: the first menu, or one season's episodes. */
sealed interface PlayMenuState {
    data object Root : PlayMenuState
    data class Season(val number: Int) : PlayMenuState
}

/**
 * Play's options (the Fire Stick's long press on Play), opened by right-clicking Play, the Menu key or Shift+F10.
 * A show: the episode Play would start ("Resume Episode", "Play Next Episode" or "Play First Episode"), then every
 * season that is out with its episode count; a season opens its aired episodes. A show with one season and nothing
 * started goes straight to its episodes. A movie: Play or Resume, Start Over while resuming, and Choose a Source.
 * Shown under the Play button.
 */
@Composable
fun PlayOptions(
    d: TitleDetails,
    source: TitleSource,
    state: PlayMenuState?,
    onState: (PlayMenuState?) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onSources: (Pair<Int?, Int?>) -> Unit,
) {
    if (state == null) return
    val below = with(LocalDensity.current) { IntOffset(0, 62.dp.roundToPx()) }
    val close = { onState(null) }
    val p = d.progress
    val resuming = p != null && p.inProgress
    if (!d.ref.isShow) {
        OptionsMenu(d.name, listOfNotNull(
            MenuItem(if (resuming) "Resume" else "Play") { onPlay(PlayRequest(d.ref)) },
            if (resuming) MenuItem("Start Over") { onPlay(PlayRequest(d.ref, fromStart = true)) } else null,
            MenuItem("Choose a Source") { onSources(null to null) },
        ), close, below)
        return
    }
    val out = remember(d.seasons) { d.seasons.filter { it.number > 0 && it.episodeCount > 0 && it.comingWords == null }.sortedBy { it.number } }
    val start: Pair<String, Pair<Int, Int>>? = when {
        resuming && p!!.season != null && p.episode != null -> "Resume Episode" to (p.season to p.episode)
        d.upNext != null -> "Play Next Episode" to d.upNext
        out.isNotEmpty() -> "Play First Episode" to (out.first().number to 1)
        else -> null
    }
    when (state) {
        PlayMenuState.Root -> {
            if (out.size == 1 && !resuming && d.upNext == null) { LaunchedEffect(Unit) { onState(PlayMenuState.Season(out.first().number)) }; return }
            val items = buildList {
                start?.let { (verb, se) -> add(MenuItem("$verb${TitleWords.DOT}S${se.first} E${se.second}") { onPlay(PlayRequest(d.ref, se.first, se.second)) }) }
                if (start != null && out.isNotEmpty()) add(MenuItem.Divider)
                out.forEach { s ->
                    // The menu closes on a choice; the season's episodes open in its place.
                    add(MenuItem("Season ${s.number}${TitleWords.DOT}${s.episodeCount} Episode${if (s.episodeCount == 1) "" else "s"}") { onState(PlayMenuState.Season(s.number)) })
                }
            }
            OptionsMenu(d.name, items, close, below, note = if (items.isEmpty()) "Couldn't find episodes of ${d.name}" else null)
        }
        is PlayMenuState.Season -> {
            var eps by remember(state.number) { mutableStateOf<List<EpisodeInfo>?>(null) }
            var failed by remember(state.number) { mutableStateOf(false) }
            LaunchedEffect(state.number) {
                runCatching { source.season(d.ref, state.number) }
                    .onSuccess { list -> eps = list.filter { !it.upcoming } }
                    .onFailure { failed = true }
            }
            val list = eps
            val items = list.orEmpty().map { e ->
                val name = e.name.takeIf { it.isNotBlank() && it != "Episode ${e.number}" }
                MenuItem("Episode ${e.number}" + (name?.let { "${TitleWords.DOT}$it" } ?: "")) { onPlay(PlayRequest(d.ref, e.season, e.number)) }
            }
            val note = when {
                failed -> "Couldn't load the episodes. Try again in a moment."
                list == null -> "Finding Episodes"
                list.isEmpty() -> "No episodes of season ${state.number} to play yet"
                else -> null
            }
            OptionsMenu("${d.name}${TitleWords.DOT}Season ${state.number}", items, close, below, note = note)
        }
    }
}
