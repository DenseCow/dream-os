package com.dreamos.desktop.settings

import com.dreamos.desktop.profiles.WindowsHello
import com.dreamos.desktop.settings.kit.Mark
import kotlinx.coroutines.delay

/**
 * DEV STAND-INS for core and playback until their services merge: in-memory, believable states so every page can be
 * opened and clicked through. Nothing here talks to a real account. Windows Hello is real ([WindowsHello]).
 */
object FakeServices {
    fun create(linked: Boolean = true, repo: Boolean = true): PagesServices {
        val prefs = MemoryPrefs()
        return PagesServices(
            prefs = prefs,
            debrid = FakeDebrid(linked),
            trakt = FakeTrakt(),
            scrapers = FakeScrapers(repo),
            keys = FakeKeys(linked),
            profiles = FakeProfiles(),
            updates = FakeUpdates(),
            notices = FakeNotices(),
            startWithWindows = { set -> if (set != null) prefs.setBool("pc.startWithWindows", set); prefs.bool("pc.startWithWindows", false) },
            check = object : SetupCheck {
                override suspend fun run(stage: (String) -> Unit): CheckOutcome {
                    stage("Starting Up…"); delay(600); stage("Searching…"); delay(1200); stage("Checking Your Account…"); delay(700)
                    return CheckOutcome(true, 42, 12)
                }
                override suspend fun speed(live: (Int) -> Unit): Int { delay(800); live(64); delay(800); return 118 }
            },
        )
    }
}

class MemoryPrefs : Prefs {
    private val m = HashMap<String, Any>()
    override fun bool(key: String, default: Boolean) = m[key] as? Boolean ?: default
    override fun setBool(key: String, value: Boolean) { m[key] = value }
    override fun str(key: String, default: String) = m[key] as? String ?: default
    override fun setStr(key: String, value: String) { m[key] = value }
}

private class FakeDebrid(linked: Boolean) : DebridAccounts {
    private val st = Debrid.entries.associateWith { DebridState(it, false) }.toMutableMap().apply {
        if (linked) {
            this[Debrid.Premiumize] = DebridState(Debrid.Premiumize, true, "dreamviewer", "Premium", "Connected · Premium Until Jan 10, 2027", Mark.OK)
            this[Debrid.RealDebrid] = DebridState(Debrid.RealDebrid, true, "dreamviewer", "Premium", "Sign In Again", Mark.BAD, needsAttention = true)
        }
    }
    private var polls = 0
    override fun states() = Debrid.entries.map { st[it]!! }
    override suspend fun check(s: Debrid): DebridState { delay(700); return st[s]!! }
    override suspend fun disconnect(s: Debrid) { st[s] = DebridState(s, false) }
    override fun useFirst(s: Debrid) {}
    override suspend fun startLink(s: Debrid): LinkCode { delay(500); polls = 0; return LinkCode("K7QX-2M", "https://${s.site}/pin", s.site + "/pin") }
    override suspend fun pollLink(s: Debrid): LinkResult {
        delay(1500)
        return if (++polls < 4) LinkResult.Waiting
        else LinkResult.Connected(true).also { st[s] = DebridState(s, true, "dreamviewer", "Premium", "Connected · Premium", Mark.OK) }
    }
    override suspend fun linkWithKey(s: Debrid, key: String): LinkResult {
        delay(800)
        return if (key.length < 8) LinkResult.Failed("That key didn't work. Check it and try again.")
        else LinkResult.Connected(true).also { st[s] = DebridState(s, true, "dreamviewer", "Premium", "Connected · Premium", Mark.OK) }
    }
}

private class FakeTrakt : TraktAccount {
    private var s = TraktState(false)
    private var id = ""
    override fun state() = s
    override val needsClientId get() = id.isEmpty()
    override fun setClientId(id: String) { if (id != this.id) s = TraktState(false); this.id = id }
    override val clientIdHint get() = id.takeIf { it.isNotEmpty() }?.let { "Ending in ${it.takeLast(4)}" }
    override suspend fun startSignIn(): LinkCode { delay(400); if (id.length < 40) throw com.dreamos.desktop.core.model.DreamError("That Client ID didn't work. Copy the whole Client ID from your Trakt app and try again."); return LinkCode("4F9A2C1D", "https://trakt.tv/activate", "trakt.tv/activate") }
    override suspend fun pollSignIn(): LinkResult { delay(2500); s = TraktState(true, "dreamviewer", "Last Synced Just Now"); return LinkResult.Connected(true) }
    override suspend fun syncNow(): Boolean { delay(900); s = s.copy(synced = "Last Synced Just Now"); return true }
    override suspend fun disconnect() { s = TraktState(false) }
}

