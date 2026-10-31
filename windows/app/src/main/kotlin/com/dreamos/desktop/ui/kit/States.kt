package com.dreamos.desktop.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign

/** A page's own title and one line about it (Browse, My List, a hub without a hero). */
@Composable
fun PageHeader(title: String, sub: String?, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val m = LocalMetrics.current
    Row(modifier.fillMaxWidth().padding(horizontal = m.gutter), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.pageTitle(), maxLines = 1)
            if (sub != null) {
                Spacer(Modifier.height(m.u(6)))
                Text(sub, style = Type.pageSub(), maxLines = 2)
            }
        }
        trailing()
    }
}

/**
 * An empty, error or first-run state: a drawn line icon in a quiet disc, a title, a sentence saying why and what to
 * do, and the one action that helps (when there is one).
 */
@Composable
fun EmptyState(icon: Icon, title: String, message: String, action: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val m = LocalMetrics.current
    Column(modifier.fillMaxWidth().padding(horizontal = m.gutter, vertical = m.u(48)), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(m.u(76)).clip(CircleShape).background(Color(0x14FFFFFF)).border(m.u(1), Palette.hairline, CircleShape), contentAlignment = Alignment.Center) {
            LineIcon(icon, Palette.textDim, Modifier.size(m.u(34)))
        }
        Spacer(Modifier.height(m.u(20)))
        Text(title, style = Type.style(24f, androidx.compose.ui.text.font.FontWeight.SemiBold), textAlign = TextAlign.Center)
        Spacer(Modifier.height(m.u(10)))
        Text(message, style = Type.body(), textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = m.u(520)))
        if (action != null && onAction != null) {
            Spacer(Modifier.height(m.u(24)))
            PearlButton(action, onAction)
        }
    }
}

/** The end of a long page: a quiet line and a way back to the top. */
@Composable
fun EndCap(onTop: () -> Unit) {
    val m = LocalMetrics.current
    Column(Modifier.fillMaxWidth().padding(top = m.u(36), bottom = m.u(64)), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(m.u(40), m.u(3)).rainbowFill(CircleShape))
        Spacer(Modifier.height(m.u(18)))
        Row(horizontalArrangement = Arrangement.Center) { PearlButton("Back to Top", onTop, icon = Icon.UP) }
    }
}
