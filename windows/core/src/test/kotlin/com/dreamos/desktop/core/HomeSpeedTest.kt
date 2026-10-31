package com.dreamos.desktop.core

import com.dreamos.desktop.core.catalog.CatalogImpl
import com.dreamos.desktop.core.catalog.ContentServices
import com.dreamos.desktop.core.content.Tmdb
import com.dreamos.desktop.core.model.CatalogPage
import com.dreamos.desktop.core.model.CatalogRow
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.net.TmdbDisk
import com.dreamos.desktop.core.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Files
import kotlin.test.*

/**
 * Home's time to rows against the real TMDB (key from local.properties; skipped without one), as the shell asks for
 * them (every slot, 8 fills at once, plus the hero):
 *  - cold: nothing on disk, nothing in memory (a first launch);
 *  - warm start: memory dropped, TMDB answers on disk (a relaunch);
 *  - warmed: a relaunch where `ContentServices.warm()` ran behind the intro first.
 */
class HomeSpeedTest {
    private class Timing(val ms: Long, val rows: Int, val slots: Int, val slowest: String)

    private suspend fun home(catalog: CatalogImpl): Timing {
        val t0 = System.nanoTime()
        val slots = catalog.rowSlots(CatalogPage.Home)
        val gate = Semaphore(8)
        val each = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val rows = kotlinx.coroutines.coroutineScope {
            val hero = async(Dispatchers.IO) { val a = System.nanoTime(); catalog.spotlight(CatalogPage.Home).also { each["(hero)"] = (System.nanoTime() - a) / 1_000_000 } }
            val filled = slots.map { s -> async(Dispatchers.IO) { gate.withPermit {
                val a = System.nanoTime()
                catalog.fill(s, CatalogPage.Home).also { each[s.name.ifEmpty { s.id }] = (System.nanoTime() - a) / 1_000_000 }
            } } }.awaitAll()
            hero.await()
            filled.filterNotNull()
        }
        val slowest = each.entries.sortedByDescending { it.value }.take(5).joinToString { "${it.key} ${it.value} ms" }
        return Timing((System.nanoTime() - t0) / 1_000_000, rows.size, slots.size, slowest)
    }

    @Test fun homeFromDiskIsInstant() = runBlocking {
        val key = TestSupport.localProperty("tmdb.key")
        if (key == null) { println("HomeSpeedTest: no tmdb.key in local.properties, skipped"); return@runBlocking }
        val saved = Platform.current
        try {
            Net.fake = null
            Platform.current = Platform.forTests(Files.createTempDirectory("dream-speed"))
            TestSupport.freshStore()
            Tmdb.setKey(key)
            ContentServices.install()
            val catalog = ContentServices.catalog
            ContentServices.forgetMemory() // other tests in this JVM may have filled the memory caches

            val cold = home(catalog)
            // The other launch pages reach the disk too (a first launch warms them behind Home).
            val c0 = System.nanoTime()
            catalog.warm()
            val coldWarmMs = (System.nanoTime() - c0) / 1_000_000
            // Key art asks finish behind the first launch (a relaunch finds them remembered).
            val k0 = System.nanoTime()
            com.dreamos.desktop.core.content.Details.awaitKeyArt(120_000)
            val keyArtMs = (System.nanoTime() - k0) / 1_000_000 + coldWarmMs
            TmdbDisk.flush()
            val files = Files.list(TmdbDisk.folder!!).use { it.count() }

            ContentServices.forgetMemory()
            val disk = home(catalog)

            ContentServices.forgetMemory()
            val w0 = System.nanoTime()
            val warmed = catalog.warm()
            val warmMs = (System.nanoTime() - w0) / 1_000_000
            val ready = home(catalog)
            val art = warmed.values.flatten().sumOf { CatalogImpl.artPaths(it).size }

            println("Home cold (first launch, TMDB): ${cold.rows} of ${cold.slots} rows in ${cold.ms} ms (slowest: ${cold.slowest})")
            println("First launch's prefetch of Home, Movies, TV Shows: $coldWarmMs ms (key art for every card done after $keyArtMs ms); $files answers kept on disk")
            println("Home warm start (memory dropped, answers on disk): ${disk.rows} of ${disk.slots} rows in ${disk.ms} ms (slowest: ${disk.slowest})")
            println("Warm prefetch (Home, Movies, TV Shows from disk): ${warmed.values.sumOf { it.size }} rows, $art art paths in $warmMs ms")
            println("Home after the prefetch: ${ready.rows} of ${ready.slots} rows in ${ready.ms} ms")

            assertTrue(cold.rows >= 8, "Home has rows")
            // Rows filled 8 at once claim titles in a slightly different order each time: a thin row may drop out.
            assertTrue(disk.rows >= cold.rows - 1, "the same rows from disk (${cold.rows} / ${disk.rows})")
            assertTrue(ready.ms < 200, "Home rows ready under 200 ms after the prefetch (${ready.ms} ms)")
            assertTrue(disk.ms < cold.ms, "disk beats the network")
        } finally {
            TmdbDisk.close()
            Platform.current = saved
        }
    }
}
