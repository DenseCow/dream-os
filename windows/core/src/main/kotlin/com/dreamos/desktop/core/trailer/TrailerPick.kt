package com.dreamos.desktop.core.trailer

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.Locale

/**
 * The trailer's choices, free of the network so they are tested on their own (fire-home TrailerPick, iPhone
 * Trailers): which of TMDB's videos to try in what order, and which of one video's streams to keep.
 *
 * PC rules (the PC has the most hardware of the three apps): video-only streams up to 4K, VP9 first (every PC GPU
 * since 2016 decodes it; H.264 stops at 1080p), H.264 where VP9 is missing; never AV1 (Settings hides AV1 by default:
 * older PCs play it less smoothly) and never HDR (trailers are mastered SDR on this path; the hero's software picture
 * can't tone map). Audio: Opus first, then AAC, in the viewer's language when the video has dubs.
 */
object TrailerPick {

    // ---- candidates ----

    /** One TMDB video ([key] is the video id; [size] TMDB's resolution field, 1080 / 2160). */
    data class Candidate(
        val key: String, val type: String, val official: Boolean, val lang: String?,
        val size: Int, val published: String, val name: String,
    ) {
        override fun toString() = "$key ($type${if (official) ", official" else ""}${lang?.let { ", $it" } ?: ""})"
    }

