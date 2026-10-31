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
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.dreamos.desktop.core.service.Services
import com.dreamos.desktop.ui.kit.DreamTheme
import com.dreamos.desktop.ui.kit.Focus
import com.dreamos.desktop.ui.kit.Metrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * The top bar's avatar menu, the bell and a card's options menu, driven offscreen with the mouse and the keyboard:
 * each opens, each action runs, Esc and a click outside close, and focus is back on the button after.
 * `DREAM_SNAP=<dir> DREAM_SNAP_PAGES=menus ./gradlew :app:run` (on Snap's throwaway data folder). Exits non-zero on a
 * failure.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
internal object MenuCheck {
    private var failed = 0
    private const val W = 1280
    private const val H = 720

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (!ok) failed++
        println("menu-check ${if (ok) "PASS" else "FAIL"}: $name${if (detail.isNotEmpty()) " ($detail)" else ""}")
    }

    /** [exits]: how many times Exit Dream OS asked the app to close (the stub stands in for Main's quit). */
    private class Run(val scene: ImageComposeScene, val shell: ShellState, val m: Metrics) { var exits = 0 }

    /** DREAM_MENU_ONLY=<prefix>: only the scenes whose name starts with it (and no bar hover pass). */
    private val only = System.getenv("DREAM_MENU_ONLY")?.takeIf { it.isNotBlank() }

    private fun scene(out: File, name: String, body: Run.() -> Unit) {
        if (only != null && !name.startsWith(only)) return
        // The shell's coroutines on the thread that renders, as in the app (its window's UI thread): page models
        // filled from another thread raced the lazy rows' measure offscreen (IndexOutOfBounds in LazyList).
        val shell = ShellState(AppServices.catalog, CoroutineScope(SupervisorJob() + onRender))
        val sc = ImageComposeScene(W, H, Density(1f)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                DreamTheme(Metrics(maxWidth.value, maxHeight.value)) { DreamApp(shell, null, intro = false) }
            }
        }
        val run = Run(sc, shell, Metrics(W.toFloat(), H.toFloat()))
        shell.exit = { run.exits++ }
        frames(sc, 3_000)
        try {
            run.body()
        } catch (e: Throwable) {
            check("$name ran without an exception", false, "${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        }
        frames(sc, 600)
        File(out, "menus-$name.png").writeBytes(sc.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        sc.close()
    }

    // Top bar positions (TopBar: the row's end padding is gutter - 6, the avatar 40 wide, 12 before it, the bell 40).
    private val Run.avatar get() = Offset(W - m.u(42 + 20).value, m.barH.value / 2)
    private val Run.bell get() = Offset(W - m.u(42 + 40 + 12 + 20).value, m.barH.value / 2)
    private val Run.outside get() = Offset(W * 0.3f, H * 0.6f)

    /**
     * Row [i] of the avatar's menu: it opens under the avatar, its right edge on the avatar's (MenuLayer's sizes); Exit
     * Dream OS sits under its line.
     */
    private fun Run.avatarRow(i: Int): Offset {
        val left = W - m.u(42).value - m.u(300).value
        val top = m.barH.value / 2 + m.u(20).value + 8f
        val line = if (avatarItems[i] == EXIT) m.u(com.dreamos.desktop.ui.kit.MenuSpec.BREAK_H).value else 0f
        return Offset(left + m.u(150).value, top + m.u(56).value + m.u(50).value * i + line + m.u(25).value)
    }

    private fun Run.click(at: Offset, button: PointerButton = PointerButton.Primary) {
        scene.sendPointerEvent(PointerEventType.Move, at)
        frames(scene, 120)
        scene.sendPointerEvent(PointerEventType.Press, at, button = button)
        // A real click: the button is down for a few frames (a window draws between the press and the release).
        frames(scene, 90)
        scene.sendPointerEvent(PointerEventType.Release, at, button = button)
        frames(scene, 500)
    }

    private fun Run.key(k: Key, shift: Boolean = false) {
        for (t in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
            scene.sendKeyEvent(KeyEvent(k, t, isShiftPressed = shift))
            scene.render(System.nanoTime())
        }
        frames(scene, 300)
    }

    private val Run.items get() = shell.menu.spec?.items?.map { it.first }
    private val avatarItems = listOf("Switch Profile", "Settings", "Notifications", EXIT)

    fun run(out: File): Int {
        // DREAM_SNAP_PAGES=menus-bar: only the bar's hover check.
        if (System.getenv("DREAM_SNAP_PAGES")?.contains("menus-bar") == true) { barHover(out); return failed }
        val me = Services.profiles.current.value.name
        scene(out, "avatar-open") {
            click(avatar)
            check("avatar click opens its menu", items == avatarItems, "items $items")
            check("avatar menu is titled with the profile", shell.menu.spec?.title == me, "title ${shell.menu.spec?.title}")
            check("Exit Dream OS is last, under a line", items?.lastOrNull() == EXIT && shell.menu.spec?.breakBefore == setOf(EXIT), "items $items")
            check("opening the menu doesn't exit", exits == 0, "exits $exits")
        }
        for ((i, label) in avatarItems.withIndex()) scene(out, "avatar-pick-$i") {
            click(avatar)
            click(avatarRow(i))
            if (label == EXIT) {
                check("avatar menu: clicking Exit Dream OS calls the exit hook once, the menu closes", exits == 1 && shell.menu.spec == null, "exits $exits, menu $items")
                return@scene
            }
            val want: Route = when (i) { 0 -> Route.Profiles; 1 -> Route.Settings(); else -> Route.Notices }
            check("avatar menu: clicking $label opens it", shell.route == want && shell.menu.spec == null && exits == 0, "route ${shell.route}, menu ${items}, exits $exits")
        }
        scene(out, "avatar-esc") {
            click(avatar)
            key(Key.Escape)
            check("Esc closes the avatar menu and stays on the page", shell.menu.spec == null && shell.route == Route.TabRoot(Tab.HOME), "route ${shell.route}")
            key(Key.Enter)
            check("focus is back on the avatar after Esc (Enter opens it again)", items == avatarItems, "items $items")
            key(Key.Escape)
        }
        scene(out, "avatar-outside") {
            click(avatar)
            click(outside)
            check("a click outside closes the avatar menu, nothing under it opens", shell.menu.spec == null && shell.route == Route.TabRoot(Tab.HOME), "route ${shell.route}, menu $items")
        }
        scene(out, "avatar-keys") {
            // Shift+Tab from the page's root: the bar is the last thing on the page, the avatar its last control.
            key(Key.Tab, shift = true)
            key(Key.Enter)
            check("keyboard: Shift+Tab then Enter opens the avatar menu", items == avatarItems, "items $items")
            key(Key.DirectionDown)
            key(Key.Enter)
            check("keyboard: Down then Enter picks Settings", shell.route == Route.Settings() && shell.menu.spec == null, "route ${shell.route}")
        }
        scene(out, "avatar-exit-keys") {
            // Focus on the avatar (Esc puts it back there), then only keys: Enter opens, Down three times, Enter.
            click(avatar)
            key(Key.Escape)
            key(Key.Enter)
            check("keyboard: Enter on the avatar opens its menu", items == avatarItems, "items $items")
            key(Key.DirectionDown); key(Key.DirectionDown); key(Key.DirectionDown)
            key(Key.Enter)
            check("keyboard: Down three times then Enter picks Exit Dream OS (the exit hook runs once)",
                exits == 1 && shell.menu.spec == null && shell.route == Route.TabRoot(Tab.HOME), "exits $exits, menu $items, route ${shell.route}")
        }
        scene(out, "avatar-exit-shown") {
            // The menu left open with Exit Dream OS lit, for the screenshot.
            click(avatar)
            key(Key.DirectionDown); key(Key.DirectionDown); key(Key.DirectionDown)
            check("keyboard: focus reaches Exit Dream OS without running it", exits == 0 && items == avatarItems, "exits $exits")
        }
        escScenes(out)
        scene(out, "bell") {
            click(bell)
            check("bell click opens Notifications", shell.route == Route.Notices, "route ${shell.route}")
        }
        scene(out, "bell-keys") {
            key(Key.Tab, shift = true)
            key(Key.Tab, shift = true)
            key(Key.Enter)
            check("keyboard: Shift+Tab twice then Enter opens Notifications", shell.route == Route.Notices, "route ${shell.route}")
        }
        // A card's options: Continue Watching's first card (Snap seeds it), the right click and the Menu key.
        val card = Offset(
            m0.gutter.value + m0.landW.value / 2,
            m0.heightDp - m0.u(48).value - m0.liftRoom.value - m0.landW.value * 9f / 32f,
        )
        scene(out, "card-right-click") {
            click(card, PointerButton.Secondary)
            val n = items?.size ?: 0
            check("right click on a card opens its menu", n >= 3, "items $items")
            click(outside)
            check("a click outside closes the card menu", shell.menu.spec == null, "menu $items")
        }
        scene(out, "card-pick") {
            click(card, PointerButton.Secondary)
            // Row 1 (Details) of a menu opened at the card (MenuLayer: 300 wide, 56 to the first row, 50 a row).
            val h = m.u(64).value + m.u(50).value * 3
            val top = card.y.coerceIn(m.u(12).value, H - h - m.u(12).value)
            val left = card.x.coerceIn(m.u(12).value, W - m.u(300).value - m.u(12).value)
            click(Offset(left + m.u(150).value, top + m.u(56).value + m.u(50).value + m.u(25).value))
            check("card menu: clicking Details opens the title", shell.route is Route.Details && shell.menu.spec == null, "route ${shell.route}")
        }
        scene(out, "card-menu-key") {
            key(Key.DirectionDown); key(Key.DirectionDown)
            key(Key.Menu)
            check("the Menu key on a focused card opens its menu", (items?.size ?: 0) >= 3, "items $items")
            key(Key.Escape)
            check("Esc closes the card menu", shell.menu.spec == null)
            key(Key.Menu)
            check("focus is back on the card after Esc (the Menu key opens it again)", (items?.size ?: 0) >= 3, "items $items")
            key(Key.Escape)
        }
        if (only == null) barHover(out)
        println("menu-check: ${if (failed == 0) "all passed" else "$failed failed"}")
        return failed
    }

    /**
     * Esc steps back one level (owner, 2026-10-30): a title page opened from Browse, Browse's root, Home's root, then
     * "Exit Dream OS?" with Cancel focused; Esc and Cancel close it (focus back where it was), Exit runs the exit hook.
     * A settings page over its group goes back to the group first, even with focus parked on the shell's root.
     */
    private fun escScenes(out: File) {
        val title = Targets.titleOf(1400940, false, "Clayface")
        scene(out, "esc-chain") {
            shell.tab(Tab.BROWSE); frames(scene, 800)
            shell.open(Route.Details(title)); frames(scene, 2_000)
            key(Key.Escape)
            check("Esc on a title page opened from Browse goes back to Browse", shell.route == Route.TabRoot(Tab.BROWSE) && !shell.askExit, "route ${shell.route}")
            key(Key.Escape)
            check("Esc on Browse's root goes to Home", shell.route == Route.TabRoot(Tab.HOME) && !shell.askExit, "route ${shell.route}")
            key(Key.Escape)
            check("Esc on Home's root asks Exit Dream OS?", shell.askExit && exits == 0, "ask ${shell.askExit}, exits $exits")
            key(Key.Escape)
            check("Esc closes the question and stays on Home", !shell.askExit && shell.route == Route.TabRoot(Tab.HOME) && exits == 0, "ask ${shell.askExit}")
            key(Key.Escape)
            key(Key.Enter)
            check("Enter on the question's focused Cancel closes it, nothing exits", !shell.askExit && exits == 0, "ask ${shell.askExit}, exits $exits")
            key(Key.Escape)
            key(Key.DirectionRight)
            key(Key.Enter)
            check("Right then Enter picks Exit: the exit hook runs once", exits == 1 && !shell.askExit, "exits $exits, ask ${shell.askExit}")
        }
        scene(out, "esc-prompt") {
            key(Key.Escape)
            check("Esc on Home's root shows the question (screenshot)", shell.askExit, "ask ${shell.askExit}")
        }
        scene(out, "esc-prompt-enter") {
            key(Key.Escape)
            frames(scene, 600)
            key(Key.Enter)
            check("Cancel has focus as soon as the question shows (Enter closes it)", !shell.askExit && exits == 0, "ask ${shell.askExit}, exits $exits")
        }
        scene(out, "esc-prompt-click") {
            key(Key.Escape)
            click(outside.copy(y = H * 0.9f))
            check("a click outside the question cancels it", !shell.askExit && exits == 0, "ask ${shell.askExit}, exits $exits")
        }
        scene(out, "esc-focus-back") {
            // Keyboard on Home: Down lands on the hero's Play, Right on More Info. After Esc and Cancel, Enter on what
            // has focus opens the title (focus came back to More Info, not the shell's root).
            key(Key.DirectionDown)
            key(Key.DirectionRight)
            key(Key.Escape)
            check("the question opens over the focused page", shell.askExit, "ask ${shell.askExit}")
            key(Key.Escape)
            key(Key.Enter)
            check("after Cancel focus is back on the control it left (Enter opens the title)", shell.route is Route.Details && exits == 0, "route ${shell.route}")
        }
        scene(out, "esc-settings") {
            shell.open(Route.Settings(com.dreamos.desktop.settings.SettingsGroup.Accounts, com.dreamos.desktop.settings.SettingsRoute.Accounts))
            frames(scene, 1_500)
            Focus.request(Focus.park, "menu-check: park on the root")
            frames(scene, 300)
            key(Key.Escape)
            check("Esc on Settings › Accounts (focus on the shell's root) goes back to Settings, not out", shell.route is Route.Settings && !shell.askExit, "route ${shell.route}")
            key(Key.Escape)
            check("Esc on Settings goes back to Home", shell.route == Route.TabRoot(Tab.HOME) && !shell.askExit, "route ${shell.route}")
        }
    }

    /**
     * The owner's flicker at the top while hovering the tabs: the pointer rests on each control of the bar (and the
     * gaps between them), and consecutive frames of the top 120 px are printed with where they changed (only the lit
     * control's own moving rainbow should); then a mouse path along the tabs, checked over the whole window.
     */
    private fun barHover(out: File) {
        // The hero's slides held still (Windows' "show animations" off does that), so any change under the bar is the
        // page reacting to the pointer.
        val was = Motion.reduced
        Motion.reduced = true
        try { barHoverScene(out) } finally { Motion.reduced = was }
    }

    private fun barHoverScene(out: File) = scene(out, "bar-hover") {
        val y = m.barH.value / 2
        // At 1280x720 (scale 1): the logo, Search (icon only), Home, Browse and My List, the gaps between them, the bell
        // and the avatar.
        val stops = listOf(
            "logo" to 75f, "search" to 155f, "gap-search-home" to 176f, "home" to 228f, "gap-home-browse" to 272f,
            "browse" to 322f, "mylist" to 422f, "bar-middle" to W / 2f, "bell" to bell.x, "avatar" to avatar.x,
        )
        // Hero slides change what is under the clear bar on their own: keep the pointer's path quick and judge each
        // stop by where the change is, not just that there is one.
        for ((name, x) in stops) {
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, y))
            frames(scene, 500)
            var prev = top(scene)
            val boxes = ArrayList<String>()
            repeat(12) {
                Thread.sleep(40)
                Snapshot.sendApplyNotifications()
                val cur = top(scene)
                diffBox(prev, cur)?.let { boxes += it }
                prev = cur
            }
            println("bar-hover $name at x=${x.toInt()}: ${if (boxes.isEmpty()) "still" else boxes.distinct().take(6).joinToString(" ")}")
        }
        // A real mouse path along the tabs, logo to My List and back, a frame per step, after the pointer has come up
        // from the hero and settled on Home. Nothing below the bar may change, except a whole slide moving on (the
        // hero runs its slides while the pointer is on the bar): a local change there is the hero reacting to the
        // pointer through the bar's gaps (its arrow blinking, the owner's flicker).
        scene.sendPointerEvent(PointerEventType.Move, Offset(228f, 150f)); frames(scene, 400)
        for (yy in 144 downTo 34 step 10) { scene.sendPointerEvent(PointerEventType.Move, Offset(228f, yy.toFloat())); frames(scene, 16) }
        frames(scene, 600)
        val path = (228..470 step 4).map { Offset(it.toFloat(), 34f) } + (470 downTo 40 step 4).map { Offset(it.toFloat(), 34f) }
        var before = top(scene, H)
        val odd = ArrayList<String>()
        var slides = 0
        for (p in path) {
            scene.sendPointerEvent(PointerEventType.Move, p)
            Thread.sleep(16)
            while (true) (queued.poll() ?: break).run()
            Snapshot.sendApplyNotifications()
            val cur = top(scene, H)
            diffBox(before, cur)?.let { b ->
                val (x0, _, x1, y1) = b.split(",", "-").map { it.toInt() }
                when {
                    y1 <= 74 -> {}
                    x1 - x0 > W * 0.9f -> slides++
                    else -> odd += "at ${p.x.toInt()},${p.y.toInt()}: $b"
                }
            }
            before = cur
        }
        check("moving along the bar changes nothing under it (the hero ignores the pointer on the bar)", odd.isEmpty(),
            "${path.size} steps, $slides slide frames${if (odd.isEmpty()) "" else "; " + odd.take(8).joinToString("; ")}")
        // Hover on, hover off: what changed between the bar at rest and the bar with one control lit.
        for ((name, x) in stops) {
            scene.sendPointerEvent(PointerEventType.Move, Offset(W / 2f, y)); frames(scene, 500)
            val rest = top(scene)
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, y)); frames(scene, 500)
            println("bar-hover lit $name: ${diffBox(rest, top(scene)) ?: "no change"}")
        }
    }

    private const val TOP = 120

    /** The frame's top [rows] pixel rows, one Int a pixel (channel order doesn't matter for a diff). */
    private fun top(scene: ImageComposeScene, rows: Int = TOP): IntArray {
        val img = scene.render(System.nanoTime())
        val bmp = org.jetbrains.skia.Bitmap.makeFromImage(img)
        val bytes = bmp.readPixels(bmp.imageInfo, bmp.rowBytes, 0, 0) ?: ByteArray(0)
        bmp.close()
        val px = IntArray(W * rows)
        val rb = W * 4
        for (yy in 0 until rows) for (xx in 0 until W) {
            val o = yy * rb + xx * 4
            px[yy * W + xx] = (bytes[o].toInt() and 255 shl 16) or (bytes[o + 1].toInt() and 255 shl 8) or (bytes[o + 2].toInt() and 255)
        }
        return px
    }

    /** Where two frames differ (more than a rounding step), as "x0,y0-x1,y1", or null when they match. */
    private fun diffBox(a: IntArray, b: IntArray): String? {
        var x0 = W; var y0 = a.size / W; var x1 = -1; var y1 = -1
        for (i in a.indices) {
            val p = a[i]; val q = b[i]
            if (p == q) continue
            val d = maxOf(Math.abs((p shr 16 and 255) - (q shr 16 and 255)), Math.abs((p shr 8 and 255) - (q shr 8 and 255)), Math.abs((p and 255) - (q and 255)))
            if (d <= 2) continue
            val xx = i % W; val yy = i / W
            if (xx < x0) x0 = xx; if (xx > x1) x1 = xx; if (yy < y0) y0 = yy; if (yy > y1) y1 = yy
        }
        return if (x1 < 0) null else "$x0,$y0-$x1,$y1"
    }

    private val m0 = Metrics(W.toFloat(), H.toFloat())

    private val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
    private val onRender = object : kotlinx.coroutines.CoroutineDispatcher() {
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued.add(block) }
    }

    private fun frames(scene: ImageComposeScene, ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            while (true) (queued.poll() ?: break).run()
            Snapshot.sendApplyNotifications()
            scene.render(System.nanoTime())
            Thread.sleep(30)
        }
    }
}
