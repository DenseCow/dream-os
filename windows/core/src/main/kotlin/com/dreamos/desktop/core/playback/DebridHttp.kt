package com.dreamos.desktop.core.playback

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One HTTP request as the debrid code sends it. */
class HttpReq(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeoutMs: Long = 6_000,
)

/**
 * An answer: [code] 0 when nothing came back (network, timeout). Never throws for a status. [headers]: the answer's
 * headers, names in lower case, first value each.
 */
class HttpReply(val code: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
    val ok get() = code in 200..299
    fun header(name: String): String? = headers[name.lowercase()]
    fun json(): JsonElement = try { if (body.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(body) } catch (e: Exception) { JsonNull }
}

/** The transport (fakes in tests). Implementations never throw; a failure is code 0. */
fun interface Http {
    suspend fun send(req: HttpReq): HttpReply
}

/**
 * The real transport: one shared JDK HttpClient (HTTP/2 where offered, kept-alive connections, so the second call to
 * a service skips DNS and TLS). Tokens and stream URLs never reach the log.
 */
class JdkHttp : Http {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override suspend fun send(req: HttpReq): HttpReply = try {
        val b = HttpRequest.newBuilder(URI.create(req.url)).timeout(Duration.ofMillis(req.timeoutMs.coerceAtLeast(500)))
        b.header("User-Agent", DebridHttp.UA)
        if ("Accept" !in req.headers) b.header("Accept", "application/json")
        for ((k, v) in req.headers) b.header(k, v)
        b.method(req.method, req.body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody())
        // The request timeout bounds the wait for the answer's headers; the body read is bounded here too.
        withTimeoutOrNull(req.timeoutMs + 1_000) {
            val r = client.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).await()
            HttpReply(r.statusCode(), r.body() ?: "", r.headers().map().entries.associate { (k, v) -> k.lowercase() to v.firstOrNull().orEmpty() })
        } ?: HttpReply(0, "")
    } catch (e: Exception) {
        HttpReply(0, "")
    }
}

/** JSON over HTTPS for the debrid services: form posts, bearer tokens, time left before a deadline. */
class DebridHttp(val http: Http, val clock: Clock) {
    companion object {
        const val UA = "Dream OS"
        /** Marks a stored Premiumize credential that is the account's API key, not an OAuth token. */
        const val API_KEY_PREFIX = "apikey:"

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
        fun form(fields: List<Pair<String, String>>) = fields.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }
    }

    /** Never throws. A Premiumize API key ([API_KEY_PREFIX]) goes as the apikey parameter, not as a Bearer token. */
    suspend fun request(url: String, method: String = "GET", bearer: String? = null, body: ByteArray? = null,
                        contentType: String? = null, timeoutMs: Long = 6_000, headers: Map<String, String> = emptyMap()): HttpReply {
        val apiKey = bearer?.takeIf { it.startsWith(API_KEY_PREFIX) }?.removePrefix(API_KEY_PREFIX)
        val target = if (apiKey != null) url + (if ('?' in url) "&" else "?") + "apikey=" + enc(apiKey) else url
        val h = LinkedHashMap(headers)
        if (bearer != null && apiKey == null) h["Authorization"] = "Bearer $bearer"
        if (contentType != null) h["Content-Type"] = contentType
        return http.send(HttpReq(target, method, h, body, timeoutMs))
    }

    suspend fun get(url: String, bearer: String? = null, timeoutMs: Long = 6_000) = request(url, bearer = bearer, timeoutMs = timeoutMs)

    suspend fun postForm(url: String, fields: List<Pair<String, String>>, bearer: String? = null, timeoutMs: Long = 6_000) =
        request(url, "POST", bearer, form(fields).toByteArray(), "application/x-www-form-urlencoded", timeoutMs)

    suspend fun postJson(url: String, json: JsonElement, bearer: String? = null, timeoutMs: Long = 6_000) =
        request(url, "POST", bearer, json.toString().toByteArray(), "application/json", timeoutMs)

    /** Timeout left until [deadline] (monotonic), at least 1 s, at most [cap]. */
    fun left(deadline: Long, cap: Long = 6_000): Long = (deadline - clock.monotonicMs()).coerceIn(1_000L, cap)
}

// ---- small JSON readers (org.json's opt* style) ----

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
operator fun JsonElement?.get(i: Int): JsonElement? = (this as? JsonArray)?.getOrNull(i)
val JsonElement?.str: String get() = when (this) {
    is JsonPrimitive -> if (this is JsonNull) "" else contentOrNull.orEmpty()
    else -> ""
}
val JsonElement?.long: Long get() = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() ?: it.contentOrNull?.toDoubleOrNull()?.toLong() } ?: 0L
val JsonElement?.int: Int get() = long.toInt()
val JsonElement?.bool: Boolean get() = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "1") } ?: false
val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()
val JsonElement?.obj: JsonObject? get() = this as? JsonObject
