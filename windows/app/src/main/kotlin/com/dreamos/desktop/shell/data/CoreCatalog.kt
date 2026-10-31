package com.dreamos.desktop.shell.data

import com.dreamos.desktop.core.catalog.BrowseRowsImpl
import com.dreamos.desktop.core.content.Tmdb
import com.dreamos.desktop.core.model.CatalogItem
import com.dreamos.desktop.core.model.CatalogPage
import com.dreamos.desktop.core.model.CatalogRow
import com.dreamos.desktop.core.model.DiscoverFilter
import com.dreamos.desktop.core.model.Genre
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.RowStyle as CoreStyle
import com.dreamos.desktop.core.playback.AvailabilityApi
import com.dreamos.desktop.core.playback.PillTone
import com.dreamos.desktop.core.playback.PlayTarget
import com.dreamos.desktop.core.service.Services
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * [Catalog] over core's services (`Services.catalog / search / titles / myList / history`, registered by
 * `ContentServices.install()`) and Dream Core's pills ([availability], null until playback is wired).
 */
class CoreCatalog(private val availability: () -> AvailabilityApi?) : Catalog {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logos = ConcurrentHashMap<String, String>()

    override val ready: Boolean get() = Tmdb.hasKey()

    // ---- rows ----

    private fun style(r: CatalogRow): RowStyle = when {
        r.isSpotlight -> RowStyle.SPOTLIGHT
        else -> when (r.style) {
            CoreStyle.POSTER -> RowStyle.POSTER
            CoreStyle.LANDSCAPE, CoreStyle.BRAND -> RowStyle.LANDSCAPE
            CoreStyle.BIG -> RowStyle.BIG
            CoreStyle.TOP10 -> RowStyle.TOP10
            CoreStyle.CONTINUE -> RowStyle.CONTINUE
        }
    }

    private fun card(i: CatalogItem) = CardItem(
        title = i.title,
        // Continue Watching's movies: "42 Min Left" (core's timeLeft); everything else the row's own line.
        sub = i.subtitle ?: i.timeLeft,
        // A followed show with an episode the New Episode banner would offer wears "NEW EPISODE" in every row.
        badge = i.badge?.takeIf { it != "UP NEXT" }
            ?: if (i.title.isShow && i.episode == null && i.title.id in Services.newEpisodes.badgedShows()) "NEW EPISODE" else null,
        progress = i.progress?.toFloat(),
        still = i.episode?.stillPath,
        keyArt = i.keyArtPath,
        plainArt = i.plainArtPath,
        upNext = i.badge == "UP NEXT",
        kicker = i.kicker,
        season = i.episode?.season,
        episode = i.episode?.number,
        airDate = i.episode?.airDate?.ifEmpty { null },
        upcoming = i.episode?.upcoming == true,
    )

    private fun row(r: CatalogRow, page: CatalogPage): Row {
        // A deep row asks core for its next cards as the viewer nears its end; the row it asks with grows with it. The
        // next page is fetched as soon as the one before it is handed over (its Watched answers read and its pill
        // checks queued behind the viewer), so reaching the end of a row finds the next cards already there.
        var asked = r
        var next: kotlinx.coroutines.Deferred<List<CardItem>>? = null
        fun fetchNext() = scope.async(com.dreamos.desktop.core.net.Lane.element(), start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val items = runCatching { Services.catalog.more(asked, page) }.getOrDefault(emptyList())
            asked = asked.copy(items = asked.items + items)
            readyPills(items.map { it.title }, NEXT_PAGE_PILLS_MS)
            items.map(::card)
        }
        val more: (suspend () -> List<CardItem>)? = if (!r.hasMore) null else ({
            val page1 = (next ?: fetchNext()).also { it.start() }.await()
            next = if (page1.isEmpty()) null else fetchNext().also { it.start() }
            page1
        })
        // The second page starts at once, behind the viewer.
        if (more != null) next = fetchNext().also { it.start() }
        return Row(r.id, r.name.ifEmpty { "Spotlight" }, style(r), r.items.map(::card), r.hubs, more)
    }

