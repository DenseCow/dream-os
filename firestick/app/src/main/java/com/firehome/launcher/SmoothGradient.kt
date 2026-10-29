package com.firehome.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color

/**
 * The theme's page gradient computed per pixel at the screen's full resolution, with a fine dither.
 * A dark gradient like Dream Cinema's (black to near-black) has only a dozen or so steps per channel across the
 * screen, so drawn plainly it shows wide flat bands, and the old quarter-size fades stretched those
 * bands into visible blocks. Here every pixel takes its exact (fractional) color plus a little
 * triangular noise before rounding: the steps dissolve into a grain too fine to see. The fades and the
 * hero's melt into the page come from this math; the page itself is the same gradient drawn dithered by
 * the GPU ([pageDrawable]), so they meet without a seam. Linear and radial gradients only. A photo
 * wallpaper keeps its own path.
 */
object SmoothGradient {

    /** One theme gradient laid over a [w] x [h] screen, as [Theme.gradient] draws it. */
    class Spec(val top: Int, val bottom: Int, val type: Int, val angle: Int, val w: Int, val h: Int) {
        val key = "$top|$bottom|$type|$angle|$w|$h"
        private val r0 = Color.red(top).toFloat()
        private val g0 = Color.green(top).toFloat()
        private val b0 = Color.blue(top).toFloat()
        private val dr = Color.red(bottom) - r0
        private val dg = Color.green(bottom) - g0
        private val db = Color.blue(bottom) - b0
        // Linear: t = ax * x + ay * y + c (GradientDrawable's line between the orientation's two corners).
        private val ax: Float
        private val ay: Float
        private val c: Float
        // Radial: GradientDrawable's centre (0.5, 0.35) and radius (0.75 of the width), bottom color at the centre.
        private val cx = 0.5f * w
        private val cy = 0.35f * h
        private val rad = 0.75f * w

        init {
            val e = ENDS[angle.coerceIn(0, ENDS.size - 1)]
            val x0 = e[0] * w; val y0 = e[1] * h
            val dx = e[2] * w - x0; val dy = e[3] * h - y0
            val len = (dx * dx + dy * dy).coerceAtLeast(1f)
            ax = dx / len; ay = dy / len; c = -(x0 * dx + y0 * dy) / len
        }

        /** How far from [top] to [bottom] the gradient is at screen pixel ([x], [y]). */
        fun t(x: Float, y: Float): Float = when (type) {
            0 -> (ax * x + ay * y + c).coerceIn(0f, 1f)
            1 -> { val dx = x - cx; val dy = y - cy; 1f - (Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat() / rad).coerceIn(0f, 1f) }
            else -> 0f
        }

        fun red(t: Float) = r0 + dr * t
        fun green(t: Float) = g0 + dg * t
        fun blue(t: Float) = b0 + db * t
    }

    /** GradientDrawable's orientations as (x0, y0, x1, y1) in fractions of the bounds, in [Theme]'s order. */
    private val ENDS = arrayOf(
        floatArrayOf(0f, 0f, 0f, 1f), // TOP_BOTTOM
        floatArrayOf(0f, 0f, 1f, 1f), // TL_BR
        floatArrayOf(0f, 0f, 1f, 0f), // LEFT_RIGHT
        floatArrayOf(0f, 1f, 1f, 0f), // BL_TR
        floatArrayOf(0f, 1f, 0f, 0f), // BOTTOM_TOP
        floatArrayOf(1f, 1f, 0f, 0f), // BR_TL
        floatArrayOf(1f, 0f, 0f, 0f), // RIGHT_LEFT
        floatArrayOf(1f, 0f, 0f, 1f), // TR_BL
    )

    /** The page gradient over the screen, or null for a photo wallpaper (not a gradient). */
    fun spec(ctx: Context): Spec? {
        val type = Theme.get(ctx, Theme.BG_TYPE)
        if (type == Theme.BG_PHOTO && Theme.wallpaperFile(ctx).exists()) return null
        val dm = ctx.resources.displayMetrics
        return Spec(Theme.get(ctx, Theme.BG_TOP), Theme.get(ctx, Theme.BG_BOTTOM), type.coerceAtMost(2),
            Theme.get(ctx, Theme.BG_ANGLE), dm.widthPixels, dm.heightPixels)
    }

