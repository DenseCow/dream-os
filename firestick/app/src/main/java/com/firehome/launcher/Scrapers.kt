package com.firehome.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Sources: the scraper repositories the user adds (Kodi repository format, e.g. CocoScrapers), whose
 * providers Dream OS runs itself in an embedded Python ([ScraperEngine]) to find streams; the debrid
 * check and Play stay Dream Core's ([NativeResolver]). Dream OS ships no sources of its own.
 *
 * Layout (app-private): files/scrapers/repos.json (what was added and from where),
 * files/scrapers/home/addons/<id>/ (installed modules, as Kodi lays them out),
 * files/scrapers/home/userdata/addon_data/<id>/settings.xml (each module's settings: its own defaults
 * with the user's provider choices on top, rewritten by Dream OS), prefs "dream_scrapers" (provider
 * on/off, speed stats).
 *
 * Public API: the setup pages' contract (status, add / update / remove, provider switches, test).
 */
object Scrapers {
    data class Repo(val url: String, val id: String, val name: String, val version: String)
    data class Provider(val id: String, val name: String, val kind: String /* "torrent" | "hoster" */, val enabled: Boolean)
    data class Status(val repos: List<Repo>, val providers: List<Provider>, val pythonReady: Boolean) {
        val ready: Boolean get() = repos.isNotEmpty() && providers.any { it.enabled }
    }
    sealed class AddResult {
        data class Ok(val repo: Repo, val modules: List<String>) : AddResult()
        data class Failed(val message: String) : AddResult() // user-facing, plain sentence, no jargon
    }
    data class TestResult(val ok: Boolean, val found: Int, val ready: Int, val ms: Long, val message: String)

    private const val TAG = "FireHome"
    private const val PREFS = "dream_scrapers"

    /** One provider file inside an installed module, for the engine. */
    internal class Ref(val id: String, val addon: String, val file: File, val name: String, val kind: String, val defaultOn: Boolean)

    /** A repository the user added: where it came from and which modules it installed (id -> version). */
    private class Rec(val url: String, val source: ScraperRepos.Source, val modules: Map<String, String>) {
        fun json(): JSONObject = source.json(url).put("modules", JSONObject(modules))
        fun repo(): Repo = Repo(url, source.id, friendly(source.name),
            source.version.ifEmpty { modules.values.firstOrNull().orEmpty() })
        companion object {
            fun from(o: JSONObject): Rec {
                val m = o.optJSONObject("modules") ?: JSONObject()
                return Rec(o.optString("url"), ScraperRepos.Source.from(o), m.keys().asSequence().associateWith { m.optString(it) })
            }
        }
    }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val installLock = Any()
    @Volatile private var recs: List<Rec>? = null
    @Volatile private var refs: List<Ref>? = null
    @Volatile private var stampValue = 0L
    @Volatile private var appCtx: Context? = null

    // ---- paths ----

    internal fun homeDir(ctx: Context) = File(ctx.applicationContext.filesDir, "scrapers/home").apply { mkdirs() }
    private fun addonsDir(ctx: Context) = File(homeDir(ctx), "addons").apply { mkdirs() }
    private fun profileDir(ctx: Context, id: String) = File(homeDir(ctx), "userdata/addon_data/$id")
    private fun reposFile(ctx: Context) = File(ctx.applicationContext.filesDir, "scrapers/repos.json")
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Changes whenever what's installed changes (the engine reloads its modules when it differs). */
    internal fun stamp() = stampValue

    // ---- status ----

