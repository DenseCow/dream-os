package com.firehome.launcher

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * Bluetooth audio next to Wi-Fi. The stick's Wi-Fi and Bluetooth share one combo chip: on 5 GHz Wi-Fi
 * the two get along (measured: Instant Play's lookups and trailer downloads left the AirPods' packet
 * timing untouched), but on 2.4 GHz they share the band, and a big background download can make
 * Bluetooth audio stutter. [crowded] tells the background work when to hold back; the log lines here
 * ("Audio: ...") pin down the cause the next time the AirPods cut out.
 */
@SuppressLint("MissingPermission")
object BtAudio {
    private const val TAG = "FireHome"

    /** True while a Bluetooth headset or speaker is connected as the TV's audio output (A2DP). */
    fun active(): Boolean = try {
        BluetoothAdapter.getDefaultAdapter()?.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
    } catch (e: Exception) {
        false
    }

    /** The Wi-Fi link's frequency in MHz; 0 when not on Wi-Fi or unknown. */
    fun wifiMhz(ctx: Context): Int = try {
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.frequency?.takeIf { it > 0 } ?: 0
    } catch (e: Exception) {
        0
    }

    /** Bluetooth audio while Wi-Fi is on 2.4 GHz: background downloads should wait. */
    fun crowded(ctx: Context): Boolean = active() && wifiMhz(ctx) in 1..2999

    /** "Wi-Fi 5.6 GHz" for log lines. */
    fun wifi(ctx: Context): String = wifiMhz(ctx).let { if (it > 0) "Wi-Fi %.1f GHz".format(it / 1000.0) else "Wi-Fi off" }

    private var lastHeldAt = 0L

    /** Logs that [what] was held back for Bluetooth audio, at most once a minute. */
    fun held(what: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHeldAt < 60_000L) return
        lastHeldAt = now
        Log.w(TAG, "Audio: $what held back (Bluetooth audio on 2.4 GHz Wi-Fi)")
    }

    /**
     * Dream Player's dropout marker: ExoPlayer reports an underrun when the audio output ran dry, the
     * one dropout the app itself causes (decoder or data late). Bluetooth cut-outs that happen over the
     * air don't show here, so an AirPods cut-out with no underrun logged points at the radio link.
     */
    fun underrunLogger(ctx: Context): AnalyticsListener {
        val app = ctx.applicationContext
        return object : AnalyticsListener {
            private var lastAt = 0L
            private var count = 0

            override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                count++
                val now = SystemClock.elapsedRealtime()
                if (now - lastAt < 5_000L) return
                lastAt = now
                val out = if (active()) "Bluetooth" else "TV speakers"
                Log.w(TAG, "Audio: underrun #$count at ${eventTime.currentPlaybackPositionMs / 1000} s (buffer $bufferSizeMs ms, fed $elapsedSinceLastFeedMs ms ago) · $out · ${wifi(app)}")
            }
        }
    }
}
