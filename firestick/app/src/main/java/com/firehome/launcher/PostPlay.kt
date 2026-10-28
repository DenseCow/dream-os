package com.firehome.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Shared look for the play flow's own buttons: drawn selected / at rest by hand (no Android focus on the player). */
object PostPlayStyle {
    fun pillState(kit: PremiumKit, b: TextView, on: Boolean) {
        val ctx = b.context
        // Cinema's button: glass at rest, pearl with ink when selected, lifted a touch (no elevation).
        b.background = if (on) android.graphics.drawable.LayerDrawable(arrayOf(
            CinemaStyle.glowDrawable(ctx, radiusDp = ((b.layoutParams?.height ?: 0).takeIf { it > 0 }?.let { (it / 2 / ctx.resources.displayMetrics.density).toInt() } ?: 22), haloDp = CinemaStyle.GLOW_HALO_SMALL).apply { haloAlpha = 0.44f; PlayerLook.border(b, this, true) },
            Ui.rounded(ctx, Rainbow.PLAY_REST, Ui.R_PILL)))
            else Ui.rounded(ctx, Ui.alpha(Ui.mix(PlayerLook.shade(ctx), kit.text, 0.12f), 0.80f), Ui.R_PILL, 1f, Ui.alpha(kit.text, 0.16f))
        b.setTextColor(if (on) PlayerLook.ink(ctx) else kit.text)
        val s = if (on) 1.06f else 1f
        b.animate().scaleX(s).scaleY(s).setStartDelay(0).setDuration(CinemaStyle.FOCUS_MS).setInterpolator(Ui.EASE).start()
    }
}

/**
 * Up next countdown, "Are you still watching?", and end-of-movie recommendations.
 *
 * Episodes: about 90 s before the credits ([SkipMarkers], with a fallback near the end) the next
 * episode is looked up (TMDB season data, rolling over to the next season) and its still fetched,
 * and its stream found ahead; at the credits an Up next card counts down 10 s (OK plays now, BACK keeps the
 * credits), then [PlayerHost.playNext]. At the end of a series, or for movies, "More like this"
 * (TMDB recommendations) with Watch credits / Back to browse. After 3 episodes in a row that the
 * countdown started with no key pressed, the next one pauses and asks "Are you still watching?".
 * Respects [PlaybackRequest.autoplayNext] and Settings › Autoplay next episode.
 */
class PostPlayController(private val host: PlayerHost) {
    private val ctx: Context = host.overlayRoot.context
    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private val Int.dp get() = (this * density).toInt()
    private var kit: PremiumKit? = null

    private enum class Mode { NONE, UP_NEXT, MORE, STILL }

    private var running = false
    private var req: PlaybackRequest? = null
    private var mode = Mode.NONE
    private var panel: View? = null
    private var markersAsked = false
    private var creditsAt: Long? = null
    private var preparing = false
    private var prepared = false
    private var next: PlayFlow.NextUp? = null
    private var recs: List<Title> = emptyList()
    private var dismissed = false
    private var ended = false
    private var advanced = false
    private var startPos = -1L
    private var stillAsked = false
    private var countdown: ValueAnimator? = null
    // Hand-drawn selection inside a panel.
    private var rows: List<List<View>> = emptyList()
    private var row = 0
    private var col = 0
    private var actions: Map<View, () -> Unit> = emptyMap()

    private val listener = object : PlayerEngine.Listener {
        override fun onProgress(positionMs: Long) = progress(positionMs)
        override fun onEnded() = ended()
    }

    /** Left alone after the end with a panel up: back to Dream OS. */
    private val idleClose = Runnable { if (running && ended) host.close() }

    fun start() {
        stop()
        running = true
        req = host.request
        mode = Mode.NONE
        markersAsked = false; creditsAt = null; preparing = false; prepared = false
        next = null; recs = emptyList(); dismissed = false; ended = false; advanced = false
        startPos = -1L; stillAsked = false
        host.engine.addListener(listener)
    }

    fun stop() {
        running = false
        host.engine.removeListener(listener)
        removePanel(animate = false)
        main.removeCallbacksAndMessages(null)
    }

