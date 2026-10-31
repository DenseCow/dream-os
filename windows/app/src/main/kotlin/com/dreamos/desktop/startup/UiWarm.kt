package com.dreamos.desktop.startup

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.configureSwingGlobalsForCompose

/**
 * The window's native start-up done beside the services' start instead of after it: the AWT toolkit and screens, and
 * the app's typeface. Each piece is what Compose's `application {}` and the first frame would do anyway, in the same
 * order (Compose's own Swing settings first, so the toolkit starts with them); here it overlaps with reading settings
 * and starting Dream Core. Nothing is shown.
 *
 * Skia's native library must already be loaded on the launching thread ([loadSkia], first in `main`): skiko's loader
 * lets a second thread call into a library that isn't bound yet (the crash win-perf fixed in AppServices.start).
 */
object UiWarm {
    /** On the launching thread, before any other thread can touch Skia. */
    fun loadSkia() = StartupTrace.span("skia load") { org.jetbrains.skiko.Library.load() }

    @OptIn(ExperimentalComposeUiApi::class)
    fun start(): Thread = Thread({
        // What `application {}` runs first (compose.application.configure.swing.globals=true in the launcher): Skiko's
        // global settings (and Skia's library, loaded already). Running it twice is harmless: the same values.
        StartupTrace.span("ui warm: swing globals") { runCatching { configureSwingGlobalsForCompose() } }
        if (java.awt.GraphicsEnvironment.isHeadless()) return@Thread
        StartupTrace.span("ui warm: awt") {
            runCatching {
                java.awt.Toolkit.getDefaultToolkit()
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform
            }
        }
        StartupTrace.span("ui warm: fonts") {
            runCatching {
                val fonts = org.jetbrains.skia.FontMgr.default
                for (style in listOf(org.jetbrains.skia.FontStyle.NORMAL, org.jetbrains.skia.FontStyle.BOLD)) {
                    fonts.matchFamilyStyle("Segoe UI Variable Display", style)?.close()
                }
            }
        }
    }, "dream-ui-warm").apply { isDaemon = true; start() }
}
