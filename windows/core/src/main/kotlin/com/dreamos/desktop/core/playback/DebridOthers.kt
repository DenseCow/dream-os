package com.dreamos.desktop.core.playback

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/** Unlocks up to [limit] of [list] side by side; best-ranked first in the answer. */
private suspend fun unlockMany(list: List<Source>, unlock: suspend (Source) -> Stream?): List<Stream> = coroutineScope {
    list.map { async { unlock(it) } }.awaitAll().filterNotNull()
}

/**
 * Premiumize ("pm"): one batched cache check for every candidate (free: adds nothing), then /transfer/directdl on the
 * cached ones (no transfer created, nothing to clean up). The OAuth device token goes as a Bearer header; an account
 * linked by API key as the apikey parameter.
 */
class PremiumizeService(private val kit: DebridKit, private val token: String) : DebridService {
    override val id = DebridServiceId.PREMIUMIZE

    companion object {
        const val API = "https://www.premiumize.me/api"
        private const val MAX = 60
    }

    override suspend fun resolveMany(sources: List<Source>, deadline: Long, limit: Int): List<Stream> {
        val cached = cached(sources, deadline)
        if (limit <= 1) {
            for (c in cached) { if (kit.now() >= deadline) break; unlock(c, deadline)?.let { return listOf(it) } }
            return emptyList()
        }
        var out = unlockMany(cached.take(limit)) { unlock(it, deadline) }
        if (out.isEmpty()) for (c in cached.drop(limit)) { if (kit.now() >= deadline) break; unlock(c, deadline)?.let { out = listOf(it) }; if (out.isNotEmpty()) break }
        return out
    }

    /** [sources] Premiumize has cached, in their order; empty when it didn't answer. */
    suspend fun cached(sources: List<Source>, deadline: Long): List<Source> {
        val list = sources.take(MAX)
        if (list.isEmpty() || token.isBlank()) return emptyList()
        val t0 = kit.now()
        val r = kit.http.postForm("$API/cache/check", list.map { "items[]" to it.hash }, token, kit.http.left(deadline))
        val o = if (r.ok) r.json() else null
        if (o == null || o["status"].str != "success") {
            // Premiumize answers errors with HTTP 200 and status "error" ("authentication_failed" for a bad token).
            PlaybackLog.w("Premiumize: cache check failed (HTTP ${r.code} ${o["code"].str})"); return emptyList()
        }
        val flags = o["response"].arr
        val hits = list.filterIndexed { i, _ -> flags.getOrNull(i).bool }
        PlaybackLog.i("Premiumize: ${hits.size} of ${list.size} cached (${kit.now() - t0} ms)")
        return hits
    }

    /** The source's file through directdl; `link` is the original file (`stream_link` is a transcode). */
    private suspend fun unlock(c: Source, deadline: Long): Stream? {
        val r = kit.http.postForm("$API/transfer/directdl", listOf("src" to DebridFiles.magnet(c)), token, kit.http.left(deadline))
        if (!r.ok) return null
        val o = r.json()
        if (o["status"].str != "success") { PlaybackLog.w("Premiumize: directdl refused (${o["code"].str.ifEmpty { o["message"].str.take(60) }})"); return null }
        val items = o["content"].arr.filter { it["link"].str.startsWith("https") || it["stream_link"].str.startsWith("https") }
        val idx = DebridFiles.pick(items.map { it["path"].str to it["size"].long }, c)
        if (idx < 0) return null
        val f = items[idx]
        val url = f["link"].str.takeIf { it.startsWith("https") } ?: f["stream_link"].str
        return kit.found(url, id, c, f["path"].str, f["size"].long)
    }
}

/** TorBox's per-account janitor state for one app run. */
class TorBoxState { @Volatile var sweptFor = ""; @Volatile var sweptAt = 0L }

/**
 * TorBox ("tb"): one batched cache check (checkcached with file lists, so a pack without the episode is skipped before
 * anything is added), then createtorrent with add_only_if_cached (never a real download), the file ids from mylist,
 * and requestdl. The played torrent (and a raced alternate until [discard]) stays in the account; ones added and not
 * used are deleted. Every torrent is recorded in the ledger before it is created; the janitor (app start, then
 * hourly) removes Dream OS's after two hours (never one in use) and keeps at most [CAP]. The viewer's own never.
 */
