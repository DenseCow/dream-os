package com.dreamos.player.subs

import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.platform.Platform
import com.dreamos.desktop.core.playback.PlayTarget
import com.dreamos.player.engine.PlayerLog
import com.dreamos.player.engine.Tracks
import com.dreamos.player.session.PlayerPrefs
import com.dreamos.player.session.PlayerSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Dream Subtitles (fire-home DreamSubtitles): subtitles from the web for the streams Dream OS finds, which arrive with
 * only what's inside the file. Source: the free OpenSubtitles index the Fire Stick and iPhone use (no key):
 * `opensubtitles-v3.strem.io/subtitles/{movie|series}/{imdb}[:s:e].json` -> `subtitles[] {url, lang (ISO 639-2, "pob"
 * Brazilian), subtitleFileName, releaseGroup?, season, episode}`; each url is a plain UTF-8 SRT.
 *
 * - Fetched while the curtain is up and saved to `%LOCALAPPDATA%\Dream OS\Cache\subs` (swept after two days), so the
 *   player only ever reads local files. mpv adds a subtitle file to a playing stream with no rebuffer, so the Fire
 *   Stick's "wait for a pause" step isn't needed: the files go in unselected the moment they're ready.
 * - Which ones: the viewer's languages (the player's choice, then Settings' first and second language), the best two
 *   per language: whole files before CD1 / CD2 halves, SDH and forced as the settings prefer, the release name closest
 *   to the playing file (group, source, resolution), then the index's order (most downloaded first). With subtitles
 *   off only the first language is fetched (listed in the menu, so turning them on needs no download).
 * - Which track shows is the player's call ([PlayerSession] autoApplyTracks, through [pick]): "Use Subtitles in the
 *   Video First" prefers the video's own track in the language and falls back to a download; off, a download first.
 *
 * Worst case: 60 lists in memory (a few KB each), 4 files per language on disk (an SRT is 30 to 150 KB, capped 2 MB).
 */
object DreamSubtitles {
    private const val API = "https://opensubtitles-v3.strem.io/subtitles"
    private const val TTL = 60 * 60_000L
    private const val PER_LANG = 2
    private const val AHEAD = 4
    private const val MAX_BYTES = 2_000_000

    /** One downloaded subtitle. [lang] is core's code ("en"; "pob" stays "pob" until it reaches mpv as Portuguese). */
    data class Sub(val path: String, val lang: String, val file: String, val group: String, val hi: Boolean, val forced: Boolean, val split: Boolean)

    /**
     * Settings › Subtitles as the player reads them. [langs]: codes in order; [show]: subtitles should be on when a
     * title starts (the Subtitles setting, or a language picked in the player).
     */
    data class Prefs(val langs: List<String>, val show: Boolean, val embeddedFirst: Boolean, val hi: Boolean, val forced: Boolean)

    fun prefs(player: PlayerPrefs): Prefs {
        val fromPlayer = player.subLang?.takeIf { it != PlayerPrefs.OFF }
        val named = listOf(PlayerSettings.text("subs.lang1") ?: "English", PlayerSettings.text("subs.lang2")).mapNotNull { code(it) }
        val langs = (listOfNotNull(fromPlayer) + named).distinct()
            .ifEmpty { listOfNotNull(player.audioLang ?: Tracks.normLang(Locale.getDefault().language)) }
        val show = player.subLang != PlayerPrefs.OFF && (fromPlayer != null || PlayerSettings.flag("subs.enabled", false))
        return Prefs(
            langs = if (show) langs.take(3) else langs.take(1),
            show = show,
            embeddedFirst = PlayerSettings.flag("subs.embedded", true),
            hi = PlayerSettings.flag("subs.hi", false),
            forced = PlayerSettings.flag("subs.forced", false),
        )
    }

    /** "English" -> "en", "Portuguese (Brazil)" -> "pob" (OpenSubtitles' own code); "None" and unknown -> null. */
    fun code(name: String?): String? {
        val n = name?.trim()?.takeIf { it.isNotEmpty() && !it.equals("none", true) } ?: return null
        if (n.replace(" ", "").equals("Portuguese(Brazil)", true)) return "pob"
        return Locale.getISOLanguages().firstOrNull { Locale(it).getDisplayLanguage(Locale.ENGLISH).equals(n, true) }
            ?: Tracks.normLang(n)?.takeIf { it.length <= 3 }
    }

    private class Stamped(val list: List<Sub>, val langs: List<String>, val at: Long = System.currentTimeMillis()) {
        val fresh get() = System.currentTimeMillis() - at < TTL
    }

    private val cache = ConcurrentHashMap<String, Stamped>()

    private fun key(t: PlayTarget) = t.key

    /**
     * What [fetch] found: the downloads, and every language the index has for the title ([indexLangs], for core's
     * language pills; null when the index wasn't asked this time).
     */
    class Fetched(val subs: List<Sub>, val indexLangs: Set<String>?)

