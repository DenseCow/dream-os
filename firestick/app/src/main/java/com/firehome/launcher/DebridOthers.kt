package com.firehome.launcher

import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Premiumize, TorBox and Real-Debrid behind [DebridService]. Endpoints and request shapes follow
 * the services' public API docs and the request shapes common open-source clients use, which is what the
 * stored tokens were made for, checked against each service's API docs. Tokens and stream URLs
 * never reach the log.
 */
private object DebridOthersKit {
    const val TAG = "FireHome"

    fun now() = SystemClock.elapsedRealtime()

    /** A bare-hash magnet; the name helps the service label the item if it ever shows in the account. */
    fun magnet(c: Candidate) = "magnet:?xt=urn:btih:${c.hash}" +
        (if (c.torrentName.isNotBlank()) "&dn=" + DebridHttp.enc(c.torrentName) else "")

    /** JSON numbers that some services send as strings. */
    fun num(o: Any?): Long = when (o) {
        is Number -> o.toLong()
        is String -> o.toDoubleOrNull()?.toLong() ?: 0L
        else -> 0L
    }

    /** Sleeps [ms] only when the deadline still leaves room for the request that follows; false when it doesn't. */
    fun pause(ms: Long, deadline: Long): Boolean {
        if (deadline - now() < ms + 1_000) return false
        Thread.sleep(ms)
        return true
    }

    /** A hand-built multipart/form-data body (TorBox's createtorrent takes nothing else): body and its Content-Type. */
    fun multipart(fields: List<Pair<String, String>>): Pair<ByteArray, String> {
        val b = "DreamOS" + java.lang.Long.toHexString(System.nanoTime())
        val sb = StringBuilder()
        for ((k, v) in fields) {
            sb.append("--").append(b).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(k).append("\"\r\n\r\n")
                .append(v).append("\r\n")
        }
        sb.append("--").append(b).append("--\r\n")
        return sb.toString().toByteArray(Charsets.UTF_8) to "multipart/form-data; boundary=$b"
    }

    /**
     * The first [limit] of [cached] through [unlock] side by side ([DebridWork]); none of them opened: the rest
     * in turn, as a single lookup did. The links come back in rank order.
     */
    fun unlockMany(cached: List<Candidate>, limit: Int, deadline: Long, unlock: (Candidate) -> Found?): List<Found> {
        val race = cached.take(limit.coerceAtLeast(1))
        var out = if (race.size > 1) DebridWork.each(race, deadline, unlock) else listOfNotNull(race.firstOrNull()?.let(unlock))
        if (out.isEmpty()) for (c in cached.drop(race.size)) {
            if (now() >= deadline) break
            val f = unlock(c) ?: continue
            out = listOf(f)
            break
        }
        return out
    }

    fun found(url: String, service: String, c: Candidate, path: String, size: Long) =
        Found(url, service, DebridFiles.base(path), if (size > 0) size else c.sizeBytes, c.quality, c.hash)
}

/**
 * Premiumize ("pm"): one batched cache check for every candidate, then /transfer/directdl on the
 * first cached one. directdl hands back the file links without creating a transfer, so nothing is
 * added to the account and there is nothing to clean up. The OAuth device token goes as a Bearer
 * header (`apikey` is for the account's API key).
 */
internal class PremiumizeService(private val token: String) : DebridService {
    override val key = "pm"

    private companion object {
        const val API = "https://www.premiumize.me/api"
        const val MAX = 60 // hashes per check
    }

    override fun resolve(candidates: List<Candidate>, deadline: Long): Found? = resolveMany(candidates, deadline, 1).firstOrNull()

    /** The first [limit] cached candidates unlocked side by side (directdl adds nothing to the account). */
    override fun resolveMany(candidates: List<Candidate>, deadline: Long, limit: Int): List<Found> = try {
        val t0 = DebridOthersKit.now()
        val cached = cached(candidates.take(MAX), deadline)
        val out = DebridOthersKit.unlockMany(cached, limit, deadline) { unlock(it, deadline) }
        if (out.isNotEmpty()) Log.w(DebridOthersKit.TAG, "Premiumize: ${out.size} link${if (out.size == 1) "" else "s"} in ${DebridOthersKit.now() - t0} ms")
        out
    } catch (e: Exception) {
        Log.w(DebridOthersKit.TAG, "Premiumize: resolve failed: ${e.javaClass.simpleName}")
        emptyList()
    }

