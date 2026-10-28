package com.firehome.launcher

import android.content.Context
import android.util.Log

/**
 * Dream OS owns the debrid accounts the user links. They live in Dream OS's own private prefs, so
 * the Dream Core Resolver plays the moment a sign-in is approved.
 *
 * Values are kept under stable setting ids ("alldebridtoken", "realdebridrefresh", ...): that is what
 * [DebridAccounts.Accounts] reads, so nothing is ever translated twice.
 *
 * Tokens are never logged; logs name the service only.
 */
internal object DreamAccounts {
    private const val TAG = "FireHome"
    private const val PREFS = "dream_accounts"

    /** Every id kept per service ("ad" | "pm" | "tb" | "rd"); the first is the "<name>.enable" switch, the second the token. */
    private val IDS = linkedMapOf(
        "ad" to listOf("alldebrid.enable", "alldebridtoken", "alldebridusername"),
        "pm" to listOf("premiumize.enable", "premiumizetoken", "premiumizeusername"),
        "tb" to listOf("torbox.enable", "torboxtoken", "torbox.username"),
        "rd" to listOf("realdebrid.enable", "realdebridtoken", "realdebridrefresh", "realdebrid.clientid", "realdebridsecret", "realdebridusername"),
    )
    private val USER = mapOf("ad" to "alldebridusername", "pm" to "premiumizeusername", "tb" to "torbox.username", "rd" to "realdebridusername")
    val ALL_IDS = IDS.values.flatten()

    @Volatile private var app: Context? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.also { app = it }.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun token(service: String) = IDS.getValue(service)[1]

    /** Dream OS's accounts by setting id (a prefs read; callers cache it). Never null; kept nullable for callers. */
    fun values(ctx: Context): Map<String, String>? {
        val p = prefs(ctx)
        return ALL_IDS.associateWith { p.getString(it, "").orEmpty() }
    }

    // ---- service order (Settings › Accounts › a service › Use First) ----

    /** Every service key, in the order Dream Core asks them by default: lookups that add nothing to the account first. */
    val DEFAULT_ORDER = listOf("pm", "tb", "rd", "ad")
    private const val ORDER = "_order"
    private val orderListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /**
     * The user's service order: all four keys ("ad" | "pm" | "tb" | "rd"), first = asked first (the
     * primary). Linked or not; callers skip what isn't linked. Unknown or missing keys fall back to
     * [DEFAULT_ORDER] positions, so the list is always complete. Any thread; one prefs read.
     */
    fun order(ctx: Context): List<String> {
        val saved = prefs(ctx).getString(ORDER, null)?.split(',')?.map { it.trim() }?.filter { it in IDS }?.distinct().orEmpty()
        return saved + DEFAULT_ORDER.filter { it !in saved }
    }

    /** The linked services in the user's order (the primary first). */
    fun linkedInOrder(ctx: Context): List<String> = order(ctx).filter { linked(ctx, it) }

    /** Saves a new order ([keys] may be partial: the rest keep their relative order after it). */
    fun setOrder(ctx: Context, keys: List<String>) {
        val clean = keys.filter { it in IDS }.distinct()
        val full = clean + order(ctx).filter { it !in clean }
        prefs(ctx).edit().putString(ORDER, full.joinToString(",")).apply()
        Log.w(TAG, "Dream OS accounts: service order ${full.joinToString(" > ") { it.uppercase() }}")
        for (l in orderListeners) try { l() } catch (e: Exception) {}
    }

    /** Moves [service] to the front (asked first). */
    fun makePrimary(ctx: Context, service: String) = setOrder(ctx, listOf(service) + order(ctx).filter { it != service })

    /** Moves [service] one place earlier ([by] = -1) or later (+1) in the full order. */
    fun move(ctx: Context, service: String, by: Int) {
        val o = order(ctx).toMutableList()
        val i = o.indexOf(service)
        val j = (i + by).coerceIn(0, o.size - 1)
        if (i < 0 || i == j) return
        o.removeAt(i); o.add(j, service)
        setOrder(ctx, o)
    }

    /** Called (on the caller's thread) whenever the order changes, e.g. to drop a cached service list. */
    fun onOrderChanged(l: () -> Unit) { orderListeners.addIfAbsent(l) }

    fun linked(ctx: Context, service: String) = !prefs(ctx).getString(token(service), null).isNullOrBlank()
    fun user(ctx: Context, service: String): String? = USER[service]?.let { prefs(ctx).getString(it, null) }?.takeIf { it.isNotBlank() && it != "true" }

    /** An approved sign-in ([DebridLink.Poll.Done.settings]): saved in Dream OS at once. */
    fun link(ctx: Context, service: String, settings: Map<String, String>) {
        val ids = IDS[service] ?: return
        val values = ids.associateWith { settings[it].orEmpty() }.toMutableMap()
        values[ids[0]] = "true" // linking is asking to use it
        save(ctx, values, "${service.uppercase()} linked")
        DebridExpiry.accountsChanged(ctx) // its premium end date, read right away
    }

    /** Signs [service] out in Dream OS. The "<name>.enable" switch is left as it is. */
    fun unlink(ctx: Context, service: String) {
        val ids = IDS[service] ?: return
        save(ctx, ids.drop(1).associateWith { "" }, "${service.uppercase()} unlinked")
    }

    /** A Real-Debrid access token refreshed by the resolver: kept, so the next start doesn't refresh again. */
    fun refreshedRealDebrid(access: String, refresh: String?) {
        val ctx = app ?: return
        if (!linked(ctx, "rd")) return
        val e = prefs(ctx).edit().putString("realdebridtoken", access)
        if (!refresh.isNullOrBlank()) e.putString("realdebridrefresh", refresh)
        e.apply()
        DebridAccounts.invalidate()
    }

