package com.firehome.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Dream OS's own updater. Dream OS is sideloaded (not in the Amazon store), so it looks for new versions
 * itself, in the GitHub Releases of its repository: local.properties `update.repo=owner/name`, compiled
 * into BuildConfig.UPDATE_REPO (empty = the updater hides itself everywhere).
 *
 * A release is: a tag named after the version ("v4.1"), one `.apk` asset (the signed release build), and
 * its SHA-256 as an asset `<apk name>.sha256` (or a `SHA-256: <hex>` line in the notes). tools/release.sh
 * builds both and prints the `gh release create` command. GitHub's "latest" release (newest non-draft,
 * non-prerelease) is the one offered.
 *
 * Fails closed: HTTPS only (every redirect too), no checksum or a wrong one = no install, and the
 * downloaded APK must be Dream OS (same package) signed with the very certificate the installed Dream OS
 * carries, with a higher version code. Fire OS then shows its own install confirmation; nothing installs
 * silently, and nothing installs while something plays ([playing]).
 *
 * Automatic: [onHome] (MainActivity, a few seconds after Home shows) checks at most once a day. A new
 * version posts a notice ("A New Version of Dream OS Is Available", "Version 4.1", no change list), kept in
 * the notifications list until the user updates or removes it, and badges Settings. On Home (never over
 * playback) it shows as an actionable card ([UpdatePage.prompt]): "Would you like to install it now?" with
 * Install Now, View Changes and Later (Later = not again until tomorrow; it stays in the list). With
 * Automatic Updates on, the update is downloaded in the background meanwhile, so Install Now goes straight
 * to Fire OS's install confirmation. Settings › Updates is [UpdatePage.open].
 */
object DreamUpdate {
    data class Release(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long, val publishedAt: Long)

    private const val TAG = "FireHome"
    private const val PREFS = "dream_update"
    private const val K_AUTO = "auto"
    private const val K_LAST = "last_check"
    private const val K_ERROR = "last_error"
    private const val K_LATEST = "latest"         // JSON: the newest release seen (newer or not)
    private const val K_SNOOZE = "snooze"         // "version:until": Later on the notice (until tomorrow)
    private const val K_INSTALLING = "installing" // version handed to the installer (its notice once it's in)
    private const val K_READY = "ready"           // the verified download: "name:length:modified"
    private const val K_MINE = "mine_notes"       // the installed version's notes: "version\n" + notes
    private const val K_DEV_REPO = "dev_repo"     // developer override (PingReceiver), else BuildConfig
    private const val DAY_MS = 24 * 60 * 60_000L
    /** An update bigger than this is not a Dream OS APK. */
    private const val MAX_BYTES = 200L * 1024 * 1024
    const val NOTICE_PREFIX = "update:"
    /** The notice's action target (DreamNoticesUi: Install and View Changes). */
    const val TARGET = "update"

    const val MSG_UNVERIFIED = "This update couldn't be verified, so it wasn't installed."
    const val MSG_PERMISSION = "Allow Dream OS to Install Updates"
    const val MSG_PLAYING = "Finish Watching First, Then Update"

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var checking = false
    @Volatile private var downloading = false
    @Volatile private var fraction = 0f
    @Volatile private var lastAuto = 0L
    @Volatile private var lastAutoDownload = 0L
    private val listeners = ArrayList<() -> Unit>()

    // ---- settings ----

    fun autoEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(K_AUTO, true)

