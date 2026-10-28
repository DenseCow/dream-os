package debridtest

import com.firehome.launcher.TorBoxService
import debridtest.Pack.cand
import debridtest.Pack.hash
import debridtest.T.check
import debridtest.T.eq
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/*
 * TorBox fixtures, from the official OpenAPI (https://api.torbox.app/openapi.json -> docs/tb_openapi.json)
 * and the response examples of the official Postman docs (https://api-docs.torbox.app -> docs/tb_examples.txt),
 * fetched 2026-10-25:
 *
 *   POST /v1/api/torrents/checkcached   query format ("object" default | "list"), list_files (bool);
 *     JSON body CheckCached {"hashes": [string]}; security OAuth2PasswordBearer (Authorization: Bearer).
 *     "Success FILES LIST": {"success":true,"error":null,"detail":"Torrent cache status retrieved successfully.",
 *       "data":[{"name":"string","size":0,"hash":"string","files":[{"id":0,"name":"string","size":0,
 *       "opensubtitles_hash":"string","short_name":"string","mimetype":"string"}]}]}   (uncached hashes absent)
 *     "Fail BAD_TOKEN" (403): {"success":false,"error":"BAD_TOKEN","detail":"Your token is invalid or has expired. ...","data":null}
 *   POST /v1/api/torrents/createtorrent   multipart/form-data: file, magnet, seed (int), allow_zip (bool),
 *     name, as_queued, add_only_if_cached (bool).
 *     Success: {"success":true,"error":null,"detail":"Torrent Added Successfully","data":{"hash":"string","torrent_id":0,"auth_id":"string"}}
 *     "Fail DOWNLOAD_NOT_CACHED" (400): {"success":false,"error":"DOWNLOAD_NOT_CACHED","detail":"Torrent not found in cache. ...","data":null}
 *   GET /v1/api/torrents/mylist   query id, bypass_cache. data: torrent(s) with files [{"id":0,"md5":..,"hash":..,
 *     "name":"string","size":0,"zipped":false,"s3_path":..,"mimetype":..,"short_name":"string","absolute_path":..}]
 *     (with ?id= Umbrella reads data as one object: torrent_files['data']['files']).
 *   GET /v1/api/torrents/requestdl   query token (required), torrent_id (required), file_id, zip_link,
 *     redirect (default false). Success: {"success":true,"error":null,"detail":"...","data":"https://example.com/download.zip"}
 *   POST /v1/api/torrents/controltorrent   JSON ControlTorrent {"torrent_id": int, "operation": string, "all": bool}.
 *     Success: {"success":true,"error":null,"detail":"Torrent operationd successfully.","data":null}
 * Umbrella torbox.py: check_cache -> POST checkcached params {'format':'list'} json {'hashes': hashlist};
 *   add_magnet data {'magnet','seed':3,'allow_zip':False}; delete_torrent json {'torrent_id','operation':'delete'};
 *   unrestrict_link params {'token','torrent_id','file_id'} -> ['data'].
 */
object TorBoxTests {
    const val TOKEN = "tb-api-key-51c0de"
    const val API = "/v1/api"
    val H1 = hash('d'); val H2 = hash('e'); val H3 = hash('f')
    val cands = listOf(cand(H1), cand(H2, "The.Show.S02.720p.HDTV"), cand(H3, "The.Show.S02.2160p"))

    /** A stateful, docs-shaped TorBox account. */
    class Account(val cached: Map<String, List<Pair<String, Long>>>) {
        val torrents = ConcurrentHashMap<Long, String>() // id -> hash
        val ids = AtomicLong(111)
        var emptyFirstList = false // a just-added torrent lists no files on the first mylist
        private val listed = ConcurrentHashMap.newKeySet<Long>()
        fun dl(id: Long, fid: Long) = "https://store-021.tb-cdn.example/dl/$id/$fid/${(cached[torrents[id]] ?: Pack.files)[fid.toInt()].first.substringAfterLast('/')}"
        private fun files(h: String) = JSONArray(cached.getValue(h).mapIndexed { i, (p, s) ->
            JSONObject().put("id", i).put("name", p).put("size", s).put("short_name", p.substringAfterLast('/'))
                .put("mimetype", if (p.endsWith(".mkv")) "video/x-matroska" else "text/plain").put("opensubtitles_hash", "0000")
        })
        private fun fail(code: Int, err: String, detail: String) = Resp.json(JSONObject().put("success", false).put("error", err).put("detail", detail).put("data", JSONObject.NULL).toString(), code)
        private fun ok(data: Any?, detail: String) = Resp.json(JSONObject().put("success", true).put("error", JSONObject.NULL).put("detail", detail).put("data", data ?: JSONObject.NULL).toString())