    /** Placeholders now, each filled when the page asks; core claims each title for one row only. */
    private suspend fun slots(page: CatalogPage): List<RowSlot> {
        // A fresh page session: rows claim their titles anew.
        return Services.catalog.rowSlots(page).map { p ->
            RowSlot(p.id, p.name.ifEmpty { "Spotlight" }, style(p)) {
                val filled = Services.catalog.fill(p, page)?.takeIf { it.items.isNotEmpty() || it.hubs.isNotEmpty() }
                // The New Episode pick (running since launch) sets which shows wear "NEW EPISODE": a row holding a show
                // the viewer follows (My List or watched) is built after it; any other row has nothing to wait for.
                if (filled != null && !com.dreamos.desktop.preload.BannerAhead.settled && followsAny(filled)) {
                    com.dreamos.desktop.preload.BannerAhead.await(BADGES_WAIT_MS)
                }
                filled?.let { row(it, page) }
            }
        }
    }

    /** [r] has a show the viewer follows, the only kind of card that can wear "NEW EPISODE". */
    private suspend fun followsAny(r: CatalogRow): Boolean = r.items.any { i ->
        i.title.isShow && (Services.myList.contains(i.title.key) || runCatching { Services.history.entry(i.title.key) }.getOrNull() != null)
    }

    override suspend fun home() = slots(CatalogPage.Home)

    override suspend fun hero(): List<HeroItem> = heroes(CatalogPage.Home)

    // Every slide's logo is looked up at once (on a first launch each is a TMDB call).
    private suspend fun heroes(page: CatalogPage): List<HeroItem> = kotlinx.coroutines.coroutineScope {
        Services.catalog.spotlight(page).map { i -> i to async { logo(i.title) } }.map { (i, logo) ->
            HeroItem(i.title, i.kicker ?: if (i.title.isShow) "Series" else "Movie", logo.await())
        }
    }

    override suspend fun hubHero(hub: Hub) = heroes(hub.page)
    override suspend fun hub(hub: Hub) = slots(hub.page)
    override suspend fun chips(hub: Hub): List<Genre> = Services.catalog.chips(hub.page)

    override suspend fun genres() = Services.catalog.genres()
    override suspend fun services() = Services.catalog.services()
    override suspend fun seasonHubs() = Services.catalog.hubs()
    override suspend fun seasonHub(id: String) = Services.catalog.hub(id)
    override suspend fun entryArt(kind: MediaKind): Title? =
        Services.catalog.spotlight(if (kind == MediaKind.MOVIE) CatalogPage.Movies else CatalogPage.Shows).firstOrNull()?.title

    // ---- search and Discover ----

    override suspend fun search(query: String) = Services.search.searchAll(query)
    override suspend fun trending() = Services.search.trending()
    override suspend fun suggestions(query: String) = runCatching { Services.search.suggestions(query) }.getOrDefault(emptyList())
    override suspend fun discover(filter: DiscoverFilter, page: Int) = Services.catalog.discover(filter, page)
    override suspend fun discoverCategories(kind: MediaKind) = Services.catalog.discoverCategories(kind)

    // ---- titles ----

    init {
        Covers.known = { com.dreamos.desktop.core.content.Details.knownKeyArt(it) }
    }

