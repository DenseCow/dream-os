package com.dreamos.desktop.scrapers

import com.dreamos.desktop.scrapers.ScraperRepos.s
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.xml.stream.XMLStreamConstants

/**
 * Sources: the scraper repositories the viewer adds (Kodi repository format, e.g. CocoScrapers), whose providers
 * Dream OS runs itself in the bundled Python ([ScraperEngine]) to find streams; the debrid check and Play stay
 * Dream Core's. Dream OS ships no sources of its own. Port of fire-home's Scrapers.kt; the layout on disk is in
 * [ScraperPaths]; provider switches, speed stats and the applied recommended set live in state.json (the stick's
 * "dream_scrapers" preferences).
 *
 * Public API: the setup / settings pages' contract (status, add / update / remove, provider switches, test).
 * Blocking calls (add, update, remove, test) run off the UI thread. Listeners are called on a background thread.
 */
class Scrapers(val paths: ScraperPaths, runtime: PythonRuntime? = PythonRuntime.locate()) {
    data class Repo(val url: String, val id: String, val name: String, val version: String, val modules: List<Module>)
    data class Module(val name: String, val version: String)
    data class Provider(val id: String, val name: String, val kind: String /* "torrent" | "hoster" */, val enabled: Boolean)
    data class Status(val repos: List<Repo>, val providers: List<Provider>, val pythonReady: Boolean, val pythonBroken: Boolean) {
        val ready: Boolean get() = repos.isNotEmpty() && providers.any { it.enabled }
    }
    sealed class AddResult {
        data class Ok(val repo: Repo, val modules: List<String>) : AddResult()
        /** A viewer-facing sentence, no technical words. */
        data class Failed(val message: String) : AddResult()
    }
    data class TestResult(val ok: Boolean, val found: Int, val ready: Int, val ms: Long, val message: String)

    /** One provider file inside an installed module, for the engine. */
    class Ref(val id: String, val addon: String, val file: File, val name: String, val kind: String, val defaultOn: Boolean)

