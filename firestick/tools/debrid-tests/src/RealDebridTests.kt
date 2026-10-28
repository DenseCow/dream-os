package debridtest

import com.firehome.launcher.DebridAccounts
import com.firehome.launcher.DreamAccounts
import com.firehome.launcher.RealDebridService
import debridtest.Pack.cand
import debridtest.Pack.hash
import debridtest.T.check
import debridtest.T.eq
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/*
 * Real-Debrid fixtures, from https://api.real-debrid.com/ (fetched 2026-10-25 -> docs/rd_api.txt):
 *
 *   Auth: "Authorization: Bearer your_api_token" (or ?auth_token=). Errors: "HTTP code 4XX or 5XX, a JSON
 *   object with properties "error" (an error message) and "error_code" (optional, an integer)".
 *   401 "Bad token (expired, invalid)" on every REST method.
 *   POST /torrents/addMagnet  magnet* — "return a 201 HTTP code" — {"id":"string","uri":"string"}
 *   GET  /torrents/info/{id}  {"id","filename","original_filename","hash","bytes","original_bytes","host","split",
 *        "progress","status": magnet_error|magnet_conversion|waiting_files_selection|queued|downloading|downloaded|error|virus|
 *        compressing|uploading|dead, "added","files":[{"id":int,"path":"string" (starting with "/"),"bytes":int,"selected":0|1}],
 *        "links":["string" (Host URL)], "ended" (only when finished)}
 *   POST /torrents/selectFiles/{id}  files* "Selected files IDs (comma separated) or "all"" — "returns 204 HTTP code"
 *        (202 Action already done; 404 invalid file id)
 *   DELETE /torrents/delete/{id}  "returns 204 HTTP code"
 *   POST /unrestrict/link  link* — {"id","filename","mimeType","filesize","link","host","chunks","crc","download"
 *        (Generated link),"streamable"}
 *   POST /oauth/v2/token  (device flow, also used to refresh with the stored refresh_token as code)
 *        client_id, client_secret, code, grant_type=http://oauth.net/grant_type/device/1.0
 *        -> {"access_token","expires_in","token_type":"Bearer","refresh_token"}
 * Umbrella realdebrid.py: rest_base_url 'https://api.real-debrid.com/rest/1.0/'; on 'bad_token' calls
 *   refresh_token(): postData {'client_id','client_secret','code': realdebridrefresh,'grant_type':
 *   'http://oauth.net/grant_type/device/1.0'} to oauth_base_url + 'token', then retries.
 * (instantAvailability is gone since late 2024, so the code adds/selects/inspects; this mock models that.)
 */
object RealDebridTests {
    const val REST = "/rest/1.0"
    val H1 = hash('1'); val H2 = hash('2'); val H3 = hash('3'); val H4 = hash('4')
    val cands = listOf(cand(H1), cand(H2, "The.Show.S02.720p.HDTV"), cand(H3, "The.Show.S02.2160p"), cand(H4, "The.Show.S02.480p"))

    class Torrent(val id: String, val hash: String) { @Volatile var selected: List<Int>? = null }

    /** A stateful, docs-shaped Real-Debrid account: [cached] hashes download at once, [known] others queue. */
    class Account(val cached: Map<String, List<Pair<String, Long>>>, val known: Map<String, List<Pair<String, Long>>> = emptyMap(), @Volatile var valid: String) {
        val torrents = ConcurrentHashMap<String, Torrent>()
        val everAdded = ConcurrentHashMap.newKeySet<String>()
        val hashOf = ConcurrentHashMap<String, String>() // every id ever added -> its hash
        private val n = AtomicInteger(0)
        @Volatile var newAccess = "rd-access-NEW-0b5e"
        val oauth = java.util.concurrent.CopyOnWriteArrayList<Req>()
        var oauthReply: ((Req) -> Resp)? = null

        fun hosterLink(id: String) = "https://real-debrid.com/d/HOST$id"
        fun download(id: String) = "https://41.download.real-debrid.example/d/DL$id/${Pack.wantName}"
        private fun err(code: Int, e: String, ec: Int) = Resp.json("""{"error":"$e","error_code":$ec}""", code)
        private fun filesOf(h: String) = cached[h] ?: known[h]

