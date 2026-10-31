package com.dreamos.desktop.settings

import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import com.dreamos.desktop.settings.kit.QrImage
import com.dreamos.desktop.settings.kit.ResultLine
import com.dreamos.desktop.settings.kit.SectionHead
import com.dreamos.desktop.settings.kit.StatusMark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The two-letter monogram on a service's dark disc (AD, RD, PM, TB). */
@Composable
fun Monogram(text: String, ink: Color) {
    Box(Modifier.size(38.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Text(text, style = DreamType.Pill.copy(color = ink, fontSize = 13.sp))
    }
}

/**
 * Accounts (the Fire Stick's Settings › Accounts): the state in one line, the four debrid services, which one to pick,
 * Show Me How and If Something Goes Wrong, the two explainers, the TMDB key while there is none, then Trakt.
 * Accounts not checked in the last 30 minutes are asked again as the page opens.
 */
@Composable
fun AccountsPage(onBack: () -> Unit, go: (SettingsRoute) -> Unit) {
    val svc = LocalPagesServices.current
    var states by remember { mutableStateOf(svc.debrid.states()) }
    var checking by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf<Pair<com.dreamos.desktop.setup.HelpTopic, Boolean>?>(null) }
    LaunchedEffect(Unit) {
        val stale = states.filter { it.linked && it.word.endsWith("Not Checked Yet") }
        if (stale.isNotEmpty()) { checking = true; stale.forEach { svc.debrid.check(it.service) }; states = svc.debrid.states(); checking = false }
    }
    help?.let { (topic, how) -> com.dreamos.desktop.setup.HelpPage(topic, how) { help = null }; return }
    PageScaffold("Accounts", onBack, subtitle = "Your debrid services find and stream your movies and shows in high quality. Connect, check or disconnect them here. Trakt is optional.") {
        val ready = states.any { it.linked && !it.needsAttention && it.mark == Mark.OK }
        val first = svc.debrid.linkedInOrder().firstOrNull()?.let { d -> states.first { it.service == d } }
        when {
            ready && svc.keys.tmdbAdded -> ResultLine(Mark.OK, "Ready to watch.")
            ready -> ResultLine(Mark.WARN, "Add your TMDB key to continue.")
            first == null -> ResultLine(Mark.WARN, "Connect at least one debrid service to continue.")
            checking || first.word.endsWith("Not Checked Yet") -> ResultLine(Mark.BUSY, "Checking your account…")
            first.word == "Sign In Again" -> ResultLine(Mark.WARN, "Sign in to ${first.service.title} again, or connect another service, to continue.")
            first.word == "Approve This Network" -> ResultLine(Mark.WARN, "Approve this network in the email from ${first.service.title}, then select ${first.service.title} to check again.")
            first.word == "Expired" -> ResultLine(Mark.WARN, "Your ${first.service.title} plan has expired. Renew it, or connect another service, to continue.")
            else -> ResultLine(Mark.WARN, "Couldn't reach ${first.service.title}. Check your internet connection, then select ${first.service.title} to check again.")
        }
        Spacer(Modifier.height(20.dp))
        SectionHead("Debrid Services", tag = "Required")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            states.forEach { st -> DebridRow(st) { go(if (st.linked) SettingsRoute.Service(st.service) else SettingsRoute.Connect(st.service)) } }
            Note(com.dreamos.desktop.setup.SetupGuides.PICK_LINE, Modifier.padding(vertical = 6.dp))
            com.dreamos.desktop.setup.HelpRows(com.dreamos.desktop.setup.SetupGuides.debridHelp) { help = com.dreamos.desktop.setup.SetupGuides.debridHelp to it }
            PageRow("What's a Debrid Service?", { go(SettingsRoute.WhatsDebrid) }, caption = "What it does, and where to get one")
            PageRow("Which Service Works Best?", { go(SettingsRoute.WhichBest) }, caption = "Premiumize gives the fastest play")
        }
        // Setup has its own TMDB step; here the key shows while there is none (the Fire Stick's).
        if (!svc.keys.tmdbAdded) {
            Spacer(Modifier.height(26.dp))
            SectionHead("Movie & Show Info", tag = "Required")
            PageRow("TMDB Key", { go(SettingsRoute.Key(true)) }, caption = "Posters, details and search", value = "Not Added", mark = Mark.WARN)
        }
        Spacer(Modifier.height(26.dp))
        SectionHead("Trakt", tag = "Optional")
        Note("Keeps your watch history and watchlist in sync across your devices.")
        Spacer(Modifier.height(10.dp))
        val t = svc.trakt.state()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PageRow("Trakt", { go(SettingsRoute.Trakt) }, caption = if (t.connected) t.synced else "trakt.tv",
                value = when { t.ended -> "Sign In Again"; t.connected -> t.user?.let { "Connected as @$it" } ?: "Connected"; else -> "Not Connected" },
                valueColor = when { t.ended -> Dream.AmberText; t.connected -> Dream.GreenText; else -> null },
                mark = if (t.connected) Mark.OK else null, leading = { ink -> Monogram("T", ink) })
            PageRow("Show Me How to Connect Trakt", { help = com.dreamos.desktop.setup.HelpTopic("", "", com.dreamos.desktop.setup.SetupGuides.trakt, null, emptyList()) to true },
                caption = "Optional and free. Add it now or later in Settings.")
        }
    }
}

