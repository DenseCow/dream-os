package com.dreamos.desktop.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dreamos.desktop.settings.LocalPagesServices
import com.dreamos.desktop.settings.UpdateState
import com.dreamos.desktop.settings.kit.Dream
import com.dreamos.desktop.settings.kit.DreamButton
import com.dreamos.desktop.settings.kit.DreamType
import com.dreamos.desktop.settings.kit.Glyph
import com.dreamos.desktop.settings.kit.InfoRow
import com.dreamos.desktop.settings.kit.LocalPagesNav
import com.dreamos.desktop.settings.kit.Mark
import com.dreamos.desktop.settings.kit.PageRow
import com.dreamos.desktop.settings.kit.PageScaffold
import com.dreamos.desktop.settings.kit.SectionHead
import com.dreamos.desktop.settings.kit.StatusMark
import kotlinx.coroutines.launch

object DreamLinks {
    const val SOURCE = "https://github.com/DenseCow/dream-os"
    const val RELEASES = "https://github.com/DenseCow/dream-os/releases"
    const val DISCLAIMER = "https://github.com/DenseCow/dream-os/blob/main/DISCLAIMER.md"
}

/** The Dream OS mark from the app's resources (the rainbow-glass cloud), or null when it can't be read. */
fun dreamMark(): ImageBitmap? = runCatching {
    Thread.currentThread().contextClassLoader.getResourceAsStream("dream_mark.png")!!.use { org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
}.getOrNull()

/**
 * About: the mark, "Dream OS", the version from the build with its channel, the update status from GitHub with
 * Check for Updates (and Get the Update when a newer version waits), the open source links, and the credits.
 */
@Composable
fun AboutPage(onBack: () -> Unit) {
    val svc = LocalPagesServices.current
    val nav = LocalPagesNav.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(svc.updates.state()) }
    /** While Get the Update downloads: 0..1; then the problem line if it couldn't. */
    var downloading by remember { mutableStateOf<Float?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val mark = remember { dreamMark() }
    // An answer over 6 hours old is asked again as About opens (the Fire Stick's AboutPage.refreshIfDue).
    LaunchedEffect(Unit) { if (state != UpdateState.Checking) { val now = svc.updates.checkIfDue(); if (downloading == null) state = now } }
    PageScaffold("About", onBack) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (mark != null) { Image(mark, "Dream OS", Modifier.size(72.dp)); Spacer(Modifier.width(18.dp)) }
            Column {
                Text("Dream OS", style = DreamType.Title)
                Row {
                    Text("Version ${svc.updates.version} ", style = DreamType.Meta.copy(color = Dream.Text))
                    Text("(${svc.updates.channel})", style = DreamType.Meta.copy(color = if (svc.updates.channel == "PUBLIC") Dream.GreenText else Dream.RedText, fontWeight = FontWeight.SemiBold))
                }
                Text("Build ${buildNumber(svc.updates.version)}", style = DreamType.Small)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Every movie and show in one place, played from your own accounts and sources.", style = DreamType.Body.copy(color = Dream.TextDim))
        Spacer(Modifier.height(28.dp))
        SectionHead("Updates")
        val v = svc.updates.version
        val (head, detail, m) = when (val s = state) {
            UpdateState.Checking -> Triple("Checking for Updates…", "You have version $v.", Mark.BUSY)
            is UpdateState.Newer -> when {
                downloading != null -> Triple("Downloading Version ${s.version}…", "${(downloading!! * 100).toInt()}% done. Dream OS closes and the installer opens when it finishes.", Mark.BUSY)
                svc.updates.hasInstaller -> Triple("Version ${s.version} Available", "You have version $v. Get the Update downloads it, closes Dream OS and installs it. ${s.checkedWords}", Mark.WARN)
                else -> Triple("Version ${s.version} Available", "You have version $v. Download the new version from its release page, then run the installer. ${s.checkedWords}", Mark.WARN)
            }
            is UpdateState.Failed -> Triple("Couldn't Check for Updates", "Dream OS couldn't reach GitHub. Check your internet connection and try again. ${s.checkedWords}", Mark.BAD)
            is UpdateState.UpToDate -> Triple("Dream OS Is Up to Date", "You have version $v. ${s.checkedWords}", Mark.OK)
            UpdateState.Never -> Triple("Not Checked Yet", "You have version $v. Dream OS checks for a new version once a day.", Mark.OFF)
        }
        Row(verticalAlignment = Alignment.Top) {
            StatusMark(m, 24.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(head, style = DreamType.Section)
                Spacer(Modifier.height(4.dp))
                Text(detail.trim(), style = DreamType.Caption)
            }
        }
        problem?.let { Spacer(Modifier.height(12.dp)); com.dreamos.desktop.settings.kit.ResultLine(Mark.WARN, it) }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            (state as? UpdateState.Newer)?.let { n ->
                if (!svc.updates.hasInstaller) DreamButton("Get the Update", { nav.openUrl(n.pageUrl) }, primary = true, glyph = Glyph.Open)
                else if (downloading == null) DreamButton("Get the Update", {
                    problem = null; downloading = 0f
                    scope.launch { problem = svc.updates.install { f -> downloading = f }; downloading = null }
                }, primary = true)
                if (problem != null) DreamButton("Open the Release Page", { nav.openUrl(n.pageUrl) }, glyph = Glyph.Open)
            }
            DreamButton(if (state == UpdateState.Checking) "Checking…" else "Check for Updates", {
                if (state != UpdateState.Checking) { state = UpdateState.Checking; scope.launch { state = svc.updates.check() } }
            })
        }
        (state as? UpdateState.Newer)?.let { n ->
            // The new version's notes (the Fire Stick's View Changes).
            Spacer(Modifier.height(26.dp))
            SectionHead("What's New in Version ${n.version}")
            val released = svc.updates.newestReleased.takeIf { it > 0 }?.let {
                "Released " + java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US))
            }
            listOfNotNull("Dream OS ${n.version}", svc.updates.newestSize.ifBlank { null }, released).joinToString("  ·  ").let { Text(it, style = DreamType.Caption) }
            Spacer(Modifier.height(8.dp))
            Text(svc.updates.newestNotes.ifBlank { "No notes were published for this version." }, style = DreamType.Body)
        }
        Spacer(Modifier.height(30.dp))
        SectionHead("Open Source")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PageRow("Source Code", { nav.openUrl(DreamLinks.SOURCE) }, caption = "Open source  ·  GPL-3.0", value = "GitHub", trailing = { ink, _ -> com.dreamos.desktop.settings.kit.GlyphIcon(Glyph.Open, ink, 18.dp) }, chevron = false)
            PageRow("All Releases", { nav.openUrl(DreamLinks.RELEASES) }, caption = "Every version and what changed", trailing = { ink, _ -> com.dreamos.desktop.settings.kit.GlyphIcon(Glyph.Open, ink, 18.dp) }, chevron = false)
            PageRow("Disclaimer", { nav.openUrl(DreamLinks.DISCLAIMER) }, caption = "Please read before using Dream OS", trailing = { ink, _ -> com.dreamos.desktop.settings.kit.GlyphIcon(Glyph.Open, ink, 18.dp) }, chevron = false)
        }
        Spacer(Modifier.height(30.dp))
        SectionHead("Credits")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoRow("Movie and Show Data", caption = "This product uses the TMDB API but is not endorsed or certified by TMDB.", value = "TMDB")
            InfoRow("Where to Watch", caption = "Streaming availability from JustWatch", value = "JustWatch")
            InfoRow("Scores", caption = "Rotten Tomatoes and IMDb scores from OMDb", value = "OMDb")
        }
    }
}

