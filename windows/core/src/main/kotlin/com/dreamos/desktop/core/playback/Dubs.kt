package com.dreamos.desktop.core.playback

/**
 * What release names say about a dub (fire-home AnimeDubs, iPhone AnimeDubNames.swift): which copies carry the
 * viewer's audio language, the source list's audio badge, and how Dream Core ranks a title's copies. A name that says
 * nothing counts for nothing: no label is better than a guess.
 */
object Dubs {
    enum class Audio { DUB, ORIGINAL, NONE }

    /** How a title's copies rank: no dub preference, dubs first (another language with Prefer Dubs on), anime with it off. */
    enum class Rank { NONE, DUB, PLAIN }

    internal val SUBS = setOf("SUB", "SUBS", "SUBBED", "SUBTITLE", "SUBTITLES", "SUBTITLED", "ESUB", "ESUBS", "ENGSUB",
        "ENGSUBS", "MULTISUB", "MULTISUBS", "SOFTSUB", "SOFTSUBS", "HARDSUB", "HARDSUBS", "VOSTFR", "VOSE", "VOSTA")
    /** Release groups that only put out the original audio with subtitles. */
    internal val SUB_GROUPS = setOf("SUBSPLEASE", "HORRIBLESUBS", "ERAI")
    internal val DUB_WORDS = setOf("DUB", "DUBS", "DUBBED", "DUBBING")

    /** Release-name tokens, letters and digits only. */
    internal fun tokens(name: String) = name.uppercase().split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }

    /**
     * Dubs first for anything whose original language isn't [want] while Prefer Dubs is on (so Play matches the title
     * page's "English Dub Available"); anime with it off gets no language preference.
     */
    fun rank(target: PlayTarget, want: String?, preferDubs: Boolean): Rank {
        val dubs = preferDubs && want != null && want != "ja"
        if (target.isAnime) return if (dubs) Rank.DUB else Rank.PLAIN
        val lang = target.originalLanguage?.lowercase().orEmpty()
        return if (dubs && lang.isNotEmpty() && lang != want) Rank.DUB else Rank.NONE
    }

    /**
     * What [name] says about its audio for a viewer whose language is [want]: DUB when it says it carries [want] (DUAL
     * AUDIO, ENG DUB, DUBBED, MULTI with ENG, a bare ENG), ORIGINAL when it says subtitles or only the original language
     * (SubsPlease, "ENG SUB", VOSTFR, JAPANESE), else NONE. A language next to a subtitle word is about subtitles.
     */
    fun audio(name: String, want: String): Audio {
        if (want == "ja") return Audio.NONE
        val t = tokens(name)
        fun at(i: Int) = t.getOrNull(i)
        fun subNear(i: Int) = at(i + 1)?.let { it in SUBS } == true || at(i - 1)?.let { it in SUBS } == true
        val subWord = t.any { it in SUBS } || t.any { it in SUB_GROUPS }
        val langs = HashSet<String>()
        var multi = false
        for ((i, w) in t.withIndex()) {
            if (w in setOf("MULTI", "MULTIAUDIO", "MULTILANG")) { if (!subNear(i)) multi = true; continue }
            if (subNear(i)) continue
            ReleaseLanguage.tag(w)?.let { langs += it }
        }
        val dual = "DUALAUDIO" in t || "DUAL" in t
        val dubWord = t.any { it in DUB_WORDS }
        val engDub = "ENGDUB" in t || "ENGDUBBED" in t
        val says = (want == "en" && (engDub || dual)) || want in langs ||
            (want == "en" && dubWord && langs.all { it == "en" || it == "ja" })
        if (says) return Audio.DUB
        return if (subWord || (langs == setOf("ja") && !multi && !dubWord)) Audio.ORIGINAL else Audio.NONE
    }

    /**
     * The source list's audio badge: "English Dub", "Dual Audio", "Multi Audio", "Subtitled", or for anime the one
     * language it names ("Japanese Audio"); null when it says none.
     */
    fun badge(name: String, want: String, anime: Boolean): String? {
        val t = tokens(name)
        val a = audio(name, want)
        if (anime && a == Audio.DUB) return "${ReleaseLanguage.name(want)} Dub"
        if ("DUALAUDIO" in t || "DUAL" in t) return "Dual Audio"
        val mi = t.indexOf("MULTI")
        if (mi >= 0 && t.getOrNull(mi + 1)?.let { it in SUBS } != true) return "Multi Audio"
        if ("MULTIAUDIO" in t) return "Multi Audio"
        if (a == Audio.ORIGINAL && (t.any { it in SUBS } || t.any { it in SUB_GROUPS })) return "Subtitled"
        if (!anime) return null
        val named = HashSet<String>()
        for ((i, w) in t.withIndex()) {
            val near = t.getOrNull(i + 1)?.let { it in SUBS } == true || (i > 0 && t[i - 1] in SUBS)
            if (!near) ReleaseLanguage.tag(w)?.let { named += it }
        }
        val l = named.singleOrNull() ?: return null
        return if (l == want) null else "${ReleaseLanguage.name(l)} Audio"
    }
}
