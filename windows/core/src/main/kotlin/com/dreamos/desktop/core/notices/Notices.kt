package com.dreamos.desktop.core.notices

import com.dreamos.desktop.core.model.Notice
import com.dreamos.desktop.core.model.NoticeAction
import com.dreamos.desktop.core.model.NoticeSeverity
import com.dreamos.desktop.core.platform.Platform
import com.dreamos.desktop.core.service.NoticeCenter
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer

/**
 * Dream OS's notification center (fire-home DreamNotices.kt): one persistent list of notices for the whole PC (not per
 * profile), newest first, the last [KEEP], each with a dedupe key, a severity, read state and an optional action.
 *
 * A key is posted once, ever: posting it again (even after it was dismissed) does nothing, so whatever makes an event
 * new goes into the key (a date, a version). Keys are remembered [SEEN_KEEP] deep after their notice is gone.
 * Banner-only kinds ([BANNER_ONLY], or [post] with `store = false`) show as a banner and are never kept in the list.
 *
 * Banners: a new notice waits in [pending] until the shell says Dream OS is free ([canShowBanner]: the window in front,
 * no player); [flush] then hands the newest two to [banners]. The shell calls [flush] when the window comes back to
 * the front or the player closes. Posted while the window isn't in front, a notice also goes out as a system
 * notification ([Platform.notify]).
 *
 * Actions are plain target strings the app runs: "update", "accounts", "renew:<service>", "title:<kind>:<id>".
 * Worst case: 50 notices + 300 keys, a few tens of KB. Thread-safe.
 */
object Notices {
    private const val FILE = "dream_notices"
    private const val LIST = "list"
    private const val SEEN = "seen"
    /** Notices kept (oldest drop off). */
    const val KEEP = 50
    /** Dedupe keys remembered after their notice is gone (so a dismissed notice never comes back). */
    const val SEEN_KEEP = 300

    /** Kinds never kept in the list, only shown as a banner (the list is for what needs attention). */
    val BANNER_ONLY = setOf(Notice.TYPE_SUMMARY, Notice.TYPE_LIMIT)

    private val ser = ListSerializer(Notice.serializer())
    private val lock = Any()
    private var cache: MutableList<Notice>? = null
    private var cacheStore: DreamStore? = null
    /** Banner-only notices waiting for, or on, their banner. */
    private val transient = HashSet<String>()
    private val pending = ArrayList<Notice>()

    private val _notices = MutableStateFlow<List<Notice>>(emptyList())
    private val _banners = MutableSharedFlow<Notice>(extraBufferCapacity = 16)

    /** Set by the shell: the Dream OS window is in front (else a new notice also goes out as a system notification). */
    @Volatile var appInFront: () -> Boolean = { true }

    /** Set by the shell: a banner may show now (the window in front, no player, no full-screen page over it). */
    @Volatile var canShowBanner: () -> Boolean = { appInFront() }

    /** Every kept notice, newest first. */
    val notices: StateFlow<List<Notice>>
        get() { synchronized(lock) { load() }; return _notices }

    /** Banners to show now, in order (from [flush]). The shell collects it and shows each with [Notice.emoji] / action. */
    val banners: SharedFlow<Notice> get() = _banners

    private fun prefs() = DreamStore.shared.file(FILE)

    private fun load(): MutableList<Notice> {
        val store = DreamStore.shared
        cache?.let { if (cacheStore === store) return it }
        val list = (prefs().get(LIST, ser) ?: emptyList()).toMutableList()
        // The list holds what needs attention: banner-only kinds stored by older builds go.
        if (list.removeAll { it.type in BANNER_ONLY }) save(list)
        cache = list
        cacheStore = store
        _notices.value = list.toList()
        return list
    }

    private fun save(list: List<Notice>) {
        prefs().put(LIST, ser, list)
        _notices.value = list.toList()
    }

    private fun seen(): MutableList<String> = prefs().getStringList(SEEN).toMutableList()

    // ---- reading ----

    fun all(): List<Notice> = synchronized(lock) { load().toList() }
    fun unreadCount(): Int = synchronized(lock) { load().count { !it.read } }
    fun find(key: String): Notice? = synchronized(lock) { load().firstOrNull { it.key == key } }

    // ---- writing ----

    /**
     * Posts [n] once per key; true when it is new. It goes to the top of the list, unread ([Notice.at] set to now when
     * 0), and waits for its banner unless [banner] is off. [store] off: a banner only, never in the list or the badge.
     * [replace]: shown again even if the key was posted before (a summary on every start). Any thread.
     */
    fun post(n: Notice, banner: Boolean = true, replace: Boolean = false, store: Boolean = n.type !in BANNER_ONLY): Boolean {
        val notice = n.copy(at = if (n.at > 0) n.at else System.currentTimeMillis(), read = false)
        synchronized(lock) {
            val seen = seen()
            val list = load()
            if (replace) list.removeAll { it.key == notice.key }
            else if (notice.key in seen || list.any { it.key == notice.key }) return false
            if (store) {
                list.add(0, notice)
                while (list.size > KEEP) list.removeAt(list.size - 1)
                save(list)
            } else transient.add(notice.key)
            if (notice.key !in seen) seen.add(notice.key)
            while (seen.size > SEEN_KEEP) seen.removeAt(0)
            prefs().putStringList(SEEN, seen)
            if (banner) {
                pending.removeAll { it.key == notice.key }
                pending.add(notice)
            }
        }
        Log.i("Notices", "posted ${notice.key} (${notice.severity.name.lowercase()})")
        val front = try { appInFront() } catch (e: Exception) { true }
        val osAlerts = try { com.dreamos.desktop.core.content.ViewerSettings.osAlerts() } catch (e: Exception) { true }
        if (!front && osAlerts) try { Platform.current.notify(notice.title, notice.body) } catch (e: Exception) {
            Log.w("Notices", "system notification failed: ${e.javaClass.simpleName}")
        }
        if (banner) flush()
        return true
    }

