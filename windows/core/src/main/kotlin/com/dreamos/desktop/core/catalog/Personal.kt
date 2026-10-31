package com.dreamos.desktop.core.catalog

import com.dreamos.desktop.core.content.MyListStore
import com.dreamos.desktop.core.content.NewEpisodes
import com.dreamos.desktop.core.content.WatchHistory
import com.dreamos.desktop.core.model.*

/**
 * The viewer's own rows (fire-home CinemaHome's refreshContinue / fillNewEpisodes / refreshMyList): built from local
 * data, never paged, and rebuilt whenever `Catalog.changes` emits (the UI keeps focus on the same title).
 */
object Personal {
    /** Continue Watching cards at most. */
    const val CONTINUE_MAX = 15
    const val MY_LIST_MAX = 20

    /** Continue Watching for [kind] (null: both), newest first: real watching only, finished titles off. */
    fun continueItems(kind: MediaKind? = null): List<CatalogItem> =
        WatchHistory.entries().filter { kind == null || it.title.kind == kind }.take(CONTINUE_MAX).map { item(it) }

    fun item(e: HistoryEntry): CatalogItem {
        val ep = if (e.isShow && e.season > 0) Episode(e.title.id, e.season, e.episode, e.episodeName.orEmpty(), stillPath = e.stillPath) else null
        // "Up Next · S2 · E5" / "S2 · E5" under the card (fire-home CinemaKit.continueCard).
        val sub = ep?.let { listOfNotNull(if (e.upNext) "Up Next" else null, "S${it.season} · E${it.number}").joinToString(" · ") }
        return CatalogItem(e.title, episode = ep, progress = if (e.upNext) 0.0 else e.progress, subtitle = sub,
            badge = if (e.upNext) "UP NEXT" else null, timeLeft = if (e.upNext) null else timeLeft(e.positionMs, e.durationMs))
    }

    /** "42 Min Left" (fire-home CinemaDetails' resume line); null when the length is unknown or under a minute is left. */
    fun timeLeft(positionMs: Long, durationMs: Long): String? =
        if (durationMs <= 0) null else ((durationMs - positionMs) / 60_000).takeIf { it > 0 }?.let { "$it Min Left" }

    fun continueWatching(id: String, kind: MediaKind? = null) = PageRow(id, "Continue Watching", RowStyle.CONTINUE, min = 1, deep = false,
        personal = { continueItems(kind).takeIf { it.isNotEmpty() } })

    @Volatile private var lastEpisodes: NewEpisodes.Snapshot? = null

    /** New Episodes for You ("Coming Up" while nothing new has aired): aired ones with their NEW badge, then up to 10 upcoming. */
    fun newEpisodes(id: String) = PageRow(id, "New Episodes for You", RowStyle.CONTINUE, min = 1, deep = false,
        rename = { lastEpisodes?.let { if (it.recent.isEmpty()) "Coming Up" else null } },
        personal = {
            val s = NewEpisodes.load()
            lastEpisodes = s
            if (s.isEmpty) null
            else (s.recent + s.upcoming.take(10)).map { it ->
                CatalogItem(it.show.title(), episode = it.info().episode, subtitle = it.badge().replace("  ·  ", " · "),
                    badge = if (NewEpisodes.isNew(it)) "NEW" else null)
            }
        })

    fun myList(id: String, kind: MediaKind? = null) = PageRow(id, "My List", RowStyle.LANDSCAPE, min = 1, deep = false,
        personal = { MyListStore.items().filter { kind == null || it.kind == kind }.take(MY_LIST_MAX).map { CatalogItem(it) }.takeIf { it.isNotEmpty() } })
}
