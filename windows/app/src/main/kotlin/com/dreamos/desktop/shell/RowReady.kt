package com.dreamos.desktop.shell

import com.dreamos.desktop.shell.data.Catalog
import com.dreamos.desktop.shell.data.HeroItem
import com.dreamos.desktop.shell.data.Row
import com.dreamos.desktop.shell.data.RowStyle
import com.dreamos.desktop.ui.kit.Images
import com.dreamos.desktop.ui.kit.Metrics
import com.dreamos.desktop.ui.kit.SpotlightLogos
import com.dreamos.desktop.ui.kit.Tmdb
import com.dreamos.desktop.ui.kit.cardArt
import com.dreamos.desktop.ui.kit.cardWidth
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * What a row or a hero needs before it shows, so it arrives whole instead of piece by piece (owner, 2026-10-30:
 * nothing pops in after a card or page appears): a row's cards' key art (the covers its 16:9 cards draw first), its
 * first cards' art and their pills (Watched, availability), a Spotlight's logo, the hero's first slide (backdrop and
 * logo). Each waits at most [WAIT_MS] (key art and the art it decides share it); what isn't in by then fades in with
 * the row as before, and a card whose key art didn't come keeps its backdrop. The rest of the row's art, and the hero's
 * next slides, load behind it.
 */
object RowReady {
    const val WAIT_MS = 1_500L
    /** Rows this high on the page are on screen at once: their pill checks go first. */
    private const val ON_SCREEN_ROWS = 3

    /** Readies [row] (the [index]th slot on its page) and returns it with the key art its cards will draw. */
    suspend fun warm(row: Row, index: Int, catalog: Catalog, m: Metrics, density: Float): Row {
        val start = System.currentTimeMillis()
        var ready = row
        coroutineScope {
            launch {
                if (row.style == RowStyle.BIG || row.style == RowStyle.LANDSCAPE) {
                    ready = row.copy(items = catalog.withKeyArt(row.items, cardsInView(row, m), WAIT_MS, front = index < ON_SCREEN_ROWS))
                }
                Images.warm(cardArt(ready, m, density), (WAIT_MS - (System.currentTimeMillis() - start)).coerceAtLeast(0))
            }
            launch { catalog.readyPills(row.items.take(12).map { it.title }, WAIT_MS, front = index < ON_SCREEN_ROWS) }
            if (row.style == RowStyle.SPOTLIGHT) row.items.firstOrNull()?.let { item ->
                launch {
                    val path = catalog.logo(item.title)
                    SpotlightLogos.remember(item.title.key, path)
                    Images.warm(listOf(Tmdb.logo(path, m.u(320).value * density)), WAIT_MS)
                }
            }
        }
        Images.prefetch(cardArt(ready, m, density, count = ready.items.size))
        return ready
    }

    /** How many of [row]'s cards are in view, whole or in part, when it shows (hub tiles first). */
    private fun cardsInView(row: Row, m: Metrics): Int {
        val stride = cardWidth(row.style, m) + m.cardGap.value
        return (kotlin.math.ceil((m.widthDp - m.gutter.value) / stride).toInt() - row.hubs.size).coerceAtLeast(0)
    }

    /** The first slide's backdrop and logo, waited for; every other slide's, behind. */
    suspend fun warmHero(heroes: List<HeroItem>, m: Metrics, density: Float) {
        val w = m.widthDp * density
        val logoW = m.u(440).value * density
        fun art(h: HeroItem) = listOf(Tmdb.backdrop(h.title.backdropPath, w), Tmdb.logo(h.logoPath, logoW))
        heroes.firstOrNull()?.let { Images.warm(art(it), WAIT_MS) }
        Images.prefetch(heroes.drop(1).flatMap(::art))
    }
}
