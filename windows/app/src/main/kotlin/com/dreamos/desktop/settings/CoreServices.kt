package com.dreamos.desktop.settings

import com.dreamos.desktop.core.content.ContentPrefs
import com.dreamos.desktop.core.model.DreamError
import com.dreamos.desktop.core.model.NoticeSeverity
import com.dreamos.desktop.core.model.PinResult
import com.dreamos.desktop.core.model.TraktDeviceCode
import com.dreamos.desktop.core.playback.AccountHealthState
import com.dreamos.desktop.core.playback.AccountsApi
import com.dreamos.desktop.core.playback.DebridServiceId
import com.dreamos.desktop.core.playback.PillTone
import com.dreamos.desktop.core.playback.SignInCode
import com.dreamos.desktop.core.playback.SignInPoll
import com.dreamos.desktop.core.service.Services
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.profiles.WindowsHello
import com.dreamos.desktop.settings.kit.Mark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.dreamos.desktop.core.model.Profile as CoreProfile
import com.dreamos.desktop.scrapers.Scrapers as ScraperStore

/**
 * Settings, Setup, Profiles, About and Notices on the real services: win-content's [Services], win-playback's
 * [AccountsApi] and win-scrapers' [ScraperStore]. The app builds it once at launch (after `ContentServices.install()`
 * and `Playback.create`) and provides it through [LocalPagesServices].
 */
object CoreServices {
    /** [playback] is Dream Core (for the subtitle languages); null falls back to the shell's one when it exists. */
    fun create(accounts: AccountsApi?, scrapers: ScraperStore = ScraperStore.shared, check: SetupCheck? = null, playback: com.dreamos.desktop.core.playback.PlaybackApi? = null): PagesServices = PagesServices(
        subtitleLanguages = { langs -> runCatching { (playback ?: com.dreamos.desktop.shell.AppServices.playback)?.viewerSubtitleLanguages(langs) } },
        prefs = StorePrefs,
        debrid = accounts?.let { CoreDebrid(it) } ?: FakeServices.create(linked = false).debrid,
        trakt = CoreTrakt,
        scrapers = CoreScrapers(scrapers),
        keys = CoreKeys,
        profiles = CoreProfiles,
        updates = CoreUpdates,
        notices = CoreNotices,
        startWithWindows = StartWithWindows::set,
        check = check ?: object : SetupCheck {
            override suspend fun run(stage: (String) -> Unit): CheckOutcome {
                val st = scrapers.status()
                return when {
                    st.repos.isEmpty() -> CheckOutcome(false, message = "Add a source first.")
                    st.providers.none { it.enabled } -> CheckOutcome(false, message = "Turn on at least one provider first.")
                    else -> CheckOutcome(false, message = "Something went wrong. Try again.")
                }
            }
        },
        setupDone = { set -> if (set == true) Services.profiles.markSetUp(); Services.profiles.isSetUp() },
        displayMode = { set ->
            com.dreamos.desktop.shell.DisplayMode.ofLabel(set)?.let { com.dreamos.desktop.shell.Display.set(it) }
            com.dreamos.desktop.shell.Display.mode.label
        },
    )
}

/** The pages' own keys in the settings store ("pages" per profile); the few core reads go through ContentPrefs. */
object StorePrefs : Prefs {
    private fun file() = DreamStore.shared.profileFile("pages")
    override fun bool(key: String, default: Boolean): Boolean = when (key) {
        "home.animeApart" -> ContentPrefs.animeApart()
        else -> file().getBoolean(key, default)
    }
    override fun setBool(key: String, value: Boolean) = when (key) {
        "home.animeApart" -> ContentPrefs.setAnimeApart(value)
        else -> file().putBoolean(key, value)
    }
    override fun str(key: String, default: String): String = when (key) {
        "audio.lang" -> ContentPrefs.audioLang()?.let { Languages.nameOf(it) } ?: default
        else -> file().string(key, default)
    }
    override fun setStr(key: String, value: String) = when (key) {
        // The pages speak names ("Spanish"); core keeps the ISO code ("es"). Locale.forLanguageTag("Spanish") is "spanish".
        "audio.lang" -> ContentPrefs.setAudioLang(Languages.codeOf(value))
        else -> file().putString(key, value)
    }
}

