package com.firehome.launcher

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Upcoming episodes and seasons for the title page: TMDB lists announced episodes before they air (a
 * future air_date, or none yet) and new seasons before they start; TVmaze ([AirTimes.episodes]) adds the
 * exact local time and often the episode's real title before TMDB has it. Words here are what the page
 * shows: "Coming Friday at 9:00 PM", "Coming Nov 14", "Coming 2027", "Date to Be Announced".
 *
 * [split] blocks (TVmaze, only for a season that still has episodes to come): call it off the main thread.
 */
object Upcoming {
    private const val TAG = "FireHome"
    /** On an upcoming episode's (copied) JSON: its exact instant, epoch ms (0 = day only or unknown). */
    const val AT = "_at"
    private val PLACEHOLDER = Regex("^(episode|ep\\.?)\\s*#?\\d+$|^(tba|tbd|untitled)$", RegexOption.IGNORE_CASE)

    /** A season's episodes: the ones out (playable, unchanged) and the ones still to come (copies with [AT]). */
    class Split(val aired: List<JSONObject>, val upcoming: List<JSONObject>)

    /**
     * Splits TMDB season [season] of show [showId] ([eps] as TMDB lists them) into out and upcoming. Out:
     * past its exact instant when TVmaze has one, else on or after its air day (the rule before), or due within a few
     * days with a real release already in the index ([Availability.earlyRelease]). TVmaze is
     * asked only when some episode is dated today or later, or undated. Blocking.
     */
    fun split(ctx: Context, showId: Int, show: String, season: Int, eps: List<JSONObject>): Split {
        val now = System.currentTimeMillis()
        val today = ymd(now)
        val open = eps.filter { date(it).let { d -> d.length != 10 || d >= today } }
        if (open.isEmpty()) return Split(eps, emptyList())
        val maze = try { AirTimes.episodes(ctx, showId) } catch (e: Exception) { null }
        val aired = ArrayList<JSONObject>(eps.size)
        val coming = ArrayList<JSONObject>()
        for (ep in eps) {
            if (open.none { it === ep }) { aired += ep; continue }
            val num = ep.optInt("episode_number")
            val m = maze?.firstOrNull { it.season == season && it.number == num }
            val d = date(ep)
            val at = m?.at ?: 0L
            val out = if (at > 0) at <= now else d.length == 10 && d <= today
            // Due in a day or two but already up (a streaming service's early drop): the index has a real release.
            if (out || coming.isEmpty() && Availability.earlyRelease(ctx, showId, show, season, num, at, d)) { aired += ep; continue }
            val c = JSONObject(ep.toString()) // TMDB's objects are cached: never written to
            c.put(AT, at)
            if (d.isEmpty() && m != null && m.date.isNotEmpty()) c.put("air_date", m.date)
            if (title(c) == null && m != null && !isPlaceholder(m.name)) c.put("name", m.name)
            coming += c
        }
        coming.firstOrNull()?.let { c ->
            val at = c.optLong(AT)
            val whenAt = if (at > 0) "${AirTimes.stamp(at)} local (tvmaze)" else date(c).ifEmpty { "date unknown" } + " (date only${if (maze == null) "" else ", tvmaze"})"
            Log.w(TAG, "Upcoming: $show S${season}E${c.optInt("episode_number")}${title(c)?.let { " \"$it\"" } ?: ""} at $whenAt" +
                if (coming.size > 1) ", ${coming.size} to come this season" else "")
        }
        return Split(aired, coming)
    }

    /** An episode's air day "yyyy-MM-dd" ("" unknown). */
    fun date(ep: JSONObject): String = if (ep.isNull("air_date")) "" else ep.optString("air_date").takeIf { isDate(it) }.orEmpty()

    /** "yyyy-MM-dd" or a bare "yyyy" (never the text "null" a missing JSON date turns into). */
    fun isDate(s: String?) = s != null && (s.length == 10 || s.length == 4) && s[0].isDigit() && s[1].isDigit() && s[2].isDigit() && s[3].isDigit()

    /** The episode's real title, or null while it has only a stand-in ("Episode 7", "TBA"). */
    fun title(ep: JSONObject): String? = ep.optString("name").trim().takeIf { !isPlaceholder(it) }

    fun isPlaceholder(s: String?): Boolean = s.isNullOrBlank() || s == "null" || PLACEHOLDER.matches(s.trim())

    // ---- words ----

    /** When it comes, as a viewer says it ("Friday at 9:00 PM", "Nov 14", "2027"); null when unknown. */
    fun whenWords(at: Long, date: String): String? = when {
        at > 0 -> AirTimes.label(at)
        date.length == 10 -> dayWords(date)
        date.length == 4 -> date
        else -> null
    }

    /** "Coming Friday at 9:00 PM", "Coming Nov 14", "Coming 2027", else "Date to Be Announced". */
    fun coming(at: Long, date: String): String = whenWords(at, date)?.let { "Coming $it" } ?: TBA

    const val TBA = "Date to Be Announced"

    /** "Today", "Tomorrow", "Friday" (this week), else "Nov 14" ("Nov 14, 2027" past this year). */
    fun dayWords(date: String): String = when (val d = NewEpisodes.daysFromToday(date)) {
        0 -> "Today"
        1 -> "Tomorrow"
        in 2..6 -> try { SimpleDateFormat("EEEE", Locale.US).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!) } catch (e: Exception) { date }
        else -> Availability.dateWords(date)
    }

    /** "March 4" ("March 4, 2027" past this year); a bare year as is. */
    fun longDate(date: String): String {
        if (date.length == 4) return date
        return try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!
            val year = SimpleDateFormat("yyyy", Locale.US)
            SimpleDateFormat(if (year.format(d) == DreamTime.fmt("yyyy").format(Date())) "MMMM d" else "MMMM d, yyyy", Locale.US).format(d)
        } catch (e: Exception) { date }
    }

    // ---- seasons ----

    /** A season that hasn't started: no episodes yet, no premiere day, or a premiere day ahead. */
    fun seasonAhead(s: Season, today: String = ymd(System.currentTimeMillis())): Boolean =
        s.episodes == 0 || s.airDate.length != 10 || s.airDate > today

    /** A season chip's small line: "Coming Mar 4" (within half a year), "Coming 2027", or "Upcoming". */
    fun chipWords(s: Season): String {
        val d = s.airDate.takeIf { isDate(it) } ?: return "Upcoming" // also a cached "null" from before the fix
        if (d.length == 4) return "Coming $d"
        val days = NewEpisodes.daysFromToday(d)
        return if (days <= 183) "Coming " + Availability.dateWords(d).substringBefore(",") else "Coming ${d.take(4)}"
    }

    /** "Coming March 4, 2027" or "Date to Be Announced". */
    fun seasonWords(s: Season): String = s.airDate.takeIf { isDate(it) }?.let { "Coming " + longDate(it) } ?: TBA

    private fun ymd(t: Long): String = DreamTime.fmt("yyyy-MM-dd").format(Date(t))
}
