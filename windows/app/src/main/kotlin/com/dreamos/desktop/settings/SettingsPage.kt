package com.dreamos.desktop.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dreamos.desktop.about.AboutPage
import com.dreamos.desktop.notices.NoticesPage
import com.dreamos.desktop.profiles.ProfileFace
import com.dreamos.desktop.settings.kit.CycleRow
import com.dreamos.desktop.settings.kit.Dream
import com.dreamos.desktop.settings.kit.DreamButton
import com.dreamos.desktop.settings.kit.DreamType
import com.dreamos.desktop.settings.kit.Glyph
import com.dreamos.desktop.settings.kit.InfoRow
import com.dreamos.desktop.settings.kit.Mark
import com.dreamos.desktop.settings.kit.PageFrame
import com.dreamos.desktop.settings.kit.PageRow
import com.dreamos.desktop.settings.kit.ScrollPage
import com.dreamos.desktop.settings.kit.SwitchRow
import com.dreamos.desktop.settings.kit.dreamClick
import com.dreamos.desktop.settings.kit.lit
import com.dreamos.desktop.settings.kit.litColors
import com.dreamos.desktop.settings.kit.rainbowEdge
import com.dreamos.desktop.settings.kit.rainbowNow
import com.dreamos.desktop.settings.kit.softEdges
import kotlinx.coroutines.launch

/** Where Settings is: its root or one of its pages (a small stack, Back / Esc pops it). */
sealed interface SettingsRoute {
    data object Accounts : SettingsRoute
    data class Service(val s: Debrid) : SettingsRoute
    data class Connect(val s: Debrid) : SettingsRoute
    data object WhatsDebrid : SettingsRoute
    data object WhichBest : SettingsRoute
    data object Scrapers : SettingsRoute
    data object AddRepo : SettingsRoute
    data object Trakt : SettingsRoute
    data object Sources : SettingsRoute
    data object TimeZone : SettingsRoute
    data class Key(val tmdb: Boolean) : SettingsRoute
    data object Themes : SettingsRoute
    data object Notices : SettingsRoute
    data object About : SettingsRoute
    data object DisplayMode : SettingsRoute
    data object WhatsNew : SettingsRoute
}

/** The groups, in the Fire Stick's order minus its hardware-only ones, plus Your PC. */
enum class SettingsGroup(val title: String) {
    Profiles("Profiles"), Accounts("Accounts & Scrapers"), Notifications("Notifications"), Playback("Playback"),
    Sources("Sources & Quality"), Appearance("Appearance"), Home("Home & Browse"), Time("Time & Region"),
    Pc("Your PC"), Updates("Updates"), About("About"),
}

/**
 * Settings (PremiumSettings): groups on the left, the group's rows on the right; pages open over it.
 * [onSwitchProfile] opens Who's Watching; [onRunSetup] starts setup again.
 */
@Composable
fun SettingsPage(onBack: () -> Unit, onSwitchProfile: () -> Unit, onRunSetup: () -> Unit, startGroup: SettingsGroup = SettingsGroup.Profiles, startRoute: SettingsRoute? = null, onReplayIntro: (() -> Unit)? = null) {
    val stack = remember { mutableStateListOf<SettingsRoute>().apply { startRoute?.let { add(it) } } }
    val go: (SettingsRoute) -> Unit = { stack.add(it) }
    val pop: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else onBack() }
    when (val top = stack.lastOrNull()) {
        null -> SettingsRoot(onBack, startGroup, go, onSwitchProfile, onRunSetup, onReplayIntro)
        SettingsRoute.Accounts -> AccountsPage(pop, go)
        is SettingsRoute.Service -> ServicePage(top.s, pop, go)
        is SettingsRoute.Connect -> ConnectPage(top.s, pop)
        SettingsRoute.WhatsDebrid -> WhatsDebridPage(pop)
        SettingsRoute.WhichBest -> WhichBestPage(pop)
        SettingsRoute.Scrapers -> ScrapersPage(pop, go)
        SettingsRoute.AddRepo -> AddRepoPage(pop)
        SettingsRoute.Trakt -> TraktPage(pop)
        SettingsRoute.Sources -> SourcesQualityPage(pop)
        SettingsRoute.TimeZone -> TimeZonePage(pop)
        is SettingsRoute.Key -> KeyPage(top.tmdb, pop)
        SettingsRoute.Themes -> ThemesPage(pop)
        SettingsRoute.Notices -> NoticesPage(pop)
        SettingsRoute.About -> AboutPage(pop)
        SettingsRoute.DisplayMode -> DisplayModePage(pop)
        SettingsRoute.WhatsNew -> com.dreamos.desktop.about.WhatsNewPage(pop)
    }
}