    private fun autoplay(r: PlaybackRequest) = r.autoplayNext && PlayFlow.autoplayNext(ctx)

    // ---- timeline ----

    private fun progress(pos: Long) {
        if (!running) return
        val r = req ?: host.request?.also { req = it } ?: return
        if (startPos < 0) startPos = pos
        if (!stillAsked && r.type == "episode" && PlayFlow.autoplayedInARow >= 3 && PlayFlow.stillWatchingCheck(ctx) && pos - startPos > 3000) {
            stillAsked = true
            showStillWatching(r)
            return
        }
        val dur = host.engine.durationMs
        if (dur <= 0) return
        if (!markersAsked) {
            markersAsked = true
            SkipMarkers.get(ctx, r, dur) { m ->
                if (running && req === r) creditsAt = m.creditsStartMs?.takeIf { it in (dur / 2) until dur } ?: fallbackCredits(r, dur)
            }
            return
        }
        val c = creditsAt ?: return
        if (!prepared && !preparing && pos >= c - 90_000) prepare(r)
        when {
            // Seeked back before the credits: put the card away; it comes back at the credits.
            (mode == Mode.UP_NEXT || mode == Mode.MORE) && pos < c - 5_000 -> { removePanel(); dismissed = false }
            mode == Mode.NONE && dismissed && pos < c - 5_000 -> dismissed = false
            mode == Mode.NONE && !dismissed && pos >= c -> showPostPlay(r)
        }
    }

    /** Without markers: the last 30 s of an episode, the last ~6 % (at most 3 min) of a movie. */
    private fun fallbackCredits(r: PlaybackRequest, dur: Long): Long =
        if (r.type == "episode") (dur - 30_000).coerceAtLeast(dur * 9 / 10)
        else (dur - minOf(180_000L, dur * 6 / 100)).coerceAtLeast(dur * 9 / 10)

    /**
     * Looks up what comes next ahead of the credits, and has its stream found ([PreResolve.ahead]:
     * link only; [PlayFlow.play] takes it when the episode starts).
     */
    private fun prepare(r: PlaybackRequest) {
        preparing = true
        PlayFlow.warmUp(ctx)
        val app = ctx.applicationContext
        Net.background({
            val n = PlayFlow.nextEpisode(app, r)
            val more = if (n != null) emptyList() else try {
                val t = Title(r.tmdbId, if (r.type == "episode") "tv" else "movie", r.title, "", null, null, r.year?.toString() ?: "", 0.0)
                Tmdb.details(app, t).recommendations.filter { it.backdropPath != null }.take(4)
            } catch (e: Exception) { emptyList() }
            n to more
        }) { result ->
            preparing = false
            if (!running || req !== r) return@background
            prepared = true
            next = result?.first
            recs = result?.second.orEmpty()
            next?.let { PreResolve.ahead(app, it.request) } // its link is ready well before the countdown ends
            next?.still?.let { Net.bitmap(ctx, it) {} } // warm the image cache for the card
            for (t in recs) t.backdrop("w300")?.let { Net.bitmap(ctx, it) {} }
            if (ended) ended() // the end came before the lookup finished
        }
    }

    private fun showPostPlay(r: PlaybackRequest) {
        if (!prepared) { if (!preparing) prepare(r); return } // shown once the lookup lands (next progress tick)
        // Post-play means it's over: watched now (a movie leaves Continue Watching, a show moves on to the next episode).
        markWatched()
        val n = next
        if (r.type == "episode" && n != null) showUpNext(n, countdown = autoplay(r))
        else if (recs.isNotEmpty()) showMore(r)
        else dismissed = true // nothing to offer: let the credits roll
    }

    /** The player saves this title as watched right away (not only at the very end, or on the way out). */
    private fun markWatched() {
        try { (host as? DreamPlayerActivity)?.onCreditsStarted() } catch (e: Exception) {}
    }

