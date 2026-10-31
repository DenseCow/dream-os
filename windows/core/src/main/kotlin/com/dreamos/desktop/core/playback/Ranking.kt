package com.dreamos.desktop.core.playback

import java.util.Locale

/**
 * Dream Core's filters and ranking (fire-home NativeResolver allowed / choose / chooseRelaxed, with the iPhone's
 * additions: Prefer HDR above the cap, the Dolby-Vision-only penalty above it, source learning, a stable tie-break).
 * Pure functions over [Parsed] sources, so they're tested without any network.
 */
object Ranking {
    private const val GB = 1L shl 30

    /** How far a Play may set the viewer's filters aside when nothing passes them: size, then quality / CAM, then codecs. */
    const val RELAX_SIZE = 1
    const val RELAX_QUALITY = 2
    const val RELAX_FORMAT = 3

    /** A scraped source with what the ranking reads, the release name scanned once. [text] is upper case. */
    class Parsed(val s: Source, val cam: Boolean) {
        val text: String = s.torrentName.uppercase(Locale.US)
        val dubbed = ReleaseWords.dubbed(s.torrentName)
        val lang: String? by lazy { ReleaseLanguage.from(s.torrentName) }
        val is8k = s.quality == Quality.UHD_8K || ReleaseWords.is8k(text)
        val anyHdr = ReleaseWords.hdr(text)
        val hdr10 = ReleaseWords.hdr10(text)
        val dv = ReleaseWords.dv(text)
        val dvOnly = dv && !hdr10
        val hevc = ReleaseWords.hevc(text)
        val av1 = ReleaseWords.av1(text)
        val avc = ReleaseWords.avc(text)
        val threeD = ReleaseWords.threeD(text)
        val upscaled = ReleaseWords.upscaled(text)
        val collection = ReleaseWords.collection(text)
        val disc = ReleaseWords.disc(s.torrentName)
        val mp4 = ReleaseWords.mp4(s.torrentName, s.fileName, s.provider)
        val multi = ReleaseWords.has(text, "MULTI", "DUAL")
        val rank get() = if (is8k) -1 else s.quality.rank
        private var audioFor: Pair<String, Dubs.Audio>? = null
        fun audio(want: String): Dubs.Audio = audioFor?.takeIf { it.first == want }?.second
            ?: Dubs.audio(s.torrentName, want).also { audioFor = want to it }
        val traits by lazy { SourceMemory.traits(s.torrentName, s.fileName, s.provider, s.sizeBytes) }
    }

    /** A scraped result as Dream Core ranks it, or null for junk (recaps, reviews, betting-site rips: never a source). */
    fun parse(i: ScrapedSource, season: Int?, episode: Int?): Parsed? {
        if (ReleaseWords.isJunk(i.name) || i.hash.isBlank()) return null
        val (q, camLabel) = ReleaseWords.quality(i.quality, i.name)
        // A season pack's size is the whole season's: unknown per episode (size limits and the size rank skip it).
        val s = Source(i.hash.lowercase(Locale.US), i.name, i.fileName, i.fileIdx, if (i.pack) 0 else i.sizeBytes, q, i.seeders,
            i.provider, i.pack, season, episode)
        return Parsed(s, camLabel || ReleaseWords.isCam(i.name))
    }

    /** Everything one ranking pass needs besides the sources. */
    class Context(
        val f: SourceFilters.Values,
        val ep: Boolean,
        val skip: Set<String> = emptySet(),
        val runtimeSec: Int = 0,
        /** The viewer's audio language, 2 letters. */
        val want: String? = null,
        val resume: Boolean = false,
        val dub: Dubs.Rank = Dubs.Rank.NONE,
        val memory: SourceMemory.Snapshot? = null,
        /** Dream Player heard [lang] in the copy with this hash before (LanguagePills.heardIn). */
        val heard: (hash: String, lang: String) -> Boolean = { _, _ -> false },
    ) {
        fun copy(skip: Set<String> = this.skip, f: SourceFilters.Values = this.f) =
            Context(f, ep, skip, runtimeSec, want, resume, dub, memory, heard)
    }

