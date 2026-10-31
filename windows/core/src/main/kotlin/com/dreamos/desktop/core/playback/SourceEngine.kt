package com.dreamos.desktop.core.playback

import kotlinx.coroutines.flow.StateFlow

/**
 * What the `scrapers` module implements for Dream Core: the viewer's scraper repositories, run in the bundled
 * CPython (fire-home ScraperEngine + Scrapers, iPhone SourceService). Dream Core asks for a title's sources and reads
 * them as each provider answers; it never waits for every provider (a Play ranks what arrived after a short grace).
 *
 * Nothing secret crosses into the engine: debrid checks happen in Dream Core.
 */
interface SourceEngine {
    /** At least one scraper repository is added with a provider switched on (otherwise: "Add a Scraper Repository"). */
    fun active(): Boolean

    /**
     * Starts a scrape and returns at once. The engine bounds it by [ScrapeQuery.timeoutMs] overall and
     * [ScrapeQuery.perProviderMs] per provider; it must end (state.done) by itself. [mode] picks the lane: a PLAY
     * scrape never queues behind look-ahead ones.
     */
    fun start(query: ScrapeQuery, mode: ScrapeMode): ScrapeJob

    /** Starts Python and loads the enabled providers in the background (app start, a title page opening). */
    fun warm() {}

    /** Dream Player started: give back idle memory (providers' caches); modules stay loaded. */
    fun trim() {}

    /** Repositories or providers changed: called so listeners (Dream Core drops its kept scrapes) can react. */
    fun onChanged(listener: () -> Unit) {}
}

/** Which lane and how wide: [PLAY] a viewer is waiting; [FULL] a look-ahead (background lane); [QUICK] the pills' check. */
enum class ScrapeMode { PLAY, FULL, QUICK }

/**
 * What a Kodi-style scraper module needs (fire-home Scrapers.info): ids, titles, year, and for an episode the show
 * title, season, episode, air date. [aliases]: alternative titles. [packs]: season packs wanted (episodes, full scrape).
 */
data class ScrapeQuery(
    val target: PlayTarget,
    val imdb: String,
    val localTitle: String = target.title,
    val aliases: List<String> = emptyList(),
    val tvdb: String? = null,
    val totalSeasons: Int? = null,
    val packs: Boolean = target.isEpisode,
    val timeoutMs: Long = 20_000,
    val perProviderMs: Long = 12_000,
) {
    companion object {
        /** The pills' quick check: the fastest providers, short limits, no packs (fire-home gather light). */
        fun quick(target: PlayTarget, imdb: String, aliases: List<String> = emptyList()) =
            ScrapeQuery(target, imdb, aliases = aliases, packs = false, timeoutMs = 5_000, perProviderMs = 4_000)
    }
}

/** One title's scrape. Sources stream into [state] as each provider answers. */
interface ScrapeJob {
    val state: StateFlow<ScrapeState>
    /** Stop it (a speculative scrape no longer wanted); [state] ends with done = true. */
    fun cancel()
    /** A real Play joined this look-ahead's scrape: it moves to the Play lane from now on. */
    fun promote() {}
}

/**
 * A scrape's progress. [version] grows with every change. [answered]: providers that finished with an answer
 * (results or an honest empty); [failed]: providers that errored or timed out; [total]: providers asked.
 * [startedAtMs] / [firstResultAtMs] are on the [Clock.monotonicMs] scale.
 */
data class ScrapeState(
    val sources: List<ScrapedSource> = emptyList(),
    val version: Int = 0,
    val done: Boolean = false,
    val cancelled: Boolean = false,
    val answered: Int = 0,
    val failed: Int = 0,
    val total: Int = 0,
    val startedAtMs: Long = 0,
    val firstResultAtMs: Long? = null,
) {
    val count get() = sources.size
    /** Ended with every provider failing or timing out (the network, not "nothing out yet"). */
    val failedAll get() = done && answered == 0
}
