package com.dreamos.desktop.scrapers

import com.dreamos.desktop.core.catalog.ContentPlaybackHooks
import com.dreamos.desktop.core.catalog.ContentReleases
import com.dreamos.desktop.core.catalog.SettingsKeyValueStore
import com.dreamos.desktop.core.content.Details
import com.dreamos.desktop.core.content.Tmdb
import com.dreamos.desktop.core.content.WatchHistory
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.playback.MemorySecrets
import com.dreamos.desktop.core.playback.Playback
import com.dreamos.desktop.core.playback.ScreenFacts
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.util.int
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test

/**
 * Live, over the internet (only with -Pe2e): Dexter: Resurrection (TMDB tv/259909) Season 2 through the real
 * CocoScrapers and Dream Core, as the title page sees it: the raw index answer for S2E1, then the season's episode
 * list before and after the page's quick check, the pills, the season-out rule and Up Next after S1E10.
 *   ./gradlew :scrapers:test -Pe2e --tests '*OutEarlyLiveTest*'
 * The TMDB key comes from local.properties at run time and is never printed.
 */
class OutEarlyLiveTest {
    private val show = 259909

    @Test fun dexterResurrectionSeasonTwo() {
        if (System.getProperty("dreamos.e2e") != "1") { println("SKIPPED: live test (run with -Pe2e)"); return }
        val repo = File(System.getProperty("dreamos.repo"))
        val key = Properties().apply { File(repo, "local.properties").reader().use { load(it) } }.getProperty("tmdb.key")?.trim()
        if (key.isNullOrEmpty()) { println("SKIPPED: no tmdb.key in local.properties"); return }
        val runtime = PythonRuntime.locate() ?: error("no bundled Python: run tools/fetch-python.sh")
        val tmp = Files.createTempDirectory("dreamos-outearly").toFile()
        DreamStore.use(DreamStore(File(tmp, "store").toPath()))
        Tmdb.setKey(key)
        WatchHistory.clearCache()

        val scrapers = Scrapers(ScraperPaths(File(tmp, "roaming"), File(tmp, "local")), runtime)
        val tmdb = TmdbFetch { path, query, _ ->
            val q = query.entries.joinToString("") { (k, v) -> "&$k=" + URLEncoder.encode(v, "UTF-8") }
            runCatching { Net.getText(Tmdb.url(path, q)) }.getOrNull()
        }
        val sources = ScraperSources(scrapers, ScraperInfo(tmdb))
        try {
            val add = scrapers.addRepository("https://github.com/CocoJoe2411/repository.cocoscrapers") { }
            println("CocoScrapers: $add")
            scrapers.applyRecommended()
            check(scrapers.engine.ensure()) { "python didn't start" }

            val p = Playback.create(sources, ContentPlaybackHooks, SettingsKeyValueStore("playback"), MemorySecrets(), object : ScreenFacts {}, ContentReleases)
            val d = Details.details(MediaKind.TV, show)
            println("TMDB: ${d.title.name}, IMDb ${d.imdbId}; seasons " + d.seasons.joinToString { "S${it.number} (${it.episodeCount} eps, ${it.airDate})" })
            WatchHistory.seasonEpisodes(show, 2)?.forEach { println("  TMDB S2E${it.int("episode_number")} ${it["air_date"]} ${it["name"]}") }

            // The raw index answer for S2E1.
            val e1 = Playback.earlyTarget(show, 2, 1, "")
            val t0 = System.currentTimeMillis()
            val found = sources.findBlocking(e1, 25_000)
            println("INDEX S2E1: ${found.size} sources in ${System.currentTimeMillis() - t0} ms; by quality ${found.groupingBy { it.quality }.eachCount()}")
            found.sortedByDescending { it.seeders }.take(6).forEach { println("  ${it.quality} ${it.seeders} seeders ${it.sizeBytes / (1 shl 20)} MB  ${it.name.take(90)}  [${it.provider}]") }

            fun list(label: String, eps: List<com.dreamos.desktop.core.model.Episode>) {
                println("$label:")
                eps.forEach { println("  E${it.number} ${it.airDate} \"${it.name}\" " + when { it.outEarly -> "OUT EARLY (plays)"; it.upcoming -> "Coming"; else -> "aired" }) }
            }
            list("Season 2, plain list (player's episode list, before any check)", Details.episodes(show, 2))
            val t1 = System.currentTimeMillis()
            list("Season 2, title page list (quick check)", Details.episodes(show, 2, askEarly = true))
            println("  page check took ${System.currentTimeMillis() - t1} ms")

            val a = p.availability
            println("Season 2 counts as out (episode 1 up early): ${a.outEarly("tv:$show:2:1")}")
            val ep1 = Playback.earlyTarget(show, 2, 1, "2026-11-01")
            println("S2E1 card pill: ${a.cardPill(ep1)?.label}; page pill: ${a.pagePill(ep1)?.label}; answer ${a.peek(ep1.key)}")
            val ep2 = Playback.earlyTarget(show, 2, 2, "")
            println("S2E2 answer: ${a.peek(ep2.key)}")
            println("Up Next after S1E10: ${WatchHistory.episodeAfter(show, 1, 10)?.let { (s, e) -> "S${s}E${e.int("episode_number")}" }}")
        } finally {
            scrapers.engine.shutdown()
        }
    }
}
