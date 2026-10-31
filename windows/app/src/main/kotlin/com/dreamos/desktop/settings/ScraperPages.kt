package com.dreamos.desktop.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import com.dreamos.desktop.settings.kit.Dream
import com.dreamos.desktop.settings.kit.DreamButton
import com.dreamos.desktop.settings.kit.DreamType
import com.dreamos.desktop.settings.kit.InfoRow
import com.dreamos.desktop.settings.kit.KeyField
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

/** Scrapers: the repositories, then the providers with Use Recommended and a switch each. */
@Composable
fun ScrapersPage(onBack: () -> Unit, go: (SettingsRoute) -> Unit) {
    val svc = LocalPagesServices.current
    var bump by remember { mutableIntStateOf(0) }
    var openRepo by remember { mutableStateOf<ScraperRepo?>(null) }
    var help by remember { mutableStateOf<Boolean?>(null) }
    help?.let { how -> com.dreamos.desktop.setup.HelpPage(com.dreamos.desktop.setup.SetupGuides.scrapersHelp, how) { help = null }; return }
    openRepo?.let { r -> RepoPage(r, onBack = { openRepo = null; bump++ }); return }
    PageScaffold("Scrapers", onBack, subtitle = "Scrapers search the web for streams of what you pick. Dream OS doesn't include any: add the repository you use, and choose its providers.") {
        androidx.compose.runtime.key(bump) {
            val repos = svc.scrapers.repos()
            val on = svc.scrapers.providers().count { it.on }
            // The page's state in one line, as the Fire Stick's Scrapers page opens.
            if (repos.isNotEmpty() && on > 0) ResultLine(Mark.OK, "Ready to watch.")
            else ResultLine(Mark.WARN, "Add a repository and turn on at least one provider.")
            Spacer(Modifier.height(20.dp))
            SectionHead(if (repos.size > 1) "Your Repositories" else "Your Repository", tag = "Required")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (repos.isEmpty()) {
                    Note("No repository yet. Add the one you use by pasting its address. Repositories are shared by the people who make them, usually on a web page or on GitHub.")
                    AddressExamples()
                    PageRow("Add Scraper Repository", { go(SettingsRoute.AddRepo) }, caption = "Type or paste its address")
                } else {
                    repos.forEach { r ->
                        PageRow(r.name, { openRepo = r }, caption = repoCaption(r), value = "Installed", valueColor = Dream.GreenText, mark = Mark.OK)
                    }
                    PageRow("Add Another Repository", { go(SettingsRoute.AddRepo) })
                }
                com.dreamos.desktop.setup.HelpRows(com.dreamos.desktop.setup.SetupGuides.scrapersHelp) { help = it }
            }
            val prov = svc.scrapers.providers()
            if (repos.isNotEmpty()) {
                Spacer(Modifier.height(26.dp))
                SectionHead("Providers  ·  ${prov.count { it.on }} of ${prov.size} On")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (prov.isEmpty()) Note("This repository didn't add any providers yet. Try Update, or add another repository.")
                    else {
                        if (svc.scrapers.recommendedOn) InfoRow("Recommended Settings On", caption = "The fastest, most reliable providers are on.")
                        else PageRow("Use Recommended Settings", { svc.scrapers.useRecommended(); bump++ }, caption = "Turns on the fastest, most reliable providers so Instant Play can start most titles in about a second.", chevron = false)
                        val allOn = prov.all { it.on }
                        PageRow(if (allOn) "Turn All Off" else "Turn All On", { svc.scrapers.setAll(!allOn); bump++ }, chevron = false)
                        prov.forEach { pr -> SwitchRow(pr.name, pr.on, { svc.scrapers.setProvider(pr.id, it); bump++ }, caption = pr.kind) }
                    }
                }
            }
        }
    }
}

@Composable
fun AddRepoPage(onBack: () -> Unit) {
    PageScaffold("Add Scraper Repository", onBack) { AddRepoBody(inSetup = false, onCancel = onBack, onDone = onBack) }
}

