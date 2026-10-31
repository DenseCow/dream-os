package com.dreamos.desktop.setup

import com.dreamos.desktop.ui.kit.rainbowText
import com.dreamos.desktop.ui.kit.rainbowFill
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamos.desktop.about.dreamMark
import com.dreamos.desktop.profiles.Face
import com.dreamos.desktop.profiles.ProfileAvatars
import com.dreamos.desktop.profiles.ProfileColors
import com.dreamos.desktop.profiles.ProfileEditor
import com.dreamos.desktop.settings.AddRepoBody
import com.dreamos.desktop.settings.ChoicePage
import com.dreamos.desktop.settings.CheckOutcome
import com.dreamos.desktop.settings.ConnectBody
import com.dreamos.desktop.settings.Debrid
import com.dreamos.desktop.settings.DebridRow
import com.dreamos.desktop.settings.KeyBody
import com.dreamos.desktop.settings.LocalPagesServices
import com.dreamos.desktop.settings.Profile
import com.dreamos.desktop.settings.ScrapersPage
import com.dreamos.desktop.settings.ServicePage
import com.dreamos.desktop.settings.SettingsRoute
import com.dreamos.desktop.settings.SourcesQualityPage
import com.dreamos.desktop.settings.TimeWords
import com.dreamos.desktop.settings.TimeZonePage
import com.dreamos.desktop.settings.Toggle
import com.dreamos.desktop.settings.TraktPage
import com.dreamos.desktop.settings.WhatToTry
import com.dreamos.desktop.settings.WhatsDebridPage
import com.dreamos.desktop.settings.WhichBestPage
import com.dreamos.desktop.settings.kit.CycleRow
import com.dreamos.desktop.settings.kit.Dream
import com.dreamos.desktop.settings.kit.DreamButton
import com.dreamos.desktop.settings.kit.DreamType
import com.dreamos.desktop.settings.kit.Glyph
import com.dreamos.desktop.settings.kit.GlyphIcon
import com.dreamos.desktop.settings.kit.InfoRow
import com.dreamos.desktop.settings.kit.KeyField
import com.dreamos.desktop.settings.kit.Mark
import com.dreamos.desktop.settings.kit.Note
import com.dreamos.desktop.settings.kit.PageFrame
import com.dreamos.desktop.settings.kit.PageRow
import com.dreamos.desktop.settings.kit.PageScaffold
import com.dreamos.desktop.settings.kit.ResultLine
import com.dreamos.desktop.settings.kit.StatusMark
import com.dreamos.desktop.settings.kit.SwitchRow
import com.dreamos.desktop.settings.kit.dreamClick
import com.dreamos.desktop.settings.kit.lit
import com.dreamos.desktop.settings.kit.rainbowEdge
import com.dreamos.desktop.settings.kit.rainbowNow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime

/** The steps in order: chapter (null = none), index in it, and how many the chapter has. */
enum class SetupStep(val chapter: Int?, val index: Int, val count: Int) {
    Welcome(null, 0, 1), Need(0, 0, 2), Zone(0, 1, 2),
    Tmdb(1, 0, 4), Debrid(1, 1, 4), Scrapers(1, 2, 4), Check(1, 3, 4),
    Alerts(2, 0, 2), Startup(2, 1, 2),
    Profile(3, 0, 4), Who(3, 1, 4), Picture(3, 2, 4), Extras(3, 3, 4), Done(null, 0, 1),
}

private sealed interface Sub {
    data class Key(val tmdb: Boolean) : Sub
    data class Connect(val s: com.dreamos.desktop.settings.Debrid) : Sub
    data class Account(val s: com.dreamos.desktop.settings.Debrid) : Sub
    data object AddRepo : Sub
    data object Manage : Sub
    data object TimeZone : Sub
    /** A step's Help page: [extra] rows first (title, caption, page), then Show Me How and If Something Goes Wrong. */
    data class Help(val title: String, val topic: HelpTopic, val extra: List<Triple<String, String, Sub>> = emptyList()) : Sub
    /** A walkthrough or problem list; Done and Back return to [parent] (the Help or key page it came from). */
    data class Guide(val topic: HelpTopic, val how: Boolean, val parent: Sub?) : Sub
    data object Privacy : Sub
    data object ChangeSettings : Sub
    data object WhatsDebrid : Sub
    data object WhichBest : Sub
    data object Trakt : Sub
    data class Person(val p: Profile) : Sub
    data class Choice(val what: String) : Sub
}