    /** [list] Premiumize has cached, in rank order, from one batched check; empty when it didn't answer. */
    private fun cached(list: List<Candidate>, deadline: Long): List<Candidate> {
        if (list.isEmpty() || token.isBlank()) return emptyList()
        val t0 = DebridOthersKit.now()
        // A form POST of items[], as the API docs list it (POST /api/cache/check) and batched checks send it.
        val r = DebridHttp.postForm("$API/cache/check", list.map { "items[]" to it.hash }, token, DebridHttp.left(deadline))
        val o = if (r.ok) r.json() else null
        if (o == null || o.optString("status") != "success") {
            // Premiumize answers errors with HTTP 200 and status "error" (code "authentication_failed" for a bad token).
            Log.w(DebridOthersKit.TAG, "Premiumize: cache check failed (HTTP ${r.code} ${o?.optString("code").orEmpty()})")
            return emptyList()
        }
        val flags = o.optJSONArray("response") ?: return emptyList()
        val cached = list.indices.filter { flags.optBoolean(it, false) }.map { list[it] }
        Log.i(DebridOthersKit.TAG, "Premiumize: ${cached.size} of ${list.size} cached (${DebridOthersKit.now() - t0} ms)")
        return cached
    }

    /** The candidate's file through directdl; `link` is the original file (`stream_link` is a transcode). */
    private fun unlock(c: Candidate, deadline: Long): Found? {
        val r = DebridHttp.postForm("$API/transfer/directdl", listOf("src" to DebridOthersKit.magnet(c)), token, DebridHttp.left(deadline))
        val o = if (r.ok) r.json() else return null
        if (o.optString("status") != "success") {
            Log.w(DebridOthersKit.TAG, "Premiumize: directdl refused (${o.optString("code").ifEmpty { o.optString("message").take(60) }})")
            return null
        }
        val content = o.optJSONArray("content") ?: return null
        val items = (0 until content.length()).mapNotNull { content.optJSONObject(it) }
            .filter { it.optString("link").startsWith("https") || it.optString("stream_link").startsWith("https") }
        val idx = DebridFiles.pick(items.map { it.optString("path") to DebridOthersKit.num(it.opt("size")) }, c)
        if (idx < 0) return null
        val f = items[idx]
        val url = f.optString("link").takeIf { it.startsWith("https") } ?: f.optString("stream_link")
        return DebridOthersKit.found(url, key, c, f.optString("path"), DebridOthersKit.num(f.opt("size")))
    }
}

/**
 * TorBox ("tb"): one batched cache check (POST /torrents/checkcached, list format with file
 * lists, so a pack without the episode is skipped before anything is added), then for the first
 * fit: createtorrent with add_only_if_cached (it can never start a real download), the file ids
 * from mylist, and requestdl for the link. The torrent that is played is kept in the account:
 * TorBox doesn't document whether a requestdl link outlives its torrent, and a link dying
 * mid-film is worse than a finished, cached item in the list. Ones added and not used are deleted.
 * Every torrent added is recorded in [DebridLedger] before it is created; the [janitor] (app start,
 * then hourly) removes Dream OS's kept ones after two hours (never one in use) and keeps at most
 * [CAP]. A torrent the user already had (same hash) is never deleted.
 */
internal class TorBoxService(private val token: String) : DebridService {
    override val key = "tb"
    private val scope = DebridLedger.scope("tb", token)

