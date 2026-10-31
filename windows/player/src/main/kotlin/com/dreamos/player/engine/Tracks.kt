package com.dreamos.player.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/** One audio, subtitle or video track as Dream Player names it (fire-home ExoEngine track labels). */
data class Track(
    val id: Int,
    val type: Type,
    /** "English", "Japanese (Director's Cut)", "English  ·  SDH". */
    val title: String,
    /** "Dolby Digital 5.1", "AAC Stereo"; "Commentary"; empty for subtitles. */
    val detail: String,
    val lang: String?,
    val selected: Boolean,
    val forced: Boolean = false,
    val external: Boolean = false,
    val codec: String = "",
    val channels: Int = 0,
    /** Video only. */
    val width: Int = 0,
    val height: Int = 0,
    val dvProfile: Int? = null,
    val supported: Boolean = true,
) {
    enum class Type { VIDEO, AUDIO, SUB }
}

internal object Tracks {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(trackListJson: String?): List<Track> {
        if (trackListJson.isNullOrBlank()) return emptyList()
        val arr = runCatching { json.parseToJsonElement(trackListJson) as JsonArray }.getOrNull() ?: return emptyList()
        val raw = arr.mapNotNull { (it as? JsonObject)?.let(::one) }
        // Duplicate labels get " (1)", " (2)" so every row can be told apart.
        val out = ArrayList<Track>(raw.size)
        for (type in Track.Type.entries) {
            val group = raw.filter { it.type == type }
            val counts = group.groupingBy { it.title + "|" + it.detail }.eachCount()
            val seen = HashMap<String, Int>()
            for (t in group) {
                val k = t.title + "|" + t.detail
                if ((counts[k] ?: 0) > 1) {
                    val n = (seen[k] ?: 0) + 1
                    seen[k] = n
                    out += t.copy(title = "${t.title} ($n)")
                } else out += t
            }
        }
        return out
    }

