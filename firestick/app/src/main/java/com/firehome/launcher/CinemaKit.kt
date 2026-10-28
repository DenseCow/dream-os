package com.firehome.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Dream Cinema components: cards (landscape, Hulu's big card with its details underneath, poster,
 * brand tile, Continue Watching, Top 10), Netflix-style buttons, Hulu's chips, the row that keeps
 * focus on a fixed left line (Apple TV) and the hero carousel. Sizes, type and motion come from
 * [CinemaStyle]; colors from the user's theme. Focus is the rainbow glow ([CinemaStyle.focusGlow]),
 * never a white ring.
 *
 * Lightweight by construction: every animated property is a RenderNode property (translation,
 * scale, alpha) or a glow's color; text views that change have fixed sizes (no relayout); art binds
 * only near focus ([CinemaRow.art]) at small TMDB sizes and decodes as RGB_565 (Net).
 */
class CinemaKit(val ctx: Context) {
    private val density = ctx.resources.displayMetrics.density
    val Int.dp get() = (this * density).toInt()
    val Float.dpf get() = this * density

    val text = CinemaStyle.text(ctx)
    val dim = CinemaStyle.textDim(ctx)
    val bg = CinemaStyle.bg(ctx)
    val bgTop = CinemaStyle.bgTop(ctx)
    val tile = CinemaStyle.tile(ctx)
    val primary = CinemaStyle.primary(ctx)
    val secondary = CinemaStyle.secondary(ctx)
    /** Ink for text on light fills (a focused button, chip or tab is a pearl capsule). */
    val ink = Ui.mix(bgTop, Color.BLACK, 0.35f)
    /** A card's resting tile, seen only while its art loads. */
    val cardFill = Ui.mix(tile, text, 0.05f)
    val font: Typeface = Theme.typeface(ctx)
    val medium: Typeface = Ui.medium(font)
    val semibold: Typeface = Ui.semibold(font)
    val bold: Typeface = Theme.typeface(ctx, Typeface.BOLD)
    val gutter = CinemaStyle.GUTTER.dp
    val screenW = ctx.resources.displayMetrics.widthPixels
    val screenH = ctx.resources.displayMetrics.heightPixels

    /** The page between the gutters (dp): every row's cards fill exactly this. */
    fun pageW(): Int = (screenW / density).toInt() - 2 * CinemaStyle.GUTTER

    /**
     * A card width (dp) near [wDp] such that a whole number of cards exactly fills the page between the
     * gutters: the first card starts on the left line, the last ends on the right one.
     */
    fun fit(wDp: Int): Int {
        val gap = CinemaStyle.CARD_GAP
        val n = maxOf(1, Math.round((pageW() + gap).toFloat() / (wDp + gap)))
        return (pageW() - (n - 1) * gap) / n
    }

    // ---- type ----

    fun label(t: CharSequence, size: Float, color: Int = text, face: Typeface = font, lines: Int = 1, tracking: Float = 0f) = TextView(ctx).apply {
        text = t
        textSize = size
        typeface = face
        setTextColor(color)
        letterSpacing = tracking
        includeFontPadding = false
        if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
    }

    /** "TONIGHT": small tracked caps above a hero title or a big card's title. */
    fun kicker(t: String, color: Int = Ui.alpha(text, 0.72f)) = label(t.uppercase(), CinemaStyle.T_CARD_KICKER, color, semibold, tracking = 0.14f)

    // ---- cards ----

    /** A movie or show card: its art (landscape backdrop or poster), the title under it on focus. */
    fun landscape(t: Title, w: Int = fit(CinemaStyle.LAND_W), h: Int = w * 9 / 16, sub: String? = null): Pair<View, CinemaCard> {
        val card = CinemaCard(this, w, h)
        card.title = t
        // Key art (the title's name on the art, KeyArt) first, then the textless backdrop, then the poster.
        val key = KeyArt.url(t, "w300")
        if (key != null) card.art().source(key, listOfNotNull(t.backdrop("w300"), t.poster("w342")))
        else card.art().source(t.backdrop("w300") ?: t.poster("w342"), listOfNotNull(t.poster("w342").takeIf { t.backdropPath != null }))
        if (t.backdropPath == null && t.posterPath == null && key == null) card.placeholder(t.name)
        return unit(card, null, t.name, sub ?: subline(t), focusOnly = true) to card
    }

    /**
     * Hulu's big landscape card: three across in a wide scope frame ([CinemaStyle.BIG_W]), the title and a
     * meta line (year, type, genres) always underneath. The row's own title says what it is, so no kicker:
     * a row of these is no taller than a standard row (two rows always fit above the bottom fade).
     */
    fun big(t: Title, @Suppress("UNUSED_PARAMETER") kicker: String): Pair<View, CinemaCard> {
        val bw = fit(CinemaStyle.BIG_W)
        val card = CinemaCard(this, bw, bw * CinemaStyle.BIG_H / CinemaStyle.BIG_W, big = true)
        card.title = t
        val key = KeyArt.url(t, "w780")
        if (key != null) card.art().source(key, listOfNotNull(t.backdrop("w780"), t.poster("w500")))
        else card.art().source(t.backdrop("w780") ?: t.poster("w500"), listOfNotNull(t.poster("w500")))
        if (t.backdropPath == null && t.posterPath == null && key == null) card.placeholder(t.name)
        return unit(card, null, t.name, meta(t), focusOnly = false) to card
    }

    /** A 2:3 poster (Top 10, My List grid, search results); the art carries the name, so no caption. */
    fun poster(t: Title, w: Int = CinemaStyle.POSTER_W, h: Int = CinemaStyle.POSTER_H): CinemaCard {
        val card = CinemaCard(this, w, h)
        card.title = t
        card.art().source(t.poster("w342") ?: t.backdrop("w300"))
        if (t.posterPath == null) card.placeholder(t.name)
        return card
    }

    /** Top 10: an outlined rank numeral beside the poster (only the poster is the card). */
    fun ranked(t: Title, rank: Int): Pair<View, CinemaCard> {
        // Numeral and poster together fill the page in whole steps, like every other row.
        val pw = fit(CinemaStyle.RANK_W + RANK_NUM + 4) - RANK_NUM - 4
        val ph = pw * 3 / 2
        val card = poster(t, pw, ph)
        val slotW = if (rank >= 10) (RANK_NUM + 40).dp else RANK_NUM.dp
        val numeral = RankNumeral(rank.toString(), slotW, ph.dp)
        // The numeral stands beside the poster (top-aligned, as tall as it); the title runs under the poster only.
        val unit = FlatLinear(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            gravity = Gravity.TOP
            addView(numeral, LinearLayout.LayoutParams(slotW, ph.dp).apply { marginEnd = 4.dp })
            addView(unit(card, null, t.name, null))
        }
        return unit to card
    }

