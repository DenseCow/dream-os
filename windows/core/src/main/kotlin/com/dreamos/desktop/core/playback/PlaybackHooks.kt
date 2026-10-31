package com.dreamos.desktop.core.playback

/**
 * What Dream Core needs from win-content (TMDB, profiles, watch history), behind one interface so the playback code
 * and its tests don't depend on content's classes. Wired by the app after the merge (docs/port/requests.md).
 */
interface PlaybackHooks {
    /** The show's (episodes) or movie's IMDb id; the target's own when it has one. Cached by the caller. */
    suspend fun imdb(target: PlayTarget): String? = target.imdb?.takeIf { it.startsWith("tt") }

    /**
     * What the scrapers need beyond ids (alternative titles, the episode's air date, total seasons): win-content
     * fills it from TMDB within [waitMs] (cached details answer at once).
     */
    suspend fun scrapeQuery(target: PlayTarget, imdb: String, quick: Boolean, waitMs: Long): ScrapeQuery =
        if (quick) ScrapeQuery.quick(target, imdb) else ScrapeQuery(target, imdb)

    /** The profile's audio language (2 letters; Dream Player's setting, else the system's). */
    fun audioLang(): String? = ReleaseLanguage.norm(java.util.Locale.getDefault().language)

    /** The profile's Prefer <Language> Dubs switch; on unless the audio language is Japanese. */
    fun preferDubs(): Boolean = audioLang() != "ja"

    /** Where the watch history says [target] starts (ms; 0 from the beginning): a resume makes an MP4 cost more. */
    fun resumeMs(target: PlayTarget): Long = 0

    /** A link was found for a real release (not a CAM): the title's pill says Available Now at once. */
    fun streamFound(target: PlayTarget, best: String) {}

    /** AllDebrid at its magnet limit (once a day): the notice "AllDebrid Magnet Limit Reached". */
    fun accountNotice(service: DebridServiceId, title: String, body: String) {}

    /**
     * A show's genres, original language and season sizes for the skip markers' anime path. The default reads core's
     * TMDB details (cached by win-content), at most 8 s; null when they can't be had.
     */
    suspend fun showFacts(showId: Int): ShowFacts? = contentShowFacts(showId)

    /**
     * Settings > Account Summary at Startup (the pages' `notices.summary`, on by default): "Your Accounts" once per
     * start. The default reads the pages' settings file for the current profile.
     */
    /**
     * Settings › Sources & Quality as Dream Core's filter keys ([PagesSourceSettings]); they win over the stored
     * filters. The default reads the pages' file for the current profile.
     */
    fun viewerSourceSettings(): Map<String, String> = PagesSourceSettings.current()

    fun accountSummaryAtStart(): Boolean = runCatching {
        com.dreamos.desktop.core.store.DreamStore.shared.profileFile("pages").getBoolean("notices.summary", true)
    }.getOrDefault(true)
}

/** [PlaybackHooks.showFacts] over core's title details (`Services.titles`). */
suspend fun contentShowFacts(showId: Int): ShowFacts? = try {
    kotlinx.coroutines.withTimeoutOrNull(8_000) {
        val d = com.dreamos.desktop.core.service.Services.titles.details(com.dreamos.desktop.core.model.MediaKind.TV, showId)
        ShowFacts(
            animation = 16 in d.title.genreIds || d.genres.any { it.equals("Animation", ignoreCase = true) },
            originalLanguage = d.title.lang,
            episodeCounts = d.seasons.filter { it.number >= 1 }.associate { it.number to it.episodeCount },
        )
    }
} catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
