package com.dreamos.desktop.core.catalog

import com.dreamos.desktop.core.catalog.PageRow.Companion.MIN_ROW
import com.dreamos.desktop.core.content.*
import com.dreamos.desktop.core.model.*
import com.dreamos.desktop.core.time.DreamTime

/**
 * Home's rows in order (fire-home CinemaHome): Continue Watching, Top Picks for You (big cards; "Popular Today" for a
 * new profile), the seasonal hub's row, New Episodes for You, New Releases, New Episodes, In Theaters, Trending Now,
 * Top 10 Today, a Spotlight, My List, Because You Watched, the profile's taste rows, then Popular on each service with
 * Coming Soon, a Coming Soon spotlight and a second Because You Watched between them. Each title shows in one row only.
 */
class HomePage(private val browse: BrowseRows) {
    private val claims = RowClaims()
    @Volatile private var top10Now: List<Title>? = null
    @Volatile private var trendingNow: List<Title>? = null
    @Volatile private var picksFallback = false
    private val charts = Any()

    fun session(): PageSession = PageSession(CatalogPage.Home, claims, rows())

    /** Top 10 first claimed, kept: every row's loader asks for it first so its titles stay its own. */
    private fun top10(): List<Title> {
        top10Now?.let { return it }
        if (RowPaging.page > 1) return RowMix.top10(claims)
        return synchronized(charts) { top10Now ?: RowMix.top10(claims).also { top10Now = it } }
    }

    /** Trending Now (claims right after Top 10): only what's out. */
    private fun trending(): List<Title> {
        trendingNow?.let { if (RowPaging.page == 1 || RowPaging.foreign("Trending Now")) return it }
        if (RowPaging.page > 1) return trendingList()
        return synchronized(charts) { trendingNow ?: trendingList().also { trendingNow = it } }
    }

    private fun trendingList(): List<Title> {
        top10()
        val (a, b) = RowPaging.all({ Tmdb.trending() }, { Tmdb.list("/trending/all/week", "&page=2") })
        return claims.take("Trending Now", (a + b).filter { RowMix.released(it) })
    }

    /**
     * Popular Today (a new profile's Top Picks): today's chart past what Top 10 and Trending Now claimed. Three pages,
     * not the Fire Stick's two: on 2026-10-30 Top 10 and Trending Now left 3 of the first 40 and the row vanished.
     */
    private fun popularToday(): List<Title> {
        val pages = RowPaging.all({ Tmdb.list("/trending/all/day") }, { Tmdb.list("/trending/all/day", "&page=2") },
            { Tmdb.list("/trending/all/day", "&page=3") }).flatten()
        return claims.take("Top Picks for You", pages.filter { it.backdropPath != null && RowMix.released(it) && !RowMix.animeHidden(it) }, 12)
    }

