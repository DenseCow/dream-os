package com.firehome.launcher

import android.content.Context
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Anime audio. New anime episodes usually come out in Japanese with subtitles; dubs follow later. This reads
 * release names only: which copies say they carry a dub in the viewer's audio language ([audio]), the source
 * list's badge ([badge]), and the per-profile Prefer English Dubs switch that Dream Core's ranking follows ([rank]).
 * What a title page says about audio and subtitles before Play is [LangPills]' job, for every language.
 * A name that says nothing counts for nothing: no label is better than a guess.
 */
object AnimeDubs {
    private const val TAG = "FireHome"

    enum class Audio { DUB, ORIGINAL, NONE }

    /**
     * Dream Core's ranking for a title ([rank]): no dub preference (the viewer's own language, or Prefer Dubs off for a
     * film or show), dubs first (anything in another language with Prefer Dubs on), anime with Prefer Dubs off.
     */
    const val RANK_NONE = 0
    const val RANK_DUB = 1
    const val RANK_PLAIN = 2

    // ---- the setting (per profile, next to the audio and subtitle languages) ----

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("player", Context.MODE_PRIVATE)
    private fun k(ctx: Context) = "preferDubs" + Profiles.suffix(ctx)

    /** The profile's audio language as a 2-letter code (Dream Player's, else the device's). */
    fun want(ctx: Context): String =
        PlayerPrefs.normLang(PlayerPrefs.audioLang(ctx) ?: Locale.getDefault().language) ?: "en"

    /** On unless the viewer's audio language is Japanese (then the original is already their language). */
    fun preferDubs(ctx: Context): Boolean = prefs(ctx).let { p ->
        if (p.contains(k(ctx))) p.getBoolean(k(ctx), true) else want(ctx) != "ja"
    }

    fun setPreferDubs(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(k(ctx), on).apply()
        Log.w(TAG, "Settings: prefer ${want(ctx)} dubs ${if (on) "on" else "off"}")
    }

    /** "English" for "en" (the names Settings › Audio Language uses). */
    fun langName(code: String?): String = PlaybackPrefs.AUDIO.firstOrNull { it.first == code }?.second ?: PlayerPrefs.displayLang(code)

    fun settingLabel(ctx: Context) = "Prefer ${langName(want(ctx))} Dubs"
    /** Title Case like the other Settings captions. */
    fun settingCaption(ctx: Context) = "Plays the ${langName(want(ctx))} Dub When a Copy Has One"

    // ---- is it anime, what language is it ----

    private val anime = ConcurrentHashMap<String, Boolean>()
    private val original = ConcurrentHashMap<String, String>()

    /** A title page already knows: saves [isAnime] a details lookup. */
    fun note(t: Title) {
        if (t.genreIds.isNotEmpty() && t.lang.isNotEmpty()) anime["${t.type}:${t.id}"] = RowMix.isAnime(t)
        PlayerPrefs.normLang(t.lang)?.let { original["${t.type}:${t.id}"] = it }
    }

    /** Blocking (TMDB details, cached). [req]'s original language ("ko"), or null when TMDB doesn't say. */
    fun originalLanguage(ctx: Context, req: PlaybackRequest): String? {
        if (req.trailer || req.tmdbId <= 0) return null
        val type = if (req.type == "episode") "tv" else "movie"
        original["$type:${req.tmdbId}"]?.let { return it }
        val d = WatchHistory.details(ctx.applicationContext, type, req.tmdbId) ?: return null
        return PlayerPrefs.normLang(d.optString("original_language"))?.also { original["$type:${req.tmdbId}"] = it }
    }

    private val spoken = ConcurrentHashMap<String, Set<String>>()

    /**
     * Blocking (TMDB details, cached). The languages spoken in [req]'s title (TMDB's spoken_languages), or null when TMDB
     * doesn't say: an English track in a film that has English among them may be the original, not a dub.
     */
    fun spokenLanguages(ctx: Context, req: PlaybackRequest): Set<String>? {
        if (req.trailer || req.tmdbId <= 0) return null
        val type = if (req.type == "episode") "tv" else "movie"
        spoken["$type:${req.tmdbId}"]?.let { return it }
        val a = WatchHistory.details(ctx.applicationContext, type, req.tmdbId)?.optJSONArray("spoken_languages") ?: return null
        return (0 until a.length()).mapNotNull { PlayerPrefs.normLang(a.optJSONObject(it)?.optString("iso_639_1")) }.toSet()
            .takeIf { it.isNotEmpty() }?.also { spoken["$type:${req.tmdbId}"] = it }
    }

    /** What [isAnime] already knows about a "tv" / "movie" title, without asking; null when it hasn't been asked. */
    fun animeKnown(type: String, id: Int): Boolean? = anime["$type:$id"]

    /** Blocking (TMDB details, cached). Anime as the anime sections define it ([RowMix.isAnime]). */
    fun isAnime(ctx: Context, req: PlaybackRequest): Boolean {
        if (req.trailer || req.tmdbId <= 0) return false
        val type = if (req.type == "episode") "tv" else "movie"
        anime["$type:${req.tmdbId}"]?.let { return it }
        val d = WatchHistory.details(ctx.applicationContext, type, req.tmdbId) ?: return false
        val g = d.optJSONArray("genres")
        val ids = if (g == null) emptyList() else (0 until g.length()).mapNotNull { g.optJSONObject(it)?.optInt("id") }
        val t = Title(req.tmdbId, type, "", "", null, null, "", 0.0, genreIds = ids, lang = d.optString("original_language"), originCountry = Tmdb.countries(d))
        PlayerPrefs.normLang(t.lang)?.let { original["$type:${req.tmdbId}"] = it }
        return RowMix.isAnime(t).also { anime["$type:${req.tmdbId}"] = it }
    }

