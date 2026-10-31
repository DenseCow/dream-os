package com.dreamos.desktop.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dreamos.desktop.settings.kit.Note
import com.dreamos.desktop.settings.kit.PageRow
import com.dreamos.desktop.settings.kit.PageScaffold
import com.dreamos.desktop.settings.kit.SectionHead

/**
 * Source size limits (fire-home PlaybackPrefs sizes), saved under Dream Core's own filter keys in the profile's
 * pages file: `size.movie.limit` / `size.episode.limit` ("off", "range", "speed"), `.min_gb`, `.max_gb`,
 * `.speed_mbps`. A mode's numbers are written when it is chosen, so Dream Core never reads a half-made limit.
 */
object SizeLimits {
    val MODES = listOf("off" to "No Limit", "range" to "Size Range", "speed" to "Match My Connection")
    val MIN_GB = listOf("0", "0.5", "1", "1.5", "2", "3", "4", "5", "8", "10").map { it to if (it == "0") "None" else "$it GB" }
    val MAX_GB = listOf("1", "1.5", "2", "3", "4", "5", "6", "8", "10", "15", "20", "25", "30", "40", "50", "60", "80", "100").map { it to "$it GB" }
    val MBPS = listOf("5", "10", "15", "20", "25", "30", "40", "50", "75", "100", "150", "200").map { it to "$it Mbps" }
    /** The setup speed test's last answer in Mbps ("" when never run). */
    const val LAST_SPEED = "speed.lastMbps"

    private fun k(ep: Boolean, part: String) = (if (ep) "size.episode." else "size.movie.") + part

    fun mode(p: Prefs, ep: Boolean) = p.str(k(ep, "limit"), "off").takeIf { m -> MODES.any { it.first == m } } ?: "off"
    fun minGb(p: Prefs, ep: Boolean) = p.str(k(ep, "min_gb"), "0")
    fun maxGb(p: Prefs, ep: Boolean) = p.str(k(ep, "max_gb"), if (ep) "5" else "20")
    fun lastSpeed(p: Prefs): Int = p.str(LAST_SPEED, "").toIntOrNull() ?: 0
    fun speed(p: Prefs, ep: Boolean) = p.str(k(ep, "speed_mbps"), "").ifBlank { p.str(k(false, "speed_mbps"), "") }.ifBlank { suggested(p) }

    /** The speed list's step at or under 90% of the last speed test (25 Mbps without one). */
    fun suggested(p: Prefs): String {
        val s = lastSpeed(p)
        if (s <= 0) return "25"
        return MBPS.lastOrNull { it.first.toFloat() <= s * 0.9f }?.first ?: MBPS.first().first
    }

    fun set(p: Prefs, ep: Boolean, part: String, value: String) {
        p.setStr(k(ep, part), value)
        // A range always stays a range: the smallest never above the largest.
        val min = minGb(p, ep).toDoubleOrNull() ?: 0.0
        val max = maxGb(p, ep).toDoubleOrNull() ?: 0.0
        if (part == "min_gb" && min > max) MAX_GB.firstOrNull { it.first.toDouble() >= min }?.let { p.setStr(k(ep, "max_gb"), it.first) }
        if (part == "max_gb" && max < min) MIN_GB.lastOrNull { it.first.toDouble() <= max }?.let { p.setStr(k(ep, "min_gb"), it.first) }
        if (part == "limit") {
            if (value == "range") { p.setStr(k(ep, "min_gb"), minGb(p, ep)); p.setStr(k(ep, "max_gb"), maxGb(p, ep)) }
            if (value == "speed") p.setStr(k(ep, "speed_mbps"), speed(p, ep))
        }
        p.setBool("size.on", mode(p, false) != "off" || mode(p, true) != "off")
    }

    fun gb(v: String) = (MIN_GB + MAX_GB).firstOrNull { it.first == v }?.second ?: "$v GB"