    companion object {
        private const val API = "https://api.torbox.app/v1/api"
        private const val MAX = 100
        private const val CAP = 25
        private const val MAX_AGE = 2 * 3_600_000L
        @Volatile private var sweptFor = 0
        @Volatile private var sweptAt = 0L

        /**
         * Blocking (background). On app start, then at most hourly: lists the account, records what
         * Dream OS never added as the user's own, and deletes Dream OS's torrents older than two hours
         * or beyond [CAP] (oldest first) that aren't in use ([DebridLedger.inUse]).
         */
        fun janitor(token: String, force: Boolean = false) {
            val now = DebridOthersKit.now()
            if (!force && sweptFor == token.hashCode() && now - sweptAt < 3_600_000L) return
            val first = sweptFor != token.hashCode()
            sweptFor = token.hashCode(); sweptAt = now
            TorBoxService(token).sweep(if (first) "app start" else "hourly")
        }
    }

    private fun sweep(why: String) {
        val r = try { DebridHttp.get("$API/torrents/mylist?bypass_cache=true", token, 10_000) } catch (e: Exception) { null }
        val arr = r?.takeIf { it.ok }?.let { try { it.json().optJSONArray("data") } catch (e: Exception) { null } } ?: run {
            Log.w(DebridOthersKit.TAG, "TorBox janitor: account not listed (HTTP ${r?.code ?: 0})"); return
        }
        val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .mapNotNull { o -> o.optString("hash").lowercase().takeIf { it.isNotEmpty() }?.let { it to o.optLong("id", -1L) } }
        val owned = DebridLedger.owned(scope)
        val held = items.mapTo(HashSet()) { it.first }
        val user = items.filter { it.first !in owned }
        DebridLedger.setUsers(scope, user.mapTo(HashSet()) { it.first })
        val wall = DebridLedger.wall()
        DebridLedger.release(scope, owned.filter { (h, o) -> h !in held && wall - o.at > 120_000 }.keys)
        val dream = items.filter { it.first in owned }
        val protect = DebridLedger.inUse()
        val free = dream.filter { it.first !in protect && it.second >= 0 }.sortedBy { owned[it.first]?.at ?: 0L }
        val drop = free.filter { wall - (owned[it.first]?.at ?: 0L) > MAX_AGE }.toMutableList()
        val over = dream.size - drop.size - CAP
        if (over > 0) drop += free.filter { it !in drop }.take(over)
        var n = 0
        for ((h, id) in drop) if (delete(id)) { DebridLedger.release(scope, listOf(h)); n++ }
        if (n > 0 || why != "hourly") Log.w(DebridOthersKit.TAG, "TorBox janitor ($why): removed $n Dream OS torrent${if (n == 1) "" else "s"} (user's ${user.size} untouched)")
    }

    override fun resolve(candidates: List<Candidate>, deadline: Long): Found? = resolveMany(candidates, deadline, 1).firstOrNull()

    /**
     * The first [limit] cached candidates that hold the file, added (cached only) and unlocked side by side. Each
     * alternate is a torrent kept in the account until [discard] (the winner started) or the janitor.
     */
    override fun resolveMany(candidates: List<Candidate>, deadline: Long, limit: Int): List<Found> = try {
        val t0 = DebridOthersKit.now()
        val fits = fits(candidates.take(MAX), deadline)
        val out = DebridOthersKit.unlockMany(fits, limit, deadline) { unlock(it, deadline) }
        if (out.isNotEmpty()) Log.w(DebridOthersKit.TAG, "TorBox: ${out.size} link${if (out.size == 1) "" else "s"} in ${DebridOthersKit.now() - t0} ms")
        out
    } catch (e: Exception) {
        Log.w(DebridOthersKit.TAG, "TorBox: resolve failed: ${e.javaClass.simpleName}")
        emptyList()
    }

    /** Alternates no longer needed: their torrents (added by Dream OS, not in use) are deleted now, not by the janitor two hours on. */
    override fun discard(hashes: Collection<String>) {
        val owned = DebridLedger.owned(scope)
        val protect = DebridLedger.inUse()
        val drop = hashes.mapNotNull { h -> if (h in protect) null else owned[h]?.id?.toLongOrNull()?.let { h to it } }
        if (drop.isEmpty()) return
        Net.run {
            var n = 0
            for ((h, id) in drop) if (delete(id)) { DebridLedger.release(scope, listOf(h)); n++ }
            Log.w(DebridOthersKit.TAG, "TorBox: dropped $n alternate${if (n == 1) "" else "s"}")
        }
    }

