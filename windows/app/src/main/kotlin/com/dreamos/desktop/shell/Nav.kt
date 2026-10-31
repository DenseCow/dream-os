package com.dreamos.desktop.shell

import androidx.compose.runtime.staticCompositionLocalOf
import com.dreamos.desktop.settings.SettingsGroup
import com.dreamos.desktop.settings.SettingsRoute
import com.dreamos.desktop.shell.data.CardItem
import com.dreamos.desktop.shell.data.Hub
import com.dreamos.desktop.shell.data.Person
import com.dreamos.desktop.shell.data.Title

/** The four tabs, in the Fire Stick's order (no Apps tab: that is the Fire Stick's own). */
enum class Tab(val label: String) { SEARCH("Search"), HOME("Home"), BROWSE("Browse"), MYLIST("My List") }

/** Everything the shell can show over a tab. Tabs and hubs are the shell's pages; the rest are win-pages'. */
sealed interface Route {
    data class TabRoot(val tab: Tab) : Route
    data class HubPage(val hub: Hub) : Route
    data class Details(val title: Title) : Route
    /** [from]: the title page it was opened from (left out of the person's Known For). */
    data class PersonPage(val person: Person, val asRole: String? = null, val from: com.dreamos.desktop.settings.kit.TitleRef? = null) : Route
    data class Settings(val group: SettingsGroup = SettingsGroup.Profiles, val route: SettingsRoute? = null) : Route
    data object Profiles : Route
    data object Notices : Route
}

/** How any page moves around: the shell's [ShellState] implements it; win-pages reach it through [PagesBridge]. */
interface Nav {
    fun open(route: Route)
    /** One step back: a page to the one under it, a tab to Home, Home to its top. False when there's nowhere to go. */
    fun back(): Boolean
    fun tab(tab: Tab)
    fun openTitle(t: Title) = open(Route.Details(t))
    /** Play (or resume) a title through Dream Core and Dream Player. */
    fun play(t: Title, from: CardItem? = null)
    fun playTrailer(t: Title)
}

val LocalNav = staticCompositionLocalOf<Nav> { error("No Nav") }