private fun Debrid.id(): DebridServiceId = when (this) {
    Debrid.AllDebrid -> DebridServiceId.ALLDEBRID; Debrid.RealDebrid -> DebridServiceId.REAL_DEBRID
    Debrid.Premiumize -> DebridServiceId.PREMIUMIZE; Debrid.TorBox -> DebridServiceId.TORBOX
}

private class CoreDebrid(private val api: AccountsApi) : DebridAccounts {
    private var pending: SignInCode? = null

    private fun state(s: Debrid): DebridState {
        val id = s.id()
        val sum = api.summary(id)
        if (!sum.linked) return DebridState(s, false)
        val h = api.lastHealth(id)
        // The Fire Stick's SetupGate.status words: "Connected · Premium Until Jan 10, 2027" (or "· Premium").
        val plan = when {
            h?.premium != true -> null
            h.until > 0 -> "Premium Until " + Instant.ofEpochMilli(h.until).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
            else -> "Premium"
        }
        val (word, mark, attention) = when (h?.health) {
            AccountHealthState.INVALID -> Triple("Sign In Again", Mark.BAD, true)
            AccountHealthState.BLOCKED -> Triple("Approve This Network", Mark.WARN, true)
            AccountHealthState.OFFLINE -> Triple("Couldn't Check Right Now", null, false)
            null -> Triple("Connected · Not Checked Yet", null, false)
            else -> when {
                h.premium == false -> Triple("Expired", Mark.BAD, true)
                h.until in 1 until System.currentTimeMillis() -> Triple("Expired", Mark.BAD, true)
                sum.tone == PillTone.BAD -> Triple("Expired", Mark.BAD, true)
                else -> Triple("Connected · " + (plan ?: "Premium"), Mark.OK, false)
            }
        }
        return DebridState(s, true, sum.user ?: h?.user, plan, word, mark, attention)
    }

    override fun states() = Debrid.entries.map { state(it) }
    override fun linkedInOrder(): List<Debrid> = api.linked.value.mapNotNull { id -> Debrid.entries.firstOrNull { it.id() == id } }
    override suspend fun check(s: Debrid): DebridState { api.checkHealth(s.id()); return state(s) }
    override suspend fun disconnect(s: Debrid) = api.unlink(s.id())
    override fun useFirst(s: Debrid) { api.setOrder(listOf(s.id()) + api.linked.value.filter { it != s.id() }) }
    override fun usesCode(s: Debrid) = api.deviceSignIn(s.id())

    override suspend fun startLink(s: Debrid): LinkCode {
        val c = api.startSignIn(s.id()) ?: throw DreamError("${s.title} didn't answer. Check your internet connection and try again.")
        pending = c
        return LinkCode(c.code, c.qrUrl, c.url.removePrefix("https://").removePrefix("www.").trimEnd('/'))
    }

    override suspend fun pollLink(s: Debrid): LinkResult {
        val c = pending ?: return LinkResult.Failed("Something went wrong. Try again.")
        delay(c.intervalS.coerceAtLeast(2) * 1000L)
        return when (val p = api.poll(c)) {
            SignInPoll.Waiting -> LinkResult.Waiting
            is SignInPoll.Done -> { pending = null; LinkResult.Connected(api.checkHealth(s.id())?.premium != false) }
            is SignInPoll.Failed -> { pending = null; LinkResult.Failed(p.message) }
        }
    }

    override suspend fun linkWithKey(s: Debrid, key: String): LinkResult = when (val p = api.linkPremiumizeKey(key)) {
        is SignInPoll.Done -> LinkResult.Connected(true)
        is SignInPoll.Failed -> LinkResult.Failed(p.message)
        SignInPoll.Waiting -> LinkResult.Waiting
    }
}

