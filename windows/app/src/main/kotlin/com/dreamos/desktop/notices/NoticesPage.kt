package com.dreamos.desktop.notices

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dreamos.desktop.settings.LocalPagesServices
import com.dreamos.desktop.settings.Notice
import com.dreamos.desktop.settings.NoticeType
import com.dreamos.desktop.settings.kit.Dream
import com.dreamos.desktop.settings.kit.DreamButton
import com.dreamos.desktop.settings.kit.DreamType
import com.dreamos.desktop.settings.kit.Glyph
import com.dreamos.desktop.settings.kit.GlyphIcon
import com.dreamos.desktop.settings.kit.MenuItem
import com.dreamos.desktop.settings.kit.OptionsMenu
import com.dreamos.desktop.settings.kit.PageScaffold
import com.dreamos.desktop.settings.kit.Radii
import com.dreamos.desktop.settings.kit.dreamClick
import com.dreamos.desktop.settings.kit.lit
import com.dreamos.desktop.settings.kit.litColors
import com.dreamos.desktop.settings.kit.rainbowEdge
import com.dreamos.desktop.settings.kit.rainbowNow

/** The notice's emoji (owner allows emoji for notices, 2026-10-29), as on the Fire Stick's banners. */
fun noticeEmoji(n: Notice): String = when {
    n.critical -> "⚠️"
    n.warning && n.type == NoticeType.Limit -> "⚠️"
    n.type == NoticeType.Expiry -> "⏳"
    n.type == NoticeType.Limit -> "⚠️"
    n.type == NoticeType.Episodes -> "📺"
    n.type == NoticeType.Update -> "✨"
    n.type == NoticeType.Summary -> "🗓️"
    else -> "🔔"
}

/**
 * Notifications: the count line, Mark All Read and Clear All, then each notice (unread ones bright with the pearl
 * dot). Selecting runs its action; right-click offers Mark as Read and Remove Notice.
 */
@Composable
fun NoticesPage(onBack: () -> Unit) {
    val svc = LocalPagesServices.current
    val nav = com.dreamos.desktop.settings.kit.LocalPagesNav.current
    var bump by remember { mutableIntStateOf(0) }
    val items = remember(bump) { svc.notices.list() }
    val unread = items.count { !it.read }
    PageScaffold("Notifications", onBack, subtitle = when { items.isEmpty() -> "Nothing New"; unread == 0 -> "All Caught Up"; unread == 1 -> "1 Unread"; else -> "$unread Unread" }) {
        if (items.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                GlyphIcon(Glyph.Bell, Dream.TextDim, 44.dp)
                Spacer(Modifier.height(14.dp))
                Text("You're All Caught Up", style = DreamType.Section)
                Spacer(Modifier.height(6.dp))
                Text("Account alerts and other news from Dream OS show up here.", style = DreamType.Caption)
            }
            return@PageScaffold
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DreamButton("Mark All Read", { svc.notices.markAllRead(); bump++ })
            DreamButton("Clear All", { svc.notices.clearAll(); bump++ })
        }
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { n -> NoticeRow(n, onOpen = { svc.notices.open(n.key); bump++; runNoticeTarget(nav, n.target) }, onRemove = { svc.notices.remove(n.key); bump++ }) }
        }
    }
}

@Composable
private fun NoticeRow(n: Notice, onOpen: () -> Unit, onRemove: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val lit = src.lit()
    val glow = rainbowNow(lit.any)
    val (fill, ink) = litColors(lit)
    val dim = if (lit.focused) Dream.InkDim else Dream.TextDim
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .rainbowEdge(lit.any, glow, Radii.Row, edge = if (lit.focused) 3.dp else 2.dp, halo = if (lit.focused) 12.dp else 0.dp)
            .background(fill, RoundedCornerShape(Radii.Row))
            .dreamClick(src, onSecondary = { menu = true }, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(40.dp).background(Color(0xFF1A1A1D), CircleShape), contentAlignment = Alignment.Center) { Text(noticeEmoji(n), style = DreamType.Row) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!n.read) { Box(Modifier.size(8.dp).background(if (lit.focused) Dream.Ink else Dream.Pearl, CircleShape)); Spacer(Modifier.width(8.dp)) }
                Text(n.title, style = DreamType.Row.copy(color = ink, fontWeight = if (n.read) FontWeight.Medium else FontWeight.SemiBold))
            }
            Spacer(Modifier.height(3.dp))
            Text(n.body, style = DreamType.Caption.copy(color = dim))
            Spacer(Modifier.height(4.dp))
            Text(listOfNotNull(n.agoWords, n.actionLabel).joinToString("   ·   "), style = DreamType.Small.copy(color = dim))
        }
        if (menu) OptionsMenu(n.title, listOfNotNull(
            if (!n.read) MenuItem("Mark as Read", onOpen) else null,
            MenuItem("Remove Notice", onRemove),
        ), onDismiss = { menu = false })
    }
}

/**
 * Runs a notice's action: "renew:<svc>" opens the service's renewal page, "update" the release page,
 * "title:<kind>:<id>:<name>" that title, "accounts" Settings. Others ("home") are the shell's.
 */
fun runNoticeTarget(nav: com.dreamos.desktop.settings.kit.PagesNav, target: String?) {
    val t = target ?: return
    when {
        t.startsWith("renew:") -> com.dreamos.desktop.core.playback.PremiumExpiry.renewUrl(t)?.let(nav::openUrl)
        t == "update" -> nav.openUpdate()
        t == "accounts" -> nav.openSettings()
        t.startsWith("title:") -> t.split(':').let { p -> p.getOrNull(2)?.toIntOrNull()?.let { id -> nav.openTitle(com.dreamos.desktop.settings.kit.TitleRef(id, p[1] == "tv", p.drop(3).joinToString(":"))) } }
    }
}