    /** A repository the viewer added: where it came from and which modules it installed (id -> version). */
    private class Rec(val url: String, val source: ScraperRepos.Source, val modules: Map<String, String>) {
        fun json(): JsonObject = JsonObject(source.json(url) + ("modules" to JsonObject(modules.mapValues { JsonPrimitive(it.value) })))
        fun repo(installedNames: (String) -> String): Repo = Repo(url, source.id, friendly(source.name),
            source.version.ifEmpty { modules.values.firstOrNull().orEmpty() }, modules.map { (id, v) -> Module(installedNames(id), v) })
        companion object {
            fun from(o: JsonObject): Rec {
                val m = o["modules"] as? JsonObject ?: JsonObject(emptyMap())
                return Rec(o.s("url"), ScraperRepos.Source.from(o), m.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() })
            }
        }
    }

    val engine = ScraperEngine(this, runtime)

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val installLock = Any()
    private val stateLock = Any()
    @Volatile private var recs: List<Rec>? = null
    @Volatile private var refs: List<Ref>? = null
    @Volatile private var stampValue = 0L
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "scrapers-timer").apply { isDaemon = true } }

    // provider switches (on.<id>), the applied recommended set, speed stats
    private val switches = ConcurrentHashMap<String, Boolean>()
    @Volatile private var recApplied: Set<String>? = null
    private class Stat(var ms: Double, var hits: Int, var misses: Int)
    private val stats = ConcurrentHashMap<String, Stat>()
    @Volatile private var statsDirty = 0

    init {
        paths.ensure()
        loadState()
    }

    /** Changes whenever what's installed or switched on changes (the engine reloads its modules when it differs). */
    fun stamp() = stampValue

    // ---- status ----

    private fun recs(): List<Rec> = recs ?: synchronized(this) {
        recs ?: try {
            val f = paths.reposFile
            if (!f.isFile) emptyList() else (Json.parseToJsonElement(f.readText()) as JsonArray).mapNotNull { (it as? JsonObject)?.let(Rec::from) }
        } catch (e: Exception) {
            ScraperLog.w("repos.json unreadable ($e)"); emptyList()
        }.also { recs = it }
    }

    private fun saveRecs(list: List<Rec>) {
        writeAtomically(paths.reposFile, JsonArray(list.map { it.json() }).toString())
        recs = list
    }

    /** Every provider of the installed modules (folder scan, cached until something is installed or removed). */
    fun refs(): List<Ref> = refs ?: synchronized(this) { refs ?: scan().also { refs = it } }

    private fun enabled(r: Ref) = switches[r.id] ?: r.defaultOn

    fun status(): Status {
        val providers = refs().map { Provider(it.id, it.name, it.kind, enabled(it)) }
        return Status(recs().map { it.repo(::moduleName) }, providers, engine.ready(), engine.broken())
    }

    /** Installed, with providers switched on, and Python can run here. Cheap; any thread. */
    fun active(): Boolean = !engine.broken() && recs().isNotEmpty() && enabledRefs().isNotEmpty()

    fun enabledRefs(): List<Ref> = refs().filter { enabled(it) }

    /** The installed modules' Python folders (every installed add-on with a Python module), for sys.path. */
    fun libDirs(): List<File> = (paths.addons.listFiles() ?: emptyArray()).filter { it.isDirectory }.sortedBy { it.name }
        .mapNotNull { d -> moduleLib(d)?.let { File(d, it) }?.takeIf { it.isDirectory } }

    /** Python is warmed in the background when a source is installed (app start, after the intro). */
    fun warm() {
        if (recs().isNotEmpty() && enabledRefs().isNotEmpty()) engine.warm()
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** Called when providers or installed modules change (ScraperSources drops its kept scrapes). */
    internal val changeHooks = CopyOnWriteArrayList<() -> Unit>()

    private fun changed() {
        timer.execute { for (l in listeners) try { l() } catch (e: Exception) { ScraperLog.w("listener failed: $e") } }
    }

    private fun installedChanged() {
        synchronized(this) { refs = null; stampValue++ }
        changeHooks.forEach { it() }
        warm()
        changed()
    }

    // ---- add / update / remove ----

    /** Blocking. Adds the repository at [url] (what the viewer typed). [progress] gets short Title Case lines. */
    fun addRepository(url: String, progress: (String) -> Unit = {}): AddResult {
        return try {
            synchronized(installLock) {
                val u = ScraperRepos.normalize(url)
                progress("Checking Address…")
                val src = ScraperRepos.resolve(u)
                val installed = LinkedHashMap<String, String>()
                val names = ArrayList<String>()
                if (src.direct != null) {
                    progress("Downloading…")
                    val bytes = ScraperRepos.download(src.direct, ScraperRepos.MAX_ZIP)
                    progress("Installing ${friendly(src.name)}…")
                    ScraperRepos.unpack(bytes, src.id, paths.addons)
                    installed[src.id] = src.version
                    names += friendly(src.name)
                } else {
                    progress("Downloading…")
                    val (spec, entries) = ScraperRepos.firstIndex(src.specs)
                    val pick = ScraperRepos.pick(entries)
                    if (pick.isEmpty()) return AddResult.Failed(ScraperRepos.NOTHING)
                    for (e in pick) {
                        progress("Installing ${friendly(e.name)}…")
                        ScraperRepos.install(e, spec, paths.addons)
                        installed[e.id] = e.version
                        names += friendly(e.name)
                    }
                }
                synchronized(this) { refs = null }
                val found = refs().filter { it.addon in installed }
                if (found.isEmpty()) {
                    removeModules(installed.keys, keepFor = recs())
                    installedChanged()
                    return AddResult.Failed(ScraperRepos.NOTHING)
                }
                progress("Getting Ready…")
                installed.keys.forEach { writeSettings(it) }
                val rec = Rec(u, src, installed)
                saveRecs(recs().filter { it.source.id != src.id } + rec)
                installedChanged()
                ScraperLog.w("added ${src.id} (${installed.entries.joinToString { "${it.key} ${it.value}" }}): ${found.size} providers")
                AddResult.Ok(rec.repo(::moduleName), names.distinct())
            }
        } catch (e: ScraperRepos.UserError) {
            ScraperLog.w("add failed: ${e.message}"); AddResult.Failed(e.message ?: ScraperRepos.NOTHING)
        } catch (e: IOException) {
            ScraperLog.w("add failed: ${e.javaClass.simpleName} ${e.message}"); AddResult.Failed(ScraperRepos.UNREACHABLE)
        } catch (e: Exception) {
            ScraperLog.w("add failed: $e"); AddResult.Failed("Something went wrong while adding that source. Try again.")
        }
    }

    /** Blocking. Checks every added repository for newer modules and installs them. */
    fun updateAll(progress: (String) -> Unit = {}): AddResult {
        val list = recs()
        if (list.isEmpty()) return AddResult.Failed("There are no sources to update yet.")
        var first: Repo? = null
        val updated = ArrayList<String>()
        var failure: String? = null
        synchronized(installLock) {
            val out = ArrayList<Rec>()
            for (rec in list) {
                try {
                    progress("Checking ${friendly(rec.source.name)}…")
                    val mods = LinkedHashMap(rec.modules)
                    var src = rec.source
                    val direct = src.direct
                    if (direct != null) {
                        val bytes = ScraperRepos.download(direct, ScraperRepos.MAX_ZIP)
                        val tmp = File(paths.temp, "scrapers-check").apply { deleteRecursively(); mkdirs() }
                        val dir = ScraperRepos.unpack(bytes, src.id, tmp)
                        val v = ScraperRepos.parseAddons(File(dir, "addon.xml").readText()).firstOrNull()?.version.orEmpty()
                        tmp.deleteRecursively()
                        if (ScraperRepos.compareVersions(v, mods[src.id].orEmpty()) > 0) {
                            progress("Installing ${friendly(src.name)}…")
                            ScraperRepos.unpack(bytes, src.id, paths.addons)
                            mods[src.id] = v; updated += friendly(src.name)
                            src = ScraperRepos.Source(src.id, src.name, v, src.specs, direct)
                        }
                    } else {
                        val (spec, entries) = ScraperRepos.firstIndex(src.specs)
                        // A newer repository add-on may have moved: Kodi follows its new addresses too.
                        entries.firstOrNull { it.id == src.id && it.specs.isNotEmpty() && ScraperRepos.compareVersions(it.version, src.version) > 0 }?.let {
                            src = ScraperRepos.Source(src.id, it.name, it.version, (it.specs + src.specs).distinctBy { s -> s.info })
                        }
                        for (e in ScraperRepos.pick(entries)) {
                            if (mods.containsKey(e.id) && ScraperRepos.compareVersions(e.version, mods[e.id].orEmpty()) <= 0 && File(paths.addons, e.id).isDirectory) continue
                            progress("Installing ${friendly(e.name)}…")
                            ScraperRepos.install(e, spec, paths.addons)
                            mods[e.id] = e.version
                            updated += friendly(e.name)
                        }
                    }
                    val r = Rec(rec.url, src, mods)
                    out += r
                    if (first == null) first = r.repo(::moduleName)
                } catch (e: Exception) {
                    out += rec
                    failure = (e as? ScraperRepos.UserError)?.message
                        ?: if (e is IOException) ScraperRepos.UNREACHABLE else "Couldn't check for updates right now. Try again later."
                    ScraperLog.w("update of ${rec.source.id} failed: ${e.javaClass.simpleName} ${e.message}")
                }
            }
            saveRecs(out)
            if (updated.isNotEmpty()) {
                synchronized(this) { refs = null }
                out.flatMap { it.modules.keys }.forEach { writeSettings(it) }
                installedChanged()
                ScraperLog.w("updated ${updated.joinToString()}")
            }
        }
        val f = first
        return if (f == null) AddResult.Failed(failure ?: "Couldn't check for updates right now. Try again later.") else AddResult.Ok(f, updated.distinct())
    }

    /** Blocking. Removes the repository [repoId] and every module only it installed. */
    fun removeRepository(repoId: String) {
        synchronized(installLock) {
            val list = recs()
            val gone = list.filter { it.source.id == repoId }
            if (gone.isEmpty()) return
            val keep = list - gone.toSet()
            saveRecs(keep)
            removeModules(gone.flatMap { it.modules.keys }.toSet(), keep)
            installedChanged()
            ScraperLog.w("removed $repoId")
        }
    }

    /** Deletes [ids] (code, settings, provider switches) unless a repository in [keepFor] still lists them. */
    private fun removeModules(ids: Set<String>, keepFor: List<Rec>) {
        val still = keepFor.flatMap { it.modules.keys }.toSet()
        for (id in ids - still) {
            File(paths.addons, id).deleteRecursively()
            paths.profileDir(id).deleteRecursively()
            switches.keys.removeIf { it.startsWith("$id:") }
        }
        saveState()
    }

    // ---- provider switches ----

    private val pendingSettings = ConcurrentHashMap.newKeySet<String>()

    fun setEnabled(providerId: String, enabled: Boolean) {
        switches[providerId] = enabled
        saveState()
        val addon = providerId.substringBefore(':')
        // A burst of switches (Turn All On) writes each module's settings once.
        if (pendingSettings.add(addon)) timer.schedule({
            pendingSettings.remove(addon)
            writeSettings(addon)
            engine.settingsChanged()
        }, 400, TimeUnit.MILLISECONDS)
        changeHooks.forEach { it() }
        if (enabled) warm()
        changed()
    }

    // ---- the installed modules on disk ----

    /** The xbmc.python.module library folder of the add-on in [dir] ("lib"), or null if it isn't a Python module. */
    private fun moduleLib(dir: File): String? {
        val xml = File(dir, "addon.xml").takeIf { it.isFile }?.readText() ?: return null
        val tag = Regex("<extension\\b[^>]*point\\s*=\\s*\"xbmc\\.python\\.module\"[^>]*>").find(xml)?.value ?: return null
        val lib = Regex("library\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1) ?: "lib"
        return lib.trim('/').takeIf { it.isNotEmpty() && ".." !in it && ':' !in it } ?: "."
    }

    private fun moduleName(id: String): String =
        File(paths.addons, "$id/addon.xml").takeIf { it.isFile }?.let { f -> ScraperRepos.parseAddons(f.readText()).firstOrNull()?.name }
            ?.let { friendly(it) } ?: id

    /**
     * Providers, Fenomscrapers / CocoScrapers layout: <lib>/<package>/sources_<x>/<kind>/<name>.py (kind folders
     * "torrents", "hosters"...). Default on / off from the module's own provider.<name> setting.
     */
    private fun scan(): List<Ref> {
        val out = ArrayList<Ref>()
        for (addon in (paths.addons.listFiles() ?: emptyArray()).filter { it.isDirectory }.sortedBy { it.name }) {
            val lib = moduleLib(addon)?.let { File(addon, it) } ?: continue
            val defaults by lazy { settingDefaults(addon) }
            for (pkg in (lib.listFiles() ?: emptyArray()).filter { it.isDirectory && File(it, "__init__.py").isFile }) {
                for (src in (pkg.listFiles() ?: emptyArray()).filter { it.isDirectory && it.name.startsWith("sources") }) {
                    for (kindDir in (src.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith("_") }) {
                        val kind = if ("torrent" in kindDir.name.lowercase()) "torrent" else "hoster"
                        for (f in (kindDir.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".py") && !it.name.startsWith("_") }.sortedBy { it.name }) {
                            val n = f.name.removeSuffix(".py")
                            val def = defaults["provider.$n"]?.equals("true", true) ?: true
                            out += Ref("${addon.name}:$n", addon.name, f, pretty(n), kind, def)
                        }
                    }
                }
            }
        }
        return out.distinctBy { it.id }
    }

    /** Every setting id of the module's resources/settings.xml with its default (Kodi's old and new formats). */
    private fun settingDefaults(addon: File): Map<String, String> {
        val f = File(addon, "resources/settings.xml").takeIf { it.isFile } ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        try {
            val p = ScraperRepos.xmlReader(f.readText())
            var current: String? = null
            while (p.hasNext()) {
                val ev = p.next()
                if (ev == XMLStreamConstants.START_ELEMENT) when (p.localName) {
                    "setting" -> {
                        val id = p.getAttributeValue(null, "id")
                        current = id
                        if (id != null && p.getAttributeValue(null, "type") != "action") out[id] = p.getAttributeValue(null, "default").orEmpty()
                    }
                    "default" -> current?.let { id -> if (id in out) out[id] = p.elementText.trim() }
                } else if (ev == XMLStreamConstants.END_ELEMENT && p.localName == "setting") current = null
            }
        } catch (e: Exception) {
            ScraperLog.w("${addon.name} settings unreadable (${e.javaClass.simpleName})")
        }
        return out
    }

    private fun readSettings(f: File): Map<String, String> {
        if (!f.isFile) return emptyMap()
        val out = LinkedHashMap<String, String>()
        try {
            val p = ScraperRepos.xmlReader(f.readText())
            while (p.hasNext()) {
                if (p.next() == XMLStreamConstants.START_ELEMENT && p.localName == "setting") {
                    val id = p.getAttributeValue(null, "id") ?: continue
                    out[id] = p.getAttributeValue(null, "value") ?: p.elementText
                }
            }
        } catch (e: Exception) { }
        return out
    }

    /**
     * The module's userdata settings.xml as Kodi keeps it (every setting, version 2 format): its defaults, what it
     * saved itself, then Dream OS's provider switches; its debug log off.
     */
    private fun writeSettings(addonId: String) {
        try {
            val addon = File(paths.addons, addonId)
            if (!addon.isDirectory) return
            val dir = paths.profileDir(addonId).apply { mkdirs() }
            val f = File(dir, "settings.xml")
            val values = LinkedHashMap(settingDefaults(addon))
            values.putAll(readSettings(f))
            for (r in refs().filter { it.addon == addonId }) values["provider.${r.id.substringAfter(':')}"] = enabled(r).toString()
            if ("debug.enabled" in values) values["debug.enabled"] = "false"
            val sb = StringBuilder("<settings version=\"2\">\n")
            for ((k, v) in values) sb.append("    <setting id=\"").append(esc(k)).append("\">").append(esc(v)).append("</setting>\n")
            sb.append("</settings>\n")
            writeAtomically(f, sb.toString())
        } catch (e: Exception) {
            ScraperLog.w("couldn't write $addonId settings ($e)")
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // ---- state.json ----

    private fun loadState() {
        try {
            val f = paths.stateFile
            if (!f.isFile) return
            val o = Json.parseToJsonElement(f.readText()) as? JsonObject ?: return
            (o["on"] as? JsonObject)?.forEach { (k, v) -> (v as? JsonPrimitive)?.booleanOrNull?.let { switches[k] = it } }
            recApplied = (o["recApplied"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()
            (o["stats"] as? JsonObject)?.forEach { (k, v) ->
                val a = v as? JsonArray ?: return@forEach
                stats[k] = Stat(a.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: 0.0, a.getOrNull(1)?.jsonPrimitive?.intOrNull ?: 0,
                    a.getOrNull(2)?.jsonPrimitive?.intOrNull ?: 0)
            }
        } catch (e: Exception) {
            ScraperLog.w("state.json unreadable ($e)")
        }
    }

    private fun saveState() {
        synchronized(stateLock) {
            statsDirty = 0
            val o = buildJsonObject {
                put("on", JsonObject(switches.mapValues { JsonPrimitive(it.value) }))
                recApplied?.let { s -> put("recApplied", JsonArray(s.map { JsonPrimitive(it) })) }
                put("stats", JsonObject(stats.mapValues { (_, s) ->
                    synchronized(s) { buildJsonArray { add(JsonPrimitive(s.ms.toLong())); add(JsonPrimitive(s.hits)); add(JsonPrimitive(s.misses)) } }
                }))
            }
            try { writeAtomically(paths.stateFile, o.toString()) } catch (e: IOException) { ScraperLog.w("state.json not saved ($e)") }
        }
    }

    private fun writeAtomically(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    // ---- provider speed (the quick check uses the fastest) ----

    internal fun recordStat(providerId: String, ms: Int, count: Int, error: String?) {
        val s = stats.getOrPut(providerId) { Stat(ms.toDouble(), 0, 0) }
        synchronized(s) {
            s.ms = s.ms * 0.7 + ms * 0.3
            if (count > 0) s.hits++ else s.misses++
            if (s.hits + s.misses > 40) { s.hits /= 2; s.misses /= 2 }
        }
        if (++statsDirty >= 10) timer.execute { saveState() }
    }

    /** Index-style providers that usually answer in well under a second, for the quick check before stats exist. */
    private val QUICK = listOf("torrentio", "knaben", "bitsearch", "piratebay", "eztv", "ytsmx", "torrentdownload", "1337x")

    /** The quick check's providers: the [n] enabled ones that answered with sources and fastest lately. */
    fun quickRefs(n: Int = 2): List<Ref> {
        val on = enabledRefs().filter { it.kind == "torrent" }
        fun score(r: Ref): Double {
            val s = stats[r.id]
            val q = QUICK.indexOf(r.id.substringAfter(':')).let { if (it < 0) 50 else it }
            if (s == null) return 5_000.0 + q * 100
            val (ms, tries, miss) = synchronized(s) { Triple(s.ms, s.hits + s.misses, s.misses) }
            val rate = if (tries == 0) 0.0 else miss.toDouble() / tries
            return ms + rate * 8_000 + q
        }
        return on.sortedBy { score(it) }.take(n)
    }

    /** What the engine measured for one provider: average answer time (ms, smoothed) and recent answers with / without sources. */
    data class Speed(val ms: Long, val hits: Int, val misses: Int) {
        val tries: Int get() = hits + misses
        val hitRate: Double get() = if (tries == 0) 0.0 else hits.toDouble() / tries
    }

    fun speed(providerId: String): Speed? = stats[providerId]?.let { s -> synchronized(s) { Speed(s.ms.toLong(), s.hits, s.misses) } }

    // ---- Use Recommended Settings ----

    /** CocoScrapers' fast indexes and dependable backups (Nyaa only ever gets anime titles). Everything else off. */
    private val COCO_ON = setOf("torrentio", "knaben", "bitsearch", "1337x", "torrentgalaxy", "eztv", "ytsmx", "nyaa")
    /** Providers that do nothing until they get their own server, key or account. */
    private val NEEDS_SETUP = setOf("comet", "mediafusion", "aiostreams", "prowlarr", "zilean", "dmm", "bitmagnet", "meteor")
    /** General torrent sites: work, but slow to answer. */
    private val GENERAL_SITES = setOf("piratebay", "limetorrents", "torlock", "torrentdownload", "torrentproject2", "torrentsdb", "torrentz2",
        "torz", "kickass2", "torrentquest", "yourbittorrent", "isohunt2", "bitcq", "bitlord", "torrentfunk")

    private fun recommendedOn(r: Ref): Boolean {
        val n = r.id.substringAfter(':').lowercase()
        if ("cocoscrapers" in r.addon.lowercase()) return n in COCO_ON
        if (n in NEEDS_SETUP) return false
        val s = speed(r.id)
        if (s != null && s.tries >= 3) return s.hitRate >= 0.4 && s.ms <= 5_000
        return r.defaultOn
    }

    /** The provider ids "Use Recommended Settings" turns on (every other one goes off). */
    fun recommended(): Set<String> = refs().filter { recommendedOn(it) }.map { it.id }.toSet()

    /** The switches match the recommended set: the one last applied, or the current recommendation when never applied. */
    fun isRecommended(): Boolean {
        val all = refs().map { it.id }.toSet()
        if (all.isEmpty()) return false
        val target = recApplied?.intersect(all) ?: recommended()
        return enabledRefs().map { it.id }.toSet() == target
    }

    /** Turns the recommended providers on and every other one off. Any thread. */
    fun applyRecommended() {
        val target = recommended()
        recApplied = target
        for (r in refs()) { val want = r.id in target; if (enabled(r) != want) setEnabled(r.id, want) }
        saveState()
        ScraperLog.w("recommended settings on (${target.size} of ${refs().size} providers)")
        changed()
    }

    /** The short line under a provider's name: what it is, at a glance. Null for one Dream OS doesn't know. */
    fun caption(providerId: String): String? = when (providerId.substringAfter(':').lowercase()) {
        "torrentio", "knaben", "bitsearch" -> "Fast Index · Recommended"
        "1337x", "torrentgalaxy" -> "Torrent Site · Backup"
        "eztv" -> "TV Episodes"
        "ytsmx" -> "Movies · Small Files"
        "nyaa" -> "Anime"
        "rutor" -> "Russian Site"
        in NEEDS_SETUP -> "Needs Its Own Setup"
        in GENERAL_SITES -> "Torrent Site"
        else -> null
    }

    /** A provider's display name from its id ("<addon>:<name>"). */
    fun providerName(providerId: String): String = pretty(providerId.substringAfter(':'))

    // ---- the setup page's check ----

    /**
     * Blocking: a real search for a well-known film through the installed scrapers. [readiness] is Dream Core's
     * debrid check (how many of the found hashes are ready on the linked accounts; null when no account is linked).
     */
    fun test(sources: ScraperSources, readiness: (List<String>) -> Int?, progress: (String) -> Unit = {}): TestResult {
        val t0 = System.currentTimeMillis()
        fun ms() = System.currentTimeMillis() - t0
        if (recs().isEmpty()) return TestResult(false, 0, 0, 0, "Add a source first.")
        if (enabledRefs().isEmpty()) return TestResult(false, 0, 0, 0, "Turn on at least one provider first.")
        progress("Starting Up…")
        if (!engine.ensure()) return TestResult(false, 0, 0, ms(), "Sources can't run on this device right now. Restart Dream OS and try again.")
        progress("Searching…")
        val found = sources.findBlocking(TEST_REQUEST, 25_000)
        if (found.isEmpty()) return TestResult(false, 0, 0, ms(), "No sources came back. Check your internet connection and try again.")
        val ready = readiness(found.map { it.hash })
            ?: return TestResult(false, found.size, 0, ms(), "Found ${found.size} sources. Link a streaming account to play them.")
        val msg = when (ready) {
            0 -> "Found ${found.size} sources, but none are ready on your account right now."
            1 -> "Found ${found.size} sources. 1 is ready to play."
            else -> "Found ${found.size} sources. $ready are ready to play."
        }
        ScraperLog.w("test: ${found.size} found, $ready ready in ${ms()} ms")
        return TestResult(true, found.size, ready, ms(), msg)
    }

    companion object {
        val shared: Scrapers by lazy { Scrapers(ScraperPaths.default()) }

        internal val TEST_REQUEST = com.dreamos.desktop.core.playback.PlayTarget(com.dreamos.desktop.core.playback.MediaKind.MOVIE, 603, "The Matrix", year = 1999, imdb = "tt0133093")

        private val NAMES = mapOf(
            "1337x" to "1337x", "ytsmx" to "YTS", "eztv" to "EZTV", "piratebay" to "The Pirate Bay", "torrentio" to "Torrentio",
            "bitsearch" to "BitSearch", "knaben" to "Knaben", "torrentgalaxy" to "TorrentGalaxy", "kickass2" to "Kickass", "nyaa" to "Nyaa",
            "torrentdownload" to "TorrentDownload", "torrentproject2" to "TorrentProject", "torrentquest" to "TorrentQuest",
            "yourbittorrent" to "YourBittorrent", "limetorrents" to "LimeTorrents", "torlock" to "Torlock", "torrentz2" to "Torrentz2",
            "rutor" to "Rutor", "mediafusion" to "MediaFusion", "comet" to "Comet", "aiostreams" to "AIOStreams", "bitmagnet" to "Bitmagnet",
            "dmm" to "Debrid Media Manager", "meteor" to "Meteor", "prowlarr" to "Prowlarr", "torrentsdb" to "TorrentsDB", "torz" to "Torz",
            "zilean" to "Zilean", "isohunt2" to "isoHunt", "bitcq" to "BitCQ", "bitlord" to "BitLord", "torrentfunk" to "TorrentFunk",
        )

        internal fun pretty(n: String) = NAMES[n.lowercase()] ?: n.replace('_', ' ').split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }

        /** "CocoScrapers Module" -> "CocoScrapers"; Kodi formatting tags dropped. */
        fun friendly(name: String): String = name.replace(Regex("\\[/?[A-Za-z]+[^\\]]*]"), "").trim()
            .removeSuffix(" Module").removeSuffix(" module").removePrefix("Script.Module.").trim().ifEmpty { "Source" }
    }
}
