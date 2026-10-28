package debridtest

import com.firehome.launcher.AllDebridService
import com.firehome.launcher.DebridLedger
import com.firehome.launcher.DebridPurpose
import debridtest.Pack.cand
import debridtest.Pack.hash
import debridtest.T.check
import debridtest.T.eq
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/*
 * AllDebrid fixtures, shaped like the live API as seen from this PC on 2026-10-25 (api.alldebrid.com):
 *   POST /v4/magnet/upload  magnets[]=<hash>...  -> {"status":"success","data":{"magnets":[{"magnet","hash","name","size","ready","id"}]}}
 *     account full: {"status":"error","error":{"code":"MAGNET_TOO_MANY","message":"Magnets limit reached (1000 accross all tabs)"}}
 *     (it counts recently deleted magnets too: the live account held 10 and still answered this)
 *   POST /v4.1/magnet/status -> {"status":"success","data":{"magnets":[{"id","hash","filename","uploadDate","statusCode",...}]}} (v4 is DISCONTINUED)
 *   POST /v4/magnet/files id[]=<id> -> {"data":{"magnets":[{"id","files":[{"n","s","l"} | {"n","e":[...]}]}]}}
 *   POST /v4/link/unlock link=<l> -> {"data":{"link","filename","filesize"}}
 *   POST /v4/magnet/delete ids[]=<id>... (or id=<id>) -> success, or {"error":{"code":"MAGNET_INVALID_ID"}} (probed live with a foreign id)
 * Re-uploading a hash the account already holds answers with that magnet's own id.
 */
object AllDebridTests {
    private var n = 0
    /** A fresh token (and so a fresh ledger scope) per scenario. */
    private fun token() = "ad-key-5ecret-${++n}"
    val A = hash('a'); val B = hash('b'); val C = hash('c'); val D = hash('d'); val E = hash('e')
    val F = hash('f'); val G = hash('1'); val H = hash('2')
    val U1 = hash('7'); val U2 = hash('8'); val U3 = hash('9')

    /** A stateful AllDebrid account: [user] magnets already in it (hash), [cached] hashes with their files. */
    class Account(val cached: Map<String, List<Pair<String, Long>>>, user: List<String> = emptyList()) {
        val ids = AtomicLong(820_000_000)
        val magnets = ConcurrentHashMap<String, String>() // id -> hash
        val uploads = CopyOnWriteArrayList<List<String>>()
        val deletes = CopyOnWriteArrayList<List<String>>()
        @Volatile var full = false
        val userIds = user.map { h -> (ids.incrementAndGet()).toString().also { magnets[it] = h } }

        fun idOf(h: String) = magnets.entries.firstOrNull { it.value == h }?.key
        fun holds(h: String) = idOf(h) != null

        private fun ok(data: Any) = Resp.json(JSONObject().put("status", "success").put("data", data).toString())
        private fun err(code: String) = Resp.json(JSONObject().put("status", "error").put("error", JSONObject().put("code", code).put("message", code)).toString())

