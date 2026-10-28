package debridtest

import com.firehome.launcher.PremiumizeService
import debridtest.Pack.cand
import debridtest.Pack.hash
import debridtest.T.check
import debridtest.T.eq
import org.json.JSONArray
import org.json.JSONObject

/*
 * Premiumize fixtures, from https://www.premiumize.me/api (fetched 2026-10-25, docs/pm_api.txt):
 *
 *   Authentication: "pass it as a Bearer token in the Authorization header ... The same header is used
 *   for OAuth 2.0 access tokens". (Umbrella premiumize.py: headers {'Authorization': 'Bearer %s' % token}.)
 *
 *   Response format: success {"status":"success", ...}; error {"status":"error","message":"...","code":"..."}.
 *   "The HTTP status is 200 for normal responses including business-logic errors. 500 is used for
 *   catastrophic server failures (still returned as a JSON envelope on /api/... paths)."
 *   authentication_failed (HTTP 200): "API key missing, invalid, or expired."
 *
 *   POST /api/cache/check   items[] — Links to check.
 *     {"status":"success","response":[true,false,...],"filename":["My.Transfer.Name",null,...],"filesize":["12345678901",0,...]}
 *     (+ deprecated "transcoded"). Umbrella check_cache_list: session.post(cache_check_url, data={'items[]': hashList}).
 *
 *   POST /api/transfer/directdl   src — Transfer source URL.
 *     {"status":"success","content":[{"path":"Folder/video1.mkv","size":123456789,"link":"https://..."}, ...]}
 *     deprecated, still returned: content[].stream_link ("for videos this mirrors link; for non-videos it's null"),
 *     content[].transcode_status, top-level location/filename/filesize. Nothing is stored in the cloud.
 */
object PremiumizeTests {
    const val TOKEN = "pm-oauth-access-7f3e9a"
    val H1 = hash('a'); val H2 = hash('b'); val H3 = hash('c')
    val cands = listOf(cand(H1), cand(H2, "The.Show.S02.720p.HDTV"), cand(H3, "The.Show.S02.2160p"))

    fun link(h: String, path: String) = "https://pm-cdn.example/dl/${h.take(6)}/${path.substringAfterLast('/')}"

    /** A docs-shaped Premiumize: [cached] hashes and what each holds. Auth by Bearer header. */
    fun service(cached: Map<String, List<Pair<String, Long>>>): (Req) -> Resp = r@{ r ->
        if (r.auth != "Bearer $TOKEN") return@r Resp.json("""{"status":"error","code":"authentication_failed","message":"API key missing, invalid, or expired."}""")
        when (r.path) {
            "/api/cache/check" -> {
                val items = r.form["items[]"] ?: r.query["items[]"] ?: emptyList()
                val o = JSONObject().put("status", "success")
                    .put("response", JSONArray(items.map { cached.containsKey(it.lowercase()) }))
                    .put("transcoded", JSONArray(items.map { if (cached.containsKey(it.lowercase())) true else JSONObject.NULL }))
                    .put("filename", JSONArray(items.map { if (cached.containsKey(it.lowercase())) Pack.DIR else JSONObject.NULL }))
                    .put("filesize", JSONArray(items.map { if (cached.containsKey(it.lowercase())) cached.getValue(it.lowercase()).sumOf { f -> f.second }.toString() else 0 }))
                Resp.json(o.toString())
            }
            "/api/transfer/directdl" -> {
                val h = Pack.btih(r.f("src").orEmpty())
                val files = cached[h] ?: return@r Resp.json("""{"status":"error","code":"not_found","message":"Item not cached."}""")
                val content = JSONArray(files.map { (p, s) ->
                    val video = p.endsWith(".mkv")
                    JSONObject().put("path", p).put("size", s).put("link", link(h!!, p))
                        .put("stream_link", if (video) link(h, p) else JSONObject.NULL)
                        .put("transcode_status", if (video) "good_as_is" else "not_applicable")
                })
                Resp.json(JSONObject().put("status", "success").put("location", link(h!!, files[0].first))
                    .put("filename", files[0].first).put("filesize", files[0].second).put("content", content).toString())
            }
            else -> Resp.json("""{"status":"error","code":"invalid_request","message":"unknown method"}""")
        }
    }

