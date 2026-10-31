package com.dreamos.desktop.selfcheck

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString

/**
 * Tells the Windows setup and uninstaller that Dream OS is open: a named mutex ([NAME]) that lives as long as the
 * process. installer/DreamOS.iss checks for it (CheckForMutexes): an update waits for the closing app to finish, and
 * an uninstall asks the viewer to close Dream OS first. Windows deletes the mutex when the process ends.
 *
 * Call [hold] once at launch, after `--selfcheck` is handled. Never throws; off Windows it does nothing.
 */
object RunningMark {
    /** Must match AppMutexName in installer/DreamOS.iss. Mutex names are case sensitive. */
    const val NAME = "DreamOS.Running"

    private interface Kernel32 : Library {
        fun CreateMutexW(attributes: Pointer?, initialOwner: Boolean, name: WString): Pointer?
    }

    // Kept so the handle stays open for the life of the process.
    @Volatile private var handle: Pointer? = null

    fun hold() {
        if (handle != null || !System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        handle = runCatching { Native.load("kernel32", Kernel32::class.java).CreateMutexW(null, false, WString(NAME)) }.getOrNull()
    }
}