        val route: (Req) -> Resp = r@{ r ->
            when (r.path) {
                "/v4/magnet/upload" -> {
                    val hs = r.form["magnets[]"].orEmpty()
                    uploads.add(hs)
                    if (full) return@r err("MAGNET_TOO_MANY")
                    val arr = JSONArray()
                    for (h in hs) {
                        val id = idOf(h) ?: ids.incrementAndGet().toString().also { magnets[it] = h }
                        arr.put(JSONObject().put("magnet", h).put("hash", h).put("name", Pack.DIR).put("size", 1).put("ready", h in cached).put("id", id.toLong()))
                    }
                    ok(JSONObject().put("magnets", arr))
                }
                "/v4.1/magnet/status" -> ok(JSONObject().put("magnets", JSONArray(magnets.map { (id, h) ->
                    JSONObject().put("id", id.toLong()).put("hash", h).put("filename", "x").put("statusCode", 4).put("uploadDate", 1)
                })))
                "/v4/magnet/files" -> {
                    val id = r.f("id[]").orEmpty()
                    val h = magnets[id] ?: return@r ok(JSONObject().put("magnets", JSONArray().put(JSONObject().put("id", id).put("error", JSONObject().put("code", "MAGNET_INVALID_ID")))))
                    val files = JSONArray((cached[h] ?: emptyList()).mapIndexed { i, (p, s) -> JSONObject().put("n", p.substringAfterLast('/')).put("s", s).put("l", "https://alldebrid.com/f/$id-$i") })
                    ok(JSONObject().put("magnets", JSONArray().put(JSONObject().put("id", id).put("files", files))))
                }
                "/v4/link/unlock" -> ok(JSONObject().put("link", "https://dl-7.alldebrid.example/dl/secret/${r.f("link")!!.substringAfterLast('/')}.mkv")
                    .put("filename", Pack.wantName).put("filesize", 2_000_000_000L))
                "/v4/magnet/delete" -> {
                    val del = r.form["ids[]"] ?: r.form["id"].orEmpty()
                    deletes.add(del)
                    if (del.any { !magnets.containsKey(it) }) return@r err("MAGNET_INVALID_ID")
                    del.forEach { magnets.remove(it) }
                    ok(JSONObject().put("message", "deleted"))
                }
                "/v4/ping" -> ok(JSONObject().put("ping", "pong"))
                else -> Resp(404, "{}")
            }
        }
    }

    private fun fresh() {
        DebridLedger.store = object : DebridLedger.Store {
            val m = ConcurrentHashMap<String, String>()
            override fun get(key: String) = m[key]
            override fun put(key: String, value: String) { m[key] = value }
        }
        DebridLedger.resetForTests()
    }

    private fun deadline(ms: Long = 8_000) = T.now() + ms
    private val pack = Pack.files

