package com.firehome.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

/**
 * The "Dream OS 4.0 · What's Inside" card at the foot of the System page (added by [SystemHealth],
 * Dream Cinema's System page): what this release is, then a
 * grid of feature tiles, each a line glyph, a Title Case name and one line on what it does.
 *
 * Every line must stay true of the code (it is the product's own claim sheet), and the hidden
 * engines are never named. Tiles take focus only so the remote can walk and scroll the grid; OK
 * does nothing. Static drawables, colors from [Theme]; focus is the Dream look ([PearlFocus]: pearl
 * tile, rainbow glow border) plus a scale lift (no blur, no elevation, no idle animation).
 */
class WhatsInside(private val ctx: Context) {

    private class Feature(val glyph: Int, val title: String, val line: String)

    private val features = listOf(
        Feature(FeatureGlyph.CORE, "Dream Core Resolver", "Fully custom-built resolver: streams in about a second via debrid"),
        Feature(FeatureGlyph.INSTANT, "Instant Play", "Predictive pre-loading: ready before you press Play, next episode lined up"),
        Feature(FeatureGlyph.PLAYER, "Dream Player", "Custom-built player: Skip Intro, Up Next, resume and Dream Subtitles"),
        Feature(FeatureGlyph.SPARK, "Dream Cinema", "All-new cinematic interface with rainbow glow focus"),
        Feature(FeatureGlyph.AIRPODS, "AirPods Compatibility", "Native AirPods support: pair, connect, Send to iPhone, and pause when they disconnect"),
        Feature(FeatureGlyph.CLOCK, "Continue Watching", "Seamless pick-up across everything, plus every New Episode"),
        Feature(FeatureGlyph.SEARCH, "Dream Search", "Universal search with the Dream Keyboard and Alexa voice"),
        Feature(FeatureGlyph.PERSON, "Profiles", "Personal profiles, each with its own list, look and PIN"),
        Feature(FeatureGlyph.SHIELD, "Ad Blocking", "System-wide ad shield across the whole Fire TV"),
        Feature(FeatureGlyph.PHONE, "Phone Remote", "Scan the QR code and your phone becomes the remote"),
        Feature(FeatureGlyph.STAR, "Trailers & Ratings", "Cinematic trailers with Rotten Tomatoes and IMDb scores"),
        Feature(FeatureGlyph.MOON, "Sleep Timer", "Pauses and sleeps the Fire TV when time's up"),
        Feature(FeatureGlyph.SUN, "Screensaver & Weather", "Cinematic trending art with live clock and weather"),
        Feature(FeatureGlyph.SLIDERS, "Make It Yours", "Screen Fit, sounds and wallpaper now; custom themes coming soon"),
        Feature(FeatureGlyph.PULSE, "System Health", "Smart self-diagnostics with one-press fixes"),
    )

    private val ink = Theme.get(ctx, Theme.TEXT)
    private val dim = Theme.get(ctx, Theme.TEXT_DIM)
    private val select = Theme.get(ctx, Theme.SELECT)
    private val font = Theme.typeface(ctx)
    private val corner = listOf(0, 10, 22)[Theme.get(ctx, Theme.CORNERS).coerceIn(0, 2)]
    private val tileCorner = corner * 2 / 3
    private val glyphColor = Color.WHITE
    private val Int.dp get() = Ui.dp(ctx, this)

