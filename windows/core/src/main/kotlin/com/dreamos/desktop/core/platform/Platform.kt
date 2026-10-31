package com.dreamos.desktop.core.platform

import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * What differs between the PC's operating systems. Everything Windows-only stays behind this, so Mac and Linux later
 * add an implementation and nothing else changes.
 */
interface Platform {
    /** Settings and the viewer's data (`%APPDATA%\Dream OS` on Windows). Created on first use. */
    val dataDir: Path
    /** Caches that may be deleted at any time (`%LOCALAPPDATA%\Dream OS\Cache` on Windows). */
    val cacheDir: Path
    /** The PC's name ("JOE-PC"), for Trakt's device list and the setup's Your PC chapter. */
    val deviceName: String
    /** "Windows", "macOS", "Linux". */
    val osName: String
    /** Opens [url] in the viewer's browser. False when nothing could open it (the UI then shows the link to copy). */
    fun openUrl(url: String): Boolean
    /**
     * A system notification (Windows toast) for something that happened while Dream OS is in the background:
     * a new episode, an update. The app shell installs the real one ([notifier]); without it nothing shows.
     */
    fun notify(title: String, body: String) { notifier?.invoke(title, body) }
    var notifier: ((title: String, body: String) -> Unit)?
    /** How secrets are sealed at rest (Windows: DPAPI, current user). */
    val secretCipher: SecretCipher get() = PlainCipher

    companion object {
        /** The platform this process runs on. Tests swap it for a temporary folder ([forTests]). */
        @Volatile var current: Platform = detect()

        fun detect(): Platform {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                os.startsWith("windows") -> WindowsPlatform()
                else -> GenericPlatform(os)
            }
        }

        /** A platform whose data and cache live under [root] (unit tests, never the viewer's folders). */
        fun forTests(root: Path): Platform = GenericPlatform("test", root)
    }
}

/** Windows: `%APPDATA%\Dream OS` for data, `%LOCALAPPDATA%\Dream OS\Cache` for caches. */
class WindowsPlatform : Platform {
    override val dataDir: Path by lazy {
        val base = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
            ?: Paths.get(System.getProperty("user.home"), "AppData", "Roaming").toString()
        Files.createDirectories(Paths.get(base, APP))
    }
    override val cacheDir: Path by lazy {
        val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: Paths.get(System.getProperty("user.home"), "AppData", "Local").toString()
        Files.createDirectories(Paths.get(base, APP, "Cache"))
    }
    override val deviceName: String
        get() = System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() } ?: hostName() ?: "Windows PC"
    override val osName = "Windows"
    override val secretCipher: SecretCipher get() = DpapiCipher
    @Volatile override var notifier: ((String, String) -> Unit)? = null

    override fun openUrl(url: String): Boolean {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return false
        if (browse(url)) return true
        // No AWT desktop (headless): the shell's own handler.
        return try {
            ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
            true
        } catch (e: Exception) {
            false
        }
    }

    private companion object { const val APP = "Dream OS" }
}

/** Mac / Linux until they get their own (XDG-style folders under the home folder), and tests. */
class GenericPlatform(private val os: String, root: Path? = null) : Platform {
    private val home: Path = root ?: Paths.get(System.getProperty("user.home"))
    override val dataDir: Path by lazy {
        Files.createDirectories(if (root != null) home.resolve("data") else home.resolve(".config").resolve("dream-os"))
    }
    override val cacheDir: Path by lazy {
        Files.createDirectories(if (root != null) home.resolve("cache") else home.resolve(".cache").resolve("dream-os"))
    }
    override val deviceName: String get() = hostName() ?: "Computer"
    override val osName: String = when {
        os.contains("mac") -> "macOS"
        os.contains("linux") -> "Linux"
        else -> os
    }
    @Volatile override var notifier: ((String, String) -> Unit)? = null
    /** Tests on a Windows PC seal secrets as the app does; Mac / Linux store them as they are until the Keychain. */
    override val secretCipher: SecretCipher
        get() = if (os == "test" && System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")) DpapiCipher else PlainCipher

    override fun openUrl(url: String): Boolean {
        if (os == "test") return false
        if (!url.startsWith("https://") && !url.startsWith("http://")) return false
        if (browse(url)) return true
        return try {
            ProcessBuilder(if (osName == "macOS") "open" else "xdg-open", url).start()
            true
        } catch (e: Exception) {
            false
        }
    }
}

private fun browse(url: String): Boolean = try {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(URI(url))
        true
    } else false
} catch (e: Exception) {
    false
}

private fun hostName(): String? = try {
    java.net.InetAddress.getLocalHost().hostName.takeIf { it.isNotBlank() }
} catch (e: Exception) {
    null
}
