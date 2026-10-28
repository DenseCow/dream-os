package com.firehome.launcher

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.Locale

/**
 * The trailer's choices, kept free of Android so they run (and are tested) on a desktop JVM:
 * which of TMDB's videos to try in what order, and which of one video's streams to play.
 *
 * Plans, best first: a DASH ladder of the indexed video-only streams plus one audio track (full-speed
 * ranged fetches, adaptive), else the HLS master (H.264 variants only), else the best muxed MP4, else
 * DASH without audio. H.264 or VP9 only, 1080p class at most: AV1 is never picked (it restarts this stick).
 */
object TrailerPick {

    // ---- candidates ----

    /** One TMDB video. */
    class Candidate(
        val key: String, val type: String, val official: Boolean, val lang: String?,
        val size: Int, val published: String, val name: String,
    ) {
        override fun toString() = "$key ($type${if (official) ", official" else ""}${lang?.let { ", $it" } ?: ""})"
    }

    /**
     * Best first: official Trailers in [lang], official English Trailers, other Trailers, Teasers, Clips,
     * Featurettes, the rest. Red band cuts (usually age-gated) after the others of their tier; then the
     * language, official, 1080p and newest first.
     */
    fun order(all: List<Candidate>, lang: String): List<Candidate> {
        fun langRank(c: Candidate) = when (c.lang?.lowercase(Locale.ROOT)) { lang -> 0; "en" -> 1; null, "", "null" -> 2; else -> 3 }
        fun tier(c: Candidate) = when (c.type) {
            "Trailer" -> when {
                c.official && langRank(c) == 0 -> 0
                c.official && langRank(c) <= 1 -> 1
                else -> 2
            }
            "Teaser" -> 3
            "Clip" -> 4
            "Featurette" -> 5
            else -> 6
        }
        return all.distinctBy { it.key }.sortedWith(
            compareBy<Candidate> { tier(it) }
                .thenBy { if (it.name.contains("red band", true)) 1 else 0 }
                .thenBy { langRank(it) }
                .thenByDescending { if (it.official) 1 else 0 }
                .thenByDescending { if (it.size >= 1080) 1 else 0 }
                .thenByDescending { it.published }
        )
    }

    // ---- trying them ----

    /** A video that failed lately: skipped until [until]. [hard]: it can't work anonymously (age gate, removed, region). */
    class Fail(val until: Long, val hard: Boolean, val why: String)

    const val SOFT_FAIL_MS = 10 * 60_000L
    const val HARD_FAIL_MS = 6 * 60 * 60_000L

    /**
     * The first of [cands] (best first) that [attempt] turns into a plan; the failures go into [fails] (per
     * video, shared across titles). Videos that failed lately are skipped, except on a [retry], which tries the
     * ones that failed softly once nothing fresh is left. A network error or rate limit stops the round (the
     * rest would fail the same way). One [log] line per video.
     */
    fun firstPlayable(
        cands: List<Candidate>, fails: MutableMap<String, Fail>, now: Long, retry: Boolean, maxTries: Int,
        log: (String) -> Unit, attempt: (Candidate) -> Pair<Plan?, String>,
    ): Pair<Candidate, Plan>? {
        fun live(c: Candidate) = fails[c.key]?.takeIf { now < it.until }
        val fresh = cands.filter { live(it) == null }
        val again = if (retry) cands.filter { live(it)?.hard == false } else emptyList()
        for (c in cands) live(c)?.let { f -> if (!(retry && !f.hard)) log("$c: skipped, failed lately (${f.why})") }
        var tries = 0
        for (c in fresh + again) {
            if (tries >= maxTries) { log("$c: not tried, $maxTries tried already"); continue }
            tries++
            val t0 = System.nanoTime()
            fun ms() = (System.nanoTime() - t0) / 1_000_000
            try {
                val (plan, why) = attempt(c)
                if (plan == null) {
                    fails[c.key] = Fail(now + SOFT_FAIL_MS, false, why)
                    log("$c: skipped, $why (${ms()} ms)")
                    continue
                }
                fails.remove(c.key)
                log("$c: ${plan.label}${if (plan !is Plan.Dash || why != "dash") " (fallback: $why)" else ""} (${ms()} ms)")
                return c to plan
            } catch (e: Throwable) {
                val name = e.javaClass.simpleName
                val msg = clean(e.message)
                val hard = e is ContentNotAvailableException // age-gated, private, removed, region-locked
                fails[c.key] = Fail(now + if (hard) HARD_FAIL_MS else SOFT_FAIL_MS, hard, if (hard) msg.ifEmpty { name } else name)
                log("$c: skipped, $name: $msg (${ms()} ms)")
                if (name == "ReCaptchaException" || e is java.io.IOException) {
                    log("stopping this round: ${if (name == "ReCaptchaException") "rate limited" else "network error"}")
                    break
                }
            }
        }
        return null
    }

