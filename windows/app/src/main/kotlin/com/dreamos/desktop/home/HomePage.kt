package com.dreamos.desktop.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.async
import com.dreamos.desktop.shell.LocalBar
import com.dreamos.desktop.shell.Motion
import com.dreamos.desktop.shell.Route
import com.dreamos.desktop.shell.RowsModel
import com.dreamos.desktop.shell.RowsPage
import com.dreamos.desktop.shell.ShellState
import com.dreamos.desktop.shell.metaLine
import com.dreamos.desktop.ui.kit.EmptyState
import com.dreamos.desktop.ui.kit.Hero
import com.dreamos.desktop.ui.kit.HeroActions
import com.dreamos.desktop.ui.kit.HeroSkeleton
import com.dreamos.desktop.ui.kit.Icon
import com.dreamos.desktop.ui.kit.LocalMetrics

/**
 * Home, in the Fire Stick's order: the hero for the time of day (advancing every 8 s), Continue Watching, Top Picks
 * for You, New Releases, New Episodes, In Theaters, Trending Now, Top 10 Today, the Spotlight, My List, Because You
 * Watched, then the services' rows with Coming Soon between them. The first row tucks up into the hero's faded
 * bottom, and the bar turns to glass once the hero has scrolled half away.
 */
@Composable
fun HomePage(state: ShellState) {
    val c = state.catalog
    val m = LocalMetrics.current
    if (!c.ready) {
        Box(Modifier.fillMaxSize().padding(top = m.barH), contentAlignment = Alignment.Center) {
            EmptyState(Icon.FILM, "Welcome to Dream OS", "Add a TMDB key in Settings to see movies and shows here.", "Open Settings", { state.open(Route.Settings(com.dreamos.desktop.settings.SettingsGroup.Accounts)) })
        }
        return
    }
    // The hero shows with the New Episode banner decided, so they arrive together (its art: RowsModel's warm-up).
    val model: RowsModel = state.rows("home", { c.home() }, {
        kotlinx.coroutines.coroutineScope {
            val banner = async { com.dreamos.desktop.preload.BannerAhead.await(BANNER_WAIT_MS) }
            com.dreamos.desktop.preload.HeroAhead.ready(c.hero(), c).also { banner.await() }
        }
    })
    BarFollows(model, m.heroH.value * 0.55f)
    LaunchedEffect(state.toTop) { if (state.toTop > 0) model.list.animateScrollToItem(0) }
    val saved by c.myList.collectAsState()

    if (model.nothing) {
        Box(Modifier.fillMaxSize().padding(top = m.barH), contentAlignment = Alignment.Center) {
            EmptyState(Icon.FILM, "Couldn't Reach TMDB", "Movies and shows load from TMDB. Check your connection, then try again.", "Try Again", { model.reloadAll() })
        }
        return
    }
    val overlap = m.heroOverlap
    RowsPage(model, c) {
        item(key = "hero") {
            val hero = model.hero
            com.dreamos.desktop.preload.PopProbe("hero", hero != null)
            if (hero == null) HeroSkeleton(m.heroH, overlap)
            else if (hero.isNotEmpty()) Hero(
                hero, m.heroH, overlap,
                HeroActions(
                    play = { state.play(it) },
                    info = { state.openTitle(it) },
                    toggleList = { c.toggleMyList(it) },
                    inList = { t -> saved.any { it.key == t.key } },
                ),
                Motion.reduced,
                meta = { metaLine(c, it.title) },
                entry = state.entry,
                afterMeta = { HeroScoreLine(c, it) },
            )
        }
        item(key = "newEpisode") { NewEpisodeSlot(state, heroShown = { model.hero != null }) }
    }
}

/** How long Home's hero waits for the New Episode pick (started at launch) so the banner comes in with it. */
private const val BANNER_WAIT_MS = 2_000L

/** Glass bar once [model]'s page has scrolled past [px] dp (or past its first item). */
@Composable
fun BarFollows(model: RowsModel, dp: Float) {
    val bar = LocalBar.current
    val dens = LocalDensity.current
    val solid by remember(model) {
        derivedStateOf { model.list.firstVisibleItemIndex > 0 || model.list.firstVisibleItemScrollOffset > dp * dens.density }
    }
    SideEffect { bar.solid = solid }
    LaunchedEffect(model) { snapshotFlow { solid }.collect { bar.solid = it } }
}
