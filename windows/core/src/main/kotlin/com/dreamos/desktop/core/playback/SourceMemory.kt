package com.dreamos.desktop.core.playback

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Smart source learning (iPhone SourceMemory.swift, Instant Play 2026-10-28): which kinds of release start fast and
 * play cleanly on this PC, and which fail. Every link's outcome is recorded against a few traits of its release (the
 * scraper provider, release group, container, video codec, size bucket). The ranking reads a nudge from it: a trait set
 * that failed repeatedly goes a step later, one that started quickly and cleanly several times a step earlier, always
 * inside the viewer's own filters and after the quality tier, so a nudge only reorders otherwise-equal releases.
 * Bounded at [MAX_KEYS] traits (~60 KB on disk).
 */
class SourceMemory(private val store: KeyValueStore, private val clock: Clock = Clock.SYSTEM) {
    @Serializable
    data class Stat(val ok: Int = 0, val fail: Int = 0, val ms: Long = 0, val at: Long = 0)

    companion object {
        const val MIN_SAMPLES = 3
        /** A start slower than this isn't "fast" even when it worked. */
        const val FAST_MS = 3_500L
        const val MAX_KEYS = 600
        private const val KEY = "core.learn"
        private val MAP = MapSerializer(String.serializer(), Stat.serializer())

        /**
         * A release's traits: "prov:<scraper>", "grp:<release group>", "ext:<container>", "codec:<video codec>",
         * "size:<bucket>", from what Dream Core knows up front (the scraped name, provider and size).
         */
        fun traits(name: String, fileName: String?, provider: String?, sizeBytes: Long): List<String> {
            val out = ArrayList<String>()
            provider?.lowercase()?.takeIf { it.isNotEmpty() }?.let { out += "prov:" + it.substringAfterLast(':') }
            group(name)?.let { out += "grp:$it" }
            container(fileName ?: name)?.let { out += "ext:$it" }
            val t = name.uppercase()
            when {
                ReleaseWords.hevc(t) -> out += "codec:hevc"
                ReleaseWords.av1(t) -> out += "codec:av1"
                ReleaseWords.avc(t) -> out += "codec:avc"
            }
            if (sizeBytes > 0) {
                val gb = sizeBytes.toDouble() / (1L shl 30)
                out += "size:" + when { gb < 2 -> "s"; gb < 6 -> "m"; gb < 15 -> "l"; else -> "xl" }
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
            g = g.lowercase()
            if (g.length !in 2..20 || !g.first().isLetterOrDigit()) return null
            if (g.all { it.isDigit() }) return null
            if ('.' in g || g in setOf("dl", "hd", "ray", "rip", "ma", "audio", "x")) return null
            return g
        }

        fun container(name: String): String? {
            val n = name.lowercase()
            for (e in listOf("mkv", "mp4", "m4v", "avi", "ts", "webm", "mov")) if (n.endsWith(".$e")) return if (e == "m4v") "mp4" else e
            return null
        }
    }

    private val lock = Any()
    private var stats: MutableMap<String, Stat> = try {
        store.get(KEY)?.let { Json.decodeFromString(MAP, it).toMutableMap() } ?: HashMap()
    } catch (e: Exception) { HashMap() }

    /** A read-only copy for one ranking pass. */
    class Snapshot(private val stats: Map<String, Stat>) {
        val isEmpty get() = stats.isEmpty()

        /** -1: these traits started fast and cleanly here; +1: they failed repeatedly; 0: nothing known. */
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

    fun snapshot() = synchronized(lock) { Snapshot(HashMap(stats)) }

    /** A link played its first frame after [ms] ([ok] true), or failed to open / start. */
    fun record(traits: List<String>, ok: Boolean, ms: Long?) {
        if (traits.isEmpty()) return
        val now = clock.wallMs()
        synchronized(lock) {
            for (t in traits) {
                val s = stats[t] ?: Stat()
                stats[t] = if (ok) s.copy(ok = s.ok + 1, ms = if (ms != null && ms > 0) (if (s.ms > 0) (s.ms * 2 + ms) / 3 else ms) else s.ms, at = now)
                else s.copy(fail = s.fail + 1, at = now)
            }
            if (stats.size > MAX_KEYS) stats.entries.sortedBy { it.value.at }.take(stats.size - MAX_KEYS + 50).map { it.key }.forEach { stats.remove(it) }
            store.put(KEY, Json.encodeToString(MAP, stats))
        }
        PlaybackLog.i("Dream Core: learned ${if (ok) "clean start" else "failure"} for ${traits.firstOrNull { it.startsWith("grp:") } ?: traits.first()} (${traits.size} traits)")
    }
}
