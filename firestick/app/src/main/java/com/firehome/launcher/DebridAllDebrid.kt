package com.firehome.launcher

import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * AllDebrid (api.alldebrid.com v4) for [NativeResolver]. AllDebrid has no free cache check: the only
 * test is magnet/upload, which adds the magnet to the user's account, and the account takes at most
 * 1000 magnets, counting recently deleted ones (MAGNET_TOO_MANY: hit on 2026-10-25 by speculative
 * lookups uploading ten candidates per title). So uploads are spent sparingly:
 *
 *  - Only for a real Play, the next episode and the source list's top rows ([DebridPurpose]; browsing
 *    and opening a title upload nothing), within a rolling 24 h budget ([DebridLedger.allowance]).
 *  - Candidates go one at a time (then in twos), stopping at the first ready one; hashes seen ready
 *    lately go first within their quality, hashes seen not cached in the last ~6 h are skipped
 *    ([DebridLedger.cached]).
 *  - Every hash is recorded in [DebridLedger] BEFORE it is uploaded; after each lookup everything it
 *    added except the magnet handed to the player is deleted in one batched call ([cleanup], on every
 *    path: found, nothing, errors, deadline). The played one is kept (whether an unlocked link outlives
 *    its magnet is unverified) until the [janitor] removes it: on app start, hourly, and at once on
 *    MAGNET_TOO_MANY, it deletes Dream OS's magnets older than [MAX_AGE] or beyond [CAP] that aren't
 *    playing. The user's own magnets (everything in the account Dream OS never claimed) are never touched.
 *  - On MAGNET_TOO_MANY: janitor, one retry, then AllDebrid is skipped for [LIMIT_MS] ([limited]) and
 *    the other linked services are asked instead.
 *
 * The first ready one's file tree (magnet/files) gives the file ([DebridFiles.pick]), link/unlock the
 * direct HTTPS link. ~0.3 s a call. Tokens and links never reach the log.
 */
internal class AllDebridService(private val token: String, private val purpose: DebridPurpose = DebridPurpose.PLAY) : DebridService {
    override val key = "ad"
    private val scope = DebridLedger.scope(key, token)

    private class Mag(val id: String, val ready: Boolean)
    private class Entry(val path: String, val bytes: Long, val link: String)

    override fun resolve(candidates: List<Candidate>, deadline: Long): Found? {
        if (candidates.isEmpty() || refused() || limited(token)) return null
        val allow = DebridLedger.allowance(key, purpose)
        if (allow <= 0) return null
        snapshot()
        val order = order(candidates)
        if (order.isEmpty()) {
            Log.i(TAG, "AllDebrid: none of ${candidates.size} cached (all seen uncached in the last hours; nothing uploaded)")
            return null
        }
        val added = LinkedHashMap<String, String>() // hash -> magnet id: what this lookup added, for cleanup
        var kept: String? = null
        var sent = 0
        try {
            var i = 0
            var batch = 1
            while (i < order.size && sent < allow && SystemClock.elapsedRealtime() < deadline) {
                val chunk = order.subList(i, minOf(i + batch, order.size, i + (allow - sent)))
                i += chunk.size; sent += chunk.size; batch = 2
                val mags = upload(chunk, deadline, added) ?: break
                for (c in chunk) {
                    val m = mags[c.hash]?.takeIf { it.ready } ?: continue
                    if (SystemClock.elapsedRealtime() >= deadline) break
                    val f = open(c, m, deadline) ?: continue
                    kept = c.hash
                    if (c.hash in added) DebridLedger.markPlayed(scope, c.hash)
                    Log.i(TAG, "AllDebrid: link after $sent upload${if (sent == 1) "" else "s"} (${candidates.size} candidates)")
                    return f
                }
            }
            if (!limited(token)) Log.i(TAG, "AllDebrid: nothing cached after $sent upload${if (sent == 1) "" else "s"} (${candidates.size} candidates)")
            return null
        } finally {
            cleanup(added, kept)
        }
    }