    /**
     * A Top 10 rank numeral, drawn from its measured ink (not the font's box), so every number from 1 to 10 has
     * the same text size, its ink ends exactly [RANK_GAP] dp from the slot's right edge (clear of the poster even
     * when it lifts on focus), and its ink bottom sits on the poster's bottom edge.
     */
    private inner class RankNumeral(private val num: String, private val slotW: Int, private val slotH: Int) : View(ctx) {
        private val stroke = 2.2f.dpf
        private val gap = RANK_GAP.dp.toFloat()
        private val ink = android.graphics.Rect()
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = bold
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = Ui.alpha(this@CinemaKit.text, 0.75f)
            // One size for every rank: the widest single digit fits a one-digit slot and "10" fits its wider slot.
            textSize = 100f
            var widest = 0
            for (d in '0'..'9') { getTextBounds(d.toString(), 0, 1, ink); widest = maxOf(widest, ink.width()) }
            getTextBounds("10", 0, 2, ink)
            val ten = ink.width()
            val room1 = RANK_NUM.dp - gap - stroke
            val room10 = (RANK_NUM + 40).dp - gap - stroke
            textSize = minOf(slotH * 0.62f, 100f * room1 / widest, 100f * room10 / ten)
            getTextBounds(num, 0, num.length, ink)
        }
        /** The outline drawn once, off the main thread, into an alpha mask tinted by [tint]; null until it's ready. */
        @Volatile private var mask: android.graphics.Bitmap? = null
        private val tint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = pen.color }
        init {
            isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            // Text this large skips the glyph cache and is drawn as stroked paths every frame (a hitch scrolling to
            // Top 10). A hardware layer fixed the scroll but its first draw (or a warm-up's buildLayer) blocked the
            // main thread for a moment between presses: it is drawn once into a bitmap in the background instead,
            // when the row is made (at boot, long before anyone scrolls there), and only composited after that.
            if (slotW > 0 && slotH > 0) RANK_ART.execute {
                val b = android.graphics.Bitmap.createBitmap(slotW, slotH, android.graphics.Bitmap.Config.ALPHA_8)
                Canvas(b).drawText(num, slotW - gap - stroke / 2f - ink.right, slotH - stroke / 2f - ink.bottom,
                    Paint(pen).apply { color = Color.WHITE })
                b.prepareToDraw()
                mask = b
                postInvalidate()
            }
        }
        override fun onDraw(canvas: Canvas) {
            mask?.let { canvas.drawBitmap(it, 0f, 0f, tint) }
        }
    }

    /**
     * Apple TV's feature module: one title across the whole page, its art full width with the logo (or name),
     * meta and two lines of synopsis over a soft scrim on the left. A single card: OK opens it.
     */
    fun spotlight(t: Title, kick: String): CinemaCard {
        val w = ((screenW / ctx.resources.displayMetrics.density) - 2 * CinemaStyle.GUTTER).toInt()
        val card = CinemaCard(this, w, SPOTLIGHT_H, big = true)
        card.title = t
        card.art().source(t.backdrop("w1280") ?: t.poster("w500"))
        card.frame.addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Ui.alpha(bgTop, 0.92f), Ui.alpha(bgTop, 0.70f), Ui.alpha(bgTop, 0.10f), Color.TRANSPARENT))
        }, MATCH_PARENT, MATCH_PARENT)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(kicker(kick, Ui.alpha(text, 0.80f)))
            val box = FrameLayout(ctx)
            val name = label(t.name, if (t.name.length > 22) 22f else 28f, text, bold, lines = 2).apply { gravity = Gravity.BOTTOM; letterSpacing = -0.015f }
            val logo = FitImage(ctx, crop = false).apply { alpha = 0f }
            box.addView(name, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            box.addView(logo, FrameLayout.LayoutParams(260.dp, MATCH_PARENT))
            addView(box, LinearLayout.LayoutParams(420.dp, 58.dp).apply { topMargin = 6.dp })
            // The meta line; a status pill (Availability) sits at its end, inside the text column, never over the words.
            val metaRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            metaRow.addView(label(meta(t), CinemaStyle.T_META + 1f, Ui.alpha(text, 0.86f), medium), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(metaRow, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 8.dp })
            card.statusSlot = metaRow
            addView(label(t.overview, CinemaStyle.T_SYNOPSIS - 0.5f, Ui.alpha(text, 0.80f), font, lines = 2).apply { setLineSpacing(0f, 1.15f) },
                LinearLayout.LayoutParams(420.dp, WRAP_CONTENT).apply { topMargin = 6.dp })
            CinemaLogos.get(ctx, t) { url ->
                if (url == null) return@get
                Net.image(ctx, logo, url) { _ -> name.animate().alpha(0f).setDuration(200).start(); logo.animate().alpha(1f).setDuration(260).start() }
            }
        }
        card.frame.addView(col, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(32.dp, 0, 0, 20.dp) })
        return card
    }

    /**
     * A big entry tile (Browse's TV Shows, Movies, Discover): art (or a deep two-hue gradient) under a soft
     * bottom scrim, the name large and a line about it, both bottom-left.
     */
    fun entryTile(name: String, sub: String, w: Int, h: Int, hues: IntArray): CinemaCard {
        val card = CinemaCard(this, w, h, big = true)
        card.frame.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Ui.mix(hues[0], bgTop, 0.55f), Ui.mix(hues[1], bgTop, 0.80f))).apply { cornerRadius = Ui.dpf(ctx, CinemaStyle.R_CARD.toFloat()) }
        card.art()
        card.frame.addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Ui.alpha(bgTop, 0.35f), Ui.alpha(bgTop, 0.90f)))
        }, MATCH_PARENT, MATCH_PARENT)
        card.frame.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(name, 24f, text, bold).apply { letterSpacing = -0.01f })
            addView(label(sub, 13f, Ui.alpha(text, 0.78f), medium).apply { setPadding(0, 5.dp, 0, 0) })
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(18.dp, 0, 18.dp, 16.dp) })
        return card
    }

    /** A genre card: a deep tint of one rainbow hue, the genre's line glyph top-right, its name bottom-left. */
    fun genreTile(name: String, hue: Int): CinemaCard {
        val gw = fit(CinemaStyle.LAND_W)
        val card = CinemaCard(this, gw, gw * 9 / 16)
        card.frame.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Ui.mix(hue, tile, 0.50f), Ui.mix(hue, bgTop, 0.80f))).apply { cornerRadius = Ui.dpf(ctx, CinemaStyle.R_CARD.toFloat()) }
        card.frame.addView(View(ctx).apply { background = GenreArt(name, Ui.mix(hue, text, 0.35f)) },
            FrameLayout.LayoutParams(46.dp, 46.dp, Gravity.TOP or Gravity.END).apply { setMargins(0, 14.dp, 14.dp, 0) })
        card.frame.addView(label(name, 17f, text, semibold), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(16.dp, 0, 16.dp, 13.dp) })
        card.frame.foreground = Ui.rounded(ctx, Color.TRANSPARENT, CinemaStyle.R_CARD, 1f, Ui.alpha(text, 0.08f))
        return card
    }

    /** Continue Watching: art, a progress capsule that takes the glow's hue on focus, title and where you are underneath. */
    fun continueCard(e: WatchHistory.Entry, apps: List<AppEntry>): Pair<View, CinemaCard> {
        val t = e.toTitle()
        val lw = fit(CinemaStyle.LAND_W)
        val card = CinemaCard(this, lw, lw * 9 / 16)
        card.title = t
        e.images().let { card.art().source(it.firstOrNull(), it.drop(1)) }
        if (e.images().isEmpty()) card.placeholder(t.name)
        val code = if (e.isShow && e.season > 0 && e.episode > 0) "S${e.season} · E${e.episode}" else null
        val left = if (!e.isShow && e.durationMs > 0) ((e.durationMs - e.positionMs) / 60_000).takeIf { it > 0 }?.let { "$it min left" } else null
        if (e.upNext) card.badge("UP NEXT")
        else if (e.progress > 0f) card.progress(e.progress)
        // Another app's titles carry its icon; Dream OS's own carry none.
        val pkg = if (WatchHistory.ours(e)) null else e.source
        apps.firstOrNull { it.pkg == pkg }?.icon?.let { icon ->
            card.frame.addView(ImageView(ctx).apply {
                setImageDrawable(icon)
                background = Ui.rounded(ctx, Color.argb(150, 0, 0, 0), Ui.R_PILL)
                setPadding(4.dp, 4.dp, 4.dp, 4.dp)
            }, FrameLayout.LayoutParams(24.dp, 24.dp, Gravity.TOP or Gravity.END).apply { setMargins(0, 8.dp, 8.dp, 0) })
        }
        val sub = when {
            code != null -> listOfNotNull(if (e.upNext) "Up Next" else null, code).joinToString(" · ")
            left != null -> left
            else -> subline(t)
        }
        return unit(card, null, t.name, sub, focusOnly = false) to card
    }

    /** New Episodes: the episode still, the show and the air day underneath. */
    fun episode(item: NewEpisodes.Item): Pair<View, CinemaCard> {
        val t = item.show.title()
        val lw = fit(CinemaStyle.LAND_W)
        val card = CinemaCard(this, lw, lw * 9 / 16)
        card.title = t
        card.art().source(item.image("w300"))
        if (NewEpisodes.isNew(ctx, item)) card.badge("NEW")
        return unit(card, null, item.show.name, item.badge().replace("  ·  ", " · "), focusOnly = false) to card
    }

    /**
     * A brand tile (Disney+): the service's name set in bold on a deep tint of its brand color, a short bar of
     * the brand color under it. A way into that service's movies and shows (never its app: apps live on
     * the Apps tab), so every tile is the same family whether the app is installed or not.
     */
    fun brand(spec: ServiceSpec): CinemaCard {
        val bw = fit(CinemaStyle.BRAND_W)
        val card = CinemaCard(this, bw, bw * 9 / 16)
        card.frame.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Ui.mix(spec.color, tile, 0.40f), Ui.mix(spec.color, bgTop, 0.80f))).apply { cornerRadius = Ui.dpf(ctx, CinemaStyle.R_CARD.toFloat()) }
        card.frame.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(label(spec.name, 19f, text, bold).apply { gravity = Gravity.CENTER; letterSpacing = -0.01f })
            addView(View(ctx).apply { background = Ui.rounded(ctx, Ui.mix(spec.color, text, 0.25f), Ui.R_PILL) },
                LinearLayout.LayoutParams(22.dp, 3.dp).apply { topMargin = 9.dp })
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply { setMargins(10.dp, 0, 10.dp, 0) })
        card.frame.foreground = Ui.rounded(ctx, Color.TRANSPARENT, CinemaStyle.R_CARD, 1f, Ui.alpha(text, 0.10f))
        return card
    }

    /** An installed app: its banner (or icon and name), the name underneath on focus. */
    fun app(app: AppEntry, w: Int, h: Int): Pair<View, CinemaCard> {
        val card = CinemaCard(this, w, h)
        val banner = app.banner
        if (banner != null) {
            card.frame.addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageDrawable(banner) }, MATCH_PARENT, MATCH_PARENT)
        } else {
            card.frame.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(ImageView(ctx).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams((h * 0.42f).toInt().dp, (h * 0.42f).toInt().dp))
                addView(label(app.label, 12.5f, text, medium).apply { gravity = Gravity.CENTER; setPadding(8.dp, 6.dp, 8.dp, 0) })
            }, MATCH_PARENT, MATCH_PARENT)
        }
        return unit(card, null, app.label, null, focusOnly = true) to card
    }

    /**
     * A card with its caption underneath, outside its rounded clip: an optional [kicker], the [title]
     * and a dim [sub] line. [focusOnly]: the caption fades in only while the card has focus (Apple TV);
     * else it always shows (Hulu). Its height is fixed either way (nothing moves when it appears), and it
     * rides down with the card's lift so the gap under the art never closes.
     */
    fun unit(card: CinemaCard, kick: String?, title: String, sub: String?, @Suppress("UNUSED_PARAMETER") focusOnly: Boolean = false, titleLines: Int = 1): LinearLayout {
        // Every card names its title underneath at all times (the user's rule); [focusOnly] is kept for callers only.
        val w = card.wPx
        val cap = FlatLinear(ctx).apply {
            orientation = LinearLayout.VERTICAL
            if (kick != null) addView(kicker(kick), LinearLayout.LayoutParams(w, WRAP_CONTENT).apply { bottomMargin = 5.dp })
            // One line: ellipsized at rest, gliding to its end while the card has focus ([MarqueeText]).
            val name: TextView = if (titleLines == 1) MarqueeText(ctx).apply {
                textSize = CinemaStyle.T_CARD_TITLE
                typeface = semibold
                setTextColor(this@CinemaKit.text)
                includeFontPadding = false
                text = title
            }.also { m -> card.onFocus { has -> m.cardFocus(has) } }
            else label(title, CinemaStyle.T_CARD_TITLE, text, semibold, lines = titleLines).apply { setLineSpacing(0f, 1.05f) }
            addView(name, LinearLayout.LayoutParams(w, WRAP_CONTENT))
            if (!sub.isNullOrEmpty()) addView(label(sub, CinemaStyle.T_META, dim, medium), LinearLayout.LayoutParams(w, WRAP_CONTENT).apply { topMargin = 4.dp })
        }
        // The focus lift grows the card up and out from its bottom edge, so it never meets its caption and
        // every caption in a row stays on one line (nothing under the card moves, nothing lays out).
        card.pivotX = w / 2f
        card.pivotY = card.hPx.toFloat()
        return FlatLinear(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(card)
            addView(cap, LinearLayout.LayoutParams(w, captionHeight(kick != null, !sub.isNullOrEmpty(), titleLines)).apply { topMargin = CAPTION_GAP.dp })
        }
    }

    /** A caption's fixed height (dp → px): kicker, title (one or two lines) and sub line, as [unit] lays them out. */
    fun captionHeight(kicker: Boolean, sub: Boolean, titleLines: Int = 1): Int =
        ((if (kicker) 15 + 5 else 0) + 19 * titleLines + (if (sub) 4 + 17 else 0)).dp

    /** "2024 · Series": the quiet line under a title. */
    fun subline(t: Title) = listOfNotNull(t.year.ifEmpty { null }, if (t.type == "tv") "Series" else "Movie").joinToString(" · ")

    /** "2024 · Series · Drama · Comedy": the hero's and big cards' meta line (genres from the list result, no lookup). */
    fun meta(t: Title, genres: Int = 2): String {
        val g = t.genreIds.mapNotNull { Discover.genreName(t.type, it) }.take(genres)
        return (listOfNotNull(t.year.ifEmpty { null }, if (t.type == "tv") "Series" else "Movie") + g).joinToString("  ·  ")
    }

    // ---- controls ----

    /**
     * Netflix's buttons: [primary] (Play) is a pearl capsule with ink text, the others translucent with
     * pearl text; focused, any of them turns pearl, lifts a touch and glows in the current hue.
     */
    fun button(label: String, iconRes: Int?, primary: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 16f
        typeface = semibold
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        // Play rests white; focused, it wears the rainbow as one solid color slowly fading from hue to hue
        // (Rainbow.fade), its text and icon following in a readable ink. The others are dark gray glass at rest;
        // focused, a pearl box with dark ink, the rainbow glow around it (same corners).
        val rest = if (primary) Color.WHITE else this@CinemaKit.text
        val colors = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), IntArray(0)), intArrayOf(if (primary) Color.WHITE else ink, rest))
        setTextColor(colors)
        iconRes?.let { res ->
            setCompoundDrawables(ctx.getDrawable(res)!!.mutate().apply { setBounds(0, 0, 20.dp, 20.dp) }, null, null, null)
            compoundDrawableTintList = colors
            compoundDrawablePadding = 10.dp
        }
        setPadding(if (iconRes != null) 22.dp else 28.dp, 0, 28.dp, 0)
        val glow = CinemaStyle.glowDrawable(ctx, radiusDp = CinemaStyle.R_BUTTON, haloDp = CinemaStyle.GLOW_HALO_SMALL)
        val rainbow = if (!primary) null else GradientDrawable().apply {
            cornerRadius = Ui.dpf(ctx, CinemaStyle.R_BUTTON.toFloat())
        }
        val fade = if (rainbow == null) null else Rainbow.fade(this, rainbow) { c ->
            val ink = ColorStateList.valueOf(c)
            setTextColor(ink)
            compoundDrawableTintList = ink
        }
        background = LayerDrawable(arrayOf(glow, rainbow ?: StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(ctx, Rainbow.PLAY_REST, CinemaStyle.R_BUTTON))
            addState(IntArray(0), Ui.rounded(ctx, Ui.alpha(Ui.mix(secondary, this@CinemaKit.text, 0.06f), 0.88f), CinemaStyle.R_BUTTON))
            setEnterFadeDuration(Ui.FOCUS_IN.toInt())
            setExitFadeDuration(Ui.FOCUS_OUT.toInt())
        }))
        isFocusable = true
        isClickable = true
        id = View.generateViewId()
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, BUTTON_H.dp).apply { marginEnd = 14.dp }
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, has ->
            CinemaStyle.focusGlow(glow, has)
            fade?.focus(has)
            val s = if (has) 1.06f else 1f
            v.animate().scaleX(s).scaleY(s).setStartDelay(0).setDuration(CinemaStyle.FOCUS_MS).setInterpolator(Ui.EASE).start()
        }
    }

    /**
     * Hulu's category chip: an outline at rest, a soft fill when it is the current one ([View.isSelected]),
     * pearl with ink text and a small glow while focused.
     */
    fun chip(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 14.5f
        typeface = medium
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_selected), IntArray(0)),
            intArrayOf(ink, this@CinemaKit.text, Ui.alpha(this@CinemaKit.text, 0.74f))))
        setPadding(18.dp, 0, 18.dp, 0)
        val glow = CinemaStyle.glowDrawable(ctx, radiusDp = CHIP_H / 2, haloDp = CinemaStyle.GLOW_HALO_SMALL).apply { haloAlpha = 0.42f }
        background = LayerDrawable(arrayOf(glow, StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(ctx, Rainbow.PLAY_REST, CinemaStyle.R_CHIP))
            addState(intArrayOf(android.R.attr.state_selected), Ui.rounded(ctx, Ui.alpha(this@CinemaKit.text, 0.16f), CinemaStyle.R_CHIP, 1f, Ui.alpha(this@CinemaKit.text, 0.30f)))
            addState(IntArray(0), Ui.rounded(ctx, Color.TRANSPARENT, CinemaStyle.R_CHIP, 1f, Ui.alpha(this@CinemaKit.text, 0.22f)))
            setEnterFadeDuration(Ui.FOCUS_IN.toInt())
            setExitFadeDuration(Ui.FOCUS_OUT.toInt())
        }))
        isFocusable = true
        isClickable = true
        id = View.generateViewId()
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, CHIP_H.dp).apply { marginEnd = 12.dp }
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, has ->
            CinemaStyle.focusGlow(glow, has)
            val s = if (has) 1.06f else 1f
            v.animate().scaleX(s).scaleY(s).setStartDelay(0).setDuration(CinemaStyle.FOCUS_MS).setInterpolator(Ui.EASE).start()
        }
    }

    /** Four glow hues from [start] on, deepened a touch so white text reads on all of them (Play's fill). */
    fun rainbowFrom(start: Int): IntArray {
        val i = CinemaStyle.GLOW_HUES.indexOf(start).coerceAtLeast(0)
        return IntArray(4) { Ui.mix(CinemaStyle.glowColor(i + it), Color.BLACK, 0.18f) }
    }

    /** A quiet empty state: a line icon in a soft disc, a title and a hint. */
    fun empty(shape: Int, title: String, hint: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, 36.dp, 0, 24.dp)
        addView(ImageView(ctx).apply {
            setImageDrawable(LineIcon(shape).apply { setTint(Ui.alpha(text, 0.9f)) })
            val p = 22.dp
            setPadding(p, p, p, p)
            background = Ui.rounded(ctx, Ui.alpha(text, 0.08f), Ui.R_PILL)
        }, LinearLayout.LayoutParams(80.dp, 80.dp))
        addView(label(title, 22f, text, semibold).apply { gravity = Gravity.CENTER; setPadding(0, 20.dp, 0, 8.dp) })
        addView(label(hint, 15f, dim, font, lines = 3).apply { gravity = Gravity.CENTER; maxWidth = 520.dp; setLineSpacing(0f, 1.2f) })
    }

    // ---- backgrounds ----

    /**
     * A scrim that melts art into the page: the page's own background (whatever the theme draws there,
     * gradient or photo) painted at quarter size for the screen area at ([topPx], [wPx]×[hPx]), with
     * [alpha] (u, v in 0..1 across the area) as its opacity. Built once per size and theme and drawn
     * scaled with filtering: one textured quad a frame, no shader math, and it matches the background
     * behind it exactly (no dark band where a black fade would meet the purple).
     */
    fun bgFade(wPx: Int, hPx: Int, topPx: Int, key: String, alpha: (Float, Float) -> Float): Drawable {
        val spec = SmoothGradient.spec(ctx)
        val sig = "$key|$wPx|$hPx|$topPx|${spec?.key ?: "photo"}"
        val bmp = fades[sig] ?: spec?.let {
            // The theme's gradient: full resolution, computed per pixel and dithered (no bands, no blocks).
            try { SmoothGradient.render(it, maxOf(2, wPx), maxOf(2, hPx), topPx, alpha) } catch (e: Throwable) { Log.w("FireHome", "cinema fade: $e"); null }
                ?.also { b -> if (fades.size >= 6) fades.clear(); fades[sig] = b }
        } ?: run {
            // A photo wallpaper: sampled from the photo itself, at a quarter size (the photo has its own grain).
            val sw = maxOf(2, wPx / 4)
            val sh = maxOf(2, hPx / 4)
            val b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            try {
                val c = Canvas(b)
                c.scale(sw / wPx.toFloat(), sh / hPx.toFloat())
                c.translate(0f, -topPx.toFloat())
                Theme.background(ctx).apply { setBounds(0, 0, screenW, screenH) }.draw(c)
            } catch (e: Exception) {
                Log.w("FireHome", "cinema fade: background didn't draw: $e")
                b.eraseColor(bgTop)
            }
            val px = IntArray(sw * sh)
            b.getPixels(px, 0, sw, 0, 0, sw, sh)
            for (y in 0 until sh) {
                val v = y / (sh - 1f)
                for (x in 0 until sw) {
                    val a = (alpha(x / (sw - 1f), v).coerceIn(0f, 1f) * 255).toInt()
                    val i = y * sw + x
                    px[i] = (a shl 24) or (px[i] and 0xFFFFFF)
                }
            }
            b.setPixels(px, 0, sw, 0, 0, sw, sh)
            if (fades.size > 8) fades.clear()
            fades[sig] = b
            b
        }
        return android.graphics.drawable.BitmapDrawable(ctx.resources, bmp).apply { isFilterBitmap = true }
    }

    companion object {
        /** The Spotlight card's height (dp). */
        /** No taller than a row (with its title and captions): the spotlight and the next row both fit above the fade. */
        const val SPOTLIGHT_H = 204
        /** The Top 10 numeral's column (dp; two digits take more). */
        const val RANK_NUM = 64
        /** One low-priority thread draws the Top 10 numerals' outlines (a few ms each, once per row). */
        private val RANK_ART: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "rank-art")
        }
        /** Clear space between a rank numeral's ink and its poster (beyond the poster's 1.07 focus lift). */
        const val RANK_GAP = 14
        const val BUTTON_H = 44
        const val CHIP_H = 36
        /** Room between a card's art and its caption (dp). */
        const val CAPTION_GAP = 10
        private val fades = HashMap<String, Bitmap>()

        /** Smooth 0..1 ramp (smoothstep). */
        fun ease(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3 - 2 * x) }
    }
}

