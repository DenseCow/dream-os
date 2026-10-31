package com.dreamos.desktop.details.dev

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.dreamos.desktop.core.content.Tmdb
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.details.DemoTitleSource
import com.dreamos.desktop.details.DevTitleSource
import com.dreamos.desktop.details.TitlePage
import com.dreamos.desktop.settings.FakeServices
import com.dreamos.desktop.settings.LocalPagesServices
import com.dreamos.desktop.settings.kit.LocalPagesNav
import com.dreamos.desktop.settings.kit.LoggingNav
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import java.util.Properties
import javax.swing.SwingUtilities

/**
 * DEV TOOL: the real title page with its hero trailer, offscreen and silent (no window, no sound, no pointer: the
 * owner's PC rule). Shots: the page before the trailer, the trailer playing with the page faded, after a pointer move,
 * and after Enter on Play (stopNow, then the logging nav's play). Uses a temporary settings store.
 * Run with -Ddream.shots=<dir> -Ddream.mpv.dir=<repo>/third_party/mpv -Ddream.trailer.silent=true.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
fun main() {
    System.setProperty("dream.trailer.silent", "true")
    val out = File(System.getProperty("dream.shots") ?: "build/title-trailer-shots").apply { mkdirs() }
    val (w, h) = (System.getProperty("dream.size") ?: "1280x720").split("x").map { it.toInt() }
    val store = DreamStore(Files.createTempDirectory("dream-title-trailer"))
    DreamStore.use(store)
    val key = listOf(File("local.properties"), File("../local.properties")).firstOrNull { it.isFile }
        ?.let { f -> Properties().apply { f.reader().use { load(it) } }.getProperty("tmdb.key") }
    if (key.isNullOrBlank()) { System.err.println("tmdb.key missing from local.properties"); return }
    Tmdb.setKey(key)
    store.profileFile("pages").putString("look.trailers", "On, Muted")
    val dev = DevTitleSource.fromLocalProperties()
    val fakes = FakeServices.create()

    lateinit var scene: ImageComposeScene
    fun ui(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
    val t0 = System.nanoTime()
    fun ms() = (System.nanoTime() - t0) / 1_000_000
    fun runFor(msLong: Long) {
        val end = System.currentTimeMillis() + msLong
        while (System.currentTimeMillis() < end) { ui { scene.render(System.nanoTime()) }; Thread.sleep(33) }
    }
    fun shot(name: String) {
        ui { File(out, "$name.png").writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes) }
        println("SHOT $name at ${ms()} ms")
    }
    ui {
        scene = ImageComposeScene(w, h, Density(1f), coroutineContext = Dispatchers.Main) {
            CompositionLocalProvider(LocalPagesNav provides LoggingNav, LocalPagesServices provides fakes) {
                TitlePage(DemoTitleSource.DUNE_TWO, dev, onBack = {})
            }
        }
    }
    // The details arrive in about half a second here; the trailer shows a second or two later.
    runFor(900)
    shot("1-before")
    runFor(12_000)
    shot("2-playing")
    ui { scene.sendPointerEvent(PointerEventType.Move, Offset(w / 2f, h / 2f)) }
    runFor(900)
    shot("3-after-mouse-move")
    // The speaker at the hero's bottom right: hover it, then M turns the sound on (silent here: no audio device).
    val gutter = (w * 0.05f).coerceIn(40f, 96f)
    ui { scene.sendPointerEvent(PointerEventType.Move, Offset(w - gutter - 26f, h * 0.86f - 34f - 26f)) }
    runFor(700)
    shot("4-speaker-hover")
    ui {
        scene.sendKeyEvent(KeyEvent(Key.M, KeyEventType.KeyDown))
        scene.sendKeyEvent(KeyEvent(Key.M, KeyEventType.KeyUp))
    }
    runFor(700)
    shot("5-sound-on")
    runFor(5500)
    shot("6-idle-again")
    ui { scene.sendPointerEvent(PointerEventType.Move, Offset(w / 3f, h / 3f)) }
    runFor(700)
    ui {
        scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
        scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp))
    }
    runFor(900)
    shot("7-after-play")
    ui { scene.close() }
    println("DONE in ${ms()} ms")
    Thread.sleep(1500)
    System.exit(0)
}