/** A service row: monogram, name, who is signed in, the status word and mark. */
@Composable
fun DebridRow(st: DebridState, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = when (st.mark) { Mark.OK -> Dream.GreenText; Mark.BAD -> Dream.RedText; Mark.WARN -> Dream.AmberText; else -> null }
    PageRow(
        st.service.title, onClick, modifier = modifier,
        // The Fire Stick's row: the status word on the right, who and the plan underneath.
        caption = if (st.linked) listOfNotNull(st.user?.let { "Signed In as $it" }, st.plan ?: st.word.substringAfter(" · ", "").ifBlank { null }).joinToString("  ·  ").ifBlank { st.service.site } else st.service.site,
        value = if (st.linked) st.word.substringBefore(" · ") else "Connect",
        valueColor = color,
        leading = { ink -> Monogram(st.service.mono, ink) },
    )
}

/** One linked service: who, its state, and Check Again / Use First / Link Again / Disconnect. */
@Composable
fun ServicePage(s: Debrid, onBack: () -> Unit, go: (SettingsRoute) -> Unit) {
    val svc = LocalPagesServices.current
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf(svc.debrid.states().first { it.service == s }) }
    var checking by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    PageScaffold(s.title, onBack) {
        InfoRow(st.user ?: s.title, caption = s.site)
        Spacer(Modifier.height(8.dp))
        InfoRow("Status", value = st.word, valueColor = when (st.mark) { Mark.OK -> Dream.GreenText; Mark.BAD -> Dream.RedText; Mark.WARN -> Dream.AmberText; else -> Dream.TextDim })
        if (st.needsAttention) {
            Spacer(Modifier.height(12.dp))
            Note(when (st.word) {
                "Approve This Network" -> "${s.title} holds your account on a network it hasn't seen before until you approve it. Open the email from ${s.title}, approve this location, then choose Check Again."
                "Expired" -> "Renew your plan on ${s.title}'s website, then choose Check Again."
                else -> "Link ${s.title} again to sign back in."
            }, color = Dream.Text)
        }
        Spacer(Modifier.height(24.dp))
        SectionHead("Actions")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PageRow("Check Again", { checking = true; scope.launch { st = svc.debrid.check(s); checking = false } }, caption = "Asks ${s.title} for your account's status",
                value = if (checking) "Checking…" else null, chevron = false)
            var order by remember { mutableStateOf(svc.debrid.linkedInOrder()) }
            if (order.size > 1 && order.first() != s) {
                PageRow("Use First", { svc.debrid.useFirst(s); order = svc.debrid.linkedInOrder() }, caption = "Dream OS asks ${s.title} before your other services", chevron = false)
            }
            PageRow("Link Again", { go(SettingsRoute.Connect(s)) }, caption = "Sign in again, or with another account")
            if (!confirm) PageRow("Disconnect", { confirm = true }, caption = "Dream OS stops using this account", chevron = false)
            else ConfirmCard("Disconnect ${s.title}?", "Dream OS stops using ${s.title} on this PC. You can connect it again anytime.", "Disconnect",
                onConfirm = { scope.launch { svc.debrid.disconnect(s); onBack() } }, onCancel = { confirm = false })
        }
    }
}

