package com.dreamos.desktop.settings

import com.dreamos.desktop.settings.kit.rainbowNow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamos.desktop.settings.kit.Dream
import com.dreamos.desktop.settings.kit.DreamButton
import com.dreamos.desktop.settings.kit.DreamType
import com.dreamos.desktop.settings.kit.Glyph
import com.dreamos.desktop.settings.kit.InfoRow
import com.dreamos.desktop.settings.kit.KeyField
import com.dreamos.desktop.settings.kit.LocalPagesNav
import com.dreamos.desktop.settings.kit.Mark
import com.dreamos.desktop.settings.kit.Note
import com.dreamos.desktop.settings.kit.PageRow
import com.dreamos.desktop.settings.kit.PageScaffold
import com.dreamos.desktop.settings.kit.ResultLine
import com.dreamos.desktop.settings.kit.SectionHead
import com.dreamos.desktop.settings.kit.StatusMark
import com.dreamos.desktop.settings.kit.SwitchRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Languages {
    val audio = listOf("English", "Spanish", "French", "German", "Italian", "Portuguese", "Dutch", "Polish", "Russian", "Ukrainian", "Turkish", "Arabic",
        "Hebrew", "Hindi", "Japanese", "Korean", "Chinese", "Swedish", "Danish", "Norwegian", "Finnish", "Greek", "Czech", "Hungarian", "Romanian")
    val subtitles = audio.flatMap { if (it == "Portuguese") listOf(it, "Portuguese (Brazil)") else listOf(it) }

    /** The ISO 639 code core keeps for an audio language's name ("Spanish" -> "es"). */
    fun codeOf(name: String): String =
        java.util.Locale.getISOLanguages().firstOrNull { java.util.Locale(it).getDisplayLanguage(java.util.Locale.US).equals(name, ignoreCase = true) } ?: name.lowercase()

    /** The name for a code ("es" -> "Spanish"); also reads a name saved by mistake ("spanish") back as the list's name. */
    fun nameOf(code: String): String {
        val n = java.util.Locale(code).getDisplayLanguage(java.util.Locale.US)
        return audio.firstOrNull { it.equals(n, ignoreCase = true) } ?: audio.firstOrNull { it.equals(code, ignoreCase = true) } ?: n
    }
}

/** A list of choices on its own page; the picked one has the drawn check. Picking saves and returns. */
@Composable
fun ChoicePage(title: String, options: List<Pair<String, String?>>, selected: String, onBack: () -> Unit, note: String? = null, onPick: (String) -> Unit) {
    PageScaffold(title, onBack, subtitle = note) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (o, caption) -> PageRow(o, { onPick(o); onBack() }, caption = caption, mark = if (o == selected) Mark.OK else Mark.PICK, chevron = false) }
        }
    }
}

/**
 * Maximum Quality's default and choices (the Fire Stick's PlaybackPrefs.ensureDefaults): the best this PC's screens
 * show, saved the first time Sources & Quality or setup's Picture and Sound opens, so the page never says Automatic.
 * 4K is offered when a screen shows it.
 */
object Quality {
    fun plays4k(): Boolean = runCatching {
        val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        !java.awt.GraphicsEnvironment.isHeadless() && ge.screenDevices.any { d -> d.displayMode.let { maxOf(it.width, it.height) >= 3200 } }
    }.getOrDefault(false)
    fun best(): String = if (plays4k()) "4K" else "1080p"
    fun ensureDefault(p: Prefs) { if (p.str("quality.max", "Automatic") == "Automatic") p.setStr("quality.max", best()) }
    fun choices(): List<Pair<String, String?>> = listOfNotNull(
        if (plays4k()) "4K" to "Sharpest picture  ·  needs about 25 Mbps" else null,
        "1080p" to "Full HD  ·  needs about 8 Mbps",
        "720p" to "HD  ·  best for slower connections",
    )
    fun caption(): String = if (plays4k()) "Your PC and screen can play 4K" else "Your PC and screen play up to 1080p"
    const val NOTE = "Dream OS plays the best video up to this quality. Choose a lower one if videos pause to load."
}

