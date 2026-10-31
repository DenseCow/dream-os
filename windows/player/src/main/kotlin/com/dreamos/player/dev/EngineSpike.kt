package com.dreamos.player.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamos.player.engine.LibMpv
import com.dreamos.player.engine.MpvCore
import com.dreamos.player.engine.PlayerLog
import com.dreamos.player.host.PlayerStage
import java.awt.Dimension
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * Engine spike: the stage + libmpv with the d3d11 settings Dream Player uses, printing smoothness counters every
 * 2 s. `./gradlew :player:spike -Purl=<video>` (args: url, then optional seconds to run and a mode: window/full).
 */
fun main(args: Array<String>) {
    val url = args.getOrNull(0) ?: error("url?")
    val seconds = args.getOrNull(1)?.toIntOrNull() ?: 30
    val startMode = args.getOrNull(2) ?: "window"
    PlayerLog.i("Spike: libmpv client API ${LibMpv.api.mpv_client_api_version().let { "${it shr 16}.${it and 0xffff}" }}")
    lateinit var core: MpvCore
    SwingUtilities.invokeAndWait {
        val app = JFrame("Dream OS (spike app window)").apply {
            contentPane.background = java.awt.Color(5, 5, 6)
            contentPane.preferredSize = Dimension(1280, 720)
            pack()
            setLocationRelativeTo(null)
            defaultCloseOperation = JFrame.EXIT_ON_CLOSE
            isVisible = true
        }
        val stage = PlayerStage(app)
        stage.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f / 255f))) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(140.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                ) {
                    Text("Overlay over mpv", color = Color.White, fontSize = 22.sp, modifier = Modifier.align(Alignment.BottomStart).padding(32.dp))
                }
            }
        }
        val hwnd = stage.show()
        if (startMode == "full") stage.setMode(PlayerStage.Mode.FullScreen)
        core = MpvCore("main")
        with(core) {
            optionWid(hwnd)
            option("vo", "gpu-next")
            option("gpu-api", "d3d11")
            option("hwdec", "d3d11va")
            option("target-colorspace-hint", "yes")
            option("keep-open", "yes")
            option("input-default-bindings", "no")
            option("input-vo-keyboard", "no")
            option("input-cursor", "no")
            option("cursor-autohide", "no")
            option("osc", "no")
            option("osd-level", "0")
            option("cache", "yes")
            option("demuxer-max-bytes", "512MiB")
            option("demuxer-readahead-secs", "120")
            option("terminal", "no")
            initialize()
            logs(System.getenv("DREAM_MPV_LOG") ?: "info")
            start { ev ->
                when (ev) {
                    is MpvCore.Event.Log -> if (ev.level != "debug" && ev.level != "trace") PlayerLog.i("mpv[${ev.prefix}] ${ev.text}")
                    is MpvCore.Event.EndFile -> PlayerLog.i("Spike: end ${ev.reason} ${ev.error}")
                    MpvCore.Event.PlaybackRestart -> PlayerLog.i("Spike: playback restart")
                    else -> {}
                }
            }
            command("loadfile", url)
        }
    }
    val t0 = System.currentTimeMillis()
    while (System.currentTimeMillis() - t0 < seconds * 1000L) {
        Thread.sleep(2000)
        val c = core
        PlayerLog.i(
            "Spike: t=${c.double("time-pos")?.let { "%.1f".format(it) }} " +
                "fps=${c.double("estimated-vf-fps")?.let { "%.2f".format(it) }} container=${c.double("container-fps")} " +
                "display=${c.double("display-fps")?.let { "%.1f".format(it) }} " +
                "drop(vo)=${c.long("frame-drop-count")} drop(dec)=${c.long("decoder-frame-drop-count")} " +
                "delayed=${c.long("vo-delayed-frame-count")} mistimed=${c.long("mistimed-frame-count")} " +
                "hwdec=${c.string("hwdec-current")} vo=${c.string("current-vo")} " +
                "size=${c.long("width")}x${c.long("height")} cache=${c.double("demuxer-cache-duration")?.let { "%.0f".format(it) }}s " +
                "in=${c.string("video-params/gamma")}/${c.string("video-params/primaries")} " +
                "out=${c.string("video-target-params/gamma") ?: c.string("video-out-params/gamma")}/${c.string("video-target-params/primaries")} " +
                "peak=${c.string("video-target-params/max-luma")}"
        )
    }
    core.destroy()
    kotlin.system.exitProcess(0)
}
