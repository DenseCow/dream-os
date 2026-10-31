package com.dreamos.desktop.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.dreamos.desktop.settings.DisplayModePage
import com.dreamos.desktop.settings.FakeServices
import com.dreamos.desktop.settings.LocalPagesServices
import com.dreamos.desktop.settings.SettingsGroup
import com.dreamos.desktop.settings.SettingsPage
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Offscreen checks of the display modes (no window): the bounds math on monitor layouts with mixed scaling and
 * negative coordinates, the saved-prefs migration, F11's toggle, and the Settings rows and page rendered.
 * `DREAM_SNAP=<dir> DREAM_SNAP_PAGES=display ./gradlew :app:run`.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
internal object DisplayCheck {
    private var failed = 0

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (!ok) failed++
        println("display-check ${if (ok) "PASS" else "FAIL"}: $name${if (detail.isNotEmpty()) " ($detail)" else ""}")
    }

    fun run(out: File): Int {
        math()
        migration()
        // The PC's real monitors through the same Win32 calls the app uses (reading only; no window).
        val mons = WinDisplay.monitors()
        println("display-check monitors (physical px, main first): ${mons.joinToString { "monitor ${it.first} work ${it.second}" }}")
        check("monitors read on this PC", mons.isNotEmpty() && mons.all { it.first.w > 0 && it.second.w <= it.first.w })
        settings(out)
        println("display-check: ${if (failed == 0) "all passed" else "$failed failed"}")
        return failed
    }

    private fun math() {
        // A 4K TV at 150% on the left (negative x), a 1080p main monitor at 100%, a 1440p monitor at 125% above-right.
        val tv = Box(-3840, -500, 3840, 2160)
        val main = Box(0, 0, 1920, 1080)
        val side = Box(1920, -400, 2560, 1440)
        val mons = listOf(main, tv, side)
        check("window inside main", ScreenFit.monitorFor(Box(100, 100, 1280, 720), mons) == main)
        check("window mostly on the TV", ScreenFit.monitorFor(Box(-1000, 200, 1280, 720), mons) == tv)
        check("window straddling, more on side", ScreenFit.monitorFor(Box(1700, 0, 1000, 600), mons) == side)
        check("window off every monitor, nearest", ScreenFit.monitorFor(Box(5000, 3000, 800, 600), mons) == side)
        check("no monitors", ScreenFit.monitorFor(Box(0, 0, 10, 10), emptyList()) == null)
        check("saved screen still there", ScreenFit.fullTarget(tv, Box(10, 10, 800, 600), mons) == tv)
        check("saved screen changed resolution, same corner", ScreenFit.fullTarget(Box(-3840, -500, 2560, 1440), Box(10, 10, 800, 600), mons) == tv)
        check("saved screen unplugged, window's monitor", ScreenFit.fullTarget(Box(7680, 0, 1920, 1080), Box(2000, 0, 800, 600), mons) == side)
        check("saved screen unplugged, window nowhere, main", ScreenFit.fullTarget(Box(7680, 0, 1920, 1080), Box(9000, 9000, 800, 600), mons) == main)
        check("first launch, no saved bounds, main", ScreenFit.fullTarget(null, null, mons) == main)
        // Windowed restore (AWT units): title bar on a work area, else Windows picks.
        val work = listOf(Box(0, 0, 1920, 1040), Box(-2560, -333, 2560, 1440))
        check("windowed bounds on screen kept", ScreenFit.reachable(Box(200, 100, 1440, 860), work) == Box(200, 100, 1440, 860))
        check("windowed bounds on the second screen kept", ScreenFit.reachable(Box(-2000, 0, 1440, 860), work) != null)
        check("windowed bounds of an unplugged screen dropped", ScreenFit.reachable(Box(4000, 100, 1440, 860), work) == null)
        check("title bar above every screen dropped", ScreenFit.reachable(Box(200, -900, 1440, 860), work) == null)
        check("larger than every screen dropped", ScreenFit.reachable(Box(0, 0, 4000, 2500), work) == null)
        check("only a sliver showing dropped", ScreenFit.reachable(Box(1880, 100, 1440, 860), work) == null)
        // F11 / Alt+Enter.
        check("F11 from Full Screen Windowed", ScreenFit.toggled(DisplayMode.FULL_SCREEN_WINDOWED, DisplayMode.FULL_SCREEN_WINDOWED) == DisplayMode.WINDOWED)
        check("F11 from Full Screen", ScreenFit.toggled(DisplayMode.FULL_SCREEN, DisplayMode.FULL_SCREEN) == DisplayMode.WINDOWED)
        check("F11 from Windowed goes to the last full kind", ScreenFit.toggled(DisplayMode.WINDOWED, DisplayMode.FULL_SCREEN) == DisplayMode.FULL_SCREEN)
        check("F11 from Windowed with no full kind yet", ScreenFit.toggled(DisplayMode.WINDOWED, DisplayMode.WINDOWED) == DisplayMode.FULL_SCREEN_WINDOWED)
        check("box text round trip", Box.parse(tv.toString()) == tv && Box.parse("1,2,0,4") == null && Box.parse(null) == null)
    }

    private fun migration() {
        fun read(vararg kv: Pair<String, String>) = WindowPrefs.read(mapOf(*kv)::get)
        check("first launch: Full Screen Windowed", read().mode == DisplayMode.FULL_SCREEN_WINDOWED)
        val old = read("w" to "1600", "h" to "900", "x" to "40", "y" to "30", "maximized" to "true", "fullscreen" to "false")
        check("old prefs open in Full Screen Windowed, windowed size and maximized kept",
            old.mode == DisplayMode.FULL_SCREEN_WINDOWED && old.w == 1600 && old.x == 40 && old.maximized)
        check("old full screen opens in Full Screen Windowed", read("fullscreen" to "true").mode == DisplayMode.FULL_SCREEN_WINDOWED)
        check("saved Windowed stays Windowed", read("mode" to "windowed", "lastFull" to "fullScreen").let { it.mode == DisplayMode.WINDOWED && it.lastFull == DisplayMode.FULL_SCREEN })
        check("saved Full Screen and its screen", read("mode" to "fullScreen", "screen" to "-3840,-500,3840,2160").let { it.mode == DisplayMode.FULL_SCREEN && it.screen == Box(-3840, -500, 3840, 2160) })
        check("unknown mode falls back", read("mode" to "cinema").mode == DisplayMode.FULL_SCREEN_WINDOWED)
        check("tiny sizes raised to the minimum", read("w" to "300", "h" to "200").let { it.w == WindowPrefs.MIN_W && it.h == WindowPrefs.MIN_H })
    }

    private fun settings(out: File) {
        val fakes = FakeServices.create()
        fun shot(name: String, w: Int, h: Int, act: (ImageComposeScene) -> Unit = {}, content: @androidx.compose.runtime.Composable () -> Unit) {
            val scene = ImageComposeScene(w, h, Density(1f)) { CompositionLocalProvider(LocalPagesServices provides fakes) { content() } }
            repeat(20) { scene.render(System.nanoTime()); Thread.sleep(30) }
            act(scene)
            repeat(20) { scene.render(System.nanoTime()); Thread.sleep(30) }
            File(out, "$name.png").writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
            println("snap $name")
        }
        shot("display-yourpc-1280x720", 1280, 720) { SettingsPage({}, {}, {}, startGroup = SettingsGroup.Pc) }
        shot("display-page-1280x720", 1280, 720) { DisplayModePage {} }
        shot("display-page-1024x640", 1024, 640) { DisplayModePage {} }
        shot("display-page-1920x1080", 1920, 1080) { DisplayModePage {} }
        // Keyboard: Tab to the first choice, Down to Full Screen (the focus look on a choice row).
        shot("display-page-keys-1280x720", 1280, 720, act = { sc ->
            for (k in listOf(Key.Tab, Key.DirectionDown, Key.DirectionDown)) {
                sc.sendKeyEvent(KeyEvent(k, KeyEventType.KeyDown)); sc.sendKeyEvent(KeyEvent(k, KeyEventType.KeyUp))
                repeat(6) { sc.render(System.nanoTime()); Thread.sleep(30) }
            }
        }) { DisplayModePage {} }
        // Picking Windowed by mouse saves it (the fake service) and the page closes back.
        var picked: String? = null
        shot("display-page-click-1280x720", 1280, 720, act = { sc ->
            val windowed = Offset(640f, 303f)
            sc.sendPointerEvent(PointerEventType.Move, windowed)
            sc.sendPointerEvent(PointerEventType.Press, windowed, button = androidx.compose.ui.input.pointer.PointerButton.Primary)
            sc.sendPointerEvent(PointerEventType.Release, windowed, button = androidx.compose.ui.input.pointer.PointerButton.Primary)
        }) { DisplayModePage { picked = "back" } }
        check("clicking Windowed saves it and goes back", picked == "back" && fakes.displayMode(null) == DisplayMode.WINDOWED.label, "mode ${fakes.displayMode(null)}")
    }
}
