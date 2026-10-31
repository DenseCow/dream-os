package com.dreamos.desktop.settings.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A text field with its visible label: the glass box, a rainbow edge while typing, and Paste beside it (keys and
 * addresses are pasted far more than typed). Enter runs [onSubmit].
 */
@Composable
fun KeyField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String? = null, onSubmit: (() -> Unit)? = null, maxWidth: Dp = 640.dp, fieldModifier: Modifier = Modifier) {
    val src = remember { MutableInteractionSource() }
    val lit = src.lit()
    val glow = rainbowNow(lit.focused)
    val clip = LocalClipboardManager.current
    Column(modifier.widthIn(max = maxWidth)) {
        Text(label, style = DreamType.Meta.copy(color = Dream.Text))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f)
                    .rainbowEdge(lit.focused, glow, 10.dp, edge = 2.dp, halo = 0.dp)
                    .background(if (lit.hovered || lit.focused) Dream.GlassHover else Dream.Glass, RoundedCornerShape(10.dp))
                    .then(if (!lit.focused) Modifier.border(1.dp, Dream.GlassEdge, RoundedCornerShape(10.dp)) else Modifier)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                if (value.isEmpty() && placeholder != null) Text(placeholder, style = DreamType.Row.copy(color = Dream.TextFaint))
                BasicTextField(
                    value, onChange, singleLine = true, interactionSource = src,
                    textStyle = DreamType.Row, cursorBrush = SolidColor(Dream.Text),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
                    modifier = fieldModifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Text).onPreviewKeyEvent { e ->
                        if (onSubmit != null && e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { onSubmit(); true } else false
                    },
                )
            }
            Spacer(Modifier.width(12.dp))
            DreamButton("Paste", { clip.getText()?.text?.trim()?.let(onChange) }, glyph = Glyph.Copy)
        }
    }
}
