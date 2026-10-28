package com.firehome.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.dreams.DreamService
import android.text.format.DateFormat
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.util.Date

/**
 * The idle screen, in Dream Cinema's language (Apple TV's calm full-bleed art and big thin clock, Netflix's
 * logo-meta-synopsis block, Disney+ / Hulu's "From My List" / "Tonight" kickers): titles from the user's own
 * library (My List, Continue Watching) mixed with tonight's picks and what's trending, one at a time, each
 * full screen with a slow Ken Burns drift and a long crossfade. Bottom left, the title's logo (or its name),
 * "2024 · Series · Drama", one line of synopsis; top right, the time, the date and the weather; bottom right,
 * the Dream OS mark.
 *
 * Cheap by rule (the stick's GPU is small): each backdrop has the dark melt (left, bottom, a touch under the
 * clock) baked into its pixels once, on a worker, against the page's exact gradient and dithered
 * ([SmoothGradient.meltInto]), so a frame is one opaque bitmap plus the text's cached layers; two bitmaps only
 * while a crossfade runs. The drift steps every [DRIFT_STEP_MS] (sub-pixel steps, scale / translation only:
 * no layout, no re-recording). At most three baked backdrops (~3.7 MB each) exist at once: the one showing,
 * the one fading out, the next one. Everything stops and lets go in [stop].
 *
 * Used both inside Dream OS (after idle time) and as the system screensaver ([DreamSaver]).
 */
class ScreensaverView(ctx: Context) : FrameLayout(ctx) {
    private val handler = Handler(Looper.getMainLooper())
    private val kit = CinemaKit(ctx)
    private val density = ctx.resources.displayMetrics.density
    private val sw = ctx.resources.displayMetrics.widthPixels
    private val sh = ctx.resources.displayMetrics.heightPixels
    private val layers = arrayOf(ImageView(ctx), ImageView(ctx))
    private var front = 0
    private var running = false

    /** One slide: the title and the small caps line above it ("From My List", "Tonight"). */
    private class Slide(val t: Title, val kicker: String)
    /** A slide made ready: its baked backdrop (or the raw one, [baked] false) and its logo, if it has one. */
    private class Ready(val s: Slide, val art: Bitmap, val baked: Boolean, val logo: Bitmap?)

    private var slides: List<Slide> = emptyList()
    private var index = 0
    /** The next slide, ready to show (its backdrop baked), or null while it's being made. */
    private var ready: Ready? = null
    /** The current slide has had its time: the next one shows the moment it's ready. */
    private var due = false
    private var misses = 0
    /** Bumped by [stop]: work started before it lands nowhere. */
    private var gen = 0

    // ---- the drift (per layer) ----
    private val driftStart = LongArray(2)
    private val driftIn = BooleanArray(2)
    private val driftDir = FloatArray(2)

