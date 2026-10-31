package com.dreamos.desktop.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dreamos.desktop.core.platform.Platform
import com.dreamos.desktop.core.service.Services
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.details.PersonPage
import com.dreamos.desktop.details.TitlePage
import com.dreamos.desktop.notices.NoticeBanner
import com.dreamos.desktop.notices.NoticesPage
import com.dreamos.desktop.notices.UpdatePrompt
import com.dreamos.desktop.profiles.WhoIsWatching
import com.dreamos.desktop.settings.LocalPagesServices
import com.dreamos.desktop.settings.NoticeType
import com.dreamos.desktop.settings.SettingsGroup
import com.dreamos.desktop.settings.SettingsPage
import com.dreamos.desktop.settings.SettingsRoute
import com.dreamos.desktop.settings.kit.LocalPagesNav
import com.dreamos.desktop.settings.kit.PagesNav
import com.dreamos.desktop.settings.kit.PlayRequest as PagesPlay
import com.dreamos.desktop.settings.kit.TitleRef
import com.dreamos.desktop.setup.SetupWizard
import com.dreamos.desktop.shell.data.Person
import com.dreamos.desktop.settings.Notice as PagesNotice
import com.dreamos.desktop.core.model.Notice as CoreNotice

/** Where the app is: the first-run setup, Who's Watching, or the app itself. */
enum class Phase { SETUP, WHO, APP }

/** The phase at launch: setup until it's finished, Who's Watching with 2+ profiles or a locked single one, else the app. */
fun startPhase(): Phase = when {
    !Services.profiles.isSetUp() -> Phase.SETUP
    Services.profiles.all.value.size > 1 || Services.profiles.current.value.hasPin -> Phase.WHO
    else -> Phase.APP
}

/**
 * The whole program in the window: the launch intro over everything, then the setup wizard while Dream OS isn't set
 * up, Who's Watching when there's a choice to make (or a PIN), then the shell. The pages get their services and a
 * [PagesNav] on the shell's Nav; notice banners and the new-version prompt sit over it all, never over playback.
 */
@Composable
fun AppRoot(state: ShellState, window: WindowControl?, intro: Boolean, start: Phase = startPhase(), onIntroDone: () -> Unit = {}) {
    var phase by remember { mutableStateOf(start) }
    // Run Setup Again (from Settings) can be closed: Close, or Esc on Welcome, returns to the app (the Fire Stick's canCancel).
    var setupAgain by remember { mutableStateOf(false) }
    state.runSetup = { setupAgain = true; phase = Phase.SETUP }
    val bridge = remember(state) { PagesBridge(state) }
    // Banners are collected from launch on and wait while the intro, setup or Who's Watching is up (the Fire Stick
    // holds them there too); collected only in the app, the ones flushed earlier were lost.
    val bannerQueue = remember { mutableStateListOf<CoreNotice>() }
    LaunchedEffect(Unit) { Services.notices.banners.collect { bannerQueue += it } }
    var introOn by remember { mutableStateOf(intro) }
    CompositionLocalProvider(LocalPagesServices provides AppServices.pagesServices, LocalPagesNav provides bridge) {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = phase,
                transitionSpec = { if (Motion.reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0)) else fadeIn(tween(420)) togetherWith fadeOut(tween(260)) },
                label = "phase",
            ) { p ->
                when (p) {
                    Phase.SETUP -> SetupWizard(onFinish = {
                        Services.profiles.markSetUp()
                        setupAgain = false
                        phase = if (Services.profiles.all.value.size > 1 || Services.profiles.current.value.hasPin) Phase.WHO else Phase.APP
                    }, onClose = if (setupAgain) ({ setupAgain = false; phase = Phase.APP }) else null)
                    Phase.WHO -> WhoIsWatching(onChosen = { state.profileChanged(); phase = Phase.APP })
                    Phase.APP -> DreamApp(state, window, intro = false)
                }
            }
            if (phase == Phase.APP && !introOn) Banners(state, bannerQueue)
            UpdateChecks()
            val homeReady = com.dreamos.desktop.startup.HomeReady.remember(state)
            if (intro) IntroOnce({ introOn = false; onIntroDone() }) { phase != Phase.APP || homeReady() }
            // Settings › About › Replay Intro: the same intro, at its shortest hold (nothing loads behind it).
            var replays by remember { mutableStateOf(0) }
            state.replayIntro = { replays++ }
            if (replays > 0) androidx.compose.runtime.key(replays) { IntroOnce({}) { true } }
        }
    }
}

