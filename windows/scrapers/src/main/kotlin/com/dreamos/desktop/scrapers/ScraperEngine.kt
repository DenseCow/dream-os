package com.dreamos.desktop.scrapers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.Condition
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

/**
 * Runs the providers of the installed scraper modules in the helper process ([ScraperHost]); port of fire-home's
 * ScraperEngine.kt with the iPhone's later fixes.
 *
 * A [Job] is one title's scrape: the enabled providers run on a small shared thread pool inside Python ("play" lane
 * for a Play or look-ahead, "background" for quick checks, "urgent" when a viewer is waiting) and every provider's
 * sources stream back as it returns. Nothing secret crosses into Python: debrid checks are Dream Core's.
 *
 * Rules (from the iPhone's bugs): no lock is held while waiting on Python; [ready] / [broken] read volatile flags
 * only; every wait has a deadline; a job Python never answers is finished for its callers after its time limit plus
 * 15 s and Python told to drop it; a job whose process died ends at once ("python stopped").
 */
class ScraperEngine internal constructor(private val scrapers: Scrapers, private val runtime: PythonRuntime?) {
    companion object {
        const val PLAY_THREADS = 8
        /** A PC has room for four quick-check providers at once (the stick ran two). */
        const val BACKGROUND_THREADS = 4
    }

    private val host: ScraperHost? = runtime?.let { ScraperHost(it, scrapers.paths) }
    private val ensureLock = ReentrantLock()
    @Volatile private var initGeneration = -1
    @Volatile private var syncedStamp = -1L
    @Volatile private var failure: String? = if (runtime == null) "python missing" else null
    private val warming = AtomicBoolean(false)
    private val ids = AtomicInteger()
    private val jobs = ConcurrentHashMap<Int, Job>()

    enum class Lane(val slots: Int) { PLAY(5), BACKGROUND(3) }
    private val gates = Lane.values().associateWith { LaneGate(it.slots) }

    init {
        host?.onEvent = ::event
        host?.onExit = { gen ->
            // Every job still running in that process ends now; its callers don't wait out their deadlines.
            for (j in jobs.values) if (j.generation == gen) j.complete("python stopped")
        }
    }

    /** Python is up with the installed modules loaded. */
    fun ready(): Boolean = host?.alive() == true && initGeneration == host.generation && syncedStamp == scrapers.stamp()

    /** Python can't run here (missing from the install). Scrapes then find nothing. */
    fun broken(): Boolean = failure != null

    /** The helper's process id while it runs (diagnostics, tests). */
    fun pid(): Long? = host?.pid()

