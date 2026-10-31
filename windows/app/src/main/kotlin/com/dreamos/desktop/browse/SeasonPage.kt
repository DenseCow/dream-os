package com.dreamos.desktop.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.dreamos.desktop.core.model.HubTile
import com.dreamos.desktop.shell.BarSolid
import com.dreamos.desktop.shell.Route
import com.dreamos.desktop.shell.RowsPage
import com.dreamos.desktop.shell.ShellState
import com.dreamos.desktop.shell.data.Hub
import com.dreamos.desktop.ui.kit.EmptyState
import com.dreamos.desktop.ui.kit.HubMasthead
import com.dreamos.desktop.ui.kit.Icon
import com.dreamos.desktop.ui.kit.LocalMetrics

/**
 * One seasonal hub's page (fire-home CinemaSeason), opened from Home's hub row, a genre page's hub row or More to
 * Celebrate: the masthead (glyph, kicker, the hub's name, the countdown with "Night 30 of 31", the tagline), then
 * Tonight's Pick across the page, the hub's rows for this profile (a kids profile sees only its kids-safe rows; the
 * first as big cards) and the other hubs on today. The rows are core's (`CatalogPage.Season`).
 */
@Composable
fun SeasonPage(state: ShellState, hub: Hub, hubId: String) {
    val c = state.catalog
    val m = LocalMetrics.current
    BarSolid()
    if (!c.ready) {
        Box(Modifier.fillMaxSize().padding(top = m.barH), contentAlignment = Alignment.Center) {
            EmptyState(Icon.FILM, "Add a TMDB Key", "Open Settings and add a TMDB key to see ${hub.name}.", "Open Settings",
                { state.open(Route.Settings(com.dreamos.desktop.settings.SettingsGroup.Accounts)) })
        }
        return
    }
    // The masthead's words are the calendar's (no network); read again on every visit and every new day.
    val tile = state.model("season-tile:$hubId") { mutableStateOf<HubTile?>(null) }
    val model = state.rows("hub:$hub", { c.hub(hub) }, null)
    LaunchedEffect(hubId) { tile.value = runCatching { c.seasonHub(hubId) }.getOrNull() ?: tile.value }
    LaunchedEffect(c) {
        c.changes.collect {
            tile.value = runCatching { c.seasonHub(hubId) }.getOrNull() ?: tile.value
            model.refreshRows()
        }
    }
    LaunchedEffect(state.toTop) { if (state.toTop > 0) model.list.animateScrollToItem(0) }
    if (model.nothing) {
        Box(Modifier.fillMaxSize().padding(top = m.barH), contentAlignment = Alignment.Center) {
            EmptyState(Icon.FILM, "Couldn't Load ${hub.name}", "Check your connection, then try again.", "Try Again", { model.reloadAll() })
        }
        return
    }
    RowsPage(model, c) {
        item(key = "masthead") {
            val t = tile.value
            val mod = Modifier.padding(top = m.barH + m.u(28), bottom = m.u(26))
            if (t != null) HubMasthead(t, mod)
            else Box(mod.fillMaxWidth().height(m.u(96)))
        }
    }
}