    /**
     * Blocking. Which of [candidates]' top rows AllDebrid has cached (info hashes), for the source
     * list's "Ready" marks: what [DebridLedger] knows, plus one upload of the unknown ones (deleted
     * again right after). Null when it can't tell at all.
     */
    fun readyHashes(candidates: List<Candidate>, deadline: Long): Set<String>? {
        if (candidates.isEmpty() || refused()) return null
        val top = candidates.take(DebridPurpose.LIST.maxUploads)
        val ready = top.filter { DebridLedger.cached(key, it.hash) == true }.mapTo(HashSet()) { it.hash }
        if (limited(token)) return ready
        val ask = top.filter { DebridLedger.cached(key, it.hash) == null }.take(DebridLedger.allowance(key, purpose))
        if (ask.isEmpty()) return ready
        snapshot()
        val added = LinkedHashMap<String, String>()
        try {
            upload(ask, deadline, added)?.forEach { (h, m) -> if (m.ready) ready += h }
        } finally {
            cleanup(added, null)
        }
        return ready
    }

    /** First upload ever for this account: what it holds now is the user's own (an hourly listing keeps this current). */
    private fun snapshot() {
        if (!DebridLedger.usersKnown(scope)) janitor(token, "first look at the account", 4_000)
    }

    /**
     * [candidates] minus hashes seen uncached lately; within each quality (in rank order), the ones
     * seen ready lately first, so a Play usually costs one upload without trading quality.
     */
    private fun order(candidates: List<Candidate>): List<Candidate> {
        val state = candidates.associate { it.hash to DebridLedger.cached(key, it.hash) }
        val live = candidates.filter { state[it.hash] != false }
        return live.map { it.quality }.distinct().flatMap { q -> live.filter { it.quality == q }.sortedBy { if (state[it.hash] == true) 0 else 1 } }
    }