/**
 * First-run setup (setup-redesign.md), for the PC: Welcome, Get Ready, Connect, Your PC, Make It Yours, Done, one
 * shape per step ([StepFrame]), one primary action, defaults already chosen, finished tasks moving on by themselves
 * (except a debrid sign-in and an added repository: there can be several, so those return to their step).
 * Esc goes back one step (or closes a page). [onClose] is set for Run Setup Again; [onFinish] after Start Watching.
 */
@Composable
fun SetupWizard(onFinish: () -> Unit, onClose: (() -> Unit)? = null, start: SetupStep? = null) {
    val svc = LocalPagesServices.current
    val p = svc.prefs
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(start ?: runCatching { SetupStep.valueOf(p.str("setup.step", "Welcome")) }.getOrDefault(SetupStep.Welcome)) }
    var forward by remember { mutableStateOf(true) }
    var sub by remember { mutableStateOf<Sub?>(null) }
    var gate by remember { mutableStateOf<String?>(null) }
    var gateBump by remember { mutableIntStateOf(0) }
    var bump by remember { mutableIntStateOf(0) }
    fun go(to: SetupStep, fwd: Boolean = true) { step = to; forward = fwd; gate = null; sub = null; p.setStr("setup.step", to.name) }
    fun next() = go(SetupStep.entries[(step.ordinal + 1).coerceAtMost(SetupStep.entries.lastIndex)])
    fun back() { if (sub != null) { sub = null; bump++ } else if (step.ordinal > 0) go(SetupStep.entries[step.ordinal - 1], false) else onClose?.invoke() }
    fun block(line: String) { gate = line; gateBump++ }
    /** A finished task closes its page; the step moves on by itself when it was reached going forward. */
    fun finished(ok: Boolean) { sub = null; bump++; if (ok && forward) scope.launch { delay(250); next() } }
    /**
     * A task that can be done more than once (another debrid service, another repository) closes its page and
     * stays on the step, with Continue focused and the rest of the list still there.
     */
    fun returned() { sub = null; bump++ }

    when (val s = sub) {
        is Sub.Key -> { PageScaffold(if (s.tmdb) "Enter Your TMDB Key" else "Add Rotten Tomatoes Scores", { sub = null }) {
            KeyBody(s.tmdb, onDone = { finished(s.tmdb && svc.keys.tmdbAdded && step == SetupStep.Tmdb) },
                onHelp = { how -> sub = Sub.Guide(if (s.tmdb) SetupGuides.tmdbHelp else SetupGuides.omdbHelp, how, s) })
        }; return }
        is Sub.Connect -> { PageScaffold("Connect ${s.s.title}", { sub = null }) { ConnectBody(s.s, onCancel = { sub = null }) { returned() } }; return }
        is Sub.Account -> { ServicePage(s.s, { sub = null; bump++ }, { r -> if (r is SettingsRoute.Connect) sub = Sub.Connect(r.s) }); return }
        Sub.AddRepo -> { PageScaffold("Add Scraper Repository", { sub = null }) { AddRepoBody(inSetup = true, onCancel = { sub = null }) { returned() } }; return }
        Sub.Manage -> { ScrapersPage({ sub = null; bump++ }, { r -> if (r == SettingsRoute.AddRepo) sub = Sub.AddRepo }); return }
        // The picked zone and its time show for a moment on the step before it moves on (the Fire Stick's 1.5 s).
        Sub.TimeZone -> { TimeZonePage({ sub = null }, onPicked = { sub = null; if (forward) scope.launch { delay(1500); next() } }); return }
        is Sub.Help -> { PageScaffold(s.title, { sub = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                s.extra.forEach { (t, c, to) -> PageRow(t, { sub = to }, caption = c) }
                HelpRows(s.topic) { how -> sub = Sub.Guide(s.topic, how, s) }
            }
        }; return }
        is Sub.Guide -> { HelpPage(s.topic, s.how) { sub = s.parent }; return }
        Sub.Privacy -> { PageScaffold("Where Your Information Goes", { sub = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("TMDB" to "Posters, artwork and details", "TVmaze" to "New-episode air times", "Your Debrid Service" to "Finds and plays what you choose",
                    "Your Scrapers" to "Searches only the sites you add", "Trakt" to "Only if you link it").forEach { (n, c) -> InfoRow(n, caption = c) }
            }
            Spacer(Modifier.height(14.dp)); Note("A few free services provide extras such as subtitles. Dream OS has no ads, tracking or analytics.")
        }; return }
        Sub.ChangeSettings -> { SourcesQualityPage { sub = null; bump++ }; return }
        Sub.WhatsDebrid -> { WhatsDebridPage { sub = null }; return }
        Sub.WhichBest -> { WhichBestPage { sub = null }; return }
        Sub.Trakt -> { TraktPage { sub = null; bump++ }; return }
        is Sub.Person -> { ProfileEditor(s.p) { sub = null; bump++ }; return }
        is Sub.Choice -> {
            when (s.what) {
                "quality" -> ChoicePage("Maximum Quality", com.dreamos.desktop.settings.Quality.choices(), p.str("quality.max", com.dreamos.desktop.settings.Quality.best()), { sub = null },
                    com.dreamos.desktop.settings.Quality.NOTE) { p.setStr("quality.max", it) }
                else -> ChoicePage("Audio Language", com.dreamos.desktop.settings.Languages.audio.map { it to null }, p.str("audio.lang", "English"), { sub = null }) { p.setStr("audio.lang", it) }
            }
            return
        }
        null -> {}
    }

    PageFrame(::back) {
        androidx.compose.runtime.key(step, bump) { StepBody(step, svc, ::next, ::block, { sub = it }, forward, gate, gateBump, onFinish) }
        if (onClose != null && step != SetupStep.Done) DreamButton("Close", onClose, modifier = Modifier.align(Alignment.TopEnd).padding(20.dp))
    }
}

