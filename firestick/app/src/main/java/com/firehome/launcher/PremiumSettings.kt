package com.firehome.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Settings (the name is historical: it began as Dream Premium's): a full-screen page over a frosted snapshot of the home screen.
 *
 * Left: the page title, the profile and the categories, with one highlight that glides between
 * them (the focus glow while choosing, a quiet glass pill marking the open category otherwise).
 * Right: the open category's options on a twilight glass card. Resting on a category swaps the
 * card (debounced, short cross-fade and slide); Right enters the options, Left or Back returns,
 * Back on the categories closes the page.
 *
 * Light by design: one frost bitmap per open, no per-frame blur, every animation ends.
 */
class PremiumSettings(
    private val ctx: Context,
    private val window: android.view.Window,
    private val header: Header,
    private val sections: List<Section>,
    private val host: Host,
) {
    class Header(val name: String, val emoji: String, val avatarColor: Int)

    /**
     * [toggle] non-null = shows a switch in that state; OK runs [action] (the caller flips the setting and may call refresh).
     * [icon] is a drawable resource (tinted to the row), or a [LineIcon] / [SettingsGlyph] shape constant.
     * [caption]: a short second line under the label. [tone]: the value's color at rest (amber, red); ink when focused.
     * [wrapCaption]: the caption may take two lines (a sentence that must be read whole, like a legal credit).
     */
    class Item(val label: String, val value: String? = null, val icon: Int? = null, val toggle: Boolean? = null,
               val caption: String? = null, val tone: Int? = null, val wrapCaption: Boolean = false, val action: () -> Unit)

    /** [icon] is a LineIcon or SettingsGlyph shape constant. [badge]: a small rainbow dot after the title (Updates: a new version waits). */
    class Section(val title: String, val icon: Int, val items: List<Item>, val badge: Boolean = false)

    /** Back on the category list (or an item that navigates away) closes the page. */
    interface Host { fun close() }

    private val k = PremiumKit(ctx)
    private val Int.dp get() = Ui.dp(ctx, this)

    private var data: List<Section> = sections
    /** Category the card belongs to (marked on the left), and the one whose rows are actually built. */
    private var shown = 0
    private var filled = -1
    private var pending = 0
    private var gen = 0
    private var outRunning = false
    private var focusedCat = -1
    private var released = false
    private var closing = false
    private var lastTag: String? = null
    /** Last focused row per category title, so Right returns to where the viewer was. */
    private val lastRow = HashMap<String, String>()

    private val root = Root()
    /** Full-screen; MainActivity adds it to the decor view. */
    val view: View = root

    private val backdrop = FrameLayout(ctx)
    private val frostView = ImageView(ctx)
    private val veil = View(ctx)
    private var frost: Bitmap? = null

    private val content = LinearLayout(ctx)
    private val headerViews = ArrayList<View>()
    private val catScroll = SmoothScrollView(ctx)
    private val catFrame = FrameLayout(ctx)
    private val catList = LinearLayout(ctx)
    private val catRows = ArrayList<TextView>()
    private val catIcons = ArrayList<Drawable>()
    private val hl = FrameLayout(ctx)
    private val hlGlow = View(ctx)
    /** Cinema: the rainbow border around the pearl highlight (same radius; it glides with it). */
    private val hlRing: CinemaGlow? = CinemaStyle.glowDrawable(ctx, radiusDp = Ui.R_MD, haloDp = 10)

    private val card = FrameLayout(ctx)
    private val pane = LinearLayout(ctx)
    private val titleRow = LinearLayout(ctx)
    private val titleIcon = ImageView(ctx)
    private val titleText = k.label("", Ui.T_HEADLINE, k.text, k.semibold, Ui.TRACK_TITLE)
    private val itemScroll = SmoothScrollView(ctx)
    private val list = LinearLayout(ctx)
    private val rows = ArrayList<Row>()

    private val swapRunnable = Runnable { show(pending, animate = true) }

    // Declared before init (which builds the first card): Kotlin runs initializers in order.
    /** Each section's rows, built once (keyed by the section object: a refresh brings new ones and clears this). */
    private val built = HashMap<Section, List<Row>>()
    /** When focus last moved: rows are only built ahead once the viewer has paused. */
    private var lastMove = 0L
    /** Per-row fade of the categories at the list's edges (0 = gone, 1 = full), folded into their ink. */
    private var catFade = FloatArray(0)

    private class Row(var item: Item, val view: LinearLayout, val label: TextView, val value: TextView,
                      val icon: Drawable?, val sw: DreamSwitch?, var on: Boolean, val caption: TextView)

    init {
        root.isClickable = true // nothing under the page takes touches/clicks
        buildBackdrop()
        buildContent()
        buildCategories()
        if (data.isNotEmpty()) fill(0)
        // Snapshot now, before the page draws: the frost is the home screen as it was.
        PremiumKit.frost(window) { b ->
            if (released) { b?.recycle(); return@frost }
            if (b == null) {
                veil.background = veilDrawable(0.94f, 0.97f)
                return@frost
            }
            frost = b
            frostView.setImageBitmap(b)
            frostView.alpha = 0f
            frostView.animate().alpha(1f).setDuration(Ui.FADE_IMAGE).setInterpolator(Ui.EASE).start()
        }
    }

    // ---- build ----

    private fun veilDrawable(top: Float, bottom: Float) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Ui.alpha(k.bgTop, top), Ui.alpha(Ui.mix(k.bgTop, k.bgBottom, 0.6f), bottom)))

    private fun buildBackdrop() {
        frostView.scaleType = ImageView.ScaleType.FIT_XY
        backdrop.addView(frostView, MATCH_PARENT, MATCH_PARENT)
        // Twilight veil: the frost stays a hint of color and shape, the text always reads.
        veil.background = veilDrawable(0.62f, 0.76f)
        backdrop.addView(veil, MATCH_PARENT, MATCH_PARENT)
        // Dream light: one soft wash of the highlight behind the title (static, partial area).
        backdrop.addView(View(ctx).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = 300.dp.toFloat()
                colors = intArrayOf(Ui.alpha(Ui.mix(k.select, k.blush, 0.35f), 0.16f), Color.TRANSPARENT)
            }
        }, FrameLayout.LayoutParams(600.dp, 600.dp).apply { leftMargin = (-230).dp; topMargin = (-260).dp })
        root.addView(backdrop, MATCH_PARENT, MATCH_PARENT)
    }

    private fun buildContent() {
        content.orientation = LinearLayout.HORIZONTAL
        content.setPadding(PremiumKit.EDGE.dp, 40.dp, 44.dp, 36.dp)
        content.clipChildren = false
        content.clipToPadding = false

        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val kick = k.kicker("Dream OS")
        val title = k.label("Settings", Ui.T_DISPLAY, k.text, k.semibold, Ui.TRACK_TITLE).apply { setPadding(0, 6.dp, 0, 0) }
        left.addView(kick)
        left.addView(title)
        headerViews += kick
        headerViews += title
        if (header.name.isNotBlank() || header.emoji.isNotBlank()) {
            val chip = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = k.glass(Ui.R_PILL, 70)
                setPadding(4.dp, 4.dp, 14.dp, 4.dp)
                addView(TextView(ctx).apply {
                    text = header.emoji
                    textSize = 14f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(header.avatarColor) }
                }, LinearLayout.LayoutParams(26.dp, 26.dp))
                addView(k.label(header.name, Ui.T_CAPTION, Ui.alpha(k.text, 0.9f), k.medium),
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 9.dp })
            }
            left.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 14.dp })
            headerViews += chip
        }

        // Categories: the gliding highlight sits behind the rows and scrolls with them.
        catList.orientation = LinearLayout.VERTICAL
        hl.addView(View(ctx).apply {
            background = Ui.rounded(ctx, Ui.alpha(k.text, 0.08f), Ui.R_MD, 1f, Ui.alpha(k.text, 0.10f))
        }, MATCH_PARENT, MATCH_PARENT)
        hl.addView(View(ctx).apply { background = Ui.focusFill(ctx, k.select, Ui.R_PILL) },
            FrameLayout.LayoutParams(3.dp, 18.dp, Gravity.CENTER_VERTICAL).apply { leftMargin = 6.dp })
        hlGlow.background = if (hlRing != null) Ui.rounded(ctx, PearlFocus.PEARL, Ui.R_MD) else Ui.focusFill(ctx, k.select, Ui.R_MD)
        hlGlow.alpha = 0f
        hl.addView(hlGlow, MATCH_PARENT, MATCH_PARENT)
        hl.alpha = 0f
        // Cinema: the glow is the highlight's own background (under the glass and the pearl fill, so the
        // fill covers the halo's inner band); the frame leaves room for the halo inside the scroller.
        hlRing?.let { hl.background = it }
        catFrame.addView(hl, FrameLayout.LayoutParams(MATCH_PARENT, CAT_H.dp))
        catFrame.addView(catList, MATCH_PARENT, WRAP_CONTENT)
        if (hlRing != null) {
            catFrame.setPadding(10.dp, 10.dp, 10.dp, 10.dp)
            catFrame.clipChildren = false
            catFrame.clipToPadding = false
        } else catFrame.setPadding(0, 0, 0, 8.dp)
        catScroll.isVerticalScrollBarEnabled = false
        catScroll.overScrollMode = View.OVER_SCROLL_NEVER
        catScroll.margin = 8.dp
        // The list scrolls inside its own box, always under the title and the profile chip (never over them).
        // Its edges soften by fading the rows that reach them ([fadeCats]): the frost behind has no one color
        // to paint a gradient in, and an offscreen fading edge costs this GPU a layer every frame.
        catScroll.clipViewport = true
        catScroll.onScrolled = { fadeCats() }
        catScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fadeCats() }
        catScroll.addView(catFrame, MATCH_PARENT, WRAP_CONTENT)
        left.addView(catScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = 26.dp })
        content.addView(left, LinearLayout.LayoutParams(LEFT_W.dp, MATCH_PARENT))

        // The card: section title with its glowing glyph, then the options.
        card.background = k.sheet(Ui.R_XL)
        card.setPadding(22.dp, 24.dp, 22.dp, 12.dp)
        pane.orientation = LinearLayout.VERTICAL
        pane.clipChildren = false
        // The section switch fades the card's content without an offscreen layer: each frame draws the rows
        // with the fade's alpha (their faint fills and ink barely overlap), instead of allocating and filling a
        // card-sized layer twice per switch, which this GPU paid for in long frames.
        pane.forceHasOverlappingRendering(false)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        titleRow.setPadding(8.dp, 0, 8.dp, 0)
        titleIcon.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(k.select, k.blush)).apply {
            shape = GradientDrawable.OVAL
        }
        titleIcon.setPadding(10.dp, 10.dp, 10.dp, 10.dp)
        titleRow.addView(titleIcon, LinearLayout.LayoutParams(40.dp, 40.dp))
        titleRow.addView(titleText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = 14.dp })
        pane.addView(titleRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        list.orientation = LinearLayout.VERTICAL
        // Cinema: room for the focused row's glow border inside the scroller (it clips at its own edges).
        list.setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        list.clipChildren = false
        list.clipToPadding = false
        itemScroll.isVerticalScrollBarEnabled = false
        itemScroll.overScrollMode = View.OVER_SCROLL_NEVER
        // A soft fade where the options run past the card's edge, never a hard cut through a row: painted in
        // the card's own color over the rows (no offscreen layer), and the rows stay under the card's title.
        itemScroll.clipViewport = true
        itemScroll.softEdges(cardInk(0.18f), cardInk(0.97f), 44.dp)
        itemScroll.margin = 14.dp
        itemScroll.clipChildren = false
        itemScroll.addView(list, MATCH_PARENT, WRAP_CONTENT)
        pane.addView(itemScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = 16.dp })
        card.addView(pane, MATCH_PARENT, MATCH_PARENT)
        content.addView(card, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = 30.dp })

        root.addView(content, MATCH_PARENT, MATCH_PARENT)
    }

    /** The card's surface color [t] of the way down (the sheet's gradient, opaque): what [itemScroll]'s edges fade into. */
    private fun cardInk(t: Float): Int = Ui.mix(Ui.mix(k.tile, k.text, 0.06f), Ui.mix(k.tile, k.bgTop, 0.4f), t) or (0xFF shl 24)

    /** Rows reaching the top or bottom edge of the scrolling category list fade out (a soft edge, no layer). */
    private fun fadeCats() {
        if (catFade.size != catRows.size) catFade = FloatArray(catRows.size) { 1f }
        val vh = catScroll.height
        val scrollable = vh > 0 && catFrame.height > vh
        val f = CAT_FADE.dp.toFloat()
        var changed = false
        val sy = catScroll.scrollY
        // Each edge fades only while there is more to scroll that way (like the soft edges of the card).
        val kTop = (sy / f).coerceIn(0f, 1f)
        val kBottom = ((catFrame.height - vh - sy) / f).coerceIn(0f, 1f)
        for ((i, r) in catRows.withIndex()) {
            var a = 1f
            if (scrollable) {
                val h = r.height.toFloat()
                val top = catList.top + r.top - sy.toFloat()
                // A row touching the edge shows at about half strength, one half cut off at about a quarter.
                val aTop = ((top + h) / (f + h)).coerceIn(0f, 1f)
                val aBottom = ((vh - top) / (f + h)).coerceIn(0f, 1f)
                a = minOf(1f - (1f - aTop) * kTop, 1f - (1f - aBottom) * kBottom)
            }
            a = a.coerceIn(0f, 1f)
            if (kotlin.math.abs(catFade[i] - a) > 0.01f) { catFade[i] = a; changed = true }
        }
        if (changed) styleCategories()
    }

    private fun buildCategories() {
        catList.removeAllViews()
        catRows.clear()
        catIcons.clear()
        for ((i, s) in data.withIndex()) {
            val icon = SettingsGlyph.of(s.icon).apply { setBounds(0, 0, 22.dp, 22.dp) }
            val row = TextView(ctx).apply {
                text = s.title
                textSize = 16f
                typeface = k.medium
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setCompoundDrawablesRelative(icon, null, if (s.badge) badgeDot() else null, null)
                compoundDrawablePadding = 14.dp
                setPadding(18.dp, 0, 14.dp, 0)
                tag = "cat:${s.title}"
                isFocusable = true
                isClickable = true
                setOnClickListener { show(i, animate = false); firstRowTarget()?.requestFocus() }
            }
            catList.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, CAT_H.dp).apply { bottomMargin = CAT_GAP.dp })
            catRows += row
            catIcons += icon
        }
        catFade = FloatArray(catRows.size) { 1f }
        styleCategories()
        catScroll.post { if (!released) fadeCats() }
    }

    /** The category's badge: a small disc in the brand's rainbow (like the bell's count), never tinted with the row. */
    private fun badgeDot(): Drawable = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Rainbow.COLORS[0], Rainbow.COLORS[1], Rainbow.COLORS[2])).apply {
        shape = GradientDrawable.OVAL
        setBounds(0, 0, 9.dp, 9.dp)
    }

    /** Category tints: ink on the glow, full pearl for the open one, the rest sit back. */
    private fun styleCategories() {
        for ((i, row) in catRows.withIndex()) {
            val base = when (i) {
                focusedCat -> if (hlRing != null) PearlFocus.INK else k.onSelect
                shown -> k.text
                else -> Ui.alpha(k.text, 0.62f)
            }
            val fade = catFade.getOrNull(i) ?: 1f
            val c = if (fade >= 1f || i == focusedCat) base
                else (base and 0x00FFFFFF) or ((Color.alpha(base) * fade).toInt() shl 24)
            if (row.currentTextColor != c) {
                row.setTextColor(c)
                catIcons[i].setTint(c)
                (row.compoundDrawablesRelative[2])?.alpha = if (i == focusedCat) 255 else (255 * fade).toInt()
            }
        }
    }

    /** Builds the card's title and rows for section [i] (no animation). */
    private fun fill(i: Int) {
        val s = data[i]
        filled = i
        titleText.text = s.title
        titleIcon.setImageDrawable(SettingsGlyph.of(s.icon).apply { setTint(k.onSelect) })
        list.removeAllViews()
        rows.clear()
        // Rows built ahead while the page rested ([prebuild]) go straight in: building a card's rows on the spot
        // stalled the UI thread for a few frames on every category switch.
        val made = built[s] ?: s.items.map { buildRow(s, it) }.also { built[s] = it }
        for (r in made) {
            val h = if (r.item.wrapCaption) WRAP_CONTENT else ROW_H.dp
            list.addView(r.view, LinearLayout.LayoutParams(MATCH_PARENT, h).apply { bottomMargin = ROW_GAP.dp })
            rows += r
        }
        if (s.items.isEmpty()) {
            list.addView(k.label("Nothing to set here yet.", Ui.T_BODY, k.dim, k.font).apply { setPadding(8.dp, 12.dp, 8.dp, 0) })
        }
        itemScroll.scrollTo(0, 0)
    }

    /** Builds one more section's rows while nothing moves, until every section has them. */
    private val prebuild = object : Runnable {
        override fun run() {
            if (released || closing) return
            val idle = android.os.SystemClock.uptimeMillis() - lastMove > PREBUILD_IDLE && !outRunning
            if (idle) {
                val s = data.firstOrNull { it.items.isNotEmpty() && it !in built } ?: return
                built[s] = s.items.map { buildRow(s, it) }.also { warm(it) }
            }
            root.postDelayed(this, PREBUILD_STEP)
        }
    }

    /**
     * Measures and lays out prebuilt rows at the card's width while the page rests, so a section switch only
     * attaches them: their text layouts already exist (a TextView keeps its layout when the width is the same),
     * and the switch's frames carry no text shaping.
     */
    private fun warm(made: List<Row>) {
        val w = list.width - list.paddingLeft - list.paddingRight
        if (w <= 0) return
        val ws = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
        for (r in made) {
            val hs = if (r.item.wrapCaption) View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                else View.MeasureSpec.makeMeasureSpec(ROW_H.dp, View.MeasureSpec.EXACTLY)
            r.view.measure(ws, hs)
            r.view.layout(0, 0, r.view.measuredWidth, r.view.measuredHeight)
        }
    }

    private fun itemIcon(res: Int): Drawable? =
        if (res in 0..199) SettingsGlyph.of(res) else try { ctx.getDrawable(res)?.mutate() } catch (e: Exception) { null }

    private fun buildRow(s: Section, item: Item): Row {
        val focused = intArrayOf(android.R.attr.state_focused)
        fun states(on: Int, rest: Int) = ColorStateList(arrayOf(focused, IntArray(0)), intArrayOf(on, rest))
        val v = FadeRow(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 0, 18.dp, 0)
            tag = rowTag(s, item)
            isFocusable = true
            isClickable = true
            background = StateListDrawable().apply {
                addState(focused, Ui.rounded(ctx, PearlFocus.PEARL, Ui.R_MD))
                addState(IntArray(0), Ui.rounded(ctx, Ui.alpha(k.text, 0.045f), Ui.R_MD))
                setEnterFadeDuration(Ui.FOCUS_IN.toInt())
                setExitFadeDuration(Ui.FOCUS_OUT.toInt())
            }
        }
        val iconRest = Ui.alpha(k.text, 0.8f)
        val icon = item.icon?.let { itemIcon(it) }?.apply { setTint(iconRest) }
        if (icon != null) {
            v.addView(ImageView(ctx).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(8.dp, 8.dp, 8.dp, 8.dp)
                isDuplicateParentStateEnabled = true
                background = StateListDrawable().apply {
                    addState(focused, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.alpha(k.onSelect, 0.14f)) })
                    addState(IntArray(0), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.alpha(k.text, 0.07f)) })
                }
            }, LinearLayout.LayoutParams(34.dp, 34.dp).apply { marginEnd = 14.dp })
        } else {
            v.setPadding(20.dp, 0, 18.dp, 0)
        }
        val ink = PearlFocus.INK
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true }
        val label = k.label(item.label, 16.5f, k.text, k.medium).apply {
            setTextColor(states(ink, Ui.alpha(k.text, 0.94f)))
            isDuplicateParentStateEnabled = true
        }
        texts.addView(label)
        val caption = k.label(item.caption ?: "", 12.5f, k.dim, k.font).apply {
            setTextColor(states(valueInk(), k.dim))
            isDuplicateParentStateEnabled = true
            setPadding(0, 4.dp, 0, 0)
            visibility = if (item.caption.isNullOrEmpty()) View.GONE else View.VISIBLE
            if (item.wrapCaption) maxLines = 2
        }
        texts.addView(caption)
        if (item.wrapCaption) {
            v.minimumHeight = ROW_H.dp
            v.setPadding(v.paddingLeft, 10.dp, v.paddingRight, 10.dp)
        }
        v.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val value = k.label(item.value ?: "", Ui.T_LABEL, k.dim, k.font).apply {
            setTextColor(states(valueInk(), item.tone ?: k.dim))
            isDuplicateParentStateEnabled = true
            maxWidth = 220.dp
            gravity = Gravity.END
            visibility = if (item.value.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        v.addView(value, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 12.dp })
        val sw = item.toggle?.let { DreamSwitch(ctx, it, k) }
        if (sw != null) {
            v.addView(sw, LinearLayout.LayoutParams(46.dp, 28.dp).apply { marginStart = 14.dp })
        } else {
            v.addView(TextView(ctx).apply {
                text = "›"
                textSize = 24f
                includeFontPadding = false
                setTextColor(states(Ui.alpha(k.onSelect, 0.75f), Ui.alpha(k.text, 0.32f)))
                isDuplicateParentStateEnabled = true
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 12.dp })
        }
        val row = Row(item, v, label, value, icon, sw, item.toggle ?: false, caption)
        // Dream Cinema: one rounded face, glass at rest and solid pearl focused, under the fading rainbow border.
        val cinemaFocus = CinemaRowFocus(v, Ui.R_MD, Ui.alpha(k.text, 0.045f))
        v.setOnFocusChangeListener { view, has ->
            cinemaFocus.focus(has)
            Ui.lift(view, has, Ui.LIFT_ROW)
            row.icon?.setTint(if (has) k.onSelect else iconRest)
            row.sw?.lit = has
        }
        v.setOnClickListener {
            // Switches answer at once; refresh() later corrects them if the setting didn't change.
            row.sw?.let { row.on = !row.on; it.set(row.on) }
            try {
                row.item.action()
            } catch (e: Exception) {
                Log.w(TAG, "Setting failed: ${row.item.label}", e)
            }
        }
        return row
    }

    private fun rowTag(s: Section, item: Item) = "row:${s.title}:${item.label}"

    /** A focused row's second-line ink (captions and values): Cinema's dim ink on pearl, else the highlight's. */
    private fun valueInk() = PearlFocus.INK_DIM

    // ---- motion ----

    /** Slides the highlight to category [i]. */
    private fun glide(i: Int, animate: Boolean) {
        val r = catRows.getOrNull(i) ?: return
        if (!r.isLaidOut) {
            r.post { if (!released) glide(i, false) }
            return
        }
        val y = r.top.toFloat()
        if (!animate || hl.alpha == 0f) {
            hl.animate().translationY(y).setStartDelay(0).setDuration(0).start()
            return
        }
        hl.animate().translationY(y).setStartDelay(0).setDuration(GLIDE).setInterpolator(Ui.EASE).start()
    }

    /** Highlight state: the focus glow while choosing a category, a quiet glass marker otherwise. */
    private fun glow(on: Boolean) {
        hlRing?.let { PearlFocus.focus(it, on) } // in on the shared clock; out at once, like the rows
        // Cinema: the pearl is opaque the moment the border shows (a fading fill would let the halo's inner band through).
        if (on && hlRing != null) { hlGlow.animate().cancel(); hlGlow.alpha = 1f; return }
        hlGlow.animate().alpha(if (on) 1f else 0f).setDuration(if (on) Ui.FOCUS_IN else Ui.FOCUS_OUT)
            .setInterpolator(Ui.SETTLE).start()
    }

    private fun requestSwap(i: Int) {
        pending = i
        root.removeCallbacks(swapRunnable)
        root.postDelayed(swapRunnable, SWAP_DELAY)
    }

    /** Makes section [i] the open one; the card cross-fades to it when [animate]. */
    private fun show(i: Int, animate: Boolean) {
        root.removeCallbacks(swapRunnable)
        if (released || i !in data.indices) return
        if (i != shown) {
            shown = i
            styleCategories()
        }
        if (filled == i) {
            if (outRunning) {
                outRunning = false
                gen++
                pane.animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(160).setInterpolator(Ui.SETTLE).start()
            }
            return
        }
        val g = ++gen
        pane.animate().cancel()
        if (!animate || pane.alpha < 0.05f) {
            outRunning = false
            fill(i)
            enterPane()
            return
        }
        outRunning = true
        pane.animate().alpha(0f).translationX((-10).dp.toFloat()).setStartDelay(0).setDuration(110)
            .setInterpolator(Ui.EXIT).withEndAction {
                if (g != gen || released) return@withEndAction
                outRunning = false
                fill(i)
                enterPane()
            }.start()
    }

    /**
     * The new card's content glides in from the right as one piece (no layer: see [pane]'s setup; its rows were
     * prebuilt and measured ahead, [warm]). Staggering every row separately redrew the whole card each frame.
     */
    private fun enterPane() {
        pane.alpha = 0f
        pane.translationX = 18.dp.toFloat()
        pane.animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(240).setInterpolator(Ui.EASE).start()
    }

    // ---- focus ----

    private fun firstRowTarget(): View? =
        lastRow[data.getOrNull(shown)?.title]?.let { list.findViewWithTag<View>(it) } ?: rows.firstOrNull()?.view

    private fun inPage(v: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === root) return true
            p = p.parent as? View
        }
        return false
    }

    /** D-pad rules: categories move among themselves, Right enters the card, Left returns. */
    private fun navigate(f: View?, dir: Int): View? {
        if (f == null || !inPage(f)) return catRows.getOrNull(shown)
        val ci = catRows.indexOf(f)
        if (ci >= 0) return when (dir) {
            View.FOCUS_UP -> catRows.getOrNull(ci - 1)
            View.FOCUS_DOWN -> catRows.getOrNull(ci + 1)
            View.FOCUS_RIGHT -> { show(ci, animate = false); firstRowTarget() }
            else -> null
        }
        val ri = rows.indexOfFirst { it.view === f }
        if (ri >= 0) return when (dir) {
            View.FOCUS_LEFT -> catRows.getOrNull(shown)
            View.FOCUS_UP -> rows.getOrNull(ri - 1)?.view
            View.FOCUS_DOWN -> rows.getOrNull(ri + 1)?.view
            else -> null
        }
        return android.view.FocusFinder.getInstance().findNextFocus(root, f, dir)
    }

    private fun onFocusMoved(now: View?) {
        if (released || now == null) return
        if (!inPage(now)) {
            reclaim(now)
            return
        }
        lastTag = now.tag as? String
        lastMove = android.os.SystemClock.uptimeMillis()
        val ci = catRows.indexOf(now)
        focusedCat = ci
        if (ci >= 0) {
            glide(ci, true)
            glow(true)
            if (ci != shown || filled != ci || outRunning) requestSwap(ci) else root.removeCallbacks(swapRunnable)
        } else {
            glow(false)
            glide(shown, true)
            if (rows.any { it.view === now }) data.getOrNull(shown)?.let { s -> lastTag?.let { lastRow[s.title] = it } }
        }
        styleCategories()
    }

    /**
     * Focus fell onto the home screen under the page (an overlay on top closed and focused home):
     * bring it back to where the viewer was. Overlays above the page keep their focus.
     */
    private fun reclaim(now: View) {
        if (closing) return
        val decor = root.parent as? ViewGroup ?: return
        var top: View = now
        while (true) {
            val p = top.parent as? View ?: return
            if (p === decor) break
            top = p
        }
        if (decor.indexOfChild(top) >= decor.indexOfChild(root)) return
        root.post { if (!closing && !released && root.isAttachedToWindow) restoreFocus(lastTag) }
    }

    private fun restoreFocus(tag: String?) {
        val target = tag?.let { root.findViewWithTag<View>(it) } ?: catRows.getOrNull(shown)
        target?.requestFocus()
    }

    private val focusWatch = ViewTreeObserver.OnGlobalFocusChangeListener { _, now -> onFocusMoved(now) }

    private inner class Root : FrameLayout(ctx) {
        override fun focusSearch(focused: View?, direction: Int): View? = navigate(focused, direction) ?: focused

        /** Focus arriving from outside (an overlay above closed) lands where the viewer was, not on the first view. */
        override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
            val t = lastTag?.let { findViewWithTag<View>(it) } ?: catRows.getOrNull(shown)
            return t?.requestFocus() == true || super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            viewTreeObserver.addOnGlobalFocusChangeListener(focusWatch)
        }

        override fun onDetachedFromWindow() {
            viewTreeObserver.removeOnGlobalFocusChangeListener(focusWatch)
            super.onDetachedFromWindow()
        }
    }

    // ---- public ----

    /** True once the frost covers the whole screen opaquely (nothing under the page shows any more). */
    val opaque: Boolean get() = frost != null && !released && frostView.alpha >= 1f && backdrop.alpha >= 1f && root.alpha >= 1f

    fun focusInitial() {
        root.post {
            if (released) return@post
            glide(shown, false)
            catRows.getOrNull(shown)?.requestFocus()
        }
    }

    /** true = consumed: from the options, Back returns to the categories; on the categories it closes the page via [Host.close]. */
    fun handleBack(): Boolean {
        val f = root.findFocus()
        if (f != null && rows.any { it.view === f }) {
            catRows.getOrNull(shown)?.requestFocus()
            return true
        }
        host.close()
        return true
    }

    /** Veil fades in, header and categories rise one after another, the card glides in from the right. */
    fun animateIn() {
        backdrop.alpha = 0f
        backdrop.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).withLayer().start()
        Ui.rise(headerViews + catRows, fromDp = 12, startDelay = 60L, step = 28L, duration = 300L)
        card.alpha = 0f
        card.translationX = 56.dp.toFloat()
        card.animate().alpha(1f).translationX(0f).setStartDelay(90).setDuration(380).setInterpolator(Ui.EASE).withLayer().start()
        hl.animate().alpha(1f).setStartDelay(300).setDuration(240).setInterpolator(Ui.SETTLE).start()
        lastMove = android.os.SystemClock.uptimeMillis()
        root.postDelayed(prebuild, PREBUILD_STEP)
    }

    /** One quick fade of the whole page (a single layer), then [done]. */
    fun animateOut(done: () -> Unit) {
        closing = true
        root.removeCallbacks(swapRunnable)
        var finished = false
        root.animate().alpha(0f).scaleX(1.015f).scaleY(1.015f).setStartDelay(0).setDuration(200)
            .setInterpolator(Ui.EXIT).withLayer().withEndAction {
                if (!finished) { finished = true; done() }
            }.start()
    }

    /** Rebuilds values and toggles in place (or the rows, if the list changed), keeping focus on the same row. */
    fun refresh(sections: List<Section>) {
        if (released) return
        val f = root.findFocus()
        val tag = (f?.tag as? String) ?: lastTag
        val old = data
        data = sections
        built.clear() // new section objects; the rows on screen are kept or rebuilt below
        // The other sections' rows are built ahead again (prebuild stops once every section had them).
        root.removeCallbacks(prebuild)
        if (!closing) root.postDelayed(prebuild, PREBUILD_STEP)
        val catsChanged = old.size != sections.size || old.indices.any { old[it].title != sections[it].title || old[it].icon != sections[it].icon || old[it].badge != sections[it].badge }
        if (shown !in sections.indices) shown = 0
        if (catsChanged) {
            root.removeCallbacks(swapRunnable)
            buildCategories()
            glide(shown, false)
        }
        if (sections.isEmpty()) {
            list.removeAllViews()
            rows.clear()
            filled = -1
        } else {
            val s = sections[shown]
            val same = !catsChanged && filled == shown && !outRunning && rows.size == s.items.size &&
                rows.indices.all { rows[it].item.label == s.items[it].label && (rows[it].sw != null) == (s.items[it].toggle != null) }
            if (same) {
                for ((r, item) in rows.zip(s.items)) {
                    r.item = item
                    r.value.text = item.value ?: ""
                    r.value.visibility = if (item.value.isNullOrEmpty()) View.GONE else View.VISIBLE
                    if (r.caption.text.toString() != (item.caption ?: "")) r.caption.text = item.caption ?: ""
                    r.caption.visibility = if (item.caption.isNullOrEmpty()) View.GONE else View.VISIBLE
                    r.value.setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), IntArray(0)),
                        intArrayOf(valueInk(), item.tone ?: k.dim)))
                    val t = item.toggle
                    if (t != null && t != r.on) { r.on = t; r.sw?.set(t) }
                }
                built[s] = rows.toList() // the updated rows are this (new) section's rows now
            } else {
                gen++
                outRunning = false
                pane.animate().cancel()
                pane.alpha = 1f
                pane.translationX = 0f
                fill(shown)
            }
        }
        // Only take focus back if it's ours or fell under the page; an overlay on top keeps it.
        val decor = root.parent as? ViewGroup
        val now = root.rootView.findFocus()
        val ownedAbove = now != null && !inPage(now) && decor != null && run {
            var top: View = now
            while (top.parent is View && top.parent !== decor) top = top.parent as View
            top.parent === decor && decor.indexOfChild(top) > decor.indexOfChild(root)
        }
        if (!ownedAbove && !closing) {
            val target = tag?.let { root.findViewWithTag<View>(it) }
                ?: (if (f != null && rows.none { it.view === f } && catRows.none { it === f }) firstRowTarget() else null)
                ?: catRows.getOrNull(shown)
            if (target != null && !target.isFocused) target.requestFocus()
        }
        styleCategories()
    }

    fun release() {
        released = true
        closing = true
        root.removeCallbacks(swapRunnable)
        root.removeCallbacks(prebuild)
        built.clear()
        root.animate().cancel()
        pane.animate().cancel()
        hl.animate().cancel()
        hlGlow.animate().cancel()
        hlRing?.let { CinemaStyle.setLive(it, false) }
        backdrop.animate().cancel()
        card.animate().cancel()
        frostView.animate().cancel()
        for (r in rows) r.sw?.stop()
        frostView.setImageDrawable(null)
        frost?.recycle()
        frost = null
    }

    companion object {
        private const val TAG = "PremiumSettings"
        private const val LEFT_W = 252
        private const val CAT_H = 46
        private const val CAT_GAP = 4
        /** How far into the category list its edge fade reaches (dp). */
        private const val CAT_FADE = 34
        private const val ROW_H = 56
        private const val ROW_GAP = 6
        private const val GLIDE = 240L
        private const val SWAP_DELAY = 150L
        /** Rows are built ahead one section per step, once focus has rested this long (ms). */
        private const val PREBUILD_IDLE = 450L
        private const val PREBUILD_STEP = 120L
    }
}

