package com.dreamos.desktop.core.playback

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Release-name words shared by Dream Core's pick, the availability pills and the source badges (fire-home
 * NativeResolver CAM_WORDS / JUNK_WORDS, iPhone ReleaseWords.swift), so a CAM can't count as a real copy in one place
 * and not another. Every check is a whole token in the upper-cased name; a space in a word matches any separator.
 */
object ReleaseWords {
    /**
     * Camera, telesync, telecine and screener releases, whatever the name calls them. TamilMV's "HQ PreDVD" and
     * "HQ Pre" are theater recordings sold as 1080p; "KinoRip" is the Czech word for the same.
     */
    val CAM = listOf("CAM", "HDCAM", "HQCAM", "CAMRIP", "CAM-RIP", "TS", "HDTS", "TSRIP", "HDTSRIP", "TELESYNC",
        "TC", "HDTC", "TELECINE", "SCR", "DVDSCR", "HDTSCR", "SCREENER", "PDVD", "PREDVD", "PRE-DVD", "PREDVDRIP", "HQ PRE", "KINORIP")

    /** Not the movie at all: recap and review videos, betting-site ads posing as rips. */
    val JUNK = listOf("RECAP", "REVIEW", "1XBET", "4RABET", "4RABETRIP", "FLIXMOMO")

    /** The label a CAM copy gets on the source list and the loading note, most specific first. */
    private val CAM_LABELS = listOf("HDCAM", "CAMRIP", "CAM", "HDTS", "TELESYNC", "TS", "HDTC", "TELECINE", "TC", "DVDSCR",
        "SCREENER", "SCR", "PDVD", "PREDVD", "PRE-DVD", "HQ PRE", "KINORIP")

    private val COLLECTION = listOf("TRILOGY", "DUOLOGY", "QUADRILOGY", "PENTALOGY", "HEXALOGY", "COLLECTION", "SAGA", "BOXSET", "ANTHOLOGY", "COMPLETE")
    private val DISC_EXT = Regex("(?i)\\.(iso|img|rar|zip|7z|exe)$")
    private val CHANNELS = Regex("(?<![0-9])([57])[ .]1(?![0-9])")
    private val CYRILLIC = Regex("[\\u0400-\\u04FF]")

    private val compiled = ConcurrentHashMap<List<String>, Regex>()

    /** Any of [words] as a whole token in [text] (any case); "HQ PRE" also matches "HQ.Pre", "HQ_PRE". */
    fun has(text: String, words: List<String>): Boolean {
        if (words.isEmpty()) return false
        val re = compiled.getOrPut(words) {
            val alts = words.joinToString("|") { w -> w.split(' ').joinToString("[ ._-]") { Regex.escape(it) } }
            Regex("(?<![A-Z0-9])(?:$alts)(?![A-Z0-9])")
        }
        return re.containsMatchIn(text.uppercase(Locale.US))
    }

    fun has(text: String, vararg words: String) = has(text, words.toList())

    /** A video extension at the end goes first: "Movie.ts" is a container, not a telesync. */
    private fun strip(name: String): String {
        val l = name.lowercase(Locale.US)
        for (ext in listOf(".ts", ".mkv", ".mp4", ".m4v", ".avi", ".mov", ".webm")) if (l.endsWith(ext)) return name.dropLast(ext.length)
        return name
    }

    /** A camera or screener copy, by its torrent / file name (whole words; a title word like "CAMERA" isn't). */
    fun isCam(name: String) = has(strip(name), CAM)

    /** A recap, review or betting-site video: never a source. */
    fun isJunk(name: String) = has(strip(name), JUNK)

    /** "HDCAM", "TS", "HQ PRE"... for a CAM copy; "CAM" when none is named. */
    fun camLabel(name: String): String { val t = strip(name); return CAM_LABELS.firstOrNull { has(t, it) } ?: "CAM" }

    /** 8K / 4320p: never picked (no PC GPU decodes it in time; the name parse would otherwise call it SD). */
    fun is8k(t: String) = has(t, "4320P", "8K", "FUHD")

