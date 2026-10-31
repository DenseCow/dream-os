package com.dreamos.desktop.core

import com.dreamos.desktop.core.catalog.CatalogImpl
import com.dreamos.desktop.core.catalog.ContentServices
import com.dreamos.desktop.core.content.Tmdb
import com.dreamos.desktop.core.model.CatalogPage
import com.dreamos.desktop.core.model.RowStyle
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.service.Services
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Integration smoke test against the real TMDB: a whole Home feed for a new profile, with the key from local.properties
 * (`tmdb.key`, read at run time; never compiled in). Skipped when there's no key.
 */
class HomeSmokeTest {
    @Test fun loadsARealHomeFeed() = runBlocking {
        val key = TestSupport.localProperty("tmdb.key")
        if (key == null) { println("HomeSmokeTest: no tmdb.key in local.properties, skipped"); return@runBlocking }
        Net.fake = null
        TestSupport.freshStore()
        Tmdb.setKey(key)
        ContentServices.install()
        val catalog = Services.catalog as CatalogImpl
        val t0 = System.currentTimeMillis()
        val slots = catalog.rowSlots(CatalogPage.Home)
        assertTrue(slots.size >= 8, "Home has its rows (${slots.size})")
        val rows = slots.mapNotNull { catalog.fill(it, CatalogPage.Home) }
        val ms = System.currentTimeMillis() - t0
        for (r in rows) println("Home: ${r.name.ifEmpty { "(Spotlight)" }} [${r.style}] ${r.items.size}: " +
            r.items.take(4).joinToString { it.title.name + (it.kicker?.let { k -> " ($k)" } ?: "") })
        println("Home: ${rows.size} of ${slots.size} rows filled in $ms ms")
        val names = rows.map { it.name }
        assertTrue("Trending Now" in names, "Trending Now shows")
        val top10 = rows.firstOrNull { it.style == RowStyle.TOP10 }
        assertNotNull(top10, "Top 10 Today shows")
        assertEquals(10, top10.items.size, "Top 10 is always 10")
        assertEquals((1..10).toList(), top10.items.map { it.rank })
        assertTrue(names.contains("Popular Today") || names.contains("Top Picks for You"))
        // Each title shows in one row only (spotlights and personal rows aside).
        // In Theaters may share (it must always be there: RowClaims.takeAtLeast), as on the Fire Stick.
        val unique = rows.filter { !it.isSpotlight && it.name != "In Theaters" }
        val keys = unique.flatMap { r -> r.items.map { it.title.key } }
        val twice = keys.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        assertTrue(twice.isEmpty(), "a title in two rows: " + twice.joinToString { k -> k + " in " + unique.filter { r -> r.items.any { it.title.key == k } }.joinToString("/") { it.name } })
        // Nothing unreleased in the charts.
        val today = com.dreamos.desktop.core.time.DreamTime.todayYmd()
        for (r in rows.filter { it.name == "Trending Now" || it.style == RowStyle.TOP10 }) {
            assertTrue(r.items.all { it.title.date.length != 10 || it.title.date <= today }, "${r.name} lists only titles out today")
        }
        // No anime outside the anime sections (on by default).
        assertTrue(rows.flatMap { it.items }.none { com.dreamos.desktop.core.content.RowMix.isAnime(it.title) })
        val hero = catalog.spotlight(CatalogPage.Home)
        assertTrue(hero.isNotEmpty(), "the hero has titles")
        println("Hero: " + hero.joinToString { "${it.title.name} (${it.kicker})" })
        // A deep row loads its next page.
        val trending = rows.first { it.name == "Trending Now" }
        val more = catalog.more(trending, CatalogPage.Home)
        println("Trending Now page 2: ${more.size} more")
        assertTrue(more.none { m -> trending.items.any { it.title.key == m.title.key } })
    }
}

/** The other pages, Search and a title page against the real TMDB (skipped without a key). */
class PagesSmokeTest {
    @Test fun loadsBrowsePagesSearchAndATitle() = runBlocking {
        val key = TestSupport.localProperty("tmdb.key")
        if (key == null) { println("PagesSmokeTest: no tmdb.key in local.properties, skipped"); return@runBlocking }
        Net.fake = null
        TestSupport.freshStore()
        Tmdb.setKey(key)
        ContentServices.install()
        val catalog = Services.catalog
        for (page in listOf(CatalogPage.Movies, CatalogPage.Shows, CatalogPage.Anime)) {
            val t0 = System.currentTimeMillis()
            val slots = catalog.rowSlots(page)
            val rows = slots.take(10).mapNotNull { catalog.fill(it, page) }
            println("$page: ${rows.size} of the first ${minOf(10, slots.size)} rows (${slots.size} in all) in ${System.currentTimeMillis() - t0} ms: " +
                rows.joinToString { "${it.name.ifEmpty { "(Spotlight)" }} ${it.items.size}" })
            assertTrue(rows.size >= 5, "$page has rows")
            assertTrue(catalog.spotlight(page).isNotEmpty(), "$page has a hero")
            if (page != CatalogPage.Anime) assertTrue(rows.flatMap { it.items }.none { com.dreamos.desktop.core.content.RowMix.isAnime(it.title) })
        }
        val chips = catalog.chips(CatalogPage.Movies)
        assertTrue(chips.isNotEmpty())
        val horror = CatalogPage.GenrePage(chips.first { it.name == "Horror" })
        val hr = catalog.rowSlots(horror).take(4).mapNotNull { catalog.fill(it, horror) }
        println("Horror chip: " + hr.joinToString { "${it.name} ${it.items.size}" })
        assertTrue(hr.isNotEmpty())
        val hubs = catalog.hubs()
        println("Hubs today: " + hubs.joinToString { "${it.title} (${it.status})" })
        assertTrue(hubs.isNotEmpty())
        val genres = catalog.genres()
        println("Browse genres: ${genres.size}, categories: ${catalog.categories().size}, services: ${catalog.services().map { it.name }}")
        val found = Services.search.searchAll("yellow jackets")
        println("Search 'yellow jackets': " + found.titles.take(3).joinToString { it.name })
        assertTrue(found.titles.any { it.name.equals("Yellowjackets", true) })
        val person = Services.search.searchAll("tom hanks movies").person
        println("Search 'tom hanks movies': ${person?.heading} ${person?.titles?.size}")
        assertNotNull(person)
        val d = Services.titles.details(com.dreamos.desktop.core.model.MediaKind.TV, 1396)
        println("Breaking Bad: ${d.runtimeWords}, ${d.seasons.size} seasons, ${d.cast.size} cast, providers ${d.providers.map { it.name }}, logo ${d.logoPath != null}")
        assertTrue(d.seasons.isNotEmpty())
        val eps = Services.titles.episodes(1396, 1)
        assertEquals(7, eps.size)
        val scores = Services.titles.scores(d)
        println("Breaking Bad scores: ${scores.audienceBadge} tomato ${scores.tomatometer} cert '${scores.certification}'")
        assertNotNull(scores.audienceBadge)
    }
}