        val route: (Req) -> Resp = r@{ r ->
            if (r.path == "$API/torrents/requestdl") {
                // requestdl takes the key as ?token= (no OAuth security in the OpenAPI)
                if (r.q("token") != TOKEN) return@r fail(403, "BAD_TOKEN", "Your token is invalid or has expired. Please log in again or reset your token.")
                val id = r.q("torrent_id")?.toLongOrNull(); val fid = r.q("file_id")?.toLongOrNull() ?: 0
                if (id == null || !torrents.containsKey(id)) return@r fail(404, "ITEM_NOT_FOUND", "Item not found.")
                return@r ok(dl(id, fid), "Torrent download requested successfully.")
            }
            if (r.auth != "Bearer $TOKEN") return@r fail(403, "BAD_TOKEN", "Your token is invalid or has expired. Please log in again or reset your token.")
            when (r.path) {
                "$API/torrents/checkcached" -> {
                    val hashes = JSONObject(r.body).getJSONArray("hashes").let { a -> (0 until a.length()).map { a.getString(it).lowercase() } }
                    val list = r.q("format") == "list"; val withFiles = r.q("list_files") == "true"
                    fun entry(h: String) = JSONObject().put("name", Pack.DIR).put("size", cached.getValue(h).sumOf { it.second }).put("hash", h)
                        .apply { if (withFiles) put("files", files(h)) }
                    val hits = hashes.filter { cached.containsKey(it) }
                    ok(if (list) JSONArray(hits.map { entry(it) }) else JSONObject().apply { hits.forEach { put(it, entry(it)) } },
                        "Torrent cache status retrieved successfully.")
                }
                "$API/torrents/createtorrent" -> {
                    val m = r.multipart
                    val h = Pack.btih(m["magnet"].orEmpty()) ?: return@r fail(400, "MISSING_REQUIRED_OPTION", "Missing magnet.")
                    if (!cached.containsKey(h) && m["add_only_if_cached"] == "true")
                        return@r fail(400, "DOWNLOAD_NOT_CACHED", "Torrent not found in cache. Disable the add only if cached parameter to add the download.")
                    val id = ids.getAndAdd(111); torrents[id] = h
                    ok(JSONObject().put("hash", h).put("torrent_id", id).put("auth_id", "auth-1"), "Found Cached Torrent. Using Cached Torrent.")
                }
                "$API/torrents/mylist" -> {
                    val id = r.q("id")?.toLongOrNull()
                    val h = id?.let { torrents[it] } ?: return@r fail(404, "ITEM_NOT_FOUND", "Item not found.")
                    val first = listed.add(id)
                    ok(JSONObject().put("id", id).put("hash", h).put("name", Pack.DIR).put("download_state", "cached").put("download_finished", true)
                        .put("cached", true).put("files", if (emptyFirstList && first) JSONArray() else files(h)), "Torrent list retrieved successfully.")
                }
                "$API/torrents/controltorrent" -> {
                    val o = JSONObject(r.body)
                    if (o.optString("operation") != "delete") return@r fail(400, "INVALID_OPTION", "bad operation")
                    if (torrents.remove(o.optLong("torrent_id", -1)) == null) return@r fail(409, "NOT_OWNER", "not yours")
                    ok(null, "Torrent operationd successfully.")
                }
                else -> fail(404, "ENDPOINT_NOT_FOUND", "Endpoint not found. Please check the URL and try again.")
            }
        }
    }

    private fun resolve(budgetMs: Long = 8_000, token: String = TOKEN, list: List<com.firehome.launcher.Candidate> = cands) =
        TorBoxService(token).resolve(list, T.now() + budgetMs)

    private fun checkCall(r: Req?, hashes: List<String>) {
        check(r != null, "a cache check was made"); r ?: return
        eq(r.method, "POST", "checkcached method"); eq(r.path, "$API/torrents/checkcached", "checkcached path")
        eq(r.q("format"), "list", "checkcached format"); eq(r.q("list_files"), "true", "checkcached list_files")
        eq(r.auth, "Bearer $TOKEN", "checkcached auth")
        check(r.contentType.startsWith("application/json"), "checkcached body is JSON (${r.contentType})")
        eq(try { JSONObject(r.body).getJSONArray("hashes").toList() } catch (e: Exception) { null }, hashes, "checkcached body hashes")
    }