/** "Last Synced Just Now" / "5 Min Ago" / "3 Hours Ago" / "4 Days Ago". */
fun syncedWords(at: Long): String {
    if (at <= 0) return "Not Synced Yet"
    val m = (System.currentTimeMillis() - at) / 60_000
    return "Last Synced " + when {
        m < 1 -> "Just Now"
        m < 60 -> "$m Min Ago"
        m < 120 -> "1 Hour Ago"
        m < 1440 -> "${m / 60} Hours Ago"
        m < 2880 -> "1 Day Ago"
        else -> "${m / 1440} Days Ago"
    }
}

private object CoreTrakt : TraktAccount {
    private var code: TraktDeviceCode? = null
    private val t get() = Services.trakt
    override fun state(): TraktState {
        val s = t.status.value
        // "Last Synced 3 Hours Ago" up to 48 hours (core's words, the Fire Stick's).
        val synced = runCatching { com.dreamos.desktop.core.trakt.Trakt.lastSyncedText() }.getOrElse { syncedWords(s.lastSync) }
        return TraktState(s.signedIn, s.user.ifBlank { null }, synced, ended = !s.signedIn && s.problem != null)
    }
    override val needsClientId get() = !t.status.value.hasClientId
    override fun setClientId(id: String) = t.setClientId(id.trim())
    override val clientIdHint: String? get() = com.dreamos.desktop.core.trakt.Trakt.clientId.takeIf { it.isNotEmpty() }?.let { "Ending in ${it.takeLast(4)}" }
    override val redirectUri: String get() = com.dreamos.desktop.core.trakt.Trakt.REDIRECT_URI
    override val appsUrl: String get() = com.dreamos.desktop.core.trakt.Trakt.APPS_URL
    override suspend fun startSignIn(): LinkCode {
        val c = t.startSignIn().also { code = it }
        return LinkCode(c.userCode, c.verifyUrl, c.verifyUrl.removePrefix("https://").trimEnd('/'), c.expiresAt)
    }
    override suspend fun pollSignIn(): LinkResult {
        val c = code ?: return LinkResult.Failed("Couldn't reach Trakt. Try again.")
        // Core's own sentence for why it stopped (expired, declined, already used, unreachable).
        return if (t.awaitApproval(c)) LinkResult.Connected(true)
            else LinkResult.Failed(com.dreamos.desktop.core.trakt.Trakt.lastSignInProblem ?: "The code expired. Get a new one to try again.")
    }
    override suspend fun syncNow(): Boolean = runCatching { t.syncNow() }.isSuccess
    override suspend fun disconnect() = t.signOut()
}

private class CoreScrapers(private val s: ScraperStore) : Scrapers {
    override fun repos() = s.status().repos.map { ScraperRepo(it.name, it.url, it.version.ifBlank { null }, it.id) }
    override fun providers(): List<ScraperProvider> {
        val rec = s.recommended()
        return s.status().providers.map {
            ScraperProvider(it.id, it.name, s.caption(it.id) ?: if (it.kind == "hoster") "File Hosts" else "Torrent Sources", it.enabled, it.id in rec)
        }
    }
    override suspend fun add(address: String, stage: (String) -> Unit): Result<String> = withContext(Dispatchers.IO) {
        when (val r = s.addRepository(address, stage)) {
            is ScraperStore.AddResult.Ok -> Result.success(r.repo.name)
            is ScraperStore.AddResult.Failed -> Result.failure(IllegalStateException(r.message))
            else -> Result.failure(IllegalStateException("That didn't work. Check the address and try again."))
        }
    }
    override suspend fun update(repo: ScraperRepo): Result<String> = withContext(Dispatchers.IO) {
        when (val r = s.updateAll()) {
            is ScraperStore.AddResult.Failed -> Result.failure(IllegalStateException(r.message))
            else -> Result.success("Up to Date")
        }
    }
    override suspend fun remove(repo: ScraperRepo) = withContext(Dispatchers.IO) { s.removeRepository(repo.id) }
    override fun setProvider(id: String, on: Boolean) = s.setEnabled(id, on)
    override fun useRecommended() = s.applyRecommended()
    override fun setAll(on: Boolean) = s.status().providers.forEach { s.setEnabled(it.id, on) }
    override val recommendedOn get() = s.isRecommended()
}

