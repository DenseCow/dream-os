package com.dreamos.desktop.shell.data

import com.dreamos.desktop.settings.kit.TitleRef
import com.dreamos.desktop.shell.AppServices
import com.dreamos.desktop.ui.kit.Images
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Title pages readied ahead of a click: a card the pointer or the keyboard rests on for [REST_MS] gets its page built
 * (details, kept scores and pills, through the title source the page uses), its backdrop and logo loaded at the page's
 * sizes and, for a show, the first season's episodes with their first stills, so the click opens a complete page in
 * its first frame. Its hero trailer is readied too (TrailerAhead, background lane). Bounded: one readying per title at a time, the built pages kept by the title source (48).
 */
object TitleAhead {
    const val REST_MS = 300L
    private const val STILLS = 6

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Readies [t]'s page after [REST_MS] unless the returned job is cancelled first (the pointer moved on). */
    fun rest(t: Title): Job = scope.launch {
        delay(REST_MS)
        com.dreamos.desktop.trailer.TrailerAhead.card(t.id, t.isShow, t.name)
        ready(t)
    }

    /** Readies [t]'s page now (also the speed harness). */
    suspend fun ready(t: Title) {
        if (!running.add(t.key)) return
        try {
            val ref = TitleRef(t.id, t.isShow, t.name)
            val src = AppServices.titleSource
            val d = src.instant(ref) ?: runCatching { src.details(ref) }.getOrNull() ?: return
            kotlinx.coroutines.coroutineScope {
                val art = listOfNotNull(d.backdropUrl, d.logoUrl).map { u -> async { Images.load(u) } }
                val season = d.defaultSeason?.takeIf { t.isShow }?.let { s ->
                    async { runCatching { src.season(ref, s) }.getOrNull()?.take(STILLS)?.forEach { e -> e.stillUrl?.let { u -> Images.load(u) } } }
                }
                (art + listOfNotNull(season)).awaitAll()
            }
        } finally {
            running.remove(t.key)
        }
    }
}
