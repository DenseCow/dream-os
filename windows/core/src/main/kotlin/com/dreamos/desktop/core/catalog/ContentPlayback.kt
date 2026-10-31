package com.dreamos.desktop.core.catalog

import com.dreamos.desktop.core.content.ContentPrefs
import com.dreamos.desktop.core.content.Details
import com.dreamos.desktop.core.content.MyListStore
import com.dreamos.desktop.core.content.RowMix
import com.dreamos.desktop.core.content.Tmdb
import com.dreamos.desktop.core.content.ViewerSettings
import com.dreamos.desktop.core.content.WatchHistory
import com.dreamos.desktop.core.model.Episode
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.Notice
import com.dreamos.desktop.core.model.PlayRequest
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.notices.Notices
import com.dreamos.desktop.core.playback.DebridServiceId
import com.dreamos.desktop.core.playback.KeyValueStore
import com.dreamos.desktop.core.playback.PlayTarget
import com.dreamos.desktop.core.playback.PlaybackHooks
import com.dreamos.desktop.core.playback.ReleaseInfo
import com.dreamos.desktop.core.playback.ScrapeQuery
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.time.DreamTime
import com.dreamos.desktop.core.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import com.dreamos.desktop.core.playback.MediaKind as PlayKind

/**
 * What Dream Core asks of content (docs/port/requests.md, win-playback and win-shell): the IMDb id, the scrape query's
 * alternative titles / TVDB id / season count, the profile's audio language and Prefer Dubs, where the history resumes,
 * and the AllDebrid limit notice. The app passes it to `Playback.create(hooks = ContentPlaybackHooks, ...)`.
 * Every TMDB lookup goes through the 3 h lookup cache and the disk (TmdbDisk), so a title page's Play costs nothing.
 */
object ContentPlaybackHooks : PlaybackHooks {
    private fun showKind(t: PlayTarget) = if (t.kind == PlayKind.EPISODE) MediaKind.TV else MediaKind.MOVIE

    /** The title's TMDB details with external ids and alternative titles (the scrapers' extra names). Blocking. */
    private fun details(t: PlayTarget): JsonObject? =
        if (t.tmdbId <= 0 || !Tmdb.hasKey()) null
        else WatchHistory.tmdb("/${showKind(t).path}/${t.tmdbId}", "&append_to_response=external_ids,alternative_titles")

    override suspend fun imdb(target: PlayTarget): String? {
        target.imdb?.takeIf { it.startsWith("tt") }?.let { return it }
        Details.cachedDetails(showKind(target), target.tmdbId)?.imdbId?.let { return it }
        return withContext(Dispatchers.IO) { details(target)?.let(::imdbOf) }
    }

    /** Blocking TMDB calls a caller only waits on for a moment; they finish here (and land in the cache) on their own. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun scrapeQuery(target: PlayTarget, imdb: String, quick: Boolean, waitMs: Long): ScrapeQuery {
        // The details call blocks: a timeout around withContext would wait for it anyway, so it runs on its own and
        // the wait really ends after [waitMs].
        val call = background.async { details(target) }
        val d = withTimeoutOrNull(waitMs.coerceAtLeast(1)) { call.await() }
            ?: return if (quick) ScrapeQuery.quick(target, imdb) else ScrapeQuery(target, imdb)
        val q = query(target, imdb, d)
        return if (quick) ScrapeQuery.quick(target, imdb, q.aliases) else q
    }

    /** The scrape query from a details answer (pure; the iPhone's ScraperInfo names). */
    fun query(target: PlayTarget, imdb: String, d: JsonObject): ScrapeQuery {
        val show = target.kind == PlayKind.EPISODE
        val title = d.str(if (show) "name" else "title").ifEmpty { target.title }
        val seen = hashSetOf(title.lowercase())
        val aliases = ArrayList<String>()
        fun alias(s: String) { val t = s.trim(); if (t.isNotEmpty() && seen.add(t.lowercase()) && aliases.size < 12) aliases += t }
        alias(d.str(if (show) "original_name" else "original_title"))
        val alt = d.o("alternative_titles")
        for (o in (alt.a("titles") ?: alt.a("results")).objects()) {
            if (o.str("iso_3166_1") in ENGLISH_COUNTRIES) alias(o.str("title"))
        }
        val tvdb = d.o("external_ids").int("tvdb_id").takeIf { it > 0 }?.toString()
        val seasons = d.int("number_of_seasons").takeIf { show && it > 0 }
        return ScrapeQuery(target, imdb, localTitle = title, aliases = aliases, tvdb = tvdb, totalSeasons = seasons)
    }

    private val ENGLISH_COUNTRIES = setOf("US", "GB", "CA", "AU", "NZ", "IE")

    private fun imdbOf(d: JsonObject): String? =
        listOf(d.o("external_ids").str("imdb_id"), d.str("imdb_id")).firstOrNull { it.startsWith("tt") }