    @Synchronized private fun save(ctx: Context, values: Map<String, String>, what: String) {
        val e = prefs(ctx).edit()
        for ((k, v) in values) e.putString(k, v)
        e.commit()
        DebridAccounts.invalidate()
        Log.w(TAG, "Dream OS accounts: $what")
    }
}

/**
 * Instant Play (Settings › Playback): the stream the Play button would play is looked for while the
 * viewer browses or reads a title page, so Play starts at once. Off: nothing is looked for ahead
 * (Play still finds it, a moment later). Next-episode lookups during the credits are Autoplay's.
 */
object InstantPlay {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("dream_instant_play", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("instant_play", true)
    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("instant_play", on).apply()
        Log.w("FireHome", "Instant Play ${if (on) "on" else "off"}")
    }
}

/**
 * Dream OS's own source filters: quality cap, Hide AV1 / HEVC / HDR / Dolby Vision / CAM, size
 * limits, HDR / HEVC preference and subtitle languages. The Dream Core Resolver reads them here
 * ([current]); Settings › Sources & Quality writes them ([put]).
 *
 * Keys are the option keys below ([KEYS]), plus three the UI doesn't show: hide.avc, hide.3d, hide.upscaled.
 */
object DreamFilters {
    private const val TAG = "FireHome"
    private const val PREFS = "dream_filters"
    private val Q = listOf("4k", "1080p", "720p", "sd")

    /** The option keys Settings shows. */
    val KEYS = setOf(
        "quality.max", "quality.min", "hide.cam", "hide.hdr", "hide.dv", "hide.hevc", "hide.av1", "prefer.hdr", "prefer.hevc",
        "size.movie.limit", "size.movie.min_gb", "size.movie.max_gb", "size.movie.speed_mbps",
        "size.episode.limit", "size.episode.min_gb", "size.episode.max_gb", "size.episode.speed_mbps",
        "subs.enabled", "subs.lang1", "subs.lang2", "subs.embedded", "subs.hi", "subs.forced",
    )
    /** Keys the resolver honors that Settings doesn't show. */
    private val EXTRA = setOf("hide.avc", "hide.3d", "hide.upscaled")

    /** The filters as the resolver applies them. Missing values fall back to safe defaults for this stick. */
    class Values internal constructor(private val v: Map<String, String>) {
        private fun on(k: String, def: Boolean) = v[k].let { if (it.isNullOrEmpty()) def else it == "true" }
        /** A quality cap was ever chosen; without one the resolver ranks 1080p first. */
        val known = !v["quality.max"].isNullOrEmpty()
        /** 0 4K, 1 1080p, 2 720p, 3 SD: anything better is dropped. */
        val cap = Q.indexOf(v["quality.max"]).coerceAtLeast(0)
        /** SD only goes when something better exists. */
        val hideSd = v["quality.min"].let { if (it.isNullOrEmpty()) true else it != "sd" }
        val hideCam = on("hide.cam", true)
        val hideHdr = on("hide.hdr", false)
        val hideDv = on("hide.dv", false)
        val hideHevc = on("hide.hevc", false)
        /** 10-bit AV1 crashes this stick's display driver: hidden unless the user turned it off. */
        val hideAv1 = on("hide.av1", true)
        val hideAvc = on("hide.avc", false)
        val hide3d = on("hide.3d", true)
        val hideUpscaled = on("hide.upscaled", false)
        val preferHdr = on("prefer.hdr", false)
        val preferHevc = on("prefer.hevc", false)
        val subsOn = on("subs.enabled", false)
        /** Subtitle languages in order of preference (English names, e.g. "Portuguese(Brazil)"). */
        val subLangs: List<String> = listOfNotNull(v["subs.lang1"], v["subs.lang2"]).filter { it.isNotBlank() }.distinct().ifEmpty { listOf("English") }

        /** Size range in GB when the limit is "range"; "speed" isn't applied by the resolver. */
        fun range(ep: Boolean): ClosedFloatingPointRange<Double>? {
            val p = if (ep) "size.episode" else "size.movie"
            if (v["$p.limit"] != "range") return null
            val min = v["$p.min_gb"]?.toDoubleOrNull() ?: 0.0
            val max = v["$p.max_gb"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: Double.MAX_VALUE
            return min..maxOf(min, max)
        }

        /** Internet speed in Mbps when the limit is "speed" (files too big to stream at it are skipped); null otherwise. */
        fun speedMbps(ep: Boolean): Double? {
            val p = if (ep) "size.episode" else "size.movie"
            if (v["$p.limit"] != "speed") return null
            return (v["$p.speed_mbps"] ?: v["size.movie.speed_mbps"])?.toDoubleOrNull()?.takeIf { it > 0 }
        }
    }

    @Volatile private var cached: Values? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Any thread; one prefs read, then cached until a value changes. */
    fun current(ctx: Context): Values {
        cached?.let { return it }
        return Values(stored(ctx)).also { cached = it }
    }

    /** What Dream OS holds, by option key (for the settings UI). */
    fun stored(ctx: Context): Map<String, String> =
        prefs(ctx).all.filterKeys { !it.startsWith("_") }.mapValues { it.value?.toString().orEmpty() }

    /** A value the user chose; non-filter keys are ignored. */
    fun put(ctx: Context, key: String, value: String) {
        if (key !in KEYS && key !in EXTRA) return
        prefs(ctx).edit().putString(key, value).apply()
        cached = null
    }
}