    private fun ended() {
        if (!running || advanced) return
        ended = true
        val r = req ?: return
        if (!prepared) { if (!preparing) prepare(r); return }
        val n = next
        when {
            r.type == "episode" && n != null && autoplay(r) -> advance(n, auto = mode != Mode.UP_NEXT || countdown != null)
            r.type == "episode" && n != null -> if (mode != Mode.UP_NEXT) showUpNext(n, countdown = false)
            recs.isNotEmpty() -> if (mode != Mode.MORE) showMore(r)
            else -> { host.close(); return }
        }
        main.removeCallbacks(idleClose)
        main.postDelayed(idleClose, 90_000)
    }

    private fun advance(n: PlayFlow.NextUp, auto: Boolean) {
        if (advanced) return
        advanced = true
        PlayFlow.autoplayedInARow = if (auto) PlayFlow.autoplayedInARow + 1 else 0
        removePanel(animate = false)
        host.playNext(n.request)
    }

    // ---- keys ----

    fun onKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return false // onBack
        if (event.action == KeyEvent.ACTION_DOWN) PlayFlow.autoplayedInARow = 0 // someone's there
        if (mode == Mode.NONE) return false
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        val dpad = event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        if (mode == Mode.UP_NEXT) {
            // Cinema's card has two buttons: Left / Right choose between them (OK acts on the chosen one).
            val btns = upButtons
            val lr = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            if (btns != null && lr) {
                if (event.action == KeyEvent.ACTION_DOWN) upPaint?.invoke(if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) UP_CREDITS else UP_NEXT)
                return true
            }
            // OK plays now; everything else (seeking, controls) goes on to the player.
            if (!ok) return false
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (btns != null && upSel == UP_CREDITS) { if (ended) host.close() else { dismissed = true; removePanel() } } // Credits
                else next?.let { advance(it, auto = false) }
            }
            return true
        }
        if (!ok && !dpad) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        main.removeCallbacks(idleClose)
        if (ended) main.postDelayed(idleClose, 90_000)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> select(row, col - 1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> select(row, col + 1)
            KeyEvent.KEYCODE_DPAD_UP -> select(row - 1, col)
            KeyEvent.KEYCODE_DPAD_DOWN -> select(row + 1, col)
            else -> if (event.repeatCount == 0) rows.getOrNull(row)?.getOrNull(col)?.let { actions[it]?.invoke() }
        }
        return true
    }

    fun onBack(): Boolean = when (mode) {
        Mode.NONE -> false
        Mode.UP_NEXT, Mode.MORE -> {
            if (ended) host.close() else { dismissed = true; removePanel() } // keep watching the credits
            true
        }
        Mode.STILL -> { host.close(); true }
    }

    // ---- panels ----

    private fun kit() = kit ?: PremiumKit(ctx).also { kit = it }

    private fun addPanel(v: View, lp: FrameLayout.LayoutParams, m: Mode) {
        removePanel(animate = false)
        mode = m
        panel = v
        host.setControlsVisible(false)
        host.overlayRoot.addView(v, lp)
        v.alpha = 0f
        v.translationY = Ui.S5.dp.toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(Ui.SHEET_IN).setInterpolator(Ui.EASE).start()
    }

    private fun removePanel(animate: Boolean = true) {
        countdown?.cancel()
        countdown = null
        val v = panel ?: run { mode = Mode.NONE; return }
        panel = null
        mode = Mode.NONE
        rows = emptyList(); actions = emptyMap()
        upButtons = null; upPaint = null; upSel = UP_NEXT
        if (animate) v.animate().alpha(0f).translationY(Ui.S4.dp.toFloat()).setDuration(Ui.SHEET_OUT).setInterpolator(Ui.EXIT)
            .withEndAction { (v.parent as? ViewGroup)?.removeView(v) }.start()
        else (v.parent as? ViewGroup)?.removeView(v)
    }

    private fun select(r: Int, c: Int) {
        if (rows.isEmpty()) return
        val nr = r.coerceIn(0, rows.size - 1)
        val nc = c.coerceIn(0, rows[nr].size - 1)
        rows.forEachIndexed { i, list -> list.forEachIndexed { j, v -> paint(v, i == nr && j == nc) } }
        row = nr; col = nc
    }

    private fun paint(v: View, on: Boolean) {
        val glow = v.tag as? CinemaGlow
        if (v is TextView) PostPlayStyle.pillState(kit(), v, on)
        else if (glow != null) {
            // Only a change of selection moves the glow (select() repaints every card).
            if (on != glow.shown) PlayerLook.border(v, glow, on)
            CinemaStyle.lift(v, on, z = false)
        } else {
            Ui.lift(v, on, Ui.LIFT_CARD, Ui.Z_FOCUS)
            (v.tag as? View)?.alpha = if (on) 1f else 0f // the card's ring
        }
    }

    private fun pill(label: String) = kit().label(label, Ui.T_BODY, kit().text, kit().semibold).apply {
        gravity = Gravity.CENTER
        setPadding(Ui.S5.dp, 0, Ui.S5.dp, 0)
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, 44.dp).apply { marginEnd = Ui.S3.dp }
    }

    private fun rounded(v: View, radiusDp: Int) {
        val r = radiusDp.dp.toFloat()
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, r)
        }
        v.clipToOutline = true
    }

    /** 10 s, only while the Up Next card is up ([tick] gets 0..1); at the end the next episode starts. */
    private fun startCountdown(n: PlayFlow.NextUp, tick: (Float) -> Unit) {
        this.countdown = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 10_000
            interpolator = LinearInterpolator()
            startDelay = Ui.SHEET_IN
            addUpdateListener { tick(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) { if (!cancelled && running && mode == Mode.UP_NEXT) advance(n, auto = true) }
            })
            start()
        }
    }

    // Cinema's Up Next pills ([UP_NEXT] left, [UP_CREDITS] right) and which one Left / Right has selected.
    private var upButtons: List<TextView>? = null
    private var upSel = UP_NEXT
    private var upPaint: ((Int) -> Unit)? = null

    /**
     * Dream Cinema's Up Next: a small horizontal card on the bottom-right margin line (about a third of the
     * width, a fifth of the height) while the credits play on full size. Left, the next episode's still with
     * a thin rainbow line filling along its foot as it counts down; right, "NEXT EPISODE", the
     * episode and its runtime in one line, and two compact pills: Play (Play's rainbow fade, selected from the start) and
     * Credits (dark glass; selected, pearl with the fading rainbow border). Left / Right choose, OK acts,
     * Back keeps the credits. Neutral smoked glass with a hairline edge, sliding in from the right.
     */
    private fun showUpNext(n: PlayFlow.NextUp, countdown: Boolean) {
        val k = kit()
        val r = n.request
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp, 10.dp, 14.dp, 10.dp)
            background = PlayerLook.sheet(ctx, 16)
            clipChildren = false; clipToPadding = false
        }
        val still = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Ui.alpha(k.text, 0.08f)) }
        rounded(still, 10)
        Net.image(ctx, still, n.still ?: r.backdrop)
        val line = CountdownBar((3 * density).toInt(), rainbow = true)
        still.foreground = line
        card.addView(still, LinearLayout.LayoutParams(UP_STILL_W.dp, (UP_STILL_W * 9 / 16).dp))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        col.addView(k.kicker("Next Episode").apply { setTextColor(CinemaStyle.textDim(ctx)) })
        // The episode in white, its runtime after it in cool gray (two lines at most at the column width, then an ellipsis).
        val ep = listOfNotNull("S${r.season} E${r.episode}", r.episodeTitle?.takeIf { it.isNotBlank() }).joinToString("  ·  ")
        val rt = if (n.runtimeMin > 0) "  ·  " + PlayerMeta.runtime(n.runtimeMin) else ""
        val name = android.text.SpannableString(ep + rt).apply {
            if (rt.isNotEmpty()) setSpan(android.text.style.ForegroundColorSpan(CinemaStyle.textDim(ctx)), ep.length, ep.length + rt.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        col.addView(k.label(name, 15f, k.text, k.semibold, lines = 2).apply { maxWidth = UP_TEXT_W.dp },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 5.dp })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        val ink = PlayerLook.ink(ctx)
        val radius = UP_PILL_H / 2
        fun pill(label: String) = k.label(label, 13.5f, k.text, k.semibold).apply { gravity = Gravity.CENTER; setPadding(14.dp, 0, 14.dp, 0) }
        // Play: Play's fill (white at rest, the rainbow fade while selected), its ink following.
        val playIcon = k.icon(R.drawable.ic_play).apply { setBounds(0, 0, 11.dp, 11.dp) }
        val play = pill("Play").apply {
            setPadding(12.dp, 0, 16.dp, 0)
            setCompoundDrawables(playIcon, null, null, null)
            compoundDrawablePadding = 7.dp
        }
        val rain = GradientDrawable().apply { cornerRadius = radius.dp.toFloat() }
        play.background = rain
        val fade = Rainbow.fade(play, rain) { c -> play.setTextColor(c); playIcon.setTint(c) }
        val credits = pill("Credits")
        val creditsGlow = CinemaStyle.glowDrawable(ctx, radiusDp = radius, haloDp = CinemaStyle.GLOW_HALO_SMALL).apply { haloAlpha = 0.44f }
        val pearl = android.graphics.drawable.LayerDrawable(arrayOf(creditsGlow, Ui.rounded(ctx, Rainbow.PLAY_REST, radius)))
        val glass = Ui.rounded(ctx, Ui.alpha(Ui.mix(PlayerLook.shade(ctx), k.text, 0.12f), 0.82f), radius, 1f, Ui.alpha(k.text, 0.18f))
        val paint: (Int) -> Unit = { sel ->
            upSel = sel
            fade.focus(sel == UP_NEXT)
            credits.background = if (sel == UP_CREDITS) pearl else glass
            PlayerLook.border(credits, creditsGlow, sel == UP_CREDITS)
            credits.setTextColor(if (sel == UP_CREDITS) ink else k.text)
            for ((i, b) in listOf(play, credits).withIndex()) {
                val s = if (i == sel) 1.05f else 1f
                b.animate().scaleX(s).scaleY(s).setDuration(CinemaStyle.FOCUS_MS).setInterpolator(Ui.EASE).start()
            }
        }
        row.addView(play, LinearLayout.LayoutParams(WRAP_CONTENT, UP_PILL_H.dp).apply { marginEnd = 8.dp })
        row.addView(credits, LinearLayout.LayoutParams(WRAP_CONTENT, UP_PILL_H.dp))
        col.addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 9.dp })
        card.addView(col, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 12.dp })
        upButtons = listOf(play, credits)
        upPaint = paint
        paint(UP_NEXT)
        addPanel(card, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = PremiumKit.EDGE.dp; bottomMargin = 56.dp
        }, Mode.UP_NEXT)
        // A soft slide in from the right (addPanel's rise replaced).
        card.animate().cancel()
        card.translationY = 0f
        card.translationX = 40.dp.toFloat()
        card.animate().alpha(1f).translationX(0f).setDuration(Ui.SHEET_IN).setInterpolator(Ui.EASE).start()
        if (countdown) startCountdown(n) { line.progress = it }
    }

    /**
     * More Like This: up to four cards with Watch Credits / Back to Browse. The credits keep rolling behind a
     * deep, smooth scrim (an even dim, heavier toward the bottom and the left where the words sit), so they
     * never fight the cards. The cards are sized so all of them sit between Home's gutters: the focused one,
     * with its lift and glow, is always whole on screen.
     */
    private fun showMore(r: PlaybackRequest) {
        val k = kit()
        val root = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
        root.addView(View(ctx).apply {
            // Plain gradients (no full-screen bitmap): over moving video their steps don't show.
            val shade = PlayerLook.shade(ctx)
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.ColorDrawable(Ui.alpha(shade, 0.52f)),
                GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Ui.alpha(shade, 0.94f), Ui.alpha(shade, 0.62f), Ui.alpha(shade, 0.18f), Color.TRANSPARENT)),
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.alpha(shade, 0.55f), Ui.alpha(shade, 0.22f), Color.TRANSPARENT)),
            ))
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val heading = if (r.type == "episode") "You've reached the end of ${r.title}" else "More Like This"
        col.addView(k.label(heading, Ui.T_HEADLINE, k.text, k.semibold, Ui.TRACK_TITLE),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = Ui.S4.dp })
        val cardsRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; isBaselineAligned = false }
        val cards = ArrayList<View>()
        val map = HashMap<View, () -> Unit>()
        // Every card between the gutters (Home's): at most MORE_W wide, narrower when they'd run past the right one.
        val page = ctx.resources.displayMetrics.widthPixels - 2 * PremiumKit.EDGE.dp
        val n = recs.size.coerceAtLeast(1)
        val cardW = minOf(MORE_W.dp, (page - (n - 1) * Ui.S4.dp) / n)
        val cardH = cardW * 9 / 16
        for ((i, t) in recs.withIndex()) {
            val cell = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
            val frame = FrameLayout(ctx)
            val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Ui.alpha(k.text, 0.08f)) }
            rounded(art, Ui.R_MD)
            Net.image(ctx, art, t.backdrop("w300"))
            frame.addView(art, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            // The card glows (halo and edge in the next hue).
            frame.background = CinemaStyle.glowDrawable(ctx, radiusDp = Ui.R_MD)
            frame.tag = frame.background
            cell.addView(frame, LinearLayout.LayoutParams(cardW, cardH))
            cell.addView(k.label(t.name, Ui.T_CAPTION, Ui.alpha(k.text, 0.9f), k.medium, lines = 2),
                LinearLayout.LayoutParams(cardW, WRAP_CONTENT).apply { topMargin = Ui.S2.dp })
            cardsRow.addView(cell, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (i < recs.size - 1) marginEnd = Ui.S4.dp })
            cards += frame
            map[frame] = { pick(r, t) }
        }
        col.addView(cardsRow, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = Ui.S5.dp })
        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        val credits = pill(if (ended) "Watch Again" else "Watch Credits")
        val browse = pill("Back to Browse")
        btnRow.addView(credits); btnRow.addView(browse)
        map[credits] = {
            if (ended) { host.engine.seekTo(0); host.engine.play(); ended = false; main.removeCallbacks(idleClose) }
            dismissed = true
            removePanel()
        }
        map[browse] = { host.close() }
        col.addView(btnRow, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(col, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = PremiumKit.EDGE.dp; bottomMargin = 56.dp
        })
        addPanel(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT), Mode.MORE)
        rows = listOf(cards, listOf(credits, browse)).filter { it.isNotEmpty() }
        actions = map
        select(0, 0)
    }

    /** A recommendation: movies play in Dream Player; shows open their Dream OS details page. */
    private fun pick(r: PlaybackRequest, t: Title) {
        if (t.type == "movie") {
            host.playNext(PlaybackRequest(
                title = t.name, type = "movie", tmdbId = t.id, year = t.year.take(4).toIntOrNull(),
                backdrop = PlayFlow.img(t.backdropPath, "w1280"), poster = PlayFlow.img(t.posterPath, "w342"),
                autoplayNext = r.autoplayNext,
            ))
            advanced = true
            removePanel(animate = false)
        } else {
            PlayFlow.openDetails(ctx, t)
            host.close()
        }
    }

    /**
     * Dream Cinema's "Are You Still Watching?" (Netflix's, in Cinema's type): the paused picture dimmed and
     * melting dark toward the left, and on the page's left line the show in small caps, the question in the
     * hero's big bold title, the episode under it, then Continue Watching and Back to Browse as Cinema pills
     * (glass at rest; selected, pearl with the fading rainbow border). Left / Right choose, OK acts, Back leaves.
     */
    private fun showStillWatchingCinema(r: PlaybackRequest) {
        val k = kit()
        val shade = PlayerLook.shade(ctx)
        val panel = FrameLayout(ctx).apply {
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.ColorDrawable(Ui.alpha(shade, 0.46f)),
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.alpha(shade, 0.86f), Ui.alpha(shade, 0.52f), Color.TRANSPARENT)),
            ))
            clipChildren = false; clipToPadding = false
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        col.addView(k.kicker(r.title).apply { setTextColor(Ui.alpha(k.text, 0.72f)) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = 12.dp })
        col.addView(k.label("Are You Still Watching?", CinemaStyle.T_HERO_TITLE, k.text, k.bold, -0.015f),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        if (r.season != null && r.episode != null) {
            val ep = listOfNotNull("S${r.season} E${r.episode}", r.episodeTitle?.takeIf { it.isNotBlank() }).joinToString("  ·  ")
            col.addView(k.label(ep, 16f, Ui.alpha(k.text, 0.78f), k.medium).apply { maxWidth = 560.dp },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 12.dp })
        }
        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        val cont = pill("Continue Watching").apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = 16.dp }
        val browse = pill("Back to Browse").apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = 0 }
        btnRow.addView(cont); btnRow.addView(browse)
        col.addView(btnRow, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 30.dp })
        panel.addView(col, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START).apply {
            leftMargin = CinemaStyle.GUTTER.dp
        })
        addPanel(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT), Mode.STILL)
        // A plain fade for the full-screen dim (addPanel's rise would show its edge); the words rise a touch.
        panel.animate().cancel()
        panel.translationY = 0f
        panel.animate().alpha(1f).setDuration(Ui.SHEET_IN).setInterpolator(Ui.EASE).start()
        col.alpha = 0f
        col.translationY = 10.dp.toFloat()
        col.animate().alpha(1f).translationY(0f).setStartDelay(120).setDuration(Ui.SHEET_IN + 120).setInterpolator(Ui.EASE).start()
        rows = listOf(listOf(cont, browse))
        actions = mapOf(
            cont to { PlayFlow.autoplayedInARow = 0; removePanel(); host.engine.play() },
            browse to { host.close() },
        )
        select(0, 0)
    }

    /** Developer preview of the question (PingReceiver --ez still true), over whatever is playing. */
    fun debugStillWatching() {
        val r = host.request ?: return
        if (!running || mode == Mode.STILL) return
        stillAsked = true
        showStillWatching(r)
    }

    /** "Are You Still Watching?": pauses and asks ([showStillWatchingCinema]). */
    private fun showStillWatching(r: PlaybackRequest) {
        host.engine.pause()
        showStillWatchingCinema(r)
    }

    /**
     * Cinema's countdown: a thin line filling along the foot of a view over the 10 s, set as its foreground
     * (so the view keeps its own size, and its rounded clip shapes the line's ends): the rainbow on a faint
     * track over the Up Next still, or soft ink over a filled pill. One small view redrawn per frame, only
     * while it counts.
     */
    private class CountdownBar(private val h: Int, private val rainbow: Boolean = false) : android.graphics.drawable.Drawable() {
        private val track = Paint().apply { color = if (rainbow) Color.argb(70, 255, 255, 255) else Color.argb(30, 0, 0, 0) }
        private val bar = Paint().apply { color = if (rainbow) Color.WHITE else Color.argb(120, 0, 0, 0) } // white: the shader at full strength

        override fun onBoundsChange(b: android.graphics.Rect) {
            // Over a still: the rainbow; over the Next Episode pill's own fill: soft ink.
            if (rainbow) bar.shader = android.graphics.LinearGradient(b.left.toFloat(), 0f, b.right.toFloat(), 0f, Rainbow.COLORS, null, android.graphics.Shader.TileMode.CLAMP)
        }
        var progress = 0f
            set(v) { field = v; invalidateSelf() }

        override fun draw(c: Canvas) {
            val b = bounds
            val top = (b.bottom - h).toFloat()
            c.drawRect(b.left.toFloat(), top, b.right.toFloat(), b.bottom.toFloat(), track)
            if (progress > 0f) c.drawRect(b.left.toFloat(), top, b.left + b.width() * progress.coerceIn(0f, 1f), b.bottom.toFloat(), bar)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private companion object {
        /** Cinema's Up Next pills, left to right. */
        const val UP_NEXT = 0
        const val UP_CREDITS = 1
        // Cinema's Up Next card (dp): still width (16:9), the text column's widest line, pill height.
        const val UP_STILL_W = 124
        const val UP_TEXT_W = 170
        const val UP_PILL_H = 32
        /** More Like This: the widest a card gets (dp); four fit between the gutters at about 200. */
        const val MORE_W = 224
    }
}