/** An in-place confirmation (only before removing something): the question, one sentence, the action and Cancel. */
@Composable
fun ConfirmCard(title: String, message: String, action: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val cancel = remember { FocusRequester() }
    Column(Modifier.background(Dream.Panel, RoundedCornerShape(14.dp)).padding(20.dp)) {
        Text(title, style = DreamType.Section)
        Spacer(Modifier.height(6.dp))
        Text(message, style = DreamType.Body.copy(color = Dream.TextDim))
        Spacer(Modifier.height(16.dp))
        // Cancel first and focused, the action second (the Fire Stick's confirmations).
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DreamButton("Cancel", onCancel, modifier = Modifier.focusRequester(cancel))
            DreamButton(action, onConfirm)
        }
    }
    LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
}

/** Connect a service: the page around [ConnectBody]. */
@Composable
fun ConnectPage(s: Debrid, onBack: () -> Unit) {
    PageScaffold("Connect ${s.title}", onBack) { ConnectBody(s, onDone = onBack) }
}

/**
 * Signing in: for code services the code, big, beside a QR code of the short address, with Open in Browser and
 * Copy Code; it connects by itself once approved. Premiumize takes the account's key instead. Then the account is
 * checked ([VerifyBody]): a ready one returns by itself ([onDone], 1.6 s); one that isn't ready stays with Check Again
 * and Not Now ([onCancel]). A failure says why, with What to Try.
 */
