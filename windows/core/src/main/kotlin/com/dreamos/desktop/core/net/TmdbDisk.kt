package com.dreamos.desktop.core.net

import com.dreamos.desktop.core.util.Log
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * TMDB's JSON answers on disk (the iPhone's TmdbDisk), so a relaunch paints Home from disk instead of asking again.
 * One file per request URL (`<cache>\tmdb\<sha256 of the URL>.json`; the URL carries the viewer's key, so a new key
 * never reads the old key's answers). Each kind of answer keeps its own lifetime ([ttlMs]).
 *
 * Lists (charts, discover, search, recommendations) are served stale-while-revalidate: an answer past its lifetime but
 * younger than [MAX_AGE_MS] comes back at once (marked [Answer.stale]) and a fresh copy is fetched behind, so rows never
 * wait on the network at launch. Objects (a title's details, a season, a person) are fetched again once past their
 * lifetime; an older copy is used only when TMDB can't be reached.
 *
 * Bounds: files older than [MAX_AGE_MS] go; past [MAX_BYTES] the oldest go until [PRUNE_TO_BYTES] (checked when opened
 * and every [PRUNE_EVERY] writes). Worst case on disk: 256 MB (a busy week of browsing is a few tens of MB).
 */
object TmdbDisk {
    const val MAX_AGE_MS = 7L * 86_400_000L
    const val MAX_BYTES = 256L shl 20
    private const val PRUNE_TO_BYTES = 192L shl 20
    private const val PRUNE_EVERY = 512
    private const val HOUR = 3_600_000L

    /** One answer: its text, and whether it is past its lifetime (a fresh copy is on its way). */
    class Answer(val body: String, val stale: Boolean)

    @Volatile private var dir: Path? = null
    private val writes = AtomicInteger()
    private val refreshing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "tmdb-disk").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }

    /** Refreshes of stale lists: several at once in TmdbGate's background lane, idle threads go away. */
    private val refresher = ThreadPoolExecutor(6, 6, 30, TimeUnit.SECONDS, LinkedBlockingQueue(), Lane.threads("tmdb-refresh"))
        .apply { allowCoreThreadTimeOut(true) }

    /** Starts keeping answers under [folder] (ContentServices.install: `<cache>\tmdb`). Prunes behind. */
    fun open(folder: Path) {
        Files.createDirectories(folder)
        dir = folder
        writer.execute { prune(folder) }
    }

    /** Stops using the disk (tests). */
    fun close() { dir = null }

    val folder: Path? get() = dir

    /** The request's path ("/trending/all/day"), without host, version and query. */
    fun pathOf(url: String): String = url.substringAfter("themoviedb.org/3", url).substringBefore('?')

    /** Never kept: the key check must ask TMDB, and anything account-bound. */
    fun cacheable(path: String) = path != "/configuration" && !path.startsWith("/account") && !path.startsWith("/authentication")

    /** Lists served stale-while-revalidate (rows); everything else is an object. */
    fun isList(path: String): Boolean =
        path.startsWith("/trending/") || path.startsWith("/discover/") || path.startsWith("/search/") ||
            LIST_ENDS.any { path.endsWith(it) }

    private val LIST_ENDS = listOf("/recommendations", "/similar", "/popular", "/top_rated", "/now_playing", "/upcoming",
        "/on_the_air", "/airing_today")

    /**
     * How long an answer stays good: charts and searches an hour, discover lists six hours, a show's details and seasons
     * six hours (its next episode), a movie's details a day, ratings, ids, images and people a week.
     */
    fun ttlMs(path: String): Long = when {
        path.startsWith("/trending/") || path.startsWith("/search/") -> HOUR
        path.startsWith("/discover/") -> 6 * HOUR
        path.endsWith("/release_dates") || path.endsWith("/content_ratings") || path.endsWith("/external_ids") ||
            path.endsWith("/images") || path.startsWith("/person/") -> 7 * 24 * HOUR
        path.startsWith("/tv/") -> 6 * HOUR
        path.startsWith("/movie/") -> 24 * HOUR
        else -> 6 * HOUR
    }

    /**
     * [url]'s answer: from disk while good, else from [network] (kept). [revalidate]: an answer past its lifetime is
     * served at once and refreshed behind (lists by default; a row's object lookups may ask for it too). Throws what
     * [network] throws when nothing on disk can stand in. Blocking: IO threads only.
     */
    fun get(url: String, revalidate: Boolean? = null, network: () -> String): Answer {
        val d = dir
        val path = pathOf(url)
        if (d == null || !cacheable(path)) return Answer(network(), false)
        val f = d.resolve(name(url))
        val age = ageMs(f)
        if (age != null && age < ttlMs(path)) read(f)?.let { return Answer(it, false) }
        if (age != null && age < MAX_AGE_MS && (revalidate ?: isList(path))) {
            read(f)?.let { body ->
                refreshBehind(url, f, network)
                return Answer(body, true)
            }
        }
        return try {
            Answer(network().also { write(f, it) }, false)
        } catch (e: Exception) {
            // TMDB unreachable or failing: an older copy beats an empty row. A real "not found" is passed on.
            val usable = e !is HttpError || e.code == 429 || e.code >= 500
            if (usable && age != null && age < MAX_AGE_MS) read(f)?.let { return Answer(it, true) }
            throw e
        }
    }

    private fun refreshBehind(url: String, f: Path, network: () -> String) {
        if (!refreshing.add(url)) return
        try {
            refresher.execute {
                try { write(f, network()) } catch (e: Exception) { /* offline: the stale copy stays */ } finally { refreshing.remove(url) }
            }
        } catch (e: Exception) {
            refreshing.remove(url)
        }
    }

    private fun name(url: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        val sb = StringBuilder(h.size * 2 + 5)
        for (b in h) { sb.append(HEX[(b.toInt() shr 4) and 15]); sb.append(HEX[b.toInt() and 15]) }
        return sb.append(".json").toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun ageMs(f: Path): Long? = try {
        System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis()
    } catch (e: Exception) { null }

    private fun read(f: Path): String? = try { Files.readString(f).takeIf { it.isNotEmpty() } } catch (e: Exception) { null }

    private fun write(f: Path, body: String) {
        val t = body.trimStart()
        if (!t.startsWith("{") && !t.startsWith("[")) return
        try {
            writer.execute {
                try {
                    val tmp = f.resolveSibling(f.fileName.toString() + ".tmp")
                    Files.writeString(tmp, body)
                    try {
                        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING)
                    }
                } catch (e: Exception) {
                    Log.d("TmdbDisk", "write failed: ${e.javaClass.simpleName}")
                }
                if (writes.incrementAndGet() % PRUNE_EVERY == 0) f.parent?.let { prune(it) }
            }
        } catch (e: Exception) { /* shutting down */ }
    }

    /** Waits until every queued write is on disk (tests, app exit). */
    fun flush() {
        try { writer.submit {}.get(5, TimeUnit.SECONDS) } catch (e: Exception) {}
    }

    /** Old files go; past [MAX_BYTES] the oldest go until [PRUNE_TO_BYTES]. */
    internal fun prune(folder: Path) {
        try {
            val now = System.currentTimeMillis()
            val files = Files.list(folder).use { s -> s.filter { it.fileName.toString().endsWith(".json") }.toList() }
                .mapNotNull { p -> try { Triple(p, Files.getLastModifiedTime(p).toMillis(), Files.size(p)) } catch (e: Exception) { null } }
            var total = 0L
            val kept = ArrayList<Triple<Path, Long, Long>>()
            for (x in files) {
                if (now - x.second > MAX_AGE_MS) Files.deleteIfExists(x.first) else { kept += x; total += x.third }
            }
            if (total > MAX_BYTES) {
                for (x in kept.sortedBy { it.second }) {
                    if (total <= PRUNE_TO_BYTES) break
                    Files.deleteIfExists(x.first)
                    total -= x.third
                }
            }
        } catch (e: Exception) {
            Log.d("TmdbDisk", "prune: ${e.javaClass.simpleName}")
        }
    }
}
