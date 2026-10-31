package com.dreamos.desktop.details

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamos.desktop.settings.kit.Dream
import com.dreamos.desktop.settings.kit.DreamType

/**
 * Parts laid out on one line with a dim dot between them; a part that doesn't fit starts the next line, and a
 * line never starts or ends with a dot (the iPhone's meta line rule).
 */
@Composable
fun DotLine(modifier: Modifier = Modifier, gap: Dp = 10.dp, rowGap: Dp = 6.dp, parts: List<@Composable () -> Unit>) {
    Layout(
        modifier = modifier,
        content = {
            parts.forEach { it() }
            repeat((parts.size - 1).coerceAtLeast(0)) { Text("·", style = DreamType.Meta.copy(color = Dream.TextFaint)) }
        },
    ) { measurables, constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth)
        val items = measurables.take(parts.size).map { it.measure(loose) }
        val dots = measurables.drop(parts.size).map { it.measure(loose) }
        val g = gap.roundToPx()
        val rg = rowGap.roundToPx()
        data class Place(val x: Int, val line: Int, val item: Int, val dot: Int?)
        val places = mutableListOf<Place>()
        var x = 0; var line = 0; var widest = 0
        items.forEachIndexed { i, p ->
            val dotW = if (i == 0) 0 else dots[i - 1].width + 2 * g
            if (i > 0 && x + dotW + p.width > constraints.maxWidth) { line++; x = 0 }
            val useDot = i > 0 && x > 0
            if (useDot) { places += Place(x + g, line, -1, i - 1); x += dotW }
            places += Place(x, line, i, null)
            x += p.width
            widest = maxOf(widest, x)
        }
        val lineH = IntArray(line + 1)
        places.forEach { pl -> val h = if (pl.item >= 0) items[pl.item].height else dots[pl.dot!!].height; lineH[pl.line] = maxOf(lineH[pl.line], h) }
        val tops = IntArray(line + 1).also { for (l in 1..line) it[l] = it[l - 1] + lineH[l - 1] + rg }
        val height = tops[line] + lineH[line]
        layout(widest.coerceAtMost(constraints.maxWidth), height) {
            places.forEach { pl ->
                val p = if (pl.item >= 0) items[pl.item] else dots[pl.dot!!]
                p.place(pl.x, tops[pl.line] + (lineH[pl.line] - p.height) / 2)
            }
        }
    }
}

/** The certification in a thin box ("PG-13", "TV-MA"). */
@Composable
fun CertBox(cert: String) {
    Text(
        cert, style = DreamType.Meta.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Dream.Text),
        modifier = Modifier.border(1.2.dp, Dream.Text.copy(alpha = 0.55f), RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** The drawn tomato (one for every score) and the score in its status color: 75+ green, 60 to 74 amber, under 60 red. */
@Composable
fun TomatoScore(score: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(17.dp)) {
            val s = size.minDimension
            drawCircle(Color(0xFFF2553F), s * 0.42f, Offset(s * 0.5f, s * 0.56f))
            val leaf = Path().apply {
                moveTo(s * 0.5f, s * 0.2f); lineTo(s * 0.3f, s * 0.12f); lineTo(s * 0.42f, s * 0.26f)
                lineTo(s * 0.26f, s * 0.32f); lineTo(s * 0.5f, s * 0.3f); lineTo(s * 0.74f, s * 0.32f)
                lineTo(s * 0.58f, s * 0.26f); lineTo(s * 0.7f, s * 0.12f); close()
            }
            drawPath(leaf, Color(0xFF4CC46A))
            drawRect(Color(0xFF4CC46A), Offset(s * 0.47f, s * 0.02f), Size(s * 0.06f, s * 0.2f))
        }
        Spacer(Modifier.width(6.dp))
        val c = when { score >= 75 -> Dream.GreenText; score >= 60 -> Dream.AmberText; else -> Dream.RedText }
        Text("$score%", style = DreamType.Meta.copy(color = c, fontWeight = FontWeight.SemiBold))
    }
}

/** The audience score as published: a small "IMDb" / "TMDB" tag and the number. No stars. */
@Composable
fun AudienceScore(tag: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Ratings.audienceBadge: the source as a small filled pill tag, then the number.
        Box(Modifier.background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp)) {
            Text(tag, style = DreamType.Meta.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, color = Dream.Text))
        }
        Spacer(Modifier.width(7.dp))
        Text(value, style = DreamType.Meta.copy(color = Dream.Text, fontWeight = FontWeight.SemiBold))
    }
}

/** The title page's meta line: date, cert, length, two genres, tomato, audience score. */
@Composable
fun MetaLine(d: TitleDetails, modifier: Modifier = Modifier) {
    val parts = mutableListOf<@Composable () -> Unit>()
    TitleWords.metaDate(d.releaseDate, d.year)?.let { t -> parts += { Text(t, style = DreamType.Meta) } }
    d.certification?.let { c -> parts += { CertBox(c) } }
    val length = if (d.ref.isShow) d.seasonCount?.let { TitleWords.seasons(it) } else d.runtimeMin?.let { TitleWords.runtime(it) }
    length?.let { t -> parts += { Text(t, style = DreamType.Meta) } }
    if (d.genres.isNotEmpty()) parts += { Text(d.genres.take(2).joinToString(", "), style = DreamType.Meta) }
    d.scores.tomato?.let { s -> parts += { TomatoScore(s) } }
    TitleWords.audience(d.scores)?.let { (tag, v) -> parts += { AudienceScore(tag, v) } }
    if (parts.isNotEmpty()) DotLine(modifier, parts = parts)
}