/** The intro plays once per launch, over whichever phase is underneath (it loads behind), until [ready]. */
@Composable
private fun IntroOnce(onDone: () -> Unit, ready: () -> Boolean) {
    var show by remember { mutableStateOf(true) }
    if (show) Intro(Motion.reduced, ready) { show = false; onDone() }
}

/**
 * Notice banners one at a time (top right, from `Services.notices.banners`) and the new-version prompt (once per
 * version; Later remembers it). Nothing shows while Dream Player plays: core holds banners until it closes.
 */
@Composable
private fun Banners(state: ShellState, queue: MutableList<CoreNotice>) {
    Box(Modifier.fillMaxSize()) {
        NoticeBanner(
            queue.firstOrNull()?.let(::pagesNotice),
            onClick = { n -> Services.notices.markRead(n.key); state.openNoticeTarget(n.target); queue.removeAll { it.key == n.key } },
            onGone = { n -> queue.removeAll { it.key == n.key } },
        )
        val update by Services.updates.status.collectAsState()
        val prefs = remember { DreamStore.shared.file("shell") }
        var later by remember { mutableStateOf(prefs.getString("update.later")) }
        var hour by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(15 * 60_000L); hour++ } }
        val latest = update.latest
        if (update.available && latest != null && !snoozed(later, latest.version, hour) && !AppServices.playerShowing) {
            UpdatePrompt(
                latest.version,
                onGet = { Platform.current.openUrl(RELEASES); later = latest.version + "|" + Long.MAX_VALUE },
                // Later: off Home until tomorrow, at least 12 hours (the Fire Stick's snooze); the notice stays in the list.
                onLater = {
                    val v = latest.version + "|" + System.currentTimeMillis()
                    prefs.putString("update.later", v); later = v
                    Services.notices.markRead(com.dreamos.desktop.core.update.UpdateCheck.NOTICE_PREFIX + latest.version)
                },
            )
        }
    }
}

private const val RELEASES = "https://github.com/DenseCow/dream-os/releases"

/** "4.4|<epoch ms>" from Later: hidden until the next midnight after it, and at least 12 hours. */
private fun snoozed(later: String?, version: String, @Suppress("UNUSED_PARAMETER") tick: Int): Boolean {
    val (v, at) = later?.split('|')?.let { it.getOrNull(0) to it.getOrNull(1)?.toLongOrNull() } ?: return false
    if (v != version || at == null) return false
    if (at == Long.MAX_VALUE) return true
    val z = java.time.ZoneId.systemDefault()
    val midnight = java.time.Instant.ofEpochMilli(at).atZone(z).toLocalDate().plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
    return System.currentTimeMillis() < maxOf(midnight, at + 12 * 60 * 60_000L)
}

/**
 * The update check without a click (the Fire Stick's DreamUpdate): shortly after launch, then once a day. With
 * Settings › Updates › Automatic Updates on (the default), a newer version's setup downloads ahead so Get the Update
 * installs at once; it still asks first.
 */
@Composable
private fun UpdateChecks() {
    LaunchedEffect(Unit) {
        // After an update: "You're Up To Date!" / "Dream OS 4.4 is installed.", a banner once (DreamUpdate.postInstalled).
        val shell = DreamStore.shared.file("shell")
        val now = com.dreamos.desktop.core.update.UpdateCheck.currentVersion()
        val seen = shell.getString("version.seen")
        if (now != "0" && seen != now) {
            if (seen != null && com.dreamos.desktop.core.update.UpdateCheck.newer(now, seen)) runCatching {
                Services.notices.post(com.dreamos.desktop.core.model.Notice("updated:$now", com.dreamos.desktop.core.model.Notice.TYPE_UPDATE,
                    "You're Up To Date!", "Dream OS $now is installed.", com.dreamos.desktop.core.model.NoticeSeverity.INFO), banner = true, store = false)
            }
            shell.putString("version.seen", now)
        }
        delay(20_000)
        while (true) {
            val s = runCatching { Services.updates.check(force = false) }.getOrNull()
            val auto = runCatching { DreamStore.shared.profileFile("pages").getBoolean("updates.auto", true) }.getOrDefault(true)
            if (s?.available == true && auto) runCatching { AppServices.pagesServices.updates.prefetch() }
            delay(24 * 60 * 60_000L)
        }
    }
}

