package com.dreamos.desktop.core.playback

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** AllDebrid's per-account state for one app run (refused keys, the janitor's last sweep). */
class AllDebridState {
    @Volatile var refusedScope = ""
    @Volatile var refusedAt = 0L
    /** AUTH_BLOCKED: a sign-in from a new network waiting for the owner's approval by email. */
    @Volatile var blockedScope = ""
    @Volatile var blockedAt = 0L
    @Volatile var sweptFor = ""
    @Volatile var sweptAt = 0L
    val sweeping = Mutex()
}

/**
 * AllDebrid (api.alldebrid.com v4) for Dream Core (fire-home DebridAllDebrid.kt + the iPhone's race). AllDebrid has no
 * free cache check: the only test is magnet/upload, which adds the magnet to the viewer's account, and the account
 * takes at most 1000 magnets, counting recently deleted ones. So uploads are spent sparingly:
 *  - Only for a real Play, the next episode and the source list's top rows ([DebridPurpose]), within a rolling 24 h
 *    budget ([DebridLedger.allowance]).
 *  - The first upload sends the top [RACE_CAP] at once (both ready ones opened side by side, the second the alternate),
 *    then one or two at a time, stopping at the first ready one; hashes seen not cached in the last ~6 h are skipped.
 *  - Every hash is recorded in the ledger BEFORE it is uploaded; after each lookup everything it added except what was
 *    handed out is deleted in one batched call, on every path. The played one stays until the janitor (app start,
 *    hourly, at once on MAGNET_TOO_MANY) removes Dream OS's magnets older than [MAX_AGE] or beyond [CAP] not in use.
 *    The viewer's own magnets are never touched; before the account was listed once, nothing is uploaded.
 *  - On MAGNET_TOO_MANY: janitor, one retry, then AllDebrid is skipped for [LIMIT_MS].
 * Worst case per Play: 10 uploads, 1 files call and 2 unlocks; per day 300 uploads outside Plays.
 */
