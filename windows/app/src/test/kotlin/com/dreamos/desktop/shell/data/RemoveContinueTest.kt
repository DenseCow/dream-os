package com.dreamos.desktop.shell.data

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.dreamos.desktop.core.catalog.ContentServices
import com.dreamos.desktop.core.content.ProfileStore
import com.dreamos.desktop.core.content.Tmdb
import com.dreamos.desktop.core.content.WatchHistory
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.PlayRequest
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.net.NetResponse
import com.dreamos.desktop.core.platform.Platform
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.shell.RowsModel
import com.dreamos.desktop.shell.ShellState
import com.dreamos.desktop.shell.SlotState
import com.dreamos.desktop.ui.kit.DreamTheme
import com.dreamos.desktop.ui.kit.MenuLayer
import com.dreamos.desktop.ui.kit.Metrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.file.Files
import kotlin.test.*

/**
 * Remove from Continue Watching as the owner does it: the card's menu opened, the action clicked with the mouse, then
 * Home's Continue Watching row looked at, at once and after core's change event. A throwaway data folder (never the
 * viewer's), TMDB faked; the menu offscreen.
 */
@OptIn(ExperimentalComposeUiApi::class)
class RemoveContinueTest {
    private lateinit var catalog: CoreCatalog
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val kept = movie(1)
    private val gone = movie(2)

    @BeforeTest fun setUp() {
        val root = Files.createTempDirectory("dream-cwremove")
        Platform.current = Platform.forTests(root)
        DreamStore.use(DreamStore(root.resolve("settings")))
        DreamStore.currentProfile = DreamStore.FIRST_PROFILE
        ProfileStore.init()
        Net.fake = { _, _, _ -> NetResponse(404, "", emptyMap()) }
        Tmdb.setKey("test")
        WatchHistory.clearCache()
        ContentServices.install()
        catalog = CoreCatalog { null }
        WatchHistory.report(PlayRequest(kept), 30 * 60_000L, 100 * 60_000L, 120_000, exact = true)
        WatchHistory.report(PlayRequest(gone), 40 * 60_000L, 100 * 60_000L, 120_000, exact = true)
    }

    @AfterTest fun tearDown() { scope.cancel(); Net.fake = null }

    private fun movie(id: Int) = Title(id, MediaKind.MOVIE, "Movie $id", posterPath = "/p$id.jpg", backdropPath = "/b$id.jpg",
        year = "2020", date = "2020-01-01", votes = 1000, rating = 7.0, genreIds = listOf(18), lang = "en")

    /** Home's Continue Watching row as the page holds it (null: not showing). */
    private fun RowsModel.continueRow(): List<String>? =
        (states["home:continue"] as? SlotState.Shown)?.row?.items?.map { it.title.key }