    private fun resolve(budgetMs: Long = 8_000, token: String = TOKEN, list: List<com.firehome.launcher.Candidate> = cands) =
        PremiumizeService(token).resolve(list, T.now() + budgetMs)

    /** The documented request shape of the batched cache check. */
    private fun checkCacheCall(r: Req?, hashes: List<String>) {
        check(r != null, "a cache check was made")
        r ?: return
        eq(r.method, "POST", "cache/check method (docs: POST /api/cache/check)")
        eq(r.path, "/api/cache/check", "cache/check path")
        eq(r.auth, "Bearer $TOKEN", "cache/check auth header")
        check(r.contentType.startsWith("application/x-www-form-urlencoded"), "cache/check is a form post (got ${r.contentType})")
        eq(r.form["items[]"], hashes, "cache/check items[] in candidate order")
        check(r.q("apikey") == null && r.f("apikey") == null, "no apikey parameter alongside the OAuth token")
    }

    private fun directdlCall(r: Req?, h: String) {
        check(r != null, "a directdl call was made for ${h.take(4)}")
        r ?: return
        eq(r.method, "POST", "directdl method"); eq(r.path, "/api/transfer/directdl", "directdl path")
        eq(r.auth, "Bearer $TOKEN", "directdl auth header")
        eq(r.f("src"), "magnet:?xt=urn:btih:$h&dn=" + java.net.URLEncoder.encode(cands.first { it.hash == h }.torrentName, "UTF-8"), "directdl src magnet")
    }

    private fun nothingStored() = check(Mock.log.none { it.path.startsWith("/api/transfer/create") || it.path.contains("delete") },
        "nothing added to the cloud (directdl only), so nothing to delete")