@Composable
fun ConnectBody(s: Debrid, onCancel: () -> Unit = {}, onDone: () -> Unit) {
    val svc = LocalPagesServices.current
    val nav = LocalPagesNav.current
    val clip = LocalClipboardManager.current
    var attempt by remember { mutableIntStateOf(0) }
    var code by remember(attempt) { mutableStateOf<LinkCode?>(null) }
    var result by remember(attempt) { mutableStateOf<LinkResult?>(null) }
    var copied by remember(attempt) { mutableStateOf(false) }
    if (!svc.debrid.usesCode(s)) { KeyLinkBody(s, onCancel, onDone); return }
    LaunchedEffect(attempt) {
        // No code within 20 s: the service didn't answer (the Fire Stick's limit).
        val c = runCatching { kotlinx.coroutines.withTimeoutOrNull(20_000) { svc.debrid.startLink(s) } }.getOrNull()
        if (c == null) { result = LinkResult.Failed("${s.title} didn't answer. Check your internet connection and try again."); return@LaunchedEffect }
        code = c
        while (true) {
            val r = svc.debrid.pollLink(s)
            if (r != LinkResult.Waiting) { result = r; break }
        }
    }
    val c = code
    when (val r = result) {
        is LinkResult.Connected -> VerifyBody(s, onCancel, onDone)
        is LinkResult.Failed -> {
            ResultLine(Mark.BAD, r.message)
            Spacer(Modifier.height(14.dp)); WhatToTry(com.dreamos.desktop.setup.SetupGuides.linkHelp(r.message, s.title, key = false))
            Spacer(Modifier.height(16.dp))
            DreamButton("Try Again", { attempt++ }, primary = true)
        }
        else -> if (c == null) {
            Row(verticalAlignment = Alignment.CenterVertically) { StatusMark(Mark.BUSY); Spacer(Modifier.width(12.dp)); Text("Getting a code…", style = DreamType.Row) }
        } else {
            Row(verticalAlignment = Alignment.Top) {
                QrImage(c.url, 176.dp)
                Spacer(Modifier.width(28.dp))
                Column(Modifier.widthIn(max = 460.dp)) {
                    Text("Go to", style = DreamType.Caption)
                    Text(c.shortUrl, style = DreamType.Big)
                    Spacer(Modifier.height(12.dp))
                    Text("Enter this code", style = DreamType.Caption)
                    Text(c.code, style = DreamType.Display.copy(fontSize = 40.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold))
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DreamButton("Open in Browser", { clip.setText(AnnotatedString(c.code)); copied = true; nav.openUrl(c.url) }, primary = true, glyph = Glyph.Open)
                        DreamButton(if (copied) "Copied" else "Copy Code", { clip.setText(AnnotatedString(c.code)); copied = true }, glyph = if (copied) Glyph.Check else Glyph.Copy)
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { StatusMark(Mark.BUSY); Spacer(Modifier.width(12.dp)); Text("Waiting for You to Approve…", style = DreamType.Row) }
            Spacer(Modifier.height(6.dp))
            Note("Dream OS connects by itself as soon as you approve it on ${s.title}'s website. Open in Browser copies the code for you. The code works for about 10 minutes.")
        }
    }
}

/**
 * After a sign-in: Dream OS asks the service about the plan (the Fire Stick's verify screen). Ready: "<S> Is
 * Connected", who and the plan, then back by itself after 1.6 s. Not ready: why, with Check Again and Not Now.
 */
@Composable
fun VerifyBody(s: Debrid, onNotNow: () -> Unit, onReady: () -> Unit) {
    val svc = LocalPagesServices.current
    var round by remember { mutableIntStateOf(0) }
    var st by remember { mutableStateOf<DebridState?>(null) }
    val again = remember { FocusRequester() }
    LaunchedEffect(round) {
        st = null
        val now = svc.debrid.check(s)
        st = now
        if (now.mark == Mark.OK && !now.needsAttention) { delay(1600); onReady() }
        else { delay(60); runCatching { again.requestFocus() } }
    }
    val now = st
    val ok = now != null && now.mark == Mark.OK && !now.needsAttention
    ResultLine(if (now == null) Mark.BUSY else if (ok) Mark.OK else Mark.WARN, when {
        now == null -> "Checking Your Account…"
        ok -> "${s.title} Is Connected"
        now.word == "Approve This Network" -> "Approve This Network"
        else -> "${s.title} Isn't Ready Yet"
    })
    Spacer(Modifier.height(8.dp))
    Note(when {
        now == null -> "One moment while Dream OS asks ${s.title} about your plan."
        ok -> listOfNotNull(now.user?.let { "Signed in as $it." }, now.plan?.let { "$it." }).joinToString(" ")
        now.word == "Approve This Network" -> "${s.title} holds your account on a network it hasn't seen before until you approve it. Open the email from ${s.title}, approve this location, then select Check Again."
        now.word == "Expired" -> "This account doesn't have an active premium plan. Renew it on ${s.title}'s website, then select Check Again."
        else -> "Dream OS couldn't confirm your plan. Check your internet connection, then select Check Again."
    }, color = Dream.Text)
    if (now != null && !ok) {
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DreamButton("Check Again", { round++ }, primary = true, modifier = Modifier.focusRequester(again))
            DreamButton("Not Now", onNotNow)
        }
    }
}

/** Premiumize: open the account page, copy the API Key, paste it here. */
@Composable
private fun KeyLinkBody(s: Debrid, onCancel: () -> Unit, onDone: () -> Unit) {
    val svc = LocalPagesServices.current
    val nav = LocalPagesNav.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LinkResult?>(null) }
    Steps(listOf(
        "Select Open Your ${s.title} Account and sign in.",
        "Find API Key on your account page and copy it.",
        "Come back here, select Paste, then Connect.",
    ))
    Spacer(Modifier.height(14.dp))
    DreamButton("Open Your ${s.title} Account", { nav.openUrl("https://www.premiumize.me/account") }, glyph = Glyph.Open)
    Spacer(Modifier.height(20.dp))
    KeyField("Your ${s.title} Key", key, { key = it })
    Spacer(Modifier.height(14.dp))
    when {
        busy -> Row(verticalAlignment = Alignment.CenterVertically) {
            StatusMark(Mark.BUSY); Spacer(Modifier.width(12.dp))
            Column { Text("Checking Your Key", style = DreamType.Row); Text("One moment…", style = DreamType.Caption) }
        }
        result is LinkResult.Connected -> VerifyBody(s, onCancel, onDone)
        else -> {
            (result as? LinkResult.Failed)?.let {
                ResultLine(Mark.BAD, "That Key Didn't Work")
                Spacer(Modifier.height(6.dp)); Note(it.message, color = Dream.Text)
                Spacer(Modifier.height(12.dp)); WhatToTry(com.dreamos.desktop.setup.SetupGuides.linkHelp(it.message, s.title, key = true))
                Spacer(Modifier.height(14.dp))
            }
            DreamButton(if (result is LinkResult.Failed) "Try Again" else "Connect", {
                busy = true
                scope.launch { result = svc.debrid.linkWithKey(s, key.trim()); busy = false }
            }, primary = true, enabled = key.isNotBlank())
        }
    }
}

