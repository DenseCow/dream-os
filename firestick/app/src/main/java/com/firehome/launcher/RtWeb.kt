package com.firehome.launcher

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Rotten Tomatoes critic score from rottentomatoes.com itself: the fallback for titles OMDb knows
 * but has no Tomatometer for: on a details page, and for series on the hero after ~1.5 s on screen
 * (never from the bulk prefetch), see [Ratings.loadRtWeb].
 *
 * One request to the search page, whose results are `<search-page-result type="movie|tvSeries">`
 * sections of `<search-page-media-row release-year / start-year / tomatometer-score …>` rows (the
 * attribute names come with and without hyphens). The row must match the title (normalized) and
 * year (±1) in the right section; a match without a score costs one more request, for the title
 * page's `"criticsScore":{… "score":"97"}` JSON. At most [DAILY] lookups a day, 6 s timeouts, no
 * retries, one try per title per session. Any change in the markup just finds nothing.
 */
object RtWeb {
    private const val TAG = "RtWeb"
    private const val DAILY = 80
    private const val TIMEOUT = 6000
    private const val SITE = "https://www.rottentomatoes.com/"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    /** A finished lookup: [score] null is a miss (no match, ambiguous, or no score). */
    class Result(val score: Int?)

    private val tried = HashSet<String>()

    /** Not yet tried this session. */
    fun mayTry(t: Title) = synchronized(tried) { t.key !in tried }

    /** Blocking. The lookup's [Result], or null when it wasn't made or failed (nothing to cache). */
    fun lookup(ctx: Context, t: Title): Result? {
        synchronized(tried) { if (!tried.add(t.key)) return null }
        if (t.name.isBlank() || !spend(ctx)) return null
        val started = SystemClock.uptimeMillis()
        val result = try {
            find(t)
        } catch (e: Exception) {
            Log.w(TAG, "rt web: \"${t.name}\" failed after ${SystemClock.uptimeMillis() - started} ms: ${e.javaClass.simpleName}")
            return null
        }
        Log.w(TAG, "rt web: \"${t.name}\" (${t.year}) ${result.score?.let { "found $it%" } ?: "miss"}, ${SystemClock.uptimeMillis() - started} ms")
        return result
    }

    private fun find(t: Title): Result {
        val html = get(SITE + "search?search=" + URLEncoder.encode(t.name, "UTF-8"))
        val section = if (t.type == "tv") "tvSeries" else "movie"
        val want = norm(t.name)
        val year = t.year.toIntOrNull()
        val matches = ArrayList<Pair<Int?, String?>>() // (score, page url)
        val exactYear = ArrayList<Pair<Int?, String?>>()
        val starts = Regex("<search-page-result\\b").findAll(html).map { it.range.first }.toList()
        for ((i, s) in starts.withIndex()) {
            val chunk = html.substring(s, if (i + 1 < starts.size) starts[i + 1] else html.length)
            val open = chunk.substring(0, chunk.indexOf('>').takeIf { it > 0 } ?: continue)
            if (attrs(open)["type"] != section) continue
            for (m in Regex("<search-page-media-row\\b([^>]*)>(.*?)</search-page-media-row>", RegexOption.DOT_MATCHES_ALL).findAll(chunk)) {
                val a = attrs(m.groupValues[1])
                val anchor = Regex("<a\\b([^>]*\\bslot=\"title\"[^>]*)>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(m.groupValues[2]) ?: continue
                if (norm(unescape(anchor.groupValues[2].replace(Regex("<[^>]*>"), ""))) != want) continue
                val y = (if (t.type == "tv") a["startyear"] else a["releaseyear"])?.toIntOrNull()
                if (year != null && (y == null || Math.abs(y - year) > 1)) continue
                val href = attrs(anchor.groupValues[1])["href"]?.takeIf { it.startsWith(SITE) }
                val row = a["tomatometerscore"]?.toIntOrNull()?.takeIf { it in 0..100 } to href
                matches += row
                if (y == year) exactYear += row
            }
        }
        // One match, or one with the exact year among several; anything else is ambiguous.
        val pick = when { matches.size == 1 -> matches[0]; exactYear.size == 1 -> exactYear[0]; else -> return Result(null) }
        pick.first?.let { return Result(it) }
        val page = pick.second ?: return Result(null)
        val score = Regex("\"criticsScore\":\\{[^{}]*?\"score\":\"(\\d{1,3})\"").find(get(page))?.groupValues?.get(1)?.toIntOrNull()
        return Result(score?.takeIf { it in 0..100 })
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT
        conn.readTimeout = TIMEOUT
        conn.useCaches = false
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Attributes of an opening tag, names lowercased without hyphens ("release-year" = "releaseyear"). */
    private fun attrs(tag: String): Map<String, String> =
        Regex("([A-Za-z][\\w-]*)=\"([^\"]*)\"").findAll(tag).associate { it.groupValues[1].replace("-", "").lowercase() to it.groupValues[2].trim() }

    /** Case, punctuation, "&"/"and" and a leading "The" don't matter. */
    private fun norm(s: String): String = s.lowercase()
        .replace("&", " and ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .removePrefix("the ")
        .replace(" ", "")

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").trim()

    private fun spend(ctx: Context): Boolean = synchronized(this) {
        val p = ctx.getSharedPreferences("ratings_budget", Context.MODE_PRIVATE)
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val used = if (p.getString("webDay", "") == day) p.getInt("webCount", 0) else 0
        if (used >= DAILY) return false
        p.edit().putString("webDay", day).putInt("webCount", used + 1).apply()
        if (used + 1 == DAILY) Log.w(TAG, "rt web: $DAILY lookups today, paused until tomorrow")
        true
    }
}