/** Settings › Updates › What's New: the notes of the version this PC has (the Fire Stick's NOTES page). */
@Composable
fun WhatsNewPage(onBack: () -> Unit) {
    val svc = LocalPagesServices.current
    val v = svc.updates.version
    var notes by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(v) { val n = svc.updates.notesFor(v); if (n == null) failed = true else notes = n }
    PageScaffold("What's New", onBack, subtitle = "The changes in version $v, the one you have.") {
        when {
            failed -> com.dreamos.desktop.settings.kit.ResultLine(Mark.WARN, "Couldn't reach GitHub. Check your internet connection and try again.")
            notes == null -> Row(verticalAlignment = Alignment.CenterVertically) { StatusMark(Mark.BUSY); Spacer(Modifier.width(12.dp)); Text("Getting the notes…", style = DreamType.Row) }
            notes!!.isBlank() -> Text("No notes were published for this version.", style = DreamType.Body.copy(color = Dream.TextDim))
            else -> Text(notes!!, style = DreamType.Body)
        }
    }
}

/** major*10000 + minor*100 + patch, from the version the build carries. */
fun buildNumber(version: String): Int {
    val p = version.split('.').mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() } + listOf(0, 0, 0)
    return p[0] * 10000 + p[1] * 100 + p[2]
}