private class FakeScrapers(repo: Boolean) : Scrapers {
    private val repos = if (repo) mutableListOf(ScraperRepo("CocoScrapers", "https://cocojoe2411.github.io", "1.0.45")) else mutableListOf()
    private val prov = if (!repo) mutableListOf() else mutableListOf(
        ScraperProvider("tio", "Torrentio", "Torrent Sources", true, true),
        ScraperProvider("knight", "Knightcrawler", "Torrent Sources", true, true),
        ScraperProvider("bitsearch", "BitSearch", "Torrent Sources", false, false),
        ScraperProvider("eztv", "EZTV", "Torrent Sources", true, true),
        ScraperProvider("yts", "YTS", "Torrent Sources", false, true),
        ScraperProvider("tgx", "TorrentGalaxy", "Torrent Sources", false, false),
    )
    override fun repos() = repos.toList()
    override fun providers() = prov.toList()
    override suspend fun add(address: String, stage: (String) -> Unit): Result<String> {
        stage("Starting…"); delay(600); stage("Downloading…"); delay(1200)
        if (!address.startsWith("https://")) return Result.failure(IllegalArgumentException("That didn't work. Check the address and try again."))
        repos += ScraperRepo("New Repository", address, "1.0.0")
        return Result.success("New Repository")
    }
    override suspend fun update(repo: ScraperRepo): Result<String> { delay(900); return Result.success("Up to Date") }
    override suspend fun remove(repo: ScraperRepo) { repos.remove(repo); if (repos.isEmpty()) prov.clear() }
    override fun setProvider(id: String, on: Boolean) { val i = prov.indexOfFirst { it.id == id }; if (i >= 0) prov[i] = prov[i].copy(on = on) }
    override fun useRecommended() { prov.replaceAll { it.copy(on = it.recommended) } }
    override fun setAll(on: Boolean) { prov.replaceAll { it.copy(on = on) } }
    override val recommendedOn get() = prov.isNotEmpty() && prov.all { it.on == it.recommended }
}

private class FakeKeys(added: Boolean) : Keys {
    override var tmdbAdded = added
    override var omdbAdded = false
    override suspend fun saveTmdb(key: String): Boolean { delay(700); return (key.trim().length == 32).also { if (it) tmdbAdded = true } }
    override suspend fun saveOmdb(key: String): Boolean { delay(700); return (key.trim().length == 8).also { if (it) omdbAdded = true } }
    override fun removeOmdb() { omdbAdded = false }
}

private class FakeProfiles : ProfilesService {
    private val list = mutableListOf(
        Profile("1", "Joe", "😎", 0xFF7C5CFF),
        Profile("2", "Sam", "🌸", 0xFFF472B6, locked = true),
        Profile("3", "Kids", "🐼", 0xFF4ADE80, kidsLevel = 1, pinToLeave = true),
    )
    private val pins = mutableMapOf("2" to "1234")
    private var current = "1"
    private var wrong = 0
    override fun all() = list.toList()
    override fun currentId() = current
    override suspend fun select(id: String, pin: String?): PinCheck {
        val need = pinNeededFor(id)
        if (need != null && (pin == null || checkPin(need, pin) != PinCheck.Ok)) return PinCheck.Wrong
        current = id; return PinCheck.Ok
    }
    override fun pinNeededFor(id: String): String? = when {
        list.firstOrNull { it.id == current }?.let { it.kidsLevel < 3 && it.pinToLeave } == true && id != current -> pins.keys.firstOrNull { k -> list.any { it.id == k && it.kidsLevel == 3 } }
        pins.containsKey(id) -> id
        else -> null
    }
    override suspend fun checkPin(id: String, pin: String): PinCheck = when {
        pins[id] == pin -> { wrong = 0; PinCheck.Ok }
        ++wrong > 5 -> PinCheck.Wait(30 shl (wrong - 6).coerceAtMost(5))
        else -> PinCheck.Wrong
    }
    override suspend fun save(p: Profile, pin: String?): Profile {
        val id = p.id.ifBlank { (list.maxOf { it.id.toInt() } + 1).toString() }
        when { pin == "" -> pins.remove(id); pin != null -> pins[id] = pin }
        val saved = p.copy(id = id, locked = pins.containsKey(id), hello = p.hello && pins.containsKey(id))
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list[i] = saved else list += saved
        return saved
    }
    override suspend fun remove(id: String) { list.removeAll { it.id == id }; pins.remove(id) }
    override val helloReady: Boolean get() = WindowsHello.available()
    override suspend fun helloVerify(reason: String) = WindowsHello.verify(reason)
}

private class FakeUpdates : Updates {
    override val version = System.getProperty("dream.version") ?: "4.3.4"
    override val channel = "DEV"
    private var s: UpdateState = UpdateState.UpToDate("Last Checked Today at 6:42 PM")
    override fun state() = s
    override suspend fun check(): UpdateState { s = UpdateState.Checking; delay(1200); s = UpdateState.UpToDate("Last Checked Just Now"); return s }
}

private class FakeNotices : Notices {
    private val items = mutableListOf(
        Notice("exp:pm", NoticeType.Expiry, "Premiumize Premium Ends in 5 Days", "Renew by Tue, Nov 3 to Keep Streaming", "2 Hours Ago", false, "Renew", warning = true),
        Notice("avail:1", NoticeType.Episodes, "Now Available in 4K", "Dune: Part Two", "Yesterday", false, "Go to Movie"),
        Notice("update:4.3.4", NoticeType.Update, "There's a New Version Available", "Dream OS 4.3.4", "3 Days Ago", true, "Install Now"),
    )
    override fun list() = items.toList()
    override fun markAllRead() { items.replaceAll { it.copy(read = true) } }
    override fun clearAll() { items.clear() }
    override fun remove(key: String) { items.removeAll { it.key == key } }
    override fun open(key: String) { items.replaceAll { if (it.key == key) it.copy(read = true) else it } }
}