    fun hdr(t: String) = has(t, "HDR", "HDR10", "HDR10+", "DV", "DOVI", "DOLBY VISION", "HLG")
    fun hdr10(t: String) = has(t, "HDR", "HDR10", "HDR10+")
    fun dv(t: String) = has(t, "DV", "DOVI", "DOLBY VISION")
    /** Dolby Vision with no HDR10 named: usually profile 5 (no standard HDR picture underneath). */
    fun dvOnly(t: String) = dv(t) && !hdr10(t)
    fun hevc(t: String) = has(t, "HEVC", "X265", "H265", "H.265")
    fun av1(t: String) = has(t, "AV1")
    fun avc(t: String) = has(t, "AVC", "X264", "H264", "H.264")
    fun threeD(t: String) = has(t, "3D")
    fun upscaled(t: String) = has(t, "UPSCALE", "UPSCALED", "AI UPSCALED", "AI-UPSCALED", "AIUS")
    fun collection(t: String) = has(t, COLLECTION)
    fun disc(name: String) = DISC_EXT.containsMatchIn(name) || has(name, "ISO", "BDMV", "BD25", "BD50", "BD66", "BD100")

    /** A dubbed or foreign-audio release (the clean-release rule ranks it after an undubbed one). */
    fun dubbed(name: String) = CYRILLIC.containsMatchIn(name) ||
        has(name, "DUB", "DUBBED", "DUBLADO", "MULTI", "DUAL", "TRUEFRENCH", "FRENCH", "ITA", "LATINO")

    /** The quality from the provider's label, else the name; [cam] true for a CAM / TS / SCR label. */
    fun quality(providerLabel: String?, name: String): Pair<Quality, Boolean> {
        if (is8k(name)) return Quality.UHD_8K to false
        return when (providerLabel?.uppercase(Locale.US)) {
            "4K", "2160P" -> Quality.UHD_4K to false
            "1080P" -> Quality.FHD_1080 to false
            "720P" -> Quality.HD_720 to false
            "CAM", "SCR", "TELESYNC", "TS" -> Quality.SD to true
            else -> when {
                has(name, "2160P", "4K", "UHD") -> Quality.UHD_4K
                has(name, "1080P") -> Quality.FHD_1080
                has(name, "720P") -> Quality.HD_720
                else -> Quality.SD
            } to false
        }
    }

    /** "Remux", "BluRay", "WEB-DL"... the release's source; null when it names none. */
    fun sourceLabel(t: String): String? = when {
        has(t, "REMUX") -> "Remux"
        has(t, "BLURAY", "BLU-RAY", "BDRIP", "BRRIP") -> "BluRay"
        has(t, "WEB-DL", "WEBDL") -> "WEB-DL"
        has(t, "WEBRIP", "WEB-RIP") -> "WEBRip"
        has(t, "WEB") -> "WEB"
        has(t, "HDTV") -> "HDTV"
        has(t, "DVDRIP") -> "DVDRip"
        else -> null
    }

    /** Picture and sound badges the name carries: "Dolby Vision" / "HDR10+" / "HDR", "HEVC" / "AV1", "Remux", "Atmos" / "5.1". */
    fun tags(name: String): List<String> {
        val t = name.uppercase(Locale.US)
        return listOfNotNull(
            when { dv(t) -> "Dolby Vision"; has(t, "HDR10+") -> "HDR10+"; hdr10(t) -> "HDR"; else -> null },
            when { hevc(t) -> "HEVC"; av1(t) -> "AV1"; else -> null },
            if (has(t, "REMUX")) "Remux" else null,
            if (has(t, "ATMOS")) "Atmos" else CHANNELS.find(t)?.let { "${it.groupValues[1]}.1" },
        )
    }

    /** An MP4 (or M4V) by what's known up front: the file or torrent name, an "MP4" token, or YTS (always MP4). */
    fun mp4(name: String, fileName: String?, provider: String?): Boolean {
        val n = (fileName ?: name).lowercase(Locale.US)
        val prov = provider?.substringAfter(':')?.lowercase(Locale.US).orEmpty()
        return n.endsWith(".mp4") || n.endsWith(".m4v") || has(name, "MP4") || prov.startsWith("yts") || has(name, "YTS", "YTS.MX", "YIFY")
    }
}

