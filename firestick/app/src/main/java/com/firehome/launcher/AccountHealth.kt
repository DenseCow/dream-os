package com.firehome.launcher

import android.content.Context
import android.graphics.Color
import android.util.Log
import org.json.JSONObject

/**
 * The health of each linked debrid account, for Settings › Streaming Accounts: one cheap, read-only
 * account call per service ([check]: the service's "who am I" endpoint, never a magnet upload) gives
 * whether the saved sign-in still works and when the premium plan ends. The last result is kept
 * (app-private prefs), so the page shows it at once and only re-asks when it's old or on Test Connection.
 *
 * AllDebrid's magnet limit is the resolver's own record (DebridLedger, "limit.<scope>" in
 * dream_debrid_ledger), read straight from its prefs here (read only).
 *
 * Tokens are never logged.
 */
internal object AccountHealth {
    private const val TAG = "FireHome"
    private const val PREFS = "dream_account_health"
    private const val DAY = 86_400_000L
    /** Results older than this are re-checked when the page opens. */
    const val STALE_MS = 30 * 60_000L

    /** BLOCKED: AllDebrid's AUTH_BLOCKED, a sign-in from a new location waiting for the owner's approval. */
    enum class Health { OK, LIMIT, INVALID, OFFLINE, BLOCKED }

    /** What to do about [Health.BLOCKED], in the same words on the setup's check, Accounts and Settings. */
    fun blockedLine(name: String) = "$name blocked this new location. Approve it from the email $name sent you " +
        "(or the security page of your $name account), then choose Check Again."

    /** [premium]: true/false when the service said, null when unknown; [until]: plan end (wall ms), 0 when unknown or none. */
    class Info(val service: String, val health: Health, val at: Long, val premium: Boolean?, val until: Long, val user: String?) {
        fun json(): JSONObject = JSONObject().put("h", health.name).put("at", at).put("p", when (premium) { true -> 1; false -> 0; null -> -1 })
            .put("u", until).put("n", user ?: "")
        companion object {
            fun from(s: String, o: JSONObject) = Info(s, try { Health.valueOf(o.optString("h")) } catch (e: Exception) { Health.OFFLINE },
                o.optLong("at"), when (o.optInt("p", -1)) { 1 -> true; 0 -> false; else -> null }, o.optLong("u"), o.optString("n").ifEmpty { null })
        }
    }

    /** The status colors' light text tints (STANDARDS §2: amber #FFD60A, red #FF453A). */
    val AMBER = Color.parseColor("#FFE266")
    val RED = Color.parseColor("#FF8178")

    val NAMES = mapOf("ad" to "AllDebrid", "pm" to "Premiumize", "tb" to "TorBox", "rd" to "Real-Debrid")
    fun name(service: String) = NAMES[service] ?: service.uppercase()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun last(ctx: Context, service: String): Info? = try {
        prefs(ctx).getString(service, null)?.let { Info.from(service, JSONObject(it)) }
    } catch (e: Exception) { null }

    fun forget(ctx: Context, service: String) = prefs(ctx).edit().remove(service).apply()

    fun stale(ctx: Context, service: String) = last(ctx, service)?.let { System.currentTimeMillis() - it.at > STALE_MS } ?: true

    // ---- the magnet limit (the resolver's record) ----

    /** AllDebrid is resting at its magnet limit: until when (wall ms), or 0. */
    fun limitedUntil(ctx: Context, service: String): Long {
        if (service != "ad") return 0
        val token = DebridAccounts.get(ctx.applicationContext)?.allDebrid ?: return 0
        return try {
            val raw = ctx.applicationContext.getSharedPreferences("dream_debrid_ledger", Context.MODE_PRIVATE)
                .getString("limit." + DebridLedger.scope("ad", token), null) ?: return 0
            JSONObject(raw).optLong("until").takeIf { it > System.currentTimeMillis() } ?: 0
        } catch (e: Exception) { 0 }
    }

    // ---- the check ----

    private fun token(acc: DebridAccounts.Accounts, s: String) = when (s) {
        "ad" -> acc.allDebrid; "pm" -> acc.premiumize; "tb" -> acc.torBox; "rd" -> acc.realDebrid; else -> null
    }

