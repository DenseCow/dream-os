package com.dreamos.desktop.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamos.desktop.settings.ConfirmCard
import com.dreamos.desktop.settings.KidsLevels
import com.dreamos.desktop.settings.LocalPagesServices
import com.dreamos.desktop.settings.Profile
import com.dreamos.desktop.settings.kit.CycleRow
import com.dreamos.desktop.settings.kit.Dream
import com.dreamos.desktop.settings.kit.DreamButton
import com.dreamos.desktop.settings.kit.DreamType
import com.dreamos.desktop.settings.kit.KeyField
import com.dreamos.desktop.settings.kit.Note
import com.dreamos.desktop.settings.kit.PageRow
import com.dreamos.desktop.settings.kit.PageScaffold
import com.dreamos.desktop.settings.kit.SectionHead
import com.dreamos.desktop.settings.kit.SwitchRow
import com.dreamos.desktop.settings.kit.dreamClick
import com.dreamos.desktop.settings.kit.lit
import com.dreamos.desktop.settings.kit.rainbowEdge
import com.dreamos.desktop.settings.kit.rainbowNow
import kotlinx.coroutines.launch

/**
 * Add Profile / Edit Profile: name, picture, color, Kids (Maximum Rating, Ask for a PIN to Leave), Lock With a PIN,
 * Unlock With Windows Hello (when this PC has it), Remove Profile. A blank [start] id adds a new profile.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditor(start: Profile, onClose: () -> Unit) {
    val svc = LocalPagesServices.current.profiles
    val scope = rememberCoroutineScope()
    val adding = start.id.isBlank()
    var p by remember { mutableStateOf(start) }
    var newPin by remember { mutableStateOf<String?>(null) }
    var choosingPin by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var helloOk by remember { mutableStateOf(false) }
    var ratingPage by remember { mutableStateOf(false) }
    /** Kids was switched by hand: the name no longer guesses it (KidsProfiles' name rule). */
    var kidsTouched by remember { mutableStateOf(!adding) }
    var ownerPin by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { helloOk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { svc.helloReady } }
    // The grown-up whose PIN a Kids profile asks for to leave: the first profile that isn't Kids (the Fire Stick's owner).
    val owner = svc.all().firstOrNull { it.kidsLevel >= 3 && it.id != p.id }
    if (ratingPage) {
        com.dreamos.desktop.settings.ChoicePage("Maximum Rating", KidsLevels.all.map { it.first to it.third }, KidsLevels.all[p.kidsLevel.coerceIn(0, 3)].first, { ratingPage = false },
            note = "Movies and shows rated above this don't appear on this profile.") { pick ->
            val lv = KidsLevels.all.indexOfFirst { it.first == pick }
            p = p.copy(kidsLevel = lv, pinToLeave = p.pinToLeave && lv < 3)
        }
        return
    }
    val locked = if (newPin != null) newPin!!.isNotEmpty() else p.locked
    fun save() {
        if (p.name.isBlank()) { error = "Type a name first."; return }
        scope.launch {
            runCatching { svc.save(p.copy(name = p.name.trim().take(20)), newPin) }.onSuccess { onClose() }.onFailure { error = "Couldn't save this profile. Try again." }
        }
    }
    Box(Modifier.fillMaxSize()) {
    PageScaffold(if (adding) "Add Profile" else "Edit Profile", onClose) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Face(p.avatar, p.color, 96.dp)
            Spacer(Modifier.width(24.dp))
            KeyField("Name", p.name, { name ->
                p = p.copy(name = name.take(20)); error = null
                // A new profile named Kids, Junior and so on starts as Kids until the switch is touched.
                if (!kidsTouched) p = p.copy(kidsLevel = if (KIDS_NAME.containsMatchIn(name)) 1 else 3)
            }, placeholder = "Name", onSubmit = ::save, maxWidth = 420.dp)
        }
        error?.let { Spacer(Modifier.height(10.dp)); Text(it, style = DreamType.Row.copy(color = Dream.RedText)) }
        Spacer(Modifier.height(26.dp))
        SectionHead("Picture")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ProfileAvatars.forEach { a -> Choice(a == p.avatar, { p = p.copy(avatar = a) }) { Text(a, fontSize = 30.sp) } }
        }
        Spacer(Modifier.height(22.dp))
        SectionHead("Color")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ProfileColors.forEach { (c, _) -> Choice(c == p.color, { p = p.copy(color = c) }) { Box(Modifier.size(40.dp).background(Color(c), CircleShape)) } }
        }
        Spacer(Modifier.height(26.dp))
        SectionHead("Kids")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val kids = p.kidsLevel < 3
            SwitchRow("Kids Profile", kids, { on -> kidsTouched = true; p = p.copy(kidsLevel = if (on) 1 else 3, pinToLeave = p.pinToLeave && on) }, caption = "Limits movies and shows to the rating you choose")
            if (kids) {
                val lv = KidsLevels.all[p.kidsLevel.coerceIn(0, 2)]
                PageRow("Maximum Rating", { ratingPage = true }, value = "${lv.first}  ·  ${lv.second}", caption = lv.third)
                // Only with a grown-up to ask; without their PIN yet, turning it on sets one for them first.
                if (owner != null) SwitchRow("Ask for a PIN to Leave", p.pinToLeave, { on ->
                    if (on && !owner.locked) ownerPin = true else p = p.copy(pinToLeave = on)
                }, caption = if (owner.locked) "Uses ${owner.name}'s PIN" else "Sets a PIN for ${owner.name}")
            }
        }
        Spacer(Modifier.height(26.dp))
        SectionHead("Profile")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwitchRow("Lock With a PIN", locked, { on -> if (on) choosingPin = true else { newPin = ""; p = p.copy(hello = false) } }, caption = "Asks for a 4-digit PIN when this profile is chosen")
            if (locked && helloOk) SwitchRow("Unlock With Windows Hello", p.hello, { on ->
                if (!on) p = p.copy(hello = false)
                else scope.launch { if (svc.helloVerify("Confirm it's you to turn on Windows Hello.")) p = p.copy(hello = true) else error = "Couldn't turn on Windows Hello. Try again." }
            }, caption = "Asks for Windows Hello first; the PIN still works")
            val removable = !adding && svc.all().firstOrNull()?.id != p.id && svc.currentId() != p.id
            if (removable) {
                if (!confirm) PageRow("Remove Profile", { confirm = true }, caption = "Also removes its list and history", chevron = false)
                else ConfirmCard("Remove ${p.name.ifBlank { "This Profile" }}?", "Its list, history and look are removed from this PC.", "Remove",
                    onConfirm = { scope.launch { runCatching { svc.remove(p.id) }.onSuccess { onClose() }.onFailure { error = "Couldn't remove this profile. Try again."; confirm = false } } },
                    onCancel = { confirm = false })
            }
        }
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DreamButton(if (adding) "Create Profile" else "Done", ::save, primary = true)
            DreamButton("Cancel", onClose)
        }
        if (adding) { Spacer(Modifier.height(10.dp)); Note("Each profile keeps its own list, history and look. A locked profile asks for its PIN when chosen or edited.") }
    }
    if (choosingPin) PinPad(PinMode.Choose(p.name), onDone = { pin -> newPin = pin; choosingPin = false }, onCancel = { choosingPin = false })
    if (ownerPin && owner != null) PinPad(PinMode.Choose(owner.name), onDone = { pin ->
        ownerPin = false
        scope.launch { runCatching { svc.save(owner, pin) }.onSuccess { p = p.copy(pinToLeave = true) }.onFailure { error = "Couldn't save this profile. Try again." } }
    }, onCancel = { ownerPin = false })
    }
}

/** Names that start a new profile as Kids (fire-home KidsProfiles.KIDS_NAME). */
private val KIDS_NAME = Regex("(?i)\\b(kids?|kiddos?|children|child|junior|juniors)\\b")

/** A picture or color choice: a ring when chosen, the rainbow edge when lit. */
@Composable
private fun Choice(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val lit = src.lit()
    val glow = rainbowNow(lit.any)
    Box(
        Modifier.size(58.dp)
            .rainbowEdge(lit.any, glow, 14.dp, edge = 2.5.dp, halo = 0.dp)
            .background(if (lit.hovered) Dream.GlassHover else Dream.Glass, RoundedCornerShape(14.dp))
            .then(if (selected) Modifier.border(2.dp, Dream.Pearl, RoundedCornerShape(14.dp)) else Modifier)
            .dreamClick(src, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