    /** "Up to 20 GB", "1 GB to 5 GB", "Up to 25 Mbps", "No Limit". */
    fun summary(p: Prefs, ep: Boolean): String = when (mode(p, ep)) {
        "range" -> minGb(p, ep).let { if (it == "0") "Up to " + gb(maxGb(p, ep)) else gb(it) + " to " + gb(maxGb(p, ep)) }
        "speed" -> "Up to ${speed(p, ep)} Mbps"
        else -> "No Limit"
    }
}

/**
 * Source Size Limits: Movies and Episodes, each with its Limit (No Limit, Size Range, Match My Connection) and,
 * for the chosen one, Smallest File and Largest File, or Your Internet Speed. Every choice is a page of its own.
 */
@Composable
fun SourceSizePage(onBack: () -> Unit) {
    val p = LocalPagesServices.current.prefs
    var choice by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var bump by remember { mutableIntStateOf(0) }
    choice?.let { (ep, what) ->
        val back: () -> Unit = { choice = null; bump++ }
        val kind = if (ep) "Episode" else "Movie"
        when (what) {
            "limit" -> ChoicePage("$kind Size Limit", SizeLimits.MODES.map { (id, label) ->
                label to when (id) { "range" -> "Set the smallest and largest file"; "speed" -> "Skips files too large for your connection"; else -> "Any file size" }
            }, SizeLimits.MODES.first { it.first == SizeLimits.mode(p, ep) }.second, back) { label ->
                SizeLimits.set(p, ep, "limit", SizeLimits.MODES.first { it.second == label }.first)
            }
            "min" -> ChoicePage("Smallest File", SizeLimits.MIN_GB.map { it.second to null }, SizeLimits.gb(SizeLimits.minGb(p, ep)).let { if (it == "0 GB") "None" else it }, back) { label ->
                SizeLimits.set(p, ep, "min_gb", SizeLimits.MIN_GB.first { it.second == label }.first)
            }
            "max" -> ChoicePage("Largest File", SizeLimits.MAX_GB.map { it.second to null }, SizeLimits.gb(SizeLimits.maxGb(p, ep)), back) { label ->
                SizeLimits.set(p, ep, "max_gb", SizeLimits.MAX_GB.first { it.second == label }.first)
            }
            else -> {
                val measured = SizeLimits.lastSpeed(p)
                ChoicePage("Your Internet Speed", SizeLimits.MBPS.map { it.second to null }, "${SizeLimits.speed(p, ep)} Mbps", back,
                    note = if (measured > 0) "Your last speed test: $measured Mbps." else "Choose the speed your internet plan gives you. Files too large to play smoothly at it are skipped.") { label ->
                    SizeLimits.set(p, ep, "speed_mbps", SizeLimits.MBPS.first { it.second == label }.first)
                }
            }
        }
        return
    }
    PageScaffold("Source Size Limits", onBack, subtitle = "Skip files that are too large or too small. Match My Connection skips files too large to play smoothly on your connection.") {
        androidx.compose.runtime.key(bump) {
            for (ep in listOf(false, true)) {
                if (ep) Spacer(Modifier.height(24.dp))
                SectionHead(if (ep) "Episodes" else "Movies")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val mode = SizeLimits.mode(p, ep)
                    PageRow("Limit", { choice = ep to "limit" }, value = SizeLimits.MODES.first { it.first == mode }.second)
                    if (mode == "range") {
                        PageRow("Smallest File", { choice = ep to "min" }, value = SizeLimits.gb(SizeLimits.minGb(p, ep)).let { if (it == "0 GB") "None" else it })
                        PageRow("Largest File", { choice = ep to "max" }, value = SizeLimits.gb(SizeLimits.maxGb(p, ep)))
                    }
                    if (mode == "speed") {
                        val measured = SizeLimits.lastSpeed(p)
                        PageRow("Your Internet Speed", { choice = ep to "speed" }, caption = if (measured > 0) "Last speed test: $measured Mbps" else null, value = "${SizeLimits.speed(p, ep)} Mbps")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Note("Files of unknown size always pass.")
        }
    }
}
