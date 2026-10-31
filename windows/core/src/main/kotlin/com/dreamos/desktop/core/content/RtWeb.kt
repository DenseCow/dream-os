package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.time.DreamTime
import com.dreamos.desktop.core.util.Log

/**
 * The Rotten Tomatoes critic score from rottentomatoes.com itself (fire-home RtWeb.kt): the fallback for titles
 * OMDb knows but has no Tomatometer for, on a title page only ([Ratings.scores], never a prefetch).
 *
 * One request to the search page, whose results are `<search-page-result type="movie|tvSeries">` sections of
 * `<search-page-media-row release-year / start-year / tomatometer-score ...>` rows (attribute names come with and
 * without hyphens). The row must match the title (normalized) and year (within one) in the right section; a match
 * without a score costs one more request, for the title page's `"criticsScore":{... "score":"97"}`. At most [DAILY]
 * lookups a day, 6 s timeouts, no retries, one try per title per session (the tried set is bounded by the daily
 * budget). Any change in the markup just finds nothing.
 */
object RtWeb {
    private const val DAILY = 80
    private const val TIMEOUT_MS = 6_000L
    private const val SITE = "https://www.rottentomatoes.com/"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    /** A finished lookup: [score] null is a miss (no match, ambiguous, or no score). */
    class Result(val score: Int?)

    /** One search row that matched: its score (null when the row shows none) and its page. */
    data class Match(val score: Int?, val page: String?)

    private val tried = HashSet<String>()
    private val ROW = Regex("<search-page-media-row\\b([^>]*)>(.*?)</search-page-media-row>", RegexOption.DOT_MATCHES_ALL)
    private val ANCHOR = Regex("<a\\b([^>]*\\bslot=\"title\"[^>]*)>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
    private val ATTR = Regex("([A-Za-z][\\w-]*)=\"([^\"]*)\"")
    private val CRITICS = Regex("\"criticsScore\":\\{[^{}]*?\"score\":\"(\\d{1,3})\"")

    /** Not yet tried this session. */
    fun mayTry(t: Title) = synchronized(tried) { t.key !in tried }

    /** Blocking. The lookup's [Result], or null when it wasn't made or failed (nothing to cache). */
    fun lookup(t: Title): Result? {
        synchronized(tried) { if (!tried.add(t.key)) return null }
        if (t.name.isBlank() || !spend()) return null
        val started = System.currentTimeMillis()
        val result = try {
            find(t)
        } catch (e: Exception) {
            Log.w("RtWeb", "\"${t.name}\" failed after ${System.currentTimeMillis() - started} ms: ${e.javaClass.simpleName}")
            return null
        }
        Log.i("RtWeb", "\"${t.name}\" (${t.year}) ${result.score?.let { "found $it%" } ?: "miss"}, ${System.currentTimeMillis() - started} ms")
        return result
    }

    private fun find(t: Title): Result {
        val pick = pick(get(SITE + "search?search=" + Net.encode(t.name)), t) ?: return Result(null)
        pick.score?.let { return Result(it) }
        val page = pick.page ?: return Result(null)
        return Result(criticsScore(get(page)))
    }

    /** The one search row for [t] (one match, or one with the exact year among several); null when none or ambiguous. */
    fun pick(html: String, t: Title): Match? {
        val section = if (t.kind == MediaKind.TV) "tvSeries" else "movie"
        val want = norm(t.name)
        val year = t.year.toIntOrNull()
        val matches = ArrayList<Match>()
        val exactYear = ArrayList<Match>()
        val starts = Regex("<search-page-result\\b").findAll(html).map { it.range.first }.toList()
        for ((i, s) in starts.withIndex()) {
            val chunk = html.substring(s, if (i + 1 < starts.size) starts[i + 1] else html.length)
            val close = chunk.indexOf('>').takeIf { it > 0 } ?: continue
            if (attrs(chunk.substring(0, close))["type"] != section) continue
            for (m in ROW.findAll(chunk)) {
                val a = attrs(m.groupValues[1])
                val anchor = ANCHOR.find(m.groupValues[2]) ?: continue
                if (norm(unescape(anchor.groupValues[2].replace(Regex("<[^>]*>"), ""))) != want) continue
                val y = (if (t.kind == MediaKind.TV) a["startyear"] else a["releaseyear"])?.toIntOrNull()
                if (year != null && (y == null || kotlin.math.abs(y - year) > 1)) continue
                val href = attrs(anchor.groupValues[1])["href"]?.takeIf { it.startsWith(SITE) }
                val row = Match(a["tomatometerscore"]?.toIntOrNull()?.takeIf { it in 0..100 }, href)
                matches += row
                if (y == year) exactYear += row
            }
        }
        return when {
            matches.size == 1 -> matches[0]
            exactYear.size == 1 -> exactYear[0]
            else -> null
        }
    }

    /** A title page's critics score (0-100), null when it shows none. */
    fun criticsScore(html: String): Int? = CRITICS.find(html)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }

    private fun get(url: String): String {
        val r = Net.request("GET", url, null, mapOf(
            "User-Agent" to UA, "Accept" to "text/html,application/xhtml+xml", "Accept-Language" to "en-US,en;q=0.9",
        ), timeoutMs = TIMEOUT_MS)
        if (!r.ok) throw java.io.IOException("Rotten Tomatoes answered ${r.code}")
        return r.body
    }

    /** Attributes of an opening tag, names lowercased without hyphens ("release-year" = "releaseyear"). */
    private fun attrs(tag: String): Map<String, String> =
        ATTR.findAll(tag).associate { it.groupValues[1].replace("-", "").lowercase() to it.groupValues[2].trim() }

    /** Case, punctuation, "&" / "and" and a leading "The" don't matter. */
    private fun norm(s: String): String = s.lowercase()
        .replace("&", " and ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .removePrefix("the ")
        .replace(" ", "")

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").trim()

    private fun spend(): Boolean = synchronized(this) {
        val p = Ratings.budget()
        val day = DreamTime.todayYmd()
        val used = if (p.getString("webDay") == day) p.getInt("webCount") else 0
        if (used >= DAILY) return false
        p.edit { putString("webDay", day); putInt("webCount", used + 1) }
        if (used + 1 == DAILY) Log.w("RtWeb", "$DAILY lookups today, paused until tomorrow")
        true
    }
}
