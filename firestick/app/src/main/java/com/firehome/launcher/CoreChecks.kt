package com.firehome.launcher

import android.content.Context
import android.util.Log

/**
 * System Health's Dream Core checks: the linked debrid account (one cheap account call to the service
 * the resolver asks first, so a revoked key or a lapsed plan shows here, not as a failed play) and the
 * user's scraper sources. Plain facts, one row each. Blocking (one short HTTPS call, cached [CORE_TTL]).
 */
object CoreChecks {
    private const val TAG = "FireHome"

    enum class State { OK, FIX, INFO }

    class Check(val key: String, val title: String, val state: State, val detail: String)

    const val DREAM_CORE = "dreamCore"
    const val SCRAPERS = "scrapers"
    /** Health pages refresh often; the account is asked at most this often. */
    private const val CORE_TTL = 60_000L
    /** The account check only (network); the scrapers line is read fresh each time (local, cheap). */
    @Volatile private var coreCache: Triple<Long, String, Check>? = null

    fun dreamCore(ctx: Context): List<Check> {
        val app = ctx.applicationContext
        val now = android.os.SystemClock.elapsedRealtime()
        val acc = DebridAccounts.get(app)
        // The linked account changed: asked again at once, whatever the cache holds.
        val key = "${listOf(acc?.allDebrid, acc?.premiumize, acc?.torBox, acc?.realDebrid).hashCode()}"
        val core = coreCache?.takeIf { (at, k, _) -> k == key && now - at < CORE_TTL }?.third
            ?: (if (acc?.any != true) noAccount() else account(app, acc)).also { coreCache = Triple(now, key, it) }
        return listOf(core, scrapers(app))
    }

    private const val TITLE = "Dream Core Resolver"

    private fun noAccount() = Check(DREAM_CORE, TITLE, State.FIX, "No Debrid Service Linked · Add One in Settings › Accounts")

    /** One service's account call; [read] gives true (signed in, plan active), false (refused, no plan) or null (no answer to judge by). */
    private class Service(val name: String, val url: String, val token: String, val read: (DebridHttp.Reply) -> Boolean?)

    private fun account(app: Context, acc: DebridAccounts.Accounts): Check {
        // The resolver's own order: the first linked service is the one every play asks first.
        var blocked = false
        val s = listOfNotNull(
            acc.allDebrid?.let { t -> Service("AllDebrid", "${AllDebridService.API}user?agent=${AllDebridService.AGENT}", t) { r ->
                val j = r.json()
                // AUTH_BLOCKED: the sign-in is fine; AllDebrid waits for its owner to approve this network.
                blocked = j.optJSONObject("error")?.optString("code") == "AUTH_BLOCKED"
                if (j.optString("status") == "success") j.optJSONObject("data")?.optJSONObject("user")?.optBoolean("isPremium", true) ?: true
                else if (j.optJSONObject("error")?.optString("code").orEmpty().let { it.startsWith("AUTH_") || it.startsWith("ACCOUNT_") || it == "MUST_BE_PREMIUM" }) false
                else null
            } },
            acc.premiumize?.let { t -> Service("Premiumize", "https://www.premiumize.me/api/account/info", t) { r ->
                val j = r.json()
                if (j.optString("status") == "success") !j.has("premium_until") || j.optLong("premium_until") > System.currentTimeMillis() / 1000
                else if (r.code == 401 || j.optString("message").contains("auth", true)) false else null
            } },
            acc.torBox?.let { t -> Service("TorBox", "https://api.torbox.app/v1/api/user/me", t) { r ->
                if (r.code == 401 || r.code == 403) false else if (r.ok && r.json().optBoolean("success")) true else null
            } },
            // Real-Debrid's access token expires every few hours and the resolver renews it on the next
            // play, so a 401 here isn't a broken account: only a reply that names the account counts.
            acc.realDebrid?.let { t -> Service("Real-Debrid", "https://api.real-debrid.com/rest/1.0/user", t) { r ->
                if (r.ok) r.json().optString("type") == "premium" else null
            } },
        ).firstOrNull() ?: return noAccount()
        val ok = try { s.read(DebridHttp.get(s.url, s.token, timeoutMs = 4_000)) } catch (e: Exception) { null }
        Log.w(TAG, "System health: Dream Core account (${s.name}) ${when (ok) { true -> "valid"; false -> if (blocked) "waiting for network approval" else "refused"; null -> "not checked" }}")
        if (ok == false && blocked) return Check(DREAM_CORE, TITLE, State.FIX,
            "${s.name} is waiting for you to approve this network. Open the email from ${s.name} and approve this location.")
        return when (ok) {
            true -> Check(DREAM_CORE, TITLE, State.OK, "On · ${s.name} Account Active" +
                (DebridExpiry.caption(app, when (s.name) { "AllDebrid" -> "ad"; "Premiumize" -> "pm"; "TorBox" -> "tb"; else -> "rd" })?.let { " · $it" } ?: ""))
            false -> Check(DREAM_CORE, TITLE, State.FIX, "${s.name} didn't accept the saved sign-in, or its plan has ended. Link the account again in Accounts.")
            null -> Check(DREAM_CORE, TITLE, State.INFO, "On · ${s.name} · Account Check Unavailable Right Now")
        }
    }

    /** The user's scraper sources: installed, with providers on (no network). */
    private fun scrapers(app: Context): Check {
        val st = try { Scrapers.status(app) } catch (e: Exception) { null }
        val on = st?.providers?.count { it.enabled } ?: 0
        return when {
            st == null || st.repos.isEmpty() -> Check(SCRAPERS, "Scrapers", State.FIX, "No Scraper Repository Added · Add One in Settings › Scrapers")
            on == 0 -> Check(SCRAPERS, "Scrapers", State.FIX, "Every Provider Is Off · Turn Some On in Settings › Scrapers")
            else -> Check(SCRAPERS, "Scrapers", State.OK, "${st.repos.size} Source${if (st.repos.size == 1) "" else "s"} · $on Provider${if (on == 1) "" else "s"} On")
        }
    }
}
