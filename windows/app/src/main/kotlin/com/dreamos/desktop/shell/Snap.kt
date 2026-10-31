package com.dreamos.desktop.shell

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.dreamos.desktop.browse.DiscoverModel
import com.dreamos.desktop.search.SearchModel
import com.dreamos.desktop.core.model.PlayRequest
import com.dreamos.desktop.core.platform.Platform
import com.dreamos.desktop.core.service.Services
import com.dreamos.desktop.shell.data.Hub
import com.dreamos.desktop.ui.kit.DreamTheme
import com.dreamos.desktop.ui.kit.Metrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Offscreen screenshots of the shell's pages at several window sizes, for checking layout without a window
 * (DREAM_SNAP=<folder> ./gradlew :app:run). Personal rows are filled with a few trending titles so their cards can be
 * checked; nothing here ships to viewers. DREAM_SNAP_ONLY=<size name> limits it to one size, DREAM_SNAP_PAGES to a
 * comma list of page names.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
object Snap {
    private class Size(val name: String, val w: Int, val h: Int, val density: Float)

    private val sizes = listOf(
        Size("1024x640", 1024, 640, 1f),
        Size("1280x720", 1280, 720, 1f),
        Size("1920x1080", 1920, 1080, 1f),
        Size("3840x2160@150", 3840, 2160, 1.5f),
        Size("3840x2160", 3840, 2160, 1f),
    )

