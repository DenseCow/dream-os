package com.dreamos.desktop.home

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dreamos.desktop.details.AudienceScore
import com.dreamos.desktop.details.TomatoScore
import com.dreamos.desktop.shell.data.Catalog
import com.dreamos.desktop.shell.data.HeroItem

/**
 * A hero slide's scores after its meta line (the Fire Stick's hero): the tomato with its Tomatometer and the audience
 * score, the title page's own marks. Read once as the slide comes in, from what's kept (asked ahead for every slide
 * when the hero loads), so a slide never gains its scores while it is on screen.
 */
@Composable
fun HeroScoreLine(c: Catalog, h: HeroItem) {
    val s = remember(h.title.key) { c.scores(h.title) } ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        s.tomato?.let { Spacer(Modifier.width(14.dp)); TomatoScore(it) }
        s.audience?.let { a ->
            val tag = a.substringBefore(' ')
            val value = a.substringAfter(' ', "")
            if (value.isNotEmpty()) { Spacer(Modifier.width(14.dp)); AudienceScore(tag, value) }
        }
    }
}