    // ---- top right: time, date, weather ----
    private val clock = kit.label("", 96f, Color.WHITE, thin(kit.font)).apply {
        letterSpacing = -0.02f
        gravity = Gravity.END
        setShadowLayer(18f, 0f, 2f, Color.argb(170, 0, 0, 0))
    }
    private val date = kit.label("", 18f, Ui.alpha(Color.WHITE, 0.86f), kit.medium).apply {
        gravity = Gravity.END
        setShadowLayer(12f, 0f, 1f, Color.argb(190, 0, 0, 0))
    }
    private val weather = kit.label("", 15f, Ui.alpha(Color.WHITE, 0.70f), kit.font).apply {
        gravity = Gravity.END
        visibility = View.GONE
        setShadowLayer(12f, 0f, 1f, Color.argb(190, 0, 0, 0))
    }
    private val timeColumn = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
    }

    // ---- bottom left: the title ----
    private val kicker = kit.kicker("")
    private val logoBox = dp(LOGO_H)
    private val logo = FitImage(ctx, crop = false)
    private val bigTitle = kit.label("", 46f, kit.text, kit.bold, lines = 2).apply {
        gravity = Gravity.BOTTOM
        letterSpacing = -0.015f
        setLineSpacing(0f, 0.92f)
    }
    private val meta = kit.label("", CinemaStyle.T_META + 2f, Ui.alpha(kit.text, 0.86f), kit.medium)
    // Up to three full lines (one line cut most synopses off mid-word), wider than the title block.
    // A longer one glides up slowly so all of it can be read (AutoScroll), then the slide moves on ([advance]).
    private val synopsis = AutoScrollText(ctx).apply {
        textSize = CinemaStyle.T_SYNOPSIS + 1f
        typeface = kit.font
        setTextColor(Ui.alpha(kit.text, 0.78f))
        includeFontPadding = false
        maxLines = 3
        setLineSpacing(0f, 1.18f)
        restMs = SYNOPSIS_REST_MS
        onTop = true // the saver is what covers Home: its own synopsis isn't paused by that
    }
    private val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; alpha = 0f }

    // ---- bottom right: the brand ----
    private val brand = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    /** "Dream OS", fading through the rainbow one hue at a time, as Play does when focused ([Rainbow.fadeColor]). */
    private val brandWord = kit.label("Dream OS", 15f, Rainbow.fadeColor(), kit.semibold, tracking = 0.02f).apply {
        setShadowLayer(10f, 0f, 1f, Color.argb(150, 0, 0, 0))
    }
    private val brandTint = object : Runnable {
        override fun run() {
            if (!running) return
            brandWord.setTextColor(Rainbow.fadeColor())
            handler.postDelayed(this, BRAND_STEP_MS)
        }
    }

    /** Readability over art that couldn't be baked (out of memory): the same melt as plain gradients. Rare. */
    private val fallbackScrim = FrameLayout(ctx).apply { visibility = View.GONE }

    /** The melt baked into every backdrop: dark toward the text (left, bottom) and a little under the clock. */
    private val melt: (Float, Float) -> Float = { u, v ->
        val left = 0.86f * (1f - CinemaKit.ease((u - 0.02f) / 0.60f))
        val bottom = 0.97f * CinemaKit.ease((v - 0.40f) / 0.60f)
        val clockShade = 0.42f * (1f - CinemaKit.ease(v / 0.34f)) * CinemaKit.ease((u - 0.48f) / 0.42f)
        1f - (1f - left) * (1f - bottom) * (1f - clockShade)
    }

    init {
        // The page itself until the first backdrop is in (then nothing under the art: it covers everything).
        background = SmoothGradient.pageBitmap(ctx)?.let { BitmapDrawable(ctx.resources, it) } ?: ColorDrawable(kit.bg)
        // The two backdrops live in their own frame (as CinemaHero's artFrame): the crossfade lifts the incoming one
        // with translationZ, and a ViewGroup draws its children in Z order. Added straight to the saver, the art
        // (Z 1) drew over every other child (Z 0): the title block, the clock and the mark all vanished under it.
        // In here the Z only orders the two backdrops; the frame itself stays at Z 0, under the text.
        // No offscreen layer while the saver fades in / out: its alpha goes straight onto the backdrops.
        val artFrame = object : FrameLayout(ctx) { override fun hasOverlappingRendering() = false }
        for (l in layers) {
            l.scaleType = ImageView.ScaleType.FIT_XY
            l.alpha = 0f
            artFrame.addView(l, MATCH_PARENT, MATCH_PARENT)
        }
        addView(artFrame, MATCH_PARENT, MATCH_PARENT)
        fallbackScrim.addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.alpha(kit.bg, 0.88f), Ui.alpha(kit.bg, 0.45f), Color.TRANSPARENT))
        }, LayoutParams((sw * 0.62f).toInt(), MATCH_PARENT, Gravity.START))
        fallbackScrim.addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Ui.alpha(kit.bg, 0.70f), Ui.alpha(kit.bg, 0.96f)))
        }, LayoutParams(MATCH_PARENT, (sh * 0.58f).toInt(), Gravity.BOTTOM))
        addView(fallbackScrim, MATCH_PARENT, MATCH_PARENT)

        timeColumn.addView(clock, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.END })
        timeColumn.addView(date, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.END; topMargin = dp(6) })
        timeColumn.addView(weather, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.END; topMargin = dp(6) })
        addView(timeColumn, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(36)
            rightMargin = kit.gutter
        })

        val infoW = dp(INFO_W)
        info.addView(kicker, LinearLayout.LayoutParams(infoW, WRAP_CONTENT).apply { bottomMargin = dp(10) })
        info.addView(FrameLayout(ctx).apply {
            addView(bigTitle, FrameLayout.LayoutParams(infoW, logoBox, Gravity.BOTTOM))
            addView(logo, FrameLayout.LayoutParams(dp(LOGO_W), logoBox, Gravity.BOTTOM))
        }, LinearLayout.LayoutParams(infoW, logoBox))
        info.addView(meta, LinearLayout.LayoutParams(infoW, WRAP_CONTENT).apply { topMargin = dp(16) })
        info.addView(synopsis, LinearLayout.LayoutParams(dp(SYNOPSIS_W), WRAP_CONTENT).apply { topMargin = dp(10) })
        addView(info, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = kit.gutter
            bottomMargin = dp(BOTTOM)
        })

        markBitmap(ctx)?.let { bmp ->
            brand.addView(ImageView(ctx).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(22)))
        }
        brand.addView(brandWord, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(10) })
        // Tucked into the corner, below the synopsis's last line, so a long synopsis never runs into it.
        addView(brand, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(BRAND_EDGE)
            bottomMargin = dp(BRAND_EDGE)
        })
    }

    /** The saver's own fade in / out applies to each child (no screen-sized offscreen layer for it). */
    override fun hasOverlappingRendering() = false

    private fun dp(v: Int) = (v * density).toInt()

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = DreamTime.formatClock(context, now.time)
            date.text = DreamTime.fmt(context, "EEEE, MMMM d").format(now)
            // On the minute (a moment after it turns), not every few seconds: each change re-renders the clock's layer.
            handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 200L)
        }
    }

    /** The current slide has shown long enough: on to the next (now, or as soon as it's ready). */
    private var heldFor = 0L
    private val advance = object : Runnable { override fun run() {
        // A synopsis still gliding (or holding at its end) is being read: the slide waits for it, up to a cap.
        if (running && synopsis.reading && heldFor < MAX_HOLD_MS) {
            heldFor += HOLD_STEP_MS
            handler.postDelayed(this, HOLD_STEP_MS)
            return
        }
        heldFor = 0L
        if (running) {
            val r = ready
            if (r != null) { ready = null; show(r) } else due = true
        }
    } }

    /** The Ken Burns step: both layers while they show (one most of the time, two during a crossfade). */
    private val driftRun = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            for (i in 0..1) {
                val l = layers[i]
                if (l.drawable == null || l.alpha <= 0f || driftStart[i] == 0L) continue
                val p = ((now - driftStart[i]) / DRIFT_MS.toFloat()).coerceIn(0f, 1f)
                place(l, i, if (driftIn[i]) p else 1f - p)
            }
            handler.postDelayed(this, DRIFT_STEP_MS)
        }
    }

    /**
     * Layer [i] at drift position [t] (0..1). Scaling about the centre grows each side by more than the shift
     * (at 1080p 77 px a side against 33 px), so the art always covers the whole screen.
     */
    private fun place(l: View, i: Int, t: Float) {
        val sc = 1f + (DRIFT_SCALE - 1f) * t
        l.scaleX = sc
        l.scaleY = sc
        l.translationX = driftDir[i] * DRIFT_DX * density * t
        l.translationY = -DRIFT_DY * density * t
    }

    fun start() {
        if (running) return
        running = true
        val g = gen
        // Text sits on cached layers: the art moving under it never re-rasterizes it (only a change of text does).
        timeColumn.setLayerType(LAYER_TYPE_HARDWARE, null)
        info.setLayerType(LAYER_TYPE_HARDWARE, null)
        brand.setLayerType(LAYER_TYPE_HARDWARE, null)
        handler.post(tick)
        handler.post(brandTint)
        Weather.load(context) { now ->
            if (!running || g != gen || now == null) return@load
            weather.text = "${now.icon}  ${now.tempF}°F" + if (now.place.isNotEmpty()) "   ·   ${now.place}" else ""
            weather.visibility = View.VISIBLE
        }
        val app = context.applicationContext
        Net.background({ pool(app) }) { list ->
            if (!running || g != gen || list.isNullOrEmpty()) return@background
            slides = list
            index = 0
            due = true // the first slide shows the moment it's ready
            prepare(g)
        }
    }

    fun stop() {
        running = false
        gen++
        handler.removeCallbacks(tick)
        handler.removeCallbacks(brandTint)
        handler.removeCallbacks(advance)
        handler.removeCallbacks(driftRun)
        ready = null
        due = false
        timeColumn.setLayerType(LAYER_TYPE_NONE, null)
        info.setLayerType(LAYER_TYPE_NONE, null)
        brand.setLayerType(LAYER_TYPE_NONE, null)
        info.animate().cancel()
        for (l in layers) {
            l.animate().cancel()
            l.setImageDrawable(null) // the bitmaps go
        }
        logo.setImageDrawable(null)
    }

    /**
     * What it shows: My List and Continue Watching, mixed one-for-one with the time of day's picks and what's
     * trending, every one with a backdrop, no title twice. Blocking (TMDB): a worker.
     */
    private fun pool(ctx: Context): List<Slide> {
        val lib = ArrayList<Slide>()
        try {
            for (t in Tmdb.myList(ctx)) if (t.backdropPath != null) lib += Slide(t, "From My List")
        } catch (e: Exception) { Log.w("FireHome", "saver: my list: $e") }
        try {
            for (e in WatchHistory.recent(ctx).take(16)) {
                if (e.hidden || e.tmdbId <= 0 || e.backdropPath == null) continue
                lib += Slide(e.toTitle(), if (e.upNext || e.progress < WatchHistory.FINISHED) "Continue Watching" else "Watch Again")
            }
        } catch (e: Exception) { Log.w("FireHome", "saver: history: $e") }
        val disc = ArrayList<Slide>()
        if (Tmdb.hasKey(ctx)) try {
            val moment = HomePicks.moment().name
            // Anime stays in the anime sections (the user's rule): not in the saver's picks or trending.
            for (t in HomePicks.featured(ctx, 8)) if (!RowMix.isAnime(t)) disc += Slide(t, moment)
            for (t in Tmdb.trending(ctx)) if (t.backdropPath != null && !RowMix.isAnime(t)) disc += Slide(t, "Trending Now")
        } catch (e: Exception) { Log.w("FireHome", "saver: trending: $e") }
        val a = lib.distinctBy { it.t.key }.shuffled()
        val b = disc.distinctBy { it.t.key }.shuffled()
        val out = LinkedHashMap<String, Slide>()
        var i = 0
        while ((i < a.size || i < b.size) && out.size < MAX_SLIDES) {
            a.getOrNull(i)?.let { out.putIfAbsent(it.t.key, it) }
            b.getOrNull(i)?.let { out.putIfAbsent(it.t.key, it) }
            i++
        }
        return out.values.toList()
    }

    /** Makes the next slide ready: its backdrop fetched and baked, its logo looked up and loaded. */
    private fun prepare(g: Int) {
        if (!running || g != gen || slides.isEmpty()) return
        val s = slides[index % slides.size]
        index++
        val url = s.t.backdrop("w1280")
        if (url == null) { skip(g); return }
        var art: Pair<Bitmap, Boolean>? = null
        var logoDone = false
        var logoBmp: Bitmap? = null
        fun maybeReady() {
            val a = art ?: return
            if (!logoDone || !running || g != gen) return
            misses = 0
            val r = Ready(s, a.first, a.second, logoBmp)
            if (due) { due = false; show(r) } else ready = r
        }
        Net.bitmap(context, url) { raw ->
            if (!running || g != gen) return@bitmap
            if (raw == null) { skip(g); return@bitmap }
            bake(raw, g) { bmp, baked -> art = bmp to baked; maybeReady() }
        }
        CinemaLogos.get(context, s.t) { logoUrl ->
            if (!running || g != gen) return@get
            if (logoUrl == null) { logoDone = true; maybeReady(); return@get }
            Net.bitmap(context, logoUrl) { b -> logoBmp = b; logoDone = true; maybeReady() }
        }
    }

    /** A slide without art: the next one instead (and give up quietly once every one has failed). */
    private fun skip(g: Int) {
        if (++misses >= slides.size.coerceAtLeast(1)) { Log.w("FireHome", "saver: no backdrops could be loaded"); return }
        handler.postDelayed({ prepare(g) }, 400)
    }

    /**
     * [raw] (the w1280 backdrop) cropped to the screen's shape at its own resolution (~1280x720, 3.7 MB) with
     * the melt drawn into it per pixel against the page's gradient ([SmoothGradient.meltInto]: dithered, no
     * bands). On a worker; handed over a frame after its upload started. [done] gets the raw art and false when
     * baking fails (the plain gradients then cover it).
     */
    private fun bake(raw: Bitmap, g: Int, done: (Bitmap, Boolean) -> Unit) {
        if (raw.width < 2 || raw.height < 2) { done(raw, false); return }
        val spec = SmoothGradient.spec(context) ?: SmoothGradient.Spec(kit.bg, kit.bg, 0, 0, sw, sh)
        Net.background({
            try {
                val rw = raw.width.toFloat()
                val rh = raw.height.toFloat()
                val fit = maxOf(sw / rw, sh / rh)
                val bw = minOf(sw, Math.round(sw / fit)).coerceAtLeast(2)
                val bh = Math.round(bw * sh / sw.toFloat()).coerceAtLeast(2)
                val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                c.scale(bw / sw.toFloat(), bh / sh.toFloat())
                // Cover the screen; any overflow trimmed a little more from the bottom than the top (faces sit high).
                val m = Matrix().apply { setScale(fit, fit); postTranslate((sw - rw * fit) / 2f, -(rh * fit - sh) * 0.3f) }
                c.drawBitmap(raw, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
                SmoothGradient.meltInto(out, spec, sw, sh, melt)
                out.setHasAlpha(false)
                out
            } catch (e: Throwable) {
                Log.w("FireHome", "saver: backdrop not baked: $e")
                null
            }
        }) { out ->
            if (!running || g != gen) return@background
            if (out == null) { done(raw, false); return@background }
            out.prepareToDraw()
            Choreographer.getInstance().postFrameCallback { handler.post { if (running && g == gen) done(out, true) } }
        }
    }

    /** The crossfade to [r]: art, then the title block; the next slide is made ready once the fade is done. */
    private fun show(r: Ready) {
        if (!running) return
        val g = gen
        val inI = 1 - front
        val incoming = layers[inI]
        val outgoing = layers[front]
        incoming.animate().cancel()
        outgoing.animate().cancel()
        if (outgoing.drawable != null) outgoing.alpha = 1f // a fade cut short: the art that was arriving is the base now
        incoming.scaleType = if (r.baked) ImageView.ScaleType.FIT_XY else ImageView.ScaleType.CENTER_CROP
        incoming.setImageBitmap(r.art)
        incoming.translationZ = 1f
        outgoing.translationZ = 0f
        incoming.alpha = 0f
        // Ken Burns: in or out, left or right, by turns.
        driftIn[inI] = index % 2 == 1
        driftDir[inI] = if ((index / 2) % 2 == 0) -1f else 1f
        driftStart[inI] = SystemClock.uptimeMillis()
        place(incoming, inI, if (driftIn[inI]) 0f else 1f)
        front = inI
        if (!r.baked) fallbackScrim.visibility = View.VISIBLE
        incoming.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(Ui.EASE).withEndAction {
            if (!running || g != gen) return@withEndAction
            // Fully in and opaque: nothing under it needs drawing any more.
            background = null
            if (layers[front] === incoming) {
                outgoing.alpha = 0f
                outgoing.setImageDrawable(null) // its bitmap goes
                driftStart[1 - inI] = 0L
                if (r.baked) fallbackScrim.visibility = View.GONE
            }
            prepare(g)
        }.start()
        handler.removeCallbacks(driftRun)
        handler.post(driftRun)
        setInfo(r)
        handler.removeCallbacks(advance)
        handler.postDelayed(advance, SLIDE_MS)
    }

    private fun setInfo(r: Ready) {
        info.animate().cancel()
        if (info.alpha < 0.01f) { fillInfo(r); infoIn(500); return }
        info.animate().alpha(0f).translationY(0f).setStartDelay(0).setDuration(450).setInterpolator(Ui.EXIT).withEndAction {
            if (!running) return@withEndAction
            fillInfo(r)
            infoIn(350)
        }.start()
    }

    /** The title block rises a touch as it fades in, after the art has begun to. */
    private fun infoIn(delay: Long) {
        info.translationY = dp(8).toFloat()
        info.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(900).setInterpolator(Ui.EASE).start()
    }

    private fun fillInfo(r: Ready) {
        val t = r.s.t
        kicker.text = r.s.kicker.uppercase()
        val name = t.name
        bigTitle.text = name
        bigTitle.textSize = when {
            name.length > 30 -> 32f
            name.length > 18 -> 38f
            else -> 46f
        }
        if (r.logo != null) {
            logo.setImageBitmap(r.logo)
            logo.visibility = View.VISIBLE
            bigTitle.visibility = View.INVISIBLE
        } else {
            logo.setImageDrawable(null)
            logo.visibility = View.GONE
            bigTitle.visibility = View.VISIBLE
        }
        meta.text = kit.meta(t)
        synopsis.text = t.overview
        synopsis.visibility = if (t.overview.isBlank()) View.GONE else View.VISIBLE
    }

    companion object {
        private const val SLIDE_MS = 14_000L
        private const val FADE_MS = 2_400L
        /** One drift runs the whole time a layer shows (its slide plus both fades). */
        private const val DRIFT_MS = SLIDE_MS + 2 * FADE_MS
        private const val DRIFT_SCALE = 1.08f
        private const val DRIFT_DX = 22f
        private const val DRIFT_DY = 8f
        /** ~30 steps a second: each a fraction of a pixel, which the GPU's filtering shows as glide, not steps. */
        private const val DRIFT_STEP_MS = 33L
        private const val MAX_SLIDES = 24
        private const val INFO_W = 600
        /** The synopsis column (dp): about 70 characters a line, clear of the Dream OS mark at the bottom right. */
        private const val SYNOPSIS_W = 760
        private const val LOGO_W = 400
        private const val LOGO_H = 104
        private const val BOTTOM = 60
        /** A long synopsis starts to glide this soon after its slide arrives (ms). */
        private const val SYNOPSIS_REST_MS = 2_500L
        /** How long a slide may wait for its synopsis to finish, checked every [HOLD_STEP_MS]. */
        private const val MAX_HOLD_MS = 30_000L
        private const val HOLD_STEP_MS = 1_000L
        /** The brand's distance from the screen's right and bottom edges (dp): in the corner, inside TV overscan. */
        private const val BRAND_EDGE = 26
        /** The brand's rainbow step (ms): ~15 a second, smooth for a slow fade, a tiny redraw each. */
        private const val BRAND_STEP_MS = 66L

        /** The theme's font at its thinnest (the big clock). */
        private fun thin(base: Typeface): Typeface =
            if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(base, 200, false) else Typeface.create("sans-serif-thin", Typeface.NORMAL)

        /** The rainbow cloud mark, decoded small (it shows 22 dp tall): a few dozen KB, not the full PNG. */
        private fun markBitmap(ctx: Context): Bitmap? = try {
            BitmapFactory.decodeResource(ctx.resources, R.drawable.dream_mark, BitmapFactory.Options().apply { inSampleSize = 4 })
        } catch (e: Throwable) { null }
    }
}

/** System screensaver (Fire OS idle), showing the same view. Any button wakes it. */
class DreamSaver : DreamService() {
    private var view: ScreensaverView? = null

    companion object {
        /** True while the Fire OS screensaver is on screen (MainActivity skips its GPU trim then). */
        @Volatile var dreaming = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        view = ScreensaverView(this).also { setContentView(it) }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        dreaming = true
        view?.start()
    }

    override fun onDreamingStopped() {
        dreaming = false
        view?.stop()
        super.onDreamingStopped()
    }
}
