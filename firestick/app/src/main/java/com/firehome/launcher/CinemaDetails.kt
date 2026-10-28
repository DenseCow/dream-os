package com.firehome.launcher

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dream Cinema title page, full screen over everything: TMDB details, seasons, cast, recommendations,
 * the background trailer, My List and Continue Watching, through [TitlePage.Host]:
 *
 * - **Stage** (Apple TV / Netflix): the full-bleed backdrop under a scrim that fades from the theme's
 *   near-black at the left and bottom into its purple; the title logo (or the title), one clean meta
 *   line, a three-line synopsis, then the actions: Play / Resume (with a progress line under it),
 *   Episodes, the user's streaming apps, Trailer, My List.
 * - **Sheet** (Disney+ / Netflix): a tab bar peeks at the bottom (Episodes, More Like This, Details).
 *   Down slides the sheet up over the backdrop (one translation; the stage copy fades, the backdrop
 *   dims under a purple veil). Each tab is a lane of cards with a description of the focused item
 *   under it, so the cards stay small and the page never relayouts on a focus move.
 *
 * Focus moves in bands (actions, tabs, then the tab's lanes): Up/Down between bands landing on the item
 * last focused there, Left/Right inside a band. Back from below returns to the actions, then closes.
 * The focused item glows (CinemaStyle's rainbow glow, one hue per focus move); the hue also tints the
 * page's small accents (the tab underline, progress bars); fills stay the theme's.
 *
 * Instant Play needs nothing from here: MainActivity looks up the Play button's stream when the page
 * opens and whenever the remote rests on it (it only checks that a title page is open).
 */
class CinemaDetails(private val ctx: Context, private val t: Title, private val host: TitlePage.Host) : TitlePage {

    private val kit = PremiumKit(ctx) // HeroTrailer's dependency only: nothing on this page is drawn by it
    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private val screenH = ctx.resources.displayMetrics.heightPixels
    private val Int.dp get() = (this * density).toInt()
    private val isShow = t.type == "tv"
    /** A show's in-progress or up-next episode; looked up again when the watch history changes ([historyChanged]). */
    private var next: WatchHistory.Entry? = if (isShow) host.nextEpisode(t) else null
    private val playable = host.canPlay()
    private var details: Details? = Tmdb.cachedDetails(t)
    private var released = false
    private var closing = false
    private val images = ArrayList<ImageView>()
    private val today = DreamTime.todayYmd(ctx)
    private val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val pretty = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    // ---- palette (the user's theme) and type ----

    private val ink = CinemaStyle.bgTop(ctx)      // near-black
    private val dusk = CinemaStyle.bg(ctx)        // deep purple
    private val fg = CinemaStyle.text(ctx)
    private val dim = CinemaStyle.textDim(ctx)
    private val font: Typeface = Theme.typeface(ctx)
    private val bold: Typeface = Theme.typeface(ctx, Typeface.BOLD)
    private val medium = Ui.medium(font)
    private val semibold = Ui.semibold(font)
    /** The current glow hue; small accents follow it (see [setHue]). */
    private var hue = CinemaStyle.glowNow
    private var hueAnim: ValueAnimator? = null

    // ---- layers ----

    private val root: FrameLayout = object : FrameLayout(ctx) {
        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            if (closing) return true
            // Something is about to play or open: the trailer lets go of the decoder before the click lands.
            // (Not on a title still to come: OK there opens nothing.)
            if (e.action == KeyEvent.ACTION_DOWN && e.keyCode in SELECT_KEYS && findFocus()?.tag != COMING) trailer.stopNow()
            if (e.action == KeyEvent.ACTION_DOWN && navigate(e.keyCode)) return true
            return super.dispatchKeyEvent(e)
        }
    }
    override val view: View get() = root

    private val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(ink, Ui.mix(ink, dusk, 0.55f), dusk))
    private val backLayer: FrameLayout = flatFrame()
    /** Scrims, veil, stage and sheet: one layer over the backdrop. */
    private val chrome: FrameLayout = flatFrame()
    /** The pressed card's art, at once, until the real backdrop has loaded over it. */
    private val backPh = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val backImg = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    private val trailerSlot = FrameLayout(ctx)
    private val trailer = HeroTrailer.attach(trailerSlot, kit) { (backImg.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap }.apply {
        heroFocus = { v -> v != null && !closing && !sheetUp && isInside(v, buttons) }
        onImmersive = { on, animate -> trailerImmersive(on, animate) }
    }
    private var swallowKey = -1
    private val scrimSide = flat()
    private val scrimBottom = flat()
    private val veil = flat()

    // ---- stage ----

    // No offscreen layer for its fades: its parts barely overlap, and a stage-sized layer is a big allocation.
    private val stage = object : LinearLayout(ctx) { override fun hasOverlappingRendering() = false }
    private val logoSlot = FrameLayout(ctx)
    private val titleText = label(t.name, if (t.name.length > 24) 34f else T_TITLE, fg, bold, 2).apply {
        letterSpacing = -0.01f
        gravity = Gravity.BOTTOM or Gravity.START
    }
    private val logo = ImageView(ctx).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_START
        maxWidth = LOGO_W.dp
        maxHeight = LOGO_H.dp
        alpha = 0f
    }
    private val meta = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    /**
     * The synopsis, reachable with Up from the actions: focused, it takes the pearl face
     * and rainbow border like every control, and a longer text glides up so all of it can be read (AutoScroll).
     */
    private val synopsis = kit.scrollLabel(t.overview, CinemaStyle.T_SYNOPSIS, Ui.alpha(fg, 0.84f), font, lines = 3).apply {
        setLineSpacing(0f, 1.22f)
        isFocusable = true
        setPadding(SYN_PAD.dp, 10.dp, SYN_PAD.dp, 10.dp)
        cinemaRowFocus(this, 12)
        inkOnPearl(this)
    }
    private val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
    private val progressLine = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
    private val also = label("", 12.5f, dim, font, 1)
    private var rating: Ratings.Rating? = Ratings.peek(ctx, t)

    // ---- sheet ----

    private val sheet = flatLinear(LinearLayout.VERTICAL)
    private val tabBar = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    private val tabRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
    private val underline = View(ctx).apply { pivotX = 0f }
    private val underlineFill = GradientDrawable().apply { cornerRadius = 2.dp.toFloat(); setColor(hue) }
    private val panels = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    private val tabs = ArrayList<Tab>()
    private var tab = -1
    private var pendingTab = -1
    private val tabSwitch = Runnable { showTab(pendingTab) }
    private var sheetUp = false
    private var sheetGen = 0
    /** Sheet position, 0 (stage) to 1 (sheet up), and the one animator that moves it ([setSheet]). */
    private var sheetT = 0f
    private var sheetAnim: ValueAnimator? = null
    /** The sheet has been raised once: its art loads from then on ([loadArt]). */
    private var sheetShown = false
    private val pendingArt = ArrayList<() -> Unit>()
    private val travel get() = (screenH - PEEK.dp - SHEET_TOP.dp).toFloat()
    /** Holds focus for a moment while a focused lane is rebuilt, so Android doesn't hand it elsewhere. */
    private val parking = View(ctx)

    private class Tab(val name: String, val view: TextView, val panel: View, val bands: List<Lane>, val onFirstShow: () -> Unit) {
        var shown = false
    }

    // ---- bands ----

    private var chipsLane: Lane? = null
    private var epLane: Lane? = null
    private var recLane: Lane? = null
    private var castLane: Lane? = null
    /** The Information tab's grid (TitleInfo.kt): built once with the details, a value rewritten as ratings land. */
    private var infoGrid: InfoGrid? = null
    /** Median episode length of the first season loaded (the default one); 0 until it arrives. */
    private var epMinutes = 0
    private val remembered = HashMap<View, View>()

    // ---- episodes ----

    private val chips = ArrayList<TextView>()
    private val chipFills = ArrayList<GradientDrawable>()
    private var season = 0
    private var pendingSeason = 0
    private var seasonQueued = false
    private var seasonGen = 0
    private var epFadeEnd = 0L
    private var nextCard: View? = null
    private val seasonSwitch = Runnable { seasonQueued = false; showSeason(pendingSeason) }
    private var epBlurb: Blurb? = null
    private var recBlurb: Blurb? = null
    /** Announced seasons that haven't started, by number (their chips and lists read "Coming ..."). */
    private val comingSeasons = HashMap<Int, Season>()

    init {
        root.background = bg
        root.isClickable = true
        root.isFocusable = false
        root.clipChildren = false
        backLayer.addView(backPh, MATCH_PARENT, MATCH_PARENT)
        backLayer.addView(backImg, MATCH_PARENT, MATCH_PARENT)
        backLayer.addView(trailerSlot, MATCH_PARENT, MATCH_PARENT)
        trailer.setState(t, resting = false)
        root.addView(backLayer, MATCH_PARENT, MATCH_PARENT)
        // Everything over the backdrop lives in one layer: the background trailer fades just this ([trailerImmersive]),
        // so no fade can ever cancel the sheet's slide.
        chrome.clipChildren = false
        root.addView(chrome, MATCH_PARENT, MATCH_PARENT)
        // Copy side: the theme's near-black at the left edge, clear by two thirds across.
        scrimSide.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
            Ui.alpha(ink, 0.97f), Ui.alpha(ink, 0.84f), Ui.alpha(ink, 0.42f), Color.TRANSPARENT))
        chrome.addView(scrimSide, MATCH_PARENT, MATCH_PARENT)
        // Bottom: into the near-black with a breath of the theme's purple, where the sheet rises from.
        val floor = Ui.mix(ink, dusk, 0.22f)
        scrimBottom.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, Ui.alpha(floor, 0.7f), floor))
        chrome.addView(scrimBottom, FrameLayout.LayoutParams(MATCH_PARENT, (screenH * 0.55f).toInt(), Gravity.BOTTOM))
        // The sheet's ground: the theme gradient over a dimmed backdrop (black above, purple where the cards sit).
        veil.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            Ui.alpha(ink, 0.86f), Ui.alpha(Ui.mix(ink, dusk, 0.45f), 0.94f), Ui.alpha(Ui.mix(ink, dusk, 0.8f), 0.98f)))
        veil.alpha = 0f
        veil.visibility = View.INVISIBLE
        chrome.addView(veil, MATCH_PARENT, MATCH_PARENT)
        chrome.addView(parking, FrameLayout.LayoutParams(1, 1))

        buildStage()
        chrome.addView(stage, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = (PEEK + 18).dp
        })
        buildSheet()
        chrome.addView(sheet, FrameLayout.LayoutParams(MATCH_PARENT, screenH - SHEET_TOP.dp).apply { topMargin = screenH - PEEK.dp })
        applySheet(0f)
        // MainActivity keeps stacked title pages whole on its Back stack (detached, then added back): coming back,
        // the page shows exactly where it was left, art in place and no motion stopped half way.
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            private var seen = false
            override fun onViewAttachedToWindow(v: View) {
                if (!seen) { seen = true; return }
                if (released || closing) return
                restoreAfterReattach()
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })

        loadBackdrop(t.backdropPath)
        loadLogo()
        loadRating()
        val d = details
        buildButtons(d)
        fillMeta(d)
        if (d != null) applyDetails(d) else Net.background({ Tmdb.details(ctx, t) }) { r ->
            if (released) return@background
            if (r == null) { Log.w(TAG, "cinema details: no TMDB data for ${t.key}"); return@background }
            details = r
            buildButtons(r)
            fillMeta(r, fade = true)
            applyDetails(r)
        }
    }

    // ---- TitlePage ----

    override fun focusInitial() { buttons.getChildAt(0)?.requestFocus() }

    /** What the Play button says and the progress line under it, as last built ([buildButtons]). */
    private var playSig: String? = null

    private fun playSig(): String {
        val e = resumeEntry()
        val shown = e ?: next?.takeIf { it.progress > 0f && !it.upNext }
        return (if (e != null) resumeLabel(e) else PlayFlow.playLabel(ctx, t, next)) + "|" + (shown?.let { it.positionMs / 60_000 } ?: -1)
    }

    /**
     * A viewing ended, saved its spot or finished while this page waited behind the player: its Resume button
     * and progress line are redone so they match (a finished movie says Play again, never Resume from the old spot).
     */
    override fun historyChanged() {
        if (released || closing) return
        if (isShow) next = host.nextEpisode(t)
        if (playSig() != playSig) buildButtons(details)
    }

    override fun handleBack(): Boolean {
        if (closing) return true
        if (sheetUp) {
            setSheet(false)
            (remembered[buttons]?.takeIf { it.isAttachedToWindow } ?: buttons.getChildAt(0))?.requestFocus()
            return true
        }
        return false
    }

    override fun interceptKey(e: KeyEvent): Boolean {
        if (released) return false
        val code = e.keyCode
        if (code == swallowKey) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) swallowKey = -1
            else { if (e.action == KeyEvent.ACTION_UP) swallowKey = -1; return true }
        }
        if (code in PASS_KEYS) return false
        if (trailer.immersive) {
            // Full screen: the trailer keeps playing, the page comes back over it, and this key does nothing else.
            if (e.action == KeyEvent.ACTION_DOWN) { swallowKey = code; trailer.wake() }
            return true
        }
        if (e.action == KeyEvent.ACTION_DOWN) trailer.userActive()
        return false
    }

    /**
     * Entrance: the backdrop (the pressed card's own art at once) grows out of the card's spot into the full
     * screen, then the copy slides in from the left line by line and the tab bar rises into its peek.
     */
    override fun animateIn(fromRect: Rect?, fromBitmap: Bitmap?) {
        if (fromBitmap != null && !fromBitmap.isRecycled) backPh.setImageBitmap(fromBitmap)
        bg.alpha = 0
        ValueAnimator.ofInt(0, 255).apply {
            duration = 200
            addUpdateListener { if (!released) bg.alpha = it.animatedValue as Int }
            start()
        }
        val w = ctx.resources.displayMetrics.widthPixels.toFloat()
        if (fromRect != null && fromRect.width() > 0) {
            val s = (fromRect.width() / w).coerceIn(0.12f, 1f)
            backLayer.pivotX = 0f
            backLayer.pivotY = 0f
            backLayer.scaleX = s
            backLayer.scaleY = s
            backLayer.translationX = fromRect.left.toFloat()
            backLayer.translationY = fromRect.top.toFloat()
            backLayer.alpha = 0.7f
            backLayer.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).alpha(1f)
                .setDuration(ENTER_MS).setInterpolator(Ui.EASE).withEndAction { backLayer.pivotX = w / 2f; backLayer.pivotY = screenH / 2f }.start()
        } else {
            backLayer.alpha = 0f
            backLayer.scaleX = 1.06f
            backLayer.scaleY = 1.06f
            backLayer.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(620).setInterpolator(Ui.EASE).start()
        }
        for (s in listOf(scrimSide, scrimBottom)) {
            s.alpha = 0f
            s.animate().alpha(1f).setStartDelay(80).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        }
        Ui.slideIn((0 until stage.childCount).map { stage.getChildAt(it) }, fromDp = 36, startDelay = 160, step = 45L, duration = 420L)
        // The tab bar rises into its peek (the bar, not the sheet: the sheet's position belongs to [setSheet]).
        Ui.rise(listOf(tabBar), fromDp = 28, startDelay = 360, duration = 420L)
    }

    override fun animateOut(done: () -> Unit) {
        closing = true
        main.removeCallbacksAndMessages(null)
        trailer.stopForExit {
            root.animate().cancel()
            root.animate().alpha(0f).scaleX(1.015f).scaleY(1.015f).setStartDelay(0).setDuration(Ui.OVERLAY_OUT)
                .setInterpolator(Ui.SETTLE).withLayer().withEndAction { done() }.start()
        }
    }

    override fun release() {
        released = true
        trailer.release()
        main.removeCallbacksAndMessages(null)
        hueAnim?.cancel()
        backPh.setImageDrawable(null)
        logo.setImageDrawable(null)
        for (iv in images) {
            iv.animate().cancel()
            iv.tag = null // a load still in flight no longer matches, so it won't land here
            iv.setImageDrawable(null)
        }
        images.clear()
        remembered.clear()
        nextCard = null
    }

    // ---- stage ----

    private fun buildStage() {
        stage.orientation = LinearLayout.VERTICAL
        stage.clipChildren = false
        stage.clipToPadding = false
        stage.setPadding(CinemaStyle.GUTTER.dp, 0, CinemaStyle.GUTTER.dp, 0)
        logoSlot.addView(titleText, FrameLayout.LayoutParams(LOGO_W.dp, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        logoSlot.addView(logo, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        stage.addView(logoSlot, LinearLayout.LayoutParams(LOGO_W.dp, LOGO_H.dp))
        stage.addView(meta, LinearLayout.LayoutParams(WRAP_CONTENT, 24.dp).apply { topMargin = 18.dp })
        // Padded for its focus face, pulled back by the same amount so the text stays on the left line.
        stage.addView(synopsis, LinearLayout.LayoutParams((TEXT_W + 2 * SYN_PAD).dp, WRAP_CONTENT).apply {
            topMargin = 2.dp; marginStart = -SYN_PAD.dp
        })
        stage.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 22.dp })
        // Available Now / CAM Only / Not Available Yet, and when it comes (Availability.kt); gone when there's nothing to say.
        stage.addView(Availability.TitleStatus(ctx, t), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        stage.addView(progressLine, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 16.dp })
        stage.addView(also, LinearLayout.LayoutParams(TEXT_W.dp, WRAP_CONTENT).apply { topMargin = 12.dp })
        synopsis.minLines = 3
    }

    private fun applyDetails(d: Details) {
        // A title page opened: a small taste signal (Taste.kt), its genres from the list or the details.
        try {
            Taste.noteOpened(ctx, t, t.genreIds.ifEmpty { d.genres.mapNotNull { n -> Discover.genres(t.type).firstOrNull { it.name == n }?.id } })
        } catch (e: Exception) {}
        if (synopsis.text.isNullOrEmpty()) synopsis.text = d.overview.ifEmpty { "No description available." }
        if (t.backdropPath == null) loadBackdrop(d.backdropPath)
        buildTabs(d)
    }

    private fun loadBackdrop(path: String?) {
        val url = path?.let { Tmdb.IMG + "w1280" + it } ?: return
        if (backImg.tag == url) return
        if (backImg !in images) images += backImg
        Net.image(ctx, backImg, url) {
            if (released) return@image
            backImg.animate().alpha(1f).setDuration(CinemaStyle.BACKDROP_FADE_MS).setInterpolator(Ui.EASE)
                .withEndAction {
                    if (released) return@withEndAction
                    backPh.setImageDrawable(null)
                    // The backdrop covers the screen from here on: the page's own gradient needn't be drawn under it.
                    main.postDelayed({ if (!released && !closing && backLayer.scaleX == 1f) root.background = null }, ENTER_MS)
                }.start()
        }
    }

    /** TMDB's English (or language-neutral) title logo replaces the typed title once it's decoded. */
    private fun loadLogo() {
        Net.background({
            val a = WatchHistory.tmdb(ctx, "/${t.type}/${t.id}/images", "&include_image_language=en,null")?.optJSONArray("logos")
            val logos = a?.let { (0 until it.length()).map { i -> it.getJSONObject(i) } }.orEmpty()
                .filter { !it.optString("file_path").endsWith(".svg") }
            (logos.firstOrNull { it.optString("iso_639_1") == "en" } ?: logos.firstOrNull())
                ?.optString("file_path")?.takeIf { it.isNotEmpty() }?.let { Tmdb.IMG + "w500" + it }
        }) { url ->
            if (released || url == null) return@background
            PlayFlow.logo(ctx, url) { bmp ->
                if (released || bmp == null) return@logo
                logo.setImageBitmap(bmp)
                logo.animate().alpha(1f).setDuration(280).setInterpolator(Ui.EASE).start()
                titleText.animate().alpha(0f).setDuration(200).setInterpolator(Ui.SETTLE).start()
            }
        }
    }

    private fun loadRating() {
        Ratings.load(ctx, t, Ratings.Omdb.DETAILS) { r ->
            if (released || r == null) return@load
            rating = r
            fillMeta(details, fade = true)
            details?.info?.let { infoGrid?.set(TitleInfo.RATINGS, it.ratingsWords(r)) }
        }
    }

    /** "2019  ·  [TV-MA]  ·  3 Seasons  ·  Drama, Crime  ·  🍅 94%  ·  ★★★★☆": one line, the age rating boxed. */
    private fun fillMeta(d: Details?, fade: Boolean = false) {
        val before = meta.childCount
        meta.removeAllViews()
        val r = rating
        val parts = ArrayList<View>()
        // The full release date ("July 31, 2026"; owner, 2026-10-28), "Coming March 1, 2027" while still to come, else
        // the year when TMDB has no day (cards keep the year alone).
        val day = d?.info?.releaseDay?.ifEmpty { null } ?: t.date
        (TitleInfo.comingWords(ctx, day) ?: TitleInfo.longDate(ctx, day) ?: t.year.take(4).ifEmpty { null })?.let { parts += metaText(it) }
        r?.cert?.ifEmpty { null }?.let { parts += certBadge(it) }
        (d?.runtime?.ifEmpty { null } ?: r?.runtime?.ifEmpty { null })?.let { parts += metaText(it) }
        (d?.genres?.ifEmpty { null } ?: r?.genres)?.take(2)?.joinToString(", ")?.ifEmpty { null }?.let { parts += metaText(it) }
        // Scores: the tomato with the Tomatometer in its quality colour, then the audience score as published
        // (IMDb's, else TMDB's when enough people voted).
        r?.tomatometer?.let { parts += tomatoScore(it) }
        Ratings.audienceFor(r, t)?.let { parts += Ratings.audienceBadge(ctx, it, 14f, fg, bold, semibold) }
        for ((i, p) in parts.withIndex()) {
            if (i > 0) meta.addView(metaText("·", Ui.alpha(dim, 0.7f)), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = 10.dp; marginEnd = 10.dp
            })
            meta.addView(p)
        }
        if (fade && meta.childCount != before) {
            meta.alpha = 0.4f
            meta.animate().alpha(1f).setDuration(240).setInterpolator(Ui.EASE).start()
        }
    }

    private fun metaText(s: String, color: Int = dim) = label(s, 14f, color, medium, 1)

    /** Rotten Tomatoes: the tomato glyph and the score in its quality colour (green fresh, amber mixed, red low). */
    private fun tomatoScore(v: Int) = label("$v%", 14f, Ratings.scoreColor(kit, v), semibold, 1).apply {
        val px = (14f * 1.25f * ctx.resources.displayMetrics.scaledDensity).toInt()
        setCompoundDrawables(TomatoGlyph().apply { setBounds(0, 0, px, px) }, null, null, null)
        compoundDrawablePadding = 7.dp
        gravity = Gravity.CENTER_VERTICAL
    }


    private fun certBadge(s: String) = label(s, 11.5f, Ui.alpha(fg, 0.9f), semibold, 1).apply {
        gravity = Gravity.CENTER
        setPadding(7.dp, 3.dp, 7.dp, 3.dp)
        background = Ui.rounded(ctx, Color.TRANSPARENT, 4, 1.2f, Ui.alpha(fg, 0.55f))
    }

    // ---- actions ----

    /**
     * Play first (Resume from the saved spot when it's in Continue Watching; long press asks which episode or
     * source), then Episodes for shows, the user's installed streaming apps, Trailer and My List. Rebuilt once
     * when the details land (focus stays on the same action).
     */
    private fun buildButtons(d: Details?) {
        val focusedKey = (buttons.findFocus()?.let { f -> (0 until buttons.childCount).map { buttons.getChildAt(it) }.firstOrNull { it === f } })?.tag
        val rebuild = buttons.childCount > 0
        val had = (0 until buttons.childCount).map { buttons.getChildAt(it).tag }.toSet()
        if (focusedKey != null) park()
        remembered.remove(buttons)
        buttons.removeAllViews()
        progressLine.visibility = View.GONE
        playSig = playSig()

        val installed = host.installed()
        val services = d?.providers.orEmpty().mapNotNull { p -> Tmdb.SERVICES.firstOrNull { it.providerId == p.id } }.distinctBy { it.pkg }
        val watchable = services.filter { it.pkg in installed }
        var primaryTaken = false
        var episodesAdded = false
        fun addEpisodes() {
            if (!isShow || episodesAdded) return
            episodesAdded = true
            buttons.addView(button("episodes", "Episodes", R.drawable.ic_tv, null, primary = false) { openEpisodes() })
        }
        val inProgress = resumeEntry()
        if (inProgress != null && (playable || !WatchHistory.ours(inProgress))) {
            primaryTaken = true
            buttons.addView(button("play", resumeLabel(inProgress), R.drawable.ic_play, null, primary = true) { host.resume(inProgress) }.apply {
                if (playable) setOnLongClickListener { host.watch(t, true); true }
            })
            showProgress(inProgress)
            addEpisodes()
        } else if (playable) {
            primaryTaken = true
            buttons.addView(button("play", PlayFlow.playLabel(ctx, t, next), R.drawable.ic_play, null, primary = true) { host.watch(t, false) }.apply {
                setOnLongClickListener { host.watch(t, true); true }
            })
            next?.takeIf { it.progress > 0f && !it.upNext }?.let { showProgress(it) }
            addEpisodes()
        }
        for (svc in watchable) {
            buttons.addView(button("svc:${svc.pkg}", "Watch on ${svc.name}", R.drawable.ic_play, null, primary = !primaryTaken) { host.openOnService(svc, t) })
            primaryTaken = true
            addEpisodes()
        }
        d?.trailerKey?.let {
            buttons.addView(button("trailer", "Trailer", R.drawable.ic_movie, null, primary = !primaryTaken) {
                // Full screen in Dream Player, continuing the background trailer from where it stopped.
                val from = trailer.handoff()
                host.playTrailer(t, from?.second ?: 0L, from?.first)
            })
            primaryTaken = true
            addEpisodes()
        }
        addEpisodes()
        // My List: the + turns into a green check as the title is added (and back), the label flips to
        // "Added"; the label keeps the wider word's width, so the button never changes size.
        val saved = Tmdb.inMyList(ctx, t)
        val mark = MyListMark(ctx, saved)
        val mine = button("mylist", if (saved) ADDED else MY_LIST, 0, null, primary = false, lead = mark, labels = listOf(MY_LIST, ADDED)) {
            Tmdb.toggleMyList(ctx, t)
            val on = Tmdb.inMyList(ctx, t)
            mark.animateTo(on)
            ((mark.parent as? ViewGroup)?.getChildAt(1) as? TextView)?.let { flipLabel(it, if (on) ADDED else MY_LIST) }
        }
        buttons.addView(mine)

        val elsewhere = d?.providers.orEmpty().map { it.name }.distinct().filterNot { n -> watchable.any { n.startsWith(it.name) } }
        also.text = if (elsewhere.isNotEmpty()) "Also Streaming On  " + elsewhere.take(4).joinToString("  ·  ") else ""
        also.visibility = if (also.text.isEmpty()) View.GONE else View.VISIBLE

        if (!rebuild) return
        for (i in 0 until buttons.childCount) buttons.getChildAt(i).let { b ->
            if (b.tag !in had) { b.alpha = 0f; b.animate().alpha(1f).setDuration(240).setInterpolator(Ui.EASE).start() }
        }
        if (focusedKey != null) {
            ((0 until buttons.childCount).map { buttons.getChildAt(it) }.firstOrNull { it.tag == focusedKey } ?: buttons.getChildAt(0))?.requestFocus()
            unpark()
        }
    }

    /** The label's word changes with a short lift: the old one rises away, the new one rises into place. */
    private fun flipLabel(tv: TextView, word: String) {
        if (tv.text.toString() == word) return
        val d = 5.dp.toFloat()
        tv.animate().cancel()
        tv.animate().alpha(0f).translationY(-d).setStartDelay(0).setDuration(110).setInterpolator(Ui.EXIT).withEndAction {
            tv.text = word
            tv.translationY = d
            tv.animate().alpha(1f).translationY(0f).setDuration(210).setInterpolator(Ui.EASE).start()
        }.start()
    }

    /** Under the actions: a short bar in the glow hue and "S2 E4 · 12 Min Left" (Netflix-style). */
    private fun showProgress(e: WatchHistory.Entry) {
        progressLine.removeAllViews()
        val track = FrameLayout(ctx).apply { background = Ui.rounded(ctx, Ui.alpha(fg, 0.22f), 2) }
        val fill = View(ctx)
        accentFill(fill, 2)
        track.addView(fill, FrameLayout.LayoutParams((PROGRESS_W * e.progress.coerceIn(0.03f, 1f)).toInt().dp, MATCH_PARENT))
        progressLine.addView(track, LinearLayout.LayoutParams(PROGRESS_W.dp, 4.dp))
        val left = if (e.durationMs > 0) ((e.durationMs - e.positionMs) / 60_000).takeIf { it > 0 }?.let { "$it Min Left" } else null
        val what = listOfNotNull(if (e.isShow) "S${e.season} E${e.episode}" else null, e.episodeTitle?.takeIf { e.isShow && it.isNotBlank() }, left)
        progressLine.addView(label(what.joinToString("  ·  "), 13f, dim, medium, 1),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 14.dp })
        progressLine.visibility = View.VISIBLE
    }

    /**
     * An action: icon (or a glyph such as My List's + / ✓) and label with a real gap between them, on one
     * corner radius. Play (the primary): white at rest, the fading rainbow fill while focused ([Rainbow.fade],
     * as Home's Play). The rest: neutral dark glass at rest; focused, a pearl box with dark ink and the rainbow
     * glowing border around it ([PearlFocus]), fading in step with Play.
     */
    private fun button(key: String, title: String, icon: Int, glyph: String?, primary: Boolean, lead: View? = null,
                       labels: List<String>? = null, onClick: () -> Unit): GlowBox {
        val r = CinemaStyle.R_BUTTON
        val rainbow = if (primary) PearlFocus.rainbowFill(ctx, r) else null
        val idleFill: Drawable = rainbow ?: PearlFocus.glass(ctx, r, 1.6f)
        val focusFill: Drawable = rainbow ?: Ui.rounded(ctx, PearlFocus.PEARL, r)
        val inkRest = if (primary) PearlFocus.INK else fg
        val box = GlowBox(r, CinemaStyle.GLOW_HALO_SMALL, opaqueFace = false)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18.dp, 0, 22.dp, 0)
            background = idleFill
        }
        val icon0: View = lead ?: if (glyph != null) label(glyph, 19f, inkRest, semibold, 1).apply { gravity = Gravity.CENTER }
        else ImageView(ctx).apply { setImageDrawable(ctx.getDrawable(icon)?.mutate()); setColorFilter(inkRest) }
        (icon0 as? MyListMark)?.setInk(inkRest, pearl = false)
        // A drawn glyph's ring may reach past it: the row lets it (the box's own clip is the button's shape).
        if (lead != null) row.clipChildren = false
        val wrap = glyph != null && lead == null
        row.addView(icon0, LinearLayout.LayoutParams(if (wrap) WRAP_CONTENT else 20.dp, if (wrap) WRAP_CONTENT else 20.dp))
        val text = label(title, 15.5f, inkRest, semibold, 1)
        // Words that take turns ([labels]) share the widest one's width, centered: the button keeps its size.
        if (labels != null) {
            text.width = labels.maxOf { kotlin.math.ceil(text.paint.measureText(it).toDouble()).toInt() } + 1
            text.gravity = Gravity.CENTER_HORIZONTAL
        }
        row.addView(text, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 10.dp })
        val fade = if (rainbow == null) null else Rainbow.fade(row, rainbow) { c -> recolor(row, c) }
        box.addView(row, FrameLayout.LayoutParams(WRAP_CONTENT, BUTTON_H.dp))
        return box.apply {
            tag = key
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, BUTTON_H.dp).apply { marginEnd = 16.dp }
            setOnClickListener { onClick() }
            setOnFocusChangeListener { _, has ->
                if (has) remembered[buttons] = this
                if (fade != null) fade.focus(has) else {
                    focusGlow(box, has)
                    row.background = if (has) focusFill else idleFill
                    recolor(row, if (has) PearlFocus.INK else inkRest)
                }
                lift(this, has, CinemaStyle.FOCUS_SCALE_BIG)
            }
        }
    }

    private fun recolor(row: ViewGroup, c: Int) {
        for (i in 0 until row.childCount) when (val v = row.getChildAt(i)) {
            is TextView -> v.setTextColor(c)
            is ImageView -> v.setColorFilter(c)
            is MyListMark -> v.setInk(c, pearl = c == PearlFocus.INK)
        }
    }

    private fun openEpisodes() {
        val i = tabs.indexOfFirst { it.name == EPISODES }
        if (i < 0) { if (playable) host.watch(t, true); return }
        buttons.findFocus()?.let { f -> topChild(buttons, f) }?.let { remembered[buttons] = it }
        showTab(i)
        flushSeason()
        val lane = epLane ?: return
        (entryOf(lane) ?: chipsLane?.let { entryOf(it) })?.requestFocus()
        setSheet(true)
    }

    // ---- sheet: tabs and panels ----

    private fun buildSheet() {
        sheet.clipChildren = false
        sheet.clipToPadding = false
        // Tab text sits on the gutter: the capsule's side padding reaches out past it.
        tabRow.setPadding((CinemaStyle.GUTTER - TAB_PAD).dp, 0, CinemaStyle.GUTTER.dp, 0)
        tabRow.clipToPadding = false // the focused capsule's lift reaches past the padding; never clip its edge
        tabBar.addView(tabRow, FrameLayout.LayoutParams(MATCH_PARENT, TAB_H.dp))
        underline.background = underlineFill
        underlineFill.setColor(PearlFocus.PEARL) // pearl at rest; the glow color while a tab has focus ([tabFocus])
        underline.alpha = 0f
        tabBar.addView(underline, FrameLayout.LayoutParams(100, 3.dp, Gravity.BOTTOM))
        sheet.addView(tabBar, LinearLayout.LayoutParams(MATCH_PARENT, TAB_H.dp))
        sheet.addView(panels, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    private fun buildTabs(d: Details) {
        if (tabs.isNotEmpty()) return
        if (isShow) {
            val out = d.seasons.filter { it.number > 0 && it.episodes > 0 && aired(it.airDate) }
            // Announced seasons after the last one out (Upcoming.kt): their own tab, a "Coming Mar 4" line on the chip.
            val last = out.maxOfOrNull { it.number } ?: 0
            val ahead = d.seasons.filter { it.number > last && Upcoming.seasonAhead(it, today) }.sortedBy { it.number }.take(2)
            comingSeasons.clear()
            for (s in ahead) comingSeasons[s.number] = s
            val seasons = (out + ahead).sortedBy { it.number }
            if (seasons.isNotEmpty()) addTab(EPISODES, episodesPanel(seasons, out.sortedBy { it.number }), listOfNotNull(chipsLane, epLane)) {}
        }
        if (d.recommendations.isNotEmpty()) {
            val p = morePanel(d.recommendations)
            addTab("More Like This", p, listOfNotNull(recLane)) { fillRecs(d.recommendations) }
        }
        val info = detailsPanel(d)
        addTab("About", info, listOfNotNull(castLane)) { fillCast(d.cast) }
        // Apple TV-style Information: the whole grid on screen at once, so the tab needs no focus stops of its own.
        d.info?.let { ti -> informationPanel(ti)?.let { addTab("Information", it, emptyList()) {} } }
        // The names fade in when the details land (their own alpha: the row's belongs to [applySheet]).
        for (x in tabs) { x.view.alpha = 0f; x.view.animate().alpha(1f).setDuration(260).setInterpolator(Ui.EASE).start() }
        tabRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!released) placeUnderline(false) }
        showTab(0, animate = false)
    }

    /**
     * A tab: plain text at rest (bright when it's the tab on show, its underline under it); focused, a pearl
     * capsule with dark ink and the rainbow border ([PearlFocus]), while the underline takes the glow color.
     */
    private fun addTab(name: String, panel: View, bands: List<Lane>, onFirstShow: () -> Unit) {
        val i = tabs.size
        val tv = label(name, CinemaStyle.T_TAB + 2f, dim, semibold, 1).apply {
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setPadding(TAB_PAD.dp, 0, TAB_PAD.dp, 0)
        }
        // Its own face, painted on each focus change: pearl at once (it must cover the halo's inner band), clear at rest.
        val face = GradientDrawable().apply { cornerRadius = (TAB_PILL / 2).dp.toFloat(); setColor(Color.TRANSPARENT) }
        val glow = CinemaStyle.glowDrawable(ctx, radiusDp = TAB_PILL / 2, haloDp = CinemaStyle.GLOW_HALO_SMALL)
        tv.background = android.graphics.drawable.LayerDrawable(arrayOf(glow, face))
        tv.setOnFocusChangeListener { v, has ->
            face.setColor(if (has) PearlFocus.PEARL else Color.TRANSPARENT)
            if (has) CinemaStyle.focusGlow(glow, true) else dropGlow(glow)
            tv.setTextColor(if (has) PearlFocus.INK else if (i == tab) fg else dim)
            lift(v, has, 1.04f)
            if (has) {
                stepHue()
                queueTab(i)
                CinemaStyle.followGlow(underline) { u, c -> (u.background as? GradientDrawable)?.setColor(c) }
                // The focused capsule already marks the tab: the underline steps aside instead of running into its edge.
                underline.animate().alpha(0f).setDuration(120).start()
            } else tv.post {
                // After the move settles (during it the row still counts as focused): pearl again once focus left the tabs.
                if (!released && !tabRow.hasFocus()) {
                    CinemaStyle.unfollowGlow(underline); underlineFill.setColor(PearlFocus.PEARL)
                    underline.animate().alpha(1f).setDuration(200).start()
                }
            }
        }
        tv.setOnClickListener { flushTab(); moveBand(tabRow, tv, 1) }
        tabRow.addView(tv, LinearLayout.LayoutParams(WRAP_CONTENT, TAB_PILL.dp).apply { marginEnd = 10.dp })
        panel.visibility = View.INVISIBLE
        panels.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        tabs += Tab(name, tv, panel, bands, onFirstShow)
    }

    private fun queueTab(i: Int) {
        pendingTab = i
        main.removeCallbacks(tabSwitch)
        if (i != tab) main.postDelayed(tabSwitch, TAB_DEBOUNCE)
    }

    private fun flushTab() {
        main.removeCallbacks(tabSwitch)
        if (pendingTab >= 0 && pendingTab != tab) showTab(pendingTab)
    }

    /** Crossfades to tab [i]'s panel (a short rise), slides the underline under its name. */
    private fun showTab(i: Int, animate: Boolean = true) {
        if (i !in tabs.indices || i == tab) return
        val old = tabs.getOrNull(tab)
        tab = i
        val nt = tabs[i]
        for ((j, x) in tabs.withIndex()) x.view.setTextColor(if (x.view.isFocused) PearlFocus.INK else if (j == i) fg else dim)
        if (!nt.shown) { nt.shown = true; nt.onFirstShow() }
        old?.panel?.let { p ->
            p.animate().cancel()
            p.animate().alpha(0f).setDuration(110).setInterpolator(Ui.SETTLE).withEndAction { if (tabs.getOrNull(tab)?.panel !== p) p.visibility = View.INVISIBLE }.start()
        }
        nt.panel.animate().cancel()
        nt.panel.visibility = View.VISIBLE
        if (animate) {
            nt.panel.alpha = 0f
            nt.panel.translationY = 14.dp.toFloat()
            nt.panel.animate().alpha(1f).translationY(0f).setStartDelay(60).setDuration(260).setInterpolator(Ui.EASE).start()
        } else nt.panel.alpha = 1f
        placeUnderline(animate)
    }

    /** The underline glides (translation + scale, no layout) under the current tab's name. */
    private fun placeUnderline(animate: Boolean) {
        val v = tabs.getOrNull(tab)?.view ?: return
        if (v.width == 0) return
        val x = (v.left + TAB_PAD.dp).toFloat() // under the words, not the capsule
        val sx = (v.width - 2 * TAB_PAD.dp) / 100f
        if (animate && underline.alpha > 0f) underline.animate().translationX(x).scaleX(sx).setDuration(TAB_SLIDE_MS).setInterpolator(Ui.EASE).start()
        else { underline.translationX = x; underline.scaleX = sx; underline.animate().alpha(1f).setDuration(200).start() }
    }

    /**
     * Down from the actions (or Up back to them): the sheet rides up over the dimmed backdrop. One animator
     * owns the whole motion and sets every moving part from the same eased fraction each frame (sheet, stage,
     * veil, tab bar), so they always start, move and land together, and nothing else (the trailer's fade, the
     * entrance) can stop one part half way. Reversing mid-way continues from where the page is.
     */
    private fun setSheet(up: Boolean) {
        if (up == sheetUp) return
        sheetUp = up
        val gen = ++sheetGen
        // Anything still easing these parts (the entrance) hands them over to this motion.
        for (v in listOf(sheet, stage, veil, scrimSide, scrimBottom, tabRow)) v.animate().cancel()
        // Up, the veil covers the scrims and the stage: they stop drawing once it's there (the GPU is small);
        // down, the veil itself stops drawing.
        if (!up) for (v in listOf(scrimSide, scrimBottom, stage)) v.visibility = View.VISIBLE
        else veil.visibility = View.VISIBLE
        val from = sheetT
        val to = if (up) 1f else 0f
        sheetAnim?.cancel()
        sheetAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = maxOf(160L, (SHEET_MS * kotlin.math.abs(to - from)).toLong())
            interpolator = Ui.EASE
            addUpdateListener { if (!released) applySheet(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (gen != sheetGen || released) return
                    applySheet(to)
                    if (up) for (v in listOf(scrimSide, scrimBottom, stage)) v.visibility = View.INVISIBLE
                    else veil.visibility = View.INVISIBLE
                    // The sheet's art starts loading once it has slid into place (not competing with the slide).
                    if (up && !sheetShown) { sheetShown = true; flushArt() }
                }
            })
            start()
        }
    }

    /**
     * Back on screen after MainActivity's Back stack held the page detached: any motion that was cut off lands,
     * loaded art is fully shown (a fade whose callback never came would leave it blank), the layers drawn match
     * the sheet's position, and the chrome is back if the trailer had it hidden.
     */
    private fun restoreAfterReattach() {
        sheetAnim?.cancel()
        applySheet(if (sheetUp) 1f else 0f)
        for (v in listOf(scrimSide, scrimBottom, stage)) v.visibility = if (sheetUp) View.INVISIBLE else View.VISIBLE
        veil.visibility = if (sheetUp) View.VISIBLE else View.INVISIBLE
        chrome.animate().cancel()
        chrome.alpha = 1f
        for (iv in images) if (iv.drawable != null) { iv.animate().cancel(); iv.alpha = 1f }
        if (backImg.drawable != null) { backImg.animate().cancel(); backImg.alpha = 1f }
        if (logo.drawable != null) { logo.animate().cancel(); logo.alpha = 1f; titleText.alpha = 0f }
        backLayer.animate().cancel()
        backLayer.alpha = 1f; backLayer.scaleX = 1f; backLayer.scaleY = 1f; backLayer.translationX = 0f; backLayer.translationY = 0f
        root.animate().cancel()
        root.alpha = 1f; root.scaleX = 1f; root.scaleY = 1f
    }

    /** The page at sheet position [t] (0 = stage, 1 = sheet up): everything that moves, from one number. */
    private fun applySheet(t: Float) {
        sheetT = t
        val d = travel
        sheet.translationY = -d * t
        sheet.alpha = 1f
        stage.translationY = -d * 0.35f * t
        stage.alpha = (1f - t / 0.7f).coerceIn(0f, 1f) // the copy is gone before the cards reach it
        veil.alpha = t
        // The scrims hand over to the veil on the same curve, so switching them off at the top changes nothing.
        scrimSide.alpha = 1f - t
        scrimBottom.alpha = 1f - t
        tabRow.alpha = TAB_PEEK_ALPHA + (1f - TAB_PEEK_ALPHA) * t
    }

    // ---- Episodes ----

    /** [out]: the seasons already out; the default season comes from them (never one still to come). */
    private fun episodesPanel(seasons: List<Season>, out: List<Season>): View {
        val sel = next?.season?.takeIf { s -> out.any { it.number == s } } ?: (out.firstOrNull() ?: seasons.first()).number
        chips.clear()
        chipFills.clear()
        val cl = Lane(8)
        for (s in seasons.take(60)) cl.inner.addView(seasonChip(s))
        val el = Lane(14)
        chipsLane = cl
        epLane = el
        val blurb = Blurb(EP_BLURB_W)
        epBlurb = blurb
        season = 0
        showSeason(sel, first = true)
        chips.firstOrNull { it.tag == sel }?.let { c -> cl.post { cl.glide((c.parent as View).left, animate = false) } }
        return flatLinear(LinearLayout.VERTICAL).apply {
            clipChildren = false
            setPadding(0, 12.dp, 0, 0)
            addView(cl, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(el, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 2.dp })
            addView(blurb.view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12.dp })
        }
    }

    /** Season chip (the focusable is its glow box): see [paintChip]. */
    private fun seasonChip(s: Season): View {
        val fill = GradientDrawable().apply { cornerRadius = 18.dp.toFloat() } // the glow's radius: border and fill hug
        val base = s.name.takeIf { it.isNotBlank() && !it.startsWith("Season") && it.length <= 22 } ?: "Season ${s.number}"
        // A season still to come: a smaller, softer "Coming Mar 4" after the name (it keeps the chip's ink, focused or not).
        val text: CharSequence = comingSeasons[s.number]?.let { c ->
            android.text.SpannableStringBuilder(base).append("   ").apply {
                val from = length
                append(Upcoming.chipWords(c))
                setSpan(android.text.style.RelativeSizeSpan(0.82f), from, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(SoftInk(0.62f), from, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } ?: base
        val chip = label(text, 14f, dim, semibold, 1).apply {
            tag = s.number
            gravity = Gravity.CENTER
            setPadding(18.dp, 0, 18.dp, 0)
            background = fill
        }
        chips += chip
        chipFills += fill
        paintChip(chip, fill, false)
        val box = GlowBox(18, CinemaStyle.GLOW_HALO_SMALL, opaqueFace = false)
        box.addView(chip, FrameLayout.LayoutParams(WRAP_CONTENT, 36.dp))
        return box.apply {
            tag = s.number
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, 36.dp).apply { marginEnd = 10.dp }
            setOnClickListener { v -> queueSeason(s.number); flushSeason(); chipsLane?.let { moveBand(it, v, 1) } }
            setOnFocusChangeListener { v, has ->
                focusGlow(box, has)
                if (has) { queueSeason(s.number); chipsLane?.follow(v) }
                paintChip(chip, fill, has)
                lift(v, has, 1.06f)
            }
        }
    }

    /**
     * Focused: the pearl capsule with dark ink (the rainbow border is the chip box's glow). At rest: neutral
     * dark glass, a little brighter with a pearl hairline for the season on show.
     */
    private fun paintChip(v: TextView, fill: GradientDrawable, focused: Boolean) {
        val selected = v.tag == season
        when {
            focused -> { fill.setColor(PearlFocus.PEARL); fill.setStroke(0, 0); v.setTextColor(PearlFocus.INK) }
            selected -> { fill.setColor(Ui.alpha(Color.WHITE, 0.14f)); fill.setStroke(1.dp, Ui.alpha(PearlFocus.PEARL, 0.55f)); v.setTextColor(fg) }
            else -> { fill.setColor(Ui.alpha(Color.WHITE, 0.06f)); fill.setStroke(1.dp, Ui.alpha(Color.WHITE, 0.10f)); v.setTextColor(Ui.alpha(fg, 0.72f)) }
        }
    }

    private fun repaintChips() { for ((i, c) in chips.withIndex()) paintChip(c, chipFills[i], (c.parent as? View)?.isFocused == true) }

    private fun queueSeason(n: Int) {
        pendingSeason = n
        main.removeCallbacks(seasonSwitch)
        seasonQueued = n != season
        if (seasonQueued) main.postDelayed(seasonSwitch, SEASON_DEBOUNCE)
    }

    private fun flushSeason() {
        if (!seasonQueued) return
        main.removeCallbacks(seasonSwitch)
        seasonQueued = false
        showSeason(pendingSeason)
    }

    private fun showSeason(n: Int, first: Boolean = false) {
        val lane = epLane ?: return
        if (n == season) return
        season = n
        repaintChips()
        val gen = ++seasonGen
        if (!first) {
            lane.animate().alpha(0f).setDuration(EP_FADE_OUT).setInterpolator(Ui.SETTLE).start()
            epFadeEnd = SystemClock.uptimeMillis() + EP_FADE_OUT
        } else fillEpisodes(lane, n, null) // skeletons hold the space until the season arrives
        // Out and still to come (Upcoming.kt: TVmaze's exact times, asked only when the season has episodes ahead).
        Net.background({ WatchHistory.seasonEpisodes(ctx, t.id, n)?.let { Upcoming.split(ctx, t.id, t.name, n, it) } }) { split ->
            if (released) return@background
            // Information's Episode Length: the real median of this (the default) season, written in place.
            if (epMinutes == 0) TitleInfo.episodeMinutes(split?.aired).takeIf { it > 0 }?.let { m ->
                epMinutes = m
                infoGrid?.set(TitleInfo.EPISODE_LENGTH, TitleInfo.runtimeWords(m))
            }
            if (gen != seasonGen) return@background
            val eps = split?.aired.orEmpty()
            val coming = split?.upcoming.orEmpty()
            val wait = epFadeEnd - SystemClock.uptimeMillis()
            if (wait > 0) main.postDelayed({ if (!released && gen == seasonGen) fillEpisodes(lane, n, eps, coming) }, wait)
            else fillEpisodes(lane, n, eps, coming)
        }
    }

    /**
     * [eps] null: four quiet placeholders (static, no shimmer). [coming]: the season's episodes still to come,
     * after the ones out ([upcomingCard]); a season with nothing listed yet shows one [seasonComingCard].
     */
    private fun fillEpisodes(lane: Lane, n: Int, eps: List<JSONObject>?, coming: List<JSONObject> = emptyList()) {
        val inner = lane.inner
        val hadFocus = lane.hasFocus()
        if (hadFocus) park()
        clearImages(inner)
        inner.removeAllViews()
        remembered.remove(lane)
        nextCard = null
        var start = 0
        val ahead = comingSeasons[n]
        if (eps == null) {
            repeat(4) { inner.addView(skeleton(CinemaStyle.CARD_W, CinemaStyle.CARD_H)) }
        } else if (eps.isEmpty() && coming.isEmpty() && ahead != null) {
            inner.addView(seasonComingCard(ahead))
            describeSeasonComing(ahead, animate = false)
            lane.scrollTo(0, 0)
        } else if (eps.isEmpty() && coming.isEmpty()) {
            inner.addView(label("No Episodes of Season $n to Watch Yet", 15f, dim, font, 1), LinearLayout.LayoutParams(WRAP_CONTENT, CinemaStyle.CARD_H.dp))
            epBlurb?.set("", "", "")
        } else {
            val next = next
            val nextIdx = if (next != null && next.season == n) eps.indexOfFirst { it.optInt("episode_number") == next.episode } else -1
            start = if (eps.size <= MAX_CARDS) 0 else (nextIdx - 2).coerceIn(0, eps.size - MAX_CARDS)
            val shown = eps.subList(start, minOf(eps.size, start + MAX_CARDS))
            val focusIdx = (nextIdx - start).coerceAtLeast(0)
            for ((i, ep) in shown.withIndex()) inner.addView(episodeCard(n, ep, kotlin.math.abs(i - focusIdx)))
            // Still to come, after the last one out (when the lane reaches it).
            val tail = if (start + shown.size >= eps.size) coming.take(MAX_COMING) else emptyList()
            // The viewer's next episode not out yet: the lane opens on it (it can't play; it says when it comes).
            val nextComing = if (nextIdx < 0 && next != null && next.season == n) tail.indexOfFirst { it.optInt("episode_number") == next.episode } else -1
            for ((i, ep) in tail.withIndex()) {
                val card = upcomingCard(n, ep, shown.size + i - focusIdx.coerceAtMost(shown.size))
                inner.addView(card)
                if (i == nextComing) nextCard = (card as ViewGroup).getChildAt(0)
            }
            if (shown.size < eps.size && playable) inner.addView(allEpisodesCard(eps.size))
            when {
                nextComing >= 0 -> describeEpisode(n, tail[nextComing], animate = false)
                shown.isNotEmpty() -> (shown.getOrNull(focusIdx) ?: shown.first()).let { describeEpisode(n, it, animate = false) }
                else -> describeEpisode(n, tail.first(), animate = false)
            }
            val target = nextCard
            if (target != null && (focusIdx > 0 || nextComing >= 0)) lane.post { lane.glide((target.parent as View).left, animate = false) } else lane.scrollTo(0, 0)
        }
        if (eps == null) return
        lane.animate().alpha(1f).setDuration(EP_FADE_IN).setInterpolator(Ui.EASE).start()
        if (hadFocus) {
            (entryOf(lane) ?: chips.firstOrNull { it.tag == season }?.parent as? View ?: buttons.getChildAt(0))?.requestFocus()
            unpark()
        }
    }

    /** Still with its progress bar (in the glow hue) and an Up Next / Resume tag; "4. Name" and runtime under it. */
    private fun episodeCard(n: Int, ep: JSONObject, rank: Int): View {
        val num = ep.optInt("episode_number")
        val name = ep.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: "Episode $num"
        val next = next
        val upNext = next != null && next.season == n && next.episode == num
        val resume = upNext && next!!.progress > 0f && !next.upNext
        val card = artCard(CinemaStyle.CARD_W, CinemaStyle.CARD_H)
        val still = ep.optString("still_path").takeIf { it.isNotEmpty() && it != "null" }
        // Never a blank tile: TMDB has no still for many new episodes (and a still can fail), so the show's backdrop,
        // then its poster, stand in.
        val showArt = listOfNotNull((t.backdropPath ?: details?.backdropPath)?.let { Tmdb.IMG + "w300" + it }, t.poster("w342"))
        when {
            still != null -> art(card.art, Tmdb.IMG + "w300" + still, rank, showArt)
            showArt.isNotEmpty() -> art(card.art, showArt[0], rank, showArt.drop(1))
            else -> placeholder(card.art, "E$num")
        }
        if (upNext) card.art.addView(tag(if (resume) "Resume" else "Up Next"),
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(10.dp, 10.dp, 0, 0) })
        if (resume) progressBar(card.art, CinemaStyle.CARD_W, next!!.progress)
        card.setOnClickListener { if (resume) host.resume(next!!) else host.playEpisode(t, n, num) }
        if (upNext) nextCard = card
        // Aired in the last three weeks: its real status pill (Available Now · 4K / CAM Only / Not Available Yet). Older ones never get one.
        // Watched (Watched.kt: the viewer finished this very episode): the gold Watched pill instead, no check.
        val airDate = ep.optString("air_date").takeIf { it.length == 10 }.orEmpty()
        val watched = !resume && Watched.episode(ctx, t.id, n, num)
        if (watched) Availability.attachWatched(card.art)
        val pillFocus = if (!resume && !watched && Availability.recentEpisode(0L, airDate))
            Availability.attachTarget(card.art, Availability.episode(t.id, t.name, null, n, num, 0L, airDate)) else null
        return captioned(card, "$num.  $name", epRuntime(ep)) { has -> if (has) describeEpisode(n, ep); pillFocus?.invoke(has) }
    }

    private fun describeEpisode(n: Int, ep: JSONObject, animate: Boolean = true) {
        val num = ep.optInt("episode_number")
        val name = ep.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: "Episode $num"
        if (ep.has(Upcoming.AT)) {
            // Still to come: when, instead of the air date and runtime.
            val sub = "Season $n, Episode $num  ·  " + Upcoming.coming(ep.optLong(Upcoming.AT), Upcoming.date(ep))
            epBlurb?.set(Upcoming.title(ep) ?: name, sub, ep.optString("overview").takeIf { it != "null" }.orEmpty(), animate)
            return
        }
        val date = TitleInfo.longDate(ctx, ep.optString("air_date"))?.let { "Aired $it" }
        val sub = listOfNotNull("Season $n, Episode $num", epRuntime(ep).ifEmpty { null }, date).joinToString("  ·  ")
        epBlurb?.set(name, sub, ep.optString("overview").takeIf { it != "null" }.orEmpty(), animate)
    }

    // ---- still to come (Upcoming.kt) ----

    /**
     * An episode not out yet, the size and spacing of [episodeCard] but quieter: its still dimmed (or a dark
     * neutral face), a date stamp in the middle ("Friday" / "Oct 30" / "9:00 PM"), the red Not Available Yet
     * pill; "S2 · E7  Ace of Spades" over "Coming Friday at 9:00 PM". It can't play: OK only settles its
     * description (no stream lookup, nothing opens).
     */
    private fun upcomingCard(n: Int, ep: JSONObject, rank: Int): View {
        val num = ep.optInt("episode_number")
        val at = ep.optLong(Upcoming.AT)
        val date = Upcoming.date(ep)
        val card = artCard(CinemaStyle.CARD_W, CinemaStyle.CARD_H)
        val still = ep.optString("still_path").takeIf { it.isNotEmpty() && it != "null" }
        if (still != null) {
            art(card.art, Tmdb.IMG + "w300" + still, rank)
            card.art.addView(View(ctx).apply { setBackgroundColor(Color.argb(150, 0, 0, 0)) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else card.art.background = comingFace()
        card.art.addView(dateStamp(at, date), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER).apply { bottomMargin = 14.dp })
        // The red pill says when, in the same words as row cards ("Friday · 9:00 PM", "Coming Nov 14"; Availability.whenForms).
        val stamp = Availability.Entry(Availability.Status.NONE, null, Upcoming.coming(at, date), 0L,
            if (at > 0) at else if (date.length == 10) Availability.midnight(date) else 0L, exact = at > 0)
        card.art.addView(Availability.Pill(ctx).apply { show(stamp) },
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(10.dp, 0, 10.dp, 10.dp) })
        card.tag = COMING
        card.setOnClickListener { describeEpisode(n, ep, animate = false) }
        val title = Upcoming.title(ep)?.let { "S$n · E$num  $it" } ?: "S$n · Episode $num"
        card.onFocus = { has -> if (has) describeEpisode(n, ep) }
        return cardColumn(card, title to Upcoming.coming(at, date), muted = true)
    }

    /** An announced season with no episodes listed yet: one card, "New Season" over its date. */
    private fun seasonComingCard(s: Season): View {
        val card = artCard(CinemaStyle.CARD_W, CinemaStyle.CARD_H)
        card.art.background = comingFace()
        val words = Upcoming.seasonWords(s)
        val stack = flatLinear(LinearLayout.VERTICAL).apply { gravity = Gravity.CENTER_HORIZONTAL }
        // Full card width for both lines, so "New Season" never shrinks to the date's width ("Ne…").
        stack.addView(label("New Season", 12f, dim, semibold, 1).apply { letterSpacing = 0.06f; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        stack.addView(label(if (words == Upcoming.TBA) "To Be Announced" else words.removePrefix("Coming "), 19f, Ui.alpha(fg, 0.9f), semibold, 1),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 8.dp })
        card.art.addView(stack, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply { leftMargin = 16.dp; rightMargin = 16.dp })
        card.tag = COMING
        card.setOnClickListener { describeSeasonComing(s, animate = false) }
        card.onFocus = { has -> if (has) describeSeasonComing(s) }
        return cardColumn(card, "Season ${s.number}" to words, muted = true)
    }

    private fun describeSeasonComing(s: Season, animate: Boolean = true) {
        epBlurb?.set("Season ${s.number}", "Season ${s.number}  ·  ${Upcoming.seasonWords(s)}", "", animate)
    }

    /** The face of a card with no picture yet: dark neutral glass, a faint hairline (no image to load). */
    private fun comingFace(): Drawable = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.rgb(28, 28, 31), Color.rgb(14, 14, 16))).apply {
        cornerRadius = CinemaStyle.R_CARD.dp.toFloat()
        setStroke(1.dp, Ui.alpha(Color.WHITE, 0.08f))
    }

    /** "Friday" / "Oct 30" / "9:00 PM" (the parts known), "2027", or "Date to Be Announced", centered on a card. */
    private fun dateStamp(at: Long, date: String): View {
        val col = flatLinear(LinearLayout.VERTICAL).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val day: Date? = when {
            at > 0 -> Date(at)
            date.length == 10 -> try { ymd.parse(date) } catch (e: Exception) { null }
            else -> null
        }
        if (day == null) {
            col.addView(label(if (date.length == 4) date else Upcoming.TBA, if (date.length == 4) 24f else 15f, Ui.alpha(fg, 0.88f), semibold, 1))
            return col
        }
        // An exact instant shows in the viewer's chosen zone; a date-only air day stays that day (no zone math).
        fun f(p: String) = if (at > 0) DreamTime.fmt(ctx, p) else SimpleDateFormat(p, Locale.US)
        col.addView(label(f("EEEE").format(day), 12f, dim, semibold, 1).apply { letterSpacing = 0.06f })
        val thisYear = f("yyyy").format(day) == DreamTime.fmt(ctx, "yyyy").format(Date())
        col.addView(label(f(if (thisYear) "MMM d" else "MMM d, yyyy").format(day), 24f, Ui.alpha(fg, 0.92f), semibold, 1),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 6.dp })
        if (at > 0) col.addView(label(DreamTime.formatTime(ctx, at), 13f, dim, medium, 1),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 6.dp })
        return col
    }

    private fun allEpisodesCard(total: Int): View {
        val card = artCard(CinemaStyle.CARD_W, CinemaStyle.CARD_H)
        placeholder(card.art, "All $total Episodes")
        card.setOnClickListener { host.watch(t, true) }
        return captioned(card, "All Episodes", "Pick Any Episode") { has -> if (has) epBlurb?.set("All $total Episodes", "Season $season", "") }
    }

    private fun epRuntime(ep: JSONObject): String {
        val rt = ep.optInt("runtime")
        return when {
            rt >= 60 -> if (rt % 60 == 0) "${rt / 60}h" else "${rt / 60}h ${rt % 60}m"
            rt > 0 -> "$rt Min"
            else -> ""
        }
    }

    // ---- More Like This ----

    private fun morePanel(recs: List<Title>): View {
        val lane = Lane(14)
        recLane = lane
        for (i in 0 until minOf(recs.size, MAX_CARDS)) lane.inner.addView(skeleton(CinemaStyle.POSTER_W, CinemaStyle.POSTER_H))
        val blurb = Blurb(EP_BLURB_W)
        recBlurb = blurb
        recs.firstOrNull()?.let { describeRec(it, animate = false) }
        return flatLinear(LinearLayout.VERTICAL).apply {
            clipChildren = false
            setPadding(0, 6.dp, 0, 0)
            addView(lane, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(blurb.view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        }
    }

    /** Posters are built (and their art fetched) the first time the tab is shown. */
    private fun fillRecs(recs: List<Title>) {
        val lane = recLane ?: return
        lane.inner.removeAllViews()
        for ((i, o) in recs.take(MAX_CARDS).withIndex()) {
            val card = artCard(CinemaStyle.POSTER_W, CinemaStyle.POSTER_H)
            Ratings.prefetch(ctx, o, card)
            val url = o.poster("w342")
            if (url != null) art(card.art, url, i) else placeholder(card.art, o.name)
            card.setOnClickListener { host.openDetails(o) }
            card.setOnLongClickListener { host.titleMenu(o); true }
            // A new title's status pill (Availability), like on every row card.
            val pillFocus = Availability.attachTitle(card.art, o)
            card.onFocus = { has -> if (has) describeRec(o); pillFocus?.invoke(has) }
            val sub = listOfNotNull(o.year.take(4).ifEmpty { null }, if (o.type == "tv") "Series" else "Movie").joinToString("  ·  ")
            lane.inner.addView(cardColumn(card, o.name to sub))
        }
    }

    private fun describeRec(o: Title, animate: Boolean = true) {
        val sub = listOfNotNull(if (o.type == "tv") "Series" else "Movie", o.year.ifEmpty { null },
            Ratings.audienceFor(null, o)?.let { (src, v) -> "$src $v" }).joinToString("  ·  ")
        recBlurb?.set(o.name, sub, o.overview, animate)
    }

    // ---- Details ----

    /** About (the full synopsis), the facts in two columns, then the cast lane. */
    private fun detailsPanel(d: Details): View {
        val about = label(d.overview.ifEmpty { t.overview }.ifEmpty { "No description available." }, 14.5f, Ui.alpha(fg, 0.86f), font, 6).apply {
            setLineSpacing(0f, 1.25f)
        }
        // The facts live in the Information tab now (TitleInfo.kt): About is the story, its tagline over it.
        val tagline = d.info?.tagline.orEmpty()
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(CinemaStyle.GUTTER.dp, 0, CinemaStyle.GUTTER.dp, 0)
            addView(heading("About"))
            if (tagline.isNotEmpty()) addView(label(tagline, 15f, fg, semibold, 1),
                LinearLayout.LayoutParams(ABOUT_W.dp, WRAP_CONTENT).apply { topMargin = 10.dp })
            addView(about, LinearLayout.LayoutParams(ABOUT_W.dp, WRAP_CONTENT).apply { topMargin = 10.dp })
        }
        val col = flatLinear(LinearLayout.VERTICAL).apply {
            clipChildren = false
            setPadding(0, 18.dp, 0, 0)
            addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        if (d.cast.isNotEmpty()) {
            val lane = Lane(12, CinemaStyle.GUTTER - (CAST_W - CAST_SIZE) / 2) // the portrait, not its caption box, on the gutter
            castLane = lane
            col.addView(heading("Cast").apply { setPadding(CinemaStyle.GUTTER.dp, 0, 0, 0) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 20.dp })
            col.addView(lane, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        return col
    }

    private fun fillCast(cast: List<CastMember>) {
        val lane = castLane ?: return
        for ((i, c) in cast.take(15).withIndex()) lane.inner.addView(castItem(c, i))
    }

    // ---- Information ----

    /**
     * Apple TV-style Information (TitleInfo.kt): every fact TMDB has, label over value in four columns, built
     * once here and static after (the ratings and the next episode's exact time rewrite one value in place).
     * Null when there's nothing to show.
     */
    private fun informationPanel(info: TitleInfo): View? {
        val next = if (info.nextSeason > 0) AirTimes.peek(t.id)?.next?.takeIf { it.season == info.nextSeason && it.number == info.nextEpisode } else null
        val cells = info.cells(ctx, rating, next?.at ?: 0L, epMinutes)
        if (cells.isEmpty()) return null
        val w = ctx.resources.displayMetrics.widthPixels - 2 * CinemaStyle.GUTTER.dp
        val grid = InfoGrid(ctx, cells, w, Ui.alpha(fg, 0.94f), dim, semibold, medium)
        infoGrid = grid
        Log.w(TAG, "Info: ${t.name}, ${grid.count} fields")
        // The next episode's exact time (TVmaze, usually cached already by the status line).
        if (info.nextSeason > 0 && next == null) Net.background({ AirTimes.info(ctx, t.id) }) { a ->
            if (released) return@background
            val at = a?.next?.takeIf { it.season == info.nextSeason && it.number == info.nextEpisode }?.at ?: return@background
            grid.set("Next Episode", info.nextWords(ctx, at))
        }
        return flatLinear(LinearLayout.VERTICAL).apply {
            setPadding(CinemaStyle.GUTTER.dp, 22.dp, CinemaStyle.GUTTER.dp, 0)
            addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    /** Round portrait that glows when focused, name and role under it. OK does nothing. */
    private fun castItem(c: CastMember, rank: Int): View {
        val box = GlowBox(CAST_SIZE / 2)
        val circle = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.alpha(fg, 0.08f)) }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        val url = c.profile("w185")
        if (url != null) {
            val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            circle.addView(iv, MATCH_PARENT, MATCH_PARENT)
            loadArt(iv, url, rank, fade = false)
        } else {
            val initials = c.name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase()
            circle.addView(label(initials, 22f, dim, semibold, 1).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        }
        box.addView(circle, FrameLayout.LayoutParams(CAST_SIZE.dp, CAST_SIZE.dp))
        val name = label(c.name, 13.5f, Ui.alpha(fg, 0.8f), medium, 1).apply { gravity = Gravity.CENTER; ellipsize = TextUtils.TruncateAt.END }
        val role = label(c.character, 12f, dim, font, 1).apply { gravity = Gravity.CENTER; ellipsize = TextUtils.TruncateAt.END }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            isFocusable = true
            addView(box, LinearLayout.LayoutParams(CAST_SIZE.dp, CAST_SIZE.dp))
            addView(name, LinearLayout.LayoutParams(CAST_W.dp, WRAP_CONTENT).apply { topMargin = 12.dp })
            if (c.character.isNotBlank()) addView(role, LinearLayout.LayoutParams(CAST_W.dp, WRAP_CONTENT).apply { topMargin = 4.dp })
            layoutParams = LinearLayout.LayoutParams(CAST_W.dp, WRAP_CONTENT).apply { marginEnd = 8.dp }
            setOnFocusChangeListener { v, has ->
                focusGlow(box, has)
                lift(box, has, CinemaStyle.FOCUS_SCALE)
                name.setTextColor(if (has) fg else Ui.alpha(fg, 0.8f))
                if (has) castLane?.follow(v)
            }
        }
    }

    // ---- cards ----

    /** A focusable card: glow halo behind, rounded clipped art frame ([art]) on top. */
    private inner class Card(w: Int, h: Int) : GlowBox(CinemaStyle.R_CARD) {
        val art = FrameLayout(ctx).apply {
            background = Ui.rounded(ctx, Ui.alpha(fg, 0.07f), CinemaStyle.R_CARD)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        var onFocus: ((Boolean) -> Unit)? = null
        init {
            addView(art, LayoutParams(w.dp, h.dp))
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(w.dp, h.dp)
            setOnFocusChangeListener { v, has ->
                focusGlow(this, has)
                lift(v, has, CinemaStyle.FOCUS_SCALE)
                onFocus?.invoke(has)
                if (has) laneOf(v)?.follow(v)
            }
        }
    }

    private fun artCard(w: Int, h: Int) = Card(w, h)

    private fun art(frame: FrameLayout, url: String, rank: Int, fallbacks: List<String> = emptyList()) {
        val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(iv, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        loadArt(iv, url, rank, fade = true, fallbacks = fallbacks)
    }

    /**
     * Sheet art loads only once the sheet has been raised: while the stage is up the cards are off screen,
     * and every image landing there would still cost a full-screen redraw over the backdrop. It fades in
     * only where it's seen.
     */
    private fun loadArt(iv: ImageView, url: String, rank: Int, fade: Boolean, fallbacks: List<String> = emptyList()) {
        if (!sheetShown) { pendingArt += { loadArt(iv, url, rank, fade, fallbacks) }; return }
        images += iv
        if (fade) iv.alpha = 0f
        Net.image(ctx, iv, url, fallbacks, rank = rank) {
            if (fade) { if (sheetUp) iv.animate().alpha(1f).setDuration(Ui.FADE_IMAGE).setInterpolator(Ui.EASE).start() else iv.alpha = 1f }
        }
    }

    private fun flushArt() {
        val list = ArrayList(pendingArt)
        pendingArt.clear()
        for (f in list) f()
    }

    private fun placeholder(frame: FrameLayout, s: String) {
        frame.addView(label(s, 15f, dim, semibold, 2).apply { gravity = Gravity.CENTER; setPadding(12.dp, 0, 12.dp, 0) },
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun skeleton(w: Int, h: Int) = View(ctx).apply {
        background = Ui.rounded(ctx, Ui.alpha(fg, 0.06f), CinemaStyle.R_CARD)
        layoutParams = LinearLayout.LayoutParams(w.dp, h.dp).apply { marginEnd = CinemaStyle.CARD_GAP.dp }
    }

    private fun tag(s: String) = label(s, 11.5f, fg, semibold, 1).apply {
        setPadding(9.dp, 4.dp, 9.dp, 4.dp)
        background = Ui.rounded(ctx, Ui.alpha(ink, 0.72f), 100)
    }

    private fun progressBar(frame: FrameLayout, wDp: Int, p: Float) {
        val track = FrameLayout(ctx).apply { background = Ui.rounded(ctx, Ui.alpha(Color.WHITE, 0.3f), 2) }
        val fill = View(ctx)
        accentFill(fill, 2)
        track.addView(fill, FrameLayout.LayoutParams(((wDp - 20) * p.coerceIn(0.03f, 1f)).toInt().dp, MATCH_PARENT))
        frame.addView(track, FrameLayout.LayoutParams((wDp - 20).dp, 4.dp, Gravity.BOTTOM or Gravity.START).apply { setMargins(10.dp, 0, 0, 10.dp) })
    }

    /** A progress fill: pearl (static: nothing re-tints off-focus accents per frame). */
    private fun accentFill(v: View, radiusDp: Int) {
        val g = GradientDrawable().apply { cornerRadius = radiusDp.dp.toFloat(); setColor(PearlFocus.PEARL) }
        v.background = g
    }

    /** [card] with its caption: title and a dim line, brightening while the card has focus. */
    private fun captioned(card: Card, title: String, sub: String, onFocus: (Boolean) -> Unit): View {
        card.onFocus = onFocus
        return cardColumn(card, title to sub)
    }

    /** [muted]: a title still to come, its caption a step softer. */
    private fun cardColumn(card: Card, caption: Pair<String, String>?, muted: Boolean = false): View {
        val w = card.layoutParams.width
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = CinemaStyle.CARD_GAP.dp }
        }
        col.addView(card)
        if (caption == null) return col
        val cap = flatLinear(LinearLayout.VERTICAL).apply { alpha = CAPTION_REST }
        cap.addView(label(caption.first, CinemaStyle.T_CARD_TITLE, if (muted) Ui.alpha(fg, 0.74f) else fg, medium, 1).apply { ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(w, WRAP_CONTENT))
        if (caption.second.isNotEmpty()) cap.addView(label(caption.second, 12.5f, dim, font, 1), LinearLayout.LayoutParams(w, WRAP_CONTENT).apply { topMargin = 4.dp })
        col.addView(cap, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 12.dp })
        val base = card.onFocus
        val h = card.layoutParams.height
        card.onFocus = { has ->
            base?.invoke(has)
            cap.animate().alpha(if (has) 1f else CAPTION_REST).translationY(if (has) (CinemaStyle.FOCUS_SCALE - 1f) * h / 2f else 0f)
                .setDuration(CinemaStyle.FOCUS_MS).setInterpolator(Ui.EASE).start()
        }
        return col
    }

    private fun heading(s: String) = label(s, CinemaStyle.T_ROW, fg, semibold, 1)

    /** The focused item's name, a dim facts line and up to three lines of its synopsis, under a lane. */
    private inner class Blurb(widthDp: Int) {
        // The card above already shows the title: the description leads with its facts (never repeats the name).
        private val name = label("", 17f, fg, semibold, 1).apply { ellipsize = TextUtils.TruncateAt.END; visibility = View.GONE }
        private val sub = label("", 14f, Ui.alpha(fg, 0.9f), medium, 1)
        private val body = label("", 14f, Ui.alpha(fg, 0.8f), font, 3).apply { setLineSpacing(0f, 1.2f); minLines = 3 }
        val view = flatLinear(LinearLayout.VERTICAL).apply {
            setPadding(CinemaStyle.GUTTER.dp, 0, 0, 0)
            addView(name, LinearLayout.LayoutParams(widthDp.dp, WRAP_CONTENT))
            addView(sub, LinearLayout.LayoutParams(widthDp.dp, WRAP_CONTENT))
            // Fixed height (three lines): new text never asks the page for a layout pass.
            addView(body, LinearLayout.LayoutParams(widthDp.dp, body.lineHeight * 3 + 2.dp).apply { topMargin = 9.dp })
        }
        private var pending: Runnable? = null

        /** Settles a beat after focus does, so sweeping a lane doesn't rewrite text every step. */
        fun set(n: String, s: String, b: String, animate: Boolean = true) {
            pending?.let { main.removeCallbacks(it) }
            val r = Runnable {
                if (released) return@Runnable
                name.text = n
                sub.text = s
                body.text = b
                if (animate) {
                    view.alpha = 0.35f
                    view.animate().alpha(1f).setDuration(180).setInterpolator(Ui.EASE).start()
                }
            }
            pending = r
            if (animate) main.postDelayed(r, BLURB_REST) else r.run()
        }
    }

    // ---- focus glow ----

    /**
     * A focusable frame wearing CinemaStyle's rainbow glow as its background: the halo draws past the frame's
     * edges (no parent of a glowing item clips), the content covers its middle. Corner [radiusDp] must be at
     * most half the frame's height (pills pass that, not R_CHIP).
     */
    private open inner class GlowBox(radiusDp: Int, haloDp: Int = CinemaStyle.GLOW_HALO, val opaqueFace: Boolean = true) : FrameLayout(ctx) {
        val glow = CinemaStyle.glowDrawable(ctx, CinemaStyle.glowNow, radiusDp, haloDp)
        init {
            clipChildren = false
            clipToPadding = false
            background = glow
        }
    }

    /** The glow off at once (and off the shared clock). */
    private fun dropGlow(g: CinemaGlow) { g.snap(g.hue, false); CinemaStyle.setLive(g, false) }

    /** Focus arrives: the glow fades up in the rainbow color of the moment (the shared clock). */
    private fun focusGlow(box: GlowBox, has: Boolean) {
        // The halo reaches a corner radius inside the edge; only an opaque face hides that band, so over glass
        // (buttons and chips at rest) the glow goes at once instead of fading out as a hard inner box.
        if (has || box.opaqueFace) CinemaStyle.focusGlow(box.glow, has) else dropGlow(box.glow)
        if (has) setHue(CinemaStyle.glowNow)
    }

    /** A focus without a glow of its own: keeps [hue] current. */
    private fun stepHue() = setHue(CinemaStyle.nextGlow())

    /** The glow color the latest focus took (the accents follow the shared clock via [CinemaStyle.followGlow]). */
    private fun setHue(c: Int) { hue = c }

    /** Scale-only lift (no elevation shadow: the halo is the depth). */
    private fun lift(v: View, has: Boolean, s: Float) {
        v.animate().scaleX(if (has) s else 1f).scaleY(if (has) s else 1f).setDuration(if (has) Ui.FOCUS_IN else Ui.FOCUS_OUT)
            .setInterpolator(if (has) Ui.SPRING else Ui.SETTLE).start()
    }

    // ---- lanes ----

    /**
     * A horizontal lane that keeps the focused item at the gutter (Netflix), gliding there itself rather
     * than letting the scroll view jump; vertical padding is room for the lift and the glow.
     */
    private inner class Lane(padV: Int, startDp: Int = CinemaStyle.GUTTER) : HorizontalScrollView(ctx) {
        val inner = LinearLayout(ctx)
        private var anim: ValueAnimator? = null
        init {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
            clipChildren = false
            clipToPadding = false
            setPadding(startDp.dp, padV.dp, CinemaStyle.GUTTER.dp, padV.dp)
            inner.orientation = LinearLayout.HORIZONTAL
            inner.clipChildren = false
            inner.clipToPadding = false
            addView(inner, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect) = 0
        fun follow(v: View) { topChild(inner, v)?.let { glide(it.left) } }
        fun glide(x: Int, animate: Boolean = true) {
            val max = maxOf(0, inner.width - (width - paddingLeft - paddingRight))
            val to = x.coerceIn(0, max)
            anim?.cancel()
            if (!animate) { scrollTo(to, 0); return }
            if (to == scrollX) return
            anim = ValueAnimator.ofInt(scrollX, to).apply {
                duration = CinemaStyle.ROW_SCROLL_MS
                interpolator = Ui.EASE
                addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
                start()
            }
        }
    }

    // ---- focus bands ----

    private fun bands(): List<ViewGroup> = listOf<ViewGroup>(buttons, tabRow) + (tabs.getOrNull(tab)?.bands ?: emptyList())

    private fun bandOf(v: View): ViewGroup? {
        val all = bands()
        var p: View? = v
        while (p != null && p !== root) {
            for (b in all) if (b === p) return b
            p = p.parent as? View
        }
        return null
    }

    private fun navigate(code: Int): Boolean {
        val f = root.findFocus() ?: return false
        // The synopsis sits above the actions: Up reaches it, Down goes back, Left/Right stay.
        if (f === synopsis) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> (remembered[buttons]?.takeIf { it.isAttachedToWindow } ?: entryOf(buttons))?.requestFocus()
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {}
                else -> return false
            }
            return true
        }
        val band = bandOf(f) ?: return false
        if (band === buttons && code == KeyEvent.KEYCODE_DPAD_UP && synopsis.isShown && !synopsis.text.isNullOrEmpty()) {
            remembered[buttons] = f
            synopsis.requestFocus()
            return true
        }
        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> { moveBand(band, f, -1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveBand(band, f, 1); true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val dir = if (code == KeyEvent.KEYCODE_DPAD_LEFT) View.FOCUS_LEFT else View.FOCUS_RIGHT
                FocusFinder.getInstance().findNextFocus(band, f, dir)?.requestFocus()
                true
            }
            else -> false
        }
    }

    private fun moveBand(band: ViewGroup, from: View, dir: Int) {
        if (band === tabRow && dir > 0) flushTab()
        remembered[band] = topChild(band, from) ?: from
        val list = bands()
        var i = list.indexOf(band) + dir
        while (i in list.indices) {
            val b = list[i]
            if (b === epLane) flushSeason()
            val target = entryOf(b)
            if (target != null) {
                setSheet(b !== buttons) // first: the stage must be visible again before a button can take focus
                target.requestFocus()
                return
            }
            i += dir
        }
    }

    /** Where focus lands in [b]: the item last focused there, else the current tab / season / next episode / first. */
    private fun entryOf(b: ViewGroup): View? {
        remembered[b]?.let { if (it.isAttachedToWindow && it.isFocusable && isInside(it, b)) return it }
        val preferred = when {
            b === tabRow -> tabs.getOrNull(tab)?.view
            b === chipsLane -> chips.firstOrNull { it.tag == season }?.parent as? View
            b === epLane -> nextCard?.takeIf { it.isAttachedToWindow && isInside(it, b) }
            else -> null
        }
        return preferred ?: ArrayList<View>().also { b.addFocusables(it, View.FOCUS_DOWN) }.firstOrNull()
    }

    private fun laneOf(v: View): Lane? {
        var p: android.view.ViewParent? = v.parent
        while (p != null) { if (p is Lane) return p; p = p.parent }
        return null
    }

    /** The child of [group] that holds [v] (or is it). */
    private fun topChild(group: ViewGroup, v: View): View? {
        var p: View? = v
        while (p != null) {
            val parent = p.parent
            if (parent === group) return p
            p = parent as? View
        }
        return null
    }

    private fun isInside(v: View, ancestor: View): Boolean {
        var p: Any? = v.parent
        while (p is View) { if (p === ancestor) return true; p = p.parent }
        return false
    }

    private fun park() { parking.isFocusable = true; parking.requestFocus() }

    private fun unpark() { if (!parking.isFocused) parking.isFocusable = false }

    // ---- background trailer ----

    /**
     * The trailer takes the screen: everything over the backdrop fades as one layer ([chrome]) and comes back.
     * Only the chrome's alpha is touched, so the sheet's position and the parts' own fades are left alone.
     */
    private fun trailerImmersive(on: Boolean, animate: Boolean) {
        if (released) return
        chrome.animate().cancel()
        val to = if (on) 0f else 1f
        if (!animate || closing) { chrome.alpha = to; return }
        chrome.animate().alpha(to).setStartDelay(0).setDuration(if (on) IMMERSIVE_OUT_MS else IMMERSIVE_IN_MS).setInterpolator(Ui.EASE).start()
    }

    // ---- helpers ----

    private fun resumeEntry(): WatchHistory.Entry? = WatchHistory.entries(ctx).firstOrNull {
        it.key == t.key && !it.upNext && it.progress > 0f && it.progress < WatchHistory.FINISHED &&
            (!it.isShow || (it.season > 0 && it.episode > 0))
    }

    private fun resumeLabel(e: WatchHistory.Entry): String = if (e.isShow) "Resume S${e.season} E${e.episode}" else "Resume"

    private fun aired(date: String) = date.isNotEmpty() && date != "null" && date <= today

    private fun clearImages(group: ViewGroup) {
        val it = images.iterator()
        while (it.hasNext()) {
            val iv = it.next()
            if (isInside(iv, group)) { iv.animate().cancel(); iv.tag = null; iv.setImageDrawable(null); it.remove() }
        }
    }

    private fun label(s: CharSequence, sp: Float, color: Int, tf: Typeface, lines: Int) = TextView(ctx).apply {
        text = s
        textSize = sp
        setTextColor(color)
        typeface = tf
        includeFontPadding = false
        maxLines = lines
        if (lines == 1) isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun Float.dpi() = maxOf(1, (this * density).toInt())

    /** Text drawn in its view's own color at [a] of its opacity (follows focus ink changes). */
    private class SoftInk(private val a: Float) : android.text.style.CharacterStyle() {
        override fun updateDrawState(tp: android.text.TextPaint) { tp.alpha = (tp.alpha * a).toInt() }
    }

    private fun flat(): View = object : View(ctx) { override fun hasOverlappingRendering() = false }
    private fun flatFrame(): FrameLayout = object : FrameLayout(ctx) { override fun hasOverlappingRendering() = false }
    private fun flatLinear(o: Int): LinearLayout = object : LinearLayout(ctx) { override fun hasOverlappingRendering() = false }.apply { orientation = o }

    companion object {
        /** The synopsis' focus face reaches this far past the text on each side (dp). */
        private const val SYN_PAD = 14
        private const val TAG = "FireHome"
        private val EVAL = ArgbEvaluator()
        private const val EPISODES = "Episodes"
        /** How much of the sheet (its tab bar) peeks under the stage (dp). */
        private const val PEEK = 64
        /** Where the sheet's top settles once it has slid up (dp). */
        private const val SHEET_TOP = 34
        private const val TAB_H = 48
        /** A focused tab's pearl capsule height (dp). */
        private const val TAB_PILL = 36
        private const val TAB_PAD = 18
        private const val TAB_PEEK_ALPHA = 0.8f
        private const val LOGO_W = 420
        private const val LOGO_H = 128
        private const val T_TITLE = 42f
        private const val TEXT_W = 540
        private const val EP_BLURB_W = 620
        private const val BUTTON_H = 46
        private const val MY_LIST = "My List"
        private const val ADDED = "Added"
        private const val PROGRESS_W = 150
        private const val CAST_SIZE = 92
        private const val CAST_W = 118
        /** The About tab's text column (dp): a comfortable reading width. */
        private const val ABOUT_W = 700
        private const val MAX_CARDS = 24
        /** Upcoming episodes shown after the ones out. */
        private const val MAX_COMING = 12
        /** Tag of a card still to come (OK there opens nothing). */
        private const val COMING = "coming"
        private const val SHEET_MS = 440L
        private const val ENTER_MS = 460L
        private const val TAB_SLIDE_MS = 260L
        private const val TAB_DEBOUNCE = 160L
        private const val SEASON_DEBOUNCE = 250L
        private const val BLURB_REST = 110L
        private const val EP_FADE_OUT = 120L
        private const val EP_FADE_IN = 220L
        /** Card captions always show (titles are never hidden); focus only carries them down with the lift. */
        private const val CAPTION_REST = 1f
        private const val IMMERSIVE_OUT_MS = 600L
        private const val IMMERSIVE_IN_MS = 420L
        private val PASS_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)
        private val SELECT_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }
}
