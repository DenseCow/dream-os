package com.dreamos.desktop.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.Text
import com.dreamos.desktop.settings.kit.DreamType
import com.dreamos.desktop.settings.kit.Mark
import kotlinx.coroutines.launch
import com.dreamos.desktop.settings.kit.PageRow
import com.dreamos.desktop.settings.kit.PageScaffold
import com.dreamos.desktop.settings.kit.SectionHead
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Time zone and clock words (TimeSettings / DreamTime.CURATED). Stand-in until core's DreamTime lands; the choice is
 * kept in prefs "time.zone" (an IANA id, "" = automatic) and "time.clock" (Automatic / 12-Hour / 24-Hour).
 */
object TimeWords {
    val zones: List<Pair<String, List<Pair<String, String>>>> = listOf(
        "United States" to listOf("Eastern Time" to "America/New_York", "Central Time" to "America/Chicago", "Mountain Time" to "America/Denver",
            "Arizona" to "America/Phoenix", "Pacific Time" to "America/Los_Angeles", "Alaska" to "America/Anchorage", "Hawaii" to "Pacific/Honolulu", "Puerto Rico" to "America/Puerto_Rico"),
        "Americas" to listOf("Atlantic Time (Halifax)" to "America/Halifax", "Newfoundland" to "America/St_Johns", "Toronto" to "America/Toronto", "Vancouver" to "America/Vancouver",
            "Mexico City" to "America/Mexico_City", "Bogotá" to "America/Bogota", "Lima" to "America/Lima", "Santiago" to "America/Santiago", "São Paulo" to "America/Sao_Paulo", "Buenos Aires" to "America/Argentina/Buenos_Aires"),
        "Europe & Africa" to listOf("London" to "Europe/London", "Dublin" to "Europe/Dublin", "Lisbon" to "Europe/Lisbon", "Paris" to "Europe/Paris", "Berlin" to "Europe/Berlin",
            "Madrid" to "Europe/Madrid", "Rome" to "Europe/Rome", "Amsterdam" to "Europe/Amsterdam", "Stockholm" to "Europe/Stockholm", "Athens" to "Europe/Athens", "Istanbul" to "Europe/Istanbul",
            "Moscow" to "Europe/Moscow", "Lagos" to "Africa/Lagos", "Cairo" to "Africa/Cairo", "Johannesburg" to "Africa/Johannesburg", "Nairobi" to "Africa/Nairobi"),
        "Asia & Pacific" to listOf("Dubai" to "Asia/Dubai", "Karachi" to "Asia/Karachi", "India" to "Asia/Kolkata", "Bangkok" to "Asia/Bangkok", "Singapore" to "Asia/Singapore",
            "Manila" to "Asia/Manila", "China" to "Asia/Shanghai", "Hong Kong" to "Asia/Hong_Kong", "Seoul" to "Asia/Seoul", "Tokyo" to "Asia/Tokyo", "Perth" to "Australia/Perth",
            "Adelaide" to "Australia/Adelaide", "Brisbane" to "Australia/Brisbane", "Sydney" to "Australia/Sydney", "Auckland" to "Pacific/Auckland"),
        "Other" to listOf("Coordinated Universal Time" to "UTC"),
    )

    fun labelOf(id: String): String = zones.flatMap { it.second }.firstOrNull { it.second == id }?.first ?: id.substringAfterLast('/').replace('_', ' ')

    fun zone(p: Prefs): ZoneId = p.str("time.zone", "").takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    /** "Automatic (Eastern Time)" or "Pacific Time". */
    fun zoneValue(p: Prefs): String {
        val id = p.str("time.zone", "")
        return if (id.isBlank()) "Automatic (${labelOf(ZoneId.systemDefault().id)})" else labelOf(id)
    }

    private fun twelveHour(p: Prefs): Boolean = when (p.str("time.clock", "Automatic")) {
        "12-Hour" -> true
        "24-Hour" -> false
        else -> systemTwelveHour()
    }

