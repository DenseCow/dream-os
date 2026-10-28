package com.firehome.launcher

import java.util.Locale

/**
 * Audio track names in Dream Player's Audio & Subtitles: reads a language from a track's own name
 * ("Italiano 5.1", "ENG", "Latino") when the file only says "und", and tells a name that merely repeats
 * language / codec / channels ("English AC3 5.1") from one worth showing ("Director's Commentary").
 */
object AudioLanguages {
    /** Several languages in one file (MULTI, DUAL); the actual ones show once it plays. */
    const val MULTI = "multi"

    /** Release tags (upper case) to BCP-47 codes. Only tags that don't read as ordinary title words. */
    private val TAGS: Map<String, String> = HashMap<String, String>().apply {
        fun put(code: String, vararg tags: String) { for (t in tags) this[t] = code }
        put("en", "ENG", "ENGLISH")
        put("it", "ITA", "ITALIAN", "ITALIANO")
        put("es", "SPA", "SPANISH", "ESP", "ESPANOL", "CASTELLANO")
        put("es-419", "LATINO", "LATAM", "LATINOAMERICANO")
        put("fr", "FRE", "FRA", "FRENCH", "TRUEFRENCH", "VFF", "VFI", "VF", "VF2")
        put("fr-CA", "VFQ", "QUEBEC")
        put("de", "GER", "GERMAN", "DEU", "DEUTSCH")
        put("pt", "POR", "PORTUGUESE", "PORTUGUES")
        put("pt-BR", "PTBR", "BRAZILIAN", "DUBLADO")
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

    /** Track names that only repeat a language, codec or layout ("English", "AC3 5.1", "Stereo"). */
    private val PLAIN = setOf(
        "AC3", "EAC3", "E", "DD", "DDP", "DD+", "DOLBY", "DIGITAL", "PLUS", "ATMOS", "TRUEHD", "DTS", "HD", "MA", "X", "HRA", "ES",
        "AAC", "LC", "HE", "FLAC", "OPUS", "MP3", "PCM", "LPCM", "MONO", "STEREO", "SURROUND", "CH", "KBPS", "AUDIO", "TRACK",
        "ORIGINAL", "DEFAULT", "MAIN", "CORE",
    )

    private fun tokens(s: String) = s.uppercase(Locale.US).split(Regex("[^A-Z0-9+]+")).filter { it.isNotEmpty() }

    /** A language from an audio track's own name ("Italiano", "ENG 5.1", "Castellano"), or null. */
    fun fromTrackName(name: String?): String? {
        val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        for (t in tokens(n)) {
            TAGS[t]?.takeIf { it != MULTI }?.let { return PlayerPrefs.normLang(it) }
            if (t.length > 3) ExoEngine.languageCode(t.lowercase(Locale.US))?.takeIf { it.length == 2 }?.let { return it }
        }
        return null
    }

    /**
     * True when a track's own name says nothing beyond its language [lang], codec and channels
     * ("English", "ENG AC3 5.1"); a variant or role ("Latino", "Commentary", "Castellano") is worth showing.
     */
    fun isPlainTrackName(name: String, lang: String?): Boolean = tokens(name).all { t ->
        t in PLAIN || t.all { it.isDigit() } || t.matches(Regex("[0-9]+(CH|K|KBPS|BIT)")) ||
            (lang != null && (TAGS[t] == lang || (t.length > 3 && ExoEngine.languageCode(t.lowercase(Locale.US)) == lang)))
    }
}