/** Sources & Quality (the Fire Stick's PlaybackPages.overview): Picture, Sound, Subtitles, Source Size Limits, then Sources. */
@Composable
fun SourcesQualityPage(onBack: () -> Unit) {
    val svc = LocalPagesServices.current
    val p = svc.prefs
    var choice by remember { mutableStateOf<String?>(null) }
    var bump by remember { mutableIntStateOf(0) }
    remember { Quality.ensureDefault(p) }
    fun subsChanged() = svc.subtitleLanguages(listOf(p.str("subs.lang1", "English"), p.str("subs.lang2", "None")).filter { it != "None" }.distinct())
    when (choice) {
        "quality" -> { ChoicePage("Maximum Quality", Quality.choices(), p.str("quality.max", Quality.best()), { choice = null }, Quality.NOTE) { p.setStr("quality.max", it) }; return }
        "audio" -> { ChoicePage("Audio Language", Languages.audio.map { it to null }, p.str("audio.lang", "English"), { choice = null }) { p.setStr("audio.lang", it) }; return }
        "sub1" -> { ChoicePage("Subtitle Language", Languages.subtitles.map { it to null }, p.str("subs.lang1", "English"), { choice = null }) { p.setStr("subs.lang1", it); subsChanged() }; return }
        "sub2" -> { ChoicePage("Second Subtitle Language", (listOf("None") + Languages.subtitles).map { it to null }, p.str("subs.lang2", "None"), { choice = null }) { p.setStr("subs.lang2", it); subsChanged() }; return }
        "size" -> { SourceSizePage { choice = null; bump++ }; return }
    }
    PageScaffold("Sources & Quality", onBack, subtitle = "Choose how Dream OS plays your movies and shows: picture quality, languages, subtitles, sound and file sizes.") {
        androidx.compose.runtime.key(bump) {
            SectionHead("Picture")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PageRow("Maximum Quality", { choice = "quality" }, caption = Quality.caption(), value = p.str("quality.max", Quality.best()))
                Toggle(p, "Prefer HDR", "prefer.hdr", false, "Plays an HDR or Dolby Vision copy when there is one, even above Maximum Quality")
                Toggle(p, "Hide AV1 Videos", "hide.av1", true, "Older PCs play AV1 less smoothly")
            }
            Spacer(Modifier.height(24.dp))
            SectionHead("Sound")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val audio = p.str("audio.lang", "English")
                PageRow("Audio Language", { choice = "audio" }, caption = "Preferred when a video has several", value = audio)
                if (audio != "Japanese") Toggle(p, "Prefer $audio Dubs", "audio.preferDubs", true, "Plays the $audio dub when a copy has one.")
                Toggle(p, "Dolby Audio to Your Soundbar", "audio.passthrough", false, "Sends Dolby and DTS sound untouched to your soundbar or receiver")
            }
            Spacer(Modifier.height(24.dp))
            SectionHead("Subtitles")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Toggle(p, "Subtitles", "subs.enabled", false, "Turns subtitles on when a movie or show starts")
                PageRow("Subtitle Language", { choice = "sub1" }, value = p.str("subs.lang1", "English"))
                PageRow("Second Choice", { choice = "sub2" }, caption = "Used when the first isn't available", value = p.str("subs.lang2", "None"))
                Toggle(p, "Use Subtitles in the Video First", "subs.embedded", true, "Before downloading any")
            }
            Spacer(Modifier.height(24.dp))
            SectionHead("Source Size")
            PageRow("Source Size Limits", { choice = "size" }, caption = "Movies: ${SizeLimits.summary(p, false)}  ·  Episodes: ${SizeLimits.summary(p, true)}")
            // The Fire Stick keeps these in Settings' Sources & Quality group; here they sit with the rest (the iPhone's page).
            Spacer(Modifier.height(24.dp))
            SectionHead("Sources")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Toggle(p, "Hide HEVC (H.265)", "hide.hevc", false)
                Toggle(p, "Hide HDR", "hide.hdr", false, "For screens without HDR")
                Toggle(p, "Hide Dolby Vision", "hide.dv", false, "Keeps files that also have HDR")
                Toggle(p, "Hide CAM Copies", "hide.cam", true, "Recorded in a cinema")
                Toggle(p, "HDR and Dolby Vision First", "prefer.hdrFirst", false)
            }
        }
    }
}

@Composable
fun Toggle(p: Prefs, title: String, key: String, default: Boolean, caption: String? = null) {
    var on by remember(key) { mutableStateOf(p.bool(key, default)) }
    SwitchRow(title, on, { on = it; p.setBool(key, it) }, caption = caption)
}

