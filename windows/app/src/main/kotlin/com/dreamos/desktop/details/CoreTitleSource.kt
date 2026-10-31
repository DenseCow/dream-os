package com.dreamos.desktop.details

import com.dreamos.desktop.core.model.Episode
import kotlinx.coroutines.async
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.model.TitleInfo
import com.dreamos.desktop.core.playback.AvailabilityApi
import com.dreamos.desktop.core.playback.PillGlyph
import com.dreamos.desktop.core.playback.PillTone
import com.dreamos.desktop.core.playback.PlayTarget
import com.dreamos.desktop.core.playback.MediaKind as PKind
import com.dreamos.desktop.core.playback.PlaybackApi
import com.dreamos.desktop.core.service.Services
import com.dreamos.desktop.settings.kit.Glyph
import com.dreamos.desktop.settings.kit.PillKind
import com.dreamos.desktop.settings.kit.TitleRef
import java.time.LocalDate
import java.util.Locale
import com.dreamos.desktop.core.model.TitleDetails as CoreDetails
import com.dreamos.desktop.core.playback.Pill as CorePill

/**
 * The title page on core: details, scores, episodes and people from win-content's [Services]; the availability,
 * Watched and language pills and Choose a Source from win-playback ([availability], [playback], which the app passes
 * in when it creates them; without them the page simply shows no pills and no sources).
 */
