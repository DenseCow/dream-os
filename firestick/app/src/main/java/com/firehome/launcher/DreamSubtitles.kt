package com.firehome.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Dream Subtitles: subtitles from the web for streams Dream OS found itself ([NativeResolver]), which
 * arrive with only what's inside the file.
 *
 * Source: the free OpenSubtitles index Stremio runs (no key): GET
 * `opensubtitles-v3.strem.io/subtitles/{movie|series}/{imdb}[:s:e].json` → `subtitles[] {id, url, lang
 * (ISO 639-2: "eng", "pob" for Brazilian Portuguese), subtitleFileName, releaseGroup?, season, episode}`.
 * Each url is a plain UTF-8 SRT (Stremio re-encodes it: no redirect, no gzip).
 *
 * Never a stutter, never a slower start:
 *  - The files are downloaded to the cache folder while the stream is still being resolved ([prefetch],
 *    called by the resolver the moment it knows the IMDb id, in parallel with the index and the debrid
 *    service). ExoPlayer gets local files: a side-loaded subtitle is prepared together with the video
 *    (a slow or dead subtitle URL would hold up the start, or fail it), a local file is instant.
 *  - Subtitles are part of ExoPlayer's media item; adding one later re-prepares the stream (a visible
 *    rebuffer). So they go in at load ([Session.begin]). Only when they really come late, and the
 *    user wants subtitles shown, are they added where the re-prepare can't be seen: a pause of 1.5 s
 *    or more, or a buffer that is empty anyway. Subtitles off: a late list is simply not added.
 *
 * Which ones: in the user's languages (the player's subtitle choice, then the Subtitles settings' first
 * and second language), the best [PER_LANG] per language: whole files before CD1/CD2 splits, SDH and
 * forced as the settings prefer, the release name closest to the playing file (group, source,
 * resolution: the same cut and frame rate), then the index's own order (most downloaded first).
 * "Use subtitles in the video first" leaves the downloads unselected; the video's own track in the
 * language is chosen when there is one.
 */
object DreamSubtitles {
    private const val TAG = "FireHome"
    private const val API = "https://opensubtitles-v3.strem.io/subtitles"
    private const val TTL = 60 * 60_000L
    private const val PER_LANG = 2
    /** Downloaded per language ahead (before the playing file's name is known), the best [PER_LANG] chosen from them. */
    private const val AHEAD = 4
    private const val MAX_BYTES = 2_000_000

    /** One subtitle the index offers; [lang] normalized ([PlayerPrefs.normLang]; "pob" stays "pob"); [path] once downloaded. */
    private data class Entry(
        val url: String, val lang: String, val file: String, val group: String,
        val hi: Boolean, val forced: Boolean, val split: Boolean, val path: String? = null,
    )

    private class Stamped(val list: List<Entry>, val langs: List<String>, val at: Long = SystemClock.elapsedRealtime()) {
        val fresh get() = SystemClock.elapsedRealtime() - at < TTL
    }

    private val cache = ConcurrentHashMap<String, Stamped>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    // ---- the user's choices ----

    /** [show]: subtitles should be on (the Subtitles setting, or a language picked in the player). */
    private class Prefs(val langs: List<String>, val show: Boolean, val embeddedFirst: Boolean, val hi: Boolean, val forced: Boolean)

    /** The settings as last read, so [Session.begin] on the main thread never touches storage. */
    @Volatile private var lastPrefs: Prefs? = null

    /** Settings › Subtitles, as Dream OS keeps them ([DreamFilters]; the first call may migrate them once). Blocking. */
    private fun settings(ctx: Context): Map<String, String> = try {
        DreamFilters.current(ctx)
        DreamFilters.stored(ctx)
    } catch (e: Exception) { emptyMap() }