    /** An error message for the log: no URLs (stream links carry tokens), one line, short. */
    fun clean(msg: String?): String = msg.orEmpty().replace(Regex("https?://\\S+"), "<url>").replace('\n', ' ').take(160)

    // ---- streams ----

    /** One representation (one itag) of the DASH manifest. [cls] is its quality class (1920x800 is 1080). */
    class Rep(
        val itag: Int, val url: String, val mime: String, val codecs: String, val bitrate: Int,
        val width: Int, val height: Int, val fps: Int, val cls: Int,
        val initEnd: Int, val indexStart: Int, val indexEnd: Int,
        val sampleRate: Int = 0, val channels: Int = 0, val lang: String? = null,
    )

    sealed class Plan {
        /** For the log: what was chosen (never a URL). */
        abstract val label: String
        /** A stream URL carrying the links' expiry. */
        abstract val anyUrl: String

        class Dash(val video: List<Rep>, val audio: Rep?, val durationMs: Long, val note: String = "") : Plan() {
            override val label get() = "DASH " + video.joinToString { "${it.cls}p ${it.codecs}" } + " + " + (audio?.codecs ?: "no audio") + note
            override val anyUrl get() = video.first().url
            /** The manifest with video up to [maxHeight] (the ladder below it stays for slow networks). */
            fun manifest(maxHeight: Int): String = dash(video.filter { it.cls <= maxHeight }.ifEmpty { listOf(video.last()) }, audio, durationMs)
        }

        /** [master]: YouTube's HLS master playlist, cut to the variants this device plays. */
        class Hls(val masterUrl: String, val master: String, val variants: String) : Plan() {
            override val label get() = "HLS $variants"
            override val anyUrl get() = masterUrl
        }

        class Progressive(val url: String, val cls: Int, val codecs: String) : Plan() {
            override val label get() = "MP4 ${cls}p $codecs"
            override val anyUrl get() = url
        }
    }

    /** The quality class of a [w]x[h] picture: the height it would have at 16:9 (letterboxed scope is still 1080p). */
    fun pclass(w: Int, h: Int): Int {
        if (w <= 0 || h <= 0) return maxOf(w, h, 0)
        val long = maxOf(w, h)
        val short = minOf(w, h)
        val v = maxOf(short, (long * 9 + 8) / 16)
        return STD.firstOrNull { v <= it * 21 / 20 } ?: v
    }

    private val STD = intArrayOf(144, 240, 360, 480, 720, 1080, 1440, 2160, 4320)

    private fun isAvc(codec: String?) = codec.orEmpty().startsWith("avc1")
    /** VP9 profile 0 (8-bit SDR) only. */
    private fun isVp9(codec: String?) = codec.orEmpty().let { it == "vp9" || it.startsWith("vp09.00") }