/** Themes: the one look, Dream Cinema, and the line about themes to come. */
@Composable
fun ThemesPage(onBack: () -> Unit) {
    PageScaffold("Themes", onBack) {
        Row(Modifier.fillMaxWidth().background(Dream.Glass, RoundedCornerShape(14.dp)).border(1.dp, Dream.GlassEdge, RoundedCornerShape(14.dp)).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(120.dp, 68.dp).background(Brush.verticalGradient(listOf(Color(0xFF1A1A1E), Dream.Black)), RoundedCornerShape(10.dp)).border(2.dp, rainbowNow(), RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text("Dream Cinema", style = DreamType.Section)
                Spacer(Modifier.height(4.dp))
                Text("In Use", style = DreamType.Caption.copy(color = Dream.GreenText))
            }
            StatusMark(Mark.OK)
        }
        Spacer(Modifier.height(16.dp))
        Note("Dream Cinema is your look. In a future update, you'll be able to download themes made by others and create your own.")
    }
}

/**
 * Settings › Accounts & Scrapers › TMDB Key / OMDb Key (the Fire Stick's KeyPages): whether a key is added, the entry, then Show Me
 * How and If Something Goes Wrong.
 */
@Composable
fun KeyPage(tmdb: Boolean, onBack: () -> Unit) {
    val svc = LocalPagesServices.current
    val topic = if (tmdb) com.dreamos.desktop.setup.SetupGuides.tmdbHelp else com.dreamos.desktop.setup.SetupGuides.omdbHelp
    var help by remember { mutableStateOf<Boolean?>(null) }
    help?.let { how -> com.dreamos.desktop.setup.HelpPage(topic, how) { help = null }; return }
    PageScaffold(if (tmdb) "TMDB Key" else "OMDb Key", onBack,
        subtitle = if (tmdb) "Every poster, detail and search in Dream OS comes from TMDB, with your own free key." else "A free OMDb key adds Rotten Tomatoes and IMDb scores to every title.") {
        val added = if (tmdb) svc.keys.tmdbAdded else svc.keys.omdbAdded
        InfoRow(if (tmdb) "TMDB Key" else "OMDb Key", caption = if (tmdb) "Posters, details and search" else "Rotten Tomatoes and IMDb scores",
            value = if (added) "Added" else "Not Added", valueColor = if (added) Dream.GreenText else Dream.TextDim)
        Spacer(Modifier.height(20.dp))
        KeyBody(tmdb, onDone = onBack, onHelp = { help = it }, howButton = false)
        Spacer(Modifier.height(26.dp))
        SectionHead("Help")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { com.dreamos.desktop.setup.HelpRows(topic) { help = it } }
    }
}

/**
 * Entering a key: how to get it (the website opens in the browser), the field with Paste, Save Key; the check, then
 * Key Added and back by itself 1.5 s later. A key that fails its check is never saved.
 */
