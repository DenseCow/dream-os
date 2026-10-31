package com.dreamos.desktop.core.playback

import java.util.Locale

/** Release-name checks for Choose a Source (fire-home SourcePicker, iPhone SourceNames): another episode, duplicates. */
object SourceNames {
    enum class Match { OK, DOUBT, OTHER_EPISODE, OTHER_TITLE }

    private val STOP = setOf("the", "a", "an", "and", "of", "in", "on", "to")
    private fun norm(s: String) = s.lowercase(Locale.US).replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ").trim()
    private fun words(s: String) = norm(s).split(' ').filter { it.isNotEmpty() && it !in STOP }
    private val YEARS = Regex("""(?<![0-9])(19\d{2}|20\d{2})(?![0-9])""")
    private fun yearsIn(s: String) = YEARS.findAll(s).map { it.value.toInt() }.toList()

    /**
     * S01E02, with an optional second episode that needs a dash or another "e" (S01E01-03, S01E01-E03, S01E01E02):
     * "S01E02.1080p" is episode 2, not episodes 2 to 108 (the Other Episode fix, fire-home SourcePicker).
     */
    private val SXE = Regex("""(?i)(?<![a-z0-9])s(\d{1,2})[ ._-]*e(\d{1,3})(?:(?:[ ._]*-[ ._]*e?|[ ._-]*e)(\d{1,3})(?![0-9]))?""")
    private val NXN = Regex("""(?i)(?<![0-9])(\d{1,2})x(\d{2,3})(?![0-9])""")
    private val SEASON = Regex("""(?i)(?<![a-z0-9])(?:s|season[ ._-]*)(\d{1,2})(?![0-9e])""")

    /** Whether release [name] looks like [t]: its S/E (or year) and its title words. */
    fun match(name: String, t: PlayTarget): Match {
        val title = words(t.title)
        val nameWords = words(name).toSet()
        val have = if (title.isEmpty()) 1f else title.count { it in nameWords } / title.size.toFloat()
        if (t.isEpisode && t.season != null && t.episode != null) {
            val se = SXE.find(name) ?: NXN.find(name)
            if (se != null) {
                val s = se.groupValues[1].toInt()
                val e1 = se.groupValues[2].toInt()
                val e2 = se.groupValues.getOrNull(3)?.toIntOrNull()?.takeIf { it >= e1 } ?: e1
                if (s != t.season || t.episode !in e1..e2) return Match.OTHER_EPISODE
            } else {
                val season = SEASON.find(name)?.groupValues?.get(1)?.toIntOrNull()
                if (season != null && season != t.season) return Match.OTHER_EPISODE
            }
        } else if (t.year != null && t.year !in yearsIn(t.title)) {
            val y = yearsIn(name).firstOrNull()
            if (y != null && kotlin.math.abs(y - t.year) > 1 && have < 1f) return Match.OTHER_TITLE
        }
        return when { have == 0f -> Match.OTHER_TITLE; have < 1f -> Match.DOUBT; else -> Match.OK }
    }

    /** "Torrentio" from "cocoscrapers:torrentio". */
    fun provider(p: String) = p.substringAfterLast(':').replace('_', ' ').split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.US) } }

    /** One row per release (same hash, or the same release name), the best copy kept where it first appeared. */
    fun dedupe(list: List<SourceRow>): List<SourceRow> {
        fun name(r: SourceRow): String {
            val n = r.source.fileName?.let { DebridFiles.base(it) } ?: r.source.torrentName
            // Only a video extension goes: a torrent name's last dotted tag ("1080p.WEB" / "1080p.HDTS") is part of the release.
            val base = if (DebridFiles.isVideo(n)) n.substringBeforeLast('.') else n
            return norm(base).replace(" ", "")
        }
        fun better(a: SourceRow, b: SourceRow) = if (a.source.quality.rank != b.source.quality.rank) a.source.quality.rank < b.source.quality.rank
            else a.source.sizeBytes > b.source.sizeBytes
        val out = ArrayList<Pair<SourceRow, String>>()
        for (s in list) {
            val ns = name(s)
            val i = out.indexOfFirst { it.first.source.hash == s.source.hash || (ns.length >= 8 && it.second == ns) }
            if (i < 0) out += s to ns else if (better(s, out[i].first)) out[i] = s to ns
        }
        return out.map { it.first }
    }
}

/**
 * The player's stream badges (fire-home StreamBadges): what plays now, from mpv's facts and the release name. CAM goes
 * first, in the amber of "CAM Only" ([Badge.warn]), so a camera copy is never mistaken for a real one.
 */
object StreamBadges {
    data class Badge(val text: String, val warn: Boolean = false)
    data class Facts(val badges: List<Badge>, val size: String?) { val summary get() = badges.joinToString(" · ") { it.text } }

    /** What mpv says about the video (any field may be unknown). [transfer]: "pq", "hlg", "bt.1886"...; [dolbyVision]: a DV stream. */
    data class Video(val width: Int = 0, val height: Int = 0, val transfer: String? = null, val dolbyVision: Boolean = false)
    /** What mpv says about the audio: codec ("eac3", "truehd", "dts", "aac"...), channel count, a DTS-HD / DTS:X profile. */
    data class Audio(val codec: String? = null, val channels: Int = 0, val profile: String? = null)