    /** One read-only account call for [service] (Test Connection). Blocking; saved and returned; null when it isn't linked. */
    fun check(ctx: Context, service: String): Info? {
        val app = ctx.applicationContext
        DebridAccounts.invalidate() // a link or unlink a moment ago counts
        val acc = DebridAccounts.get(app) ?: return null
        val t = token(acc, service)?.takeIf { it.isNotBlank() } ?: return null
        val now = System.currentTimeMillis()
        val info = try {
            when (service) {
                "ad" -> {
                    val r = DebridHttp.get("${AllDebridService.API}user?agent=${AllDebridService.AGENT}", t, 6_000)
                    val j = r.json()
                    val u = j.optJSONObject("data")?.optJSONObject("user")
                    when {
                        j.optString("status") == "success" && u != null -> {
                            val prem = u.optBoolean("isPremium", true)
                            val until = u.optLong("premiumUntil") * 1000L
                            val h = if (limitedUntil(app, "ad") > 0) Health.LIMIT else Health.OK
                            Info(service, h, now, prem, until, u.optString("username").ifEmpty { null })
                        }
                        j.optJSONObject("error")?.optString("code") == "AUTH_BLOCKED" -> Info(service, Health.BLOCKED, now, null, 0, null)
                        j.optJSONObject("error")?.optString("code").orEmpty().let { it.startsWith("AUTH_") || it.startsWith("ACCOUNT_") } ->
                            Info(service, Health.INVALID, now, null, 0, null)
                        else -> Info(service, Health.OFFLINE, now, null, 0, null)
                    }
                }
                "pm" -> {
                    val r = DebridHttp.get("https://www.premiumize.me/api/account/info", t, 6_000)
                    val j = r.json()
                    when {
                        r.ok && j.optString("status") == "success" -> {
                            val until = j.optLong("premium_until") * 1000L
                            Info(service, Health.OK, now, until > now, until, j.opt("customer_id")?.toString()?.ifEmpty { null })
                        }
                        r.code == 401 || r.code == 403 || j.optString("message").contains("auth", true) -> Info(service, Health.INVALID, now, null, 0, null)
                        else -> Info(service, Health.OFFLINE, now, null, 0, null)
                    }
                }
                "tb" -> {
                    val r = DebridHttp.get("https://api.torbox.app/v1/api/user/me", t, 6_000)
                    when {
                        r.code == 401 || r.code == 403 -> Info(service, Health.INVALID, now, null, 0, null)
                        r.ok && r.json().optBoolean("success") -> {
                            val d = r.json().optJSONObject("data")
                            val until = iso(d?.optString("premium_expires_at"))
                            val plan = d?.optInt("plan", -1) ?: -1
                            val prem = when { plan == 0 -> false; until > 0 -> until > now; plan > 0 -> true; else -> null }
                            Info(service, Health.OK, now, prem, until, d?.optString("email")?.ifEmpty { null })
                        }
                        else -> Info(service, Health.OFFLINE, now, null, 0, null)
                    }
                }
                "rd" -> realDebrid(app, acc, t, now)
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Streaming accounts: ${service.uppercase()} check failed: ${e.javaClass.simpleName}")
            Info(service, Health.OFFLINE, now, null, 0, null)
        } ?: return null
        // Keep the last known plan when this answer didn't carry one (e.g. no answer at all).
        val prev = last(app, service)
        val kept = if ((info.health == Health.OFFLINE || info.health == Health.BLOCKED) && prev != null) Info(service, info.health, now, prev.premium, prev.until, prev.user) else info
        prefs(app).edit().putString(service, kept.json().toString()).apply()
        if (kept.health != Health.OFFLINE) DebridExpiry.markBlocked(app, service, kept.health == Health.BLOCKED)
        Log.w(TAG, "Streaming accounts: ${service.uppercase()} ${kept.health}" +
            (if (kept.until > 0) ", plan ${((kept.until - now) / DAY)} days left" else "") + (if (kept.premium == false) ", no premium" else ""))
        return kept
    }

    /** Real-Debrid's access token lasts hours: on a 401 it is refreshed once with the user's own device credentials (saved in Dream OS). */
    private fun realDebrid(app: Context, acc: DebridAccounts.Accounts, token: String, now: Long): Info {
        fun read(r: DebridHttp.Reply): Info? {
            if (!r.ok) return null
            val j = r.json()
            val until = iso(j.optString("expiration"))
            return Info("rd", Health.OK, now, j.optString("type") == "premium", until, j.optString("username").ifEmpty { null })
        }
        val url = "https://api.real-debrid.com/rest/1.0/user"
        val r = DebridHttp.get(url, token, 6_000)
        read(r)?.let { return it }
        if (r.code != 401) return Info("rd", if (r.code == 403) Health.INVALID else Health.OFFLINE, now, null, 0, null)
        val t = RealDebridToken.renew(acc, timeoutMs = 6_000)
        val access = t.access ?: return Info("rd",
            if (t.code == RealDebridToken.NO_CREDENTIALS || t.code in 400..499) Health.INVALID else Health.OFFLINE, now, null, 0, null)
        Log.w(TAG, "Streaming accounts: Real-Debrid sign-in renewed")
        return read(DebridHttp.get(url, access, 6_000)) ?: Info("rd", Health.OFFLINE, now, null, 0, null)
    }

    /** The services' ISO 8601 dates, offsets included ([ExpiryRules.iso]); 0 when missing. */
    private fun iso(s: String?): Long = ExpiryRules.iso(s.orEmpty())

    // ---- words ----

    /** The plan line and its tone (null = the normal color): "Premium · 214 Days Left", "Expires in 3 Days" (amber), "Expired" (red). */
    fun premiumText(i: Info?): Pair<String, Int?>? {
        if (i == null) return null
        val now = System.currentTimeMillis()
        if (i.premium == false || (i.until in 1 until now)) return "Expired" to RED
        if (i.until <= 0) return if (i.premium == true) "Premium" to null else null
        val days = ((i.until - now + DAY - 1) / DAY).toInt()
        return when {
            days <= 1 && i.until - now < DAY / 2 -> "Expires Today" to AMBER
            days <= 1 -> "Expires Tomorrow" to AMBER
            days <= 7 -> "Expires in $days Days" to AMBER
            else -> "Premium · $days Days Left" to null
        }
    }

    /**
     * The plan line for [service]: the premium check ([DebridExpiry], the notifications' twice-a-day
     * round) or this page's own Test Connection, whichever answered last.
     */
    fun plan(ctx: Context, service: String): Pair<String, Int?>? {
        val mine = last(ctx, service)
        val e = try { DebridExpiry.status(ctx, service) } catch (x: Exception) { null }
        if (e == null || (mine != null && mine.premium != null && mine.at > e.checkedAt)) return premiumText(mine)
        if (e.expired) return "Expired" to RED
        if (e.until <= 0) return if (e.premium) "Premium" to null else null
        val d = e.daysLeft()
        return when {
            e.subscribed && d <= 7 -> (if (d <= 0) "Renews Today" else if (d == 1) "Renews Tomorrow" else "Renews in $d Days") to null
            d <= 0 -> "Expires Today" to AMBER
            d == 1 -> "Expires Tomorrow" to AMBER
            d <= 7 -> "Expires in $d Days" to AMBER
            else -> "Premium · $d Days Left" to null
        }
    }

    fun healthText(h: Health) = when (h) {
        Health.OK -> "Connection OK"
        Health.LIMIT -> "Magnet Limit Reached"
        Health.INVALID -> "Sign-In Invalid"
        Health.OFFLINE -> "No Answer Right Now"
        Health.BLOCKED -> "Approve New Location"
    }

    fun healthTone(h: Health): Int? = when (h) { Health.OK -> null; Health.LIMIT, Health.OFFLINE -> AMBER; Health.INVALID, Health.BLOCKED -> RED }

    fun ago(at: Long): String {
        val m = ((System.currentTimeMillis() - at) / 60_000).coerceAtLeast(0)
        return when {
            m < 1 -> "Checked Just Now"
            m < 60 -> "Checked $m Min Ago"
            m < 48 * 60 -> "Checked ${m / 60} Hour${if (m / 60 == 1L) "" else "s"} Ago"
            else -> "Checked ${m / 1440} Days Ago"
        }
    }

    /**
     * The short value a service row shows on the right, most important first, with its tone:
     * Sign-In Invalid / Expired (red), Magnet Limit Reached / Expires in N Days (amber), else the plan.
     */
    fun summary(ctx: Context, service: String): Pair<String, Int?> {
        if (!DreamAccounts.linked(ctx, service)) return "Not Linked" to null
        val i = last(ctx, service)
        val limited = limitedUntil(ctx, service) > 0
        val prem = plan(ctx, service)
        return when {
            i?.health == Health.INVALID -> "Sign-In Invalid" to RED
            i?.health == Health.BLOCKED || DebridExpiry.blocked(ctx, service) -> "Approve New Location" to RED
            prem?.second == RED -> prem
            limited -> "Magnet Limit Reached" to AMBER
            prem != null -> prem
            else -> "Linked" to null
        }
    }

    /** Why someone might add [service] (Add a Service). */
    fun reason(service: String) = when (service) {
        "pm" -> "Lookups Add Nothing to Your Account"
        "tb" -> "Big Cache · Lookups Add Nothing to Your Account"
        "rd" -> "Largest Cache"
        "ad" -> "Fast Links · Lookups Use Magnet Slots"
        else -> ""
    }
}