class CoreTitleSource(
    private val availability: AvailabilityApi? = null,
    private val playback: PlaybackApi? = null,
) : TitleSource {
    private val titles get() = Services.titles
    private val history get() = Services.history
    /** The last details per title, so a season or Choose a Source can build its target. */
    private val known = java.util.concurrent.ConcurrentHashMap<String, CoreDetails>()

    private fun kindOf(ref: TitleRef) = if (ref.isShow) MediaKind.TV else MediaKind.MOVIE

    /** [airDate]: an episode's own listed day (its pill and the "still ahead" rule go by it); else the title's. */
    private fun target(d: CoreDetails, season: Int? = null, episode: Int? = null, episodeName: String? = null, airDate: String? = null): PlayTarget = PlayTarget(
        kind = if (season != null) PKind.EPISODE else PKind.MOVIE,
        tmdbId = d.title.id, title = d.title.name, year = d.title.year.toIntOrNull(), imdb = d.imdbId,
        season = season, episode = episode, episodeTitle = episodeName,
        originalLanguage = d.title.lang.ifBlank { null }, spokenLanguages = d.info?.spoken ?: emptyList(),
        runtimeMin = d.runtime, airDate = airDate?.ifBlank { null } ?: d.title.date.ifBlank { null },
        backdrop = d.title.backdrop("w1280"), poster = d.title.poster(), logo = d.logo(),
    )

    /** Movies only: a show's card pill belongs to its episodes (a long-running show gets none). */
    private fun cardTarget(t: Title) = if (t.isShow) null else PlayTarget(PKind.MOVIE, t.id, t.name, t.year.toIntOrNull(), airDate = t.date.ifBlank { null }, poster = t.poster())

    private fun pill(p: CorePill?): PillSpec? = p?.let {
        PillSpec(it.label, when (it.tone) {
            PillTone.GOOD -> PillKind.Green; PillTone.WARN -> PillKind.Amber; PillTone.BAD -> PillKind.Red
            PillTone.WATCHED -> PillKind.Watched; PillTone.PLAIN -> PillKind.Neutral
        }, glyphOf(it.glyph))
    }

    private fun glyphOf(g: PillGlyph): Glyph? = when (g) {
        PillGlyph.CHECK -> Glyph.Check; PillGlyph.SPEAKER -> Glyph.Speaker; PillGlyph.CLOCK -> Glyph.Info
        PillGlyph.SUBTITLES -> Glyph.Sources; PillGlyph.CAM -> Glyph.Film; PillGlyph.NONE -> null
    }

    private fun date(s: String?): LocalDate? = s?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    /**
     * Pages built in the last [INSTANT_MS] (a hover readied it, or the viewer was just there), for [instant]. Bounded:
     * [MAX_BUILT] pages of a few KB each.
     */
    private class Built(val at: Long, val details: TitleDetails)
    private val built = object : LinkedHashMap<String, Built>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Built>?) = size > MAX_BUILT
    }
    private fun refKey(ref: TitleRef) = (if (ref.isShow) "tv:" else "movie:") + ref.tmdbId

    override fun instant(ref: TitleRef): TitleDetails? = synchronized(built) { built[refKey(ref)] }
        ?.takeIf { System.currentTimeMillis() - it.at < INSTANT_MS }?.details

    /** Scores run on their own (OMDb, Rotten Tomatoes' site): the page waits for them at most [SCORES_WAIT_MS]. */
    private val side = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    override suspend fun details(ref: TitleRef): TitleDetails = build(ref).also { d ->
        synchronized(built) { built[refKey(ref)] = Built(System.currentTimeMillis(), d) }
    }

    private suspend fun build(ref: TitleRef): TitleDetails {
        val d = titles.details(kindOf(ref), ref.tmdbId)
        known[d.title.key] = d
        val t = d.title
        val info = d.info ?: TitleInfo()
        // A first look at a title asks OMDb (and maybe Rotten Tomatoes' site): seconds at worst. The page doesn't wait
        // past SCORES_WAIT_MS for them; the kept scores (none yet) show, and the answer is kept for the next visit.
        val asking = side.async { runCatching { titles.scores(d) }.getOrNull() }
        val scores = kotlinx.coroutines.withTimeoutOrNull(SCORES_WAIT_MS) { asking.await() }
            ?: runCatching { com.dreamos.desktop.core.content.Ratings.cached(d) }.getOrNull()
        val entry = runCatching { history.entry(t.key) }.getOrNull()
        val watched = runCatching { history.isWatched(t) }.getOrDefault(false)
        val next = if (ref.isShow) runCatching { history.nextFor(t) }.getOrNull() else null
        val label = runCatching { history.playLabel(t) }.getOrNull()
        // A show's page pill is its Play episode's (WatchHistory.playEntry, else S1 E1; a show taken off the row starts fresh).
        val pageTarget = if (!ref.isShow) target(d) else target(d, next?.season ?: 1, next?.episode ?: 1)
        availability?.ask(listOf(pageTarget), priority = true)
        val langs = availability?.languagePills(pageTarget)
        val today = LocalDate.now()
        // Out: its day has come, or its first episode is already up early (a source decides, not the date).
        val seasonsOut = d.seasons.filter { it.number > 0 && it.episodeCount > 0 &&
            ((date(it.airDate)?.let { a -> !a.isAfter(today) } ?: false) || availability?.outEarly("tv:${t.id}:${it.number}:1") == true) }
        val announced = d.seasons.filter { it.number > 0 && it !in seasonsOut && it.number > (seasonsOut.maxOfOrNull { s -> s.number } ?: 0) }.take(2)
        val seasons = seasonsOut.map { SeasonInfo(it.number, seasonName(it.name, it.number), it.episodeCount) } +
            announced.map { s ->
                val a = date(s.airDate)
                SeasonInfo(s.number, seasonName(s.name, s.number), s.episodeCount, comingWords = a?.let { comingShort(it, today) } ?: "Upcoming",
                    comingLong = a?.let { "Coming " + TitleWords.long(it) } ?: TitleWords.TBA)
            }
        val release = if (ref.isShow) date(info.premiere) ?: date(t.date)
        else listOfNotNull(date(info.theatrical), date(info.digital)).minOrNull() ?: date(t.date)
        return TitleDetails(
            ref = ref.copy(name = t.name), name = t.name,
            logoUrl = d.logo(), backdropUrl = t.backdrop("w1280"), posterUrl = t.poster(),
            releaseDate = release, year = t.year.toIntOrNull(),
            certification = scores?.certification?.ifBlank { null } ?: info.cert.ifBlank { null },
            runtimeMin = d.runtime.takeIf { it > 0 && !ref.isShow },
            seasonCount = if (ref.isShow) info.seasons.takeIf { it > 0 } ?: seasonsOut.size.takeIf { it > 0 } else null,
            genres = d.genres.take(2),
            scores = Scores(tomato = scores?.tomatometer, badge = scores?.audienceBadge),
            statusPill = if (watched) PillSpec("Watched", PillKind.Watched) else pill(availability?.pagePill(pageTarget)),
            statusNote = if (watched && !ref.isShow) null else availability?.peek(pageTarget.key)?.note,
            languagePills = listOfNotNull(langs?.audio, langs?.subtitles).map { lp ->
                PillSpec(lp.text, when (lp.tone) { PillTone.GOOD -> PillKind.Green; PillTone.WARN -> PillKind.Amber; else -> PillKind.Neutral }, glyphOf(lp.glyph))
            },
            tagline = info.tagline.ifBlank { null },
            overview = t.overview,
            trailerKey = d.trailerKey,
            seasons = seasons,
            defaultSeason = next?.season?.takeIf { s -> seasonsOut.any { it.number == s } } ?: seasonsOut.firstOrNull()?.number,
            cast = d.cast.take(15).map { CastMember(it.id, it.name, it.character, it.profile()) },
            similar = d.recommendations.take(24).map { r -> cardOf(r).copy(pill = cardTarget(r)?.let { ct -> pill(availability?.cardPill(ct)) }) },
            info = infoCells(info, d, scores?.tomatometer, scores?.audienceBadge, scores?.certification?.ifBlank { null }),
            // Free leaves out what's already under Streaming (TitleInfo.watchWords, iPhone Where to Watch); eight a group at most.
            whereToWatch = WhereToWatch(info.streaming.take(8), info.free.filter { it !in info.streaming }.take(8), info.rentBuy.take(8)),
            progress = entry?.takeIf { !it.hidden && it.progress > 0 && !it.finished }?.let {
                TitleProgress(it.season.takeIf { s -> s > 0 }, it.episode.takeIf { e -> e > 0 }, it.episodeName, it.progress.toFloat(), ((it.durationMs - it.positionMs) / 60_000).toInt())
            },
            upNext = next?.let { r -> val s = r.season; val e = r.episode; if (s != null && e != null) s to e else null },
            onMyList = Services.myList.contains(t.key),
            playLabel = label,
        )
    }

    private companion object {
        const val INSTANT_MS = 5 * 60_000L
        const val MAX_BUILT = 48
        const val SCORES_WAIT_MS = 700L
        const val SEASON_FRESH_MS = 10 * 60_000L
        const val MAX_SEASONS = 24
        const val MAX_TARGETS = 400
    }

    private fun seasonName(name: String, n: Int) = name.takeIf { it.isNotBlank() && !it.startsWith("Season") && it.length <= 22 } ?: "Season $n"

    /** A season chip's words (Upcoming.chipWords): "Coming Mar 4" within half a year, else "Coming 2027". */
    private fun comingShort(d: LocalDate, today: LocalDate) = if (!d.isAfter(today.plusDays(183))) "Coming " + TitleWords.short(d, d) else "Coming ${d.year}"

    private fun infoCells(i: TitleInfo, d: CoreDetails, tomato: Int?, audience: String?, cert: String? = null): List<InfoCell> {
        val out = mutableListOf<InfoCell>()
        fun long(s: String) = date(s)?.let { TitleWords.long(it) }
        fun plural(one: String, many: String, l: List<String>, n: Int) { l.take(n).takeIf { it.isNotEmpty() }?.let { out += InfoCell(if (it.size == 1) one else many, it.joinToString(", ")) } }
        val today = LocalDate.now()
        if (!i.isShow) {
            val th = long(i.theatrical); val dg = long(i.digital); val ahead = listOfNotNull(date(i.theatrical), date(i.digital)).any { it.isAfter(today) }
            val released = when { th != null && dg != null -> "In Theaters $th${TitleWords.DOT}Digital $dg"; else -> th ?: dg ?: long(d.title.date) }
            released?.let { out += InfoCell(if (ahead) "Release Date" else "Released", it, wide = true) }
            if (d.runtime > 0) out += InfoCell("Run Time", d.runtimeWords.ifBlank { TitleWords.runtime(d.runtime) })
        } else {
            long(i.premiere)?.let { out += InfoCell("First Aired", if (date(i.premiere)!!.isAfter(today)) "Coming $it" else it) }
            long(i.lastAired)?.takeIf { i.lastAired != i.premiere }?.let { out += InfoCell("Last Aired", it) }
            i.status.ifBlank { null }?.let { out += InfoCell("Status", it) }
            if (i.seasons > 0) out += InfoCell("Seasons", TitleWords.seasons(i.seasons) + if (i.episodes > 0) ", ${i.episodes} Episode${if (i.episodes == 1) "" else "s"}" else "")
            if (i.nextSeason > 0 && i.nextEpisode > 0) {
                // TitleInfo.nextWords: the exact time when TVmaze's is known ("Friday, October 30 at 9:00 PM"), else the day.
                val at = com.dreamos.desktop.core.content.AirTimes.peek(d.title.id)?.next?.takeIf { it.season == i.nextSeason && it.number == i.nextEpisode }?.at
                val whenWords = at?.let { com.dreamos.desktop.core.time.DreamTime.format(it, "EEEE, MMMM d") + " at " + com.dreamos.desktop.core.time.DreamTime.formatTime(it) }
                    ?: long(i.nextDate) ?: TitleWords.TBA
                out += InfoCell("Next Episode", "Season ${i.nextSeason}, Episode ${i.nextEpisode}${TitleWords.DOT}$whenWords", wide = true)
            }
            if (i.runtime > 0) out += InfoCell("Episode Length", (if (i.runtimeApprox) "About " else "") + TitleWords.runtime(i.runtime))
        }
        (i.cert.ifBlank { null } ?: cert)?.let { out += InfoCell("Rated", it + if (i.certNote.isNotBlank()) TitleWords.DOT + i.certNote else "") }
        i.genres.take(4).takeIf { it.isNotEmpty() }?.let { out += InfoCell("Genres", it.joinToString(", ")) }
        if (!i.isShow) {
            plural("Director", "Directors", i.directors, 3)
            plural("Writer", "Writers", i.writers, 3)
            i.basedOn.ifBlank { null }?.let { out += InfoCell("Based On", it) }
            i.producers.take(3).takeIf { it.isNotEmpty() }?.let { out += InfoCell("Producers", it.joinToString(", ")) }
        } else {
            i.creators.take(3).takeIf { it.isNotEmpty() }?.let { out += InfoCell("Created By", it.joinToString(", ")) }
            plural("Network", "Networks", i.networks, 3)
        }
        plural("Studio", "Studios", i.studios, 2)
        plural("Country", "Countries", i.countries, 3)
        i.language.ifBlank { null }?.let { out += InfoCell("Original Language", it) }
        i.originalTitle.ifBlank { null }?.takeIf { !it.equals(d.title.name, ignoreCase = true) }?.let { out += InfoCell("Original Title", it) }
        if (!i.isShow) {
            if (i.budget > 0) out += InfoCell("Budget", TitleWords.money(i.budget))
            if (i.revenue > 0) out += InfoCell("Box Office", TitleWords.money(i.revenue))
            i.collection.ifBlank { null }?.let { out += InfoCell("Collection", if (it.endsWith("Collection")) "Part of the $it" else "Part of $it") }
            i.status.ifBlank { null }?.takeIf { it != "Released" }?.let { out += InfoCell("Status", it) }
        }
        // TitleInfo.ratingsWords: Rotten Tomatoes, IMDb and TMDB (after 150 votes), each that's known.
        val tmdb = if (i.score > 0 && i.votes >= 150) "TMDB " + String.format(Locale.US, "%.1f", i.score) else null
        val ratings = listOfNotNull(tomato?.let { "Rotten Tomatoes $it%" }, audience?.takeIf { it.startsWith("IMDb") }, tmdb ?: audience?.takeIf { it.startsWith("TMDB") })
        if (ratings.isNotEmpty()) out += InfoCell("Ratings", ratings.joinToString(TitleWords.DOT), wide = true)
        return out
    }

    override suspend fun season(ref: TitleRef, season: Int): List<EpisodeInfo> {
        // The first episode still ahead gets one quick look in the index first: one already up plays now.
        val eps = titles.episodesChecked(ref.tmdbId, season)
        val d = known["tv:${ref.tmdbId}"]
        val entry = runCatching { history.entry("tv:${ref.tmdbId}") }.getOrNull()
        val today = LocalDate.now()
        val out = eps.map { e -> episode(e, d, entry, today) }
        // Recent and early episodes' pills: checked now, ahead of the cards' queue (this season is on screen or next).
        val due = out.mapNotNull { e -> episodeTargets[epKey(ref.tmdbId, e.season, e.number)] }
        if (due.isNotEmpty()) availability?.ask(due, priority = true)
        synchronized(seasons) { seasons[seasonKey(ref, season)] = Kept(System.currentTimeMillis(), out) }
        return out
    }

    // ---- preloading ----

    private class Kept<T>(val at: Long, val value: T)
    private fun seasonKey(ref: TitleRef, season: Int) = "${ref.tmdbId}:$season"
    private fun epKey(show: Int, season: Int, episode: Int) = "tv:$show:$season:$episode"

    /** Seasons built in the last [SEASON_FRESH_MS]: at most [MAX_SEASONS] lists of a few dozen episodes (~10 KB each). */
    private val seasons = object : LinkedHashMap<String, Kept<List<EpisodeInfo>>>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Kept<List<EpisodeInfo>>>?) = size > MAX_SEASONS
    }
    /** Pill targets of episodes due one (recent or out early), by episode: at most [MAX_TARGETS]. */
    private val episodeTargets: MutableMap<String, PlayTarget> = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, PlayTarget>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, PlayTarget>?) = size > MAX_TARGETS
    })
    /** More Like This movies' pill targets, by TMDB id: at most [MAX_TARGETS]. */
    private val similarTargets: MutableMap<Int, PlayTarget> = java.util.Collections.synchronizedMap(object : LinkedHashMap<Int, PlayTarget>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, PlayTarget>?) = size > MAX_TARGETS
    })

    override fun seasonNow(ref: TitleRef, season: Int): List<EpisodeInfo>? = synchronized(seasons) { seasons[seasonKey(ref, season)] }
        ?.takeIf { System.currentTimeMillis() - it.at < SEASON_FRESH_MS }?.value

    override val pillUpdates: kotlinx.coroutines.flow.Flow<String>
        get() = availability?.updates?.let { u -> kotlinx.coroutines.flow.flow { u.collect { emit(it.first) } } } ?: kotlinx.coroutines.flow.emptyFlow()

    /** The page's pill target, as [details] picks it (a show's is its Play episode's). */
    private suspend fun pageTarget(ref: TitleRef, d: CoreDetails): PlayTarget {
        if (!ref.isShow) return target(d)
        val entry = runCatching { history.entry(d.title.key) }.getOrNull()
        val next = runCatching { history.nextFor(d.title) }.getOrNull()
        return target(d, entry?.season?.takeIf { it > 0 } ?: next?.season ?: 1, entry?.episode?.takeIf { it > 0 } ?: next?.episode ?: 1)
    }

    override suspend fun livePills(d: TitleDetails): TitleDetails {
        val a = availability ?: return d
        val core = known[refKey(d.ref)] ?: return d
        val pt = pageTarget(d.ref, core)
        val watched = d.statusPill?.kind == PillKind.Watched
        val langs = a.languagePills(pt)
        return d.copy(
            statusPill = if (watched) d.statusPill else pill(a.pagePill(pt)) ?: d.statusPill,
            statusNote = if (watched && !d.ref.isShow) null else a.peek(pt.key)?.note ?: d.statusNote,
            languagePills = listOfNotNull(langs.audio, langs.subtitles).map { lp ->
                PillSpec(lp.text, when (lp.tone) { PillTone.GOOD -> PillKind.Green; PillTone.WARN -> PillKind.Amber; else -> PillKind.Neutral }, glyphOf(lp.glyph))
            }.ifEmpty { d.languagePills },
            similar = d.similar.map { c -> similarTargets[c.ref.tmdbId]?.let { t -> pill(a.cardPill(t)) }?.let { c.copy(pill = it) } ?: c },
        )
    }

    override fun liveEpisodePills(ref: TitleRef, eps: List<EpisodeInfo>): List<EpisodeInfo> {
        val a = availability ?: return eps
        return eps.map { e -> episodeTargets[epKey(ref.tmdbId, e.season, e.number)]?.let { t -> pill(a.cardPill(t)) }?.let { e.copy(pill = it) } ?: e }
    }

    /**
     * Any title page can get one: the page asks for its own answer even for older titles (Dream Core then shows
     * "Available Now" with the quality). Only Watched, or no Dream Core, means none is coming.
     */
    override fun pillExpected(d: TitleDetails): Boolean = availability != null && d.statusPill?.kind != PillKind.Watched

    override fun lookAhead(d: TitleDetails) {
        val core = known[refKey(d.ref)] ?: return
        val targets = core.recommendations.take(24).mapNotNull { r -> cardTarget(r)?.also { similarTargets[r.id] = it } }
        if (targets.isNotEmpty()) availability?.ask(targets)
    }


    private suspend fun episode(e: Episode, d: CoreDetails?, entry: com.dreamos.desktop.core.model.HistoryEntry?, today: LocalDate): EpisodeInfo {
        val air = date(e.airDate)
        val upcoming = e.upcoming && !e.outEarly
        val inProgress = entry != null && entry.season == e.season && entry.episode == e.number && !entry.finished && entry.progress > 0
        val recent = e.outEarly || (air != null && !air.isAfter(today) && air.isAfter(today.minusDays(21)))
        val target = d?.let { target(it, e.season, e.number, e.name, e.airDate) }
        if (recent && target != null) episodeTargets[epKey(e.showId, e.season, e.number)] = target
        return EpisodeInfo(
            season = e.season, number = e.number, name = e.name, runtimeMin = e.runtime.takeIf { it > 0 }, airDate = air,
            overview = e.overview, stillUrl = e.still("w500"),
            fallbackArt = listOfNotNull(d?.title?.backdrop("w780"), d?.title?.poster()),
            progress = if (inProgress) entry!!.progress.toFloat() else 0f,
            watched = runCatching { history.isEpisodeWatched(e.showId, e.season, e.number) }.getOrDefault(false),
            pill = if (recent && target != null) pill(availability?.cardPill(target)) else null,
            upcoming = upcoming,
            // Upcoming.coming: "Coming Friday at 9:00 PM" (TVmaze's exact time), "Coming Tomorrow", "Coming Nov 14", else TBA.
            comingWords = if (!upcoming) null else when {
                e.airsAt != null -> "Coming " + com.dreamos.desktop.core.time.DreamTime.airLabel(e.airsAt!!)
                air != null -> "Coming " + com.dreamos.desktop.core.time.DreamTime.dayWords(e.airDate.take(10))
                else -> TitleWords.TBA
            },
            airsAt = if (upcoming) e.airsAt else null,
        )
    }

    override suspend fun seasonUpEarly(ref: TitleRef, season: Int): Boolean =
        runCatching { titles.episodesChecked(ref.tmdbId, season).any { !it.upcoming } }.getOrDefault(false)

    override suspend fun person(id: Int, excluding: TitleRef?): PersonDetails {
        val p = titles.person(id, excluding?.let { refKey(it) }) ?: throw com.dreamos.desktop.core.model.DreamError("Couldn't load this person right now.")
        // Known For: their best-known titles (not the one they were opened from); Movies & Shows: every credit (iPhone PersonView).
        val known = p.knownFor.map { cardOf(it) }
        val all = p.credits.map { cardOf(it.title) }.ifEmpty { known }
        return PersonDetails(p.id, p.name, p.profile("w185"), null, null, null, p.bio, known, all, metaLine = p.meta.ifBlank { null })
    }

    private fun cardOf(t: Title) = TitleCard(TitleRef(t.id, t.isShow, t.name), t.name, t.poster(), t.year.toIntOrNull(), overview = t.overview,
        audience = com.dreamos.desktop.core.content.Ratings.audience(null, t.rating, t.votes))

    override fun onMyList(ref: TitleRef): Boolean = Services.myList.contains(refKey(ref))

    /** The last Choose a Source list's sources by id, with their target, so a pick can be prepared (one list at a time). */
    @Volatile private var listed: Map<String, Pair<PlayTarget, com.dreamos.desktop.core.playback.Source>> = emptyMap()

    override suspend fun sources(ref: TitleRef, season: Int?, episode: Int?, loose: Boolean): List<SourceOption> {
        val pb = playback ?: return emptyList()
        val d = known[(if (ref.isShow) "tv:" else "movie:") + ref.tmdbId] ?: titles.details(kindOf(ref), ref.tmdbId)
        val t = target(d, season, episode)
        val rows = pb.sources(t, loose)
        listed = rows.associate { it.source.hash to (t to it.source) }
        val ready = runCatching { pb.readyHashes(rows) }.getOrNull()
        return rows.map { r ->
            val s = r.source
            val isReady = r.ready ?: ready?.contains(s.hash)
            SourceOption(
                id = s.hash,
                headline = listOfNotNull(s.quality.label, size(s.sizeBytes)).joinToString(TitleWords.DOT),
                badges = (r.badges + listOfNotNull(if (s.seeders == 1) "1 Seeder" else if (s.seeders > 1) "${s.seeders} Seeders" else null)).joinToString(TitleWords.DOT),
                releaseName = prettyName(s.fileName?.let { com.dreamos.desktop.core.playback.DebridFiles.base(it) } ?: s.torrentName),
                // The Fire Stick's source sheet: one status pill (Outside Your Filters on Search All Sources, else Other
                // Episode), then Ready under it.
                pills = listOfNotNull(
                    when {
                        loose && r.outsideFilters -> PillSpec("Outside Your Filters", PillKind.Neutral)
                        r.otherEpisode -> PillSpec("Other Episode", PillKind.Amber)
                        else -> null
                    },
                    if (isReady == true) PillSpec("Ready", PillKind.Green) else null,
                ),
                otherEpisode = r.otherEpisode,
            )
        }
    }

    override suspend fun prepare(ref: TitleRef, season: Int?, episode: Int?, sourceId: String): com.dreamos.desktop.core.playback.PlayRequest? {
        val pb = playback ?: return null
        val (t, s) = listed[sourceId] ?: return null
        // startMs 0: Dream Player starts where the watch history says (win-player round 2), as Play does.
        return pb.playSource(t, s, 0)
    }

    /**
     * A release name as people read it (SourcePicker.prettyName): a video file's extension dropped (a torrent name has
     * none, its last dotted tag stays), dots and underscores between words as spaces, kept inside numbers (5.1, H.264).
     */
    private fun prettyName(name: String): String {
        val base = if (com.dreamos.desktop.core.playback.DebridFiles.isVideo(name)) name.substringBeforeLast('.') else name
        return base.replace(Regex("(?<![0-9])[._]|[._](?![0-9])"), " ").replace(Regex("\\s+"), " ").trim()
    }

    /** SourcePicker.sizeLabel: "850 MB" under a gigabyte, else "12.3 GB" (binary units, as the Fire Stick counts). */
    private fun size(b: Long): String? {
        val gb = b / (1024.0 * 1024 * 1024)
        return when {
            b <= 0 -> null
            gb < 1.0 -> "${(gb * 1024).toInt()} MB"
            else -> String.format(Locale.US, "%.1f GB", gb)
        }
    }

    override suspend fun setMyList(ref: TitleRef, on: Boolean): Boolean {
        val key = (if (ref.isShow) "tv:" else "movie:") + ref.tmdbId
        if (Services.myList.contains(key) == on) return on
        val t = known[key]?.title ?: Title(ref.tmdbId, kindOf(ref), ref.name)
        return Services.myList.toggle(t)
    }
}
