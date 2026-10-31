package com.dreamos.desktop.core.update

import com.dreamos.desktop.core.model.Notice
import com.dreamos.desktop.core.model.NoticeAction
import com.dreamos.desktop.core.model.NoticeSeverity
import com.dreamos.desktop.core.model.UpdateInfo
import com.dreamos.desktop.core.model.UpdateStatus
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.notices.Notices
import com.dreamos.desktop.core.service.Updates
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.store.Prefs
import com.dreamos.desktop.core.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * The GitHub update check (fire-home DreamUpdate.kt's check and About status): the latest public release of
 * [REPO] (newest non-draft, non-prerelease), its Windows installer [ASSET], at most every 6 hours unless forced.
 * GitHub's ETag is sent back (If-None-Match), so an unchanged release costs a 304 and keeps the stored answer.
 *
 * A newer version posts the notice "There's a New Version Available" / "Dream OS 4.3.4" (key `update:<version>`, action
 * "Install Now" -> target "update"); a newer one replaces an older one, and once this build is current they go.
 * Downloading, verifying ([UpdateInfo.sha256]) and running the installer is the app's job.
 *
 * The SHA-256 comes from a `<asset>.sha256` asset (sha256sum format), else the one `.sha256` asset, else a notes
 * line naming it ("SHA-256: <hex>"), as on the Fire Stick. Blocking calls: IO threads only.
 */
object UpdateCheck {
    const val REPO = "DenseCow/dream-os"
    const val URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val ASSET = "DreamOS-Windows-Setup.exe"
    /** The notice's action target: the app's Install / View Changes. */
    const val TARGET = "update"
    const val NOTICE_PREFIX = "update:"
    const val NOTICE_TITLE = "There's a New Version Available"
    const val EVERY_MS = 6 * 60 * 60_000L
    fun noticeBody(version: String) = "Dream OS $version"

    private const val FILE = "dream_update"
    private const val K_LATEST = "latest"
    private const val K_ETAG = "etag"
    private const val K_CHECKED = "checked_at"
    private const val K_FAILED = "failed"

    /** "4.1" out of "v4.1", "Dream OS 4.1.2", "4.1-beta". */
    private val VERSION = Regex("""\d+(?:\.\d+)*""")
    private val HEX64 = Regex("""\b[0-9a-fA-F]{64}\b""")

    /** This build's version (the app passes `-Ddream.version=4.3.4`), "0" when it doesn't. */
    fun currentVersion(): String = System.getProperty("dream.version")?.trim()?.takeIf { it.isNotEmpty() } ?: "0"

    private fun prefs() = DreamStore.shared.file(FILE)
    private val lock = Any()
    private val _status = MutableStateFlow(UpdateStatus(current = "0"))
    @Volatile private var loadedFrom: DreamStore? = null

    /** What About shows (from the last check; no network). */
    val status: StateFlow<UpdateStatus>
        get() {
            if (loadedFrom !== DreamStore.shared) synchronized(lock) { publish() }
            return _status
        }

    private fun stored(): UpdateStatus {
        val p = prefs()
        val latest = p.get(K_LATEST, UpdateInfo.serializer())
        val cur = currentVersion()
        return UpdateStatus(cur, latest, latest != null && newer(latest.version, cur), p.getLong(K_CHECKED), p.getBoolean(K_FAILED))
    }

    private fun publish(): UpdateStatus {
        loadedFrom = DreamStore.shared
        return stored().also { _status.value = it }
    }

    /** The last check is over 6 hours old (or there's none, or the clock went back). */
    fun due(now: Long = System.currentTimeMillis()): Boolean {
        val last = prefs().getLong(K_CHECKED)
        return last == 0L || now - last >= EVERY_MS || last > now
    }

    /**
     * Asks GitHub when [force] or [due]; otherwise returns the stored answer. Never throws: a failed check keeps the
     * last answer and says so ([UpdateStatus.failed]). Blocking.
     */
    fun check(force: Boolean = false): UpdateStatus = synchronized(lock) {
        if (!force && !due()) return publish()
        val p = prefs()
        val now = System.currentTimeMillis()
        val hasStored = p.get(K_LATEST, UpdateInfo.serializer()) != null
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/vnd.github+json"
        headers["X-GitHub-Api-Version"] = "2022-11-28"
        p.getString(K_ETAG)?.takeIf { hasStored && it.isNotEmpty() }?.let { headers["If-None-Match"] = it }
        val res = try { Net.request("GET", URL, null, headers, 15_000) } catch (e: Exception) {
            Log.w("Update", "check failed: ${e.javaClass.simpleName}")
            null
        }
        when {
            res == null || (!res.ok && res.code != 304 && res.code != 404) -> {
                Log.w("Update", "check: ${res?.code ?: "no answer"} from $REPO")
                p.edit { putLong(K_CHECKED, now); putBoolean(K_FAILED, true) }
            }
            res.code == 304 -> p.edit { putLong(K_CHECKED, now); putBoolean(K_FAILED, false) }
            res.code == 404 -> {
                Log.i("Update", "v${currentVersion()} installed, no releases published in $REPO")
                p.edit { remove(K_LATEST); remove(K_ETAG); putLong(K_CHECKED, now); putBoolean(K_FAILED, false) }
            }
            else -> {
                val info = try { parseJson(res.body).obj()?.let { release(it) } } catch (e: Exception) { null }
                p.edit {
                    if (info != null) putJson(K_LATEST, Prefs.JSON.encodeToJsonElement(UpdateInfo.serializer(), info)) else remove(K_LATEST)
                    putString(K_ETAG, res.header("ETag")?.takeIf { info != null })
                    putLong(K_CHECKED, now)
                    putBoolean(K_FAILED, false)
                }
                if (info == null) Log.w("Update", "latest release in $REPO has no version in its tag; ignored")
            }
        }
        val s = publish()
        if (!s.failed) {
            val l = s.latest
            if (l != null && s.available) {
                if (res?.code != 304) Log.i("Update", "v${s.current} installed, v${l.version} available" +
                    (if (l.downloadUrl.isEmpty()) ", no Windows installer" else "") + (if (l.sha256 == null) ", no checksum published" else ""))
                postNotice(l.version)
            } else {
                if (res?.code != 304) Log.i("Update", "v${s.current} installed, up to date" + (l?.let { " (latest v${it.version})" } ?: ""))
                Notices.dismissPrefix(NOTICE_PREFIX)
            }
        }
        s
    }

    /** A release answer as [UpdateInfo]; null when its tag names no version. */
    internal fun release(o: JsonObject): UpdateInfo? {
        val version = VERSION.find(o.str("tag_name"))?.value ?: VERSION.find(o.str("name"))?.value ?: return null
        val assets = o.a("assets").objects()
        // Only the setup by its exact name: a release from before it (DreamOS-Windows.msi) has no installer this
        // app can run, and About offers the release page instead.
        val setup = assets.firstOrNull { it.str("name").equals(ASSET, ignoreCase = true) }
        val url = setup?.str("browser_download_url")?.takeIf { it.startsWith("https://") }.orEmpty()
        val body = o.str("body")
        val sha = if (setup == null) null else shaFromAsset(assets, setup.str("name")) ?: shaFromNotes(body)
        return UpdateInfo(
            version = version,
            notes = cleanNotes(body),
            downloadUrl = url,
            sizeBytes = if (url.isEmpty()) 0 else setup?.long("size") ?: 0,
            publishedAt = try { Instant.parse(o.str("published_at")).toEpochMilli() } catch (e: Exception) { 0L },
            sha256 = sha,
        )
    }

    /** "There's a New Version Available" / "Dream OS 4.3.4", one per version; a newer version's notice replaces an older one. */
    private fun postNotice(version: String) {
        val key = NOTICE_PREFIX + version
        for (n in Notices.all()) if (n.key.startsWith(NOTICE_PREFIX) && n.key != key) Notices.dismiss(n.key)
        // Kept in the list without a banner: the new-version prompt is its only banner (the Fire Stick's banner = false).
        Notices.post(Notice(key, Notice.TYPE_UPDATE, NOTICE_TITLE, noticeBody(version), NoticeSeverity.INFO,
            action = NoticeAction("Install Now", TARGET)), banner = false)
    }

    /** True when version [a] is newer than [b] (numeric, part by part: 4.10 > 4.9). */
    fun newer(a: String, b: String): Boolean {
        val x = VERSION.find(a)?.value?.split('.')?.map { it.toIntOrNull() ?: 0 } ?: return false
        val y = VERSION.find(b)?.value?.split('.')?.map { it.toIntOrNull() ?: 0 } ?: return true
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** The checksum asset `<asset>.sha256` (sha256sum format: "hex  name"), else the one `.sha256` asset. */
    private fun shaFromAsset(assets: List<JsonObject>, assetName: String): String? {
        val shas = assets.filter { it.str("name").endsWith(".sha256", ignoreCase = true) }
        val a = shas.firstOrNull { it.str("name").equals("$assetName.sha256", ignoreCase = true) } ?: shas.singleOrNull() ?: return null
        val url = a.str("browser_download_url")
        if (!url.startsWith("https://")) return null
        return try {
            val r = Net.request("GET", url, null, emptyMap(), 12_000)
            if (!r.ok || r.body.length > 4096) null else HEX64.find(r.body)?.value?.lowercase()
        } catch (e: Exception) {
            Log.w("Update", "checksum asset: ${e.javaClass.simpleName}")
            null
        }
    }

    /** A notes line naming the checksum: "SHA-256: <hex>" (any line with "sha" and 64 hex digits). */
    private fun shaFromNotes(body: String): String? = body.lines()
        .firstOrNull { it.contains("sha", ignoreCase = true) && HEX64.containsMatchIn(it) }
        ?.let { HEX64.find(it)?.value?.lowercase() }

    /**
     * Release notes (GitHub Markdown) as clean text: no checksum lines, no markup, links as their words, bullets as
     * "•", headings as plain lines; blank lines collapsed.
     */
    fun cleanNotes(md: String): String {
        val out = ArrayList<String>()
        var fence = false
        for (raw in md.replace("\r", "").replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "").lines()) {
            var l = raw.trimEnd()
            if (l.trimStart().startsWith("```")) { fence = !fence; continue }
            if (fence) continue
            if (l.contains("sha", ignoreCase = true) && HEX64.containsMatchIn(l)) continue
            if (HEX64.matches(l.trim())) continue
            if (Regex("""^\s*([-*_])\s*(\1\s*){2,}$""").matches(l)) continue // a rule
            l = l.replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "")             // images
                .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")             // links: their words
                .replace(Regex("""<[^>]+>"""), "")                            // html tags
                .replace(Regex("""(\*\*|__)(.+?)\1"""), "$2")
                .replace(Regex("""(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])"""), "$1")
                .replace("`", "")
            val bullet = Regex("""^\s*(?:[-*+]|\d+[.)])\s+(.*)$""").find(l)
            l = when {
                Regex("""^\s*#{1,6}\s""").containsMatchIn(l) -> l.replace(Regex("""^\s*#{1,6}\s*"""), "").trim()
                bullet != null -> "•  " + bullet.groupValues[1].trim()
                else -> l.trim().removePrefix(">").trim()
            }
            if (l.isEmpty()) { if (out.isNotEmpty() && out.last().isNotEmpty()) out += "" } else out += l
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        return out.joinToString("\n")
    }

    /** "12.4 MB" / "800 KB" ("" when unknown). */
    fun sizeText(bytes: Long): String = if (bytes <= 0) "" else if (bytes >= 1024 * 1024) "%.1f MB".format(java.util.Locale.US, bytes / 1048576.0)
        else "${(bytes / 1024).coerceAtLeast(1)} KB"
}

/** [Updates] for About and the shell. Register at launch: `Services.updates = UpdatesImpl()`; then `check()` once. */
class UpdatesImpl : Updates {
    override val status: StateFlow<UpdateStatus> get() = UpdateCheck.status
    override suspend fun check(force: Boolean): UpdateStatus = withContext(Dispatchers.IO) { UpdateCheck.check(force) }
    override suspend fun install(progress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val st = UpdateCheck.status.value.takeIf { it.latest != null } ?: UpdateCheck.check(force = true)
        val latest = st.latest?.takeIf { st.available } ?: throw com.dreamos.desktop.core.model.DreamError("You're up to date.")
        UpdateInstall.launch(UpdateInstall.download(latest, progress))
    }
}
