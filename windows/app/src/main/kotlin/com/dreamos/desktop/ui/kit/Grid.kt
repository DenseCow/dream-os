package com.dreamos.desktop.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import com.dreamos.desktop.shell.data.CardItem

/**
 * A poster wall as lines of a page's list ([cols] across, between both gutters), each line keeping [Metrics.liftRoom]
 * above and below so a lit poster's ring is never cut. Lines are list items, so the page scrolls them like rows and
 * keyboard focus moves straight up and down the columns.
 */
fun LazyListScope.posterLines(keyPrefix: String, items: List<CardItem>, cols: Int, w: Dp, m: Metrics) {
    val lines = items.chunked(cols)
    for ((i, line) in lines.withIndex()) {
        item(key = "$keyPrefix:$i:${line.first().title.key}") {
            Row(
                Modifier.fillMaxWidth().padding(start = m.gutter, end = m.gutter, top = m.liftRoom, bottom = m.liftRoom - m.u(6)),
                horizontalArrangement = Arrangement.spacedBy(m.cardGap),
            ) {
                for (it in line) PosterCard(it, w)
            }
        }
    }
}

/** A poster wall's lines while they load: still poster shapes. */
fun LazyListScope.skeletonLines(key: String, cols: Int, w: Dp, m: Metrics, lines: Int = 2) {
    repeat(lines) { i ->
        item(key = "$key:skel:$i") {
            Row(Modifier.fillMaxWidth().padding(start = m.gutter, end = m.gutter, top = m.liftRoom, bottom = m.liftRoom), horizontalArrangement = Arrangement.spacedBy(m.cardGap)) {
                repeat(cols) { SkeletonPoster(w, m) }
            }
        }
    }
}

@Composable
private fun SkeletonPoster(w: Dp, m: Metrics) {
    Column {
        Box(Modifier.size(w, w * 1.5f).clip(RoundedCornerShape(m.radius)).background(Brush.verticalGradient(listOf(Palette.surfaceHigh, Palette.surface))))
        Spacer(Modifier.height(m.u(10)))
        Box(Modifier.size(w * 0.62f, m.u(11)).clip(RoundedCornerShape(50)).background(Palette.surfaceHigh))
    }
}