class TorBoxService(private val kit: DebridKit, private val token: String, private val st: TorBoxState) : DebridService {
    override val id = DebridServiceId.TORBOX
    private val scope = DebridLedger.scope("tb", token)
    private val ledger get() = kit.ledger

    companion object {
        const val API = "https://api.torbox.app/v1/api"
        private const val MAX = 100
        private const val CAP = 25
        private const val MAX_AGE = 2 * 3_600_000L
    }

    override suspend fun resolveMany(sources: List<Source>, deadline: Long, limit: Int): List<Stream> {
        maybeJanitor()
        val fits = fits(sources, deadline)
        if (limit <= 1) {
            for (c in fits) { if (kit.now() >= deadline) break; unlock(c, deadline)?.let { return listOf(it) } }
            return emptyList()
        }
        var out = unlockMany(fits.take(limit)) { unlock(it, deadline) }
        if (out.isEmpty()) for (c in fits.drop(limit)) { if (kit.now() >= deadline) break; unlock(c, deadline)?.let { out = listOf(it) }; if (out.isNotEmpty()) break }
        return out
    }

    override fun discard(hashes: List<String>) {
        val owned = ledger.owned(scope)
        val protect = ledger.inUse()
        val drop = hashes.mapNotNull { h -> owned[h]?.i?.toLongOrNull()?.takeIf { h !in protect }?.let { h to it } }
        if (drop.isEmpty()) return
        kit.background("TorBox: discard") {
            var n = 0
            for ((h, i) in drop) if (delete(i)) { ledger.release(scope, listOf(h)); n++ }
            PlaybackLog.i("TorBox: dropped $n alternate${if (n == 1) "" else "s"}")
        }
    }

    /** [sources] TorBox has cached that hold the wanted file, in order; empty when it didn't answer. */
    suspend fun fits(sources: List<Source>, deadline: Long): List<Source> {
        val list = sources.take(MAX)
        if (list.isEmpty() || token.isBlank()) return emptyList()
        val t0 = kit.now()
        val body = buildJsonObject { putJsonArray("hashes") { list.forEach { add(it.hash) } } }
        val r = kit.http.postJson("$API/torrents/checkcached?format=list&list_files=true", body, token, kit.http.left(deadline))
        if (!r.ok) { PlaybackLog.w("TorBox: cache check failed (HTTP ${r.code} ${r.json()["error"].str})"); return emptyList() }
        val cached = cachedFiles(r.json()["data"])
        val fits = list.filter { c -> cached[c.hash.lowercase()]?.let { it.isEmpty() || DebridFiles.pick(it, c) >= 0 } == true }
        PlaybackLog.i("TorBox: ${fits.size} of ${list.size} cached (${kit.now() - t0} ms)")
        return fits
    }

    private fun cachedFiles(data: JsonElement?): Map<String, List<Pair<String, Long>>> {
        val out = HashMap<String, List<Pair<String, Long>>>()
        fun add(fallback: String?, t: JsonElement) {
            val h = t["hash"].str.ifEmpty { fallback.orEmpty() }.lowercase()
            if (h.isEmpty()) return
            out[h] = t["files"].arr.map { f -> f["name"].str.ifEmpty { f["short_name"].str } to f["size"].long }
        }
        when (data) {
            is JsonArray -> data.forEach { add(null, it) }
            is JsonObject -> data.forEach { (k, v) -> add(k, v) }
            else -> {}
        }
        return out
    }

