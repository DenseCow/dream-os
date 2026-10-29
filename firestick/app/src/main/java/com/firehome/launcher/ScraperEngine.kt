package com.firehome.launcher

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The embedded Python (Chaquopy, Python 3.11) that runs the scraper modules the user installed
 * ([Scrapers]). Started once, in the background (app start / first need, never the main thread),
 * so Play never waits for it: [warm]. Kodi API shims and the runner live in app/src/main/python
 * (dreamhost/, xbmc*.py).
 *
 * A [Job] is one title's scrape: the enabled providers run on a small shared thread pool inside
 * Python ("play" lane for a real Play, "background" for everything else, so a speculative lookup
 * never starves a Play) and every provider's sources stream back as it returns ([Job.items],
 * [Job.await]). Nothing secret crosses into Python: debrid checks happen in Kotlin (NativeResolver).
 */
internal object ScraperEngine {
    private const val TAG = "FireHome"
    const val PLAY_THREADS = 8
    const val BACKGROUND_THREADS = 2

    /** Called from Python (runner.scrape). Kept by R8 (proguard-rules.pro). */
    interface Sink {
        fun results(provider: String, json: String)
        fun done(provider: String, count: Int, ms: Int, error: String?)
    }

    @Volatile private var runner: PyObject? = null
    @Volatile private var failure: String? = null
    @Volatile private var syncedStamp = -1L
    private val starting = AtomicBoolean(false)
    private val startLock = Any()
    private val ids = AtomicInteger()

    /** Python is up with the installed modules loaded. */
    fun ready(): Boolean = runner != null

    /** Python couldn't start on this device (logged once); scrapes then find nothing and the fallbacks run. */
    fun broken(): Boolean = failure != null

