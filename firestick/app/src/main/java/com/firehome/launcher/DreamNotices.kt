package com.firehome.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dream OS's notification center: one app-private, persistent list of notices (newest first, the
 * last [KEEP]), each with a dedupe [Notice.key], a severity, read state and an optional action.
 *
 * Adding a new kind of notice is one call:
 *
 *     DreamNotices.post(ctx, "update:1.4", DreamNotices.TYPE_UPDATE, "Dream OS 1.4 Is Ready", "Restart Dream OS to finish.",
 *         DreamNotices.Severity.INFO, DreamNotices.Action("Restart", "restart"))
 *
 * A key is posted once: posting it again (even after it was dismissed) does nothing, so put whatever
 * makes the event new into the key (a date, a version). [post] is safe from any thread. A new notice
 * shows as a banner over Dream OS's home screen ([Presenter], DreamNoticesUi); while Dream Player,
 * the screensaver or another app is in front it waits until Home is back. The bell in the top bar
 * and Settings › Notifications open the list.
 *
 * Actions are plain strings ([Action.target]) DreamNoticesUi knows how to run: "renew:<service>"
 * ("ad" | "pm" | "tb" | "rd") opens that service's renewal page as a QR code; "accounts" opens
 * Accounts. Nothing here ever holds a token.
 */
object DreamNotices {
    private const val TAG = "FireHome"
    private const val PREFS = "dream_notices"
    private const val LIST = "list"
    private const val SEEN = "seen"
    /** Notices kept (oldest drop off). */
    const val KEEP = 50
    /** Dedupe keys remembered after their notice is gone (so a dismissed notice never comes back). */
    private const val SEEN_KEEP = 300

    const val TYPE_EXPIRY = "expiry"
    const val TYPE_LIMIT = "limit"
    const val TYPE_EPISODES = "episodes"
    const val TYPE_UPDATE = "update"
    const val TYPE_INFO = "info"
    const val TYPE_SUMMARY = "summary"

    enum class Severity { INFO, WARNING, CRITICAL }

    /** What OK on the notice does: [label] names it ("Renew"), [target] says what to open (see the class doc). */
    class Action(val label: String, val target: String)

    class Notice(
        val key: String,
        val type: String,
        val title: String,
        val body: String,
        val severity: Severity,
        val at: Long,
        val read: Boolean,
        val action: Action?,
    ) {
        fun toJson(): JSONObject = JSONObject().put("k", key).put("t", type).put("ti", title).put("b", body)
            .put("s", severity.name).put("at", at).put("r", read).apply {
                if (action != null) put("al", action.label).put("ag", action.target)
            }

        fun withRead(r: Boolean) = Notice(key, type, title, body, severity, at, r, action)

        companion object {
            fun from(o: JSONObject) = Notice(
                o.optString("k"), o.optString("t", TYPE_INFO), o.optString("ti"), o.optString("b"),
                try { Severity.valueOf(o.optString("s")) } catch (e: Exception) { Severity.INFO },
                o.optLong("at"), o.optBoolean("r"),
                o.optString("ag").takeIf { it.isNotEmpty() }?.let { Action(o.optString("al", "Open"), it) },
            )
        }
    }

    /** Shows banners (MainActivity's DreamNoticesUi). Main thread only. */
    interface Presenter {
        /** Dream OS's own screen is in front and free (no Dream Player, no screensaver, no intro). */
        fun canShowBanner(): Boolean
        fun showBanner(n: Notice)
        /** A live banner (see [live]); null when it can't show now. */
        fun showLive(title: String): Live? = null
        /** An actionable banner (see [prompt]); false when it can't show now. */
        fun showPrompt(n: Notice, choices: List<Choice>, sound: Boolean, onBack: () -> Unit): Boolean = false
        /** Something that covered the banners' place closed (Settings › Updates): waiting banners may show now. */
        fun screenFreed() {}
    }

    /** One button on an actionable banner ([prompt]): [primary] wears the rainbow (one per banner, first). */
    class Choice(val label: String, val primary: Boolean = false, val run: () -> Unit)

    /** A banner for work under way, changed in place when it's done ([live]). Main thread. */
    interface Live {
        /**
         * The result in place: [title] in green with a check ([good], silent) or in red with the notification
         * sound; the banner leaves [holdMs] later, then [then] runs.
         */
        fun finish(title: String, good: Boolean, holdMs: Long, then: (() -> Unit)? = null)
        /** Leaves quietly (nothing worth saying); [then] runs once it has gone. */
        fun dismiss(then: (() -> Unit)? = null)
    }

    /**
     * A live banner right now: the usual banner, silent, the rainbow loader in its icon slot ("Checking for
     * Updates…"), never stored. Null when Home isn't free or another banner is on screen. Main thread.
     */
    fun live(title: String): Live? = presenter?.get()?.showLive(title)

