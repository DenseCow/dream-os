package com.dreamos.desktop.scrapers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * Kodi-style add-on repositories for [Scrapers] (port of fire-home's ScraperRepos.kt), read the way Kodi reads
 * them: a repository add-on (repository.x.zip) names its addons.xml (<info>), the checksum next to it (<checksum>,
 * MD5) and where the zips live (<datadir>, laid out as <id>/<id>-<version>.zip). What the viewer types may be a
 * GitHub repository page, a repository zip, a single add-on zip, an addons.xml, or a web folder listing a
 * repository zip. HTTPS only (http:// is upgraded), sizes capped (a download stops the moment it passes its cap),
 * zips unpacked safely, XML read with DTDs and external entities off.
 *
 * Downloaded files are untrusted: nothing here runs them; [Scrapers] only lists their providers.
 */
object ScraperRepos {
    private const val MAX_INDEX = 8 shl 20
    const val MAX_ZIP = 40 shl 20
    private const val MAX_UNZIPPED = 120L shl 20
    private const val MAX_ENTRIES = 6000
    private const val MAX_PAGE = 3 shl 20

    /** A viewer-facing reason: a plain sentence (same words as the Fire Stick and iPhone). */
    class UserError(message: String) : Exception(message)

    const val UNREACHABLE = "Dream OS couldn't reach that address. Check your internet connection and the address, then try again."
    const val NOTHING = "No scrapers were found at that address. Check the address and try again."
    const val BAD_ADDRESS = "That doesn't look like a web address. Check it and try again."
    const val TOO_LARGE = "That download is too large."
    const val UNSAFE = "That download isn't safe to install."
    const val INSECURE = "That source uses an insecure connection, so Dream OS can't use it."

    /** Kodi's own and what Dream OS provides itself (the requests stack ships with the app). */
    private val BUILT_IN = setOf(
        "xbmc.python", "xbmc.gui", "xbmc.json", "xbmc.metadata", "xbmc.addon", "xbmc.core", "kodi.resource", "kodi.binary.global.main",
        "script.module.requests", "script.module.urllib3", "script.module.chardet", "script.module.charset_normalizer",
        "script.module.idna", "script.module.certifi",
    )

    fun builtIn(id: String) = id in BUILT_IN || id.startsWith("xbmc.") || id.startsWith("kodi.")

    /** Where a repository's index lives. */
    data class Spec(val info: String, val checksum: String?, val datadir: String, val zip: Boolean = true, val compressed: Boolean = false) {
        fun json(): JsonObject = buildJsonObject {
            put("info", JsonPrimitive(info)); put("checksum", JsonPrimitive(checksum ?: "")); put("datadir", JsonPrimitive(datadir))
            put("zip", JsonPrimitive(zip)); put("gz", JsonPrimitive(compressed))
        }
        companion object {
            fun from(o: JsonObject) = Spec(o.s("info"), o.s("checksum").ifEmpty { null }, o.s("datadir"),
                o["zip"]?.jsonPrimitive?.booleanOrNull ?: true, o["gz"]?.jsonPrimitive?.booleanOrNull ?: false)
        }
    }

    /** One add-on in an addons.xml (or a single addon.xml). */
    data class Entry(
        val id: String, val name: String, val version: String,
        val requires: List<Pair<String, Boolean>>,   // id, optional
        val module: Boolean,                          // has xbmc.python.module
        val specs: List<Spec>,                        // a repository add-on's <dir>s
    )

    /** What an address turned out to be. [specs] in the order to try; [direct]: a single add-on zip's address. */
    data class Source(val id: String, val name: String, val version: String, val specs: List<Spec>, val direct: String? = null) {
        fun json(url: String): JsonObject = buildJsonObject {
            put("url", JsonPrimitive(url)); put("id", JsonPrimitive(id)); put("name", JsonPrimitive(name)); put("version", JsonPrimitive(version))
            put("specs", JsonArray(specs.map { it.json() })); put("direct", JsonPrimitive(direct ?: ""))
        }
        companion object {
            fun from(o: JsonObject): Source = Source(o.s("id"), o.s("name"), o.s("version"),
                (o["specs"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(Spec::from) }.orEmpty(), o.s("direct").ifEmpty { null })
        }
    }

    internal fun JsonObject.s(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    // ---- what the address is ----

    /** Normalized https address, or a [UserError]. */
    fun normalize(raw: String): String {
        var u = raw.trim().trim('"', '\'', '<', '>')
        if (u.isEmpty() || ' ' in u) throw UserError(BAD_ADDRESS)
        if (u.startsWith("http://", true)) u = "https://" + u.substring(7)
        else if (!u.startsWith("https://", true)) {
            if ("://" in u) throw UserError(BAD_ADDRESS)
            u = "https://$u"
        }
        val host = try { URL(u).host.orEmpty() } catch (e: Exception) { "" }
        if (!host.contains('.')) throw UserError(BAD_ADDRESS)
        return u
    }

    /** Blocking. What [url] (already normalized) points at, as a repository to install from. */
    fun resolve(url: String): Source {
        val path = URL(url).path.orEmpty()
        val lower = path.lowercase()
        val gh = Regex("^https://(?:www\\.)?github\\.com/([^/]+)/([^/#?]+)(?:/tree/([^/]+)(/.*)?)?/?$", RegexOption.IGNORE_CASE)
            .find(url.substringBefore('?').substringBefore('#'))
        return when {
            lower.endsWith(".zip") -> fromZip(url, download(url, MAX_ZIP))
            lower.endsWith(".xml") || lower.endsWith(".xml.gz") -> fromIndexUrl(url)
            gh != null && "/releases" !in path && "/blob/" !in path ->
                fromGitHub(gh.groupValues[1], gh.groupValues[2].removeSuffix(".git"), gh.groupValues[3].ifEmpty { null }, gh.groupValues[4].trim('/').ifEmpty { null })
            else -> fromPage(url)
        }
    }

    /** A zip: a repository add-on (its <dir>s), or a scraper module itself. */
    private fun fromZip(url: String, bytes: ByteArray): Source {
        val xml = addonXmlFromZip(bytes) ?: throw UserError(NOTHING)
        val e = parseAddons(xml).firstOrNull() ?: throw UserError(NOTHING)
        return if (e.specs.isNotEmpty()) Source(e.id, e.name, e.version, e.specs)
        else Source(e.id, e.name, e.version, emptyList(), direct = url)
    }

    private fun fromIndexUrl(url: String): Source {
        val dir = url.substringBeforeLast('/') + "/"
        val gz = url.endsWith(".gz", true)
        val spec = Spec(url, (if (gz) url.removeSuffix(".gz") else url) + ".md5", dir, compressed = gz)
        index(spec) // reachable and readable, or throws
        val u = URL(url)
        return Source("site.${u.host}" + u.path.substringBeforeLast('/').replace('/', '.').trimEnd('.'), u.host, "", listOf(spec))
    }

    /**
     * A GitHub repository: its file list (one API call) shows the repository zip (preferred: its <info> is where the
     * owner publishes updates) and any addons.xml (the fallback, also used when the API can't be asked: then the
     * usual places are tried directly).
     */
    private fun fromGitHub(owner: String, repo: String, ref: String?, sub: String?): Source {
        val branch = ref ?: "HEAD"
        val raw = "https://raw.githubusercontent.com/$owner/$repo/$branch/"
        val paths: List<String>? = try {
            val r = String(download("https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1", MAX_PAGE,
                mapOf("Accept" to "application/vnd.github+json")), Charsets.UTF_8)
            val tree = Json.parseToJsonElement(r).jsonObject["tree"] as? JsonArray ?: JsonArray(emptyList())
            tree.mapNotNull { (it as? JsonObject)?.takeIf { o -> o.s("type") == "blob" }?.s("path") }
                .filter { sub == null || it.startsWith("$sub/") || it == sub }
        } catch (e: UserError) { throw e } catch (e: Exception) {
            ScraperLog.w("GitHub file list unavailable for $owner/$repo (${e.javaClass.simpleName}); trying the usual places")
            null
        }
        val indexes = (paths?.filter { it.endsWith("addons.xml") }?.sortedBy { it.count { c -> c == '/' } }
            ?: listOf("zips/addons.xml", "addons.xml", "repo/zips/addons.xml", "_zips/addons.xml", "docs/addons.xml", "repo/addons.xml"))
        val zipRe = Regex("(?:^|/)(repository\\.[^/]+?)-([0-9][^/]*)\\.zip$")
        val repoZip = paths?.mapNotNull { p -> zipRe.find(p)?.let { Triple(p, it.groupValues[1], it.groupValues[2]) } }
            ?.groupBy { it.second }?.values?.map { v -> v.maxWithOrNull { a, b -> compareVersions(a.third, b.third) }!! }
            ?.sortedByDescending { it.second.contains(repo.removePrefix("repository.").lowercase()) }?.firstOrNull()
        var fromRepo: Source? = null
        if (repoZip != null) {
            try {
                val s = fromZip(raw + repoZip.first, download(raw + repoZip.first, MAX_ZIP))
                if (s.specs.isNotEmpty()) fromRepo = s
            } catch (e: UserError) {
            } catch (e: Exception) {
                ScraperLog.w("repository zip unreadable (${e.javaClass.simpleName})")
            }
        }
        // The repository's own addresses first, then the addons.xml right here as a fallback.
        val local = indexes.map { p -> Spec(raw + p, "$raw$p.md5", raw + p.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }) }
        val specs = (fromRepo?.specs.orEmpty() + local).distinctBy { it.info }
        val working = specs.firstOrNull { s -> try { index(s); true } catch (e: Exception) { false } }
            ?: throw UserError(if (paths == null) UNREACHABLE else NOTHING)
        val ordered = listOf(working) + specs.filter { it !== working }.take(2)
        return fromRepo?.let { Source(it.id, it.name, it.version, ordered) }
            ?: Source("github.$owner.$repo".lowercase(), repo, "", ordered)
    }

    /** A web folder (Kodi "file source" style): the repository zip it lists, else its addons.xml. */
    private fun fromPage(url: String): Source {
        val base = if (url.endsWith("/")) url else "$url/"
        val html = try { String(download(url, MAX_PAGE), Charsets.UTF_8) } catch (e: UserError) { throw e } catch (e: IOException) { throw UserError(UNREACHABLE) }
        val href = Regex("href=[\"']([^\"']*repository\\.[^\"'/]+?\\.zip)[\"']", RegexOption.IGNORE_CASE).findAll(html).map { it.groupValues[1] }.toList()
        for (h in href.sortedWith { a, b -> compareVersions(b.substringAfterLast('-').removeSuffix(".zip"), a.substringAfterLast('-').removeSuffix(".zip")) }) {
            val u = try { normalize(URL(URL(base), h).toString()) } catch (e: Exception) { continue }
            try { return fromZip(u, download(u, MAX_ZIP)) } catch (e: Exception) { }
        }
        return try { fromIndexUrl(base + "addons.xml") } catch (e: Exception) { throw UserError(NOTHING) }
    }

    // ---- the index ----

    private val indexCache = HashMap<String, Pair<Long, List<Entry>>>()

    /** Blocking. [spec]'s addons.xml, MD5-checked when a checksum is published. Cached a minute. */
    fun index(spec: Spec): List<Entry> {
        synchronized(indexCache) { indexCache[spec.info]?.let { if (System.currentTimeMillis() - it.first < 60_000) return it.second } }
        var bytes = download(spec.info, MAX_INDEX)
        if (spec.compressed || spec.info.endsWith(".gz", true)) bytes = GZIPInputStream(ByteArrayInputStream(bytes)).use { readCapped(it, MAX_INDEX) }
        spec.checksum?.let { c ->
            val want = try { String(download(c, 4096), Charsets.UTF_8).trim().substringBefore(' ').lowercase() } catch (e: Exception) { null }
            if (want != null && want.length == 32 && want.all { it in "0123456789abcdef" }) {
                val got = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
                if (got != want) throw UserError("The source's file list didn't check out. Try again in a few minutes.")
            }
        }
        val list = parseAddons(String(bytes, Charsets.UTF_8))
        if (list.isEmpty()) throw IOException("empty index")
        synchronized(indexCache) { indexCache[spec.info] = System.currentTimeMillis() to list }
        return list
    }

    /** The first spec of [specs] that answers, with its entries. */
    fun firstIndex(specs: List<Spec>): Pair<Spec, List<Entry>> {
        var err: Exception? = null
        for (s in specs) try { return s to index(s) } catch (e: Exception) { err = e }
        throw (err as? UserError) ?: UserError(UNREACHABLE)
    }

    /**
     * The add-ons to install from [entries]: the scraper modules (by name; else every Python module, at most a few)
     * plus what they need that isn't built in and is in the same index. Missing dependencies are logged.
     */
    fun pick(entries: List<Entry>): List<Entry> {
        val byId = entries.associateBy { it.id }
        val modules = entries.filter { it.module }
        val wanted = modules.filter { "scraper" in it.id.lowercase() || "scraper" in it.name.lowercase() }.ifEmpty { modules.take(6) }
        val out = LinkedHashMap<String, Entry>()
        fun add(e: Entry) {
            if (e.id in out) return
            out[e.id] = e
            for ((dep, optional) in e.requires) {
                if (builtIn(dep)) continue
                val d = byId[dep]
                if (d != null) add(d) else if (!optional) ScraperLog.w("${e.id} needs $dep, which this source doesn't have")
            }
        }
        wanted.forEach(::add)
        return out.values.toList()
    }

    /** Blocking. Downloads [e] from [spec] and unpacks it into [addonsDir]. Returns its folder. */
    fun install(e: Entry, spec: Spec, addonsDir: File): File {
        val dir = if (spec.datadir.endsWith("/")) spec.datadir else spec.datadir + "/"
        if (!spec.zip) throw UserError("This source isn't in a format Dream OS supports yet.")
        val url = normalize("$dir${e.id}/${e.id}-${e.version}.zip")
        return unpack(download(url, MAX_ZIP), e.id, addonsDir)
    }

    // ---- zips ----

    /** The addon.xml at the top of a Kodi add-on zip (<id>/addon.xml). */
    private fun addonXmlFromZip(bytes: ByteArray): String? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var n = 0
            while (true) {
                val e = z.nextEntry ?: return null
                if (++n > MAX_ENTRIES) return null
                val name = e.name.replace('\\', '/')
                if (!e.isDirectory && name.count { it == '/' } == 1 && name.endsWith("/addon.xml")) return String(readCapped(z, 1 shl 20), Charsets.UTF_8)
            }
        }
    }

    /**
     * Unpacks a Kodi add-on zip (everything under one <id>/ folder) into addonsDir/<id>, replacing what was there only
     * once the new copy is complete. Refuses paths that climb out (zip-slip), absolute or drive paths, Windows device
     * names, too many files, or too much data, and a zip whose addon.xml isn't [expectId] (when given).
     */
    fun unpack(bytes: ByteArray, expectId: String?, addonsDir: File): File {
        addonsDir.mkdirs()
        val staging = File(addonsDir.parentFile, "staging-${System.nanoTime()}")
        staging.mkdirs()
        val root = staging.canonicalPath + File.separator
        try {
            var top: String? = null
            var total = 0L
            var count = 0
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (++count > MAX_ENTRIES) throw UserError(TOO_LARGE)
                    val name = e.name.replace('\\', '/').trimStart('/')
                    if (name.isEmpty()) continue
                    val parts = name.split('/')
                    if (parts.any { it == ".." || reservedName(it) } || name.contains(':') || e.name.startsWith("/") || e.name.startsWith("\\")) throw UserError(UNSAFE)
                    val first = parts[0]
                    if (top == null) top = first else if (first != top) throw UserError("That download isn't a single add-on.")
                    val out = File(staging, name)
                    if (!out.canonicalPath.startsWith(root)) throw UserError(UNSAFE)
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val r = z.read(buf)
                            if (r < 0) break
                            total += r
                            if (total > MAX_UNZIPPED) throw UserError(TOO_LARGE)
                            o.write(buf, 0, r)
                        }
                    }
                }
            }
            val id = top ?: throw UserError(NOTHING)
            val xmlFile = File(staging, "$id/addon.xml")
            val xml = if (xmlFile.isFile) xmlFile.readText() else throw UserError(NOTHING)
            val parsed = parseAddons(xml).firstOrNull() ?: throw UserError(NOTHING)
            if (parsed.id != id || (expectId != null && parsed.id != expectId) || !Regex("[A-Za-z0-9._-]+").matches(id)) {
                throw UserError("That download didn't match what the source listed.")
            }
            val dest = File(addonsDir, id)
            val old = File(addonsDir.parentFile, "old-$id-${System.nanoTime()}")
            if (dest.exists() && !dest.renameTo(old)) throw IOException("couldn't move the old copy")
            if (!File(staging, id).renameTo(dest)) { old.renameTo(dest); throw IOException("couldn't move the new copy") }
            old.deleteRecursively()
            return dest
        } finally {
            staging.deleteRecursively()
        }
    }

    /** CON, NUL, COM1... (with or without an extension): Windows opens a device instead of a file. */
    private fun reservedName(part: String): Boolean {
        val base = part.substringBefore('.').trimEnd(' ').uppercase()
        return base in setOf("CON", "PRN", "AUX", "NUL") || Regex("(COM|LPT)[0-9]").matches(base)
    }

    // ---- XML ----

    /** A StAX reader with DTDs and external entities off (downloaded XML is untrusted). */
    internal fun xmlReader(xml: String): XMLStreamReader {
        val f = XMLInputFactory.newInstance()
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false)
        f.setProperty(XMLInputFactory.IS_COALESCING, true)
        return f.createXMLStreamReader(StringReader(xml.removePrefix("﻿")))
    }

    private fun XMLStreamReader.attr(name: String): String? = getAttributeValue(null, name)

    /** addons.xml (or one addon.xml): every <addon>, with its imports, module flag and repository <dir>s. */
    fun parseAddons(xml: String): List<Entry> {
        val out = ArrayList<Entry>()
        try {
            val p = xmlReader(xml)
            var id = ""; var name = ""; var version = ""
            var reqs = ArrayList<Pair<String, Boolean>>()
            var module = false
            var specs = ArrayList<Spec>()
            var inRepo = false
            var info: String? = null; var gz = false; var checksum: String? = null; var datadir: String? = null; var zip = true
            var depth = 0
            fun flushDir() {
                val i = info; val d = datadir
                if (i != null && d != null) {
                    try { specs += Spec(normalize(i), checksum?.let { normalize(it) }, normalize(d), zip, gz) } catch (e: Exception) { }
                }
                info = null; checksum = null; datadir = null; zip = true; gz = false
            }
            while (p.hasNext()) {
                val ev = p.next()
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    depth++
                    when (p.localName) {
                        "addon" -> if (p.attr("id") != null && depth <= 2) {
                            id = p.attr("id").orEmpty(); name = p.attr("name").orEmpty(); version = p.attr("version").orEmpty()
                            reqs = ArrayList(); module = false; specs = ArrayList(); inRepo = false
                        }
                        "import" -> p.attr("addon")?.let { reqs += it to (p.attr("optional") == "true") }
                        "extension" -> when (p.attr("point")) {
                            "xbmc.python.module" -> module = true
                            "xbmc.addon.repository" -> inRepo = true
                        }
                        "dir" -> if (inRepo) flushDir()
                        "info" -> if (inRepo) { gz = p.attr("compressed") == "true"; info = p.elementText.trim(); depth-- }
                        "checksum" -> if (inRepo) { checksum = p.elementText.trim(); depth-- }
                        "datadir" -> if (inRepo) { zip = p.attr("zip") != "false"; datadir = p.elementText.trim(); depth-- }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    when (p.localName) {
                        "dir" -> if (inRepo) flushDir()
                        "extension" -> if (inRepo) { flushDir(); inRepo = false }
                        "addon" -> if (id.isNotEmpty()) { out += Entry(id, name.ifEmpty { id }, version, reqs, module, specs); id = "" }
                    }
                    depth--
                }
            }
        } catch (e: Exception) {
            ScraperLog.w("unreadable add-on list (${e.javaClass.simpleName})")
        }
        return out
    }

    /** Kodi-style version order: numeric parts compared as numbers ("1.0.45" > "1.0.9"). */
    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.', '-', '~', '+', '_'); val y = b.split('.', '-', '~', '+', '_')
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { "0" }; val q = y.getOrElse(i) { "0" }
            val pn = p.toLongOrNull(); val qn = q.toLongOrNull()
            val c = if (pn != null && qn != null) pn.compareTo(qn) else p.compareTo(q)
            if (c != 0) return c
        }
        return 0
    }

    // ---- HTTPS ----

    /** Blocking GET over HTTPS only (redirects stay on HTTPS), at most [max] bytes. */
    fun download(url: String, max: Int, headers: Map<String, String> = emptyMap()): ByteArray {
        if (!url.startsWith("https://", true)) throw UserError(INSECURE)
        var target = URL(url)
        repeat(6) {
            val c = target.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 12_000
                c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", "Dream OS")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("redirect without a location")
                    val next = URL(target, loc)
                    if (!next.protocol.equals("https", true)) throw UserError(INSECURE)
                    target = next
                    return@repeat
                }
                if (code != 200) throw IOException("HTTP $code")
                if (c.contentLengthLong > max) throw UserError(TOO_LARGE)
                return c.inputStream.use { readCapped(it, max) }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    /** Reads at most [max] bytes; one byte more and it stops with [TOO_LARGE] (nothing past the cap is kept). */
    internal fun readCapped(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val r = input.read(buf, 0, minOf(buf.size, max + 1 - out.size()))
            if (r < 0) break
            out.write(buf, 0, r)
            if (out.size() > max) throw UserError(TOO_LARGE)
        }
        return out.toByteArray()
    }
}
