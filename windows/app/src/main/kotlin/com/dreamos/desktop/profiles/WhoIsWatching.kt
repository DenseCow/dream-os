package com.dreamos.desktop.profiles

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dreamos.desktop.settings.LocalPagesServices
import com.dreamos.desktop.settings.PinCheck
import com.dreamos.desktop.settings.Profile
import com.dreamos.desktop.settings.kit.Dream
import com.dreamos.desktop.settings.kit.DreamButton
import com.dreamos.desktop.settings.kit.DreamType
import com.dreamos.desktop.settings.kit.Glyph
import com.dreamos.desktop.settings.kit.GlyphIcon
import com.dreamos.desktop.settings.kit.MenuItem
import com.dreamos.desktop.settings.kit.OptionsMenu
import com.dreamos.desktop.settings.kit.PageFrame
import com.dreamos.desktop.settings.kit.dreamClick
import com.dreamos.desktop.settings.kit.lit
import com.dreamos.desktop.settings.kit.rainbowEdge
import com.dreamos.desktop.settings.kit.rainbowNow
import kotlinx.coroutines.launch

/**
 * Who's Watching?: every profile's face, Add Profile while there are fewer than 8, Edit Profiles / Done. A locked
 * profile asks for its PIN (Windows Hello first when it's on); leaving a Kids profile that asks needs a grown-up's.
 * [trusted] (opened from Settings) is the current profile, chosen without a PIN. [onClose] is null at launch.
 */
@Composable
fun WhoIsWatching(onChosen: (Profile) -> Unit, onClose: (() -> Unit)? = null, trusted: String? = null) {
    val svc = LocalPagesServices.current.profiles
    val scope = rememberCoroutineScope()
    var bump by remember { mutableIntStateOf(0) }
    val all = remember(bump) { svc.all() }
    var editing by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<Profile?>(null) }
    var pinFor by remember { mutableStateOf<Pair<Profile, Boolean>?>(null) } // profile, true = to edit it
    var helloOk by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { helloOk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { svc.helloReady } }

    editor?.let { p -> ProfileEditor(p, onClose = { editor = null; bump++ }); return }

    /**
     * Whose PIN editing [p] needs: its own when locked (not for the trusted current profile), else a grown-up's while
     * the profile in use is Kids with Ask for a PIN to Leave (so Kids can't switch its own limits off), else none.
     */
    fun editNeed(p: Profile): String? {
        val me = all.firstOrNull { it.id == svc.currentId() }
        return when {
            p.locked && p.id != trusted -> p.id
            me != null && me.kidsLevel < 3 && me.pinToLeave -> all.firstOrNull { it.kidsLevel >= 3 && it.locked }?.id
            else -> null
        }
    }
    fun edit(p: Profile) { if (editNeed(p) != null) pinFor = p to true else editor = p }
    fun choose(p: Profile) {
        if (editing) { edit(p); return }
        if (p.id == trusted) { onChosen(p); return }
        if (svc.pinNeededFor(p.id) != null) pinFor = p to false
        else scope.launch { if (svc.select(p.id) == PinCheck.Ok) onChosen(p) }
    }
    val first = remember { FocusRequester() }
    PageFrame(onClose) {
        Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Who's Watching?", style = DreamType.Display)
            Spacer(Modifier.height(44.dp))
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(Modifier.widthIn(max = 1100.dp), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                all.forEachIndexed { i, p ->
                    FaceTile(p.name.ifBlank { "Profile" }, if (i == 0) Modifier.focusRequester(first) else Modifier, locked = p.locked, editing = editing,
                        options = listOf(MenuItem("Edit Profile") { edit(p) }), onClick = { choose(p) }) { Face(p.avatar, p.color, 132.dp) }
                }
                if (all.size < 8) FaceTile("Add Profile", Modifier, onClick = { editor = Profile("", "", ProfileAvatars[all.size % 12], ProfileColors[all.size % 8].first) }) {
                    Box(Modifier.size(132.dp).border(2.dp, Dream.TextFaint, RoundedCornerShape(32.dp)), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Plus, Dream.TextDim, 44.dp) }
                }
            }
            Spacer(Modifier.height(48.dp))
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DreamButton(if (editing) "Done" else "Edit Profiles", { editing = !editing }, glyph = if (editing) Glyph.Check else Glyph.Pencil)
                if (onClose != null) DreamButton("Close", onClose)
            }
        }
        pinFor?.let { (p, toEdit) ->
            val need = if (toEdit) editNeed(p) ?: p.id else svc.pinNeededFor(p.id) ?: p.id
            val owner = all.firstOrNull { it.id == need } ?: p
            PinPad(
                PinMode.Verify("Enter PIN", "${owner.avatar}  ${owner.name}",
                    check = { pin -> if (toEdit) svc.checkPin(need, pin) else svc.select(p.id, pin) },
                    // Hello stands in for the profile's own PIN only, never for a grown-up's PIN to leave Kids (core's rule).
                    hello = if (helloOk && owner.hello && owner.id == p.id) ({
                        when {
                            !svc.helloVerify(if (toEdit) "Confirm it's you to edit this profile." else "Confirm it's you to open this profile.") -> "Windows Hello didn't work. Enter the PIN."
                            toEdit -> null
                            svc.selectTrusted(p.id) == PinCheck.Ok -> null
                            else -> "Enter the PIN to open this profile."
                        }
                    }) else null),
                onDone = { pin ->
                    pinFor = null
                    when {
                        toEdit -> editor = p
                        // A PIN or a Hello pass: either way the profile is already selected.
                        else -> onChosen(p)
                    }
                },
                onCancel = { pinFor = null },
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun FaceTile(name: String, modifier: Modifier, locked: Boolean = false, editing: Boolean = false, options: List<MenuItem> = emptyList(), onClick: () -> Unit, face: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val lit = src.lit()
    val glow = rainbowNow(lit.any)
    val s by animateFloatAsState(if (lit.any) 1.06f else 1f, tween(160))
    var menu by remember { mutableStateOf(false) }
    Column(modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.scale(s).rainbowEdge(lit.any, glow, 32.dp, edge = 3.dp, halo = if (lit.focused) 18.dp else 8.dp)
            .dreamClick(src, onSecondary = if (options.isEmpty()) null else ({ menu = true }), onClick = onClick)) {
            face()
            if (editing) Box(Modifier.size(132.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(32.dp)), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Pencil, Color.White, 36.dp) }
            if (locked) Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(30.dp).background(Color.Black.copy(alpha = 0.7f), CircleShape), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Lock, Color.White, 18.dp) }
        }
        Spacer(Modifier.height(12.dp))
        Text(name, style = DreamType.Row.copy(color = if (lit.any) Dream.Text else Dream.TextDim), textAlign = TextAlign.Center, maxLines = 2)
        if (menu) OptionsMenu(name, options, onDismiss = { menu = false })
    }
}
