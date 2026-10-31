package com.dreamos.desktop.ui.kit

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.*

/**
 * The card menu picked with the mouse, offscreen: a click on an action runs it (the press used to reach the backdrop's
 * "click outside closes" first, the menu left before the release, and the action never ran). A click outside closes.
 */
@OptIn(ExperimentalComposeUiApi::class)
class MenuClickTest {
    private val w = 1280
    private val h = 720
    private var now = 0L

    private fun frames(scene: ImageComposeScene, ms: Long) {
        val end = now + ms * 1_000_000L
        while (now < end) {
            Snapshot.sendApplyNotifications()
            scene.render(now)
            now += 16_000_000L
        }
    }

    private fun click(scene: ImageComposeScene, at: Offset) {
        scene.sendPointerEvent(PointerEventType.Move, at)
        frames(scene, 50)
        scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        // A person's click: the button is down for about a tenth of a second (several frames).
        frames(scene, 120)
        scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
        frames(scene, 100)
    }

    /** The menu opened at (100, 100); three actions that all count, so a click anywhere in the actions' band hits one. */
    private fun open(state: MenuState, ran: IntArray, removes: Boolean) {
        val act = { ran[0]++; Unit }
        val labels = listOf("Play", "Details", "Remove from Continue Watching")
        state.show(MenuSpec("A Title", labels.map { it to act }, Offset(100f, 100f), if (removes) labels.toSet() else emptySet()))
    }

    private fun scene(state: MenuState) = ImageComposeScene(w, h, Density(1f)) {
        DreamTheme(Metrics(w.toFloat(), h.toFloat())) { MenuLayer(state) }
    }

    @Test fun clickingAnActionRunsIt() {
        for (removes in listOf(false, true)) {
            val state = MenuState()
            val ran = intArrayOf(0)
            val scene = scene(state)
            try {
                open(state, ran, removes)
                frames(scene, 300)
                // Menu at (100, 100), 300 wide at this size; its title takes the first ~50 px, the actions 50 px each.
                click(scene, Offset(250f, 100f + 64f + 75f))
                assertEquals(1, ran[0], "the action ran (removes=$removes)")
                assertNull(state.spec, "the menu closed")
            } finally { scene.close() }
        }
    }

    @Test fun clickingOutsideClosesWithoutRunningAnything() {
        val state = MenuState()
        val ran = intArrayOf(0)
        val scene = scene(state)
        try {
            open(state, ran, removes = false)
            frames(scene, 300)
            click(scene, Offset(900f, 600f))
            assertEquals(0, ran[0])
            assertNull(state.spec)
        } finally { scene.close() }
    }
}