    /**
     * Notice [n] as the standard banner with its [choices] built in, for the remote (the new-version notice:
     * Install Now and Later). It takes focus (the first choice), stays until one is picked, and Back runs
     * [onBack]; [sound] plays the notification sound. Never stored by this call (post the notice for the list).
     * False when Home isn't free or another banner is on screen. Main thread.
     */
    fun prompt(n: Notice, choices: List<Choice>, sound: Boolean, onBack: () -> Unit): Boolean =
        presenter?.get()?.showPrompt(n, choices, sound, onBack) ?: false

    @Volatile private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var cache: MutableList<Notice>? = null
    private val listeners = ArrayList<() -> Unit>()
    /** Banner-only notices ([post] with store off) still waiting for, or on, their banner. */
    private val transient = HashSet<String>()
    /** Kinds that are never kept in the list, only shown as a banner (the user keeps the list for account expiry). */
    val BANNER_ONLY = setOf(TYPE_SUMMARY, TYPE_LIMIT)
    /** New notices waiting for Home (shown in order once it is back). Main thread. */
    private val pending = ArrayList<Notice>()
    private var presenter: java.lang.ref.WeakReference<Presenter>? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.also { app = it }.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(ctx: Context): MutableList<Notice> = synchronized(lock) {
        cache ?: try {
            val a = JSONArray(prefs(ctx).getString(LIST, null) ?: "[]")
            MutableList(a.length()) { Notice.from(a.getJSONObject(it)) }
        } catch (e: Exception) { ArrayList() }.also { list ->
            // The list holds account expiry only (the user's rule): banner-only kinds stored by older builds go.
            if (list.removeAll { it.type in BANNER_ONLY }) save(ctx, list)
            cache = list
        }
    }

    private fun save(ctx: Context, list: List<Notice>) {
        val a = JSONArray()
        for (n in list) a.put(n.toJson())
        prefs(ctx).edit().putString(LIST, a.toString()).apply()
    }

    private fun seen(ctx: Context): MutableList<String> = try {
        val a = JSONArray(prefs(ctx).getString(SEEN, null) ?: "[]")
        MutableList(a.length()) { a.getString(it) }
    } catch (e: Exception) { ArrayList() }

    // ---- reading ----

    /** Every notice, newest first. */
    fun all(ctx: Context): List<Notice> = synchronized(lock) { ArrayList(load(ctx)) }

    fun unreadCount(ctx: Context): Int = synchronized(lock) { load(ctx).count { !it.read } }

    fun find(ctx: Context, key: String): Notice? = synchronized(lock) { load(ctx).firstOrNull { it.key == key } }

    // ---- writing ----

    /**
     * Posts a notice once per [key]; true when it is new. It goes to the top of the list, unread, and
     * shows as a banner (now, or once the user is back on Home) unless [banner] is off. [store] off: a
     * banner only, never in the list or the badge (the list is for account expiry, the user's rule).
     * [replace]: shown again even if the key was posted before (the summary on every start-up).
     * Any thread.
     */
    fun post(ctx: Context, key: String, type: String, title: String, body: String,
             severity: Severity = Severity.INFO, action: Action? = null, banner: Boolean = true, replace: Boolean = false,
             store: Boolean = true): Boolean {
        val n = Notice(key, type, title, body, severity, System.currentTimeMillis(), false, action)
        synchronized(lock) {
            val seen = seen(ctx)
            val list = load(ctx)
            if (replace) list.removeAll { it.key == key }
            else if (key in seen || list.any { it.key == key }) return false
            if (store) {
                list.add(0, n)
                while (list.size > KEEP) list.removeAt(list.size - 1)
                save(ctx, list)
            } else transient.add(key)
            if (key !in seen) seen.add(key)
            while (seen.size > SEEN_KEEP) seen.removeAt(0)
            prefs(ctx).edit().putString(SEEN, JSONArray(seen).toString()).apply()
        }
        Log.w(TAG, "Notices: posted $key (${severity.name.lowercase()})")
        main.post {
            changed()
            if (!banner) return@post
            pending.removeAll { it.key == key }
            pending.add(n)
            flush()
        }
        return true
    }

    fun markRead(ctx: Context, key: String) = edit(ctx) { list -> list.replaceAll { if (it.key == key && !it.read) it.withRead(true) else it } }

    fun markAllRead(ctx: Context) = edit(ctx) { list -> list.replaceAll { if (it.read) it else it.withRead(true) } }

    fun dismiss(ctx: Context, key: String) = edit(ctx) { list -> list.removeAll { it.key == key } }

