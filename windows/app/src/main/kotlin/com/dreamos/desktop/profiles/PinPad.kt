package com.dreamos.desktop.profiles

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamos.desktop.settings.PinCheck
import com.dreamos.desktop.settings.kit.Dream
import com.dreamos.desktop.settings.kit.DreamButton
import com.dreamos.desktop.settings.kit.DreamType
import com.dreamos.desktop.settings.kit.Glyph
import com.dreamos.desktop.settings.kit.GlyphIcon
import com.dreamos.desktop.settings.kit.dreamClick
import com.dreamos.desktop.settings.kit.lit
import com.dreamos.desktop.settings.kit.litColors
import com.dreamos.desktop.settings.kit.rainbowEdge
import com.dreamos.desktop.settings.kit.rainbowNow
import kotlinx.coroutines.launch

/** What the pad is for: checking a PIN, or choosing a new one (typed twice). */
sealed interface PinMode {
    /**
     * [check] answers a try; [hello] (when set) asks Windows Hello instead and does what a right PIN would (opens the
     * profile through core's trusted select): null when that worked, else the line to show ("Enter the PIN.").
     */
    data class Verify(val headline: String, val line: String, val check: suspend (String) -> PinCheck, val hello: (suspend () -> String?)? = null) : PinMode
    data class Choose(val name: String) : PinMode
}

/**
 * The PIN pad over the page: headline, the line under it (a red error replaces it), four dots, the keypad (or the
 * number keys), Cancel. Windows Hello, when on for the profile, comes up by itself once; Use Windows Hello retries.
 * [onDone] gets the PIN (the chosen one, or the one that passed).
 */
@Composable
fun PinPad(mode: PinMode, onDone: (String) -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var digits by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val shake = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }
    fun nudge() { scope.launch { for (x in listOf(12f, -12f, 8f, -8f, 3f, 0f)) shake.animateTo(x, tween(50)) } }
    fun fail(msg: String) { error = msg; digits = ""; nudge() }
    fun submit(pin: String) {
        when (mode) {
            is PinMode.Verify -> scope.launch {
                when (val r = mode.check(pin)) {
                    PinCheck.Ok -> onDone(pin)
                    // The Fire Stick's words (PinPad.kt).
                    PinCheck.Wrong -> fail("Wrong PIN, try again")
                    is PinCheck.Wait -> fail("Too many tries. Try again in " + if (r.seconds < 60) "${r.seconds} seconds" else "${(r.seconds + 59) / 60} minutes")
                }
            }
            is PinMode.Choose -> if (first == null) { first = pin; digits = ""; error = null }
                else if (first == pin) onDone(pin) else { first = null; fail("PINs didn't match") }
        }
    }
    fun press(d: Char) { if (digits.length < 4) { digits += d; error = null; if (digits.length == 4) submit(digits) } }
    fun hello() { val h = (mode as? PinMode.Verify)?.hello ?: return; scope.launch { val problem = h(); if (problem == null) onDone("") else error = problem } }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() }; hello() }
    com.dreamos.desktop.settings.kit.BackHandler(onCancel)

    val (headline, line) = when (mode) {
        is PinMode.Verify -> mode.headline to mode.line
        is PinMode.Choose -> if (first == null) "Choose a 4-Digit PIN" to mode.name.ifBlank { "This Profile" } else "Confirm Your PIN" to "Enter the same 4 digits again"
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f))
            .clickable(remember { MutableInteractionSource() }, null) {}
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val c = e.utf16CodePoint.toChar()
                when {
                    c in '0'..'9' -> { press(c); true }
                    e.key == Key.Backspace -> { digits = digits.dropLast(1); true }
                    e.key == Key.Escape -> { onCancel(); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.offset(x = shake.value.dp).width(380.dp).background(Dream.Panel, RoundedCornerShape(20.dp)).border(1.dp, Dream.PanelEdge, RoundedCornerShape(20.dp)).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(headline, style = DreamType.SubTitle)
            Spacer(Modifier.height(6.dp))
            Text(error ?: line, style = DreamType.Body.copy(color = if (error != null) Dream.RedText else Dream.TextDim))
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(4) { i -> Box(Modifier.size(16.dp).background(if (i < digits.length) Dream.Pearl else Color.Transparent, CircleShape).border(2.dp, Dream.TextDim, CircleShape)) }
            }
            Spacer(Modifier.height(22.dp))
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "del", "0", "done")
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { k ->
                        PadKey(k) {
                            // ✓ before four digits shakes, as on the Fire Stick.
                            when (k) { "del" -> digits = digits.dropLast(1); "done" -> if (digits.length == 4) submit(digits) else nudge(); else -> press(k[0]) }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DreamButton("Cancel", onCancel)
                if ((mode as? PinMode.Verify)?.hello != null) DreamButton("Use Windows Hello", { hello() }, glyph = Glyph.Face)
            }
        }
    }
}

@Composable
private fun PadKey(k: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val lit = src.lit()
    val glow = rainbowNow(lit.any)
    val (fill, ink) = litColors(lit)
    Box(
        Modifier.size(width = 92.dp, height = 56.dp)
            .rainbowEdge(lit.any, glow, 12.dp, edge = if (lit.focused) 3.dp else 2.dp, halo = 0.dp)
            .background(fill, RoundedCornerShape(12.dp)).dreamClick(src, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (k) {
            "del" -> GlyphIcon(Glyph.Back, ink, 22.dp)
            "done" -> GlyphIcon(Glyph.Check, ink, 22.dp)
            else -> Text(k, style = DreamType.Big.copy(color = ink, fontSize = 24.sp))
        }
    }
}