    /** Starts Python in the background if a source is installed. Returns at once; any thread. */
    fun warm() {
        if (host == null || ready() || !warming.compareAndSet(false, true)) return
        Thread({ try { ensure() } finally { warming.set(false) } }, "scrapers-warm").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /**
     * Blocking: Python started, the runner set up and in step with what's installed. False if it can't run.
     * Never on the UI thread. Waits at most 45 s for another thread's [ensure].
     */
    fun ensure(): Boolean {
        val h = host ?: return false
        if (ready()) return true
        if (!ensureLock.tryLock(45, TimeUnit.SECONDS)) {
            ScraperLog.w("ensure waited 45 s for another start; going on without it")
            return ready()
        }
        try {
            val gen = h.ensureStarted()
            if (initGeneration != gen) {
                h.call("init", mapOf("home" to JsonPrimitive(scrapers.paths.home.absolutePath),
                    "temp" to JsonPrimitive(scrapers.paths.temp.absolutePath), "verbose" to JsonPrimitive(ScraperLog.verbose)), 30_000)
                initGeneration = gen
                syncedStamp = -1
            }
            val stamp = scrapers.stamp()
            if (syncedStamp != stamp) {
                val t0 = System.nanoTime()
                h.call("set_modules", mapOf("libs" to JsonArray(scrapers.libDirs().map { JsonPrimitive(it.absolutePath) })), 30_000)
                val enabled = scrapers.enabledRefs()
                val n = h.call("warm", mapOf("providers" to refsJson(enabled)), 60_000).jsonPrimitive.intOrNull ?: 0
                syncedStamp = stamp
                ScraperLog.w("$n of ${enabled.size} enabled providers loaded in ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
            return true
        } catch (e: ScraperHost.HostException) {
            ScraperLog.w("python not ready (${e.message})")
            return false
        } finally {
            ensureLock.unlock()
        }
    }

    /** Provider settings changed (their settings files were rewritten): Python forgets what it read. */
    fun settingsChanged() {
        val h = host ?: return
        if (!h.alive()) return
        Thread({ try { h.call("reload_settings", timeoutMs = 10_000) } catch (_: Exception) {} }, "scrapers-settings").apply { isDaemon = true }.start()
    }

    /**
     * Dream Player is playing: Python gives back idle memory (providers' caches, idle lane threads, garbage); its
     * modules stay loaded. Skipped while a scrape runs. Blocking; a background thread only.
     */
    fun trimNow() {
        val h = host ?: return
        if (!h.alive()) return
        try {
            val n = h.call("trim", timeoutMs = 10_000).jsonPrimitive.intOrNull ?: 0
            ScraperLog.w(if (n < 0) "trim skipped (a scrape is running)" else "trimmed for playback ($n objects freed)")
        } catch (e: ScraperHost.HostException) {
            ScraperLog.w("trim failed (${e.message})")
        }
    }

    /** Ends the helper process (app exit, tests). */
    fun shutdown() { host?.stop() }

    internal fun refsJson(refs: List<Scrapers.Ref>): JsonArray = buildJsonArray {
        for (r in refs) add(buildJsonObject {
            put("id", JsonPrimitive(r.id)); put("addon", JsonPrimitive(r.addon)); put("file", JsonPrimitive(r.file.absolutePath))
            put("name", JsonPrimitive(r.name)); put("kind", JsonPrimitive(r.kind))
        })
    }

    // ---- jobs ----

    /** One torrent a provider found (lower-case hex info hash). */
    data class Item(val hash: String, val name: String, val quality: String, val seeders: Int, val bytes: Long,
                    val provider: String, val pack: Boolean)

    /** One title's scrape. Sources arrive while it runs ([items]; [version] grows); [awaitDone] waits for its end. */
    inner class Job internal constructor(val id: Int, val label: String, val light: Boolean, val total: Int) {
        val startedAt: Long = System.currentTimeMillis()
        private val lock = Any()
        private val byHash = LinkedHashMap<String, Item>()
        private val versionFlow = MutableStateFlow(0)
        /** Grows with every change; a collector sees the latest. */
        val version: StateFlow<Int> get() = versionFlow
        @Volatile var done = false; private set
        @Volatile var cancelled = false; private set
        @Volatile var urgent = false; private set
        @Volatile var firstMs = 0L; private set
        @Volatile var answered = 0; private set
        @Volatile private var errors = 0
        @Volatile internal var generation = -1
        private val withResults = HashSet<String>()
        private val slow = ArrayList<String>()
        private val doneLatch = java.util.concurrent.CountDownLatch(1)

        /** Ended with every provider failing or timing out (the network, not "nothing out yet"), or without Python. */
        val failedAll: Boolean get() = done && (answered == 0 || errors >= answered)

        fun items(): List<Item> = synchronized(lock) { byHash.values.toList() }
        val count: Int get() = synchronized(lock) { byHash.size }

        /** Blocks until the job ends or [timeoutMs] passes. True if it ended. */
        fun awaitDone(timeoutMs: Long): Boolean = doneLatch.await(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)

        /** Suspends until the job ends or [timeoutMs] passes. True if it ended. */
        suspend fun awaitDoneSuspending(timeoutMs: Long): Boolean {
            if (done) return true
            return withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) { versionFlow.first { done }; true } ?: done
        }

        fun cancel() {
            if (cancelled) return
            cancelled = true
            host?.send("cancel", mapOf("job" to JsonPrimitive(id)))
            gates.values.forEach { it.poke() }
        }

        /** A Play joined this scrape: it takes a lane slot at once if it is still waiting for one. */
        fun promote() {
            if (urgent) return
            urgent = true
            gates.values.forEach { it.poke() }
        }

        internal fun add(provider: String, list: List<Item>) {
            synchronized(lock) {
                if (done) return
                for (it in list) {
                    val old = byHash[it.hash]
                    // The same torrent from two providers: keep the one that knows more (size, seeders), singles before packs.
                    if (old == null || (old.pack && !it.pack) || (old.bytes <= 0 && it.bytes > 0) ||
                        (old.pack == it.pack && it.seeders > old.seeders && it.bytes > 0)) byHash[it.hash] = it
                }
                if (list.isNotEmpty()) {
                    withResults += provider.substringBefore('#')
                    if (firstMs == 0L) firstMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
                }
                versionFlow.value = versionFlow.value + 1
            }
        }

        internal fun finished(provider: String, count: Int, ms: Int, error: String?) {
            synchronized(lock) {
                if (done) return
                answered++
                if (error != null && error != "late") {
                    errors++
                    slow += "${provider.substringAfter(':')} ($error${if (count < 0) ", ${ms / 1000} s" else ""})"
                }
                versionFlow.value = versionFlow.value + 1
            }
        }

        internal fun complete(error: String? = null) {
            // The line is built under the lock: a provider's last results can still be landing (add). Nothing else
            // is called under it, so a finished job can't hold up the reader thread.
            val line = synchronized(lock) {
                if (done) return
                done = true
                versionFlow.value = versionFlow.value + 1
                val ms = System.currentTimeMillis() - startedAt
                "${byHash.size} results from ${withResults.size} providers in $ms ms ($label${if (light) ", quick check" else ""}" +
                    "${if (slow.isNotEmpty()) "; no answer: " + slow.take(8).joinToString(", ") else ""}${if (error != null) "; $error" else ""})"
            }
            doneLatch.countDown()
            jobs.remove(id)
            ScraperLog.w(line)
        }
    }

    /** From the host: one provider's sources or its end. */
    private fun event(o: JsonObject) {
        val id = o["job"]?.jsonPrimitive?.intOrNull ?: return
        val job = jobs[id] ?: return
        val provider = o["p"]?.jsonPrimitive?.contentOrNull ?: return
        when (o["ev"]?.jsonPrimitive?.contentOrNull) {
            "r" -> {
                val arr = o["items"] as? JsonArray ?: return
                val base = provider.substringBefore('#')
                val out = ArrayList<Item>(arr.size)
                for (e in arr) {
                    val x = e as? JsonObject ?: continue
                    if (x.str("k") != "torrent") continue   // hosters are dropped, as on the stick (no hoster unlock in Dream Core)
                    val h = x.str("h") ?: continue
                    if (h.length != 40) continue
                    out += Item(h, x.str("n").orEmpty(), x.str("q") ?: "SD", x["s"]?.jsonPrimitive?.intOrNull ?: 0,
                        x["b"]?.jsonPrimitive?.longOrNull ?: 0L, base, !x.str("pk").isNullOrEmpty())
                }
                job.add(provider, out)
            }
            "d" -> {
                val n = o["n"]?.jsonPrimitive?.intOrNull ?: 0
                val ms = o["ms"]?.jsonPrimitive?.intOrNull ?: 0
                val err = o["err"]?.let { if (it is JsonPrimitive && it.isString) it.content else null }
                job.finished(provider, n, ms, err)
                // A cancelled scrape says nothing about the provider's speed or hit rate.
                if (err != "cancelled") scrapers.recordStat(provider.substringBefore('#'), ms, n, err)
            }
        }
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * Starts one scrape and returns at once. [info]: the title as scraper modules expect it ([ScraperInfo]);
     * [light]: the quick check (few fast providers, short limits, no packs); [play]: the Play lane (also
     * look-aheads'); [urgent]: a viewer is waiting: never waits for a slot, and runs on its own Python pool.
     */
    fun start(info: JsonObject, refs: List<Scrapers.Ref>, label: String, light: Boolean, play: Boolean, urgent: Boolean,
              timeoutMs: Long, perProviderMs: Long, packs: Boolean): Job {
        val job = Job(ids.incrementAndGet(), label, light, refs.size)
        if (urgent) job.promote()
        if (refs.isEmpty()) { job.complete("no providers enabled"); return job }
        if (host == null) { job.complete("python unavailable"); return job }
        jobs[job.id] = job
        val lane = if (play) Lane.PLAY else Lane.BACKGROUND
        val gate = gates.getValue(lane)
        val refsJson = refsJson(refs)
        Thread({
            // A slot: a Play waits up to 30 s (something is badly stuck by then), a check only as long as its own limit.
            val gotSlot = gate.acquire(System.currentTimeMillis() + if (play) 30_000 else timeoutMs + 8_000) { job.urgent || job.cancelled }
            if (!gotSlot) { job.complete("busy: no ${lane.name.lowercase()} slot in time"); return@Thread }
            try {
                if (job.cancelled || job.done) { job.complete("cancelled"); return@Thread }
                if (!ensure()) { job.complete("python unavailable"); return@Thread }
                if (job.cancelled || job.done) { job.complete("cancelled"); return@Thread }
                job.generation = host.generation
                val opts = buildJsonObject {
                    put("timeout", JsonPrimitive(timeoutMs / 1000.0)); put("per", JsonPrimitive(perProviderMs / 1000.0))
                    put("lane", JsonPrimitive(if (job.urgent) "urgent" else lane.name.lowercase()))
                    put("threads", JsonPrimitive(if (play) PLAY_THREADS else BACKGROUND_THREADS))
                    put("packs", JsonPrimitive(packs)); put("hosts", JsonArray(emptyList()))
                }
                val fields: Map<String, JsonElement> = mapOf("job" to JsonPrimitive(job.id), "info" to info, "providers" to refsJson, "opts" to opts)
                // Python bounds a scrape by its timeout; 15 s past that the job is finished for its callers anyway.
                host.call("scrape", fields, timeoutMs + 15_000)
                job.complete()
            } catch (e: ScraperHost.HostException) {
                if (e.message?.contains("timed out") == true) {
                    ScraperLog.w("job ${job.id} ${job.label} stuck in python; released")
                    job.complete("stuck")
                    host.send("cancel", mapOf("job" to JsonPrimitive(job.id)))
                } else job.complete("failed: ${e.message?.take(160)}")
            } finally {
                gate.release()
                jobs.remove(job.id)
            }
        }, "scrapers-job-${job.id}").apply { isDaemon = true; if (!play) priority = Thread.NORM_PRIORITY - 1 }.start()
        return job
    }
}

/**
 * A lane's slots: a counting gate with deadlines. An urgent caller takes one at once even when none is free (the
 * count goes below zero and later callers wait until it is given back); urgency is read again on every wake-up.
 */
internal class LaneGate(slots: Int) {
    private val lock = ReentrantLock()
    private val cond: Condition = lock.newCondition()
    private var free = slots

    fun acquire(deadline: Long, urgent: () -> Boolean): Boolean = lock.withLock {
        while (free <= 0 && !urgent()) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return false
            cond.await(minOf(left, 300), TimeUnit.MILLISECONDS)
        }
        free--
        true
    }

    fun release() = lock.withLock { free++; cond.signal() }

    fun poke() = lock.withLock { cond.signalAll() }
}