    private suspend fun unlock(c: Source, deadline: Long): Stream? {
        val h = c.hash.lowercase()
        // Recorded before it is added; a hash the viewer already has is theirs and is never deleted from here.
        val mine = h in ledger.claim(scope, listOf(h))
        val (body, type) = multipart(listOf("magnet" to DebridFiles.magnet(c), "seed" to "3", "allow_zip" to "false", "add_only_if_cached" to "true"))
        val add = kit.http.request("$API/torrents/createtorrent", "POST", token, body, type, kit.http.left(deadline))
        val tidEl = add.json()["data"]["torrent_id"]
        val tid = if (add.ok && tidEl != null) tidEl.long else -1L
        if (tid < 0) {
            PlaybackLog.w("TorBox: add refused (HTTP ${add.code} ${add.json()["error"].str})")
            if (mine && ledger.owned(scope)[h]?.i == null) ledger.release(scope, listOf(h))
            return null
        }
        if (mine) ledger.setId(scope, h, tid.toString())
        var keep = !mine
        try {
            var files = files(tid, deadline)
            // A just-added torrent can list its files a moment later.
            if (files.isEmpty() && kit.pause(400, deadline)) files = files(tid, deadline)
            val idx = DebridFiles.pick(files.map { it.second to it.third }, c)
            if (idx < 0 || kit.now() >= deadline) return null
            val (fid, path, size) = files[idx]
            val r = kit.http.get("$API/torrents/requestdl?token=${DebridHttp.enc(token)}&torrent_id=$tid&file_id=$fid", token, kit.http.left(deadline))
            val url = if (r.ok) r.json()["data"].str else ""
            if (!url.startsWith("http")) { PlaybackLog.w("TorBox: no link (HTTP ${r.code} ${r.json()["error"].str})"); return null }
            keep = true
            if (mine) ledger.markPlayed(scope, h)
            return kit.found(url, id, c, path, size)
        } finally {
            if (!keep && h !in ledger.inUse()) kit.background("TorBox: cleanup") { if (delete(tid)) ledger.release(scope, listOf(h)) }
        }
    }

    /** (file id, name, size) of torrent [tid]; bypass_cache, as mylist is otherwise cached a while server side. */
    private suspend fun files(tid: Long, deadline: Long): List<Triple<Long, String, Long>> {
        val r = kit.http.get("$API/torrents/mylist?id=$tid&bypass_cache=true", token, kit.http.left(deadline))
        if (!r.ok) return emptyList()
        val d = r.json()["data"]
        val t = when (d) { is JsonObject -> d; is JsonArray -> d.getOrNull(0); else -> null } ?: return emptyList()
        return t["files"].arr.map { Triple(it["id"].long, it["name"].str.ifEmpty { it["short_name"].str }, it["size"].long) }
    }

    suspend fun delete(tid: Long): Boolean {
        val body = buildJsonObject { put("torrent_id", tid); put("operation", "delete") }
        val r = kit.http.postJson("$API/torrents/controltorrent", body, token, 8_000)
        if (!r.ok) PlaybackLog.w("TorBox: cleanup of an unused torrent failed (HTTP ${r.code})")
        return r.ok
    }

    private fun maybeJanitor() {
        val now = kit.now()
        if (st.sweptFor == scope && now - st.sweptAt < 3_600_000L) return
        val first = st.sweptFor != scope
        st.sweptFor = scope; st.sweptAt = now
        kit.background("TorBox janitor") { sweep(if (first) "app start" else "hourly") }
    }

    /** Lists the account, records what Dream OS never added as the viewer's own, deletes Dream OS's old or extra ones. */
    suspend fun sweep(why: String) {
        val r = kit.http.get("$API/torrents/mylist?bypass_cache=true", token, 10_000)
        val arr = r.takeIf { it.ok }?.json()?.get("data") as? JsonArray ?: run { PlaybackLog.w("TorBox janitor: account not listed (HTTP ${r.code})"); return }
        val items = arr.mapNotNull { o -> o["hash"].str.lowercase().takeIf { it.isNotEmpty() }?.let { it to (if (o["id"] != null) o["id"].long else -1L) } }
        val owned = ledger.owned(scope)
        val held = items.mapTo(HashSet()) { it.first }
        val user = items.filter { it.first !in owned }
        ledger.setUsers(scope, user.mapTo(HashSet()) { it.first })
        val wall = kit.clock.wallMs()
        ledger.release(scope, owned.filter { (h, o) -> h !in held && wall - o.t > 120_000 }.keys)
        val dream = items.filter { it.first in owned }
        val protect = ledger.inUse()
        val free = dream.filter { it.first !in protect && it.second >= 0 }.sortedBy { owned[it.first]?.t ?: 0L }
        val drop = free.filter { wall - (owned[it.first]?.t ?: 0L) > MAX_AGE }.toMutableList()
        val over = dream.size - drop.size - CAP
        if (over > 0) drop += free.filter { it !in drop }.take(over)
        var n = 0
        for ((h, tid) in drop) if (delete(tid)) { ledger.release(scope, listOf(h)); n++ }
        if (n > 0 || why != "hourly") PlaybackLog.w("TorBox janitor ($why): removed $n Dream OS torrent${if (n == 1) "" else "s"} (the viewer's ${user.size} untouched)")
    }

