package com.dreamos.desktop.scrapers

import com.dreamos.desktop.core.playback.MediaKind
import com.dreamos.desktop.core.playback.PlayTarget
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End to end, over the internet: install the CocoScrapers repository into a fresh folder, apply the recommended
 * providers, scrape a well-known movie and an episode, print counts and timings. Only with -Pe2e:
 *   ./gradlew :scrapers:test -Pe2e --tests '*ScraperE2ETest*'
 * TMDB details come from tmdb.key in local.properties (read at run time; never printed).
 */
class ScraperE2ETest {
    private fun tmdb(): TmdbFetch? {
        val repo = System.getProperty("dreamos.repo") ?: return null
        val f = File(repo, "local.properties").takeIf { it.isFile } ?: return null
        val key = Properties().apply { f.reader().use { load(it) } }.getProperty("tmdb.key")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val bearer = key.length > 40
        return TmdbFetch { path, query, timeoutMs ->
            val q = (query + if (bearer) emptyMap() else mapOf("api_key" to key)).entries
                .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            val c = URL("https://api.themoviedb.org/3$path?$q").openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs.toInt().coerceAtLeast(1000); c.readTimeout = timeoutMs.toInt().coerceAtLeast(1000)
            if (bearer) c.setRequestProperty("Authorization", "Bearer $key")
            try { if (c.responseCode == 200) c.inputStream.use { String(it.readBytes()) } else null } catch (e: Exception) { null } finally { c.disconnect() }
        }
    }

    @Test fun cocoScrapersEndToEnd() {
        if (System.getProperty("dreamos.e2e") != "1") { println("SKIPPED: end-to-end test (run with -Pe2e)"); return }
        val runtime = PythonRuntime.locate() ?: error("no bundled Python: run tools/fetch-python.sh")
        val tmp = Files.createTempDirectory("dreamos-e2e").toFile()
        val paths = ScraperPaths(File(tmp, "roaming"), File(tmp, "local"))
        val scrapers = Scrapers(paths, runtime)
        val tm = tmdb()
        println("TMDB details: ${if (tm != null) "on" else "off (request fields only)"}")
        val sources = ScraperSources(scrapers, ScraperInfo(tm))
        try {
            var t = System.currentTimeMillis()
            val add = scrapers.addRepository("https://github.com/CocoJoe2411/repository.cocoscrapers") { println("  progress: $it") }
            println("ADD: $add in ${System.currentTimeMillis() - t} ms")
            assertTrue(add is Scrapers.AddResult.Ok, "add failed: $add")
            val st = scrapers.status()
            println("installed: ${st.repos.map { r -> "${r.name} ${r.version} " + r.modules.joinToString { "${it.name} ${it.version}" } }}")
            println("providers: ${st.providers.size} (${st.providers.count { it.enabled }} on by the module's defaults)")
            scrapers.applyRecommended()
            println("recommended on: ${scrapers.enabledRefs().map { it.name }}")

            t = System.currentTimeMillis()
            assertTrue(scrapers.engine.ensure(), "python didn't start")
            println("PYTHON: started, runner set up, modules imported in ${System.currentTimeMillis() - t} ms")

            fun run(label: String, r: PlayTarget) {
                val t0 = System.currentTimeMillis()
                val found = sources.findBlocking(r, 25_000)
                val ms = System.currentTimeMillis() - t0
                val job = sources.jobFor(r)!!
                val byQ = found.groupingBy { it.quality }.eachCount()
                val byP = found.groupingBy { it.provider }.eachCount()
                println("SCRAPE $label: ${found.size} sources in $ms ms (first source after ${job.firstMs} ms; done=${job.done}; packs ${found.count { it.pack }})")
                println("  by quality: $byQ")
                println("  by provider: $byP")
                found.sortedByDescending { it.seeders }.take(3).forEach { println("  e.g. ${it.quality} ${it.seeders} seeders ${it.sizeBytes / (1 shl 20)} MB  ${it.name.take(90)}") }
                assertTrue(found.isNotEmpty(), "$label found nothing")
            }
            run("movie The Matrix (1999)", PlayTarget(MediaKind.MOVIE, 603, "The Matrix", imdb = "tt0133093", year = 1999))
            run("episode Breaking Bad S01E01", PlayTarget(MediaKind.EPISODE, 1396, "Breaking Bad", imdb = "tt0903747", year = 2008, season = 1, episode = 1))
            run("movie Inception (2010), warm", PlayTarget(MediaKind.MOVIE, 27205, "Inception", imdb = "tt1375666", year = 2010))
            run("episode The Office S02E01, warm", PlayTarget(MediaKind.EPISODE, 2316, "The Office", imdb = "tt0386676", year = 2005, season = 2, episode = 1))
            println("speeds: " + scrapers.enabledRefs().joinToString { r -> "${r.name} ${scrapers.speed(r.id)?.let { "${it.ms} ms ${it.hits}/${it.tries}" } ?: "-"}" })
        } finally {
            scrapers.engine.shutdown()
        }
    }
}
