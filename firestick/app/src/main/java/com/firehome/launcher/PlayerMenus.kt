package com.firehome.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dream Player preferences: subtitle appearance and preferred audio / subtitle languages,
 * stored per profile. [applyStyle] pushes the appearance to Media3's SubtitleView.
 */
object PlayerPrefs {
    val SIZES = listOf("S", "M", "L", "XL")
    private val SIZE_SCALE = floatArrayOf(0.78f, 1f, 1.25f, 1.5f)
    val STYLES = listOf("Classic", "Boxed", "Yellow")
    val POSITIONS = listOf("Low", "Standard", "Raised")
    private val POS_FRACTION = floatArrayOf(0.045f, 0.08f, 0.16f)
    /** Subtitles sit above the controls while they are on screen. */
    private const val RAISED_FOR_CONTROLS = 0.30f
    const val OFF = "off"
    private const val BASE_FRACTION = 0.0533f // Media3's default text size fraction
    const val YELLOW = 0xFFFFE45C.toInt()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("player", Context.MODE_PRIVATE)
    private fun k(ctx: Context, key: String) = key + Profiles.suffix(ctx)

    fun size(ctx: Context) = prefs(ctx).getInt(k(ctx, "subSize"), 1).coerceIn(0, SIZES.size - 1)
    fun style(ctx: Context) = prefs(ctx).getInt(k(ctx, "subStyle"), 0).coerceIn(0, STYLES.size - 1)
    fun position(ctx: Context) = prefs(ctx).getInt(k(ctx, "subPos"), 1).coerceIn(0, POSITIONS.size - 1)
    fun setSize(ctx: Context, v: Int) = prefs(ctx).edit().putInt(k(ctx, "subSize"), v).apply()
    fun setStyle(ctx: Context, v: Int) = prefs(ctx).edit().putInt(k(ctx, "subStyle"), v).apply()
    fun setPosition(ctx: Context, v: Int) = prefs(ctx).edit().putInt(k(ctx, "subPos"), v).apply()
    fun sizeScale(ctx: Context) = SIZE_SCALE[size(ctx)]

    /** Normalized language ("en"), or null when unknown. */
    fun audioLang(ctx: Context): String? = prefs(ctx).getString(k(ctx, "audioLang"), null)
    /** "off", a normalized language, or null when never chosen. */
    fun subLang(ctx: Context): String? = prefs(ctx).getString(k(ctx, "subLang"), null)
    fun setAudioLang(ctx: Context, lang: String?) { if (lang != null) prefs(ctx).edit().putString(k(ctx, "audioLang"), lang).apply() }
    fun setSubLang(ctx: Context, lang: String?) { if (lang != null) prefs(ctx).edit().putString(k(ctx, "subLang"), lang).apply() }

    /** Titles (a movie, or a whole show) whose subtitles the viewer chose in the player: automatic subtitles leave them alone. */
    private fun titleOf(r: PlaybackRequest) = (if (r.type == "episode") "tv:" else "movie:") + r.tmdbId
    fun subsChosen(ctx: Context, r: PlaybackRequest): Boolean =
        r.tmdbId > 0 && prefs(ctx).getStringSet(k(ctx, "subsChosen"), null)?.contains(titleOf(r)) == true
    fun noteSubsChosen(ctx: Context, r: PlaybackRequest) {
        if (r.tmdbId <= 0 || r.trailer) return
        val now = prefs(ctx).getStringSet(k(ctx, "subsChosen"), null).orEmpty()
        if (titleOf(r) in now) return
        // At most 200: a title dropped from the set only gets automatic subtitles again.
        prefs(ctx).edit().putStringSet(k(ctx, "subsChosen"), (now.take(199) + titleOf(r)).toSet()).apply()
    }

    // ---- Volume Boost (ExoEngine's LoudnessEnhancer), per profile ----

    /** Off, then about +3, +6 and +10 dB: the top step stays modest so loud scenes don't clip badly. */
    val BOOST_LABELS = listOf("Off", "+25%", "+50%", "+100%")
    private val BOOST_MB = intArrayOf(0, 300, 600, 1000)
    val BOOST_MAX_MB get() = BOOST_MB.last()

    fun boostLevel(ctx: Context) = prefs(ctx).getInt(k(ctx, "boost"), 0).coerceIn(0, BOOST_MB.size - 1)
    fun setBoostLevel(ctx: Context, v: Int) = prefs(ctx).edit().putInt(k(ctx, "boost"), v.coerceIn(0, BOOST_MB.size - 1)).apply()
    fun boostMb(ctx: Context) = BOOST_MB[boostLevel(ctx)]
    fun boostMbAt(level: Int) = BOOST_MB[level.coerceIn(0, BOOST_MB.size - 1)]
    /** "+50%" for 600 mB (the nearest step's name). */
    fun boostLabel(mb: Int): String = BOOST_LABELS[BOOST_MB.indices.minByOrNull { kotlin.math.abs(BOOST_MB[it] - mb) } ?: 0]