    /**
     * Languages in order, as normalized codes: the player's subtitle choice, then the settings' first
     * and second language. Subtitles off everywhere: just the first one (listed in the player's menu,
     * not shown, so turning them on mid-film needs no download). Blocking (the first time).
     */
    private fun prefs(ctx: Context): Prefs {
        val s = settings(ctx)
        val player = PlayerPrefs.subLang(ctx)
        val fromPlayer = player?.takeIf { it != PlayerPrefs.OFF }
        val named = listOf(s["subs.lang1"], s["subs.lang2"]).mapNotNull { code(it) }
        val langs = (listOfNotNull(fromPlayer) + named).distinct().ifEmpty {
            listOfNotNull(PlayerPrefs.normLang(PlayerPrefs.audioLang(ctx) ?: Locale.getDefault().language))
        }
        val show = player != PlayerPrefs.OFF && (fromPlayer != null || s["subs.enabled"] == "true")
        return Prefs(
            if (show) langs.take(3) else langs.take(1), show,
            embeddedFirst = s["subs.embedded"] != "false",
            hi = s["subs.hi"] == "true",
            forced = s["subs.forced"] == "true",
        ).also { lastPrefs = it }
    }

    /** "English" → "en", "Portuguese(Brazil)" → "pob" (OpenSubtitles' own code); null when unknown. */
    private fun code(name: String?): String? {
        val n = name?.trim()?.takeIf { it.isNotEmpty() && !it.equals("none", true) } ?: return null
        if (n.replace(" ", "").equals("Portuguese(Brazil)", true)) return "pob"
        return Locale.getISOLanguages().firstOrNull { Locale(it).getDisplayLanguage(Locale.ENGLISH).equals(n, true) }
            ?: PlayerPrefs.normLang(n)?.takeIf { it.length <= 3 }
    }

    /** The viewer's subtitle languages in order (the player's choice, then Settings' first and second); empty when none is set. Blocking. */
    fun viewerLanguages(ctx: Context): List<String> {
        val s = settings(ctx)
        val fromPlayer = PlayerPrefs.subLang(ctx)?.takeIf { it != PlayerPrefs.OFF }
        return (listOfNotNull(fromPlayer) + listOf(s["subs.lang1"], s["subs.lang2"]).mapNotNull { code(it) }).distinct()
    }

    // ---- the index ----

    private fun key(r: PlaybackRequest): String {
        val id = if (r.tmdbId > 0) "${r.tmdbId}" else r.imdb.orEmpty()
        return if (r.type == "episode") "tv:$id:${r.season}:${r.episode}" else "movie:$id"
    }

    private val indexLangs = ConcurrentHashMap<String, Pair<Set<String>, Long>>()

    /**
     * Blocking. Every subtitle language the index has for [req] (normalized; "pob" for Brazilian Portuguese), for the
     * title page's language pills ([LangPills]); null when the index couldn't be asked. Kept for [TTL].
     */
    fun indexLanguages(ctx: Context, req: PlaybackRequest): Set<String>? {
        val k = key(req)
        indexLangs[k]?.takeIf { SystemClock.elapsedRealtime() - it.second < TTL }?.let { return it.first }
        val ep = req.type == "episode"
        if (req.trailer || (ep && (req.season == null || req.episode == null))) return null
        return try {
            val id = NativeResolver.imdbFor(ctx.applicationContext, req) ?: return null
            val r = DebridHttp.get(if (ep) "$API/series/$id:${req.season}:${req.episode}.json" else "$API/movie/$id.json", timeoutMs = 6_000)
            val a = if (r.ok) r.json().optJSONArray("subtitles") else null
            if (a == null) null else {
                val out = HashSet<String>()
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    if (ep && o.optInt("season") > 0 && (o.optInt("season") != req.season || o.optInt("episode") != req.episode)) continue
                    val raw = o.optString("lang").lowercase(Locale.US)
                    (if (raw == "pob") raw else PlayerPrefs.normLang(raw))?.let { out += it }
                }
                if (indexLangs.size > 200) indexLangs.clear()
                indexLangs[k] = out to SystemClock.elapsedRealtime()
                out
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dream Subtitles: index unavailable for the language pills (${e.javaClass.simpleName})"); null
        }
    }

    private val HI = Regex("(?i)(?<![a-z])(sdh|hi|cc|hearing[ ._-]?impaired)(?![a-z])")
    private val FORCED = Regex("(?i)(?<![a-z])forced(?![a-z])")
    private val SPLIT = Regex("(?i)(?<![a-z])cd ?[1-9](?![0-9])")
    private val SOURCES = listOf("remux", "bluray", "bdrip", "brrip", "web", "webdl", "webrip", "hdtv", "dvdrip", "hdrip", "amzn", "nf", "dsnp", "hmax", "atvp")
    private val RES = listOf("2160p", "1080p", "720p", "480p")