    /**
     * [list] TorBox has cached and that hold the wanted file (a pack without the episode is skipped before anything
     * is added), in rank order, from one batched check; empty when it didn't answer.
     */
    private fun fits(list: List<Candidate>, deadline: Long): List<Candidate> {
        if (list.isEmpty() || token.isBlank()) return emptyList()
        val t0 = DebridOthersKit.now()
        val body = JSONObject().put("hashes", JSONArray(list.map { it.hash })).toString().toByteArray()
        val r = DebridHttp.request("$API/torrents/checkcached?format=list&list_files=true", "POST", token, body,
            "application/json", DebridHttp.left(deadline))
        if (!r.ok) {
            // 401/403 with error BAD_TOKEN for a stale token.
            Log.w(DebridOthersKit.TAG, "TorBox: cache check failed (HTTP ${r.code} ${errorOf(r)})")
            return emptyList()
        }
        val cached = cachedFiles(r.json().opt("data"))
        val fits = list.filter { c ->
            val files = cached[c.hash.lowercase()] ?: return@filter false
            files.isEmpty() || DebridFiles.pick(files, c) >= 0
        }
        Log.i(DebridOthersKit.TAG, "TorBox: ${fits.size} of ${list.size} cached (${DebridOthersKit.now() - t0} ms)")
        return fits
    }

    /** hash -> its files (name, size) from a checkcached reply, in list or object format; uncached hashes are absent. */
    private fun cachedFiles(data: Any?): Map<String, List<Pair<String, Long>>> {
        val out = HashMap<String, List<Pair<String, Long>>>()
        fun add(fallbackHash: String?, t: JSONObject) {
            val h = t.optString("hash").ifEmpty { fallbackHash.orEmpty() }.lowercase()
            if (h.isEmpty()) return
            val fa = t.optJSONArray("files")
            out[h] = if (fa == null) emptyList() else (0 until fa.length()).mapNotNull { fa.optJSONObject(it) }
                .map { it.optString("name").ifEmpty { it.optString("short_name") } to DebridOthersKit.num(it.opt("size")) }
        }
        when (data) {
            is JSONArray -> for (i in 0 until data.length()) data.optJSONObject(i)?.let { add(null, it) }
            is JSONObject -> for (k in data.keys()) data.optJSONObject(k)?.let { add(k, it) }
        }
        return out
    }

    private fun unlock(c: Candidate, deadline: Long): Found? {
        val h = c.hash.lowercase()
        // Recorded before it is added; a hash the user already has is theirs and is never deleted from here.
        val mine = h in DebridLedger.claim(scope, listOf(h))
        val (body, type) = DebridOthersKit.multipart(listOf(
            "magnet" to DebridOthersKit.magnet(c), "seed" to "3", "allow_zip" to "false", "add_only_if_cached" to "true",
        ))
        val add = DebridHttp.request("$API/torrents/createtorrent", "POST", token, body, type, DebridHttp.left(deadline))
        val id = if (add.ok) add.json().optJSONObject("data")?.optLong("torrent_id", -1L) ?: -1L else -1L
        if (id < 0) {
            Log.w(DebridOthersKit.TAG, "TorBox: add refused (HTTP ${add.code} ${errorOf(add)})")
            if (mine && DebridLedger.owned(scope)[h]?.id == null) DebridLedger.release(scope, listOf(h))
            return null
        }
        if (mine) DebridLedger.setId(scope, h, id.toString())
        var keep = !mine
        try {
            var files = files(id, deadline)
            // A just-added torrent can list its files a moment later.
            if (files.isEmpty() && DebridOthersKit.pause(400, deadline)) files = files(id, deadline)
            val idx = DebridFiles.pick(files.map { it.second to it.third }, c)
            if (idx < 0 || DebridOthersKit.now() >= deadline) return null
            val (fid, path, size) = files[idx]
            val r = DebridHttp.get("$API/torrents/requestdl?token=${DebridHttp.enc(token)}&torrent_id=$id&file_id=$fid",
                token, DebridHttp.left(deadline))
            val url = if (r.ok) r.json().optString("data") else ""
            if (!url.startsWith("http")) {
                Log.w(DebridOthersKit.TAG, "TorBox: no link (HTTP ${r.code} ${errorOf(r)})")
                return null
            }
            keep = true
            if (mine) DebridLedger.markPlayed(scope, h)
            return DebridOthersKit.found(url, key, c, path, size)
        } finally {
            if (!keep && h !in DebridLedger.inUse()) Net.run { if (delete(id)) DebridLedger.release(scope, listOf(h)) }
        }
    }