    /** A hand-built multipart/form-data body (createtorrent takes nothing else): body and its Content-Type. */
    private fun multipart(fields: List<Pair<String, String>>): Pair<ByteArray, String> {
        val b = "DreamOS" + java.lang.Long.toHexString(System.nanoTime())
        val sb = StringBuilder()
        for ((k, v) in fields) sb.append("--").append(b).append("\r\nContent-Disposition: form-data; name=\"").append(k).append("\"\r\n\r\n").append(v).append("\r\n")
        sb.append("--").append(b).append("--\r\n")
        return sb.toString().toByteArray(Charsets.UTF_8) to "multipart/form-data; boundary=$b"
    }
}

/** Real-Debrid's renewed access token for one app run (shared by every probe): fire-home RealDebridService companion. */
class RealDebridState {
    val lock = Mutex()
    @Volatile var refreshedFrom: String? = null
    @Volatile var refreshedTo: String? = null
    @Volatile var refreshCode: String? = null
    @Volatile var refreshFailedAt = 0L
    @Volatile var sweptFor = ""
    @Volatile var sweptAt = 0L
}

/**
 * Real-Debrid ("rd"). Its cache check was switched off in late 2024, so the only test is to add the magnet, select the
 * file wanted and see whether it is "downloaded" at once: cached torrents are, anything else is deleted straight away.
 * Probes go in parallel rounds of [BATCH] (at most [MAX] torrents per lookup, each in the account for a moment);
 * within the round with cached ones, up to [limit] are unrestricted side by side (the links live on after the probe
 * torrents are deleted, so alternates cost nothing). Every torrent id is recorded in the ledger the moment it is added;
 * the janitor deletes leftovers. The ledger scope is the device client id, which a token refresh doesn't change.
 * The access token lasts about an hour; on a 401 it is renewed with the viewer's own device credentials and saved.
 */
class RealDebridService(private val kit: DebridKit, private val st: RealDebridState) : DebridService {
    override val id = DebridServiceId.REAL_DEBRID
    private val accounts get() = kit.accounts
    private val stored = accounts.token(DebridServiceId.REAL_DEBRID).orEmpty()
    private val scope = DebridLedger.scope("rd", accounts.value("realdebrid.clientid").ifBlank { stored })

    companion object {
        const val REST = "https://api.real-debrid.com/rest/1.0/"
        const val OAUTH = "https://api.real-debrid.com/oauth/v2/"
        private const val BATCH = 3
        private const val MAX = 9
        private val WAITING = setOf("queued", "waiting_files_selection", "magnet_conversion")
        const val NO_CREDENTIALS = -1
    }

    private class Probe(val rank: Int, val c: Source, val tid: String, val path: String, val bytes: Long, val link: String)

    fun token(): String = st.refreshedTo?.takeIf { st.refreshedFrom == stored } ?: stored