/** Numbered steps: the action in white, plain words. */
@Composable
fun Steps(steps: List<String>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        steps.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(28.dp).background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = DreamType.Pill.copy(color = Dream.Text))
                }
                Spacer(Modifier.width(14.dp))
                Text(s, style = DreamType.Body, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
fun WhatsDebridPage(onBack: () -> Unit) {
    val nav = LocalPagesNav.current
    PageScaffold("What's a Debrid Service?", onBack) {
        // The Fire Stick's words (DreamSetupPages.debridInfo), "select" for the PC.
        Text("A debrid service is a paid account that keeps a huge library of movies and shows on fast servers. Dream OS plays what you pick from your account, in full quality.", style = DreamType.Body)
        Spacer(Modifier.height(12.dp))
        Text("You need one. Sign up with any of these, then come back and select it.", style = DreamType.Body)
        Spacer(Modifier.height(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Debrid.entries.forEach { d -> PageRow(d.title, { nav.openUrl(d.signUp) }, caption = d.site, value = "Sign Up", leading = { ink -> Monogram(d.mono, ink) }) }
        }
    }
}

@Composable
fun WhichBestPage(onBack: () -> Unit) {
    PageScaffold("Which Service Works Best?", onBack) {
        // The Fire Stick's words (DreamSetupPages.debridBest), "select" for the PC, then the two services it names.
        listOf(
            "Premiumize is the best match. Dream OS can ask it whether a title is ready before you select Play, so Play starts almost at once. TorBox works the same way.",
            "AllDebrid and Real-Debrid are asked only when you select Play, so Play takes a second or two longer. Everything else works the same.",
            "You can connect more than one. Dream OS asks them in the order you choose.",
        ).forEach { Text(it, style = DreamType.Body); Spacer(Modifier.height(12.dp)) }
        val nav = LocalPagesNav.current
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Debrid.Premiumize, Debrid.TorBox).forEach { d -> PageRow(d.title, { nav.openUrl(d.signUp) }, caption = d.site, value = "Sign Up", leading = { ink -> Monogram(d.mono, ink) }) }
        }
    }
}

