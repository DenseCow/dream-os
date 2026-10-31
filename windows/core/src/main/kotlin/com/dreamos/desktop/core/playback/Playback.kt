package com.dreamos.desktop.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Dream Core for the player and the pages ([PlaybackApi]; fire-home PlayFlow + NativeResolver, iPhone
 * DreamCorePlayback + PlaybackExtras). Built once by the app ([create]) and shared.
 */
class Playback(
    val core: DreamCore,
    val lookAhead: LookAhead,
    val accounts: Accounts,
    val availability: AvailabilityChecker,
    val skips: SkipMarkers? = null,
    /** Premium expiry notices; started by [warm]. */
    val expiry: PremiumExpiry? = null,
) : PlaybackApi {

    companion object {
        /** The loading screen's words (the same on every Dream OS; STANDARDS §4: what happened and what to do). */
        val LIMIT = PlaybackError("Can't Play Right Now", "Your AllDebrid account has reached its magnet limit. It frees up automatically; try again later.")
        val BLOCKED = PlaybackError("Approve This Network", "AllDebrid is waiting for you to approve this network. Open the email AllDebrid sent you, approve it, then choose Play again.")
        val NO_ACCOUNT = PlaybackError("Connect a Debrid Service", "Add one in Settings › Accounts & Scrapers, then choose Play again.")
        val NO_SCRAPERS = PlaybackError("Add a Scraper Repository", "Add one in Settings › Accounts & Scrapers › Scrapers, then choose Play again.")
        val NOT_YET = PlaybackError("This Title Isn't Available Yet", "There's no version to stream yet. Check back soon.")
        val NOT_READY = PlaybackError("Couldn't Find a Ready Stream", "Try again in a moment, or choose another source.")
        val SOURCE_NOT_READY = PlaybackError("This Source Isn't Ready", "Try another source.")
        val NO_ANSWER = PlaybackError("Couldn't Search for Streams", "Your scrapers didn't answer. Try again in a moment.")
        const val CAM_NOTE = "Only a CAM Version Is Available"
        const val BEST_NOTE = "Playing the Best Available Version"
        /** A Play's budget: link in ~1 s usually, never a spinner forever. */
        const val PLAY_BUDGET_MS = 8_000L

        /**
         * Everything wired: [store] for small state (win-content's settings store), [secrets] for tokens (DPAPI on
         * Windows), [premiumizeClientId] from local.properties at run time (empty: Premiumize links by API key),
         * [streamCacheDir] for the stream head start (e.g. %LOCALAPPDATA%\Dream OS\stream; null turns it off).
         */
        fun create(engine: SourceEngine, hooks: PlaybackHooks, store: KeyValueStore, secrets: SecretStore, screen: ScreenFacts,
                   releases: ReleaseInfo, premiumizeClientId: String = "", streamCacheDir: java.io.File? = null,
                   http: Http = JdkHttp(), clock: Clock = Clock.SYSTEM,
                   scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                   notices: NoticeSink = CoreNoticeSink): Playback {
            val kit = DebridKit(DebridHttp(http, clock), DebridLedger(store, clock), DebridAccounts(secrets, store), clock, scope)
            val langs = LanguagePillsStore(store, clock)
            val core = DreamCore(engine, kit, SourceFilters(store, screen) { hooks.viewerSourceSettings() }, SourceMemory(store, clock), langs, hooks, store)
            streamCacheDir?.let { core.streamCache = StreamCache(it, clock) }
            val avail = AvailabilityChecker(core, releases, store, clock, scope)
            val skips = SkipMarkers(http, store, clock) { hooks.showFacts(it) }
            val accounts = Accounts(kit, store, premiumizeClientId, core)
            val expiry = PremiumExpiry(accounts, kit.accounts, store, clock, scope, notices,
                zone = { runCatching { com.dreamos.desktop.core.time.DreamTime.zone }.getOrDefault(java.time.ZoneId.systemDefault()) },
                summaryOn = hooks::accountSummaryAtStart)
            // The episode list, Up Next and Continue Watching treat an episode a source already has as aired.
            com.dreamos.desktop.core.content.WatchHistory.outEarly = { showId, season, episode -> avail.outEarly("tv:$showId:$season:$episode") }
            // The title page's season list: an episode still ahead gets one quick look in the index first.
            com.dreamos.desktop.core.content.WatchHistory.earlyRelease = { showId, season, episode, airDate ->
                kotlinx.coroutines.runBlocking { avail.earlyRelease(earlyTarget(showId, season, episode, airDate)) }
            }
            return Playback(core, LookAhead(core), accounts, avail, skips, expiry)
        }

        /** Dream Core's target for an episode of [showId] (the show's kept details give its name, IMDb id and anime flag). */
        fun earlyTarget(showId: Int, season: Int, episode: Int, airDate: String): PlayTarget {
            val d = com.dreamos.desktop.core.content.Details.cachedDetails(com.dreamos.desktop.core.model.MediaKind.TV, showId)
            val ep = com.dreamos.desktop.core.model.Episode(showId, season, episode, "", airDate = airDate)
            return if (d != null) com.dreamos.desktop.core.catalog.ContentPlaybackHooks.target(d.title, ep, d.imdbId)
            else PlayTarget(MediaKind.EPISODE, showId, "Show $showId", season = season, episode = episode, airDate = airDate.ifEmpty { null })
        }
    }

    override fun canPlay() = core.available()

    /** The error a Play that found nothing ends with: what's missing in setup, the account's state, or why nothing plays. */
    private fun failure(t: PlayTarget): PlaybackError = when {
        !core.kit.accounts.any -> NO_ACCOUNT
        !core.engine.active() -> NO_SCRAPERS
        core.allDebridBlocked() && core.kit.accounts.linked.value.all { it == DebridServiceId.ALLDEBRID } -> BLOCKED
        core.limitBlocked() -> LIMIT
        else -> when (core.miss(t)) { Miss.NOTHING -> NOT_YET; Miss.NO_ANSWER -> NO_ANSWER; else -> NOT_READY }
    }

    private fun request(t: PlayTarget, s: Stream, startMs: Long): PlayRequest {
        val note = core.relaxed(t, s)?.let { cam -> if (cam) CAM_NOTE else BEST_NOTE }
        val stream = if (note != null) s.copy(note = note) else s
        core.handing(stream)
        val cache = core.streamCache
        cache?.stop(stream.secureUrl)
        return PlayRequest(t, stream, startMs, core.alternatesFor(t), cache?.localUrl(stream))
    }

    override suspend fun play(target: PlayTarget, startMs: Long): PlayRequest {
        val t0 = core.kit.clock.monotonicMs()
        val found = core.find(target, PLAY_BUDGET_MS, DebridPurpose.PLAY, startMs)
            ?: throw failure(target).also { PlaybackLog.w("Play flow: ${it.title} (${DreamCore.secs(core.kit.clock.monotonicMs() - t0)})") }
        PlaybackLog.i("Play flow: Dream Core stream (${found.service.key.uppercase()} ${found.quality.label}) in ${DreamCore.secs(core.kit.clock.monotonicMs() - t0)}")
        return request(target, found, startMs)
    }

    override suspend fun playLoose(target: PlayTarget, startMs: Long): PlayRequest {
        val found = core.findLoose(target) ?: throw failure(target)
        return request(target, found, startMs)
    }

    override suspend fun sources(target: PlayTarget, loose: Boolean) = core.sources(target, loose)

    override suspend fun readyHashes(rows: List<SourceRow>) = core.readyHashes(rows.map { it.source })

    override suspend fun playSource(target: PlayTarget, source: Source, startMs: Long): PlayRequest {
        val f = core.resolveSource(target, source) ?: throw SOURCE_NOT_READY
        return request(target, f, startMs)
    }

    override fun failed(target: PlayTarget, stream: Stream) = core.forget(target, stream)

    override fun nextStream(target: PlayTarget): Stream? = core.nextLink(target)?.also { core.handing(it) }

    override fun handing(stream: Stream) = core.handing(stream)

    override fun firstFrame(target: PlayTarget, stream: Stream) = core.firstFrame(target, stream)

    override suspend fun survey(target: PlayTarget) = core.survey(target)

    override fun titleOpened(target: PlayTarget) {
        lookAhead.titleOpened(target)
        availability.ask(listOf(target), priority = true)
    }

    override fun cardRested(target: PlayTarget) = lookAhead.cardRested(target)

    override fun continueWatching(targets: List<PlayTarget>) = lookAhead.continueWatching(targets)

    override fun nextEpisodeAhead(next: PlayTarget) = lookAhead.nextEpisode(next)

    override fun playerActive(active: Boolean) {
        lookAhead.playerActive(active)
        availability.paused = active
    }

    override fun abandon(target: PlayTarget) = core.abandon(target)

    override fun warm() {
        core.warm()
        expiry?.start()
    }

    override suspend fun skipMarkers(target: PlayTarget, durationMs: Long, chapters: List<SkipChapter>): SkipInfo =
        skips?.info(target, durationMs, chapters) ?: SkipInfo.NONE

    override fun learnIntro(target: PlayTarget, fromMs: Long, toMs: Long) { skips?.learn(target, fromMs, toMs) }

    override fun introConfirmed(target: PlayTarget) { skips?.confirmLearned(target) }

    override fun heardLanguages(target: PlayTarget, hash: String?, langs: Set<String>) {
        val codes = langs.mapNotNull { ReleaseLanguage.norm(it) }.toSet()
        if (codes.isNotEmpty() && target.tmdbId > 0) core.langs.heard(target.key, hash, codes)
    }

    override fun subtitleIndex(target: PlayTarget, langs: Set<String>) =
        core.langs.noteIndex(target.key, langs.mapNotNull { ReleaseLanguage.norm(it) }.toSet())

    override fun viewerSubtitleLanguages(langs: List<String>) =
        core.langs.noteViewerSubs(langs.mapNotNull { ReleaseLanguage.norm(it) }.distinct())
}