private object CoreKeys : Keys {
    private val k get() = Services.keys
    override val tmdbAdded get() = k.tmdb.value.isNotBlank()
    override val omdbAdded get() = k.omdb.value.isNotBlank()
    override suspend fun saveTmdb(key: String) = (runCatching { k.checkTmdb(key) }.getOrDefault(false)).also { if (it) k.setTmdb(key) }
    override suspend fun saveOmdb(key: String) = (runCatching { k.checkOmdb(key) }.getOrDefault(false)).also { if (it) k.setOmdb(key) }
    override fun removeOmdb() = k.setOmdb("")
}

private object CoreProfiles : ProfilesService {
    private val p get() = Services.profiles
    private fun hello(id: String) = DreamStore.shared.file("pages").getBoolean("hello.$id", false)

    private fun mine(c: CoreProfile) = Profile(
        c.id, c.name, c.emoji, CoreProfile.COLORS.getOrElse(c.color) { CoreProfile.COLORS[0] }, c.hasPin,
        if (c.isKids) c.kidsLevel else 3, c.exitPin, hello(c.id) && c.hasPin,
    )

    private fun result(r: PinResult) = when (r) { PinResult.Ok -> PinCheck.Ok; PinResult.Wrong -> PinCheck.Wrong; is PinResult.Wait -> PinCheck.Wait(r.seconds) }

    override fun all() = p.all.value.map(::mine)
    override fun currentId() = p.current.value.id
    override fun pinNeededFor(id: String) = p.pinNeeded(id)
    override suspend fun select(id: String, pin: String?) = result(p.select(id, pin))
    override suspend fun selectTrusted(id: String) = result(p.selectTrusted(id))
    override suspend fun checkPin(id: String, pin: String) = result(p.checkPin(id, pin))
    override suspend fun save(p: Profile, pin: String?): Profile {
        val base = if (p.id.isBlank()) this.p.add(p.name) else this.p.all.value.first { it.id == p.id }
        val color = CoreProfile.COLORS.indexOf(p.color).coerceAtLeast(0)
        val kids = p.kidsLevel < 3
        this.p.save(base.copy(name = p.name, emoji = p.avatar, color = color, isKids = kids, kidsLevel = if (kids) p.kidsLevel else base.kidsLevel, exitPin = kids && p.pinToLeave), pin)
        val saved = this.p.all.value.first { it.id == base.id }
        DreamStore.shared.file("pages").putBoolean("hello.${saved.id}", p.hello && saved.hasPin)
        return mine(saved)
    }
    override suspend fun remove(id: String) = p.delete(id)
    override val helloReady get() = WindowsHello.available()
    override suspend fun helloVerify(reason: String) = WindowsHello.verify(reason)
}

/** "Today at 6:42 PM", "Yesterday at 9:05 AM", "Oct 21 at 8:05 PM". */
fun whenWords(at: Long): String {
    val z = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(at).atZone(z)
    val day = t.toLocalDate(); val today = LocalDate.now(z)
    val time = t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    return when (day) {
        today -> if (System.currentTimeMillis() - at < 60_000) "Just Now" else "Today at $time"
        today.minusDays(1) -> "Yesterday at $time"
        else -> t.format(DateTimeFormatter.ofPattern("MMM d", Locale.US)) + " at $time"
    }
}

private object CoreUpdates : Updates {
    private val u get() = Services.updates
    override val hasInstaller: Boolean get() = u.status.value.let { it.available && !it.latest?.downloadUrl.isNullOrBlank() }

