package com.dreamos.desktop.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dreamos.desktop.shell.data.CardItem
import com.dreamos.desktop.shell.data.Title
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode

/** What a card does when clicked and what its options are; the shell provides it for every page. */
interface CardActions {
    fun open(item: CardItem)
    fun menu(item: CardItem, continueRow: Boolean): MenuOpener
    /** The title's status pill now (no network); reading it in composition follows new answers. */
    fun pill(t: Title, narrow: Boolean): com.dreamos.desktop.shell.data.Pill? = null
    /** A New Episodes for You card's episode pill now (no network). */
    fun episodePill(item: CardItem): com.dreamos.desktop.shell.data.Pill? = null
    /** A seasonal hub tile was clicked. */
    fun openHub(tile: com.dreamos.desktop.core.model.HubTile) {}
}

val LocalCardActions = staticCompositionLocalOf<CardActions> { error("No CardActions") }

/** The art box every card shares: rounded, a dark surface under the art, a hairline edge, the interaction. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun CardArt(
    item: CardItem,
    w: Dp,
    h: Dp,
    art: CardChoice,
    continueRow: Boolean = false,
    lift: Float = 1.045f,
    alignment: Alignment = Alignment.Center,
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    val m = LocalMetrics.current
    val acts = LocalCardActions.current
    val shape = RoundedCornerShape(m.radius)
    // Resting on a card (pointer or keyboard) readies its title page, so a click opens it complete.
    val ahead = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    fun rest(on: Boolean) { ahead[0]?.cancel(); ahead[0] = if (on) com.dreamos.desktop.shell.data.TitleAhead.rest(item.title) else null }
    androidx.compose.runtime.DisposableEffect(item.title.key) { onDispose { ahead[0]?.cancel() } }
    Box(
        Modifier
            .size(w, h)
            .semantics { contentDescription = item.title.name }
            .onPointerEvent(PointerEventType.Enter) { rest(true) }
            .onPointerEvent(PointerEventType.Exit) { rest(false) }
            .onFocusChanged { if (it.isFocused) rest(true) else if (!it.hasFocus) rest(false) }
            .dreamCard(m.radius, onClick = { acts.open(item) }, onMenu = acts.menu(item, continueRow), lift = lift)
            .clip(shape)
            .background(Palette.surface),
    ) {
        DreamImage(art.urls, Modifier.fillMaxSize(), alignment = alignment, fitFrom = art.fitFrom, placeholder = { NamePlaceholder(item.title.name) })
        com.dreamos.desktop.preload.ArtProbe("card-art", art.urls.firstOrNull { it != null })
        overlay()
        Box(Modifier.fillMaxSize().border(m.u(1), Color(0x14FFFFFF), shape))
    }
}

/** A title's name, centered and quiet, while (or when) it has no art. */
@Composable
fun NamePlaceholder(name: String) {
    val m = LocalMetrics.current
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Palette.surfaceHigh, Palette.surface))).padding(m.u(12)), contentAlignment = Alignment.Center) {
        Text(name, style = Type.cardTitle().copy(color = Palette.textFaint), textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** The caption under a card: the name on one line, the sub line under it. */
@Composable
fun Caption(title: String, sub: String?, w: Dp) {
    val m = LocalMetrics.current
    Column(Modifier.width(w).padding(top = m.u(10))) {
        Text(title, style = Type.cardTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!sub.isNullOrEmpty()) {
            Spacer(Modifier.height(m.u(3)))
            Text(sub, style = Type.cardMeta(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun subline(t: Title) = listOfNotNull(t.year.ifEmpty { null }, if (t.isShow) "Series" else "Movie").joinToString(" · ")

/**
 * A card's art in order of choice, as URLs at [px] wide (the first that loads shows; [CardChoice.fitFrom] marks where
 * the choices turn into a poster, drawn whole). The rule (owner, 2026-10-30: "it should be the cover of the title, like
 * see Sinners"): every card that shows a title shows its cover, whole, never cropped. A 16:9 card (landscape rows, Top
 * Picks' big cards) takes key art first (TMDB's English backdrop with the title's name on it, 16:9 like the card), then
 * the backdrop, then the poster drawn whole; a 2:3 card takes the poster. Only the Spotlight, a frame wider than any
 * art, takes the textless backdrop, since a cover there would lose its lettering to the crop. A card that plays an
 * episode (Continue Watching, New Episodes for You) shows the episode's still first, as the Fire Stick does.
 */
class CardChoice(val urls: List<String?>, val fitFrom: Int)

fun cardChoice(item: CardItem, style: com.dreamos.desktop.shell.data.RowStyle, px: Float, live: Boolean = true): CardChoice {
    val t = item.title
    val backdrops = when (style) {
        com.dreamos.desktop.shell.data.RowStyle.POSTER, com.dreamos.desktop.shell.data.RowStyle.TOP10 ->
            return CardChoice(listOf(Tmdb.poster(t.posterPath, px), Tmdb.backdrop(t.backdropPath, px)), Int.MAX_VALUE)
        com.dreamos.desktop.shell.data.RowStyle.SPOTLIGHT -> listOfNotNull(item.plainArt ?: t.backdropPath)
        com.dreamos.desktop.shell.data.RowStyle.CONTINUE -> listOfNotNull(item.still, t.backdropPath)
        com.dreamos.desktop.shell.data.RowStyle.BIG, com.dreamos.desktop.shell.data.RowStyle.LANDSCAPE ->
            listOfNotNull(item.still, if (live) com.dreamos.desktop.shell.data.Covers.of(item) else item.keyArt, t.backdropPath)
    }.distinct().map { Tmdb.backdrop(it, px) }
    return CardChoice(backdrops + Tmdb.poster(t.posterPath, px), backdrops.size)
}

/** A row's card width in dp, as [com.dreamos.desktop.ui.kit.DataRowView] draws it. */
fun cardWidth(style: com.dreamos.desktop.shell.data.RowStyle, m: Metrics): Float = when (style) {
    com.dreamos.desktop.shell.data.RowStyle.POSTER -> m.posterW.value
    com.dreamos.desktop.shell.data.RowStyle.TOP10 -> m.rankPosterW.value
    com.dreamos.desktop.shell.data.RowStyle.BIG -> m.bigW.value
    com.dreamos.desktop.shell.data.RowStyle.SPOTLIGHT -> m.widthDp - 2 * m.gutter.value
    else -> m.landW.value
}

/** The art each of a row's first [count] cards shows (its first choice, sized as drawn): what a row warms before it shows. */
fun cardArt(row: com.dreamos.desktop.shell.data.Row, m: Metrics, density: Float, count: Int = 12): List<String?> {
    val px = cardWidth(row.style, m) * density
    return row.items.take(count).map { cardChoice(it, row.style, px).urls.firstOrNull { u -> u != null } }
}

/** The standard row card: 16:9 art, the name and a sub line underneath. */
@Composable
fun LandscapeCard(item: CardItem, w: Dp) {
    val h = w * 9f / 16f
    val px = with(LocalDensity.current) { w.toPx() }
    val t = item.title
    // Chosen once, when the card is first drawn: a cover that arrives while it is on screen never swaps in.
    val art = remember(item, px) {
        cardChoice(item, com.dreamos.desktop.shell.data.RowStyle.LANDSCAPE, px).also { com.dreamos.desktop.perf.CoverProbe.note(item, it.urls.firstOrNull()) }
    }
    Column(Modifier.width(w)) {
        CardArt(item, w, h, art) {
            CardMarks(item, narrow = w < 220.dp)
        }
        Caption(t.name, item.sub ?: subline(t), w)
    }
}

/**
 * Badges top-left and the status pill bottom-left, inset from the edge. The pill sits over the art, so it never moves
 * the layout; one known when the card first draws is there in that frame (rows wait for their first cards' pills),
 * one answered later fades in rather than appearing in a blink.
 */
@Composable
fun CardMarks(item: CardItem, narrow: Boolean) {
    val m = LocalMetrics.current
    Box(Modifier.fillMaxSize().padding(m.u(8))) {
        item.badge?.let { CardBadge(it, Modifier.align(Alignment.TopStart)) }
        val pill = item.pill ?: LocalCardActions.current.pill(item.title, narrow)
        com.dreamos.desktop.preload.PopProbe("card-pill", pill != null)
        val kept = remember { mutableStateOf(pill) }
        androidx.compose.runtime.SideEffect { if (pill != null) kept.value = pill }
        androidx.compose.animation.AnimatedVisibility(
            pill != null, Modifier.align(Alignment.BottomStart),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(PILL_FADE_MS)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)),
        ) {
            (pill ?: kept.value)?.let { StatusPill(it, narrow) }
        }
    }
}

/** A pill answered after its card is on screen fades in over this long (a soft arrival, not a blink). */
const val PILL_FADE_MS = 240

/** Top Picks' big card: a wider 16:9 frame than a landscape card's, the cover whole, the name and a meta line underneath. */
@Composable
fun BigCard(item: CardItem, w: Dp, meta: String) {
    val h = w * 9f / 16f
    val px = with(LocalDensity.current) { w.toPx() }
    val t = item.title
    val art = remember(item, px) {
        cardChoice(item, com.dreamos.desktop.shell.data.RowStyle.BIG, px).also { com.dreamos.desktop.perf.CoverProbe.note(item, it.urls.firstOrNull()) }
    }
    Column(Modifier.width(w)) {
        CardArt(item, w, h, art, lift = 1.03f) {
            CardMarks(item, narrow = false)
        }
        Caption(t.name, meta, w)
    }
}

/** A 2:3 poster; [caption] adds the name and year under it (grids, results). */
@Composable
fun PosterCard(item: CardItem, w: Dp, caption: Boolean = true) {
    val h = w * 1.5f
    val px = with(LocalDensity.current) { w.toPx() }
    val t = item.title
    Column(Modifier.width(w)) {
        CardArt(item, w, h, cardChoice(item, com.dreamos.desktop.shell.data.RowStyle.POSTER, px)) {
            CardMarks(item, narrow = w < 170.dp)
        }
        if (caption) Caption(t.name, item.sub ?: subline(t), w)
    }
}

/** Continue Watching: art, a rainbow progress line along the bottom, the name and where the viewer is. */
@Composable
fun ContinueCard(item: CardItem, w: Dp) {
    val m = LocalMetrics.current
    val h = w * 9f / 16f
    val px = with(LocalDensity.current) { w.toPx() }
    val t = item.title
    Column(Modifier.width(w)) {
        // A New Episodes for You card shares the look; its options are the title's, not Continue Watching's.
        CardArt(item, w, h, cardChoice(item, com.dreamos.desktop.shell.data.RowStyle.CONTINUE, px), continueRow = !item.newEpisode) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.62f to Color.Transparent, 1f to Color(0xB3000000))))
            if (item.upNext) CardBadge("UP NEXT", Modifier.padding(m.u(8)))
            else item.badge?.let { CardBadge(it, Modifier.padding(m.u(8))) }
            // A new episode's status while it is new, or when an upcoming one comes (the Fire Stick's episode card).
            if (item.newEpisode) LocalCardActions.current.episodePill(item)?.let {
                StatusPill(it, w < 220.dp, Modifier.align(Alignment.BottomStart).padding(m.u(8)))
            }
            val p = item.progress
            if (p != null && p > 0f) {
                Box(Modifier.align(Alignment.BottomStart).padding(horizontal = m.u(10), vertical = m.u(9)).fillMaxWidth().height(m.u(4))
                    .clip(RoundedCornerShape(50)).background(Color(0x4DFFFFFF))) {
                    Box(Modifier.fillMaxWidth(p.coerceIn(0.03f, 1f)).fillMaxHeight().rainbowFill(RoundedCornerShape(50)))
                }
            }
        }
        Caption(t.name, item.sub ?: subline(t), w)
    }
}

private val numeralTypeface by lazy {
    FontMgr.default.matchFamilyStyle("Segoe UI Variable Display", FontStyle.BOLD)
        ?: FontMgr.default.matchFamilyStyle("Segoe UI", FontStyle.BOLD)
        ?: FontMgr.default.matchFamilyStyle(null, FontStyle.BOLD)
}

/**
 * Top 10: an outlined rank numeral beside the poster. The numeral is placed by its measured ink, not its text box, so
 * every rank shares one size, its ink ends a full [Metrics.u](14) left of the poster (clear of the lifted poster's
 * ring), and its ink bottom sits on the poster's bottom edge.
 */
@Composable
fun RankedCard(item: CardItem, rank: Int, posterW: Dp) {
    val m = LocalMetrics.current
    val ph = posterW * 1.5f
    val slot = if (rank >= 10) m.rankSlot + m.u(34) else m.rankSlot
    val gap = m.u(14)
    Row(verticalAlignment = Alignment.Top) {
        Canvas(Modifier.size(slot, ph)) {
            val tf = numeralTypeface ?: return@Canvas
            val stroke = m.u(2.2f).toPx()
            val text = rank.toString()
            // One text size for every rank: the widest digit fits the one-digit slot, "10" its wider slot.
            val probe = Font(tf, 100f)
            val widest = (0..9).maxOf { probe.measureText(it.toString()).width }
            val ten = probe.measureText("10").width
            val room1 = m.rankSlot.toPx() - gap.toPx() - stroke
            val room10 = (m.rankSlot + m.u(34)).toPx() - gap.toPx() - stroke
            val sizePx = minOf(100f * room1 / widest, 100f * room10 / ten, ph.toPx() * 0.66f)
            val font = Font(tf, sizePx)
            val ink = font.measureText(text)
            val x = size.width - gap.toPx() - stroke / 2f - ink.right
            val y = size.height - stroke / 2f - ink.bottom
            val paint = Paint().apply {
                isAntiAlias = true
                mode = PaintMode.STROKE
                strokeWidth = stroke
                color = 0xC7FFFFFF.toInt()
            }
            drawIntoCanvas { it.nativeCanvas.drawString(text, x, y, font, paint) }
        }
        PosterCard(item, posterW, caption = true)
    }
}

/**
 * Spotlight: one title across the page, its art full width with the logo (or the name), meta and two lines of
 * synopsis over a soft scrim on the left. A single card: clicking opens it.
 */
@Composable
fun SpotlightCard(item: CardItem, w: Dp, kicker: String, meta: String, logo: suspend (Title) -> String?) {
    val m = LocalMetrics.current
    val h = minOf(w * 0.36f, m.u(400))
    val px = with(LocalDensity.current) { w.toPx() }
    val t = item.title
    var logoPath by remember(t.key) { mutableStateOf(SpotlightLogos.known(t.key)) }
    LaunchedEffect(t.key) { logo(t).let { logoPath = it; SpotlightLogos.remember(t.key, it) } }
    com.dreamos.desktop.preload.PopProbe("spotlight-logo-path", logoPath != null, t.key)
    CardArt(item, w, h, cardChoice(item, com.dreamos.desktop.shell.data.RowStyle.SPOTLIGHT, px), lift = 1.015f) {
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF2050506), 0.42f to Color(0xB8050506), 0.72f to Color.Transparent)))
        Column(Modifier.align(Alignment.BottomStart).padding(start = m.u(36), bottom = m.u(30)).width(minOf(w * 0.42f, m.u(520)))) {
            Text(kicker, style = Type.kicker().copy(color = Palette.overArt), maxLines = 1)
            Spacer(Modifier.height(m.u(10)))
            TitleLogo(t, logoPath, m.u(320), m.u(84), Type.style(30f, androidx.compose.ui.text.font.FontWeight.Bold, -0.3f))
            Spacer(Modifier.height(m.u(12)))
            // The status pill sits at the meta line's end, inside the text column, never over the words (the Fire Stick's).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(meta, style = Type.meta().copy(color = Palette.overArt), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                (item.pill ?: LocalCardActions.current.pill(t, false))?.let { Spacer(Modifier.width(m.u(12))); StatusPill(it, false) }
            }
            Spacer(Modifier.height(m.u(8)))
            Text(t.overview, style = Type.synopsis().copy(color = Palette.overArt), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Spotlight logos already looked up (the row readies them before it shows), so the card draws its logo in its first frame. */
object SpotlightLogos {
    private const val MAX = 256
    private val known = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > MAX
    }

    fun known(key: String): String? = synchronized(known) { known[key] }?.ifEmpty { null }
    fun remember(key: String, path: String?) = synchronized(known) { known[key] = path.orEmpty() }
}

