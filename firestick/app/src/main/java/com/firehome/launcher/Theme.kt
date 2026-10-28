package com.firehome.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable

/**
 * Dream OS's look, stored as ints in the "theme" prefs (per profile). Dream Cinema is the only look: black
 * easing into the darkest gray, neutral grays for every surface, white text, and the rainbow (the focus glow,
 * [CinemaStyle.focusGlow], and Play, [Rainbow.fade]) as the only color. Downloadable and user-made themes are
 * a future update (docs/backlog.md); until then the look keys below always hold Dream Cinema's values.
 */
object Theme {

    private fun c(hex: String) = Color.parseColor(hex)

    const val BG_TOP = "bgTop"
    const val BG_BOTTOM = "bgBottom"
    const val BG_TYPE = "bgType"     // 0 linear, 1 radial, 2 solid, 3 photo
    const val BG_ANGLE = "bgAngle"   // index into ORIENTATIONS
    const val ACCENT = "accent"      // focus outline
    const val SELECT = "select"      // menu selection / button fill
    const val TEXT = "text"
    const val TEXT_DIM = "textDim"
    const val CLOCK_COLOR = "clockColor"
    const val TILE = "tile"
    const val FONT = "font"
    const val TILE_SIZE = "tileSize"
    const val CORNERS = "corners"
    const val ZOOM = "zoom"
    const val GLOW = "glow"          // colored glow behind focused tiles
    const val SAVER = "saver"            // idle minutes before the screensaver, see SAVER_MINUTES
    const val AIRPODS_SLEEP = "airpodsSleep" // start a sleep timer when AirPods disconnect
    const val NAV_SOUND = "navSound"     // soft ticks when moving, pop on select
    const val INTRO_SOUND = "introSound" // swell + chime on the boot intro
    const val NEW_EP_CHIME = "newEpChime" // chime when New Episodes finds a new episode
    const val NOTICE_SOUND = "noticeSound" // soft two-note pling when a notification banner slides in (Sounds.notice)
    const val ACCOUNT_SUMMARY = "accountSummary" // "Your Accounts" time-left notice once per boot (DebridExpiry)
    const val BTN_PRIMARY = "btnPrimary"     // main button box (Play, Watch on ...)
    const val BTN_SECONDARY = "btnSecondary" // other button boxes (More Info, My List ...)
    /** The layout once picked here (0 Classic, 1 Dream Premium, 2 Dream Cinema): read only to migrate, see [prefs]. */
    private const val STYLE = "style"
    private const val STYLE_CINEMA = 2

    /** The name Settings and setup show for the look. */
    const val NAME = "Dream Cinema"
    /** What Settings › Themes and setup's look step say about themes. */
    const val FUTURE_NOTE = "Dream Cinema is your look. In a future update, you'll be able to download themes made by others and create your own."

    /** Index 3 ("Photo") uses the picture sent from the phone remote. */
    val SAVER_MINUTES = listOf(0, 2, 5, 10, 15)

    const val BG_PHOTO = 3
    val FONTS = listOf(
        "Default" to "sans-serif",
        "Light" to "sans-serif-light",
        "Thin" to "sans-serif-thin",
        "Medium" to "sans-serif-medium",
        "Condensed" to "sans-serif-condensed",
        "Serif" to "serif",
        "Monospace" to "monospace",
        "Casual" to "casual",
        "Cursive" to "cursive",
        "Small caps" to "sans-serif-smallcaps",
    )

    /**
     * Dream Cinema's look ([CinemaShell]): black like the logo's ground easing to a barely lifted near-black
     * (smooth: [SmoothGradient]), neutral grays for every surface, white text. SELECT is the pearl focus box
     * (with dark ink) every row, sheet and key wears.
     */
    val CINEMA: Map<String, Int> = mapOf(
        BG_TOP to c("#000000"), BG_BOTTOM to c("#050506"), BG_TYPE to 0, BG_ANGLE to 1,
        ACCENT to c("#FFFFFF"), SELECT to c("#FBF8FF"), TEXT to c("#FFFFFF"), TEXT_DIM to c("#A1A1A6"),
        CLOCK_COLOR to c("#FFFFFF"), TILE to c("#1C1C1E"),
        BTN_PRIMARY to c("#FFFFFF"), BTN_SECONDARY to c("#2C2C2E"),
        FONT to 0, TILE_SIZE to 1, CORNERS to 1, ZOOM to 1, GLOW to 1,
    )

    /** Everything else: how Dream OS behaves, kept as set. */
    private val SETTINGS = mapOf(
        NAV_SOUND to 1, INTRO_SOUND to 1, SAVER to 2, AIRPODS_SLEEP to 0, NEW_EP_CHIME to 0, NOTICE_SOUND to 1, ACCOUNT_SUMMARY to 1,
    )

    private val DEFAULTS = CINEMA + SETTINGS

    private const val VERSION = 3
    /** Dream Cinema became the only look: every profile is moved to it once (see [prefs]). */
    private const val CINEMA_ONLY = "cinemaOnly"
    /** What the old layouts and their migrations left behind (saved sides, colors, flags), dropped by [CINEMA_ONLY]. */
    private val OLD_PREFIXES = listOf("prev.", "prem.", "cin.")
    private val OLD_KEYS = listOf(STYLE, "premium1", "premium2", "cinema1", "cinema2", "cinema3", "cinema5",
        "animBg", "clockSize", "showDate", "titles", "dynamic", "sidebarBg", "sidebarText", "sidebarSelText", "sidebarStyle",
        "sidebarBg2", "sidebarSelBox", "sidebarHoverBox", "sidebarHoverLine", "sidebarBgType", "sidebarBgAngle")