    /**
     * Blocking (an IO thread): the subtitles for [t] in [p]'s languages, from the cache when fresh, else the index
     * and downloads. Empty when none or when the index can't be reached.
     */
    fun fetch(t: PlayTarget, imdb: String?, p: Prefs): Fetched {
        val k = key(t)
        cache[k]?.takeIf { it.fresh && it.langs == p.langs }?.let { return Fetched(it.list, null) }
        val none = Fetched(emptyList(), null)
        val id = (imdb ?: t.imdb)?.takeIf { it.startsWith("tt") } ?: return none
        if (t.isEpisode && (t.season == null || t.episode == null)) return none
        val t0 = System.currentTimeMillis()
        val url = if (t.isEpisode) "$API/series/$id:${t.season}:${t.episode}.json" else "$API/movie/$id.json"
        val every = try {
            val r = Net.request("GET", url, timeoutMs = 6_000)
            if (!r.ok) { PlayerLog.w("Dream Subtitles: the index answered ${r.code}"); return none }
            parse(r.body, t)
        } catch (e: Exception) {
            PlayerLog.w("Dream Subtitles: index unavailable (${e.javaClass.simpleName})"); return none
        }
        val all = every.filter { it.second.lang in p.langs }
        val dir = Platform.current.cacheDir.resolve("subs").toFile().apply { mkdirs() }
        sweep(dir)
        val got = p.langs.flatMap { l ->
            all.withIndex().filter { it.value.second.lang == l }
                .sortedWith(compareBy({ it.value.second.split }, { it.value.second.forced != p.forced }, { it.value.second.hi != p.hi }, { it.index }))
                .take(AHEAD).mapNotNull { download(it.value.first, it.value.second, dir) }
        }
        cache[k] = Stamped(got, p.langs)
        if (cache.size > 60) cache.entries.removeAll { !it.value.fresh }
        PlayerLog.i("Dream Subtitles: ${got.size} of ${all.size} in ${p.langs.joinToString("/")} ready for ${t.label} in ${System.currentTimeMillis() - t0} ms")
        return Fetched(got, every.map { it.second.lang }.toSet())
    }

    private val HI = Regex("(?i)(?<![a-z])(sdh|hi|cc|hearing[ ._-]?impaired)(?![a-z])")
    private val FORCED = Regex("(?i)(?<![a-z])forced(?![a-z])")
    private val SPLIT = Regex("(?i)(?<![a-z])cd ?[1-9](?![0-9])")

    /** The index's entries for exactly this episode, every language: (url, entry without a path yet). */
    private fun parse(body: String, t: PlayTarget): List<Pair<String, Sub>> {
        val arr = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["subtitles"] as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
            val u = s("url").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            // A series list can hold a neighbouring episode's file: only the asked-for one.
            val se = o["season"]?.jsonPrimitive?.intOrNull ?: 0
            if (t.isEpisode && se > 0 && (se != t.season || o["episode"]?.jsonPrimitive?.intOrNull != t.episode)) return@mapNotNull null
            val raw = s("lang").lowercase(Locale.US)
            val lang = (if (raw == "pob") raw else Tracks.normLang(raw)) ?: return@mapNotNull null
            val file = s("subtitleFileName")
            u to Sub("", lang, file, s("releaseGroup"), HI.containsMatchIn(file), FORCED.containsMatchIn(file), SPLIT.containsMatchIn(file))
        }
    }

    /** [s] as a local .srt named by its URL's hash (a file already there is reused), or null. */
    private fun download(url: String, s: Sub, dir: File): Sub? {
        val f = File(dir, "${Integer.toHexString(url.hashCode())}.srt")
        if (f.length() > 0) return s.copy(path = f.absolutePath)
        return try {
            val r = Net.request("GET", url, timeoutMs = 6_000)
            val text = r.body.trimStart('﻿')
            // Not an SRT (an error page) or too big: skipped.
            if (!r.ok || text.length > MAX_BYTES || !text.contains("-->")) return null
            val tmp = File(dir, f.name + ".part")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(f)) { tmp.delete(); return null }
            s.copy(path = f.absolutePath)
        } catch (e: Exception) { null }
    }

    private fun sweep(dir: File) {
        val old = System.currentTimeMillis() - 2 * 24 * 3600_000L
        dir.listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
    }

    private val SOURCES = listOf("remux", "bluray", "bdrip", "brrip", "web", "webdl", "webrip", "hdtv", "dvdrip", "hdrip", "amzn", "nf", "dsnp", "hmax", "atvp")
    private val RES = listOf("2160p", "1080p", "720p", "480p")

    private fun words(s: String) = s.lowercase(Locale.US).replace("web-dl", "webdl").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()

    /** The files to add, best first per language: [PER_LANG] each, closest to [playing] (the stream's file name). */
    fun pick(list: List<Sub>, p: Prefs, playing: String?): List<Sub> {
        val base = playing?.substringBeforeLast('.')
        val pw = base?.let { words(it) }.orEmpty()
        val group = base?.substringAfterLast('-', "")?.lowercase(Locale.US)?.takeIf { it.length in 2..20 && it.all(Char::isLetterOrDigit) }
        fun score(e: Sub): Int {
            val ew = words(e.file.substringBeforeLast('.')) + words(e.group)
            var s = 0
            if (group != null && group in ew) s += 6
            s += SOURCES.count { it in pw && it in ew } * 2
            s += RES.count { it in pw && it in ew }
            if (e.split) s -= 20
            if (e.forced != p.forced) s -= 8
            if (e.hi != p.hi) s -= 2
            return s
        }
        return p.langs.flatMap { l ->
            list.withIndex().filter { it.value.lang == l }.sortedWith(compareBy({ -score(it.value) }, { it.index })).take(PER_LANG).map { it.value }
        }
    }

    /** The track label word mpv gets for a download ("SDH", "Forced", "Brazil"); the language comes from the code. */
    fun label(s: Sub): String = when {
        s.hi -> "SDH"
        s.forced -> "Forced"
        s.lang == "pob" -> "Brazil"
        else -> ""
    }

    /** The language mpv gets ("pob" is Portuguese there). */
    fun mpvLang(s: Sub): String = if (s.lang == "pob") "pt-BR" else s.lang
}
