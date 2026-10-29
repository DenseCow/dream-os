package com.firehome.launcher

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View

/**
 * Dream Cinema: the shared design tokens every Cinema screen is built
 * from, so the shell, its pages and the title page read as one product. The idea in one line: Apple TV's
 * calm full-bleed art and focus, Netflix's hero (title logo, two-line synopsis, Play / More Info),
 * Disney+'s top bar of icon tabs, hero carousel with dots and brand tiles, Hulu's big landscape cards
 * with their details underneath and category chips.
 *
 * Lightweight by rule (the Fire TV Stick's CPU and GPU are small): no blur, no per-frame layout,
 * motion is translation / scale / alpha only (ViewPropertyAnimator), images at the smallest TMDB size
 * that looks sharp, at most one crossfading backdrop pair per screen.
 *
 * Colors always come from the user's theme ([Theme] roles: BG_TOP/BG_BOTTOM surfaces, SELECT highlight,
 * ACCENT focus ring, TEXT/TEXT_DIM, TILE, BTN_PRIMARY/BTN_SECONDARY): Cinema changes layout and motion,
 * the user's colors stay theirs.
 */
object CinemaStyle {
    // ---- layout (dp) ----
    /** Left/right safe margin of every page (TV overscan-safe, Apple TV-like breathing room). */
    const val GUTTER = 56
    /** Top bar height; the bar floats over the hero and fades out once rows are browsed. */
    const val BAR_H = 64
    /** Hero: full-bleed, this share of the screen height on Home (the first row peeks under it). */
    const val HERO_SHARE = 0.68f
    /** Gap between rows, and between a row title and its cards. */
    const val ROW_GAP = 28
    const val ROW_TITLE_GAP = 10
    const val CARD_GAP = 14

    // Card sizes (dp, 16:9 unless named): standard landscape, Hulu's big landscape, poster (2:3), brand tile.
    const val CARD_W = 248
    const val CARD_H = 140
    /**
     * Hulu's big landscape card, three across in a wide 7:3 (scope) frame: wider than the standard card,
     * yet a row of them with its title and captions is no taller than a standard row, so the focused row
     * and the next one always fit above the bottom fade (see [ROW_LINE]).
     */
    const val BIG_W = 270
    const val BIG_H = 116
    const val POSTER_W = 150
    const val POSTER_H = 225
    const val BRAND_W = 176
    /** The standard row card (16:9): four across the page between the gutters, the fifth peeking in. */
    const val LAND_W = 200
    /** Top 10 posters (beside their rank numerals). */
    const val RANK_W = 90
    const val RANK_H = 135
    /** Where a focused row's title settles once the page has scrolled (dp from the top; the bar is away by then). */
    const val ROW_LINE = 52
    /**
     * Where a poster-grid line's cards settle when the page scrolls back up to it (dp from the top); grids
     * scroll at their edges ([CinemaRowsPage]). Lines are short enough that two whole ones (poster, title,
     * year) always show above the bottom fade: at 1080p 44 + 213 + 17 + 213 = 487 dp, the fade starts at 486.
     * Lines the top edge cuts fade out instead of hiding under the top veil (a lifted poster stays clear).
     */
    const val GRID_LINE = 44
    /** Between the lines of a poster grid (dp). */
    const val GRID_GAP = 17

    // ---- shape ----
    const val R_CARD = 12
    const val R_BUTTON = 10
    const val R_CHIP = 100

    // ---- type (sp) ----
    const val T_HERO_TITLE = 40f   // when a title has no logo image
    const val T_ROW = 19f          // row titles: semibold, sentence case
    const val T_META = 13f         // "2011 · Comedy · TV-14"
    const val T_SYNOPSIS = 15f     // two lines max
    const val T_TAB = 14f
    const val T_CARD_TITLE = 15f   // under Hulu-style big cards
    const val T_CARD_KICKER = 12f  // "For fans of comedies" above it

    // ---- motion (ms) ----
    const val FOCUS_MS = 160L       // card lift / ring
    const val FOCUS_SCALE = 1.07f   // Apple TV-like lift; big cards 1.05
    const val FOCUS_SCALE_BIG = 1.05f
    const val ROW_SCROLL_MS = 220L  // rows glide to the focus line
    const val BACKDROP_FADE_MS = 320L
    const val HERO_ADVANCE_MS = 8_000L // carousel auto-advance while the hero has focus and the remote rests
    const val BAR_FADE_MS = 200L