        val route: (Req) -> Resp = r@{ r ->
            if (r.path == "/oauth/v2/token") {
                oauth.add(r)
                oauthReply?.let { return@r it(r) }
                if (r.f("client_id") != "CID" || r.f("client_secret") != "SECRET" || r.f("code") != "rd-refresh-1" ||
                    r.f("grant_type") != "http://oauth.net/grant_type/device/1.0") return@r Resp.json("""{"error":"invalid_grant","error_code":9}""", 400)
                valid = newAccess
                return@r Resp.json("""{"access_token":"$newAccess","expires_in":3600,"token_type":"Bearer","refresh_token":"rd-refresh-2"}""")
            }
            if (r.auth != "Bearer $valid") return@r err(401, "bad_token", 8)
            val p = r.path.removePrefix(REST)
            when {
                p == "/torrents/addMagnet" && r.method == "POST" -> {
                    val h = Pack.btih(r.f("magnet").orEmpty()) ?: return@r err(400, "parameter_missing", 1)
                    val id = "RD" + n.incrementAndGet() + h.take(2).uppercase()
                    torrents[id] = Torrent(id, h); everAdded.add(id); hashOf[id] = h
                    Resp.json("""{"id":"$id","uri":"https://api.real-debrid.com/rest/1.0/torrents/info/$id"}""", 201)
                }
                p.startsWith("/torrents/info/") && r.method == "GET" -> {
                    val t = torrents[p.substringAfterLast('/')] ?: return@r err(404, "unknown_ressource", 7)
                    val files = filesOf(t.hash)
                    val sel = t.selected
                    val status = when { files == null -> "magnet_conversion"; sel == null -> "waiting_files_selection"; cached.containsKey(t.hash) -> "downloaded"; else -> "downloading" }
                    val o = JSONObject().put("id", t.id).put("filename", Pack.DIR).put("hash", t.hash).put("bytes", 0).put("host", "real-debrid.com")
                        .put("split", 2000).put("progress", if (status == "downloaded") 100 else 0).put("status", status).put("added", "2026-10-25T18:00:00.000Z")
                        .put("files", JSONArray((files ?: emptyList()).mapIndexed { i, (path, b) ->
                            JSONObject().put("id", i + 1).put("path", "/$path").put("bytes", b).put("selected", if (sel?.contains(i + 1) == true) 1 else 0) }))
                        .put("links", JSONArray(if (status == "downloaded") listOf(hosterLink(t.id)) else emptyList<String>()))
                    if (status == "downloading") o.put("speed", 0).put("seeders", 0)
                    Resp.json(o.toString())
                }
                p.startsWith("/torrents/selectFiles/") && r.method == "POST" -> {
                    val t = torrents[p.substringAfterLast('/')] ?: return@r err(404, "unknown_ressource", 7)
                    val ids = r.f("files").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
                    if (ids.isEmpty() || ids.any { it < 1 || it > (filesOf(t.hash)?.size ?: 0) }) return@r err(404, "wrong_parameter", 2)
                    if (t.selected != null) return@r Resp(202, "")
                    t.selected = ids; Resp(204)
                }
                p.startsWith("/torrents/delete/") && r.method == "DELETE" ->
                    if (torrents.remove(p.substringAfterLast('/')) != null) Resp(204) else err(404, "unknown_ressource", 7)
                p == "/unrestrict/link" && r.method == "POST" -> {
                    val id = r.f("link").orEmpty().substringAfter("HOST", "")
                    if (id.isEmpty()) return@r err(400, "hoster_unsupported", 16)
                    Resp.json("""{"id":"U$id","filename":"${Pack.wantName}","mimeType":"video/x-matroska","filesize":2000000000,"link":"${hosterLink(id)}",
                        "host":"real-debrid.com","chunks":32,"crc":1,"download":"${download(id)}","streamable":1}""")
                }
                else -> err(404, "unknown_ressource", 7)
            }
        }

        fun leftovers() = torrents.keys.toList()
    }

    private fun accounts(token: String, refresh: Boolean = false) = DebridAccounts.Accounts(buildMap {
        put("realdebridtoken", token); put("realdebrid.enable", "true")
        if (refresh) { put("realdebridrefresh", "rd-refresh-1"); put("realdebrid.clientid", "CID"); put("realdebridsecret", "SECRET") }
    })

    private fun resolve(token: String, budgetMs: Long = 10_000, list: List<com.firehome.launcher.Candidate> = cands.take(3), refresh: Boolean = false) =
        RealDebridService(accounts(token, refresh)).resolve(list, T.now() + budgetMs)

    private fun calls(prefix: String) = Mock.log.filter { it.path.startsWith(REST + prefix) }
    private fun hashOfAdd(r: Req) = Pack.btih(r.f("magnet").orEmpty())