    fun run() {
        val pack = mapOf(H1 to Pack.files, H2 to Pack.files)

        T.scenario("pm", "cached first candidate: season pack -> S02E03 link", service(pack), TOKEN, link(H1, Pack.files[2].first)) {
            val f = resolve()
            check(f != null, "found a link")
            eq(f?.url, link(H1, Pack.files[Pack.WANT].first), "url is the 'link' of the S02E03 file")
            eq(f?.fileName, Pack.wantName, "file name"); eq(f?.sizeBytes, 2_000_000_000L, "size from content[].size")
            eq(f?.service, "pm", "service"); eq(f?.hash, H1, "hash")
            eq(Mock.log.size, 2, "requests: one cache check + one directdl")
            checkCacheCall(Mock.log.getOrNull(0), listOf(H1, H2, H3))
            directdlCall(Mock.log.getOrNull(1), H1)
            nothingStored()
        }

        T.scenario("pm", "first not cached, second cached -> second", service(mapOf(H2 to Pack.files)), TOKEN) {
            val f = resolve()
            eq(f?.hash, H2, "second candidate's link"); eq(f?.fileName, Pack.wantName, "file name")
            eq(Mock.log.size, 2, "requests"); checkCacheCall(Mock.log.getOrNull(0), listOf(H1, H2, H3)); directdlCall(Mock.log.getOrNull(1), H2)
            check(Mock.log.none { it.f("src")?.contains(H1) == true }, "never asked directdl for the uncached first")
            nothingStored()
        }

        T.scenario("pm", "first cached but its pack lacks S02E03 -> next cached one", service(mapOf(H1 to Pack.withoutE03, H2 to Pack.files)), TOKEN) {
            val f = resolve()
            eq(f?.hash, H2, "second candidate"); eq(f?.fileName, Pack.wantName, "file name")
            eq(Mock.log.size, 3, "requests: check + 2 directdl"); directdlCall(Mock.log.getOrNull(1), H1); directdlCall(Mock.log.getOrNull(2), H2)
            nothingStored()
        }

        T.scenario("pm", "nothing cached -> null, one request, nothing left", service(emptyMap()), TOKEN) {
            eq(resolve(), null, "result"); eq(Mock.log.size, 1, "requests"); checkCacheCall(Mock.log.getOrNull(0), listOf(H1, H2, H3)); nothingStored()
        }

        T.scenario("pm", "bad / expired token (status error, authentication_failed) -> null", service(pack), TOKEN, "pm-stale") {
            eq(resolve(token = "pm-stale-token"), null, "result"); eq(Mock.log.size, 1, "requests (stops after the failed check)")
            eq(Mock.log.getOrNull(0)?.auth, "Bearer pm-stale-token", "auth header carries the stored token")
        }

        T.scenario("pm", "blank token -> null with no request", service(pack)) {
            eq(resolve(token = ""), null, "result"); eq(Mock.log.size, 0, "requests")
        }

        val ok = service(pack)
        T.scenario("pm", "malformed JSON from cache/check -> null", { r -> if (r.path == "/api/cache/check") Resp.json("""{"status":"success","response":[tru""") else ok(r) }, TOKEN) {
            eq(resolve(), null, "result")
        }
        T.scenario("pm", "partial cache/check (no response array) -> null", { r -> if (r.path == "/api/cache/check") Resp.json("""{"status":"success"}""") else ok(r) }, TOKEN) {
            eq(resolve(), null, "result"); eq(Mock.log.size, 1, "requests")
        }
        T.scenario("pm", "directdl: content missing / not an array -> null", { r -> if (r.path == "/api/transfer/directdl") Resp.json("""{"status":"success","location":"x","content":"nope"}""") else ok(r) }, TOKEN) {
            eq(resolve(), null, "result")
        }
        T.scenario("pm", "directdl: malformed JSON -> null", { r -> if (r.path == "/api/transfer/directdl") Resp.json("""{"status":"succ""") else ok(r) }, TOKEN) {
            eq(resolve(), null, "result")
        }
        T.scenario("pm", "directdl: link generation error (status error) -> next cached", { r ->
            if (r.path == "/api/transfer/directdl" && r.f("src")!!.contains(H1)) Resp.json("""{"status":"error","code":"link_generation_failed","message":"There was an error generating the link right now."}""") else ok(r)
        }, TOKEN) {
            eq(resolve()?.hash, H2, "second cached candidate")
        }
        T.scenario("pm", "HTTP 500 (unknown_error envelope) -> null", { _ -> Resp.json("""{"status":"error","code":"unknown_error","message":"x"}""", 500) }, TOKEN) {
            eq(resolve(), null, "result")
        }
        T.scenario("pm", "connection dropped -> null", { _ -> Resp(-1) }, TOKEN) { eq(resolve(), null, "result") }
        T.scenario("pm", "empty 200 body -> null", { _ -> Resp(200, "") }, TOKEN) { eq(resolve(), null, "result") }

        T.scenario("pm", "deadline: cache check hangs (20 s) -> null by the 2.5 s deadline", { r -> if (r.path == "/api/cache/check") Resp(200, "{}", 20_000) else ok(r) }, TOKEN) {
            val (f, ms) = T.timed { resolve(2_500) }
            eq(f, null, "result"); check(ms <= 2_500 + 300, "returned after $ms ms (deadline 2500)")
        }
        T.scenario("pm", "deadline: directdl hangs -> null by the 3 s deadline", { r -> if (r.path == "/api/transfer/directdl") Resp(200, "{}", 20_000) else ok(r) }, TOKEN) {
            val (f, ms) = T.timed { resolve(3_000) }
            eq(f, null, "result"); check(ms <= 3_000 + 300, "returned after $ms ms (deadline 3000)")
        }
    }
}
