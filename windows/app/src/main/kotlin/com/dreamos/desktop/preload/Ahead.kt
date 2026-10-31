package com.dreamos.desktop.preload

import com.dreamos.desktop.core.model.NewEpisodeInfo
import com.dreamos.desktop.core.service.Services
import com.dreamos.desktop.shell.data.Catalog
import com.dreamos.desktop.shell.data.HeroItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Home's New Episode banner, picked once at launch (before the intro ends) instead of when Home first draws, so the
 * banner, the hero and the "NEW EPISODE" card corners (core's badged shows, set by the same pick) arrive together.
 * One pick per profile; Home's 30-minute refresh still asks core itself.
 */
object BannerAhead {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Pair<String, Deferred<NewEpisodeInfo?>>? = null

    private fun profile() = runCatching { Services.profiles.current.value.id }.getOrDefault("")

    /** Starts the pick for the current profile (again after a profile switch). */
    fun start(): Deferred<NewEpisodeInfo?> = synchronized(this) {
        val p = profile()
        // TMDB's background lane: Home's own rows go first at launch.
        job?.takeIf { it.first == p }?.second ?: scope.async(com.dreamos.desktop.core.net.Lane.element()) { runCatching { Services.newEpisodes.bannerPick() }.getOrNull() }
            .also { job = p to it }
    }

    /** The pick when it has finished for the current profile; null while it runs or when there is none. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun now(): NewEpisodeInfo? = job?.takeIf { it.first == profile() && it.second.isCompleted }?.second?.getCompleted()

    val settled: Boolean get() = job?.let { it.first == profile() && it.second.isCompleted } == true

    /** Waits up to [maxMs] for the pick. */
    suspend fun await(maxMs: Long): NewEpisodeInfo? = withTimeoutOrNull(maxMs) { start().await() }
}

/**
 * A page's hero, beyond its art (RowsModel's hero warm-up decodes every slide's backdrop and logo): every slide's kept
 * scores asked for ahead (OMDb, within its prefetch budget), so each slide shows its scores when it comes round.
 */
object HeroAhead {
    fun ready(items: List<HeroItem>, catalog: Catalog): List<HeroItem> {
        catalog.prefetchScores(items.map { it.title })
        return items
    }
}

/** Work that runs ahead of the viewer, off the UI thread (bounded by what calls it). */
internal val aheadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Starts [block] behind the viewer. */
internal fun ahead(block: suspend CoroutineScope.() -> Unit) { aheadScope.launch(block = block) }
