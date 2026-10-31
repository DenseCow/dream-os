package com.dreamos.desktop.scrapers

import com.dreamos.desktop.core.playback.MediaKind
import com.dreamos.desktop.core.playback.PlayTarget
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The helper process with fake provider modules (no network): streaming results, per-provider timeouts,
 * cancellation, a provider that kills Python (the next scrape starts a new process), and the guard.
 * Needs tools/fetch-python.sh to have run; skipped otherwise.
 */
class ScraperHostTest {
    private val runtime = PythonRuntime.locate()
    private var scrapers: Scrapers? = null

    @AfterTest fun stop() { scrapers?.engine?.shutdown() }

    private fun setup(): Pair<Scrapers, ScraperSources>? {
        if (runtime == null) { println("SKIPPED: no bundled Python (run tools/fetch-python.sh)"); return null }
        val tmp = Files.createTempDirectory("dreamos-scrapers").toFile()
        val paths = ScraperPaths(File(tmp, "roaming"), File(tmp, "local")).ensure()
        val addon = File(paths.addons, "script.module.fakescrapers")
        val torrents = File(addon, "lib/fakescrapers/sources_fake/torrents").apply { mkdirs() }
        File(addon, "addon.xml").writeText("""<addon id="script.module.fakescrapers" name="Fake Scrapers" version="1.0.0">
            <extension point="xbmc.python.module" library="lib"/></addon>""")
        File(addon, "lib/fakescrapers/__init__.py").writeText("")
        File(torrents, "quick.py").writeText("""
import xbmcaddon
class source:
    hasMovies = True
    hasEpisodes = True
    def sources(self, data, hostDict):
        print('a provider printing to stdout must not break the channel')
        assert xbmcaddon.Addon().getAddonInfo('id') == 'script.module.fakescrapers'
        t = data.get('tvshowtitle') or data['title']
        return [{'source': 'torrent', 'name': '%s.%s.1080p.WEB' % (t, data['year']), 'hash': '%040x' % 1, 'quality': '1080p', 'size': 2.5, 'seeders': '40'},
                {'source': 'torrent', 'name': '%s.2160p' % t, 'url': 'magnet:?xt=urn:btih:' + 'B' * 40, 'quality': '4K', 'size': 20, 'seeders': 9}]
""")
        File(torrents, "slow.py").writeText("""
import time
class source:
    def sources(self, data, hostDict):
        time.sleep(30)
        return []
""")
        File(torrents, "sneaky.py").writeText("""
class source:
    def sources(self, data, hostDict):
        out = []
        for attempt in ('subprocess', 'ctypes', 'importlib_ctypes', 'startfile'):
            try:
                if attempt == 'subprocess':
                    import subprocess; subprocess.run(['cmd', '/c', 'echo hi'])
                elif attempt == 'ctypes':
                    import ctypes
                elif attempt == 'importlib_ctypes':
                    import importlib; importlib.import_module('ctypes').CDLL('kernel32')
                else:
                    import os; os.startfile('notepad.exe')
                out.append(attempt)
            except Exception:
                pass
        # Anything that got through shows up as a result named after it.
        return [{'source': 'torrent', 'name': 'ESCAPED ' + a, 'hash': '%040x' % (100 + i), 'quality': 'SD'} for i, a in enumerate(out)]
""")
        File(torrents, "crash.py").writeText("""
import os
class source:
    def sources(self, data, hostDict):
        if data.get('title') == 'Crash Me':
            os._exit(7)
        return []
""")
        paths.reposFile.writeText("""[{"url":"https://example.org/fake.zip","id":"script.module.fakescrapers","name":"Fake Scrapers","version":"1.0.0",
            "specs":[],"direct":"https://example.org/fake.zip","modules":{"script.module.fakescrapers":"1.0.0"}}]""")
        val s = Scrapers(paths, runtime)
        scrapers = s
        return s to ScraperSources(s, ScraperInfo(null))
    }