    /** (file id, name, size) of torrent [id]; bypass_cache, as mylist is otherwise cached for a while server side. */
    private fun files(id: Long, deadline: Long): List<Triple<Long, String, Long>> {
        val r = DebridHttp.get("$API/torrents/mylist?id=$id&bypass_cache=true", token, DebridHttp.left(deadline))
        if (!r.ok) return emptyList()
        val d = r.json().opt("data")
        val t = when (d) { is JSONObject -> d; is JSONArray -> d.optJSONObject(0); else -> null } ?: return emptyList()
        val fa = t.optJSONArray("files") ?: return emptyList()
        return (0 until fa.length()).mapNotNull { fa.optJSONObject(it) }.map {
            Triple(it.optLong("id"), it.optString("name").ifEmpty { it.optString("short_name") }, DebridOthersKit.num(it.opt("size")))
        }
    }

    private fun delete(id: Long): Boolean {
        val body = JSONObject().put("torrent_id", id).put("operation", "delete").toString().toByteArray()
        val r = DebridHttp.request("$API/torrents/controltorrent", "POST", token, body, "application/json", 8_000)
        if (!r.ok) Log.w(DebridOthersKit.TAG, "TorBox: cleanup of an unused torrent failed (HTTP ${r.code})")
        return r.ok
    }

    private fun errorOf(r: DebridHttp.Reply) = try { r.json().optString("error") } catch (e: Exception) { "" }
}

/**
 * Real-Debrid ("rd"). Its cache check (instantAvailability) was switched off in late 2024, so the
 * only test is to add the magnet, select the one file wanted and see whether it is "downloaded" at
 * once: cached torrents are, anything else is a real download and is deleted straight away.
 * Candidates go in parallel rounds of [BATCH] (about one round trip chain each, ~2 s a round); the
 * best-ranked cached one of a round is unrestricted, every torrent added is deleted afterwards
 * (the unrestricted link lives on in the account's downloads). Each
 * torrent id is recorded in [DebridLedger] the moment it is added and dropped once deleted; the
 * [janitor] (app start, then hourly) deletes any left over (a cleanup that failed, the app killed
 * mid-lookup). Real-Debrid gives every add its own id, so the user's own torrents are never touched.
 * The ledger is kept per device client id, which a token refresh doesn't change.
 *
 * The access token lasts about an hour; on a 401 it is refreshed with the user's own device
 * credentials and saved in Dream OS's accounts (DreamAccounts; [RealDebridToken]).
 */
internal class RealDebridService(private val accounts: DebridAccounts.Accounts) : DebridService {
    override val key = "rd"

    private companion object {
        const val REST = "https://api.real-debrid.com/rest/1.0/"
        const val BATCH = 3
        const val MAX = 9 // three rounds at most: each try is a torrent added to the account for a moment
        /** Statuses a cached torrent can show for a moment right after the file is selected. */
        val WAITING = setOf("queued", "waiting_files_selection", "magnet_conversion")

        val lock = Any()
        /** The stored token [refreshedTo] replaces; a different stored token (re-linked) makes it stale. */
        @Volatile var refreshedFrom: String? = null
        @Volatile var refreshedTo: String? = null
        @Volatile var refreshCode: String? = null
        @Volatile var refreshFailedAt = 0L

        val pool = ThreadPoolExecutor(BATCH, BATCH, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
            Thread(r, "rd-probe").apply { isDaemon = true }
        }.apply { allowCoreThreadTimeOut(true) }

        @Volatile var sweptFor = 0
        @Volatile var sweptAt = 0L
    }