    private fun systemTwelveHour(): Boolean = (java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, Locale.getDefault()) as? java.text.SimpleDateFormat)?.toPattern()?.contains('h') ?: true

    /** The setting itself, as the Fire Stick shows it: Automatic, 12-Hour or 24-Hour. */
    fun clockValue(choice: String): String = choice

    fun time(p: Prefs, at: ZonedDateTime): String = at.format(DateTimeFormatter.ofPattern(if (twelveHour(p)) "h:mm a" else "HH:mm", Locale.US))

    fun nowWords(p: Prefs): String = time(p, ZonedDateTime.now(zone(p)))

    /** "GMT-5", "GMT+5:30", "GMT" (a plain hyphen-minus: every font has it). */
    fun gmt(id: String): String {
        val secs = ZonedDateTime.now(ZoneId.of(id)).offset.totalSeconds
        if (secs == 0) return "GMT"
        val sign = if (secs < 0) "-" else "+"
        val a = Math.abs(secs)
        return "GMT$sign${a / 3600}" + if (a % 3600 != 0) ":%02d".format((a % 3600) / 60) else ""
    }
}

/**
 * Time Zone (the Fire Stick's TimeSettings page): the time now in the chosen zone, "Choose Your Time Zone", Automatic,
 * a saved zone that isn't in the list, then the zones by region. The check moves to the pick, then the page closes
 * (320 ms). Times tick each minute.
 */
@Composable
fun TimeZonePage(onBack: () -> Unit, onPicked: () -> Unit = onBack) {
    val p = LocalPagesServices.current.prefs
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var picked by remember { mutableStateOf(p.str("time.zone", "")) }
    var minute by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(60_000 - System.currentTimeMillis() % 60_000); minute++ }
    }
    fun pick(id: String) { picked = id; p.setStr("time.zone", id); scope.launch { kotlinx.coroutines.delay(320); onPicked() } }
    PageScaffold("Time Zone", onBack, subtitle = "New episode times, the clock and reminders all follow the time zone you choose.") {
        androidx.compose.runtime.key(minute) {
        val now = TimeWords.zone(p)
        Text("It's ${TimeWords.time(p, ZonedDateTime.now(now))}  ·  ${TimeWords.labelOf(now.id)}", style = DreamType.Section)
        Spacer(Modifier.height(18.dp))
        SectionHead("Choose Your Time Zone")
        val auto = ZoneId.systemDefault().id
        PageRow("Automatic", { pick("") }, caption = "Follows your PC  ·  " + TimeWords.labelOf(auto), value = TimeWords.time(p, ZonedDateTime.now(ZoneId.of(auto))),
            mark = if (picked.isBlank()) Mark.OK else Mark.PICK, chevron = false)
        // A zone saved earlier that the list doesn't carry stays choosable, under Automatic.
        val saved = p.str("time.zone", "")
        if (saved.isNotBlank() && TimeWords.zones.none { r -> r.second.any { it.second == saved } } && runCatching { ZoneId.of(saved) }.isSuccess) {
            Spacer(Modifier.height(6.dp))
            PageRow(TimeWords.labelOf(saved), { pick(saved) }, caption = TimeWords.gmt(saved), value = TimeWords.time(p, ZonedDateTime.now(ZoneId.of(saved))),
                mark = if (picked == saved) Mark.OK else Mark.PICK, chevron = false)
        }
        TimeWords.zones.forEach { (region, list) ->
            Spacer(Modifier.height(18.dp))
            SectionHead(region)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                list.forEach { (label, id) ->
                    PageRow(label, { pick(id) }, caption = TimeWords.gmt(id), value = TimeWords.time(p, ZonedDateTime.now(ZoneId.of(id))),
                        mark = if (picked == id) Mark.OK else Mark.PICK, chevron = false)
                }
            }
        }
        }
    }
}
