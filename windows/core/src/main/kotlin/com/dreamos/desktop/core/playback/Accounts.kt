package com.dreamos.desktop.core.playback

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Debrid sign-in and account health (fire-home DebridLink + AccountHealth, iPhone DebridLink.swift +
 * DebridAccountsStatus.swift), under Dream OS's own identity:
 *  - Real-Debrid: device code with Real-Debrid's published client id for open-source apps ([RD_CLIENT], which hands each
 *    viewer their own client id and secret), then the token; saves the device credentials for renewals.
 *  - AllDebrid: v4.1 pin/get with agent "DreamOS", pin/check until activated.
 *  - Premiumize: device sign-in only with a client id registered to Dream OS ([premiumizeClientId], from
 *    local.properties at run time, never in the code); otherwise the account's API key.
 *  - TorBox: device start with app "DreamOS".
 * On a PC the code page can also open in the browser ([SignInCode.qrUrl]). Tokens never reach the log.
 */
class Accounts(private val kit: DebridKit, private val healthStore: KeyValueStore, private val premiumizeClientId: String = "",
               private val core: DreamCore? = null) : AccountsApi {
    companion object {
        private const val RD_CLIENT = "X245A4XAIBGVM"
        private const val TB_APP = "DreamOS"
        const val STALE_MS = 30 * 60_000L
        private const val DAY = 86_400_000L

        /** The services' ISO 8601 dates, offsets included; 0 when missing or unreadable. */
        fun iso(s: String?): Long = try { if (s.isNullOrBlank()) 0 else OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (e: DateTimeParseException) {
            try { java.time.LocalDateTime.parse(s!!.removeSuffix("Z")).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() } catch (x: Exception) { 0 }
        }
    }

    private val http get() = kit.http
    private fun wall() = kit.clock.wallMs()
    private val acc get() = kit.accounts
    private val store = HealthStore()

    override val linked: StateFlow<List<DebridServiceId>> get() = acc.linked

    override fun deviceSignIn(service: DebridServiceId) = service != DebridServiceId.PREMIUMIZE || premiumizeClientId.isNotBlank()

    override suspend fun startSignIn(service: DebridServiceId): SignInCode? {
        val now = wall()
        val r: SignInCode? = when (service) {
            DebridServiceId.REAL_DEBRID -> {
                val o = http.get("${RealDebridService.OAUTH}device/code?client_id=$RD_CLIENT&new_credentials=yes").takeIf { it.ok }?.json() ?: return failed(service)
                val code = o["user_code"].str.ifEmpty { return failed(service) }
                val url = o["verification_url"].str.ifEmpty { "https://real-debrid.com/device" }
                SignInCode(service, code, url, o["direct_verification_url"].str.ifEmpty { "$url?user_code=$code" }, if (o["interval"] != null) o["interval"].int.coerceAtLeast(3) else 5,
                    now + (o["expires_in"].long.takeIf { it > 0 } ?: 600) * 1000, mapOf("device_code" to o["device_code"].str))
            }
            DebridServiceId.ALLDEBRID -> {
                val d = http.get("https://api.alldebrid.com/v4.1/pin/get?agent=${AllDebridService.AGENT}").takeIf { it.ok }?.json()?.get("data") ?: return failed(service)
                val pin = d["pin"].str.ifEmpty { return failed(service) }
                SignInCode(service, pin, d["base_url"].str.ifEmpty { "https://alldebrid.com/pin/" }, d["user_url"].str.ifEmpty { "https://alldebrid.com/pin/?pin=$pin" },
                    5, now + (d["expires_in"].long.takeIf { it > 0 } ?: 600) * 1000, mapOf("check" to d["check"].str, "pin" to pin))
            }
            DebridServiceId.PREMIUMIZE -> {
                if (premiumizeClientId.isBlank()) return null
                val o = http.postForm("https://www.premiumize.me/token", listOf("client_id" to premiumizeClientId, "response_type" to "device_code")).json()
                val code = o["user_code"].str.ifEmpty { return failed(service) }
                val url = o["verification_uri"].str.ifEmpty { "https://www.premiumize.me/device" }
                SignInCode(service, code, url, url, if (o["interval"] != null) o["interval"].int.coerceAtLeast(3) else 5, now + (o["expires_in"].long.takeIf { it > 0 } ?: 600) * 1000,
                    mapOf("device_code" to o["device_code"].str))
            }
            DebridServiceId.TORBOX -> {
                val o = http.get("${TorBoxService.API}/user/auth/device/start?app=$TB_APP").json()
                if (!o["success"].bool) return failed(service)
                val d = o["data"]
                val verify = d["verification_url"].str.ifEmpty { d["verification_uri"].str }.ifEmpty { "https://torbox.app/oauth/device?app=$TB_APP" }
                val friendly = d["friendly_verification_url"].str.ifEmpty { d["friendly_url"].str }.ifEmpty { verify }
                SignInCode(service, d["code"].str, friendly, verify, if (d["interval"] != null) d["interval"].int.coerceAtLeast(3) else 5, now + 600_000L, mapOf("device_code" to d["device_code"].str))
            }
        }
        PlaybackLog.w("Accounts: ${service.key.uppercase()} sign-in started (code shown, polling every ${r?.intervalS} s)")
        return r
    }

    private fun failed(s: DebridServiceId): SignInCode? { PlaybackLog.w("Accounts: ${s.key.uppercase()} sign-in start failed"); return null }

    override suspend fun poll(code: SignInCode): SignInPoll {
        if (wall() > code.expiresAt) return SignInPoll.Failed("The code expired. Try again.")
        val p = code.state
        val done: Pair<Map<String, String>, String?>? = when (code.service) {
            DebridServiceId.REAL_DEBRID -> {
                val cred = http.get("${RealDebridService.OAUTH}device/credentials?client_id=$RD_CLIENT&code=${DebridHttp.enc(p.getValue("device_code"))}")
                val c = if (cred.ok) cred.json() else null
                if (c == null || c["client_secret"].str.isEmpty()) null else {
                    val id = c["client_id"].str; val secret = c["client_secret"].str
                    val tok = http.postForm("${RealDebridService.OAUTH}token", listOf("client_id" to id, "client_secret" to secret,
                        "code" to p.getValue("device_code"), "grant_type" to "http://oauth.net/grant_type/device/1.0")).json()
                    val access = tok["access_token"].str
                    if (access.isEmpty()) null else {
                        val user = http.get("${RealDebridService.REST}user", access).json()["username"].str
                        mapOf("realdebrid.clientid" to id, "realdebridsecret" to secret, "realdebridtoken" to access,
                            "realdebridrefresh" to tok["refresh_token"].str, "realdebridusername" to user) to user
                    }
                }
            }
            DebridServiceId.ALLDEBRID -> {
                val d = http.postForm("https://api.alldebrid.com/v4/pin/check", listOf("check" to p.getValue("check"), "pin" to p.getValue("pin"))).json()["data"]
                val key = d["apikey"].str
                if (!d["activated"].bool || key.isEmpty()) null else {
                    val user = http.get("${AllDebridService.API}user?agent=${AllDebridService.AGENT}", key).json()["data"]["user"]["username"].str
                    mapOf("alldebridtoken" to key, "alldebridusername" to user) to user
                }
            }
            DebridServiceId.PREMIUMIZE -> {
                val o = http.postForm("https://www.premiumize.me/token", listOf("client_id" to premiumizeClientId, "code" to p.getValue("device_code"), "grant_type" to "device_code")).json()
                when {
                    o["access_token"].str.isNotEmpty() -> {
                        val access = o["access_token"].str
                        val user = http.get("${PremiumizeService.API}/account/info", access).json()["customer_id"].str
                        mapOf("premiumizetoken" to access, "premiumizeusername" to user) to user
                    }
                    o["error"].str == "access_denied" -> return SignInPoll.Failed("The sign-in was declined.")
                    else -> null
                }
            }
            DebridServiceId.TORBOX -> {
                val o = http.postJson("${TorBoxService.API}/user/auth/device/token", buildJsonObject { put("device_code", p.getValue("device_code")) }).json()
                val access = o["data"]["access_token"].str
                if (!o["success"].bool || access.isEmpty()) null else {
                    val me = http.get("${TorBoxService.API}/user/me", access).json()["data"]
                    val user = me["email"].str.ifEmpty { me["customer"].str }
                    mapOf("torboxtoken" to access, "torbox.username" to user) to user
                }
            }
        }
        // A network blip or "not yet": keep waiting until the code expires.
        done ?: return SignInPoll.Waiting
        acc.save(code.service, done.first)
        store.forget(code.service)
        core?.warm()
        PlaybackLog.w("Accounts: ${code.service.displayName} linked")
        return SignInPoll.Done(done.second?.ifEmpty { null })
    }

    override suspend fun linkPremiumizeKey(key: String): SignInPoll {
        val k = key.trim().filter { !it.isWhitespace() }
        if (k.length !in 8..64) return SignInPoll.Failed("That doesn't look like a key. Check it and try again.")
        val r = http.get("${PremiumizeService.API}/account/info?apikey=${DebridHttp.enc(k)}")
        if (r.code == 0) return SignInPoll.Failed("Premiumize didn't answer. Check the connection and try again.")
        val o = r.json()
        if (o["status"].str != "success") return SignInPoll.Failed("Premiumize didn't accept that key. Check it and try again.")
        val user = o["customer_id"].str
        acc.save(DebridServiceId.PREMIUMIZE, mapOf("premiumizetoken" to DebridHttp.API_KEY_PREFIX + k, "premiumizeusername" to user))
        return SignInPoll.Done(user.ifEmpty { null })
    }

    override fun unlink(service: DebridServiceId) { acc.unlink(service); store.forget(service) }

    override fun setOrder(order: List<DebridServiceId>) = acc.setOrder(order)

    // ---- health ----

    /** The last checks, one small JSON per service. */
    private inner class HealthStore {
        fun last(s: DebridServiceId): AccountHealth? = try {
            healthStore.get("health.${s.key}")?.let { Json.decodeFromString(AccountHealth.serializer(), it) }
        } catch (e: Exception) { null }
        fun put(h: AccountHealth) = healthStore.put("health.${h.service.key}", Json.encodeToString(AccountHealth.serializer(), h))
        fun forget(s: DebridServiceId) = healthStore.remove("health.${s.key}")
    }

    override fun lastHealth(service: DebridServiceId) = store.last(service)

    override suspend fun checkHealth(service: DebridServiceId): AccountHealth? {
        val t = acc.token(service) ?: return null
        val now = wall()
        fun h(x: AccountHealthState, premium: Boolean? = null, until: Long = 0, user: String? = null, subscribed: Boolean = false) =
            AccountHealth(service, x, now, premium, until, user, subscribed)
        val info = when (service) {
            DebridServiceId.ALLDEBRID -> {
                val r = http.get("${AllDebridService.API}user?agent=${AllDebridService.AGENT}", t)
                val j = r.json()
                val u = j["data"]["user"]
                val code = j["error"]["code"].str
                when {
                    r.code == 0 -> h(AccountHealthState.OFFLINE)
                    j["status"].str == "success" && u != null -> {
                        val limited = AllDebridService.limited(kit.ledger, t, now)
                        if (core != null && core.adState.blockedScope.isNotEmpty()) core.adState.blockedScope = ""
                        h(if (limited) AccountHealthState.LIMIT else AccountHealthState.OK, if (u["isPremium"] != null) u["isPremium"].bool else true,
                            u["premiumUntil"].long * 1000, u["username"].str.ifEmpty { null }, u["isSubscribed"].bool)
                    }
                    code == "AUTH_BLOCKED" -> h(AccountHealthState.BLOCKED)
                    code.startsWith("AUTH_") || code.startsWith("ACCOUNT_") -> h(AccountHealthState.INVALID)
                    else -> h(AccountHealthState.OFFLINE)
                }
            }
            DebridServiceId.PREMIUMIZE -> {
                val r = http.get("${PremiumizeService.API}/account/info", t)
                val j = r.json()
                when {
                    r.ok && j["status"].str == "success" -> { val until = j["premium_until"].long * 1000; h(AccountHealthState.OK, until > now, until, j["customer_id"].str.ifEmpty { null }) }
                    r.code == 401 || r.code == 403 || j["message"].str.contains("auth", true) -> h(AccountHealthState.INVALID)
                    else -> h(AccountHealthState.OFFLINE)
                }
            }
            DebridServiceId.TORBOX -> {
                val r = http.get("${TorBoxService.API}/user/me", t)
                when {
                    r.code == 401 || r.code == 403 -> h(AccountHealthState.INVALID)
                    r.ok && r.json()["success"].bool -> {
                        val d = r.json()["data"]
                        val until = iso(d["premium_expires_at"].str)
                        val plan = if (d["plan"] != null) d["plan"].int else -1
                        val prem = when { plan == 0 -> false; until > 0 -> until > now; plan > 0 -> true; else -> null }
                        h(AccountHealthState.OK, prem, until, d["email"].str.ifEmpty { null }, d["is_subscribed"].bool)
                    }
                    else -> h(AccountHealthState.OFFLINE)
                }
            }
            DebridServiceId.REAL_DEBRID -> {
                suspend fun read(tok: String): AccountHealth? {
                    val r = http.get("${RealDebridService.REST}user", tok)
                    if (!r.ok) return null
                    val j = r.json()
                    // No expiration date: the seconds of premium left.
                    val until = iso(j["expiration"].str).takeIf { it > 0 } ?: j["premium"].long.takeIf { it > 0 }?.let { now + it * 1000 } ?: 0
                    return h(AccountHealthState.OK, j["type"].str == "premium", until, j["username"].str.ifEmpty { null })
                }
                val r = http.get("${RealDebridService.REST}user", t)
                if (r.ok) read(t) ?: h(AccountHealthState.OFFLINE)
                else if (r.code != 401) h(if (r.code == 403) AccountHealthState.INVALID else AccountHealthState.OFFLINE)
                else {
                    val (code, access, _) = renew(kit, timeoutMs = 6_000)
                    if (access == null) h(if (code == RealDebridService.NO_CREDENTIALS || code in 400..499) AccountHealthState.INVALID else AccountHealthState.OFFLINE)
                    else read(access) ?: h(AccountHealthState.OFFLINE)
                }
            }
        }
        // Keep the last known plan when this answer didn't carry one.
        val prev = store.last(service)
        val kept = if ((info.health == AccountHealthState.OFFLINE || info.health == AccountHealthState.BLOCKED) && prev != null)
            info.copy(premium = prev.premium, until = prev.until, user = prev.user, subscribed = prev.subscribed) else info
        store.put(kept)
        PlaybackLog.w("Streaming accounts: ${service.key.uppercase()} ${kept.health}" + (if (kept.until > 0) ", plan ${(kept.until - now) / DAY} days left" else "") +
            (if (kept.premium == false) ", no premium" else ""))
        return kept
    }

    /** The plan line and its tone: "Premium · 214 Days Left", "Expires in 3 Days" (amber), "Expired" (red). */
    fun planText(i: AccountHealth?): Pair<String, PillTone>? {
        i ?: return null
        val now = wall()
        if (i.premium == false || i.until in 1 until now) return "Expired" to PillTone.BAD
        if (i.until <= 0) return if (i.premium == true) "Premium" to PillTone.PLAIN else null
        if (i.subscribed) return "Premium · Renews Automatically" to PillTone.PLAIN
        val days = ((i.until - now + DAY - 1) / DAY).toInt()
        return when {
            days <= 1 && i.until - now < DAY / 2 -> "Expires Today" to PillTone.WARN
            days <= 1 -> "Expires Tomorrow" to PillTone.WARN
            days <= 7 -> "Expires in $days Days" to PillTone.WARN
            else -> "Premium · $days Days Left" to PillTone.PLAIN
        }
    }

    override fun summary(service: DebridServiceId): AccountSummary {
        val tok = acc.token(service) ?: return AccountSummary(service, false, "Not Linked", PillTone.PLAIN, null)
        val i = store.last(service)
        val limited = service == DebridServiceId.ALLDEBRID && AllDebridService.limited(kit.ledger, tok, wall())
        val blocked = service == DebridServiceId.ALLDEBRID && core?.allDebridBlocked() == true
        val plan = planText(i)
        val (text, tone) = when {
            i?.health == AccountHealthState.INVALID -> "Sign-In Invalid" to PillTone.BAD
            i?.health == AccountHealthState.BLOCKED || blocked -> AccountHealthState.BLOCKED.label to PillTone.BAD
            plan?.second == PillTone.BAD -> plan
            limited -> "Magnet Limit Reached" to PillTone.WARN
            plan != null -> plan
            else -> "Linked" to PillTone.PLAIN
        }
        return AccountSummary(service, true, text, tone, i?.user ?: acc.user(service))
    }

    /** "Checked Just Now", "Checked 5 Min Ago", "Checked 2 Hours Ago". */
    fun ago(at: Long): String {
        val m = ((wall() - at) / 60_000).coerceAtLeast(0)
        return when {
            m < 1 -> "Checked Just Now"
            m < 60 -> "Checked $m Min Ago"
            m < 48 * 60 -> "Checked ${m / 60} Hour${if (m / 60 == 1L) "" else "s"} Ago"
            else -> "Checked ${m / 1440} Days Ago"
        }
    }

    /** Why someone might add [service] (Add a Service). */
    fun reason(service: DebridServiceId) = when (service) {
        DebridServiceId.PREMIUMIZE -> "Lookups Add Nothing to Your Account"
        DebridServiceId.TORBOX -> "Big Cache · Lookups Add Nothing to Your Account"
        DebridServiceId.REAL_DEBRID -> "Largest Cache"
        DebridServiceId.ALLDEBRID -> "Fast Links · Lookups Use Magnet Slots"
    }

    /** True when the kept check is older than [STALE_MS] (the page re-checks when it opens). */
    fun stale(service: DebridServiceId) = store.last(service)?.let { wall() - it.at > STALE_MS } ?: true
}
