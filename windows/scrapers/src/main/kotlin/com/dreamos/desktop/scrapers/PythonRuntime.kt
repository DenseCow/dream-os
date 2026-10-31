package com.dreamos.desktop.scrapers

import java.io.File
import java.security.MessageDigest

/**
 * The bundled CPython (tools/fetch-python.sh: the official Windows embeddable build, with our own ._pth) and the
 * host's Python files.
 *
 * Where Python is looked for, first match wins: the `dreamos.python.dir` system property; `python/` in the packaged
 * app's resources (`compose.application.resources.dir`); `third_party/python` above the working folder (a dev run
 * from the repo). The requests stack is `python-site/` next to it.
 *
 * The host's own files (dreamserve.py, dreamhost/, the xbmc shims) ship inside this jar and are written to
 * `<cache>/host/<hash of their contents>/` on first use, so what runs is always exactly this build's code.
 */
class PythonRuntime(val pythonDir: File, val siteDir: File) {
    /** pythonw.exe: no console window, ever (Java also starts it with CREATE_NO_WINDOW). */
    val executable: File get() = File(pythonDir, "pythonw.exe")

    fun usable(): Boolean = executable.isFile && File(pythonDir, "python313.zip").isFile

    companion object {
        fun locate(): PythonRuntime? {
            val candidates = ArrayList<File>()
            System.getProperty("dreamos.python.dir")?.takeIf { it.isNotBlank() }?.let { candidates += File(it) }
            System.getProperty("compose.application.resources.dir")?.takeIf { it.isNotBlank() }?.let { candidates += File(it, "python") }
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            repeat(5) {
                val d = dir ?: return@repeat
                candidates += File(d, "third_party/python")
                dir = d.parentFile
            }
            for (c in candidates) {
                val rt = PythonRuntime(c, File(c.parentFile, "python-site"))
                if (rt.usable()) return rt
            }
            return null
        }

        /** The host's files inside the jar (resources under dreamos/python/). */
        private val HOST_FILES = listOf(
            "dreamserve.py", "xbmc.py", "xbmcaddon.py", "xbmcgui.py", "xbmcplugin.py", "xbmcvfs.py",
            "dreamhost/__init__.py", "dreamhost/runner.py",
        )

        /** Writes the host's files under [hostRoot] (once per build) and returns their folder. Older copies are removed. */
        @Synchronized
        fun extractHost(hostRoot: File): File {
            val files = HOST_FILES.associateWith { name ->
                PythonRuntime::class.java.getResourceAsStream("/dreamos/python/$name")?.use { it.readBytes() }
                    ?: error("host file $name missing from the app")
            }
            val md = MessageDigest.getInstance("SHA-256")
            for ((name, bytes) in files) { md.update(name.toByteArray()); md.update(bytes) }
            val tag = md.digest().take(8).joinToString("") { "%02x".format(it) }
            val dest = File(hostRoot, tag)
            if (File(dest, ".complete").isFile) return dest
            hostRoot.mkdirs()
            val staging = File(hostRoot, "staging-${System.nanoTime()}")
            for ((name, bytes) in files) {
                val f = File(staging, name)
                f.parentFile.mkdirs()
                f.writeBytes(bytes)
            }
            File(staging, ".complete").writeText(tag)
            dest.deleteRecursively()
            if (!staging.renameTo(dest)) {
                staging.deleteRecursively()
                if (!File(dest, ".complete").isFile) error("couldn't place the host files")
            }
            hostRoot.listFiles()?.filter { it.isDirectory && it.name != tag }?.forEach { it.deleteRecursively() }
            return dest
        }
    }
}