    // The current profile's prefs, cached by file name: get() runs dozens of times per layout
    // build, and a profile switch changes the name so the cache refreshes itself.
    @Volatile private var cached: Pair<String, android.content.SharedPreferences>? = null

    // Version 3 (color wheel / gradients) changed the stored keys, so start fresh once.
    private fun prefs(ctx: Context): android.content.SharedPreferences {
        val name = "theme" + Profiles.suffix(ctx)
        cached?.let { if (it.first == name) return it.second }
        return ctx.getSharedPreferences(name, Context.MODE_PRIVATE).also {
            if (it.getInt("v", 1) < VERSION) it.edit().clear().putInt("v", VERSION).apply()
            if (!it.contains(CINEMA_ONLY)) migrateToCinemaOnly(it, name)
            cached = name to it
        }
    }

    /**
     * Once per profile: Dream Premium and Classic are gone, so whoever was on one moves to Dream Cinema, and
     * every look key takes Cinema's value (the colors, fonts and presets Customize could change went with it).
     * A photo wallpaper sent from the phone stays. Sounds, the screensaver and the other settings are kept.
     */
    private fun migrateToCinemaOnly(p: android.content.SharedPreferences, name: String) {
        val style = p.getInt(STYLE, STYLE_CINEMA)
        if (style != STYLE_CINEMA) {
            android.util.Log.w("FireHome", "Theme: migrated ${if (style == 1) "Premium" else "Classic"} → Dream Cinema ($name)")
        }
        val photo = p.getInt(BG_TYPE, 0) == BG_PHOTO
        val e = p.edit()
        for (k in p.all.keys) if (k in OLD_KEYS || OLD_PREFIXES.any { k.startsWith(it) }) e.remove(k)
        for ((k, v) in CINEMA) e.putInt(k, v)
        if (photo) e.putInt(BG_TYPE, BG_PHOTO)
        e.putInt(CINEMA_ONLY, 1).apply()
    }

    fun get(ctx: Context, key: String): Int = prefs(ctx).getInt(key, DEFAULTS.getValue(key))

    fun set(ctx: Context, key: String, value: Int) = prefs(ctx).edit().putInt(key, value).apply()

    /** Back to Dream Cinema's own background (drops a photo wallpaper sent from the phone). */
    fun clearPhoto(ctx: Context) {
        set(ctx, BG_TYPE, CINEMA.getValue(BG_TYPE))
        wallpaperFile(ctx).delete()
    }

    fun hex(color: Int) = String.format("#%06X", color and 0xFFFFFF)

    fun typeface(ctx: Context, style: Int = Typeface.NORMAL): Typeface =
        Typeface.create(FONTS[get(ctx, FONT)].second, style)

    /** Black or white, whichever reads better on [bg]. */
    fun contrastOn(bg: Int): Int {
        val lum = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255
        return if (lum > 0.6) Color.BLACK else Color.WHITE
    }

    /** The page background for the stored gradient settings. */
    fun background(ctx: Context): android.graphics.drawable.Drawable {
        if (get(ctx, BG_TYPE) == BG_PHOTO) photoBackground(ctx)?.let { return it }
        // Full resolution and dithered (no bands); the plain GradientDrawable only if that bitmap can't be made.
        SmoothGradient.pageBitmap(ctx)?.let { return android.graphics.drawable.BitmapDrawable(ctx.resources, it) }
        return gradient(
            get(ctx, BG_TOP), get(ctx, BG_BOTTOM), get(ctx, BG_TYPE).coerceAtMost(2), get(ctx, BG_ANGLE),
            ctx.resources.displayMetrics.widthPixels,
        )
    }

    fun wallpaperFile(ctx: Context) = java.io.File(ctx.filesDir, "wallpaper.jpg")

    // Decoded once and reused (keyed by file time) so theme rebuilds don't re-decode the photo.
    private var photoCache: Pair<Long, android.graphics.Bitmap>? = null

    private fun photoBackground(ctx: Context): android.graphics.drawable.Drawable? {
        val f = wallpaperFile(ctx)
        if (!f.exists()) return null
        val bmp = photoCache?.takeIf { it.first == f.lastModified() }?.second
            ?: android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            })?.also { photoCache = f.lastModified() to it }
            ?: return null
        // Slight dim keeps text readable over bright photos.
        return android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.BitmapDrawable(ctx.resources, bmp),
            android.graphics.drawable.ColorDrawable(Color.argb(95, 0, 0, 0)),
        ))
    }

    fun gradient(top: Int, bottom: Int, type: Int, angle: Int, sizePx: Int) = GradientDrawable().apply {
        when (type.coerceAtMost(2)) {
            2 -> setColor(top)
            1 -> {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = sizePx * 0.75f
                setGradientCenter(0.5f, 0.35f)
                colors = intArrayOf(bottom, top)
            }
            else -> {
                orientation = ORIENTATIONS[angle]
                colors = intArrayOf(top, bottom)
            }
        }
    }

    private val ORIENTATIONS = listOf(
        GradientDrawable.Orientation.TOP_BOTTOM,
        GradientDrawable.Orientation.TL_BR,
        GradientDrawable.Orientation.LEFT_RIGHT,
        GradientDrawable.Orientation.BL_TR,
        GradientDrawable.Orientation.BOTTOM_TOP,
        GradientDrawable.Orientation.BR_TL,
        GradientDrawable.Orientation.RIGHT_LEFT,
        GradientDrawable.Orientation.TR_BL,
    )
}