@Composable
private fun StepBody(
    step: SetupStep, svc: com.dreamos.desktop.settings.PagesServices, next: () -> Unit, block: (String) -> Unit,
    open: (Sub) -> Unit, forward: Boolean, gate: String?, gateBump: Int, onFinish: () -> Unit,
) {
    val p = svc.prefs
    val scope = rememberCoroutineScope()
    val primary = remember { FocusRequester() }
    LaunchedEffect(step) { delay(60); runCatching { primary.requestFocus() } }
    @Composable fun Primary(text: String, onClick: () -> Unit) = DreamButton(text, onClick, primary = true, big = true, modifier = Modifier.focusRequester(primary))
    @Composable fun Second(text: String, onClick: () -> Unit) = DreamButton(text, onClick, big = true)
    @Composable fun Frame(glyph: Glyph?, title: String, sentence: String?, buttons: @Composable () -> Unit, big: Boolean = false, body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
        StepFrame(step.chapter, step.index, step.count, glyph, title, sentence, gate, gateBump, buttons, big, body)

    when (step) {
        SetupStep.Welcome -> WelcomeScreen { Primary("Start Setup", next) }
        SetupStep.Need -> Frame(Glyph.Info, "Here's What You'll Need", "Dream OS plays from accounts you connect yourself. It doesn't include any movies, shows or sources, and you're responsible for what you add.",
            { Primary("I Understand") { p.setBool("setup.accepted", true); next() }; Second("Where Your Information Goes") { open(Sub.Privacy) } }) {
            Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                NeedCard(1, "A TMDB Key", "Posters, details and search", "Free  ·  About 5 Minutes", Modifier.weight(1f).fillMaxSize())
                NeedCard(2, "A Debrid Service", "Streams what you watch in full quality", "About \$3 to \$12 a Month", Modifier.weight(1f).fillMaxSize())
                NeedCard(3, "A Scraper Repository", "Free add-ons that find the streams", "Free  ·  About 2 Minutes", Modifier.weight(1f).fillMaxSize())
            }
            Spacer(Modifier.height(16.dp))
            Note("Trakt, Rotten Tomatoes scores and the rest are optional. Add them later in Settings.")
        }
        SetupStep.Zone -> Frame(Glyph.Clock, "Check Your Time Zone", "Dream OS uses this to show when new episodes and movies come out.",
            { Primary("Continue", next); Second("Change Time Zone") { open(Sub.TimeZone) } }) {
            val auto = p.str("time.zone", "").isBlank()
            Column(Modifier.background(Dream.Glass, RoundedCornerShape(16.dp)).padding(22.dp)) {
                Text(TimeWords.labelOf(TimeWords.zone(p).id), style = DreamType.Big)
                Text(TimeWords.time(p, ZonedDateTime.now(TimeWords.zone(p))), style = DreamType.Display.copy(fontSize = 44.sp, fontWeight = FontWeight.Bold))
                Text(if (auto) "Set automatically from your PC." else "Chosen by you.", style = DreamType.Caption)
            }
        }
        SetupStep.Tmdb -> {
            val has = svc.keys.tmdbAdded
            val help = Sub.Help("Help With TMDB", SetupGuides.tmdbHelp)
            Frame(Glyph.Key, "Connect Movie & Show Info", "Posters, details and search come from TMDB, a free movie website. Dream OS needs your own free key, made in a few minutes.", {
                if (has) Primary("Continue", next) else Primary("Enter My Key") { open(Sub.Key(true)) }
                Second("Help") { open(help) }
                if (has && !svc.keys.omdbAdded) Second("Add Rotten Tomatoes Scores") { open(Sub.Key(false)) }
            }) {
                if (has) { ResultLine(Mark.OK, "Your TMDB Key Is Added"); Spacer(Modifier.height(6.dp)); Note("Posters, details and search are ready.") }
                else Row(verticalAlignment = Alignment.Top) {
                    com.dreamos.desktop.settings.Steps(listOf("Make a free account on TMDB.", "On TMDB's API page, copy the key labeled API Key.", "Come back and select Enter My Key."), Modifier.weight(1f))
                    Spacer(Modifier.width(32.dp))
                    SiteCard("Open TMDB's API Page", "https://www.themoviedb.org/settings/api")
                }
            }
        }
        SetupStep.Debrid -> {
            var states by remember { mutableStateOf(svc.debrid.states()) }
            var checking by remember { mutableStateOf(states.any { it.linked }) }
            LaunchedEffect(Unit) { states.filter { it.linked }.forEach { svc.debrid.check(it.service) }; states = svc.debrid.states(); checking = false }
            // The gate reads the viewer's first service (Use First's order), as the Fire Stick's SetupGate does.
            val order = svc.debrid.linkedInOrder()
            val first = order.firstOrNull()?.let { d -> states.first { it.service == d } }
            val ready = states.any { it.linked && !it.needsAttention && it.mark == Mark.OK }
            // Focus: Continue once a service is ready; else the first service that needs the viewer; else the first service.
            val rowFocus = remember { FocusRequester() }
            val focusRow = !ready && (first == null || first.needsAttention)
            LaunchedEffect(focusRow) { if (focusRow) { delay(90); runCatching { rowFocus.requestFocus() } } }
            Frame(Glyph.Sources, "Connect a Debrid Service", "A debrid service is a paid account that streams movies and shows to Dream OS in full quality. Choose the one you have.", {
                Primary("Continue") {
                    when {
                        ready -> next()
                        first == null -> block("Connect at least one debrid service to continue.")
                        checking -> block("Checking your account…")
                        first.word == "Sign In Again" -> block("Sign in to ${first.service.title} again, or connect another service, to continue.")
                        first.word == "Approve This Network" -> block("Approve this network in the email from ${first.service.title}, then choose ${first.service.title} to check again.")
                        first.word == "Expired" -> block("Your ${first.service.title} plan has expired. Renew it, or connect another service, to continue.")
                        // Linked but not answered yet: never let an unchecked account through (SetupGate.accountsBlocker).
                        first.word.endsWith("Not Checked Yet") -> block("Checking your account…")
                        else -> block("Couldn't reach ${first.service.title}. Check your internet connection, then choose ${first.service.title} to check again.")
                    }
                }
                Second("Help Me Choose") { open(Sub.Help("Help Me Choose", SetupGuides.debridHelp, listOf(
                    Triple("What's a Debrid Service?", "What it does, and where to get one", Sub.WhatsDebrid),
                    Triple("Which Service Works Best?", "Premiumize gives the fastest play", Sub.WhichBest)))) }
                if (!svc.trakt.state().connected) Second("Connect Trakt") { open(Sub.Trakt) }
            }) {
                val target = first?.takeIf { it.needsAttention }?.service ?: Debrid.AllDebrid
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Debrid.AllDebrid, Debrid.RealDebrid, Debrid.Premiumize, Debrid.TorBox).forEach { d ->
                        val st = states.first { it.service == d }
                        DebridRow(st, if (d == target) Modifier.focusRequester(rowFocus) else Modifier) { open(if (st.linked) Sub.Account(d) else Sub.Connect(d)) }
                    }
                }
            }
        }
        SetupStep.Scrapers -> {
            val repos = svc.scrapers.repos()
            val on = svc.scrapers.providers().count { it.on }
            val help = Sub.Help("Help With Scrapers", SetupGuides.scrapersHelp)
            Frame(Glyph.Sources, "Add Your Scrapers",
                if (repos.isEmpty()) "Scrapers are free add-ons that find the streams for your debrid service. They come in a repository: a web address you add here. Dream OS doesn't include any."
                else "Your scrapers find the streams for your debrid service.", {
                if (repos.isEmpty()) Primary("Add Repository") { open(Sub.AddRepo) }
                else Primary("Continue") { if (on > 0) next() else block("Add a scraper repository and turn on at least one provider to continue.") }
                if (repos.isNotEmpty()) Second("Manage Scrapers") { open(Sub.Manage) }
                Second("Help") { open(help) }
            }) {
                if (repos.isEmpty()) {
                    Column(Modifier.background(Dream.Glass, RoundedCornerShape(16.dp)).padding(20.dp)) {
                        Text("A Repository Address Looks Like This", style = DreamType.Row.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.height(10.dp))
                        listOf("https://someone.github.io/repo", "https://github.com/someone/repo", "https://example.com/repo.zip").forEach {
                            Text(it, style = DreamType.Row.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = Dream.TextDim)); Spacer(Modifier.height(4.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Note("Copy it exactly as you got it. It usually starts with https:// and often has github in it.")
                    }
                } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repos.forEach { r -> InfoRow(r.name, caption = "Version ${r.version ?: "Unknown"}  ·  " + short(r.address).substringBefore('/'), value = "Installed", valueColor = Dream.GreenText) }
                    InfoRow("Providers", caption = if (svc.scrapers.recommendedOn) "The recommended providers are on." else null, value = "$on of ${svc.scrapers.providers().size} On", valueColor = if (on > 0) Dream.GreenText else Dream.AmberText)
                }
            }
        }
        SetupStep.Check -> CheckStep(svc, forward, next, block, primary) { g, t, s, b, body -> Frame(g, t, s, b, body = body) }
        SetupStep.Alerts -> Frame(Glyph.Bell, "Get New Episode Alerts", "Your PC tells you when a show you follow has a new episode.", { Primary("Continue", next) }) {
            Toggle(p, "New Episode Alerts", "notices.osAlerts", true, "On this PC, only for shows you follow")
        }
        SetupStep.Startup -> Frame(Glyph.Pc, "Open Dream OS With Windows", "Dream OS can open by itself when you sign in to Windows, so it's ready on a PC by the TV.", { Primary("Continue", next) }) {
            var on by remember { mutableStateOf(svc.startWithWindows(null)) }
            SwitchRow("Start With Windows", on, { on = svc.startWithWindows(it) }, caption = "Opens Dream OS when you sign in to Windows")
            Spacer(Modifier.height(10.dp)); Note("It's in Settings too, under Your PC.")
        }
        SetupStep.Profile -> ProfileStep(svc, next, block, primary) { g, t, s, b, body -> Frame(g, t, s, b, body = body) }
        SetupStep.Who -> Frame(Glyph.Person, "Who Else Watches?", "Add a profile for everyone at home. A Kids profile only shows movies and shows up to the rating you choose.", { Primary("Continue", next) }) {
            val all = svc.profiles.all()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                all.forEach { pr ->
                    val caption = listOfNotNull(
                        if (pr.kidsLevel < 3) "Kids  ·  Up to " + com.dreamos.desktop.settings.KidsLevels.all[pr.kidsLevel].second else "All Ratings",
                        if (pr.locked) "PIN" else null, if (pr.id == svc.profiles.currentId()) "You" else null).joinToString("  ·  ")
                    PageRow(pr.name.ifBlank { "Profile" }, { open(Sub.Person(pr)) }, caption = caption, leading = { _ -> Face(pr.avatar, pr.color, 38.dp) })
                }
                if (all.size < 8) PageRow("Add a Profile", { open(Sub.Person(Profile("", "", ProfileAvatars[all.size % 12], ProfileColors[all.size % 8].first))) },
                    caption = "For someone else at home, or a Kids profile", leading = { ink -> GlyphIcon(Glyph.Plus, ink, 22.dp) })
            }
        }
        SetupStep.Picture -> Frame(Glyph.Film, "Picture and Sound", "These defaults suit most PCs. Change them now, or anytime in Settings.",
            { Primary("Continue", next); Second("Change Settings") { open(Sub.ChangeSettings) } }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                remember { com.dreamos.desktop.settings.Quality.ensureDefault(p) }
                PageRow("Maximum Quality", { open(Sub.Choice("quality")) }, caption = com.dreamos.desktop.settings.Quality.caption(), value = p.str("quality.max", com.dreamos.desktop.settings.Quality.best()))
                PageRow("Audio Language", { open(Sub.Choice("audio")) }, value = p.str("audio.lang", "English"))
                Toggle(p, "Subtitles", "subs.enabled", false, "Turns subtitles on when a movie or show starts")
                PageRow("Source Size Limits", { open(Sub.ChangeSettings) }, caption = "Skip files too large for your internet", value = if (p.bool("size.on", false)) "On" else "No Limit")
            }
        }
        SetupStep.Extras -> Frame(Glyph.Check, "A Few Extras", "Small touches, already set the way most people like them. They're all in Settings too.", { Primary("Continue", next) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Toggle(p, "Keep Anime in Anime Sections", "home.animeApart", true, "Anime shows only on the Anime page and its rows")
                val lang = p.str("audio.lang", "English")
                if (lang != "Japanese") Toggle(p, "Prefer $lang Dubs", "audio.preferDubs", true, "Plays the $lang dub when a copy has one")
                var tr by remember { mutableStateOf(p.str("look.trailers", com.dreamos.desktop.trailer.BackgroundTrailers.DEFAULT)) }
                val ch = com.dreamos.desktop.trailer.BackgroundTrailers.entries.map { it.label } // the Fire Stick's order
                CycleRow("Background Trailers", tr, onPrev = { tr = ch[(ch.indexOf(tr) + 2) % 3]; p.setStr("look.trailers", tr) }, onNext = { tr = ch[(ch.indexOf(tr) + 1) % 3]; p.setStr("look.trailers", tr) }, caption = "Behind the picture at the top of title pages")
                Toggle(p, "Autoplay Next Episode", "play.autoplay", true, "Up Next counts down in the final minute")
                Toggle(p, "Navigation Sounds", "look.navSounds", true, "Soft ticks as you move around")
                Toggle(p, "Intro Sound", "look.introSound", true, "A gentle chime at startup")
            }
        }
        SetupStep.Done -> DoneStep(svc, onFinish, primary) { g, t, s, b, body -> StepFrame(null, 0, 1, g, t, s, gate, gateBump, b, true, body) }
    }
}