/** Core's notice as the pages' banner draws it. */
fun pagesNotice(n: CoreNotice) = PagesNotice(
    n.key,
    when (n.type) {
        "expiry" -> NoticeType.Expiry; "limit" -> NoticeType.Limit; "episodes" -> NoticeType.Episodes
        "update" -> NoticeType.Update; "summary" -> NoticeType.Summary; else -> NoticeType.Info
    },
    n.title, n.body, "Just Now", n.read, n.action?.label, n.severity != com.dreamos.desktop.core.model.NoticeSeverity.INFO, n.action?.target,
    critical = n.severity == com.dreamos.desktop.core.model.NoticeSeverity.CRITICAL,
)

/** Where a notice's action goes: "renew:pm" / "accounts" (Accounts), "home", "title:<kind>:<id>:<name>", "update" (About). */
fun ShellState.openNoticeTarget(target: String?) {
    val t = target ?: return
    when {
        // Renew opens the service's renewal page, as the Notifications list does (and the iPhone's Safari).
        t.startsWith("renew:") -> com.dreamos.desktop.core.playback.PremiumExpiry.renewUrl(t)?.let { Platform.current.openUrl(it) }
            ?: open(Route.Settings(SettingsGroup.Accounts, SettingsRoute.Accounts))
        t == "accounts" -> open(Route.Settings(SettingsGroup.Accounts, SettingsRoute.Accounts))
        t == "home" -> tab(Tab.HOME)
        t == "update" -> open(Route.Settings(SettingsGroup.About, SettingsRoute.About))
        t.startsWith("title:") -> t.split(":", limit = 4).let { p ->
            val id = p.getOrNull(2)?.toIntOrNull() ?: return
            openTitle(Targets.titleOf(id, p.getOrNull(1) == "tv", p.getOrNull(3).orEmpty()))
        }
    }
}

/** win-pages' pages, full screen over the tab (they carry their own Back), on the shell's Nav. */
@Composable
fun PagesPage(r: Route, state: ShellState) {
    val back = { state.back(); Unit }
    when (r) {
        is Route.Details -> TitleRef(r.title.id, r.title.isShow, r.title.name).let { ref ->
            // The card's own facts (name, backdrop, year) paint the page's top at once while the details load.
            val t = r.title
            TitlePage(ref, AppServices.titleSource, onBack = back, initial = com.dreamos.desktop.details.TitleDetails(
                ref, t.name, backdropUrl = t.backdrop("w1280"), posterUrl = t.poster(), year = t.year.toIntOrNull(), overview = t.overview,
            ))
        }
        is Route.PersonPage -> PersonPage(r.person.id, r.person.name, AppServices.titleSource, onBack = back, asRole = r.asRole, from = r.from)
        is Route.Settings -> SettingsPage(
            onBack = back,
            onSwitchProfile = { state.open(Route.Profiles) },
            onRunSetup = { state.runSetup() },
            onReplayIntro = { state.replayIntro() },
            startGroup = r.group, startRoute = r.route,
        )
        // From Settings the profile in use is trusted: no PIN to pick or edit it (the Fire Stick's trustedId).
        Route.Profiles -> WhoIsWatching(onChosen = { state.back(); state.profileChanged() }, onClose = back, trusted = Services.profiles.current.value.id)
        Route.Notices -> NoticesPage(onBack = back)
        else -> Unit
    }
}

/** The pages' way out, on the shell: back, titles, people, Settings, Dream Player, trailers, the browser. */
class PagesBridge(private val state: ShellState) : PagesNav {
    override fun back() { state.back() }
    override fun openTitle(ref: TitleRef) = state.openTitle(Targets.titleOf(ref.tmdbId, ref.isShow, ref.name))
    override fun openPerson(personId: Int, name: String) = state.open(Route.PersonPage(Person(personId, name)))
    override fun openPerson(personId: Int, name: String, asRole: String?, from: TitleRef?) =
        state.open(Route.PersonPage(Person(personId, name), asRole?.ifBlank { null }, from))
    override fun openSettings() = state.open(Route.Settings())
    override fun openUpdate() = state.open(Route.Settings(SettingsGroup.About, SettingsRoute.About))
    override fun play(request: PagesPlay) =
        state.playAt(Targets.titleOf(request.title.tmdbId, request.title.isShow, request.title.name), request.season, request.episode, null, request.fromStart)
    override fun playTrailer(title: TitleRef, videoKey: String) {
        state.playTrailer(Targets.titleOf(title.tmdbId, title.isShow, title.name), videoKey.ifBlank { null })
    }
    override fun playPrepared(request: com.dreamos.desktop.core.playback.PlayRequest, fallback: PagesPlay) = state.playPrepared(request)
    override fun openUrl(url: String) { Platform.current.openUrl(url) }
}

