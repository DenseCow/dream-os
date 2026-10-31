package com.dreamos.desktop.core.time

import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.*
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Dream OS time zone + clock format (fire-home DreamTime.kt, with the iPhone's date-only helpers). Stored per PC, not
 * per profile. Every displayed time and every "Today / Tomorrow / weekday" decision goes through here.
 *
 * Two kinds of values:
 * - **Instants** (epoch ms): worded in the chosen zone ([formatTime], [format], [dayDelta], [airLabel]).
 * - **Date-only TMDB days** ("yyyy-MM-dd"): the date is the date, never shifted by a zone ([formatDay], [dateWords],
 *   [dayWords], [daysFromToday]).
 *
 * Page dates (daily pages, "new" windows, year floors) are computed per call, never held from class load: the app can
 * run for weeks. Month and day names are US English, like the other apps.
 */
object DreamTime {
    data class Choice(val id: String, val label: String)

    private const val FILE = "dream_time"
    private const val K_ZONE = "zone"     // absent = Automatic
    private const val K_24H = "clock24"   // absent = follow the PC; "1" / "0"

    private val us = Locale.US
    private val _version = MutableStateFlow(0L)

    /** Bumps after the zone or the clock format changes (clocks and dates on screen redraw). */
    val version: StateFlow<Long> get() = _version

    /** Tests pin "now" here (epoch ms); null = the real clock. */
    @Volatile var fixedNow: Long? = null
    fun now(): Long = fixedNow ?: System.currentTimeMillis()

    private fun prefs() = DreamStore.shared.file(FILE)

    // ---- zone ----

    /** The PC's zone (what "Automatic" follows). */
    fun detected(): ZoneId = ZoneId.systemDefault()

    private fun storedZoneId(): String? = prefs().getString(K_ZONE)

    val isAutomatic: Boolean get() = storedZoneId() == null

    /** The viewer's pick, or the PC's zone when Automatic. */
    val zone: ZoneId
        get() = storedZoneId()?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: detected()

    /** null = Automatic. */
    fun setZone(zoneId: String?) {
        val clean = zoneId?.takeIf { it.isNotBlank() && runCatching { ZoneId.of(it) }.isSuccess }
        prefs().putString(K_ZONE, clean)
        Log.i("Time", "zone set to " + (clean?.let { "$it (manual)" } ?: "${detected().id} (automatic)"))
        _version.value++
    }

    // ---- clock format ----

    /** null = follow the PC, true/false = forced. */
    val override24h: Boolean? get() = prefs().getString(K_24H)?.let { it == "1" }

    /** The PC's own clock format (Windows region settings, as the JVM reads them). */
    val device24h: Boolean
        get() = runCatching {
            DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, FormatStyle.SHORT, IsoChronology.INSTANCE, Locale.getDefault())
                .let { 'H' in it || 'k' in it }
        }.getOrDefault(false)

    val use24h: Boolean get() = override24h ?: device24h

    /** null = follow the PC. */
    fun set24h(on: Boolean?) {
        prefs().putString(K_24H, on?.let { if (it) "1" else "0" })
        Log.i("Time", "clock format " + when (on) { null -> "automatic"; true -> "24-hour"; false -> "12-hour" })
        _version.value++
    }

    // ---- instants (worded in the chosen zone) ----

    private val formatters = ConcurrentHashMap<String, DateTimeFormatter>()

    /** A formatter for [pattern] (US English names) in [z] (the chosen zone by default). Shared; about 20 patterns. */
    fun formatter(pattern: String, z: ZoneId = zone): DateTimeFormatter {
        if (formatters.size > 128) formatters.clear()
        return formatters.computeIfAbsent(pattern + "|" + z.id) { DateTimeFormatter.ofPattern(pattern, us).withZone(z) }
    }

    /** [epochMs] in [pattern] in the chosen zone: `DreamTime.format(t, "MMM d")`. */
    fun format(epochMs: Long, pattern: String): String = formatter(pattern).format(Instant.ofEpochMilli(epochMs))

    fun zoned(epochMs: Long = now()): ZonedDateTime = Instant.ofEpochMilli(epochMs).atZone(zone)

    /** "9:00 PM" / "21:00" in the chosen zone. */
    fun formatTime(epochMs: Long): String = formatTimeIn(epochMs, zone)

    /** Same as [formatTime] in an explicit zone (the picker previews each zone). */
    fun formatTimeIn(epochMs: Long, z: ZoneId): String =
        formatter(if (use24h) "HH:mm" else "h:mm a", z).format(Instant.ofEpochMilli(epochMs))

    /** A big clock face: "9:00" (12-hour, no AM/PM) or "21:00". */
    fun formatClock(epochMs: Long = now()): String = format(epochMs, if (use24h) "HH:mm" else "h:mm")

    /** "9 PM" style: drops ":00" on the hour in 12-hour mode. */
    fun formatTimeShort(epochMs: Long): String {
        if (use24h) return formatTime(epochMs)
        return if (zoned(epochMs).minute == 0) format(epochMs, "h a") else format(epochMs, "h:mm a")
    }

    /** Hour of day (0-23) now in the chosen zone (greetings, Tonight vs Today). */
    fun hourNow(): Int = zoned().hour

    /** Today in the chosen zone, "yyyy-MM-dd". */
    fun todayYmd(nowMs: Long = now()): String = ymd(nowMs)

    /** [epochMs]'s day in the chosen zone, "yyyy-MM-dd". */
    fun ymd(epochMs: Long): String = zoned(epochMs).toLocalDate().toString()

    /** "yyyy-MM-dd" for today + [offsetDays] in the chosen zone (calendar days, DST-safe). */
    fun ymdPlus(offsetDays: Int, nowMs: Long = now()): String = zoned(nowMs).toLocalDate().plusDays(offsetDays.toLong()).toString()

    /** "yyyy-MM-dd" [years] ago today (RowMix.yearsAgo). */
    fun yearsAgo(years: Int, nowMs: Long = now()): String = zoned(nowMs).toLocalDate().minusYears(years.toLong()).toString()

    /** Days between today (chosen zone) and [epochMs]'s day: 0 today, 1 tomorrow, -1 yesterday. */
    fun dayDelta(epochMs: Long, nowMs: Long = now()): Int =
        ChronoUnit.DAYS.between(zoned(nowMs).toLocalDate(), zoned(epochMs).toLocalDate()).toInt()

    /** The start of a date-only day in the chosen zone (epoch ms); null when unreadable. */
    fun midnight(ymd: String): Long? = localDate(ymd)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()

    /** The chosen zone's current year. */
    fun thisYear(nowMs: Long = now()): Int = zoned(nowMs).year

    // ---- date-only days ("yyyy-MM-dd": never shifted) ----

    /** The day of "yyyy-MM-dd" (longer strings: their first 10 characters); null when unreadable. */
    fun localDate(ymd: String?): LocalDate? {
        if (ymd == null || ymd.length < 10) return null
        return runCatching { LocalDate.parse(ymd.substring(0, 10)) }.getOrNull()
    }

    /** Whole days from today (chosen zone) to [ymd]: 0 today, -1 yesterday, 1 tomorrow; null when unreadable. */
    fun daysFromToday(ymd: String?, nowMs: Long = now()): Int? {
        val d = localDate(ymd) ?: return null
        return ChronoUnit.DAYS.between(zoned(nowMs).toLocalDate(), d).toInt()
    }

    /** [ymd] worded with [pattern] ("MMM d", "EEEE", "MMMM d, yyyy"), with no zone involved; null when unreadable. */
    fun formatDay(ymd: String?, pattern: String): String? =
        localDate(ymd)?.let { DateTimeFormatter.ofPattern(pattern, us).format(it) }

    /** "Nov 14" ("Nov 14, 2027" past this year) for a date-only day. The input when unreadable. */
    fun dateWords(ymd: String, nowMs: Long = now()): String {
        val d = localDate(ymd) ?: return ymd
        return formatDay(ymd, if (d.year == thisYear(nowMs)) "MMM d" else "MMM d, yyyy") ?: ymd
    }

    /** "March 1, 2024" for a date-only day; null when unreadable. */
    fun longDate(ymd: String?): String? = formatDay(ymd, "MMMM d, yyyy")

    /** "March 4" ("March 4, 2027" past this year); a bare year as is. */
    fun longDateShort(ymd: String, nowMs: Long = now()): String {
        if (ymd.length == 4) return ymd
        val d = localDate(ymd) ?: return ymd
        return formatDay(ymd, if (d.year == thisYear(nowMs)) "MMMM d" else "MMMM d, yyyy") ?: ymd
    }

    /** "Nov 14", "" when unreadable. */
    fun shortDate(ymd: String): String = formatDay(ymd, "MMM d").orEmpty()

    /** "Today", "Tomorrow", "Friday" (this week), else "Nov 14" ("Nov 14, 2027" past this year). */
    fun dayWords(ymd: String, nowMs: Long = now()): String = when (daysFromToday(ymd, nowMs)) {
        0 -> "Today"
        1 -> "Tomorrow"
        in 2..6 -> formatDay(ymd, "EEEE") ?: ymd
        else -> dateWords(ymd, nowMs)
    }

    /** True when the date-only [ymd] is after today (chosen zone). */
    fun isAhead(ymd: String?, nowMs: Long = now()): Boolean =
        ymd != null && ymd.length >= 10 && ymd.substring(0, 10) > todayYmd(nowMs)

    // ---- instants as a viewer says them ----

    /** "Tonight at 9:00 PM" (today, from 5 PM), "Today at 3:00 PM", "Tomorrow at 3:00 AM", "Friday at 9:00 PM", else "Nov 14 at 9:00 PM". */
    fun airLabel(atMs: Long, nowMs: Long = now()): String {
        val time = formatTime(atMs)
        return when (dayDelta(atMs, nowMs)) {
            0 -> (if (zoned(atMs).hour >= 17) "Tonight" else "Today") + " at $time"
            1 -> "Tomorrow at $time"
            in 2..6 -> format(atMs, "EEEE") + " at $time"
            else -> format(atMs, "MMM d") + " at $time"
        }
    }

    // ---- zone names ----

    /** "GMT−5" / "GMT+5:30" for [z] right now (small secondary text only). */
    fun offsetText(z: ZoneId, nowMs: Long = now()): String {
        val mins = z.rules.getOffset(Instant.ofEpochMilli(nowMs)).totalSeconds / 60
        if (mins == 0) return "GMT"
        val sign = if (mins < 0) "−" else "+"
        val a = abs(mins)
        return if (a % 60 == 0) "GMT$sign${a / 60}" else "GMT$sign${a / 60}:" + "%02d".format(a % 60)
    }

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

    private val US_IDS = setOf("America/New_York", "America/Chicago", "America/Denver", "America/Phoenix",
        "America/Los_Angeles", "America/Anchorage", "Pacific/Honolulu", "America/Puerto_Rico")
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

    /** Curated, friendly list (US first, then major world zones by region). */
    fun choices(): List<Choice> = CURATED

    /** Region header for a curated id (the picker groups rows by it). */
    fun region(id: String): String = when {
        id in US_IDS -> "United States"
        id.startsWith("America/") -> "Americas"
        id.startsWith("Europe/") || id.startsWith("Africa/") -> "Europe & Africa"
        id == "UTC" -> "Other"
        else -> "Asia & Pacific"
    }

    /** Friendly name for a zone: "Eastern Time", "London", or the city part of the id as a fallback. */
    fun labelFor(id: String): String {
        CURATED.firstOrNull { it.id == id }?.let { return it.label }
        ALIASES[id]?.let { return labelFor(it) }
        if (US_OTHER_PREFIXES.any { id.startsWith(it) } || id in US_OTHER) {
            val tz = runCatching { ZoneId.of(id) }.getOrNull()
            if (tz != null) {
                val now = Instant.ofEpochMilli(now())
                CURATED.firstOrNull { c ->
                    val o = ZoneId.of(c.id)
                    c.id in US_IDS && o.rules.getStandardOffset(now) == tz.rules.getStandardOffset(now) &&
                        (o.rules.nextTransition(now) != null) == (tz.rules.nextTransition(now) != null)
                }?.let { return it.label }
            }
        }
        return id.substringAfterLast('/').replace('_', ' ').ifBlank { "Local Time" }
    }

    /** "Automatic (Eastern Time)" or "Pacific Time". */
    fun summary(): String = if (isAutomatic) "Automatic (${labelFor(detected().id)})" else labelFor(zone.id)

    /** "Automatic (12-Hour)" / "12-Hour" / "24-Hour". */
    fun clockSummary(): String = when (override24h) {
        null -> "Automatic (${if (use24h) "24-Hour" else "12-Hour"})"
        true -> "24-Hour"
        false -> "12-Hour"
    }
}