private typealias FrameFn = @Composable (Glyph?, String, String?, @Composable () -> Unit, @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) -> Unit

/** Making Sure It All Works: runs as the step opens, then the speed card; passes move on by themselves. */
@Composable
private fun CheckStep(svc: com.dreamos.desktop.settings.PagesServices, forward: Boolean, next: () -> Unit, block: (String) -> Unit, primary: FocusRequester, frame: FrameFn) {
    val p = svc.prefs
    // Passed before (this run of setup or an earlier one): no second search, only the speed card (the Fire Stick's).
    val passedBefore = remember { p.bool("setup.checked", false) }
    var stage by remember { mutableStateOf<String?>("Starting Up…") }
    var result by remember { mutableStateOf<CheckOutcome?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var speed by remember { mutableStateOf<Int?>(null) }
    var speedLive by remember { mutableStateOf<Int?>(null) }
    var speedDone by remember { mutableStateOf(false) }
    var lowered by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(attempt) {
        result = null; stage = "Starting Up…"
        val r = if (passedBefore && attempt == 0) CheckOutcome(true, message = "Your services found streams the last time Dream OS checked.")
            else runCatching { svc.check.run { stage = it } }.getOrElse { CheckOutcome(false, message = "Something went wrong. Try again.") }
        stage = null; result = r
        if (!r.ok) p.setBool("setup.checked", false)
        if (r.ok) {
            p.setBool("setup.checked", true)
            speed = svc.check.speed { speedLive = it }; speedDone = true
            speed?.takeIf { it > 0 }?.let { p.setStr(com.dreamos.desktop.settings.SizeLimits.LAST_SPEED, it.toString()) }
        }
    }
    val q = p.str("quality.max", "4K").let { if (it == "Automatic") "4K" else it }
    val better = speed?.let { s -> when { q == "4K" && s < 25 -> if (s >= 8) "1080p" else "720p"; q == "1080p" && s < 8 -> "720p"; else -> null } }
    LaunchedEffect(speedDone, lowered) { if (speedDone && (better == null || lowered != null) && forward) { delay(1500); next() } }
    val r = result
    frame(Glyph.Check, "Making Sure It All Works", "Dream OS runs one real search with your debrid service and scrapers, so you know everything plays before you start.", {
        when {
            r == null -> DreamButton("Checking…", { block("One moment. Dream OS is still checking.") }, primary = true, big = true, modifier = Modifier.focusRequester(primary))
            r.ok -> DreamButton("Continue", next, primary = true, big = true, modifier = Modifier.focusRequester(primary))
            else -> DreamButton("Try Again", { attempt++ }, primary = true, big = true, modifier = Modifier.focusRequester(primary))
        }
    }) {
        when {
            r == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                StatusMark(Mark.BUSY, 26.dp); Spacer(Modifier.width(14.dp))
                Column { Text("Checking…", style = DreamType.Big); Text(stage ?: "", style = DreamType.Caption) }
            }
            r.ok -> {
                ResultLine(Mark.OK, "Ready to Watch")
                Spacer(Modifier.height(6.dp))
                Note(when {
                    passedBefore && attempt == 0 -> r.message
                    r.ready == 0 -> "Found ${r.found} sources, but none are ready on your account right now."
                    else -> "Found ${r.found} sources. " + if (r.ready == 1) "1 is ready to play." else "${r.ready} are ready to play."
                })
                Spacer(Modifier.height(20.dp))
                Column(Modifier.background(Dream.Glass, RoundedCornerShape(16.dp)).padding(18.dp)) {
                    val s = speed
                    Text(when { s != null -> "Your Connection  ·  $s Mbps  ·  ${SpeedTest.verdict(s)}"; speedDone -> "Your Connection"; else -> "Your Connection" + (speedLive?.let { "  ·  Testing…  $it Mbps" } ?: "") }, style = DreamType.Row.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(4.dp))
                    Text(when {
                        !speedDone -> "Testing your connection…"
                        s == null -> "The speed test couldn't run right now. Everything else is ready."
                        lowered != null -> "Maximum Quality is now $lowered. Change it anytime in Settings."
                        better != null -> "Too slow for smooth $q. $better will play more smoothly."
                        else -> "Fast enough for $q, your Maximum Quality."
                    }, style = DreamType.Caption)
                    if (better != null && lowered == null) { Spacer(Modifier.height(10.dp)); DreamButton("Use $better", { p.setStr("quality.max", better); lowered = better }) }
                }
            }
            else -> {
                ResultLine(Mark.BAD, "Not Quite Ready")
                Spacer(Modifier.height(6.dp)); Note(r.message, color = Dream.Text)
                Spacer(Modifier.height(14.dp)); WhatToTry(SetupGuides.checkHelp(r.message))
            }
        }
    }
}