    /** mpv's chapter-list JSON: `[{"title": "Opening", "time": 92.5}, ...]`, in time order. */
    fun chapters(json: String?): List<Chapter> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = runCatching { this.json.parseToJsonElement(json) as JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val t = o["time"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            Chapter((t * 1000).toLong(), o.str("title").orEmpty())
        }.sortedBy { it.startMs }
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.let { if (it.isString) it.content else null }
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull ?: this[k]?.jsonPrimitive?.doubleOrNull?.toInt()
    private fun JsonObject.flag(k: String) = this[k]?.jsonPrimitive?.booleanOrNull == true

    private fun one(o: JsonObject): Track? {
        val type = when (o.str("type")) { "video" -> Track.Type.VIDEO; "audio" -> Track.Type.AUDIO; "sub" -> Track.Type.SUB; else -> return null }
        val id = o.int("id") ?: return null
        val lang = normLang(o.str("lang"))
        val external = o.flag("external")
        // An added file without a title is named by its file ("176fd7da.srt"): never shown.
        val name = o.str("title")?.trim().orEmpty().let { if (external && FILE_NAME.containsMatchIn(it)) "" else it }
        val codec = o.str("codec").orEmpty()
        val profile = o.str("codec-profile").orEmpty()
        val channels = o.int("demux-channel-count") ?: 0
        val forced = o.flag("forced")
        val langName = langName(lang)
        return when (type) {
            Track.Type.AUDIO -> {
                val extra = name.takeIf { it.isNotEmpty() && !it.equals(langName, true) && !isTechnical(it) }
                val title = langName + (extra?.let { " ($it)" } ?: "")
                val roles = buildList {
                    if (o.flag("visual-impaired") || name.contains("description", true)) add("Audio Description")
                    if (name.contains("commentary", true)) add("Commentary")
                }
                val detail = (listOf(listOf(codecLong(codec, profile, name), channelsLong(channels)).filter { it.isNotEmpty() }.joinToString(" ")) + roles)
                    .filter { it.isNotEmpty() }.joinToString("  ·  ")
                Track(id, type, title, detail, lang, o.flag("selected"), forced, external, codec, channels)
            }
            Track.Type.SUB -> {
                val tags = buildList {
                    if (forced) add("Forced")
                    if (o.flag("hearing-impaired") || Regex("\\b(SDH|HI|CC)\\b").containsMatchIn(name)) add("SDH")
                    // Dream Subtitles' downloads (the only files Dream Player adds): named as Settings names them.
                    if (external) add("Downloaded")
                    if (name.isNotEmpty() && !name.equals(langName, true) && !Regex("^(SDH|HI|CC|Forced|Full)$", RegexOption.IGNORE_CASE).matches(name)) add(name)
                }
                val textual = codec !in setOf("hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "xsub")
                Track(id, type, (listOf(langName) + tags).joinToString("  ·  "), if (textual) "" else "Image Subtitles", lang, o.flag("selected"), forced, external, codec)
            }
            Track.Type.VIDEO -> Track(
                id, type, name, codecVideo(codec), lang, o.flag("selected"), codec = codec,
                width = o.int("demux-w") ?: 0, height = o.int("demux-h") ?: 0,
                dvProfile = o.int("dolby-vision-profile"),
            )
        }
    }

    private val FILE_NAME = Regex("(?i)\\.(srt|ass|ssa|vtt|sub|idx|sup)$")

    private fun isTechnical(s: String) = Regex("(?i)\\b(ac3|e-?ac-?3|eac3|dts|truehd|aac|flac|atmos|ddp?|\\d\\.\\d|stereo|mono|kbps|surround)\\b").containsMatchIn(s)

    /**
     * The language as core keeps it (ContentPrefs.normLang: ISO 639-1, "eng" / "en-US" -> "en"), so the player's
     * choices, core's audio language and the language pills compare equal; null for und / unknown / zxx / mis / mul.
     * OpenSubtitles' "pob" (Brazilian Portuguese) is Portuguese here.
     */
    fun normLang(code: String?): String? {
        val c = code?.trim()?.lowercase()?.split('-', '_')?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        if (c in setOf("und", "unknown", "zxx", "mis", "mul")) return null
        if (c == "pob") return "pt"
        return com.dreamos.desktop.core.content.ContentPrefs.normLang(BIBLIO[c] ?: c)
    }

    /** ISO 639-2 (three letters) to ISO 639-1, from the JDK's own list; Locale names only the two-letter codes. */
    private val TWO: Map<String, String> by lazy {
        Locale.getISOLanguages().associateBy { runCatching { Locale(it).isO3Language }.getOrDefault(it) }
    }

    fun langName(code: String?): String {
        if (code == null) return "Unknown Language"
        val two = if (code.length == 3) TWO[code] ?: code else code
        val n = Locale(two).getDisplayLanguage(Locale.ENGLISH)
        return if (n.isBlank() || n.equals(code, true)) code.uppercase() else n
    }

    /** ISO 639-2/B codes that Locale doesn't know, to their 639-2/T twins. */
    private val BIBLIO = mapOf(
        "fre" to "fra", "ger" to "deu", "dut" to "nld", "chi" to "zho", "cze" to "ces", "gre" to "ell",
        "per" to "fas", "rum" to "ron", "slo" to "slk", "ice" to "isl", "arm" to "hye", "baq" to "eus",
        "bur" to "mya", "geo" to "kat", "mac" to "mkd", "mao" to "mri", "may" to "msa", "tib" to "bod",
        "wel" to "cym", "alb" to "sqi", "pob" to "por",
    )

    fun codecLong(codec: String, profile: String, name: String): String = when {
        codec == "eac3" && (profile.contains("JOC", true) || name.contains("atmos", true)) -> "Dolby Atmos"
        codec == "truehd" && name.contains("atmos", true) -> "Dolby Atmos"
        codec == "ac3" -> "Dolby Digital"
        codec == "eac3" -> "Dolby Digital Plus"
        codec == "ac4" -> "Dolby AC-4"
        codec == "truehd" || codec == "mlp" -> "Dolby TrueHD"
        codec == "dts" && profile.contains("DTS:X", true) -> "DTS:X"
        codec == "dts" && (profile.contains("MA", true) || profile.contains("HRA", true) || profile.contains("HD", true)) -> "DTS-HD"
        codec == "dts" && profile.contains("Express", true) -> "DTS Express"
        codec == "dts" -> "DTS"
        codec == "aac" -> "AAC"
        codec == "flac" -> "FLAC"
        codec == "opus" -> "Opus"
        codec == "vorbis" -> "Vorbis"
        codec == "mp3" -> "MP3"
        codec.startsWith("pcm") -> "PCM"
        else -> codec.uppercase()
    }

    fun codecShort(codec: String, profile: String, name: String): String = when (val l = codecLong(codec, profile, name)) {
        "Dolby Digital" -> "AC3"
        "Dolby Digital Plus" -> "E-AC3"
        "Dolby Atmos" -> if (codec == "truehd") "TrueHD Atmos" else "E-AC3 Atmos"
        "Dolby TrueHD" -> "TrueHD"
        else -> l
    }

    fun channelsLong(n: Int): String = when (n) {
        0 -> ""
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$n Channels"
    }

    fun codecVideo(codec: String): String = when (codec) {
        "hevc" -> "HEVC"
        "h264" -> "H.264"
        "vp9" -> "VP9"
        "av1" -> "AV1"
        "mpeg2video" -> "MPEG-2"
        else -> codec.uppercase()
    }
}
