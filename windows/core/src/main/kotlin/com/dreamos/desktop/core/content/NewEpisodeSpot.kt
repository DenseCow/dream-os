package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.HistoryEntry
import com.dreamos.desktop.core.model.NewEpisodeInfo
import com.dreamos.desktop.core.model.Notice
import com.dreamos.desktop.core.notices.Notices
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.time.DreamTime
import com.dreamos.desktop.core.util.Log

/**
 * Which new episode Home's New Episode banner offers (fire-home NewEpisodeSpot): a followed show's episode out in the
 * last [BANNER_DAYS] days that this profile hasn't played or dismissed, first shown less than [RETIRE_MS] ago (no
 * nagging), and only when the viewer is caught up: a show only on My List always counts; a watched one only when this
 * is its Up Next episode or the one right after the last one finished (a new season's first after the last season).
 * Watching it already, or behind: no (Continue Watching has it). Each refresh also posts one "New Episode" notice for
 * the episodes it found (banner only, never stored), and keeps [badged] for the "NEW EPISODE" card corner.
 *
 * Per profile in `newepisodespot[_<id>]`: `done` (played or dismissed keys), `first.<key>` (first shown, ms). Both are
 * kept only for episodes still in the window: worst case a few dozen short strings.
 */
object NewEpisodeSpot {
    /** Offered while at most this many days old (0 = today). */
    const val BANNER_DAYS = 3
    /** Retired this long after the banner first showed it, played or not. */
    const val RETIRE_MS = 48 * 60 * 60_000L

    private val lock = Any()
    private fun prefs() = DreamStore.shared.profileFile("newepisodespot")

    /** TMDB ids of shows with an episode the banner would offer: their cards wear "NEW EPISODE". */
    @Volatile var badged: Set<Int> = emptySet()
        private set

    /** The episode the banner offers now (the newest), or null. Loads New Episodes (TMDB only when a show is due). Blocking. */
    fun pick(): NewEpisodeInfo? {
        val s = NewEpisodes.load()
        val history = try { WatchHistory.recent() } catch (e: Exception) { emptyList() }
        val done = prefs().getStringList("done").toSet()
        val now = System.currentTimeMillis()
        fun offered(i: NewEpisodes.Item): Boolean {
            if (i.upcoming || i.key in done) return false
            val age = DreamTime.daysFromToday(i.ep.airDate) ?: return false
            val first = prefs().getLong("first.${i.key}", now)
            return age in -BANNER_DAYS..0 && first > now - RETIRE_MS && caughtUp(history, i.show.id, i.ep.season, i.ep.number)
        }
        val list = s.recent.filter(::offered)
        badged = list.mapTo(HashSet()) { it.show.id }
        if (s.announce.isNotEmpty()) {
            val news = s.announce.filter(::offered)
            if (news.isNotEmpty()) notice(news)
            NewEpisodes.announced(s.announce.map { it.key })
        }
        prune(s.recent.mapTo(HashSet()) { it.key })
        return list.firstOrNull()?.info()
    }

    /**
     * The viewer is caught up with [showId] for S[season] E[number]: not in the history (followed from My List) yes;
     * else it's the Up Next episode, or the one right after the last one finished.
     */
    fun caughtUp(history: List<HistoryEntry>, showId: Int, season: Int, number: Int): Boolean {
        val e = history.firstOrNull { it.isShow && it.title.id == showId } ?: return true
        if (e.upNext) return e.season == season && e.episode == number
        if (e.season > season || (e.season == season && e.episode >= number)) return false
        return e.finished && ((e.season == season && e.episode == number - 1) || (e.season == season - 1 && number == 1))
    }

    /** The banner showed [e]: its retirement clock starts the first time. */
    fun displayed(e: NewEpisodeInfo) = synchronized(lock) {
        val k = "first.${e.key}"
        if (!prefs().contains(k)) prefs().putLong(k, System.currentTimeMillis())
    }

    /** Played or dismissed: never offered again on this profile. */
    fun done(e: NewEpisodeInfo) {
        synchronized(lock) {
            val d = prefs().getStringList("done")
            if (e.key !in d) prefs().putStringList("done", d + e.key)
        }
        badged = badged - e.show.id
    }

    /** "S3 · E7" ("S1 · E1–8" for a batch drop). */
    fun code(e: NewEpisodeInfo) = "S${e.season} · E${e.number}" + if (e.lastNumber > e.number) "–${e.lastNumber}" else ""

    private fun notice(items: List<NewEpisodes.Item>) {
        val infos = items.map { it.info() }
        val names = infos.map { it.show.name }.distinct()
        val title = if (infos.size == 1) "New Episode" else "${infos.size} New Episodes"
        val body = when {
            infos.size == 1 -> "${infos[0].show.name}  ·  ${code(infos[0])}"
            names.size == 1 -> names[0]
            names.size == 2 -> "${names[0]} and ${names[1]}"
            else -> "${names[0]}, ${names[1]} and ${names.size - 2} More"
        }
        val key = "newep:" + infos.joinToString(",") { it.key }
        if (Notices.post(key, Notice.TYPE_EPISODES, title, body, store = false)) Log.i("NewEp", "notice \"$title: $body\"")
    }

    /** Marks only for episodes still in the window; read again under the lock so a Dismiss written meanwhile stays. */
    private fun prune(keep: Set<String>) = synchronized(lock) {
        val p = prefs()
        val done = p.getStringList("done")
        val stale = p.keys.filter { it.startsWith("first.") && it.removePrefix("first.") !in keep }
        if (stale.isEmpty() && done.all { it in keep }) return@synchronized
        p.edit {
            putStringList("done", done.filter { it in keep })
            stale.forEach { remove(it) }
        }
    }
}