    // ---- colors from the theme ----
    fun bg(ctx: Context) = Theme.get(ctx, Theme.BG_BOTTOM)
    fun bgTop(ctx: Context) = Theme.get(ctx, Theme.BG_TOP)
    fun text(ctx: Context) = Theme.get(ctx, Theme.TEXT)
    fun textDim(ctx: Context) = Theme.get(ctx, Theme.TEXT_DIM)
    fun tile(ctx: Context) = Theme.get(ctx, Theme.TILE)
    fun primary(ctx: Context) = Theme.get(ctx, Theme.BTN_PRIMARY)
    fun secondary(ctx: Context) = Theme.get(ctx, Theme.BTN_SECONDARY)

    /**
     * Card lift on focus: scale (+ elevation unless [z] is false) only, no layout; [big] for Hulu-size
     * cards. Glowing views ([CinemaGlow]) pass z = false: the glow is the depth, and an elevation shadow
     * would only cost a shadow pass that can't be seen on the dark background.
     */
    fun lift(v: View, focused: Boolean, big: Boolean = false, z: Boolean = true) {
        val s = if (!focused) 1f else if (big) FOCUS_SCALE_BIG else FOCUS_SCALE
        val a = v.animate().scaleX(s).scaleY(s).setStartDelay(0).setDuration(FOCUS_MS).setInterpolator(Ui.EASE)
        if (z) a.translationZ(if (focused) Ui.dpf(v.context, 12f) else 0f)
        a.start()
    }

    // ---- the rainbow focus glow ----
    //
    // Every highlight in Cinema is the same light: a soft halo plus a thin bright edge around the focused
    // thing, in one color that slowly fades through the rainbow ([Rainbow.fadeColor], the very clock the
    // Play button's fill runs on, so borders and Play always agree). On the black-to-gray base it reads as
    // an Apple-like light edge, not neon. Cost: the halo is one small alpha mask per corner size, blurred
    // once on the CPU and drawn nine-sliced, tinted by the paint ([CinemaGlow]). One shared clock ([tick],
    // ~20 times a second: the fade is slow) re-tints only the glows that are showing and the few accents
    // that follow it (tab underline, page dots, a focused progress bar); nothing runs when nothing glows,
    // and it all stops while the screen is hidden ([pause]).

    /** The rainbow, as [Rainbow.COLORS]: electric blue, violet, magenta, coral, orange, yellow, lime, aqua. */
    val GLOW_HUES = Rainbow.COLORS
    /** Glow fade in / out (ms). */
    const val GLOW_MS = 250L
    /** How far the halo reaches past the edge (dp); buttons and chips use [GLOW_HALO_SMALL]. */
    const val GLOW_HALO = 20
    const val GLOW_HALO_SMALL = 12
    /** Halo strength at full glow: soft on black (the edge carries the color). */
    const val GLOW_HALO_ALPHA = 0.34f
    /** The bright edge, drawn just outside the view (dp). */
    const val GLOW_EDGE = 3f // was 1.5: the user asked for a thicker rainbow border
    /** The shared clock's step (ms) while the remote is in use. */
    private const val TICK_MS = 50L
    /**
     * Once focus has rested [IDLE_AFTER_MS], the hue only steps every [IDLE_TICK_MS]: the slow fade still
     * moves, at 4 frames a second instead of 20 (STANDARDS: nothing animates per frame while idle).
     * The next focus move brings the full rate back at once ([wake]).
     */
    private const val IDLE_AFTER_MS = 3_000L
    private const val IDLE_TICK_MS = 250L

    /** The highlight color right now (the fading rainbow, in step with Play). */
    val glowNow: Int get() = Rainbow.fadeColor()

    fun glowColor(step: Int): Int = GLOW_HUES[Math.floorMod(step, GLOW_HUES.size)]

    /** Kept for callers from the per-move rainbow: the color no longer steps, it fades ([glowNow]). */
    fun nextGlow(): Int = glowNow

    // Weak keys: the callback gets the view as an argument, so it must not capture it (or the entry never clears).
    private val followers = java.util.WeakHashMap<View, (View, Int) -> Unit>()
    /** Glows that are showing (fading in, lit, or fading out): the clock re-tints them. */
    private val live = java.util.WeakHashMap<CinemaGlow, Boolean>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var ticking = false
    private var paused = false
    /** Full-screen pages over the paused shell (setup) that keep the clock running ([keep]). */
    private var keepers = 0
    /** Uptime of the last focus move (a glow lighting up or an accent starting to follow). */
    private var activeAt = 0L
    private val tickRun = Runnable { ticking = false; tick() }

