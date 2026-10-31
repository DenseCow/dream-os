package com.dreamos.player.ui

import com.dreamos.desktop.core.playback.StreamBadges
import com.dreamos.player.session.PlayerSession

/** One capsule in the badge line. [cam]: the amber CAM look; [hdr]: drawn with the sun and the rainbow edge (shown in HDR). */
data class Badge(val text: String, val cam: Boolean = false, val hdr: Boolean = false)

/**
 * The stream badges for what plays now: core's StreamBadges (fire-home's rules: resolution, HDR kind, audio, source,
 * CAM first in amber) fed with mpv's facts. The HDR badge gets the sun only while the picture really goes out in HDR.
 * [full] adds the size (pause screen, Info).
 */
fun badgesFor(s: PlayerSession, full: Boolean): List<Badge> {
    if (s.trailer) return emptyList()
    val f = s.engineState.facts
    val stream = s.request.stream
    val video = if (f.width > 0) StreamBadges.Video(f.width, f.height, f.gamma.ifEmpty { null }, f.dvProfile != null) else null
    val audio = if (f.audioCodec.isNotEmpty()) StreamBadges.Audio(f.audioCodec, f.audioChannels, f.audioProfile.ifEmpty { null }) else null
    val facts = StreamBadges.facts(stream.releaseName.ifEmpty { stream.fileName }, stream.url, stream.sizeBytes, video, audio) ?: return emptyList()
    val hdrWords = setOf("Dolby Vision", "HDR10+", "HDR10", "HLG")
    val out = facts.badges.map { Badge(it.text, cam = it.warn, hdr = it.text in hdrWords && f.hdrOnScreen) }
    return facts.size?.takeIf { full }?.let { out + Badge(it) } ?: out
}

fun sizeText(bytes: Long): String = if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000} MB"
