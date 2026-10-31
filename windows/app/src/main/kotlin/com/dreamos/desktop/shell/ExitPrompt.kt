package com.dreamos.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dreamos.desktop.notices.Scaled
import com.dreamos.desktop.notices.shellScale
import com.dreamos.desktop.settings.kit.Dream
import com.dreamos.desktop.settings.kit.DreamButton
import com.dreamos.desktop.settings.kit.DreamType
import com.dreamos.desktop.ui.kit.Focus

/**
 * Esc at the very root (Home, nothing open, no page on the stack): "Exit Dream OS?" in the middle of a dimmed window,
 * Cancel first and focused, then Exit (the confirmations' order). Esc, Backspace, Cancel or a click outside closes it
 * ([onCancel]); Exit quits through the window's own close ([ShellState.exit], as the avatar menu's Exit Dream OS does
 * without asking). Focus stays inside while it is open; Left and Right move between the two buttons.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ExitPrompt(state: ShellState, onCancel: () -> Unit) {
    if (!state.askExit) return
    val cancel = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    Box(
        Modifier.fillMaxSize()
            .background(Color(0x99000000))
            // A click outside cancels; a press on the card reaches here too, already consumed (MenuLayer's rule).
            .onPointerEvent(PointerEventType.Press) { e -> if (e.changes.none { it.isConsumed }) onCancel() }
            .onPreviewKeyEvent { k ->
                if (k.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (k.key) {
                    Key.Escape, Key.Backspace -> { onCancel(); true }
                    Key.DirectionLeft -> { Focus.move(fm, FocusDirection.Left, "exit prompt: left"); true }
                    Key.DirectionRight -> { Focus.move(fm, FocusDirection.Right, "exit prompt: right"); true }
                    Key.DirectionUp, Key.DirectionDown -> true
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Cancel takes focus a frame in, once the buttons listen for it (asked any sooner, the focus came before the
        // button's look was watching and Cancel showed unlit), and again for a few frames until the card holds it.
        var held by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            repeat(12) {
                withFrameNanos { }
                if (held) return@LaunchedEffect
                Focus.request(cancel, "exit prompt: Cancel")
            }
        }
        Scaled(shellScale()) {
            Column(
                Modifier.width(440.dp)
                    .semantics { paneTitle = "Exit Dream OS?" }
                    .background(Dream.Panel, RoundedCornerShape(16.dp))
                    .border(1.dp, Dream.PanelEdge, RoundedCornerShape(16.dp))
                    .onPointerEvent(PointerEventType.Press) { it.changes.forEach { c -> c.consume() } }
                    .onFocusChanged { held = it.hasFocus }
                    .focusProperties { exit = { if (state.askExit) FocusRequester.Cancel else FocusRequester.Default } }
                    .focusGroup()
                    .padding(24.dp),
            ) {
                Text("Exit Dream OS?", style = DreamType.Section)
                Spacer(Modifier.height(8.dp))
                Text("You can open it again from the Start menu.", style = DreamType.Body.copy(color = Dream.TextDim))
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DreamButton("Cancel", onCancel, modifier = Modifier.focusRequester(cancel))
                    DreamButton("Exit", { state.askExit = false; state.exit() })
                }
            }
        }
    }
}