/**
 * A settings row: while it fades (the card's slide-in) its parts are faded one by one instead of through an
 * offscreen layer per row per frame (the glass fill and the text barely overlap, so it looks the same). A
 * focused row keeps the layer: its pearl face must hide the glow's inner band while it fades.
 */
internal class FadeRow(ctx: Context) : LinearLayout(ctx) {
    override fun hasOverlappingRendering() = isFocused
}

/**
 * Settings switch: a capsule track with a knob that slides across (one short spring per flip,
 * nothing allocated while drawing). [lit] = its row has the focus glow behind it, so the colors
 * turn to the ink that reads on the glow instead of the highlight they would vanish into.
 */
private class DreamSwitch(ctx: Context, private var on: Boolean, k: PremiumKit) : View(ctx) {
    private val select = k.select
    private val ink = PearlFocus.INK
    /** The knob when on, at rest: white on the highlight, but ink on Cinema's pearl track (white would vanish into it). */
    private val knobOn = PearlFocus.INK
    private val text = k.text
    private val offTrack = Ui.alpha(k.text, 0.18f)
    private val offTrackLit = Ui.fade(ink, 0.26f)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var pos = if (on) 1f else 0f
    private var anim: ValueAnimator? = null

    var lit = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Slides to [value] (no-op if already there). */
    fun set(value: Boolean) {
        if (value == on) return
        on = value
        anim?.cancel()
        anim = ValueAnimator.ofFloat(pos, if (on) 1f else 0f).apply {
            duration = 200
            interpolator = Ui.SPRING
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun stop() { anim?.cancel(); anim = null }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        val p = pos.coerceIn(0f, 1f)
        track.color = if (lit) lerp(offTrackLit, ink, p) else lerp(offTrack, select, p)
        knob.color = if (lit) lerp(ink, select, p) else lerp(text, knobOn, p)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)
        val kr = r - h * 0.13f
        canvas.drawCircle(r + (w - 2 * r) * pos, r, kr, knob)
    }