    /** Focus moved: the clock goes back to its full rate (at once, if it was resting). */
    fun wake() {
        val now = android.os.SystemClock.uptimeMillis()
        val resting = now - activeAt >= IDLE_AFTER_MS
        activeAt = now
        if (resting && ticking) { main.removeCallbacks(tickRun); ticking = false }
        tick()
    }

    /** [apply] runs now and with the fading color while [v] is on screen (a handful of accents, never cards). */
    fun followGlow(v: View, apply: (View, Int) -> Unit) {
        followers[v] = apply
        apply(v, glowNow)
        wake()
    }

    /** Stops [v] following the color (a progress bar going back to pearl when its card loses focus). */
    fun unfollowGlow(v: View) { followers.remove(v) }

    /** False while the screen is hidden ([pause]): then nothing steps on the clock (Play's fade keeps its own timer). */
    val clockRunning: Boolean get() = !paused || keepers > 0

    /**
     * A full-screen page over the paused shell (setup) shows ([on]) or goes: while one shows, the clock keeps
     * re-tinting the glows on screen (only visible views are touched), so its focused control keeps moving.
     */
    fun keep(on: Boolean) {
        keepers = (keepers + if (on) 1 else -1).coerceAtLeast(0)
        if (on) wake()
    }

    /** The screen is hidden (true) or back (false): the clock stops / starts. */
    fun pause(p: Boolean) {
        paused = p
        if (!p) tick()
    }

    internal fun setLive(g: CinemaGlow, on: Boolean) {
        if (on) { live[g] = true; wake() } else live.remove(g)
    }

    /** One step of the shared clock: re-tint what shows, and come back only if something did. */
    internal fun tick() {
        if (!clockRunning || ticking) return
        val c = glowNow
        var any = false
        if (live.isNotEmpty()) for (g in live.keys.toList()) {
            // A glow layered under a face (PearlFocus, CinemaRowFocus) reports to its LayerDrawable: find the view.
            var cb = g.callback
            while (cb is Drawable) cb = cb.callback
            val v = cb as? View
            if (v != null && v.isAttachedToWindow && v.isShown) { g.tint(c); any = true }
        }
        // The hero's slow drift moves on this clock too: while it drifts, the rate stays full (Home's motion). A
        // drift ends after DRIFT_MS; the clock then drops to its idle rate instead of re-tinting Home 20 times a second.
        var smooth = false
        if (followers.isNotEmpty()) for ((v, apply) in followers.entries.toList()) {
            if (v.isAttachedToWindow && v.isShown) { apply(v, c); any = true; if (v is CinemaHero && v.driftMoving) smooth = true }
        }
        if (any) {
            ticking = true
            val resting = !smooth && android.os.SystemClock.uptimeMillis() - activeAt >= IDLE_AFTER_MS
            main.postDelayed(tickRun, if (resting) IDLE_TICK_MS else TICK_MS)
        }
    }

    /**
     * A glow for a view of corner radius [radiusDp], starting in [color] (invisible until shown). Cheap to
     * make: the blurred halo mask is cached per size ([mask]); each view gets its own small drawable.
     */
    fun glowDrawable(ctx: Context, color: Int = glowNow, radiusDp: Int = R_CARD, haloDp: Int = GLOW_HALO) =
        CinemaGlow(ctx, radiusDp, haloDp).also { it.tint(color) }

    /**
     * Focus in: the glow fades up in the current color and follows the fade while it shows. Focus out: it
     * fades away. The view's parent must not clip children (the halo draws past the view).
     */
    fun focusGlow(glow: CinemaGlow, has: Boolean) {
        if (has) { val c = glowNow; glow.show(c, c) } else glow.hide()
    }

    private val masks = HashMap<Long, Bitmap>()

    /**
     * The halo for corner radius [r] px reaching [e] px out: a rounded square blurred once into an ALPHA_8
     * bitmap of 2(e + r) + 1 px a side (a few KB), its middle row and column the stretchable part.
     */
    internal fun mask(r: Int, e: Int): Bitmap = masks.getOrPut((r.toLong() shl 32) or e.toLong()) {
        val c = e + r
        val size = 2 * c + 1
        Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8).also { b ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                if (e > 0) maskFilter = BlurMaskFilter(e * 0.8f, BlurMaskFilter.Blur.NORMAL)
            }
            Canvas(b).drawRoundRect(RectF(e.toFloat(), e.toFloat(), (size - e).toFloat(), (size - e).toFloat()), r.toFloat(), r.toFloat(), p)
        }
    }
}

