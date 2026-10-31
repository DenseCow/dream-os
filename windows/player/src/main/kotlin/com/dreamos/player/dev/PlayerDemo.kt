package com.dreamos.player.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import com.dreamos.player.DreamPlayer
import com.dreamos.desktop.core.playback.MediaKind
import com.dreamos.desktop.core.playback.PlayTarget
import com.dreamos.player.engine.PlayerLog
import com.dreamos.player.session.PlayerSession
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * Dev runner (not shipped): a stand-in app window, Dream Player opened over it, and a small script for screenshots.
 * `./gradlew :player:demo "-Pargs=<script>"`; script steps, comma separated:
 *   size:WxH  movie|episode  wait:S  shot:NAME  sheet:INFO|AUDIO_SUBS|PLAYBACK|EPISODES|SOURCE|XRAY  close-sheet
 *   seek:S  pause  play  mode:full|window|mini  hover:FX:FY (pointer to a fraction of the player)  stats  quit
 * Screenshots go to -Ddream.shots (default build/shots). The metadata below is test data for the open movie Sintel
 * (Blender Foundation, 2010, CC BY 3.0); markers and the episode layout are made up for exercising the UI.
 */
fun main(args: Array<String>) {
    val script = (args.firstOrNull() ?: "wait:12,shot:controls,stats,quit").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val shots = File(System.getProperty("dream.shots") ?: "build/shots").apply { mkdirs() }
    devStore()
    val url = System.getProperty("dream.url") ?: SINTEL
    lateinit var app: ComposeWindow
    val size = script.firstOrNull { it.startsWith("size:") }?.removePrefix("size:")?.split("x")?.map { it.toInt() } ?: listOf(1280, 720)
    val episode = "episode" in script
    SwingUtilities.invokeAndWait {
        app = ComposeWindow().apply {
            title = "Dream OS"
            setContent { Box(Modifier.fillMaxSize().background(Color(0xFF050506)), contentAlignment = Alignment.Center) { Text("Dream OS", color = Color.White) } }
            contentPane.preferredSize = Dimension(size[0], size[1])
            pack()
            setLocationRelativeTo(null)
            defaultCloseOperation = javax.swing.WindowConstants.EXIT_ON_CLOSE
            isVisible = true
        }
        val target = if (episode) PlayTarget(MediaKind.EPISODE, 45745, "Sintel", 2010, season = 1, episode = 1, episodeTitle = "Test Episode One")
        else PlayTarget(MediaKind.MOVIE, 45745, "Sintel", 2010, imdb = "tt1727587")
        DreamPlayer.play(app, target, DevPlayback(url), DemoServices(), DemoEvents)
    }
    val robot = Robot()
    for (step in script) {
        val (cmd, arg) = step.split(":", limit = 2).let { it[0] to it.getOrNull(1) }
        val s: PlayerSession? = DreamPlayer.session
        fun ui(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
        when (cmd) {
            "wait" -> Thread.sleep(((arg ?: "1").toDouble() * 1000).toLong())
            "shot" -> {
                Thread.sleep(400)
                val b: Rectangle = DreamPlayer.stageBounds ?: app.bounds
                val f = File(shots, "${arg ?: "shot"}.png")
                ImageIO.write(robot.createScreenCapture(b), "png", f)
                PlayerLog.i("Demo: screenshot ${f.absolutePath} (${b.width}x${b.height})")
            }
            "sheet" -> ui { s?.openSheet(PlayerSession.Sheet.valueOf(arg!!)) }
            "close-sheet" -> ui { s?.closeSheet() }
            "seek" -> ui { s?.seekTo((arg!!.toDouble() * 1000).toLong()) }
            "pause" -> ui { s?.engine?.pause() }
            "play" -> ui { s?.engine?.play() }
            "mode" -> ui {
                val c = DreamPlayer.control ?: return@ui
                when (arg) {
                    "full" -> if (!c.fullScreen) c.toggleFullScreen()
                    "window" -> if (c.mini) c.expand() else if (c.fullScreen) c.toggleFullScreen()
                    "mini" -> c.enterMini()
                }
            }
            "hover" -> {
                val b = DreamPlayer.stageBounds ?: continue
                val (fx, fy) = arg!!.split(":").map { it.toDouble() }
                robot.mouseMove(b.x + (b.width * fx).toInt() - 6, b.y + (b.height * fy).toInt())
                Thread.sleep(80)
                robot.mouseMove(b.x + (b.width * fx).toInt(), b.y + (b.height * fy).toInt())
            }
            "stats" -> PlayerLog.i("Demo: smoothness ${DreamPlayer.engineSmoothness()}")
            "quit" -> { ui { DreamPlayer.close() }; Thread.sleep(500); kotlin.system.exitProcess(0) }
        }
    }
}
