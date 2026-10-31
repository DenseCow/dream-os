package com.dreamos.desktop.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.dreamos.desktop.core.model.HubTile

/*
 * The seasonal hub pieces (fire-home SeasonUi): the hub's tile (Home's hub row, a genre page's hub row, a hub page's
 * More to Celebrate) and its page's masthead.
 */

/** Graphite, never a hue: a hub's flavor comes from its glyph, its art and its words (the Fire Stick's tile). */
private val TILE_TOP = Color(0xFF1D1D20)
private val TILE_BOTTOM = Color(0xFF0B0B0D)

/** "Night 30 of 31" under the tile, else the kicker under a countdown, else "Seasonal Collection" (fire-home SeasonUi.tile). */
fun hubTileSub(tile: HubTile): String = tile.night ?: if (tile.hasCountdown) tile.kicker else "Seasonal Collection"

/**
 * A hub's card, the size of a row's landscape card: a graphite tile, the hub's glyph top right in its rainbow hue,
 * its kicker and title bottom left. Under it the countdown or tagline ("1 Night Until Halloween"), then "Night 30 of
 * 31" (or its kicker).
 */
@Composable
fun HubTileCard(tile: HubTile, w: Dp) {
    val m = LocalMetrics.current
    val acts = LocalCardActions.current
    val h = w * 9f / 16f
    val shape = RoundedCornerShape(m.radius)
    Column(Modifier.width(w)) {
        Box(
            Modifier.size(w, h)
                .semantics { contentDescription = "${tile.title}, ${tile.status}" }
                .dreamCard(m.radius, { acts.openHub(tile) })
                .clip(shape)
                .background(Brush.linearGradient(listOf(TILE_TOP, TILE_BOTTOM), Offset.Zero, Offset.Infinite))
                .border(m.u(1), Color(0x17FFFFFF), shape),
        ) {
            GenreGlyph(tile.glyph, Rainbow.hue(tile.hue), Modifier.align(Alignment.TopEnd).padding(top = m.u(12), end = m.u(12)).size(m.u(50)))
            Column(Modifier.align(Alignment.BottomStart).padding(start = m.u(14), end = m.u(14), bottom = m.u(12))) {
                Text(tile.kicker, style = Type.kicker().copy(color = Palette.text.copy(alpha = 0.66f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(m.u(5)))
                Text(tile.title, style = Type.style(16.5f, FontWeight.SemiBold, lineHeight = 1.1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Caption(tile.status, hubTileSub(tile), w)
    }
}

/**
 * A hub page's masthead under the bar (fire-home SeasonUi.masthead): the glyph, then the kicker, the hub's name large
 * and the countdown with the night ("1 Night Until Halloween  ·  Night 30 of 31"), or the tagline; under a countdown
 * the tagline follows on its own line. Not a control: the first thing to click is Tonight's Pick under it.
 */
@Composable
fun HubMasthead(tile: HubTile, modifier: Modifier = Modifier) {
    val m = LocalMetrics.current
    val countdown = if (tile.hasCountdown) tile.status else null
    val line = listOfNotNull(countdown, tile.night).joinToString(DOT).ifEmpty { tile.tagline }
    Row(modifier.fillMaxWidth().padding(horizontal = m.gutter), verticalAlignment = Alignment.CenterVertically) {
        GenreGlyph(tile.glyph, Rainbow.hue(tile.hue), Modifier.size(m.u(76)))
        Spacer(Modifier.width(m.u(22)))
        Column {
            Text(tile.kicker, style = Type.kicker(), maxLines = 1)
            Spacer(Modifier.height(m.u(6)))
            Text(tile.title, style = Type.pageTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(m.u(8)))
            Text(line, style = Type.pageSub(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (countdown != null && tile.tagline.isNotEmpty()) {
                Spacer(Modifier.height(m.u(4)))
                Text(tile.tagline, style = Type.style(14f, FontWeight.Normal, color = Palette.textFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