    /**
     * The window's background: the gradient drawn by the GPU with its dither on, which breaks the steps up the
     * same way [render]'s noise does. It was a screen-sized bitmap (8.3 MB at 1080p, and as much again as a
     * texture). Null when it's solid (nothing to smooth) or a photo.
     */
    fun pageDrawable(ctx: Context): android.graphics.drawable.Drawable? {
        val s = spec(ctx) ?: return null
        if (s.type == 2 || s.top == s.bottom) return null
        @Suppress("DEPRECATION")
        return Theme.gradient(s.top, s.bottom, s.type, s.angle, s.w).apply { setDither(true) }
    }

    /**
     * The gradient's pixels for the screen strip [outW] x [outH] starting at screen row [topPx], at full
     * resolution. With [alpha] ((u, v) across the strip, 0..1 each) it's a fade: the page's own color going
     * transparent, dithered in color and in alpha.
     */
    fun render(s: Spec, outW: Int, outH: Int, topPx: Int, alpha: ((Float, Float) -> Float)?): Bitmap {
        val b = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val row = IntArray(outW)
        val a = if (alpha != null) FloatArray(outW) else null
        val vDen = (outH - 1).coerceAtLeast(1).toFloat()
        for (y in 0 until outH) {
            val sy = y + topPx + 0.5f
            if (a != null) alphaRow(alpha!!, outW, y / vDen, a)
            for (x in 0 until outW) {
                val t = s.t(x + 0.5f, sy)
                val n = noise(x, y)
                val al = if (a == null) 255 else q(a[x] * 255f + noise(x + 7919, y + 104729))
                row[x] = (al shl 24) or (q(s.red(t) + n) shl 16) or (q(s.green(t) + n) shl 8) or q(s.blue(t) + n)
            }
            b.setPixels(row, 0, outW, 0, y, outW, 1)
        }
        if (a == null) b.setHasAlpha(false)
        return b
    }

    /**
     * [art] (opaque, showing the screen region [regionW] x [regionH] from the top) melted into the page
     * in place: each pixel blends toward the page's exact color by [alpha] (u, v across the region), in
     * full precision with the dither on the result, so the dark melt has no bands or blocks.
     */
    fun meltInto(art: Bitmap, s: Spec, regionW: Int, regionH: Int, alpha: (Float, Float) -> Float) {
        val w = art.width
        val h = art.height
        val row = IntArray(w)
        val a = FloatArray(w)
        val sx = regionW / w.toFloat()
        val syk = regionH / h.toFloat()
        val vDen = (h - 1).coerceAtLeast(1).toFloat()
        for (y in 0 until h) {
            art.getPixels(row, 0, w, 0, y, w, 1)
            alphaRow(alpha, w, y / vDen, a)
            val py = (y + 0.5f) * syk
            for (x in 0 until w) {
                val k = a[x].coerceIn(0f, 1f)
                val p = row[x]
                val t = s.t((x + 0.5f) * sx, py)
                val n = noise(x, y)
                val r = ((p shr 16) and 0xFF) * (1f - k) + s.red(t) * k + n
                val g = ((p shr 8) and 0xFF) * (1f - k) + s.green(t) * k + n
                val bl = (p and 0xFF) * (1f - k) + s.blue(t) * k + n
                row[x] = (0xFF shl 24) or (q(r) shl 16) or (q(g) shl 8) or q(bl)
            }
            art.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    /** Alpha samples this far apart along a row, eased between (fades are smooth): ~1/8 of the calls. */
    private const val STEP = 8

    private fun alphaRow(fn: (Float, Float) -> Float, w: Int, v: Float, out: FloatArray) {
        val uDen = (w - 1).coerceAtLeast(1).toFloat()
        var x0 = 0
        var a0 = fn(0f, v)
        while (x0 < w - 1) {
            val x1 = minOf(x0 + STEP, w - 1)
            val a1 = fn(x1 / uDen, v)
            val span = (x1 - x0).toFloat()
            for (x in x0 until x1) out[x] = a0 + (a1 - a0) * ((x - x0) / span)
            x0 = x1
            a0 = a1
        }
        out[w - 1] = a0
    }

    /** Triangular noise in -1..1 (one 8-bit step either way), the same for every frame and theme. */
    private fun noise(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return ((h and 0xFFFF) + ((h ushr 16) and 0xFFFF)) / 65535f - 1f
    }

    private fun q(v: Float): Int { val i = (v + 0.5f).toInt(); return if (i < 0) 0 else if (i > 255) 255 else i }
}