/** Languages a release name says ("ITA", "Latino", "MULTI"): fire-home AudioLanguages, iPhone ReleaseLanguage. */
object ReleaseLanguage {
    const val MULTI = "multi"

    private val TAGS: Map<String, String> = HashMap<String, String>().apply {
        fun put(code: String, vararg t: String) { for (x in t) this[x] = code }
        put("en", "ENG", "ENGLISH")
        put("it", "ITA", "ITALIAN", "ITALIANO")
        put("es", "SPA", "SPANISH", "ESP", "ESPANOL", "CASTELLANO", "LATINO", "LATAM", "LATINOAMERICANO")
        put("fr", "FRE", "FRA", "FRENCH", "TRUEFRENCH", "VFF", "VFI", "VF", "VF2", "VFQ", "QUEBEC")
        put("de", "GER", "GERMAN", "DEU", "DEUTSCH")
        put("pt", "POR", "PORTUGUESE", "PORTUGUES", "PTBR", "BRAZILIAN", "DUBLADO")
        put("ru", "RUS", "RUSSIAN")
        put("uk", "UKR", "UKRAINIAN")
        put("pl", "POL", "POLISH", "PLDUB", "LEKTOR")
        put("nl", "DUTCH", "NLD")
        put("sv", "SWE", "SWEDISH")
        put("no", "NOR", "NORWEGIAN")
        put("da", "DAN", "DANISH")
        put("fi", "FIN", "FINNISH")
        put("cs", "CZE", "CZECH")
        put("hu", "HUN", "HUNGARIAN")
        put("ro", "RUM", "ROMANIAN")
        put("el", "GREEK")
        put("tr", "TUR", "TURKISH")
        put("ar", "ARABIC")
        put("he", "HEB", "HEBREW")
        put("hi", "HIN", "HINDI")
        put("ta", "TAMIL")
        put("te", "TELUGU")
        put("ml", "MALAYALAM")
        put("kn", "KANNADA")
        put("bn", "BENGALI")
        put("ja", "JPN", "JAPANESE")
        put("ko", "KOR", "KOREAN")
        put("zh", "CHINESE", "MANDARIN", "CANTONESE")
        put("th", "THAI")
        put("vi", "VIETNAMESE")
        put(MULTI, "MULTI", "MULTILANG", "MULTIAUDIO", "DUAL", "DUALAUDIO")
    }

    /** English language names to 2-letter codes ("italian" -> "it"). */
    private val NAMES: Map<String, String> by lazy {
        Locale.getISOLanguages().associateBy { Locale(it).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.US) }
    }

    private val SPLIT = Regex("[^A-Z0-9+]+")

    fun tokens(name: String) = name.uppercase(Locale.US).split(SPLIT).filter { it.isNotEmpty() }

    /** The language one release-name token names ("ENG" -> "en"); MULTI and DUAL name none. */
    fun tag(token: String): String? = TAGS[token.uppercase(Locale.US)]?.takeIf { it != MULTI }

    /** A language from a release name ("Italiano", "ENG 5.1", "Castellano"), or null. */
    fun from(name: String): String? {
        for (t in tokens(name)) {
            TAGS[t]?.let { if (it != MULTI) return it }
            if (t.length > 3) NAMES[t.lowercase(Locale.US)]?.let { return it }
        }
        return null
    }

    /** "eng" / "en-US" / "pt_BR" -> "en" / "pt"; null for und / unknown. */
    fun norm(l: String?): String? {
        var p = l?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return null
        p = p.split('-', '_').first()
        if (p in setOf("", "und", "unknown", "zxx", "mis")) return null
        if (p.length == 3) {
            val biblio = mapOf("ger" to "de", "fre" to "fr", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el", "per" to "fa",
                "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "geo" to "ka", "ice" to "is",
                "mac" to "mk", "may" to "ms", "wel" to "cy", "tib" to "bo")
            biblio[p]?.let { return it }
            Locale.getISOLanguages().firstOrNull { Locale(it).isO3Language == p }?.let { return it }
        }
        return p
    }

    /** "English" for "en". */
    fun name(code: String?): String {
        if (code.isNullOrEmpty()) return "Unknown"
        val n = Locale(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty { code }
        return n.replaceFirstChar { it.titlecase(Locale.US) }
    }
}
