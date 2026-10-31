package com.dreamos.desktop.core.playback

import com.dreamos.desktop.core.model.Notice
import com.dreamos.desktop.core.model.NoticeAction
import com.dreamos.desktop.core.model.NoticeSeverity
import com.dreamos.desktop.core.notices.Notices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Where expiry notices go: core's notification center by default ([CoreNoticeSink]); tests record them. */
interface NoticeSink {
    /** Posts once per key ([Notices.post]); true when new. */
    fun post(key: String, type: String, title: String, body: String, severity: NoticeSeverity, action: NoticeAction?,
             banner: Boolean = true, replace: Boolean = false, store: Boolean = true): Boolean
    /** Keys of the kept notices that start with [prefix]. */
    fun keys(prefix: String): List<String>
    fun dismiss(key: String)
    fun dismissPrefix(prefix: String)
}

/** [NoticeSink] over win-content's [Notices]. */
object CoreNoticeSink : NoticeSink {
    override fun post(key: String, type: String, title: String, body: String, severity: NoticeSeverity, action: NoticeAction?,
                      banner: Boolean, replace: Boolean, store: Boolean) =
        Notices.post(key, type, title, body, severity, action, banner = banner, replace = replace, store = store)
    override fun keys(prefix: String) = Notices.all().map { it.key }.filter { it.startsWith(prefix) }
    override fun dismiss(key: String) = Notices.dismiss(key)
    override fun dismissPrefix(prefix: String) = Notices.dismissPrefix(prefix)
}

/**
 * When each linked debrid account's premium ends, and the notices that follow (fire-home DebridExpiry): a warning at 7,
 * 3 and 1 days left, a critical one once it has ended. Each fires once per threshold per expiry date, so a renewal (a
 * new date) starts over and clears that service's older warnings. An auto-renewing subscription (AllDebrid and TorBox
 * report it) gets no countdown, only the expired notice should a renewal fail. Plus, once per start, "Your Accounts":
 * every linked service with its time left (Settings › Account Summary at Startup).
 *
 * The plan comes from the read-only account call ([Accounts.checkHealth]; tokens only go to their own service and
 * never to the log). Checked at start, then every [EVERY_MS], at once when the linked accounts change, and after
 * [RETRY_MS] when nothing answered (offline). A 15-minute timer asks whether a round is due; nothing else runs.
 * Kept: two small values in the store; the plans themselves are the health records.
 */