class AllDebridService(private val kit: DebridKit, private val token: String, private val purpose: DebridPurpose = DebridPurpose.PLAY,
                       private val st: AllDebridState) : DebridService {
    override val id = DebridServiceId.ALLDEBRID
    private val scope = DebridLedger.scope(key, token)
    private val ledger get() = kit.ledger

    private class Mag(val id: String, val ready: Boolean)
    private class Entry(val path: String, val bytes: Long, val link: String)
    class Res(val data: JsonElement?, val error: String, val answered: Boolean) { val limit get() = data == null && isLimit(error) }
    class Sweep(val removed: Int, val dream: Int, val user: Int)

    companion object {
        const val API = "https://api.alldebrid.com/v4/"
        private const val API_41 = "https://api.alldebrid.com/v4.1/"
        const val AGENT = "DreamOS"
        private const val RETRY_MS = 10 * 60_000L
        const val LIMIT_MS = 30 * 60_000L
        const val MAX_AGE = 2 * 3_600_000L
        const val CAP = 25
        const val RACE_CAP = 2
        private const val HOUR = 3_600_000L

        fun isLimit(code: String) = code.isNotEmpty() && (code == "MAGNET_TOO_MANY" || "TOO_MANY" in code || "LIMIT" in code || "QUOTA" in code)
        /** AllDebrid hit its magnet limit lately (remembered across restarts): skip it, ask the other services. */
        fun limited(ledger: DebridLedger, token: String, wall: Long) = ledger.limitedUntil(DebridLedger.scope("ad", token)) > wall
    }

    private fun refused() = st.refusedScope == scope && kit.now() - st.refusedAt < RETRY_MS
    /** AllDebrid answered AUTH_BLOCKED for this account in the last [RETRY_MS]. */
    fun blocked() = st.blockedScope == scope && kit.now() - st.blockedAt < RETRY_MS
    private fun limited() = limited(ledger, token, kit.clock.wallMs())

    override suspend fun resolveMany(sources: List<Source>, deadline: Long, limit: Int): List<Stream> {
        if (sources.isEmpty() || refused() || limited()) return emptyList()
        val allow = ledger.allowance(key, purpose)
        if (allow <= 0) return emptyList()
        snapshot()
        if (!ledger.usersKnown(scope)) { PlaybackLog.w("AllDebrid: account not listed yet; skipped (the viewer's magnets stay untouched)"); return emptyList() }
        val order = order(sources)
        if (order.isEmpty()) { PlaybackLog.i("AllDebrid: none of ${sources.size} cached (all seen uncached in the last hours; nothing uploaded)"); return emptyList() }
        val race = limit.coerceIn(1, RACE_CAP)
        val added = LinkedHashMap<String, String>() // hash -> magnet id: what this lookup added, for cleanup
        val kept = HashSet<String>()
        var sent = 0
        try {
            var i = 0
            var batch = race
            while (i < order.size && sent < allow && kit.now() < deadline) {
                val chunk = order.subList(i, minOf(i + batch, order.size, i + (allow - sent)))
                i += chunk.size; sent += chunk.size; batch = 2
                val mags = upload(chunk, deadline, added) ?: break
                val ready = chunk.mapNotNull { c -> mags[c.hash]?.takeIf { it.ready }?.let { c to it } }
                if (ready.isEmpty()) continue
                if (kit.now() >= deadline) break
                val out: List<Stream> = if (race > 1 && ready.size > 1) coroutineScope {
                    ready.take(race).map { (c, m) -> async { open(c, m, deadline) } }.awaitAll().filterNotNull()
                } else {
                    var one: Stream? = null
                    for ((c, m) in ready) { if (kit.now() >= deadline) break; one = open(c, m, deadline); if (one != null) break }
                    listOfNotNull(one)
                }
                val first = out.firstOrNull() ?: continue
                out.forEach { kept += it.hash }
                if (first.hash in added) ledger.markPlayed(scope, first.hash)
                PlaybackLog.i("AllDebrid: ${if (out.size == 1) "link" else "${out.size} links"} after $sent upload${if (sent == 1) "" else "s"} (${sources.size} candidates)")
                return out
            }
            if (!limited()) PlaybackLog.i("AllDebrid: nothing cached after $sent upload${if (sent == 1) "" else "s"} (${sources.size} candidates)")
            return emptyList()
        } finally {
            cleanup(added, kept)
        }
    }

    /** Alternates no longer needed: their magnets (added by Dream OS, not playing) are deleted now. */
    override fun discard(hashes: List<String>) {
        val owned = ledger.owned(scope)
        val protect = ledger.inUse()
        val drop = hashes.mapNotNull { h -> owned[h]?.i?.takeIf { h !in protect }?.let { it to h } }
        if (drop.isEmpty()) return
        kit.background("AllDebrid: discard") {
            val gone = delete(drop, kit.now() + 10_000)
            ledger.release(scope, gone)
            PlaybackLog.i("AllDebrid: dropped ${gone.size} alternate${if (gone.size == 1) "" else "s"}")
        }
    }

    /**
     * Which of [sources]' top rows AllDebrid has cached, for the source list's "Ready" marks: what the ledger knows, plus
     * one upload of the unknown ones (deleted again right after). Null when it can't tell at all.
     */
    suspend fun readyHashes(sources: List<Source>, deadline: Long): Set<String>? {
        if (sources.isEmpty() || refused()) return null
        val top = sources.take(DebridPurpose.LIST.maxUploads)
        val ready = top.filter { ledger.cached(key, it.hash) == true }.mapTo(HashSet()) { it.hash }
        if (limited()) return ready
        val ask = top.filter { ledger.cached(key, it.hash) == null }.take(ledger.allowance(key, purpose))
        if (ask.isEmpty()) return ready
        snapshot()
        if (!ledger.usersKnown(scope)) return ready
        val added = LinkedHashMap<String, String>()
        try { upload(ask, deadline, added)?.forEach { (h, m) -> if (m.ready) ready += h } } finally { cleanup(added, emptySet()) }
        return ready
    }

    /** First upload ever for this account: what it holds now is the viewer's own. */
    private suspend fun snapshot() { if (!ledger.usersKnown(scope)) janitor("first look at the account", 4_000) }

    /** [sources] minus hashes seen uncached lately; within each quality (in rank order) the ones seen ready lately first. */
    private fun order(sources: List<Source>): List<Source> {
        val state = sources.associate { it.hash to ledger.cached(key, it.hash) }
        val live = sources.filter { state[it.hash] != false }
        return live.map { it.quality }.distinct().flatMap { q -> live.filter { it.quality == q }.sortedBy { if (state[it.hash] == true) 0 else 1 } }
    }

    /** One magnet/upload of [chunk]: claimed first, counted, sent; what it added goes into [added]. Null when nothing came back. */
    private suspend fun upload(chunk: List<Source>, deadline: Long, added: MutableMap<String, String>): Map<String, Mag>? {
        val hashes = chunk.map { it.hash }
        val mine = ledger.claim(scope, hashes)
        var r = send(hashes, deadline)
        if (r.limit) r = heal(hashes, deadline, r.error)
        val arr = r.data["magnets"] as? kotlinx.serialization.json.JsonArray
        if (arr == null) {
            // An error answer added nothing; a lost answer may have (the janitor settles those claims from its listing).
            if (r.answered) ledger.owned(scope).let { own -> ledger.release(scope, mine.filter { own[it]?.i == null }) }
            return null
        }
        val mags = HashMap<String, Mag>()
        val seen = HashMap<String, Boolean>()
        for (o in arr) {
            val h = o["hash"].str.lowercase()
            val id = o["id"].str.takeIf { it.isNotEmpty() } ?: continue // rejected: an "error" instead
            val ready = o["ready"].bool
            mags[h] = Mag(id, ready); seen[h] = ready
            if (h in mine) { ledger.setId(scope, h, id); added[h] = id }
        }
        val own = ledger.owned(scope)
        ledger.release(scope, mine.filter { it !in mags && own[it]?.i == null })
        ledger.seen(key, seen)
        return mags
    }

    private suspend fun send(hashes: List<String>, deadline: Long): Res {
        ledger.counted(key, hashes.size, purpose == DebridPurpose.PLAY)
        val r = call("magnet/upload", hashes.map { "magnets[]" to it }, deadline)
        val arr = r.data["magnets"].arr
        if (arr.isNotEmpty()) {
            val codes = arr.map { it["error"]["code"].str }
            if (codes.all { isLimit(it) }) return Res(null, codes.first(), true)
        }
        return r
    }

    /** MAGNET_TOO_MANY: the janitor (bounded), one retry; still full: AllDebrid rests for [LIMIT_MS]. */
    private suspend fun heal(hashes: List<String>, deadline: Long, code: String): Res {
        val sweep = janitor("magnet limit", 3_000, aggressive = true)
        val again = send(hashes, deadline)
        if (!again.limit) {
            PlaybackLog.w("AllDebrid: magnet limit; janitor removed ${sweep?.removed ?: 0}, retry ${if (again.data != null) "worked" else "failed (${again.error})"}")
            return again
        }
        ledger.setLimited(scope, kit.clock.wallMs() + LIMIT_MS)
        PlaybackLog.w("AllDebrid: magnet limit (${again.error.ifEmpty { code }}) after the janitor removed ${sweep?.removed ?: 0} " +
            "(Dream OS holds ${sweep?.dream ?: "?"}, the viewer's ${sweep?.user ?: "?"}); AllDebrid skipped for ${LIMIT_MS / 60_000} min")
        return again
    }

    /** The chosen magnet's file for [c], unlocked to a direct link; null when it has none that fits or no link yet. */
    private suspend fun open(c: Source, m: Mag, deadline: Long): Stream? {
        val tree = call("magnet/files", listOf("id[]" to m.id), deadline).data["magnets"][0]["files"].arr
        if (tree.isEmpty()) return null
        val files = ArrayList<Entry>().also { flatten(tree, "", it) }
        val f = files.getOrNull(DebridFiles.pick(files.map { it.path to it.bytes }, c)) ?: return null
        if (kit.now() >= deadline) return null
        val u = call("link/unlock", listOf("link" to f.link), deadline).data ?: return null
        val url = u["link"].str.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
        return Stream(url, id, u["filename"].str.ifBlank { DebridFiles.base(f.path) }, u["filesize"].long.takeIf { it > 0 } ?: f.bytes,
            c.quality, c.hash, releaseName = c.torrentName)
    }

    /** Everything this lookup added but [kept], deleted in one batched call in the background. */
    private fun cleanup(added: Map<String, String>, kept: Set<String>) {
        if (added.isEmpty()) return
        val owned = ledger.owned(scope)
        val protect = ledger.inUse()
        val drop = added.filter { (h, _) -> h !in kept && h !in protect && owned[h]?.p != true }
        if (drop.isEmpty()) return
        kit.background("AllDebrid: cleanup") {
            val gone = delete(drop.map { it.value to it.key }, kit.now() + 10_000)
            ledger.release(scope, gone)
            PlaybackLog.w("AllDebrid: cleaned ${gone.size}" + if (gone.size < drop.size) "; ${drop.size - gone.size} left for the janitor" else "")
        }
    }

    private fun flatten(nodes: List<JsonElement>, dir: String, out: MutableList<Entry>) {
        for (n in nodes) {
            val path = if (dir.isEmpty()) n["n"].str else dir + "/" + n["n"].str
            val sub = n["e"]
            if (sub is kotlinx.serialization.json.JsonArray) flatten(sub, path, out)
            else n["l"].str.takeIf { it.isNotEmpty() }?.let { out += Entry(path, n["s"].long, it) }
        }
    }

    /** Background. The janitor on app start and then at most hourly. */
    suspend fun warm() {
        val now = kit.now()
        if (st.sweptFor == scope && now - st.sweptAt < HOUR) return
        val first = st.sweptFor != scope
        st.sweptFor = scope; st.sweptAt = now
        janitor(if (first) "app start" else "hourly", 8_000)
    }

    /**
     * Lists the account and deletes Dream OS's magnets older than [MAX_AGE] or beyond [CAP] (oldest first), never one in
     * use; [aggressive] (the account is full): every one not in use. Everything else is the viewer's own: recorded as
     * such, never touched. At most [boundMs] for the listing and for the delete each. Null when not listed.
     */
    suspend fun janitor(why: String, boundMs: Long, aggressive: Boolean = false): Sweep? {
        val got = withTimeoutOrNull(boundMs) { st.sweeping.lock(); true } ?: return null
        if (!got) return null
        try {
            val end = kit.now() + boundMs
            val r = call("magnet/status", emptyList(), end, API_41)
            val arr = r.data["magnets"] as? kotlinx.serialization.json.JsonArray
                ?: run { PlaybackLog.w("AllDebrid janitor: account not listed (${r.error.ifEmpty { "no answer" }})"); return null }
            class Item(val id: String, val hash: String)
            val items = arr.mapNotNull { o -> o["hash"].str.lowercase().takeIf { it.isNotEmpty() }?.let { Item(o["id"].str, it) } }
            val owned = ledger.owned(scope)
            val held = items.mapTo(HashSet()) { it.hash }
            val user = items.filter { it.hash !in owned }
            ledger.setUsers(scope, user.mapTo(HashSet()) { it.hash })
            val wall = kit.clock.wallMs()
            ledger.release(scope, owned.filter { (h, o) -> h !in held && wall - o.t > 120_000 }.keys)
            val dream = items.filter { it.hash in owned }
            for (d in dream) if (owned[d.hash]?.i == null && d.id.isNotEmpty()) ledger.setId(scope, d.hash, d.id)
            val protect = ledger.inUse()
            val free = dream.filter { it.hash !in protect && it.id.isNotEmpty() }.sortedBy { owned[it.hash]?.t ?: 0L }
            val drop = LinkedHashSet<Item>()
            free.filterTo(drop) { aggressive || wall - (owned[it.hash]?.t ?: 0L) > MAX_AGE }
            val over = dream.size - drop.size - CAP
            if (over > 0) free.filter { it !in drop }.take(over).forEach { drop += it }
            val gone = if (drop.isEmpty()) emptySet() else delete(drop.map { it.id to it.hash }, kit.now() + boundMs)
            ledger.release(scope, gone)
            if (gone.isNotEmpty() || why != "hourly") PlaybackLog.w("AllDebrid janitor ($why): removed ${gone.size} Dream OS magnet${if (gone.size == 1) "" else "s"} " +
                "(the viewer's ${user.size} untouched; Dream OS keeps ${dream.size - gone.size})")
            return Sweep(gone.size, dream.size - gone.size, user.size)
        } finally {
            st.sweeping.unlock()
        }
    }

    /** Deletes magnets (id to hash) in one batched call; refused as a batch (one id already gone fails it all): one by one. */
    private suspend fun delete(items: List<Pair<String, String>>, deadline: Long): Set<String> {
        if (items.isEmpty()) return emptySet()
        val r = call("magnet/delete", items.map { "ids[]" to it.first }, deadline)
        if (r.answered && r.error.isEmpty()) return items.mapTo(HashSet()) { it.second }
        if (items.size == 1 && r.error != "MAGNET_INVALID_ID") return emptySet()
        val gone = HashSet<String>()
        for ((id, h) in items) {
            if (kit.now() >= deadline) break
            val one = if (items.size == 1) r else call("magnet/delete", listOf("id" to id), deadline)
            if (one.answered && (one.error.isEmpty() || one.error == "MAGNET_INVALID_ID")) gone += h
        }
        return gone
    }

    /** One API call (form POST, Bearer token), logged without the token. */
    suspend fun call(path: String, fields: List<Pair<String, String>>, deadline: Long, base: String = API): Res {
        val r = kit.http.postForm("$base$path?agent=$AGENT", fields, token, kit.http.left(deadline))
        if (r.code == 0) { PlaybackLog.w("AllDebrid: $path no answer"); return Res(null, "", false) }
        val j = r.json() as? JsonObject ?: run { PlaybackLog.w("AllDebrid: $path HTTP ${r.code}, not JSON"); return Res(null, "", false) }
        if (j["status"].str != "error") {
            val d = j["data"]
            if (d is JsonObject || path == "magnet/delete") return Res(d ?: JsonObject(emptyMap()), "", true)
            PlaybackLog.w("AllDebrid: $path HTTP ${r.code}"); return Res(null, "", true)
        }
        val code = j["error"]["code"].str.ifEmpty { "ERROR" }
        when {
            code == "AUTH_BLOCKED" -> {
                if (st.blockedScope != scope) PlaybackLog.w("AllDebrid: this network waits for the owner's approval (AUTH_BLOCKED)")
                st.blockedScope = scope; st.blockedAt = kit.now(); st.refusedScope = scope; st.refusedAt = kit.now()
            }
            code.startsWith("AUTH_") || code == "MUST_BE_PREMIUM" || code.startsWith("ACCOUNT_") -> {
                if (st.refusedScope != scope) PlaybackLog.w(if (code.startsWith("AUTH_")) "AllDebrid: sign-in no longer valid ($code)" else "AllDebrid: account can't stream ($code)")
                st.refusedScope = scope; st.refusedAt = kit.now()
            }
            code != "MAGNET_INVALID_ID" || path != "magnet/delete" -> PlaybackLog.w("AllDebrid: $path error $code")
        }
        return Res(null, code, true)
    }
}
