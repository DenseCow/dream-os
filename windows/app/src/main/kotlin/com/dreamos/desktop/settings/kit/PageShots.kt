package com.dreamos.desktop.settings.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * DEV TOOL: renders pages offscreen at the four window sizes the pages must hold (1024 x 640, 1280 x 720,
 * 1920 x 1080, 3840 x 2160 at 200%) and writes PNGs, so layouts, hover and focus can be checked without a window.
 * Run through the worktree's scratch runner; scenes come from [PreviewScenes].
 */
data class ShotSize(val w: Int, val h: Int, val density: Float) {
    val tag: String get() = "${w}x$h" + if (density != 1f) "@${density}x" else ""
}

class Shot(
    val name: String,
    val sizes: List<ShotSize> = ShotSizes.ALL,
    /** What to do to the page before the picture: hover a point, press keys. Gets the size in pixels. */
    val act: (ShotDriver) -> Unit = {},
    val content: @Composable () -> Unit,
)

object ShotSizes {
    val SMALL = ShotSize(1024, 640, 1f)
    val HD = ShotSize(1280, 720, 1f)
    val FHD = ShotSize(1920, 1080, 1f)
    val UHD = ShotSize(3840, 2160, 2f)
    val ALL = listOf(SMALL, HD, FHD, UHD)
}

@OptIn(ExperimentalComposeUiApi::class)
class ShotDriver(val scene: ImageComposeScene, val size: ShotSize) {
    /** Moves the mouse to a point given as fractions of the window. */
    fun hover(fx: Float, fy: Float) {
        scene.sendPointerEvent(PointerEventType.Move, Offset(size.w * fx, size.h * fy))
        settle(6)
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun key(key: androidx.compose.ui.input.key.Key, times: Int = 1, shift: Boolean = false) {
        repeat(times) {
            scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(key, androidx.compose.ui.input.key.KeyEventType.KeyDown, isShiftPressed = shift))
            scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(key, androidx.compose.ui.input.key.KeyEventType.KeyUp, isShiftPressed = shift))
            settle(3)
        }
    }

    fun tab(times: Int = 1) = key(androidx.compose.ui.input.key.Key.Tab, times)
    fun down(times: Int = 1) = key(androidx.compose.ui.input.key.Key.DirectionDown, times)
    fun right(times: Int = 1) = key(androidx.compose.ui.input.key.Key.DirectionRight, times)
    fun enter() = key(androidx.compose.ui.input.key.Key.Enter)

    fun wheel(fx: Float, fy: Float, dy: Float) {
        scene.sendPointerEvent(PointerEventType.Scroll, Offset(size.w * fx, size.h * fy), scrollDelta = Offset(0f, dy))
        settle(8)
    }

    fun settle(frames: Int = 4) {
        repeat(frames) { scene.render(System.nanoTime()); Thread.sleep(30) }
    }

    /** Renders until the image downloads are done (at most [maxMs]). */
    fun waitForImages(maxMs: Long = 15_000) {
        val start = System.currentTimeMillis()
        var quiet = 0
        while (System.currentTimeMillis() - start < maxMs) {
            settle(2)
            if (PageImages.inFlight.get() == 0) quiet++ else quiet = 0
            if (quiet >= 6) break
        }
    }
}

fun main(args: Array<String>) {
    // Never a sound from a preview (the owner's PC rule): the title page's hero trailer stays silent whatever the profile says.
    System.setProperty("dream.trailer.silent", "true")
    // A throwaway settings store: previews never read or write the viewer's own settings; hero trailers off unless a
    // scene turns them on (they take the screen after 4 s of rest, which a slow scene reaches).
    val store = com.dreamos.desktop.core.store.DreamStore(java.nio.file.Files.createTempDirectory("dream-page-shots"))
    com.dreamos.desktop.core.store.DreamStore.use(store)
    store.profileFile("pages").putString("look.trailers", "Off")
    try { shoot(args) } catch (t: Throwable) { t.printStackTrace() } finally { kotlin.system.exitProcess(0) }
}

private fun shoot(args: Array<String>) {
    val out = File(System.getProperty("shots.out") ?: "shots").apply { mkdirs() }
    val wanted = args.toList()
    val shots = PreviewScenes.all().filter { s -> wanted.isEmpty() || wanted.any { s.name.startsWith(it) } }
    val fakes = com.dreamos.desktop.settings.FakeServices.create()
    for (shot in shots) for (size in shot.sizes) {
        val scene = ImageComposeScene(size.w, size.h, Density(size.density)) {
            CompositionLocalProvider(LocalPagesNav provides LoggingNav, com.dreamos.desktop.settings.LocalPagesServices provides fakes) { shot.content() }
        }
        val driver = ShotDriver(scene, size)
        driver.waitForImages()
        shot.act(driver)
        driver.waitForImages(4_000)
        val img = scene.render(System.nanoTime())
        val f = File(out, "${shot.name}-${size.tag}.png")
        f.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        println("wrote ${f.name}")
        scene.close()
    }
}