    /**
     * The best plan for one video, or null with the reason. [fetchText] downloads a manifest (HLS), null on failure.
     */
    fun plan(ex: StreamExtractor, lang: String, hasVp9: Boolean, fetchText: (String) -> String?): Pair<Plan?, String> {
        val videoOnly = try { ex.videoOnlyStreams } catch (e: Exception) { emptyList() }
        val audioStreams = try { ex.audioStreams } catch (e: Exception) { emptyList() }
        val video = pickVideo(videoOnly, hasVp9)
        val audio = pickAudio(audioStreams, lang)
        val durationMs = videoOnly.firstNotNullOfOrNull { s -> s.itagItem?.approxDurationMs?.takeIf { it > 0 } }
            ?: (try { ex.length } catch (e: Exception) { 0L }).takeIf { it > 0 }?.times(1000L) ?: 0L
        if (video.isNotEmpty() && audio != null && durationMs > 0) return Plan.Dash(video, audio, durationMs) to "dash"

        val why = when {
            video.isEmpty() -> "no indexed H.264/VP9 video up to 1080p (${videoOnly.size} video-only streams)"
            audio == null -> "no indexed audio"
            else -> "no duration"
        }
        val hlsUrl = try { ex.hlsUrl } catch (e: Exception) { "" }
        if (hlsUrl.isNotEmpty()) {
            val master = fetchText(hlsUrl)
            val cut = master?.let { filterHlsMaster(it) }
            if (cut != null) return Plan.Hls(hlsUrl, cut.first, cut.second) to why
        }
        val muxed = try { ex.videoStreams } catch (e: Exception) { emptyList() }
        pickMuxed(muxed)?.let { return it to why }
        if (video.isNotEmpty() && durationMs > 0) return Plan.Dash(video, null, durationMs, " (no sound)") to why
        return null to "$why; ${if (hlsUrl.isEmpty()) "no HLS" else "HLS unusable"}; ${muxed.size} muxed, none H.264 MP4"
    }

    /**
     * The video ladder: AVC when it reaches the top class (the friendliest decoder), else VP9 when the device
     * decodes it and it goes higher. 1080p, 720p and 480p classes (lower only when that's all), 30 fps or less when offered.
     */
    fun pickVideo(all: List<VideoStream>, hasVp9: Boolean): List<Rep> {
        val usable = all.filter {
            it.isVideoOnly && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.itagItem != null &&
                it.initEnd > 0 && it.indexEnd > it.indexStart && pclass(it.width, it.height) in 240..1080
        }
        val avc = usable.filter { it.format == MediaFormat.MPEG_4 && isAvc(it.codec) }
        val vp9 = if (hasVp9) usable.filter { it.format == MediaFormat.WEBM && isVp9(it.codec) } else emptyList()
        val avcTop = avc.maxOfOrNull { pclass(it.width, it.height) } ?: 0
        val vp9Top = vp9.maxOfOrNull { pclass(it.width, it.height) } ?: 0
        val family = if (avc.isNotEmpty() && (avcTop >= 1080 || avcTop >= vp9Top)) avc else vp9
        val byClass = family.groupBy { pclass(it.width, it.height) }
        val classes = byClass.keys.filter { it >= 480 }.ifEmpty { byClass.keys.toList() }.sortedDescending()
        return classes.mapNotNull { c ->
            val list = byClass.getValue(c)
            (list.filter { it.fps in 1..30 }.ifEmpty { list }).maxByOrNull { it.bitrate }
        }.map { s ->
            val it = s.itagItem!!
            Rep(s.itag, s.content, s.format!!.mimeType, s.codec.orEmpty(), maxOf(s.bitrate, it.bitrate, 1),
                s.width, s.height, s.fps, pclass(s.width, s.height), s.initEnd, s.indexStart, s.indexEnd)
        }
    }

    /** AAC (best LC track) in the user's language, else the original track; Opus when there's no AAC. */
    fun pickAudio(all: List<AudioStream>, lang: String): Rep? {
        val usable = all.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.itagItem != null && it.initEnd > 0 && it.indexEnd > it.indexStart }
        val tracks = usable.filter { it.audioLocale?.language == lang }.ifEmpty {
            usable.filter { it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty {
                usable.filter { it.audioTrackType == null || it.audioLocale == null }.ifEmpty { usable }
            }
        }
        val aac = tracks.filter { it.format == MediaFormat.M4A && it.codec.orEmpty().startsWith("mp4a.40.2") }
            .ifEmpty { tracks.filter { it.format == MediaFormat.M4A } }
        val s = aac.maxByOrNull { it.averageBitrate }
            ?: tracks.filter { it.format == MediaFormat.WEBMA_OPUS || it.format == MediaFormat.WEBMA }.maxByOrNull { it.averageBitrate }
            ?: return null
        val it = s.itagItem!!
        return Rep(s.itag, s.content, s.format!!.mimeType, s.codec.orEmpty(), maxOf(s.bitrate, it.bitrate, 1), 0, 0, 0, 0,
            s.initEnd, s.indexStart, s.indexEnd, it.sampleRate.takeIf { r -> r > 0 } ?: 44100,
            it.audioChannels.takeIf { c -> c > 0 } ?: 2, s.audioLocale?.language)
    }