    val view: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(Ui.S5.dp, Ui.S5.dp, Ui.S5.dp, Ui.S5.dp)
        // Glass with a hairline edge, washed from the top-left corner in the highlight and its dream
        // partner. The wash is blended into the glass up front: one full-card layer, not two (fill rate).
        val glass = Ui.surface(ctx, 1f)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            Ui.alpha(Ui.mix(glass, select, 0.22f), 0.9f),
            Ui.alpha(Ui.mix(glass, Ui.dreamShift(select), 0.07f), 0.9f),
            Ui.alpha(glass, 0.9f),
        )).apply {
            cornerRadius = corner.dp.toFloat()
            setStroke(1.dp, Ui.alpha(ink, 0.08f))
        }
        addView(header())
        addView(View(ctx).apply { setBackgroundColor(Ui.alpha(ink, 0.08f)) },
            LinearLayout.LayoutParams(MATCH_PARENT, 1.dp).apply { topMargin = Ui.S5.dp })
        addView(label(Ui.T_SECTION, ink).apply {
            text = "What's Inside"
            typeface = Ui.semibold(font)
            letterSpacing = Ui.TRACK_TITLE
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = Ui.S5.dp; bottomMargin = Ui.S4.dp })
        features.chunked(COLUMNS).forEachIndexed { r, row ->
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
                for (c in 0 until COLUMNS) {
                    val lp = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { if (c > 0) marginStart = Ui.S3.dp }
                    // A short last row keeps the grid's column widths (empty cells hold the space).
                    addView(row.getOrNull(c)?.let { tile(it) } ?: View(ctx), lp)
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (r > 0) topMargin = Ui.S3.dp })
        }
    }

    /** Brand mark, "Dream OS 4.0", the one-line pitch, and what is new in this release. */
    private fun header() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // The Dream OS mark itself (the rainbow cloud) on a dark tile: the brand, not a generic glyph.
        addView(FrameLayout(ctx).apply {
            background = Ui.rounded(ctx, Color.BLACK, maxOf(tileCorner, 10), 1f, Ui.alpha(Color.WHITE, 0.10f))
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.dream_mark)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(46.dp, 46.dp, Gravity.CENTER))
        }, LinearLayout.LayoutParams(56.dp, 56.dp).apply { marginEnd = Ui.S4.dp })
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(Ui.T_TITLE, ink).apply {
                text = "Dream OS ${version()}"
                typeface = Ui.semibold(font)
                letterSpacing = Ui.TRACK_TITLE
            })
            addView(label(Ui.T_BODY, dim).apply {
                text = "The complete premium streaming package, all built in."
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = Ui.S1.dp + 2.dp })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(pill("New in ${version()}", filled = true))
                addView(pill("Dream Core Resolver", filled = false), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = Ui.S2.dp })
                addView(pill("Dream Cinema", filled = false), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = Ui.S2.dp })
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = Ui.S3.dp })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun pill(s: String, filled: Boolean) = label(Ui.T_MICRO + 1f, if (!filled) ink else PearlFocus.INK).apply {
        text = s
        typeface = Ui.semibold(font)
        letterSpacing = 0.02f
        setPadding(Ui.S3.dp, Ui.S1.dp + 1.dp, Ui.S3.dp, Ui.S1.dp + 1.dp)
        background = if (filled) (Ui.rounded(ctx, PearlFocus.PEARL, Ui.R_PILL))
            else Ui.rounded(ctx, Ui.alpha(ink, 0.06f), Ui.R_PILL, 1f, Ui.alpha(ink, 0.16f))
    }

    /** One feature: glyph badge, name, one line. Focus: the pearl tile with dark ink inside the rainbow border, lifted a touch. */
    private fun tile(f: Feature) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        minimumHeight = 76.dp
        setPadding(Ui.S3.dp + 2.dp, Ui.S3.dp, Ui.S3.dp + 2.dp, Ui.S3.dp)
        // The Dream focus look: a pearl tile with dark ink inside the rainbow glow border, always rounded.
        val r = maxOf(tileCorner, 10)
        val glow = PearlFocus.apply(this, r, rest = Ui.rounded(ctx, Ui.alpha(ink, 0.045f), r, 1f, Ui.alpha(ink, 0.06f)))
        addView(FrameLayout(ctx).apply {
            isDuplicateParentStateEnabled = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(ctx, Ui.alpha(PearlFocus.INK, 0.08f), r * 3 / 4))
                addState(IntArray(0), Ui.rounded(ctx, Ui.alpha(Color.WHITE, 0.10f), r * 3 / 4))
            }
            addView(ImageView(ctx).apply {
                isDuplicateParentStateEnabled = true
                setImageDrawable(FeatureGlyph(f.glyph))
                imageTintList = states(PearlFocus.INK, glyphColor)
            }, FrameLayout.LayoutParams(22.dp, 22.dp, Gravity.CENTER))
        }, LinearLayout.LayoutParams(40.dp, 40.dp).apply { marginEnd = Ui.S3.dp })
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isDuplicateParentStateEnabled = true
            addView(label(Ui.T_BODY, ink).apply {
                text = f.title
                typeface = Ui.semibold(font)
                isDuplicateParentStateEnabled = true
                setTextColor(states(PearlFocus.INK, ink))
            })
            addView(label(Ui.T_CAPTION, dim).apply {
                text = f.line
                maxLines = 2
                setLineSpacing(2.dp.toFloat(), 1f)
                isDuplicateParentStateEnabled = true
                setTextColor(states(PearlFocus.INK_DIM, dim))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Ui.S1.dp })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        setOnFocusChangeListener { v, has -> PearlFocus.focus(glow, has); Ui.lift(v, has, LIFT) }
    }

    private fun label(sp: Float, color: Int) = TextView(ctx).apply {
        textSize = sp
        typeface = font
        setTextColor(color)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun states(focused: Int, normal: Int) =
        ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), IntArray(0)), intArrayOf(focused, normal))

    /** The installed version ("4.0"), so the title can't drift from the build. */
    private fun version() = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    } catch (e: Exception) {
        null
    } ?: "4.0"

    companion object {
        private const val COLUMNS = 3
        /** Between [Ui.LIFT_ROW] and [Ui.LIFT_PILL]: a wide tile reads as lifted without crowding its neighbours. */
        private const val LIFT = 1.04f

        /** The boot intro's short list: the headline features, each in its own color from Dream Cinema's glow palette. */
        private val INTRO = listOf(
            Triple(FeatureGlyph.CORE, "Dream Core Resolver", "Fully custom-built resolver"),
            Triple(FeatureGlyph.INSTANT, "Instant Play", "Predictive loading, zero wait"),
            Triple(FeatureGlyph.PLAYER, "Dream Player", "Custom-built cinema player"),
            Triple(FeatureGlyph.SPARK, "Dream Cinema", "All-new cinematic interface"),
            Triple(FeatureGlyph.AIRPODS, "AirPods Compatibility", "Native support, Send to iPhone"),
            Triple(FeatureGlyph.SHIELD, "Ad Blocking", "System-wide ad shield"),
            Triple(FeatureGlyph.PULSE, "System Health", "Smart self-diagnostics"),
        )
        private val INTRO_COLORS = intArrayOf(
            0xFF2F7BFF.toInt(), 0xFF8B5CFF.toInt(), 0xFFFF3DC8.toInt(), 0xFFFF8A1F.toInt(),
            0xFFFFD21F.toInt(), 0xFF7CFF4F.toInt(), 0xFF19E3D6.toInt(),
        )

        /**
         * "What's Inside" for the boot intro: a slim glass panel on the black left of the artwork (the logo
         * holds the middle), fixed colors like the rest of the intro so it reads the same whatever the theme.
         * Neutral smoked glass with a hairline, not tinted: on the black logo art only the feature icons
         * carry color, like the logo's own rainbow. Rows start hidden; [revealIntro] brings them in one by one.
         */
        fun intro(ctx: Context): View {
            fun dp(v: Int) = Ui.dp(ctx, v)
            val font = Theme.typeface(ctx)
            val ink = Color.WHITE
            val dim = Color.parseColor("#B4B4BC")
            fun label(sp: Float, color: Int) = TextView(ctx).apply {
                textSize = sp; typeface = font; setTextColor(color); includeFontPadding = false
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setShadowLayer(8f, 0f, 1f, Color.argb(160, 0, 0, 0))
            }
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.argb(170, 30, 30, 33), Color.argb(150, 12, 12, 14))).apply {
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(1), Color.argb(38, 255, 255, 255))
                }
                alpha = 0f
                addView(label(15f, ink).apply { text = "What's Inside"; typeface = Ui.semibold(font) })
                addView(label(11f, dim).apply { text = "The full premium streaming package" },
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(4) })
                INTRO.forEachIndexed { i, (glyph, title, line) ->
                    val color = INTRO_COLORS[i % INTRO_COLORS.size]
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        alpha = 0f
                        addView(FrameLayout(ctx).apply {
                            background = GradientDrawable().apply { cornerRadius = dp(7).toFloat(); setColor(Ui.alpha(color, 0.18f)) }
                            addView(ImageView(ctx).apply { setImageDrawable(FeatureGlyph(glyph).apply { setTint(color) }) },
                                FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
                        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
                        addView(LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(label(12.5f, ink).apply { text = title; typeface = Ui.semibold(font) })
                            addView(label(10.5f, dim).apply { text = line },
                                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2) })
                        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
                }
            }
        }

        /** Fades the [intro] panel in after [delayMs], then its rows one by one (alpha + a short slide: cheap). */
        fun revealIntro(panel: View, delayMs: Long) {
            val p = panel as? LinearLayout ?: return
            val shift = Ui.dpf(p.context, 14f)
            p.translationX = -shift
            p.animate().alpha(1f).translationX(0f).setStartDelay(delayMs).setDuration(600).setInterpolator(Ui.EASE).start()
            var row = 0
            for (i in 0 until p.childCount) {
                val v = p.getChildAt(i)
                if (v.alpha != 0f) continue // header lines come with the panel
                v.translationX = -shift
                v.animate().alpha(1f).translationX(0f).setStartDelay(delayMs + 350 + row++ * 140L).setDuration(420).setInterpolator(Ui.EASE).start()
            }
        }
    }
}

