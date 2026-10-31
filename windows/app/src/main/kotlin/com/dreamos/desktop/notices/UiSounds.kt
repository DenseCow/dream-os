package com.dreamos.desktop.notices

import com.dreamos.desktop.core.store.DreamStore
import java.util.concurrent.Executors
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import kotlin.math.log10

/**
 * Dream OS's interface sounds (fire-home Sounds.kt, the same five recordings and levels): a soft tick as the keyboard
 * moves focus (quieter and spaced out while a key is held), a click on Enter, the intro's swell and chime, and the
 * two-note notification pling (at most once every 4 s). Each follows its Settings switch for the profile in use:
 * Appearance › Navigation Sounds, Intro Sound, Notification Sound (all on by default).
 *
 * Never a sound headless or from a preview (`dream.silent` / `dream.trailer.silent`): the owner's PC stays quiet
 * while pages are checked. Clips load and play on one background thread, so a key press never waits on audio.
 */
object UiSounds {
    private val silent: Boolean get() = java.awt.GraphicsEnvironment.isHeadless() ||
        System.getProperty("dream.silent") == "true" || System.getProperty("dream.trailer.silent") == "true"
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dream-ui-sounds").apply { isDaemon = true } }
    private val clips = HashMap<String, Clip?>()
    @Volatile private var lastTick = 0L
    @Volatile private var lastNotice = 0L

    private fun on(key: String) = runCatching { DreamStore.shared.profileFile("pages").getBoolean(key, true) }.getOrDefault(true)

    /** Focus moved with an arrow key; a key held down (presses closer than 150 ms) ticks quieter and less often. */
    fun tick() {
        val now = System.currentTimeMillis()
        val held = now - lastTick < 150
        if (now - lastTick < if (held) 90 else 45) return
        lastTick = now
        if (on("look.navSounds")) play("nav_tick", if (held) 0.018f else 0.035f)
    }

    /** Enter on a control. */
    fun select() { if (on("look.navSounds")) play("nav_select", 0.24f) }

    /** The intro's swell as the mark eases in. */
    fun swell() { if (on("look.introSound")) play("intro_swell", 0.95f) }

    /** The intro's chime as it hands over to Home. */
    fun chime() { if (on("look.introSound")) play("welcome_chime", 0.7f) }

    /** A notification banner slid in. */
    fun notice() {
        val now = System.currentTimeMillis()
        if (now - lastNotice < 4_000) return
        lastNotice = now
        if (on("look.noticeSound")) play("notice_chime", 1f)
    }

    private fun play(name: String, volume: Float) {
        if (silent) return
        worker.execute {
            val clip = clips.getOrPut(name) { load(name) } ?: return@execute
            runCatching {
                (clip.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl)?.let { g ->
                    g.value = (20f * log10(volume.coerceIn(0.0001f, 1f))).coerceIn(g.minimum, g.maximum)
                }
                clip.stop(); clip.framePosition = 0; clip.start()
            }
        }
    }

    private fun load(name: String): Clip? = runCatching {
        val res = UiSounds::class.java.getResourceAsStream("/sounds/$name.wav") ?: return null
        val stream = AudioSystem.getAudioInputStream(java.io.BufferedInputStream(res))
        AudioSystem.getClip().apply { open(stream) }
    }.getOrNull()
}