/** Create Your Profile: name, picture, color for the profile in use. */
@Composable
private fun ProfileStep(svc: com.dreamos.desktop.settings.PagesServices, next: () -> Unit, block: (String) -> Unit, primary: FocusRequester, frame: FrameFn) {
    val scope = rememberCoroutineScope()
    val me = svc.profiles.all().firstOrNull { it.id == svc.profiles.currentId() } ?: Profile("", "", ProfileAvatars[0], ProfileColors[0].first)
    var p by remember { mutableStateOf(me.copy(name = if (me.name == "Me") "" else me.name)) }
    // Focus: the name field while it's blank, else Continue (the Fire Stick's).
    val nameFocus = remember { FocusRequester() }
    val blank = remember { p.name.isBlank() }
    LaunchedEffect(Unit) { if (blank) { delay(90); runCatching { nameFocus.requestFocus() } } }
    frame(Glyph.Person, "Create Your Profile", "A name and a picture. Each profile keeps its own list, history and look. Add a PIN in Who's Watching or in Settings.", {
        DreamButton("Continue", { if (p.name.isBlank()) block("Type your name first.") else scope.launch { svc.profiles.save(p.copy(name = p.name.trim()), null); next() } }, primary = true, big = true, modifier = Modifier.focusRequester(primary))
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Face(p.avatar, p.color, 72.dp); Spacer(Modifier.width(20.dp))
            KeyField("Your Name", p.name, { p = p.copy(name = it.take(20)) }, placeholder = "Name", maxWidth = 380.dp, fieldModifier = Modifier.focusRequester(nameFocus))
        }
        Spacer(Modifier.height(18.dp))
        Text("Picture", style = DreamType.Meta.copy(color = Dream.Text)); Spacer(Modifier.height(8.dp))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileAvatars.forEach { a -> Pick(a == p.avatar, { p = p.copy(avatar = a) }) { Text(a, fontSize = 24.sp) } }
        }
        Spacer(Modifier.height(14.dp))
        Text("Color", style = DreamType.Meta.copy(color = Dream.Text)); Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileColors.forEach { (c, _) -> Pick(c == p.color, { p = p.copy(color = c) }) { Box(Modifier.size(28.dp).background(Color(c), CircleShape)) } }
        }
    }
}

