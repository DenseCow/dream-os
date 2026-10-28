package com.firehome.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings Dream OS up after a reboot. Only the system's own BOOT_COMPLETED counts (a protected
 * broadcast no app can send): an explicit broadcast from another app is ignored.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        HomeRedirectService.ensureEnabled(context)
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
