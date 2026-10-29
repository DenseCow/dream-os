package com.firehome.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Dream OS time zone + clock format. Stored per device (not per profile).
 * Every displayed time and every "Today / Tomorrow / weekday" decision goes through here,
 * so the whole platform follows the zone picked in Settings > Time & Region (or setup).
 */
object DreamTime {
    private const val PREFS = "dream_time"
    private const val K_ZONE = "zone"     // absent = Automatic
    private const val K_24H = "clock24"   // absent = follow device; "1" / "0"
    private const val TAG = "FireHome"

    data class Choice(val id: String, val label: String)

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cachedZoneId: String? = null
    @Volatile private var cacheLoaded = false
    @Volatile private var cached24: Boolean? = null
    @Volatile private var cache24Loaded = false

    @Volatile private var app: Context? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.also { app = it }.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Load the saved choice early (MainActivity / player onCreate) so the context-free helpers below know it. */
    fun init(ctx: Context) { storedZoneId(ctx); override24h(ctx) }

    /** Context-free: the chosen zone once [init] ran (any DreamTime call with a Context counts), else the device zone. */
    fun zone(): TimeZone = app?.let { zone(it) } ?: detected()

    /** Context-free clock format (device 12-hour default until [init]). */
    fun use24h(): Boolean = app?.let { use24h(it) } ?: false

