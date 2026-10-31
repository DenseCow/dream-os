package com.firehome.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Setup's stage (the 2026-10-29 redesign). Every step has the same shape: [ChapterRail] across the top,
 * then the step's emblem, "Step 2 of 4" and title, one or two sentences, the one thing to do, and the
 * buttons pinned to the bottom of the column. [SetupTile] is a large choice for picking one of a few
 * things (a debrid service).
 */

/**
 * The four chapters of setup as labeled segments over one thin track. The finished part of the track is the fading
 * rainbow (the brand's only accent, and here it means progress); the current chapter's name is white, finished
 * ones a lighter gray, the rest dim. One view, drawn in one pass; it eases to a new position, and then only the
 * hue moves (redrawn on Cinema's shared clock while on screen, [Rainbow.redrawOnFade]).
 */
internal class ChapterRail(ctx: Context, private val k: PremiumKit) : View(ctx) {
    private val Int.dpf get() = this * resources.displayMetrics.density
    private var names: List<String> = emptyList()
    private var at = -1
    private var shown = FloatArray(0)
    private var anim: ValueAnimator? = null
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.alpha(Color.WHITE, 0.14f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        Rainbow.redrawOnFade(this)
    }

    /** [now]: the current chapter (-1 before the first); [progress]: how far each chapter is, 0 to 1. */
    fun set(chapters: List<String>, now: Int, progress: FloatArray) {
        val sameNames = chapters == names
        names = chapters
        at = now
        if (!sameNames || shown.size != progress.size) shown = FloatArray(progress.size)
        val from = shown.copyOf()
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = Ui.SETTLE
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                for (i in shown.indices) shown[i] = from[i] + (progress[i] - from[i]) * t
                invalidate()
            }
            start()
        }
        contentDescription = chapters.getOrNull(now)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = label.fontMetrics
        val h = (fm.descent - fm.ascent + 10.dpf + 4.dpf).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val n = names.size
        if (n == 0) return
        val gap = 14.dpf
        val seg = (width - gap * (n - 1)) / n
        val fm = label.fontMetrics
        val base = -fm.ascent
        val barTop = height - 4.dpf
        val r = 2.dpf
        fill.color = Rainbow.fadeColor()
        for (i in 0 until n) {
            val x = i * (seg + gap)
            label.typeface = if (i == at) k.semibold else k.medium
            label.color = when {
                i == at -> Color.WHITE
                i < at -> Ui.alpha(Color.WHITE, 0.70f)
                else -> Ui.alpha(Color.WHITE, 0.52f)
            }
            c.drawText(names[i], x, base, label)
            rect.set(x, barTop, x + seg, height.toFloat())
            c.drawRoundRect(rect, r, r, track)
            val f = shown.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            if (f > 0f) {
                rect.set(x, barTop, x + seg * f, height.toFloat())
                c.drawRoundRect(rect, r, r, fill)
            }
        }
    }
}

/**
 * A step's emblem for steps without their own [SetupArt] drawing: the same dark-glass disc in the thin ring of the
 * fading rainbow ([GlyphPen]), with the step's line icon in pearl. Only the ring's hue moves, on Cinema's clock.
 */
internal class IconArt(ctx: Context, icon: Int) : View(ctx) {
    private val g = GlyphPen(weight = 3f, ring = 1.7f)
    private val d = try { ctx.getDrawable(icon)?.mutate()?.apply { setTint(PearlFocus.PEARL) } } catch (e: Exception) { null }

    init { Rainbow.redrawOnFade(this) }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height) / 100f
        if (s <= 0f) return
        val save = canvas.save()
        canvas.translate(width / 2f - 50f * s, height / 2f - 50f * s)
        canvas.scale(s, s)
        g.backdrop(canvas)
        canvas.restoreToCount(save)
        d?.let {
            val half = (minOf(width, height) * 0.22f).toInt()
            it.setBounds(width / 2 - half, height / 2 - half, width / 2 + half, height / 2 + half)
            it.draw(canvas)
        }
    }
}

/**
 * A large choice: an emblem, a name that wraps in full, and a status line with its drawn mark. At rest neutral
 * glass; focused, the pearl face with dark ink inside the rainbow border ([PearlFocus]), like every control.
 */
internal class SetupTile(ctx: Context, private val k: PremiumKit, emblem: View, onClick: () -> Unit) : LinearLayout(ctx) {
    private val Int.dp get() = Ui.dp(context, this)
    val title: TextView
    private val status: TextView
    private val mark = View(ctx)
    private var key = ""

    init {
        orientation = VERTICAL
        gravity = Gravity.START
        setPadding(18.dp, 18.dp, 16.dp, 16.dp)
        isFocusable = true
        isClickable = true
        val glow = PearlFocus.apply(this, Ui.R_LG)
        addView(emblem, LayoutParams(40.dp, 40.dp))
        title = k.label("", 18f, SetupBrand.TEXT, k.semibold, lines = 0).apply {
            isDuplicateParentStateEnabled = true
            setTextColor(PearlFocus.states(PearlFocus.INK, SetupBrand.TEXT))
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        }
        addView(title, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 16.dp })
        val line = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isDuplicateParentStateEnabled = true }
        line.addView(mark, LayoutParams(16.dp, 16.dp).apply { marginEnd = 8.dp })
        status = k.label("", 14f, SetupBrand.DIM, k.medium, lines = 0).apply {
            isDuplicateParentStateEnabled = true
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        }
        line.addView(status, LayoutParams(0, WRAP_CONTENT, 1f))
        addView(line, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 6.dp })
        setOnFocusChangeListener { v, has -> PearlFocus.focus(glow, has); Ui.lift(v, has, 1.04f) }
        setOnClickListener { try { onClick() } catch (e: Exception) { android.util.Log.w(SetupBrand.TAG, "Setup: tile ${title.text}", e) } }
    }

    /** In place; nothing is touched when it already shows exactly this. [tone]: the status words' color. */
    fun set(t: String, line: String, tone: Int? = null, m: BrandRow.Mark = BrandRow.Mark.NONE) {
        val now = "$t|$line|$tone|$m"
        if (now == key) return
        key = now
        title.text = t
        status.text = line
        status.setTextColor(PearlFocus.states(PearlFocus.INK_DIM, tone ?: SetupBrand.DIM))
        mark.visibility = if (m == BrandRow.Mark.NONE) GONE else VISIBLE
        mark.background = if (m == BrandRow.Mark.NONE) null else StatusGlyph(m)
    }
}
