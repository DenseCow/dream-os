package com.firehome.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.http.HttpResponseCache
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Small HTTP + image helper so the launcher doesn't need any libraries. */
object Net {
    // Threads exit after 30 s idle, so a launcher sitting behind the player for hours holds none.
    private val io = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, LinkedBlockingQueue()).apply {
        allowCoreThreadTimeOut(true)
    }

    init {
        // The platform's HTTP stack keeps only 5 idle connections (all hosts together) by default: with a page's
        // row lists, card art and the hero all in flight, most connections closed after one request and the next
        // paid a new TLS handshake (a few hundred ms on this stick). The pool reads this once, when it is made, so
        // it is set before the first connection (installHttpCache, first thing in onCreate, touches Net).
        if (System.getProperty("http.maxConnections") == null) System.setProperty("http.maxConnections", "16")
    }

    /**
     * Row lists (a page's lazy rows, [load]) on their own 3 threads, nearest-to-focus first ([Task.rank], which
     * the page re-ranks on every focus move, then [resortLoads]): the row focus is heading for never waits behind
     * rows further down, nor behind the shared [io] queue (logos, prefetches, trailers). Each list fans out its
     * own TMDB requests ([RowPaging.all]), so three at once keep the network busy.
     */
    private val lists = ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, PriorityBlockingQueue<Runnable>()) { r ->
        Thread(r, "net-rows")
    }.apply {
        allowCoreThreadTimeOut(true)
    }
    private val taskSeq = java.util.concurrent.atomic.AtomicLong()

    /** A queued row list ([load]). [rank]: lower first (distance from focus); [cancel] drops it if it hasn't started. */
    class Task internal constructor(@Volatile var rank: Int, private val work: () -> Unit) : Runnable, Comparable<Task> {
        private val seq = taskSeq.incrementAndGet()
        private val state = java.util.concurrent.atomic.AtomicInteger(0) // 0 waiting, 1 started, 2 cancelled
        /** When it was queued, and when a thread took it (uptime ms; 0 until then). */
        val queuedAt = android.os.SystemClock.uptimeMillis()
        @Volatile var startedAt = 0L
            private set
        override fun run() {
            if (!state.compareAndSet(0, 1)) return
            startedAt = android.os.SystemClock.uptimeMillis()
            work()
        }
        /** True if it was still waiting: it never runs, and its done never comes. */
        fun cancel(): Boolean = state.compareAndSet(0, 2)
        val waiting: Boolean get() = state.get() == 0
        override fun compareTo(other: Task) = if (rank != other.rank) rank.compareTo(other.rank) else seq.compareTo(other.seq)
    }

    /** Runs [work] on the row-list threads at [rank]; [done] gets its result (null on an exception) on the main thread. */
    fun <T> load(rank: Int, work: () -> T, done: (T?) -> Unit): Task {
        val task = Task(rank) {
            val result = try { work() } catch (e: Exception) { null }
            main.post { done(result) }
        }
        lists.execute(task)
        return task
    }

    /** Re-sorts the waiting row lists after their [Task.rank]s changed (the queue only orders on insertion). */
    fun resortLoads() {
        if (lists.queue.isEmpty()) return
        val waiting = ArrayList<Runnable>()
        lists.queue.drainTo(waiting)
        for (t in waiting) if ((t as Task).waiting) lists.execute(t)
    }

    /**
     * Card art has its own 4 threads, nearest-to-focus ([rank]) then newest request first: after a
     * fast scroll, the rows on screen now load before the ones already scrolled past (which are
     * usually cancelled anyway, see [cancel]), and JSON row loads on [io] never wait behind a queue
     * of images. The threads sit a notch below the UI thread in priority (still the foreground
     * group), so a burst of decodes never steals a frame from a scroll.
     */
    private val img = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, PriorityBlockingQueue<Runnable>()) { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT + 5); r.run() }, "net-img")
    }.apply {
        allowCoreThreadTimeOut(true)
    }

    /** Requests made in one main-thread message share a batch: newer batches first, in order inside one. */
    private var batch = 0L
    private var batchOpen = false
    private var seq = 0L

    /**
     * A queued decode. [rank] is how far its row is from focus (0 = nearest, or no page to ask):
     * lower first, then the newer batch. It can change after queuing ([reorder]), which re-sorts the
     * queue; the queue itself only orders on insertion.
     */
    private class Job(val batch: Long, val seq: Long, @Volatile var rank: Int, val work: () -> Unit) : Runnable, Comparable<Job> {
        override fun run() = work()
        override fun compareTo(other: Job) = when {
            rank != other.rank -> rank.compareTo(other.rank)
            batch != other.batch -> other.batch.compareTo(batch)
            else -> seq.compareTo(other.seq)
        }
    }

    @Synchronized private fun submitImage(req: Req?, work: () -> Unit) {
        if (!batchOpen) { batchOpen = true; batch++; main.post { closeBatch() } }
        val job = Job(batch, seq++, req?.rank ?: 0, work)
        req?.job = job
        img.execute(job)
    }

    @Synchronized private fun closeBatch() { batchOpen = false }

    /** Starts a new, more urgent batch: requests after this load before everything already queued. */
    @Synchronized fun urgent() {
        batch++
        if (!batchOpen) { batchOpen = true; main.post { closeBatch() } }
    }

    /**
     * Re-ranks every art load still waiting: [rank] gives a view's new urgency (lower first), or null
     * to leave it. Newest-first alone starves a fast scroll: each step queues the row entering the
     * art window, so the row farthest ahead always jumps the one about to come on screen. The page
     * calls this on every focus move with each row's distance from focus. Main thread only.
     */
    fun reorder(rank: (ImageView) -> Int?) {
        var changed = false
        for ((view, req) in requests) {
            val r = rank(view) ?: continue
            req.rank = r
            val job = req.job ?: continue
            if (job.rank != r) { job.rank = r; changed = true }
        }
        if (!changed) return
        // Only what is still queued moves; a decode in progress just finishes.
        val waiting = ArrayList<Runnable>()
        img.queue.drainTo(waiting)
        for (j in waiting) img.execute(j)
    }

    /** A view's art load in flight; [cancel] marks it so the worker skips it (main thread only). */
    private class Req {
        @Volatile var cancelled = false
        @Volatile var job: Job? = null
        /** Carried over to the retry's job. */
        @Volatile var rank = 0
        /** For [ArtTiming]: when it was asked for (main thread) and when a worker took it, then its download and decode. */
        val askedAt = android.os.SystemClock.uptimeMillis()
        @Volatile var startedAt = 0L
        @Volatile var downloadMs = 0L
        @Volatile var decodeMs = 0L
    }

    /** Where one card's art spent its time: waiting for a worker, downloading (0: from the disk cache), decoding; [memory]: none of it. */
    class ArtTiming(val waitMs: Long, val downloadMs: Long, val decodeMs: Long, val memory: Boolean)

    /** The timing of the art whose onLoaded is running right now (main thread; null outside an onLoaded). */
    var lastTiming: ArtTiming? = null
        private set
    private val requests = java.util.WeakHashMap<ImageView, Req>()

    /** Drops [view]'s art load in flight, if any: a row that let go of its art doesn't fetch it anyway. */
    fun cancel(view: ImageView) { requests.remove(view)?.cancelled = true }

    /** True while [view] waits for its art. */
    fun loading(view: ImageView) = requests.containsKey(view)

    /** Shared pool for short blocking side jobs (shell checks, key injection, history writes) that must not
     *  queue behind image loads. Up to 3 threads, idle ones exit after 30 s; overflow runs on a fresh daemon. */
    val misc: ExecutorService = ThreadPoolExecutor(0, 3, 30, TimeUnit.SECONDS, SynchronousQueue()) { r ->
        Thread(r, "net-misc").apply { isDaemon = true }
    }.apply {
        rejectedExecutionHandler = java.util.concurrent.RejectedExecutionHandler { r, _ -> Thread(r, "net-misc-x").apply { isDaemon = true }.start() }
    }

    /** Runs [work] on [misc]; exceptions are swallowed like an ad-hoc Thread's would be. */
    fun run(work: () -> Unit) = misc.execute { try { work() } catch (e: Exception) { Log.w("Net", "background job failed: $e") } }
    private val main = Handler(Looper.getMainLooper())

    // RGB_565 bitmaps, so ~2 bytes per pixel. The stick only has 1.7 GB of RAM.
    // One 30 MB budget in two parts: card art (~100 KB each) and full-screen backdrops (~1.8 MB each at
    // w1280), so a few focus moves across rows never flush the cards of the page you just left. The
    // card part holds a whole art window (11 rows of up to 20 cards), so
    // scrolling back over rows you just left shows their art at once instead of decoding it again.
    private val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val large = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun cached(url: String): Bitmap? = memory.get(url) ?: large.get(url)

    private fun keep(url: String, bmp: Bitmap) {
        if (bmp.byteCount >= LARGE_BYTES) large.put(url, bmp) else memory.put(url, bmp)
    }

    /** Drops every decoded bitmap. Views keep the ones they show; the rest reload from the disk cache. */
    fun evictMemory() {
        memory.evictAll()
        large.evictAll()
    }

    /** "cards 12.3/18 MB (110), backdrops 3.6/6 MB (2)" for the memory report. */
    fun memoryStats(): String {
        fun mb(b: Int) = "%.1f".format(b / 1048576f)
        return "cards ${mb(memory.size())}/${mb(memory.maxSize())} MB (${memory.snapshot().size}), " +
            "backdrops ${mb(large.size())}/${mb(large.maxSize())} MB (${large.snapshot().size})"
    }

    /**
     * A page went off screen: its art views let go of their bitmaps (each keeps its url in its tag),
     * so only the cache above holds them and it can evict them. [rebind] puts them back (instantly
     * from memory, else from the disk cache with the usual fade) when the page shows again.
     */
    fun unbind(root: View) {
        if (root is ImageView) {
            val url = root.tag as? String
            // A load still in flight is dropped too (and marked, so [rebind] starts it again).
            if (url != null && url.startsWith("http") && (root.drawable != null || loading(root))) {
                cancel(root)
                root.animate().cancel()
                root.alpha = 1f
                root.setImageDrawable(null)
                unbound[root] = true
            }
        } else if (root is ViewGroup) {
            for (i in 0 until root.childCount) unbind(root.getChildAt(i))
        }
    }

    /** Views [unbind] emptied (main thread only; weak, so released pages just drop out). */
    private val unbound = java.util.WeakHashMap<ImageView, Boolean>()

    fun rebind(ctx: Context, root: View) {
        if (root is ImageView) {
            val url = root.tag as? String
            if (unbound.remove(root) != null) {
                if (url != null && root.drawable == null) image(ctx, root, url)
            }
        } else if (root is ViewGroup) {
            for (i in 0 until root.childCount) rebind(ctx, root.getChildAt(i))
        }
    }

    /** 8 MB HTTP cache for JSON (TMDB sends ETag/max-age), used only by [getText]: every other connection
     *  (image downloads that already have their own disk cache) defaults to no caching. */
    fun installHttpCache(ctx: Context) {
        if (HttpResponseCache.getInstalled() != null) return
        try {
            HttpResponseCache.install(File(ctx.cacheDir, "http"), 8L * 1024 * 1024)
            URL("http://127.0.0.1/").openConnection().defaultUseCaches = false // static default for all connections
        } catch (e: Exception) { Log.w("Net", "http cache unavailable: $e") }
    }

    fun <T> background(work: () -> T, done: (T?) -> Unit) {
        io.execute {
            val result = try { work() } catch (e: Exception) { null }
            main.post { done(result) }
        }
    }

    fun getText(url: String): String {
        val tmdb = url.startsWith("https://api.themoviedb.org/")
        var attempt = 0
        while (true) {
            if (tmdb) TmdbGate.enter()
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.useCaches = true // the only cached path, see installHttpCache
            try {
                val code = conn.responseCode
                // TMDB answers 429 when many requests land at once: wait out its pause and ask again (twice), so a row
                // never disappears for it.
                if (tmdb && code == 429 && attempt < 2) {
                    attempt++
                    TmdbGate.backOff(maxOf(conn.getHeaderField("Retry-After")?.toDoubleOrNull() ?: 0.0, 0.6 * attempt))
                    continue
                }
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * TMDB's request pacing (iPhone's TmdbGate). TMDB allows roughly 40-50 requests a second per address; past that it
     * answers 429 and the request is lost. Requests wait for a free slot in the current second (rarely more than a
     * moment: a page asks a few dozen lists at most), and after a 429 everyone waits out the pause. Blocking: network
     * threads only.
     */
    private object TmdbGate {
        private const val PER_SECOND = 35
        private val stamps = java.util.ArrayDeque<Long>()
        private var pausedUntil = 0L

        fun enter() {
            while (true) {
                val wait = synchronized(this) {
                    val now = android.os.SystemClock.uptimeMillis()
                    while (stamps.isNotEmpty() && now - stamps.peekFirst()!! >= 1000) stamps.pollFirst()
                    if (now >= pausedUntil && stamps.size < PER_SECOND) { stamps.addLast(now); return }
                    maxOf(pausedUntil - now, stamps.peekFirst()?.let { 1000 - (now - it) } ?: 50L)
                }
                Thread.sleep(wait.coerceIn(20L, 1000L))
            }
        }

        fun backOff(seconds: Double) {
            synchronized(this) {
                val until = android.os.SystemClock.uptimeMillis() + (seconds * 1000).toLong()
                if (until > pausedUntil) pausedUntil = until
            }
            Log.w("Net", "TMDB: rate limited, pausing requests ${"%.1f".format(seconds)} s")
        }
    }

    fun getJson(url: String) = JSONObject(getText(url))

    /** Fetches a bitmap through the same memory/disk caches, without a view. */
    fun bitmap(ctx: Context, url: String, done: (Bitmap?) -> Unit) {
        cached(url)?.let { done(it); return }
        val cacheDir = imgDir(ctx)
        background({ loadBitmap(cacheDir, url) }) { bmp ->
            if (bmp == null) { done(null); return@background }
            keep(url, bmp)
            if (bmp.byteCount < LARGE_BYTES) { done(bmp); return@background }
            // A backdrop (1.8 MB at w1280) handed over at once is uploaded inside the frame that first draws
            // it, with the UI thread waiting on the sync. Its upload starts now on the render thread, and it
            // is handed over a frame later, by when it is a texture already.
            bmp.prepareToDraw()
            android.view.Choreographer.getInstance().postFrameCallback { main.post { done(bmp) } }
        }
    }

    /** The disk cache folder, created on the loading thread when an image is first written: no file checks on the main thread per card. */
    @Volatile private var imgDir: File? = null
    private fun imgDir(ctx: Context): File = imgDir ?: File(ctx.cacheDir, "img").also { imgDir = it }

    /**
     * Loads [url] into [view], via memory and disk caches. Ignores stale results on recycled views.
     * A network error is retried once; if the art doesn't exist (or keeps failing) the next of
     * [fallbacks] is tried, so a card only stays on its name when there's no art at all.
     * [rank] orders the decode (see [Job]); a page's rows use their distance from focus.
     */
    fun image(ctx: Context, view: ImageView, url: String?, fallbacks: List<String> = emptyList(), rank: Int = 0, onLoaded: ((Bitmap) -> Unit)? = null) {
        cancel(view)
        view.tag = url
        if (url == null) return
        cached(url)?.let {
            // A card's art from memory shows at once, even if a fade of an earlier image was cut short.
            if (view is ArtView) { view.animate().cancel(); view.alpha = 1f }
            view.setImageBitmap(it)
            // A row getting its art back ahead of focus (ArtWindow.restore) is usually not drawn yet: its
            // textures go up on the render thread now, not all twenty in the frame the row comes back in.
            it.prepareToDraw()
            if (onLoaded != null) {
                lastTiming = MEMORY_HIT
                try { onLoaded(it) } finally { lastTiming = null }
            }
            return
        }
        view.setImageDrawable(null)
        val req = Req().also { it.rank = rank; requests[view] = it }
        val cacheDir = imgDir(ctx)
        fun show(bmp: Bitmap, timing: Req) {
            // Art that lands on a card the user can see arrived late: an eased fade, so it settles in
            // rather than pops. Art for a row still off screen (or a hidden page) is simply there when
            // the row scrolls in, with no fade running out of sight.
            val late = onScreen(view)
            if (view is ArtView) view.animate().cancel()
            view.alpha = if (late) 0f else 1f
            view.setImageBitmap(bmp)
            if (late) view.animate().alpha(1f).setDuration(Ui.FADE_IMAGE).setInterpolator(Ui.EASE).start()
            if (onLoaded != null) {
                lastTiming = ArtTiming(maxOf(0L, timing.startedAt - timing.askedAt), timing.downloadMs, timing.decodeMs, memory = false)
                try { onLoaded(bmp) } finally { lastTiming = null }
            }
        }
        // Card art never sits blank behind one stalled download (the iPhone's hedge): once this choice has been
        // downloading [HEDGE_MS] (time waiting for a worker doesn't count), the next choice is asked too and the
        // first that arrives shows. A failed choice goes straight to the next one; only the last is retried.
        val next = fallbacks.firstOrNull()
        var hedge: Req? = null
        var primaryFailed = false
        var hedgeFailed = false
        fun nextAfterBoth() {
            requests.remove(view)
            if (fallbacks.size > 1) image(ctx, view, fallbacks[1], fallbacks.drop(2), req.rank, onLoaded)
        }
        val startHedge = Runnable {
            if (next == null || hedge != null || requests[view] !== req || req.cancelled || view.tag != url) return@Runnable
            val h = Req().also { it.rank = req.rank }
            hedge = h
            Log.w("Net", "image slow, asking the next choice too: $url")
            fetch(cacheDir, next, h, retry = false) { bmp ->
                if (requests[view] !== req || view.tag != url) return@fetch
                if (bmp != null) { requests.remove(view); req.cancelled = true; view.tag = next; show(bmp, h); return@fetch }
                hedgeFailed = true
                if (primaryFailed) nextAfterBoth()
            }
        }
        fetch(cacheDir, url, req, retry = next == null, onDownload = if (next == null) null else ({ main.postDelayed(startHedge, HEDGE_MS) })) { bmp ->
            main.removeCallbacks(startHedge)
            if (requests[view] !== req) return@fetch // cancelled, or the view asked for other art since
            if (view.tag != url) { requests.remove(view); return@fetch }
            if (bmp != null) { requests.remove(view); hedge?.cancelled = true; show(bmp, req); return@fetch }
            primaryFailed = true
            when {
                hedge == null -> { requests.remove(view); if (next != null) image(ctx, view, next, fallbacks.drop(1), req.rank, onLoaded) }
                hedgeFailed -> nextAfterBoth()
                else -> {} // the next choice is on its way and shows when it lands
            }
        }
    }

    private val onScreenRect = android.graphics.Rect()

    /**
     * True if any part of [v] is within the screen right now: attached and not scrolled away. A view
     * that is hidden for the moment (a logo shown once it loads) counts as on screen.
     */
    private fun onScreen(v: View): Boolean {
        if (!v.isAttachedToWindow) return false
        // Card art in a row that isn't drawn for now (pages hide rows that faded out) is simply there
        // when the row shows: a fade nobody sees would still redraw the screen for its whole length.
        if (v is ArtView) {
            var p: Any? = v.parent
            while (p is View) { if (p.visibility != View.VISIBLE) return false; p = p.parent }
        }
        return v.visibility != View.VISIBLE || v.getGlobalVisibleRect(onScreenRect)
    }

    /** Decodes [url] on [img] (skipped if [req] was cancelled by then); [done] runs on the main thread. */
    private fun fetch(cacheDir: File, url: String, req: Req, retry: Boolean, onDownload: (() -> Unit)? = null, done: (Bitmap?) -> Unit) {
        submitImage(req) {
            var failed = false
            if (req.startedAt == 0L) req.startedAt = android.os.SystemClock.uptimeMillis()
            val bmp = if (req.cancelled) null else try {
                loadBitmap(cacheDir, url, req, onDownload)
            } catch (e: Exception) {
                failed = true
                Log.w("Net", "image failed (${if (retry) "will retry" else "gave up"}): $url: $e")
                null
            }
            main.post {
                if (bmp != null) {
                    keep(url, bmp)
                    // Texture upload on the render thread now, not in the frame the row first shows
                    // (a whole row of fresh art uploaded in one frame is a dropped frame on this GPU).
                    bmp.prepareToDraw()
                }
                if (failed && retry && !req.cancelled) main.postDelayed({
                    if (req.cancelled) done(null) else fetch(cacheDir, url, req, retry = false, done = done)
                }, RETRY_MS)
                else done(bmp)
            }
        }
    }

    private fun loadBitmap(cacheDir: File, url: String, req: Req? = null, onDownload: (() -> Unit)? = null): Bitmap? {
        val file = File(cacheDir, sha1(url))
        if (!file.exists()) {
            val t0 = android.os.SystemClock.uptimeMillis()
            onDownload?.invoke() // a real download starts (not the disk cache): the card's hedge timer runs from here
            cacheDir.mkdirs() // here, off the main thread (and back again if the system cleared the cache)
            val conn = URL(url).openConnection() as HttpURLConnection
            // A stalled connection gives up in 6 s (was 8 + 15) so the card's next choice can take over.
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.useCaches = false // stored in our own disk cache already
            try {
                if (conn.responseCode !in 200..299) { Log.w("Net", "image HTTP ${conn.responseCode}: $url"); return null }
                val tmp = File(cacheDir, file.name + ".tmp")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(file)
            } finally {
                conn.disconnect()
            }
            req?.downloadMs = android.os.SystemClock.uptimeMillis() - t0
            trimDisk(cacheDir, file.length())
        }
        val t1 = android.os.SystemClock.uptimeMillis()
        // Never bigger than the screen needs: TMDB art is already sized by its URL (w185/w300/w1280),
        // but other art (fanart) can be 4K. Bounds first, then a power-of-2 sample.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_W || bounds.outHeight / (sample * 2) >= MAX_H) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565 // opaque art; PNGs with alpha still decode as ARGB
            inSampleSize = sample
        }
        // A file that won't decode (cut short, corrupt) goes, so the next request downloads it again.
        val bmp = BitmapFactory.decodeFile(file.path, opts)
        req?.decodeMs = android.os.SystemClock.uptimeMillis() - t1
        if (bmp == null) file.delete()
        return bmp
    }

    /** The disk cache's size as far as this process knows (bytes; -1 until first counted). */
    private var diskBytes = -1L

    /**
     * Keeps the disk cache under [DISK_LIMIT]. It used to list and stat every file in it (thousands) after each
     * download, on the art threads: tens of ms per card and a heap of garbage while a page fills. Now the folder
     * is counted once, each download adds its size, and only going over the limit lists it again (oldest go).
     */
    private val diskLock = Any()

    /** Its own lock: [submitImage] holds Net's on the main thread, and a trim lists the whole folder. */
    private fun trimDisk(dir: File, added: Long) = synchronized(diskLock) {
        if (diskBytes >= 0) {
            diskBytes += added
            if (diskBytes < DISK_LIMIT) return@synchronized
        }
        val files = dir.listFiles() ?: return@synchronized
        val sizes = LongArray(files.size) { files[it].length() }
        var total = sizes.sum()
        if (total >= DISK_LIMIT) {
            val stamps = LongArray(files.size) { files[it].lastModified() }
            for (i in files.indices.sortedBy { stamps[it] }) {
                if (files[i].delete()) total -= sizes[i]
                if (total < DISK_LIMIT * 0.8) break
            }
        }
        diskBytes = total
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun sha1(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        val out = CharArray(d.size * 2)
        for ((i, b) in d.withIndex()) { out[2 * i] = HEX[(b.toInt() shr 4) and 15]; out[2 * i + 1] = HEX[b.toInt() and 15] }
        return String(out)
    }

    private val MEMORY_HIT = ArtTiming(0, 0, 0, memory = true)

    /** Wait before the one retry of an art download that failed on the network. */
    private const val RETRY_MS = 1500L
    /** A card's art downloading this long without arriving: its next choice is asked too (first to land shows). */
    private const val HEDGE_MS = 1500L
    private const val DISK_LIMIT = 80L * 1024 * 1024
    /** Decoded art is at least this big before it is subsampled (a w1280 backdrop is never touched). */
    private const val MAX_W = 1280
    private const val MAX_H = 720
    /** Bitmaps from this size up go to the backdrop part of the cache. */
    private const val LARGE_BYTES = 1024 * 1024
}
