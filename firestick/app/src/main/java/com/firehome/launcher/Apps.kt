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
    /** Where the package is installed; it changes when the app is updated. */
    internal val source: String get() = ri.activityInfo.applicationInfo.sourceDir.orEmpty()

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

    /** Largest a banner / icon is drawn (px): the Apps grid's 5 columns at 1080p with the focus lift. */
    internal const val BANNER_W = 384
    internal const val BANNER_H = 216
    internal const val ICON_PX = 160

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
            // A banner that fills its whole rectangle is kept in RGB_565: half the memory, and opaque, so the
            // renderer can skip what's under it.
            var out = bmp
            if (always) {
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                if (px.all { it ushr 24 == 0xFF }) bmp.copy(android.graphics.Bitmap.Config.RGB_565, false)?.let { out = it; bmp.recycle() }
            }
            object : android.graphics.drawable.BitmapDrawable(android.content.res.Resources.getSystem(), out) {
                override fun getIntrinsicWidth() = iw
                override fun getIntrinsicHeight() = ih
            }.apply { isFilterBitmap = true }
        } catch (e: Throwable) {
            d
        }
    }

    /**
     * Bounded by bytes: 1/32 of the app's heap class, 2 to 8 MB (about 4 MB on a Fire TV Stick HD: some 15 banners
     * at 166 KB plus icons at 100 KB). Emptied when Home is hidden or memory runs low ([trim]); a card that is
     * still showing keeps its drawable.
     */
    @Volatile private var artCache = cache(4 * 1024 * 1024)

    private fun cache(bytes: Int) = object : LruCache<String, Art>(bytes) {
        override fun sizeOf(key: String, v: Art) = maxOf(1, artBytes(v.banner) + artBytes(v.icon))
    }

    private fun artBytes(d: Drawable?): Int = when (d) {
        null -> 0
        is android.graphics.drawable.BitmapDrawable -> d.bitmap?.allocationByteCount ?: 0
        else -> 0 // vector or shape: drawn, nothing decoded
    }

    /** Sizes the cache from this device's heap class (once, at the first load). */
    private fun sizeFor(ctx: Context) {
        if (sized) return
        sized = true
        val mb = (ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.memoryClass ?: 128
        val bytes = (mb * 1024 * 1024 / 32).coerceIn(2 * 1024 * 1024, 8 * 1024 * 1024)
        if (bytes != artCache.maxSize()) artCache = cache(bytes)
    }
    @Volatile private var sized = false

    fun art(app: AppEntry): Art = artCache.get(app.pkg) ?: app.loadArt().also { artCache.put(app.pkg, it) }

    /** Home is hidden (Dream Player in front, another app) or memory is low: decoded art goes. */
    fun trim() = artCache.evictAll()

    /** "12 apps, 2.9 MB (banner 320x180 ...)" for the memory report. */
    fun artStats(): String {
        val all = artCache.snapshot()
        val total = all.values.sumOf { artBytes(it.banner) + artBytes(it.icon) }
        val sizes = all.entries.joinToString(" ") { (k, a) -> "${k.substringAfterLast('.')}=${a.banner?.let { "${it.intrinsicWidth}x${it.intrinsicHeight}" } ?: "-"}/${a.icon.intrinsicWidth}" }
        return "${all.size} apps, ${"%.1f".format(total / 1048576f)} MB [$sizes]"
    }

    /** Each package's install path when its art was cached: an update moves it, and only then is the art dropped. */
    private val artSource = HashMap<String, String>()

    /** Any thread. */
    fun load(ctx: Context): List<AppEntry> {
        sizeFor(ctx)
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
        // Only apps that were updated or removed lose their decoded art (every package broadcast used to clear
        // it all, and each visible banner was decoded again on the UI thread).
        synchronized(artSource) {
            val now = seen.mapValues { (_, e) -> e.source }
            for ((pkg, src) in artSource) if (now[pkg] != src) artCache.remove(pkg)
            artSource.clear()
            artSource.putAll(now)
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
