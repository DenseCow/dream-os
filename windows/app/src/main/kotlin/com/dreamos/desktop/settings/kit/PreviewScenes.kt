package com.dreamos.desktop.settings.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.dreamos.desktop.notices.UpdatePrompt
import com.dreamos.desktop.notices.NoticeBanner
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import com.dreamos.desktop.details.DemoTitleSource
import com.dreamos.desktop.details.DevTitleSource
import com.dreamos.desktop.details.PersonPage
import com.dreamos.desktop.details.TitlePage
import com.dreamos.desktop.settings.Debrid
import com.dreamos.desktop.settings.SettingsGroup
import com.dreamos.desktop.settings.SettingsPage
import com.dreamos.desktop.settings.SettingsRoute

/** DEV TOOL: the scenes [main] in PageShots renders. Each page area adds its own. */
object PreviewScenes {
    private val dev by lazy { DevTitleSource.fromLocalProperties() }
    private val demo by lazy { DemoTitleSource(dev) }

    fun all(): List<Shot> = titleScenes() + settingsScenes() + setupScenes() + reviewScenes() + round2Scenes() + parityScenes()

    /** The settings parity pass (docs/port/parity-settings.md): every page it changed, at 1024 x 640 and 1280 x 720 ("par-"). */
    private fun parityScenes(): List<Shot> {
        val two = listOf(ShotSizes.SMALL, ShotSizes.HD)
        val setup = com.dreamos.desktop.setup.SetupStep.entries
        fun step(name: String) = setup.first { it.name == name }
        return listOf("Notifications", "Sources", "Accounts", "Updates", "About").map { g ->
            Shot("par-group-$g", sizes = two, content = settings(SettingsGroup.valueOf(g)))
        } + listOf(
            "accounts" to SettingsRoute.Accounts, "service" to SettingsRoute.Service(Debrid.RealDebrid), "scrapers" to SettingsRoute.Scrapers,
            "addrepo" to SettingsRoute.AddRepo, "key-tmdb" to SettingsRoute.Key(true), "key-omdb" to SettingsRoute.Key(false), "timezone" to SettingsRoute.TimeZone,
            "sources" to SettingsRoute.Sources, "whatsnew" to SettingsRoute.WhatsNew, "whichbest" to SettingsRoute.WhichBest, "whatsdebrid" to SettingsRoute.WhatsDebrid,
            "trakt" to SettingsRoute.Trakt, "themes" to SettingsRoute.Themes, "about" to SettingsRoute.About, "notices" to SettingsRoute.Notices,
        ).map { (n, r) -> Shot("par-page-$n", sizes = two, act = { Thread.sleep(600); it.settle(6) }, content = settings(route = r)) } + listOf(
            // A fresh sign-in: the fake approves after a few polls, then the account check (Is Connected).
            Shot("par-connect-verify", sizes = listOf(ShotSizes.HD), act = { d -> repeat(40) { d.settle(10) } }) {
                val fresh = androidx.compose.runtime.remember { com.dreamos.desktop.settings.FakeServices.create(linked = false) }
                androidx.compose.runtime.CompositionLocalProvider(com.dreamos.desktop.settings.LocalPagesServices provides fresh) {
                    com.dreamos.desktop.settings.ConnectPage(Debrid.AllDebrid) {}
                }
            },
            Shot("par-profile-kids", sizes = two) { com.dreamos.desktop.profiles.ProfileEditor(com.dreamos.desktop.settings.Profile("3", "Kids", "🐼", 0xFF4ADE80, kidsLevel = 1, pinToLeave = true)) {} },
            Shot("par-profile-add", sizes = listOf(ShotSizes.HD), act = { it.settle(6) }) { com.dreamos.desktop.profiles.ProfileEditor(com.dreamos.desktop.settings.Profile("", "", "🦊", 0xFF38BDF8)) {} },
            Shot("par-banner-critical", sizes = listOf(ShotSizes.HD), act = { Thread.sleep(700); it.settle(10) }) {
                val n = com.dreamos.desktop.settings.Notice("exp:rd", com.dreamos.desktop.settings.NoticeType.Expiry, "Real-Debrid Premium Expired", "Renew or Link Another Service to Keep Streaming", "Just Now", false, "Renew", warning = true, critical = true)
                androidx.compose.foundation.layout.BoxWithConstraints(androidx.compose.ui.Modifier.fillMaxSize().background(Dream.Black)) {
                    com.dreamos.desktop.ui.kit.DreamTheme(com.dreamos.desktop.ui.kit.Metrics(maxWidth.value, maxHeight.value)) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) { NoticeBanner(n, {}, {}) }
                    }
                }
            },
        ) + listOf("Debrid", "Scrapers", "Check", "Profile", "Picture", "Extras", "Done").map { s ->
            Shot("par-setup-$s", sizes = two, act = { Thread.sleep(if (s == "Check") 4500 else 900); it.settle(8) }) { com.dreamos.desktop.setup.SetupWizard({}, start = step(s)) }
        }
    }

    private val REVIEW = listOf(ShotSizes.SMALL, ShotSizes.HD, ShotSizes.FHD)

    /** Every setup step, settings group and settings page at the three review sizes ("review-" prefix). */
    private fun reviewScenes(): List<Shot> =
        com.dreamos.desktop.setup.SetupStep.entries.map { st ->
            Shot("review-setup-${st.ordinal.toString().padStart(2, '0')}-${st.name}", sizes = REVIEW, act = { Thread.sleep(if (st == com.dreamos.desktop.setup.SetupStep.Check) 4500 else 700); it.settle(6) }) {
                com.dreamos.desktop.setup.SetupWizard({}, start = st)
            }
        } + SettingsGroup.entries.map { g -> Shot("review-group-${g.name}", sizes = REVIEW, content = settings(g)) } +
        listOf(
            SettingsRoute.Accounts, SettingsRoute.Service(Debrid.RealDebrid), SettingsRoute.Connect(Debrid.RealDebrid), SettingsRoute.Connect(Debrid.Premiumize),
            SettingsRoute.WhatsDebrid, SettingsRoute.WhichBest, SettingsRoute.Scrapers, SettingsRoute.AddRepo, SettingsRoute.Trakt, SettingsRoute.Sources,
            SettingsRoute.TimeZone, SettingsRoute.Key(true), SettingsRoute.Key(false), SettingsRoute.Themes, SettingsRoute.Notices, SettingsRoute.About,
        ).mapIndexed { i, r -> Shot("review-page-${i.toString().padStart(2, '0')}-${r.toString().substringBefore('(').substringAfterLast('.').substringBefore('@')}", sizes = REVIEW, content = settings(route = r)) }

    private fun titleScenes(): List<Shot> = listOf(
        Shot("title-show") { TitlePage(DemoTitleSource.SEVERANCE, demo, onBack = {}) },
        Shot("title-movie") { TitlePage(DemoTitleSource.DUNE_TWO, demo, onBack = {}) },
        Shot("title-lang", sizes = listOf(ShotSizes.HD)) { TitlePage(DemoTitleSource.SQUID_GAME, demo, onBack = {}) },
        Shot("title-hover", sizes = listOf(ShotSizes.HD), act = { it.hover(0.33f, 0.73f) }) { TitlePage(DemoTitleSource.SEVERANCE, demo, onBack = {}) },
        Shot("title-tab", sizes = listOf(ShotSizes.HD), act = { it.tab(3) }) { TitlePage(DemoTitleSource.SEVERANCE, demo, onBack = {}) },
        Shot("title-episodes", sizes = listOf(ShotSizes.HD, ShotSizes.FHD), act = { it.wheel(0.5f, 0.5f, 9f); it.hover(0.4f, 0.55f) }) {
            TitlePage(DemoTitleSource.SEVERANCE, demo, onBack = {})
        },
        Shot("title-lower", sizes = listOf(ShotSizes.HD, ShotSizes.FHD), act = { it.wheel(0.95f, 0.5f, 30f) }) { TitlePage(DemoTitleSource.DUNE_TWO, demo, onBack = {}) },
        Shot("title-loading", sizes = listOf(ShotSizes.HD)) { TitlePage(DemoTitleSource.SEVERANCE, DemoTitleSource(dev, DemoTitleSource.Mode.Loading), onBack = {}) },
        Shot("title-error", sizes = listOf(ShotSizes.HD)) { TitlePage(DemoTitleSource.SEVERANCE, DemoTitleSource(dev, DemoTitleSource.Mode.Failing), onBack = {}) },
        Shot("person", sizes = listOf(ShotSizes.SMALL, ShotSizes.FHD)) { PersonPage(55536, "Melissa McCarthy", demo, onBack = {}) },
        // Parity audit, area 2 (docs/port/parity-title.md).
        Shot("par-title-upcoming", sizes = listOf(ShotSizes.HD, ShotSizes.FHD), act = { it.wheel(0.5f, 0.5f, 9f); it.hover(0.973f, 0.6875f); it.settle(8); it.wheel(0.973f, 0.6875f, 14f); it.hover(0.72f, 0.66f) }) {
            TitlePage(DemoTitleSource.SEVERANCE, DemoTitleSource(dev, DemoTitleSource.Mode.Upcoming), onBack = {})
        },
        Shot("par-title-coming", sizes = listOf(ShotSizes.HD), act = { it.wheel(0.5f, 0.5f, 9f) }) {
            TitlePage(DemoTitleSource.SEVERANCE, DemoTitleSource(dev, DemoTitleSource.Mode.Coming), onBack = {})
        },
        Shot("par-title-rec", sizes = listOf(ShotSizes.HD, ShotSizes.FHD), act = { it.wheel(0.95f, 0.5f, 12f); it.hover(0.2f, 0.4f) }) { TitlePage(DemoTitleSource.DUNE_TWO, demo, onBack = {}) },
        Shot("par-title-info", sizes = listOf(ShotSizes.HD, ShotSizes.FHD), act = { it.wheel(0.95f, 0.5f, 40f) }) { TitlePage(DemoTitleSource.DUNE_TWO, demo, onBack = {}) },
        Shot("par-title-showinfo", sizes = listOf(ShotSizes.HD), act = { it.wheel(0.95f, 0.5f, 40f) }) { TitlePage(DemoTitleSource.SQUID_GAME, demo, onBack = {}) },
        Shot("par-title-sources", sizes = listOf(ShotSizes.SMALL, ShotSizes.HD)) {
            com.dreamos.desktop.details.SourcesPanel(com.dreamos.desktop.details.TitleDetails(DemoTitleSource.SEVERANCE, "Severance"), 2, 4, demo, onClose = {})
        },
        Shot("par-person", sizes = listOf(ShotSizes.HD, ShotSizes.FHD)) {
            PersonPage(1100, "Arnold Schwarzenegger", demo, onBack = {}, asRole = "The Terminator", from = com.dreamos.desktop.settings.kit.TitleRef(218, false, "The Terminator"))
        },
    )

    private fun settings(group: SettingsGroup = SettingsGroup.Profiles, route: SettingsRoute? = null): @androidx.compose.runtime.Composable () -> Unit = {
        SettingsPage(onBack = {}, onSwitchProfile = {}, onRunSetup = {}, startGroup = group, startRoute = route)
    }

    private fun settingsScenes(): List<Shot> = listOf(
        Shot("settings-root", sizes = listOf(ShotSizes.SMALL, ShotSizes.FHD), content = settings(SettingsGroup.Accounts)),
        Shot("settings-playback", sizes = listOf(ShotSizes.HD), act = { it.hover(0.6f, 0.5f) }, content = settings(SettingsGroup.Playback)),
        Shot("settings-focus", sizes = listOf(ShotSizes.HD), act = { it.tab(2) }, content = settings(SettingsGroup.About)),
        Shot("settings-accounts", sizes = listOf(ShotSizes.HD), content = settings(route = SettingsRoute.Accounts)),
        Shot("settings-connect", sizes = listOf(ShotSizes.SMALL, ShotSizes.HD), content = settings(route = SettingsRoute.Connect(Debrid.AllDebrid))),
        Shot("settings-key", sizes = listOf(ShotSizes.HD), content = settings(route = SettingsRoute.Key(true))),
        Shot("settings-scrapers", sizes = listOf(ShotSizes.HD), content = settings(route = SettingsRoute.Scrapers)),
        Shot("settings-about", sizes = listOf(ShotSizes.HD, ShotSizes.UHD), content = settings(route = SettingsRoute.About)),
        Shot("settings-notices", sizes = listOf(ShotSizes.HD), content = settings(route = SettingsRoute.Notices)),
        Shot("settings-sources", sizes = listOf(ShotSizes.HD), content = settings(route = SettingsRoute.Sources)),
    )

    private fun setupScenes(): List<Shot> = listOf(
        Shot("setup-welcome", act = { Thread.sleep(900); it.settle(4) }) { com.dreamos.desktop.setup.SetupWizard({}, start = com.dreamos.desktop.setup.SetupStep.Welcome) },
        Shot("setup-need", sizes = listOf(ShotSizes.SMALL, ShotSizes.HD)) { com.dreamos.desktop.setup.SetupWizard({}, start = com.dreamos.desktop.setup.SetupStep.Need) },
        Shot("setup-tmdb", sizes = listOf(ShotSizes.SMALL, ShotSizes.HD)) { com.dreamos.desktop.setup.SetupWizard({}, start = com.dreamos.desktop.setup.SetupStep.Tmdb) },
        Shot("setup-debrid", sizes = listOf(ShotSizes.SMALL), act = { it.hover(0.5f, 0.55f) }) { com.dreamos.desktop.setup.SetupWizard({}, start = com.dreamos.desktop.setup.SetupStep.Debrid) },
        // A fresh setup connects AllDebrid (the fake sign-in approves after a few polls): the step stays, Continue focused.
        Shot("setup-debrid-stay", sizes = listOf(ShotSizes.HD), act = { d ->
            d.key(androidx.compose.ui.input.key.Key.Tab, 4, shift = true); d.enter()
            repeat(30) { d.settle(10) }
        }) {
            val fresh = androidx.compose.runtime.remember { com.dreamos.desktop.settings.FakeServices.create(linked = false) }
            androidx.compose.runtime.CompositionLocalProvider(com.dreamos.desktop.settings.LocalPagesServices provides fresh) {
                com.dreamos.desktop.setup.SetupWizard({}, start = com.dreamos.desktop.setup.SetupStep.Debrid)
            }
        },
        Shot("setup-check", sizes = listOf(ShotSizes.HD), act = { Thread.sleep(4500); it.settle(10) }) { com.dreamos.desktop.setup.SetupWizard({}, start = com.dreamos.desktop.setup.SetupStep.Check) },
        Shot("setup-profile", sizes = listOf(ShotSizes.SMALL)) { com.dreamos.desktop.setup.SetupWizard({}, start = com.dreamos.desktop.setup.SetupStep.Profile) },
        Shot("setup-done", sizes = listOf(ShotSizes.HD)) { com.dreamos.desktop.setup.SetupWizard({}, start = com.dreamos.desktop.setup.SetupStep.Done) },
        Shot("profiles-who", sizes = listOf(ShotSizes.SMALL, ShotSizes.FHD), act = { it.hover(0.5f, 0.45f) }) { com.dreamos.desktop.profiles.WhoIsWatching({}) },
        Shot("profiles-pin", sizes = listOf(ShotSizes.HD)) { androidx.compose.foundation.layout.Box { com.dreamos.desktop.profiles.PinPad(com.dreamos.desktop.profiles.PinMode.Verify("Enter PIN", "🌸  Sam", { com.dreamos.desktop.settings.PinCheck.Wrong }), {}, {}) } },
        Shot("profiles-edit", sizes = listOf(ShotSizes.HD)) { com.dreamos.desktop.profiles.ProfileEditor(com.dreamos.desktop.settings.Profile("3", "Kids", "🐼", 0xFF4ADE80, kidsLevel = 1, pinToLeave = true)) {} },
    )

    /** Round 2: Play's options, Search All Sources, the Trakt app page, size limits, banners. */
    private fun round2Scenes(): List<Shot> {
        val K = androidx.compose.ui.input.key.Key
        val sizes3 = listOf(ShotSizes.SMALL, ShotSizes.HD, ShotSizes.FHD)
        return listOf(
            Shot("r2-playmenu-show", sizes = listOf(ShotSizes.SMALL, ShotSizes.FHD), act = { it.settle(10); it.tab(1); it.key(K.Menu); it.settle(10) }) { TitlePage(DemoTitleSource.SEVERANCE, demo, onBack = {}) },
            Shot("r2-playmenu-season", sizes = listOf(ShotSizes.HD), act = { Thread.sleep(1500); it.settle(20) }) {
                var d by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.dreamos.desktop.details.TitleDetails?>(null) }
                androidx.compose.runtime.LaunchedEffect(Unit) { d = demo.details(DemoTitleSource.SEVERANCE) }
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(Dream.Black).padding(60.dp)) {
                    d?.let { com.dreamos.desktop.details.PlayOptions(it, demo, com.dreamos.desktop.details.PlayMenuState.Season(2), {}, {}, {}) }
                }
            },
            Shot("r2-playmenu-movie", sizes = listOf(ShotSizes.HD), act = { it.settle(10); it.tab(1); it.key(K.Menu); it.settle(10) }) { TitlePage(DemoTitleSource.DUNE_TWO, demo, onBack = {}) },
            Shot("r2-sources", sizes = listOf(ShotSizes.SMALL, ShotSizes.HD), act = { it.settle(10); it.tab(4); it.enter(); Thread.sleep(800); it.settle(10) }) { TitlePage(DemoTitleSource.DUNE_TWO, demo, onBack = {}) },
            Shot("r2-trakt-start", sizes = sizes3, content = settings(route = SettingsRoute.Trakt)),
            Shot("r2-trakt-ownapp", sizes = sizes3) { com.dreamos.desktop.settings.TraktOwnAppPage({}, {}) },
            Shot("r2-size", sizes = sizes3) {
                val p = com.dreamos.desktop.settings.LocalPagesServices.current.prefs
                androidx.compose.runtime.remember { com.dreamos.desktop.settings.SizeLimits.set(p, false, "limit", "range"); com.dreamos.desktop.settings.SizeLimits.set(p, true, "limit", "speed"); 0 }
                com.dreamos.desktop.settings.SourceSizePage {}
            },
            Shot("r2-banners", sizes = sizes3, act = { Thread.sleep(700); it.settle(10) }) {
                val n = com.dreamos.desktop.settings.Notice("exp:pm", com.dreamos.desktop.settings.NoticeType.Expiry, "Premiumize Premium Ends in 5 Days", "Renew by Tue, Nov 3 to Keep Streaming", "2 Hours Ago", false, "Renew", warning = true)
                androidx.compose.foundation.layout.BoxWithConstraints(androidx.compose.ui.Modifier.fillMaxSize().background(Dream.Black)) {
                    com.dreamos.desktop.ui.kit.DreamTheme(com.dreamos.desktop.ui.kit.Metrics(maxWidth.value, maxHeight.value)) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.Center)) { com.dreamos.desktop.notices.AllCaughtUp({}) }
                            NoticeBanner(n, {}, {})
                            UpdatePrompt("4.3.4", {}, {})
                        }
                    }
                }
            },
        )
    }
}
