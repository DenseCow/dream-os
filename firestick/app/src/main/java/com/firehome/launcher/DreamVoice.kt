package com.firehome.launcher

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * The remote's mic button. It belongs to Alexa (com.amazon.vizzini), which records the remote and
 * does the speech-to-text; Dream OS never records it. Dream Search follows Alexa instead
 * (AlexaSearch): listening while Alexa listens, then the spoken query, taken from Amazon's results.
 *
 * Fire OS's mic key is keycode 319 ("ALEXA" in Vendor_0171_Product_0421.kl). Fire OS consumes it
 * before apps and accessibility services, so these are only seen with other remotes / keyboards.
 * Fire OS hides Log.d, so voice logs use Log.w under [TAG].
 */
object DreamVoice {
    const val TAG = "DreamVoice"

    /** Fire OS's mic / Alexa key (not in the Android SDK). */
    const val KEYCODE_ALEXA = 319

    fun isVoiceKey(code: Int) =
        code == KEYCODE_ALEXA || code == KeyEvent.KEYCODE_VOICE_ASSIST || code == KeyEvent.KEYCODE_ASSIST ||
            code == KeyEvent.KEYCODE_SEARCH

    @Volatile private var lastTrigger = 0L

    /** The first of several signals for one press counts (the key itself, the accessibility filter). */
    fun claimTrigger(windowMs: Long = 2500L): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastTrigger < windowMs) return false
        lastTrigger = now
        return true
    }

    fun onMicKey(down: Boolean) {
        Log.w(TAG, "mic key ${if (down) "down" else "up"}")
    }
}