@Composable
fun KeyBody(tmdb: Boolean, onDone: () -> Unit, onHelp: ((Boolean) -> Unit)? = null, howButton: Boolean = true) {
    val svc = LocalPagesServices.current
    val nav = LocalPagesNav.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var ok by remember { mutableStateOf<Boolean?>(null) }
    fun save() {
        if (key.isBlank() || busy) return
        busy = true; ok = null
        scope.launch {
            ok = if (tmdb) svc.keys.saveTmdb(key.trim()) else svc.keys.saveOmdb(key.trim())
            busy = false
            if (ok == true) { delay(1500); onDone() }
        }
    }
    Text(
        if (tmdb) "Dream OS gets posters, details and search from TMDB. Your key is free: on TMDB's API page, use the key labeled API Key (32 letters and numbers), not the much longer API Read Access Token."
        else "Rotten Tomatoes and IMDb scores come from OMDb. Your key is free and arrives by email: select the link in that email to activate it, then paste the key here.",
        style = DreamType.Body,
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (tmdb) DreamButton("Open TMDB's API Page", { nav.openUrl("https://www.themoviedb.org/settings/api") }, glyph = Glyph.Open)
        else DreamButton("Get a Free OMDb Key", { nav.openUrl("https://www.omdbapi.com/apikey.aspx") }, glyph = Glyph.Open)
        if (onHelp != null && howButton) DreamButton("Show Me How", { onHelp(true) })
    }
    if (tmdb) { Spacer(Modifier.height(18.dp)); TmdbKeyArt(Modifier.widthIn(max = 560.dp)) }
    Spacer(Modifier.height(20.dp))
    KeyField("Your ${if (tmdb) "TMDB" else "OMDb"} Key", key, { key = it; ok = null }, placeholder = "Paste or Type Your Key", onSubmit = ::save)
    Spacer(Modifier.height(16.dp))
    when {
        busy -> Row(verticalAlignment = Alignment.CenterVertically) {
            StatusMark(Mark.BUSY); Spacer(Modifier.width(12.dp))
            Column { Text("Checking Your Key", style = DreamType.Row); Text("One moment…", style = DreamType.Caption) }
        }
        ok == true -> {
            ResultLine(Mark.OK, "Key Added")
            Spacer(Modifier.height(4.dp)); Note(if (tmdb) "Posters and details are ready." else "Rotten Tomatoes and IMDb scores are on.")
        }
        else -> {
            if (ok == false) {
                ResultLine(Mark.BAD, "That Key Didn't Work")
                Spacer(Modifier.height(4.dp)); Note("Check it and try again.")
                Spacer(Modifier.height(10.dp))
                WhatToTry(if (tmdb) listOf(
                    "Copy API Key again: the short one with 32 letters and numbers, not the long API Read Access Token. Paste it with nothing before or after it.",
                    "Still not working? Check your PC's internet connection, then select Save Key again.",
                ) else listOf(
                    "Open OMDb's email and select the link in it to activate your key, then select Save Key again. The email can take a few minutes.",
                    "Copy only the key, with nothing before or after it.",
                ))
                Spacer(Modifier.height(14.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DreamButton(if (ok == false) "Try Again" else "Save Key", ::save, primary = true, enabled = key.isNotBlank())
                if (ok == false && onHelp != null) DreamButton("Show Me How", { onHelp(true) })
            }
        }
    }
}

/** The "What to Try" box under an error. */
@Composable
fun WhatToTry(lines: List<String>, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 640.dp).background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(16.dp)).border(1.dp, Dream.GlassEdge, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Text("What to Try", style = DreamType.Row.copy(fontWeight = FontWeight.SemiBold))
        lines.forEach { Spacer(Modifier.height(6.dp)); Text(it, style = DreamType.Caption.copy(fontSize = 14.5.sp)) }
    }
}

/**
 * A drawing of TMDB's API page (not a screenshot): API Key lit with the rainbow edge and "Copy This One", the much
 * longer API Read Access Token dimmed with "Not This One". Informational.
 */
@Composable
fun TmdbKeyArt(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(Color(0xFF0E0E10), RoundedCornerShape(14.dp)).border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(36.dp, 8.dp)) { for (i in 0..2) drawCircle(Color.White.copy(alpha = 0.18f), 3.5.dp.toPx(), androidx.compose.ui.geometry.Offset((4 + i * 12).dp.toPx(), size.height / 2)) }
            Spacer(Modifier.width(10.dp))
            Text("themoviedb.org  ·  API", style = DreamType.Small.copy(color = Color(0xFFA1A1AA), fontSize = 11.5.sp, fontWeight = FontWeight.Medium))
        }
        Spacer(Modifier.height(12.dp))
        KeyArtField("API Key", "Copy This One", "8f3a1c9e2b7d4f60a5e1c3b9d2f7a4e8", lit = true)
        Spacer(Modifier.height(10.dp))
        KeyArtField("API Read Access Token", "Not This One", "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI4ZjNhMWM5ZTJiN2Q0ZjYwYTVlMWMzYjlkMmY3YTRlOCIs", lit = false)
    }
}

@Composable
private fun KeyArtField(label: String, tag: String, value: String, lit: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().alpha(if (lit) 1f else 0.8f)
            .background(Color.White.copy(alpha = if (lit) 0.07f else 0.03f), shape)
            .then(if (lit) Modifier.border(1.5.dp, rainbowNow(lit), shape) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = DreamType.Meta.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (lit) Dream.Text else Dream.TextDim), modifier = Modifier.weight(1f))
            Text(tag, style = DreamType.Small.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (lit) Dream.Ink else Dream.TextDim),
                modifier = Modifier.background(if (lit) Dream.Pearl else Color.White.copy(alpha = 0.06f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(value, style = DreamType.Small.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = if (lit) Dream.Text else Dream.Text.copy(alpha = 0.35f)))
    }
}