/**
 * A card's art. Its size never depends on the image (it fills a card of fixed size), so a swap never
 * asks for a layout: ImageView would otherwise request one whenever the new image's size differs (any
 * load, any release), re-measuring the path up to the page. Knows its url ([source]) before it loads,
 * so a row can bind art only near focus ([bind]) and let it go far away ([unbind]).
 */
class CinemaArt(ctx: Context) : ImageView(ctx) {
    var url: String? = null
        private set
    private var fallbacks: List<String> = emptyList()
    private var swapping = false

    init { scaleType = ScaleType.CENTER_CROP }

    fun source(u: String?, fb: List<String> = emptyList()) {
        url = u
        fallbacks = fb
        tag = u // Net.unbind / rebind find it by its tag
    }

    /** The row this art is bound by and its place in it ([CinemaRow.rankOf]: queued art is re-ranked as focus moves). */
    var owner: CinemaRow? = null
    var slot = 0
    /** Called once when the art next shows (main thread), with where its time went ([Net.lastTiming]). */
    var onShown: ((Net.ArtTiming?) -> Unit)? = null

    fun bind(rank: Int) {
        val u = url ?: return
        if (drawable != null || Net.loading(this)) return
        if (onShown == null) Net.image(context, this, u, fallbacks, rank)
        else Net.image(context, this, u, fallbacks, rank) { _ -> onShown?.let { f -> onShown = null; f(Net.lastTiming) } }
    }

    fun unbind() {
        if (url == null || (drawable == null && !Net.loading(this))) return
        Net.cancel(this)
        animate().cancel()
        alpha = 1f
        setImageDrawable(null)
    }

    override fun requestLayout() { if (!swapping) super.requestLayout() }

    override fun setImageDrawable(d: Drawable?) {
        swapping = isLaidOut && width > 0
        try { super.setImageDrawable(d) } finally { swapping = false }
    }
}

/**
 * An image fitted by its own matrix, so a new image never needs a layout: [crop] fills the box and keeps
 * the top part of the art ([bias] 0 = top, 0.5 = centered: hero backdrops keep faces, which sit high);
 * otherwise it fits inside the box, anchored bottom-left (a title logo sitting right on the meta line).
 */
class FitImage(ctx: Context, private val crop: Boolean, private val bias: Float = 0.22f) : ImageView(ctx) {
    private val m = Matrix()
    private var swapping = false

    init { scaleType = ScaleType.MATRIX }

    override fun requestLayout() { if (!swapping) super.requestLayout() }

    override fun setImageDrawable(d: Drawable?) {
        swapping = isLaidOut && width > 0
        try { super.setImageDrawable(d) } finally { swapping = false }
        place()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        place()
    }

