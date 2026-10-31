package com.dreamos.desktop.profiles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.dreamos.desktop.settings.Profile

/** The profile colors (#7C5CFF … #2DD4BF) with names for screen readers and the color row. */
val ProfileColors = listOf(
    0xFF7C5CFF to "Violet", 0xFF38BDF8 to "Sky Blue", 0xFFF472B6 to "Pink", 0xFF4ADE80 to "Green",
    0xFFFBBF24 to "Amber", 0xFFF87171 to "Coral", 0xFFA78BFA to "Lavender", 0xFF2DD4BF to "Teal",
)

val ProfileAvatars = listOf("😀", "😎", "🦄", "🐱", "🐶", "🦊", "👾", "🌟", "🚀", "🎮", "🌸", "🐼")

/** A profile's face: its emoji on a rounded square of its color (a soft top-to-bottom shade of it). */
@Composable
fun ProfileFace(p: Profile, size: Dp, modifier: Modifier = Modifier) = Face(p.avatar, p.color, size, modifier)

@Composable
fun Face(avatar: String, color: Long, size: Dp, modifier: Modifier = Modifier) {
    val c = Color(color)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            drawRoundRect(Brush.verticalGradient(listOf(c, c.copy(red = c.red * 0.62f, green = c.green * 0.62f, blue = c.blue * 0.62f))), cornerRadius = CornerRadius(this.size.minDimension * 0.24f))
        }
        Text(avatar, fontSize = (size.value * 0.5f).sp)
    }
}
