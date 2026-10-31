package com.dreamos.desktop.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** A clock tests move by hand (monotonic and wall together). */
class FakeClock(start: Long = 1_000_000L) : Clock {
    val t = AtomicLong(start)
    override fun monotonicMs() = t.get()
    override fun wallMs() = 1_700_000_000_000L + t.get()
    fun advance(ms: Long) { t.addAndGet(ms) }
}

/** A recorded request: method, url, body as text. */
data class Sent(val method: String, val url: String, val body: String, val headers: Map<String, String>)

/** Answers by the first route whose key the URL contains; unrouted: 404. Records every request. */
class FakeHttp(private val routes: MutableList<Pair<String, (Sent) -> HttpReply>> = mutableListOf()) : Http {
    val sent = CopyOnWriteArrayList<Sent>()
    fun on(urlPart: String, answer: (Sent) -> HttpReply) = apply { routes.add(0, urlPart to answer) }
    fun json(urlPart: String, body: String, code: Int = 200) = on(urlPart) { HttpReply(code, body) }
    override suspend fun send(req: HttpReq): HttpReply {
        val s = Sent(req.method, req.url, req.body?.toString(Charsets.UTF_8).orEmpty(), req.headers)
        sent += s
        return routes.firstOrNull { s.url.contains(it.first) }?.second?.invoke(s) ?: HttpReply(404, "")
    }
    fun count(urlPart: String) = sent.count { it.url.contains(urlPart) }
}

/** A kit on fakes; background work runs on a real dispatcher (tests wait for it where it matters). */
fun fakeKit(http: Http, clock: FakeClock = FakeClock(), secrets: SecretStore = MemorySecrets(), store: KeyValueStore = MemoryStore()) =
    DebridKit(DebridHttp(http, clock), DebridLedger(store, clock), DebridAccounts(secrets, store), clock, CoroutineScope(SupervisorJob() + Dispatchers.IO))

fun src(hash: String, name: String, q: Quality = Quality.FHD_1080, size: Long = 2L shl 30, seeders: Int = 10, season: Int? = null, episode: Int? = null) =
    Source(hash, name, sizeBytes = size, quality = q, seeders = seeders, season = season, episode = episode)