    private fun place() {
        val d = drawable ?: return
        val bw = d.intrinsicWidth.toFloat()
        val bh = d.intrinsicHeight.toFloat()
        if (bw <= 0 || bh <= 0 || width == 0 || height == 0) return
        m.reset()
        if (crop) {
            val s = maxOf(width / bw, height / bh)
            m.setScale(s, s)
            m.postTranslate((width - bw * s) / 2f, -(bh * s - height) * bias)
        } else {
            val s = minOf(width / bw, height / bh)
            m.setScale(s, s)
            m.postTranslate(0f, height - bh * s)
        }
        imageMatrix = m
    }
}

/**
 * The Cinema card: an unclipped holder that is the focusable view, scales on focus and draws the glow
 * behind and around its art ([glow] as its background), with the art clipped to rounded corners inside
 * ([frame]). Its parent must not clip children (the halo reaches past it).
 */
class CinemaCard(val kit: CinemaKit, wDp: Int, hDp: Int, radiusDp: Int = CinemaStyle.R_CARD, val big: Boolean = false) : FrameLayout(kit.ctx) {
    val glow = CinemaStyle.glowDrawable(context, radiusDp = radiusDp)
    val frame = FlatFrame(context)
    val wPx = Ui.dp(context, wDp)
    val hPx = Ui.dp(context, hDp)
    /** The movie or show this card opens (Instant Play looks for its stream while focus rests here). */
    var title: Title? = null
    /** A card that writes text over its art (the Spotlight): where a status pill goes instead of the art's corner. */
    var statusSlot: LinearLayout? = null
    private var artView: CinemaArt? = null
    private val listeners = ArrayList<(Boolean) -> Unit>()
    private val radius = radiusDp

    init {
        layoutParams = LinearLayout.LayoutParams(wPx, hPx)
        background = glow
        clipChildren = false
        clipToPadding = false
        frame.background = Ui.rounded(context, kit.cardFill, radiusDp)
        frame.outlineProvider = ViewOutlineProvider.BACKGROUND
        frame.clipToOutline = true
        addView(frame, MATCH_PARENT, MATCH_PARENT)
        isFocusable = true
        isClickable = true
        setOnFocusChangeListener { _, has ->
            CinemaStyle.focusGlow(glow, has)
            CinemaStyle.lift(this, has, big, z = false)
            for (l in listeners) l(has)
        }
    }

    override fun hasOverlappingRendering() = false

    fun onFocus(l: (Boolean) -> Unit) { listeners += l }

    fun art(): CinemaArt = artView ?: CinemaArt(context).also { artView = it; frame.addView(it, MATCH_PARENT, MATCH_PARENT) }

    val artOrNull: CinemaArt? get() = artView

    /** The name inside the tile, for a title with no art at all. */
    fun placeholder(name: String) {
        frame.addView(kit.label(name, 14f, kit.dim, kit.medium, lines = 2).apply {
            gravity = Gravity.CENTER
            setPadding(12.dp(), 0, 12.dp(), 0)
        }, 0, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
    }

    /** A small capsule in the top-left corner (NEW, UP NEXT). */
    fun badge(t: String) {
        frame.addView(kit.label(t, 10.5f, kit.ink, kit.bold, tracking = 0.08f).apply {
            setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
            background = Ui.rounded(context, kit.text, Ui.R_PILL)
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(8.dp(), 8.dp(), 8.dp(), 8.dp()) })
    }

    /** Progress along the bottom: pearl at rest, the glow's hue while the card has focus. */
    fun progress(p: Float) {
        val inner = wPx - 2 * 10.dp()
        val fill = GradientDrawable().apply { cornerRadius = 2.dp().toFloat(); setColor(kit.text) }
        frame.addView(FrameLayout(context).apply {
            background = Ui.rounded(context, Color.argb(110, 255, 255, 255), Ui.R_PILL)
            addView(View(context).apply { background = fill }, FrameLayout.LayoutParams((inner * p.coerceIn(0.04f, 1f)).toInt(), MATCH_PARENT))
        }, FrameLayout.LayoutParams(MATCH_PARENT, 4.dp(), Gravity.BOTTOM).apply { setMargins(10.dp(), 0, 10.dp(), 9.dp()) })
        // Focused, the bar takes the fading highlight color (it follows the shared clock); at rest, pearl.
        val bar = frame.getChildAt(frame.childCount - 1)
        onFocus { has ->
            if (has) CinemaStyle.followGlow(bar) { v, c -> (((v as FrameLayout).getChildAt(0)).background as GradientDrawable).setColor(c) }
            else { CinemaStyle.unfollowGlow(bar); fill.setColor(kit.text) }
        }
    }

    private fun Int.dp() = Ui.dp(context, this)
}

/** A section of a Cinema page that focus can enter from above or below (hero, chips, a row). */
interface CinemaSection {
    /** Where focus lands coming into this section (a row's remembered card, the hero's Play); null: nothing to focus yet. */
    fun entry(): View?
}

/** The end of a long page: "You're All Caught Up" and Back to Top. */
class CinemaEndCap(kit: CinemaKit, onTop: () -> Unit) : FlatLinear(kit.ctx), CinemaSection {
    private val button = kit.button("Back to Top", R.drawable.ic_arrow_up, primary = false) { onTop() }

    init {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        setPadding(0, Ui.dp(context, 12), 0, Ui.dp(context, 24))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.TRANSPARENT, Ui.alpha(kit.text, 0.28f), Color.TRANSPARENT))
        }, LinearLayout.LayoutParams(Ui.dp(context, 420), Math.max(1, Ui.dp(context, 1))))
        addView(kit.label("You're All Caught Up", 13.5f, kit.dim, kit.medium, tracking = 0.02f).apply { setPadding(0, Ui.dp(context, 16), 0, Ui.dp(context, 16)) })
        (button.layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        addView(button)
    }

    override fun entry(): View? = button
}

/**
 * A row (Apple TV): its title on the page's left line and the cards underneath, which glide so the
 * focused card always sits on that same line (the end of the row stops at the right margin instead).
 * Left and Right move along it only (never into another row); the row remembers its card, so coming
 * back down or up lands where you left it. Cards left of the line fade out: nothing is ever cut by the
 * screen's edge on the left. The strip moves by translationX alone (no scrolling, no layout).
 */
