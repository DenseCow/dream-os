package com.firehome.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.util.LruCache

/** Cheap metadata; [banner] and [icon] are decoded on first use (only for tiles actually shown) and kept
 *  in a small bounded cache instead of pinning every installed app's art for the life of the process. */
class AppEntry(
    val pkg: String,
    val label: String,
    val system: Boolean,
    private val pm: PackageManager,
    private val ri: ResolveInfo,
) {
    val banner: Drawable? get() = Apps.art(this).banner
    val icon: Drawable get() = Apps.art(this).icon

    internal fun loadArt() = Apps.Art(
        banner = try { ri.activityInfo.let { it.loadBanner(pm) ?: it.applicationInfo.loadBanner(pm) } } catch (e: Exception) { null }
            ?.let { Apps.fit(it, Apps.BANNER_W, Apps.BANNER_H, always = true) },
        icon = Apps.fit(try { ri.loadIcon(pm) } catch (e: Exception) { pm.defaultActivityIcon }, Apps.ICON_PX, Apps.ICON_PX),
    )
}

object Apps {
    // Shown first on a fresh install, in this order, if they're installed.
    private val DEFAULT_FAVORITES = listOf(
        "com.amazon.firebat",          // Prime Video
        "com.amazon.firetv.youtube",
        "com.netflix.ninja",
        "com.peacock.peacockfiretv",
        "tv.twitch.android.viewer",
        "com.esaba.downloader",
    )

    private const val PREFS = "home"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_HIDDEN = "hidden"

    class Art(val banner: Drawable?, val icon: Drawable)

    /** Largest a banner / icon is ever drawn (px, with the biggest tile size and focus lift). */
    internal const val BANNER_W = 512
    internal const val BANNER_H = 288
    internal const val ICON_PX = 256

    /**
     * Some apps ship 1280x720 banners and 640-2560 px icons (3-25 MB each decoded) for a tile that's
     * ~420 px wide: those are drawn once, filtered, into a bitmap of the largest size shown. The
     * drawable keeps reporting its original intrinsic size, so every layout stays exactly as it was.
     */
    internal fun fit(d: Drawable, maxW: Int, maxH: Int, always: Boolean = false): Drawable {
        val iw = d.intrinsicWidth
        val ih = d.intrinsicHeight
        if (iw <= 0 || ih <= 0 || (!always && iw <= maxW && ih <= maxH)) return d
        val scale = minOf(1f, maxW / iw.toFloat(), maxH / ih.toFloat())
        val w = maxOf(1, (iw * scale).toInt())
        val h = maxOf(1, (ih * scale).toInt())
        return try {
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            c.drawFilter = android.graphics.PaintFlagsDrawFilter(0, android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
            val old = android.graphics.Rect(d.bounds)
            d.setBounds(0, 0, w, h)
            d.draw(c)
            d.bounds = old
            // A banner that fills its whole rectangle is marked opaque, so its card can drop the tile
            // under it (ArtView) and draws one layer less.
            if (always) {
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                if (px.all { it ushr 24 == 0xFF }) bmp.setHasAlpha(false)
            }
            object : android.graphics.drawable.BitmapDrawable(android.content.res.Resources.getSystem(), bmp) {
                override fun getIntrinsicWidth() = iw
                override fun getIntrinsicHeight() = ih
            }.apply { isFilterBitmap = true }
        } catch (e: Throwable) {
            d
        }
    }

    // Count-bounded: a Fire TV banner is ~230 KB decoded, an icon 40-150 KB.
    private val artCache = LruCache<String, Art>(40)

    fun art(app: AppEntry): Art = artCache.get(app.pkg) ?: app.loadArt().also { artCache.put(app.pkg, it) }

    /** "12 apps, 2.9 MB (banner 320x180 ...)" for the memory report. */
    fun artStats(): String {
        fun bytes(d: Drawable?): Int = when (d) {
            null -> 0
            is android.graphics.drawable.BitmapDrawable -> d.bitmap?.byteCount ?: 0
            else -> maxOf(0, d.intrinsicWidth) * maxOf(0, d.intrinsicHeight) * 4
        }
        val all = artCache.snapshot()
        val total = all.values.sumOf { bytes(it.banner) + bytes(it.icon) }
        val sizes = all.entries.joinToString(" ") { (k, a) -> "${k.substringAfterLast('.')}=${a.banner?.let { "${it.intrinsicWidth}x${it.intrinsicHeight}" } ?: "-"}/${a.icon.intrinsicWidth}" }
        return "${all.size} apps, ${"%.1f".format(total / 1048576f)} MB [$sizes]"
    }

    fun load(ctx: Context): List<AppEntry> {
        artCache.evictAll() // a package was added/updated/removed: its art may have changed
        val pm = ctx.applicationContext.packageManager // not the Activity's: entries outlive a recreate
        val seen = LinkedHashMap<String, AppEntry>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val ai = ri.activityInfo
                if (ai.packageName == ctx.packageName || ai.packageName in seen) continue
                seen[ai.packageName] = AppEntry(
                    pkg = ai.packageName,
                    label = ri.loadLabel(pm).toString(),
                    system = ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    pm = pm,
                    ri = ri,
                )
            }
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    fun launchIntent(ctx: Context, pkg: String): Intent? {
        val pm = ctx.packageManager
        return (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // Per profile (favorites, hidden and Live TV pins); the first profile keeps the original file.
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS + Profiles.suffix(ctx), Context.MODE_PRIVATE)

    fun favorites(ctx: Context): MutableList<String> {
        val p = prefs(ctx)
        if (!p.contains(KEY_FAVORITES)) return DEFAULT_FAVORITES.toMutableList()
        return p.getString(KEY_FAVORITES, "")!!.split(',').filter { it.isNotEmpty() }.toMutableList()
    }

    fun saveFavorites(ctx: Context, favs: List<String>) {
        prefs(ctx).edit().putString(KEY_FAVORITES, favs.joinToString(",")).apply()
    }

    fun hidden(ctx: Context): MutableSet<String> =
        prefs(ctx).getStringSet(KEY_HIDDEN, emptySet())!!.toMutableSet()

    fun saveHidden(ctx: Context, hidden: Set<String>) {
        prefs(ctx).edit().putStringSet(KEY_HIDDEN, hidden).apply()
    }
}