    /**
     * One magnet/upload of [chunk]: claimed in the ledger first, counted, sent. What it added goes
     * into [added]; each hash's cache state is remembered. On an account-limit error: the janitor,
     * one retry, then the limit is remembered ([LIMIT_MS]). Null when nothing came back.
     */
    private fun upload(chunk: List<Candidate>, deadline: Long, added: MutableMap<String, String>): Map<String, Mag>? {
        val hashes = chunk.map { it.hash }
        val mine = DebridLedger.claim(scope, hashes)
        var r = send(hashes, deadline)
        if (r.limit) r = heal(hashes, deadline, r.error)
        val arr = r.data?.optJSONArray("magnets")
        if (arr == null) {
            // An error answer added nothing; a lost answer may have (the janitor settles those claims from its listing).
            if (r.answered) DebridLedger.owned(scope).let { own -> DebridLedger.release(scope, mine.filter { own[it]?.id == null }) }
            return null
        }
        val mags = HashMap<String, Mag>()
        val seen = HashMap<String, Boolean>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val h = o.optString("hash").lowercase()
            val id = o.opt("id")?.toString()?.takeIf { it.isNotEmpty() } ?: continue // rejected: an "error" instead
            val ready = o.optBoolean("ready")
            mags[h] = Mag(id, ready)
            seen[h] = ready
            if (h in mine) { DebridLedger.setId(scope, h, id); added[h] = id }
        }
        val own = DebridLedger.owned(scope)
        DebridLedger.release(scope, mine.filter { it !in mags && own[it]?.id == null })
        DebridLedger.seen(key, seen)
        return mags
    }

    private fun send(hashes: List<String>, deadline: Long): Res {
        DebridLedger.counted(key, hashes.size, purpose == DebridPurpose.PLAY)
        val r = call(token, "magnet/upload", hashes.map { "magnets[]" to it }, deadline)
        // Every magnet refused for a limit counts as the account's limit too.
        val arr = r.data?.optJSONArray("magnets")
        if (arr != null && arr.length() > 0) {
            val codes = (0 until arr.length()).map { arr.optJSONObject(it)?.optJSONObject("error")?.optString("code").orEmpty() }
            if (codes.all { isLimit(it) }) return Res(null, codes.first(), true)
        }
        return r
    }

    /** MAGNET_TOO_MANY (or a similar limit): the janitor (bounded), one retry; still full: AllDebrid rests for [LIMIT_MS]. */
    private fun heal(hashes: List<String>, deadline: Long, code: String): Res {
        val sweep = janitor(token, "magnet limit", 3_000, aggressive = true)
        val again = send(hashes, deadline)
        if (!again.limit) {
            Log.w(TAG, "AllDebrid: magnet limit; janitor removed ${sweep?.removed ?: 0}, retry ${if (again.data != null) "worked" else "failed (${again.error})"}")
            return again
        }
        DebridLedger.setLimited(scope, DebridLedger.wall() + LIMIT_MS)
        if (sweep != null && sweep.dream == 0 && sweep.user >= 900) {
            Log.w(TAG, "AllDebrid account is full of your own magnets; Dream Core can't add new ones")
        } else {
            Log.w(TAG, "AllDebrid: magnet limit (${again.error.ifEmpty { code }}) after the janitor removed ${sweep?.removed ?: 0} " +
                "(Dream OS holds ${sweep?.dream ?: "?"}, yours ${sweep?.user ?: "?"}; the limit counts recently deleted magnets too); " +
                "AllDebrid skipped for ${LIMIT_MS / 60_000} min")
        }
        return again
    }

    /** The chosen magnet's file for [c], unlocked to a direct link; null when it has none that fits or no link yet. */
    private fun open(c: Candidate, m: Mag, deadline: Long): Found? {
        val tree = call(token, "magnet/files", listOf("id[]" to m.id), deadline).data
            ?.optJSONArray("magnets")?.optJSONObject(0)?.optJSONArray("files") ?: return null
        val files = ArrayList<Entry>().also { flatten(tree, "", it) }
        val f = files.getOrNull(DebridFiles.pick(files.map { it.path to it.bytes }, c)) ?: return null
        if (SystemClock.elapsedRealtime() >= deadline) return null
        // No link but "delayed": AllDebrid would have to prepare the file first; the next one may be instant.
        val u = call(token, "link/unlock", listOf("link" to f.link), deadline).data ?: return null
        val url = u.optString("link").takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
        return Found(url, key, u.optString("filename").ifBlank { DebridFiles.base(f.path) },
            u.optLong("filesize").takeIf { it > 0 } ?: f.bytes, c.quality, c.hash)
    }

    /** Everything this lookup added but [kept] (the one handed to the player), deleted in one batched call, in the background. */
    private fun cleanup(added: Map<String, String>, kept: String?) {
        if (added.isEmpty()) return
        val owned = DebridLedger.owned(scope)
        val protect = DebridLedger.inUse()
        val drop = added.filter { (h, _) -> h != kept && h !in protect && owned[h]?.played != true }
        val keep = if (kept != null && kept in added) " (kept 1 ${if (purpose == DebridPurpose.PLAY) "playing" else "ready to play"})" else ""
        if (drop.isEmpty()) { if (keep.isNotEmpty()) Log.i(TAG, "AllDebrid: cleaned 0$keep"); return }
        Net.run {
            val gone = delete(token, drop.map { it.value to it.key }, SystemClock.elapsedRealtime() + 10_000)
            DebridLedger.release(scope, gone)
            Log.w(TAG, "AllDebrid: cleaned ${gone.size}$keep" + if (gone.size < drop.size) "; ${drop.size - gone.size} left for the janitor" else "")
        }
    }

    /** AllDebrid's file tree ({n, s, l} files, {n, e: [...]} folders) as flat paths. */
    private fun flatten(nodes: JSONArray, dir: String, out: MutableList<Entry>) {
        for (i in 0 until nodes.length()) {
            val n = nodes.optJSONObject(i) ?: continue
            val path = if (dir.isEmpty()) n.optString("n") else dir + "/" + n.optString("n")
            val sub = n.optJSONArray("e")
            if (sub != null) flatten(sub, path, out)
            else n.optString("l").takeIf { it.isNotEmpty() }?.let { out.add(Entry(path, n.optLong("s"), it)) }
        }
    }

    private fun refused() = refusedKey == token.hashCode() && SystemClock.elapsedRealtime() - refusedAt < RETRY_MS

    /** One API call's outcome: [data] on success; [error] AllDebrid's code ("" when none); [answered]: AllDebrid replied. */
    class Res(val data: JSONObject?, val error: String, val answered: Boolean) {
        val limit get() = data == null && isLimit(error)
    }

    /** What a janitor run saw: magnets it removed, Dream OS's left, the user's own. */
    class Sweep(val removed: Int, val dream: Int, val user: Int)

    companion object {
        private const val TAG = "FireHome"
        const val API = "https://api.alldebrid.com/v4/"
        private const val API_41 = "https://api.alldebrid.com/v4.1/"
        const val AGENT = "DreamOS"
        /** A refused key is tried again after this long (a blocked account may have been unblocked by email). */
        private const val RETRY_MS = 10 * 60_000L
        /** After MAGNET_TOO_MANY survives the janitor, AllDebrid is skipped this long (then tried again). */
        const val LIMIT_MS = 30 * 60_000L
        /** Dream OS's magnets older than this (and not playing) are removed by the janitor. */
        const val MAX_AGE = 2 * 3_600_000L
        /** At most this many of Dream OS's magnets stay in the account (oldest removed first). */
        const val CAP = 25
        private const val HOUR = 3_600_000L
        @Volatile private var refusedKey = 0
        @Volatile private var refusedAt = 0L
        @Volatile private var sweptFor = 0
        @Volatile private var sweptAt = 0L
        private val sweeping = ReentrantLock()

        fun isLimit(code: String) = code.isNotEmpty() && (code == "MAGNET_TOO_MANY" || "TOO_MANY" in code || "LIMIT" in code || "QUOTA" in code)

        /** AllDebrid hit its magnet limit lately (remembered [LIMIT_MS], across restarts): skip it, ask the other services. */
        fun limited(token: String) = DebridLedger.limitedUntil(DebridLedger.scope("ad", token)) > DebridLedger.wall()

        /**
         * Blocking (background). The connection opened ahead and, on app start and then at most hourly,
         * the [janitor]; in between, a ping keeps the socket.
         */
        fun warm(token: String) {
            val now = SystemClock.elapsedRealtime()
            if (sweptFor == token.hashCode() && now - sweptAt < HOUR) {
                try { DebridHttp.get("${API}ping?agent=$AGENT", timeoutMs = 5_000) } catch (e: Exception) {}
                return
            }
            val first = sweptFor != token.hashCode()
            sweptFor = token.hashCode(); sweptAt = now
            janitor(token, if (first) "app start" else "hourly", 8_000)
        }

        /**
         * Blocking. Lists the account and deletes Dream OS's magnets (claimed in [DebridLedger]) that
         * are older than [MAX_AGE] or beyond [CAP] (oldest first), never one in [DebridLedger.inUse];
         * [aggressive] (the account is full): every one not in use. Everything else in the account is
         * the user's own: recorded as such, never touched. At most [boundMs] for the listing and for
         * the delete each. Null when the account can't be listed (or another run holds it past the bound).
         */
        fun janitor(token: String, why: String, boundMs: Int, aggressive: Boolean = false): Sweep? {
            if (!sweeping.tryLock(boundMs.toLong(), TimeUnit.MILLISECONDS)) return null
            try {
                val scope = DebridLedger.scope("ad", token)
                val end = SystemClock.elapsedRealtime() + boundMs
                val r = call(token, "magnet/status", emptyList(), end, API_41)
                val arr = r.data?.optJSONArray("magnets") ?: run { Log.w(TAG, "AllDebrid janitor: account not listed (${r.error.ifEmpty { "no answer" }})"); return null }
                class Item(val id: String, val hash: String)
                val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
                    val h = o.optString("hash").lowercase().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    Item(o.opt("id")?.toString().orEmpty(), h)
                }
                val owned = DebridLedger.owned(scope)
                val held = items.mapTo(HashSet()) { it.hash }
                val user = items.filter { it.hash !in owned }
                DebridLedger.setUsers(scope, user.mapTo(HashSet()) { it.hash })
                val now = DebridLedger.wall()
                // Claims no longer in the account are gone (a claim under 2 min old may be an upload still on its way).
                DebridLedger.release(scope, owned.filter { (h, o) -> h !in held && now - o.at > 120_000 }.keys)
                val dream = items.filter { it.hash in owned }
                for (d in dream) if (owned[d.hash]?.id == null && d.id.isNotEmpty()) DebridLedger.setId(scope, d.hash, d.id)
                val protect = DebridLedger.inUse()
                val free = dream.filter { it.hash !in protect && it.id.isNotEmpty() }.sortedBy { owned[it.hash]?.at ?: 0L }
                val drop = LinkedHashSet<Item>()
                free.filterTo(drop) { aggressive || now - (owned[it.hash]?.at ?: 0L) > MAX_AGE }
                val over = dream.size - drop.size - CAP
                if (over > 0) free.filter { it !in drop }.take(over).forEach { drop += it }
                val gone = if (drop.isEmpty()) emptySet() else delete(token, drop.map { it.id to it.hash }, SystemClock.elapsedRealtime() + boundMs)
                DebridLedger.release(scope, gone)
                if (gone.isNotEmpty() || why != "hourly") {
                    Log.w(TAG, "AllDebrid janitor ($why): removed ${gone.size} Dream OS magnet${if (gone.size == 1) "" else "s"} " +
                        "(user's ${user.size} untouched; Dream OS keeps ${dream.size - gone.size})")
                }
                return Sweep(gone.size, dream.size - gone.size, user.size)
            } catch (e: Exception) {
                Log.w(TAG, "AllDebrid janitor: failed (${e.javaClass.simpleName})"); return null
            } finally {
                sweeping.unlock()
            }
        }

        /**
         * Blocking. Deletes magnets ([id] to hash) in one batched call; if AllDebrid refuses the batch
         * (one id already gone fails it all), one by one. The hashes that are gone afterwards.
         */
        fun delete(token: String, items: List<Pair<String, String>>, deadline: Long): Set<String> {
            if (items.isEmpty()) return emptySet()
            val r = call(token, "magnet/delete", items.map { "ids[]" to it.first }, deadline)
            if (r.answered && r.error.isEmpty()) return items.mapTo(HashSet()) { it.second }
            if (items.size == 1 && r.error != "MAGNET_INVALID_ID") return emptySet()
            val gone = HashSet<String>()
            for ((id, h) in items) {
                if (SystemClock.elapsedRealtime() >= deadline) break
                val one = if (items.size == 1) r else call(token, "magnet/delete", listOf("id" to id), deadline)
                if (one.answered && (one.error.isEmpty() || one.error == "MAGNET_INVALID_ID")) gone += h
            }
            return gone
        }

        /** One API call (form POST, Bearer token), logged without the token. */
        fun call(token: String, path: String, fields: List<Pair<String, String>>, deadline: Long, base: String = API): Res {
            val r = try {
                DebridHttp.postForm("$base$path?agent=$AGENT", fields, token, DebridHttp.left(deadline))
            } catch (e: Exception) {
                Log.w(TAG, "AllDebrid: $path failed (${e.javaClass.simpleName})"); return Res(null, "", false)
            }
            val j = try { r.json() } catch (e: Exception) { Log.w(TAG, "AllDebrid: $path HTTP ${r.code}, not JSON"); return Res(null, "", false) }
            if (j.optString("status") != "error") {
                val d = j.optJSONObject("data")
                if (d != null || path == "magnet/delete") return Res(d ?: JSONObject(), "", true)
                Log.w(TAG, "AllDebrid: $path HTTP ${r.code}"); return Res(null, "", true)
            }
            val code = j.optJSONObject("error")?.optString("code").orEmpty().ifEmpty { "ERROR" }
            if (code.startsWith("AUTH_") || code == "MUST_BE_PREMIUM" || code.startsWith("ACCOUNT_")) refuse(token, code)
            else if (code != "MAGNET_INVALID_ID" || path != "magnet/delete") Log.w(TAG, "AllDebrid: $path error $code")
            return Res(null, code, true)
        }

        /** The account can't be used (bad key, blocked, not premium): skipped for a while, logged once per key. */
        private fun refuse(token: String, code: String) {
            val h = token.hashCode()
            if (refusedKey != h) Log.w(TAG, if (code.startsWith("AUTH_")) "AllDebrid: sign-in no longer valid ($code)" else "AllDebrid: account can't stream ($code)")
            refusedKey = h; refusedAt = SystemClock.elapsedRealtime()
        }
    }
}