class CinemaRow(val kit: CinemaKit, title: String?) : FlatLinear(kit.ctx), CinemaSection {
    private val ctx = kit.ctx
    val header: TextView? = title?.let {
        kit.label(it, CinemaStyle.T_ROW, kit.text, kit.semibold).apply { setPadding(kit.gutter, 0, kit.gutter, 0) }
    }
    /**
     * Units faded all the way out (past the margins; most of a long row): left out of the strip's display list,
     * so the renderer neither syncs nor walks them each frame (an alpha-0 view is skipped when drawn, but still
     * synced, with all its art, caption and glow nodes). They stay VISIBLE and laid out: focus, measuring and
     * layout see no difference, and a unit is put back ([unpark]) the moment it starts fading in.
     */
    private val parked = HashSet<View>()
    private val strip = object : FlatLinear(ctx) {
        override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean =
            if (child in parked) false else super.drawChild(canvas, child, drawingTime)
    }.apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        setPadding(kit.gutter, 0, kit.gutter, 0)
    }
    private val density = ctx.resources.displayMetrics.density
    private val arrowSize = (ARROW_DP * density).toInt()
    private val chevron: Drawable? = ctx.getDrawable(R.drawable.ic_chevron_left)?.mutate()?.apply { setTint(kit.text) }
    private var arrowL = 0f
    private var arrowR = 0f
    private var wantL = false
    private var wantR = false
    private var arrowAnim: android.animation.ValueAnimator? = null

    private val track = object : FreeTrack(ctx) {
        override fun focusSearch(focused: View?, direction: Int): View? {
            if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) {
                val step = if (direction == View.FOCUS_RIGHT) 1 else -1
                val i = cards.indexOfFirst { focused != null && HomeShell.isInside(focused, it) }
                if (i >= 0) return cards.getOrNull(i + step) ?: focused
                // Along the placeholders the same way (never out of the row).
                val g = ghosts.indexOfFirst { focused === it }
                if (g >= 0) return ghosts.getOrNull(g + step) ?: focused
            }
            return super.focusSearch(focused, direction)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            glideAfterLayout.let { if (it >= 0) { glideAfterLayout = -1; if (it == index) glideTo(it) } }
            // Cards that arrived (or a row that came back) take the fades for where the row rests, without motion.
            settle(animate = false)
        }

        // The more-to-scroll chevrons, drawn in the margins level with the middle of the cards.
        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            super.dispatchDraw(canvas)
            val d = chevron ?: return
            if (arrowL <= 0f && arrowR <= 0f) return
            val card = cards.firstOrNull() ?: return
            val unit = units.firstOrNull() ?: return
            val cy = strip.top + unit.top + (if (card !== unit) card.top else 0) + card.height / 2
            val half = arrowSize / 2
            if (arrowL > 0f) {
                val cx = kit.gutter / 2
                d.setBounds(cx - half, cy - half, cx + half, cy + half)
                d.alpha = (255 * ARROW_ALPHA * arrowL).toInt()
                d.draw(canvas)
            }
            if (arrowR > 0f) {
                val cx = width - kit.gutter / 2
                canvas.save()
                canvas.scale(-1f, 1f, cx.toFloat(), cy.toFloat())
                d.setBounds(cx - half, cy - half, cx + half, cy + half)
                d.alpha = (255 * ARROW_ALPHA * arrowR).toInt()
                d.draw(canvas)
                canvas.restore()
            }
        }
    }.apply {
        clipChildren = false
        clipToPadding = false
        addView(strip, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }
    val cards = ArrayList<CinemaCard>()
    /**
     * The placeholders until the cards arrive ([skeleton]): they take focus like cards (Netflix), so focus
     * can rest on a row that's still loading and Down goes row by row, never past the rows not in yet.
     * [fill] hands focus from the placeholder to the card in its place.
     */
    private val ghosts = ArrayList<CinemaCard>()
    /** A card that took focus before its first layout: glided to once it has one ([focused]); -1 none. */
    private var glideAfterLayout = -1
    private val units = ArrayList<View>()
    /** The card focus is on, or was last on (where it lands coming back). */
    var index = 0
        private set
    /** Where the strip is gliding to (or resting); fades and chevrons follow this, not the frames in between. */
    private var target = 0f
    private val faded = java.util.WeakHashMap<View, Float>()
    /** Called when one of this row's cards takes focus. */
    var onCardFocus: ((CinemaRow, CinemaCard) -> Unit)? = null
    /** Called when focus comes within [RowPaging.NEAR_END] cards of the end (a deep row loads its next page). */
    var onNearEnd: ((CinemaRow) -> Unit)? = null
    /** Set once real cards are in (skeletons until then). */
    var filled = false
        private set
    /** One line of a grid: coming in from the line above or below keeps the column ([prefer]). */
    var grid = false
    private var artOn = false
    private var artRank = 0
    /** Called once when the cards between the margins at [fill] all show their art, with each one's timing (the page's RowTiming). */
    var onArtReady: ((List<Net.ArtTiming?>) -> Unit)? = null

    init {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        header?.let { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = CinemaStyle.ROW_TITLE_GAP.dp() + 4.dp() }) }
        addView(track, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    override fun entry(): View? =
        if (cards.isNotEmpty()) cards[index.coerceIn(0, cards.size - 1)] else ghosts.getOrNull(index.coerceIn(0, maxOf(0, ghosts.size - 1)))

    /** The card focus should land on when it next comes in (a grid line keeps the column it came from). */
    fun prefer(i: Int) {
        val n = if (cards.isNotEmpty()) cards.size else ghosts.size
        if (n > 0) index = i.coerceIn(0, n - 1)
    }

    /**
     * Placeholders of the real cards' size (the theme's tile color, static: no shimmer to draw each frame), so
     * the row keeps its height when they arrive. They take focus like cards do ([ghosts]).
     */
    fun skeleton(count: Int, wDp: Int, hDp: Int, captionPx: Int) {
        strip.removeAllViews()
        parked.clear()
        ghosts.clear()
        val w = kit.fit(wDp)
        val h = hDp * w / wDp
        // Only the ones that fit between the margins (nothing is ever cut by the screen's edge).
        val n = minOf(count, maxOf(1, (kit.pageW() + CinemaStyle.CARD_GAP) / (w + CinemaStyle.CARD_GAP)))
        for (i in 0 until n) {
            val g = CinemaCard(kit, w, h, big = wDp >= CinemaStyle.BIG_W).apply {
                frame.background = Ui.rounded(ctx, kit.tile, CinemaStyle.R_CARD)
                frame.clipToOutline = false // nothing in it to clip
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                onFocus { has -> if (has) index = i }
            }
            ghosts += g
            strip.addView(FlatLinear(ctx).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                addView(g)
                if (captionPx > 0) addView(View(ctx), LinearLayout.LayoutParams(w.dp(), captionPx + CinemaKit.CAPTION_GAP.dp()))
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (i < n - 1) marginEnd = CinemaStyle.CARD_GAP.dp() })
        }
    }

    /**
     * The real cards ([pairs]: each unit as laid out, and the card in it). The ones on screen glide in once if
     * [animate]. Focus resting on a placeholder moves to the card in its place before the placeholders go, so
     * it never leaves the row and the page doesn't move.
     */
    fun fill(pairs: List<Pair<View, CinemaCard>>, animate: Boolean) {
        val keep = ghosts.indexOfFirst { it.isFocused }
        // Nothing to put in place of a placeholder focus rests on: the row stays as it is.
        if (keep >= 0 && pairs.isEmpty()) return
        val leaving = if (keep < 0) emptyList() else (0 until strip.childCount).map { strip.getChildAt(it) }
        if (keep < 0) strip.removeAllViews()
        ghosts.clear()
        parked.clear()
        units.clear()
        cards.clear()
        faded.clear()
        index = 0
        target = 0f
        strip.animate().cancel()
        strip.translationX = 0f
        val gap = CinemaStyle.CARD_GAP.dp()
        val right = kit.screenW - kit.gutter
        var x = kit.gutter
        for ((i, p) in pairs.withIndex()) {
            val (unit, card) = p
            units += unit
            cards += card
            // A bare card keeps its fixed size (a banner would otherwise wrap its own pixel size).
            val lp = (unit.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            lp.marginEnd = if (i < pairs.size - 1) gap else 0
            strip.addView(unit, lp)
            card.onFocus { has -> if (has) focused(card) }
            // Cards past the right margin start hidden (the chevron says there's more), never cut off.
            unit.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val inside = x + unit.measuredWidth <= right + SLACK
            x += unit.measuredWidth + gap
            faded[unit] = if (inside) 1f else 0f
            if (!inside) { unit.alpha = 0f; park(unit) }
            else if (animate) {
                unit.alpha = 0f
                // Under focus they only fade in where the placeholders were (nothing slides under the user).
                unit.translationX = if (keep >= 0) 0f else 28.dp().toFloat()
                unit.animate().alpha(1f).translationX(0f).setStartDelay(if (keep >= 0) 0L else 40L + i * 45L)
                    .setDuration(if (keep >= 0) 220L else 360L).setInterpolator(Ui.EASE).start()
            }
        }
        setArrows(left = false, right = x - gap > right + SLACK)
        filled = true
        onArtReady?.let { trackArt(it) }
        if (keep >= 0) {
            // Focus onto the card where it rested, then the placeholders go (removing a focused view would drop focus).
            cards[minOf(keep, cards.size - 1)].requestFocus()
            for (v in leaving) strip.removeView(v)
        }
        if (artOn) bindNear()
    }

    private fun focused(card: CinemaCard) {
        val i = cards.indexOf(card)
        if (i < 0) return
        index = i
        // Cards just put in (focus handed over from a placeholder) have no place yet: the glide waits for layout.
        if (strip.isLayoutRequested) glideAfterLayout = i else glideTo(i)
        bindNear()
        onCardFocus?.invoke(this, card)
        if (i >= cards.size - RowPaging.NEAR_END) onNearEnd?.invoke(this)
    }

    /**
     * More cards ([pairs]) at the end of a filled row (its next page). Nothing already in moves or is
     * measured again (each new card brings its gap before it); they start hidden past the right margin
     * and fade in as the row glides to them, like the rest.
     */
    fun append(pairs: List<Pair<View, CinemaCard>>) {
        if (pairs.isEmpty() || !filled) return
        val gap = CinemaStyle.CARD_GAP.dp()
        for ((unit, card) in pairs) {
            val lp = (unit.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            lp.marginStart = gap
            lp.marginEnd = 0
            units += unit
            cards += card
            faded[unit] = 0f
            unit.alpha = 0f
            park(unit)
            strip.addView(unit, lp)
            card.onFocus { has -> if (has) focused(card) }
        }
        setArrows(wantL, true)
        if (artOn) bindNear()
    }

    private fun trackW() = if (track.width > 0) track.width else kit.screenW

    /**
     * Brings card [i] between the margins, moving the row only as far as it must (like [EdgeRow]):
     * every new press re-aims the same glide, so fast presses never leave it between cards.
     */
    private fun glideTo(i: Int) {
        val u = units.getOrNull(i) ?: return
        val w = trackW()
        var t = target
        if (u.right + t > w - kit.gutter) t = (w - kit.gutter - u.right).toFloat()
        if (u.left + t < kit.gutter) t = (kit.gutter - u.left).toFloat()
        t = t.coerceIn(minOf(0f, (w - strip.width).toFloat()), 0f)
        target = t
        if (strip.translationX != t) strip.animate().translationX(t).setStartDelay(0).setDuration(CinemaStyle.ROW_SCROLL_MS).setInterpolator(Ui.EASE).start()
        settle(animate = true)
    }

    /**
     * Cards not wholly between the margins where the row is headed fade out (never the focused one), so no
     * card is ever cut by the screen's edge; the chevrons in the margins say there's more that way.
     */
    private fun settle(animate: Boolean) {
        if (units.isEmpty() || strip.width == 0) return
        val w = trackW()
        for ((j, v) in units.withIndex()) {
            val x = v.left + target
            val inside = cards[j].isFocused || (x >= kit.gutter - SLACK && x + v.width <= w - kit.gutter + SLACK)
            val a = if (inside) 1f else 0f
            if (faded[v] == a) continue
            faded[v] = a
            val anim = v.animate().alpha(a).setStartDelay(0).setDuration(if (!animate) 0L else if (inside) 220L else 140L).setInterpolator(Ui.EASE)
            if (inside) unpark(v)
            else anim.withEndAction { if (faded[v] == 0f && v.alpha == 0f && v.parent === strip) park(v) }
            anim.start()
        }
        setArrows(target < -SLACK, strip.width + target > w + SLACK)
    }

    /** [v] (faded out) leaves the strip's display list; [unpark] puts it back (one re-record of the strip, no layout). */
    private fun park(v: View) { if (parked.add(v)) strip.invalidate() }
    private fun unpark(v: View) { if (parked.remove(v)) strip.invalidate() }

    private fun setArrows(left: Boolean, right: Boolean) {
        if (left == wantL && right == wantR) return
        wantL = left
        wantR = right
        val fromL = arrowL
        val fromR = arrowR
        arrowAnim?.cancel()
        arrowAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = Ui.EASE
            addUpdateListener {
                val f = it.animatedValue as Float
                arrowL = fromL + ((if (wantL) 1f else 0f) - fromL) * f
                arrowR = fromR + ((if (wantR) 1f else 0f) - fromR) * f
                track.invalidate()
            }
            start()
        }
    }

    /** Back to the first card without moving focus (the page went back to the top). */
    fun reset() {
        if (index == 0 && target == 0f) return
        index = 0
        target = 0f
        strip.animate().translationX(0f).setDuration(CinemaStyle.ROW_SCROLL_MS).setInterpolator(Ui.EASE).start()
        settle(animate = true)
    }

    /** Art on ([on], the row is on screen or about to be) or let go; [rank] orders the decodes (distance from focus). */
    fun art(on: Boolean, rank: Int) {
        artRank = rank
        if (on == artOn) { if (on) bindNear(); return }
        artOn = on
        // Let go: its timing line is dropped too (art that shows on a later visit isn't this row's first wait).
        if (on) bindNear() else for (c in cards) c.artOrNull?.let { it.onShown = null; it.unbind() }
    }

    /**
     * Where card [slot]'s art goes in the art queue: by the row's rank (its distance from focus, see [art]) first,
     * then, within the row, the cards nearest the focused one (or the first ones) first.
     */
    private fun rankFor(slot: Int) = artRank * 4 + minOf(3, kotlin.math.abs(slot - index) / 3)

    /** [a]'s rank now (null: this row holds no art, or [a] isn't one of its cards): for [Net.reorder] on focus moves. */
    fun rankOf(a: CinemaArt): Int? = if (!artOn || a.owner !== this) null else rankFor(a.slot)

    /** The cards between the margins now report when their art shows; once all have, [ready] gets their timings. */
    private fun trackArt(ready: (List<Net.ArtTiming?>) -> Unit) {
        onArtReady = null
        val first = units.indices.filter { faded[units[it]] == 1f }.mapNotNull { cards[it].artOrNull?.takeIf { a -> a.url != null } }
        if (first.isEmpty()) return
        val got = arrayOfNulls<Net.ArtTiming>(first.size)
        var left = first.size
        for ((k, a) in first.withIndex()) a.onShown = { t -> got[k] = t; if (--left == 0) ready(got.toList()) }
    }

    private fun bindNear() {
        if (!artOn || cards.isEmpty()) return
        val unitW = (units.firstOrNull()?.width ?: 0).takeIf { it > 0 } ?: CinemaStyle.LAND_W.dp()
        val visible = kit.screenW / (unitW + CinemaStyle.CARD_GAP.dp()) + 2
        val from = maxOf(0, index - visible)
        val to = minOf(cards.size - 1, index + visible)
        for (j in from..to) cards[j].artOrNull?.let { a ->
            a.owner = this
            a.slot = j
            a.bind(rankFor(j))
        }
    }

    private fun Int.dp() = Ui.dp(ctx, this)

    private companion object {
        /** Pixels a card may sit past a margin and still count as inside (rounding). */
        const val SLACK = 3
        const val ARROW_DP = 22
        const val ARROW_ALPHA = 0.75f
    }
}

