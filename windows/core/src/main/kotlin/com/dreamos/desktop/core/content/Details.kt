package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.*
import com.dreamos.desktop.core.net.HttpError
import com.dreamos.desktop.core.net.Lane
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.time.DreamTime
import com.dreamos.desktop.core.util.*
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A title page's data (fire-home Tmdb.details / fetchDetails / cachedDetails, the iPhone's Details.fetch): ONE TMDB
 * request carries videos, watch/providers, credits, recommendations, release_dates (movies) or content_ratings (shows),
 * external_ids and images (the English logo and key art). Also a season's episodes with the Upcoming split (fire-home
 * Upcoming.kt, iPhone Details.episodes), a show's air state, logos, key art (fire-home KeyArt.kt), person pages and
 * X-Ray's cast (fire-home PlayerXRay.kt XRayData). Blocking: IO threads only.
 *
 * Caches (all bounded, worst cases):
 *  - details: [MAX_DETAILS] titles, 3 h, kept unfiltered for every profile (~20-40 KB each: about 10 MB).
 *  - logos: [MAX_LOGOS] paths (~100 bytes each: ~50 KB).
 *  - card art (key art and plain art): [MAX_KEY_ART] titles in memory and in the `keyart` settings file, 14 days
 *    (~100 bytes each: ~400 KB).
 *  - people: [MAX_PEOPLE] persons with up to 40 known titles, every credit (up to [MAX_CREDITS]) and a trimmed
 *    biography (~150 KB each at most: ~19 MB).
 *  - X-Ray casts: [MAX_CASTS] lists of up to 24 people (~3 KB each: ~200 KB).
 * Season episode lists ride on [WatchHistory.tmdb]'s cache (256 answers). Every answer is also on disk (TmdbDisk).
 */
object Details {
    private const val DETAILS_TTL = 3 * 3_600_000L
    private const val MAX_DETAILS = 256
    private const val MAX_LOGOS = 512
    private const val MAX_KEY_ART = 4000
    /** Key art asks' places: every `soon` ask (a row about to show) before every other, each kind in asking order. */
    private const val SOON = 0L
    private const val ASKED = 1L shl 40
    private const val KEY_ART_AGE = 14L * 86_400_000L
    private const val MAX_PEOPLE = 128
    private const val MAX_CASTS = 64

    /** X-Ray: people shown at most. */
    const val MAX_FACES = 24
    /** A person's best-known titles shown at most (40 are kept so a Kids limit still leaves enough). */
    const val MAX_TITLES = 14
    private const val KEEP_TITLES = 40
    /** A person's credits kept at most (a prolific actor's whole career fits). */
    const val MAX_CREDITS = 400
    /** The person page's biography, cut at a full sentence near this many characters. */
    const val BIO_MAX = 900

    private val SKIP_GENRES = setOf(10767, 10763) // talk, news
    private val SELF_ROLE = Regex("(?i)\\b(self|himself|herself|narrator|host)\\b")
    private val PLACEHOLDER = Regex("^(episode|ep\\.?)\\s*#?\\d+$|^(tba|tbd|untitled)$", RegexOption.IGNORE_CASE)
    private val SENTENCE_END = Regex("[.!?][\"')\\]”’]?\\s")

