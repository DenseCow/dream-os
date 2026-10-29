package com.firehome.launcher

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * When each linked debrid account's premium ends, read from the service's own account endpoint, and
 * the notices that follow (DreamNotices): a warning at 7, 3 and 1 days left, a critical one once it
 * has ended. Each fires once per threshold per expiry date, so a renewal (a new date) starts over;
 * a renewal also clears that service's older warnings. An auto-renewing subscription (AllDebrid,
 * TorBox report it) gets no countdown, only the expired notice should a renewal fail.
 *
 * Fields (each service's official API):
 *  - AllDebrid   GET api.alldebrid.com/v4/user        data.user.premiumUntil (unix s, 0 = none), isPremium, isSubscribed
 *  - Real-Debrid GET api.real-debrid.com/rest/1.0/user  expiration (ISO 8601), premium (s left), type ("premium" | "free")
 *  - Premiumize  GET www.premiumize.me/api/account/info premium_until (unix s, null = free)
 *  - TorBox      GET api.torbox.app/v1/api/user/me      data.premium_expires_at (ISO 8601), plan (0 = free), is_subscribed
 *
 * Checked on app start, then at most every [EVERY_MS] (Home's resume and clock tick ask, [maybeCheck]),
 * and at once when the linked accounts change. Results are kept ([status]) for the Accounts screen
 * and System Health. Tokens are only ever sent to their own service; never logged or stored here.
 */
object DebridExpiry {
    private const val TAG = "FireHome"
    private const val PREFS = "dream_expiry"
    private const val LAST = "_last"
    private const val FP = "_fp"
    const val EVERY_MS = 6 * 3_600_000L
    /** A failed round (offline at boot) is tried again after this long instead of six hours. */
    private const val RETRY_MS = 20 * 60_000L
    private const val DAY = 86_400_000L
    val SERVICES = listOf("ad", "pm", "tb", "rd")

    /** Renewal pages, shown as a QR code by the notice's action. */
    fun renewUrl(service: String) = when (service) {
        "ad" -> "https://alldebrid.com/offer/"
        "pm" -> "https://www.premiumize.me/premium"
        "tb" -> "https://torbox.app/pricing"
        "rd" -> "https://real-debrid.com/premium"
        else -> "https://alldebrid.com/offer/"
    }

    /** What a service said, last time. [until] ms since the epoch (0 = no date given). */
    class Status(val service: String, val until: Long, val premium: Boolean, val subscribed: Boolean, val checkedAt: Long) {
        fun daysLeft(now: Long = System.currentTimeMillis()) = ExpiryRules.daysLeft(now, until, DreamTime.zone())
        val expired get() = ExpiryRules.level(System.currentTimeMillis(), until, premium, subscribed, DreamTime.zone()) == ExpiryRules.Level.EXPIRED
    }

    @Volatile private var running = false
    @Volatile private var lastAttempt = 0L
    /** The first ask in this process always checks (app start). */
    @Volatile private var startChecked = false
    /** The startup account summary is still due in this process (once per boot, after its first round). */
    @Volatile private var summaryDue = true

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** AllDebrid's AUTH_BLOCKED: the account waits for its owner to approve this new location. */
    private class Blocked : Exception()
    private const val BLOCKED = "blocked_"

    /** The last round found [service] blocking this location (cleared by its next good answer). */
    fun blocked(ctx: Context, service: String) = prefs(ctx).getLong(BLOCKED + service, 0L) > 0

    /** The account check ([AccountHealth.check]) heard the same answer, or a good one. */
    fun markBlocked(ctx: Context, service: String, on: Boolean) {
        if (on) prefs(ctx).edit().putLong(BLOCKED + service, System.currentTimeMillis()).apply()
        else if (blocked(ctx, service)) prefs(ctx).edit().remove(BLOCKED + service).apply()
    }

    fun status(ctx: Context, service: String): Status? = try {
        prefs(ctx).getString(service, null)?.let { JSONObject(it) }?.let {
            Status(service, it.optLong("until"), it.optBoolean("premium"), it.optBoolean("sub"), it.optLong("at"))
        }
    } catch (e: Exception) { null }

    /**
     * One short line for a linked service's row: "Premium Until Jan 10, 2027 · 77 Days Left",
     * "Premium Ends Tomorrow", "Premium Expired"; null when nothing is known yet.
     */
    fun caption(ctx: Context, service: String): String? {
        if (blocked(ctx, service)) return "Approve New Location"
        val s = status(ctx, service) ?: return null
        if (s.expired) return if (s.until > 0) "Premium Expired ${dateText(s.until, year = true)}" else "No Premium Plan"
        if (s.until <= 0) return if (s.premium) "Premium Active" else null
        val d = s.daysLeft()
        val left = when (d) { 0 -> "Ends Today"; 1 -> "Ends Tomorrow"; else -> "$d Days Left" }
        return if (s.subscribed && d > 7) "Premium Renews ${dateText(s.until, year = true)}"
            else "Premium Until ${dateText(s.until, year = true)} · $left"
    }

    /** Settings' line: each linked service with what is known ("AllDebrid · Premium Until Jan 10, 2027 · 77 Days Left"). */
    fun summary(ctx: Context): String {
        val acc = DebridAccounts.get(ctx) ?: return "No Debrid Account Linked"
        val parts = SERVICES.filter { tokenOf(acc, it) != null }.map { svc ->
            "${DreamNotices.serviceName(svc)} · ${caption(ctx, svc) ?: "Not Checked Yet"}"
        }
        return if (parts.isEmpty()) "No Debrid Account Linked" else parts.joinToString("   ")
    }

    /** "Tue, Nov 3". */
    fun shortDate(at: Long): String = DreamTime.fmt("EEE, MMM d").format(Date(at))

    /** "Tuesday, November 3" (or "Nov 3, 2027" with [year]). */
    fun dateText(at: Long, year: Boolean = false): String =
        DreamTime.fmt(if (year) "MMM d, yyyy" else "EEEE, MMMM d").format(Date(at))

    /** Which accounts are linked. Real-Debrid counts by its device client id: its access token changes on every refresh. */
    private fun fingerprint(acc: DebridAccounts.Accounts?): String {
        val rd = acc?.realDebrid?.let { acc.realDebridClientId.ifBlank { it } }
        return Integer.toHexString(listOf(acc?.allDebrid, acc?.premiumize, acc?.torBox, rd).map { it?.hashCode() ?: 0 }.hashCode())
    }

    /**
     * Cheap; call freely from the main thread (Home's resume, the clock tick). Starts a background round
     * when the last one is [EVERY_MS] old, or the linked accounts changed since.
     */
    fun maybeCheck(ctx: Context) {
        val app = ctx.applicationContext
        if (running) return
        val now = System.currentTimeMillis()
        if (now - lastAttempt < 60_000) return
        lastAttempt = now
        val first = !startChecked
        startChecked = true
        Net.run {
            val p = prefs(app)
            val acc = DebridAccounts.get(app)
            val fp = fingerprint(acc)
            val last = p.getLong(LAST, 0)
            val due = first || now - last >= EVERY_MS || p.getString(FP, null) != fp
            pollLimits(app, acc)
            if (due) checkBlocking(app)
        }
    }

    /** An account was just linked or unlinked: check now (background). */
    fun accountsChanged(ctx: Context) {
        val app = ctx.applicationContext
        Net.run { checkBlocking(app) }
    }

    /** Settings' "Check Now": a round right away; [done] (main thread) gets how many services answered. */
    fun checkNow(ctx: Context, done: (Int) -> Unit) {
        val app = ctx.applicationContext
        Net.background({ checkBlocking(app) }) { n -> done(n ?: 0) }
    }

    /** Blocking. Asks every linked service, saves what each said, posts the notices due. Returns how many answered. */
    @Synchronized fun checkBlocking(ctx: Context): Int {
        val app = ctx.applicationContext
        running = true
        lastAttempt = System.currentTimeMillis()
        try {
            DebridAccounts.invalidate()
            val acc = DebridAccounts.get(app) ?: return 0
            val p = prefs(app)
            val tokens = mapOf("ad" to acc.allDebrid, "pm" to acc.premiumize, "tb" to acc.torBox, "rd" to acc.realDebrid)
            var asked = 0
            var answered = 0
            var warned = false
            for (svc in SERVICES) {
                val token = tokens[svc]
                if (token.isNullOrBlank()) {
                    // Disconnected: its plan, its location block and its expiry notices go with it.
                    if (p.contains(svc) || p.contains(BLOCKED + svc)) {
                        p.edit().remove(svc).remove(BLOCKED + svc).apply()
                        DreamNotices.dismissPrefix(app, "expiry:$svc:")
                    }
                    continue
                }
                asked++
                val s = try { fetch(svc, token, acc) } catch (e: Blocked) {
                    p.edit().putLong(BLOCKED + svc, System.currentTimeMillis()).apply(); null
                } catch (e: Exception) {
                    Log.w(TAG, "Premium check: ${DreamNotices.serviceName(svc)} failed: ${e.javaClass.simpleName}"); null
                } ?: continue
                answered++
                p.edit().remove(BLOCKED + svc).apply()
                p.edit().putString(svc, JSONObject().put("until", s.until).put("premium", s.premium).put("sub", s.subscribed).put("at", s.checkedAt).toString()).apply()
                val days = s.daysLeft()
                Log.w(TAG, "Premium check: ${DreamNotices.serviceName(svc)} ${if (s.until > 0) "premium until ${dateText(s.until, true)} ($days days)" else if (s.premium) "premium, no end date" else "no premium"}" +
                    if (s.subscribed) ", renews automatically" else "")
                if (notify(app, s)) warned = true
            }
            // A round where nothing answered (offline) counts as a short retry, not a six-hour wait.
            val now = System.currentTimeMillis()
            p.edit().putLong(LAST, if (asked > 0 && answered == 0) now - EVERY_MS + RETRY_MS else now)
                .putString(FP, fingerprint(acc)).apply()
            if (summaryDue) { summaryDue = false; startupSummary(app, acc, banner = !warned) }
            return answered
        } finally {
            running = false
        }
    }

    /** The notice a status calls for, if any (one per threshold per expiry date). True when one was posted now. */
    private fun notify(ctx: Context, s: Status): Boolean {
        val name = DreamNotices.serviceName(s.service)
        val now = System.currentTimeMillis()
        val level = ExpiryRules.level(now, s.until, s.premium, s.subscribed, DreamTime.zone())
        val prefix = "expiry:${s.service}:"
        if (level == null) {
            // Healthy (renewed, or far off): older warnings for this service are stale.
            DreamNotices.dismissPrefix(ctx, prefix)
            return false
        }
        val key = ExpiryRules.key(s.service, level, s.until)
        if (DreamNotices.find(ctx, key) != null) return false
        val others = SERVICES.filter { it != s.service && DebridAccounts.get(ctx)?.let { a -> tokenOf(a, it) } != null }
            .filter { status(ctx, it)?.expired != true }
        val action = DreamNotices.Action("Renew", "renew:${s.service}")
        val (title, body, sev) = if (level == ExpiryRules.Level.EXPIRED) Triple(
            "$name Premium Expired",
            if (others.isNotEmpty()) "Streaming Through ${others.joinToString(" and ") { DreamNotices.serviceName(it) }} Instead"
                else "Renew or Link Another Service to Keep Streaming",
            DreamNotices.Severity.CRITICAL,
        ) else {
            val d = s.daysLeft(now)
            Triple(
                "$name Premium " + when (d) { 0 -> "Ends Today"; 1 -> "Ends Tomorrow"; else -> "Ends in $d Days" },
                "Renew by ${shortDate(s.until)} to Keep Streaming",
                DreamNotices.Severity.WARNING,
            )
        }
        // One live notice per service: the newer threshold replaces the earlier one.
        for (n in DreamNotices.all(ctx)) if (n.key.startsWith(prefix) && n.key != key) DreamNotices.dismiss(ctx, n.key)
        return DreamNotices.post(ctx, key, DreamNotices.TYPE_EXPIRY, title, body, sev, action)
    }

    /**
     * On every start of Dream OS (each process, updates included: the user asked for it at every start-up),
     * after the first round: "Your Accounts", every linked service with its time left
     * ("Real-Debrid 96 Days · Premiumize 214 Days · AllDebrid 1 Year", soonest first). Uses this round's
     * answers, else a result under [EVERY_MS] old; nothing known: skipped quietly. One entry per day in
     * the list (refreshed on each boot). [banner] off (a warning just took the banner): the list only.
     * Settings › Account Summary at Startup ([Theme.ACCOUNT_SUMMARY]) turns it off.
     */
    private fun startupSummary(ctx: Context, acc: DebridAccounts.Accounts, banner: Boolean) {
        if (Theme.get(ctx, Theme.ACCOUNT_SUMMARY) != 1) return
        val now = System.currentTimeMillis()
        val known = SERVICES.filter { tokenOf(acc, it) != null }
            .mapNotNull { status(ctx, it)?.takeIf { s -> now - s.checkedAt < EVERY_MS } }
        if (known.isEmpty()) return
        // One service per line, "Name: value" (DreamNoticesUi lays them out as a two-column list).
        val parts = known.sortedBy { if (it.until > 0) it.until else Long.MAX_VALUE }.map { s ->
            val left = ExpiryRules.timeLeft(now, s.until, s.premium, s.subscribed, DreamTime.zone())
            "${DreamNotices.serviceName(s.service)}: ${if (s.subscribed && left != "Expired") "Renews Automatically" else left}"
        }
        val day = DreamTime.todayYmd(ctx, now)
        DreamNotices.post(ctx, "summary:$day", DreamNotices.TYPE_SUMMARY, "Your Accounts", parts.joinToString("\n"),
            DreamNotices.Severity.INFO, null, banner = banner, replace = true, store = false)
    }

    private fun tokenOf(a: DebridAccounts.Accounts, svc: String) = when (svc) {
        "ad" -> a.allDebrid; "pm" -> a.premiumize; "tb" -> a.torBox; "rd" -> a.realDebrid; else -> null
    }

    /** Dream Core's resolver marked a service as at its account limit (DebridLedger): the day's warning. */
    private fun pollLimits(ctx: Context, acc: DebridAccounts.Accounts?) {
        acc ?: return
        val now = System.currentTimeMillis()
        for (svc in SERVICES) {
            val t = tokenOf(acc, svc) ?: continue
            val until = try { DebridLedger.limitedUntil(DebridLedger.scope(svc, t)) } catch (e: Exception) { 0L }
            if (until > now) DreamNotices.accountLimit(ctx, svc)
        }
    }

    // ---- the services ----

    private fun fetch(svc: String, token: String, acc: DebridAccounts.Accounts): Status? {
        val now = System.currentTimeMillis()
        return when (svc) {
            "ad" -> {
                val r = DebridHttp.get("${AllDebridService.API}user?agent=${AllDebridService.AGENT}", token, 8_000)
                val j = r.json()
                if (j.optString("status") != "success") {
                    val code = j.optJSONObject("error")?.optString("code").orEmpty()
                    Log.w(TAG, "Premium check: AllDebrid answered ${r.code} $code")
                    if (code == "AUTH_BLOCKED") throw Blocked()
                    return null
                }
                val u = j.optJSONObject("data")?.optJSONObject("user") ?: return null
                Status(svc, u.optLong("premiumUntil") * 1000, u.optBoolean("isPremium"), u.optBoolean("isSubscribed"), now)
            }
            "pm" -> {
                val r = DebridHttp.get("https://www.premiumize.me/api/account/info", token, 8_000)
                val j = r.json()
                if (j.optString("status") != "success") { Log.w(TAG, "Premium check: Premiumize answered ${r.code}"); return null }
                val until = if (j.isNull("premium_until")) 0L else j.optLong("premium_until") * 1000
                Status(svc, until, until > now, false, now)
            }
            "tb" -> {
                val r = DebridHttp.get("https://api.torbox.app/v1/api/user/me", token, 8_000)
                val j = r.json()
                if (!r.ok || !j.optBoolean("success")) { Log.w(TAG, "Premium check: TorBox answered ${r.code} ${j.optString("error")}"); return null }
                val d = j.optJSONObject("data") ?: return null
                val until = if (d.isNull("premium_expires_at")) 0L else ExpiryRules.iso(d.optString("premium_expires_at"))
                Status(svc, until, d.optInt("plan", 0) > 0 && (until == 0L || until > now), d.optBoolean("is_subscribed"), now)
            }
            "rd" -> {
                var r = DebridHttp.get("https://api.real-debrid.com/rest/1.0/user", token, 8_000)
                if (r.code == 401) {
                    // The access token lasts about an hour: renewed with the user's own device credentials, and kept.
                    val fresh = refreshRealDebrid(acc) ?: return null
                    r = DebridHttp.get("https://api.real-debrid.com/rest/1.0/user", fresh, 8_000)
                }
                if (!r.ok) { Log.w(TAG, "Premium check: Real-Debrid answered ${r.code}"); return null }
                val j = r.json()
                val exp = j.optString("expiration")
                var until = if (exp.isNotEmpty()) ExpiryRules.iso(exp) else 0L
                if (until == 0L && j.optLong("premium") > 0) until = now + j.optLong("premium") * 1000
                Status(svc, until, j.optString("type") == "premium", false, now)
            }
            else -> null
        }
    }

    private fun refreshRealDebrid(acc: DebridAccounts.Accounts): String? {
        val r = RealDebridToken.renew(acc, timeoutMs = 8_000)
        if (r.access == null && r.code != RealDebridToken.NO_CREDENTIALS) Log.w(TAG, "Premium check: Real-Debrid sign-in renewal refused (${r.code})")
        return r.access
    }
}

// ---- rules (pure, unit-checked off-device) ----
/** The thresholds, apart from Android so they can be checked with fake dates. */
internal object ExpiryRules {
    enum class Level(val tag: String) { D7("7d"), D3("3d"), D1("1d"), EXPIRED("expired") }

    /** Calendar days from [now]'s date to [until]'s date in [zone] (0 = today, 1 = tomorrow; negative once past). */
    fun daysLeft(now: Long, until: Long, zone: TimeZone): Int {
        fun dayNumber(t: Long): Long {
            val c = Calendar.getInstance(zone).apply { timeInMillis = t }
            val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear(); set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
            }
            return u.timeInMillis / 86_400_000L
        }
        return (dayNumber(until) - dayNumber(now)).toInt()
    }

    /**
     * The notice due: null (nothing: more than 7 days left, an auto-renewing subscription, or nothing
     * known), D7 / D3 / D1 (at most that many days left), EXPIRED (the end has passed, or no premium).
     */
    fun level(now: Long, until: Long, premium: Boolean, subscribed: Boolean, zone: TimeZone): Level? {
        if (until <= 0L) return if (premium) null else Level.EXPIRED
        if (until <= now) return Level.EXPIRED
        if (subscribed) return null
        val d = daysLeft(now, until, zone)
        return when {
            d <= 1 -> Level.D1
            d <= 3 -> Level.D3
            d <= 7 -> Level.D7
            else -> null
        }
    }

    /** "96 Days", "1 Day", "Ends Today", "1 Year", "2 Years", "Expired", "Active" (premium, no end date). */
    fun timeLeft(now: Long, until: Long, premium: Boolean, subscribed: Boolean, zone: TimeZone): String {
        if (level(now, until, premium, subscribed, zone) == Level.EXPIRED) return "Expired"
        if (until <= 0L) return "Active"
        val d = daysLeft(now, until, zone)
        return when {
            d <= 0 -> "Ends Today"
            d == 1 -> "1 Day"
            d < 365 -> "$d Days"
            d < 730 -> "1 Year"
            else -> "${d / 365} Years"
        }
    }

    /** "expiry:ad:3d:2027-01-10": once per threshold per expiry date (a renewal moves the date and starts over). */
    fun key(service: String, level: Level, until: Long): String {
        val date = if (until > 0) SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(until)) else "none"
        return "expiry:$service:${level.tag}:$date"
    }

    /** ISO 8601 ("2027-01-10T20:04:02.000Z", "+00:00" offsets, or a bare date) to ms; 0 when unreadable. */
    fun iso(s: String): Long {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2}))?(?:\\.\\d+)?)?\\s*(Z|[+-]\\d{2}:?\\d{2})?$").find(s.trim()) ?: return 0L
        val g = m.groupValues
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].ifEmpty { "0" }.toInt(), g[5].ifEmpty { "0" }.toInt(), g[6].ifEmpty { "0" }.toInt())
        }
        var t = c.timeInMillis
        val z = g[7]
        if (z.isNotEmpty() && z != "Z") {
            val sign = if (z[0] == '-') -1 else 1
            val digits = z.substring(1).replace(":", "")
            t -= sign * (digits.substring(0, 2).toLong() * 3_600_000L + digits.substring(2, 4).toLong() * 60_000L)
        }
        return t
    }
}