/** TMDB title logos (the clean wordmark art the hero shows instead of plain text), looked up once per title. */
object CinemaLogos {
    private val cache = android.util.LruCache<String, String>(300)

    /** A logo url already known for [t] (null: none, or not looked up yet). */
    fun peek(t: Title): String? = cache.get(t.key)?.ifEmpty { null }

    /** Looks up [t]'s English (or language-free) PNG logo, w500; [done] gets null when it has none. Main thread. */
    fun get(ctx: Context, t: Title, done: (String?) -> Unit) {
        cache.get(t.key)?.let { done(it.ifEmpty { null }); return }
        if (!Tmdb.hasKey(ctx)) { done(null); return }
        Net.background({
            val j = Net.getJson("https://api.themoviedb.org/3/${t.type}/${t.id}/images?api_key=${Tmdb.key(ctx)}&include_image_language=en,null")
            val a = j.optJSONArray("logos")
            var best: String? = null
            if (a != null) for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val p = o.optString("file_path")
                // SVG logos can't be decoded here.
                if (!p.endsWith(".png", ignoreCase = true)) continue
                if (o.optString("iso_639_1") == "en") { best = p; break }
                if (best == null) best = p
            }
            best?.let { "${Tmdb.IMG}w500$it" } ?: ""
        }) { url ->
            if (url != null) cache.put(t.key, url)
            done(url?.ifEmpty { null })
        }
    }
}

/** The heroes' baked backdrops ([CinemaHero.bake], ~2.5 MB each): the slide showing, the one before and the next. */
object HeroArt {
    val baked = object : android.util.LruCache<String, Bitmap>(10 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Home hidden a while: the views keep the art they show, the rest is baked again when needed. */
    fun evict() = baked.evictAll()
}

/**
 * The hero carousel (Netflix and Disney+): full-bleed backdrop, the title's logo (or its name, big), a
 * meta line, a two-line synopsis, Play and More Info, and page dots. One title at a time; Left on Play
 * and Right past More Info step through them, and it advances by itself ([CinemaStyle.HERO_ADVANCE_MS])
 * only while focus rests on it. Art crossfades with a slow settle; the text fades over sideways in the
 * direction you moved. The buttons never move or fade (focus stays steady on them).
 */
class CinemaHero(
    private val kit: CinemaKit,
    private val host: HomeShell.Host,
    heightDp: Int,
    private val compact: Boolean,
) : FrameLayout(kit.ctx), CinemaSection {
    private val ctx = kit.ctx
    private val handler = Handler(Looper.getMainLooper())
    private val hPx = Ui.dp(ctx, heightDp)
    private val backA = FitImage(ctx, crop = true, bias = ART_BIAS).apply { alpha = 0f }
    private val backB = FitImage(ctx, crop = true, bias = ART_BIAS).apply { alpha = 0f }
    private var front = backA
    private var artUrl: String? = null
    /** The art's own clip (the hero's rectangle): what an opaque backdrop covers ([opaqueArea]). */
    private val artFrame = FrameLayout(ctx)
    /**
     * Readability: the art melts into the page toward the text (left), the rows (bottom) and the bar (top).
     * Baked into each backdrop once ([bake]), so the hero is one opaque layer a frame instead of three
     * (window gradient, art, a full-screen blended scrim). The scrim view only shows over art that couldn't
     * be baked (out of memory), never over the bare page (there it would only repaint the page).
     */
    private val scrimAlpha: (Float, Float) -> Float = { u, v ->
        val left = 0.93f * (1f - CinemaKit.ease((u - 0.08f) / 0.62f))
        val bottom = CinemaKit.ease((v - 0.42f) / 0.58f)
        val top = 0.55f * (1f - CinemaKit.ease(v / 0.30f))
        1f - (1f - left) * (1f - bottom) * (1f - top)
    }
    /** The scrim as a picture: made only when it's needed (art that couldn't be baked, or a photo wallpaper's bake). */
    private val scrimDrawable by lazy { kit.bgFade(kit.screenW, hPx, 0, if (compact) "hero-c" else "hero", scrimAlpha) }
    private val scrim = View(ctx).apply { visibility = View.GONE }
    /** The art views now holding a baked backdrop (the scrim is in their pixels). */
    private val baked = HashSet<View>(2)
    private val infoW = Ui.dp(ctx, if (compact) 500 else 520)
    private val kickerView = kit.kicker("")
    private val logoBox = Ui.dp(ctx, if (compact) 64 else 84)
    private val logo = FitImage(ctx, crop = false).apply { alpha = 0f }
    private val bigTitle = kit.label("", if (compact) 32f else CinemaStyle.T_HERO_TITLE, kit.text, kit.bold, lines = 2).apply {
        gravity = Gravity.BOTTOM
        letterSpacing = -0.015f
        setLineSpacing(0f, 0.92f)
        alpha = 0f
    }
    private val metaView = kit.label("", CinemaStyle.T_META + 1f, Ui.alpha(kit.text, 0.86f), kit.medium)
    /** After the meta line: the tomato with its coloured Tomatometer (when known) and Netflix-style stars. */
    private val heroScores = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun setScores(t: Title) {
        heroScores.removeAllViews()
        val r = Ratings.peek(ctx, t)
        if (r == null) Ratings.prefetch(ctx, t) // in memory by the next time this slide comes round
        val sp = CinemaStyle.T_META + 1f
        val px = (sp * 1.15f * ctx.resources.displayMetrics.scaledDensity).toInt()
        val gap = Ui.dp(ctx, 14)
        r?.tomatometer?.let { v ->
            heroScores.addView(kit.label("$v%", sp, Ratings.scoreColor(kit.text, v), kit.semibold).apply {
                setCompoundDrawables(TomatoGlyph().apply { setBounds(0, 0, px, px) }, null, null, null)
                compoundDrawablePadding = Ui.dp(ctx, 6)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = gap })
        }
        Ratings.audienceFor(r, t)?.let { a ->
            heroScores.addView(Ratings.audienceBadge(ctx, a, sp, kit.text, kit.bold, kit.semibold),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = gap })
        }
    }
    /**
     * Two lines that always start at the first word: the whole sentences that fit ([fitSentences]), else the first
     * one cut with an ellipsis. It never scrolls: a glide that held at its last line read as a synopsis starting
     * mid-sentence ("lessons he's learned from nearly two decades..."; owner, 2026-10-28). The title page has it all.
     */
    private val synopsisW = Ui.dp(ctx, 470)
    private val synopsis = kit.label("", CinemaStyle.T_SYNOPSIS, Ui.alpha(kit.text, 0.80f), kit.font, lines = 2).apply {
        setLineSpacing(0f, 1.18f)
        hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
    }

    /**
     * The meta line that fits beside the scores in [infoW]: two genres, then one, then none, so the tomato and the
     * IMDb badge are never squeezed or cut (iPhone MetaLine, 2026-10-28).
     */
    private fun fitMeta(t: Title): String {
        heroScores.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val room = infoW - heroScores.measuredWidth - metaView.compoundPaddingLeft - metaView.compoundPaddingRight
        for (g in 2 downTo 1) kit.meta(t, g).let { if (metaView.paint.measureText(it) <= room) return it }
        return kit.meta(t, 0)
    }