    fun run() {
        run {
            val tok = token(); fresh()
            val acc = Account(mapOf(A to pack), listOf(U1, U2))
            T.scenario("ad", "Play: first candidate ready -> 1 upload, link, the played magnet kept", acc.route, tok) {
                val f = AllDebridService(tok).resolve(listOf(cand(A), cand(B), cand(C)), deadline())
                check(f != null, "found a link"); eq(f?.hash, A, "hash")
                eq(acc.uploads.toList(), listOf(listOf(A)), "uploads (one hash)")
                T.settle(); eq(acc.deletes.size, 0, "delete calls")
                check(acc.holds(A) && acc.holds(U1) && acc.holds(U2), "played + user magnets still there")
            }
        }
        run {
            val tok = token(); fresh()
            val acc = Account(mapOf(C to pack), listOf(U1))
            T.scenario("ad", "Play: one at a time, then pairs; all but the played one deleted in one batch", acc.route, tok) {
                val f = AllDebridService(tok).resolve(listOf(cand(A), cand(B), cand(C), cand(D)), deadline())
                eq(f?.hash, C, "found")
                eq(acc.uploads.toList(), listOf(listOf(A), listOf(B, C)), "uploads")
                T.settle()
                eq(acc.deletes.size, 1, "delete calls (batched)")
                check(!acc.holds(A) && !acc.holds(B) && acc.holds(C) && acc.holds(U1), "A, B gone; C (playing) and the user's kept")
                check(android.util.Log.lines.any { "AllDebrid: cleaned 2 (kept 1 playing)" in it }, "cleanup log line")
            }
        }
        run {
            val tok = token(); fresh()
            val acc = Account(mapOf(U1 to Pack.withoutE03, B to pack), listOf(U1, U2))
            T.scenario("ad", "the user's own magnet sent again (same id back) is never deleted", acc.route, tok) {
                val f = AllDebridService(tok).resolve(listOf(cand(U1), cand(A), cand(B)), deadline())
                eq(f?.hash, B, "found")
                T.settle()
                check(acc.holds(U1) && acc.holds(U2), "user's magnets untouched")
                check(!acc.holds(A), "Dream OS's unused magnet deleted")
                check(acc.deletes.flatten().none { it in acc.userIds }, "no delete ever names a user magnet")
            }
        }
        run {
            val tok = token(); fresh()
            val acc = Account(emptyMap(), listOf(U1))
            T.scenario("ad", "nothing cached: every upload deleted; the same title again uploads nothing", acc.route, tok) {
                val s = AllDebridService(tok)
                check(s.resolve(listOf(cand(A), cand(B), cand(C)), deadline()) == null, "null")
                T.settle()
                eq(acc.magnets.values.toSet(), setOf(U1), "account back to the user's own")
                val before = acc.uploads.size
                check(s.resolve(listOf(cand(A), cand(B), cand(C)), deadline()) == null, "null again")
                eq(acc.uploads.size, before, "no new uploads (seen uncached lately)")
            }
        }
        run {
            val tok = token(); fresh()
            val acc = Account(mapOf(A to pack), listOf(U1, U2, U3))
            T.scenario("ad", "MAGNET_TOO_MANY: janitor, one retry, then AllDebrid rests (user's untouched)", acc.route, tok) {
                // An earlier Play left its magnet (A) in the account.
                check(AllDebridService(tok).resolve(listOf(cand(A)), deadline()) != null, "first play")
                T.settle()
                acc.full = true
                val up0 = acc.uploads.size
                check(AllDebridService(tok).resolve(listOf(cand(B), cand(C)), deadline()) == null, "null when full")
                eq(acc.uploads.size - up0, 2, "upload + one retry")
                check(!acc.holds(A), "the janitor removed Dream OS's kept magnet")
                check(acc.holds(U1) && acc.holds(U2) && acc.holds(U3), "user's magnets untouched")
                check(AllDebridService.limited(tok), "limit remembered")
                val req0 = Mock.log.size
                check(AllDebridService(tok).resolve(listOf(cand(D)), deadline()) == null, "skipped while limited")
                eq(Mock.log.size - req0, 0, "no calls while limited")
                check(android.util.Log.lines.any { "AllDebrid: magnet limit" in it && "skipped for 30 min" in it }, "self-heal outcome logged")
            }
        }
        run {
            val tok = token(); fresh()
            val acc = Account(emptyMap(), listOf(U1, U2))
            T.scenario("ad", "janitor: old Dream OS magnets go, at most 25 kept, user's untouched", acc.route, tok) {
                val scope = DebridLedger.scope("ad", tok)
                val own = JSONObject()
                val old = DebridLedger.wall() - 3 * 3_600_000L
                for (i in 0 until 40) {
                    val h = String.format("%040x", i + 1000)
                    val id = acc.ids.incrementAndGet().toString(); acc.magnets[id] = h
                    own.put(h, JSONObject().put("t", if (i < 10) old + i else DebridLedger.wall() - 60_000 + i).put("i", id))
                }
                DebridLedger.store.put("own.$scope", own.toString())
                DebridLedger.store.put("user.$scope", JSONObject().put("h", JSONArray(listOf(U1, U2))).put("at", 1).toString())
                DebridLedger.resetForTests()
                val sw = AllDebridService.janitor(tok, "test", 5_000)
                eq(sw?.removed, 15, "removed (10 old + 5 over the cap)")
                eq(sw?.user, 2, "user's counted")
                eq(acc.magnets.size, 27, "left: 25 Dream OS + 2 user")
                check(acc.holds(U1) && acc.holds(U2), "user's untouched")
                check(android.util.Log.lines.any { "AllDebrid janitor (test): removed 15 Dream OS magnets (user's 2 untouched" in it }, "janitor log line")
            }
        }
        run {
            val tok = token(); fresh()
            val acc = Account(mapOf(A to pack), listOf(U1))
            T.scenario("ad", "browsing and a details page upload nothing", acc.route, tok) {
                check(AllDebridService(tok, DebridPurpose.BROWSE).resolve(listOf(cand(A)), deadline()) == null, "browse null")
                check(AllDebridService(tok, DebridPurpose.PAGE).resolve(listOf(cand(A)), deadline()) == null, "page null")
                eq(Mock.log.size, 0, "requests")
            }
        }
        run {
            val tok = token(); fresh()
            val acc = Account(mapOf(B to pack), listOf(U1))
            T.scenario("ad", "daily budget: next-episode stops at 300 uploads, Play still uploads", acc.route, tok) {
                DebridLedger.counted("ad", 300, false)
                check(AllDebridService(tok, DebridPurpose.AHEAD).resolve(listOf(cand(B)), deadline()) == null, "ahead held back")
                eq(acc.uploads.size, 0, "no upload for ahead")
                check(AllDebridService(tok, DebridPurpose.PLAY).resolve(listOf(cand(B)), deadline()) != null, "play works")
                eq(acc.uploads.size, 1, "one upload for Play")
            }
        }
        run {
            val tok = token(); fresh()
            val acc = Account(mapOf(B to pack, D to pack, G to pack), listOf(U1))
            T.scenario("ad", "source list: top 5 rows in one upload, all deleted; known ones not sent again", acc.route, tok) {
                val list = listOf(A, B, C, D, E, F, G, H).map { cand(it) }
                val r = AllDebridService(tok, DebridPurpose.LIST).readyHashes(list, deadline())
                eq(r, setOf(B, D), "ready among the top 5")
                eq(acc.uploads.toList(), listOf(listOf(A, B, C, D, E)), "one upload of the top 5")
                T.settle()
                eq(acc.magnets.values.toSet(), setOf(U1), "everything deleted again")
                val r2 = AllDebridService(tok, DebridPurpose.LIST).readyHashes(list, deadline())
                eq(r2, setOf(B, D), "same marks from memory")
                eq(acc.uploads.size, 1, "no second upload")
            }
        }
        run {
            val tok = token(); fresh()
            val acc = Account(mapOf(A to pack, C to pack), listOf(U1))
            T.scenario("ad", "a hash seen ready lately goes first within its quality", acc.route, tok) {
                DebridLedger.seen("ad", mapOf(C to true, A to false))
                val f = AllDebridService(tok).resolve(listOf(cand(A), cand(B), cand(C)), deadline())
                eq(f?.hash, C, "found the known one")
                eq(acc.uploads.toList(), listOf(listOf(C)), "one upload, A (seen uncached) skipped")
            }
        }
        run {
            val tok = token(); fresh()
            val acc = Account(emptyMap(), listOf(U1))
            T.scenario("ad", "batch delete refused (one magnet already gone) -> one by one, nothing left", { r ->
                // The user removes A by hand while the lookup goes on: the batch then names a missing id.
                if (r.path == "/v4/magnet/upload" && B in r.form["magnets[]"].orEmpty()) acc.idOf(A)?.let { acc.magnets.remove(it) }
                acc.route(r)
            }, tok) {
                check(AllDebridService(tok).resolve(listOf(cand(A), cand(B), cand(C)), deadline()) == null, "null")
                T.settle()
                eq(acc.magnets.values.toSet(), setOf(U1), "account back to the user's own")
                eq(acc.deletes.size, 4, "one batch + three single deletes")
                check(android.util.Log.lines.any { "AllDebrid: cleaned 3" in it }, "cleanup counted the gone one too")
            }
        }
        run {
            val tok = token(); fresh()
            val acc = Account(mapOf(A to pack, B to pack), listOf(U1))
            T.scenario("ad", "unlock fails everywhere -> null, every Dream OS magnet deleted", { r ->
                if (r.path == "/v4/link/unlock") Resp(500, "oops") else acc.route(r)
            }, tok) {
                check(AllDebridService(tok).resolve(listOf(cand(A), cand(B)), deadline()) == null, "null")
                T.settle()
                eq(acc.magnets.values.toSet(), setOf(U1), "account back to the user's own")
            }
        }
    }
}
