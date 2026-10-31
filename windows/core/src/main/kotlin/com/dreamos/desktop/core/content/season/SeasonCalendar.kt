package com.dreamos.desktop.core.content

/*
 * Seasonal & event hubs, the calendar half (fire-home SeasonCalendar.kt): plain Kotlin, so the whole year can be
 * checked in tests ([SeasonCalendar.selfCheck]). The hubs themselves are [SeasonCatalog]; which are on for this
 * profile, their tiles and the TMDB loading are SeasonHub.kt (drawing is the UI's).
 */

/** A calendar day (proleptic Gregorian, no time zone): the season system's clock. */
class SDay private constructor(val epoch: Long) : Comparable<SDay> {
    val y: Int
    val m: Int
    val d: Int

    init {
        // Howard Hinnant's civil_from_days.
        val z = epoch + 719468
        val era = Math.floorDiv(z, 146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        y = (yoe + era * 400).toInt() + if (m <= 2) 1 else 0
    }

    /** Month and day as one number (October 26 = 1026), for fixed windows. */
    val md: Int get() = m * 100 + d

    /** ISO weekday: 1 Monday … 7 Sunday (1970-01-01 was a Thursday). */
    val dow: Int get() = (Math.floorMod(epoch + 3, 7L) + 1).toInt()

    operator fun plus(days: Int) = SDay(epoch + days)
    operator fun minus(days: Int) = SDay(epoch - days)

    /** Days from [o] to this day (positive when this is later). */
    fun since(o: SDay): Int = (epoch - o.epoch).toInt()

    override fun compareTo(other: SDay) = epoch.compareTo(other.epoch)
    override fun equals(other: Any?) = other is SDay && other.epoch == epoch
    override fun hashCode() = epoch.hashCode()
    override fun toString() = String.format(java.util.Locale.US, "%04d-%02d-%02d", y, m, d)

    companion object {
        fun of(y: Int, m: Int, d: Int): SDay {
            // Howard Hinnant's days_from_civil.
            val yy = if (m <= 2) y - 1 else y
            val era = Math.floorDiv(yy, 400)
            val yoe = yy - era * 400
            val mp = (m + 9) % 12
            val doy = (153 * mp + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return SDay(era * 146097L + doe - 719468)
        }

        /** "yyyy-MM-dd", or null. */
        fun parse(s: String?): SDay? {
            val p = s?.trim()?.split('-')?.mapNotNull { it.toIntOrNull() } ?: return null
            if (p.size != 3 || p[1] !in 1..12 || p[2] !in 1..31) return null
            return of(p[0], p[1], p[2]).takeIf { it.m == p[1] }
        }

        fun lengthOfMonth(y: Int, m: Int) = (if (m == 12) of(y + 1, 1, 1) else of(y, m + 1, 1)).since(of(y, m, 1))

        /** The [n]th [dow] (1 Monday … 7 Sunday) of month [m]: Thanksgiving is the 4th Thursday of November. */
        fun nthWeekday(y: Int, m: Int, dow: Int, n: Int): SDay {
            val first = of(y, m, 1)
            return first + (Math.floorMod(dow - first.dow, 7) + (n - 1) * 7)
        }

        /** Western (Gregorian) Easter Sunday: the anonymous Gregorian algorithm (Meeus/Jones/Butcher). */
        fun easter(y: Int): SDay {
            val a = y % 19
            val b = y / 100
            val c = y % 100
            val d = b / 4
            val e = b % 4
            val f = (b + 8) / 25
            val g = (b - f + 1) / 3
            val h = (19 * a + b - d - g + 15) % 30
            val i = c / 4
            val k = c % 4
            val l = (32 + 2 * e + 2 * i - h - k) % 7
            val mm = (a + 11 * h + 22 * l) / 451
            val month = (h + l - 7 * mm + 114) / 31
            val day = (h + l - 7 * mm + 114) % 31 + 1
            return of(y, month, day)
        }

        fun thanksgiving(y: Int) = nthWeekday(y, 11, 4, 4)
        fun mothersDay(y: Int) = nthWeekday(y, 5, 7, 2)
        fun fathersDay(y: Int) = nthWeekday(y, 6, 7, 3)

        /**
         * The Academy Awards ceremony: the dates the Academy has announced, else the second Sunday of March
         * (where recent ceremonies have mostly landed). Only the Awards Season hub's last day and its countdown
         * depend on it; add a year here once its date is announced.
         */
        fun awardsNight(y: Int): SDay = when (y) {
            2025 -> of(2025, 3, 2)
            2026 -> of(2026, 3, 15)
            2027 -> of(2027, 3, 14)
            else -> nthWeekday(y, 3, 7, 2)
        }
    }
}

/** When a hub is on: [contains] says for any day; [range] gives its days in a year (logs, the self-check). */
class SeasonWindow(val contains: (SDay) -> Boolean, private val first: (Int) -> SDay?) {
    /** Where the window that's on (or next) in year [y] starts. */
    fun start(y: Int): SDay? = first(y)

    companion object {
        /** Month-day to month-day, inclusive (0101 = January 1st); may run over New Year (1227 to 0102). Feb 29 is fine as an end. */
        fun fixed(from: Int, to: Int) = SeasonWindow({ d ->
            if (from <= to) d.md in from..to else d.md >= from || d.md <= to
        }, { y -> SDay.of(y, from / 100, from % 100) })

        /** [before] days before a computed day through [after] days after it (Easter, Thanksgiving). */
        fun around(anchor: (Int) -> SDay, before: Int, after: Int) = SeasonWindow({ d ->
            (d.y - 1..d.y + 1).any { yy -> val a = anchor(yy); d >= a - before && d <= a + after }
        }, { y -> anchor(y) - before })

        /** From a fixed month-day through a computed day of the same year (Awards Season: Feb 15 to Awards Night). */
        fun until(from: Int, end: (Int) -> SDay) = SeasonWindow({ d ->
            d >= SDay.of(d.y, from / 100, from % 100) && d <= end(d.y)
        }, { y -> SDay.of(y, from / 100, from % 100) })
    }
}

/**
 * A hub's countdown: "5 Nights Until Halloween", then [greeting] on the day ("Happy Halloween"). [unit] is
 * "Night" or "Day"; [until] reads after "Until" ("Halloween", "the New Year"). [eve] replaces the "1 Day"
 * line; [after] shows on the window's days after the target (else [greeting]).
 */
class Countdown(
    val target: (Int) -> SDay,
    val unit: String,
    val until: String,
    val greeting: String,
    val eve: String? = null,
    val after: String? = null,
) {
    /** The target this countdown is about on [today] (this year's, or next year's once this year's is a week gone). */
    fun next(today: SDay): SDay = target(today.y).let { if (it < today - GRACE) target(today.y + 1) else it }

    fun daysTo(today: SDay): Int = next(today).since(today)

    fun text(today: SDay): String {
        val n = daysTo(today)
        return when {
            n > 1 -> "$n ${unit}s Until $until"
            n == 1 -> eve ?: "1 $unit Until $until"
            n == 0 -> greeting
            else -> after ?: greeting
        }
    }

    private companion object { const val GRACE = 7 }
}

/**
 * One row of a hub. [movie] and [tv] are TMDB Discover filters for each type (null: none of that type), with
 * "{LASTYEAR}" (the year the hub looks back on), "{YEARSAGO:n}" ("yyyy-MM-dd" n years ago) filled in when it
 * loads. [kind] is how it's filled; [ids] are a [Kind.PICKS] row's titles ("movie:603"). [kids]: safe for a kids
 * profile (only these rows show there). [genre]: a TMDB movie genre whose trending titles lead a [Kind.TRENDING] row.
 */
class SeasonRow(
    val title: String,
    val kind: Kind,
    val movie: String?,
    val tv: String? = null,
    val kids: Boolean = false,
    val ids: List<String> = emptyList(),
    val genre: Int = 0,
) {
    enum class Kind {
        /** What's hot: TMDB's trending titles of [genre], topped up from the last two years' most popular. */
        TRENDING,
        /** Released in the last year, most popular first. */
        NEW,
        /** Popular now alternating with long-time favorites (RowMix.variety). */
        VARIETY,
        /** The filter as asked (its own sort_by, else most popular). */
        POPULAR,
        /** The most voted of all: the classics everyone knows. */
        TOP_VOTED,
        /** Recent, highly rated and widely seen (RowMix.acclaimed). */
        ACCLAIMED,
        /** A hand-picked list of titles ([ids]), in order. */
        PICKS,
    }
}

/**
 * A seasonal or event hub: Halloween's "31 Nights of Horror", Christmas, Pride, a base season. [priority]
 * decides between hubs that are on at once (a holiday beats its base season; [boost] more within [boostDays]
 * of the countdown's target, so Valentine's week wins over Black History Month). [base]: one of the four
 * seasons, so some hub is always on. [glyph]: the id of its line-art drawing (the UI draws it); [hue]: the rainbow hue the glyph is
 * tinted from. [art]: a title ("movie:948") whose backdrop is the hub's art, else Tonight's Pick's. [pick]:
 * the pool Tonight's Pick comes from (one a day, the same all day), [kidsPick] for a kids profile.
 * [genres]: Browse genre pages that link here while it's on. [nights]: Halloween's "Night 26 of 31".
 */
class SeasonalHub(
    val id: String,
    val title: String,
    val kicker: String,
    val tagline: String,
    val glyph: String,
    val hue: Int,
    val priority: Int,
    val window: SeasonWindow,
    val rows: List<SeasonRow>,
    val pick: SeasonRow,
    val kidsPick: SeasonRow? = null,
    val countdown: Countdown? = null,
    val boostDays: Int = 0,
    val boost: Int = 0,
    val base: Boolean = false,
    val art: String? = null,
    val genres: Set<String> = emptySet(),
    val nights: Boolean = false,
) {
    fun on(today: SDay) = window.contains(today)

    fun priorityOn(today: SDay): Int {
        val c = countdown ?: return priority
        return priority + if (boost > 0 && c.daysTo(today) in 0..boostDays) boost else 0
    }

    /** The rows a profile sees: a kids profile only the kids-safe ones. */
    fun rowsFor(kids: Boolean) = if (kids) rows.filter { it.kids } else rows

    /** Shown to a kids profile at all: it has a row for them. */
    fun forKids() = rows.any { it.kids }

    /** The line under the title: the countdown when there is one, else the tagline. */
    fun status(today: SDay): String = countdown?.text(today) ?: tagline

    /** "Night 26 of 31" (Halloween), else null. */
    fun night(today: SDay): String? {
        if (!nights || !on(today)) return null
        var start = today
        while (on(start - 1) && today.since(start) < 62) start -= 1
        var end = today
        while (on(end + 1) && end.since(today) < 62) end += 1
        return "Night ${today.since(start) + 1} of ${end.since(start) + 1}"
    }
}

/** Which hub is on: the highest-priority one whose window holds the day; every day has one (a base season). */
object SeasonCalendar {
    /** Every hub that is on, highest priority first (a kids profile: only the hubs with rows for kids). */
    fun active(today: SDay, kids: Boolean = false, all: List<SeasonalHub> = SeasonCatalog.ALL): List<SeasonalHub> =
        all.filter { it.on(today) && (!kids || it.forKids()) }.sortedWith(compareByDescending<SeasonalHub> { it.priorityOn(today) }.thenBy { if (it.base) 1 else 0 })

    fun top(today: SDay, kids: Boolean = false): SeasonalHub = active(today, kids).firstOrNull() ?: SeasonCatalog.ALL.first { it.base }

    fun find(id: String?): SeasonalHub? = id?.let { i -> SeasonCatalog.ALL.firstOrNull { it.id.equals(i, ignoreCase = true) } }

    private val YEARS_AGO = Regex("\\{YEARSAGO:(\\d+)\\}")

    /**
     * [q] (a row's filter or title) with its placeholders filled for hub [s] on [today]: "{LASTYEAR}" is the year
     * the hub looks back on (the one before its countdown's target: New Year's and Awards Season look back on
     * the year just gone), "{YEARSAGO:n}" the day n years ago ("yyyy-MM-dd").
     */
    fun fill(q: String, s: SeasonalHub, today: SDay): String {
        if (!q.contains('{')) return q
        val last = ((s.countdown?.next(today) ?: today).y - 1).toString()
        return YEARS_AGO.replace(q.replace("{LASTYEAR}", last)) { m ->
            val y = today.y - m.groupValues[1].toInt()
            SDay.of(y, today.m, minOf(today.d, SDay.lengthOfMonth(y, today.m))).toString()
        }
    }

    /** "halloween 10-01..10-31, winter 12-01..02-28": each hub's days in [year] as ranges (logs, the report). */
    fun describe(year: Int, all: List<SeasonalHub> = SeasonCatalog.ALL): List<String> = all.map { s ->
        val ranges = ArrayList<String>()
        var d = SDay.of(year, 1, 1)
        val end = SDay.of(year, 12, 31)
        var from: SDay? = null
        while (d <= end) {
            val on = s.on(d)
            if (on && from == null) from = d
            if ((!on || d == end) && from != null) {
                val to = if (on) d else d - 1
                ranges += "${fmt(from)}..${fmt(to)}"
                from = null
            }
            d += 1
        }
        "${s.id} ${ranges.joinToString(", ")}"
    }

    private fun fmt(d: SDay) = String.format(java.util.Locale.US, "%02d-%02d", d.m, d.d)

    /**
     * The calendar's own test: every day of [years] has exactly one base season on and a hub on top (for adults
     * and for kids), every hub is on at least once, and the computed days are right for 2026 and 2027.
     * Returns the problems (empty: all good) and a summary line.
     */
    fun selfCheck(years: IntRange = 2026..2027): Pair<List<String>, String> {
        val bad = ArrayList<String>()
        val expect = mapOf(
            "Easter 2026" to (SDay.easter(2026) to SDay.of(2026, 4, 5)),
            "Easter 2027" to (SDay.easter(2027) to SDay.of(2027, 3, 28)),
            "Mother's Day 2026" to (SDay.mothersDay(2026) to SDay.of(2026, 5, 10)),
            "Mother's Day 2027" to (SDay.mothersDay(2027) to SDay.of(2027, 5, 9)),
            "Father's Day 2026" to (SDay.fathersDay(2026) to SDay.of(2026, 6, 21)),
            "Father's Day 2027" to (SDay.fathersDay(2027) to SDay.of(2027, 6, 20)),
            "Thanksgiving 2026" to (SDay.thanksgiving(2026) to SDay.of(2026, 11, 26)),
            "Thanksgiving 2027" to (SDay.thanksgiving(2027) to SDay.of(2027, 11, 25)),
            "Easter 2024" to (SDay.easter(2024) to SDay.of(2024, 3, 31)),
            "Easter 2025" to (SDay.easter(2025) to SDay.of(2025, 4, 20)),
        )
        for ((name, p) in expect) if (p.first != p.second) bad += "$name is ${p.first}, expected ${p.second}"
        if (SDay.of(2026, 10, 26).dow != 1) bad += "2026-10-26 should be a Monday"
        val seen = HashSet<String>()
        for (y in years) {
            var d = SDay.of(y, 1, 1)
            while (d.y == y) {
                val bases = SeasonCatalog.ALL.count { it.base && it.on(d) }
                if (bases != 1) bad += "$d has $bases base seasons"
                val on = active(d)
                if (on.isEmpty()) bad += "$d has no hub" else seen += on.first().id
                if (active(d, kids = true).isEmpty()) bad += "$d has no hub for kids"
                on.firstOrNull()?.let { s -> if (s.rows.size < 5) bad += "${s.id} has only ${s.rows.size} rows" }
                d += 1
            }
        }
        for (s in SeasonCatalog.ALL) if (s.id !in seen) bad += "${s.id} never leads in $years"
        val ids = SeasonCatalog.ALL.map { it.id }
        if (ids.size != ids.toSet().size) bad += "duplicate hub ids"
        val summary = "${SeasonCatalog.ALL.size} hubs, ${years.first}-${years.last} every day covered, " +
            "Easter ${SDay.easter(2026)}/${SDay.easter(2027)}, Mother's Day ${SDay.mothersDay(2026)}/${SDay.mothersDay(2027)}, " +
            "Father's Day ${SDay.fathersDay(2026)}/${SDay.fathersDay(2027)}, Thanksgiving ${SDay.thanksgiving(2026)}/${SDay.thanksgiving(2027)}, " +
            "Awards Night ${SDay.awardsNight(2026)}/${SDay.awardsNight(2027)}"
        return bad to summary
    }
}