class PremiumExpiry(
    private val accounts: Accounts,
    private val debrid: DebridAccounts,
    private val store: KeyValueStore,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val notices: NoticeSink = CoreNoticeSink,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val summaryOn: () -> Boolean = { true },
) {
    companion object {
        const val EVERY_MS = 6 * 3_600_000L
        const val RETRY_MS = 20 * 60_000L
        private const val TICK_MS = 15 * 60_000L
        private const val LAST = "expiry.last"
        private const val FP = "expiry.fp"

        /** Renewal pages (the notice's Renew action opens one: "renew:<service key>"). */
        fun renewUrl(service: DebridServiceId) = when (service) {
            DebridServiceId.ALLDEBRID -> "https://alldebrid.com/offer/"
            DebridServiceId.PREMIUMIZE -> "https://www.premiumize.me/premium"
            DebridServiceId.TORBOX -> "https://torbox.app/pricing"
            DebridServiceId.REAL_DEBRID -> "https://real-debrid.com/premium"
        }

        /** "renew:rd" to its renewal page; null for any other action. */
        fun renewUrl(action: String): String? =
            action.removePrefix("renew:").takeIf { action.startsWith("renew:") }?.let { DebridServiceId.of(it) }?.let { renewUrl(it) }
    }

    enum class Level(val tag: String) { D7("7d"), D3("3d"), D1("1d"), EXPIRED("expired") }

    private val mutex = Mutex()
    @Volatile private var startChecked = false
    @Volatile private var summaryDue = true
    private var loop: Job? = null

    // ---- the rules (pure) ----

    /** Calendar days from [now]'s date to [until]'s date in [z] (0 today, 1 tomorrow, negative once past). */
    fun daysLeft(now: Long, until: Long, z: ZoneId = zone()): Int =
        ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(z).toLocalDate(), Instant.ofEpochMilli(until).atZone(z).toLocalDate()).toInt()

    /**
     * The notice due: null (more than 7 days left, an auto-renewing subscription, or nothing known), D7 / D3 / D1 (at
     * most that many days left), EXPIRED (the end has passed, or the service says no premium).
     */
    fun level(now: Long, h: AccountHealth): Level? {
        if (h.until <= 0L) return if (h.premium == false) Level.EXPIRED else null
        if (h.until <= now) return Level.EXPIRED
        if (h.subscribed) return null
        val d = daysLeft(now, h.until)
        return when {
            d <= 1 -> Level.D1
            d <= 3 -> Level.D3
            d <= 7 -> Level.D7
            else -> null
        }
    }

    /** "96 Days", "1 Day", "Ends Today", "1 Year", "2 Years", "Expired", "Active" (premium, no end date). */
    fun timeLeft(now: Long, h: AccountHealth): String {
        if (level(now, h) == Level.EXPIRED) return "Expired"
        if (h.until <= 0L) return "Active"
        val d = daysLeft(now, h.until)
        return when {
            d <= 0 -> "Ends Today"
            d == 1 -> "1 Day"
            d < 365 -> "$d Days"
            d < 730 -> "1 Year"
            else -> "${d / 365} Years"
        }
    }

    /** "expiry:ad:3d:2027-01-10": once per threshold per expiry date. */
    fun key(service: DebridServiceId, level: Level, until: Long): String {
        val date = if (until > 0) Instant.ofEpochMilli(until).atOffset(ZoneOffset.UTC).toLocalDate().toString() else "none"
        return "expiry:${service.key}:${level.tag}:$date"
    }

    private fun fmt(pattern: String, at: Long) = DateTimeFormatter.ofPattern(pattern, Locale.US).withZone(zone()).format(Instant.ofEpochMilli(at))

    // ---- scheduling ----

    /** Starts the 15-minute check and the watch on linked accounts. Once; returns at once. */
    fun start() {
        synchronized(this) {
            if (loop?.isActive == true) return
            loop = scope.launch {
                launch { debrid.linked.drop(1).distinctUntilChanged().collect { check("accounts changed") } }
                while (isActive) {
                    maybeCheck()
                    delay(TICK_MS)
                }
            }
        }
    }

    /** Which accounts are linked; Real-Debrid by its device client id (its access token changes on every renewal). */
    private fun fingerprint(): String = DebridServiceId.entries.joinToString("|") { s ->
        val t = debrid.token(s) ?: return@joinToString ""
        val id = if (s == DebridServiceId.REAL_DEBRID) debrid.value("realdebrid.clientid").ifEmpty { t } else t
        Integer.toHexString(id.hashCode())
    }

    /** A round when one is due (the first in this process, [EVERY_MS] old, or other accounts). Cheap otherwise. */
    suspend fun maybeCheck() {
        val first = !startChecked
        startChecked = true
        val last = store.get(LAST)?.toLongOrNull() ?: 0
        val due = first || clock.wallMs() - last >= EVERY_MS || store.get(FP) != fingerprint()
        if (due) check(if (first) "start" else "due")
    }

    /**
     * Asks every linked service, posts the notices due. Returns how many answered with a plan. One round at a time;
     * a second ask while one runs waits for it and returns its count.
     */
    suspend fun check(why: String = "asked"): Int = mutex.withLock {
        val now = clock.wallMs()
        var asked = 0
        var answered = 0
        var warned = false
        val known = ArrayList<AccountHealth>()
        for (s in DebridServiceId.entries) {
            if (debrid.token(s) == null) {
                // Unlinked: its expiry notices go with it.
                notices.dismissPrefix("expiry:${s.key}:")
                continue
            }
            asked++
            val h = try { accounts.checkHealth(s) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            if (h == null || (h.health != AccountHealthState.OK && h.health != AccountHealthState.LIMIT)) continue
            answered++
            known += h
            if (notify(now, h)) warned = true
        }
        // Nothing answered (offline at start): tried again after RETRY_MS, not six hours.
        store.put(LAST, (if (asked > 0 && answered == 0) now - EVERY_MS + RETRY_MS else now).toString())
        store.put(FP, fingerprint())
        PlaybackLog.i("Premium check ($why): $answered of $asked accounts answered")
        if (summaryDue && answered > 0) { summaryDue = false; summary(now, known, banner = !warned) }
        answered
    }

    /** The notice [h] calls for, if any. True when one was posted now. */
    private fun notify(now: Long, h: AccountHealth): Boolean {
        val s = h.service
        val name = s.displayName
        val prefix = "expiry:${s.key}:"
        val level = level(now, h)
        if (level == null) {
            // Renewed, or far off: older warnings for this service are stale.
            notices.dismissPrefix(prefix)
            return false
        }
        val key = key(s, level, h.until)
        if (key in notices.keys(prefix)) return false
        val severity: NoticeSeverity
        val title: String
        val body: String
        if (level == Level.EXPIRED) {
            val others = debrid.linked.value.filter { it != s }.filter { o ->
                accounts.lastHealth(o)?.let { level(now, it) != Level.EXPIRED } ?: true
            }
            title = "$name Premium Expired"
            body = if (others.isNotEmpty()) "Streaming Through ${others.joinToString(" and ") { it.displayName }} Instead"
                else "Renew or Link Another Service to Keep Streaming"
            severity = NoticeSeverity.CRITICAL
        } else {
            val d = daysLeft(now, h.until)
            title = "$name Premium " + when (d) { 0 -> "Ends Today"; 1 -> "Ends Tomorrow"; else -> "Ends in $d Days" }
            body = "Renew by ${fmt("EEE, MMM d", h.until)} to Keep Streaming"
            severity = NoticeSeverity.WARNING
        }
        // One live notice per service: the newer threshold replaces the earlier one.
        for (k in notices.keys(prefix)) if (k != key) notices.dismiss(k)
        val posted = notices.post(key, Notice.TYPE_EXPIRY, title, body, severity, NoticeAction("Renew", "renew:${s.key}"))
        if (posted) PlaybackLog.i("Premium check: $title")
        return posted
    }

    /**
     * "Your Accounts", once per start of Dream OS after its first round: each service that answered with its time
     * left, soonest first ("Real-Debrid: 96 Days", "TorBox: Renews Automatically"). [banner] off when a warning just
     * took the banner (the list only). Settings › Account Summary at Startup turns it off.
     */
    private fun summary(now: Long, known: List<AccountHealth>, banner: Boolean) {
        if (!summaryOn()) return
        val lines = known.sortedBy { if (it.until > 0) it.until else Long.MAX_VALUE }.map { h ->
            val left = timeLeft(now, h)
            "${h.service.displayName}: ${if (h.subscribed && left != "Expired") "Renews Automatically" else left}"
        }
        if (lines.isEmpty()) return
        val day = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate().toString()
        notices.post("summary:$day", Notice.TYPE_SUMMARY, "Your Accounts", lines.joinToString("\n"), NoticeSeverity.INFO, null,
            banner = banner, replace = true, store = false)
    }

    /** The day [until] falls on, for a row's caption ("Jan 10, 2027"). */
    fun dateText(until: Long): String = fmt("MMM d, yyyy", until)
}