@Composable
private fun SettingsRoot(onBack: () -> Unit, start: SettingsGroup, go: (SettingsRoute) -> Unit, onSwitchProfile: () -> Unit, onRunSetup: () -> Unit, onReplayIntro: (() -> Unit)?) {
    val svc = LocalPagesServices.current
    var group by remember { mutableStateOf(start) }
    var bump by remember { mutableIntStateOf(0) }
    val firstGroup = remember { FocusRequester() }
    PageFrame(onBack) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val narrow = maxWidth < 1100.dp
            Row(Modifier.fillMaxSize()) {
                // ---- Groups ----
                Column(Modifier.width(if (narrow) 270.dp else 320.dp).fillMaxHeight().background(Color(0xFF09090B)).padding(start = 24.dp, end = 18.dp, top = 22.dp)) {
                    DreamButton("Back", onBack, glyph = Glyph.Back)
                    Spacer(Modifier.height(26.dp))
                    val me = svc.profiles.all().firstOrNull { it.id == svc.profiles.currentId() }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
                        if (me != null) { ProfileFace(me, 40.dp); Spacer(Modifier.width(12.dp)) }
                        Column {
                            Text("Settings", style = DreamType.Title)
                            if (me != null) Text(me.name, style = DreamType.Caption)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    val scroll = rememberScrollState()
                    Column(Modifier.fillMaxSize().softEdges(scroll).verticalScroll(scroll).padding(vertical = 8.dp, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SettingsGroup.entries.forEachIndexed { i, g ->
                            GroupTab(g.title, g == group, if (i == 0) Modifier.focusRequester(firstGroup) else Modifier) { group = g }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
                // ---- Rows ----
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    key2(group, bump) {
                        ScrollPage(maxWidth = 860.dp, room = 26.dp) {
                            Spacer(Modifier.height(64.dp))
                            Text(group.title, style = DreamType.SubTitle)
                            Spacer(Modifier.height(18.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                GroupRows(group, svc, go, onSwitchProfile, onRunSetup, onReplayIntro) { bump++ }
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { firstGroup.requestFocus() } }
}

@Composable
private fun key2(a: Any, b: Any, content: @Composable () -> Unit) = androidx.compose.runtime.key(a, b) { content() }

/** A group in the left list: bright with a glass face when it's the one showing. */
@Composable
private fun GroupTab(title: String, current: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val lit = src.lit()
    val glow = rainbowNow(lit.any)
    val (fill, ink) = litColors(lit, rest = if (current) Color.White.copy(alpha = 0.10f) else Color.Transparent)
    Box(
        modifier.fillMaxWidth()
            .rainbowEdge(lit.any, glow, 10.dp, edge = if (lit.focused) 3.dp else 2.dp, halo = if (lit.focused) 10.dp else 0.dp)
            .background(fill, RoundedCornerShape(10.dp))
            .dreamClick(src, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text(title, style = DreamType.Row.copy(color = if (current || lit.any) ink else Dream.TextDim, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium))
    }
}

/** Cycles [choices] forward or back from [current]. */
fun <T> List<T>.step(current: T, by: Int): T = this[Math.floorMod(indexOf(current) + by, size)]

@Composable
private fun GroupRows(group: SettingsGroup, svc: PagesServices, go: (SettingsRoute) -> Unit, onSwitchProfile: () -> Unit, onRunSetup: () -> Unit, onReplayIntro: (() -> Unit)?, refresh: () -> Unit) {
    val p = svc.prefs
    val scope = rememberCoroutineScope()
    @Composable
    fun toggle(title: String, key: String, default: Boolean, caption: String? = null) {
        var on by remember(key) { mutableStateOf(p.bool(key, default)) }
        SwitchRow(title, on, { on = it; p.setBool(key, it) }, caption = caption)
    }
    @Composable
    fun cycle(title: String, key: String, choices: List<String>, default: String, caption: String? = null) {
        var v by remember(key) { mutableStateOf(p.str(key, default)) }
        CycleRow(title, v, onPrev = { v = choices.step(v, -1); p.setStr(key, v) }, onNext = { v = choices.step(v, 1); p.setStr(key, v) }, caption = caption)
    }
    when (group) {
        SettingsGroup.Profiles -> {
            val many = svc.profiles.all().size > 1
            val me = svc.profiles.all().firstOrNull { it.id == svc.profiles.currentId() }
            PageRow(if (many) "Switch Profile" else "Profiles", onSwitchProfile, value = me?.name, caption = "Add, edit or switch who's watching")
            PageRow("Run Setup Again", onRunSetup, caption = "Walk through Dream OS setup step by step")
        }
        SettingsGroup.Accounts -> {
            val linked = svc.debrid.states().filter { it.linked }
            val attention = linked.any { it.needsAttention }
            PageRow("Accounts", { go(SettingsRoute.Accounts) }, caption = "Your debrid services and Trakt",
                value = when { attention -> "Needs Attention"; linked.isEmpty() -> "Not Connected"; linked.size == 1 -> linked[0].service.title; else -> "${linked.size} Connected" },
                valueColor = when { attention -> Dream.RedText; linked.isNotEmpty() -> Dream.GreenText; else -> null })
            val t = svc.trakt.state()
            PageRow("Trakt", { go(SettingsRoute.Trakt) },
                caption = when { t.ended -> "Your Trakt sign-in has ended"; t.connected -> t.synced; else -> "Sync watched history, progress and My List" },
                value = when { t.ended -> "Sign In Again"; t.connected -> t.user?.let { "Connected as @$it" } ?: "Connected"; else -> "Not Connected" },
                valueColor = when { t.ended -> Dream.AmberText; t.connected -> Dream.GreenText; else -> null })
            val on = svc.scrapers.providers().count { it.on }
            PageRow("Scrapers", { go(SettingsRoute.Scrapers) }, caption = "Your scraper repositories and providers",
                value = when { svc.scrapers.repos().isEmpty() -> "Not Set Up"; on == 0 -> "Turn One On"; else -> "$on Providers On" },
                valueColor = when { svc.scrapers.repos().isEmpty() -> null; on == 0 -> Dream.AmberText; else -> Dream.GreenText })
            // Your own keys sit with the accounts (owner, 2026-10-30; the same order on all three apps).
            PageRow("TMDB Key", { go(SettingsRoute.Key(true)) }, caption = "Titles, artwork and search", value = if (svc.keys.tmdbAdded) "Added" else "Not Added",
                mark = if (svc.keys.tmdbAdded) null else Mark.WARN)
            PageRow("OMDb Key", { go(SettingsRoute.Key(false)) }, caption = "Rotten Tomatoes and IMDb scores", value = if (svc.keys.omdbAdded) "Added" else "Not Added")
            PageRow("Sources & Quality", { go(SettingsRoute.Sources) }, caption = "Quality, languages, subtitles and size limits",
                value = p.str("quality.max", "Automatic") + "  ·  " + if (p.bool("size.on", false)) "Size Limits On" else "No Size Limit")
        }
        SettingsGroup.Notifications -> {
            val unread = svc.notices.list().count { !it.read }
            PageRow("Notifications", { go(SettingsRoute.Notices) }, caption = "Account alerts and expiry reminders",
                value = when (unread) { 0 -> "All Caught Up"; 1 -> "1 Unread"; else -> "$unread Unread" })
            toggle("Account Summary at Startup", "notices.summary", true, "Time left on each account when Dream OS starts")
            toggle("New Episode Alerts", "notices.osAlerts", true, "On this PC when a show you follow has a new episode")
            var checking by remember { mutableStateOf<String?>(null) }
            PageRow("Check Premium Accounts", {
                checking = "Checking…"
                scope.launch {
                    val linked = svc.debrid.states().filter { it.linked }
                    checking = if (linked.isEmpty()) "No Account Linked" else { linked.forEach { svc.debrid.check(it.service) }; "Checked" }
                }
            }, caption = "Asks each linked service when its plan ends", value = checking, valueColor = if (checking == "Checked") Dream.GreenText else null, chevron = false)
        }
        SettingsGroup.Playback -> {
            toggle("Instant Play", "play.instant", true, "Finds the stream while you browse, so Play starts at once")
            toggle("Autoplay Next Episode", "play.autoplay", true, "Up Next counts down in the final minute")
            toggle("Are You Still Watching?", "play.stillWatching", true, "Asks after 3 episodes in a row")
            toggle("Skip Intros Automatically", "play.skipIntros", false, "When Dream Player knows where the intro ends")
            toggle("Subtitles", "subs.enabled", false, "Turns on subtitles in your language")
            PageRow("Subtitle Languages", { go(SettingsRoute.Sources) }, caption = "First and second choice",
                value = listOf(p.str("subs.lang1", "English"), p.str("subs.lang2", "None")).filter { it != "None" }.joinToString(", "))
            val audio = p.str("audio.lang", "English")
            if (audio != "Japanese") toggle("Prefer $audio Dubs", "audio.preferDubs", true, "Plays the $audio dub when a copy has one")
            toggle("Use Subtitles in the Video First", "subs.embedded", true, "Before downloading any")
            cycle("Subtitle Size", "subs.size", listOf("Small", "Medium", "Large", "Extra Large"), "Medium")
            cycle("Subtitle Style", "subs.style", listOf("Classic", "Boxed", "Yellow"), "Classic")
            cycle("Subtitle Position", "subs.position", listOf("Low", "Standard", "Raised"), "Standard")
        }
        SettingsGroup.Sources -> {
            // As the Fire Stick: Automatic (never chosen) steps to 1080p first, then around 4K / 1080p / 720p / SD.
            var q by remember { mutableStateOf(p.str("quality.max", "Automatic")) }
            val qs = listOf("4K", "1080p", "720p", "SD")
            fun stepQ(by: Int) { q = if (q !in qs) "1080p" else qs.step(q, by); p.setStr("quality.max", q) }
            CycleRow("Highest Quality", q, onPrev = { stepQ(-1) }, onNext = { stepQ(1) }, caption = "Hides anything better than this")
            toggle("Hide AV1", "hide.av1", true, "Older PCs play AV1 less smoothly")
            toggle("Hide HEVC (H.265)", "hide.hevc", false)
            toggle("Hide HDR", "hide.hdr", false, "For screens without HDR")
            toggle("Hide Dolby Vision", "hide.dv", false, "Keeps files that also have HDR")
            toggle("Hide CAM Copies", "hide.cam", true, "Recorded in a cinema")
            toggle("HDR and Dolby Vision First", "prefer.hdrFirst", false)
            PageRow("All Source Settings", { go(SettingsRoute.Sources) }, caption = "Size limits, languages and subtitles")
        }
        SettingsGroup.Appearance -> {
            PageRow("Themes", { go(SettingsRoute.Themes) }, value = "Dream Cinema", caption = "Your look  ·  more themes are coming")
            cycle("Background Trailers", "look.trailers", com.dreamos.desktop.trailer.BackgroundTrailers.entries.map { it.label }, com.dreamos.desktop.trailer.BackgroundTrailers.DEFAULT, "Behind the picture at the top of title pages")
            toggle("Navigation Sounds", "look.navSounds", true, "Soft ticks as you move")
            toggle("Intro Sound", "look.introSound", true, "The chime when Dream OS opens")
            toggle("Notification Sound", "look.noticeSound", true, "A soft chime when a notification appears")
        }
        SettingsGroup.Home -> {
            toggle("New Episode Chime", "home.episodeChime", false, "When a show you watch has a new episode")
            toggle("Keep Anime in Anime Sections", "home.animeApart", true, "Anime shows only on the Anime page and its rows")
        }
        SettingsGroup.Time -> {
            PageRow("Time Zone", { go(SettingsRoute.TimeZone) }, caption = "New episode times, the clock and reminders", value = TimeWords.zoneValue(p))
            var clock by remember { mutableStateOf(p.str("time.clock", "Automatic")) }
            val choices = listOf("Automatic", "12-Hour", "24-Hour")
            CycleRow("Clock Format", TimeWords.clockValue(clock), onPrev = { clock = choices.step(clock, -1); p.setStr("time.clock", clock) },
                onNext = { clock = choices.step(clock, 1); p.setStr("time.clock", clock) }, caption = "Now " + TimeWords.nowWords(p))
        }
        SettingsGroup.Pc -> {
            var start by remember { mutableStateOf(svc.startWithWindows(null)) }
            SwitchRow("Start With Windows", start, { start = svc.startWithWindows(it) }, caption = "Opens Dream OS when you sign in to Windows")
            PageRow("Display Mode", { go(SettingsRoute.DisplayMode) }, caption = "How Dream OS fills your screen", value = svc.displayMode(null))
        }
        // Settings › Updates (the Fire Stick's UpdatePage.settingsItems): the version and its state, Install, Check for
        // Updates, Automatic Updates and What's New. Installing happens on About, as it does from the prompt.
        SettingsGroup.Updates -> {
            var u by remember { mutableStateOf(svc.updates.state()) }
            val v = svc.updates.version
            PageRow("Dream OS", { go(SettingsRoute.About) }, value = "Version $v", caption = when (val s = u) {
                is UpdateState.Newer -> "Version ${s.version} is available"
                is UpdateState.Failed -> "Couldn't check last time"
                is UpdateState.UpToDate -> "Up to date"
                else -> "Checks for a new version once a day"
            }, valueColor = if (u is UpdateState.Newer) Dream.GreenText else null)
            (u as? UpdateState.Newer)?.takeIf { svc.updates.hasInstaller }?.let { n ->
                PageRow("Install Version ${n.version}", { go(SettingsRoute.About) }, value = svc.updates.newestSize.ifBlank { null },
                    caption = "Dream OS closes, installs it and opens again")
            }
            PageRow("Check for Updates", {
                if (u != UpdateState.Checking) { u = UpdateState.Checking; scope.launch { u = svc.updates.check() } }
            }, caption = if (svc.updates.checkedAt > 0) "Last checked " + whenWords(svc.updates.checkedAt) else "Not checked yet",
                value = if (u == UpdateState.Checking) "Checking…" else null, chevron = false)
            toggle("Automatic Updates", "updates.auto", true, "Downloads new versions, then asks before installing")
            PageRow("What's New", { go(SettingsRoute.WhatsNew) }, value = "Version $v", caption = "The changes in the version you have")
        }
        SettingsGroup.About -> {
            val u = svc.updates.state()
            PageRow("About Dream OS", { go(SettingsRoute.About) }, value = "Version ${svc.updates.version}", caption = when (u) {
                UpdateState.Checking -> "Checking for Updates…"
                is UpdateState.Newer -> "Version ${u.version} Available"
                is UpdateState.Failed -> "Couldn't Check for Updates"
                is UpdateState.UpToDate -> "Up to Date"
                UpdateState.Never -> "Version, Updates and Credits"
            }, valueColor = if (u is UpdateState.Newer) Dream.GreenText else null)
            if (onReplayIntro != null) PageRow("Replay Intro", onReplayIntro, caption = "The Dream OS opening, once more", chevron = false)
            PageRow("Source Code", { go(SettingsRoute.About) }, value = "GitHub", caption = "Open source  ·  GPL-3.0")
            InfoRow("Movie and Show Data", value = "TMDB", caption = "This product uses the TMDB API but is not endorsed or certified by TMDB.")
            InfoRow("Where to Watch", value = "JustWatch", caption = "Streaming availability from JustWatch")
            InfoRow("Scores", value = "OMDb", caption = "Rotten Tomatoes and IMDb scores from OMDb")
        }
    }
}

/**
 * Settings › Your PC › Display Mode: the three ways the window sits on the screen, each with its sentence. Saved for
 * this PC (every profile shares it); F11 or Alt+Enter switches between Windowed and the full-screen kind picked here.
 */
@Composable
fun DisplayModePage(onBack: () -> Unit) {
    val svc = LocalPagesServices.current
    ChoicePage(
        "Display Mode",
        com.dreamos.desktop.shell.DisplayMode.entries.map { it.label to it.line },
        svc.displayMode(null),
        onBack,
        note = "This PC keeps your choice for every profile. F11 or Alt+Enter switches between Windowed and full screen.",
    ) { svc.displayMode(it) }
}