    /** Context-free versions of the formatters, for helpers that have no Context (AirTimes, Upcoming...). */
    fun fmt(pattern: String): SimpleDateFormat = SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone() }
    fun calendar(epochMs: Long = System.currentTimeMillis()): Calendar =
        Calendar.getInstance(zone(), Locale.US).apply { timeInMillis = epochMs }
    fun formatTime(epochMs: Long): String =
        SimpleDateFormat(if (use24h()) "HH:mm" else "h:mm a", Locale.US).apply { timeZone = zone() }.format(epochMs)
    fun todayYmd(now: Long = System.currentTimeMillis()): String = fmt("yyyy-MM-dd").format(now)
    /** "yyyy-MM-dd" for today + [offsetDays] in the chosen zone (calendar days, DST-safe). */
    fun ymdPlus(offsetDays: Int): String = calendar().apply { add(Calendar.DAY_OF_MONTH, offsetDays) }.let { fmt("yyyy-MM-dd").format(it.time) }
    fun dayDelta(epochMs: Long, now: Long = System.currentTimeMillis()): Int =
        (dayIndex(calendar(epochMs)) - dayIndex(calendar(now))).toInt()
    /** Hour of day (0-23) now in the chosen zone (greetings, Tonight vs Today). */
    fun hourNow(): Int = calendar().get(Calendar.HOUR_OF_DAY)

    // ---------------------------------------------------------------- zone

    /** The device zone (what "Automatic" follows). */
    fun detected(): TimeZone = TimeZone.getDefault()

    private fun storedZoneId(ctx: Context): String? {
        if (!cacheLoaded) {
            cachedZoneId = runCatching { prefs(ctx).getString(K_ZONE, null) }.getOrNull()
            cacheLoaded = true
        }
        return cachedZoneId
    }

    fun isAutomatic(ctx: Context): Boolean = storedZoneId(ctx) == null

    /** The user's pick, or the device zone when Automatic. */
    fun zone(ctx: Context): TimeZone {
        val id = storedZoneId(ctx) ?: return detected()
        return TimeZone.getTimeZone(id)
    }

    /** null = Automatic. */
    fun setZone(ctx: Context, zoneId: String?) {
        val clean = zoneId?.takeIf { it.isNotBlank() && TimeZone.getTimeZone(it).id == it }
        prefs(ctx).edit().apply { if (clean == null) remove(K_ZONE) else putString(K_ZONE, clean) }.apply()
        cachedZoneId = clean; cacheLoaded = true
        Log.w(TAG, "Time: zone set to " + (clean?.let { "$it (manual)" } ?: "${detected().id} (automatic)"))
        notifyChanged()
    }

    // --------------------------------------------------------- clock format

    /** Default: follow the device setting. */
    fun use24h(ctx: Context): Boolean {
        val o = override24h(ctx)
        if (o != null) return o
        return runCatching { android.text.format.DateFormat.is24HourFormat(ctx) }.getOrDefault(false)
    }

    /** null = follow device, true/false = forced. */
    fun override24h(ctx: Context): Boolean? {
        if (!cache24Loaded) {
            cached24 = runCatching { prefs(ctx).getString(K_24H, null) }.getOrNull()?.let { it == "1" }
            cache24Loaded = true
        }
        return cached24
    }

    /** null = follow device. */
    fun set24h(ctx: Context, on: Boolean?) {
        prefs(ctx).edit().apply { if (on == null) remove(K_24H) else putString(K_24H, if (on) "1" else "0") }.apply()
        cached24 = on; cache24Loaded = true
        Log.w(TAG, "Time: clock format " + when (on) { null -> "automatic"; true -> "24-hour"; false -> "12-hour" })
        notifyChanged()
    }

    // ------------------------------------------------------------ listeners

    fun addListener(l: () -> Unit) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun notifyChanged() {
        val run = Runnable { for (l in listeners) runCatching { l() } }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    // ------------------------------------------------------------ formatting

    /** A SimpleDateFormat already set to the chosen zone (US English month/day names). */
    fun fmt(ctx: Context, pattern: String): SimpleDateFormat =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone(ctx) }

    /** A Calendar in the chosen zone, set to [epochMs] (now by default). */
    fun calendar(ctx: Context, epochMs: Long = System.currentTimeMillis()): Calendar =
        Calendar.getInstance(zone(ctx), Locale.US).apply { timeInMillis = epochMs }

    /** "9:00 PM" / "21:00" in the chosen zone. */
    fun formatTime(ctx: Context, epochMs: Long): String = formatTimeIn(ctx, epochMs, zone(ctx))

    /** Same as [formatTime] but in an explicit zone (used by the picker to preview each zone). */
    fun formatTimeIn(ctx: Context, epochMs: Long, tz: TimeZone): String =
        SimpleDateFormat(if (use24h(ctx)) "HH:mm" else "h:mm a", Locale.US).apply { timeZone = tz }.format(epochMs)

    /** A big clock face: "9:00" (12-hour, no AM/PM) or "21:00": top bars and the screensaver. */
    fun formatClock(ctx: Context, epochMs: Long = System.currentTimeMillis()): String =
        fmt(ctx, if (use24h(ctx)) "HH:mm" else "h:mm").format(epochMs)

    /** Days between today (in the chosen zone) and [epochMs]'s day: 0 = today, 1 = tomorrow, -1 = yesterday. */
    fun dayDelta(ctx: Context, epochMs: Long, now: Long = System.currentTimeMillis()): Int =
        (dayIndex(calendar(ctx, epochMs)) - dayIndex(calendar(ctx, now))).toInt()

    /** Today in the chosen zone as "yyyy-MM-dd". */
    fun todayYmd(ctx: Context, now: Long = System.currentTimeMillis()): String = fmt(ctx, "yyyy-MM-dd").format(now)

    private fun dayIndex(c: Calendar): Long {
        // A zone-independent day number from the calendar's own Y/M/D fields.
        val u = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        u.clear(); u.set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        return Math.floorDiv(u.timeInMillis, 86_400_000L)
    }

    /** "GMT−5" / "GMT+5:30" for [tz] right now (small secondary text only). */
    fun offsetText(tz: TimeZone, now: Long = System.currentTimeMillis()): String {
        val mins = tz.getOffset(now) / 60_000
        if (mins == 0) return "GMT"
        val sign = if (mins < 0) "−" else "+"
        val a = Math.abs(mins)
        return if (a % 60 == 0) "GMT$sign${a / 60}" else "GMT$sign${a / 60}:" + "%02d".format(a % 60)
    }

    // ------------------------------------------------------------- choices

    private val CURATED: List<Choice> = listOf(
        // United States
        Choice("America/New_York", "Eastern Time"),
        Choice("America/Chicago", "Central Time"),
        Choice("America/Denver", "Mountain Time"),
        Choice("America/Phoenix", "Arizona"),
        Choice("America/Los_Angeles", "Pacific Time"),
        Choice("America/Anchorage", "Alaska"),
        Choice("Pacific/Honolulu", "Hawaii"),
        Choice("America/Puerto_Rico", "Puerto Rico"),
        // Americas
        Choice("America/Halifax", "Atlantic Time (Halifax)"),
        Choice("America/St_Johns", "Newfoundland"),
        Choice("America/Toronto", "Toronto"),
        Choice("America/Vancouver", "Vancouver"),
        Choice("America/Mexico_City", "Mexico City"),
        Choice("America/Bogota", "Bogotá"),
        Choice("America/Lima", "Lima"),
        Choice("America/Santiago", "Santiago"),
        Choice("America/Sao_Paulo", "São Paulo"),
        Choice("America/Argentina/Buenos_Aires", "Buenos Aires"),
        // Europe & Africa
        Choice("Europe/London", "London"),
        Choice("Europe/Dublin", "Dublin"),
        Choice("Europe/Lisbon", "Lisbon"),
        Choice("Europe/Paris", "Paris"),
        Choice("Europe/Berlin", "Berlin"),
        Choice("Europe/Madrid", "Madrid"),
        Choice("Europe/Rome", "Rome"),
        Choice("Europe/Amsterdam", "Amsterdam"),
        Choice("Europe/Stockholm", "Stockholm"),
        Choice("Europe/Athens", "Athens"),
        Choice("Europe/Istanbul", "Istanbul"),
        Choice("Europe/Moscow", "Moscow"),
        Choice("Africa/Lagos", "Lagos"),
        Choice("Africa/Cairo", "Cairo"),
        Choice("Africa/Johannesburg", "Johannesburg"),
        Choice("Africa/Nairobi", "Nairobi"),
        // Asia & Pacific
        Choice("Asia/Dubai", "Dubai"),
        Choice("Asia/Karachi", "Karachi"),
        Choice("Asia/Kolkata", "India"),
        Choice("Asia/Bangkok", "Bangkok"),
        Choice("Asia/Singapore", "Singapore"),
        Choice("Asia/Manila", "Manila"),
        Choice("Asia/Shanghai", "China"),
        Choice("Asia/Hong_Kong", "Hong Kong"),
        Choice("Asia/Seoul", "Seoul"),
        Choice("Asia/Tokyo", "Tokyo"),
        Choice("Australia/Perth", "Perth"),
        Choice("Australia/Adelaide", "Adelaide"),
        Choice("Australia/Brisbane", "Brisbane"),
        Choice("Australia/Sydney", "Sydney"),
        Choice("Pacific/Auckland", "Auckland"),
        Choice("UTC", "Coordinated Universal Time"),
    )

    /** Region header for a curated id (used by the picker to group rows). */
    fun region(id: String): String = when {
        id in US_IDS -> "United States"
        id.startsWith("America/") -> "Americas"
        id.startsWith("Europe/") || id.startsWith("Africa/") -> "Europe & Africa"
        id == "UTC" -> "Other"
        else -> "Asia & Pacific"
    }
    private val US_IDS = setOf("America/New_York", "America/Chicago", "America/Denver", "America/Phoenix",
        "America/Los_Angeles", "America/Anchorage", "Pacific/Honolulu", "America/Puerto_Rico")

    /** Curated, friendly list (US first, then major world zones by region). */
    fun choices(): List<Choice> = CURATED

    /** Friendly name for a zone: "Eastern Time", "London", or the city part of the id as a fallback. */
    fun label(ctx: Context, zone: TimeZone): String = labelFor(zone.id)

    fun labelFor(id: String): String {
        CURATED.firstOrNull { it.id == id }?.let { return it.label }
        ALIASES[id]?.let { return labelFor(it) }
        // A US zone with the same rules as a curated one (e.g. America/Indiana/Knox -> Central Time). Only US
        // zones: Caracas or Hermosillo share a US zone's rules but aren't "Puerto Rico" or "Arizona".
        if (US_OTHER_PREFIXES.any { id.startsWith(it) } || id in US_OTHER) {
            val tz = TimeZone.getTimeZone(id)
            CURATED.firstOrNull { c ->
                val o = TimeZone.getTimeZone(c.id)
                c.id in US_IDS && o.rawOffset == tz.rawOffset && o.useDaylightTime() == tz.useDaylightTime()
            }?.let { return it.label }
        }
        return id.substringAfterLast('/').replace('_', ' ').ifBlank { "Local Time" }
    }
    private val US_OTHER_PREFIXES = listOf("America/Indiana/", "America/Kentucky/", "America/North_Dakota/")
    private val US_OTHER = setOf("America/Menominee", "America/Juneau", "America/Sitka", "America/Nome", "America/Yakutat",
        "America/Metlakatla")
    private val ALIASES = mapOf(
        "US/Eastern" to "America/New_York", "US/Central" to "America/Chicago", "US/Mountain" to "America/Denver",
        "US/Arizona" to "America/Phoenix", "US/Pacific" to "America/Los_Angeles", "US/Alaska" to "America/Anchorage",
        "US/Hawaii" to "Pacific/Honolulu", "America/Detroit" to "America/New_York", "America/Indiana/Indianapolis" to "America/New_York",
        "America/Kentucky/Louisville" to "America/New_York", "America/Boise" to "America/Denver", "Asia/Calcutta" to "Asia/Kolkata",
        "Etc/UTC" to "UTC", "GMT" to "UTC", "Europe/Kiev" to "Europe/Athens", "Europe/Kyiv" to "Europe/Athens",
    )

    /** "Automatic (Eastern Time)" or "Pacific Time". */
    fun summary(ctx: Context): String =
        if (isAutomatic(ctx)) "Automatic (${labelFor(detected().id)})" else labelFor(zone(ctx).id)

    /** "Automatic" / "12-Hour" / "24-Hour". */
    fun clockSummary(ctx: Context): String = when (override24h(ctx)) {
        null -> "Automatic (${if (use24h(ctx)) "24-Hour" else "12-Hour"})"
        true -> "24-Hour"
        false -> "12-Hour"
    }
}