    /** Request shapes every RD scenario must keep. */
    private fun shapes(token: String) {
        for (r in Mock.log.filter { it.path.startsWith(REST) }) eq(r.auth, "Bearer $token", "auth header on $r")
        for (r in calls("/torrents/addMagnet")) {
            eq(r.method, "POST", "addMagnet method")
            check(r.contentType.startsWith("application/x-www-form-urlencoded"), "addMagnet is a form post")
            check(r.f("magnet")?.startsWith("magnet:?xt=urn:btih:") == true, "addMagnet magnet param (${r.f("magnet")})")
        }
        for (r in calls("/torrents/delete/")) eq(r.method, "DELETE", "delete method")
        check(Mock.log.none { it.path.contains("instantAvailability") }, "no instantAvailability (switched off by RD)")
        check(Mock.log.none { it.q("auth_token") != null }, "token goes in the header, not ?auth_token=")
    }

    fun run() {
        val both = mapOf(H1 to Pack.files, H2 to Pack.files)

        run {
            val a = Account(both, valid = "rd-access-A")
            T.scenario("rd", "cached first candidate: season pack -> S02E03, unrestricted", a.route, "rd-access-A", "download.real-debrid", "HOST") {
                val f = resolve("rd-access-A")
                check(f != null, "found a link")
                eq(f?.hash, H1, "the best-ranked cached candidate wins (H2 is cached too)")
                check(f?.url?.startsWith("https://41.download.real-debrid.example/d/DL") == true, "url is unrestrict's 'download' (${f?.url})")
                eq(f?.fileName, Pack.wantName, "file name"); eq(f?.sizeBytes, 2_000_000_000L, "size"); eq(f?.service, "rd", "service")
                eq(calls("/torrents/addMagnet").map { hashOfAdd(it) }.toSet(), setOf(H1, H2, H3), "one round adds all three (BATCH 3)")
                val sel = calls("/torrents/selectFiles/")
                check(sel.isNotEmpty(), "files selected")
                for (s in sel) eq(s.f("files"), (Pack.WANT + 1).toString(), "selectFiles picks S02E03 (file id ${Pack.WANT + 1}) on $s")
                val un = calls("/unrestrict/link")
                eq(un.size, 1, "one unrestrict"); check(un.firstOrNull()?.f("link")?.startsWith("https://real-debrid.com/d/HOST") == true, "unrestrict the hoster link from info.links[0]")
                T.settle(); shapes("rd-access-A")
                eq(a.leftovers(), emptyList<String>(), "every test torrent deleted, the played one too (by design; the link lives on)")
            }
        }

        run {
            val a = Account(mapOf(H2 to Pack.files), known = mapOf(H1 to Pack.files), valid = "rd-access-A")
            T.scenario("rd", "first not cached (downloading), second cached -> second; first deleted", a.route, "rd-access-A") {
                val f = resolve("rd-access-A")
                eq(f?.hash, H2, "second candidate"); eq(f?.fileName, Pack.wantName, "file")
                T.settle(); shapes("rd-access-A")
                eq(calls("/torrents/delete/").size, a.everAdded.size, "one DELETE per torrent added (${a.everAdded.size})")
                eq(a.leftovers(), emptyList<String>(), "no leftovers")
            }
        }

        run {
            val a = Account(emptyMap(), known = mapOf(H1 to Pack.files, H2 to Pack.files, H4 to Pack.files), valid = "rd-access-A")
            T.scenario("rd", "nothing cached (downloading / never seen) over 2 rounds -> null, all deleted", a.route, "rd-access-A") {
                val f = resolve("rd-access-A", list = cands)
                eq(f, null, "result")
                eq(calls("/torrents/addMagnet").size, 4, "4 candidates tried (rounds of 3 + 1)")
                eq(calls("/unrestrict/link").size, 0, "nothing unrestricted")
                T.settle(); shapes("rd-access-A")
                eq(a.leftovers(), emptyList<String>(), "no leftovers")
            }
        }

        run {
            val a = Account(mapOf(H1 to Pack.withoutE03, H2 to Pack.files), valid = "rd-access-A")
            T.scenario("rd", "first cached but its pack lacks S02E03 -> not selected, deleted, second", a.route, "rd-access-A") {
                eq(resolve("rd-access-A")?.hash, H2, "second candidate")
                check(calls("/torrents/selectFiles/").none { a.hashOf[it.path.substringAfterLast('/')] == H1 }, "no file selected in the pack without the episode")
                T.settle(); eq(a.leftovers(), emptyList<String>(), "no leftovers")
            }
        }

        run {
            val a = Account(both, valid = "rd-access-valid")
            T.scenario("rd", "bad token, no refresh credentials -> null, nothing added", a.route, "rd-access-stale") {
                eq(resolve("rd-access-stale"), null, "result")
                eq(a.oauth.size, 0, "no refresh attempted without credentials")
                T.settle(); eq(a.everAdded.size, 0, "nothing added"); eq(a.leftovers(), emptyList<String>(), "no leftovers")
            }
        }

        run {
            DreamAccounts.refreshed.clear()
            val a = Account(both, valid = "rd-access-current")
            T.scenario("rd", "401 bad_token -> refresh-token grant -> retried with the new token -> link", a.route, "rd-access-EXPIRED", "rd-access-NEW", "rd-refresh", "SECRET") {
                val f = resolve("rd-access-EXPIRED", refresh = true)
                eq(f?.hash, H1, "found after the refresh")
                eq(a.oauth.size, 1, "exactly one refresh for three parallel probes")
                val o = a.oauth.firstOrNull()
                eq(o?.method, "POST", "token method"); eq(o?.f("grant_type"), "http://oauth.net/grant_type/device/1.0", "grant_type")
                eq(o?.f("client_id"), "CID", "client_id"); eq(o?.f("client_secret"), "SECRET", "client_secret"); eq(o?.f("code"), "rd-refresh-1", "code = stored refresh token")
                check(o?.auth == null, "no bearer on the token call")
                val first401 = Mock.log.filter { it.path.startsWith(REST) && it.auth == "Bearer rd-access-EXPIRED" }
                check(first401.isNotEmpty() && first401.all { it.path.endsWith("addMagnet") }, "only the first tries used the expired token ($first401)")
                val after = Mock.log.filter { it.path.startsWith(REST) && it.auth != "Bearer rd-access-EXPIRED" }
                check(after.isNotEmpty() && after.all { it.auth == "Bearer ${a.newAccess}" }, "every later call uses the new token")
                eq(DreamAccounts.refreshed.toList(), listOf(a.newAccess to "rd-refresh-2"), "new token + rotated refresh token saved in Dream OS")
                T.settle(); eq(a.leftovers(), emptyList<String>(), "no leftovers")
            }
            T.scenario("rd", "next resolve with the same stored token reuses the refreshed one (no 2nd refresh)", a.route, "rd-access-NEW") {
                eq(resolve("rd-access-EXPIRED", refresh = true)?.hash, H1, "found")
                eq(a.oauth.size, 1, "still one refresh in total")
                check(Mock.log.filter { it.path.startsWith(REST) }.all { it.auth == "Bearer ${a.newAccess}" }, "no request with the expired token")
            }
        }

        run {
            val a = Account(both, valid = "rd-access-other").apply { oauthReply = { Resp.json("""{"error":"invalid_grant","error_code":9}""", 400) } }
            T.scenario("rd", "401 and the refresh is refused -> null, no leftovers", a.route, "rd-access-OLD2", "SECRET") {
                eq(resolve("rd-access-OLD2", refresh = true), null, "result"); eq(a.oauth.size, 1, "one refresh attempt")
                T.settle(); eq(a.leftovers(), emptyList<String>(), "no leftovers")
            }
        }

        // ---- malformed / partial / HTTP errors / timeouts
        run {
            val a = Account(both, valid = "rd-access-A")
            T.scenario("rd", "malformed addMagnet JSON -> null", { r -> if (r.path.endsWith("addMagnet")) Resp.json("""{"id":"RD""", 201) else a.route(r) }, "rd-access-A") {
                eq(resolve("rd-access-A"), null, "result")
            }
        }
        run {
            val a = Account(both, valid = "rd-access-A")
            T.scenario("rd", "malformed torrents/info JSON -> null, added torrents deleted", { r -> if (r.path.contains("/torrents/info/")) Resp.json("""{"status":"downl""") else a.route(r) }, "rd-access-A") {
                eq(resolve("rd-access-A"), null, "result"); T.settle(); eq(a.leftovers(), emptyList<String>(), "no leftovers")
            }
        }
        run {
            val a = Account(both, valid = "rd-access-A")
            T.scenario("rd", "info without files (partial) -> null, deleted", { r -> if (r.path.contains("/torrents/info/")) Resp.json("""{"id":"x","status":"waiting_files_selection"}""") else a.route(r) }, "rd-access-A") {
                eq(resolve("rd-access-A"), null, "result"); T.settle(); eq(a.leftovers(), emptyList<String>(), "no leftovers")
            }
        }
        run {
            val a = Account(both, valid = "rd-access-A")
            T.scenario("rd", "malformed unrestrict JSON -> null, cached probes still deleted", { r -> if (r.path.endsWith("unrestrict/link")) Resp.json("""{"download":"https://x""") else a.route(r) }, "rd-access-A") {
                eq(resolve("rd-access-A"), null, "result"); T.settle(); eq(a.leftovers(), emptyList<String>(), "no leftovers")
            }
        }
        run {
            val a = Account(both, valid = "rd-access-A")
            T.scenario("rd", "unrestrict 503 for the best -> next cached of the round", { r ->
                if (r.path.endsWith("unrestrict/link") && a.torrents.values.any { "HOST" + it.id == r.f("link")?.substringAfterLast('/') && it.hash == H1 })
                    Resp.json("""{"error":"service_unavailable","error_code":25}""", 503) else a.route(r)
            }, "rd-access-A") {
                eq(resolve("rd-access-A")?.hash, H2, "second cached"); T.settle(); eq(a.leftovers(), emptyList<String>(), "no leftovers")
            }
        }
        run {
            val a = Account(both, valid = "rd-access-A")
            T.scenario("rd", "HTTP 500 on addMagnet -> null", { r -> if (r.path.endsWith("addMagnet")) Resp.json("""{"error":"internal","error_code":-1}""", 500) else a.route(r) }, "rd-access-A") {
                eq(resolve("rd-access-A"), null, "result"); eq(a.everAdded.size, 0, "nothing added")
            }
        }
        T.scenario("rd", "connection dropped -> null", { _ -> Resp(-1) }, "rd-access-A") { eq(resolve("rd-access-A"), null, "result") }

        run {
            val a = Account(both, valid = "rd-access-A")
            T.scenario("rd", "deadline: addMagnet hangs -> null by the 2.5 s deadline", { r -> if (r.path.endsWith("addMagnet")) Resp(201, "{}", 20_000) else a.route(r) }, "rd-access-A") {
                val (f, ms) = T.timed { resolve("rd-access-A", budgetMs = 2_500) }
                eq(f, null, "result"); check(ms <= 2_500 + 300, "returned after $ms ms (deadline 2500)")
            }
        }
        run {
            val a = Account(both, valid = "rd-access-A")
            T.scenario("rd", "deadline: torrents/info hangs -> null by the 3 s deadline, adds deleted", { r -> if (r.path.contains("/torrents/info/")) Resp(200, "{}", 20_000) else a.route(r) }, "rd-access-A") {
                val (f, ms) = T.timed { resolve("rd-access-A", budgetMs = 3_000) }
                eq(f, null, "result"); check(ms <= 3_000 + 300, "returned after $ms ms (deadline 3000)")
                // resolve() returns at the deadline; the probes' own info requests time out a moment later
                // (their timeout is DebridHttp.left(), at least 1 s) and only then queue their DELETEs.
                Thread.sleep(1_200)
                T.settle(); eq(a.leftovers(), emptyList<String>(), "no leftovers")
            }
        }
        run {
            val a = Account(both, valid = "rd-access-A")
            // budget 600 ms: each request still gets DebridHttp.left()'s 1 s floor, so the 800 ms reply lands after the deadline
            T.scenario("rd", "deadline: addMagnet answers after the deadline (within its timeout) -> null on time, late torrents deleted",
                { r -> if (r.path.endsWith("addMagnet")) a.route(r).let { Resp(it.code, it.body, 800) } else a.route(r) }, "rd-access-A") {
                val (f, ms) = T.timed { resolve("rd-access-A", budgetMs = 600) }
                eq(f, null, "result"); check(ms <= 600 + 300, "returned after $ms ms (deadline 600)")
                T.settle(); eq(a.everAdded.size, 3, "three late adds"); eq(a.leftovers(), emptyList<String>(), "late adds deleted (left: ${a.leftovers()})")
                eq(calls("/torrents/selectFiles/").size, 0, "nothing selected after the deadline")
            }
        }
        run {
            val a = Account(both, valid = "rd-access-A")
            T.scenario("rd", "KNOWN LIMIT: addMagnet reply lost to its timeout -> id never seen; torrent stays unselected (no download)",
                { r -> if (r.path.endsWith("addMagnet")) a.route(r).let { Resp(it.code, it.body, 1_700) } else a.route(r) }, "rd-access-A") {
                val (f, ms) = T.timed { resolve("rd-access-A", budgetMs = 1_500) }
                eq(f, null, "result"); check(ms <= 1_500 + 300, "returned after $ms ms (deadline 1500)")
                T.settle()
                eq(calls("/torrents/selectFiles/").size, 0, "nothing selected, so RD never starts a download")
                check(a.torrents.values.all { it.selected == null }, "what is left sits in waiting_files_selection")
                println("        note: ${a.leftovers().size} torrent(s) left in waiting_files_selection: their ids never reached the client")
            }
        }
    }
}
