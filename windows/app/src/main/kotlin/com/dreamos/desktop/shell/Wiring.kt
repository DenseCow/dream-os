package com.dreamos.desktop.shell

import com.dreamos.desktop.core.catalog.ContentServices
import com.dreamos.desktop.core.content.Tmdb
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.notices.Notices
import com.dreamos.desktop.core.platform.Platform
import com.dreamos.desktop.core.playback.Playback
import com.dreamos.desktop.core.playback.ScreenFacts
import com.dreamos.desktop.core.service.Services
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.trakt.Trakt
import com.dreamos.desktop.scrapers.ScraperInfo
import com.dreamos.desktop.scrapers.ScraperSources
import com.dreamos.desktop.scrapers.Scrapers
import com.dreamos.desktop.shell.data.CoreCatalog
import com.dreamos.desktop.startup.StartupTrace
import com.dreamos.desktop.ui.kit.Images
import com.dreamos.desktop.ui.kit.Tmdb as ArtUrls
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLEncoder
import java.util.Properties

/**
 * Starts core and Dream Core for the app (docs/port/requests.md): content services, the developer's keys handed over
 * once, Windows notifications, Dream Core with the viewer's scrapers as its source engine, and the hooks that tell
 * core whether the window is in front. Everything here is the app's wiring; the logic lives in core and scrapers.
 */
object AppServices {
    /**
     * Dream Core, built on its own thread at launch (half a second of class loading and settings that Home doesn't
     * need), done long before the first screen asks for it; a reader that comes earlier waits for it.
     */
    val playback: Playback? get() = if (started) built.join() else null
    private val built = java.util.concurrent.CompletableFuture<Playback?>()
    @Volatile private var sources: ScraperSources? = null
    /** The pop-in harness's stand-in for Dream Core's pills (its throwaway folder has no scrapers); null in the app. */
    @Volatile var availabilityOverride: com.dreamos.desktop.core.playback.AvailabilityApi? = null
    val availability: com.dreamos.desktop.core.playback.AvailabilityApi? get() = availabilityOverride ?: playback?.availability
    val catalog = CoreCatalog { availability }

    /** The title page's data (details, pills, Choose a Source), one for the app. */
    val titleSource by lazy { com.dreamos.desktop.details.CoreTitleSource(availability, playback) }

    /** Settings, Setup, Profiles, About and Notices on the real services, with setup's real search check. */
    val pagesServices by lazy { com.dreamos.desktop.settings.CoreServices.create(playback?.accounts, Scrapers.shared, SetupSearch) }