    private val NON_WORD = Regex("[^A-Z0-9+]+")
    private val VIDEO_EXT = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts")

    /** [badges] with any CAM badge moved to the front (the source list's rows too). */
    fun camFirst(badges: List<String>): List<String> {
        val cam = badges.filter { ReleaseWords.isCam(it) || it == "CAM" }
        return cam + (badges - cam.toSet())
    }

    /**
     * The badges for what plays: resolution, HDR kind, audio, source (CAM in amber, first). [releaseName] from the
     * Stream, else the URL's file name. Null before mpv knows any format.
     */
    fun facts(releaseName: String?, url: String?, sizeBytes: Long, video: Video?, audio: Audio?): Facts? {
        val name = releaseName?.takeIf { it.isNotBlank() } ?: url?.substringBefore('?')?.substringAfterLast('/')?.takeIf { isVideo(it) }
        val tags = name?.let { " " + stripExt(it).uppercase(Locale.US).replace(NON_WORD, " ") + " " }.orEmpty()
        val out = ArrayList<Badge>()
        val src = source(tags)
        if (src == "CAM") out += Badge("CAM", warn = true)
        resolution(video)?.let { out += Badge(it) }
        if (out.none { it.text != "CAM" } && audio == null) return if (out.isEmpty()) null else Facts(out, sizeText(sizeBytes))
        hdr(video, tags)?.let { out += Badge(it) }
        audioName(audio, tags)?.let { out += Badge(it) }
        if (src != null && src != "CAM") out += Badge(src)
        return Facts(out, sizeText(sizeBytes))
    }

    private fun resolution(v: Video?): String? {
        val w = v?.width ?: 0; val h = v?.height ?: 0
        if (h <= 0) return null
        return when { h >= 2000 || w >= 3200 -> "4K"; h >= 1000 || w >= 1800 -> "1080p"; h >= 700 || w >= 1200 -> "720p"; else -> "SD" }
    }

    private fun has(tags: String, vararg words: String) = words.any { tags.contains(" $it ") }

    private fun hdr(v: Video?, tags: String): String? {
        if (v != null) when {
            v.dolbyVision -> return "Dolby Vision"
            v.transfer == "pq" -> return if (has(tags, "HDR10+", "HDR10PLUS")) "HDR10+" else "HDR10"
            v.transfer == "hlg" -> return "HLG"
            v.transfer != null -> return null // the stream says SDR
        }
        return when {
            has(tags, "DV", "DOVI", "DOLBY VISION") -> "Dolby Vision"
            has(tags, "HDR10+", "HDR10PLUS") -> "HDR10+"
            has(tags, "HDR", "HDR10") -> "HDR10"
            has(tags, "HLG") -> "HLG"
            else -> null
        }
    }

    private fun channels(n: Int) = when (n) { 0 -> null; 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$n ch" }

    private fun audioName(a: Audio?, tags: String): String? {
        a ?: return null
        val ch = channels(a.channels)
        val atmos = has(tags, "ATMOS")
        fun with(name: String) = listOfNotNull(name, ch).joinToString(" ")
        return when (a.codec?.lowercase(Locale.US)) {
            "eac3" -> if (atmos) "Dolby Atmos" else with("Dolby Digital Plus")
            "truehd" -> if (atmos) "Dolby Atmos" else with("Dolby TrueHD")
            "ac3" -> with("Dolby Digital")
            "ac4" -> with("Dolby AC-4")
            "dts" -> when {
                a.profile?.contains("X", true) == true -> "DTS:X"
                a.profile?.contains("HD", true) == true -> with("DTS-HD")
                else -> with("DTS")
            }
            else -> ch
        }
    }

    private val CAM_TAGS by lazy { ReleaseWords.CAM.map { it.replace(NON_WORD, " ") }.distinct().toTypedArray() }

    private fun source(tags: String): String? = when {
        tags.isBlank() -> null
        has(tags, *CAM_TAGS) -> "CAM"
        has(tags, "REMUX", "BDREMUX") -> "Remux"
        has(tags, "BLURAY", "BLU RAY", "BDRIP", "BRRIP", "BD25", "BD50") -> "BluRay"
        has(tags, "WEBRIP", "WEB RIP") -> "WEBRip"
        has(tags, "WEB DL", "WEBDL", "WEB") -> "WEB-DL"
        has(tags, "HDTV") -> "HDTV"
        has(tags, "DVDRIP", "DVD") -> "DVD"
        else -> null
    }

    private fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase(Locale.US) in VIDEO_EXT
    private fun stripExt(name: String) = if (isVideo(name)) name.substringBeforeLast('.') else name

    fun sizeText(b: Long): String? {
        if (b <= 0) return null
        val gb = b / 1_073_741_824.0
        return if (gb >= 1) String.format(Locale.US, "%.1f GB", gb) else "${b shr 20} MB"
    }
}
