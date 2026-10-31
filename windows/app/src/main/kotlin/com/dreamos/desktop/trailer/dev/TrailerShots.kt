package com.dreamos.desktop.trailer.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamos.desktop.core.content.Tmdb
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.trailer.TrailerRef
import com.dreamos.desktop.trailer.TrailerBackdrop
import com.dreamos.desktop.trailer.TrailerBackdropState
import com.dreamos.desktop.trailer.rememberTrailerBackdropState
import com.dreamos.desktop.trailer.trailerUi
import com.dreamos.desktop.trailer.trailerWatch
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import java.util.Properties
import javax.swing.SwingUtilities

/**
 * The hero trailer without a window, a sound or the pointer (the owner's PC rule): a title-page-like hero rendered with
 * ImageComposeScene on the UI thread, the real lookup and a real libmpv drawing with no audio device. Shots: before the
 * trailer, playing (UI faded), after a pointer move (UI back), 5 s later (faded again), after stopNow.
 * `./gradlew :app:trailerShots -Pshots=<dir> [-Pmovie=<tmdb id>]`. Uses a temporary settings store, never the viewer's.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    System.setProperty("dream.trailer.silent", "true")
    val out = File(System.getProperty("dream.shots") ?: "build/trailer-shots").apply { mkdirs() }
    val movie = System.getProperty("dream.movie")?.toIntOrNull() ?: 693134
    val (w, h) = (System.getProperty("dream.size") ?: "1280x720").split("x").map { it.toInt() }

    val store = DreamStore(Files.createTempDirectory("dream-trailer-shots"))
    DreamStore.use(store)
    val key = listOf(File("local.properties"), File("../local.properties")).firstOrNull { it.isFile }
        ?.let { f -> Properties().apply { f.reader().use { load(it) } }.getProperty("tmdb.key") }
    if (key.isNullOrBlank()) { System.err.println("tmdb.key missing from local.properties"); return }
    Tmdb.setKey(key)
    store.profileFile("pages").putString("look.trailers", "On, Muted")

    val ref = TrailerRef(movie, isShow = false, name = "Movie $movie")
    lateinit var scene: ImageComposeScene
    lateinit var state: TrailerBackdropState
    fun ui(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
    val t0 = System.nanoTime()
    fun ms() = (System.nanoTime() - t0) / 1_000_000
    fun frame() = ui { scene.render(System.nanoTime()) }
    fun shot(name: String) {
        ui {
            val img = scene.render(System.nanoTime())
            File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        println("SHOT $name at ${ms()} ms: playing=${state.playing} immersive=${state.immersive}")
    }
    fun runFor(msLong: Long, until: () -> Boolean = { false }) {
        val end = System.currentTimeMillis() + msLong
        while (System.currentTimeMillis() < end && !until()) { frame(); Thread.sleep(33) }
    }

    ui {
        scene = ImageComposeScene(w, h, Density(1f), coroutineContext = Dispatchers.Main) {
            state = rememberTrailerBackdropState()
            LaunchedEffect(Unit) { state.update(ref, resting = true, visible = true) }
            Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0D)).trailerWatch(state)) {
                // Stand-in for the backdrop picture (the trailer dissolves in over it).
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2A2A2E), Color(0xFF111113)))))
                TrailerBackdrop(state, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().trailerUi(state)) {
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.42f to Color.Black.copy(alpha = 0.55f), 0.75f to Color.Transparent)))
                    Column(Modifier.align(Alignment.BottomStart).padding(48.dp)) {
                        Text("Title Page Hero", color = Color.White, fontSize = 40.sp)
                        Text("Play   Trailer   My List", color = Color(0xFFD0D0D6), fontSize = 18.sp)
                    }
                }
            }
        }
    }
    runFor(400)
    shot("1-before")
    runFor(30_000) { state.playing }
    println("PLAYING after ${ms()} ms (the 1 s rest, or the lookup + first frame + picture check when longer)")
    runFor(2500)
    shot("2-playing-immersive")
    ui { scene.sendPointerEvent(PointerEventType.Move, Offset(w / 2f, h / 2f)) }
    runFor(800)
    shot("3-after-mouse-move")
    runFor(5200)
    shot("4-idle-again")
    ui { state.stopNow() }
    runFor(600)
    shot("5-after-stop")
    ui { scene.close() }
    println("DONE in ${ms()} ms; shots in ${out.absolutePath}")
    Thread.sleep(1500) // the decoder is let go on its own thread
    System.exit(0)
}