    /** Rank of a quality: best allowed first; with no cap chosen, 1080p before 4K (streams well on any connection). */
    fun tier(rank: Int, f: SourceFilters.Values) = if (f.known) rank else when (rank) { 1 -> 0; 0 -> 1; else -> rank }

    /** Best tier the filters can give at all (what "a good result" means for the early exit). */
    fun bestTier(f: SourceFilters.Values) = if (f.known) f.cap.coerceAtMost(3) else 0

    /** A copy above Maximum Quality that only Prefer HDR let in. */
    fun aboveCap(p: Parsed, f: SourceFilters.Values) = f.known && p.rank < f.cap

    /**
     * Whether the viewer would play [p]: their filters (Hide CAM; discs, archives, episodes over 40 GB and 8K never).
     * Size limits: a GB range, or "fit my internet speed". Season packs carry no per-episode size: sizes don't apply.
     */
    fun allowed(p: Parsed, f: SourceFilters.Values, ep: Boolean, runtimeSec: Int = 0, relax: Int = 0): Boolean {
        val c = p.s
        if (p.cam && f.hideCam && relax < RELAX_QUALITY) return false
        if (c.fileName != null) { if (!DebridFiles.isVideo(c.fileName)) return false } else if (p.disc) return false
        if (ep && c.sizeBytes > 40 * GB) return false
        // 8K: no PC GPU here decodes it in time; never picked, relaxed or not.
        if (p.is8k) return false
        // Above what this PC is worth picking never plays, relaxed or not.
        if (p.rank < f.deviceCap) return false
        // Prefer HDR: an HDR copy may go above Maximum Quality (a Dolby-Vision-only one not while DV is hidden).
        if (relax < RELAX_QUALITY && p.rank < f.cap && !(f.hdrAboveCap && p.anyHdr && !(f.hideDv && p.dvOnly))) return false
        if (relax < RELAX_SIZE && !f.sizeOk(c.sizeBytes, ep, runtimeSec)) return false
        // Relaxed or not, what the viewer hides stays out: HDR / Dolby Vision / 3D, and AV1 when hidden.
        return !((relax < RELAX_FORMAT && f.hideHevc && p.hevc) ||
            (f.hideHdr && p.hdr10) ||
            (f.hideDv && p.dv) ||
            (f.hideAv1 && p.av1) ||
            (relax < RELAX_FORMAT && f.hideAvc && p.avc) ||
            (f.hide3d && p.threeD) ||
            (relax < RELAX_QUALITY && f.hideUpscaled && p.upscaled))
    }