    @Test fun scrapesStreamsTimesOutAndGuards() {
        val (s, src) = setup() ?: return
        assertEquals(4, s.refs().size)
        assertTrue(s.active())
        val t0 = System.currentTimeMillis()
        assertTrue(s.engine.ensure(), "python should start")
        println("python + runner + modules ready in ${System.currentTimeMillis() - t0} ms")

        val movie = PlayTarget(MediaKind.MOVIE, 1, "Fake Movie", imdb = "tt0000001", year = 2001)
        val t1 = System.currentTimeMillis()
        val batches = runBlocking { src.stream(movie, urgent = true).toList() }
        val all = batches.flatten()
        println("stream: ${batches.size} batches, ${all.size} sources in ${System.currentTimeMillis() - t1} ms")
        assertEquals(setOf("0".repeat(39) + "1", "b".repeat(40)), all.map { it.hash }.toSet())
        assertTrue(all.none { it.name.startsWith("ESCAPED") }, "guard let through: ${all.map { it.name }}")
        val first = all.first { it.hash.endsWith("1") }
        assertEquals("1080p", first.quality)
        assertEquals((2.5 * 1024 * 1024 * 1024).toLong(), first.sizeBytes)
        // The slow provider was cut at its 12 s limit, not the 30 s it sleeps.
        val job = src.jobFor(movie)!!
        assertTrue(job.done && System.currentTimeMillis() - t1 < 16_000, "scrape should end at the per-provider limit")

        // A second ask reuses the kept scrape.
        val t2 = System.currentTimeMillis()
        assertEquals(2, src.findBlocking(movie, 5_000).size)
        assertTrue(System.currentTimeMillis() - t2 < 500)
    }

    @Test fun cancelEndsAScrapeAtOnce() {
        val (s, _) = setup() ?: return
        assertTrue(s.engine.ensure())
        val slow = s.refs().filter { it.id.endsWith(":slow") }
        val job = s.engine.start(kotlinx.serialization.json.buildJsonObject {
            put("title", kotlinx.serialization.json.JsonPrimitive("X")); put("year", kotlinx.serialization.json.JsonPrimitive("2000"))
        }, slow, "cancel test", light = false, play = true, urgent = true, timeoutMs = 20_000, perProviderMs = 15_000, packs = false)
        Thread.sleep(800)
        val t0 = System.currentTimeMillis()
        job.cancel()
        assertTrue(job.awaitDone(3_000), "a cancelled scrape should end within moments")
        println("cancel -> done in ${System.currentTimeMillis() - t0} ms")
    }

    @Test fun aCrashedPythonIsRestarted() {
        val (s, src) = setup() ?: return
        assertTrue(s.engine.ensure())
        val pid1 = s.engine.pid()
        val crash = PlayTarget(MediaKind.MOVIE, 2, "Crash Me", imdb = "tt0000002", year = 2002)
        val t0 = System.currentTimeMillis()
        val found = src.findBlocking(crash, 10_000)
        val job = src.jobFor(crash)!!
        println("crash scrape ended in ${System.currentTimeMillis() - t0} ms with ${found.size} sources (done=${job.done})")
        assertTrue(job.done && System.currentTimeMillis() - t0 < 5_000, "the job should end as soon as python dies")
        // The next scrape starts a new process and works.
        val movie = PlayTarget(MediaKind.MOVIE, 3, "After Crash", imdb = "tt0000003", year = 2003)
        assertEquals(2, src.findBlocking(movie, 20_000).size)
        assertNotEquals(pid1, s.engine.pid())
        println("restarted: pid $pid1 -> ${s.engine.pid()}")
    }

    /** core/playback's SourceEngine: start() returns at once; the state mirrors the engine's job until it ends. */
    @Test fun coreSourceEngineMirrorsTheScrape() {
        val (s, src) = setup() ?: return
        assertTrue(s.engine.ensure())
        val movie = PlayTarget(MediaKind.MOVIE, 4, "Core Movie", imdb = "tt0000004", year = 2004)
        val job = src.start(com.dreamos.desktop.core.playback.ScrapeQuery(movie, "tt0000004"), com.dreamos.desktop.core.playback.ScrapeMode.PLAY)
        val end = runBlocking { kotlinx.coroutines.withTimeoutOrNull(20_000) { job.state.first { it.done } } }
        assertTrue(end != null, "the core job should end")
        assertEquals(2, end!!.sources.size)
        assertTrue(end.answered > 0 && !end.failedAll)
        assertTrue(end.firstResultAtMs != null)
    }
}