    /** Removes every notice whose key starts with [prefix] (e.g. a service's old expiry warnings after a renewal). Their keys stay seen. */
    fun dismissPrefix(ctx: Context, prefix: String) = edit(ctx) { list -> list.removeAll { it.key.startsWith(prefix) } }

    fun clearAll(ctx: Context) = edit(ctx) { list -> list.clear() }

    /** Forgets that keys starting with [prefix] were ever posted, so they can fire again (a renewal starts a new period). */
    fun forget(ctx: Context, prefix: String) {
        synchronized(lock) {
            val s = seen(ctx)
            if (s.removeAll { it.startsWith(prefix) }) prefs(ctx).edit().putString(SEEN, JSONArray(s).toString()).apply()
        }
    }

    private inline fun edit(ctx: Context, change: (MutableList<Notice>) -> Unit) {
        synchronized(lock) {
            val list = load(ctx)
            val before = list.map { it.key to it.read }
            change(list)
            if (list.map { it.key to it.read } == before) return
            save(ctx, list)
        }
        main.post {
            val c = app
            if (c != null) pending.removeAll { p -> !stillNew(c, p.key) }
            changed()
        }
    }

    // ---- listeners and banners (main thread) ----

    /** [l] runs (main thread) whenever the list or a read state changes: the bell's badge, an open panel. */
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { for (l in listeners.toList()) try { l() } catch (e: Exception) { Log.w(TAG, "Notices: listener failed: $e") } }

    /** See [Presenter.screenFreed]. */
    fun screenFreed() { presenter?.get()?.screenFreed() }

    fun setPresenter(p: Presenter?) { presenter = p?.let { java.lang.ref.WeakReference(it) } }

    /** Home is back (or the screensaver closed): any banners that waited show now, in order. Main thread. */
    fun flush() {
        val p = presenter?.get() ?: return
        if (pending.isEmpty() || !p.canShowBanner()) return
        // Several waited (a long movie): the newest two, not a parade.
        val show = pending.takeLast(2)
        pending.clear()
        for (n in show) p.showBanner(n)
    }

    /** A banner not yet shown that is no longer worth showing (read meanwhile): DreamNoticesUi asks before it slides in. */
    fun stillNew(ctx: Context, key: String) = synchronized(lock) { key in transient } || find(ctx, key)?.read == false

    /** A banner-only notice has been shown (or dropped): nothing of it is kept. */
    fun bannerDone(key: String) { synchronized(lock) { transient.remove(key) } }

    // ---- hooks for other parts of Dream OS ----

    /**
     * Dream Core found a debrid account at its limit (AllDebrid's MAGNET_TOO_MANY, a quota): one warning
     * per service per day. [service] "ad" | "pm" | "tb" | "rd"; [what] names the limit ("Magnet",
     * "Download"). Any thread; never blocks. For the resolver: `DreamNotices.accountLimit(ctx, "ad")`.
     */
    fun accountLimit(ctx: Context, service: String, what: String = if (service == "ad") "Magnet" else "Download") {
        val name = serviceName(service)
        val day = DreamTime.todayYmd(ctx)
        post(ctx, "limit:$service:$day", TYPE_LIMIT, "$name $what Limit Reached",
            "Streaming Through Your Other Services for Now",
            Severity.WARNING, null, store = false)
    }

    fun serviceName(service: String) = when (service) {
        "ad" -> "AllDebrid"; "pm" -> "Premiumize"; "tb" -> "TorBox"; "rd" -> "Real-Debrid"; else -> service
    }

    /** Developer preview (PingReceiver `--es notice demo`): a warning and a critical notice, new keys each time. */
    fun demo(ctx: Context) {
        val t = System.currentTimeMillis()
        post(ctx, "demo:warn:$t", TYPE_EXPIRY, "AllDebrid Premium Ends in 3 Days",
            "Renew by ${DebridExpiry.shortDate(t + 3 * 86_400_000L)} to Keep Streaming",
            Severity.WARNING, Action("Renew", "renew:ad"))
        main.postDelayed({
            // The longest line a real notice carries (three services to fall back on): it wraps, never cut.
            post(ctx, "demo:crit:$t", TYPE_EXPIRY, "Premiumize Premium Expired",
                "Streaming Through Real-Debrid and AllDebrid and TorBox Instead",
                Severity.CRITICAL, Action("Renew", "renew:pm"))
        }, 900)
        main.postDelayed({
            post(ctx, "demo:sum:$t", TYPE_SUMMARY, "Your Accounts", "Real-Debrid: 5 Days\nPremiumize: 21 Days\nAllDebrid: 76 Days\nTorBox: Renews Automatically",
                Severity.INFO, null, replace = true, store = false)
        }, 1_800)
    }

    /** Developer: removes the demo notices (`--es notice clear-demo`). */
    fun clearDemo(ctx: Context) = dismissPrefix(ctx, "demo:")
}