    private fun waitFor(what: String, ms: Long = 5_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > end) fail("timed out: $what")
            Thread.sleep(20)
        }
    }

    /** Home's rows loaded and following the catalog, as HomePage does while it shows. */
    private fun home(follow: Boolean = true): RowsModel {
        val model = RowsModel(scope, { catalog.home() }, null)
        model.start({ row, _ -> row })
        waitFor("Home's Continue Watching") { model.continueRow()?.toSet() == setOf(kept.key, gone.key) }
        if (follow) followNow(model)
        return model
    }

    private fun followNow(model: RowsModel) {
        scope.launch { model.follow(catalog) }
        Thread.sleep(300) // subscribed
    }

    /** "At once": the next frame or two (the page hears the removal on its own thread). */
    private val AT_ONCE_MS = 100L

    private var now = 0L
    private fun frames(scene: ImageComposeScene, ms: Long) {
        val end = now + ms * 1_000_000L
        while (now < end) {
            Snapshot.sendApplyNotifications()
            scene.render(now)
            now += 16_000_000L
        }
    }

    /** A person's mouse click on the menu (button down about a tenth of a second). */
    private fun click(scene: ImageComposeScene, at: Offset) {
        scene.sendPointerEvent(PointerEventType.Move, at)
        frames(scene, 50)
        scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        frames(scene, 120)
        scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
        frames(scene, 50)
    }

    @Test fun removeFromContinueWatchingThroughTheMenuTakesTheCardOffHome() {
        val model = home()
        val shell = ShellState(catalog, scope)
        val scene = ImageComposeScene(1280, 720, Density(1f)) { DreamTheme(Metrics(1280f, 720f)) { MenuLayer(shell.menu) } }
        try {
            shell.menu(CardItem(gone, progress = 0.4f), continueRow = true).open(Offset(100f, 100f))
            frames(scene, 300)
            val labels = shell.menu.spec!!.items.map { it.first }
            assertEquals("Remove from Continue Watching", labels[2])
            // The menu at (100, 100): its title ~56 px, then 50 px per action; the third action's middle.
            click(scene, Offset(250f, 100f + 56f + 2 * 50f + 25f))
        } finally { scene.close() }

        // At once: the card leaves the row before core has even saved (optimistic).
        waitFor("the card left Home's Continue Watching at once", AT_ONCE_MS) { model.continueRow() == listOf(kept.key) }

        // Core hid it (the Fire Stick's hidden flag) and said so; Home's rows reload and it stays gone.
        waitFor("history entry hidden") { WatchHistory.entry(gone.key)?.hidden == true }
        waitFor("the change event") { catalog.changeCount.value > 0 }
        Thread.sleep(600) // the reload lands
        assertEquals(listOf(kept.key), model.continueRow(), "after the change event")
        assertNull(WatchHistory.playEntry(gone), "a removed title starts fresh")
    }

    @Test fun removingTheLastCardHidesTheRow() {
        WatchHistory.remove(kept.key)
        val model = RowsModel(scope, { catalog.home() }, null)
        model.start({ row, _ -> row })
        waitFor("Home's Continue Watching") { model.continueRow() == listOf(gone.key) }
        followNow(model)
        catalog.removeContinue(gone)
        waitFor("the row leaves with its last card", AT_ONCE_MS) { model.states["home:continue"] is SlotState.Hidden }
        // Through core's change and the reload it stays gone: never a skeleton in its place.
        val end = System.currentTimeMillis() + 1_200
        while (System.currentTimeMillis() < end) {
            assertIs<SlotState.Hidden>(model.states["home:continue"], "stays gone through the reload, no skeleton")
            Thread.onSpinWait()
        }
        assertTrue(catalog.changeCount.value > 0, "the change came and the rows reloaded")
    }

    @Test fun aPageKeptAwayCatchesUpWhenItShowsAgain() {
        // Home showed, then the viewer went to Movies (Home's model kept, not listening) and removed the card there.
        val model = home(follow = false)
        val showing = scope.launch { model.follow(catalog) }
        Thread.sleep(300)
        showing.cancel()
        val before = catalog.changeCount.value
        catalog.removeContinue(gone)
        waitFor("the change event") { catalog.changeCount.value > before }
        assertEquals(setOf(kept.key, gone.key), model.continueRow()?.toSet(), "not showing, so not reloaded yet")
        followNow(model) // back on Home
        waitFor("reloaded on return") { model.continueRow() == listOf(kept.key) }
    }

    @Test fun newEpisodeCardsDontOfferContinueWatchingRemoval() {
        val shell = ShellState(catalog, scope)
        // New Episodes for You: the same wide card, no progress (not on Continue Watching).
        val show = Title(9, MediaKind.TV, "Show 9")
        shell.menu(CardItem(show, season = 2, episode = 1), continueRow = true).open(null)
        val labels = shell.menu.spec!!.items.map { it.first }
        assertFalse("Remove from Continue Watching" in labels, labels.toString())
        assertTrue("Add to My List" in labels)
    }

    @Test fun removeFromMyListLeavesTheMyListRowAtOnce() {
        kotlinx.coroutines.runBlocking { com.dreamos.desktop.core.service.Services.myList.toggle(kept) }
        val model = RowsModel(scope, { catalog.home() }, null)
        model.start({ row, _ -> row })
        waitFor("Home's My List") { (model.states["home:my-list"] as? SlotState.Shown)?.row?.items?.map { it.title.key } == listOf(kept.key) }
        followNow(model)
        catalog.toggleMyList(kept)
        waitFor("the card left at once", AT_ONCE_MS) { model.states["home:my-list"] is SlotState.Hidden }
        waitFor("off the list") { !com.dreamos.desktop.core.service.Services.myList.contains(kept.key) }
    }
}
