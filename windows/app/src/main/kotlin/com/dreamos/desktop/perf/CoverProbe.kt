package com.dreamos.desktop.perf

import com.dreamos.desktop.shell.data.CardItem
import java.util.concurrent.ConcurrentHashMap

/**
 * The speed harness's count of covers: what each 16:9 title card drew when it was first drawn, its cover (key art) or
 * not. Off outside the harness (one volatile read per card drawn).
 */
object CoverProbe {
    @Volatile var on = false
    /** Title key to the title and whether its card's first choice was its cover. */
    val drawn = ConcurrentHashMap<String, Pair<com.dreamos.desktop.shell.data.Title, Boolean>>()

    fun note(item: CardItem, first: String?) {
        if (!on || item.still != null || item.episode != null) return
        val cover = com.dreamos.desktop.shell.data.Covers.of(item)
        drawn.putIfAbsent(item.title.key, item.title to (cover != null && first?.endsWith(cover) == true))
    }
}