    /**
     * Setup's "Making Sure It All Works": one real search through the viewer's scrapers, then how many of the found
     * copies are ready on their debrid accounts (null: none linked).
     */
    private object SetupSearch : com.dreamos.desktop.settings.SetupCheck {
        override suspend fun run(stage: (String) -> Unit): com.dreamos.desktop.settings.CheckOutcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val core = playback?.core
            val src = sources ?: return@withContext com.dreamos.desktop.settings.CheckOutcome(false, message = "Restart Dream OS and try again.")
            val r = Scrapers.shared.test(src, { hashes ->
                core?.let { c -> kotlinx.coroutines.runBlocking { c.readyHashes(hashes.map { com.dreamos.desktop.core.playback.Source(it, "") }) } }?.size
            }) { line -> stage(line) }
            com.dreamos.desktop.settings.CheckOutcome(r.ok, r.found, r.ready, r.message)
        }
    }

    /** The window has focus (notices go to a banner rather than a Windows notification). */
    @Volatile var windowInFront = true
    /** Dream Player is showing (no banners over playback, the Play buttons' fade holds still); win-player sets it. */
    @Volatile var playerShowing = false
        set(v) { field = v; com.dreamos.desktop.ui.kit.ShellRainbow.covered = v }

    @Volatile private var started = false
    private val artScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /**
     * The first cards' art of a row filled ahead, at the saved window size (the same URLs the row's cards draw and
     * warm: ui/kit Cards.kt `cardArt`), for the background prefetch.
     */
    private fun warmArt(row: com.dreamos.desktop.core.model.CatalogRow, m: com.dreamos.desktop.ui.kit.Metrics, scale: Float): List<String?> {
        val style = when {
            row.isSpotlight -> com.dreamos.desktop.shell.data.RowStyle.SPOTLIGHT
            row.style == com.dreamos.desktop.core.model.RowStyle.POSTER -> com.dreamos.desktop.shell.data.RowStyle.POSTER
            row.style == com.dreamos.desktop.core.model.RowStyle.TOP10 -> com.dreamos.desktop.shell.data.RowStyle.TOP10
            row.style == com.dreamos.desktop.core.model.RowStyle.BIG -> com.dreamos.desktop.shell.data.RowStyle.BIG
            else -> com.dreamos.desktop.shell.data.RowStyle.LANDSCAPE
        }
        val px = com.dreamos.desktop.ui.kit.cardWidth(style, m) * scale
        val poster = style == com.dreamos.desktop.shell.data.RowStyle.POSTER || style == com.dreamos.desktop.shell.data.RowStyle.TOP10
        return com.dreamos.desktop.core.catalog.CatalogImpl.artPaths(row).take(12).map { path ->
            if (poster) ArtUrls.poster(path, px) else ArtUrls.backdrop(path, px)
        }
    }

    /** Once, before the first screen. */
    fun start() {
        if (started) return
        started = true
        // Skia's native library loaded here, on this thread, before any background work: art decoded off the UI
        // thread would otherwise race the first frame's Skia use, and skiko's loader lets the loser call into a
        // library that isn't bound yet (UnsatisfiedLinkError at the first frame, seen on a fast relaunch).
        org.jetbrains.skiko.Library.load()
        StartupTrace.span("services: content install") { ContentServices.install() }
        // Dream Core on its own thread (it needs the profile's settings from install, nothing the first screen needs).
        Thread({
            built.complete(StartupTrace.span("services: playback create") { runCatching { buildPlayback() } }
                .onFailure { System.err.println("Dream OS: Dream Core didn't start (${it.javaClass.simpleName}: ${it.message})") }.getOrNull())
        }, "dream-core-start").apply { isDaemon = true }.start()
        handOverDevKeys()
        // Home, Movies and TV Shows filled ahead (from TMDB answers on disk on a relaunch) while the intro plays; their
        // first cards' art follows as soon as each page's rows are in (Home first), as background prefetches.
        ContentServices.warm()
        // Home's New Episode banner (and the "NEW EPISODE" corners it decides) picked while the intro plays.
        com.dreamos.desktop.preload.BannerAhead.start()
        artScope.launch {
            val p = WindowPrefs.load()
            val scale = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().let { e ->
                if (e.isHeadlessInstance) 1f else e.defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX.toFloat()
            }
            // The size the window will open at (full screen and maximized included: Display), as the rows will draw.
            val (w, h) = Display.expectedSize(p)
            val m = com.dreamos.desktop.ui.kit.Metrics(w.toFloat(), h.toFloat())
            val started = HashSet<com.dreamos.desktop.core.model.CatalogPage>()
            ContentServices.warmed.collect { pages ->
                for ((page, rows) in pages) if (started.add(page)) rows.forEach { Images.prefetch(warmArt(it, m, scale)) }
            }
        }
        Notices.appInFront = { windowInFront }
        Notices.canShowBanner = { windowInFront && !playerShowing }
        Platform.current.notifier = { title, body -> Toasts.show(title, body) }
    }

    /** After the launch intro: heavy starts wait until the viewer sees Home. */
    fun afterIntro() {
        StartupTrace.mark("intro: gone")
        StartupTrace.logSummary()
        StartupTrace.writeIfAsked()
        Thread({ runCatching { Scrapers.shared.warm() }; runCatching { playback?.warm() } }, "dream-warm").apply { isDaemon = true }.start()
        com.dreamos.desktop.startup.StartArchive.refreshLater()
    }

    /** The window came to the front (or left it). */
    fun windowFocus(front: Boolean) {
        windowInFront = front
        if (front) { runCatching { Trakt.appInFront() }; runCatching { Notices.flush() } }
    }

    /** Dream Player closed: notices held during playback come out. */
    fun playerClosed() { playerShowing = false; runCatching { Notices.flush() } }

    fun shutdown() { runCatching { Scrapers.shared.engine.shutdown() } }

    // ---- Dream Core ----

    private fun buildPlayback(): Playback {
        val tmdb = com.dreamos.desktop.scrapers.TmdbFetch { path, query, _ ->
            val q = query.entries.joinToString("") { (k, v) -> "&$k=" + URLEncoder.encode(v, "UTF-8") }
            runCatching { Net.getText(Tmdb.url(path, q)) }.getOrNull()
        }
        val engine = ScraperSources(Scrapers.shared, ScraperInfo(tmdb)).also { sources = it }
        val local = System.getenv("LOCALAPPDATA")?.let { File(it) } ?: File(System.getProperty("user.home"), ".cache")
        return Playback.create(
            engine = engine,
            hooks = com.dreamos.desktop.core.catalog.ContentPlaybackHooks,
            store = com.dreamos.desktop.core.catalog.SettingsKeyValueStore("playback"),
            // Debrid tokens sealed with Windows DPAPI; the earlier plain playback_accounts.json moves over once.
            secrets = com.dreamos.desktop.core.platform.Secrets.playback,
            screen = object : ScreenFacts {},
            releases = com.dreamos.desktop.core.catalog.ContentReleases,
            premiumizeClientId = localProperty("premiumize.client_id").orEmpty(),
            streamCacheDir = File(local, "Dream OS/stream"),
        )
    }

    // ---- developer keys (local.properties, never compiled in) ----

    /**
     * A developer build hands the owner's TMDB key over once, as if typed in Settings; it never overwrites a key the
     * viewer entered and never comes back after they removed it (the Fire Stick's rule).
     */
    private fun handOverDevKeys() {
        val f = DreamStore.shared.file("shell")
        if (f.getString("devKeyHanded") != null) return
        val k = System.getenv("DREAM_TMDB_KEY")?.takeIf { it.isNotBlank() } ?: localProperty("tmdb.key") ?: return
        if (!Tmdb.hasKey()) Tmdb.setKey(k)
        f.putString("devKeyHanded", "1")
    }

    fun localProperty(name: String): String? {
        val dir = File(System.getProperty("user.dir"))
        for (file in listOf(File(dir, "local.properties"), File(dir.parentFile ?: dir, "local.properties"))) {
            if (!file.isFile) continue
            Properties().apply { file.inputStream().use { load(it) } }.getProperty(name)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }
}

/**
 * Windows notifications for notices posted while the window is in the back (Platform.notify): one tray icon, made on
 * the first notice, showing each as a Windows toast.
 */
object Toasts {
    private var icon: java.awt.TrayIcon? = null

    @Synchronized fun show(title: String, body: String) {
        if (!java.awt.SystemTray.isSupported()) return
        val i = icon ?: runCatching {
            val img = javax.imageio.ImageIO.read(Toasts::class.java.getResourceAsStream("/dream_icon.png"))
            java.awt.TrayIcon(img, "Dream OS").apply { isImageAutoSize = true }.also { java.awt.SystemTray.getSystemTray().add(it) }
        }.getOrNull() ?: return
        icon = i
        i.displayMessage(title, body, java.awt.TrayIcon.MessageType.NONE)
    }
}