/** "Version 2.1  ·  host" (the version left out when the repository has none), as the Fire Stick words it. */
fun repoCaption(r: ScraperRepo): String =
    listOfNotNull(r.version?.let { "Version $it" }, r.address.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/'))
        .joinToString("  ·  ")

/** "A Repository Address Looks Like This" with the three shapes an address takes. */
@Composable
fun AddressExamples() {
    Column(Modifier.background(Dream.Glass, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(20.dp)) {
        Text("A Repository Address Looks Like This", style = DreamType.Row.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
        Spacer(Modifier.height(10.dp))
        listOf("https://someone.github.io/repo", "https://github.com/someone/repo", "https://example.com/repo.zip").forEach {
            Text(it, style = DreamType.Row.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = Dream.TextDim)); Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Note("Copy it exactly as you got it. It usually starts with https:// and often has github in it.")
    }
}

/**
 * Typing or pasting a repository's address and adding it: the progress words, then "Added <name>" and back by
 * itself after the result shows. Setup ([inSetup]) turns on the recommended providers; Settings leaves the choice to
 * Use Recommended Settings (the Fire Stick's). A failure shows the scrapers' own sentence and What to Try.
 */
@Composable
fun AddRepoBody(inSetup: Boolean, onCancel: () -> Unit, onDone: () -> Unit) {
    val svc = LocalPagesServices.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Result<String>?>(null) }
    fun add() {
        if (address.isBlank() || stage != null) return
        result = null
        scope.launch {
            val r = svc.scrapers.add(address.trim()) { stage = it }
            stage = null; result = r
            if (r.isSuccess) { if (inSetup) svc.scrapers.useRecommended(); delay(1500); onDone() }
        }
    }
    Note("Paste the repository's address exactly as you got it. It usually starts with https:// and often has github in it, such as https://someone.github.io/repo.", color = Dream.Text)
    Spacer(Modifier.height(18.dp))
    KeyField("Repository Address", address, { address = it }, placeholder = "https://yourname.github.io/repo", onSubmit = ::add)
    Spacer(Modifier.height(16.dp))
    val r = result
    when {
        stage != null -> Row(verticalAlignment = Alignment.CenterVertically) { StatusMark(Mark.BUSY); Spacer(Modifier.width(12.dp)); Text(stage!!, style = DreamType.Row) }
        r != null && r.isSuccess -> {
            ResultLine(Mark.OK, "Added ${r.getOrNull()}")
            val n = svc.scrapers.providers().let { pr -> if (inSetup) pr.count { it.on } else pr.size }
            Spacer(Modifier.height(6.dp)); Note(if (n == 1) "1 provider ready" else "$n providers ready")
        }
        else -> {
            if (r != null) {
                val msg = r.exceptionOrNull()?.message ?: "That didn't work. Check the address and try again."
                ResultLine(Mark.BAD, "Couldn't Add It")
                Spacer(Modifier.height(6.dp)); Note(msg, color = Dream.Text)
                Spacer(Modifier.height(12.dp)); WhatToTry(com.dreamos.desktop.setup.SetupGuides.repoHelp(msg))
                Spacer(Modifier.height(14.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DreamButton(if (r != null) "Try Again" else "Add", ::add, primary = true, enabled = address.isNotBlank())
                if (r != null) DreamButton("Cancel", onCancel)
            }
        }
    }
}

/** One repository: its version and address, Update, Remove (with a confirmation). */
@Composable
private fun RepoPage(r: ScraperRepo, onBack: () -> Unit) {
    val svc = LocalPagesServices.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    PageScaffold(r.name, onBack) {
        InfoRow("Version", value = r.version ?: "Not Stated")
        Spacer(Modifier.height(8.dp))
        InfoRow("Address", caption = r.address)
        Spacer(Modifier.height(24.dp))
        SectionHead("Actions")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PageRow("Update", {
                update = "Checking for Updates…"
                scope.launch { update = svc.scrapers.update(r).fold({ it }, { it.message ?: "Couldn't check right now. Try again later." }) }
            }, caption = "Gets the newest version of its providers", value = update, chevron = false)
            if (!confirm) PageRow("Remove Repository", { confirm = true }, caption = "Its providers are removed too", chevron = false)
            else ConfirmCard("Remove ${r.name}?", "Dream OS removes this repository and its providers. Without a repository, Dream OS can't find streams until you add one again.", "Remove",
                onConfirm = { scope.launch { svc.scrapers.remove(r); onBack() } }, onCancel = { confirm = false })
        }
    }
}