    /**
     * Blocking (background). On app start, then at most hourly: test torrents Dream OS added and
     * never got to delete (older than 10 min) are deleted now.
     */
    fun sweep(force: Boolean = false) {
        val now = DebridOthersKit.now()
        if (stored.isBlank() || (!force && sweptFor == stored.hashCode() && now - sweptAt < 3_600_000L)) return
        sweptFor = stored.hashCode(); sweptAt = now
        var found = 0
        var n = 0
        // Builds before the client-id scope kept the ledger under the access token: its leftovers are swept too.
        for (sc in listOf(scope, DebridLedger.scope("rd", stored)).distinct()) {
            val left = DebridLedger.owned(sc).filter { DebridLedger.wall() - it.value.at > 10 * 60_000L }
            found += left.size
            for ((k, o) in left) {
                val id = o.id ?: k.removePrefix("id:")
                val r = try { DebridHttp.request("${REST}torrents/delete/$id", "DELETE", token(), timeoutMs = 8_000) } catch (e: Exception) { null } ?: continue
                // 404: already gone.
                if (r.ok || r.code == 404) { DebridLedger.release(sc, listOf(k)); if (r.ok) n++ }
            }
        }
        if (found > 0) Log.w(DebridOthersKit.TAG, "Real-Debrid janitor: removed $n leftover test torrent${if (n == 1) "" else "s"}")
    }

    private val stored = accounts.realDebrid.orEmpty()
    /** The access token changes on every refresh; the device client id stays for the life of the sign-in. */
    private val scope = DebridLedger.scope("rd", accounts.realDebridClientId.ifBlank { stored })

    private fun token(): String = refreshedTo?.takeIf { refreshedFrom == stored } ?: stored

    /** A cached torrent found by a probe: its torrent [id] and the hoster [link] of the picked file. */
    private class Probe(val rank: Int, val c: Candidate, val id: String, val path: String, val bytes: Long, val link: String)

    /** One parallel round: probes hand their cached torrent in here, or delete it themselves once the round closed. */
    private class Round {
        private val got = ArrayList<Probe>()
        private var closed = false
        @Synchronized fun offer(p: Probe): Boolean { if (closed) return false; got.add(p); return true }
        @Synchronized fun close(): List<Probe> { closed = true; return got.sortedBy { it.rank } }
    }

    override fun resolve(candidates: List<Candidate>, deadline: Long): Found? = resolveMany(candidates, deadline, 1).firstOrNull()

    /**
     * Rounds of probes as before; within the round that has cached ones, up to [limit] are unrestricted side by
     * side. The unrestricted links live on after the probe torrents are deleted, so alternates add nothing to the account.
     */
    override fun resolveMany(candidates: List<Candidate>, deadline: Long, limit: Int): List<Found> = try {
        find(candidates.take(MAX), deadline, limit.coerceAtLeast(1))
    } catch (e: Exception) {
        Log.w(DebridOthersKit.TAG, "Real-Debrid: resolve failed: ${e.javaClass.simpleName}")
        emptyList()
    }

    private fun find(list: List<Candidate>, deadline: Long, limit: Int): List<Found> {
        if (list.isEmpty() || stored.isBlank()) return emptyList()
        val t0 = DebridOthersKit.now()
        for (start in list.indices step BATCH) {
            if (DebridOthersKit.now() >= deadline) break
            val round = Round()
            val futures: List<Future<*>> = list.subList(start, minOf(start + BATCH, list.size)).mapIndexed { i, c ->
                pool.submit(Runnable { probe(c, start + i, round, deadline) })
            }
            for (f in futures) {
                val ms = deadline - DebridOthersKit.now()
                if (ms <= 0) break
                try { f.get(ms, TimeUnit.MILLISECONDS) } catch (e: TimeoutException) { break } catch (e: Exception) { }
            }
            val cached = round.close()
            var found: List<Found> = emptyList()
            try {
                if (limit > 1 && cached.size > 1) {
                    found = DebridWork.each(cached.take(limit), deadline) { unrestrict(it, deadline) }
                } else for (p in cached) {
                    if (DebridOthersKit.now() >= deadline) break
                    val f = unrestrict(p, deadline) ?: continue
                    found = listOf(f)
                    break
                }
                if (found.isNotEmpty()) Log.w(DebridOthersKit.TAG, "Real-Debrid: ${found.size} link${if (found.size == 1) "" else "s"} in ${DebridOthersKit.now() - t0} ms")
            } finally {
                // Also when unrestrict throws (network, bad JSON): the round's torrents are in the account either way.
                if (cached.isNotEmpty()) Net.run { for (p in cached) delete(p.id) }
            }
            if (found.isNotEmpty()) return found
        }
        Log.i(DebridOthersKit.TAG, "Real-Debrid: none of ${list.size} cached (${DebridOthersKit.now() - t0} ms)")
        return emptyList()
    }