/**
 * The feature glyphs: outline drawings on a 24-unit grid with the same stroke, caps and tint
 * behaviour as [LineIcon], for the shapes that family doesn't have. Paths are built once per size.
 */
class FeatureGlyph(private val shape: Int) : Drawable() {
    companion object {
        const val CORE = 0; const val INSTANT = 1; const val PLAYER = 2; const val SPARK = 3; const val AIRPODS = 4
        const val CLOCK = 5; const val SEARCH = 6; const val PERSON = 7; const val SHIELD = 8; const val PHONE = 9
        const val STAR = 10; const val MOON = 11; const val SUN = 12; const val SLIDERS = 13; const val PULSE = 14
        private const val STROKE = 1.8f

        private fun pt(cx: Float, cy: Float, r: Float, deg: Float): Pair<Float, Float> {
            val a = Math.toRadians(deg.toDouble())
            return Pair(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
        }

        private fun build(shape: Int): Path = Path().apply {
            fun rr(l: Float, t: Float, r: Float, b: Float, rad: Float) = addRoundRect(RectF(l, t, r, b), rad, rad, Path.Direction.CW)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) { moveTo(x1, y1); lineTo(x2, y2) }
            fun circle(x: Float, y: Float, r: Float) = addCircle(x, y, r, Path.Direction.CW)
            when (shape) {
                CORE -> {
                    // A hexagonal core around a spark: the resolver at the heart of play.
                    for (i in 0 until 6) {
                        val (x, y) = pt(12f, 12f, 9f, -90f + i * 60f)
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                    moveTo(12.8f, 7.5f); lineTo(9.6f, 12.6f); lineTo(12.2f, 12.6f); lineTo(11.2f, 16.5f); lineTo(14.4f, 11.4f); lineTo(11.8f, 11.4f); close()
                }
                INSTANT -> {
                    moveTo(9.5f, 5.5f); lineTo(19.5f, 12f); lineTo(9.5f, 18.5f); close()
                    line(3f, 9f, 6.2f, 9f); line(2f, 12f, 6.2f, 12f); line(3f, 15f, 6.2f, 15f)
                }
                PLAYER -> {
                    rr(3f, 4f, 21f, 16f, 2.5f)
                    moveTo(10.3f, 7.4f); lineTo(14.6f, 10f); lineTo(10.3f, 12.6f); close()
                    line(4f, 20f, 8.2f, 20f); circle(10f, 20f, 1.6f); line(11.8f, 20f, 20f, 20f)
                }
                SPARK -> {
                    moveTo(11f, 3f); quadTo(11.6f, 11.4f, 19.5f, 12f); quadTo(11.6f, 12.6f, 11f, 21f)
                    quadTo(10.4f, 12.6f, 2.5f, 12f); quadTo(10.4f, 11.4f, 11f, 3f); close()
                    moveTo(18.5f, 2.8f); quadTo(18.7f, 5.3f, 21.2f, 5.5f); quadTo(18.7f, 5.7f, 18.5f, 8.2f)
                    quadTo(18.3f, 5.7f, 15.8f, 5.5f); quadTo(18.3f, 5.3f, 18.5f, 2.8f); close()
                }
                AIRPODS -> {
                    // Two earbuds: round heads, long stems.
                    rr(4.5f, 3.5f, 11f, 10f, 3.25f); line(9.8f, 10f, 9.8f, 20.5f)
                    rr(13f, 3.5f, 19.5f, 10f, 3.25f); line(14.2f, 10f, 14.2f, 20.5f)
                }
                CLOCK -> {
                    circle(12f, 12f, 8.8f)
                    line(12f, 7f, 12f, 12f); line(12f, 12f, 15.6f, 14.2f)
                }
                SEARCH -> {
                    circle(10.5f, 10.5f, 6.5f)
                    line(15.3f, 15.3f, 20f, 20f)
                }
                PERSON -> {
                    circle(12f, 8.2f, 3.8f)
                    moveTo(4.5f, 20.5f); quadTo(4.8f, 14f, 12f, 14f); quadTo(19.2f, 14f, 19.5f, 20.5f)
                }
                SHIELD -> {
                    moveTo(12f, 3f); lineTo(19.5f, 6f); lineTo(19.5f, 11.5f); quadTo(19.5f, 17.8f, 12f, 21f)
                    quadTo(4.5f, 17.8f, 4.5f, 11.5f); lineTo(4.5f, 6f); close()
                    moveTo(8.8f, 12f); lineTo(11.1f, 14.3f); lineTo(15.4f, 9.8f)
                }
                PHONE -> {
                    rr(7f, 2.5f, 17f, 21.5f, 2.6f)
                    line(10.6f, 18.4f, 13.4f, 18.4f)
                }
                STAR -> {
                    for (i in 0 until 10) {
                        val (x, y) = pt(12f, 12.8f, if (i % 2 == 0) 9f else 3.9f, -90f + i * 36f)
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                MOON -> {
                    addCircle(11.5f, 12.5f, 8.2f, Path.Direction.CW)
                    op(Path().apply { addCircle(16.2f, 8.2f, 6.6f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
                }
                SUN -> {
                    circle(12f, 12f, 3.8f)
                    for (i in 0 until 8) {
                        val (x1, y1) = pt(12f, 12f, 6.6f, i * 45f)
                        val (x2, y2) = pt(12f, 12f, 9f, i * 45f)
                        line(x1, y1, x2, y2)
                    }
                }
                SLIDERS -> {
                    line(4f, 7f, 7f, 7f); circle(9f, 7f, 2f); line(11f, 7f, 20f, 7f)
                    line(4f, 12f, 13f, 12f); circle(15f, 12f, 2f); line(17f, 12f, 20f, 12f)
                    line(4f, 17f, 9f, 17f); circle(11f, 17f, 2f); line(13f, 17f, 20f, 17f)
                }
                PULSE -> {
                    moveTo(2.5f, 12.5f); lineTo(7f, 12.5f); lineTo(9.3f, 6.5f); lineTo(13f, 18f)
                    lineTo(15.4f, 10f); lineTo(17f, 12.5f); lineTo(21.5f, 12.5f)
                }
            }
        }
    }

    private val unit = build(shape)
    /** Tints the mask: an alpha-only bitmap draws in the paint's color. */
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE }
    /**
     * The glyph rasterized once per size. A curved, anti-aliased stroke path is re-rasterized on the
     * render thread every frame it's drawn, and fifteen of them made each frame of the System page's
     * scroll glide miss its deadline; a small cached mask draws as a plain texture.
     */
    private var mask: Bitmap? = null

    override fun onBoundsChange(b: Rect) {
        mask = null
        if (b.width() <= 0 || b.height() <= 0) return
        val path = Path()
        unit.transform(Matrix().apply { setScale(b.width() / 24f, b.height() / 24f) }, path)
        mask = Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ALPHA_8).also {
            Canvas(it).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = STROKE * b.width() / 24f
            })
        }
    }

    override fun draw(canvas: Canvas) {
        val m = mask ?: return
        canvas.drawBitmap(m, bounds.left.toFloat(), bounds.top.toFloat(), paint)
    }

    private var tints: ColorStateList? = null

    /** Follows state tints (ink on a focused tile's fill), like [LineIcon]. */
    override fun setTintList(tint: ColorStateList?) {
        tints = tint
        applyTint(state)
    }

    override fun isStateful() = tints?.isStateful == true

    override fun onStateChange(state: IntArray) = applyTint(state)

    private fun applyTint(state: IntArray): Boolean {
        val t = tints
        val c = t?.getColorForState(state, t.defaultColor) ?: Color.WHITE
        if (paint.color == c) return false
        paint.color = c
        invalidateSelf()
        return true
    }

    override fun getIntrinsicWidth() = -1
    override fun getIntrinsicHeight() = -1
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