    private fun setupFile(info: com.dreamos.desktop.core.model.UpdateInfo): java.io.File {
        val dir = java.io.File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "Dream OS/updates").apply { mkdirs() }
        return java.io.File(dir, "DreamOS-Windows-Setup-${info.version.filter { c -> c.isLetterOrDigit() || c == '.' }}.exe")
    }

    /**
     * Downloads the newest setup (checked against the release's SHA-256 when it states one) and keeps it for
     * [install]; a setup already downloaded for this version is used as it is. Returns the file, or a plain sentence.
     */
    private fun fetch(info: com.dreamos.desktop.core.model.UpdateInfo, progress: (Float) -> Unit): Any {
        val setup = setupFile(info)
        if (setup.isFile && setup.length() > 0) { progress(1f); return setup }
        // Older downloads and half-finished ones go; only this version's setup is kept.
        setup.parentFile.listFiles()?.forEach { if (it != setup && (it.name.endsWith(".part") || it.name.endsWith(".msi") || it.name.endsWith(".exe"))) it.delete() }
        val part = java.io.File(setup.parentFile, setup.name + ".part")
        val ok = runCatching {
            val http = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(15)).build()
            val req = java.net.http.HttpRequest.newBuilder(java.net.URI(info.downloadUrl)).header("User-Agent", "DreamOS").build()
            val res = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream())
            if (res.statusCode() != 200) return@runCatching false
            val total = res.headers().firstValueAsLong("Content-Length").orElse(info.sizeBytes).coerceAtLeast(1)
            val sha = java.security.MessageDigest.getInstance("SHA-256")
            res.body().use { input -> part.outputStream().use { out ->
                val buf = ByteArray(1 shl 16); var got = 0L; var n: Int; var pct = -1
                while (input.read(buf).also { n = it } >= 0) {
                    out.write(buf, 0, n); sha.update(buf, 0, n); got += n
                    val now = (got * 100 / total).toInt().coerceIn(0, 100)
                    if (now != pct) { pct = now; progress(now / 100f) } // once per percent, not per block
                }
            } }
            val hex = sha.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256 != null && !info.sha256.equals(hex, ignoreCase = true)) return@runCatching null
            part.renameTo(setup)
        }.getOrElse { false }
        return when (ok) {
            null -> { part.delete(); "The download didn't match the release. Try again." }
            false -> { part.delete(); "Couldn't download the update. Check your internet connection and try again." }
            else -> setup
        }
    }

    override suspend fun prefetch(): Boolean = withContext(Dispatchers.IO) {
        val info = u.status.value.takeIf { it.available }?.latest?.takeIf { it.downloadUrl.startsWith("https://") } ?: return@withContext false
        fetch(info) {} is java.io.File
    }

    override suspend fun install(progress: (Float) -> Unit): String? = withContext(Dispatchers.IO) {
        val info = u.status.value.latest?.takeIf { it.downloadUrl.startsWith("https://") } ?: return@withContext "This release has no Windows installer. Open the release page instead."
        when (val got = fetch(info, progress)) {
            is String -> got
            else -> {
                val setup = got as java.io.File
                // Started through cmd's start so the setup lives on after Dream OS quits (it replaces the open files),
                // silently: a progress window, no questions; it opens Dream OS again when it's done.
                val cmd = listOf("cmd", "/c", "start", "\"\"", setup.absolutePath) + com.dreamos.desktop.core.update.UpdateInstall.SETUP_ARGS
                val started = runCatching { ProcessBuilder(cmd).start(); true }.getOrDefault(false)
                if (!started) "Couldn't start the installer. Open the release page instead."
                else {
                    runCatching { com.dreamos.desktop.shell.AppServices.shutdown() }
                    kotlin.system.exitProcess(0)
                }
            }
        }
    }
    override val version: String get() = System.getProperty("dream.version") ?: u.status.value.current.ifBlank { "Unknown" }
    override val channel: String get() = System.getProperty("dream.channel") ?: "DEV"
    private fun map(s: com.dreamos.desktop.core.model.UpdateStatus): UpdateState {
        val checked = if (s.checkedAt > 0) "Last Checked " + whenWords(s.checkedAt) + "." else ""
        return when {
            s.checkedAt <= 0 && !s.failed -> UpdateState.Never
            s.failed -> UpdateState.Failed(checked)
            s.available && s.latest != null -> UpdateState.Newer(s.latest!!.version, "https://github.com/DenseCow/dream-os/releases", checked)
            else -> UpdateState.UpToDate(checked)
        }
    }
    override fun state() = map(u.status.value)
    override suspend fun check() = map(u.check(force = true))
    override suspend fun checkIfDue() = map(u.check(force = false))
    override val checkedAt: Long get() = u.status.value.checkedAt
    override val newestNotes: String get() = u.status.value.latest?.notes.orEmpty()
    override val newestSize: String get() = com.dreamos.desktop.core.update.UpdateCheck.sizeText(u.status.value.latest?.sizeBytes ?: 0)
    override val newestReleased: Long get() = u.status.value.latest?.publishedAt ?: 0

    /** This version's notes from its GitHub release (tag v4.3.4 or 4.3.4); the newest release's when that's this one. */
    override suspend fun notesFor(version: String): String? = withContext(Dispatchers.IO) {
        u.status.value.latest?.takeIf { it.version == version && it.notes.isNotBlank() }?.let { return@withContext it.notes }
        for (tag in listOf("v$version", version)) {
            val r = runCatching {
                com.dreamos.desktop.core.net.Net.request("GET", "https://api.github.com/repos/${com.dreamos.desktop.core.update.UpdateCheck.REPO}/releases/tags/$tag", null,
                    mapOf("Accept" to "application/vnd.github+json"), 12_000)
            }.getOrNull() ?: return@withContext null
            if (r.code == 404) continue
            if (!r.ok) return@withContext null
            val body = runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(r.body) as kotlinx.serialization.json.JsonObject)["body"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } }.getOrNull()
            return@withContext com.dreamos.desktop.core.update.UpdateCheck.cleanNotes(body.orEmpty())
        }
        ""
    }
}