/**
 * The Cinema focus glow ([CinemaStyle.focusGlow]): set as (or under) a view's background, it draws a soft
 * halo past the view's edges and a thin bright edge hugging them, in one hue. The halo is the cached mask
 * from [CinemaStyle.mask], drawn as nine slices (corners as they are, edges stretched, the middle skipped:
 * the view covers it) with the paint's color, so a hue change is a repaint of eight bitmap rects, nothing
 * more. Hidden, it draws nothing at all.
 */
class CinemaGlow(ctx: Context, radiusDp: Int, haloDp: Int) : Drawable() {
    private val density = ctx.resources.displayMetrics.density
    private val r = (radiusDp * density).toInt().coerceAtLeast(1)
    private val e = (haloDp * density).toInt()
    private val mask = CinemaStyle.mask(r, e)
    private val c = e + r
    private val halo = Paint(Paint.FILTER_BITMAP_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = CinemaStyle.GLOW_EDGE * density }
    private val src = Rect()
    private val dst = Rect()
    private val ring = RectF()
    private var color = CinemaStyle.glowNow
    private var level = 0f
    private var anim: ValueAnimator? = null
    /** Halo strength at full glow (chips and buttons may go a touch softer). */
    var haloAlpha = CinemaStyle.GLOW_HALO_ALPHA

    val hue: Int get() = color
    val shown: Boolean get() = level > 0f

    fun tint(c: Int) { color = c; invalidateSelf() }

    /** Fades up in [to] ([from] kept for callers); the shared clock keeps it on the fading color while it shows. */
    fun show(@Suppress("UNUSED_PARAMETER") from: Int, to: Int) {
        color = to
        CinemaStyle.setLive(this, true)
        animate(to, to, 1f)
    }

    fun hide() = animate(color, color, 0f)

    /** Set at once, no motion (a view that is focused as it's built). */
    fun snap(to: Int, on: Boolean) { anim?.cancel(); color = to; level = if (on) 1f else 0f; invalidateSelf() }

    private fun animate(from: Int, to: Int, toLevel: Float) {
        anim?.cancel()
        val fromLevel = level
        if (fromLevel == toLevel && from == to) {
            color = to
            if (toLevel == 0f) CinemaStyle.setLive(this, false) // hidden before it showed: off the clock too
            invalidateSelf()
            return
        }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CinemaStyle.GLOW_MS
            interpolator = Ui.SETTLE
            addUpdateListener {
                val f = it.animatedValue as Float
                // The color is the shared clock's (CinemaStyle.tick); only a real blend is drawn here.
                if (from != to) color = EVAL.evaluate(f, from, to) as Int
                level = fromLevel + (toLevel - fromLevel) * f
                invalidateSelf()
                if (f >= 1f && toLevel == 0f) CinemaStyle.setLive(this@CinemaGlow, false)
            }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        if (level <= 0.003f) return
        val b = bounds
        if (b.width() < 2 * r || b.height() < 2 * r) return
        halo.color = color
        halo.alpha = (255 * level * haloAlpha).toInt().coerceIn(0, 255)
        val l = b.left - e; val t = b.top - e; val rr = b.right + e; val bb = b.bottom + e
        val m = mask.width
        // Corners.
        slice(canvas, 0, 0, c, c, l, t, l + c, t + c)
        slice(canvas, m - c, 0, m, c, rr - c, t, rr, t + c)
        slice(canvas, 0, m - c, c, m, l, bb - c, l + c, bb)
        slice(canvas, m - c, m - c, m, m, rr - c, bb - c, rr, bb)
        // Edges (the one-pixel middle column / row stretched).
        slice(canvas, c, 0, c + 1, c, l + c, t, rr - c, t + c)
        slice(canvas, c, m - c, c + 1, m, l + c, bb - c, rr - c, bb)
        slice(canvas, 0, c, c, c + 1, l, t + c, l + c, bb - c)
        slice(canvas, m - c, c, m, c + 1, rr - c, t + c, rr, bb - c)
        val half = edge.strokeWidth / 2f
        edge.color = color
        edge.alpha = (255 * level).toInt().coerceIn(0, 255)
        ring.set(b.left - half, b.top - half, b.right + half, b.bottom + half)
        canvas.drawRoundRect(ring, r + half, r + half, edge)
    }

    private fun slice(canvas: Canvas, sl: Int, st: Int, sr: Int, sb: Int, dl: Int, dt: Int, dr: Int, db: Int) {
        if (dr <= dl || db <= dt) return
        src.set(sl, st, sr, sb)
        dst.set(dl, dt, dr, db)
        canvas.drawBitmap(mask, src, dst, halo)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object { val EVAL = ArgbEvaluator() }
}