    /** The fire-home call shape: `post(key, type, title, body, severity, action)`. */
    fun post(key: String, type: String, title: String, body: String, severity: NoticeSeverity = NoticeSeverity.INFO,
             action: NoticeAction? = null, emoji: String? = null, banner: Boolean = true, replace: Boolean = false,
             store: Boolean = type !in BANNER_ONLY): Boolean =
        post(Notice(key, type, title, body, severity, 0, false, action, emoji), banner, replace, store)

    fun markRead(key: String) = edit { list -> list.replaceAll { if (it.key == key && !it.read) it.copy(read = true) else it } }
    fun markAllRead() = edit { list -> list.replaceAll { if (it.read) it else it.copy(read = true) } }
    fun dismiss(key: String) = edit { list -> list.removeAll { it.key == key } }

    /** Removes every notice whose key starts with [prefix] (a service's old expiry warnings after a renewal). Their keys stay seen. */
    fun dismissPrefix(prefix: String) = edit { list -> list.removeAll { it.key.startsWith(prefix) } }

    fun clearAll() = edit { list -> list.clear() }

    /** Forgets that keys starting with [prefix] were ever posted, so they can fire again (a renewal starts a new period). */
    fun forget(prefix: String): Unit = synchronized(lock) {
        val s = seen()
        if (s.removeAll { it.startsWith(prefix) }) prefs().putStringList(SEEN, s)
    }

    private inline fun edit(change: (MutableList<Notice>) -> Unit): Unit = synchronized(lock) {
        val list = load()
        val before = list.map { it.key to it.read }
        change(list)
        // A banner not shown yet that was read or removed meanwhile is no longer worth showing.
        pending.removeAll { p -> !stillNewLocked(p.key) }
        if (list.map { it.key to it.read } != before) save(list)
    }

    // ---- banners ----

    /** Dream OS is free again (window in front, player closed): waiting banners go out, the newest two. */
    fun flush() {
        val show: List<Notice>
        synchronized(lock) {
            if (pending.isEmpty()) return
            val free = try { canShowBanner() } catch (e: Exception) { false }
            if (!free) return
            // Several waited (a long movie): the newest two, not a parade.
            show = pending.filter { stillNewLocked(it.key) }.takeLast(2)
            pending.clear()
        }
        for (n in show) _banners.tryEmit(n)
    }

    /** Banners still waiting (the shell may show them itself). */
    fun pendingBanners(): List<Notice> = synchronized(lock) { pending.toList() }

    private fun stillNewLocked(key: String) = key in transient || load().firstOrNull { it.key == key }?.read == false

    /** A banner not yet shown that is no longer worth showing (read meanwhile): ask before it slides in. */
    fun stillNew(key: String) = synchronized(lock) { stillNewLocked(key) }

    /** A banner-only notice has been shown (or dropped): nothing of it is kept. */
    fun bannerDone(key: String) { synchronized(lock) { transient.remove(key) } }

    // ---- hooks for other parts of Dream OS ----

    /**
     * Dream Core found a debrid account at its limit: one banner per service per day. [service] "ad" | "pm" | "tb" | "rd";
     * [what] names the limit ("Magnet", "Download"). Any thread; never blocks.
     */
    fun accountLimit(service: String, what: String = if (service == "ad") "Magnet" else "Download", day: String) {
        post("limit:$service:$day", Notice.TYPE_LIMIT, "${serviceName(service)} $what Limit Reached",
            "Streaming Through Your Other Services for Now", NoticeSeverity.WARNING, store = false)
    }

    fun serviceName(service: String) = when (service) {
        "ad" -> "AllDebrid"; "pm" -> "Premiumize"; "tb" -> "TorBox"; "rd" -> "Real-Debrid"; else -> service
    }

    /** Tests: forget the in-memory copy (a new store). */
    internal fun reset() = synchronized(lock) { cache = null; cacheStore = null; transient.clear(); pending.clear() }
}

/** [NoticeCenter] for the screens, over [Notices]. Register at launch: `Services.notices = NoticeCenterImpl()`. */
class NoticeCenterImpl : NoticeCenter {
    override val notices: StateFlow<List<Notice>> get() = Notices.notices
    override fun post(notice: Notice) { Notices.post(notice) }
    override fun post(notice: Notice, banner: Boolean, store: Boolean) { Notices.post(notice, banner = banner, store = store) }
    override fun markRead(key: String) = Notices.markRead(key)
    override fun markAllRead() = Notices.markAllRead()
    override fun dismiss(key: String) = Notices.dismiss(key)

    /** The banners to show (see [Notices.banners]). */
    override val banners: SharedFlow<Notice> get() = Notices.banners

    /** See [Notices.appInFront]. */
    var appInFront: () -> Boolean
        get() = Notices.appInFront
        set(v) { Notices.appInFront = v }
}