    fun run(dir: String) {
        if (com.dreamos.desktop.preload.PopIn.on) { com.dreamos.desktop.preload.PopInRun.run(dir); return }
        val out = File(dir).apply { mkdirs() }
        // A throwaway data folder (never the viewer's): core's stores, the dev key handed over, a few saved and
        // half-watched titles so the personal rows can be checked.
        val root = java.nio.file.Files.createTempDirectory("dream-snap")
        if (System.getenv("DREAM_SNAP_PAGES")?.contains("applog") == true) { logCheck(root.toFile()); System.exit(0) }
        if (System.getenv("DREAM_SNAP_PAGES")?.contains("crash-card") == true) { crashCard(File(dir)); System.exit(0) }
        Platform.current = Platform.forTests(root)
        if (System.getenv("DREAM_SNAP_PAGES")?.split(",")?.contains("display") == true) System.exit(DisplayCheck.run(File(dir)))
        AppServices.start()
        val catalog = AppServices.catalog
        runBlocking {
            seed()
            // The pointer shots aim at the first row under the hero: the New Episode banner (checked in the flow
            // shots) is set aside here so that row sits where they expect it.
            if (System.getenv("DREAM_SNAP_FLOW") == null) repeat(10) { Services.newEpisodes.bannerPick()?.let { Services.newEpisodes.bannerDone(it) } }
        }
        if (System.getenv("DREAM_SNAP_FLOW") != null) { flow(out); System.exit(0) }
        if (System.getenv("DREAM_SNAP_PAGES")?.split(",")?.any { it.startsWith("menus") } == true) System.exit(MenuCheck.run(out))
        val only = System.getenv("DREAM_SNAP_ONLY")
        val pages = System.getenv("DREAM_SNAP_PAGES")?.split(",")?.map { it.trim() }?.toSet()
        var firstScene = true
        for (s in sizes.filter { only == null || it.name == only }) {
            fun shot(name: String, settle: Long = 2_500, setup: (ShellState) -> Unit = {}, act: (ImageComposeScene, ShellState) -> Unit = { _, _ -> }) {
                if (pages != null && name !in pages) return
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val shell = ShellState(catalog, scope)
                setup(shell)
                val scene = ImageComposeScene(s.w, s.h, Density(s.density)) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        DreamTheme(Metrics(maxWidth.value, maxHeight.value)) { DreamApp(shell, null, intro = name == "intro") }
                    }
                }
                val wait = if (firstScene) 7_000L else settle
                firstScene = false
                frames(scene, if (name == "intro") 900 else wait)
                act(scene, shell)
                frames(scene, 1_400)
                val img = scene.render(System.nanoTime())
                File(out, "${s.name}-$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                scene.close()
                println("snap ${s.name}-$name")
            }
            // The first Continue Watching card's art, where the pointer goes for the hover and right-click shots.
            val mm = Metrics(s.w / s.density, s.h / s.density)
            val card = Offset(
                (mm.gutter.value + mm.landW.value / 2) * s.density,
                (mm.heroH.value - mm.heroOverlap.value + mm.u(30).value + mm.liftRoom.value + mm.landW.value * 9f / 32f) * s.density,
            )
            shot("intro")
            shot("home")
            shot("home-rows", act = { _, sh -> scroll(sh, "home", 3) })
            shot("home-top10", act = { _, sh -> scroll(sh, "home", 7) })
            shot("home-hover", act = { sc, _ -> sc.sendPointerEvent(PointerEventType.Move, card) })
            shot("home-keys", act = { sc, sh ->
                key(sc, Key.DirectionDown); frames(sc, 300)
                key(sc, Key.DirectionDown); frames(sc, 300)
                key(sc, Key.DirectionRight)
            })
            shot("menu", act = { sc, _ ->
                sc.sendPointerEvent(PointerEventType.Move, card)
                sc.sendPointerEvent(PointerEventType.Press, card, button = androidx.compose.ui.input.pointer.PointerButton.Secondary)
            })
            // Remove from Continue Watching from the card's menu, by mouse and by keyboard: the card leaves its row
            // while focus is on its way back to it (the owner's "ActiveParent with no focused child", 2026-10-30).
            shot("menu-remove", act = { sc, _ ->
                sc.sendPointerEvent(PointerEventType.Move, card)
                sc.sendPointerEvent(PointerEventType.Press, card, button = androidx.compose.ui.input.pointer.PointerButton.Secondary)
                sc.sendPointerEvent(PointerEventType.Release, card, button = androidx.compose.ui.input.pointer.PointerButton.Secondary)
                frames(sc, 500)
                val at = menuRow(mm, card, s.density, 2)
                sc.sendPointerEvent(PointerEventType.Move, at)
                sc.sendPointerEvent(PointerEventType.Press, at, button = androidx.compose.ui.input.pointer.PointerButton.Primary)
                sc.sendPointerEvent(PointerEventType.Release, at, button = androidx.compose.ui.input.pointer.PointerButton.Primary)
                frames(sc, 1_500)
                key(sc, Key.DirectionRight); frames(sc, 300)
                probe(sc, mm, s.density)
            })
            shot("menu-keys-open", act = { sc, _ ->
                key(sc, Key.DirectionDown); frames(sc, 300)
                key(sc, Key.DirectionDown); frames(sc, 300)
                key(sc, Key.Menu); frames(sc, 500)
                key(sc, Key.DirectionDown); frames(sc, 200); key(sc, Key.DirectionDown)
            })
            shot("menu-remove-keys", act = { sc, _ ->
                key(sc, Key.DirectionDown); frames(sc, 300)
                key(sc, Key.DirectionDown); frames(sc, 300)
                key(sc, Key.Menu); frames(sc, 500)
                key(sc, Key.DirectionDown); key(sc, Key.DirectionDown); key(sc, Key.Enter)
                frames(sc, 1_500)
                key(sc, Key.DirectionRight); frames(sc, 300)
                key(sc, Key.DirectionUp); frames(sc, 300)
                probe(sc, mm, s.density)
            })
            // A row's edge arrows clicked to the end and back, with and without a card focused from the keyboard, and
            // a sideways wheel (the owner's arrow crash, 2026-10-30). Continue Watching gets 14 more cards first.
            if (pages == null || pages.any { it.startsWith("arrows") || it.startsWith("row-") }) runBlocking { seedMore() }
            // The edge arrow zones: the gutter's width at each edge, centered on the art above the pill band.
            val zoneY = card.y - mm.u(22).value * s.density
            val arrowR = Offset((mm.widthDp - mm.gutter.value / 2) * s.density, zoneY)
            val arrowL = Offset(mm.gutter.value / 2 * s.density, zoneY)
            fun clicks(sc: ImageComposeScene, at: Offset, n: Int) = repeat(n) {
                sc.sendPointerEvent(PointerEventType.Move, at)
                sc.sendPointerEvent(PointerEventType.Press, at, button = androidx.compose.ui.input.pointer.PointerButton.Primary)
                sc.sendPointerEvent(PointerEventType.Release, at, button = androidx.compose.ui.input.pointer.PointerButton.Primary)
                frames(sc, 520)
            }
            shot("arrows", act = { sc, _ ->
                sc.sendPointerEvent(PointerEventType.Move, card); frames(sc, 300)
                clicks(sc, arrowR, 8); clicks(sc, arrowL, 8); clicks(sc, arrowR, 2)
                probe(sc, mm, s.density)
            })
            shot("arrows-keys", act = { sc, _ ->
                key(sc, Key.DirectionDown); frames(sc, 300)
                key(sc, Key.DirectionDown); frames(sc, 300)
                sc.sendPointerEvent(PointerEventType.Move, card); frames(sc, 300)
                clicks(sc, arrowR, 8)
                key(sc, Key.DirectionRight); frames(sc, 300)
                sc.sendPointerEvent(PointerEventType.Move, card); frames(sc, 300)
                clicks(sc, arrowL, 8)
                key(sc, Key.DirectionLeft); frames(sc, 300)
                key(sc, Key.DirectionUp); frames(sc, 300)
            })
            fun page(sh: ShellState) = sh.model<RowsModel>("home") { error("no home") }.list.let { "${it.firstVisibleItemIndex}/${it.firstVisibleItemScrollOffset}" }
            fun row() = com.dreamos.desktop.ui.kit.RowGlide.movedPx.toInt()
            fun wheel(sc: ImageComposeScene, at: Offset, n: Int, dx: Float = 0f, dy: Float = 1f, shift: Boolean = false) = repeat(n) {
                sc.sendPointerEvent(PointerEventType.Scroll, at, scrollDelta = Offset(dx, dy),
                    keyboardModifiers = androidx.compose.ui.input.pointer.PointerKeyboardModifiers(isShiftPressed = shift))
                frames(sc, 120)
            }
            // Sideways input scrolls the row anywhere over it: a touchpad's sideways swipe, then Shift+wheel, with a
            // card focused from the keyboard first (focus follows to a card in view).
            shot("row-wheel", act = { sc, sh ->
                key(sc, Key.DirectionDown); frames(sc, 300)
                key(sc, Key.DirectionDown); frames(sc, 300)
                sc.sendPointerEvent(PointerEventType.Move, card)
                val p0 = page(sh); val r0 = row()
                wheel(sc, card, 10, dx = 1f, dy = 0f); frames(sc, 500)
                println("row-wheel: sideways swipe moved the row ${row() - r0} px, page $p0 -> ${page(sh)} (row moves, page stays)")
                val r1 = row()
                wheel(sc, card, 3, dy = 1f, shift = true); frames(sc, 500)
                println("row-wheel: Shift+wheel moved the row ${row() - r1} px, page -> ${page(sh)} (row moves, page stays)")
                key(sc, Key.DirectionRight); frames(sc, 300)
            })
            // The owner's case: the pointer rests on a card while reading, the wheel scrolls the page.
            shot("row-wheel-cards", act = { sc, sh ->
                sc.sendPointerEvent(PointerEventType.Move, card); frames(sc, 900)
                val p0 = page(sh); val r0 = row()
                wheel(sc, card, 5); frames(sc, 500)
                println("row-wheel-cards: page $p0 -> ${page(sh)}, row moved ${row() - r0} px (page moves, row 0)")
            })
            // The wheel over the right edge arrow scrolls the row; at the row's end the notches go to the page.
            shot("row-wheel-zone", act = { sc, sh ->
                sc.sendPointerEvent(PointerEventType.Move, card); frames(sc, 300)
                sc.sendPointerEvent(PointerEventType.Move, arrowR); frames(sc, 300)
                val p0 = page(sh); val r0 = row()
                wheel(sc, arrowR, 3); frames(sc, 500)
                println("row-wheel-zone: 3 notches on the arrow: row moved ${row() - r0} px, page $p0 -> ${page(sh)} (row moves, page stays)")
                val r1 = row()
                wheel(sc, arrowR, 30); frames(sc, 500)
                println("row-wheel-zone: 30 more: row moved ${row() - r1} px then stopped, page -> ${page(sh)} (the page moves at the row's end)")
            })
            // A page flick starting over the hero keeps scrolling the page while rows slide under the pointer.
            shot("row-wheel-flick", act = { sc, sh ->
                val hero = Offset(card.x, card.y - mm.u(260).value * s.density)
                sc.sendPointerEvent(PointerEventType.Move, hero); frames(sc, 450)
                val seen = mutableListOf(page(sh))
                repeat(10) { sc.sendPointerEvent(PointerEventType.Scroll, hero, scrollDelta = Offset(0f, 1f)); frames(sc, 80); seen += page(sh) }
                println("row-wheel-flick: page ${seen.joinToString(" ")} (should keep moving)")
            })
            // The arrows on screen: a row hovered, the pointer on the right arrow (lit), the fades at both edges.
            shot("row-arrows-look", act = { sc, _ ->
                sc.sendPointerEvent(PointerEventType.Move, card); frames(sc, 300)
                clicks(sc, arrowR, 1)
                sc.sendPointerEvent(PointerEventType.Move, arrowR); frames(sc, 400)
            })
            shot("browse", setup = { it.tab(Tab.BROWSE) })
            shot("browse-discover", setup = { it.tab(Tab.BROWSE) }, act = { _, sh -> runBlocking { sh.model("browse") { DiscoverModel() }.list.scrollToItem(4) } })
            // Discover's three pickers: the closed line, each panel opened by a click, then the keyboard path (Esc
            // returns focus to the button, Right moves to the next, Enter opens, Down and Enter pick, Space reopens).
            fun discover(sh: ShellState) = sh.model("browse") { DiscoverModel() }
            val toDiscover: (ShellState) -> Unit = { it.tab(Tab.BROWSE) }
            fun atDiscover(sh: ShellState) = runBlocking { discover(sh).list.scrollToItem(4) }
            shot("discover", setup = toDiscover, act = { _, sh -> atDiscover(sh) })
            for ((name, desc) in listOf("discover-kind" to "Movies or TV Shows", "discover-sort" to "Sort By", "discover-genre" to "Genres")) {
                shot(name, setup = toDiscover, act = { sc, sh ->
                    atDiscover(sh); frames(sc, 600)
                    val at = button(desc) ?: return@shot println("$name: no button $desc")
                    clicks(sc, at, 1)
                })
            }
            shot("discover-keys", setup = toDiscover, act = { sc, sh ->
                atDiscover(sh); frames(sc, 600)
                val sort = button("Sort By") ?: return@shot println("discover-keys: no Sort By button")
                clicks(sc, sort, 1)
                key(sc, Key.Escape); frames(sc, 300)
                key(sc, Key.DirectionRight); frames(sc, 300)
                key(sc, Key.Enter); frames(sc, 500)
                repeat(3) { key(sc, Key.DirectionDown); frames(sc, 150) }
                key(sc, Key.Enter); frames(sc, 1_500)
                println("discover-keys: after Esc, Right, Enter, Down x3, Enter: ${discover(sh).filter} (genre should be the third genre)")
                key(sc, Key.Spacebar); frames(sc, 500)
                key(sc, Key.DirectionUp); frames(sc, 200)
            })
            shot("discover-mouse-pick", setup = toDiscover, act = { sc, sh ->
                atDiscover(sh); frames(sc, 600)
                // Action on Movies, then TV Shows: the genre comes along as TV's Action (10759), as on the Fire Stick.
                val genre = button("Genres") ?: return@shot println("discover-mouse-pick: no Genres button")
                clicks(sc, genre, 1)
                val action = pickerRow("Action") ?: return@shot println("discover-mouse-pick: no Action row")
                clicks(sc, action, 1); frames(sc, 1_000)
                val kind = button("Movies or TV Shows") ?: return@shot println("discover-mouse-pick: no kind button")
                clicks(sc, kind, 1)
                val tv = pickerRow("TV Shows") ?: return@shot println("discover-mouse-pick: no TV Shows row")
                clicks(sc, tv, 1); frames(sc, 1_500)
                println("discover-mouse-pick: picked TV Shows: ${discover(sh).filter.kind}, genre ${discover(sh).filter.genre} (10759 = TV's Action); panel closed: ${pickerRow("TV Shows") == null}")
                // A click outside closes it without picking.
                val sortAt = button("Sort By") ?: return@shot
                clicks(sc, sortAt, 1)
                val before = discover(sh).filter
                clicks(sc, Offset(s.w / 2f, s.h - 20f), 1)
                println("discover-mouse-pick: outside click kept the filter: ${discover(sh).filter == before}")
            })
            shot("search", setup = { it.tab(Tab.SEARCH) })
            shot("search-results", setup = { it.tab(Tab.SEARCH); it.model("search") { SearchModel() }.query = "Pacino" }, settle = 3_500)
            shot("mylist", setup = { it.tab(Tab.MYLIST) })
            val horror = runBlocking { Services.catalog.genres() }.firstOrNull { it.name == "Horror" }
            val netflix = runBlocking { Services.catalog.services() }.firstOrNull { it.name == "Netflix" }
            horror?.let { g -> shot("hub-genre", setup = { it.tab(Tab.BROWSE); it.open(Route.HubPage(Hub.genre(g))) }) }
            shot("hub-movies", setup = { it.tab(Tab.BROWSE); it.open(Route.HubPage(Hub.movies)) })
            netflix?.let { n -> shot("hub-service", setup = { it.tab(Tab.BROWSE); it.open(Route.HubPage(Hub.service(n))) }, act = { _, sh -> scroll(sh, "hub:${Hub.service(n)}", 2) }) }
            shot("details", setup = { sh -> catalog.myList.value.firstOrNull()?.let { sh.openTitle(it) } })
            // The seasonal hub on today (31 Nights of Horror through October): Home's hub row, its page, its rows.
            val season = runBlocking { Services.catalog.hubs() }.firstOrNull()
            shot("home-hub", settle = 4_000, act = { _, sh -> scroll(sh, "home", 4) })
            season?.let { t ->
                val key = "hub:${Hub.season(t)}"
                shot("hub-season", settle = 4_000, setup = { it.tab(Tab.BROWSE); it.open(Route.HubPage(Hub.season(t))) })
                shot("hub-season-rows", settle = 4_000, setup = { it.tab(Tab.BROWSE); it.open(Route.HubPage(Hub.season(t))) },
                    act = { _, sh -> scroll(sh, key, 3) })
                shot("hub-season-more", settle = 6_000, setup = { it.tab(Tab.BROWSE); it.open(Route.HubPage(Hub.season(t))) },
                    act = { _, sh -> runBlocking { sh.model<RowsModel>(key) { error("no model $key") }.let { m -> m.list.scrollToItem(m.slots.size) } } })
            }
            parityBrowse(::shot, pages)
            // The same title page wheeled down to More Like This (its posters whole).
            shot("details-more", settle = 3_500, setup = { sh -> catalog.myList.value.firstOrNull()?.let { sh.openTitle(it) } }, act = { sc, _ ->
                val mid = Offset(s.w / 2f, s.h / 2f)
                repeat(12) { sc.sendPointerEvent(PointerEventType.Scroll, mid, scrollDelta = Offset(0f, 1f)); frames(sc, 80) }
            })
        }
        System.exit(0)
    }

    /**
     * The whole program offscreen, step by step, on a fresh data folder: the intro, the first-run setup, Who's Watching
     * (a second profile added), Home, a title page, then Play: Dream Player's curtain (a silent, windowless mpv; with
     * no debrid account linked it ends on the curtain's "Connect a Debrid Service").
     */
    private fun flow(out: File) {
        val w = 1280; val h = 720
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val shell = ShellState(AppServices.catalog, scope)
        var played: com.dreamos.desktop.core.playback.PlayTarget? = null
        var trailer: Pair<com.dreamos.desktop.core.trailer.TrailerPlayback, com.dreamos.desktop.core.playback.PlayTarget>? = null
        shell.launcher = object : PlayerLauncher {
            override fun play(target: com.dreamos.desktop.core.playback.PlayTarget, startMs: Long) { played = target }
            override fun trailer(playback: com.dreamos.desktop.core.trailer.TrailerPlayback, target: com.dreamos.desktop.core.playback.PlayTarget) { trailer = playback to target }
        }
        fun scene(name: String, start: Phase, intro: Boolean, ms: Long, act: () -> Unit = {}) {
            val sc = ImageComposeScene(w, h, Density(1f)) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    DreamTheme(Metrics(maxWidth.value, maxHeight.value)) { AppRoot(shell, null, intro = intro, start = start) }
                }
            }
            frames(sc, ms)
            act()
            frames(sc, 1_500)
            File(out, "flow-$name.png").writeBytes(sc.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            sc.close()
            println("snap flow-$name")
        }
        println("flow: phase at launch ${startPhase()}")
        scene("1-intro", startPhase(), intro = true, ms = 900)
        scene("2-setup", startPhase(), intro = false, ms = 2_500)
        // Setup finished by hand here (its own steps are win-pages' shots), and a second profile so Who's Watching asks.
        runBlocking { Services.profiles.markSetUp(); Services.profiles.add("Guest") }
        println("flow: phase after setup ${startPhase()}")
        scene("3-who", startPhase(), intro = false, ms = 2_000)
        runBlocking { Services.profiles.select(com.dreamos.desktop.core.model.Profile.FIRST) }
        scene("4-home", Phase.APP, intro = false, ms = 7_000)
        val pick = runBlocking { Services.search.trending() }.first { !it.isShow }
        shell.openTitle(pick)
        scene("5-title", Phase.APP, intro = false, ms = 5_000)
        // Play from the title page's way out (the same call its Play button makes).
        PagesBridge(shell).play(com.dreamos.desktop.settings.kit.PlayRequest(com.dreamos.desktop.settings.kit.TitleRef(pick.id, false, pick.name)))
        val until = System.currentTimeMillis() + 15_000
        while (played == null && System.currentTimeMillis() < until) Thread.sleep(100)
        val target = played ?: error("Play never reached the player")
        println("flow: Play -> ${target.label} imdb=${target.imdb}")
        val playback = AppServices.playback ?: error("Dream Core didn't start")
        curtain(out, playback, playback.pending(target), false, listOf("6-curtain" to 700L, "7-curtain-answer" to 12_000L), w, h)
        // The title page's Trailer (the same call its button makes): Dream Player's trailer mode on TrailerPlayback.
        PagesBridge(shell).playTrailer(com.dreamos.desktop.settings.kit.TitleRef(pick.id, false, pick.name), "")
        val until2 = System.currentTimeMillis() + 15_000
        while (trailer == null && System.currentTimeMillis() < until2) Thread.sleep(100)
        val (tp, tt) = trailer ?: error("Trailer never reached the player")
        println("flow: Trailer -> ${tt.label}")
        curtain(out, tp, tp.pending(tt), true, listOf("8-trailer-curtain" to 300L, "9-trailer-playing" to 10_000L), w, h)
    }

    private fun com.dreamos.desktop.core.playback.Playback.pending(t: com.dreamos.desktop.core.playback.PlayTarget) =
        com.dreamos.desktop.core.playback.PlayRequest(t, com.dreamos.desktop.core.playback.Stream("", com.dreamos.desktop.core.playback.DebridServiceId.REAL_DEBRID, ""), 0)

    /** Dream Player's own screen for [target], offscreen, as WindowPlayer would open it over the window. */
    private fun curtain(out: File, api: com.dreamos.desktop.core.playback.PlaybackApi, first: com.dreamos.desktop.core.playback.PlayRequest,
                        trailer: Boolean, shots: List<Pair<String, Long>>, w: Int, h: Int) {
        val langs = AppServices.playback?.core?.langs
        if (System.getProperty("dream.mpv.dir") == null) {
            val dev = System.getenv("DREAM_MPV_DIR") ?: listOf("../third_party/mpv", "../../dream-os-windows-wt-player/third_party/mpv")
                .map { File(System.getProperty("user.dir"), it) }.firstOrNull { File(it, "libmpv-2.dll").isFile }?.path
            dev?.let { System.setProperty("dream.mpv.dir", it) }
        }
        fun ui(block: () -> Unit) = javax.swing.SwingUtilities.invokeAndWait(block)
        lateinit var session: com.dreamos.player.session.PlayerSession
        lateinit var engine: com.dreamos.player.engine.MpvEngine
        val stage = object : com.dreamos.player.ui.StageControl {
            override val fullScreen = false
            override val mini = false
            override fun toggleFullScreen() {}
            override fun enterMini() {}
            override fun expand() {}
            override fun dragMini(dx: Int, dy: Int) {}
        }
        ui {
            engine = com.dreamos.player.engine.MpvEngine(null) // headless: shows and plays nothing, no sound
            session = com.dreamos.player.session.PlayerSession(first, api, com.dreamos.player.core.CorePlayerServices(langs),
                object : com.dreamos.player.api.PlayerEvents {}, engine, trailer = trailer, onClose = {})
        }
        for ((name, wait) in shots) {
            Thread.sleep(wait)
            lateinit var bytes: ByteArray
            ui {
                val sc = ImageComposeScene(w, h, Density(1f)) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().then(Modifier)) {
                        com.dreamos.player.ui.PlayerScreen(session, stage)
                    }
                }
                var t = 0L
                repeat(60) { sc.render(t); t += 16_000_000L }
                bytes = sc.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
                sc.close()
            }
            File(out, "flow-$name.png").writeBytes(bytes)
            println("snap flow-$name curtain=${session.curtain} firstFrame=${session.engineState.firstFrame}")
        }
        ui { session.close() }
        engine.destroy()
    }

    private suspend fun seed() {
        val trending = Services.search.trending()
        trending.drop(8).take(6).forEach { Services.myList.toggle(it) }
        for ((i, t) in trending.filter { !it.isShow }.take(4).withIndex()) {
            val dur = 6_000_000L
            Services.history.report(PlayRequest(t), dur * (2 + i) / 10, dur, 180_000L, exact = true)
        }
    }

    /**
     * A left click on the bar's Search button. Compose's clickable asks for focus on every press, which is where a
     * corrupted focus tree threw "ActiveParent with no focused child" in the app (a raw error dialog).
     */
    private fun probe(sc: ImageComposeScene, m: Metrics, density: Float) {
        val at = Offset(155f * m.s * density, 32f * m.s * density)
        sc.sendPointerEvent(PointerEventType.Move, at)
        sc.sendPointerEvent(PointerEventType.Press, at, button = androidx.compose.ui.input.pointer.PointerButton.Primary)
        sc.sendPointerEvent(PointerEventType.Release, at, button = androidx.compose.ui.input.pointer.PointerButton.Primary)
        frames(sc, 300)
        println("probe: a click after the action went through")
    }

    /** The log file on a throwaway folder: a line from core's Log, a stack trace, a key masked, 16 MB rolled to 3 files. */
    private fun logCheck(tmp: File) {
        val dir = File(tmp, "logs")
        System.setProperty("dream.log.dir", dir.path)
        AppLog.install()
        com.dreamos.desktop.core.util.Log.i("Check", "a line from core with api_key=SECRET123 in it")
        AppLog.error("a test stack trace", IllegalArgumentException("ActiveParent with no focused child"))
        // Crash's handler: an error on another thread, and a failed focus request, both with their stacks.
        Crash.installThreadHandler()
        Thread({ throw IllegalStateException("probe from a worker") }, "dream-probe").apply { start(); join() }
        com.dreamos.desktop.ui.kit.Focus.move(object : androidx.compose.ui.focus.FocusManager {
            override fun clearFocus(force: Boolean) {}
            override fun moveFocus(focusDirection: androidx.compose.ui.focus.FocusDirection): Boolean = throw IllegalStateException("ActiveParent with no focused child")
        }, androidx.compose.ui.focus.FocusDirection.Down, "probe")
        val text = File(dir, "dream.log").readText()
        System.out.println("applog: core line ${text.contains("DreamOS/Check: a line from core")}, key masked ${!text.contains("SECRET123")}, " +
            "stack ${text.contains("at com.dreamos.desktop.shell.Snap.logCheck")}, " +
            "thread error ${text.contains("uncaught on thread dream-probe") && text.contains("IllegalStateException: probe from a worker")}, " +
            "focus error ${text.contains("DreamOS/Focus: probe: ActiveParent") && text.contains("at com.dreamos.desktop.ui.kit.Focus.move")}")
        val pad = "x".repeat(1000)
        val quiet = System.out
        repeat(16_000) { System.err.println(pad) }
        quiet.println("applog: files ${dir.listFiles()!!.sortedBy { it.name }.joinToString { "${it.name} ${it.length() / 1024} KB" }}")
    }

    /** The card shown when Dream OS can't carry on (Crash), at its own size. */
    private fun crashCard(out: File) {
        val scene = ImageComposeScene(560, 300, Density(1f)) { Crash.Preview() }
        frames(scene, 400)
        File(out, "crash-card.png").writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("snap crash-card")
    }

    private suspend fun seedMore() {
        val trending = Services.search.trending()
        for ((i, t) in trending.filter { !it.isShow }.drop(4).take(14).withIndex()) {
            val dur = 6_000_000L
            Services.history.report(PlayRequest(t), dur * (2 + i % 6) / 10, dur, 180_000L, exact = true)
        }
    }

    /**
     * The Browse area's parity shots (docs/port/parity-browse.md, `DREAM_SNAP_PAGES=par-...`): Search before typing
     * (Recent), a suggestion, a genre, a person and no match; My List's sections with a saved title part way through, A-Z
     * and TV Shows only; a Movies chip under its own hero; a service page's end with the JustWatch credit.
     */
    private fun parityBrowse(
        shot: (String, Long, (ShellState) -> Unit, (ImageComposeScene, ShellState) -> Unit) -> Unit, pages: Set<String>?,
    ) {
        if (pages == null || pages.none { it.startsWith("par-") }) return
        runBlocking {
            com.dreamos.desktop.core.content.RecentSearches.add("Brad Pitt")
            com.dreamos.desktop.core.content.RecentSearches.add("horror")
            Services.history.continueWatching().firstOrNull()?.title?.let { if (!Services.myList.contains(it.key)) Services.myList.toggle(it) }
        }
        val none: (ImageComposeScene, ShellState) -> Unit = { _, _ -> }
        fun search(name: String, q: String, settle: Long = 4_000) =
            shot(name, settle, { it.tab(Tab.SEARCH); it.model("search") { SearchModel() }.query = q }, none)
        shot("par-search-recent", 3_000, { it.tab(Tab.SEARCH) }, none)
        search("par-search-suggest", "hor", 3_000)
        search("par-search-genre", "horror", 7_000)
        search("par-search-person", "brad pitt")
        search("par-search-none", "qzxvqzx")
        shot("par-mylist", 3_000, { com.dreamos.desktop.core.content.MyListView.save("all", "recent"); it.tab(Tab.MYLIST) }, none)
        shot("par-mylist-az", 3_000, { com.dreamos.desktop.core.content.MyListView.save("all", "az"); it.tab(Tab.MYLIST) }, none)
        shot("par-mylist-tv", 3_000, { com.dreamos.desktop.core.content.MyListView.save("tv", "recent"); it.tab(Tab.MYLIST) }, none)
        com.dreamos.desktop.core.content.MyListView.save("all", "recent")
        // Movies with the Comedy chip picked: its rows under Comedy's own hero.
        val comedy = runBlocking { AppServices.catalog.chips(Hub.movies) }.firstOrNull { it.name == "Comedy" }
        comedy?.let { g ->
            shot("par-hub-chip", 6_000, { sh ->
                sh.tab(Tab.BROWSE); sh.open(Route.HubPage(Hub.movies))
                sh.model("chips:${Hub.movies}") { androidx.compose.runtime.mutableStateOf(runBlocking { AppServices.catalog.chips(Hub.movies) }) }
                sh.model("chip:${Hub.movies}") { androidx.compose.runtime.mutableStateOf(g.id) }
                sh.rows("hub:${Hub.movies}", { AppServices.catalog.hub(Hub.genre(g)) }, { AppServices.catalog.hubHero(Hub.movies) })
            }, none)
        }
        val netflix = runBlocking { Services.catalog.services() }.firstOrNull { it.name == "Netflix" } ?: return
        shot("par-hub-service-end", 8_000, { it.tab(Tab.BROWSE); it.open(Route.HubPage(Hub.service(netflix))) }) { sc, sh ->
            scroll(sh, "hub:${Hub.service(netflix)}", 999); frames(sc, 1_500); scroll(sh, "hub:${Hub.service(netflix)}", 999)
        }
    }

    private fun scroll(sh: ShellState, key: String, item: Int) = runBlocking {
        sh.model<RowsModel>(key) { error("no model $key") }.list.scrollToItem(item)
    }

    /** The middle of the options menu's row [i] when it opened at [at] (MenuLayer: 8 padding, 48 title, 50 a row). */
    private fun menuRow(m: Metrics, at: Offset, density: Float, i: Int): Offset {
        val h = m.u(64).value + m.u(50).value * 3
        val top = (at.y / density).coerceIn(m.u(12).value, m.heightDp - h - m.u(12).value)
        val left = (at.x / density).coerceIn(m.u(12).value, m.widthDp - m.u(300).value - m.u(12).value)
        return Offset((left + m.u(150).value) * density, (top + m.u(56).value + m.u(50).value * i + m.u(25).value) * density)
    }

    /** The middle of the picker button headed [heading], when it is on screen. */
    private fun button(heading: String): Offset? = com.dreamos.desktop.ui.kit.PickerSpots.at[heading]?.center

    /** The middle of the open panel's row reading [label]; null once the panel has closed. */
    private fun pickerRow(label: String): Offset? = com.dreamos.desktop.ui.kit.PickerSpots.at["row:$label"]?.center

    private fun frames(scene: ImageComposeScene, ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            Snapshot.sendApplyNotifications()
            scene.render(System.nanoTime())
            Thread.sleep(40)
        }
    }

    private fun key(scene: ImageComposeScene, k: Key) {
        for (t in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
            scene.sendKeyEvent(KeyEvent(k, t))
            scene.render(System.nanoTime())
        }
    }
}
