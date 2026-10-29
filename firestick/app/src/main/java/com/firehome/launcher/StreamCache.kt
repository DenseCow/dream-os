package com.firehome.launcher

import android.content.Context
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.common.util.Util
import androidx.media3.extractor.ChunkIndex
import androidx.media3.extractor.SeekMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InterruptedIOException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Instant Play's head start on the stream itself: once a link is ready ([PreResolve]), the parts of
 * the file Dream Player reads first are fetched into a small disk cache (Media3 SimpleCache, 64 MB,
 * LRU, in cacheDir/stream), so Play starts from local data instead of a TLS connect, the MKV header
 * and the first seconds over the network. [prefetch] takes the head of the file (header, first
 * clusters), an MKV's Cues (its seek index, found through the SeekHead: Media3's extractor reads
 * the Cues before the first cluster, so a start needs them as much as a resume does), the file's
 * tail (where Cues sit when there is no SeekHead) and, for a resume, a window around the byte
 * offset the position works out to (proportional: close enough for the extractor to land in cached
 * data most of the time; a miss just reads the network as before).
 *
 * Dream Player reads through [readOnlyFactory]: cached ranges come from disk, everything else goes
 * to the same HTTP source as before, and nothing is written during playback (no disk churn while a
 * movie plays). One title at a time: a new prefetch removes the others; [drop] clears everything.
 * Lowest priority, its own thread, 128 KB reads, and it pauses while the remote is in use
 * ([PreResolve.userBusy]: even a background download costs the details page frames on this CPU).
 * Its requests say `Connection: close`: a kept-alive socket the host has since dropped would be
 * handed to the player's first request and cost it seconds. URLs are never logged in full.
 *
 * [fastStart] is the foreground counterpart for a link whose start isn't on disk when Play is
 * pressed (a cold Dream Core resolve, a pre-resolve whose prefetch didn't get there): exactly what
 * the extractor reads before the first frame, fetched in parallel connections within a short budget,
 * then the link is handed to the player. Cold, the player paid three sequential round trips for an
 * MKV (header, Cues at the end, first cluster) and one long single-connection read for an MP4 whose
 * moov is tens of MB; here they overlap and the player reads them from disk.
 */
object StreamCache {
    private const val TAG = "FireHome"
    private const val SIZE_BYTES = 64L shl 20
    /** The file's head: the container header plus well over the 1 s start buffer at 1080p/2160p HEVC. */
    private const val HEAD_BYTES = 12L shl 20
    /** The file's tail: where an MKV keeps its Cues (the seek index). */
    private const val TAIL_BYTES = 2L shl 20
    private const val RESUME_BEFORE = 3L shl 20
    private const val RESUME_AFTER = 8L shl 20
    /** Most Cues are tens of KB; the first slice fetched at the Cues' position, before its size is known. */
    private const val CUES_FIRST = 256L shl 10
    private const val CUES_MAX = 8L shl 20

    private val lock = Any()
    private var cache: SimpleCache? = null
    @Volatile private var current: Job? = null
    /** The link whose start is on disk (the last prefetch that ran to its end). */
    @Volatile private var done: String? = null

    private class Job(val key: String) {
        @Volatile var cancelled = false
        @Volatile var writer: CacheWriter? = null
        fun cancel() { cancelled = true; writer?.cancel() }
    }

    /** The one cache (SimpleCache allows a single instance per folder). Loads its index the first time; cheap. */
    private fun get(ctx: Context): Cache = synchronized(lock) {
        cache ?: SimpleCache(File(ctx.cacheDir, "stream"), LeastRecentlyUsedCacheEvictor(SIZE_BYTES), StandaloneDatabaseProvider(ctx)).also { cache = it }
    }

    /** Opens the cache off the main thread ahead of the first use (the first pre-resolve). */
    fun warm(ctx: Context) {
        val app = ctx.applicationContext
        Net.run { try { get(app) } catch (e: Exception) { Log.w(TAG, "Stream cache: $e") } }
    }

    /**
     * [upstream] with the cache in front of it, read-only: for Dream Player playing [url]. While a fast start
     * is still writing that link, a range it holds is waited for instead of fetched again ([blockOnCache]).
     */
    fun readOnlyFactory(ctx: Context, upstream: DataSource.Factory, url: String): DataSource.Factory = try {
        val inner = CacheDataSource.Factory()
            .setCache(get(ctx))
            .setUpstreamDataSourceFactory(upstream)
            .setCacheReadDataSourceFactory(DataSource.Factory { Buffered(FileDataSource()) })
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or (if (blockOnCache(url)) CacheDataSource.FLAG_BLOCK_ON_CACHE else 0))
        inner
    } catch (e: Exception) {
        Log.w(TAG, "Stream cache: not used: $e")
        upstream
    }

    /**
     * Cached bytes read in 64 KB blocks. Media3's cache reads go through FileDataSource, one syscall per
     * read, and the extractor reads an MKV's Cues a few bytes at a time (tens of thousands of entries):
     * unbuffered that took 5-8 s, longer than fetching them over the network, whose stream is buffered.
     */
    private class Buffered(private val inner: DataSource) : DataSource by inner {
        private val buf = ByteArray(64 * 1024)
        private var pos = 0
        private var lim = 0
        private var remaining = C.LENGTH_UNSET.toLong()

        override fun open(dataSpec: DataSpec): Long {
            pos = 0; lim = 0
            return inner.open(dataSpec).also { remaining = it }
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (pos == lim) {
                val want = if (remaining == C.LENGTH_UNSET.toLong()) buf.size else minOf(buf.size.toLong(), remaining).toInt()
                if (want <= 0) return C.RESULT_END_OF_INPUT
                val r = inner.read(buf, 0, want)
                if (r <= 0) return r
                pos = 0; lim = r
                if (remaining != C.LENGTH_UNSET.toLong()) remaining -= r
            }
            val n = minOf(length, lim - pos)
            System.arraycopy(buf, pos, target, offset, n)
            pos += n
            return n
        }

        override fun close() { pos = 0; lim = 0; inner.close() }
    }

    /** The cache key Dream Player will read [url] under (ExoEngine's URI of the clean URL). */
    private fun key(url: String) = Uri.parse(url.substringBefore('|')).toString()

    /**
     * Fetches the parts of [rawUrl] (headers after '|') the player reads first, in the
     * background. [startMs]/[durationMs]: the resume spot, for the window around it (0 = from the start).
     */
    fun prefetch(ctx: Context, rawUrl: String, headers: Map<String, String>, startMs: Long, durationMs: Long) {
        val app = ctx.applicationContext
        val clean = rawUrl.substringBefore('|')
        if (!clean.startsWith("http")) return
        val all = LinkedHashMap<String, String>().apply {
            putAll(headers)
            if ('|' in rawUrl) putAll(StreamUrls.headers(rawUrl.substringAfter('|')))
        }
        val job = Job(key(clean))
        synchronized(lock) {
            if (done == job.key || current?.key == job.key) return // there already, or on its way
            current?.cancel(); current = job
        }
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
            try {
                run(app, job, clean, all, startMs, durationMs)
            } catch (e: Exception) {
                if (!job.cancelled) Log.w(TAG, "Stream cache: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            } finally {
                synchronized(lock) { if (current === job) current = null }
            }
        }, "stream-prefetch").apply { isDaemon = true }.start()
    }

    private fun run(app: Context, job: Job, url: String, headers: Map<String, String>, startMs: Long, durationMs: Long) {
        hold(job) // the hand-off itself may land mid-navigation: not even the clean-up and probe then
        // Cancelled meanwhile (a fast start took over): the clean-up below would delete what that one is writing.
        if (job.cancelled) return
        val t0 = SystemClock.elapsedRealtime()
        val cache = get(app)
        // One title at a time.
        for (k in cache.keys.toList()) if (k != job.key) cache.removeResource(k)
        if (done != job.key) done = null
        val http = ExoEngine.httpFactory(headers + ("Connection" to "close"))
        val ds = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http).createDataSourceForDownloading()
        val uri = Uri.parse(url)
        val buf = ByteArray(128 * 1024)
        // Every request is bounded and read to its end: a transfer cut off midway can leave the debrid host
        // treating the link as still streaming, and the player's own request then crawls for seconds.
        val len = length(http, uri)
        if (job.cancelled) return
        // The cache must know where the file ends: bounded requests never tell it, and without it a read that
        // runs to the end of a cached tail (an MKV's Cues) asks the host for bytes past the end and stalls.
        if (len > 0) cache.applyContentMetadataMutations(job.key, ContentMetadataMutations.setContentLength(ContentMetadataMutations(), len))
        // After every read: wait while the remote is being used (the page's frames come first).
        val rest = CacheWriter.ProgressListener { _, _, _ -> hold(job) }
        write(job, CacheWriter(ds, DataSpec.Builder().setUri(uri).setPosition(0).setLength(if (len > 0) minOf(HEAD_BYTES, len) else HEAD_BYTES).build(), buf, rest))
        // The Cues, wherever the SeekHead says they are (unless the head or tail has them already).
        var cues = 0L
        if (!job.cancelled && len > 0) {
            val at = cuesPosition(read(cache, http, uri, 0, 256 * 1024))
            if (at != null && at > 0 && at < len && !cache.isCached(job.key, at, minOf(CUES_FIRST, len - at))) {
                write(job, CacheWriter(ds, DataSpec.Builder().setUri(uri).setPosition(at).setLength(minOf(CUES_FIRST, len - at)).build(), buf, rest))
                val size = elementSize(read(cache, http, uri, at, 16))
                val whole = (size + 12).coerceAtMost(CUES_MAX)
                if (!job.cancelled && size > 0 && whole > CUES_FIRST && at + whole <= len) {
                    write(job, CacheWriter(ds, DataSpec.Builder().setUri(uri).setPosition(at + CUES_FIRST).setLength(whole - CUES_FIRST).build(), buf, rest))
                }
                cues = whole
            }
        }
        var tail = 0L
        if (!job.cancelled && len > HEAD_BYTES + TAIL_BYTES) {
            write(job, CacheWriter(ds, DataSpec.Builder().setUri(uri).setPosition(len - TAIL_BYTES).setLength(TAIL_BYTES).build(), buf, rest))
            tail = TAIL_BYTES
        }
        var window = 0L
        if (!job.cancelled && startMs > 0 && durationMs > 0 && len > 0) {
            val at = (len.toDouble() * startMs / durationMs).toLong()
            val from = (at - RESUME_BEFORE).coerceAtLeast(HEAD_BYTES)
            val to = (at + RESUME_AFTER).coerceAtMost(len - tail)
            if (to > from) {
                write(job, CacheWriter(ds, DataSpec.Builder().setUri(uri).setPosition(from).setLength(to - from).build(), buf, rest))
                window = to - from
            }
        }
        if (!job.cancelled) {
            done = job.key
            val mb = cache.getCachedBytes(job.key, 0, if (len > 0) len else Long.MAX_VALUE) / 1048576.0
            Log.w(TAG, "Stream cache: ${"%.1f".format(mb)} MB ahead in ${(SystemClock.elapsedRealtime() - t0) / 100 / 10.0} s" +
                (if (len > 0) " of ${len shr 20} MB" else "") + (if (cues > 0) ", cues ${cues shr 10} KB" else "") +
                (if (window > 0) " (resume window ${window shr 20} MB at ${startMs / 60_000} min)" else ""))
        }
    }

    /**
     * [length] bytes of the stream at [position], from the cache when they are there (else fetched, not kept).
     * [block]: a range a fast start is still writing is waited for rather than fetched again.
     */
    private fun read(cache: Cache, http: DataSource.Factory, uri: Uri, position: Long, length: Int, block: Boolean = false): ByteArray {
        val ds = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http).setCacheWriteDataSinkFactory(null)
            .setFlags(if (block) CacheDataSource.FLAG_BLOCK_ON_CACHE else 0).createDataSource()
        val out = ByteArray(length)
        var n = 0
        try {
            ds.open(DataSpec.Builder().setUri(uri).setPosition(position).setLength(length.toLong()).build())
            while (n < length) {
                val r = ds.read(out, n, length - n)
                if (r <= 0) break
                n += r
            }
        } catch (e: Exception) {
        } finally {
            try { ds.close() } catch (e: Exception) {}
        }
        return if (n == length) out else out.copyOf(n)
    }

    // ---- a little EBML: where an MKV keeps its Cues ----

    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEKHEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEKID = 0x53ABL
    private const val ID_SEEKPOSITION = 0x53ACL
    private const val ID_CUES = 0x1C53BB6BL
    private const val ID_CLUSTER = 0x1F43B675L

    private class Ebml(val b: ByteArray) {
        var i = 0
        val left get() = b.size - i
        private fun lengthOf(first: Int): Int { var m = 0x80; var l = 1; while (l <= 8 && first and m == 0) { m = m shr 1; l++ }; return l }
        /** An element ID (marker bits kept), or -1 at the end. */
        fun id(): Long {
            if (left < 1) return -1
            val l = lengthOf(b[i].toInt() and 0xFF)
            if (l > 4 || left < l) return -1
            var v = 0L
            repeat(l) { v = (v shl 8) or (b[i++].toLong() and 0xFF) }
            return v
        }
        /** An element size, -1 for unknown or at the end. */
        fun size(): Long {
            if (left < 1) return -1
            val first = b[i].toInt() and 0xFF
            val l = lengthOf(first)
            if (l > 8 || left < l) return -1
            var v = (first and (0xFF shr l)).toLong()
            var allOnes = v == (0xFFL shr l)
            i++
            repeat(l - 1) { val x = b[i++].toLong() and 0xFF; if (x != 0xFFL) allOnes = false; v = (v shl 8) or x }
            return if (allOnes) -1 else v
        }
        fun uint(n: Int): Long { var v = 0L; repeat(n) { v = (v shl 8) or (b[i++].toLong() and 0xFF) }; return v }
        /**
         * Past [n] bytes of data. Never `i += size()`: Kotlin reads `i` before it evaluates the right-hand side, so
         * the byte(s) size() itself consumed were lost and the walk landed inside the EBML header's last field.
         * That one line kept the index path from ever engaging (every MKV's head read as "not a Segment").
         */
        fun skip(n: Long) { i += n.toInt().coerceAtLeast(0) }
    }

    /** The Cues' absolute byte position from the SeekHead(s) near the file's start, or null (none named, not an MKV). */
    private fun cuesPosition(head: ByteArray): Long? = scan(head)?.cuesAt?.takeIf { it > 0 }

    /** The data size of the element whose header starts [bytes] (the Cues), or -1. */
    private fun elementSize(bytes: ByteArray): Long = try {
        val e = Ebml(bytes)
        if (e.id() == ID_CUES) e.size() else -1
    } catch (e: Exception) { -1 }

    // ---- the MKV's seek index, parsed here instead of by the player's extractor ----
    //
    // Media3's MatroskaExtractor reads the Cues before the first cluster: a seek to the file's end, the index
    // read through its EBML reader a few bytes per call (tens of thousands of calls: ~0.35 s for a 31 KB index
    // on this stick, measured both over the network and from disk), a seek back. The same bytes parsed here in
    // one read take milliseconds. ExoEngine's extractor wrapper tells the extractor to leave the Cues alone
    // when [cuesAhead] says the index can be built here, and swaps the result in ([mkvSeekMap]) the moment the
    // extractor would otherwise report the stream unseekable. [mkvIndex] mirrors MatroskaExtractor.buildSeekMap
    // exactly (same ChunkIndex, same scaling, same trimming), so seeking, resuming and the duration are what
    // the extractor would have produced itself.

    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMECODE_SCALE = 0x2AD7B1L
    private const val ID_DURATION = 0x4489L
    private const val ID_CUE_POINT = 0xBBL
    private const val ID_CUE_TIME = 0xB3L
    private const val ID_CUE_TRACK_POSITIONS = 0xB7L
    private const val ID_CUE_CLUSTER_POSITION = 0xF1L

    /**
     * What the head of an MKV says: the Segment's data position and size (-1 unknown), the Info's timing ([durationTimecode]
     * Long.MIN_VALUE when there is none), and where the Cues are ([cuesAt] -1 when nothing names them).
     */
    private class MkvHead(val segmentPos: Long, val segmentSize: Long, val timecodeScale: Long, val durationTimecode: Long, val infoSeen: Boolean, val cuesAt: Long)

    /** An index being built ahead of the extractor for one link: the head's facts, the Cues' whole size, their first bytes when fetched here, then the map. */
    private class Ahead(val key: String, val head: MkvHead, val whole: Long, val first: ByteArray?) { @Volatile var map: SeekMap? = null }

    @Volatile private var ahead: Ahead? = null

    /**
     * The Seek entries of the SeekHead whose data runs from e.i to [end]: target ID to absolute position, into [seeks]
     * (the last entry per ID wins, as in the extractor).
     */
    private fun seekEntries(e: Ebml, end: Int, segment: Long, seeks: HashMap<Long, Long>) {
        while (e.i < end && e.left > 2) {
            val sid = e.id(); val ssize = e.size()
            if (sid != ID_SEEK) { e.skip(ssize); continue }
            val send = e.i + ssize.toInt()
            var target = -1L; var pos = -1L
            while (e.i < send) {
                val fid = e.id(); val fsize = e.size().toInt()
                when (fid) { ID_SEEKID -> target = e.uint(fsize); ID_SEEKPOSITION -> pos = e.uint(fsize); else -> e.i += fsize }
            }
            if (target >= 0 && pos >= 0) seeks[target] = segment + pos
        }
        e.i = end
    }

    /**
     * The top of an MKV read from [head] (its first bytes), or null when it isn't one. Walks the top-level elements
     * before the first cluster: every SeekHead (a file may hold more than one), the Info, Cues sitting up front. A
     * SeekHead that names only a second SeekHead (older mkvmerge keeps the full list at the end of the file) is
     * followed when its bytes are in [head], or [more] can supply them (from the cache, once the tail is on disk).
     */
    private fun scan(head: ByteArray, more: ((Long, Int) -> ByteArray?)? = null): MkvHead? = try {
        val e = Ebml(head)
        if (e.id() != ID_EBML) null else {
            e.skip(e.size())
            if (e.id() != ID_SEGMENT) null else {
                val segmentSize = e.size()
                val segment = e.i.toLong()
                val seeks = HashMap<Long, Long>()
                var scale = -1L
                var duration = Long.MIN_VALUE
                var infoSeen = false
                var cuesInline = -1L
                while (e.left > 2) {
                    val at = e.i
                    val id = e.id(); val size = e.size()
                    if (id < 0) break
                    when (id) {
                        ID_SEEKHEAD -> { if (size < 0) break; seekEntries(e, minOf(e.i + size, head.size.toLong()).toInt(), segment, seeks) }
                        ID_INFO -> {
                            if (size < 0 || e.i + size > head.size) break // unknown size, or not all in the head
                            val end = e.i + size.toInt()
                            while (e.i < end) {
                                val fid = e.id(); val fsize = e.size()
                                if (fid < 0 || fsize < 0) break
                                when (fid) {
                                    ID_TIMECODE_SCALE -> if (fsize <= 8) scale = e.uint(fsize.toInt()) else break
                                    ID_DURATION -> when (fsize) {
                                        4L -> duration = Float.fromBits(e.uint(4).toInt()).toDouble().toLong()
                                        8L -> duration = Double.fromBits(e.uint(8)).toLong()
                                        else -> break
                                    }
                                    else -> e.skip(fsize)
                                }
                            }
                            e.i = end
                            infoSeen = true
                        }
                        ID_CUES -> { cuesInline = at.toLong(); break } // right at the front: the extractor parses them inline
                        ID_CLUSTER -> break
                        else -> { if (size < 0) break; e.skip(size) }
                    }
                }
                // Only another SeekHead named: the full list is there (at the end of the file, as a rule).
                val chained = seeks[ID_SEEKHEAD]
                if (chained != null && ID_CUES !in seeks && chained > 0) {
                    val b = if (chained + 2 < head.size) head.copyOfRange(chained.toInt(), head.size) else more?.invoke(chained, 4096)
                    if (b != null) {
                        val c = Ebml(b)
                        if (c.id() == ID_SEEKHEAD) { val cs = c.size(); if (cs > 0) seekEntries(c, minOf(c.i + cs, b.size.toLong()).toInt(), segment, seeks) }
                    }
                }
                // As the extractor does: TimecodeScale omitted means 1 ms.
                MkvHead(segment, segmentSize, if (scale > 0) scale else 1_000_000L, duration, infoSeen, if (cuesInline >= 0) cuesInline else (seeks[ID_CUES] ?: -1L))
            }
        }
    } catch (e: Exception) { null }

    /**
     * The head's facts needed for the index, or null when any is missing (nothing names the Cues, no Duration in the
     * Info, an unknown-size Info): the extractor then does its own thing, as before. [more]: see [scan].
     */
    private fun mkvHead(head: ByteArray, more: ((Long, Int) -> ByteArray?)? = null): MkvHead? =
        scan(head, more)?.takeIf { it.infoSeen && it.durationTimecode != Long.MIN_VALUE && it.cuesAt > 0 }

    /**
     * The seek map MatroskaExtractor.buildSeekMap would make of the Cues element in [cues] (its header first),
     * or null when the element is cut short or malformed in a way the extractor would reject (the stream is
     * then reported unseekable, as the extractor itself would).
     */
    private fun mkvIndex(cues: ByteArray, h: MkvHead): SeekMap? {
        val e = Ebml(cues)
        if (e.id() != ID_CUES) return null
        val size = e.size()
        if (size <= 0 || e.i + size > cues.size) return null
        val end = (e.i + size).toInt()
        val times = ArrayList<Long>(4096)
        val positions = ArrayList<Long>(4096)
        while (e.i < end) {
            val id = e.id(); val s = e.size()
            if (id < 0 || s < 0) return null
            if (id != ID_CUE_POINT) { e.i += s.toInt(); continue }
            val pend = e.i + s.toInt()
            var seenPosition = false
            while (e.i < pend) {
                val cid = e.id(); val cs = e.size()
                if (cid < 0 || cs < 0) return null
                when (cid) {
                    ID_CUE_TIME -> { if (cs > 8) return null; times.add(Util.scaleLargeTimestamp(e.uint(cs.toInt()), h.timecodeScale, 1000)) }
                    ID_CUE_TRACK_POSITIONS -> {
                        val tend = e.i + cs.toInt()
                        while (e.i < tend) {
                            val tid = e.id(); val ts = e.size()
                            if (tid < 0 || ts < 0) return null
                            // More than one track's positions in a cue point: the first is taken, as the extractor does.
                            if (tid == ID_CUE_CLUSTER_POSITION && !seenPosition) { if (ts > 8) return null; positions.add(e.uint(ts.toInt())); seenPosition = true }
                            else e.i += ts.toInt()
                        }
                    }
                    else -> e.i += cs.toInt()
                }
            }
        }
        val n = times.size
        if (n == 0 || positions.size != n) return null
        val durationUs = Util.scaleLargeTimestamp(h.durationTimecode, h.timecodeScale, 1000)
        var sizes = IntArray(n)
        var offsets = LongArray(n)
        var durationsUs = LongArray(n)
        var timesUs = LongArray(n)
        for (i in 0 until n) { timesUs[i] = times[i]; offsets[i] = h.segmentPos + positions[i] }
        for (i in 0 until n - 1) { sizes[i] = (offsets[i + 1] - offsets[i]).toInt(); durationsUs[i] = timesUs[i + 1] - timesUs[i] }
        sizes[n - 1] = (h.segmentPos + h.segmentSize - offsets[n - 1]).toInt()
        durationsUs[n - 1] = durationUs - timesUs[n - 1]
        if (durationsUs[n - 1] <= 0) { // the extractor drops a last cue point past the duration
            if (n == 1) return null
            sizes = sizes.copyOf(n - 1); offsets = offsets.copyOf(n - 1); durationsUs = durationsUs.copyOf(n - 1); timesUs = timesUs.copyOf(n - 1)
        }
        return ChunkIndex(sizes, offsets, durationsUs, timesUs)
    }

    /**
     * Whether the Matroska extractor opening [url] (ExoEngine's clean URL, with [http] its HTTP source) should
     * leave the Cues to [mkvSeekMap]: the file's head is on disk (a fast start or prefetch brought it), it is an
     * MKV whose SeekHead says where the Cues are, and their size is known and reasonable. The Cues themselves are
     * read from disk, waited for while a fast start is still writing them, or, when neither (a fast start that
     * ended without them), fetched here in one request: the extractor would open that very connection itself and
     * then read the index a few bytes per call. From the extractor's own thread. An MKV declined says why in the log.
     */
    fun cuesAhead(ctx: Context, url: String, http: DataSource.Factory): Boolean = try {
        val k = key(url)
        if (ahead?.key == k) true else {
            val cache = get(ctx.applicationContext)
            val uri = Uri.parse(url.substringBefore('|'))
            if (!cache.isCached(k, 0, FAST_HEAD)) false else {
                val head = read(cache, http, uri, 0, FAST_HEAD.toInt())
                if (!isMkv(head)) false else {
                    val h = mkvHead(head) { at, n -> if (cache.isCached(k, at, 16)) read(cache, http, uri, at, n) else null }
                    if (h == null) declined("nothing names the Cues, or no Duration") else {
                        val len = ContentMetadata.getContentLength(cache.getContentMetadata(k))
                        val onDisk = cache.isCached(k, h.cuesAt, 16)
                        // On disk or on its way: the header now, the rest in mkvSeekMap. Else the first slice (the whole index, nearly always).
                        val n = if (onDisk || blockOnCache(url)) 16L else minOf(CUES_FIRST, if (len > h.cuesAt) len - h.cuesAt else CUES_FIRST)
                        val bytes = read(cache, http, uri, h.cuesAt, n.toInt(), block = !onDisk)
                        val e = Ebml(bytes)
                        val size = if (e.id() == ID_CUES) e.size() else -1
                        val whole = e.i + size
                        when {
                            size <= 0 -> declined("no Cues at the position named (${h.cuesAt})")
                            whole > CUES_MAX -> declined("Cues too big (${whole shr 10} KB)")
                            else -> { ahead = Ahead(k, h, whole, bytes.takeIf { n > 16 }); true }
                        }
                    }
                }
            }
        }
    } catch (e: Exception) { false }

    private fun declined(why: String): Boolean { Log.w(TAG, "Fast start: MKV index not used: $why"); return false }

    /**
     * The seek index of [url] after [cuesAhead] said yes: the Cues read from disk (waited for while a fast start
     * is still writing them; fetched if they are somehow not there) and parsed as the extractor would. Null when
     * they can't be used (the stream is then unseekable, as the extractor would have made it). Built once per link.
     */
    fun mkvSeekMap(ctx: Context, url: String, http: DataSource.Factory): SeekMap? = try {
        val a = ahead?.takeIf { it.key == key(url) }
        if (a == null) null else {
            val built = a.map
            if (built != null) {
                Log.w(TAG, "Fast start: MKV index reused (${(built as? ChunkIndex)?.length ?: 0} cue points)")
                built
            } else {
                val t0 = SystemClock.elapsedRealtime()
                val cache = get(ctx.applicationContext)
                val uri = Uri.parse(url.substringBefore('|'))
                val whole = a.whole.toInt()
                val first = a.first
                val bytes = when {
                    first == null -> read(cache, http, uri, a.head.cuesAt, whole, block = blockOnCache(url))
                    first.size >= whole -> first.copyOf(whole)
                    else -> first + read(cache, http, uri, a.head.cuesAt + first.size, whole - first.size, block = blockOnCache(url)) // bigger than the first slice: the rest
                }
                val map = mkvIndex(bytes, a.head)
                Log.w(TAG, "Fast start: MKV index " + (if (map is ChunkIndex) "${map.length} cue points" else "NOT usable") +
                    " from ${bytes.size shr 10} KB in ${SystemClock.elapsedRealtime() - t0} ms" + (if (first != null) " (fetched here)" else ""))
                a.map = map
                map
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Fast start: MKV index failed: ${e.javaClass.simpleName}")
        null
    }

    /** The file's size from a one-byte range request (Content-Range), or -1. */
    private fun length(http: DataSource.Factory, uri: Uri): Long {
        val ds = http.createDataSource()
        return try {
            ds.open(DataSpec.Builder().setUri(uri).setPosition(0).setLength(1).build())
            val range = ds.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
            ds.read(ByteArray(1), 0, 1)
            range?.substringAfter('/', "")?.trim()?.toLongOrNull() ?: -1L
        } catch (e: Exception) {
            -1L
        } finally {
            try { ds.close() } catch (e: Exception) {}
        }
    }

    private fun hold(job: Job) {
        while (PreResolve.userBusy() && !job.cancelled) Thread.sleep(100)
    }

    private fun write(job: Job, w: CacheWriter) {
        if (job.cancelled) return
        hold(job)
        job.writer = w
        try {
            w.cache()
        } catch (e: InterruptedIOException) {
            // Dropped.
        } finally {
            job.writer = null
        }
    }

    // ---- fast start ----

    /** The head read first: an MKV's EBML header, SeekHead and Tracks, or an MP4's ftyp and the moov's header. */
    private const val FAST_HEAD = 256L shl 10
    /** The file's end, fetched alongside the head on speculation: an MKV's Cues nearly always sit there. */
    private const val FAST_TAIL = 1L shl 20
    /** Media after the metadata (or around the resume spot): a few seconds at 1080p, the start buffer at a 4K remux. */
    private const val FAST_MEDIA = 2L shl 20
    private const val FAST_PIECE = 1L shl 20
    /** One range request of an MP4's moov (a 2.5 h film's sample tables run to 15 MB); two are speculated for an .mp4. */
    private const val FAST_SLICE = 3L shl 20
    private const val FAST_WORKERS = 5
    /** The hand-over waits this long at most for the head (nothing can be planned without it). */
    private const val FAST_HEAD_MS = 4_000L
    /** Metadata still arriving after this isn't planned around any more (the player is reading on its own by then). */
    private const val FAST_META_MS = 8_000L
    /** A piece's connection or read may stall this long before it fails and frees its range for the player. */
    private const val FAST_TIMEOUT_MS = 8_000
    private const val MOOV_MAX = 40L shl 20

    /** The fast start under way (planning or still transferring), for [blockOnCache]. */
    @Volatile private var fast: Fast? = null

    /** The link's start is on disk (a prefetch ran to its end): the player can have it straight away. */
    fun ready(rawUrl: String): Boolean = done == key(rawUrl.substringBefore('|'))

    /**
     * Whether Dream Player's cache source for [url] should wait for a range a fast start is still writing (a
     * fraction of a second) rather than open its own connection for it (most of a second, and the bytes twice).
     * Only while that fast start runs: nothing else writes the cache while the player is up.
     */
    private fun blockOnCache(url: String): Boolean = fast?.let { it.key == key(url) && !it.over } == true

    /**
     * From a background thread: fetches what the player reads before the first frame of [rawUrl] into the
     * cache, in parallel connections, and returns as soon as the head is on disk and the rest is planned (the
     * head tells the container and where its Cues or moov are). The caller then hands the link to the player,
     * which parses the header while the remaining pieces land; where it reaches one still in flight it waits
     * for it ([blockOnCache]). Transfers are never cut (a request closed midway can make the host treat the link
     * as still streaming). [sizeBytes]: the file's size when known (the debrid service says), else taken from
     * the first response. Not HTTP, not an MKV/MP4, or the start on disk already: nothing beyond the first read.
     */
    fun fastStart(ctx: Context, rawUrl: String, headers: Map<String, String>, startMs: Long, durationMs: Long, sizeBytes: Long) {
        val clean = rawUrl.substringBefore('|')
        if (!clean.startsWith("http")) return
        val k = key(clean)
        synchronized(lock) {
            if (done == k) return
            current?.cancel(); current = null // a background prefetch (paused for the key press anyway) gives way
        }
        val all = LinkedHashMap<String, String>().apply {
            putAll(headers)
            if ('|' in rawUrl) putAll(StreamUrls.headers(rawUrl.substringAfter('|')))
        }
        try {
            val http = ExoEngine.httpFactory(all + ("Connection" to "close")).setConnectTimeoutMs(FAST_TIMEOUT_MS).setReadTimeoutMs(FAST_TIMEOUT_MS)
            val f = Fast(get(ctx.applicationContext), http, Uri.parse(clean), k, sizeBytes)
            synchronized(lock) { fast?.over = true; fast = f }
            f.begin(startMs, durationMs)
        } catch (e: Exception) {
            Log.w(TAG, "Fast start: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
        }
    }

    private class Fast(val cache: Cache, val http: DataSource.Factory, val uri: Uri, val key: String, var len: Long) {
        val t0 = SystemClock.elapsedRealtime()
        /** Done planning: nothing more is queued, and the player needn't wait on the cache for this link. */
        @Volatile var over = false
        private val pool = ThreadPoolExecutor(FAST_WORKERS, FAST_WORKERS, 2, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
            Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE); r.run() }, "fast-start").apply { isDaemon = true }
        }
        private val meta = ArrayList<Future<*>>()
        private val media = ArrayList<Future<*>>()
        private val notes: MutableList<String> = Collections.synchronizedList(ArrayList())
        @Volatile private var head: ByteArray? = null
        /** The file's size from the head's Content-Range (what the host says; checks [len]). */
        @Volatile private var reported = -1L
        private var kind = "?"
        private var cuesAt: Long? = null
        private var moovEnd = -1L

        private fun now() = SystemClock.elapsedRealtime()
        private fun secs(ms: Long) = "%.2f".format(Locale.US, ms / 1000.0)

        /** Queues a range (skipped when cached already; [keep]: its bytes kept for parsing). */
        private fun fetch(what: String, position: Long, length: Long, metadata: Boolean, keep: Boolean = false): Future<*>? {
            if (over || position < 0 || length <= 0 || (len > 0 && position >= len)) return null
            val n = if (len > 0) minOf(length, len - position) else length
            if (!keep && cache.isCached(key, position, n)) return null
            val f = pool.submit { if (!over) pull(what, position, n, keep) }
            (if (metadata) meta else media).add(f)
            return f
        }

        /** Pieces of [piece] over [from, to), each its own connection. */
        private fun pieces(what: String, from: Long, to: Long, metadata: Boolean, piece: Long = FAST_PIECE) {
            var p = from
            while (p < to) { fetch(what, p, minOf(piece, to - p), metadata); p += piece }
        }

        private fun pull(what: String, position: Long, length: Long, keep: Boolean) {
            val ds = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http).createDataSourceForDownloading()
            val buf = ByteArray(128 * 1024)
            val kept = if (keep) ByteArrayOutputStream(length.toInt()) else null
            val a = now()
            var opened = 0L
            var n = 0L
            var failed: String? = null
            try {
                ds.open(DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build())
                opened = now()
                if (keep) ds.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
                    ?.substringAfter('/', "")?.trim()?.toLongOrNull()?.let { reported = it }
                while (true) {
                    val r = ds.read(buf, 0, buf.size)
                    if (r == C.RESULT_END_OF_INPUT) break
                    if (r > 0) { n += r; kept?.write(buf, 0, r) }
                }
            } catch (e: Exception) {
                failed = e.javaClass.simpleName
            } finally {
                try { ds.close() } catch (e: Exception) {}
                if (keep) head = kept!!.toByteArray()
                // "head 256K @0.91/1.02s": first byte at 0.91 s, done at 1.02 s after the start.
                notes += "$what ${n shr 10}K @${secs((if (opened > 0) opened else now()) - t0)}/${secs(now() - t0)}s" + (failed?.let { " FAILED $it" } ?: "")
            }
        }

        /** Waits for [fs] until [until] (elapsed clock); true when all are done by then. */
        private fun await(fs: List<Future<*>>, until: Long): Boolean {
            for (f in fs) {
                val left = until - now()
                if (left <= 0 && !f.isDone) return false
                try { f.get(left.coerceAtLeast(1), TimeUnit.MILLISECONDS) } catch (e: TimeoutException) { return false } catch (e: Exception) {}
            }
            return true
        }

        private fun setLength(l: Long) {
            len = l
            cache.applyContentMetadataMutations(key, ContentMetadataMutations.setContentLength(ContentMetadataMutations(), l))
        }

        /** Blocking until the head is on disk and the rest is planned; the plan goes on in its own thread. */
        fun begin(startMs: Long, durationMs: Long) {
            for (other in cache.keys.toList()) if (other != key) cache.removeResource(other) // one title at a time
            if (done != key) done = null
            if (len <= 0) len = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            if (len > 0) setLength(len)
            val name = uri.lastPathSegment?.lowercase(Locale.US).orEmpty()
            val mp4Hint = name.endsWith(".mp4") || name.endsWith(".m4v") || name.endsWith(".mov")
            val resume = startMs > 0 && durationMs > 0 && len > 0
            // Everything useful whatever the container goes out at once: the head; the file's end (an MKV's Cues; not
            // for an .mp4, whose moov is either right here or far bigger than a tail); the first media (an MKV's
            // first clusters, an MP4's moov or mdat) or, resuming, a window around where the position should land
            // (proportional: close enough for the extractor's seek most of the time); for an .mp4, two more slices
            // of what is most likely its moov (a WEBRip's is at the front), so they needn't wait for the head.
            val headTask = fetch("head", 0, FAST_HEAD, metadata = true, keep = true)
            var tailAt = -1L
            if (len > FAST_HEAD + FAST_MEDIA + FAST_TAIL && !mp4Hint) { tailAt = len - FAST_TAIL; fetch("tail", tailAt, FAST_TAIL, metadata = true) }
            // Resuming an .mp4: its moov is most often after the mdat (a BluRay encode's), and the seek needs it before
            // anything plays. Its last slices go out now, alongside the head and the resume window, not after the head.
            var moovTail = -1L
            if (mp4Hint && resume && len > FAST_HEAD + 4 * FAST_SLICE) {
                moovTail = len - 2 * FAST_SLICE
                pieces("moov@end?", moovTail, len, metadata = true, piece = FAST_SLICE)
            }
            var mediaEnd = FAST_HEAD
            if (resume) {
                val at = (len.toDouble() * startMs / durationMs).toLong()
                pieces("resume", (at - FAST_PIECE).coerceAtLeast(FAST_HEAD), (at + 2 * FAST_PIECE).coerceAtMost(len), metadata = false)
            } else {
                mediaEnd = FAST_HEAD + FAST_MEDIA
                pieces("media", FAST_HEAD, mediaEnd, metadata = false)
            }
            if (mp4Hint && !resume) { pieces("moov?", mediaEnd, mediaEnd + 2 * FAST_SLICE, metadata = true, piece = FAST_SLICE); mediaEnd += 2 * FAST_SLICE }
            // The head tells the rest: the container, where its Cues or moov are, how big the file really is.
            val headOk = headTask != null && await(listOf(headTask), t0 + FAST_HEAD_MS)
            val h = head
            if (reported > 0 && len > 0 && reported != len) {
                // The size we were told is wrong: the tail went to the wrong place. Let the transfers end, drop it all.
                Log.w(TAG, "Fast start: size mismatch (${len shr 20} vs ${reported shr 20} MB); not used")
                over = true; pool.shutdown()
                try { pool.awaitTermination(FAST_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {}
                cache.removeResource(key)
                return
            }
            if (reported > 0 && len <= 0) setLength(reported)
            if (headOk && h != null && h.size >= 16) when {
                isMkv(h) -> {
                    kind = "MKV"
                    val at = cuesPosition(h)?.takeIf { it > 0 && it < len }
                    cuesAt = at
                    if (at != null) {
                        // Not inside the tail already on its way: the Cues now. Their whole size when their header is in the head
                        // (Cues up front), else the first slice, topped up in finish() once it says how big they are.
                        val whole = if (at + 16 <= h.size) elementSize(h.copyOfRange(at.toInt(), at.toInt() + 16)) + 12 else -1L
                        val want = if (whole > 12) whole.coerceAtMost(CUES_MAX) else minOf(if (tailAt > at) tailAt - at else len - at, CUES_FIRST)
                        if (tailAt < 0 || at < tailAt) fetch("cues", at, want, metadata = true)
                    } else if (tailAt < 0 && len > FAST_HEAD + FAST_MEDIA + FAST_TAIL) {
                        // Nothing up front names the Cues and the size wasn't known when the tail was planned: the file's end
                        // on speculation (a second SeekHead or the Cues themselves are there, as a rule).
                        tailAt = len - FAST_TAIL
                        fetch("tail", tailAt, FAST_TAIL, metadata = true)
                    }
                }
                isMp4(h) -> {
                    kind = "MP4"
                    moovRange(h, len)?.let { (at, size) ->
                        moovEnd = (if (size > 0) at + size else len).coerceAtMost(at + MOOV_MAX)
                        // What the speculative end slices don't cover yet (nothing, when the moov sits inside them).
                        pieces("moov", maxOf(at, mediaEnd), if (moovTail > 0) minOf(moovEnd, moovTail) else moovEnd, metadata = true, piece = FAST_SLICE)
                        // Right after a front moov: the mdat (or a free box left before it) and the box headers finish() needs.
                        if (!resume && size > 0 && moovEnd < len) pieces("after", moovEnd, moovEnd + FAST_MEDIA, metadata = false)
                    }
                }
            }
            val handed = now() - t0
            Thread({ finish(handed, resume) }, "fast-start-plan").apply { isDaemon = true }.start()
        }

        /** After the hand-over: what needs the metadata on disk first, then the summary once every piece is in. */
        private fun finish(handed: Long, resume: Boolean) {
            val metaDone = await(meta.toList(), t0 + FAST_META_MS)
            await(media.toList(), t0 + FAST_META_MS) // the bytes after a moov carry the box headers walked below
            if (metaDone) when (kind) {
                // An MKV's Cues bigger than the first slice (audio cues too, a long film): the rest.
                "MKV" -> cuesAt?.let { at ->
                    val whole = elementSize(read(cache, http, uri, at, 16)) + 12
                    val have = cache.getCachedLength(key, at, whole)
                    if (whole > 12 && whole <= CUES_MAX && have in 1 until whole) fetch("cues+", at + have, whole - have, metadata = true)
                }
                // The media after a front moov: the mdat, past any free box left between them (its headers are on disk; past them, the first box not on disk is taken for the mdat).
                "MP4" -> if (!resume && moovEnd > 0) mdatAt(moovEnd)?.let { at -> pieces("mdat", at, at + FAST_MEDIA, metadata = false) }
            }
            await(meta.toList() + media.toList(), t0 + FAST_META_MS + FAST_TIMEOUT_MS)
            over = true
            pool.shutdown()
            synchronized(lock) { if (fast === this) fast = null }
            val mb = if (len > 0) cache.getCachedBytes(key, 0, len) / 1048576.0 else 0.0
            Log.w(TAG, "Fast start: $kind ${if (len > 0) "${len shr 20} MB" else "size unknown"}, handed over at ${secs(handed)} s, " +
                "${"%.1f".format(Locale.US, mb)} MB on disk by ${secs(now() - t0)} s${if (metaDone) "" else " (metadata NOT all there)"}: " +
                synchronized(notes) { notes.joinToString(" | ") })
        }

        /** The mdat's position walking the boxes from [from] (a moov's end) through the cache: the first one not on disk is taken for it; null when nothing fits. */
        private fun mdatAt(from: Long): Long? {
            var off = from
            repeat(4) {
                if (off >= len || !cache.isCached(key, off, 16)) return if (off < len) off else null
                val b = read(cache, http, uri, off, 16)
                if (b.size < 16) return null
                var size = ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)
                if (size == 1L) { size = 0; repeat(8) { i -> size = (size shl 8) or (b[8 + i].toLong() and 0xFF) } }
                if (box(b, 0) == "mdat") return off
                if (size < 8) return null
                off += size
            }
            return null
        }
    }

    private fun isMkv(h: ByteArray) = h.size >= 4 && (h[0].toInt() and 0xFF) == 0x1A && (h[1].toInt() and 0xFF) == 0x45 && (h[2].toInt() and 0xFF) == 0xDF && (h[3].toInt() and 0xFF) == 0xA3

    private fun box(h: ByteArray, at: Int) = if (at + 8 <= h.size) String(h, at + 4, 4, Charsets.ISO_8859_1) else ""

    private fun isMp4(h: ByteArray) = box(h, 0) in setOf("ftyp", "moov", "mdat", "free", "wide", "skip", "styp")

    /**
     * MP4: where the moov box is (position to size), walking the top-level boxes in [h]: right at the front
     * (size known), or after the mdat (size -1: fetched from there to the file's end, [len]). Null when neither
     * can be told from the head.
     */
    private fun moovRange(h: ByteArray, len: Long): Pair<Long, Long>? {
        var off = 0L
        while (off + 16 <= h.size) {
            val i = off.toInt()
            var size = ((h[i].toLong() and 0xFF) shl 24) or ((h[i + 1].toLong() and 0xFF) shl 16) or ((h[i + 2].toLong() and 0xFF) shl 8) or (h[i + 3].toLong() and 0xFF)
            val type = box(h, i)
            var header = 8L
            if (size == 1L) {
                size = 0; repeat(8) { size = (size shl 8) or (h[i + 8 + it].toLong() and 0xFF) }
                header = 16
            } else if (size == 0L) size = if (len > 0) len - off else -1
            when (type) {
                "moov" -> return off to size
                "mdat" -> return if (size > 0 && len > 0 && off + size + 8 < len) (off + size) to -1L else null
            }
            if (size < header) return null
            off += size
        }
        return null
    }

    /** The player is opening a stream: a prefetch still running stops at once (whatever is cached stays). */
    fun playerOpening() {
        synchronized(lock) { current?.cancel(); current = null }
    }

    /** Stops any prefetch and forgets everything cached (the link was dropped). */
    fun drop(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(lock) { current?.cancel(); current = null; done = null }
        Net.run {
            try {
                val c = get(app)
                for (k in c.keys.toList()) c.removeResource(k)
            } catch (e: Exception) {
                Log.w(TAG, "Stream cache: $e")
            }
        }
    }
}