    override fun audioLang(): String? = ContentPrefs.audioLang() ?: super.audioLang()

    override fun preferDubs(): Boolean = audioLang() != "ja" && ViewerSettings.preferDubs()

    override fun resumeMs(target: PlayTarget): Long = try { WatchHistory.startPositionMs(request(target)) } catch (e: Exception) { 0L }

    override fun accountNotice(service: DebridServiceId, title: String, body: String) {
        Notices.post("account:${service.name.lowercase()}:${DreamTime.todayYmd()}", Notice.TYPE_LIMIT, title, body)
    }

    // ---- between content's and Dream Core's types ----

    /** Dream Core's target for a title (and an episode of it). */
    fun target(title: Title, episode: Episode? = null, imdb: String? = null): PlayTarget {
        val d = Details.cachedDetails(title.kind, title.id)
        val show = title.isShow && episode != null
        return PlayTarget(
            kind = if (show) PlayKind.EPISODE else PlayKind.MOVIE,
            tmdbId = title.id, title = title.name, year = title.year.toIntOrNull(),
            imdb = imdb ?: d?.imdbId,
            season = episode?.season?.takeIf { show }, episode = episode?.number?.takeIf { show },
            episodeTitle = episode?.name?.takeIf { show && it.isNotBlank() },
            originalLanguage = title.lang.ifEmpty { null }, isAnime = RowMix.isAnime(title),
            runtimeMin = (if (show) episode?.runtime ?: 0 else 0).takeIf { it > 0 } ?: d?.runtime ?: 0,
            airDate = (if (show) episode?.airDate else title.date)?.ifEmpty { null },
            backdrop = title.backdrop("w1280"), poster = title.poster("w342"),
        )
    }

    /** Dream Core's target for what the viewer asked to play. */
    fun target(r: PlayRequest): PlayTarget {
        val ep = if (r.season != null && r.episode != null) Episode(r.title.id, r.season, r.episode, r.episodeName.orEmpty()) else null
        return target(r.title, ep, r.imdbId)
    }

    /** The history's request for a Dream Core target (to read where it resumes). */
    fun request(t: PlayTarget): PlayRequest {
        val kind = showKind(t)
        val title = Details.cachedDetails(kind, t.tmdbId)?.title
            ?: Title(t.tmdbId, kind, t.title, year = t.year?.toString().orEmpty(), lang = t.originalLanguage.orEmpty())
        return PlayRequest(title, t.imdb, t.season, t.episode, t.episodeTitle)
    }
}

/**
 * Dates and My List for the availability pills (win-playback's ReleaseInfo): DreamTime's zone and words, and the movie's
 * digital release day from TMDB's release_dates (US type 4, else the earliest anywhere).
 */
object ContentReleases : ReleaseInfo {
    override fun today(): LocalDate = LocalDate.parse(DreamTime.todayYmd())
    override fun midnight(date: String): Long = DreamTime.midnight(date) ?: super.midnight(date)
    override fun dateWords(date: String) = DreamTime.dateWords(date)
    override fun airWords(at: Long) = DreamTime.airLabel(at)
    override fun cardDate(at: Long): Pair<String, String> {
        val ymd = DreamTime.ymd(at)
        return DreamTime.dateWords(ymd) to DreamTime.shortDate(ymd)
    }

    override suspend fun digitalDate(tmdbId: Int): String? = if (tmdbId <= 0 || !Tmdb.hasKey()) null else withContext(Dispatchers.IO) {
        WatchHistory.tmdb("/movie/$tmdbId", "&append_to_response=release_dates")?.let(::digitalFrom)
    }

    /** The US digital day (release type 4), else the earliest digital day anywhere; null when none is listed. */
    fun digitalFrom(d: JsonObject): String? {
        val results = d.o("release_dates").a("results").objects()
        fun days(o: JsonObject) = o.a("release_dates").objects().filter { it.int("type") == 4 }
            .map { it.str("release_date").take(10) }.filter { it.length == 10 }
        return results.firstOrNull { it.str("iso_3166_1") == "US" }?.let { days(it).minOrNull() }
            ?: results.flatMap { days(it) }.minOrNull()
    }

    override fun myListKeys(): Set<String> = MyListStore.items().mapTo(HashSet()) { it.key }
}

/**
 * Dream Core's small state in the settings store (win-playback's KeyValueStore): one file per [name] under
 * `%APPDATA%\Dream OS\settings`, written behind and atomically like every settings file.
 */
class SettingsKeyValueStore(name: String) : KeyValueStore {
    private val f = DreamStore.shared.file(name)
    override fun get(key: String) = f.getString(key)
    override fun put(key: String, value: String) = f.putString(key, value)
    override fun remove(key: String) = f.remove(key)
}