/** "Just Now", "5 Minutes Ago", "1 Hour Ago", "Yesterday", "3 Days Ago", then "Oct 21". */
fun agoWords(at: Long): String {
    val m = (System.currentTimeMillis() - at) / 60_000
    return when {
        m < 1 -> "Just Now"
        m < 2 -> "1 Minute Ago"
        m < 60 -> "$m Minutes Ago"
        m < 120 -> "1 Hour Ago"
        m < 1440 -> "${m / 60} Hours Ago"
        m < 2880 -> "Yesterday"
        m < 10080 -> "${m / 1440} Days Ago"
        else -> Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
    }
}

private object CoreNotices : Notices {
    private val n get() = Services.notices
    override fun list() = n.notices.value.map {
        Notice(
            it.key, when (it.type) {
                "expiry" -> NoticeType.Expiry; "limit" -> NoticeType.Limit; "episodes" -> NoticeType.Episodes
                "update" -> NoticeType.Update; "summary" -> NoticeType.Summary; else -> NoticeType.Info
            }, it.title, it.body, agoWords(it.at), it.read, it.action?.label, it.severity != NoticeSeverity.INFO, it.action?.target,
            critical = it.severity == NoticeSeverity.CRITICAL,
        )
    }
    override fun markAllRead() = n.markAllRead()
    override fun clearAll() = n.notices.value.forEach { n.dismiss(it.key) }
    override fun remove(key: String) = n.dismiss(key)
    override fun open(key: String) = n.markRead(key)
}

/**
 * Start With Windows: the per-user Run key pointing at the installed app's launcher (jpackage sets
 * `jpackage.app-path`). From a development run there is no launcher, so it stays off.
 */
object StartWithWindows {
    private const val KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "Dream OS"

    fun set(on: Boolean?): Boolean {
        val exe = System.getProperty("jpackage.app-path")
        if (on != null && exe != null) {
            val cmd = if (on) listOf("reg", "add", KEY, "/v", NAME, "/t", "REG_SZ", "/d", "\"$exe\"", "/f") else listOf("reg", "delete", KEY, "/v", NAME, "/f")
            runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor() }
        }
        return runCatching {
            val p = ProcessBuilder("reg", "query", KEY, "/v", NAME).redirectErrorStream(true).start()
            p.waitFor() == 0
        }.getOrDefault(false)
    }
}
