package com.dreamos.desktop.core.playback

/**
 * Settings › Sources & Quality as Dream Core's filter keys. The pages save the viewer's words in their per-profile
 * `pages` file ("4K", "Automatic", switches); win-content's ViewerSettings reads the same keys. Mapping:
 *  - `quality.max` "4K" / "1080p" / "720p" / "SD" to "4k" / "1080p" / "720p" / "sd"; "Automatic" leaves the cap unset
 *    (1080p ranked first, 4K allowed).
 *  - `prefer.hdr` (Prefer HDR, "even above Maximum Quality") to `quality.hdr`; `prefer.hdrFirst` (HDR and Dolby
 *    Vision First) to `prefer.hdr`.
 *  - `hide.av1|hevc|hdr|dv|cam` and `subs.enabled` as they are; `subs.lang1|lang2` as language names ("None" left out).
 *  - `size.movie|episode.limit` ("off" / "range" / "speed") with `.min_gb`, `.max_gb`, `.speed_mbps` as they are
 *    (Settings › Source Size Limits saves Dream Core's own keys and values).
 * Only keys the viewer has actually saved are returned: the rest keep Dream Core's defaults.
 */
object PagesSourceSettings {
    private val QUALITY = mapOf("4k" to "4k", "1080p" to "1080p", "720p" to "720p", "sd" to "sd")
    private val SAME = listOf("hide.av1", "hide.hevc", "hide.hdr", "hide.dv", "hide.cam", "subs.enabled") +
        listOf("movie", "episode").flatMap { k -> listOf("limit", "min_gb", "max_gb", "speed_mbps").map { "size.$k.$it" } }

    /** [read] answers a saved value as text ("true", "4K"), or null when the viewer never set it. */
    fun map(read: (String) -> String?): Map<String, String> {
        val out = HashMap<String, String>()
        read("quality.max")?.let { q -> out["quality.max"] = QUALITY[q.trim().lowercase()] ?: "" }
        read("prefer.hdr")?.let { out["quality.hdr"] = it }
        read("prefer.hdrFirst")?.let { out["prefer.hdr"] = it }
        for (k in SAME) read(k)?.let { out[k] = it }
        read("subs.lang1")?.takeIf { it != "None" }?.let { out["subs.lang1"] = it }
        read("subs.lang2")?.takeIf { it != "None" }?.let { out["subs.lang2"] = it }
        return out
    }

    /** The current profile's saved values (core's settings store); empty when there is no store. */
    fun current(): Map<String, String> = try {
        val f = com.dreamos.desktop.core.store.DreamStore.shared.profileFile("pages")
        map { k -> f.getJson(k)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } }
    } catch (e: Exception) { emptyMap() }
}