/** A title's logo, left-aligned in its box, or its name in [style] when TMDB has no logo. */
@Composable
fun TitleLogo(t: Title, logoPath: String?, maxW: Dp, maxH: Dp, style: androidx.compose.ui.text.TextStyle) {
    val px = with(LocalDensity.current) { maxW.toPx() }
    if (logoPath != null) {
        com.dreamos.desktop.preload.ArtProbe("logo", Tmdb.logo(logoPath, px))
        DreamImage(listOf(Tmdb.logo(logoPath, px)), Modifier.size(maxW, maxH), contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
            placeholder = { Box(Modifier.size(maxW, maxH), contentAlignment = Alignment.BottomStart) { Text(t.name, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis) } })
    } else {
        Text(t.name, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A genre tile: a deep tint of one rainbow hue, the genre's drawn glyph top-right, its name bottom-left (fire-home
 * genreTile). Hues run on across the row, so the rainbow shows through Browse without a single loud block.
 */
@Composable
fun GenreTile(name: String, hue: Color, w: Dp, glyph: String = name, onClick: () -> Unit) {
    val m = LocalMetrics.current
    val h = w * 9f / 16f
    val shape = RoundedCornerShape(m.radius)
    Box(
        Modifier.size(w, h)
            .dreamCard(m.radius, onClick)
            .clip(shape)
            .background(Brush.linearGradient(listOf(mix(hue, Palette.surfaceHigh, 0.55f), mix(hue, Palette.page, 0.84f)), Offset.Zero, Offset.Infinite))
            .border(m.u(1), Color(0x14FFFFFF), shape),
    ) {
        GenreGlyph(glyph, mix(hue, Palette.text, 0.35f), Modifier.align(Alignment.TopEnd).padding(m.u(14)).size(m.u(54)))
        Text(name, style = Type.style(18f, androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = m.u(16), end = m.u(16), bottom = m.u(13)))
    }
}

/** A service tile: the name set bold on a deep tint of its brand color, a short bar of that color under it. */
@Composable
fun BrandTile(name: String, color: Color, w: Dp, onClick: () -> Unit) {
    val m = LocalMetrics.current
    val h = w * 9f / 16f
    val shape = RoundedCornerShape(m.radius)
    Box(
        Modifier.size(w, h)
            .dreamCard(m.radius, onClick)
            .clip(shape)
            .background(Brush.linearGradient(listOf(mix(color, Palette.surfaceHigh, 0.62f), mix(color, Palette.page, 0.86f)), Offset.Zero, Offset.Infinite))
            .border(m.u(1), Color(0x1AFFFFFF), shape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(name, style = Type.style(20f, androidx.compose.ui.text.font.FontWeight.Bold, -0.2f), maxLines = 1)
            Spacer(Modifier.height(m.u(10)))
            Box(Modifier.size(m.u(24), m.u(3)).clip(RoundedCornerShape(50)).background(mix(color, Palette.text, 0.25f)))
        }
    }
}

/** A big entry tile (Browse's TV Shows and Movies): the week's most popular art under a scrim, the name large. */
@Composable
fun EntryTile(name: String, sub: String, art: Title?, hues: Pair<Color, Color>, w: Dp, h: Dp, onClick: () -> Unit) {
    val m = LocalMetrics.current
    val shape = RoundedCornerShape(m.radius)
    val px = with(LocalDensity.current) { w.toPx() }
    Box(
        Modifier.size(w, h)
            .dreamCard(m.radius, onClick, lift = 1.025f)
            .clip(shape)
            .background(Brush.linearGradient(listOf(mix(hues.first, Palette.page, 0.55f), mix(hues.second, Palette.page, 0.80f)), Offset.Zero, Offset.Infinite)),
    ) {
        if (art != null) DreamImage(listOf(Tmdb.backdrop(art.backdropPath, px)), Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color(0x59050506), 1f to Color(0xEB050506))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = m.u(24), end = m.u(24), bottom = m.u(20))) {
            Text(name, style = Type.style(30f, androidx.compose.ui.text.font.FontWeight.Bold, -0.3f), maxLines = 1)
            Spacer(Modifier.height(m.u(6)))
            Text(sub, style = Type.meta(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxSize().border(m.u(1), Color(0x14FFFFFF), shape))
    }
}