/** Trakt: sign in with a code, sync, disconnect. */
/** A failure's plain words: core's DreamError sentence, else the generic one (never a technical message). */
internal fun Throwable.plainWords(fallback: String = "Couldn't reach Trakt. Try again."): String =
    (this as? com.dreamos.desktop.core.model.DreamError)?.message ?: fallback

/**
 * Trakt (TraktPage): sign in with a short code, the connected account, Sync Now and Disconnect, for this profile only.
 * Dream OS ships no Trakt keys, so the viewer's own free Trakt app comes first (Use Your Own Trakt App), then the
 * sign-in; Advanced keeps that app reachable once it is saved.
 */
@Composable
fun TraktPage(onBack: () -> Unit) {
    val svc = LocalPagesServices.current
    val nav = LocalPagesNav.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf(svc.trakt.state()) }
    var needsApp by remember { mutableStateOf(svc.trakt.needsClientId) }
    var signing by remember { mutableStateOf<LinkCode?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var ownApp by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    var justConnected by remember { mutableStateOf(false) }
    var syncFailed by remember { mutableStateOf(false) }
    fun refresh() { st = svc.trakt.state(); needsApp = svc.trakt.needsClientId }
    fun signIn() {
        if (starting) return
        starting = true; problem = null
        scope.launch {
            runCatching { svc.trakt.startSignIn() }.onSuccess { signing = it }.onFailure { problem = it.plainWords() }
            starting = false
        }
    }
    if (ownApp) { TraktOwnAppPage(onBack = { ownApp = false; refresh() }, onCode = { signing = it; ownApp = false; refresh() }); return }

    val me = svc.profiles.all().firstOrNull { it.id == svc.profiles.currentId() }?.name?.takeIf { it.isNotBlank() }
    PageScaffold("Trakt", onBack, subtitle = if (st.ended) "Your Trakt sign-in has ended. Sign in again to keep this profile in sync."
        else "Keep what you watch in step across your devices: finished movies and episodes, where you left off, and your watchlist." +
            (me?.let { " Signed in for $it's profile only." } ?: "")) {
        val code = signing
        @Composable fun advanced() {
            Spacer(Modifier.height(20.dp))
            SectionHead("Advanced")
            PageRow("Your Trakt App", { ownApp = true }, caption = svc.trakt.clientIdHint?.let { "Client ID $it" } ?: "Use your own Trakt app keys")
        }
        when {
            justConnected -> {
                // The connected moment (the Fire Stick's), then the account by itself after 1.5 s.
                LaunchedEffect(Unit) { delay(1500); justConnected = false }
                ResultLine(Mark.OK, "Connected")
                Spacer(Modifier.height(8.dp))
                Note((st.user?.let { "Signed in as $it. " } ?: "Signed in to Trakt. ") + "Your history and watchlist are syncing now.", color = Dream.Text)
            }
            code != null -> {
                LaunchedEffect(code) {
                    when (val r = svc.trakt.pollSignIn()) {
                        is LinkResult.Connected -> { refresh(); signing = null; justConnected = true }
                        is LinkResult.Failed -> { problem = r.message; signing = null }
                        else -> signing = null
                    }
                }
                var left by remember(code) { mutableStateOf(code.expiresAt - System.currentTimeMillis()) }
                LaunchedEffect(code) { while (left > 0) { delay(1000); left = code.expiresAt - System.currentTimeMillis() } }
                Text("Sign In to Trakt", style = DreamType.Section)
                Spacer(Modifier.height(12.dp))
                Row {
                    QrImage(code.url, 160.dp)
                    Spacer(Modifier.width(24.dp))
                    Column {
                        Text("On this PC or any phone, go to the website below and enter this code:", style = DreamType.Body)
                        Spacer(Modifier.height(8.dp))
                        Text(code.shortUrl, style = DreamType.Big)
                        Text(code.code, style = DreamType.Display.copy(fontSize = 36.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold))
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            DreamButton("Open in Browser", { clip.setText(AnnotatedString(code.code)); nav.openUrl(code.url) }, primary = true, glyph = Glyph.Open)
                            DreamButton("Copy Code", { clip.setText(AnnotatedString(code.code)) }, glyph = Glyph.Copy)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { StatusMark(Mark.BUSY); Spacer(Modifier.width(12.dp)); Text("Waiting for You to Approve…", style = DreamType.Row) }
                Note("Approve Dream OS on Trakt and this page connects by itself." +
                    if (code.expiresAt > 0 && left > 0) "  Code expires in %d:%02d.".format(left / 60_000, (left / 1000) % 60) else "")
                Spacer(Modifier.height(14.dp))
                DreamButton("Cancel", { signing = null })
            }
            needsApp -> {
                SectionHead("Get Started")
                PageRow("Use Your Own Trakt App", { ownApp = true }, caption = "Free, and takes about a minute")
                Spacer(Modifier.height(14.dp))
                Note("Dream OS doesn't come with Trakt keys. Your own Trakt app is free and is used only by this PC.")
            }
            !st.connected -> {
                if (!st.ended) { Note("Sign in with Trakt and Dream OS keeps this profile's watched history, Continue Watching and My List in step with your Trakt account."); Spacer(Modifier.height(14.dp)) }
                SectionHead("Account")
                PageRow(if (st.ended) "Sign In Again" else "Sign In", ::signIn, caption = "Opens a short code and a QR code", value = if (starting) "One Moment…" else null)
                problem?.let {
                    Spacer(Modifier.height(12.dp)); ResultLine(Mark.WARN, it)
                    Spacer(Modifier.height(12.dp)); DreamButton("Get a New Code", ::signIn, primary = true)
                }
                advanced()
            }
            else -> {
                InfoRow(st.user ?: "Trakt", caption = st.user?.let { "@$it  ·  Connected" } ?: "Connected")
                Spacer(Modifier.height(20.dp))
                SectionHead("Sync")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PageRow("Sync Now", { syncing = true; syncFailed = false; scope.launch { syncFailed = !svc.trakt.syncNow(); st = svc.trakt.state(); syncing = false } },
                        caption = if (syncFailed) "Couldn't reach Trakt. Try again." else st.synced, value = if (syncing) "Syncing…" else null, chevron = false)
                    InfoRow("Watched History", caption = "Movies and episodes you finish are marked watched")
                    InfoRow("Continue Watching", caption = "Pick up where you left off on any device")
                    InfoRow("My List", caption = "Stays in step with your Trakt watchlist")
                }
                Spacer(Modifier.height(20.dp))
                SectionHead("Account")
                if (!confirm) PageRow("Sign Out", { confirm = true }, caption = "Stops syncing this profile with Trakt", chevron = false)
                else ConfirmCard("Sign Out of Trakt?", "Dream OS stops syncing this profile with Trakt. Everything already on this PC stays, and your Trakt account keeps its history. You can sign in again anytime.", "Sign Out",
                    onConfirm = { scope.launch { svc.trakt.disconnect(); refresh(); confirm = false } }, onCancel = { confirm = false })
                advanced()
            }
        }
    }
}

/**
 * Use Your Own Trakt App (TraktPages.ownApp, the iPhone's TraktOwnAppView): three short steps with Open Trakt Apps,
 * the Redirect URI to copy, then the Client ID. Sign In saves it, asks Trakt for a sign-in code (which proves the ID
 * works) and goes straight on to the code. The PC's sign-in needs no Client Secret, so there is one field.
 */
@Composable
fun TraktOwnAppPage(onBack: () -> Unit, onCode: (LinkCode) -> Unit) {
    val svc = LocalPagesServices.current
    val nav = LocalPagesNav.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf(svc.trakt.clientIdHint) }
    fun submit() {
        if (checking) return
        val clean = id.filterNot { it.isWhitespace() }
        when {
            clean.isEmpty() -> { error = "Enter your app's Client ID."; return }
            clean.length < 40 -> { error = "That Client ID didn't work. Copy the whole Client ID from your Trakt app and try again."; return }
        }
        error = null; checking = true
        scope.launch {
            val before = svc.trakt.clientIdHint
            svc.trakt.setClientId(clean)
            runCatching { svc.trakt.startSignIn() }
                .onSuccess { checking = false; onCode(it) }
                .onFailure {
                    // A Client ID Trakt refuses isn't kept when there was none before.
                    if (before == null) svc.trakt.setClientId("")
                    error = it.plainWords(); checking = false; hint = svc.trakt.clientIdHint
                }
        }
    }
    PageScaffold("Use Your Own Trakt App", onBack, subtitle = "Dream OS is open source, so it doesn't come with Trakt keys. Your own Trakt app is free, takes about a minute, and is used only by this PC.") {
        SectionHead("Create Your App")
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NumberedLine(1, "Go to ${shortUrl(svc.trakt.appsUrl)} and choose New Application.")
            Row(Modifier.padding(start = 42.dp)) { DreamButton("Open Trakt Apps", { nav.openUrl(svc.trakt.appsUrl) }, glyph = Glyph.Open) }
            NumberedLine(2, "Give it any name, and enter this Redirect URI:")
            Row(
                Modifier.padding(start = 42.dp).widthIn(max = 560.dp).background(Dream.Glass, RoundedCornerShape(14.dp))
                    .border(1.dp, Dream.GlassEdge, RoundedCornerShape(14.dp)).padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(svc.trakt.redirectUri, style = DreamType.Big.copy(fontFamily = FontFamily.Monospace, fontSize = 18.sp), modifier = Modifier.weight(1f), maxLines = 1)
                Spacer(Modifier.width(12.dp))
                DreamButton(if (copied) "Copied" else "Copy", { clip.setText(AnnotatedString(svc.trakt.redirectUri)); copied = true }, glyph = if (copied) Glyph.Check else Glyph.Copy)
            }
            NumberedLine(3, "Save it, then copy its Client ID into the field below.")
        }
        Spacer(Modifier.height(26.dp))
        SectionHead("Your App Keys")
        KeyField("Client ID", id, { id = it; error = null }, placeholder = "Paste or Type Your Client ID", onSubmit = ::submit)
        error?.let { Spacer(Modifier.height(12.dp)); ResultLine(Mark.WARN, it) }
        Spacer(Modifier.height(16.dp))
        if (checking) Row(verticalAlignment = Alignment.CenterVertically) {
            StatusMark(Mark.BUSY); Spacer(Modifier.width(12.dp))
            Column { Text("Checking Your Keys", style = DreamType.Row); Text("One Moment…", style = DreamType.Caption) }
        } else DreamButton("Sign In", ::submit, primary = true, enabled = id.isNotBlank())
        if (hint != null) {
            Spacer(Modifier.height(26.dp))
            SectionHead("Saved Keys")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("Your Trakt App", caption = "Client ID $hint")
                if (!confirmRemove) PageRow("Remove Trakt App Keys", { confirmRemove = true }, caption = "Signs every profile on this PC out of Trakt", chevron = false)
                else ConfirmCard("Remove Trakt App Keys?", "Dream OS forgets your Trakt app's keys, and every profile on this PC is signed out of Trakt. Everything already on this PC stays. You can enter keys again anytime.", "Remove",
                    onConfirm = { svc.trakt.setClientId(""); confirmRemove = false; onBack() }, onCancel = { confirmRemove = false })
            }
        }
    }
}

/** One numbered step: the number in a pearl disc, the words beside it (the iPhone's step(n, text)). */
@Composable
private fun NumberedLine(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).background(Dream.Pearl, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
            Text("$n", style = DreamType.Pill.copy(color = Dream.Ink, fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.width(14.dp))
        Text(text, style = DreamType.Body, modifier = Modifier.padding(top = 2.dp))
    }
}

private fun shortUrl(url: String) = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