    private fun lerp(a: Int, b: Int, t: Float) = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )
}

/**
 * Dream Cinema's focus for a list row (Settings, menus): one rounded face of the row's own, neutral glass
 * at rest and solid pearl while focused, exactly under the fading rainbow border (same radius, same bounds).
 *
 * Why it's built this way: the face is repainted on every focus change rather than left to a state list (a
 * state list nested in another drawable can lose its callback and freeze mid-fade, leaving rows stuck
 * pearl), and the glow drops at once on focus loss: its halo reaches a corner radius inside the edge,
 * which only an opaque pearl face may cover (on glass it would show as a hard inner box while fading).
 * Text and icons follow the row's focused state as before (ink on pearl, white on glass).
 */
internal class CinemaRowFocus(v: View, radiusDp: Int, private val rest: Int, private val restStroke: Int = 0) {
    private val face = GradientDrawable().apply { cornerRadius = Ui.dpf(v.context, radiusDp.toFloat()) }
    private val glow = CinemaStyle.glowDrawable(v.context, radiusDp = radiusDp, haloDp = 10)
    private val strokePx = maxOf(1, Ui.dp(v.context, 1))

    init {
        paint(false)
        v.background = null
        v.background = android.graphics.drawable.LayerDrawable(arrayOf(glow, face))
        // The halo draws past the row: its list (and that list's holder) must not clip it.
        (v.parent as? ViewGroup)?.clipChildren = false
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(x: View) {
                (x.parent as? ViewGroup)?.let { it.clipChildren = false; (it.parent as? ViewGroup)?.clipChildren = false }
            }
            override fun onViewDetachedFromWindow(x: View) {}
        })
    }

    fun focus(has: Boolean) {
        paint(has)
        if (has) CinemaStyle.focusGlow(glow, true)
        else { glow.snap(glow.hue, false); CinemaStyle.setLive(glow, false) }
    }

    private fun paint(on: Boolean) {
        if (on) { face.setColor(PearlFocus.PEARL); face.setStroke(0, 0) }
        else { face.setColor(rest); face.setStroke(if (restStroke != 0) strokePx else 0, restStroke) }
    }
}

