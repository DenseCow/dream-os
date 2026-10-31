package com.dreamos.desktop.browse

import kotlinx.coroutines.async
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import com.dreamos.desktop.ui.kit.Chip
import com.dreamos.desktop.core.model.Genre
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.dreamos.desktop.home.BarFollows
import com.dreamos.desktop.shell.Motion
import com.dreamos.desktop.shell.RowsPage
import com.dreamos.desktop.shell.ShellState
import com.dreamos.desktop.shell.data.Hub
import com.dreamos.desktop.shell.metaLine
import com.dreamos.desktop.ui.kit.EmptyState
import com.dreamos.desktop.ui.kit.Hero
import com.dreamos.desktop.ui.kit.HeroActions
import com.dreamos.desktop.ui.kit.HeroSkeleton
import com.dreamos.desktop.ui.kit.Icon
import com.dreamos.desktop.ui.kit.LocalMetrics

/**
 * A hub page: a genre or category, a service, Movies / TV Shows, or a seasonal hub. A shorter hero of the hub's own
 * featured titles, then its rows. Esc, Backspace, the mouse's back button or the bar's Back return to the page that
 * opened it.
 */
@Composable
fun HubPage(state: ShellState, hub: Hub) {
    // A seasonal hub has its own page: a masthead instead of a hero (fire-home CinemaSeason).
    (hub.page as? com.dreamos.desktop.core.model.CatalogPage.Season)?.let { SeasonPage(state, hub, it.hubId); return }
    val c = state.catalog
    val m = LocalMetrics.current
    // Movies / TV Shows: "All" and the category chips; a chip shows its genre's rows under the same hero.
    val chips = state.model("chips:$hub") { mutableStateOf<List<Genre>>(emptyList()) }
    val chip = state.model("chip:$hub") { mutableStateOf("") }
    // The hero shows with the chips under it known, so nothing lands after it (its art: RowsModel's warm-up).
    val model = state.rows("hub:$hub", { c.hub(hub) }, {
        kotlinx.coroutines.coroutineScope {
            val ch = async { if (chips.value.isEmpty() && c.ready) runCatching { c.chips(hub) }.getOrDefault(emptyList()) else chips.value }
            com.dreamos.desktop.preload.HeroAhead.ready(c.hubHero(hub), c).also { chips.value = ch.await() }
        }
    })
    val heroH = m.heroH
    BarFollows(model, heroH.value * 0.5f)
    LaunchedEffect(state.toTop) { if (state.toTop > 0) model.list.animateScrollToItem(0) }
    val saved by c.myList.collectAsState()
    // A chip's hero: that category's own featured titles (fire-home CinemaCategory.genre); All goes back to the page's.
    // The hero showing stays until the next one is in.
    val chipHero = state.model("chipHero:$hub") { mutableStateOf<List<com.dreamos.desktop.shell.data.HeroItem>?>(null) }
    LaunchedEffect(chip.value) {
        val id = chip.value
        val g = chips.value.firstOrNull { it.id == id } ?: return@LaunchedEffect
        val list = runCatching { com.dreamos.desktop.preload.HeroAhead.ready(c.hubHero(Hub.genre(g)), c) }.getOrDefault(emptyList())
        if (list.isNotEmpty() && chip.value == id) chipHero.value = list
    }
    if (!c.ready) {
        // fire-home's pages without a TMDB key: what to add, and where.
        val what = when (hub) { Hub.movies -> "movies here"; Hub.shows -> "TV shows here"; else -> hub.name }
        val icon = if (hub == Hub.shows || hub.page is com.dreamos.desktop.core.model.CatalogPage.Service) Icon.TV else Icon.FILM
        Box(Modifier.fillMaxSize().padding(top = m.barH), contentAlignment = Alignment.Center) {
            EmptyState(icon, "Add a TMDB Key", "Open Settings and add a TMDB key to browse $what.")
        }
        return
    }
    if (model.nothing) {
        Box(Modifier.fillMaxSize().padding(top = m.barH), contentAlignment = Alignment.Center) {
            EmptyState(Icon.FILM, "Couldn't Load ${hub.name}", "Check your connection, then try again.", "Try Again", { model.reloadAll() })
        }
        return
    }
    val overlap = m.heroOverlap
    // A service's rows come from where-to-watch data (JustWatch, through TMDB): its credit, small and quiet, at the end.
    val credit: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = if (hub.page is com.dreamos.desktop.core.model.CatalogPage.Service) {
        { item(key = "justwatch") { androidx.compose.material.Text("Streaming Availability From JustWatch", style = com.dreamos.desktop.ui.kit.Type.cardMeta(),
            modifier = Modifier.padding(start = m.gutter, end = m.gutter, top = m.u(4), bottom = m.u(24))) } }
    } else { {} }
    RowsPage(model, c, bottom = credit) {
        item(key = "hero") {
            val hero = if (chip.value.isEmpty()) model.hero else chipHero.value ?: model.hero
            if (hero == null) HeroSkeleton(heroH, overlap)
            else if (hero.isNotEmpty()) Hero(
                hero, heroH, overlap,
                HeroActions({ state.play(it) }, { state.openTitle(it) }, { c.toggleMyList(it) }, { t -> saved.any { it.key == t.key } }),
                Motion.reduced, meta = { metaLine(c, it.title) }, entry = state.entry,
                afterMeta = { com.dreamos.desktop.home.HeroScoreLine(c, it) },
            )
        }
        val all = chips.value
        if (all.isNotEmpty()) item(key = "chips") {
            val labels = listOf("All") + all.map { it.name }
            LazyRow(
                contentPadding = PaddingValues(start = m.gutter, end = m.gutter, top = m.u(4), bottom = m.rowGap - m.u(8)),
                horizontalArrangement = Arrangement.spacedBy(m.u(10)),
            ) {
                items(labels.size, key = { labels[it] }) { i ->
                    val id = if (i == 0) "" else all[i - 1].id
                    Chip(labels[i], chip.value == id, {
                        if (chip.value != id) {
                            if (id.isEmpty()) chipHero.value = null
                            chip.value = id
                            model.switchRows { if (i == 0) c.hub(hub) else c.hub(Hub.genre(all[i - 1])) }
                        }
                    })
                }
            }
        }
    }
}