    /** The best muxed (video + audio) H.264 MP4 up to the 1080p class. */
    fun pickMuxed(all: List<VideoStream>): Plan.Progressive? = all
        .filter { !it.isVideoOnly && it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.MPEG_4 &&
            (it.codec == null || isAvc(it.codec)) && pclass(it.width, it.height) <= 1080 }
        .maxByOrNull { pclass(it.width, it.height) * 10 + (if (it.fps in 1..30) 1 else 0) }
        ?.let { Plan.Progressive(it.content, pclass(it.width, it.height), it.codec ?: "avc1") }

    /**
     * YouTube's HLS master cut to H.264 variants up to the 1080p class (no VP9 in TS, no AV1, no I-frame
     * playlists); the audio groups stay. Null when nothing is left. Second: the kept variants, for the log.
     */
    fun filterHlsMaster(text: String): Pair<String, String>? {
        val lines = text.lines()
        val out = StringBuilder(text.length)
        val kept = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimEnd('\r')
            when {
                line.startsWith("#EXT-X-I-FRAME-STREAM-INF") -> {}
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    val uri = lines.getOrNull(i + 1)?.trimEnd('\r').orEmpty()
                    i++
                    val codecs = Regex("CODECS=\"([^\"]*)\"").find(line)?.groupValues?.get(1).orEmpty()
                    val res = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)?.groupValues
                    val cls = if (res != null) pclass(res[1].toInt(), res[2].toInt()) else 0
                    val videoCodec = codecs.split(',').map { it.trim() }
                        .firstOrNull { !it.startsWith("mp4a") && !it.startsWith("opus") && !it.startsWith("ac-3") && !it.startsWith("ec-3") }
                    if (isAvc(videoCodec) && cls in 1..1080 && uri.isNotEmpty() && !uri.startsWith("#")) {
                        out.append(line).append('\n').append(uri).append('\n')
                        kept += "${cls}p"
                    }
                }
                else -> if (line.isNotEmpty()) out.append(line).append('\n')
            }
            i++
        }
        if (kept.isEmpty()) return null
        return out.toString() to kept.distinct().sortedByDescending { it.removeSuffix("p").toInt() }.joinToString("/") + " H.264"
    }

    // ---- DASH ----

    fun dash(video: List<Rep>, audio: Rep?, durationMs: Long): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun rep(r: Rep, extra: String, inner: String = "") =
            "<Representation id=\"${r.itag}\" codecs=\"${esc(r.codecs)}\" bandwidth=\"${r.bitrate}\"$extra>$inner" +
                "<BaseURL>${esc(r.url)}</BaseURL>" +
                "<SegmentBase indexRange=\"${r.indexStart}-${r.indexEnd}\"><Initialization range=\"0-${r.initEnd}\"/></SegmentBase>" +
                "</Representation>"
        val sb = StringBuilder(4096)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        sb.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" type=\"static\" ")
        sb.append("minBufferTime=\"PT1.500S\" mediaPresentationDuration=\"PT%.3fS\"><Period>".format(Locale.US, durationMs / 1000.0))
        sb.append("<AdaptationSet contentType=\"video\" mimeType=\"${video.first().mime}\" subsegmentAlignment=\"true\">")
        for (v in video) sb.append(rep(v, " width=\"${v.width}\" height=\"${v.height}\"" + if (v.fps > 0) " frameRate=\"${v.fps}\"" else ""))
        sb.append("</AdaptationSet>")
        if (audio != null) {
            sb.append("<AdaptationSet contentType=\"audio\" mimeType=\"${audio.mime}\" subsegmentAlignment=\"true\"")
            audio.lang?.let { sb.append(" lang=\"${esc(it)}\"") }
            sb.append(">")
            sb.append(rep(audio, " audioSamplingRate=\"${audio.sampleRate}\"",
                "<AudioChannelConfiguration schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\" value=\"${audio.channels}\"/>"))
            sb.append("</AdaptationSet>")
        }
        sb.append("</Period></MPD>")
        return sb.toString()
    }
}