/**
 * Gives [v] Dream Cinema's row focus ([CinemaRowFocus]) on top of whatever its own focus listener does
 * (call after that listener is set). No-op outside Dream Cinema. [rest] is the row's resting fill.
 */
internal fun cinemaRowFocus(v: View, radiusDp: Int, rest: Int = Color.TRANSPARENT, restStroke: Int = 0) {
    val f = CinemaRowFocus(v, radiusDp, rest, restStroke)
    val prev = v.onFocusChangeListener
    v.setOnFocusChangeListener { x, has -> f.focus(has); prev?.onFocusChange(x, has) }
}

/**
 * For rows built with fixed light text: on focus every TextView inside [row] turns dark ink for the pearl
 * face ([PearlFocus.INK]; dimmer lines [PearlFocus.INK_DIM]) and gets its own color back on focus loss.
 * Set explicitly from the focus listener (duplicate parent state only works when wired before the child
 * is added, which these rows don't do). Call after the row's listener is set. No-op outside Dream Cinema.
 */
internal fun inkOnPearl(row: ViewGroup) {
    val texts = ArrayList<Triple<TextView, Int, Int>>()
    fun walk(g: ViewGroup) {
        for (i in 0 until g.childCount) when (val c = g.getChildAt(i)) {
            is TextView -> {
                val rest = c.currentTextColor
                val dimLine = Color.alpha(rest) < 0xE0 || (Color.red(rest) + Color.green(rest) + Color.blue(rest)) < 600
                texts += Triple(c, rest, if (dimLine) PearlFocus.INK_DIM else PearlFocus.INK)
            }
            is ViewGroup -> walk(c)
        }
    }
    walk(row)
    val prev = row.onFocusChangeListener
    row.setOnFocusChangeListener { v, has ->
        for ((t, rest, ink) in texts) t.setTextColor(if (has) ink else rest)
        prev?.onFocusChange(v, has)
    }
}

/** A single focusable TextView row: its own text turns ink on the pearl face. */
internal fun inkOnPearl(t: TextView) {
    val rest = t.currentTextColor
    t.setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), IntArray(0)), intArrayOf(PearlFocus.INK, rest)))
}