    private fun createCall(r: Req?, h: String) {
        check(r != null, "a createtorrent for ${h.take(4)}"); r ?: return
        eq(r.method, "POST", "createtorrent method"); eq(r.path, "$API/torrents/createtorrent", "createtorrent path")
        eq(r.auth, "Bearer $TOKEN", "createtorrent auth")
        check(r.contentType.startsWith("multipart/form-data; boundary="), "createtorrent is multipart (${r.contentType})")
        val m = r.multipart
        eq(Pack.btih(m["magnet"].orEmpty()), h, "createtorrent magnet hash")
        eq(m["add_only_if_cached"], "true", "add_only_if_cached (never a real download)")
        eq(m["seed"], "3", "seed (3 = don't seed, as Umbrella)"); eq(m["allow_zip"], "false", "allow_zip")
    }

    private fun calls(path: String) = Mock.log.filter { it.path == "$API/torrents/$path" }

    fun run() {
        val both = mapOf(H1 to Pack.files, H2 to Pack.files)

        run {
            val a = Account(both)
            T.scenario("tb", "cached first candidate: season pack -> S02E03 link", a.route, TOKEN, "tb-cdn") {
                val f = resolve()
                check(f != null, "found a link")
                eq(f?.url, a.dl(111, Pack.WANT.toLong()), "url is requestdl's data for file id ${Pack.WANT}")
                eq(f?.fileName, Pack.wantName, "file name"); eq(f?.sizeBytes, 2_000_000_000L, "size"); eq(f?.hash, H1, "hash"); eq(f?.service, "tb", "service")
                checkCall(Mock.log.getOrNull(0), listOf(H1, H2, H3))
                createCall(calls("createtorrent").firstOrNull(), H1)
                eq(calls("createtorrent").size, 1, "one torrent added")
                val ml = calls("mylist").firstOrNull()
                eq(ml?.q("id"), "111", "mylist id"); eq(ml?.q("bypass_cache"), "true", "mylist bypass_cache"); eq(ml?.auth, "Bearer $TOKEN", "mylist auth")
                val rq = calls("requestdl").firstOrNull()
                eq(rq?.method, "GET", "requestdl method"); eq(rq?.q("token"), TOKEN, "requestdl token param")
                eq(rq?.q("torrent_id"), "111", "requestdl torrent_id"); eq(rq?.q("file_id"), Pack.WANT.toString(), "requestdl file_id (S02E03)")
                T.settle()
                eq(calls("controltorrent").size, 0, "the played torrent is kept (by design), nothing else to delete")
                eq(a.torrents.keys.toList(), listOf(111L), "account holds only the played torrent")
            }
        }

        run {
            val a = Account(mapOf(H2 to Pack.files))
            T.scenario("tb", "first not cached, second cached -> second; uncached never added", a.route, TOKEN) {
                val f = resolve()
                eq(f?.hash, H2, "second candidate"); eq(f?.fileName, Pack.wantName, "file name")
                eq(calls("createtorrent").size, 1, "only the cached one added"); createCall(calls("createtorrent").firstOrNull(), H2)
                T.settle(); eq(a.torrents.values.toList(), listOf(H2), "account holds only the played torrent")
            }
        }

        run {
            val a = Account(mapOf(H1 to Pack.withoutE03, H2 to Pack.files))
            T.scenario("tb", "first cached but its pack lacks S02E03 -> skipped before adding", a.route, TOKEN) {
                eq(resolve()?.hash, H2, "second candidate")
                eq(calls("createtorrent").map { Pack.btih(it.multipart["magnet"].orEmpty()) }, listOf(H2), "only H2 added")
            }
        }

        run {
            val a = Account(both)
            val route: (Req) -> Resp = { r -> if (r.path == "$API/torrents/requestdl" && r.q("torrent_id") == "111") Resp.json("""{"success":false,"error":"DATABASE_ERROR","detail":"x","data":null}""", 500) else a.route(r) }
            T.scenario("tb", "first added but requestdl fails -> deleted, second played", route, TOKEN) {
                val f = resolve()
                eq(f?.hash, H2, "second candidate"); eq(f?.url, a.dl(222, Pack.WANT.toLong()), "second's link")
                T.settle()
                val del = calls("controltorrent")
                eq(del.size, 1, "one cleanup call")
                eq(del.firstOrNull()?.let { JSONObject(it.body).optLong("torrent_id") }, 111L, "deleted torrent_id 111")
                eq(del.firstOrNull()?.let { JSONObject(it.body).optString("operation") }, "delete", "operation")
                eq(del.firstOrNull()?.auth, "Bearer $TOKEN", "cleanup auth")
                check(del.firstOrNull()?.contentType?.startsWith("application/json") == true, "cleanup body is JSON")
                eq(a.torrents.keys.toList(), listOf(222L), "only the played torrent is left")
            }
        }

        run {
            val a = Account(both)
            val route: (Req) -> Resp = { r -> if (r.path == "$API/torrents/createtorrent" && r.multipart["magnet"]!!.contains(H1))
                Resp.json("""{"success":false,"error":"DOWNLOAD_NOT_CACHED","detail":"Torrent not found in cache. Disable the add only if cached parameter to add the download.","data":null}""", 400) else a.route(r) }
            T.scenario("tb", "createtorrent refuses the first (DOWNLOAD_NOT_CACHED) -> second", route, TOKEN) {
                eq(resolve()?.hash, H2, "second candidate"); T.settle(); eq(calls("controltorrent").size, 0, "nothing to delete")
            }
        }

        run {
            val a = Account(both).apply { emptyFirstList = true }
            T.scenario("tb", "just-added torrent lists no files at first -> lists again, plays", a.route, TOKEN) {
                val f = resolve()
                eq(f?.hash, H1, "first candidate"); eq(f?.fileName, Pack.wantName, "file"); eq(calls("mylist").size, 2, "mylist asked twice")
            }
        }

        run {
            val a = Account(emptyMap())
            T.scenario("tb", "nothing cached -> null, nothing added", a.route, TOKEN) {
                eq(resolve(), null, "result"); eq(Mock.log.size, 1, "requests"); checkCall(Mock.log.getOrNull(0), listOf(H1, H2, H3))
                T.settle(); check(a.torrents.isEmpty(), "account untouched")
            }
        }
        T.scenario("tb", "nothing cached, data null -> null", { _ -> Resp.json("""{"success":true,"error":null,"detail":"x","data":null}""") }, TOKEN) {
            eq(resolve(), null, "result"); eq(Mock.log.size, 1, "requests")
        }
        T.scenario("tb", "nothing cached, object format {} -> null", { _ -> Resp.json("""{"success":true,"error":null,"detail":"x","data":{}}""") }, TOKEN) {
            eq(resolve(), null, "result")
        }

        run {
            val a = Account(both)
            T.scenario("tb", "bad / expired key (403 BAD_TOKEN) -> null", a.route, TOKEN, "tb-old") {
                eq(resolve(token = "tb-old-key"), null, "result"); eq(Mock.log.size, 1, "requests"); eq(Mock.log.getOrNull(0)?.auth, "Bearer tb-old-key", "auth")
            }
        }

        val ok = Account(both)
        T.scenario("tb", "malformed checkcached JSON -> null", { r -> if (r.path.endsWith("checkcached")) Resp.json("""{"success":true,"data":[{"hash":""") else ok.route(r) }, TOKEN) {
            eq(resolve(), null, "result"); eq(Mock.log.size, 1, "requests")
        }
        run {
            val a = Account(both)
            T.scenario("tb", "malformed mylist JSON after the add -> null, added torrent deleted", { r -> if (r.path.endsWith("mylist")) Resp.json("""{"success":tr""") else a.route(r) }, TOKEN) {
                eq(resolve(), null, "result"); T.settle(); check(a.torrents.isEmpty(), "no leftovers (left: ${a.torrents})")
            }
        }
        run {
            val a = Account(both)
            T.scenario("tb", "requestdl answers without a URL (partial) -> deleted, next", { r -> if (r.path.endsWith("requestdl") && r.q("torrent_id") == "111") Resp.json("""{"success":true,"data":null}""") else a.route(r) }, TOKEN) {
                eq(resolve()?.hash, H2, "second candidate"); T.settle(); eq(a.torrents.keys.toList(), listOf(222L), "only the played one left")
            }
        }
        T.scenario("tb", "HTTP 500 everywhere -> null", { _ -> Resp.json("""{"success":false,"error":"UNKNOWN_ERROR","detail":"x","data":null}""", 500) }, TOKEN) {
            eq(resolve(), null, "result")
        }
        T.scenario("tb", "connection dropped -> null", { _ -> Resp(-1) }, TOKEN) { eq(resolve(), null, "result") }

        T.scenario("tb", "deadline: checkcached hangs -> null by the 2.5 s deadline", { _ -> Resp(200, "{}", 20_000) }, TOKEN) {
            val (f, ms) = T.timed { resolve(2_500) }
            eq(f, null, "result"); check(ms <= 2_500 + 300, "returned after $ms ms (deadline 2500)")
        }
        run {
            val a = Account(both)
            T.scenario("tb", "deadline: mylist hangs after the add -> null by the deadline, added one deleted", { r -> if (r.path.endsWith("mylist")) Resp(200, "{}", 20_000) else a.route(r) }, TOKEN) {
                val (f, ms) = T.timed { resolve(3_000) }
                eq(f, null, "result"); check(ms <= 3_000 + 300, "returned after $ms ms (deadline 3000)")
                T.settle(); check(a.torrents.isEmpty(), "no leftovers (left: ${a.torrents})")
            }
        }
    }

    private fun JSONArray.toList() = (0 until length()).map { getString(it) }
}