    private class Lru<K, V>(private val max: Int) : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
    }

    // ---- details ----

    /** A details answer read into the model, before any profile filter. [anyCert]: the US rating, else any country's. */
    class Parsed(val details: TitleDetails, val usCert: String, val anyCert: String, val keyArt: String?, val plainArt: String? = null)

    private class Entry(val at: Long, val details: TitleDetails, val anyCert: String)

    private val cache = Lru<String, Entry>(MAX_DETAILS)
    private val inFlight = HashMap<String, FutureTask<Entry>>()

    private fun key(kind: MediaKind, id: Int) = "${kind.path}:$id"

    /** Cached details without the network (a page's first frame), More Like This within the profile's limit; null when not known. */
    fun cachedDetails(kind: MediaKind, id: Int): TitleDetails? {
        val e = synchronized(cache) { cache[key(kind, id)] } ?: return null
        return forProfile(e.details, fetching = false)
    }

    /**
     * The title page's details (cached 3 h). Notes the opening for Taste. Throws [DreamError] with a plain sentence
     * when there's no TMDB key, TMDB can't be reached, or the title is gone.
     */
    fun details(kind: MediaKind, id: Int): TitleDetails {
        val e = entry(kind, id)
        try { Taste.noteOpened(e.details.title, e.details.title.genreIds) } catch (x: Exception) {}
        return forProfile(e.details, fetching = true)
    }

    /** The age rating for the hero and Scores: the US one, else any country's ("" when none); cached only. */
    fun certification(kind: MediaKind, id: Int): String? = synchronized(cache) { cache[key(kind, id)] }?.anyCert

    private fun entry(kind: MediaKind, id: Int): Entry {
        val k = key(kind, id)
        val task: FutureTask<Entry>
        val owner: Boolean
        synchronized(cache) {
            cache[k]?.takeIf { System.currentTimeMillis() - it.at < DETAILS_TTL }?.let { return it }
            val running = inFlight[k]
            owner = running == null
            task = running ?: FutureTask { fetch(kind, id) }.also { inFlight[k] = it }
        }
        if (owner) try { task.run() } finally { synchronized(cache) { inFlight.remove(k) } }
        try {
            return task.get()
        } catch (e: ExecutionException) {
            throw (e.cause as? Exception) ?: e
        }
    }

    private fun fetch(kind: MediaKind, id: Int): Entry {
        if (!Tmdb.hasKey()) throw DreamError("Add your TMDB key in Settings to see titles.")
        val part = if (kind == MediaKind.MOVIE) "release_dates" else "content_ratings"
        val json = try {
            Net.getJson(Tmdb.url("/${kind.path}/$id",
                "&append_to_response=videos,watch/providers,credits,recommendations,external_ids,images,$part&include_image_language=en,null"))
        } catch (e: HttpError) {
            if (e.code == 404) throw DreamError("This title isn't available right now.", cause = e)
            throw DreamError("Couldn't load this title. Try again in a moment.", cause = e)
        } catch (e: Exception) {
            throw DreamError("Couldn't load this title. Check your connection and try again.", cause = e)
        }
        val p = parse(json, kind) ?: throw DreamError("This title isn't available right now.")
        val t = p.details.title
        try { KidsFilter.remember(t, p.usCert) } catch (e: Exception) {}
        p.keyArt?.let { rememberKeyArt(t.key, it, p.plainArt) }
        synchronized(logos) { logos[key(kind, id)] = p.details.logoPath.orEmpty() }
        val e = Entry(System.currentTimeMillis(), p.details, p.anyCert)
        synchronized(cache) { cache[key(kind, id)] = e }
        return e
    }

    /**
     * More Like This within the profile's rating limit ([fetching]: unknown ratings are looked up, else only cached
     * ones count). Anime stays: More Like This is a personal row ([RowMix.animeRow], STANDARDS §5).
     */
    private fun forProfile(d: TitleDetails, fetching: Boolean): TitleDetails {
        if (!KidsFilter.active() || d.recommendations.isEmpty()) return d
        val recs = if (fetching) KidsFilter.titles(d.recommendations) else d.recommendations.filter { KidsFilter.allowed(it) }
        return d.copy(recommendations = recs)
    }

    /** A details answer as [TitleDetails] (no profile filter, no network); null when it isn't a title. */
    fun parse(json: JsonObject, kind: MediaKind): Parsed? {
        val base = Tmdb.parse(json, kind) ?: return null
        val genreObjs = json.a("genres").objects()
        val t = base.copy(genreIds = genreObjs.map { it.int("id") }.filter { it > 0 }.ifEmpty { base.genreIds })
        val genres = genreObjs.mapNotNull { it.str("name").ifEmpty { null } }
        val movie = kind == MediaKind.MOVIE

        // The trailer plays inside Dream Player; the key is the video's id and its site is never shown or named.
        val videos = json.o("videos").a("results").objects().filter { it.str("site") == "YouTube" && it.str("key").isNotEmpty() }
        val trailer = (videos.firstOrNull { it.str("type") == "Trailer" } ?: videos.firstOrNull())?.str("key")

        val providers = json.o("watch/providers").o("results").o("US").a("flatrate").objects().mapNotNull { p ->
            val pid = p.int("provider_id")
            val name = p.str("provider_name").trim()
            if (pid <= 0 || Tmdb.hiddenProvider(pid, name)) null else Provider(pid, name, p.strOrNull("logo_path"))
        }.distinctBy { it.id }

        val cast = json.o("credits").a("cast").objects().mapNotNull { c ->
            val name = c.str("name").trim()
            if (name.isEmpty()) null else CastMember(c.int("id"), name, c.str("character").trim(), c.strOrNull("profile_path"))
        }.take(15)

        val recs = json.o("recommendations").a("results").objects().mapNotNull { r ->
            Tmdb.parse(r, MediaKind.of(r.str("media_type")) ?: kind)
        }.filter { it.key != t.key && !it.adult }.distinctBy { it.key }.take(20)

        val seasons = json.a("seasons").objects().map { s ->
            SeasonSummary(s.int("season_number"), s.str("name"), s.int("episode_count"),
                s.str("air_date").takeIf { it.length == 10 }.orEmpty(), s.strOrNull("poster_path"))
        }

        val imdb = listOf(json.o("external_ids").str("imdb_id"), json.str("imdb_id")).firstOrNull { it.startsWith("tt") }
        val info = try { TitleInfoParse.parse(json, t, genres) } catch (e: Exception) { null }
        val runtime = (if (movie) json.int("runtime") else TitleInfoParse.median(json.a("episode_run_time").ints()))
            .takeIf { it > 0 } ?: info?.runtime ?: 0
        val runtimeWords = if (movie) Tmdb.runtimeWords(runtime).orEmpty()
        else json.int("number_of_seasons").takeIf { it > 0 }?.let { "$it Season" + if (it > 1) "s" else "" }.orEmpty()

        val byCountry = Tmdb.certificationsByCountry(json, movie)
        val us = byCountry["US"].orEmpty()
        val images = json.o("images")

        val d = TitleDetails(
            title = t, genres = genres, runtime = runtime, runtimeWords = runtimeWords, trailerKey = trailer,
            providers = providers, cast = cast, recommendations = recs, seasons = seasons, imdbId = imdb,
            logoPath = logoFrom(images), info = info,
        )
        return Parsed(d, us, us.ifEmpty { byCountry.values.firstOrNull().orEmpty() }, images?.let { keyArtFrom(it) },
            images?.let { plainArtFrom(it, t.backdropPath) })
    }

    // ---- logos and key art (one /images answer serves both) ----

    private val logos = Lru<String, String>(MAX_LOGOS)

    /** The English logo (else any, never SVG) from an images answer; null when none. */
    fun logoFrom(images: JsonObject?): String? {
        val list = images.a("logos").objects().filter { it.str("file_path").let { p -> p.isNotEmpty() && !p.endsWith(".svg") } }
        return (list.firstOrNull { it.str("iso_639_1") == "en" } ?: list.firstOrNull())?.str("file_path")
    }

    /** The best voted English backdrop (the title's name on it) from an images answer; "" when there is none. */
    fun keyArtFrom(images: JsonObject): String =
        images.a("backdrops").objects().filter { it.str("iso_639_1") == "en" && it.str("file_path").isNotEmpty() }
            .maxWithOrNull(compareBy<JsonObject>({ it.dbl("vote_average") }, { it.int("vote_count") }))?.str("file_path").orEmpty()

    /** [kind] [id]'s English logo art path (transparent PNG), null when none or TMDB couldn't be reached. Cached. */
    fun logo(kind: MediaKind, id: Int): String? {
        val k = key(kind, id)
        synchronized(logos) { logos[k] }?.let { return it.ifEmpty { null } }
        synchronized(cache) { cache[k] }?.let { return it.details.logoPath }
        val images = images(kind, id) ?: return null
        val path = logoFrom(images)
        synchronized(logos) { logos[k] = path.orEmpty() }
        rememberKeyArt(k, keyArtFrom(images), null)
        return path
    }

    private fun images(kind: MediaKind, id: Int): JsonObject? {
        if (!Tmdb.hasKey()) return null
        return try {
            Net.getJson("${Tmdb.API}/${kind.path}/$id/images?api_key=${Tmdb.key()}&include_image_language=en,null")
        } catch (e: Exception) { null }
    }

    // Card art from the /images answer (fire-home KeyArt.kt, owner 2026-10-28), remembered 14 days in memory and in the
    // `keyart` file ("at|key|plain"; "" = none), so a row asks once and later launches build their cards with it at once:
    //  - key art: TMDB's English backdrop with the title's name on it, for 16:9 cards (landscape rows, Top Picks) that
    //    show it whole;
    //  - plain art: a backdrop with no text on it (iso_639_1 null), for frames wider than the art (the Spotlight crops
    //    it, and cropped lettering reads as broken): "" when the title's own backdrop is textless (or TMDB has no
    //    textless one), else the best voted textless backdrop.

    private class CardArt(val key: String, val plain: String?, val at: Long)

    private val keyArt = HashMap<String, CardArt>()
    private val keyArtAsks = HashMap<String, KeyArtAsk>()
    @Volatile private var keyArtLoaded = false
    // A PC asks for a whole row's card art at once, in TmdbGate's background lane: a click or the page on screen goes
    // first. Asks run in [KeyArtAsk.order]: those for a row about to show ([primeKeyArt] `soon`) ahead of the rest.
    private val keyArtPool = ThreadPoolExecutor(12, 12, 30, TimeUnit.SECONDS, java.util.concurrent.PriorityBlockingQueue(), Lane.threads("keyart"))
        .apply { allowCoreThreadTimeOut(true) }
    private val keyArtSeq = java.util.concurrent.atomic.AtomicLong()
    // Asks for the rows on screen: the foreground lane, so the first rows a viewer sees don't wait behind other pages
    // being filled ahead (the background lane's 24 a second go mostly to their lists on a first launch). A few cards
    // a row, a few rows: bounded.
    private val keyArtFront = ThreadPoolExecutor(6, 6, 30, TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue()) { r ->
        Thread(r, "keyart-front").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    /** One title's ask; lower [order] runs first (asked in order, a `soon` ask before every other). */
    private class KeyArtAsk(work: java.util.concurrent.Callable<String?>, @Volatile var order: Long) : FutureTask<String?>(work), Comparable<KeyArtAsk> {
        override fun compareTo(other: KeyArtAsk) = order.compareTo(other.order)
    }

    private fun keyArtFile() = DreamStore.shared.file("keyart")

    private fun loadKeyArt() {
        if (keyArtLoaded) return
        synchronized(keyArt) {
            if (keyArtLoaded) return
            val now = System.currentTimeMillis()
            val f = keyArtFile()
            for (k in f.keys) {
                val parts = (f.getString(k) ?: continue).split('|')
                val at = parts[0].toLongOrNull() ?: continue
                // Entries from before plain art have two parts: plain unknown (the backdrop stands in).
                if (now - at < KEY_ART_AGE && parts.size >= 2) keyArt[k] = CardArt(parts[1], parts.getOrNull(2), at)
            }
            keyArtLoaded = true
        }
    }

    private fun rememberKeyArt(titleKey: String, path: String, plain: String?) {
        loadKeyArt()
        val now = System.currentTimeMillis()
        val (kept, dropped) = synchronized(keyArt) {
            val e = CardArt(path, plain ?: keyArt[titleKey]?.plain, now)
            keyArt[titleKey] = e
            e to if (keyArt.size <= MAX_KEY_ART) emptyList()
            else keyArt.entries.sortedBy { it.value.at }.take(keyArt.size - MAX_KEY_ART).map { it.key }.onEach { keyArt.remove(it) }
        }
        keyArtFile().edit {
            putString(titleKey, "$now|$path" + (kept.plain?.let { "|$it" } ?: ""))
            dropped.forEach { remove(it) }
        }
    }

    /** The textless backdrop for wide frames from an images answer; "" when [backdrop] is textless itself or none is. */
    fun plainArtFrom(images: JsonObject, backdrop: String?): String {
        val plain = images.a("backdrops").objects().filter { it.str("iso_639_1").isEmpty() && it.str("file_path").isNotEmpty() }
        if (plain.isEmpty() || backdrop == null || plain.any { it.str("file_path") == backdrop }) return ""
        return plain.maxWithOrNull(compareBy<JsonObject>({ it.dbl("vote_average") }, { it.int("vote_count") }))?.str("file_path").orEmpty()
    }

    /** [t]'s key art path when already known (memory only, any thread); null when it has none or wasn't asked yet. */
    fun knownKeyArt(t: Title): String? {
        loadKeyArt()
        return synchronized(keyArt) { keyArt[t.key] }?.key?.ifEmpty { null }
    }

    /** [t]'s textless backdrop for wide frames when its own backdrop has text (memory only); null: use the backdrop. */
    fun knownPlainArt(t: Title): String? {
        loadKeyArt()
        return synchronized(keyArt) { keyArt[t.key] }?.plain?.ifEmpty { null }
    }

    /** [t]'s key art path (the English backdrop with its name on it), asking TMDB when unknown; null when none. Blocking. */
    fun keyArtPath(t: Title): String? {
        loadKeyArt()
        synchronized(keyArt) { keyArt[t.key] }?.let { return it.key.ifEmpty { null } }
        return try { askKeyArt(t)?.get(10, TimeUnit.SECONDS) } catch (e: Exception) { null }
    }

    /**
     * Asks for [titles]' card art together and waits at most [waitMs], so a row never waits long; answers that come
     * later are remembered for the next time the row is built (and read by cards drawn after them, [knownKeyArt]).
     * [soon]: a row about to show needs these, so they go ahead of every ask waiting (a first launch queues key art for
     * three pages of rows; the row on screen shouldn't wait behind the others); [front]: on screen, so they are asked
     * in the foreground lane. Blocking.
     */
    fun primeKeyArt(titles: List<Title>, waitMs: Long = 300, soon: Boolean = false, front: Boolean = false) {
        if (titles.isEmpty() || !Tmdb.hasKey()) return
        loadKeyArt()
        val tasks = titles.distinctBy { it.key }.mapNotNull { askKeyArt(it) }
        // Out of the queue and back in with an earlier place; one already running (or done) isn't in the queue.
        if (soon || front) for (task in tasks) if (keyArtPool.queue.remove(task)) {
            if (front) keyArtFront.execute { Lane.foreground { task.run() } }
            else { task.order = SOON + keyArtSeq.incrementAndGet(); keyArtPool.execute(task) }
        }
        val deadline = System.currentTimeMillis() + waitMs
        for (task in tasks) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            try { task.get(left, TimeUnit.MILLISECONDS) } catch (e: Exception) {}
        }
    }

    /** Key art read again from its file on next use, as after a relaunch (tests). */
    internal fun reloadKeyArt() = synchronized(keyArt) { keyArt.clear(); keyArtLoaded = false }

    /** Waits until no key art ask is queued or running (tests: a relaunch finds every answer remembered). */
    internal fun awaitKeyArt(timeoutMs: Long) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end && (keyArtPool.activeCount > 0 || keyArtPool.queue.isNotEmpty())) Thread.sleep(50)
    }

    /** The running (or new) ask for [t]; null when its answer is already known. */
    private fun askKeyArt(t: Title): KeyArtAsk? = synchronized(keyArtAsks) {
        if (synchronized(keyArt) { keyArt.containsKey(t.key) }) return null
        keyArtAsks[t.key]?.let { return it }
        val task = KeyArtAsk(java.util.concurrent.Callable {
            try {
                // A failed call (offline) is not remembered: the next build asks again.
                val images = images(t.kind, t.id) ?: return@Callable null
                val path = keyArtFrom(images)
                rememberKeyArt(t.key, path, plainArtFrom(images, t.backdropPath))
                synchronized(logos) { logos[t.key] = logoFrom(images).orEmpty() }
                path.ifEmpty { null }
            } finally {
                synchronized(keyArtAsks) { keyArtAsks.remove(t.key) }
            }
        }, ASKED + keyArtSeq.incrementAndGet())
        keyArtAsks[t.key] = task
        keyArtPool.execute(task)
        task
    }

    // ---- episodes ----

    /**
     * Season [season] of show [showId], still-to-come episodes marked `upcoming` with TVmaze's exact instant when
     * known. One the index already has before its day ([WatchHistory.outEarly], set by the playback side: "a source
     * decides, not the date") is `outEarly` instead and plays. [askEarly] (the title page): the first episode still
     * ahead is looked up first ([WatchHistory.earlyRelease], one quick bounded check), and the next one too while each
     * turns out to be up. Listed dates never change. Throws [DreamError] when TMDB can't be reached. Blocking.
     */
    fun episodes(showId: Int, season: Int, askEarly: Boolean = false): List<Episode> {
        val raw = WatchHistory.seasonEpisodes(showId, season)
            ?: throw DreamError("Couldn't load this season. Check your connection and try again.")
        val now = DreamTime.now()
        val today = DreamTime.todayYmd(now)
        val open = raw.any { it.str("air_date").let { d -> d.length != 10 || d >= today } }
        val maze = if (open) try { AirTimes.episodes(showId) } catch (e: Exception) { null } else null
        return split(showId, season, raw, maze, now, early = if (askEarly) WatchHistory.earlyRelease else null, outEarly = WatchHistory.outEarly)
    }

    /**
     * The Upcoming split (fire-home Upcoming.split, iPhone Details.episodes), pure: an episode is out when past its
     * exact instant ([maze]), else on or after its air day; one still ahead is `outEarly` when [outEarly] says the
     * index has it, or (while no episode before it is truly upcoming) when [early]'s quick check finds a source; else
     * `upcoming` (its missing day and placeholder name filled from TVmaze).
     */
    fun split(
        showId: Int, season: Int, raw: List<JsonObject>, maze: List<AirTimes.MazeEpisode>?, nowMs: Long,
        early: ((Int, Int, Int, String) -> Boolean)? = null,
        outEarly: ((Int, Int, Int) -> Boolean)? = null,
    ): List<Episode> {
        val today = DreamTime.todayYmd(nowMs)
        var coming = false
        return raw.map { e ->
            val num = e.int("episode_number")
            val d = e.str("air_date").takeIf { it.length == 10 }.orEmpty()
            val ep = Episode(
                showId = showId, season = season, number = num, name = e.str("name").trim(), overview = e.str("overview"),
                stillPath = e.strOrNull("still_path"), airDate = d, runtime = e.int("runtime"),
            )
            if (d.isNotEmpty() && d < today) return@map ep
            val m = maze?.firstOrNull { it.season == season && it.number == num }
            val at = m?.at
            val aired = if (at != null) at <= nowMs else d.isNotEmpty() && d <= today
            if (aired) return@map ep.copy(airsAt = at)
            val day = d.ifEmpty { m?.date.orEmpty() }
            val name = if (isPlaceholder(ep.name) && m != null && !isPlaceholder(m.name)) m.name else ep.name
            val isEarly = try {
                outEarly?.invoke(showId, season, num) == true || !coming && early?.invoke(showId, season, num, day) == true
            } catch (x: Exception) { false }
            if (!isEarly) coming = true
            ep.copy(airDate = day, name = name, airsAt = at, upcoming = !isEarly, outEarly = isEarly)
        }
    }

    /** A stand-in episode name ("Episode 7", "TBA") rather than its real title. */
    fun isPlaceholder(s: String?): Boolean = s.isNullOrBlank() || s == "null" || PLACEHOLDER.matches(s.trim())

    // ---- a show's air state (the Watched pill's "caught up" rule) ----

    /** [showId]'s latest aired episode and next air day (TMDB; shares WatchHistory's 3 h cache). Null when unreachable. */
    fun showAirState(showId: Int): ShowAirState? {
        val d = WatchHistory.details(MediaKind.TV, showId) ?: return null
        try { Watched.noteAir(showId, d) } catch (e: Exception) {}
        return airState(d, DreamTime.todayYmd())
    }

    /**
     * The air state from a show's details: its latest aired episode, the episode counts of the seasons before it
     * (index = season number, 0 for specials), and the day the next episode (else the next announced season) airs.
     */
    fun airState(d: JsonObject, today: String): ShowAirState {
        val last = d.o("last_episode_to_air")
        val ls = last.int("season_number")
        val le = last.int("episode_number")
        val seasons = d.a("seasons").objects()
        var next = d.o("next_episode_to_air").str("air_date").takeIf { it.length == 10 }.orEmpty()
        if (next.isEmpty()) {
            next = seasons.filter { it.int("season_number") > ls && it.str("air_date").let { x -> x.length == 10 && x > today } }
                .minOfOrNull { it.str("air_date") }.orEmpty()
        }
        val counts = (1 until maxOf(ls, 1)).map { n -> seasons.firstOrNull { it.int("season_number", -1) == n }?.int("episode_count") ?: 0 }
        return ShowAirState(ls, le, listOf(0) + counts, next)
    }

    // ---- Kids ----

    /** The current profile may open [t] (its rating limit); fetches the rating when unknown (up to ~8 s). Blocking. */
    fun allowed(t: Title): Boolean = KidsFilter.allowedFetching(t)

    // ---- people (person page, X-Ray) ----

    private val people = Lru<Int, PersonDetails>(MAX_PEOPLE)
    private val casts = Lru<String, List<CastMember>>(MAX_CASTS)

    /**
     * One person: born / died and birthplace, the biography ending on a full sentence, and their best-known titles
     * within the profile's rating limit, without [excludingKey] (the title they're seen in). Null when TMDB couldn't
     * be reached. Blocking.
     */
    fun person(id: Int, excludingKey: String? = null): PersonDetails? {
        if (id <= 0 || !Tmdb.hasKey()) return null
        val p = synchronized(people) { people[id] } ?: run {
            val j = WatchHistory.tmdb("/person/$id", "&append_to_response=combined_credits") ?: return null
            parsePerson(id, j).also { synchronized(people) { people[id] = it } }
        }
        val allowed = KidsFilter.titles(p.credits.map { it.title }).mapTo(HashSet()) { it.key }
        return p.copy(
            knownFor = KidsFilter.titles(p.knownFor.filter { it.key != excludingKey }).take(MAX_TITLES),
            credits = p.credits.filter { it.title.key in allowed },
        )
    }

    /** A person answer (with combined_credits) as [PersonDetails], titles unfiltered (the cache serves every profile). */
    fun parsePerson(id: Int, j: JsonObject): PersonDetails {
        // The title page's long date ("July 31, 1970"), date-only so it never shifts a day.
        val born = DreamTime.longDate(j.str("birthday"))
        val died = DreamTime.longDate(j.str("deathday"))
        val life = when {
            born != null && died != null -> "$born – $died"
            born != null -> "Born $born"
            else -> null
        }
        val place = j.str("place_of_birth").trim().ifEmpty { null }
        val meta = listOfNotNull(life, place).joinToString("  ·  ")
        val all = j.o("combined_credits").a("cast").objects().mapNotNull { o ->
            if (SELF_ROLE.containsMatchIn(o.str("character"))) null else Tmdb.parse(o, null)
        }.filter { t -> t.posterPath != null && !t.adult && t.genreIds.none { it in SKIP_GENRES } }
            .distinctBy { it.key }.sortedByDescending { it.votes }
        // Known titles first; a newcomer's handful of small ones still shows.
        val known = all.filter { it.votes >= 20 }.let { if (it.size >= 4) it else all }.take(KEEP_TITLES)
        return PersonDetails(
            id = id, name = j.str("name").trim(), profilePath = j.strOrNull("profile_path"), meta = meta,
            bio = bioLead(j.str("biography"), BIO_MAX), knownFor = known, department = j.str("known_for_department"),
            credits = credits(j.o("combined_credits")),
        )
    }

    /**
     * Every credit in a combined_credits answer, one per title (roles joined, acting first), newest first; titles
     * without a date (announced) go last. Same leave-outs as Known For: talk and news, playing themselves, no poster,
     * adult.
     */
    fun credits(cc: JsonObject?): List<PersonCredit> {
        class Acc(val title: Title, val dept: String) { val roles = LinkedHashSet<String>() }
        val by = LinkedHashMap<String, Acc>()
        fun add(o: JsonObject, role: String, dept: String) {
            val t = Tmdb.parse(o, null) ?: return
            if (t.posterPath == null || t.adult || t.genreIds.any { it in SKIP_GENRES }) return
            val acc = by.getOrPut(t.key) { Acc(t, dept) }
            if (role.isNotBlank() && acc.roles.size < 3) acc.roles += role.trim()
        }
        for (o in cc.a("cast").objects()) {
            val character = o.str("character")
            if (SELF_ROLE.containsMatchIn(character)) continue
            add(o, character, "Acting")
        }
        for (o in cc.a("crew").objects()) add(o, o.str("job"), o.str("department"))
        return by.values.map { PersonCredit(it.title, it.roles.joinToString(", "), it.dept) }
            .sortedWith(compareBy<PersonCredit> { it.title.date.isEmpty() }.thenByDescending { it.title.date }.thenByDescending { it.title.votes })
            .take(MAX_CREDITS)
    }

    /**
     * The start of a biography ending on a whole sentence, at most about [maxChars] characters (PlayerXRay's fitBio,
     * the iPhone's PersonDetails.bioLead): the longest run of full sentences that fits; when even the first is longer,
     * whole words and "…". A sentence ends at . ! ? (maybe a closing quote or bracket) and a space, never after a lone
     * initial ("J. ").
     */
    fun bioLead(full: String, maxChars: Int = 260): String {
        val text = full.replace(Regex("\\s*\\n+\\s*"), " ").trim()
        if (text.length <= maxChars) return text
        val head = text.take(maxChars)
        val padded = "$head "
        var end = -1
        for (m in SENTENCE_END.findAll(padded)) {
            val at = m.range.first
            if (at >= 2 && padded[at - 2] == ' ' && padded[at - 1].isUpperCase()) continue
            end = m.range.last
        }
        if (end >= 40) return padded.substring(0, end).trim()
        val words = head.split(' ').let { if (it.size > 1) it.dropLast(1) else it }
        return words.joinToString(" ").trimEnd(',', ';', ':', '-', '—', ' ') + "…"
    }

    /**
     * Who's in what [r] plays (X-Ray), top billing first, [MAX_FACES] at most. An episode: its own cast, then its guest
     * stars; when TMDB lists fewer than three, the show's whole cast (each actor's most frequent role). A movie: its
     * cast. A failed lookup isn't remembered (a network hiccup isn't "nobody's in it"). Blocking.
     */
    fun cast(r: PlayRequest): List<CastMember> {
        val t = r.title
        if (t.id <= 0 || !Tmdb.hasKey()) return emptyList()
        val k = "${t.kind.path}:${t.id}:${r.season}:${r.episode}"
        synchronized(casts) { casts[k] }?.let { return it }
        val out = LinkedHashMap<Int, CastMember>()
        var failed = false
        fun add(rows: List<JsonObject>, aggregate: Boolean) {
            for (o in rows.sortedBy { it.int("order", 999) }) {
                if (out.size >= MAX_FACES) return
                val id = o.int("id")
                val name = o.str("name").trim()
                if (id <= 0 || name.isEmpty() || out.containsKey(id)) continue
                val character = if (aggregate) o.a("roles").objects().maxByOrNull { it.int("episode_count") }?.str("character").orEmpty()
                else o.str("character")
                out[id] = CastMember(id, name, character.trim(), o.strOrNull("profile_path"))
            }
        }
        if (t.isShow) {
            val s = r.season
            val e = r.episode
            if (s != null && e != null) {
                val c = WatchHistory.tmdb("/tv/${t.id}/season/$s/episode/$e/credits")
                if (c == null) failed = true
                add(c.a("cast").objects(), aggregate = false)
                add(c.a("guest_stars").objects(), aggregate = false)
            }
            if (out.size < 3) {
                val c = WatchHistory.tmdb("/tv/${t.id}/aggregate_credits")
                if (c == null) failed = true
                add(c.a("cast").objects(), aggregate = true)
            }
        } else {
            val c = WatchHistory.tmdb("/movie/${t.id}/credits")
            if (c == null) failed = true
            add(c.a("cast").objects(), aggregate = false)
        }
        val list = out.values.toList()
        if (!failed || list.size >= 3) synchronized(casts) { casts[k] = list }
        return list
    }

    /** Forgets every cached answer (a new TMDB key). Key art stays: it doesn't depend on the key. */
    fun clearCache() {
        synchronized(cache) { cache.clear() }
        synchronized(logos) { logos.clear() }
        synchronized(people) { people.clear() }
        synchronized(casts) { casts.clear() }
    }
}