    /**
     * Fetches [req]'s subtitle list and downloads the likely picks, in the background (returns at
     * once; nothing when cached for the same languages or already on its way). [imdb]: the show's or
     * movie's IMDb id when the caller knows it (else looked up like the resolver does). Any thread.
     */
    fun prefetch(ctx: Context, req: PlaybackRequest, imdb: String?) {
        if (req.trailer) return
        val k = key(req)
        if (!inFlight.add(k)) return
        val app = ctx.applicationContext
        Net.run {
            try {
                val p = prefs(app)
                if (cache[k]?.takeIf { it.fresh && it.langs == p.langs } != null) return@run
                val id = imdb?.takeIf { it.startsWith("tt") } ?: NativeResolver.imdbFor(app, req) ?: return@run
                val ep = req.type == "episode"
                if (ep && (req.season == null || req.episode == null)) return@run
                val t0 = SystemClock.elapsedRealtime()
                val r = DebridHttp.get(if (ep) "$API/series/$id:${req.season}:${req.episode}.json" else "$API/movie/$id.json", timeoutMs = 6_000)
                if (!r.ok) { Log.w(TAG, "Dream Subtitles: index HTTP ${r.code}"); return@run }
                val a = r.json().optJSONArray("subtitles") ?: return@run
                val all = ArrayList<Entry>()
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val u = o.optString("url").takeIf { it.startsWith("https://") } ?: continue
                    // A series list can hold a neighbouring episode's file: only the asked-for one.
                    if (ep && o.optInt("season") > 0 && (o.optInt("season") != req.season || o.optInt("episode") != req.episode)) continue
                    val raw = o.optString("lang").lowercase(Locale.US)
                    val lang = (if (raw == "pob") raw else PlayerPrefs.normLang(raw))?.takeIf { it in p.langs } ?: continue
                    val file = o.optString("subtitleFileName")
                    all += Entry(u, lang, file, o.optString("releaseGroup"), HI.containsMatchIn(file), FORCED.containsMatchIn(file), SPLIT.containsMatchIn(file))
                }
                // The likely picks per language (the playing file's name isn't known yet), to local files.
                val dir = File(app.cacheDir, "dreamsubs").apply { mkdirs() }
                sweep(dir)
                val got = p.langs.flatMap { l ->
                    all.withIndex().filter { it.value.lang == l }
                        .sortedWith(compareBy({ it.value.split }, { it.value.forced != p.forced }, { it.value.hi != p.hi }, { it.index }))
                        .take(AHEAD).mapNotNull { download(it.value, dir) }
                }
                cache[k] = Stamped(got, p.langs)
                if (cache.size > 60) cache.entries.removeAll { !it.value.fresh }
                Log.w(TAG, "Dream Subtitles: ${got.size} of ${all.size} in ${p.langs.joinToString("/")} ready for ${req.title} in ${SystemClock.elapsedRealtime() - t0} ms")
            } catch (e: Exception) {
                Log.w(TAG, "Dream Subtitles: index unavailable (${e.javaClass.simpleName})")
            } finally {
                inFlight.remove(k)
            }
        }
    }

    /** [e] as a local .srt (named by its URL's hash; a file already there is reused), or null. */
    private fun download(e: Entry, dir: File): Entry? {
        val f = File(dir, "${Integer.toHexString(e.url.hashCode())}.srt")
        if (f.length() > 0) return e.copy(path = f.absolutePath)
        return try {
            val r = DebridHttp.get(e.url, timeoutMs = 6_000)
            val body = r.body.trimStart('﻿')
            if (!r.ok || body.length > MAX_BYTES || !body.contains("-->")) return null // not an SRT (an error page)
            val tmp = File(dir, f.name + ".part")
            tmp.writeText(body)
            if (!tmp.renameTo(f)) { tmp.delete(); return null }
            e.copy(path = f.absolutePath)
        } catch (x: Exception) { null }
    }

    /** Files older than two days go (a few hundred KB a title at most). */
    private fun sweep(dir: File) {
        val old = System.currentTimeMillis() - 2 * 24 * 3600_000L
        dir.listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
    }

    private fun words(s: String) = s.lowercase(Locale.US).replace("web-dl", "webdl").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()

    /** The files to add for [p], best first per language (see the class comment); [playing] is the stream's file name. */
    private fun pick(list: List<Entry>, p: Prefs, playing: String?): List<Entry> {
        val base = playing?.substringBeforeLast('.')
        val pw = base?.let { words(it) }.orEmpty()
        val group = base?.substringAfterLast('-', "")?.lowercase(Locale.US)?.takeIf { it.length in 2..20 && it.all(Char::isLetterOrDigit) }
        fun score(e: Entry): Int {
            val ew = words(e.file.substringBeforeLast('.')) + words(e.group)
            var s = 0
            if (group != null && group in ew) s += 6
            s += SOURCES.count { it in pw && it in ew } * 2
            s += RES.count { it in pw && it in ew }
            if (e.split) s -= 20                    // half a film
            if (e.forced != p.forced) s -= 8        // forced files only cover the foreign-language parts
            if (e.hi != p.hi) s -= 2
            return s
        }
        return p.langs.flatMap { l ->
            list.withIndex().filter { it.value.lang == l && it.value.path != null }
                .sortedWith(compareBy({ -score(it.value) }, { it.index })).take(PER_LANG).map { it.value }
        }
    }

    // ---- one player screen ----

    /**
     * Dream Subtitles for one player screen ([DreamPlayerActivity]): [begin] at each stream start
     * returns what goes into the media item right away; a list that comes later waits for a moment
     * the re-prepare can't be seen. Main thread.
     */
    class Session(private val ctx: Context, private val exo: ExoEngine) : PlayerEngine.Listener {
        private val main = Handler(Looper.getMainLooper())
        private var req: PlaybackRequest? = null
        private var file: String? = null
        private var gen = 0
        private var pending: List<SubtitleFile>? = null
        private var attached = false
        private var started = false
        /** A late list while subtitles are off: kept in case the audio turns out to need them ([showWhenReady]). */
        private var held: List<SubtitleFile>? = null
        /** The late list's wait ended with nothing to add. */
        private var none = false
        private var whenReady: ((Boolean) -> Unit)? = null

        init { exo.addListener(this) }

        /** The subtitle languages this stream's list was fetched for, best first (normalized; "pob" for Brazilian). */
        fun languages(): List<String> = lastPrefs?.langs.orEmpty()

        /**
         * The audio isn't in the viewer's language ([PlayerOverlay.audioCheck]): a list that comes late goes in at
         * once, subtitles off or not (a rebuffer beats a film nobody can follow), and [done] hears whether one did.
         */
        fun showWhenReady(done: (Boolean) -> Unit) {
            when {
                req == null || attached || none -> done(false)
                held != null -> { pending = held; held = null; whenReady = done; attachNow(force = true) }
                pending != null -> { whenReady = done; attachNow(force = true) }
                else -> whenReady = done
            }
        }

        private fun settle(added: Boolean) {
            if (!added) none = true
            whenReady?.let { whenReady = null; it(added) }
        }

        /**
         * A stream starts: [raw] for [r]. Only Dream OS's own streams (the resolver's link for [r])
         * that brought no subtitles of their own ([hasSubs]) get Dream Subtitles. Returns the ones
         * ready now (empty when none, or not ours). No I/O.
         */
        fun begin(r: PlaybackRequest?, raw: String, hasSubs: Boolean): List<SubtitleFile> {
            end()
            val found = r?.let { NativeResolver.peek(it) }?.takeIf { it.url == raw.substringBefore('|') }
            if (r == null || r.trailer || found == null || hasSubs) return emptyList()
            req = r; file = found.fileName
            val p = lastPrefs
            val ready = p?.let { cache[key(r)]?.takeIf { s -> s.fresh && s.langs == p.langs }?.list }
            if (p == null || ready == null) {
                prefetch(ctx, r, null) // joins one under way
                wait(gen)
                return emptyList()
            }
            attached = true
            return subtitles(pick(ready, p, file), p, preferDownload = !p.embeddedFirst).also { log(r, it, "at the start") }
        }

        /** A late list: checked every half second for ~20 s. */
        private fun wait(g: Int, tries: Int = 0) {
            main.postDelayed({
                val r = req
                if (g != gen || attached || r == null) return@postDelayed
                val p = lastPrefs
                val list = p?.let { cache[key(r)]?.takeIf { s -> s.fresh && s.langs == p.langs }?.list }
                if (p == null || list == null) { if (tries < 40) wait(g, tries + 1) else settle(false); return@postDelayed }
                val subs = subtitles(pick(list, p, file), p, preferDownload = !(p.embeddedFirst && hasOwn(p)))
                when {
                    subs.isEmpty() -> settle(false)
                    // Not shown anyway: no reason to touch the stream (the menu lists the video's own tracks).
                    !p.show && whenReady == null -> { held = subs; Log.w(TAG, "Dream Subtitles: ${subs.size} came after the start; not added (subtitles are off)") }
                    else -> { pending = subs; if (whenReady != null) attachNow(force = true) else tryAttach() }
                }
            }, 500)
        }

        /** The video has a track of its own in one of the user's languages (once its tracks are known). */
        private fun hasOwn(p: Prefs) = exo.tracks("text").any { PlayerPrefs.normLang(it.language) in p.langs }

        private fun subtitles(list: List<Entry>, p: Prefs, preferDownload: Boolean): List<SubtitleFile> = list.map { e ->
            // One word at most: the menu adds SDH / Forced from it ("English · SDH"), the language comes from the code.
            val label = if (e.hi) "SDH" else if (e.forced) "Forced" else if (e.lang == "pob") "Brazil" else null
            SubtitleFile(e.path!!, if (e.lang == "pob") "pt-BR" else e.lang, label, online = true, asDefault = p.show && preferDownload)
        }

        private val attachLater = Runnable { attachNow() }

        private fun bufferEmpty() = exo.isBuffering && exo.bufferedMs - exo.positionMs < 300

        /** A late list goes in at a pause of 1.5 s or more, or while the buffer is empty anyway. */
        private fun tryAttach() {
            if (pending == null || !started || !exo.hasSource) return
            if (exo.player?.playWhenReady == false) { main.removeCallbacks(attachLater); main.postDelayed(attachLater, 1_500); return }
            main.removeCallbacks(attachLater)
            if (bufferEmpty()) attachNow()
        }

        private fun attachNow(force: Boolean = false) {
            val subs = pending ?: return
            if (!exo.hasSource || exo.player == null || (!force && exo.player?.playWhenReady != false && !bufferEmpty())) return
            pending = null; attached = true
            exo.addExternalSubtitles(subs)
            req?.let { log(it, subs, if (force) "for audio in another language" else "during a pause") }
            settle(true)
        }

        /**
         * The first frame (the tracks are known): with "in the video first" and subtitles on but none
         * picked in the player yet, the video's own track in the language, else the download. A late
         * list may now wait for its moment.
         */
        fun onFirstFrame() {
            started = true
            val p = lastPrefs
            if (attached && p != null && p.show && p.embeddedFirst) {
                val text = exo.tracks("text")
                if (text.none { it.selected }) p.langs.firstNotNullOfOrNull { l ->
                    text.firstOrNull { it.supported && PlayerPrefs.normLang(it.language) == (if (l == "pob") "pt" else l) }
                }?.let { exo.selectTrack("text", it.id) }
            }
            tryAttach()
        }

        override fun onState() { tryAttach() }

        /** The stream ended, or another starts. */
        fun end() {
            gen++
            req = null; file = null; pending = null; attached = false; started = false
            held = null; none = false; whenReady = null
            main.removeCallbacks(attachLater)
        }

        private fun log(r: PlaybackRequest, subs: List<SubtitleFile>, at: String) {
            if (subs.isEmpty()) return
            val by = subs.groupingBy { it.language }.eachCount().entries.joinToString(", ") { "${it.value} ${it.key}" }
            Log.w(TAG, "Dream Subtitles: ${r.title}: $by added $at")
        }
    }
}
