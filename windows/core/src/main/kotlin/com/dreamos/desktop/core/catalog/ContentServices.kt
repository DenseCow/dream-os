package com.dreamos.desktop.core.catalog

import com.dreamos.desktop.core.content.AirTimes
import com.dreamos.desktop.core.content.Details
import com.dreamos.desktop.core.content.NewReleases
import com.dreamos.desktop.core.content.Tmdb
import com.dreamos.desktop.core.content.WatchHistory
import com.dreamos.desktop.core.content.NewEpisodes
import com.dreamos.desktop.core.content.ProfileStore
import com.dreamos.desktop.core.content.Ratings
import com.dreamos.desktop.core.net.TmdbDisk
import com.dreamos.desktop.core.notices.NoticeCenterImpl
import com.dreamos.desktop.core.platform.Platform
import com.dreamos.desktop.core.service.Services
import com.dreamos.desktop.core.trakt.Trakt
import com.dreamos.desktop.core.trakt.TraktServiceImpl
import com.dreamos.desktop.core.update.UpdatesImpl
import com.dreamos.desktop.core.model.CatalogPage
import com.dreamos.desktop.core.model.CatalogRow
import com.dreamos.desktop.core.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import com.dreamos.desktop.core.content.ViewerSettings

/**
 * Puts core's content services into [Services]. The app calls [install] once at launch, before the first screen:
 * it loads the profiles, points the settings store at the current profile, connects the hooks between parts (TVmaze
 * air times for New Episodes, Trakt following My List and Continue Watching) and registers the real services.
 */
object ContentServices {
    val catalog = CatalogImpl(BrowseRowsImpl)
    @Volatile private var installed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _warmed = MutableStateFlow<Map<CatalogPage, List<CatalogRow>>>(emptyMap())

    /**
     * The rows [warm] filled, by page, for the shell's art prefetch: `CatalogImpl.artPaths(row)` gives each card's
     * image path; the shell sizes and loads them before the page shows. Pages are added as each is filled (Home first).
     */
    val warmed: StateFlow<Map<CatalogPage, List<CatalogRow>>> get() = _warmed

    /**
     * Fills Home, Movies and TV Shows ahead, in the background (call right after [install], before the launch intro):
     * from TMDB answers on disk this takes a fraction of a second, and those pages then open filled. Home asks TMDB
     * ahead of the other two (TmdbGate's lanes). Does nothing without a TMDB key.
     */
    fun warm(): Job = scope.launch {
        try {
            catalog.warm { page, rows -> _warmed.update { it + (page to rows) } }
        } catch (e: Exception) { Log.w("Catalog", "warm: ${e.message ?: e}") }
    }

    /**
     * Fills one [page] ahead, behind the viewer (fire-home CinemaBrowse.prefetchOnRest: the viewer resting on its
     * Browse tile), so it usually opens filled. Background lane: never ahead of the page on screen.
     */
    fun warmPage(page: CatalogPage): Job = scope.launch(com.dreamos.desktop.core.net.Lane.element()) {
        try { catalog.warm(listOf(page)) } catch (e: Exception) { Log.w("Catalog", "warm $page: ${e.message ?: e}") }
    }

    /** Drops what a relaunch wouldn't have in memory; the disk (TMDB answers, settings) stays. Tests only. */
    internal fun forgetMemory() {
        catalog.forgetPrepared()
        _warmed.value = emptyMap()
        Tmdb.clearLists()
        Details.clearCache()
        Details.reloadKeyArt()
        WatchHistory.clearCache()
        NewReleases.clearCache()
    }

    fun install() {
        synchronized(this) {
            ProfileStore.init()
            TmdbDisk.open(Platform.current.cacheDir.resolve("tmdb"))
            if (installed) return
            installed = true
        }
        NewEpisodes.airInfo = { id, imdb, tvdb ->
            AirTimes.info(id, imdb, tvdb)?.let { i ->
                NewEpisodes.AirInfo(i.prev?.let { NewEpisodes.AirEp(it.season, it.number, it.at) },
                    i.next?.let { NewEpisodes.AirEp(it.season, it.number, it.at) })
            }
        }
        Services.catalog = catalog
        Services.search = SearchImpl { catalog.browse }
        Services.titles = TitlesImpl()
        Services.profiles = ProfilesImpl()
        Services.history = HistoryImpl()
        Services.myList = MyListImpl()
        Services.newEpisodes = NewEpisodesImpl()
        Services.keys = KeysImpl(omdbGet = { Ratings.key() }, omdbSet = { Ratings.setKey(it) }, omdbCheck = { Ratings.checkKey(it) })
        Services.notices = NoticeCenterImpl()
        Services.updates = UpdatesImpl()
        Services.trakt = TraktServiceImpl()
        Trakt.install()
        NewEpisodes.addFollowSource { Trakt.followedShowTmdbIds() }
        ProfileStore.onSwitched = { Trakt.profileChanged(); ViewerSettings.applyTime() }
        ViewerSettings.applyTime()
        // The clock settings the pages save drive DreamTime (each profile has its own).
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        scope.launch {
            ProfileStore.current.flatMapLatest { ViewerSettings.file().changes }.filter { it.startsWith("time.") }
                .collect { ViewerSettings.applyTime() }
        }
    }
}
