package com.dreamos.desktop.settings.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/** One choice in an options menu. [MenuItem.Divider] draws a hairline between groups of choices. */
data class MenuItem(val label: String, val action: () -> Unit) {
    companion object { val Divider = MenuItem("") {} }
}

/**
 * The right-click options (the Fire Stick's ☰ Options as a menu at the pointer): a graphite panel with the title
 * and its choices. Esc or a click outside closes it; arrows and Enter work as everywhere.
 */
@Composable
fun OptionsMenu(title: String?, items: List<MenuItem>, onDismiss: () -> Unit, offset: IntOffset = IntOffset(0, 0), note: String? = null) {
    Popup(
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
        onPreviewKeyEvent = { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onDismiss(); true } else false },
    ) {
        val first = remember { FocusRequester() }
        val fm = LocalFocusManager.current
        Column(
            Modifier.widthIn(min = 240.dp, max = 420.dp)
                .heightIn(max = 560.dp)
                .background(Dream.Panel, RoundedCornerShape(14.dp))
                .border(1.dp, Dream.PanelEdge, RoundedCornerShape(14.dp))
                .verticalScroll(rememberScrollState())
                .padding(10.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> { fm.moveFocus(FocusDirection.Down); true }
                        Key.DirectionUp -> { fm.moveFocus(FocusDirection.Up); true }
                        else -> false
                    }
                },
        ) {
            if (title != null) {
                Text(title, style = DreamType.Meta, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp))
            }
            if (note != null) Text(note, style = DreamType.Row.copy(color = Dream.TextDim), modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            val firstReal = items.indexOfFirst { it !== MenuItem.Divider }
            items.forEachIndexed { i, item ->
                if (item === MenuItem.Divider) Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(Dream.PanelEdge))
                else MenuRow(item.label, if (i == firstReal) Modifier.focusRequester(first) else Modifier) { onDismiss(); item.action() }
                if (i < items.lastIndex) Spacer(Modifier.height(4.dp))
            }
        }
        LaunchedEffect(items.size) { if (items.any { it !== MenuItem.Divider }) runCatching { first.requestFocus() } }
    }
}

@Composable
private fun MenuRow(label: String, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val lit = src.lit()
    val (fill, ink) = litColors(lit, rest = androidx.compose.ui.graphics.Color.Transparent)
    Box(
        modifier.fillMaxWidth().background(fill, RoundedCornerShape(9.dp)).dreamClick(src, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(label, style = DreamType.Row.copy(color = ink)) }
}
