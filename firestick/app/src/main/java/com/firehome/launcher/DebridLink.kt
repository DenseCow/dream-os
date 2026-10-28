package com.firehome.launcher

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Debrid device sign-in done by Dream OS itself (plain HTTPS), under
 * Dream OS's own identity. The result is the account's setting values ([Done.settings]), which
 * Dream OS keeps (DreamAccounts).
 *
 *  - Real-Debrid: GET oauth/v2/device/code?client_id=X245A4XAIBGVM&new_credentials=yes (Real-Debrid's
 *    published client id for open-source apps, which hands each user their own client_id /
 *    client_secret), poll oauth/v2/device/credentials, then POST oauth/v2/token (grant_type
 *    http://oauth.net/grant_type/device/1.0). Saves realdebrid.clientid, realdebridsecret,
 *    realdebridtoken, realdebridrefresh, realdebridusername (rest/1.0/user).
 *  - AllDebrid: GET v4.1/pin/get?agent=DreamOS, POST v4/pin/check (check, pin) until activated:
 *    apikey. Saves alldebridtoken, alldebridusername (v4/user).
 *  - Premiumize: device sign-in needs a client id registered to Dream OS (local.properties
 *    premiumize.client_id -> BuildConfig). POST www.premiumize.me/token (response_type device_code),
 *    poll with grant_type device_code. Without one, the account's API key is entered instead
 *    ([premiumizeKey]) and stored as "apikey:<key>" (DebridHttp sends it as the apikey parameter).
 *    Saves premiumizetoken, premiumizeusername (api/account/info customer_id).
 *  - TorBox: GET user/auth/device/start?app=DreamOS, POST user/auth/device/token {device_code}.
 *    Saves torboxtoken, torbox.username (user/me).
 * Each also turns the service's "<name>.enable" switch on (linking is the user asking to use it).
 */
object DebridLink {
    private const val TAG = "FireHome"

    /** A sign-in waiting for the user: show [code] and [url], with a QR code of [qr]. */
    class Pending(val service: String, val code: String, val url: String, val qr: String, val intervalS: Int, val expiresAt: Long,
                  val state: Map<String, String>)

    sealed class Poll {
        object Waiting : Poll()
        class Done(val settings: Map<String, String>, val user: String?) : Poll()
        class Failed(val message: String) : Poll()
    }

    private const val RD_OAUTH = "https://api.real-debrid.com/oauth/v2/"
    private const val RD_REST = "https://api.real-debrid.com/rest/1.0/"
    /** Real-Debrid's public client id for open-source apps (each user then gets their own client id and secret). */
    private const val RD_CLIENT = "X245A4XAIBGVM"
    private const val AD_AGENT = AllDebridService.AGENT
    private val PM_CLIENT: String get() = BuildConfig.PREMIUMIZE_CLIENT_ID.trim()
    private const val TB_API = "https://api.torbox.app/v1/api"
    private const val TB_APP = "DreamOS"

    /** False when [service] can't do the code-and-QR sign-in in this build (Premiumize without a registered client id). */
    fun deviceSignIn(service: String) = service != "pm" || PM_CLIENT.isNotEmpty()

    /**
     * Links Premiumize with the account's API key (premiumize.me/account), for builds without a
     * registered device sign-in. Blocking. [Poll.Done] when Premiumize accepts it.
     */
    fun premiumizeKey(key: String): Poll = try {
        val k = key.trim().filter { !it.isWhitespace() }
        if (k.length !in 8..64) Poll.Failed("That doesn't look like a key. Check it and try again.")
        else {
            val o = get("https://www.premiumize.me/api/account/info?apikey=${enc(k)}")
            if (o.optString("status") != "success") Poll.Failed("Premiumize didn't accept that key. Check it and try again.")
            else {
                val user = o.opt("customer_id")?.toString().orEmpty()
                Poll.Done(linkedMapOf("premiumizetoken" to DebridHttp.API_KEY_PREFIX + k, "premiumizeusername" to user, "premiumize.enable" to "true"), user.ifEmpty { null })
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Accounts: PM key check failed: ${e.javaClass.simpleName}")
        Poll.Failed("Premiumize didn't answer. Check the network and try again.")
    }

    /** Starts a sign-in for service key "rd" / "ad" / "pm" / "tb". Blocking; null when the service didn't answer. */
    fun start(service: String): Pending? = try {
        val now = System.currentTimeMillis()
        when (service) {
            "rd" -> {
                val o = get("${RD_OAUTH}device/code?client_id=$RD_CLIENT&new_credentials=yes")
                val code = o.getString("user_code")
                val url = o.optString("verification_url").ifEmpty { "https://real-debrid.com/device" }
                val direct = o.optString("direct_verification_url").ifEmpty { "$url?user_code=$code" }
                Pending("rd", code, url, direct, o.optInt("interval", 5).coerceAtLeast(3), now + o.optInt("expires_in", 600) * 1000L,
                    mapOf("device_code" to o.getString("device_code")))
            }
            "ad" -> {
                val o = get("https://api.alldebrid.com/v4.1/pin/get?agent=$AD_AGENT").getJSONObject("data")
                val pin = o.getString("pin")
                Pending("ad", pin, o.optString("base_url").ifEmpty { "https://alldebrid.com/pin/" },
                    o.optString("user_url").ifEmpty { "https://alldebrid.com/pin/?pin=$pin" }, 5,
                    now + o.optInt("expires_in", 600) * 1000L, mapOf("check" to o.getString("check"), "pin" to pin))
            }
            "pm" -> if (PM_CLIENT.isEmpty()) null else {
                val o = post("https://www.premiumize.me/token", mapOf("client_id" to PM_CLIENT, "response_type" to "device_code"))
                val url = o.optString("verification_uri").ifEmpty { "https://www.premiumize.me/device" }
                Pending("pm", o.getString("user_code"), url, url, o.optInt("interval", 5).coerceAtLeast(3),
                    now + o.optInt("expires_in", 600) * 1000L, mapOf("device_code" to o.getString("device_code")))
            }
            "tb" -> {
                val o = get("$TB_API/user/auth/device/start?app=$TB_APP")
                if (!o.optBoolean("success")) throw IllegalStateException("start refused")
                val d = o.getJSONObject("data")
                val verify = d.optString("verification_url").ifEmpty { d.optString("verification_uri") }.ifEmpty { "https://torbox.app/oauth/device?app=$TB_APP" }
                val friendly = d.optString("friendly_verification_url").ifEmpty { d.optString("friendly_url") }.ifEmpty { verify }
                Pending("tb", d.getString("code"), friendly, verify, d.optInt("interval", 5).coerceAtLeast(3), now + 600_000L,
                    mapOf("device_code" to d.getString("device_code")))
            }
            else -> null
        }.also { Log.w(TAG, "Accounts: ${service.uppercase()} sign-in started (code shown, polling every ${it?.intervalS} s)") }
    } catch (e: Exception) {
        Log.w(TAG, "Accounts: ${service.uppercase()} sign-in start failed: $e")
        null
    }

    /** One check whether the user approved [p]. Blocking. */
    fun poll(p: Pending): Poll = try {
        if (System.currentTimeMillis() > p.expiresAt) Poll.Failed("The code expired. Try again.")
        else when (p.service) {
            "rd" -> {
                val cred = getOrNull("${RD_OAUTH}device/credentials?client_id=$RD_CLIENT&code=${enc(p.state.getValue("device_code"))}")
                if (cred == null || !cred.has("client_secret")) Poll.Waiting
                else {
                    val id = cred.getString("client_id")
                    val secret = cred.getString("client_secret")
                    val tok = post("${RD_OAUTH}token", mapOf("client_id" to id, "client_secret" to secret, "code" to p.state.getValue("device_code"),
                        "grant_type" to "http://oauth.net/grant_type/device/1.0"))
                    val access = tok.getString("access_token")
                    val user = try { get("${RD_REST}user", bearer = access).optString("username") } catch (e: Exception) { "" }
                    Poll.Done(linkedMapOf("realdebrid.clientid" to id, "realdebridsecret" to secret, "realdebridtoken" to access,
                        "realdebridrefresh" to tok.getString("refresh_token"), "realdebridusername" to user, "realdebrid.enable" to "true"), user.ifEmpty { null })
                }
            }
            "ad" -> {
                val d = post("https://api.alldebrid.com/v4/pin/check", mapOf("check" to p.state.getValue("check"), "pin" to p.state.getValue("pin")))
                    .optJSONObject("data")
                if (d == null || !d.optBoolean("activated") || d.optString("apikey").isEmpty()) Poll.Waiting
                else {
                    val key = d.getString("apikey")
                    val user = try { get("https://api.alldebrid.com/v4/user?agent=$AD_AGENT&apikey=${enc(key)}").getJSONObject("data").getJSONObject("user").optString("username") } catch (e: Exception) { "" }
                    Poll.Done(linkedMapOf("alldebridtoken" to key, "alldebridusername" to user, "alldebrid.enable" to "true"), user.ifEmpty { null })
                }
            }
            "pm" -> {
                val o = postOrError("https://www.premiumize.me/token", mapOf("client_id" to PM_CLIENT, "code" to p.state.getValue("device_code"), "grant_type" to "device_code"))
                when {
                    o.has("access_token") -> {
                        val access = o.getString("access_token")
                        val user = try { get("https://www.premiumize.me/api/account/info", bearer = access).opt("customer_id")?.toString().orEmpty() } catch (e: Exception) { "" }
                        Poll.Done(linkedMapOf("premiumizetoken" to access, "premiumizeusername" to user, "premiumize.enable" to "true"), user.ifEmpty { null })
                    }
                    o.optString("error") == "access_denied" -> Poll.Failed("The sign-in was declined")
                    else -> Poll.Waiting
                }
            }
            "tb" -> {
                val o = postJson("$TB_API/user/auth/device/token", JSONObject().put("device_code", p.state.getValue("device_code")))
                val access = o?.optJSONObject("data")?.optString("access_token").orEmpty()
                if (o?.optBoolean("success") != true || access.isEmpty()) Poll.Waiting
                else {
                    val me = try { get("$TB_API/user/me", bearer = access).optJSONObject("data") } catch (e: Exception) { null }
                    val user = me?.optString("email")?.ifEmpty { null } ?: me?.opt("customer")?.toString().orEmpty()
                    Poll.Done(linkedMapOf("torboxtoken" to access, "torbox.username" to user, "torbox.enable" to "true"), user.ifEmpty { null })
                }
            }
            else -> Poll.Failed("This service can't be linked here")
        }
    } catch (e: Exception) {
        Log.w(TAG, "Accounts: ${p.service.uppercase()} poll: $e")
        Poll.Waiting // a network blip: keep waiting until the code expires
    }

    // ---- HTTP ----

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun open(url: String, bearer: String?): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", "Dream OS")
        setRequestProperty("Accept", "application/json")
        if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
    }

    private fun read(c: HttpURLConnection): Pair<Int, String> {
        val code = c.responseCode
        val s = c.runCatching { (if (code in 200..299) inputStream else errorStream)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        return code to s
    }

    private fun get(url: String, bearer: String? = null): JSONObject {
        val c = open(url, bearer)
        try {
            val (code, s) = read(c)
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return JSONObject(s)
        } finally { c.disconnect() }
    }

    /** null while the server says "not yet" (an error status). */
    private fun getOrNull(url: String): JSONObject? {
        val c = open(url, null)
        try {
            val (code, s) = read(c)
            return if (code in 200..299 && s.isNotBlank()) JSONObject(s) else null
        } finally { c.disconnect() }
    }

    private fun form(fields: Map<String, String>) = fields.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }.toByteArray()

    private fun post(url: String, fields: Map<String, String>): JSONObject {
        val o = postOrError(url, fields)
        if (o.has("error") && !o.has("access_token") && !o.has("data") && !o.has("user_code")) throw IllegalStateException(o.optString("error"))
        return o
    }

    /** The JSON reply whatever the status (device-code polls answer "not yet" with an error status). */
    private fun postOrError(url: String, fields: Map<String, String>): JSONObject {
        val c = open(url, null)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = form(fields)
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val (_, s) = read(c)
            return if (s.isBlank()) JSONObject() else JSONObject(s)
        } finally { c.disconnect() }
    }

    private fun postJson(url: String, json: JSONObject): JSONObject? {
        val c = open(url, null)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            val body = json.toString().toByteArray()
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val (_, s) = read(c)
            return if (s.isBlank()) null else JSONObject(s)
        } finally { c.disconnect() }
    }
}