    override suspend fun resolveMany(sources: List<Source>, deadline: Long, limit: Int): List<Stream> {
        val list = sources.take(MAX)
        if (list.isEmpty() || stored.isBlank()) return emptyList()
        maybeSweep()
        val t0 = kit.now()
        var start = 0
        while (start < list.size) {
            if (kit.now() >= deadline) break
            val round = list.subList(start, minOf(start + BATCH, list.size))
            val base = start
            start += BATCH
            val cached = coroutineScope { round.mapIndexed { i, c -> async { probe(c, base + i, deadline) } }.awaitAll() }.filterNotNull()
            var result: List<Stream> = emptyList()
            try {
                if (limit > 1 && cached.size > 1) {
                    result = coroutineScope { cached.take(limit).map { p -> async { unrestrict(p, deadline) } }.awaitAll() }.filterNotNull()
                } else for (p in cached) {
                    if (kit.now() >= deadline) break
                    unrestrict(p, deadline)?.let { result = listOf(it); PlaybackLog.i("Real-Debrid: link for #${p.rank} in ${kit.now() - t0} ms") }
                    if (result.isNotEmpty()) break
                }
            } finally {
                // The round's torrents are in the account either way.
                if (cached.isNotEmpty()) kit.background("Real-Debrid: cleanup") { cached.forEach { delete(it.tid) } }
            }
            if (result.isNotEmpty()) return result
        }
        PlaybackLog.i("Real-Debrid: none of ${list.size} cached (${kit.now() - t0} ms)")
        return emptyList()
    }

    /** Adds [c], selects its file and checks it is complete at once. A torrent not handed back is deleted. */
    private suspend fun probe(c: Source, rank: Int, deadline: Long): Probe? {
        val add = call("POST", "torrents/addMagnet", listOf("magnet" to DebridFiles.magnet(c)), deadline)
        val tid = if (add.ok) add.json()["id"].str.ifEmpty { null } else null
        if (tid == null) { if (!add.ok) PlaybackLog.w("Real-Debrid: add refused (HTTP ${add.code} ${add.json()["error"].str})"); return null }
        kit.ledger.claim(scope, listOf("id:$tid")); kit.ledger.setId(scope, "id:$tid", tid)
        var handed = false
        try {
            var info = info(tid, deadline) ?: return null
            if (info["status"].str == "magnet_conversion" && kit.pause(350, deadline)) info = info(tid, deadline) ?: return null
            if (info["status"].str != "waiting_files_selection") return null
            val files = info["files"].arr
            val idx = DebridFiles.pick(files.map { it["path"].str to it["bytes"].long }, c)
            if (idx < 0 || kit.now() >= deadline) return null
            val file = files[idx]
            if (!call("POST", "torrents/selectFiles/$tid", listOf("files" to file["id"].long.toString()), deadline).ok) return null
            info = info(tid, deadline) ?: return null
            if (info["status"].str in WAITING && kit.pause(400, deadline)) info = info(tid, deadline) ?: return null
            if (info["status"].str != "downloaded") return null
            val link = info["links"][0].str
            if (!link.startsWith("http")) return null
            handed = true
            return Probe(rank, c, tid, file["path"].str, file["bytes"].long, link)
        } finally {
            if (!handed) kit.background("Real-Debrid: cleanup") { delete(tid) }
        }
    }

    private suspend fun info(tid: String, deadline: Long): JsonElement? =
        call("GET", "torrents/info/$tid", null, deadline).takeIf { it.ok }?.json()

    /** The direct HTTPS link for a cached probe; RAR archives and the like don't play, so they don't count. */
    private suspend fun unrestrict(p: Probe, deadline: Long): Stream? {
        val r = call("POST", "unrestrict/link", listOf("link" to p.link), deadline)
        if (!r.ok) { PlaybackLog.w("Real-Debrid: unrestrict refused (HTTP ${r.code} ${r.json()["error"].str})"); return null }
        val o = r.json()
        val url = o["download"].str
        val name = o["filename"].str.ifEmpty { DebridFiles.base(p.path) }
        if (!url.startsWith("https") || !DebridFiles.isVideo(name)) return null
        return kit.found(url, id, p.c, name, o["filesize"].long.takeIf { it > 0 } ?: p.bytes)
    }

    private suspend fun delete(tid: String) {
        val r = kit.http.request("${REST}torrents/delete/$tid", "DELETE", token(), timeoutMs = 8_000)
        if (r.ok || r.code == 404) kit.ledger.release(scope, listOf("id:$tid"))
        if (!r.ok && r.code != 404) PlaybackLog.w("Real-Debrid: cleanup of a test torrent failed (HTTP ${r.code}); the janitor retries")
    }