    /** [t]'s leading whole sentences that fit [synopsis]'s two lines; [t] itself when it fits or no sentence does. */
    private fun fitSentences(t: String): String {
        val text = t.trim()
        if (text.isEmpty()) return text
        val w = synopsisW - synopsis.compoundPaddingLeft - synopsis.compoundPaddingRight
        fun lines(s: String) = android.text.StaticLayout.Builder.obtain(s, 0, s.length, synopsis.paint, w)
            .setLineSpacing(synopsis.lineSpacingExtra, synopsis.lineSpacingMultiplier)
            .setIncludePad(false).setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE)
            .build().lineCount
        if (lines(text) <= 2) return text
        val ends = Regex("[.!?…][\"”’)]*(?=\\s)").findAll(text).map { it.range.last + 1 }.toList()
        var best: String? = null
        for (e in ends) {
            val s = text.substring(0, e)
            if (lines(s) <= 2) best = s else break
        }
        return best ?: text
    }
    private val info = FlatLinear(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    val buttons = FlatLinear(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
    private var play: View? = null
    private var more: View? = null
    private val dotRow = FlatLinear(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val dotMark = View(ctx).apply { alpha = 0f }
    private val dotMarkFill = GradientDrawable().apply { cornerRadius = Ui.dpf(ctx, 3f) }
    // Clips each child to its own bounds (the dots and the capsule never draw past them): the capsule's hue step
    // then redraws only the capsule's own rectangle, not the screen (see [damageBox]).
    private val dots = FlatFrame(ctx).apply { clipChildren = true; clipToPadding = false }

    private var titles: List<Title> = emptyList()
    private var kickerFor: (Title) -> String = { "" }
    var index = 0
        private set
    private var logoWait: Runnable? = null
    /** Off screen (page hidden, app paused): no rotation, no art loads. */
    var active = false
        set(v) {
            field = v
            if (v) { schedule(); drift() } else { handler.removeCallbacks(rotate); driftAt = 0L }
        }

    private val rotate = object : Runnable {
        override fun run() {
            if (active && titles.size > 1 && buttons.hasFocus() && host.resumed() && host.idle()) step(1) else schedule()
        }
    }

    init {
        clipChildren = false
        clipToPadding = false
        // The art in its own layer of the tree: the crossfade raises the incoming image with translationZ.
        // It clips itself (a rectangular outline): the drifting, slightly scaled art must never spill past
        // the hero, and the hero itself can't clip (the buttons' glow reaches past their row).
        // Held in a clipping frame ([damageBox]) so the drift's redraw is the hero's rectangle, not the screen.
        addView(damageBox(artFrame.apply {
            outlineProvider = android.view.ViewOutlineProvider.BOUNDS
            clipToOutline = true
            addView(backA, MATCH_PARENT, MATCH_PARENT)
            addView(backB, MATCH_PARENT, MATCH_PARENT)
        }, 0), MATCH_PARENT, MATCH_PARENT)
        addView(scrim, MATCH_PARENT, MATCH_PARENT)
        info.addView(kickerView, LinearLayout.LayoutParams(infoW, WRAP_CONTENT).apply { bottomMargin = Ui.dp(ctx, 8) })
        info.addView(FrameLayout(ctx).apply {
            addView(bigTitle, FrameLayout.LayoutParams(infoW, logoBox + Ui.dp(ctx, 8), Gravity.BOTTOM))
            addView(logo, FrameLayout.LayoutParams(Ui.dp(ctx, 360), logoBox, Gravity.BOTTOM))
        }, LinearLayout.LayoutParams(infoW, logoBox + Ui.dp(ctx, 8)))
        // The meta line, then the tomato and the stars (fixed height, so the line never moves while scores land).
        info.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(metaView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(heroScores, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }, LinearLayout.LayoutParams(infoW, Ui.dp(ctx, 22)).apply { topMargin = Ui.dp(ctx, 12) })
        info.addView(synopsis, LinearLayout.LayoutParams(synopsisW, Ui.dp(ctx, 42)).apply { topMargin = Ui.dp(ctx, 8) })
        val playable = host.canPlay()
        // A plain "Play"; hold OK to pick the episode.
        if (playable) play = kit.button("Play", R.drawable.ic_play, primary = true) { current()?.let { host.watch(it, false) } }.apply {
            setOnLongClickListener { current()?.let { host.watch(it, true) }; true }
        }
        else play = kit.button("Trailer", R.drawable.ic_play, primary = true) { current()?.let { host.playTrailerFor(it) } }
        more = kit.button("More Info", R.drawable.ic_info, primary = false) { current()?.let { host.openDetails(it, null) } }
        buttons.addView(play)
        buttons.addView(more)
        (more!!.layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        val column = FlatLinear(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(info)
            // Play steps on the shared clock (its fill and glow): bounded, so each step redraws only around the
            // buttons, glow and lift included ([BUTTONS_ROOM] past every edge).
            addView(damageBox(buttons, Ui.dp(ctx, BUTTONS_ROOM)), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 18) })
        }
        addView(column, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = kit.gutter
            bottomMargin = Ui.dp(ctx, if (compact) 30 else 38)
        })
        dotMark.background = dotMarkFill
        dots.addView(dotRow, FrameLayout.LayoutParams(WRAP_CONTENT, Ui.dp(ctx, 10)))
        dots.addView(dotMark, FrameLayout.LayoutParams(Ui.dp(ctx, DOT_MARK), Ui.dp(ctx, DOT), Gravity.CENTER_VERTICAL))
        addView(dots, FrameLayout.LayoutParams(WRAP_CONTENT, Ui.dp(ctx, 10), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = kit.gutter
            bottomMargin = Ui.dp(ctx, if (compact) 30 else 38) + (Ui.dp(ctx, CinemaKit.BUTTON_H) - Ui.dp(ctx, 10)) / 2
        })
        // The current dot wears the glow's hue (it follows the rainbow as focus moves anywhere).
        CinemaStyle.followGlow(dotMark) { v, c -> ((v.background) as? GradientDrawable)?.setColor(c) }
        // The drift's clock (see [drift]); the callback takes the hero as its argument (weak keys).
        if (DRIFT_ON) CinemaStyle.followGlow(this) { v, _ -> (v as CinemaHero).driftTick() }
    }

    override fun entry(): View? = play

    /**
     * [v] in a frame that clips its children, [room] px of padding around [v]'s content and the same negative
     * margin around [v] (so the frame lays out exactly as [v] alone did, and nothing that draws up to [room] past
     * [v]'s content is cut). Why: a view whose parent doesn't clip children reports unbounded damage (HWUI can't
     * know how far it draws), and with every Cinema container unclipped for the glow's halo, one step of Play's
     * fill used to dirty the whole window: each clock tick redrew the rows, captions and bottom fade under the hero
     * too. A clipped [v] bounds that damage to its own rectangle.
     */
    private fun damageBox(v: View, room: Int): View {
        v.setPadding(v.paddingLeft + room, v.paddingTop + room, v.paddingRight + room, v.paddingBottom + room)
        return FlatFrame(ctx).apply {
            clipChildren = true
            clipToPadding = false
            addView(v, FrameLayout.LayoutParams(if (room == 0) MATCH_PARENT else WRAP_CONTENT, if (room == 0) MATCH_PARENT else WRAP_CONTENT).apply { setMargins(-room, -room, -room, -room) })
        }
    }

    /** Always its full height (the art is full-bleed; the text sits at its bottom), whatever the content. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(hPx, MeasureSpec.EXACTLY))

    fun current(): Title? = titles.getOrNull(index)

    /** The titles to feature; [kicker] names each one ("Tonight", "Featured on Netflix"). Keeps the slide if it's still among them. */
    fun setTitles(list: List<Title>, kicker: (Title) -> String) {
        val keep = current()?.key
        titles = list.take(MAX)
        kickerFor = kicker
        index = titles.indexOfFirst { it.key == keep }.coerceAtLeast(0)
        buildDots()
        fill(animate = false)
        art(current()?.backdrop("w1280"))
        prefetchNext()
        schedule()
    }

    /** Focus moved somewhere (anywhere): the rest timer starts over. */
    fun focusMoved() = schedule()

    private fun schedule() {
        handler.removeCallbacks(rotate)
        if (active && titles.size > 1) handler.postDelayed(rotate, CinemaStyle.HERO_ADVANCE_MS)
    }

    /** Left on Play, Right past More Info: the previous / next title. */
    override fun focusSearch(focused: View?, direction: Int): View? {
        if (titles.size > 1 && focused != null) {
            if (direction == View.FOCUS_LEFT && focused === play) { step(-1); return focused }
            if (direction == View.FOCUS_RIGHT && focused === more) { step(1); return focused }
        }
        return super.focusSearch(focused, direction)
    }

    private fun step(by: Int) {
        if (titles.size < 2) return
        index = Math.floorMod(index + by, titles.size)
        fill(animate = true, dir = by)
        art(current()?.backdrop("w1280"))
        syncDots()
        prefetchNext()
        schedule()
        if (buttons.hasFocus()) host.speculate(current())
    }

    private fun fill(animate: Boolean, dir: Int = 1) {
        val t = current() ?: return
        if (!animate) { setText(t); return }
        val shift = Ui.dpf(ctx, 22f) * dir
        info.animate().cancel()
        info.animate().alpha(0f).translationX(-shift * 0.6f).setDuration(130).setInterpolator(Ui.EXIT).withEndAction {
            if (current() !== t) return@withEndAction
            setText(t)
            info.translationX = shift
            info.animate().alpha(1f).translationX(0f).setDuration(320).setInterpolator(Ui.EASE).start()
        }.start()
    }

    private fun setText(t: Title) {
        kickerView.text = kickerFor(t).uppercase()
        kickerView.visibility = if (kickerView.text.isEmpty()) View.INVISIBLE else View.VISIBLE
        setScores(t)
        metaView.text = fitMeta(t)
        synopsis.text = fitSentences(t.overview)
        bigTitle.text = t.name
        bigTitle.textSize = when {
            t.name.length > 30 -> if (compact) 24f else 28f
            t.name.length > 18 -> if (compact) 28f else 34f
            else -> if (compact) 32f else CinemaStyle.T_HERO_TITLE
        }
        // The logo, when TMDB has one: wait a moment for it before showing the plain title (no flash of text).
        logo.animate().cancel()
        bigTitle.animate().cancel()
        logo.alpha = 0f
        logo.setImageDrawable(null)
        logo.tag = null
        bigTitle.alpha = 0f
        logoWait?.let { handler.removeCallbacks(it) }
        logoWait = Runnable { if (current() === t && logo.drawable == null) bigTitle.animate().alpha(1f).setDuration(220).start() }.also { handler.postDelayed(it, LOGO_WAIT_MS) }
        CinemaLogos.get(ctx, t) { url ->
            if (current() !== t) return@get
            if (url == null) { logoWait?.let { handler.removeCallbacks(it); it.run() }; return@get }
            Net.image(ctx, logo, url) { _ ->
                if (current() !== t) return@image
                logoWait?.let { handler.removeCallbacks(it) }
                bigTitle.animate().cancel()
                bigTitle.alpha = 0f
                logo.animate().alpha(1f).setDuration(260).setInterpolator(Ui.EASE).start()
            }
        }
    }

    private fun art(url: String?) {
        artUrl = url
        if (url == null) return
        Net.bitmap(ctx, url) { raw ->
            if (raw == null || url != artUrl) return@bitmap
            bake(url, raw) { bmp, isBaked -> if (url == artUrl) showArt(bmp, isBaked) }
        }
    }

    /**
     * [raw] (the w1280 backdrop) cropped exactly as the hero shows it, with the scrim drawn into it once: an
     * opaque bitmap at the backdrop's own resolution (~1280x490, 2.5 MB), the melt into the page computed
     * per pixel against the page's exact gradient and dithered ([SmoothGradient.meltInto]: no bands, no blocks). Made on a worker, kept in a small cache (Left/Right back and forth bakes nothing)
     * and handed over a frame after its upload started, as [Net.bitmap] does. [done] gets the raw art and
     * false when baking fails (the scrim view then covers it).
     */
    private fun bake(url: String, raw: Bitmap, done: (Bitmap, Boolean) -> Unit) {
        val spec = SmoothGradient.spec(ctx)
        // A photo wallpaper has no gradient to compute: the scrim picture (sampled from the photo) goes on instead.
        val s = if (spec == null) (scrimDrawable as? android.graphics.drawable.BitmapDrawable)?.bitmap else null
        if ((spec == null && s == null) || raw.width < 2 || raw.height < 2) { done(raw, false); return }
        val key = url + "|" + hPx + "|" + (spec?.key ?: System.identityHashCode(s))
        HeroArt.baked.get(key)?.let { done(it, true); return }
        val w = kit.screenW
        val h = hPx
        Net.background({
            try {
                val rw = raw.width.toFloat()
                val rh = raw.height.toFloat()
                val fit = maxOf(w / rw, h / rh)
                // The backdrop's own pixels where the hero shows it (never upscaled here: the GPU's filter does that).
                val bw = minOf(w, Math.round(w / fit)).coerceAtLeast(2)
                val bh = Math.round(bw * h / w.toFloat()).coerceAtLeast(2)
                val out = Bitmap.createBitmap(bw, bh, if (spec != null) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565)
                val c = Canvas(out)
                c.scale(bw / w.toFloat(), bh / h.toFloat())
                val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
                // FitImage's crop: cover, the overflow trimmed [ART_BIAS] from the top.
                val m = Matrix().apply { setScale(fit, fit); postTranslate((w - rw * fit) / 2f, -(rh * fit - h) * ART_BIAS) }
                c.drawBitmap(raw, m, p)
                if (spec != null) { SmoothGradient.meltInto(out, spec, w, h, scrimAlpha); out.setHasAlpha(false) }
                else c.drawBitmap(s!!, null, android.graphics.Rect(0, 0, w, h), p)
                out
            } catch (e: Throwable) {
                Log.w("FireHome", "hero: backdrop not baked: $e")
                null
            }
        }) { out ->
            if (out == null) { done(raw, false); return@background }
            HeroArt.baked.put(key, out)
            out.prepareToDraw()
            android.view.Choreographer.getInstance().postFrameCallback { handler.post { done(out, true) } }
        }
    }

    /** The crossfade: the incoming art over the outgoing (two layers only while it runs, then one). */
    private fun showArt(bmp: Bitmap, isBaked: Boolean) {
        val incoming = if (front === backA) backB else backA
        val outgoing = front
        incoming.animate().cancel()
        outgoing.animate().cancel()
        // A fade cut short: the art that was arriving is now the opaque base.
        if (outgoing.drawable != null) outgoing.alpha = 1f
        incoming.setImageBitmap(bmp)
        if (isBaked) baked += incoming else baked -= incoming
        updateScrim()
        incoming.translationZ = 1f
        outgoing.translationZ = 0f
        incoming.alpha = 0f
        incoming.scaleX = 1f
        incoming.scaleY = 1f
        incoming.translationX = 0f
        incoming.animate().alpha(1f).setDuration(CinemaStyle.BACKDROP_FADE_MS * 2).setInterpolator(Ui.EASE).withEndAction {
            if (front === incoming) {
                outgoing.alpha = 0f
                outgoing.setImageDrawable(null) // its texture goes
                baked -= outgoing
                updateScrim()
            }
        }.start()
        front = incoming
        drift()
    }

    /** The scrim view shows only over art that isn't baked (see [scrim]). */
    private fun updateScrim() {
        val need = (backA.drawable != null && backA !in baked) || (backB.drawable != null && backB !in baked)
        val v = if (need) View.VISIBLE else View.GONE
        if (need && scrim.background == null) scrim.background = scrimDrawable
        if (scrim.visibility != v) scrim.visibility = v
    }

    /**
     * The art's clip when an opaque (baked) backdrop fills all of it at full opacity, else null: the shell
     * then leaves the window's background out there (none of it could show through). During a crossfade the
     * outgoing art is still fully opaque under the incoming one, and the drift only ever scales the art up
     * (moving it less than it grows), so the art always covers its clip.
     */
    fun opaqueArea(): View? {
        if (!isShown || artFrame.alpha < 1f || artFrame.visibility != View.VISIBLE) return null
        val full = (backA.drawable != null && backA in baked && backA.alpha >= 1f && backA.visibility == View.VISIBLE) ||
            (backB.drawable != null && backB in baked && backB.alpha >= 1f && backB.visibility == View.VISIBLE)
        return if (full) artFrame else null
    }

    /** The art view the drift moves, how far along it is (0..1 of [DRIFT_MS]), and its last step (0: resting). */
    private var driftView: View? = null
    private var driftT = 0f
    private var driftAt = 0L
    /** False while the hero is scrolled away or covered: no drift (it would redraw for nobody). */
    var drifting = true
        set(v) { if (field == v) return; field = v; if (v) drift() else driftAt = 0L }

    /**
     * Apple TV's Ken Burns: the art drifts very slowly (a touch larger, a little to the left) for as long as a
     * slide shows. It steps on the shared glow clock (every 50 ms: the hero follows it, [driftTick]), not on
     * every vsync: a step moves the art a fifth of a pixel at most, invisible as steps under the GPU's
     * filtering, and it lands in the frames the glowing focus draws anyway, so the drift adds no frames of
     * its own (a vsync-driven animation redrew the whole hero 60 times a second). Nothing is laid out.
     */
    private fun drift() {
        if (!DRIFT_ON || !drifting || !active || front.drawable == null) return
        if (driftView !== front) { driftView = front; driftT = 0f } // a new slide's art starts at rest (showArt)
        driftAt = 0L // counts from the next tick
    }

    private fun driftTick() {
        val v = driftView
        if (!DRIFT_ON || !drifting || !active || v == null || v !== front || v.drawable == null) { driftAt = 0L; return }
        val now = android.os.SystemClock.uptimeMillis()
        val last = driftAt
        driftAt = now
        if (last == 0L || driftT >= 1f) return
        driftT = (driftT + (now - last).coerceAtMost(200L) / DRIFT_MS.toFloat()).coerceAtMost(1f)
        // Scale about the centre grows each side by more than the shift: the art always covers its clip.
        val sc = 1f + (DRIFT_SCALE - 1f) * driftT
        v.scaleX = sc
        v.scaleY = sc
        v.translationX = -Ui.dpf(ctx, DRIFT_DX) * driftT
    }

    /** The next title's backdrop and logo come into the caches while this one shows. */
    private fun prefetchNext() {
        if (titles.size < 2) return
        val n = titles[(index + 1) % titles.size]
        // Baked ahead too, so the next crossfade starts the moment it's asked for.
        handler.postDelayed({
            if (active) {
                n.backdrop("w1280")?.let { u -> Net.bitmap(ctx, u) { b -> if (b != null && active) bake(u, b) { _, _ -> } } }
                CinemaLogos.get(ctx, n) {}
            }
        }, 1500)
    }

    private fun buildDots() {
        dotRow.removeAllViews()
        if (titles.size < 2) { dots.visibility = View.GONE; return }
        for (i in titles.indices) dotRow.addView(View(ctx).apply {
            background = Ui.rounded(ctx, Ui.alpha(kit.text, 0.34f), Ui.R_PILL)
        }, LinearLayout.LayoutParams(Ui.dp(ctx, DOT), Ui.dp(ctx, DOT)).apply { if (i > 0) marginStart = Ui.dp(ctx, DOT_GAP) })
        dots.visibility = View.VISIBLE
        dotMark.alpha = 0f
        dots.post { syncDots() }
    }

    /** The hue capsule glides over the current dot (translation only). */
    private fun syncDots() {
        val d = dotRow.getChildAt(index) ?: return
        if (dotRow.width == 0) return
        val x = d.left + (d.width - dotMark.width) / 2f
        if (dotMark.alpha < 0.05f) { dotMark.translationX = x; dotMark.animate().alpha(1f).setDuration(240).start() }
        else dotMark.animate().translationX(x).setDuration(320).setInterpolator(Ui.EASE).start()
    }

    /** Off screen for a while: the art lets go (the hero puts it back when shown). */
    fun releaseArt() {
        for (b in listOf(backA, backB)) { b.animate().cancel(); b.setImageDrawable(null); b.alpha = 0f }
        baked.clear()
        updateScrim()
        driftView = null
        driftT = 0f
        driftAt = 0L
        artUrl = null
    }

    fun restoreArt() {
        if (front.drawable == null) art(current()?.backdrop("w1280"))
    }

    fun release() {
        active = false
        CinemaStyle.unfollowGlow(this)
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        /**
         * The Ken Burns drift (one-line switch). It was off: a drifting hero once redrew at ~30 ms a frame (about
         * 11 fps) and stuttered. Since then the hero is one opaque layer (the scrim baked into the art, the window
         * background left out under it), the drift steps on the shared 50 ms clock (no frames of its own), and
         * every clock step's redraw is bounded ([damageBox]): a drift step redraws the hero's rectangle, which the
         * idle clock step (bar, Play, dots) already spans, so the drift costs no extra frames and almost no extra
         * pixels. Set back to false if gfxinfo on an idle Home shows a 90th percentile above 12 ms
         * (docs/backlog.md has the steps).
         */
        const val DRIFT_ON = true
        /** FitImage's crop for the backdrops, which [bake] matches (the overflow trimmed 22 % from the top). */
        const val ART_BIAS = 0.22f
        const val DRIFT_MS = 24_000L
        const val DRIFT_SCALE = 1.07f
        const val DRIFT_DX = 22f
        const val MAX = 6
        /** Room around the hero buttons inside their clip (dp): the small glow's 12 dp halo plus the 1.06 focus lift. */
        const val BUTTONS_ROOM = 20
        const val DOT = 6
        const val DOT_GAP = 10
        const val DOT_MARK = 18
        const val LOGO_WAIT_MS = 420L
    }
}

