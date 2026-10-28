package com.firehome.launcher

import android.app.Application
import android.os.Handler
import android.os.Looper

/**
 * A force-stop ends Dream OS and takes the Home button service off Fire OS's list, but Fire OS starts the process
 * again right away (for the screen that was in front) without opening it. Checking here, a moment after the start,
 * puts the Home button back even when no Dream OS screen opens ([HomeRedirectService.ensureEnabled]; never after
 * the user switched it off in Settings). The delay lets Fire OS finish handling the stop first.
 */
class DreamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Handler(Looper.getMainLooper()).postDelayed({ HomeRedirectService.ensureEnabled(this) }, 2_500)
    }
}
