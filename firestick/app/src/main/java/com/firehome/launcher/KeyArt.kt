package com.firehome.launcher

import android.content.Context
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Key art for landscape and big cards (owner, 2026-10-28): TMDB's backdrops with the title's name on them (images
 * tagged iso_639_1 = "en"), the best voted one, instead of a textless scene. Movies and shows alike; episode cards
 * keep their still. The answer comes from /{type}/{id}/images?include_image_language=en,null (the call the title
 * logos use) and is remembered per title for 14 days (memory + prefs), so a row asks once and later launches build
 * their cards with it at once. Cards fall back to the textless backdrop, then the poster ([CinemaKit.landscape]).
 */
object KeyArt {
    private const val PREFS = "dream_keyart"
    private const val MAX_AGE = 14L * 86_400_000
    private const val MAX_KEPT = 4000

    /** Title key -> "path" ("" = it has none) and when it was learned. */
    private val known = HashMap<String, Pair<String, Long>>()
    private val inflight = HashMap<String, FutureTask<String>>()
    private val dirty = HashMap<String, String>()
    @Volatile private var loaded = false
    private var flushQueued = false

    /** Its own few low-priority threads: never behind (or in front of) the row lists and card art. */
    private val pool = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "keyart").apply { priority = Thread.NORM_PRIORITY - 1 }
    }.apply { allowCoreThreadTimeOut(true) }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(ctx: Context) {
        if (loaded) return
        synchronized(known) {
            if (loaded) return
            val now = System.currentTimeMillis()
            for ((k, v) in prefs(ctx).all) {
                val s = v as? String ?: continue
                val bar = s.indexOf('|')
                if (bar <= 0) continue
                val at = s.substring(0, bar).toLongOrNull() ?: continue
                if (now - at < MAX_AGE) known[k] = s.substring(bar + 1) to at
            }
            loaded = true
        }
    }

    /** [t]'s key art url at [size] if known (null: none, or not asked yet). Any thread; reads only memory. */
    fun url(t: Title, size: String): String? =
        synchronized(known) { known[t.key]?.first }?.takeIf { it.isNotEmpty() }?.let { "${Tmdb.IMG}$size$it" }

    /**
     * Asks for [titles]' key art together (background threads only) and waits at most [waitMs] so a row never waits
     * long; answers that come later are remembered for the next time the row is built.
     */
    fun prime(ctx: Context, titles: List<Title>, waitMs: Long = 300) {
        if (titles.isEmpty() || !Tmdb.hasKey(ctx)) return
        load(ctx)
        val tasks = titles.distinctBy { it.key }.mapNotNull { t -> ask(ctx, t) }
        val deadline = System.currentTimeMillis() + waitMs
        for (task in tasks) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            try { task.get(left, TimeUnit.MILLISECONDS) } catch (e: Exception) { }
        }
    }

    /** The running (or new) ask for [t]; null when its answer is already known. */
    private fun ask(ctx: Context, t: Title): FutureTask<String>? = synchronized(known) {
        if (known.containsKey(t.key)) return null
        inflight[t.key]?.let { return it }
        val task = FutureTask(java.util.concurrent.Callable<String> {
            val path = try { best(ctx, t) } catch (e: Exception) { null }
            synchronized(known) {
                inflight.remove(t.key)
                // A failed call (offline) is not remembered: the next build asks again.
                if (path != null) remember(ctx, t.key, path)
            }
            path ?: ""
        })
        inflight[t.key] = task
        pool.execute(task)
        task
    }

    /** The best voted English backdrop's path, "" when there is none; null when TMDB couldn't be reached. */
    private fun best(ctx: Context, t: Title): String? {
        val j = Net.getJson("https://api.themoviedb.org/3/${t.type}/${t.id}/images?api_key=${Tmdb.key(ctx)}&include_image_language=en,null")
        val a = j.optJSONArray("backdrops") ?: return ""
        var best: org.json.JSONObject? = null
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("iso_639_1") != "en" || o.optString("file_path").isEmpty()) continue
            val b = best
            if (b == null || o.optDouble("vote_average", 0.0) > b.optDouble("vote_average", 0.0) ||
                (o.optDouble("vote_average", 0.0) == b.optDouble("vote_average", 0.0) && o.optInt("vote_count") > b.optInt("vote_count"))) best = o
        }
        return best?.optString("file_path").orEmpty()
    }

    /** Called holding [known]. Written in one batch a couple of seconds after a burst of answers. */
    private fun remember(ctx: Context, key: String, path: String) {
        val now = System.currentTimeMillis()
        known[key] = path to now
        dirty[key] = "$now|$path"
        if (known.size > MAX_KEPT) {
            known.entries.sortedBy { it.value.second }.take(known.size - MAX_KEPT).map { it.key }.forEach { known.remove(it); dirty[it] = "" }
        }
        if (flushQueued) return
        flushQueued = true
        pool.execute {
            try { Thread.sleep(2000) } catch (e: InterruptedException) { }
            val batch = synchronized(known) { flushQueued = false; HashMap(dirty).also { dirty.clear() } }
            val e = prefs(ctx).edit()
            for ((k, v) in batch) if (v.isEmpty()) e.remove(k) else e.putString(k, v)
            e.apply()
        }
    }
}
