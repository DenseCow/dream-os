package com.firehome.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock

/** Low-latency UI sounds (navigation ticks, select, intro swell and chime, the notification pling), all preloaded. */
class Sounds(ctx: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val ids = HashMap<Int, Int>()
    private val loaded = HashSet<Int>()
    private var pendingSound = 0
    private var pendingVolume = 0f
    private var lastTick = 0L
    private var lastNotice = 0L

    init {
        // Listener first, then load, so no completion can slip past before it's registered.
        pool.setOnLoadCompleteListener { _, id, status ->
            if (status != 0) return@setOnLoadCompleteListener
            loaded += id
            // A long sound requested before it finished loading (the intro sound) plays as soon as it's ready.
            if (id == pendingSound) {
                pool.play(id, pendingVolume, pendingVolume, 1, 0, 1f)
                pendingSound = 0
            }
        }
        for (res in listOf(R.raw.nav_tick, R.raw.nav_select, R.raw.welcome_chime, R.raw.intro_swell, R.raw.notice_chime)) {
            ids[res] = pool.load(ctx, res, 1)
        }
    }

    /** Soft tick for focus moves; quieter and rate-limited while a key is held. */
    fun tick(held: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTick < if (held) 90 else 45) return
        lastTick = now
        play(R.raw.nav_tick, if (held) 0.018f else 0.035f, waitForLoad = false)
    }

    fun select() = play(R.raw.nav_select, 0.24f, waitForLoad = false)

    fun swell() = play(R.raw.intro_swell, 0.95f, waitForLoad = true)

    fun chime() = play(R.raw.welcome_chime, 0.7f, waitForLoad = true)

    /**
     * A notification banner slid in (DreamNoticesUi): a soft rising two-note pling, about 3 dB under the
     * welcome chime, at most once every few seconds however many banners follow. Settings' Notification
     * Sound ([Theme.NOTICE_SOUND], on by default) switches it off; callers check it ([noticeOn]).
     */
    fun notice() {
        val now = SystemClock.uptimeMillis()
        if (now - lastNotice < NOTICE_GAP_MS) return
        lastNotice = now
        play(R.raw.notice_chime, 1f, waitForLoad = false)
    }

    companion object {
        private const val NOTICE_GAP_MS = 4_000L
        fun noticeOn(ctx: Context) = Theme.get(ctx, Theme.NOTICE_SOUND) == 1
    }

    private fun play(res: Int, volume: Float, waitForLoad: Boolean) {
        val id = ids[res] ?: return
        if (id in loaded) {
            pool.play(id, volume, volume, 1, 0, 1f)
        } else if (waitForLoad) {
            pendingSound = id
            pendingVolume = volume
        }
    }

    fun release() = pool.release()
}