    /**
     * Best first: official Trailers in [lang], official English Trailers, other Trailers, Teasers, Clips,
     * Featurettes, the rest. Red band cuts (usually age-gated) after the others of their tier; then the language,
     * official, 1080p or better, and newest first.
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
     * The first of [cands] (best first) that [attempt] turns into streams; failures go into [fails] (per video, shared
     * across titles). Videos that failed lately are skipped, except on a [retry], which tries the ones that failed
     * softly once nothing fresh is left. A network error or rate limit stops the round (the rest would fail the same
     * way). One [log] line per video.
     */
    fun <T : Any> firstPlayable(
        cands: List<Candidate>, fails: MutableMap<String, Fail>, now: Long, retry: Boolean, maxTries: Int,
        log: (String) -> Unit, attempt: (Candidate) -> Pair<T?, String>,
    ): Pair<Candidate, T>? {
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
                val (found, why) = attempt(c)
                if (found == null) {
                    fails[c.key] = Fail(now + SOFT_FAIL_MS, false, why)
                    log("$c: skipped, $why (${ms()} ms)")
                    continue
                }
                fails.remove(c.key)
                log("$c: $why (${ms()} ms)")
                return c to found
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

    enum class Codec { VP9, AVC, AAC, OPUS, OTHER }

    /**
     * One stream of a video: [cls] is its quality class (1920x800 is 1080), [length] its size in bytes when the link
     * says (`clen`), else 0. Audio streams have no picture (0 sizes) and [lang] when the video has dubs.
     */
    data class Rep(
        val itag: Int, val url: String, val mime: String, val codec: Codec, val bitrate: Int,
        val width: Int = 0, val height: Int = 0, val fps: Int = 0, val cls: Int = 0,
        val length: Long = 0, val lang: String? = null,
    ) {
        val label: String get() = if (cls > 0) "${cls}p ${codec.name}${if (fps > 30) " ${fps}fps" else ""}" else codec.name
        override fun toString() = label
    }

    /** Everything one video offers that the PC plays, best first; the plan is made per use by [TrailerEngine]. */
    class Streams(
        /** One stream per quality class, best class first (4K, 1440p, 1080p, 720p, 480p; lower only when that's all). */
        val video: List<Rep>,
        /** Best audio first: Opus, then AAC. */
        val audio: List<Rep>,
        /** YouTube's HLS master (every class with sound, in short segments), or null. */
        val hlsUrl: String?,
        /** The best muxed MP4 (video + sound in one file; usually 360p), or null. */
        val muxed: Rep?,
        val durationMs: Long,
    ) {
        val label: String get() = "video ${video.joinToString("/")}, audio ${audio.firstOrNull()?.label ?: "none"}" +
            (if (hlsUrl != null) ", HLS" else "") + (muxed?.let { ", muxed ${it.cls}p" } ?: "")
        /** A stream URL carrying the links' expiry. */
        val anyUrl: String? get() = video.firstOrNull()?.url ?: hlsUrl ?: muxed?.url

        /** The best video stream up to [maxClass] (the lowest there is when every class is above it). */
        fun videoUpTo(maxClass: Int): Rep? = video.firstOrNull { it.cls <= maxClass } ?: video.lastOrNull()
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

    /** HDR itags (VP9.2 330-337, AV1 HDR 694-702): never picked. */
    private fun hdrItag(itag: Int) = itag in 330..337 || itag in 694..702

    fun videoCodec(codec: String?): Codec? {
        val c = codec.orEmpty().lowercase(Locale.ROOT)
        return when {
            c == "vp9" || c.startsWith("vp09.00") -> Codec.VP9 // profile 0: 8-bit SDR
            c.startsWith("avc1") -> Codec.AVC
            else -> null // AV1, VP9 profile 2 (HDR), anything unknown
        }
    }

    /** The video-only streams the PC plays, one per class (VP9 first, then H.264; 30 fps or less first when offered). */
    fun pickVideo(all: List<VideoStream>): List<Rep> = videoReps(all.filter {
        it.isVideoOnly && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && !hdrItag(it.itag)
    }.mapNotNull { s ->
        val codec = videoCodec(s.codec) ?: return@mapNotNull null
        Rep(s.itag, s.content, s.format?.mimeType ?: "video/mp4", codec, maxOf(s.bitrate, 1), s.width, s.height, s.fps,
            pclass(s.width, s.height), clen(s.content))
    })

    /** [reps] reduced to one per class: VP9 over H.264, 30 fps or less over higher rates, then the higher bitrate. */
    fun videoReps(reps: List<Rep>): List<Rep> {
        val byClass = reps.filter { it.cls in 240..2160 }.groupBy { it.cls }
        val classes = byClass.keys.filter { it >= 480 }.ifEmpty { byClass.keys.toList() }.sortedDescending()
        return classes.map { c ->
            byClass.getValue(c).sortedWith(
                compareBy<Rep> { if (it.codec == Codec.VP9) 0 else 1 }
                    .thenBy { if (it.fps in 1..30) 0 else 1 }
                    .thenByDescending { it.bitrate }
            ).first()
        }
    }

    /** Audio in the viewer's [lang] when the video has dubs, else the original track; Opus first, then AAC. */
    fun pickAudio(all: List<AudioStream>, lang: String): List<Rep> {
        val usable = all.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        val tracks = usable.filter { it.audioLocale?.language == lang }.ifEmpty {
            usable.filter { it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty {
                usable.filter { it.audioTrackType == null || it.audioLocale == null }.ifEmpty { usable }
            }
        }
        return audioReps(tracks.mapNotNull { s ->
            val codec = when {
                s.format == MediaFormat.WEBMA_OPUS || s.codec.orEmpty().startsWith("opus") -> Codec.OPUS
                s.format == MediaFormat.M4A && s.codec.orEmpty().startsWith("mp4a.40.2") -> Codec.AAC
                s.format == MediaFormat.M4A -> Codec.AAC
                else -> return@mapNotNull null
            }
            // AAC HE (mp4a.40.5) ranks under LC: lower bitrate, thinner sound.
            val rate = if (s.codec.orEmpty().startsWith("mp4a.40.5")) 1 else maxOf(s.averageBitrate, s.bitrate / 1000, 1)
            Rep(s.itag, s.content, s.format?.mimeType ?: "audio/mp4", codec, rate, length = clen(s.content),
                lang = s.audioLocale?.language)
        })
    }

    /** Opus over AAC, then the higher bitrate. */
    fun audioReps(reps: List<Rep>): List<Rep> =
        reps.sortedWith(compareBy<Rep> { if (it.codec == Codec.OPUS) 0 else 1 }.thenByDescending { it.bitrate })

    /** The best muxed (video + sound) MP4 the PC plays. */
    fun pickMuxed(all: List<VideoStream>): Rep? = all
        .filter { !it.isVideoOnly && it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.MPEG_4 }
        .filter { it.codec == null || videoCodec(it.codec.substringBefore(',').trim()) == Codec.AVC }
        .maxByOrNull { pclass(it.width, it.height) * 10 + (if (it.fps in 1..30) 1 else 0) }
        ?.let { Rep(it.itag, it.content, "video/mp4", Codec.AVC, maxOf(it.bitrate, 1), it.width, it.height, it.fps,
            pclass(it.width, it.height), clen(it.content)) }

    /** Everything [ex] (a fetched page) offers that the PC plays, or null with the reason. */
    fun streams(ex: StreamExtractor, lang: String): Pair<Streams?, String> {
        val videoOnly = try { ex.videoOnlyStreams } catch (e: Exception) { emptyList() }
        val audioStreams = try { ex.audioStreams } catch (e: Exception) { emptyList() }
        val muxedAll = try { ex.videoStreams } catch (e: Exception) { emptyList() }
        val hls = try { ex.hlsUrl } catch (e: Exception) { "" }.takeIf { it.isNotEmpty() }
        val video = pickVideo(videoOnly)
        val audio = pickAudio(audioStreams, lang)
        val muxed = pickMuxed(muxedAll)
        val durationMs = (try { ex.length } catch (e: Exception) { 0L }) * 1000L
        if (video.isEmpty() && hls == null && muxed == null) {
            return null to "no stream this PC plays (${videoOnly.size} video-only, ${muxedAll.size} muxed, no HLS)"
        }
        val s = Streams(video, audio, hls, muxed, durationMs)
        return s to s.label
    }

    /** The byte length a stream link carries (`clen=`), 0 when it doesn't. */
    fun clen(url: String): Long = Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    /** The expiry (unix seconds) a stream link carries (`expire=` or `/expire/`), or null. */
    fun expiry(url: String): Long? = Regex("[?&/]expire[=/](\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()

    // ---- HLS ----

    /**
     * YouTube's HLS master cut to SDR H.264 / VP9 variants up to [maxClass] (no AV1, no I-frame playlists); audio and
     * subtitle groups stay. mpv then opens the best one left. Null when nothing is left.
     */
    fun filterHlsMaster(text: String, maxClass: Int): String? {
        val lines = text.lines()
        val out = StringBuilder(text.length)
        var kept = 0
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
                    val sdr = !line.contains("VIDEO-RANGE=PQ") && !line.contains("VIDEO-RANGE=HLG")
                    val vcodec = codecs.split(',').map { it.trim() }
                        .firstOrNull { !it.startsWith("mp4a") && !it.startsWith("opus") && !it.startsWith("ac-3") && !it.startsWith("ec-3") }
                    if (sdr && videoCodec(vcodec) != null && cls in 1..maxClass && uri.isNotEmpty() && !uri.startsWith("#")) {
                        out.append(line).append('\n').append(uri).append('\n')
                        kept++
                    }
                }
                else -> if (line.isNotEmpty()) out.append(line).append('\n')
            }
            i++
        }
        return if (kept == 0) null else out.toString()
    }
}