    private fun rows(): List<PageRow> {
        val out = ArrayList<PageRow>()
        out += Personal.continueWatching("home:continue")
        out += PageRow("home:top-picks", "Top Picks for You", RowStyle.BIG, first = 12,
            rename = { if (picksFallback) "Popular Today" else null },
            load = {
                trending()
                if (RowPaging.page > 1) (if (picksFallback) popularToday() else claims.take("Top Picks for You", HomePicks.topPicks()))
                else claims.take("Top Picks for You", HomePicks.topPicks()).takeIf { it.size >= HomePicks.MIN_PICKS }
                    ?: run { picksFallback = true; popularToday() }
            },
            card = { t, _ -> CatalogItem(t, kicker = HomePicks.bigKicker(t, browse::genreName)) })
        browse.homeSeasonRow(claims)?.let { out += it }
        out += Personal.newEpisodes("home:new-episodes-for-you")
        browse.homeNewReleases(claims) { trending() }.let { out += it }
        out += PageRow("home:trending", "Trending Now", RowStyle.LANDSCAPE, first = 16, load = { trending() })
        out += PageRow("home:top10", "Top 10 Today", RowStyle.TOP10, deep = false, load = { top10().takeIf { it.size >= MIN_ROW } },
            card = { t, i -> CatalogItem(t, rank = i + 1) })
        out += PageRow("home:spotlight", "", RowStyle.BIG, spotlight = true, min = 1, deep = false, load = {
            val taken = HashSet<String>().apply { trending().forEach { add(it.key) }; top10().forEach { add(it.key) } }
            // The week's best-rated trending title the rows above don't show: pages 2-4, the first with a pick wins.
            (2..4).asSequence().map { pg ->
                Tmdb.trendingShown(query = "&page=$pg", art = "backdrop").filter { it.key !in taken && it.votes >= 300 }
                    .sortedByDescending { it.rating }.take(1)
            }.firstOrNull { it.isNotEmpty() }.orEmpty()
        }, card = { t, _ -> CatalogItem(t, kicker = "Spotlight") })
        out += Personal.myList("home:my-list")

        val seeds = BecauseYouWatched.homeSeeds(2)
        seeds.getOrNull(0)?.let { out += becauseRow("home:byw:0", it) }
        for ((name, title) in Taste.forYou(Taste.current(), ContentPrefs.isKids(), HomePicks.moment().genres, browse.tasteCategories())) {
            browse.forYouRow("home:for-you:$name", name, title, claims) { trending() }?.let { out += it }
        }
        for ((i, svc) in HomePicks.services().withIndex()) {
            val title = "Popular on ${svc.name}"
            out += PageRow("home:svc:${svc.name}", title, RowStyle.LANDSCAPE, first = 16, load = {
                trending()
                val (tv, mv) = RowPaging.all({ Tmdb.onService(svc.providerId, MediaKind.TV) }, { Tmdb.onService(svc.providerId, MediaKind.MOVIE) })
                claims.take(title, RowMix.interleave(listOf(tv, mv)), 16).takeIf { it.size >= MIN_ROW }
            })
            when (i) {
                1 -> out += comingSoonRow()
                2 -> out += PageRow("home:spotlight-soon", "", RowStyle.BIG, spotlight = true, min = 1, deep = false,
                    load = {
                        browse.comingSoon(MediaKind.MOVIE).filter { it.date.isNotEmpty() && !RowMix.animeHidden(it) && it.backdropPath != null }
                            .drop(16).take(8)
                    }, pick = { l -> l.maxByOrNull { it.votes } }, card = { t, _ -> CatalogItem(t, kicker = "Coming Soon") })
                3 -> seeds.getOrNull(1)?.let { out += becauseRow("home:byw:1", it) }
            }
        }
        return out
    }

    private fun becauseRow(id: String, e: HistoryEntry): PageRow {
        val title = "Because You Watched ${e.title.name}"
        return PageRow(id, title, RowStyle.LANDSCAPE, load = {
            trending()
            val skip = HashSet<String>().apply { WatchHistory.recent().forEach { add(it.key) }; MyListStore.items().forEach { add(it.key) } }
            claims.take(title, RowMix.recs(e.title.kind, e.title.id, e.title.year.toIntOrNull()).filter { it.key !in skip }).takeIf { it.size >= MIN_ROW }
        })
    }

    /** Coming Soon: the most anticipated movies and series of the next months, soonest first, their date under each. */
    private fun comingSoonRow() = PageRow("home:coming-soon", "Coming Soon", RowStyle.LANDSCAPE, first = 16, load = {
        val soon = RowPaging.all({ browse.comingSoon(MediaKind.MOVIE) }, { browse.comingSoon(MediaKind.TV) }).flatten()
            .filter { it.date.isNotEmpty() && it.backdropPath != null }
        claims.take("Coming Soon", soon, 16).sortedBy { it.date }.takeIf { it.size >= MIN_ROW }
    }, card = { t, _ -> CatalogItem(t, subtitle = comingSoonLine(t)) })

    companion object {
        /** "Nov 14  ·  Movie" under a Coming Soon card. */
        fun comingSoonLine(t: Title): String =
            listOfNotNull(DreamTime.shortDate(t.date).ifEmpty { null }, if (t.isShow) "Series" else "Movie").joinToString(" · ")
    }
}
