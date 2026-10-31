package com.dreamos.desktop.scrapers

import java.io.File

/**
 * Where the scrapers live on disk.
 *
 * [root] (`%APPDATA%\Dream OS\scrapers`): repos.json (what was added and from where), state.json (provider
 * switches, speed stats), home/addons/<id>/ (installed modules, laid out as Kodi lays them out),
 * home/userdata/addon_data/<id>/settings.xml (each module's settings, rewritten by Dream OS).
 * [cache] (`%LOCALAPPDATA%\Dream OS\Cache\scrapers`): special://temp, compiled bytecode, and the host's own
 * Python files (extracted from the app, see [PythonRuntime]). Losing [cache] costs nothing but a slower start.
 *
 * Windows-only paths stay here; Mac / Linux get their own [default] branch later.
 */
class ScraperPaths(val root: File, val cache: File) {
    val home: File get() = File(root, "home")
    val addons: File get() = File(home, "addons")
    val reposFile: File get() = File(root, "repos.json")
    val stateFile: File get() = File(root, "state.json")
    val temp: File get() = File(cache, "temp")
    val pycache: File get() = File(cache, "pycache")
    val hostRoot: File get() = File(cache, "host")

    fun profileDir(addonId: String) = File(home, "userdata/addon_data/$addonId")

    fun ensure(): ScraperPaths {
        for (d in listOf(root, addons, File(home, "userdata/addon_data"), temp, pycache, hostRoot)) d.mkdirs()
        return this
    }

    companion object {
        fun default(): ScraperPaths {
            val home = System.getProperty("user.home")
            val roaming = System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: "$home/AppData/Roaming"
            val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: "$home/AppData/Local"
            return ScraperPaths(File(roaming, "Dream OS/scrapers"), File(local, "Dream OS/Cache/scrapers"))
        }
    }
}

/** The scrapers' log lines ("Scrapers: ..."). [sink] is replaced by the app's logger once core has one. */
object ScraperLog {
    @Volatile var sink: (String) -> Unit = { System.err.println(it) }
    @Volatile var verbose = false

    fun w(msg: String) = try { sink("Scrapers: $msg") } catch (_: Throwable) {}
    fun d(msg: String) { if (verbose) w(msg) }
}