    private val BIBLIOGRAPHIC = mapOf(
        "fre" to "fr", "ger" to "de", "chi" to "zh", "dut" to "nl", "gre" to "el", "cze" to "cs", "rum" to "ro",
        "slo" to "sk", "ice" to "is", "per" to "fa", "wel" to "cy", "arm" to "hy", "baq" to "eu", "geo" to "ka",
        "mac" to "mk", "mao" to "mi", "may" to "ms", "alb" to "sq", "bur" to "my", "tib" to "bo",
    )
    private val iso3: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (c in Locale.getISOLanguages()) try { m[Locale(c).isO3Language] = c } catch (e: Exception) {}
        m
    }

    fun normLang(l: String?): String? {
        val p = l?.trim()?.lowercase(Locale.US)?.substringBefore('-')?.substringBefore('_') ?: return null
        if (p.isEmpty() || p == "und" || p == "unknown" || p == "zxx" || p == "mis") return null
        if (p.length == 3) return BIBLIOGRAPHIC[p] ?: iso3[p] ?: p
        return p
    }

    fun displayLang(code: String?): String = code?.let { Locale(it).getDisplayLanguage(Locale.getDefault()).takeIf { n -> n.isNotBlank() } } ?: "Unknown"

    // ---- SubtitleView ----

    private var cachedView: java.lang.ref.WeakReference<androidx.media3.ui.SubtitleView>? = null

    /** The core's SubtitleView: found once in the player's window (no hard dependency on the activity). */
    fun findSubtitleView(root: View): androidx.media3.ui.SubtitleView? {
        cachedView?.get()?.let { if (it.isAttachedToWindow) return it }
        val found = find(root.rootView)
        cachedView = found?.let { java.lang.ref.WeakReference(it) }
        return found
    }

    private fun find(v: View): androidx.media3.ui.SubtitleView? {
        if (v is androidx.media3.ui.SubtitleView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    fun applyStyle(ctx: Context, root: View, controlsUp: Boolean) {
        val sv = findSubtitleView(root) ?: return
        try {
            val face = Ui.medium(Theme.typeface(ctx))
            val edge = androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
            val style = when (style(ctx)) {
                1 -> androidx.media3.ui.CaptionStyleCompat(Color.WHITE, Color.argb(190, 8, 6, 18), Color.TRANSPARENT,
                    androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE, Color.TRANSPARENT, face)
                2 -> androidx.media3.ui.CaptionStyleCompat(YELLOW, Color.TRANSPARENT, Color.TRANSPARENT, edge, Color.argb(230, 0, 0, 0), face)
                else -> androidx.media3.ui.CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT, edge, Color.argb(230, 0, 0, 0), face)
            }
            sv.setApplyEmbeddedStyles(false)
            sv.setApplyEmbeddedFontSizes(false)
            sv.setStyle(style)
            sv.setFractionalTextSize(BASE_FRACTION * sizeScale(ctx))
            val pos = POS_FRACTION[position(ctx)]
            sv.setBottomPaddingFraction(if (controlsUp) maxOf(pos, RAISED_FOR_CONTROLS) else pos)
        } catch (e: Throwable) {
            Log.w("DreamPlayer", "subtitle style: $e")
        }
    }

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    fun raiseForControls(ctx: Context, root: View, controlsUp: Boolean) {
        val sv = findSubtitleView(root) ?: return
        val pos = POS_FRACTION[position(ctx)]
        try { sv.setBottomPaddingFraction(if (controlsUp) maxOf(pos, RAISED_FOR_CONTROLS) else pos) } catch (e: Throwable) {}
    }

    /**
     * A player sheet (X-Ray, Episodes, Audio & Subtitles, Change Source, post-play) is up: the subtitles fade
     * out of its way, and back in when it closes. Only their alpha: the cues keep coming, so the line on
     * screen is the right one the moment they return.
     */
    fun hideSubtitles(root: View, hidden: Boolean) {
        val sv = findSubtitleView(root) ?: return
        sv.animate().cancel()
        sv.animate().alpha(if (hidden) 0f else 1f).setDuration(if (hidden) Ui.OVERLAY_OUT else Ui.OVERLAY_IN)
            .setInterpolator(if (hidden) Ui.EXIT else Ui.EASE).start()
    }
}

/** Synopsis and facts for the Info sheet and the paused card. */
class PlayerInfo(val title: String, val overview: String, val meta: String, val source: String?)

object PlayerMeta {
    /** TMDB's own score shows once this many people voted (as [Ratings.audienceFor]). */
    private const val SCORE_VOTES = 150

    /** Blocking: TMDB (through the existing bounded caches) and the source name. */
    fun load(ctx: Context, req: PlaybackRequest): PlayerInfo {
        var title = req.episodeTitle ?: req.title
        var overview = ""
        val meta = ArrayList<String>()
        if (Tmdb.hasKey(ctx) && req.tmdbId > 0) {
            if (req.type == "episode" && req.season != null && req.episode != null) {
                val ep = WatchHistory.tmdb(ctx, "/tv/${req.tmdbId}/season/${req.season}/episode/${req.episode}")
                if (ep != null) {
                    ep.optString("name").takeIf { it.isNotBlank() && it != "null" }?.let { title = it }
                    overview = ep.optString("overview").takeIf { it != "null" }.orEmpty()
                    airDate(ep.optString("air_date"))?.let { meta += it }
                    ep.optInt("runtime").takeIf { it > 0 }?.let { meta += runtime(it) }
                    ep.optDouble("vote_average", 0.0).takeIf { it >= 1.0 && ep.optInt("vote_count") >= SCORE_VOTES }
                        ?.let { meta += "TMDB " + String.format(Locale.US, "%.1f", it) }
                }
                if (overview.isBlank()) {
                    try { overview = Tmdb.details(ctx, Title(req.tmdbId, "tv", req.title, "", null, null, "", 0.0)).overview } catch (e: Exception) {}
                }
            } else {
                try {
                    val d = Tmdb.details(ctx, Title(req.tmdbId, "movie", req.title, "", null, null, req.year?.toString() ?: "", 0.0))
                    overview = d.overview
                    req.year?.let { meta += it.toString() }
                    if (d.runtime.isNotBlank()) meta += d.runtime
                    if (d.genres.isNotEmpty()) meta += d.genres.take(2).joinToString(", ")
                } catch (e: Exception) {}
            }
        }
        if (meta.isEmpty()) req.year?.let { meta += it.toString() }
        return PlayerInfo(title, overview, meta.joinToString("  ·  "), sourceName(ctx, req.player))
    }

    fun runtime(min: Int) = when {
        min >= 60 -> if (min % 60 == 0) "${min / 60}h" else "${min / 60}h ${min % 60}m"
        else -> "$min min"
    }

    /** "2024-03-01" as "Mar 1, 2024". Any thread: SimpleDateFormat isn't thread-safe, so one per call. */
    fun airDate(raw: String): String? = try {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw)?.let { SimpleDateFormat("MMM d, yyyy", Locale.US).format(it) }
    } catch (e: Exception) { null }

    private fun sourceName(ctx: Context, id: String?): String? {
        if (id.isNullOrBlank()) return null
        val tail = id.substringAfterLast('.').substringAfterLast(':').removeSuffix(".json")
        return tail.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.US) }
    }
}

/**
 * The player's frosted side sheets: Audio & Subtitles, Info, Episodes. One sheet at a time,
 * floating at the right with fully rounded corners over a soft scrim (no snapshot: the video is a
 * SurfaceView). Rows use the Dream focus glow; every animation ends.
 */