    override suspend fun withKeyArt(items: List<CardItem>, wait: Int, maxMs: Long, front: Boolean): List<CardItem> {
        fun needs(c: CardItem) = c.keyArt == null && c.still == null && c.episode == null
        if (items.none(::needs)) return items
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val first = items.take(wait).filter(::needs).map { it.title }
            if (maxMs > 0) com.dreamos.desktop.core.content.Details.primeKeyArt(first, maxMs, soon = true, front = front)
            items.map { c ->
                if (!needs(c)) c
                else c.copy(keyArt = com.dreamos.desktop.core.content.Details.knownKeyArt(c.title),
                    plainArt = c.plainArt ?: com.dreamos.desktop.core.content.Details.knownPlainArt(c.title))
            }
        }
    }

    override suspend fun logo(t: Title): String? {
        logos[t.key]?.let { return it.ifEmpty { null } }
        val p = runCatching { Services.titles.titleLogo(t.kind, t.id) }.getOrNull()
        logos[t.key] = p.orEmpty()
        return p
    }

    // ---- scores (kept by core's Ratings; asked ahead for heroes, OMDb only, within its prefetch budget) ----

    override fun scores(t: Title): HeroScores? = runCatching { com.dreamos.desktop.core.content.Ratings.cached(t) }.getOrNull()
        ?.let { s -> HeroScores(s.tomatometer, s.audienceBadge).takeIf { it.tomato != null || it.audience != null } }

    override fun prefetchScores(titles: List<Title>) {
        val due = titles.filter { scoresAsked.add(it.key) && scores(it)?.tomato == null }
        if (due.isNotEmpty()) scope.launch(com.dreamos.desktop.core.net.Lane.element()) {
            for (t in due) runCatching {
                val d = Services.titles.details(t.kind, t.id)
                com.dreamos.desktop.core.content.Ratings.scores(d, prefetch = true)
            }
        }
    }

    /** Titles whose scores were asked ahead this session (at most a hero's slides per page visited). */
    private val scoresAsked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun genreNames(t: Title, max: Int): List<String> =
        t.genreIds.mapNotNull { BrowseRowsImpl.genreName(t.kind, it) }.take(max)

    // ---- the viewer's own ----

    override val myList: StateFlow<List<Title>> get() = Services.myList.items
    override fun toggleMyList(t: Title) {
        if (Services.myList.contains(t.key)) removed.tryEmit(Removal(t.key, PersonalRow.MY_LIST))
        scope.launch { Services.myList.toggle(t) }
    }

    /** The card leaves its rows now; core hides the history entry (it comes back only if watched again). */
    override fun removeContinue(t: Title) {
        removed.tryEmit(Removal(t.key, PersonalRow.CONTINUE))
        scope.launch { Services.history.remove(t.key) }
    }

    private val removed = kotlinx.coroutines.flow.MutableSharedFlow<Removal>(extraBufferCapacity = 16)
    override val removals: Flow<Removal> get() = removed

    // Core's changes, heard once for the app and counted, so a page that wasn't showing still learns of them.
    private val count = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private val counting = java.util.concurrent.atomic.AtomicBoolean(false)
    override val changeCount: StateFlow<Long>
        get() {
            if (counting.compareAndSet(false, true)) scope.launch {
                // Before ContentServices.install there's nothing to hear yet (an empty flow): the next ask tries again.
                try { Services.catalog.changes.collect { watched.clear(); count.value++ } } finally { counting.set(false) }
            }
            return count
        }
    override val changes: Flow<Unit> get() = changeCount.drop(1).map { }

    // ---- pills (movies; a show's pill belongs to its episodes on the title page) ----

    private fun target(t: Title) = PlayTarget(
        com.dreamos.desktop.core.playback.MediaKind.MOVIE, t.id, t.name, t.year.toIntOrNull(),
        originalLanguage = t.lang.ifEmpty { null }, airDate = t.date.ifEmpty { null },
    )

    /** Watched answers as cards ask for them (history's ledger, read off the UI thread), kept for the session. */
    private val watched = ConcurrentHashMap<String, Boolean>()
    private val watchedChanged = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 256)

    override fun pill(t: Title, narrow: Boolean): Pill? {
        // Watched replaces the availability pill on anything the viewer finished (STANDARDS section 5).
        if (watched[t.key] == true) return Pill(listOf("Watched"), Pill.Tone.WATCHED)
        if (t.isShow) return null
        val p = availability()?.cardPill(target(t), narrow) ?: return null
        val tone = when (p.tone) {
            PillTone.GOOD -> Pill.Tone.GOOD
            PillTone.WARN -> Pill.Tone.WARN
            PillTone.BAD -> Pill.Tone.BAD
            PillTone.WATCHED -> Pill.Tone.WATCHED
            PillTone.PLAIN -> Pill.Tone.PLAIN
        }
        return Pill(p.labels, tone)
    }

    private fun episodeTarget(show: Title, season: Int, episode: Int, name: String, airDate: String) = PlayTarget(
        com.dreamos.desktop.core.playback.MediaKind.EPISODE, show.id, show.name, show.year.toIntOrNull(),
        season = season, episode = episode, episodeTitle = name.ifBlank { null },
        originalLanguage = show.lang.ifEmpty { null }, airDate = airDate.ifEmpty { null },
    )

    override fun episodePill(show: Title, season: Int, episode: Int, name: String, airDate: String): Pill? {
        val p = availability()?.cardPill(episodeTarget(show, season, episode, name, airDate), false) ?: return null
        val tone = when (p.tone) {
            PillTone.GOOD -> Pill.Tone.GOOD
            PillTone.WARN -> Pill.Tone.WARN
            PillTone.BAD -> Pill.Tone.BAD
            PillTone.WATCHED -> Pill.Tone.WATCHED
            PillTone.PLAIN -> Pill.Tone.PLAIN
        }
        return Pill(p.labels, tone)
    }

    override fun askEpisode(show: Title, season: Int, episode: Int, name: String, airDate: String) {
        availability()?.ask(listOf(episodeTarget(show, season, episode, name, airDate)), priority = true)
    }

    override fun ask(titles: List<Title>) {
        val unknown = titles.filter { !watched.containsKey(it.key) }
        if (unknown.isNotEmpty()) scope.launch {
            for (t in unknown) {
                val w = runCatching { Services.history.isWatched(t) }.getOrDefault(false)
                if (watched.put(t.key, w) != w && w) watchedChanged.tryEmit(t.key)
            }
        }
        val a = availability() ?: return
        titles.filter { !it.isShow }.takeIf { it.isNotEmpty() }?.let { l -> a.ask(l.map(::target)) }
    }

    override suspend fun readyPills(titles: List<Title>, maxMs: Long, front: Boolean) {
        kotlinx.coroutines.withTimeoutOrNull(maxMs) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                for (t in titles) if (!watched.containsKey(t.key)) watched[t.key] = runCatching { Services.history.isWatched(t) }.getOrDefault(false)
            }
            val a = availability() ?: return@withTimeoutOrNull
            val checker = a as? com.dreamos.desktop.core.playback.AvailabilityChecker
            val movies = titles.filter { !it.isShow && watched[it.key] != true }.map(::target)
            a.ask(movies, priority = front)
            // Wait only for titles that get a card pill, and only while answers keep coming (an account that can't
            // answer, or none at all, must not hold the row).
            var left = movies.filter { a.peek(it.key) == null && checker?.mayHavePill(it) != false }
            var quietSince = System.currentTimeMillis()
            while (left.isNotEmpty() && System.currentTimeMillis() - quietSince < QUIET_MS) {
                kotlinx.coroutines.delay(40)
                val now = left.filter { a.peek(it.key) == null }
                if (now.size < left.size) quietSince = System.currentTimeMillis()
                left = now
            }
        }
    }

    override val pillUpdates: Flow<String>
        get() = kotlinx.coroutines.flow.merge(availability()?.updates?.map { it.first } ?: emptyFlow(), watchedChanged)

    /** History changed (a title finished, a profile switched): Watched answers are asked again. */
    fun forgetWatched() = watched.clear()

    private companion object {
        /** A row stops waiting for pills once no answer has come for this long. */
        const val QUIET_MS = 500L
        /** A deep row's next page (fetched behind the viewer) waits this long for its pills before it is handed over. */
        const val NEXT_PAGE_PILLS_MS = 1_500L
        /** How long a row waits for the New Episode pick before building its cards (it runs from launch). */
        const val BADGES_WAIT_MS = 1_500L
    }
}