/**
 * A frame whose child may be wider than the screen (a row of cards, a line of chips): the child is
 * measured at its natural width instead of being squeezed to the frame, then moved by translationX.
 */
open class FreeTrack(ctx: Context) : FrameLayout(ctx) {
    init { clipChildren = false; clipToPadding = false }
    override fun hasOverlappingRendering() = false
    override fun measureChildWithMargins(child: View, parentWidthMeasureSpec: Int, widthUsed: Int, parentHeightMeasureSpec: Int, heightUsed: Int) {
        val lp = child.layoutParams as MarginLayoutParams
        child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            getChildMeasureSpec(parentHeightMeasureSpec, paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin + heightUsed, lp.height))
    }
}

/**
 * Hulu's chip row: one category current at a time ([select]), starting on the page's left line. A line
 * longer than the screen slides (translationX) to keep the focused chip between the gutters; Left and
 * Right stay in the line.
 */
class CinemaChips(private val kit: CinemaKit, labels: List<String>, private val onPick: (Int) -> Unit) : FreeTrack(kit.ctx), CinemaSection {
    private val chips = ArrayList<TextView>()
    private val line = FlatLinear(kit.ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        gravity = Gravity.CENTER_VERTICAL
        setPadding(kit.gutter, 0, kit.gutter, 0)
    }
    var current = 0
        private set

    init {
        addView(line, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        for ((i, l) in labels.withIndex()) {
            val c = kit.chip(l) { select(i, now = true) }
            val base = c.onFocusChangeListener
            c.setOnFocusChangeListener { v, has -> base?.onFocusChange(v, has); if (has) keepInView(v) }
            chips += c
            line.addView(c)
        }
        (chips.lastOrNull()?.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0
        chips.firstOrNull()?.isSelected = true
    }

    override fun focusSearch(focused: View?, direction: Int): View? {
        if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) {
            val i = chips.indexOf(focused)
            if (i >= 0) return chips.getOrNull(i + if (direction == View.FOCUS_RIGHT) 1 else -1) ?: focused
        }
        return super.focusSearch(focused, direction)
    }

    /** Where the line is gliding to; the fades follow it. */
    private var target = 0f
    private val faded = java.util.WeakHashMap<View, Float>()

    private fun keepInView(v: View) {
        val w = if (width > 0) width else kit.screenW
        var t = target
        val right = v.right + t
        val left = v.left + t
        if (right > w - kit.gutter) t -= right - (w - kit.gutter)
        if (left < kit.gutter) t += kit.gutter - left
        t = t.coerceIn(minOf(0f, (w - line.width).toFloat()), 0f)
        target = t
        if (t != line.translationX) line.animate().translationX(t).setDuration(CinemaStyle.ROW_SCROLL_MS).setInterpolator(Ui.EASE).start()
        settle(animate = true)
    }

    /** Chips not wholly between the margins fade out (never the focused one): nothing is cut by the screen's edge. */
    private fun settle(animate: Boolean) {
        if (line.width == 0) return
        val w = if (width > 0) width else kit.screenW
        for (c in chips) {
            val x = c.left + target
            val a = if (c.isFocused || (x >= kit.gutter - 3 && x + c.width <= w - kit.gutter + 3)) 1f else 0f
            if (faded[c] == a) continue
            faded[c] = a
            c.animate().alpha(a).setDuration(if (animate) 180L else 0L).setInterpolator(Ui.EASE).start()
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        settle(animate = false)
    }

    override fun entry(): View? = chips.getOrNull(current)

    fun indexOf(v: View) = chips.indexOf(v)

    fun chip(i: Int): View? = chips.getOrNull(i)

    /** Marks [i] current without acting on it (the starting state). */
    fun mark(i: Int) {
        chips.getOrNull(current)?.isSelected = false
        current = i.coerceIn(0, maxOf(0, chips.size - 1))
        chips.getOrNull(current)?.isSelected = true
    }

    fun select(i: Int, now: Boolean) {
        if (i == current && !now) return
        val changed = i != current
        chips.getOrNull(current)?.isSelected = false
        current = i
        chips.getOrNull(i)?.isSelected = true
        if (changed) onPick(i)
    }
}