    fun setAuto(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(K_AUTO, on).apply()
        Log.w(TAG, "Update: automatic updates ${if (on) "on" else "off"}")
        changed()
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val REPO = Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""")

    /** owner/name of the releases repository ("" = updater hidden). */
    fun repo(ctx: Context): String {
        val r = prefs(ctx).getString(K_DEV_REPO, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.UPDATE_REPO
        return r.trim().takeIf { REPO.matches(it) } ?: ""
    }

    /** The updater exists in this build (a repository is set). */
    fun enabled(ctx: Context) = repo(ctx).isNotEmpty()

    fun currentVersion(): String = BuildConfig.VERSION_NAME

    fun lastChecked(ctx: Context): Long = prefs(ctx).getLong(K_LAST, 0L)

    /** The last check's plain-language problem, or null when it went fine. */
    fun lastError(ctx: Context): String? = prefs(ctx).getString(K_ERROR, null)

    /** The newest release seen (may be the installed version). */
    fun latest(ctx: Context): Release? = stored(ctx)?.r

    /** A release newer than this Dream OS, or null. */
    fun available(ctx: Context): Release? = latest(ctx)?.takeIf { newer(it.version, currentVersion()) }

    /** The newer release is downloaded and verified (Install starts the installer at once). */
    fun ready(ctx: Context): Boolean = available(ctx)?.let { r -> verifiedFile(ctx, r) != null } ?: false

    fun downloading() = downloading
    fun checking() = checking
    /** Download progress 0..1 while [downloading]. */
    fun downloadFraction() = fraction

    /** Called (main thread) when the state changes: a check, a download, the switch. */
    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun changed() { main.post { for (l in synchronized(listeners) { listeners.toList() }) try { l() } catch (e: Exception) { Log.w(TAG, "Update: listener: $e") } } }

    /** Something is playing in Dream Player (or just was): never update then. */
    fun playing(): Boolean = DreamPlayerActivity.isActive()

    // ---- check ----

    private class Stored(val r: Release, val tag: String, val sha: String?, val asset: String)

    private fun stored(ctx: Context): Stored? = try {
        prefs(ctx).getString(K_LATEST, null)?.let { s ->
            val o = JSONObject(s)
            Stored(Release(o.getString("v"), o.optString("n"), o.getString("u"), o.optLong("s"), o.optLong("p")),
                o.optString("t"), o.optString("h").takeIf { it.length == 64 }, o.optString("a"))
        }
    } catch (e: Exception) { null }

    private fun store(ctx: Context, s: Stored) {
        prefs(ctx).edit().putString(K_LATEST, JSONObject().put("v", s.r.version).put("n", s.r.notes).put("u", s.r.apkUrl)
            .put("s", s.r.sizeBytes).put("p", s.r.publishedAt).put("t", s.tag).put("h", s.sha ?: "").put("a", s.asset).toString()).apply()
    }

    /**
     * Asks GitHub for the latest release. Blocking (never on the main thread). Returns the release when it is
     * newer than this Dream OS, else null (up to date, or the check failed: [progress] then gets the reason,
     * which [lastError] keeps too).
     */
    fun checkNow(ctx: Context, progress: (String) -> Unit): Release? {
        val app = ctx.applicationContext
        val repo = repo(app)
        if (repo.isEmpty()) { progress("Updates Aren't Available in This Version"); return null }
        checking = true
        changed()
        progress("Checking for Updates…")
        try {
            val p = prefs(app)
            val res = try { getText("https://api.github.com/repos/$repo/releases/latest", 15_000, json = true) }
                catch (e: Exception) { Log.w(TAG, "Update: check failed: $e"); null }
            p.edit().putLong(K_LAST, System.currentTimeMillis()).apply()
            if (res == null || (res.code !in 200..299 && res.code != 404)) {
                val msg = "Couldn't Check for Updates Right Now. Try Again Later."
                Log.w(TAG, "Update: check: ${res?.code ?: "no answer"} from $repo")
                p.edit().putString(K_ERROR, msg).apply()
                progress(msg)
                return null
            }
            p.edit().remove(K_ERROR).apply()
            if (res.code == 404) {
                Log.w(TAG, "Update: v${currentVersion()} installed, no releases published in $repo")
                progress("Dream OS Is Up to Date")
                return null
            }
            val o = JSONObject(res.body)
            val tag = o.optString("tag_name")
            val version = VERSION.find(tag)?.value ?: VERSION.find(o.optString("name"))?.value
            val assets = o.optJSONArray("assets")
            val list = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
            val apks = list.filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
            val apk = apks.firstOrNull { it.optString("name").contains("dream", ignoreCase = true) } ?: apks.firstOrNull()
            if (version == null || apk == null) {
                Log.w(TAG, "Update: latest release \"$tag\" in $repo has ${if (version == null) "no version in its tag" else "no .apk"}; ignored")
                progress("Dream OS Is Up to Date")
                return null
            }
            val apkName = apk.optString("name")
            val url = apk.optString("browser_download_url")
            if (!url.startsWith("https://")) { Log.w(TAG, "Update: asset URL isn't HTTPS"); progress("Dream OS Is Up to Date"); return null }
            val body = o.optString("body")
            val sha = shaFromAsset(list, apkName) ?: shaFromNotes(body)
            val r = Release(version, cleanNotes(body), url, apk.optLong("size"), parseTime(o.optString("published_at")))
            val prev = stored(app)
            store(app, Stored(r, tag, sha, apkName))
            if (!newer(version, currentVersion()) && !newer(currentVersion(), version)) setMine(app, version, r.notes)
            val isNew = newer(version, currentVersion())
            Log.w(TAG, "Update: v${currentVersion()} installed, " +
                (if (isNew) "v$version available (${sizeText(r.sizeBytes)})" else "up to date (latest v$version)") +
                (if (sha == null) ", no checksum published" else ""))
            if (prev != null && prev.r.version != version) cleanFiles(app, keep = null) // an older download goes
            if (!isNew) {
                cleanFiles(app, keep = null)
                DreamNotices.dismissPrefix(app, NOTICE_PREFIX)
                progress("Dream OS Is Up to Date")
                return null
            }
            postNotice(app, r)
            progress("Dream OS ${r.version} Is Available")
            return r
        } finally {
            checking = false
            changed()
        }
    }

    /** "4.1" out of "v4.1", "Dream OS 4.1.2", "4.1-beta". */
    private val VERSION = Regex("""\d+(?:\.\d+)*""")

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

    private val HEX64 = Regex("""\b[0-9a-fA-F]{64}\b""")

    /** The checksum asset `<apk>.sha256` (sha256sum format: "hex  name"), else the one `.sha256` asset. */
    private fun shaFromAsset(assets: List<JSONObject>, apkName: String): String? {
        val shas = assets.filter { it.optString("name").endsWith(".sha256", ignoreCase = true) }
        val a = shas.firstOrNull { it.optString("name").equals("$apkName.sha256", ignoreCase = true) } ?: shas.singleOrNull() ?: return null
        val url = a.optString("browser_download_url")
        if (!url.startsWith("https://")) return null
        return try {
            val res = getText(url, 12_000, json = false, maxBytes = 4096)
            if (res.code !in 200..299) null else HEX64.find(res.body)?.value?.lowercase()
        } catch (e: Exception) { Log.w(TAG, "Update: checksum asset: $e"); null }
    }

    /** A notes line naming the checksum: "SHA-256: <hex>" (any line with "sha" and 64 hex digits). */
    private fun shaFromNotes(body: String): String? = body.lines()
        .firstOrNull { it.contains("sha", ignoreCase = true) && HEX64.containsMatchIn(it) }
        ?.let { HEX64.find(it)?.value?.lowercase() }

    private fun parseTime(s: String): Long = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)?.time ?: 0L
    } catch (e: Exception) { 0L }

    /**
     * Release notes (GitHub Markdown) as clean text: no checksum lines, no markup, links as their words,
     * bullets as "•", headings as plain lines; blank lines collapsed.
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

    fun sizeText(bytes: Long): String = if (bytes <= 0) "" else if (bytes >= 1024 * 1024) "%.1f MB".format(Locale.US, bytes / 1048576.0)
        else "${(bytes / 1024).coerceAtLeast(1)} KB"

    // ---- the installed version's notes (Settings › Updates › What's New) ----

    private fun setMine(ctx: Context, version: String, notes: String) {
        prefs(ctx).edit().putString(K_MINE, version + "\n" + notes).apply()
    }

    /** The installed version's release notes when known (no network), else null. */
    fun installedNotes(ctx: Context): String? {
        val s = prefs(ctx).getString(K_MINE, null) ?: return null
        val v = s.substringBefore('\n')
        return if (v == currentVersion() || (!newer(v, currentVersion()) && !newer(currentVersion(), v))) s.substringAfter('\n', "") else null
    }

    /** The installed version's notes, from its own release ("v4.0" or "4.0") when not known yet. Blocking. */
    fun fetchInstalledNotes(ctx: Context): String? {
        installedNotes(ctx)?.let { return it }
        val repo = repo(ctx).ifEmpty { return null }
        for (tag in listOf("v${currentVersion()}", currentVersion())) {
            try {
                val res = getText("https://api.github.com/repos/$repo/releases/tags/$tag", 12_000, json = true)
                if (res.code !in 200..299) continue
                val notes = cleanNotes(JSONObject(res.body).optString("body"))
                setMine(ctx, currentVersion(), notes)
                return notes
            } catch (e: Exception) { Log.w(TAG, "Update: notes for $tag: $e"); return null }
        }
        return null
    }

    // ---- notices ----

    /**
     * "A New Version of Dream OS Is Available" / "Version 4.1": kept in the notifications list (Install Now,
     * View Changes, Later) until Dream OS is updated or the user removes it; its banner waits for Home (never over
     * playback). One per version; a newer version's notice replaces an older one.
     */
    private fun postNotice(ctx: Context, r: Release) {
        for (n in DreamNotices.all(ctx)) if (n.key.startsWith(NOTICE_PREFIX) && n.key != NOTICE_PREFIX + r.version) DreamNotices.dismiss(ctx, n.key)
        DreamNotices.post(ctx, NOTICE_PREFIX + r.version, DreamNotices.TYPE_UPDATE, "A New Version of Dream OS Is Available",
            "Version ${r.version}", DreamNotices.Severity.INFO, DreamNotices.Action("Install Now", TARGET),
            banner = false) // on Home it shows as the actionable card instead (UpdatePage.prompt), not a passing banner
    }

    // ---- download and verify ----

    private fun fileFor(ctx: Context, r: Release) = File(ApkProvider.dir(ctx), "dreamos-" + r.version.replace(Regex("[^0-9A-Za-z.]"), "") + ".apk")

    private fun cleanFiles(ctx: Context, keep: File?) {
        ApkProvider.dir(ctx).listFiles()?.forEach { f ->
            if (f.name.startsWith("dreamos-") && f.name != keep?.name && f.name != keep?.name + ".part") f.delete()
        }
        if (keep == null) prefs(ctx).edit().remove(K_READY).apply()
    }

    private fun mark(f: File) = f.name + ":" + f.length() + ":" + f.lastModified()

    /** The verified download for [r], or null. */
    private fun verifiedFile(ctx: Context, r: Release): File? {
        val f = fileFor(ctx, r)
        return if (f.isFile && f.length() > 0 && prefs(ctx).getString(K_READY, null) == mark(f)) f else null
    }

    /**
     * Downloads [r] (resuming a partial file) and verifies it: the published SHA-256, then Dream OS's package,
     * a higher version code and the installed app's signing certificate. Blocking. [background]: stops
     * (keeping the partial file) as soon as something starts playing. Null with a [progress] message on failure.
     */
    fun prepare(ctx: Context, r: Release, background: Boolean = false, progress: (String) -> Unit): File? {
        val app = ctx.applicationContext
        verifiedFile(app, r)?.let { return it }
        synchronized(lock) {
            verifiedFile(app, r)?.let { return it }
            downloading = true
            fraction = 0f
            changed()
            try { return downloadAndVerify(app, r, background, progress) }
            finally { downloading = false; changed() }
        }
    }

    private fun downloadAndVerify(ctx: Context, r: Release, background: Boolean, progress: (String) -> Unit): File? {
        val sha = stored(ctx)?.takeIf { it.r.apkUrl == r.apkUrl }?.sha
        if (sha == null) {
            Log.w(TAG, "Update: v${r.version} has no published checksum; refused")
            progress(MSG_UNVERIFIED)
            return null
        }
        if (!r.apkUrl.startsWith("https://") || r.sizeBytes > MAX_BYTES) { progress(MSG_UNVERIFIED); return null }
        val done = fileFor(ctx, r)
        val part = File(done.path + ".part")
        cleanFiles(ctx, keep = done)
        if (done.isFile && sha256(done) == sha && verifyApk(ctx, done, progress)) { prefs(ctx).edit().putString(K_READY, mark(done)).apply(); return done }
        done.delete()
        var attempt = 0
        while (true) {
            attempt++
            var c: HttpURLConnection? = null
            try {
                if (background && playing()) { Log.w(TAG, "Update: download waits (playing)"); progress("Paused While You Watch"); return null }
                val have = if (part.isFile) part.length() else 0L
                c = open(r.apkUrl, 15_000, 25_000, json = false, range = have)
                val code = c.responseCode
                if (code == 416) { part.delete(); throw java.io.IOException("range not satisfiable") }
                if (code !in 200..299) { Log.w(TAG, "Update: download answered $code"); progress("The Download Didn't Start. Try Again Later."); return null }
                val resumed = code == 206 && have > 0
                val total = if (resumed) have + c.contentLengthLong.coerceAtLeast(0) else c.contentLengthLong.takeIf { it > 0 } ?: r.sizeBytes
                if (total > MAX_BYTES) { progress(MSG_UNVERIFIED); return null }
                RandomAccessFile(part, "rw").use { out ->
                    if (resumed) out.seek(have) else out.setLength(0)
                    var got = if (resumed) have else 0L
                    val buf = ByteArray(64 * 1024)
                    var at = 0L
                    c.inputStream.use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            if (got > MAX_BYTES) { progress(MSG_UNVERIFIED); return null }
                            val now = SystemClock.elapsedRealtime()
                            if (now - at > 250) {
                                at = now
                                if (background && playing()) { Log.w(TAG, "Update: download waits at $got bytes (playing)"); progress("Paused While You Watch"); return null }
                                if (total > 0) fraction = (got.toFloat() / total).coerceIn(0f, 1f)
                                progress(if (total > 0) "Downloading  ·  ${sizeText(got)} of ${sizeText(total)}" else "Downloading…")
                            }
                        }
                    }
                    if (total > 0 && got < total) throw java.io.IOException("short read $got/$total")
                }
                fraction = 1f
                progress("Checking the Update…")
                if (sha256(part) != sha) {
                    part.delete()
                    Log.w(TAG, "Update: v${r.version} checksum mismatch; refused")
                    progress(MSG_UNVERIFIED)
                    return null
                }
                if (!part.renameTo(done)) { progress("Couldn't Save the Update. Free Up Some Space and Try Again."); return null }
                if (!verifyApk(ctx, done, progress)) { done.delete(); return null }
                prefs(ctx).edit().putString(K_READY, mark(done)).apply()
                Log.w(TAG, "Update: v${r.version} downloaded (${sizeText(done.length())}), checksum and signature verified")
                progress("Ready to Install")
                return done
            } catch (e: Exception) {
                Log.w(TAG, "Update: download attempt $attempt: $e")
                if (attempt >= 4) { progress("The Download Stopped. Check the Network and Try Again."); return null }
                Thread.sleep(2000L * attempt)
            } finally {
                try { c?.disconnect() } catch (e: Exception) {}
            }
        }
    }

    /** Dream OS's own package, a higher version code, and signed by the same certificate(s) as the installed app. */
    private fun verifyApk(ctx: Context, apk: File, progress: (String) -> Unit): Boolean {
        val pm = ctx.packageManager
        try {
            @Suppress("DEPRECATION")
            val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            val mine = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
            val theirs = signers(info)
            val ours = signers(mine)
            if (info == null || info.packageName != ctx.packageName || theirs.isEmpty() || theirs != ours) {
                Log.w(TAG, "Update: ${apk.name} refused: package ${info?.packageName}, signer ${if (theirs.isNotEmpty() && theirs == ours) "same" else "different"}")
                progress(MSG_UNVERIFIED)
                return false
            }
            if (code(info) <= code(mine)) {
                Log.w(TAG, "Update: ${apk.name} refused: version code ${code(info)} isn't above the installed ${code(mine)}")
                progress("This Version Is Already Installed")
                return false
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Update: signature check: $e")
            progress(MSG_UNVERIFIED)
            return false
        }
    }

    @Suppress("DEPRECATION")
    private fun signers(p: PackageInfo?): Set<String> = p?.signatures.orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()

    @Suppress("DEPRECATION")
    private fun code(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return hex(md.digest())
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // ---- install ----

    /** Fire OS lets Dream OS hand APKs to the installer ("Install unknown apps" for Dream OS). */
    fun canInstall(ctx: Context): Boolean = if (android.os.Build.VERSION.SDK_INT >= 26) ctx.packageManager.canRequestPackageInstalls()
        else @Suppress("DEPRECATION") android.provider.Settings.Secure.getInt(ctx.contentResolver, android.provider.Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1

    /**
     * Opens the Fire TV page where Dream OS is allowed to install updates ("Install unknown apps"). Fire OS 7
     * resolves the standard per-app action to My Fire TV (the switch itself is under Developer Options).
     * False if nothing opened.
     */
    fun openInstallSettings(ctx: Context): Boolean {
        val tries = listOfNotNull(
            if (android.os.Build.VERSION.SDK_INT >= 26) android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${ctx.packageName}")) else null,
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            android.content.Intent().setClassName("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.tv.device.DeviceActivity"),
        )
        for (i in tries) {
            try {
                if (ctx.packageManager.resolveActivity(i, 0) == null) continue
                ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Exception) {}
        }
        return false
    }

    /**
     * Downloads (if not done yet), verifies and hands [r] to the system installer, which asks the user to
     * confirm. Blocking. False with a plain [progress] message when it can't: something is playing, the
     * update couldn't be verified, or Dream OS may not install apps yet ([MSG_PERMISSION]: the caller guides
     * the user to [openInstallSettings]).
     */
    fun install(ctx: Context, r: Release, progress: (String) -> Unit): Boolean {
        val app = ctx.applicationContext
        if (playing()) { progress(MSG_PLAYING); return false }
        val f = prepare(app, r, background = false, progress) ?: return false
        if (playing()) { progress(MSG_PLAYING); return false }
        if (!canInstall(app)) { Log.w(TAG, "Update: Install unknown apps isn't allowed for Dream OS yet"); progress(MSG_PERMISSION); return false }
        progress("Opening the Installer…")
        prefs(app).edit().putString(K_INSTALLING, r.version).apply()
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(ApkProvider.uri(app, f), ApkProvider.MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            app.startActivity(i)
            // Cancelled on Fire TV's screen: the notice card doesn't come straight back on Home (30 minutes).
            prefs(app).edit().putString(K_SNOOZE, "${r.version}:${System.currentTimeMillis() + 30 * 60_000L}").apply()
            Log.w(TAG, "Update: v${r.version} handed to the installer")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Update: installer: $e")
            progress("The Installer Couldn't Open. Try Again.")
            false
        }
    }

    // ---- automatic ----

    /**
     * Home is showing (MainActivity, a few seconds after resume). Right after an update: the "Installed"
     * notice. Then, never while something plays: the daily check, the background download (Automatic
     * Updates) and the actionable card for a new version, unless the user chose Later today.
     */
    fun onHome(a: Activity, homeFree: () -> Boolean) {
        val app = a.applicationContext
        if (!enabled(app)) return
        justUpdated(app)
        if (playing()) return
        if (!launchChecked) { launchCheck(a, homeFree); return }
        val now = System.currentTimeMillis()
        val last = lastChecked(app)
        val due = now - last >= DAY_MS || last > now
        if (due && !checking && (lastAuto == 0L || SystemClock.elapsedRealtime() - lastAuto > 10 * 60_000L)) {
            lastAuto = SystemClock.elapsedRealtime()
            Net.run {
                val r = checkNow(app) {} ?: return@run
                main.postDelayed({ maybePrompt(a, r, homeFree) }, 2_500)
                if (autoEnabled(app)) autoDownload(app, r)
            }
            return
        }
        val r = available(app) ?: return
        main.postDelayed({ maybePrompt(a, r, homeFree) }, 1_500)
        if (autoEnabled(app) && verifiedFile(app, r) == null && !downloading &&
            (lastAutoDownload == 0L || SystemClock.elapsedRealtime() - lastAutoDownload > 30 * 60_000L)) Net.run { autoDownload(app, r) }
    }

    /** This process has run its launch check (every Dream OS start, boot included, checks once, daily limit aside). */
    @Volatile private var launchChecked = false
    private var launchWaiting = false
    private var launchTries = 0

    /**
     * The launch check: once Home has settled (no intro, setup, profile picker, screensaver, player or other
     * banner: [homeFree], tried again every few seconds until it is), Dream Cinema shows a silent live banner
     * of the notification system (DreamNotices.live: "Checking for Updates…", the rainbow loader in its icon
     * slot) that turns in place into "You're Up to Date!" (green, silent) or "Update Available · Version 4.1"
     * (red, with the notification sound), after which the update card takes over. A failed check just fades.
     */
    private fun launchCheck(a: Activity, homeFree: () -> Boolean) {
        val app = a.applicationContext
        if (launchWaiting) return
        if (!homeFree() || UpdatePage.isOpen() || a.isFinishing) {
            if (++launchTries > 200) return // ~10 minutes without a free Home: the next resume tries again
            launchWaiting = true
            main.postDelayed({ launchWaiting = false; if (!a.isDestroyed && !playing()) launchCheck(a, homeFree) }, 3_000)
            return
        }
        launchChecked = true
        lastAuto = SystemClock.elapsedRealtime()
        val live = DreamNotices.live("Checking for Updates…")
        val started = SystemClock.elapsedRealtime()
        Net.run {
            val r = checkNow(app) {}
            val failed = r == null && lastError(app) != null
            Log.w(TAG, "Update: launch check → " + when { failed -> "couldn't check"; r == null -> "up to date (${currentVersion()})"; else -> "${r.version} available" })
            // The loader shows for a moment at least, so the result never flashes past.
            val wait = (1_400L - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0L)
            main.postDelayed({
                when {
                    failed -> live?.dismiss()
                    r == null -> live?.finish("You're Up to Date!", good = true, holdMs = 2_500)
                    live != null -> live.finish("Update Available  ·  Version ${r.version}", good = false, holdMs = 3_200) { maybePrompt(a, r, homeFree) }
                    else -> maybePrompt(a, r, homeFree)
                }
            }, wait)
            if (r != null && autoEnabled(app)) autoDownload(app, r)
        }
    }

    private fun autoDownload(app: Context, r: Release) {
        lastAutoDownload = SystemClock.elapsedRealtime()
        if (playing()) return
        val f = prepare(app, r, background = true) {} ?: return
        Log.w(TAG, "Update: v${r.version} ready to install (${sizeText(f.length())})")
    }

    /** Later on the notice: not shown on Home again until tomorrow (at least 12 hours); it stays in the list. */
    fun snooze(ctx: Context, r: Release) {
        val c = DreamTime.calendar(ctx).apply {
            add(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
        }
        val until = maxOf(c.timeInMillis, System.currentTimeMillis() + 12 * 60 * 60_000L)
        prefs(ctx).edit().putString(K_SNOOZE, "${r.version}:$until").apply()
        DreamNotices.markRead(ctx, NOTICE_PREFIX + r.version)
        Log.w(TAG, "Update: v${r.version} later (until ${java.util.Date(until)})")
        changed()
    }

    private fun snoozed(ctx: Context, r: Release): Boolean {
        val s = prefs(ctx).getString(K_SNOOZE, null) ?: return false
        return s.substringBeforeLast(':') == r.version && System.currentTimeMillis() < (s.substringAfterLast(':').toLongOrNull() ?: 0L)
    }

    /** The new-version card, only on a free Home (no player, nothing opened over Home) and not after Later today. */
    private fun maybePrompt(a: Activity, r: Release, homeFree: () -> Boolean) {
        val app = a.applicationContext
        if (a.isFinishing || a.isDestroyed || playing() || !homeFree() || UpdatePage.isOpen()) return
        if (available(app)?.version != r.version || snoozed(app, r)) return
        // The notice was removed from the list: the user is done with this version.
        if (DreamNotices.find(app, NOTICE_PREFIX + r.version) == null) return
        // Focus must be on Home itself (not Settings, a menu or a sheet over it).
        val content = a.findViewById<android.view.View>(android.R.id.content) ?: return
        var v: android.view.View? = a.currentFocus ?: return
        var inside = false
        while (v != null) { if (v === content) { inside = true; break }; v = v.parent as? android.view.View }
        if (!inside) return
        Log.w(TAG, "Update: showing the v${r.version} notice on Home")
        UpdatePage.prompt(a, r)
    }

    /** First start after an update: a notice once; the download and the old version's notice go. */
    private fun justUpdated(ctx: Context) {
        val p = prefs(ctx)
        val v = p.getString(K_INSTALLING, null) ?: return
        if (newer(v, currentVersion())) return // still the old version (the install was cancelled)
        p.edit().remove(K_INSTALLING).apply()
        cleanFiles(ctx, keep = null)
        DreamNotices.dismissPrefix(ctx, NOTICE_PREFIX)
        latest(ctx)?.takeIf { !newer(it.version, currentVersion()) && !newer(currentVersion(), it.version) }?.let { setMine(ctx, it.version, it.notes) }
        Log.w(TAG, "Update: now on v${currentVersion()}")
        DreamNotices.post(ctx, "updated:${currentVersion()}", DreamNotices.TYPE_UPDATE, "Dream OS ${currentVersion()} Is Installed",
            "See What's New in Settings  ›  Updates", DreamNotices.Severity.INFO, null, store = false)
    }

    // ---- developer ----

    /**
     * PingReceiver (adb only): `--es update check [--es repo owner/name]` checks now (against a test
     * repository; `--es repo build` goes back to the build's own), `prompt` shows the notice card again at the next Home (forgets Later),
     * `install` downloads and opens the installer, `reset` forgets every check, question and download,
     * `launch` runs the launch check (its "Checking for Updates…" notice) again at the next Home.
     * Then: adb logcat -s FireHome | grep Update
     */
    fun dev(ctx: Context, what: String, repo: String?) {
        val app = ctx.applicationContext
        val p = prefs(app)
        if (repo != null) {
            if (repo == "build" || repo.isBlank()) p.edit().remove(K_DEV_REPO).apply()
            else if (REPO.matches(repo.trim())) p.edit().putString(K_DEV_REPO, repo.trim()).apply()
            else Log.w(TAG, "Update: [dev] \"$repo\" isn't owner/name")
        }
        Log.w(TAG, "Update: [dev] $what (repo ${repo(app).ifEmpty { "none" }})")
        when (what) {
            "check" -> Net.run {
                val r = checkNow(app) { Log.w(TAG, "Update: [dev] $it") }
                if (r != null && autoEnabled(app)) prepare(app, r) { Log.w(TAG, "Update: [dev] $it") }
            }
            "prompt" -> { p.edit().remove(K_SNOOZE).apply(); lastAuto = 0L; lastAutoDownload = 0L }
            // The launch check again at the next Home (as if Dream OS had just started).
            "launch" -> { launchChecked = false; launchTries = 0 }
            "install" -> Net.run { available(app)?.let { r -> install(app, r) { Log.w(TAG, "Update: [dev] $it") } } ?: Log.w(TAG, "Update: [dev] nothing to install") }
            "reset" -> {
                p.edit().remove(K_LAST).remove(K_ERROR).remove(K_LATEST).remove(K_SNOOZE).remove(K_INSTALLING).remove(K_MINE).apply()
                cleanFiles(app, keep = null)
                DreamNotices.dismissPrefix(app, NOTICE_PREFIX)
                DreamNotices.forget(app, NOTICE_PREFIX)
                lastAuto = 0L
                lastAutoDownload = 0L
            }
        }
        changed()
    }

    // ---- HTTP (HTTPS only, every hop) ----

    private class Res(val code: Int, val body: String)

    private fun getText(url: String, timeout: Int, json: Boolean, maxBytes: Int = 2 * 1024 * 1024): Res {
        val c = open(url, timeout, timeout, json, 0L)
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val b = ByteArray(16 * 1024)
                while (true) { val n = s.read(b); if (n < 0) break; out.write(b, 0, n); if (out.size() > maxBytes) break }
                out.toByteArray()
            } ?: ByteArray(0)
            return Res(code, String(bytes, Charsets.UTF_8))
        } finally { c.disconnect() }
    }

    /** Follows up to 5 redirects by hand, refusing any hop that isn't HTTPS. */
    private fun open(url: String, connect: Int, read: Int, json: Boolean, range: Long): HttpURLConnection {
        var u = URL(url)
        repeat(6) {
            if (u.protocol != "https") throw java.io.IOException("refused a non-HTTPS hop to ${u.host}")
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = connect
            c.readTimeout = read
            c.setRequestProperty("User-Agent", "DreamOS/${currentVersion()}")
            if (json) {
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            if (range > 0) c.setRequestProperty("Range", "bytes=$range-")
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw java.io.IOException("redirect without a location")
                u = URL(u, loc)
                return@repeat
            }
            return c
        }
        throw java.io.IOException("too many redirects")
    }
}