    /** Starts Python in the background if a scraper module is installed. Returns at once; any thread. */
    fun warm(ctx: Context) {
        if ((runner != null && syncedStamp == Scrapers.stamp()) || failure != null || !starting.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try { ensure(app) } finally { starting.set(false) }
        }, "scrapers-warm").start()
    }

    /** Blocking: the runner, started and in step with what's installed; null if Python can't start. Never the main thread. */
    fun ensure(ctx: Context): PyObject? {
        runner?.let { r -> if (syncedStamp == Scrapers.stamp()) return r }
        val app = ctx.applicationContext
        synchronized(startLock) {
            failure?.let { return null }
            var r = runner
            if (r == null) {
                val t0 = SystemClock.elapsedRealtime()
                try {
                    if (!Python.isStarted()) Python.start(AndroidPlatform(app))
                    val tPy = SystemClock.elapsedRealtime() - t0
                    r = Python.getInstance().getModule("dreamhost.runner")
                    r.callAttr("init", Scrapers.homeDir(app).absolutePath, tempDir(app).absolutePath, false)
                    sync(app, r)
                    runner = r
                    Log.w(TAG, "Scrapers: python ready in ${SystemClock.elapsedRealtime() - t0} ms (interpreter $tPy ms)")
                } catch (e: Throwable) {
                    failure = e.javaClass.simpleName
                    Log.w(TAG, "Scrapers: python couldn't start (${e.javaClass.simpleName}: ${e.message?.take(200)})")
                    return null
                }
            } else if (syncedStamp != Scrapers.stamp()) {
                try { sync(app, r) } catch (e: Throwable) { Log.w(TAG, "Scrapers: reload failed (${e.message?.take(200)})") }
            }
            return r
        }
    }

    /** The installed modules' code on Python's path, the enabled providers imported ahead (compiled once to __pycache__). */
    private fun sync(app: Context, r: PyObject) {
        val stamp = Scrapers.stamp()
        val t0 = SystemClock.elapsedRealtime()
        r.callAttr("set_modules", JSONArray(Scrapers.libDirs(app).map { it.absolutePath }).toString())
        val enabled = Scrapers.enabledRefs(app)
        val n = r.callAttr("warm", refsJson(enabled)).toInt()
        syncedStamp = stamp
        Log.w(TAG, "Scrapers: $n of ${enabled.size} enabled providers loaded in ${SystemClock.elapsedRealtime() - t0} ms")
    }

    /** Provider settings changed (Dream OS rewrote the modules' settings files): Python forgets what it read. */
    fun settingsChanged() {
        try { runner?.callAttr("reload_settings") } catch (e: Throwable) {}
    }

    /**
     * Dream Player is playing ([PlaybackTrim]): Python gives back idle memory (providers' caches, idle lane
     * threads, garbage); its modules stay loaded, so the next Play starts as fast. Skipped while a scrape
     * runs or before Python is up. Blocking (waits for Python's lock): a background thread only.
     * Dream Core's kept scrapes are trimmed too ([NativeResolver.trimForPlayback]).
     */
    fun trimNow() {
        NativeResolver.trimForPlayback()
        val r = runner ?: return
        try {
            val n = r.callAttr("trim").toInt()
            Log.w(TAG, if (n < 0) "Scrapers: trim skipped (a scrape is running)" else "Scrapers: trimmed for playback ($n objects freed)")
        } catch (e: Throwable) {
            Log.w(TAG, "Scrapers: trim failed (${e.javaClass.simpleName})")
        }
    }

    private fun tempDir(app: Context) = File(app.cacheDir, "scrapers").apply { mkdirs() }

    private fun refsJson(refs: List<Scrapers.Ref>) = JSONArray(refs.map {
        JSONObject().put("id", it.id).put("addon", it.addon).put("file", it.file.absolutePath).put("name", it.name).put("kind", it.kind)
    }).toString()

    // ---- jobs ----

    /** One source a provider found. [hash] for torrents (lower-case hex); hosters carry [host] and [url]. */
    class Item(
        val hash: String?, val name: String, val quality: String, val seeders: Int, val bytes: Long,
        val provider: String, val kind: String, val pack: Boolean, val host: String?, val url: String?,
    )

    /**
     * One title's scrape. Sources arrive while it runs ([items], [version] grows); [await] blocks until
     * something new lands, it ends, or a deadline. [label] is for the log.
     */
    class Job internal constructor(val label: String, val light: Boolean, val total: Int) {
        val startedAt: Long = SystemClock.elapsedRealtime()
        private val lock = Object()
        private val byHash = LinkedHashMap<String, Item>()
        private val hosters = ArrayList<Item>()
        @Volatile var version = 0; private set
        @Volatile var done = false; private set
        /** ms from start to the first source (0 = none yet). */
        @Volatile var firstMs = 0L; private set
        @Volatile var answered = 0; private set
        @Volatile var cancelled = false; private set
        @Volatile private var errors = 0
        /** Ended with every provider failing or timing out (the network, not "nothing out yet"). */
        val failedAll: Boolean get() = done && (answered == 0 || errors >= answered)
        internal var pyId = 0
        private val providersWithResults = HashSet<String>()
        private val slow = ArrayList<String>()

        fun items(): List<Item> = synchronized(lock) { byHash.values.toList() + hosters }

        val count: Int get() = synchronized(lock) { byHash.size + hosters.size }

        /** Waits until a version other than [seen] exists, the job ends, or [until] (elapsedRealtime). True if something changed. */
        fun await(seen: Int, until: Long): Boolean {
            synchronized(lock) {
                while (version == seen && !done) {
                    val left = until - SystemClock.elapsedRealtime()
                    if (left <= 0) return false
                    lock.wait(left)
                }
                return version != seen
            }
        }

        /** Blocks until the job ends or [until]. */
        fun awaitDone(until: Long): Boolean {
            synchronized(lock) {
                while (!done) {
                    val left = until - SystemClock.elapsedRealtime()
                    if (left <= 0) return false
                    lock.wait(left)
                }
                return true
            }
        }

        fun cancel() {
            cancelled = true
            val r = runner ?: return
            // Never on the main thread: the call waits for Python's lock while providers run.
            Thread({ try { r.callAttr("cancel", pyId) } catch (e: Throwable) {} }, "scrapers-cancel").start()
        }

        internal fun add(provider: String, list: List<Item>) {
            synchronized(lock) {
                for (it in list) {
                    if (it.hash == null) { hosters += it; continue }
                    val old = byHash[it.hash]
                    // The same torrent from two providers: keep the one that knows more (size, seeders), singles before packs.
                    if (old == null || (old.pack && !it.pack) || (old.bytes <= 0 && it.bytes > 0) || (old.pack == it.pack && it.seeders > old.seeders && it.bytes > 0)) byHash[it.hash] = it
                }
                if (list.isNotEmpty()) {
                    providersWithResults += provider.substringBefore('#')
                    if (firstMs == 0L) firstMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1)
                }
                version++
                lock.notifyAll()
            }
        }

        internal fun finished(provider: String, count: Int, ms: Int, error: String?) {
            synchronized(lock) {
                answered++
                if (error != null && error != "late") errors++
                if (error != null && error != "late") slow += "${provider.substringAfter(':')} ($error${if (count < 0) ", ${ms / 1000} s" else ""})"
                version++
                lock.notifyAll()
            }
            Scrapers.recordStat(provider.substringBefore('#'), ms, count, error)
        }

        internal fun complete(error: String? = null) {
            // The line is built under the lock: a provider's last results can still be landing ([add]).
            val line = synchronized(lock) {
                done = true
                version++
                lock.notifyAll()
                val ms = SystemClock.elapsedRealtime() - startedAt
                "Scrapers: ${byHash.size + hosters.size} results from ${providersWithResults.size} providers in $ms ms ($label${if (light) ", quick check" else ""}" +
                    "${if (slow.isNotEmpty()) "; no answer: " + slow.take(8).joinToString(", ") else ""}${if (error != null) "; $error" else ""})"
            }
            Log.w(TAG, line)
        }
    }

    /** Public (not private): Python calls it through JNI and reflection. */
    class JobSink(val job: Job) : Sink {
        override fun results(provider: String, json: String) {
            val a = try { JSONArray(json) } catch (e: Exception) { return }
            val out = ArrayList<Item>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val kind = o.optString("k", "torrent")
                out += Item(
                    hash = o.optString("h").takeIf { kind == "torrent" && it.length == 40 },
                    name = o.optString("n"), quality = o.optString("q", "SD"), seeders = o.optInt("s"), bytes = o.optLong("b"),
                    provider = provider.substringBefore('#'), kind = kind, pack = o.optString("pk").isNotEmpty(),
                    host = o.optString("host").ifEmpty { null }, url = o.optString("u").ifEmpty { null },
                )
            }
            job.add(provider, out.filter { it.kind != "torrent" || it.hash != null })
        }

        override fun done(provider: String, count: Int, ms: Int, error: String?) = job.finished(provider, count, ms, error)
    }

    /** Jobs run here (each blocks on Python while its providers run); a Play's own pool never queues behind background ones. */
    private val pool = jobPool(3, "scrapers-job")
    private val playPool = jobPool(2, "scrapers-play")

    private fun jobPool(n: Int, name: String) = ThreadPoolExecutor(n, n, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, name).apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    /**
     * Starts one scrape and returns at once. [info]: the title as scraper modules expect it (Scrapers.info);
     * [light]: the quick check (few fast providers, short limits, no packs); [play]: a real Play (its own lane).
     */
    fun start(ctx: Context, info: JSONObject, refs: List<Scrapers.Ref>, label: String, light: Boolean, play: Boolean,
              timeoutMs: Long, perProviderMs: Long, packs: Boolean): Job {
        val app = ctx.applicationContext
        val job = Job(label, light, refs.size)
        job.pyId = ids.incrementAndGet()
        if (refs.isEmpty()) { job.complete("no providers enabled"); return job }
        (if (play) playPool else pool).execute {
            if (!play) Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                val r = ensure(app)
                if (r == null) { job.complete("python unavailable"); return@execute }
                if (job.cancelled) { job.complete("cancelled"); return@execute }
                val opts = JSONObject().put("timeout", timeoutMs / 1000.0).put("per", perProviderMs / 1000.0)
                    .put("lane", if (play) "play" else "background").put("threads", if (play) PLAY_THREADS else BACKGROUND_THREADS)
                    .put("packs", packs).put("hosts", JSONArray())
                r.callAttr("scrape", job.pyId, info.toString(), refsJson(refs), opts.toString(), JobSink(job))
                job.complete()
            } catch (e: Throwable) {
                job.complete("failed: ${e.javaClass.simpleName} ${e.message?.take(160)}")
            } finally {
                if (!play) Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            }
        }
        return job
    }
}
