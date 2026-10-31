package com.dreamos.desktop.core.playback

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The viewer's source filters, Settings › Sources & Quality (fire-home DreamFilters, iPhone DreamFilters.swift). Same
 * keys as the other apps, so a backup moves between them. Stored as one JSON map under [STORE_KEY].
 *
 * [viewer]: the values Settings › Sources & Quality saved in the pages' own file, already in these keys
 * ([PagesSourceSettings]); they win over the stored map. Read on every [current] (an in-memory map), so a change in
 * Settings applies to the next Play without a restart.
 *
 * Not ported: the iPhone's Cellular Data Saver (a PC has no mobile data) and the Fire Stick's device cap for the
 * 1080p stick (a PC scales any grade; [ScreenFacts.maxQuality] covers a GPU that can't decode 4K).
 */
class SourceFilters(private val store: KeyValueStore, private val screen: ScreenFacts,
                    private val viewer: () -> Map<String, String> = { emptyMap() }) {
    companion object {
        const val STORE_KEY = "dream_filters"
        private val Q = listOf("4k", "1080p", "720p", "sd")

        /** The option keys Settings shows. */
        val KEYS = setOf(
            "quality.max", "quality.min", "quality.hdr", "hide.cam", "hide.hdr", "hide.dv", "hide.hevc", "hide.av1",
            "prefer.hdr", "prefer.hevc", "size.movie.limit", "size.movie.min_gb", "size.movie.max_gb", "size.movie.speed_mbps",
            "size.episode.limit", "size.episode.min_gb", "size.episode.max_gb", "size.episode.speed_mbps",
            "subs.enabled", "subs.lang1", "subs.lang2", "subs.embedded", "subs.hi", "subs.forced",
        )
        /** Keys the resolver honors that Settings doesn't show. */
        private val EXTRA = setOf("hide.avc", "hide.3d", "hide.upscaled")
        private val MAP = MapSerializer(String.serializer(), String.serializer())
        private const val GB = 1L shl 30
    }

    @Volatile private var cached: Values? = null

    /**
     * The filters as the resolver applies them; missing values fall back to safe defaults. [deviceCap]: the best grade
     * this PC is worth picking (scale 0 4K .. 3 SD), never relaxed. [screenHdr]: the screen shows HDR now.
     */
    class Values(private val v: Map<String, String>, val deviceCap: Int = 0, screenHdr: Boolean = false) {
        private fun on(k: String, def: Boolean) = v[k].let { if (it.isNullOrEmpty()) def else it == "true" }
        /** A quality cap was ever chosen; without one the resolver ranks 1080p first (streams well on any connection). */
        val known = !v["quality.max"].isNullOrEmpty()
        /** 0 4K, 1 1080p, 2 720p, 3 SD: anything better is dropped (relaxable), beyond [deviceCap] never. */
        val cap = maxOf(Q.indexOf(v["quality.max"]).coerceAtLeast(0), deviceCap)
        /** SD only goes when something better exists. */
        val hideSd = v["quality.min"].let { if (it.isNullOrEmpty()) true else it != "sd" }
        val hideCam = on("hide.cam", true)
        val hideHdr = on("hide.hdr", false)
        val hideDv = on("hide.dv", false)
        val hideHevc = on("hide.hevc", false)
        /** Hidden by default as on the other apps (10-bit AV1 crashed the stick); a PC plays it, the viewer can turn it off. */
        val hideAv1 = on("hide.av1", true)
        val hideAvc = on("hide.avc", false)
        val hide3d = on("hide.3d", true)
        val hideUpscaled = on("hide.upscaled", false)
        val preferHdr = on("prefer.hdr", false)
        val preferHevc = on("prefer.hevc", false)
        /**
         * Prefer HDR above the cap (iPhone HDRPreference, "quality.hdr"): an HDR copy may go above Maximum Quality
         * (almost every HDR copy is 4K, so a 1080p cap otherwise never plays HDR). Only while the screen shows HDR;
         * default on when it does.
         */
        val hdrAboveCap = screenHdr && on("quality.hdr", true)
        val subsOn = on("subs.enabled", false)
        val subLangs: List<String> = listOfNotNull(v["subs.lang1"], v["subs.lang2"]).filter { it.isNotBlank() }.distinct().ifEmpty { listOf("English") }

        /** Size range in GB when the limit is "range". */
        fun range(ep: Boolean): ClosedFloatingPointRange<Double>? {
            val p = if (ep) "size.episode" else "size.movie"
            if (v["$p.limit"] != "range") return null
            val min = v["$p.min_gb"]?.toDoubleOrNull() ?: 0.0
            val max = v["$p.max_gb"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: Double.MAX_VALUE
            return min..maxOf(min, max)
        }

        /** Internet speed in Mbps when the limit is "speed" (files too big to stream at it are skipped); null otherwise. */
        fun speedMbps(ep: Boolean): Double? {
            val p = if (ep) "size.episode" else "size.movie"
            if (v["$p.limit"] != "speed") return null
            return (v["$p.speed_mbps"] ?: v["size.movie.speed_mbps"])?.toDoubleOrNull()?.takeIf { it > 0 }
        }

        /** Whether [bytes] passes the size limits for a title running [runtimeSec] (0 unknown). Unknown sizes pass. */
        fun sizeOk(bytes: Long, ep: Boolean, runtimeSec: Int): Boolean {
            if (bytes <= 0) return true
            range(ep)?.let { if (bytes.toDouble() / GB !in it) return false }
            val mbps = speedMbps(ep)
            if (mbps != null && runtimeSec > 0 && bytes * 8.0 / runtimeSec / 1_000_000.0 > mbps) return false
            return true
        }
    }

    /** Search All Sources: every filter off but Hide CAM, discs, archives and what this PC can't show; 4K ranked first. */
    fun loose(): Values = Values(mapOf("quality.max" to "4k", "quality.min" to "sd", "hide.av1" to "false", "hide.3d" to "false",
        "hide.cam" to current().hideCam.toString()), deviceCap(), false)

    private fun deviceCap() = screen.maxQuality.rank.coerceAtLeast(0)

    /** Any thread; one store read, then cached until a value changes or the screen's HDR state flips. */
    fun current(): Values {
        val hdr = screen.hdr
        val v = viewerValues()
        cached?.takeIf { cachedHdr == hdr && cachedViewer == v }?.let { return it }
        synchronized(this) {
            return Values(own() + v, deviceCap(), hdr).also { cached = it; cachedHdr = hdr; cachedViewer = v }
        }
    }

    @Volatile private var cachedHdr = false
    @Volatile private var cachedViewer: Map<String, String> = emptyMap()

    private fun viewerValues(): Map<String, String> = try { viewer() } catch (e: Exception) { emptyMap() }

    private fun own(): Map<String, String> = try {
        store.get(STORE_KEY)?.let { Json.decodeFromString(MAP, it) } ?: emptyMap()
    } catch (e: Exception) { emptyMap() }

    /** What Dream OS holds, by option key (for the settings UI): the stored map with Settings' values over it. */
    fun stored(): Map<String, String> = own() + viewerValues()

    /** A value the viewer chose; non-filter keys are ignored. */
    fun put(key: String, value: String) {
        if (key !in KEYS && key !in EXTRA) return
        synchronized(this) {
            store.put(STORE_KEY, Json.encodeToString(MAP, own() + (key to value)))
            cached = null
        }
    }
}