    /**
     * [all] minus failed torrents and what the filters drop, best first: a film collection and files twice the
     * stream-friendly size last; then CAM after every real release (Hide CAM on, relaxed); then the language check;
     * then the quality tier; within one, the dub preference, an MP4 when resuming, packs, stream-friendly size
     * (movies up to 25 GB, episodes 6 GB), the viewer's HDR / HEVC preference and clean releases, what this PC learned,
     * an MKV before an MP4, then seeders, then scrape order.
     */
    fun choose(all: List<Parsed>, cx: Context, relax: Int = 0): List<Parsed> {
        val f = cx.f
        val pass = all.filter { it.s.hash !in cx.skip && allowed(it, f, cx.ep, cx.runtimeSec, relax) }
        val camsIn = !f.hideCam || relax >= RELAX_QUALITY
        // Hide CAM off: a CAM ranks like any copy, after the real releases of its quality; on (relaxed): after every one.
        val camsLast = f.hideCam && camsIn
        // SD only when nothing better exists (with CAM copies let in: a real release, not a CAM).
        val list = if (f.hideSd && pass.any { it.s.quality != Quality.SD && !(camsIn && it.cam) }) pass.filter { it.s.quality != Quality.SD } else pass
        val heavy = if (cx.ep) 6 * GB else 25 * GB
        val want = cx.want
        val other = want != null && want != "en" && cx.dub != Dubs.Rank.PLAIN
        val dubFirst = cx.dub == Dubs.Rank.DUB && want != null
        fun b(x: Boolean) = if (x) 1 else 0
        fun dubMiss(p: Parsed) = dubFirst && p.audio(want!!) != Dubs.Audio.DUB && !cx.heard(p.s.hash, want)
        fun langMiss(p: Parsed) = if (!other) 0 else if (p.lang == want || p.multi || cx.heard(p.s.hash, want!!)) 0 else 1
        fun sink(p: Parsed) = p.s.sizeBytes > 2 * heavy || (!cx.ep && p.collection)
        fun penalty(p: Parsed) = (if (p.dubbed && !other && !dubFirst) 2 else 0) + (if (p.upscaled) 2 else 0) +
            (if (f.preferHdr && !p.anyHdr) 1 else 0) + (if (f.preferHevc && !p.hevc) 1 else 0) + (if (aboveCap(p, f) && p.dvOnly) 1 else 0)
        val mem = cx.memory?.takeIf { !it.isEmpty }
        val keyed = list.mapIndexed { i, p ->
            intArrayOf(b(sink(p)), b(camsLast && p.cam), langMiss(p), tier(p.rank, f), b(p.cam), b(dubMiss(p)),
                b(cx.resume && p.mp4), b(p.s.pack), b(p.s.sizeBytes > heavy), penalty(p), mem?.nudge(p.traits) ?: 0,
                b(p.mp4), -p.s.seeders, i) to p
        }
        return keyed.sortedWith { a, c -> compareKeys(a.first, c.first) }.map { it.second }
    }

    private fun compareKeys(a: IntArray, b: IntArray): Int {
        for (i in a.indices) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }

    /**
     * [choose] at the strictest level that leaves something: the filters as set, then without size limits, then
     * without quality limits (CAM allowed, ranked after any real release), then without the HEVC / AVC choices.
     * [relaxOk] false (anything but a Play): the filters only. Returns the list and the level used.
     */
    fun chooseRelaxed(all: List<Parsed>, cx: Context, relaxOk: Boolean): Pair<List<Parsed>, Int> {
        for (level in 0..(if (relaxOk) RELAX_FORMAT else 0)) {
            val kept = choose(all, cx, level)
            if (kept.isNotEmpty()) return kept to level
        }
        return emptyList<Parsed>() to 0
    }

    /**
     * Counts and the best release of each grade for the pills ([SourceSurvey]): the best release the viewer would play
     * (Dolby Vision / HDR they hide don't count); within a quality, DV, then HDR first.
     */
    fun survey(all: List<Parsed>, f: SourceFilters.Values): SourceSurvey {
        val (cams, good) = all.filter { !it.is8k }.partition { it.cam }
        fun range(p: Parsed) = when { p.dv && !f.hideDv -> "DV"; p.hdr10 && !f.hideHdr -> "HDR"; else -> null }
        val allowedGood = good.filter { !(f.hideDv && it.dv) && !(f.hideHdr && it.hdr10) }.ifEmpty { good }
        val top = allowedGood.minByOrNull {
            it.rank.toLong() * 10_000_000 + (when (range(it)) { "DV" -> 0; "HDR" -> 1; else -> 2 }) * 1_000_000L - minOf(it.s.seeders, 999_999)
        }
        val topCam = cams.minByOrNull { it.rank.toLong() * 1_000_000 - minOf(it.s.seeders, 999_999) }
        return SourceSurvey(all.size, good.size, cams.size,
            top?.let { listOfNotNull(it.s.quality.label, ReleaseWords.sourceLabel(it.text), range(it)).joinToString(" ") },
            topCam?.let { ReleaseWords.camLabel(it.s.torrentName) + " " + it.s.quality.label })
    }

    /** "1080p WEB-DL" for a found real release, "HDTS 1080p" for a CAM. */
    fun bestLabel(p: Parsed) = if (p.cam) ReleaseWords.camLabel(p.s.torrentName) + " " + p.s.quality.label
        else listOfNotNull(p.s.quality.label, ReleaseWords.sourceLabel(p.text)).joinToString(" ")
}