@Composable
private fun Pick(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val lit = src.lit()
    val glow = rainbowNow(lit.any)
    Box(Modifier.size(48.dp).rainbowEdge(lit.any, glow, 12.dp, edge = 2.5.dp, halo = 0.dp)
        .background(if (lit.hovered) Dream.GlassHover else Dream.Glass, RoundedCornerShape(12.dp))
        .then(if (selected) Modifier.border(2.dp, Dream.Pearl, RoundedCornerShape(12.dp)) else Modifier)
        .dreamClick(src, onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

/** You're All Set: what's set up, Start Watching, then the welcome. */
@Composable
private fun DoneStep(svc: com.dreamos.desktop.settings.PagesServices, onFinish: () -> Unit, primary: FocusRequester, frame: FrameFn) {
    val p = svc.prefs
    val me = svc.profiles.all().firstOrNull { it.id == svc.profiles.currentId() }
    var sendOff by remember { mutableStateOf(false) }
    if (sendOff) { SendOff(onFinish); return }
    frame(null, if (me?.name.isNullOrBlank()) "You're All Set" else "You're All Set, ${me!!.name}", "Change anything anytime in Settings.", {
        DreamButton("Start Watching", { svc.setupDone(true); p.setStr("setup.step", "Welcome"); sendOff = true }, primary = true, big = true, modifier = Modifier.focusRequester(primary))
    }) {
        if (me != null) { Face(me.avatar, me.color, 88.dp); Spacer(Modifier.height(20.dp)) }
        // Verified, not only linked (the Fire Stick's SetupGate.anyVerified).
        val linked = svc.debrid.states().any { it.linked && !it.needsAttention && it.mark == Mark.OK }
        val scr = svc.scrapers.providers().any { it.on }
        val items = listOf(
            true to "Profile", svc.keys.tmdbAdded to if (svc.keys.tmdbAdded) "Movie & Show Info" else "Movie & Show Info Not Added",
            svc.keys.omdbAdded to if (svc.keys.omdbAdded) "Ratings" else "Ratings Not Added",
            linked to if (linked) "Accounts Connected" else "Accounts", scr to if (scr) "Scrapers Ready" else "Scrapers",
            p.bool("setup.checked", false) to if (p.bool("setup.checked", false)) "Ready to Watch" else "Final Check",
            p.bool("notices.osAlerts", true) to if (p.bool("notices.osAlerts", true)) "New Episode Alerts On" else "New Episode Alerts Off",
        )
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(maxItemsInEachRow = 3, horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.forEach { (ok, t) -> Row(Modifier.width(220.dp), verticalAlignment = Alignment.CenterVertically) { StatusMark(if (ok) Mark.OK else Mark.OFF); Spacer(Modifier.width(10.dp)); Text(t, style = DreamType.Row) } }
        }
    }
}

/** The send-off: a rainbow halo and "Dream OS" settling in, "Welcome to Dream OS", about 2.6 s. */
@Composable
private fun SendOff(onFinish: () -> Unit) {
    val grow = remember { Animatable(0.9f) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { fade.animateTo(1f, tween(900)) }
        grow.animateTo(1f, tween(1600))
        delay(1000); onFinish()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(260.dp).alpha(fade.value * 0.55f).rainbowFill(CircleShape))
        Box(Modifier.size(232.dp).background(Dream.Black, CircleShape))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.scale(grow.value).alpha(fade.value)) {
            Text("Dream OS", style = DreamType.Display.copy(fontSize = 40.sp, fontWeight = FontWeight.Bold, color = rainbowText()))
            Spacer(Modifier.height(8.dp))
            Text("Welcome to Dream OS", style = DreamType.Row.copy(fontSize = 18.sp, color = Dream.TextDim))
        }
    }
}