    /**
     * How Dream Core ranks [req]'s copies. Blocking (see [isAnime]). Anything whose original language isn't the
     * viewer's plays a copy that carries their language first (within a quality tier) while Prefer Dubs is on, so Play
     * matches the title page's "English Dub Available" ([LangPills]).
     */
    fun rank(ctx: Context, req: PlaybackRequest): Int = try {
        val w = want(ctx)
        val dubs = preferDubs(ctx) && w != "ja"
        when {
            isAnime(ctx, req) -> if (dubs) RANK_DUB else RANK_PLAIN
            dubs && originalLanguage(ctx, req).let { it != null && it != w } -> RANK_DUB
            else -> RANK_NONE
        }
    } catch (e: Exception) { RANK_NONE }

    // ---- release names ----

    internal val SUBS = setOf("SUB", "SUBS", "SUBBED", "SUBTITLE", "SUBTITLES", "SUBTITLED", "ESUB", "ESUBS", "ENGSUB", "ENGSUBS",
        "MULTISUB", "MULTISUBS", "SOFTSUB", "SOFTSUBS", "HARDSUB", "HARDSUBS", "VOSTFR", "VOSE", "VOSTA")
    /** Release groups that only put out the original audio with English subtitles. */
    internal val SUB_GROUPS = setOf("SUBSPLEASE", "HORRIBLESUBS", "ERAI")
    internal val DUBS = setOf("DUB", "DUBS", "DUBBED", "DUBBING")

    private val SPLIT = Regex("[^A-Z0-9]+")
    internal fun tokens(name: String) = name.uppercase(Locale.US).split(SPLIT).filter { it.isNotEmpty() }

    /**
     * What [name] says about its audio for a viewer whose language is [want]: [Audio.DUB] when it says it carries
     * [want] (DUAL AUDIO, DUAL, ENGLISH DUB, ENG DUB, DUBBED, MULTI with ENG, ENG / ENGLISH as audio), [Audio.ORIGINAL] when it
     * says subtitles or only the original language (SubsPlease, "ENG SUB", VOSTFR, JAPANESE), else [Audio.NONE].
     * A language next to a subtitle word ("ENG SUB", "MULTI-SUBS") is about subtitles, never audio.
     */
    fun audio(name: String, want: String): Audio {
        if (want == "ja") return Audio.NONE
        val t = tokens(name)
        fun subNear(i: Int) = t.getOrNull(i + 1) in SUBS || t.getOrNull(i - 1) in SUBS
        val subWord = t.any { it in SUBS } || t.any { it in SUB_GROUPS }
        val langs = HashSet<String>()
        var multi = false
        for ((i, w) in t.withIndex()) {
            if (w == "MULTI" || w == "MULTIAUDIO" || w == "MULTILANG") { if (!subNear(i)) multi = true; continue }
            if (subNear(i)) continue
            AudioLanguages.tagLanguage(w)?.let { langs += it }
        }
        val dual = "DUALAUDIO" in t || "DUAL" in t
        val dubWord = t.any { it in DUBS }
        val engDub = "ENGDUB" in t || "ENGDUBBED" in t
        val says = when {
            want == "en" && (engDub || dual) -> true
            // Named as audio ("ENG", "ENGLISH", "Kor-Eng", "[Eng+Kor]", MULTI with ENG, "ENG AAC"), wherever a subtitle word sits.
            want in langs -> true
            // "Dubbed" alone (no other language named): English-speaking trackers' word for the English dub.
            want == "en" && dubWord && langs.all { it == "en" || it == "ja" } -> true
            else -> false
        }
        if (says) return Audio.DUB
        val original = subWord || (langs == setOf("ja") && !multi && !dubWord)
        return if (original) Audio.ORIGINAL else Audio.NONE
    }

    /**
     * The source list's audio badge for [name] (Change Source, Choose a Source): "English Dub", "Dual Audio",
     * "Multi Audio", "Subtitled", or for anime the one language it names ("Japanese Audio"); null when it says none.
     */
    fun badge(name: String, want: String, isAnime: Boolean): String? {
        val t = tokens(name)
        val a = audio(name, want)
        // A film in the viewer's own language names it too ("ENG ITA"): "Dub" only where the original is another language.
        if (isAnime && a == Audio.DUB) return "${langName(want)} Dub"
        if ("DUALAUDIO" in t || "DUAL" in t) return "Dual Audio"
        if (("MULTI" in t && t.getOrNull(t.indexOf("MULTI") + 1) !in SUBS) || "MULTIAUDIO" in t) return "Multi Audio"
        if (a == Audio.ORIGINAL && (t.any { it in SUBS } || t.any { it in SUB_GROUPS })) return "Subtitled"
        if (!isAnime) return null
        val named = t.withIndex().filter { (i, _) -> t.getOrNull(i + 1) !in SUBS && t.getOrNull(i - 1) !in SUBS }
            .mapNotNull { AudioLanguages.tagLanguage(it.value) }.toSet()
        return named.singleOrNull()?.takeIf { it != want }?.let { "${langName(it)} Audio" }
    }

    // ---- what the scrapers found, per title / episode ----

    /** The key [NativeResolver] and [Availability] use: "movie:<id>" or "tv:<show>:<s>:<e>". */
    fun key(r: PlaybackRequest): String {
        val id = if (r.tmdbId > 0) "${r.tmdbId}" else r.imdb.orEmpty()
        return if (r.type == "episode") "tv:$id:${r.season}:${r.episode}" else "movie:$id"
    }

    /** Dream Core read [copies] (release name, info hash) for [key] (a survey, a lookup, the source list): kept for the language pills. */
    fun record(ctx: Context, key: String, copies: List<Pair<String, String>>, want: String) = LangPills.record(ctx, key, copies, want)
}