    private fun recs(ctx: Context): List<Rec> = recs ?: synchronized(this) {
        recs ?: try {
            val f = reposFile(ctx)
            if (!f.isFile) emptyList() else JSONArray(f.readText()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Rec::from) } }
        } catch (e: Exception) {
            Log.w(TAG, "Scrapers: repos.json unreadable ($e)")
            // Copied aside before the next save overwrites it: the viewer's repository list can still be recovered.
            try {
                val f = reposFile(ctx)
                val aside = File(f.path + ".unreadable")
                if (f.isFile && !aside.exists()) f.copyTo(aside)
            } catch (x: Exception) { /* best effort */ }
            emptyList()
        }.also { recs = it }
    }

    private fun saveRecs(ctx: Context, list: List<Rec>) {
        val f = reposFile(ctx)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "repos.json.tmp")
        tmp.writeText(JSONArray(list.map { it.json() }).toString())
        tmp.renameTo(f)
        recs = list
    }

    /** Every provider of the installed modules (filesystem scan, cached until something is installed or removed). */
    internal fun refs(ctx: Context): List<Ref> = refs ?: synchronized(this) {
        refs ?: scan(ctx).also { refs = it }
    }

    private fun enabled(p: android.content.SharedPreferences, r: Ref) = p.getBoolean("on.${r.id}", r.defaultOn)

    fun status(ctx: Context): Status {
        appCtx = ctx.applicationContext
        val p = prefs(ctx)
        val providers = refs(ctx).map { Provider(it.id, it.name, it.kind, enabled(p, it)) }
        return Status(recs(ctx).map { it.repo() }, providers, ScraperEngine.ready())
    }

    /** Installed, with providers switched on, and Python can run here. Cheap; any thread. */
    internal fun active(ctx: Context): Boolean = !ScraperEngine.broken() && recs(ctx).isNotEmpty() && enabledRefs(ctx).isNotEmpty()

    internal fun enabledRefs(ctx: Context): List<Ref> { val p = prefs(ctx); return refs(ctx).filter { enabled(p, it) } }

    /** The installed modules' Python folders (every installed add-on with a Python module), for sys.path. */
    internal fun libDirs(ctx: Context): List<File> = (addonsDir(ctx).listFiles() ?: emptyArray()).filter { it.isDirectory }.sortedBy { it.name }
        .mapNotNull { d -> moduleLib(d)?.let { File(d, it) }?.takeIf { it.isDirectory } }

    /** Python is warmed in the background when a source is installed (app start, before Play). */
    internal fun warm(ctx: Context) {
        if (recs(ctx).isNotEmpty() && enabledRefs(ctx).isNotEmpty()) ScraperEngine.warm(ctx)
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun changed() {
        main.post { for (l in listeners) try { l() } catch (e: Exception) { Log.w(TAG, "Scrapers: listener failed: $e") } }
    }

    private fun installedChanged(ctx: Context) {
        synchronized(this) { refs = null; stampValue++ }
        NativeResolver.scrapersChanged()
        warm(ctx)
        changed()
    }

    // ---- add / update / remove ----

    fun addRepository(ctx: Context, url: String, progress: (String) -> Unit): AddResult {
        val app = ctx.applicationContext
        appCtx = app
        return try {
            synchronized(installLock) {
                val u = ScraperRepos.normalize(url)
                progress("Checking Address…")
                val src = ScraperRepos.resolve(u, progress)
                val installed = LinkedHashMap<String, String>()
                val names = ArrayList<String>()
                if (src.direct != null) {
                    progress("Downloading…")
                    val bytes = ScraperRepos.download(src.direct, 40 shl 20)
                    progress("Installing ${friendly(src.name)}…")
                    ScraperRepos.unpack(bytes, src.id, addonsDir(app))
                    installed[src.id] = src.version
                    names += friendly(src.name)
                } else {
                    progress("Downloading…")
                    val (spec, entries) = ScraperRepos.firstIndex(src.specs)
                    val pick = ScraperRepos.pick(entries)
                    if (pick.isEmpty()) return AddResult.Failed(ScraperRepos.NOTHING)
                    for (e in pick) {
                        progress("Installing ${friendly(e.name)}…")
                        ScraperRepos.install(e, spec, addonsDir(app))
                        installed[e.id] = e.version
                        names += friendly(e.name)
                    }
                }
                synchronized(this) { refs = null }
                val found = refs(app).filter { it.addon in installed }
                if (found.isEmpty()) {
                    removeModules(app, installed.keys, keepFor = recs(app))
                    installedChanged(app)
                    return AddResult.Failed(ScraperRepos.NOTHING)
                }
                progress("Getting Ready…")
                installed.keys.forEach { writeSettings(app, it) }
                val rec = Rec(u, src, installed)
                saveRecs(app, recs(app).filter { it.source.id != src.id } + rec)
                installedChanged(app)
                Log.w(TAG, "Scrapers: added ${src.id} (${installed.entries.joinToString { "${it.key} ${it.value}" }}): ${found.size} providers")
                AddResult.Ok(rec.repo(), names.distinct())
            }
        } catch (e: ScraperRepos.UserError) {
            Log.w(TAG, "Scrapers: add failed: ${e.message}"); AddResult.Failed(e.message ?: ScraperRepos.NOTHING)
        } catch (e: IOException) {
            Log.w(TAG, "Scrapers: add failed: ${e.javaClass.simpleName} ${e.message}"); AddResult.Failed(ScraperRepos.UNREACHABLE)
        } catch (e: Exception) {
            Log.w(TAG, "Scrapers: add failed: $e"); AddResult.Failed("Something went wrong while adding that source. Try again.")
        }
    }

    fun updateAll(ctx: Context, progress: (String) -> Unit): AddResult {
        val app = ctx.applicationContext
        val list = recs(app)
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
                    if (src.direct != null) {
                        val bytes = ScraperRepos.download(src.direct!!, 40 shl 20)
                        val tmp = File(app.cacheDir, "scrapers-check").apply { deleteRecursively(); mkdirs() }
                        val dir = ScraperRepos.unpack(bytes, src.id, tmp)
                        val v = ScraperRepos.parseAddons(File(dir, "addon.xml").readText()).firstOrNull()?.version.orEmpty()
                        tmp.deleteRecursively()
                        if (ScraperRepos.compareVersions(v, mods[src.id].orEmpty()) > 0) {
                            progress("Installing ${friendly(src.name)}…")
                            ScraperRepos.unpack(bytes, src.id, addonsDir(app))
                            mods[src.id] = v; updated += friendly(src.name)
                            src = ScraperRepos.Source(src.id, src.name, v, src.specs, src.direct)
                        }
                    } else {
                        val (spec, entries) = ScraperRepos.firstIndex(src.specs)
                        // A newer repository add-on may have moved: Kodi follows its new addresses too.
                        entries.firstOrNull { it.id == src.id && it.specs.isNotEmpty() && ScraperRepos.compareVersions(it.version, src.version) > 0 }?.let {
                            src = ScraperRepos.Source(src.id, it.name, it.version, (it.specs + src.specs).distinctBy { s -> s.info })
                        }
                        for (e in ScraperRepos.pick(entries)) {
                            if (mods.containsKey(e.id) && ScraperRepos.compareVersions(e.version, mods[e.id].orEmpty()) <= 0 && File(addonsDir(app), e.id).isDirectory) continue
                            progress("Installing ${friendly(e.name)}…")
                            ScraperRepos.install(e, spec, addonsDir(app))
                            mods[e.id] = e.version
                            updated += friendly(e.name)
                        }
                    }
                    val r = Rec(rec.url, src, mods)
                    out += r
                    if (first == null) first = r.repo()
                } catch (e: Exception) {
                    out += rec
                    failure = (e as? ScraperRepos.UserError)?.message ?: if (e is IOException) ScraperRepos.UNREACHABLE else "Couldn't check for updates right now. Try again later."
                    Log.w(TAG, "Scrapers: update of ${rec.source.id} failed: ${e.javaClass.simpleName} ${e.message}")
                }
            }
            saveRecs(app, out)
            if (updated.isNotEmpty()) {
                out.flatMap { it.modules.keys }.forEach { writeSettings(app, it) }
                installedChanged(app)
                Log.w(TAG, "Scrapers: updated ${updated.joinToString()}")
            }
        }
        val f = first
        return if (f == null) AddResult.Failed(failure ?: "Couldn't check for updates right now. Try again later.") else AddResult.Ok(f, updated.distinct())
    }

    fun removeRepository(ctx: Context, repoId: String) {
        val app = ctx.applicationContext
        synchronized(installLock) {
            val list = recs(app)
            val gone = list.filter { it.source.id == repoId }
            if (gone.isEmpty()) return
            val keep = list - gone.toSet()
            saveRecs(app, keep)
            removeModules(app, gone.flatMap { it.modules.keys }.toSet(), keep)
            installedChanged(app)
            Log.w(TAG, "Scrapers: removed $repoId")
        }
    }

    /** Deletes [ids] (code, settings, provider switches) unless a repository in [keepFor] still lists them. */
    private fun removeModules(app: Context, ids: Set<String>, keepFor: List<Rec>) {
        val still = keepFor.flatMap { it.modules.keys }.toSet()
        val p = prefs(app).edit()
        for (id in ids - still) {
            File(addonsDir(app), id).deleteRecursively()
            profileDir(app, id).deleteRecursively()
            prefs(app).all.keys.filter { it.startsWith("on.$id:") }.forEach { p.remove(it) }
        }
        p.apply()
    }

    // ---- provider switches ----

    private val pendingSettings = ConcurrentHashMap.newKeySet<String>()

    fun setEnabled(ctx: Context, providerId: String, enabled: Boolean) {
        val app = ctx.applicationContext
        prefs(app).edit().putBoolean("on.$providerId", enabled).apply()
        val addon = providerId.substringBefore(':')
        // A burst of switches (Turn All On) writes each module's settings once.
        if (pendingSettings.add(addon)) main.postDelayed({
            pendingSettings.remove(addon)
            Net.run { writeSettings(app, addon); ScraperEngine.settingsChanged() }
        }, 400)
        NativeResolver.scrapersChanged()
        if (enabled) warm(app)
        changed()
    }

    // ---- the installed modules on disk ----

    /** The xbmc.python.module library folder of the add-on in [dir] ("lib"), or null if it isn't a Python module. */
    private fun moduleLib(dir: File): String? {
        val xml = File(dir, "addon.xml").takeIf { it.isFile }?.readText() ?: return null
        val tag = Regex("<extension\\b[^>]*point\\s*=\\s*\"xbmc\\.python\\.module\"[^>]*>").find(xml)?.value ?: return null
        val lib = Regex("library\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1) ?: "lib"
        return lib.trim('/').takeIf { it.isNotEmpty() && ".." !in it } ?: "."
    }

    /**
     * Providers, Fenomscrapers / CocoScrapers layout: <lib>/<package>/sources_<x>/<kind>/<name>.py
     * (kind folders "torrents", "hosters"...). Default on/off from the module's own provider.<name> setting.
     */
    private fun scan(ctx: Context): List<Ref> {
        val out = ArrayList<Ref>()
        for (addon in (addonsDir(ctx).listFiles() ?: emptyArray()).filter { it.isDirectory }.sortedBy { it.name }) {
            val lib = moduleLib(addon)?.let { File(addon, it) } ?: continue
            val defaults by lazy { settingDefaults(addon) }
            for (pkg in (lib.listFiles() ?: emptyArray()).filter { it.isDirectory && File(it, "__init__.py").isFile }) {
                for (src in (pkg.listFiles() ?: emptyArray()).filter { it.isDirectory && it.name.startsWith("sources") }) {
                    for (kindDir in (src.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith("_") }) {
                        val kind = if ("torrent" in kindDir.name.lowercase()) "torrent" else "hoster"
                        for (f in (kindDir.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".py") && !it.name.startsWith("_") }.sortedBy { it.name }) {
                            val n = f.name.removeSuffix(".py")
                            val def = defaults["provider.$n"]?.let { it.equals("true", true) } ?: true
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
        try { f.reader().use { reader ->
            val p = Xml.newPullParser()
            p.setInput(reader)
            var current: String? = null
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) break
                if (ev == XmlPullParser.START_TAG) when (p.name) {
                    "setting" -> {
                        val id = p.getAttributeValue(null, "id")
                        current = id
                        if (id != null && p.getAttributeValue(null, "type") != "action") out[id] = p.getAttributeValue(null, "default").orEmpty()
                    }
                    "default" -> current?.let { id -> if (id in out) out[id] = p.nextText().trim() }
                } else if (ev == XmlPullParser.END_TAG && p.name == "setting") current = null
            }
        } } catch (e: Exception) {
            Log.w(TAG, "Scrapers: ${addon.name} settings unreadable (${e.javaClass.simpleName})")
        }
        return out
    }

    private fun readSettings(f: File): Map<String, String> {
        if (!f.isFile) return emptyMap()
        val out = LinkedHashMap<String, String>()
        try { f.reader().use { reader ->
            val p = Xml.newPullParser()
            p.setInput(reader)
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) break
                if (ev == XmlPullParser.START_TAG && p.name == "setting") {
                    val id = p.getAttributeValue(null, "id") ?: continue
                    val v = p.getAttributeValue(null, "value")
                    out[id] = v ?: p.nextText()
                }
            }
        } } catch (e: Exception) { }
        return out
    }

    /**
     * The module's userdata settings.xml as Kodi keeps it (every setting, version 2 format): its defaults,
     * what it saved itself, then Dream OS's provider switches; its debug log off.
     */
    private fun writeSettings(ctx: Context, addonId: String) {
        try {
            val addon = File(addonsDir(ctx), addonId)
            if (!addon.isDirectory) return
            val dir = profileDir(ctx, addonId).apply { mkdirs() }
            val f = File(dir, "settings.xml")
            val values = LinkedHashMap(settingDefaults(addon))
            values.putAll(readSettings(f))
            val p = prefs(ctx)
            for (r in refs(ctx).filter { it.addon == addonId }) values["provider.${r.id.substringAfter(':')}"] = enabled(p, r).toString()
            if ("debug.enabled" in values) values["debug.enabled"] = "false"
            val sb = StringBuilder("<settings version=\"2\">\n")
            for ((k, v) in values) sb.append("    <setting id=\"").append(esc(k)).append("\">").append(esc(v)).append("</setting>\n")
            sb.append("</settings>\n")
            val tmp = File(dir, "settings.xml.tmp")
            tmp.writeText(sb.toString())
            tmp.renameTo(f)
        } catch (e: Exception) {
            Log.w(TAG, "Scrapers: couldn't write $addonId settings ($e)")
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // ---- names ----

    private val NAMES = mapOf(
        "1337x" to "1337x", "ytsmx" to "YTS", "eztv" to "EZTV", "piratebay" to "The Pirate Bay", "torrentio" to "Torrentio",
        "bitsearch" to "BitSearch", "knaben" to "Knaben", "torrentgalaxy" to "TorrentGalaxy", "kickass2" to "Kickass", "nyaa" to "Nyaa",
        "torrentdownload" to "TorrentDownload", "torrentproject2" to "TorrentProject", "torrentquest" to "TorrentQuest",
        "yourbittorrent" to "YourBittorrent", "limetorrents" to "LimeTorrents", "torlock" to "Torlock", "torrentz2" to "Torrentz2",
        "rutor" to "Rutor", "mediafusion" to "MediaFusion", "comet" to "Comet", "aiostreams" to "AIOStreams", "bitmagnet" to "Bitmagnet",
        "dmm" to "Debrid Media Manager", "meteor" to "Meteor", "prowlarr" to "Prowlarr", "torrentsdb" to "TorrentsDB", "torz" to "Torz",
        "zilean" to "Zilean", "isohunt2" to "isoHunt", "bitcq" to "BitCQ", "bitlord" to "BitLord", "torrentfunk" to "TorrentFunk",
    )

    private fun pretty(n: String) = NAMES[n.lowercase()] ?: n.replace('_', ' ').split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }

    /** "CocoScrapers Module" -> "CocoScrapers"; Kodi formatting tags dropped. */
    internal fun friendly(name: String): String = name.replace(Regex("\\[/?[A-Za-z]+[^\\]]*]"), "").trim()
        .removeSuffix(" Module").removeSuffix(" module").removePrefix("Script.Module.").trim().ifEmpty { "Source" }

    // ---- provider speed (the quick check uses the fastest) ----

    private class Stat(var ms: Double, var hits: Int, var misses: Int)
    private val stats = ConcurrentHashMap<String, Stat>()
    @Volatile private var statsLoaded = false
    @Volatile private var statsDirty = 0

    internal fun recordStat(providerId: String, ms: Int, count: Int, error: String?) {
        val s = stats.getOrPut(providerId) { Stat(ms.toDouble(), 0, 0) }
        synchronized(s) {
            s.ms = s.ms * 0.7 + ms * 0.3
            if (count > 0) s.hits++ else s.misses++
            if (s.hits + s.misses > 40) { s.hits /= 2; s.misses /= 2 }
        }
        if (++statsDirty >= 10) appCtx?.let { saveStats(it) }
    }

    private fun loadStats(ctx: Context) {
        if (statsLoaded) return
        statsLoaded = true
        try {
            val o = JSONObject(prefs(ctx).getString("stats", "{}") ?: "{}")
            for (k in o.keys()) o.optJSONArray(k)?.let { a -> stats.putIfAbsent(k, Stat(a.optDouble(0), a.optInt(1), a.optInt(2))) }
        } catch (e: Exception) { }
    }

    private fun saveStats(ctx: Context) {
        statsDirty = 0
        val o = JSONObject()
        for ((k, s) in stats) synchronized(s) { o.put(k, JSONArray().put(s.ms.toLong()).put(s.hits).put(s.misses)) }
        prefs(ctx).edit().putString("stats", o.toString()).apply()
    }

    /** Index-style providers that usually answer in well under a second, for the quick check before stats exist. */
    private val QUICK = listOf("torrentio", "knaben", "bitsearch", "piratebay", "eztv", "ytsmx", "torrentdownload", "1337x")

    /**
     * The quick check's providers: the [n] enabled ones that answered with sources and fastest lately
     * (or, before any stats, the known index-style ones), never ones that keep failing.
     */
    internal fun quickRefs(ctx: Context, n: Int = 2): List<Ref> {
        loadStats(ctx)
        val on = enabledRefs(ctx).filter { it.kind == "torrent" }
        fun score(r: Ref): Double {
            val s = stats[r.id]
            val q = QUICK.indexOf(r.id.substringAfter(':')).let { if (it < 0) 50 else it }
            if (s == null) return 5_000.0 + q * 100
            val tries = s.hits + s.misses
            val miss = if (tries == 0) 0.0 else s.misses.toDouble() / tries
            return s.ms + miss * 8_000 + q
        }
        return on.sortedBy { score(it) }.take(n)
    }

    /** What the engine measured for one provider: average answer time (ms, smoothed) and recent answers with / without sources. */
    data class Speed(val ms: Long, val hits: Int, val misses: Int) {
        val tries: Int get() = hits + misses
        val hitRate: Double get() = if (tries == 0) 0.0 else hits.toDouble() / tries
    }

    /** The engine's measured speed and success for [providerId] ("<addon>:<name>"), or null before it ran. */
    fun speed(ctx: Context, providerId: String): Speed? {
        loadStats(ctx)
        val s = stats[providerId] ?: return null
        return synchronized(s) { Speed(s.ms.toLong(), s.hits, s.misses) }
    }

    // ---- Use Recommended Settings ----

    /** CocoScrapers' fast indexes and dependable backups (Nyaa only ever gets anime titles). Everything else off. */
    private val COCO_ON = setOf("torrentio", "knaben", "bitsearch", "1337x", "torrentgalaxy", "eztv", "ytsmx", "nyaa")
    /** Providers that do nothing until they get their own server, key or account. */
    private val NEEDS_SETUP = setOf("comet", "mediafusion", "aiostreams", "prowlarr", "zilean", "dmm", "bitmagnet", "meteor")
    /** General torrent sites: work, but slow to answer. */
    private val GENERAL_SITES = setOf("piratebay", "limetorrents", "torlock", "torrentdownload", "torrentproject2", "torrentsdb", "torrentz2",
        "torz", "kickass2", "torrentquest", "yourbittorrent", "isohunt2", "bitcq", "bitlord", "torrentfunk")

    private fun isCoco(addon: String) = "cocoscrapers" in addon.lowercase()

    /**
     * Whether [r] belongs in the recommended set. CocoScrapers: the fixed list above. Any other module:
     * the engine's own numbers once a provider has answered a few times (answers with sources at least
     * 40% of the time, in 5 s or less on average), else the module's own default; never one that needs its own setup.
     */
    private fun recommendedOn(r: Ref): Boolean {
        val n = r.id.substringAfter(':').lowercase()
        if (isCoco(r.addon)) return n in COCO_ON
        if (n in NEEDS_SETUP) return false
        val s = stats[r.id]?.let { synchronized(it) { Speed(it.ms.toLong(), it.hits, it.misses) } }
        if (s != null && s.tries >= 3) return s.hitRate >= 0.4 && s.ms <= 5_000
        return r.defaultOn
    }

    /** The provider ids "Use Recommended Settings" turns on (every other one goes off). */
    fun recommended(ctx: Context): Set<String> {
        loadStats(ctx)
        return refs(ctx).filter { recommendedOn(it) }.map { it.id }.toSet()
    }

    /**
     * The switches match the recommended set: the one last applied (so measured speeds drifting later
     * don't flip it by themselves), or the current recommendation when it was never applied.
     */
    fun isRecommended(ctx: Context): Boolean {
        val all = refs(ctx).map { it.id }.toSet()
        if (all.isEmpty()) return false
        val target = prefs(ctx).getStringSet("rec.applied", null)?.intersect(all) ?: recommended(ctx)
        return enabledRefs(ctx).map { it.id }.toSet() == target
    }

    /** Turns the recommended providers on and every other one off. Any thread (settings files are written once, after). */
    fun applyRecommended(ctx: Context) {
        val app = ctx.applicationContext
        val target = recommended(app)
        prefs(app).edit().putStringSet("rec.applied", target).apply()
        val p = prefs(app)
        for (r in refs(app)) { val want = r.id in target; if (enabled(p, r) != want) setEnabled(app, r.id, want) }
        Log.w(TAG, "Scrapers: recommended settings on (${target.size} of ${refs(app).size} providers)")
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

    // ---- the title as scraper modules expect it ----

    private class Info(val json: JSONObject, val at: Long = SystemClock.elapsedRealtime())
    private val infos = ConcurrentHashMap<String, Info>()

    /**
     * Blocking. What a Kodi-format scraper module expects from its caller: imdb, tmdb, title, localtitle, aliases, year, and for an
     * episode tvshowtitle, season, episode, premiered (its air date), tvdb, total_seasons. Built from TMDB
     * (details are cached already; alternative titles and the episode's date get at most [waitMs]).
     */
    internal fun info(ctx: Context, req: PlaybackRequest, imdb: String, waitMs: Long = 900): JSONObject {
        val ep = req.type == "episode"
        val key = if (ep) "tv:${req.tmdbId}:${req.season}:${req.episode}" else "movie:${req.tmdbId}:$imdb"
        infos[key]?.takeIf { SystemClock.elapsedRealtime() - it.at < 6 * 3_600_000L }?.let { return it.json }
        val app = ctx.applicationContext
        val type = if (ep) "tv" else "movie"
        val id = req.tmdbId
        val t0 = SystemClock.elapsedRealtime()
        fun <T> later(work: () -> T?): FutureTask<T?> = FutureTask { try { work() } catch (e: Exception) { null } }.also { Thread(it, "scrapers-info").start() }
        val alt = if (id > 0) later { WatchHistory.tmdb(app, "/$type/$id/alternative_titles") } else null
        val epi = if (ep && id > 0) later { WatchHistory.seasonEpisodes(app, id, req.season ?: 1)?.firstOrNull { it.optInt("episode_number") == req.episode } } else null
        val d = if (id > 0) try { WatchHistory.details(app, type, id) } catch (e: Exception) { null } else null
        fun <T> FutureTask<T?>?.take(): T? = try { this?.get((waitMs - (SystemClock.elapsedRealtime() - t0)).coerceAtLeast(1), TimeUnit.MILLISECONDS) } catch (e: Exception) { null }
        val o = JSONObject().put("imdb", imdb).put("tmdb", if (id > 0) id.toString() else "")
        val title = if (ep) d?.optString("name").orEmpty().ifEmpty { req.title } else d?.optString("title").orEmpty().ifEmpty { req.title }
        val original = d?.optString(if (ep) "original_name" else "original_title").orEmpty()
        val year = req.year?.toString() ?: d?.optString(if (ep) "first_air_date" else "release_date").orEmpty().take(4)
        val aliases = JSONArray()
        val seen = HashSet<String>().apply { add(title.lowercase()) }
        fun alias(t: String, country: String) { if (t.isNotBlank() && seen.add(t.lowercase()) && aliases.length() < 12) aliases.put(JSONObject().put("title", t).put("country", country)) }
        alias(original, d?.optJSONArray("origin_country")?.optString(0)?.lowercase() ?: "")
        alt.take()?.let { a ->
            val list = a.optJSONArray("titles") ?: a.optJSONArray("results")
            if (list != null) for (i in 0 until list.length()) list.optJSONObject(i)?.let { t ->
                val c = t.optString("iso_3166_1")
                if (c in setOf("US", "GB", "CA", "AU", "NZ", "IE")) alias(t.optString("title"), c.lowercase())
            }
        }
        o.put("year", year).put("aliases", aliases)
        if (ep) {
            val e = epi.take()
            o.put("tvshowtitle", title).put("title", req.episodeTitle ?: e?.optString("name")?.ifEmpty { null } ?: "Episode ${req.episode}")
                .put("localtitle", title).put("season", req.season.toString()).put("episode", req.episode.toString())
                .put("premiered", e?.optString("air_date").orEmpty())
                .put("tvdb", d?.optJSONObject("external_ids")?.optInt("tvdb_id")?.takeIf { it > 0 }?.toString() ?: "")
            d?.optInt("number_of_seasons")?.takeIf { it > 0 }?.let { o.put("total_seasons", it) }
        } else {
            o.put("title", title).put("localtitle", title)
        }
        if (alt?.isDone == true && (!ep || epi?.isDone == true)) {
            infos[key] = Info(o)
            if (infos.size > 60) infos.entries.removeAll { SystemClock.elapsedRealtime() - it.value.at > 3_600_000L }
        }
        return o
    }

    // ---- the setup page's test ----

    /** Blocking: a real search for a well-known film through the installed scrapers and the linked account(s). */
    fun test(ctx: Context, progress: (String) -> Unit): TestResult {
        val app = ctx.applicationContext
        val t0 = SystemClock.elapsedRealtime()
        fun ms() = SystemClock.elapsedRealtime() - t0
        if (recs(app).isEmpty()) return TestResult(false, 0, 0, 0, "Add a source first.")
        if (enabledRefs(app).isEmpty()) return TestResult(false, 0, 0, 0, "Turn on at least one provider first.")
        progress("Starting Up…")
        if (ScraperEngine.ensure(app) == null) return TestResult(false, 0, 0, ms(), "Sources can't run on this device right now. Restart Dream OS and try again.")
        progress("Searching…")
        val req = PlaybackRequest(title = "The Matrix", type = "movie", tmdbId = 603, imdb = "tt0133093", year = 1999)
        val r = NativeResolver.testScrape(app, req, 25_000)
        if (r.found == 0) return TestResult(false, 0, 0, ms(), "No sources came back. Check your internet connection and try again.")
        if (!r.account) return TestResult(false, r.found, 0, ms(), "Found ${r.found} sources. Link a streaming account to play them.")
        val ready = r.ready
        val msg = when {
            ready == 0 -> "Found ${r.found} sources, but none are ready on your account right now."
            ready == 1 -> "Found ${r.found} sources. 1 is ready to play."
            else -> "Found ${r.found} sources. $ready are ready to play."
        }
        Log.w(TAG, "Scrapers: test: ${r.found} found, $ready ready in ${ms()} ms")
        return TestResult(true, r.found, ready, ms(), msg)
    }
}