    /** Adds [c], selects its file and checks it is complete at once. Any torrent not handed to [round] is deleted. */
    private fun probe(c: Candidate, rank: Int, round: Round, deadline: Long) {
        var id: String? = null
        var handed = false
        try {
            val add = call("POST", "torrents/addMagnet", listOf("magnet" to DebridOthersKit.magnet(c)), deadline)
            id = if (add.ok) add.json().optString("id").ifEmpty { null } else null
            id?.let { DebridLedger.claim(scope, listOf("id:$it")); DebridLedger.setId(scope, "id:$it", it) }
            if (id == null) {
                if (!add.ok) Log.w(DebridOthersKit.TAG, "Real-Debrid: add refused (HTTP ${add.code} ${errorOf(add)})")
                return
            }
            var info = torrentInfo(id, deadline) ?: return
            // A torrent RD knows has its file list at once; magnet_conversion is a hash it has never seen.
            if (info.optString("status") == "magnet_conversion" && DebridOthersKit.pause(350, deadline)) info = torrentInfo(id, deadline) ?: return
            if (info.optString("status") != "waiting_files_selection") return
            val fa = info.optJSONArray("files") ?: return
            val files = (0 until fa.length()).mapNotNull { fa.optJSONObject(it) }
            val idx = DebridFiles.pick(files.map { it.optString("path") to it.optLong("bytes") }, c)
            if (idx < 0 || DebridOthersKit.now() >= deadline) return
            val file = files[idx]
            val sel = call("POST", "torrents/selectFiles/$id", listOf("files" to file.optLong("id").toString()), deadline)
            if (!sel.ok) return
            info = torrentInfo(id, deadline) ?: return
            if (info.optString("status") in WAITING && DebridOthersKit.pause(400, deadline)) info = torrentInfo(id, deadline) ?: return
            if (info.optString("status") != "downloaded") return
            val link = info.optJSONArray("links")?.optString(0).orEmpty()
            if (!link.startsWith("http")) return
            handed = round.offer(Probe(rank, c, id, file.optString("path"), file.optLong("bytes"), link))
        } catch (e: Exception) {
            // network trouble: treated as not cached
        } finally {
            val drop = id
            if (drop != null && !handed) Net.run { delete(drop) }
        }
    }

    private fun torrentInfo(id: String, deadline: Long): JSONObject? {
        val r = call("GET", "torrents/info/$id", null, deadline)
        return if (r.ok) r.json() else null
    }

    /** The direct HTTPS link for a cached probe; RAR archives and the like don't play, so they don't count. */
    private fun unrestrict(p: Probe, deadline: Long): Found? {
        val r = call("POST", "unrestrict/link", listOf("link" to p.link), deadline)
        if (!r.ok) {
            Log.w(DebridOthersKit.TAG, "Real-Debrid: unrestrict refused (HTTP ${r.code} ${errorOf(r)})")
            return null
        }
        val o = r.json()
        val url = o.optString("download")
        val name = o.optString("filename").ifEmpty { DebridFiles.base(p.path) }
        if (!url.startsWith("https") || !DebridFiles.isVideo(name)) return null
        return DebridOthersKit.found(url, key, p.c, name, o.optLong("filesize").takeIf { it > 0 } ?: p.bytes)
    }