class PlayerMenus(
    private val host: PlayerHost,
    private val kit: PremiumKit,
    private val root: FrameLayout,
    private val cb: Callbacks,
) {
    interface Callbacks {
        fun toast(text: String)
        /** The sheet closed by itself or by Back; [opener] names the action that opened it. */
        fun sheetClosed(opener: String)
        /** Cast & Characters (X-Ray), opened from inside Info. */
        fun openCast() {}
        fun canOpenCast(): Boolean = false
        fun info(): PlayerInfo?
        fun loadInfo(done: (PlayerInfo?) -> Unit)
        fun subtitleStyleChanged()
        /** The user picked a track by hand (stops auto-apply for this stream). */
        fun userPicked(kind: String)
        fun playEpisode(season: Int, episode: Int, title: String?, startMs: Long)
    }

    private val ctx = root.context
    private val main = Handler(Looper.getMainLooper())
    private val Int.dp get() = (this * ctx.resources.displayMetrics.density).toInt()
    private val layer = FlatFrame(ctx).apply { visibility = View.GONE; clipChildren = false }
    private val scrim = object : View(ctx) { override fun hasOverlappingRendering() = false }
    private var panel: FrameLayout? = null
    private var kind = ""
    private var gen = 0
    private val images = ArrayList<ImageView>()

    /** The sheets' layer in the overlay root (the overlay tells its own views from other components'). */
    val layerView: View get() = layer
    val isOpen get() = panel != null
    val openKind get() = kind
    fun focusRoot(): ViewGroup? = panel

    init {
        scrim.background = PlayerLook.sideScrim(ctx)
        layer.addView(scrim, MATCH_PARENT, MATCH_PARENT)
        root.addView(layer, MATCH_PARENT, MATCH_PARENT)
    }

    // ---- sheet frame ----
    //
    // Clipping, outermost in: the card clips everything to its rounded outline. Inside it a column
    // holds a fixed header (kicker, title, optional chip row) and one scroller; the column keeps
    // clipChildren on, so each scroller is cut at its own viewport and scrolled rows can never
    // reach the header or leave the card. Lift room for focused rows and chips is padding inside
    // each scroller ([Ui.liftRoom]), reaching into the card's padding, so lifts and rings aren't cut.

    private fun frame(widthDp: Int, kicker: String?, title: String): Pair<FrameLayout, LinearLayout> {
        closeNow()
        val fit = ScreenFit.insets(ctx)
        val p = FlatFrame(ctx).apply {
            background = PlayerLook.sheet(ctx)
            setPadding(SHEET_PAD_H.dp, 26.dp, SHEET_PAD_H.dp, SHEET_PAD_BOTTOM.dp)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            // The column may reach into this padding (scrollers' lift room); the outline is the clip line.
            clipChildren = false
            clipToPadding = false
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(8.dp, 0, 8.dp, 0) }
        if (!kicker.isNullOrBlank()) head.addView(kit.kicker(kicker).apply { setTextColor(Ui.alpha(kit.text, 0.62f)) })
        head.addView(kit.label(title, Ui.T_HEADLINE, kit.text, kit.semibold, Ui.TRACK_TITLE).apply {
            if (!kicker.isNullOrBlank()) setPadding(0, 6.dp, 0, 0)
        })
        col.addView(head)
        p.addView(col, MATCH_PARENT, MATCH_PARENT)
        layer.addView(p, FrameLayout.LayoutParams(widthDp.dp, MATCH_PARENT, Gravity.END).apply {
            setMargins(0, 26.dp + fit[1], 30.dp + fit[0], 26.dp + fit[1])
        })
        panel = p
        layer.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        p.alpha = 0f
        p.translationX = 40.dp.toFloat()
        p.animate().alpha(1f).translationX(0f).setDuration(Ui.SHEET_IN).setInterpolator(Ui.EASE).start()
        return p to col
    }

    /**
     * The sheet's one vertical list: fills the rest of the card, clipped at its own viewport (its
     * parent column clips), with [LIFT_H] of lift room at the sides and [LIFT_V] above and below. No fading edge (an
     * offscreen layer per frame, and the band the old layout showed mid-list).
     */
    private fun scroller(col: LinearLayout, list: LinearLayout): SmoothScrollView {
        list.orientation = LinearLayout.VERTICAL
        list.clipChildren = false
        list.clipToPadding = false
        // Vertical lift room lives in the list itself (first and last rows); while scrolling, the
        // glide margin keeps the focused row clear of the viewport edge.
        list.setPadding(8.dp, LIFT_V.dp, 8.dp, LIFT_V.dp)
        val sv = SmoothScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = false
            margin = 16.dp
            clipChildren = false
            addView(list, MATCH_PARENT, WRAP_CONTENT)
        }
        // The list ends a little above the card's bottom edge (inside its padding), never on it.
        // Ends inside the card's bottom padding (the list's own padding keeps the last row off it).
        col.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = (14 - LIFT_V).dp; bottomMargin = -LIFT_V.dp })
        Ui.liftRoom(sv, LIFT_H)
        return sv
    }

    /** Closes the open sheet (animated) and tells the overlay. */
    fun close() {
        val p = panel ?: return
        val k = kind
        panel = null
        kind = ""
        gen++
        main.removeCallbacks(seasonSwitch)
        clearImages()
        scrim.animate().alpha(0f).setDuration(Ui.SHEET_OUT).setInterpolator(Ui.EXIT).start()
        p.animate().alpha(0f).translationX(30.dp.toFloat()).setDuration(Ui.SHEET_OUT).setInterpolator(Ui.EXIT).withEndAction {
            layer.removeView(p)
            if (panel == null) layer.visibility = View.GONE
        }.start()
        cb.sheetClosed(k)
    }

    /** Drops the sheet at once (new stream, overlay stopping). */
    fun closeNow() {
        val p = panel ?: return
        panel = null
        kind = ""
        gen++
        main.removeCallbacks(seasonSwitch)
        clearImages()
        p.animate().cancel()
        layer.removeView(p)
        layer.visibility = View.GONE
    }

    fun release() {
        closeNow()
        PremiumKit.detach(layer)
    }

    private fun clearImages() {
        for (iv in images) { iv.animate().cancel(); iv.setImageDrawable(null); iv.tag = null }
        images.clear()
    }

    /** Sheet-specific keys before the overlay's generic focus move; true if handled. */
    fun onKey(e: KeyEvent, focused: View?): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        if (kind == "audio" && focused?.tag == "boost") {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (e.repeatCount == 0) stepBoost(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (e.repeatCount == 0) stepBoost(+1); return true }
            }
        }
        if (kind == "info" && focused is SmoothScrollView) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { focused.glideTo(focused.scrollY + 120.dp, 260); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { focused.glideTo(focused.scrollY - 120.dp, 260); return true }
            }
        }
        if (kind == "episodes") {
            val tag = focused?.tag as? String ?: return false
            if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && tag.startsWith("season:")) {
                flushSeason()
                (epList?.findViewWithTag<View>(PLAYING_TAG) ?: epList?.getChildAt(0)?.takeIf { it.isFocusable })?.requestFocus()
                return true
            }
            if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP && tag.startsWith("ep:") && epList?.getChildAt(0) == focused) {
                (chipRow?.findViewWithTag<View>("season:$season") ?: chipRow?.getChildAt(0))?.requestFocus()
                return true
            }
        }
        return false
    }

    // ---- shared rows ----

    private val focusedState = intArrayOf(android.R.attr.state_focused)
    private val selectedState = intArrayOf(android.R.attr.state_selected)

    private fun states(on: Int, rest: Int) = ColorStateList(arrayOf(focusedState, IntArray(0)), intArrayOf(on, rest))

    /** A radio-style option row: check mark when [checked], focus glow, optional second line. */
    private fun optionRow(tag: String, title: String, sub: String?, checked: Boolean, enabled: Boolean = true, onClick: () -> Unit): View {
        val rest = if (enabled) kit.text else Ui.alpha(kit.text, 0.42f)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 0, 16.dp, 0)
            minimumHeight = (if (sub != null) 60 else 48).dp
            this.tag = tag
            isFocusable = true
            isClickable = true
            background = StateListDrawable().apply {
                addState(focusedState, Ui.focusFill(ctx, kit.select, Ui.R_MD))
                addState(IntArray(0), if (checked) Ui.rounded(ctx, Ui.alpha(kit.text, 0.07f), Ui.R_MD) else ColorDrawable(Color.TRANSPARENT))
                setEnterFadeDuration(Ui.FOCUS_IN.toInt())
                setExitFadeDuration(Ui.FOCUS_OUT.toInt())
            }
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, has -> Ui.lift(v, has, Ui.LIFT_ROW) }
        }
        // Cinema: the glow and a pearl wash on focus (text stays pearl), the chosen one's check in the glow hue.
        PlayerLook.glowRow(row, Ui.R_MD, if (checked) PlayerLook.chosen(ctx, Ui.R_MD) else null)
        val onFocus = PlayerLook.ink(ctx)
        val check = ImageView(ctx).apply {
            setImageDrawable(PlayerIcon(PlayerIcon.CHECK))
            // Cinema: the check wears Play's current hue (the shared rainbow clock), the only accent on the sheet.
            imageTintList = ColorStateList.valueOf(Rainbow.fadeColor())
            visibility = if (checked) View.VISIBLE else View.INVISIBLE
            isDuplicateParentStateEnabled = true
        }
        row.addView(check, LinearLayout.LayoutParams(20.dp, 20.dp).apply { marginEnd = 12.dp })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8.dp, 0, 8.dp); isDuplicateParentStateEnabled = true }
        texts.addView(kit.label(title, Ui.T_BODY, rest, if (checked) kit.semibold else kit.medium).apply {
            setTextColor(states(onFocus, rest))
            isDuplicateParentStateEnabled = true
        })
        if (sub != null) texts.addView(kit.label(sub, Ui.T_CAPTION, Ui.alpha(kit.text, if (enabled) 0.6f else 0.4f), kit.font).apply {
            setTextColor(states(Ui.alpha(PlayerLook.ink(ctx), 0.70f), Ui.alpha(kit.text, if (enabled) 0.6f else 0.4f)))
            isDuplicateParentStateEnabled = true
            setPadding(0, 4.dp, 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 4.dp }
        return row
    }

    private fun heading(t: String, topDp: Int = 14) = kit.kicker(t).apply {
        setPadding(12.dp, topDp.dp, 0, 8.dp)
        setTextColor(Ui.alpha(kit.text, 0.62f)) // neutral caps: the rainbow is the only color
    }

    /** Glass chip like the details page's season chips: glow when focused, brighter while chosen; [onFocus] runs too. */
    private fun chip(label: String, tag: String, selected: Boolean, onFocus: ((Boolean) -> Unit)? = null, onClick: (View) -> Unit) = TextView(ctx).apply {
        text = label
        this.tag = tag
        textSize = Ui.T_LABEL
        typeface = kit.semibold
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        isSelected = selected
        setPadding(16.dp, 0, 16.dp, 0)
        minWidth = 44.dp
        isFocusable = true
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, 34.dp).apply { marginEnd = 8.dp }
        setOnClickListener { onClick(it) }
        // Cinema's chip (CinemaKit.chip): glass at rest, brighter while chosen, pearl with ink and the glow on focus.
        val (bg, glow) = PlayerLook.control(ctx, 17, selected = true)
        background = bg
        setTextColor(ColorStateList(arrayOf(focusedState, selectedState, IntArray(0)), intArrayOf(PlayerLook.ink(ctx), kit.text, Ui.alpha(kit.text, 0.74f))))
        setOnFocusChangeListener { v, has -> PlayerLook.focus(v, glow, has, 1.06f); onFocus?.invoke(has) }
    }

    private fun focusLater(v: View?) {
        v ?: return
        v.post { if (panel != null) v.requestFocus() }
    }

    // ---- Audio & Subtitles ----

    private var preview: TextView? = null
    private var previewBox: FrameLayout? = null

    fun openAudio(focusTag: String? = null) {
        val e = host.engine
        val (_, col) = frame(440, null, "Audio & Subtitles")
        kind = "audio"
        val list = LinearLayout(ctx)
        scroller(col, list)
        var first: View? = null

        list.addView(heading("Audio", 4))
        val audio = e.tracks("audio")
        if (audio.isEmpty()) list.addView(kit.label("Default Audio", Ui.T_BODY, kit.dim, kit.medium).apply { setPadding(44.dp, 6.dp, 12.dp, 10.dp) })
        // Every track in the file: language, then codec and channels; ones this TV can't decode stay listed, greyed.
        for (t in audio) {
            val name = t.title?.takeIf { it.isNotBlank() } ?: t.label.ifBlank { PlayerPrefs.displayLang(PlayerPrefs.normLang(t.language)) }
            val detail = t.detail?.takeIf { it.isNotBlank() }
            val sub = if (!t.supported) listOfNotNull(detail, "Not Supported on This TV").joinToString("  ·  ") else detail
            val r = optionRow("a:${t.id}", name, sub, t.selected, t.supported) {
                if (!t.supported) { cb.toast("This audio format can't play on this TV"); return@optionRow }
                if (!t.selected) {
                    e.selectTrack("audio", t.id)
                    PlayerPrefs.setAudioLang(ctx, PlayerPrefs.normLang(t.language))
                    cb.userPicked("audio")
                    cb.toast("Audio  ·  $name")
                }
                rebuildAudio("a:${t.id}")
            }
            list.addView(r)
            if (t.selected && first == null) first = r
        }
        list.addView(boostRow())
        // The audio's real output becomes known (PCM or a Dolby / DTS bitstream): the boost row follows it.
        (e as? ExoEngine)?.onPassthrough = { if (kind == "audio") main.post { if (kind == "audio") openAudioInPlace(panel?.findFocus()?.tag as? String) } }

        list.addView(heading("Subtitles"))
        val text = e.tracks("text")
        val anyOn = text.any { it.selected }
        val off = optionRow("t:off", "Off", null, !anyOn) {
            if (anyOn) {
                e.selectTrack("text", null)
                PlayerPrefs.setSubLang(ctx, PlayerPrefs.OFF)
                cb.userPicked("text")
                cb.toast("Subtitles  ·  Off")
            }
            rebuildAudio("t:off")
        }
        list.addView(off)
        for (t in text) {
            val sub = if (!t.supported) "Not Supported on This TV" else null
            list.addView(optionRow("t:${t.id}", t.label.ifBlank { PlayerPrefs.displayLang(PlayerPrefs.normLang(t.language)) }, sub, t.selected, t.supported) {
                if (!t.supported) { cb.toast("These subtitles can't be shown on this device"); return@optionRow }
                if (!t.selected) {
                    e.selectTrack("text", t.id)
                    PlayerPrefs.setSubLang(ctx, PlayerPrefs.normLang(t.language))
                    cb.userPicked("text")
                    cb.toast("Subtitles  ·  " + t.label)
                }
                rebuildAudio("t:${t.id}")
            })
        }
        if (first == null && audio.isEmpty()) first = off

        list.addView(heading("Subtitle Appearance"))
        list.addView(buildPreview())
        list.addView(segRow("Size", PlayerPrefs.SIZES, PlayerPrefs.size(ctx), "size") { PlayerPrefs.setSize(ctx, it) })
        list.addView(segRow("Style", PlayerPrefs.STYLES, PlayerPrefs.style(ctx), "style") { PlayerPrefs.setStyle(ctx, it) })
        list.addView(segRow("Position", PlayerPrefs.POSITIONS, PlayerPrefs.position(ctx), "pos") { PlayerPrefs.setPosition(ctx, it) })

        val target = focusTag?.let { list.findViewWithTag<View>(it) } ?: first ?: list.findViewWithTag("t:off")
        focusLater(target)
    }

    // ---- Volume Boost ----

    private var boostValue: TextView? = null

    /** Why the boost can't apply: the audio goes out as a bitstream the TV never touches. Null when it can. */
    private fun boostBlocked(): String? {
        val enc = (host.engine as? ExoEngine)?.passthrough ?: 0
        return when (enc) {
            0 -> null
            androidx.media3.common.C.ENCODING_DTS, androidx.media3.common.C.ENCODING_DTS_HD -> "Not Available with DTS Audio"
            androidx.media3.common.C.ENCODING_AC3, androidx.media3.common.C.ENCODING_E_AC3, androidx.media3.common.C.ENCODING_E_AC3_JOC,
            androidx.media3.common.C.ENCODING_AC4, androidx.media3.common.C.ENCODING_DOLBY_TRUEHD -> "Not Available with Dolby Audio"
            else -> "Not Available with Surround Audio"
        }
    }

    /** "Volume Boost" with its level at the right: OK steps through Off, +25%, +50%, +100%; Left / Right step down / up. */
    private fun boostRow(): View {
        val blocked = boostBlocked()
        val row = optionRow("boost", "Volume Boost", blocked, checked = false, enabled = blocked == null) {
            if (boostBlocked() != null) cb.toast("Volume Boost can't change Dolby or DTS audio sent to a sound system")
            else stepBoost(+1, wrap = true)
        } as LinearLayout
        val rest = if (blocked == null) Ui.alpha(kit.text, 0.72f) else Ui.alpha(kit.text, 0.42f)
        val value = kit.label(if (blocked == null) PlayerPrefs.BOOST_LABELS[PlayerPrefs.boostLevel(ctx)] else "", Ui.T_BODY, rest, kit.semibold).apply {
            fontFeatureSettings = "tnum"
            setTextColor(states(PlayerLook.ink(ctx), rest))
            isDuplicateParentStateEnabled = true
            visibility = if (blocked == null) View.VISIBLE else View.GONE
        }
        row.addView(value, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 16.dp })
        boostValue = value
        return row
    }

    private fun stepBoost(dir: Int, wrap: Boolean = false) {
        if (boostBlocked() != null) return
        val n = PlayerPrefs.BOOST_LABELS.size
        val cur = PlayerPrefs.boostLevel(ctx)
        val next = if (wrap) (cur + dir + n) % n else (cur + dir).coerceIn(0, n - 1)
        if (next == cur) return
        PlayerPrefs.setBoostLevel(ctx, next)
        (host.engine as? ExoEngine)?.setBoost(PlayerPrefs.boostMbAt(next))
        boostValue?.text = PlayerPrefs.BOOST_LABELS[next]
        cb.toast("Volume Boost  ·  " + PlayerPrefs.BOOST_LABELS[next])
    }

    private fun rebuildAudio(focusTag: String) {
        if (kind != "audio") return
        // Let the engine apply the selection, then redraw the checks.
        main.postDelayed({ if (kind == "audio") openAudioInPlace(focusTag) }, 120)
    }

    /** Rebuilds the audio sheet's content without the entrance animation (tracks changed). */
    private fun openAudioInPlace(focusTag: String?) {
        val p = panel ?: return
        val sv = findScroll(p)
        val y = sv?.scrollY ?: 0
        layer.removeView(p)
        panel = null
        openAudio(focusTag)
        panel?.let { np ->
            np.animate().cancel(); np.alpha = 1f; np.translationX = 0f
            scrim.animate().cancel(); scrim.alpha = 1f
            findScroll(np)?.let { s -> s.post { s.scrollTo(0, y) } }
        }
    }

    private fun findScroll(v: View): SmoothScrollView? {
        if (v is SmoothScrollView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Tracks changed while the sheet is open: refresh it, keeping focus. */
    fun tracksChanged() {
        if (kind != "audio") return
        val tag = panel?.findFocus()?.tag as? String
        openAudioInPlace(tag)
    }

    private fun buildPreview(): View {
        val box = FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                (intArrayOf(PlayerLook.shade(ctx), Ui.mix(kit.tile, kit.text, 0.08f)))).apply {
                cornerRadius = Ui.R_MD.dp.toFloat()
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        val t = TextView(ctx).apply {
            text = "I'll be right there."
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        box.addView(t, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM))
        preview = t
        previewBox = box
        stylePreview()
        return box.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 92.dp).apply { setMargins(4.dp, 0, 4.dp, 10.dp) }
        }
    }

    private fun stylePreview() {
        val t = preview ?: return
        val style = PlayerPrefs.style(ctx)
        t.typeface = Ui.medium(Theme.typeface(ctx))
        t.textSize = 13.5f * PlayerPrefs.sizeScale(ctx)
        t.setTextColor(if (style == 2) PlayerPrefs.YELLOW else Color.WHITE)
        if (style == 1) {
            t.setShadowLayer(0f, 0f, 0f, 0)
            t.background = Ui.rounded(ctx, Color.argb(190, 8, 6, 18), 4)
            t.setPadding(8.dp, 4.dp, 8.dp, 4.dp)
        } else {
            t.background = null
            t.setPadding(0, 0, 0, 0)
            t.setShadowLayer(3f, 0f, 1f, Color.BLACK)
        }
        val bottom = when (PlayerPrefs.position(ctx)) { 0 -> 6; 2 -> 26; else -> 13 }
        (t.layoutParams as? FrameLayout.LayoutParams)?.let { it.bottomMargin = bottom.dp; t.layoutParams = it }
    }

    private fun segRow(label: String, options: List<String>, current: Int, key: String, set: (Int) -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 6.dp, 4.dp, 6.dp)
            clipChildren = false
            clipToPadding = false
        }
        row.addView(kit.label(label, Ui.T_BODY, kit.text, kit.medium), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val chips = ArrayList<TextView>()
        for ((i, o) in options.withIndex()) {
            val c = chip(o, "$key:$i", i == current) { v ->
                for (x in chips) x.isSelected = x === v
                set(i)
                stylePreview()
                cb.subtitleStyleChanged()
            }
            chips += c
            row.addView(c)
        }
        (chips.lastOrNull()?.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0
        row.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        return row
    }

    // ---- Info ----

    fun openInfo() {
        val req = host.request
        val kicker = when {
            req?.type == "episode" && req.season != null && req.episode != null -> "${req.title}  ·  S${req.season} · E${req.episode}"
            req != null -> req.year?.let { "Movie  ·  $it" } ?: "Movie"
            else -> null
        }
        val (_, col) = frame(500, kicker, req?.let { it.episodeTitle ?: it.title } ?: "Now Playing")
        kind = "info"
        val list = LinearLayout(ctx)
        val sv = scroller(col, list)
        sv.isFocusable = true
        sv.keyScroll = false
        val myGen = gen
        val metaView = kit.label("", Ui.T_LABEL, Ui.alpha(kit.text, 0.72f), kit.medium).apply { setPadding(4.dp, 0, 4.dp, 0) }
        val synopsis = kit.label("", Ui.T_BODY, Ui.alpha(kit.text, 0.88f), kit.font, lines = 0).apply {
            setLineSpacing(0f, 1.25f)
            setPadding(4.dp, 10.dp, 4.dp, 0)
        }
        // Cast & Characters lives here now (not in the controls row): first stop, above the synopsis.
        var castChip: View? = null
        if (cb.canOpenCast()) {
            val castRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(4.dp, 0, 0, 14.dp); clipChildren = false; clipToPadding = false }
            castChip = chip("Cast & Characters", "cast", selected = false) { closeNow(); cb.openCast() }
            castRow.addView(castChip)
            list.addView(castRow)
        }
        list.addView(metaView)
        list.addView(synopsis)
        val facts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        list.addView(heading("Now Playing", 22))
        list.addView(facts)
        val e = host.engine
        val vi = e.videoInfo()
        if (vi.isNotBlank()) {
            val badges = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(4.dp, 2.dp, 0, 12.dp) }
            for (part in vi.split("·").map { it.trim() }.filter { it.isNotEmpty() }.take(6)) {
                badges.addView(kit.badge(part), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = 6.dp })
            }
            facts.addView(badges)
        }
        fun fact(k: String, v: String) {
            facts.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(4.dp, 6.dp, 4.dp, 6.dp)
                addView(kit.label(k, Ui.T_LABEL, Ui.alpha(kit.text, 0.55f), kit.medium), LinearLayout.LayoutParams(110.dp, WRAP_CONTENT))
                addView(kit.label(v, Ui.T_LABEL, Ui.alpha(kit.text, 0.92f), kit.font, lines = 2), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })
        }
        fact("Audio", e.tracks("audio").firstOrNull { it.selected }?.label ?: "Default")
        fact("Subtitles", e.tracks("text").firstOrNull { it.selected }?.label ?: "Off")
        fun fill(info: PlayerInfo?) {
            metaView.text = info?.meta.orEmpty()
            metaView.visibility = if (info?.meta.isNullOrBlank()) View.GONE else View.VISIBLE
            synopsis.text = info?.overview?.takeIf { it.isNotBlank() } ?: if (info == null) "Loading…" else "No synopsis available."
            if (info?.source != null && facts.findViewWithTag<View>("source") == null) {
                val before = facts.childCount
                fact("Source", info.source)
                facts.getChildAt(before)?.tag = "source"
            }
        }
        val have = cb.info()
        fill(have)
        if (have == null) cb.loadInfo { info -> if (gen == myGen && kind == "info") fill(info) }
        focusLater(castChip ?: sv)
    }

    // ---- Episodes ----

    private var chipRow: LinearLayout? = null
    private var epList: LinearLayout? = null
    private var epScroll: SmoothScrollView? = null
    private var season = -1
    private var pendingSeason = -1
    private var history: WatchHistory.Entry? = null
    private val seasonSwitch = Runnable { showSeason(pendingSeason) }

    fun openEpisodes() {
        val req = host.request ?: return
        if (req.type != "episode") return
        val (_, col) = frame(560, req.title, "Episodes")
        kind = "episodes"
        val myGen = gen
        season = -1
        // Season chips: part of the fixed header, their own horizontally scrolling line, cut at the
        // card's inner edge (plus lift room), never over the episode list.
        val chipsScroll = FlatRow(ctx).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
        }
        val chips = FlatLinear(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(8.dp, 0, 8.dp, 0)
        }
        chipsScroll.addView(chips, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        col.addView(chipsScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        Ui.liftRoom(chipsScroll, LIFT_H, CHIP_LIFT_V) // the chips' glow reaches 12 dp past them: room for all of it
        chipRow = chips
        val list = LinearLayout(ctx)
        val epSv = scroller(col, list)
        epSv.margin = 24.dp
        epScroll = epSv
        epList = list
        for (i in 0 until 3) list.addView(skeletonRow())
        val cur = req.season ?: 1
        Net.background({
            val d = try { Tmdb.details(ctx, Title(req.tmdbId, "tv", req.title, "", null, null, "", 0.0)) } catch (e: Exception) { null }
            val h = try { WatchHistory.entries(ctx).firstOrNull { it.isShow && it.tmdbId == req.tmdbId } } catch (e: Exception) { null }
            Pair(d, h)
        }) { res ->
            if (gen != myGen || kind != "episodes") return@background
            history = res?.second
            val seasons = res?.first?.seasons.orEmpty().filter { it.episodes > 0 }
                .sortedWith(compareBy({ if (it.number == 0) 1 else 0 }, { it.number }))
                .ifEmpty { listOf(Season(cur, "Season $cur", 0, "")) }
            for (s in seasons.take(60)) {
                val name = s.name.takeIf { it.isNotBlank() && !it.startsWith("Season") && it.length <= 18 } ?: if (s.number == 0) "Specials" else "Season ${s.number}"
                chips.addView(chip(name, "season:${s.number}", s.number == cur, onFocus = { has -> if (has) queueSeason(s.number) }) {
                    queueSeason(s.number); flushSeason()
                    (epList?.findViewWithTag<View>(PLAYING_TAG) ?: epList?.getChildAt(0)?.takeIf { v -> v.isFocusable })?.requestFocus()
                })
            }
            (chips.getChildAt(chips.childCount - 1)?.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0
            showSeason(if (seasons.any { it.number == cur }) cur else seasons.first().number, focus = true)
            chips.findViewWithTag<View>("season:$cur")?.let { c ->
                chipsScroll.post {
                    val viewport = chipsScroll.width - chipsScroll.paddingLeft - chipsScroll.paddingRight
                    if (c.right > viewport) chipsScroll.scrollTo(maxOf(0, c.left - 8.dp), 0)
                }
            }
        }
    }

    private fun queueSeason(n: Int) {
        pendingSeason = n
        main.removeCallbacks(seasonSwitch)
        if (n != season) main.postDelayed(seasonSwitch, 380)
    }

    private fun flushSeason() {
        main.removeCallbacks(seasonSwitch)
        if (pendingSeason >= 0 && pendingSeason != season) showSeason(pendingSeason)
    }

    private fun showSeason(n: Int, focus: Boolean = false) {
        val list = epList ?: return
        if (n == season) return
        season = n
        chipRow?.let { r -> for (i in 0 until r.childCount) r.getChildAt(i).isSelected = r.getChildAt(i).tag == "season:$n" }
        val req = host.request ?: return
        val myGen = gen
        Net.background({
            // Out (playable) and still to come (Upcoming.kt; the first one is named under the list).
            WatchHistory.seasonEpisodes(ctx, req.tmdbId, n)?.let { Upcoming.split(ctx, req.tmdbId, req.title, n, it) }
        }) { split ->
            if (gen != myGen || kind != "episodes" || season != n) return@background
            fillSeason(list, n, split?.aired.orEmpty(), focus)
            split?.upcoming?.firstOrNull()?.let { list.addView(comingLine(it)) }
        }
    }

    /** A quiet line under the episodes: "Next Episode: E7 · Ace of Spades · Friday at 9:00 PM" (not focusable, not playable). */
    private fun comingLine(ep: JSONObject): View {
        val words = listOfNotNull("E${ep.optInt("episode_number")}", Upcoming.title(ep),
            Upcoming.whenWords(ep.optLong(Upcoming.AT), Upcoming.date(ep)) ?: Upcoming.TBA).joinToString("  ·  ")
        return kit.label("Next Episode:  $words", Ui.T_CAPTION, Ui.alpha(kit.text, 0.58f), kit.medium).apply {
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
        }
    }

    private fun fillSeason(list: LinearLayout, n: Int, eps: List<JSONObject>, focus: Boolean) {
        val hadFocus = list.hasFocus()
        // Keep focus inside the sheet while the rows are swapped (else it drops to the window).
        if (hadFocus) chipRow?.findViewWithTag<View>("season:$n")?.requestFocus()
        clearImages()
        list.removeAllViews()
        epScroll?.let { it.scrollTo(0, 0) }
        val req = host.request ?: return
        if (eps.isEmpty()) {
            list.addView(kit.label(if (Tmdb.hasKey(ctx)) "No Episodes to Watch Yet" else "Episode list needs a TMDB key", Ui.T_BODY, kit.dim)
                .apply { setPadding(12.dp, 12.dp, 0, 12.dp) })
            if (hadFocus) chipRow?.findViewWithTag<View>("season:$n")?.requestFocus()
            return
        }
        val playingIdx = if (req.season == n) eps.indexOfFirst { it.optInt("episode_number") == req.episode } else -1
        val start = if (eps.size <= MAX_EPS) 0 else (playingIdx - 6).coerceIn(0, eps.size - MAX_EPS)
        val shown = eps.subList(start, minOf(eps.size, start + MAX_EPS))
        var playing: View? = null
        for (ep in shown) {
            val r = episodeRow(n, ep, req)
            list.addView(r)
            if (r.tag == PLAYING_TAG) playing = r
        }
        if (focus || hadFocus) {
            val target = playing ?: list.getChildAt(0)
            if (focus) focusLater(target) else target?.requestFocus()
        }
    }

    private fun skeletonRow() = View(ctx).apply {
        background = Ui.rounded(ctx, Ui.alpha(kit.text, 0.05f), Ui.R_LG)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 98.dp).apply { bottomMargin = 8.dp }
    }

    private fun episodeRow(n: Int, ep: JSONObject, req: PlaybackRequest): View {
        val num = ep.optInt("episode_number")
        val name = ep.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: "Episode $num"
        val isPlaying = req.season == n && req.episode == num
        val h = history
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp, 8.dp, 14.dp, 8.dp)
            tag = if (isPlaying) PLAYING_TAG else "ep:$n:$num"
            isFocusable = true
            isClickable = true
            background = StateListDrawable().apply {
                addState(focusedState, Ui.rounded(ctx, Ui.alpha(kit.text, 0.13f), Ui.R_LG, 1.5f, Ui.alpha(kit.accent, 0.92f)))
                addState(IntArray(0), if (isPlaying) Ui.rounded(ctx, Ui.alpha(kit.select, 0.10f), Ui.R_LG) else ColorDrawable(Color.TRANSPARENT))
                setEnterFadeDuration(Ui.FOCUS_IN.toInt())
                setExitFadeDuration(Ui.FOCUS_OUT.toInt())
            }
            setOnFocusChangeListener { v, has -> Ui.lift(v, has, Ui.LIFT_ROW) }
        }
        PlayerLook.glowRow(row, Ui.R_LG, if (isPlaying) PlayerLook.chosen(ctx, Ui.R_LG) else null)
        val still = FrameLayout(ctx).apply {
            background = Ui.rounded(ctx, Ui.mix(kit.tile, kit.text, 0.05f), Ui.R_MD)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        val path = ep.optString("still_path").takeIf { it.isNotEmpty() && it != "null" }
        if (path != null) {
            val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            still.addView(iv, MATCH_PARENT, MATCH_PARENT)
            Net.image(ctx, iv, Tmdb.IMG + "w300" + path)
            images += iv
        } else {
            still.addView(kit.label("E$num", Ui.T_SECTION, kit.dim, kit.semibold).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        // Progress: live for the one playing, the saved position for the last one watched, full for earlier ones.
        val progress = when {
            isPlaying -> host.engine.let { if (it.durationMs > 0) it.positionMs.toFloat() / it.durationMs else 0f }
            h != null && h.season == n && h.episode == num && !h.upNext -> h.progress
            h != null && (n in 1 until h.season || (n == h.season && num < h.episode)) -> 1f
            else -> 0f
        }
        if (isPlaying) still.addView(kit.badge("PLAYING", highlight = false),
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(6.dp, 6.dp, 0, 0) })
        if (progress > 0.01f) {
            still.addView(FrameLayout(ctx).apply {
                background = Ui.rounded(ctx, Color.argb(110, 0, 0, 0), Ui.R_PILL)
                addView(View(ctx).apply {
                    background = if (progress >= 0.999f) Ui.rounded(ctx, Ui.alpha(kit.text, 0.7f), Ui.R_PILL)
                        else Ui.rounded(ctx, CinemaStyle.glowNow, Ui.R_PILL)
                }, FrameLayout.LayoutParams(((STILL_W - 16) * progress.coerceIn(0.06f, 1f)).toInt().dp, MATCH_PARENT))
            }, FrameLayout.LayoutParams(MATCH_PARENT, 3.dp, Gravity.BOTTOM).apply { setMargins(8.dp, 0, 8.dp, 7.dp) })
        }
        row.addView(still, LinearLayout.LayoutParams(STILL_W.dp, (STILL_W * 9 / 16).dp))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true }
        val titleColor = ColorStateList(arrayOf(focusedState, IntArray(0)), intArrayOf(PlayerLook.ink(ctx), Ui.alpha(kit.text, 0.94f)))
        col.addView(kit.label("$num.  $name", Ui.T_BODY, kit.text, kit.semibold).apply { setTextColor(titleColor); isDuplicateParentStateEnabled = true })
        val rt = ep.optInt("runtime").takeIf { it > 0 }?.let { PlayerMeta.runtime(it) }
        val date = PlayerMeta.airDate(ep.optString("air_date"))
        val metaLine = listOfNotNull(rt, date, if (progress >= 0.999f && !isPlaying) "Watched" else null).joinToString("  ·  ")
        if (metaLine.isNotEmpty()) col.addView(kit.label(metaLine, Ui.T_CAPTION, Ui.alpha(kit.text, 0.58f), kit.medium).apply {
            setPadding(0, 5.dp, 0, 0)
            setTextColor(PlayerLook.rowText(ctx, Ui.alpha(kit.text, 0.58f), 0.66f))
            isDuplicateParentStateEnabled = true
        })
        val ov = ep.optString("overview").takeIf { it.isNotBlank() && it != "null" }
        if (ov != null) col.addView(kit.label(ov, Ui.T_CAPTION, Ui.alpha(kit.text, 0.66f), kit.font, lines = 2).apply {
            setTextColor(PlayerLook.rowText(ctx, Ui.alpha(kit.text, 0.66f), 0.72f))
            isDuplicateParentStateEnabled = true
            setLineSpacing(0f, 1.15f)
            setPadding(0, 6.dp, 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = 14.dp })
        row.setOnClickListener {
            if (isPlaying) { close(); return@setOnClickListener }
            val resume = if (h != null && h.season == n && h.episode == num && !h.upNext && h.progress in 0.02f..WatchHistory.FINISHED) h.positionMs else 0L
            closeNow()
            cb.playEpisode(n, num, name, resume)
        }
        row.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 6.dp }
        return row
    }

    companion object {
        private const val MAX_EPS = 60
        private const val STILL_W = 150
        private const val PLAYING_TAG = "ep:playing"
        /** Lift room inside each scroller (rows grow 2 %, chips 6 %, plus the 1.5 dp focus ring). */
        private const val LIFT_H = 8
        private const val LIFT_V = 10
        /** Above and below the season chips: their halo ([CinemaStyle.GLOW_HALO_SMALL]) plus the focus lift. */
        private const val CHIP_LIFT_V = 14
        private const val SHEET_PAD_H = 22
        private const val SHEET_PAD_BOTTOM = 18
    }
}
