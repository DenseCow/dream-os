package com.dreamos.desktop.core.catalog

import com.dreamos.desktop.core.content.*
import com.dreamos.desktop.core.model.*
import com.dreamos.desktop.core.service.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Search (fire-home CinemaSearch): spoken-title search, a person's titles, a genre's rows; anime and Kids rules applied. */
class SearchImpl(private val browse: () -> BrowseRows) : Search {
    override suspend fun searchAll(query: String): SearchResults = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty() || !Tmdb.hasKey()) return@withContext SearchResults()
        val (titles, person, genre) = RowPaging.all(
            { try { Tmdb.searchSpoken(q) } catch (e: Exception) { emptyList<Title>() } },
            { try { Tmdb.searchPerson(q) } catch (e: Exception) { null } },
            { try { browse().genreSearch(q) } catch (e: Exception) { null } },
        )
        // A title search shows what was asked for, anime included (the anime rule is for rows, charts and genre
        // search); a person's titles aren't repeated in the title results.
        val p = person as Tmdb.PersonResult?
        @Suppress("UNCHECKED_CAST")
        val shown = (titles as List<Title>).filter { t -> t.posterPath != null && !t.adult && (p == null || p.titles.none { it.key == t.key }) }
        SearchResults(
            titles = shown,
            person = p?.let { SearchResults.Person(it.id, it.name, it.heading, it.titles) },
            genre = genre as SearchResults.GenreHits?,
        )
    }

    override suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) { browse().suggestions(query) }

    override suspend fun trending(): List<Title> = withContext(Dispatchers.IO) {
        if (!Tmdb.hasKey()) emptyList() else try { Tmdb.trendingShown(art = "poster") } catch (e: Exception) { emptyList() }
    }
}

class ProfilesImpl : Profiles {
    override val all: StateFlow<List<Profile>> get() = ProfileStore.all
    override val current: StateFlow<Profile> get() = ProfileStore.current
    override fun isSetUp() = ProfileStore.isSetUp()
    override fun markSetUp() = ProfileStore.markSetUp()
    override suspend fun select(id: String, pin: String?) = withContext(Dispatchers.IO) { ProfileStore.select(id, pin) }
    override suspend fun selectTrusted(id: String) = withContext(Dispatchers.IO) { ProfileStore.selectTrusted(id) }
    override suspend fun checkPin(id: String, pin: String) = withContext(Dispatchers.IO) { ProfileStore.checkPin(id, pin) }
    override fun pinNeeded(id: String): String? = ProfileStore.pinNeeded(id)
    override suspend fun add(name: String) = withContext(Dispatchers.IO) { ProfileStore.add(name) }
    override suspend fun save(profile: Profile, pin: String?) = withContext(Dispatchers.IO) { ProfileStore.save(profile, pin) }
    override suspend fun delete(id: String) = withContext(Dispatchers.IO) { ProfileStore.delete(id) }
}

class HistoryImpl : History {
    override val version: StateFlow<Long> get() = WatchHistory.version
    override suspend fun continueWatching() = withContext(Dispatchers.IO) { WatchHistory.entries() }
    override suspend fun entry(key: String) = withContext(Dispatchers.IO) { WatchHistory.entry(key) }
    override suspend fun recent() = withContext(Dispatchers.IO) { WatchHistory.recent() }
    override suspend fun report(request: PlayRequest, positionMs: Long, durationMs: Long, realWatchedMs: Long, exact: Boolean) =
        withContext(Dispatchers.IO) { WatchHistory.report(request, positionMs, durationMs, realWatchedMs, exact) }
    override suspend fun remove(key: String) = withContext(Dispatchers.IO) { WatchHistory.remove(key) }
    override suspend fun startPositionMs(request: PlayRequest) = withContext(Dispatchers.IO) { WatchHistory.startPositionMs(request) }
    override suspend fun nextFor(show: Title): PlayRequest? = if (!show.isShow) null else withContext(Dispatchers.IO) { WatchHistory.nextFor(show) }
    override suspend fun playLabel(title: Title) = withContext(Dispatchers.IO) { WatchHistory.playLabel(title) }
    override suspend fun isWatched(title: Title) = withContext(Dispatchers.IO) {
        if (title.isShow) Watched.checkShow(title.id)
        Watched.title(title)
    }
    override suspend fun isEpisodeWatched(showId: Int, season: Int, episode: Int) = withContext(Dispatchers.IO) { Watched.episode(showId, season, episode) }
}

class MyListImpl : MyList {
    override val items: StateFlow<List<Title>> get() = MyListStore.flow
    override fun contains(key: String) = MyListStore.contains(key)
    override suspend fun toggle(title: Title) = withContext(Dispatchers.IO) { MyListStore.toggle(title) }
}

class NewEpisodesImpl : com.dreamos.desktop.core.service.NewEpisodes {
    override suspend fun snapshot() = withContext(Dispatchers.IO) {
        if (!Tmdb.hasKey()) NewEpisodesSnapshot() else com.dreamos.desktop.core.content.NewEpisodes.load().model()
    }
    override suspend fun markSeen(item: CatalogItem) = withContext(Dispatchers.IO) {
        val ep = item.episode ?: return@withContext
        com.dreamos.desktop.core.content.NewEpisodes.markSeen("${item.title.id}:s${ep.season}e${ep.number}")
    }
    override suspend fun bannerPick() = withContext(Dispatchers.IO) { if (!Tmdb.hasKey()) null else NewEpisodeSpot.pick() }
    override suspend fun bannerShown(episode: NewEpisodeInfo) = withContext(Dispatchers.IO) { NewEpisodeSpot.displayed(episode) }
    override suspend fun bannerDone(episode: NewEpisodeInfo) = withContext(Dispatchers.IO) { NewEpisodeSpot.done(episode) }
    override fun badgedShows(): Set<Int> = NewEpisodeSpot.badged
}

/** The viewer's TMDB and OMDb keys. [omdbStore] reads / writes the OMDb key (Ratings owns its file). */
class KeysImpl(
    private val omdbGet: () -> String = { com.dreamos.desktop.core.store.DreamStore.shared.file("omdb").string("key") },
    private val omdbSet: (String) -> Unit = { com.dreamos.desktop.core.store.DreamStore.shared.file("omdb").putString("key", it.trim()) },
    private val omdbCheck: (String) -> Boolean = { false },
) : Keys {
    private val _tmdb = MutableStateFlow(Tmdb.key())
    private val _omdb = MutableStateFlow(omdbGet())
    override val tmdb: StateFlow<String> get() = _tmdb
    override val omdb: StateFlow<String> get() = _omdb

    override suspend fun checkTmdb(key: String): Boolean = withContext(Dispatchers.IO) {
        try { Tmdb.checkKey(key) } catch (e: Exception) { throw DreamError("Couldn't reach TMDB. Check the connection and try again.", cause = e) }
    }

    override suspend fun checkOmdb(key: String): Boolean = withContext(Dispatchers.IO) {
        try { omdbCheck(key) } catch (e: Exception) { throw DreamError("Couldn't reach OMDb. Check the connection and try again.", cause = e) }
    }

    override fun setTmdb(key: String) {
        Tmdb.setKey(key)
        Tmdb.clearLists()
        WatchHistory.clearCache()
        _tmdb.value = Tmdb.key()
        ContentPrefs.bump()
    }

    override fun setOmdb(key: String) {
        omdbSet(key)
        _omdb.value = omdbGet()
    }
}