    private fun delete(id: String) {
        val r = DebridHttp.request("${REST}torrents/delete/$id", "DELETE", token(), timeoutMs = 8_000)
        if (r.ok || r.code == 404) DebridLedger.release(scope, listOf("id:$id"))
        if (!r.ok) Log.w(DebridOthersKit.TAG, "Real-Debrid: cleanup of a test torrent failed (HTTP ${r.code}); the janitor retries")
    }

    /** A REST call with the current token; on 401 (bad_token: expired) refreshes once and retries. */
    private fun call(method: String, path: String, fields: List<Pair<String, String>>?, deadline: Long): DebridHttp.Reply {
        fun send(t: String) = if (fields == null) DebridHttp.request(REST + path, method, t, timeoutMs = DebridHttp.left(deadline))
            else DebridHttp.request(REST + path, method, t, DebridHttp.form(fields).toByteArray(), "application/x-www-form-urlencoded",
                DebridHttp.left(deadline))
        val used = token()
        val r = send(used)
        if (r.code != 401 || !refresh(used, deadline)) return r
        return send(token())
    }

    /** Swaps in a fresh access token unless another probe already did; false when that isn't possible. */
    private fun refresh(failed: String, deadline: Long): Boolean {
        synchronized(lock) {
            if (token() != failed) return true
            if (DebridOthersKit.now() - refreshFailedAt < 60_000 && refreshFailedAt != 0L) return false
            val code = refreshCode?.takeIf { refreshedFrom == stored } ?: accounts.realDebridRefresh
            val r = RealDebridToken.renew(accounts, code, DebridHttp.left(deadline))
            if (r.code == RealDebridToken.NO_CREDENTIALS) return false
            val access = r.access
            if (access == null) {
                refreshFailedAt = DebridOthersKit.now()
                Log.w(DebridOthersKit.TAG, "Real-Debrid: token refresh failed (HTTP ${r.code})")
                return false
            }
            refreshCode = r.refresh ?: code
            refreshedTo = access
            refreshedFrom = stored
            refreshFailedAt = 0L
            Log.i(DebridOthersKit.TAG, "Real-Debrid: token refreshed (saved in Dream OS)")
            return true
        }
    }

    private fun errorOf(r: DebridHttp.Reply) = try { r.json().optString("error") } catch (e: Exception) { "" }
}

/**
 * Real-Debrid's access token renewal with the user's own device credentials, shared by the resolver,
 * Streaming Accounts' check and the premium check. A new token is saved in Dream OS at once.
 */
internal object RealDebridToken {
    private const val OAUTH = "https://api.real-debrid.com/oauth/v2/token"
    /** [Renewal.code] when the account has no device credentials to renew with. */
    const val NO_CREDENTIALS = -1

    /** [code]: HTTP status (0 no answer); [access] / [refresh]: the new tokens, null when refused. */
    class Renewal(val code: Int, val access: String?, val refresh: String?)

    /** Blocking. [code] is the refresh code to use (the saved one by default). */
    fun renew(acc: DebridAccounts.Accounts, code: String = acc.realDebridRefresh, timeoutMs: Int): Renewal {
        if (acc.realDebridClientId.isBlank() || acc.realDebridSecret.isBlank() || code.isBlank()) return Renewal(NO_CREDENTIALS, null, null)
        val r = try {
            DebridHttp.postForm(OAUTH, listOf(
                "client_id" to acc.realDebridClientId, "client_secret" to acc.realDebridSecret,
                "code" to code, "grant_type" to "http://oauth.net/grant_type/device/1.0",
            ), timeoutMs = timeoutMs)
        } catch (e: Exception) { null } ?: return Renewal(0, null, null)
        val o = try { r.takeIf { it.ok }?.json() } catch (e: Exception) { null }
        val access = o?.optString("access_token").orEmpty()
        if (access.isEmpty()) return Renewal(r.code, null, null)
        val refresh = o?.optString("refresh_token")?.ifEmpty { null }
        DreamAccounts.refreshedRealDebrid(access, refresh)
        return Renewal(r.code, access, refresh)
    }
}
