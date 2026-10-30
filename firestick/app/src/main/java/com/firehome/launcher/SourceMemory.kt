package com.firehome.launcher

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Source learning (the iPhone's SourceMemory, 2026-10-28): which kinds of release start fast and play cleanly on
 * this stick, and which fail. Every link's outcome is recorded against a few traits of its release: the scraper
 * provider, the release group, the container, the video codec and a size bucket. Dream Core's ranking reads a
 * nudge from it ([Snapshot.nudge]): a trait set that failed repeatedly goes a step later, one that has started
 * quickly and cleanly several times a step earlier, always inside the viewer's own quality and size filters
 * (those are never touched) and after the quality tier, so a learned nudge only reorders otherwise-equal releases.
 *
 * Bounded: at most [MAX_KEYS] traits (the oldest go), ~40 bytes each as JSON in the "dream_core" prefs; one
 * HashMap in memory, copied for a ranking pass (a few hundred small entries).
 */
object SourceMemory {
    private const val TAG = "FireHome"
    /** What a trait needs before it says anything. */
    const val MIN_SAMPLES = 3
    /** A start slower than this isn't "fast" even when it worked. */
    const val FAST_MS = 3_500L
    private const val KEY = "learn"
    private const val MAX_KEYS = 600

    /** [ms]: rolling first-frame time of the clean starts, recent ones weighing more; [at]: last change (wall clock). */
    class Stat(val ok: Int = 0, val fail: Int = 0, val ms: Long = 0, val at: Long = 0)

    private val lock = Any()
    private var prefs: SharedPreferences? = null
    private var stats: HashMap<String, Stat>? = null

    /** Opens the store (once; cheap after that). Any thread but the main one for the first call. */
    fun init(ctx: Context) {
        if (stats != null) return
        synchronized(lock) {
            if (stats != null) return
            val p = ctx.applicationContext.getSharedPreferences("dream_core", Context.MODE_PRIVATE)
            prefs = p
            stats = load(p)
        }
    }

    private fun load(p: SharedPreferences): HashMap<String, Stat> {
        val out = HashMap<String, Stat>()
        try {
            val o = JSONObject(p.getString(KEY, null) ?: return out)
            for (k in o.keys()) {
                val a = o.optJSONArray(k) ?: continue
                out[k] = Stat(a.optInt(0), a.optInt(1), a.optLong(2), a.optLong(3))
            }
        } catch (e: Exception) { Log.w(TAG, "Dream Core: learned sources unreadable; starting over") }
        return out
    }

    private fun save() {
        val p = prefs ?: return
        val s = stats ?: return
        val o = JSONObject()
        for ((k, v) in s) o.put(k, JSONArray().put(v.ok).put(v.fail).put(v.ms).put(v.at))
        p.edit().putString(KEY, o.toString()).apply()
    }

    /** A read-only copy for one ranking pass (no lock per candidate). */
    class Snapshot(private val stats: Map<String, Stat>) {
        val isEmpty get() = stats.isEmpty()

        /** -1: this release's traits have started fast and cleanly here; +1: they have failed repeatedly; 0: nothing known. */
        fun nudge(traits: List<String>): Int {
            if (stats.isEmpty()) return 0
            var bad = false; var fast = false; var anyFail = false
            for (t in traits) {
                val s = stats[t] ?: continue
                // The release group is the most telling trait: two failures and more failures than clean plays is enough.
                if (t.startsWith("grp:") && s.fail >= 2 && s.fail > s.ok) bad = true
                if (s.ok + s.fail < MIN_SAMPLES) continue
                if (s.fail.toDouble() / (s.ok + s.fail) >= 0.6) bad = true
                if (s.fail > 0) anyFail = true
                if (s.fail == 0 && s.ok >= MIN_SAMPLES && s.ms in 1..FAST_MS) fast = true
            }
            return if (bad) 1 else if (fast && !anyFail) -1 else 0
        }
    }

    fun snapshot(): Snapshot = synchronized(lock) { Snapshot(stats?.let { HashMap(it) } ?: emptyMap()) }

    /** A link played its first frame after [ms] (clean start, [ok]) or failed to open / start. */
    fun record(traits: List<String>, ok: Boolean, ms: Long = 0) {
        if (traits.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val s = stats ?: return
            for (t in traits) {
                val o = s[t] ?: Stat()
                s[t] = if (ok) Stat(o.ok + 1, o.fail, if (ms > 0) (if (o.ms > 0) (o.ms * 2 + ms) / 3 else ms) else o.ms, now)
                    else Stat(o.ok, o.fail + 1, o.ms, now)
            }
            if (s.size > MAX_KEYS) s.entries.sortedBy { it.value.at }.take(s.size - MAX_KEYS + 50).forEach { s.remove(it.key) }
            save()
        }
        val what = traits.firstOrNull { it.startsWith("grp:") } ?: traits.first()
        Log.w(TAG, "Dream Core: learned ${if (ok) "clean start" else "failure"} for $what (${traits.size} traits)")
    }

    // ---- traits of a release ----

    /**
     * The traits of a release: "prov:<scraper>", "grp:<release group>", "ext:<container>", "codec:<video codec>",
     * "size:<bucket>". Built from what Dream Core knows up front (the scraped name, provider and size); for a link
     * whose scrape is gone, from the link's file name and size.
     */
    fun traits(name: String, fileName: String?, provider: String?, sizeBytes: Long): List<String> {
        val out = ArrayList<String>(5)
        provider?.lowercase(java.util.Locale.US)?.takeIf { it.isNotEmpty() }?.let { out += "prov:" + it.substringAfterLast(':') }
        group(name)?.let { out += "grp:$it" }
        container(fileName ?: name)?.let { out += "ext:$it" }
        val t = name.uppercase(java.util.Locale.US)
        when {
            tok(t, "HEVC") || tok(t, "X265") || tok(t, "H265") || tok(t, "H.265") -> out += "codec:hevc"
            tok(t, "AV1") -> out += "codec:av1"
            tok(t, "X264") || tok(t, "H264") || tok(t, "H.264") || tok(t, "AVC") -> out += "codec:avc"
        }
        if (sizeBytes > 0) {
            val gb = sizeBytes.toDouble() / (1L shl 30)
            out += "size:" + (if (gb < 2) "s" else if (gb < 6) "m" else if (gb < 15) "l" else "xl")
        }
        return out
    }

    /** The release group: what follows the last '-' of the name (its extension off), when it looks like one. */
    fun group(name: String): String? {
        var base = name
        val dot = base.lastIndexOf('.')
        if (dot >= 0 && base.length - dot <= 5) base = base.substring(0, dot)
        val dash = base.lastIndexOf('-')
        if (dash < 0) return null
        var g = base.substring(dash + 1).trim()
        val sp = g.indexOfFirst { it == ' ' || it == '[' || it == '(' }
        if (sp >= 0) g = g.substring(0, sp)
        g = g.lowercase(java.util.Locale.US)
        if (g.length !in 2..20 || !(g[0].isLetter() || g[0].isDigit())) return null
        // Numbers alone (a "-1080p" tail) aren't a group.
        if (g.all { it.isDigit() }) return null
        // Nor the tail of a hyphenated tag ("WEB-DL", "Blu-ray", "DTS-HD") or of a dotted name after it.
        if ('.' in g || g in NOT_GROUPS) return null
        return g
    }

    private val NOT_GROUPS = setOf("dl", "hd", "ray", "rip", "ma", "audio", "x")

    fun container(name: String): String? {
        val n = name.lowercase(java.util.Locale.US)
        for (e in CONTAINERS) if (n.endsWith(".$e")) return if (e == "m4v") "mp4" else e
        return null
    }

    private val CONTAINERS = listOf("mkv", "mp4", "m4v", "avi", "ts", "webm", "mov")

    /** [w] as a whole token in upper-case [t]. */
    private fun tok(t: String, w: String): Boolean {
        var from = 0
        while (true) {
            val i = t.indexOf(w, from)
            if (i < 0) return false
            val before = if (i == 0) null else t[i - 1]
            val after = if (i + w.length >= t.length) null else t[i + w.length]
            if ((before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())) return true
            from = i + 1
        }
    }
}
