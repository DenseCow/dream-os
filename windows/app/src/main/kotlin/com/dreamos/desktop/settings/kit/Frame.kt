package com.dreamos.desktop.settings.kit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The shell's back chain: every open layer that has its own way back (a page's [PageFrame], Choose a Source, a PIN
 * pad) adds it while it is on screen, and the shell's Esc, Backspace and mouse Back run the newest one, wherever focus
 * happens to be (it can rest on the shell's root after a control went away). Nothing in it: the shell's own stack.
 */
class BackChain {
    private val handlers = ArrayList<() -> Unit>()
    fun add(h: () -> Unit) { handlers.add(h) }
    fun remove(h: () -> Unit) { handlers.remove(h) }
    /** The newest layer's way back, or null when only the shell's own pages are open. */
    fun top(): (() -> Unit)? = handlers.lastOrNull()
}

/** The shell's [BackChain]; null outside the shell (previews, setup's own window phase). */
val LocalBackChain = staticCompositionLocalOf<BackChain?> { null }

/** Adds [onBack] to the shell's [BackChain] while this is composed. */
@Composable
fun BackHandler(onBack: () -> Unit) {
    val chain = LocalBackChain.current ?: return
    val latest = rememberUpdatedState(onBack)
    DisposableEffect(chain) {
        val h: () -> Unit = { latest.value() }
        chain.add(h)
        onDispose { chain.remove(h) }
    }
}

/** The scale the pages are laid out at, or null outside a [PageFrame]. */
val LocalPageScale = compositionLocalOf<Float?> { null }

/**
 * The scale for a window of [w] x [h] dp: the pages are designed in a space of about 1440 x 860 and grow with the
 * window (4K at 100% looks like 1080p, only sharper); small windows shrink to 82% at most so 1024 x 640 still fits.
 */
fun pageScale(w: Float, h: Float): Float = minOf(w / 1440f, h / 860f).coerceIn(0.82f, 3f)

/**
 * The root of every page: the black page, the scaled layout space, and the keyboard path. Arrow keys move focus
 * between controls, Esc (and Backspace outside text fields) goes [onBack]. Nested frames don't scale twice.
 */
@Composable
fun PageFrame(onBack: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    if (onBack != null) BackHandler(onBack)
    BoxWithConstraints(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Dream.Black, Dream.Page)))) {
        val outer = LocalPageScale.current
        val d = LocalDensity.current
        val scale = if (outer != null) 1f else pageScale(maxWidth.value, maxHeight.value)
        CompositionLocalProvider(
            LocalDensity provides Density(d.density * scale, d.fontScale),
            LocalPageScale provides (outer ?: scale),
        ) {
            val fm = LocalFocusManager.current
            val root = remember { FocusRequester() }
            var rootFocused by remember { mutableStateOf(false) }
            // Before the content's own effects, so a page that focuses its first action wins.
            if (outer == null) LaunchedEffect(Unit) { runCatching { root.requestFocus() } }
            Box(
                Modifier.fillMaxSize()
                    .focusRequester(root)
                    .onFocusChanged { rootFocused = it.isFocused }
                    .focusable()
                    .onKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val dir = when (e.key) {
                            Key.DirectionDown -> FocusDirection.Down
                            Key.DirectionUp -> FocusDirection.Up
                            Key.DirectionLeft -> FocusDirection.Left
                            Key.DirectionRight -> FocusDirection.Right
                            else -> null
                        }
                        when {
                            dir != null -> { if (rootFocused) fm.moveFocus(FocusDirection.Next) else fm.moveFocus(dir); true }
                            (e.key == Key.Escape || e.key == Key.Backspace) && onBack != null -> { onBack(); true }
                            else -> false
                        }
                    },
            ) { content() }
        }
    }
}

/** Fades the top and bottom [edge] of a scrolling area into the page (SmoothScrollView.softEdges), only where more is hidden. */
fun Modifier.softEdges(state: ScrollState, edge: Dp = 28.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val e = edge.toPx() / size.height
        val top = if (state.value > 0) Color.Transparent else Color.Black
        val bottom = if (state.value < state.maxValue) Color.Transparent else Color.Black
        drawRect(
            Brush.verticalGradient(0f to top, e to Color.Black, 1f - e to Color.Black, 1f to bottom),
            blendMode = BlendMode.DstIn,
        )
    }

/** The thin scrollbar for the mouse: quiet at rest, brighter under the pointer. */
val DreamScrollbar = ScrollbarStyle(
    minimalHeight = 48.dp, thickness = 8.dp, shape = RoundedCornerShape(4.dp), hoverDurationMillis = 250,
    unhoverColor = Color.White.copy(alpha = 0.16f), hoverColor = Color.White.copy(alpha = 0.42f),
)

/** A scrolling column with soft edges and a scrollbar on the right; [room] keeps glows inside the scroller. */
@Composable
fun ScrollPage(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    maxWidth: Dp = 880.dp,
    room: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().softEdges(state).verticalScroll(state), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = maxWidth).fillMaxWidth().padding(horizontal = 40.dp, vertical = room)) { content() }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(state),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp, horizontal = 4.dp),
            style = DreamScrollbar,
        )
    }
}

/**
 * A full page (DreamSettingsPage): Back at the top left, the page title, then the scrolling body. Esc goes back.
 */
@Composable
fun PageScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    maxWidth: Dp = 880.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    PageFrame(onBack, modifier) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 28.dp, top = 22.dp, end = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) DreamButton("Back", onBack, glyph = Glyph.Back)
            }
            ScrollPage(maxWidth = maxWidth) {
                Text(title, style = DreamType.Title)
                if (subtitle != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(subtitle, style = DreamType.Body.copy(color = Dream.TextDim))
                }
                Spacer(Modifier.height(24.dp))
                content()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
