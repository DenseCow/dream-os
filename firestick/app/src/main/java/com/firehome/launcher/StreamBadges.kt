package com.firehome.launcher

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import java.util.Locale

/**
 * What's actually playing, as small badges: "4K", "Dolby Vision", "Dolby Atmos", "WEB-DL" (and the file size as
 * quiet text on the Pause Screen). Picture and sound come from the formats ExoPlayer is decoding (the selected
 * tracks until the decoders have them); the source type from the release name Dream Core picked (each [Found]
 * notes its name and size here by URL, whichever path then opens it), else the stream URL's own file name.
 * Recomputed only when a format changes ([ExoEngine.onFormat]); the views are rebuilt only when the text does.
 */
object StreamBadges {
    class Badge(val text: String, val warn: Boolean = false)

    class Facts(val badges: List<Badge>, val size: String?) {
        val summary: String get() = badges.joinToString(" · ") { it.text }
    }

    private class Release(val name: String, val size: Long)

    /** URL -> release (the last few dozen links Dream Core found). */
    private val known = object : LinkedHashMap<String, Release>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Release>?) = size > 48
    }

    fun note(url: String, name: String, sizeBytes: Long) {
        synchronized(known) { known[url] = Release(name, sizeBytes) }
    }

    /** The badges for what [exo] plays now; null before any format is known. */
    fun facts(exo: ExoEngine): Facts? {
        val p = exo.player ?: return null
        val url = exo.url
        val rel = synchronized(known) { known[url] }
        val name = rel?.name?.takeIf { it.isNotBlank() } ?: fileName(url)
        val tags = name?.let { " " + stripExt(it).uppercase(Locale.US).replace(NON_WORD, " ") + " " }.orEmpty()
        val v = p.videoFormat ?: selected(p, C.TRACK_TYPE_VIDEO)
        val a = p.audioFormat ?: selected(p, C.TRACK_TYPE_AUDIO)
        val out = ArrayList<Badge>()
        resolution(v, p)?.let { out.add(Badge(it)) }
        if (out.isEmpty() && a == null) return null
        hdr(v, tags)?.let { out.add(Badge(it)) }
        audio(a, tags)?.let { out.add(Badge(it)) }
        source(tags)?.let { out.add(Badge(it, warn = it == "CAM")) }
        return Facts(out, rel?.size?.takeIf { it > 0 }?.let { sizeText(it) })
    }

    private val NON_WORD = Regex("[^A-Z0-9+]+")

    private fun selected(p: Player, type: Int): Format? {
        for (g in p.currentTracks.groups) if (g.type == type) for (i in 0 until g.length) if (g.isTrackSelected(i)) return g.getTrackFormat(i)
        return null
    }

    private fun resolution(v: Format?, p: Player): String? {
        var w = v?.width ?: Format.NO_VALUE
        var h = v?.height ?: Format.NO_VALUE
        if (h <= 0) { w = p.videoSize.width; h = p.videoSize.height }
        if (h <= 0) return null
        return when {
            h >= 2000 || w >= 3200 -> "4K"
            h >= 1000 || w >= 1800 -> "1080p"
            h >= 700 || w >= 1200 -> "720p"
            else -> "SD"
        }
    }

    private fun hdr(v: Format?, tags: String): String? {
        if (v != null) {
            val c = v.codecs.orEmpty()
            val t = v.colorInfo?.colorTransfer
            when {
                v.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || c.startsWith("dvh") || c.startsWith("dav1") -> return "Dolby Vision"
                t == C.COLOR_TRANSFER_ST2084 -> return if (has(tags, "HDR10+", "HDR10PLUS")) "HDR10+" else "HDR10"
                t == C.COLOR_TRANSFER_HLG -> return "HLG"
                t == C.COLOR_TRANSFER_SDR -> return null // the stream says SDR
            }
        }
        // The container didn't say: the release name does.
        return when {
            has(tags, "DV", "DOVI", "DOLBY VISION") -> "Dolby Vision"
            has(tags, "HDR10+", "HDR10PLUS") -> "HDR10+"
            has(tags, "HDR", "HDR10") -> "HDR10"
            has(tags, "HLG") -> "HLG"
            else -> null
        }
    }

    private fun audio(a: Format?, tags: String): String? {
        a ?: return null
        val ch = ExoEngine.channelsLong(a.channelCount)
        // Matroska doesn't flag Atmos (E-AC3 JOC / TrueHD): the release name does.
        val atmos = has(tags, "ATMOS")
        fun with(name: String) = listOfNotNull(name, ch).joinToString(" ")
        return when (a.sampleMimeType) {
            MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
            MimeTypes.AUDIO_E_AC3 -> if (atmos) "Dolby Atmos" else with("Dolby Digital Plus")
            MimeTypes.AUDIO_TRUEHD -> if (atmos) "Dolby Atmos" else with("Dolby TrueHD")
            MimeTypes.AUDIO_AC3 -> with("Dolby Digital")
            MimeTypes.AUDIO_AC4 -> with("Dolby AC-4")
            MimeTypes.AUDIO_DTS_X -> "DTS:X"
            MimeTypes.AUDIO_DTS_HD -> with("DTS-HD")
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_EXPRESS -> with("DTS")
            else -> ch
        }
    }

    private fun source(tags: String): String? = when {
        tags.isBlank() -> null
        has(tags, "REMUX", "BDREMUX") -> "Remux"
        has(tags, "BLURAY", "BLU RAY", "BDRIP", "BRRIP", "BD25", "BD50") -> "BluRay"
        has(tags, "WEBRIP", "WEB RIP") -> "WEBRip"
        has(tags, "WEB DL", "WEBDL", "WEB") -> "WEB-DL"
        has(tags, "HDTV") -> "HDTV"
        has(tags, *CAM_TAGS) -> "CAM"
        has(tags, "DVDRIP", "DVD") -> "DVD"
        else -> null
    }

    private fun has(tags: String, vararg words: String) = words.any { tags.contains(" $it ") }

    /** The resolver's CAM words ([NativeResolver.CAM_WORDS]) spelled as these tags are: "PRE-DVD" reads "PRE DVD" here. */
    private val CAM_TAGS by lazy { NativeResolver.CAM_WORDS.map { it.replace(NON_WORD, " ") }.distinct().toTypedArray() }

    /** The URL's last path segment when it is a video file's name ("Movie.2024.2160p.WEB-DL....mkv"). */
    private fun fileName(url: String): String? = try {
        Uri.parse(url).lastPathSegment?.takeIf { isVideo(it) }
    } catch (e: Exception) { null }

    private val VIDEO_EXT = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts")
    private fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase(Locale.US) in VIDEO_EXT
    /** "Name.2160p.WEB-DL.mkv" -> "Name.2160p.WEB-DL" (a torrent name without an extension keeps its last tag). */
    private fun stripExt(name: String) = if (isVideo(name)) name.substringBeforeLast('.') else name

    private fun sizeText(b: Long): String {
        val gb = b / 1_073_741_824.0
        return if (gb >= 1) String.format(Locale.US, "%.1f GB", gb) else "${b shr 20} MB"
    }

    // ---- views ----

    /** An empty badge row ([fill] fills it); gone until there's something to say. */
    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
    }

    /**
     * Dark-glass capsules with a hairline, pearl text (CAM in the amber of "CAM Only"); [compact]: the controls' smaller
     * size; [withSize]: the file size after them as quiet text.
     */
    fun fill(row: LinearLayout, kit: PremiumKit, f: Facts?, compact: Boolean, withSize: Boolean) {
        row.removeAllViews()
        if (f == null || f.badges.isEmpty()) { row.visibility = View.GONE; return }
        val ctx = row.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val glass = Color.argb(150, 14, 14, 16)
        for ((i, b) in f.badges.withIndex()) {
            val ink = if (b.warn) AMBER_TEXT else Ui.alpha(kit.text, 0.92f)
            val tv = kit.label(b.text, if (compact) 11.5f else Ui.T_CAPTION, ink, kit.semibold, 0.02f).apply {
                background = if (b.warn) Ui.rounded(ctx, Ui.mix(glass, AMBER, 0.16f), Ui.R_PILL, 1f, Ui.alpha(AMBER, 0.6f))
                    else Ui.rounded(ctx, glass, Ui.R_PILL, 1f, Ui.alpha(kit.text, 0.22f))
                val h = dp(if (compact) 9 else 11)
                val v = dp(if (compact) 4 else 5)
                setPadding(h, v, h, v)
            }
            row.addView(tv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (i > 0) marginStart = dp(if (compact) 6 else 8) })
        }
        if (withSize && f.size != null) {
            val s = kit.label(f.size, Ui.T_CAPTION, Ui.alpha(kit.text, 0.56f), kit.medium).apply { fontFeatureSettings = "tnum" }
            row.addView(s, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(14) })
        }
        row.visibility = View.VISIBLE
    }

    private const val AMBER = 0xFFFFD60A.toInt()
    private const val AMBER_TEXT = 0xFFFFE266.toInt()
}