    private fun maybeSweep() {
        val now = kit.now()
        if (st.sweptFor == scope && now - st.sweptAt < 3_600_000L) return
        st.sweptFor = scope; st.sweptAt = now
        kit.background("Real-Debrid janitor") { sweep() }
    }

    /** Test torrents Dream OS added and never got to delete (older than 10 min) are deleted now. */
    suspend fun sweep() {
        val left = kit.ledger.owned(scope).filter { kit.clock.wallMs() - it.value.t > 10 * 60_000L }
        var n = 0
        for ((k, o) in left) {
            val tid = o.i ?: k.removePrefix("id:")
            val r = kit.http.request("${REST}torrents/delete/$tid", "DELETE", token(), timeoutMs = 8_000)
            if (r.ok || r.code == 404) { kit.ledger.release(scope, listOf(k)); if (r.ok) n++ }
        }
        if (left.isNotEmpty()) PlaybackLog.w("Real-Debrid janitor: removed $n leftover test torrent${if (n == 1) "" else "s"}")
    }

    /** A REST call with the current token; on 401 (expired) renews once and retries. */
    suspend fun call(method: String, path: String, fields: List<Pair<String, String>>?, deadline: Long): HttpReply {
        suspend fun send(t: String) = if (fields == null) kit.http.request(REST + path, method, t, timeoutMs = kit.http.left(deadline))
            else kit.http.request(REST + path, method, t, DebridHttp.form(fields).toByteArray(), "application/x-www-form-urlencoded", kit.http.left(deadline))
        val used = token()
        val r = send(used)
        if (r.code != 401 || !refresh(used, deadline)) return r
        return send(token())
    }

    /** Swaps in a fresh access token unless another probe already did; false when that isn't possible. */
    private suspend fun refresh(failed: String, deadline: Long): Boolean = st.lock.withLock {
        if (token() != failed) return@withLock true
        if (st.refreshFailedAt != 0L && kit.now() - st.refreshFailedAt < 60_000) return@withLock false
        val code = st.refreshCode?.takeIf { st.refreshedFrom == stored } ?: accounts.value("realdebridrefresh")
        val r = renew(kit, code, kit.http.left(deadline))
        if (r.first == NO_CREDENTIALS) return@withLock false
        val access = r.second ?: run { st.refreshFailedAt = kit.now(); PlaybackLog.w("Real-Debrid: token refresh failed (HTTP ${r.first})"); return@withLock false }
        st.refreshCode = r.third ?: code
        st.refreshedTo = access
        st.refreshedFrom = stored
        st.refreshFailedAt = 0L
        PlaybackLog.i("Real-Debrid: token refreshed (saved in Dream OS)")
        true
    }
}

/**
 * Real-Debrid's access token renewal with the viewer's own device credentials, shared by Dream Core and the account
 * check. A new token is saved at once. Returns (HTTP code or [RealDebridService.NO_CREDENTIALS], access, refresh).
 */
suspend fun renew(kit: DebridKit, code: String = kit.accounts.value("realdebridrefresh"), timeoutMs: Long): Triple<Int, String?, String?> {
    val id = kit.accounts.value("realdebrid.clientid")
    val secret = kit.accounts.value("realdebridsecret")
    if (id.isBlank() || secret.isBlank() || code.isBlank()) return Triple(RealDebridService.NO_CREDENTIALS, null, null)
    val r = kit.http.postForm("${RealDebridService.OAUTH}token", listOf("client_id" to id, "client_secret" to secret,
        "code" to code, "grant_type" to "http://oauth.net/grant_type/device/1.0"), timeoutMs = timeoutMs)
    val o = if (r.ok) r.json() else null
    val access = o["access_token"].str
    if (access.isEmpty()) return Triple(r.code, null, null)
    val refresh = o["refresh_token"].str.ifEmpty { null }
    kit.accounts.refreshedRealDebrid(access, refresh)
    return Triple(r.code, access, refresh)
}
