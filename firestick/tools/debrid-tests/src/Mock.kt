package debridtest

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLConnection
import java.net.URLDecoder
import java.net.URLStreamHandler
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** One request the code under test made, as the mock saw it. [svc] is "pm" | "tb" | "rd" (from the real host). */
class Req(
    val svc: String, val method: String, val path: String, val rawQuery: String,
    val headers: Map<String, String>, val body: String,
) {
    val auth get() = headers["authorization"]
    val contentType get() = headers["content-type"].orEmpty()
    val query: Map<String, List<String>> by lazy { parseForm(rawQuery) }
    fun q(k: String) = query[k]?.firstOrNull()
    /** application/x-www-form-urlencoded body. */
    val form: Map<String, List<String>> by lazy { parseForm(body) }
    fun f(k: String) = form[k]?.firstOrNull()
    /** multipart/form-data body (TorBox createtorrent): name -> value. */
    val multipart: Map<String, String> by lazy {
        val b = contentType.substringAfter("boundary=", "")
        if (b.isEmpty()) emptyMap() else body.split("--$b").mapNotNull { part ->
            val name = Regex("name=\"([^\"]+)\"").find(part)?.groupValues?.get(1) ?: return@mapNotNull null
            name to part.substringAfter("\r\n\r\n").removeSuffix("\r\n")
        }.toMap()
    }
    override fun toString() = "$method /$svc$path${if (rawQuery.isNotEmpty()) "?$rawQuery" else ""}"

    companion object {
        fun parseForm(s: String): Map<String, List<String>> = if (s.isBlank()) emptyMap() else
            s.split("&").filter { it.isNotEmpty() }.map {
                URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("=", ""), "UTF-8")
            }.groupBy({ it.first }, { it.second })
    }
}

/** What the mock answers. [code] -1 drops the connection without an answer. */
class Resp(val code: Int, val body: String = "", val delayMs: Long = 0) {
    companion object {
        fun json(body: String, code: Int = 200, delayMs: Long = 0) = Resp(code, body.trimIndent(), delayMs)
    }
}

/**
 * A local HTTP server standing in for the three services. The app code keeps its real https://
 * hosts: a URLStreamHandlerFactory (JVM-wide, harness only) sends every https URL for
 * www.premiumize.me, api.torbox.app and api.real-debrid.com to http://127.0.0.1:<port>/<svc><path>.
 * Any other host fails the request (and is recorded as a stray).
 */
object Mock {
    val HOSTS = mapOf("www.premiumize.me" to "pm", "api.torbox.app" to "tb", "api.real-debrid.com" to "rd", "api.alldebrid.com" to "ad")

    private lateinit var server: HttpServer
    var port = 0; private set
    val log = CopyOnWriteArrayList<Req>()
    val strays = CopyOnWriteArrayList<String>()
    val inflight = AtomicInteger()
    @Volatile var lastAt = 0L
    @Volatile var route: (Req) -> Resp = { Resp(404, "{}") }

    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 64)
        server.executor = Executors.newCachedThreadPool { r -> Thread(r, "mock").apply { isDaemon = true } }
        server.createContext("/") { ex -> handle(ex) }
        server.start()
        port = server.address.port
        URL.setURLStreamHandlerFactory { proto ->
            if (proto != "https") null else object : URLStreamHandler() {
                override fun openConnection(u: URL): URLConnection {
                    val svc = HOSTS[u.host] ?: run { strays.add(u.toString().substringBefore('?')); throw IOException("harness: unexpected host ${u.host}") }
                    return URL("http", "127.0.0.1", port, "/$svc${u.file}").openConnection()
                }
            }
        }
    }

    fun reset(r: (Req) -> Resp) { log.clear(); strays.clear(); route = r }

    private fun handle(ex: HttpExchange) {
        inflight.incrementAndGet()
        var hung = false
        try {
            val p = ex.requestURI.rawPath
            val svc = p.removePrefix("/").substringBefore('/')
            val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val req = Req(svc, ex.requestMethod, p.removePrefix("/$svc"), ex.requestURI.rawQuery.orEmpty(), headers, body)
            log.add(req)
            val resp = try { route(req) } catch (t: Throwable) { Resp(599, "harness route threw $t") }
            if (resp.delayMs >= 5_000) {
                // a deliberate hang: the client is meant to time out, so it doesn't count as work in flight
                inflight.decrementAndGet(); hung = true
            }
            if (resp.delayMs > 0) Thread.sleep(resp.delayMs)
            if (resp.code < 0) { ex.close(); return }
            val bytes = resp.body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            if (resp.code == 204 || bytes.isEmpty()) ex.sendResponseHeaders(resp.code, -1)
            else { ex.sendResponseHeaders(resp.code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) } }
        } catch (e: IOException) {
            // the client gave up (timeout): nothing to answer
        } finally {
            ex.close()
            if (!hung) { lastAt = System.nanoTime() / 1_000_000; inflight.decrementAndGet() }
        }
    }
}
